package br.com.linksql;

import java.util.*;

/** Contrato das faixas, sem depender de conexoes ou dados de clientes reais. */
public final class JdbcBatchRangesRegressionTest {
  public static void main(String[] args) {
    List<String> codes = new ArrayList<>();
    for (int x = 0; x < 36612; x++) codes.add(String.format(java.util.Locale.ROOT, "%06d", 100000 + x));
    List<String[]> ranges = JdbcBatchRanges.groupCodes(codes, 250);
    check(ranges.size() == 147, "36.612 codigos devem gerar 147 consultas, nao 1040");
    check(Arrays.equals(ranges.get(0), new String[]{"100000","100249"}), "primeira faixa");
    check(Arrays.equals(ranges.get(ranges.size()-1), new String[]{"136500","136611"}), "ultima faixa");
    List<String> sparse = List.of("000001","001680","002002","002002","003010","703001");
    List<String[]> sparseRanges = JdbcBatchRanges.groupCodes(sparse,2);
    check(sparseRanges.size() == 3, "remover duplicatas e faixas vazias");
    for (String code: sparse) {
      int matches = 0;
      for (String[] range: sparseRanges) {
        if (code.compareTo(range[0]) >= 0 && code.compareTo(range[1]) <= 0) matches++;
      }
      check(matches == 1, "codigo omitido ou duplicado: " + code);
    }
    String sql = JdbcBatchRanges.discoverySql("CAD_CLI0", "CLI_CODIGO");
    check(sql.contains("CLI_EXCLUIDO") && sql.contains("CLI_DTINAT") && sql.contains("CLI_DTATIV"),
        "identificar apenas clientes ativos");
    check(JdbcBatchRanges.discoverySql("CAD_FOR0", "FOR_COD")
        .contains("SQL_DELETED = 'F'"), "filtro fornecedor");
    check(!JdbcBatchRanges.discoverySql("CAD_FOR0", "FOR_COD").contains("CLI_EXCLUIDO"),
        "nao aplicar regra cliente ao fornecedor");
    System.out.println("OK: faixas ocupadas, chaves sem omissao/duplicacao e regra de cliente ativo");
  }
  private static void check(boolean ok, String message) { if (!ok) throw new AssertionError(message); }
}
