/*
 * Copyright (C) 2023 Dremio
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
import static org.projectnessie.integtests.crossengine.TestUtils.rowToSqlDeletePredicate;
import static org.projectnessie.integtests.crossengine.TestUtils.rowToSqlInsertValue;

import com.google.errorprone.annotations.FormatMethod;
import java.util.List;
import java.util.stream.Collectors;
import org.apache.spark.sql.Dataset;
import org.apache.spark.sql.Row;
import org.apache.spark.sql.SparkSession;
import org.intellij.lang.annotations.Language;

final class SparkTestEngine implements TestEngine {

  private final String engineName;
  private final SparkSession sparkSession;
  private final String namespace;

  SparkTestEngine(String name, SparkSession sparkSession, String namespace) {
    this.engineName = name;
    this.sparkSession = sparkSession;
    this.namespace = namespace;
  }

  @Override
  public String getName() {
    return engineName;
  }

  @Override
  public String toTableIdentifier(String tableName) {
    String sparkCatalogName = "nessie"; // see IcebergSparkExtension
    return String.join(".", sparkCatalogName, namespace, tableName);
  }

  @FormatMethod
  Dataset<Row> sparkSql(@Language("SQL") String query, Object... args) {
    String fullQuery = format(query, args);
    try {
      return sparkSession.sql(fullQuery);
    } catch (Exception e) {
      throw new RuntimeException("Spark failed to run SQL: " + fullQuery, e);
    }
  }

  @Override
  public void insertRow(String tableName, List<Object> row) {
    sparkSql("INSERT INTO %s VALUES %s", toTableIdentifier(tableName), rowToSqlInsertValue(row));
  }

  @Override
  public void deleteRow(String tableName, List<Object> row) {
    sparkSql("DELETE FROM %s WHERE %s", toTableIdentifier(tableName), rowToSqlDeletePredicate(row));
  }

  @Override
  public List<List<Object>> selectRowsOrderedById(String tableName) {
    return sparkSql("SELECT id, val FROM %s ORDER BY id", toTableIdentifier(tableName))
        .collectAsList()
        .stream()
        .map(r -> asList(r.get(0), r.get(1)))
        .collect(Collectors.toList());
  }
}
