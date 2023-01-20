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

import com.github.vlsi.jandex.JandexProcessResources
import java.io.File
import java.util.*
import org.gradle.api.JavaVersion
import org.gradle.api.Project
import org.gradle.api.artifacts.Dependency
import org.gradle.api.artifacts.ExternalModuleDependency
import org.gradle.api.artifacts.ModuleDependency
import org.gradle.api.artifacts.VersionCatalogsExtension
import org.gradle.api.attributes.Bundling
import org.gradle.api.logging.LogLevel
import org.gradle.api.tasks.SourceSetContainer
import org.gradle.api.tasks.testing.Test
import org.gradle.jvm.toolchain.JavaLanguageVersion
import org.gradle.jvm.toolchain.JavaToolchainService
import org.gradle.kotlin.dsl.DependencyHandlerScope
import org.gradle.kotlin.dsl.add
import org.gradle.kotlin.dsl.exclude
import org.gradle.kotlin.dsl.extra
import org.gradle.kotlin.dsl.getByType
import org.gradle.kotlin.dsl.provideDelegate
import org.gradle.kotlin.dsl.withType

/**
 * Apply the given `sparkVersion` as a `strictly` version constraint and [withSparkExcludes] on the
 * current [Dependency].
 */
fun ModuleDependency.forSpark(sparkVersion: String): ModuleDependency {
  val dep = this as ExternalModuleDependency
  dep.version { strictly(sparkVersion) }
  return this.withSparkExcludes()
}

/** Apply a bunch of common dependency-exclusion to the current Spark [Dependency]. */
fun ModuleDependency.withSparkExcludes(): ModuleDependency {
  return this.exclude("commons-logging", "commons-logging")
    .exclude("log4j", "log4j")
    .exclude("org.slf4j", "slf4j-log4j12")
    .exclude("org.eclipse.jetty", "jetty-util")
    .exclude("org.apache.avro", "avro")
    .exclude("org.apache.arrow", "arrow-vector")
}

fun DependencyHandlerScope.forScala(scalaVersion: String) {
  // Note: Quarkus contains Scala dependencies since 2.9.0
  add("implementation", "org.scala-lang:scala-library") { version { strictly(scalaVersion) } }
  add("implementation", "org.scala-lang:scala-reflect") { version { strictly(scalaVersion) } }
}

/**
 * Forces all [Test] tasks to use Java 11 for test execution, which is mandatory for tests using
 * Spark.
 */
fun Project.forceJava11ForTests() {
  if (!JavaVersion.current().isJava11) {
    tasks.withType(Test::class.java).configureEach {
      val javaToolchains = project.extensions.findByType(JavaToolchainService::class.java)
      javaLauncher.set(
        javaToolchains!!.launcherFor { languageVersion.set(JavaLanguageVersion.of(11)) }
      )
    }
  }
}

fun Project.dependencyVersion(key: String) = rootProject.extra[key].toString()

fun Project.testLogLevel() =
  System.getProperty(
    "test.log.level",
    when (gradle.startParameter.logLevel) {
      LogLevel.DEBUG -> "DEBUG"
      LogLevel.INFO -> "INFO"
      LogLevel.LIFECYCLE,
      LogLevel.WARN -> "WARN"
      LogLevel.QUIET,
      LogLevel.ERROR -> "ERROR"
    }
  )

/** Just load [Properties] from a [File]. */
fun loadProperties(file: File): Properties {
  val props = Properties()
  file.reader().use { reader -> props.load(reader) }
  return props
}

/** Hack for Jandex-Plugin (removed later). */
fun Project.useBuildSubDirectory(buildSubDir: String) {
  buildDir = file("$buildDir/$buildSubDir")

  // TODO open an issue for the Jandex plugin - it configures the task's output directory too
  //  early, so re-assigning the output directory (project.buildDir=...) to a different path
  //  isn't reflected in the Jandex output.
  tasks.withType<JandexProcessResources>().configureEach {
    val sourceSets: SourceSetContainer by project
    sourceSets.all { destinationDir = this.output.resourcesDir!! }
  }
}

/** Resolves the Spark and Scala major versions for all `nqeit-iceberg-spark*` projects. */
fun Project.getSparkScalaVersionsForProject(): SparkScalaVersions {
  val sparkScala = project.name.split("-").last().split("_")

  val sparkMajorVersion = if (sparkScala[0][0].isDigit()) sparkScala[0] else "3.2"
  val scalaMajorVersion = sparkScala[1]

  useBuildSubDirectory("$sparkMajorVersion-$scalaMajorVersion")

  return useSparkScalaVersionsForProject(sparkMajorVersion, scalaMajorVersion)
}

