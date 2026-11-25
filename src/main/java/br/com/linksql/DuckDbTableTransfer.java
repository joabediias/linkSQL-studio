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
    if (target instanceof org.duckdb.DuckDBConnection duck && appenderCompatible(meta)) {
      boolean cast = false;
      for (int i=1; i<=width; i++) if (requiresCast(meta.getColumnType(i))) cast = true;
      if (!cast) return append(source, duck, schema, table, meta, cancelled);
      // Área intermediária textual para tipos exatos que o Appender 1.3.2 não aceita.
      // Converte em SQL tipado, sem arredondamento por double.
      String staging = "_linksql_stage_" + java.util.UUID.randomUUID().toString().replace("-", "");
      String stageName = ident(schema) + "." + ident(staging);
      var stageDdl = new java.util.StringJoiner(", ");
      var expressions = new java.util.StringJoiner(", ");
      for (int i=1; i<=width; i++) {
        String column = ident(meta.getColumnLabel(i));
        String sqlType = type(meta, i);
        boolean convert = requiresCast(meta.getColumnType(i));
        stageDdl.add(column + " " + (convert ? "VARCHAR" : sqlType));
        expressions.add(convert ? "CAST(" + column + " AS " + sqlType + ")" : column);
      }
      try (Statement st = target.createStatement()) {
        st.execute("CREATE TABLE " + stageName + " (" + stageDdl + ")");
        long rows = append(source, duck, schema, staging, meta, cancelled);
        if (cancelled.getAsBoolean() || Thread.currentThread().isInterrupted())
          throw new java.util.concurrent.CancellationException("Transferência cancelada");
        st.executeUpdate("INSERT INTO " + name + " SELECT " + expressions + " FROM " + stageName);
        st.execute("DROP TABLE " + stageName);
        return rows;
      }
    }
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

  private static boolean requiresCast(int type) {
    return type == Types.DECIMAL || type == Types.NUMERIC || type == Types.DATE || type == Types.TIME || type == Types.TIMESTAMP;
  }

  // Binários mantêm o caminho JDBC original até validar a equivalência.
  private static boolean appenderCompatible(ResultSetMetaData meta) throws SQLException {
    if (Boolean.getBoolean("linksql.duckdb.disableAppender")) return false;
    for (int i=1; i<=meta.getColumnCount(); i++) {
      switch (meta.getColumnType(i)) {
        case Types.TINYINT, Types.SMALLINT, Types.INTEGER, Types.BIGINT,
             Types.FLOAT, Types.REAL, Types.DOUBLE, Types.DECIMAL, Types.NUMERIC,
             Types.DATE, Types.TIME, Types.TIMESTAMP,
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
              // Tipos não suportados pelo Appender são tratados pelo caminho JDBC.
              String value = source.getString(i);
              if (value == null) out.appendNull();
              else switch (meta.getColumnType(i)) {
                case Types.TINYINT, Types.SMALLINT, Types.INTEGER -> out.append(source.getInt(i));
                case Types.BIGINT -> out.append(source.getLong(i));
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
