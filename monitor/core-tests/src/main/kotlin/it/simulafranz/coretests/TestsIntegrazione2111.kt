// ANDROID (funzioni 21/11): test dell'integrazione nel monitor delle nuove funzioni del cuore (NUOVE_FUNZIONI_CUORE.md):
// artefatto della falsa cattura e dell'ambulanza nel motore delle curve, comando end_scenario.
package it.simulafranz.coretests

import it.simulafranz.core.*
import java.io.File

// ampiezza massima |ECG| fra t0 e t0 + secs (campioni ogni 5 ms, tempi crescenti come sul monitor)
private fun peak(e: WaveformEngine, t0: Double, secs: Double, lead: Int): Double {
    var m = 0.0; var t = t0
    while (t < t0 + secs) { m = Math.max(m, Math.abs(e.ecgAt(t, lead))); t += 0.005 }
    return m
}

fun testIntegrazione2111() {
    // --- falsa cattura: spike e grosso artefatto dopo ogni spike, nessun QRS stimolato ---
    var s = NewState(); Sensor(s, "pads", true); RhythmNow(s, "asystole"); Cmd(s, "defib_mode", "pacer")
    Cmd(s, "defib_pacer", mapOf("rate" to 70, "output_ma" to 100, "demand" to false, "running" to true))
    val eCap = WaveformEngine(7); eCap.updateWithSnapshot(s.snapshot())
    Cmd(s, "set_defib_config", mapOf("false_capture" to true))
    CHECK(s.snapshot().defib.paceFalseCapture && !s.snapshot().defib.paceCapture, "falsa cattura: snapshot")
    val eFalse = WaveformEngine(7); eFalse.updateWithSnapshot(s.snapshot())
    val pk = peak(eFalse, 10.0, 3.0, SLLeadII)
    CHECK(pk > 0.9, { F("falsa cattura: grosso artefatto dopo lo spike (%.2f mV)", pk) })
    CHECK(eFalse.markersFrom(10.0, 13.0, false) and SLMarkerPace != 0, "falsa cattura: spike del pacemaker visibili")
    CHECK(eFalse.lastBeatAtOrBefore(13.0) < 0, "falsa cattura: nessun QRS catturato (nessun battito programmato)")
    peak(eCap, 10.0, 3.0, SLLeadII)
    CHECK(eCap.lastBeatAtOrBefore(13.0) > 0, "cattura vera: QRS stimolati programmati (confronto)")
    CHECK(eFalse.plethAt(13.5) == 0.0, "falsa cattura: nessuna onda di polso")
    // il pacing senza cattura sotto soglia resta quello di prima (artefatto piccolo)
    Cmd(s, "set_defib_config", mapOf("false_capture" to false))
    Cmd(s, "defib_pacer", mapOf("output_ma" to 30))
    val eLow = WaveformEngine(7); eLow.updateWithSnapshot(s.snapshot())
    CHECK(!s.snapshot().defib.paceFalseCapture && peak(eLow, 10.0, 3.0, SLLeadII) < pk * 0.5, "sotto soglia: artefatto piccolo come prima")

    // --- ambulanza in movimento: artefatto da movimento sulle curve, anche senza polso ---
    s = NewState(); RhythmNow(s, "vf")
    val eStill = WaveformEngine(7); eStill.updateWithSnapshot(s.snapshot())
    Cmd(s, "vehicle_motion", true)
    val eMove = WaveformEngine(7); eMove.updateWithSnapshot(s.snapshot())
    var maxd = 0.0; for (i in 0 until 600) { val t = 10 + i * 0.01; maxd = Math.max(maxd, Math.abs(eMove.ecgAt(t, SLLeadII) - eStill.ecgAt(t, SLLeadII))) }
    CHECK(maxd > 0.3, { F("ambulanza: artefatto sull'ECG anche in FV (%.2f mV)", maxd) })
    s = NewState()
    val pStill = WaveformEngine(7); pStill.updateWithSnapshot(s.snapshot())
    Cmd(s, "vehicle_motion", "on")
    val pMove = WaveformEngine(7); pMove.updateWithSnapshot(s.snapshot())
    maxd = 0.0; for (i in 0 until 600) { val t = 10 + i * 0.01; maxd = Math.max(maxd, Math.abs(pMove.plethAt(t) - pStill.plethAt(t))) }
    CHECK(maxd > 0.1, { F("ambulanza: artefatto sulla pletismografia (%.2f)", maxd) })

    // --- end_scenario ---
    s = NewState(); Cmd(s, "reset_clock")
    CHECK(s.stateDictionary()["scenario_ended"] == false, "fine scenario: falso all'inizio")
    val sc = SFScenarioStore(null, s)
    val lg = SFSessionLog(null, s); lg.observe()
    val api = APIController(s, { "482913" }, emptyMap()); api.clock = { gNow }; api.sessionLog = lg; api.scenarios = sc
    api.handle(CReq("POST", "/api/v1/control", "{\"action\":\"take\"}", "web-A", "Web%20A", null))
    CHECK(sc.start("es-arresto-fv", 0) == null && BOOL(sc.runInfo()["active"]), "fine scenario: scenario avviato")
    RhythmNow(s, "vf"); Tick(s, 65)
    val r = api.handle(CReq("POST", "/api/v1/command", "{\"command\":\"end_scenario\"}", "web-A", "Web%20A", null))
    lg.observe()
    var st = s.stateDictionary()
    CHECK(r.status == 200 && BOOL(st["scenario_ended"]) && !BOOL(st["simulation_running"]) && s.snapshot().scenarioEnded, "fine scenario: in pausa e segnato")
    CHECK(!BOOL(sc.runInfo()["active"]), "fine scenario: lo scenario a fasi è chiuso")
    val last = lg.entriesSince(0).lastOrNull { HASSTR(it["text"], "Fine scenario") }
    CHECK(last != null && last["tag"] == "scenario_end" && HASSTR(last["text"], "timer 01:05") && HASSTR(last["text"], "ore "),
          { "fine scenario: voce nel registro con orario e timer (${last?.get("text")})" })
    CHECK(!LogHas(lg, "Scenario in pausa"), "fine scenario: niente voce «in pausa» in più")
    val tScen = INT(st["scenario_time_s"]); val arrest = INT(st["cpr_metrics"]["arrest_s"])
    Tick(s, 30)
    st = s.stateDictionary()
    CHECK(INT(st["scenario_time_s"]) == tScen && INT(st["cpr_metrics"]["arrest_s"]) == arrest, "fine scenario: timer e paziente fermi (pausa vera)")
    // salvataggio/ripristino: la fine resta
    val s2 = NewStateRaw(); s2.restoreState(Json.parse(Json.stringify(s.exportState())) as Map<String, Any?>)
    CHECK(s2.snapshot().scenarioEnded && !s2.snapshot().simulationRunning, "fine scenario: conservata dal ripristino")
    // si azzera con la ripresa, con un nuovo avvio, con il reset
    Cmd(s, "set_simulation_running", true)
    CHECK(!s.snapshot().scenarioEnded && s.snapshot().simulationRunning, "fine scenario: la ripresa la toglie")
    Cmd(s, "end_scenario"); Cmd(s, "end_scenario")
    CHECK(s.snapshot().scenarioEnded && !s.snapshot().simulationRunning, "fine scenario: ripetuto, nessun errore")
    sc.start("es-arresto-fv", 0)
    CHECK(!s.snapshot().scenarioEnded, "fine scenario: un nuovo avvio la toglie")
    Cmd(s, "end_scenario"); Reset(s)
    CHECK(!s.snapshot().scenarioEnded && s.snapshot().simulationRunning, "fine scenario: il reset la toglie")

}
