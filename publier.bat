```bat
@echo off
title DespyLauncher - Mise a jour GitHub
cd /d "%~dp0"

where git >nul 2>nul
if errorlevel 1 (
    echo Git n'est pas installe.
    pause
    exit /b 1
)

if not exist .git (
    git init -b main || goto :err
    git remote add origin https://github.com/SquareChunk/Despylauncher.git
)

git config user.name SquareChunk
git config user.email SquareChunk@users.noreply.github.com

echo.
echo Synchronisation avec GitHub...
git fetch origin || goto :err

echo.
echo Ajout de TOUS les fichiers modifies...
git add -A

echo.
echo Enregistrement des modifications...
git commit -m "Mise a jour complete"

echo.
echo Envoi vers GitHub...
git push -u origin main || goto :err

echo.
echo ======================================
echo   MISE A JOUR ENVOYEE AVEC SUCCES !
echo ======================================
echo.
echo Tous les fichiers suivis ont ete envoyes.
pause
exit /b 0

:err
echo.
echo ERREUR : impossible d'envoyer les fichiers.
echo Verifie ta connexion et ton acces GitHub.
pause
exit /b 1
```