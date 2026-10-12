# LinkSQL 2.7.1 — Editor SQL multibanco

O **LinkSQL** oferece um editor Java/Swing para consultas nativas e federadas entre MySQL, Oracle, PostgreSQL, SQL Server e Firebird.

## Execução no desenvolvimento

Requisitos: JDK 17+, Maven 3.9+.

```powershell
mvn clean compile exec:java
```

## Instalação Windows

```powershell
.\distribuicao\GERAR-WINDOWS.bat
```

O gerador passa a produzir `LinkSQL.exe` e `LinkSQL-portatil.zip`.

**Compatibilidade:** nomes internos de classes e a pasta
`%USERPROFILE%\.LinkSQL` foram mantidos deliberadamente nesta versão:
isso preserva o histórico, as sessões, as preferências e as conexões existentes.

## JDBC integrado ao processador SantySQL 5.3.7

A POC utiliza a URL `jdbc:linksql:embedded`, sem processo Gateway separado.
O driver compilado e as dependências são distribuídos em um ZIP separado.
