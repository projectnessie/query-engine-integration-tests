/*
 * Copyright (C) 2022 Dremio
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

import java.util.Properties
import java.util.regex.Pattern
import org.gradle.internal.component.local.model.DefaultProjectComponentSelector

if (!JavaVersion.current().isCompatibleWith(JavaVersion.VERSION_11)) {
  throw GradleException("Build requires Java 11")
}

val baseVersion = file("version.txt").readText().trim()

pluginManagement {
  // Cannot use a settings-script global variable/value, so pass the 'versions' Properties via
  // settings.extra around.
  val versions = java.util.Properties()
  val pluginIdPattern =
    java.util.regex.Pattern.compile("\\s*id\\(\"([^\"]+)\"\\) version \"([^\"]+)\"\\s*")
  settings.extra["nessieBuildTools.versions"] = versions

  plugins {

    // Note: this is NOT a real project but a hack for dependabot to manage the plugin versions.
    //
    // Background: dependabot only manages dependencies (incl Gradle plugins) in build.gradle[.kts]
    // files. It scans the root build.gradle[.kts] fila and those in submodules referenced in
    // settings.gradle[.kts].
    // But dependabot does not manage managed plugin dependencies in settings.gradle[.kts].
    // However, since dependabot is a "dumb search and replace engine", we can use a trick:
    // 1. Have this "dummy" build.gradle.kts file with all managed plugin dependencies.
    // 2. Add an `include()` to this build file in settings.gradle.kts, surrounded with an
    //    `if (false)`, so Gradle does _not_ pick it up.
    // 3. Parse this file in our settings.gradle.kts, provide a `ResolutionStrategy` to the
    //    plugin dependencies.

    val pulledVersions =
      file("gradle/dependabot/build.gradle.kts")
        .readLines()
        .map { line -> pluginIdPattern.matcher(line) }
        .filter { matcher -> matcher.matches() }
        .associate { matcher -> matcher.group(1) to matcher.group(2) }

    resolutionStrategy {
      eachPlugin {
        if (requested.version == null) {
          var pluginId = requested.id.id
          if (
            pluginId.startsWith("org.projectnessie.buildsupport.") ||
              pluginId == "org.projectnessie.smallrye-open-api"
          ) {
            pluginId = "org.projectnessie.buildsupport.spotless"
          }
          if (pulledVersions.containsKey(pluginId)) {
            useVersion(pulledVersions[pluginId])
          }
        }
      }
    }

    versions["versionErrorPronePlugin"] = pulledVersions["net.ltgt.errorprone"]
    versions["versionIdeaExtPlugin"] = pulledVersions["org.jetbrains.gradle.plugin.idea-ext"]
    versions["versionSpotlessPlugin"] = pulledVersions["com.diffplug.spotless"]
    versions["versionJandexPlugin"] = pulledVersions["com.github.vlsi.jandex"]
    versions["versionShadowPlugin"] = pulledVersions["com.github.johnrengelman.plugin-shadow"]
    versions["versionNessieBuildPlugins"] =
      pulledVersions["org.projectnessie.buildsupport.spotless"]
    versions["versionProjectnessiePlugin"] = pulledVersions["org.projectnessie"]
    versions["versionTestRerunPlugin"] = pulledVersions["org.caffinitas.gradle.testrerun"]

    // The project's settings.gradle.kts is "executed" before buildSrc's settings.gradle.kts and
    // build.gradle.kts.
    //
    // Plugin and important dependency versions are defined here and shared with buildSrc via
    // a properties file, and via an 'extra' property with all other modules of the Nessie build.
    //
    // This approach works fine with GitHub's dependabot as well
    val nessieBuildVersionsFile = file("build/nessieBuild/versions.properties")
    nessieBuildVersionsFile.parentFile.mkdirs()
    nessieBuildVersionsFile.outputStream().use {
      versions.store(it, "Nessie Build versions from settings.gradle.kts - DO NOT MODIFY!")
    }
  }

  repositories {
    mavenCentral() // prefer Maven Central, in case Gradle's repo has issues
    gradlePluginPortal()
    if (System.getProperty("withMavenLocal").toBoolean()) {
      mavenLocal()
    }
  }
}

gradle.rootProject {
  val prj = this
  val versions = settings.extra["nessieBuildTools.versions"] as Properties
  versions.forEach { k, v -> prj.extra[k.toString()] = v }
}

gradle.beforeProject {
  version = baseVersion
  group = "org.projectnessie.integrations-tools-tests"
}

val ideSyncActive =
  System.getProperty("idea.sync.active").toBoolean() ||
    System.getProperty("eclipse.product") != null ||
    gradle.startParameter.taskNames.any { it.startsWith("eclipse") }

// Make nessie.integrationsTesting.*SourceTree absolute paths
fun projectCanonicalPath(sub: String): File {
  val property = "nessie.integrationsTesting.${sub}SourceTree"
  val projectDir =
    rootProject.projectDir.resolve(System.getProperty(property, "./included-builds/$sub"))
  val canonicalDir = projectDir.canonicalFile
  if (ideSyncActive) {
    val additionalPropertiesDir = file("$canonicalDir/build")
    additionalPropertiesDir.mkdirs()
    val additionalPropertiesFile = file("$additionalPropertiesDir/additional-build.properties")
    val additionalProperties = Properties()
    additionalProperties.putAll(
      System.getProperties().filter { e -> e.key.toString().startsWith("nessie.") }
    )
    additionalPropertiesFile.writer().use { writer ->
      additionalProperties.store(writer, "Generated")
    }
  }

  return canonicalDir
}

val frameworksVersions = loadProperties(file("frameworks-versions.properties"))

val nessieMajorVersion = majorVersion(System.getProperty("nessie.versionNessie", "999.99"))
val icebergMajorVersion = majorVersion(System.getProperty("nessie.versionIceberg", "999.99"))

val includeNessieBuild = System.getProperty("nessie.versionNessie") == null
val includeIcebergBuild = System.getProperty("nessie.versionIceberg") == null

val sparkVersions = frameworksVersions["sparkVersions"].toString().split(",").map { it.trim() }
val sparkRestrictionsForNessie = versionConstraints("spark", "nessie", nessieMajorVersion)
val sparkRestrictionsForIceberg = versionConstraints("spark", "iceberg", icebergMajorVersion)
val sparkRestrictions = versionRestrictions(sparkRestrictionsForNessie, sparkRestrictionsForIceberg)

updateDefaultVersion(sparkRestrictions, "spark")

// The Iceberg build works only against one Scala version. So if the Iceberg build is included,
// we have to restrict the integration tests to the same Scala version.
val scalaVersion = System.getProperty("scalaVersion", "2.12")!!
val scalaRestrictionsForSourceBuild = if (includeIcebergBuild) setOf(scalaVersion) else setOf()
val scalaRestrictionsForNessie = versionConstraints("scala", "nessie", nessieMajorVersion)
val scalaRestrictionsForIceberg = versionConstraints("scala", "iceberg", icebergMajorVersion)
val scalaRestrictions =
  versionRestrictions(
    versionRestrictions(scalaRestrictionsForNessie, scalaRestrictionsForIceberg),
    scalaRestrictionsForSourceBuild
  )

updateDefaultVersion(scalaRestrictions, "scala")

// Iceberg source builds require Flink artifacts. But there are no Flink artifacts for Scala 2.13
// at the moment, so exclude everything of Flink when using an Iceberg source build and Scala 2.13.
val flinkVersions =
  if (includeIcebergBuild && scalaVersion != "2.12") listOf()
  else frameworksVersions["flinkVersions"].toString().split(",").map { it.trim() }
val flinkRestrictions = versionConstraints("flink", "iceberg", icebergMajorVersion)

updateDefaultVersion(flinkRestrictions, "flink")

val prestoVersions = frameworksVersions["prestoVersions"].toString().split(",").map { it.trim() }
val prestoRestrictions = versionConstraints("presto", "iceberg", icebergMajorVersion)

updateDefaultVersion(prestoRestrictions, "presto")

val nessieSourceDir = projectCanonicalPath("nessie")
val icebergSourceDir = projectCanonicalPath("iceberg")

file("build").mkdirs()

file("build/frameworks-versions-effective.properties").writer().use {
  frameworksVersions.store(it, "Effective frameworks-versions.properties")
}

System.err.println(
  """
  Nessie major version:   $nessieMajorVersion (included: $includeNessieBuild)
  Iceberg major version:  $icebergMajorVersion (included: $includeIcebergBuild)
  Spark restrictions:     $sparkRestrictions
    via Nessie:           $sparkRestrictionsForNessie
    via Iceberg:          $sparkRestrictionsForIceberg
  Scala restrictions:     $scalaRestrictions
    via Nessie:           $scalaRestrictionsForNessie
    via Iceberg:          $scalaRestrictionsForIceberg
    for Source build:     $scalaRestrictionsForSourceBuild
  Flink restrictions:     $flinkRestrictions
  Presto restrictions:    $prestoRestrictions
""".trimIndent()
)

fun updateDefaultVersion(restrictions: Set<String>, project: String) {
  if (restrictions.isNotEmpty()) {
    val ver = restrictions.first()
    if (frameworksVersions.containsKey("version${project.capitalize()}-$ver")) {
      frameworksVersions["${project}DefaultVersion"] = ver
    }
  }
}

fun versionRestrictions(
  currentRestrictions: Set<String>,
  addedRestrictions: Set<String>
): Set<String> {
  if (currentRestrictions.isEmpty()) {
    return addedRestrictions
  }
  if (addedRestrictions.isEmpty()) {
    return currentRestrictions
  }
  return currentRestrictions.union(addedRestrictions)
}

fun restrictedVersion(restrictions: Set<String>, majorVersion: String): Boolean =
  restrictions.isNotEmpty() && !restrictions.contains(majorVersion)

fun versionConstraints(
  restricted: String,
  restricting: String,
  restrictingMajorVersion: String
): Set<String> =
  frameworksVersions
    .getProperty("constraints.${restricted}Versions.$restricting-$restrictingMajorVersion", "")
    .split(",")
    .map { it.trim() }
    .filter { it.isNotEmpty() }
    .toSet()

fun majorVersion(version: String): String {
  val elems = version.split(".")
  return "${elems[0]}.${elems[1]}"
}

fun loadProperties(file: File): Properties {
  val props = Properties()
  file.reader().use { reader -> props.load(reader) }
  return props
}

fun loadNessieIcebergProjects(): Set<String> {
  val nessieIcebergProjects = HashSet<String>()
  loadProperties(file("$nessieSourceDir/gradle/projects.iceberg.properties")).keys.forEach {
    nessieIcebergProjects.add(it.toString())
  }

  val sparkScala = loadProperties(file("$nessieSourceDir/clients/spark-scala.properties"))
  val allScalaVersions = LinkedHashSet<String>()
  for (sparkVersion in sparkScala["sparkVersions"].toString().split(",")) {
    val scalaVersions =
      sparkScala["sparkVersion-${sparkVersion}-scalaVersions"].toString().split(",").map {
        it.trim()
      }
    for (scalaVersion in scalaVersions) {
      allScalaVersions.add(scalaVersion)
      nessieIcebergProjects.add("nessie-spark-extensions-${sparkVersion}_$scalaVersion")
    }
  }
  for (scalaVersion in allScalaVersions) {
    nessieIcebergProjects.add("nessie-spark-extensions-base_$scalaVersion")
  }
  nessieIcebergProjects.add("nessie-spark-extensions-base")
  nessieIcebergProjects.add("nessie-spark-extensions")
  nessieIcebergProjects.add("nessie-spark-3.2-extensions")

  return nessieIcebergProjects
}

val nessieIcebergProjects = if (includeNessieBuild) loadNessieIcebergProjects() else setOf()

fun DependencySubstitution.manageNessieProjectDependency(
  includedBuildDesc: String,
  substitutions: DependencySubstitutions
): Boolean {
  val req = requested
  if (req is ModuleComponentSelector && req.version.isEmpty()) {
    if (
      req.group == "org.projectnessie" &&
        (req.module.startsWith("nessie") || req.module == "iceberg-views") &&
        req.module != "nessie-antlr-runtime"
    ) {
      val module = if (req.module == "nessie") "" else req.module
      val targetBuild =
        if (nessieIcebergProjects.contains(req.module)) "nessie-iceberg" else "nessie"
      val prx = projectFromIncludedBuild(targetBuild, ":$module")
      logger.info(
        "Substituting {}'s dependency to '{}:{}' as project '{}' in build '{}'",
        includedBuildDesc,
        req.group,
        req.module,
        prx.projectPath,
        prx.buildName
      )
      val target = if (prx.projectPath == ":") substitutions.platform(prx) else prx
      useTarget(target, "Managed via $includedBuildDesc")
      return true
    }
  }
  return false
}

fun projectFromIncludedBuild(includedBuild: String, projectPath: String): ProjectComponentSelector {
  // TODO this is dirty, but does its job, which is to substitute dependencies to
  //  org.projectnessie:nessie-* to the projects built by the included Nessie build
  try {
    val inclBuild = gradle.includedBuild(includedBuild)
    val inclBuildInternal = inclBuild as org.gradle.internal.composite.IncludedBuildInternal
    val inclBuildTarget = inclBuildInternal.target
    val nessiePrj = inclBuildTarget.projects.getProject(org.gradle.util.Path.path(projectPath))
    val prjIdent = nessiePrj.componentIdentifier
    return DefaultProjectComponentSelector.newSelector(prjIdent)
  } catch (x: Exception) {
    x.printStackTrace()
    throw x
  }
}

if (includeNessieBuild) {
  logger.lifecycle("Including 'Nessie' from $nessieSourceDir")
  includeBuild(nessieSourceDir) { name = "nessie" }
}

if (includeIcebergBuild) {
  logger.lifecycle("Including 'Iceberg' from $icebergSourceDir")
  includeBuild(icebergSourceDir) {
    name = "iceberg"

    val propertyPattern = Pattern.compile("\\s*(\\S+)\\s*=\\s*(\\S+)\\s*")!!

    // Iceberg's "dependency recommendation" stuff doesn't work when the Iceberg build is included
    // here. So parse Iceberg's versions.props file here and substitute the affected dependencies.
    val icebergVersions = mutableMapOf<String, String>()
    file(projectDir.resolve("versions.props")).forEachLine { line ->
      val m = propertyPattern.matcher(line.trim())
      if (m.matches()) {
        icebergVersions[m.group(1)] = m.group(2)
      }
    }
    // TODO These dependencies are pulled from
    //   'com.google.cloud:google-cloud-bom:0.164.0'
    // via
    //   'com.google.cloud:libraries-bom:24.1.0'
    // but that somehow doesn't work in this case with includedBuild + substituted dependencies
    icebergVersions["com.google.cloud:google-cloud-nio"] = "0.123.17"
    icebergVersions["com.google.cloud:google-cloud-storage"] = "2.2.2"

    // Replace dependencies in the "root" build with projects from the included build.
    // Here: substitute declared
    dependencySubstitution {
      listOf(
          "iceberg-api",
          "iceberg-bundled-guava",
          "iceberg-common",
          "iceberg-core",
          "iceberg-hive-metastore",
          "iceberg-nessie",
          "iceberg-parquet"
        )
        .forEach { moduleName ->
          substitute(module("org.apache.iceberg:$moduleName")).using(project(":$moduleName"))
        }
      // TODO needs to depend on the `shadow` configuration of `:iceberg-bundled-guava`, but that
      //  doesn't really work here :(
      // substitute(module("org.apache.iceberg:iceberg-bundled-guava")).withClassifier("shadow").using(project(":iceberg-bundled-guava"))
      val scalaVersion = scalaRestrictionsForSourceBuild.first()
      System.getProperty("sparkVersions", "3.1,3.2").split(",").forEach { sparkVersion ->
        if (sparkVersion != "3.1" || scalaVersion == "2.12") {
          listOf("spark", "spark-extensions", "spark-runtime").forEach { moduleName ->
            val fullName = "iceberg-$moduleName-${sparkVersion}_$scalaVersion"
            substitute(module("org.apache.iceberg:$fullName"))
              .using(project(":iceberg-spark:$fullName"))
          }
          if (sparkVersion == "3.1") {
            substitute(module("org.apache.iceberg:iceberg-spark3"))
              .using(project(":iceberg-spark:iceberg-spark-3.1_2.12"))
            substitute(module("org.apache.iceberg:iceberg-spark3-extensions"))
              .using(project(":iceberg-spark:iceberg-spark-extensions-3.1_2.12"))
          }
        }
      }
      System.getProperty("flinkVersions", "1.15").split(",").forEach { flinkVersion ->
        substitute(module("org.apache.iceberg:iceberg-flink-$flinkVersion"))
          .using(project(":iceberg-flink:iceberg-flink-$flinkVersion"))
        substitute(module("org.apache.iceberg:iceberg-flink-runtime-$flinkVersion"))
          .using(project(":iceberg-flink:iceberg-flink-runtime-$flinkVersion"))
      }

      val substitutions = this
      all {
        if (!manageNessieProjectDependency("Iceberg", substitutions)) {
          val req = requested
          if (req is ModuleComponentSelector && req.version.isEmpty()) {
            var ver = icebergVersions["${req.group}:${req.module}"]
            if (ver == null) {
              ver = icebergVersions["${req.group}:*"]
            }
            if (ver != null) {
              logger.info(
                "Nessie/Iceberg - managed {}:{} with version {}",
                req.group,
                req.module,
                ver
              )
              useTarget(module("${req.group}:${req.module}:${ver}"), "Managed via Nessie")
            }
          }
        }
      }
    }
  }
}

if (includeNessieBuild) {
  logger.lifecycle("Including 'Nessie-Iceberg' from $nessieSourceDir/nessie-iceberg")
  includeBuild("$nessieSourceDir/nessie-iceberg") {
    name = "nessie-iceberg"

    dependencySubstitution {
      val substitutions = this
      all { manageNessieProjectDependency("Nessie-Iceberg", substitutions) }
    }
  }
}

include("nqeit-nessie-common")

include("nqeit-iceberg-flink-extension")

include("nqeit-iceberg-spark-extension")

include("nqeit-presto-extension")

fun includeProject(artifactId: String, projectDir: File) {
  include(artifactId)
  val p = project(":$artifactId")
  p.projectDir = projectDir
}

for (sparkVersion in sparkVersions) {
  if (restrictedVersion(sparkRestrictions, sparkVersion)) {
    continue
  }
  val scalaVersions =
    frameworksVersions["sparkVersion-${sparkVersion}-scalaVersions"].toString().split(",").map {
      it.trim()
    }
  for (scalaVersion in scalaVersions) {
    if (restrictedVersion(scalaRestrictions, scalaVersion)) {
      continue
    }
    includeProject("nqeit-iceberg-spark-${sparkVersion}_$scalaVersion", file("nqeit-iceberg-spark"))
  }
}

for (flinkVersion in flinkVersions) {
  if (restrictedVersion(flinkRestrictions, flinkVersion)) {
    continue
  }
  includeProject("nqeit-iceberg-flink-$flinkVersion", file("nqeit-iceberg-flink"))
}

for (prestoVersion in prestoVersions) {
  if (restrictedVersion(prestoRestrictions, prestoVersion)) {
    continue
  }
  includeProject("nqeit-presto-$prestoVersion", file("nqeit-presto"))
}

for (crossEngineSetup in
  frameworksVersions["crossEngineSetups"].toString().split(",").map { it.trim() }) {
  val sparkMajor = frameworksVersions["crossEngine.$crossEngineSetup.sparkMajorVersion"] as String
  val scalaMajor = frameworksVersions["crossEngine.$crossEngineSetup.scalaMajorVersion"] as String
  val flinkMajor = frameworksVersions["crossEngine.$crossEngineSetup.flinkMajorVersion"] as String
  if (
    restrictedVersion(sparkRestrictions, sparkMajor) ||
      restrictedVersion(scalaRestrictions, scalaMajor) ||
      restrictedVersion(flinkRestrictions, flinkMajor)
  ) {
    continue
  }
  includeProject(
    "nqeit-cross-engine-$sparkMajor-$scalaMajor-$flinkMajor",
    file("nqeit-cross-engine")
  )
  if (ideSyncActive) {
    break
  }
}

if (false) {
  include("gradle:dependabot")
}
