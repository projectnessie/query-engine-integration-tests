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
package org.projectnessie.integtests.presto;

import static java.util.Arrays.asList;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.extension.ExtendWith;
import org.projectnessie.integtests.nessie.NessieTestsExtension;

@ExtendWith({PrestoJdbcExtension.class, NessieTestsExtension.class})
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class ITPresto {

  @Order(102)
  @Test
  public void createTables(@Presto PrestoHelper presto) throws Exception {
    presto.execute(
        "CREATE TABLE %s (id INT, val VARCHAR)", presto.qualifiedTableName("from_presto"));
  }

  private static final List<List<Object>> tableRows = new ArrayList<>();

  @Order(112)
  @Test
  public void insertIntoPresto(@Presto PrestoHelper presto) throws Exception {
    presto.executeUpdate(
        "INSERT INTO %s (id, val) VALUES (300, 'from-presto')",
        presto.qualifiedTableName("from_presto"));
    tableRows.add(asList(300, "from-presto"));
  }

  @Order(122)
  @Test
  public void selectFromPresto(@Presto PrestoHelper presto) throws Exception {
    try (Stream<List<Object>> rows =
        presto.query("SELECT id, val FROM %s", presto.qualifiedTableName("from_presto"))) {
      assertThat(rows).containsExactlyInAnyOrderElementsOf(tableRows);
    }
  }

  @Order(132)
  @Test
  public void insertIntoPresto2(@Presto PrestoHelper presto) throws Exception {
    presto.executeUpdate(
        "INSERT INTO %s (id, val) VALUES (301, 'from-presto')",
        presto.qualifiedTableName("from_presto"));
    tableRows.add(asList(301, "from-presto"));
  }

  @Order(142)
  @Test
  public void selectFromPresto2(@Presto PrestoHelper presto) throws Exception {
    try (Stream<List<Object>> rows =
        presto.query("SELECT id, val FROM %s", presto.qualifiedTableName("from_presto"))) {
      assertThat(rows).containsExactlyInAnyOrderElementsOf(tableRows);
    }
  }
}
