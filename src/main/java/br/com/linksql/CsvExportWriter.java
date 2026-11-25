package br.com.linksql;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import javax.swing.JTable;

/** Codificação CSV independente da escolha de arquivo e das opções Swing. */
final class CsvExportWriter {
  private CsvExportWriter() {}

  static void write(JTable table, Path path, Charset charset, char separator,
                    boolean includeHeader, boolean includeQuote) throws IOException {
    int quoteMode = includeQuote ? 0 : 1;
    try (BufferedWriter out = Files.newBufferedWriter(path, charset)) {
      if (includeHeader) {
        for (int c = 0; c < table.getColumnCount(); c++) {
          if (c > 0) out.write(separator);
          out.write(csv(table.getColumnName(c), separator, quoteMode));
        }
        out.newLine();
      }
      for (int r = 0; r < table.getRowCount(); r++) {
        for (int c = 0; c < table.getColumnCount(); c++) {
          if (c > 0) out.write(separator);
          Object value = table.getValueAt(r, c);
          out.write(csv(value == null ? "" : value.toString(), separator, quoteMode));
        }
        out.newLine();
      }
    }
  }

  /** Exporta sem materializar linhas no heap; arquivo final só é publicado após sucesso. */
  static long write(java.sql.ResultSet rows, Path path, Charset charset, char separator,
      boolean includeHeader, boolean includeQuote, java.util.function.BooleanSupplier cancelled)
      throws IOException, java.sql.SQLException {
    Path target = path.toAbsolutePath();
    Path temp = Files.createTempFile(target.getParent(), ".linksql-export-", ".tmp");
    long count = 0;
    int mode = includeQuote ? 0 : 1;
    try {
      try (BufferedWriter out = Files.newBufferedWriter(temp, charset)) {
        var meta = rows.getMetaData();
        int width = meta.getColumnCount();
        if (includeHeader) {
          for (int i = 1; i <= width; i++) {
            if (i > 1) out.write(separator);
            out.write(csv(meta.getColumnLabel(i), separator, mode));
          }
          out.newLine();
        }
        while (!cancelled.getAsBoolean() && !Thread.currentThread().isInterrupted() && rows.next()) {
          for (int i = 1; i <= width; i++) {
            if (i > 1) out.write(separator);
            String value = rows.getString(i);
            out.write(csv(value == null ? "" : value, separator, mode));
          }
          out.newLine();
          count++;
        }
      }
      if (cancelled.getAsBoolean() || Thread.currentThread().isInterrupted())
        throw new java.util.concurrent.CancellationException("Exportação cancelada");
      try {
        Files.move(temp, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING,
            java.nio.file.StandardCopyOption.ATOMIC_MOVE);
      } catch (java.nio.file.AtomicMoveNotSupportedException ex) {
        Files.move(temp, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
      }
      return count;
    } finally { Files.deleteIfExists(temp); }
  }

  static String csv(String value, char separator, int quoteMode) {
    if (quoteMode == 2) return value;
    boolean required = value.indexOf(separator) >= 0 || value.indexOf('"') >= 0
        || value.indexOf('\n') >= 0 || value.indexOf('\r') >= 0;
    if (quoteMode == 1 && !required) return value;
    return "\"" + value.replace("\"", "\"\"") + "\"";
  }
}
