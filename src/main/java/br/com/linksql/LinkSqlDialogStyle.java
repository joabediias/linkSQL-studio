package br.com.linksql;

import javax.swing.*;
import javax.swing.table.JTableHeader;
import java.awt.*;

/**
 * Aparencia compartilhada pelas janelas auxiliares do LinkSQL.
 * Nao guarda estado, nao altera validacoes, registros, senhas nem consultas JDBC.
 * Consulta a paleta ativa em LinkSqlVisualStyle; novos dialogos seguem o tema da janela principal.
 */
final class LinkSqlDialogStyle {
  private LinkSqlDialogStyle() {}
  private static final Font BODY = new Font("Segoe UI", Font.PLAIN, 12);
  private static final Font CAPTION = new Font("Segoe UI", Font.BOLD, 11);

  static void window(JDialog dialog) {
    Container content = dialog.getContentPane();
    content.setBackground(LinkSqlVisualStyle.background());
    if (content instanceof JComponent c) c.setBorder(BorderFactory.createEmptyBorder(14, 16, 14, 16));
  }

  static JPanel heading(String title, String description, String iconName) {
    JPanel container = new JPanel(new BorderLayout(12, 0));
    container.setOpaque(false);
    JLabel icon = new JLabel(LinkSqlVisualStyle.icon(iconName, LinkSqlVisualStyle.accent(), 27));
    icon.setBorder(BorderFactory.createEmptyBorder(0, 2, 0, 0));
    container.add(icon, BorderLayout.WEST);
    JPanel headings = new JPanel(new GridLayout(2, 1, 0, 2));
    headings.setOpaque(false);
    JLabel name = new JLabel(title);
    name.setFont(new Font("Segoe UI", Font.BOLD, 19));
    name.setForeground(LinkSqlVisualStyle.text());
    headings.add(name);
    headings.add(LinkSqlVisualStyle.hint(description));
    container.add(headings, BorderLayout.CENTER);
    return container;
  }

  static JPanel surface(JPanel panel) {
    panel.setBackground(LinkSqlVisualStyle.surface());
    panel.setBorder(BorderFactory.createCompoundBorder(
        BorderFactory.createLineBorder(LinkSqlVisualStyle.border(), 1, true),
        BorderFactory.createEmptyBorder(12, 12, 12, 12)));
    return panel;
  }

  static void bareSurface(JComponent component) {
    component.setBackground(LinkSqlVisualStyle.surface());
    component.setForeground(LinkSqlVisualStyle.text());
  }

  static JLabel caption(String caption) {
    JLabel label = new JLabel(caption);
    label.setFont(CAPTION);
    label.setForeground(LinkSqlVisualStyle.subtleText());
    return label;
  }

  static void field(JComponent field) {
    LinkSqlVisualStyle.input(field);
    field.setForeground(LinkSqlVisualStyle.text());
    field.setFont(BODY);
  }

  static void button(JButton button, String icon, boolean primary) {
    LinkSqlVisualStyle.button(button, button.getText(), icon, primary ? "primary" : "quiet");
  }

  static void destructiveButton(JButton button, String icon) {
    LinkSqlVisualStyle.button(button, button.getText(), icon, "danger");
  }

  static void tabs(JTabbedPane tabs) { LinkSqlVisualStyle.tabPane(tabs); }

  static JScrollPane scroll(Component content) {
    JScrollPane pane = new JScrollPane(content);
    pane.setBorder(BorderFactory.createLineBorder(LinkSqlVisualStyle.border()));
    pane.getViewport().setBackground(LinkSqlVisualStyle.surface());
    return pane;
  }

  static void list(JList<?> list, int rowHeight) {
    list.setFont(BODY);
    list.setFixedCellHeight(rowHeight);
    list.setBackground(LinkSqlVisualStyle.surface());
    list.setForeground(LinkSqlVisualStyle.text());
    list.setSelectionBackground(LinkSqlVisualStyle.rowSelection());
    list.setSelectionForeground(LinkSqlVisualStyle.text());
    list.setBorder(BorderFactory.createEmptyBorder(4, 4, 4, 4));
  }

  static void table(JTable table) {
    LinkSqlVisualStyle.resultsTable(table);
    JTableHeader header = table.getTableHeader();
    header.setReorderingAllowed(false);
    header.setFont(new Font("Segoe UI", Font.BOLD, 12));
  }

  static JTextArea note(String text) {
    JTextArea note = new JTextArea(text);
    note.setEditable(false);
    note.setLineWrap(true);
    note.setWrapStyleWord(true);
    note.setFocusable(false);
    note.setOpaque(false);
    note.setFont(BODY);
    note.setForeground(LinkSqlVisualStyle.subtleText());
    note.setBorder(BorderFactory.createEmptyBorder(8, 10, 8, 10));
    return note;
  }

  static JPanel footer() {
    JPanel panel = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 5));
    panel.setOpaque(false);
    return panel;
  }

  static JPanel section(String label, Component body) {
    JPanel panel = new JPanel(new BorderLayout(0, 9));
    panel.setOpaque(false);
    panel.add(caption(label), BorderLayout.NORTH);
    panel.add(body, BorderLayout.CENTER);
    return panel;
  }

  static void styleOptionPanel(JPanel panel) {
    panel.setBackground(LinkSqlVisualStyle.surface());
    panel.setBorder(BorderFactory.createEmptyBorder(12, 14, 12, 14));
  }
}
