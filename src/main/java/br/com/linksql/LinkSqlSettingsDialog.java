package br.com.linksql;

import javax.swing.*;
import java.awt.*;
import java.util.Locale;

/** Cancelar não modifica preferências; a persistência continua no controlador principal. */
final class LinkSqlSettingsDialog {
  private LinkSqlSettingsDialog() {}

  static LinkSqlSettings.Snapshot show(JFrame owner, LinkSqlSettings.Snapshot current) {
    Form form = new Form(current);
    JDialog dialog = new JDialog(owner, "LinkSQL — Configurações", true);
    dialog.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
    dialog.setContentPane(form);
    LinkSqlDialogStyle.window(dialog);
    LinkSqlSettings.Snapshot[] result = {null};
    JButton save = new JButton("Salvar configurações"), restore = new JButton("Restaurar padrões"), cancel = new JButton("Cancelar");
    LinkSqlDialogStyle.button(save, "save", true);
    LinkSqlDialogStyle.button(restore, "history", false);
    LinkSqlDialogStyle.button(cancel, "close", false);
    restore.addActionListener(e -> form.setValues(LinkSqlSettings.defaults()));
    cancel.addActionListener(e -> dialog.dispose());
    save.addActionListener(e -> {
      try { result[0] = form.read(); dialog.dispose(); }
      catch (Exception ex) { JOptionPane.showMessageDialog(dialog,
          ex.getMessage() == null ? "Verifique os valores informados." : ex.getMessage(),
          "Configuração inválida", JOptionPane.WARNING_MESSAGE); }
    });
    JPanel actions = new JPanel(new BorderLayout(12, 0)); actions.setOpaque(false);
    actions.add(restore, BorderLayout.WEST);
    JPanel confirmation = LinkSqlDialogStyle.footer(); confirmation.add(cancel); confirmation.add(save);
    actions.add(confirmation, BorderLayout.EAST);
    form.footer.add(actions, BorderLayout.SOUTH);
    dialog.getRootPane().setDefaultButton(save);
    dialog.getRootPane().registerKeyboardAction(e -> dialog.dispose(), KeyStroke.getKeyStroke("ESCAPE"), JComponent.WHEN_IN_FOCUSED_WINDOW);
    dialog.setMinimumSize(new Dimension(720, 600));
    dialog.setSize(860, 660);
    dialog.setLocationRelativeTo(owner);
    dialog.setVisible(true);
    return result[0];
  }

