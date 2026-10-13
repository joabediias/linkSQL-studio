package br.com.linksql;

import javax.swing.*;
import com.formdev.flatlaf.FlatLaf;
import com.formdev.flatlaf.FlatDarkLaf;
import com.formdev.flatlaf.FlatLightLaf;
import java.util.HashMap;
import java.util.Map;
import javax.swing.border.Border;
import javax.swing.table.JTableHeader;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.DefaultTreeCellRenderer;
import java.awt.*;
import java.awt.geom.*;
import java.util.Locale;

/**
 * Identidade visual da interface principal LinkSQL.
 * Apenas apresentacao: nao conhece conexoes, SQL, senhas ou regras de negocio.
 * Todas as cores e icones possuem variantes claro/escuro e nao requerem recursos externos.
 */
final class LinkSqlVisualStyle {
  private LinkSqlVisualStyle() {}

  private static boolean dark;
  static final int TOOLBAR_HEIGHT = 44;
  static Color background() { return dark ? color("#0F1115") : color("#F4F6F9"); }
  static Color surface() { return dark ? color("#181B21") : Color.WHITE; }
  static Color editorSurface() { return dark ? color("#12151A") : Color.WHITE; }
  static Color toolbarSurface() { return dark ? color("#181B21") : Color.WHITE; }
  static Color border() { return dark ? color("#303641") : color("#D8DFE8"); }
  static Color text() { return dark ? color("#E8EDF5") : color("#172536"); }
  static Color subtleText() { return dark ? color("#A7B4C7") : color("#53647A"); }
  static Color accent() { return dark ? color("#22D3E8") : color("#087F94"); }
  static Color primaryBg() { return dark ? color("#22D3E8") : color("#087F94"); }
  static Color primaryText() { return dark ? color("#092029") : Color.WHITE; }
  static Color alternateRow() { return dark ? color("#16191F") : color("#F8FAFC"); }
  static Color currentLine() { return dark ? color("#1C242D") : color("#EFF6F8"); }
  static Color databaseColor(String technology) {
    String name = technology.toUpperCase(Locale.ROOT).replace(" ", "");
    return switch (name) {
      case "MYSQL" -> dark ? color("#F8B34C") : color("#A76006");
      case "POSTGRESQL" -> dark ? color("#78ADFF") : color("#2766BB");
      case "ORACLE" -> dark ? color("#FF8578") : color("#C54437");
      case "SQLSERVER" -> dark ? color("#EA81B0") : color("#AC3675");
      case "FIREBIRD" -> dark ? color("#EAD375") : color("#8D6A06");
      default -> accent();
    };
  }
  static Color rowSelection() { return dark ? color("#193840") : color("#E1F1F4"); }
  private static Color color(String hex) { return Color.decode(hex); }

