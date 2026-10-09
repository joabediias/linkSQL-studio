package br.com.santysqlstudio;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.apache.calcite.adapter.jdbc.JdbcSchema;
import org.apache.calcite.jdbc.CalciteConnection;
import org.apache.calcite.schema.SchemaPlus;
import org.apache.commons.dbcp2.BasicDataSource;
import com.formdev.flatlaf.FlatDarkLaf;
import com.formdev.flatlaf.FlatLightLaf;
import org.fife.ui.rsyntaxtextarea.RSyntaxTextArea;
import org.fife.ui.rsyntaxtextarea.SyntaxConstants;
import org.fife.ui.rsyntaxtextarea.Theme;
import org.fife.ui.rtextarea.RTextScrollPane;

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
import java.util.prefs.Preferences;

public class Main {
  private static final ObjectMapper JSON = new ObjectMapper();
  private static final Map<String, BasicDataSource> SOURCES = new LinkedHashMap<>();
  private static Connection federation;
  private static Path configPath = Paths.get("config", "connections.example.json");
  private static final int ROW_LIMIT = 1000;
  private static final Preferences PREFS = Preferences.userNodeForPackage(Main.class);
  private static boolean darkMode = PREFS.getBoolean("darkMode", true);
  private static JFrame frame;
  private static JButton themeButton;
  private static JPanel toolbar;
  private static JLabel resultSummary;
  private static long queryStarted;
  private static JLabel status;
  private static JTable results;
  private static JTabbedPane tabs;
  private static JTree explorer;
  private static DefaultMutableTreeNode rootNode;
  private static JButton execute;
  private static SwingWorker<DefaultTableModel, Void> currentQuery;
  private static final Map<Component, Path> OPEN_FILES = new IdentityHashMap<>();
  private static JTextField tableSearch;
  // Cache de metadados: consultas JDBC executadas fora da thread da interface.
  private static final Map<String, List<String>> TABLE_CACHE = new java.util.concurrent.ConcurrentHashMap<>();
  private static final Map<String, List<String>> COLUMN_CACHE = new java.util.concurrent.ConcurrentHashMap<>();
  private static final Set<String> TABLE_LOADING = java.util.concurrent.ConcurrentHashMap.newKeySet();
  private static final Set<String> COLUMN_LOADING = java.util.concurrent.ConcurrentHashMap.newKeySet();

  private static final String[] SQL_WORDS = {"SELECT", "FROM", "WHERE", "JOIN", "LEFT JOIN", "RIGHT JOIN", "INNER JOIN", "ON", "GROUP BY", "ORDER BY", "HAVING", "LIMIT", "UNION ALL", "DISTINCT", "AS", "AND", "OR", "NOT", "IS NULL", "IN", "EXISTS", "WITH", "CASE", "WHEN", "THEN", "ELSE", "END", "COUNT", "SUM", "AVG", "MIN", "MAX", "FETCH NEXT", "ROWS ONLY"};

  public static void main(String[] args) {
    try { applyLookAndFeel(); } catch (Exception ex) { ex.printStackTrace(); }
    SwingUtilities.invokeLater(Main::createUi);
    Runtime.getRuntime().addShutdownHook(new Thread(Main::closeResources));
  }

  private static void applyLookAndFeel() throws UnsupportedLookAndFeelException {
    UIManager.setLookAndFeel(darkMode ? new FlatDarkLaf() : new FlatLightLaf());
    UIManager.put("Button.arc", 10);
    UIManager.put("Component.arc", 9);
    UIManager.put("TextComponent.arc", 8);
    UIManager.put("TabbedPane.showTabSeparators", true);
    UIManager.put("Table.showHorizontalLines", true);
    UIManager.put("Table.rowHeight", 28);
    UIManager.put("Tree.rowHeight", 24);
  }

  private static void toggleTheme() {
    darkMode = !darkMode;
    PREFS.putBoolean("darkMode", darkMode);
    try {
      applyLookAndFeel();
      SwingUtilities.updateComponentTreeUI(frame);
      for (int i = 0; i < tabs.getTabCount(); i++) {
        RSyntaxTextArea editor = (RSyntaxTextArea) ((JScrollPane) tabs.getComponentAt(i)).getViewport().getView();
        styleEditor(editor);
      }
      themeButton.setText(darkMode ? "☀  Tema claro" : "☾  Tema escuro");
      frame.repaint();
    } catch (Exception ex) { showError(ex); }
  }

