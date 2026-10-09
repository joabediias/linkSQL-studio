package br.com.santysqlstudio;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.apache.calcite.adapter.jdbc.JdbcSchema;
import org.apache.calcite.jdbc.CalciteConnection;
import org.apache.calcite.schema.SchemaPlus;
import org.apache.commons.dbcp2.BasicDataSource;

import javax.swing.*;
import javax.swing.event.TreeSelectionEvent;
import javax.swing.table.DefaultTableModel;
import javax.swing.table.TableColumn;
import javax.swing.tree.*;
import java.awt.*;
import java.awt.event.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.sql.*;
import java.util.*;
import java.util.List;

public class Main {
  private static final ObjectMapper JSON = new ObjectMapper();
  private static final Map<String, BasicDataSource> SOURCES = new LinkedHashMap<>();
  private static Connection federation;
  private static Path configPath = Paths.get("config", "connections.example.json");
  private static final int ROW_LIMIT = 1000;
  private static JFrame frame;
  private static JLabel status;
  private static JTable results;
  private static JTabbedPane tabs;
  private static JTree explorer;
  private static DefaultMutableTreeNode rootNode;
  private static JButton execute;
  private static SwingWorker<DefaultTableModel, Void> currentQuery;

  public static void main(String[] args) {
    SwingUtilities.invokeLater(Main::createUi);
    Runtime.getRuntime().addShutdownHook(new Thread(Main::closeResources));
  }