  static final class Form extends JPanel {
    final JToggleButton dark = new JToggleButton(), light = new JToggleButton();
    final JComboBox<String> pageSize = new JComboBox<>(new String[]{"100", "250", "500", "1.000", "2.000", "5.000", "10.000", "20.000", "50.000", "100.000", "Todas (1 página)"});
    final JCheckBox automatic = new JCheckBox("Ativar sugestões contextuais");
    final JCheckBox writes = new JCheckBox("Permitir escrita");
    final JTabbedPane categories = new JTabbedPane();
    final JSpinner completionDelay = new JSpinner(new SpinnerNumberModel(420, 150, 2000, 50));
    final JSpinner fontSize = new JSpinner(new SpinnerNumberModel(14, 10, 28, 1));
    final JSpinner filterDelay = new JSpinner(new SpinnerNumberModel(300, 100, 2000, 50));
    final JPanel footer = new JPanel(new BorderLayout(0, 10));
    Form(LinkSqlSettings.Snapshot current) {
      super(new BorderLayout(0, 16));
      setBackground(LinkSqlVisualStyle.background());
      setBorder(BorderFactory.createEmptyBorder(20, 22, 16, 22));
      add(LinkSqlDialogStyle.heading("Configurações", "Ajuste o tema, o editor SQL e a apresentação dos resultados.", "settings"), BorderLayout.NORTH);
      pageSize.setEditable(true);
      for (JComponent input : new JComponent[]{pageSize, completionDelay, fontSize, filterDelay}) LinkSqlDialogStyle.field(input);
      automatic.setOpaque(false);
      writes.setOpaque(false);
      automatic.addActionListener(e -> completionDelay.setEnabled(automatic.isSelected()));
      ButtonGroup themes = new ButtonGroup(); themes.add(dark); themes.add(light);
      themeButton(dark, true); themeButton(light, false);
      JPanel themeChoices = new JPanel(new GridLayout(1, 2, 12, 0)); themeChoices.setOpaque(false);
      themeChoices.add(dark); themeChoices.add(light);
      JPanel appearance = sections();
      appearance.add(LinkSqlDialogStyle.card("Aparência", "Escolha como deseja visualizar seu espaço de trabalho.", themeChoices));
      appearance.add(Box.createVerticalStrut(16));
      JPanel editor = rows();
      editor.add(option("Tamanho da fonte", "Tamanho do texto no editor SQL, de 10 a 28 pontos.", fontSize));
      editor.add(option("Autocomplete", "Exibir sugestões de comandos, tabelas e colunas.", automatic));
      editor.add(option("Aguardar sugestões", "Tempo de espera antes das sugestões, em milissegundos.", completionDelay));
      appearance.add(LinkSqlDialogStyle.card("Editor SQL", "Deixe a edição mais confortável para sua rotina.", editor));
      appearance.add(Box.createVerticalStrut(12));
      JTextArea editorHint = LinkSqlDialogStyle.note("Ctrl+Espaço abre as sugestões mesmo com o autocomplete desativado.");
      editorHint.setBorder(BorderFactory.createEmptyBorder(0, 4, 0, 4));
      appearance.add(editorHint);

      JPanel queries = sections();
      JPanel results = rows();
      results.add(option("Linhas por página", "Quantidade de registros exibidos em cada página.", pageSize));
      results.add(option("Aguardar filtro", "Tempo de espera ao filtrar a grade, em milissegundos.", filterDelay));
      queries.add(LinkSqlDialogStyle.card("Resultados", "Organize a leitura e a pesquisa dos resultados.", results));
      queries.add(Box.createVerticalStrut(16));
      JPanel execution = rows();
      execution.add(option("Comandos de escrita (update, insert...)", "Exige uma conexão selecionada e permissão no banco.", writes));
      JTextArea executionHint = LinkSqlDialogStyle.note("Cada comando concluído é confirmado no banco. As permissões do usuário continuam obrigatórias.");
      executionHint.setBorder(BorderFactory.createEmptyBorder(10, 0, 0, 0));
      execution.add(executionHint);
      queries.add(LinkSqlDialogStyle.card("Execução SQL", "Defina se deseja permitir alterações nos dados e na estrutura.", execution));
      LinkSqlDialogStyle.tabs(categories);
      categories.putClientProperty("JTabbedPane.tabHeight", 42);
      categories.addTab("Aparência e editor", sectionScroll(appearance));
      categories.addTab("Consultas e execução", sectionScroll(queries));
      add(categories, BorderLayout.CENTER);
      footer.setOpaque(false);
      JTextArea hint = LinkSqlDialogStyle.note("As alterações são aplicadas ao salvar.");
      hint.setBorder(null); footer.add(hint, BorderLayout.NORTH);
      add(footer, BorderLayout.SOUTH);
      setValues(current);
    }
    void setValues(LinkSqlSettings.Snapshot value) {
      dark.setSelected(value.darkMode()); light.setSelected(!value.darkMode());
      pageSize.setSelectedItem(LinkSqlSettings.formatPageSize(value.pageSize()));
      automatic.setSelected(value.automaticCompletion());
      writes.setSelected(value.allowWrites());
      completionDelay.setValue(value.completionDelayMs()); completionDelay.setEnabled(value.automaticCompletion());
      fontSize.setValue(value.sqlFontSize()); filterDelay.setValue(value.resultFilterDelayMs());
    }
    LinkSqlSettings.Snapshot read() throws java.text.ParseException {
      completionDelay.commitEdit(); fontSize.commitEdit(); filterDelay.commitEdit();
      return new LinkSqlSettings.Snapshot(dark.isSelected(), parsePageSize(String.valueOf(pageSize.isEditable() ? pageSize.getEditor().getItem() : pageSize.getSelectedItem())),
          automatic.isSelected(), ((Number)completionDelay.getValue()).intValue(),
          ((Number)fontSize.getValue()).intValue(), ((Number)filterDelay.getValue()).intValue(),
          writes.isSelected());
    }
  }

