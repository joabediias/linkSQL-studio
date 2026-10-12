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

  static String csv(String value, char separator, int quoteMode) {
    if (quoteMode == 2) return value;
    boolean required = value.indexOf(separator) >= 0 || value.indexOf('"') >= 0
        || value.indexOf('\n') >= 0 || value.indexOf('\r') >= 0;
    if (quoteMode == 1 && !required) return value;
    return "\"" + value.replace("\"", "\"\"") + "\"";
  }
}
