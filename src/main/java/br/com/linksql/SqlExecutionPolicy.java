package br.com.linksql;

import java.sql.SQLException;
import java.util.Collection;
import java.util.List;
import java.util.Set;

/** A permissão da interface nunca é herdada pelo driver JDBC embutido. */
final class SqlExecutionPolicy {
  private SqlExecutionPolicy() {}
  private static final Set<String> WRITES = Set.of("INSERT", "UPDATE", "DELETE", "MERGE",
      "CREATE", "ALTER", "DROP", "TRUNCATE", "SELECT", "WITH");

  static NativeSqlRouter.Route writeRoute(String sql, boolean enabled, String alias,
      Collection<JdbcConnectionProfile> profiles) throws SQLException {
    List<String> words = ReadOnlySqlGuard.scan(sql); // um único comando, aspas/comentários válidos
    try { ReadOnlySqlGuard.requireReadOnlySelect(sql); return null; }
    catch (SQLException ex) {
      if (!enabled) throw new SQLException(
          "Comando bloqueado pelo modo somente leitura. Habilite comandos de escrita em Configurações.", "25006");
    }
    if (words.isEmpty() || !WRITES.contains(words.get(0)))
      throw new SQLException("Comando não suportado. Use SELECT, INSERT, UPDATE, DELETE, MERGE ou DDL.", "42000");
    if (alias == null || alias.isBlank())
      throw new SQLException("Selecione o banco desta aba para executar comandos de escrita.", "25006");
    FederatedQuerySupport.Analysis refs = FederatedQuerySupport.analyze(sql, profiles);
    if (!refs.sources().isEmpty() || !refs.unknownSources().isEmpty())
      throw new SQLException("Escrita usa SQL nativo na conexão selecionada, sem prefixos de conexões federadas.", "25006");
    for (JdbcConnectionProfile profile : profiles)
      if (profile.name().equalsIgnoreCase(alias))
        return new NativeSqlRouter.Route(profile.name(), profile.type(), sql);
    throw new SQLException("Conexão não cadastrada: " + alias, "08001");
  }
}
