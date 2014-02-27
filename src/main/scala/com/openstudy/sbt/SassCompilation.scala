package com.openstudy
package sbt

import java.lang.Runtime

import scala.collection.JavaConversions._
import java.io._

import _root_.sbt.{File => SbtFile, _}
import Keys.{baseDirectory, resourceDirectory, streams, target, _}

trait SassCompilation extends Compilation {
  val forceSassCompile = SettingKey[Boolean]("force-sass-compile")
  val compileSass = TaskKey[Unit]("compile-sass")

  def doSassCompile(streams:TaskStreams, baseDirectory: File, bucket:Option[String], force: Boolean): Unit =
    doSassCompile(streams, baseDirectory, bucket, force, Runtime.getRuntime, System.getenv().toMap)

  def doSassCompile(streams:TaskStreams, baseDirectory: File, bucket:Option[String], force: Boolean, runtime: Runtime, systemEnvironment: Map[String, String]): Unit = {
    streams.log.info("Compiling SASS files...")

    val environment =
      if (bucket.isDefined)
        systemEnvironment + ("asset_domain" -> bucket.getOrElse(""))
      else
        systemEnvironment

    val compassCompileCommand = {
      val compassCompile =  ("compass" :: "compile" :: "-e" :: "production" :: Nil).toArray
        
      if (force) {
        compassCompile :+ "--force"
      } else {
        compassCompile
      }
    }

    val process =
      runtime.exec(
        compassCompileCommand,
        environment.map { case (key, value) => key + "=" + value }.toArray,
        baseDirectory)
    val result = process.waitFor

    if (result != 0) {
      streams.log.error(
        scala.io.Source.fromInputStream(process.getErrorStream).mkString(""))
      throw new RuntimeException("SASS compilation failed with code " + result + ".")
    } else {
      streams.log.info("Done.")
    }
  }
}
