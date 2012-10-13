package com.openstudy { package sbt {
  import java.io._
  import java.math.BigInteger
  import java.nio.charset.Charset
  import java.security.MessageDigest

  import _root_.sbt.{File => SbtFile, _}
  import Keys.{baseDirectory, resourceDirectory, streams, target, _}

  import org.mozilla.javascript.{ErrorReporter, EvaluatorException}
  import com.yahoo.platform.yui.compressor._


  object ResourceManagementPlugin extends Plugin {
    case class PathInformation(source:String, target:String)
    abstract class CompileResult {
      protected val runtime = java.lang.Runtime.getRuntime

      protected def process : java.lang.Process

      private val result = process.waitFor

      val failed_? = result != 0

      val error =
        if (failed_?)
          scala.io.Source.fromInputStream(process.getErrorStream).mkString("")
        else
          ""
    }

    // The result of a CoffeeScript compile.
    class CsCompileResult(info:PathInformation) extends CompileResult {
      protected lazy val process = runtime.exec(
        ("coffee" :: "-o" :: info.target ::
                    "-c" :: info.source :: Nil).toArray)
    }
    class LessCompileResult(info:PathInformation) extends CompileResult {
      protected lazy val process = {
        runtime.exec(
        ("lessc" :: info.source :: info.target :: Nil).toArray)
      }
    }

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
    val defaultCompressionOptions = JSCompressionOptions("utf-8", -1, true, false, false, false)

    val ResourceCompile = config("resources")

    val webappResources = SettingKey[Seq[File]]("webapp-resources")

    val awsAccessKey = SettingKey[String]("aws-access-key")
    val awsSecretKey = SettingKey[String]("aws-secret-key")
    val awsS3Bucket = SettingKey[String]("aws-s3-bucket")
    val checksumInFilename = SettingKey[Boolean]("checksum-in-filename")
    val compiledCoffeeScriptDirectory = SettingKey[File]("compiled-coffee-script-directory")
    val compiledLessDirectory = SettingKey[File]("compiled-less-directory")
    val targetJavaScriptDirectory = SettingKey[File]("target-java-script-directory")
    val bundleDirectory = SettingKey[File]("bundle-directory")
    val scriptBundle = SettingKey[File]("javascript-bundle")
    val styleBundle = SettingKey[File]("stylesheet-bundle")
    val scriptBundleVersions = SettingKey[File]("javascript-bundle-versions")
    val styleBundleVersions = SettingKey[File]("stylesheet-bundle-versions")
    val compressedTarget = SettingKey[File]("compressed-target")

    val scriptDirectories = TaskKey[Seq[File]]("javascripts-directories")
    val styleDirectories = TaskKey[Seq[File]]("stylesheets-directories")
    val coffeeScriptSources = TaskKey[Seq[File]]("coffee-script-sources")
    val cleanCoffeeScript = TaskKey[Unit]("clean-coffee-script")
    val compileCoffeeScript = TaskKey[Unit]("compile-coffee-script")
    val copyScripts = TaskKey[Unit]("copy-scripts")
    val compileSass = TaskKey[Unit]("compile-sass")
    val lessSources = TaskKey[Seq[File]]("less-sources")
    val cleanLess = TaskKey[Unit]("clean-less")
    val compileLess = TaskKey[Unit]("compile-less")
    val compressScripts = TaskKey[Map[String,String]]("compress-scripts")
    val compressCss = TaskKey[Map[String,String]]("compress-styles")
    val deployScripts = TaskKey[Unit]("deploy-scripts")
    val deployCss = TaskKey[Unit]("deploy-styles")
    val compressResources = TaskKey[Unit]("compress-resources")
    val deployResources = TaskKey[Unit]("deploy-resources")
    val mashScripts = TaskKey[Unit]("mash-scripts")

    val customBucketMap = scala.collection.mutable.HashMap[String, List[String]]()

    def doCoffeeScriptClean(streams:TaskStreams, baseDiretory:File, compiledCsDir:File, csSources:Seq[File]) = {
      streams.log.info("Cleaning " + csSources.length + " generated JavaScript files.")

      val outdatedPaths = csSources.foreach { source =>
        (compiledCsDir / (source.base + ".js")).delete
      }
    }

    def doProcessCompile(streams:TaskStreams, baseDirectory:File, destinationDirectory:File, sources:Seq[File], filetype:String, targetExtension:String, compile:(PathInformation)=>CompileResult, targetIsDirectory:Boolean = false) = {
      def outdated_?(source:File) = {
        val target = destinationDirectory / (source.base + "." + targetExtension)

        source.lastModified() > target.lastModified()
      }
      val outdatedPaths = sources.collect {
        case file if outdated_?(file) =>
          PathInformation(
            IO.relativize(baseDirectory, file).get,
            if (targetIsDirectory)
              IO.relativize(baseDirectory, destinationDirectory).get
            else
              IO.relativize(baseDirectory, destinationDirectory / (file.base + "." + targetExtension)).get
          )
      }

      if (outdatedPaths.length > 0) {
        streams.log.info(
          "Compiling " + outdatedPaths.length + " " + filetype + " sources to " +
          destinationDirectory + "..."
        )

        val failures = outdatedPaths.collect {
          case pathInfo => compile(pathInfo)
        }.partition(_.failed_?)._1.map(_.error)

        if (failures.length != 0) {
          streams.log.error(failures.mkString("\n"))
          throw new RuntimeException(filetype + " compilation failed.")
        }
      }
    }

    def doCoffeeScriptCompile(streams:TaskStreams, baseDirectory:File, compiledCsDir:File, csSources:Seq[File]) = {
      doProcessCompile(streams, baseDirectory, compiledCsDir, csSources, "CoffeeScript", "js", new CsCompileResult(_), targetIsDirectory = true)
    }

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

    def doSassCompile(streams:TaskStreams, bucket:String) = {
      streams.log.info("Compiling SASS files...")

      val runtime = java.lang.Runtime.getRuntime
      val process =
        runtime.exec(
          ("compass" :: "compile" :: "-e" :: "production" :: "--force" :: Nil).toArray,
          ("asset_domain=" + bucket :: Nil).toArray)
      val result = process.waitFor

      if (result != 0) {
        streams.log.error(
          scala.io.Source.fromInputStream(process.getErrorStream).mkString(""))
        throw new RuntimeException("SASS compilation failed with code " + result + ".")
      } else {
        streams.log.info("Done.")
      }
    }

    def doLessClean(streams:TaskStreams, baseDiretory:File, compiledLessDir:File, lessSources:Seq[File]) = {
      streams.log.info("Cleaning " + lessSources.length + " generated CSS files.")

      val outdatedPaths = lessSources.foreach { source =>
        (compiledLessDir / (source.base + ".css")).delete
      }
    }

    def doLessCompile(streams:TaskStreams, baseDirectory:File, compiledLessDir:File, lessSources:Seq[File]) = {
      doProcessCompile(streams, baseDirectory, compiledLessDir, lessSources, "LESS", "css", new LessCompileResult(_))
    }

    def doCompress(streams:TaskStreams, checksumInFilename:Boolean, sourceDirectories:Seq[File], compressedTarget:File, bundle:File, extension:String, compressor:(Seq[String],BufferedWriter,ExceptionErrorReporter)=>Unit) : Map[String,String] = {
      if (bundle.exists) {
        try {
          val bundles = Map[String,List[String]]() ++
            IO.read(bundle, Charset.forName("UTF-8")).split("\n\n").flatMap { section =>
              val lines = section.split("\n").toList

              if (lines.length >= 1)
                Some(lines(0) -> lines.drop(1))
              else {
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
          case exception =>
            streams.log.error(exception.toString + "\n" + exception.getStackTrace.map(_.toString).mkString("\n"))

            throw new RuntimeException("Compression failed.")
        }
      } else {
        streams.log.warn("Couldn't find " + bundle.absolutePath + "; not generating any bundles.")

        Map()
      }
    }

    def doScriptCompress(streams:TaskStreams, checksumInFilename:Boolean, copyScripts:Unit, targetJsDirectory:File, compressedTarget:File, scriptBundle:File) = {
      doCompress(streams, checksumInFilename, List(targetJsDirectory), compressedTarget / "javascripts", scriptBundle, "js", { (fileContents, writer, reporter) =>
        val compressor =
          new JavaScriptCompressor(
            new StringReader(fileContents.mkString(";\n")),
            reporter)

        compressor.compress(writer,
          defaultCompressionOptions.lineBreakPos, defaultCompressionOptions.munge,
          defaultCompressionOptions.verbose, defaultCompressionOptions.preserveSemicolons,
          defaultCompressionOptions.disableOptimizations)
      })
    }
    def doScriptMash(streams:TaskStreams, checksumInFilename:Boolean, copyScripts:Unit, targetJsDirectory:File, compressedTarget:File, scriptBundle:File) = {
      doCompress(streams, checksumInFilename, List(targetJsDirectory), compressedTarget / "javascripts", scriptBundle, "js", { (fileContents, writer, reporter) =>
        val mashedScript = fileContents.mkString(";\n")

        writer.write(mashedScript, 0, mashedScript.length)
      })
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
            case e =>
              streams.log.error(e.getMessage + "\n" + e.getStackTrace.mkString("\n"))
              throw new RuntimeException("Failed to upload " + file)
          }
        }
      } catch {
        case exception =>
          streams.log.error(exception.toString + "\n" + exception.getStackTrace.map(_.toString).mkString("\n"))
          throw new RuntimeException("Deploy failed.")
      }
    }
    def withBucketMapping(bundles:Seq[File], defaultBucket:String, customBucketMap:Map[String, List[String]])(deployHandler:(String, Seq[File])=>Unit) = {
      val bundlesForDefaultBucket = bundles.filterNot((file) => customBucketMap.contains(file.getName))
      deployHandler(defaultBucket, bundlesForDefaultBucket)

      for {
        customBucketName <- customBucketMap.keys
        bucketFiles <- customBucketMap.get(customBucketName)
        bundlesForBucket = bundles.filter((file) => bucketFiles.contains(file.getName))
      } {
        deployHandler(defaultBucket, bundlesForDefaultBucket)
      }
    }
    def doScriptDeploy(streams:TaskStreams, checksumInFilename:Boolean, bundleChecksums:Map[String,String], scriptBundleVersions:File, compressedTarget:File, access:String, secret:String, defaultBucket:String) = {
      val bundles = (compressedTarget / "javascripts" ** "*.js").get

      withBucketMapping(bundles, defaultBucket, customBucketMap.toMap) { (bucketName, files) =>
        doDeploy(streams, checksumInFilename, bundleChecksums, scriptBundleVersions, compressedTarget, files, "text/javascript", access, secret, bucketName)
      }
    }
    def doCssDeploy(streams:TaskStreams, checksumInFilename:Boolean, bundleChecksums:Map[String,String], styleBundleVersions:File, compressedTarget:File, access:String, secret:String, bucket:String) = {
      doDeploy(streams, checksumInFilename, bundleChecksums, styleBundleVersions, compressedTarget, (compressedTarget / "stylesheets" ** "*.css").get, "text/css", access, secret, bucket)
    }

    val resourceManagementSettings = Seq(
      checksumInFilename in ResourceCompile := false,
      bundleDirectory in ResourceCompile <<= (resourceDirectory in Compile)(_ / "bundles"),
      scriptBundle in ResourceCompile <<= (bundleDirectory in ResourceCompile)(_ / "javascript.bundle"),
      styleBundle in ResourceCompile <<= (bundleDirectory in ResourceCompile)(_ / "stylesheet.bundle"),
      scriptBundleVersions in ResourceCompile <<= (bundleDirectory in ResourceCompile)(_ / "javascript-bundle-versions"),
      styleBundleVersions in ResourceCompile <<= (bundleDirectory in ResourceCompile)(_ / "stylesheet-bundle-versions"),
      compiledCoffeeScriptDirectory in ResourceCompile <<= target(_ / "compiled-coffee-script"),
      targetJavaScriptDirectory in ResourceCompile <<= target(_ / "javascripts"),
      compiledLessDirectory in ResourceCompile <<= (webappResources in Compile) { resources => (resources * "stylesheets").get.head },
      compressedTarget in ResourceCompile <<= target(_ / "compressed"),

      scriptDirectories in ResourceCompile <<= (webappResources in Compile) map { resources => (resources * "javascripts").get },
      styleDirectories in ResourceCompile <<= (webappResources in Compile) map { resources => (resources * "stylesheets").get },
      coffeeScriptSources in ResourceCompile <<= (webappResources in Compile) map { resources => (resources ** "*.coffee").get },
      lessSources in ResourceCompile <<= (webappResources in Compile) map { resources => (resources ** "*.less").get.filter(! _.name.startsWith("_")) },
      cleanCoffeeScript in ResourceCompile <<= (streams, baseDirectory, compiledCoffeeScriptDirectory in ResourceCompile, coffeeScriptSources in ResourceCompile) map doCoffeeScriptClean _,
      compileCoffeeScript in ResourceCompile <<= (streams, baseDirectory, compiledCoffeeScriptDirectory in ResourceCompile, coffeeScriptSources in ResourceCompile) map doCoffeeScriptCompile _,
      copyScripts in ResourceCompile <<= (streams, compileCoffeeScript in ResourceCompile, compiledCoffeeScriptDirectory in ResourceCompile, scriptDirectories in ResourceCompile, targetJavaScriptDirectory in ResourceCompile) map doScriptCopy _,
      compileSass in ResourceCompile <<= (streams, awsS3Bucket) map doSassCompile _,
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
