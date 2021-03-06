package benchq
package queue

import akka.actor._
import benchq.git.GitRepo
import benchq.influxdb.ResultsDb
import benchq.jenkins.ScalaJenkins
import benchq.model.Status._
import benchq.model._
import benchq.repo.ScalaBuildsRepo
import play.api.Logger
import play.api.libs.concurrent.Execution.Implicits.defaultContext

import scala.util.{Failure, Success, Try}

class TaskQueue(compilerBenchmarkTaskService: CompilerBenchmarkTaskService,
                benchmarkResultService: BenchmarkResultService,
                scalaBuildsRepo: ScalaBuildsRepo,
                scalaJenkins: ScalaJenkins,
                resultsDb: ResultsDb,
                knownRevisionService: KnownRevisionService,
                benchmarkService: BenchmarkService,
                lastExecutedBenchmarkService: LastExecutedBenchmarkService,
                gitRepo: GitRepo,
                system: ActorSystem,
                config: Config) {

  val queueActor = system.actorOf(QueueActor.props, "queue-actor")
  val checkNewCommitsActor = system.actorOf(CheckNewCommitsActor.props, "check-new-commits-actor")

  object QueueActor {
    case object PingQueue
    case class ScalaVersionAvailable(taskId: Long, artifactName: Try[Option[String]])
    case class ScalaBuildStarted(taskId: Long, res: Try[Unit])
    case class ScalaBuildFinished(taskId: Long, buildSucceeded: Try[Unit])
    case class TravisBuildFinished(sha: String, res: Try[Unit])
    case class BenchmarkStarted(taskId: Long, res: Try[Unit])
    case class BenchmarkFinished(taskId: Long, results: Try[List[BenchmarkResult]])
    case class ResultsSent(taskId: Long, res: Try[Unit])

    val props = Props(new QueueActor)
  }

  class QueueActor extends Actor {
    import QueueActor._
    def updateStatus(task: CompilerBenchmarkTask, newStatus: Status): Unit =
      compilerBenchmarkTaskService.update(task.id.get, task.copy(status = newStatus)(None))

    def ifSuccess[T](id: Long, res: Try[T])(f: (CompilerBenchmarkTask, T) => Unit): Unit = {
      compilerBenchmarkTaskService.findById(id) match {
        case Some(task) =>
          res match {
            case Failure(e) =>
              Logger.error(s"Action on task $id failed", e)
              updateStatus(task, RequestFailed(task.status, e.getMessage))
            case Success(t) =>
              f(task, t)
          }

        case None =>
          Logger.error(s"Could not find task for $id")
      }
    }

    def receive: Receive = {
      case PingQueue => // traverse entire queue, start jobs for actionable items
        val queue = compilerBenchmarkTaskService.byPriority(StatusCompanion.actionableCompanions)

        var canStartBenchmark = compilerBenchmarkTaskService.countByStatus(WaitForBenchmark) == 0

        // If there are multiple tasks waiting for the same Scala version, only count one
        var numRunningScalaBuilds =
          compilerBenchmarkTaskService
            .byIndex(Set(WaitForScalaBuild))
            .map(_.scalaVersion)
            .distinct
            .size
        def canStartScalaBuild =
          numRunningScalaBuilds < config.scalaJenkins.maxConcurrentScalaBuilds

        for (task <- queue; id = task.id.get) task.status match {
          case CheckScalaVersionAvailable =>
            updateStatus(task, WaitForScalaVersionAvailable)
            scalaBuildsRepo
              .checkBuildAvailable(task.scalaVersion)
              .onComplete(res => self ! ScalaVersionAvailable(id, res))

          case StartScalaBuild if canStartScalaBuild =>
            updateStatus(task, WaitForScalaBuild)
            numRunningScalaBuilds += 1
            scalaJenkins
              .startScalaBuild(task)
              .onComplete(res => self ! ScalaBuildStarted(id, res))

          case StartBenchmark if canStartBenchmark =>
            updateStatus(task, WaitForBenchmark)
            canStartBenchmark = false
            scalaJenkins
              .startBenchmark(task)
              .onComplete(res => self ! BenchmarkStarted(id, res))

          case SendResults =>
            updateStatus(task, WaitForSendResults)
            resultsDb
              .sendResults(task, benchmarkResultService.resultsForTask(id))
              .onComplete(res => self ! ResultsSent(id, res))

          case _ =>
        }

      case ScalaVersionAvailable(id, tryArtifactName) =>
        ifSuccess(id, tryArtifactName) { (task, artifactName) =>
          Logger.info(s"Search result for Scala build of ${task.scalaVersion}: $artifactName")
          val newStatus = artifactName match {
            case Some(artifact) =>
              // could pass on artifact name - currently we do another lookup in ScalaJenkins.startBenchmark
              StartBenchmark
            case None =>
              // Check if the Scala version is being built by some other task
              val versionBeingBuilt = compilerBenchmarkTaskService
                .byPriority(Set(StartScalaBuild, WaitForScalaBuild))
                .exists(t => t.id != task.id && t.scalaVersion == task.scalaVersion)
              if (versionBeingBuilt) WaitForScalaBuild
              else StartScalaBuild
          }
          updateStatus(task, newStatus)
        }
        self ! PingQueue

      case ScalaBuildStarted(id, tryRes) =>
        // If the build cannot be started, mark the task as RequestFailed. Otherwise wait for
        // the `ScalaBuildFinished` message triggered by the Jenkins webhook.
        ifSuccess(id, tryRes)((task, _) => {
          Logger.info(s"Started Scala build for task ${task.id}")
        })

      case ScalaBuildFinished(id, tryRes) =>
        ifSuccess(id, tryRes) { (task, _) =>
          Logger.info(s"Scala build finished for task ${task.id}")
          updateStatus(task, StartBenchmark)
          // Update other tasks that are waiting for this Scala version to be built
          compilerBenchmarkTaskService
            .byPriority(Set(WaitForScalaBuild))
            .filter(_.scalaVersion == task.scalaVersion)
            .foreach(task => updateStatus(task, StartBenchmark))
        }
        self ! PingQueue

      case TravisBuildFinished(sha, tryRes) =>
        val tasks = compilerBenchmarkTaskService
          .byIndex(Set(WaitForScalaBuild))
          .filter(t => t.scalaVersion.repo == config.scalaScalaRepo && t.scalaVersion.sha == sha)
        tasks match {
          case List(task) =>
            tryRes match {
              case Failure(e) =>
                Logger.error(s"Action on task ${task.id} failed", e)
                updateStatus(task, RequestFailed(task.status, e.getMessage))
                self ! PingQueue
              case Success(_) =>
                self ! ScalaBuildFinished(task.id.get, tryRes)
            }

          case _ =>
            Logger.info(s"No unique task for travis build ($sha): $tasks")
        }

      case BenchmarkStarted(id, tryRes) =>
        // If the benchmark cannot be started, mark the task as RequestFailed. Otherwise wait for
        // the `BenchmarkFinished` message triggered by the Benchmark runner.
        ifSuccess(id, tryRes)((task, _) => {
          Logger.info(s"Started benchmark job for task ${task.id}")
        })

      case BenchmarkFinished(id, tryResults) =>
        ifSuccess(id, tryResults) { (task, results) =>
          Logger.info(s"Benchmark job finished for task ${task.id}")
          benchmarkResultService.insertResults(results)
          updateStatus(task, SendResults)
        }
        self ! PingQueue

      case ResultsSent(id, tryResult) =>
        ifSuccess(id, tryResult) { (task, _) =>
          updateStatus(task, Done)
        }
    }
  }

  object CheckNewCommitsActor {
    import java.util.concurrent.TimeUnit.{MILLISECONDS => MS}

    case class Check(branch: Branch)

    val props = Props(new CheckNewCommitsActor)

    private def shouldExecute(benchmark: Benchmark, branch: Branch, newCommitSha: String): Boolean =
      benchmark.daily == 0 || {
        lastExecutedBenchmarkService.findLast(benchmark.id.get, branch) match {
          case Some(last) =>
            gitRepo.commitDateMillis(newCommitSha, fetch = false) match {
              case Some(newCommitTime) =>
                val daysSince = MS.toDays(newCommitTime - last.commitTime)
                Logger.info(
                  s"Last execution for benchmark ${benchmark.id} was ${last.sha} at ${last.commitTime}, new commit $newCommitSha at $newCommitTime, $daysSince days between, daily is ${benchmark.daily}")
                daysSince >= benchmark.daily

              case _ =>
                Logger.info(s"Could not get commit time for new commit $newCommitSha")
                true
            }

          case _ =>
            Logger.info(s"No last execution for benchmark ${benchmark.id} on $branch")
            true
        }
      }
  }

  class CheckNewCommitsActor extends Actor {
    import CheckNewCommitsActor._

    def receive: Receive = {
      case Check(branch) =>
        knownRevisionService.lastKnownRevision(branch) match {
          case Some(knownRevision) =>
            val newCommits = gitRepo.newMergeCommitsSince(knownRevision)
            Logger.info(s"Starting benchmarks for new commits in $branch: $newCommits")
            newCommits foreach { newCommit =>
              val defaultBenchmarks = benchmarkService.defaultBenchmarks(branch)
              val benchmarksToRun =
                defaultBenchmarks.filter(b => shouldExecute(b, branch, newCommit))
              if (benchmarksToRun.nonEmpty) {
                val task =
                  CompilerBenchmarkTask(
                    config.appConfig.defaultJobPriority,
                    model.Status.WaitForScalaBuild, // just wait for travis to build it
                    ScalaVersion(config.scalaScalaRepo, newCommit, Nil)(None),
                    benchmarksToRun
                  )(None)
                compilerBenchmarkTaskService.insert(task)
              }
            }
            if (newCommits.nonEmpty) {
              knownRevisionService.updateOrInsert(knownRevision.copy(revision = newCommits.head))
              queueActor ! QueueActor.PingQueue
            }

          case None =>
            Logger.error(s"Could not find last known revision for branch ${branch.entryName}")
        }
    }
  }
}
