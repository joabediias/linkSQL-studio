package br.com.linksql;

import java.sql.*;
import java.util.concurrent.ExecutionException;

/** Executar com java -Djava.awt.headless=true br.com.linksql.LinkSqlErrorDialogRegressionTest. */
public final class LinkSqlErrorDialogRegressionTest {
  private static int checks;
  private static void check(boolean ok) { checks++; if (!ok) throw new AssertionError("Verificação " + checks); }
  private static void title(Throwable e, String expected) {
    check(LinkSqlErrorDialog.explain(e).title().equals(expected));
  }
  public static void main(String[] args) {
    title(new ExecutionException(new OutOfMemoryError("Java heap space")), "Memória insuficiente");
    title(new SQLTimeoutException("timeout"), "Tempo limite da consulta atingido");
    title(new Exception("Timeout of '900000' ms for query execution is reached"), "Tempo limite da consulta atingido");
    title(new SQLException("Access denied for user", "28000", 1045), "Falha de autenticação");
    title(new SQLException("SELECT command denied", "42000", 1142), "Permissão insuficiente");
    title(new SQLException("You have an error in your SQL syntax near DOUBLE", "42000", 1064), "SQL não aceito");
    title(new Exception("No match found for function signature SUBSTRING_INDEX"), "Função SQL incompatível");
    title(new NumberFormatException("For input string: x"), "Erro de conversão de dados");
    title(new Exception("Connection refused"), "Falha de conexão");
    title(new Exception("Zero date value prohibited"), "Data inválida na origem");
    title(new Exception(), "Não foi possível concluir a operação");
    SQLException sql = new SQLException("original", "42000", 1064);
    sql.setNextException(new SQLException("adicional"));
    String details = LinkSqlErrorDialog.technicalDetails(new ExecutionException(sql));
    check(details.contains("original") && details.contains("42000") && details.contains("1064") && details.contains("adicional"));
    Exception first = new Exception("primeiro"), second = new Exception("segundo");
    first.initCause(second); second.initCause(first);
    check(LinkSqlErrorDialog.technicalDetails(first).length() < 200);
    check(LinkSqlErrorDialog.technicalDetails(null).isEmpty());
    System.out.println(checks + " verificações de mensagens aprovadas.");
  }
}
