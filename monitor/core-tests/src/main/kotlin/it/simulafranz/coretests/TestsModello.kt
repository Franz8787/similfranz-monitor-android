// Portato da tests/run-tests.m (monitor iPad)
// testState, testTransitions, testWaveforms, testAlarms
package it.simulafranz.coretests

import it.simulafranz.core.*

fun testState() {
    val s = NewState()
    var sn = s.snapshot()
    CHECK(sn.rhythm == "sinus" && sn.heartRate == 82 && sn.spo2 == 98, "valori iniziali")
    val raw = NewStateRaw().snapshot()
    CHECK(!raw.ecgConnected && !raw.spo2Connected && !raw.nibpConnected && !raw.etco2Connected && !raw.artConnected,
          "all'avvio tutti i sensori sono scollegati")
    val err = Err()
    CHECK(Cmd(s, "set_heart_rate", 110, err) && s.snapshot().heartRate == 110, "set_heart_rate 110")
    CHECK(!Cmd(s, "set_heart_rate", 400, err) && err.has("range"), "FC fuori range rifiutata")
    CHECK(!Cmd(s, "set_heart_rate", "abc", err), "FC stringa rifiutata")
    CHECK(!Cmd(s, "set_heart_rate", true, err), "FC booleana rifiutata")
    CHECK(!RhythmNow(s, "torsade", err), "ritmo non supportato rifiutato")
    CHECK(!Cmd(s, "boom", null, err) && err.has("unknown"), "comando sconosciuto")
    CHECK(!Ok(s.applyCommand(mapOf("command" to "set_spo2", "value" to 90, "device_id" to "altro")), err), "device_id errato")
    CHECK(!Cmd(s, "set_bp", mapOf("systolic" to 80, "diastolic" to 90), err), "diastolica >= sistolica rifiutata")
    CHECK(Cmd(s, "set_bp", mapOf("systolic" to 95, "diastolic" to 60), err), "set_bp valido")
    CHECK(Cmd(s, "set_nibp", mapOf("systolic" to 95, "diastolic" to 60), err), "set_nibp (alias compatibilità)")
    CHECK(!Cmd(s, "set_sensor", mapOf("sensor" to "eeg", "connected" to true), err), "sensore sconosciuto rifiutato")
    CHECK(!Cmd(s, "set_sensor", mapOf("sensor" to "ecg", "connected" to 1), err), "set_sensor richiede booleano")
    CHECK(!Cmd(s, "set_volume", 150, err) && Cmd(s, "set_volume", 40, err) && s.snapshot().volume == 40, "volume 0..100")
    CHECK(Cmd(s, "set_qrs_beep", false, err) && !s.snapshot().qrsBeepEnabled, "bip QRS disattivabile")

    val rev = s.snapshot().revision
    Cmd(s, "set_spo2", 91, null)
    CHECK(s.snapshot().revision == rev + 1, "revision incrementale")

    RhythmNow(s, "vf", null)
    sn = s.snapshot()
    CHECK(!sn.pulsePresent && !sn.spo2Displayable && sn.displayedHeartRate == -1, "VF senza polso")
    CHECK(ISNULL(s.stateDictionary(), "spo2_displayed"), "VF: spo2_displayed null")
    RhythmNow(s, "asystole", null)
    CHECK(s.snapshot().displayedHeartRate == 0, "asistolia FC 0")
    RhythmNow(s, "pea", null)
    sn = s.snapshot()
    CHECK(!sn.pulsePresent && sn.displayedHeartRate > 0, "PEA: attività elettrica senza polso")
    RhythmNow(s, "sinus_tachycardia", null)
    CHECK(s.snapshot().heartRate > 100 && s.snapshot().pulsePresent, "preset tachicardia sinusale")

    // Sensori
    Sensor(s, "spo2", false)
    CHECK(!s.snapshot().spo2Displayable && s.snapshot().spo2 == 91, "sonda staccata: valore non mostrato")
    Sensor(s, "spo2", true)
    Sensor(s, "ecg", false)
    CHECK(s.snapshot().displayedHeartRate == -1, "elettrodi staccati: FC ECG '---'")
    Sensor(s, "ecg", true)
    CHECK(s.snapshot().artDisplayedSystolic == -1, "ART non collegata: '--'")
    Sensor(s, "art", true)
    Cmd(s, "set_bp", mapOf("systolic" to 130, "diastolic" to 70), null)
    CHECK(s.snapshot().artDisplayedSystolic == 130 && s.snapshot().artDisplayedDiastolic == 70, "ART mostra la PA del paziente")
    RhythmNow(s, "vf", null)
    // ANDROID (ART RCP 22/11): la pressione crolla in pochi secondi verso il riempimento sistemico (~15), non più 20 fisso
    gNow += 15; s.tickAtTime(gNow)
    CHECK(s.snapshot().artDisplayedSystolic in 12..20 && s.snapshot().artDisplayedDiastolic in 10..18) { F("ART in arresto ~15 mmHg (%d/%d)", s.snapshot().artDisplayedSystolic, s.snapshot().artDisplayedDiastolic) }
    RhythmNow(s, "sinus", null)

    // NIBP: profilo realistico (gonfiaggio -> gradini -> scarico)
    Cmd(s, "set_bp", mapOf("systolic" to 120, "diastolic" to 80), null)
    CHECK(Ok(s.startNibpMeasurement(), null), "avvio NIBP")
    CHECK(!Ok(s.startNibpMeasurement(), err), "NIBP già in corso rifiutato")
    var p = 0.0; var step = -1
    val m = s.snapshot()
    var pt = m.nibpPhaseAt(gNow + 2); p = pt.pressure; step = pt.step
    CHECK(pt.phase == SLNibpPhaseInflating && p > 30) { F("fase gonfiaggio (%.0f mmHg)", p) }
    var pk = 0.0; pt = m.nibpPhaseAt(gNow + 5.74); pk = pt.pressure; step = pt.step
    CHECK(pk > 140) { F("picco di gonfiaggio sopra la sistolica (%.0f mmHg)", pk) }
    pt = m.nibpPhaseAt(gNow + 9); p = pt.pressure; step = pt.step
    CHECK(pt.phase == SLNibpPhaseDeflating && step >= 0 && p < pk, "sgonfiaggio a gradini")
    gNow += 3; s.tickAtTime(gNow)
    CHECK(s.snapshot().nibpStatus == SLNibpMeasuring, "NIBP in misura dopo 3 s")
    gNow += 60; s.tickAtTime(gNow)
    sn = s.snapshot()
    CHECK(sn.nibpStatus == SLNibpComplete && Math.abs(sn.nibpLastSystolic - 120) <= 4 && Math.abs(sn.nibpLastDiastolic - 80) <= 3)
        { F("NIBP completata ~120/80 (%d/%d, scarto realistico)", sn.nibpLastSystolic, sn.nibpLastDiastolic) }
    CHECK(Math.abs(sn.meanArterialPressure - 93) <= 4) { F("PAM ~93 (%d)", sn.meanArterialPressure) }
    RhythmNow(s, "vf", null); RunNibp(s)
    CHECK(s.snapshot().nibpStatus == SLNibpError && s.snapshot().nibpError == "no_pulse", "NIBP in VF: nessuna oscillazione")
    RhythmNow(s, "sinus", null); Sensor(s, "nibp", false); RunNibp(s)
    CHECK(s.snapshot().nibpError == "cuff", "NIBP con bracciale staccato: errore bracciale")
    Sensor(s, "nibp", true)
    s.startNibpMeasurement(); Sensor(s, "nibp", false)
    CHECK(s.snapshot().nibpStatus == SLNibpError, "bracciale staccato durante la misura: interrotta")
    Sensor(s, "nibp", true)

    // Bassa perfusione: SpO2 con "?" e allarme tecnico
    Cmd(s, "set_bp", mapOf("systolic" to 62, "diastolic" to 38), null)
    CHECK(s.snapshot().lowPerfusion && BOOL(s.stateDictionary()["spo2_low_perfusion"]), "ipotensione marcata: SpO2 con ?")
    CHECK(AlarmManager.evaluate(s.snapshot()).messages.joinToString(",").contains("BASSA PERFUSIONE"), "allarme bassa perfusione")
    Cmd(s, "set_bp", mapOf("systolic" to 120, "diastolic" to 80), null); Tick(s, 25)   // ANDROID (perfusione 22/11): la perfusione segue la PAM in qualche secondo
    CHECK(!s.snapshot().lowPerfusion, "PA normale: niente ?")

    // Allarme disattivato per parametro (i ritmi letali suonano comunque)
    Cmd(s, "set_alarm_limits", mapOf("hr_high" to 80), null); Cmd(s, "set_heart_rate", 100, null)
    Tick(s, 6)   // ANDROID (numerici realistici 22/11): gli allarmi guardano il numero a schermo (misurato con qualche secondo)
    CHECK(INT(AlarmManager.evaluate(s.snapshot()).severity["hr"]) > 0, "FC 100 > 80: allarme")
    CHECK(Cmd(s, "set_alarm_enabled", mapOf("param" to "hr", "enabled" to false), err), "disattiva allarme FC")
    CHECK(INT(AlarmManager.evaluate(s.snapshot()).severity["hr"]) == 0, "allarme FC disattivato: niente allarme")
    RhythmNow(s, "vf", null)
    CHECK(AlarmManager.evaluate(s.snapshot()).level == SLAlarmHigh, "FV suona anche con allarme FC disattivato")
    RhythmNow(s, "sinus", null); Cmd(s, "set_alarm_enabled", mapOf("param" to "hr", "enabled" to true), null)
    Cmd(s, "reset_alarm_limits", null, null); Cmd(s, "set_heart_rate", 82, null); Cmd(s, "set_bp", mapOf("systolic" to 120, "diastolic" to 80), null)
    CHECK(!Cmd(s, "set_alarm_enabled", mapOf("param" to "xx", "enabled" to false), err), "parametro sconosciuto rifiutato")

    // NIBP automatica ogni 5 minuti
    CHECK(!Cmd(s, "set_nibp_interval", 7, err), "intervallo non previsto rifiutato")
    CHECK(Cmd(s, "set_nibp_interval", 5, err) && INT(s.stateDictionary()["nibp_next_in_s"]) == 300, "NIBP auto: prossima tra 300 s")
    gNow += 299; s.tickAtTime(gNow)
    CHECK(s.snapshot().nibpStatus != SLNibpMeasuring, "non ancora partita a 299 s")
    gNow += 2; s.tickAtTime(gNow)
    CHECK(s.snapshot().nibpStatus == SLNibpMeasuring, "misura automatica partita allo scadere")
    gNow += 60; s.tickAtTime(gNow)
    CHECK(s.snapshot().nibpStatus == SLNibpComplete && INT(s.stateDictionary()["nibp_next_in_s"]) > 200, "misura completata, ciclo successivo programmato")
    Cmd(s, "set_nibp_interval", 0, null)
    CHECK(ISNULL(s.stateDictionary(), "nibp_next_in_s"), "NIBP auto spenta")

    // Silenziamento con scadenza
    Cmd(s, "mute_alarms", true, null)
    CHECK(s.snapshot().alarmsMuted && s.snapshot().alarmsMutedRemaining == 20, "allarmi silenziati per 20 s")
    gNow += 12; s.tickAtTime(gNow)
    CHECK(s.snapshot().alarmsMuted && s.snapshot().alarmsMutedRemaining == 8, "countdown: 8 s rimanenti")
    gNow += 9; s.tickAtTime(gNow)
    CHECK(!s.snapshot().alarmsMuted, "dopo 20 s gli allarmi si riattivano da soli")

    // Limiti di allarme
    CHECK(Cmd(s, "set_alarm_limits", mapOf("hr_high" to 100, "spo2_low" to 94), err) &&
          s.snapshot().limit("hr_high") == 100 && s.snapshot().limit("hr_low") == 50, "limiti parziali applicati")
    CHECK(!Cmd(s, "set_alarm_limits", mapOf("hr_low" to 110), err) && err.has("lower"), "limite min >= max rifiutato")
    CHECK(!Cmd(s, "set_alarm_limits", mapOf("foo" to 1), err), "limite sconosciuto rifiutato")
    CHECK(!Cmd(s, "set_alarm_limits", mapOf("spo2_low" to 20), err), "limite fuori range rifiutato")
    Reset(s)
    CHECK(s.snapshot().rhythm == "sinus" && s.snapshot().heartRate == 82, "reset_scenario")
    CHECK(s.snapshot().limit("hr_high") == 100, "i limiti NON si azzerano con il reset dello scenario")
    Cmd(s, "reset_alarm_limits", null, null)
    CHECK(s.snapshot().limit("hr_high") == 120, "reset_alarm_limits")
}