  /** Configure derived FlatLaf colors before defaults are resolved by component UIs. */
  static void install(boolean darkMode) throws UnsupportedLookAndFeelException {
    dark = darkMode;
    Map<String, String> palette = new HashMap<>();
    palette.put("@background", dark ? "#181B21" : "#FFFFFF");
    palette.put("@foreground", dark ? "#E8EDF5" : "#172536");
    palette.put("@disabledForeground", dark ? "#8591A3" : "#748195");
    palette.put("@accentColor", dark ? "#22D3E8" : "#087F94");
    palette.put("@selectionBackground", dark ? "#193840" : "#E1F1F4");
    palette.put("@selectionForeground", dark ? "#E8EDF5" : "#172536");
    for (String prefix : new String[]{"TextField", "FormattedTextField", "PasswordField", "TextArea", "ComboBox", "Spinner"}) {
      palette.put(prefix + ".background", dark ? "#12151A" : "#FFFFFF");
      palette.put(prefix + ".foreground", "$Label.foreground");
      palette.put(prefix + ".disabledBackground", dark ? "#1B1F26" : "#F0F3F7");
      palette.put(prefix + ".disabledForeground", "@disabledForeground");
    }
    for (String key : new String[]{"ComboBox.buttonBackground", "ComboBox.buttonEditableBackground", "Spinner.buttonBackground"})
      palette.put(key, dark ? "#12151A" : "#FFFFFF");
    palette.put("Component.borderColor", dark ? "#303641" : "#D8DFE8");
    palette.put("Component.disabledBorderColor", "$Component.borderColor");
    palette.put("Component.focusedBorderColor", "@accentColor");
    palette.put("Component.focusColor", "@accentColor");
    palette.put("Component.focusWidth", "1");
    palette.put("Component.arc", "10");
    palette.put("TextComponent.arc", "10");
    palette.put("TextField.margin", "7,10,7,10");
    palette.put("PasswordField.margin", "7,10,7,10");
    palette.put("ComboBox.padding", "7,10,7,10");
    palette.put("Button.arc", "12");
    palette.put("ToggleButton.selectedBackground", "@selectionBackground");
    palette.put("ToggleButton.selectedForeground", "@accentColor");
    palette.put("ToggleButton.background", dark ? "#0F1115" : "#F4F6F9");
    palette.put("Button.background", dark ? "#181B21" : "#FFFFFF");
    palette.put("Button.default.background", "@accentColor");
    palette.put("Button.default.foreground", dark ? "#092029" : "#FFFFFF");
    palette.put("ScrollBar.background", dark ? "#181B21" : "#F4F6F9");
    palette.put("ScrollBar.track", "$ScrollBar.background");
    palette.put("ScrollBar.thumb", dark ? "#3B4553" : "#BFCAD8");
    palette.put("TabbedPane.selectedForeground", "$Label.foreground");
    palette.put("TabbedPane.foreground", dark ? "#A7B4C7" : "#53647A");
    palette.put("TabbedPane.underlineColor", "@accentColor");
    palette.put("SplitPane.background", dark ? "#303641" : "#D8DFE8");
    palette.put("MenuBar.background", dark ? "#181B21" : "#FFFFFF");
    for (String prefix : new String[]{"PopupMenu", "Menu", "MenuItem", "CheckBoxMenuItem", "RadioButtonMenuItem"}) {
      palette.put(prefix + ".background", dark ? "#181B21" : "#FFFFFF");
      palette.put(prefix + ".foreground", "@foreground");
      palette.put(prefix + ".selectionBackground", "@selectionBackground");
      palette.put(prefix + ".selectionForeground", "@selectionForeground");
    }
    palette.put("TitlePane.background", "$MenuBar.background");
    palette.put("TitlePane.foreground", "$Label.foreground");
    FlatLaf.setGlobalExtraDefaults(palette);
    UIManager.setLookAndFeel(darkMode ? new FlatDarkLaf() : new FlatLightLaf());
    defaults(darkMode);
  }

