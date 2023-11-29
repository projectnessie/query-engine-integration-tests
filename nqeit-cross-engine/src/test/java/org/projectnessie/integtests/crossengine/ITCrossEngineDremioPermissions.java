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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.projectnessie.integtests.crossengine.TestUtils.toRow;

import java.util.List;
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
public class ITCrossEngineDremioPermissions {

  private static final String NAMESPACE = "db";
  private static final String READ_ONLY_TABLE = "dremio_readonly"; // pre-created on the outside
  private static final String EXPECTED_PERMISSION_ERROR =
      String.format(
          "Update entity is not allowed on reference 'main' on content '%s.%s'",
          NAMESPACE, READ_ONLY_TABLE);

  @BeforeAll
  public static void setupNamepspace(
      @NessieAPI NessieApiV1 nessie, @NessieDefaultBranch String branch) throws Exception {
    try {
      nessie.createNamespace().namespace(NAMESPACE).refName(branch).create();
    } catch (NessieNamespaceAlreadyExistsException ignore) {
      // ignore
    }
  }

  @Test
  public void testReadOnlyTableSpark(
      @Spark(nessieClientOverrideSystemPropertyPrefix = "nessie.readonlyclient.")
          SparkSession readOnlySpark,
      @Dremio DremioHelper dremio) {

    SparkTestEngine readOnlySparkEngine =
        new SparkTestEngine("SPARK-READONLY", readOnlySpark, NAMESPACE);
    List<Object> newRow = toRow(777);

    assertThatThrownBy(() -> readOnlySparkEngine.insertRow(READ_ONLY_TABLE, newRow))
        .hasStackTraceContaining(EXPECTED_PERMISSION_ERROR);
    assertThat(readOnlySparkEngine.selectRowsOrderedById(READ_ONLY_TABLE)).doesNotContain(newRow);

    DremioTestEngine dremioEngine = new DremioTestEngine("DREMIO", dremio, NAMESPACE);
    dremioEngine.insertRow(READ_ONLY_TABLE, newRow);
    assertThat(readOnlySparkEngine.selectRowsOrderedById(READ_ONLY_TABLE)).contains(newRow);
  }

  @Test
  public void testReadOnlyTableFlink(
      @Flink(nessieClientOverrideSystemPropertyPrefix = "nessie.readonlyclient.")
          FlinkHelper readOnlyFlink,
      @Dremio DremioHelper dremio) {

    FlinkTestEngine readOnlyFlinkEngine =
        new FlinkTestEngine("FLINK-READONLY", readOnlyFlink, NAMESPACE);
    List<Object> newRow = toRow(888);

    assertThatThrownBy(() -> readOnlyFlinkEngine.insertRow(READ_ONLY_TABLE, newRow))
        .hasStackTraceContaining(EXPECTED_PERMISSION_ERROR);
    assertThat(readOnlyFlinkEngine.selectRowsOrderedById(READ_ONLY_TABLE)).doesNotContain(newRow);

    DremioTestEngine dremioEngine = new DremioTestEngine("DREMIO", dremio, NAMESPACE);
    dremioEngine.insertRow(READ_ONLY_TABLE, newRow);
    assertThat(readOnlyFlinkEngine.selectRowsOrderedById(READ_ONLY_TABLE)).contains(newRow);
  }

  @Test
  public void testReadOnlyTableDremio(
      @Dremio(systemPropertyPrefix = "dremio.readonly.") DremioHelper readOnlyDremio,
      @Dremio DremioHelper dremio) {

    DremioTestEngine readOnlyDremioEngine =
        new DremioTestEngine("DREMIO-READONLY", readOnlyDremio, NAMESPACE);
    List<Object> newRow = toRow(999);

    assertThatThrownBy(() -> readOnlyDremioEngine.insertRow(READ_ONLY_TABLE, newRow))
        .hasStackTraceContaining(EXPECTED_PERMISSION_ERROR);
    assertThat(readOnlyDremioEngine.selectRowsOrderedById(READ_ONLY_TABLE)).doesNotContain(newRow);

    DremioTestEngine dremioEngine = new DremioTestEngine("DREMIO", dremio, NAMESPACE);
    dremioEngine.insertRow(READ_ONLY_TABLE, newRow);
    assertThat(readOnlyDremioEngine.selectRowsOrderedById(READ_ONLY_TABLE)).contains(newRow);
  }
}
