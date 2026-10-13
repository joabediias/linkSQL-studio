package br.com.linksql;

import com.fasterxml.jackson.databind.JsonNode;
import java.sql.Connection;
import java.sql.SQLException;

/** Configuracao nao secreta. Senhas nunca sao lidas do JSON. */
record JdbcConnectionProfile(
    String name, SqlDatabaseType type, String host, int port, String database,
    String schema, String user, String passwordEnv, String jdbcUrl, boolean readOnly) {

  static JdbcConnectionProfile fromJson(JsonNode item) {
    SqlDatabaseType type = SqlDatabaseType.fromCode(item.path("type").asText("MYSQL"));
    String name = item.path("name").asText("").strip();
    String host = item.path("host").asText("").strip();
    String database = item.path("database").asText("").strip();
    String user = item.path("user").asText("").strip();
    String url = item.path("jdbcUrl").asText("").strip();
    String env = item.path("passwordEnv").asText("").strip();
    if (env.isBlank()) env = type == SqlDatabaseType.MYSQL
        ? "SQLBRIDGE_MYSQL_PASSWORD" : "LINKSQL_" + name.toUpperCase() + "_PASSWORD";
    JdbcConnectionProfile profile = new JdbcConnectionProfile(name, type, host,
        item.path("port").asInt(type == SqlDatabaseType.MYSQL ? 4407 : type.defaultPort()), database,
        item.path("schema").asText("").strip(), user, env, url,
        item.path("readOnly").asBoolean(true));
    profile.validate();
    return profile;
  }

  void validate() {
    if (name == null || !name.matches("[A-Za-z][A-Za-z0-9_]*"))
      throw new IllegalArgumentException("Nome da conexao invalido: " + name + " (use letras, numeros e _)");
    if (type == null) throw new IllegalArgumentException("Escolha o tipo do banco.");
    if (jdbcUrl == null || jdbcUrl.isBlank()) {
      if (host == null || host.isBlank() || database == null || database.isBlank())
        throw new IllegalArgumentException("Host e banco/servico sao obrigatorios sem URL JDBC personalizada.");
      if (port < 1 || port > 65535) throw new IllegalArgumentException("Porta deve estar entre 1 e 65535.");
    } else {
      String normalizedUrl = jdbcUrl.toLowerCase(java.util.Locale.ROOT);
      String expectedPrefix = switch (type) {
        case MYSQL -> "jdbc:mysql:";
        case ORACLE -> "jdbc:oracle:";
        case POSTGRESQL -> "jdbc:postgresql:";
        case SQLSERVER -> "jdbc:sqlserver:";
        case FIREBIRD -> "jdbc:firebirdsql:";
      };
      if (!normalizedUrl.startsWith(expectedPrefix))
        throw new IllegalArgumentException("URL JDBC incompativel com o tipo " + type + ".");
      if (normalizedUrl.matches("(?s).*(?:password|pwd)\\s*=.*"))
        throw new IllegalArgumentException("Nao inclua a senha na URL JDBC; use o campo Senha.");
    }
    if (user == null || user.isBlank()) throw new IllegalArgumentException("Usuario e obrigatorio.");
    if (passwordEnv == null || !passwordEnv.matches("[A-Za-z_][A-Za-z0-9_]*"))
      throw new IllegalArgumentException("Nome de variavel de senha invalido.");
  }

  String resolvedJdbcUrl() {
    return jdbcUrl != null && !jdbcUrl.isBlank() ? jdbcUrl : type.url(host, port, database);
  }

  String schemaPattern() {
    return schema == null || schema.isBlank() ? type.defaultSchema(user) : schema;
  }

  String catalogFor(Connection connection) throws SQLException {
    if (type == SqlDatabaseType.ORACLE || type == SqlDatabaseType.FIREBIRD) return null;
    // Catalogo do driver ja identifica o database. Nao adivinhar valores de outro servidor.
    return connection.getCatalog();
  }

  String schemaForMetadata() {
    String selected = schemaPattern();
    return selected.isBlank() ? null : selected;
  }

  String catalogForCalcite(Connection connection) throws SQLException { return catalogFor(connection); }
  String schemaForCalcite() {
    return schemaForMetadata();
  }
}
