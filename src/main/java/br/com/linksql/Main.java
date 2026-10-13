package br.com.linksql;

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
import org.fife.ui.rsyntaxtextarea.Token;
import org.fife.ui.rsyntaxtextarea.SyntaxScheme;
import org.fife.ui.rtextarea.RTextScrollPane;

import javax.swing.*;
import javax.swing.event.TreeSelectionEvent;
import javax.swing.event.DocumentListener;
import javax.swing.event.DocumentEvent;
import javax.swing.table.DefaultTableModel;
import javax.swing.table.TableColumn;
import javax.swing.table.TableRowSorter;
import javax.swing.RowFilter;
import javax.swing.tree.*;
import java.awt.*;
import java.awt.event.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.sql.*;
import java.util.*;
import java.util.List;
import static br.com.linksql.SqlQueryPlanner.*;
import br.com.linksql.SqlQueryPlanner.DirectSql;
import br.com.linksql.SqlQueryPlanner.SqlWindow;

public class Main {
  private static final ObjectMapper JSON = new ObjectMapper();
  private static final SqlConnectionRegistry CONNECTIONS = new SqlConnectionRegistry(Main::promptConnectionPassword);
  private static final Map<String, BasicDataSource> SOURCES = CONNECTIONS.sources();
  private static Connection federation;
  private static Path configPath = defaultConfigPath();
  /** Perfis compatíveis com o driver JDBC instalado no mesmo usuario Windows. */
  private static Path defaultConfigPath() {
    return LinkSqlPaths.prepareConnectionProfilesFile();
  }

  private static List<String> connectionNames() {
    List<String> names = new ArrayList<>();
    for (JdbcConnectionProfile profile : CONNECTIONS.allProfiles()) names.add(profile.name());
    return names;
  }

  private static File defaultUserFile(String filename) {
    return LinkSqlPaths.defaultUserFile(filename);
  }

  /** Pergunta somente ao acessar o banco e oferece armazenamento seguro permanente. */
  private static String promptConnectionPassword(JdbcConnectionProfile profile) {
    if (GraphicsEnvironment.isHeadless()) return null;
    final String[] answer = new String[1];
    final boolean[] persist = new boolean[1];
    Runnable dialog = () -> {
      JPasswordField field = new JPasswordField(24);
      JCheckBox remember = new JCheckBox("Lembrar senha protegida no Windows", true);
      JPanel panel = new JPanel(new BorderLayout(0, 9));
      panel.add(new JLabel("Senha de " + profile.name() + " (" + profile.type() + "):"), BorderLayout.NORTH);
      panel.add(field, BorderLayout.CENTER);
      panel.add(remember, BorderLayout.SOUTH);
      int choice = JOptionPane.showConfirmDialog(frame, panel, "Credenciais da conexão",
          JOptionPane.OK_CANCEL_OPTION, JOptionPane.QUESTION_MESSAGE);
      if (choice != JOptionPane.OK_OPTION) return;
      char[] characters = field.getPassword();
      try {
        if (characters.length == 0) return;
        // Somente captura a senha no EDT; DPAPI é executada na thread de trabalho.
        persist[0] = remember.isSelected();
        answer[0] = new String(characters);
      } finally { Arrays.fill(characters, '\0'); field.setText(""); }
    };
    try {
      if (SwingUtilities.isEventDispatchThread()) dialog.run();
      else SwingUtilities.invokeAndWait(dialog);
    } catch (Exception ex) { return null; }
    if (persist[0] && answer[0] != null) {
      final Runnable encryption = () -> {
        char[] secret = answer[0].toCharArray();
        try { ConnectionVault.save(profile.name(), secret, ConnectionVault.Duration.PERMANENT); }
        catch (IOException ex) {
          SwingUtilities.invokeLater(() -> JOptionPane.showMessageDialog(frame,
              "Não foi possível salvar a senha protegida: " + ex.getMessage(),
              "LinkSQL", JOptionPane.WARNING_MESSAGE));
        } finally { Arrays.fill(secret, '\0'); }
      };
      if (SwingUtilities.isEventDispatchThread()) new Thread(encryption, "linksql-salvar-credencial").start();
      else encryption.run();
    }
    return answer[0];
  }

