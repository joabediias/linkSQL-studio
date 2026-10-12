package br.com.santysqlstudio;

import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.util.Vector;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;
import javax.swing.table.DefaultTableModel;

/** Converte ResultSet em modelo somente leitura, sem eventos por linha. */
final class SqlResultModels {
  private SqlResultModels() {}

  // Monta o modelo fora da EDT sem disparar um evento Swing a cada registro.
  // Especialmente importante quando Linhas/pagina = 100.000 ou Todas.
  static DefaultTableModel readResultModel(ResultSet rs, int pageSize,
      java.util.function.BooleanSupplier cancelled) throws SQLException {
    ResultSetMetaData meta = rs.getMetaData();
    int width = meta.getColumnCount();
    Vector<String> headers = new Vector<>(width);
    for (int i = 1; i <= width; i++) headers.add(meta.getColumnLabel(i));
    Vector<Vector<Object>> rows = new Vector<>();
    while (!cancelled.getAsBoolean() && !Thread.currentThread().isInterrupted()
        && (pageSize == 0 || rows.size() < pageSize) && rs.next()) {
      Vector<Object> row = new Vector<>(width);
      for (int i = 1; i <= width; i++) row.add(rs.getObject(i));
      rows.add(row);
    }
    if (cancelled.getAsBoolean() || Thread.currentThread().isInterrupted())
      throw new CancellationException("Consulta cancelada");
    return new DefaultTableModel(rows, headers) {
      public boolean isCellEditable(int row, int col) { return false; }
    };
  }

}
