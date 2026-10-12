package br.com.linksql.jdbc;

import br.com.linksql.LinkSqlEmbeddedEngine;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

final class EmbeddedConnection implements InvocationHandler {
  private final LinkSqlEmbeddedEngine engine;
  private final List<EmbeddedStatement> statements = new ArrayList<>();
  private boolean closed;
  private Connection proxy;

  private EmbeddedConnection(LinkSqlEmbeddedEngine engine) { this.engine = engine; }

  static Connection open(Path config) throws SQLException {
    LinkSqlEmbeddedEngine engine = new LinkSqlEmbeddedEngine(config);
    EmbeddedConnection handler = new EmbeddedConnection(engine);
    Connection result = (Connection) Proxy.newProxyInstance(
        LinkSqlDriver.class.getClassLoader(), new Class<?>[]{Connection.class}, handler);
    handler.proxy = result;
    return result;
  }

  private void ensureOpen() throws SQLException {
    if (closed) throw new SQLException("Conexao LinkSQL fechada", "08003");
  }

  @Override public Object invoke(Object p, Method m, Object[] args) throws Throwable {
    String name = m.getName();
    if (name.equals("toString")) return "LinkSQL JDBC Embedded (somente leitura)";
    if (name.equals("hashCode")) return System.identityHashCode(p);
    if (name.equals("equals")) return p == args[0];
    if (name.equals("isClosed")) return closed;
    if (name.equals("close")) {
      if (!closed) {
        closed = true;
        for (EmbeddedStatement st : List.copyOf(statements)) st.close();
        engine.close();
      }
      return null;
    }
    ensureOpen();
    switch (name) {
      case "createStatement": {
        EmbeddedStatement st = new EmbeddedStatement(engine, proxy, args);
        statements.add(st);
        return st.proxy();
      }
      case "setReadOnly": {
        if (!(Boolean) args[0]) throw new SQLException("LinkSQL JDBC POC e exclusivamente somente leitura", "25006");
        return null;
      }
      case "isReadOnly": return true;
      case "setAutoCommit": {
        if (!(Boolean) args[0]) throw new SQLFeatureNotSupportedException("Transacoes nao suportadas nesta POC");
        return null;
      }
      case "getAutoCommit": return true;
      case "commit", "rollback": throw new SQLFeatureNotSupportedException("Transacoes nao suportadas nesta POC");
      case "getTransactionIsolation": return Connection.TRANSACTION_NONE;
      case "setTransactionIsolation": throw new SQLFeatureNotSupportedException("Transacoes nao suportadas nesta POC");
      case "getWarnings": return null;
      case "clearWarnings": return null;
      case "nativeSQL": return args[0];
      case "getCatalog", "getSchema": return null;
      case "getMetaData": return engine.metadataConnection().getMetaData();
      case "setCatalog", "setSchema": return null;
      case "isValid": return true;
      case "getHoldability": return java.sql.ResultSet.CLOSE_CURSORS_AT_COMMIT;
      case "getNetworkTimeout": return 0;
      case "getTypeMap": return java.util.Collections.emptyMap();
      case "getClientInfo": return args == null || args.length == 0 ? new java.util.Properties() : null;
      case "unwrap": {
        Class<?> requested = (Class<?>) args[0];
        if (requested.isInstance(p)) return p;
        throw new SQLException("Nao implementa " + requested.getName());
      }
      case "isWrapperFor": return ((Class<?>) args[0]).isInstance(p);
      case "setClientInfo": return null;
      default: throw new SQLFeatureNotSupportedException("Recurso JDBC nao implementado na POC: Connection." + name);
    }
  }
}
