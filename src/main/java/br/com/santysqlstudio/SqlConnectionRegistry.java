package br.com.santysqlstudio;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.calcite.adapter.jdbc.JdbcSchema;
import org.apache.calcite.jdbc.CalciteConnection;
import org.apache.calcite.schema.SchemaPlus;
import org.apache.commons.dbcp2.BasicDataSource;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Registro de fontes JDBC do Studio. O formato legado sem "type" continua MySQL.
 * A execucao de SQL nativo nao-MySQL sera expandida nas proximas versoes.
 */
final class SqlConnectionRegistry {
  private static final ObjectMapper JSON = new ObjectMapper();
  private final Map<String, BasicDataSource> sources = new LinkedHashMap<>();
  private final Map<String, JdbcConnectionProfile> profiles = new LinkedHashMap<>();
  private final java.util.function.Function<String, String> passwordResolver;
  private Connection federation;

  SqlConnectionRegistry() { this(null); }
  SqlConnectionRegistry(java.util.function.Function<String, String> passwordResolver) {
    this.passwordResolver = passwordResolver;
  }

  Map<String, BasicDataSource> sources() { return sources; }
  synchronized JdbcConnectionProfile profile(String name) { return profiles.get(name); }
  synchronized List<String> mysqlSourceNames() {
    List<String> names = new ArrayList<>();
    for (JdbcConnectionProfile profile : profiles.values())
      if (profile.type() == SqlDatabaseType.MYSQL) names.add(profile.name());
    return names;
  }

  /** Nao mantem a senha em arquivo; no EXE ela pode ser solicitada uma vez por recarga. */
  private String resolvePassword(JdbcConnectionProfile profile, Map<String, String> cache) {
    String key = profile.passwordEnv();
    String password = System.getenv(key);
    if (password == null || password.isEmpty()) {
      password = cache.get(key);
      if (password == null && passwordResolver != null) {
        password = passwordResolver.apply(key);
        if (password != null && !password.isEmpty()) cache.put(key, password);
      }
    }
    if (password == null || password.isEmpty())
      throw new IllegalArgumentException("Senha nao informada para " + profile.name() + ". Configure " + key + ".");
    return password;
  }

  private static BasicDataSource makeDataSource(JdbcConnectionProfile profile, String password) {
    BasicDataSource ds = new BasicDataSource();
    ds.setDriverClassName(profile.type().driver());
    ds.setUrl(profile.resolvedJdbcUrl());
    ds.setUsername(profile.user());
    ds.setPassword(password);
    ds.setMaxTotal(2);
    ds.setMaxIdle(1);
    ds.setInitialSize(0);
    ds.setMaxWaitMillis(15000);
    return ds;
  }

  /** Teste isolado nao altera conexoes carregadas ou configurações. */
  String test(JdbcConnectionProfile profile, String password) throws Exception {
    profile.validate();
    if (password == null || password.isBlank())
      throw new IllegalArgumentException("Informe a senha para testar a conexao.");
    try (BasicDataSource ds = makeDataSource(profile, password);
         Connection conn = ds.getConnection()) {
      return conn.getMetaData().getDatabaseProductName() + " "
          + conn.getMetaData().getDatabaseProductVersion();
    }
  }

  /** Cada consulta federada recebe uma conexao independente, preservando cancelamento. */
  Connection openFederatedQueryConnection() throws SQLException {
    Connection connection = DriverManager.getConnection("jdbc:calcite:caseSensitive=false");
    try {
      SchemaPlus schemas = connection.unwrap(CalciteConnection.class).getRootSchema();
      synchronized (this) {
        for (Map.Entry<String, BasicDataSource> entry : sources.entrySet()) {
          JdbcConnectionProfile profile = profiles.get(entry.getKey());
          addCalciteSchema(schemas, profile, entry.getValue());
        }
      }
      return connection;
    } catch (Exception ex) {
      try { connection.close(); } catch (SQLException ignored) {}
      if (ex instanceof SQLException sqlEx) throw sqlEx;
      throw new SQLException("Falha ao preparar conexao federada", ex);
    }
  }

  private static void addCalciteSchema(SchemaPlus schemas, JdbcConnectionProfile profile,
                                       BasicDataSource ds) {
    String catalog = switch (profile.type()) {
      case SQLSERVER, POSTGRESQL -> profile.database();
      default -> null;
    };
    schemas.add(profile.name(), JdbcSchema.create(schemas, profile.name(), ds,
        catalog, profile.schemaForCalcite()));
  }

  /** Atualiza as fontes de forma atomica; em erro preserva conexoes anteriores. */
  synchronized Connection configure(Path path) throws Exception {
    JsonNode root = JSON.readTree(Files.readString(path));
    Map<String, BasicDataSource> nextSources = new LinkedHashMap<>();
    Map<String, JdbcConnectionProfile> nextProfiles = new LinkedHashMap<>();
    Connection next = DriverManager.getConnection("jdbc:calcite:caseSensitive=false");
    try {
      SchemaPlus schemas = next.unwrap(CalciteConnection.class).getRootSchema();
      Set<String> names = new HashSet<>();
      Map<String, String> passwordCache = new LinkedHashMap<>();
      for (JsonNode item : root.path("connections")) {
        JdbcConnectionProfile profile = JdbcConnectionProfile.fromJson(item);
        if (!names.add(profile.name().toUpperCase(Locale.ROOT)))
          throw new IllegalArgumentException("Nome de conexao duplicado: " + profile.name());
        BasicDataSource ds = makeDataSource(profile, resolvePassword(profile, passwordCache));
        nextSources.put(profile.name(), ds);
        nextProfiles.put(profile.name(), profile);
        addCalciteSchema(schemas, profile, ds);
      }
      Connection previous = federation;
      Map<String, BasicDataSource> old = new LinkedHashMap<>(sources);
      federation = next;
      sources.clear(); sources.putAll(nextSources);
      profiles.clear(); profiles.putAll(nextProfiles);
      if (previous != null) try { previous.close(); } catch (SQLException ignored) {}
      for (BasicDataSource ds : old.values()) try { ds.close(); } catch (SQLException ignored) {}
      return next;
    } catch (Exception ex) {
      try { next.close(); } catch (SQLException ignored) {}
      for (BasicDataSource ds : nextSources.values()) try { ds.close(); } catch (SQLException ignored) {}
      throw ex;
    }
  }

  synchronized void close() {
    try { if (federation != null) federation.close(); } catch (Exception ignored) {}
    federation = null;
    for (BasicDataSource ds : sources.values()) try { ds.close(); } catch (SQLException ignored) {}
    sources.clear(); profiles.clear();
  }
}
