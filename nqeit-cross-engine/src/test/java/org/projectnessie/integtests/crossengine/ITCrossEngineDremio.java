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

import com.google.errorprone.annotations.FormatMethod;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.apache.spark.sql.Dataset;
import org.apache.spark.sql.Row;
import org.apache.spark.sql.SparkSession;
import org.intellij.lang.annotations.Language;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.projectnessie.client.api.NessieApiV1;
import org.projectnessie.error.NessieNamespaceAlreadyExistsException;
import org.projectnessie.integtests.dremio.DremioHelper;
import org.projectnessie.integtests.dremio.IcebergDremioExtension;
import org.projectnessie.integtests.flink.Flink;
import org.projectnessie.integtests.flink.FlinkHelper;
import org.projectnessie.integtests.flink.IcebergFlinkExtension;
import org.projectnessie.integtests.iceberg.spark.IcebergSparkExtension;
import org.projectnessie.integtests.iceberg.spark.Spark;
import org.projectnessie.integtests.nessie.NessieAPI;
import org.projectnessie.integtests.nessie.NessieDefaultBranch;
import org.projectnessie.integtests.nessie.NessieTestsExtension;

@ExtendWith({
  IcebergSparkExtension.class,
  IcebergFlinkExtension.class,
  NessieTestsExtension.class,
  IcebergDremioExtension.class
})
public class ITCrossEngineDremio {

  private static final String NAMESPACE = "db";
  private static final String DREMIO_TABLE = "from_dremio";
  private static final String SPARK_TABLE = "from_spark";
  private static final String FLINK_TABLE = "from_flink";

  private static SparkSession spark;
  private static FlinkHelper flink;
  private static DremioHelper dremioHelper;

  @FormatMethod
  private static Dataset<Row> sparkSql(@Language("SQL") String query, Object... args) {
    String fullQuery = format(query, args);
    try {
      return spark.sql(fullQuery);
    } catch (Exception e) {
      throw new RuntimeException("Spark failed to run SQL: " + fullQuery, e);
    }
  }

  @BeforeAll
  public static void setupEngines(
      @Spark SparkSession spark,
      @Flink FlinkHelper flink,
      @NessieAPI NessieApiV1 nessie,
      @NessieDefaultBranch String branch,
      DremioHelper dremioHelper)
      throws Exception {
    try {
      nessie.createNamespace().namespace(NAMESPACE).refName(branch).create();
    } catch (NessieNamespaceAlreadyExistsException ignore) {
      // ignore
    }

    ITCrossEngineDremio.spark = spark;
    ITCrossEngineDremio.flink = flink;
    ITCrossEngineDremio.dremioHelper = dremioHelper;

    Stream.of(SPARK_TABLE, DREMIO_TABLE, FLINK_TABLE)
        .forEach(x -> sparkSql("DROP TABLE IF EXISTS %s", sparkTable(x)));
  }

  private static String sparkTable(String tableName) {
    String sparkCatalogName = "nessie"; // see IcebergSparkExtension
    return String.join(".", sparkCatalogName, NAMESPACE, tableName);
  }

  private static String dremioTable(String tableName) {
    return format("\"%s\".\"%s\".\"%s\"", dremioHelper.getCatalogName(), NAMESPACE, tableName);
  }

  private static String flinkTable(String tableName) {
    assertThat(NAMESPACE).isEqualTo(flink.databaseName());
    return flink.qualifiedTableName(tableName);
  }

  private void runInsertTests(String testTable) {
    List<List<Object>> tableRows = new ArrayList<>();

    sparkSql("INSERT INTO %s VALUES (123, 'foo')", sparkTable(testTable));
    tableRows.add(asList(123, "foo"));
    assertSelectFromEngines(tableRows, testTable);

    dremioHelper.runQuery("INSERT INTO %s VALUES (456,'bar')", dremioTable(testTable));
    tableRows.add(asList(456, "bar"));
    assertSelectFromEngines(tableRows, testTable);

    flink.sql("INSERT INTO %s (id, val) VALUES (789, 'cool')", flinkTable(testTable));
    tableRows.add(asList(789, "cool"));
    assertSelectFromEngines(tableRows, testTable);
  }

  private void runDeleteTests(String testTable) {
    List<List<Object>> tableRows = new ArrayList<>();
    tableRows.add(asList(123, "foo"));
    tableRows.add(asList(456, "bar"));
    tableRows.add(asList(789, "cool"));

    sparkSql("DELETE FROM %s WHERE id=123 AND val='foo'", sparkTable(testTable));
    tableRows.remove(asList(123, "foo"));
    assertSelectFromEngines(tableRows, testTable);

    dremioHelper.runQuery("DELETE FROM %s WHERE id=456 and val='bar'", dremioTable(testTable));
    tableRows.remove(asList(456, "bar"));
    assertSelectFromEngines(tableRows, testTable);

    // Flink does not support delete statement, so the test for flink is skipped
  }

  private static void selectFromSpark(List<List<Object>> tableRows, String testTable) {
    assertThat(
            sparkSql("SELECT id, val FROM %s", sparkTable(testTable)).collectAsList().stream()
                .map(r -> asList(r.get(0), r.get(1))))
        .containsExactlyInAnyOrderElementsOf(tableRows);
  }

  private static void selectFromFlink(List<List<Object>> tableRows, String testTable) {
    assertThat(flink.sql("SELECT * FROM %s", flinkTable(testTable)))
        .hasSize(tableRows.size())
        .map(r -> asList(r.getField(0), r.getField(1)))
        .containsExactlyInAnyOrderElementsOf(tableRows);
  }

  private static void selectFromDremio(List<List<Object>> tableRows, String testTable) {
    List<List<Object>> rows =
        dremioHelper.runSelectQuery("SELECT * FROM %s", dremioTable(testTable));
    assertThat(rows).containsExactlyInAnyOrderElementsOf(tableRows);
  }

  private void assertSelectFromEngines(List<List<Object>> tableRows, String testTable) {
    selectFromSpark(tableRows, testTable);
    selectFromDremio(tableRows, testTable);
    selectFromFlink(tableRows, testTable);
  }

  @Test
  public void testDremioTable() {
    String testTable = DREMIO_TABLE;
    dremioHelper.runQuery("CREATE TABLE %s (id INT, val VARCHAR)", dremioTable(testTable));
    runInsertTests(testTable);
    runDeleteTests(testTable);
  }

  @Test
  public void testSparkTable() {
    String testTable = SPARK_TABLE;
    sparkSql("CREATE TABLE %s (id int, val string)", sparkTable(testTable));
    runInsertTests(testTable);
    runDeleteTests(testTable);
  }

  @Test
  public void testFlinkTable() {
    String testTable = FLINK_TABLE;
    flink.sql("CREATE TABLE %s (id INT, val STRING)", flinkTable(testTable));
    runInsertTests(testTable);
    runDeleteTests(testTable);
  }
}
