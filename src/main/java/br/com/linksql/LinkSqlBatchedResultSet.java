package br.com.linksql;

import java.lang.reflect.*;
import java.sql.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * JDBC ResultSet forward-only backed by bounded federated queries.
 * One chunk at a time; the consuming CSV tool sees a single ResultSet.
 * Explicitly opt-in via /*LINKSQL_LOTE:alias.column*\/ comments in SQL.
 */
public final class LinkSqlBatchedResultSet implements InvocationHandler {
  private static final Pattern DECLARATION = Pattern.compile(
      "/\\*\\s*LINKSQL_EXPORT:([A-Za-z_][A-Za-z_0-9]*)\\.([A-Za-z_][A-Za-z_0-9]*):([1-9][0-9]{0,3})\\s*\\*/");
  private final LinkSqlEmbeddedEngine engine;
  private final String template;
  private final Statement owner;
  private final List<String[]> ranges;
  private final int timeout;
  private final int fetchSize;
  private final int maxRows;
  private final AtomicBoolean cancelled = new AtomicBoolean(false);
  private Connection connection;
  private Statement statement;
  private ResultSet current;
  private ResultSetMetaData metadata;
  private int index;
  private long rowNumber;
  private long chunkRows;
  private long chunkStarted;
  private boolean validRow;
  private boolean closed;
  private ResultSet proxy;

  private LinkSqlBatchedResultSet(LinkSqlEmbeddedEngine engine, String sql, Statement owner,
                                  int timeout, int fetchSize, int maxRows) throws SQLException {
    this.engine = engine;
    this.template = sql;
    this.owner = owner;
    this.timeout = timeout;
    this.fetchSize = fetchSize;
    this.maxRows = maxRows;
    Matcher directive = DECLARATION.matcher(sql);
    String table = "CAD_CLI0", column = "CLI_CODIGO";
    int batchSize = 250;
    if (directive.find()) {
      table = directive.group(1);
      column = directive.group(2);
      batchSize = Integer.parseInt(directive.group(3));
    }
    try {
      ranges = JdbcBatchRanges.discover(engine.registryForBatches(), sql, table, column, batchSize);
    } catch (Exception ex) {
      throw new SQLException("Nao foi possivel localizar faixas de codigos: " + ex.getMessage(), "HY000", ex);
    }
    proxy = (ResultSet) Proxy.newProxyInstance(LinkSqlBatchedResultSet.class.getClassLoader(),
        new Class<?>[]{ResultSet.class}, this);
    JdbcBatchRanges.log("Iniciando cursor incremental com " + ranges.size() + " faixas; sem carregar CSV na memoria");
    // Metadata must be available before calling next(), including empty first chunks.
    try {
      openNextChunk();
    } catch (SQLException ex) {
      try { close(); } catch (SQLException close) { ex.addSuppressed(close); }
      throw ex;
    }
  }

  public static ResultSet execute(LinkSqlEmbeddedEngine engine, String sql, Statement owner,
                                  int timeout, int fetchSize, int maxRows) throws SQLException {
    if (!LargeCsvExport.isTemplate(sql))
      throw new SQLException("SQL sem marcadores de lote; a consulta nao sera executada inteira.", "HY000");
    return new LinkSqlBatchedResultSet(engine, sql, owner, timeout, fetchSize, maxRows).proxy;
  }

  /** Cancel without closing the result set on an unrelated thread. */
  public static void cancel(ResultSet rs) {
    if (rs != null && Proxy.isProxyClass(rs.getClass())) {
      InvocationHandler handler = Proxy.getInvocationHandler(rs);
      if (handler instanceof LinkSqlBatchedResultSet cursor) cursor.cancel();
    }
  }

