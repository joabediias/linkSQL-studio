package br.com.linksql;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.LinkedHashMap;
import java.util.Map;

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

  record Namespace(String name, String catalog, String schema, boolean defaultScope) {}

  /** Catalogs for MySQL/SQL Server; schemas within the connected database for Oracle/PostgreSQL. */
  static List<Namespace> namespaces(Connection c, JdbcConnectionProfile profile) throws SQLException {
    String catalog = profile.catalogFor(c), schema = profile.schemaForMetadata();
    DatabaseMetaData meta = c.getMetaData();
    // Only the explicit field restricts discovery. Implicit public/dbo/user defaults do not.
    String configured = profile.schema() == null ? "" : profile.schema().strip();
    if (!configured.isEmpty() && profile.type() != SqlDatabaseType.FIREBIRD)
      return configuredNamespace(meta, profile, catalog, configured);
    Map<String, Namespace> result = new LinkedHashMap<>();
    if (profile.type() == SqlDatabaseType.MYSQL || profile.type() == SqlDatabaseType.SQLSERVER) {
      try (ResultSet rs = meta.getCatalogs()) {
        while (rs.next()) {
          String name = rs.getString("TABLE_CAT");
          if (name != null && !name.isBlank()) result.put(name.toLowerCase(Locale.ROOT),
              new Namespace(name, name, null, same(name, catalog)));
        }
      } catch (SQLException ignored) { /* Current scope is still usable. */ }
    } else if (profile.type() != SqlDatabaseType.FIREBIRD) {
      try (ResultSet rs = meta.getSchemas()) {
        while (rs.next()) {
          String name = rs.getString("TABLE_SCHEM");
          String ownerCatalog = null;
          try { ownerCatalog = rs.getString("TABLE_CATALOG"); } catch (SQLException ignored) {}
          if (ownerCatalog != null && catalog != null && !same(ownerCatalog, catalog)) continue;
          if (name != null && !name.isBlank()) result.put(name.toLowerCase(Locale.ROOT),
              new Namespace(name, catalog, name, same(name, schema)));
        }
      } catch (SQLException ignored) { /* Fall back to the configured schema. */ }
    }
    String current = namespace(catalog, schema, profile.schemaPattern(), profile.database());
    if (profile.type() == SqlDatabaseType.MYSQL || profile.type() == SqlDatabaseType.SQLSERVER)
      current = namespace(catalog, null, null, profile.database());
    result.putIfAbsent(current.toLowerCase(Locale.ROOT), new Namespace(current, catalog, schema, true));
    List<Namespace> namespaces = new ArrayList<>(result.values());
    namespaces.sort(Comparator.comparing(Namespace::name, String.CASE_INSENSITIVE_ORDER));
    return namespaces;
  }

  private static List<Namespace> configuredNamespace(DatabaseMetaData meta, JdbcConnectionProfile profile,
      String catalog, String configured) throws SQLException {
    boolean mysql = profile.type() == SqlDatabaseType.MYSQL;
    try (ResultSet rs = mysql ? meta.getCatalogs() : meta.getSchemas()) {
      while (rs.next()) {
        String name = rs.getString(mysql ? "TABLE_CAT" : "TABLE_SCHEM");
        if (name == null || !same(name, configured)) continue;
        if (!mysql) {
          String ownerCatalog = null;
          try { ownerCatalog = rs.getString("TABLE_CATALOG"); } catch (SQLException ignored) {}
          if (catalog != null && ownerCatalog != null && !same(catalog, ownerCatalog)) continue;
        }
        return List.of(mysql ? new Namespace(name, name, null, same(name, catalog))
            : new Namespace(name, catalog, name, true));
      }
      // The configured name is absent from the metadata visible to this user.
      return List.of();
    } catch (SQLException unavailable) {
      // A driver may deny enumeration while still allowing metadata for a known namespace.
      // Keep the explicit restriction, never fall back to another database or all schemas.
      return List.of(mysql ? new Namespace(configured, configured, null, same(configured, catalog))
          : new Namespace(configured, catalog, configured, true));
    }
  }

  static boolean same(String left, String right) {
    return left == null ? right == null || right.isBlank() : right != null && left.equalsIgnoreCase(right);
  }

  static String literalPattern(DatabaseMetaData metadata, String value) throws SQLException {
    if (value == null) return null;
    String escape = metadata.getSearchStringEscape();
    if (escape == null || escape.isEmpty()) return value;
    return value.replace(escape, escape + escape).replace("%", escape + "%").replace("_", escape + "_");
  }

  static List<Entry> tables(Connection c, JdbcConnectionProfile profile, Namespace scope) throws SQLException {
    return readTables(c, profile, scope.catalog(), literalPattern(c.getMetaData(), scope.schema()));
  }

  static List<Entry> tables(Connection c, JdbcConnectionProfile profile) throws SQLException {
    return readTables(c, profile, profile.catalogFor(c), profile.schemaForMetadata());
  }

  private static List<Entry> readTables(Connection c, JdbcConnectionProfile profile, String selectedCatalog, String selectedSchema) throws SQLException {
    DatabaseMetaData metadata = c.getMetaData();
    List<Entry> entries = new ArrayList<>();
    try (ResultSet rs = metadata.getTables(selectedCatalog, selectedSchema, "%",
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
        ? metadata.getFunctions(catalog, literalPattern(metadata, schema), "%")
        : metadata.getProcedures(catalog, literalPattern(metadata, schema), "%")) {
      while (rs.next()) {
        String name = rs.getString(functions ? "FUNCTION_NAME" : "PROCEDURE_NAME");
        if (name != null && !name.isBlank() && !names.contains(name)) names.add(name);
      }
    }
    names.sort(String.CASE_INSENSITIVE_ORDER);
    return names;
  }
}
