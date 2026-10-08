package br.com.santysqlstudio;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.calcite.adapter.jdbc.JdbcSchema;
import org.apache.calcite.jdbc.CalciteConnection;
import org.apache.calcite.schema.SchemaPlus;
import org.apache.commons.dbcp2.BasicDataSource;

import javax.swing.*;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.nio.file.*;
import java.sql.*;
import java.util.*;
import java.util.List;

public class Main {
  private static final List<BasicDataSource> SOURCES = new ArrayList<>();
  private static Connection connection;

  public static void main(String[] args) {
    SwingUtilities.invokeLater(() -> {
      JFrame frame = new JFrame("SantySQL Studio — protótipo 0.2");
      frame.setDefaultCloseOperation(WindowConstants.EXIT_ON_CLOSE);
      frame.setSize(1050, 700);
      JTextArea editor = new JTextArea("SELECT * FROM SRV_ARRAIAL.SUA_TABELA FETCH NEXT 100 ROWS ONLY", 10, 80);
      editor.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 14));
      JTable results = new JTable();
      JLabel status = new JLabel("Carregue o arquivo de conexões para iniciar.");
      JButton load = new JButton("Carregar conexões");
      JButton run = new JButton("Executar SQL");
      JButton clear = new JButton("Limpar resultados");
      JPanel actions = new JPanel(new FlowLayout(FlowLayout.LEFT));
      actions.add(load); actions.add(run); actions.add(clear);
      JPanel top = new JPanel(new BorderLayout());
      top.add(actions, BorderLayout.NORTH); top.add(new JScrollPane(editor), BorderLayout.CENTER);
      JSplitPane split = new JSplitPane(JSplitPane.VERTICAL_SPLIT, top, new JScrollPane(results));
      split.setResizeWeight(.45);
      frame.add(split, BorderLayout.CENTER); frame.add(status, BorderLayout.SOUTH);
      load.addActionListener(e -> {
        JFileChooser chooser = new JFileChooser("config");
        if (chooser.showOpenDialog(frame) == JFileChooser.APPROVE_OPTION) {
          try { configure(chooser.getSelectedFile().toPath()); status.setText("Conexões carregadas. Execute um SELECT."); }
          catch (Exception ex) { showError(frame, ex); }
        }
      });
      run.addActionListener(e -> {
        if (connection == null) { status.setText("Carregue as conexões primeiro."); return; }
        String sql = editor.getText().trim();
        // Calcite executa uma instrução por vez e não aceita ";" no final.
        while (sql.endsWith(";")) {
          sql = sql.substring(0, sql.length() - 1).trim();
        }
        if (sql.isEmpty()) {
          status.setText("Digite uma consulta SQL.");
          return;
        }
        final String query = sql;
        run.setEnabled(false); status.setText("Executando consulta...");
        new SwingWorker<DefaultTableModel, Void>() {
          protected DefaultTableModel doInBackground() throws Exception {
            try (Statement stmt = connection.createStatement()) {
              stmt.setMaxRows(1000); stmt.setQueryTimeout(120);
              try (ResultSet rs = stmt.executeQuery(query)) {
                ResultSetMetaData meta = rs.getMetaData();
                String[] columns = new String[meta.getColumnCount()];
                for (int i=0;i<columns.length;i++) columns[i]=meta.getColumnLabel(i+1);
                DefaultTableModel model = new DefaultTableModel(columns, 0);
                while (rs.next()) {
                  Object[] row = new Object[columns.length];
                  for (int i=0;i<row.length;i++) row[i]=rs.getObject(i+1);
                  model.addRow(row);
                }
                return model;
              }
            }
          }
          protected void done() {
            run.setEnabled(true);
            try { DefaultTableModel model=get(); results.setModel(model); status.setText("Consulta concluída: "+model.getRowCount()+" linhas (limite de visualização: 1000)."); }
            catch (Exception ex) { status.setText("Falha na consulta."); showError(frame, ex); }
          }
        }.execute();
      });
      clear.addActionListener(e -> results.setModel(new DefaultTableModel()));
      frame.setLocationRelativeTo(null); frame.setVisible(true);
    });
    Runtime.getRuntime().addShutdownHook(new Thread(() -> {
      try { if (connection!=null) connection.close(); } catch (Exception ignored) {}
      for (BasicDataSource ds : SOURCES) {
        try { ds.close(); } catch (java.sql.SQLException ex) { ex.printStackTrace(); }
      }
    }));
  }

  private static void configure(Path path) throws Exception {
    JsonNode root = new ObjectMapper().readTree(Files.readString(path));
    if (connection != null) connection.close();
    for (BasicDataSource ds : SOURCES) ds.close();
    SOURCES.clear();
    Connection next = DriverManager.getConnection("jdbc:calcite:caseSensitive=false");
    CalciteConnection calcite = next.unwrap(CalciteConnection.class);
    SchemaPlus schemas = calcite.getRootSchema();
    Set<String> names = new HashSet<>();
    try {
      for (JsonNode item : root.path("connections")) {
        String name = item.path("name").asText();
        if (!name.matches("[A-Za-z][A-Za-z0-9_]*") || !names.add(name.toUpperCase(Locale.ROOT)))
          throw new IllegalArgumentException("Nome de conexão inválido ou duplicado: "+name);
        String passwordEnv = item.path("passwordEnv").asText();
        String password = System.getenv(passwordEnv);
        if (password == null) throw new IllegalArgumentException("Variável de ambiente ausente: "+passwordEnv);
        BasicDataSource ds = new BasicDataSource();
        ds.setDriverClassName("com.mysql.cj.jdbc.Driver");
        ds.setUrl("jdbc:mysql://"+item.path("host").asText()+":"+item.path("port").asInt(4407)+"/"+item.path("database").asText()+"?useSSL=false&connectTimeout=5000&socketTimeout=120000");
        ds.setUsername(item.path("user").asText()); ds.setPassword(password);
        ds.setMaxTotal(2); ds.setMaxIdle(1); ds.setInitialSize(0);
        SOURCES.add(ds);
        schemas.add(name, JdbcSchema.create(schemas, name, ds, null, null));
      }
      connection = next;
    } catch (Exception ex) { next.close(); throw ex; }
  }
  private static void showError(Component parent, Exception ex) {
    Throwable cause = ex;
    while (cause.getCause()!=null) cause=cause.getCause();
    JOptionPane.showMessageDialog(parent, cause.getMessage(), "Erro", JOptionPane.ERROR_MESSAGE);
  }
}
