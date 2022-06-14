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

import static org.projectnessie.integtests.nessie.internal.Util.checkSupportedParameterType;
import static org.projectnessie.integtests.nessie.internal.Util.nessieClientParams;

import com.google.common.collect.Lists;
import com.google.errorprone.annotations.FormatMethod;
import java.net.URI;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.apache.flink.api.java.ExecutionEnvironment;
import org.apache.flink.client.deployment.executors.RemoteExecutor;
import org.apache.flink.client.program.ContextEnvironment;
import org.apache.flink.client.program.StreamContextEnvironment;
import org.apache.flink.configuration.Configuration;
import org.apache.flink.configuration.CoreOptions;
import org.apache.flink.configuration.DeploymentOptions;
import org.apache.flink.configuration.RestOptions;
import org.apache.flink.core.execution.DefaultExecutorServiceLoader;
import org.apache.flink.core.execution.PipelineExecutorServiceLoader;
import org.apache.flink.runtime.minicluster.MiniCluster;
import org.apache.flink.runtime.minicluster.MiniClusterConfiguration;
import org.apache.flink.table.api.EnvironmentSettings;
import org.apache.flink.table.api.TableEnvironment;
import org.apache.flink.table.api.TableResult;
import org.apache.flink.types.Row;
import org.apache.flink.util.CloseableIterator;
import org.apache.iceberg.CatalogProperties;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.ExtensionContext.Store.CloseableResource;
import org.junit.jupiter.api.extension.ParameterContext;
import org.junit.jupiter.api.extension.ParameterResolutionException;
import org.junit.jupiter.api.extension.ParameterResolver;
import org.projectnessie.integtests.nessie.internal.IcebergWarehouse;
import org.projectnessie.integtests.nessie.internal.NessieEnv;

public class IcebergFlinkExtension implements ParameterResolver {

  private static final int DEFAULT_TM_NUM = 1;
  private static final int DEFAULT_PARALLELISM = 4;
  private static final Configuration DISABLE_CLASSLOADER_CHECK_CONFIG =
      new Configuration()
          // disable classloader check as Avro may cache class/object in the serializers.
          .set(CoreOptions.CHECK_LEAKED_CLASSLOADER, false);
  public static final String FLINK_CONFIG_PREFIX = "flink.config.";
  public static final String FLINK_REMOTE_HOST = "flink.remote.host";
  public static final String FLINK_REMOTE_PORT = "flink.remote.port";

  private static final ExtensionContext.Namespace NAMESPACE =
      ExtensionContext.Namespace.create(IcebergFlinkExtension.class);

  @Override
  public boolean supportsParameter(
      ParameterContext parameterContext, ExtensionContext extensionContext)
      throws ParameterResolutionException {
    if (parameterContext.isAnnotated(Flink.class)) {
      checkSupportedParameterType(
          Flink.class, parameterContext, TableEnvironment.class, FlinkHelper.class);
      return true;
    }
    return false;
  }

  @Override
  public Object resolveParameter(
      ParameterContext parameterContext, ExtensionContext extensionContext)
      throws ParameterResolutionException {
    if (parameterContext.isAnnotated(Flink.class)) {
      FlinkPerRun flink = FlinkPerRun.get(extensionContext);
      if (FlinkHelper.class.isAssignableFrom(parameterContext.getParameter().getType())) {
        return flink;
      }
      if (TableEnvironment.class.isAssignableFrom(parameterContext.getParameter().getType())) {
        return flink.getTableEnv();
      }
    }
    return null;
  }

  private static class FlinkPerRun implements CloseableResource, FlinkHelper {
    private final FlinkHolder flinkHolder;
    private final String catalogName;
    private final String databaseName = "db";

    static FlinkPerRun get(ExtensionContext extensionContext) {
      return extensionContext
          .getRoot()
          .getStore(NAMESPACE)
          .getOrComputeIfAbsent(
              FlinkPerRun.class, c -> new FlinkPerRun(extensionContext), FlinkPerRun.class);
    }

    private FlinkPerRun(ExtensionContext extensionContext) {
      this.flinkHolder = FlinkHolder.get(extensionContext);

      NessieEnv env = NessieEnv.get(extensionContext);
      catalogName =
          String.format("NesQuEIT_%s_%d", env.getStartedDateTimeString(), env.getStartedNanos());

      Map<String, String> config = new HashMap<>();
      config.put("type", "iceberg");
      config.put(CatalogProperties.CATALOG_IMPL, "org.apache.iceberg.nessie.NessieCatalog");
      config.put(CatalogProperties.CACHE_ENABLED, "false");
      config.putAll(nessieClientParams(extensionContext));
      config.put(
          CatalogProperties.WAREHOUSE_LOCATION,
          IcebergWarehouse.get(extensionContext).getUri().toString());

      sql("CREATE CATALOG %s WITH %s", catalogName(), toWithClause(config));
    }

