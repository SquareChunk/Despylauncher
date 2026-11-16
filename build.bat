@echo off
setlocal
REM Compile les sources et cree Despylauncher.jar (necessite un JDK 21+)
cd /d "%~dp0"

REM --- 1) JDK present ? ---
where javac >nul 2>nul
if errorlevel 1 (
  echo ERREUR : "javac" introuvable. Installe un JDK 21 ou plus ^(pas juste Java/JRE^) :
  echo   https://adoptium.net  ^(Temurin 21^)
  echo Pense a cocher "Add to PATH" a l'installation, puis relance ce fichier.
  goto :err
)
where jar >nul 2>nul
if errorlevel 1 (
  echo ERREUR : la commande "jar" est introuvable : il te faut un JDK complet.
  goto :err
)
echo Version du compilateur :
javac -version

REM --- 2) Ou sont les sources ? (dans src\... ou directement ici) ---
set "SRC="
if exist "src\fr\redkingdom\launcher\Main.java" set "SRC=src\fr\redkingdom\launcher"
if not defined SRC if exist "Main.java" set "SRC=."
if not defined SRC (
  echo ERREUR : Main.java introuvable.
  echo Mets Main.java, Core.java et Json.java dans ce dossier, ou dans src\fr\redkingdom\launcher\
  goto :err
)
echo Sources trouvees dans : %SRC%

REM --- 3) Nettoyage ---
if exist build rmdir /s /q build
mkdir build\classes
if exist Despylauncher.jar del /f /q Despylauncher.jar
if exist Despylauncher.jar (
  echo ERREUR : impossible de remplacer Despylauncher.jar : ferme le launcher puis relance.
  goto :err
)

REM --- 4) Compilation ---
javac --release 21 -encoding UTF-8 -d build\classes "%SRC%\Main.java" "%SRC%\Core.java" "%SRC%\Json.java"
if errorlevel 1 (
  echo ERREUR de compilation. Verifie que ton JDK est en version 21 ou plus ^(javac -version^).
  goto :err
)

REM --- 5) Numero de version : +1 a chaque build (fichier version.txt, embarque dans le jar) ---
if not exist version.txt echo 0>version.txt
set /p VER=<version.txt
set /a VER=VER+1
>version.txt echo %VER%
copy /y version.txt build\classes\version.txt >nul
echo Version du launcher : %VER%

REM --- 6) Creation du jar ---
echo Main-Class: fr.redkingdom.launcher.Main> build\manifest.txt
jar cfm Despylauncher.jar build\manifest.txt -C build\classes .
if errorlevel 1 goto :err

echo.
echo OK - Despylauncher.jar cree ^(version %VER%^)
pause
exit /b 0

:err
echo.
echo La compilation a echoue ^(voir le message ci-dessus^).
pause
exit /b 1
