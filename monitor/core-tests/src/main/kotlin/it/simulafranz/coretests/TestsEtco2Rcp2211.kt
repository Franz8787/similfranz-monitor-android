// ANDROID (EtCO2 RCP 22/11): capnografia e pressione arteriosa cruenta durante l'arresto e la RCP, qualità della RCP
// dettagliata (frequenza numerica, profondità, rilascio, mani), affaticamento, cambio dell'operatore, adrenalina, pause, ROSC.
package it.simulafranz.coretests

import it.simulafranz.core.*

// ANDROID (EtCO2 visibile 24/11): questi test descrivono la modalità REALISTICA (solo ventilazioni vere), non più di serie
private fun NewStateR(): SimulationState = NewState().also { Cmd(it, "set_ventilation", mapOf("etco2_cpr_display" to "realistic")) }
// arresto in FV con CO2 e ART collegate; RCP corretta avviata e lasciata andare `sec` secondi
private fun arrestWithCpr(sec: Int = 40): SimulationState {
    val s = NewStateR(); Sensor(s, "etco2", true); Sensor(s, "art", true)
    RhythmNow(s, "vf"); Tick(s, 30)
    Cmd(s, "set_cpr", true); Tick(s, sec)
    return s
}
private fun disp(s: SimulationState): Int = INT(s.stateDictionary()["etco2_displayed"])
// un'insufflazione e la sua lettura a fine espirazione
private fun breath(s: SimulationState): Int { Cmd(s, "ventilate"); Tick(s, 3); return disp(s) }
private class Span(val min: Double, val max: Double, val peaks: Int)
// campiona una curva del motore sul tempo vero dello stato (gNow): minimo, massimo e numero di picchi sopra la soglia
private fun sample(s: SimulationState, sec: Double, thr: Double, f: (WaveformEngine, Double) -> Double): Span {
    val e = WaveformEngine(7); e.updateWithSnapshot(s.snapshot())
    var mn = 1e9; var mx = -1e9; var peaks = 0; var up = false
    var t = gNow; val end = gNow + sec
    while (t < end) {
        val v = f(e, t); mn = Math.min(mn, v); mx = Math.max(mx, v)
        if (!up && v > thr) { peaks++; up = true } else if (up && v < thr * 0.7) up = false
        t += 0.004
    }
    return Span(mn, mx, peaks)
}

