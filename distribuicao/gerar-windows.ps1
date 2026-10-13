# Requer Windows 10/11 x64, JDK 17+ com jpackage e Maven.
# WiX Toolset e necessario apenas para criar o instalador .exe.
# Execute a partir de distribuicao\GERAR-WINDOWS.bat.
param(
    [switch]$SomentePortatil,
    [switch]$SemZip
)
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version 2.0
. (Join-Path $PSScriptRoot 'preparar-instalador-portugues.ps1')

function Executar([string]$Comando, [string[]]$Parametros) {
    Write-Host ('> ' + $Comando + ' ' + ($Parametros -join ' ')) -ForegroundColor Cyan
    & $Comando @Parametros
    if ($LASTEXITCODE -ne 0) { throw "Falha ao executar $Comando (codigo $LASTEXITCODE)." }
}

try {
    if ($env:OS -ne 'Windows_NT') { throw 'Esta geracao deve ser feita no Windows (jpackage cria arquivos para o sistema atual).' }
    $raiz = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
    Set-Location $raiz
    if (!(Test-Path (Join-Path $raiz 'pom.xml'))) { throw 'pom.xml nao encontrado. Extraia o ZIP na raiz do projeto Maven.' }
    $mavenInfo = Get-Command mvn.cmd -ErrorAction SilentlyContinue
    if (!$mavenInfo) { throw 'Maven nao encontrado no PATH. Verifique mvn -version.' }
    $maven = $mavenInfo.Source

    # Descobre o JDK mesmo quando JAVA_HOME ou PATH nao estao configurados.
    # Isso e comum em computadores nos quais Maven e Java usam configuracoes
    # diferentes ou o projeto foi movido para outra unidade de disco.
    $jpackageCandidatos = [System.Collections.Generic.List[string]]::new()
    if ($env:JAVA_HOME) {
        $jpackageCandidatos.Add((Join-Path $env:JAVA_HOME 'bin\jpackage.exe'))
    }
    $jpackageInfo = Get-Command jpackage.exe -ErrorAction SilentlyContinue
    if ($jpackageInfo) { $jpackageCandidatos.Add($jpackageInfo.Source) }

    # javac costuma apontar para um JDK completo, mesmo quando java aponta
    # para um atalho do JRE (por exemplo, Oracle javapath).
    $javacInfo = Get-Command javac.exe -ErrorAction SilentlyContinue
    if ($javacInfo) {
        $jpackageCandidatos.Add((Join-Path (Split-Path $javacInfo.Source -Parent) 'jpackage.exe'))
    }

    # Locais padrao de diferentes distribuicoes de JDK no Windows.
    $programFilesRoots = @($env:ProgramFiles, ${env:ProgramW6432}, ${env:ProgramFiles(x86)}) |
        Where-Object { -not [string]::IsNullOrWhiteSpace($_) } | Select-Object -Unique
    foreach ($pf in $programFilesRoots) {
        foreach ($vendor in @('Java', 'Eclipse Adoptium', 'Microsoft', 'Amazon Corretto', 'Azul Systems', 'BellSoft')) {
            $vendorDir = Join-Path $pf $vendor
            if (!(Test-Path -LiteralPath $vendorDir -PathType Container)) { continue }
            $instalacoes = @(Get-ChildItem -LiteralPath $vendorDir -Directory -ErrorAction SilentlyContinue |
                Sort-Object Name -Descending)
            foreach ($instalacao in $instalacoes) {
                $jpackageCandidatos.Add((Join-Path $instalacao.FullName 'bin\jpackage.exe'))
            }
        }
    }

    # Registro Oracle JavaSoft JDK (quando a instalacao usa outra pasta).
    foreach ($regKey in @('HKLM:\SOFTWARE\JavaSoft\JDK',
                          'HKLM:\SOFTWARE\JavaSoft\Java Development Kit',
                          'HKCU:\SOFTWARE\JavaSoft\JDK')) {
        if (!(Test-Path -LiteralPath $regKey)) { continue }
        $registro = @(Get-Item -LiteralPath $regKey -ErrorAction SilentlyContinue)
        $registro += @(Get-ChildItem -LiteralPath $regKey -ErrorAction SilentlyContinue)
        foreach ($item in $registro) {
            if ($null -eq $item) { continue }
            $dados = Get-ItemProperty -LiteralPath $item.PSPath -ErrorAction SilentlyContinue
            if ($null -ne $dados -and $dados.PSObject.Properties['JavaHome']) {
                $jpackageCandidatos.Add((Join-Path ([string]$dados.JavaHome) 'bin\jpackage.exe'))
            }
        }
    }

    $jpackage = $null
    foreach ($candidato in ($jpackageCandidatos | Select-Object -Unique)) {
        if ((Test-Path -LiteralPath $candidato -PathType Leaf) -and
            (Test-Path -LiteralPath (Join-Path (Split-Path $candidato -Parent) 'jar.exe') -PathType Leaf) -and
            (Test-Path -LiteralPath (Join-Path (Split-Path $candidato -Parent) 'java.exe') -PathType Leaf)) {
            $jpackage = $candidato
            break
        }
    }
    if (!$jpackage) {
        $javaHomeExibido = if ($env:JAVA_HOME) { $env:JAVA_HOME } else { '(nao definido)' }
        throw ("jpackage.exe nao encontrado. JAVA_HOME: $javaHomeExibido. " +
            'No PowerShell, execute: where.exe jpackage; where.exe javac; ' +
            'Get-ChildItem "C:\Program Files\Java" -Directory. ' +
            'Se existir um JDK 17+, configure JAVA_HOME para a pasta do JDK e adicione seu bin ao PATH.')
    }
    Write-Host "jpackage localizado: $jpackage" -ForegroundColor Green
    $jdkBin = Split-Path $jpackage -Parent
    $jdkHome = Split-Path $jdkBin -Parent
    $env:JAVA_HOME = $jdkHome
    $env:PATH = $jdkBin + ';' + $env:PATH
    $jarExe = Join-Path $jdkBin 'jar.exe'
    $javaExe = Join-Path $jdkBin 'java.exe'
    if (!(Test-Path $jarExe) -or !(Test-Path $javaExe)) { throw 'JDK incompleto: jar.exe ou java.exe ausente.' }
    # Capture toda a saida antes de verificar o codigo de retorno.
    # Select-Object -First 1 diretamente na saida do processo pode fechar o pipe
    # enquanto o Java ainda escreve outras linhas, gerando falso erro no Windows.
    $linhasVersaoJava = @(& $javaExe --version 2>&1)
    $codigoVersaoJava = $LASTEXITCODE
    if ($codigoVersaoJava -ne 0) {
        $detalhes = ($linhasVersaoJava | Out-String).Trim()
        throw "Falha ao consultar a versao do JDK (codigo $codigoVersaoJava). $detalhes"
    }
    $versaoJava = [string]($linhasVersaoJava | Where-Object { [string]$_ -match '^\s*(openjdk|java)\s+\d+' } | Select-Object -First 1)
    if (!$versaoJava) {
        throw "Versao do Java nao reconhecida. Saida: $(($linhasVersaoJava | Out-String).Trim())"
    }
    if ($versaoJava -notmatch '^\s*(?:openjdk|java)\s+(\d+)(?:[.\s+\-]|$)') {
        throw "Versao do Java nao reconhecida: $versaoJava"
    }
    if ([int]$Matches[1] -lt 17) { throw 'JDK 17 ou superior necessario.' }
    Write-Host "JDK: $versaoJava" -ForegroundColor Green

    $pastaTarget = Join-Path $raiz 'target'
    $entrada = Join-Path $pastaTarget 'distribuicao-input'
    $lib = Join-Path $entrada 'lib'
    $destino = Join-Path $pastaTarget 'distribuicao'
    if (Test-Path $entrada) { Remove-Item -LiteralPath $entrada -Recurse -Force }
    if (Test-Path $destino) { Remove-Item -LiteralPath $destino -Recurse -Force }
    New-Item -ItemType Directory -Force -Path $lib, $destino | Out-Null

    Executar $maven @('-B', '-DskipTests', 'clean', 'package')
    # O Maven clean remove o target: recriamos as pastas depois da compilacao.
    New-Item -ItemType Directory -Force -Path $lib, $destino | Out-Null
    Executar $maven @('-B', 'org.apache.maven.plugins:maven-dependency-plugin:3.6.1:copy-dependencies',
        '-DincludeScope=runtime', "-DoutputDirectory=$lib")
    $dependencias = @(Get-ChildItem -LiteralPath $lib -Filter '*.jar' -File | Sort-Object Name)
    if ($dependencias.Count -eq 0) { throw 'Nenhuma dependencia runtime foi copiada. Verifique o pom.xml.' }
    # Verificacoes locais obrigatorias antes de gerar os pacotes 4.0.0.
    $classpathTestes = (Join-Path $pastaTarget 'test-classes') + ';' +
        (Join-Path $pastaTarget 'classes') + ';' + (Join-Path $lib '*')
    foreach ($teste in @('LinkSqlCoreRegressionTest', 'LinkSqlMigrationRegressionTest', 'LinkSqlRelease40RegressionTest')) {
        Executar $javaExe @('-Djava.awt.headless=true', '-Xmx512m', '-cp', $classpathTestes, "br.com.linksql.$teste")
    }

    # Escolhe somente o JAR principal do Maven (nao sources, original, shaded ou javadoc).
    [xml]$pom = Get-Content (Join-Path $raiz 'pom.xml') -Raw
    $artifactId = [string]$pom.project.artifactId
    $projectVersion = [string]$pom.project.version
    if (!$projectVersion) { $projectVersion = [string]$pom.project.parent.version }
    $nomePadrao = "$artifactId-$projectVersion"
    # O bloco <build> e a propriedade <finalName> sao opcionais no pom.xml.
    # Com Set-StrictMode, acessar diretamente uma propriedade ausente causa erro.
    # SelectSingleNode devolve $null sem falhar, inclusive com namespace XML.
    $noFinalName = $pom.SelectSingleNode("/*[local-name()='project']/*[local-name()='build']/*[local-name()='finalName']")
    $nomeFinal = if ($null -ne $noFinalName) { [string]$noFinalName.InnerText } else { '' }
    $nomeFinal = $nomeFinal.Trim()
    if ([string]::IsNullOrWhiteSpace($nomeFinal) -or $nomeFinal.Contains('${')) { $nomeFinal = $nomePadrao }
    $jarPrincipal = Join-Path $pastaTarget ($nomeFinal + '.jar')
    if (!(Test-Path $jarPrincipal)) {
        $candidatos = @(Get-ChildItem -Path $pastaTarget -Filter '*.jar' -File |
            Where-Object { $_.Name -notmatch '(^original-|-(sources|javadoc|tests)\.jar$)' })
        if ($candidatos.Count -ne 1) { throw 'Nao consegui identificar o JAR principal. Confira target e pom.xml.' }
        $jarPrincipal = $candidatos[0].FullName
    }
    $nomeJarDistribuicao = 'LinkSQL.jar'
    $jarDistribuicao = Join-Path $entrada $nomeJarDistribuicao
    Copy-Item -LiteralPath $jarPrincipal -Destination $jarDistribuicao

    # JAR fino: as bibliotecas continuam separadas, evitando conflitos de
    # META-INF/services do Apache Calcite, MySQL Connector e outras bibliotecas.
    $classpath = ($dependencias | ForEach-Object { 'lib/' + $_.Name }) -join ' '
    $manifesto = Join-Path $pastaTarget 'manifest-linksql.mf'

    # O formato MANIFEST.MF limita cada linha fisica a 72 bytes.
    # O Class-Path das dependencias pode ultrapassar esse limite. Dobramos
    # a linha com um espaco inicial em cada continuacao (especificacao JAR).
    # Nomes dos JARs Maven usados aqui sao ASCII, entao 70 chars < 72 bytes.
    $cabecalhoClasspath = 'Class-Path: ' + $classpath
    $linhasManifesto = [System.Collections.Generic.List[string]]::new()
    # O JAR principal ja possui Manifest-Version; nao repetir no merge.
    $linhasManifesto.Add('Main-Class: br.com.linksql.Main')
    while ($cabecalhoClasspath.Length -gt 70) {
        $linhasManifesto.Add($cabecalhoClasspath.Substring(0, 70))
        $cabecalhoClasspath = ' ' + $cabecalhoClasspath.Substring(70)
    }
    $linhasManifesto.Add($cabecalhoClasspath)
    $linhasManifesto.Add('')
    $linhasManifesto.Add('')
    [System.IO.File]::WriteAllText($manifesto,
        ($linhasManifesto -join "`r`n"), [System.Text.Encoding]::ASCII)
    Write-Host ("Manifesto JAR gerado com " + $linhasManifesto.Count + " linhas (Class-Path dividido corretamente).") -ForegroundColor Green
    Executar $jarExe @('--update', '--file', $jarDistribuicao, '--manifest', $manifesto)

    # Nao incluir nenhum JSON de conexoes no instalador (podem conter dados internos).
    @'
LinkSQL 4.0.0
- O arquivo de conexoes da versao instalada fica em:
  %USERPROFILE%\.linksql\config\connections.json
- Configure suas conexoes pelo menu Conexoes > Gerenciar conexoes.
- As conexoes sao abertas sob demanda; a senha e solicitada no primeiro
  uso se nao houver credencial DPAPI valida ou variavel de ambiente.
- Senhas lembradas ficam protegidas pelo Windows, nao em texto puro no JSON.
- Historico, sessoes, preferencias e logs continuam no perfil do usuario.
'@ | Set-Content -LiteralPath (Join-Path $entrada 'LEIA-ME.txt') -Encoding UTF8

    $icones = @()
    $icone = Join-Path $PSScriptRoot 'LinkSQL.ico'
    if (Test-Path $icone) { $icones = @('--icon', $icone) }
    # Modulos extras para JDBC, GUI e bibliotecas usadas pelo Calcite.
    $modulos = 'java.desktop,java.sql,java.naming,java.management,java.scripting,java.net.http,java.instrument,java.prefs,java.security.jgss,jdk.crypto.ec,jdk.unsupported,jdk.localedata'
    $jpackageBase = @('--name', 'LinkSQL', '--app-version', '4.0.0',
        '--vendor', 'LinkSQL', '--input', $entrada,
        '--main-jar', $nomeJarDistribuicao, '--main-class', 'br.com.linksql.Main',
        '--java-options', '-Dlinksql.packaged=true',
        '--java-options', '-Duser.language=pt', '--java-options', '-Duser.country=BR',
        '--add-modules', $modulos) + $icones

    Write-Host 'Criando aplicativo portatil (EXE com Java incluido)...' -ForegroundColor Yellow
    Executar $jpackage (@('--type', 'app-image', '--dest', $destino) + $jpackageBase)
    $executavel = Join-Path $destino 'LinkSQL\LinkSQL.exe'
    if (!(Test-Path $executavel)) { throw 'jpackage nao produziu o EXE portatil esperado.' }
    Write-Host "Portatil criado: $executavel" -ForegroundColor Green

    if (!$SemZip) {
        $zipPortatil = Join-Path $destino 'LinkSQL-4.0.0-portatil.zip'
        Compress-Archive -Path (Join-Path $destino 'LinkSQL') -DestinationPath $zipPortatil -CompressionLevel Optimal -Force
        Write-Host "ZIP portatil: $zipPortatil" -ForegroundColor Green
    }

    if (!$SomentePortatil) {
        $temWix3 = (Get-Command candle.exe -ErrorAction SilentlyContinue) -and (Get-Command light.exe -ErrorAction SilentlyContinue)
        if (!$temWix3) {
            Write-Warning 'WiX Toolset ausente. Aplicativo portatil pronto; para gerar instalador EXE use JDK 17/21 com WiX 3 no PATH e execute novamente.'
        } else {
            Write-Host 'Criando instalador EXE...' -ForegroundColor Yellow
            try {
                $recursosPt = Preparar-RecursosPortugues $jdkHome $pastaTarget (Join-Path $PSScriptRoot 'recursos-windows')
                $installerWork = Join-Path $pastaTarget 'instalador-verificacao'
                if (Test-Path $installerWork) { Remove-Item -LiteralPath $installerWork -Recurse -Force }
                Executar $jpackage (@('--type', 'exe', '--dest', $destino,
                    '--resource-dir', $recursosPt, '--temp', $installerWork, '--verbose',
                    '--win-menu', '--win-shortcut', '--win-per-user-install', '--win-dir-chooser',
                    '--win-upgrade-uuid', '2eb6546d-303d-4b43-883b-516298db0270') + $jpackageBase)
                Verificar-IdiomaMsi $installerWork
                Write-Host 'Instalador Windows 4.0.0 em portugues do Brasil criado em target\distribuicao\.' -ForegroundColor Green
            } catch {
                Write-Warning "Nao foi possivel gerar o instalador EXE: $($_.Exception.Message)"
                Write-Warning 'O EXE portatil ja esta pronto. O instalador nao foi validado; nao o distribua.'
                $instaladorFalho = Join-Path $destino 'LinkSQL-4.0.0.exe'
                if (Test-Path $instaladorFalho) { Remove-Item -LiteralPath $instaladorFalho -Force }
                throw
            }
        }
    }
    Write-Host 'CONCLUIDO: abra target\distribuicao\ para ver os arquivos.' -ForegroundColor Green
    exit 0
} catch {
    Write-Error $_.Exception.Message
    exit 1
}