  static void defaults(boolean darkMode) {
    dark = darkMode;
    Font font = new Font("Segoe UI", Font.PLAIN, 13);
    UIManager.put("defaultFont", font);
    for (String prefix : new String[]{"Label", "Button", "Menu", "MenuItem", "MenuBar", "TextField", "PasswordField",
        "ComboBox", "CheckBox", "TabbedPane", "Table", "TableHeader", "Tree", "List", "Spinner"})
      UIManager.put(prefix + ".font", new javax.swing.plaf.FontUIResource(font));
    UIManager.put("TextArea.foreground", text());
    UIManager.put("FormattedTextField.foreground", text());
    UIManager.put("PasswordField.caretForeground", text());
    UIManager.put("ComboBox.buttonArrowColor", subtleText());
    UIManager.put("ComboBox.buttonBackground", editorSurface());
    UIManager.put("ComboBox.selectionBackground", rowSelection());
    UIManager.put("ComboBox.selectionForeground", text());
    UIManager.put("ScrollBar.track", surface());
    UIManager.put("Table.selectionInactiveBackground", rowSelection());
    UIManager.put("Table.selectionInactiveForeground", text());
    UIManager.put("List.selectionInactiveBackground", rowSelection());
    UIManager.put("List.selectionInactiveForeground", text());
    UIManager.put("Button.arc", 10);
    UIManager.put("Panel.background", surface());
    UIManager.put("Label.foreground", text());
    UIManager.put("TextField.background", editorSurface());
    UIManager.put("PasswordField.background", editorSurface());
    UIManager.put("TextArea.background", editorSurface());
    UIManager.put("TextField.foreground", text());
    UIManager.put("PasswordField.foreground", text());
    UIManager.put("TextField.caretForeground", text());
    UIManager.put("TextField.placeholderForeground", subtleText());
    UIManager.put("ComboBox.background", editorSurface());
    UIManager.put("ComboBox.foreground", text());
    UIManager.put("Spinner.background", editorSurface());
    UIManager.put("CheckBox.background", surface());
    UIManager.put("CheckBox.foreground", text());
    UIManager.put("MenuBar.background", toolbarSurface());
    UIManager.put("MenuBar.foreground", dark ? Color.WHITE : text());
    UIManager.put("MenuBar.selectionForeground", dark ? Color.WHITE : text());
    UIManager.put("Menu.background", surface());
    UIManager.put("Menu.foreground", text());
    UIManager.put("MenuItem.background", surface());
    UIManager.put("MenuItem.foreground", text());
    UIManager.put("PopupMenu.background", surface());
    UIManager.put("PopupMenu.foreground", text());
    for (String prefix : new String[]{"Menu", "MenuItem", "CheckBoxMenuItem", "RadioButtonMenuItem"}) {
      UIManager.put(prefix + ".selectionBackground", rowSelection());
      UIManager.put(prefix + ".selectionForeground", text());
    }
    UIManager.put("ScrollPane.background", surface());
    UIManager.put("Viewport.background", surface());
    UIManager.put("ScrollBar.background", surface());
    UIManager.put("ScrollBar.thumb", border());
    UIManager.put("Component.borderColor", border());
    UIManager.put("Component.focusedBorderColor", accent());
    UIManager.put("Button.background", toolbarSurface());
    UIManager.put("Button.foreground", text());
    UIManager.put("Button.hoverBackground", rowSelection());
    UIManager.put("Button.focusedBackground", rowSelection());
    UIManager.put("Button.default.background", primaryBg());
    UIManager.put("Button.default.foreground", primaryText());
    UIManager.put("List.background", surface());
    UIManager.put("List.foreground", text());
    UIManager.put("List.selectionBackground", rowSelection());
    UIManager.put("List.selectionForeground", text());
    UIManager.put("Component.arc", 9);
    UIManager.put("TextComponent.arc", 8);
    UIManager.put("ScrollBar.width", 11);
    UIManager.put("ScrollBar.thumbArc", 12);
    UIManager.put("TabbedPane.showTabSeparators", false);
    UIManager.put("TabbedPane.tabHeight", 39);
    UIManager.put("TabbedPane.tabInsets", new Insets(6, 15, 6, 15));
    UIManager.put("TabbedPane.selectedBackground", surface());
    UIManager.put("TabbedPane.background", background());
    UIManager.put("TabbedPane.contentAreaColor", surface());
    UIManager.put("TabbedPane.underlineColor", accent());
    UIManager.put("TabbedPane.inactiveUnderlineColor", border());
    UIManager.put("Tree.rowHeight", 27);
    UIManager.put("Table.rowHeight", 30);
    UIManager.put("Table.showHorizontalLines", false);
    UIManager.put("Table.showVerticalLines", false);
    UIManager.put("Table.gridColor", border());
    UIManager.put("Table.alternateRowColor", alternateRow());
    UIManager.put("Table.background", surface());
    UIManager.put("Table.foreground", text());
    UIManager.put("Tree.background", surface());
    UIManager.put("Tree.foreground", text());
    UIManager.put("Table.selectionBackground", rowSelection());
    UIManager.put("Table.selectionForeground", text());
    UIManager.put("TableHeader.background", surface());
    UIManager.put("TableHeader.foreground", subtleText());
    UIManager.put("TableHeader.height", 35);
    UIManager.put("Tree.selectionBackground", rowSelection());
    UIManager.put("Tree.selectionForeground", text());
    UIManager.put("Component.focusColor", accent());
    UIManager.put("TabbedPane.foreground", subtleText());
    UIManager.put("TabbedPane.selectedForeground", text());
    UIManager.put("SplitPane.background", background());
    UIManager.put("SplitPaneDivider.draggingColor", accent());
  }

