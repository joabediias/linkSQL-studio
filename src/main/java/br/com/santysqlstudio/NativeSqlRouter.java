package br.com.santysqlstudio;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Identifica SELECTs com referencias explicitas a uma unica conexao nao-MySQL.
 * Uma rota incerta retorna null: o executor preserva entao o Calcite federado.
 * Nao tenta traduzir dialetos ou converter identificadores entre fornecedores.
 */
final class NativeSqlRouter {
  private NativeSqlRouter() {}

  record Route(String source, SqlDatabaseType type, String sql) {}
  private record Token(String value, int start, int end, boolean identifier, boolean quoted) {
    boolean word(String text) { return identifier && !quoted && value.equalsIgnoreCase(text); }
  }
  private record Span(int start, int end) {}

  static Route route(String sql, Collection<JdbcConnectionProfile> profiles) {
    if (sql == null || sql.isBlank() || profiles == null || profiles.isEmpty()) return null;
    List<Token> tokens = lex(sql);
    if (!readOnlyTokens(tokens)) return null;

    JdbcConnectionProfile chosen = null;
    List<Span> removals = new ArrayList<>();
    boolean sourceInFromOrJoin = false;
    Set<String> sources = new HashSet<>();
    for (int i = 0; i + 2 < tokens.size(); i++) {
      Token prefix = tokens.get(i);
      if (!prefix.identifier || !tokens.get(i + 1).value.equals(".")
          || !tokens.get(i + 2).identifier) continue;
      if (i > 0 && tokens.get(i - 1).value.equals(".")) continue;
      JdbcConnectionProfile p = null;
      for (JdbcConnectionProfile candidate : profiles) {
        if (candidate.name().equalsIgnoreCase(prefix.value)) { p = candidate; break; }
      }
      if (p == null) continue;
      sources.add(p.name().toUpperCase(Locale.ROOT));
      if (sources.size() > 1) return null; // JOIN entre origens: Calcite.
      chosen = p;
      removals.add(new Span(prefix.start, tokens.get(i + 2).start));
      if (i > 0 && (tokens.get(i - 1).word("FROM") || tokens.get(i - 1).word("JOIN"))) {
        sourceInFromOrJoin = true;
      }
    }
    if (chosen == null || !sourceInFromOrJoin || chosen.type() == SqlDatabaseType.MYSQL) return null;
    StringBuilder nativeSql = new StringBuilder(sql);
    removals.sort(Comparator.comparingInt(Span::start).reversed());
    for (Span r : removals) nativeSql.delete(r.start, r.end);
    return new Route(chosen.name(), chosen.type(), nativeSql.toString());
  }

  /** Valida a natureza somente-leitura antes do roteamento nativo da aba. */
  static boolean readOnlyQuery(String sql) {
    return sql != null && readOnlyTokens(lex(sql));
  }

  private static boolean readOnlyTokens(List<Token> tokens) {
    if (tokens == null || tokens.isEmpty()) return false;
    if (!tokens.get(0).word("SELECT") && !tokens.get(0).word("WITH")) return false;
    // WITH com CTE de escrita (PostgreSQL) nao deve ser encaminhado sem revisao.
    for (Token t : tokens) {
      if (t.word("INSERT") || t.word("UPDATE") || t.word("DELETE") || t.word("MERGE")
          || t.word("CREATE") || t.word("ALTER") || t.word("DROP") || t.word("TRUNCATE")
          || t.word("EXEC") || t.word("EXECUTE") || t.word("CALL") || t.word("GRANT")
          || t.word("REVOKE") || t.word("COPY") || t.word("INTO") || t.word("LOCK")
          || t.value.equals(";")) return false;
    }
    return true;
  }

  /** Lexer conservador: comentarios e literais nunca sao reescritos. */
  private static List<Token> lex(String sql) {
    List<Token> tokens = new ArrayList<>();
    for (int i = 0; i < sql.length();) {
      char ch = sql.charAt(i);
      if (Character.isWhitespace(ch)) { i++; continue; }
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
        // Comentario executavel do MySQL nao e seguro para reescrita.
        if (i + 2 < sql.length() && sql.charAt(i + 2) == '!') return null;
        int end = sql.indexOf("*/", i + 2);
        if (end < 0) return null;
        i = end + 2; continue;
      }
      int start = i;
      if (ch == '\'') {
        i++;
        boolean closed = false;
        while (i < sql.length()) {
          if (sql.charAt(i) == '\'' ) {
            if (i + 1 < sql.length() && sql.charAt(i + 1) == '\'') { i += 2; continue; }
            closed = true; i++; break;
          }
          if (sql.charAt(i) == '\\' && i + 1 < sql.length()) { i += 2; continue; }
          i++;
        }
        if (!closed) return null;
        tokens.add(new Token("<literal>", start, i, false, false));
      } else if (ch == '"' || ch == '`' || ch == '[') {
        char closing = ch == '[' ? ']' : ch;
        i++; StringBuilder name = new StringBuilder(); boolean closed = false;
        while (i < sql.length()) {
          char current = sql.charAt(i++);
          if (current == closing) {
            if (i < sql.length() && sql.charAt(i) == closing) {
              name.append(closing); i++; continue;
            }
            closed = true; break;
          }
          name.append(current);
        }
        if (!closed) return null;
        tokens.add(new Token(name.toString(), start, i, true, true));
      } else if (ch == '$') {
        // Dollar-quoted PostgreSQL: pula sem editar o corpo.
        int j = i + 1;
        while (j < sql.length() && (Character.isLetterOrDigit(sql.charAt(j)) || sql.charAt(j) == '_')) j++;
        if (j < sql.length() && sql.charAt(j) == '$') {
          String delimiter = sql.substring(i, j + 1);
          int end = sql.indexOf(delimiter, j + 1);
          if (end < 0) return null;
          i = end + delimiter.length();
          tokens.add(new Token("<literal>", start, i, false, false));
        } else {
          i++; tokens.add(new Token("$", start, i, false, false));
        }
      } else if (Character.isLetter(ch) || ch == '_') {
        i++;
        while (i < sql.length() && (Character.isLetterOrDigit(sql.charAt(i))
            || sql.charAt(i) == '_' || sql.charAt(i) == '$')) i++;
        tokens.add(new Token(sql.substring(start, i), start, i, true, false));
      } else {
        i++;
        tokens.add(new Token(sql.substring(start, i), start, i, false, false));
      }
    }
    return tokens;
  }
}
