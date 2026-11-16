#!/usr/bin/env bash
# Compile les sources et cree Despylauncher.jar (necessite un JDK 21+)
set -e
cd "$(dirname "$0")"
rm -rf build && mkdir -p build/classes
javac --release 21 -encoding UTF-8 -d build/classes src/fr/redkingdom/launcher/*.java
[ -f version.txt ] || echo 0 > version.txt
echo $(( $(cat version.txt) + 1 )) > version.txt
cp version.txt build/classes/version.txt
printf 'Main-Class: fr.redkingdom.launcher.Main\n' > build/manifest.txt
jar cfm Despylauncher.jar build/manifest.txt -C build/classes .
echo "OK -> Despylauncher.jar"