    static String toWithClause(Map<String, String> props) {
      StringBuilder builder = new StringBuilder();
      builder.append("(");
      int propCount = 0;
      for (Map.Entry<String, String> entry : props.entrySet()) {
        if (propCount > 0) {
          builder.append(",");
        }
        builder
            .append("'")
            .append(entry.getKey())
            .append("'")
            .append("=")
            .append("'")
            .append(entry.getValue())
            .append("'");
        propCount++;
      }
      builder.append(")");
      return builder.toString();
    }

    @Override
    public String databaseName() {
      return databaseName;
    }

    @Override
    public String catalogName() {
      return catalogName;
    }

    @Override
    public TableEnvironment getTableEnv() {
      return flinkHolder.tableEnvironment;
    }

    @Override
    public String qualifiedTableName(String tableName) {
      return String.format("`%s`.`%s`.`%s`", catalogName(), databaseName(), tableName);
    }

    @FormatMethod
    @Override
    public TableResult exec(TableEnvironment env, String query, Object... args) {
      return env.executeSql(String.format(query, args));
    }

    @FormatMethod
    @Override
    public TableResult exec(String query, Object... args) {
      return exec(getTableEnv(), query, args);
    }

    @FormatMethod
    @Override
    public List<Row> sql(String query, Object... args) {
      TableResult tableResult = exec(query, args);
      try (CloseableIterator<Row> iter = tableResult.collect()) {
        return Lists.newArrayList(iter);
      } catch (Exception e) {
        throw new RuntimeException("Failed to collect table result", e);
      }
    }

    @Override
    public void close() {
      sql("DROP CATALOG IF EXISTS %s", catalogName());
    }
  }

  private static class FlinkHolder implements CloseableResource {
    private MiniCluster flinkMiniCluster;
    private final TableEnvironment tableEnvironment;

    static FlinkHolder get(ExtensionContext extensionContext) {
      return extensionContext
          .getRoot()
          .getStore(NAMESPACE)
          .getOrComputeIfAbsent(FlinkHolder.class, c -> new FlinkHolder(), FlinkHolder.class);
    }

    private FlinkHolder() {

      Configuration cfg = new Configuration();
      cfg.setString(DeploymentOptions.TARGET, RemoteExecutor.NAME);

      if (System.getProperty(FLINK_REMOTE_HOST) != null) {
        System.getProperties().entrySet().stream()
            .filter(e -> e.getKey().toString().startsWith(FLINK_CONFIG_PREFIX))
            .forEach(
                e ->
                    cfg.setString(
                        e.getKey().toString().substring(FLINK_CONFIG_PREFIX.length()),
                        e.getValue().toString()));

        String flinkHost = System.getProperty(FLINK_REMOTE_HOST);
        int flinkPort = Integer.getInteger(FLINK_REMOTE_PORT, 8081);

        ExecutionEnvironment executionEnvironment =
            ExecutionEnvironment.createRemoteEnvironment(flinkHost, flinkPort);
        cfg.addAll(executionEnvironment.getConfiguration());
      } else {
        cfg.addAll(DISABLE_CLASSLOADER_CHECK_CONFIG);
        cfg.setString(RestOptions.BIND_PORT, "10000-30000");

        flinkMiniCluster =
            new MiniCluster(
                new MiniClusterConfiguration.Builder()
                    .setNumTaskManagers(DEFAULT_TM_NUM)
                    .setNumSlotsPerTaskManager(DEFAULT_PARALLELISM)
                    .setConfiguration(cfg)
                    .build());

        try {
          flinkMiniCluster.start();
        } catch (Exception e) {
          throw new RuntimeException(e);
        }

        URI restURI;
        try {
          restURI = flinkMiniCluster.getRestAddress().get(60, TimeUnit.SECONDS);
        } catch (InterruptedException | ExecutionException | TimeoutException e) {
          throw new RuntimeException(e);
        }

        ExecutionEnvironment executionEnvironment =
            ExecutionEnvironment.createRemoteEnvironment(restURI.getHost(), restURI.getPort());
        cfg.addAll(executionEnvironment.getConfiguration());
      }

      PipelineExecutorServiceLoader executorServiceLoader = new DefaultExecutorServiceLoader();
      ContextEnvironment.setAsContext(
          executorServiceLoader, cfg, Thread.currentThread().getContextClassLoader(), false, true);
      StreamContextEnvironment.setAsContext(
          executorServiceLoader, cfg, Thread.currentThread().getContextClassLoader(), false, true);

      EnvironmentSettings settings = EnvironmentSettings.newInstance().inBatchMode().build();
      TableEnvironment env = TableEnvironment.create(settings);
      env.getConfig().addConfiguration(cfg);

      tableEnvironment = env;
    }

    @Override
    public void close() throws Exception {
      ContextEnvironment.unsetAsContext();
      StreamContextEnvironment.unsetAsContext();

      if (flinkMiniCluster != null) {
        try {
          flinkMiniCluster.close();
        } finally {
          flinkMiniCluster = null;
        }
      }
    }
  }
}
