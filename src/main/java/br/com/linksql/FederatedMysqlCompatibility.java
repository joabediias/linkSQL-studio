package br.com.linksql;

import java.io.IOException;
import java.io.Reader;
import java.io.StringReader;
import java.io.UncheckedIOException;
import java.sql.Timestamp;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.StringJoiner;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.apache.calcite.runtime.SqlFunctions;
import org.apache.calcite.schema.SchemaPlus;
import org.apache.calcite.schema.impl.ScalarFunctionImpl;
import org.apache.calcite.sql.SqlBasicTypeNameSpec;
import org.apache.calcite.sql.SqlCall;
import org.apache.calcite.sql.SqlDataTypeSpec;
import org.apache.calcite.sql.SqlFunctionCategory;
import org.apache.calcite.sql.SqlIdentifier;
import org.apache.calcite.sql.SqlNode;
import org.apache.calcite.sql.SqlNodeList;
import org.apache.calcite.sql.SqlUnresolvedFunction;
import org.apache.calcite.sql.fun.SqlStdOperatorTable;
import org.apache.calcite.sql.parser.SqlAbstractParserImpl;
import org.apache.calcite.sql.parser.SqlParserImplFactory;
import org.apache.calcite.sql.parser.impl.SqlParserImpl;
import org.apache.calcite.sql.type.SqlTypeName;
import org.apache.calcite.sql.util.SqlShuttle;

/** Extensões MySQL usadas no SQL federado que o Calcite não registra por padrão. */
public final class FederatedMysqlCompatibility implements SqlParserImplFactory {
  private static final String CONCAT_WS_IMPL = "LINKSQL_CONCAT_WS";
  private static final Pattern REGEXP = Pattern.compile(
      "'(?:''|[^'])*'|\"(?:\"\"|[^\"])*\"|`(?:``|[^`])*`|/\\*[\\s\\S]*?\\*/|--[^\\r\\n]*"
          + "|(?<![\\p{L}\\p{N}_$])REGEXP(?![\\p{L}\\p{N}_$])",
      Pattern.CASE_INSENSITIVE);
  private static final Map<Character, DateTimeFormatter> DATE_MASKS = Map.ofEntries(
      Map.entry('Y', "uuuu"), Map.entry('y', "uu"), Map.entry('m', "MM"), Map.entry('c', "M"),
      Map.entry('d', "dd"), Map.entry('e', "d"), Map.entry('j', "DDD"), Map.entry('H', "HH"),
      Map.entry('k', "H"), Map.entry('h', "hh"), Map.entry('I', "hh"), Map.entry('l', "h"),
      Map.entry('i', "mm"), Map.entry('s', "ss"), Map.entry('S', "ss"), Map.entry('f', "SSSSSS"),
      Map.entry('T', "HH:mm:ss"), Map.entry('r', "hh:mm:ss a"), Map.entry('p', "a"),
      Map.entry('a', "EEE"), Map.entry('b', "MMM"), Map.entry('M', "MMMM"), Map.entry('W', "EEEE"))
      .entrySet().stream().collect(Collectors.toUnmodifiableMap(Map.Entry::getKey,
          e -> DateTimeFormatter.ofPattern(e.getValue(), Locale.ENGLISH)));

  static void register(SchemaPlus schema) {
    schema.add("DATE_FORMAT", ScalarFunctionImpl.create(FederatedMysqlCompatibility.class, "dateFormat"));
    schema.add("RPAD", ScalarFunctionImpl.create(FederatedMysqlCompatibility.class, "rpad"));
    schema.add(CONCAT_WS_IMPL, ScalarFunctionImpl.create(FederatedMysqlCompatibility.class, "concatWs"));
  }

