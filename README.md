# SantySQL Studio 0.4 (Java 17)

Aplicativo experimental de consultas federadas MySQL usando Apache Calcite. Mantém limite de 1.000 linhas exibidas; CSV exporta somente as linhas carregadas.

## Executar no PowerShell

```powershell
cd C:\caminho\santySQL-studio
$env:SQLBRIDGE_MYSQL_PASSWORD = Read-Host "Senha do MySQL"
mvn clean compile exec:java
```

O arquivo `config/connections.example.json` contém as conexões. `passwordEnv` deve conter o **nome da variável de ambiente**, nunca a senha.

## Novidades 0.4

- Ctrl+Enter ou botão Executar: executa o SQL selecionado; sem seleção, executa somente o comando em que está o cursor, delimitado por `;` fora de strings e comentários.
- Duplo clique em uma tabela: insere `SELECT * ... FETCH NEXT 100 ROWS ONLY;` no cursor.
- Expanda uma tabela para carregar colunas e tipos MySQL sob demanda.
- Clique direito em tabela para consultar 100 linhas ou copiar nome qualificado.
- Título corrigido para evitar caractere de travessão exibido incorretamente.

## Observações

- Consultas de escrita/DDL, procedimentos SQL com delimitadores personalizados e scripts com múltiplas instruções não são suportados pelo executor de leitura Calcite.
- Consultas federadas pesadas podem consumir memória; valide `JOIN` com filtros e pequenas tabelas.
- Requer rede e permissões MySQL para cada servidor configurado.
