package br.com.linksql;

import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import javax.swing.*;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;

/**
 * Interface isolada para pesquisar, marcar favoritos e reutilizar SQL do
 * historico. Retorna somente o SQL selecionado; a criacao da aba pertence ao
 * controlador da janela principal (Main).
 */
final class SqlHistoryDialog {
  private SqlHistoryDialog() {}

  static String choose(JFrame frame, List<String> history, java.util.function.Consumer<Exception> errorHandler) {
    Set<String> favorites;
    try { favorites = SqlFavoritesStore.read(); }
    catch (IOException ex) { errorHandler.accept(ex); return null; }
    if (history.isEmpty() && favorites.isEmpty()) {
      JOptionPane.showMessageDialog(frame, "Ainda não existem consultas no histórico.");
      return null;
    }
    // Favoritos não desaparecem quando consultas antigas deixam o histórico.
    Set<String> combined = new LinkedHashSet<>(favorites);
    combined.addAll(history);
    List<String> all = new ArrayList<>(combined);
    List<String> visible = new ArrayList<>();
    DefaultListModel<String> labels = new DefaultListModel<>();
    JList<String> list = new JList<>(labels);
    list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
    LinkSqlDialogStyle.list(list, 40);
    list.setCellRenderer(new DefaultListCellRenderer() {
      @Override public Component getListCellRendererComponent(JList<?> view, Object value,
          int index, boolean selected, boolean focus) {
        JLabel label = (JLabel) super.getListCellRendererComponent(view, value, index, selected, focus);
        label.setFont(new Font("Segoe UI", Font.PLAIN, 12));
        label.setBorder(BorderFactory.createEmptyBorder(6, 10, 6, 10));
        label.setBackground(selected ? LinkSqlVisualStyle.rowSelection() : LinkSqlVisualStyle.surface());
        label.setForeground(LinkSqlVisualStyle.text());
        label.setIcon(LinkSqlVisualStyle.icon("history", LinkSqlVisualStyle.accent(), 14));
        label.setIconTextGap(9);
        return label;
      }
    });
    JTextArea preview = new JTextArea(10, 70);
    preview.setEditable(false);
    preview.setLineWrap(false);
    preview.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
    preview.setBackground(LinkSqlVisualStyle.editorSurface());
    preview.setForeground(LinkSqlVisualStyle.text());
    preview.setCaretColor(LinkSqlVisualStyle.accent());
    preview.setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));
    JTextField search = new JTextField(28);
    LinkSqlDialogStyle.field(search);
    search.putClientProperty("JTextField.placeholderText", "Pesquisar no texto das consultas...");
    JCheckBox onlyFavorites = new JCheckBox("Somente favoritos");
    JButton favoriteButton = new JButton("Marcar favorito");
    LinkSqlDialogStyle.button(favoriteButton, "history", false);
    favoriteButton.setEnabled(false);
    Runnable updatePreview = () -> {
      int selected = list.getSelectedIndex();
      if (selected < 0 || selected >= visible.size()) {
        preview.setText("");
        favoriteButton.setEnabled(false);
        return;
      }
      String sql = visible.get(selected);
      preview.setText(sql);
      preview.setCaretPosition(0);
      favoriteButton.setEnabled(true);
      favoriteButton.setText(favorites.contains(sql) ? "Remover favorito" : "Marcar favorito");
    };
    Runnable filter = () -> {
      String previous = list.getSelectedIndex() >= 0 && list.getSelectedIndex() < visible.size()
          ? visible.get(list.getSelectedIndex()) : null;
      String term = search.getText().toLowerCase(Locale.ROOT).trim();
      visible.clear();
      labels.clear();
      for (String sql : all) {
        if (onlyFavorites.isSelected() && !favorites.contains(sql)) continue;
        if (!sql.toLowerCase(Locale.ROOT).contains(term)) continue;
        visible.add(sql);
        String oneLine = sql.replaceAll("\\s+", " ").trim();
        String prefix = favorites.contains(sql) ? "★  " : "    ";
        labels.addElement(prefix + (oneLine.length() > 108 ? oneLine.substring(0, 108) + "..." : oneLine));
      }
      int index = previous == null ? -1 : visible.indexOf(previous);
      if (!visible.isEmpty()) list.setSelectedIndex(index >= 0 ? index : 0);
      else updatePreview.run();
    };
    list.addListSelectionListener(e -> { if (!e.getValueIsAdjusting()) updatePreview.run(); });
    search.getDocument().addDocumentListener(new DocumentListener() {
      public void insertUpdate(DocumentEvent e) { filter.run(); }
      public void removeUpdate(DocumentEvent e) { filter.run(); }
      public void changedUpdate(DocumentEvent e) { filter.run(); }
    });
    onlyFavorites.addActionListener(e -> filter.run());
    favoriteButton.addActionListener(e -> {
      int index = list.getSelectedIndex();
      if (index < 0 || index >= visible.size()) return;
      String sql = visible.get(index);
      boolean wasFavorite = favorites.contains(sql);
      if (wasFavorite) favorites.remove(sql);
      else favorites.add(sql);
      try { SqlFavoritesStore.write(favorites); }
      catch (IOException ex) {
        if (wasFavorite) favorites.add(sql);
        else favorites.remove(sql);
        errorHandler.accept(ex);
      }
      filter.run();
    });
    JPanel tools = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 6));
    tools.setOpaque(false);
    tools.add(LinkSqlDialogStyle.caption("PESQUISA"));
    tools.add(search);
    tools.add(onlyFavorites);
    tools.add(favoriteButton);
    JPanel top = new JPanel(new BorderLayout(0, 12));
    top.setOpaque(false);
    top.add(LinkSqlDialogStyle.heading("Histórico e favoritos",
        "Pesquise, organize e reutilize consultas executadas anteriormente.", "history"), BorderLayout.NORTH);
    top.add(tools, BorderLayout.CENTER);
    JPanel listPanel = LinkSqlDialogStyle.section("CONSULTAS DISPONÍVEIS", LinkSqlDialogStyle.scroll(list));
    JPanel previewPanel = LinkSqlDialogStyle.section("PRÉVIA DO SQL", LinkSqlDialogStyle.scroll(preview));
    JSplitPane split = new JSplitPane(JSplitPane.VERTICAL_SPLIT, listPanel, previewPanel);
    LinkSqlVisualStyle.splitter(split);
    split.setResizeWeight(.48);
    JPanel panel = new JPanel(new BorderLayout(0, 12));
    LinkSqlDialogStyle.styleOptionPanel(panel);
    panel.add(top, BorderLayout.NORTH);
    panel.add(split, BorderLayout.CENTER);
    panel.setPreferredSize(new Dimension(890, 570));
    filter.run();
    int choice = JOptionPane.showConfirmDialog(frame, panel,
        "LinkSQL — Histórico SQL e favoritos",
        JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
    if (choice == JOptionPane.OK_OPTION && list.getSelectedIndex() >= 0)
      return visible.get(list.getSelectedIndex());
    return null;
  }

}
