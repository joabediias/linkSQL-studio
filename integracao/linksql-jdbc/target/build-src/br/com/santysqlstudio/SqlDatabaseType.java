package br.com.santysqlstudio;

import java.util.Locale;

/** Tipo de banco e contrato JDBC. Nenhum conector e acoplado a interface Swing. */
enum SqlDatabaseType {
  MYSQL("MySQL", "com.mysql.cj.jdbc.Driver", 3306),
  ORACLE("Oracle", "oracle.jdbc.OracleDriver", 1521),
  POSTGRESQL("PostgreSQL", "org.postgresql.Driver", 5432),
  SQLSERVER("SQL Server", "com.microsoft.sqlserver.jdbc.SQLServerDriver", 1433),
  FIREBIRD("Firebird", "org.firebirdsql.jdbc.FBDriver", 3050);

  private final String label;
  private final String driver;
  private final int defaultPort;

  SqlDatabaseType(String label, String driver, int defaultPort) {
    this.label = label; this.driver = driver; this.defaultPort = defaultPort;
  }

  String driver() { return driver; }
  int defaultPort() { return defaultPort; }
  @Override public String toString() { return label; }

  static SqlDatabaseType fromCode(String text) {
    if (text == null || text.isBlank()) return MYSQL; // JSON legado nao tinha tipo.
    String normalized = text.strip().toUpperCase(Locale.ROOT).replace(" ", "").replace("_", "");
    return switch (normalized) {
      case "MYSQL" -> MYSQL;
      case "ORACLE" -> ORACLE;
      case "POSTGRES", "POSTGRESQL" -> POSTGRESQL;
      case "SQLSERVER", "MSSQL" -> SQLSERVER;
      case "FIREBIRD" -> FIREBIRD;
      default -> throw new IllegalArgumentException("Tipo de banco nao reconhecido: " + text);
    };
  }

  String defaultSchema(String username) {
    return switch (this) {
      case ORACLE -> username == null ? "" : username.toUpperCase(Locale.ROOT);
      case POSTGRESQL -> "public";
      case SQLSERVER -> "dbo";
      case MYSQL, FIREBIRD -> "";
    };
  }

  String url(String host, int port, String database) {
    String server = host.contains(":") && !host.startsWith("[") ? "[" + host + "]" : host;
    return switch (this) {
      case MYSQL -> "jdbc:mysql://" + server + ":" + port + "/" + database
          + "?connectTimeout=5000&socketTimeout=120000&useSSL=false";
      case ORACLE -> "jdbc:oracle:thin:@//" + server + ":" + port + "/" + database;
      case POSTGRESQL -> "jdbc:postgresql://" + server + ":" + port + "/" + database
          + "?connectTimeout=5&socketTimeout=120";
      case SQLSERVER -> "jdbc:sqlserver://" + server + ":" + port + ";databaseName=" + database
          + ";encrypt=true;trustServerCertificate=false;loginTimeout=5";
      case FIREBIRD -> "jdbc:firebirdsql://" + server + ":" + port + "/" + database;
    };
  }
}
