package com.openstudy
package sbt

import java.lang.Runtime

import scala.collection.JavaConversions._
import java.io._

import _root_.sbt.{File => SbtFile, _}
import Keys.{baseDirectory, resourceDirectory, streams, target, _}

trait SassCompilation extends Compilation {
  val forceSassCompile = SettingKey[Boolean]("force-sass-compile")
  val productionSassCompile = SettingKey[Boolean]("production-sass-compile")
  val compileSass = TaskKey[Unit]("compile-sass")

  def runtime: java.lang.Runtime
  def systemEnvironment: Map[String, String]

  def doSassCompile(streams:TaskStreams, baseDirectory: File, bucket:Option[String], force: Boolean, production: Boolean): Unit = {
    val environment =
      if (bucket.isDefined)
        systemEnvironment + ("asset_domain" -> bucket.getOrElse(""))
      else
        systemEnvironment

    val compassCompileCommand = {
      var compassCompile =  ("compass" :: "compile" :: Nil).toArray

      if (production) {
        streams.log.debug("Production mode on")
        compassCompile = compassCompile ++ ("-e" :: "production" :: Nil)
      }

      if (force) {
        streams.log.debug("Force mode on")
        compassCompile = compassCompile :+ "--force"
      }

      streams.log.info(s"Compiling SASS files using: ${compassCompile.mkString(" ")}")

      compassCompile
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
