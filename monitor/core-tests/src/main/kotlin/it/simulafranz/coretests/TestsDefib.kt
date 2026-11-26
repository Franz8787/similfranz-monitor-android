// Portato da tests/run-tests.m (monitor iPad)
// testDefib, testSpO2TempCrt, testMotion, testSites, testCrtAssess, testCuffError
package it.simulafranz.coretests

import it.simulafranz.core.*
import java.io.ByteArrayOutputStream
import java.util.Base64
import java.util.zip.Deflater
import java.util.zip.GZIPOutputStream

fun testDefib() {
    val s = NewState()
    val err = Err()
    var d = s.snapshot().defib
    CHECK(d.mode == SLDefibOff && !d.padsConnected && d.energy == 150 && d.shockCount == 0, "defib: stato iniziale spento")
    CHECK(!Cmd(s, "defib_mode", "boh", err), "defib_mode non valido rifiutato")

    CHECK(s.stateDictionary()["defib"]["config"]["outcome_mode"] == "instructor", "esito predefinito: decide l'istruttore dopo ogni scarica")
    Cmd(s, "set_defib_config", mapOf("outcome_mode" to "instructor"), null)
    CHECK(!Cmd(s, "set_defib_config", mapOf("outcome_mode" to "boh"), err), "outcome_mode non valido rifiutato")
    // decide l'istruttore: lo shock non cambia il ritmo finché non sceglie
    Cmd(s, "defib_mode", "manual", null); Sensor(s, "pads", true); RhythmNow(s, "vf", null)
    Cmd(s, "defib_charge", null, null); Tick(s, 5); Cmd(s, "defib_shock", null, null)
    var dd = s.stateDictionary()["defib"]
    CHECK(s.snapshot().rhythm == "vf" && BOOL(dd["outcome_pending"]) && dd["outcome_from"] == "vf",
          "istruttore: dopo lo shock resta FV in attesa della decisione")
    CHECK(!Cmd(s, "defib_outcome", "boh", err), "defib_outcome non valido rifiutato")
    CHECK(Cmd(s, "defib_outcome", "stay", err) && s.snapshot().rhythm == "vf" &&
          !BOOL(s.stateDictionary()["defib"]["outcome_pending"]), "istruttore: resta nel ritmo pre-shock")
    Cmd(s, "defib_charge", null, null); Tick(s, 5); Cmd(s, "defib_shock", null, null)
    CHECK(Cmd(s, "defib_outcome", "sinus", err) && PS(s) && s.snapshot().rhythm == "sinus" && s.snapshot().pulsePresent,
          "istruttore: torna in sinusale (ROSC)")
    // anche la scarica SINCRONIZZATA chiede l'esito, e lo stato dice che era sincronizzata e con quanti joule
    RhythmNow(s, "afib", null); Cmd(s, "defib_energy", 100, null); Cmd(s, "defib_sync", true, null)
    Cmd(s, "defib_charge", null, null); Tick(s, 5); Cmd(s, "defib_shock", null, null); s.defibRWaveAt(gNow + 0.2)
    dd = s.stateDictionary()["defib"]
    CHECK(BOOL(dd["outcome_pending"]) && BOOL(dd["outcome_sync"]) && INT(dd["outcome_energy_j"]) == 100 &&
          dd["outcome_from"] == "afib" && s.snapshot().rhythm == "afib",
          "istruttore: cardioversione sincronizzata in attesa della decisione (FA, 100 J, SYNC)")
    CHECK(Cmd(s, "defib_outcome", "sinus", err) && PS(s, 3.0) && s.snapshot().rhythm == "sinus", "istruttore: cardioversione → sinusale")
    Cmd(s, "defib_sync", false, null)
    Cmd(s, "defib_charge", null, null); Tick(s, 5)
    Cmd(s, "defib_charge", null, null)
    CHECK(s.snapshot().defib.charge == SLChargeReady, "CARICA da già carico: non ricomincia la carica")
    Cmd(s, "defib_disarm", null, null)
    Sensor(s, "pads", false); Sensor(s, "ecg", true)
    Cmd(s, "defib_sync", true, null)
    CHECK(!Cmd(s, "defib_charge", null, err), "solo elettrodi ECG: il defibrillatore non ha le piastre")
    Cmd(s, "defib_sync", false, null)
    Cmd(s, "defib_mode", "off", null)
    Reset(s)
    Cmd(s, "set_defib_config", mapOf("outcome_mode" to "auto"), null)     // i test seguenti verificano le regole automatiche

    // --- MANUALE ---
    Cmd(s, "defib_mode", "manual", null)
    CHECK(!Cmd(s, "defib_charge", null, err) && s.snapshot().defib.message.contains("PIASTRE"), "carica senza piastre rifiutata")
    Sensor(s, "pads", true)
    CHECK(!Cmd(s, "defib_energy", "12", err) && !Cmd(s, "defib_energy", 11, err), "energia non selezionabile rifiutata")
    CHECK(Cmd(s, "defib_energy", 200, err) && s.snapshot().defib.energy == 200, "energia 200 J")
    CHECK(!Cmd(s, "defib_shock", null, err), "shock senza carica rifiutato")
    Cmd(s, "defib_charge", null, null)
    CHECK(s.snapshot().defib.charge == SLChargeCharging, "carica in corso")
    Tick(s, 6)
    CHECK(s.snapshot().defib.charge == SLChargeReady && s.snapshot().defib.chargedEnergy == 200, "carica completa ~5 s")
    Cmd(s, "defib_energy", 150, null)
    CHECK(s.snapshot().defib.charge == SLChargeNone, "cambio energia da carico: carica annullata")
    Cmd(s, "defib_charge", null, null); Tick(s, 5); Tick(s, 61)
    CHECK(s.snapshot().defib.charge == SLChargeNone, "scarica interna dopo 60 s")

    // FV: shock asincrono 150 J -> conversione al ritmo configurato (sinusale) e ROSC
    RhythmNow(s, "vf", null)
    Cmd(s, "defib_charge", null, null); Tick(s, 5)
    CHECK(Cmd(s, "defib_shock", null, err), "shock erogato")
    PS(s)                                                     // ANDROID (EF-04): pausa e primi complessi lenti
    var sn = s.snapshot()
    CHECK(sn.defib.shockCount == 1 && sn.defib.lastShockEnergy == 150 && sn.rhythm == "sinus" && sn.pulsePresent,
          "FV + 150 J -> sinusale")
    // energia sotto la soglia: nessuna conversione
    RhythmNow(s, "vf", null); Cmd(s, "defib_energy", 50, null)
    Cmd(s, "defib_charge", null, null); Tick(s, 3); Cmd(s, "defib_shock", null, null)
    CHECK(s.snapshot().rhythm == "vf", "FV + 50 J (sotto soglia 120): resta FV")
    // servono 2 shock efficaci
    Cmd(s, "set_defib_config", mapOf("shocks_to_convert" to 2, "post_shock_rhythm" to "pea"), null)
    Cmd(s, "defib_energy", 200, null)
    Cmd(s, "defib_charge", null, null); Tick(s, 6); Cmd(s, "defib_shock", null, null)
    CHECK(s.snapshot().rhythm == "vf", "1° shock efficace su 2: resta FV")
    Cmd(s, "defib_charge", null, null); Tick(s, 6); Cmd(s, "defib_shock", null, null)
    CHECK(s.snapshot().rhythm == "pea", "2° shock: ritmo post-shock configurato (PEA)")
    CHECK(!Cmd(s, "set_defib_config", mapOf("post_shock_rhythm" to "torsade"), err) && !Cmd(s, "set_defib_config", mapOf("boh" to 1), err),
          "config non valida rifiutata")
    Cmd(s, "set_defib_config", mapOf("shocks_to_convert" to 1, "post_shock_rhythm" to "sinus"), null)

    // R su T: shock NON sincronizzato su FA -> FV
    RhythmNow(s, "afib", null)
    Cmd(s, "defib_charge", null, null); Tick(s, 6); Cmd(s, "defib_shock", null, null)
    CHECK(s.snapshot().rhythm == "vf", "FA + shock asincrono -> FV (R su T)")

    // Cardioversione sincronizzata: lo shock attende l'onda R
    RhythmNow(s, "afib", null)
    CHECK(Cmd(s, "defib_sync", true, err) && s.snapshot().defib.sync, "SYNC attivo")
    Cmd(s, "defib_charge", null, null); Tick(s, 6)
    Cmd(s, "defib_shock", null, null)
    sn = s.snapshot()
    CHECK(sn.defib.syncPending && sn.defib.shockCount == 5 && sn.rhythm == "afib", "SYNC: shock in attesa dell'onda R")
    s.defibRWaveAt(gNow - 1)
    CHECK(s.snapshot().defib.syncPending, "onda R precedente alla pressione ignorata")
    s.defibRWaveAt(gNow + 0.2)
    PS(s, 3.0); sn = s.snapshot()
    CHECK(!sn.defib.syncPending && sn.defib.shockCount == 6 && sn.defib.lastShockSynced && sn.rhythm == "sinus",
          "SYNC: erogato sull'onda R, FA -> sinusale")
    CHECK(!sn.defib.sync, "dopo lo shock il SYNC si disattiva (predefinito)")
    Cmd(s, "set_defib_config", mapOf("sync_stays_on" to true), null)
    RhythmNow(s, "vt_pulse", null); Cmd(s, "defib_sync", true, null)
    Cmd(s, "defib_charge", null, null); Tick(s, 6); Cmd(s, "defib_shock", null, null); s.defibRWaveAt(gNow + 0.1)
    PS(s, 3.0); sn = s.snapshot()
    CHECK(sn.rhythm == "sinus" && sn.defib.sync, "TV con polso: cardioversione e SYNC che resta attivo")
    Cmd(s, "defib_sync", false, null)
    // piastre staccate da carico: disarmo
    Cmd(s, "defib_charge", null, null); Tick(s, 6)
    Sensor(s, "pads", false)
    CHECK(s.snapshot().defib.charge == SLChargeNone, "piastre staccate: carica annullata")

    // --- DAE ---
    Reset(s)
    RhythmNow(s, "vf", null)
    Cmd(s, "defib_mode", "aed", null)
    d = s.snapshot().defib
    CHECK(d.aedPhase == SLAedPads && HASSTR(d.voicePrompt, "piastre"), "DAE: chiede le piastre")
    val seq = d.promptSeq
    CHECK(!Cmd(s, "defib_energy", 200, err), "DAE: energia non modificabile")
    Sensor(s, "pads", true)
    d = s.snapshot().defib
    CHECK(d.aedPhase == SLAedAnalyzing && d.promptSeq > seq, "DAE: piastre -> analisi con voce")
    Cmd(s, "set_cpr", true, null); Tick(s, 10)
    CHECK(s.snapshot().defib.aedPhase == SLAedAnalyzing && s.snapshot().defib.message.contains("MOVIMENTO"), "DAE: RCP durante l'analisi = movimento")
    Cmd(s, "set_cpr", false, null); Tick(s, 8.5)
    d = s.snapshot().defib
    CHECK(d.aedShockAdvised && (d.aedPhase == SLAedCharging || d.aedPhase == SLAedReady), "DAE: FV -> shock consigliato")
    Tick(s, 5)
    CHECK(s.snapshot().defib.aedPhase == SLAedReady && s.snapshot().defib.charge == SLChargeReady, "DAE: carico, premere shock")
    Cmd(s, "defib_shock", null, null)
    PS(s); sn = s.snapshot()
    CHECK(sn.defib.aedPhase == SLAedCPR && sn.defib.shockCount == 1 && sn.defib.lastShockEnergy == 150 && sn.rhythm == "sinus",
          "DAE: shock 150 J, conversione, RCP")
    RhythmNow(s, "asystole", null)
    Tick(s, 114)                                              // ANDROID (EF-04): 7 s già passati nel preludio dopo la scarica
    CHECK(s.snapshot().defib.aedPhase == SLAedAnalyzing, "DAE: dopo 2 min di RCP nuova analisi")
    Tick(s, 9)
    d = s.snapshot().defib
    CHECK(!d.aedShockAdvised && d.aedPhase == SLAedCPR, "DAE: asistolia -> shock non consigliato")
    Cmd(s, "defib_analyze", null, null)
    CHECK(s.snapshot().defib.aedPhase == SLAedAnalyzing, "DAE: ANALIZZA manuale")
    RhythmNow(s, "vf", null); Tick(s, 9); Tick(s, 5); Tick(s, 61)
    CHECK(s.snapshot().defib.aedPhase == SLAedCPR && s.snapshot().defib.shockCount == 1, "DAE: shock non premuto -> scarica interna e RCP")
    RhythmNow(s, "vt_pulse", null); Cmd(s, "set_heart_rate", 180, null); Cmd(s, "defib_analyze", null, null); Tick(s, 9)
    CHECK(s.snapshot().defib.aedShockAdvised, "DAE: TV rapida (>=150) consigliata anche con polso")

    // --- PACER ---
    Reset(s)
    RhythmNow(s, "sinus_bradycardia", null)       // FC 45
    Cmd(s, "defib_mode", "pacer", null)
    CHECK(!Cmd(s, "defib_pacer", mapOf("running" to true), err), "pacing senza piastre rifiutato")
    Sensor(s, "pads", true)
    CHECK(!Cmd(s, "defib_pacer", mapOf("rate" to 250), err), "frequenza fuori range rifiutata")
    Cmd(s, "defib_pacer", mapOf("rate" to 70, "output_ma" to 40, "running" to true), null)
    d = s.snapshot().defib
    CHECK(d.paceRunning && d.paceActive && !d.paceCapture && s.snapshot().displayedHeartRate == 45, "40 mA sotto soglia (70): spike senza cattura")
    Cmd(s, "defib_pacer", mapOf("output_ma" to 80), null)
    sn = s.snapshot()
    CHECK(sn.defib.paceCapture && sn.defib.paceMechanical && sn.displayedHeartRate == 70 && sn.pulseRate == 70, "80 mA: cattura elettrica e meccanica a 70")
    Cmd(s, "defib_pacer", mapOf("rate" to 40), null)
    CHECK(!s.snapshot().defib.paceActive, "demand: inibito con ritmo proprio (45) >= frequenza (40)")
    Cmd(s, "defib_pacer", mapOf("demand" to false), null)
    d = s.snapshot().defib
    CHECK(d.paceActive && !d.paceCapture, "fisso: spike anche con ritmo proprio, ma senza cattura")
    Cmd(s, "defib_pacer", mapOf("rate" to 70, "demand" to true), null)
    RhythmNow(s, "asystole", null)
    sn = s.snapshot()
    CHECK(sn.defib.paceCapture && sn.defib.paceMechanical && sn.pulsePresent, "asistolia: cattura meccanica (predefinita) genera polso")
    Cmd(s, "set_defib_config", mapOf("mechanical_capture" to false), null)
    sn = s.snapshot()
    CHECK(sn.defib.paceCapture && !sn.defib.paceMechanical && !sn.pulsePresent, "istruttore: solo cattura elettrica, nessun polso")
    RhythmNow(s, "sinus_bradycardia", null)
    CHECK(!s.snapshot().pulsePresent, "bradicardia stimolata senza cattura meccanica: niente polso")
    Cmd(s, "set_defib_config", mapOf("mechanical_capture" to true), null); RhythmNow(s, "asystole", null)
    CHECK(!AlarmManager.evaluate(sn).messages.contains("ASISTOLIA"), "asistolia stimolata: niente allarme asistolia")
    Cmd(s, "set_defib_config", mapOf("capture_possible" to false), null)
    CHECK(!s.snapshot().defib.paceCapture, "istruttore: cattura impossibile")
    Cmd(s, "set_defib_config", mapOf("capture_possible" to true), null)
    // PAUSE: 25% della frequenza impostata (ritmo proprio visibile)
    RhythmNow(s, "sinus_bradycardia", null); Cmd(s, "set_heart_rate", 40, null)
    Cmd(s, "defib_pacer", mapOf("rate" to 80, "paused" to true), null)
    d = s.snapshot().defib
    CHECK(d.pacePaused && d.paceRate == 20 && d.paceSetRate == 80 && !d.paceActive, "PAUSE: 25% (20 ppm), demand inibito dal ritmo proprio")
    Cmd(s, "defib_pacer", mapOf("paused" to false), null)
    CHECK(s.snapshot().defib.paceRate == 80 && s.snapshot().defib.paceActive, "PAUSE rilasciato: 80 ppm")
    CHECK(Cmd(s, "defib_energy", 4, err), "energia 4 J accettata")
    Cmd(s, "defib_mode", "pacer", null)
    Cmd(s, "set_layout", "generic_icu", null); Cmd(s, "set_heart_rate", 45, null); Cmd(s, "defib_pacer", mapOf("rate" to 70), null)
    Sensor(s, "pads", false)
    CHECK(!s.snapshot().defib.paceRunning, "piastre staccate: pacing interrotto")

    // forme d'onda: spike e battiti stimolati
    RhythmNow(s, "sinus_bradycardia", null); Sensor(s, "pads", true)
    Cmd(s, "defib_pacer", mapOf("rate" to 80, "output_ma" to 90, "running" to true), null)
    val e = WaveformEngine(7)
    e.updateWithSnapshot(s.snapshot())
    var spikes = 0; var beats = 0; var lastB = -1.0
    var t = 0.0
    while (t < 6.0) {
        e.ecgAt(t, SLLeadII)
        if ((e.markersFrom(t, t + 0.004, false) and SLMarkerPace) != 0) spikes++
        val b = e.lastBeatAtOrBefore(t); if (b > lastB) { lastB = b; beats++ }
        t += 0.004
    }
    CHECK(spikes >= 7 && spikes <= 9 && beats >= 7 && beats <= 9) { F("pacing 80/min: ~8 spike e ~8 battiti in 6 s (spike %d, battiti %d)", spikes, beats) }
    CHECK(e.ecgAt(6.1, SLLeadPads) != 0.0 || e.ecgAt(6.2, SLLeadPads) != 0.0, "derivazione piastre attiva")

    // reset: config dell'istruttore conservata, stato del defibrillatore azzerato
    Cmd(s, "set_defib_config", mapOf("capture_threshold_ma" to 55), null)
    Reset(s)
    val st = s.stateDictionary()
    CHECK(st["defib"]["mode"] == "off" && INT(st["defib"]["config"]["capture_threshold_ma"]) == 55 &&
          !BOOL(st["sensors"]["pads"]), "reset: defib spento, config conservata")
}

