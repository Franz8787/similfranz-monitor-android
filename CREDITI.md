# Crediti e licenze del materiale di terzi

Tutto il resto del progetto (codice, documentazione, suoni, layout, modelli 3D) è opera originale:
© 2026 Francesco Palmisano — codice e documentazione GPL-3.0, modelli 3D (`stampa3d/`) CC BY-NC 4.0.

## Figura del paziente (regia web)

`monitor/app/src/main/assets/web-console/body.svg`: Goran tek-en con Rob Hurt, Wikimedia Commons, licenza CC BY-SA 4.0
(https://creativecommons.org/licenses/by-sa/4.0/). Resta sotto la sua licenza. CC BY-SA 4.0 è compatibile in un solo verso
con la GPL-3.0 del progetto: la figura si distribuisce dentro l'app (APK del monitor) con questa attribuzione e la sua licenza.

## Voce e caratteri

La voce dei DAE usa la sintesi vocale del sistema Android™: il progetto non contiene file vocali.
Nessun file di caratteri è incluso: l'app usa i caratteri di sistema di Android.

# Icone

Material Symbols © Google LLC, Apache License 2.0 (testo: https://www.apache.org/licenses/LICENSE-2.0).
Health Icons © Resolve to Save Lives, CC0 1.0 (pubblico dominio).
Lavorazione: conversione da SVG a PNG, ridimensionamento e colore; nessuna modifica al disegno.

## Set e licenze

| Set | Licenza | Sito / sorgente | Testo della licenza |
|---|---|---|---|
| Material Symbols (Google), stile «Outlined», pieno (FILL 1), peso 400, griglia 48 | Apache License 2.0 | https://fonts.google.com/icons — https://github.com/google/material-design-icons | https://github.com/google/material-design-icons/blob/master/LICENSE — https://www.apache.org/licenses/LICENSE-2.0 |
| Health Icons (Resolve to Save Lives), stile «outline», griglia 48 | CC0 1.0 Universal (pubblico dominio) | https://healthicons.org — https://github.com/resolvetosavelives/healthicons | https://github.com/resolvetosavelives/healthicons/blob/main/LICENSE — https://creativecommons.org/publicdomain/zero/1.0/ |

Apache 2.0: l'uso, la modifica e la ridistribuzione sono liberi, anche in app distribuite; va conservato l'avviso di licenza
(questo file e il link al testo della licenza). CC0: nessun obbligo di attribuzione, la citiamo comunque per correttezza.

## App MONITOR — layout «DAE» generico

Cartella delle risorse: `monitor/app/src/main/res/drawable-nodpi/` (PNG 192×192).
Usate in `monitor/app/src/main/java/it/simulafranz/monitor/GenericAedView.kt` (funzioni `GAIcon`/`GAIconView`).

| File nell'app | Dove compare | Set | Icona | URL SVG |
|---|---|---|---|---|
| `ic_dae_accensione.png` | tasto di accensione verde, passo 1 «ACCENDI» (simbolo IEC 60417-5009) | Material Symbols | power_settings_new | https://github.com/google/material-design-icons/blob/master/symbols/web/power_settings_new/materialsymbolsoutlined/power_settings_new_fill1_48px.svg |
| `ic_dae_placche.png` | passo 2 «COLLEGA»: targa «PLACCHE» che indica la presa sul fianco (connettore del cavo) | Material Symbols | cable | https://github.com/google/material-design-icons/blob/master/symbols/web/cable/materialsymbolsoutlined/cable_fill1_48px.svg |
| `ic_dae_non_toccare.png` | spia «NON TOCCARE IL PAZIENTE» sotto lo schermo | Material Symbols | do_not_touch | https://github.com/google/material-design-icons/blob/master/symbols/web/do_not_touch/materialsymbolsoutlined/do_not_touch_fill1_48px.svg |
| `ic_dae_scarica.png` | passo 3 «SCARICA»: tasto arancione (fulmine) | Material Symbols | electric_bolt | https://github.com/google/material-design-icons/blob/master/symbols/web/electric_bolt/materialsymbolsoutlined/electric_bolt_fill1_48px.svg |
| `ic_dae_bambino.png` | tastino BAMBINO (adulto con bambino per mano) | Material Symbols | escalator_warning | https://github.com/google/material-design-icons/blob/master/symbols/web/escalator_warning/materialsymbolsoutlined/escalator_warning_fill1_48px.svg |

## App CONTROLLER — griglia «SONDE SUL PAZIENTE»

Cartella delle risorse: `controller/app/src/main/res/drawable-nodpi/` (PNG 96×96).
Usate in `CtlViewController.kt` (funzione `CtlSondaIcon`, tessere `CtlSensorTile`). 


| File nell'app | Sonda | Set | Icona | URL SVG |
|---|---|---|---|---|
| `ic_sonda_ecg.png` | Elettrodi ECG | Health Icons | symbols/heartbeat | https://github.com/resolvetosavelives/healthicons/blob/main/public/icons/svg/outline/symbols/heartbeat.svg |
| `ic_sonda_pads.png` | Piastre defibrillatore | Health Icons | devices/defibrillator | https://github.com/resolvetosavelives/healthicons/blob/main/public/icons/svg/outline/devices/defibrillator.svg |
| `ic_sonda_spo2.png` | Sonda SpO₂ | Health Icons | devices/pulse-oximeter | https://github.com/resolvetosavelives/healthicons/blob/main/public/icons/svg/outline/devices/pulse-oximeter.svg |
| `ic_sonda_nibp.png` | Bracciale NIBP | Health Icons | devices/blood-pressure_monitor | https://github.com/resolvetosavelives/healthicons/blob/main/public/icons/svg/outline/devices/blood-pressure_monitor.svg |
| `ic_sonda_art.png` | Linea arteriosa | Health Icons | devices/infusion-pump | https://github.com/resolvetosavelives/healthicons/blob/main/public/icons/svg/outline/devices/infusion-pump.svg |
| `ic_sonda_etco2.png` | Capnografia (EtCO₂) | Health Icons | body/lungs | https://github.com/resolvetosavelives/healthicons/blob/main/public/icons/svg/outline/body/lungs.svg |
| `ic_sonda_temp.png` | Sonda temperatura | Health Icons | devices/thermometer | https://github.com/resolvetosavelives/healthicons/blob/main/public/icons/svg/outline/devices/thermometer.svg |

## Icone disegnate nel codice (nessun credito di terzi)

- Controller: pausa, campanella, manometro ecc. (`CtlIcon` in `CtlUI.kt`).
- Monitor: le icone degli altri layout sono disegnate nel codice (es. il fulmine `SFBoltPath` in `SFBolt.kt`).

# Suoni

Elenco dei suoni usati come file nelle app Android (i bip, gli allarmi e i toni del defibrillatore sono sintetizzati dal codice, in `SoundEngine.kt`, e non sono file).

| File nell'app | Dove suona | Autore | Licenza | Sorgente |
|---|---|---|---|---|
| `monitor/app/src/main/assets/sounds/fine_scenario.wav` (5,2 s, mono) | monitor, una volta a FINE SCENARIO (`SFEndJingle.kt`) | composto e sintetizzato da noi (progetto SimilFranz): due battiti del cuore che si sciolgono in un arpeggio di Sol maggiore e in un accordo sospeso che si risolve | CC0 1.0 Universal (pubblico dominio) — https://creativecommons.org/publicdomain/zero/1.0/ | `scripts/audio/make_jingle.py` |

## Video, musica e caratteri

- **Videotutorial** (`docs/video/`): riprese dello schermo delle app e animazioni 3D del guscio fatte per questo progetto.
  La **musica** è originale, sintetizzata dal codice per il progetto: nessun brano di terzi.
- **Pagina del progetto** (`docs/index.html`): i caratteri Familjen Grotesk, Atkinson Hyperlegible e JetBrains Mono sono
  caricati da Google Fonts (licenza SIL Open Font License 1.1); la pagina fa quindi una richiesta a un server esterno.
- **Compilazione**: `gradle-wrapper.jar` è di Gradle (licenza Apache 2.0).
