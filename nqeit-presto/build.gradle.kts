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

plugins {
  `java-library`
  `tools-integrations-conventions`
  id("org.projectnessie")
}

val presto = getPrestoVersionsForProject()

dependencies {
  implementation(platform(rootProject))
  implementation(project(":nqeit-nessie-common"))
  implementation(project(":nqeit-presto-extension"))

  prestoDependencies("implementation", presto)

  commonTestDependencies()
}

// Note: Nessie-Quarkus server dependency and Projectnessie plugin are automatically configured,
// when the Projectnessie plugin's included in the `plugins` section.

tasks.withType<Test>().configureEach {
  systemProperty("presto.prestoMajorVersion", presto.prestoMajorVersion)
  systemProperty("presto.prestoVersion", presto.prestoVersion)
}