  private static void styleEditor(RSyntaxTextArea area) {
    String resource = darkMode ? "/org/fife/ui/rsyntaxtextarea/themes/dark.xml" : "/org/fife/ui/rsyntaxtextarea/themes/default.xml";
    try (InputStream in = Main.class.getResourceAsStream(resource)) {
      if (in != null) Theme.load(in).apply(area);
    } catch (IOException ex) { ex.printStackTrace(); }
    area.setBackground(UIManager.getColor("TextArea.background"));
    area.setForeground(UIManager.getColor("TextArea.foreground"));
    area.setCurrentLineHighlightColor(darkMode ? new Color(48, 55, 69) : new Color(233, 240, 250));
    area.setCaretColor(UIManager.getColor("TextArea.foreground"));
    area.setSelectionColor(darkMode ? new Color(57, 93, 148) : new Color(178, 210, 249));
  }

  private static void createUi() {
    frame = new JFrame("SantySQL Studio - 0.7.1.1");
    frame.setDefaultCloseOperation(WindowConstants.EXIT_ON_CLOSE);
    frame.setSize(1360, 840);
    frame.setMinimumSize(new Dimension(900, 600));
    frame.setLocationRelativeTo(null);

    JButton load = new JButton("Carregar conexões");
    JButton manage = new JButton("Gerenciar conexões");
    JButton searchColumns = new JButton("Buscar coluna");
    JButton newTab = new JButton("Nova aba SQL");
    JButton openSql = new JButton("Abrir SQL");
    JButton saveSql = new JButton("Salvar SQL");
    execute = new JButton("Executar SQL  (Ctrl+Enter)");
    JButton clear = new JButton("Limpar grade");
    JButton export = new JButton("Exportar CSV");
    themeButton = new JButton(darkMode ? "☀  Tema claro" : "☾  Tema escuro");
    toolbar = new JPanel(new FlowLayout(FlowLayout.LEFT, 9, 9));
    toolbar.setBorder(BorderFactory.createEmptyBorder(2, 8, 2, 8));
    execute.putClientProperty("JButton.buttonType", "roundRect");
    execute.setBackground(new Color(40, 116, 218));
    execute.setForeground(Color.WHITE);
    for (JButton b : new JButton[]{load, manage, searchColumns, newTab, openSql, saveSql, execute, clear, export, themeButton}) toolbar.add(b);

    rootNode = new DefaultMutableTreeNode("Conexões MySQL");
    explorer = new JTree(rootNode);
    explorer.setRootVisible(true);
    explorer.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 13));
    explorer.setShowsRootHandles(true);
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

    searchColumns.addActionListener(e -> searchColumnsAcrossTables());

    tabs = new JTabbedPane();
    addSqlTab();
    results = new JTable(new DefaultTableModel());
    results.setAutoResizeMode(JTable.AUTO_RESIZE_OFF);
    results.setAutoCreateRowSorter(true);
    results.setRowHeight(28);
    results.setShowHorizontalLines(true);
    results.setShowVerticalLines(false);
    results.setCellSelectionEnabled(true);
    results.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 13));
    results.getTableHeader().setFont(new Font(Font.SANS_SERIF, Font.BOLD, 12));
    results.getTableHeader().setReorderingAllowed(true);
    JPanel resultPanel = new JPanel(new BorderLayout());
    resultSummary = new JLabel("  RESULTADOS  ·  Nenhuma consulta executada");
    resultSummary.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 12));
    resultSummary.setBorder(BorderFactory.createEmptyBorder(9, 8, 9, 8));
    resultPanel.add(resultSummary, BorderLayout.NORTH);
    resultPanel.add(new JScrollPane(results), BorderLayout.CENTER);
    JSplitPane vertical = new JSplitPane(JSplitPane.VERTICAL_SPLIT, tabs, resultPanel);
    vertical.setResizeWeight(.48);
    JPanel explorerPanel = new JPanel(new BorderLayout(0, 4));
    tableSearch = new JTextField();
    tableSearch.putClientProperty("JTextField.placeholderText", "Buscar tabela (Enter)...");
    tableSearch.setToolTipText("Busca entre as tabelas dos servidores já expandidos; Enter encontra a próxima.");
    tableSearch.addActionListener(e -> findTable());
    explorerPanel.add(tableSearch, BorderLayout.NORTH);
    explorerPanel.add(new JScrollPane(explorer), BorderLayout.CENTER);
    JSplitPane horizontal = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, explorerPanel, vertical);
    horizontal.setResizeWeight(.19);
    horizontal.setDividerLocation(275);
    status = new JLabel(" Carregue as conexões para iniciar. | Limite de visualização: " + ROW_LIMIT + " linhas");
    status.setBorder(BorderFactory.createEmptyBorder(4, 4, 4, 4));
    JPanel top = new JPanel(new BorderLayout());
    JLabel brand = new JLabel("  ◈  SantySQL Studio   /   SQL federado");
    brand.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 15));
    brand.setBorder(BorderFactory.createEmptyBorder(12, 10, 8, 10));
    top.add(brand, BorderLayout.NORTH);
    top.add(toolbar, BorderLayout.CENTER);
    frame.add(top, BorderLayout.NORTH);
    frame.add(horizontal, BorderLayout.CENTER);
    frame.add(status, BorderLayout.SOUTH);

    load.addActionListener(e -> chooseAndLoad());
    manage.addActionListener(e -> manageConnections());
    newTab.addActionListener(e -> addSqlTab());
    openSql.addActionListener(e -> openSqlFile());
    saveSql.addActionListener(e -> saveSqlFile(false));
    execute.addActionListener(e -> runQuery());
    clear.addActionListener(e -> results.setModel(new DefaultTableModel()));
    export.addActionListener(e -> exportCsv());
    themeButton.addActionListener(e -> toggleTheme());
    frame.setVisible(true);
  }

  private static void addSqlTab() {
    RSyntaxTextArea area = new RSyntaxTextArea(15, 60);
    area.setSyntaxEditingStyle(SyntaxConstants.SYNTAX_STYLE_SQL);
    area.setCodeFoldingEnabled(true);
    area.setAntiAliasingEnabled(true);
    area.setMarkOccurrences(true);
    area.setBracketMatchingEnabled(true);
    area.setPaintTabLines(true);
    styleEditor(area);
    area.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 14));
    area.setTabSize(2);
    if (tabs.getTabCount() == 0) area.setText("SELECT *\nFROM SRV_ARRAIAL.cad_usu0\nFETCH NEXT 100 ROWS ONLY;");
    area.getInputMap().put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, InputEvent.CTRL_DOWN_MASK), "runSql");
    area.getInputMap().put(KeyStroke.getKeyStroke(KeyEvent.VK_SPACE, InputEvent.CTRL_DOWN_MASK), "sqlComplete");
    area.getActionMap().put("sqlComplete", new AbstractAction() { public void actionPerformed(ActionEvent e) { showCompletions(area); } });
    area.getInputMap().put(KeyStroke.getKeyStroke(KeyEvent.VK_S, InputEvent.CTRL_DOWN_MASK), "saveSql");
    area.getActionMap().put("saveSql", new AbstractAction() { public void actionPerformed(ActionEvent e) { saveSqlFile(false); } });
    area.getInputMap().put(KeyStroke.getKeyStroke(KeyEvent.VK_O, InputEvent.CTRL_DOWN_MASK), "openSql");
    area.getActionMap().put("openSql", new AbstractAction() { public void actionPerformed(ActionEvent e) { openSqlFile(); } });
    area.getActionMap().put("runSql", new AbstractAction() { public void actionPerformed(ActionEvent e) { runQuery(); } });
    String title = "SQL " + (tabs.getTabCount() + 1);
    RTextScrollPane scroll = new RTextScrollPane(area);
    scroll.setLineNumbersEnabled(true);
    scroll.setFoldIndicatorEnabled(true);
    tabs.addTab(title, scroll);
    tabs.setSelectedIndex(tabs.getTabCount() - 1);
  }

  private static void openSqlFile() {
    JFileChooser chooser = new JFileChooser();
    chooser.setFileFilter(new javax.swing.filechooser.FileNameExtensionFilter("Scripts SQL (*.sql)", "sql"));
    if (chooser.showOpenDialog(frame) != JFileChooser.APPROVE_OPTION) return;
    Path file = chooser.getSelectedFile().toPath();
    try {
      addSqlTab();
      activeEditor().setText(Files.readString(file, StandardCharsets.UTF_8));
      OPEN_FILES.put(tabs.getSelectedComponent(), file);
      tabs.setTitleAt(tabs.getSelectedIndex(), file.getFileName().toString());
      status.setText(" Script aberto: " + file);
    } catch (IOException ex) { showError(ex); }
  }

  private static void saveSqlFile(boolean saveAs) {
    Component tab = tabs.getSelectedComponent();
    if (tab == null) return;
    Path file = saveAs ? null : OPEN_FILES.get(tab);
    if (file == null) {
      JFileChooser chooser = new JFileChooser();
      chooser.setSelectedFile(new File("consulta.sql"));
      chooser.setFileFilter(new javax.swing.filechooser.FileNameExtensionFilter("Scripts SQL (*.sql)", "sql"));
      if (chooser.showSaveDialog(frame) != JFileChooser.APPROVE_OPTION) return;
      file = chooser.getSelectedFile().toPath();
      if (!file.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".sql"))
        file = file.resolveSibling(file.getFileName() + ".sql");
      if (Files.exists(file) && JOptionPane.showConfirmDialog(frame, "Sobrescrever " + file.getFileName() + "?", "Confirmar", JOptionPane.YES_NO_OPTION) != JOptionPane.YES_OPTION) return;
    }
    try {
      Files.writeString(file, activeEditor().getText(), StandardCharsets.UTF_8);
      OPEN_FILES.put(tab, file);
      tabs.setTitleAt(tabs.getSelectedIndex(), file.getFileName().toString());
      status.setText(" Script salvo: " + file);
    } catch (IOException ex) { showError(ex); }
  }

  private static void findTable() {
    String term = tableSearch.getText().trim().toLowerCase(Locale.ROOT);
    if (term.isEmpty()) return;
    List<TreePath> matches = new ArrayList<>();
    DefaultMutableTreeNode root = (DefaultMutableTreeNode) explorer.getModel().getRoot();
    Enumeration<?> nodes = root.depthFirstEnumeration();
    while (nodes.hasMoreElements()) {
      DefaultMutableTreeNode node = (DefaultMutableTreeNode) nodes.nextElement();
      if (node.getUserObject() instanceof TableNode table && table.table.toLowerCase(Locale.ROOT).contains(term))
        matches.add(new TreePath(node.getPath()));
    }
    if (matches.isEmpty()) {
      status.setText(" Nenhuma tabela encontrada entre os servidores expandidos. Expanda um servidor para carregar suas tabelas.");
      return;
    }
    TreePath selected = explorer.getSelectionPath();
    int next = 0;
    if (selected != null) {
      int pos = matches.indexOf(selected);
      if (pos >= 0) next = (pos + 1) % matches.size();
    }
    explorer.setSelectionPath(matches.get(next));
    explorer.scrollPathToVisible(matches.get(next));
    status.setText(" Busca: " + matches.size() + " tabela(s) encontrada(s). Pressione Enter para a próxima.");
  }

  // Pesquisa sob demanda no catálogo do servidor, sem carregar todas as colunas na memória.
  private static void searchColumnsAcrossTables() {
    if (SOURCES.isEmpty()) {
      JOptionPane.showMessageDialog(frame, "Carregue as conexões primeiro.");
      return;
    }
    JComboBox<String> serverBox = new JComboBox<>(SOURCES.keySet().toArray(new String[0]));
    JTextField columnName = new JTextField(22);
    JPanel panel = new JPanel(new GridLayout(2, 2, 8, 8));
    panel.add(new JLabel("Servidor:")); panel.add(serverBox);
    panel.add(new JLabel("Nome da coluna contém:")); panel.add(columnName);
    if (JOptionPane.showConfirmDialog(frame, panel, "Pesquisar coluna", JOptionPane.OK_CANCEL_OPTION,
        JOptionPane.PLAIN_MESSAGE) != JOptionPane.OK_OPTION) return;
    String term = columnName.getText().trim();
    if (term.isEmpty()) return;
    String server = (String) serverBox.getSelectedItem();
    BasicDataSource ds = SOURCES.get(server);
    if (ds == null) return;
    status.setText(" Pesquisando coluna '" + term + "' em " + server + "...");
    new SwingWorker<List<String>, Void>() {
      protected List<String> doInBackground() throws Exception {
        List<String> found = new ArrayList<>();
        // A consulta usa os metadados do MySQL e limita o retorno a 300 colunas.
        String sql = "SELECT TABLE_NAME, COLUMN_NAME, COLUMN_TYPE FROM information_schema.COLUMNS "
            + "WHERE TABLE_SCHEMA = DATABASE() AND LOWER(COLUMN_NAME) LIKE ? ESCAPE '!' "
            + "ORDER BY TABLE_NAME, ORDINAL_POSITION LIMIT 300";
        try (Connection c = ds.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
          ps.setString(1, "%" + term.toLowerCase(Locale.ROOT).replace("!", "!!")
              .replace("%", "!%").replace("_", "!_") + "%");
          try (ResultSet rs = ps.executeQuery()) {
            while (rs.next()) found.add(rs.getString(1) + "." + rs.getString(2) + "  :  " + rs.getString(3));
          }
        }
        return found;
      }
      protected void done() {
        try {
          List<String> found = get();
          status.setText(" Pesquisa de colunas: " + found.size() + " resultado(s) em " + server + ".");
          JTextArea output = new JTextArea(found.isEmpty() ? "Nenhuma coluna encontrada." : String.join("\n", found));
          output.setEditable(false);
          output.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
          JScrollPane pane = new JScrollPane(output);
          pane.setPreferredSize(new Dimension(680, 430));
          JOptionPane.showMessageDialog(frame, pane, "Colunas encontradas — " + server, JOptionPane.INFORMATION_MESSAGE);
        } catch (Exception ex) { showError(ex); }
      }
    }.execute();
  }

  private record AliasTable(String source, String table) {}

  // Analisa FROM/JOIN no comando SQL inteiro, inclusive depois da posicao do cursor.
  // Aceita nomes entre aspas duplas/backticks e aliases com ou sem AS.
  private static final java.util.regex.Pattern TABLE_REFERENCE = java.util.regex.Pattern.compile(
      "(?i)\\b(?:FROM|JOIN)\\s+[`\"]?([a-z][a-z0-9_]*)[`\"]?\\s*\\.\\s*[`\"]?([a-z0-9_]+)[`\"]?" +
      "(?:\\s+(?:AS\\s+)?([a-z_][a-z0-9_]*))?");

  private static boolean isSqlKeyword(String word) {
    if (word == null) return false;
    return java.util.Arrays.asList("WHERE", "JOIN", "INNER", "LEFT", "RIGHT", "FULL", "CROSS", "ON",
        "GROUP", "ORDER", "HAVING", "LIMIT", "FETCH", "UNION", "EXCEPT", "INTERSECT", "OFFSET",
        "WINDOW", "QUALIFY").contains(word.toUpperCase(Locale.ROOT));
  }

  private static AliasTable resolveAlias(String sql, String alias) {
    java.util.regex.Matcher m = TABLE_REFERENCE.matcher(sql);
    AliasTable found = null;
    while (m.find()) {
      String source = resolveSource(m.group(1));
      if (source == null) continue;
      String table = m.group(2);
      String declaredAlias = m.group(3);
      if (alias.equalsIgnoreCase(table) || (declaredAlias != null &&
          !isSqlKeyword(declaredAlias) && alias.equalsIgnoreCase(declaredAlias))) {
        found = new AliasTable(source, table);
      }
    }
    return found;
  }

  // Evita capturar aliases de outro comando SQL na mesma aba.
  private static String completionStatement(String sql, int caret) {
    int start = 0, end = sql.length();
    // Os comandos sao separados por ';'. O editor ja usa esse criterio para executar SQL.
    // Aqui o objetivo e manter o contexto da consulta em que o cursor esta.
    for (int i = 0; i < sql.length(); i++) {
      if (sql.charAt(i) == ';') {
        if (i < caret) start = i + 1;
        else { end = i; break; }
      }
    }
    return sql.substring(start, Math.max(start, end));
  }

  private static String resolveSource(String name) {
    for (String source : SOURCES.keySet()) if (source.equalsIgnoreCase(name)) return source;
    return null;
  }

  private static void fetchTables(String source, Runnable onLoaded) {
    if (source == null || TABLE_CACHE.containsKey(source) || !TABLE_LOADING.add(source)) return;
    BasicDataSource ds = SOURCES.get(source);
    if (ds == null) { TABLE_LOADING.remove(source); return; }
    new SwingWorker<List<String>, Void>() {
      protected List<String> doInBackground() throws Exception {
        List<String> names = new ArrayList<>();
        try (Connection c = ds.getConnection();
             ResultSet rs = c.getMetaData().getTables(c.getCatalog(), null, "%", new String[]{"TABLE", "VIEW"})) {
          while (rs.next()) names.add(rs.getString("TABLE_NAME"));
        }
        names.sort(String.CASE_INSENSITIVE_ORDER);
        return names;
      }
      protected void done() {
        TABLE_LOADING.remove(source);
        try {
          if (SOURCES.get(source) != ds) return; // Conexao substituida durante o carregamento.
          TABLE_CACHE.put(source, get());
          if (onLoaded != null) onLoaded.run();
        } catch (Exception ex) { status.setText(" Não foi possível carregar tabelas de " + source + ": " + ex.getMessage()); }
      }
    }.execute();
  }

  private static void fetchColumns(String source, String table, Runnable onLoaded) {
    String key = source + "." + table;
    if (COLUMN_CACHE.containsKey(key) || !COLUMN_LOADING.add(key)) return;
    BasicDataSource ds = SOURCES.get(source);
    if (ds == null) { COLUMN_LOADING.remove(key); return; }
    new SwingWorker<List<String>, Void>() {
      protected List<String> doInBackground() throws Exception {
        List<String> names = new ArrayList<>();
        try (Connection c = ds.getConnection();
             ResultSet rs = c.getMetaData().getColumns(c.getCatalog(), null, table, "%")) {
          while (rs.next()) names.add(rs.getString("COLUMN_NAME"));
        }
        return names;
      }
      protected void done() {
        COLUMN_LOADING.remove(key);
        try {
          if (SOURCES.get(source) != ds) return;
          COLUMN_CACHE.put(key, get());
          if (onLoaded != null) onLoaded.run();
        } catch (Exception ex) { status.setText(" Não foi possível carregar colunas de " + key + ": " + ex.getMessage()); }
      }
    }.execute();
  }

  private static void showCompletions(RSyntaxTextArea editor) {
    showCompletions(editor, true);
  }

  private static void showCompletions(RSyntaxTextArea editor, boolean allowLoad) {
    int caret = editor.getCaretPosition();
    String before = editor.getText().substring(0, caret);
    int start = caret;
    while (start > 0 && (Character.isLetterOrDigit(before.charAt(start - 1)) || before.charAt(start - 1) == '_')) start--;
    String prefix = before.substring(start).toLowerCase(Locale.ROOT);
    String left = before.substring(0, start);
    java.util.regex.Matcher qualifierMatcher = java.util.regex.Pattern.compile(
        "(?i)([a-z_][a-z0-9_]*)\\.\\s*$").matcher(left);
    String qualifier = qualifierMatcher.find() ? qualifierMatcher.group(1) : null;
    String source = qualifier == null ? null : resolveSource(qualifier);
    boolean afterDot = source != null;
    String contextSql = completionStatement(editor.getText(), caret);
    AliasTable aliasTable = !afterDot && qualifier != null ? resolveAlias(contextSql, qualifier) : null;
    if (qualifier != null && !afterDot && aliasTable == null) {
      status.setText(" Alias '" + qualifier + "' nao encontrado em FROM/JOIN desta consulta.");
      return;
    }
    Set<String> candidates = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);

    if (aliasTable != null) {
      String key = aliasTable.source() + "." + aliasTable.table();
      List<String> columns = COLUMN_CACHE.get(key);
      if (columns == null) {
        if (allowLoad) {
          status.setText(" Carregando colunas de " + key + "...");
          int originalCaret = caret;
          fetchColumns(aliasTable.source(), aliasTable.table(), null);
          javax.swing.Timer retry = new javax.swing.Timer(250, null);
          final int[] attempts = {0};
          retry.addActionListener(event -> {
            if (!editor.isShowing() || editor.getCaretPosition() != originalCaret || ++attempts[0] > 24) retry.stop();
            else if (COLUMN_CACHE.containsKey(key)) { retry.stop(); showCompletions(editor, false); }
            else if (!COLUMN_LOADING.contains(key)) retry.stop();
          });
          retry.start();
        }
        return;
      }
      candidates.addAll(columns);
    } else if (afterDot) {
      List<String> tables = TABLE_CACHE.get(source);
      if (tables == null) {
        if (allowLoad) {
          status.setText(" Carregando tabelas de " + source + " para autocompletar...");
          int originalCaret = caret;
          fetchTables(source, null);
          javax.swing.Timer retry = new javax.swing.Timer(250, null);
          final int[] attempts = {0};
          retry.addActionListener(event -> {
            if (!editor.isShowing() || editor.getCaretPosition() != originalCaret || ++attempts[0] > 24) {
              retry.stop();
            } else if (TABLE_CACHE.containsKey(source)) {
              retry.stop();
              showCompletions(editor, false);
            } else if (!TABLE_LOADING.contains(source)) {
              retry.stop();
              status.setText(" Não foi possível obter tabelas de " + source + ".");
            }
          });
          retry.start();
        }
        return;
      }
      candidates.addAll(tables);
    } else if (qualifier != null) {
      // Expressao qualificada: nunca misturar keywords e tabelas quando o alias nao foi localizado.
      status.setText(" Alias '" + qualifier + "' nao encontrado. Confira o FROM/JOIN e a conexao.");
      return;
    } else {
      Collections.addAll(candidates, SQL_WORDS);
      candidates.addAll(SOURCES.keySet());
      for (List<String> tables : TABLE_CACHE.values()) candidates.addAll(tables);
      if (allowLoad) for (String server : SOURCES.keySet()) fetchTables(server, null);

      // Sugestao de colunas a partir de tabelas explicitamente citadas na consulta.
      java.util.regex.Matcher tablesInSql = java.util.regex.Pattern.compile(
          "(?i)\\b(?:FROM|JOIN)\\s+([a-z][a-z0-9_]*)\\.\\\"?([a-z0-9_]+)\\\"?").matcher(contextSql);
      while (tablesInSql.find()) {
        String server = resolveSource(tablesInSql.group(1));
        if (server == null) continue;
        String table = tablesInSql.group(2);
        String key = server + "." + table;
        List<String> columns = COLUMN_CACHE.get(key);
        if (columns != null) candidates.addAll(columns);
        else if (allowLoad) fetchColumns(server, table, null);
      }
    }

    java.util.List<String> matches = new ArrayList<>();
    for (String candidate : candidates) {
      if (candidate.toLowerCase(Locale.ROOT).startsWith(prefix)) matches.add(candidate);
      if (matches.size() >= 100) break;
    }
    if (matches.isEmpty()) {
      status.setText(" Nenhuma sugestão encontrada para '" + prefix + "'.");
      return;
    }
    final int replaceStart = start;
    JList<String> suggestions = new JList<>(matches.toArray(new String[0]));
    suggestions.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
    suggestions.setSelectedIndex(0);
    suggestions.setVisibleRowCount(Math.min(9, matches.size()));
    suggestions.setFixedCellHeight(25);
    JScrollPane scroll = new JScrollPane(suggestions);
    scroll.setBorder(BorderFactory.createEmptyBorder());
    scroll.setPreferredSize(new Dimension(280, Math.min(9, matches.size()) * 25 + 4));
    JPopupMenu menu = new JPopupMenu();
    menu.setLayout(new BorderLayout());
    menu.add(scroll, BorderLayout.CENTER);
    Runnable accept = () -> {
      String candidate = suggestions.getSelectedValue();
      if (candidate == null) return;
      try {
        editor.getDocument().remove(replaceStart, caret - replaceStart);
        editor.getDocument().insertString(replaceStart, candidate, null);
        editor.setCaretPosition(replaceStart + candidate.length());
        menu.setVisible(false);
        SwingUtilities.invokeLater(editor::requestFocusInWindow);
      } catch (javax.swing.text.BadLocationException ex) { showError(ex); }
    };
    suggestions.addMouseListener(new MouseAdapter() {
      public void mouseClicked(MouseEvent e) { if (e.getClickCount() == 2) accept.run(); }
    });
    suggestions.getInputMap(JComponent.WHEN_FOCUSED).put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), "acceptCompletion");
    suggestions.getActionMap().put("acceptCompletion", new AbstractAction() {
      public void actionPerformed(ActionEvent e) { accept.run(); }
    });
    suggestions.getInputMap(JComponent.WHEN_FOCUSED).put(KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), "closeCompletion");
    suggestions.getActionMap().put("closeCompletion", new AbstractAction() {
      public void actionPerformed(ActionEvent e) {
        menu.setVisible(false);
        SwingUtilities.invokeLater(editor::requestFocusInWindow);
      }
    });
    try {
      Rectangle rect = editor.modelToView2D(caret).getBounds();
      menu.show(editor, rect.x, rect.y + rect.height);
      SwingUtilities.invokeLater(suggestions::requestFocusInWindow);
    } catch (javax.swing.text.BadLocationException ex) { showError(ex); }
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
      for (String server : SOURCES.keySet()) fetchTables(server, null);
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
          List<String> loadedTables = get();
          TABLE_CACHE.put(name, loadedTables);
          for (String table : loadedTables) {
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
          COLUMN_CACHE.put(table.source + "." + table.table, columns.stream().map(c -> c.split("  :  ")[0]).toList());
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
    JTextArea queryEditor = activeEditor();
    if (federation == null) { status.setText(" Carregue as conexões primeiro."); queryEditor.requestFocusInWindow(); return; }
    if (currentQuery != null && !currentQuery.isDone()) { queryEditor.requestFocusInWindow(); return; }
    String sql = queryEditor.getSelectedText();
    if (sql == null || sql.isBlank()) sql = statementAtCaret(queryEditor);
    sql = sql.trim();
    while (sql.endsWith(";")) sql = sql.substring(0, sql.length() - 1).trim();
    if (sql.isBlank()) { status.setText(" Posicione o cursor em uma consulta ou selecione o SQL."); queryEditor.requestFocusInWindow(); return; }
    final String query = sql;
    execute.setEnabled(false);
    queryStarted = System.nanoTime();
    status.setText(" Executando consulta...");
    resultSummary.setText("  RESULTADOS  ·  Executando...");
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
          double elapsed = (System.nanoTime() - queryStarted) / 1_000_000_000.0;
          resultSummary.setText(String.format(Locale.ROOT, "  RESULTADOS  ·  %,d linhas  ·  %.2f s", model.getRowCount(), elapsed));
          status.setText(" Consulta concluída: " + model.getRowCount() + " linhas (limite: " + ROW_LIMIT + ").");
        } catch (Exception ex) { resultSummary.setText("  RESULTADOS  ·  Erro na consulta"); status.setText(" Falha na consulta."); showError(ex); }
      }
    };
    currentQuery.execute();
    // Ctrl+Enter deve manter o teclado no editor, sem alterar cursor ou seleção.
    SwingUtilities.invokeLater(queryEditor::requestFocusInWindow);
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
      TABLE_CACHE.clear(); COLUMN_CACHE.clear();
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
