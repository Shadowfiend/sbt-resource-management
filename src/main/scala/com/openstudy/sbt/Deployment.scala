package com.openstudy
package sbt

import java.io._

import _root_.sbt.{File => SbtFile, _}
import Keys.{baseDirectory, resourceDirectory, streams, target, _}

trait Deployment {
  val deployResources = TaskKey[Unit]("deploy-resources")

  def customBucketMap: scala.collection.mutable.HashMap[String, List[String]]

  def doDeploy(streams:TaskStreams, checksumInFilename:Boolean, bundleChecksums:Map[String,String], bundleVersions:File, baseCompressedTarget:File, files:Seq[File], mimeType:String, bucket:String, access:Option[String], secret:Option[String]) = {
    try {
      val handler = new S3Handler(bucket, access, secret)

      IO.write(bundleVersions.asFile, "checksum-in-filename=" + checksumInFilename + "\n")
      for {
        file <- files
        bundle =
          (if (checksumInFilename)
            file.base.split("-").dropRight(1).mkString("-")
          else
            file.base)
        checksum <- bundleChecksums.get(bundle)
        relativePath <- IO.relativize(baseCompressedTarget, file)
      } yield {
        streams.log.info("  Deploying bundle " + bundle + " as " + relativePath + "...")

        try {
          val contents = IO.readBytes(file)
          handler.saveFile(mimeType, relativePath, contents)

          IO.append(bundleVersions, bundle + "=" + checksum + "\n")
        } catch {
          case e: Exception =>
            streams.log.error(e.getMessage + "\n" + e.getStackTrace.mkString("\n"))
            throw new RuntimeException("Failed to upload " + file)
        }
      }
    } catch {
      case exception: Exception =>
        streams.log.error(exception.toString + "\n" + exception.getStackTrace.map(_.toString).mkString("\n"))
        throw new RuntimeException("Deploy failed.")
    }
  }

  protected def withAwsConfiguration(streams: TaskStreams, access: Option[String], secret: Option[String], defaultBucket: Option[String])(deployHandler: (String,Option[String],Option[String])=>Unit) = {
    if (defaultBucket.isEmpty) {
      streams.log.error("To use AWS deployment, you must set awsS3Bucket := Some(\"your S3 bucket name\") in your sbt build.")

      throw new RuntimeException("Missing AWS info, aborting deploy. See previous errors for more information.")
    } else {
      deployHandler(defaultBucket.get, access, secret)
    }
  }

  protected def withBucketMapping(bundles:Seq[File], defaultBucket:String, customBucketMap:scala.collection.Map[String, List[String]])(deployHandler:(String, Seq[File])=>Unit) = {
    val bundlesForDefaultBucket = bundles.filterNot { (file) =>
      customBucketMap.exists { case (id, files) => files.contains(file.getName) }
    }
    deployHandler(defaultBucket, bundlesForDefaultBucket)

    for {
      customBucketName <- customBucketMap.keys
      bucketFiles <- customBucketMap.get(customBucketName)
      bundlesForBucket = bundles.filter(file => bucketFiles.contains(file.getName))
    } {
      deployHandler(customBucketName, bundlesForBucket)
    }
  }
}
