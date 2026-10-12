package br.com.linksql.jdbc;

import br.com.linksql.LinkSqlEmbeddedEngine;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.sql.Statement;

final class EmbeddedStatement implements InvocationHandler {
  private final LinkSqlEmbeddedEngine engine;
  private final Connection connection;
  private final Object[] createArgs;
  private Statement proxy;
  private Connection activeConnection;
  private Statement activeStatement;
  private int fetchSize = 500;
  private int timeout = 120;
  private int maxRows = 0;
  private boolean closed;

  EmbeddedStatement(LinkSqlEmbeddedEngine engine, Connection connection, Object[] args) {
    this.engine = engine;
    this.connection = connection;
    this.createArgs = args;
    proxy = (Statement) Proxy.newProxyInstance(LinkSqlDriver.class.getClassLoader(), new Class<?>[]{Statement.class}, this);
  }

  Statement proxy() { return proxy; }

  void close() throws SQLException {
    if (!closed) {
      closed = true;
      closeActive();
    }
  }

  private void closeActive() throws SQLException {
    SQLException failure = null;
    if (activeStatement != null) {
      try { activeStatement.close(); } catch (SQLException ex) { failure = ex; }
      activeStatement = null;
    }
    if (activeConnection != null) {
      try { activeConnection.close(); } catch (SQLException ex) { if (failure == null) failure = ex; else failure.addSuppressed(ex); }
      activeConnection = null;
    }
    if (failure != null) throw failure;
  }

  private void ensureOpen() throws SQLException {
    if (closed) throw new SQLException("Statement LinkSQL fechado", "07000");
  }

  private ResultSet execute(String sql) throws SQLException {
    ensureOpen();
    closeActive();
    LinkSqlEmbeddedEngine.Query query = engine.plan(sql);
    activeConnection = query.connection();
    try {
      // Calcite nem sempre aceita setReadOnly, mas os comandos sao validados.
      try { activeConnection.setReadOnly(true); } catch (SQLException ignored) {}
      if (createArgs != null && createArgs.length >= 2) {
        activeStatement = activeConnection.createStatement((Integer) createArgs[0], (Integer) createArgs[1]);
      } else activeStatement = activeConnection.createStatement();
      activeStatement.setQueryTimeout(timeout);
      if (fetchSize > 0) activeStatement.setFetchSize(fetchSize);
      if (maxRows > 0) activeStatement.setMaxRows(maxRows);
      return activeStatement.executeQuery(query.sql());
    } catch (SQLException | RuntimeException ex) {
      try { closeActive(); } catch (SQLException close) { ex.addSuppressed(close); }
      throw ex;
    }
  }

  @Override public Object invoke(Object p, Method m, Object[] args) throws Throwable {
    String name = m.getName();
    if (name.equals("toString")) return "LinkSQL Embedded Statement";
    if (name.equals("hashCode")) return System.identityHashCode(p);
    if (name.equals("equals")) return p == args[0];
    if (name.equals("close")) { close(); return null; }
    if (name.equals("isClosed")) return closed;
    ensureOpen();
    switch (name) {
      case "executeQuery": return execute((String) args[0]);
      case "execute": { execute((String) args[0]); return true; }
      case "getResultSet": return activeStatement == null ? null : activeStatement.getResultSet();
      case "getMoreResults": return false;
      case "getUpdateCount": return -1;
      case "getLargeUpdateCount": return -1L;
      case "getConnection": return connection;
      case "setFetchSize": fetchSize = (Integer) args[0]; return null;
      case "getFetchSize": return fetchSize;
      case "setQueryTimeout": timeout = (Integer) args[0]; return null;
      case "getQueryTimeout": return timeout;
      case "setMaxRows": maxRows = (Integer) args[0]; return null;
      case "getMaxRows": return maxRows;
      case "getResultSetType": return ResultSet.TYPE_FORWARD_ONLY;
      case "getResultSetConcurrency": return ResultSet.CONCUR_READ_ONLY;
      case "getResultSetHoldability": return ResultSet.CLOSE_CURSORS_AT_COMMIT;
      case "getFetchDirection": return ResultSet.FETCH_FORWARD;
      case "setFetchDirection": if ((Integer) args[0] != ResultSet.FETCH_FORWARD) throw new SQLFeatureNotSupportedException(); return null;
      case "getWarnings": return activeStatement == null ? null : activeStatement.getWarnings();
      case "clearWarnings": if (activeStatement != null) activeStatement.clearWarnings(); return null;
      case "cancel": if (activeStatement != null) activeStatement.cancel(); return null;
      case "closeOnCompletion": if (activeStatement != null) activeStatement.closeOnCompletion(); return null;
      case "isCloseOnCompletion": return activeStatement != null && activeStatement.isCloseOnCompletion();
      case "unwrap": {
        Class<?> requested = (Class<?>) args[0];
        if (requested.isInstance(p)) return p;
        throw new SQLException("Nao implementa " + requested.getName());
      }
      case "isWrapperFor": return ((Class<?>) args[0]).isInstance(p);
      case "executeUpdate", "executeLargeUpdate", "addBatch", "executeBatch", "executeLargeBatch":
        throw new SQLException("LinkSQL JDBC POC aceita somente consultas SELECT", "25006");
      default: throw new SQLFeatureNotSupportedException("Recurso JDBC nao implementado na POC: Statement." + name);
    }
  }
}
