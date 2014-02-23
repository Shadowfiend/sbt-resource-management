package com.openstudy
package sbt

import java.io._

import _root_.sbt.{File => SbtFile, _}
import Keys.{baseDirectory, resourceDirectory, streams, target, _}

import com.yahoo.platform.yui.compressor._

trait ScriptCompressing extends Compressing {
  val compressScripts = TaskKey[Map[String,String]]("compress-scripts")
  val mashScripts = TaskKey[Unit]("mash-scripts")

  def doScriptCompress(streams:TaskStreams, checksumInFilename:Boolean, copyScripts:Unit, targetJsDirectory:File, compressedTarget:File, scriptBundle:File) = {
    doCompress(streams, checksumInFilename, List(targetJsDirectory), compressedTarget / "javascripts", scriptBundle, "js", { (fileContents, writer, reporter) =>
      val compressor =
        new JavaScriptCompressor(
          new StringReader(fileContents.mkString(";\n")),
          reporter)

      compressor.compress(writer,
        defaultCompressionOptions.lineBreakPos, defaultCompressionOptions.munge,
        defaultCompressionOptions.verbose, defaultCompressionOptions.preserveSemicolons,
        defaultCompressionOptions.disableOptimizations)
    })
  }

  def doScriptMash(streams:TaskStreams, checksumInFilename:Boolean, copyScripts:Unit, targetJsDirectory:File, compressedTarget:File, scriptBundle:File) = {
    doCompress(streams, checksumInFilename, List(targetJsDirectory), compressedTarget / "javascripts", scriptBundle, "js", { (fileContents, writer, reporter) =>
      val mashedScript = fileContents.mkString(";\n")

      writer.write(mashedScript, 0, mashedScript.length)
    })
  }
}
