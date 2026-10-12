# 03. Inventário completo do código-fonte Java

Todos os arquivos Java do desktop estão atualmente em `src/main/java/br/com/linksql/`. **Os grupos abaixo são conceituais:** os arquivos continuam organizados em um pacote Java único nesta etapa.

> Orientação: localize a classe pelo nome no VS Code/IntelliJ, abra os métodos indicados e acompanhe as chamadas no documento [04 — Fluxos e motor SQL](04-fluxos-e-motor-sql.md).

## A. Inicialização e interface

| Arquivo                            | Responsabilidade concreta                                                                                                                       | Métodos/entradas mais úteis                                                                                                  |
| ---------------------------------- | ----------------------------------------------------------------------------------------------------------------------------------------------- | ---------------------------------------------------------------------------------------------------------------------------- |
| **`Main.java`**                    | Entrada do desktop, criação da janela, eventos, explorador, editor, seleção de conexão por aba, execução de queries/scripts, grade e exportação | `main`, `createUi`, `addSqlTab`, `runQuery`, `runScript`, `loadResultPage`, `requestCount`, `loadConfiguration`, `showError` |
| **`LinkSqlVisualStyle.java`**      | Paleta, ícones desenhados, componentes e atualização de tema da interface principal                                                             | `defaults`, `refresh`, `icon`, `button`, `explorer`, `resultsTable`, `splitter`                                              |
| **`LinkSqlDialogStyle.java`**      | Aparência consistente dos diálogos secundários                                                                                                  | `window`, `heading`, `field`, `button`, `section`, `table`, `scroll`                                                         |
| **`ConnectionManagerDialog.java`** | Gerenciar perfis de servidores: listar, filtrar, criar, editar, duplicar, testar e excluir, além da interação com senhas                        | `show`, `loadProfiles`, `refreshFilter`, `duplicateProfile`, `testConnection`, `saveProfile`, `deleteProfile`                |
| **`LinkSqlSettingsDialog.java`**    | Janela de configurações por Editor SQL/Resultados/Aparência; retorna preferências selecionadas ou cancelamento                                  | `show`, `parsePageSize`                                                                                                      |
| **`TableMetadataDialog.java`**     | Exibir colunas e propriedades de tabelas via metadados JDBC em segundo plano                                                                    | `show` e trabalhador `SwingWorker`                                                                                           |
| **`DatabaseCatalog.java`**         | Consulta e agrupa tabelas/views por catálogo/schema para o explorador, usando `DatabaseMetaData`                                                | `tables`, `routines`, `namespace`                                                                                            |
| **`SqlHistoryDialog.java`**        | **Novo:** lista de consultas anteriores, pesquisa, favoritos e prévia. Devolve SQL selecionado para o chamador                                  | `choose`                                                                                                                     |
| **`LinkSqlGridHeaders.java`**       | Ajusta renderização/alinhamento de cabeçalhos das grades                                                                                        | `alignLeft`                                                                                                                  |

## B. Conexões, perfis e credenciais

| Arquivo                          | O que faz                                                                                                        | Métodos/estruturas relevantes                                                                  |
| -------------------------------- | ---------------------------------------------------------------------------------------------------------------- | ---------------------------------------------------------------------------------------------- |
| **`JdbcConnectionProfile.java`** | Representa e valida um perfil de conexão; lê parâmetros públicos de JSON, não inclui senha                       | `fromJson`, `validate`, `resolvedJdbcUrl`, `schemaPattern`, `catalogFor`                       |
| **`SqlDatabaseType.java`**       | Enum dos cinco SGBDs, classes de driver, portas e modelos de URL JDBC                                            | `fromCode`, `url`, `defaultSchema`, `driver`                                                   |
| **`SqlConnectionRegistry.java`** | Mantém perfis, pools DBCP2, resolução de senha, conexões sob demanda e preparação dos schemas Calcite            | `source`, `configureLazy`, `configure`, `openFederatedQueryConnection`, `test`, `close`        |
| **`ConnectionVault.java`**       | Cofre de senhas com DPAPI CurrentUser no Windows; políticas permanente, 7/30 dias e sessão                       | `get`, `save`, `has`, `delete`, `rename`, `parseDpapiResult`                                   |
| **`LinkSqlPaths.java`**          | **Novo:** caminhos dos perfis, sessão e local sugerido para arquivos; criação não destrutiva do JSON de conexões | `prepareConnectionProfilesFile`, `sqlSessionFile`, `connectionProfilesFile`, `defaultUserFile` |

