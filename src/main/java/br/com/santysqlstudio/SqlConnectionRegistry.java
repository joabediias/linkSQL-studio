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
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Gerencia o ciclo de vida das fontes MySQL e das conexões federadas Calcite. */
final class SqlConnectionRegistry {
  private static final ObjectMapper JSON = new ObjectMapper();
  private final Map<String, BasicDataSource> sources = new LinkedHashMap<>();
  private Connection federation;
  private final java.util.function.Function<String, String> passwordResolver;

  SqlConnectionRegistry() { this(null); }

  SqlConnectionRegistry(java.util.function.Function<String, String> passwordResolver) {
    this.passwordResolver = passwordResolver;
  }

  Map<String, BasicDataSource> sources() { return sources; }

  /** Cada consulta federada usa uma conexão própria, necessária para cancelamento independente. */
  Connection openFederatedQueryConnection() throws SQLException {
    Connection connection = DriverManager.getConnection("jdbc:calcite:caseSensitive=false");
    try {
      SchemaPlus schemas = connection.unwrap(CalciteConnection.class).getRootSchema();
      synchronized (this) {
        for (Map.Entry<String, BasicDataSource> source : sources.entrySet()) {
          schemas.add(source.getKey(), JdbcSchema.create(schemas, source.getKey(), source.getValue(), null, null));
        }
      }
      return connection;
    } catch (Exception ex) {
      try { connection.close(); } catch (SQLException ignored) {}
      if (ex instanceof SQLException sqlEx) throw sqlEx;
      throw new SQLException("Falha ao preparar conexão federada da consulta", ex);
    }
  }

  /** Recarrega configuração de forma transacional: erro mantém fontes antigas válidas. */
  synchronized Connection configure(Path path) throws Exception {
    JsonNode root = JSON.readTree(Files.readString(path));
    Map<String, BasicDataSource> nextSources = new LinkedHashMap<>();
    Connection next = DriverManager.getConnection("jdbc:calcite:caseSensitive=false");
    try {
      SchemaPlus schemas = next.unwrap(CalciteConnection.class).getRootSchema();
      Set<String> names = new HashSet<>();
      Map<String, String> requestedPasswords = new java.util.HashMap<>();
      for (JsonNode item : root.path("connections")) {
        String name = item.path("name").asText();
        if (!name.matches("[A-Za-z][A-Za-z0-9_]*") || !names.add(name.toUpperCase(Locale.ROOT)))
          throw new IllegalArgumentException("Nome inválido ou duplicado: " + name);
        String passwordEnv = item.path("passwordEnv").asText();
        String password = System.getenv(passwordEnv);
        if (password == null || password.isEmpty()) {
          password = requestedPasswords.get(passwordEnv);
          if (password == null && passwordResolver != null) {
            password = passwordResolver.apply(passwordEnv);
            if (password != null && !password.isEmpty()) requestedPasswords.put(passwordEnv, password);
          }
        }
        if (password == null || password.isEmpty())
          throw new IllegalArgumentException("Senha MySQL não informada para: " + passwordEnv);
        BasicDataSource ds = new BasicDataSource();
        ds.setDriverClassName("com.mysql.cj.jdbc.Driver");
        ds.setUrl("jdbc:mysql://" + item.path("host").asText() + ":" + item.path("port").asInt(4407) + "/" + item.path("database").asText() + "?useSSL=false&connectTimeout=5000&socketTimeout=120000");
        ds.setUsername(item.path("user").asText());
        ds.setPassword(password);
        ds.setMaxTotal(2); ds.setMaxIdle(1); ds.setInitialSize(0);
        nextSources.put(name, ds);
        schemas.add(name, JdbcSchema.create(schemas, name, ds, null, null));
      }
      Connection previous = federation;
      Map<String, BasicDataSource> old = new LinkedHashMap<>(sources);
      federation = next;
      sources.clear(); sources.putAll(nextSources);
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
    sources.clear();
  }
}
