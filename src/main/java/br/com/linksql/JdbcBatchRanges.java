package br.com.linksql;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.sql.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.regex.*;

/** Discovers occupied six-digit source codes, then builds non-overlapping SQL ranges. */
final class JdbcBatchRanges {
  private static final Pattern ALIAS = Pattern.compile("(?i)\\b(SRV_[a-z0-9_]+)\\.");
  private static final Pattern IDENTIFIER = Pattern.compile("[a-zA-Z_][a-zA-Z0-9_]*");
  private static final DateTimeFormatter CLOCK = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
  private static final Path LOG = Paths.get(System.getProperty("user.home", "."), ".linksql", "logs", "jdbc-exportacao.log");
  private JdbcBatchRanges() { }

  static synchronized void log(String message) {
    String line = "[" + CLOCK.format(LocalDateTime.now()) + "] " + message;
    System.err.println("[LinkSQL JDBC] " + line);
    try {
      Files.createDirectories(LOG.getParent());
      Files.writeString(LOG, line + System.lineSeparator(), StandardCharsets.UTF_8,
          StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    } catch (Exception ignored) { /* fail-safe; logging cannot abort export */ }
  }

  static List<String[]> groupCodes(Collection<String> codes, int batchSize) {
    if (batchSize < 1 || batchSize > 1000) throw new IllegalArgumentException("Lote fora de 1..1000");
    TreeSet<String> sorted = new TreeSet<>();
    for (String code : codes) {
      if (code != null && code.matches("[0-9]{6}")) sorted.add(code);
    }
    List<String[]> ranges = new ArrayList<>();
    String first = null, last = null;
    int count = 0;
    for (String code : sorted) {
      if (count == 0) first = code;
      last = code;
      count++;
      if (count >= batchSize) {
        ranges.add(new String[]{first, last});
        count = 0;
      }
    }
    if (count > 0) ranges.add(new String[]{first, last});
    return ranges;
  }

  static String discoverySql(String table, String column) {
    if (!IDENTIFIER.matcher(table).matches() || !IDENTIFIER.matcher(column).matches())
      throw new IllegalArgumentException("Tabela ou coluna de lote invalida");
    String query = "SELECT DISTINCT TRIM(" + column + ") FROM " + table
        + " WHERE " + column + " REGEXP '^[0-9]{6}
                                 int batchSize) throws SQLException {
    if (!IDENTIFIER.matcher(table).matches() || !IDENTIFIER.matcher(column).matches())
      throw new SQLException("Tabela ou coluna de lote invalida");
    if (batchSize < 1 || batchSize > 1000) throw new SQLException("Tamanho de lote fora de 1..1000");
    Set<String> aliases = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
    Matcher matcher = ALIAS.matcher(sql);
    while (matcher.find()) aliases.add(matcher.group(1));
    if (aliases.isEmpty()) throw new SQLException("Nenhuma origem SRV_ identificada no SQL");
    TreeSet<String> codes = new TreeSet<>();
    String discoverySql = discoverySql(table, column);
    log("Inicio descoberta de codigos: " + table + "." + column + ", tamanho por lote=" + batchSize);
    long begun = System.nanoTime();
    for (JdbcConnectionProfile profile : registry.allProfiles()) {
      if (!aliases.remove(profile.name())) continue;
      if (profile.type() != SqlDatabaseType.MYSQL) {
        throw new SQLException("Descoberta por codigos suportada somente em origem MySQL: " + profile.name());
      }
      long started = System.nanoTime();
      int fromSource = 0;
      try (Connection connection = registry.source(profile.name()).getConnection();
           Statement st = connection.createStatement()) {
        st.setQueryTimeout(180);
        st.setFetchSize(500);
        try (ResultSet rs = st.executeQuery(discoverySql)) {
          while (rs.next()) {
            String code = rs.getString(1);
            if (code != null && code.matches("[0-9]{6}")) {
              codes.add(code);
              fromSource++;
            }
          }
        }
      }
      log("Origem " + profile.name() + ": " + fromSource + " codigos elegiveis; "
          + ((System.nanoTime() - started) / 1_000_000) + " ms");
    }
    if (!aliases.isEmpty()) throw new SQLException("Fontes nao cadastradas: " + aliases);
    if (codes.isEmpty()) throw new SQLException("Nenhum codigo elegivel encontrado nas fontes selecionadas");
    List<String[]> result = groupCodes(codes, batchSize);
    log("Descoberta concluida: " + codes.size() + " codigos distintos, " + result.size()
        + " faixas ocupadas; " + ((System.nanoTime() - begun) / 1_000_000) + " ms");
    return result;
  }
}
 AND SQL_DELETED = 'F'";
    if ("CAD_CLI0".equalsIgnoreCase(table) && "CLI_CODIGO".equalsIgnoreCase(column)) {
      query += " AND UPPER(TRIM(COALESCE(CLI_EXCLUIDO, ''))) <> 'S'"
          + " AND (COALESCE(YEAR(CLI_DTINAT), 0) < 1900 OR CLI_DTATIV > CLI_DTINAT)";
    }
    return query;
  }

  static List<String[]> discover(SqlConnectionRegistry registry, String sql, String table, String column,
                                 int batchSize) throws SQLException {
    if (!IDENTIFIER.matcher(table).matches() || !IDENTIFIER.matcher(column).matches())
      throw new SQLException("Tabela ou coluna de lote invalida");
    if (batchSize < 1 || batchSize > 1000) throw new SQLException("Tamanho de lote fora de 1..1000");
    Set<String> aliases = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
    Matcher matcher = ALIAS.matcher(sql);
    while (matcher.find()) aliases.add(matcher.group(1));
    if (aliases.isEmpty()) throw new SQLException("Nenhuma origem SRV_ identificada no SQL");
    TreeSet<String> codes = new TreeSet<>();
    String discoverySql = discoverySql(table, column);
    log("Inicio descoberta de codigos: " + table + "." + column + ", tamanho por lote=" + batchSize);
    long begun = System.nanoTime();
    for (JdbcConnectionProfile profile : registry.allProfiles()) {
      if (!aliases.remove(profile.name())) continue;
      if (profile.type() != SqlDatabaseType.MYSQL) {
        throw new SQLException("Descoberta por codigos suportada somente em origem MySQL: " + profile.name());
      }
      long started = System.nanoTime();
      int fromSource = 0;
      try (Connection connection = registry.source(profile.name()).getConnection();
           Statement st = connection.createStatement()) {
        st.setQueryTimeout(180);
        st.setFetchSize(500);
        try (ResultSet rs = st.executeQuery(discoverySql)) {
          while (rs.next()) {
            String code = rs.getString(1);
            if (code != null && code.matches("[0-9]{6}")) {
              codes.add(code);
              fromSource++;
            }
          }
        }
      }
      log("Origem " + profile.name() + ": " + fromSource + " codigos elegiveis; "
          + ((System.nanoTime() - started) / 1_000_000) + " ms");
    }
    if (!aliases.isEmpty()) throw new SQLException("Fontes nao cadastradas: " + aliases);
    if (codes.isEmpty()) throw new SQLException("Nenhum codigo elegivel encontrado nas fontes selecionadas");
    List<String[]> result = groupCodes(codes, batchSize);
    log("Descoberta concluida: " + codes.size() + " codigos distintos, " + result.size()
        + " faixas ocupadas; " + ((System.nanoTime() - begun) / 1_000_000) + " ms");
    return result;
  }
}
