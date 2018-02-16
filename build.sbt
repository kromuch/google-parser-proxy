val globalSettings = Seq[SettingsDefinition](
  version := "0.1",
  scalaVersion := "2.12.4"
)

val proxyParser = Project("proxy-parser",file("."))
  .settings(globalSettings: _*)
  .settings(
  libraryDependencies ++= Seq(
    "com.typesafe.slick" %% "slick" % "3.2.1",
    "org.slf4j" % "slf4j-nop" % "1.6.4",
    "com.typesafe.slick" %% "slick-hikaricp" % "3.2.1",
    "org.postgresql" % "postgresql" % "42.1.4",
    "com.typesafe.akka" %% "akka-actor" % "2.4.19"
  )
)