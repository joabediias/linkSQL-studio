package br.com.linksql;

import javax.imageio.ImageIO;
import java.awt.Image;
import java.awt.Window;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/** Shared application icon; no platform or database dependencies. */
final class LinkSqlAppIcon {
  private LinkSqlAppIcon() {}
  static void apply(Window window) {
    try (InputStream resource = LinkSqlAppIcon.class.getResourceAsStream("/br/com/linksql/icons/linksql.png")) {
      if (resource == null) return;
      BufferedImage master = ImageIO.read(resource);
      if (master == null) return;
      List<Image> icons = new ArrayList<>();
      for (int size : new int[]{16, 24, 32, 48, 64, 128, 256})
        icons.add(master.getScaledInstance(size, size, Image.SCALE_SMOOTH));
      window.setIconImages(icons);
    } catch (IOException ignored) { /* A missing optional icon must not prevent startup. */ }
  }
}
