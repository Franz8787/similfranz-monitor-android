#!/bin/bash
# Annulla scripts/ottimizza-tablet.sh: rimette le app tolte con --cancella e riattiva quelle disattivate.
# Uso: scripts/ripristina-tablet.sh   (con più tablet: ANDROID_SERIAL=<seriale> scripts/ripristina-tablet.sh)
R="$(cd "$(dirname "$0")/.." && pwd)"; source "$R/scripts/env.sh"
for p in $(grep -E '^  [a-zA-Z]+\.' "$R/scripts/ottimizza-tablet.sh" | awk '{print $1}' | sort -u); do
  adb shell cmd package install-existing "$p" >/dev/null 2>&1
  adb shell pm enable "$p" 2>&1 | tr -d '\r'
done
adb shell svc nfc enable 2>/dev/null
echo "OK: app ripristinate"
