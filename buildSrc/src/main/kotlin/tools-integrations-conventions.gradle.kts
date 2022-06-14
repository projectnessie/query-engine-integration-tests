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

import org.gradle.api.JavaVersion
import org.gradle.api.Project
import org.gradle.api.file.DuplicatesStrategy
import org.gradle.api.plugins.JavaPlugin
import org.gradle.api.plugins.JavaPluginExtension
import org.gradle.api.tasks.bundling.Jar
import org.gradle.api.tasks.compile.JavaCompile
import org.gradle.api.tasks.javadoc.Javadoc
import org.gradle.api.tasks.testing.Test
import org.gradle.external.javadoc.CoreJavadocOptions
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.maven
import org.gradle.kotlin.dsl.named
import org.gradle.kotlin.dsl.provideDelegate
import org.gradle.kotlin.dsl.register
import org.gradle.kotlin.dsl.repositories
import org.gradle.kotlin.dsl.withType
import org.projectnessie.quarkus.gradle.QuarkusAppExtension
import org.projectnessie.quarkus.gradle.QuarkusAppPlugin

plugins {
  id("org.caffinitas.gradle.testrerun")
  id("org.projectnessie.buildsupport.checkstyle")
  id("org.projectnessie.buildsupport.jandex")
  id("org.projectnessie.buildsupport.spotless")
  id("org.projectnessie.buildsupport.errorprone")
}

configureJava()

testTasks()

configureIntTests()

configurations.all {
  if (name != "nessieQuarkusServer") {
    resolutionStrategy.dependencySubstitution {
      all {
        val nessieVersionToUse = System.getProperty("nessie.versionNessie")
        val icebergVersionToUse = System.getProperty("nessie.versionIceberg")
        val req = requested
        if (req is ModuleComponentSelector) {
          if (
            icebergVersionToUse != null &&
              req.group == "org.apache.iceberg" &&
              (req.version.isEmpty() || req.version != icebergVersionToUse)
          ) {
            val target = "${req.group}:${req.module}:$icebergVersionToUse"
            useTarget(target, "Managed Iceberg version to $icebergVersionToUse")
          }
          if (
            nessieVersionToUse != null &&
              (req.version.isEmpty() || req.version != nessieVersionToUse) &&
              req.group == "org.projectnessie" &&
              req.module != "nessie-antlr-runtime" &&
              (req.module.startsWith("nessie") || req.module == "iceberg-views")
          ) {
            val target = "${req.group}:${req.module}:$nessieVersionToUse"
            useTarget(target, "Managed Nessie version to $nessieVersionToUse")
          }
        }
      }
    }
  }
}

fun Project.configureIntTests() {
  val externalNessieUrl = System.getProperty("nessie.externalNessieUrl")

  if (externalNessieUrl == null) {
    plugins.withType(QuarkusAppPlugin::class.java).configureEach {
      configure<QuarkusAppExtension> {
        try {
          includeTask(tasks.named<Test>("intTest"))
        } catch (x: UnknownTaskException) {
          // ignore
        }
        environmentNonInput.put("HTTP_ACCESS_LOG_LEVEL", testLogLevel())
      }

      dependencies {
        val nessieServerVersionToUse =
          System.getProperty(
            "nessie.versionNessieServer",
            System.getProperty("nessie.versionNessie")
          )
        if (nessieServerVersionToUse == null) {
          // Manage to included Nessie build
          add(
            "nessieQuarkusServer",
            mapOf(
              "group" to "org.projectnessie",
              "name" to "nessie-quarkus",
              "configuration" to "quarkusRunner"
            )
          )
        } else {
          // Manage to specific Nessie version
          add(
            "nessieQuarkusServer",
            mapOf(
              "group" to "org.projectnessie",
              "name" to "nessie-quarkus",
              "classifier" to "runner",
              "version" to nessieServerVersionToUse
            )
          )
        }
      }
    }
  }

  tasks.withType<Test>().configureEach {
    System.getProperties()
      .mapKeys { it.key.toString() }
      .filterKeys {
        it.startsWith("spark.") ||
          it.startsWith("hive.") ||
          it.startsWith("hadoop.") ||
          it.startsWith("presto.") ||
          it.startsWith("flink.") ||
          it.startsWith("nessie.")
      }
      .forEach { k, v -> systemProperty(k, v) }

    if (externalNessieUrl != null) {
      systemProperty("quarkus.http.url", externalNessieUrl)
    }
    System.getProperties()
      .filterKeys { (it as String).startsWith("nessie.") }
      .forEach { k, v -> systemProperty(k as String, v) }
  }
}

fun Project.testTasks() {
  if (projectDir.resolve("src/test").exists()) {
    tasks.withType<Test>().configureEach {
      useJUnitPlatform {}
      val testJvmArgs: String? by project
      val testHeapSize: String? by project
      if (testJvmArgs != null) {
        jvmArgs((testJvmArgs as String).split(" "))
      }
      if (testHeapSize != null) {
        setMinHeapSize(testHeapSize)
        setMaxHeapSize(testHeapSize)
      }

      systemProperty("file.encoding", "UTF-8")
      systemProperty("user.language", "en")
      systemProperty("user.country", "US")
      systemProperty("user.variant", "")
      systemProperty("test.log.level", testLogLevel())
      filter {
        isFailOnNoMatchingTests = false
        when (name) {
          "test" -> {
            includeTestsMatching("*Test")
            includeTestsMatching("Test*")
            excludeTestsMatching("Abstract*")
            excludeTestsMatching("IT*")
          }
          "intTest" -> includeTestsMatching("IT*")
        }
      }
      if (name != "test") {
        mustRunAfter(tasks.named<Test>("test"))
      }
    }
    val intTest =
      tasks.register<Test>("intTest") {
        group = "verification"
        description = "Runs the integration tests."

        setForkEvery(1)
      }
    tasks.named("check") { dependsOn(intTest) }
  }
}

fun Project.configureJava() {
  tasks.withType<Jar>().configureEach {
    manifest {
      attributes["Implementation-Title"] = "Nessie ${project.name}"
      attributes["Implementation-Version"] = project.version
      attributes["Implementation-Vendor"] = "Dremio"
    }
    duplicatesStrategy = DuplicatesStrategy.WARN
  }

  repositories {
    mavenCentral { content { excludeVersionByRegex("io[.]delta", ".*", ".*-nessie") } }
    maven("https://storage.googleapis.com/nessie-maven") {
      name = "Nessie Delta custom Repository"
      content { includeVersionByRegex("io[.]delta", ".*", ".*-nessie") }
    }
    if (System.getProperty("withMavenLocal").toBoolean()) {
      mavenLocal()
    }
  }

  tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.compilerArgs.add("-parameters")

    // Required to enable incremental compilation w/ immutables, see
    // https://github.com/immutables/immutables/pull/858 and
    // https://github.com/immutables/immutables/issues/804#issuecomment-487366544
    options.compilerArgs.add("-Aimmutables.gradle.incremental")
  }

  tasks.withType<Javadoc>().configureEach {
    val opt = options as CoreJavadocOptions
    // don't spam log w/ "warning: no @param/@return"
    opt.addStringOption("Xdoclint:-reference", "-quiet")
  }

  plugins.withType<JavaPlugin>().configureEach {
    configure<JavaPluginExtension> {
      withJavadocJar()
      withSourcesJar()
      sourceCompatibility = JavaVersion.VERSION_1_8
      targetCompatibility = JavaVersion.VERSION_1_8
    }
  }
}
