package br.com.santysqlstudio;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.event.ListSelectionEvent;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.io.IOException;
import java.nio.file.*;
import java.util.Arrays;
import java.util.Locale;

/** Cadastro de perfis com carregamento automatico e senha protegida por usuario Windows. */
final class ConnectionManagerDialog extends JDialog {
  private static final ObjectMapper JSON = new ObjectMapper();
  private final Path file;
  private final SqlConnectionRegistry registry;
  private final Runnable onChanged;
  private final DefaultListModel<JdbcConnectionProfile> profiles = new DefaultListModel<>();
  private final JList<JdbcConnectionProfile> list = new JList<>(profiles);
  private final JTextField name = new JTextField(25), host = new JTextField(25), port = new JTextField(8),
      database = new JTextField(25), schema = new JTextField(25), username = new JTextField(25),
      passwordEnv = new JTextField(25), jdbcUrl = new JTextField(32);
  private final JPasswordField password = new JPasswordField(25);
  private final JComboBox<SqlDatabaseType> databaseType = new JComboBox<>(SqlDatabaseType.values());
  private final JCheckBox remember = new JCheckBox("Guardar senha protegida neste usuário do Windows", true);
  private final JComboBox<ConnectionVault.Duration> duration = new JComboBox<>(ConnectionVault.Duration.values());
  private final JCheckBox trustSqlServer = new JCheckBox("Confiar no certificado do SQL Server (somente ambientes confiáveis)");
  private final JLabel credentialHint = new JLabel("Senha ainda não cadastrada");
  private final JButton saveButton = new JButton("Salvar conexão"), testButton = new JButton("Testar conexão"),
      removeButton = new JButton("Excluir"), resetPassword = new JButton("Esquecer senha");
  private String originalName;
  private boolean selecting;
  private ConnectionManagerDialog(JFrame owner, Path file, SqlConnectionRegistry registry, Runnable onChanged) {
    super(owner, "LinkSQL — Gerenciar conexões", true);
    this.file = file; this.registry = registry; this.onChanged = onChanged;
    setMinimumSize(new Dimension(830, 575));
    setSize(1000, 665);
    setLocationRelativeTo(owner);
    setLayout(new BorderLayout(12, 12));
    ((JComponent)getContentPane()).setBorder(new EmptyBorder(12, 12, 12, 12));

    JLabel header = new JLabel("Conexões de banco de dados");
    header.setFont(header.getFont().deriveFont(Font.BOLD, 19f));
    JLabel subtitle = new JLabel("Os perfis ficam salvos no Windows; os servidores são acessados quando necessários.");
    subtitle.setForeground(UIManager.getColor("Label.disabledForeground"));
    JPanel title = new JPanel(new GridLayout(2, 1, 2, 2)); title.add(header); title.add(subtitle);
    add(title, BorderLayout.NORTH);

    list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
    list.setCellRenderer((listComponent, value, index, isSelected, cellHasFocus) -> {
      JLabel label = new JLabel("  " + value.name() + "     ·     " + value.type(), UIManager.getIcon("FileView.hardDriveIcon"), JLabel.LEFT);
      label.setOpaque(true);
      label.setBorder(new EmptyBorder(10, 7, 10, 5));
      label.setBackground(isSelected ? listComponent.getSelectionBackground() : listComponent.getBackground());
      label.setForeground(isSelected ? listComponent.getSelectionForeground() : listComponent.getForeground());
      return label;
    });
    JButton addButton = new JButton("+ Nova conexão");
    addButton.addActionListener(e -> chooseDatabase());
    JPanel left = new JPanel(new BorderLayout(4, 7));
    left.add(addButton, BorderLayout.NORTH);
    left.add(new JScrollPane(list), BorderLayout.CENTER);
    left.setPreferredSize(new Dimension(260, 500));
    list.addListSelectionListener(this::selected);

    JTabbedPane pages = new JTabbedPane();
    JPanel primary = new JPanel(new GridBagLayout());
    int row = 0;
    addField(primary, row++, "Banco de dados", databaseType);
    addField(primary, row++, "Nome da conexão", name);
    addField(primary, row++, "Servidor / Host", host);
    addField(primary, row++, "Porta", port);
    addField(primary, row++, "Banco / serviço / arquivo", database);
    addField(primary, row++, "Schema (opcional)", schema);
    addField(primary, row++, "Usuário", username);
    addField(primary, row++, "Senha", password);
    credentialHint.setFont(credentialHint.getFont().deriveFont(11f));
    credentialHint.setForeground(UIManager.getColor("Label.disabledForeground"));
    addField(primary, row++, "Proteção", credentialHint);
    addField(primary, row++, "", remember);
    addField(primary, row++, "Lembrar senha por", duration);
    remember.addActionListener(e -> duration.setEnabled(remember.isSelected()));
    JPanel advanced = new JPanel(new GridBagLayout());
    addField(advanced, 0, "Variável de ambiente (legado)", passwordEnv);
    addField(advanced, 1, "URL JDBC (opcional)", jdbcUrl);
    addField(advanced, 2, "SQL Server", trustSqlServer);
    JTextArea hint = new JTextArea("O LinkSQL utiliza somente leitura por padrão. Ao usar URL JDBC personalizada, confira as propriedades SSL/TLS de cada servidor. A senha nunca será escrita no arquivo de conexões.");
    hint.setEditable(false); hint.setLineWrap(true); hint.setWrapStyleWord(true); hint.setOpaque(false);
    addField(advanced, 3, "", hint);
    pages.addTab("Conexão", new JScrollPane(primary));
    pages.addTab("Avançado", new JScrollPane(advanced));
    databaseType.addActionListener(e -> {
      if (!selecting && databaseType.getSelectedItem() instanceof SqlDatabaseType t) {
        if (port.getText().isBlank() || isDefaultPort(port.getText())) port.setText(String.valueOf(t.defaultPort()));
        if (username.getText().isBlank()) username.setText(defaultUser(t));
      }
    });

    JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 3));
    resetPassword.addActionListener(e -> forgetPassword());
    removeButton.addActionListener(e -> deleteProfile());
    testButton.addActionListener(e -> testConnection());
    saveButton.addActionListener(e -> saveProfile());
    buttons.add(resetPassword); buttons.add(removeButton);
    buttons.add(testButton); buttons.add(saveButton);
    JPanel right = new JPanel(new BorderLayout(5, 8));
    right.add(pages, BorderLayout.CENTER); right.add(buttons, BorderLayout.SOUTH);

    JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, left, right);
    split.setResizeWeight(0.26);
    add(split, BorderLayout.CENTER);
    JButton close = new JButton("Fechar"); close.addActionListener(e -> dispose());
    JPanel footer = new JPanel(new FlowLayout(FlowLayout.RIGHT)); footer.add(close);
    add(footer, BorderLayout.SOUTH);
    loadProfiles();
    if (profiles.size() != 0) list.setSelectedIndex(0);
    else setEmptyForm(SqlDatabaseType.MYSQL);
  }

  static void show(JFrame frame, Path path, SqlConnectionRegistry registry, Runnable onChanged) {
    new ConnectionManagerDialog(frame, path, registry, onChanged).setVisible(true);
  }

  private static void addField(JPanel panel, int row, String label, JComponent field) {
    GridBagConstraints c = new GridBagConstraints();
    c.gridx = 0; c.gridy = row; c.weightx = 0; c.fill = GridBagConstraints.HORIZONTAL;
    c.anchor = GridBagConstraints.NORTHWEST; c.insets = new Insets(7, 8, 7, 15);
    c.ipadx = 3;
    panel.add(new JLabel(label), c);
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

  private void loadProfiles() {
    profiles.clear();
    try {
      for (JsonNode node : readDocument().path("connections"))
        profiles.addElement(JdbcConnectionProfile.fromJson(node));
    } catch (Exception ex) { error(ex); }
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
      credentialHint.setText(ConnectionVault.has(chosen.name()) ? "Senha protegida salva. Deixe vazio para manter." :
          "Senha não salva. Informe uma para lembrar.");
    } finally { selecting = false; }
  }

  private void chooseDatabase() {
    final JDialog wizard = new JDialog(this, "Selecionar banco de dados", true);
    wizard.setLayout(new BorderLayout(10, 10));
    JLabel title = new JLabel("  Escolha o banco para a nova conexão");
    title.setFont(title.getFont().deriveFont(Font.BOLD, 16f));
    wizard.add(title, BorderLayout.NORTH);
    JPanel cards = new JPanel(new GridLayout(0, 2, 12, 12));
    cards.setBorder(new EmptyBorder(14, 14, 14, 14));
    for (SqlDatabaseType type : SqlDatabaseType.values()) {
      JButton button = new JButton("  " + type.toString());
      button.setFont(button.getFont().deriveFont(Font.BOLD, 15f));
      button.setPreferredSize(new Dimension(195, 75));
      button.addActionListener(e -> { wizard.dispose(); setEmptyForm(type); });
      cards.add(button);
    }
    wizard.add(cards, BorderLayout.CENTER);
    wizard.pack(); wizard.setLocationRelativeTo(this); wizard.setVisible(true);
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
      credentialHint.setText("Informe a senha para guardá-la protegida pelo Windows.");
      name.requestFocusInWindow();
    } finally { selecting = false; }
  }

  private JdbcConnectionProfile formProfile() {
    SqlDatabaseType type = (SqlDatabaseType)databaseType.getSelectedItem();
    int parsedPort;
    try { parsedPort = Integer.parseInt(port.getText().strip()); }
    catch (Exception ex) { throw new IllegalArgumentException("Informe uma porta válida."); }
    String customUrl = jdbcUrl.getText().strip();
    if (type == SqlDatabaseType.SQLSERVER && trustSqlServer.isSelected() && customUrl.isEmpty()) {
      customUrl = "jdbc:sqlserver://" + host.getText().strip() + ":" + parsedPort
          + ";databaseName=" + database.getText().strip() + ";encrypt=true;trustServerCertificate=true";
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
  }

  private void error(Exception ex) {
    Throwable cause = ex;
    while (cause.getCause() != null && cause.getCause() != cause) cause = cause.getCause();
    JOptionPane.showMessageDialog(this, String.valueOf(cause.getMessage()), "LinkSQL — erro", JOptionPane.ERROR_MESSAGE);
  }
}
