package com.openstudy { package sbt {
  import java.io._
  import java.nio.charset.Charset

  import _root_.sbt.{File => SbtFile, _}
  import Keys.{baseDirectory, resourceDirectory, streams, target, _}

  import org.mozilla.javascript.{ErrorReporter, EvaluatorException}
  import com.yahoo.platform.yui.compressor._


  object ResourceManagementPlugin extends Plugin {
    // The result of a CoffeeScript compile.
    class CsCompileResult(pathToCs:String, pathToJs:String) {
      private val runtime = java.lang.Runtime.getRuntime

      private val process = runtime.exec(
        ("coffee" :: "-o" :: pathToJs ::
                    "-c" :: pathToCs :: Nil).toArray)

      private val result = process.waitFor

      val failed_? = result != 0

      val error =
        if (failed_?)
          scala.io.Source.fromInputStream(process.getErrorStream).mkString("")
        else
          ""
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
    val bundleDirectory = SettingKey[File]("bundle-directory")
    val scriptBundle = SettingKey[File]("javascript-bundle")
    val styleBundle = SettingKey[File]("stylesheet-bundle")
    val scriptBundleVersions = SettingKey[File]("javascript-bundle-versions")
    val styleBundleVersions = SettingKey[File]("stylesheet-bundle-versions")
    val compressedTarget = SettingKey[File]("compressed-target")

    val scriptDirectories = TaskKey[Seq[File]]("javascripts-directories")
    val styleDirectories = TaskKey[Seq[File]]("stylesheets-directories")
    val coffeeScriptSources = TaskKey[Seq[File]]("coffee-script-sources")
    val compileCoffeeScript = TaskKey[Unit]("compile-coffee-script")
    val compileSass = TaskKey[Unit]("compile-sass")
    val compressScripts = TaskKey[Unit]("compress-scripts")
    val compressCss = TaskKey[Unit]("compress-styles")
    val deployScripts = TaskKey[Unit]("deploy-scripts")
    val deployCss = TaskKey[Unit]("deploy-styles")
    val compressResources = TaskKey[Unit]("deploy-resources")
    val deployResources = TaskKey[Unit]("deploy-resources")
    val mashScripts = TaskKey[Unit]("mash-scripts")

    case class PathInformation(source:String, target:String)
    def doCoffeeScriptCompile(streams:TaskStreams, baseDirectory:File, webappResources:Seq[File], csSources:Seq[File]) = {
      val chosenDirectory = webappResources.head / "javascripts"

      def outdated_?(source:File) = {
        val target = chosenDirectory / (source.base + ".js")

        source.lastModified() > target.lastModified()
      }
      val outdatedPaths = csSources.collect {
        case file if outdated_?(file) =>
          PathInformation(
            IO.relativize(baseDirectory, file).get,
            IO.relativize(baseDirectory, chosenDirectory).get
          )
      }

      if (outdatedPaths.length > 0) {
        streams.log.info(
          "Compiling " + outdatedPaths.length + " CoffeeScript sources to " +
          chosenDirectory + "..."
        )

        val failures = outdatedPaths.collect {
          case PathInformation(source, target) => new CsCompileResult(source, target)
        }.partition(_.failed_?)._1.map(_.error)

        if (failures.length != 0) {
          streams.log.error(failures.mkString("\n"))
          throw new RuntimeException("CoffeeScript compilation failed.")
        }
      }
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

    def doCompress(streams:TaskStreams, sourceDirectories:Seq[File], compressedTarget:File, bundle:File, extension:String, compressor:(Seq[String],BufferedWriter,ExceptionErrorReporter)=>Unit) = {
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

          IO.delete(compressedTarget)
          streams.log.info("  Found " + bundles.size + " bundles.")
          for {
            (bundle, files) <- bundles
          } {
            streams.log.info("    Bundling " + files.length + " files into bundle " + bundle + "...")
            val contentsToCompress =
              (for {
                filename <- files
                file <- (filename.split("/").foldLeft(sourceDirectories:PathFinder)(_ * _)).get
              } yield {
                IO.read(file, Charset.forName("UTF-8"))
              })

            IO.createDirectory(compressedTarget)
            IO.writer(compressedTarget / (bundle + "." + extension), "", Charset.forName("UTF-8"), false) { writer =>
              compressor(contentsToCompress, writer, new ExceptionErrorReporter(streams, bundle))
            }
          }
        } catch {
          case exception =>
            streams.log.error(exception.toString + "\n" + exception.getStackTrace.map(_.toString).mkString("\n"))

            throw new RuntimeException("Compression failed.")
        }
      } else {
        streams.log.warn("Couldn't find " + bundle.absolutePath + "; not generating any bundles.")
      }
    }

    def doScriptCompress(streams:TaskStreams, compileCoffeeScript:Unit, scriptDirectories:Seq[File], compressedTarget:File, scriptBundle:File) = {
      doCompress(streams, scriptDirectories, compressedTarget / "javascripts", scriptBundle, "js", { (fileContents, writer, reporter) =>
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
    def doScriptMash(streams:TaskStreams, compileCoffeeScript:Unit, scriptDirectories:Seq[File], compressedTarget:File, scriptBundle:File) = {
      doCompress(streams, scriptDirectories, compressedTarget / "javascripts", scriptBundle, "js", { (fileContents, writer, reporter) =>
        val mashedScript = fileContents.mkString(";\n")

        writer.write(mashedScript, 0, mashedScript.length)
      })
    }
    def doCssCompress(streams:TaskStreams, compileSass:Unit, styleDirectories:Seq[File], compressedTarget:File, styleBundle:File) = {
      doCompress(streams, styleDirectories, compressedTarget / "stylesheets", styleBundle, "css", { (fileContents, writer, reporter) =>
        val compressor =
          new CssCompressor(new StringReader(fileContents.mkString("")))

        compressor.compress(writer, defaultCompressionOptions.lineBreakPos)
      })
    }

    def doDeploy(streams:TaskStreams, bundleVersions:File, baseCompressedTarget:File, files:Seq[File], mimeType:String, access:String, secret:String, bucket:String) = {
      val handler = new S3Handler(access, secret, bucket)

      IO.write(bundleVersions.asFile, "")
      for {
        file <- files
        bundle = file.base
        relativePath <- IO.relativize(baseCompressedTarget, file)
      } yield {
        streams.log.info("  Deploying bundle " + bundle + " as " + relativePath + "...")

        try {
          val contents = IO.readBytes(file) 
          val checksum = handler.saveFile(mimeType, relativePath, contents)

          IO.append(bundleVersions, bundle + "=" + checksum + "\n")
        } catch {
          case e =>
            streams.log.error(e.getMessage + "\n" + e.getStackTrace.mkString("\n"))
            throw new RuntimeException("Failed to upload " + file)
        }
      }
    }
    def doScriptDeploy(streams:TaskStreams, compressScripts:Unit, scriptBundleVersions:File, compressedTarget:File, access:String, secret:String, bucket:String) = {
      doDeploy(streams, scriptBundleVersions, compressedTarget, (compressedTarget / "javascripts" ** "*.js").get, "text/javascript", access, secret, bucket)
    }
    def doCssDeploy(streams:TaskStreams, compressStyles:Unit, styleBundleVersions:File, compressedTarget:File, access:String, secret:String, bucket:String) = {
      doDeploy(streams, styleBundleVersions, compressedTarget, (compressedTarget / "stylesheets" ** "*.css").get, "text/css", access, secret, bucket)
    }

    val resourceManagementSettings = Seq(
      bundleDirectory in ResourceCompile <<= (resourceDirectory in Compile)(_ / "bundles"),
      scriptBundle in ResourceCompile <<= (bundleDirectory in ResourceCompile)(_ / "javascript.bundle"),
      styleBundle in ResourceCompile <<= (bundleDirectory in ResourceCompile)(_ / "stylesheet.bundle"),
      scriptBundleVersions in ResourceCompile <<= (bundleDirectory in ResourceCompile)(_ / "javascript-bundle-versions"),
      styleBundleVersions in ResourceCompile <<= (bundleDirectory in ResourceCompile)(_ / "stylesheet-bundle-versions"),
      compressedTarget in ResourceCompile <<= target(_ / "compressed"),

      scriptDirectories in ResourceCompile <<= (webappResources in Compile) map { resources => (resources * "javascripts").get },
      styleDirectories in ResourceCompile <<= (webappResources in Compile) map { resources => (resources * "stylesheets").get },
      coffeeScriptSources in ResourceCompile <<= (webappResources in Compile) map { resources => (resources ** "*.coffee").get },
      compileCoffeeScript in ResourceCompile <<= (streams, baseDirectory, webappResources in Compile, coffeeScriptSources in ResourceCompile) map doCoffeeScriptCompile _,
      compileSass in ResourceCompile <<= (streams, awsS3Bucket) map doSassCompile _,
      compressScripts in ResourceCompile <<= (streams, compileCoffeeScript in ResourceCompile, scriptDirectories in ResourceCompile, compressedTarget in ResourceCompile, scriptBundle in ResourceCompile) map doScriptCompress _,
      compressCss in ResourceCompile <<= (streams, compileSass in ResourceCompile, styleDirectories in ResourceCompile, compressedTarget in ResourceCompile, styleBundle in ResourceCompile) map doCssCompress _,
      deployScripts in ResourceCompile <<= (streams, compressScripts in ResourceCompile, scriptBundleVersions in ResourceCompile, compressedTarget in ResourceCompile, awsAccessKey, awsSecretKey, awsS3Bucket) map doScriptDeploy _,
      deployCss in ResourceCompile <<= (streams, compressCss in ResourceCompile, styleBundleVersions in ResourceCompile, compressedTarget in ResourceCompile, awsAccessKey, awsSecretKey, awsS3Bucket) map doCssDeploy _,

      compressResources in ResourceCompile <<= (compressScripts in ResourceCompile, compressCss in ResourceCompile) map { (thing, other) => },
      deployResources in ResourceCompile <<= (deployScripts in ResourceCompile, deployCss in ResourceCompile) map { (_, _) => },

      mashScripts in ResourceCompile <<= (streams, compileCoffeeScript in ResourceCompile, scriptDirectories in ResourceCompile, compressedTarget in ResourceCompile, scriptBundle in ResourceCompile) map doScriptMash _
    )
  }
} }
