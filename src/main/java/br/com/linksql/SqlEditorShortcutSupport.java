package br.com.linksql;

import javax.swing.*;
import javax.swing.text.JTextComponent;
import java.awt.event.ActionEvent;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.util.Objects;

/** Atalhos do editor, incluindo equivalentes para teclados ABNT2 e teclado numerico. */
final class SqlEditorShortcutSupport {
  static final String COMMENT_ACTION = "linksql.toggleComment";

  private SqlEditorShortcutSupport() {}

  static void installCommentShortcut(JTextComponent editor, Runnable toggle) {
    Objects.requireNonNull(editor);
    Objects.requireNonNull(toggle);
    editor.getActionMap().put(COMMENT_ACTION, new AbstractAction() {
      @Override public void actionPerformed(ActionEvent event) { toggle.run(); }
    });
    InputMap input = editor.getInputMap(JComponent.WHEN_FOCUSED);
    int ctrl = InputEvent.CTRL_DOWN_MASK;
    // Tecla '/' principal, inclusive layouts nos quais Shift tambem esta pressionado.
    input.put(KeyStroke.getKeyStroke(KeyEvent.VK_SLASH, ctrl), COMMENT_ACTION);
    input.put(KeyStroke.getKeyStroke(KeyEvent.VK_SLASH, ctrl | InputEvent.SHIFT_DOWN_MASK), COMMENT_ACTION);
    // '/' do teclado numerico.
    input.put(KeyStroke.getKeyStroke(KeyEvent.VK_DIVIDE, ctrl), COMMENT_ACTION);
    // Alguns layouts brasileiros emitem Ctrl+Shift+7 para o caractere '/'.
    input.put(KeyStroke.getKeyStroke(KeyEvent.VK_7, ctrl | InputEvent.SHIFT_DOWN_MASK), COMMENT_ACTION);
  }
}
