package br.com.linksql;

import java.util.Locale;
import java.util.prefs.Preferences;

/** Preferências persistidas; mantém as chaves existentes das versões 1.9 e 2.0. */
final class LinkSqlSettings {
  private static final Preferences PREFS = Preferences.userNodeForPackage(Main.class);
  static {
    // Renomear pacote Java muda o nó de Preferences. Importação uma vez,
    // sem apagar preferências antigas e sem sobrescrever novos valores.
    try {
      Preferences root = Preferences.userRoot();
      if (root.nodeExists("/br/com/santysqlstudio")) {
        Preferences old = root.node("/br/com/santysqlstudio");
        for (String key : old.keys()) {
          if (PREFS.get(key, null) == null) PREFS.put(key, old.get(key, ""));
        }
      }
    } catch (java.util.prefs.BackingStoreException | SecurityException ex) {
      // Preferências usam os padrões caso a migração não esteja disponível.
    }
  }
  static final int DEFAULT_PAGE_SIZE = 1000;
  static final int MAX_PAGE_SIZE = 100000;
  static final Locale DISPLAY_LOCALE = Locale.forLanguageTag("pt-BR");
  static final int DEFAULT_COMPLETION_DELAY_MS = 420;
  static final int DEFAULT_FONT_SIZE = 14;
  static final int DEFAULT_FILTER_DELAY_MS = 300;

  record Snapshot(boolean darkMode, int pageSize, boolean automaticCompletion,
                  int completionDelayMs, int sqlFontSize, int resultFilterDelayMs) {
    Snapshot {
      validatePageSize(pageSize);
      if (completionDelayMs < 150 || completionDelayMs > 2000)
        throw new IllegalArgumentException("Tempo do autocomplete deve estar entre 150 e 2000 ms.");
      if (sqlFontSize < 10 || sqlFontSize > 28)
        throw new IllegalArgumentException("Fonte SQL deve estar entre 10 e 28.");
      if (resultFilterDelayMs < 100 || resultFilterDelayMs > 2000)
        throw new IllegalArgumentException("Tempo do filtro deve estar entre 100 e 2000 ms.");
    }
  }

  private LinkSqlSettings() {}

  static Snapshot defaults() {
    return new Snapshot(true, DEFAULT_PAGE_SIZE, true,
        DEFAULT_COMPLETION_DELAY_MS, DEFAULT_FONT_SIZE, DEFAULT_FILTER_DELAY_MS);
  }

  static Snapshot snapshot() {
    return new Snapshot(darkMode(), pageSize(), automaticCompletion(),
        completionDelayMs(), sqlFontSize(), resultFilterDelayMs());
  }

  static void save(Snapshot value) {
    // O record valida os campos, protegendo as preferências de valores inválidos.
    PREFS.putBoolean("darkMode", value.darkMode());
    PREFS.putInt("rowsPerPage", value.pageSize());
    PREFS.putBoolean("automaticCompletion", value.automaticCompletion());
    PREFS.putInt("completionDelayMs", value.completionDelayMs());
    PREFS.putInt("sqlFontSize", value.sqlFontSize());
    PREFS.putInt("resultFilterDelayMs", value.resultFilterDelayMs());
    // Ignora e remove a opcao antiga: a contagem federada e sempre ativa.
    PREFS.remove("countFederated");
  }

  static int pageSize() {
    int saved = PREFS.getInt("rowsPerPage", DEFAULT_PAGE_SIZE);
    return saved == 0 ? 0 : Math.max(100, Math.min(MAX_PAGE_SIZE, saved));
  }
  private static void validatePageSize(int size) {
    if (size != 0 && (size < 100 || size > MAX_PAGE_SIZE))
      throw new IllegalArgumentException("Linhas por página deve estar entre 100 e 100.000 ou Todas.");
  }
  static void setPageSize(int size) {
    validatePageSize(size);
    PREFS.putInt("rowsPerPage", size);
  }
  static boolean darkMode() { return PREFS.getBoolean("darkMode", true); }
  static void setDarkMode(boolean dark) { PREFS.putBoolean("darkMode", dark); }
  static boolean automaticCompletion() { return PREFS.getBoolean("automaticCompletion", true); }
  static int completionDelayMs() {
    return clamp(PREFS.getInt("completionDelayMs", DEFAULT_COMPLETION_DELAY_MS), 150, 2000);
  }
  static int sqlFontSize() {
    return clamp(PREFS.getInt("sqlFontSize", DEFAULT_FONT_SIZE), 10, 28);
  }
  static int resultFilterDelayMs() {
    return clamp(PREFS.getInt("resultFilterDelayMs", DEFAULT_FILTER_DELAY_MS), 100, 2000);
  }
  private static int clamp(int value, int min, int max) { return Math.max(min, Math.min(max, value)); }
  static String formatCount(long value) { return String.format(DISPLAY_LOCALE, "%,d", value); }
  static String formatPageSize(int size) {
    return size == 0 ? "Todas (1 página)" : formatCount(size);
  }
}