fun testSpO2TempCrt() {
    val s = NewStateRaw()
    val err = Err()
    CHECK(Cmd(s, "set_spo2_delay", 10, err) && !Cmd(s, "set_spo2_delay", 500, err), "ritardo SpO2 0..120 s")
    Sensor(s, "ecg", true); Sensor(s, "spo2", true)
    var sn = s.snapshot()
    CHECK(sn.spo2Searching && sn.spo2Acquiring && !sn.spo2Displayable, "sonda appena messa: ricerca, valore nascosto")
    Tick(s, 5.5)
    sn = s.snapshot()
    CHECK(!sn.spo2Searching && sn.spo2Acquiring && !sn.spo2Displayable, "metà ritardo: onda presente, valore ancora nascosto")
    Tick(s, 5)
    CHECK(s.snapshot().spo2Displayable, "dopo 10 s il valore compare")
    Sensor(s, "spo2", false); Sensor(s, "spo2", true)
    CHECK(s.snapshot().spo2Acquiring, "ricollegando la sonda il ritardo riparte")
    Cmd(s, "set_spo2_delay", 0, null); Sensor(s, "spo2", false); Sensor(s, "spo2", true)
    CHECK(s.snapshot().spo2Displayable, "ritardo 0: valore subito")
    CHECK(Cmd(s, "set_spo2_low_perfusion", true, err) && s.snapshot().lowPerfusion && BOOL(s.stateDictionary()["spo2_low_perfusion"]),
          "flag bassa perfusione con PA normale")
    Cmd(s, "set_spo2_low_perfusion", false, null)
    CHECK(!s.snapshot().lowPerfusion, "flag bassa perfusione disattivato")

    CHECK(Cmd(s, "set_temperature", 38.6, err) && s.snapshot().temperatureTenths == 386, "temperatura 38.6")
    CHECK(!Cmd(s, "set_temperature", 50, err) && !Cmd(s, "set_temperature", true, err), "temperatura fuori range rifiutata")
    CHECK(ISNULL(s.stateDictionary(), "temperature_displayed"), "temperatura nascosta senza sonda")
    Sensor(s, "temp", true)
    CHECK(Math.abs(DBL(s.stateDictionary()["temperature_displayed"]) - 38.6) < 0.01, "temperatura mostrata con la sonda")
    Sensor(s, "pads", true)
    CHECK(BOOL(s.stateDictionary()["sensors"]["temp"]) && BOOL(s.stateDictionary()["sensors"]["pads"]), "stato API: sensori temp e pads")
    CHECK(Cmd(s, "set_crt", 3.5, err) && s.snapshot().crtTenths == 35 && !Cmd(s, "set_crt", 0, err), "refill capillare 0.5..10 s")
    CHECK(Cmd(s, "set_crt_enabled", false, err) && !s.snapshot().crtEnabled, "refill disattivabile")
    // NIBP: seconda misura senza bracciale -> errore "cuff" e il valore precedente sparisce
    Sensor(s, "nibp", true); RhythmNow(s, "sinus", null); RunNibp(s)
    CHECK(s.snapshot().nibpLastSystolic > 0, "prima misura NIBP valida")
    Sensor(s, "nibp", false); RunNibp(s)
    CHECK(s.snapshot().nibpError == "cuff" && s.snapshot().nibpLastSystolic < 0, "bracciale tolto: errore perdite e valore precedente cancellato")
    CHECK(Cmd(s, "mute_alarms", 120, err) && s.snapshot().alarmsMuted && s.snapshot().alarmsMutedRemaining == 120, "silenzio di 120 s")
    CHECK(!Cmd(s, "mute_alarms", 2000, err), "silenzio oltre 15 min rifiutato")
    Cmd(s, "mute_alarms", false, null)
    // layout originali (versione pubblica) per primi nel catalogo, con i loro nomi
    val cat = SimulationState.layoutCatalog()
    CHECK(cat.size >= 3 && cat[0]["id"] == "generic_icu" && cat[0]["name"] == "ICU"
          && cat[1]["id"] == "extrah" && cat[1]["name"] == "extraH"
          && cat[2]["id"] == "dae" && cat[2]["name"] == "DAE", "catalogo dei layout: ICU, extraH, DAE per primi")
    CHECK(s.stateDictionary()["layouts"] == cat && SimulationState.supportedLayouts().size == cat.size, "catalogo nello stato, elenco degli id coerente")
    // ANDROID (categorie dei layout 22/11): ogni voce ha categoria e tipo; prima «Default», poi «Personalizzati», per tipo
    CHECK(cat.all { (it["category"] == "default" || it["category"] == "custom") && it["kind"] != null }, "catalogo: ogni layout ha categoria e tipo")
    CHECK(cat.filter { it["category"] == "default" }.map { it["id"] } == listOf("generic_icu", "extrah", "dae"), "categoria Default: ICU, extraH, DAE")
    CHECK(cat.indexOfLast { it["category"] == "default" } < (cat.indexOfFirst { it["category"] == "custom" }.takeIf { it >= 0 } ?: cat.size), "Default prima di Personalizzati")
    val kOrd = listOf("icu", "ward", "defib", "aed")
    val cusK = cat.filter { it["category"] == "custom" }.map { kOrd.indexOf(it["kind"]).let { i -> if (i < 0) 9 else i } }
    CHECK(cusK == cusK.sorted(), "Personalizzati ordinati per tipo (terapia intensiva, reparto, defibrillatore, DAE)")
    CHECK(cat.map { it["id"] }.toSet().size == cat.size, "catalogo senza id doppi")
    CHECK(SFLayoutIsAed("dae") && !SFLayoutIsAed("extrah") && !SFLayoutIsAed(null), "SFLayoutIsAed: dae sì, extrah no")
    for (a in cat.filter { it["kind"] == "aed" }.map { it["id"]!! }) {
        val t = NewState(); Cmd(t, "set_layout", "extrah", null); Cmd(t, "defib_mode", "aed", null)
        CHECK(Cmd(t, "set_layout", a, err) && t.snapshot().defib.mode == SLDefibOff && !t.devicePowered, "DAE $a: arriva spento")
    }
    CHECK(Cmd(s, "set_layout", "extrah", err) && s.snapshot().layout == "extrah", "layout extrah")
    CHECK(Cmd(s, "set_layout", "dae", err) && s.snapshot().layout == "dae", "layout dae")
    CHECK(!Cmd(s, "set_layout", "boh", err) && err.has("extrah") && err.has("dae"), "layout sconosciuto: l'errore elenca quelli disponibili")
    // extraH: il defibrillatore usa anche gli elettrodi ECG del dispositivo, e nessuna regola aggiuntiva
    Cmd(s, "set_layout", "extrah", null)
    CHECK(!Cmd(s, "set_layout", "boh", err) && Cmd(s, "set_layout", "extrah", err) && s.snapshot().layout == "extrah", "layout valido / non valido")
    CHECK(Cmd(s, "defib_event", null, err) && s.snapshot().defib.events.lastOrNull()["text"] == "Evento marcato", "tasto EVENT nel registro")
    Reset(s)
    CHECK(s.snapshot().temperatureTenths == 368 && s.snapshot().crtTenths == 20 && !s.snapshot().tempConnected, "reset: 36.8 °C, refill 2 s, sonda staccata")
    CHECK(!s.snapshot().crtEnabled && s.snapshot().layout == "extrah", "reset: refill e layout sono impostazioni del monitor, restano")
}

