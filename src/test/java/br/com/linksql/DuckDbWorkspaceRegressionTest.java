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
    var dir = Files.createTempDirectory("linksql-duckdb-test-");
    try {
      var csv = dir.resolve("resultado.csv");
      try (var source = DriverManager.getConnection("jdbc:duckdb:");
           var st = source.createStatement();
           var workspace = DuckDbWorkspace.open(dir, 256, 1);
           var rows = st.executeQuery("SELECT i AS codigo, CAST(i AS DECIMAL(12,2)) AS limite FROM range(10000) t(i)")) {
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
