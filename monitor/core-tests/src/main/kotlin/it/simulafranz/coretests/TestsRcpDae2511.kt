// similFranz Monitor — test del 25/11: stanchezza di chi comprime allineata ai 2 minuti del DAE (anche con il polso),
// peggioramento automatico senza cambio, «Movimento rilevato» solo dopo l'invito a sospendere la RCP, esito della scarica
// che si chiude da solo dopo 10 s, tempi nel registro e scarica con un operatore a contatto. In TUTTI i layout disponibili.
package it.simulafranz.coretests

import it.simulafranz.core.*

private fun ev(s: SimulationState, needle: String): Boolean =
    (s.stateDictionary()["defib"]["events"] as? List<*>)?.any { (it["text"] as? String)?.contains(needle) == true } == true

fun testRcpDae2511() {
    // ---- stanchezza: piena a 2 min di compressioni, superficiale da sola, a 4 min anche il rilascio; il cambio la azzera
    run {
        val s = NewState(); RhythmNow(s, "vf"); Tick(s, 5)
        Cmd(s, "set_cpr", true); Tick(s, 60)
        var d = s.stateDictionary()
        CHECK(DBL(d["cpr_fatigue"]) in 0.45..0.55 && d["cpr_depth_quality"] == "ok") { F("stanchezza a 1 min: %.2f", DBL(d["cpr_fatigue"])) }
        Tick(s, 61); d = s.stateDictionary()
        CHECK(DBL(d["cpr_fatigue"]) == 1.0 && d["cpr_depth_quality"] == "shallow" && d["cpr_recoil_quality"] == "ok") { F("a 2 min: stanchezza %.2f, profondità %s", DBL(d["cpr_fatigue"]), d["cpr_depth_quality"]) }
        CHECK(ev(s, "compressioni superficiali per stanchezza"), "registro: superficiali per stanchezza")
        Tick(s, 120); d = s.stateDictionary()
        CHECK(d["cpr_depth_quality"] == "shallow" && d["cpr_recoil_quality"] == "incomplete", "a 4 min senza cambio: anche il rilascio incompleto")
        Cmd(s, "cpr_rescuer_change"); d = s.stateDictionary()
        CHECK(DBL(d["cpr_fatigue"]) == 0.0 && d["cpr_depth_quality"] == "ok" && d["cpr_recoil_quality"] == "ok", "cambio dell'operatore: tutto torna come impostato")
    }
    // ---- con un ritmo con polso (RCP dopo la scarica) la stanchezza sale lo stesso; il passaggio ad arresto non la azzera
    run {
        val s = NewState(); RhythmNow(s, "sinus"); Tick(s, 3)
        Cmd(s, "set_cpr", true); Tick(s, 60)
        CHECK(DBL(s.stateDictionary()["cpr_fatigue"]) in 0.45..0.55) { F("RCP su sinusale: stanchezza %.2f dopo 1 min", DBL(s.stateDictionary()["cpr_fatigue"])) }
        RhythmNow(s, "vf"); Tick(s, 1)
        CHECK(DBL(s.stateDictionary()["cpr_fatigue"]) >= 0.45, "il passaggio ad arresto non azzera la stanchezza")
    }
    // ---- DAE in ogni layout
    var n = 0
    for (lay in SimulationState.supportedLayouts()) {
        val s = NewState()
        if (!Cmd(s, "set_layout", lay)) continue
        n++
        Cmd(s, "defib_mode", "aed"); RhythmNow(s, "vf"); Sensor(s, "pads", true)
        Tick(s, 20)
        var d = s.snapshot().defib
        if (d.aedPhase != SLAedReady) { CHECK(false) { F("%s: DAE non carico dopo 20 s (fase %d)", lay, d.aedPhase) }; continue }
        Tick(s, 3)
        Cmd(s, "patient_touch", 5); Cmd(s, "defib_shock")
        d = s.snapshot().defib
        CHECK(d.shockCount == 1) { F("%s: scarica", lay) }
        CHECK(ev(s, "SHOCK premuto")) { F("%s: tempo dall'invito alla scarica nel registro %s", lay, (s.stateDictionary()["defib"]["events"] as? List<*>)?.takeLast(5)?.map { it["text"] }) }
        CHECK(ev(s, "operatore a contatto")) { F("%s: scarica con paziente toccato nel registro", lay) }
        CHECK(s.stateDictionary()["defib"]["outcome_pending"] == true) { F("%s: domanda sull'esito aperta", lay) }
        Tick(s, 10.5)
        CHECK(s.stateDictionary()["defib"]["outcome_pending"] == false && s.snapshot().rhythm == "vf" && ev(s, "nessuna scelta in 10 s")) { F("%s: esito chiuso da solo dopo 10 s, resta FV", lay) }
        // ciclo di RCP: 2 min pieni, poi analisi; prima l'invito, il «Movimento rilevato» solo dopo
        Cmd(s, "set_cpr", true)
        Tick(s, 120 - 11.0 - 0.5)
        CHECK(s.snapshot().defib.aedPhase == SLAedCPR) { F("%s: ancora RCP poco prima dei 2 min", lay) }
        Tick(s, 1.5)
        d = s.snapshot().defib
        CHECK(d.aedPhase == SLAedAnalyzing && !HASSTR(d.voicePrompt, "Movimento")) { F("%s: a 2 min analisi con l'invito (voce: %s)", lay, d.voicePrompt) }
        Tick(s, 4)
        CHECK(!HASSTR(s.snapshot().defib.voicePrompt, "Movimento")) { F("%s: niente «Movimento rilevato» mentre si dice di sospendere", lay) }
        Tick(s, 3)
        CHECK(HASSTR(s.snapshot().defib.voicePrompt, "Movimento rilevato")) { F("%s: RCP che continua dopo l'invito = movimento rilevato (%s)", lay, s.snapshot().defib.voicePrompt) }
        Cmd(s, "set_cpr", false); RhythmNow(s, "asystole"); Tick(s, 14)
        CHECK(s.snapshot().defib.aedPhase == SLAedCPR) { F("%s: asistolia: shock non consigliato", lay) }
        Tick(s, 4); Cmd(s, "set_cpr", true)
        CHECK(ev(s, "Shock non consigliato: RCP ripresa dopo")) { F("%s: ripresa della RCP dopo il non consigliato nel registro", lay) }
    }
    CHECK(n >= 1) { F("layout provati: %d", n) }
    println("  (DAE 25/11 provato in $n layout)")
}