fun testMotion() {
    val err = Err()
    val s = NewState()
    CHECK(!Cmd(s, "set_patient_motion", "jump", err), "movimento: livello non valido rifiutato")
    CHECK(!Cmd(s, "set_patient_motion", mapOf("level" to "light", "duration_s" to 500), err), "movimento: durata fuori range rifiutata")
    CHECK(Cmd(s, "set_patient_motion", "strong", err) && s.snapshot().patientMotion == 2, "movimento marcato continuo")
    CHECK(s.stateDictionary()["patient_motion"] == "strong", "stato JSON: patient_motion")
    CHECK(s.snapshot().lowPerfusion, "movimento marcato: SpO2 incerta ('?')")
    // ECG con artefatto: la differenza fra con e senza movimento deve essere ampia
    val e1 = WaveformEngine(7); val e2 = WaveformEngine(7)
    e1.updateWithSnapshot(s.snapshot())
    Cmd(s, "set_patient_motion", "off", null)
    e2.updateWithSnapshot(s.snapshot())
    var maxd = 0.0; for (i in 0 until 500) { val t = 10 + i * 0.01; maxd = Math.max(maxd, Math.abs(e1.ecgAt(t, SLLeadII) - e2.ecgAt(t, SLLeadII))) }
    CHECK(maxd > 0.5, "movimento: artefatto visibile sull'ECG")
    // a tempo: si ferma da solo
    Cmd(s, "set_patient_motion", mapOf("level" to "light", "duration_s" to 5), null)
    CHECK(s.snapshot().patientMotion == 1 && s.snapshot().patientMotionRemaining == 5, "movimento lieve per 5 s")
    gNow += 6; s.tickAtTime(gNow)
    CHECK(s.snapshot().patientMotion == 0, "movimento a tempo: finisce da solo")
    // NIBP: movimenti marcati durante la misura -> misura non riuscita
    s.startNibpMeasurement()
    Cmd(s, "set_patient_motion", mapOf("level" to "strong", "duration_s" to 3), null)
    gNow += 60; s.tickAtTime(gNow)
    CHECK(s.snapshot().nibpStatus == SLNibpError && s.snapshot().nibpError == "motion", "NIBP: movimento marcato = misura non riuscita")
    RunNibp(s)
    CHECK(s.snapshot().nibpStatus == SLNibpComplete, "NIBP: paziente fermo = misura riuscita")
    // senza polso il paziente non si muove: movimenti rifiutati e quelli in corso si fermano
    Cmd(s, "set_patient_motion", "strong", null)
    RhythmNow(s, "vf", null); gNow += 0.5; s.tickAtTime(gNow)
    CHECK(s.snapshot().patientMotion == 0, "movimento: si ferma quando il polso scompare")
    CHECK(!Cmd(s, "set_patient_motion", "light", err) && err.has("pulse"), "movimento: rifiutato in FV (senza polso)")
    CHECK(Cmd(s, "set_patient_motion", "off", err), "movimento: 'off' sempre accettato")
    CHECK(!Cmd(s, "patient_touch", 30, err) && Cmd(s, "patient_touch", null, err) && s.snapshot().operatorTouch, "tocco operatore: con qualsiasi ritmo (3 s)")
    val e3 = WaveformEngine(7); Sensor(s, "pads", true); e3.updateWithSnapshot(s.snapshot())
    gNow += 3.5; s.tickAtTime(gNow)
    CHECK(!s.snapshot().operatorTouch, "tocco operatore: finisce da solo")
    val e4 = WaveformEngine(7); e4.updateWithSnapshot(s.snapshot())
    maxd = 0.0; for (i in 0 until 300) { val t = 20 + i * 0.01; maxd = Math.max(maxd, Math.abs(e3.ecgAt(t, SLLeadPads) - e4.ecgAt(t, SLLeadPads))) }
    CHECK(maxd > 0.8, "tocco operatore: artefatto sulle piastre")
    Sensor(s, "pads", false)
    // DAE: il tocco interrompe l'analisi, che riparte da capo quando nessuno tocca il paziente
    Cmd(s, "defib_mode", "aed", null); Sensor(s, "pads", true)
    Tick(s, 7)      // ANDROID (25/11): prima l'invito «Analisi in corso. Non toccare il paziente», il movimento si controlla dopo
    Cmd(s, "patient_touch", 10, null); Tick(s, 1)
    var d = s.snapshot().defib
    CHECK(d.aedPhase == SLAedAnalyzing && d.message.contains("FERMARE IL MOVIMENTO") && HASSTR(d.voicePrompt, "Fermare il movimento"), "DAE: movimento = analisi interrotta, non toccare il paziente")
    val seq = d.promptSeq
    Tick(s, 7)
    CHECK(s.snapshot().defib.promptSeq > seq && s.snapshot().defib.aedPhase == SLAedAnalyzing, "DAE: il messaggio si ripete finché il movimento continua")
    Tick(s, 2.5)
    d = s.snapshot().defib
    CHECK(d.aedPhase == SLAedAnalyzing && HASSTR(d.voicePrompt, "Analisi del ritmo"), "DAE: paziente fermo = analisi riprende")
    Tick(s, 5)
    CHECK(s.snapshot().defib.aedPhase == SLAedAnalyzing, "DAE: l'analisi riparte da capo (non ancora finita)")
    Tick(s, 6)
    CHECK(s.snapshot().defib.aedPhase == SLAedCharging || s.snapshot().defib.aedPhase == SLAedReady, "DAE: analisi completata dopo il movimento")
    // ANDROID (A8): piastre con contatto scarso: il DAE non analizza e chiede di controllarle; sistemate, l'analisi riparte
    Sensor(s, "pads", false); RhythmNow(s, "vf", null); Sensor(s, "pads", true)
    CHECK(!Cmd(s, "set_pads_contact", "boh", null), "contatto piastre: valore non valido rifiutato")
    Cmd(s, "set_pads_contact", "poor", null); Tick(s, 1)
    d = s.snapshot().defib
    CHECK(d.aedPhase == SLAedAnalyzing && d.message.contains("CONTROLLARE LE PIASTRE") && HASSTR(d.voicePrompt, "Controllare le piastre"), "DAE: contatto scarso = controllare le piastre")
    Tick(s, 12)
    CHECK(s.snapshot().defib.aedPhase == SLAedAnalyzing, "DAE: con contatto scarso l'analisi non finisce mai")
    Cmd(s, "set_pads_contact", "good", null); Tick(s, 11)
    CHECK(s.snapshot().defib.aedPhase == SLAedCharging || s.snapshot().defib.aedPhase == SLAedReady, "DAE: contatto ripristinato = analisi completata")
    Cmd(s, "set_pads_contact", "poor", null); Sensor(s, "pads", false); Sensor(s, "pads", true)
    CHECK(!s.snapshot().defib.message.contains("CONTROLLARE LE PIASTRE"), "piastre riapplicate = contatto di nuovo buono")
    // ANDROID (21/11): RCP ripresa durante la carica = carica annullata, «Movimento rilevato», nuova analisi a RCP ferma
    s.resetScenario()
    Cmd(s, "defib_mode", "aed", null); RhythmNow(s, "vf", null); Sensor(s, "pads", true)
    Tick(s, 11)
    CHECK(s.snapshot().defib.aedPhase == SLAedCharging || s.snapshot().defib.aedPhase == SLAedReady, "DAE: FV → carica")
    Cmd(s, "set_cpr", true, null); Tick(s, 0.2)
    d = s.snapshot().defib
    CHECK(d.aedPhase == SLAedAnalyzing && d.charge == SLChargeNone && d.message.contains("MOVIMENTO") && HASSTR(d.voicePrompt, "Movimento rilevato"), "RCP durante la carica: carica annullata, movimento rilevato")
    Cmd(s, "set_cpr", false, null); Tick(s, 12)
    CHECK(s.snapshot().defib.aedPhase == SLAedCharging || s.snapshot().defib.aedPhase == SLAedReady, "RCP ferma: nuova analisi e nuova carica")
    // tocco del paziente durante la carica: stessa interruzione; in manuale invece nessun effetto
    Tick(s, 0.5)
    if (s.snapshot().defib.aedPhase == SLAedCharging) {
        Cmd(s, "patient_touch", 3, null); Tick(s, 0.2)
        CHECK(s.snapshot().defib.aedPhase == SLAedAnalyzing && s.snapshot().defib.charge == SLChargeNone && HASSTR(s.snapshot().defib.voicePrompt, "Fermare il movimento"), "tocco durante la carica: carica annullata")
    }
    s.resetScenario()
    Cmd(s, "defib_mode", "manual", null); Sensor(s, "pads", true); RhythmNow(s, "vf", null)
    Cmd(s, "defib_charge", null, null); Tick(s, 0.5); Cmd(s, "patient_touch", 3, null); Tick(s, 0.5)
    CHECK(s.snapshot().defib.charge != SLChargeNone && !s.snapshot().defib.message.contains("MOVIMENTO"), "manuale: il tocco durante la carica non cambia nulla")
    // ANDROID (21/11): qualità della RCP scelta dall'istruttore → feedback del DAE durante la RCP
    s.resetScenario()
    Cmd(s, "defib_mode", "aed", null); RhythmNow(s, "asystole", null); Sensor(s, "pads", true)
    Tick(s, 12)
    CHECK(s.snapshot().defib.aedPhase == SLAedCPR, "DAE: asistolia → RCP")
    CHECK(!Cmd(s, "set_cpr_quality", mapOf("rate" to "boh"), null), "qualità RCP: valore non valido rifiutato")
    Cmd(s, "set_cpr", true, null); Cmd(s, "set_cpr_quality", mapOf("depth" to "shallow"), null); Tick(s, 1)
    d = s.snapshot().defib
    CHECK(d.message == "SPINGERE PIÙ FORTE" && HASSTR(d.voicePrompt, "più forte") && s.snapshot().cprDepthQuality == "shallow", "RCP superficiale: spingere più forte")
    Cmd(s, "set_cpr_quality", mapOf("depth" to "ok", "rate" to "fast"), null); Tick(s, 1)
    CHECK(s.snapshot().defib.message == "SPINGERE PIÙ LENTAMENTE", "RCP veloce: più lentamente")
    Cmd(s, "set_cpr_quality", mapOf("rate" to "ok"), null); Tick(s, 1)
    CHECK(s.snapshot().defib.message == "RCP IN CORSO" && HASSTR(s.snapshot().defib.voicePrompt, "efficaci"), "RCP corretta: compressioni efficaci")
    s.resetScenario()
    CHECK(s.snapshot().patientMotion == 0, "reset: paziente fermo")
    CHECK(s.snapshot().cprRateQuality == "ok" && s.snapshot().cprDepthQuality == "ok", "reset: RCP di nuovo corretta")
}

