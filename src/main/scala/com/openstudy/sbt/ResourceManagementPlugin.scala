package com.openstudy { package sbt {
  import _root_.sbt._

  trait ResourceManagementPlugin extends BasicScalaProject {
    lazy val compressScripts = task {
      log.info("Compressing scripts...")

      None
    }
    lazy val deployScripts = task {
      log.info("Deploying scripts to " + ""/* s3Bucket */ + "...")

      None
    } dependsOn(compressScripts)

    lazy val compressCss = task {
      log.info("Compressing CSS...")

      None
    }
    lazy val deployCss = task {
      log.info("Deploying CSS to " + ""/* s3Bucket */ + "...")

      None
    } dependsOn(compressCss)

    lazy val compressResources = task { None } dependsOn(compressScripts, compressCss)
    lazy val deployResources = task { None } dependsOn(deployScripts, deployCss)
  }
} }
