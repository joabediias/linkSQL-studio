package br.com.linksql;

import org.apache.calcite.sql.SqlCall;
import org.apache.calcite.sql.SqlDialect;
import org.apache.calcite.sql.SqlDialectFactory;
import org.apache.calcite.sql.SqlDialects;
import org.apache.calcite.sql.SqlNode;
import org.apache.calcite.sql.SqlWriter;
import org.apache.calcite.sql.dialect.MysqlSqlDialect;
import org.apache.calcite.sql.fun.SqlStdOperatorTable;

/** Preserva a concatenação ANSI ao delegar uma expressão federada ao MySQL. */
final class MysqlCompatDialect extends MysqlSqlDialect {
  static final SqlDialectFactory FACTORY = metadata -> new MysqlCompatDialect(
      SqlDialects.createContext(metadata)
          .withDatabaseProduct(SqlDialect.DatabaseProduct.MYSQL)
          .withDataTypeSystem(MYSQL_TYPE_SYSTEM));

  MysqlCompatDialect(SqlDialect.Context context) {
    super(context);
  }

  @Override public void unparseCall(SqlWriter writer, SqlCall call, int leftPrec, int rightPrec) {
    if (call.getOperator() == SqlStdOperatorTable.CONCAT) {
      // No MySQL padrão, || é OR. Não dependa do sql_mode da conexão.
      SqlWriter.Frame frame = writer.startFunCall("CONCAT");
      for (SqlNode operand : call.getOperandList()) {
        writer.sep(",");
        operand.unparse(writer, 0, 0);
      }
      writer.endFunCall(frame);
    } else {
      super.unparseCall(writer, call, leftPrec, rightPrec);
    }
  }
}
