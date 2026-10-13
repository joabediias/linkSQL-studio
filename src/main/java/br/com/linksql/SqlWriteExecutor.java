package br.com.linksql;

import java.sql.*;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import javax.swing.table.DefaultTableModel;

/** Confirma cada comando somente após sucesso; rollback de DML em erro/cancelamento. */
final class SqlWriteExecutor {
  private SqlWriteExecutor() {}
  record Result(DefaultTableModel model, int affected) {}

  static Result execute(Connection connection, String sql, int resultLimit,
      BooleanSupplier cancelled, Consumer<Statement> running) throws SQLException {
    boolean autoCommit = connection.getAutoCommit();
    boolean readOnly = connection.isReadOnly();
    boolean committed = false;
    try {
      connection.setReadOnly(false);
      connection.setAutoCommit(false);
      if (cancelled.getAsBoolean()) throw new CancellationException("Comando cancelado");
      Result result;
      try (Statement statement = connection.createStatement()) {
        running.accept(statement);
        statement.setQueryTimeout(120);
        statement.setMaxRows(0); // nunca limitar a quantidade de linhas alteradas
        boolean rows = statement.execute(sql);
        DefaultTableModel model = null;
        if (rows) try (ResultSet rs = statement.getResultSet()) {
          model = SqlResultModels.readResultModel(rs, resultLimit, cancelled);
        }
        result = new Result(model, statement.getUpdateCount());
        if (cancelled.getAsBoolean() || Thread.currentThread().isInterrupted())
          throw new CancellationException("Comando cancelado");
        connection.commit();
        committed = true;
      }
      return result;
    } finally {
      if (!committed) try { connection.rollback(); } catch (SQLException ignored) {}
      // Não restaurar autoCommit após rollback falhar: close/pool deve descartar a conexão.
      if (committed) {
        try { connection.setAutoCommit(autoCommit); connection.setReadOnly(readOnly); }
        catch (SQLException ignored) { /* o fechamento da conexão continua obrigatório */ }
      }
    }
  }
}
