package br.com.linksql;

import java.awt.*;

/** FlowLayout whose preferred height includes wrapped rows, including toolbars. */
final class LinkSqlWrapLayout extends FlowLayout {
  LinkSqlWrapLayout(int alignment, int horizontalGap, int verticalGap) {
    super(alignment, horizontalGap, verticalGap);
  }

  @Override public Dimension preferredLayoutSize(Container target) { return measure(target, false); }
  @Override public Dimension minimumLayoutSize(Container target) { return measure(target, true); }

  private Dimension measure(Container target, boolean minimum) {
    synchronized (target.getTreeLock()) {
      int width = target.getWidth();
      Container ancestor = target.getParent();
      while (width <= 0 && ancestor != null) {
        width = ancestor.getWidth();
        ancestor = ancestor.getParent();
      }
      if (width <= 0) width = Integer.MAX_VALUE;
      Insets insets = target.getInsets();
      int horizontal = insets.left + insets.right + 2 * getHgap();
      int available = Math.max(1, width - horizontal);
      int rowWidth = 0, rowHeight = 0, usedWidth = 0, usedHeight = 0;
      for (Component child : target.getComponents()) {
        if (!child.isVisible()) continue;
        Dimension size = minimum ? child.getMinimumSize() : child.getPreferredSize();
        int gap = rowWidth == 0 ? 0 : getHgap();
        if (rowWidth > 0 && rowWidth + gap + size.width > available) {
          usedWidth = Math.max(usedWidth, rowWidth);
          usedHeight += rowHeight + getVgap();
          rowWidth = 0; rowHeight = 0; gap = 0;
        }
        rowWidth += gap + size.width;
        rowHeight = Math.max(rowHeight, size.height);
      }
      usedWidth = Math.max(usedWidth, rowWidth);
      usedHeight += rowHeight;
      return new Dimension(usedWidth + horizontal,
          usedHeight + insets.top + insets.bottom + 2 * getVgap());
    }
  }
}
