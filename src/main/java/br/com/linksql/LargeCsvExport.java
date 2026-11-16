package br.com.linksql;

import java.io.*;
import java.math.BigDecimal;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.sql.*;
import java.util.*;
import java.util.concurrent.atomic.*;
import java.util.function.Consumer;
import java.util.regex.*;

/** Executa SQL federado por faixas e escreve um CSV unico, sem usar a JTable. */
final class LargeCsvExport {
  private static final Pattern ID = Pattern.compile("[A-Za-z_][A-Za-z_0-9]*");
  private static final Pattern MARKER = Pattern.compile("/\\*\\s*LINKSQL_LOTE:([A-Za-z_][A-Za-z_0-9]*)\\.([A-Za-z_][A-Za-z_0-9]*)\\s*\\*/");
  private static final Pattern SOURCE = Pattern.compile("(?i)\\b(SRV_[A-Za-z0-9_]+)\\.");
  record Config(String table, String column, int width, int prefixWidth, int batchSize, char delimiter) {
    Config {
      if (table == null || !ID.matcher(table).matches() || column == null || !ID.matcher(column).matches())
        throw new IllegalArgumentException("Nome da tabela/campo invalido.");
      if (width < 2 || width > 12 || prefixWidth < 1 || prefixWidth >= width || batchSize < 1 || batchSize > 10000)
        throw new IllegalArgumentException("Tamanho de codigo/prefixo/lote invalido.");
    }
  }
  record Summary(long rows, BigDecimal credit, int chunks) {}
  private record Range(String start, String end) { public String toString() { return start + "-" + end; } }
  private LargeCsvExport() {}
  static boolean isTemplate(String sql) { return MARKER.matcher(sql).find(); }
  static String partition(String sql, String start, String end) {
    Matcher m = MARKER.matcher(sql);
    StringBuffer result = new StringBuffer();
    int count = 0;
    while (m.find()) {
      count++;
      String predicate = " AND " + m.group(1) + "." + m.group(2)
          + " >= '" + start + "' AND " + m.group(1) + "." + m.group(2) + " <= '" + end + "' ";
      m.appendReplacement(result, Matcher.quoteReplacement(predicate));
    }
    m.appendTail(result);
    if (count == 0) throw new IllegalArgumentException("SQL sem marcadores /*LINKSQL_LOTE:ALIAS.CAMPO*/.");
    // Calcite JDBC espera uma unica instrução sem o terminador final.
    String executable = result.toString().stripTrailing();
    return executable.endsWith(";") ? executable.substring(0, executable.length() - 1).stripTrailing() : executable;
  }
  private static String padded(long n, int width) { return String.format(Locale.ROOT, "%0" + width + "d", n); }
  private static String fingerprint(String sql, Config cfg) throws Exception {
    byte[] hash = MessageDigest.getInstance("SHA-256").digest((sql + cfg).getBytes(StandardCharsets.UTF_8));
    StringBuilder s = new StringBuilder();
    for (byte b : hash) s.append(String.format("%02x", b & 255));
    return s.toString();
  }
  private static List<Range> discover(SqlConnectionRegistry registry, String sql, Config cfg,
                                      AtomicBoolean canceled, Consumer<String> report) throws Exception {
    TreeSet<String> referenced = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
    Matcher matcher = SOURCE.matcher(sql);
    while (matcher.find()) referenced.add(matcher.group(1));
    TreeSet<String> prefixes = new TreeSet<>();
    for (JdbcConnectionProfile profile : registry.allProfiles()) {
      if (!referenced.remove(profile.name())) continue;
      if (profile.type() != SqlDatabaseType.MYSQL) throw new SQLException("Descoberta automatica disponivel para MySQL: " + profile.name());
      if (canceled.get()) throw new InterruptedException("Cancelado");
      report.accept("Lendo prefixos: " + profile.name());
      String query = "SELECT DISTINCT LEFT(TRIM(" + cfg.column + "), " + cfg.prefixWidth
          + ") FROM " + cfg.table + " WHERE " + cfg.column + " REGEXP '^[0-9]{" + cfg.width + "}$'";
      try (Connection conn = registry.source(profile.name()).getConnection(); Statement st = conn.createStatement()) {
        st.setQueryTimeout(180);
        try (ResultSet rs = st.executeQuery(query)) {
          while (rs.next()) {
            String p = rs.getString(1);
            if (p != null && p.matches("[0-9]{" + cfg.prefixWidth + "}")) prefixes.add(p);
          }
        }
      }
    }
    if (!referenced.isEmpty()) throw new SQLException("Fontes nao cadastradas: " + referenced);
    if (prefixes.isEmpty()) throw new SQLException("Nenhum prefixo numerico de largura " + cfg.width + " encontrado.");
    long suffix = 1;
    for (int n = cfg.prefixWidth; n < cfg.width; n++) suffix *= 10;
    List<Range> ranges = new ArrayList<>();
    for (String prefix : prefixes) {
      long base = Long.parseLong(prefix) * suffix;
      for (long x = 0; x < suffix; x += cfg.batchSize)
        ranges.add(new Range(padded(base + x, cfg.width), padded(base + Math.min(suffix - 1, x + cfg.batchSize - 1), cfg.width)));
    }
    return ranges;
  }
  /** Exposes bounded ranges for a streaming JDBC ResultSet (no CSV writer). */
  static List<String[]> jdbcRanges(SqlConnectionRegistry registry, String sql,
                                   String table, String column, int batchSize) throws Exception {
    Config config = new Config(table, column, 6, 3, batchSize, ';');
    List<Range> found = discover(registry, sql, config, new AtomicBoolean(false), value -> {});
    List<String[]> result = new ArrayList<>();
    for (Range range : found) result.add(new String[]{range.start(), range.end()});
    return result;
  }

