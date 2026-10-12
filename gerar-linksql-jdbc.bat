@echo off
setlocal EnableExtensions

rem LinkSQL JDBC 0.6 - verifica a estrutura antes de compilar.
set "RAIZ=%~dp0"
set "SCRIPT=%RAIZ%ferramentas\gerar-linksql-jdbc.ps1"
set "MODULO=%RAIZ%integracao\linksql-jdbc\pom.xml"
set "FONTE=%RAIZ%src\main\java\br\com\linksql"

pushd "%RAIZ%" 2>nul
if errorlevel 1 (
  echo ERRO: nao foi possivel acessar a pasta do projeto:
  echo "%RAIZ%"
  pause
  exit /b 1
)

echo =========================================
echo    LinkSQL JDBC - Gerador automatico 0.6
echo =========================================
echo Projeto: "%CD%"
echo.

if not exist "%SCRIPT%" (
  echo ERRO: script auxiliar nao encontrado.
  echo Esperado: "%SCRIPT%"
  echo.
  echo Extraia o ZIP COMPLETO na pasta que contem o pom.xml do LinkSQL.
  echo Nao copie apenas este arquivo .bat e nao deixe os scripts em uma subpasta extra.
  goto :erro
)
if not exist "%MODULO%" (
  echo ERRO: modulo JDBC nao encontrado.
  echo Esperado: "%MODULO%"
  echo.
  echo Confira a pasta integracao\linksql-jdbc com os fontes atuais do LinkSQL 3.3.1
  echo na RAIZ do projeto LinkSQL, mantendo todas as subpastas.
  goto :erro
)
if not exist "%FONTE%" (
  echo ERRO: esta pasta nao parece ser a raiz do projeto LinkSQL.
  echo Pasta de fontes esperada: "%FONTE%"
  goto :erro
)

where powershell.exe >nul 2>nul
if errorlevel 1 (
  echo ERRO: powershell.exe nao encontrado neste Windows.
  goto :erro
)

powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%SCRIPT%"
set "RET=%ERRORLEVEL%"
if not "%RET%"=="0" (
  echo.
  echo ERRO: nao foi possivel gerar o LinkSQL JDBC. Codigo: %RET%
  echo Envie as mensagens exibidas acima para diagnostico.
  popd
  pause
  exit /b %RET%
)

echo.
echo SUCESSO: confira target\linksql-jdbc\linksql-jdbc-bundle-0.6.jar
popd
pause
exit /b 0

:erro
echo.
echo Estrutura minima esperada:
echo   LinkSQL\pom.xml
echo   LinkSQL\GERAR-LINKSQL-JDBC.bat
echo   LinkSQL\ferramentas\gerar-linksql-jdbc.ps1
echo   LinkSQL\integracao\linksql-jdbc\pom.xml
echo   LinkSQL\src\main\java\br\com\linksql\
echo.
popd
pause
exit /b 1
