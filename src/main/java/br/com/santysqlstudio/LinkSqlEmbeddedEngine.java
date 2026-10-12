package br.com.santysqlstudio;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import javax.swing.JPasswordField;
import javax.swing.JOptionPane;
import javax.swing.SwingUtilities;

/** Reutiliza o registro de conexoes, roteamento nativo e Calcite sem servidor externo. */
public final class LinkSqlEmbeddedEngine implements AutoCloseable {
  public record Query(Connection connection, String sql) {}
  private static final ConcurrentHashMap<String, String> SESSION_PASSWORDS = new ConcurrentHashMap<>();
  private final SqlConnectionRegistry registry;
  private final Connection metaConnection;

  public LinkSqlEmbeddedEngine(Path connectionsFile) throws SQLException {
    registry = new SqlConnectionRegistry(LinkSqlEmbeddedEngine::resolvePassword);
    try { metaConnection = registry.configure(connectionsFile); }
    catch (Exception ex) {
      registry.close();
      throw new SQLException("Nao foi possivel carregar as conexoes do LinkSQL: " + ex.getMessage(), "08001", ex);
    }
  }

  private static String resolvePassword(String key) {
    String cached = SESSION_PASSWORDS.get(key);
    if (cached != null) return cached;
    if (java.awt.GraphicsEnvironment.isHeadless()) return null;
    final String[] value = new String[1];
    Runnable ask = () -> {
      JPasswordField pwd = new JPasswordField(24);
      int answer = JOptionPane.showConfirmDialog(null, pwd,
          "LinkSQL JDBC - senha para " + key, JOptionPane.OK_CANCEL_OPTION, JOptionPane.QUESTION_MESSAGE);
      if (answer == JOptionPane.OK_OPTION) {
        char[] chars = pwd.getPassword();
        value[0] = new String(chars);
        java.util.Arrays.fill(chars, '\0');
      }
    };
    try {
      if (SwingUtilities.isEventDispatchThread()) ask.run();
      else SwingUtilities.invokeAndWait(ask);
    } catch (Exception ex) { return null; }
    if (value[0] != null && !value[0].isEmpty()) SESSION_PASSWORDS.putIfAbsent(key, value[0]);
    return value[0];
  }

  public Connection metadataConnection() { return metaConnection; }

  public Query plan(String original) throws SQLException {
    GatewaySqlGuard.requireReadOnlySelect(original);
    String sql = original.strip();
    if (sql.endsWith(";")) sql = sql.substring(0, sql.length() - 1).stripTrailing();
    NativeSqlRouter.Route nativeRoute = NativeSqlRouter.route(sql, registry.allProfiles());
    SqlQueryPlanner.DirectSql mysqlRoute = SqlQueryPlanner.directSql(sql, registry.mysqlSourceNames());
    if (nativeRoute != null) return new Query(sourceConnection(nativeRoute.source()), nativeRoute.sql());
    if (mysqlRoute != null) return new Query(sourceConnection(mysqlRoute.source()),
        SqlQueryPlanner.executableSql(sql, registry.mysqlSourceNames()));
    return new Query(registry.openFederatedQueryConnection(sql), sql);
  }

  private Connection sourceConnection(String alias) throws SQLException {
    org.apache.commons.dbcp2.BasicDataSource source = registry.sources().get(alias);
    if (source == null) throw new SQLException("Conexao nao cadastrada: " + alias);
    return source.getConnection();
  }

  @Override public void close() { registry.close(); }
}
