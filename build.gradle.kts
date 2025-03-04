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

plugins { `tools-integrations-conventions` }

mapOf(
    "versionCheckstyle" to libs.versions.checkstyle.get(),
    "versionErrorProneAnnotations" to libs.versions.errorprone.get(),
    "versionErrorProneCore" to libs.versions.errorprone.get(),
    "versionErrorProneSlf4j" to libs.versions.errorproneSlf4j.get(),
    "versionGoogleJavaFormat" to libs.versions.googleJavaFormat.get(),
    "versionJandex" to libs.versions.jandex.get(),
  )
  .plus(loadProperties(file("build/frameworks-versions-effective.properties")))
  .forEach { (k, v) -> extra[k.toString()] = v }

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