  @Override public SqlAbstractParserImpl getParser(Reader stream) {
    try {
      StringBuilder sql = new StringBuilder();
      char[] buffer = new char[4096];
      int count;
      while ((count = stream.read(buffer)) != -1) sql.append(buffer, 0, count);
      return new SqlParserImpl(new StringReader(normalize(sql.toString()))) {
        @Override public SqlNode parseSqlExpressionEof() throws Exception {
          return super.parseSqlExpressionEof().accept(mysqlFunctions());
        }

        @Override public SqlNode parseSqlStmtEof() throws Exception {
          return super.parseSqlStmtEof().accept(mysqlFunctions());
        }

        @Override public SqlNodeList parseSqlStmtList() throws Exception {
          return (SqlNodeList) super.parseSqlStmtList().accept(mysqlFunctions());
        }
      };
    } catch (IOException ex) {
      throw new UncheckedIOException(ex);
    }
  }

  private static SqlShuttle mysqlFunctions() {
    return new SqlShuttle() {
      @Override public SqlNode visit(SqlCall node) {
        SqlCall call = (SqlCall) super.visit(node);
        if (!(call.getOperator() instanceof SqlUnresolvedFunction function)
            || !function.getNameAsId().isSimple() || !function.getName().equalsIgnoreCase("CONCAT_WS")
            || call.operandCount() < 2 || call.getFunctionQuantifier() != null) return call;
        // Calcite 1.38 deixa um separador extra quando os últimos valores são NULL.
        // Empacota os argumentos para aceitar o mesmo número variável de valores.
        var pos = call.getParserPosition();
        var text = new SqlDataTypeSpec(new SqlBasicTypeNameSpec(SqlTypeName.VARCHAR, pos), pos);
        List<SqlNode> arguments = call.getOperandList().subList(1, call.operandCount()).stream()
            .<SqlNode>map(value -> SqlStdOperatorTable.CAST.createCall(pos, value, text)).toList();
        SqlNode values = SqlStdOperatorTable.ARRAY_VALUE_CONSTRUCTOR.createCall(pos, arguments);
        return new SqlUnresolvedFunction(new SqlIdentifier(CONCAT_WS_IMPL, pos),
            null, null, null, null, SqlFunctionCategory.USER_DEFINED_FUNCTION)
            .createCall(pos, call.operand(0), values);
      }
    };
  }

  static String normalize(String sql) {
    var matcher = REGEXP.matcher(sql);
    var rewritten = new StringBuilder(sql);
    while (matcher.find()) {
      if (!matcher.group().equalsIgnoreCase("REGEXP")) continue;
      int before = matcher.start() - 1, after = matcher.end();
      while (before >= 0 && Character.isWhitespace(sql.charAt(before))) before--;
      while (after < sql.length() && Character.isWhitespace(sql.charAt(after))) after++;
      if (before >= 0 && after < sql.length() && sql.charAt(before) != '.'
          && (sql.charAt(after) == '\'' || sql.charAt(after) == '?')) {
        // RLIKE é sinônimo no MySQL. Mantém as posições dos erros do parser.
        rewritten.replace(matcher.start(), matcher.end(), "RLIKE ");
      }
    }
    return rewritten.toString();
  }

  public static String dateFormat(Timestamp value, String mask) {
    if (value == null || mask == null) return null;
    StringBuilder result = new StringBuilder(mask.length());
    var date = value.toLocalDateTime();
    for (int i = 0; i < mask.length(); i++) {
      char c = mask.charAt(i);
      if (c != '%' || i + 1 == mask.length()) {
        result.append(c);
        continue;
      }
      char specifier = mask.charAt(++i);
      if (specifier == '%') {
        result.append('%');
        continue;
      }
      DateTimeFormatter formatter = DATE_MASKS.get(specifier);
      if (formatter == null) throw new IllegalArgumentException("DATE_FORMAT: máscara não suportada: %" + specifier);
      result.append(formatter.format(date));
    }
    return result.toString();
  }

  public static String rpad(String value, Integer length, String padding) {
    if (value == null || length == null || padding == null || length < 0) return null;
    if (padding.isEmpty() && value.length() < length) return null;
    return SqlFunctions.rpad(value, length, padding);
  }

  public static String concatWs(String separator, List<?> values) {
    if (separator == null || values == null) return null;
    StringJoiner result = new StringJoiner(separator);
    for (Object value : values) {
      if (value != null) result.add(value.toString());
    }
    return result.toString();
  }
}
