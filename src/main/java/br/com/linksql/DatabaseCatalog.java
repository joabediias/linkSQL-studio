package br.com.linksql;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/** Metadados do explorador, sem executar SQL específico de cada fornecedor. */
final class DatabaseCatalog {
  private DatabaseCatalog() {}

  record Entry(String namespace, String catalog, String schema, String table, boolean view) {}

  static String namespace(String catalog, String schema, String configuredSchema, String configuredDatabase) {
    if (schema != null && !schema.isBlank()) return schema;
    if (configuredSchema != null && !configuredSchema.isBlank()) return configuredSchema;
    if (catalog != null && !catalog.isBlank()) return catalog;
    if (configuredDatabase != null && !configuredDatabase.isBlank()) {
      String normalized = configuredDatabase.replace('\\', '/');
      int cut = normalized.lastIndexOf('/');
      return cut >= 0 ? normalized.substring(cut + 1) : normalized;
    }
    return "Padrão";
  }

  static List<Entry> tables(Connection c, JdbcConnectionProfile profile) throws SQLException {
    DatabaseMetaData metadata = c.getMetaData();
    List<Entry> entries = new ArrayList<>();
    try (ResultSet rs = metadata.getTables(profile.catalogFor(c), profile.schemaForMetadata(), "%",
        new String[]{"TABLE", "VIEW"})) {
      while (rs.next()) {
        String name = rs.getString("TABLE_NAME");
        if (name == null || name.isBlank()) continue;
        String catalog = rs.getString("TABLE_CAT");
        String schema = rs.getString("TABLE_SCHEM");
        String type = rs.getString("TABLE_TYPE");
        String label = namespace(catalog, schema, profile.schemaPattern(), profile.database());
        entries.add(new Entry(label, catalog, schema, name,
            type != null && type.toUpperCase(Locale.ROOT).contains("VIEW")));
      }
    }
    entries.sort(Comparator.comparing(Entry::namespace, String.CASE_INSENSITIVE_ORDER)
        .thenComparing(Entry::view)
        .thenComparing(Entry::table, String.CASE_INSENSITIVE_ORDER));
    return entries;
  }

  static List<String> routines(Connection c, JdbcConnectionProfile profile,
      String catalog, String schema, boolean functions) throws SQLException {
    List<String> names = new ArrayList<>();
    DatabaseMetaData metadata = c.getMetaData();
    try (ResultSet rs = functions
        ? metadata.getFunctions(catalog, schema, "%")
        : metadata.getProcedures(catalog, schema, "%")) {
      while (rs.next()) {
        String name = rs.getString(functions ? "FUNCTION_NAME" : "PROCEDURE_NAME");
        if (name != null && !name.isBlank() && !names.contains(name)) names.add(name);
      }
    }
    names.sort(String.CASE_INSENSITIVE_ORDER);
    return names;
  }
}
