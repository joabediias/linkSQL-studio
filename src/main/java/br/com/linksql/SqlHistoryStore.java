package br.com.linksql;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

/** Histórico de SQL separado da interface. Formato Base64 original preservado. */
final class SqlHistoryStore {
  private static final Path FILE = LinkSqlPaths.sqlHistoryFile();
  private static final int LIMIT = 100;
  private SqlHistoryStore() {}

  static List<String> read() throws IOException {
    if (!Files.exists(FILE)) return new ArrayList<>();
    List<String> entries = new ArrayList<>();
    for (String line : Files.readAllLines(FILE, StandardCharsets.UTF_8)) {
      try { entries.add(new String(Base64.getDecoder().decode(line), StandardCharsets.UTF_8)); }
      catch (IllegalArgumentException ignored) { }
    }
    return entries;
  }

  static void remember(String sql) throws IOException {
    List<String> entries = read();
    entries.remove(sql);
    entries.add(0, sql);
    if (entries.size() > LIMIT) entries = new ArrayList<>(entries.subList(0, LIMIT));
    Files.createDirectories(FILE.getParent());
    List<String> encoded = new ArrayList<>();
    for (String entry : entries) encoded.add(Base64.getEncoder().encodeToString(entry.getBytes(StandardCharsets.UTF_8)));
    Files.write(FILE, encoded, StandardCharsets.UTF_8);
  }
}
