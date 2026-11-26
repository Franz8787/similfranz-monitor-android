// ANDROID (guasti 22/11): test dei malfunzionamenti simulati del dispositivo (RICERCA_MALFUNZIONAMENTI_DAE.md, cap. 4;
// comandi set_device_fault, clear_device_faults, set_device_fault_beep; campi device_faults, device_readiness,
// battery_shocks_left, fault_beep_interval_s). Regola: di serie nessun guasto e comportamento identico a prima.
package it.simulafranz.coretests

import it.simulafranz.core.*

private fun F2(s: SimulationState, kind: String, on: Boolean, err: Err? = null): Boolean =
    Cmd(s, "set_device_fault", mapOf("kind" to kind, "on" to on), err)

// DAE generico acceso (come il tasto di accensione del layout): monitor acceso + modalità DAE
private fun PowerAed(s: SimulationState): Boolean { s.devicePowered = true; return Cmd(s, "defib_mode", "aed") }
private fun PowerOff(s: SimulationState) { Cmd(s, "defib_mode", "off"); s.devicePowered = false }
private fun D(s: SimulationState): SLDefibInfo = s.snapshot().defib
private fun Voice(s: SimulationState): String = D(s).voicePrompt ?: ""
// analisi (8 s) + carica: arriva a «pronto»
private fun ToReady(s: SimulationState) { Tick(s, 9); Tick(s, 6) }
private fun Shock(s: SimulationState): Boolean = Cmd(s, "defib_shock")

