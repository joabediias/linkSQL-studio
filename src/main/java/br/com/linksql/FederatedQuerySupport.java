package br.com.linksql;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Reconhece as fontes logicas mencionadas em SQL Calcite, sem alterar o texto.
 * Um token dentro de string ou comentario nunca cria uma referencia a conexao.
 * Nao e um parser SQL completo: expressoes nao reconhecidas sao delegadas ao Calcite.
 */
final class FederatedQuerySupport {
  private FederatedQuerySupport() {}

  record Analysis(List<String> sources, List<String> unknownSources) {
    boolean referencesMultipleSources() { return sources.size() > 1; }
  }

  private record Token(String text, boolean identifier) {
    boolean is(String word) { return identifier && text.equalsIgnoreCase(word); }
  }

  static Analysis analyze(String sql, Collection<JdbcConnectionProfile> profiles) {
    Map<String, JdbcConnectionProfile> registered = new LinkedHashMap<>();
    for (JdbcConnectionProfile p : profiles) registered.put(p.name().toUpperCase(Locale.ROOT), p);
    Set<String> found = new LinkedHashSet<>();
    Set<String> unknown = new LinkedHashSet<>();
    List<Token> tokens = lex(sql == null ? "" : sql);
    // Retorno conservador quando a entrada possui aspas/comentarios sem fechamento.
    if (tokens == null) return new Analysis(List.of(), List.of());
    for (int i = 0; i + 2 < tokens.size(); i++) {
      Token qualifier = tokens.get(i);
      if (!qualifier.identifier || !tokens.get(i + 1).text.equals(".")
          || !tokens.get(i + 2).identifier) continue;
      // Evita interpretar a segunda parte de nomes com tres ou mais segmentos.
      if (i > 0 && tokens.get(i - 1).text.equals(".")) continue;
      String key = qualifier.text.toUpperCase(Locale.ROOT);
      JdbcConnectionProfile profile = registered.get(key);
      if (profile != null) {
        found.add(profile.name());
      } else if (key.startsWith("SRV_") && i > 0
          && (tokens.get(i - 1).is("FROM") || tokens.get(i - 1).is("JOIN")
              || tokens.get(i - 1).text.equals(","))) {
        unknown.add(qualifier.text);
      }
    }
    return new Analysis(List.copyOf(found), List.copyOf(unknown));
  }

  /** Fontes registradas na conexao Calcite. Sem referencias explicitas, inclui todas. */
  static Set<String> sourcesToRegister(String sql, Collection<JdbcConnectionProfile> profiles) {
    Analysis analysis = analyze(sql, profiles);
    Set<String> selected = new LinkedHashSet<>();
    for (String name : analysis.sources()) selected.add(name.toUpperCase(Locale.ROOT));
    if (selected.isEmpty())
      for (JdbcConnectionProfile p : profiles) selected.add(p.name().toUpperCase(Locale.ROOT));
    return selected;
  }

  static void validate(String sql, Collection<JdbcConnectionProfile> profiles) throws SQLException {
    Analysis analysis = analyze(sql, profiles);
    if (!analysis.unknownSources().isEmpty()) {
      String available = profiles.stream().map(JdbcConnectionProfile::name)
          .reduce((a, b) -> a + ", " + b).orElse("nenhuma");
      throw new SQLException("Conexao logica nao cadastrada: " + String.join(", ", analysis.unknownSources())
          + ". Conexoes disponiveis: " + available
          + ". Verifique o prefixo antes do nome da tabela.");
    }
  }

  static String executionLabel(String sql, Collection<JdbcConnectionProfile> profiles) {
    Analysis analysis = analyze(sql, profiles);
    if (analysis.sources().isEmpty()) return "Calcite federado";
    Map<String, SqlDatabaseType> types = new LinkedHashMap<>();
    for (JdbcConnectionProfile p : profiles) types.put(p.name(), p.type());
    List<String> labels = new ArrayList<>();
    for (String name : analysis.sources()) labels.add(name + " [" + types.get(name) + "]");
    return "Calcite federado (" + String.join(" + ", labels) + ")";
  }

