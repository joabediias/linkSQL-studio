package br.com.linksql;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Armazenamento JSON das abas SQL, sem dependencia de Swing. Nao conhece
 * conexoes JDBC, senhas DPAPI nem a implementacao do editor.
 *
 * Formato preservado: {"tabs":[{"title","sql","file"?,"savedText",
 * "defaultSource"}],"selected":0}. Arquivos legados sem defaultSource
 * continuam abrindo em modo federado.
 */
final class SqlSessionStore {
  private static final ObjectMapper JSON = new ObjectMapper();

  private SqlSessionStore() {}

  record Tab(String title, String sql, String file, String savedText, String defaultSource) {}

  record Session(List<Tab> tabs, int selected) {
    Session { tabs = List.copyOf(tabs); }
  }

  static Optional<Session> read(Path path) throws IOException {
    if (!Files.isRegularFile(path)) return Optional.empty();
    JsonNode root = JSON.readTree(path.toFile());
    if (root == null || !root.path("tabs").isArray()) return Optional.empty();
    List<Tab> tabs = new ArrayList<>();
    for (JsonNode item : root.path("tabs")) {
      tabs.add(new Tab(item.path("title").asText("SQL"),
          item.path("sql").asText(""),
          item.hasNonNull("file") ? item.get("file").asText() : null,
          item.path("savedText").asText(""),
          item.path("defaultSource").asText("")));
    }
    return Optional.of(new Session(tabs, root.path("selected").asInt(0)));
  }

  /** Gravacao atomica quando suportada pelo sistema de arquivos. */
  static void write(Path path, Session session) throws IOException {
    ObjectNode root = JSON.createObjectNode();
    ArrayNode tabs = root.putArray("tabs");
    for (Tab tab : session.tabs()) {
      ObjectNode item = tabs.addObject();
      item.put("title", tab.title());
      item.put("sql", tab.sql());
      if (tab.file() != null) item.put("file", tab.file());
      item.put("savedText", tab.savedText());
      item.put("defaultSource", tab.defaultSource());
    }
    root.put("selected", session.selected());
    Files.createDirectories(path.getParent());
    Path temporary = path.resolveSibling("sql-session.tmp");
    JSON.writerWithDefaultPrettyPrinter().writeValue(temporary.toFile(), root);
    try {
      Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING,
          StandardCopyOption.ATOMIC_MOVE);
    } catch (java.nio.file.AtomicMoveNotSupportedException ex) {
      Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING);
    }
  }
}
