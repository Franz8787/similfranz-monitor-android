#!/bin/bash
# Attiva (o disattiva) sul tablet MONITOR collegato con il cavo le due funzioni pensate per il tablet chiuso nel guscio:
#   - tasti fisici: Home = carica (tre pressioni: Home), «app recenti» = scarica, «indietro» = accensione (tre pressioni: app recenti) (servizio
#     «similFranz: tasti del monitor»: guarda solo quei tre tasti e solo con il monitor a schermo);
#   - icona «Blocca schermo» e voce «Spegni lo schermo» del menu (amministratore del dispositivo, solo blocco schermo).
# Si possono attivare anche a mano dalle Impostazioni del tablet: vedi docs/monitor.md.
# Uso: scripts/tasti-guscio.sh attiva|disattiva        (con più tablet: ANDROID_SERIAL=<seriale> scripts/tasti-guscio.sh ...)
set -e
R="$(cd "$(dirname "$0")/.." && pwd)"; source "$R/scripts/env.sh"
PKG=it.simulafranz.monitor; SVC=$PKG/$PKG.SFTastiService; ADM=$PKG/.SFBloccaAdmin
case "$1" in attiva|disattiva) ;; *) echo "Uso: scripts/tasti-guscio.sh attiva|disattiva" >&2; exit 1;; esac
adb shell pm list packages | grep -q "package:$PKG\$" || { echo "Sul tablet collegato non c'è similFranz Monitor" >&2; exit 1; }
ora="$(adb shell settings get secure enabled_accessibility_services | tr -d '\r')"; [ "$ora" = null ] && ora=""
senza="$(echo "$ora" | tr ':' '\n' | grep -v -F "$SVC" | grep -v '^$' | paste -sd: - || true)"
if [ "$1" = attiva ]; then
  adb shell settings put secure enabled_accessibility_services "${senza:+$senza:}$SVC"
  adb shell settings put secure accessibility_enabled 1
  adb shell dpm set-active-admin "$ADM" >/dev/null 2>&1 || echo "«Blocca schermo»: attivalo dal tablet toccando l'icona (Android chiede conferma)"
  echo "OK: tasti del guscio e «Blocca schermo» attivi"
else
  if [ -n "$senza" ]; then adb shell settings put secure enabled_accessibility_services "$senza"; else adb shell settings delete secure enabled_accessibility_services >/dev/null; fi
  adb shell dpm remove-active-admin "$ADM" >/dev/null 2>&1 || echo "«Blocca schermo»: toglilo da Impostazioni › Sicurezza › Amministratori dispositivo"
  echo "OK: tasti del guscio e «Blocca schermo» disattivati"
fi
