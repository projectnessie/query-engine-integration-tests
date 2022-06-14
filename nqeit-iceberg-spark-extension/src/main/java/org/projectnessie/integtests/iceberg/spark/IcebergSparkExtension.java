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

import static org.projectnessie.integtests.nessie.internal.Util.checkSupportedParameterType;
import static org.projectnessie.integtests.nessie.internal.Util.nessieClientParams;

import org.apache.spark.SparkConf;
import org.apache.spark.sql.SparkSession;
import org.apache.spark.sql.internal.SQLConf;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.ExtensionContext.Store.CloseableResource;
import org.junit.jupiter.api.extension.ParameterContext;
import org.junit.jupiter.api.extension.ParameterResolutionException;
import org.junit.jupiter.api.extension.ParameterResolver;
import org.projectnessie.integtests.nessie.internal.IcebergWarehouse;

public class IcebergSparkExtension implements ParameterResolver {

  private static final String SPARK_MASTER_URL = System.getProperty("spark.master.url", "local[2]");
  private static final String NON_NESSIE_CATALOG = "invalid_hive";

  private static final ExtensionContext.Namespace NAMESPACE =
      ExtensionContext.Namespace.create(IcebergSparkExtension.class);

  @Override
  public boolean supportsParameter(
      ParameterContext parameterContext, ExtensionContext extensionContext)
      throws ParameterResolutionException {
    if (parameterContext.isAnnotated(Spark.class)) {
      checkSupportedParameterType(Spark.class, parameterContext, SparkSession.class);
      return true;
    }
    return false;
  }

  @Override
  public Object resolveParameter(
      ParameterContext parameterContext, ExtensionContext extensionContext)
      throws ParameterResolutionException {
    if (parameterContext.isAnnotated(Spark.class)) {
      return SparkSessionHolder.get(extensionContext).getConfiguredSparkSession(extensionContext);
    }
    return null;
  }

  private static class SparkSessionHolder implements CloseableResource {

    private final SparkConf conf;
    private SparkSession sparkSession;

    static SparkSessionHolder get(ExtensionContext extensionContext) {
      return extensionContext
          .getRoot()
          .getStore(NAMESPACE)
          .getOrComputeIfAbsent(
              SparkSessionHolder.class,
              c -> new SparkSessionHolder(extensionContext),
              SparkSessionHolder.class);
    }

    private SparkSessionHolder(ExtensionContext extensionContext) {
      this.conf = new SparkConf();

      nessieClientParams(extensionContext).forEach(this::applyConf);
      applyConf("warehouse", IcebergWarehouse.get(extensionContext).getUri().toString());

      conf.set(SQLConf.PARTITION_OVERWRITE_MODE().key(), "dynamic")
          .set("spark.testing", "true")
          .set(SQLConf.SHUFFLE_PARTITIONS().key(), "4")
          .set("spark.sql.catalog.nessie.catalog-impl", "org.apache.iceberg.nessie.NessieCatalog")
          .set("spark.sql.catalog.nessie.cache.expiration-interval-ms", "0")
          .set("spark.sql.catalog.nessie", "org.apache.iceberg.spark.SparkCatalog")
          .set("spark.sql.defaultUrlStreamHandlerFactory.enabled", "false");

      conf.set(
          "spark.sql.extensions",
          "3.2".equals(System.getProperty("sparkScala.sparkMajorVersion"))
              ? "org.projectnessie.spark.extensions.NessieSpark32SessionExtensions"
              : "org.projectnessie.spark.extensions.NessieSparkSessionExtensions");

      // the following catalog is only added to test a check in the nessie spark extensions
      conf.set(
              String.format("spark.sql.catalog.%s", NON_NESSIE_CATALOG),
              "org.apache.iceberg.spark.SparkCatalog")
          .set(
              String.format("spark.sql.catalog.%s.catalog-impl", NON_NESSIE_CATALOG),
              "org.apache.iceberg.hive.HiveCatalog")
          .set(
              String.format("spark.sql.catalog.%s.expiration-interval-ms", NON_NESSIE_CATALOG),
              "0");
    }

    private void applyConf(String k, String v) {
      conf.set(String.format("spark.sql.catalog.nessie.%s", k), v);
      conf.set(String.format("spark.sql.catalog.spark_catalog.%s", k), v);
    }

    public SparkSession getConfiguredSparkSession(ExtensionContext extensionContext) {
      nessieClientParams(extensionContext).forEach(this::applyConf);
      SparkSession spark =
          SparkSession.builder().master(SPARK_MASTER_URL).config(conf).getOrCreate();
      spark.sparkContext().setLogLevel(System.getProperty("spark.log.level", "WARN"));
      sparkSession = spark;
      return spark;
    }

    @Override
    public void close() {
      SparkSession spark = sparkSession;
      sparkSession = null;
      if (spark != null) {
        spark.stop();
      }
    }
  }
}
