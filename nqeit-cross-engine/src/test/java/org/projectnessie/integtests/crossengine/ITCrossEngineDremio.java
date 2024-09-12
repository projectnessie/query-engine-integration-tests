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

import static java.util.Arrays.asList;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import org.apache.spark.sql.SparkSession;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.projectnessie.client.api.NessieApiV1;
import org.projectnessie.error.NessieNamespaceAlreadyExistsException;
import org.projectnessie.integtests.dremio.Dremio;
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

  private static final String NAMESPACE = System.getProperty("nqeit.namespace", "db");
  private static final String DREMIO_TABLE = "from_dremio";
  private static final String SPARK_TABLE = "from_spark";
  private static final String FLINK_TABLE = "from_flink";

  private static SparkTestEngine sparkEngine;
  private static FlinkTestEngine flinkEngine;
  private static DremioTestEngine dremioEngine;

  private static List<TestEngine> insertEngines;
  private static List<TestEngine> deleteEngines;
  private static List<TestEngine> selectEngines;

  @BeforeAll
  public static void setupEngines(
      @Spark SparkSession spark,
      @Flink FlinkHelper flink,
      @NessieAPI NessieApiV1 nessie,
      @NessieDefaultBranch String branch,
      @Dremio DremioHelper dremioHelper)
      throws Exception {
    try {
      nessie.createNamespace().namespace(NAMESPACE).refName(branch).create();
    } catch (NessieNamespaceAlreadyExistsException ignore) {
      // ignore
    }

    ITCrossEngineDremio.sparkEngine = new SparkTestEngine("SPARK", spark, NAMESPACE);
    ITCrossEngineDremio.flinkEngine = new FlinkTestEngine("FLINK", flink, NAMESPACE);
    ITCrossEngineDremio.dremioEngine = new DremioTestEngine("DREMIO", dremioHelper, NAMESPACE);

    Stream.of(SPARK_TABLE, DREMIO_TABLE, FLINK_TABLE)
        .forEach(
            x -> sparkEngine.sparkSql("DROP TABLE IF EXISTS %s", sparkEngine.toTableIdentifier(x)));

    selectEngines = asList(dremioEngine, sparkEngine, flinkEngine);
    insertEngines = asList(dremioEngine, sparkEngine, flinkEngine);
    deleteEngines = asList(dremioEngine, sparkEngine); // flink does not support deletes

    long seed = System.currentTimeMillis();
    System.out.println("testRandom seed: " + seed);
    Random testRandom = new Random(seed);

    Collections.shuffle(selectEngines, testRandom);
    Collections.shuffle(insertEngines, testRandom);
    Collections.shuffle(deleteEngines, testRandom);

    System.out.println(
        "selectEngines order: "
            + selectEngines.stream().map(TestEngine::getName).collect(Collectors.joining(", ")));
    System.out.println(
        "insertEngines order: "
            + insertEngines.stream().map(TestEngine::getName).collect(Collectors.joining(", ")));
    System.out.println(
        "deleteEngines order: "
            + deleteEngines.stream().map(TestEngine::getName).collect(Collectors.joining(", ")));
  }

  private void assertSelectFromEngines(String tableName, List<List<Object>> tableRows) {
    for (TestEngine engine : selectEngines) {
      List<List<Object>> rows = engine.selectRowsOrderedById(tableName);
      assertThat(rows).containsExactlyElementsOf(tableRows);
    }
  }

  private List<List<Object>> runInsertTests(String tableName) {
    List<List<Object>> rowsToInsert =
        IntStream.rangeClosed(1, insertEngines.size())
            .mapToObj(TestUtils::toRow)
            .collect(Collectors.toList());

    List<List<Object>> tableRows = new ArrayList<>();
    for (int i = 0; i < rowsToInsert.size(); i++) {
      List<Object> row = rowsToInsert.get(i);
      TestEngine engine = insertEngines.get(i);
      engine.insertRow(tableName, row);

      tableRows.add(row);
      assertSelectFromEngines(tableName, tableRows);
    }
    return tableRows;
  }

  private void runDeleteTests(String tableName, List<List<Object>> insertedRows) {
    int rowCount = insertedRows.size();
    assertThat(rowCount).isGreaterThan(deleteEngines.size());

    List<List<Object>> tableRows = new ArrayList<>(insertedRows);
    for (int i = 0; i < rowCount; i++) {
      List<Object> row = tableRows.remove(0);
      TestEngine engine = deleteEngines.get(i % deleteEngines.size());
      engine.deleteRow(tableName, row);

      assertSelectFromEngines(tableName, tableRows);
    }
  }

  private void runTests(String tableName) {
    List<List<Object>> insertedRows = runInsertTests(tableName);
    runDeleteTests(tableName, insertedRows);
  }

  @Test
  public void testDremioTable() {
    String testTable = DREMIO_TABLE;
    dremioEngine.runQuery(
        "CREATE TABLE %s (id INT, val VARCHAR)", dremioEngine.toTableIdentifier(testTable));
    runTests(testTable);
  }

  @Test
  public void testSparkTable() {
    String testTable = SPARK_TABLE;
    sparkEngine.sparkSql(
        "CREATE TABLE %s (id int, val string)", sparkEngine.toTableIdentifier(testTable));
    runTests(testTable);
  }

  @Test
  public void testFlinkTable() {
    String testTable = FLINK_TABLE;
    flinkEngine.runQuery(
        "CREATE TABLE %s (id INT, val STRING)", flinkEngine.toTableIdentifier(testTable));
    runTests(testTable);
  }
}
