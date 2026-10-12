package br.com.linksql;

import java.util.Collection;

/**
 * Define a origem de um SELECT em uma aba SQL.
 * Referencias explicitas a SRV_... sempre prevalecem sobre a conexao padrao.
 * SQL sem prefixo usa a conexao selecionada, sem reescrever nomes ou dialetos.
 */
final class SqlTabRouting {
  private SqlTabRouting() {}

  static NativeSqlRouter.Route route(String sql, String tabAlias,
                                     Collection<JdbcConnectionProfile> profiles) {
    String base = SqlQueryPlanner.sqlWindow(sql).baseSql();
    NativeSqlRouter.Route explicit = NativeSqlRouter.route(base, profiles);
    if (explicit != null) return explicit;
    if (tabAlias == null || tabAlias.isBlank()) return null;

    FederatedQuerySupport.Analysis refs = FederatedQuerySupport.analyze(base, profiles);
    // Dois ou mais aliases -> Calcite. Um alias -> executor original dessa origem.
    if (!refs.sources().isEmpty() || !refs.unknownSources().isEmpty()) return null;
    if (!NativeSqlRouter.readOnlyQuery(base)) return null; // somente SELECT/CTE seguros

    for (JdbcConnectionProfile profile : profiles) {
      if (profile.name().equalsIgnoreCase(tabAlias))
        return new NativeSqlRouter.Route(profile.name(), profile.type(), base);
    }
    return null;
  }

  static boolean missingAlias(String tabAlias, Collection<JdbcConnectionProfile> profiles) {
    if (tabAlias == null || tabAlias.isBlank()) return false;
    for (JdbcConnectionProfile p : profiles)
      if (p.name().equalsIgnoreCase(tabAlias)) return false;
    return true;
  }
}
