package br.com.santysqlstudio;

import org.apache.calcite.sql.SqlDialect;
import org.apache.calcite.sql.SqlDialectFactory;
import org.apache.calcite.sql.SqlDialects;
import org.apache.calcite.sql.SqlNode;
import org.apache.calcite.sql.SqlWriter;
import org.apache.calcite.sql.dialect.FirebirdSqlDialect;

/**
 * Dialeto exclusivo para SQL gerado pelo Calcite em consultas federadas.
 *
 * Firebird 2.x aceita FIRST/SKIP, mas nao OFFSET/FETCH do SQL:2008.
 * A sintaxe no editor continua ANSI (FETCH FIRST ... ROWS ONLY), enquanto
 * apenas o SQL de pushdown gerado para a origem Firebird usa FIRST/SKIP.
 * A limitacao e o offset permanecem parametros JDBC quando aplicavel.
 */
final class FirebirdCompatDialect extends FirebirdSqlDialect {
  static final SqlDialectFactory FACTORY = metadata -> new FirebirdCompatDialect(
      SqlDialects.createContext(metadata)
          .withDatabaseProduct(SqlDialect.DatabaseProduct.FIREBIRD));

  private FirebirdCompatDialect(SqlDialect.Context context) {
    super(context);
  }

  @Override public void unparseTopN(SqlWriter writer, SqlNode offset, SqlNode fetch) {
    if (fetch != null) {
      writer.keyword("FIRST");
      fetch.unparse(writer, -1, -1);
    }
    if (offset != null) {
      writer.keyword("SKIP");
      offset.unparse(writer, -1, -1);
    }
  }

  @Override public void unparseOffsetFetch(SqlWriter writer, SqlNode offset, SqlNode fetch) {
    // FIRST/SKIP ja foi emitido imediatamente apos SELECT.
  }
}
