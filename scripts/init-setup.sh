#!/bin/bash
set -e

# Pfade zu den Konfigurationsdateien (anpassen, falls nötig)
APP_PROPERTIES="/src/main/resources/application.properties"
GRADLE_PROPERTIES="/gradle.properties"

# Stelle sicher, dass die Verzeichnisse existieren
mkdir -p "$(dirname "$APP_PROPERTIES")"

# Falls die Dateien noch nicht existieren, lege sie an
# [ ! -f "$APP_PROPERTIES" ] && touch "$APP_PROPERTIES"
# [ ! -f "$GRADLE_PROPERTIES" ] && touch "$GRADLE_PROPERTIES"

# Füge die notwendigen Zeilen hinzu
echo "Füge Konfigurationen in $APP_PROPERTIES ein..."
echo "spring.devtools.livereload.port=35729" >> "$APP_PROPERTIES"
echo "spring.devtools.restart.poll-interval=2s" >> "$APP_PROPERTIES"
echo "spring.devtools.restart.quiet-period=1s" >> "$APP_PROPERTIES"

echo "Füge Konfiguration in $GRADLE_PROPERTIES ein..."
echo "org.gradle.dependency.verification=off" >> "$GRADLE_PROPERTIES"

echo "Init-Setup abgeschlossen."
