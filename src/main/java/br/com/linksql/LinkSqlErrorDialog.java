package br.com.linksql;

import java.awt.Component;
import java.awt.Dimension;
import java.sql.SQLException;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;
import javax.swing.JOptionPane;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;

/** Explica causas provaveis sem modificar a execucao SQL nem ocultar o erro original. */
final class LinkSqlErrorDialog {
  private LinkSqlErrorDialog() {}

  record Explanation(String title, String message, String suggestion) {}

  static Explanation explain(Throwable error) {
    for (Throwable cause : chain(error)) {
      String message = cause.getMessage() == null ? "" : cause.getMessage();
      String lower = message.toLowerCase(java.util.Locale.ROOT);
      if (cause instanceof NumberFormatException || lower.contains("for input string:"))
        return new Explanation("Erro de conversão de dados",
            "Um valor de texto não pôde ser convertido para número.",
            "Verifique comparações entre códigos de texto e valores numéricos, como CAMPO = 08. "
                + "Para códigos, use CAMPO = '08'. Campos vazios também podem causar este erro.");
      if (lower.contains("no match found for function signature") || lower.contains("no match found for function"))
        return new Explanation("Função SQL incompatível",
            "O mecanismo federado não conseguiu interpretar uma função ou seus tipos de argumentos.",
            "Verifique os tipos dos argumentos e a compatibilidade da função no modo Federado. "
                + "CASE WHEN pode ser uma alternativa quando a função for IF().");
      if (lower.contains("zero date") || lower.contains("zero datetime") || lower.contains("0000-00-00"))
        return new Explanation("Data inválida na origem",
            "O banco retornou uma data zerada ou inválida.",
            "Verifique os registros da coluna de data. Nas conexões MySQL, confira o tratamento automático de datas zeradas.");
      if (lower.contains("access denied") || lower.contains("permission denied")
          || lower.contains("command denied") || lower.contains("execute command denied"))
        return new Explanation("Permissão insuficiente",
            "O usuário do banco não tem permissão para realizar esta operação.",
            "Verifique as permissões da conta e se a consulta está usando uma função armazenada ou objeto restrito.");
      if (lower.contains("connection refused") || lower.contains("communications link failure")
          || lower.contains("connection timed out") || lower.contains("connect timed out"))
        return new Explanation("Falha de conexão",
            "Não foi possível estabelecer ou manter a conexão com o banco.",
            "Confira servidor, porta, rede e disponibilidade do serviço.");
      if (cause instanceof SQLException sql) {
        String state = sql.getSQLState();
        if (state != null && state.startsWith("28"))
          return new Explanation("Falha de autenticação", "O banco recusou as credenciais da conexão.",
              "Confira usuário, senha e permissões do perfil cadastrado.");
        if (state != null && state.startsWith("08"))
          return new Explanation("Falha de conexão", "Ocorreu um problema de comunicação com o banco.",
              "Confira rede, servidor, porta e estado da conexão.");
      }
    }
    return new Explanation("Não foi possível concluir a operação",
        "O LinkSQL recebeu um erro durante a operação.",
        "Abra os detalhes técnicos para consultar a mensagem original e identificar a causa.");
  }

  private static java.util.List<Throwable> chain(Throwable error) {
    java.util.ArrayList<Throwable> result = new java.util.ArrayList<>();
    Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
    for (Throwable cause = error; cause != null && seen.add(cause); cause = cause.getCause())
      result.add(cause);
    return result;
  }

  static String technicalDetails(Throwable error) {
    StringBuilder details = new StringBuilder();
    for (Throwable cause : chain(error)) {
      if (details.length() > 0) details.append("\nCausado por: ");
      details.append(cause.getClass().getName()).append(": ")
          .append(cause.getMessage() == null ? "(sem mensagem)" : cause.getMessage());
      if (cause instanceof SQLException sql) {
        if (sql.getSQLState() != null) details.append("\nSQLState: ").append(sql.getSQLState());
        if (sql.getErrorCode() != 0) details.append(" | Código JDBC: ").append(sql.getErrorCode());
        int extra = 0;
        for (SQLException next = sql.getNextException(); next != null && extra++ < 3;
             next = next.getNextException()) {
          details.append("\nJDBC adicional: ").append(next.getMessage());
        }
      }
    }
    return details.toString();
  }

  static void show(Component parent, Throwable error) {
    Explanation explanation = explain(error);
    String message = explanation.message() + "\n\nSugestão: " + explanation.suggestion();
    Object[] options = {"Fechar", "Detalhes técnicos"};
    int choice = JOptionPane.showOptionDialog(parent, message, explanation.title(),
        JOptionPane.DEFAULT_OPTION, JOptionPane.ERROR_MESSAGE, null, options, options[0]);
    if (choice == 1) {
      JTextArea text = new JTextArea(technicalDetails(error));
      text.setEditable(false);
      text.setCaretPosition(0);
      text.setLineWrap(true);
      text.setWrapStyleWord(true);
      JScrollPane pane = new JScrollPane(text);
      pane.setPreferredSize(new Dimension(650, 280));
      JOptionPane.showMessageDialog(parent, pane, "Detalhes técnicos do erro",
          JOptionPane.INFORMATION_MESSAGE);
    }
  }
}
