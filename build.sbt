sbtPlugin := true

name := "sbt-resource-management"

organization := "com.openstudy"

version := "0.5.1-SNAPSHOT"

pomExtra := {
<url>http://github.com/Shadowfiend/sbt-resource-management</url>
<licenses>
  <license>
    <name>MIT</name>
    <url>http://opensource.org/licenses/MIT</url>
    <distribution>repo</distribution>
  </license>
</licenses>
<scm>
  <url>https://github.com/Shadowfiend/sbt-resource-management.git</url>
  <connection>https://github.com/Shadowfiend/sbt-resource-management.git</connection>
</scm>
<developers>
  <developer>
    <id>shadowfiend</id>
    <name>Antonio Salazar Cardozo</name>
    <email>savedfastcool@gmail.com</email>
  </developer>
  <developer>
    <id>farmdawgnation</id>
    <name>Matt Farmer</name>
    <email>matt@frmr.me</email>
  </developer>
</developers>
}

libraryDependencies += "com.amazonaws" % "aws-java-sdk" % "1.7.5"

libraryDependencies += "com.yahoo.platform.yui" % "yuicompressor"  % "2.4.7"

libraryDependencies += "org.scalatest" %% "scalatest" % "2.2.4" % "test"

libraryDependencies += "org.mockito" % "mockito-core" % "1.9.5" % "test"


scalacOptions += "-deprecation"

publishTo <<= version { (v: String) =>
  val nexus = "https://oss.sonatype.org/"
  if (v.trim.endsWith("SNAPSHOT"))
    Some("snapshots" at nexus + "content/repositories/snapshots")
  else
    Some("releases"  at nexus + "service/local/staging/deploy/maven2")
}

credentials += Credentials(Path.userHome / ".sonatype")
