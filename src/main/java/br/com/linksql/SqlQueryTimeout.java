package br.com.linksql;

/** Limite de execucao das consultas SQL, configuravel por propriedade JVM. */
final class SqlQueryTimeout {
  static final int DEFAULT_SECONDS = 15 * 60;
  private static final int MIN_SECONDS = 30;
  private static final int MAX_SECONDS = 60 * 60;

  private SqlQueryTimeout() {}

  static int seconds() {
    String configured = System.getProperty("linksql.query.timeout.seconds", "").trim();
    if (configured.isEmpty()) return DEFAULT_SECONDS;
    try {
      int seconds = Integer.parseInt(configured);
      return seconds >= MIN_SECONDS && seconds <= MAX_SECONDS ? seconds : DEFAULT_SECONDS;
    } catch (NumberFormatException ex) {
      return DEFAULT_SECONDS;
    }
  }
}
