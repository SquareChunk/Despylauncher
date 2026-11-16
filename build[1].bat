@echo off
REM Compile les sources et cree Despylauncher.jar (necessite un JDK 21+)
cd /d "%~dp0"
if exist build rmdir /s /q build
mkdir build\classes
javac --release 21 -encoding UTF-8 -d build\classes src\fr\redkingdom\launcher\*.java || goto :err
echo Main-Class: fr.redkingdom.launcher.Main> build\manifest.txt
jar cfm Despylauncher.jar build\manifest.txt -C build\classes . || goto :err
echo OK - Despylauncher.jar cree
pause
exit /b 0
:err
echo ERREUR de compilation (JDK 21+ installe ?)
pause
exit /b 1
