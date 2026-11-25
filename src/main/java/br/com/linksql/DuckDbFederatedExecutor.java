package br.com.linksql;

import java.nio.file.Path;
import java.sql.*;
import java.util.*;
import java.util.function.BooleanSupplier;
import org.apache.calcite.sql.*;
import org.apache.calcite.sql.parser.SqlParser;
import org.apache.calcite.sql.util.SqlBasicVisitor;
import org.apache.calcite.config.Lex;

/** Experimental: materializa tabelas qualificadas de origens MySQL em disco. */
final class DuckDbFederatedExecutor {
  private DuckDbFederatedExecutor() {}
  record Table(String source, String name) {}

  static List<Table> tables(String sql, Collection<String> aliases) throws SQLException {
    ReadOnlySqlGuard.requireReadOnlySelect(sql);
    Map<String,String> names = new HashMap<>();
    for (String alias : aliases) names.put(alias.toUpperCase(Locale.ROOT), alias);
    Set<Table> tables = new LinkedHashSet<>();
    try {
      SqlNode root = SqlParser.create(sql, SqlParser.config().withLex(Lex.MYSQL)).parseQuery();
      root.accept(new SqlBasicVisitor<Void>() {
        private void from(SqlNode node) {
          if (node == null) return;
          if (node instanceof SqlIdentifier id) {
            if (id.names.size() == 1) return; // CTE; DuckDB valida referências locais.
            if (id.names.size() != 2) throw new IllegalArgumentException("Use CONEXAO.tabela no modo experimental.");
            String source = names.get(id.names.get(0).toUpperCase(Locale.ROOT));
            if (source == null) throw new IllegalArgumentException("Origem desconhecida: " + id.names.get(0));
            tables.add(new Table(source, id.names.get(1)));
          } else if (node instanceof SqlJoin join) {
            from(join.getLeft()); from(join.getRight());
          } else if (node instanceof SqlCall call && call.getKind() == SqlKind.AS) {
            from(call.operand(0));
          } else if (node instanceof SqlCall call && call.getKind().belongsTo(SqlKind.QUERY)) {
            // A visita recursiva normal encontra os SELECTs.
          } else throw new IllegalArgumentException("Expressão FROM não suportada no modo experimental.");
        }
        @Override public Void visit(SqlCall call) {
          if (call instanceof SqlSelect select) from(select.getFrom());
          return super.visit(call);
        }
      });
    } catch (Exception ex) {
      throw new SQLException("Consulta não suportada pelo modo DuckDB experimental: " + ex.getMessage(), ex);
    }
    if (tables.isEmpty()) throw new SQLException("Informe pelo menos uma tabela qualificada.");
    return List.copyOf(tables);
  }

  /** Conservador: qualquer identificador de coluna pode pertencer a qualquer origem.
   * Estrelas em SELECTs sobre CTEs não exigem ler todas as colunas físicas.
   * NATURAL JOIN e estrelas físicas desativam a redução para evitar alterar semântica.
   */
  record Projection(Set<String> names, boolean all) {
    boolean includes(String column) { return all || names.contains(column.toUpperCase(Locale.ROOT)); }
  }

  static Projection projection(String sql) throws SQLException {
    Set<String> columns = new HashSet<>();
    boolean[] all = {false};
    try {
      SqlNode root = SqlParser.create(sql, SqlParser.config().withLex(Lex.MYSQL)).parseQuery();
      root.accept(new SqlBasicVisitor<Void>() {
        private boolean physical(SqlNode node) {
          if (node instanceof SqlIdentifier id) return id.names.size() > 1;
          if (node instanceof SqlJoin join) return physical(join.getLeft()) || physical(join.getRight());
          if (node instanceof SqlCall call && call.getKind() == SqlKind.AS) return physical(call.operand(0));
          return false;
        }
        @Override public Void visit(SqlIdentifier id) {
          if (!id.isStar()) columns.add(id.names.get(id.names.size() - 1).toUpperCase(Locale.ROOT));
          return null;
        }
        @Override public Void visit(SqlCall call) {
          if (call instanceof SqlJoin join && join.isNatural()) all[0] = true;
          if (call instanceof SqlSelect select && physical(select.getFrom())) {
            for (SqlNode item : select.getSelectList()) {
              if (item instanceof SqlIdentifier id && id.isStar()) all[0] = true;
            }
          }
          return super.visit(call);
        }
      });
    } catch (Exception ex) { throw new SQLException("Falha ao analisar projeção DuckDB", ex); }
    return new Projection(Set.copyOf(columns), all[0]);
  }

  private static String mysqlIdentifier(String name) { return "`" + name.replace("`", "``") + "`"; }

  static String remoteSql(Statement st, Table table, Projection projection) throws SQLException {
    String name = mysqlIdentifier(table.name());
    if (projection.all()) return "SELECT * FROM " + name;
    List<String> selected = new ArrayList<>();
    String first = null;
    try (ResultSet empty = st.executeQuery("SELECT * FROM " + name + " WHERE 1=0")) {
      ResultSetMetaData meta = empty.getMetaData();
      for (int i = 1; i <= meta.getColumnCount(); i++) {
        String column = meta.getColumnName(i);
        if (first == null) first = column;
        if (projection.includes(column)) selected.add(mysqlIdentifier(column));
      }
    }
    // COUNT(*) e SELECT constante precisam preservar a cardinalidade da origem.
    if (selected.isEmpty() && first != null) selected.add(mysqlIdentifier(first));
    return selected.isEmpty() ? "SELECT * FROM " + name
        : "SELECT " + String.join(", ", selected) + " FROM " + name;
  }

  static DuckDbWorkspace prepare(String sql, SqlConnectionRegistry registry, Path parent,
      int memoryMb, int threads, BooleanSupplier cancelled) throws Exception {
    List<Table> tables = tables(sql, registry.mysqlSourceNames());
    Projection projection = projection(sql);
    DuckDbWorkspace workspace = DuckDbWorkspace.open(parent, memoryMb, threads);
    try {
      for (Table table : tables) {
        if (cancelled.getAsBoolean() || Thread.currentThread().isInterrupted())
          throw new java.util.concurrent.CancellationException("Consulta cancelada");
        try (Connection remote = new FederatedStreamingDataSource(registry.source(table.source()),
                 SqlDatabaseType.MYSQL).getConnection();
             Statement st = remote.createStatement(ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY)) {
          try (ResultSet rows = st.executeQuery(remoteSql(st, table, projection))) {
            DuckDbTableTransfer.copy(rows, workspace.connection(), table.source(), table.name(), cancelled);
          }
        }
      }
      return workspace;
    } catch (Exception | Error ex) {
      try { workspace.close(); } catch (Exception close) { ex.addSuppressed(close); }
      throw ex;
    }
  }
}
