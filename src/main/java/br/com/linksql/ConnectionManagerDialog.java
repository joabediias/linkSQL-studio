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
  private final JCheckBox trustSqlServer = new JCheckBox("<html>Confiar no certificado do SQL Server<br>(somente ambientes confiáveis)</html>");
  private final JCheckBox legacyFirebird = new JCheckBox("<html>Compatibilidade Firebird antigo<br>(enableProtocol=*)</html>");
  private final JLabel credentialHint = new JLabel("Senha ainda não cadastrada");
  private final JButton saveButton = new JButton("Salvar conexão"), testButton = new JButton("Testar conexão"),
      removeButton = new JButton("Excluir"), resetPassword = new JButton("Esquecer senha"),
      duplicateButton = new JButton("Duplicar");
  private String originalName;
  private boolean selecting;
  private final JPanel rows = new LinkSqlFormPanel(null);
  private final JLabel totalValue = new JLabel(), technologyValue = new JLabel(), readOnlyValue = new JLabel();
  private final JPanel editorBody = new JPanel(new BorderLayout(0, 14));
  private JDialog editorDialog;

  private ConnectionManagerDialog(JFrame owner, Path file, SqlConnectionRegistry registry, Runnable onChanged) {
    super(owner, "LinkSQL — Gerenciar conexões", true);
    this.file = file; this.registry = registry; this.onChanged = onChanged;
    setMinimumSize(new Dimension(920, 640));
    setSize(1160, 760);
    setLocationRelativeTo(owner);
    setLayout(new BorderLayout(0, 16));
    LinkSqlDialogStyle.window(this);
    ((JComponent) getContentPane()).setBorder(new EmptyBorder(26, 32, 20, 32));

    JButton addButton = new JButton("Nova conexão");
    LinkSqlDialogStyle.button(addButton, "plus", true);
    addButton.addActionListener(e -> chooseDatabase());
    JPanel heading = new JPanel(new BorderLayout(20, 0));
    heading.setOpaque(false);
    heading.add(LinkSqlDialogStyle.heading("Gerenciador de conexões",
        "Cadastre, teste e organize as conexões JDBC usadas nas consultas nativas e federadas.", "database"), BorderLayout.CENTER);
    JPanel newAction = new JPanel(new GridBagLayout());
    newAction.setOpaque(false); newAction.add(addButton);
    heading.add(newAction, BorderLayout.EAST);
    add(heading, BorderLayout.NORTH);

    JPanel center = new JPanel(new BorderLayout(0, 12));
    center.setOpaque(false);
    JPanel top = new JPanel(new BorderLayout(0, 12)); top.setOpaque(false);
    JPanel stats = new JPanel(new GridLayout(1, 3, 14, 0)); stats.setOpaque(false);
    stats.add(statCard("CONEXÕES SALVAS", totalValue));
    stats.add(statCard("TECNOLOGIAS", technologyValue));
    stats.add(statCard("PERFIS SOMENTE LEITURA", readOnlyValue));
    top.add(stats, BorderLayout.NORTH);

    list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
    list.addListSelectionListener(this::selected);
    LinkSqlDialogStyle.field(search);
    search.putClientProperty("JTextField.placeholderText", "Pesquisar conexões...");
    search.setToolTipText("Buscar por nome, servidor ou banco, sem conectar aos servidores.");
    search.setPreferredSize(new Dimension(240, 36));
    search.getDocument().addDocumentListener(new DocumentListener() {
      public void insertUpdate(DocumentEvent e) { refreshFilter(); }
      public void removeUpdate(DocumentEvent e) { refreshFilter(); }
      public void changedUpdate(DocumentEvent e) { refreshFilter(); }
    });
    filterType.addItem("Todos os bancos");
    for (SqlDatabaseType type : SqlDatabaseType.values()) filterType.addItem(type.toString());
    filterType.addActionListener(e -> refreshFilter());
    sorting.addActionListener(e -> refreshFilter());
    LinkSqlDialogStyle.field(sorting);
    sorting.setPreferredSize(new Dimension(180, 36));
    JPanel filtering = new JPanel(new BorderLayout(12, 0)); filtering.setOpaque(false);
    filtering.add(search, BorderLayout.CENTER); filtering.add(sorting, BorderLayout.EAST);
    JPanel chips = LinkSqlVisualStyle.actionRow(FlowLayout.LEFT);
    ButtonGroup filters = new ButtonGroup();
    for (int i = 0; i < filterType.getItemCount(); i++) {
      final int index = i;
      JToggleButton chip = new JToggleButton(i == 0 ? "Todas" : filterType.getItemAt(i));
      chip.setFont(new Font("Segoe UI", Font.PLAIN, 12));
      chip.setMargin(new Insets(6, 11, 6, 11));
      chip.setForeground(LinkSqlVisualStyle.subtleText());
      chip.setBackground(LinkSqlVisualStyle.background());
      chip.putClientProperty("JButton.selectedBackground", LinkSqlVisualStyle.rowSelection());
      chip.putClientProperty("JButton.selectedForeground", LinkSqlVisualStyle.accent());
      if (i > 0) chip.setIcon(LinkSqlVisualStyle.icon("status", LinkSqlVisualStyle.databaseColor(chip.getText()), 10));
      chip.addActionListener(e -> filterType.setSelectedIndex(index));
      filters.add(chip); chips.add(chip);
      if (i == 0) chip.setSelected(true);
    }
    JPanel filterRows = new JPanel(new BorderLayout(0, 8)); filterRows.setOpaque(false);
    filterRows.add(filtering, BorderLayout.NORTH); filterRows.add(chips, BorderLayout.SOUTH);
    top.add(filterRows, BorderLayout.CENTER);
    center.add(top, BorderLayout.NORTH);
    rows.setLayout(new BoxLayout(rows, BoxLayout.Y_AXIS));
    rows.setOpaque(false);
    JScrollPane scroll = LinkSqlDialogStyle.scroll(rows);
    scroll.setBorder(null);
    scroll.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
    scroll.getViewport().setBackground(LinkSqlVisualStyle.background());
    center.add(scroll, BorderLayout.CENTER);
    add(center, BorderLayout.CENTER);
    JPanel footer = new JPanel(new BorderLayout()); footer.setOpaque(false);
    listCount.setForeground(LinkSqlVisualStyle.subtleText());
    listCount.setFont(new Font("Segoe UI", Font.PLAIN, 12));
    footer.add(listCount, BorderLayout.WEST);
    JButton close = new JButton("Fechar");
    LinkSqlDialogStyle.button(close, "close", false);
    close.addActionListener(e -> dispose());
    JPanel actions = LinkSqlDialogStyle.footer(); actions.add(close);
    footer.add(actions, BorderLayout.EAST); add(footer, BorderLayout.SOUTH);
    buildEditor();
    loadProfiles();
    if (!profiles.isEmpty()) list.setSelectedIndex(0);
    else setEmptyForm(SqlDatabaseType.MYSQL);
  }

  private static JPanel statCard(String title, JLabel value) {
    JPanel card = new RoundedCard(new BorderLayout(0, 10));
    card.setBorder(new EmptyBorder(13, 18, 13, 18));
    card.add(LinkSqlVisualStyle.smallLabel(title), BorderLayout.NORTH);
    value.setFont(new Font("Segoe UI", Font.BOLD, 24));
    value.setForeground(LinkSqlVisualStyle.text());
    card.add(value, BorderLayout.CENTER);
    return card;
  }

  /** Actual buttons rather than interactive-looking controls painted by a list renderer. */
  private void rebuildRows() {
    totalValue.setText(String.valueOf(allProfiles.size()));
    technologyValue.setText(String.valueOf(allProfiles.stream().map(JdbcConnectionProfile::type).distinct().count()));
    readOnlyValue.setText(String.valueOf(allProfiles.stream().filter(JdbcConnectionProfile::readOnly).count()));
    rows.removeAll();
    for (int i = 0; i < profiles.size(); i++) {
      JdbcConnectionProfile profile = profiles.get(i);
      JPanel card = new RoundedCard(new BorderLayout(14, 0));
      card.setBorder(new EmptyBorder(12, 16, 12, 12));
      card.setAlignmentX(Component.LEFT_ALIGNMENT);
      card.setPreferredSize(new Dimension(720, 72));
      card.setMaximumSize(new Dimension(Integer.MAX_VALUE, 72));
      JPanel marker = new JPanel();
      marker.setBackground(LinkSqlVisualStyle.databaseColor(profile.type().toString()));
      marker.setPreferredSize(new Dimension(4, 42));
      card.add(marker, BorderLayout.WEST);
      JButton details = new JButton("<html><b>" + escape(profile.name())
          + "</b><br><span style='font-size:10px;color:" + cssColor(LinkSqlVisualStyle.subtleText()) + "'>"
          + escape(profile.type() + "  ·  " + connectionLocation(profile) + "  ·  " + profile.user()) + "</span></html>");
      details.setHorizontalAlignment(SwingConstants.LEFT);
      details.setFont(new Font("Segoe UI", Font.PLAIN, 14));
      details.setForeground(LinkSqlVisualStyle.text());
      details.putClientProperty("JButton.buttonType", "toolBarButton");
      details.setMargin(new Insets(3, 3, 3, 3));
      details.setToolTipText("Editar " + profile.name());
      details.addActionListener(e -> { list.setSelectedValue(profile, false); openEditor(); });
      card.add(details, BorderLayout.CENTER);
      JPanel actions = new JPanel(new FlowLayout(FlowLayout.RIGHT, 4, 6)); actions.setOpaque(false);
      actions.add(rowAction(profile, "Testar", "play", () -> testConnection()));
      actions.add(rowAction(profile, "Editar", "edit", () -> openEditor()));
      actions.add(rowAction(profile, "Duplicar", "copy", () -> { duplicateProfile(); openEditor(); }));
      actions.add(rowAction(profile, "Excluir", "trash", () -> deleteProfile()));
      card.add(actions, BorderLayout.EAST);
      rows.add(card); rows.add(Box.createVerticalStrut(8));
    }
    if (profiles.isEmpty()) {
      JLabel empty = LinkSqlVisualStyle.hint(allProfiles.isEmpty()
          ? "Nenhuma conexão cadastrada. Use Nova conexão para começar."
          : "Nenhuma conexão corresponde aos filtros.");
      empty.setBorder(new EmptyBorder(20, 10, 20, 10)); rows.add(empty);
    }
    rows.revalidate(); rows.repaint();
  }

  private JButton rowAction(JdbcConnectionProfile profile, String label, String glyph, Runnable action) {
    JButton button = new JButton(label.equals("Testar") ? label : "");
    LinkSqlVisualStyle.button(button, button.getText(), glyph, "muted");
    button.setFont(new Font("Segoe UI", Font.PLAIN, 12));
    button.setMargin(new Insets(7, 7, 7, 7));
    button.setToolTipText(label + " " + profile.name());
    button.getAccessibleContext().setAccessibleName(label + " " + profile.name());
    button.addActionListener(e -> { list.setSelectedValue(profile, false); action.run(); });
    return button;
  }

  private static String escape(String value) {
    return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
  }

  private static String cssColor(Color value) {
    return String.format("#%02x%02x%02x", value.getRed(), value.getGreen(), value.getBlue());
  }

  private static final class RoundedCard extends JPanel {
    RoundedCard(LayoutManager layout) { super(layout); setOpaque(false); }
    @Override protected void paintComponent(Graphics graphics) {
      Graphics2D g = (Graphics2D) graphics.create();
      g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
      g.setColor(LinkSqlVisualStyle.surface()); g.fillRoundRect(0, 0, getWidth()-1, getHeight()-1, 16, 16);
      g.setColor(LinkSqlVisualStyle.border()); g.drawRoundRect(0, 0, getWidth()-1, getHeight()-1, 16, 16);
      g.dispose(); super.paintComponent(graphics);
    }
  }

  private void buildEditor() {
    editorBody.setOpaque(false);
    JTabbedPane pages = new JTabbedPane(); LinkSqlDialogStyle.tabs(pages);
    JPanel basicFields = new LinkSqlFormPanel(null);
    basicFields.setBackground(LinkSqlVisualStyle.background());
    basicFields.setLayout(new BoxLayout(basicFields, BoxLayout.Y_AXIS));
    basicFields.setBorder(new EmptyBorder(10, 4, 6, 4));
    basicFields.add(fieldGroup("Nome da conexão", name));
    JPanel connection = new JPanel(new GridBagLayout()); connection.setOpaque(false); connection.setAlignmentX(Component.LEFT_ALIGNMENT);
    GridBagConstraints c = new GridBagConstraints(); c.gridy = 0; c.fill = GridBagConstraints.HORIZONTAL;
    c.weightx = .42; c.gridx = 0; c.insets = new Insets(0, 0, 0, 12);
    connection.add(fieldGroup("Tecnologia", databaseType), c);
    c.weightx = .38; c.gridx = 1;
    connection.add(fieldGroup("Servidor / Host", host), c);
    c.weightx = .20; c.gridx = 2; c.insets = new Insets(0, 0, 0, 0);
    connection.add(fieldGroup("Porta", port), c);
    basicFields.add(connection);
    basicFields.add(fieldPair("Banco / serviço / arquivo", database, "Usuário", username));
    basicFields.add(fieldGroup("Senha", password));
    credentialHint.setFont(new Font("Segoe UI", Font.PLAIN, 12));
    credentialHint.setForeground(LinkSqlVisualStyle.subtleText());
    basicFields.add(fieldGroup("", credentialHint));
    JPanel security = new RoundedCard(new BorderLayout(0, 8));
    security.setBorder(new EmptyBorder(10, 12, 10, 12));
    remember.setText("Guardar senha protegida pelo Windows"); remember.setOpaque(false);
    security.add(remember, BorderLayout.NORTH);
    JPanel policy = new JPanel(new BorderLayout(12, 0)); policy.setOpaque(false);
    policy.add(LinkSqlDialogStyle.caption("Lembrar por"), BorderLayout.WEST);
    policy.add(duration, BorderLayout.CENTER); security.add(policy, BorderLayout.CENTER);
    basicFields.add(fieldGroup("", security));
    remember.addActionListener(e -> duration.setEnabled(remember.isSelected()));
    JPanel advanced = new LinkSqlFormPanel(new GridBagLayout());
    advanced.setBackground(LinkSqlVisualStyle.background());
    addField(advanced, 0, "Schema (opcional)", schema);
    addField(advanced, 2, "URL JDBC (opcional)", jdbcUrl);
    addField(advanced, 3, "SQL Server", trustSqlServer);
    addField(advanced, 4, "Firebird", legacyFirebird);
    trustSqlServer.setToolTipText("Ativa trustServerCertificate=true. Evite em produção sem uma justificativa.");
    legacyFirebird.setToolTipText("Habilita os protocolos antigos do Jaybird quando o servidor Firebird exigir.");
    addField(advanced, 5, "", LinkSqlDialogStyle.note("O LinkSQL utiliza somente leitura por padrão. "
        + "Ao usar URL JDBC personalizada, confira as propriedades SSL/TLS de cada servidor. "
        + "A senha nunca será escrita no arquivo de conexões."));
    for (JPanel panel : new JPanel[]{basicFields, advanced}) {
      JScrollPane scroll = LinkSqlDialogStyle.scroll(panel);
      scroll.setBorder(null); scroll.getViewport().setBackground(LinkSqlVisualStyle.background());
      scroll.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
      pages.addTab(panel == basicFields ? "Conexão" : "Avançado", scroll);
    }
    for (JComponent field : new JComponent[]{name, host, port, database, schema, username, password,
        passwordEnv, jdbcUrl, databaseType, duration}) LinkSqlDialogStyle.field(field);
    name.putClientProperty("JTextField.placeholderText", "Ex.: CRM Produção");
    databaseType.addActionListener(e -> {
      if (!selecting && databaseType.getSelectedItem() instanceof SqlDatabaseType t) {
        if (port.getText().isBlank() || isDefaultPort(port.getText())) port.setText(String.valueOf(t.defaultPort()));
        if (username.getText().isBlank()) username.setText(defaultUser(t));
        updateDatabaseSpecificFields();
      }
    });
    editorBody.add(pages, BorderLayout.CENTER);
    JPanel buttons = LinkSqlDialogStyle.footer();
    LinkSqlDialogStyle.button(saveButton, "save", true);
    LinkSqlDialogStyle.button(testButton, "play", false);
    LinkSqlDialogStyle.destructiveButton(resetPassword, "settings");
    resetPassword.addActionListener(e -> forgetPassword());
    testButton.addActionListener(e -> testConnection());
    saveButton.addActionListener(e -> saveProfile());
    JButton cancel = new JButton("Cancelar"); LinkSqlDialogStyle.button(cancel, "close", false);
    cancel.addActionListener(e -> closeEditor());
    JPanel bottom = new JPanel(new BorderLayout(6, 8)); bottom.setOpaque(false);
    JPanel testActions = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 5)); testActions.setOpaque(false);
    testActions.add(testButton); bottom.add(testActions, BorderLayout.WEST);
    buttons.add(cancel); buttons.add(saveButton); bottom.add(buttons, BorderLayout.EAST);
    JPanel extra = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0)); extra.setOpaque(false);
    extra.add(resetPassword); bottom.add(extra, BorderLayout.NORTH);
    editorBody.add(bottom, BorderLayout.SOUTH);
  }

  private void openEditor() {
    if (editorDialog != null) return;
    editorDialog = new JDialog(this, originalName == null ? "Nova conexão JDBC" : "Editar conexão JDBC", true);
    editorDialog.setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
    editorDialog.setLayout(new BorderLayout(0, 12));
    LinkSqlDialogStyle.window(editorDialog);
    ((JComponent) editorDialog.getContentPane()).setBorder(new EmptyBorder(20, 24, 18, 24));
    editorDialog.add(LinkSqlDialogStyle.heading(originalName == null ? "Nova conexão JDBC" : "Editar conexão JDBC",
        "As senhas salvas ficam protegidas pelo Windows, fora do arquivo de conexões.", "database"), BorderLayout.NORTH);
    resetPassword.setVisible(originalName != null);
    saveButton.setText(originalName == null ? "Adicionar conexão" : "Salvar alterações");
    editorDialog.add(editorBody, BorderLayout.CENTER);
    editorDialog.setMinimumSize(new Dimension(680, 610));
    editorDialog.setSize(720, 650);
    editorDialog.setLocationRelativeTo(this);
    JRootPane root = editorDialog.getRootPane();
    root.setDefaultButton(saveButton);
    root.registerKeyboardAction(e -> closeEditor(), KeyStroke.getKeyStroke("ESCAPE"), JComponent.WHEN_IN_FOCUSED_WINDOW);
    editorDialog.addWindowListener(new java.awt.event.WindowAdapter() {
      @Override public void windowClosing(java.awt.event.WindowEvent e) { closeEditor(); }
      @Override public void windowClosed(java.awt.event.WindowEvent e) { editorDialog = null; password.setText(""); }
    });
    SwingUtilities.invokeLater(() -> name.requestFocusInWindow());
    editorDialog.setVisible(true);
  }

  private void closeEditor() {
    if (!saveButton.isEnabled()) return;
    if (editorDialog != null) { editorDialog.dispose(); editorDialog = null; }
    password.setText("");
  }

  static void show(JFrame frame, Path path, SqlConnectionRegistry registry, Runnable onChanged) {
    show(frame, path, registry, onChanged, null);
  }

  static void show(JFrame frame, Path path, SqlConnectionRegistry registry,
                   Runnable onChanged, String preferredName) {
    ConnectionManagerDialog dialog = new ConnectionManagerDialog(frame, path, registry, onChanged);
    if (preferredName != null) dialog.selectName(preferredName);
    if (preferredName != null) SwingUtilities.invokeLater(dialog::openEditor);
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

  private static JPanel fieldGroup(String caption, JComponent field) {
    JPanel group = new JPanel(new BorderLayout(0, 6));
    group.setOpaque(false);
    group.setAlignmentX(Component.LEFT_ALIGNMENT);
    group.setBorder(new EmptyBorder(5, 0, 7, 0));
    if (!caption.isBlank()) group.add(LinkSqlDialogStyle.caption(caption), BorderLayout.NORTH);
    group.add(field, BorderLayout.CENTER);
    field.setMinimumSize(new Dimension(60, field.getPreferredSize().height));
    return group;
  }

  private static JPanel fieldPair(String first, JComponent left, String second, JComponent right) {
    JPanel pair = new JPanel(new GridLayout(1, 2, 14, 0));
    pair.setOpaque(false);
    pair.setAlignmentX(Component.LEFT_ALIGNMENT);
    pair.add(fieldGroup(first, left));
    pair.add(fieldGroup(second, right));
    return pair;
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
    listCount.setText(matches.size() + " de " + allProfiles.size() + " conexões");
    rebuildRows();
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
    setEmptyForm(SqlDatabaseType.MYSQL);
    openEditor();
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
        JOptionPane.showMessageDialog(messageOwner(), "Informe a senha para testar a conexão."); return;
      }
      final String toUse = pass;
      testButton.setEnabled(false);
      new SwingWorker<String, Void>() {
        protected String doInBackground() throws Exception { return registry.test(profile, toUse); }
        protected void done() {
          testButton.setEnabled(true);
          try { JOptionPane.showMessageDialog(messageOwner(), "Conectado: " + get(),
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
            closeEditor();
            JOptionPane.showMessageDialog(messageOwner(),
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
    if (JOptionPane.showConfirmDialog(messageOwner(), "Excluir a conexão " + originalName + " e sua senha salva?",
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
    if (JOptionPane.showConfirmDialog(messageOwner(), "Remover a senha protegida de " + originalName + "?",
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

  private Component messageOwner() { return editorDialog == null ? this : editorDialog; }

  private void error(Exception ex) {
    Throwable cause = ex;
    while (cause.getCause() != null && cause.getCause() != cause) cause = cause.getCause();
    JOptionPane.showMessageDialog(messageOwner(), String.valueOf(cause.getMessage()), "LinkSQL — erro", JOptionPane.ERROR_MESSAGE);
  }
}
