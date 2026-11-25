package br.com.linksql;

import java.nio.file.Files;
import java.nio.charset.StandardCharsets;
import java.sql.DriverManager;

/** Teste executável sem infraestrutura externa. */
public final class DuckDbWorkspaceRegressionTest {
  public static void main(String[] args) throws Exception {
    var projection = DuckDbFederatedExecutor.projection(
        "WITH B AS (SELECT C.CLI_CODIGO, C.CLI_LIMITE FROM SRV_MATRIZ.CAD_CLI0 C WHERE C.SQL_DELETED = 'F') SELECT B.* FROM B");
    if (projection.all() || !projection.includes("CLI_CODIGO") || !projection.includes("SQL_DELETED")
        || projection.includes("CLI_CODPAG")) throw new AssertionError("Projeção incorreta");
    if (!DuckDbFederatedExecutor.projection("SELECT C.* FROM SRV_MATRIZ.CAD_CLI0 C").all())
      throw new AssertionError("Wildcard físico não preservado");
    if (!DuckDbFederatedExecutor.projection("SELECT A.ID FROM A.T A NATURAL JOIN B.T B").all())
      throw new AssertionError("NATURAL JOIN não preservado");
    var aliases = java.util.List.of("SRV_MATRIZ");
    var physical = new DuckDbFederatedExecutor.Table("SRV_MATRIZ", "CAD_CLI0");
    var common = DuckDbFederatedExecutor.nullFilters(
        "SELECT C.ID FROM SRV_MATRIZ.CAD_CLI0 C WHERE C.ID IS NOT NULL UNION ALL SELECT D.ID FROM SRV_MATRIZ.CAD_CLI0 D WHERE D.ID IS NOT NULL AND D.X IS NOT NULL", aliases);
    if (!java.util.Set.of("ID").equals(common.get(physical)))
      throw new AssertionError("Interseção de filtros incorreta");
    var mixed = DuckDbFederatedExecutor.nullFilters(
        "SELECT ID FROM SRV_MATRIZ.CAD_CLI0 WHERE ID IS NOT NULL UNION ALL SELECT ID FROM SRV_MATRIZ.CAD_CLI0", aliases);
    if (!mixed.get(physical).isEmpty()) throw new AssertionError("Filtro indevido em tabela compartilhada");
    var disjunction = DuckDbFederatedExecutor.nullFilters(
        "SELECT ID FROM SRV_MATRIZ.CAD_CLI0 WHERE ID IS NOT NULL OR X IS NOT NULL", aliases);
    if (!disjunction.get(physical).isEmpty()) throw new AssertionError("OR não deve ser delegado");
    var joined = DuckDbFederatedExecutor.nullFilters(
        "SELECT C.ID FROM SRV_MATRIZ.CAD_CLI0 C LEFT JOIN SRV_MATRIZ.OUTRA D ON C.ID = D.ID WHERE C.ID IS NOT NULL", aliases);
    var other = new DuckDbFederatedExecutor.Table("SRV_MATRIZ", "OUTRA");
    if (!java.util.Set.of("ID").equals(joined.get(physical)) || !joined.get(other).isEmpty())
      throw new AssertionError("Filtro no lado preservado do LEFT JOIN incorreto");
    var nullable = DuckDbFederatedExecutor.nullFilters(
        "SELECT C.ID FROM SRV_MATRIZ.CAD_CLI0 C LEFT JOIN SRV_MATRIZ.OUTRA D ON C.ID = D.ID WHERE D.ID IS NOT NULL", aliases);
    if (!nullable.get(physical).isEmpty() || !nullable.get(other).isEmpty())
      throw new AssertionError("Filtro indevido no lado não preservado");
    var inner = DuckDbFederatedExecutor.nullFilters(
        "SELECT C.ID FROM SRV_MATRIZ.CAD_CLI0 C INNER JOIN SRV_MATRIZ.OUTRA D ON C.ID = D.ID WHERE C.ID IS NOT NULL AND D.ID IS NOT NULL", aliases);
    if (!java.util.Set.of("ID").equals(inner.get(physical)) || !java.util.Set.of("ID").equals(inner.get(other)))
      throw new AssertionError("Filtros INNER JOIN incorretos");
    var shared = DuckDbFederatedExecutor.nullFilters(
        "SELECT C.ID FROM SRV_MATRIZ.CAD_CLI0 C LEFT JOIN SRV_MATRIZ.OUTRA D ON C.ID = D.ID WHERE C.ID IS NOT NULL UNION ALL SELECT ID FROM SRV_MATRIZ.CAD_CLI0", aliases);
    if (!shared.get(physical).isEmpty()) throw new AssertionError("Filtro indevido entre ocorrências");
    var four = java.util.List.of("A","B","C","D");
    String federated = "SELECT ID FROM A.T UNION ALL SELECT ID FROM B.T UNION ALL SELECT ID FROM C.T UNION ALL SELECT ID FROM D.T";
    if (!"duckdb".equals(LinkSqlEmbeddedEngine.selectEngine(federated, "auto", four)))
      throw new AssertionError("Auto deve selecionar DuckDB para quatro origens elegíveis");
    if (!"calcite".equals(LinkSqlEmbeddedEngine.selectEngine(federated, "calcite", four)))
      throw new AssertionError("Modo Calcite deve permanecer explícito");
    if (!"calcite".equals(LinkSqlEmbeddedEngine.selectEngine(federated, "auto", java.util.List.of("A","B","C"))))
      throw new AssertionError("Origem não elegível deve permanecer no Calcite");
    if (!"calcite".equals(LinkSqlEmbeddedEngine.selectEngine("SELECT ID FROM A.T UNION ALL SELECT ID FROM B.T", "auto", four)))
      throw new AssertionError("Auto não deve transferir consultas pequenas indiscriminadamente");
    var dir = Files.createTempDirectory("linksql-duckdb-test-");
    try {
      var csv = dir.resolve("resultado.csv");
      try (var source = DriverManager.getConnection("jdbc:duckdb:");
           var st = source.createStatement();
           var workspace = DuckDbWorkspace.open(dir, 256, 1);
           var rows = st.executeQuery("SELECT i AS codigo, CAST(i AS DECIMAL(12,2)) AS limite FROM range(10000) t(i)")) {
        DuckDbSqlCompatibility.install(workspace.connection());
        try (var compat = workspace.connection().createStatement();
             var check = compat.executeQuery(DuckDbSqlCompatibility.rewrite(
                 "SELECT REGEXP_REPLACE('12.345-67', '[^0-9]', ''), DATE_FORMAT(DATE '2026-11-25', '%d/%m/%Y'), CHAR(65), 'REGEXP_REPLACE(x)'"))) {
          if (!check.next() || !"1234567".equals(check.getString(1))
              || !"25/11/2026".equals(check.getString(2)) || !"A".equals(check.getString(3))
              || !"REGEXP_REPLACE(x)".equals(check.getString(4)))
            throw new AssertionError("Compatibilidade SQL incorreta");
        }
        long transferred = DuckDbTableTransfer.copy(rows, workspace.connection(), "origem", "clientes", () -> false);
        if (transferred != 10000) throw new AssertionError("Transferência incompleta");
        try (var q = workspace.connection().createStatement();
             var result = q.executeQuery("SELECT codigo, limite FROM origem.clientes ORDER BY codigo")) {
          long exported = CsvExportWriter.write(result, csv, StandardCharsets.UTF_8, ';', true, false, () -> false);
          if (exported != 10000) throw new AssertionError("Exportação incompleta");
        }
        try (var lines = Files.lines(csv)) {
          if (lines.count() != 10001) throw new AssertionError("CSV incorreto");
        }
      }
      Files.delete(csv);
      try (var remaining = Files.list(dir)) {
        if (remaining.findAny().isPresent()) throw new AssertionError("Arquivos temporários restantes");
      }
    } finally {
      try (var paths = Files.walk(dir)) {
        for (var p : paths.sorted(java.util.Comparator.reverseOrder()).toList()) Files.deleteIfExists(p);
      }
    }
    System.out.println("DuckDB e exportação incremental: OK");
  }
}
