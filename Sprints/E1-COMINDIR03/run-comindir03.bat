@echo off
setlocal enabledelayedexpansion

cd /d "%~dp0"

echo ============================================================
echo COMINDIR03 - arrancar IPFS + Java
echo ============================================================

rem -------------------------------------------
rem 1) Verificar JDK
rem -------------------------------------------
set "JDK="
if defined JAVA_HOME if exist "%JAVA_HOME%\bin\javac.exe" set "JDK=%JAVA_HOME%"
if not defined JDK (
  for /d %%D in ("C:\Program Files\Java\*" "C:\Program Files\Eclipse Adoptium\*") do (
    if exist "%%~D\bin\javac.exe" (
      set "JDK=%%~D"
      goto :jdk_found
    )
  )
)
:jdk_found
if not defined JDK (
  echo JDK 11+ nao encontrado.
  echo Instale o JDK 11 ou superior e defina JAVA_HOME ou adicione-o ao PATH.
  pause
  exit /b 1
)

set "PATH=%JDK%\bin;%PATH%"

rem -------------------------------------------
rem 2) Verificar IPFS
rem -------------------------------------------
where ipfs >nul 2>nul
if errorlevel 1 (
  echo IPFS nao encontrado no PATH.
  echo Instale o Kubo IPFS e reinicie o terminal.
  pause
  exit /b 1
)

echo Verificando IPFS...
if not exist "%USERPROFILE%\.ipfs\config" (
  echo Inicializando IPFS...
  ipfs init
  if errorlevel 1 (
    echo Falha ao inicializar IPFS.
    pause
    exit /b 1
  )
)

echo Ajustando configuracao API...
ipfs config Addresses.API /ip4/127.0.0.1/tcp/5001
ipfs config --json Pubsub "{\"Enabled\":true}"

echo    (Se a linha acima falhar, o teu IPFS e' demasiado antigo/incompativel)

echo Verificando daemon...
curl -s http://127.0.0.1:5001/api/v0/version >nul 2>&1
if errorlevel 1 (
  echo Iniciando IPFS daemon com PubSub...
  start "IPFS" cmd /k ipfs daemon --enable-pubsub-experiment
  timeout /t 8 /nobreak >nul
)

echo Verificando API do IPFS...
curl -s http://127.0.0.1:5001/api/v0/version >nul 2>&1
if errorlevel 1 (
  echo O IPFS nao respondeu em http://127.0.0.1:5001/api/v0/version
  echo Execute manualmente:
  echo   ipfs daemon --enable-pubsub-experiment
  pause
  exit /b 1
)

echo IPFS OK.
echo.

rem -------------------------------------------
rem 3) Compilar modulos Java
rem -------------------------------------------
set "OUT=%~dp0out"
mkdir "%OUT%" 2>nul

echo Compilando modulos...
for %%P in (rep03-lider rep03-processador rep03-produtor rep03-replica) do (
  set "PROJ_OUT=%OUT%\%%P"
  mkdir "!PROJ_OUT!" 2>nul
  echo   %%P
  for /r "%~dp0%%P\src" %%F in (*.java) do (
    javac -d "!PROJ_OUT!" "%%~F"
    if errorlevel 1 (
      echo Falha na compilacao em %%~F
      pause
      exit /b 1
    )
  )
)

echo Compilacao concluida.
echo.

rem -------------------------------------------
rem 4) Iniciar aplicacoes Java
rem -------------------------------------------
echo Arrancando aplicacoes...
start "replica1" cmd /k java -cp "%OUT%\rep03-replica" rep03.PartitionReplica 1 0,1
ping 127.0.0.1 -n 2 >nul
start "replica2" cmd /k java -cp "%OUT%\rep03-replica" rep03.PartitionReplica 2 0,1
ping 127.0.0.1 -n 2 >nul
start "replica3" cmd /k java -cp "%OUT%\rep03-replica" rep03.PartitionReplica 3 0,1
ping 127.0.0.1 -n 2 >nul

start "lider0" cmd /k java -cp "%OUT%\rep03-lider" rep03.PartitionLeader 0 3
ping 127.0.0.1 -n 2 >nul
start "lider1" cmd /k java -cp "%OUT%\rep03-lider" rep03.PartitionLeader 1 3
ping 127.0.0.1 -n 2 >nul
start "lider2" cmd /k java -cp "%OUT%\rep03-lider" rep03.PartitionLeader 2 3
ping 127.0.0.1 -n 2 >nul

start "processador" cmd /k java -cp "%OUT%\rep03-processador" rep03.AlarmProcessor 3
ping 127.0.0.1 -n 2 >nul

start "produtor" cmd /k java -cp "%OUT%\rep03-produtor" rep03.Producer 3

echo.
echo Processos iniciados.
echo.
echo Se quiseres testar manualmente:
echo   java -cp "%OUT%\rep03-replica" rep03.PartitionReplica 1 0,1
echo   java -cp "%OUT%\rep03-lider" rep03.PartitionLeader 0 3
echo   java -cp "%OUT%\rep03-lider" rep03.PartitionLeader 1 3
echo   java -cp "%OUT%\rep03-lider" rep03.PartitionLeader 2 3
echo   java -cp "%OUT%\rep03-processador" rep03.AlarmProcessor 3
echo   java -cp "%OUT%\rep03-produtor" rep03.Producer 3
echo.

pause
exit /b 0