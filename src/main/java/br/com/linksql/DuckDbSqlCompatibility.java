package br.com.linksql;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Locale;
import java.util.regex.Pattern;

/** Compatibilidade restrita às funções homologadas do SQL federado. */
final class DuckDbSqlCompatibility {
  private DuckDbSqlCompatibility() {}
  private static final Pattern TOKEN = Pattern.compile(
      "'(?:''|[^'])*'|\"(?:\"\"|[^\"])*\"|`(?:``|[^`])*`|/\\*[\\s\\S]*?\\*/|--[^\\r\\n]*|[A-Za-z_][A-Za-z_0-9$]*");
  static String rewrite(String sql) {
    var matcher = TOKEN.matcher(sql);
    StringBuilder out = new StringBuilder();
    while (matcher.find()) {
      String token = matcher.group();
      String replacement = token;
      int next = matcher.end();
      while (next < sql.length() && Character.isWhitespace(sql.charAt(next))) next++;
      if (next < sql.length() && sql.charAt(next) == '(') {
        replacement = switch (token.toUpperCase(Locale.ROOT)) {
          case "REGEXP_REPLACE" -> "linksql_regexp_replace";
          case "DATE_FORMAT" -> "linksql_date_format";
          case "CHAR" -> "chr";
          default -> token;
        };
      }
      matcher.appendReplacement(out, java.util.regex.Matcher.quoteReplacement(replacement));
    }
    matcher.appendTail(out);
    return out.toString();
  }

  static void install(Connection connection) throws SQLException {
    try (Statement st = connection.createStatement()) {
      st.execute("CREATE TEMP MACRO linksql_regexp_replace(v, p, r) AS regexp_replace(v, p, r, 'g')");
      st.execute("CREATE TEMP MACRO linksql_date_format(v, p) AS "
          + "CASE WHEN p IN ('%d/%m/%Y', '%m/%Y', '%Y-%m-%d') "
          + "THEN strftime(CAST(v AS TIMESTAMP), p) "
          + "ELSE error('DATE_FORMAT: mascara ainda nao suportada no modo DuckDB') END");
    }
  }
}
