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
  describe("SassCompilation") {
    describe("should execute the correct compass command") {
      it("with force unspecified") {
        val mockTaskStreams = mock[TaskStreams]
        val mockBaseDirectory = mock[File]
        val mockRuntime = mock[Runtime]
        val mockProcess = mock[java.lang.Process]
        val mockLogger = mock[Logger]
        val environment: Map[String, String] = Map("bacon" -> "wakka", "apple" -> "2")

        val testSassCompilation = new SassCompilation {
          val runtime = mockRuntime
          val systemEnvironment = environment
        }

        when(mockTaskStreams.log).thenReturn(mockLogger)
        when(mockRuntime.exec(isA(classOf[Array[String]]), anyObject(), anyObject())).thenReturn(mockProcess)
        when(mockProcess.waitFor).thenReturn(0)

        testSassCompilation.doSassCompile(
          mockTaskStreams,
          mockBaseDirectory,
          bucket = None,
          force = false
        )

        verify(mockRuntime).exec(
          Array[String]("compass", "compile", "-e", "production"),
          environment.map { case (key, value) => key + "=" + value }.toArray,
          mockBaseDirectory
        )
      }

      it("with force on") {
        val mockTaskStreams = mock[TaskStreams]
        val mockBaseDirectory = mock[File]
        val mockRuntime = mock[Runtime]
        val mockProcess = mock[java.lang.Process]
        val mockLogger = mock[Logger]
        val environment: Map[String, String] = Map("bacon" -> "wakka", "apple" -> "2")

        val testSassCompilation = new SassCompilation {
          val runtime = mockRuntime
          val systemEnvironment = environment
        }

        when(mockTaskStreams.log).thenReturn(mockLogger)
        when(mockRuntime.exec(isA(classOf[Array[String]]), anyObject(), anyObject())).thenReturn(mockProcess)
        when(mockProcess.waitFor).thenReturn(0)

        testSassCompilation.doSassCompile(
          mockTaskStreams,
          mockBaseDirectory,
          bucket = None,
          force = true
        )

        verify(mockRuntime).exec(
          Array[String]("compass", "compile", "-e", "production", "--force"),
          environment.map { case (key, value) => key + "=" + value }.toArray,
          mockBaseDirectory
        )
      }
    }

    it("should set the asset_domain env variable if bucket is defined") {
      val mockTaskStreams = mock[TaskStreams]
      val mockBaseDirectory = mock[File]
      val mockRuntime = mock[Runtime]
      val mockProcess = mock[java.lang.Process]
      val mockLogger = mock[Logger]
      val environment: Map[String, String] = Map("bacon" -> "wakka", "apple" -> "2", "asset_domain" -> "bacon")

      val testSassCompilation = new SassCompilation {
        val runtime = mockRuntime
        val systemEnvironment = environment
      }

      when(mockTaskStreams.log).thenReturn(mockLogger)
      when(mockRuntime.exec(isA(classOf[Array[String]]), anyObject(), anyObject())).thenReturn(mockProcess)
      when(mockProcess.waitFor).thenReturn(0)

      testSassCompilation.doSassCompile(
        mockTaskStreams,
        mockBaseDirectory,
        bucket = Some("bacon"),
        force = true
      )

      verify(mockRuntime).exec(
        Array[String]("compass", "compile", "-e", "production", "--force"),
        environment.map { case (key, value) => key + "=" + value }.toArray,
        mockBaseDirectory
      )
    }

    it("should throw a RuntimeException if compass exits nonzero") {
      val mockTaskStreams = mock[TaskStreams]
      val mockBaseDirectory = mock[File]
      val mockRuntime = mock[Runtime]
      val mockProcess = mock[java.lang.Process]
      val mockLogger = mock[Logger]
      val environment: Map[String, String] = Map("bacon" -> "wakka", "apple" -> "2", "asset_domain" -> "bacon")

      val testSassCompilation = new SassCompilation {
        val runtime = mockRuntime
        val systemEnvironment = environment
      }

      when(mockTaskStreams.log).thenReturn(mockLogger)
      when(mockRuntime.exec(isA(classOf[Array[String]]), anyObject(), anyObject())).thenReturn(mockProcess)
      when(mockProcess.waitFor).thenReturn(1)

      intercept[RuntimeException] {
        testSassCompilation.doSassCompile(
          mockTaskStreams,
          mockBaseDirectory,
          bucket = Some("bacon"),
          true
        )
      }
    }
  }
}
