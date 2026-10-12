# 04. Fluxos de execução e motor SQL

## 1. Da abertura do programa à interface

```text
Main.main()
  -> applyLookAndFeel()
  -> createUi() (EDT do Swing)
     -> constroi menu, explorador, editor, resultado, barra de status
     -> restaura abas de SqlSessionStore
     -> carrega perfis por LinkSqlPaths e SqlConnectionRegistry.configureLazy
     -> atualiza o explorador e o seletor de conexão da aba
```

O Swing usa a **Event Dispatch Thread (EDT)** para atualizar componentes visuais. Chamadas demoradas a bancos são deslocadas para `SwingWorker` sempre que implementado no projeto; não execute queries diretamente dentro de ações Swing.

## 2. Consulta SQL nativa sem prefixo

Se uma aba aponta para `SRV_PG`:

```sql
SELECT codigo, nome
FROM clientes
WHERE ativo = 1;
```

Fluxo simplificado:

1. `Main.runQuery` obtém SQL selecionado ou comando do cursor; captura a conexão padrão da aba.
2. `FederatedQuerySupport.validate` verifica referências de origem explícitas desconhecidas.
3. `SqlTabRouting.route` verifica se é SELECT/CTE encaminhável e seleciona a conexão padrão, caso não haja prefixos lógicos.
4. `NativeJdbcPaging.window` interpreta o tamanho da página e eventuais limites no SQL.
5. `NativeServerPaging.sql` tenta empurrar os limites para o fornecedor, quando o dialeto permite.
6. `SqlConnectionRegistry.source(alias).getConnection` usa o pool JDBC da origem correspondente.
7. `Statement.executeQuery` obtém um `ResultSet`; `SqlResultModels.readResultModel` constrói a grade.
8. `Main.showOrUpdateResultTab` apresenta o resultado. Uma contagem auxiliar pode iniciar separadamente via `requestCount`.

**Importante:** não é necessário inserir `SRV_PG.` quando a aba já selecionou `SRV_PG`; contudo, o uso de schemas reais (`public.tabela`, `dbo.tabela`) continua dependendo do fornecedor.

## 3. SQL explícito e JOIN federado

```sql
SELECT m.codigo, o.codigo
FROM SRV_MYSQL.clientes m
JOIN SRV_ORACLE.CLIENTES o ON m.codigo = o.CODIGO;
```

- O prefixo `SRV_MYSQL` ou `SRV_ORACLE` é **alias lógico cadastrado** no LinkSQL, não necessariamente o nome do schema remoto.
- `FederatedQuerySupport.analyze` identifica quais origens a consulta referencia.
- `SqlTabRouting` evita impor a conexão padrão quando há origens explícitas.
- Para múltiplas origens, o controlador solicita conexão do **Apache Calcite** via `SqlConnectionRegistry.openFederatedQueryConnection`.
- O registro prepara schemas JDBC somente para as origens relevantes detectadas e o Calcite planeja a consulta federada.
- `FirebirdCompatDialect` é usado quando SQL gerado pelo Calcite precisa respeitar a sintaxe de Firebird antigo.

## 4. SQL nativo com prefixo

Uma consulta com apenas uma origem explícita pode aproveitar roteamento nativo, conforme as regras do SQL e do fornecedor:

```sql
SELECT * FROM SRV_ORACLE.CLIENTES;
```

A decisão é conservadora: em caso de ambiguidade ou combinação de fontes, preserva-se o caminho já suportado, em vez de reescrever indiscriminadamente a consulta.

## 5. Execução de scripts

O comando **Ctrl+Shift+Enter** executa um script com vários comandos, separado por `SqlScriptText.splitStatements` (extraído do `Main` nesta versão). O separador reconhece aspas e comentários conforme a lógica preexistente; o método não substitui um parser de todos os dialetos.

