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
package org.projectnessie.integtests.iceberg.spark;

import static java.lang.String.format;
import static java.util.Arrays.asList;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import org.apache.spark.sql.SparkSession;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.extension.ExtendWith;
import org.projectnessie.integtests.nessie.NessieRefName;
import org.projectnessie.integtests.nessie.NessieTestsExtension;

@ExtendWith({NessieTestsExtension.class, IcebergSparkExtension.class})
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class ITIcebergSpark {

  @Test
  @Order(200)
  public void createBranch(@Spark SparkSession spark, @NessieRefName String dev) {
    System.err.println(
        spark.sql(format("CREATE BRANCH %s IN nessie FROM main", dev)).collectAsList());
    System.err.println(spark.sql("LIST REFERENCES IN nessie").collectAsList());
  }

  @Test
  @Order(210)
  public void listReferences(@Spark SparkSession spark) {
    System.err.println(spark.sql("LIST REFERENCES IN nessie").collectAsList());
  }

  @Order(100)
  @Test
  public void createTables(@Spark SparkSession spark) {
    spark.sql("CREATE TABLE nessie.db.from_spark (id int, val string)");
  }

  private static final List<List<Object>> tableRows = new ArrayList<>();

  @Order(110)
  @Test
  public void insertIntoSpark(@Spark SparkSession spark) {
    spark.sql(format("INSERT INTO nessie.db.%s select 100, \"from-spark\"", "from_spark"));
    tableRows.add(asList(100, "from-spark"));
  }

  @Order(120)
  @Test
  public void selectFromSpark(@Spark SparkSession spark) {
    assertThat(
            spark
                .sql(format("SELECT id, val FROM nessie.db.%s", "from_spark"))
                .collectAsList()
                .stream()
                .map(r -> asList(r.get(0), r.get(1))))
        .containsExactlyInAnyOrderElementsOf(tableRows);
  }

  @Order(130)
  @Test
  public void insertIntoSpark2(@Spark SparkSession spark) {
    spark.sql(format("INSERT INTO nessie.db.%s select 101, \"from-spark\"", "from_spark"));
    tableRows.add(asList(101, "from-spark"));
  }

  @Order(140)
  @Test
  public void selectFromSpark2(@Spark SparkSession spark) {
    assertThat(
            spark
                .sql(format("SELECT id, val FROM nessie.db.%s", "from_spark"))
                .collectAsList()
                .stream()
                .map(r -> asList(r.get(0), r.get(1))))
        .containsExactlyInAnyOrderElementsOf(tableRows);
  }
}
