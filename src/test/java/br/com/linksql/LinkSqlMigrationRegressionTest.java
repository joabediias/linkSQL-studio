package br.com.linksql;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;

/** Testes isolados de migração de caminho; não abrem bancos nem DPAPI. */
public final class LinkSqlMigrationRegressionTest {
  private static int checks;
  private static void check(boolean result, String label) {
    if (!result) throw new AssertionError(label);
    checks++;
  }
  public static void main(String[] args) throws Exception {
    Path home = Files.createTempDirectory("linksql-rename-test-");
    String originalHome = System.getProperty("user.home");
    System.setProperty("user.home", home.toString());
    try {
      Path legacy = home.resolve(".santysql-studio");
      Path legacyConfig = legacy.resolve("config/connections.json");
      Path legacyVault = legacy.resolve("config/credentials.dpapi.json");
      Files.createDirectories(legacyConfig.getParent());
      String profile = "{\"connections\":[{\"name\":\"SRV_TEST\",\"type\":\"MYSQL\"}]}";
      String vault = "{\"SRV_TEST\":{\"protected\":\"ZmFrZQ==\",\"duration\":\"PERMANENT\"}}";
      Files.writeString(legacyConfig, profile, StandardCharsets.UTF_8);
      Files.writeString(legacyVault, vault, StandardCharsets.UTF_8);
      Files.writeString(legacy.resolve("sql-session.json"), "{\"tabs\":[]}");
      Files.writeString(legacy.resolve("sql-history.txt"), "U0VMRUNUIDE=\n");
      Files.writeString(legacy.resolve("sql-favorites.txt"), "U0VMRUNUIDg==\n");
      Path newProfiles = LinkSqlPaths.prepareConnectionProfilesFile();
      check(newProfiles.equals(home.resolve(".linksql/config/connections.json")), "nome da pasta nova");
      check(Files.readString(newProfiles).equals(profile), "perfil preservado");
      check(Files.readString(LinkSqlPaths.credentialFile()).equals(vault), "arquivo DPAPI importado sem decifrar");
      check(Files.readString(LinkSqlPaths.sqlSessionFile()).contains("tabs"), "sessao migrada");
      check(Files.isRegularFile(LinkSqlPaths.sqlHistoryFile()), "historico migrado");
      check(Files.isRegularFile(LinkSqlPaths.sqlFavoritesFile()), "favoritos migrados");
      check(Files.isRegularFile(home.resolve(".linksql/.migration-complete")), "marcador unico da migracao");
      check(Files.readString(legacyConfig).equals(profile), "origem preservada");
      check(Files.readString(legacyVault).equals(vault), "cofre original nao alterado");

      String edited = "{\"connections\":[{\"name\":\"SRV_NEW\"}]}";
      Files.writeString(newProfiles, edited);
      LinkSqlPaths.mirrorForJdbc05(newProfiles);
      check(Files.readString(legacyConfig).equals(edited), "JDBC 0.5 le alteracoes do perfil espelhado");
      String changedVault = "{\"SRV_NEW\":{\"protected\":\"YWJj\"}}";
      Files.writeString(LinkSqlPaths.credentialFile(), changedVault);
      LinkSqlPaths.mirrorForJdbc05(LinkSqlPaths.credentialFile());
      check(Files.readString(legacyVault).equals(changedVault), "cofre criptografado espelhado");
      Files.delete(LinkSqlPaths.credentialFile());
      LinkSqlPaths.userDirectory();
      check(!Files.exists(LinkSqlPaths.credentialFile()), "cofre removido nao e reimportado indevidamente");
      Files.writeString(legacyConfig, profile);
      LinkSqlPaths.userDirectory();
      check(Files.readString(newProfiles).equals(edited), "edicao nova nunca sobrescrita pelo legado");
      JsonNode json = new ObjectMapper().readTree(newProfiles.toFile());
      check(json.path("connections").get(0).path("name").asText().equals("SRV_NEW"), "perfil JSON valido");
      System.out.println("LinkSQL: " + checks + " testes de migracao APROVADOS.");
    } finally {
      System.setProperty("user.home", originalHome);
      try (var paths = Files.walk(home)) {
        paths.sorted(Comparator.reverseOrder()).forEach(path -> {
          try { Files.deleteIfExists(path); } catch (Exception ignored) {}
        });
      }
    }
  }
}
