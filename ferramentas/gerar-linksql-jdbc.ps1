# LinkSQL JDBC - gerador automatico de JAR consolidado (sem Python)
# Utiliza as fontes atualizadas do projeto e Maven Shade para montar um unico JAR.
param([switch]$PularTeste)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

function Executar-Nativo {
    param([string]$Executavel, [string[]]$Argumentos)
    Write-Host "> $Executavel $($Argumentos -join ' ')"
    & $Executavel @Argumentos
    if ($LASTEXITCODE -ne 0) { throw "Comando falhou ($LASTEXITCODE): $Executavel" }
}

function Localizar-Programa {
    param([string]$Nome, [string[]]$Candidatos)
    foreach ($c in $Candidatos) { if ($c -and (Test-Path -LiteralPath $c -PathType Leaf)) { return $c } }
    $cmd = Get-Command $Nome -ErrorAction SilentlyContinue
    if ($cmd) { return $cmd.Source }
    return $null
}

try {
    $raiz = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
    $modulo = Join-Path $raiz 'integracao\linksql-jdbc'
    $pom = Join-Path $modulo 'pom.xml'
    $fontesMotor = Join-Path $raiz 'src\main\java\br\com\linksql'
    $fontesDriver = Join-Path $raiz 'integracao\jdbc-src\br\com\linksql\jdbc'
    if (-not (Test-Path -LiteralPath $pom -PathType Leaf)) { throw "pom.xml do gerador nao encontrado: $pom" }
    if (-not (Test-Path -LiteralPath $fontesMotor -PathType Container)) { throw "Fontes do LinkSQL nao encontrados: $fontesMotor" }

    $javaHome = $env:JAVA_HOME
    $jdkCandidatos = @()
    if ($javaHome) { $jdkCandidatos += (Join-Path $javaHome 'bin\javac.exe') }
    $jdkCandidatos += 'C:\Program Files\Java\jdk-17\bin\javac.exe'
    $javac = Localizar-Programa 'javac.exe' $jdkCandidatos
    if (-not $javac) { throw 'JDK 17+ nao encontrado. Configure JAVA_HOME para a pasta do JDK.' }
    $jdkBin = Split-Path -Parent $javac
    $java = Join-Path $jdkBin 'java.exe'
    $jarExe = Join-Path $jdkBin 'jar.exe'
    if (-not (Test-Path $java) -or -not (Test-Path $jarExe)) { throw "Instalacao incompleta do JDK: $jdkBin" }
    $versao = (& $javac -version 2>&1 | Out-String).Trim()
    if ($LASTEXITCODE -ne 0) { throw 'Falha ao executar javac -version.' }
    if ($versao -notmatch '(\d+)') { throw "Versao do compilador desconhecida: $versao" }
    $major = [int]$Matches[1]
    if ($major -lt 17) { throw "Este projeto exige Java 17 ou superior. Detectado: $versao" }
    Write-Host "JDK: $versao" -ForegroundColor Cyan
    $env:JAVA_HOME = Split-Path -Parent $jdkBin
    $env:PATH = "$jdkBin;$env:PATH"

    $mavenCandidatos = @(
        'C:\Apache\Maven\bin\mvn.cmd',
        'C:\Apache\maven\bin\mvn.cmd'
    )
    $mvn = Localizar-Programa 'mvn.cmd' $mavenCandidatos
    if (-not $mvn) { throw 'Maven nao encontrado. Instale Maven 3.9+ ou configure o PATH.' }

    [xml]$pomXml = Get-Content -LiteralPath $pom -Raw -Encoding UTF8
    $noVersao = $pomXml.SelectSingleNode("/*[local-name()='project']/*[local-name()='version']")
    if (-not $noVersao) { throw 'Versao nao encontrada no pom.xml do LinkSQL JDBC.' }
    $versaoBundle = $noVersao.InnerText.Trim()
    if ($versaoBundle -notmatch '^\d+\.\d+(\.\d+)?$') { throw "Versao nao suportada: $versaoBundle" }

    # Mantemos somente as classes que fazem parte do engine, sem Main.java / Swing do editor.
    $classes = @(
        'FederatedCountSupport', 'FederatedQuerySupport', 'FirebirdCompatDialect',
        'ReadOnlySqlGuard', 'JdbcConnectionProfile', 'ConnectionVault', 'LinkSqlPaths', 'LinkSqlEmbeddedEngine',
        'NativeJdbcPaging', 'NativeQueryCount', 'NativeServerPaging', 'NativeSqlRouter',
        'SingleServerSqlRouter', 'SqlConnectionRegistry', 'SqlDatabaseType',
        'SqlPageTotals', 'SqlQueryPlanner', 'SqlResultModels'
    )
    $classesJDBC = @('LinkSqlDriver', 'EmbeddedConnection', 'EmbeddedStatement')
    $alvoModulo = Join-Path $modulo 'target'
    $staging = Join-Path $alvoModulo 'build-src'
    if (Test-Path -LiteralPath $alvoModulo) { Remove-Item -LiteralPath $alvoModulo -Recurse -Force }
    $destMotor = Join-Path $staging 'br\com\linksql'
    $destDriver = Join-Path $staging 'br\com\linksql\jdbc'
    New-Item -ItemType Directory -Path $destMotor, $destDriver -Force | Out-Null
    foreach ($name in $classes) {
        $origem = Join-Path $fontesMotor ($name + '.java')
        if (-not (Test-Path -LiteralPath $origem -PathType Leaf)) {
            throw "Classe necessaria nao encontrada: $origem. Atualize os fontes do LinkSQL antes de gerar o driver."
        }
        Copy-Item -LiteralPath $origem -Destination $destMotor
    }
    foreach ($name in $classesJDBC) {
        $origem = Join-Path $fontesDriver ($name + '.java')
        if (-not (Test-Path -LiteralPath $origem -PathType Leaf)) { throw "Fonte JDBC nao encontrado: $origem" }
        Copy-Item -LiteralPath $origem -Destination $destDriver
    }
    $engine = Get-Content -LiteralPath (Join-Path $destMotor 'LinkSqlEmbeddedEngine.java') -Raw -Encoding UTF8
    $registry = Get-Content -LiteralPath (Join-Path $destMotor 'SqlConnectionRegistry.java') -Raw -Encoding UTF8
    $vault = Get-Content -LiteralPath (Join-Path $destMotor 'ConnectionVault.java') -Raw -Encoding UTF8
    if ($registry -notmatch 'ConnectionVault.get' -or $vault -notmatch 'LINKSQL_DPAPI_V1') {
        throw 'Fontes do cofre DPAPI desatualizados. Use as classes LinkSQL 3.3.1 revisadas, incluindo ConnectionVault.'
    }
    if ($engine -notmatch 'configureLazy' -or $registry -notmatch 'configureLazy') {
        throw 'Fontes JDBC sem configureLazy. Verifique o LinkSqlEmbeddedEngine e o SqlConnectionRegistry na fonte 3.3.1.'
    }

    Executar-Nativo -Executavel $mvn -Argumentos @('-B', '-ntp', '-f', $pom, '-DskipTests', 'package')

    $jarOrigem = Join-Path $alvoModulo ("linksql-jdbc-bundle-{0}.jar" -f $versaoBundle)
    if (-not (Test-Path -LiteralPath $jarOrigem -PathType Leaf)) { throw "JAR nao encontrado apos Maven Shade: $jarOrigem" }
    if ((Get-Item -LiteralPath $jarOrigem).Length -lt 4000000) {
        throw 'JAR inesperadamente pequeno: pode estar faltando o motor Calcite.'
    }
    # Inspeciona o conteudo para nao publicar um JAR sem o registro JDBC ou as classes necessarias.
    $entradas = @(& $jarExe tf $jarOrigem)
    if ($LASTEXITCODE -ne 0) { throw 'Falha ao ler o conteudo do JAR gerado.' }
    foreach ($obrigatorio in @(
        'br/com/linksql/jdbc/LinkSqlDriver.class',
        'br/com/linksql/LinkSqlEmbeddedEngine.class',
        'br/com/linksql/ConnectionVault.class',
        'org/apache/calcite/jdbc/CalciteConnection.class',
        'META-INF/services/java.sql.Driver'
    )) {
        if ($entradas -notcontains $obrigatorio) { throw "Classe ou recurso ausente do bundle: $obrigatorio" }
    }
    # Nao publicar drivers de fornecedores junto ao bundle; exportador ja possui os seus.
    foreach ($indesejado in @(
        'com/mysql/cj/jdbc/Driver.class',
        'oracle/jdbc/OracleDriver.class',
        'org/postgresql/Driver.class',
        'com/microsoft/sqlserver/jdbc/SQLServerDriver.class',
        'org/firebirdsql/jdbc/FBDriver.class'
    )) {
        if ($entradas -contains $indesejado) { throw "Driver de banco duplicado no bundle: $indesejado" }
    }

    $saida = Join-Path $raiz 'target\linksql-jdbc'
    New-Item -ItemType Directory -Force -Path $saida | Out-Null
    $jarFinal = Join-Path $saida ("linksql-jdbc-bundle-{0}.jar" -f $versaoBundle)
    # Testar ANTES de publicar; caso falhe, nao substituir o jar anterior no SantySQL.
    if (-not $PularTeste) {
        $teste = Join-Path $modulo 'testes\VerificarLinkSqlJdbc.java'
        if (-not (Test-Path -LiteralPath $teste -PathType Leaf)) { throw "Teste JDBC ausente: $teste" }
        $testClasses = Join-Path $alvoModulo 'teste-classes'
        New-Item -ItemType Directory -Force -Path $testClasses | Out-Null
        Executar-Nativo -Executavel $javac -Argumentos @('--release', '17', '-cp', $jarOrigem, '-d', $testClasses, $teste)
        $testClasspath = "$jarOrigem;$testClasses"
        Executar-Nativo -Executavel $java -Argumentos @('-cp', $testClasspath, 'VerificarLinkSqlJdbc')
    }
    Copy-Item -LiteralPath $jarOrigem -Destination $jarFinal -Force
    $hash = (Get-FileHash -LiteralPath $jarFinal -Algorithm SHA256).Hash.ToLowerInvariant()
    $info = @(
        "LinkSQL JDBC Bundle $versaoBundle",
        "Arquivo: $jarFinal",
        "SHA256: $hash",
        "Criado: $(Get-Date -Format 'yyyy-MM-dd HH:mm:ss')",
        'Drivers externos MySQL/Oracle/PostgreSQL/SQL Server/Firebird: nao inclusos',
        'Instale somente apos validar seus bancos no ambiente de teste.'
    )
    $info | Set-Content -LiteralPath (Join-Path $saida 'BUILD-INFO.txt') -Encoding UTF8
    Write-Host ''
    Write-Host 'GERACAO CONCLUIDA!' -ForegroundColor Green
    Write-Host "JAR: $jarFinal"
    Write-Host "SHA256: $hash"
    Write-Host "Teste: $(if ($PularTeste) {'ignorado'} else {'aprovado'})"
    Write-Host 'Nao substituimos nenhum driver na instalacao do SantySQL.'
    exit 0
} catch {
    Write-Error "ERRO na geracao do LinkSQL JDBC: $($_.Exception.Message)"
    exit 1
}
