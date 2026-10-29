@echo off
rem Start the server using WindSpigot on Java 25.
rem The plugin jar is downloaded from the latest GitHub release instead of being built locally.
setlocal
cd /d "%~dp0"

set "ROOT=%CD%"
set "RUN=%ROOT%\run"
if not exist "%RUN%\plugins\Hypixel2020\maps" mkdir "%RUN%\plugins\Hypixel2020\maps"

set "PLUGIN_URL=https://github.com/Coccocoahelper/hypixel2020/releases/latest/download/Hypixel2020.jar"
set "WINDSPIGOT_URL=https://ci.codemc.io/job/WindSpigot/lastSuccessfulBuild/artifact/target/WindSpigot.jar"

set "JAVA25="
for /d %%D in ("%ROOT%\.tools\jdk25*") do set "JAVA25=%%~fD\bin\java.exe"
if not defined JAVA25 if defined JAVA25_HOME set "JAVA25=%JAVA25_HOME%\bin\java.exe"
if not defined JAVA25 for /d %%D in ("%ProgramFiles%\Eclipse Adoptium\jdk-25*" "%ProgramFiles%\Microsoft\jdk-25*" "%ProgramFiles%\Java\jdk-25*" "%ProgramFiles%\Java\jre-25*") do set "JAVA25=%%~fD\bin\java.exe"
if not defined JAVA25 (
  echo Java 25 not found. Install Temurin 25 from https://adoptium.net/temurin/releases/ and set JAVA25_HOME to it, then rerun.
  pause
  exit /b 1
)

call :download "%PLUGIN_URL%" "%RUN%\plugins\Hypixel2020.jar"
if errorlevel 1 exit /b %errorlevel%

call :download "%WINDSPIGOT_URL%" "%RUN%\WindSpigot.jar"
if errorlevel 1 exit /b %errorlevel%

rem install the bundled maps (maps\) and any you imported yourself (import\ready\) that are not in run\ yet
for /d %%M in ("%ROOT%\maps\*" "%ROOT%\import\ready\*") do (
  if exist "%%~fM\." if not exist "%RUN%\plugins\Hypixel2020\maps\%%~nxM" xcopy /e /i /q "%%~fM" "%RUN%\plugins\Hypixel2020\maps\%%~nxM" >nul
)

if not exist "%RUN%\server.properties" (
  powershell -NoProfile -Command "$p=-join((48..57)+(97..122)|Get-Random -Count 20|%%{[char]$_}); (Get-Content '%ROOT%\server-defaults\server.properties') -replace 'CHANGE_ME',$p | Set-Content -Path '%RUN%\server.properties' -Encoding UTF8"
)
if not exist "%RUN%\spigot.yml" copy /y "%ROOT%\server-defaults\spigot.yml" "%RUN%\spigot.yml" >nul

findstr /c:"eula=true" "%RUN%\eula.txt" >nul 2>&1
if errorlevel 1 (
  echo Minecraft servers need you to accept the Minecraft EULA: https://aka.ms/MinecraftEULA
  set /p "EULA=Do you accept it? (y/n) "
  if /i not "%EULA%"=="y" (
    echo The server can't start without accepting the EULA.
    pause
    exit /b 1
  )
  > "%RUN%\eula.txt" echo eula=true
)

cd /d "%RUN%"
echo Starting server. Join "localhost" from Minecraft 1.8.9. Type "stop" here to shut it down.
"%JAVA25%" -Xms2G -Xmx4G -XX:+UseG1GC -jar WindSpigot.jar nogui
pause
exit /b %ERRORLEVEL%

:download
set "URL=%~1"
set "OUT=%~2"
if exist "%OUT%" exit /b 0
powershell -NoProfile -ExecutionPolicy Bypass -Command "$ProgressPreference='SilentlyContinue'; Invoke-WebRequest -Uri '%URL%' -OutFile '%OUT%'"
exit /b %ERRORLEVEL%