// ANDROID (sincronia polso 22/11): sincronia QRS -> onda arteriosa (ART) e pletismografica (pleth).
// Strumento di misura + test: 30+ minuti di simulazione (accelerata) con rampe di FC, FA, extrasistoli (preludi), pausa,
// blocchi AV e arresto. Per ogni battito: ritardo R -> inizio / picco dell'onda ART e pleth, onde senza QRS («fantasma»),
// QRS efficaci senza onda, deficit di polso. Le onde si riconoscono SULLE CURVE campionate (come le vede chi guarda il
// monitor), i QRS dagli istanti del motore (lastBeatAtOrBefore). SF_POLSO_REPORT=1 stampa la tabella per finestre di 5 min.
package it.simulafranz.coretests

import it.simulafranz.core.*

private const val FS = 250.0
private const val DT = 1.0 / FS

// un'onda riconosciuta sulla curva: istante del piede (inizio) e del picco, ampiezza
private class Onda(val on: Double, val pk: Double, val amp: Double)

// QRS registrato: istante, FC impostata in quel momento, ritmo con polso sì/no, segmento dello script
private class Qrs(val t: Double, val meanRR: Double, val pulse: Boolean, val seg: String, val drawn: Boolean, var prev: Double = 9.0)

// riconoscimento delle onde: picco = massimo entro ±0,15 s con prominenza (rispetto al minimo dei 0,35 s prima) >= thr;
// piede = ultimo campione prima del picco entro il 3% dell'ampiezza dal minimo
private fun onde(v: DoubleArray, t0: Double, thr: Double): List<Onda> {
    val out = ArrayList<Onda>()
    val w = (0.15 * FS).toInt(); val back = (0.35 * FS).toInt()
    var i = back
    while (i < v.size - w) {
        val x = v[i]
        var isMax = true
        for (j in i - w..i + w) { if (v[j] > x || (v[j] == x && j < i)) { isMax = false; break } }
        if (isMax) {
            var mn = x; var kMin = i
            for (j in i - back..i) if (v[j] < mn) { mn = v[j]; kMin = j }
            val a = x - mn
            if (a >= thr) {
                var foot = kMin
                for (j in kMin..i) if (v[j] <= mn + 0.03 * a) foot = j
                out.add(Onda(t0 + foot * DT, t0 + i * DT, a))
                i += w; continue
            }
        }
        i++
    }
    return out
}

class PolsoMisure(val name: String) {
    val dOn = ArrayList<Double>(); val dPk = ArrayList<Double>()
    var fantasma = 0; var doppie = 0; var mancanti = 0; var deficit = 0; var precoci = 0; var arrestoOnde = 0
    val perFinestra = HashMap<Int, ArrayList<Double>>()   // finestra di 5 min -> ritardi R -> picco
    val sinusPk = HashMap<String, ArrayList<Double>>()     // segmento sinusale -> ritardi R -> picco (deriva a parità di condizioni)
    val ampRel = ArrayList<Pair<Double, Double>>()         // (RR precedente / RR medio, ampiezza) di ogni onda
    var ampNorm = 1.0; val ampPrecoci = ArrayList<Double>()
    var precociAmpMax = 0.0                                // ampiezza massima (relativa alla normale) delle onde dei battiti precoci
    var segOnde = HashMap<String, Int>(); var segQrs = HashMap<String, Int>()
    fun mean(l: List<Double>) = if (l.isEmpty()) Double.NaN else l.average()
    fun pct(l: List<Double>, p: Double): Double { if (l.isEmpty()) return Double.NaN; val s = l.sorted(); return s[Math.min(s.size - 1, (p * s.size).toInt())] }
    fun derivaSinusale(): Double { val m = sinusPk.values.filter { it.size > 20 }.map { it.average() }; return if (m.size < 2) 0.0 else m.max() - m.min() }
    // correlazione (Pearson) tra RR precedente e ampiezza nelle onde con rel in [lo, hi]
    fun corrAmp(lo: Double, hi: Double): Double {
        val l = ampRel.filter { it.first in lo..hi }; if (l.size < 10) return 0.0
        val mx = l.map { it.first }.average(); val my = l.map { it.second }.average()
        var sxy = 0.0; var sxx = 0.0; var syy = 0.0
        for ((x, y) in l) { sxy += (x - mx) * (y - my); sxx += (x - mx) * (x - mx); syy += (y - my) * (y - my) }
        return if (sxx <= 0 || syy <= 0) 0.0 else sxy / Math.sqrt(sxx * syy)
    }
    fun deriva(): Double { val m = perFinestra.values.filter { it.size > 20 }.map { it.average() }; return if (m.isEmpty()) 0.0 else m.max() - m.min() }
}