fun Project.useSparkScalaVersionsForProject(
  sparkMajorVersion: String,
  scalaMajorVersion: String
): SparkScalaVersions {
  return SparkScalaVersions(
    sparkMajorVersion,
    scalaMajorVersion,
    dependencyVersion("versionSpark-$sparkMajorVersion"),
    dependencyVersion("versionScala-$scalaMajorVersion")
  )
}

class SparkScalaVersions(
  val sparkMajorVersion: String,
  val scalaMajorVersion: String,
  val sparkVersion: String,
  val scalaVersion: String
) {}

/** Resolves the Flink and Scala major versions for all `nqeit-iceberg-flink*` projects. */
fun Project.getFlinkVersionsForProject(): FlinkVersions {
  val flinkMajorVersion = project.name.split("-").last()

  useBuildSubDirectory(flinkMajorVersion)

  return useFlinkVersionsForProject(flinkMajorVersion)
}

fun Project.useFlinkVersionsForProject(flinkMajorVersion: String): FlinkVersions {
  val scalaMajorVersion = dependencyVersion("flink-scala-$flinkMajorVersion")
  return useFlinkVersionsForProject(flinkMajorVersion, scalaMajorVersion)
}

fun Project.useFlinkVersionsForProject(
  flinkMajorVersion: String,
  scalaMajorVersion: String
): FlinkVersions {
  val scalaForDependencies = dependencyVersion("flink-scalaForDependencies-$flinkMajorVersion")
  return useFlinkVersionsForProject(flinkMajorVersion, scalaMajorVersion, scalaForDependencies)
}

fun Project.useFlinkVersionsForProject(
  flinkMajorVersion: String,
  scalaMajorVersion: String,
  scalaForDependencies: String
): FlinkVersions {
  return FlinkVersions(
    flinkMajorVersion,
    scalaMajorVersion,
    scalaForDependencies,
    dependencyVersion("versionFlink-$flinkMajorVersion"),
    dependencyVersion("versionFlinkHadoop-$flinkMajorVersion")
  )
}

class FlinkVersions(
  val flinkMajorVersion: String,
  val scalaMajorVersion: String,
  val scalaForDependencies: String,
  val flinkVersion: String,
  val hadoopVersion: String
) {}

/** Resolves the Presto versions for all `nqeit-presto*` projects. */
fun Project.getPrestoVersionsForProject(): PrestoVersions {
  val prestoMajorVersion = project.name.split("-").last()

  useBuildSubDirectory(prestoMajorVersion)

  return usePrestoVersionsForProject(prestoMajorVersion)
}

fun Project.usePrestoVersionsForProject(prestoMajorVersion: String): PrestoVersions {
  return PrestoVersions(prestoMajorVersion, dependencyVersion("versionPresto-$prestoMajorVersion"))
}

class PrestoVersions(val prestoMajorVersion: String, val prestoVersion: String) {}

fun Project.getCrossEngineVersionsForProject(): CrossEngineVersions {
  val splits = project.name.split("-")
  val sparkMajorVersion = splits[3]
  val scalaMajorVersion = splits[4]
  val flinkMajorVersion = splits[5]

  useBuildSubDirectory("spark-$sparkMajorVersion-$scalaMajorVersion-flink-$flinkMajorVersion")

  return CrossEngineVersions(
    useSparkScalaVersionsForProject(sparkMajorVersion, scalaMajorVersion),
    useFlinkVersionsForProject(flinkMajorVersion, scalaMajorVersion)
  )
}

class CrossEngineVersions(val sparkScala: SparkScalaVersions, val flink: FlinkVersions) {}

fun majorVersion(version: String): String {
  val elems = version.split(".")
  return "${elems[0]}.${elems[1]}"
}

