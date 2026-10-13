package br.com.linksql;

import javax.sql.DataSource;
import java.io.PrintWriter;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.*;
import java.util.logging.Logger;

/** Configura somente conexões emprestadas ao Calcite, sem mudar o executor nativo. */
final class FederatedStreamingDataSource implements DataSource {
  private final DataSource source;
  private final SqlDatabaseType type;
  FederatedStreamingDataSource(DataSource source, SqlDatabaseType type) { this.source = source; this.type = type; }

  private Connection stream(Connection connection) throws SQLException {
    try {
      // pgJDBC usa cursor com fetch positivo somente dentro de uma transação.
      // O pool desfaz a transação de leitura ao devolver a conexão.
      if (type == SqlDatabaseType.POSTGRESQL) {
        connection.setReadOnly(true); connection.setAutoCommit(false);
      }
      return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(), new Class<?>[]{Connection.class},
          (proxy, method, args) -> {
            try {
              Object value = method.invoke(connection, args);
              if (value instanceof Statement statement && (method.getName().equals("createStatement")
                  || method.getName().equals("prepareStatement"))) {
                try {
                  int fetch = 1000;
                  if (type == SqlDatabaseType.MYSQL) {
                    boolean cursor = connection.isWrapperFor(com.mysql.cj.jdbc.JdbcConnection.class)
                        && connection.unwrap(com.mysql.cj.jdbc.JdbcConnection.class).getPropertySet()
                            .getBooleanProperty(com.mysql.cj.conf.PropertyKey.useCursorFetch).getValue();
                    fetch = cursor ? 1000 : Integer.MIN_VALUE;
                  }
                  statement.setFetchSize(fetch);
                  statement.setQueryTimeout(120);
                } catch (SQLException ex) { try { statement.close(); } catch (SQLException ignored) {} throw ex; }
              }
              return value;
            } catch (InvocationTargetException ex) { throw ex.getCause(); }
          });
    } catch (SQLException | RuntimeException ex) {
      try { connection.close(); } catch (SQLException ignored) {}
      throw ex;
    }
  }
  public Connection getConnection() throws SQLException { return stream(source.getConnection()); }
  public Connection getConnection(String user, String password) throws SQLException { return stream(source.getConnection(user, password)); }
  public PrintWriter getLogWriter() throws SQLException { return source.getLogWriter(); }
  public void setLogWriter(PrintWriter writer) throws SQLException { source.setLogWriter(writer); }
  public void setLoginTimeout(int seconds) throws SQLException { source.setLoginTimeout(seconds); }
  public int getLoginTimeout() throws SQLException { return source.getLoginTimeout(); }
  public Logger getParentLogger() throws SQLFeatureNotSupportedException { return source.getParentLogger(); }
  public <T> T unwrap(Class<T> cls) throws SQLException { return cls.isInstance(this) ? cls.cast(this) : source.unwrap(cls); }
  public boolean isWrapperFor(Class<?> cls) throws SQLException { return cls.isInstance(this) || source.isWrapperFor(cls); }
}
