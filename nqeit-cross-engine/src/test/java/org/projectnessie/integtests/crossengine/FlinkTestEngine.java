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

import static java.util.Arrays.asList;
import static org.projectnessie.integtests.crossengine.TestUtils.rowToSqlInsertValue;

import com.google.errorprone.annotations.FormatMethod;
import java.util.List;
import java.util.stream.Collectors;
import org.apache.flink.types.Row;
import org.assertj.core.api.Assertions;
import org.intellij.lang.annotations.Language;
import org.projectnessie.integtests.flink.FlinkHelper;

final class FlinkTestEngine implements TestEngine {

  private final String name;
  private final FlinkHelper flink;
  private final String database;

  FlinkTestEngine(String name, FlinkHelper flink, String database) {
    this.name = name;
    this.flink = flink;
    this.database = database;
  }

  @Override
  public String getName() {
    return name;
  }

  @Override
  public String toTableIdentifier(String tableName) {
    Assertions.assertThat(database).isEqualTo(flink.databaseName());
    return flink.qualifiedTableName(tableName);
  }

  @FormatMethod
  List<Row> runQuery(@Language("SQL") String query, Object... args) {
    return flink.sql(query, args);
  }

  @Override
  public void insertRow(String tableName, List<Object> row) {
    runQuery(
        "INSERT INTO %s (id, val) VALUES %s",
        toTableIdentifier(tableName), rowToSqlInsertValue(row));
  }

  @Override
  public void deleteRow(String tableName, List<Object> row) {
    throw new UnsupportedOperationException("Flink does not support delete statements");
  }

  @Override
  public List<List<Object>> selectRowsOrderedById(String tableName) {
    return runQuery("SELECT id, val FROM %s ORDER BY id", toTableIdentifier(tableName)).stream()
        .map(r -> asList(r.getField(0), r.getField(1)))
        .collect(Collectors.toList());
  }
}