  private static void createUi() {
    frame = new JFrame("SantySQL Studio - 0.4");
    frame.setDefaultCloseOperation(WindowConstants.EXIT_ON_CLOSE);
    frame.setSize(1250, 790);
    frame.setLocationRelativeTo(null);

    JButton load = new JButton("Carregar conexões");
    JButton manage = new JButton("Gerenciar conexões");
    JButton newTab = new JButton("Nova aba SQL");
    execute = new JButton("Executar SQL  (Ctrl+Enter)");
    JButton clear = new JButton("Limpar grade");
    JButton export = new JButton("Exportar CSV");
    JPanel toolbar = new JPanel(new FlowLayout(FlowLayout.LEFT, 7, 5));
    for (JButton b : new JButton[]{load, manage, newTab, execute, clear, export}) toolbar.add(b);

    rootNode = new DefaultMutableTreeNode("Conexões MySQL");
    explorer = new JTree(rootNode);
    explorer.setRootVisible(true);
    explorer.addTreeWillExpandListener(new javax.swing.event.TreeWillExpandListener() {
      public void treeWillExpand(javax.swing.event.TreeExpansionEvent event) {
        Object node = event.getPath().getLastPathComponent();
        if (node instanceof DefaultMutableTreeNode n) {
          if (n.getUserObject() instanceof SourceNode source && !source.loaded) loadTables(n, source.name);
          if (n.getUserObject() instanceof TableNode table && !table.loaded) loadColumns(n, table);
        }
      }
      public void treeWillCollapse(javax.swing.event.TreeExpansionEvent event) {}
    });
    explorer.addMouseListener(new MouseAdapter() {
      public void mouseClicked(MouseEvent e) {
        if (e.getClickCount() != 2) return;
        TreePath path = explorer.getPathForLocation(e.getX(), e.getY());
        if (path == null) return;
        DefaultMutableTreeNode node = (DefaultMutableTreeNode) path.getLastPathComponent();
        if (node.getUserObject() instanceof TableNode table) insertTableSql(table);
      }
    });
    JPopupMenu tableMenu = new JPopupMenu();
    JMenuItem queryTable = new JMenuItem("Consultar dados (100 linhas)");
    JMenuItem copyTable = new JMenuItem("Copiar nome completo");
    tableMenu.add(queryTable);
    tableMenu.add(copyTable);
    final TableNode[] selectedTable = new TableNode[1];
    explorer.addMouseListener(new MouseAdapter() {
      private void popup(MouseEvent e) {
        if (!e.isPopupTrigger()) return;
        TreePath path = explorer.getPathForLocation(e.getX(), e.getY());
        if (path == null) return;
        Object item = ((DefaultMutableTreeNode) path.getLastPathComponent()).getUserObject();
        if (!(item instanceof TableNode table)) return;
        selectedTable[0] = table;
        explorer.setSelectionPath(path);
        tableMenu.show(explorer, e.getX(), e.getY());
      }
      public void mousePressed(MouseEvent e) { popup(e); }
      public void mouseReleased(MouseEvent e) { popup(e); }
    });
    queryTable.addActionListener(e -> { if (selectedTable[0] != null) insertTableSql(selectedTable[0]); });
    copyTable.addActionListener(e -> {
      if (selectedTable[0] != null) {
        TableNode table = selectedTable[0];
        Toolkit.getDefaultToolkit().getSystemClipboard().setContents(
            new java.awt.datatransfer.StringSelection(qualifiedTable(table)), null);
      }
    });

    tabs = new JTabbedPane();
    addSqlTab();
    results = new JTable(new DefaultTableModel());
    results.setAutoResizeMode(JTable.AUTO_RESIZE_OFF);
    results.setAutoCreateRowSorter(true);
    results.setRowHeight(23);
    results.setCellSelectionEnabled(true);
    results.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 12));
    results.getTableHeader().setReorderingAllowed(true);
    JSplitPane vertical = new JSplitPane(JSplitPane.VERTICAL_SPLIT, tabs, new JScrollPane(results));
    vertical.setResizeWeight(.48);
    JSplitPane horizontal = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, new JScrollPane(explorer), vertical);
    horizontal.setResizeWeight(.19);
    horizontal.setDividerLocation(240);
    status = new JLabel(" Carregue as conexões para iniciar. | Limite de visualização: " + ROW_LIMIT + " linhas");
    status.setBorder(BorderFactory.createEmptyBorder(4, 4, 4, 4));
    frame.add(toolbar, BorderLayout.NORTH);
    frame.add(horizontal, BorderLayout.CENTER);
    frame.add(status, BorderLayout.SOUTH);

    load.addActionListener(e -> chooseAndLoad());
    manage.addActionListener(e -> manageConnections());
    newTab.addActionListener(e -> addSqlTab());
    execute.addActionListener(e -> runQuery());
    clear.addActionListener(e -> results.setModel(new DefaultTableModel()));
    export.addActionListener(e -> exportCsv());
    frame.setVisible(true);
  }

  private static void addSqlTab() {
    JTextArea area = new JTextArea("", 15, 60);
    area.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 14));
    area.setTabSize(2);
    if (tabs.getTabCount() == 0) area.setText("SELECT *\nFROM SRV_ARRAIAL.cad_usu0\nFETCH NEXT 100 ROWS ONLY;");
    area.getInputMap().put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, InputEvent.CTRL_DOWN_MASK), "runSql");
    area.getActionMap().put("runSql", new AbstractAction() { public void actionPerformed(ActionEvent e) { runQuery(); } });
    String title = "SQL " + (tabs.getTabCount() + 1);
    tabs.addTab(title, new JScrollPane(area));
    tabs.setSelectedIndex(tabs.getTabCount() - 1);
  }

  private static JTextArea activeEditor() {
    JScrollPane pane = (JScrollPane) tabs.getSelectedComponent();
    return (JTextArea) pane.getViewport().getView();
  }

  private static void chooseAndLoad() {
    JFileChooser chooser = new JFileChooser(configPath.toAbsolutePath().getParent().toFile());
    chooser.setSelectedFile(configPath.toFile());
    if (chooser.showOpenDialog(frame) == JFileChooser.APPROVE_OPTION) {
      configPath = chooser.getSelectedFile().toPath();
      loadConfiguration();
    }
  }

  private static void loadConfiguration() {
    try {
      configure(configPath);
      rebuildTree();
      status.setText(" " + SOURCES.size() + " conexões carregadas de " + configPath.getFileName());
    } catch (Exception ex) { showError(ex); }
  }

  private static void rebuildTree() {
    rootNode.removeAllChildren();
    for (String name : SOURCES.keySet()) {
      DefaultMutableTreeNode node = new DefaultMutableTreeNode(new SourceNode(name));
      node.add(new DefaultMutableTreeNode("Expandir para carregar tabelas..."));
      rootNode.add(node);
    }
    ((DefaultTreeModel) explorer.getModel()).reload();
    explorer.expandRow(0);
  }

  private static void loadTables(DefaultMutableTreeNode node, String name) {
    SourceNode source = (SourceNode) node.getUserObject();
    source.loaded = true;
    node.removeAllChildren();
    node.add(new DefaultMutableTreeNode("Carregando..."));
    ((DefaultTreeModel) explorer.getModel()).reload(node);
    new SwingWorker<List<String>, Void>() {
      protected List<String> doInBackground() throws Exception {
        List<String> tables = new ArrayList<>();
        try (Connection c = SOURCES.get(name).getConnection(); ResultSet rs = c.getMetaData().getTables(c.getCatalog(), null, "%", new String[]{"TABLE", "VIEW"})) {
          while (rs.next()) tables.add(rs.getString("TABLE_NAME"));
        }
        Collections.sort(tables);
        return tables;
      }
      protected void done() {
        node.removeAllChildren();
        try {
          for (String table : get()) {
            DefaultMutableTreeNode tableNode = new DefaultMutableTreeNode(new TableNode(name, table));
            tableNode.add(new DefaultMutableTreeNode("Expandir para carregar colunas..."));
            node.add(tableNode);
          }
          status.setText(" Tabelas de " + name + " carregadas.");
        } catch (Exception ex) { source.loaded = false; node.add(new DefaultMutableTreeNode("Erro: tente expandir novamente")); showError(ex); }
        ((DefaultTreeModel) explorer.getModel()).reload(node);
        explorer.expandPath(new TreePath(node.getPath()));
      }
    }.execute();
  }

  private static String qualifiedTable(TableNode table) {
    return table.source + ".\"" + table.table.replace("\"", "\"\"") + "\"";
  }

  private static void insertTableSql(TableNode table) {
    JTextArea editor = activeEditor();
    editor.insert("SELECT * FROM " + qualifiedTable(table) + " FETCH NEXT 100 ROWS ONLY;\n",
        editor.getCaretPosition());
    editor.requestFocusInWindow();
  }

  private static void loadColumns(DefaultMutableTreeNode node, TableNode table) {
    table.loaded = true;
    node.removeAllChildren();
    node.add(new DefaultMutableTreeNode("Carregando colunas..."));
    ((DefaultTreeModel) explorer.getModel()).reload(node);
    new SwingWorker<List<String>, Void>() {
      protected List<String> doInBackground() throws Exception {
        List<String> columns = new ArrayList<>();
        try (Connection c = SOURCES.get(table.source).getConnection();
             ResultSet rs = c.getMetaData().getColumns(c.getCatalog(), null, table.table, "%")) {
          while (rs.next()) {
            String name = rs.getString("COLUMN_NAME");
            String type = rs.getString("TYPE_NAME");
            int size = rs.getInt("COLUMN_SIZE");
            columns.add(name + "  :  " + type + (size > 0 ? "(" + size + ")" : ""));
          }
        }
        return columns;
      }
      protected void done() {
        node.removeAllChildren();
        try {
          List<String> columns = get();
          for (String column : columns) node.add(new DefaultMutableTreeNode(column));
          status.setText(" " + columns.size() + " colunas de " + table.table + ".");
        } catch (Exception ex) {
          table.loaded = false;
          node.add(new DefaultMutableTreeNode("Erro ao carregar colunas"));
          showError(ex);
        }
        ((DefaultTreeModel) explorer.getModel()).reload(node);
        explorer.expandPath(new TreePath(node.getPath()));
      }
    }.execute();
  }

  // Divide comandos somente em ponto e virgula fora de strings e comentarios.
  private static String statementAtCaret(JTextArea editor) {
    String sql = editor.getText();
    int caret = editor.getCaretPosition();
    List<int[]> parts = new ArrayList<>();
    int start = 0;
    char quote = 0;
    boolean lineComment = false, blockComment = false;
    for (int i = 0; i < sql.length(); i++) {
      char ch = sql.charAt(i);
      char next = i + 1 < sql.length() ? sql.charAt(i + 1) : 0;
      if (lineComment) { if (ch == '\n') lineComment = false; continue; }
      if (blockComment) { if (ch == '*' && next == '/') { blockComment = false; i++; } continue; }
      if (quote != 0) {
        if (ch == quote) {
          if (next == quote) i++;
          else quote = 0;
        } else if (ch == '\\' && next != 0) i++;
        continue;
      }
      if (ch == '-' && next == '-' && (i + 2 == sql.length() || Character.isWhitespace(sql.charAt(i + 2)))) { lineComment = true; i++; continue; }
      if (ch == '#') { lineComment = true; continue; }
      if (ch == '/' && next == '*') { blockComment = true; i++; continue; }
      if (ch == '\'' || ch == '"' || ch == '`') { quote = ch; continue; }
      if (ch == ';') { parts.add(new int[]{start, i + 1}); start = i + 1; }
    }
    parts.add(new int[]{start, sql.length()});
    for (int[] part : parts) {
      if (caret >= part[0] && caret <= part[1] && !sql.substring(part[0], part[1]).isBlank())
        return sql.substring(part[0], part[1]);
    }
    return "";
  }

  private static void runQuery() {
    if (federation == null) { status.setText(" Carregue as conexões primeiro."); return; }
    if (currentQuery != null && !currentQuery.isDone()) return;
    String sql = activeEditor().getSelectedText();
    if (sql == null || sql.isBlank()) sql = statementAtCaret(activeEditor());
    sql = sql.trim();
    while (sql.endsWith(";")) sql = sql.substring(0, sql.length() - 1).trim();
    if (sql.isBlank()) { status.setText(" Posicione o cursor em uma consulta ou selecione o SQL."); return; }
    final String query = sql;
    execute.setEnabled(false);
    status.setText(" Executando consulta...");
    currentQuery = new SwingWorker<>() {
      protected DefaultTableModel doInBackground() throws Exception {
        try (Statement stmt = federation.createStatement()) {
          stmt.setMaxRows(ROW_LIMIT);
          stmt.setQueryTimeout(120);
          try (ResultSet rs = stmt.executeQuery(query)) {
            ResultSetMetaData meta = rs.getMetaData();
            String[] headers = new String[meta.getColumnCount()];
            for (int i = 0; i < headers.length; i++) headers[i] = meta.getColumnLabel(i + 1);
            DefaultTableModel model = new DefaultTableModel(headers, 0) {
              public boolean isCellEditable(int row, int col) { return false; }
            };
            while (rs.next() && model.getRowCount() < ROW_LIMIT) {
              Object[] row = new Object[headers.length];
              for (int i = 0; i < row.length; i++) row[i] = rs.getObject(i + 1);
              model.addRow(row);
            }
            return model;
          }
        }
      }
      protected void done() {
        execute.setEnabled(true);
        try {
          DefaultTableModel model = get();
          results.setModel(model);
          configureColumns();
          status.setText(" Consulta concluída: " + model.getRowCount() + " linhas (limite: " + ROW_LIMIT + ").");
        } catch (Exception ex) { status.setText(" Falha na consulta."); showError(ex); }
      }
    };
    currentQuery.execute();
  }

  private static void configureColumns() {
    for (int c = 0; c < results.getColumnCount(); c++) {
      TableColumn column = results.getColumnModel().getColumn(c);
      String header = results.getColumnName(c);
      int width = Math.max(110, Math.min(300, header.length() * 9 + 24));
      int samples = Math.min(results.getRowCount(), 30);
      for (int r = 0; r < samples; r++) {
        Object value = results.getValueAt(r, c);
        if (value != null) width = Math.max(width, Math.min(300, value.toString().length() * 7 + 22));
      }
      column.setPreferredWidth(width);
    }
  }

  private static void exportCsv() {
    if (results.getColumnCount() == 0) { status.setText(" Execute uma consulta antes de exportar."); return; }
    JFileChooser chooser = new JFileChooser();
    chooser.setSelectedFile(new File("resultado.csv"));
    if (chooser.showSaveDialog(frame) != JFileChooser.APPROVE_OPTION) return;
    Path path = chooser.getSelectedFile().toPath();
    try (BufferedWriter out = Files.newBufferedWriter(path, StandardCharsets.UTF_8)) {
      out.write('\uFEFF');
      for (int c = 0; c < results.getColumnCount(); c++) {
        if (c > 0) out.write(';');
        out.write(csv(results.getColumnName(c)));
      }
      out.newLine();
      for (int r = 0; r < results.getRowCount(); r++) {
        for (int c = 0; c < results.getColumnCount(); c++) {
          if (c > 0) out.write(';');
          Object value = results.getModel().getValueAt(r, c);
          out.write(csv(value == null ? "" : value.toString()));
        }
        out.newLine();
      }
      status.setText(" CSV exportado: " + path + " (" + results.getRowCount() + " linhas exibidas).");
    } catch (Exception ex) { showError(ex); }
  }

  private static String csv(String value) {
    return "\"" + value.replace("\"", "\"\"") + "\"";
  }

  private static void manageConnections() {
    try {
      ObjectNode document;
      if (Files.exists(configPath)) document = (ObjectNode) JSON.readTree(Files.readString(configPath));
      else { document = JSON.createObjectNode(); document.set("connections", JSON.createArrayNode()); }
      ArrayNode list = (ArrayNode) document.withArray("connections");
      String[] labels = new String[list.size()];
      for (int i = 0; i < labels.length; i++) labels[i] = list.get(i).path("name").asText();
      String[] actions = {"Adicionar", "Editar", "Excluir", "Cancelar"};
      int action = JOptionPane.showOptionDialog(frame, "Conexões configuradas: " + String.join(", ", labels) + "\nArquivo: " + configPath,
          "Gerenciar conexões", JOptionPane.DEFAULT_OPTION, JOptionPane.PLAIN_MESSAGE, null, actions, actions[0]);
      if (action == 3 || action == JOptionPane.CLOSED_OPTION) return;
      int index = -1;
      if (action == 1 || action == 2) {
        if (labels.length == 0) { JOptionPane.showMessageDialog(frame, "Não há conexões cadastradas."); return; }
        Object selected = JOptionPane.showInputDialog(frame, "Selecione a conexão", "Conexões", JOptionPane.PLAIN_MESSAGE, null, labels, labels[0]);
        if (selected == null) return;
        for (int i = 0; i < labels.length; i++) if (labels[i].equals(selected)) index = i;
      }
      if (action == 2) {
        if (JOptionPane.showConfirmDialog(frame, "Excluir " + labels[index] + "?", "Confirmar", JOptionPane.YES_NO_OPTION) != JOptionPane.YES_OPTION) return;
        list.remove(index);
      } else {
        JsonNode previous = index < 0 ? JSON.createObjectNode() : list.get(index);
        JTextField name = new JTextField(previous.path("name").asText(), 23);
        JTextField host = new JTextField(previous.path("host").asText(), 23);
        JTextField port = new JTextField(previous.path("port").asText("4407"), 23);
        JTextField database = new JTextField(previous.path("database").asText(), 23);
        JTextField user = new JTextField(previous.path("user").asText("consulta"), 23);
        JTextField env = new JTextField(previous.path("passwordEnv").asText("SQLBRIDGE_MYSQL_PASSWORD"), 23);
        JPanel form = new JPanel(new GridLayout(0, 2, 7, 7));
        for (Object[] field : new Object[][]{{"Nome", name}, {"Host/IP", host}, {"Porta", port}, {"Banco", database}, {"Usuário", user}, {"Variável de senha", env}}) {
          form.add(new JLabel((String) field[0])); form.add((JTextField) field[1]);
        }
        if (JOptionPane.showConfirmDialog(frame, form, "Dados da conexão", JOptionPane.OK_CANCEL_OPTION) != JOptionPane.OK_OPTION) return;
        if (!name.getText().matches("[A-Za-z][A-Za-z0-9_]*") || host.getText().isBlank() || database.getText().isBlank() || env.getText().isBlank()) {
          JOptionPane.showMessageDialog(frame, "Preencha os campos obrigatórios e use nome sem espaços."); return;
        }
        ObjectNode item = JSON.createObjectNode();
        item.put("name", name.getText().trim()); item.put("host", host.getText().trim());
        item.put("port", Integer.parseInt(port.getText().trim())); item.put("database", database.getText().trim());
        item.put("user", user.getText().trim()); item.put("passwordEnv", env.getText().trim());
        if (index < 0) list.add(item); else list.set(index, item);
      }
      Files.createDirectories(configPath.toAbsolutePath().getParent());
      JSON.writerWithDefaultPrettyPrinter().writeValue(configPath.toFile(), document);
      status.setText(" Configuração salva em " + configPath + ". Clique em Carregar conexões para atualizar.");
    } catch (Exception ex) { showError(ex); }
  }

  private static synchronized void configure(Path path) throws Exception {
    JsonNode root = JSON.readTree(Files.readString(path));
    Map<String, BasicDataSource> nextSources = new LinkedHashMap<>();
    Connection next = DriverManager.getConnection("jdbc:calcite:caseSensitive=false");
    try {
      SchemaPlus schemas = next.unwrap(CalciteConnection.class).getRootSchema();
      Set<String> names = new HashSet<>();
      for (JsonNode item : root.path("connections")) {
        String name = item.path("name").asText();
        if (!name.matches("[A-Za-z][A-Za-z0-9_]*") || !names.add(name.toUpperCase(Locale.ROOT)))
          throw new IllegalArgumentException("Nome inválido ou duplicado: " + name);
        String passwordEnv = item.path("passwordEnv").asText();
        String password = System.getenv(passwordEnv);
        if (password == null) throw new IllegalArgumentException("Variável de ambiente ausente: " + passwordEnv);
        BasicDataSource ds = new BasicDataSource();
        ds.setDriverClassName("com.mysql.cj.jdbc.Driver");
        ds.setUrl("jdbc:mysql://" + item.path("host").asText() + ":" + item.path("port").asInt(4407) + "/" + item.path("database").asText() + "?useSSL=false&connectTimeout=5000&socketTimeout=120000");
        ds.setUsername(item.path("user").asText());
        ds.setPassword(password);
        ds.setMaxTotal(2); ds.setMaxIdle(1); ds.setInitialSize(0);
        nextSources.put(name, ds);
        schemas.add(name, JdbcSchema.create(schemas, name, ds, null, null));
      }
      Connection previous = federation;
      Map<String, BasicDataSource> old = new LinkedHashMap<>(SOURCES);
      federation = next;
      SOURCES.clear(); SOURCES.putAll(nextSources);
      if (previous != null) previous.close();
      for (BasicDataSource ds : old.values()) try { ds.close(); } catch (SQLException ignored) {}
    } catch (Exception ex) {
      next.close();
      for (BasicDataSource ds : nextSources.values()) try { ds.close(); } catch (SQLException ignored) {}
      throw ex;
    }
  }

  private static void closeResources() {
    try { if (federation != null) federation.close(); } catch (Exception ignored) {}
    for (BasicDataSource ds : SOURCES.values()) try { ds.close(); } catch (SQLException ignored) {}
  }

  private static void showError(Exception ex) {
    Throwable cause = ex;
    while (cause.getCause() != null) cause = cause.getCause();
    JOptionPane.showMessageDialog(frame, String.valueOf(cause.getMessage()), "Erro", JOptionPane.ERROR_MESSAGE);
  }

  private static class SourceNode {
    final String name;
    boolean loaded;
    SourceNode(String name) { this.name = name; }
    public String toString() { return name; }
  }
  private static class TableNode {
    final String source, table;
    boolean loaded;
    TableNode(String source, String table) { this.source = source; this.table = table; }
    public String toString() { return table; }
  }
}