fun testEtco2Rcp2211() {
    // --- arresto senza RCP: EtCO2 e ART crollano, niente onde ---
    var s = NewStateR(); Sensor(s, "etco2", true); Sensor(s, "art", true)
    RhythmNow(s, "vf"); Tick(s, 20)
    var d = s.stateDictionary()
    // 24/11 (Gutiérrez 2021): senza ventilazioni la CO2 resta negli alveoli, ma nessuno espira: nessun numero
    CHECK(ISNULL(d, "etco2_displayed") && d["etco2_cpr_mode"] == "auto") { F("arresto senza RCP: nessuna espirazione, nessun numero (%s), modello attivo", d["etco2_displayed"]) }
    var a = sample(s, 3.0, 1e9) { e, t -> e.artAt(t) }
    CHECK(a.max - a.min < 5 && a.max < 19) { F("arresto senza RCP: ART quasi piatta ~15, solo il tremolio della FV (%.1f-%.1f)", a.min, a.max) }
    CHECK(s.snapshot().artDisplayedSystolic in 14..18, "arresto senza RCP: numeri ART ~15")
    CHECK(d["cpr_rate_quality"] == "ok" && d["cpr_depth_quality"] == "ok" && d["cpr_recoil_quality"] == "ok" && d["cpr_hands_quality"] == "ok" &&
          INT(d["cpr_rate_cpm"]) == 110 && DBL(d["cpr_fatigue"]) == 0.0, "di serie la RCP è fatta bene (tutto ok, 110/min, nessun affaticamento)")

    // --- RCP corretta: EtCO2 15-20, numero solo con le ventilazioni, oscillazioni da compressione ---
    s = arrestWithCpr(40)
    d = s.stateDictionary()
    CHECK(INT(d["etco2_mmhg"]) in 15..20) { F("RCP corretta: EtCO2 del modello 15-20 (%d)", INT(d["etco2_mmhg"])) }
    CHECK(ISNULL(d, "etco2_displayed") && s.snapshot().respRate == 0, "RCP senza ventilazioni: nessun numero (APNEA)")
    CHECK(AlarmManager.evaluate(s.snapshot()).messages.joinToString(",").contains("APNEA"), "RCP senza ventilazioni: allarme APNEA")
    var c = sample(s, 6.0, 1.0) { e, t -> e.capnoAt(t) }
    CHECK(c.max > 1.5 && c.max < 6) { F("RCP senza ventilazioni: solo piccole oscillazioni (max %.1f mmHg)", c.max) }
    CHECK(c.peaks in 10..12) { F("oscillazioni alla frequenza delle compressioni: 110/min = 11 in 6 s (%d)", c.peaks) }
    val good = breath(s)
    CHECK(good in 15..20) { F("RCP corretta: EtCO2 a monitor alla ventilazione 15-20 (%d)", good) }
    // onda alla ventilazione con il picco giusto
    Cmd(s, "ventilate"); val b0 = gNow
    run { val e = WaveformEngine(2); e.updateWithSnapshot(s.snapshot())
      var pk = 0.0; var t = b0 + 1.4; while (t < b0 + 2.7) { pk = Math.max(pk, e.capnoAt(t)); t += 0.01 }
      val bc = s.snapshot().breathCo2.last()
      CHECK(Math.abs(pk - bc) < 3.5 && pk > 12) { F("onda alla ventilazione: plateau %.1f ~ picco dell'atto %.0f", pk, bc) } }
    // 30:2: seconda insufflazione ravvicinata un po' più bassa
    gNow += 1.5; Cmd(s, "ventilate")
    val bc = s.snapshot().breathCo2
    CHECK(bc.size >= 2 && bc[bc.size - 1] <= bc[bc.size - 2], "30:2: la seconda insufflazione non supera la prima")
    // ART: un'onda per compressione, ~80/28
    a = sample(s, 6.0, 50.0) { e, t -> e.artAt(t) }
    CHECK(a.peaks in 10..12 && a.max in 70.0..92.0 && a.min in 24.0..32.0) { F("RCP corretta: ART %d onde in 6 s, %.0f/%.0f", a.peaks, a.max, a.min) }
    val sn = s.snapshot()
    CHECK(sn.artDisplayedSystolic in 70..92 && sn.artDisplayedDiastolic in 24..32 && Math.abs(sn.artDisplayedSystolic - a.max) < 6) {
        F("RCP corretta: numeri ART coerenti con la curva (%d/%d)", sn.artDisplayedSystolic, sn.artDisplayedDiastolic) }

    // --- superficiale ---
    Cmd(s, "cpr_rescuer_change"); Cmd(s, "set_cpr_quality", mapOf("depth" to "shallow")); Tick(s, 60)
    val shallow = breath(s)
    CHECK(shallow in 7..12) { F("RCP superficiale: EtCO2 8-12 (%d)", shallow) }
    a = sample(s, 4.0, 30.0) { e, t -> e.artAt(t) }
    CHECK(a.max in 36.0..52.0 && a.min < 20) { F("RCP superficiale: ART ~45/<20 (%.0f/%.0f)", a.max, a.min) }
    c = sample(s, 4.0, 0.5) { e, t -> e.capnoAt(t) }
    CHECK(s.snapshot().co2CompressionAmp < 2.0, "superficiale: oscillazioni del capnogramma più piccole")

    // --- frequenza numerica: lenta 85, veloce 140, continua ---
    Cmd(s, "cpr_rescuer_change"); Cmd(s, "set_cpr_quality", mapOf("depth" to "ok", "rate_cpm" to 85)); Tick(s, 60)
    d = s.stateDictionary()
    CHECK(d["cpr_rate_quality"] == "slow" && INT(d["cpr_rate_cpm"]) == 85, "rate_cpm 85 -> categoria lenta")
    val slow = breath(s)
    CHECK(slow in 9..14) { F("RCP lenta 85/min: EtCO2 ~12 (%d)", slow) }
    a = sample(s, 6.0, 50.0) { e, t -> e.artAt(t) }
    CHECK(a.peaks in 8..9 && a.min < 23) { F("RCP lenta: onde rade (%d in 6 s, 85/min) e diastolica più bassa (%.0f)", a.peaks, a.min) }
    c = sample(s, 6.0, 0.8) { e, t -> e.capnoAt(t) }
    Cmd(s, "cpr_rescuer_change"); Cmd(s, "set_cpr_quality", mapOf("rate" to "fast")); Tick(s, 60)
    CHECK(INT(s.stateDictionary()["cpr_rate_cpm"]) == 140 && s.stateDictionary()["cpr_rate_quality"] == "fast", "categoria vecchia «fast» -> 140/min")
    val fast = breath(s)
    a = sample(s, 6.0, 45.0) { e, t -> e.artAt(t) }
    CHECK(fast in 12..17 && a.peaks in 13..15 && a.max < 75) { F("RCP veloce 140/min: EtCO2 %d, %d onde in 6 s, picchi %.0f", fast, a.peaks, a.max) }
    Cmd(s, "cpr_rescuer_change"); Cmd(s, "set_cpr_quality", mapOf("rate_cpm" to 112)); Tick(s, 60)
    CHECK(s.stateDictionary()["cpr_rate_quality"] == "ok", "rate_cpm 112 -> corretta")
    CHECK(!Cmd(s, "set_cpr_quality", mapOf("rate_cpm" to 250)) && !Cmd(s, "set_cpr_quality", mapOf("recoil" to "boh")) && !Cmd(s, "set_cpr_quality", mapOf("hands" to 3)),
          "valori fuori range rifiutati")
    // continuità: 100..120 uguale, poi calo graduale
    run {
        val v = IntArray(4); val rates = intArrayOf(100, 120, 125, 135)
        for (i in 0 until 4) { val x = arrestWithCpr(5); Cmd(x, "set_cpr_quality", mapOf("rate_cpm" to rates[i])); Tick(x, 80); v[i] = INT(x.stateDictionary()["etco2_mmhg"]) }
        CHECK(Math.abs(v[0] - v[1]) <= 1 && v[2] <= v[1] && v[3] < v[1]) { F("efficacia continua con la frequenza: 100 %d, 120 %d, 125 %d, 135 %d", v[0], v[1], v[2], v[3]) }
    }

    // --- rilascio incompleto e mani ---
    s = arrestWithCpr(30); Cmd(s, "cpr_rescuer_change")
    val ref = sample(s, 3.0, 50.0) { e, t -> e.artAt(t) }
    Cmd(s, "cpr_rescuer_change"); Cmd(s, "set_cpr_quality", mapOf("recoil" to "incomplete")); Cmd(s, "cpr_rescuer_change"); Tick(s, 40); Cmd(s, "cpr_rescuer_change")
    d = s.stateDictionary()
    a = sample(s, 3.0, 50.0) { e, t -> e.artAt(t) }
    CHECK(d["cpr_recoil_quality"] == "incomplete" && a.min > ref.min + 2 && a.max < ref.max - 4) {
        F("rilascio incompleto: diastolica più alta (%.0f > %.0f), picchi più bassi (%.0f < %.0f)", a.min, ref.min, a.max, ref.max) }
    CHECK(INT(d["etco2_mmhg"]) in 13..17) { F("rilascio incompleto: EtCO2 più bassa di qualche mmHg (%d)", INT(d["etco2_mmhg"])) }
    Cmd(s, "cpr_rescuer_change"); Cmd(s, "set_cpr_quality", mapOf("recoil" to "ok", "hands" to "wrong")); Tick(s, 40); Cmd(s, "cpr_rescuer_change")
    a = sample(s, 3.0, 40.0) { e, t -> e.artAt(t) }
    CHECK(s.stateDictionary()["cpr_hands_quality"] == "wrong" && INT(s.stateDictionary()["etco2_mmhg"]) in 11..16 && a.max < ref.max - 10) {
        F("mani sbagliate: EtCO2 %d, picchi ART %.0f", INT(s.stateDictionary()["etco2_mmhg"]), a.max) }
    CHECK(hasEvent(s, "rilascio incompleto") && hasEvent(s, "mani in posizione sbagliata"), "qualità RCP nel registro")

    // --- affaticamento e cambio dell'operatore ---
    s = arrestWithCpr(20)
    val fresh = INT(s.stateDictionary()["etco2_mmhg"])
    Tick(s, 130)
    d = s.stateDictionary()
    CHECK(DBL(d["cpr_fatigue"]) >= 0.9 && INT(d["etco2_mmhg"]) <= fresh - 2) { F("affaticamento dopo 2,5 min: %.2f, EtCO2 %d -> %d", DBL(d["cpr_fatigue"]), fresh, INT(d["etco2_mmhg"])) }
    val tiredDia = s.snapshot().artDisplayedDiastolic
    CHECK(tiredDia < 25) { F("affaticamento: diastolica ART sotto 25 (%d)", tiredDia) }
    CHECK(Cmd(s, "cpr_rescuer_change") && DBL(s.stateDictionary()["cpr_fatigue"]) == 0.0 && hasEvent(s, "cambio dell'operatore"), "cpr_rescuer_change: affaticamento azzerato, nel registro")
    Tick(s, 40)
    CHECK(INT(s.stateDictionary()["etco2_mmhg"]) >= fresh - 1 && s.snapshot().artDisplayedDiastolic > tiredDia + 2, "dopo il cambio EtCO2 e diastolica risalgono")
    // la voce del registro «Cambio dell'operatore alle compressioni» fa lo stesso
    Tick(s, 130); val lg = SFSessionLog(null, s)
    lg.add("action", "Cambio dell'operatore alle compressioni", null, "istr")
    CHECK(DBL(s.stateDictionary()["cpr_fatigue"]) == 0.0, "voce di registro «Cambio dell'operatore»: affaticamento azzerato")

    // --- adrenalina: EtCO2 giù di poco, diastolica su ---
    s = arrestWithCpr(60); Cmd(s, "cpr_rescuer_change"); Tick(s, 1)
    val e0 = INT(s.stateDictionary()["etco2_mmhg"]); val d0 = s.snapshot().artDisplayedDiastolic
    SFSessionLog(null, s).add("drug", "Adrenalina 1 mg", null, "istr")
    Tick(s, 90); Cmd(s, "cpr_rescuer_change"); Tick(s, 1)
    CHECK(INT(s.stateDictionary()["etco2_mmhg"]) <= e0 - 2 && s.snapshot().artDisplayedDiastolic >= d0 + 5) {
        F("adrenalina: EtCO2 %d -> %d (giù), diastolica %d -> %d (su)", e0, INT(s.stateDictionary()["etco2_mmhg"]), d0, s.snapshot().artDisplayedDiastolic) }
    for (i in 0 until 5) { Tick(s, 60); Cmd(s, "cpr_rescuer_change") }
    CHECK(Math.abs(INT(s.stateDictionary()["etco2_mmhg"]) - e0) <= 1, "adrenalina: effetto finito dopo 5 min")

    // --- pausa delle compressioni: crollo in pochi secondi, nessuna onda; ripresa ---
    s = arrestWithCpr(40); breath(s)
    Cmd(s, "set_cpr", false); Tick(s, 1)
    CHECK(s.snapshot().ventilationManual && Cmd(s, "ventilate"), "pausa: il paziente senza polso non respira da solo (ventilazione manuale anche mentre la FR scende)")
    Tick(s, 5)
    a = sample(s, 2.0, 1e9) { e, t -> e.artAt(t) }
    CHECK(a.max - a.min < 5 && a.max < 22) { F("pausa: ART senza onde, crollata (%.0f-%.0f)", a.min, a.max) }
    // 24/11 (Gutiérrez et al., PLoS ONE 2021): in pausa senza ROSC l'EtCO2 cala ~11 % a ogni ventilazione, non crolla di colpo
    CHECK(s.snapshot().co2CompressionAmp == 0.0, "pausa: nessuna oscillazione da compressione")
    val p1 = breath(s); Tick(s, 3); val p2 = breath(s); Tick(s, 3); val p3 = breath(s)
    CHECK(p1 in 10..20 && p3 < p1 && p3 >= p1 * 0.6) { F("pausa: ogni ventilazione abbassa l'EtCO2 di ~11 %% (%d -> %d -> %d)", p1, p2, p3) }
    Tick(s, 14)
    Cmd(s, "set_cpr", true); Tick(s, 2)
    val r2 = INT(s.stateDictionary()["etco2_mmhg"])
    CHECK(r2 < 17, "ripresa: l'EtCO2 non torna di colpo")
    Tick(s, 30)
    CHECK(breath(s) in 14..20 && s.snapshot().artDisplayedDiastolic >= 24, "ripresa: dopo ~30 s EtCO2 e diastolica di nuovo buone")

    // --- iperventilazione: EtCO2 per atto e ART più basse ---
    s = arrestWithCpr(60); Cmd(s, "cpr_rescuer_change")
    val normal = breath(s); val diaN = s.snapshot().artDisplayedDiastolic
    for (i in 0 until 30) { Cmd(s, "ventilate"); Tick(s, 2) }
    Cmd(s, "cpr_rescuer_change"); Tick(s, 15); Cmd(s, "cpr_rescuer_change")
    val hyper = breath(s)
    CHECK(hyper <= normal * 0.75 && s.snapshot().artDisplayedDiastolic < diaN) { F("iperventilazione 30/min: EtCO2 %d -> %d, diastolica %d -> %d", normal, hyper, diaN, s.snapshot().artDisplayedDiastolic) }

    // --- ROSC: aumento brusco e sostenuto, onde proprie del ritmo ---
    s = arrestWithCpr(60); val pre = breath(s)
    RhythmNow(s, "sinus"); Tick(s, 6)
    CHECK(s.snapshot().etco2 >= pre + 10 && s.stateDictionary()["etco2_cpr_mode"] == null) { F("ROSC: EtCO2 %d -> %d (>= +10)", pre, s.snapshot().etco2) }
    a = sample(s, 4.0, 1e9) { e, t -> e.artAt(t) }
    CHECK(a.max - a.min > 15 && s.snapshot().artDisplayedSystolic == s.snapshot().systolic, "ROSC: onde arteriose pulsatili e numeri del paziente")

    // --- valore forzato dall'istruttore: priorità sul modello ---
    s = arrestWithCpr(40)
    Cmd(s, "set_etco2", 38); Tick(s, 20)
    CHECK(s.stateDictionary()["etco2_cpr_mode"] == "instructor" && INT(s.stateDictionary()["etco2_mmhg"]) == 38 && breath(s) in 36..44, "istruttore: valore forzato (38) durante le compressioni (24/11: + fino al 15 % col massaggio buono e continuo)")
    Cmd(s, "set_cpr", false); Tick(s, 8)
    run { val b1 = breath(s); Tick(s, 3); val b2 = breath(s)
      CHECK(b2 < b1 && INT(s.stateDictionary()["etco2_mmhg"]) == 38) { F("istruttore: in pausa cala a ogni ventilazione (%d -> %d), il valore impostato resta", b1, b2) } }
    Cmd(s, "set_cpr", true); Tick(s, 40)
    CHECK(breath(s) >= 34, "istruttore: alla ripresa torna il suo valore")
    Cmd(s, "cpr_rescuer_change"); Cmd(s, "set_cpr_quality", mapOf("depth" to "ok")); Tick(s, 60)
    CHECK(s.stateDictionary()["etco2_cpr_mode"] == "auto" && INT(s.stateDictionary()["etco2_mmhg"]) in 13..20, "toccando la qualità RCP si torna al modello")
    // la fase di scenario che AVVIA l'arresto con etco2 0 non blocca il modello
    s = NewStateR(); Sensor(s, "etco2", true)
    Cmd(s, "transition", mapOf("rhythm" to "pea", "params" to mapOf("etco2_mmhg" to 0), "duration_s" to 10))
    Cmd(s, "set_cpr", true); Tick(s, 40)
    CHECK(s.stateDictionary()["etco2_cpr_mode"] == "auto" && INT(s.stateDictionary()["etco2_mmhg"]) >= 14, "fase che avvia l'arresto: decide il modello")
    // senza fisiologia automatica: come prima, vale il valore impostato
    s = NewStateR(); Sensor(s, "etco2", true); Cmd(s, "set_auto_physiology", false); RhythmNow(s, "vf"); Cmd(s, "set_cpr", true); Tick(s, 30)
    CHECK(s.stateDictionary()["etco2_cpr_mode"] == null && INT(s.stateDictionary()["etco2_mmhg"]) == 35, "senza fisiologia automatica l'EtCO2 resta quella impostata")

    // --- linea CO2 / trasduttore staccati: niente ---
    s = arrestWithCpr(30); Sensor(s, "etco2", false); Sensor(s, "art", false); Cmd(s, "ventilate")
    c = sample(s, 3.0, 1e9) { e, t -> e.capnoAt(t) }
    CHECK(c.max == 0.0 && s.snapshot().artDisplayedSystolic == -1, "sonde staccate: capnogramma piatto, ART «--»")

    // --- reset e ripristino ---
    s = arrestWithCpr(30); Cmd(s, "set_cpr_quality", mapOf("rate_cpm" to 95, "recoil" to "incomplete", "hands" to "wrong", "depth" to "shallow")); breath(s)
    @Suppress("UNCHECKED_CAST")
    val back = Json.parse(Json.stringify(s.exportState())) as Map<String, Any?>
    val s2 = NewStateRaw(); CHECK(s2.restoreState(back) == null, "ripristino: riuscito")
    val k = listOf("cpr_rate_cpm", "cpr_rate_quality", "cpr_recoil_quality", "cpr_hands_quality", "cpr_fatigue", "etco2_mmhg", "etco2_displayed", "etco2_cpr_mode", "art_displayed_diastolic_mmhg")
    CHECK(k.all { s.stateDictionary()[it] == s2.stateDictionary()[it] }) { "ripristino: qualità RCP e modello identici " + k.filter { s.stateDictionary()[it] != s2.stateDictionary()[it] } }
    Reset(s); d = s.stateDictionary()
    CHECK(d["cpr_recoil_quality"] == "ok" && d["cpr_hands_quality"] == "ok" && INT(d["cpr_rate_cpm"]) == 110 && d["cpr_depth_quality"] == "ok" && DBL(d["cpr_fatigue"]) == 0.0,
          "reset_scenario: RCP di nuovo «fatta bene»")
}

private fun hasEvent(s: SimulationState, needle: String): Boolean =
    (s.stateDictionary()["defib"]["events"] as? List<*>)?.any { (it["text"] as? String)?.contains(needle) == true } == true
