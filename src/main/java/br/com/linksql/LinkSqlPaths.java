package br.com.linksql;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;

/**
 * Diretórios oficiais LinkSQL e migração não destrutiva do perfil anterior.
 * O diretório antigo só é usado para importar dados uma vez e manter
 * compatibilidade temporária com o driver JDBC 0.5 instalado no exportador.
 * Nunca remove o diretório antigo e nunca inclui senhas em texto puro.
 */
final class LinkSqlPaths {
  private LinkSqlPaths() {}
  private static final String LEGACY_DIR = ".santysql-studio"; // Somente compatibilidade com versões anteriores.
  private static final String MIGRATED = ".migration-complete";
  private static final String[] IMPORT_FILES = {
      "config/connections.json", "config/credentials.dpapi.json",
      "sql-session.json", "sql-history.txt", "sql-favorites.txt"};

  static synchronized Path userDirectory() {
    Path home = Paths.get(System.getProperty("user.home"));
    Path current = home.resolve(".linksql");
    Path marker = current.resolve(MIGRATED);
    try {
      Files.createDirectories(current);
      if (!Files.isRegularFile(marker)) {
        Path legacy = home.resolve(LEGACY_DIR);
        for (String name : IMPORT_FILES) {
          Path from = legacy.resolve(name);
          Path to = current.resolve(name);
          if (Files.isRegularFile(from) && !Files.exists(to)) {
            Files.createDirectories(to.getParent());
            Files.copy(from, to); // sem sobrescrever dados novos
          }
        }
        Files.writeString(marker, "Migração não destrutiva realizada pelo LinkSQL.\n",
            StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
      }
    } catch (IOException ex) {
      // Não criar uma configuração vazia se a migração falhou: o usuário
      // precisa poder corrigir o acesso sem correr risco de perder perfis.
      throw new IllegalStateException("Falha ao preparar dados LinkSQL em " + current
          + ". Verifique as permissões e mantenha o backup do diretório antigo.", ex);
    }
    return current;
  }

  static Path connectionProfilesFile() {
    return userDirectory().resolve("config").resolve("connections.json");
  }

  static Path credentialFile() {
    return userDirectory().resolve("config").resolve("credentials.dpapi.json");
  }

  static Path sqlSessionFile() { return userDirectory().resolve("sql-session.json"); }
  static Path sqlHistoryFile() { return userDirectory().resolve("sql-history.txt"); }
  static Path sqlFavoritesFile() { return userDirectory().resolve("sql-favorites.txt"); }
  static Path logDirectory() { return userDirectory().resolve("logs"); }

  /**
   * Espelha apenas arquivos necessários ao LinkSQL JDBC 0.5 existente.
   * O armazenamento principal da aplicação é sempre .linksql. O arquivo
   * antigo é uma cópia transitória para preservar o exportador instalado.
   */
  static void mirrorForJdbc05(Path changedFile) throws IOException {
    Path current = userDirectory();
    Path profiles = current.resolve("config/connections.json");
    Path credentials = current.resolve("config/credentials.dpapi.json");
    Path relative;
    if (changedFile.toAbsolutePath().normalize().equals(profiles.toAbsolutePath().normalize()))
      relative = Path.of("config", "connections.json");
    else if (changedFile.toAbsolutePath().normalize().equals(credentials.toAbsolutePath().normalize()))
      relative = Path.of("config", "credentials.dpapi.json");
    else return;
    if (!Files.isRegularFile(changedFile)) return;
    Path target = Paths.get(System.getProperty("user.home"), LEGACY_DIR).resolve(relative);
    Files.createDirectories(target.getParent());
    Path pending = Files.createTempFile(target.getParent(), "linksql-compat-", ".tmp");
    try {
      Files.copy(changedFile, pending, StandardCopyOption.REPLACE_EXISTING);
      try { Files.move(pending, target, StandardCopyOption.REPLACE_EXISTING,
          StandardCopyOption.ATOMIC_MOVE); }
      catch (java.nio.file.AtomicMoveNotSupportedException ex) {
        Files.move(pending, target, StandardCopyOption.REPLACE_EXISTING);
      }
    } finally { Files.deleteIfExists(pending); }
  }

  /** Usa perfis salvos; como fallback, apenas o exemplo sem credenciais. */
  static Path prepareConnectionProfilesFile() {
    Path userConfig = connectionProfilesFile();
    try {
      Files.createDirectories(userConfig.getParent());
      if (!Files.exists(userConfig)) {
        Path example = Paths.get("config", "connections.json");
        if (!Files.isRegularFile(example)) example = Paths.get("config", "connections.example.json");
        if (Files.isRegularFile(example)) Files.copy(example, userConfig);
        else Files.writeString(userConfig, "{\n  \"connections\": []\n}\n", StandardCharsets.UTF_8,
            StandardOpenOption.CREATE_NEW);
      }
      mirrorForJdbc05(userConfig);
    } catch (IOException ex) {
      throw new IllegalStateException("Não foi possível preparar as conexões LinkSQL. Verifique: "
          + userConfig, ex);
    }
    return userConfig;
  }

  static File defaultUserFile(String filename) {
    if (!Boolean.getBoolean("linksql.packaged")) return new File(filename);
    Path documents = Paths.get(System.getProperty("user.home"), "Documents");
    Path folder = Files.isDirectory(documents) ? documents : documents.getParent();
    return folder.resolve(filename).toFile();
  }
}
