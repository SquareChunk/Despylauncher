@echo off
setlocal
cd /d "%~dp0"
set "SRC=Main.java"
if exist "src\fr\redkingdom\launcher\Main.java" set "SRC=src\fr\redkingdom\launcher\Main.java"
if not exist "%SRC%" (
  echo Main.java introuvable : mets ce fichier dans le meme dossier que Main.java
  pause
  exit /b 1
)

set "NOM=MClauncher"
if not defined NOM exit /b 1

powershell -NoProfile -Command "$q=[char]34; $t=[IO.File]::ReadAllText($env:SRC); $m=[regex]::Match($t,'DEFAULT_TITLE\s*=\s*'+$q+'[^'+$q+']*'+$q); if(-not $m.Success -or $env:NOM.Contains($q) -or $env:NOM.Contains('\')){exit 1}; $t=$t.Replace($m.Value,'DEFAULT_TITLE = '+$q+$env:NOM+$q); [IO.File]::WriteAllText($env:SRC,$t,(New-Object Text.UTF8Encoding $false))"
if errorlevel 1 (
  echo ERREUR : nom impossible ^(pas de guillemets ni de \^) ou ligne DEFAULT_TITLE introuvable.
  pause
  exit /b 1
)

call build.bat <nul
if errorlevel 1 exit /b 1
call publier.bat