  static JLabel smallLabel(String value) {
    JLabel label = new JLabel(value);
    label.setFont(new Font("Segoe UI", Font.BOLD, 11));
    label.setForeground(subtleText());
    label.putClientProperty("linksql.labelRole", "muted");
    return label;
  }

  static JLabel hint(String value) {
    JLabel label = new JLabel(value);
    label.setFont(new Font("Segoe UI", Font.PLAIN, 12));
    label.setForeground(subtleText());
    label.putClientProperty("linksql.labelRole", "muted");
    return label;
  }

  static JPanel sectionTitle(String title, String subTitle, String iconName) {
    JPanel panel = new JPanel(new BorderLayout(10, 0));
    panel.setOpaque(false);
    JLabel icon = new JLabel(icon(iconName, accent(), 18));
    icon.setBorder(BorderFactory.createEmptyBorder(0, 3, 0, 0));
    panel.add(icon, BorderLayout.WEST);
    JPanel texts = new JPanel(new GridLayout(2, 1, 0, 0));
    texts.setOpaque(false);
    JLabel main = new JLabel(title);
    main.setFont(new Font("Segoe UI", Font.BOLD, 13));
    main.setForeground(text());
    main.putClientProperty("linksql.labelRole", "normal");
    JLabel subtitle = hint(subTitle);
    texts.add(main);
    texts.add(subtitle);
    panel.add(texts, BorderLayout.CENTER);
    return panel;
  }

  static void button(JButton button, String label, String iconName, String variant) {
    button.setText(label);
    button.putClientProperty("linksql.buttonVariant", variant);
    button.putClientProperty("linksql.iconName", iconName);
    button.setFont(new Font("Segoe UI", Font.BOLD, 12));
    button.setMargin(new Insets(7, 12, 7, 12));
    button.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
    applyButton(button);
  }

  private static void applyButton(JButton button) {
    String variant = String.valueOf(button.getClientProperty("linksql.buttonVariant"));
    String glyph = String.valueOf(button.getClientProperty("linksql.iconName"));
    boolean primary = "primary".equals(variant);
    boolean danger = "danger".equals(variant);
    if (primary) {
      button.setForeground(primaryText());
      button.setBackground(primaryBg());
      button.putClientProperty("JButton.buttonType", null);
    } else if (danger) {
      button.setForeground(dark ? color("#FFA6A6") : color("#B84545"));
      button.setBackground(toolbarSurface());
      button.putClientProperty("JButton.buttonType", "toolBarButton");
    } else {
      button.setForeground(text());
      button.setBackground(toolbarSurface());
      button.putClientProperty("JButton.buttonType", "toolBarButton");
    }
    if (!"null".equals(glyph)) {
      int glyphSize = "sun".equals(glyph) || "moon".equals(glyph) ? 20 : 17;
      button.setIcon(icon(glyph, primary ? primaryText() : danger ? button.getForeground() : "muted".equals(variant) ? subtleText() : accent(), glyphSize));
      button.setIconTextGap(7);
    }
  }

  static void surfacePanel(JPanel panel, String role) {
    panel.putClientProperty("linksql.panelRole", role);
    applyPanel(panel, role);
  }

