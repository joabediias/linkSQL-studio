package br.com.santysqlstudio;

import javax.sql.DataSource;
import javax.swing.*;
import javax.swing.table.DefaultTableModel;
import javax.swing.table.TableColumn;
import java.awt.*;
import java.awt.datatransfer.StringSelection;
import java.sql.*;
import java.util.*;
import java.util.List;

/** Estrutura de tabelas do MySQL via DatabaseMetaData, sem executar SELECT * na tabela. */
final class TableMetadataDialog {
  private TableMetadataDialog() {}

  private record ColumnInfo(String name, String type, int size, int decimals,
                            boolean nullable, String key, String defaultValue,
                            String autoIncrement, String remarks) {}

  private static String optional(ResultSet rs, String column) {
    try { return rs.getString(column); }
    catch (SQLException ignored) { return null; }
  }

  private static List<ColumnInfo> fetch(DataSource ds, String table) throws SQLException {
    try (Connection conn = ds.getConnection()) {
      DatabaseMetaData meta = conn.getMetaData();
      String catalog = conn.getCatalog();
      Set<String> primary = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
      try (ResultSet rs = meta.getPrimaryKeys(catalog, null, table)) {
        while (rs.next()) {
          String key = rs.getString("COLUMN_NAME");
          if (key != null) primary.add(key);
        }
      }
      List<ColumnInfo> columns = new ArrayList<>();
      try (ResultSet rs = meta.getColumns(catalog, null, table, "%")) {
        while (rs.next()) {
          String name = rs.getString("COLUMN_NAME");
          String nullable = optional(rs, "IS_NULLABLE");
          String auto = optional(rs, "IS_AUTOINCREMENT");
          int size = rs.getInt("COLUMN_SIZE");
          int decimal = rs.getInt("DECIMAL_DIGITS");
          String type = rs.getString("TYPE_NAME");
          String defaultValue = optional(rs, "COLUMN_DEF");
          String remarks = optional(rs, "REMARKS");
          columns.add(new ColumnInfo(name, type, size, decimal,
              "YES".equalsIgnoreCase(nullable), primary.contains(name) ? "PK" : "",
              defaultValue, "YES".equalsIgnoreCase(auto) ? "Sim" : "Não", remarks));
        }
      }
      return columns;
    }
  }

  static void show(JFrame owner, DataSource source, String server, String table, String column) {
    JDialog dialog = new JDialog(owner, "Estrutura - " + server + "." + table, false);
    dialog.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
    JPanel body = new JPanel(new BorderLayout(8, 10));
    body.setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));
    JLabel heading = new JLabel("Servidor: " + server + "    Tabela: " + table);
    heading.setFont(heading.getFont().deriveFont(Font.BOLD));
    JLabel legend = new JLabel("Carregando metadados JDBC...");
    JPanel title = new JPanel(new BorderLayout());
    title.add(heading, BorderLayout.NORTH);
    title.add(legend, BorderLayout.SOUTH);
    body.add(title, BorderLayout.NORTH);
    JLabel waiting = new JLabel("Carregando informações das colunas...");
    waiting.setHorizontalAlignment(SwingConstants.CENTER);
    body.add(waiting, BorderLayout.CENTER);
    JButton close = new JButton("Fechar");
    close.addActionListener(e -> dialog.dispose());
    JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT));
    buttons.add(close);
    body.add(buttons, BorderLayout.SOUTH);
    dialog.setContentPane(body);
    dialog.setSize(1050, 530);
    dialog.setLocationRelativeTo(owner);

    SwingWorker<List<ColumnInfo>, Void> worker = new SwingWorker<>() {
      protected List<ColumnInfo> doInBackground() throws Exception {
        return fetch(source, table);
      }
      protected void done() {
        if (!dialog.isDisplayable() || isCancelled()) return;
        try {
          List<ColumnInfo> columns = get();
          if (columns.isEmpty()) {
            legend.setText("Nenhuma coluna encontrada.");
            waiting.setText("Verifique o catálogo e as permissões de acesso.");
            return;
          }
          Vector<String> headers = new Vector<>(List.of(
              "Coluna", "Tipo", "Tamanho", "Decimais", "Nulo?", "Chave",
              "Padrão", "Auto incremento", "Comentário"));
          Vector<Vector<Object>> rows = new Vector<>(columns.size());
          int selectedRow = -1;
          for (ColumnInfo info : columns) {
            if (column != null && column.equalsIgnoreCase(info.name())) selectedRow = rows.size();
            Vector<Object> row = new Vector<>();
            row.add(info.name());
            row.add(info.type());
            row.add(info.size() == 0 ? "" : info.size());
            row.add(info.decimals() == 0 ? "" : info.decimals());
            row.add(info.nullable() ? "Sim" : "Não");
            row.add(info.key());
            row.add(info.defaultValue() == null ? "" : info.defaultValue());
            row.add(info.autoIncrement());
            row.add(info.remarks() == null ? "" : info.remarks());
            rows.add(row);
          }
          DefaultTableModel model = new DefaultTableModel(rows, headers) {
            public boolean isCellEditable(int r, int c) { return false; }
          };
          JTable grid = new JTable(model);
          grid.setRowHeight(26);
          grid.setAutoResizeMode(JTable.AUTO_RESIZE_OFF);
          grid.getTableHeader().setReorderingAllowed(true);
          StudioGridHeaders.alignLeft(grid);
          int[] widths = {180, 110, 90, 85, 75, 75, 160, 125, 240};
          for (int i = 0; i < grid.getColumnCount(); i++) {
            TableColumn tc = grid.getColumnModel().getColumn(i);
            tc.setPreferredWidth(widths[i]);
          }
          JScrollPane scroll = new JScrollPane(grid);
          body.remove(waiting);
          body.add(scroll, BorderLayout.CENTER);
          if (selectedRow >= 0) {
            grid.setRowSelectionInterval(selectedRow, selectedRow);
            grid.scrollRectToVisible(grid.getCellRect(selectedRow, 0, true));
          }
          legend.setText(columns.size() + " coluna(s)   ·   PK = chave primária");
          JButton copy = new JButton("Copiar estrutura");
          copy.addActionListener(e -> {
            StringBuilder text = new StringBuilder();
            for (int c = 0; c < model.getColumnCount(); c++) {
              if (c > 0) text.append('\t');
              text.append(model.getColumnName(c));
            }
            text.append('\n');
            for (int r = 0; r < model.getRowCount(); r++) {
              for (int c = 0; c < model.getColumnCount(); c++) {
                if (c > 0) text.append('\t');
                Object value = model.getValueAt(r, c);
                if (value != null) text.append(value.toString().replace('\t', ' ').replace('\n', ' '));
              }
              text.append('\n');
            }
            Toolkit.getDefaultToolkit().getSystemClipboard().setContents(
                new StringSelection(text.toString()), null);
          });
          buttons.add(copy, 0);
          body.revalidate();
          body.repaint();
        } catch (Exception ex) {
          Throwable cause = ex;
          while (cause.getCause() != null) cause = cause.getCause();
          legend.setText("Erro ao carregar estrutura");
          waiting.setText("Não foi possível obter a estrutura: " + cause.getMessage());
        }
      }
    };
    worker.execute();
    dialog.setVisible(true);
  }
}
