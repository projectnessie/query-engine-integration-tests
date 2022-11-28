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

import java.util.ArrayList;
import java.util.List;
import org.apache.spark.sql.SparkSession;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.projectnessie.integtests.dremio.DremioHelper;
import org.projectnessie.integtests.dremio.IcebergDremioExtension;
import org.projectnessie.integtests.flink.Flink;
import org.projectnessie.integtests.flink.FlinkHelper;
import org.projectnessie.integtests.flink.IcebergFlinkExtension;
import org.projectnessie.integtests.iceberg.spark.IcebergSparkExtension;
import org.projectnessie.integtests.iceberg.spark.Spark;
import org.projectnessie.integtests.nessie.NessieTestsExtension;

@ExtendWith({
  IcebergSparkExtension.class,
  IcebergFlinkExtension.class,
  NessieTestsExtension.class,
  IcebergDremioExtension.class
})
public class ITCrossEngineDremio {

  private static SparkSession spark;
  private static FlinkHelper flink;
  private static DremioHelper dremioHelper;

  @BeforeAll
  public static void setupEngines(
      @Spark SparkSession spark, @Flink FlinkHelper flink, DremioHelper dremioHelper) {
    ITCrossEngineDremio.spark = spark;
    ITCrossEngineDremio.flink = flink;
    ITCrossEngineDremio.dremioHelper = dremioHelper;

    spark.sql("DROP TABLE IF EXISTS nessie.db.from_spark");
    spark.sql("DROP TABLE IF EXISTS nessie.db.from_dremio");
    spark.sql("DROP TABLE IF EXISTS nessie.db.from_flink");
  }

  private void runInsertTests(String testTable) {
    List<List<Object>> tableRows = new ArrayList<>();

    spark.sql(format("INSERT INTO nessie.db.%s VALUES (123, 'foo')", testTable));
    tableRows.add(asList(123, "foo"));
    assertSelectFromEngines(tableRows, testTable);

    dremioHelper.runInsertQuery(format("INSERT INTO %s VALUES (456,'bar')", testTable));
    tableRows.add(asList(456, "bar"));
    assertSelectFromEngines(tableRows, testTable);

    flink.sql("INSERT INTO %s (id, val) VALUES (789, 'cool')", flink.qualifiedTableName(testTable));
    tableRows.add(asList(789, "cool"));
    assertSelectFromEngines(tableRows, testTable);
  }

  private void runDeleteTests(String testTable) {
    List<List<Object>> tableRows = new ArrayList<>();
    tableRows.add(asList(123, "foo"));
    tableRows.add(asList(456, "bar"));
    tableRows.add(asList(789, "cool"));

    spark.sql(format("DELETE FROM nessie.db.%s WHERE id=123 AND val='foo'", testTable));
    tableRows.remove(asList(123, "foo"));
    assertSelectFromEngines(tableRows, testTable);

    dremioHelper.executeDmlStatement(
        format("DELETE FROM %s WHERE id=456 and val='bar'", testTable));
    tableRows.remove(asList(456, "bar"));
    assertSelectFromEngines(tableRows, testTable);

    // Flink does not support delete statement, so the test for flink is skipped
  }

  private static void selectFromSpark(List<List<Object>> tableRows, String testTable) {
    assertThat(
            spark
                .sql(format("SELECT id, val FROM nessie.db.%s", testTable))
                .collectAsList()
                .stream()
                .map(r -> asList(r.get(0), r.get(1))))
        .containsExactlyInAnyOrderElementsOf(tableRows);
  }

  private static void selectFromFlink(List<List<Object>> tableRows, String testTable) {
    assertThat(flink.sql("SELECT * FROM %s", flink.qualifiedTableName(testTable)))
        .hasSize(tableRows.size())
        .map(r -> asList(r.getField(0), r.getField(1)))
        .containsExactlyInAnyOrderElementsOf(tableRows);
  }

  private static void selectFromDremio(List<List<Object>> tableRows, String testTable) {
    List<List<Object>> rows = dremioHelper.runSelectQuery(format("SELECT * FROM %s", testTable));
    assertThat(rows).containsExactlyInAnyOrderElementsOf(tableRows);
  }

  private void assertSelectFromEngines(List<List<Object>> tableRows, String testTable) {
    selectFromSpark(tableRows, testTable);
    selectFromDremio(tableRows, testTable);
    selectFromFlink(tableRows, testTable);
  }

  @Test
  public void testDremioTable() {
    String testTable = "from_dremio";
    dremioHelper.executeDmlStatement(format("CREATE TABLE %s (id INT, val VARCHAR)", testTable));
    runInsertTests(testTable);
    runDeleteTests(testTable);
  }

  @Test
  public void testSparkTable() {
    String testTable = "from_spark";
    spark.sql(format("CREATE TABLE nessie.db.%s (id int, val string)", testTable));
    runInsertTests(testTable);
    runDeleteTests(testTable);
  }

  @Test
  public void testFlinkTable() {
    String testTable = "from_flink";
    flink.sql("CREATE TABLE %s (id INT, val STRING)", flink.qualifiedTableName(testTable));
    runInsertTests(testTable);
    runDeleteTests(testTable);
  }
}
