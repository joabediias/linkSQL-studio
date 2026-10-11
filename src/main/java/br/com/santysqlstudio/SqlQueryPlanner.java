package br.com.santysqlstudio;

import java.util.Collection;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Regras de roteamento e paginação SQL independentes da interface Swing. */
final class SqlQueryPlanner {
  private SqlQueryPlanner() {}

  record DirectSql(String source, String sql) {}
  record SqlWindow(String baseSql, Long maxRows, long startOffset) {}

  // Extrai apenas limites finais simples, preservando a consulta original no editor.
  // O limite SQL e o tamanho de pagina sao coisas distintas.
  static SqlWindow sqlWindow(String sql) {
    String text = sql.trim();
    java.util.regex.Matcher m = java.util.regex.Pattern.compile(
        "(?is)^(.+?)\\s+OFFSET\\s+(\\d+)\\s+ROWS?\\s+FETCH\\s+(?:FIRST|NEXT)\\s+(\\d+)\\s+ROWS?\\s+ONLY$").matcher(text);
    if (m.matches()) return new SqlWindow(m.group(1).trim(), Long.parseLong(m.group(3)), Long.parseLong(m.group(2)));
    m = java.util.regex.Pattern.compile(
        "(?is)^(.+?)\\s+FETCH\\s+(?:FIRST|NEXT)\\s+(\\d+)\\s+ROWS?\\s+ONLY$").matcher(text);
    if (m.matches()) return new SqlWindow(m.group(1).trim(), Long.parseLong(m.group(2)), 0L);
    m = java.util.regex.Pattern.compile("(?is)^(.+?)\\s+LIMIT\\s+(\\d+)\\s+OFFSET\\s+(\\d+)$").matcher(text);
    if (m.matches()) return new SqlWindow(m.group(1).trim(), Long.parseLong(m.group(2)), Long.parseLong(m.group(3)));
    m = java.util.regex.Pattern.compile("(?is)^(.+?)\\s+LIMIT\\s+(\\d+)\\s*,\\s*(\\d+)$").matcher(text);
    if (m.matches()) return new SqlWindow(m.group(1).trim(), Long.parseLong(m.group(3)), Long.parseLong(m.group(2)));
    m = java.util.regex.Pattern.compile("(?is)^(.+?)\\s+LIMIT\\s+(\\d+)$").matcher(text);
    if (m.matches()) return new SqlWindow(m.group(1).trim(), Long.parseLong(m.group(2)), 0L);
    return new SqlWindow(text, null, 0L);
  }
  static String directPageSql(DirectSql direct, SqlWindow window, int page, int size) {
    long skipped = (long) page * size;
    long amount = size == 0 ? (window.maxRows() == null ? 0L : window.maxRows())
        : window.maxRows() == null ? size : Math.max(0L, Math.min((long) size, window.maxRows() - skipped));
    long offset = Math.addExact(window.startOffset(), skipped);
    if (size == 0 && window.maxRows() == null && offset == 0L) return direct.sql();
    // LIMIT de um inteiro positivo mantém o teto do SQL; para OFFSET puro,
    // o MySQL exige um limite sintático (máximo de BIGINT UNSIGNED).
    String limit = size == 0 && window.maxRows() == null ? "18446744073709551615" : Long.toString(amount);
    return direct.sql() + " LIMIT " + limit + " OFFSET " + offset;
  }
  // Analise lexical conservadora: permite JOIN, CTE e subconsulta de um unico servidor,
  // sem reescrever strings/comentarios; ambiguidades continuam no Calcite.
  static DirectSql directSql(String sql, Collection<String> sources) {
    SingleServerSqlRouter.Route route = SingleServerSqlRouter.route(sqlWindow(sql).baseSql(), sources);
    return route == null ? null : new DirectSql(route.source(), route.sql());
  }
  static String executableSql(String sql, Collection<String> sources) {
    DirectSql direct = directSql(sql, sources);
    if (direct == null) return sql;
    SqlWindow window = sqlWindow(sql);
    if (window.maxRows() != null) {
      return direct.sql() + " LIMIT " + window.maxRows() + " OFFSET " + window.startOffset();
    }
    return direct.sql();
  }
  static boolean isPageableSql(String sql) {
    if (sql == null || sql.isBlank()) return false;
    // Um FETCH/LIMIT/OFFSET dentro de subconsulta ou CTE nao limita o resultado
    // principal. Verificar apenas clausulas do nivel externo do SQL.
    String normalized = sql.stripLeading().toUpperCase(Locale.ROOT);
    if (!normalized.matches("(?s)^(?:SELECT|WITH)\\b.*")) return false;
    SqlWindow window = sqlWindow(sql);
    return window.maxRows() != null || !hasTopLevelRowWindow(sql);
  }

  /** Identifica palavras de paginacao apenas no nivel externo do SELECT. */
  private static boolean hasTopLevelRowWindow(String sql) {
    int depth = 0;
    for (int i = 0; i < sql.length();) {
      char ch = sql.charAt(i);
      if (ch == '-' && i + 1 < sql.length() && sql.charAt(i + 1) == '-') {
        i += 2;
        while (i < sql.length() && sql.charAt(i) != '\n') i++;
        continue;
      }
      if (ch == '#') {
        while (i < sql.length() && sql.charAt(i) != '\n') i++;
        continue;
      }
      if (ch == '/' && i + 1 < sql.length() && sql.charAt(i + 1) == '*') {
        int end = sql.indexOf("*/", i + 2);
        if (end < 0) return true; // Consulta incompleta: nao tentar paginar.
        i = end + 2; continue;
      }
      if (ch == '\'' || ch == '"' || ch == '`' || ch == '[') {
        char close = ch == '[' ? ']' : ch;
        i++;
        boolean finished = false;
        while (i < sql.length()) {
          char c = sql.charAt(i++);
          if (c == close) {
            if (i < sql.length() && sql.charAt(i) == close) { i++; continue; }
            finished = true; break;
          }
          if (ch == '\'' && c == '\\' && i < sql.length()) i++;
        }
        if (!finished) return true;
        continue;
      }
      // PostgreSQL: $$...$$ e $tag$...$tag$ nao devem ser analisados como SQL.
      if (ch == '$') {
        int j = i + 1;
        while (j < sql.length() && (Character.isLetterOrDigit(sql.charAt(j)) || sql.charAt(j) == '_')) j++;
        if (j < sql.length() && sql.charAt(j) == '$') {
          String tag = sql.substring(i, j + 1);
          int end = sql.indexOf(tag, j + 1);
          if (end < 0) return true;
          i = end + tag.length(); continue;
        }
      }
      if (ch == '(') { depth++; i++; continue; }
      if (ch == ')') { if (depth == 0) return true; depth--; i++; continue; }
      if (Character.isLetter(ch) || ch == '_') {
        int begin = i++;
        while (i < sql.length() && (Character.isLetterOrDigit(sql.charAt(i)) || sql.charAt(i) == '_')) i++;
        if (depth == 0) {
          String word = sql.substring(begin, i).toUpperCase(Locale.ROOT);
          if (word.equals("FETCH") || word.equals("LIMIT") || word.equals("OFFSET")) return true;
        }
        continue;
      }
      i++;
    }
    return depth != 0; // SQL desbalanceado: comportamento conservador.
  }

}
