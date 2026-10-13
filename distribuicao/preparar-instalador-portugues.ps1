# Recursos compativeis com o jpackage do JDK selecionado, sem copiar templates de outro JDK.
function Preparar-RecursosPortugues([string]$JdkHome, [string]$PastaTarget, [string]$Origem) {
    $jmod = Join-Path $JdkHome 'bin\jmod.exe'
    $modulo = Join-Path $JdkHome 'jmods\jdk.jpackage.jmod'
    if (!(Test-Path $jmod) -or !(Test-Path $modulo)) {
        throw 'O instalador em portugues requer um JDK completo com jmod e jdk.jpackage.jmod.'
    }
    $extraido = Join-Path $PastaTarget 'jpackage-recursos-originais'
    if (Test-Path $extraido) { Remove-Item -LiteralPath $extraido -Recurse -Force }
    Executar $jmod @('extract', '--dir', $extraido, $modulo)
    $base = Join-Path $extraido 'classes\jdk\jpackage\internal\resources'
    $template = Join-Path $base 'main.wxs'
    $strings = Join-Path $base 'MsiInstallerStrings_en.wxl'
    if (!(Test-Path $template) -or !(Test-Path $strings)) {
        throw 'Template Windows do jpackage nao reconhecido. Use JDK 17/21 com WiX 3 para esta distribuicao.'
    }
    $recursos = Join-Path $PastaTarget 'instalador-recursos-pt-BR'
    if (Test-Path $recursos) { Remove-Item -LiteralPath $recursos -Recurse -Force }
    New-Item -ItemType Directory -Force -Path $recursos | Out-Null
    Copy-Item -LiteralPath (Join-Path $Origem 'LinkSQL-pt-BR.wxl') -Destination $recursos
    [xml]$padrao = Get-Content -LiteralPath $strings -Raw -Encoding UTF8
    [xml]$traducao = Get-Content -LiteralPath (Join-Path $recursos 'LinkSQL-pt-BR.wxl') -Raw -Encoding UTF8
    $ids = @($traducao.WixLocalization.String | ForEach-Object { [string]$_.Id })
    foreach ($texto in $padrao.WixLocalization.String) {
        if ($ids -notcontains ('LinkSQL_' + [string]$texto.Id)) {
            throw "O JDK adicionou uma mensagem sem traducao: $($texto.Id). Atualize LinkSQL-pt-BR.wxl antes de distribuir."
        }
    }
    # jpackage carrega o WXL original junto com o nosso. Os identificadores
    # LinkSQL_* sao exclusivos, evitando LGHT0100 mesmo com fallback en-us.
    # Os dialogos adicionais tambem precisam apontar para esses novos nomes.
    foreach ($nome in @('main.wxs', 'InstallDirNotEmptyDlg.wxs', 'ShortcutPromptDlg.wxs')) {
        $arquivo = Join-Path $base $nome
        if (!(Test-Path $arquivo)) { continue }
        $wxs = Get-Content -LiteralPath $arquivo -Raw -Encoding UTF8
        foreach ($texto in $padrao.WixLocalization.String) {
            $id = [string]$texto.Id
            $wxs = $wxs.Replace(('!(loc.' + $id + ')'), ('!(loc.LinkSQL_' + $id + ')'))
        }
        if ($nome -eq 'main.wxs') {
            if ($wxs -notmatch 'JpProductLanguage' -or $wxs -notmatch 'schemas.microsoft.com/wix/2006/wi') {
                throw 'Formato WiX do JDK nao suportado. Padrao desta versao: JDK 17/21 e WiX 3.'
            }
            $wxs = [regex]::Replace($wxs, '(?m)(\?define\s+JpProductLanguage\s*=\s*)[^?]+', '${1}1046 ')
            if ($wxs -notmatch 'WixUI_ErrorProgressText') {
                $wxs = $wxs.Replace('</Product>', '<UIRef Id="WixUI_ErrorProgressText"/></Product>')
            }
        }
        $wxs = $wxs.Replace('[ProductName] Setup', 'Instalacao do [ProductName]')
        $wxs = $wxs.Replace('Text="Yes"', 'Text="Sim"').Replace('Text="No"', 'Text="Nao"')
        [System.IO.File]::WriteAllText((Join-Path $recursos $nome), $wxs, [System.Text.UTF8Encoding]::new($false))
    }
    return $recursos
}

function Verificar-IdiomaMsi([string]$PastaTemporaria) {
    $pacotes = @(Get-ChildItem -LiteralPath $PastaTemporaria -Filter '*.msi' -File -Recurse)
    if ($pacotes.Count -eq 0) { throw 'MSI intermediario nao encontrado para verificar o idioma do instalador.' }
    $windowsInstaller = $null
    $database = $null
    $view = $null
    $record = $null
    try {
        $windowsInstaller = New-Object -ComObject WindowsInstaller.Installer
        $database = $windowsInstaller.OpenDatabase($pacotes[0].FullName, 0)
        $view = $database.OpenView("SELECT ``Value`` FROM ``Property`` WHERE ``Property`` = 'ProductLanguage'")
        $view.Execute()
        $record = $view.Fetch()
        if ($null -eq $record -or [string]$record.StringData(1) -ne '1046') {
            throw 'O MSI gerado nao esta identificado como portugues do Brasil (1046).'
        }
        $view.Close()
        [void][System.Runtime.InteropServices.Marshal]::ReleaseComObject($record); $record = $null
        [void][System.Runtime.InteropServices.Marshal]::ReleaseComObject($view); $view = $null
        $view = $database.OpenView('SELECT `Text` FROM `Control`')
        $view.Execute()
        while ($null -ne ($record = $view.Fetch())) {
            $texto = ([string]$record.StringData(1)).Replace('&', '')
            if ($texto -match '^(Next|Back|Cancel|Finish|Install|Yes|No)\s*([<>]|\.\.\.)?$') {
                throw "Controle do instalador permaneceu em ingles: $texto."
            }
            [void][System.Runtime.InteropServices.Marshal]::ReleaseComObject($record); $record = $null
        }
        $view.Close()
    } finally {
        foreach ($com in @($record, $view, $database, $windowsInstaller)) {
            if ($null -ne $com) { [void][System.Runtime.InteropServices.Marshal]::ReleaseComObject($com) }
        }
    }
}
