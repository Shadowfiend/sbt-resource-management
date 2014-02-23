package com.openstudy
package sbt

import java.io._
import java.math.BigInteger
import java.nio.charset.Charset
import java.security.MessageDigest

import _root_.sbt.{File => SbtFile, _}
import Keys.{baseDirectory, resourceDirectory, streams, target, _}

import org.mozilla.javascript.{ErrorReporter, EvaluatorException}

/**
 * Handles all JS errors by throwing an exception. This can then get caught
 * and turned into a Failure. in the masher.
 */
class ExceptionErrorReporter(streams:TaskStreams, filename:String) extends ErrorReporter {
  def throwExceptionWith(message:String, file:String, line:Int) = {
    streams.log.info("Compressor failed at: " + filename + ":" + line + "; said: " + message)
    throw new Exception("Compressor failed at: " + file + ":" + line + "; said: " + message)
  }

  def warning(message:String, file:String, line:Int, lineSource:String, lineOffset:Int) = {
    throwExceptionWith(message, file, line)
  }

  def error(message:String, file:String, line:Int, lineSource:String, lineOffset:Int) = {
    throwExceptionWith(message, file, line)
  }

  def runtimeError(message:String, file:String, line:Int, lineSource:String, lineOffset:Int) : EvaluatorException = {
    throwExceptionWith(message, file, line)
  }
}

case class JSCompressionOptions(
  charset:String, lineBreakPos:Int,
  munge:Boolean, verbose:Boolean,
  preserveSemicolons:Boolean, disableOptimizations:Boolean)

trait Compressing {
  val compressedTarget = SettingKey[File]("compressed-target")
  val compressResources = TaskKey[Unit]("compress-resources")

  val defaultCompressionOptions = JSCompressionOptions("utf-8", -1, true, false, false, false)

  def customBucketMap: scala.collection.mutable.HashMap[String, List[String]]

  def doCompress(streams:TaskStreams, checksumInFilename:Boolean, sourceDirectories:Seq[File], compressedTarget:File, bundle:File, extension:String, compressor:(Seq[String],BufferedWriter,ExceptionErrorReporter)=>Unit) : Map[String,String] = {
    if (bundle.exists) {
      try {
        val bundles = Map[String,List[String]]() ++
          IO.read(bundle, Charset.forName("UTF-8")).replaceAll("""(\n){3,}""","\n\n").split("\n\n").flatMap { section =>
            val lines = section.split("\n").toList

            if (lines.length >= 1) {
              // "bundlename->customBucketName"
              lines(0).split("->").toList match {
                case bundleId :: customBundleTarget =>
                  customBundleTarget.headOption.foreach { bundleTarget =>
                    val filesForBucket = customBucketMap.getOrElseUpdate(bundleTarget, List()) ++ List(bundleId + "." + extension)
                    customBucketMap.put(bundleTarget, filesForBucket)
                  }

                  Some(bundleId -> lines.drop(1))

                case _ =>
                  None
              }
            } else {
              streams.log.warn("Found a bundle with no name/content.")
              None
            }
          }

        def contentsForBundle(bundleName:String, filesAndBundles:Option[List[String]] = None) : List[String] = {
          (filesAndBundles orElse bundles.get(bundleName)).toList.flatMap { filesAndBundles =>
            filesAndBundles.flatMap { fileOrBundleName =>
              if (fileOrBundleName.contains(".")) {
                // If it contains a ., we consider it a filename.
                val matchingFiles = fileOrBundleName.split("/").foldLeft(sourceDirectories:PathFinder)(_ * _)
                for (file <- matchingFiles.get) yield {
                  IO.read(file, Charset.forName("UTF-8"))
                }
              } else {
                // With no ., we consider it a bundle name.
                contentsForBundle(fileOrBundleName)
              }
            }
          }
        }

        IO.delete(compressedTarget)
        streams.log.info("  Found " + bundles.size + " " + extension + " bundles.")
        for {
          (bundleName, filesAndBundles) <- bundles
        } yield {
          streams.log.info("    Bundling " + filesAndBundles.length + " files and bundles into bundle " + bundleName + "...")
          val contentsToCompress = contentsForBundle(bundleName)

          IO.createDirectory(compressedTarget)
          val stringWriter = new StringWriter
          val bufferedWriter = new BufferedWriter(stringWriter)
          compressor(contentsToCompress, bufferedWriter,
                     new ExceptionErrorReporter(streams, bundleName))

          bufferedWriter.flush()
          val compressedContents = stringWriter.toString()
          // Preliminary research shows this doesn't really produce the same output
          // as say the md5sum program, but we don't care, we just want a nice
          // filename.
          val digestBytes =
            MessageDigest.getInstance("MD5").digest(compressedContents.getBytes("UTF-8"))
          val checksum = new BigInteger(1, digestBytes).toString(16)

          val filename =
            if (checksumInFilename)
              bundleName + "-" + checksum
            else
              bundleName

          IO.writer(compressedTarget / (filename + "." + extension), "", Charset.forName("UTF-8"), false)(_.append(compressedContents))

          (bundleName, checksum)
        }
      } catch {
        case exception: Exception =>
          streams.log.error(exception.toString + "\n" + exception.getStackTrace.map(_.toString).mkString("\n"))

          throw new RuntimeException("Compression failed.")
      }
    } else {
      streams.log.warn("Couldn't find " + bundle.absolutePath + "; not generating any bundles.")

      Map()
    }
  }
}
