// ANDROID (avvio scenario 22/11): macchina a stati dello scenario. PRONTO (monitor vivo, timer fermo a 00:00, registro fermo)
// → start_scenario → in corso ⇄ in pausa → end_scenario (terminato) → reset_scenario → PRONTO.
package it.simulafranz.coretests

import it.simulafranz.core.*

private fun phase(s: SimulationState): Any? = s.stateDictionary()["scenario_phase"]

fun testAvvio2211() {
    // --- PRONTO: stato iniziale ---
    var s = NewState()
    var d = s.stateDictionary()
    CHECK(d["scenario_phase"] == "ready" && d["scenario_started"] == false, "pronto: stato iniziale (scenario_phase ready, scenario_started falso)")
    CHECK(d["simulation_running"] == true && !s.snapshot().scenarioEnded, "pronto: il monitor è vivo (simulation_running vero, nessuna copertura)")
    Tick(s, 30)
    CHECK(s.snapshot().scenarioTime == 0.0 && LONG(s.stateDictionary()["scenario_time_s"]) == 0L, "pronto: il timer resta a 00:00")
    // il paziente si prepara e il dispositivo funziona
    CHECK(Cmd(s, "set_heart_rate", 120) && RhythmNow(s, "vf"), "pronto: si prepara il paziente")
    s.devicePowered = true
    CHECK(Cmd(s, "defib_mode", "manual") && Sensor(s, "pads", true) && Cmd(s, "defib_charge"), "pronto: il defibrillatore funziona (carica)")
    Tick(s, 8)
    CHECK(Cmd(s, "defib_shock") && s.snapshot().defib.shockCount == 1, "pronto: la scarica si eroga (prova del dispositivo)")
    // reset_clock in PRONTO: niente
    CHECK(Cmd(s, "reset_clock"), "pronto: reset_clock accettato")
    Tick(s, 5)
    CHECK(s.snapshot().scenarioTime == 0.0 && phase(s) == "ready", "pronto: reset_clock non fa partire il timer")

    // --- start_scenario ---
    val seq0 = s.scenarioStartSeq
    CHECK(Cmd(s, "start_scenario"), "start_scenario accettato")
    d = s.stateDictionary()
    CHECK(d["scenario_phase"] == "running" && d["scenario_started"] == true && s.scenarioStartSeq == seq0 + 1, "avvio: in corso, scenario_started vero")
    CHECK(INT(d["cpr_metrics"]["arrest_s"]) == 0, "avvio: le metriche RCP partono da zero (le prove in PRONTO non contano)")
    Tick(s, 12)
    CHECK(Math.abs(s.snapshot().scenarioTime - 12.0) < 0.01, "avvio: il timer corre da 00:00")
    CHECK(Cmd(s, "start_scenario") && s.scenarioStartSeq == seq0 + 1 && Math.abs(s.snapshot().scenarioTime - 12.0) < 0.01, "avvio ripetuto: niente (il timer non si azzera)")

    // --- pausa / ripresa ---
    Cmd(s, "set_simulation_running", false)
    CHECK(phase(s) == "paused" && !s.snapshot().simulationRunning, "pausa: scenario_phase paused")
    Tick(s, 20)
    CHECK(Math.abs(s.snapshot().scenarioTime - 12.0) < 0.01, "pausa: timer fermo")
    Cmd(s, "set_simulation_running", true)
    Tick(s, 3)
    CHECK(phase(s) == "running" && Math.abs(s.snapshot().scenarioTime - 15.0) < 0.01, "ripresa: il timer riprende da dove era")
    // start_scenario in pausa = riprendi (stesso timer)
    Cmd(s, "set_simulation_running", false); Tick(s, 5)
    CHECK(Cmd(s, "start_scenario") && phase(s) == "running" && s.scenarioStartSeq == seq0 + 1, "start_scenario in pausa: riprende")
    // reset_clock in corso: come prima
    Cmd(s, "reset_clock"); Tick(s, 2)
    CHECK(Math.abs(s.snapshot().scenarioTime - 2.0) < 0.01, "in corso: reset_clock azzera il timer come prima")

    // --- fine e azzeramento ---
    Cmd(s, "end_scenario")
    d = s.stateDictionary()
    CHECK(d["scenario_phase"] == "ended" && d["scenario_ended"] == true && d["simulation_running"] == false, "fine: scenario_phase ended")
    Cmd(s, "reset_scenario")
    d = s.stateDictionary()
    CHECK(d["scenario_phase"] == "ready" && d["scenario_started"] == false && d["simulation_running"] == true && LONG(d["scenario_time_s"]) == 0L, "reset_scenario: di nuovo PRONTO a 00:00")
    Tick(s, 10)
    CHECK(s.snapshot().scenarioTime == 0.0, "reset_scenario: timer fermo")

    // --- client vecchio: set_simulation_running true in PRONTO = start_scenario ---
    val seq1 = s.scenarioStartSeq
    CHECK(Cmd(s, "set_simulation_running", true) && phase(s) == "running" && s.scenarioStartSeq == seq1 + 1, "client vecchio: «riprendi» in PRONTO avvia lo scenario")
    Tick(s, 4)
    CHECK(Math.abs(s.snapshot().scenarioTime - 4.0) < 0.01, "client vecchio: il timer corre")
    // pausa in PRONTO (client vecchio, tasto PAUSA del monitor): pausa vera, il timer resta a 00:00, «riprendi» avvia
    Cmd(s, "reset_scenario"); Cmd(s, "set_simulation_running", false); Tick(s, 6)
    CHECK(!s.snapshot().simulationRunning && phase(s) == "ready" && s.snapshot().scenarioTime == 0.0, "pausa in PRONTO: resta PRONTO, timer a 00:00")
    Cmd(s, "set_simulation_running", true); Tick(s, 2)
    CHECK(phase(s) == "running" && Math.abs(s.snapshot().scenarioTime - 2.0) < 0.01, "pausa in PRONTO poi riprendi: avviato")
    // start_scenario dopo la fine (senza azzerare): nuovo avvio da 00:00
    Cmd(s, "end_scenario"); val seq2 = s.scenarioStartSeq
    CHECK(Cmd(s, "start_scenario") && phase(s) == "running" && s.snapshot().scenarioTime == 0.0 && s.scenarioStartSeq == seq2 + 1, "start_scenario dopo la fine: nuovo avvio da 00:00")

    // --- ripresa dopo una chiusura forzata: stesso stato ---
    for (ph in listOf("ready", "running", "paused", "ended")) {
        s = NewState()
        if (ph != "ready") { Cmd(s, "start_scenario"); Tick(s, 7) }
        if (ph == "paused") Cmd(s, "set_simulation_running", false)
        if (ph == "ended") Cmd(s, "end_scenario")
        val ex = Json.parseOrNull(Json.stringify(s.exportState())) as Map<String, Any?>
        val r = NewStateRaw()
        CHECK(r.restoreState(ex) == null && phase(r) == ph, { "ripresa: $ph ripristinato (${phase(r)})" })
        Tick(r, 3)
        val t = r.snapshot().scenarioTime
        val want = when (ph) { "ready" -> 0.0; "running" -> 10.0; else -> 7.0 }
        CHECK(Math.abs(t - want) < 0.01, { "ripresa: $ph, timer $t invece di $want" })
    }
    // salvataggio della versione di prima (senza "started"): avviato
    s = NewState(); Cmd(s, "start_scenario"); Tick(s, 5)
    @Suppress("UNCHECKED_CAST") val ex0 = Json.parseOrNull(Json.stringify(s.exportState())) as MutableMap<String, Any?>
    @Suppress("UNCHECKED_CAST") (ex0["sim"] as MutableMap<String, Any?>).remove("started")
    val r0 = NewStateRaw()
    CHECK(r0.restoreState(ex0) == null && phase(r0) == "running", "ripresa: salvataggio di prima senza «started» = avviato")

    // --- registro: PRONTO fermo, avvio = sessione nuova con «Scenario avviato» ---
    s = NewState()
    val lg = SFSessionLog(null, s); lg.observe()
    Cmd(s, "start_scenario"); lg.noteCommand(mapOf("command" to "start_scenario"), "istr"); lg.observe()
    lg.add("note", "prima simulazione", null, null)
    val sA = lg.currentSession()
    Cmd(s, "end_scenario"); lg.noteCommand(mapOf("command" to "end_scenario"), "istr")
    // fine → (debriefing) → reset_scenario: sessione nuova e vuota, registro fermo
    Cmd(s, "reset_scenario"); lg.noteCommand(mapOf("command" to "reset_scenario"), "istr"); lg.observe()
    val sB = lg.currentSession()
    s.devicePowered = true; Sensor(s, "spo2", true); lg.observe(); lg.noteCommand(mapOf("command" to "set_spo2", "value" to 90), "istr")
    CHECK(sB == sA + 1 && lg.entriesSince(0, sB).isEmpty(), "registro: dopo l'azzeramento (PRONTO) nessuna voce")
    // avvio rilevato dal giro di observe (es. set_simulation_running true da un client vecchio)
    Cmd(s, "set_simulation_running", true); lg.observe()
    var e = lg.entriesSince(0, sB)
    CHECK(lg.currentSession() == sB && e.size == 1 && HASSTR(e[0]["text"], "Scenario avviato · ore "), { "registro: avvio visto da observe, una sola voce (${e.map { it["text"] }})" })
    lg.observe(); lg.noteCommand(mapOf("command" to "set_simulation_running", "value" to true), "istr")
    CHECK(lg.entriesSince(0, sB).size == 1, "registro: l'avvio non si ripete e i cambi fatti in PRONTO non diventano voci")
    Sensor(s, "ecg", true); lg.observe()
    CHECK(LogHas(lg, "Elettrodi ECG: applicata"), "registro: avviato, le azioni si registrano")
    // avvio con una sessione corrente non vuota (fine senza azzerare, poi Avvia): sessione nuova
    Cmd(s, "end_scenario"); lg.noteCommand(mapOf("command" to "end_scenario"), "istr")
    Cmd(s, "start_scenario"); lg.noteCommand(mapOf("command" to "start_scenario"), "istr")
    CHECK(lg.currentSession() == sB + 1 && lg.entriesSince(0, sB + 1).size == 1, "registro: avvio dopo una fine non azzerata = sessione nuova")
    // ripresa dopo una chiusura forzata in corso: nessuna sessione nuova, nessuna voce di avvio
    val ex1 = Json.parseOrNull(Json.stringify(s.exportState())) as Map<String, Any?>
    val s2 = NewStateRaw(); val lg2 = SFSessionLog(null, s2); lg2.observe()
    s2.restoreState(ex1); lg2.observe(); lg2.observe()
    CHECK(lg2.entriesSince(0).none { it["tag"] == "scenario_start" } && lg2.add("note", "dopo la ripresa", null, null) != null, "registro: ripresa in corso, niente «Scenario avviato» e si scrive")

    // --- scenario a fasi da PRONTO: start_scenario implicito, una sola voce di avvio con il nome ---
    s = NewState()
    val api = APIController(s, { "482913" }, emptyMap())
    api.clock = { gNow }
    val lg3 = SFSessionLog(null, s); api.sessionLog = lg3; api.scenarios = SFScenarioStore(null, s)
    lg3.observe()
    api.handle(CReq("POST", "/api/v1/control", "{\"action\":\"take\"}", "web-A", "Web%20A", null))
    val r = api.handle(CReq("POST", "/api/v1/scenarios", "{\"action\":\"start\",\"id\":\"es-arresto-fv\"}", "web-A", null, null))
    lg3.observe()
    e = lg3.entriesSince(0, lg3.currentSession())
    CHECK(r.status == 200 && phase(s) == "running", "scenario a fasi da PRONTO: avvia anche lo scenario")
    CHECK(e.count { it["tag"] == "scenario_start" } == 1 && HASSTR(e.first()["text"], "Scenario «") && HASSTR(lg3.sessions().last()["title"], "Dolore toracico"),
          { "scenario a fasi da PRONTO: una sola voce di avvio, sessione con il nome (${e.map { it["text"] }})" })
    // API: start_scenario e i campi di stato
    s = NewState()
    val api2 = APIController(s, { "482913" }, emptyMap()); api2.clock = { gNow }
    api2.handle(CReq("POST", "/api/v1/control", "{\"action\":\"take\"}", "web-A", "Web%20A", null))
    val st = J(api2.handle(CReq("GET", "/api/v1/state", null, "web-A", null, null)))["state"]
    CHECK(st["scenario_phase"] == "ready" && st["scenario_started"] == false, "API: /api/v1/state con scenario_phase e scenario_started")
    val rs = api2.handle(CReq("POST", "/api/v1/command", "{\"command\":\"start_scenario\"}", "web-A", null, null))
    CHECK(rs.status == 200 && J(rs)["state"]["scenario_phase"] == "running", "API: start_scenario")
}
