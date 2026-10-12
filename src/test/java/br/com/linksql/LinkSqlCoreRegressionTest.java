package br.com.linksql;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Smoke tests sem banco remoto e sem segredos. Executar via comando documentado
 * no roteiro de testes: nao requer JUnit nem modifica os dados reais do usuario.
 */
public final class LinkSqlCoreRegressionTest {
  private static int checks;
  private static void check(boolean value, String description) {
    if (!value) throw new AssertionError(description);
    checks++;
  }

  public static void main(String[] args) throws Exception {
    Path home = Files.createTempDirectory("linksql33-smoke-");
    System.setProperty("user.home", home.toString());
    Path file = home.resolve("session-test.json");
    try {
      check(SqlSessionStore.read(file).isEmpty(), "sessao inexistente");
      var tabs = List.of(
          new SqlSessionStore.Tab("SQL 1", "SELECT 1", null, "SELECT 1", "SRV_PG"),
          new SqlSessionStore.Tab("SQL consulta", "SELECT * FROM tabela", "C:\\scripts\\teste.sql", "", ""));
      SqlSessionStore.write(file, new SqlSessionStore.Session(tabs, 1));
      Optional<SqlSessionStore.Session> loaded = SqlSessionStore.read(file);
      check(loaded.isPresent(), "sessao salva pode ser lida");
      check(loaded.get().selected() == 1, "aba ativa preservada");
      check(loaded.get().tabs().equals(tabs), "texto, titulo, caminho e fonte preservados");
      JsonNode written = new ObjectMapper().readTree(file.toFile());
      check(!written.path("tabs").get(0).has("file"), "arquivo inexistente omitido");
      check(written.path("tabs").get(0).path("defaultSource").asText().equals("SRV_PG"),
          "conexao padrao no JSON");
      check(!Files.exists(file.resolveSibling("sql-session.tmp")), "temporario movido");
      String old = "{\"tabs\":[{\"title\":\"Legado\",\"sql\":\"SELECT 2\",\"savedText\":\"\"}],\"selected\":0}";
      Files.writeString(file, old, StandardCharsets.UTF_8);
      check(SqlSessionStore.read(file).orElseThrow().tabs().get(0).defaultSource().isEmpty(),
          "sessao legado sem defaultSource permanece federada");
      Files.writeString(file, "{\"selected\":3}");
      check(SqlSessionStore.read(file).isEmpty(), "arquivo sem tabs nao restaura abas");
      Files.writeString(file, "nao e json");
      try { SqlSessionStore.read(file); throw new AssertionError("JSON invalido aceito"); }
      catch (com.fasterxml.jackson.core.JsonProcessingException expected) { checks++; }

      Path profiles = LinkSqlPaths.prepareConnectionProfilesFile();
      check(Files.exists(profiles), "arquivo de perfis inicial preparado");
      check(profiles.startsWith(home), "perfis isolados no usuario atual");
      String original = "{\"connections\":[{\"name\":\"EXISTENTE\"}]}";
      Files.writeString(profiles, original);
      LinkSqlPaths.prepareConnectionProfilesFile();
      check(Files.readString(profiles).equals(original), "perfil existente nao sobrescrito");
      check(LinkSqlPaths.sqlSessionFile().getFileName().toString().equals("sql-session.json"),
          "caminho de sessao preservado");

      List<JdbcConnectionProfile> registered = List.of(
          new JdbcConnectionProfile("SRV_PG", SqlDatabaseType.POSTGRESQL, "localhost", 5432,
              "teste", "public", "usuario", "ENV", "", true),
          new JdbcConnectionProfile("SRV_ORACLE", SqlDatabaseType.ORACLE, "localhost", 1521,
              "teste", "", "usuario", "ENV", "", true));
      check(SqlTabRouting.route("SELECT * FROM clientes", "SRV_PG", registered).source().equals("SRV_PG"),
          "roteamento nativo por aba");
      check(SqlTabRouting.route("SELECT * FROM SRV_ORACLE.CLIENTES", "SRV_PG", registered).source().equals("SRV_ORACLE"),
          "prefixo explicito tem prioridade");
      check(SqlTabRouting.route("SELECT * FROM SRV_PG.c p JOIN SRV_ORACLE.c o ON p.id=o.id", "SRV_PG", registered)==null,
          "join entre origens usa federacao");
      check(SqlTabRouting.route("DELETE FROM clientes", "SRV_PG", registered)==null,
          "comando de escrita nao recebe roteamento de SELECT");
      check(SqlTabRouting.missingAlias("NAO_EXISTE", registered), "alias inexistente detectado");
      check(SqlPageTotals.whenExhausted(0, 100, 37) == 37L,
          "contagem inferida apenas em pagina incompleta");
      check(SqlPageTotals.whenExhausted(0, 100, 100) == null,
          "pagina cheia nao define total");
      check(SqlEditorActions.toggleComments("SELECT 1").contains("--"),
          "edicao de comentario do SQL preservada");
      check(SqlScriptText.splitStatements("SELECT 1; SELECT 2;").size() == 2,
          "separacao de scripts simples");
      check(SqlScriptText.splitStatements("SELECT ';' AS texto; SELECT 2;").size() == 2,
          "separacao nao corta ponto e virgula em string");
      check(SqlScriptText.splitStatements("SELECT 1 /* ; */; SELECT 2;").size() == 2,
          "separacao nao corta comentario em bloco");
      check(SqlScriptText.splitStatements("SELECT 1 -- comentario ;\n; SELECT 2;").size() == 2,
          "separacao nao corta comentario de linha");
      check(SqlScriptText.formatClauses("SELECT a FROM t WHERE x=1").contains("\nFROM"),
          "formatacao separa FROM do nivel principal");
      check(SqlScriptText.formatClauses("SELECT 'FROM WHERE' FROM t").contains("'FROM WHERE'"),
          "formatacao preserva literais");
      check(SqlScriptText.formatClauses("SELECT * FROM (SELECT x FROM y) s").contains("(SELECT x FROM y)"),
          "formatacao preserva subconsulta interna");

      System.out.println("LinkSQL 3.3.1: " + checks + " verificacoes de arquitetura APROVADAS.");
    } finally {
      // Arquivos de teste sao isolados; evita mexer no perfil real do Windows.
      Files.walk(home).sorted(java.util.Comparator.reverseOrder()).forEach(path -> {
        try { Files.deleteIfExists(path); } catch (Exception ignored) { }
      });
    }
  }
}
