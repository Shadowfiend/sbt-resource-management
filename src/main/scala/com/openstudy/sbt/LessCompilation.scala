package com.openstudy
package sbt

import java.io._

import _root_.sbt.{File => SbtFile, _}
import Keys.{baseDirectory, resourceDirectory, streams, target, _}

trait LessCompilation extends Compilation {
  val compiledLessDirectory = SettingKey[Option[File]]("compiled-less-directory")
  val lessSources = TaskKey[Seq[File]]("less-sources")
  val cleanLess = TaskKey[Unit]("clean-less")
  val compileLess = TaskKey[Unit]("compile-less")

  class LessCompilationResult(info:PathInformation) extends CompilationResult {
    protected lazy val process = {
      runtime.exec(
        ("lessc" :: info.source :: info.target :: Nil).toArray,
        null, // inherit environment
        info.workingDirectory
      )
    }
  }

  def doLessCompile(streams:TaskStreams, baseDirectory:File, compiledLessDir:Option[File], lessSources:Seq[File]) = {
    compiledLessDir.map { compiledLessDir =>
      doProcessCompile(streams, baseDirectory, compiledLessDir, lessSources, "LESS", "css", new LessCompilationResult(_))
    }
  }

  def doLessClean(streams:TaskStreams, baseDiretory:File, compiledLessDir:Option[File], lessSources:Seq[File]) = {
    compiledLessDir.map { compiledLessDir =>
      streams.log.info("Cleaning " + lessSources.length + " generated CSS files.")

      val outdatedPaths = lessSources.foreach { source =>
        (compiledLessDir / (source.base + ".css")).delete
      }
    }
  }
}
