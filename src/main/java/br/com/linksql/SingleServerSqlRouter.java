package br.com.linksql;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Roteamento conservador de SELECT/CTE para uma unica conexao MySQL.
 * Nao tenta validar a gramatica SQL: ao encontrar uma referencia ambigua, usa Calcite.
 * Analisa tokens, sem modificar comentarios, literais, aliases ou a estrutura da consulta.
 */
final class SingleServerSqlRouter {
  private SingleServerSqlRouter() {}
  record Route(String source, String sql) {}

  private enum Kind { IDENT, STRING, DOT, COMMA, LPAREN, RPAREN, OTHER }
  private record Token(String value, int start, int end, int depth, Kind kind) {
    boolean word(String expected) {
      return kind == Kind.IDENT && value.equalsIgnoreCase(expected);
    }
    boolean ident() { return kind == Kind.IDENT; }
  }
  private record Range(int start, int end) {}

  static Route route(String sql, Collection<String> sourceNames) {
    if (sql == null || sql.isBlank() || sourceNames == null || sourceNames.isEmpty()) return null;
    List<Token> tokens = tokenize(sql);
    if (tokens == null || tokens.isEmpty()) return null;
    if (!tokens.get(0).word("SELECT") && !tokens.get(0).word("WITH")) return null;

    String source = null;
    int topSelects = 0;
    List<Range> prefixRanges = new ArrayList<>();
    Set<Integer> tableQualifiers = new HashSet<>();
    Set<String> references = new HashSet<>();

    for (int i = 0; i < tokens.size(); i++) {
      Token t = tokens.get(i);
      if (t.depth() == 0 && t.word("SELECT")) topSelects++;
      // Algumas extensoes do Calcite nao sao aceitas pelo MySQL; preserve a rota federada.
      if (t.word("ILIKE") || t.word("TABLESAMPLE") || t.word("OFFSET") || t.word("FETCH")
          || (t.word("SIMILAR") && i + 1 < tokens.size() && tokens.get(i + 1).word("TO"))
          || (t.word("IS") && i + 2 < tokens.size()
              && tokens.get(i + 1).word("DISTINCT") && tokens.get(i + 2).word("FROM"))
          || (t.word("FULL") && i + 1 < tokens.size()
              && (tokens.get(i + 1).word("JOIN") || tokens.get(i + 1).word("OUTER")))
          || (t.word("NULLS") && i + 1 < tokens.size()
              && (tokens.get(i + 1).word("FIRST") || tokens.get(i + 1).word("LAST"))))
        return null;
      if (t.depth() == 0 && (t.word("INSERT") || t.word("UPDATE") || t.word("DELETE")
          || t.word("CREATE") || t.word("ALTER") || t.word("DROP") || t.word("REPLACE")
          || t.word("TRUNCATE") || t.word("MERGE") || t.word("CALL"))) return null;
      // SELECT ... INTO OUTFILE/variavel e SELECT ... FOR UPDATE nao sao leituras puras.
      if (t.word("INTO") || (t.word("FOR") && i + 1 < tokens.size() && tokens.get(i + 1).word("UPDATE")))
        return null;
      if (!t.word("FROM") && !t.word("JOIN")) continue;
      int next = i + 1;
      if (next < tokens.size() && tokens.get(next).word("LATERAL")) next++;
      if (next >= tokens.size()) return null;
      Token table = tokens.get(next);
      if (table.kind() == Kind.LPAREN) continue; // subconsulta; seu FROM sera analisado separadamente
      if (!table.ident()) return null; // identificador ambiguo, ex.: dupla aspa/SQL dinamico
      if (next + 1 < tokens.size() && tokens.get(next + 1).kind() == Kind.DOT) {
        if (next + 2 >= tokens.size() || !tokens.get(next + 2).ident()) return null;
        String resolved = resolveSource(table.value(), sourceNames);
        if (resolved == null) return null; // schema fisico/externo nao mapeado
        references.add(resolved);
        if (references.size() > 1) return null;
        source = resolved;
        tableQualifiers.add(next);
        if (!onlyWhitespaceBetween(sql, table.end(), tokens.get(next + 1).start())
            || !onlyWhitespaceBetween(sql, tokens.get(next + 1).end(), tokens.get(next + 2).start()))
          return null;
        prefixRanges.add(new Range(table.start(), tokens.get(next + 2).start()));
        // Alias com nome identico ao servidor causa ambiguidade em "SERVIDOR.coluna".
        int after = next + 3;
        if (after < tokens.size() && tokens.get(after).word("AS")) after++;
        if (after < tokens.size() && tokens.get(after).ident()
            && resolveSource(tokens.get(after).value(), sourceNames) != null) return null;
      } else {
        // FROM tabela_alias (CTE ou tabela sem qualificador); aceito somente quando
        // outra tabela da consulta explicitamente indica o servidor.
        int after = next + 1;
        if (after < tokens.size() && tokens.get(after).word("AS")) after++;
        if (after < tokens.size() && tokens.get(after).ident()
            && resolveSource(tokens.get(after).value(), sourceNames) != null) return null;
      }
    }
    if (source == null || topSelects == 0) return null;

    // JOIN implicito por virgula pode incluir outra tabela nao qualificada ou
    // outra base. Mantemos essas variantes no Calcite ate suportar sua analise completa.
    for (int i = 0; i < tokens.size(); i++) {
      Token t = tokens.get(i);
      if (!t.word("FROM")) continue;
      int level = t.depth();
      for (int j = i + 1; j < tokens.size(); j++) {
        Token p = tokens.get(j);
        if (p.depth() < level) break;
        if (p.depth() != level) continue;
        if (j > i + 1 && (p.word("WHERE") || p.word("JOIN") || p.word("ON")
            || p.word("GROUP") || p.word("ORDER") || p.word("HAVING")
            || p.word("UNION") || p.word("LIMIT") || p.word("OFFSET")
            || p.word("FETCH") || p.word("WINDOW") || p.word("QUALIFY")
            || p.word("FOR"))) break;
        if (p.kind() == Kind.COMMA) return null;
      }
    }

    // Corrige tambem referencias de coluna em tres partes: SRV.TABELA.COLUNA.
    // Qualificadores de duas partes fora de FROM/JOIN podem ser aliases de tabelas;
    // ao encontrar um deles, prefere o Calcite a assumir que e um schema.
    for (int i = 0; i + 2 < tokens.size(); i++) {
      Token t = tokens.get(i);
      if (!t.ident() || tokens.get(i + 1).kind() != Kind.DOT || !tokens.get(i + 2).ident()) continue;
      String resolved = resolveSource(t.value(), sourceNames);
      if (resolved == null || tableQualifiers.contains(i)) continue;
      if (!resolved.equals(source)) return null;
      if (i + 4 >= tokens.size() || tokens.get(i + 3).kind() != Kind.DOT
          || !tokens.get(i + 4).ident()) return null;
      if (!onlyWhitespaceBetween(sql, t.end(), tokens.get(i + 1).start())
          || !onlyWhitespaceBetween(sql, tokens.get(i + 1).end(), tokens.get(i + 2).start()))
        return null;
      prefixRanges.add(new Range(t.start(), tokens.get(i + 2).start()));
    }

    // Aplica as remocoes da direita para a esquerda para manter offsets originais.
    StringBuilder rewritten = new StringBuilder(sql);
    prefixRanges.sort((a, b) -> Integer.compare(b.start(), a.start()));
    for (Range range : prefixRanges) rewritten.delete(range.start(), range.end());
    return new Route(source, rewritten.toString());
  }

