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
package org.projectnessie.integtests.dremio;

import static java.util.Arrays.asList;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;

@ExtendWith({IcebergDremioExtension.class})
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class ITIcebergDremio {

  private static final List<List<Object>> tableRows = new ArrayList<>();

  @BeforeAll
  public static void maybeSkipTests() {
    Assumptions.assumeTrue(System.getProperty("dremio.url") != null);
  }

  @BeforeAll
  public static void setUp(DremioHelper dremioHelper) {
    dropTableIfExists(dremioHelper);
  }

  private static void dropTableIfExists(DremioHelper dremioHelper) {
    dremioHelper.executeDmlStatement("DROP TABLE IF EXISTS foo_bar");
  }

  @Order(100)
  @Test
  public void createTable(DremioHelper dremioHelper) {
    dremioHelper.executeDmlStatement("CREATE TABLE foo_bar (id INT, val VARCHAR)");
  }

  @Order(110)
  @Test
  public void insertInto(DremioHelper dremioHelper) {
    dremioHelper.runInsertQuery("INSERT INTO foo_bar VALUES (456,'bar')");
    tableRows.add(asList(456, "bar"));
  }

  @Order(120)
  @Test
  public void selectFrom(DremioHelper dremioHelper) {
    List<List<Object>> rows = dremioHelper.runSelectQuery("SELECT * FROM foo_bar");
    assertThat(rows).containsExactlyInAnyOrderElementsOf(tableRows);
  }

  @Order(130)
  @Test
  public void insertInto2(DremioHelper dremioHelper) {
    dremioHelper.runInsertQuery("INSERT INTO foo_bar VALUES (123,'foo')");
    tableRows.add(asList(123, "foo"));
  }

  @Order(140)
  @Test
  public void selectFrom2(DremioHelper dremioHelper) {
    List<List<Object>> rows = dremioHelper.runSelectQuery("SELECT * FROM foo_bar");
    assertThat(rows).containsExactlyInAnyOrderElementsOf(tableRows);
  }

  @Order(150)
  @Test
  public void dropTable(DremioHelper dremioHelper) {
    dremioHelper.executeDmlStatement("DROP TABLE foo_bar");
  }
}
