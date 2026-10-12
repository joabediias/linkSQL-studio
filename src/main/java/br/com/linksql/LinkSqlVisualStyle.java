package br.com.linksql;

import javax.swing.*;
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
  static final int TOOLBAR_HEIGHT = 52;
  static Color background() { return dark ? color("#111827") : color("#F4F7FB"); }
  static Color surface() { return dark ? color("#1B2534") : Color.WHITE; }
  static Color editorSurface() { return dark ? color("#17212F") : Color.WHITE; }
  static Color toolbarSurface() { return dark ? color("#182232") : Color.WHITE; }
  static Color border() { return dark ? color("#344358") : color("#E2E8F0"); }
  static Color text() { return dark ? color("#E5ECF7") : color("#17243B"); }
  static Color subtleText() { return dark ? color("#A6B6CE") : color("#64748B"); }
  static Color accent() { return dark ? color("#7EAEFF") : color("#2863E4"); }
  static Color primaryBg() { return dark ? color("#3474E2") : color("#2863E4"); }
  static Color primaryText() { return Color.WHITE; }
  static Color rowSelection() { return dark ? color("#254066") : color("#E4EEFF"); }
  private static Color color(String hex) { return Color.decode(hex); }

  static void defaults(boolean darkMode) {
    dark = darkMode;
    Font font = new Font("Segoe UI", Font.PLAIN, 13);
    UIManager.put("defaultFont", font);
    UIManager.put("Button.arc", 11);
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
    UIManager.put("Table.selectionBackground", rowSelection());
    UIManager.put("Table.selectionForeground", text());
    UIManager.put("TableHeader.background", surface());
    UIManager.put("TableHeader.foreground", subtleText());
    UIManager.put("TableHeader.height", 35);
    UIManager.put("Tree.selectionBackground", rowSelection());
    UIManager.put("Tree.selectionForeground", text());
    UIManager.put("Component.focusColor", accent());
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
      button.putClientProperty("JButton.buttonType", "roundRect");
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
      button.setIcon(icon(glyph, primary ? Color.WHITE : danger ? button.getForeground() : accent(), 17));
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
            BorderFactory.createEmptyBorder(12, 16, 12, 16)));
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
            BorderFactory.createEmptyBorder(9, 12, 9, 12)));
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
        BorderFactory.createEmptyBorder(8, 15, 8, 15)));
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
    header.setBackground(dark ? color("#263347") : color("#F1F5FB"));
    header.setForeground(text());
    header.setFont(new Font("Segoe UI", Font.BOLD, 12));
    header.setPreferredSize(new Dimension(header.getPreferredSize().width, 35));
  }

  static void splitter(JSplitPane split) {
    split.setBorder(BorderFactory.createEmptyBorder());
    split.setDividerSize(5);
    split.setContinuousLayout(true);
  }

  static void input(JComponent input) {
    input.putClientProperty("JComponent.roundRect", Boolean.TRUE);
    input.setFont(new Font("Segoe UI", Font.PLAIN, 12));
  }

  static void refresh(Container root, boolean darkMode) {
    defaults(darkMode);
    refreshRecursive(root);
    root.revalidate();
    root.repaint();
  }

  private static void refreshRecursive(Component component) {
    if (component instanceof JPanel panel) {
      Object role = panel.getClientProperty("linksql.panelRole");
      if (role instanceof String r) applyPanel(panel, r);
    }
    if (component instanceof JButton button && button.getClientProperty("linksql.buttonVariant") != null)
      applyButton(button);
    if (component instanceof JLabel label) {
      if (Boolean.TRUE.equals(label.getClientProperty("linksql.status"))) status(label);
      Object labelRole = label.getClientProperty("linksql.labelRole");
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
          : title.contains("Schema / banco") ? "folder"
          : title.equals("Conexões SQL") ? "database"
          : leaf ? "field" : "folder";
      setIcon(icon(name, selected ? accent() : (type.equals("SourceNode") ? accent() : subtleText()), 16));
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
          case "stop" -> g.drawRoundRect(5, 5, 10, 10, 2, 2);
          case "plus" -> { g.drawLine(10, 3, 10, 17); g.drawLine(3, 10, 17, 10); }
          case "open" -> { g.drawRoundRect(3, 5, 14, 12, 2, 2); g.drawLine(3, 8, 8, 8); g.drawLine(6, 3, 12, 3); }
          case "save" -> { g.drawRoundRect(3, 3, 14, 14, 2, 2); g.drawRect(6, 3, 8, 5); g.drawRect(6, 11, 8, 6); }
          case "export" -> { g.drawLine(10, 2, 10, 13); g.drawLine(6, 9, 10, 13); g.drawLine(14, 9, 10, 13); g.drawLine(3, 15, 3, 18); g.drawLine(3, 18, 17, 18); g.drawLine(17, 18, 17, 15); }
          case "table" -> { g.drawRoundRect(2, 3, 16, 14, 2, 2); g.drawLine(2, 8, 18, 8); g.drawLine(8, 3, 8, 17); g.drawLine(2, 13, 18, 13); }
          case "folder" -> { Path2D p = new Path2D.Double(); p.moveTo(2, 6); p.lineTo(8, 6); p.lineTo(10, 8); p.lineTo(18, 8); p.lineTo(17, 16); p.lineTo(3, 16); p.closePath(); g.draw(p); }
          case "function" -> { g.drawLine(5, 2, 5, 18); g.drawLine(5, 3, 11, 3); g.drawLine(2, 10, 12, 10); g.drawLine(13, 12, 18, 17); g.drawLine(18, 12, 13, 17); }
          case "eye" -> { g.drawOval(4, 7, 12, 6); g.fillOval(8, 9, 4, 3); }
          case "field" -> { g.drawRoundRect(5, 5, 10, 10, 2, 2); }
          case "status" -> g.fillOval(5, 5, 10, 10);
          case "settings" -> { g.drawOval(4, 4, 12, 12); g.fillOval(8, 8, 4, 4); }
          case "moon" -> { g.drawArc(4, 2, 13, 16, 90, 230); g.drawArc(8, 0, 9, 16, 60, 210); }
          case "sun" -> { g.drawOval(6, 6, 8, 8); g.drawLine(10, 1, 10, 4); g.drawLine(10, 16, 10, 19); g.drawLine(1, 10, 4, 10); g.drawLine(16, 10, 19, 10); }
          case "code" -> { g.drawLine(8, 3, 3, 10); g.drawLine(3, 10, 8, 17); g.drawLine(12, 3, 17, 10); g.drawLine(17, 10, 12, 17); }
          case "history" -> { g.drawArc(3, 3, 14, 14, 20, 290); g.drawLine(10, 6, 10, 10); g.drawLine(10, 10, 13, 12); }
          default -> g.drawRoundRect(3, 3, 14, 14, 3, 3);
        }
      } finally { g.dispose(); }
    }
  }
}