  /** Melhora mensagens de erros Calcite/JDBC sem substituir a causa original. */
  static SQLException explain(SQLException failure) {
    String message = failure.getMessage() == null ? "" : failure.getMessage();
    String lower = message.toLowerCase(Locale.ROOT);
    String hint = null;
    if (lower.contains("cannot apply") || lower.contains("cannot cast")
        || lower.contains("type mismatch") || lower.contains("incompatible types")
        || lower.contains("invalid character value for cast")) {
      hint = "Dica LinkSQL: em JOINs entre fornecedores, confira os tipos das colunas. "
          + "Se forem diferentes, converta explicitamente com CAST para tipos compativeis.";
    } else if (lower.contains("not found") || lower.contains("unknown table")
        || lower.contains("unknown column") || lower.contains("does not exist")) {
      hint = "Dica LinkSQL: confirme o alias da conexao, o schema e a grafia das tabelas/colunas. "
          + "No SQL federado, use aspas duplas para identificadores que exigem preservar maiusculas/minusculas.";
    } else if (lower.contains("timeout waiting for idle object")
        || lower.contains("borrowmaxwaitduration")) {
      hint = "Dica LinkSQL: o pool JDBC esgotou as conexoes disponiveis ou alguma "
          + "conexao permaneceu ocupada. Confira outros SELECTs simultaneos, "
          + "o tempo de resposta das origens e os limites do servidor. "
          + "A versao 2.5.1 amplia o pool sob demanda para consultas federadas.";
    } else if (lower.contains("timed out") || lower.contains("timeout")
        || lower.contains("connection refused") || lower.contains("connect exception")) {
      hint = "Dica LinkSQL: verifique se todas as origens do JOIN estao acessiveis "
          + "e experimente aplicar filtros seletivos antes de combinar tabelas grandes.";
    }
    if (hint == null) return failure;
    return new SQLException(message + "\n\n" + hint, failure.getSQLState(),
        failure.getErrorCode(), failure);
  }

  private static List<Token> lex(String text) {
    List<Token> tokens = new ArrayList<>();
    for (int i = 0; i < text.length();) {
      char c = text.charAt(i);
      if (Character.isWhitespace(c)) { i++; continue; }
      if (c == '-' && i + 1 < text.length() && text.charAt(i + 1) == '-') {
        i += 2;
        while (i < text.length() && text.charAt(i) != '\n') i++;
        continue;
      }
      if (c == '#') {
        while (i < text.length() && text.charAt(i) != '\n') i++;
        continue;
      }
      if (c == '/' && i + 1 < text.length() && text.charAt(i + 1) == '*') {
        int close = text.indexOf("*/", i + 2);
        if (close < 0) return null;
        i = close + 2; continue;
      }
      if (c == '\'') {
        i++;
        boolean closed = false;
        while (i < text.length()) {
          if (text.charAt(i) == '\'') {
            i++;
            if (i < text.length() && text.charAt(i) == '\'') { i++; continue; }
            closed = true; break;
          }
          if (text.charAt(i) == '\\' && i + 1 < text.length()) i++;
          i++;
        }
        if (!closed) return null;
        continue;
      }
      if (c == '$') {
        int j = i + 1;
        while (j < text.length() && (Character.isLetterOrDigit(text.charAt(j)) || text.charAt(j) == '_')) j++;
        if (j < text.length() && text.charAt(j) == '$') {
          String tag = text.substring(i, j + 1);
          int close = text.indexOf(tag, j + 1);
          if (close < 0) return null;
          i = close + tag.length();
          continue;
        }
      }
      if (c == '"' || c == '`' || c == '[') {
        char closing = c == '[' ? ']' : c;
        i++;
        StringBuilder ident = new StringBuilder();
        boolean closed = false;
        while (i < text.length()) {
          char ch = text.charAt(i++);
          if (ch == closing) {
            if (i < text.length() && text.charAt(i) == closing) {
              ident.append(closing); i++; continue;
            }
            closed = true; break;
          }
          ident.append(ch);
        }
        if (!closed) return null;
        tokens.add(new Token(ident.toString(), true));
        continue;
      }
      if (Character.isLetter(c) || c == '_') {
        int start = i++;
        while (i < text.length() && (Character.isLetterOrDigit(text.charAt(i))
            || text.charAt(i) == '_' || text.charAt(i) == '$')) i++;
        tokens.add(new Token(text.substring(start, i), true));
        continue;
      }
      tokens.add(new Token(String.valueOf(c), false));
      i++;
    }
    return tokens;
  }
}
