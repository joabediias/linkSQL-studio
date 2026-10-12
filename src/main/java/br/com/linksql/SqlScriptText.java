package br.com.linksql;

import java.util.ArrayList;
import java.util.List;

/**
 * Utilitarios sem estado para transformar SQL em texto. Nao acessam o banco,
 * nao verificam permissoes e nao tentam substituir um parser SQL completo.
 */
final class SqlScriptText {
  private SqlScriptText() {}

  /** Formata somente clausulas de nivel externo, preservando comentarios/literais. */
  static String formatClauses(String sql) {
    boolean[] code = new boolean[sql.length()];
    int[] depth = new int[sql.length()];
    char quote = 0;
    boolean lineComment = false, blockComment = false;
    int nesting = 0;
    for (int i = 0; i < sql.length(); i++) {
      char ch = sql.charAt(i);
      char next = i + 1 < sql.length() ? sql.charAt(i + 1) : 0;
      if (lineComment) { if (ch == '\n') lineComment = false; continue; }
      if (blockComment) { if (ch == '*' && next == '/') { i++; blockComment = false; } continue; }
      if (quote != 0) {
        if (ch == quote) { if (next == quote) i++; else quote = 0; }
        else if (ch == '\\' && next != 0) i++;
        continue;
      }
      if (ch == '-' && next == '-' && (i + 2 == sql.length() || Character.isWhitespace(sql.charAt(i + 2)))) { i++; lineComment = true; continue; }
      if (ch == '#') { lineComment = true; continue; }
      if (ch == '/' && next == '*') { i++; blockComment = true; continue; }
      if (ch == '\'' || ch == '"' || ch == '`') { quote = ch; continue; }
      if (ch == '(') nesting++;
      if (ch == ')') nesting = Math.max(0, nesting - 1);
      code[i] = true;
      depth[i] = nesting;
    }
    java.util.regex.Pattern clauses = java.util.regex.Pattern.compile(
        "(?i)\\b(?:LEFT\\s+OUTER\\s+JOIN|RIGHT\\s+OUTER\\s+JOIN|FULL\\s+OUTER\\s+JOIN|INNER\\s+JOIN|LEFT\\s+JOIN|RIGHT\\s+JOIN|CROSS\\s+JOIN|UNION\\s+ALL|GROUP\\s+BY|ORDER\\s+BY|FETCH\\s+NEXT|SELECT|FROM|WHERE|HAVING|LIMIT|JOIN|UNION|OFFSET)\\b");
    java.util.regex.Matcher matcher = clauses.matcher(sql);
    List<int[]> changes = new ArrayList<>();
    while (matcher.find()) {
      int pos = matcher.start();
      if (pos == 0 || !code[pos] || depth[pos] != 0) continue;
      boolean valid = true;
      for (int k = pos; k < matcher.end(); k++) if (!code[k] || depth[k] != 0) { valid = false; break; }
      if (!valid) continue;
      int ws = pos;
      while (ws > 0 && (sql.charAt(ws - 1) == ' ' || sql.charAt(ws - 1) == '\t')) ws--;
      if (ws == pos) continue;
      if (ws == 0 || sql.charAt(ws - 1) == '\n' || sql.charAt(ws - 1) == '\r') continue;
      changes.add(new int[]{ws, pos});
    }
    StringBuilder result = new StringBuilder(sql);
    for (int i = changes.size() - 1; i >= 0; i--) {
      int[] range = changes.get(i);
      result.replace(range[0], range[1], "\n");
    }
    return result.toString();
  }


  // Separa comandos SQL sem quebrar strings ou comentarios.
  static List<String> splitStatements(String sql) {
    List<String> statements = new ArrayList<>();
    int start = 0;
    char quote = 0;
    boolean lineComment = false, blockComment = false;
    for (int i = 0; i < sql.length(); i++) {
      char ch = sql.charAt(i);
      char next = i + 1 < sql.length() ? sql.charAt(i + 1) : 0;
      if (lineComment) { if (ch == '\n') lineComment = false; continue; }
      if (blockComment) { if (ch == '*' && next == '/') { blockComment = false; i++; } continue; }
      if (quote != 0) {
        if (ch == quote) { if (next == quote) i++; else quote = 0; }
        else if (ch == '\\' && next != 0) i++;
        continue;
      }
      if (ch == '-' && next == '-' && (i + 2 == sql.length() || Character.isWhitespace(sql.charAt(i + 2)))) { lineComment = true; i++; continue; }
      if (ch == '#') { lineComment = true; continue; }
      if (ch == '/' && next == '*') { blockComment = true; i++; continue; }
      if (ch == '\'' || ch == '"' || ch == '`') { quote = ch; continue; }
      if (ch == ';') {
        String part = sql.substring(start, i).trim();
        if (!part.isBlank()) statements.add(part);
        start = i + 1;
      }
    }
    String last = sql.substring(start).trim();
    if (!last.isBlank()) statements.add(last);
    return statements;
  }

}
