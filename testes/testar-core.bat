@echo off
setlocal
cd /d "%~dp0.."
where powershell.exe >nul 2>&1
if errorlevel 1 (
  echo ERRO: PowerShell nao encontrado.
  exit /b 1
)
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0testar-core.ps1" %*
set "CODIGO=%ERRORLEVEL%"
if not "%CODIGO%"=="0" echo ERRO: Verifique as mensagens acima.
exit /b %CODIGO%
