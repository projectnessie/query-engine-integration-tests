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
import org.gradle.internal.component.local.model.DefaultProjectComponentSelector

if (!JavaVersion.current().isCompatibleWith(JavaVersion.VERSION_11)) {
  throw GradleException("Build requires Java 11")
}

val baseVersion = file("version.txt").readText().trim()

pluginManagement {
  repositories {
    mavenCentral() // prefer Maven Central, in case Gradle's repo has issues
    gradlePluginPortal()
    if (System.getProperty("withMavenLocal").toBoolean()) {
      mavenLocal()
    }
  }
}

plugins { id("com.gradle.develocity") version ("3.17.1") }

develocity {
  if (System.getenv("CI") != null) {
    buildScan {
      termsOfUseUrl = "https://gradle.com/terms-of-service"
      termsOfUseAgree = "yes"
      // Add some potentially interesting information from the environment
      listOf(
          "GITHUB_ACTION_REPOSITORY",
          "GITHUB_ACTOR",
          "GITHUB_BASE_REF",
          "GITHUB_HEAD_REF",
          "GITHUB_JOB",
          "GITHUB_REF",
          "GITHUB_REPOSITORY",
          "GITHUB_RUN_ID",
          "GITHUB_RUN_NUMBER",
          "GITHUB_SHA",
          "GITHUB_WORKFLOW"
        )
        .forEach { e ->
          val v = System.getenv(e)
          if (v != null) {
            value(e, v)
          }
        }
      val ghUrl = System.getenv("GITHUB_SERVER_URL")
      if (ghUrl != null) {
        val ghRepo = System.getenv("GITHUB_REPOSITORY")
        val ghRunId = System.getenv("GITHUB_RUN_ID")
        link("Summary", "$ghUrl/$ghRepo/actions/runs/$ghRunId")
        link("PRs", "$ghUrl/$ghRepo/pulls")
      }
    }
  } else {
    buildScan { publishing { onlyIf { gradle.startParameter.isBuildScan } } }
  }
}

rootProject.name = "NesQuEIT"

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
"""
    .trimIndent()
)

fun updateDefaultVersion(restrictions: Set<String>, project: String) {
  if (restrictions.isNotEmpty()) {
    val ver = restrictions.first()
    if (frameworksVersions.containsKey("version${project.capitalize()}-$ver")) {
      frameworksVersions["${project}DefaultVersion"] = ver
    }
  }
}

/**
 * Combines two sets of version restrictions. An empty set means "do not care", so the "other" one
 * will be returned. If both sets are not empty, the common set will be returned.
 */
fun versionRestrictions(
  currentRestrictions: Set<String>,
  intersectRestrictions: Set<String>
): Set<String> {
  if (currentRestrictions.isEmpty()) {
    return intersectRestrictions
  }
  if (intersectRestrictions.isEmpty()) {
    return currentRestrictions
  }
  return currentRestrictions.intersect(intersectRestrictions)
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

  val sparkScala = loadProperties(file("$nessieSourceDir/integrations/spark-scala.properties"))
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
    nessieIcebergProjects.add("nessie-spark-extensions-basetests_$scalaVersion")
  }
  nessieIcebergProjects.add("nessie-spark-extensions-base")
  nessieIcebergProjects.add("nessie-spark-extensions")
  nessieIcebergProjects.add("nessie-spark-3.2-extensions")

  return nessieIcebergProjects
}

val nessieIcebergProjects = if (includeNessieBuild) loadNessieIcebergProjects() else setOf()

val includedNessieVersion =
  if (includeNessieBuild) file("$nessieSourceDir/version.txt").readText().trim() else "NONE"

fun DependencySubstitution.manageNessieProjectDependency(
  includedBuildDesc: String,
  substitutions: DependencySubstitutions
): Boolean {
  val req = requested
  if (req is ModuleComponentSelector) {
    if (
      (req.group == "org.projectnessie.nessie" ||
        req.group == "org.projectnessie.nessie-integrations" ||
        (req.group == "org.projectnessie" && !req.module.startsWith("nessie-apprunner"))) &&
        req.version.isEmpty()
    ) {
      val module = if (req.module == "nessie") "" else req.module
      val targetBuild =
        if (nessieIcebergProjects.contains(req.module)) "nessie-iceberg" else "nessie"

      // project() doesn't handle included builds :(
      // useTarget(project(":$targetBuild:$module"), "Project managed via $includedBuildDesc")

      // GAV doesn't work, because GAV is not automatically resolved to a Gradle project :(
      // useTarget(mapOf("group" to req.group, "name" to req.module, "version" to
      // includedNessieVersion), "Version managed via $includedBuildDesc")

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

    var icebergVersions = mutableMapOf<String, String>()
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
          "iceberg-aws",
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
      System.getProperty("flinkVersions", "1.16").split(",").forEach { flinkVersion ->
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

include("nqeit-iceberg-dremio-extension")

fun includeProject(artifactId: String, projectDir: File) {
  include(artifactId)
  val p = project(":$artifactId")
  p.projectDir = projectDir
}

include("nqeit-iceberg-dremio")

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
