package com.openstudy
package sbt

import java.io._

import _root_.sbt.{File => SbtFile, _}
import Keys.{baseDirectory, resourceDirectory, streams, target, _}

import com.yahoo.platform.yui.compressor._

trait CssCompressing extends Compressing {
  val compressCss = TaskKey[Map[String,String]]("compress-styles")

  def doCssCompress(streams:TaskStreams, checksumInFilename:Boolean, compileSass:Unit, styleDirectories:Seq[File], compressedTarget:File, styleBundle:File) = {
    doCompress(streams, checksumInFilename, styleDirectories, compressedTarget / "stylesheets", styleBundle, "css", { (fileContents, writer, reporter) =>
      val compressor =
        new CssCompressor(new StringReader(fileContents.mkString("")))

      compressor.compress(writer, defaultCompressionOptions.lineBreakPos)
    })
  }
}
