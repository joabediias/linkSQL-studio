package br.com.santysqlstudio;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.SQLException;
import java.util.logging.FileHandler;
import java.util.logging.Formatter;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

/** Logs locais com rotação, sem registrar SQL, credenciais ou mensagens de exceções. */
final class StudioLog {
  private static final Logger LOGGER = Logger.getLogger("br.com.santysqlstudio");
  private static boolean initialized;
  private StudioLog() {}

  private static synchronized void init() {
    if (initialized) return;
    initialized = true;
    try {
      Path dir = Paths.get(System.getProperty("user.home"), ".santysql-studio", "logs");
      Files.createDirectories(dir);
      FileHandler handler = new FileHandler(dir.resolve("studio-%g.log").toString(), 1_000_000, 3, true);
      handler.setFormatter(new Formatter() {
        public String format(LogRecord r) {
          return new java.util.Date(r.getMillis()) + " | " + r.getLevel() + " | " + r.getMessage() + System.lineSeparator();
        }
      });
      LOGGER.setUseParentHandlers(false);
      LOGGER.addHandler(handler);
    } catch (IOException | SecurityException ignored) { /* Falha de log não impede abrir o editor. */ }
  }

  static void error(String context, Throwable ex) {
    init();
    Throwable actual = ex;
    while (actual.getCause() != null && actual.getCause() != actual) actual = actual.getCause();
    String code = actual instanceof SQLException sql ? " SQLState=" + sql.getSQLState() + " Code=" + sql.getErrorCode() : "";
    // Não registrar ex.getMessage(): erros JDBC podem incluir texto SQL e informações de conexão.
    String place = "";
    for (StackTraceElement frame : actual.getStackTrace()) {
      if (frame.getClassName().startsWith("br.com.santysqlstudio.")) {
        place = " em " + frame.getClassName() + ":" + frame.getLineNumber(); break;
      }
    }
    LOGGER.log(Level.SEVERE, context + " | " + actual.getClass().getSimpleName() + code + place);
  }
}
