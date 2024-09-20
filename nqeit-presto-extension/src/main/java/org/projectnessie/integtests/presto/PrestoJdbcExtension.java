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

import static java.lang.String.format;
import static org.projectnessie.integtests.nessie.internal.Util.checkSupportedParameterType;

import com.facebook.presto.iceberg.CatalogType;
import com.facebook.presto.iceberg.IcebergConfig;
import com.facebook.presto.iceberg.IcebergPlugin;
import com.facebook.presto.jdbc.PrestoDriver;
import com.facebook.presto.server.testing.TestingPrestoServer;
import com.facebook.presto.sql.parser.SqlParserOptions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.errorprone.annotations.FormatMethod;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.Spliterators.AbstractSpliterator;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;
import org.apache.iceberg.FileFormat;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.ExtensionContext.Store.CloseableResource;
import org.junit.jupiter.api.extension.ParameterContext;
import org.junit.jupiter.api.extension.ParameterResolutionException;
import org.junit.jupiter.api.extension.ParameterResolver;
import org.projectnessie.integtests.nessie.internal.DefaultBranchPerRun;
import org.projectnessie.integtests.nessie.internal.HiveMetastoreCatalog;
import org.projectnessie.integtests.nessie.internal.IcebergWarehouse;
import org.projectnessie.integtests.nessie.internal.NessieEnv;
import org.projectnessie.integtests.nessie.internal.PrestoLocalData;
import org.projectnessie.model.Branch;

public class PrestoJdbcExtension implements ParameterResolver {

  private static final ExtensionContext.Namespace NAMESPACE =
      ExtensionContext.Namespace.create(PrestoJdbcExtension.class);

  public static final String ICEBERG_CATALOG = "iceberg";
  public static final String PRESTO_JDBC_HOST_PORT = "presto.jdbc.host-port";
  public static final String PRESTO_SESSION_PREFIX = "presto.session.";

  @Override
  public boolean supportsParameter(
      ParameterContext parameterContext, ExtensionContext extensionContext)
      throws ParameterResolutionException {
    if (parameterContext.isAnnotated(Presto.class)) {
      checkSupportedParameterType(
          Presto.class, parameterContext, Connection.class, PrestoHelper.class);
      return true;
    }
    return false;
  }

  @Override
  public Object resolveParameter(
      ParameterContext parameterContext, ExtensionContext extensionContext)
      throws ParameterResolutionException {
    Presto presto = parameterContext.findAnnotation(Presto.class).get();
    PrestoServer prestoServer = PrestoServer.get(extensionContext);
    try {
      Properties properties = new Properties();

      System.getProperties().entrySet().stream()
          .filter(e -> e.getKey().toString().startsWith(PRESTO_SESSION_PREFIX))
          .forEach(
              e ->
                  properties.put(
                      e.getKey().toString().substring(PRESTO_SESSION_PREFIX.length()),
                      e.getValue().toString()));

      prestoServer.populateConnectionProperties(properties);

      Branch defaultBranch = DefaultBranchPerRun.get(extensionContext).getDefaultBranch();

      // See com.facebook.presto.iceberg.IcebergSessionProperties for valid session properties
      properties.put(
          "sessionProperties", "iceberg.nessie_reference_name:" + defaultBranch.getName());

      Connection conn =
          PrestoDriverResource.get(extensionContext)
              .connect(
                  prestoServer.getHostPort(),
                  presto.catalog().isEmpty() ? ICEBERG_CATALOG : presto.catalog(),
                  presto.schema(),
                  presto.extraUrlParameters(),
                  properties);
      PrestoConnections.get(extensionContext).add(conn);

      if (parameterContext.getParameter().getType().isAssignableFrom(PrestoHelper.class)) {
        return new PrestoHelperImpl(conn);
      }

      return conn;
    } catch (SQLException e) {
      throw new ParameterResolutionException("Failed to connect to Presto", e);
    }
  }

  private interface PrestoServer {
    static PrestoServer get(ExtensionContext extensionContext) {
      return extensionContext
          .getRoot()
          .getStore(NAMESPACE)
          .getOrComputeIfAbsent(
              PrestoServer.class, c -> createPrestoServer(extensionContext), PrestoServer.class);
    }

    static PrestoServer createPrestoServer(ExtensionContext extensionContext) {
      String hostPort = System.getProperty(PRESTO_JDBC_HOST_PORT);
      if (hostPort == null) {
        return new LocalPrestoServer(extensionContext);
      }

      return new RemotePrestoServer(hostPort);
    }

    String getHostPort();

    void populateConnectionProperties(Properties properties);
  }

  private static class RemotePrestoServer implements PrestoServer {

    private final String hostPort;

    public RemotePrestoServer(String hostPort) {
      this.hostPort = hostPort;
    }

    @Override
    public String getHostPort() {
      return hostPort;
    }

    @Override
    public void populateConnectionProperties(Properties properties) {}
  }

  private static class LocalPrestoServer implements PrestoServer, CloseableResource {
    private final TestingPrestoServer testingPrestoServer;

