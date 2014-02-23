package com.openstudy
package sbt

import java.io._

import _root_.sbt.{File => SbtFile, _}
import Keys.{baseDirectory, resourceDirectory, streams, target, _}

trait ScriptDeploying extends Deploying {
  val deployScripts = TaskKey[Unit]("deploy-scripts")

  def doScriptDeploy(streams:TaskStreams, checksumInFilename:Boolean, bundleChecksums:Map[String,String], scriptBundleVersions:File, compressedTarget:File, access:Option[String], secret:Option[String], defaultBucket:Option[String]) = {
    val bundles = (compressedTarget / "javascripts" ** "*.js").get

    withAwsConfiguration(streams, access, secret, defaultBucket) { (access, secret, defaultBucket) =>
      withBucketMapping(bundles, defaultBucket, customBucketMap) { (bucketName, files) =>
        doDeploy(streams, checksumInFilename, bundleChecksums, scriptBundleVersions, compressedTarget, files, "text/javascript", access, secret, bucketName)
      }
    }
  }
}
