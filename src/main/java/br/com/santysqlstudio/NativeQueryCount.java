package br.com.santysqlstudio;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Constrói uma contagem de linhas sem trazer as linhas ao cliente JDBC.
 * Preserva DISTINCT, GROUP BY, JOIN, UNION e filtros encapsulando o SELECT.
 * A ordenação final é removida: SQL Server não aceita ORDER BY em subconsulta
 * sem TOP/OFFSET e a ordenação não altera a cardinalidade.
 *
 * Não executa SQL: o chamador deve usar apenas consultas SELECT/CTE
 * aprovadas pelo roteador e aplicar o limite explícito do editor separadamente.
 */
final class NativeQueryCount {
  private NativeQueryCount() {}

  static String sql(String nativeSql) {
    if (nativeSql == null || nativeSql.isBlank()) return null;
    String text = nativeSql.trim();
    if (text.endsWith(";")) text = text.substring(0, text.length() - 1).trim();
    List<Word> words = topLevelWords(text);
    if (words == null || words.isEmpty()) return null;
    String first = words.get(0).value;
    if (!first.equals("SELECT") && !first.equals("WITH")) return null;

    int selectStart = 0;
    if (first.equals("WITH")) {
      // SQL Server exige WITH no início do comando, fora da tabela derivada.
      selectStart = -1;
      for (Word w : words) {
        if (w.value.equals("SELECT")) { selectStart = w.start; break; }
      }
      if (selectStart < 0) return null;
    }
    int orderStart = -1;
    for (int i = 0; i + 1 < words.size(); i++) {
      if (words.get(i).start >= selectStart && words.get(i).value.equals("ORDER")
          && words.get(i + 1).value.equals("BY")) orderStart = words.get(i).start;
    }
    String prefix = text.substring(0, selectStart);
    String body = text.substring(selectStart, orderStart >= 0 ? orderStart : text.length()).trim();
    if (body.isBlank()) return null;
    // O alias sem AS é aceito por Oracle, SQL Server, PostgreSQL e Firebird.
    return prefix + "SELECT COUNT(*) FROM (\n" + body + "\n) SANTYSQL_TOTAL";
  }

  private record Word(String value, int start) {}

  /** Lexer conservador, consciente de comentários, aspas e parênteses. */
  private static List<Word> topLevelWords(String sql) {
    List<Word> words = new ArrayList<>();
    int depth = 0;
    for (int i = 0; i < sql.length();) {
      char c = sql.charAt(i);
      if (Character.isWhitespace(c)) { i++; continue; }
      if (c == '-' && i + 1 < sql.length() && sql.charAt(i + 1) == '-') {
        i += 2;
        while (i < sql.length() && sql.charAt(i) != '\n') i++;
        continue;
      }
      if (c == '#' ) { // comentários de consultas oriundas de MySQL
        while (i < sql.length() && sql.charAt(i) != '\n') i++;
        continue;
      }
      if (c == '/' && i + 1 < sql.length() && sql.charAt(i + 1) == '*') {
        int end = sql.indexOf("*/", i + 2);
        if (end < 0) return null;
        i = end + 2; continue;
      }
      if (c == '\'' || c == '"' || c == '`' || c == '[') {
        char end = c == '[' ? ']' : c;
        i++;
        boolean finished = false;
        while (i < sql.length()) {
          if (sql.charAt(i) == end) {
            if (i + 1 < sql.length() && sql.charAt(i + 1) == end) { i += 2; continue; }
            i++; finished = true; break;
          }
          if (c == '\'' && sql.charAt(i) == '\\' && i + 1 < sql.length()) { i += 2; continue; }
          i++;
        }
        if (!finished) return null;
        continue;
      }
      // Strings dollar-quoted do PostgreSQL: não interpretar tokens internos.
      if (c == '$') {
        int j = i + 1;
        while (j < sql.length() && (Character.isLetterOrDigit(sql.charAt(j)) || sql.charAt(j) == '_')) j++;
        if (j < sql.length() && sql.charAt(j) == '$') {
          String tag = sql.substring(i, j + 1);
          int end = sql.indexOf(tag, j + 1);
          if (end < 0) return null;
          i = end + tag.length(); continue;
        }
      }
      if (c == '(') { depth++; i++; continue; }
      if (c == ')') { if (--depth < 0) return null; i++; continue; }
      if (Character.isLetter(c) || c == '_') {
        int start = i++;
        while (i < sql.length() && (Character.isLetterOrDigit(sql.charAt(i)) || sql.charAt(i) == '_')) i++;
        if (depth == 0) words.add(new Word(sql.substring(start, i).toUpperCase(Locale.ROOT), start));
        continue;
      }
      i++;
    }
    return depth == 0 ? words : null;
  }
}