  private static void writeCell(Writer w, String value, char delimiter) throws IOException {
    if (value == null) value = "";
    boolean quote = value.indexOf('"') >= 0 || value.indexOf(delimiter) >= 0
        || value.indexOf('\n') >= 0 || value.indexOf('\r') >= 0;
    if (quote) w.write('"');
    for (int i = 0; i < value.length(); i++) {
      char c = value.charAt(i);
      if (c == '"') w.write('"');
      w.write(c);
    }
    if (quote) w.write('"');
  }
  private static void writeRow(Writer w, String[] values, char delimiter) throws IOException {
    for (int i = 0; i < values.length; i++) {
      if (i != 0) w.write(delimiter);
      writeCell(w, values[i], delimiter);
    }
    w.write("\r\n");
  }
  private static long number(Properties p, String key) {
    return Long.parseLong(p.getProperty(key, "0"));
  }
  private static void checkpoint(Path file, Properties props) throws IOException {
    Path tmp = Path.of(file + ".tmp");
    try (OutputStream out = Files.newOutputStream(tmp)) { props.store(out, "LinkSQL CSV resumivel"); }
    try { Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE); }
    catch (AtomicMoveNotSupportedException ex) { Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING); }
  }
  static Summary run(SqlConnectionRegistry registry, String template, Config cfg, Path destination,
                     AtomicBoolean canceled, AtomicReference<Statement> active,
                     Consumer<String> progress) throws Exception {
    if (!isTemplate(template)) throw new SQLException("Abra um SQL com marcadores /*LINKSQL_LOTE:ALIAS.CAMPO*/.");
    Path output = destination.toAbsolutePath().normalize();
    if (output.getParent() != null) Files.createDirectories(output.getParent());
    Path partial = Path.of(output + ".part"), state = Path.of(output + ".checkpoint");
    String hash = fingerprint(template, cfg);
    Properties props = new Properties();
    List<Range> ranges = new ArrayList<>();
    if (Files.exists(state)) {
      try (InputStream in = Files.newInputStream(state)) { props.load(in); }
      if (!hash.equals(props.getProperty("fingerprint"))) throw new SQLException("Checkpoint de outro SQL/configuracao.");
      if (!Files.exists(partial)) throw new SQLException("CSV parcial desapareceu; nao e possivel retomar.");
      for (String segment : props.getProperty("segments", "").split(",")) {
        if (segment.isBlank()) continue;
        String[] parts = segment.split("-");
        ranges.add(new Range(parts[0], parts[1]));
      }
      try (FileChannel c = FileChannel.open(partial, StandardOpenOption.WRITE)) { c.truncate(number(props, "offset")); }
      progress.accept("Retomando do ultimo lote confirmado.");
    } else {
      if (Files.exists(partial) || Files.exists(output)) throw new SQLException("Arquivo destino ou parcial ja existe.");
      ranges = discover(registry, template, cfg, canceled, progress);
      StringJoiner sj = new StringJoiner(",");
      for (Range r : ranges) sj.add(r.toString());
      props.setProperty("fingerprint", hash); props.setProperty("segments", sj.toString());
      props.setProperty("index", "0"); props.setProperty("offset", "0");
      props.setProperty("rows", "0"); props.setProperty("credit", "0");
      Files.createFile(partial);
      checkpoint(state, props);
    }
    int first = Math.toIntExact(number(props, "index"));
    long rows = number(props, "rows");
    BigDecimal credit = new BigDecimal(props.getProperty("credit", "0"));
    boolean headerWritten = number(props, "offset") > 0;
    for (int i = first; i < ranges.size(); i++) {
      if (canceled.get()) throw new InterruptedException("Cancelado — retome pelo mesmo arquivo.");
      Range range = ranges.get(i);
      String sql = partition(template, range.start, range.end);
      progress.accept("Lote " + (i + 1) + "/" + ranges.size() + " (" + range + "): " + rows + " registros");
      try (Connection conn = registry.openFederatedQueryConnection(sql); Statement st = conn.createStatement()) {
        active.set(st);
        st.setQueryTimeout(900);
        st.setFetchSize(100);
        try (ResultSet rs = st.executeQuery(sql);
             BufferedWriter out = Files.newBufferedWriter(partial, StandardCharsets.UTF_8, StandardOpenOption.APPEND)) {
          ResultSetMetaData md = rs.getMetaData();
          int n = md.getColumnCount();
          if (!headerWritten) {
            String[] headers = new String[n];
            for (int c = 0; c < n; c++) headers[c] = md.getColumnLabel(c + 1);
            writeRow(out, headers, cfg.delimiter);
            headerWritten = true;
          }
          int creditColumn = 0;
          for (int c = 1; c <= n; c++)
            if ("LIMITE_CREDITO_APROVADO".equalsIgnoreCase(md.getColumnLabel(c))) creditColumn = c;
          while (rs.next()) {
            if (canceled.get()) throw new InterruptedException("Cancelado — retome pelo mesmo arquivo.");
            String[] values = new String[n];
            for (int c = 0; c < n; c++) {
              Object v = rs.getObject(c + 1);
              values[c] = v == null ? "" : v.toString();
            }
            writeRow(out, values, cfg.delimiter);
            rows++;
            if (creditColumn > 0 && !values[creditColumn - 1].isBlank())
              credit = credit.add(new BigDecimal(values[creditColumn - 1].trim()));
          }
        }
      } finally { active.set(null); }
      try (FileChannel c = FileChannel.open(partial, StandardOpenOption.WRITE)) { c.force(true); }
      props.setProperty("index", String.valueOf(i + 1));
      props.setProperty("offset", String.valueOf(Files.size(partial)));
      props.setProperty("rows", String.valueOf(rows));
      props.setProperty("credit", credit.toPlainString());
      checkpoint(state, props);
    }
    try { Files.move(partial, output, StandardCopyOption.ATOMIC_MOVE); }
    catch (AtomicMoveNotSupportedException ex) { Files.move(partial, output); }
    Files.deleteIfExists(state);
    progress.accept("Exportado " + rows + " registros em " + output);
    return new Summary(rows, credit, ranges.size());
  }
}
