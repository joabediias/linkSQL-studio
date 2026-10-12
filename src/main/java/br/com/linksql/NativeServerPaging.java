package br.com.linksql;

import java.sql.SQLException;

/**
 * Paginação enviada ao próprio banco quando o dialeto oferece cláusula
 * portátil para o fornecedor. SQL Server permanece no leitor JDBC até termos
 * verificação de ORDER BY de nível externo (requisito do OFFSET/FETCH T-SQL).
 * Consultas sem ORDER BY não garantem estabilidade das páginas.
 */
final class NativeServerPaging {
  private NativeServerPaging() {}

  static String sql(NativeSqlRouter.Route route, NativeJdbcPaging.Window window) throws SQLException {
    if (route == null || window == null || !window.limited()) return null;
    String text = route.sql().trim();
    if (text.endsWith(";")) text = text.substring(0, text.length() - 1).stripTrailing();
    if (text.isEmpty()) return null;
    long skip = window.skip();
    long amount = window.amount();
    try {
      return switch (route.type()) {
        case POSTGRESQL -> text + " LIMIT " + amount + " OFFSET " + skip;
        case ORACLE -> text + " OFFSET " + skip + " ROWS FETCH NEXT " + amount + " ROWS ONLY";
        case FIREBIRD -> {
          // Firebird usa indices 1-based para ROWS <inicio> TO <fim>.
          if (amount == 0) yield null; // leitor JDBC evita resultados incorretos.
          long start = Math.addExact(skip, 1L);
          long end = Math.addExact(skip, amount);
          yield text + " ROWS " + start + " TO " + end;
        }
        case MYSQL -> text + " LIMIT " + amount + " OFFSET " + skip;
        case SQLSERVER -> null;
      };
    } catch (ArithmeticException ex) {
      throw new SQLException("Intervalo de pagina acima do limite suportado", ex);
    }
  }
}
