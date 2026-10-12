package br.com.linksql;

/**
 * Contagem opcional de resultado federado: nao materializa as linhas no cliente.
 * Pode ser custosa em JOINS entre servidores, por isso o executor usa timeout
 * e a consulta principal nunca espera a contagem terminar.
 */
final class FederatedCountSupport {
  private FederatedCountSupport() {}

  static String countSql(String original) {
    if (original == null || !SqlQueryPlanner.isPageableSql(original)) return null;
    SqlQueryPlanner.SqlWindow w = SqlQueryPlanner.sqlWindow(original);
    // Conserva DISTINCT, GROUP BY, JOIN e CTE no interior da contagem;
    // remove apenas o ORDER BY externo, que nao muda cardinalidade.
    return NativeQueryCount.sql(w.baseSql());
  }

  static long effectiveTotal(long raw, String original) {
    SqlQueryPlanner.SqlWindow w = SqlQueryPlanner.sqlWindow(original);
    long total = Math.max(0L, raw - w.startOffset());
    return w.maxRows() == null ? total : Math.min(total, w.maxRows());
  }
}
