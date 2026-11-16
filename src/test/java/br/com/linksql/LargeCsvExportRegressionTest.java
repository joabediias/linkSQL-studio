package br.com.linksql;

/** Teste rapido do contrato de particionamento SQL; nao acessa bancos externos. */
public final class LargeCsvExportRegressionTest {
  public static void main(String[] args) {
    String sql = "WITH C AS (SELECT * FROM SRV_MATRIZ.CAD_CLI0 C WHERE 1=1 "
        + "/*LINKSQL_LOTE:C.CLI_CODIGO*/), P AS (SELECT * FROM SRV_MATRIZ.CLI_PAG0 CP WHERE 1=1 "
        + "/*LINKSQL_LOTE:CP.PAG_CLI*/) SELECT * FROM C";
    require(LargeCsvExport.isTemplate(sql), "marcadores nao reconhecidos");
    String bounded = LargeCsvExport.partition(sql, "002000", "002249");
    require(!bounded.contains("/*LINKSQL_LOTE:"), "marcadores nao substituidos");
    require(bounded.contains("C.CLI_CODIGO >= '002000' AND C.CLI_CODIGO <= '002249'"),
        "filtro principal incorreto");
    require(bounded.contains("CP.PAG_CLI >= '002000' AND CP.PAG_CLI <= '002249'"),
        "filtro pagamentos incorreto");
    require(bounded.contains("SRV_MATRIZ.CAD_CLI0"), "SQL fonte alterado");
    boolean rejected = false;
    try { LargeCsvExport.partition("SELECT 1", "000000", "000249"); }
    catch (IllegalArgumentException ex) { rejected = true; }
    require(rejected, "SQL sem marcadores nao foi recusado");
    rejected = false;
    try { new LargeCsvExport.Config("CAD_CLI0;DROP", "CLI_CODIGO", 6, 3, 250, ';'); }
    catch (IllegalArgumentException ex) { rejected = true; }
    require(rejected, "identificador inseguro aceito");
    System.out.println("OK: particionamento SQL, marcadores e validacao de identificadores");
  }
  private static void require(boolean b, String msg) { if (!b) throw new AssertionError(msg); }
}
