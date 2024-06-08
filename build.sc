import mill._
import mill.scalalib._
import mill.scalalib.publish._

object mailutil extends RootModule with ScalaModule with PublishModule {

  val JakartaMailVersion = "2.0.1"

  override def scalaVersion = "3.3.3"

 def scalacOptions = T {
   super.scalacOptions() ++ Seq(/*"-explain",*/"-deprecation")
 }

  override def artifactName = "mailutil"
  override def publishVersion = T{"0.0.4"}
  override def pomSettings    = T{
    PomSettings(
      description = "Utilities for conveniently working with e-mail from Scala",
      organization = "com.mchange",
      url = "https://github.com/swaldman/mailutil",
      licenses = Seq(License.`Apache-2.0`),
      versionControl = VersionControl.github("swaldman", "mailutil"),
      developers = Seq(
	Developer("swaldman", "Steve Waldman", "https://github.com/swaldman")
      )
    )
  }

  override def ivyDeps = T{
    super.ivyDeps() ++ Agg(
      ivy"com.lihaoyi::os-lib:0.9.3",
      ivy"com.sun.mail:jakarta.mail:${JakartaMailVersion}",
      ivy"com.sun.mail:smtp:${JakartaMailVersion}",
      ivy"com.mchange::conveniences:0.0.4"
    )
  }
}