class PolsoRisultato(val art: PolsoMisure, val pleth: PolsoMisure, val qrs: Int, val beepScarto: Double, val beep: Int, val ondePleth: Int)

/**
 * Simulazione lunga: script a segmenti (secondi dall'inizio -> azione). Restituisce le misure per ART e pleth.
 * dtQuery = passo di campionamento (4 ms, come un tracciato a 250 Hz); durante la «pausa» il motore non viene interrogato.
 */
fun polsoSimula(minuti: Double, seed: Int = 7): PolsoRisultato {
    val s = NewState(); Sensor(s, "art", true); Sensor(s, "etco2", true)
    Cmd(s, "set_bp", mapOf("systolic" to 120, "diastolic" to 75))
    Cmd(s, "set_heart_rate", 70)
    val e = WaveformEngine(seed); e.updateWithSnapshot(s.snapshot())
    val T = minuti * 60.0
    // script (s dall'inizio): rampe di FC, FA, preludi con extrasistoli, pausa, BAV, arresto
    data class Az(val at: Double, val seg: String, val f: () -> Unit)
    val k = T / 1800.0           // lo script si allunga/accorcia con la durata (riferimento 30 min)
    val script = listOf(
        Az(0.0, "sinusale 70") { },
        Az(240 * k, "rampa 70->140") { Cmd(s, "transition", mapOf("params" to mapOf("heart_rate_bpm" to 140), "duration_s" to 60)) },
        Az(420 * k, "rampa 140->50") { Cmd(s, "transition", mapOf("params" to mapOf("heart_rate_bpm" to 50), "duration_s" to 90)) },
        Az(600 * k, "FA 110 (preludio)") { Cmd(s, "set_heart_rate", 110); Cmd(s, "set_rhythm", "afib") },
        Az(900 * k, "sinusale 80 (preludio)") { Cmd(s, "set_rhythm", "sinus"); Cmd(s, "set_heart_rate", 80) },
        Az(1000 * k, "pausa") { Cmd(s, "set_simulation_running", false) },
        Az(1030 * k, "ripresa") { Cmd(s, "set_simulation_running", true) },
        Az(1100 * k, "TV con polso (extrasistoli)") { Cmd(s, "set_rhythm", "vt_pulse") },
        Az(1200 * k, "sinusale 75") { RhythmNow(s, "sinus"); Cmd(s, "set_heart_rate", 75) },
        Az(1260 * k, "BAV Mobitz I") { Cmd(s, "set_ecg_morphology", mapOf("av_block" to "mobitz1")) },
        Az(1380 * k, "BAV 3°") { Cmd(s, "set_ecg_morphology", mapOf("av_block" to "third")) },
        Az(1500 * k, "FV (arresto)") { Cmd(s, "set_ecg_morphology", mapOf("av_block" to "none")); Cmd(s, "set_rhythm", "vf") },
        Az(1600 * k, "sinusale 90 (ROSC)") { RhythmNow(s, "sinus"); Cmd(s, "set_heart_rate", 90); Cmd(s, "set_bp", mapOf("systolic" to 120, "diastolic" to 75)) },
        Az(1700 * k, "FA 130") { RhythmNow(s, "afib"); Cmd(s, "set_heart_rate", 130) }
    )
    val n = (T * FS).toInt()
    val ecg = DoubleArray(n); val pl = DoubleArray(n); val ar = DoubleArray(n)
    val valid = BooleanArray(n)
    val qrs = ArrayList<Qrs>()
    var si = 0; var seg = ""; var paused = false; var segAt = 0.0
    val segStart = ArrayList<Double>()
    val t0 = gNow; var lastR = -1.0
    val beepOn = ArrayList<Double>(); var lastBeep = -1.0; var spDisp = true
    for (i in 0 until n) {
        val t = t0 + i * DT
        while (si < script.size && t - t0 >= script[si].at) { script[si].f(); seg = script[si].seg; segAt = t; paused = seg == "pausa"; si++; e.updateWithSnapshot(s.snapshot()) }
        if (i % 125 == 0) { gNow = t; s.tickAtTime(gNow); val sn0 = s.snapshot(); e.updateWithSnapshot(sn0); spDisp = sn0.spo2Displayable }
        if (paused) continue
        ecg[i] = e.ecgAt(t, SLLeadII); pl[i] = e.plethAt(t); ar[i] = e.artAt(t); valid[i] = true
        val r = e.lastBeatAtOrBefore(t)
        if (r > lastR + 1e-6) {
            lastR = r; val sn = s.snapshot()
            // QRS disegnato: non quelli recuperati «nel passato» alla ripresa dopo la pausa o prima del primo campione
            val drawn = r >= t - 0.4 && i > 0 && valid[i - 1]
            segStart.add(segAt)
            qrs.add(Qrs(r, 60.0 / Math.max(1, sn.heartRate), sn.pulsePresent, seg, drawn, if (qrs.isEmpty()) 9.0 else r - qrs.last().t))
        }
        val b = polsoBeep(e, t)
        if (b > lastBeep + 0.05 && spDisp) { lastBeep = b; beepOn.add(b) }   // come il monitor: bip SpO2 solo con la SpO2 misurabile
    }
    gNow = t0 + T
    // onde per tratti continui (la pausa interrompe la curva)
    fun ondeTratti(v: DoubleArray, thr: Double): List<Onda> {
        val out = ArrayList<Onda>(); var a = 0
        while (a < n) { if (!valid[a]) { a++; continue }; var b = a; while (b < n && valid[b]) b++; out.addAll(onde(v.copyOfRange(a, b), t0 + a * DT, thr)); a = b }
        return out
    }
    val dett = System.getenv("SF_POLSO_DETT") != null
    val res = ArrayList<PolsoMisure>()
    for ((nm, v, thr, lagMin, lagMax) in listOf(Quint("ART", ar, 4.0, 0.05, 0.45), Quint("pleth", pl, 0.04, 0.10, 0.60))) {
        val m = PolsoMisure(nm); val w = ondeTratti(v, thr)
        // ANDROID (difetti clinici 22/11, EM-05): al ROSC il saturimetro cerca il segnale per ~8 s (pleth piatto, poi riparte):
        // in quella finestra niente «QRS senza onda» né «fantasma» per il pleth
        val roscAt = qrs.indices.firstOrNull { qrs[it].seg.contains("ROSC") }?.let { segStart[it] } ?: -1.0
        fun inRoscSearch(t: Double): Boolean = nm == "pleth" && roscAt > 0 && t >= roscAt && t < roscAt + 10.0
        val usate = HashMap<Int, Int>()
        for (o in w) {
            // QRS associato: l'ultimo prima del piede con ritardo plausibile
            var best = -1
            for (j in qrs.indices) { val d = o.on - qrs[j].t; if (d >= lagMin && d <= lagMax) best = j }
            if (best < 0 && inRoscSearch(o.on)) continue
            if (best < 0) { m.fantasma++; if (dett) println(F("   %s fantasma a %.2f s (piede), amp %.2f", nm, o.on - t0, o.amp)); continue }
            val q = qrs[best]
            if (!q.pulse) { m.arrestoOnde++; continue }
            if (usate.containsKey(best)) { m.doppie++; continue }
            usate[best] = 1
            m.dOn.add(o.on - q.t); m.dPk.add(o.pk - q.t); m.ampRel.add(Pair(q.prev / q.meanRR, o.amp))
            // battiti precoci lontani dai comandi (subito dopo un comando la FC del QRS è ancora quella di prima)
            if (q.prev / q.meanRR < 0.6 && q.t - segStart[best] > 2.0) m.ampPrecoci.add(o.amp)
            if (dett && q.prev / q.meanRR < 0.6) println(F("   %s onda di un battito precoce a %.2f s (%s): RR prima %.2f, RR medio %.2f, ampiezza %.2f", nm, q.t - t0, q.seg, q.prev, q.meanRR, o.amp))
            m.segOnde[q.seg] = (m.segOnde[q.seg] ?: 0) + 1
            if (q.seg.startsWith("sinusale") && !q.seg.contains("preludio") && !q.seg.contains("ROSC")) m.sinusPk.getOrPut(q.seg) { ArrayList() }.add(o.pk - q.t)
            m.perFinestra.getOrPut(((q.t - t0) / 300.0).toInt()) { ArrayList() }.add(o.pk - q.t)
        }
        for (q in qrs) if (q.drawn && q.pulse) m.segQrs[q.seg] = (m.segQrs[q.seg] ?: 0) + 1
        for (j in qrs.indices) {
            val q = qrs[j]; if (!q.pulse || usate.containsKey(j) || !q.drawn || inRoscSearch(q.t)) continue
            val i0 = ((q.t - t0) * FS).toInt(); if (i0 < 0 || i0 + 150 >= n || !valid[i0 + 150]) continue
            val prev = if (j > 0) q.t - qrs[j - 1].t else 9.0
            if (prev < 0.6 * q.meanRR) m.deficit++                  // battito precoce senza onda: deficit di polso (atteso)
            else if (prev >= 0.85 * q.meanRR && !q.seg.startsWith("FA") && !q.seg.contains("preludio")) { m.mancanti++; if (dett) println(F("   %s QRS senza onda a %.2f s (%s, RR prima %.2f)", nm, q.t - t0, q.seg, prev)) }
        }
        for (j in 1 until qrs.size) if (qrs[j].pulse && qrs[j].drawn && qrs[j].t - qrs[j - 1].t < 0.6 * qrs[j].meanRR) m.precoci++
        val norm = m.ampRel.filter { it.first in 0.95..1.6 }.map { it.second }.sorted().let { if (it.isEmpty()) 1.0 else it[it.size / 2] }
        m.precociAmpMax = m.ampPrecoci.maxOfOrNull { it / norm } ?: 0.0
        m.ampNorm = norm
        res.add(m)
    }
    // bip del saturimetro: scarto tra il bip e il piede dell'onda pleth più vicina
    val pw = ondeTratti(pl, 0.04).map { it.on }
    var sc = 0.0; var nb = 0
    for (b in beepOn) { var d = 9.0; for (x in pw) d = Math.min(d, Math.abs(x - b)); if (d < 1.0) { sc += d; nb++ } }
    // ANDROID (EM-05): le onde della ricerca SpO2 al ROSC (pleth che riparte, numero non ancora pronto: niente bip) non contano
    val roscAt0 = qrs.indices.firstOrNull { qrs[it].seg.contains("ROSC") }?.let { segStart[it] } ?: -1.0
    val pwN = pw.count { !(roscAt0 > 0 && it >= roscAt0 && it < roscAt0 + 10.0) }
    val bN = beepOn.count { !(roscAt0 > 0 && it >= roscAt0 && it < roscAt0 + 10.0) }
    return PolsoRisultato(res[0], res[1], qrs.size, if (nb > 0) sc / nb else 9.0, bN, pwN)
}

