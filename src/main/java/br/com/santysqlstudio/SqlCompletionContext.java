package br.com.santysqlstudio;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Metadados sintaticos leves para autocomplete; nao executa SQL nem tenta ser um parser completo. */
final class SqlCompletionContext {
  private SqlCompletionContext() {}

  record TableRef(String source, String table) {}
  record Reference(String alias, TableRef table, String cte) {}
  private record Tok(String value, int depth, int start, boolean ident) {
    boolean is(String text) { return value.equalsIgnoreCase(text); }
  }

  static final class Analysis {
    private final Map<String, List<String>> ctes = new LinkedHashMap<>();
    private final Map<String, Reference> refs = new LinkedHashMap<>();
    private final Set<TableRef> tables = new LinkedHashSet<>();
    List<String> cteNames() {
      List<String> names = new ArrayList<>();
      for (Reference ref : refs.values()) if (ref.cte != null) names.add(ref.cte);
      names.addAll(ctes.keySet());
      return names.stream().distinct().toList();
    }
    Reference reference(String alias) { return refs.get(alias.toLowerCase(Locale.ROOT)); }
    List<String> cteColumns(String name) { return ctes.getOrDefault(name.toLowerCase(Locale.ROOT), List.of()); }
    Set<TableRef> tables() { return Collections.unmodifiableSet(tables); }
  }

  static Analysis analyze(String sql, Collection<String> sources) {
    Analysis analysis = new Analysis();
    if (sql == null || sql.isBlank()) return analysis;
    List<Tok> tokens = lex(sql);
    if (tokens.isEmpty()) return analysis;
    parseCtes(tokens, analysis);
    for (int i = 0; i < tokens.size(); i++) {
      Tok t = tokens.get(i);
      if (!t.is("FROM") && !t.is("JOIN")) continue;
      int j = i + 1;
      if (j < tokens.size() && tokens.get(j).is("LATERAL")) j++;
      if (j >= tokens.size() || !tokens.get(j).ident) continue;
      String first = tokens.get(j).value;
      String schema = null;
      String tableName = first;
      int after = j + 1;
      if (after + 1 < tokens.size() && tokens.get(after).is(".") && tokens.get(after + 1).ident) {
        schema = first;
        tableName = tokens.get(after + 1).value;
        after += 2;
      }
      String alias = tableName;
      if (after < tokens.size() && tokens.get(after).is("AS")) after++;
      if (after < tokens.size() && tokens.get(after).ident && !reserved(tokens.get(after).value)) {
        alias = tokens.get(after).value;
      }
      String resolved = schema == null ? null : source(schema, sources);
      if (resolved != null) {
        TableRef ref = new TableRef(resolved, tableName);
        analysis.tables.add(ref);
        analysis.refs.put(alias.toLowerCase(Locale.ROOT), new Reference(alias, ref, null));
        analysis.refs.putIfAbsent(tableName.toLowerCase(Locale.ROOT), new Reference(tableName, ref, null));
      } else if (schema == null && analysis.ctes.containsKey(tableName.toLowerCase(Locale.ROOT))) {
        String cte = tableName.toLowerCase(Locale.ROOT);
        analysis.refs.put(alias.toLowerCase(Locale.ROOT), new Reference(alias, null, cte));
        analysis.refs.putIfAbsent(cte, new Reference(cte, null, cte));
      }
    }
    return analysis;
  }

  private static void parseCtes(List<Tok> tokens, Analysis a) {
    if (!tokens.get(0).is("WITH")) return;
    int i = 1;
    if (i < tokens.size() && tokens.get(i).is("RECURSIVE")) i++;
    while (i < tokens.size() && tokens.get(i).ident && tokens.get(i).depth == 0) {
      String cte = tokens.get(i++).value.toLowerCase(Locale.ROOT);
      List<String> names = new ArrayList<>();
      if (i < tokens.size() && tokens.get(i).is("(")) {
        i++;
        while (i < tokens.size() && tokens.get(i).depth == 1) {
          if (tokens.get(i).ident) names.add(tokens.get(i).value);
          i++;
        }
        if (i >= tokens.size() || !tokens.get(i).is(")")) break;
        i++;
      }
      if (i >= tokens.size() || !tokens.get(i).is("AS")) break;
      i++;
      if (i >= tokens.size() || !tokens.get(i).is("(")) break;
      i++;
      int selectStart = i;
      while (i < tokens.size() && tokens.get(i).depth > 0) i++;
      if (i >= tokens.size() || !tokens.get(i).is(")")) break;
      if (names.isEmpty()) names.addAll(inferColumns(tokens.subList(selectStart, i)));
      a.ctes.put(cte, List.copyOf(names));
      i++;
      if (i < tokens.size() && tokens.get(i).is(",")) i++;
      else break;
    }
  }

  // Extrai apenas projecoes simples e aliases explicitos; expressoes sem alias ficam fora.
  private static List<String> inferColumns(List<Tok> body) {
    int start = -1;
    int end = body.size();
    for (int i = 0; i < body.size(); i++) {
      if (body.get(i).is("SELECT") && body.get(i).depth == 1) { start = i + 1; break; }
    }
    if (start < 0) return List.of();
    for (int i = start; i < body.size(); i++) {
      if (body.get(i).depth == 1 && body.get(i).is("FROM")) { end = i; break; }
    }
    List<String> cols = new ArrayList<>();
    int segment = start;
    for (int i = start; i <= end; i++) {
      if (i == end || (body.get(i).depth == 1 && body.get(i).is(","))) {
        String name = projectionName(body.subList(segment, i));
        if (name != null) cols.add(name);
        segment = i + 1;
      }
    }
    return cols;
  }

