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
  echo Exemplo: C:\Program Files\Java\jdk-17
  echo.
  pause
  exit /b 1
)

set "PATH=%JDK%\bin;%PATH%"
set "OUT=%~dp0out"
mkdir "%OUT%" 2>nul

echo Compilando COMINDIR03...
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
echo Comandos de execucao:
echo   java -cp "%OUT%\rep03-replica" rep03.PartitionReplica 1 0,1
echo   java -cp "%OUT%\rep03-lider" rep03.PartitionLeader 0 3
echo   java -cp "%OUT%\rep03-lider" rep03.PartitionLeader 1 3
echo   java -cp "%OUT%\rep03-lider" rep03.PartitionLeader 2 3
echo   java -cp "%OUT%\rep03-processador" rep03.AlarmProcessor 3
echo   java -cp "%OUT%\rep03-produtor" rep03.Producer 3
echo.
echo Se quiseres lancar tudo em janelas separadas:
echo   start "replica" cmd /k java -cp "%OUT%\rep03-replica" rep03.PartitionReplica 1 0,1
echo   start "lider0" cmd /k java -cp "%OUT%\rep03-lider" rep03.PartitionLeader 0 3
echo   start "lider1" cmd /k java -cp "%OUT%\rep03-lider" rep03.PartitionLeader 1 3
echo   start "lider2" cmd /k java -cp "%OUT%\rep03-lider" rep03.PartitionLeader 2 3
echo   start "processador" cmd /k java -cp "%OUT%\rep03-processador" rep03.AlarmProcessor 3
echo   start "produtor" cmd /k java -cp "%OUT%\rep03-produtor" rep03.Producer 3

echo.
pause
exit /b 0
