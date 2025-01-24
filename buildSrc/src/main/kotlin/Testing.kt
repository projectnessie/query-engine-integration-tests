/*
 * Copyright (C) 2023 Dremio
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
import org.gradle.api.UnknownTaskException
import org.gradle.api.tasks.testing.Test
import org.gradle.jvm.toolchain.JavaLanguageVersion
import org.gradle.jvm.toolchain.JavaToolchainService
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.dependencies
import org.gradle.kotlin.dsl.named
import org.gradle.kotlin.dsl.provideDelegate
import org.gradle.kotlin.dsl.register
import org.gradle.kotlin.dsl.withType
import org.projectnessie.nessierunner.gradle.NessieRunnerExtension
import org.projectnessie.nessierunner.gradle.NessieRunnerPlugin

fun Project.nessieConfigureTestTasks() {
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

        forkEvery = 1
      }
    tasks.named("check") { dependsOn(intTest) }
  }

  val externalNessieUrl = System.getProperty("nessie.externalNessieUrl")

  if (externalNessieUrl == null) {
    plugins.withType(NessieRunnerPlugin::class.java).configureEach {
      configure<NessieRunnerExtension> {
        try {
          includeTask(tasks.named<Test>("intTest"))
        } catch (x: UnknownTaskException) {
          // ignore
        }
        environmentNonInput.put("HTTP_ACCESS_LOG_LEVEL", testLogLevel())
        jvmArgumentsNonInput.add("-XX:SelfDestructTimer=30")
      }

      dependencies {
        val nessieServerVersionToUse =
          System.getProperty(
            "nessie.versionNessieServer",
            System.getProperty("nessie.versionNessie"),
          )
        if (nessieServerVersionToUse == null) {
          // Manage to included Nessie build
          add(
            "nessieQuarkusServer",
            mapOf(
              "group" to "org.projectnessie.nessie",
              "name" to "nessie-quarkus",
              "configuration" to "quarkusRunner",
            ),
          )
        } else {
          // Manage to specific Nessie version
          add(
            "nessieQuarkusServer",
            mapOf(
              "group" to "org.projectnessie.nessie",
              "name" to "nessie-quarkus",
              "classifier" to "runner",
              "version" to nessieServerVersionToUse,
            ),
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
      .forEach { (k, v) -> systemProperty(k, v) }

    if (externalNessieUrl != null) {
      systemProperty("quarkus.http.url", externalNessieUrl)
    }
    System.getProperties()
      .filterKeys { (it as String).startsWith("nessie.") }
      .forEach { (k, v) -> systemProperty(k as String, v) }
  }
}

fun Project.forceJavaVersionForTests(requiredJavaVersion: Int) {
  tasks.withType<Test>().configureEach {
    val currentJavaVersion = JavaVersion.current().majorVersion.toInt()
    if (currentJavaVersion != requiredJavaVersion) {
      val javaToolchains = project.extensions.findByType(JavaToolchainService::class.java)
      javaLauncher.set(
        javaToolchains!!.launcherFor {
          languageVersion.set(JavaLanguageVersion.of(requiredJavaVersion))
        }
      )
    }
  }
}
