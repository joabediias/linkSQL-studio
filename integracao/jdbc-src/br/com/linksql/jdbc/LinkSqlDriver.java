package br.com.linksql.jdbc;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Driver;
import java.sql.DriverManager;
import java.sql.DriverPropertyInfo;
import java.sql.SQLException;
import java.util.Properties;
import java.util.logging.Logger;

/**
 * Driver JDBC EMBUTIDO. Nenhum socket, Gateway ou servidor local.
 * Carrega as fontes JDBC pelo mecanismo de consultas do LinkSQL.
 */
public final class LinkSqlDriver implements Driver {
  private static final String PREFIX = "jdbc:linksql:embedded";

  // Necessario para apps que descobrem drivers por ServiceLoader/DriverManager.
  static {
    try {
      DriverManager.registerDriver(new LinkSqlDriver());
    } catch (SQLException ex) {
      throw new ExceptionInInitializerError(ex);
    }
  }


  @Override public Connection connect(String url, Properties properties) throws SQLException {
    if (!acceptsURL(url)) return null;
    Path config = configPath(url);
    if (!Files.isRegularFile(config)) {
      throw new SQLException("Arquivo de conexoes LinkSQL nao encontrado: " + config
          + ". Cadastre as conexoes no LinkSQL ou use ?config=CAMINHO_URL_ENCODED.", "08001");
    }
    return EmbeddedConnection.open(config);
  }

  @Override public boolean acceptsURL(String url) {
    return url != null && (url.equals(PREFIX) || url.startsWith(PREFIX + "?"));
  }

  private static Path configPath(String url) throws SQLException {
    String option = null;
    int q = url.indexOf('?');
    if (q >= 0) {
      for (String part : url.substring(q + 1).split("&")) {
        if (part.startsWith("config=")) {
          option = URLDecoder.decode(part.substring(7), StandardCharsets.UTF_8);
          break;
        }
      }
    }
    if (option != null && !option.isBlank()) {
      try { return Path.of(option).toAbsolutePath().normalize(); }
      catch (RuntimeException ex) { throw new SQLException("Caminho config invalido", "08001", ex); }
    }
    // Pasta legada preservada na renomeacao do Studio para LinkSQL.
    Path home = Path.of(System.getProperty("user.home"), ".santysql-studio", "config", "connections.json");
    if (Files.isRegularFile(home)) return home;
    Path local = Path.of("config", "connections.json").toAbsolutePath();
    return Files.isRegularFile(local) ? local : home;
  }

  @Override public DriverPropertyInfo[] getPropertyInfo(String url, Properties props) {
    DriverPropertyInfo config = new DriverPropertyInfo("config", "");
    config.description = "Caminho opcional para connections.json (na URL, use codificacao URL).";
    return new DriverPropertyInfo[] { config };
  }
  @Override public int getMajorVersion() { return 0; }
  @Override public int getMinorVersion() { return 4; }
  @Override public boolean jdbcCompliant() { return false; }
  @Override public Logger getParentLogger() { return Logger.getLogger("br.com.linksql.jdbc"); }
}
