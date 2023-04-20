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
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
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

  private static List<TestEngine> insertEngines;
  private static List<TestEngine> deleteEngines;
  private static List<TestEngine> selectEngines;

  private interface TestEngine {

    String getName();

    void insertRow(String tableName, List<Object> row);

    void deleteRow(String tableName, List<Object> row);

    List<List<Object>> selectRowsOrderedById(String tableName);
  }

  private static final TestEngine DREMIO_ENGINE =
      new TestEngine() {

        @Override
        public String getName() {
          return "DREMIO";
        }

        @Override
        public void insertRow(String tableName, List<Object> row) {
          dremioHelper.runQuery(
              "INSERT INTO %s VALUES %s", dremioTable(tableName), rowToSqlInsertValue(row));
        }

        @Override
        public void deleteRow(String tableName, List<Object> row) {
          dremioHelper.runQuery(
              "DELETE FROM %s WHERE %s", dremioTable(tableName), rowToSqlDeletePredicate(row));
        }

        @Override
        public List<List<Object>> selectRowsOrderedById(String tableName) {
          return dremioHelper.runSelectQuery(
              "SELECT * FROM %s ORDER BY id", dremioTable(tableName));
        }
      };

  private static final TestEngine SPARK_ENGINE =
      new TestEngine() {
        @Override
        public String getName() {
          return "SPARK";
        }

        @Override
        public void insertRow(String tableName, List<Object> row) {
          sparkSql("INSERT INTO %s VALUES %s", sparkTable(tableName), rowToSqlInsertValue(row));
        }

        @Override
        public void deleteRow(String tableName, List<Object> row) {
          sparkSql("DELETE FROM %s WHERE %s", sparkTable(tableName), rowToSqlDeletePredicate(row));
        }

        @Override
        public List<List<Object>> selectRowsOrderedById(String tableName) {
          return sparkSql("SELECT id, val FROM %s ORDER BY id", sparkTable(tableName))
              .collectAsList()
              .stream()
              .map(r -> asList(r.get(0), r.get(1)))
              .collect(Collectors.toList());
        }
      };

  private static final TestEngine FLINK_ENGINE =
      new TestEngine() {

        @Override
        public String getName() {
          return "FLINK";
        }

        @Override
        public void insertRow(String tableName, List<Object> row) {
          flink.sql(
              "INSERT INTO %s (id, val) VALUES %s",
              flinkTable(tableName), rowToSqlInsertValue(row));
        }

        @Override
        public void deleteRow(String tableName, List<Object> row) {
          throw new UnsupportedOperationException("Flink does not support delete statements");
        }

        @Override
        public List<List<Object>> selectRowsOrderedById(String tableName) {
          return flink.sql("SELECT id, val FROM %s ORDER BY id", flinkTable(tableName)).stream()
              .map(r -> asList(r.getField(0), r.getField(1)))
              .collect(Collectors.toList());
        }
      };

  private static String rowToSqlInsertValue(List<Object> row) {
    return String.format("(%s, '%s')", row.get(0), row.get(1));
  }

  private static String rowToSqlDeletePredicate(List<Object> row) {
    return String.format("id=%s AND val='%s'", row.get(0), row.get(1));
  }

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

    selectEngines = asList(DREMIO_ENGINE, SPARK_ENGINE, FLINK_ENGINE);
    insertEngines = asList(DREMIO_ENGINE, SPARK_ENGINE, FLINK_ENGINE);
    deleteEngines = asList(DREMIO_ENGINE, SPARK_ENGINE); // flink does not support deletes

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

  private static List<Object> toRow(int x) {
    return asList(x, "row" + x);
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
            .mapToObj(ITCrossEngineDremio::toRow)
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
    dremioHelper.runQuery("CREATE TABLE %s (id INT, val VARCHAR)", dremioTable(testTable));
    runTests(testTable);
  }

  @Test
  public void testSparkTable() {
    String testTable = SPARK_TABLE;
    sparkSql("CREATE TABLE %s (id int, val string)", sparkTable(testTable));
    runTests(testTable);
  }

  @Test
  public void testFlinkTable() {
    String testTable = FLINK_TABLE;
    flink.sql("CREATE TABLE %s (id INT, val STRING)", flinkTable(testTable));
    runTests(testTable);
  }
}