private data class Quint(val a: String, val b: DoubleArray, val c: Double, val d: Double, val e: Double)

// bip del saturimetro (sorgente SpO2): istante dell'arrivo del polso al dito, come lo usa il monitor
fun polsoBeep(e: WaveformEngine, t: Double): Double = e.lastPulseArrivalAtOrBefore(t)

fun polsoStampa(r: PolsoRisultato) {
    println(F("POLSO: %d QRS, bip SpO2 -> piede pleth: scarto medio %.0f ms", r.qrs, r.beepScarto * 1000))
    for (m in listOf(r.art, r.pleth)) {
        println(F("POLSO %s: %d onde; R->inizio %.0f ms (p5 %.0f, p95 %.0f); R->picco %.0f ms (p5 %.0f, p95 %.0f); deriva tra finestre %.0f ms",
            m.name, m.dOn.size, m.mean(m.dOn) * 1000, m.pct(m.dOn, 0.05) * 1000, m.pct(m.dOn, 0.95) * 1000,
            m.mean(m.dPk) * 1000, m.pct(m.dPk, 0.05) * 1000, m.pct(m.dPk, 0.95) * 1000, m.deriva() * 1000))
        println(F("POLSO %s: fantasma %d, doppie %d, QRS efficaci senza onda %d, precoci %d di cui senza onda (deficit) %d (onde dei precoci <= %.0f%% della normale), onde in arresto %d",
            m.name, m.fantasma, m.doppie, m.mancanti, m.precoci, m.deficit, m.precociAmpMax * 100, m.arrestoOnde))
        println(F("POLSO %s: deriva R->picco tra i tratti sinusali (minuti diversi) %.0f ms; ampiezza~RR precedente in FA r = %.2f; BAV 3°: %d onde / %d QRS; FV: %d onde",
            m.name, m.derivaSinusale() * 1000, m.corrAmp(0.5, 1.5), m.segOnde["BAV 3°"] ?: 0, m.segQrs["BAV 3°"] ?: 0, m.segOnde["FV (arresto)"] ?: 0))
        if (System.getenv("SF_POLSO_REPORT") != null)
            for (k in m.perFinestra.keys.sorted()) println(F("   %s min %2d-%2d: R->picco %.0f ms (%d onde)", m.name, k * 5, k * 5 + 5, m.perFinestra[k]!!.average() * 1000, m.perFinestra[k]!!.size))
    }
}