fun DependencyHandlerScope.icebergSparkDependencies(
  configuration: String,
  sparkScala: SparkScalaVersions,
  project: Project
) {
  add(configuration, "org.apache.iceberg:iceberg-api")
  add(configuration, "org.apache.iceberg:iceberg-aws")
  add(configuration, "org.apache.iceberg:iceberg-nessie")
  add(
    configuration,
    "org.apache.iceberg:iceberg-spark-${sparkScala.sparkMajorVersion}_${sparkScala.scalaMajorVersion}"
  )
  add(
    configuration,
    "org.apache.iceberg:iceberg-spark-extensions-${sparkScala.sparkMajorVersion}_${sparkScala.scalaMajorVersion}"
  )

  val nessieMajor = majorVersion(System.getProperty("nessie.versionNessie", "99.99")).toDouble()
  if (nessieMajor < 0.40) {
    when (sparkScala.sparkMajorVersion) {
      "3.1" -> add(configuration, "org.projectnessie:nessie-spark-extensions")
      "3.2" -> add(configuration, "org.projectnessie:nessie-spark-3.2-extensions")
    }
  } else {
    add(
      configuration,
      "org.projectnessie:nessie-spark-extensions-${sparkScala.sparkMajorVersion}_${sparkScala.scalaMajorVersion}"
    ) {
      attributes {
        attribute(
          Bundling.BUNDLING_ATTRIBUTE,
          project.objects.named(Bundling::class.java, Bundling.SHADOWED)
        )
      }
    }
  }

  add(configuration, "org.apache.spark:spark-sql_${sparkScala.scalaMajorVersion}") {
    forSpark(sparkScala.sparkVersion)
  }
  add(configuration, "org.apache.spark:spark-core_${sparkScala.scalaMajorVersion}") {
    forSpark(sparkScala.sparkVersion)
  }
  add(configuration, "org.apache.spark:spark-hive_${sparkScala.scalaMajorVersion}") {
    forSpark(sparkScala.sparkVersion)
  }

  if (sparkScala.sparkMajorVersion == "3.3") {
    val versionCatalog = project.extensions.getByType<VersionCatalogsExtension>().named("libs")
    val jacksonBomDep =
      versionCatalog.findLibrary("jackson-bom").orElseThrow {
        IllegalStateException("No library 'jackson-bom' defined in version catalog 'libs'")
      }
    add(configuration, platform(jacksonBomDep))
  }
}

fun DependencyHandlerScope.icebergFlinkDependencies(configuration: String, flink: FlinkVersions) {
  add(configuration, "org.apache.iceberg:iceberg-core")
  add(configuration, "org.apache.iceberg:iceberg-api")
  add(configuration, "org.apache.iceberg:iceberg-aws")
  add(configuration, "org.apache.iceberg:iceberg-flink-${flink.flinkMajorVersion}")
  add(configuration, "org.apache.iceberg:iceberg-nessie")

  add(configuration, "org.apache.flink:flink-connector-test-utils:${flink.flinkVersion}")
  add(configuration, "org.apache.flink:flink-core:${flink.flinkVersion}")
  add(configuration, "org.apache.flink:flink-runtime:${flink.flinkVersion}")
  add(
    configuration,
    "org.apache.flink:flink-streaming-java${flink.scalaForDependencies}:${flink.flinkVersion}"
  )
  add(
    configuration,
    "org.apache.flink:flink-table-planner_${flink.scalaMajorVersion}:${flink.flinkVersion}"
  )
  add(configuration, "org.apache.flink:flink-table-api-java:${flink.flinkVersion}")
  add(
    configuration,
    "org.apache.flink:flink-table-api-java-bridge${flink.scalaForDependencies}:${flink.flinkVersion}"
  )
  add(configuration, "org.apache.flink:flink-connector-base:${flink.flinkVersion}")
  add(configuration, "org.apache.flink:flink-connector-files:${flink.flinkVersion}")
  add(configuration, "org.apache.flink:flink-metrics-dropwizard:${flink.flinkVersion}")

  add(configuration, "org.apache.flink:flink-test-utils-junit:${flink.flinkVersion}") {
    exclude("junit")
  }
  add(
    configuration,
    "org.apache.flink:flink-test-utils${flink.scalaForDependencies}:${flink.flinkVersion}"
  )

  add("testRuntimeOnly", "org.apache.hadoop:hadoop-common:${flink.hadoopVersion}")
  add("testRuntimeOnly", "org.apache.hadoop:hadoop-hdfs:${flink.hadoopVersion}")
  add("testRuntimeOnly", "org.apache.hadoop:hadoop-mapreduce-client-core:${flink.hadoopVersion}")
}

fun DependencyHandlerScope.prestoDependencies(configuration: String, presto: PrestoVersions) {
  add(configuration, "com.facebook.presto:presto-jdbc:${presto.prestoVersion}")
  add(configuration, "com.facebook.presto:presto-main:${presto.prestoVersion}")
  add(configuration, "com.facebook.presto:presto-iceberg:${presto.prestoVersion}")

  add(
    "test${configuration.capitalize()}",
    "com.facebook.presto:presto-tests:${presto.prestoVersion}"
  )
}
