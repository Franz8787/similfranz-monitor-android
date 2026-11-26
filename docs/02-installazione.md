# Installare le app

Due strade: **scaricare gli APK pronti** (la più semplice) oppure **compilarli** dal codice. In entrambi i casi non
servono account, abbonamenti o il Play Store, e nessun «sblocco» del tablet.

## Che cosa serve

- Uno o due tablet Android™ 8 o successivi, in orizzontale (pensato e provato sul Samsung® Galaxy® Tab Active2).
- Un cavo USB per collegare il tablet al computer (solo per la strada con il computer).
- Per compilare: un computer Mac®, Linux® o Windows® con **Android Studio** (porta Java 17 e l'SDK Android).

## Strada A: APK pronti, dal tablet

1. Dalla pagina **Releases** della repo scarica `similfranz-monitor-<versione>.apk` sul tablet del monitor e
   `similfranz-controller-<versione>.apk` su quello del controller.
2. Apri il file scaricato. Android chiede di consentire l'installazione da questa fonte: consentila (solo per questa
   app, se il tablet lo permette).
3. Al primo avvio concedi i permessi che l'app chiede:
   - **monitor**: posizione (Android la chiede per il collegamento diretto Wi-Fi Direct®), fotocamera e microfono (solo
     per la Sala, che si accende soltanto quando la apri dalla regia);
   - **controller**: posizione (Wi-Fi Direct) e archivio (per i PDF della Storia Sim).

## Strada B: APK pronti, dal computer con il cavo

1. Sul tablet attiva le **Opzioni sviluppatore** (Impostazioni → Info sul tablet → Informazioni software → tocca 7 volte
   «Numero build») e poi **Debug USB**.
2. Collega il tablet e tocca **Consenti** sulla richiesta che compare.
3. Metti gli APK scaricati in `apk/` con i nomi `similfranz-monitor.apk` e `similfranz-controller.apk`, poi:
   ```bash
   scripts/installa.sh monitor        # sul tablet del monitor
   scripts/installa.sh controller     # sul tablet del controller
   ```
   Lo script installa, concede i permessi e apre l'app. Con due tablet collegati insieme scegli quale con
   `ANDROID_SERIAL=<seriale> scripts/installa.sh …` (i seriali li vedi con `adb devices`).

## Strada C: compilare dal codice

```bash
scripts/compila.sh                  # crea apk/similfranz-monitor.apk e apk/similfranz-controller.apk
scripts/installa.sh monitor
scripts/installa.sh controller
```

Gli APK che compili tu sono firmati con la tua chiave di debug: per aggiornare un'app installata da una Release prima
disinstallala (le firme sono diverse).

Per controllare il cuore del simulatore: `cd monitor && ./gradlew :core-tests:run` (devono passare tutti).

## Dopo l'installazione

- **Tasti del guscio (consigliato se usi il guscio).** Sul monitor puoi attivare ACCENSIONE, CARICA e SCARICA dai tasti
  fisici, l'uscita alla Home solo con tre pressioni e l'icona «Blocca schermo». Dal computer, con il tablet del monitor collegato:
  ```bash
  scripts/tasti-guscio.sh attiva       # per toglierli: scripts/tasti-guscio.sh disattiva
  ```
  Senza computer si attivano dalle Impostazioni del tablet: i passi sono nella
  [guida al monitor](monitor.md#tasti-fisici-del-tablet-e-schermo). Sono due permessi di Android (un servizio di
  accessibilità che guarda solo tre tasti e l'amministratore del dispositivo per il solo blocco dello schermo): il
  progetto non raccoglie e non invia dati.
- Facoltativo: [dedica il tablet alla simulazione](ottimizzazione.md) disattivando le app che non servono.
- Collega i due tablet e prova uno scenario: [guida all'uso](03-uso.md).

## Se qualcosa non va

| Problema | Che cosa fare |
|---|---|
| «App non installata» | c'è già una versione firmata diversamente: disinstallala e riprova |
| Il controller non trova il monitor | Wi-Fi acceso su entrambi (non serve una rete), permesso di posizione concesso, app del monitor aperta |
| `adb` non vede il tablet | cavo dati (non solo ricarica), Debug USB attivo, «Consenti» sul tablet |
| Le tracce scattano | chiudi le altre app; vedi [ottimizzazione](ottimizzazione.md) |
| La voce del DAE non parla | Impostazioni → Gestione generale → Sintesi vocale: installa la voce italiana |

---
© 2026 Francesco Palmisano · GPL-3.0. Samsung® e Galaxy® sono marchi registrati di Samsung Electronics Co., Ltd.;
Android™ è un marchio di Google LLC; Wi-Fi Direct® è un marchio registrato di Wi-Fi Alliance; Mac® è un marchio di
Apple Inc.; Windows® di Microsoft Corporation; Linux® di Linus Torvalds.
