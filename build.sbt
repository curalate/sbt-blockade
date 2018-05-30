import sbt._
import sbt.Keys._

organization := "com.curalate"

name := "sbt-blockade"

scalacOptions ++= Seq("-deprecation", "-feature", "-language:implicitConversions")

sbtPlugin := true

credentials += Credentials(Path.userHome / ".sbt" / "credentials")

// Uncomment and reimport the SBT project in IntelliJ for easier 2.12 code editing
//scalaVersion := "2.12.5"

crossSbtVersions := Vector("0.13.15", "1.1.5")

resolvers ++= Seq(
  "Curalate Ivy" at "https://maven.curalate.com/content/groups/ivy",
  "Curalate Maven" at "https://maven.curalate.com/content/groups/omnibus"
)

ScriptedPlugin.scriptedSettings

scriptedLaunchOpts ++= Seq(
  "-Xmx1024M",
  "-Dplugin.version=" + version.value,
  "-Dscripted=true"
)

scriptedBufferLog := false

fork := true

licenses := Seq("Apache-2.0" -> url("https://www.apache.org/licenses/LICENSE-2.0.html"))

homepage := Some(url("https://github.com/curalate/sbt-blockade"))

scmInfo := Some(ScmInfo(url("https://github.com/curaslate/sbt-blockade"),
                            "git@github.com:curalate/sbt-blockade.git"))

// To sync with Maven central, you need to supply the following information:
pomExtra in Global := {
  <developers>
    <developer>
      <id>timperrett</id>
      <name>Timothy Perrett</name>
      <url>github.com/timperrett</url>
    </developer>
  </developers>
}

pomPostProcess := { identity }

libraryDependencies += {
  CrossVersion.partialVersion(scalaVersion.value) match {
    case Some((2, 10)) => "net.liftweb" %% "lift-json" % "2.5.1"
    case _ => "net.liftweb" %% "lift-json" % "3.2.0"
  }
}

libraryDependencies ++= Seq(
  "org.scalatest" %% "scalatest" % "3.0.1" % "test"
)

addCommandAlias("xbuild", ";^compile")
addCommandAlias("validate", ";^test;^scripted")
