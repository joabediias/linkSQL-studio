package br.com.linksql;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.Collection;

/** Lista permitida: nunca serializa cofre, senha, preferencias ou campos desconhecidos. */
final class ConnectionProfileTransfer {
  private static final ObjectMapper JSON = new ObjectMapper();
  private ConnectionProfileTransfer() {}

  static ObjectNode profile(JdbcConnectionProfile p) throws IOException {
    p.validate();
    String url = p.jdbcUrl();
    String decoded;
    try { decoded = java.net.URLDecoder.decode(url == null ? "" : url, StandardCharsets.UTF_8); }
    catch (IllegalArgumentException ex) { throw new IOException("URL JDBC inválida em " + p.name(), ex); }
    if (decoded.matches("(?is).*(?:password|passwd|pwd|token|secret|credential)\\s*[=:].*")
        || decoded.matches("(?is).*//[^/?;]*@.*")
        || decoded.matches("(?is)jdbc:oracle:thin:[^@].*@.*"))
      throw new IOException("A URL de " + p.name() + " contém credenciais. Remova-as da URL antes de transferir o perfil.");
    ObjectNode item = JSON.createObjectNode();
    item.put("name", p.name()); item.put("type", p.type().name());
    item.put("host", p.host()); item.put("port", p.port());
    item.put("database", p.database()); item.put("schema", p.schema());
    item.put("user", p.user()); item.put("passwordEnv", p.passwordEnv());
    item.put("jdbcUrl", url); item.put("readOnly", p.readOnly());
    return item;
  }

  static ObjectNode document(Collection<JdbcConnectionProfile> profiles) throws IOException {
    ObjectNode root = JSON.createObjectNode();
    root.put("formatVersion", 1);
    ArrayNode items = root.putArray("connections");
    for (JdbcConnectionProfile p : profiles) items.add(profile(p));
    return root;
  }

  static void export(Path path, Collection<JdbcConnectionProfile> profiles) throws IOException {
    java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream();
    JSON.writerWithDefaultPrettyPrinter().writeValue(output, document(profiles));
    byte[] bytes = output.toByteArray();
    Path target = path.toAbsolutePath();
    Path temporary = Files.createTempFile(target.getParent(), ".linksql-perfis-", ".tmp");
    try {
      Files.write(temporary, bytes);
      try { Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
      catch (AtomicMoveNotSupportedException ex) {
        Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
      }
    } finally { Files.deleteIfExists(temporary); }
  }
}
