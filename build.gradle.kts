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

import org.gradle.api.internal.tasks.TaskDependencyContainer

plugins {
  `java-platform`
  id("org.projectnessie.buildsupport.ide-integration")
  `tools-integrations-conventions`
}

val versionAssertJ = "3.23.1"
val versionCheckstyle = "10.3.3"
val versionErrorProneAnnotations = "2.15.0"
val versionErrorProneCore = "2.15.0"
val versionErrorProneSlf4j = "0.1.15"
val versionGoogleJavaFormat = "1.15.0"
val versionGuava = "31.1-jre"
val versionJackson = "2.13.4"
val versionJandex = "2.4.3.Final"
val versionJetbrainsAnnotations = "23.0.0"
val versionJunit = "5.9.1"
val versionJsr305 = "3.0.2"
val versionLogback = "1.2.11"
val versionOpenapi = "3.0"
val versionSlf4j = "2.0.2"

mapOf(
    "versionCheckstyle" to versionCheckstyle,
    "versionErrorProneAnnotations" to versionErrorProneAnnotations,
    "versionErrorProneCore" to versionErrorProneCore,
    "versionErrorProneSlf4j" to versionErrorProneSlf4j,
    "versionGoogleJavaFormat" to versionGoogleJavaFormat,
    "versionJandex" to versionJandex
  )
  .plus(loadProperties(file("build/frameworks-versions-effective.properties")))
  .forEach { (k, v) -> extra[k.toString()] = v }

dependencies {
  constraints {
    api("ch.qos.logback:logback-access:$versionLogback")
    api("ch.qos.logback:logback-classic:$versionLogback")
    api("ch.qos.logback:logback-core:$versionLogback")
    api("com.fasterxml.jackson:jackson-bom:$versionJackson")
    api("com.google.code.findbugs:jsr305:$versionJsr305")
    api("com.google.errorprone:error_prone_annotations:$versionErrorProneAnnotations")
    api("com.google.errorprone:error_prone_core:$versionErrorProneCore")
    api("com.google.googlejavaformat:google-java-format:$versionGoogleJavaFormat")
    api("com.google.guava:guava:$versionGuava")
    api("com.puppycrawl.tools:checkstyle:$versionCheckstyle")
    api("jp.skypencil.errorprone.slf4j:errorprone-slf4j:$versionErrorProneSlf4j")
    api("org.assertj:assertj-core:$versionAssertJ")
    api("org.eclipse.microprofile.openapi:microprofile-openapi-api:$versionOpenapi")
    api("org.jboss:jandex:$versionJandex")
    api("org.junit:junit-bom:$versionJunit")
    api("org.jetbrains:annotations:$versionJetbrainsAnnotations")
    api("org.slf4j:jcl-over-slf4j:$versionSlf4j")
    api("org.slf4j:log4j-over-slf4j:$versionSlf4j")
    api("org.slf4j:slf4j-api:$versionSlf4j")
  }
}

javaPlatform { allowDependencies() }

tasks.named<Wrapper>("wrapper") { distributionType = Wrapper.DistributionType.ALL }

fun findIncludedBuild(includedBuildName: String): IncludedBuild? {
  try {
    return gradle.includedBuild(includedBuildName)
  } catch (x: UnknownDomainObjectException) {
    return null
  }
}

tasks.register("publishLocal") {
  group = "publishing"
  description =
    "Bundles all publishToLocalMaven tasks from the included Nessie build, and its included Iceberg build"

  listOf("nessie", "iceberg", "nessie-iceberg").forEach { includedBuildName ->
    val inclBuild = findIncludedBuild(includedBuildName)
    if (inclBuild != null) {
      val inclBuildInternal = inclBuild as org.gradle.internal.composite.IncludedBuildInternal
      val inclBuildTarget = inclBuildInternal.target
      inclBuildTarget.ensureProjectsLoaded()
      inclBuildTarget.projects.allProjects
        // Exclude the Nessie iceberg-views project, as publishing requires fixing a bunch more
        // dependencies. Nessie's iceberg-views project will disappear once Iceberg has views
        // built-in anyway.
        .filter { projectState -> projectState.name != "iceberg-views" }
        .forEach { projectState ->
          val taskDependency =
            inclBuild.task("${projectState.projectPath}:publishToMavenLocal")
              as TaskDependencyContainer
          val lenientTaskDep = TaskDependencyContainer { context ->
            try {
              taskDependency.visitDependencies(context)
            } catch (x: Exception) {
              logger.debug("Ignoring lazy included-build task dependency {}", x.toString())
            }
          }
          dependsOn(lenientTaskDep)
        }
    }
  }
}
