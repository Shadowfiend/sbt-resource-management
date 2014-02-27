package com.openstudy.sbt

import java.lang.Runtime
import scala.collection.JavaConversions._

import org.scalatest._
  import mock._

import org.mockito.Mockito._
import org.mockito.Matchers._

import java.io._
import _root_.sbt.{File => SbtFile, _}
import Keys.{baseDirectory, resourceDirectory, streams, target, _}

class SassCompilationSpec extends FunSpec with MockitoSugar {
  val testSassCompilation = new SassCompilation {}

  describe("SassCompilation") {
    it("should execute the correct compass command with force off") {
      val mockTaskStreams = mock[TaskStreams]
      val mockBaseDirectory = mock[File]
      val mockRuntime = mock[Runtime]
      val mockProcess = mock[java.lang.Process]
      val mockLogger = mock[Logger]
      val enviroment: Map[String, String] = Map.empty

      when(mockTaskStreams.log).thenReturn(mockLogger)
      when(mockRuntime.exec(isA(classOf[Array[String]]), anyObject(), anyObject())).thenReturn(mockProcess)
      when(mockProcess.waitFor).thenReturn(0)

      testSassCompilation.doSassCompile(mockTaskStreams, mockBaseDirectory, None, false, mockRuntime, enviroment)

      verify(mockRuntime).exec(
        Array[String]("compass", "compile", "-e", "production"),
        enviroment.map { case (key, value) => key + "=" + value }.toArray,
        mockBaseDirectory
      )
    }

    it("should execute the correct compass command with force on") {
      val mockTaskStreams = mock[TaskStreams]
      val mockBaseDirectory = mock[File]
      val mockRuntime = mock[Runtime]
      val mockProcess = mock[java.lang.Process]
      val mockLogger = mock[Logger]
      val enviroment: Map[String, String] = Map.empty

      when(mockTaskStreams.log).thenReturn(mockLogger)
      when(mockRuntime.exec(isA(classOf[Array[String]]), anyObject(), anyObject())).thenReturn(mockProcess)
      when(mockProcess.waitFor).thenReturn(0)

      testSassCompilation.doSassCompile(mockTaskStreams, mockBaseDirectory, None, true, mockRuntime, enviroment)

      verify(mockRuntime).exec(
        Array[String]("compass", "compile", "-e", "production", "--force"),
        enviroment.map { case (key, value) => key + "=" + value }.toArray,
        mockBaseDirectory
      )
    }

    it("should set the asset_domain env variable if bucket is defined") {
      val mockTaskStreams = mock[TaskStreams]
      val mockBaseDirectory = mock[File]
      val mockRuntime = mock[Runtime]
      val mockProcess = mock[java.lang.Process]
      val mockLogger = mock[Logger]
      val enviroment: Map[String, String] = Map("asset_domain" -> "bacon")

      when(mockTaskStreams.log).thenReturn(mockLogger)
      when(mockRuntime.exec(isA(classOf[Array[String]]), anyObject(), anyObject())).thenReturn(mockProcess)
      when(mockProcess.waitFor).thenReturn(0)

      testSassCompilation.doSassCompile(mockTaskStreams, mockBaseDirectory, Some("bacon"), true, mockRuntime, enviroment)

      verify(mockRuntime).exec(
        Array[String]("compass", "compile", "-e", "production", "--force"),
        enviroment.map { case (key, value) => key + "=" + value }.toArray,
        mockBaseDirectory
      )
    }

    it("should throw a RuntimeException if compass exits nonzero") {
      val mockTaskStreams = mock[TaskStreams]
      val mockBaseDirectory = mock[File]
      val mockRuntime = mock[Runtime]
      val mockProcess = mock[java.lang.Process]
      val mockLogger = mock[Logger]
      val enviroment: Map[String, String] = Map("asset_domain" -> "bacon")

      when(mockTaskStreams.log).thenReturn(mockLogger)
      when(mockRuntime.exec(isA(classOf[Array[String]]), anyObject(), anyObject())).thenReturn(mockProcess)
      when(mockProcess.waitFor).thenReturn(1)

      intercept[RuntimeException] {
        testSassCompilation.doSassCompile(mockTaskStreams, mockBaseDirectory, Some("bacon"), true, mockRuntime, enviroment)
      }
    }
  }
}
