#!/bin/bash
# FACOLTATIVO. Prepara un Samsung Galaxy Tab Active2 a fare SOLO da simulatore: disattiva le app e i servizi che non
# servono (meno memoria occupata, nessuna notifica o finestra che compare durante lo scenario, batteria che dura di più).
# Pensato per un tablet DEDICATO alla simulazione, senza account e senza dati personali. Su altri modelli i nomi dei
# pacchetti possono non esistere: lo script li salta.
#   scripts/ottimizza-tablet.sh             disattiva (pm disable-user): le app restano sul tablet, spente
#   scripts/ottimizza-tablet.sh --cancella  in più TOGLIE le app dell'elenco CANCELLA per l'utente (pm uninstall --user 0):
#                                           il pacchetto di fabbrica resta, quindi si possono rimettere
# Si annulla del tutto con scripts/ripristina-tablet.sh. Un ripristino di fabbrica rimette comunque tutto com'era.
# Non tocca: Impostazioni, telefono e rubrica di sistema, Archivio (per gli APK), Chrome, fotocamera, galleria,
# sintesi vocale (serve alla voce dei DAE), servizi Google di base.
R="$(cd "$(dirname "$0")/.." && pwd)"; source "$R/scripts/env.sh"
DEL=0; [ "$1" = "--cancella" ] && DEL=1
n=$(adb devices | grep -c -E '\sdevice$' || true)
[ "$n" = 1 ] || [ -n "$ANDROID_SERIAL" ] || { echo "Collega UN tablet (oppure ANDROID_SERIAL=<seriale>)" >&2; exit 1; }
echo "Modello: $(adb shell getprop ro.product.model | tr -d '\r')"
echo "Disattivo le app che non servono alla simulazione$([ $DEL = 1 ] && echo ' e tolgo quelle dell elenco CANCELLA')."
echo "Si annulla con scripts/ripristina-tablet.sh. Continuo? [s/N]"; read -r ok; [ "$ok" = s ] || [ "$ok" = S ] || exit 0
A=(adb)
# app che con --cancella vengono tolte del tutto (non servono né al monitor né al controller)
CANCELLA=(
  com.microsoft.office.excel
  com.microsoft.office.onenote
  com.microsoft.office.powerpoint
  com.microsoft.office.word
  com.microsoft.skydrive                # OneDrive
  com.skype.raider                      # Skype
  flipboard.boxer.app                   # Briefing (Flipboard)
  com.hiya.star                         # identificazione chiamate
  com.sec.penup                         # PENUP
  com.google.android.apps.docs          # Drive
  com.google.android.apps.maps          # Maps
  com.google.android.apps.photos        # Google Foto
  com.google.android.apps.tachyon       # Duo
  com.google.android.gm                 # Gmail
  com.google.android.music              # Play Musica
  com.google.android.videos             # Play Film
  com.google.android.youtube            # YouTube
  com.google.android.googlequicksearchbox  # app Google
  com.samsung.android.app.notes         # Samsung Notes
  com.samsung.android.app.reminder      # Promemoria
  com.samsung.android.spdfnote          # note PDF
  com.samsung.android.email.provider    # Email
  com.samsung.android.galaxycontinuity  # Samsung Flow
  com.samsung.android.voc               # Samsung Members
  com.samsung.android.app.spage         # Bixby Home
  com.samsung.android.bixby.service
  com.samsung.android.homemode          # Bacheca giornaliera
  com.samsung.android.weather           # Meteo
  com.sec.android.app.kidshome          # Modalità bambini
  com.samsung.android.kidsinstaller
  com.samsung.android.smartswitchassistant
  com.sec.android.easyMover.Agent       # Smart Switch
  com.sec.android.app.samsungapps       # Galaxy Store
  com.sec.android.widgetapp.samsungapps
  com.sec.android.app.sbrowser          # Samsung Internet (resta Chrome)
  com.sec.android.mimage.gear360editor
  com.samsung.android.app.camera.sticker.facear.preload
  com.samsung.android.app.camera.sticker.stamp.preload
  com.samsung.android.stickercenter
  com.samsung.android.app.watchmanagerstub
  com.samsung.android.app.social
  com.samsung.storyservice
  com.sec.android.app.dictionary
  com.sec.android.widgetapp.webmanual
  com.monotype.android.font.chococooky
  com.monotype.android.font.cooljazz
  com.monotype.android.font.foundation
  com.monotype.android.font.rosemary
  com.sec.android.app.popupcalculator   # Calcolatrice
)
APPS=(
  com.google.android.apps.docs          # Drive
  com.google.android.apps.maps          # Maps
  com.google.android.apps.photos        # Google Foto
  com.google.android.apps.tachyon       # Duo
  com.google.android.gm                 # Gmail
  com.google.android.googlequicksearchbox  # app Google (~100 MB in memoria)
  com.google.android.music              # Play Musica
  com.google.android.videos             # Play Film
  com.google.android.youtube            # YouTube
  com.microsoft.office.excel
  com.microsoft.office.onenote
  com.microsoft.office.powerpoint
  com.microsoft.office.word
  com.microsoft.skydrive                # OneDrive
  com.skype.raider                      # Skype
  com.sec.penup                         # PENUP
  com.samsung.android.app.notes         # Samsung Notes
  com.samsung.android.calendar          # Calendario
  com.samsung.android.email.provider    # Email
  com.samsung.android.galaxycontinuity  # Samsung Flow
  com.samsung.android.voc               # Samsung Members
  com.samsung.android.app.spage         # Bixby Home
  com.sec.android.app.samsungapps       # Galaxy Store
  com.sec.android.app.sbrowser          # Samsung Internet (resta Chrome)
  com.sec.android.app.popupcalculator   # Calcolatrice
  com.sec.android.app.clockpackage      # Orologio
  com.samsung.android.game.gamehome     # Game Launcher
  com.samsung.android.game.gametools
  com.samsung.android.homemode          # Bacheca giornaliera (Daily Board)
  flipboard.boxer.app                   # Briefing (Flipboard)
  com.samsung.android.weather           # Meteo Samsung
  com.sec.android.daemonapp             # servizio meteo/notizie in background
  com.samsung.android.scloud            # Samsung Cloud
  com.samsung.android.samsungpass       # Samsung Pass
  com.samsung.android.samsungpassautofill
  com.sec.android.app.kidshome          # Modalità bambini
  com.samsung.android.kidsinstaller
  com.samsung.android.wellbeing         # Benessere digitale
  com.samsung.android.smartswitchassistant
  com.sec.android.easyMover.Agent       # Smart Switch
  # servizi in background che non servono né al monitor né al controller (aggiunti 21/11/2026)
  com.android.vending                   # Play Store (senza account e senza rete non serve; gli APK si installano lo stesso)
  com.google.android.apps.turbo         # Device Health Services
  com.samsung.android.game.gos          # Game Optimizing Service
  com.samsung.android.smartmirroring    # Smart View
  com.samsung.android.dqagent           # agente qualità del dispositivo
  com.samsung.android.bio.face.service  # riconoscimento del volto
  com.samsung.android.beaconmanager
  com.samsung.knox.securefolder         # Cartella protetta
  com.sec.spp.push                      # notifiche push Samsung
  com.samsung.android.mobileservice     # servizi social Samsung
  com.samsung.android.app.galaxyfinder  # Finder
  com.samsung.android.rubin.app         # servizio di personalizzazione
  com.samsung.android.fmm               # Trova il mio dispositivo
  com.samsung.android.ipsgeofence
  com.samsung.android.svcagent
  com.samsung.android.easysetup
  com.samsung.android.smartcallprovider
  com.samsung.android.knox.analytics.uploader
  com.sec.android.diagmonagent          # diagnostica inviata a Samsung
  com.sec.android.app.billing
  com.samsung.android.service.airviewdictionary
  com.samsung.android.app.simplesharing
  com.samsung.android.allshare.service.mediashare
  com.sec.android.soagent               # avvisi di aggiornamento software (tablet senza rete)
  com.wssyncmldm                        # aggiornamento software via rete (FOTA)
  com.samsung.android.MtpApplication    # finestra "USB / trasferimento file" che copre l'app quando si attacca il cavo
)
# app che restano ma senza notifiche (aggiornamenti e promozioni del Play Store)
QUIET=()
if [ $DEL = 1 ]; then
  for p in "${CANCELLA[@]}"; do
    if "${A[@]}" shell pm list packages "$p" | grep -q "package:$p\$"; then
      r=$("${A[@]}" shell pm uninstall -k --user 0 "$p" 2>&1 | tr -d '\r')
      echo "$p: tolta ($r)"
    fi
  done
fi
for p in "${APPS[@]}"; do
  if "${A[@]}" shell pm list packages "$p" | grep -q "package:$p\$"; then
    r=$("${A[@]}" shell pm disable-user --user 0 "$p" 2>&1 | tr -d '\r')
    echo "$p: $r"
  fi
done
# NFC spenta (non serve a niente qui; si riaccende da Impostazioni)
"${A[@]}" shell svc nfc disable 2>/dev/null
for p in "${QUIET[@]}"; do "${A[@]}" shell appops set "$p" POST_NOTIFICATION ignore && echo "$p: notifiche spente"; done
# memoria: libera le cache delle app e chiude i processi in background
"${A[@]}" shell pm trim-caches 64G >/dev/null 2>&1
"${A[@]}" shell am kill-all
echo "fatto. Memoria libera: $("${A[@]}" shell grep MemAvailable /proc/meminfo | tr -s ' ' | cut -d' ' -f2) kB"