  private void openNextChunk() throws SQLException {
    if (index >= ranges.size()) return;
    String[] bound = ranges.get(index++);
    chunkStarted = System.nanoTime();
    chunkRows = 0;
    JdbcBatchRanges.log("Lote " + index + "/" + ranges.size()
        + " [" + bound[0] + "-" + bound[1] + "]: planejando consulta...");
    String sql = LargeCsvExport.partition(template, bound[0], bound[1]);
    LinkSqlEmbeddedEngine.Query query;
    try {
      query = engine.plan(sql);
    } catch (SQLException | RuntimeException ex) {
      JdbcBatchRanges.log("ERRO no planejamento do lote " + index + ": " + ex.getMessage());
      throw ex;
    }
    JdbcBatchRanges.log("Lote " + index + ": plano pronto em "
        + ((System.nanoTime() - chunkStarted) / 1_000_000) + " ms; executando...");
    connection = query.connection();
    try {
      statement = connection.createStatement(ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY);
      if (timeout > 0) statement.setQueryTimeout(timeout);
      if (fetchSize > 0) statement.setFetchSize(fetchSize);
      current = statement.executeQuery(query.sql());
      if (metadata == null) metadata = current.getMetaData();
      JdbcBatchRanges.log("Lote " + index + ": resultado JDBC aberto em "
          + ((System.nanoTime() - chunkStarted) / 1_000_000) + " ms; lendo linhas...");
    } catch (SQLException | RuntimeException ex) {
      JdbcBatchRanges.log("ERRO no lote " + index + ": " + ex.getMessage());
      try { closeCurrent(); } catch (SQLException close) { ex.addSuppressed(close); }
      throw ex;
    }
  }

  private void closeCurrent() throws SQLException {
    SQLException failure = null;
    if (current != null) {
      try { current.close(); } catch (SQLException ex) { failure = ex; }
      current = null;
    }
    if (statement != null) {
      try { statement.close(); } catch (SQLException ex) {
        if (failure == null) failure = ex; else failure.addSuppressed(ex);
      }
      statement = null;
    }
    if (connection != null) {
      try { connection.close(); } catch (SQLException ex) {
        if (failure == null) failure = ex; else failure.addSuppressed(ex);
      }
      connection = null;
    }
    validRow = false;
    if (failure != null) throw failure;
  }

  public void cancel() {
    cancelled.set(true);
    Statement active = statement;
    if (active != null) {
      try { active.cancel(); } catch (SQLException ignored) {}
    }
  }

  public void close() throws SQLException {
    if (closed) return;
    closed = true;
    cancelled.set(true);
    closeCurrent();
  }

  private boolean next() throws SQLException {
    if (cancelled.get()) throw new SQLException("Exportacao JDBC cancelada", "57014");
    if (maxRows > 0 && rowNumber >= maxRows) { closeCurrent(); return false; }
    while (current != null || index < ranges.size()) {
      if (current == null) openNextChunk();
      if (current == null) return false;
      if (current.next()) {
        rowNumber++;
        chunkRows++;
        validRow = true;
        return true;
      }
      JdbcBatchRanges.log("Lote " + index + "/" + ranges.size()
          + " concluido: " + chunkRows + " linhas; " + ((System.nanoTime() - chunkStarted) / 1_000_000)
          + " ms; total enviado=" + rowNumber);
      closeCurrent();
    }
    JdbcBatchRanges.log("Exportacao JDBC finalizada: " + rowNumber + " linhas entregues.");
    validRow = false;
    return false;
  }

  @Override public Object invoke(Object object, Method method, Object[] args) throws Throwable {
    String name = method.getName();
    if (name.equals("toString")) return "LinkSQL JDBC ResultSet incremental";
    if (name.equals("hashCode")) return System.identityHashCode(object);
    if (name.equals("equals")) return object == args[0];
    switch (name) {
      case "isClosed": return closed;
      case "close": close(); return null;
      case "unwrap": {
        Class<?> type = (Class<?>) args[0];
        if (type.isInstance(object)) return object;
        throw new SQLException("Interface nao implementada: " + type.getName());
      }
      case "isWrapperFor": return ((Class<?>) args[0]).isInstance(object);
    }
    if (closed) throw new SQLException("ResultSet fechado", "24000");
    switch (name) {
      case "next": return next();
      case "getMetaData": return metadata;
      case "getStatement": return owner;
      case "getType": return ResultSet.TYPE_FORWARD_ONLY;
      case "getConcurrency": return ResultSet.CONCUR_READ_ONLY;
      case "getHoldability": return ResultSet.CLOSE_CURSORS_AT_COMMIT;
      case "getFetchDirection": return ResultSet.FETCH_FORWARD;
      case "getRow": return rowNumber > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) rowNumber;
      case "isBeforeFirst": return rowNumber == 0;
      case "isAfterLast": return current == null && index >= ranges.size();
      case "isFirst": return rowNumber == 1 && validRow;
      case "isLast": return false;
      case "rowUpdated", "rowInserted", "rowDeleted": return false;
    }
    if (current == null) throw new SQLException("Nenhum registro JDBC selecionado", "24000");
    try {
      return method.invoke(current, args);
    } catch (InvocationTargetException ex) {
      throw ex.getCause();
    }
  }
}