    public LocalPrestoServer(ExtensionContext extensionContext) {
      try {
        testingPrestoServer =
            new TestingPrestoServer(
                true,
                ImmutableMap.of(),
                null,
                null,
                new SqlParserOptions(),
                ImmutableList.of(),
                Optional.of(PrestoLocalData.get(extensionContext).getPath()));
        testingPrestoServer.installPlugin(new IcebergPlugin());

        FileFormat format = new IcebergConfig().getFileFormat();

        NessieEnv env = NessieEnv.get(extensionContext);
        // presto with iceberg 1.4.x only supports the v1 endpoint
        String nessieUri = env.getNessieUri().replaceAll("api/v2", "api/v1");

        Map<String, String> extraConnectorProperties =
            ImmutableMap.<String, String>builder()
                .put("iceberg.catalog.type", CatalogType.NESSIE.name())
                .put("iceberg.nessie.uri", nessieUri)
                .build();

        Map<String, String> icebergProperties =
            ImmutableMap.<String, String>builder()
                .put("hive.metastore", "file")
                .put(
                    "hive.metastore.catalog.dir",
                    HiveMetastoreCatalog.get(extensionContext).getUri().toString())
                .put("iceberg.file-format", format.name())
                .put(
                    "iceberg.catalog.warehouse",
                    IcebergWarehouse.get(extensionContext).getUri().toString())
                .putAll(extraConnectorProperties)
                .build();
        testingPrestoServer.createCatalog(ICEBERG_CATALOG, "iceberg", icebergProperties);
      } catch (Exception e) {
        throw new RuntimeException(e);
      }
    }

    @Override
    public String getHostPort() {
      return testingPrestoServer.getAddress().getHost()
          + ':'
          + testingPrestoServer.getAddress().getPort();
    }

    @Override
    public void populateConnectionProperties(Properties properties) {
      properties.put("user", "admin");
      // properties.put("password", prestoServer.getPassword());
    }

    @Override
    public void close() throws Throwable {
      testingPrestoServer.close();
    }
  }

  private static class PrestoConnections implements CloseableResource {
    private final List<Connection> connections = new ArrayList<>();

    private PrestoConnections() {}

    static PrestoConnections get(ExtensionContext extensionContext) {
      return extensionContext
          .getStore(NAMESPACE)
          .getOrComputeIfAbsent(
              PrestoConnections.class, c -> new PrestoConnections(), PrestoConnections.class);
    }

    @Override
    public void close() throws Throwable {
      Throwable toThrow = null;
      for (Connection connection : connections) {
        try {
          connection.close();
        } catch (Throwable t) {
          if (toThrow == null) {
            toThrow = t;
          } else {
            toThrow.addSuppressed(t);
          }
        }
      }
      if (toThrow != null) {
        throw toThrow;
      }
    }

    public void add(Connection conn) {
      connections.add(conn);
    }
  }

  private static class PrestoDriverResource implements CloseableResource {
    private final PrestoDriver driver;

    PrestoDriverResource() {
      this.driver = new PrestoDriver();
    }

    static PrestoDriverResource get(ExtensionContext extensionContext) {
      return extensionContext
          .getRoot()
          .getStore(NAMESPACE)
          .getOrComputeIfAbsent(
              PrestoDriverResource.class,
              d -> new PrestoDriverResource(),
              PrestoDriverResource.class);
    }

    @Override
    public void close() {
      driver.close();
    }

    Connection connect(
        String hostPort,
        String catalog,
        String schema,
        String extraUrlParameters,
        Properties properties)
        throws SQLException {
      return driver.connect(
          format("jdbc:presto://%s/%s/%s?%s", hostPort, catalog, schema, extraUrlParameters),
          properties);
    }
  }

  private static final class PrestoHelperImpl implements PrestoHelper {

    private final Connection connection;

    private PrestoHelperImpl(Connection connection) {
      this.connection = connection;
    }

    @Override
    public String getDefaultCatalog() {
      return ICEBERG_CATALOG;
    }

    @Override
    public String qualifiedTableName(String table) {
      return qualifiedTableName("db", table);
    }

    @Override
    public String qualifiedTableName(String schema, String table) {
      return format("%s.%s.%s", getDefaultCatalog(), schema, table);
    }

    @Override
    public Connection getConnection() {
      return connection;
    }

    @Override
    @FormatMethod
    public boolean execute(String sql, Object... args) throws SQLException {
      try (Statement st = getConnection().createStatement()) {
        return st.execute(format(sql, args));
      }
    }

    @Override
    @FormatMethod
    public int executeUpdate(String sql, Object... args) throws SQLException {
      try (Statement st = getConnection().createStatement()) {
        return st.executeUpdate(format(sql, args));
      }
    }

    @Override
    @FormatMethod
    public Stream<List<Object>> query(String sql, Object... args) throws SQLException {
      Statement st = getConnection().createStatement();
      st.closeOnCompletion();
      ResultSet rs = st.executeQuery(format(sql, args));

      Runnable rsCloser =
          new Runnable() {
            private boolean closed;

            @Override
            public void run() {
              if (closed) {
                return;
              }
              try {
                rs.close();
              } catch (SQLException e) {
                throw new RuntimeException(e);
              }
              closed = true;
            }
          };

      AbstractSpliterator<List<Object>> split =
          new AbstractSpliterator<List<Object>>(Long.MAX_VALUE, 0) {
            @Override
            public boolean tryAdvance(Consumer<? super List<Object>> action) {
              try {
                if (!rs.next()) {
                  rsCloser.run();
                  return false;
                }

                int columnCount = rs.getMetaData().getColumnCount();
                action.accept(
                    IntStream.rangeClosed(1, columnCount)
                        .mapToObj(
                            c -> {
                              try {
                                return rs.getObject(c);
                              } catch (SQLException e) {
                                throw new RuntimeException(e);
                              }
                            })
                        .collect(Collectors.toList()));
                return true;
              } catch (SQLException e) {
                throw new RuntimeException(e);
              }
            }
          };

      return StreamSupport.stream(split, false).onClose(rsCloser);
    }
  }
}
