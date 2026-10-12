package br.com.santysqlstudio;

import javax.swing.*;
import java.awt.*;
import java.util.Locale;

/** Janela modal: Cancelar não modifica nenhuma preferência. */
final class StudioSettingsDialog {
  private StudioSettingsDialog() {}

  static StudioSettings.Snapshot show(JFrame owner, StudioSettings.Snapshot current) {
    JComboBox<String> theme = new JComboBox<>(new String[]{"Escuro", "Claro"});
    theme.setSelectedIndex(current.darkMode() ? 0 : 1);

    JComboBox<String> pageSize = new JComboBox<>(new String[]{
        "100", "250", "500", "1.000", "2.000", "5.000", "10.000",
        "20.000", "50.000", "100.000", "Todas (1 página)"});
    pageSize.setEditable(true);
    pageSize.setSelectedItem(StudioSettings.formatPageSize(current.pageSize()));

    JCheckBox automatic = new JCheckBox("Exibir sugestões enquanto digito", current.automaticCompletion());
    JSpinner completionDelay = new JSpinner(new SpinnerNumberModel(current.completionDelayMs(), 150, 2000, 50));
    JSpinner fontSize = new JSpinner(new SpinnerNumberModel(current.sqlFontSize(), 10, 28, 1));
    JSpinner filterDelay = new JSpinner(new SpinnerNumberModel(current.resultFilterDelayMs(), 100, 2000, 50));
    completionDelay.setEnabled(automatic.isSelected());
    automatic.addActionListener(e -> completionDelay.setEnabled(automatic.isSelected()));

    JPanel fields = new JPanel(new GridBagLayout());
    fields.setBorder(BorderFactory.createEmptyBorder(12, 16, 8, 16));
    GridBagConstraints c = new GridBagConstraints();
    c.insets = new Insets(6, 6, 6, 6);
    c.gridy = 0;
    addRow(fields, c, "Tema", theme);
    addRow(fields, c, "Linhas por página (padrão)", pageSize);
    addRow(fields, c, "Tamanho da fonte SQL", fontSize);
    addRow(fields, c, "Sugestões automáticas", automatic);
    addRow(fields, c, "Aguardar antes das sugestões (ms)", completionDelay);
    addRow(fields, c, "Aguardar antes de filtrar (ms)", filterDelay);

    JTextArea note = new JTextArea(
        "As preferências são salvas para as próximas sessões. O tamanho padrão da página "
        + "vale para novas execuções; os resultados já carregados mantêm seu tamanho atual.\n"
        + "Ctrl+Espaço continua funcionando mesmo com as sugestões automáticas desligadas.\n"
        + "A opção Todas (1 página) pode consumir muita memória.\n"
        + "A contagem federada ocorre em segundo plano, com tempo limite, sem bloquear o SELECT principal.");
    note.setEditable(false);
    note.setLineWrap(true);
    note.setWrapStyleWord(true);
    note.setOpaque(false);
    note.setFocusable(false);
    note.setFont(UIManager.getFont("Label.font"));
    note.setBorder(BorderFactory.createEmptyBorder(8, 14, 8, 14));

    JPanel panel = new JPanel(new BorderLayout());
    panel.add(fields, BorderLayout.NORTH);
    panel.add(note, BorderLayout.CENTER);
    panel.setPreferredSize(new Dimension(650, 440));
    Object[] buttons = {"Salvar", "Restaurar padrões", "Cancelar"};
    while (true) {
      int action = JOptionPane.showOptionDialog(owner, panel, "Configurações — LinkSQL 2.7.1",
          JOptionPane.DEFAULT_OPTION, JOptionPane.PLAIN_MESSAGE, null, buttons, buttons[0]);
      if (action < 0 || action == 2) return null;
      if (action == 1) {
        StudioSettings.Snapshot defaults = StudioSettings.defaults();
        theme.setSelectedIndex(defaults.darkMode() ? 0 : 1);
        pageSize.setSelectedItem(StudioSettings.formatPageSize(defaults.pageSize()));
        automatic.setSelected(defaults.automaticCompletion());
        completionDelay.setValue(defaults.completionDelayMs());
        completionDelay.setEnabled(automatic.isSelected());
        fontSize.setValue(defaults.sqlFontSize());
        filterDelay.setValue(defaults.resultFilterDelayMs());
        continue;
      }
      if (action != 0) continue;
      try {
        int page = parsePageSize(String.valueOf(pageSize.getSelectedItem()));
        completionDelay.commitEdit();
        fontSize.commitEdit();
        filterDelay.commitEdit();
        return new StudioSettings.Snapshot(theme.getSelectedIndex() == 0, page,
            automatic.isSelected(), ((Number) completionDelay.getValue()).intValue(),
            ((Number) fontSize.getValue()).intValue(), ((Number) filterDelay.getValue()).intValue());
      } catch (Exception ex) {
        JOptionPane.showMessageDialog(owner, ex.getMessage() == null ? "Verifique os valores informados." : ex.getMessage(),
            "Configuração inválida", JOptionPane.WARNING_MESSAGE);
      }
    }
  }

  static int parsePageSize(String input) {
    String value = input.trim().toLowerCase(Locale.ROOT);
    if (value.startsWith("todas")) return 0;
    // Formatação brasileira para milhares; não aceita ponto decimal.
    if (!value.matches("[0-9]{1,3}(\\.[0-9]{3})*|[0-9]+"))
      throw new IllegalArgumentException("Informe 100 a 100.000 linhas por página, ou Todas.");
    int size;
    try { size = Integer.parseInt(value.replace(".", "")); }
    catch (NumberFormatException ex) { throw new IllegalArgumentException("Valor muito grande para linhas por página."); }
    if (size < 100 || size > StudioSettings.MAX_PAGE_SIZE)
      throw new IllegalArgumentException("Informe 100 a 100.000 linhas por página, ou Todas.");
    return size;
  }

  private static void addRow(JPanel panel, GridBagConstraints c, String caption, JComponent input) {
    c.gridx = 0; c.weightx = 0; c.fill = GridBagConstraints.NONE; c.anchor = GridBagConstraints.WEST;
    panel.add(new JLabel(caption + ":"), c);
    c.gridx = 1; c.weightx = 1; c.fill = GridBagConstraints.HORIZONTAL;
    panel.add(input, c);
    c.gridy++;
  }
}
