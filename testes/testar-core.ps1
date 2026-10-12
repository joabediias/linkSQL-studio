# Executa testes autocontidos de arquitetura (sem JUnit e sem banco remoto).
# Requer JDK 17+ e Maven no Windows.
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version 2.0
try {
    $root = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
    Set-Location $root
    $mvn = Get-Command mvn.cmd -ErrorAction SilentlyContinue
    if ($null -eq $mvn) { throw 'mvn.cmd nao encontrado no PATH. Confira mvn -version.' }
    $java = Get-Command java.exe -ErrorAction SilentlyContinue
    if ($null -eq $java) { throw 'java.exe nao encontrado no PATH. Confira JAVA_HOME.' }

    & $mvn.Source -B test-compile
    if ($LASTEXITCODE -ne 0) { throw 'Falha no Maven test-compile.' }

    $cpFile = Join-Path $root 'target\linksql-test-classpath.txt'
    & $mvn.Source -B 'org.apache.maven.plugins:maven-dependency-plugin:3.6.1:build-classpath' '-Dmdep.includeScope=test' "-Dmdep.outputFile=$cpFile"
    if ($LASTEXITCODE -ne 0 -or !(Test-Path $cpFile)) { throw 'Falha ao resolver classpath Maven.' }
    $dependencies = (Get-Content -LiteralPath $cpFile -Raw).Trim()
    $classpath = (Join-Path $root 'target\test-classes') + ';' + (Join-Path $root 'target\classes') + ';' + $dependencies
    & $java.Source -cp $classpath 'br.com.linksql.LinkSqlCoreRegressionTest'
    if ($LASTEXITCODE -ne 0) { throw 'Regressao core falhou.' }
    & $java.Source -cp $classpath 'br.com.linksql.LinkSqlMigrationRegressionTest'
    if ($LASTEXITCODE -ne 0) { throw 'Regressao core falhou. Nao aprove a versao.' }
    Write-Host 'Testes de arquitetura concluidos.' -ForegroundColor Green
    exit 0
} catch {
    Write-Error $_.Exception.Message
    exit 1
}
