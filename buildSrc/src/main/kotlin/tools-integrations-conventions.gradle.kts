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

plugins { id("org.caffinitas.gradle.testrerun") }

val hasSrcMain = projectDir.resolve("src/main").exists()
val hasSrcTest = projectDir.resolve("src/test").exists()

nessieConfigureSpotless()

nessieConfigureJandex()

nessieConfigureJava()

nessieConfigureScala()

nessieIde()

if (hasSrcMain || hasSrcTest) {
  nessieConfigureCheckstyle()

  nessieConfigureErrorprone()

  if (hasSrcTest) {
    nessieConfigureTestTasks()
  }
}

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
            useTarget(
              "${req.group}:${req.module}:$icebergVersionToUse",
              "Managed Iceberg version to $version (attributes: ${req.attributes})"
            )
          }

          if (req.group == "org.projectnessie") {
            throw GradleException("Use of group ID org.projectnessie is no longer supported.")
          }

          if (
            nessieVersionToUse != null &&
              (req.group.startsWith("org.projectnessie.nessie")) &&
              (req.module.startsWith("nessie") || req.module == "iceberg-views") &&
              (req.version.isEmpty() || req.version != nessieVersionToUse)
          ) {
            useTarget(
              "${req.group}:${req.module}:$nessieVersionToUse",
              "Managed Nessie version to $version (attributes: ${req.attributes})"
            )
          }
        }
      }
    }
  }
}
