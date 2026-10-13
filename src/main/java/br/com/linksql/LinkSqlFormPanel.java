package br.com.linksql;

import javax.swing.*;
import java.awt.*;

/** Keeps a form as wide as its viewport, allowing only vertical scrolling. */
final class LinkSqlFormPanel extends JPanel implements Scrollable {
  LinkSqlFormPanel(LayoutManager layout) { super(layout); }
  @Override public Dimension getPreferredScrollableViewportSize() { return getPreferredSize(); }
  @Override public int getScrollableUnitIncrement(Rectangle visible, int orientation, int direction) { return 18; }
  @Override public int getScrollableBlockIncrement(Rectangle visible, int orientation, int direction) {
    return Math.max(18, visible.height - 36);
  }
  @Override public boolean getScrollableTracksViewportWidth() { return true; }
  @Override public boolean getScrollableTracksViewportHeight() { return false; }
}