fun testSites() {
    val s = NewStateRaw(); val err = Err()
    CHECK(Cmd(s, "set_sensor", mapOf("sensor" to "nibp", "connected" to true, "site" to "upper_arm_r"), err), "set_sensor con posizione")
    CHECK(s.snapshot().sensorSites["nibp"] == "upper_arm_r", "posizione del bracciale salvata")
    CHECK(!Cmd(s, "set_sensor", mapOf("sensor" to "spo2", "connected" to true, "site" to "Dito Destro!"), err), "posizione non valida rifiutata")
    CHECK(Cmd(s, "set_sensor", mapOf("sensor" to "spo2", "connected" to true, "site" to "finger_r2"), err), "SpO2 al dito destro")
    CHECK(s.snapshot().spo2SignalValid && !s.snapshot().spo2CuffOccluded, "bracciale fermo: SpO2 regolare")
    s.startNibpMeasurement()
    val sn = s.snapshot()
    CHECK(sn.spo2CuffOccluded && !sn.spo2SignalValid && sn.spo2SignalSetting, "NIBP sullo stesso braccio: SpO2 occlusa durante la misura")
    val j = s.stateDictionary()
    CHECK(BOOL(j["spo2_cuff_occluded"]) && BOOL(j["spo2_signal_valid"]) && j["sensor_sites"]["spo2"] == "finger_r2", "JSON: posizioni e occlusione")
    Cmd(s, "set_sensor", mapOf("sensor" to "spo2", "connected" to false), null)
    Cmd(s, "set_sensor", mapOf("sensor" to "spo2", "connected" to true, "site" to "finger_l4"), null)
    CHECK(!s.snapshot().spo2CuffOccluded, "SpO2 sul braccio opposto: nessuna occlusione")
    Cmd(s, "set_sensor", mapOf("sensor" to "nibp", "connected" to false), null)
    CHECK(s.snapshot().sensorSites["nibp"] == null, "scollegato: posizione rimossa")
    s.resetScenario()
    CHECK(s.snapshot().sensorSites.size == 0, "reset: nessuna posizione")
    // senza lato indicato (Pocket, stati, scenari) vale quello di serie che mostrano le regie: bracciale a destra
    Cmd(s, "set_sensor", mapOf("sensor" to "nibp", "connected" to true), null)
    Cmd(s, "set_sensor", mapOf("sensor" to "spo2", "connected" to true, "site" to "finger_r2"), null)
    CHECK(s.snapshot().sensorSites["nibp"] == "upper_arm_r" && s.stateDictionary()["sensor_sites"]["nibp"] == "upper_arm_r",
          "bracciale senza lato: braccio destro di serie, anche nel JSON")
    s.startNibpMeasurement()
    CHECK(s.snapshot().spo2CuffOccluded, "bracciale di serie (dx) e SpO2 al dito destro: occlusa durante la misura")
    // linea arteriosa radiale sullo stesso braccio: curva piatta durante la misura, poi torna
    Cmd(s, "set_sensor", mapOf("sensor" to "art", "connected" to true, "site" to "radial_r"), null)
    val a1 = s.snapshot()
    CHECK(a1.artCuffOccluded && a1.artDisplayedSystolic == 26 && a1.artDisplayedDiastolic == 24 && BOOL(s.stateDictionary()["art_cuff_occluded"]),
          "ART radiale destra con bracciale destro in misura: smorzata (26/24)")
    Tick(s, 60)
    val a2 = s.snapshot()
    CHECK(!a2.artCuffOccluded && a2.artDisplayedSystolic == a2.systolic, "misura finita: la curva arteriosa torna pulsatile")
    Cmd(s, "set_sensor", mapOf("sensor" to "art", "connected" to false), null)
    Cmd(s, "set_sensor", mapOf("sensor" to "art", "connected" to true), null)
    s.startNibpMeasurement()
    CHECK(s.snapshot().sensorSites["art"] == "radial_l" && !s.snapshot().artCuffOccluded, "ART di serie a sinistra, bracciale a destra: nessun effetto")
    Cmd(s, "set_sensor", mapOf("sensor" to "art", "connected" to false), null)
    Cmd(s, "set_sensor", mapOf("sensor" to "art", "connected" to true, "site" to "femoral_r"), null)
    CHECK(!s.snapshot().artCuffOccluded, "ART femorale: il bracciale non la influenza")
}

