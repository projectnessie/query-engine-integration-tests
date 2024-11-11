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

import org.gradle.api.internal.artifacts.DefaultModuleIdentifier
import org.gradle.api.internal.artifacts.dependencies.DefaultImmutableVersionConstraint
import org.gradle.internal.component.external.model.DefaultModuleComponentSelector

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
          var moduleComponentSelector = req as DefaultModuleComponentSelector
          if (
            icebergVersionToUse != null &&
              req.group == "org.apache.iceberg" &&
              (req.version.isEmpty() || req.version != icebergVersionToUse)
          ) {
            // TODO get rid of the internal Gradle classes DefaultImmutableVersionConstraint +
            //  DefaultModuleComponentSelector.
            val version = DefaultImmutableVersionConstraint.of(icebergVersionToUse)
            val target =
              DefaultModuleComponentSelector.newSelector(
                req.moduleIdentifier,
                version,
                req.attributes,
                moduleComponentSelector.getCapabilitySelectors()
              )
            useTarget(target, "Managed Iceberg version to $version (attributes: ${req.attributes})")
          }

          if (nessieVersionToUse != null) {
            var groupId =
              if (req.group != "org.projectnessie") req.group else "org.projectnessie.nessie"

            if (
              (req.version.isEmpty() || req.version != nessieVersionToUse) &&
                ((req.group == "org.projectnessie" ||
                  req.group.startsWith("org.projectnessie.nessie")) || req.group != groupId) &&
                (req.module.startsWith("nessie") || req.module == "iceberg-views")
            ) {
              // TODO get rid of the internal Gradle classes DefaultImmutableVersionConstraint +
              //  DefaultModuleComponentSelector, once we can compeletely ignore the
              //  "org.projectnessie" group-ID.
              val version = DefaultImmutableVersionConstraint.of(nessieVersionToUse)
              val target =
                DefaultModuleComponentSelector.newSelector(
                  DefaultModuleIdentifier.newId(groupId, req.module),
                  version,
                  req.attributes,
                  moduleComponentSelector.getCapabilitySelectors()
                )
              useTarget(
                target,
                "Managed Nessie version to $version (attributes: ${req.attributes})"
              )
            }
          }
        }
      }
    }
  }
}
