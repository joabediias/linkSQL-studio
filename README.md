# LinkSQL 3.3.1 — SQL Studio multibanco

**LinkSQL** é uma aplicação desktop Java 17 para explorar bancos de dados e executar consultas SQL nativas ou federadas entre **MySQL, Oracle, PostgreSQL, SQL Server e Firebird**. A interface utiliza Swing, FlatLaf e RSyntaxTextArea; as consultas federadas usam Apache Calcite e conexões JDBC.

O aplicativo principal funciona localmente no Windows, sem Docker, WSL ou um servidor intermediário. A integração com o **SantySQL 5.3.7** usa o **LinkSQL JDBC 0.5** distribuído separadamente; este projeto desktop não deve substituir o JAR estável já instalado no exportador.

## Inicialização no Windows

Requisitos: **JDK 17 ou superior**, **Maven** no PATH e acesso JDBC às bases necessárias.

```powershell
cd "E:\Projetos\linksql"
mvn clean compile exec:java
```

Para a distribuição portátil, execute `distribuicao\gerar-windows.bat`. O instalador `.exe` depende de uma instalação do WiX compatível com o JDK; detalhes em [Guia de distribuição](documentacao/07-distribuicao-windows.md).

## Documentação

Comece pelo [Manual técnico](documentacao/manual-linksql.html) (abre offline no navegador) ou pelo [Índice da documentação](documentacao/00-indice.md): contém visão de negócio, arquitetura, **inventário de cada classe Java**, fluxos de execução, manuais do usuário e do desenvolvedor, segurança, build e roteiro de testes.

## Armazenamento local

- Configuração de conexões: `%USERPROFILE%\.linksql\config\connections.json`.
- Credenciais protegidas: `%USERPROFILE%\.linksql\config\credentials.dpapi.json`, vinculadas ao usuário Windows.
- Sessão das abas: `%USERPROFILE%\.linksql\sql-session.json`.
- Histórico/favoritos: `%USERPROFILE%\.linksql\sql-history.txt` e `sql-favorites.txt`.

**Não coloque senhas, arquivos de credenciais nem dados de clientes em repositórios Git.** A pasta histórica `.santysql-studio` será preservada durante a migração, mas os dados principais passam a ficar em `.linksql`.

## Validação

```powershell
.\testes\testar-core.bat
```

O teste de arquitetura não abre bancos externos. A execução e os metadados dos seus servidores, o Windows DPAPI real e a geração do instalador continuam sujeitos ao roteiro de validação do ambiente Windows.

## Migração do nome e compatibilidade

Esta reorganização é o **LinkSQL 3.3.1**. O código Java usa `br.com.linksql`, e a pasta local principal passa a ser `%USERPROFILE%\.linksql`. Na primeira execução, o LinkSQL copia os dados existentes de `.santysql-studio` sem apagá-los e importa preferências do pacote Java anterior, se necessário.

**Importante:** o **SantySQL 5.3.7 é um aplicativo distinto**. O LinkSQL JDBC **0.5 já instalado** continua lendo uma cópia de compatibilidade dos perfis e credenciais; o código-fonte do gerador foi preparado para um JDBC **0.6**, que deve ser compilado e testado separadamente antes de substituir o 0.5.