  private static boolean onlyWhitespaceBetween(String sql, int start, int end) {
    for (int i = start; i < end; i++) if (!Character.isWhitespace(sql.charAt(i))) return false;
    return true;
  }

  private static String resolveSource(String name, Collection<String> sources) {
    for (String candidate : sources) if (candidate.equalsIgnoreCase(name)) return candidate;
    return null;
  }

  private static List<Token> tokenize(String sql) {
    List<Token> tokens = new ArrayList<>();
    int depth = 0;
    for (int i = 0; i < sql.length();) {
      char ch = sql.charAt(i);
      if (Character.isWhitespace(ch)) { i++; continue; }
      if (ch == '-' && i + 2 < sql.length() && sql.charAt(i + 1) == '-'
          && Character.isWhitespace(sql.charAt(i + 2))) {
        i += 2;
        while (i < sql.length() && sql.charAt(i) != '\n') i++;
        continue;
      }
      if (ch == '#') {
        while (i < sql.length() && sql.charAt(i) != '\n') i++;
        continue;
      }
      if (ch == '/' && i + 1 < sql.length() && sql.charAt(i + 1) == '*') {
        // Comentarios executaveis do MySQL podem conter SQL que o roteador nao ve.
        if (i + 2 < sql.length() && sql.charAt(i + 2) == '!') return null;
        int end = sql.indexOf("*/", i + 2);
        if (end < 0) return null;
        i = end + 2;
        continue;
      }
      int start = i;
      if (ch == '\'' || ch == '"') {
        i++;
        boolean closed = false;
        while (i < sql.length()) {
          if (sql.charAt(i) == '\\' && i + 1 < sql.length()) { i += 2; continue; }
          if (sql.charAt(i) == ch) {
            if (i + 1 < sql.length() && sql.charAt(i + 1) == ch) { i += 2; continue; }
            i++; closed = true; break;
          }
          i++;
        }
        if (!closed) return null;
        tokens.add(new Token(sql.substring(start, i), start, i, depth, Kind.STRING));
      } else if (ch == '`') {
        i++;
        StringBuilder identifier = new StringBuilder();
        boolean closed = false;
        while (i < sql.length()) {
          if (sql.charAt(i) == '`') {
            if (i + 1 < sql.length() && sql.charAt(i + 1) == '`') {
              identifier.append('`'); i += 2; continue;
            }
            i++; closed = true; break;
          }
          identifier.append(sql.charAt(i++));
        }
        if (!closed) return null;
        tokens.add(new Token(identifier.toString(), start, i, depth, Kind.IDENT));
      } else if (Character.isLetter(ch) || ch == '_') {
        i++;
        while (i < sql.length() && (Character.isLetterOrDigit(sql.charAt(i))
            || sql.charAt(i) == '_' || sql.charAt(i) == '$')) i++;
        tokens.add(new Token(sql.substring(start, i), start, i, depth, Kind.IDENT));
      } else if (ch == '.') {
        i++;
        tokens.add(new Token(".", start, i, depth, Kind.DOT));
      } else if (ch == ',') {
        i++;
        tokens.add(new Token(",", start, i, depth, Kind.COMMA));
      } else if (ch == '(') {
        i++;
        tokens.add(new Token("(", start, i, depth, Kind.LPAREN));
        depth++;
      } else if (ch == ')') {
        depth--;
        if (depth < 0) return null;
        i++;
        tokens.add(new Token(")", start, i, depth, Kind.RPAREN));
      } else if (ch == ';') {
        return null; // SQL unico; o executor ja remove o ';' final.
      } else {
        i++;
        tokens.add(new Token(sql.substring(start, i), start, i, depth, Kind.OTHER));
      }
    }
    return depth == 0 ? tokens : null;
  }
}
