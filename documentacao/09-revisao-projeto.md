# 09. Revisão do projeto, limpeza e migração — LinkSQL 3.3.1

## Análise realizada

A revisão foi feita sobre o **`linksql.zip` enviado**, não sobre um projeto genérico. Foram identificadas 42 classes Java no aplicativo principal. Três classes exclusivas do Gateway experimental foram removidas, resultando em **39 classes principais**, mais as classes de teste e os fontes do driver embutido.

| Ação | Motivo | Situação |
|---|---|---|
| Remover `SantySqlGateway.java`, `GatewayPgWireServer.java` e `GatewaySqlExecutor.java` | Protótipo de serviço não utilizado pelo Desktop nem pelo JDBC embarcado | Removidos |
| Renomear `GatewaySqlGuard.java` para `ReadOnlySqlGuard.java` | Guarda de consultas utilizada pelo motor embutido, independente do Gateway legado | Mantido com nome claro |
| Excluir `target/` do projeto-fonte | Resultados de compilação, cópias e JARs gerados | Fora do novo ZIP |
| Manter `integracao/jdbc-src`, `integracao/linksql-jdbc/pom.xml` e testes | Necessários para reconstruir o driver | Mantidos |
| Renomear `br.com.santysqlstudio` para `br.com.linksql` | Consistência técnica com o nome LinkSQL | Alterado no código, Maven e scripts |
| Renomear documentação para letras minúsculas | Padrão solicitado | Feito, inclusive HTML e `readme.md` |
| Corrigir links da documentação | Alguns apontavam para arquivos removidos ou com maiúsculas antigas | Corrigidos |
| Organizar dados em `.linksql` | Nome único para o projeto | Migração de primeira execução com preservação da origem |
| Conservar LinkSQL JDBC 0.5 no exportador | Produto separado já validado | Arquivos necessários espelhados temporariamente |

## Estrutura de código

- `src/main/java/br/com/linksql`: aplicativo principal, engine SQL, visual, persistência e conexões (39 classes).
- `src/test/java/br/com/linksql`: testes de regressão e migração.
- `integracao/jdbc-src/br/com/linksql/jdbc`: driver JDBC embutido.
- `integracao/linksql-jdbc`: módulo de construção do bundle.
- `ferramentas`: gerador PowerShell JDBC, com versão preparada para 0.6.
- `distribuicao`: scripts de portátil/instalador Windows.
- `testes`: execução dos testes de regressão no Windows.
- `documentacao`: manuais em minúsculas, com HTML navegável.

**Não foram movidas todas as classes para dezenas de novos pacotes**: essa refatoração mudaria muito mais dependências ao mesmo tempo. A estrutura atual funciona e deve ser reorganizada funcionalmente em versões menores, após testes.

## Passo a passo seguro no Windows

1. Feche LinkSQL e SantySQL 5.3.7.
2. Faça backup das pastas do projeto e `%USERPROFILE%\.santysql-studio`.
3. Extraia o novo ZIP em uma pasta **`linksql`** independente, sem sobrescrever o projeto antigo. Não extraia a pasta `target` de versões anteriores.
4. Execute `mvn clean compile exec:java` na nova pasta.
5. Confira se `%USERPROFILE%\.linksql` recebeu perfis, cofre DPAPI, histórico, favoritos e sessões. A pasta antiga não é excluída.
6. Valide tema, seleção de conexão, DPAPI, `SELECT` nativo, JOIN federado, paginação e CSV.
7. Abra o SantySQL 5.3.7 com o **JDBC 0.5 atual** e confirme uma consulta usando credenciais salvas. Ele ainda procura os arquivos de compatibilidade na pasta antiga.
8. Rode `testes\testar-core.bat` para testar as regras sem acessar bancos reais.
9. Só depois gere o novo instalador pelo script `distribuicao\gerar-windows.bat`.

O **gerador de driver 0.6 está atualizado apenas em código-fonte**. A compilação Windows e a substituição do bundle JDBC são uma validação distinta. Não instale o 0.6 sem antes testar e preservar um backup do 0.5.

## Reversão

Se o LinkSQL 3.3.1 não passar em seu ambiente, feche-o e reabra o LinkSQL 3.3.0 no diretório antigo. **Não apague `.santysql-studio`** durante esse processo. Não é necessário restaurar uma senha em texto puro; o cofre DPAPI original permanece intacto no mesmo usuário Windows.

## Fora do escopo

- Mudança de visual: aguardará as imagens e instruções que você enviará.
- Otimização de grandes JOINs federados: será uma etapa própria, com medições e testes de desempenho.
- Novo JDBC 0.6 pronto para produção: requer compilação e validação no Windows.
