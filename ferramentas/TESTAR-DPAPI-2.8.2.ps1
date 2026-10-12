# Diagnostico da ponte PowerShell + DPAPI, sem acessar senhas reais.
$ErrorActionPreference = 'Stop'
$marker = 'LINKSQL_DPAPI_V1:'
$script = @'
$ErrorActionPreference='Stop';
try {
  Add-Type -AssemblyName System.Security -ErrorAction Stop;
  $input64=[Console]::In.ReadLine();
  if ([string]::IsNullOrWhiteSpace($input64)) { throw 'Entrada vazia para DPAPI' };
  $raw=[Convert]::FromBase64String($input64);
  $scope=[System.Security.Cryptography.DataProtectionScope]::CurrentUser;
  $out=if ('__MODE__' -eq 'protect') {
    [System.Security.Cryptography.ProtectedData]::Protect($raw,$null,$scope)
  } else {
    [System.Security.Cryptography.ProtectedData]::Unprotect($raw,$null,$scope)
  };
  [Console]::Out.WriteLine('LINKSQL_DPAPI_V1:'+[Convert]::ToBase64String($out));
  exit 0
} catch { [Console]::Error.WriteLine('DPAPI: '+$_.Exception.Message); exit 2 }
'@
function Invoke-DpapiBridge([string]$mode, [byte[]]$bytes) {
  $command = $script.Replace('__MODE__', $mode)
  $encoded = [Convert]::ToBase64String([System.Text.Encoding]::Unicode.GetBytes($command))
  $psi = New-Object System.Diagnostics.ProcessStartInfo
  $psi.FileName = 'powershell.exe'
  $psi.Arguments = '-NoLogo -NoProfile -NonInteractive -EncodedCommand ' + $encoded
  $psi.UseShellExecute = $false
  $psi.RedirectStandardInput = $true
  $psi.RedirectStandardOutput = $true
  $psi.RedirectStandardError = $true
  $psi.CreateNoWindow = $true
  $proc = New-Object System.Diagnostics.Process
  $proc.StartInfo = $psi
  try {
    if (-not $proc.Start()) { throw 'Nao foi possivel iniciar PowerShell.' }
    $taskOut = $proc.StandardOutput.ReadToEndAsync()
    $taskErr = $proc.StandardError.ReadToEndAsync()
    $proc.StandardInput.WriteLine([Convert]::ToBase64String($bytes))
    $proc.StandardInput.Close()
    if (-not $proc.WaitForExit(15000)) { $proc.Kill(); throw 'Tempo limite de 15 segundos.' }
    $stdout = $taskOut.Result
    $stderr = $taskErr.Result
    if ($proc.ExitCode -ne 0) { throw ('Falha DPAPI - codigo ' + $proc.ExitCode + ': ' + $stderr) }
    $line = ($stdout -split '\r?\n' | Where-Object { $_.Trim().StartsWith($marker) } | Select-Object -Last 1)
    if (-not $line) { throw 'O PowerShell nao devolveu a linha identificada LINKSQL_DPAPI_V1.' }
    $b64 = $line.Trim().Substring($marker.Length).Trim()
    return ,([Convert]::FromBase64String($b64))
  } finally { $proc.Dispose() }
}
try {
  $text = [System.Text.Encoding]::UTF8.GetBytes('LinkSQL-2.8.2-Teste-sem-senhas')
  [byte[]]$sealed = Invoke-DpapiBridge 'protect' $text
  [byte[]]$restored = Invoke-DpapiBridge 'unprotect' $sealed
  if ([Convert]::ToBase64String($text) -ne [Convert]::ToBase64String($restored)) {
    throw 'O valor recuperado e diferente do original.'
  }
  Write-Host 'LinkSQL 2.8.2: teste DPAPI (processo externo) APROVADO.' -ForegroundColor Green
  Write-Host 'Nenhuma senha real foi lida ou gravada.'
} catch {
  Write-Host ('LinkSQL 2.8.2: teste DPAPI FALHOU: ' + $_.Exception.Message) -ForegroundColor Red
  exit 1
}
