package com.openstudy { package sbt {
  import java.io._
  import java.nio.charset.Charset

  import _root_.sbt.{File => SbtFile, _}
  import Keys.{baseDirectory, resourceDirectory, streams, target, _}

  import org.mozilla.javascript.{ErrorReporter, EvaluatorException}
  import com.yahoo.platform.yui.compressor._


  object ResourceManagementPlugin extends Plugin {
    type TaskStreams = std.TaskStreams[Project.ScopedKey[_]]

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
          "CoffeeScript compilation failed. Errors: " + scala.io.Source.fromInputStream(process.getErrorStream).mkString("")
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

    def doCoffeeScriptCompile(streams:TaskStreams, baseDirectory:File, webappResources:Seq[File], csSources:Seq[File]) = {
      val chosenDirectory = webappResources.head
      val failures = csSources.map { file =>
        new CsCompileResult(
          IO.relativize(baseDirectory, file).get,
          IO.relativize(baseDirectory, chosenDirectory / "javascripts" / (file.base + ".js")).get)
      }.partition(_.failed_?)._1.map(_.error)
    }

    def doSassCompile(streams:TaskStreams) = {
      streams.log.info("Compiling SASS files...")

      val runtime = java.lang.Runtime.getRuntime
      val process =
        runtime.exec(
          ("compass" :: "compile" :: "-e" :: "production" :: "--force" :: Nil).toArray,
          ("asset_domain=" + awsS3Bucket :: Nil).toArray)
      val result = process.waitFor

      if (result != 0)
        streams.log.error("SASS compilation failed with code " + result + ". Errors: " + scala.io.Source.fromInputStream(process.getErrorStream).mkString(""))
      else {
        streams.log.info("Done.")
      }
    }

    def doCompress(streams:TaskStreams, sourceDirectories:Seq[File], compressedTarget:File, bundle:File, compressor:(Seq[String],BufferedWriter,ExceptionErrorReporter)=>Unit) = {
      if (bundle.exists) {
        try {
          val bundles = Map[String,List[String]]() ++
            scala.io.Source.fromFile(bundle).mkString("").split("\n\n").flatMap { section =>
              val lines = section.split("\n").toList

              if (lines.length >= 1)
                Some(lines(0) -> lines.drop(1))
              else {
                streams.log.warn("Found a bundle with no name/content.")
                None
              }
            }

          streams.log.info("  Found " + bundles.size + " bundles.")
          for {
            (bundle, files) <- bundles
          } {
            streams.log.info("    Bundling " + files.length + " files into bundle " + bundle + "...")
            val contentsToCompress =
              (for {
                filename <- files
                file <- (sourceDirectories * filename).get
              } yield {
                scala.io.Source.fromFile(file).mkString("")
              })

            IO.createDirectory(compressedTarget / "javascripts")
            IO.writer(compressedTarget / "javascripts" / (bundle + ".js"), "", Charset.forName("UTF-8"), false) { writer =>
              compressor(contentsToCompress, writer, new ExceptionErrorReporter(streams, bundle))
            }
          }
        } catch {
          case exception =>
            streams.log.error(exception.toString + "\n" + exception.getStackTrace.map(_.toString).mkString("\n"))
        }
      } else {
        streams.log.warn("Couldn't find " + bundle.absolutePath + "; not generating any bundles.")
      }
    }

    def doScriptCompress(streams:TaskStreams, compileCoffeeScript:Unit, scriptDirectories:Seq[File], compressedTarget:File, scriptBundle:File) = {
      doCompress(streams, scriptDirectories, compressedTarget / "javascripts", scriptBundle, { (fileContents, writer, reporter) =>
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
    def doCssCompress(streams:TaskStreams, compileSass:Unit, styleDirectories:Seq[File], compressedTarget:File, styleBundle:File) = {
      doCompress(streams, styleDirectories, compressedTarget / "stylesheets", styleBundle, { (fileContents, writer, reporter) =>
        val compressor =
          new CssCompressor(new StringReader(fileContents.mkString("")))

        compressor.compress(writer, defaultCompressionOptions.lineBreakPos)
      })
    }

    def doDeploy(streams:TaskStreams, bundleVersions:File, compressedTarget:File, mimeType:String, access:String, secret:String, bucket:String) = {
      val handler = new S3Handler(access, secret, bucket)

      IO.write(bundleVersions.asFile, "")
      for {
        file <- (compressedTarget ** "*.js").get
        bundle = file.base
        relativePath <- IO.relativize(compressedTarget / "..", file)
      } yield {
        streams.log.info("  Deploying bundle " + bundle + " as " + relativePath + "...")

        try {
          val contents = IO.readBytes(file) 
          val checksum = handler.saveFile(mimeType, relativePath, contents)

          IO.append(bundleVersions, bundle + "=" + checksum)
        } catch {
          case e =>
            streams.log.error("Failed to upload " + file)

            streams.log.error(e.getMessage + "\n" + e.getStackTrace.mkString("\n"))
        }
      }
    }
    def doScriptDeploy(streams:TaskStreams, compressScripts:Unit, scriptBundleVersions:File, compressedTarget:File, access:String, secret:String, bucket:String) = {
      doDeploy(streams, scriptBundleVersions, compressedTarget / "javascripts", "text/javascript", access, secret, bucket)
    }
    def doCssDeploy(streams:TaskStreams, compressStyles:Unit, styleBundleVersions:File, compressedTarget:File, access:String, secret:String, bucket:String) = {
      doDeploy(streams, styleBundleVersions, compressedTarget / "stylesheets", "text/css", access, secret, bucket)
    }

    val resourceManagementSettings = Seq(
      bundleDirectory in ResourceCompile <<= resourceDirectory(_ / "bundles"),
      scriptBundle in ResourceCompile <<= bundleDirectory(_ / "javascript.bundle"),
      styleBundle in ResourceCompile <<= bundleDirectory(_ / "stylesheet.bundle"),
      scriptBundleVersions in ResourceCompile <<= bundleDirectory(_ / "javascript-bundle-versions"),
      styleBundleVersions in ResourceCompile <<= bundleDirectory(_ / "stylesheet-bundle-versions"),
      compressedTarget in ResourceCompile <<= target(_ / "compressed"),

      scriptDirectories in ResourceCompile <<= webappResources map { resources => (resources * "javascripts").get },
      styleDirectories in ResourceCompile <<= webappResources map { resources => (resources * "stylesheets").get },
      coffeeScriptSources in ResourceCompile <<= webappResources map { resources => (resources ** "*.coffee").get },
      compileCoffeeScript in ResourceCompile <<= (streams, baseDirectory, webappResources, coffeeScriptSources) map doCoffeeScriptCompile _,
      compileSass in ResourceCompile := streams map doSassCompile _,
      compressScripts in ResourceCompile <<= (streams, compileCoffeeScript, scriptDirectories, compressedTarget, scriptBundle) map doScriptCompress _,
      compressCss in ResourceCompile <<= (streams, compileSass, styleDirectories, compressedTarget, styleBundle) map doCssCompress _,
      deployScripts in ResourceCompile <<= (streams, compressScripts, scriptBundleVersions, compressedTarget, awsAccessKey, awsSecretKey, awsS3Bucket) map doScriptDeploy _,
      deployCss in ResourceCompile <<= (streams, compressCss, styleBundleVersions, compressedTarget, awsAccessKey, awsSecretKey, awsS3Bucket) map doCssDeploy _,

      compressResources in ResourceCompile <<= (compressScripts, compressCss) map { (thing, other) => },
      deployResources in ResourceCompile <<= (deployScripts, deployCss) map { (_, _) => }
    )
  }
} }
