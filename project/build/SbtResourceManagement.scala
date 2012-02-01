import sbt._

class SbtResourceManagementPluginProject(info:ProjectInfo) extends PluginProject(info) {
  val liftVersion = "2.4"
  override def libraryDependencies = Set(
    "net.liftweb" %% "lift-util" % liftVersion % "compile->default",
    "net.java.dev.jets3t" % "jets3t" % "0.8.1",
    "com.yahoo.platform.yui" % "yuicompressor" % "2.4.2"
  ) ++ super.libraryDependencies
}
