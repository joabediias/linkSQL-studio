# 06. Manual do usuário

## 1. Abrir o LinkSQL

Execute o aplicativo instalado ou, no ambiente de desenvolvimento, use `mvn clean compile exec:java`. A aplicação pode recuperar as abas e consultas anteriores automaticamente.

## 2. Cadastrar uma conexão

1. Abra **Conexões → Gerenciar conexões**.
2. Informe um nome lógico (por exemplo `SRV_PG`, com letras, números e `_`).
3. Selecione MySQL, Oracle, PostgreSQL, SQL Server ou Firebird.
4. Preencha host, porta, database/serviço, schema se necessário, usuário e opções específicas.
5. Use **Testar conexão**; depois salve o perfil.
6. Quando solicitado, informe senha e escolha se quer protegê-la no Windows.

A senha persistente é protegida pelo usuário Windows via DPAPI; o perfil JSON contém somente os parâmetros públicos de conexão. Se utilizar outro usuário Windows, poderá ter de informar novamente a senha.

## 3. Explorar objetos

1. No **Explorador**, expanda a conexão.
2. Expanda o schema/banco e as pastas **Tabelas**, **Views**, **Procedimentos** e **Funções**.
3. Expanda uma tabela para carregar colunas, quando disponíveis.
4. Clique duas vezes em uma tabela para inserir uma consulta no editor ou use o menu de contexto para inspecionar sua estrutura.

Os resultados dependem das permissões do usuário no banco e de metadados disponibilizados pelo driver.

## 4. Escolher a conexão da aba

No seletor **BANCO DESTA ABA**, escolha:

- Uma origem, como `SRV_MYSQL`: SQL sem alias lógico é encaminhado diretamente ao banco selecionado.
- **Federado (SRV\_...)**: para SQL com prefixos lógicos de diferentes conexões.

A escolha é individual de cada aba e é lembrada na sessão.

### Exemplo nativo

Com `SRV_PG` selecionado:

```sql
SELECT codigo, nome
FROM clientes
WHERE ativo = 1;
```

### Exemplo federado

```sql
SELECT a.codigo, b.codigo
FROM SRV_MYSQL.clientes a
JOIN SRV_ORACLE.CLIENTES b ON a.codigo = b.CODIGO;
```

Os nomes das tabelas e colunas são apenas exemplos e devem ser substituídos pelos nomes reais dos seus bancos.

## 5. Executar e navegar nos resultados

- **Ctrl+Enter:** executa o comando na posição do cursor ou o trecho selecionado.
- **Ctrl+Shift+Enter:** executa um script com múltiplos comandos.
- Use as opções de **linhas/página**, **Próxima**, **Anterior** e filtros locais de resultado.
- A ordenação estável de páginas requer, em geral, `ORDER BY` apropriado na consulta.
- A contagem total, quando disponível, pode ser calculada separadamente; aguarde o processamento.

## 6. Organizar abas e SQL

- **Nova aba** cria um editor independente.
- Clique com o botão direito no título para **Fechar aba**, **Fechar outras abas** ou **Fechar abas à direita**.
- **Ctrl+S** salva o SQL; **Ctrl+O** abre arquivo `.sql`.
- **Ctrl+F / Ctrl+H:** localizar e substituir.
- **Ctrl+Shift+F:** formatação de cláusulas SQL.
- **Ctrl+Espaço:** sugestões de autocomplete.
- **Ctrl+/** (ou atalho equivalente ABNT2): comentar/descomentar.

A sessão preserva SQL e conexão da aba; salvar um script em arquivo `.sql` continua recomendado para consultas importantes.

## 7. Histórico e favoritos

Acesse **SQL → Histórico e favoritos**, pesquise pelo texto, marque ou remova favoritos e confirme a escolha para abrir o SQL em nova aba. Os favoritos são persistidos separadamente do histórico recente.

## 8. Exportar CSV

Execute uma consulta, confira a tabela exibida e clique em **Exportar CSV**. Escolha separador, codificação UTF-8/ISO-8859-1, cabeçalho e aspas. Salve o arquivo no local desejado.

**Atenção:** a exportação pela grade leva somente as linhas carregadas/visíveis naquele resultado; para grandes exportações automatizadas, use o SantySQL 5.3.7 com o driver JDBC, seguindo seu fluxo específico.

## 9. Alternar tema

Use o botão de **Tema claro/escuro** ou a opção em **Aparência**. As janelas secundárias seguem a mesma identidade visual da principal.
