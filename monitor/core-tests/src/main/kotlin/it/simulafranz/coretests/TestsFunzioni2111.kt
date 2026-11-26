// ANDROID (funzioni 21/11): test delle nuove funzioni del cuore (NUOVE_FUNZIONI_CUORE.md):
// metriche RCP, tempi per layout e device_realism, regia DAE, avanzamenti automatici, cardioversione e pacing,
// allarmi tecnici e ambulanza in movimento, salvataggio e ripristino dello stato.
package it.simulafranz.coretests

import it.simulafranz.core.*

private fun events(s: SimulationState): List<String> = (s.stateDictionary()["defib"]["events"] as List<*>).map { STR(it["text"]) ?: "" }
private fun hasEvent(s: SimulationState, needle: String): Boolean = events(s).any { it.contains(needle) }

fun testFunzioni2111() {
    val err = Err()

    // --- 1. METRICHE RCP ---
    var s = NewState()
    Cmd(s, "start_scenario")             // ANDROID (avvio scenario 22/11): metriche di uno scenario avviato
    var cm = s.stateDictionary()["cpr_metrics"]
    CHECK(ISNULL(cm, "ccf_percent") && COUNT(cm["shocks"]) == 0 && ISNULL(cm, "last_pre_shock_s"), "metriche: vuote all'inizio")
    Sensor(s, "pads", true); Cmd(s, "defib_mode", "manual"); Cmd(s, "defib_energy", 200)
    RhythmNow(s, "vf"); Cmd(s, "set_cpr", true)
    Tick(s, 30)
    Cmd(s, "defib_charge"); Tick(s, 5)                       // carica durante le compressioni
    Cmd(s, "set_cpr", false); Tick(s, 3)
    CHECK(Cmd(s, "defib_shock", null, err), "metriche: scarica erogata")
    cm = s.stateDictionary()["cpr_metrics"]
    CHECK(INT(cm["last_pre_shock_s"]) == 3 && ISNULL(cm, "last_post_shock_s"), { F("metriche: pausa pre 3 s, post in attesa (%s)", cm.toString()) })
    Tick(s, 2); Cmd(s, "set_cpr", true)
    cm = s.stateDictionary()["cpr_metrics"]
    val sh0 = cm["shocks"][0]
    CHECK(INT(cm["last_post_shock_s"]) == 2 && INT(sh0["n"]) == 1 && INT(sh0["energy_j"]) == 200 && INT(sh0["pre_s"]) == 3 && INT(sh0["post_s"]) == 2,
          { F("metriche: scarica 1 pre 3 post 2 (%s)", sh0.toString()) })
    CHECK(hasEvent(s, "Scarica 1: pausa pre 3 s, post 2 s"), "metriche: riga nel registro del defibrillatore")
    Tick(s, 10)
    cm = s.stateDictionary()["cpr_metrics"]
    CHECK(INT(cm["ccf_percent"]) == 90 && INT(cm["arrest_s"]) == 50, { F("metriche: frazione di compressione 45/50 s = 90 %% (%s)", cm.toString()) })
    // scarica a compressioni in corso: pause 0
    Cmd(s, "defib_outcome", "stay"); Cmd(s, "defib_charge"); Tick(s, 5); Cmd(s, "defib_shock")
    cm = s.stateDictionary()["cpr_metrics"]
    CHECK(INT(cm["last_pre_shock_s"]) == 0 && INT(cm["last_post_shock_s"]) == 0 && hasEvent(s, "Scarica 2: pausa pre 0 s, post 0 s"), "metriche: scarica senza fermare le compressioni")
    // in pausa il tempo non conta
    Cmd(s, "set_simulation_running", false); Tick(s, 60); Cmd(s, "set_simulation_running", true)
    CHECK(INT(s.stateDictionary()["cpr_metrics"]["arrest_s"]) == INT(cm["arrest_s"]), "metriche: la pausa non conta nel tempo in arresto")
    // scarica su ritmo con polso (cardioversione)
    Cmd(s, "defib_outcome", "afib"); PS(s); Cmd(s, "defib_energy", 100); Cmd(s, "defib_sync", true)
    Cmd(s, "defib_charge"); Tick(s, 5); Cmd(s, "defib_shock"); s.defibRWaveAt(gNow + 0.2)
    CHECK(hasEvent(s, "Scarica 3: su ritmo con polso (SYNC)"), "metriche: cardioversione registrata senza pause")
    Reset(s)
    CHECK(COUNT(s.stateDictionary()["cpr_metrics"]["shocks"]) == 0 && ISNULL(s.stateDictionary()["cpr_metrics"], "ccf_percent"), "metriche: azzerate dal reset")

    // --- 2. TEMPI PER LAYOUT e device_realism ---
    CHECK(SLLayoutTimingFor("dae").analysisS == 8.0, "tempi: tabella per layout")
    CHECK(s.stateDictionary()["defib"]["config"]["device_realism"] == false, "device_realism: spento di serie")
    var dd = s.stateDictionary()["defib"]

    // --- 3. REGIA DAE ---
    s = NewState(); Cmd(s, "set_defib_config", mapOf("outcome_mode" to "auto"))
    CHECK(!Cmd(s, "set_defib_config", mapOf("outcome_sequence" to listOf("boh")), err) && !Cmd(s, "set_defib_config", mapOf("outcome_sequence" to "stay"), err),
          "sequenza: valori non validi rifiutati")
    CHECK(Cmd(s, "set_defib_config", mapOf("outcome_sequence" to listOf("stay", "convert")), err), "sequenza: [stay, convert]")
    Sensor(s, "pads", true); Cmd(s, "defib_mode", "manual"); Cmd(s, "defib_energy", 200); RhythmNow(s, "vf")
    Cmd(s, "defib_charge"); Tick(s, 6); Cmd(s, "defib_shock")
    CHECK(s.snapshot().rhythm == "vf" && INT(s.stateDictionary()["defib"]["outcome_sequence_index"]) == 1, "sequenza: 1a scarica resta FV")
    Cmd(s, "defib_charge"); Tick(s, 6); Cmd(s, "defib_shock")
    PS(s); CHECK(s.snapshot().rhythm == "sinus", "sequenza: 2a scarica converte")
    Cmd(s, "set_defib_config", mapOf("outcome_sequence" to listOf("asystole")))
    RhythmNow(s, "vf"); Cmd(s, "defib_charge"); Tick(s, 6); Cmd(s, "defib_shock")
    CHECK(s.snapshot().rhythm == "asystole", "sequenza: un ritmo come esito")
    Cmd(s, "set_defib_config", mapOf("outcome_sequence" to emptyList<String>()))
    CHECK(!HAS(s.stateDictionary()["defib"]["config"], "outcome_sequence"), "sequenza: lista vuota -> si torna a shocks_to_convert")
    RhythmNow(s, "vf"); Cmd(s, "defib_charge"); Tick(s, 6); Cmd(s, "defib_shock")
    PS(s); CHECK(s.snapshot().rhythm == "sinus", "senza sequenza: shocks_to_convert (1) come prima")
    // placche pediatriche
    s = NewState()
    CHECK(!Cmd(s, "set_pads_type", "boh", err), "placche: valore non valido rifiutato")
    Cmd(s, "set_pads_type", "pediatric"); RhythmNow(s, "vf"); Cmd(s, "defib_mode", "aed")
    dd = s.stateDictionary()["defib"]
    CHECK(dd["pads_type"] == "pediatric" && INT(dd["aed_effective_energy_j"]) == 50 && dd["message"] == "APPLICARE LE PIASTRE PEDIATRICHE" &&
          HASSTR(s.snapshot().defib.voicePrompt, "pediatriche"), "placche pediatriche: energia 50 J e messaggi coerenti")
    Sensor(s, "pads", true); Tick(s, 9)
    CHECK(s.snapshot().defib.chargedEnergy == 50, { F("placche pediatriche: il DAE carica 50 J (%d)", s.snapshot().defib.chargedEnergy) })
    Tick(s, 5); Cmd(s, "defib_shock"); Cmd(s, "set_cpr", true); Cmd(s, "set_cpr_quality", mapOf("depth" to "shallow")); Tick(s, 12)
    CHECK(s.snapshot().defib.message == "RCP IN CORSO", "placche pediatriche: nessun consiglio correttivo sulla RCP")
    s = NewState(); Cmd(s, "set_aed_child_mode", true)
    CHECK(BOOL(s.stateDictionary()["defib"]["aed_child_mode"]) && INT(s.stateDictionary()["defib"]["aed_effective_energy_j"]) == 50, "modalità bambino: energia pediatrica")
    Cmd(s, "set_defib_config", mapOf("aed_ped_energy_j" to 70))
    CHECK(INT(s.stateDictionary()["defib"]["aed_effective_energy_j"]) == 70 && s.snapshot().defib.energy == 150, "energia pediatrica configurabile, energia adulto invariata")
    // cavo delle piastre
    s = NewState(); Sensor(s, "pads", true); Cmd(s, "defib_mode", "manual")
    CHECK(BOOL(s.stateDictionary()["defib"]["pads_connected"]) && s.stateDictionary()["defib"]["pads_cable"] == "connected", "cavo: collegato di serie")
    Cmd(s, "set_pads_cable", "disconnected")
    dd = s.stateDictionary()["defib"]
    CHECK(!BOOL(dd["pads_connected"]) && BOOL(dd["pads_applied"]) && BOOL(s.stateDictionary()["sensors"]["pads"]) && dd["message"] == "COLLEGARE IL CAVO DELLE PIASTRE",
          "cavo staccato: piastre sul paziente ma il defibrillatore non le vede")
    CHECK(!Cmd(s, "defib_charge", null, err), "cavo staccato: carica rifiutata")
    RhythmNow(s, "vf"); Cmd(s, "defib_mode", "aed")
    CHECK(s.snapshot().defib.aedPhase == SLAedPads && s.snapshot().defib.voicePrompt == "Collegare il cavo delle piastre.", "cavo staccato in DAE: «Collegare il cavo»")
    Cmd(s, "set_pads_cable", "connected")
    CHECK(s.snapshot().defib.aedPhase == SLAedAnalyzing && hasEvent(s, "Cavo delle piastre collegato"), "cavo collegato: il DAE analizza")
    // impedenza alta: lo shock non parte
    s = NewState(); Sensor(s, "pads", true); Cmd(s, "defib_mode", "manual"); RhythmNow(s, "vf")
    Cmd(s, "defib_charge"); Tick(s, 5); Cmd(s, "set_pads_contact", "poor")
    CHECK(!Cmd(s, "defib_shock", null, err) && s.snapshot().defib.shockCount == 0 && s.snapshot().defib.charge == SLChargeReady &&
          HASSTR(s.snapshot().defib.message, "IMPEDENZA ALTA"), "impedenza alta: shock non erogato, la carica resta")
    Cmd(s, "set_pads_contact", "good")
    CHECK(Cmd(s, "defib_shock", null, err) && s.snapshot().defib.shockCount == 1, "contatto ripristinato: shock erogato")

    // --- 4. AVANZAMENTI AUTOMATICI ---
    s = NewState()
    CHECK(s.stateDictionary()["defib"]["config"]["auto_advance"] == false && ISNULL(s.stateDictionary(), "auto_action"), "auto_advance: spento di serie")
    CHECK(!Cmd(s, "undo_auto_action", null, err), "undo: niente da annullare")
    Cmd(s, "set_defib_config", mapOf("auto_advance" to true))
    Sensor(s, "pads", true); Cmd(s, "defib_mode", "manual"); RhythmNow(s, "afib"); Cmd(s, "defib_energy", 100); Cmd(s, "defib_sync", true)
    Cmd(s, "defib_charge"); Tick(s, 5); Cmd(s, "defib_shock"); s.defibRWaveAt(gNow + 0.2)
    var aa = s.stateDictionary()["auto_action"]
    CHECK(!BOOL(s.stateDictionary()["defib"]["outcome_pending"]) && HASSTR(aa["text"], "Cardioversione") && INT(aa["remaining_s"]) == 5 && PS(s, 2.5) && s.snapshot().rhythm == "sinus",
          "auto_advance: cardioversione efficace -> sinusale, con «Annulla» per 5 s")
    Tick(s, 2)
    CHECK(Cmd(s, "undo_auto_action", null, err) && s.snapshot().rhythm == "afib" && BOOL(s.stateDictionary()["defib"]["outcome_pending"]) &&
          ISNULL(s.stateDictionary(), "auto_action"), "undo: torna in FA e la decisione passa all'istruttore")
    Cmd(s, "defib_outcome", "stay"); Cmd(s, "defib_sync", true); Cmd(s, "defib_charge"); Tick(s, 5); Cmd(s, "defib_shock"); s.defibRWaveAt(gNow + 0.2)
    Tick(s, 6)
    CHECK(ISNULL(s.stateDictionary(), "auto_action") && !Cmd(s, "undo_auto_action", null, err) && s.snapshot().rhythm == "sinus", "undo: dopo 5 s non si annulla più")

    // --- 5. CARDIOVERSIONE e PACING ---
    s = NewState()
    CHECK(s.stateDictionary()["defib"]["config"]["sync_off_after_shock"] == true, "sync_off_after_shock: vero di serie (come prima)")
    Cmd(s, "set_defib_config", mapOf("sync_off_after_shock" to false))
    CHECK(s.stateDictionary()["defib"]["config"]["sync_stays_on"] == true, "sync_off_after_shock = false -> sync_stays_on")
    Sensor(s, "pads", true); Cmd(s, "defib_mode", "manual"); Cmd(s, "defib_sync", true)
    CHECK(BOOL(s.stateDictionary()["defib"]["sync_markers"]), "marker di sincronizzazione visibili (manuale, SYNC, sinusale)")
    RhythmNow(s, "vf")
    CHECK(!BOOL(s.stateDictionary()["defib"]["sync_markers"]), "FV: nessun QRS, nessun marker")
    s = NewState(); Sensor(s, "pads", true); RhythmNow(s, "asystole"); Cmd(s, "defib_mode", "pacer")
    Cmd(s, "defib_pacer", mapOf("rate" to 70, "output_ma" to 100, "demand" to false, "running" to true))
    CHECK(s.snapshot().defib.paceCapture && s.snapshot().pulsePresent, "pacing: cattura sopra soglia (di serie)")
    Cmd(s, "set_defib_config", mapOf("false_capture" to true))
    var pc = s.stateDictionary()["defib"]["pacer"]
    CHECK(!BOOL(pc["capture"]) && BOOL(pc["false_capture"]) && BOOL(pc["active"]) && !s.snapshot().pulsePresent, "falsa cattura: spike ma nessuna cattura né polso")
    Cmd(s, "set_defib_config", mapOf("false_capture" to false, "mechanical_capture" to false))
    pc = s.stateDictionary()["defib"]["pacer"]
    CHECK(BOOL(pc["capture"]) && !BOOL(pc["mechanical_capture"]) && !s.snapshot().pulsePresent, "cattura elettrica senza cattura meccanica")

    // --- 7. ALLARMI TECNICI e AMBULANZA IN MOVIMENTO ---
    s = NewState()
    CHECK(!Cmd(s, "set_technical_alarm", mapOf("kind" to "boh", "on" to true), err) && !Cmd(s, "set_technical_alarm", mapOf("kind" to "low_battery"), err),
          "allarme tecnico: valori non validi rifiutati")
    Cmd(s, "set_technical_alarm", mapOf("kind" to "ecg_leads_off", "on" to true))
    var st = s.stateDictionary()
    CHECK(!s.snapshot().ecgConnected && BOOL(st["sensors"]["ecg"]) && ISNULL(st, "displayed_heart_rate_bpm") &&
          st["technical_alarms"][0]["kind"] == "ecg_leads_off" && st["technical_alarms"][0]["text"] == "ECG ELETTRODI SCOLLEGATI",
          "elettrodi scollegati simulati: ECG assente sul monitor, sensore ancora collegato nello stato")
    CHECK(AlarmManager.evaluate(s.snapshot()).messages.contains("ECG ELETTRODI SCOLLEGATI"), "elettrodi scollegati simulati: allarme tecnico del monitor")
    Cmd(s, "set_technical_alarm", mapOf("kind" to "ecg_leads_off", "on" to false))
    CHECK(s.snapshot().ecgConnected && s.snapshot().technicalAlarms.isEmpty(), "allarme tecnico tolto: ECG di nuovo visibile")
    Cmd(s, "set_technical_alarm", mapOf("kind" to "spo2_probe_off", "on" to true))
    CHECK(!s.snapshot().spo2Connected && ISNULL(s.stateDictionary(), "spo2_displayed"), "sonda SpO2 staccata simulata")
    Cmd(s, "set_technical_alarm", mapOf("kind" to "nibp_cuff_error", "on" to true))
    CHECK(s.stateDictionary()["nibp_status"] == "error" && s.stateDictionary()["nibp_error"] == "cuff", "errore bracciale simulato")
    RunNibp(s)
    CHECK(s.stateDictionary()["nibp_status"] == "error", "errore bracciale simulato: la misura non riesce")
    Cmd(s, "set_technical_alarm", mapOf("kind" to "nibp_cuff_error", "on" to false)); RunNibp(s)
    CHECK(s.stateDictionary()["nibp_status"] == "complete", "errore bracciale tolto: la misura riesce")
    Cmd(s, "set_technical_alarm", mapOf("kind" to "low_battery", "on" to true))
    CHECK(s.snapshot().technicalAlarms.contains("low_battery"), "batteria scarica simulata")
    RhythmNow(s, "vf")
    CHECK(!Cmd(s, "set_patient_motion", "light", err) && Cmd(s, "vehicle_motion", true, err), "ambulanza in movimento: vale anche senza polso")
    st = s.stateDictionary()
    CHECK(BOOL(st["vehicle_motion"]) && st["patient_motion"] == "off" && s.snapshot().patientMotion == 1, "ambulanza: artefatto come il movimento lieve")
    Cmd(s, "vehicle_motion", "off")
    CHECK(!s.snapshot().vehicleMotion && s.snapshot().patientMotion == 0, "ambulanza ferma")
    Reset(s)
    CHECK(s.snapshot().technicalAlarms.isEmpty() && !s.snapshot().vehicleMotion, "reset: niente distrattori")

    // --- 8. ROBUSTEZZA: salvataggio e ripristino ---
    s = NewState(); Cmd(s, "reset_clock")
    Cmd(s, "set_layout", "extrah"); Sensor(s, "etco2", true); Sensor(s, "pads", true); Sensor(s, "art", true)
    Cmd(s, "set_alarm_limits", mapOf("hr_low" to 45)); Cmd(s, "set_nibp_interval", 5); RunNibp(s)
    Cmd(s, "set_defib_config", mapOf("outcome_sequence" to listOf("stay", "convert"), "outcome_mode" to "auto"))
    Cmd(s, "defib_mode", "manual"); RhythmNow(s, "vf"); Cmd(s, "set_cpr", true); Tick(s, 20)
    Cmd(s, "defib_charge"); Tick(s, 6); Cmd(s, "set_cpr", false); Tick(s, 2); Cmd(s, "defib_shock")
    Cmd(s, "transition", mapOf("params" to mapOf("etco2_mmhg" to 20), "duration_s" to 60))
    Cmd(s, "run_chain", mapOf("name" to "fasi", "steps" to listOf(mapOf("wait_s" to 40, "rhythm" to "asystole", "label" to "peggiora"))))
    Cmd(s, "set_technical_alarm", mapOf("kind" to "low_battery", "on" to true)); Cmd(s, "set_pads_type", "pediatric")
    Tick(s, 5)
    val saved = s.exportState()
    @Suppress("UNCHECKED_CAST")
    val back = Json.parse(Json.stringify(saved)) as Map<String, Any?>
    val s2 = NewStateRaw(); s2.clock = { gNow + 5000.0 }            // orologio diverso, come dopo un riavvio
    CHECK(s2.restoreState(back) == null, "ripristino: riuscito")
    fun cmp(a: Map<String, Any?>, b: Map<String, Any?>): List<String> {
        val diff = ArrayList<String>()
        for (k in a.keys) if (k != "revision" && k != "defib" && a[k] != b[k]) diff.add("$k: ${a[k]} != ${b[k]}")
        val da = a["defib"] as Map<*, *>; val db = b["defib"] as Map<*, *>
        for (k in da.keys) if (k != "events" && da[k] != db[k]) diff.add("defib.$k: ${da[k]} != ${db[k]}")
        val ea = (da["events"] as List<*>).map { it["text"] }; val eb = (db["events"] as List<*>).map { it["text"] }
        if (eb.take(ea.size) != ea) diff.add("defib.events")
        return diff
    }
    var diff = cmp(s.stateDictionary(), s2.stateDictionary())
    CHECK(diff.isEmpty(), { "ripristino: stato identico; differenze: $diff" })
    CHECK(hasEvent(s2, "Stato ripristinato"), "ripristino: riga nel registro")
    Tick(s, 20); s2.tickAtTime(gNow + 5000.0)
    // stesso tempo trascorso per i due: s ha fatto i tick a passi di 0,5 s, s2 un solo tick finale
    diff = cmp(s.stateDictionary(), s2.stateDictionary())
    CHECK(diff.isEmpty(), { "ripristino: dopo 20 s i due pazienti evolvono uguali; differenze: $diff" })
    Tick(s, 30); s2.tickAtTime(gNow + 5000.0)
    Tick(s, 12); s2.tickAtTime(gNow + 5000.0)          // ANDROID (transizioni ritmo 22/11): la FV si affina, poi asistolia
    CHECK(s2.snapshot().rhythm == "asystole" && s.snapshot().rhythm == "asystole", "ripristino: la catena prosegue e scatta")
    // in pausa: resta in pausa
    Cmd(s, "set_simulation_running", false)
    val s3 = NewStateRaw(); s3.restoreState(Json.parse(Json.stringify(s.exportState())) as Map<String, Any?>)
    CHECK(!s3.snapshot().simulationRunning && s3.snapshot().scenarioTime == s.snapshot().scenarioTime, "ripristino: pausa e tempo di scenario")
    // dati rovinati: valori di ripiego, mai NaN o fuori range
    val s4 = NewState()
    CHECK(s4.restoreState(mapOf("format" to 99L)) != null && s4.restoreState(mapOf("format" to 1L)) != null, "ripristino: formato sbagliato rifiutato")
    CHECK(s4.restoreState(mapOf("format" to 1L, "patient" to mapOf("hr" to Double.NaN, "spo2" to "x", "sys" to 50, "dia" to 90, "rhythm" to "boh",
                                "ramps" to listOf(mapOf("key" to "spo2_percent", "to" to 500, "dur" to Double.NaN))),
                                "defib" to mapOf("mode" to 9, "energy" to 11, "config" to mapOf("aed_energy_j" to 5000, "outcome_sequence" to listOf("x"))))) == null,
          "ripristino: dati rovinati accettati con ripiego")
    val sn4 = s4.snapshot()
    CHECK(sn4.heartRate == 82 && sn4.spo2 == 98 && sn4.systolic == 50 && sn4.diastolic < 50 && sn4.rhythm == "sinus" && sn4.transitions.isEmpty() &&
          sn4.defib.mode == SLDefibPacer && sn4.defib.energy == 150 && INT(s4.stateDictionary()["defib"]["config"]["aed_energy_j"]) == 150 &&
          !HAS(s4.stateDictionary()["defib"]["config"], "outcome_sequence"),
          { F("ripristino: ripieghi (FC %d, SpO2 %d, PA %d/%d, modo %d)", sn4.heartRate, sn4.spo2, sn4.systolic, sn4.diastolic, sn4.defib.mode) })
    // NaN nei comandi
    CHECK(!Cmd(s4, "patient_touch", Double.NaN, err) && !Cmd(s4, "set_patient_motion", mapOf("level" to "light", "duration_s" to Double.NaN), err),
          "NaN rifiutato (tocco, durata del movimento)")
    s4.muteDuration = Double.NaN
    CHECK(s4.muteDuration == 20.0, "silenziamento: durata NaN -> 20 s")
    // scenario a fasi in corso
    val sc = SFScenarioStore(null, s)
    val id = STR(sc.scenarios()[0]["id"])
    sc.start(id, 0); sc.next()
    val run = sc.exportRun()
    val sc2 = SFScenarioStore(null, s)
    CHECK(sc2.restoreRun(Json.parse(Json.stringify(run)) as Map<String, Any?>) == null && INT(sc2.runInfo()["phase"]) == 1 && sc2.runInfo()["id"] == id,
          "ripristino: scenario a fasi in corso con la sua fase")
    CHECK(sc2.restoreRun(mapOf("active" to true, "id" to "nessuno")) != null && !BOOL(sc2.runInfo()["active"]), "ripristino: scenario sconosciuto")
}
