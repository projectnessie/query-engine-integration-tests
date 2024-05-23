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

val sparkScala = getSparkScalaVersionsForProject()

dependencies {
  // picks the right dependencies for scala compilation
  forScala(sparkScala.scalaVersion)

  testCompileOnly(platform(rootProject))

  implementation("org.projectnessie:nessie-client")
  implementation(project(":nqeit-nessie-common"))
  implementation(project(":nqeit-iceberg-spark-extension"))
  compileOnly(libs.microprofile.openapi)

  icebergSparkDependencies("implementation", sparkScala, project)

  testImplementation(platform(libs.junit.bom))
  testImplementation(libs.bundles.junit.testing)
  testRuntimeOnly(libs.junit.jupiter.engine)
  testRuntimeOnly(libs.logback.classic)
  testRuntimeOnly(libs.slf4j.log4j.over.slf4j)
}

// Note: Nessie-Quarkus server dependency and Projectnessie plugin are automatically configured,
// when the Projectnessie plugin's included in the `plugins` section.

forceJava11ForTests()

tasks.withType<Test>().configureEach {
  systemProperty("sparkScala.sparkMajorVersion", sparkScala.sparkMajorVersion)
  systemProperty("sparkScala.sparkVersion", sparkScala.sparkVersion)
  systemProperty("sparkScala.scalaMajorVersion", sparkScala.scalaMajorVersion)
  systemProperty("sparkScala.scalaVersion", sparkScala.scalaVersion)
}
