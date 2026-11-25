package br.com.linksql;

import java.sql.*;
import java.util.function.BooleanSupplier;

/** Transfere um resultado JDBC para uma tabela local sem acumular todas as linhas. */
final class DuckDbTableTransfer {
  private DuckDbTableTransfer() {}

  static long copy(ResultSet source, Connection target, String schema, String table,
      BooleanSupplier cancelled) throws SQLException {
    var meta = source.getMetaData();
    int width = meta.getColumnCount();
    String name = ident(schema) + "." + ident(table);
    StringBuilder ddl = new StringBuilder("CREATE TABLE ").append(name).append(" (");
    StringBuilder insert = new StringBuilder("INSERT INTO ").append(name).append(" VALUES (");
    for (int i = 1; i <= width; i++) {
      if (i > 1) { ddl.append(", "); insert.append(", "); }
      ddl.append(ident(meta.getColumnLabel(i))).append(" ").append(type(meta, i));
      insert.append("?");
    }
    try (Statement stmt = target.createStatement()) {
      stmt.execute("CREATE SCHEMA IF NOT EXISTS " + ident(schema));
      stmt.execute(ddl.append(")").toString());
    }
    if (target instanceof org.duckdb.DuckDBConnection duck && appenderCompatible(meta))
      return append(source, duck, schema, table, meta, cancelled);
    long count = 0;
    boolean auto = target.getAutoCommit();
    if (!auto) throw new SQLException("A transferência requer conexão local exclusiva.");
    target.setAutoCommit(false);
    try (PreparedStatement out = target.prepareStatement(insert.append(")").toString())) {
      while (!cancelled.getAsBoolean() && !Thread.currentThread().isInterrupted() && source.next()) {
        for (int i = 1; i <= width; i++) {
          switch (meta.getColumnType(i)) {
            case Types.BINARY, Types.VARBINARY, Types.LONGVARBINARY, Types.BLOB -> out.setBytes(i, source.getBytes(i));
            case Types.CHAR, Types.VARCHAR, Types.LONGVARCHAR, Types.NCHAR, Types.NVARCHAR,
                 Types.LONGNVARCHAR, Types.CLOB, Types.NCLOB -> out.setString(i, source.getString(i));
            case Types.DATE, Types.TIME, Types.TIMESTAMP -> out.setString(i, source.getString(i));
            default -> out.setObject(i, source.getObject(i));
          }
        }
        out.addBatch();
        if (++count % 256 == 0) {
          out.executeBatch();
          out.clearBatch();
          // O workspace é descartável e só fica visível após preparar todas as tabelas.
          // Evita manter uma transação de centenas de milhares de linhas em memória.
          if (count % 8192 == 0) target.commit();
        }
      }
      if (cancelled.getAsBoolean() || Thread.currentThread().isInterrupted())
        throw new java.util.concurrent.CancellationException("Transferência cancelada");
      if (count % 256 != 0) out.executeBatch();
      target.commit();
      return count;
    } catch (SQLException | RuntimeException ex) {
      try { target.rollback(); } catch (SQLException rollback) { ex.addSuppressed(rollback); }
      throw ex;
    } finally { target.setAutoCommit(true); }
  }

  // Datas e binários mantêm o caminho JDBC original até validar a equivalência.
  private static boolean appenderCompatible(ResultSetMetaData meta) throws SQLException {
    if (Boolean.getBoolean("linksql.duckdb.disableAppender")) return false;
    for (int i=1; i<=meta.getColumnCount(); i++) {
      switch (meta.getColumnType(i)) {
        case Types.TINYINT, Types.SMALLINT, Types.INTEGER, Types.BIGINT,
             Types.DECIMAL, Types.NUMERIC, Types.FLOAT, Types.REAL, Types.DOUBLE,
             Types.BOOLEAN, Types.BIT, Types.CHAR, Types.VARCHAR, Types.LONGVARCHAR,
             Types.NCHAR, Types.NVARCHAR, Types.LONGNVARCHAR, Types.CLOB, Types.NCLOB -> {}
        default -> { return false; }
      }
    }
    return true;
  }

  private static long append(ResultSet source, org.duckdb.DuckDBConnection target,
      String schema, String table, ResultSetMetaData meta, BooleanSupplier cancelled) throws SQLException {
    long count = 0;
    if (!target.getAutoCommit()) throw new SQLException("A transferência requer conexão local exclusiva.");
    target.setAutoCommit(false);
    try {
      boolean more = true;
      while (more) {
        try (var out = target.createAppender(schema, table)) {
          for (int row=0; row<8192; row++) {
            if (cancelled.getAsBoolean() || Thread.currentThread().isInterrupted())
              throw new java.util.concurrent.CancellationException("Transferência cancelada");
            if (!source.next()) { more = false; break; }
            out.beginRow();
            for (int i=1; i<=meta.getColumnCount(); i++) {
              // DECIMAL usa BigDecimal nativo para manter precisão sem passar por double.
              String value = source.getString(i);
              if (value == null) out.appendNull();
              else switch (meta.getColumnType(i)) {
                case Types.TINYINT, Types.SMALLINT, Types.INTEGER -> out.append(source.getInt(i));
                case Types.BIGINT -> out.append(source.getLong(i));
                case Types.DECIMAL, Types.NUMERIC -> out.appendBigDecimal(source.getBigDecimal(i));
                case Types.FLOAT, Types.REAL, Types.DOUBLE -> out.append(source.getDouble(i));
                case Types.BOOLEAN, Types.BIT -> out.append(source.getBoolean(i));
                default -> out.append(value);
              }
            }
            out.endRow();
            count++;
          }
        }
        target.commit();
      }
      return count;
    } catch (SQLException | RuntimeException ex) {
      try { target.rollback(); } catch (SQLException rollback) { ex.addSuppressed(rollback); }
      throw ex;
    } finally { target.setAutoCommit(true); }
  }

  static String ident(String value) {
    return "\"" + value.replace("\"", "\"\"") + "\"";
  }

  private static String type(ResultSetMetaData m, int i) throws SQLException {
    return switch (m.getColumnType(i)) {
      case Types.TINYINT, Types.SMALLINT, Types.INTEGER -> "INTEGER";
      case Types.BIGINT -> "BIGINT";
      case Types.DECIMAL, Types.NUMERIC -> {
        int p = m.getPrecision(i), s = m.getScale(i);
        if (p < 1 || p > 38 || s < 0 || s > p)
          throw new SQLException("Precisão decimal não suportada: " + p + "," + s);
        yield "DECIMAL(" + p + "," + s + ")";
      }
      case Types.FLOAT, Types.REAL, Types.DOUBLE -> "DOUBLE";
      case Types.BOOLEAN, Types.BIT -> "BOOLEAN";
      case Types.DATE -> "DATE";
      case Types.TIME -> "TIME";
      case Types.TIMESTAMP -> "TIMESTAMP";
      case Types.BINARY, Types.VARBINARY, Types.LONGVARBINARY, Types.BLOB -> "BLOB";
      case Types.CHAR, Types.VARCHAR, Types.LONGVARCHAR, Types.NCHAR, Types.NVARCHAR,
           Types.LONGNVARCHAR, Types.CLOB, Types.NCLOB -> "VARCHAR";
      default -> throw new SQLException("Tipo JDBC não suportado no modo experimental: " + m.getColumnTypeName(i));
    };
  }
}
