package com.openstudy
package sbt

import java.io._

import _root_.sbt.{File => SbtFile, _}
import Keys.{baseDirectory, resourceDirectory, streams, target, _}

case class PathInformation(source:String, target:String, workingDirectory: File)

abstract class CompilationResult {
  protected val runtime = java.lang.Runtime.getRuntime

  protected def process : java.lang.Process

  private val result = process.waitFor

  val failed_? = result != 0

  val error =
    if (failed_?)
      scala.io.Source.fromInputStream(process.getErrorStream).mkString("")
    else
      ""
}

trait Compilation {
  def doProcessCompile(streams:TaskStreams, baseDirectory:File, destinationDirectory:File, sources:Seq[File], filetype:String, targetExtension:String, compile:(PathInformation)=>CompilationResult, targetIsDirectory:Boolean = false) = {
    def outdated_?(source:File) = {
      val target = destinationDirectory / (source.base + "." + targetExtension)

      source.lastModified() > target.lastModified()
    }
    val outdatedPaths = sources.collect {
      case file if outdated_?(file) =>
        PathInformation(
          IO.relativize(baseDirectory, file).get,
          if (targetIsDirectory)
            IO.relativize(baseDirectory, destinationDirectory).get
          else
            IO.relativize(baseDirectory, destinationDirectory / (file.base + "." + targetExtension)).get,
          baseDirectory
        )
    }

    if (outdatedPaths.length > 0) {
      streams.log.info(
        "Compiling " + outdatedPaths.length + " " + filetype + " sources to " +
        destinationDirectory + "..."
      )

      val failures = outdatedPaths.collect {
        case pathInfo => compile(pathInfo)
      }.partition(_.failed_?)._1.map(_.error)

      if (failures.length != 0) {
        streams.log.error(failures.mkString("\n"))
        throw new RuntimeException(filetype + " compilation failed.")
      }
    }
  }
}
