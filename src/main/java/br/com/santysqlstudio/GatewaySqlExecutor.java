package br.com.santysqlstudio;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;

/**
 * Ponte do protocolo de rede para o mecanismo JDBC/Calcite JA existente no Studio.
 * Execucao sem estado entre requisicoes; o gateway nao altera preferências ou abas.
 */
final class GatewaySqlExecutor implements AutoCloseable {
  @FunctionalInterface interface ResultConsumer {
    void accept(ResultSet results) throws Exception;
  }

  private final SqlConnectionRegistry registry = new SqlConnectionRegistry();

  GatewaySqlExecutor(Path connectionsFile) throws Exception {
    registry.configure(connectionsFile);
  }

  void executeSelect(String sql, ResultConsumer consumer) throws Exception {
    GatewaySqlGuard.requireReadOnlySelect(sql);
    // Arquivos .sql exportados pelo SantySQL 5.3.7 podem terminar em ;.
    // O roteador nativo aceita apenas uma instrucao sem terminador.
    sql = sql.strip();
    if (sql.endsWith(";")) sql = sql.substring(0, sql.length() - 1).stripTrailing();
    NativeSqlRouter.Route nativeRoute = NativeSqlRouter.route(sql, registry.allProfiles());
    SqlQueryPlanner.DirectSql mysqlRoute = SqlQueryPlanner.directSql(sql, registry.mysqlSourceNames());
    Connection connection;
    String executedSql;
    if (nativeRoute != null) {
      connection = sourceConnection(nativeRoute.source());
      executedSql = nativeRoute.sql();
    } else if (mysqlRoute != null) {
      connection = sourceConnection(mysqlRoute.source());
      executedSql = SqlQueryPlanner.executableSql(sql, registry.mysqlSourceNames());
    } else {
      connection = registry.openFederatedQueryConnection(sql);
      executedSql = sql;
    }
    try (Connection c = connection) {
      // So leitura e tambem protegida por permissao do usuario de cada banco.
      try { c.setReadOnly(true); } catch (SQLException ignored) { /* Calcite pode nao suportar */ }
      try (Statement statement = c.createStatement()) {
        statement.setQueryTimeout(60);
        statement.setFetchSize(500);
        try (ResultSet result = statement.executeQuery(executedSql)) {
          consumer.accept(result);
        }
      }
    }
  }

  private Connection sourceConnection(String alias) throws SQLException {
    org.apache.commons.dbcp2.BasicDataSource source = registry.sources().get(alias);
    if (source == null) throw new SQLException("Conexao nao cadastrada: " + alias);
    return source.getConnection();
  }

  List<String> sourceNames() {
    return registry.allProfiles().stream().map(JdbcConnectionProfile::name).toList();
  }

  @Override public void close() { registry.close(); }
}
