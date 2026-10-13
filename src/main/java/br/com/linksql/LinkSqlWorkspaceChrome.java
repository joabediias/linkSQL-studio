package br.com.linksql;

import javax.swing.*;
import java.awt.*;

/** Desktop chrome shared by the workspace. Receives existing actions only. */
final class LinkSqlWorkspaceChrome {
  private LinkSqlWorkspaceChrome() {}

  static JPanel header(JButton open, JButton newTab, JButton theme) {
    JPanel header = new JPanel(new BorderLayout(12, 0));
    LinkSqlVisualStyle.surfacePanel(header, "header");
    JPanel brand = new JPanel(new BorderLayout(10, 0));
    brand.setOpaque(false);
    JLabel logo = new JLabel(LinkSqlAppIcon.icon(36));
    logo.getAccessibleContext().setAccessibleName("Logo do LinkSQL Studio");
    brand.add(logo, BorderLayout.WEST);
    JPanel words = new JPanel(new GridLayout(2, 1));
    words.setOpaque(false);
    JLabel name = new JLabel("LinkSQL Studio");
    name.setFont(new Font("Segoe UI", Font.BOLD, 15));
    name.setForeground(LinkSqlVisualStyle.text());
    name.putClientProperty("linksql.labelRole", "normal");
    words.add(name);
    words.add(LinkSqlVisualStyle.hint("SQL nativo e federado"));
    brand.add(words, BorderLayout.CENTER);
    header.add(brand, BorderLayout.WEST);
    // A barra principal sempre ocupa uma linha, inclusive na primeira troca de tema.
    JPanel actions = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
    actions.setOpaque(false);
    Dimension themeSize = theme.getPreferredSize();
    theme.setPreferredSize(new Dimension(Math.max(150, themeSize.width + 12), themeSize.height));
    actions.add(open);
    actions.add(newTab);
    actions.add(LinkSqlVisualStyle.smallLabel("4.0.0"));
    actions.add(theme);
    header.add(actions, BorderLayout.EAST);
    return header;
  }

  static JPanel navigation(JButton connections, JButton history, Runnable settings) {
    JPanel rail = new JPanel(new BorderLayout());
    LinkSqlVisualStyle.surfacePanel(rail, "explorer");
    JPanel items = new JPanel(new GridLayout(0, 1, 0, 8));
    items.setOpaque(false);
    items.setBorder(BorderFactory.createEmptyBorder(10, 5, 10, 5));
    items.add(action("Gerenciar conexões", "database", connections::doClick));
    items.add(action("Histórico e favoritos", "history", history::doClick));
    items.add(action("Configurações", "settings", settings));
    rail.add(items, BorderLayout.NORTH);
    return rail;
  }

  private static JButton action(String description, String glyph, Runnable action) {
    JButton button = new JButton();
    LinkSqlVisualStyle.button(button, "", glyph, "quiet");
    button.setMargin(new Insets(9, 9, 9, 9));
    button.setToolTipText(description);
    button.getAccessibleContext().setAccessibleName(description);
    button.addActionListener(e -> action.run());
    return button;
  }

  static JPanel shell(JPanel header, JPanel rail, Component workspace, JLabel status) {
    JPanel content = new JPanel(new BorderLayout());
    LinkSqlVisualStyle.surfacePanel(content, "shell");
    content.add(header, BorderLayout.NORTH);
    JPanel center = new JPanel(new BorderLayout());
    LinkSqlVisualStyle.surfacePanel(center, "shell");
    center.add(rail, BorderLayout.WEST);
    center.add(workspace, BorderLayout.CENTER);
    content.add(center, BorderLayout.CENTER);
    content.add(status, BorderLayout.SOUTH);
    return content;
  }
}