  private static void applyPanel(JPanel panel, String role) {
    switch (role) {
      case "shell" -> { panel.setBackground(background()); panel.setBorder(null); }
      case "header" -> {
        panel.setBackground(surface());
        panel.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createMatteBorder(0, 0, 1, 0, border()),
            BorderFactory.createEmptyBorder(8, 14, 8, 14)));
      }
      case "toolbar" -> {
        panel.setBackground(toolbarSurface());
        panel.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createMatteBorder(0, 0, 1, 0, border()),
            BorderFactory.createEmptyBorder(6, 13, 6, 13)));
      }
      case "section" -> {
        panel.setBackground(surface());
        panel.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createMatteBorder(0, 0, 1, 0, border()),
            BorderFactory.createEmptyBorder(6, 12, 6, 12)));
      }
      case "explorer" -> {
        panel.setBackground(surface());
        panel.setBorder(BorderFactory.createMatteBorder(0, 0, 0, 1, border()));
      }
      case "results" -> {
        panel.setBackground(surface());
        panel.setBorder(BorderFactory.createMatteBorder(1, 0, 0, 0, border()));
      }
      case "status" -> {
        panel.setBackground(surface());
        panel.setBorder(BorderFactory.createMatteBorder(1, 0, 0, 0, border()));
      }
      default -> panel.setBackground(surface());
    }
  }

  static void status(JLabel label) {
    label.putClientProperty("linksql.status", Boolean.TRUE);
    label.setFont(new Font("Segoe UI", Font.PLAIN, 12));
    label.setForeground(subtleText());
    label.setIcon(icon("status", accent(), 13));
    label.setIconTextGap(9);
    label.setOpaque(true);
    label.setBackground(surface());
    label.setBorder(BorderFactory.createCompoundBorder(
        BorderFactory.createMatteBorder(1, 0, 0, 0, border()),
        BorderFactory.createEmptyBorder(5, 14, 5, 14)));
  }

  static void tabPane(JTabbedPane tabs) {
    tabs.putClientProperty("linksql.tabPane", Boolean.TRUE);
    tabs.setFont(new Font("Segoe UI", Font.BOLD, 12));
    tabs.setBackground(background());
    tabs.setForeground(text());
    tabs.putClientProperty("JTabbedPane.tabType", "underlined");
    tabs.putClientProperty("JTabbedPane.showTabSeparators", Boolean.FALSE);
  }

  static void explorer(JTree tree) {
    tree.setFont(new Font("Segoe UI", Font.PLAIN, 12));
    tree.setRowHeight(28);
    tree.setBackground(surface());
    tree.setForeground(text());
    tree.setCellRenderer(new ExplorerRenderer());
    tree.setBorder(BorderFactory.createEmptyBorder(7, 7, 7, 7));
  }

  static void resultsTable(JTable table) {
    table.setFont(new Font("Segoe UI", Font.PLAIN, 12));
    table.setRowHeight(30);
    table.setShowGrid(false);
    table.setIntercellSpacing(new Dimension(0, 0));
    table.setSelectionBackground(rowSelection());
    table.setSelectionForeground(text());
    table.setBackground(surface());
    table.setForeground(text());
    JTableHeader header = table.getTableHeader();
    header.setBackground(dark ? color("#1C2027") : color("#F0F3F7"));
    header.setForeground(text());
    header.setFont(new Font("Segoe UI", Font.BOLD, 12));
    header.setPreferredSize(new Dimension(header.getPreferredSize().width, 35));
  }

  static void splitter(JSplitPane split) {
    split.setBorder(BorderFactory.createEmptyBorder());
    split.setDividerSize(5);
    split.setBackground(border());
    if (split.getUI() instanceof javax.swing.plaf.basic.BasicSplitPaneUI ui)
      ui.getDivider().setBackground(border());
    split.setContinuousLayout(true);
  }

  static JPanel actionRow(int alignment) {
    JPanel row = new JPanel(new LinkSqlWrapLayout(alignment, 6, 3));
    row.setOpaque(false);
    return row;
  }

  static void input(JComponent input) {
    input.putClientProperty("JComponent.roundRect", null);
    input.putClientProperty("linksql.input", Boolean.TRUE);
    applyInput(input);
    input.setFont(new Font("Segoe UI", Font.PLAIN, 12));
  }

  private static void applyInput(JComponent input) {
    input.setBackground(editorSurface());
    input.setForeground(text());
    if (input instanceof JComboBox<?> combo && (combo.getRenderer() == null
        || combo.getRenderer() instanceof javax.swing.plaf.UIResource
        || combo.getRenderer() instanceof ThemeComboRenderer)) combo.setRenderer(new ThemeComboRenderer());
    if (input instanceof javax.swing.text.JTextComponent field) {
      field.setCaretColor(text());
      field.setSelectionColor(rowSelection());
      field.setSelectedTextColor(text());
    }
  }

  /** Hidden combo popups are not children of the window and can retain the previous LAF colors. */
  private static final class ThemeComboRenderer extends DefaultListCellRenderer {
    @Override public Component getListCellRendererComponent(JList<?> list, Object value,
        int index, boolean selected, boolean focused) {
      JLabel label = (JLabel)super.getListCellRendererComponent(list, value, index, selected, focused);
      list.setBackground(editorSurface()); list.setForeground(text());
      list.setSelectionBackground(rowSelection()); list.setSelectionForeground(text());
      label.setBackground(index >= 0 && selected ? rowSelection() : editorSurface());
      label.setForeground(text()); label.setOpaque(true);
      return label;
    }
  }

  /** Context and submenu popups live outside the window tree; refresh them when opened. */
  static void popup(JPopupMenu menu) {
    if (!Boolean.TRUE.equals(menu.getClientProperty("linksql.themedPopup"))) {
      menu.putClientProperty("linksql.themedPopup", Boolean.TRUE);
      menu.addPopupMenuListener(new javax.swing.event.PopupMenuListener() {
        public void popupMenuWillBecomeVisible(javax.swing.event.PopupMenuEvent e) { refreshPopup(menu); }
        public void popupMenuWillBecomeInvisible(javax.swing.event.PopupMenuEvent e) {}
        public void popupMenuCanceled(javax.swing.event.PopupMenuEvent e) {}
      });
    }
    refreshPopup(menu);
  }

  private static void refreshPopup(JPopupMenu menu) {
    SwingUtilities.updateComponentTreeUI(menu);
    menu.setBackground(surface()); menu.setForeground(text()); menu.setOpaque(true);
    menu.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createLineBorder(border()),
        BorderFactory.createEmptyBorder(4, 2, 4, 2)));
    for (Component child : menu.getComponents()) {
      if (child instanceof JMenuItem item) {
        item.setBackground(surface()); item.setForeground(text()); item.setOpaque(true);
        if (item instanceof JMenu submenu) popup(submenu.getPopupMenu());
      } else if (child instanceof JSeparator separator) {
        separator.setBackground(surface()); separator.setForeground(border());
      } else if (child instanceof Container content) refreshRecursive(content);
    }
  }

  static void refresh(Container root, boolean darkMode) {
    defaults(darkMode);
    refreshRecursive(root);
    root.revalidate();
    root.repaint();
  }

  private static void refreshRecursive(Component component) {
    if (component instanceof JComponent input && Boolean.TRUE.equals(input.getClientProperty("linksql.input")))
      applyInput(input);
    if (component instanceof JScrollPane scroll) {
      scroll.setBackground(surface());
      scroll.getViewport().setBackground(surface());
      if (scroll.getBorder() != null) scroll.setBorder(BorderFactory.createLineBorder(border()));
    }
    if (component instanceof JSplitPane split) splitter(split);
    if (component instanceof javax.swing.plaf.basic.BasicSplitPaneDivider divider) divider.setBackground(border());
    if (component instanceof JPanel panel) {
      Object role = panel.getClientProperty("linksql.panelRole");
      if (role instanceof String r) applyPanel(panel, r);
    }
    if (component instanceof JButton button && button.getClientProperty("linksql.buttonVariant") != null)
      applyButton(button);
    if (component instanceof JLabel label) {
      if (label.getClientProperty("linksql.iconName") instanceof String glyph)
        label.setIcon(icon(glyph, accent(), ((Icon) label.getIcon()).getIconWidth()));
      if (Boolean.TRUE.equals(label.getClientProperty("linksql.status"))) status(label);
      Object labelRole = label.getClientProperty("linksql.labelRole");
      if (labelRole == null && !Boolean.TRUE.equals(label.getClientProperty("linksql.status"))) label.setForeground(text());
      if ("muted".equals(labelRole)) label.setForeground(subtleText());
      else if ("normal".equals(labelRole)) label.setForeground(text());
    }
    if (component instanceof JTable table) resultsTable(table);
    if (component instanceof JTree tree) explorer(tree);
    if (component instanceof JTabbedPane pane && Boolean.TRUE.equals(pane.getClientProperty("linksql.tabPane")))
      tabPane(pane);
    if (component instanceof Container c) for (Component child : c.getComponents()) refreshRecursive(child);
  }

  private static class ExplorerRenderer extends DefaultTreeCellRenderer {
    @Override public Component getTreeCellRendererComponent(JTree tree, Object value, boolean selected,
          boolean expanded, boolean leaf, int row, boolean focus) {
      super.getTreeCellRendererComponent(tree, value, selected, expanded, leaf, row, focus);
      Object user = value instanceof DefaultMutableTreeNode node ? node.getUserObject() : value;
      String title = String.valueOf(user);
      String type = user == null ? "" : user.getClass().getSimpleName();
      String name = type.equals("SourceNode") ? "database"
          : type.equals("TableNode") ? "table"
          : type.equals("RoutineFolder") ? "function"
          : title.contains("Views") ? "eye"
          : title.contains("Tabelas") ? "table"
          : title.contains("Procedimentos") || title.contains("Funções") ? "function"
          : title.equals("Schemas") || type.equals("NamespaceNode") ? "folder"
          : title.equals("Conexões SQL") ? "database"
          : leaf ? "field" : "folder";
      Color iconColor = selected ? accent() : subtleText();
      if (type.equals("SourceNode") && title.endsWith("]") && title.lastIndexOf("[") >= 0)
        iconColor = databaseColor(title.substring(title.lastIndexOf("[") + 1, title.length() - 1));
      setIcon(icon(name, iconColor, 16));
      setFont(new Font("Segoe UI", type.equals("SourceNode") || title.equals("Conexões SQL")
          ? Font.BOLD : Font.PLAIN, 12));
      setBorderSelectionColor(null);
      setBackgroundSelectionColor(rowSelection());
      setTextSelectionColor(text());
      setBackgroundNonSelectionColor(surface());
      setTextNonSelectionColor(text());
      return this;
    }
  }

  static Icon icon(String name, Color color, int size) {
    return new LineIcon(name, color, size);
  }

  /** SVG-like vector shapes, rendered locally; no bundled image/font required. */
  private record LineIcon(String name, Color color, int size) implements Icon {
    @Override public int getIconWidth() { return size; }
    @Override public int getIconHeight() { return size; }
    @Override public void paintIcon(Component comp, Graphics graphics, int x, int y) {
      Graphics2D g = (Graphics2D) graphics.create();
      try {
        g.translate(x, y);
        g.scale(size / 20.0, size / 20.0);
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setStroke(new BasicStroke(1.6f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.setColor(color);
        switch (name.toLowerCase(Locale.ROOT)) {
          case "database" -> {
            g.drawOval(3, 2, 14, 6); g.drawArc(3, 7, 14, 6, 180, 180);
            g.drawArc(3, 12, 14, 6, 180, 180);
            g.drawLine(3, 5, 3, 15); g.drawLine(17, 5, 17, 15);
          }
          case "play" -> { Path2D p = new Path2D.Double(); p.moveTo(6, 3); p.lineTo(16, 10); p.lineTo(6, 17); p.closePath(); g.fill(p); }
          case "close" -> { g.drawLine(5, 5, 15, 15); g.drawLine(15, 5, 5, 15); }
          case "star", "star-filled" -> {
            Path2D star = new Path2D.Double();
            for (int i = 0; i < 10; i++) {
              double angle = -Math.PI / 2 + i * Math.PI / 5;
              double radius = i % 2 == 0 ? 8 : 3.6;
              double sx = 10 + Math.cos(angle) * radius, sy = 10 + Math.sin(angle) * radius;
              if (i == 0) star.moveTo(sx, sy); else star.lineTo(sx, sy);
            }
            star.closePath(); if (name.equals("star-filled")) g.fill(star); else g.draw(star);
          }
          case "stop" -> g.drawRoundRect(5, 5, 10, 10, 2, 2);
          case "plus" -> { g.drawLine(10, 3, 10, 17); g.drawLine(3, 10, 17, 10); }
          case "open" -> { g.drawRoundRect(3, 5, 14, 12, 2, 2); g.drawLine(3, 8, 8, 8); g.drawLine(6, 3, 12, 3); }
          case "edit" -> { g.drawLine(4, 14, 14, 4); g.drawLine(7, 17, 17, 7); g.drawLine(14, 4, 17, 7); g.drawLine(4, 14, 3, 18); g.drawLine(3, 18, 7, 17); }
          case "copy" -> { g.drawRoundRect(7, 7, 11, 11, 2, 2); g.drawLine(13, 4, 13, 2); g.drawLine(13, 2, 2, 2); g.drawLine(2, 2, 2, 13); g.drawLine(2, 13, 4, 13); }
          case "trash" -> { g.drawLine(3, 5, 17, 5); g.drawRoundRect(5, 5, 10, 13, 2, 2); g.drawLine(7, 2, 13, 2); g.drawLine(8, 9, 8, 14); g.drawLine(12, 9, 12, 14); }
          case "save" -> { g.drawRoundRect(3, 3, 14, 14, 2, 2); g.drawRect(6, 3, 8, 5); g.drawRect(6, 11, 8, 6); }
          case "export" -> { g.drawLine(10, 2, 10, 13); g.drawLine(6, 9, 10, 13); g.drawLine(14, 9, 10, 13); g.drawLine(3, 15, 3, 18); g.drawLine(3, 18, 17, 18); g.drawLine(17, 18, 17, 15); }
          case "table" -> { g.drawRoundRect(2, 3, 16, 14, 2, 2); g.drawLine(2, 8, 18, 8); g.drawLine(8, 3, 8, 17); g.drawLine(2, 13, 18, 13); }
          case "folder" -> { Path2D p = new Path2D.Double(); p.moveTo(2, 6); p.lineTo(8, 6); p.lineTo(10, 8); p.lineTo(18, 8); p.lineTo(17, 16); p.lineTo(3, 16); p.closePath(); g.draw(p); }
          case "function" -> { g.drawLine(5, 2, 5, 18); g.drawLine(5, 3, 11, 3); g.drawLine(2, 10, 12, 10); g.drawLine(13, 12, 18, 17); g.drawLine(18, 12, 13, 17); }
          case "eye" -> { g.drawOval(4, 7, 12, 6); g.fillOval(8, 9, 4, 3); }
          case "field" -> { g.drawRoundRect(5, 5, 10, 10, 2, 2); }
          case "status" -> g.fillOval(5, 5, 10, 10);
          case "settings" -> {
            g.drawLine(3, 5, 17, 5); g.drawLine(3, 10, 17, 10); g.drawLine(3, 15, 17, 15);
            g.fillOval(6, 3, 4, 4); g.fillOval(12, 8, 4, 4); g.fillOval(5, 13, 4, 4);
          }
          case "moon" -> {
            // Uma única silhueta fechada evita as pontas cruzadas dos dois arcos.
            Path2D moon = new Path2D.Double();
            moon.moveTo(17.5, 10.7);
            moon.curveTo(17.1, 14.4, 14.0, 17.3, 10.2, 17.3);
            moon.curveTo(6.1, 17.3, 2.8, 14.0, 2.8, 9.9);
            moon.curveTo(2.8, 6.1, 5.7, 3.0, 9.4, 2.6);
            moon.curveTo(7.9, 5.0, 8.2, 8.1, 10.2, 10.1);
            moon.curveTo(12.2, 12.1, 15.1, 12.4, 17.5, 10.7);
            moon.closePath();
            g.draw(moon);
          }
          case "sun" -> {
            g.draw(new Ellipse2D.Double(6.5, 6.5, 7, 7));
            for (int ray = 0; ray < 8; ray++) {
              double angle = ray * Math.PI / 4;
              double dx = Math.cos(angle), dy = Math.sin(angle);
              g.draw(new Line2D.Double(10 + dx * 6.3, 10 + dy * 6.3,
                  10 + dx * 8.5, 10 + dy * 8.5));
            }
          }
          case "code" -> { g.drawLine(8, 3, 3, 10); g.drawLine(3, 10, 8, 17); g.drawLine(12, 3, 17, 10); g.drawLine(17, 10, 12, 17); }
          case "history" -> { g.drawArc(3, 3, 14, 14, 20, 290); g.drawLine(10, 6, 10, 10); g.drawLine(10, 10, 13, 12); }
          default -> g.drawRoundRect(3, 3, 14, 14, 3, 3);
        }
      } finally { g.dispose(); }
    }
  }
}
