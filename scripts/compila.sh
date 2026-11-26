#!/bin/bash
# Compila le due app (APK di prova, firmati con la tua chiave di debug) e le copia in apk/.
# Uso: scripts/compila.sh            Serve Java 17 e l'SDK Android (Android Studio). Vedi docs/02-installazione.md
set -e
R="$(cd "$(dirname "$0")/.." && pwd)"; source "$R/scripts/env.sh"
[ -n "$ANDROID_HOME" ] || { echo "SDK Android non trovato: imposta ANDROID_HOME" >&2; exit 1; }
mkdir -p "$R/apk"
for app in monitor controller; do
  echo "== compilo $app"
  (cd "$R/$app" && echo "sdk.dir=$ANDROID_HOME" > local.properties && ./gradlew -q :app:assembleDebug)
  cp "$R/$app/app/build/outputs/apk/debug/app-debug.apk" "$R/apk/similfranz-$app.apk"
done
echo "OK: apk/similfranz-monitor.apk e apk/similfranz-controller.apk"
