import sbt._

class SbtResourceManagementPluginProject(info:ProjectInfo) extends PluginProject(info) {
  override def libraryDependencies = Set(
    "net.java.dev.jets3t" % "jets3t" % "0.8.1"
  ) ++ super.libraryDependencies
}