fun testTransitions() {
    val s = NewState(); val err = Err()
    // Rampa lineare FC 82 -> 142 in 60 s
    CHECK(Cmd(s, "transition", mapOf("params" to mapOf("heart_rate_bpm" to 142), "duration_s" to 60), err), "transizione accettata")
    gNow += 30; s.tickAtTime(gNow)
    var hr = s.snapshot().heartRate
    CHECK(hr >= 110 && hr <= 114) { F("a metà rampa FC ~112 (%d)", hr) }
    CHECK(INT(s.stateDictionary()["transitions"]["heart_rate_bpm"]["remaining_s"]) == 30, "tempo residuo esposto")
    gNow += 31; s.tickAtTime(gNow)
    CHECK(s.snapshot().heartRate == 142 && COUNT(s.stateDictionary()["transitions"]) == 0, "rampa completata")
    // Comando manuale annulla la rampa di quel parametro
    Cmd(s, "transition", mapOf("params" to mapOf("spo2_percent" to 80), "duration_s" to 100), null)
    gNow += 10; s.tickAtTime(gNow)
    Cmd(s, "set_spo2", 95, null); gNow += 50; s.tickAtTime(gNow)
    CHECK(s.snapshot().spo2 == 95, "set manuale interrompe la rampa")
    // Interruzione di un solo parametro: resta al valore raggiunto, l'altro continua
    Cmd(s, "transition", mapOf("params" to mapOf("heart_rate_bpm" to 100, "respiratory_rate_rpm" to 26), "duration_s" to 20), null)
    gNow += 10; s.tickAtTime(gNow)
    val hrMid = s.snapshot().heartRate
    CHECK(Cmd(s, "cancel_transitions", listOf("heart_rate_bpm"), err), "interruzione singolo parametro")
    gNow += 15; s.tickAtTime(gNow)
    CHECK(s.snapshot().heartRate == hrMid && s.snapshot().respRate == 26) { F("FC ferma a %d, FR completata a 26", hrMid) }
    CHECK(!Cmd(s, "cancel_transitions", "x", err), "cancel_transitions con valore non valido rifiutato")
    CHECK(!Cmd(s, "transition", mapOf("params" to mapOf("pippo" to 1), "duration_s" to 10), err), "parametro sconosciuto rifiutato")
    CHECK(!Cmd(s, "transition", mapOf("params" to mapOf("diastolic_mmhg" to 150), "duration_s" to 10), err), "diastolica >= sistolica rifiutata")
    CHECK(!Cmd(s, "transition", mapOf("duration_s" to 10), err), "transizione vuota rifiutata")
    // Sinusale -> tachicardia sinusale in 10 s: ritmo subito, FC in rampa verso 130 (non a scatto)
    val t = NewState()
    Cmd(t, "set_heart_rate", 80, null)
    Cmd(t, "transition", mapOf("params" to mapOf("spo2_percent" to 97), "duration_s" to 10, "rhythm" to "sinus_tachycardia"), null)
    CHECK(t.snapshot().rhythm == "sinus_tachycardia" && t.snapshot().heartRate == 80) { F("ritmo cambiato subito, FC ancora 80 (%d)", t.snapshot().heartRate) }
    gNow += 5; t.tickAtTime(gNow)
    val hrT = t.snapshot().heartRate
    CHECK(hrT >= 100 && hrT <= 110) { F("a metà la FC sale gradualmente (~105: %d)", hrT) }
    gNow += 6; t.tickAtTime(gNow)
    CHECK(t.snapshot().heartRate == 130) { F("a fine rampa FC 130 (%d)", t.snapshot().heartRate) }
    // ANDROID (transizioni ritmo 22/11): la FV in una transizione parte con il suo preludio (pochi secondi), poi arriva;
    // con "onset": "immediate" è istantanea come prima
    Cmd(t, "transition", mapOf("params" to mapOf("spo2_percent" to 90), "duration_s" to 10, "rhythm" to "vf"), null)
    CHECK(t.snapshot().rhythm == "sinus_tachycardia" && t.stateDictionary()["rhythm_onset"]["to"] == "vf", "FV in una transizione: prima il preludio")
    Tick(t, 7.0)
    CHECK(t.snapshot().rhythm == "vf", "FV in una transizione: arrivata entro 7 s")
    Cmd(t, "transition", mapOf("duration_s" to 0, "rhythm" to "sinus", "onset" to "immediate"), null)
    Cmd(t, "transition", mapOf("params" to mapOf("spo2_percent" to 90), "duration_s" to 10, "rhythm" to "vf", "onset" to "immediate"), null)
    CHECK(t.snapshot().rhythm == "vf", "FV immediata in una transizione con onset immediate")

    // FC 0 = asistolia (diretta o al termine di una rampa)
    Reset(s)
    Cmd(s, "set_heart_rate", 0, null)
    CHECK(s.snapshot().rhythm == "asystole", "FC 0 -> asistolia")
    Reset(s)
    Cmd(s, "transition", mapOf("params" to mapOf("heart_rate_bpm" to 0), "duration_s" to 10), null)
    gNow += 11; s.tickAtTime(gNow)
    CHECK(s.snapshot().rhythm == "asystole", "rampa FC fino a 0 -> asistolia")

    // Fisiologia automatica: arresto -> SpO2/FR/EtCO2 a 0; ROSC -> recupero
    Reset(s); Sensor(s, "etco2", true)
    RhythmNow(s, "vf", null)
    gNow += 60; s.tickAtTime(gNow)
    var sn = s.snapshot()
    CHECK(sn.spo2 == 0 && sn.respRate == 0 && sn.etco2 == 0) { F("arresto: SpO2 %d FR %d EtCO2 %d a 0", sn.spo2, sn.respRate, sn.etco2) }
    gNow += 1; s.tickAtTime(gNow)
    CHECK(s.snapshot().systolic <= 50) { F("arresto: la PA del paziente crolla (%d)", s.snapshot().systolic) }
    RhythmNow(s, "sinus", null)
    gNow += 2; s.tickAtTime(gNow)
    CHECK(s.snapshot().systolic <= 62 && s.snapshot().pulsePresent) { F("ROSC: la PA riparte bassa (%d)", s.snapshot().systolic) }
    gNow += 58; s.tickAtTime(gNow)
    sn = s.snapshot()
    CHECK(sn.spo2 == 98 && sn.respRate == 16 && sn.etco2 in 36..48, "ROSC: SpO2 e FR di prima; EtCO2 ancora sopra (CO2 accumulata in smaltimento, 24/11)")
    gNow += 120; s.tickAtTime(gNow)
    CHECK(s.snapshot().etco2 == 35, "ROSC: ritorno ai valori pre-arresto")
    CHECK(sn.systolic > 60 && sn.systolic < 110) { F("ROSC: dopo 60 s la PA sta ancora risalendo (%d)", sn.systolic) }
    gNow += 70; s.tickAtTime(gNow)
    CHECK(s.snapshot().systolic == 120 && s.snapshot().diastolic == 80, "ROSC: PA tornata a 120/80 dopo ~2 minuti")
    Cmd(s, "set_auto_physiology", false, null)
    RhythmNow(s, "asystole", null); gNow += 60; s.tickAtTime(gNow)
    CHECK(s.snapshot().spo2 == 98, "fisiologia automatica disattivabile")

    // ROSC: picco di EtCO2 nei primi secondi
    Cmd(s, "set_auto_physiology", true, null)
    Reset(s); Sensor(s, "etco2", true)
    RhythmNow(s, "vf", null); gNow += 60; s.tickAtTime(gNow)
    RhythmNow(s, "sinus", null); gNow += 6; s.tickAtTime(gNow)
    CHECK(s.snapshot().etco2 >= 45) { F("ROSC: picco di EtCO2 (%d)", s.snapshot().etco2) }
    gNow += 50; s.tickAtTime(gNow)
    CHECK(s.snapshot().etco2 in 38..48) { F("ROSC: CO2 accumulata ancora in smaltimento dopo ~1 min (%d)", s.snapshot().etco2) }
    gNow += 110; s.tickAtTime(gNow)
    CHECK(s.snapshot().etco2 == 35, "ROSC: EtCO2 tornata a 35 in ~2-3 min")

    // RCP in arresto: EtCO2 10-20, ventilazioni; stop -> crollo
    Reset(s); Sensor(s, "etco2", true)
    RhythmNow(s, "vf", null); gNow += 60; s.tickAtTime(gNow)
    CHECK(Cmd(s, "set_cpr", true, err) && s.snapshot().cprActive, "RCP avviata")
    gNow += 15; s.tickAtTime(gNow)
    // ANDROID (EtCO2 RCP 22/11): non più 15 fisso: RCP corretta ~15-20 mmHg dal modello (dopo 15 s ancora in salita)
    CHECK(INT(s.stateDictionary()["etco2_mmhg"]) in 14..20 && s.snapshot().respRateSet == 10.0) { F("RCP: EtCO2 %d (14-20), FR impostata 10", INT(s.stateDictionary()["etco2_mmhg"])) }
    // ventilazione manuale: senza insufflazioni niente CO2 a monitor; ogni "ventilate" = un'onda e il valore
    CHECK(s.snapshot().ventilationManual && s.snapshot().respRate == 0 && ISNULL(s.stateDictionary(), "etco2_displayed"), "RCP: senza ventilazioni l'EtCO2 non compare")
    CHECK(Cmd(s, "ventilate", null, err) && Cmd(s, "ventilate", null, err), "RCP: ventilazione segnalata")
    CHECK(s.snapshot().breathTimes.size == 1, "RCP: due tocchi ravvicinati = una insufflazione")
    gNow += 1.5; Cmd(s, "ventilate", null, null)
    CHECK(s.snapshot().respRate == 2 && INT(s.stateDictionary()["etco2_displayed"]) in 14..20) { F("RCP: 2 insufflazioni, EtCO2 %d (14-20) a monitor", INT(s.stateDictionary()["etco2_displayed"])) }
    run { val we = WaveformEngine(1); we.updateWithSnapshot(s.snapshot())
      val b0 = s.snapshot().breathTimes.lastOrNull() ?: 0.0
      // ANDROID (EtCO2 RCP 22/11): fuori dall'onda restano solo le piccole oscillazioni da compressione (pochi mmHg)
      CHECK(we.capnoAt(b0 + 0.5) < 5 && we.capnoAt(b0 + 2.0) > 10 && we.capnoAt(b0 + 3.5) < 5, "RCP: capnogramma solo dopo l'insufflazione") }
    gNow += 31; CHECK(s.snapshot().respRate == 0, "RCP: 30 s senza ventilare, EtCO2 sparisce")
    RhythmNow(s, "sinus", null)
    CHECK(!s.snapshot().cprActive, "al ROSC la RCP si ferma")

    // TV con polso: polso presente, allarme alto, sigla TV
    Reset(s); RhythmNow(s, "vt_pulse", null)
    CHECK(s.snapshot().pulsePresent && s.snapshot().heartRate >= 120, "TV con polso: polso presente, FC >= 120")
    CHECK(AlarmManager.evaluate(s.snapshot()).level == SLAlarmHigh, "TV con polso: allarme alto")

    // Apnea con polso: desaturazione progressiva, poi recupero
    Reset(s)
    Cmd(s, "set_resp_rate", 0, null)
    for (i in 0 until 60) { gNow += 1; s.tickAtTime(gNow) }
    CHECK(s.snapshot().spo2 < 90) { F("apnea 60 s: SpO2 in calo (%d)", s.snapshot().spo2) }
    Cmd(s, "set_resp_rate", 16, null)
    for (i in 0 until 45) { gNow += 1; s.tickAtTime(gNow) }
    CHECK(s.snapshot().spo2 == 98, "ripresa del respiro: SpO2 risale a 98")

    // FR -> EtCO2: iperventilazione abbassa l'EtCO2
    Reset(s)
    Cmd(s, "set_resp_rate", 32, null); gNow += 185; s.tickAtTime(gNow)   // ANDROID (AE-13): equilibrio in ~3 min
    CHECK(s.snapshot().etco2 >= 16 && s.snapshot().etco2 <= 19) { F("FR 16->32: EtCO2 35 -> ~18 (%d)", s.snapshot().etco2) }

    // FA rapida: deficit di polso
    Reset(s); RhythmNow(s, "afib", null); Cmd(s, "set_heart_rate", 150, null)
    CHECK(s.snapshot().pulseRate < 150 && s.snapshot().pulseRate > 120) { F("FA 150: PR %d < FC", s.snapshot().pulseRate) }

    // Catena: attesa 10 s -> FC 120 in 20 s -> attesa 5 s -> FV immediata
    Reset(s); Cmd(s, "set_auto_physiology", true, null)
    val steps = listOf(mapOf("wait_s" to 10, "duration_s" to 20, "params" to mapOf("heart_rate_bpm" to 122), "label" to "tachicardia"),
                       mapOf("wait_s" to 5, "duration_s" to 0, "rhythm" to "vf", "label" to "arresto"))
    CHECK(Cmd(s, "run_chain", mapOf("name" to "Test", "steps" to steps), err), "catena avviata")
    gNow += 5; s.tickAtTime(gNow)
    CHECK(s.snapshot().heartRate == 82 && s.stateDictionary()["chain"]["phase"] == "waiting", "passo 1 in attesa")
    gNow += 15; s.tickAtTime(gNow)
    hr = s.snapshot().heartRate
    CHECK(hr > 95 && hr < 110) { F("passo 1 in rampa (FC %d)", hr) }
    gNow += 16; s.tickAtTime(gNow)
    Tick(s, 7.0)                                         // ANDROID (transizioni ritmo 22/11): preludio della FV
    CHECK(s.snapshot().heartRate == 122 && s.snapshot().rhythm == "vf", "passo 2: FV dopo l'attesa")
    CHECK(!BOOL(s.stateDictionary()["chain"]["active"]), "catena terminata")
    CHECK(!Cmd(s, "run_chain", mapOf("steps" to listOf(mapOf("wait_s" to 5, "params" to mapOf("spo2_percent" to 200)))), err) && err.has("step 1"),
          "passo non valido rifiutato con indicazione del passo")
    Cmd(s, "run_chain", mapOf("steps" to steps), null); Cmd(s, "stop_chain", null, null)
    gNow += 100; s.tickAtTime(gNow)
    CHECK(!BOOL(s.stateDictionary()["chain"]["active"]), "stop_chain")
}

