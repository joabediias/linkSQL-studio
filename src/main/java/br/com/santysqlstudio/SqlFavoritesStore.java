package br.com.santysqlstudio;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.Base64;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Favoritos separados do histórico, preservados mesmo quando ele atinge o limite. */
final class SqlFavoritesStore {
  private static final Path FILE = Paths.get(System.getProperty("user.home"),
      ".santysql-studio", "sql-favorites.txt");
  private SqlFavoritesStore() {}

  static Set<String> read() throws IOException {
    Set<String> values = new LinkedHashSet<>();
    if (!Files.isRegularFile(FILE)) return values;
    for (String line : Files.readAllLines(FILE, StandardCharsets.UTF_8)) {
      try {
        String sql = new String(Base64.getDecoder().decode(line), StandardCharsets.UTF_8);
        if (!sql.isBlank()) values.add(sql);
      } catch (IllegalArgumentException ignored) { /* Linha antiga/corrompida não impede uso. */ }
    }
    return values;
  }

  static void write(Set<String> statements) throws IOException {
    Files.createDirectories(FILE.getParent());
    List<String> encoded = statements.stream()
        .filter(sql -> sql != null && !sql.isBlank())
        .map(sql -> Base64.getEncoder().encodeToString(sql.getBytes(StandardCharsets.UTF_8)))
        .toList();
    Path pending = FILE.resolveSibling(FILE.getFileName() + ".tmp");
    Files.write(pending, encoded, StandardCharsets.UTF_8);
    try {
      Files.move(pending, FILE, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    } catch (java.nio.file.AtomicMoveNotSupportedException e) {
      Files.move(pending, FILE, StandardCopyOption.REPLACE_EXISTING);
    }
  }
}