  private static JPanel sections() {
    JPanel sections = new LinkSqlFormPanel(null);
    sections.setLayout(new BoxLayout(sections, BoxLayout.Y_AXIS));
    sections.setOpaque(false);
    sections.setBorder(BorderFactory.createEmptyBorder(14, 2, 8, 2));
    return sections;
  }
  private static JScrollPane sectionScroll(JPanel sections) {
    JScrollPane scroll = LinkSqlDialogStyle.scroll(sections);
    scroll.setBorder(null);
    scroll.getViewport().setBackground(LinkSqlVisualStyle.background());
    scroll.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
    scroll.getVerticalScrollBar().setUnitIncrement(18);
    return scroll;
  }

  private static void themeButton(JToggleButton button, boolean dark) {
    button.setText("<html><b>" + (dark ? "Escuro" : "Claro") + "</b><br><span style='font-size:10px'>" + (dark ? "Grafite e ciano" : "Branco e azul petróleo") + "</span></html>");
    button.setIcon(new ThemeSwatch(dark)); button.setIconTextGap(12);
    button.setFont(new Font("Segoe UI", Font.PLAIN, 13));
    button.setHorizontalAlignment(SwingConstants.LEFT);
    button.setMargin(new Insets(10, 12, 10, 12));
    button.putClientProperty("FlatLaf.style", "arc: 12");
    button.setPreferredSize(new Dimension(250, 56));
    button.getAccessibleContext().setAccessibleName("Tema " + (dark ? "escuro" : "claro"));
  }
  private record ThemeSwatch(boolean dark) implements Icon {
    public int getIconWidth() { return 44; }
    public int getIconHeight() { return 34; }
    public void paintIcon(Component c, Graphics graphics, int x, int y) {
      Graphics2D g = (Graphics2D)graphics.create();
      g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
      String[] palette = dark ? new String[]{"#0F1115", "#303641", "#22D3E8"} : new String[]{"#E6EBF2", "#FFFFFF", "#087F94"};
      for (int i = 0; i < 3; i++) { g.setColor(Color.decode(palette[i])); g.fillRoundRect(x + i * 15, y + 2, 11, 30, 8, 8); }
      g.dispose();
    }
  }
  private static JPanel rows() {
    JPanel panel = new JPanel(); panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS)); panel.setOpaque(false); return panel;
  }
  private static JPanel option(String title, String description, JComponent control) {
    JPanel row = new JPanel(new BorderLayout(18, 0)); row.setOpaque(false);
    row.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createMatteBorder(1, 0, 0, 0, LinkSqlVisualStyle.border()),
        BorderFactory.createEmptyBorder(10, 0, 10, 0)));
    JPanel labels = new JPanel(new BorderLayout(0, 3)); labels.setOpaque(false);
    JLabel label = new JLabel(title); label.setForeground(LinkSqlVisualStyle.text()); label.setFont(new Font("Segoe UI", Font.BOLD, 13));
    label.setLabelFor(control); labels.add(label, BorderLayout.NORTH);
    labels.add(LinkSqlVisualStyle.hint(description), BorderLayout.CENTER);
    row.add(labels, BorderLayout.CENTER);
    JPanel right = new JPanel(new GridBagLayout()); right.setOpaque(false);
    int width = control instanceof JCheckBox ? control.getPreferredSize().width + 8 : 170;
    control.setPreferredSize(new Dimension(width, Math.max(36, control.getPreferredSize().height)));
    right.add(control); row.add(right, BorderLayout.EAST);
    return row;
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

}
