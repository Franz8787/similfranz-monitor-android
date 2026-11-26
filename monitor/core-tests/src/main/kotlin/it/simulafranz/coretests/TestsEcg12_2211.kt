// ANDROID (12 derivazioni 22/11): morfologia dell'ECG (ST, blocchi di branca, BAV, elettroliti...), blocchi AV come disturbi
// di conduzione, modello a 12 derivazioni (proiezioni di Einthoven/Goldberger), carta 12D (cattura coerente col tracciato).
package it.simulafranz.coretests

import it.simulafranz.core.*
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.min
import kotlin.math.sqrt

private fun morphCmd(s: SimulationState, v: Any?, err: Err? = null): Boolean = Cmd(s, "set_ecg_morphology", v, err)
private fun model(m: Map<String, Any?>): SLEcgModel = SFEcg12.model(SFEcgMorphology.parse(m, SLEcgMorph.NORMAL).first!!)
// ST (mV) misurato come fa un cardiologo: 60 ms dopo il punto J rispetto alla linea isoelettrica (prima della P)
private fun stAt(md: SLEcgModel, l: Int): Double = md.lead(l, md.jPt * md.qrsK + 0.06, 0.75, true) - md.lead(l, -0.45, 0.75, true)
private fun gs(x: Double, mu: Double, s: Double): Double { val z = (x - mu) / s; return exp(-0.5 * z * z) }
// complesso del monitor PRIMA del lavoro (ecgNarrow di WaveformEngine), per il confronto di retrocompatibilità
private fun legacy(tau: Double, rr: Double, v5: Boolean): Double {
    val qt = 0.40 * sqrt(min(maxOf(rr, 0.25), 2.0)); val pT = -0.16 * min(1.0, sqrt(rr))
    return if (!v5) 0.12 * gs(tau, pT, 0.025) - 0.10 * gs(tau, -0.028, 0.008) + gs(tau, 0.0, 0.010) - 0.25 * gs(tau, 0.026, 0.009) + 0.28 * gs(tau, qt * 0.62, qt * 0.11)
    else 0.09 * gs(tau, pT, 0.024) - 0.08 * gs(tau, -0.024, 0.007) + 1.45 * gs(tau, 0.002, 0.0105) - 0.18 * gs(tau, 0.028, 0.008) + 0.40 * gs(tau, qt * 0.63, qt * 0.12)
}
// motore che ha disegnato `sec` secondi del tracciato (II e V5) aggiornandosi dallo stato
private fun run(s: SimulationState, sec: Double, seed: Int = 21): WaveformEngine {
    val e = WaveformEngine(seed); e.updateWithSnapshot(s.snapshot())
    var t = gNow; val end = gNow + sec; var k = 0
    while (t < end) { e.ecgAt(t, SLLeadII); e.ecgAt(t, SLLeadV); e.plethAt(t); t += 0.004; k++; if (k % 125 == 0) { gNow = t; s.tickAtTime(gNow); e.updateWithSnapshot(s.snapshot()) } }
    gNow = end
    return e
}
private fun events(s: SimulationState): List<String> = ((s.stateDictionary()["defib"]["events"] as? List<*>) ?: emptyList<Any?>()).map { STR(it["text"]) ?: "" }

