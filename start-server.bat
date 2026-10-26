@echo off
rem Double-click to start the Hypixel 2020 test server (no Git Bash/WSL needed).
rem Builds the plugin, installs it and the maps into run\, then runs Spigot 1.8.8 on Java 8.
setlocal
cd /d "%~dp0"

echo Building plugin...
call "%~dp0gradlew.bat" build -q
if errorlevel 1 (
  echo Build failed.
  pause
  exit /b 1
)

if not exist "run\plugins\Hypixel2020\maps" mkdir "run\plugins\Hypixel2020\maps"
copy /y "build\libs\Hypixel2020.jar" "run\plugins\Hypixel2020.jar" >nul

if not exist "run\spigot-1.8.8.jar" (
  for /f "delims=" %%J in ('dir /s /b "%USERPROFILE%\.gradle\caches\modules-2\files-2.1\org.spigotmc\spigot\spigot-1.8.8-R0.1-SNAPSHOT.jar" 2^>nul') do copy /y "%%J" "run\spigot-1.8.8.jar" >nul
)

rem install the bundled maps (maps\) and any you imported yourself (import\ready\) that aren't in run\ yet
for /d %%M in ("maps\*" "import\ready\*") do (
  if not exist "run\plugins\Hypixel2020\maps\%%~nxM" xcopy /e /i /q "%%M" "run\plugins\Hypixel2020\maps\%%~nxM" >nul
)

rem first start: default server settings (offline mode, RCON with a random password, long player tracking range)
if not exist "run\server.properties" (
  powershell -NoProfile -Command "$p=-join((48..57)+(97..122)|Get-Random -Count 20|%%{[char]$_}); (Get-Content 'server-defaults\server.properties') -replace 'CHANGE_ME',$p | Set-Content -Encoding ascii 'run\server.properties'"
)
if not exist "run\spigot.yml" copy /y "server-defaults\spigot.yml" "run\spigot.yml" >nul

findstr /c:"eula=true" "run\eula.txt" >nul 2>&1
if errorlevel 1 (
  echo Minecraft servers need you to accept the Minecraft EULA: https://aka.ms/MinecraftEULA
  set /p "EULA=Do you accept it? (y/n) "
  call :accept_eula
  if errorlevel 1 exit /b 1
)

rem Java 8 (Spigot 1.8.8 needs it): .tools\jdk8*, then JAVA8_HOME, then an installed Temurin/Adoptium 8
set "JAVA8="
for /d %%D in (".tools\jdk8*") do set "JAVA8=%%~fD\bin\java.exe"
if not defined JAVA8 if defined JAVA8_HOME set "JAVA8=%JAVA8_HOME%\bin\java.exe"
if not defined JAVA8 for /d %%D in ("%ProgramFiles%\Eclipse Adoptium\jre-8*" "%ProgramFiles%\Eclipse Adoptium\jdk-8*" "%ProgramFiles%\Java\jre1.8*" "%ProgramFiles%\Java\jdk1.8*") do set "JAVA8=%%~fD\bin\java.exe"
if not defined JAVA8 (
  echo Java 8 not found. Install Temurin 8 from https://adoptium.net/temurin/releases/?version=8
  echo or set JAVA8_HOME to a Java 8 folder, then run this again.
  pause
  exit /b 1
)

cd run
echo Starting server. Join "localhost" from Minecraft 1.8.9. Type "stop" here to shut it down.
"%JAVA8%" -Xms2G -Xmx4G -XX:+UseG1GC %H2020_JVM_OPTS% -cp spigot-1.8.8.jar org.bukkit.craftbukkit.Main nogui
pause
exit /b 0

:accept_eula
if /i "%EULA%"=="y" (
  > "run\eula.txt" echo eula=true
  exit /b 0
)
echo The server can't start without accepting the EULA.
pause
exit /b 1
