package br.com.linksql;

import javax.swing.*;
import javax.swing.table.JTableHeader;
import javax.swing.table.TableCellRenderer;
import java.awt.Component;

/** Alinha os titulos como os dados, preservando o renderer do tema e o icone de ordenacao. */
final class LinkSqlGridHeaders {
  private LinkSqlGridHeaders() {}

  static void alignLeft(JTable table) {
    JTableHeader header = table.getTableHeader();
    final TableCellRenderer themeRenderer = header.getDefaultRenderer();
    header.setDefaultRenderer((grid, value, isSelected, hasFocus, row, column) -> {
      Component rendered = themeRenderer.getTableCellRendererComponent(
          grid, value, isSelected, hasFocus, row, column);
      if (rendered instanceof JLabel label) label.setHorizontalAlignment(SwingConstants.LEFT);
      return rendered;
    });
    header.repaint();
  }
}
