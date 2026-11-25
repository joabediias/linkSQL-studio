package br.com.linksql;

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
  private final String mode;
  private final Connection metaConnection;

  public LinkSqlEmbeddedEngine(Path connectionsFile) throws SQLException {
    this(connectionsFile, FederatedEngineSettings.effective().mode());
  }

  public LinkSqlEmbeddedEngine(Path connectionsFile, String engineMode) throws SQLException {
    mode = engineMode.toLowerCase(java.util.Locale.ROOT);
    if (!java.util.Set.of("calcite", "duckdb", "auto").contains(mode))
      throw new SQLException("Motor federado inválido: " + engineMode);
    registry = new SqlConnectionRegistry(LinkSqlEmbeddedEngine::resolvePassword);
    try { metaConnection = registry.configureLazy(connectionsFile); }
    catch (Exception ex) {
      registry.close();
      throw new SQLException("Nao foi possivel carregar as conexoes do LinkSQL: " + ex.getMessage(), "08001", ex);
    }
  }

  private static String resolvePassword(JdbcConnectionProfile profile) {
    String key = profile.name().toUpperCase(java.util.Locale.ROOT);
    String cached = SESSION_PASSWORDS.get(key);
    if (cached != null) return cached;
    if (java.awt.GraphicsEnvironment.isHeadless()) return null;
    final String[] value = new String[1];
    Runnable ask = () -> {
      JPasswordField pwd = new JPasswordField(24);
      javax.swing.JCheckBox keep = new javax.swing.JCheckBox("Lembrar senha protegida no Windows", true);
      javax.swing.JPanel panel = new javax.swing.JPanel(new java.awt.BorderLayout(0, 8));
      panel.add(new javax.swing.JLabel("Senha: " + profile.name() + " (" + profile.type() + ")"), java.awt.BorderLayout.NORTH);
      panel.add(pwd, java.awt.BorderLayout.CENTER);
      panel.add(keep, java.awt.BorderLayout.SOUTH);
      int answer = JOptionPane.showConfirmDialog(null, panel,
          "LinkSQL JDBC - senha da conexão", JOptionPane.OK_CANCEL_OPTION, JOptionPane.QUESTION_MESSAGE);
      if (answer == JOptionPane.OK_OPTION) {
        char[] chars = pwd.getPassword();
        if (chars.length != 0) {
          if (keep.isSelected()) {
            try { ConnectionVault.save(profile.name(), chars, ConnectionVault.Duration.PERMANENT); }
            catch (java.io.IOException ex) {
              JOptionPane.showMessageDialog(null, "Nao foi possivel proteger a senha: " + ex.getMessage(),
                  "LinkSQL JDBC", JOptionPane.WARNING_MESSAGE);
            }
          }
          value[0] = new String(chars);
        }
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
    return plan(original, () -> Thread.currentThread().isInterrupted(), st -> {});
  }

  public Query plan(String original, java.util.function.BooleanSupplier cancelled,
      java.util.function.Consumer<java.sql.Statement> active) throws SQLException {
    ReadOnlySqlGuard.requireReadOnlySelect(original);
    String sql = original.strip();
    if (sql.endsWith(";")) sql = sql.substring(0, sql.length() - 1).stripTrailing();
    NativeSqlRouter.Route nativeRoute = NativeSqlRouter.route(sql, registry.allProfiles());
    SqlQueryPlanner.DirectSql mysqlRoute = SqlQueryPlanner.directSql(sql, registry.mysqlSourceNames());
    if (nativeRoute != null) return new Query(sourceConnection(nativeRoute.source()), nativeRoute.sql());
    if (mysqlRoute != null) return new Query(sourceConnection(mysqlRoute.source()),
        SqlQueryPlanner.executableSql(sql, registry.mysqlSourceNames()));
    return new Query(openFederated(registry, sql, mode, cancelled, active), sql);
  }

  static Connection openFederated(SqlConnectionRegistry registry, String sql, String mode) throws SQLException {
    return openFederated(registry, sql, mode, () -> Thread.currentThread().isInterrupted(), st -> {});
  }

  static Connection openFederated(SqlConnectionRegistry registry, String sql, String mode,
      java.util.function.BooleanSupplier cancelled, java.util.function.Consumer<java.sql.Statement> active) throws SQLException {
    if (!mode.equals("duckdb")) return registry.openFederatedQueryConnection(sql);
    // Origens ainda não homologadas no DuckDB permanecem no Calcite.
    try { DuckDbFederatedExecutor.tables(sql, registry.mysqlSourceNames()); }
    catch (SQLException unsupported) { return registry.openFederatedQueryConnection(sql); }
    DuckDbWorkspace workspace = null;
    try {
      var config = FederatedEngineSettings.effective();
      int memory = config.memoryMb();
      int threads = config.threads();
      Path temp = config.temp();
      workspace = DuckDbFederatedExecutor.prepare(sql, registry, temp, memory, threads,
          cancelled, active);
      DuckDbSqlCompatibility.install(workspace.connection());
      return workspace.managedConnection();
    } catch (Exception ex) {
      if (workspace != null) try { workspace.close(); } catch (Exception close) { ex.addSuppressed(close); }
      if (ex instanceof SQLException se) throw se;
      throw new SQLException("Falha no motor DuckDB experimental", ex);
    }
  }

  private Connection sourceConnection(String alias) throws SQLException {
    return registry.source(alias).getConnection();
  }

  @Override public void close() { registry.close(); }
}
