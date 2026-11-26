#!/bin/bash
# Installa un'app sul tablet collegato con il cavo USB (Debug USB attivo), dà i permessi e la apre.
# Uso: scripts/installa.sh monitor|controller [file.apk]
#   senza file.apk usa apk/similfranz-<app>.apk (scaricato dalla Release o creato da scripts/compila.sh)
# Con più tablet collegati: ANDROID_SERIAL=<seriale> scripts/installa.sh ...   (seriali: adb devices)
set -e
R="$(cd "$(dirname "$0")/.." && pwd)"; source "$R/scripts/env.sh"
APP="$1"; case "$APP" in monitor|controller) ;; *) echo "Uso: scripts/installa.sh monitor|controller [file.apk]" >&2; exit 1;; esac
APK="${2:-$R/apk/similfranz-$APP.apk}"
[ -f "$APK" ] || { echo "Non trovo $APK: scaricalo dalla Release oppure lancia scripts/compila.sh" >&2; exit 1; }
n=$(adb devices | grep -c -E '\sdevice$' || true)
[ "$n" = 0 ] && { echo "Nessun tablet: collega il cavo, attiva Debug USB e tocca «Consenti» sul tablet" >&2; exit 1; }
[ "$n" -gt 1 ] && [ -z "$ANDROID_SERIAL" ] && { echo "Più tablet collegati: scegli con ANDROID_SERIAL=<seriale> (vedi adb devices)" >&2; exit 1; }
PKG=it.simulafranz.$APP
adb install -r "$APK"
adb shell pm grant $PKG android.permission.ACCESS_FINE_LOCATION || true   # Android 9 lo chiede per Wi-Fi Direct
if [ "$APP" = monitor ]; then
  # «Sala» (audio e video in diretta per il controller): si apre solo quando lo chiedi tu dalla regia
  adb shell pm grant $PKG android.permission.CAMERA || true
  adb shell pm grant $PKG android.permission.RECORD_AUDIO || true
else
  # Storia Sim: i PDF vanno in Documenti/Storia Sim
  adb shell pm grant $PKG android.permission.WRITE_EXTERNAL_STORAGE || true
  adb shell pm grant $PKG android.permission.READ_EXTERNAL_STORAGE || true
fi
adb shell am start -n $PKG/.MainActivity >/dev/null
echo "OK: similFranz $APP installato e aperto"
