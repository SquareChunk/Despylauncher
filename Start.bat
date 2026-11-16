@echo off
REM Lance Despylauncher sans rien installer : telecharge un Java 21 portable la premiere fois.
cd /d "%~dp0"
if not exist "java21\bin\javaw.exe" (
  echo Premier lancement : telechargement de Java 21 ^(une seule fois, environ 50 Mo^)...
  powershell -NoProfile -ExecutionPolicy Bypass -Command "$ProgressPreference='SilentlyContinue'; [Net.ServicePointManager]::SecurityProtocol=[Net.SecurityProtocolType]::Tls12; Invoke-WebRequest 'https://api.adoptium.net/v3/binary/latest/21/ga/windows/x64/jre/hotspot/normal/eclipse' -OutFile 'java21.zip'; Expand-Archive 'java21.zip' -DestinationPath 'java21tmp' -Force"
  if errorlevel 1 goto :err
  for /d %%D in (java21tmp\*) do move "%%D" java21 >nul
  rmdir /s /q java21tmp
  del java21.zip
)
if not exist "java21\bin\javaw.exe" goto :err
if not exist "Despylauncher.jar" (
  echo Despylauncher.jar introuvable : mets-le dans le meme dossier que Start.bat.
  pause
  exit /b 1
)
start "" "java21\bin\javaw.exe" -jar Despylauncher.jar
exit /b 0
:err
echo.
echo ERREUR : impossible de telecharger Java. Verifie ta connexion internet.
pause
exit /b 1
