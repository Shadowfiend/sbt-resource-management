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

  trait ResourceManagementPlugin extends BasicScalaProject { self:BasicScalaProject =>
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

    // Override this to set the proper run mode.
    def runMode = "development"

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

          for {
            (bundle, files) <- bundles
          } {
            val contentsToCompress =
              (for (file <- files) yield {
                scala.io.Source.fromFile(new File(("src" / "main" / "webapp" / "javascripts").absolutePath + "/" + file)).mkString("")
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
    lazy val deployScripts = task {
      log.info("Deploying scripts to " + ""/* s3Bucket */ + "...")

      None
    } dependsOn(compressScripts)

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
              (for (file <- files) yield {
                scala.io.Source.fromFile(new File(("src" / "main" / "webapp" / "stylesheets").absolutePath + "/" + file)).mkString("")
              }).mkString(";\n")

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
    lazy val deployCss = task {
      log.info("Deploying CSS to " + ""/* s3Bucket */ + "...")

      None
    } dependsOn(compressCss)

    lazy val compressResources = task { None } dependsOn(compressScripts, compressCss)
    lazy val deployResources = task { None } dependsOn(deployScripts, deployCss)
  }
} }
