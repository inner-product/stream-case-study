ThisBuild / scalaVersion := "2.13.8"
ThisBuild / version := "0.1.0"
ThisBuild / versionScheme := Some("early-semver")

ThisBuild / useSuperShell := false
Global / onChangedBuildSource := ReloadOnSourceChanges

ThisBuild / scalafixDependencies += "com.github.liancheng" %% "organize-imports" % "0.5.0"
ThisBuild / semanticdbEnabled := true
ThisBuild / semanticdbVersion := scalafixSemanticdb.revision

libraryDependencies ++= Seq(
  "org.typelevel" %% "cats-core" % "2.7.0",
  "org.typelevel" %% "cats-effect" % "3.3.6",
  "org.scalameta" %% "munit" % "0.7.29" % Test,
  "org.scalameta" %% "munit-scalacheck" % "0.7.29" % Test
)

lazy val build = taskKey[Unit]("Build everything")
build := Def
  .sequential(
    dependencyUpdates,
    scalafmtAll,
    scalafixAll.toTask(""),
    (Test / test)
  )
  .value
