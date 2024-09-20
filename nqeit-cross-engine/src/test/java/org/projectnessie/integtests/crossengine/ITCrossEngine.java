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
package org.projectnessie.integtests.crossengine;

import static java.lang.String.format;
import static java.util.Arrays.asList;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.params.provider.Arguments.arguments;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.apache.spark.sql.SparkSession;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.projectnessie.client.api.NessieApiV2;
import org.projectnessie.error.NessieNamespaceAlreadyExistsException;
import org.projectnessie.integtests.flink.Flink;
import org.projectnessie.integtests.flink.FlinkHelper;
import org.projectnessie.integtests.flink.IcebergFlinkExtension;
import org.projectnessie.integtests.iceberg.spark.IcebergSparkExtension;
import org.projectnessie.integtests.iceberg.spark.Spark;
import org.projectnessie.integtests.nessie.NessieAPI;
import org.projectnessie.integtests.nessie.NessieDefaultBranch;
import org.projectnessie.integtests.nessie.NessieTestsExtension;

@ExtendWith({IcebergSparkExtension.class, IcebergFlinkExtension.class, NessieTestsExtension.class})
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class ITCrossEngine {

  private static final String NAMESPACE = System.getProperty("nesqueit.namespace", "db");

  private static String sparkTableIdentifier(String tableName) {
    return String.format("nessie.%s.%s", NAMESPACE, tableName);
  }

  @Order(20)
  @Test
  public void createNamespace(@NessieAPI NessieApiV2 nessie, @NessieDefaultBranch String branch)
      throws Exception {
    try {
      nessie.createNamespace().namespace(NAMESPACE).refName(branch).create();
    } catch (NessieNamespaceAlreadyExistsException ignore) {
      // ignore
    }
  }

  @Order(100)
  @Test
  public void createTables(@Spark SparkSession spark) {
    spark.sql(format("CREATE TABLE %s (id int, val string)", sparkTableIdentifier("from_spark")));
  }

  @Order(101)
  @Test
  public void createTables(@Flink FlinkHelper flink) {
    flink.sql("CREATE TABLE %s (id int, val string)", flink.qualifiedTableName("from_flink"));
  }

  static Stream<Arguments> tables() {
    return Stream.of(arguments("from_spark"), arguments("from_flink"));
  }

  private static final Map<String, List<List<Object>>> tableRows = new HashMap<>();

  private static void tableAddRow(String table, Object... values) {
    tableRows.computeIfAbsent(table, t -> new ArrayList<>()).add(asList(values));
  }

  @Order(110)
  @ParameterizedTest
  @MethodSource("tables")
  public void insertIntoSpark(String table, @Spark SparkSession spark) {
    spark.sql(format("INSERT INTO %s select 100, \"from-spark\"", sparkTableIdentifier(table)));
    tableAddRow(table, 100, "from-spark");
  }

  @Order(111)
  @ParameterizedTest
  @MethodSource("tables")
  public void insertIntoFlink(String table, @Flink FlinkHelper flink) {
    flink.sql(
        "INSERT INTO %s (id, val) VALUES (200, 'from-flink')", flink.qualifiedTableName(table));
    tableAddRow(table, 200, "from-flink");
  }

  @Order(120)
  @ParameterizedTest
  @MethodSource("tables")
  public void selectFromSpark(String table, @Spark SparkSession spark) {
    assertThat(
            spark
                .sql(format("SELECT id, val FROM %s", sparkTableIdentifier(table)))
                .collectAsList()
                .stream()
                .map(r -> asList(r.get(0), r.get(1))))
        .containsExactlyInAnyOrderElementsOf(tableRows.get(table));
  }

  @Order(121)
  @ParameterizedTest
  @MethodSource("tables")
  public void selectFromFlink(String table, @Flink FlinkHelper flink) {
    assertThat(flink.sql("SELECT id, val FROM %s", flink.qualifiedTableName(table)))
        .map(r -> Arrays.asList(r.getField(0), r.getField(1)))
        .containsExactlyInAnyOrderElementsOf(tableRows.get(table));
  }

  @Order(130)
  @ParameterizedTest
  @MethodSource("tables")
  public void insertIntoSpark2(String table, @Spark SparkSession spark) {
    spark.sql(format("INSERT INTO %s select 101, \"from-spark\"", sparkTableIdentifier(table)));
    tableAddRow(table, 101, "from-spark");
  }

  @Order(131)
  @ParameterizedTest
  @MethodSource("tables")
  public void insertIntoFlink2(String table, @Flink FlinkHelper flink) {
    flink.sql(
        "INSERT INTO %s (id, val) VALUES (201, 'from-flink')", flink.qualifiedTableName(table));
    tableAddRow(table, 201, "from-flink");
  }

  @Order(140)
  @ParameterizedTest
  @MethodSource("tables")
  public void selectFromSpark2(String table, @Spark SparkSession spark) {
    assertThat(
            spark
                .sql(format("SELECT id, val FROM %s", sparkTableIdentifier(table)))
                .collectAsList()
                .stream()
                .map(r -> asList(r.get(0), r.get(1))))
        .containsExactlyInAnyOrderElementsOf(tableRows.get(table));
  }

  @Order(141)
  @ParameterizedTest
  @MethodSource("tables")
  public void selectFromFlink2(String table, @Flink FlinkHelper flink) {
    assertThat(flink.sql("SELECT id, val FROM %s", flink.qualifiedTableName(table)))
        .map(r -> Arrays.asList(r.getField(0), r.getField(1)))
        .containsExactlyInAnyOrderElementsOf(tableRows.get(table));
  }
}
