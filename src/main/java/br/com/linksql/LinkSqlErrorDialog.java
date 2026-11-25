package br.com.linksql;

import java.awt.*;
import java.awt.datatransfer.StringSelection;
import java.awt.event.KeyEvent;
import java.sql.SQLTimeoutException;
import javax.swing.*;
import javax.swing.border.EmptyBorder;
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
    java.util.List<Throwable> causes = chain(error);
    for (Throwable cause : causes) {
      if (cause instanceof OutOfMemoryError)
        return new Explanation("Memória insuficiente",
            "O Java ficou sem memória para concluir a operação.",
            "Salve sua consulta e reinicie o LinkSQL. Reduza o volume de dados e revise agrupamentos e ordenações antes de tentar novamente.");
    }
    for (Throwable cause : causes) {
      if (cause instanceof SQLException sql && sql.getSQLState() != null && sql.getSQLState().startsWith("28"))
        return new Explanation("Falha de autenticação", "O banco recusou as credenciais da conexão.",
            "Confira usuário e senha no cadastro da conexão.");
    }
    for (Throwable cause : causes) {
      String message = cause.getMessage() == null ? "" : cause.getMessage();
      String lower = message.toLowerCase(java.util.Locale.ROOT);
      if (cause instanceof SQLTimeoutException || lower.contains("query execution is reached")
          || lower.contains("query timed out") || lower.contains("query timeout"))
        return new Explanation("Tempo limite da consulta atingido",
            "A consulta ultrapassou o tempo permitido para execução.",
            "Revise os filtros e o volume de dados. Confira também o timeout configurado na conexão antes de executar novamente.");
      if (lower.contains("you have an error in your sql syntax")
          || lower.contains("syntax error") || lower.contains("parse failed"))
        return new Explanation("SQL não aceito",
            "O banco ou o mecanismo de consultas não conseguiu interpretar o comando SQL.",
            "Consulte o trecho indicado nos detalhes. No modo Federado, o erro também pode estar na SQL gerada para o banco de origem.");
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
    if (!SwingUtilities.isEventDispatchThread()) {
      SwingUtilities.invokeLater(() -> show(parent, error));
      return;
    }
    Explanation explanation = explain(error);
    String details = technicalDetails(error);
    Window owner = parent instanceof Window window ? window :
        parent == null ? null : SwingUtilities.getWindowAncestor(parent);
    JDialog dialog = new JDialog(owner, explanation.title(), Dialog.ModalityType.APPLICATION_MODAL);
    dialog.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
    JPanel content = new JPanel(new BorderLayout(0, 16));
    content.setBorder(new EmptyBorder(20, 20, 16, 20));
    JPanel summary = new JPanel(new BorderLayout(14, 0));
    JLabel icon = new JLabel(UIManager.getIcon("OptionPane.errorIcon"));
    icon.setVerticalAlignment(SwingConstants.TOP);
    summary.add(icon, BorderLayout.WEST);
    JPanel words = new JPanel(new BorderLayout(0, 10));
    JLabel title = new JLabel(explanation.title());
    title.setFont(title.getFont().deriveFont(Font.BOLD, 17f));
    words.add(title, BorderLayout.NORTH);
    JTextArea message = new JTextArea(explanation.message() + "\n\nO que fazer\n" + explanation.suggestion());
    message.setEditable(false);
    message.setOpaque(false);
    message.setFont(UIManager.getFont("Label.font"));
    message.setForeground(UIManager.getColor("Label.foreground"));
    message.setLineWrap(true);
    message.setWrapStyleWord(true);
    message.setColumns(54);
    words.add(message, BorderLayout.CENTER);
    summary.add(words, BorderLayout.CENTER);
    content.add(summary, BorderLayout.NORTH);

    JTextArea technical = new JTextArea(details, 12, 54);
    technical.setEditable(false);
    technical.setLineWrap(true);
    technical.setWrapStyleWord(true);
    technical.setCaretPosition(0);
    JScrollPane pane = new JScrollPane(technical);
    pane.setVisible(false);
    content.add(pane, BorderLayout.CENTER);
    JPanel actions = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
    JButton toggle = new JButton("Mostrar detalhes");
    JButton copy = new JButton("Copiar erro");
    JButton close = new JButton("Fechar");
    toggle.addActionListener(event -> {
      pane.setVisible(!pane.isVisible());
      toggle.setText(pane.isVisible() ? "Ocultar detalhes" : "Mostrar detalhes");
      dialog.pack();
      fitOnScreen(dialog);
    });
    copy.addActionListener(event -> {
      try {
        Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(
            explanation.title() + "\n" + explanation.message() + "\n\n" + details), null);
        copy.setText("Copiado!");
      } catch (IllegalStateException | SecurityException ex) {
        pane.setVisible(true);
        toggle.setText("Ocultar detalhes");
        technical.selectAll();
        technical.requestFocusInWindow();
        copy.setText("Use Ctrl+C nos detalhes");
        dialog.pack();
        fitOnScreen(dialog);
      }
    });
    close.addActionListener(event -> dialog.dispose());
    actions.add(toggle);
    actions.add(copy);
    actions.add(close);
    content.add(actions, BorderLayout.SOUTH);
    dialog.setContentPane(content);
    dialog.getRootPane().setDefaultButton(close);
    dialog.getRootPane().registerKeyboardAction(event -> dialog.dispose(),
        KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), JComponent.WHEN_IN_FOCUSED_WINDOW);
    dialog.pack();
    dialog.setLocationRelativeTo(parent);
    fitOnScreen(dialog);
    dialog.setVisible(true);
  }

  private static void fitOnScreen(JDialog dialog) {
    GraphicsConfiguration config = dialog.getGraphicsConfiguration();
    Rectangle bounds = config.getBounds();
    Insets insets = Toolkit.getDefaultToolkit().getScreenInsets(config);
    int left = bounds.x + insets.left, top = bounds.y + insets.top;
    int width = bounds.width - insets.left - insets.right;
    int height = bounds.height - insets.top - insets.bottom;
    dialog.setSize(Math.min(dialog.getWidth(), width), Math.min(dialog.getHeight(), height));
    dialog.setLocation(Math.max(left, Math.min(dialog.getX(), left + width - dialog.getWidth())),
        Math.max(top, Math.min(dialog.getY(), top + height - dialog.getHeight())));
  }
}
