@echo off
setlocal enabledelayedexpansion
cd /d "%~dp0"

echo Verificando JDK...
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
  echo.
  pause
  exit /b 1
)

set "PATH=%JDK%\bin;%PATH%"
set "OUT=%~dp0out"
mkdir "%OUT%" 2>nul

echo Compilando todos os modulos...
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

echo.
echo Compilacao concluida.
echo.
echo Arrancando em janelas separadas...
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
exit /b 0
