@echo off
setlocal
cd /d "%~dp0.."
where powershell.exe >nul 2>&1
if errorlevel 1 (
  echo ERRO: PowerShell do Windows nao encontrado.
  pause
  exit /b 1
)
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0gerar-windows.ps1" %*
set "CODIGO=%ERRORLEVEL%"
echo.
if not "%CODIGO%"=="0" echo ERRO: Consulte as mensagens acima. Codigo: %CODIGO%
if "%CODIGO%"=="0" echo Geracao finalizada. Confira target\distribuicao\.
pause
exit /b %CODIGO%
