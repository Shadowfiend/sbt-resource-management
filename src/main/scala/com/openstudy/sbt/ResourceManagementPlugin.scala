package com.openstudy { package sbt {
  import java.io._

  import _root_.sbt._

  import org.mozilla.javascript.{ErrorReporter, EvaluatorException}
  import com.yahoo.platform.yui.compressor._


  // The result of a CoffeeScript compile.
  class CsCompileResult(incomingPath:Path) {
    private val runtime = Runtime.getRuntime

    private val sourcePath = incomingPath.relativePath.split(File.separator).toList.dropRight(1).mkString(File.separator)
    private val process = runtime.exec(
      ("coffee" :: "-o" :: ("src/main/webapp/javascripts/" + sourcePath) ::
                  "-c" :: ("src/main/webapp/coffee-script-hidden/" + incomingPath.relativePath) :: Nil).toArray)

    private val result = process.waitFor

    val failed_? = result != 0

    val error =
      if (failed_?)
        "CoffeeScript compilation failed. Errors: " + scala.io.Source.fromInputStream(process.getErrorStream).mkString("")
      else
        ""
  }
  object CsCompileResult {
    implicit def path2CsCompileResult(path:Path) = new CsCompileResult(path)
  }

  trait ResourceManagementPlugin extends BasicScalaProject with S3Handler { self:BasicScalaProject =>
    /**
     * Handles all JS errors by throwing an exception. This can then get caught
     * and turned into a Failure. in the masher.
     */
    class ExceptionErrorReporter(filename:String) extends ErrorReporter {
      def throwExceptionWith(message:String, file:String, line:Int) = {
        log.info("Compressor failed at: " + filename + ":" + line + "; said: " + message)
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

    // Override this to set the proper AWS credentials.
    def awsAccessKey : Property[String]
    def accessKey = awsAccessKey.value
    def awsSecretKey : Property[String]
    def secretKey = awsSecretKey.value
    // Override this to set the proper S3 bucket for asset upload.
    def awsS3Bucket : Property[String]
    def bucket = awsS3Bucket.value

    def coffeeScriptSources : Iterable[Path] = (("src" / "main" / "webapp" / "coffee-script-hidden" ##) ** "*.coffee").get
    def coffeeScriptResults : Iterable[Path] = coffeeScriptSources.map { source =>
      source.relativePath.split(File.separator).toList.dropRight(1).foldLeft("src" / "main" / "webapp" / "javascripts")(_ / _)
    }
    lazy val compileCoffeeScript = fileTask(coffeeScriptResults) {
      // Recursively find cs files.
      // If any are found, run coffee on them and deposit the results in
      // target/.

      val failures = coffeeScriptSources.map { path =>
        new CsCompileResult(path)
      }.partition(_.failed_?)._1.map(_.error).toList

      if (failures.length > 0)
        Some("CoffeeScript compilation failed. Errors:\n" + failures.mkString("\n\n"))
      else
        None
    }
    lazy val compressScripts = task {
      log.info("Compressing scripts...")

      // Find bundles.
      val path = ("src" / "main" / "resources" / "bundles" / "javascript.bundle")

      if (path.exists) {
        try {
          val bundles = Map[String,List[String]]() ++
            scala.io.Source.fromFile(path.asFile).mkString("").split("\n\n").flatMap { section =>
              val lines = section.split("\n").toList

              if (lines.length >= 1)
                Some(lines(0) -> lines.drop(1))
              else {
                log.warn("Found a bundle with no name/content.")
                None
              }
            }

          val bundleVersions = "src" / "main" / "resources" / "bundles" / "javascript-bundle-versions"
          FileUtilities.write(bundleVersions.asFile, "", log)
          for {
            (bundle, files) <- bundles
          } {
            val contentsToCompress =
              (for {
                filename <- files
                file = new File(("src" / "main" / "webapp" / "javascripts").absolutePath + "/" + filename)
              } yield {
                scala.io.Source.fromFile(file).mkString("")
              }).mkString(";\n")

            val compressor =
              new JavaScriptCompressor(
                new StringReader(contentsToCompress),
                new ExceptionErrorReporter(bundle))

            FileUtilities.createDirectory("target" / "compressed" / "javascripts", log)
            val writer = new BufferedWriter(new FileWriter(("target" / "compressed" / "javascripts").absolutePath + "/" + bundle + ".js"))
            compressor.compress(writer,
              defaultCompressionOptions.lineBreakPos, defaultCompressionOptions.munge,
              defaultCompressionOptions.verbose, defaultCompressionOptions.preserveSemicolons,
              defaultCompressionOptions.disableOptimizations)
          }

          None
        } catch {
          case exception =>
            Some(exception.getMessage + "\n" + exception.getStackTrace.map(_.toString).mkString("\n"))
        }
      } else {
        log.warn("Couldn't find " + path.absolutePath + "; not generating any bundles.")
        None
      }
    } dependsOn(compileCoffeeScript)
    lazy val compileSass = task {
      log.info("Compiling SASS files...")

      val runtime = Runtime.getRuntime
      val process = runtime.exec(("compass" :: "compile" :: Nil).toArray)
      val result = process.waitFor

      if (result != 0)
        Some("SASS compilation failed. Errors: " + scala.io.Source.fromInputStream(process.getErrorStream).mkString(""))
      else
        None
    }
    lazy val compressCss = task {
      log.info("Compressing CSS...")

      // Find bundles.
      val path = ("src" / "main" / "resources" / "bundles" / "stylesheet.bundle")

      if (path.exists) {
        try {
          val bundles = Map[String,List[String]]() ++
            scala.io.Source.fromFile(path.asFile).mkString("").split("\n\n").flatMap { section =>
              val lines = section.split("\n").toList

              if (lines.length >= 1)
                Some(lines(0) -> lines.drop(1))
              else {
                log.warn("Found a bundle with no name/content.")
                None
              }
            }

          for {
            (bundle, files) <- bundles
          } {
            val contentsToCompress =
              (for {
                filename <- files
                file = new File(("src" / "main" / "webapp" / "stylesheets").absolutePath + "/" + filename)
              } yield {
                scala.io.Source.fromFile(file).mkString("")
              }).mkString("")

            val compressor =
              new CssCompressor(new StringReader(contentsToCompress))

            FileUtilities.createDirectory("target" / "compressed" / "stylesheets", log)
            val writer = new BufferedWriter(new FileWriter(("target" / "compressed" / "stylesheets").absolutePath + "/" + bundle + ".css"))
            compressor.compress(writer, defaultCompressionOptions.lineBreakPos)
          }

          None
        } catch {
          case exception =>
            Some(exception.getMessage + "\n" + exception.getStackTrace.map(_.toString).mkString("\n"))
        }
      } else {
        log.warn("Couldn't find " + path.absolutePath + "; not generating any bundles.")
        None
      }

      None
    } dependsOn(compileSass)

    lazy val deployScripts = task {
      log.info("Deploying scripts to " + bucket + "...")

      val bundleVersions = "src" / "main" / "resources" / "bundles" / "javascript-bundle-versions"
      FileUtilities.write(bundleVersions.asFile, "", log)

      val scripts = ("target" / "compressed" ##) / "javascripts"
      val results:List[String] =
        (for {
          path <- (scripts ** "*.js").get
          bundle = path.base
          relativePath = path.relativePath
          file = path.asFile
        } yield {
          try {
            val checksum = FileUtilities.readBytes(file, log) match {
              case Left(error) =>
                throw new Exception(error)
              case Right(contents) =>
                saveFile("text/javascript", relativePath, contents)
            }

            FileUtilities.append(bundleVersions.asFile, bundle + "=" + checksum, log)

            List[String]()
          } catch {
            case e =>
              log.error("Failed to upload " + file)

              List(e.getMessage + "\n" + e.getStackTrace.mkString("\n"))
          }
        }).toList.flatten

      results.firstOption
    } dependsOn(compressScripts)

    lazy val deployCss = task {
      log.info("Deploying CSS to " + bucket + "...")

      val bundleVersions = "src" / "main" / "resources" / "bundles" / "stylesheet-bundle-versions"
      FileUtilities.write(bundleVersions.asFile, "", log)

      val scripts = ("target" / "compressed" ##) / "stylesheets"
      val results:List[String] =
        (for {
          path <- (scripts ** "*.css").get
          bundle = path.base
          relativePath = path.relativePath
          file = path.asFile
        } yield {
          try {
            val checksum = FileUtilities.readBytes(file, log) match {
              case Left(error) =>
                throw new Exception(error)
              case Right(contents) =>
                saveFile("text/css", relativePath, contents)
            }

            FileUtilities.append(bundleVersions.asFile, bundle + "=" + checksum, log)

            List[String]()
          } catch {
            case e =>
              log.error("Failed to upload " + file)

              List(e.getMessage + "\n" + e.getStackTrace.mkString("\n"))
          }
        }).toList.flatten

      results.firstOption
    } dependsOn(compressCss)

    lazy val compressResources = task { None } dependsOn(compressScripts, compressCss)
    lazy val deployResources = task { None } dependsOn(deployScripts, deployCss)
  }
} }
