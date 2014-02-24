package com.openstudy
package sbt

import java.io._

import _root_.sbt.{File => SbtFile, _}
import Keys.{baseDirectory, resourceDirectory, streams, target, _}

trait CoffeeScriptCompilation extends Compilation {
  val compiledCoffeeScriptDirectory = SettingKey[File]("compiled-coffee-script-directory")
  val coffeeScriptSources = TaskKey[Seq[File]]("coffee-script-sources")
  val cleanCoffeeScript = TaskKey[Unit]("clean-coffee-script")
  val compileCoffeeScript = TaskKey[Unit]("compile-coffee-script")

  // The result of a CoffeeScript compile.
  class CsCompileResult(info:PathInformation) extends CompileResult {
    protected lazy val process = runtime.exec(
      ("coffee" :: "-o" :: info.target ::
                  "-c" :: info.source :: Nil).toArray,
      null, // inherit environment
      info.workingDirectory
    )
  }

  def doCoffeeScriptClean(streams:TaskStreams, baseDiretory:File, compiledCsDir:File, csSources:Seq[File]) = {
    streams.log.info("Cleaning " + csSources.length + " generated JavaScript files.")

    val outdatedPaths = csSources.foreach { source =>
      (compiledCsDir / (source.base + ".js")).delete
    }
  }

  def doCoffeeScriptCompile(streams:TaskStreams, baseDirectory:File, compiledCsDir:File, csSources:Seq[File]) = {
    doProcessCompile(streams, baseDirectory, compiledCsDir, csSources, "CoffeeScript", "js", new CsCompileResult(_), targetIsDirectory = true)
  }
}