fun testPolso2211() {
    // --- 32 minuti: rampe di FC, FA, preludi con extrasistoli, pausa, BAV, arresto, ROSC ---
    val r = polsoSimula(32.0)
    if (System.getenv("SF_POLSO_STAMPA") != null) polsoStampa(r)
    val a = r.art; val p = r.pleth
    CHECK(a.dOn.size > 2500 && p.dOn.size > 2500) { F("onde riconosciute: ART %d, pleth %d (QRS %d)", a.dOn.size, p.dOn.size, r.qrs) }
    CHECK(a.pct(a.dOn, 0.02) >= 0.150 && a.pct(a.dOn, 0.98) <= 0.250) { F("ART: R -> piede %.0f-%.0f ms (atteso 150-250)", a.pct(a.dOn, 0.02) * 1000, a.pct(a.dOn, 0.98) * 1000) }
    CHECK(a.pct(a.dPk, 0.02) >= 0.250 && a.pct(a.dPk, 0.98) <= 0.350) { F("ART: R -> picco %.0f-%.0f ms (atteso 250-350)", a.pct(a.dPk, 0.02) * 1000, a.pct(a.dPk, 0.98) * 1000) }
    CHECK(p.pct(p.dOn, 0.02) >= 0.250 && p.pct(p.dOn, 0.98) <= 0.350) { F("pleth: R -> piede %.0f-%.0f ms (atteso 250-350)", p.pct(p.dOn, 0.02) * 1000, p.pct(p.dOn, 0.98) * 1000) }
    CHECK(p.pct(p.dPk, 0.02) >= 0.400 && p.pct(p.dPk, 0.98) <= 0.600) { F("pleth: R -> picco %.0f-%.0f ms (atteso 400-600)", p.pct(p.dPk, 0.02) * 1000, p.pct(p.dPk, 0.98) * 1000) }
    CHECK(a.mean(a.dPk) < p.mean(p.dPk) - 0.1, "il polso arriva prima alla radiale (ART) che al dito (pleth)")
    for (m in listOf(a, p)) {
        CHECK(m.derivaSinusale() < 0.010) { F("%s: nessuna deriva nel tempo (R -> picco nei tratti sinusali a minuti diversi: scarto %.0f ms)", m.name, m.derivaSinusale() * 1000) }
        CHECK(m.deriva() < 0.040) { F("%s: R -> picco per finestre di 5 min entro 40 ms (solo l'effetto della FC sul PEP): %.0f ms", m.name, m.deriva() * 1000) }
        CHECK(m.fantasma == 0 && m.doppie == 0) { F("%s: nessuna onda senza QRS (%d) né due onde per un QRS (%d)", m.name, m.fantasma, m.doppie) }
        CHECK(m.mancanti == 0) { F("%s: ogni QRS efficace ha la sua onda (%d senza)", m.name, m.mancanti) }
        CHECK(m.arrestoOnde == 0 && (m.segOnde["BAV 3°"] ?: 0) == (m.segQrs["BAV 3°"] ?: -1)) { F("%s: niente onde in arresto (%d); BAV 3°: un'onda per QRS di scappamento (%d / %d)", m.name, m.arrestoOnde, m.segOnde["BAV 3°"] ?: 0, m.segQrs["BAV 3°"] ?: 0) }
        CHECK(m.precoci >= 5 && m.deficit >= 2 && m.precociAmpMax < 0.6) { F("%s: deficit di polso nei battiti precoci: %d precoci, %d senza onda, onde <= %.0f%% della normale", m.name, m.precoci, m.deficit, m.precociAmpMax * 100) }
        CHECK(m.corrAmp(0.5, 1.5) > 0.5) { F("%s: in FA l'ampiezza segue l'RR precedente (r = %.2f)", m.name, m.corrAmp(0.5, 1.5)) }
    }
    CHECK(r.beepScarto < 0.030 && Math.abs(r.beep - r.ondePleth) <= 3) { F("bip SpO2 sull'arrivo dell'onda al dito: scarto %.0f ms, %d bip / %d onde", r.beepScarto * 1000, r.beep, r.ondePleth) }

    // --- arresto: nessuna onda di polso (TV senza polso, FV, PEA, asistolia) ---
    for (rh in listOf("vt", "vf", "pea", "asystole")) {
        val s = NewState(); Sensor(s, "art", true); val e = WaveformEngine(5); RhythmNow(s, rh); gNow += 30; s.tickAtTime(gNow); e.updateWithSnapshot(s.snapshot())
        var pmx = 0.0; var amn = 999.0; var amx = 0.0; var t = gNow
        while (t < gNow + 8) { e.ecgAt(t, SLLeadII); pmx = Math.max(pmx, e.plethAt(t)); val v = e.artAt(t); amn = Math.min(amn, v); amx = Math.max(amx, v); t += 0.004 }
        CHECK(pmx < 0.01 && amx - amn < 4 && e.lastPulseArrivalAtOrBefore(t) < 0) { F("%s: pleth piatta (%.3f), ART senza onde (%.1f mmHg), nessun bip del saturimetro", rh, pmx, amx - amn) }
    }

    // --- ROSC: la pressione sale dalla prima sistole efficace, non a scatto ---
    run {
        val s = NewState(); Sensor(s, "art", true); Cmd(s, "set_bp", mapOf("systolic" to 120, "diastolic" to 75)); val e = WaveformEngine(9)
        RhythmNow(s, "vf"); gNow += 30; s.tickAtTime(gNow); e.updateWithSnapshot(s.snapshot())
        var t = gNow; while (t < gNow + 3) { e.ecgAt(t, SLLeadII); e.artAt(t); t += 0.004 }
        RhythmNow(s, "sinus"); Cmd(s, "set_heart_rate", 80); Cmd(s, "set_bp", mapOf("systolic" to 120, "diastolic" to 75)); e.updateWithSnapshot(s.snapshot())
        var maxStep = 0.0; var prev = e.artAt(t); val t1 = t; var first = -1.0
        while (t < t1 + 4) { e.ecgAt(t, SLLeadII); val v = e.artAt(t); maxStep = Math.max(maxStep, Math.abs(v - prev)); if (first < 0 && v > prev + 0.5) first = t; prev = v; t += 0.004 }
        val r1 = e.lastBeatAtOrBefore(first)
        CHECK(maxStep < 8 && first > 0 && r1 > 0 && first - r1 in 0.15..0.25) { F("ROSC: nessun gradino (max %.1f mmHg in 4 ms), la pressione sale %.0f ms dopo il primo QRS", maxStep, (first - r1) * 1000) }
    }

    // --- EF-19: la curva ART è continua (nessun gradino) nei preludi verso l'asistolia, nelle rampe della PA e all'arresto ---
    run {
        var worst = 0.0; var where = ""
        for (k in 0 until 8) {
            val s = NewState(); Sensor(s, "art", true); val e = WaveformEngine(11 + k)
            Cmd(s, "set_bp", mapOf("systolic" to 120, "diastolic" to 75)); e.updateWithSnapshot(s.snapshot())
            var t = gNow; val t0 = gNow; var n = 0; var v1 = Double.NaN; var v2 = Double.NaN; var v3 = Double.NaN
            val cmdAt = t0 + 6.0; var sent = false
            while (t < t0 + 40.0) {
                if (!sent && t >= cmdAt) {
                    sent = true
                    if (k < 6) Cmd(s, "set_rhythm", "asystole")                  // preludi diversi (blocchi, pause, scappamenti)
                    else if (k == 6) Cmd(s, "transition", mapOf("params" to mapOf("systolic_mmhg" to 70, "diastolic_mmhg" to 40), "duration_s" to 20))
                    else Cmd(s, "set_rhythm", "vf", null)                         // arresto improvviso (preludio della FV)
                    e.updateWithSnapshot(s.snapshot())
                }
                if (n % 125 == 0) { gNow = t; s.tickAtTime(gNow); e.updateWithSnapshot(s.snapshot()) }
                e.ecgAt(t, SLLeadII); val v = e.artAt(t)
                // gradino = differenza isolata: un campione salta molto più dei vicini (le pendenze e gli spigoli dell'onda no)
                if (!v3.isNaN()) { val sp = Math.abs(v1 - v2) - Math.max(Math.abs(v2 - v3), Math.abs(v - v1)); if (sp > worst) { worst = sp; where = F("prova %d a %.2f s: %.2f mmHg", k, t - t0, sp) } }
                v3 = v2; v2 = v1; v1 = v; t += 0.004; n++
            }
            gNow = t
        }
        CHECK(worst < 0.8) { "ART continua nei preludi verso l'asistolia, nelle rampe della PA e all'arresto (salto massimo in 4 ms: $where)" }
    }

    // --- tracciati: colonna di ogni istante ancorata al tempo (stessa per tracce con la stessa velocità e larghezza) ---
    run {
        val w = 1100; val sp = 90.0
        val a1 = SFSweep(sp); val b1 = SFSweep(sp); val c1 = SFSweep(sp)
        a1.reset(1000.0); b1.reset(1003.7)                                // traccia creata dopo (cambio di layout)
        var ok = true; var t = 1004.0
        while (t < 1200.0) {
            a1.advance(t)
            val hidden = t > 1100 && t < 1106                             // vista nascosta / ferma per 6 s (nessun disegno)
            if (!hidden) b1.advance(t)
            if (t > 1050 && t < 1050.02) b1.reset(t)                      // cambio di derivazione: solo l'ECG si azzera
            if (t > 1106) { if (c1.lastPix == Long.MIN_VALUE) c1.reset(t); c1.advance(t) }   // traccia nuova (cambio di layout)
            if (!hidden && a1.column(w) != b1.column(w)) ok = false
            if (c1.lastPix != Long.MIN_VALUE && c1.column(w) != a1.column(w)) ok = false
            t += 0.016
        }
        CHECK(ok, "tracciati con la stessa velocità: cursore alla stessa colonna dopo cambio di layout, cambio di derivazione e pausa")
        val e1 = SFSweep(25.0 * 4); val e2 = SFSweep(12.5 * 4)          // 25 e 12,5 mm/s
        e1.reset(500.0); e2.reset(500.0); var okT = true; t = 500.0
        while (t < 800.0) { e1.advance(t); e2.advance(t); if (Math.abs((e1.lastPix + 1) / e1.speed - (e2.lastPix + 1) / e2.speed) > 1.0 / e2.speed + 1e-9) okT = false; t += 0.0167 }
        CHECK(okT, "velocità diverse (25 e 12,5 mm/s): l'ultimo campione di entrambe è lo stesso istante (entro un pixel)")
    }
}