## C. Decisão entre SQL nativo e federado

| Arquivo                          | O que faz                                                                                                             | Métodos/estruturas relevantes                                               |
| -------------------------------- | --------------------------------------------------------------------------------------------------------------------- | --------------------------------------------------------------------------- |
| **`SqlTabRouting.java`**         | Usa o banco escolhido para a aba em SELECTs sem prefixo; respeita referências explícitas e JOINs multiorigem          | `route`, `missingAlias`                                                     |
| **`NativeSqlRouter.java`**       | Reconhece SELECTs/CTEs para execução nativa de fornecedores não MySQL, evitando alterar literais/comentários          | `route`, `readOnlyQuery`                                                    |
| **`SingleServerSqlRouter.java`** | Roteamento de SELECT para uma única origem MySQL, com análise lexical conservadora                                    | `route`                                                                     |
| **`SqlQueryPlanner.java`**       | Reconhece limites SQL e determina SQL direto MySQL, incluindo janela de registros                                     | `sqlWindow`, `directSql`, `directPageSql`, `executableSql`, `isPageableSql` |
| **`FederatedQuerySupport.java`** | Detecta referências a conexões no SQL, verifica aliases desconhecidos, seleciona origens para Calcite e melhora erros | `analyze`, `validate`, `sourcesToRegister`, `explain`                       |
| **`FirebirdCompatDialect.java`** | Ajusta a tradução gerada pelo Calcite para servidores Firebird legados (por exemplo, FIRST/SKIP)                      | Extensão do `SqlDialect` do Calcite                                         |

Esses arquivos identificam a estratégia de execução; **não confunda roteamento lexical com validação completa de segurança do SQL**. As contas JDBC devem manter os privilégios apropriados.

## D. Paginação, leitura e CSV

| Arquivo                          | O que faz                                                                      | Métodos/estruturas relevantes |
| -------------------------------- | ------------------------------------------------------------------------------ | ----------------------------- |
| **`NativeJdbcPaging.java`**      | Pagina resultados do lado JDBC quando necessário, incluindo pular linhas lidas | `window`, `configure`, `read` |
| **`NativeServerPaging.java`**    | Tenta empurrar LIMIT/OFFSET/FETCH/ROWS para o servidor conforme o dialeto      | `sql`                         |
| **`NativeQueryCount.java`**      | Gera consulta COUNT para SQL nativo, respeitando peculiaridades do SQL Server  | `sql`                         |
| **`FederatedCountSupport.java`** | Compõe consultas de contagem em resultados federados                           | `countSql`, `effectiveTotal`  |
| **`SqlPageTotals.java`**         | Infere contagem exata quando uma página vem incompleta                         | `whenExhausted`               |
| **`SqlResultModels.java`**       | Transforma `ResultSet` em `DefaultTableModel` não editável                     | `readResultModel`             |
| **`CsvExportWriter.java`**       | Escreve CSV de uma `JTable`, com codificação, separador, cabeçalho e aspas     | `write`, `csv`                |

## E. Editor e produtividade

