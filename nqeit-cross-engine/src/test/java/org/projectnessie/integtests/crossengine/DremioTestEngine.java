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

import static org.projectnessie.integtests.crossengine.TestUtils.rowToSqlDeletePredicate;
import static org.projectnessie.integtests.crossengine.TestUtils.rowToSqlInsertValue;

import com.google.errorprone.annotations.FormatMethod;
import java.util.List;
import org.intellij.lang.annotations.Language;
import org.projectnessie.integtests.dremio.DremioHelper;

final class DremioTestEngine implements TestEngine {

  private final String name;
  private final DremioHelper dremioHelper;
  private final String namespace;

  DremioTestEngine(String name, DremioHelper dremioHelper, String namespace) {
    this.name = name;
    this.dremioHelper = dremioHelper;
    this.namespace = namespace;
  }

  @Override
  public String getName() {
    return name;
  }

  @Override
  public String toTableIdentifier(String tableName) {
    return String.format(
        "\"%s\".\"%s\".\"%s\"", dremioHelper.getCatalogName(), namespace, tableName);
  }

  @FormatMethod
  public String runQuery(@Language("SQL") String query, Object... args) {
    return dremioHelper.runQuery(query, args);
  }

  @Override
  public void insertRow(String tableName, List<Object> row) {
    runQuery("INSERT INTO %s VALUES %s", toTableIdentifier(tableName), rowToSqlInsertValue(row));
  }

  @Override
  public void deleteRow(String tableName, List<Object> row) {
    runQuery("DELETE FROM %s WHERE %s", toTableIdentifier(tableName), rowToSqlDeletePredicate(row));
  }

  @Override
  public List<List<Object>> selectRowsOrderedById(String tableName) {
    return dremioHelper.runSelectQuery(
        "SELECT * FROM %s ORDER BY id", toTableIdentifier(tableName));
  }
}
