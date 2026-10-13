package br.com.linksql;

import java.sql.*;
import java.util.Set;
import java.util.concurrent.*;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import javax.swing.table.DefaultTableModel;

/** Avança na mesma consulta; páginas anteriores continuam no cache limitado da interface. */
final class FederatedResultCursor implements AutoCloseable {
  @FunctionalInterface interface ConnectionFactory { Connection open() throws SQLException; }
  private static final Set<FederatedResultCursor> OPEN = ConcurrentHashMap.newKeySet();
  private static final ScheduledExecutorService REAPER = Executors.newSingleThreadScheduledExecutor(r -> {
    Thread t = new Thread(r, "linksql-cursor-idle"); t.setDaemon(true); return t;
  });
  private final Connection connection;
  private final Statement statement;
  private ResultSet rows;
  private volatile int nextPage;
  private volatile boolean closed;
  private ScheduledFuture<?> expiry;

  private FederatedResultCursor(Connection connection, Statement statement) {
    this.connection = connection; this.statement = statement;
    OPEN.add(this);
  }

  static FederatedResultCursor open(ConnectionFactory factory, String sql,
      Consumer<Connection> connectionMonitor, Consumer<Statement> statementMonitor,
      BooleanSupplier cancelled) throws SQLException {
    Connection c = factory.open();
    Statement s = null;
    FederatedResultCursor cursor = null;
    try {
      if (cancelled.getAsBoolean()) throw new CancellationException("Consulta cancelada");
      connectionMonitor.accept(c);
      s = c.createStatement(ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY);
      if (cancelled.getAsBoolean()) throw new CancellationException("Consulta cancelada");
      statementMonitor.accept(s);
      s.setMaxRows(0); s.setQueryTimeout(120);
      cursor = new FederatedResultCursor(c, s);
      cursor.rows = s.executeQuery(sql);
      if (cancelled.getAsBoolean()) throw new CancellationException("Consulta cancelada");
      return cursor;
    } catch (SQLException | RuntimeException ex) {
      if (cursor != null) cursor.close();
      else {
        if (s != null) try { s.close(); } catch (SQLException ignored) {}
        try { c.close(); } catch (SQLException ignored) {}
      }
      throw ex;
    }
  }

  boolean canRead(int page) { return !closed && page == nextPage; }
  Connection connection() { return connection; }
  Statement statement() { return statement; }

  synchronized DefaultTableModel read(int page, int size, BooleanSupplier cancelled) throws SQLException {
    if (!canRead(page) || size <= 0) throw new SQLException("Cursor indisponível para esta página.");
    if (expiry != null) expiry.cancel(false);
    try {
      DefaultTableModel model = SqlResultModels.readResultModel(rows, size, cancelled);
      nextPage++;
      if (model.getRowCount() < size) close();
      else expiry = REAPER.schedule(this::close, 5, TimeUnit.MINUTES);
      return model;
    } catch (SQLException | RuntimeException ex) { close(); throw ex; }
  }

  public synchronized void close() {
    if (closed) return;
    closed = true; OPEN.remove(this);
    if (expiry != null) expiry.cancel(false);
    if (rows != null) try { rows.close(); } catch (SQLException ignored) {}
    try { statement.close(); } catch (SQLException ignored) {}
    try { connection.close(); } catch (SQLException ignored) {}
  }

  // Nunca bloquear o EDT enquanto o driver encerra uma leitura remota.
  void closeAsync() { REAPER.execute(this::close); }
  static void closeAll() { for (FederatedResultCursor c : OPEN) c.closeAsync(); }
}
