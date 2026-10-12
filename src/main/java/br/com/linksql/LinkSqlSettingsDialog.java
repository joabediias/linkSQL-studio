package br.com.linksql;

import javax.swing.*;
import java.awt.*;
import java.util.Locale;

/** Janela modal: Cancelar não modifica nenhuma preferência. */
final class LinkSqlSettingsDialog {
  private LinkSqlSettingsDialog() {}

  static LinkSqlSettings.Snapshot show(JFrame owner, LinkSqlSettings.Snapshot current) {
    JComboBox<String> theme = new JComboBox<>(new String[]{"Escuro", "Claro"});
    theme.setSelectedIndex(current.darkMode() ? 0 : 1);

    JComboBox<String> pageSize = new JComboBox<>(new String[]{
        "100", "250", "500", "1.000", "2.000", "5.000", "10.000",
        "20.000", "50.000", "100.000", "Todas (1 página)"});
    pageSize.setEditable(true);
    pageSize.setSelectedItem(LinkSqlSettings.formatPageSize(current.pageSize()));

    JCheckBox automatic = new JCheckBox("Exibir sugestões enquanto digito", current.automaticCompletion());
    JSpinner completionDelay = new JSpinner(new SpinnerNumberModel(current.completionDelayMs(), 150, 2000, 50));
    JSpinner fontSize = new JSpinner(new SpinnerNumberModel(current.sqlFontSize(), 10, 28, 1));
    JSpinner filterDelay = new JSpinner(new SpinnerNumberModel(current.resultFilterDelayMs(), 100, 2000, 50));
    completionDelay.setEnabled(automatic.isSelected());
    automatic.addActionListener(e -> completionDelay.setEnabled(automatic.isSelected()));
    for (JComponent input : new JComponent[]{theme, pageSize, completionDelay, fontSize, filterDelay})
      LinkSqlDialogStyle.field(input);

    JPanel editor = sectionForm();
    GridBagConstraints editorRow = rowStyle();
    addRow(editor, editorRow, "Tamanho da fonte SQL", fontSize);
    addRow(editor, editorRow, "Autocomplete", automatic);
    addRow(editor, editorRow, "Aguardar sugestões (ms)", completionDelay);

    JPanel results = sectionForm();
    GridBagConstraints resultRow = rowStyle();
    addRow(results, resultRow, "Linhas por página", pageSize);
    addRow(results, resultRow, "Aguardar filtro (ms)", filterDelay);

    JPanel appearance = sectionForm();
    GridBagConstraints themeRow = rowStyle();
    addRow(appearance, themeRow, "Tema visual", theme);

    JTabbedPane categories = new JTabbedPane();
    LinkSqlDialogStyle.tabs(categories);
    categories.addTab("Editor SQL", LinkSqlDialogStyle.scroll(editor));
    categories.addTab("Resultados", LinkSqlDialogStyle.scroll(results));
    categories.addTab("Aparência", LinkSqlDialogStyle.scroll(appearance));

    JPanel panel = new JPanel(new BorderLayout(0, 13));
    LinkSqlDialogStyle.styleOptionPanel(panel);
    panel.add(LinkSqlDialogStyle.heading("Configurações",
        "Personalize o editor, a paginação e o tema visual do LinkSQL.", "settings"), BorderLayout.NORTH);
    panel.add(categories, BorderLayout.CENTER);
    JPanel notePanel = new JPanel(new BorderLayout());
    notePanel.setOpaque(false);
    notePanel.add(LinkSqlDialogStyle.note(
        "As preferências são salvas para as próximas sessões. O tamanho padrão da página "
        + "vale para novas execuções. Ctrl+Espaço continua disponível com sugestões automáticas desligadas. "
        + "'Todas' pode usar muita memória; a contagem federada ocorre em segundo plano."), BorderLayout.CENTER);
    panel.add(notePanel, BorderLayout.SOUTH);
    panel.setPreferredSize(new Dimension(730, 465));

    Object[] buttons = {"Salvar", "Restaurar padrões", "Cancelar"};
    while (true) {
      int action = JOptionPane.showOptionDialog(owner, panel, "Configurações — LinkSQL 3.3.1",
          JOptionPane.DEFAULT_OPTION, JOptionPane.PLAIN_MESSAGE, null, buttons, buttons[0]);
      if (action < 0 || action == 2) return null;
      if (action == 1) {
        LinkSqlSettings.Snapshot defaults = LinkSqlSettings.defaults();
        theme.setSelectedIndex(defaults.darkMode() ? 0 : 1);
        pageSize.setSelectedItem(LinkSqlSettings.formatPageSize(defaults.pageSize()));
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
        return new LinkSqlSettings.Snapshot(theme.getSelectedIndex() == 0, page,
            automatic.isSelected(), ((Number) completionDelay.getValue()).intValue(),
            ((Number) fontSize.getValue()).intValue(), ((Number) filterDelay.getValue()).intValue());
      } catch (Exception ex) {
        JOptionPane.showMessageDialog(owner, ex.getMessage() == null ? "Verifique os valores informados." : ex.getMessage(),
            "Configuração inválida", JOptionPane.WARNING_MESSAGE);
      }
    }
  }

  private static JPanel sectionForm() {
    JPanel panel = new JPanel(new GridBagLayout());
    panel.setBackground(LinkSqlVisualStyle.surface());
    panel.setBorder(BorderFactory.createEmptyBorder(18, 18, 18, 18));
    return panel;
  }

  private static GridBagConstraints rowStyle() {
    GridBagConstraints c = new GridBagConstraints();
    c.insets = new Insets(10, 7, 10, 7);
    c.gridy = 0;
    return c;
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
    if (size < 100 || size > LinkSqlSettings.MAX_PAGE_SIZE)
      throw new IllegalArgumentException("Informe 100 a 100.000 linhas por página, ou Todas.");
    return size;
  }

  private static void addRow(JPanel panel, GridBagConstraints c, String caption, JComponent input) {
    c.gridx = 0; c.weightx = 0; c.fill = GridBagConstraints.NONE; c.anchor = GridBagConstraints.WEST;
    panel.add(LinkSqlDialogStyle.caption(caption + ":"), c);
    c.gridx = 1; c.weightx = 1; c.fill = GridBagConstraints.HORIZONTAL;
    panel.add(input, c);
    c.gridy++;
  }
}
