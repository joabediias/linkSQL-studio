package br.com.linksql;

import javax.imageio.ImageIO;
import javax.swing.Icon;
import java.awt.Component;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Image;
import java.awt.RenderingHints;
import java.awt.Window;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/** Shared application icon; no platform or database dependencies. */
final class LinkSqlAppIcon {
  private static final BufferedImage MASTER = load();
  private LinkSqlAppIcon() {}
  private static BufferedImage load() {
    try (InputStream resource = LinkSqlAppIcon.class.getResourceAsStream("/br/com/linksql/icons/linksql.png")) {
      return resource == null ? null : ImageIO.read(resource);
    } catch (IOException ignored) { return null; }
  }
  static void apply(Window window) {
    if (MASTER == null) return;
    List<Image> icons = new ArrayList<>();
    for (int size : new int[]{16, 24, 32, 48, 64, 128, 256})
      icons.add(MASTER.getScaledInstance(size, size, Image.SCALE_SMOOTH));
    window.setIconImages(icons);
  }
  static Icon icon(int size) {
    return MASTER == null ? LinkSqlVisualStyle.icon("database", LinkSqlVisualStyle.accent(), size)
        : new AppLogo(MASTER, size);
  }
  // Desenha a imagem original na escala da tela, preservando transparência e nitidez.
  private record AppLogo(BufferedImage image, int size) implements Icon {
    @Override public int getIconWidth() { return size; }
    @Override public int getIconHeight() { return size; }
    @Override public void paintIcon(Component component, Graphics graphics, int x, int y) {
      Graphics2D g = (Graphics2D) graphics.create();
      try {
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        g.drawImage(image, x, y, size, size, null);
      } finally { g.dispose(); }
    }
  }
}
