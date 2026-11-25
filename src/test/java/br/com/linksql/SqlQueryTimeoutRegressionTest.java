package br.com.linksql;

/** Teste rapido sem acesso aos servidores. */
public final class SqlQueryTimeoutRegressionTest {
  public static void main(String[] args) {
    System.clearProperty("linksql.query.timeout.seconds");
    check(SqlQueryTimeout.seconds() == 900, "limite padrao de 15 minutos");
    System.setProperty("linksql.query.timeout.seconds", "1200");
    check(SqlQueryTimeout.seconds() == 1200, "configuracao customizada");
    System.setProperty("linksql.query.timeout.seconds", "abc");
    check(SqlQueryTimeout.seconds() == 900, "configuracao invalida");
    System.setProperty("linksql.query.timeout.seconds", "999999");
    check(SqlQueryTimeout.seconds() == 900, "limite excessivo");
    check(SqlDatabaseType.MYSQL.url("localhost", 3306, "db").contains("socketTimeout=900000&"), "socket MySQL em milissegundos");
    check(SqlDatabaseType.POSTGRESQL.url("localhost", 5432, "db").endsWith("socketTimeout=900"), "socket PostgreSQL em segundos");
    System.setProperty("linksql.query.timeout.seconds", "1200");
    check(SqlDatabaseType.MYSQL.url("localhost", 3306, "db").contains("socketTimeout=1200000&"), "socket MySQL acompanha configuracao");
    check(SqlDatabaseType.POSTGRESQL.url("localhost", 5432, "db").endsWith("socketTimeout=1200"), "socket PostgreSQL acompanha configuracao");
    System.clearProperty("linksql.query.timeout.seconds");
    System.out.println("OK: timeout padrao 900s e configuracao protegida");
  }
  private static void check(boolean condition, String error) {
    if (!condition) throw new AssertionError(error);
  }
}