fun CountBeats(e: WaveformEngine, t0: Double, secs: Double, thr: Double): Int {
    var n = 0; var above = false
    var t = t0
    while (t < t0 + secs) {
        val v = e.ecgAt(t, SLLeadII)
        if (!above && v > thr) { n++; above = true } else if (above && v < thr * 0.5) above = false
        t += 0.002
    }
    return n
}

fun testWaveforms() {
    val s = NewState()
    Sensor(s, "art", true); Sensor(s, "etco2", true)
    val e = WaveformEngine(42)
    e.updateWithSnapshot(s.snapshot())
    val beats = CountBeats(e, 0.0, 30.0, 0.6)
    CHECK(beats >= 38 && beats <= 44) { F("sinusale 82 bpm: %d battiti in 30 s", beats) }

    var t: Double
    var maxP = 0.0; t = 30.0; while (t < 33) { maxP = Math.max(maxP, e.plethAt(t)); t += 0.01 }
    CHECK(maxP > 0.5) { F("pletismografia presente (max %.2f)", maxP) }
    var amx = 0.0; var amn = 999.0; t = 33.0; while (t < 38) { val a = e.artAt(t); amx = Math.max(amx, a); amn = Math.min(amn, a); t += 0.005 }
    CHECK(Math.abs(amx - 120) < 12 && Math.abs(amn - 80) < 8) { F("ART sinusale: max %.0f min %.0f (atteso ~120/80)", amx, amn) }

    // Derivazione V diversa da II
    var diff = 0.0; t = 40.0; while (t < 42) { diff += Math.abs(e.ecgAt(t, SLLeadII) - e.ecgAt(t, SLLeadV)); t += 0.004 }
    CHECK(diff > 20) { F("II e V hanno morfologie diverse (diff %.1f)", diff) }

    // Elettrodi staccati: ECG piatto
    Sensor(s, "ecg", false); e.updateWithSnapshot(s.snapshot())
    var mxe = 0.0; t = 43.0; while (t < 45) { mxe = Math.max(mxe, Math.abs(e.ecgAt(t, SLLeadV))); t += 0.004 }
    CHECK(mxe == 0.0, "elettrodi staccati: ECG piatto")
    Sensor(s, "ecg", true)

    RhythmNow(s, "vf", null); e.updateWithSnapshot(s.snapshot())
    var mn = 9.0; var mx = -9.0; t = 50.0; while (t < 55) { val v = e.ecgAt(t, SLLeadII); mn = Math.min(mn, v); mx = Math.max(mx, v); t += 0.004 }
    CHECK(mx - mn > 0.4) { F("VF: attività caotica ampia (%.2f mV)", mx - mn) }
    var maxPv = 0.0; t = 58.0; while (t < 62) { maxPv = Math.max(maxPv, e.plethAt(t)); t += 0.01 }
    CHECK(maxPv < 0.01, "VF: pletismografia piatta")
    // ANDROID (ART RCP 22/11): appena dopo l'arresto la pressione sta ancora crollando (dal valore di prima): si controlla che
    // non ci siano onde pulsatili (oscillazione < 4 mmHg) e, a pressione scesa, che sia vicina al riempimento sistemico
    var av = 0.0; var avMin = 999.0; t = 62.0; while (t < 64) { av = Math.max(av, e.artAt(t)); avMin = Math.min(avMin, e.artAt(t)); t += 0.01 }
    CHECK(av - avMin < 4) { F("VF: ART senza onde pulsatili (%.1f-%.1f)", avMin, av) }
    run { val s9 = NewState(); Sensor(s9, "art", true); RhythmNow(s9, "vf"); gNow += 20; s9.tickAtTime(gNow); val e2 = WaveformEngine(3); e2.updateWithSnapshot(s9.snapshot())
      var a2 = 0.0; var tt = gNow; while (tt < gNow + 2) { a2 = Math.max(a2, e2.artAt(tt)); tt += 0.01 }
      CHECK(a2 < 20) { F("VF dopo 20 s: ART ~15 mmHg (max %.1f)", a2) } }

    RhythmNow(s, "asystole", null); e.updateWithSnapshot(s.snapshot())
    var amp = 0.0; t = 70.0; while (t < 75) { amp = Math.max(amp, Math.abs(e.ecgAt(t, SLLeadII))); t += 0.004 }
    CHECK(amp > 0.1 && amp < 0.35) { F("asistolia: linea ondulata non piatta, senza QRS (max %.3f mV)", amp) }

    RhythmNow(s, "vt", null); e.updateWithSnapshot(s.snapshot())
    val vtBeats = CountBeats(e, 80.0, 10.0, 0.6)
    CHECK(vtBeats >= 25 && vtBeats <= 35) { F("TV 180 bpm: %d battiti in 10 s", vtBeats) }
    var above = 0.0; t = 90.0; while (t < 100) { if (e.ecgAt(t, SLLeadII) > 0.6) above += 0.001; t += 0.001 }
    RhythmNow(s, "sinus", null); e.updateWithSnapshot(s.snapshot())
    var aboveS = 0.0; t = 110.0; while (t < 120) { if (e.ecgAt(t, SLLeadII) > 0.6) aboveS += 0.001; t += 0.001 }
    CHECK(above / Math.max(vtBeats, 1) > 2.5 * aboveS / 13.0, "QRS TV più largo del sinusale")

    RhythmNow(s, "afib", null); Cmd(s, "set_heart_rate", 100, null); e.updateWithSnapshot(s.snapshot())
    val rr = ArrayList<Double>(); var last = -1.0; var up = false
    t = 130.0
    while (t < 160) {
        val v = e.ecgAt(t, SLLeadII)
        if (!up && v > 0.6) { if (last > 0) rr.add(t - last); last = t; up = true } else if (up && v < 0.3) up = false
        t += 0.002
    }
    val mean = if (rr.isEmpty()) 0.0 else rr.average(); var vr = 0.0
    for (x in rr) vr += Math.pow(x - mean, 2.0)
    CHECK(Math.sqrt(vr / Math.max(rr.size, 1)) / mean > 0.12, "FA: RR irregolare")

    RhythmNow(s, "pea", null); e.updateWithSnapshot(s.snapshot())
    val peaBeats = CountBeats(e, 170.0, 10.0, 0.6)
    var maxPP = 0.0; t = 175.0; while (t < 180) { maxPP = Math.max(maxPP, e.plethAt(t)); t += 0.01 }
    CHECK(peaBeats > 5 && maxPP < 0.01) { F("PEA: %d QRS, pleth assente", peaBeats) }

    RhythmNow(s, "sinus", null); Cmd(s, "set_etco2", 40, null); e.updateWithSnapshot(s.snapshot())
    var cmax = 0.0; var cmin = 99.0; t = 200.0; while (t < 215) { val v = e.capnoAt(t); cmax = Math.max(cmax, v); cmin = Math.min(cmin, v); t += 0.01 }
    CHECK(Math.abs(cmax - 40) < 1.5 && cmin < 1) { F("capnogramma: max %.1f, min %.1f", cmax, cmin) }
    Sensor(s, "etco2", false); e.updateWithSnapshot(s.snapshot())
    CHECK(e.capnoAt(220.0) == 0.0, "sensore CO2 staccato: capnogramma piatto")
    Sensor(s, "etco2", true); RhythmNow(s, "sinus", null)
    Cmd(s, "set_bp", mapOf("systolic" to 120, "diastolic" to 80), null)   // annulla le rampe post-ROSC dei passi precedenti
    // Frequenze alte: ART e pleth restano nel range impostato (niente accumulo dei battiti)
    for (f in listOf(150, 200, 250)) {
        Cmd(s, "set_heart_rate", f, null); e.updateWithSnapshot(s.snapshot())
        var ax = 0.0; var an = 999.0; var px = 0.0; var pn = 9.0
        val t0 = 1000 + 10 * f.toDouble()
        t = t0
        while (t < t0 + 6) { val a = e.artAt(t); val p = e.plethAt(t); ax = Math.max(ax, a); an = Math.min(an, a); px = Math.max(px, p); pn = Math.min(pn, p); t += 0.002 }
        CHECK(ax <= 121 && an >= 79 && ax > 110) { F("FC %d: ART %.0f/%.0f entro 120/80", f, ax, an) }
        CHECK(px <= 0.85 && pn < 0.1) { F("FC %d: pleth %.2f..%.2f non sale verso l'alto", f, pn, px) }
    }

}

