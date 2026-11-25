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

  static DuckDbWorkspace prepare(String sql, SqlConnectionRegistry registry, Path parent,
      int memoryMb, int threads, BooleanSupplier cancelled) throws Exception {
    List<Table> tables = tables(sql, registry.mysqlSourceNames());
    DuckDbWorkspace workspace = DuckDbWorkspace.open(parent, memoryMb, threads);
    try {
      for (Table table : tables) {
        if (cancelled.getAsBoolean() || Thread.currentThread().isInterrupted())
          throw new java.util.concurrent.CancellationException("Consulta cancelada");
        try (Connection remote = new FederatedStreamingDataSource(registry.source(table.source()),
                 SqlDatabaseType.MYSQL).getConnection();
             Statement st = remote.createStatement(ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY)) {
          String identifier = "`" + table.name().replace("`", "``") + "`";
          try (ResultSet rows = st.executeQuery("SELECT * FROM " + identifier)) {
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
