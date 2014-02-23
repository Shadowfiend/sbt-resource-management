package com.openstudy { package sbt {
  import scala.collection.JavaConversions._

  import java.io._
  import _root_.sbt.{File => SbtFile, _}
  import Keys.{baseDirectory, resourceDirectory, streams, target, _}

  import com.yahoo.platform.yui.compressor._

  object ResourceManagementPlugin extends Plugin with SassCompiling with LessCompiling with CoffeeScriptCompiling with ScriptCompressing {
    val ResourceCompile = config("resources")

    private val webappResourceAlias = SettingKey[Seq[File]]("webapp-resources")

    val awsAccessKey = SettingKey[Option[String]]("aws-access-key")
    val awsSecretKey = SettingKey[Option[String]]("aws-secret-key")
    val awsS3Bucket = SettingKey[Option[String]]("aws-s3-bucket")
    val checksumInFilename = SettingKey[Boolean]("checksum-in-filename")

    val targetJavaScriptDirectory = SettingKey[File]("target-java-script-directory")
    val bundleDirectory = SettingKey[File]("bundle-directory")
    val scriptBundle = SettingKey[File]("javascript-bundle")
    val styleBundle = SettingKey[File]("stylesheet-bundle")
    val scriptBundleVersions = SettingKey[File]("javascript-bundle-versions")
    val styleBundleVersions = SettingKey[File]("stylesheet-bundle-versions")

    val scriptDirectories = TaskKey[Seq[File]]("javascripts-directories")
    val styleDirectories = TaskKey[Seq[File]]("stylesheets-directories")
    val copyScripts = TaskKey[Unit]("copy-scripts")
    val compressCss = TaskKey[Map[String,String]]("compress-styles")
    val deployScripts = TaskKey[Unit]("deploy-scripts")
    val deployCss = TaskKey[Unit]("deploy-styles")
    val deployResources = TaskKey[Unit]("deploy-resources")

    val customBucketMap = scala.collection.mutable.HashMap[String, List[String]]()

    def doScriptCopy(streams:TaskStreams, coffeeScriptCompile:Unit, compiledCsDir:File, scriptDirectories:Seq[File], targetJSDir:File) = {
      def copyPathsForDirectory(directory:File) = {
        for {
          source <- (directory ** "*.*").get
          relativeComponents = IO.relativize(directory, source).get.split("/")
          target = relativeComponents.foldLeft(targetJSDir)(_ / _):File
            if source.lastModified() > target.lastModified()
        } yield {
          (source, target)
        }
      }

      val csCopyPaths = copyPathsForDirectory(compiledCsDir)
      streams.log.info("Copying " + csCopyPaths.length + " compiled CoffeeScript files...")
      IO.copy(csCopyPaths, true)

      val scriptCopyPaths =
        scriptDirectories.foldLeft(List[(File,File)]())(_ ++ copyPathsForDirectory(_))
      streams.log.info("Copying " + scriptCopyPaths.length + " JavaScript files...")
      IO.copy(scriptCopyPaths, true)
    }

    def doCssCompress(streams:TaskStreams, checksumInFilename:Boolean, compileSass:Unit, styleDirectories:Seq[File], compressedTarget:File, styleBundle:File) = {
      doCompress(streams, checksumInFilename, styleDirectories, compressedTarget / "stylesheets", styleBundle, "css", { (fileContents, writer, reporter) =>
        val compressor =
          new CssCompressor(new StringReader(fileContents.mkString("")))

        compressor.compress(writer, defaultCompressionOptions.lineBreakPos)
      })
    }

    def doDeploy(streams:TaskStreams, checksumInFilename:Boolean, bundleChecksums:Map[String,String], bundleVersions:File, baseCompressedTarget:File, files:Seq[File], mimeType:String, access:String, secret:String, bucket:String) = {
      try {
        val handler = new S3Handler(access, secret, bucket)

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
    private def withAwsConfiguration(streams: TaskStreams, access: Option[String], secret: Option[String], defaultBucket: Option[String])(deployHandler: (String,String,String)=>Unit) = {
      val abort_? = access.isEmpty || secret.isEmpty || defaultBucket.isEmpty
      if (access.isEmpty)
        streams.log.error("To use AWS deployment, you must set awsAccessKey := Some(\"your AWS access key\") in your sbt build.")
      if (secret.isEmpty)
        streams.log.error("To use AWS deployment, you must set awsSecretKey := Some(\"your AWS secret key\") in your sbt build.")
      if (defaultBucket.isEmpty)
        streams.log.error("To use AWS deployment, you must set awsS3Bucket := Some(\"your S3 bucket name\") in your sbt build.")

      if (abort_?)
        throw new RuntimeException("Missing AWS info, aborting deploy. See previous errors for more information.")
      else
        deployHandler(access.get, secret.get, defaultBucket.get)
    }
    def withBucketMapping(bundles:Seq[File], defaultBucket:String, customBucketMap:scala.collection.Map[String, List[String]])(deployHandler:(String, Seq[File])=>Unit) = {
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
    def doScriptDeploy(streams:TaskStreams, checksumInFilename:Boolean, bundleChecksums:Map[String,String], scriptBundleVersions:File, compressedTarget:File, access:Option[String], secret:Option[String], defaultBucket:Option[String]) = {
      val bundles = (compressedTarget / "javascripts" ** "*.js").get

      withAwsConfiguration(streams, access, secret, defaultBucket) { (access, secret, defaultBucket) =>
        withBucketMapping(bundles, defaultBucket, customBucketMap) { (bucketName, files) =>
          doDeploy(streams, checksumInFilename, bundleChecksums, scriptBundleVersions, compressedTarget, files, "text/javascript", access, secret, bucketName)
        }
      }
    }
    def doCssDeploy(streams:TaskStreams, checksumInFilename:Boolean, bundleChecksums:Map[String,String], styleBundleVersions:File, compressedTarget:File, access:Option[String], secret:Option[String], defaultBucket:Option[String]) = {
      val bundles = (compressedTarget / "stylesheets" ** "*.css").get

      withAwsConfiguration(streams, access, secret, defaultBucket) { (access, secret, defaultBucket) =>
        withBucketMapping(bundles, defaultBucket, customBucketMap) { (bucketName, files) =>
          doDeploy(streams, checksumInFilename, bundleChecksums, styleBundleVersions, compressedTarget, files, "text/css", access, secret, bucketName)
        }
      }
    }

    val resourceManagementSettings = Seq(
      checksumInFilename in ResourceCompile := false,

      awsAccessKey := None,
      awsSecretKey := None,
      awsS3Bucket := None,
      forceSassCompile := false,

      bundleDirectory in ResourceCompile <<= (resourceDirectory in Compile)(_ / "bundles"),
      scriptBundle in ResourceCompile <<= (bundleDirectory in ResourceCompile)(_ / "javascript.bundle"),
      styleBundle in ResourceCompile <<= (bundleDirectory in ResourceCompile)(_ / "stylesheet.bundle"),
      scriptBundleVersions in ResourceCompile <<= (bundleDirectory in ResourceCompile)(_ / "javascript-bundle-versions"),
      styleBundleVersions in ResourceCompile <<= (bundleDirectory in ResourceCompile)(_ / "stylesheet-bundle-versions"),
      compiledCoffeeScriptDirectory in ResourceCompile <<= target(_ / "compiled-coffee-script"),
      targetJavaScriptDirectory in ResourceCompile <<= target(_ / "javascripts"),
      compiledLessDirectory in ResourceCompile <<= (webappResourceAlias in Compile) { resources => (resources * "stylesheets").get.headOption },
      compressedTarget in ResourceCompile <<= target(_ / "compressed"),

      scriptDirectories in ResourceCompile <<= (webappResourceAlias in Compile) map { resources => (resources * "javascripts").get },
      styleDirectories in ResourceCompile <<= (webappResourceAlias in Compile) map { resources => (resources * "stylesheets").get },
      coffeeScriptSources in ResourceCompile <<= (webappResourceAlias in Compile) map { resources => (resources ** "*.coffee").get },
      lessSources in ResourceCompile <<= (webappResourceAlias in Compile) map { resources => (resources ** "*.less").get.filter(! _.name.startsWith("_")) },
      cleanCoffeeScript in ResourceCompile <<= (streams, baseDirectory, compiledCoffeeScriptDirectory in ResourceCompile, coffeeScriptSources in ResourceCompile) map doCoffeeScriptClean _,
      compileCoffeeScript in ResourceCompile <<= (streams, baseDirectory, compiledCoffeeScriptDirectory in ResourceCompile, coffeeScriptSources in ResourceCompile) map doCoffeeScriptCompile _,
      copyScripts in ResourceCompile <<= (streams, compileCoffeeScript in ResourceCompile, compiledCoffeeScriptDirectory in ResourceCompile, scriptDirectories in ResourceCompile, targetJavaScriptDirectory in ResourceCompile) map doScriptCopy _,
      compileSass in ResourceCompile <<= (streams, baseDirectory, awsS3Bucket, forceSassCompile) map doSassCompile _,
      cleanLess in ResourceCompile <<= (streams, baseDirectory, compiledLessDirectory in ResourceCompile, lessSources in ResourceCompile) map doLessClean _,
      compileLess in ResourceCompile <<= (streams, baseDirectory, compiledLessDirectory in ResourceCompile, lessSources in ResourceCompile) map doLessCompile _,
      compressScripts in ResourceCompile <<= (streams, checksumInFilename in ResourceCompile, copyScripts in ResourceCompile, targetJavaScriptDirectory in ResourceCompile, compressedTarget in ResourceCompile, scriptBundle in ResourceCompile) map doScriptCompress _,
      compressCss in ResourceCompile <<= (streams, checksumInFilename in ResourceCompile, compileSass in ResourceCompile, styleDirectories in ResourceCompile, compressedTarget in ResourceCompile, styleBundle in ResourceCompile) map doCssCompress _,
      deployScripts in ResourceCompile <<= (streams, checksumInFilename in ResourceCompile, compressScripts in ResourceCompile, scriptBundleVersions in ResourceCompile, compressedTarget in ResourceCompile, awsAccessKey, awsSecretKey, awsS3Bucket) map doScriptDeploy _,
      deployCss in ResourceCompile <<= (streams, checksumInFilename in ResourceCompile, compressCss in ResourceCompile, styleBundleVersions in ResourceCompile, compressedTarget in ResourceCompile, awsAccessKey, awsSecretKey, awsS3Bucket) map doCssDeploy _,

      compressResources in ResourceCompile <<= (compressScripts in ResourceCompile, compressCss in ResourceCompile) map { (thing, other) => },
      deployResources in ResourceCompile <<= (deployScripts in ResourceCompile, deployCss in ResourceCompile) map { (_, _) => },

      mashScripts in ResourceCompile <<= (streams, checksumInFilename in ResourceCompile, copyScripts in ResourceCompile, targetJavaScriptDirectory in ResourceCompile, compressedTarget in ResourceCompile, scriptBundle in ResourceCompile) map doScriptMash _,
      watchSources <++= (coffeeScriptSources in ResourceCompile, scriptDirectories in ResourceCompile) map {
        (csSources, scriptDirectories) => csSources ++ scriptDirectories
      }
    )
  }
} }
