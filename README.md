# SantySQL Studio 0.4 (Java 17)

Aplicativo experimental de consultas federadas MySQL usando Apache Calcite. Mantém limite de 1.000 linhas exibidas;

## Executar no PowerShell

```powershell
cd C:\caminho\santySQL-studio
$env:SQLBRIDGE_MYSQL_PASSWORD = Read-Host "Senha do MySQL"
mvn clean compile exec:java
```

O arquivo `config/connections.example.json` contém as conexões. `passwordEnv` deve conter o **nome da variável de ambiente**, nunca a senha.
