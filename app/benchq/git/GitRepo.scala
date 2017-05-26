package benchq
package git

import benchq.model.{Branch, KnownRevision}
import better.files._

import scala.sys.process._
import scala.util.Try

class GitRepo(config: Config) {
  import config.gitRepo._

  val repoUrl = "https://github.com/scala/scala.git"
  def checkoutDirectory = File(checkoutLocation)
  def checkoutDirectoryJ = checkoutDirectory.toJava

  private def cloneIfNonExisting(): Unit = {
    if (!checkoutDirectory.exists)
      Process(s"git clone $repoUrl ${checkoutDirectory.name}", checkoutDirectory.parent.toJava).!
  }

  private def fetchOrigin(): Unit = {
    cloneIfNonExisting()
    Process("git fetch -f origin --tags", checkoutDirectoryJ).!
  }

  def newMergeCommitsSince(knownRevision: KnownRevision): List[String] = {
    fetchOrigin()
    // --first-parent to pick only merge commits, and direct commits to the branch
    // http://stackoverflow.com/questions/10248137/git-how-to-list-commits-on-this-branch-but-not-from-merged-branches
    Process(
      s"git log --first-parent --pretty=format:%H ${knownRevision.revision}..origin/${knownRevision.branch.entryName}",
      checkoutDirectoryJ).lineStream.toList
  }

  def branchesContaining(sha: String): Try[List[Branch]] = {
    fetchOrigin()
    val originPrefix = "origin/"
    Try {
      // Throws an exception if `sha` is not known
      val containingBranches =
        Process(s"git branch -r --contains $sha", checkoutDirectoryJ).lineStream
          .map(_.trim)
          .collect({
            case s if s.startsWith(originPrefix) => s.substring(originPrefix.length)
          })
          .toSet
      Branch.sortedValues.filter(b => containingBranches(b.entryName))
    }
  }

  def isMerged(sha: String): Boolean =
    branchesContaining(sha).map(_.nonEmpty).getOrElse(false)

  def shaForTag(tag: String): Try[String] = {
    fetchOrigin()
    Try(Process(s"git rev-list -n 1 $tag", checkoutDirectoryJ).lineStream.head)
  }

  def tagForSha(sha: String): Option[String] = {
    fetchOrigin()
    Try(Process(s"git describe --tag --exact-match $sha", checkoutDirectoryJ).lineStream.head).toOption
  }
}