  private static final int DEFAULT_PAGE_SIZE = LinkSqlSettings.DEFAULT_PAGE_SIZE;
  private static final int MAX_PAGE_SIZE = LinkSqlSettings.MAX_PAGE_SIZE;
  private static final Locale DISPLAY_LOCALE = LinkSqlSettings.DISPLAY_LOCALE;
  private static int preferredPageSize = LinkSqlSettings.pageSize();
  private static boolean darkMode = LinkSqlSettings.darkMode();
  private static boolean automaticCompletion = LinkSqlSettings.automaticCompletion();
  private static int completionDelayMs = LinkSqlSettings.completionDelayMs();
  private static int sqlFontSize = LinkSqlSettings.sqlFontSize();
  private static int resultFilterDelayMs = LinkSqlSettings.resultFilterDelayMs();
  private static JFrame frame;
  private static JButton themeButton;
  private static JPanel toolbar;
  private static JLabel resultSummary;
  private static JLabel status;
  private static JTable results;
  private static JTabbedPane resultTabs;
  private static int nextResultNumber = 1;
  private static JTabbedPane tabs;
  private static JTree explorer;
  private static DefaultMutableTreeNode rootNode;
  private static JButton execute;
  private static JButton cancelQueryButton;
  private static JTextField resultFilter;
  private static JComboBox<String> resultFilterColumn;
  private static JButton previousPageButton;
  private static JButton nextPageButton;
  private static JLabel pageLabel;
  private static JComboBox<String> pageSizeBox;
  private static javax.swing.Timer resultFilterDebounce;
  private static boolean updatingPageSizeBox;
  private static final int PAGE_CACHE_LIMIT = 8;
  private static final int PAGE_CACHE_MAX_ROWS = 16000;
  private static final java.util.concurrent.ExecutorService COUNT_POOL =
      java.util.concurrent.Executors.newFixedThreadPool(2, r -> {
        Thread t = new Thread(r, "linksql-count"); t.setDaemon(true); return t;
      });
  private static final java.util.concurrent.ScheduledExecutorService COUNT_WATCHDOG =
      java.util.concurrent.Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "linksql-count-timeout"); t.setDaemon(true); return t;
      });
  private static final int FEDERATED_COUNT_TIMEOUT = 20;
  private static int resultPageSize(JScrollPane scroll) {
    Object size = scroll.getClientProperty("pageSize");
    return size instanceof Integer n ? n : preferredPageSize;
  }
  private static String formatPageSize(int size) { return LinkSqlSettings.formatPageSize(size); }
  private static String firstPageSql(String sql, int size, String tabAlias) {
    // JDBC portavel: o limite/paginacao das conexoes nao-MySQL e aplicado
    // sobre o ResultSet. Nunca injetar LIMIT de MySQL no SQL de outro banco.
    NativeSqlRouter.Route nativeRoute = nativeRoute(sql, tabAlias);
    if (nativeRoute != null) return nativeRoute.sql();
    DirectSql direct = directSql(sql);
    if (direct == null) return sql;
    return directPageSql(direct, sqlWindow(sql), 0, size);
  }
  private static NativeSqlRouter.Route nativeRoute(String sql, String tabAlias) {
    return SqlTabRouting.route(sql, tabAlias, CONNECTIONS.allProfiles());
  }
  private static String executionLabel(String sql, String tabAlias) {
    NativeSqlRouter.Route route = nativeRoute(sql, tabAlias);
    if (route != null) return route.type() + " nativo (" + route.source() + ")";
    return directSql(sql) == null
        ? FederatedQuerySupport.executionLabel(sql, CONNECTIONS.allProfiles()) : "MySQL direto";
  }
  private static DirectSql directSql(String sql) {
    return SqlQueryPlanner.directSql(sql, CONNECTIONS.mysqlSourceNames());
  }
  private static String executableSql(String sql) {
    return SqlQueryPlanner.executableSql(sql, CONNECTIONS.mysqlSourceNames());
  }
  // MySQL Connector/J: modo streaming apenas em leituras diretas ilimitadas.
  // Reduz a duplicação do resultado na memória do driver antes de montar a grade.
  private static void configureFetch(Statement stmt, String sql, int size) throws SQLException {
    if (size == 0 && directSql(sql) != null && isPageableSql(sql)) {
      stmt.setFetchSize(Integer.MIN_VALUE);
    }
  }
  private static Connection queryConnectionFor(String sql, String tabAlias) throws SQLException {
    NativeSqlRouter.Route nativeRoute = nativeRoute(sql, tabAlias);
    if (nativeRoute != null) return CONNECTIONS.source(nativeRoute.source()).getConnection();
    DirectSql direct = directSql(sql);
    return direct == null ? openQueryConnection(sql) : CONNECTIONS.source(direct.source()).getConnection();
  }
  private static java.util.Map<Integer, DefaultTableModel> pageCache(JScrollPane scroll) {
    @SuppressWarnings("unchecked")
    java.util.Map<Integer, DefaultTableModel> cache =
        (java.util.Map<Integer, DefaultTableModel>) scroll.getClientProperty("pageCache");
    if (cache == null) {
      cache = new LinkedHashMap<Integer, DefaultTableModel>(12, .75f, true) {
        protected boolean removeEldestEntry(Map.Entry<Integer, DefaultTableModel> eldest) {
          int byMemory = Math.max(1, PAGE_CACHE_MAX_ROWS / Math.max(1, resultPageSize(scroll)));
          return size() > Math.min(PAGE_CACHE_LIMIT, byMemory);
        }
      };
      scroll.putClientProperty("pageCache", cache);
    }
    return cache;
  }
  private static void requestCount(JScrollPane scroll, String sql) {
    String tabAlias = (String) scroll.getClientProperty("sqlDefaultSource");
    // Cada resultado usa um ticket: uma resposta antiga nao modifica uma nova consulta.
    final Object ticket = new Object();
    scroll.putClientProperty("countTicket", ticket);
    int size = resultPageSize(scroll);
    if (size == 0) {
      scroll.putClientProperty("countPending", false);
      updatePageControls();
      return;
    }
    DirectSql direct = directSql(sql);
    NativeSqlRouter.Route nativeCount = direct == null ? nativeRoute(sql, tabAlias) : null;
    boolean federated = direct == null && nativeCount == null;
    // Contagem federada sempre ativa, sem depender de preferencia antiga do usuario.
    boolean allowed = isPageableSql(sql);
    scroll.putClientProperty("countUnavailable", false);
    scroll.putClientProperty("countPending", false);
    scroll.putClientProperty("totalRows", null);

    // Se a primeira pagina estiver incompleta, ja conhecemos o total real.
    if (scroll.getViewport().getView() instanceof JTable table) {
      Long exact = SqlPageTotals.whenExhausted(0, size, table.getModel().getRowCount());
      if (exact != null && isPageableSql(sql)) {
        scroll.putClientProperty("totalRows", exact);
        updatePageControls();
        return;
      }
    }
    if (!allowed) { updatePageControls(); return; }
    scroll.putClientProperty("countPending", true);
    updatePageControls();

    COUNT_POOL.submit(() -> {
      java.util.concurrent.ScheduledFuture<?> watchdog = null;
      final java.util.concurrent.atomic.AtomicReference<Connection> countConnection =
          new java.util.concurrent.atomic.AtomicReference<>();
      final java.util.concurrent.atomic.AtomicReference<Statement> countStatement =
          new java.util.concurrent.atomic.AtomicReference<>();
      try {
        String source = direct != null ? direct.source() : nativeCount != null ? nativeCount.source() : null;
        String baseSql = direct != null ? direct.sql() : nativeCount != null ? nativeCount.sql() : null;
        String countSql;
        if (direct != null) {
          countSql = "SELECT COUNT(*) FROM (" + baseSql + ") AS LINKSQL_TOTAL";
          java.util.regex.Matcher basic = java.util.regex.Pattern.compile(
              "(?is)^SELECT\\s+\\*\\s+FROM\\s+([`\"A-Za-z_][`\"A-Za-z0-9_]*)"
                  + "(?:\\s+ORDER\\s+BY\\s+[`\"A-Za-z0-9_.,\\s]+)?\\s*$")
              .matcher(baseSql);
          if (basic.matches()) countSql = "SELECT COUNT(*) FROM " + basic.group(1);
        } else if (nativeCount != null) {
          countSql = NativeQueryCount.sql(baseSql);
        } else {
          countSql = FederatedCountSupport.countSql(sql);
        }
        if (countSql == null) throw new SQLException("Nao foi possivel gerar COUNT seguro.");

        if (federated) {
          // Evita bloquear indefinidamente em driver remoto que ignore setQueryTimeout.
          watchdog = COUNT_WATCHDOG.schedule(() -> {
            Statement st = countStatement.get();
            if (st != null) try { st.cancel(); } catch (SQLException ignored) {}
            Connection conn = countConnection.get();
            if (conn != null) try { conn.close(); } catch (SQLException ignored) {}
          }, FEDERATED_COUNT_TIMEOUT, java.util.concurrent.TimeUnit.SECONDS);
        }
        try (Connection conn = federated ? openQueryConnection(sql) : CONNECTIONS.source(source).getConnection()) {
          countConnection.set(conn);
          try (Statement st = conn.createStatement()) {
            countStatement.set(st);
            st.setQueryTimeout(federated ? FEDERATED_COUNT_TIMEOUT : 45);
            try (ResultSet rs = st.executeQuery(countSql)) {
              if (!rs.next()) throw new SQLException("Contagem nao retornou registros.");
              long total = FederatedCountSupport.effectiveTotal(rs.getLong(1), sql);
              SwingUtilities.invokeLater(() -> {
                if (resultTabs.indexOfComponent(scroll) >= 0
                    && ticket == scroll.getClientProperty("countTicket")
                    && sql.equals(scroll.getClientProperty("sqlQueryKey"))) {
                  scroll.putClientProperty("totalRows", total);
                  scroll.putClientProperty("countPending", false);
                  updatePageControls();
                }
              });
            }
          } finally { countStatement.set(null); }
        } finally { countConnection.set(null); }
      } catch (Exception ex) {
        LinkSqlLog.error("Falha na contagem auxiliar " + (federated ? "federada" : "nativa"), ex);
        SwingUtilities.invokeLater(() -> {
          if (resultTabs.indexOfComponent(scroll) >= 0
              && ticket == scroll.getClientProperty("countTicket")
              && sql.equals(scroll.getClientProperty("sqlQueryKey"))) {
            scroll.putClientProperty("countPending", false);
            scroll.putClientProperty("countUnavailable", true);
            updatePageControls();
          }
        });
      } finally {
        if (watchdog != null) watchdog.cancel(false);
      }
    });
  }

  private static boolean changingResultTab = false;
  private static final java.util.concurrent.atomic.AtomicReference<Statement> RUNNING_STATEMENT = new java.util.concurrent.atomic.AtomicReference<>();
  private static final java.util.concurrent.atomic.AtomicReference<Connection> RUNNING_CONNECTION = new java.util.concurrent.atomic.AtomicReference<>();
  private static final java.util.concurrent.atomic.AtomicBoolean CANCEL_REQUESTED = new java.util.concurrent.atomic.AtomicBoolean(false);
  private static SwingWorker<?, ?> currentQuery;
  private static JButton executeScriptButton;
  private static final Map<Component, Path> OPEN_FILES = new IdentityHashMap<>();
  private static JTextField tableSearch;
  private static final Map<Component, String> SAVED_TEXT = new IdentityHashMap<>();
  private static final Map<Component, String> TAB_SOURCES = new IdentityHashMap<>();
  private static JComboBox<TabSourceChoice> tabSourceBox;
  private static boolean updatingTabSourceBox;
  private record TabSourceChoice(String alias, String label) {
    @Override public String toString() { return label; }
  }
  private static String tabSource(Component tab) {
    return tab == null ? "" : TAB_SOURCES.getOrDefault(tab, "");
  }
  private static void updateTabSourceChoice() {
    if (tabSourceBox == null || tabs == null) return;
    String current = tabSource(tabs.getSelectedComponent());
    updatingTabSourceBox = true;
    try {
      tabSourceBox.removeAllItems();
      tabSourceBox.addItem(new TabSourceChoice("", "Federado"));
      for (JdbcConnectionProfile p : CONNECTIONS.allProfiles())
        tabSourceBox.addItem(new TabSourceChoice(p.name(),
            p.name() + " [" + p.type() + "]"));
      boolean found = current.isBlank();
      for (int i = 0; i < tabSourceBox.getItemCount(); i++) {
        TabSourceChoice choice = tabSourceBox.getItemAt(i);
        if (choice.alias().equalsIgnoreCase(current)) {
          tabSourceBox.setSelectedIndex(i);
          found = true;
          break;
        }
      }
      if (!found) {
        tabSourceBox.addItem(new TabSourceChoice(current,
            current + " (conexão não cadastrada)"));
        tabSourceBox.setSelectedIndex(tabSourceBox.getItemCount() - 1);
      }
    } finally {
      updatingTabSourceBox = false;
    }
  }
  private static final Path SESSION_FILE = LinkSqlPaths.sqlSessionFile();
  private static boolean restoringSession = false;
  private static boolean shuttingDown = false;
  private static javax.swing.Timer sessionTimer;
  private static int nextTabNumber = 1;
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
    LinkSqlVisualStyle.install(darkMode);
  }

  private static void toggleTheme() {
    darkMode = !darkMode;
    LinkSqlSettings.setDarkMode(darkMode);
    refreshTheme();
  }

  private static void refreshTheme() {
    try {
      applyLookAndFeel();
      SwingUtilities.updateComponentTreeUI(frame);
      LinkSqlVisualStyle.refresh(frame, darkMode);
      for (int i = 0; tabs != null && i < tabs.getTabCount(); i++) {
        RSyntaxTextArea editor = editorOf(tabs.getComponentAt(i));
        styleEditor(editor);
        editor.setFont(new Font(Font.MONOSPACED, Font.PLAIN, sqlFontSize));
      }
      LinkSqlVisualStyle.button(themeButton, darkMode ? "Tema claro" : "Tema escuro",
          darkMode ? "sun" : "moon", "quiet");
      frame.repaint();
    } catch (Exception ex) { showError(ex); }
  }

  private static void openSettings() {
    LinkSqlSettings.Snapshot options = LinkSqlSettingsDialog.show(frame, LinkSqlSettings.snapshot());
    if (options == null) return;
    if (currentQuery != null && !currentQuery.isDone() && options.pageSize() != preferredPageSize) {
      JOptionPane.showMessageDialog(frame,
          "Aguarde o fim da consulta antes de alterar a preferência de paginação.",
          "Consulta em andamento", JOptionPane.INFORMATION_MESSAGE);
      return;
    }
    if (options.pageSize() == 0 && preferredPageSize != 0) {
      int answer = JOptionPane.showConfirmDialog(frame,
          "A opção Todas (1 página) pode carregar muitos registros na memória.\n"
              + "Essa preferência será aplicada nas próximas execuções. Continuar?",
          "Aviso de memória", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
      if (answer != JOptionPane.YES_OPTION) return;
    }
    LinkSqlSettings.save(options);
    boolean changedTheme = darkMode != options.darkMode();
    darkMode = options.darkMode();
    preferredPageSize = options.pageSize();
    automaticCompletion = options.automaticCompletion();
    completionDelayMs = options.completionDelayMs();
    sqlFontSize = options.sqlFontSize();
    resultFilterDelayMs = options.resultFilterDelayMs();
    if (resultFilterDebounce != null) {
      resultFilterDebounce.setDelay(resultFilterDelayMs);
      resultFilterDebounce.setInitialDelay(resultFilterDelayMs);
    }
    for (int i = 0; tabs != null && i < tabs.getTabCount(); i++) {
      RSyntaxTextArea editor = editorOf(tabs.getComponentAt(i));
      editor.setFont(new Font(Font.MONOSPACED, Font.PLAIN, sqlFontSize));
      Object timer = editor.getClientProperty("automaticCompletionTimer");
      if (timer instanceof javax.swing.Timer t) {
        t.setDelay(completionDelayMs);
        t.setInitialDelay(completionDelayMs);
        if (!automaticCompletion) t.stop();
      }
      if (!automaticCompletion) closeCompletion(editor);
    }
    if (changedTheme) refreshTheme();
    updatePageControls();
    status.setText(" Preferências salvas. Tamanho da página aplicado às próximas consultas.");
  }

  private static void styleEditor(RSyntaxTextArea area) {
    String resource = darkMode ? "/org/fife/ui/rsyntaxtextarea/themes/dark.xml" : "/org/fife/ui/rsyntaxtextarea/themes/default.xml";
    try (InputStream in = Main.class.getResourceAsStream(resource)) {
      if (in != null) Theme.load(in).apply(area);
    } catch (IOException ex) { ex.printStackTrace(); }
    area.setBackground(LinkSqlVisualStyle.editorSurface());
    area.setForeground(LinkSqlVisualStyle.text());
    area.setCurrentLineHighlightColor(LinkSqlVisualStyle.currentLine());
    area.setCaretColor(LinkSqlVisualStyle.text());
    area.setSelectionColor(LinkSqlVisualStyle.rowSelection());
    area.setSelectedTextColor(LinkSqlVisualStyle.text());
    SyntaxScheme scheme = area.getSyntaxScheme();
    for (int token : new int[]{Token.RESERVED_WORD, Token.RESERVED_WORD_2, Token.DATA_TYPE})
      scheme.getStyle(token).foreground = LinkSqlVisualStyle.accent();
    for (int token : new int[]{Token.COMMENT_EOL, Token.COMMENT_MULTILINE, Token.COMMENT_DOCUMENTATION})
      scheme.getStyle(token).foreground = LinkSqlVisualStyle.subtleText();
    scheme.getStyle(Token.FUNCTION).foreground = Color.decode(darkMode ? "#B59CFF" : "#7046BD");
    scheme.getStyle(Token.LITERAL_STRING_DOUBLE_QUOTE).foreground = Color.decode(darkMode ? "#9FCE9F" : "#2F723A");
    area.setSyntaxScheme(scheme);
    if (area.getParent() != null && area.getParent().getParent() instanceof RTextScrollPane pane) {
      pane.getGutter().setBackground(LinkSqlVisualStyle.editorSurface());
      pane.getGutter().setLineNumberColor(LinkSqlVisualStyle.subtleText());
      pane.getGutter().setBorderColor(LinkSqlVisualStyle.border());
    }
  }

  private static void createUi() {
    frame = new JFrame("LinkSQL Studio — 3.4.4");
    LinkSqlAppIcon.apply(frame);
    frame.setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
    frame.addWindowListener(new WindowAdapter() {
      public void windowClosing(WindowEvent e) {
        if (tabs != null) {
          saveSession();
          shuttingDown = true;
          for (int i = tabs.getTabCount() - 1; i >= 0; i--) {
            if (!closeSqlTab(i, false)) { shuttingDown = false; return; }
          }
        }
        closeResources();
        frame.dispose();
        System.exit(0);
      }
    });
    frame.setSize(1380, 850);
    frame.getContentPane().setBackground(LinkSqlVisualStyle.background());
    frame.setMinimumSize(new Dimension(900, 600));
    frame.setLocationRelativeTo(null);

    JButton load = new JButton("Nova conexão");
    JButton manage = new JButton("Gerenciar conexões");
    JButton searchColumns = new JButton("Buscar coluna");
    JButton newTab = new JButton("Nova aba SQL");
    JButton openSql = new JButton("Abrir SQL");
    JButton saveSql = new JButton("Salvar SQL");
    JButton closeTab = new JButton("Fechar aba");
    JButton history = new JButton("Histórico e favoritos");
    JButton formatSql = new JButton("Formatar SQL");
    execute = new JButton("Executar SQL  (Ctrl+Enter)");
    executeScriptButton = new JButton("Executar script");
    cancelQueryButton = new JButton("Cancelar consulta");
    cancelQueryButton.setEnabled(false);
    JButton clear = new JButton("Limpar grade");
    JButton export = new JButton("Exportar CSV");
    themeButton = new JButton();
    LinkSqlVisualStyle.button(themeButton, darkMode ? "Tema claro" : "Tema escuro",
        darkMode ? "sun" : "moon", "quiet");
    LinkSqlVisualStyle.button(load, "Conexões", "database", "quiet");
    LinkSqlVisualStyle.button(newTab, "Nova aba", "plus", "quiet");
    LinkSqlVisualStyle.button(openSql, "Abrir", "open", "quiet");
    LinkSqlVisualStyle.button(saveSql, "Salvar", "save", "quiet");
    LinkSqlVisualStyle.button(execute, "Executar", "play", "primary");
    LinkSqlVisualStyle.button(cancelQueryButton, "Parar", "stop", "danger");
    LinkSqlVisualStyle.button(export, "Exportar CSV", "export", "quiet");
    LinkSqlVisualStyle.button(formatSql, "Formatar", "code", "quiet");
    LinkSqlVisualStyle.button(executeScriptButton, "Executar script", "play", "quiet");
    execute.setToolTipText("Executar SQL (Ctrl+Enter)");
    formatSql.setToolTipText("Formatar SQL (Ctrl+Shift+F)");
    toolbar = new JPanel(new LinkSqlWrapLayout(FlowLayout.LEFT, 6, 3));
    LinkSqlVisualStyle.surfacePanel(toolbar, "toolbar");
    for (JButton b : new JButton[]{execute, cancelQueryButton, formatSql, saveSql}) toolbar.add(b);

    JMenuBar menuBar = new JMenuBar();
    JMenu fileMenu = new JMenu("Arquivo");
    addMenuAction(fileMenu, "Nova aba SQL", newTab);
    addMenuAction(fileMenu, "Abrir SQL", openSql);
    addMenuAction(fileMenu, "Salvar SQL", saveSql);
    addMenuAction(fileMenu, "Fechar aba", closeTab);
    menuBar.add(fileMenu);

    JMenu sqlMenu = new JMenu("SQL");
    JMenuItem renameTab = new JMenuItem("Renomear aba SQL");
    renameTab.addActionListener(e -> renameActiveTab());
    fileMenu.add(renameTab);
    addMenuAction(sqlMenu, "Executar SQL (Ctrl+Enter)", execute);
    addMenuAction(sqlMenu, "Executar script (Ctrl+Shift+Enter)", executeScriptButton);
    addMenuAction(sqlMenu, "Cancelar consulta", cancelQueryButton);
    addMenuAction(sqlMenu, "Formatar SQL (Ctrl+Shift+F)", formatSql);
    addMenuAction(sqlMenu, "Histórico e favoritos", history);
    JMenuItem findItem = new JMenuItem("Localizar no editor (Ctrl+F)");
    findItem.addActionListener(e -> showFindReplace(false));
    sqlMenu.add(findItem);
    JMenuItem replaceItem = new JMenuItem("Localizar e substituir (Ctrl+H)");
    replaceItem.addActionListener(e -> showFindReplace(true));
    sqlMenu.add(replaceItem);
    sqlMenu.addSeparator();
    JMenuItem goLine = new JMenuItem("Ir para linha... (Ctrl+G)");
    goLine.addActionListener(e -> goToSqlLine());
    sqlMenu.add(goLine);
    JMenuItem comment = new JMenuItem("Comentar/descomentar linhas (Ctrl+/ ou Ctrl+Shift+7)");
    comment.addActionListener(e -> toggleSqlComment());
    sqlMenu.add(comment);
    JMenuItem duplicate = new JMenuItem("Duplicar linha ou seleção (Ctrl+D)");
    duplicate.addActionListener(e -> duplicateSqlLine());
    sqlMenu.add(duplicate);
    menuBar.add(sqlMenu);

    JMenu resultMenu = new JMenu("Resultados");
    addMenuAction(resultMenu, "Exportar CSV", export);
    JMenuItem copyHeader = new JMenuItem("Copiar selecao com cabecalho (Ctrl+Shift+C)");
    copyHeader.addActionListener(e -> { if (results != null) copySelectedResults(results, true); });
    resultMenu.add(copyHeader);
    addMenuAction(resultMenu, "Limpar grade selecionada", clear);
    menuBar.add(resultMenu);

    JMenu connectionsMenu = new JMenu("Conexoes");
    JMenuItem importProfiles = new JMenuItem("Importar perfis de arquivo");
    importProfiles.addActionListener(e -> chooseAndLoad());
    connectionsMenu.add(importProfiles);
    addMenuAction(connectionsMenu, "Gerenciar conexoes", manage);
    addMenuAction(connectionsMenu, "Buscar coluna", searchColumns);
    JMenuItem structure = new JMenuItem("Estrutura da tabela selecionada");
    structure.addActionListener(e -> {
      TreePath path = explorer.getSelectionPath();
      if (path == null) { status.setText(" Selecione uma tabela no explorador."); return; }
      DefaultMutableTreeNode node = (DefaultMutableTreeNode) path.getLastPathComponent();
      Object selected = node.getUserObject();
      if (selected instanceof TableNode t) showTableMetadata(t, null);
      else if (node.getParent() instanceof DefaultMutableTreeNode parent && parent.getUserObject() instanceof TableNode t
          && selected instanceof String column && column.contains("  :  "))
        showTableMetadata(t, column.split("  :  ", 2)[0]);
      else status.setText(" Selecione uma tabela ou coluna no explorador.");
    });
    connectionsMenu.add(structure);
    menuBar.add(connectionsMenu);

    JMenu viewMenu = new JMenu("Aparência");
    addMenuAction(viewMenu, "Alternar tema claro/escuro", themeButton);
    viewMenu.addSeparator();
    JMenuItem settingsMenu = new JMenuItem("Configurações (Ctrl+,)");
    // Show the shortcut once in the label; keep its key binding active on the window.
    frame.getRootPane().getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).put(
        KeyStroke.getKeyStroke(KeyEvent.VK_COMMA, InputEvent.CTRL_DOWN_MASK), "linksql.settings");
    frame.getRootPane().getActionMap().put("linksql.settings", new AbstractAction() {
      @Override public void actionPerformed(ActionEvent e) { openSettings(); }
    });
    settingsMenu.addActionListener(e -> openSettings());
    viewMenu.add(settingsMenu);
    menuBar.add(viewMenu);
    for (int i = 0; i < menuBar.getMenuCount(); i++) LinkSqlVisualStyle.popup(menuBar.getMenu(i).getPopupMenu());
    frame.setJMenuBar(menuBar);

    rootNode = new DefaultMutableTreeNode("Conexões SQL");
    explorer = new JTree(rootNode);
    explorer.setRootVisible(true);
    LinkSqlVisualStyle.explorer(explorer);
    explorer.setShowsRootHandles(true);
    explorer.addTreeWillExpandListener(new javax.swing.event.TreeWillExpandListener() {
      public void treeWillExpand(javax.swing.event.TreeExpansionEvent event) {
        Object node = event.getPath().getLastPathComponent();
        if (node instanceof DefaultMutableTreeNode n) {
          if (n.getUserObject() instanceof SourceNode source && !source.loaded) loadNamespaces(n, source);
          if (n.getUserObject() instanceof NamespaceNode namespace && !namespace.loaded) loadTables(n, namespace);
          if (n.getUserObject() instanceof RoutineFolder folder && !folder.loaded) loadRoutines(n, folder);
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
    LinkSqlVisualStyle.popup(tableMenu);
    JMenuItem queryTable = new JMenuItem("Consultar dados (100 linhas)");
    JMenuItem copyTable = new JMenuItem("Copiar nome completo");
    JMenuItem inspectTable = new JMenuItem("Estrutura da tabela...");
    JMenuItem inspectColumn = new JMenuItem("Detalhes da coluna...");
    tableMenu.add(queryTable);
    tableMenu.add(copyTable);
    tableMenu.addSeparator();
    tableMenu.add(inspectTable);
    tableMenu.add(inspectColumn);
    final TableNode[] selectedTable = new TableNode[1];
    final String[] selectedColumnName = new String[1];
    explorer.addMouseListener(new MouseAdapter() {
      private void popup(MouseEvent e) {
        if (!e.isPopupTrigger()) return;
        TreePath path = explorer.getPathForLocation(e.getX(), e.getY());
        if (path == null) return;
        DefaultMutableTreeNode clicked = (DefaultMutableTreeNode) path.getLastPathComponent();
        Object item = clicked.getUserObject();
        TableNode table;
        String column = null;
        if (item instanceof TableNode selected) {
          table = selected;
        } else if (item instanceof String label && clicked.getParent() instanceof DefaultMutableTreeNode parent
            && parent.getUserObject() instanceof TableNode selected && !label.startsWith("Carregando")
            && !label.startsWith("Erro") && !label.startsWith("Expandir")) {
          table = selected;
          column = label.split("  :  ", 2)[0];
        } else return;
        selectedTable[0] = table;
        selectedColumnName[0] = column;
        queryTable.setVisible(column == null);
        copyTable.setVisible(column == null);
        inspectTable.setVisible(column == null);
        inspectColumn.setVisible(column != null);
        explorer.setSelectionPath(path);
        tableMenu.show(explorer, e.getX(), e.getY());
      }
      public void mousePressed(MouseEvent e) { popup(e); }
      public void mouseReleased(MouseEvent e) { popup(e); }
    });
    // Ações diretamente no alias da conexão, sem carregar tabelas nem senhas.
    JPopupMenu sourceMenu = new JPopupMenu();
    LinkSqlVisualStyle.popup(sourceMenu);
    JMenuItem editSource = new JMenuItem("Editar conexão");
    JMenuItem copySource = new JMenuItem("Copiar alias SQL");
    JMenuItem refreshSource = new JMenuItem("Atualizar tabelas");
    sourceMenu.add(editSource); sourceMenu.add(copySource); sourceMenu.add(refreshSource);
    final DefaultMutableTreeNode[] clickedSource = new DefaultMutableTreeNode[1];
    explorer.addMouseListener(new MouseAdapter() {
      private void maybeShow(MouseEvent e) {
        if (!e.isPopupTrigger()) return;
        TreePath path = explorer.getPathForLocation(e.getX(), e.getY());
        if (path == null) return;
        DefaultMutableTreeNode node = (DefaultMutableTreeNode) path.getLastPathComponent();
        if (!(node.getUserObject() instanceof SourceNode)) return;
        clickedSource[0] = node;
        explorer.setSelectionPath(path);
        sourceMenu.show(explorer, e.getX(), e.getY());
      }
      public void mousePressed(MouseEvent e) { maybeShow(e); }
      public void mouseReleased(MouseEvent e) { maybeShow(e); }
    });
    editSource.addActionListener(e -> {
      if (clickedSource[0] != null && clickedSource[0].getUserObject() instanceof SourceNode source)
        ConnectionManagerDialog.show(frame, configPath, CONNECTIONS, Main::loadConfiguration, source.name);
    });
    copySource.addActionListener(e -> {
      if (clickedSource[0] != null && clickedSource[0].getUserObject() instanceof SourceNode source)
        Toolkit.getDefaultToolkit().getSystemClipboard().setContents(
            new java.awt.datatransfer.StringSelection(source.name), null);
    });
    refreshSource.addActionListener(e -> {
      if (clickedSource[0] == null || !(clickedSource[0].getUserObject() instanceof SourceNode source)) return;
      source.loaded = false;
      TABLE_CACHE.remove(source.name);
      clickedSource[0].removeAllChildren();
      clickedSource[0].add(new DefaultMutableTreeNode("Expandir para carregar schemas..."));
      ((DefaultTreeModel) explorer.getModel()).reload(clickedSource[0]);
      status.setText(" Tabelas de " + source.name + " prontas para atualizar. Expanda a conexão.");
    });

    queryTable.addActionListener(e -> { if (selectedTable[0] != null) insertTableSql(selectedTable[0]); });
    copyTable.addActionListener(e -> {
      if (selectedTable[0] != null) {
        TableNode table = selectedTable[0];
        Toolkit.getDefaultToolkit().getSystemClipboard().setContents(
            new java.awt.datatransfer.StringSelection(qualifiedTable(table)), null);
      }
    });
    inspectTable.addActionListener(e -> {
      if (selectedTable[0] != null) showTableMetadata(selectedTable[0], null);
    });
    inspectColumn.addActionListener(e -> {
      if (selectedTable[0] != null && selectedColumnName[0] != null)
        showTableMetadata(selectedTable[0], selectedColumnName[0]);
    });

    searchColumns.addActionListener(e -> searchColumnsAcrossTables());

    tabs = new JTabbedPane();
    LinkSqlVisualStyle.tabPane(tabs);
    installSqlTabContextMenu();
    restoringSession = true;
    restoreSession();
    if (tabs.getTabCount() == 0) addSqlTab();
    restoringSession = false;
    sessionTimer = new javax.swing.Timer(1500, e -> saveSession());
    sessionTimer.setRepeats(false);
    tabs.addChangeListener(e -> {
      scheduleSessionSave();
      updateTabSourceChoice();
    });
    tabSourceBox = new JComboBox<>();
    tabSourceBox.setPreferredSize(new Dimension(250, 30));
    LinkSqlVisualStyle.input(tabSourceBox);
    tabSourceBox.setToolTipText("Origem padrão desta aba: sem prefixo SRV_..., usa o banco selecionado.");
    tabSourceBox.addActionListener(e -> {
      if (updatingTabSourceBox || tabs.getSelectedComponent() == null) return;
      TabSourceChoice choice = (TabSourceChoice) tabSourceBox.getSelectedItem();
      if (choice == null) return;
      TAB_SOURCES.put(tabs.getSelectedComponent(), choice.alias());
      scheduleSessionSave();
    });
    updateTabSourceChoice();
    resultTabs = new JTabbedPane();
    LinkSqlVisualStyle.tabPane(resultTabs);
    resultTabs.addChangeListener(e -> selectResultTab());
    JPanel resultPanel = new JPanel(new BorderLayout());
    LinkSqlVisualStyle.surfacePanel(resultPanel, "results");
    resultSummary = new JLabel("  RESULTADOS");
    resultSummary.setFont(new Font("Segoe UI", Font.BOLD, 12));
    resultSummary.setForeground(LinkSqlVisualStyle.text());
    resultSummary.putClientProperty("linksql.labelRole", "normal");
    resultSummary.setIcon(LinkSqlVisualStyle.icon("table", LinkSqlVisualStyle.accent(), 16));
    resultSummary.setIconTextGap(8);
    resultSummary.setBorder(BorderFactory.createEmptyBorder(7, 3, 6, 6));
    resultFilter = new JTextField(14);
    resultFilter.putClientProperty("JTextField.placeholderText", "Pesquisar nos resultados...");
    LinkSqlVisualStyle.input(resultFilter);
    resultFilter.setToolTipText("Filtra os dados ja carregados na aba selecionada; nao consulta novamente o servidor.");
    // A filtragem de 100 mil linhas no EDT a cada tecla trava a interface.
    // Aguardar um intervalo configurável permite digitar antes de recalcular a grade.
    resultFilterDebounce = new javax.swing.Timer(resultFilterDelayMs, e -> applyResultFilter());
    resultFilterDebounce.setRepeats(false);
    resultFilter.getDocument().addDocumentListener(new DocumentListener() {
      public void insertUpdate(DocumentEvent e) { resultFilterDebounce.restart(); }
      public void removeUpdate(DocumentEvent e) { resultFilterDebounce.restart(); }
      public void changedUpdate(DocumentEvent e) { resultFilterDebounce.restart(); }
    });
    JPanel resultHeader = new JPanel(new BorderLayout(8, 0));
    LinkSqlVisualStyle.surfacePanel(resultHeader, "section");
    resultHeader.add(resultSummary, BorderLayout.NORTH);
    JPanel resultTools = LinkSqlVisualStyle.actionRow(FlowLayout.LEFT);
    resultFilterColumn = new JComboBox<>(new String[]{"Todas as colunas"});
    resultFilterColumn.setPrototypeDisplayValue("Todas as colunas");
    LinkSqlVisualStyle.input(resultFilterColumn);
    resultFilterColumn.addActionListener(e -> applyResultFilter());
    resultTools.add(LinkSqlVisualStyle.hint("Coluna:"));
    resultTools.add(resultFilterColumn);
    resultTools.add(LinkSqlVisualStyle.hint("Filtro:"));
    resultTools.add(resultFilter);
    resultTools.add(export);
    resultHeader.add(resultTools, BorderLayout.CENTER);
    resultPanel.add(resultHeader, BorderLayout.NORTH);
    resultPanel.add(resultTabs, BorderLayout.CENTER);
    JPanel paging = LinkSqlVisualStyle.actionRow(FlowLayout.RIGHT);
    LinkSqlVisualStyle.surfacePanel(paging, "toolbar");
    paging.setOpaque(false);
    previousPageButton = new JButton("‹  Anterior");
    nextPageButton = new JButton("Próxima  ›");
    pageLabel = new JLabel("Página 1");
    pageSizeBox = new JComboBox<>(new String[]{"100", "250", "500", "1.000", "2.000", "5.000", "10.000", "20.000", "50.000", "100.000", "Todas (1 página)"});
    pageSizeBox.setEditable(true);
    pageSizeBox.setSelectedItem(formatPageSize(preferredPageSize));
    pageSizeBox.setToolTipText("De 100 a 100.000 linhas, ou todas em uma única página. A opção Todas pode usar muita memória.");
    pageSizeBox.setPreferredSize(new Dimension(102, 28));
    LinkSqlVisualStyle.input(pageSizeBox);
    previousPageButton.putClientProperty("JButton.buttonType", "toolBarButton");
    nextPageButton.putClientProperty("JButton.buttonType", "toolBarButton");
    pageSizeBox.addActionListener(e -> changeRowsPerPage());
    paging.add(new JLabel("Linhas/página:"));
    paging.add(pageSizeBox);
    previousPageButton.addActionListener(e -> changeResultPage(-1));
    nextPageButton.addActionListener(e -> changeResultPage(1));
    paging.add(previousPageButton);
    paging.add(pageLabel);
    paging.add(nextPageButton);
    resultPanel.add(paging, BorderLayout.SOUTH);
    updatePageControls();
    JPanel sqlWorkspace = new JPanel(new BorderLayout());
    LinkSqlVisualStyle.surfacePanel(sqlWorkspace, "shell");
    JPanel sqlSourceToolbar = new JPanel(new LinkSqlWrapLayout(FlowLayout.LEFT, 9, 3));
    LinkSqlVisualStyle.surfacePanel(sqlSourceToolbar, "section");
    sqlSourceToolbar.add(LinkSqlVisualStyle.smallLabel("BANCO DESTA ABA"));
    sqlSourceToolbar.add(tabSourceBox);
    JLabel sourceHint = LinkSqlVisualStyle.hint("SQL nativo ou federado com SRV_...");
    sourceHint.setToolTipText("Consultas sem prefixo utilizam o banco desta aba; nomes SRV_XXX permitem consultas federadas.");
    sqlSourceToolbar.setToolTipText(sourceHint.getToolTipText());
    JPanel editorTools = new JPanel(new BorderLayout());
    LinkSqlVisualStyle.surfacePanel(editorTools, "shell");
    editorTools.add(toolbar, BorderLayout.NORTH);
    editorTools.add(sqlSourceToolbar, BorderLayout.SOUTH);
    sqlWorkspace.add(editorTools, BorderLayout.NORTH);
    sqlWorkspace.add(tabs, BorderLayout.CENTER);
    JSplitPane vertical = new JSplitPane(JSplitPane.VERTICAL_SPLIT, sqlWorkspace, resultPanel);
    LinkSqlVisualStyle.splitter(vertical);
    vertical.setResizeWeight(.57);
    sqlWorkspace.setMinimumSize(new Dimension(420, 160));
    resultPanel.setMinimumSize(new Dimension(420, 130));
    JPanel explorerPanel = new JPanel(new BorderLayout(0, 0));
    LinkSqlVisualStyle.surfacePanel(explorerPanel, "explorer");
    explorerPanel.setMinimumSize(new Dimension(210, 120));
    JPanel explorerTop = new JPanel(new BorderLayout(0, 8));
    LinkSqlVisualStyle.surfacePanel(explorerTop, "section");
    explorerTop.add(LinkSqlVisualStyle.sectionTitle("Explorador", "Conexões e objetos do banco", "database"), BorderLayout.NORTH);

    tableSearch = new JTextField();
    LinkSqlVisualStyle.input(tableSearch);
    tableSearch.putClientProperty("JTextField.placeholderText", "Pesquisar tabela...");
    tableSearch.setToolTipText("Busca entre as tabelas dos servidores já expandidos; Enter encontra a próxima.");
    tableSearch.addActionListener(e -> findTable());
    explorerTop.add(tableSearch, BorderLayout.SOUTH);
    explorerPanel.add(explorerTop, BorderLayout.NORTH);
    JScrollPane treeScroll = new JScrollPane(explorer);
    treeScroll.setBorder(BorderFactory.createEmptyBorder());
    explorerPanel.add(treeScroll, BorderLayout.CENTER);
    JSplitPane horizontal = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, explorerPanel, vertical);
    LinkSqlVisualStyle.splitter(horizontal);
    horizontal.setResizeWeight(.20);
    horizontal.setDividerLocation(275);
    status = new JLabel(" Pronto para consultar  ·  " + formatPageSize(preferredPageSize) + " linhas por página");
    LinkSqlVisualStyle.status(status);

    JPanel header = LinkSqlWorkspaceChrome.header(openSql, newTab, themeButton);
    JPanel rail = LinkSqlWorkspaceChrome.navigation(manage, history, Main::openSettings);
    frame.setContentPane(LinkSqlWorkspaceChrome.shell(header, rail, horizontal, status));

    load.addActionListener(e -> manageConnections());
    manage.addActionListener(e -> manageConnections());
    newTab.addActionListener(e -> addSqlTab());
    openSql.addActionListener(e -> openSqlFile());
    saveSql.addActionListener(e -> saveSqlFile(false));
    closeTab.addActionListener(e -> closeSqlTab(tabs.getSelectedIndex()));
    history.addActionListener(e -> showHistory());
    formatSql.addActionListener(e -> formatActiveSql());
    execute.addActionListener(e -> runQuery());
    executeScriptButton.addActionListener(e -> runScript());
    cancelQueryButton.addActionListener(e -> cancelRunningQuery());
    clear.addActionListener(e -> clearSelectedResult());
    export.addActionListener(e -> exportCsv());
    themeButton.addActionListener(e -> toggleTheme());
    frame.setVisible(true);
    loadConfiguration();
  }

  private static void addMenuAction(JMenu menu, String label, JButton actionButton) {
    JMenuItem item = new JMenuItem(label);
    item.addActionListener(e -> actionButton.doClick());
    menu.add(item);
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
    area.setFont(new Font(Font.MONOSPACED, Font.PLAIN, sqlFontSize));
    area.setTabSize(2);
    if (tabs.getTabCount() == 0) area.setText("-- Bem-vindo ao LinkSQL\n-- Selecione um banco acima ou use SRV_NOME.tabela no modo federado.\n\nSELECT 1 AS teste;");
    area.getInputMap().put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, InputEvent.CTRL_DOWN_MASK), "runSql");
    area.getInputMap().put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, InputEvent.CTRL_DOWN_MASK | InputEvent.SHIFT_DOWN_MASK), "runScript");
    area.getActionMap().put("runScript", new AbstractAction() { public void actionPerformed(ActionEvent e) { runScript(); } });
    area.getInputMap().put(KeyStroke.getKeyStroke(KeyEvent.VK_SPACE, InputEvent.CTRL_DOWN_MASK), "sqlComplete");
    area.getActionMap().put("sqlComplete", new AbstractAction() { public void actionPerformed(ActionEvent e) { showCompletions(area); } });
    // O RSyntaxTextArea insere o ponto usando sua acao padrao de digitacao.
    // O autocomplete e disparado pelo DocumentListener *apos* a insercao,
    // evitando digitar duas vezes por conflito KEY_PRESSED/KEY_TYPED.
    // Ctrl+Espaco continua disponivel para disparo manual.
    area.getInputMap().put(KeyStroke.getKeyStroke(KeyEvent.VK_S, InputEvent.CTRL_DOWN_MASK), "saveSql");
    area.getActionMap().put("saveSql", new AbstractAction() { public void actionPerformed(ActionEvent e) { saveSqlFile(false); } });
    area.getInputMap().put(KeyStroke.getKeyStroke(KeyEvent.VK_F, InputEvent.CTRL_DOWN_MASK | InputEvent.SHIFT_DOWN_MASK), "formatSql");
    area.getActionMap().put("formatSql", new AbstractAction() { public void actionPerformed(ActionEvent e) { formatActiveSql(); } });
    area.getInputMap().put(KeyStroke.getKeyStroke(KeyEvent.VK_F, InputEvent.CTRL_DOWN_MASK), "findSql");
    area.getActionMap().put("findSql", new AbstractAction() { public void actionPerformed(ActionEvent e) { showFindReplace(false); } });
    area.getInputMap().put(KeyStroke.getKeyStroke(KeyEvent.VK_H, InputEvent.CTRL_DOWN_MASK), "replaceSql");
    area.getActionMap().put("replaceSql", new AbstractAction() { public void actionPerformed(ActionEvent e) { showFindReplace(true); } });
    area.getInputMap().put(KeyStroke.getKeyStroke(KeyEvent.VK_O, InputEvent.CTRL_DOWN_MASK), "openSql");
    area.getActionMap().put("openSql", new AbstractAction() { public void actionPerformed(ActionEvent e) { openSqlFile(); } });
    area.getActionMap().put("runSql", new AbstractAction() { public void actionPerformed(ActionEvent e) { runQuery(); } });
    area.getInputMap().put(KeyStroke.getKeyStroke(KeyEvent.VK_G, InputEvent.CTRL_DOWN_MASK), "goToLine");
    area.getActionMap().put("goToLine", new AbstractAction() { public void actionPerformed(ActionEvent e) { goToSqlLine(); } });
    SqlEditorShortcutSupport.installCommentShortcut(area, Main::toggleSqlComment);
    area.getInputMap().put(KeyStroke.getKeyStroke(KeyEvent.VK_D, InputEvent.CTRL_DOWN_MASK), "duplicateLine");
    area.getActionMap().put("duplicateLine", new AbstractAction() { public void actionPerformed(ActionEvent e) { duplicateSqlLine(); } });
    String title = "SQL " + (nextTabNumber++);
    RTextScrollPane scroll = new RTextScrollPane(area);
    scroll.setLineNumbersEnabled(true);
    scroll.setFoldIndicatorEnabled(true);
    scroll.setBorder(BorderFactory.createEmptyBorder());
    scroll.getGutter().setBackground(LinkSqlVisualStyle.editorSurface());
    scroll.getGutter().setLineNumberColor(LinkSqlVisualStyle.subtleText());
    scroll.getGutter().setBorderColor(LinkSqlVisualStyle.border());
    tabs.addTab(title, scroll);
    TAB_SOURCES.put(scroll, "");
    SAVED_TEXT.put(scroll, area.getText());
    // Autocomplete de digitação: aguarda uma pausa e nunca bloqueia a escrita.
    javax.swing.Timer completionDelay = new javax.swing.Timer(completionDelayMs, e -> {
      if (automaticCompletion && area.isShowing() && area.isFocusOwner()
          && area.getSelectedText() == null)
        showCompletions(area, false, true);
    });
    completionDelay.setRepeats(false);
    area.putClientProperty("automaticCompletionTimer", completionDelay);
    area.getDocument().addDocumentListener(new DocumentListener() {
      private void changed(boolean dotInserted) {
        closeCompletion(area);
        updateDirtyTitle(scroll);
        scheduleSessionSave();
        if (automaticCompletion && area.isShowing() && area.isFocusOwner()) {
          if (dotInserted && area.getSelectedText() == null) {
            completionDelay.stop();
            // Confere a posicao atual; eventos de edicao podem ser enfileirados.
            int caret = area.getCaretPosition();
            if (caret > 0 && area.getText().charAt(caret - 1) == '.') {
              showCompletions(area);
              return;
            }
          }
          completionDelay.restart();
        } else completionDelay.stop();
      }
      public void insertUpdate(DocumentEvent e) {
        // Captura o caractere antes de sair da notificacao do documento.
        boolean dotInserted = false;
        if (e.getLength() == 1) {
          try { dotInserted = ".".equals(e.getDocument().getText(e.getOffset(), 1)); }
          catch (javax.swing.text.BadLocationException ignored) {}
        }
        final boolean dot = dotInserted;
        SwingUtilities.invokeLater(() -> changed(dot));
      }
      public void removeUpdate(DocumentEvent e) { SwingUtilities.invokeLater(() -> changed(false)); }
      public void changedUpdate(DocumentEvent e) { SwingUtilities.invokeLater(() -> changed(false)); }
    });
    tabs.setSelectedIndex(tabs.getTabCount() - 1);
    scheduleSessionSave();
  }

  private static void renameActiveTab() {
    int index = tabs.getSelectedIndex();
    if (index < 0) return;
    Component tab = tabs.getComponentAt(index);
    if (OPEN_FILES.containsKey(tab)) {
      JOptionPane.showMessageDialog(frame, "Esta aba esta associada a um arquivo SQL. O nome segue o arquivo.");
      return;
    }
    String old = tabs.getTitleAt(index).replaceFirst("^\\* ", "");
    String name = JOptionPane.showInputDialog(frame, "Novo nome da aba SQL:", old);
    if (name == null || name.isBlank()) return;
    tabs.setTitleAt(index, name.trim());
    updateDirtyTitle(tab);
    scheduleSessionSave();
  }

  // Operacoes sobre o editor SQL, sem modificar o resultado de consultas.
  private static void goToSqlLine() {
    RSyntaxTextArea editor = (RSyntaxTextArea) activeEditor();
    int current = 1;
    try { current = editor.getLineOfOffset(editor.getCaretPosition()) + 1; }
    catch (javax.swing.text.BadLocationException ignored) { }
    String input = JOptionPane.showInputDialog(frame,
        "Linha (1 a " + editor.getLineCount() + "):", Integer.toString(current));
    if (input == null) return;
    try {
      int line = Integer.parseInt(input.trim());
      if (line < 1 || line > editor.getLineCount()) throw new NumberFormatException();
      editor.setCaretPosition(editor.getLineStartOffset(line - 1));
      editor.requestFocusInWindow();
    } catch (NumberFormatException | javax.swing.text.BadLocationException ex) {
      JOptionPane.showMessageDialog(frame, "Informe uma linha válida de 1 a " + editor.getLineCount() + ".");
    }
  }

  private static void toggleSqlComment() {
    RSyntaxTextArea editor = (RSyntaxTextArea) activeEditor();
    try {
      int start = editor.getSelectionStart();
      int end = editor.getSelectionEnd();
      boolean selected = end > start;
      int first = editor.getLineOfOffset(start);
      int last = editor.getLineOfOffset(selected ? end - 1 : end);
      int begin = editor.getLineStartOffset(first);
      int finish = editor.getLineEndOffset(last);
      String content = editor.getText(begin, finish - begin);
      String changed = SqlEditorActions.toggleComments(content);
      if (!changed.equals(content)) {
        editor.replaceRange(changed, begin, finish);
        if (selected) editor.select(begin, begin + changed.length());
        else editor.setCaretPosition(Math.min(begin + changed.length(),
            Math.max(begin, start + changed.length() - content.length())));
      }
      editor.requestFocusInWindow();
    } catch (javax.swing.text.BadLocationException ex) { showError(ex); }
  }

  private static void duplicateSqlLine() {
    RSyntaxTextArea editor = (RSyntaxTextArea) activeEditor();
    try {
      if (editor.getSelectionStart() != editor.getSelectionEnd()) {
        int end = editor.getSelectionEnd();
        String selected = editor.getSelectedText();
        editor.insert(selected, end);
        editor.select(end, end + selected.length());
      } else {
        int line = editor.getLineOfOffset(editor.getCaretPosition());
        int start = editor.getLineStartOffset(line);
        int end = editor.getLineEndOffset(line);
        String text = editor.getText(start, end - start);
        boolean finalLine = end == editor.getDocument().getLength() && !text.endsWith("\n");
        String inserted = finalLine ? "\n" + text : text;
        editor.insert(inserted, end);
        editor.setCaretPosition(end + inserted.length());
      }
      editor.requestFocusInWindow();
    } catch (javax.swing.text.BadLocationException ex) { showError(ex); }
  }

  private static void showFindReplace(boolean replaceMode) {
    RSyntaxTextArea editor = (RSyntaxTextArea) activeEditor();
    JTextField find = new JTextField(24);
    String selected = editor.getSelectedText();
    if (selected != null && !selected.contains("\n")) find.setText(selected);
    JTextField replacement = new JTextField(24);
    JCheckBox caseSensitive = new JCheckBox("Diferenciar maiusculas/minusculas");
    JPanel panel = new JPanel(new GridLayout(replaceMode ? 3 : 2, 2, 6, 6));
    panel.add(new JLabel("Localizar:")); panel.add(find);
    if (replaceMode) { panel.add(new JLabel("Substituir por:")); panel.add(replacement); }
    panel.add(caseSensitive); panel.add(new JLabel(""));
    String[] actions = replaceMode
        ? new String[]{"Proximo", "Substituir", "Substituir todos", "Fechar"}
        : new String[]{"Proximo", "Fechar"};
    while (true) {
      int action = JOptionPane.showOptionDialog(frame, panel,
          replaceMode ? "Localizar e substituir SQL" : "Localizar SQL",
          JOptionPane.DEFAULT_OPTION, JOptionPane.PLAIN_MESSAGE, null, actions, actions[0]);
      if (action < 0 || action == actions.length - 1) break;
      String needle = find.getText();
      if (needle.isEmpty()) { status.setText(" Informe o texto a localizar."); continue; }
      if (replaceMode && action == 2) {
        String original = editor.getText();
        java.util.regex.Pattern pattern = java.util.regex.Pattern.compile(java.util.regex.Pattern.quote(needle),
            caseSensitive.isSelected() ? 0 : java.util.regex.Pattern.CASE_INSENSITIVE | java.util.regex.Pattern.UNICODE_CASE);
        java.util.regex.Matcher matcher = pattern.matcher(original);
        int count = 0;
        while (matcher.find()) count++;
        if (count > 0) {
          editor.setText(matcher.replaceAll(java.util.regex.Matcher.quoteReplacement(replacement.getText())));
          status.setText(" Substituidas " + count + " ocorrencia(s).");
        } else status.setText(" Texto nao encontrado.");
        continue;
      }
      if (replaceMode && action == 1 && editor.getSelectedText() != null &&
          (caseSensitive.isSelected() ? editor.getSelectedText().equals(needle)
              : editor.getSelectedText().equalsIgnoreCase(needle))) {
        int pos = editor.getSelectionStart();
        editor.replaceSelection(replacement.getText());
        editor.setCaretPosition(Math.min(editor.getDocument().getLength(), pos + replacement.getText().length()));
      }
      String haystack = editor.getText();
      int from = Math.min(editor.getSelectionEnd(), haystack.length());
      String searchIn = caseSensitive.isSelected() ? haystack : haystack.toLowerCase(Locale.ROOT);
      String searchFor = caseSensitive.isSelected() ? needle : needle.toLowerCase(Locale.ROOT);
      int found = searchIn.indexOf(searchFor, from);
      if (found < 0) found = searchIn.indexOf(searchFor);
      if (found >= 0) {
        editor.select(found, found + needle.length());
        editor.getCaret().setSelectionVisible(true);
        status.setText(" Encontrado na posicao " + (found + 1) + ".");
      } else status.setText(" Texto nao encontrado.");
    }
    editor.requestFocusInWindow();
  }

  private static void openSqlFile() {
    JFileChooser chooser = new JFileChooser();
    if (Boolean.getBoolean("linksql.packaged"))
      chooser.setCurrentDirectory(defaultUserFile("consulta.sql").getParentFile());
    chooser.setFileFilter(new javax.swing.filechooser.FileNameExtensionFilter("Scripts SQL (*.sql)", "sql"));
    if (chooser.showOpenDialog(frame) != JFileChooser.APPROVE_OPTION) return;
    Path file = chooser.getSelectedFile().toPath();
    try {
      addSqlTab();
      activeEditor().setText(Files.readString(file, StandardCharsets.UTF_8));
      OPEN_FILES.put(tabs.getSelectedComponent(), file);
      SAVED_TEXT.put(tabs.getSelectedComponent(), activeEditor().getText());
      tabs.setTitleAt(tabs.getSelectedIndex(), file.getFileName().toString());
      status.setText(" Script aberto: " + file);
    } catch (IOException ex) { showError(ex); }
  }

  private static boolean saveSqlFile(boolean saveAs) {
    Component tab = tabs.getSelectedComponent();
    if (tab == null) return false;
    Path file = saveAs ? null : OPEN_FILES.get(tab);
    if (file == null) {
      JFileChooser chooser = new JFileChooser();
      chooser.setSelectedFile(defaultUserFile("consulta.sql"));
      chooser.setFileFilter(new javax.swing.filechooser.FileNameExtensionFilter("Scripts SQL (*.sql)", "sql"));
      if (chooser.showSaveDialog(frame) != JFileChooser.APPROVE_OPTION) return false;
      file = chooser.getSelectedFile().toPath();
      if (!file.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".sql"))
        file = file.resolveSibling(file.getFileName() + ".sql");
      if (Files.exists(file) && JOptionPane.showConfirmDialog(frame, "Sobrescrever " + file.getFileName() + "?", "Confirmar", JOptionPane.YES_NO_OPTION) != JOptionPane.YES_OPTION) return false;
    }
    try {
      Files.writeString(file, activeEditor().getText(), StandardCharsets.UTF_8);
      OPEN_FILES.put(tab, file);
      SAVED_TEXT.put(tab, activeEditor().getText());
      tabs.setTitleAt(tabs.getSelectedIndex(), file.getFileName().toString());
      status.setText(" Script salvo: " + file);
      scheduleSessionSave();
      return true;
    } catch (IOException ex) { showError(ex); return false; }
  }

  private static RSyntaxTextArea editorOf(Component tab) {
    return (RSyntaxTextArea) ((JScrollPane) tab).getViewport().getView();
  }

  private static void updateDirtyTitle(Component tab) {
    int index = tabs.indexOfComponent(tab);
    if (index < 0) return;
    boolean dirty = !Objects.equals(editorOf(tab).getText(), SAVED_TEXT.get(tab));
    String name = OPEN_FILES.containsKey(tab) ? OPEN_FILES.get(tab).getFileName().toString()
        : tabs.getTitleAt(index).replaceFirst("^\\* ", "");
    tabs.setTitleAt(index, (dirty ? "* " : "") + name);
  }

  /** Menu no titulo da aba (pressionar/soltar: compativel com Windows e Linux). */
  private static void installSqlTabContextMenu() {
    tabs.addMouseListener(new MouseAdapter() {
      private void popup(MouseEvent e) {
        if (!e.isPopupTrigger()) return;
        int index = tabs.indexAtLocation(e.getX(), e.getY());
        if (index < 0) return; // Clique fora dos titulos nao abre o menu.
        Component target = tabs.getComponentAt(index);
        tabs.setSelectedIndex(index);
        sqlTabContextMenu(target).show(tabs, e.getX(), e.getY());
      }
      @Override public void mousePressed(MouseEvent e) { popup(e); }
      @Override public void mouseReleased(MouseEvent e) { popup(e); }
    });
  }

  /** Fechamento multiplos sempre passa pela verificacao de alteracoes nao salvas. */
  private static void closeSqlTabsAround(Component anchor, boolean onlyRight) {
    int anchorIndex = tabs.indexOfComponent(anchor);
    if (anchorIndex < 0) return;
    List<Component> candidates = new ArrayList<>();
    for (int i = tabs.getTabCount() - 1; i >= 0; i--) {
      if (i == anchorIndex || onlyRight && i < anchorIndex) continue;
      candidates.add(tabs.getComponentAt(i));
    }
    for (Component candidate : candidates) {
      int index = tabs.indexOfComponent(candidate);
      if (index >= 0 && !closeSqlTab(index, false)) break;
    }
    if (tabs.indexOfComponent(anchor) >= 0) tabs.setSelectedComponent(anchor);
    scheduleSessionSave();
  }

  private static JPopupMenu sqlTabContextMenu(Component target) {
    JPopupMenu menu = new JPopupMenu();
    LinkSqlVisualStyle.popup(menu);
    JMenuItem close = new JMenuItem("Fechar aba");
    close.addActionListener(e -> {
      int index = tabs.indexOfComponent(target);
      if (index >= 0) closeSqlTab(index);
    });
    menu.add(close);

    JMenuItem closeOthers = new JMenuItem("Fechar outras abas");
    closeOthers.setEnabled(tabs.getTabCount() > 1);
    closeOthers.addActionListener(e -> closeSqlTabsAround(target, false));
    menu.add(closeOthers);

    JMenuItem closeRight = new JMenuItem("Fechar abas à direita");
    int index = tabs.indexOfComponent(target);
    closeRight.setEnabled(index >= 0 && index < tabs.getTabCount() - 1);
    closeRight.addActionListener(e -> closeSqlTabsAround(target, true));
    menu.add(closeRight);
    return menu;
  }

  private static boolean closeSqlTab(int index) { return closeSqlTab(index, true); }

  private static boolean closeSqlTab(int index, boolean ensureOneTab) {
    if (index < 0 || index >= tabs.getTabCount()) return true;
    Component tab = tabs.getComponentAt(index);
    if (!Objects.equals(editorOf(tab).getText(), SAVED_TEXT.get(tab))) {
      tabs.setSelectedIndex(index);
      int choice = JOptionPane.showOptionDialog(frame,
          "Existem alterações não salvas em " + tabs.getTitleAt(index) + ".",
          "Fechar aba SQL", JOptionPane.YES_NO_CANCEL_OPTION, JOptionPane.WARNING_MESSAGE,
          null, new String[]{"Salvar", "Descartar", "Cancelar"}, "Salvar");
      if (choice == 0) { if (!saveSqlFile(false)) return false; }
      else if (choice != 1) return false;
    }
    OPEN_FILES.remove(tab);
    SAVED_TEXT.remove(tab);
    TAB_SOURCES.remove(tab);
    tabs.removeTabAt(index);
    if (ensureOneTab && tabs.getTabCount() == 0 && frame.isDisplayable()) addSqlTab();
    scheduleSessionSave();
    return true;
  }

  private static void scheduleSessionSave() {
    if (!restoringSession && !shuttingDown && sessionTimer != null) sessionTimer.restart();
  }

  /** O controlador coleta o estado visual, o SqlSessionStore grava o JSON. */
  private static void saveSession() {
    if (restoringSession || shuttingDown || tabs == null) return;
    try {
      List<SqlSessionStore.Tab> snapshot = new ArrayList<>();
      for (int i = 0; i < tabs.getTabCount(); i++) {
        Component tab = tabs.getComponentAt(i);
        Path file = OPEN_FILES.get(tab);
        snapshot.add(new SqlSessionStore.Tab(
            tabs.getTitleAt(i).replaceFirst("^\\* ", ""),
            editorOf(tab).getText(),
            file == null ? null : file.toString(),
            SAVED_TEXT.getOrDefault(tab, ""),
            tabSource(tab)));
      }
      SqlSessionStore.write(SESSION_FILE,
          new SqlSessionStore.Session(snapshot, tabs.getSelectedIndex()));
    } catch (Exception ex) {
      if (status != null) status.setText(" Aviso: nao foi possivel salvar a sessao SQL: " + ex.getMessage());
    }
  }

  /** Abas legadas sem defaultSource retornam ao modo federado como antes. */
  private static void restoreSession() {
    try {
      Optional<SqlSessionStore.Session> restored = SqlSessionStore.read(SESSION_FILE);
      if (restored.isEmpty()) return;
      SqlSessionStore.Session session = restored.get();
      for (SqlSessionStore.Tab saved : session.tabs()) {
        addSqlTab();
        Component tab = tabs.getSelectedComponent();
        editorOf(tab).setText(saved.sql());
        tabs.setTitleAt(tabs.getSelectedIndex(), saved.title());
        if (saved.file() != null) OPEN_FILES.put(tab, Paths.get(saved.file()));
        SAVED_TEXT.put(tab, saved.savedText());
        TAB_SOURCES.put(tab, saved.defaultSource());
        updateDirtyTitle(tab);
      }
      int selected = session.selected();
      if (tabs.getTabCount() > 0)
        tabs.setSelectedIndex(Math.max(0, Math.min(selected, tabs.getTabCount() - 1)));
      updateTabSourceChoice();
    } catch (Exception ex) {
      JOptionPane.showMessageDialog(frame, "Nao foi possivel restaurar a sessao SQL: " + ex.getMessage(),
          "Restaurar sessao", JOptionPane.WARNING_MESSAGE);
    }
  }

  private static List<String> readHistory() {
    try { return SqlHistoryStore.read(); }
    catch (IOException ex) { LinkSqlLog.error("Ler histórico SQL", ex); return new ArrayList<>(); }
  }

  private static void rememberQuery(String sql) {
    try { SqlHistoryStore.remember(sql); }
    catch (IOException ex) {
      LinkSqlLog.error("Salvar histórico SQL", ex);
      status.setText(" Aviso: não foi possível salvar o histórico SQL.");
    }
  }

  private static void showHistory() {
    String sql = SqlHistoryDialog.choose(frame, readHistory(), Main::showError);
    if (sql == null) return;
    addSqlTab();
    activeEditor().setText(sql);
    activeEditor().requestFocusInWindow();
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
    if (connectionNames().isEmpty()) {
      JOptionPane.showMessageDialog(frame, "Cadastre uma conexão primeiro.");
      return;
    }
    JComboBox<String> serverBox = new JComboBox<>(connectionNames().toArray(new String[0]));
    JTextField columnName = new JTextField(22);
    JPanel panel = new JPanel(new GridLayout(2, 2, 8, 8));
    panel.add(new JLabel("Servidor:")); panel.add(serverBox);
    panel.add(new JLabel("Nome da coluna contém:")); panel.add(columnName);
    if (JOptionPane.showConfirmDialog(frame, panel, "Pesquisar coluna", JOptionPane.OK_CANCEL_OPTION,
        JOptionPane.PLAIN_MESSAGE) != JOptionPane.OK_OPTION) return;
    String term = columnName.getText().trim();
    if (term.isEmpty()) return;
    String server = (String) serverBox.getSelectedItem();
    BasicDataSource ds;
    try { ds = CONNECTIONS.source(server); }
    catch (SQLException ex) { showError(ex); return; }
    status.setText(" Pesquisando coluna '" + term + "' em " + server + "...");
    new SwingWorker<List<String>, Void>() {
      protected List<String> doInBackground() throws Exception {
        List<String> found = new ArrayList<>();
        // JDBC DatabaseMetaData permite pesquisar sem SQL especifico de cada fornecedor.
        JdbcConnectionProfile profile = CONNECTIONS.profile(server);
        try (Connection c = ds.getConnection()) {
          DatabaseMetaData md = c.getMetaData();
          try (ResultSet rs = md.getColumns(profile.catalogFor(c),
              profile.schemaForMetadata(), "%", "%")) {
            while (rs.next() && found.size() < 300) {
              String column = rs.getString("COLUMN_NAME");
              if (column != null && column.toLowerCase(Locale.ROOT).contains(term.toLowerCase(Locale.ROOT))) {
                found.add(rs.getString("TABLE_NAME") + "." + column + "  :  " + rs.getString("TYPE_NAME"));
              }
            }
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
    for (String source : connectionNames()) if (source.equalsIgnoreCase(name)) return source;
    return null;
  }

  private static void fetchTables(String source, Runnable onLoaded) {
    if (source == null || TABLE_CACHE.containsKey(source) || !TABLE_LOADING.add(source)) return;
    BasicDataSource ds = SOURCES.get(source);
    if (ds == null) { TABLE_LOADING.remove(source); return; }
    new SwingWorker<List<String>, Void>() {
      protected List<String> doInBackground() throws Exception {
        List<String> names = new ArrayList<>();
        JdbcConnectionProfile profile = CONNECTIONS.profile(source);
        try (Connection c = ds.getConnection();
             ResultSet rs = c.getMetaData().getTables(profile.catalogFor(c),
                 profile.schemaForMetadata(), "%", new String[]{"TABLE", "VIEW"})) {
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
        JdbcConnectionProfile profile = CONNECTIONS.profile(source);
        try (Connection c = ds.getConnection();
             ResultSet rs = c.getMetaData().getColumns(profile.catalogFor(c),
                 profile.schemaForMetadata(), table, "%")) {
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
    showCompletions(editor, true, false);
  }

  private static void showCompletions(RSyntaxTextArea editor, boolean allowLoad) {
    showCompletions(editor, allowLoad, false);
  }

  private static void showCompletions(RSyntaxTextArea editor, boolean allowLoad, boolean automatic) {
    closeCompletion(editor);
    if (!editor.isShowing()) return;
    // Evita mostrar sugestões em literais SQL, comentários ou identificadores entre aspas.
    if (!SqlCompletionContext.isCodePosition(editor.getText(), editor.getCaretPosition())) return;
    int caret = editor.getCaretPosition();
    String before = editor.getText().substring(0, caret);
    int start = caret;
    while (start > 0 && (Character.isLetterOrDigit(before.charAt(start - 1)) || before.charAt(start - 1) == '_')) start--;
    String prefix = before.substring(start).toLowerCase(Locale.ROOT);
    if (automatic && (prefix.length() < 2 || editor.getSelectedText() != null)) return;
    String left = before.substring(0, start);
    java.util.regex.Matcher qualifierMatcher = java.util.regex.Pattern.compile(
        "(?i)([a-z_][a-z0-9_]*)\\s*\\.\\s*$").matcher(left);
    String qualifier = qualifierMatcher.find() ? qualifierMatcher.group(1) : null;
    String server = qualifier == null ? null : resolveSource(qualifier);
    String contextSql = completionStatement(editor.getText(), caret);
    SqlCompletionContext.Analysis context = SqlCompletionContext.analyze(contextSql, connectionNames());
    SqlCompletionContext.Reference ref = server == null && qualifier != null ? context.reference(qualifier) : null;
    Set<String> candidates = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);

    if (qualifier != null && server != null) {
      List<String> tables = TABLE_CACHE.get(server);
      if (tables == null) {
        if (allowLoad) {
          status.setText(" Carregando tabelas de " + server + " para autocompletar...");
          int originalCaret = caret;
          fetchTables(server, null);
          javax.swing.Timer retry = new javax.swing.Timer(250, null);
          final int[] attempts = {0};
          retry.addActionListener(event -> {
            if (!editor.isShowing() || editor.getCaretPosition() != originalCaret || ++attempts[0] > 24) retry.stop();
            else if (TABLE_CACHE.containsKey(server)) { retry.stop(); showCompletions(editor, false); }
            else if (!TABLE_LOADING.contains(server)) retry.stop();
          });
          retry.start();
        }
        return;
      }
      candidates.addAll(tables);
    } else if (qualifier != null && ref != null) {
      if (ref.table() != null) {
        String key = ref.table().source() + "." + ref.table().table();
        List<String> columns = COLUMN_CACHE.get(key);
        if (columns == null) {
          if (allowLoad) {
            status.setText(" Carregando colunas de " + key + "...");
            int originalCaret = caret;
            fetchColumns(ref.table().source(), ref.table().table(), null);
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
      } else if (ref.cte() != null) {
        candidates.addAll(context.cteColumns(ref.cte()));
        if (candidates.isEmpty()) {
          status.setText(" CTE '" + ref.cte() + "': nao foi possivel inferir as colunas. Use nomes explicitos ou AS na projecao.");
          return;
        }
      }
    } else if (qualifier != null) {
      if (!automatic) status.setText(" Qualificador '" + qualifier + "' nao encontrado em FROM/JOIN desta consulta.");
      return;
    } else {
      boolean expectsTable = SqlAutocompleteHints.expectsTable(left);
      if (!expectsTable) Collections.addAll(candidates, SQL_WORDS);
      candidates.addAll(connectionNames());
      candidates.addAll(context.cteNames());
      for (List<String> tables : TABLE_CACHE.values()) candidates.addAll(tables);
      if (allowLoad) for (String source : connectionNames()) fetchTables(source, null);
      if (!expectsTable) {
        for (SqlCompletionContext.TableRef table : context.tables()) {
          String key = table.source() + "." + table.table();
          List<String> cols = COLUMN_CACHE.get(key);
          if (cols != null) candidates.addAll(cols);
          else if (allowLoad) fetchColumns(table.source(), table.table(), null);
        }
        for (String cte : context.cteNames()) candidates.addAll(context.cteColumns(cte));
      }
    }

    java.util.List<String> matches = new ArrayList<>();
    for (String candidate : candidates) {
      if (candidate.toLowerCase(Locale.ROOT).startsWith(prefix)) matches.add(candidate);
      if (matches.size() >= (automatic ? 35 : 100)) break;
    }
    if (matches.isEmpty()) {
      if (!automatic) status.setText(" Nenhuma sugestão encontrada para '" + prefix + "'.");
      return;
    }
    // Nao distrai enquanto uma palavra ja corresponde exatamente ao unico candidato.
    if (automatic && matches.size() == 1 && matches.get(0).equalsIgnoreCase(prefix)) return;
    final int replaceStart = start;
    JList<String> suggestions = new JList<>(matches.toArray(new String[0]));
    suggestions.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
    suggestions.setSelectedIndex(0);
    suggestions.setVisibleRowCount(Math.min(9, matches.size()));
    suggestions.setFixedCellHeight(25);
    suggestions.setFocusable(false);  // O cursor permanece no SQL.
    JScrollPane scroll = new JScrollPane(suggestions);
    scroll.setBorder(BorderFactory.createEmptyBorder());
    scroll.setPreferredSize(new Dimension(310, Math.min(9, matches.size()) * 25 + 4));
    JPopupMenu menu = new JPopupMenu();
    LinkSqlVisualStyle.popup(menu);
    menu.setLayout(new BorderLayout());
    menu.add(scroll, BorderLayout.CENTER);
    Runnable accept = () -> {
      String candidate = suggestions.getSelectedValue();
      if (candidate == null || editor.getCaretPosition() != caret) {
        closeCompletion(editor);
        return;
      }
      closeCompletion(editor);
      try {
        editor.getDocument().remove(replaceStart, caret - replaceStart);
        editor.getDocument().insertString(replaceStart, candidate, null);
        editor.setCaretPosition(replaceStart + candidate.length());
        SwingUtilities.invokeLater(editor::requestFocusInWindow);
      } catch (javax.swing.text.BadLocationException ex) { showError(ex); }
    };
    suggestions.addMouseListener(new MouseAdapter() {
      public void mouseClicked(MouseEvent e) { if (e.getClickCount() == 2) accept.run(); }
    });
    try {
      Rectangle rect = editor.modelToView2D(caret).getBounds();
      CompletionPopup popup = new CompletionPopup(menu, suggestions,
          editor.getInputMap(JComponent.WHEN_FOCUSED));
      editor.putClientProperty("sqlCompletionPopup", popup);
      popup.install(editor, accept);
      menu.addPopupMenuListener(new javax.swing.event.PopupMenuListener() {
        public void popupMenuWillBecomeVisible(javax.swing.event.PopupMenuEvent e) { }
        public void popupMenuWillBecomeInvisible(javax.swing.event.PopupMenuEvent e) { closeCompletion(editor); }
        public void popupMenuCanceled(javax.swing.event.PopupMenuEvent e) { closeCompletion(editor); }
      });
      menu.show(editor, rect.x, rect.y + rect.height);
      SwingUtilities.invokeLater(editor::requestFocusInWindow);
    } catch (javax.swing.text.BadLocationException ex) { showError(ex); }
  }

  // Mapas de teclas instalados temporariamente; Enter normal continua intacto fora da lista.
  private static final class CompletionPopup {
    final JPopupMenu menu;
    final JList<String> suggestions;
    final InputMap inputMap;
    final Map<KeyStroke, Object> previous = new HashMap<>();
    final String[] actions = {"santy-completion-next", "santy-completion-prev",
        "santy-completion-accept", "santy-completion-close"};
    final KeyStroke[] strokes = {
        KeyStroke.getKeyStroke(KeyEvent.VK_DOWN, 0), KeyStroke.getKeyStroke(KeyEvent.VK_UP, 0),
        KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0)};
    CompletionPopup(JPopupMenu menu, JList<String> suggestions, InputMap inputMap) {
      this.menu = menu; this.suggestions = suggestions; this.inputMap = inputMap;
    }
    void install(RSyntaxTextArea editor, Runnable accept) {
      for (int i = 0; i < strokes.length; i++) {
        previous.put(strokes[i], inputMap.get(strokes[i]));
        inputMap.put(strokes[i], actions[i]);
      }
      editor.getActionMap().put(actions[0], new AbstractAction() {
        public void actionPerformed(ActionEvent e) { move(1); }
      });
      editor.getActionMap().put(actions[1], new AbstractAction() {
        public void actionPerformed(ActionEvent e) { move(-1); }
      });
      editor.getActionMap().put(actions[2], new AbstractAction() {
        public void actionPerformed(ActionEvent e) { accept.run(); }
      });
      editor.getActionMap().put(actions[3], new AbstractAction() {
        public void actionPerformed(ActionEvent e) { closeCompletion(editor); }
      });
    }
    void move(int step) {
      int next = Math.max(0, Math.min(suggestions.getModel().getSize() - 1, suggestions.getSelectedIndex() + step));
      suggestions.setSelectedIndex(next);
      suggestions.ensureIndexIsVisible(next);
    }
    void restore(RSyntaxTextArea editor) {
      for (int i = 0; i < strokes.length; i++) {
        Object prior = previous.get(strokes[i]);
        if (prior == null) inputMap.remove(strokes[i]);
        else inputMap.put(strokes[i], prior);
        editor.getActionMap().remove(actions[i]);
      }
    }
  }

  private static void closeCompletion(RSyntaxTextArea editor) {
    Object value = editor.getClientProperty("sqlCompletionPopup");
    if (!(value instanceof CompletionPopup popup)) return;
    editor.putClientProperty("sqlCompletionPopup", null);
    popup.restore(editor);
    popup.menu.setVisible(false);
  }

  private static JTextArea activeEditor() {
    JScrollPane pane = (JScrollPane) tabs.getSelectedComponent();
    return (JTextArea) pane.getViewport().getView();
  }

  private static void chooseAndLoad() {
    JFileChooser chooser = new JFileChooser();
    chooser.setDialogTitle("Importar perfis de conexão (senhas não são importadas)");
    if (chooser.showOpenDialog(frame) != JFileChooser.APPROVE_OPTION) return;
    try {
      Path source = chooser.getSelectedFile().toPath();
      JsonNode imported = JSON.readTree(Files.readString(source));
      if (!imported.path("connections").isArray())
        throw new IOException("Arquivo de perfis inválido.");
      ObjectNode destination = (ObjectNode) JSON.readTree(Files.readString(configPath));
      ArrayNode list = (ArrayNode) destination.withArray("connections");
      Set<String> known = new HashSet<>();
      for (JsonNode item : list) known.add(item.path("name").asText().toUpperCase(Locale.ROOT));
      int count = 0;
      for (JsonNode item : imported.path("connections")) {
        JdbcConnectionProfile profile = JdbcConnectionProfile.fromJson(item);
        if (known.add(profile.name().toUpperCase(Locale.ROOT))) {
          ObjectNode safe = JSON.createObjectNode();
          safe.put("name", profile.name()); safe.put("type", profile.type().name());
          safe.put("host", profile.host()); safe.put("port", profile.port());
          safe.put("database", profile.database()); safe.put("schema", profile.schema());
          safe.put("user", profile.user()); safe.put("passwordEnv", profile.passwordEnv());
          safe.put("jdbcUrl", profile.jdbcUrl()); safe.put("readOnly", profile.readOnly());
          list.add(safe); count++;
        }
      }
      if (count > 0) {
        Files.createDirectories(configPath.getParent());
        JSON.writerWithDefaultPrettyPrinter().writeValue(configPath.toFile(), destination);
      }
      loadConfiguration();
      JOptionPane.showMessageDialog(frame, count + " conexão(ões) importada(s). Senhas serão solicitadas somente no uso.");
    } catch (Exception ex) { showError(ex); }
  }

  private static void loadConfiguration() {
    try {
      configure(configPath);
      // Importações de perfis também devem aparecer no JDBC 0.5 já instalado.
      LinkSqlPaths.mirrorForJdbc05(configPath);
      rebuildTree();
      updateTabSourceChoice();
      status.setText(" " + connectionNames().size() + " conexões prontas. Selecione uma para explorar.");
    } catch (Exception ex) { showError(ex); }
  }

  private static void rebuildTree() {
    rootNode.removeAllChildren();
    for (String name : connectionNames()) {
      DefaultMutableTreeNode node = new DefaultMutableTreeNode(new SourceNode(name));
      node.add(new DefaultMutableTreeNode("Expandir para carregar schemas..."));
      rootNode.add(node);
    }
    ((DefaultTreeModel) explorer.getModel()).reload();
    explorer.expandRow(0);
  }

  private static void loadNamespaces(DefaultMutableTreeNode node, SourceNode source) {
    source.loaded = true;
    node.removeAllChildren();
    DefaultMutableTreeNode folder = new DefaultMutableTreeNode("Schemas");
    folder.add(new DefaultMutableTreeNode("Carregando bancos e schemas...")); node.add(folder);
    ((DefaultTreeModel) explorer.getModel()).reload(node);
    new SwingWorker<List<DatabaseCatalog.Namespace>, Void>() {
      protected List<DatabaseCatalog.Namespace> doInBackground() throws Exception {
        try (Connection c = CONNECTIONS.source(source.name).getConnection()) {
          return DatabaseCatalog.namespaces(c, CONNECTIONS.profile(source.name));
        }
      }
      protected void done() {
        if (node.getRoot() != rootNode) return;
        folder.removeAllChildren();
        try {
          List<DatabaseCatalog.Namespace> scopes = get();
          if (scopes.isEmpty()) folder.add(new DefaultMutableTreeNode("Schema configurado não encontrado ou indisponível"));
          for (DatabaseCatalog.Namespace scope : scopes) {
            DefaultMutableTreeNode child = new DefaultMutableTreeNode(new NamespaceNode(source.name, scope));
            child.add(new DefaultMutableTreeNode("Expandir para carregar objetos...")); folder.add(child);
          }
          status.setText(" " + scopes.size() + " banco(s)/schema(s) de " + source.name + " encontrados.");
        } catch (Exception ex) {
          source.loaded = false;
          folder.add(new DefaultMutableTreeNode("Erro: recolha e expanda a conexão para tentar novamente"));
          showError(ex);
        }
        ((DefaultTreeModel) explorer.getModel()).reload(node);
        explorer.expandPath(new TreePath(node.getPath()));
        explorer.expandPath(new TreePath(folder.getPath()));
      }
    }.execute();
  }

  private static void loadTables(DefaultMutableTreeNode node, NamespaceNode namespace) {
    namespace.loaded = true;
    node.removeAllChildren(); node.add(new DefaultMutableTreeNode("Carregando objetos..."));
    ((DefaultTreeModel) explorer.getModel()).reload(node);
    new SwingWorker<List<DatabaseCatalog.Entry>, Void>() {
      protected List<DatabaseCatalog.Entry> doInBackground() throws Exception {
        try (Connection c = CONNECTIONS.source(namespace.source).getConnection()) {
          return DatabaseCatalog.tables(c, CONNECTIONS.profile(namespace.source), namespace.scope);
        }
      }
      protected void done() {
        if (node.getRoot() != rootNode) return;
        node.removeAllChildren();
        try {
          List<DatabaseCatalog.Entry> entries = get();
          JdbcConnectionProfile profile = CONNECTIONS.profile(namespace.source);
          DefaultMutableTreeNode tables = new DefaultMutableTreeNode("Tabelas"), views = new DefaultMutableTreeNode("Views");
          List<String> defaultTables = new ArrayList<>();
          for (DatabaseCatalog.Entry entry : entries) {
            boolean defaultScope = namespace.scope.defaultScope()
                && (profile.type() == SqlDatabaseType.MYSQL || profile.type() == SqlDatabaseType.FIREBIRD
                    || DatabaseCatalog.same(entry.schema(), profile.schemaForMetadata()));
            DefaultMutableTreeNode table = new DefaultMutableTreeNode(new TableNode(namespace.source,
                entry.table(), entry.catalog() != null ? entry.catalog() : namespace.scope.catalog(), entry.schema(), defaultScope));
            table.add(new DefaultMutableTreeNode("Expandir para carregar colunas..."));
            (entry.view() ? views : tables).add(table);
            if (defaultScope) defaultTables.add(entry.table());
          }
          if (namespace.scope.defaultScope()) TABLE_CACHE.put(namespace.source, defaultTables.stream().distinct().sorted(String.CASE_INSENSITIVE_ORDER).toList());
          if (tables.getChildCount() == 0) tables.add(new DefaultMutableTreeNode("Nenhuma tabela"));
          if (views.getChildCount() == 0) views.add(new DefaultMutableTreeNode("Nenhuma view"));
          node.add(tables); node.add(views);
          for (boolean functions : new boolean[]{false, true}) {
            DefaultMutableTreeNode routines = new DefaultMutableTreeNode(new RoutineFolder(namespace.source,
                namespace.scope.catalog(), namespace.scope.schema(), functions));
            routines.add(new DefaultMutableTreeNode("Expandir para carregar rotinas...")); node.add(routines);
          }
          status.setText(" " + entries.size() + " tabela(s)/view(s) em " + namespace.scope.name() + ".");
        } catch (Exception ex) {
          namespace.loaded = false;
          node.add(new DefaultMutableTreeNode("Indisponível: tente expandir novamente")); showError(ex);
        }
        ((DefaultTreeModel) explorer.getModel()).reload(node);
        explorer.expandPath(new TreePath(node.getPath()));
      }
    }.execute();
  }

  private static void loadRoutines(DefaultMutableTreeNode node, RoutineFolder folder) {
    folder.loaded = true;
    node.removeAllChildren();
    node.add(new DefaultMutableTreeNode("Carregando..."));
    ((DefaultTreeModel) explorer.getModel()).reload(node);
    new SwingWorker<List<String>, Void>() {
      protected List<String> doInBackground() throws Exception {
        try (Connection c = CONNECTIONS.source(folder.source).getConnection()) {
          JdbcConnectionProfile profile = CONNECTIONS.profile(folder.source);
          return DatabaseCatalog.routines(c, profile, folder.catalog,
              folder.schema, folder.functions);
        }
      }
      protected void done() {
        if (node.getParent() == null) return;
        node.removeAllChildren();
        try {
          List<String> routines = get();
          if (routines.isEmpty()) node.add(new DefaultMutableTreeNode("Nenhum objeto encontrado"));
          else for (String routine : routines) node.add(new DefaultMutableTreeNode(routine));
          status.setText(" " + routines.size() + " " + (folder.functions ? "função(ões)" : "procedimento(s)")
              + " encontrado(s) em " + folder.source + ".");
        } catch (Exception ex) {
          folder.loaded = false;
          node.add(new DefaultMutableTreeNode("Indisponível: tente expandir novamente"));
          status.setText(" Metadados de rotinas indisponíveis: " + ex.getMessage());
        }
        ((DefaultTreeModel) explorer.getModel()).reload(node);
        explorer.expandPath(new TreePath(node.getPath()));
      }
    }.execute();
  }

  private static String qualifiedTable(TableNode table) {
    if (!table.defaultScope) return nativeTableName(table);
    return table.source + ".\"" + table.table.replace("\"", "\"\"") + "\"";
  }

  private static String nativeTableName(TableNode table) {
    SqlDatabaseType type = CONNECTIONS.profile(table.source).type();
    List<String> parts = new ArrayList<>();
    if ((type == SqlDatabaseType.MYSQL || type == SqlDatabaseType.SQLSERVER) && table.catalog != null && !table.catalog.isBlank()) parts.add(table.catalog);
    if (type != SqlDatabaseType.MYSQL && table.schema != null && !table.schema.isBlank()) parts.add(table.schema);
    parts.add(table.table);
    return parts.stream().map(part -> switch (type) {
      case MYSQL -> "`" + part.replace("`", "``") + "`";
      case SQLSERVER -> "[" + part.replace("]", "]]") + "]";
      default -> "\"" + part.replace("\"", "\"\"") + "\"";
    }).collect(java.util.stream.Collectors.joining("."));
  }

  private static void insertTableSql(TableNode table) {
    String selected = tabSource(tabs.getSelectedComponent());
    // Extra namespaces are browsable, but the federated alias still exposes its configured scope.
    // Open a separate native tab instead of silently reusing a different database under that alias.
    if (!table.defaultScope && !selected.equalsIgnoreCase(table.source)) {
      addSqlTab();
      TAB_SOURCES.put(tabs.getSelectedComponent(), table.source); updateTabSourceChoice();
      selected = table.source;
      status.setText(" Aba nativa de " + table.source + " criada para consultar " + nativeTableName(table) + ".");
    }
    JTextArea editor = activeEditor();
    String sqlName = selected.equalsIgnoreCase(table.source) ? nativeTableName(table) : qualifiedTable(table);
    editor.insert("SELECT * FROM " + sqlName + " FETCH NEXT 100 ROWS ONLY;\n", editor.getCaretPosition());
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
        try (Connection c = CONNECTIONS.source(table.source).getConnection();
             ResultSet rs = c.getMetaData().getColumns(table.catalog,
                 DatabaseCatalog.literalPattern(c.getMetaData(), table.schema),
                 DatabaseCatalog.literalPattern(c.getMetaData(), table.table), "%")) {
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
          if (table.defaultScope) COLUMN_CACHE.put(table.source + "." + table.table, columns.stream().map(c -> c.split("  :  ")[0]).toList());
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

  // Cada resultado usa uma grade independente; novas execuções reutilizam as abas.
  private static JTable newResultTable(DefaultTableModel model) {
    JTable table = new JTable(model);
    table.setAutoResizeMode(JTable.AUTO_RESIZE_OFF);
    table.setAutoCreateRowSorter(true);
    table.setRowHeight(28);
    table.setShowHorizontalLines(true);
    table.setShowVerticalLines(false);
    table.setCellSelectionEnabled(true);
    table.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 13));
    table.getTableHeader().setFont(new Font(Font.SANS_SERIF, Font.BOLD, 12));
    table.getTableHeader().setReorderingAllowed(true);
    LinkSqlGridHeaders.alignLeft(table);
    LinkSqlVisualStyle.resultsTable(table);
    TableRowSorter<DefaultTableModel> sorter = new TableRowSorter<>(model);
    // Mantem ordenacao numerica para colunas com valores Number.
    for (int c = 0; c < model.getColumnCount(); c++) {
      final int column = c;
      sorter.setComparator(c, (a, b) -> {
        if (a == null) return b == null ? 0 : -1;
        if (b == null) return 1;
        if (a instanceof Number && b instanceof Number)
          return new java.math.BigDecimal(a.toString()).compareTo(new java.math.BigDecimal(b.toString()));
        return String.CASE_INSENSITIVE_ORDER.compare(a.toString(), b.toString());
      });
    }
    table.setRowSorter(sorter);
    table.addMouseListener(new MouseAdapter() {
      public void mouseClicked(MouseEvent e) {
        if (e.getClickCount() == 2 && SwingUtilities.isLeftMouseButton(e)) {
          int row = table.rowAtPoint(e.getPoint());
          int col = table.columnAtPoint(e.getPoint());
          if (row >= 0 && col >= 0) showFullCell(table, row, col);
        }
      }
    });
    table.getInputMap(JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT).put(
        KeyStroke.getKeyStroke(KeyEvent.VK_C, InputEvent.CTRL_DOWN_MASK), "copyResultCells");
    table.getInputMap(JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT).put(
        KeyStroke.getKeyStroke(KeyEvent.VK_C, InputEvent.CTRL_DOWN_MASK | InputEvent.SHIFT_DOWN_MASK), "copyResultHeader");
    table.getActionMap().put("copyResultHeader", new AbstractAction() {
      public void actionPerformed(ActionEvent e) { copySelectedResults(table, true); }
    });
    table.getActionMap().put("copyResultCells", new AbstractAction() {
      public void actionPerformed(ActionEvent e) { copySelectedResults(table); }
    });
    JPopupMenu copyMenu = new JPopupMenu();
    LinkSqlVisualStyle.popup(copyMenu);
    JMenuItem copyItem = new JMenuItem("Copiar selecao (Ctrl+C)");
    copyItem.addActionListener(e -> copySelectedResults(table));
    copyMenu.add(copyItem);
    JMenuItem copyWithHeader = new JMenuItem("Copiar com cabecalho (Ctrl+Shift+C)");
    copyWithHeader.addActionListener(e -> copySelectedResults(table, true));
    copyMenu.add(copyWithHeader);
    table.setComponentPopupMenu(copyMenu);
    return table;
  }

  private static void copySelectedResults(JTable table) { copySelectedResults(table, false); }

  private static void copySelectedResults(JTable table, boolean includeHeader) {
    int[] rows = table.getSelectedRows();
    int[] cols = table.getSelectedColumns();
    if (rows.length == 0 || cols.length == 0) {
      status.setText(" Selecione as celulas ou linhas que deseja copiar.");
      return;
    }
    StringBuilder text = new StringBuilder();
    if (includeHeader) {
      for (int c = 0; c < cols.length; c++) {
        if (c > 0) text.append('\t');
        text.append(table.getColumnName(cols[c]));
      }
      text.append('\n');
    }
    for (int r = 0; r < rows.length; r++) {
      if (r > 0) text.append('\n');
      for (int c = 0; c < cols.length; c++) {
        if (c > 0) text.append('\t');
        Object value = table.getValueAt(rows[r], cols[c]);
        if (value != null) text.append(value.toString().replace('\t', ' ').replace('\n', ' '));
      }
    }
    Toolkit.getDefaultToolkit().getSystemClipboard().setContents(
        new java.awt.datatransfer.StringSelection(text.toString()), null);
    status.setText(" Copiados " + rows.length + " linha(s) e " + cols.length + " coluna(s).");
  }

  private static void showFullCell(JTable table, int row, int col) {
    Object value = table.getValueAt(row, col);
    JTextArea content = new JTextArea(value == null ? "" : value.toString(), 16, 65);
    content.setEditable(false);
    content.setLineWrap(true);
    content.setWrapStyleWord(true);
    content.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 13));
    JScrollPane pane = new JScrollPane(content);
    pane.setPreferredSize(new Dimension(720, 380));
    JButton copy = new JButton("Copiar valor");
    copy.addActionListener(e -> {
      Toolkit.getDefaultToolkit().getSystemClipboard().setContents(
          new java.awt.datatransfer.StringSelection(content.getText()), null);
      status.setText(" Valor da celula copiado.");
    });
    JPanel panel = new JPanel(new BorderLayout(0, 8));
    panel.add(pane, BorderLayout.CENTER);
    panel.add(copy, BorderLayout.SOUTH);
    JOptionPane.showMessageDialog(frame, panel,
        "Conteudo completo - " + table.getColumnName(col) + " (linha " + (row + 1) + ")",
        JOptionPane.PLAIN_MESSAGE);
  }

  private static void applyResultFilter() {
    if (changingResultTab || results == null || resultFilter == null || resultFilterColumn == null) return;
    @SuppressWarnings("unchecked")
    TableRowSorter<DefaultTableModel> sorter = (TableRowSorter<DefaultTableModel>) results.getRowSorter();
    String value = resultFilter.getText().trim();
    int selectedColumn = resultFilterColumn.getSelectedIndex() - 1;
    // JComboBox usa ordem do modelo; conversao garante compatibilidade com colunas arrastadas.
    String search = value.toLowerCase(Locale.ROOT);
    sorter.setRowFilter(value.isEmpty() ? null : new RowFilter<DefaultTableModel, Integer>() {
      public boolean include(Entry<? extends DefaultTableModel, ? extends Integer> entry) {
        if (selectedColumn >= 0) return entry.getStringValue(selectedColumn)
            .toLowerCase(Locale.ROOT).contains(search);
        for (int c = 0; c < entry.getValueCount(); c++) {
          if (entry.getStringValue(c).toLowerCase(Locale.ROOT).contains(search)) return true;
        }
        return false;
      }
    });
    Component tab = resultTabs.getSelectedComponent();
    if (tab instanceof JComponent component) {
      component.putClientProperty("resultFilter", value);
      component.putClientProperty("resultFilterColumn", resultFilterColumn.getSelectedIndex());
    }
    status.setText(" Filtro local: " + results.getRowCount() + " de " + results.getModel().getRowCount() + " linhas exibidas.");
  }

  private static Connection openQueryConnection(String sql) throws SQLException {
    return CONNECTIONS.openFederatedQueryConnection(sql);
  }

  private static void cancelRunningQuery() {
    SwingWorker<?, ?> worker = currentQuery;
    if (worker == null || worker.isDone()) return;
    CANCEL_REQUESTED.set(true);
    cancelQueryButton.setEnabled(false);
    status.setText(" Cancelamento solicitado. Liberando editor para nova consulta...");
    Statement stmt = RUNNING_STATEMENT.get();
    Connection conn = RUNNING_CONNECTION.get();
    // O worker e cancelado para liberar a interface. Sua conexao e exclusiva:
    // uma nova consulta nao reutiliza a conexao que ainda esta encerrando.
    worker.cancel(true);
    Thread canceller = new Thread(() -> {
      if (stmt != null) {
        try { stmt.cancel(); } catch (SQLException ignored) {}
      }
      if (conn != null) {
        try { conn.close(); } catch (SQLException ignored) {}
      }
    }, "linksql-cancel-query");
    canceller.setDaemon(true);
    canceller.start();
  }

  private static void selectResultTab() {
    changingResultTab = true;
    try {
      Component selected = resultTabs.getSelectedComponent();
      if (selected instanceof JScrollPane scroll && scroll.getViewport().getView() instanceof JTable table) {
        results = table;
        Object summary = scroll.getClientProperty("resultSummary");
        resultSummary.setText(summary == null ? "  RESULTADOS" : summary.toString());
        if (resultFilterColumn != null) {
          resultFilterColumn.removeAllItems();
          resultFilterColumn.addItem("Todas as colunas");
          for (int c = 0; c < table.getModel().getColumnCount(); c++)
            resultFilterColumn.addItem(table.getModel().getColumnName(c));
          Object savedColumn = scroll.getClientProperty("resultFilterColumn");
          int index = savedColumn instanceof Integer ? (Integer) savedColumn : 0;
          resultFilterColumn.setSelectedIndex(Math.max(0, Math.min(index, resultFilterColumn.getItemCount() - 1)));
          resultFilterColumn.setEnabled(true);
        }
        if (resultFilter != null) {
          resultFilter.setEnabled(true);
          resultFilter.setText(String.valueOf(scroll.getClientProperty("resultFilter") == null ? "" : scroll.getClientProperty("resultFilter")));
        }
      } else {
        results = null;
        if (resultFilter != null) { resultFilter.setText(""); resultFilter.setEnabled(false); }
        if (resultFilterColumn != null) {
          resultFilterColumn.removeAllItems();
          resultFilterColumn.addItem("Todas as colunas");
          resultFilterColumn.setEnabled(false);
        }
        resultSummary.setText("  RESULTADOS");
      }
    } finally { changingResultTab = false; }
    if (results != null) applyResultFilter();
    updatePageControls();
  }

  private static void clearSelectedResult() {
    int index = resultTabs.getSelectedIndex();
    if (index < 0) { status.setText(" Nenhum resultado para fechar."); return; }
    resultTabs.removeTabAt(index);
    selectResultTab();
    status.setText(" Aba de resultados fechada.");
  }

  // Usa o padrao brasileiro para quantidades na interface: 2.000, 50.000, 100.000.
  private static String formatRowCount(long value) {
    return String.format(DISPLAY_LOCALE, "%,d", value);
  }

  private static void addResultTab(DefaultTableModel model, double elapsed) {
    JTable table = newResultTable(model);
    JScrollPane scroll = new JScrollPane(table);
    String summary = String.format(Locale.ROOT, "  RESULTADOS  ·  %s linhas  ·  %.2f s", formatRowCount(model.getRowCount()), elapsed);
    scroll.putClientProperty("resultSummary", summary);
    String title = "Resultado " + nextResultNumber++ + " (" + model.getRowCount() + ")";
    resultTabs.addTab(title, scroll);
    int index = resultTabs.indexOfComponent(scroll);
    resultTabs.setTabComponentAt(index, resultTabHeader(title, scroll));
    resultTabs.setSelectedComponent(scroll);
    scroll.putClientProperty("resultPage", 0);
    scroll.putClientProperty("resultHasMore", false);
    configureColumns(table);
    updatePageControls();
  }

  // A aba de resultado representa a posicao do SELECT na execucao, nao o texto SQL.
  private static void showOrUpdateResultTab(DefaultTableModel model, double elapsed, Component sqlTab,
                                             String sql, int slot, int pageSize, String tabAlias) {
    JScrollPane scroll;
    if (slot < resultTabs.getTabCount() && resultTabs.getComponentAt(slot) instanceof JScrollPane existing) {
      scroll = existing;
    } else {
      JTable table = newResultTable(model);
      scroll = new JScrollPane(table);
      resultTabs.addTab("Resultado " + (slot + 1), scroll);
    }
    JTable table = newResultTable(model);
    scroll.setViewportView(table);
    scroll.putClientProperty("resultSummary", String.format(Locale.ROOT,
        "  RESULTADOS  ·  %s linhas  ·  %.2f s", formatRowCount(model.getRowCount()), elapsed));
    scroll.putClientProperty("resultFilter", "");
    scroll.putClientProperty("resultFilterColumn", 0);
    scroll.putClientProperty("sqlSourceTab", sqlTab);
    scroll.putClientProperty("sqlDefaultSource", tabAlias);
    scroll.putClientProperty("sqlQueryKey", sql.trim());
    scroll.putClientProperty("resultPage", 0);
    scroll.putClientProperty("pageSize", pageSize);
    SqlWindow window = sqlWindow(sql);
    scroll.putClientProperty("resultHasMore", pageSize > 0 && model.getRowCount() == pageSize && isPageableSql(sql)
        && (window.maxRows() == null || window.maxRows() > pageSize));
    if (pageSize == 0) scroll.putClientProperty("totalRows", (long) model.getRowCount());
    scroll.putClientProperty("pageCache", null);
    pageCache(scroll).put(0, model);
    requestCount(scroll, sql);
    String title = "Resultado " + (slot + 1) + " (" + model.getRowCount() + ")";
    int idx = resultTabs.indexOfComponent(scroll);
    resultTabs.setTitleAt(idx, title);
    resultTabs.setTabComponentAt(idx, resultTabHeader(title, scroll));
    resultTabs.setSelectedIndex(idx);
    configureColumns(table);
    selectResultTab();
  }

  private static void trimResultTabs(int count) {
    while (resultTabs.getTabCount() > count) resultTabs.removeTabAt(resultTabs.getTabCount() - 1);
    selectResultTab();
  }

  // Troca o tamanho da página preservando a preferência, com proteção para "Todas".
  private static void changeRowsPerPage() {
    if (updatingPageSizeBox || pageSizeBox == null) return;
    String label = String.valueOf(pageSizeBox.getSelectedItem()).trim();
    int chosen;
    if (label.toLowerCase(Locale.ROOT).startsWith("todas")) chosen = 0;
    else {
      String text = label.replace(".", "");
      if (!text.matches("[0-9]+")) { updatePageControls(); return; }
      try { chosen = Integer.parseInt(text); }
      catch (NumberFormatException ex) { updatePageControls(); return; }
    }
    if (chosen != 0 && (chosen < 100 || chosen > MAX_PAGE_SIZE)) {
      status.setText(" Escolha de 100 a 100.000 linhas, ou Todas (1 página).");
      updatePageControls();
      return;
    }
    Component selected = resultTabs.getSelectedComponent();
    JScrollPane scroll = selected instanceof JScrollPane p ? p : null;
    if (currentQuery != null && !currentQuery.isDone()) {
      status.setText(" Aguarde o fim da consulta antes de mudar o tamanho da página.");
      updatePageControls();
      return;
    }
    if (chosen == 0 && chosen != preferredPageSize) {
      Object totalObj = scroll == null ? null : scroll.getClientProperty("totalRows");
      String totalText = totalObj instanceof Long t
          ? String.format(new Locale("pt", "BR"), "%,d", t) : "todos os registros da consulta";
      String warning = "A opção Todas (1 página) carregará " + totalText + " na memória.\n"
          + "Em tabelas grandes, o uso de RAM pode ser alto e o carregamento pode demorar.\n"
          + "É possível cancelar a consulta durante o carregamento. Deseja continuar?";
      if (JOptionPane.showConfirmDialog(frame, warning, "Carregar todos os registros",
          JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE) != JOptionPane.YES_OPTION) {
        updatePageControls();
        return;
      }
    }
    preferredPageSize = chosen;
    LinkSqlSettings.setPageSize(chosen);
    if (scroll != null && resultTabs.indexOfComponent(scroll) >= 0 && resultPageSize(scroll) != chosen) {
      scroll.putClientProperty("pageSize", chosen);
      // O SQL nao mudou: preserva a contagem em andamento ao alterar o tamanho de pagina.
      scroll.putClientProperty("pageCache", null);
      scroll.putClientProperty("resultHasMore", false);
      // A contagem conhecida continua válida para o mesmo SQL, salvo quando
      // um novo resultado a substitui durante a recarga.
      loadResultPage(scroll, 0);
    } else status.setText(" Linhas por página: " + formatPageSize(chosen) + ".");
    updatePageControls();
  }

  // A pagina reflete o limite do SQL e o numero de linhas escolhido.
  private static void updatePageControls() {
    if (previousPageButton == null || nextPageButton == null || pageLabel == null) return;
    Component selected = resultTabs == null ? null : resultTabs.getSelectedComponent();
    JScrollPane scroll = selected instanceof JScrollPane p ? p : null;
    int size = scroll == null ? preferredPageSize : resultPageSize(scroll);
    if (pageSizeBox != null) {
      updatingPageSizeBox = true;
      try { pageSizeBox.setSelectedItem(formatPageSize(size)); }
      finally { updatingPageSizeBox = false; }
    }
    if (scroll == null) {
      previousPageButton.setEnabled(false);
      nextPageButton.setEnabled(false);
      pageLabel.setText("Nenhum resultado");
      return;
    }
    Object value = scroll.getClientProperty("resultPage");
    int page = value instanceof Integer ? (Integer) value : 0;
    Object sqlValue = scroll.getClientProperty("sqlQueryKey");
    boolean pagingSupported = size > 0 && sqlValue instanceof String sql && isPageableSql(sql);
    boolean busy = currentQuery != null && !currentQuery.isDone();
    previousPageButton.setEnabled(pagingSupported && page > 0 && !busy);
    nextPageButton.setEnabled(pagingSupported && Boolean.TRUE.equals(scroll.getClientProperty("resultHasMore")) && !busy);
    Object totalObj = scroll.getClientProperty("totalRows");
    if (size == 0) {
      int loaded = ((JTable) scroll.getViewport().getView()).getModel().getRowCount();
      pageLabel.setText(String.format(new Locale("pt", "BR"),
          "Página 1 de 1  ·  %,d registros", loaded));
      previousPageButton.setEnabled(false);
      nextPageButton.setEnabled(false);
      return;
    }
    if (totalObj instanceof Long total) {
      long pages = Math.max(1, total == 0 ? 1 : 1 + (total - 1) / size);
      long first = total == 0 ? 0 : (long) page * size + 1;
      long last = Math.min(total, (long) (page + 1) * size);
      pageLabel.setText(String.format(new Locale("pt", "BR"),
          "Página %d de %,d  ·  %,d–%,d de %,d registros", page + 1, pages, first, last, total));
      nextPageButton.setEnabled(pagingSupported && page + 1 < pages && !busy);
    } else if (sqlValue instanceof String sql && sqlWindow(sql).maxRows() != null) {
      long max = sqlWindow(sql).maxRows();
      long maxPages = Math.max(1L, max == 0 ? 1L : 1L + (max - 1L) / size);
      pageLabel.setText("Página " + (page + 1) + " de até " + maxPages +
          "  ·  limite SQL: " + String.format(new Locale("pt", "BR"), "%,d", max) + " registros" +
          (Boolean.TRUE.equals(scroll.getClientProperty("countPending")) ? " (contando...)" : ""));
      if (page + 1 >= maxPages) nextPageButton.setEnabled(false);
    } else {
      pageLabel.setText("Página " + (page + 1) + " · " +
          (Boolean.TRUE.equals(scroll.getClientProperty("countPending")) ? "calculando total..." : (Boolean.TRUE.equals(scroll.getClientProperty("countUnavailable")) ? "contagem indisponível" : "total não disponível")));
    }
  }

  private static void changeResultPage(int direction) {
    Component selected = resultTabs.getSelectedComponent();
    if (!(selected instanceof JScrollPane scroll)) return;
    if (currentQuery != null && !currentQuery.isDone()) return;
    Object original = scroll.getClientProperty("sqlQueryKey");
    if (!(original instanceof String sql) || sql.isBlank() || !isPageableSql(sql)) return;
    int current = scroll.getClientProperty("resultPage") instanceof Integer
        ? (Integer) scroll.getClientProperty("resultPage") : 0;
    int target = current + direction;
    if (target < 0 || (direction > 0 && !Boolean.TRUE.equals(scroll.getClientProperty("resultHasMore")))) return;
    loadResultPage(scroll, target);
  }

  private static void loadResultPage(JScrollPane scroll, int target) {
    if (currentQuery != null && !currentQuery.isDone()) return;
    if (resultTabs.indexOfComponent(scroll) < 0) return;
    Object original = scroll.getClientProperty("sqlQueryKey");
    if (!(original instanceof String sql) || sql.isBlank()) return;
    final int size = resultPageSize(scroll);
    // Todas carrega o SQL completo, mesmo quando não aceita paginação por OFFSET.
    if (size > 0 && !isPageableSql(sql)) return;
    int current = scroll.getClientProperty("resultPage") instanceof Integer
        ? (Integer) scroll.getClientProperty("resultPage") : 0;
    SqlWindow window = sqlWindow(sql);
    try { FederatedQuerySupport.validate(sql, CONNECTIONS.allProfiles()); }
    catch (SQLException ex) { showError(ex); return; }
    if (target < 0 || size == 0 && target != 0
        || size > 0 && window.maxRows() != null && (long) target * size >= window.maxRows()) return;
    DefaultTableModel cached = pageCache(scroll).get(target);
    if (cached != null) {
      displayPage(scroll, cached, target, 0, true);
      status.setText(" Página " + (target + 1) + " carregada.");
      return;
    }
    // A consulta original e encapsulada para preservar WHERE, ORDER BY e limites declarados.
    DirectSql directPage = directSql(sql);
    String tabAlias = (String) scroll.getClientProperty("sqlDefaultSource");
    NativeSqlRouter.Route nativePage = nativeRoute(sql, tabAlias);
    NativeJdbcPaging.Window nativeWindow;
    try { nativeWindow = nativePage == null ? null : NativeJdbcPaging.window(sql, target, size); }
    catch (SQLException ex) { showError(ex); return; }
    String pushedSql;
    try { pushedSql = NativeServerPaging.sql(nativePage, nativeWindow); }
    catch (SQLException ex) { showError(ex); return; }
    String pagedSql = pushedSql != null ? pushedSql : nativePage != null ? nativePage.sql()
        : directPage != null ? directPageSql(directPage, window, target, size)
        : size == 0 ? sql
        : "SELECT * FROM (" + sql + ") AS LINKSQL_PAGE "
            + "OFFSET " + ((long) target * size) + " ROWS FETCH NEXT " + size + " ROWS ONLY";
    execute.setEnabled(false);
    executeScriptButton.setEnabled(false);
    cancelQueryButton.setEnabled(true);
    CANCEL_REQUESTED.set(false);
    updatePageControls();
    status.setText(" Carregando página " + (target + 1) + "...");
    long started = System.nanoTime();
    currentQuery = new SwingWorker<DefaultTableModel, Void>() {
      protected DefaultTableModel doInBackground() throws Exception {
        Connection conn = null;
        Statement stmt = null;
        try {
          conn = nativePage != null ? CONNECTIONS.source(nativePage.source()).getConnection()
              : directPage != null ? CONNECTIONS.source(directPage.source()).getConnection() : openQueryConnection(sql);
          stmt = conn.createStatement();
          RUNNING_CONNECTION.set(conn);
          RUNNING_STATEMENT.set(stmt);
          if (pushedSql != null) stmt.setMaxRows(0);
          else if (nativeWindow != null) NativeJdbcPaging.configure(stmt, nativeWindow);
          else stmt.setMaxRows(size);
          stmt.setQueryTimeout(120);
          if (nativeWindow == null) configureFetch(stmt, sql, size);
          try (ResultSet rs = stmt.executeQuery(pagedSql)) {
            return pushedSql != null ? SqlResultModels.readResultModel(rs, nativeWindow.amount(), this::isCancelled)
                : nativeWindow == null ? SqlResultModels.readResultModel(rs, size, this::isCancelled)
                : NativeJdbcPaging.read(rs, nativeWindow, this::isCancelled);
          }
        } catch (SQLException ex) {
          throw nativePage == null && directPage == null
              ? FederatedQuerySupport.explain(ex) : ex;
        } finally {
          RUNNING_STATEMENT.compareAndSet(stmt, null);
          RUNNING_CONNECTION.compareAndSet(conn, null);
          if (stmt != null) try { stmt.close(); } catch (SQLException ignored) {}
          if (conn != null) try { conn.close(); } catch (SQLException ignored) {}
        }
      }
      protected void done() {
        if (currentQuery != this) return;
        execute.setEnabled(true);
        executeScriptButton.setEnabled(true);
        cancelQueryButton.setEnabled(false);
        try {
          DefaultTableModel model = get();
          if (resultTabs.indexOfComponent(scroll) < 0) return;
          if (target > 0 && model.getRowCount() == 0) {
            scroll.putClientProperty("resultHasMore", false);
            status.setText(" Não há mais registros após a página " + (current + 1) + ".");
            return;
          }
          pageCache(scroll).put(target, model);
          double seconds = (System.nanoTime() - started) / 1_000_000_000.0;
          displayPage(scroll, model, target, seconds, false);
          status.setText(" Página " + (target + 1) + " carregada.");
        } catch (Exception ex) {
          if (CANCEL_REQUESTED.get()) status.setText(" Paginação cancelada.");
          else { status.setText(" Erro ao carregar página. Resultado anterior preservado."); showError(ex); }
        } finally { updatePageControls(); }
      }
    };
    currentQuery.execute();
    updatePageControls();
  }

  private static void displayPage(JScrollPane scroll, DefaultTableModel model,
                                  int page, double seconds, boolean cached) {
    JTable table = newResultTable(model);
    scroll.setViewportView(table);
    scroll.putClientProperty("resultPage", page);
    Object totalObj = scroll.getClientProperty("totalRows");
    int size = resultPageSize(scroll);
    Object sqlValue = scroll.getClientProperty("sqlQueryKey");
    SqlWindow window = sqlValue instanceof String sql ? sqlWindow(sql) : new SqlWindow("", null, 0);
    boolean hasMore = size > 0 && (totalObj instanceof Long total
        ? (long) (page + 1) * size < total : model.getRowCount() == size
            && (window.maxRows() == null || (long) (page + 1) * size < window.maxRows()));
    scroll.putClientProperty("resultHasMore", hasMore);
    if (size == 0) scroll.putClientProperty("totalRows", (long) model.getRowCount());
    else if (totalObj == null) {
      // Quando a pagina nao ficou cheia, nao existem registros posteriores:
      // o total pode ser inferido sem uma consulta COUNT(*) adicional.
      Long inferred = SqlPageTotals.whenExhausted(page, size, model.getRowCount());
      if (inferred != null) scroll.putClientProperty("totalRows", inferred);
    }
    scroll.putClientProperty("resultFilter", "");
    scroll.putClientProperty("resultFilterColumn", 0);
    scroll.putClientProperty("resultSummary", cached
        ? String.format(Locale.ROOT, "  RESULTADOS · Página %d · %s linhas", page + 1,
            formatRowCount(model.getRowCount()))
        : String.format(Locale.ROOT, "  RESULTADOS · Página %d · %s linhas · %.2f s", page + 1,
            formatRowCount(model.getRowCount()), seconds));
    int idx = resultTabs.indexOfComponent(scroll);
    if (idx < 0) return;
    String title = "Resultado " + (idx + 1) + " (" + model.getRowCount() + ")";
    resultTabs.setTitleAt(idx, title);
    resultTabs.setTabComponentAt(idx, resultTabHeader(title, scroll));
    configureColumns(table);
    if (resultTabs.getSelectedComponent() == scroll) selectResultTab();
    updatePageControls();
  }

  private static Component resultTabHeader(String title, Component content) {
    JPanel header = new JPanel(new FlowLayout(FlowLayout.LEFT, 3, 0));
    header.setOpaque(false);
    JLabel label = new JLabel(title);
    JButton close = new JButton("×");
    close.setToolTipText("Fechar este resultado");
    close.setMargin(new Insets(0, 4, 0, 4));
    close.setFocusable(false);
    close.addActionListener(e -> {
      int index = resultTabs.indexOfComponent(content);
      if (index >= 0) resultTabs.removeTabAt(index);
      selectResultTab();
    });
    header.add(label);
    header.add(close);
    return header;
  }

  // Formatacao conservadora: apenas separa clausulas no nivel externo.
  // Literais, identificadores entre aspas e comentarios nao sao alterados.
  private static void formatActiveSql() {
    RSyntaxTextArea editor = (RSyntaxTextArea) activeEditor();
    String original = editor.getText();
    if (original.isBlank()) return;
    String formatted = SqlScriptText.formatClauses(original);
    if (formatted.equals(original)) { status.setText(" SQL ja esta formatado."); return; }
    int caret = editor.getCaretPosition();
    editor.setText(formatted);
    editor.setCaretPosition(Math.min(caret, formatted.length()));
    editor.requestFocusInWindow();
    status.setText(" SQL formatado. Atalho: Ctrl+Shift+F.");
  }

  private static void runQuery() {
    JTextArea queryEditor = activeEditor();
    if (federation == null) { status.setText(" Cadastre uma conexão primeiro."); queryEditor.requestFocusInWindow(); return; }
    if (currentQuery != null && !currentQuery.isDone()) { queryEditor.requestFocusInWindow(); return; }
    String sql = queryEditor.getSelectedText();
    if (sql == null || sql.isBlank()) sql = statementAtCaret(queryEditor);
    sql = sql.trim();
    while (sql.endsWith(";")) sql = sql.substring(0, sql.length() - 1).trim();
    if (sql.isBlank()) { status.setText(" Posicione o cursor em uma consulta ou selecione o SQL."); queryEditor.requestFocusInWindow(); return; }
    final String query = sql;
    final Component sqlTab = tabs.getSelectedComponent();
    final String tabAlias = tabSource(sqlTab);
    if (SqlTabRouting.missingAlias(tabAlias, CONNECTIONS.allProfiles())) {
      showError(new SQLException("Conexão padrão da aba não cadastrada: " + tabAlias
          + ". Escolha outra conexão ou o modo Federado."));
      return;
    }
    try { FederatedQuerySupport.validate(query, CONNECTIONS.allProfiles()); }
    catch (SQLException ex) { showError(ex); return; }
    final NativeSqlRouter.Route nativeQuery = nativeRoute(query, tabAlias);
    final NativeJdbcPaging.Window nativeWindow;
    try { nativeWindow = nativeQuery == null ? null : NativeJdbcPaging.window(query, 0, preferredPageSize); }
    catch (SQLException ex) { showError(ex); return; }
    final String pushedSql;
    try { pushedSql = NativeServerPaging.sql(nativeQuery, nativeWindow); }
    catch (SQLException ex) { showError(ex); return; }
    final int pageSize = preferredPageSize;
    rememberQuery(query);
    execute.setEnabled(false);
    executeScriptButton.setEnabled(false);
    cancelQueryButton.setEnabled(true);
    CANCEL_REQUESTED.set(false);
    final long started = System.nanoTime();
    status.setText(" Executando consulta...");
    final long[] phases = new long[3]; // conexão, execução SQL, leitura
    currentQuery = new SwingWorker<DefaultTableModel, Void>() {
      protected DefaultTableModel doInBackground() throws Exception {
        Connection queryConnection = null;
        Statement stmt = null;
        try {
          queryConnection = queryConnectionFor(query, tabAlias);
          stmt = queryConnection.createStatement();
          phases[0] = System.nanoTime() - started;
          if (currentQuery == this) {
            RUNNING_CONNECTION.set(queryConnection);
            RUNNING_STATEMENT.set(stmt);
          }
          if (isCancelled()) throw new java.util.concurrent.CancellationException("Consulta cancelada");
          if (pushedSql != null) stmt.setMaxRows(0);
          else if (nativeWindow != null) NativeJdbcPaging.configure(stmt, nativeWindow);
          else stmt.setMaxRows(pageSize);
          stmt.setQueryTimeout(120);
          if (nativeWindow == null) configureFetch(stmt, query, pageSize);
          long sqlStarted = System.nanoTime();
          try (ResultSet rs = stmt.executeQuery(pushedSql != null ? pushedSql : firstPageSql(query, pageSize, tabAlias))) {
            phases[1] = System.nanoTime() - sqlStarted;
            long readStarted = System.nanoTime();
            DefaultTableModel model = pushedSql != null
                ? SqlResultModels.readResultModel(rs, nativeWindow.amount(), this::isCancelled)
                : nativeWindow == null ? SqlResultModels.readResultModel(rs, pageSize, this::isCancelled)
                : NativeJdbcPaging.read(rs, nativeWindow, this::isCancelled);
            phases[2] = System.nanoTime() - readStarted;
            return model;
          }
        } catch (SQLException ex) {
          throw nativeQuery == null && directSql(query) == null
              ? FederatedQuerySupport.explain(ex) : ex;
        } finally {
          RUNNING_STATEMENT.compareAndSet(stmt, null);
          RUNNING_CONNECTION.compareAndSet(queryConnection, null);
          if (stmt != null) {
            try { stmt.close(); } catch (SQLException ignored) {}
          }
          if (queryConnection != null) {
            try { queryConnection.close(); } catch (SQLException ignored) {}
          }
        }
      }
      protected void done() {
        if (currentQuery != this) return;
        execute.setEnabled(true);
        executeScriptButton.setEnabled(true);
        cancelQueryButton.setEnabled(false);
        try {
          DefaultTableModel model = get();
          double elapsed = (System.nanoTime() - started) / 1_000_000_000.0;
          showOrUpdateResultTab(model, elapsed, sqlTab, query, 0, pageSize, tabAlias);
          trimResultTabs(1);
          String details = String.format(Locale.ROOT,
              "Conexão %.2f s | SQL %.2f s | Leitura %.2f s",
              phases[0] / 1e9, phases[1] / 1e9, phases[2] / 1e9);
          status.setText(" Consulta: " + formatRowCount(model.getRowCount()) + " linhas em "
              + String.format(Locale.ROOT, "%.2f s", elapsed) + " | " + details
              + " | " + executionLabel(query, tabAlias));
          status.setToolTipText(details + " | " + executionLabel(query, tabAlias));
        } catch (Exception ex) {
          if (CANCEL_REQUESTED.get()) status.setText(" Consulta cancelada. Resultados anteriores preservados.");
          else { status.setText(" Falha na consulta. Resultados anteriores preservados."); showError(ex); }
        }
      }
    };
    currentQuery.execute();
    // Ctrl+Enter deve manter o teclado no editor, sem alterar cursor ou seleção.
    SwingUtilities.invokeLater(queryEditor::requestFocusInWindow);
  }

  // Detalhes de tabelas e colunas sao obtidos via JDBC somente no servidor selecionado.
  private static void showTableMetadata(TableNode table, String selectedColumn) {
    BasicDataSource source;
    try { source = CONNECTIONS.source(table.source); }
    catch (SQLException ex) { showError(ex); return; }
    if (source == null) {
      status.setText(" Conexao indisponivel para " + table.source + ".");
      return;
    }
    TableMetadataDialog.show(frame, source, table.source, table.table, selectedColumn,
        CONNECTIONS.profile(table.source), table.catalog, table.schema);
  }

  private record ScriptResult(DefaultTableModel model, double elapsed, int index, int updated) {}

  private static void runScript() {
    JTextArea editor = activeEditor();
    if (federation == null) { status.setText(" Cadastre uma conexão primeiro."); return; }
    if (currentQuery != null && !currentQuery.isDone()) return;
    String selected = editor.getSelectedText();
    List<String> commands = SqlScriptText.splitStatements(
        selected != null && !selected.isBlank() ? selected : editor.getText());
    if (commands.isEmpty()) { status.setText(" Nenhum comando SQL para executar."); return; }
    execute.setEnabled(false);
    executeScriptButton.setEnabled(false);
    cancelQueryButton.setEnabled(true);
    CANCEL_REQUESTED.set(false);
    status.setText(" Executando script: " + commands.size() + " comando(s)...");
    final Component sqlTab = tabs.getSelectedComponent();
    final String tabAlias = tabSource(sqlTab);
    if (SqlTabRouting.missingAlias(tabAlias, CONNECTIONS.allProfiles())) {
      showError(new SQLException("Conexão padrão da aba não cadastrada: " + tabAlias));
      execute.setEnabled(true);
      executeScriptButton.setEnabled(true);
      cancelQueryButton.setEnabled(false);
      return;
    }
    final int pageSize = preferredPageSize;
    currentQuery = new SwingWorker<Void, ScriptResult>() {
      private int resultSlot = 0;
      protected Void doInBackground() throws Exception {
        for (int index = 0; index < commands.size(); index++) {
          if (isCancelled() || Thread.currentThread().isInterrupted()) break;
          String sql = commands.get(index);
          FederatedQuerySupport.validate(sql, CONNECTIONS.allProfiles());
          long started = System.nanoTime();
          Connection connection = null;
          Statement stmt = null;
          try {
            connection = queryConnectionFor(sql, tabAlias);
            stmt = connection.createStatement();
            RUNNING_CONNECTION.set(connection);
            RUNNING_STATEMENT.set(stmt);
            if (isCancelled()) break;
            NativeSqlRouter.Route nativeQuery = nativeRoute(sql, tabAlias);
            NativeJdbcPaging.Window nativeWindow = nativeQuery == null ? null
                : NativeJdbcPaging.window(sql, 0, pageSize);
            String pushedSql = NativeServerPaging.sql(nativeQuery, nativeWindow);
            if (pushedSql != null) stmt.setMaxRows(0);
            else if (nativeWindow != null) NativeJdbcPaging.configure(stmt, nativeWindow);
            else stmt.setMaxRows(pageSize);
            stmt.setQueryTimeout(120);
            if (nativeWindow == null) configureFetch(stmt, sql, pageSize);
            boolean hasResult = stmt.execute(pushedSql != null ? pushedSql
                : isPageableSql(sql) ? firstPageSql(sql, pageSize, tabAlias) : executableSql(sql));
            DefaultTableModel model = null;
            int updated = -1;
            if (hasResult) {
              try (ResultSet rs = stmt.getResultSet()) {
                model = pushedSql != null
                    ? SqlResultModels.readResultModel(rs, nativeWindow.amount(), this::isCancelled)
                    : nativeWindow == null ? SqlResultModels.readResultModel(rs, pageSize, this::isCancelled)
                    : NativeJdbcPaging.read(rs, nativeWindow, this::isCancelled);
              }
            } else updated = stmt.getUpdateCount();
            if (isCancelled() || Thread.currentThread().isInterrupted()) break;
            publish(new ScriptResult(model, (System.nanoTime() - started) / 1_000_000_000.0, index + 1, updated));
          } catch (SQLException ex) {
            throw nativeRoute(sql, tabAlias) == null && directSql(sql) == null
                ? FederatedQuerySupport.explain(ex) : ex;
          } finally {
            RUNNING_STATEMENT.compareAndSet(stmt, null);
            RUNNING_CONNECTION.compareAndSet(connection, null);
            if (stmt != null) try { stmt.close(); } catch (SQLException ignored) {}
            if (connection != null) try { connection.close(); } catch (SQLException ignored) {}
          }
        }
        return null;
      }
      protected void process(List<ScriptResult> chunks) {
        for (ScriptResult result : chunks) {
          if (result.model() != null) showOrUpdateResultTab(result.model(), result.elapsed(), sqlTab,
              commands.get(result.index() - 1), resultSlot++, pageSize, tabAlias);
          status.setText(" Script: comando " + result.index() + "/" + commands.size() +
              (result.model() != null ? " - " + result.model().getRowCount() + " linha(s)." :
                  " - " + result.updated() + " registro(s) afetado(s)."));
        }
      }
      protected void done() {
        if (currentQuery != this) return;
        execute.setEnabled(true);
        executeScriptButton.setEnabled(true);
        cancelQueryButton.setEnabled(false);
        if (CANCEL_REQUESTED.get()) { status.setText(" Script cancelado. Resultados concluidos preservados."); return; }
        try { get(); trimResultTabs(resultSlot); status.setText(" Script concluido: " + commands.size() + " comando(s)."); }
        catch (Exception ex) { status.setText(" Script interrompido por erro. Resultados anteriores preservados."); showError(ex); }
      }
    };
    currentQuery.execute();
    SwingUtilities.invokeLater(editor::requestFocusInWindow);
  }

  private static void configureColumns(JTable results) {
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
    JTable table = results;
    if (table == null || table.getColumnCount() == 0) {
      status.setText(" Execute uma consulta antes de exportar.");
      return;
    }
    JComboBox<String> separators = new JComboBox<>(new String[]{"Ponto e virgula (;) ", "Virgula (,) ", "Pipeline (|)"});
    JComboBox<String> encodings = new JComboBox<>(new String[]{"UTF-8", "ISO-8859-1"});
    JCheckBox header = new JCheckBox("Incluir cabecalho", true);
    JCheckBox includeQuote = new JCheckBox("Incluir quote", true);
    JPanel options = new JPanel(new GridLayout(4, 2, 8, 8));
    options.add(new JLabel("Separador:")); options.add(separators);
    options.add(new JLabel("Codificacao:")); options.add(encodings);
    options.add(header); options.add(new JLabel());
    options.add(includeQuote); options.add(new JLabel());
    if (JOptionPane.showConfirmDialog(frame, options, "Opcoes de exportacao CSV",
        JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE) != JOptionPane.OK_OPTION) return;
    char separator = separators.getSelectedIndex() == 1 ? ',' : separators.getSelectedIndex() == 2 ? '|' : ';';
    java.nio.charset.Charset charset = encodings.getSelectedIndex() == 1
        ? java.nio.charset.StandardCharsets.ISO_8859_1 : StandardCharsets.UTF_8;
    JFileChooser chooser = new JFileChooser();
    chooser.setSelectedFile(defaultUserFile("resultado.csv"));
    if (chooser.showSaveDialog(frame) != JFileChooser.APPROVE_OPTION) return;
    Path path = chooser.getSelectedFile().toPath();
    if (!path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".csv"))
      path = path.resolveSibling(path.getFileName() + ".csv");
    if (Files.exists(path) && JOptionPane.showConfirmDialog(frame,
        "Sobrescrever " + path.getFileName() + "?", "Confirmar",
        JOptionPane.YES_NO_OPTION) != JOptionPane.YES_OPTION) return;
    try {
      CsvExportWriter.write(table, path, charset, separator, header.isSelected(), includeQuote.isSelected());
      status.setText(" CSV exportado: " + path + " (" + table.getRowCount() + " linhas exibidas).");
    } catch (Exception ex) { showError(ex); }
  }

  private static void manageConnections() {
    ConnectionManagerDialog.show(frame, configPath, CONNECTIONS, Main::loadConfiguration);
  }

  private static synchronized void configure(Path path) throws Exception {
    federation = CONNECTIONS.configureLazy(path);
    TABLE_CACHE.clear();
    COLUMN_CACHE.clear();
  }

  private static void closeResources() { CONNECTIONS.close(); }

  private static void showError(Exception ex) {
    LinkSqlLog.error("Operação do LinkSQL", ex);
    Throwable cause = ex;
    while (cause.getCause() != null && cause.getCause() != cause) cause = cause.getCause();
    StringBuilder details = new StringBuilder(String.valueOf(cause.getMessage()));
    if (cause instanceof SQLException jdbcError) {
      if (jdbcError.getSQLState() != null && !jdbcError.getSQLState().isBlank())
        details.append("\nSQLState: ").append(jdbcError.getSQLState());
      if (jdbcError.getErrorCode() != 0)
        details.append(" | Codigo JDBC: ").append(jdbcError.getErrorCode());
      int extra = 0;
      for (SQLException next = jdbcError.getNextException(); next != null && extra++ < 3;
          next = next.getNextException()) {
        if (next.getMessage() != null && !next.getMessage().equals(cause.getMessage()))
          details.append("\n").append(next.getMessage());
      }
    }
    JOptionPane.showMessageDialog(frame, details.toString(), "Erro", JOptionPane.ERROR_MESSAGE);
  }

  private static class SourceNode {
    final String name;
    boolean loaded;
    SourceNode(String name) { this.name = name; }
    public String toString() {
      JdbcConnectionProfile profile = CONNECTIONS.profile(name);
      return name + (profile == null ? "" : "  [" + profile.type() + "]");
    }
  }
  private static class NamespaceNode {
    final String source;
    final DatabaseCatalog.Namespace scope;
    boolean loaded;
    NamespaceNode(String source, DatabaseCatalog.Namespace scope) { this.source = source; this.scope = scope; }
    public String toString() { return scope.name(); }
  }
  private static class TableNode {
    final String source, table, catalog, schema;
    final boolean defaultScope;
    boolean loaded;
    TableNode(String source, String table, String catalog, String schema, boolean defaultScope) {
      this.source = source; this.table = table; this.catalog = catalog; this.schema = schema; this.defaultScope = defaultScope;
    }
    public String toString() {
      JdbcConnectionProfile profile = CONNECTIONS.profile(source);
      return profile != null && profile.type() == SqlDatabaseType.SQLSERVER && schema != null ? schema + "." + table : table;
    }
  }

  private static class RoutineFolder {
    final String source, catalog, schema;
    final boolean functions;
    boolean loaded;
    RoutineFolder(String source, String catalog, String schema, boolean functions) {
      this.source = source;
      this.catalog = catalog;
      this.schema = schema;
      this.functions = functions;
    }
    public String toString() { return functions ? "Funções" : "Procedimentos"; }
  }

}
