//: ----------------------------------------------------------------------------
//: Copyright (C) 2016 Verizon.  All Rights Reserved.
//:
//:   Licensed under the Apache License, Version 2.0 (the "License");
//:   you may not use this file except in compliance with the License.
//:   You may obtain a copy of the License at
//:
//:       http://www.apache.org/licenses/LICENSE-2.0
//:
//:   Unless required by applicable law or agreed to in writing, software
//:   distributed under the License is distributed on an "AS IS" BASIS,
//:   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
//:   See the License for the specific language governing permissions and
//:   limitations under the License.
//:
//: ----------------------------------------------------------------------------
package verizon.build

import BlockadeOps.TransitiveViolation
import sbt.Keys._
import sbt._
import verizon.build.blockadeio
import verizon.build.blockadeio._
import verizon.build.depgraph._
import scala.concurrent.duration._
import depgraph._
import scala.util.Try

object BlockadePlugin extends AutoPlugin { self =>

  object autoImport {
    import java.net.URI

    val blockadeEnforcementInterval = settingKey[Duration]("blockade-enforcement-interval")
    val blockadeCacheFile = settingKey[File]("blockade-cache-file")
    val blockadeUris = settingKey[Seq[URI]]("blockade-uris")
    val blockade = taskKey[Unit]("blockade")
    val blockadeDependencyGraphCrossProjectId = settingKey[ModuleID]("blockade-dependency-graph-cross-project-id")
    val blockadeFailTransitive = settingKey[Boolean]("blockade-fail-transitive")
  }

  import autoImport._

  /** sbt auto-plugin stuffs **/
  override def trigger = allRequirements
  override lazy val projectSettings = self.settings
  override lazy val globalSettings: Seq[Setting[_]] = Seq(
    blockadeUris := Nil,
    blockadeFailTransitive := false
  )

  /** actual plugin content **/
  import scala.Console.{GREEN, RESET, YELLOW}
  import scala.io.Source
  import scala.util.{Failure, Success, Try}

  private def dependenciesOK(name: String, transitive: Boolean = false): String =
    GREEN + s"[$name] All ${if (transitive) "transitive" else "direct"} dependencies are within current restrictions." + RESET

  private def writeCheckFile(f: File, period: Duration): Unit = {
    val contents = System.nanoTime + period.toNanos
    IO.write(f, contents.toString.getBytes("UTF-8"))
  }

  private def readCheckFile(f: File): Boolean =
    (for {
      a <- Try(Source.fromFile(f).mkString.toLong)
      b <- Try(Duration.fromNanos(a).toNanos)
    } yield b > System.nanoTime).getOrElse(true)

  private def flattenTrys[T](xs: Seq[Try[T]]): Try[Seq[T]] = {
    val (ss: Seq[Success[T]]@unchecked, fs: Seq[Failure[T]]@unchecked) =
      xs.partition(_.isSuccess)

    if (fs.isEmpty) Success(ss map (_.get))
    else Failure[Seq[T]](fs(0).exception) // Only keep the first failure
  }

  val moduleGraphSbtTask = Def.task {
    val update = sbt.Keys.update.value
    val root = blockadeDependencyGraphCrossProjectId.value
    val config = (sbt.Keys.configuration in Compile).value
    SbtUpdateReport.fromConfigurationReport(update.configuration(config.name).get, root)
  }

  def settings: Seq[Def.Setting[_]] = Seq(
    blockadeDependencyGraphCrossProjectId := {
      CrossVersion(Keys.scalaVersion.value, Keys.scalaBinaryVersion.value)(Keys.projectID.value)
    },
    blockadeCacheFile := target.value / "blockaded",
    blockadeEnforcementInterval := 30.minutes,
    blockadeUris := Seq.empty,
    skip in blockade := {
      val f = blockadeCacheFile.value
      if (f.exists) readCheckFile(f) else false
    },
    blockade := {
      val log = streams.value.log

      // If the project has not been blockaded recently, we attempt to blockade and display results.
      if (!(skip in blockade).value) {
        val parsedBlockadeItems: Try[Seq[Blockade]] = flattenTrys(blockadeUris.value.map(url => blockadeio.loadFromURI(url).flatMap(BlockadeOps.parseBlockade)))
        val deps: Seq[ModuleID] = (libraryDependencies in Compile).value
        val graph: ModuleGraph = GraphOps.pruneEvicted(moduleGraphSbtTask.value)
        parsedBlockadeItems.map((blockades: Seq[Blockade]) => BlockadeOps.analyseDeps(deps, blockades, graph)) match {
          case Failure(_: java.net.UnknownHostException) => ()

          case Failure(e) =>
            log.error(s"Unable to execute the specified blockades because an error occurred: $e")

          // If we succeed in parsing the Blockades, we evaluate the dependency graph and display the results.
          case Success((immediateOutcomes, transitiveViolations)) =>
            immediateOutcomes.toList match {
              case Nil =>
                writeCheckFile(blockadeCacheFile.value, blockadeEnforcementInterval.value)
                log.info(dependenciesOK(name.value))
              case list => {
                log.warn(showImmediateDepResults(name.value, list))
                if (list.exists(_._1.raisesError == true))
                  sys.error("One or more of the specified immediate dependencies are restricted.")
                else ()
              }
            }

            val showTransitiveViolation: TransitiveViolation => Unit =
              violation => log.warn(YELLOW + s"[${name.value}]" + showTransitiveDepResults(violation) + RESET)

            transitiveViolations match {
              case Nil =>
                log.info(dependenciesOK(name = name.value, transitive = true))
              case list@(violation +: _) =>
                // If blockadeFailTransitive is turned on show all violations, otherwise just show one
                if (blockadeFailTransitive.value) {
                  list.foreach(showTransitiveViolation)
                  if (list.exists(_.outcome.raisesError))
                    sys.error("One or more transitive dependencies are restricted.")
                } else showTransitiveViolation(violation)
            }
        }
      } else {
        // Do nothing since the project has been blockaded recently.
        ()
      }
    }
  )
}