  private static String projectionName(List<Tok> terms) {
    if (terms.isEmpty()) return null;
    int depth = 1;
    for (int i = 0; i + 1 < terms.size(); i++) {
      if (terms.get(i).depth == depth && terms.get(i).is("AS") && terms.get(i + 1).ident)
        return terms.get(i + 1).value;
    }
    // Coluna simples ou alias.coluna, sem transformacoes, DISTINCT aceito.
    int j = 0;
    if (terms.get(0).is("DISTINCT")) j++;
    if (j >= terms.size()) return null;
    if (terms.size() - j == 1 && terms.get(j).ident) return terms.get(j).value;
    if (terms.size() - j == 3 && terms.get(j).ident && terms.get(j + 1).is(".") && terms.get(j + 2).ident)
      return terms.get(j + 2).value;
    return null;
  }

  private static String source(String word, Collection<String> sources) {
    if (sources == null) return null;
    for (String source : sources) if (source.equalsIgnoreCase(word)) return source;
    return null;
  }

  private static boolean reserved(String word) {
    return Set.of("WHERE", "ON", "USING", "INNER", "LEFT", "RIGHT", "FULL", "OUTER", "JOIN",
        "CROSS", "GROUP", "ORDER", "HAVING", "LIMIT", "FETCH", "OFFSET", "UNION", "EXCEPT",
        "INTERSECT", "WINDOW", "QUALIFY", "FOR", "AND", "OR", "AS").contains(word.toUpperCase(Locale.ROOT));
  }

  /** Nao inicia sugestões em comentários, strings ou identificadores delimitados. */
  static boolean isCodePosition(String sql, int caret) {
    if (sql == null || caret < 0 || caret > sql.length()) return false;
    char quote = 0;
    boolean lineComment = false, blockComment = false;
    for (int i = 0; i < caret; i++) {
      char c = sql.charAt(i);
      char next = i + 1 < caret ? sql.charAt(i + 1) : 0;
      if (lineComment) {
        if (c == '\n' || c == '\r') lineComment = false;
        continue;
      }
      if (blockComment) {
        if (c == '*' && next == '/') { blockComment = false; i++; }
        continue;
      }
      if (quote != 0) {
        if (c == '\\' && quote != '`' && next != 0) { i++; continue; }
        if (c == quote) {
          if (next == quote) i++;
          else quote = 0;
        }
        continue;
      }
      if (c == '-' && next == '-' && (i + 2 >= caret || Character.isWhitespace(sql.charAt(i + 2)))) {
        lineComment = true; i++; continue;
      }
      if (c == '#') { lineComment = true; continue; }
      if (c == '/' && next == '*') { blockComment = true; i++; continue; }
      if (c == '\'' || c == '"' || c == '`') quote = c;
    }
    return quote == 0 && !lineComment && !blockComment;
  }

  private static List<Tok> lex(String sql) {
    List<Tok> tokens = new ArrayList<>();
    int depth = 0;
    for (int i = 0; i < sql.length();) {
      char ch = sql.charAt(i);
      if (Character.isWhitespace(ch)) { i++; continue; }
      if (ch == '-' && i + 2 < sql.length() && sql.charAt(i + 1) == '-' && Character.isWhitespace(sql.charAt(i + 2))) {
        while (i < sql.length() && sql.charAt(i) != '\n') i++;
        continue;
      }
      if (ch == '#') { while (i < sql.length() && sql.charAt(i) != '\n') i++; continue; }
      if (ch == '/' && i + 1 < sql.length() && sql.charAt(i + 1) == '*') {
        int end = sql.indexOf("*/", i + 2);
        if (end < 0) break;
        i = end + 2;
        continue;
      }
      int begin = i;
      if (ch == '\'' || ch == '"' || ch == '`') {
        i++;
        StringBuilder word = new StringBuilder();
        boolean closed = false;
        while (i < sql.length()) {
          char c = sql.charAt(i++);
          if (c == '\\' && ch != '`' && i < sql.length()) { word.append(sql.charAt(i++)); continue; }
          if (c == ch) {
            if (i < sql.length() && sql.charAt(i) == ch) { word.append(ch); i++; continue; }
            closed = true;
            break;
          }
          word.append(c);
        }
        if (!closed) break;
        if (ch != '\'') tokens.add(new Tok(word.toString(), depth, begin, true));
      } else if (Character.isLetter(ch) || ch == '_') {
        i++;
        while (i < sql.length() && (Character.isLetterOrDigit(sql.charAt(i)) || sql.charAt(i) == '_' || sql.charAt(i) == '$')) i++;
        tokens.add(new Tok(sql.substring(begin, i), depth, begin, true));
      } else {
        if (ch == ')') depth = Math.max(0, depth - 1);
        tokens.add(new Tok(String.valueOf(ch), depth, begin, false));
        if (ch == '(') depth++;
        i++;
      }
    }
    return tokens;
  }
}
