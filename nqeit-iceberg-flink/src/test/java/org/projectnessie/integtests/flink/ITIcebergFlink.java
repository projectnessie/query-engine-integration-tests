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
package org.projectnessie.integtests.flink;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.extension.ExtendWith;
import org.projectnessie.client.api.NessieApiV1;
import org.projectnessie.error.NessieNamespaceAlreadyExistsException;
import org.projectnessie.integtests.nessie.NessieAPI;
import org.projectnessie.integtests.nessie.NessieDefaultBranch;
import org.projectnessie.integtests.nessie.NessieTestsExtension;

@ExtendWith({NessieTestsExtension.class, IcebergFlinkExtension.class})
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class ITIcebergFlink {

  @Order(20)
  @Test
  public void createNamespace(
      @NessieAPI NessieApiV1 nessie, @NessieDefaultBranch String branch, @Flink FlinkHelper flink)
      throws Exception {
    try {
      nessie.createNamespace().namespace(flink.databaseName()).refName(branch).create();
    } catch (NessieNamespaceAlreadyExistsException ignore) {
      // ignore
    }
  }

  @Order(100)
  @Test
  public void createTable(@Flink FlinkHelper flink) {
    flink.sql("CREATE TABLE %s (id BIGINT, data STRING)", flink.qualifiedTableName("foo_bar"));
  }

  @Order(101)
  @Test
  public void insertInto(@Flink FlinkHelper flink) {
    flink.sql("INSERT INTO %s (id, data) VALUES (123, 'foo')", flink.qualifiedTableName("foo_bar"));
  }

  @Order(102)
  @Test
  public void selectFrom(@Flink FlinkHelper flink) {
    assertThat(flink.sql("SELECT * FROM %s", flink.qualifiedTableName("foo_bar")))
        .hasSize(1)
        .map(r -> Arrays.asList(r.getField(0), r.getField(1)))
        .containsExactlyInAnyOrder(Arrays.asList(123L, "foo"));
  }

  @Order(103)
  @Test
  public void insertInto2(@Flink FlinkHelper flink) {
    flink.sql("INSERT INTO %s (id, data) VALUES (456, 'bar')", flink.qualifiedTableName("foo_bar"));
  }

  @Order(104)
  @Test
  public void selectFrom2(@Flink FlinkHelper flink) {
    assertThat(flink.sql("SELECT * FROM %s", flink.qualifiedTableName("foo_bar")))
        .hasSize(2)
        .map(r -> Arrays.asList(r.getField(0), r.getField(1)))
        .containsExactlyInAnyOrder(Arrays.asList(123L, "foo"), Arrays.asList(456L, "bar"));
  }
}
