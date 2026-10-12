package br.com.santysqlstudio;

import java.util.regex.Pattern;

/** Determina se o ponto atual espera um nome de tabela ou CTE. */
final class SqlAutocompleteHints {
  private static final Pattern TABLE_CONTEXT = Pattern.compile("(?is).*\\b(?:FROM|JOIN)\\s+$");
  private SqlAutocompleteHints() {}

  static boolean expectsTable(String sqlBeforeWord) {
    return sqlBeforeWord != null && TABLE_CONTEXT.matcher(sqlBeforeWord).matches();
  }
}