fun testAlarms() {
    val s = NewState()
    CHECK(AlarmManager.evaluate(s.snapshot()).level == SLAlarmNone, "nessun allarme a riposo")
    Cmd(s, "set_spo2", 88, null)
    CHECK(AlarmManager.evaluate(s.snapshot()).level == SLAlarmNone, "SpO2 88: il numero a schermo arriva dopo il ritardo del dito, l'allarme pure")
    Tick(s, 6)   // ANDROID (numerici realistici 22/11)
    AlarmManager.evaluate(s.snapshot()); Tick(s, 5)   // ANDROID (IN-08): + ritardo d'allarme della SpO2 (5 s di serie)
    var r = AlarmManager.evaluate(s.snapshot())
    CHECK(r.level == SLAlarmMedium && r.violated.contains("spo2"), "SpO2 88 < 90 -> allarme medio")
    Cmd(s, "set_alarm_limits", mapOf("spo2_low" to 85), null)
    CHECK(AlarmManager.evaluate(s.snapshot()).level == SLAlarmNone, "abbassando il limite l'allarme rientra")
    RhythmNow(s, "vf", null)
    r = AlarmManager.evaluate(s.snapshot())
    CHECK(r.level == SLAlarmHigh && HASSTR(r.messages.firstOrNull(), "FIBRILLAZIONE"), "VF -> allarme alto")
    Sensor(s, "ecg", false)
    r = AlarmManager.evaluate(s.snapshot())
    CHECK(!HASSTR(r.messages.firstOrNull(), "FIBRILLAZIONE") && r.messages.joinToString(",").contains("ELETTRODI"),
          "elettrodi staccati: il monitor non vede la VF, solo allarme tecnico")
    Reset(s); Sensor(s, "spo2", false)
    CHECK(AlarmManager.evaluate(s.snapshot()).level == SLAlarmTechnical, "sonda staccata -> allarme tecnico")
    Reset(s); Cmd(s, "set_alarm_limits", mapOf("hr_high" to 80), null)
    r = AlarmManager.evaluate(s.snapshot())
    CHECK(r.level == SLAlarmMedium && r.violated.contains("hr"), "FC 82 > limite 80 -> allarme medio")
    CHECK(r.messages.firstOrNull() == "FC > 80") { F("il banner indica solo il limite superato (%s)", r.messages.firstOrNull()) }
    CHECK(INT(r.severity["hr"]) == SLAlarmMedium, "FC appena sopra il limite: riquadro GIALLO")
    Cmd(s, "set_heart_rate", 140, null); Tick(s, 6)
    CHECK(INT(AlarmManager.evaluate(s.snapshot()).severity["hr"]) == SLAlarmHigh, "FC molto sopra il limite: riquadro ROSSO")
    Cmd(s, "set_spo2", 75, null); Tick(s, 6)   // limite SpO2 attuale 85 (impostato sopra): 75 < 85-5 -> critico
    AlarmManager.evaluate(s.snapshot()); Tick(s, 5)   // ANDROID (IN-08): ritardo d'allarme della SpO2
    r = AlarmManager.evaluate(s.snapshot())
    CHECK(INT(r.severity["spo2"]) == SLAlarmHigh && INT(r.severity["hr"]) == SLAlarmHigh, "più valori in allarme insieme")
    Reset(s); Cmd(s, "reset_alarm_limits", null, null); Sensor(s, "etco2", true); Cmd(s, "set_resp_rate", 0, null)
    CHECK(AlarmManager.evaluate(s.snapshot()).level == SLAlarmHigh, "apnea (con CO2) -> allarme alto")
    Sensor(s, "etco2", false)
    CHECK(AlarmManager.evaluate(s.snapshot()).messages.joinToString(",").contains("APNEA"), "apnea rilevata anche dall'impedenza ECG senza CO2")
}
