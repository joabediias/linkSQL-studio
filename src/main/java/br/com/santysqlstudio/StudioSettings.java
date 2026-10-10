package br.com.santysqlstudio;

import java.util.Locale;
import java.util.prefs.Preferences;

/** Preferências persistidas mantendo as mesmas chaves da versão 1.9. */
final class StudioSettings {
  private static final Preferences PREFS = Preferences.userNodeForPackage(Main.class);
  static final int DEFAULT_PAGE_SIZE = 1000;
  static final int MAX_PAGE_SIZE = 100000;
  static final Locale DISPLAY_LOCALE = Locale.forLanguageTag("pt-BR");
  private StudioSettings() {}

  static int pageSize() {
    int saved = PREFS.getInt("rowsPerPage", DEFAULT_PAGE_SIZE);
    return saved == 0 ? 0 : Math.max(100, Math.min(MAX_PAGE_SIZE, saved));
  }
  static void setPageSize(int size) {
    if (size != 0 && (size < 100 || size > MAX_PAGE_SIZE))
      throw new IllegalArgumentException("Tamanho de página fora do limite permitido: " + size);
    PREFS.putInt("rowsPerPage", size);
  }
  static boolean darkMode() { return PREFS.getBoolean("darkMode", true); }
  static void setDarkMode(boolean dark) { PREFS.putBoolean("darkMode", dark); }
  static String formatCount(long value) { return String.format(DISPLAY_LOCALE, "%,d", value); }
  static String formatPageSize(int size) {
    return size == 0 ? "Todas (1 página)" : formatCount(size);
  }
}