fun testEcg12_2211() {
    // --- retrocompatibilità: senza morfologia II e V5 sono identici al monitor di prima ---
    run {
        val md = SFEcg12.model(SLEcgMorph.NORMAL)
        var maxd = 0.0
        for (rr in listOf(0.4, 0.75, 1.0, 1.5)) { var tau = -0.5; while (tau < 0.9) { maxd = maxOf(maxd, abs(md.lead(SL12II, tau, rr, true) - legacy(tau, rr, false)), abs(md.lead(SL12V5, tau, rr, true) - legacy(tau, rr, true))); tau += 0.002 } }
        CHECK(maxd < 1e-9) { F("morfologia normale: II e V5 uguali al tracciato di prima (scarto max %.2e mV)", maxd) }
        val m = md.measures(0.75, true)
        CHECK(m.prMs!! in 120..200 && m.qrsMs in 80..100 && m.qtcMs in 360..440 && m.axisQrs in 30..75) { F("misure normali: PR %d QRS %d QTc %d asse %d", m.prMs, m.qrsMs, m.qtcMs, m.axisQrs) }
    }

    // --- comando e stato ---
    var s = NewState()
    CHECK(s.stateDictionary()["ecg_morphology"]["st"] == "none" && s.stateDictionary()["av_block_active"] == false, "di serie: morfologia normale, nessun blocco")
    val err = Err()
    CHECK(!morphCmd(s, mapOf("st" to "stemi_marte"), err) && err.has("st must be one of"), "st sconosciuto rifiutato")
    CHECK(!morphCmd(s, mapOf("colore" to "rosso"), err) && err.has("unknown field"), "campo sconosciuto rifiutato")
    CHECK(!morphCmd(s, mapOf("bbb" to "lbbb", "extra" to listOf("wpw")), err) && err.has("wpw"), "WPW con blocco di branca rifiutato")
    CHECK(!morphCmd(s, mapOf("av_block" to "first", "extra" to listOf("wpw")), err) && err.has("wpw"), "WPW con BAV rifiutato")
    CHECK(!morphCmd(s, mapOf("extra" to listOf("hyperkalemia", "hypokalemia")), err) && err.has("mutually exclusive"), "iper- e ipokaliemia insieme rifiutate")
    CHECK(!morphCmd(s, mapOf("st" to "stemi_anterior", "st_mm" to 20), err) && err.has("st_mm"), "st_mm fuori intervallo")
    CHECK(!morphCmd(s, mapOf("av_block" to "first", "pr_ms" to 150), err) && err.has("pr_ms"), "pr_ms fuori intervallo")
    CHECK(!morphCmd(s, 5, err), "valore non oggetto rifiutato")
    CHECK(morphCmd(s, mapOf("st" to "stemi_inferior", "bbb" to "rbbb", "extra" to listOf("lvh"))), "combinazione STEMI + BBD + IVS accettata")
    var em = s.stateDictionary()["ecg_morphology"]
    CHECK(em["st"] == "stemi_inferior" && em["bbb"] == "rbbb" && HASOBJ(em["extra"], "lvh") && HASSTR(em["label"], "STEMI inferiore"), "stato ecg_morphology con etichetta")
    CHECK(events(s).any { it.startsWith("ECG: STEMI inferiore") }, "registro: «ECG: …» per il debriefing")
    CHECK(morphCmd(s, mapOf("bbb" to "none")) && s.stateDictionary()["ecg_morphology"]["st"] == "stemi_inferior", "aggiornamento parziale: gli altri campi restano")
    CHECK(morphCmd(s, "normal") && s.snapshot().ecgMorph.isNormal, "\"normal\" azzera tutto")
    morphCmd(s, mapOf("st" to "pericarditis", "interpretation" to false))
    CHECK(!s.snapshot().ecgMorph.interpretation, "interpretazione disattivabile")
    // la morfologia resta col cambio di ritmo (substrato) e si azzera col reset
    morphCmd(s, mapOf("st" to "stemi_anterior", "interpretation" to true))
    RhythmNow(s, "vf"); RhythmNow(s, "sinus")
    CHECK(s.snapshot().ecgMorph.st == "stemi_anterior", "lo STEMI resta dopo FV e ROSC")
    val ex = s.exportState(); val s2 = NewState(); s2.restoreState(ex)
    CHECK(s2.snapshot().ecgMorph.st == "stemi_anterior", "morfologia salvata e ripristinata (ripartenza)")
    Reset(s)
    CHECK(s.snapshot().ecgMorph.isNormal, "reset_scenario: morfologia normale")
    // transizione / fase di scenario con la morfologia
    CHECK(Cmd(s, "transition", mapOf("ecg_morphology" to mapOf("st" to "stemi_lateral"))) && s.snapshot().ecgMorph.st == "stemi_lateral", "transition con ecg_morphology")
    CHECK(s.validateTransition(mapOf("ecg_morphology" to mapOf("st" to "nulla"))) != null, "validateTransition rifiuta una morfologia sbagliata")
    Cmd(s, "transition", mapOf("rhythm" to "afib", "ecg_morphology" to "normal"))
    CHECK(s.snapshot().rhythmOnset != null && s.snapshot().ecgMorph.st == "stemi_lateral", "con un preludio la morfologia arriva insieme al ritmo")
    Tick(s, 8.0)
    CHECK(s.snapshot().rhythm == "afib" && s.snapshot().ecgMorph.isNormal, "…e arriva all'arrivo del ritmo")

    // --- ST per territorio (con speculari) ---
    run {
        val inf = model(mapOf("st" to "stemi_inferior"))
        CHECK(stAt(inf, SL12III) > 0.2 && stAt(inf, SL12II) > 0.15 && stAt(inf, SL12aVF) > 0.15 && stAt(inf, SL12III) > stAt(inf, SL12II), "STEMI inferiore: ST su in II, III, aVF (III > II)")
        CHECK(stAt(inf, SL12aVL) < -0.1 && stAt(inf, SL12I) < 0, "STEMI inferiore: speculare in aVL e I")
        val ant = model(mapOf("st" to "stemi_anterior"))
        CHECK(stAt(ant, SL12V2) > 0.25 && stAt(ant, SL12V3) > 0.25 && stAt(ant, SL12aVL) > 0.05 && stAt(ant, SL12III) < -0.08, "STEMI anteriore: V2-V3 su, aVL su, III giù")
        val lat = model(mapOf("st" to "stemi_lateral"))
        CHECK(stAt(lat, SL12I) > 0.1 && stAt(lat, SL12aVL) > 0.1 && stAt(lat, SL12V6) > 0.15 && stAt(lat, SL12III) < -0.08, "STEMI laterale: I, aVL, V6 su; III giù")
        val post = model(mapOf("st" to "stemi_posterior"))
        var mx = -9.0; var mn = 9.0; var tq = -0.05; while (tq < 0.05) { val x = post.lead(SL12V1, tq, 0.75, true); mx = maxOf(mx, x); mn = minOf(mn, x); tq += 0.002 }
        CHECK(stAt(post, SL12V2) < -0.15 && mx > -mn, "STEMI posteriore: ST giù in V1-V3 con R alta in V1")
        val ns = model(mapOf("st" to "nstemi_depression"))
        CHECK(stAt(ns, SL12V5) < -0.1 && stAt(ns, SL12aVR) > 0.05, "NSTEMI: ST giù in V5, su in aVR")
        val lm = model(mapOf("st" to "left_main"))
        CHECK(stAt(lm, SL12aVR) > 0.15 && stAt(lm, SL12aVR) > stAt(lm, SL12V1), "tronco comune: aVR su più di V1")
        val per = model(mapOf("st" to "pericarditis"))
        val prII = per.lead(SL12II, -0.065, 0.75, true) - per.lead(SL12II, -0.45, 0.75, true)
        CHECK(stAt(per, SL12II) > 0.08 && stAt(per, SL12V5) > 0.08 && stAt(per, SL12aVR) < -0.05 && prII < -0.03, "pericardite: ST diffuso su, aVR giù, PR sottoslivellato")
        val big = model(mapOf("st" to "stemi_inferior", "st_mm" to 6))
        CHECK(stAt(big, SL12III) > 1.6 * stAt(inf, SL12III), "st_mm cambia l'entità del sopraslivellamento")
        // Einthoven / Goldberger in tutte le morfologie: III = II − I, aVR + aVL + aVF = 0
        var bad = 0
        for (st in SLEcgStValues) for (b in SLEcgBbbValues) {
            val md = model(mapOf("st" to st, "bbb" to b))
            var tau = -0.4
            while (tau < 0.7) { val i = md.lead(SL12I, tau, 0.8, true); val ii = md.lead(SL12II, tau, 0.8, true)
                if (abs(md.lead(SL12III, tau, 0.8, true) - (ii - i)) > 1e-9 || abs(md.lead(SL12aVR, tau, 0.8, true) + md.lead(SL12aVL, tau, 0.8, true) + md.lead(SL12aVF, tau, 0.8, true)) > 1e-9) bad++
                tau += 0.01 }
        }
        CHECK(bad == 0) { "leggi di Einthoven e Goldberger rispettate in tutte le combinazioni ST × branca ($bad scarti)" }
    }

    // --- blocchi di branca, emiblocco, WPW, elettroliti, QT ---
    run {
        val r = model(mapOf("bbb" to "rbbb")); val l = model(mapOf("bbb" to "lbbb"))
        CHECK(r.measures(0.75, true).qrsMs >= 120 && l.measures(0.75, true).qrsMs >= 130, "BBD e BBS: QRS largo (≥ 120 / ≥ 130 ms)")
        CHECK(r.lead(SL12V1, 0.062, 0.75, true) > 0.4 && r.lead(SL12V6, 0.062, 0.75, true) < -0.1 && r.lead(SL12V1, 0.30, 0.75, true) < 0, "BBD: R' in V1, S larga in V6, T negativa in V1")
        CHECK(l.lead(SL12V1, 0.02, 0.75, true) < -1.0 && l.lead(SL12V6, -0.026, 0.75, true) > 0 && stAt(l, SL12V2) > 0.1 && stAt(l, SL12V6) < -0.05,
              "BBS: QS profonda in V1, niente q in V6, ST-T discordanti")
        val laf = model(mapOf("bbb" to "lafb")).measures(0.75, true)
        CHECK(laf.axisQrs in -75..-31) { "emiblocco anteriore sinistro: asse ${laf.axisQrs} (< −30)" }
        val w = model(mapOf("extra" to listOf("wpw"))).measures(0.75, true)
        CHECK(w.prMs!! < 125 && w.qrsMs > 110) { F("WPW: PR corto %d, QRS largo %d", w.prMs, w.qrsMs) }
        val hk = model(mapOf("extra" to listOf("hyperkalemia"))); val n0 = SFEcg12.model(SLEcgMorph.NORMAL)
        CHECK(hk.lead(SL12V3, 0.62 * 0.4 * sqrt(0.75) * 0.93, 0.75, true) > 1.8 * n0.lead(SL12V3, 0.62 * 0.4 * sqrt(0.75), 0.75, true), "iperkaliemia: T alte e appuntite")
        val hks = model(mapOf("extra" to listOf("hyperkalemia_severe"))).measures(0.75, true)
        CHECK(hks.qrsMs > 160, "iperkaliemia grave: QRS molto largo")
        val lq = model(mapOf("extra" to listOf("long_qt"))).measures(1.0, true)
        CHECK(lq.qtcMs >= 480) { "QT lungo: QTc ${lq.qtcMs}" }
        val b1 = model(mapOf("av_block" to "first")).measures(0.75, true)
        CHECK(b1.prMs!! in 260..300) { "BAV 1°: PR ${b1.prMs}" }
    }

    // --- blocchi AV: FC ventricolare coerente, frequenza atriale, polso solo sui QRS ---
    s = NewState(); Cmd(s, "set_heart_rate", 80)
    morphCmd(s, mapOf("av_block" to "mobitz1"))
    var d = s.stateDictionary()
    CHECK(INT(d["heart_rate_bpm"]) == 60 && INT(d["atrial_rate_bpm"]) == 80 && d["av_block_active"] == true, "Mobitz I: atri 80, FC ventricolare 60 (4:3 in media)")
    morphCmd(s, mapOf("av_block" to "2to1"))
    d = s.stateDictionary()
    CHECK(INT(d["heart_rate_bpm"]) == 40 && INT(d["atrial_rate_bpm"]) == 80, "BAV 2:1: FC 40 con atri a 80")
    morphCmd(s, mapOf("av_block" to "third"))
    d = s.stateDictionary()
    CHECK(INT(d["heart_rate_bpm"]) == 35 && INT(d["atrial_rate_bpm"]) == 80, "BAV 3°: scappamento ventricolare 35, atri 80")
    morphCmd(s, mapOf("escape" to "junctional"))
    CHECK(s.snapshot().heartRate == 45, "BAV 3° con scappamento giunzionale: 45")
    RhythmNow(s, "sinus_tachycardia")
    CHECK(s.snapshot().heartRate == 45 && s.snapshot().atrialRate == 130, "tachicardia sinusale con BAV 3°: atri 130, ventricoli fermi allo scappamento")
    RhythmNow(s, "sinus")
    morphCmd(s, mapOf("av_block" to "none"))
    CHECK(s.snapshot().heartRate == 80 && !s.snapshot().avBlockActive, "blocco tolto: i ventricoli tornano alla frequenza degli atri")
    // FA: solo il BAV 3° ha senso
    RhythmNow(s, "afib"); morphCmd(s, mapOf("av_block" to "mobitz1"))
    CHECK(!s.snapshot().avBlockActive, "FA + Mobitz I: blocco non applicato (stato av_block_active false)")
    morphCmd(s, "normal")

    // motore: battiti, P non condotte, FC dalla carta, polso
    for ((av, hrTarget) in listOf("first" to 80, "mobitz1" to 60, "mobitz2" to 60, "2to1" to 40, "third" to 35)) {
        val st = NewState(); Cmd(st, "set_heart_rate", 80); morphCmd(st, mapOf("av_block" to av))
        val e = run(st, 14.0)
        val c = e.capture12()
        CHECK(abs(c.hr - hrTarget) <= maxOf(4, hrTarget / 8)) { "$av: FC sulla carta ${c.hr} ≈ $hrTarget (FC ventricolare dello stato ${st.snapshot().heartRate})" }
        CHECK(c.reading.lines.any { it.contains("Blocco AV") }) { "$av: interpretazione con il blocco AV (${c.reading.lines})" }
        // polso: niente polso «fantasma» dalle onde P (ogni battito perfuso è un QRS)
        var okP = true; var t = gNow - 10.0
        while (t < gNow - 0.5) { val pb = e.lastPerfusedBeatAtOrBefore(t); if (pb > 0 && abs(e.lastBeatAtOrBefore(t) - pb) > 1e-9 && e.lastBeatAtOrBefore(t) < pb) okP = false; t += 0.05 }
        CHECK(okP, "$av: il polso segue solo i QRS")
    }
    run {   // BAV 1°: PR lungo sulla carta
        val st = NewState(); morphCmd(st, mapOf("av_block" to "first", "pr_ms" to 320))
        val c = run(st, 12.0).capture12()
        CHECK((c.measures?.prMs ?: 0) in 300..340) { "BAV 1° con pr_ms 320: PR sulla carta ${c.measures?.prMs}" }
    }
    // asistolia da BAV avanzato: le P restano
    run {
        val st = NewState(); morphCmd(st, mapOf("av_block" to "third"))
        Cmd(st, "set_rhythm", "asystole")
        CHECK(st.snapshot().rhythmOnset?.variant == "escape_failure", "BAV 3° -> asistolia: lo scappamento cede (variante escape_failure)")
        Tick(st, 20.0)
        CHECK(st.snapshot().rhythm == "asystole" && st.snapshot().avBlockActive && st.stateDictionary()["atrial_rate_bpm"] != null, "asistolia ventricolare con onde P (blocco ancora attivo)")
        val c = run(st, 12.0).capture12()
        CHECK(c.hr == 0 && c.reading.lines.first().contains("solo onde P"), "carta: asistolia ventricolare (solo onde P)")
        val s3 = NewState(); Cmd(s3, "set_heart_rate", 80); morphCmd(s3, mapOf("av_block" to "2to1"))
        Cmd(s3, "set_rhythm", "asystole")
        CHECK(s3.snapshot().rhythmOnset?.variant == "av_block", "BAV 2:1 -> asistolia: sempre per blocco AV")
    }

    // --- tracciato del monitor: la morfologia si vede in II ---
    run {
        val st = NewState(); morphCmd(st, mapOf("st" to "stemi_inferior", "st_mm" to 4))
        val e = WaveformEngine(9); e.updateWithSnapshot(st.snapshot())
        val e0 = WaveformEngine(9); val s0 = NewState(); e0.updateWithSnapshot(s0.snapshot())
        var t = gNow; var maxDiff = 0.0
        while (t < gNow + 5) { val x = e.ecgAt(t, SLLeadII); val y = e0.ecgAt(t, SLLeadII); maxDiff = maxOf(maxDiff, x - y); t += 0.004 }
        CHECK(maxDiff > 0.2) { F("STEMI inferiore visibile sul tracciato II del monitor (+%.2f mV)", maxDiff) }
    }

    // --- carta 12D: coerenza con il tracciato ---
    run {
        val st = NewState(); Cmd(st, "set_heart_rate", 120); morphCmd(st, mapOf("st" to "stemi_anterior"))
        val e = run(st, 12.0)
        val t0 = System.nanoTime(); val c = e.capture12(); val ms = (System.nanoTime() - t0) / 1e6
        CHECK(c.leads.size == 12 && c.leads[0].size == 2500 && c.fs == 250, "carta: 12 derivazioni × 10 s a 250 Hz")
        CHECK(abs(c.hr - 120) <= 4) { "carta: FC ${c.hr} ≈ 120" }
        CHECK(c.reading.headline == "*** STEMI ACUTO ***", "interpretazione: *** STEMI ACUTO ***")
        CHECK(ms < 1500) { F("cattura in %.0f ms", ms) }
        // la striscia II della carta è il tracciato II del monitor (stessi battiti)
        var maxd = 0.0
        val e2 = WaveformEngine(21); val st2 = NewState(); Cmd(st2, "set_heart_rate", 120); morphCmd(st2, mapOf("st" to "stemi_anterior"))
        gNow -= 12.0; e2.updateWithSnapshot(st2.snapshot())
        var t = gNow; var k = 0; val vals = ArrayList<Double>()
        while (t < gNow + 12.0) { val v = e2.ecgAt(t, SLLeadII); e2.ecgAt(t, SLLeadV); e2.plethAt(t); vals.add(v); t += 0.004; k++; if (k % 125 == 0) { st2.tickAtTime(t); e2.updateWithSnapshot(st2.snapshot()) } }
        for (i in 0 until 2400) maxd = maxOf(maxd, abs(c.leads[SL12II][i] - vals[vals.size - 2501 + i]))
        CHECK(maxd < 0.02) { F("striscia II della carta = tracciato II del monitor (scarto %.3f mV)", maxd) }
    }
    run {
        val st = NewState(); RhythmNow(st, "vf")
        val c = run(st, 12.0).capture12()
        CHECK(c.hr == -1 && c.measures == null && c.reading.lines.first() == "Fibrillazione ventricolare", "carta in FV: FC ---, nessuna misura")
        val off = NewStateRaw(); val c2 = run(off, 3.0).capture12()
        CHECK(!c2.electrodes && c2.leads[SL12II].all { it == 0f }, "elettrodi staccati: carta vuota")
    }
    run {   // interpretazione realistica: il posteriore isolato NON è segnalato come STEMI (come molti algoritmi)
        val st = NewState(); morphCmd(st, mapOf("st" to "stemi_posterior"))
        val c = run(st, 11.0).capture12()
        CHECK(c.reading.headline == null && c.reading.lines.any { it.contains("V1-V3") }, "posteriore isolato: nessun *** STEMI ***, sottoslivellamento V1-V3")
        val s2 = NewState(); morphCmd(s2, mapOf("st" to "stemi_inferior", "interpretation" to false))
        val c2 = run(s2, 11.0).capture12()
        CHECK(c2.reading.headline == null && c2.reading.lines == listOf("Interpretazione automatica non attiva"), "interpretazione disattivata")
    }

    // --- registro: 12 derivazioni acquisito ---
    run {
        val st = NewState(); Cmd(st, "start_scenario"); morphCmd(st, mapOf("st" to "stemi_inferior"))
        CHECK(Cmd(st, "ecg12_acquired", mapOf("layout" to "extrah", "interpretation" to "*** STEMI ACUTO ***")), "ecg12_acquired accettato")
        CHECK(events(st).any { it.startsWith("12 derivazioni acquisito") && it.contains("STEMI inferiore") }, "registro: «12 derivazioni acquisito» con il quadro")
        val l = st.stateDictionary()["ecg12_last"]
        CHECK(INT(l["seq"]) == 1 && l["layout"] == "extrah" && HASSTR(l["interpretation"], "STEMI"), "stato ecg12_last")
    }

    // --- ANDROID (regia morfologie ECG 22/11): morfologia come campo della fase di uno scenario ---
    run {
        val st = NewState(); val sc = SFScenarioStore(null, st)
        val ok = sc.save(mapOf("name" to "ECG", "phases" to listOf(
            mapOf("name" to "Inferiore", "rhythm" to "sinus", "params" to mapOf("heart_rate_bpm" to 70), "ecg_morphology" to mapOf("st" to "stemi_inferior", "st_mm" to 3, "av_block" to "first", "pr_ms" to 300)),
            mapOf("name" to "Solo ECG", "ecg_morphology" to mapOf("av_block" to "third", "escape" to "junctional")),
            mapOf("name" to "Normale", "ecg_morphology" to "normal"))))
        CHECK(ok.error == null) { "fase con ecg_morphology salvata (${ok.error})" }
        val ph = ok.scenario!!["phases"] as List<*>
        CHECK((ph[0] as Map<*, *>)["ecg_morphology"] is Map<*, *> && (ph[2] as Map<*, *>)["ecg_morphology"] == "normal", "ecg_morphology conservata nella fase (oggetto / \"normal\")")
        val bad = sc.save(mapOf("name" to "X", "phases" to listOf(mapOf("ecg_morphology" to mapOf("bbb" to "lbbb", "extra" to listOf("wpw"))))))
        CHECK(bad.error?.contains("phase 1") == true && bad.error?.contains("wpw") == true) { "fase con combinazione non ammessa rifiutata (${bad.error})" }
        val bad2 = sc.save(mapOf("name" to "X", "phases" to listOf(mapOf("ecg_morphology" to mapOf("st" to "marte")))))
        CHECK(bad2.error?.contains("ecg_morphology") == true, "fase con valore sconosciuto rifiutata")
        val sid = ok.scenario["id"] as String
        CHECK(sc.start(sid, 0) == null && st.snapshot().ecgMorph.st == "stemi_inferior" && st.snapshot().ecgMorph.avBlock == "first" && st.snapshot().ecgMorph.prMs == 300, "fase 1: morfologia applicata")
        CHECK(sc.next() == null && st.snapshot().ecgMorph.avBlock == "third" && st.snapshot().ecgMorph.escape == "junctional" && st.snapshot().ecgMorph.st == "stemi_inferior", "fase con la sola morfologia: parziale, il resto resta")
        CHECK(sc.next() == null && st.snapshot().ecgMorph.isNormal, "fase \"normal\": ECG normale")
        // transizione di «Prepara» con la sola morfologia (regia): accettata, applicata subito
        CHECK(Cmd(st, "transition", mapOf("params" to emptyMap<String, Any?>(), "duration_s" to 30, "ecg_morphology" to mapOf("bbb" to "lbbb"))) && st.snapshot().ecgMorph.bbb == "lbbb", "transition con la sola ecg_morphology")
    }
}
