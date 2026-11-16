
@echo off
chcp 65001 >nul
title DESPY LAUNCHER - UPDATE COMPLET
cd /d "%~dp0"

echo ======================================
echo       DESPY LAUNCHER UPDATE
echo ======================================
echo.

where git >nul 2>nul
if errorlevel 1 (
    echo Git est introuvable.
    pause
    exit /b 1
)

if not exist .git (
    git init -b main
    git remote add origin https://github.com/SquareChunk/Despylauncher.git
)

echo [1/4] Compilation du launcher...

if exist build.bat (
    call build.bat
    if errorlevel 1 goto erreur
) else (
    echo Aucun build.bat trouve.
    echo Compilation automatique impossible.
)

echo.
echo [2/4] Synchronisation GitHub...
git fetch origin
if errorlevel 1 goto erreur

echo.
echo [3/4] Ajout de tous les fichiers...
git add -A
git commit -m "Mise a jour complete"

echo.
echo [4/4] Publication...
git push -u origin main
if errorlevel 1 goto erreur

echo.
echo ======================================
echo       PUBLICATION REUSSIE !
echo ======================================
echo.
pause
exit /b 0

:erreur
echo.
echo ERREUR PENDANT LA MISE A JOUR !
echo Verifie les messages ci-dessus.
pause
exit /b 1
