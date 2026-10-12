package br.com.linksql;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;
import javax.swing.table.DefaultTableModel;

/**
 * Paginacao portavel para consultas nativas Oracle, PostgreSQL, SQL Server e
 * Firebird. Nao injeta LIMIT/OFFSET de outro dialeto no SQL do usuario.
 * Paginas altas exigem ler/pular registros anteriores; o empurramento de
 * OFFSET/FETCH ao servidor sera uma otimizacao futura, por fornecedor.
 */
final class NativeJdbcPaging {
  private NativeJdbcPaging() {}
  record Window(String sql, long skip, int amount, boolean limited) {}

  static Window window(String sql, int page, int size) throws SQLException {
    if (page < 0 || size < 0) throw new SQLException("Pagina ou tamanho de pagina invalido.");
    SqlQueryPlanner.SqlWindow requested = SqlQueryPlanner.sqlWindow(sql);
    long skipped;
    try { skipped = Math.addExact(requested.startOffset(), Math.multiplyExact((long) page, size)); }
    catch (ArithmeticException ex) { throw new SQLException("Offset de pagina acima do limite suportado.", ex); }
    long remaining = requested.maxRows() == null ? Long.MAX_VALUE
        : Math.max(0L, requested.maxRows() - (long) page * size);
    int amount;
    if (size == 0) {
      if (requested.maxRows() == null) amount = 0; // Sem limite SQL: Todas.
      else if (remaining > Integer.MAX_VALUE)
        throw new SQLException("Limite SQL acima do maximo suportado pelo leitor JDBC.");
      else amount = (int) remaining;
    } else amount = (int) Math.min((long) size, remaining);
    boolean limited = size != 0 || requested.maxRows() != null;
    return new Window(requested.baseSql(), skipped, amount, limited);
  }

  static void configure(Statement stmt, Window window) throws SQLException {
    if (window.limited()) {
      long end;
      try { end = Math.addExact(window.skip(), (long) window.amount()); }
      catch (ArithmeticException ex) { throw new SQLException("Offset acima do limite JDBC.", ex); }
      if (end > Integer.MAX_VALUE) {
        // JDBC Statement.setMaxRows so aceita int; ainda pode executar sem essa otimizacao.
        stmt.setMaxRows(0);
      } else stmt.setMaxRows((int) end);
    } else stmt.setMaxRows(0);
  }

  static DefaultTableModel read(ResultSet rs, Window window, BooleanSupplier cancelled)
      throws SQLException {
    for (long i = 0; i < window.skip(); i++) {
      if (cancelled.getAsBoolean() || Thread.currentThread().isInterrupted())
        throw new CancellationException("Consulta cancelada");
      if (!rs.next()) return SqlResultModels.readResultModel(rs, 0, cancelled);
    }
    if (window.limited() && window.amount() == 0)
      return emptyModel(rs);
    return SqlResultModels.readResultModel(rs, window.limited() ? window.amount() : 0, cancelled);
  }

  private static DefaultTableModel emptyModel(ResultSet rs) throws SQLException {
    java.sql.ResultSetMetaData md = rs.getMetaData();
    java.util.Vector<String> names = new java.util.Vector<>();
    for (int i = 1; i <= md.getColumnCount(); i++) names.add(md.getColumnLabel(i));
    return new DefaultTableModel(new java.util.Vector<java.util.Vector<Object>>(), names) {
      public boolean isCellEditable(int row, int col) { return false; }
    };
  }
}
