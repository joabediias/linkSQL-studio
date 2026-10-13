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
    JPanel actions = LinkSqlDialogStyle.footer(); actions.add(restore); actions.add(cancel); actions.add(save);
    form.footer.add(actions, BorderLayout.SOUTH);
    dialog.getRootPane().setDefaultButton(save);
    dialog.getRootPane().registerKeyboardAction(e -> dialog.dispose(), KeyStroke.getKeyStroke("ESCAPE"), JComponent.WHEN_IN_FOCUSED_WINDOW);
    dialog.setMinimumSize(new Dimension(720, 600));
    dialog.setSize(840, 740);
    dialog.setLocationRelativeTo(owner);
    dialog.setVisible(true);
    return result[0];
  }

  static final class Form extends JPanel {
    final JToggleButton dark = new JToggleButton(), light = new JToggleButton();
    final JComboBox<String> pageSize = new JComboBox<>(new String[]{"100", "250", "500", "1.000", "2.000", "5.000", "10.000", "20.000", "50.000", "100.000", "Todas (1 página)"});
    final JCheckBox automatic = new JCheckBox("Ativar sugestões contextuais");
    final JSpinner completionDelay = new JSpinner(new SpinnerNumberModel(420, 150, 2000, 50));
    final JSpinner fontSize = new JSpinner(new SpinnerNumberModel(14, 10, 28, 1));
    final JSpinner filterDelay = new JSpinner(new SpinnerNumberModel(300, 100, 2000, 50));
    final JPanel footer = new JPanel(new BorderLayout(0, 10));
    Form(LinkSqlSettings.Snapshot current) {
      super(new BorderLayout(0, 18));
      setBackground(LinkSqlVisualStyle.background());
      setBorder(BorderFactory.createEmptyBorder(20, 24, 18, 24));
      add(LinkSqlDialogStyle.heading("Configurações", "Ajuste o tema, o editor SQL e a apresentação dos resultados.", "settings"), BorderLayout.NORTH);
      pageSize.setEditable(true);
      for (JComponent input : new JComponent[]{pageSize, completionDelay, fontSize, filterDelay}) LinkSqlDialogStyle.field(input);
      automatic.setOpaque(false);
      automatic.addActionListener(e -> completionDelay.setEnabled(automatic.isSelected()));
      ButtonGroup themes = new ButtonGroup(); themes.add(dark); themes.add(light);
      themeButton(dark, true); themeButton(light, false);
      JPanel themeChoices = new JPanel(new GridLayout(1, 2, 12, 0)); themeChoices.setOpaque(false);
      themeChoices.add(dark); themeChoices.add(light);
      JPanel sections = new LinkSqlFormPanel(null);
      sections.setLayout(new BoxLayout(sections, BoxLayout.Y_AXIS)); sections.setOpaque(false);
      sections.add(LinkSqlDialogStyle.card("Aparência", "Escolha o tema usado nos painéis e nas janelas.", themeChoices));
      sections.add(Box.createVerticalStrut(14));
      JPanel editor = rows();
      editor.add(option("Tamanho da fonte", "Aplicado ao editor SQL, de 10 a 28 pontos.", fontSize));
      editor.add(option("Autocomplete", "Sugere comandos, tabelas e colunas enquanto você digita.", automatic));
      editor.add(option("Aguardar sugestões", "Intervalo em milissegundos antes de exibir sugestões.", completionDelay));
      sections.add(LinkSqlDialogStyle.card("Editor SQL", "Fonte e comportamento das sugestões contextuais.", editor));
      sections.add(Box.createVerticalStrut(14));
      JPanel results = rows();
      results.add(option("Linhas por página", "Define o tamanho das páginas para novas consultas.", pageSize));
      results.add(option("Aguardar filtro", "Intervalo em milissegundos ao pesquisar nos resultados.", filterDelay));
      sections.add(LinkSqlDialogStyle.card("Resultados", "Paginação e pesquisa na grade de resultados.", results));
      JScrollPane scroll = LinkSqlDialogStyle.scroll(sections); scroll.setBorder(null);
      scroll.getViewport().setBackground(LinkSqlVisualStyle.background());
      scroll.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
      scroll.getVerticalScrollBar().setUnitIncrement(18);
      add(scroll, BorderLayout.CENTER);
      footer.setOpaque(false);
      JTextArea hint = LinkSqlDialogStyle.note("As alterações são aplicadas ao salvar. Ctrl+Espaço também abre as sugestões quando o autocomplete está desligado.");
      hint.setBorder(null); footer.add(hint, BorderLayout.NORTH);
      add(footer, BorderLayout.SOUTH);
      setValues(current);
    }
    void setValues(LinkSqlSettings.Snapshot value) {
      dark.setSelected(value.darkMode()); light.setSelected(!value.darkMode());
      pageSize.setSelectedItem(LinkSqlSettings.formatPageSize(value.pageSize()));
      automatic.setSelected(value.automaticCompletion());
      completionDelay.setValue(value.completionDelayMs()); completionDelay.setEnabled(value.automaticCompletion());
      fontSize.setValue(value.sqlFontSize()); filterDelay.setValue(value.resultFilterDelayMs());
    }
    LinkSqlSettings.Snapshot read() throws java.text.ParseException {
      completionDelay.commitEdit(); fontSize.commitEdit(); filterDelay.commitEdit();
      return new LinkSqlSettings.Snapshot(dark.isSelected(), parsePageSize(String.valueOf(pageSize.isEditable() ? pageSize.getEditor().getItem() : pageSize.getSelectedItem())),
          automatic.isSelected(), ((Number)completionDelay.getValue()).intValue(),
          ((Number)fontSize.getValue()).intValue(), ((Number)filterDelay.getValue()).intValue());
    }
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
        BorderFactory.createEmptyBorder(6, 0, 6, 0)));
    JPanel labels = new JPanel(new BorderLayout(0, 3)); labels.setOpaque(false);
    JLabel label = new JLabel(title); label.setForeground(LinkSqlVisualStyle.text()); label.setFont(new Font("Segoe UI", Font.PLAIN, 13));
    label.setLabelFor(control); labels.add(label, BorderLayout.NORTH);
    labels.add(LinkSqlVisualStyle.hint(description), BorderLayout.CENTER);
    row.add(labels, BorderLayout.CENTER);
    JPanel right = new JPanel(new GridBagLayout()); right.setOpaque(false);
    control.setPreferredSize(new Dimension(control instanceof JCheckBox ? 230 : 200, Math.max(32, control.getPreferredSize().height)));
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
