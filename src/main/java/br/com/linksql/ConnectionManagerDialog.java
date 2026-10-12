package br.com.linksql;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.event.ListSelectionEvent;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.io.IOException;
import java.nio.file.*;
import java.util.Arrays;
import java.util.Locale;
import java.util.List;
import java.util.ArrayList;
import java.util.Comparator;

/** Cadastro de perfis com carregamento automatico e senha protegida por usuario Windows. */
final class ConnectionManagerDialog extends JDialog {
  private static final ObjectMapper JSON = new ObjectMapper();
  private final Path file;
  private final SqlConnectionRegistry registry;
  private final Runnable onChanged;
  private final DefaultListModel<JdbcConnectionProfile> profiles = new DefaultListModel<>();
  private final List<JdbcConnectionProfile> allProfiles = new ArrayList<>();
  private final JTextField search = new JTextField();
  private final JComboBox<String> filterType = new JComboBox<>();
  private final JComboBox<String> sorting = new JComboBox<>(new String[]{"Agrupar por banco", "Ordenar por nome"});
  private final JLabel listCount = new JLabel("Nenhuma conexão");
  private final JList<JdbcConnectionProfile> list = new JList<>(profiles);
  private final JTextField name = new JTextField(25), host = new JTextField(25), port = new JTextField(8),
      database = new JTextField(25), schema = new JTextField(25), username = new JTextField(25),
      passwordEnv = new JTextField(25), jdbcUrl = new JTextField(32);
  private final JPasswordField password = new JPasswordField(25);
  private final JComboBox<SqlDatabaseType> databaseType = new JComboBox<>(SqlDatabaseType.values());
  private final JCheckBox remember = new JCheckBox("Guardar senha protegida neste usuário do Windows", true);
  private final JComboBox<ConnectionVault.Duration> duration = new JComboBox<>(ConnectionVault.Duration.values());
  private final JCheckBox trustSqlServer = new JCheckBox("Confiar no certificado do SQL Server (somente ambientes confiáveis)");
  private final JCheckBox legacyFirebird = new JCheckBox("Compatibilidade Firebird antigo (enableProtocol=*)");
  private final JLabel credentialHint = new JLabel("Senha ainda não cadastrada");
  private final JButton saveButton = new JButton("Salvar conexão"), testButton = new JButton("Testar conexão"),
      removeButton = new JButton("Excluir"), resetPassword = new JButton("Esquecer senha"),
      duplicateButton = new JButton("Duplicar");
  private String originalName;
  private boolean selecting;
  private ConnectionManagerDialog(JFrame owner, Path file, SqlConnectionRegistry registry, Runnable onChanged) {
    super(owner, "LinkSQL — Gerenciar conexões", true);
    this.file = file; this.registry = registry; this.onChanged = onChanged;
    setMinimumSize(new Dimension(920, 640));
    setSize(1160, 760);
    setLocationRelativeTo(owner);
    setLayout(new BorderLayout(12, 12));
    LinkSqlDialogStyle.window(this);
    add(LinkSqlDialogStyle.heading("Gerenciar conexões",
        "Cadastre, organize e teste os bancos sem expor senhas no arquivo de configuração.",
        "database"), BorderLayout.NORTH);

    list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
    LinkSqlDialogStyle.list(list, 66);
    list.setCellRenderer((component, value, index, selected, focused) -> {
      JPanel cell = new JPanel(new BorderLayout(2, 2));
      cell.setBorder(new EmptyBorder(8, 9, 8, 9));
      Color bg = selected ? LinkSqlVisualStyle.rowSelection() : LinkSqlVisualStyle.surface();
      cell.setBackground(bg);
      JLabel icon = new JLabel(LinkSqlVisualStyle.icon("database", LinkSqlVisualStyle.accent(), 20));
      icon.setBorder(new EmptyBorder(0, 0, 0, 10));
      cell.add(icon, BorderLayout.WEST);
      JPanel texts = new JPanel(new GridLayout(2, 1, 0, 4));
      texts.setOpaque(false);
      JLabel top = new JLabel(value.name() + "    ·    " + value.type());
      top.setFont(new Font("Segoe UI", Font.BOLD, 12));
      top.setForeground(LinkSqlVisualStyle.text());
      JLabel bottom = new JLabel(connectionLocation(value));
      bottom.setFont(new Font("Segoe UI", Font.PLAIN, 11));
      bottom.setForeground(LinkSqlVisualStyle.subtleText());
      texts.add(top); texts.add(bottom);
      cell.add(texts, BorderLayout.CENTER);
      return cell;
    });
    LinkSqlDialogStyle.field(search);
    search.putClientProperty("JTextField.placeholderText", "Buscar por nome, servidor ou banco...");
    search.setToolTipText("Filtra as conexões já cadastradas; não abre conexão com os servidores.");
    search.getDocument().addDocumentListener(new DocumentListener() {
      public void insertUpdate(DocumentEvent e) { refreshFilter(); }
      public void removeUpdate(DocumentEvent e) { refreshFilter(); }
      public void changedUpdate(DocumentEvent e) { refreshFilter(); }
    });
    filterType.addItem("Todos os bancos");
    for (SqlDatabaseType type : SqlDatabaseType.values()) filterType.addItem(type.toString());
    filterType.addActionListener(e -> refreshFilter());
    sorting.addActionListener(e -> refreshFilter());
    LinkSqlDialogStyle.field(filterType);
    LinkSqlDialogStyle.field(sorting);
    JPanel selectors = new JPanel(new GridLayout(2, 1, 0, 7));
    selectors.setOpaque(false);
    selectors.add(filterType); selectors.add(sorting);
    JButton addButton = new JButton("Nova conexão");
    LinkSqlDialogStyle.button(addButton, "plus", true);
    addButton.addActionListener(e -> chooseDatabase());
    JPanel leftTop = new JPanel(new BorderLayout(5, 9));
    leftTop.setOpaque(false);
    leftTop.add(LinkSqlDialogStyle.section("CONEXÕES SALVAS", addButton), BorderLayout.NORTH);
    JPanel filtering = new JPanel(new BorderLayout(0, 7));
    filtering.setOpaque(false);
    filtering.add(search, BorderLayout.NORTH);
    filtering.add(selectors, BorderLayout.CENTER);
    leftTop.add(filtering, BorderLayout.CENTER);
    JPanel left = LinkSqlDialogStyle.surface(new JPanel(new BorderLayout(4, 10)));
    left.add(leftTop, BorderLayout.NORTH);
    left.add(LinkSqlDialogStyle.scroll(list), BorderLayout.CENTER);
    listCount.setForeground(LinkSqlVisualStyle.subtleText());
    listCount.setFont(new Font("Segoe UI", Font.PLAIN, 11));
    left.add(listCount, BorderLayout.SOUTH);
    left.setPreferredSize(new Dimension(330, 520));
    list.addListSelectionListener(this::selected);

    JTabbedPane pages = new JTabbedPane();
    LinkSqlDialogStyle.tabs(pages);
    JPanel primary = new JPanel(new GridBagLayout());
    primary.setBackground(LinkSqlVisualStyle.surface());
    int row = 0;
    addField(primary, row++, "Banco de dados", databaseType);
    addField(primary, row++, "Nome da conexão", name);
    addField(primary, row++, "Servidor / Host", host);
    addField(primary, row++, "Porta", port);
    addField(primary, row++, "Banco / serviço / arquivo", database);
    addField(primary, row++, "Schema (opcional)", schema);
    addField(primary, row++, "Usuário", username);
    addField(primary, row++, "Senha", password);
    credentialHint.setFont(new Font("Segoe UI", Font.PLAIN, 11));
    credentialHint.setForeground(LinkSqlVisualStyle.subtleText());
    addField(primary, row++, "Proteção", credentialHint);
    addField(primary, row++, "", remember);
    addField(primary, row++, "Lembrar senha por", duration);
    remember.addActionListener(e -> duration.setEnabled(remember.isSelected()));
    JPanel advanced = new JPanel(new GridBagLayout());
    advanced.setBackground(LinkSqlVisualStyle.surface());
    addField(advanced, 0, "Variável de ambiente (legado)", passwordEnv);
    addField(advanced, 1, "URL JDBC (opcional)", jdbcUrl);
    addField(advanced, 2, "SQL Server", trustSqlServer);
    addField(advanced, 3, "Firebird", legacyFirebird);
    trustSqlServer.setToolTipText("Ativa trustServerCertificate=true. Evite em produção sem uma justificativa.");
    legacyFirebird.setToolTipText("Habilita os protocolos antigos do Jaybird quando o servidor Firebird exigir.");
    JTextArea hint = LinkSqlDialogStyle.note("O LinkSQL utiliza somente leitura por padrão. "
        + "Ao usar URL JDBC personalizada, confira as propriedades SSL/TLS de cada servidor. "
        + "A senha nunca será escrita no arquivo de conexões.");
    addField(advanced, 4, "", hint);
    pages.addTab("Conexão", LinkSqlDialogStyle.scroll(primary));
    pages.addTab("Avançado", LinkSqlDialogStyle.scroll(advanced));
    for (JComponent field : new JComponent[]{name, host, port, database, schema, username, password,
        passwordEnv, jdbcUrl, databaseType, duration}) LinkSqlDialogStyle.field(field);
    databaseType.addActionListener(e -> {
      if (!selecting && databaseType.getSelectedItem() instanceof SqlDatabaseType t) {
        if (port.getText().isBlank() || isDefaultPort(port.getText())) port.setText(String.valueOf(t.defaultPort()));
        if (username.getText().isBlank()) username.setText(defaultUser(t));
        updateDatabaseSpecificFields();
      }
    });

    JPanel buttons = LinkSqlDialogStyle.footer();
    LinkSqlDialogStyle.button(saveButton, "save", true);
    LinkSqlDialogStyle.button(testButton, "play", false);
    LinkSqlDialogStyle.button(duplicateButton, "plus", false);
    LinkSqlDialogStyle.destructiveButton(removeButton, "stop");
    LinkSqlDialogStyle.destructiveButton(resetPassword, "settings");
    resetPassword.addActionListener(e -> forgetPassword());
    removeButton.addActionListener(e -> deleteProfile());
    testButton.addActionListener(e -> testConnection());
    saveButton.addActionListener(e -> saveProfile());
    duplicateButton.addActionListener(e -> duplicateProfile());
    buttons.add(resetPassword); buttons.add(duplicateButton); buttons.add(removeButton);
    buttons.add(testButton); buttons.add(saveButton);
    JPanel right = LinkSqlDialogStyle.surface(new JPanel(new BorderLayout(5, 12)));
    right.add(pages, BorderLayout.CENTER); right.add(buttons, BorderLayout.SOUTH);

    JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, left, right);
    LinkSqlVisualStyle.splitter(split);
    split.setResizeWeight(0.30);
    add(split, BorderLayout.CENTER);
    JButton close = new JButton("Fechar");
    LinkSqlDialogStyle.button(close, "stop", false);
    close.addActionListener(e -> dispose());
    JPanel footer = LinkSqlDialogStyle.footer();
    footer.add(close);
    add(footer, BorderLayout.SOUTH);
    loadProfiles();
    if (profiles.size() != 0) list.setSelectedIndex(0);
    else setEmptyForm(SqlDatabaseType.MYSQL);
  }

  static void show(JFrame frame, Path path, SqlConnectionRegistry registry, Runnable onChanged) {
    show(frame, path, registry, onChanged, null);
  }

  static void show(JFrame frame, Path path, SqlConnectionRegistry registry,
                   Runnable onChanged, String preferredName) {
    ConnectionManagerDialog dialog = new ConnectionManagerDialog(frame, path, registry, onChanged);
    if (preferredName != null) dialog.selectName(preferredName);
    dialog.setVisible(true);
  }

  private static void addField(JPanel panel, int row, String label, JComponent field) {
    GridBagConstraints c = new GridBagConstraints();
    c.gridx = 0; c.gridy = row; c.weightx = 0; c.fill = GridBagConstraints.HORIZONTAL;
    c.anchor = GridBagConstraints.NORTHWEST; c.insets = new Insets(7, 8, 7, 15);
    c.ipadx = 3;
    panel.add(LinkSqlDialogStyle.caption(label), c);
    c.gridx = 1; c.weightx = 1; c.insets = new Insets(7, 2, 7, 10);
    panel.add(field, c);
  }

  private static String defaultUser(SqlDatabaseType type) {
    return switch(type) {
      case MYSQL -> "root";
      case ORACLE -> "system";
      case POSTGRESQL -> "postgres";
      case SQLSERVER -> "sa";
      case FIREBIRD -> "SYSDBA";
    };
  }

  private static boolean isDefaultPort(String text) {
    try {
      int n = Integer.parseInt(text.strip());
      for (SqlDatabaseType type : SqlDatabaseType.values()) if (type.defaultPort() == n) return true;
    } catch (Exception ignored) {}
    return false;
  }

  /** Apenas metadados locais: pesquisar ou ordenar não conecta aos servidores. */
  private void loadProfiles() {
    allProfiles.clear();
    try {
      for (JsonNode node : readDocument().path("connections"))
        allProfiles.add(JdbcConnectionProfile.fromJson(node));
      refreshFilter();
    } catch (Exception ex) { error(ex); }
  }

  private static String connectionLocation(JdbcConnectionProfile profile) {
    String detail = profile.jdbcUrl().isBlank()
        ? profile.host() + ":" + profile.port() : "URL JDBC personalizada";
    String db = profile.database();
    return detail + (db == null || db.isBlank() ? "" : "  ·  " + db);
  }

  private void refreshFilter() {
    String query = search.getText().strip().toLowerCase(Locale.ROOT);
    String selectedType = (String) filterType.getSelectedItem();
    String selectedName = list.getSelectedValue() == null ? null : list.getSelectedValue().name();
    List<JdbcConnectionProfile> matches = new ArrayList<>();
    for (JdbcConnectionProfile p : allProfiles) {
      if (selectedType != null && !selectedType.equals("Todos os bancos")
          && !p.type().toString().equals(selectedType)) continue;
      String searchable = (p.name() + " " + p.type() + " " + p.host() + " "
          + p.database() + " " + p.schema()).toLowerCase(Locale.ROOT);
      if (searchable.contains(query)) matches.add(p);
    }
    if (sorting.getSelectedIndex() == 1)
      matches.sort(Comparator.comparing(JdbcConnectionProfile::name, String.CASE_INSENSITIVE_ORDER));
    else matches.sort(Comparator.comparing((JdbcConnectionProfile p) -> p.type().ordinal())
        .thenComparing(JdbcConnectionProfile::name, String.CASE_INSENSITIVE_ORDER));
    profiles.clear();
    for (JdbcConnectionProfile p : matches) profiles.addElement(p);
    listCount.setText("  " + matches.size() + " de " + allProfiles.size() + " conexões");
    if (selectedName != null) {
      selectName(selectedName);
      if (list.getSelectedIndex() < 0) {
        // Perfil oculto pelo filtro: nao permitir salvar por engano
        // alterações no perfil que ja nao está visivel.
        setEmptyForm(SqlDatabaseType.MYSQL);
      }
    }
  }

  private void duplicateProfile() {
    JdbcConnectionProfile chosen = list.getSelectedValue();
    if (chosen == null) return;
    String candidate = chosen.name() + "_COPIA";
    int n = 2;
    while (existsProfile(candidate)) candidate = chosen.name() + "_COPIA" + n++;
    selecting = true;
    try {
      originalName = null; // A cópia é um perfil novo, sem compartilhar o segredo DPAPI.
      list.clearSelection();
      name.setText(candidate); databaseType.setSelectedItem(chosen.type());
      host.setText(chosen.host()); port.setText(String.valueOf(chosen.port()));
      database.setText(chosen.database()); schema.setText(chosen.schema());
      username.setText(chosen.user()); passwordEnv.setText("LINKSQL_" + candidate + "_PASSWORD");
      jdbcUrl.setText(chosen.jdbcUrl()); password.setText(""); remember.setSelected(true);
      duration.setSelectedItem(ConnectionVault.Duration.PERMANENT); duration.setEnabled(true);
      trustSqlServer.setSelected(chosen.jdbcUrl().toLowerCase(Locale.ROOT).contains("trustservercertificate=true"));
      legacyFirebird.setSelected(chosen.jdbcUrl().toLowerCase(Locale.ROOT).contains("enableprotocol=*"));
      updateDatabaseSpecificFields();
      credentialHint.setText("Cópia sem senha. Informe-a antes de salvar, se desejar lembrá-la.");
      name.requestFocusInWindow();
    } finally { selecting = false; }
  }

  private boolean existsProfile(String target) {
    for (JdbcConnectionProfile p : allProfiles)
      if (p.name().equalsIgnoreCase(target)) return true;
    return false;
  }

  private void selected(ListSelectionEvent event) {
    if (event.getValueIsAdjusting()) return;
    JdbcConnectionProfile chosen = list.getSelectedValue();
    if (chosen == null) return;
    selecting = true;
    try {
      originalName = chosen.name();
      name.setText(chosen.name()); databaseType.setSelectedItem(chosen.type());
      host.setText(chosen.host()); port.setText(String.valueOf(chosen.port()));
      database.setText(chosen.database()); schema.setText(chosen.schema());
      username.setText(chosen.user()); passwordEnv.setText(chosen.passwordEnv());
      jdbcUrl.setText(chosen.jdbcUrl()); password.setText("");
      remember.setSelected(ConnectionVault.policy(chosen.name()) != ConnectionVault.Duration.SESSION);
      duration.setSelectedItem(ConnectionVault.policy(chosen.name()));
      duration.setEnabled(remember.isSelected());
      trustSqlServer.setSelected(chosen.jdbcUrl().toLowerCase(Locale.ROOT).contains("trustservercertificate=true"));
      legacyFirebird.setSelected(chosen.jdbcUrl().toLowerCase(Locale.ROOT).contains("enableprotocol=*"));
      updateDatabaseSpecificFields();
      credentialHint.setText(ConnectionVault.has(chosen.name()) ? "Senha protegida salva. Deixe vazio para manter." :
          "Senha não salva. Informe uma para lembrar.");
    } finally { selecting = false; }
  }

  private void chooseDatabase() {
    final JDialog wizard = new JDialog(this, "Selecionar banco de dados", true);
    wizard.setLayout(new BorderLayout(12, 12));
    LinkSqlDialogStyle.window(wizard);
    wizard.add(LinkSqlDialogStyle.heading("Nova conexão",
        "Escolha o tipo de banco para configurar um novo perfil.", "database"), BorderLayout.NORTH);
    JPanel cards = new JPanel(new GridLayout(0, 2, 12, 12));
    cards.setBackground(LinkSqlVisualStyle.surface());
    cards.setBorder(new EmptyBorder(14, 14, 14, 14));
    for (SqlDatabaseType type : SqlDatabaseType.values()) {
      String description = switch(type) {
        case MYSQL -> "Bancos MySQL e compatíveis";
        case ORACLE -> "Serviços Oracle Database";
        case POSTGRESQL -> "Bancos e schemas PostgreSQL";
        case SQLSERVER -> "Microsoft SQL Server";
        case FIREBIRD -> "Arquivos FDB em servidores Firebird";
      };
      JButton button = new JButton("<html><div style='text-align:left'><b>" + type
          + "</b><br><small>" + description + "</small></div></html>");
      button.setHorizontalAlignment(SwingConstants.LEFT);
      button.setIcon(LinkSqlVisualStyle.icon("database", LinkSqlVisualStyle.accent(), 21));
      button.setIconTextGap(10);
      button.setBackground(LinkSqlVisualStyle.toolbarSurface());
      button.setForeground(LinkSqlVisualStyle.text());
      button.putClientProperty("JButton.buttonType", "roundRect");
      button.setFont(new Font("Segoe UI", Font.PLAIN, 13));
      button.setPreferredSize(new Dimension(285, 76));
      button.addActionListener(e -> { wizard.dispose(); setEmptyForm(type); });
      cards.add(button);
    }
    wizard.add(cards, BorderLayout.CENTER);
    wizard.pack();
    wizard.setMinimumSize(new Dimension(660, 410));
    wizard.setLocationRelativeTo(this);
    wizard.setVisible(true);
  }

  private void setEmptyForm(SqlDatabaseType type) {
    list.clearSelection(); selecting = true;
    try {
      originalName = null; databaseType.setSelectedItem(type);
      name.setText(""); host.setText("localhost"); port.setText(String.valueOf(type.defaultPort()));
      database.setText(""); schema.setText(""); username.setText(defaultUser(type));
      passwordEnv.setText("LINKSQL_" + type.name() + "_PASSWORD");
      jdbcUrl.setText(""); password.setText(""); remember.setSelected(true);
      duration.setSelectedItem(ConnectionVault.Duration.PERMANENT); duration.setEnabled(true);
      trustSqlServer.setSelected(false);
      legacyFirebird.setSelected(false);
      updateDatabaseSpecificFields();
      credentialHint.setText("Informe a senha para guardá-la protegida pelo Windows.");
      name.requestFocusInWindow();
    } finally { selecting = false; }
  }

  private void updateDatabaseSpecificFields() {
    SqlDatabaseType chosen = (SqlDatabaseType) databaseType.getSelectedItem();
    trustSqlServer.setEnabled(chosen == SqlDatabaseType.SQLSERVER);
    legacyFirebird.setEnabled(chosen == SqlDatabaseType.FIREBIRD);
  }

  private static String firebirdProtocolUrl(String jdbc, boolean enabled) {
    // Remover apenas este parâmetro, sem tocar nos demais parâmetros JDBC.
    String clean = jdbc.replaceAll("(?i)([?&])enableProtocol=\\*(?=(&|$))", "$1")
        .replace("?&", "?").replace("&&", "&").replaceAll("[?&]$", "");
    return enabled ? clean + (clean.contains("?") ? "&" : "?") + "enableProtocol=*" : clean;
  }

  private JdbcConnectionProfile formProfile() {
    SqlDatabaseType type = (SqlDatabaseType)databaseType.getSelectedItem();
    int parsedPort;
    try { parsedPort = Integer.parseInt(port.getText().strip()); }
    catch (Exception ex) { throw new IllegalArgumentException("Informe uma porta válida."); }
    String customUrl = jdbcUrl.getText().strip();
    if (type == SqlDatabaseType.SQLSERVER) {
      if (customUrl.isEmpty() && trustSqlServer.isSelected()) {
        customUrl = "jdbc:sqlserver://" + host.getText().strip() + ":" + parsedPort
            + ";databaseName=" + database.getText().strip() + ";encrypt=true;trustServerCertificate=true";
      } else if (!customUrl.isEmpty()) {
        customUrl = customUrl.replaceAll("(?i);trustServerCertificate=(true|false)", "");
        if (trustSqlServer.isSelected()) customUrl += ";trustServerCertificate=true";
      }
    }
    if (type == SqlDatabaseType.FIREBIRD) {
      if (customUrl.isEmpty() && legacyFirebird.isSelected()) {
        customUrl = type.url(host.getText().strip(), parsedPort, database.getText().strip());
      }
      if (!customUrl.isEmpty()) customUrl = firebirdProtocolUrl(customUrl, legacyFirebird.isSelected());
    }
    JdbcConnectionProfile candidate = new JdbcConnectionProfile(name.getText().strip(), type,
        host.getText().strip(), parsedPort, database.getText().strip(), schema.getText().strip(),
        username.getText().strip(), passwordEnv.getText().strip(), customUrl, true);
    candidate.validate();
    return candidate;
  }

  private void testConnection() {
    try {
      JdbcConnectionProfile profile = formProfile();
      char[] typed = password.getPassword();
      String pass = typed.length == 0 ? ConnectionVault.get(originalName == null ? profile.name() : originalName)
          : new String(typed);
      Arrays.fill(typed, '\0');
      if (pass == null || pass.isBlank()) pass = System.getenv(profile.passwordEnv());
      if (pass == null || pass.isBlank()) {
        JOptionPane.showMessageDialog(this, "Informe a senha para testar a conexão."); return;
      }
      final String toUse = pass;
      testButton.setEnabled(false);
      new SwingWorker<String, Void>() {
        protected String doInBackground() throws Exception { return registry.test(profile, toUse); }
        protected void done() {
          testButton.setEnabled(true);
          try { JOptionPane.showMessageDialog(ConnectionManagerDialog.this, "Conectado: " + get(),
              "Teste de conexão", JOptionPane.INFORMATION_MESSAGE); }
          catch (Exception ex) { error(ex); }
        }
      }.execute();
    } catch (Exception ex) { error(ex); }
  }

  private void saveProfile() {
    try {
      JdbcConnectionProfile profile = formProfile();
      String previousName = originalName;
      ObjectNode root = readDocument();
      ArrayNode items = (ArrayNode)root.withArray("connections");
      int oldIndex = -1;
      for (int i = 0; i < items.size(); i++) {
        String n = items.get(i).path("name").asText();
        if (previousName != null && previousName.equalsIgnoreCase(n)) oldIndex = i;
        else if (profile.name().equalsIgnoreCase(n))
          throw new IllegalArgumentException("Já existe uma conexão chamada " + profile.name());
      }
      final int indexToReplace = oldIndex;
      final char[] secret = password.getPassword();
      final boolean keep = remember.isSelected();
      final ConnectionVault.Duration chosenDuration =
          (ConnectionVault.Duration) duration.getSelectedItem();
      password.setText("");
      saveButton.setEnabled(false);
      // Proteger no Windows envolve iniciar um processo PowerShell.
      // NUNCA fazer isso na thread de eventos Swing.
      new SwingWorker<Void, Void>() {
        protected Void doInBackground() throws Exception {
          try {
            if (secret.length > 0) {
              ConnectionVault.save(profile.name(), secret,
                  keep ? chosenDuration : ConnectionVault.Duration.SESSION);
              if (previousName != null && !previousName.equalsIgnoreCase(profile.name()))
                ConnectionVault.delete(previousName);
            } else if (previousName != null && !previousName.equalsIgnoreCase(profile.name())) {
              ConnectionVault.rename(previousName, profile.name());
            }
            ObjectNode item = JSON.createObjectNode();
            item.put("name", profile.name()); item.put("type", profile.type().name());
            item.put("host", profile.host()); item.put("port", profile.port());
            item.put("database", profile.database()); item.put("schema", profile.schema());
            item.put("user", profile.user()); item.put("passwordEnv", profile.passwordEnv());
            item.put("jdbcUrl", profile.jdbcUrl()); item.put("readOnly", true);
            if (indexToReplace < 0) items.add(item); else items.set(indexToReplace, item);
            writeDocument(root);
            return null;
          } finally { Arrays.fill(secret, '\0'); }
        }
        protected void done() {
          saveButton.setEnabled(true);
          try {
            get();
            originalName = profile.name();
            loadProfiles(); selectName(profile.name()); onChanged.run();
            JOptionPane.showMessageDialog(ConnectionManagerDialog.this,
                "Conexão salva no perfil do usuário.", "LinkSQL", JOptionPane.INFORMATION_MESSAGE);
          } catch (Exception ex) {
            // Mantém a causa original DPAPI em vez de apenas "não foi possível".
            error(ex);
          }
        }
      }.execute();
    } catch (Exception ex) { error(ex); }
  }

  private void deleteProfile() {
    if (originalName == null) return;
    if (JOptionPane.showConfirmDialog(this, "Excluir a conexão " + originalName + " e sua senha salva?",
        "Confirmar exclusão", JOptionPane.YES_NO_OPTION) != JOptionPane.YES_OPTION) return;
    try {
      ObjectNode root = readDocument();
      ArrayNode items = (ArrayNode)root.withArray("connections");
      for (int i = 0; i < items.size(); i++) if (items.get(i).path("name").asText().equalsIgnoreCase(originalName)) {
        items.remove(i); break;
      }
      writeDocument(root);
      ConnectionVault.delete(originalName);
      loadProfiles(); if (!profiles.isEmpty()) list.setSelectedIndex(0); else setEmptyForm(SqlDatabaseType.MYSQL);
      onChanged.run();
    } catch (Exception ex) { error(ex); }
  }

  private void forgetPassword() {
    if (originalName == null) return;
    if (JOptionPane.showConfirmDialog(this, "Remover a senha protegida de " + originalName + "?",
        "Esquecer senha", JOptionPane.YES_NO_OPTION) != JOptionPane.YES_OPTION) return;
    try { ConnectionVault.delete(originalName); credentialHint.setText("Senha removida. Será solicitada quando necessária."); onChanged.run(); }
    catch (Exception ex) { error(ex); }
  }

  private void selectName(String key) {
    for (int i = 0; i < profiles.size(); i++) if (profiles.get(i).name().equalsIgnoreCase(key)) {
      list.setSelectedIndex(i); break;
    }
  }

  private ObjectNode readDocument() throws IOException {
    if (!Files.exists(file)) {
      ObjectNode root = JSON.createObjectNode(); root.set("connections", JSON.createArrayNode()); return root;
    }
    JsonNode root = JSON.readTree(Files.readString(file));
    if (!(root instanceof ObjectNode object) || !root.path("connections").isArray())
      throw new IOException("Formato de conexões inválido.");
    return object;
  }

  private void writeDocument(ObjectNode document) throws IOException {
    Files.createDirectories(file.toAbsolutePath().getParent());
    Path temporary = Files.createTempFile(file.toAbsolutePath().getParent(), "connections-", ".tmp");
    try {
      JSON.writerWithDefaultPrettyPrinter().writeValue(temporary.toFile(), document);
      if (Files.isRegularFile(file)) Files.copy(file,
          file.resolveSibling(file.getFileName().toString() + ".bak"), StandardCopyOption.REPLACE_EXISTING);
      try { Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
      catch (AtomicMoveNotSupportedException ignored) { Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING); }
    } finally { Files.deleteIfExists(temporary); }
    LinkSqlPaths.mirrorForJdbc05(file);
  }

  private void error(Exception ex) {
    Throwable cause = ex;
    while (cause.getCause() != null && cause.getCause() != cause) cause = cause.getCause();
    JOptionPane.showMessageDialog(this, String.valueOf(cause.getMessage()), "LinkSQL — erro", JOptionPane.ERROR_MESSAGE);
  }
}
