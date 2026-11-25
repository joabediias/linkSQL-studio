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
  private static final java.util.logging.Logger LOG = java.util.logging.Logger.getLogger(DuckDbFederatedExecutor.class.getName());
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

  /**
   * Pushdown de IS NOT NULL: somente SELECT com uma tabela física, sem JOIN.
   * Uma tabela compartilhada por várias partes da consulta só recebe filtros
   * presentes em todas as suas ocorrências. O SQL local mantém o filtro original.
   */
  static Map<Table, Set<String>> nullFilters(String sql, Collection<String> aliases) throws SQLException {
    Map<String,String> sources = new HashMap<>();
    for (String alias : aliases) sources.put(alias.toUpperCase(Locale.ROOT), alias);
    Map<Table, Set<String>> common = new LinkedHashMap<>();
    try {
      SqlNode root = SqlParser.create(sql, SqlParser.config().withLex(Lex.MYSQL)).parseQuery();
      root.accept(new SqlBasicVisitor<Void>() {
        private Table table(SqlNode node) {
          if (node instanceof SqlCall c && c.getKind() == SqlKind.AS) return table(c.operand(0));
          if (!(node instanceof SqlIdentifier id) || id.names.size() != 2) return null;
          String source = sources.get(id.names.get(0).toUpperCase(Locale.ROOT));
          return source == null ? null : new Table(source, id.names.get(1));
        }
        private void merge(Table t, Set<String> filters) {
          common.merge(t, new HashSet<>(filters), (a,b) -> { a.retainAll(b); return a; });
        }
        private void visitFrom(SqlNode node) {
          if (node instanceof SqlJoin join) {
            visitFrom(join.getLeft()); visitFrom(join.getRight());
          } else {
            Table t = table(node);
            if (t != null) merge(t, Set.of());
          }
        }
        private void collect(SqlNode node, String alias, Set<String> filters) {
          if (!(node instanceof SqlCall c)) return;
          if (c.getKind() == SqlKind.AND) {
            for (SqlNode child : c.getOperandList()) collect(child, alias, filters);
          } else if (c.getKind() == SqlKind.IS_NOT_NULL && c.operand(0) instanceof SqlIdentifier id) {
            if (id.names.size() == 1 || (id.names.size() == 2 && id.names.get(0).equalsIgnoreCase(alias)))
              filters.add(id.names.get(id.names.size()-1).toUpperCase(Locale.ROOT));
          }
        }
        @Override public Void visit(SqlCall call) {
          if (call instanceof SqlSelect select) {
            SqlNode from = select.getFrom();
            Table t = table(from);
            if (t != null) {
              String alias = from instanceof SqlCall c && c.getKind() == SqlKind.AS
                  && c.operand(1) instanceof SqlIdentifier id ? id.getSimple() : t.name();
              Set<String> filters = new HashSet<>();
              collect(select.getWhere(), alias, filters);
              merge(t, filters);
            } else visitFrom(from);
          }
          return super.visit(call);
        }
      });
    } catch (Exception ex) { throw new SQLException("Falha ao analisar filtros DuckDB", ex); }
    for (Table a : common.keySet()) for (Table b : common.keySet())
      if (!a.equals(b) && a.source().equalsIgnoreCase(b.source()) && a.name().equalsIgnoreCase(b.name())) {
        common.get(a).clear(); common.get(b).clear();
      }
    return common;
  }

  private static String mysqlIdentifier(String name) { return "`" + name.replace("`", "``") + "`"; }

  static String remoteSql(Statement st, Table table, Projection projection) throws SQLException {
    String name = mysqlIdentifier(table.name());
    return remoteSql(st, table, projection, Set.of());
  }

  static String remoteSql(Statement st, Table table, Projection projection, Set<String> filters) throws SQLException {
    String name = mysqlIdentifier(table.name());
    if (projection.all() && filters.isEmpty()) return "SELECT * FROM " + name;
    List<String> selected = new ArrayList<>();
    String first = null;
    List<String> predicates = new ArrayList<>();
    try (ResultSet empty = st.executeQuery("SELECT * FROM " + name + " WHERE 1=0")) {
      ResultSetMetaData meta = empty.getMetaData();
      for (int i = 1; i <= meta.getColumnCount(); i++) {
        String column = meta.getColumnName(i);
        if (first == null) first = column;
        if (projection.includes(column)) selected.add(mysqlIdentifier(column));
        // Não delegar datas: o driver pode converter datas zeradas em NULL.
        int type = meta.getColumnType(i);
        if (filters.contains(column.toUpperCase(Locale.ROOT)) && type != Types.DATE
            && type != Types.TIMESTAMP && type != Types.TIME && type != Types.TIMESTAMP_WITH_TIMEZONE)
          predicates.add(mysqlIdentifier(column) + " IS NOT NULL");
      }
    }
    // COUNT(*) e SELECT constante precisam preservar a cardinalidade da origem.
    if (selected.isEmpty() && first != null) selected.add(mysqlIdentifier(first));
    String query = projection.all() || selected.isEmpty() ? "SELECT * FROM " + name
        : "SELECT " + String.join(", ", selected) + " FROM " + name;
    return predicates.isEmpty() ? query : query + " WHERE " + String.join(" AND ", predicates);
  }

  static DuckDbWorkspace prepare(String sql, SqlConnectionRegistry registry, Path parent,
      int memoryMb, int threads, BooleanSupplier cancelled) throws Exception {
    return prepare(sql, registry, parent, memoryMb, threads, cancelled, st -> {});
  }

  static DuckDbWorkspace prepare(String sql, SqlConnectionRegistry registry, Path parent,
      int memoryMb, int threads, BooleanSupplier cancelled, java.util.function.Consumer<Statement> active) throws Exception {
    return prepare(sql, registry, parent, memoryMb, threads, cancelled, active, message -> {});
  }

  static DuckDbWorkspace prepare(String sql, SqlConnectionRegistry registry, Path parent,
      int memoryMb, int threads, BooleanSupplier cancelled, java.util.function.Consumer<Statement> active,
      java.util.function.Consumer<String> progress) throws Exception {
    List<Table> tables = tables(sql, registry.mysqlSourceNames());
    Projection projection = projection(sql);
    Map<Table, Set<String>> filters = nullFilters(sql, registry.mysqlSourceNames());
    DuckDbWorkspace workspace = DuckDbWorkspace.open(parent, memoryMb, threads);
    try {
      int ordinal = 0;
      for (Table table : tables) {
        long started = System.nanoTime();
        int position = ++ordinal;
        String stage = "DuckDB: transferindo " + position + "/" + tables.size() + " — " + table.source() + "." + table.name();
        LOG.info(stage);
        progress.accept(stage);
        if (cancelled.getAsBoolean() || Thread.currentThread().isInterrupted())
          throw new java.util.concurrent.CancellationException("Consulta cancelada");
        try (Connection remote = new FederatedStreamingDataSource(registry.source(table.source()),
                 SqlDatabaseType.MYSQL).getConnection();
             Statement st = remote.createStatement(ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY)) {
          active.accept(st);
          if (cancelled.getAsBoolean()) throw new java.util.concurrent.CancellationException();
          long connected = System.nanoTime();
          try (ResultSet rows = st.executeQuery(remoteSql(st, table, projection, filters.getOrDefault(table, Set.of())))) {
            long ready = System.nanoTime();
            long count = DuckDbTableTransfer.copy(rows, workspace.connection(), table.source(), table.name(), cancelled);
            LOG.info(() -> String.format(java.util.Locale.ROOT,
                "DuckDB %s.%s: %d linhas | conexão %.2fs | consulta remota %.2fs | transferência %.2fs",
                table.source(), table.name(), count, (connected-started)/1e9,
                (ready-connected)/1e9, (System.nanoTime()-ready)/1e9));
          } finally { active.accept(null); }
        }
      }
      progress.accept("DuckDB: preparação concluída; executando SQL local");
      return workspace;
    } catch (Exception | Error ex) {
      try { workspace.close(); } catch (Exception close) { ex.addSuppressed(close); }
      throw ex;
    }
  }
}
