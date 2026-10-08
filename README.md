# SQLBridge Studio — Protótipo 0.1

**Estado:** código inicial para validação; não é um servidor SQL e **ainda não conecta ao SantySQL**.

## Objetivo

Executar SQL federado entre instâncias MySQL existentes, sem importar os registros, usando Apache Calcite e um editor Swing mínimo. Testar em máquina de desenvolvimento antes de implantar na VM de 4 GB.

## Requisitos

- Windows 10/11, JDK 17, Apache Maven 3.9+ e acesso aos MySQL.
- Credenciais de usuário MySQL com permissão somente leitura.
- O primeiro teste pressupõe um único banco/schema MySQL por conexão virtual. Para acessar múltiplos schemas no mesmo servidor, cadastre uma conexão virtual por schema.

## Preparar

1. Copie `config/connections.example.json` para `config/connections.json` e preencha o nome real de cada schema MySQL. Os endereços de ARRAIAL e MATRIZ são apenas os dois servidores iniciais.
2. Abra o PowerShell **na pasta do projeto** e defina a senha da sessão: `$env:SQLBRIDGE_MYSQL_PASSWORD = Read-Host 'Senha MySQL'` (o Read-Host simples devolve texto). Não salve senha no arquivo JSON nem no repositório.
3. Execute `mvn -q compile exec:java`.
4. Clique em **Carregar conexões** e selecione `config/connections.json`.
5. Substitua `SUA_TABELA` por uma tabela real e execute uma consulta de teste.

## SQL de teste

```sql
SELECT 'ARRAIAL' AS ORIGEM, t.CODIGO
FROM SRV_ARRAIAL.SUA_TABELA t
UNION ALL
SELECT 'MATRIZ' AS ORIGEM, t.CODIGO
FROM SRV_MATRIZ.SUA_TABELA t
```

A sintaxe depende dos metadados e da versão do Calcite; a consulta acima é um exemplo, não um teste já validado contra seus bancos.

## Limitações atuais

- Não implementa protocolo PostgreSQL, ODBC ou servidor JDBC remoto. **SantySQL não está integrado nesta versão.**
- Exibe no máximo 1.000 linhas por consulta e não exporta CSV ainda.
- Consultas distribuídas com JOIN/ORDER BY podem consumir memória significativa; esta versão não implementa spill para disco nem limites rígidos por operador.
- A configuração usa `useSSL=false` apenas para teste em rede interna; produção requer configuração TLS adequada.
- O projeto ainda precisa de build, testes reais, diagnóstico de SQL e validação de desempenho. Não execute consultas pesadas em produção sem avaliar o plano e o impacto.

## Próxima entrega

Validar consultas entre duas fontes, escolher um gateway PostgreSQL-wire compatível e comprovar conexão do SantySQL antes de investir na interface completa.
