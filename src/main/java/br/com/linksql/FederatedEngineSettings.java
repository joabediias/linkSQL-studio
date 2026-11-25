package br.com.linksql;

import java.nio.file.Path;
import java.util.Locale;
import java.util.Set;
import java.util.prefs.Preferences;

/** Preferências compartilhadas pelo Studio e pelo driver embutido, no mesmo usuário. */
public final class FederatedEngineSettings {
  private static final Preferences PREFS = Preferences.userRoot().node("/br/com/linksql/federation");
  private FederatedEngineSettings() {}
  public record Config(String mode, int memoryMb, int threads, Path temp) {
    public Config {
      mode = mode.trim().toLowerCase(Locale.ROOT);
      if (!Set.of("auto","calcite","duckdb").contains(mode)) throw new IllegalArgumentException("Motor inválido");
      if (memoryMb < 128 || memoryMb > 2048 || threads < 1 || threads > 8)
        throw new IllegalArgumentException("Memória: 128–2048 MB; threads: 1–8");
      temp = temp.toAbsolutePath().normalize();
    }
  }
  public static Config saved() {
    return new Config(PREFS.get("mode","calcite"), PREFS.getInt("memoryMb",768),
        PREFS.getInt("threads",2), Path.of(PREFS.get("temp",System.getProperty("java.io.tmpdir"))));
  }
  public static Config effective() {
    Config c = saved();
    return new Config(System.getProperty("linksql.federated.engine",c.mode()),
        Integer.getInteger("linksql.duckdb.memory.mb",c.memoryMb()),
        Integer.getInteger("linksql.duckdb.threads",c.threads()),
        Path.of(System.getProperty("linksql.duckdb.temp",c.temp().toString())));
  }
  public static void save(Config c) throws java.util.prefs.BackingStoreException {
    PREFS.put("mode",c.mode()); PREFS.putInt("memoryMb",c.memoryMb());
    PREFS.putInt("threads",c.threads()); PREFS.put("temp",c.temp().toString()); PREFS.flush();
  }
}
