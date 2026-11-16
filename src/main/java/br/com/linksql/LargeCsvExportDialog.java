package br.com.linksql;

import java.awt.*;
import java.nio.file.*;
import java.sql.Statement;
import java.util.concurrent.atomic.*;
import javax.swing.*;

/** Exportacao federada incremental sem criar JTable. */
final class LargeCsvExportDialog {
  private LargeCsvExportDialog() {}
  static void open(JFrame owner, SqlConnectionRegistry registry, String sql, Path suggested) {
    if (!LargeCsvExport.isTemplate(sql)) {
      JOptionPane.showMessageDialog(owner,
          "Abra um SQL com /*LINKSQL_LOTE:ALIAS.CAMPO*/ nos WHERE das tabelas de origem.\n"
          + "Use o modelo CALPAN_CLIENTES_EXPORTACAO_AUTOMATICA.sql.",
          "Exportar CSV automatizado", JOptionPane.INFORMATION_MESSAGE);
      return;
    }
    JTextField table = new JTextField("CAD_CLI0");
    JTextField column = new JTextField("CLI_CODIGO");
    JSpinner batch = new JSpinner(new SpinnerNumberModel(250, 1, 10000, 50));
    JPanel settings = new JPanel(new GridLayout(3, 2, 8, 8));
    settings.add(new JLabel("Tabela para localizar prefixos:")); settings.add(table);
    settings.add(new JLabel("Campo do codigo (6 digitos):")); settings.add(column);
    settings.add(new JLabel("Codigos por lote:")); settings.add(batch);
    if (JOptionPane.showConfirmDialog(owner, settings, "Exportacao automatizada",
        JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE) != JOptionPane.OK_OPTION) return;
    final LargeCsvExport.Config cfg;
    try { cfg = new LargeCsvExport.Config(table.getText().trim(), column.getText().trim(),
        6, 3, (Integer) batch.getValue(), ';'); }
    catch (RuntimeException ex) {
      JOptionPane.showMessageDialog(owner, ex.getMessage(), "Configuracao invalida", JOptionPane.ERROR_MESSAGE);
      return;
    }
    JFileChooser chooser = new JFileChooser();
    chooser.setSelectedFile(suggested.toFile());
    if (chooser.showSaveDialog(owner) != JFileChooser.APPROVE_OPTION) return;
    Path target = chooser.getSelectedFile().toPath().toAbsolutePath();
    if (!target.getFileName().toString().toLowerCase(java.util.Locale.ROOT).endsWith(".csv"))
      target = target.resolveSibling(target.getFileName().toString() + ".csv");
    if (Files.exists(target)) {
      JOptionPane.showMessageDialog(owner, "O CSV ja existe. Escolha outro arquivo para nao sobrescrever.");
      return;
    }
    Path checkpoint = Path.of(target + ".checkpoint");
    if (Files.exists(checkpoint) && JOptionPane.showConfirmDialog(owner,
        "Foi encontrada uma exportacao interrompida. Retomar?",
        "Retomar exportacao", JOptionPane.YES_NO_OPTION) != JOptionPane.YES_OPTION) return;
    final Path destination = target;
    JDialog dialog = new JDialog(owner, "CSV automatizado — LinkSQL", Dialog.ModalityType.MODELESS);
    dialog.setDefaultCloseOperation(JDialog.DO_NOTHING_ON_CLOSE);
    dialog.setLayout(new BorderLayout(8, 8));
    JLabel status = new JLabel("Preparando...");
    JTextArea history = new JTextArea(8, 58);
    history.setEditable(false);
    JProgressBar progress = new JProgressBar();
    progress.setIndeterminate(true);
    JButton cancel = new JButton("Cancelar");
    JButton close = new JButton("Fechar");
    close.setEnabled(false);
    JPanel north = new JPanel(new BorderLayout(4, 4));
    north.add(status, BorderLayout.NORTH); north.add(progress, BorderLayout.SOUTH);
    JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT));
    buttons.add(cancel); buttons.add(close);
    dialog.add(north, BorderLayout.NORTH);
    dialog.add(new JScrollPane(history), BorderLayout.CENTER);
    dialog.add(buttons, BorderLayout.SOUTH);
    AtomicBoolean stop = new AtomicBoolean();
    AtomicReference<Statement> active = new AtomicReference<>();
    SwingWorker<LargeCsvExport.Summary, Void> worker = new SwingWorker<>() {
      @Override protected LargeCsvExport.Summary doInBackground() throws Exception {
        return LargeCsvExport.run(registry, sql, cfg, destination, stop, active, message ->
            SwingUtilities.invokeLater(() -> {
              status.setText(message.length() > 110 ? message.substring(0, 107) + "..." : message);
              history.append(message + "\n");
              history.setCaretPosition(history.getDocument().getLength());
            }));
      }
      @Override protected void done() {
        progress.setIndeterminate(false);
        cancel.setEnabled(false);
        close.setEnabled(true);
        try {
          LargeCsvExport.Summary result = get();
          status.setText("Concluido: " + result.rows() + " registros, " + result.chunks() + " lotes.");
          history.append("Total de limite de credito: " + result.credit().toPlainString() + "\n");
          history.append("CSV: " + destination + "\n");
        } catch (Exception ex) {
          Throwable cause = ex;
          while (cause.getCause() != null && cause.getCause() != cause) cause = cause.getCause();
          status.setText(stop.get() ? "Interrompido; retomavel." : "Erro; retomavel.");
          history.append("ERRO: " + cause.getMessage() + "\n");
        }
      }
    };
    cancel.addActionListener(e -> {
      stop.set(true);
      cancel.setEnabled(false);
      status.setText("Cancelando...");
      Statement st = active.get();
      if (st != null) new Thread(() -> { try { st.cancel(); } catch (Exception ignored) {} },
          "linksql-cancelar-exportacao").start();
    });
    close.addActionListener(e -> dialog.dispose());
    dialog.pack();
    dialog.setLocationRelativeTo(owner);
    dialog.setVisible(true);
    worker.execute();
  }
}
