package br.com.linksql;

/**
 * Infere o total apenas quando a pagina retornada foi incompleta.
 * Nao executa SQL, nao faz I/O e nao presume que uma pagina cheia e a ultima.
 */
final class SqlPageTotals {
  private SqlPageTotals() {}

  static Long whenExhausted(int zeroBasedPage, int pageSize, int loadedRows) {
    if (zeroBasedPage < 0 || pageSize <= 0 || loadedRows < 0
        || loadedRows >= pageSize || (zeroBasedPage > 0 && loadedRows == 0)) return null;
    return (long) zeroBasedPage * pageSize + loadedRows;
  }
}
