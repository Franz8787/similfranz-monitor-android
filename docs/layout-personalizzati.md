# Creare un layout personalizzato

Il monitor ha due categorie di layout, che compaiono in tutti i punti in cui si sceglie il layout (menu ☰ del
monitor, app controller, regia web):

- **Default**: i layout originali del progetto: *ICU*, *extraH*, *DAE*.
- **Personalizzati**: i layout che aggiungi tu. Nella versione pubblica è vuota: il menu mostra
  «Nessun layout personalizzato: vedi la guida per crearne uno».

Un layout è **codice compilato** insieme all'app, come sull'iPad®: non si carica da un file a parte. Lo scrivi, lo
registri in due punti (il catalogo e il registro delle viste) e ricompili. Il motore (paziente, tracce, allarmi,
defibrillatore, voce, controller, regia web) è lo stesso per tutti i layout: un layout nuovo disegna solo il
frontale e trasforma i tocchi in comandi.

I layout devono restare **originali**: niente nomi, loghi o frontali copiati da dispositivi in commercio.

## 1. Che cos'è un layout

Un layout è una vista (`UIView` del modulo `uk`, la UIKit del progetto su Android) che rispetta l'interfaccia
`SFAltLayout` (`app/src/main/java/it/simulafranz/monitor/SFAltLayout.kt`). È la stessa interfaccia del
protocollo `SFAltLayout` dell'iPad (`monitor/src/AltLayout.h`):

| Metodo | Che cosa fa |
|---|---|
| `updateWithSnapshot(s)` | riceve lo stato (valori, allarmi, defibrillatore) a ogni aggiornamento e aggiorna numeri e spie |
| `tick(now, snapshot)` | fa avanzare le tracce (ECG, pletismografia, capnografia) |
| `resetTracesAt(t)` | riparte con tracce pulite (cambio di layout, reset) |
| `setAlarmText(...)` | mostra l'allarme più importante |
| `poweredOff()` / `setDevicePowered(on)` | stato di accensione, condiviso con il controller |
| `runSelfTestWithShot(shot, done)` | prova automatica del layout (facoltativa: può chiamare subito `done(0)`) |
| `onLayoutMenu`, `onInfo`, `onVolumes` | li imposta il monitor: aprono menu dei layout, informazioni e volumi |

Il costruttore è sempre `(frame: CGRect, state: SimulationState, engine: WaveformEngine, sound: SoundEngine)`.
I tocchi dei partecipanti diventano comandi del modello, gli stessi dell'API
(`state.applyCommand(mapOf("command" to "defib_charge"))`, `set_sensor`, `defib_shock`…).

## 2. Scrivi la vista

1. Copia il layout di partenza più vicino al tuo:
   - `ExtraHView.kt` per un monitor-defibrillatore;
   - `GenericAedView.kt` per un DAE;
   - per un monitor da terapia intensiva parti dal layout ICU (`MonitorViewController`) o da `ExtraHView.kt`.
2. Mettila in `app/src/custom/java/it/simulafranz/monitor/`, con un nome di classe nuovo, per esempio
   `MioMonitorView.kt` con `class MioMonitorView(...) : UIView(frame), SFAltLayout`.
   (La cartella `src/custom` contiene tutto ciò che è personalizzato: è facile da tenere fuori da una build
   pubblica, vedi il punto 6.)
3. Dai un nome a ogni tasto (dizionario `_keys`, come in `ExtraHView`): così lo puoi premere dal computer con
   `adb shell am start -a android.intent.action.VIEW -d "simulafranz-monitor://tap?key=NOME"`.

Regole che tengono fluido un tablet economico: decorazione fissa disegnata una volta sola, niente trasparenze sopra
le tracce, aggiornare un'etichetta solo se il testo cambia (vedi le funzioni `…SetText` dei layout esistenti).

## 3. Registra il layout nel catalogo (nome, categoria, tipo)

Il catalogo è la **fonte unica** dei layout: da lì leggono il menu del monitor, il controller e la regia web (campo
`layouts` dello stato, `/api/v1/state`). Apri
`core-state/src/custom/kotlin/it/simulafranz/core/SFCustomLayoutCatalog.kt` (se non c'è, copia quello di
`core-state/src/public/...`) e aggiungi una riga:

```kotlin
object SFCustomLayoutCatalog {
    @JvmStatic fun entries(): List<Map<String, String>> = listOf(
        SFLayoutEntry("mio_monitor", "Mio monitor", SFLayoutCategoryCustom, SFLayoutKindIcu),
    )
}
```

