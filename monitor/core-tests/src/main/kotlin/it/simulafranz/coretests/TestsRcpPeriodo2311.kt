// ANDROID (BugFix 23/11, RCP solo se avviata): dopo l'analisi il DAE entra nel suo periodo RCP (invito, metronomo, 2 min)
// ma la RCP è IN CORSO solo con cpr_active (comando set_cpr della regia/controller). Senza: nessun feedback, niente
// «RCP IN CORSO», nessuna compressione nelle metriche, nessuna onda da compressioni; dopo il periodo: nuova analisi.
package it.simulafranz.coretests

import it.simulafranz.core.*

private fun artSpan(s: SimulationState): Double {
    val e = WaveformEngine(3); e.updateWithSnapshot(s.snapshot())
    var mn = 1e9; var mx = -1e9; var t = gNow
    while (t < gNow + 4.0) { val v = e.artAt(t); mn = Math.min(mn, v); mx = Math.max(mx, v); t += 0.004 }
    return mx - mn
}

fun testRcpPeriodo2311() {
    for (rhythm in listOf("vf", "asystole")) {
        val s = NewState(); Sensor(s, "art", true); Sensor(s, "etco2", true)
        Cmd(s, "defib_mode", "aed", null); RhythmNow(s, rhythm, null); Sensor(s, "pads", true)
        Tick(s, 20)
        if (s.snapshot().defib.aedPhase == SLAedReady) { Cmd(s, "defib_shock", null, null); Tick(s, 6) }   // dopo la nota «Shock erogato» (5 s)
        var d = s.snapshot().defib
        CHECK(d.aedPhase == SLAedCPR, "$rhythm: dopo l'analisi il DAE entra nel periodo RCP")
        CHECK(!s.snapshot().cprActive, "$rhythm: periodo RCP senza set_cpr: la RCP NON è in corso")
        CHECK(d.message == "INIZIARE LA RCP") { "$rhythm: a schermo l'invito a iniziare la RCP, non «RCP IN CORSO» (${d.message})" }
        CHECK(HASSTR(d.voicePrompt, "RCP")) { "$rhythm: la voce invita alla RCP (${d.voicePrompt})" }
        // qualità scelta in anticipo dall'istruttore: nessun consiglio finché le compressioni non partono
        Cmd(s, "set_cpr_quality", mapOf("depth" to "shallow"), null); Tick(s, 15)
        d = s.snapshot().defib
        CHECK(d.message == "INIZIARE LA RCP" && !HASSTR(d.voicePrompt, "più forte") && !HASSTR(d.voicePrompt, "efficaci"))
            { "$rhythm: senza compressioni nessun feedback di qualità (${d.message} / ${d.voicePrompt})" }
        CHECK(LONG(s.snapshot().cprMetrics["cpr_s"]) == 0L && LONG(s.snapshot().cprMetrics["ccf_percent"]) == 0L)
            { "$rhythm: metriche: nessun secondo di compressioni (${s.snapshot().cprMetrics})" }
        CHECK(artSpan(s) < 6.0) { F("$rhythm: ART senza onde da compressioni (escursione %.1f)", artSpan(s)) }
        CHECK(s.stateDictionary()["etco2_displayed"] == null) { "$rhythm: senza RCP né ventilazioni nessun numero EtCO2 (${s.stateDictionary()["etco2_displayed"]})" }
        // caso opposto: la regia avvia la RCP durante il periodo → feedback presente
        Cmd(s, "set_cpr", true, null); Tick(s, 2)
        d = s.snapshot().defib
        CHECK(s.snapshot().cprActive && d.message == "SPINGERE PIÙ FORTE") { "$rhythm: RCP avviata: consiglio «Spingere più forte» (${d.message})" }
        Cmd(s, "set_cpr_quality", mapOf("depth" to "ok"), null); Tick(s, 2)
        CHECK(s.snapshot().defib.message == "RCP IN CORSO", "$rhythm: RCP corretta: «RCP IN CORSO»")
        CHECK(LONG(s.snapshot().cprMetrics["cpr_s"]) >= 3L, "$rhythm: le compressioni vere entrano nelle metriche")
        CHECK(artSpan(s) > 10.0) { F("$rhythm: con la RCP l'arteriosa mostra le compressioni (%.1f)", artSpan(s)) }
        // la regia la interrompe: di nuovo solo l'invito
        Cmd(s, "set_cpr", false, null); Tick(s, 1)
        CHECK(s.snapshot().defib.message == "INIZIARE LA RCP", "$rhythm: RCP interrotta nel periodo: di nuovo «INIZIARE LA RCP»")
        // uscita dal periodo RCP: nuova analisi dopo i 2 min, senza che la RCP risulti avviata
        var analyzed = false; var k = 0
        while (k++ < 140 && !analyzed) { Tick(s, 1); analyzed = s.snapshot().defib.aedPhase == SLAedAnalyzing }
        CHECK(analyzed, "$rhythm: dopo i 2 min del periodo RCP il DAE avvia da sé una nuova analisi")
        CHECK(!s.snapshot().cprActive, "$rhythm: dopo la nuova analisi la RCP resta ferma")
    }
}