`Main.runScript` executa os comandos sequencialmente, registra resultados e trata interrupções/erros. **Atenção:** esse fluxo pode encaminhar instruções que não sejam SELECT para o banco, dependendo do SQL e dos privilégios. Em ambientes de produção, trabalhe com **usuários de consulta efetivamente sem permissões de escrita ou DDL**.

## 6. Paginação e contagem

```text
Resultado da primeira consulta
  -> Grade da página 1
  -> Consulta auxiliar COUNT (assíncrona, quando cabível)
  -> Usuário clica em Próxima
  -> cache da página? sim: reaproveita
                         não: recarrega do banco
  -> NativeServerPaging, NativeJdbcPaging ou Calcite
```

`NativeServerPaging` possui regras por fornecedor: PostgreSQL usa LIMIT/OFFSET, Oracle usa OFFSET/FETCH, Firebird usa ROWS; MySQL recebe LIMIT/OFFSET em roteamento padrão, e SQL Server pode depender de paginação JDBC. A consulta federada tem caminho próprio. O total em cache não significa que todos os registros tenham sido transferidos.

A aba do resultado guarda **qual conexão era usada quando a consulta foi disparada**, evitando que trocar o seletor da aba redirecione a paginação antiga para outro servidor.

## 7. Autocomplete e explorador

Ao expandir uma conexão, o explorador consulta os **metadados JDBC** por `DatabaseCatalog`. Os nós de schemas/tabelas/views/procedimentos/funções são montados na UI; colunas e rotinas são carregadas quando necessário. `SqlCompletionContext` usa texto SQL, aliases, CTEs e metadados conhecidos para construir sugestões.

O autocomplete é uma ferramenta de produtividade, **não um validador da consulta**. Uma sugestão pode depender da informação já carregada no explorador.

## 8. Persistência de sessão (alteração 3.3)

```text
Main.saveSession()
  -> captura titulo + SQL + arquivo (quando houver)
  -> captura conexao padrao de cada aba e indice ativo
  -> cria SqlSessionStore.Session imutavel
  -> SqlSessionStore.write() (JSON temporario e renomeacao atomica quando possivel)
```

Na próxima execução:

```text
Main.restoreSession()
  -> SqlSessionStore.read()
  -> cria abas visuais
  -> restaura editor, titulo, arquivo, fonte e aba selecionada
```

O JSON **não guarda senha**. Arquivos de sessão de versões antigas sem `defaultSource` continuam assumindo modo federado.

## 9. Histórico, favoritos e formatação

- `Main.rememberQuery` delega ao `SqlHistoryStore`, que salva as últimas consultas em linhas Base64 (apenas codificação, não criptografia).
- `Main.showHistory` obtém os dados, chama `SqlHistoryDialog.choose` e, quando confirmado, abre uma nova aba com o SQL escolhido.
- `SqlFavoritesStore` persiste consultas favoritas independentemente do limite do histórico.
- `SqlScriptText.formatClauses` organiza quebras de linha em palavras-chave externas; não altera strings/comentários.

## 10. Exportação CSV

`Main.exportCsv` coleta opções como separador, codificação, cabeçalho e aspas, e chama `CsvExportWriter.write` para serializar a **JTable exibida**. Para processos de exportação automatizados e grandes volumes, utilize o exportador SantySQL 5.3.7 com JDBC adequado ou um fluxo específico com streaming.

## 11. Autenticação/DPAPI

`ConnectionManagerDialog` salva parâmetros públicos em `connections.json`; a senha, quando o usuário autoriza lembrar, é tratada por `ConnectionVault` com DPAPI CurrentUser. O registro JDBC resolve essa credencial sob demanda, no primeiro acesso à origem. O LinkSQL JDBC 0.5 pode reutilizar a mesma proteção se for executado pelo **mesmo usuário do Windows**.

Não usar DPAPI no Windows não significa que se deva salvar a senha em JSON: deve-se utilizar outra forma segura de autenticação compatível com o ambiente.