- **id**: minuscole, cifre e `_`; è il valore del comando `set_layout` e resta salvato sul monitor.
- **nome**: quello che compare nei menu.
- **categoria**: `SFLayoutCategoryCustom` («Personalizzati»).
- **tipo** (`kind`): decide il posto nell'elenco, con lo stesso ordine logico dei layout Default:

| kind | costante | dispositivo | layout Default dello stesso tipo |
|---|---|---|---|
| `icu` | `SFLayoutKindIcu` | monitor da terapia intensiva | ICU |
| `ward` | `SFLayoutKindWard` | monitor o defibrillatore di reparto | — |
| `defib` | `SFLayoutKindDefib` | monitor-defibrillatore da emergenza e trasporto | extraH |
| `aed` | `SFLayoutKindAed` | DAE | DAE |

Dentro «Personalizzati» i layout si ordinano per tipo (icu → ward → defib → aed); a parità di tipo vale l'ordine in
cui li scrivi. Un layout nuovo si colloca quindi da solo al posto giusto.

Nello stato ogni voce arriva così:

```json
{"id": "mio_monitor", "name": "Mio monitor", "category": "custom", "kind": "icu"}
```

Un programma che legge un monitor più vecchio, senza `category`, mette tutto in «Default».

## 4. Registra la vista

Apri `app/src/custom/java/it/simulafranz/monitor/SFCustomLayouts.kt` (se non c'è, copia quello di
`app/src/public/...`) e associa all'id la tua classe:

```kotlin
object SFCustomLayouts {
    val all: List<SFCustomLayout> = listOf(
        SFCustomLayout("mio_monitor", wide = true) { f, st, en, so -> MioMonitorView(f, st, en, so) },
    )
}
```

- `wide = true`: il layout occupa tutto lo schermo come l'ICU (il tasto ☰ diventa piccolo in alto a sinistra).
- `wide = false` (di serie): il layout è un dispositivo largo 1024 punti al centro, con le prese delle sonde e la
  scocca nelle fasce laterali, come extraH e DAE.

Non serve toccare altro: il monitor crea la vista dal registro (`classForLayout`), il menu ☰ la mette sotto
«Personalizzati», controller e regia web la mostrano dal catalogo.

## 5. Compila, installa, prova

```bash
cd monitor
./gradlew :core-tests:run                          # test del cuore: il catalogo deve restare coerente
./gradlew :app:assembleDebug                       # compila il monitor
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am start -a android.intent.action.VIEW -d "simulafranz-monitor://layout?name=mio_monitor"
```

Poi controlla che il layout compaia in ☰ › Layout › Personalizzati, nel controller (Layout del monitor) e nella
regia web (pannello Monitor), e che i tocchi producano gli eventi attesi nel registro.

## 6. Build pubblica e build con i tuoi layout

Tutto ciò che è personalizzato sta nelle cartelle `src/custom` di `core-state` e `app`. La build scelta con il flag
Gradle `sfPublic` usa al loro posto le cartelle `src/public`, dove gli elenchi sono vuoti:

```bash
./gradlew :app:assembleDebug -PsfPublic=true   # «Personalizzati» vuota
./gradlew :app:assembleDebug                   # con i layout di src/custom
```

Se la cartella `src/custom` non esiste (come nella repo pubblica appena scaricata), la build usa `src/public` anche
senza flag: per il tuo primo layout copia `SFCustomLayoutCatalog.kt` da `core-state/src/public/kotlin/...` a
`core-state/src/custom/kotlin/...` e `SFCustomLayouts.kt` da `app/src/public/java/...` a `app/src/custom/java/...`,
poi segui i punti 3 e 4.

Se un file di vista personalizzata sta in `app/src/main` invece che in `app/src/custom`, aggiungilo all'elenco
`sfCustomLayoutFiles` in `app/build.gradle.kts`: la build pubblica non lo compila.

## Come lo fa l'iPad

Sul [monitor per iPad](https://github.com/Franz8787/similfranz-monitor) il meccanismo è lo stesso: un layout è una
`UIView` con il protocollo `SFAltLayout`. Su Android l'id, il nome e il tipo vanno nel catalogo
`SFCustomLayoutCatalog` e la classe nel registro `SFCustomLayouts`, senza toccare il resto del codice.

---
I layout che crei devono essere originali: niente nomi, loghi, marchi o frontali di dispositivi in commercio, che
appartengono ai rispettivi titolari. Se distribuisci un layout, la responsabilità di ciò che contiene è tua.

---
© 2026 Francesco Palmisano · GPL-3.0. iPad® è un marchio di Apple Inc., registrato negli Stati Uniti e in altri Paesi;
Android™ è un marchio di Google LLC. Progetto indipendente, non affiliato ai titolari dei marchi.
