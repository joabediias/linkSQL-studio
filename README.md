# SantySQL Studio 0.3

Protótipo Java 17 + Apache Calcite, para consultas federadas MySQL.

## Iniciar no Windows

Abra o PowerShell na pasta que contém `pom.xml`:

```powershell
$env:SQLBRIDGE_MYSQL_PASSWORD = Read-Host "Senha MySQL"
mvn clean compile exec:java
```

Clique em **Carregar conexões** e selecione `config/connections.example.json` (edite-o conforme seu ambiente). As senhas **não** são salvas no JSON: `passwordEnv` contém o nome da variável de ambiente.

## Recursos 0.3

- Navegador de conexões e tabelas à esquerda. Expanda uma conexão para carregar tabelas; dê dois cliques em uma tabela para inserir um SELECT no editor.
- Gerenciador básico de conexões (adicionar, editar e excluir no JSON). Após salvar, recarregue as conexões.
- Editor com várias abas e Ctrl+Enter; executa a seleção de texto quando houver.
- Grade de resultados com rolagem horizontal, colunas redimensionáveis e ordenação local.
- Exportação CSV com separador `;`, UTF-8 BOM e apenas as linhas atualmente carregadas (máximo 1.000).
- Consultas federadas `UNION ALL` e consultas simples via Apache Calcite.

Exemplo:

```sql
SELECT 'ARRAIAL' AS ORIGEM, cu.* FROM SRV_ARRAIAL.cad_usu0 cu
UNION ALL
SELECT 'MATRIZ' AS ORIGEM, cu.* FROM SRV_MATRIZ.cad_usu0 cu;
```

## Limitações importantes

- Apenas SELECT; uma instrução por execução. A execução de JOIN entre servidores e grandes volumes exige testes de desempenho e memória.
- O limite de 1.000 linhas é de visualização, **não** garante que o banco ou Calcite deixem de processar mais linhas.
- Exportação CSV exporta somente a grade atual; não exporta milhões de registros em streaming.
- A opção de gerenciar conexões não testa o acesso antes de salvar. A conexão é validada ao carregar tabelas ou executar consultas.
- O protótipo não fornece servidor de protocolo PostgreSQL para conexão do SantySQL 5.3.7.
- `useSSL=false` foi mantido para compatibilidade com o ambiente de teste; revisar TLS antes de produção.
- Evite compartilhar o JSON com dados internos dos servidores sem necessidade.
