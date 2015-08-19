package com.openstudy { package sbt {
  import scala.collection.JavaConversions._

  import java.lang.Runtime
  import java.io._
  import _root_.sbt.{File => SbtFile, _}
  import Keys.{baseDirectory, resourceDirectory, streams, target, _}

  object ResourceManagementPlugin extends Plugin
      with SassCompilation
      with LessCompilation
      with CoffeeScriptCompilation
      with ScriptCompression
      with CssCompression
      with CssDeployment
      with ScriptDeployment {
    val runtime = Runtime.getRuntime
    def systemEnvironment = System.getenv().toMap
    val ResourceCompile = config("resources")

    private val webappResourceAlias = SettingKey[Seq[File]]("webapp-resources")

    val awsAccessKey = SettingKey[Option[String]]("aws-access-key")
    val awsSecretKey = SettingKey[Option[String]]("aws-secret-key")
    val awsS3Bucket = SettingKey[Option[String]]("aws-s3-bucket")
    val checksumInFilename = SettingKey[Boolean]("checksum-in-filename")
    val gzipResources = SettingKey[Boolean]("gzipResources")

    val targetJavaScriptDirectory = SettingKey[File]("target-java-script-directory")
    val bundleDirectory = SettingKey[File]("bundle-directory")
    val scriptBundle = SettingKey[File]("javascript-bundle")
    val styleBundle = SettingKey[File]("stylesheet-bundle")
    val scriptBundleVersions = SettingKey[File]("javascript-bundle-versions")
    val styleBundleVersions = SettingKey[File]("stylesheet-bundle-versions")

    val scriptDirectories = TaskKey[Seq[File]]("javascripts-directories")
    val styleDirectories = TaskKey[Seq[File]]("stylesheets-directories")
    val copyScripts = TaskKey[Unit]("copy-scripts")

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

    val resourceManagementSettings = Seq(
      checksumInFilename in ResourceCompile := false,

      awsAccessKey := None,
      awsSecretKey := None,
      awsS3Bucket := None,
      forceSassCompile := false,
      gzipResources := false,

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
      deployScripts in ResourceCompile <<= (streams, checksumInFilename in ResourceCompile, gzipResources, compressScripts in ResourceCompile, scriptBundleVersions in ResourceCompile, compressedTarget in ResourceCompile, awsAccessKey, awsSecretKey, awsS3Bucket) map doScriptDeploy _,
      deployCss in ResourceCompile <<= (streams, checksumInFilename in ResourceCompile, gzipResources, compressCss in ResourceCompile, styleBundleVersions in ResourceCompile, compressedTarget in ResourceCompile, awsAccessKey, awsSecretKey, awsS3Bucket) map doCssDeploy _,

      compressResources in ResourceCompile <<= (compressScripts in ResourceCompile, compressCss in ResourceCompile) map { (thing, other) => },
      deployResources in ResourceCompile <<= (deployScripts in ResourceCompile, deployCss in ResourceCompile) map { (_, _) => },

      mashScripts in ResourceCompile <<= (streams, checksumInFilename in ResourceCompile, copyScripts in ResourceCompile, targetJavaScriptDirectory in ResourceCompile, compressedTarget in ResourceCompile, scriptBundle in ResourceCompile) map doScriptMash _,
      watchSources <++= (coffeeScriptSources in ResourceCompile, scriptDirectories in ResourceCompile) map {
        (csSources, scriptDirectories) => csSources ++ scriptDirectories
      }
    )
  }
} }