fun testCrtAssess() {
    val s = NewStateRaw()
    val q0 = s.snapshot().crtAssessSeq
    Cmd(s, "set_crt_enabled", false, null)
    CHECK(Cmd(s, "assess_crt", null, null) && s.snapshot().crtAssessSeq == q0 + 1 && s.snapshot().crtEnabled, "refill: l'istruttore fa valutare (e il riquadro torna visibile)")
    CHECK(INT(s.stateDictionary()["crt_assess_seq"]) == q0 + 1, "refill: contatore nello stato")
}

fun testCuffError() {
    val s = NewStateRaw()
    s.startNibpMeasurement(); Tick(s, 40)
    CHECK(s.snapshot().nibpStatus == SLNibpError && s.snapshot().nibpError == "cuff", "NIBP senza bracciale: errore perdita")
    Cmd(s, "set_sensor", mapOf("sensor" to "nibp", "connected" to true), null)
    CHECK(s.snapshot().nibpStatus == SLNibpIdle && s.snapshot().nibpError == null, "bracciale collegato: errore cancellato")
}


fun testAnalisi2111() {
    val err = Err()
    // --- 1. PAUSA: il paziente e i dispositivi si fermano davvero, alla ripresa ripartono da dove erano ---
    var s = NewState()
    Cmd(s, "transition", mapOf("params" to mapOf("heart_rate_bpm" to 142), "duration_s" to 60), null)
    Tick(s, 30)
    val hrMid = s.snapshot().heartRate
    CHECK(hrMid in 110..114) { F("pausa: rampa a metà (FC %d)", hrMid) }
    Cmd(s, "set_simulation_running", false, null); Tick(s, 120)
    CHECK(s.snapshot().heartRate == hrMid && INT(s.stateDictionary()["transitions"]["heart_rate_bpm"]["remaining_s"]) == 30,
          "pausa: la rampa non avanza e il tempo rimanente resta fermo")
    Cmd(s, "set_simulation_running", true, null); Tick(s, 15)
    CHECK(s.snapshot().heartRate in 125..129) { F("ripresa: la rampa riparte da dove era (FC %d)", s.snapshot().heartRate) }
    Tick(s, 16)
    CHECK(s.snapshot().heartRate == 142, "ripresa: rampa completata dopo il tempo rimanente")
    // catena (fasi dello scenario)
    Cmd(s, "run_chain", mapOf("name" to "t", "steps" to listOf(mapOf("wait_s" to 20, "params" to mapOf("heart_rate_bpm" to 50)))), null)
    Tick(s, 10)
    Cmd(s, "set_simulation_running", false, null); Tick(s, 60)
    CHECK(s.snapshot().heartRate == 142 && INT(s.stateDictionary()["chain"]["remaining_s"]) == 10, "pausa: la fase dello scenario non scatta")
    Cmd(s, "set_simulation_running", true, null); Tick(s, 9)
    CHECK(s.snapshot().heartRate == 142, "ripresa: la fase aspetta ancora il suo tempo")
    Tick(s, 2)
    CHECK(s.snapshot().heartRate == 50, "ripresa: la fase scatta dopo il tempo rimanente")
    // fisiologia automatica: arresto dato in pausa, la desaturazione parte solo alla ripresa
    Cmd(s, "set_simulation_running", false, null)
    RhythmNow(s, "vf", null); Tick(s, 60)
    CHECK(s.snapshot().spo2 == 98, "pausa: arresto dato in pausa, la SpO2 non cala")
    Cmd(s, "set_simulation_running", true, null); Tick(s, 20)
    CHECK(s.snapshot().spo2 in 45..52) { F("ripresa: la desaturazione parte ora (SpO2 %d)", s.snapshot().spo2) }
    // apnea con polso
    s = NewState(); Cmd(s, "set_resp_rate", 0, null); Tick(s, 30)
    val spoA = s.snapshot().spo2
    Cmd(s, "set_simulation_running", false, null); Tick(s, 200)
    CHECK(spoA < 98 && s.snapshot().spo2 == spoA, "pausa: l'apnea non desatura")
    Cmd(s, "set_simulation_running", true, null); Tick(s, 10)
    CHECK(s.snapshot().spo2 < spoA && s.snapshot().spo2 >= spoA - 4) { F("ripresa: l'apnea continua da dove era (%d -> %d)", spoA, s.snapshot().spo2) }
    // NIBP in misura e NIBP automatica
    s = NewState(); s.startNibpMeasurement(); Tick(s, 5)
    Cmd(s, "set_simulation_running", false, null); Tick(s, 100)
    var sn = s.snapshot()
    CHECK(sn.nibpStatus == SLNibpMeasuring && Math.abs((gNow - sn.nibpStartedAt) - 5.0) < 0.6, "pausa: la misura NIBP resta ferma (anche per la UI)")
    Cmd(s, "set_simulation_running", true, null); Tick(s, 60)
    CHECK(s.snapshot().nibpStatus == SLNibpComplete, "ripresa: la misura NIBP si completa")
    Cmd(s, "set_nibp_interval", 1, null); Tick(s, 30)
    Cmd(s, "set_simulation_running", false, null); Tick(s, 120)
    CHECK(s.snapshot().nibpStatus == SLNibpComplete && INT(s.stateDictionary()["nibp_next_in_s"]) == 30, "pausa: la NIBP automatica non parte, il conto alla rovescia è fermo")
    Cmd(s, "set_simulation_running", true, null); Tick(s, 31)
    CHECK(s.snapshot().nibpStatus == SLNibpMeasuring, "ripresa: la NIBP automatica parte dopo il tempo rimanente")
    // silenziamento degli allarmi: non è tempo clinico, scade anche in pausa
    Cmd(s, "mute_alarms", 10, null); Cmd(s, "set_simulation_running", false, null); Tick(s, 11)
    CHECK(!s.snapshot().alarmsMuted, "pausa: il silenziamento degli allarmi scade comunque")
    Cmd(s, "set_simulation_running", true, null)
    // DAE: analisi, carica, pronto (timeout), RCP 2 min
    s = NewState(); RhythmNow(s, "vf", null); Cmd(s, "defib_mode", "aed", null); Sensor(s, "pads", true)
    Tick(s, 4)
    Cmd(s, "set_simulation_running", false, null); Tick(s, 30)
    var d = s.snapshot().defib
    CHECK(d.aedPhase == SLAedAnalyzing && d.aedRemaining == 4, "pausa: l'analisi del DAE è ferma")
    Cmd(s, "set_simulation_running", true, null); Tick(s, 4.5)
    CHECK(s.snapshot().defib.aedPhase == SLAedCharging, "ripresa: l'analisi finisce, carica")
    Tick(s, 1)
    Cmd(s, "set_simulation_running", false, null)
    val prog = s.snapshot().defib.chargeProgress
    Tick(s, 60)
    CHECK(s.snapshot().defib.charge == SLChargeCharging && Math.abs(s.snapshot().defib.chargeProgress - prog) < 1e-9, "pausa: la carica non avanza")
    Cmd(s, "set_simulation_running", true, null); Tick(s, 5)
    CHECK(s.snapshot().defib.charge == SLChargeReady, "ripresa: carica completata")
    Tick(s, 30)
    Cmd(s, "set_simulation_running", false, null); Tick(s, 120)
    CHECK(s.snapshot().defib.charge == SLChargeReady && s.snapshot().defib.aedPhase == SLAedReady, "pausa: lo shock pronto non scade")
    Cmd(s, "set_simulation_running", true, null); Tick(s, 25)
    CHECK(s.snapshot().defib.charge == SLChargeReady, "ripresa: il timeout conta solo il tempo in corsa")
    Cmd(s, "defib_shock", null, null); Tick(s, 60)
    Cmd(s, "set_simulation_running", false, null); Tick(s, 200)
    d = s.snapshot().defib
    CHECK(d.aedPhase == SLAedCPR && d.aedRemaining == 60) { F("pausa: il ciclo di RCP di 2 min è fermo (fase %d, %d s)", d.aedPhase, d.aedRemaining) }
    Cmd(s, "set_simulation_running", true, null); Tick(s, 61)
    CHECK(s.snapshot().defib.aedPhase == SLAedAnalyzing, "ripresa: a fine RCP nuova analisi")
    // manuale: carica ferma in pausa
    s = NewState(); Cmd(s, "defib_mode", "manual", null); Sensor(s, "pads", true)
    Cmd(s, "set_simulation_running", false, null); Cmd(s, "defib_charge", null, null); Tick(s, 30)
    CHECK(s.snapshot().defib.charge == SLChargeCharging && s.snapshot().defib.chargeProgress == 0.0, "pausa: carica manuale avviata in pausa non avanza")
    Cmd(s, "set_simulation_running", true, null); Tick(s, 5)
    CHECK(s.snapshot().defib.charge == SLChargeReady, "ripresa: carica manuale completata")
    // reset in pausa: riparte in corsa e il tempo clinico riparte
    Cmd(s, "set_simulation_running", false, null); Reset(s)
    CHECK(s.snapshot().simulationRunning, "reset: simulazione in corsa")

    // --- 2. Disarmo in modalità DAE: mai bloccato su «Premere SHOCK» ---
    s = NewState(); RhythmNow(s, "vf", null); Cmd(s, "defib_mode", "aed", null); Sensor(s, "pads", true)
    Tick(s, 9); Tick(s, 5)
    CHECK(s.snapshot().defib.aedPhase == SLAedReady, "DAE: pronto")
    Cmd(s, "defib_disarm", null, null)
    d = s.snapshot().defib
    CHECK(d.charge == SLChargeNone && d.aedPhase == SLAedCPR && !d.message.contains("PREMERE SHOCK") && HASSTR(d.voicePrompt, "RCP"),
          "DAE: disarmo da pronto -> shock non erogato, RCP")
    Tick(s, 121)
    CHECK(s.snapshot().defib.aedPhase == SLAedAnalyzing, "DAE: dopo il disarmo il ciclo continua (nuova analisi)")
    Tick(s, 9)
    CHECK(s.snapshot().defib.aedPhase == SLAedCharging, "DAE: in carica")
    Cmd(s, "defib_disarm", null, null)
    CHECK(s.snapshot().defib.charge == SLChargeNone && s.snapshot().defib.aedPhase == SLAedCPR, "DAE: disarmo durante la carica -> RCP")
    Cmd(s, "defib_analyze", null, null)
    CHECK(s.snapshot().defib.aedPhase == SLAedAnalyzing, "DAE: dopo il disarmo ANALIZZA funziona")
    // regola dell'utente invariata: movimento in carica -> «Movimento rilevato» e nuova analisi (non RCP)
    Tick(s, 9); Cmd(s, "patient_touch", 2, null); Tick(s, 0.5)
    CHECK(s.snapshot().defib.aedPhase == SLAedAnalyzing && s.snapshot().defib.charge == SLChargeNone, "DAE: tocco in carica -> nuova analisi (regola invariata)")

    // --- 3. Ritmo che diventa non defibrillabile durante la carica / con lo shock pronto ---
    s = NewState(); RhythmNow(s, "vf", null); Cmd(s, "defib_mode", "aed", null); Sensor(s, "pads", true)
    Tick(s, 9)
    CHECK(s.snapshot().defib.aedPhase == SLAedCharging, "DAE: carica su FV")
    RhythmNow(s, "sinus", null); Tick(s, 0.5)
    d = s.snapshot().defib
    CHECK(d.charge == SLChargeNone && d.aedPhase == SLAedCPR && HASSTR(d.voicePrompt, "Ritmo cambiato") && HASSTR(d.voicePrompt, "annullato"),
          "DAE: ROSC durante la carica -> scarica annullata, RCP")
    RhythmNow(s, "vf", null); Cmd(s, "defib_analyze", null, null); Tick(s, 9); Tick(s, 5)
    CHECK(s.snapshot().defib.aedPhase == SLAedReady, "DAE: pronto su FV")
    RhythmNow(s, "asystole", null)
    CHECK(!Cmd(s, "defib_shock", null, err) && s.snapshot().defib.shockCount == 0 && s.snapshot().defib.aedPhase == SLAedCPR,
          "DAE: asistolia con shock pronto -> SHOCK rifiutato, scarica annullata, RCP")
    RhythmNow(s, "vf", null); Cmd(s, "defib_analyze", null, null); Tick(s, 9); Tick(s, 5)
    RhythmNow(s, "pea", null); Tick(s, 0.5)
    CHECK(s.snapshot().defib.charge == SLChargeNone && s.snapshot().defib.aedPhase == SLAedCPR, "DAE: PEA con shock pronto -> annullata")
    // TV con polso >= 150 consigliata dal DAE: esito come per FV/TV senza polso, non R su T
    Cmd(s, "set_defib_config", mapOf("outcome_mode" to "auto"), null)
    RhythmNow(s, "vt_pulse", null); Cmd(s, "set_heart_rate", 180, null); Cmd(s, "defib_analyze", null, null); Tick(s, 9); Tick(s, 5)
    CHECK(Cmd(s, "defib_shock", null, err) && PS(s) && s.snapshot().rhythm == "sinus", "DAE: TV con polso 180 scaricata dal DAE -> ritmo post-shock (non FV)")
    Cmd(s, "set_defib_config", mapOf("outcome_mode" to "instructor"), null)
    RhythmNow(s, "vt_pulse", null); Cmd(s, "set_heart_rate", 180, null); Cmd(s, "defib_analyze", null, null); Tick(s, 9); Tick(s, 5)
    Cmd(s, "defib_shock", null, null)
    var dd = s.stateDictionary()["defib"]
    CHECK(s.snapshot().rhythm == "vt_pulse" && BOOL(dd["outcome_pending"]), "DAE: TV con polso, decide l'istruttore (non FV automatica)")
    Cmd(s, "defib_outcome", "stay", null)
    // la regola R su T resta per la scarica MANUALE non sincronizzata
    Cmd(s, "defib_mode", "manual", null); RhythmNow(s, "vt_pulse", null)
    Cmd(s, "defib_charge", null, null); Tick(s, 5); Cmd(s, "defib_shock", null, null)
    CHECK(s.snapshot().rhythm == "vf", "manuale: TV con polso + scarica non sincronizzata -> FV (R su T invariata)")

    // --- 4. Cambio di layout verso un DAE a dispositivo acceso: il DAE parte spento, nessuna analisi ---
    s = NewState(); Sensor(s, "pads", true); RhythmNow(s, "vf", null)
    s.devicePowered = true; Cmd(s, "defib_mode", "manual", null)
    Cmd(s, "set_layout", "dae", null); Tick(s, 10)
    d = s.snapshot().defib
    CHECK(!s.devicePowered && d.mode == SLDefibOff && d.charge == SLChargeNone && d.shockCount == 0, "layout DAE: dispositivo spento, nessuna terapia, nessuna analisi")

    // --- 5. Energia: al cambio di layout e al reset torna al valore di serie ---
    s = NewState()
    Cmd(s, "set_layout", "extrah", null); Cmd(s, "defib_mode", "aed", null)
    CHECK(s.snapshot().defib.energy == 150, "energia DAE: extraH 150 J di serie")
    Cmd(s, "set_defib_config", mapOf("aed_energy_j" to 360), null); Reset(s); Cmd(s, "defib_mode", "aed", null)
    CHECK(s.snapshot().defib.energy == 150, "reset: energia DAE di serie del layout (extraH 150 J)")

}