| Arquivo                             | O que faz                                                                                 | Métodos/estruturas relevantes                                      |
| ----------------------------------- | ----------------------------------------------------------------------------------------- | ------------------------------------------------------------------ |
| **`SqlCompletionContext.java`**     | Análise contextual leve de tabelas, aliases, CTEs e colunas para sugestões                | `analyze`, `reference`, `cteNames`, `cteColumns`, `isCodePosition` |
| **`SqlAutocompleteHints.java`**     | Identifica posições do texto que esperam tabela/CTE                                       | `expectsTable`                                                     |
| **`SqlEditorActions.java`**         | Transformação de blocos de texto para comentar/descomentar SQL                            | `toggleComments`                                                   |
| **`SqlEditorShortcutSupport.java`** | Instala atalhos, incluindo variantes para teclado ABNT2                                   | `installCommentShortcut`                                           |
| **`SqlScriptText.java`**            | **Novo:** separa comandos em script e organiza quebras de linha em cláusulas externas     | `splitStatements`, `formatClauses`                                 |
| **`SqlSessionStore.java`**          | **Novo:** snapshots `Tab`/`Session` e persistência JSON atômica, sem dependência de Swing | `read`, `write`                                                    |
| **`SqlHistoryStore.java`**          | Grava e lê as últimas consultas (limite de 100), em linhas codificadas em Base64          | `remember`, `read`                                                 |
| **`SqlFavoritesStore.java`**        | Guarda favoritos independentemente do tamanho do histórico                                | `read`, `write`                                                    |
| **`LinkSqlSettings.java`**           | Preferências locais: tema, tamanho de página, autocomplete, fonte, delays etc.            | `snapshot`, `save`, `pageSize`, `setDarkMode`                      |
| **`LinkSqlLog.java`**                | Registros operacionais locais com rotação e cuidado para não expor SQL/senhas             | `error`                                                            |

## F. Integração e componentes históricos

| Arquivo                          | O que faz                                                                                                 | Uso atual                                                                                         |
| -------------------------------- | --------------------------------------------------------------------------------------------------------- | ------------------------------------------------------------------------------------------------- |
| **`LinkSqlEmbeddedEngine.java`** | Motor reaproveitado pelo driver JDBC embutido para decidir SQL nativo/federado e abrir fontes necessárias | Código compartilhado com o pacote JDBC; **não substitui por si só o bundle JDBC 0.5 distribuído** |
| **`ReadOnlySqlGuard.java`**       | Verificação defensiva de SELECT usada no motor JDBC embutido                                 | Guarda SELECT usada pelo motor JDBC embutido; preservada com nome neutro após remover o Gateway                                      |

## G. Outros arquivos do projeto

| Caminho                                            | Finalidade                                                                                    |
| -------------------------------------------------- | --------------------------------------------------------------------------------------------- |
| `pom.xml`                                          | Dependências, versão 3.3.1, compilação Java 17 e entrada `Main` pelo Maven Exec               |
| `src/test/java/.../LinkSqlCoreRegressionTest.java` | Verificações sem bancos reais de sessão, compatibilidade, roteamento e texto SQL              |
| `testes/testar-core.bat` / `.ps1`                  | Executam o teste de arquitetura no Windows após compilar pelo Maven                           |
| `distribuicao/gerar-windows.bat` / `.ps1`          | Geração de app portátil e instalador opcional usando jpackage                                 |
| `integracao/jdbc-src/`               | Fontes do driver JDBC embutido, mantidas para o gerador 0.6                                 |
| `config/connections.example.json`                  | Exemplo de parâmetros de conexão **sem senha**; não copiar credenciais reais para repositório |
| `documentacao/`                                    | Manuais técnicos, funcionais, arquitetura, segurança e roteiros de validação                  |
| `target/`                                          | Saída gerada pelo Maven/jpackage; não editar fontes dentro desta pasta                        |

### Limpeza da versão 3.3.1

O Gateway experimental foi retirado: `SantySqlGateway`, `GatewayPgWireServer` e `GatewaySqlExecutor` não são chamados pelo Desktop nem pelo JDBC embutido. **`ReadOnlySqlGuard` permanece**, pois o motor do driver JDBC o utiliza. O código-fonte tem agora **39 classes Java principais** e os pacotes Java foram renomeados para `br.com.linksql`.

O diretório `target/` e as saídas geradas do módulo JDBC não devem ser versionados nem distribuídos como fonte. Veja `09-revisao-projeto.md` para os motivos e o roteiro de validação.
