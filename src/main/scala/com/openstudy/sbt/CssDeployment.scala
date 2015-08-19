package com.openstudy
package sbt

import java.io._

import _root_.sbt.{File => SbtFile, _}
import Keys.{baseDirectory, resourceDirectory, streams, target, _}

trait CssDeployment extends Deployment {
  val deployCss = TaskKey[Unit]("deploy-styles")

  def doCssDeploy(
      streams: TaskStreams,
      checksumInFilename: Boolean, gzipFiles: Boolean,
      bundleChecksums: Map[String,String],
      styleBundleVersions: File, compressedTarget: File,
      access: Option[String], secret: Option[String], defaultBucket: Option[String]) = {
    val bundles = (compressedTarget / "stylesheets" ** "*.css").get

    withAwsConfiguration(streams, access, secret, defaultBucket) { (defaultBucket, access, secret) =>
      withBucketMapping(bundles, defaultBucket, customBucketMap) { (bucketName, files) =>
        doDeploy(
          streams,
          checksumInFilename, gzipFiles,
          bundleChecksums,
          styleBundleVersions, compressedTarget,
          files, "text/css",
          bucketName, access, secret
        )
      }
    }
  }
}
