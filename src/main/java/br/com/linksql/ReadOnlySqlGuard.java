package br.com.linksql;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Bloqueio defensivo: nao substitui grants de somente leitura nas bases. */
final class ReadOnlySqlGuard {
  private ReadOnlySqlGuard() {}
  private static final Set<String> FORBIDDEN = Set.of(
      "INSERT", "UPDATE", "DELETE", "MERGE", "CREATE", "DROP", "ALTER", "TRUNCATE",
      "EXEC", "EXECUTE", "CALL", "GRANT", "REVOKE", "COPY", "INTO", "LOCK",
      "PROCEDURE", "DO", "VACUUM", "ANALYZE", "LOAD", "OUTFILE", "DUMPFILE");

  static void requireReadOnlySelect(String sql) throws SQLException {
    if (sql == null || sql.isBlank() || sql.length() > 2_000_000)
      throw new SQLException("SQL vazio ou acima do tamanho permitido", "42000");
    List<String> words = scan(sql);
    if (words.isEmpty() || (!words.get(0).equals("SELECT") && !words.get(0).equals("WITH")))
      throw new SQLException("Gateway 2.7 aceita somente SELECT/WITH", "25006");
    if (words.get(0).equals("WITH") && !words.contains("SELECT"))
      throw new SQLException("CTE deve conter SELECT", "25006");
    for (String word : words) {
      if (FORBIDDEN.contains(word))
        throw new SQLException("Operacao nao permitida no Gateway: " + word, "25006");
    }
  }

  /** Tokeniza ignorando literais, identificadores entre aspas e comentarios. */
  static List<String> scan(String sql) throws SQLException {
    if (sql == null || sql.isBlank() || sql.length() > 2_000_000)
      throw new SQLException("SQL vazio ou acima do tamanho permitido", "42000");
    List<String> words = new ArrayList<>();
    int semicolons = 0;
    boolean afterSemicolon = false;
    for (int i = 0; i < sql.length();) {
      char c = sql.charAt(i);
      if (Character.isWhitespace(c)) { i++; continue; }
      if (c == '-' && i + 1 < sql.length() && sql.charAt(i + 1) == '-') {
        i += 2; while (i < sql.length() && sql.charAt(i) != '\n') i++; continue;
      }
      if (c == '#') { while (i < sql.length() && sql.charAt(i) != '\n') i++; continue; }
      if (c == '/' && i + 1 < sql.length() && sql.charAt(i + 1) == '*') {
        if (i + 2 < sql.length() && sql.charAt(i + 2) == '!')
          throw new SQLException("Comentários executáveis não são permitidos", "25006");
        i = sql.indexOf("*/", i + 2);
        if (i < 0) throw new SQLException("Comentario SQL nao terminado", "42000");
        i += 2; continue;
      }
      if (c == '\'' || c == '"' || c == '`' || c == '[') {
        char close = c == '[' ? ']' : c;
        i++; boolean closed = false;
        while (i < sql.length()) {
          char next = sql.charAt(i++);
          if (next == close) {
            if (i < sql.length() && sql.charAt(i) == close) { i++; continue; }
            closed = true; break;
          }
          if (c == '\'' && next == '\\' && i < sql.length()) i++;
        }
        if (!closed) throw new SQLException("Aspas SQL nao terminadas", "42000");
        if (afterSemicolon) throw new SQLException("Multiplos comandos nao permitidos", "25006");
        continue;
      }
      if (c == '$') {
        int j = i + 1;
        while (j < sql.length() && (Character.isLetterOrDigit(sql.charAt(j)) || sql.charAt(j) == '_')) j++;
        if (j < sql.length() && sql.charAt(j) == '$') {
          String delimiter = sql.substring(i, j + 1);
          int end = sql.indexOf(delimiter, j + 1);
          if (end < 0) throw new SQLException("Literal SQL nao terminado", "42000");
          i = end + delimiter.length(); continue;
        }
      }
      if (c == ';') { semicolons++; afterSemicolon = true; i++; continue; }
      if (afterSemicolon) throw new SQLException("Multiplos comandos nao permitidos", "25006");
      if (Character.isLetter(c) || c == '_') {
        int begin = i++;
        while (i < sql.length() && (Character.isLetterOrDigit(sql.charAt(i)) || sql.charAt(i) == '_' || sql.charAt(i) == '$')) i++;
        words.add(sql.substring(begin, i).toUpperCase(Locale.ROOT));
      } else i++;
    }
    if (semicolons > 1) throw new SQLException("Multiplos comandos nao permitidos", "25006");
    return words;
  }
}
