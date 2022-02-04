ThisBuild / scalaVersion := "2.13.8"
ThisBuild / version := "0.1.0"
ThisBuild / versionScheme := Some("early-semver")

ThisBuild / useSuperShell := false
Global / onChangedBuildSource := ReloadOnSourceChanges

ThisBuild / scalafixDependencies += "com.github.liancheng" %% "organize-imports" % "0.5.0"
ThisBuild / semanticdbEnabled := true
ThisBuild / semanticdbVersion := scalafixSemanticdb.revision

val sharedSettings = Seq(
  libraryDependencies ++= Seq(
    "org.typelevel" %% "cats-core" % "2.7.0",
    "org.typelevel" %% "cats-effect" % "3.3.5",
    "org.scalameta" %% "munit" % "0.7.29" % Test,
    "org.scalameta" %% "munit-scalacheck" % "0.7.29" % Test
  )
)
val build = taskKey[Unit]("Format, compile, and test")

lazy val root = project.in(file(".")).aggregate(stream, benchmarks)

lazy val stream = project
  .in(file("stream"))
  .settings(
    sharedSettings,
    build := Def
      .sequential(
        dependencyUpdates,
        scalafixAll.toTask(""),
        scalafmtAll,
        (Test / test)
      )
      .value
  )

lazy val benchmarks = project
  .in(file("benchmarks"))
  .settings(
    sharedSettings,
    build := Def
      .sequential(
        dependencyUpdates,
        scalafixAll.toTask(""),
        scalafmtAll,
        (Test / test)
      )
      .value
  )
  .enablePlugins(JmhPlugin)
  .dependsOn(stream)