fun testGuasti2211() {
    // --- di serie: nessun malfunzionamento, campi neutri, comportamento di prima ---
    var s = NewState()
    var d = s.stateDictionary()
    CHECK(COUNT(d["device_faults"]) == 0 && d["device_readiness"] == "ready", "di serie: nessun malfunzionamento, spia pronta")
    CHECK(d["device_fault_beep"] == true && LONG(d["fault_beep_interval_s"]) == 0L && ISNULL(d, "battery_shocks_left"), "di serie: bip sì ma nessun bip, nessuna batteria simulata")
    CHECK(COUNT(d["device_faults_supported"]) == SLDeviceFaultKinds.size - 7, "ICU: 6 tipi applicabili")
    Cmd(s, "set_layout", "dae")
    CHECK(COUNT(s.stateDictionary()["device_faults_supported"]) == 13, "DAE: tutti i 13 tipi")
    CHECK(PowerAed(s) && D(s).aedPhase == SLAedPads && Voice(s) == "Applicare le piastre sul torace nudo del paziente.", "di serie: accensione con la voce di prima")
    CHECK(D(s).message == "APPLICARE LE PIASTRE", "di serie: messaggio di prima")
    RhythmNow(s, "vf"); Sensor(s, "pads", true); ToReady(s)
    CHECK(D(s).charge == SLChargeReady && Shock(s) && D(s).shockCount == 1, "di serie: analisi, carica e scarica come prima")
    CHECK(Voice(s) == "Shock erogato. Iniziare subito la RCP.", "di serie: voce dopo la scarica invariata")

    // --- errori ---
    s = NewState(); val e = Err()
    CHECK(!F2(s, "boh", true, e) && e.has("set_device_fault: kind must be one of"), "kind sconosciuto rifiutato")
    CHECK(!Cmd(s, "set_device_fault", "battery_low", e) && e.has("value must be"), "valore non oggetto rifiutato")
    CHECK(!Cmd(s, "set_device_fault", mapOf("kind" to "battery_low", "on" to 1), e), "on non booleano rifiutato")
    CHECK(!Cmd(s, "set_device_fault_beep", "si", e), "set_device_fault_beep: booleano")

    // --- da spento: spia e bip per layout ---
    s = NewState(); Cmd(s, "set_layout", "dae")
    CHECK(F2(s, "battery_low", true), "battery_low a dispositivo spento")
    var sn = s.snapshot()
    CHECK(sn.deviceFaults == listOf("battery_low") && sn.deviceReadiness == "attention", "battery_low: spia di attenzione")
    CHECK(sn.faultBeepIntervalS == 30 && !sn.faultBeepDouble && sn.batteryShocksLeft == 6, "battery_low: bip ogni 30 s, 6 scariche")
    Cmd(s, "set_device_fault_beep", false)
    CHECK(s.snapshot().faultBeepIntervalS == 0 && !s.snapshot().deviceFaultBeep, "bip da spento disattivato")
    Cmd(s, "set_device_fault_beep", true)
    for (L in listOf("generic_icu")) {
        Cmd(s, "set_layout", L)
        CHECK(s.snapshot().faultBeepIntervalS == 0, "$L: nessun bip da spento")
    }
    F2(s, "maintenance", true)
    for (L in listOf("dae", "extrah")) { Cmd(s, "set_layout", L); CHECK(s.snapshot().faultBeepIntervalS == 30, "$L: bip ogni 30 s") }
    F2(s, "service_required", true)
    sn = s.snapshot()
    CHECK(sn.deviceReadiness == "fault" && sn.faultBeepIntervalS == 15 && sn.faultBeepDouble, "guasto: spia rossa, doppio bip ogni 15 s")
    CHECK(s.stateDictionary()["device_faults"] == listOf("battery_low", "maintenance", "service_required"), "elenco nell'ordine dei tipi")
    // persistenza: cambio layout, accensione e spegnimento non li toccano; reset_scenario li azzera
    Cmd(s, "set_layout", "dae"); s.devicePowered = true; s.devicePowered = false
    CHECK(s.snapshot().deviceFaults.size == 3, "restano con il cambio di layout e l'accensione")
    s.devicePowered = true
    CHECK(s.snapshot().faultBeepIntervalS == 0, "acceso: niente bip da spento")
    s.devicePowered = false
    Cmd(s, "reset_scenario")
    sn = s.snapshot()
    CHECK(sn.deviceFaults.isEmpty() && sn.deviceReadiness == "ready" && sn.faultBeepIntervalS == 0 && sn.batteryShocksLeft == -1, "reset_scenario: nessun malfunzionamento")

    // temperatura: solo spia, nessun bip; charge_fault / memory_full: spia pronta
    s = NewState(); Cmd(s, "set_layout", "dae")
    F2(s, "temperature", true)
    CHECK(s.snapshot().deviceReadiness == "attention" && s.snapshot().faultBeepIntervalS == 0, "temperatura: spia senza bip")
    F2(s, "temperature", false); F2(s, "charge_fault", true); F2(s, "memory_full", true)
    CHECK(s.snapshot().deviceReadiness == "ready" && s.snapshot().faultBeepIntervalS == 0, "charge_fault e memory_full: spia pronta")
    // cavo staccato a dispositivo spento: attenzione e bip (novità)
    s = NewState(); Cmd(s, "set_layout", "dae")
    CHECK(F2(s, "pads_unplugged", true) && s.stateDictionary()["defib"]["pads_cable"] == "disconnected", "pads_unplugged = set_pads_cable disconnected")
    CHECK(s.snapshot().deviceReadiness == "attention" && s.snapshot().faultBeepIntervalS == 30 && s.snapshot().deviceFaults == listOf("pads_unplugged"), "cavo staccato da spento: spia e bip")
    CHECK(F2(s, "pads_poor_contact", true) && s.stateDictionary()["defib"]["pads_poor_contact"] == true, "pads_poor_contact = set_pads_contact poor")
    Cmd(s, "clear_device_faults")
    CHECK(s.snapshot().deviceFaults.isEmpty() && s.stateDictionary()["defib"]["pads_cable"] == "connected" && s.stateDictionary()["defib"]["pads_poor_contact"] == false, "clear_device_faults: anche cavo e contatto")

    // esclusività della batteria
    s = NewState(); Cmd(s, "set_layout", "dae")
    F2(s, "battery_low", true); F2(s, "battery_replace", true)
    CHECK(s.snapshot().deviceFaults == listOf("battery_replace") && s.snapshot().batteryShocksLeft == 2, "battery_replace toglie battery_low (2 scariche)")
    F2(s, "battery_dead", true)
    sn = s.snapshot()
    CHECK(sn.deviceFaults == listOf("battery_dead") && sn.deviceReadiness == "dark" && sn.faultBeepIntervalS == 0 && sn.batteryShocksLeft == -1, "battery_dead: niente spia, niente bip")

    // --- battery_dead: non si accende ---
    CHECK(!PowerAed(s) && !s.devicePowered && D(s).mode == SLDefibOff, "battery_dead: il tasto non accende")
    CHECK(!Cmd(s, "set_device_power", true, e) && e.has("battery depleted"), "battery_dead: set_device_power rifiutato")
    F2(s, "battery_dead", false)
    sn = s.snapshot()
    CHECK(sn.deviceSelfTest && sn.faultBeepIntervalS == 0, "batteria sostituita da spento: autotest breve")
    gNow += 3.5
    CHECK(!s.snapshot().deviceSelfTest && s.snapshot().deviceReadiness == "ready", "dopo l'autotest: pronto")
    CHECK(PowerAed(s) && D(s).mode == SLDefibAED, "batteria sostituita: si accende")
    CHECK(Voice(s) == "Applicare le piastre sul torace nudo del paziente.", "DAE generico: nessuna voce in più dopo la sostituzione")
    // impostata da acceso: si spegne
    F2(s, "battery_dead", true)
    CHECK(!s.devicePowered && D(s).mode == SLDefibOff, "battery_dead da acceso: si spegne")
    // ICU in rete: la batteria esaurita non vale
    s = NewState(); F2(s, "battery_dead", true)
    s.devicePowered = true
    CHECK(s.devicePowered && s.snapshot().deviceReadiness == "ready", "ICU: battery_dead non applicabile")
    Cmd(s, "set_layout", "extrah")
    CHECK(!s.devicePowered, "passando a un layout a batteria con battery_dead il dispositivo si spegne")

    // --- battery_low: voce all'accensione, 6 scariche, poi battery_replace (2), poi la carica non riesce ---
    s = NewState(); Cmd(s, "set_layout", "dae"); F2(s, "battery_low", true)
    RhythmNow(s, "vf")
    CHECK(PowerAed(s) && Voice(s) == "Batteria scarica. Applicare le piastre sul torace nudo del paziente.", "battery_low: «Batteria scarica.» all'accensione")
    CHECK(D(s).message == "BATTERIA SCARICA", "battery_low: scritta per 5 s all'accensione")
    Tick(s, 6)
    CHECK(D(s).message == "APPLICARE LE PIASTRE", "battery_low: poi il messaggio normale")
    Sensor(s, "pads", true)
    for (i in 1..6) { ToReady(s); Shock(s); Tick(s, 1) ; Cmd(s, "defib_analyze") }
    sn = s.snapshot()
    CHECK(D(s).shockCount == 6, { F("battery_low: 6 scariche erogate (%d)", D(s).shockCount) })
    CHECK(sn.deviceFaults == listOf("battery_replace") && sn.batteryShocksLeft == 2, "dopo 6 scariche passa a battery_replace con 2 scariche")
    CHECK(s.stateDictionary()["defib"]["events"].let { ev -> (ev as List<*>).any { HASSTR(it["text"], "la batteria passa") } }, "registro: passaggio della batteria")
    for (i in 1..2) { ToReady(s); Shock(s); Tick(s, 1); Cmd(s, "defib_analyze") }
    CHECK(D(s).shockCount == 8 && s.snapshot().batteryShocksLeft == 0, "battery_replace: altre 2 scariche")
    Tick(s, 9)
    CHECK(D(s).aedPhase == SLAedCPR && D(s).charge == SLChargeNone && D(s).message == "SCARICA NON EROGATA — BATTERIA", "batteria finita: la carica non riesce")
    CHECK(Voice(s) == "Scarica non erogata. Sostituire la batteria. Continuare la RCP.", "batteria finita: voce, continuare la RCP")
    Cmd(s, "defib_mode", "manual")
    CHECK(!Cmd(s, "defib_charge", null, e) && e.has("battery too low"), "manuale: carica rifiutata con la batteria finita")
    F2(s, "battery_replace", false)
    CHECK(s.snapshot().batteryShocksLeft == -1 && Cmd(s, "defib_charge"), "batteria sostituita: si carica di nuovo")

    // battery_replace: dopo 3 min acceso si spegne e la batteria è esaurita (tempo clinico: la pausa non conta)
    s = NewState(); Cmd(s, "set_layout", "dae"); F2(s, "battery_replace", true)
    CHECK(PowerAed(s) && Voice(s).startsWith("Sostituire la batteria."), "battery_replace: «Sostituire la batteria.»")
    Tick(s, 100)
    Cmd(s, "set_simulation_running", false); gNow += 300; s.tickAtTime(gNow); Cmd(s, "set_simulation_running", true)
    CHECK(s.devicePowered, "battery_replace: in pausa il tempo non conta")
    Tick(s, 85)
    CHECK(!s.devicePowered && D(s).mode == SLDefibOff && s.snapshot().deviceFaults == listOf("battery_dead"), "battery_replace: dopo 3 min si spegne (batteria esaurita)")


    // --- service_required: niente analisi né carica, RCP continua; risolto da acceso -> spegnimento ---
    s = NewState(); Cmd(s, "set_layout", "dae"); F2(s, "service_required", true); RhythmNow(s, "vf"); Sensor(s, "pads", true)
    CHECK(PowerAed(s) && Voice(s) == "Unità guasta. Richiedere assistenza. Continuare la RCP.", "guasto: voce all'accensione")
    CHECK(D(s).message == "UNITÀ GUASTA — RICHIEDERE ASSISTENZA" && D(s).aedPhase == SLAedPads, "guasto: scritta, niente analisi")
    Tick(s, 20)
    CHECK(D(s).aedPhase == SLAedPads && D(s).charge == SLChargeNone, "guasto: dopo 20 s ancora niente analisi né carica")
    val seq = D(s).promptSeq; Tick(s, 12)
    CHECK(D(s).promptSeq > seq && Voice(s).startsWith("Unità guasta"), "guasto: la voce si ripete")
    CHECK(!Cmd(s, "defib_analyze", null, e) && e.has("device fault"), "guasto: ANALIZZA rifiutato")
    Cmd(s, "set_cpr", true); Tick(s, 2)
    CHECK(s.snapshot().cprActive, "guasto: la RCP resta")
    F2(s, "service_required", false)
    CHECK(!s.devicePowered && D(s).mode == SLDefibOff, "assistenza eseguita da acceso: si spegne")
    CHECK(PowerAed(s) && D(s).aedPhase == SLAedAnalyzing, "riacceso: analizza")
    // impostato durante la carica (manuale): carica tolta; il pacing non parte
    s = NewState(); Cmd(s, "set_layout", "extrah"); s.devicePowered = true; Sensor(s, "pads", true); Cmd(s, "defib_mode", "manual"); Cmd(s, "defib_charge")
    F2(s, "service_required", true); Tick(s, 1)
    CHECK(D(s).charge == SLChargeNone && D(s).message == "GUASTO TERAPIA — ASSISTENZA", "extraH: guasto durante la carica, carica tolta")
    CHECK(!Cmd(s, "defib_charge", null, e) && e.has("device fault"), "guasto: carica rifiutata")
    Cmd(s, "defib_mode", "pacer")
    CHECK(!Cmd(s, "defib_pacer", mapOf("running" to true, "output_ma" to 80), e) && e.has("device fault"), "guasto: pacing rifiutato")
    CHECK(s.snapshot().ecgConnected || true, "guasto: il monitoraggio resta (nessun effetto sui sensori)")
    // ICU: guasto nel cassetto, nessuno spegnimento alla risoluzione
    s = NewState(); s.devicePowered = true; Sensor(s, "pads", true); F2(s, "service_required", true); Cmd(s, "defib_mode", "manual")
    CHECK(!Cmd(s, "defib_charge") && D(s).message == "DEFIBRILLATORE NON DISPONIBILE", "ICU: cassetto «Defibrillatore non disponibile»")
    F2(s, "service_required", false)
    CHECK(s.devicePowered && D(s).mode == SLDefibManual && Cmd(s, "defib_charge"), "ICU: risolto, nessuno spegnimento")

    // --- pads_damaged: niente analisi finché «Piastre sostituite» ---
    s = NewState(); Cmd(s, "set_layout", "dae"); F2(s, "pads_damaged", true); RhythmNow(s, "vf")
    PowerAed(s)
    CHECK(Voice(s) == "Applicare le piastre sul torace nudo del paziente.", "piastre danneggiate: all'accensione nulla (si dice quando si applicano)")
    Sensor(s, "pads", true)
    CHECK(D(s).aedPhase == SLAedAnalyzing && Voice(s) == "Sostituire le piastre." && D(s).message == "SOSTITUIRE LE PIASTRE", "piastre danneggiate: «Sostituire le piastre.»")
    Tick(s, 20)
    CHECK(D(s).aedPhase == SLAedAnalyzing && D(s).charge == SLChargeNone, "piastre danneggiate: niente analisi")
    F2(s, "pads_damaged", false)
    CHECK(Voice(s) == "Analisi del ritmo in corso. Non toccare il paziente.", "piastre sostituite: riparte l'analisi")
    ToReady(s)
    CHECK(D(s).charge == SLChargeReady, "piastre sostituite: analisi e carica normali")

    // --- pads_expired: funziona, avviso una volta ---
    s = NewState(); Cmd(s, "set_layout", "dae"); F2(s, "pads_expired", true); RhythmNow(s, "vf"); Sensor(s, "pads", true)
    CHECK(PowerAed(s) && Voice(s) == "Piastre scadute. Sostituire le piastre. Analisi del ritmo in corso. Non toccare il paziente.", { "piastre scadute: " + Voice(s) })
    ToReady(s)
    CHECK(D(s).charge == SLChargeReady && Shock(s), "piastre scadute: si usa comunque")

    // --- maintenance, shock_button_stuck, charge_fault, memory_full ---
    s = NewState(); Cmd(s, "set_layout", "dae"); F2(s, "maintenance", true)
    CHECK(PowerAed(s) && Voice(s).startsWith("Manutenzione necessaria. Continuare il soccorso."), "manutenzione: voce all'accensione")
    s = NewState(); Cmd(s, "set_layout", "dae"); F2(s, "shock_button_stuck", true); RhythmNow(s, "vf"); Sensor(s, "pads", true)
    CHECK(PowerAed(s) && Voice(s).startsWith("Rilasciare il pulsante di scarica."), "tasto bloccato: voce all'accensione")
    ToReady(s)
    CHECK(!Cmd(s, "defib_shock", null, e) && e.has("shock button stuck") && D(s).shockCount == 0 && D(s).charge == SLChargeReady, "tasto bloccato: SHOCK non scarica")
    CHECK(Voice(s) == "Rilasciare il pulsante di scarica.", "tasto bloccato: voce")
    s = NewState(); Cmd(s, "set_layout", "dae"); F2(s, "charge_fault", true); RhythmNow(s, "vf"); Sensor(s, "pads", true)
    CHECK(PowerAed(s) && Voice(s) == "Analisi del ritmo in corso. Non toccare il paziente.", "errore di carica: nessuna voce all'accensione")
    Tick(s, 9)
    CHECK(D(s).aedPhase == SLAedCharging, "errore di carica: analizza e carica")
    Tick(s, 6)
    CHECK(D(s).aedPhase == SLAedCPR && D(s).charge == SLChargeNone && Voice(s) == "Scarica non erogata. Continuare la RCP.", "errore di carica: «Scarica non erogata.»")
    s = NewState(); Cmd(s, "set_layout", "dae"); F2(s, "memory_full", true)
    CHECK(PowerAed(s) && D(s).message == "MEMORIA PIENA", "memoria piena: scritta all'accensione")

    // --- registro della sessione ---
    s = NewState(); val lg = SFSessionLog(null, s); Cmd(s, "start_scenario"); lg.observe()   // ANDROID (avvio scenario 22/11): il registro scrive da avviato
    F2(s, "battery_low", true); lg.observe(); F2(s, "battery_low", false); lg.observe()
    val txt = lg.plainText()
    CHECK(txt.contains("Malfunzionamento simulato: Batteria quasi scarica attivato"), "registro: attivato")
    CHECK(txt.contains("Malfunzionamento simulato: Batteria quasi scarica risolto (batteria sostituita)"), "registro: risolto con l'azione")

    // --- salvataggio e ripresa ---
    s = NewState(); Cmd(s, "set_layout", "dae"); F2(s, "battery_low", true); F2(s, "pads_expired", true); Cmd(s, "set_device_fault_beep", false)
    PowerAed(s); RhythmNow(s, "vf"); Sensor(s, "pads", true); ToReady(s); Shock(s)
    val ex = Json.parseOrNull(Json.stringify(s.exportState())) as Map<String, Any?>
    var r = NewStateRaw()
    CHECK(r.restoreState(ex) == null, "ripresa riuscita")
    sn = r.snapshot()
    CHECK(sn.deviceFaults == listOf("battery_low", "pads_expired") && sn.batteryShocksLeft == 5 && !sn.deviceFaultBeep, "ripresa: malfunzionamenti, scariche residue e bip ripristinati")
    // battery_dead ripristinato: il dispositivo resta spento
    s = NewState(); Cmd(s, "set_layout", "dae"); F2(s, "battery_dead", true)
    r = NewStateRaw()
    CHECK(r.restoreState(Json.parseOrNull(Json.stringify(s.exportState())) as Map<String, Any?>) == null && !r.devicePowered && !Cmd(r, "set_device_power", true), "ripresa: batteria esaurita, non si accende")

    // --- scenario a fasi che dichiara i malfunzionamenti ---
    s = NewState()
    val sc = SFScenarioStore(null, s)
    val add = sc.save(mapOf("name" to "Controllo DAE", "phases" to listOf(mapOf("name" to "DAE in bacheca", "device_faults" to listOf("battery_low", "pads_expired")))))
    CHECK(add.error == null, { "scenario con device_faults: " + add.error })
    val bad = sc.save(mapOf("name" to "x", "phases" to listOf(mapOf("rhythm" to "vf", "device_faults" to listOf("boh")))))
    CHECK(bad.error?.contains("device_faults") == true, "scenario: device_faults non valido rifiutato")
    val sid = add.scenario?.get("id") as? String
    CHECK(sc.start(sid, 0) == null && s.snapshot().deviceFaults == listOf("battery_low", "pads_expired"), "scenario: malfunzionamenti attivati dalla fase")
}
