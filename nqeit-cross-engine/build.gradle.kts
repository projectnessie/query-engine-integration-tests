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

val crossEngine = getCrossEngineVersionsForProject()

dependencies {
  // picks the right dependencies for scala compilation
  forScala(crossEngine.sparkScala.scalaVersion)

  testCompileOnly(platform(rootProject))
  testCompileOnly(libs.jetbrains.annotations)
  testCompileOnly(libs.errorprone.annotations)

  implementation("org.projectnessie.nessie:nessie-client")
  implementation(project(":nqeit-nessie-common"))
  implementation(project(":nqeit-iceberg-spark-extension"))
  implementation(project(":nqeit-iceberg-flink-extension"))
  implementation(project(":nqeit-iceberg-dremio-extension"))
  compileOnly(libs.microprofile.openapi)
  icebergSparkDependencies("implementation", crossEngine.sparkScala, project)
  icebergFlinkDependencies("implementation", crossEngine.flink)

  testImplementation(platform(libs.junit.bom))
  testImplementation(libs.bundles.junit.testing)
  testRuntimeOnly(libs.junit.jupiter.engine)
  testRuntimeOnly(libs.logback.classic)
  testRuntimeOnly(libs.slf4j.log4j.over.slf4j)
  testRuntimeOnly(libs.awssdk.bundle)
  testRuntimeOnly(libs.awssdk.client)
}

// Note: Nessie-Quarkus server dependency and Projectnessie plugin are automatically configured,
// when the Projectnessie plugin's included in the `plugins` section.

forceJava11ForTests()

tasks.withType<Test>().configureEach {
  // Must disable hadoop-native - query engines use conflicting Hadoop versions :sign:
  systemProperty("hive.dfs.require-hadoop-native", "false")
  //
  systemProperty("sparkScala.sparkMajorVersion", crossEngine.sparkScala.sparkMajorVersion)
  systemProperty("sparkScala.sparkVersion", crossEngine.sparkScala.sparkVersion)
  systemProperty("sparkScala.scalaMajorVersion", crossEngine.sparkScala.scalaMajorVersion)
  systemProperty("sparkScala.scalaVersion", crossEngine.sparkScala.scalaVersion)
  systemProperty("flink.flinkMajorVersion", crossEngine.flink.flinkMajorVersion)
  systemProperty("flink.flinkVersion", crossEngine.flink.flinkVersion)
  systemProperty("flink.hadoopVersion", crossEngine.flink.hadoopVersion)
  systemProperty("flink.scalaMajorVersion", crossEngine.flink.scalaMajorVersion)
}
