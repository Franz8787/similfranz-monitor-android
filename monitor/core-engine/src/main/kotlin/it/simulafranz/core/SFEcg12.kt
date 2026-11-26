// ANDROID (12 derivazioni 22/11) — file nuovo, solo Android (sull'iPad non c'è).
// SimulaFranz_Monitor — MODELLO dell'ECG a 12 derivazioni: forma del complesso P-QRS-T di un battito condotto per ognuna delle
// 12 derivazioni, a partire dalla morfologia impostata (SFEcgMorphology.kt), più le misure (PR, QRS, QT/QTc, assi) e
// l'interpretazione automatica stampata sulla carta (sobria, come fanno i dispositivi). Ricerca e fonti: ECG_12D.md.
//
// Come è fatto (scelta documentata in ECG_12D.md, «Modello»):
//  - le derivazioni INDIPENDENTI sono 8: I, II, V1-V6. Ognuna ha un modello a componenti (somma di gaussiane: P eventualmente
//    bifasica, q/R/S/R' del QRS, onda delta, tratto ST con livello al punto J e al picco della T, onda J, T, U), con ampiezze e
//    tempi tipici della letteratura per un adulto con asse ~+50°;
//  - III, aVR, aVL, aVF si RICAVANO da I e II con le leggi di Einthoven e Goldberger (III = II − I, aVR = −(I+II)/2,
//    aVL = I − II/2, aVF = II − I/2): asse, alterazioni speculari e coerenza fra le derivazioni periferiche sono quindi
//    automatiche (es. STEMI inferiore con III > II -> sottoslivellamento in aVL e I, come nell'occlusione della coronaria dx);
//  - le alterazioni modificano i parametri per derivazione (blocchi di branca e WPW sostituiscono il QRS e la ripolarizzazione
//    secondaria; ST per territorio con T iperacute e lieve perdita di R; elettroliti/QT/ipotermia cambiano tempi e onde T/U);
//  - i valori «normali» di II e V5 sono ESATTAMENTE quelli del monitor di prima (ecgNarrow): senza morfologia il tracciato
//    del monitor resta identico (regola «non snaturare»).
package it.simulafranz.core

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

// Derivazioni della carta (ordine standard)
const val SL12I = 0
const val SL12II = 1
const val SL12III = 2
const val SL12aVR = 3
const val SL12aVL = 4
const val SL12aVF = 5
const val SL12V1 = 6
const val SL12V2 = 7
const val SL12V3 = 8
const val SL12V4 = 9
const val SL12V5 = 10
const val SL12V6 = 11
val SL12LeadNames = listOf("I", "II", "III", "aVR", "aVL", "aVF", "V1", "V2", "V3", "V4", "V5", "V6")

// derivazioni indipendenti (indice interno): 0 I, 1 II, 2..7 V1..V6
internal const val SLInd = 8
internal fun SL12ToInd(l: Int): Int = if (l <= SL12II) l else if (l >= SL12V1) l - 4 else -1
/** Le 12 derivazioni dalle 8 indipendenti (Einthoven / Goldberger) */
internal fun SL12FromInd(v: DoubleArray, l: Int): Double = when (l) {
    SL12I -> v[0]; SL12II -> v[1]; SL12III -> v[1] - v[0]
    SL12aVR -> -(v[0] + v[1]) / 2; SL12aVL -> v[0] - v[1] / 2; SL12aVF -> v[1] - v[0] / 2
    else -> v[l - 4]
}

private fun g(x: Double, mu: Double, s: Double): Double { val z = (x - mu) / s; return exp(-0.5 * z * z) }
private fun sm(x: Double): Double = if (x <= 0) 0.0 else if (x >= 1) 1.0 else x * x * (3 - 2 * x)

/** Parametri di una derivazione indipendente (mV, s; tempi relativi al picco R = 0; tPos/tSig in frazioni del QT) */
internal class SLLeadTpl {
    var pA = 0.0; var pA2 = 0.0; var pSig = 0.025      // P (pA2: parte terminale negativa, V1)
    var prSeg = 0.0                                   // livello del tratto PR (pericardite)
    var q = DoubleArray(30); var nq = 0               // componenti del QRS: ampiezza, centro, sigma
    var delta = 0.0                                   // onda delta (WPW)
    var stJ = 0.0; var stT = 0.0                      // livello dello ST al punto J e al picco della T
    var stCvx = 0.0                                   // convessità dello ST (STEMI: «a cupola»)
    var jWave = 0.0                                   // onda J (Osborn / cupola del Brugada)
    var tA = 0.0; var tPos = 0.62; var tSig = 0.11    // T
    var uA = 0.0                                      // U
    fun add(a: Double, mu: Double, s: Double) { q[3 * nq] = a; q[3 * nq + 1] = mu; q[3 * nq + 2] = s; nq++ }
    fun clearQrs() { nq = 0 }
    fun scalePos(k: Double, thr: Double = 0.25) { for (i in 0 until nq) if (q[3 * i] > thr) q[3 * i] *= k }
    fun scaleNeg(k: Double, thr: Double = -0.25) { for (i in 0 until nq) if (q[3 * i] < thr) q[3 * i] *= k }
    fun scaleAll(k: Double) { for (i in 0 until nq) q[3 * i] *= k }
}

/** Misure della carta (ms, gradi); null dove non misurabili */
class SLEcgMeasures(val prMs: Int?, val qrsMs: Int, val qtMs: Int, val qtcMs: Int, val axisP: Int?, val axisQrs: Int, val axisT: Int)

/** Modello di un battito condotto per la morfologia `morph` (immutabile, costruito una volta per morfologia) */
class SLEcgModel internal constructor(val morph: SLEcgMorph, internal val t: Array<SLLeadTpl>, val qrsK: Double, val qtK: Double,
                                      val prFixed: Double, val qrsOn: Double, val jPt: Double, val alternans: Double) {

    // centro della P rispetto al picco R: di serie quello del monitor di prima; con un PR fisso (o del battito) dal PR
    internal fun pCenter(rr: Double, pr: Double): Double {
        val p = if (pr > 0) pr else prFixed
        if (p <= 0) return -0.16 * min(1.0, sqrt(rr)) + (qrsOn * qrsK + 0.042)     // QRS che inizia prima/dopo: PR invariato
        return qrsOn * qrsK + 0.05 - p
    }
    internal fun qtOf(rr: Double): Double = 0.40 * sqrt(min(max(rr, 0.25), 2.0)) * qtK

    /** Derivazione indipendente i (0 I, 1 II, 2..7 V1..V6) all'istante tau dal picco R. qa = fattore del QRS (alternanza) */
    internal fun ind(i: Int, tau: Double, rr: Double, hasP: Boolean, pr: Double = -1.0, qa: Double = 1.0, pOnly: Boolean = false): Double {
        val p = t[i]
        var v = 0.0
        if (pOnly) return p.pA * g(tau, 0.0, p.pSig) + p.pA2 * g(tau, 0.035, 0.018)
        if (hasP && (p.pA != 0.0 || p.pA2 != 0.0)) {
            val pc = pCenter(rr, pr)
            v += p.pA * g(tau, pc, p.pSig)
            if (p.pA2 != 0.0) v += p.pA2 * g(tau, pc + 0.035, 0.018)
            if (p.prSeg != 0.0) { val a = pc + 0.04; val b = qrsOn * qrsK; if (b > a) v += p.prSeg * sm((tau - a) / 0.02) * (1 - sm((tau - b + 0.01) / 0.012)) }
        }
        for (k in 0 until p.nq) v += p.q[3 * k] * qa * g(tau, p.q[3 * k + 1] * qrsK, p.q[3 * k + 2] * qrsK)
        if (p.delta != 0.0) { val on = qrsOn * qrsK; v += p.delta * qa * sm((tau - on) / 0.045) * (1 - sm((tau + 0.004) / 0.035)) }
        val qt = qtOf(rr)
        val tp = p.tPos * qt
        if (p.stJ != 0.0 || p.stT != 0.0) {
            val j = jPt * qrsK; val te = tp + 2.2 * p.tSig * qt
            val rise = sm((tau - j + 0.014) / 0.022)
            if (rise > 0) {
                val b = if (tau <= j) p.stJ else if (tau < tp) { val x = (tau - j) / max(0.02, tp - j); p.stJ + (p.stT - p.stJ) * x + p.stCvx * Math.sin(Math.PI * x) }
                        else p.stT * (1 - sm((tau - tp) / max(0.02, te - tp)))
                v += rise * b
            }
        }
        if (p.jWave != 0.0) v += p.jWave * g(tau, jPt * qrsK + 0.006, 0.016)
        v += p.tA * g(tau, tp, p.tSig * qt)
        if (p.uA != 0.0) v += p.uA * g(tau, tp + 2.2 * p.tSig * qt + 0.07, 0.045)
        return v
    }

    /** Una delle 12 derivazioni (SL12…) */
    fun lead(l: Int, tau: Double, rr: Double, hasP: Boolean, pr: Double = -1.0): Double {
        val i = SL12ToInd(l)
        if (i >= 0) return ind(i, tau, rr, hasP, pr)
        val a = ind(0, tau, rr, hasP, pr); val b = ind(1, tau, rr, hasP, pr)
        return when (l) { SL12III -> b - a; SL12aVR -> -(a + b) / 2; SL12aVL -> a - b / 2; else -> b - a / 2 }
    }

    /** Misure per un intervallo R-R (PR del battito se il blocco lo cambia) */
    fun measures(rr: Double, hasP: Boolean, pr: Double = -1.0): SLEcgMeasures {
        val ii = t[1]
        val qt = qtOf(rr)
        val qtS = -qrsOn * qrsK + (ii.tPos + 2.2 * ii.tSig) * qt
        val prS = qrsOn * qrsK - pCenter(rr, pr) + 0.05
        // asse del QRS dalle forze dei primi ~60 ms (come fanno gli algoritmi: il ritardo terminale del BBD non sposta l'asse)
        fun area(p: SLLeadTpl): Double { var a = 0.0; for (k in 0 until p.nq) if (p.q[3 * k + 1] < 0.045) a += p.q[3 * k] * p.q[3 * k + 2]; return a + p.delta * 0.03 }
        val aI = area(t[0]); val aF = area(t[1]) - aI / 2
        val axQ = Math.toDegrees(atan2(aF * 2 / sqrt(3.0), aI))
        val pI = t[0].pA; val pF = t[1].pA - pI / 2
        val tI = t[0].tA * t[0].tSig; val tF = t[1].tA * t[1].tSig - tI / 2
        return SLEcgMeasures(if (hasP && (pI != 0.0 || t[1].pA != 0.0)) Math.round(prS * 1000).toInt() else null,
            Math.round((jPt - qrsOn) * qrsK * 1000).toInt(), Math.round(qtS * 1000).toInt(), Math.round(qtS / sqrt(max(rr, 0.25)) * 1000).toInt(),
            if (hasP && abs(pI) + abs(pF) > 0.01) Math.round(Math.toDegrees(atan2(pF * 2 / sqrt(3.0), pI))).toInt() else null,
            Math.round(axQ).toInt(), Math.round(Math.toDegrees(atan2(tF * 2 / sqrt(3.0), tI))).toInt())
    }
}

object SFEcg12 {

    // ST per territorio, mm (0,1 mV) nelle derivazioni indipendenti [I, II, V1, V2, V3, V4, V5, V6]; le altre 4 ne derivano.
    // Fonti: Wagner GS et al., AHA/ACCF/HRS Recommendations part VI (Circulation 2009;119:e262); Thygesen K et al., Fourth
    // Universal Definition of MI (Eur Heart J 2019;40:237); Byrne RA et al., ESC 2023 ACS (Eur Heart J 2023;44:3720);
    // Zimetbaum PJ, Josephson ME, NEJM 2003;348:933 (localizzazione dell'occlusione); Birnbaum Y, NEJM 2003 (aVR).
    private val ST = mapOf(
        "stemi_inferior" to doubleArrayOf(-0.7, 2.6, 0.0, -0.6, -0.4, 0.0, 0.3, 0.5),
        "stemi_inferoposterior" to doubleArrayOf(-0.7, 2.6, -1.0, -2.0, -1.6, -0.6, 0.3, 0.6),
        "stemi_anterior" to doubleArrayOf(1.0, -0.6, 1.5, 3.5, 3.5, 2.5, 1.2, 0.5),
        "stemi_lateral" to doubleArrayOf(1.5, 0.2, -0.3, -0.5, 0.0, 0.5, 1.8, 2.0),
        "stemi_posterior" to doubleArrayOf(0.2, 0.3, -1.5, -2.5, -2.0, -0.8, 0.0, 0.3),
        "nstemi_depression" to doubleArrayOf(-0.8, -1.2, 0.0, -0.5, -1.2, -2.0, -2.0, -1.5),
        "left_main" to doubleArrayOf(-1.5, -2.0, 0.8, -0.5, -2.0, -3.0, -3.0, -2.5),
        "pericarditis" to doubleArrayOf(1.0, 1.5, 0.0, 1.0, 1.5, 1.5, 1.5, 1.0),
        "de_winter" to doubleArrayOf(-0.5, -1.0, -0.5, -2.0, -2.5, -2.5, -1.5, -1.0),
        "wellens" to doubleArrayOf(0.0, 0.0, 0.0, 0.5, 0.3, 0.0, 0.0, 0.0))

    private fun normal(): Array<SLLeadTpl> {
        val a = Array(SLInd) { SLLeadTpl() }
        // I
        a[0].apply { pA = 0.07; add(-0.06, -0.026, 0.008); add(0.60, -0.002, 0.010); add(-0.10, 0.026, 0.009); tA = 0.20 }
        // II: ESATTAMENTE il complesso del monitor di prima (ecgNarrow, derivazione II)
        a[1].apply { pA = 0.12; add(-0.10, -0.028, 0.008); add(1.00, 0.000, 0.010); add(-0.25, 0.026, 0.009); tA = 0.28 }
        // V1: P bifasica, rS, T piccola; lieve sopraslivellamento del punto J (normale in V1-V3)
        a[2].apply { pA = 0.05; pA2 = -0.045; add(0.22, -0.020, 0.009); add(-0.95, 0.012, 0.013); tA = 0.06; tSig = 0.12; stJ = 0.03; stT = 0.02 }
        a[3].apply { pA = 0.06; add(0.38, -0.016, 0.010); add(-1.45, 0.014, 0.013); tA = 0.48; tSig = 0.12; stJ = 0.06; stT = 0.05 }
        a[4].apply { pA = 0.07; add(0.70, -0.010, 0.010); add(-0.95, 0.018, 0.012); tA = 0.46; tSig = 0.12; stJ = 0.05; stT = 0.04 }
        a[5].apply { pA = 0.08; add(-0.03, -0.026, 0.007); add(1.30, -0.003, 0.0105); add(-0.45, 0.022, 0.010); tA = 0.45; tSig = 0.12 }
        // V5: ESATTAMENTE il complesso del monitor di prima (ecgNarrow, derivazione V)
        a[6].apply { pA = 0.09; pSig = 0.024; add(-0.08, -0.024, 0.007); add(1.45, 0.002, 0.0105); add(-0.18, 0.028, 0.008); tA = 0.40; tPos = 0.63; tSig = 0.12 }
        a[7].apply { pA = 0.08; add(-0.07, -0.024, 0.007); add(1.15, 0.003, 0.0105); add(-0.10, 0.028, 0.008); tA = 0.30; tPos = 0.63; tSig = 0.12 }
        return a
    }

    private val cache = HashMap<SLEcgMorph, SLEcgModel>()

    /** Modello per la morfologia (memorizzato: si costruisce una volta) */
    fun model(m: SLEcgMorph): SLEcgModel = synchronized(cache) { cache.getOrPut(m) { build(m) } }

    private fun build(m: SLEcgMorph): SLEcgModel {
        val t = normal()
        var qrsK = 1.0; var qtK = 1.0; var prFixed = -1.0; var qrsOn = -0.042; var jPt = 0.046; var alt = 0.0
        if (m.avBlock == "first") prFixed = (if (m.prMs > 0) m.prMs else 280) / 1000.0

        // --- emiblocco anteriore sinistro (asse −45/−60°, qR in I e aVL, rS in II, III, aVF; Elizari MV, Circulation 2007) ---
        if (m.bbb == "lafb" || m.bbb == "rbbb_lafb") {
            t[0].clearQrs(); t[0].add(-0.10, -0.026, 0.008); t[0].add(0.80, -0.002, 0.010); t[0].add(-0.05, 0.026, 0.009)
            t[1].clearQrs(); t[1].add(0.30, -0.022, 0.009); t[1].add(-0.58, 0.010, 0.012)
            t[1].tA = 0.18; t[0].tA = 0.22
        }
        // --- blocco di branca destro (QRS ≥ 120 ms, rSR' in V1-V2, S larga in I, aVL, V5-V6, T secondarie negative in V1-V3;
        //     Surawicz B et al., AHA/ACCF/HRS part III, Circulation 2009;119:e235) ---
        if (m.bbb == "rbbb" || m.bbb == "rbbb_lafb") {
            val term = doubleArrayOf(-0.28, -0.12, 0.80, 0.55, 0.20, -0.20, -0.28, -0.30)
            for (i in 0 until SLInd) t[i].add(term[i], 0.062, 0.019)
            t[2].q[3 * 1] = -0.50; t[2].q[3 * 1 + 1] = 0.008; t[2].q[3 * 1 + 2] = 0.011
            t[3].scaleNeg(0.65)
            t[2].tA = -0.22; t[3].tA = -0.16; t[4].tA = 0.10
            t[2].stJ = -0.04; t[2].stT = -0.06; t[3].stJ = -0.03; t[3].stT = -0.05; t[4].stJ = 0.0; t[4].stT = 0.0
            jPt = 0.095
        }
        // --- blocco di branca sinistro (QRS ≥ 130-140 ms, niente q settale in I/V5/V6, R larga e intaccata in I, aVL, V5-V6,
        //     QS/rS profonde in V1-V3, ST-T secondarie discordanti; Strauss DG, Am J Cardiol 2011; Surawicz 2009) ---
        if (m.bbb == "lbbb") {
            val q = arrayOf(doubleArrayOf(0.62, -0.022, 0.019, 0.80, 0.036, 0.021), doubleArrayOf(0.36, -0.022, 0.019, 0.42, 0.036, 0.021),
                doubleArrayOf(0.10, -0.034, 0.008, -1.50, 0.018, 0.028), doubleArrayOf(0.15, -0.032, 0.008, -2.10, 0.020, 0.029),
                doubleArrayOf(0.20, -0.030, 0.009, -1.70, 0.024, 0.028), doubleArrayOf(0.45, -0.018, 0.016, -0.90, 0.040, 0.022),
                doubleArrayOf(0.85, -0.020, 0.019, 1.00, 0.036, 0.021), doubleArrayOf(0.80, -0.020, 0.019, 0.95, 0.036, 0.021))
            val tw = doubleArrayOf(-0.25, -0.10, 0.35, 0.50, 0.45, 0.15, -0.30, -0.28)
            val sj = doubleArrayOf(-0.06, -0.02, 0.15, 0.25, 0.20, 0.05, -0.10, -0.10)
            val stt = doubleArrayOf(-0.10, -0.04, 0.12, 0.20, 0.15, 0.04, -0.14, -0.14)
            for (i in 0 until SLInd) {
                t[i].clearQrs(); t[i].add(q[i][0], q[i][1], q[i][2]); t[i].add(q[i][3], q[i][4], q[i][5])
                t[i].tA = tw[i]; t[i].stJ = sj[i]; t[i].stT = stt[i]
            }
            qrsOn = -0.058; jPt = 0.096
        }
        // --- preeccitazione (WPW, via accessoria laterale sinistra, la più frequente): PR < 120 ms, onda delta, QRS > 110 ms,
        //     R dominante in V1, delta negativa in I/aVL (pseudo-onda Q); ESC 2019 SVT (Brugada J, Eur Heart J 2020;41:655) ---
        if (m.has("wpw")) {
            val d = doubleArrayOf(-0.12, 0.22, 0.30, 0.35, 0.32, 0.30, 0.25, 0.20)
            for (i in 0 until SLInd) t[i].delta = d[i]
            t[2].add(0.40, -0.006, 0.011); t[2].scaleNeg(0.55); t[3].scaleNeg(0.75)
            t[0].tA = -0.04
            prFixed = 0.10; qrsOn = -0.085
        }
        // --- ipertrofia ventricolare sinistra (Sokolow-Lyon SV1 + RV5/V6 ≥ 3,5 mV; Cornell; strain: ST discendente e T
        //     asimmetriche negative in I, aVL, V5-V6; Hancock EW et al., AHA/ACCF/HRS part V, Circulation 2009;119:e251) ---
        if (m.has("lvh")) {
            t[2].scaleNeg(1.8); t[3].scaleNeg(1.7); t[4].scaleNeg(1.5)
            t[5].scalePos(1.3); t[6].scalePos(1.65); t[7].scalePos(1.75); t[0].scalePos(1.5); t[1].scalePos(1.1)
            qrsK *= 1.1
            if (m.bbb != "lbbb") {
                t[6].stJ = -0.07; t[6].stT = -0.15; t[6].tA = -0.28; t[7].stJ = -0.07; t[7].stT = -0.14; t[7].tA = -0.24
                t[0].stJ = -0.04; t[0].stT = -0.08; t[0].tA = -0.15; t[1].tA *= 0.4
                t[2].stJ += 0.08; t[2].stT += 0.08; t[2].tA += 0.15; t[3].stJ += 0.08; t[3].stT += 0.06
            }
        }
        // --- embolia polmonare: S1Q3T3, T negative V1-V4, ritardo destro (r' in V1); Konstantinides SV et al., ESC 2019 PE;
        //     Shopp JD et al., Ann Emerg Med 2015 (inversione T anteriore) ---
        if (m.has("pe")) {
            t[0].q[0] = 0.06; t[0].add(-0.25, 0.034, 0.012); t[0].tA = 0.25; t[1].tA = 0.12
            if (m.bbb == "none" || m.bbb == "lafb") t[2].add(0.22, 0.045, 0.012)
            t[2].tA = -0.15; t[3].tA = -0.25; t[4].tA = -0.18; t[5].tA = -0.08
        }
        // --- Brugada tipo 1: ST a tenda (coved) ≥ 2 mm in V1-V2 che scende in una T negativa; Antzelevitch C et al., J-wave
        //     syndromes expert consensus, Heart Rhythm 2016;13:e295 ---
        if (m.has("brugada")) {
            t[2].scaleNeg(0.5); t[2].stJ = 0.30; t[2].stT = 0.02; t[2].jWave = 0.12; t[2].tA = -0.20
            t[3].stJ = 0.25; t[3].stT = 0.02; t[3].jWave = 0.10; t[3].tA = -0.12
            t[4].stJ = 0.06; t[4].stT = 0.04
        }
        // --- ipotermia: onda J di Osborn, intervalli allungati; Mattu A et al., Am J Emerg Med 2002;20:314 ---
        if (m.has("hypothermia")) {
            val j = doubleArrayOf(0.10, 0.20, 0.02, 0.08, 0.15, 0.25, 0.25, 0.20)
            for (i in 0 until SLInd) t[i].jWave += j[i]
            qtK *= 1.15; qrsK *= 1.1; if (prFixed < 0) prFixed = 0.22
        }

        // --- ST per territorio (con T iperacute e lieve perdita di R nei sopraslivellamenti) ---
        val stArr = ST[m.st]
        if (stArr != null) {
            // scala: st_mm = mm nella derivazione più alterata fra le 12
            val all = DoubleArray(12) { SL12FromInd(stArr, it) }
            val maxAbs = all.maxOf { abs(it) }
            val k = if (m.stMm > 0 && maxAbs > 0) m.stMm / maxAbs else 1.0
            for (i in 0 until SLInd) {
                val e = stArr[i] * 0.1 * k
                val p = t[i]
                when (m.st) {
                    "pericarditis" -> { p.stJ += 0.8 * e; p.stT += e }                               // concavo, senza speculari
                    "de_winter" -> { p.stJ += e; if (i >= 3 && i <= 5) p.tA += 0.45 * k else if (i >= 2) p.tA += 0.2 * k }  // ST ascendente, T alte
                    "wellens" -> { p.stJ += e; p.stT += 0.5 * e }
                    else -> {
                        if (e > 0) { p.stJ += e; p.stT += 0.9 * e; p.stCvx += 0.25 * e; p.tA += 0.6 * e; p.scalePos(max(0.7, 1 - 0.9 * e)) }
                        else { p.stJ += e; p.stT += e; p.tA += 0.35 * e }                             // orizzontale, T più piatte
                    }
                }
            }
            if (m.st == "pericarditis") {
                val pr = doubleArrayOf(-0.03, -0.08, 0.02, -0.05, -0.05, -0.05, -0.05, -0.04)       // PR sottoslivellato (in aVR sopra)
                for (i in 0 until SLInd) t[i].prSeg = pr[i] * k
            }
            if (m.st == "stemi_posterior" || m.st == "stemi_inferoposterior") {
                // perdita delle forze posteriori: R alta e larga in V1-V2, T positive alte (immagine speculare di Q e ST posteriori)
                t[2].add(0.60, 0.002, 0.013); t[2].scaleNeg(0.45); t[3].add(0.70, 0.002, 0.013); t[3].scaleNeg(0.6)
                t[2].tA = 0.25; t[3].tA = 0.55; t[4].tA = 0.50
            }
            if (m.st == "wellens") {
                // tipo B (75%): T negative profonde e simmetriche in V2-V3 (anche V1, V4), R conservata, ST isoelettrico
                t[2].tA = -0.15; t[3].tA = -0.55; t[4].tA = -0.50; t[5].tA = -0.30; t[6].tA = 0.05
                for (i in 2..5) { t[i].tSig = 0.09 }
            }
        }

        // --- elettroliti e QT ---
        if (m.has("hyperkalemia")) {
            // K ~6,5-7,5: T alte, strette e appuntite, P più piccole, PR e QRS un po' più lunghi (Mattu A, Am J Emerg Med 2000;18:721;
            // Littmann L, Gibbs MA, J Electrocardiol 2018 per l'iperkaliemia in emergenza)
            for (p in t) { p.tA = if (p.tA > 0) p.tA * 2.4 + 0.05 else p.tA * 1.3; p.tSig *= 0.55; p.tPos *= 0.93; p.pA *= 0.5; p.pA2 *= 0.5 }
            qrsK *= 1.15; if (prFixed < 0) prFixed = 0.20 else prFixed += 0.03
        }
        if (m.has("hyperkalemia_severe")) {
            // K > 8: P assenti, QRS molto largo che si fonde con la T (verso l'onda sinusoidale), bradicardia
            for (p in t) { p.tA = if (p.tA > 0) p.tA * 1.8 + 0.10 else p.tA * 1.2 - 0.05; p.tSig *= 0.9; p.pA = 0.0; p.pA2 = 0.0; p.scaleAll(0.8); p.stJ *= 0.5; p.stT *= 0.5 }
            qrsK *= 2.1
        }
        if (m.has("hypokalemia")) {
            // T appiattite, ST lievemente sottoslivellato, onde U evidenti (V2-V3), QU lungo (Diercks DB et al., J Emerg Med 2004)
            for ((i, p) in t.withIndex()) {
                val t0 = p.tA
                p.uA = if (i == 3 || i == 4) 0.22 else max(0.04, 0.35 * abs(t0))
                if (t0 > 0.1) { p.tA = t0 * 0.3; p.stJ -= 0.05; p.stT -= 0.06 }
            }
        }
        if (m.has("long_qt")) { qtK *= 1.38; for (p in t) p.tSig *= 1.15 }      // QTc ~520 ms (Schwartz PJ, ESC 2022 VA)
        if (m.has("low_voltage")) { for (p in t) { p.scaleAll(0.33); p.delta *= 0.33; p.tA *= 0.5; p.pA *= 0.8; p.pA2 *= 0.8 }; alt = 0.22 }
        return SLEcgModel(m, t, qrsK, qtK, prFixed, qrsOn, jPt, alt)
    }

    // MARK: - interpretazione automatica (sobria, come i dispositivi; non sempre «la risposta giusta»: vedi ECG_12D.md)

    class SLEcgReading(val headline: String?, val lines: List<String>)

    /**
     * Testo stampato sulla carta. rhythm = ritmo ufficiale; hr = FC ventricolare; atrial = frequenza atriale; avActive = blocco
     * in atto; paced = stimolazione con cattura; meas = misure (null se non misurabili).
     */
    fun interpret(m: SLEcgMorph, rhythm: String, hr: Int, atrial: Int, avActive: Boolean, paced: Boolean, meas: SLEcgMeasures?): SLEcgReading {
        val l = ArrayList<String>()
        if (!m.interpretation) return SLEcgReading(null, listOf("Interpretazione automatica non attiva"))
        var head: String? = null
        when {
            paced -> l.add("Ritmo da pacemaker (stimolazione ventricolare)")
            rhythm == SLRhythmVentricularFib -> l.add("Fibrillazione ventricolare")
            rhythm == SLRhythmAsystole -> l.add(if (avActive) "Asistolia ventricolare (solo onde P)" else "Asistolia")
            rhythm == SLRhythmVentricularTachy || rhythm == SLRhythmVentricularTachyPulse -> l.add("Tachicardia a complessi larghi (tachicardia ventricolare)")
            rhythm == SLRhythmPEA -> l.add("Ritmo a complessi larghi")
            rhythm == SLRhythmAtrialFibrillation -> l.add(if (avActive) "Fibrillazione atriale con blocco AV completo e ritmo di scappamento"
                else if (hr > 100) "Fibrillazione atriale con risposta ventricolare rapida" else if (hr < 60) "Fibrillazione atriale con risposta ventricolare lenta" else "Fibrillazione atriale")
            else -> {
                val a = if (avActive) atrial else hr
                if (m.has("hyperkalemia_severe")) l.add("Ritmo regolare a QRS largo, onde P non visibili")
                else l.add(if (a > 100) "Tachicardia sinusale" else if (a < 60) "Bradicardia sinusale" else "Ritmo sinusale")
                if (avActive || m.avBlock == "first") when (m.avBlock) {
                    "first" -> l.add("Blocco AV di 1° grado")
                    "mobitz1" -> l.add("Blocco AV di 2° grado tipo Mobitz I (Wenckebach)")
                    "mobitz2" -> l.add("Blocco AV di 2° grado tipo Mobitz II")
                    "2to1" -> l.add("Blocco AV di 2° grado con conduzione 2:1")
                    "high_grade" -> l.add("Blocco AV avanzato (conduzione 3:1)")
                    "third" -> l.add("Blocco AV completo (3° grado), " + (if (m.escape == "junctional") "ritmo di scappamento giunzionale" else "ritmo di scappamento ventricolare"))
                }
            }
        }
        val conducted = !paced && ((rhythm == SLRhythmSinus || rhythm == SLRhythmSinusTachycardia || rhythm == SLRhythmSinusBradycardia) || rhythm == SLRhythmAtrialFibrillation) &&
                        !(m.avBlock == "third" && avActive && m.escape == "ventricular")
        if (conducted) {
            when (m.bbb) {
                "rbbb" -> l.add("Blocco di branca destro completo")
                "lbbb" -> l.add("Blocco di branca sinistro completo")
                "lafb" -> l.add("Emiblocco anteriore sinistro")
                "rbbb_lafb" -> l.add("Blocco bifascicolare: blocco di branca destro + emiblocco anteriore sinistro")
            }
            if (m.has("wpw")) l.add("Preeccitazione ventricolare (pattern WPW): PR corto, onda delta")
            if (m.has("lvh")) l.add("Ipertrofia ventricolare sinistra con alterazioni della ripolarizzazione")
            if (m.has("low_voltage")) l.add("Bassi voltaggi del QRS, alternanza elettrica")
            if (meas != null && m.bbb != "lafb" && m.bbb != "rbbb_lafb") {
                if (meas.axisQrs < -30 && meas.axisQrs > -90) l.add("Deviazione assiale sinistra")
                else if (meas.axisQrs > 100 && meas.axisQrs <= 180) l.add("Deviazione assiale destra")
            }
            when (m.st) {
                "stemi_inferior" -> { head = "*** STEMI ACUTO ***"; l.add("Sopraslivellamento ST in II, III, aVF; sottoslivellamento speculare in I, aVL"); l.add("Infarto acuto inferiore") }
                "stemi_inferoposterior" -> { head = "*** STEMI ACUTO ***"; l.add("Sopraslivellamento ST in II, III, aVF; sottoslivellamento ST in V1-V3"); l.add("Infarto acuto inferiore, probabile estensione posteriore") }
                "stemi_anterior" -> { head = "*** STEMI ACUTO ***"; l.add("Sopraslivellamento ST in V1-V5, I, aVL; sottoslivellamento speculare in II, III, aVF"); l.add("Infarto acuto anteriore esteso") }
                "stemi_lateral" -> { head = "*** STEMI ACUTO ***"; l.add("Sopraslivellamento ST in I, aVL, V5, V6; sottoslivellamento speculare in III, aVF"); l.add("Infarto acuto laterale") }
                // come molti algoritmi veri: il posteriore isolato e il de Winter NON sono segnalati come STEMI
                "stemi_posterior" -> { l.add("Sottoslivellamento ST in V1-V3, onda R alta in V1-V2"); l.add("Considerare ischemia anteriore") }
                "de_winter" -> { l.add("Sottoslivellamento ST ascendente in V1-V6 con onde T alte; sopraslivellamento ST in aVR"); l.add("Considerare ischemia anteriore") }
                "nstemi_depression" -> { l.add("Sottoslivellamento ST orizzontale in V3-V6, I, II, aVF"); l.add("Considerare ischemia subendocardica") }
                "left_main" -> { l.add("Sottoslivellamento ST diffuso, sopraslivellamento ST in aVR"); l.add("Considerare ischemia diffusa (tronco comune / multivasale)") }
                "wellens" -> { l.add("Onde T negative profonde in V2-V3"); l.add("Considerare ischemia anteriore") }
                "pericarditis" -> { l.add("Sopraslivellamento ST diffuso, sottoslivellamento del tratto PR"); l.add("Considerare pericardite acuta") }
            }
            if (m.has("hyperkalemia")) l.add("Onde T alte e appuntite: considerare iperkaliemia")
            if (m.has("hyperkalemia_severe")) l.add("QRS molto largo, onde P assenti, T appuntite: considerare iperkaliemia grave")
            if (m.has("hypokalemia")) l.add("Onde T appiattite, onde U evidenti: considerare ipokaliemia")
            if (m.has("long_qt") && meas != null) l.add("QT lungo (QTc ${meas.qtcMs} ms)")
            if (m.has("brugada")) l.add("Sopraslivellamento ST a tenda in V1-V2: pattern di Brugada tipo 1")
            if (m.has("pe")) l.add("Pattern S1Q3T3, onde T negative in V1-V4: considerare sovraccarico ventricolare destro")
            if (m.has("hypothermia")) l.add("Onde J (di Osborn), intervalli allungati: considerare ipotermia")
        }
        val normal = l.size == 1 && l[0] == "Ritmo sinusale"
        l.add(if (normal) "ECG normale" else "ECG anormale")
        l.add("Referto non confermato")
        return SLEcgReading(head, l)
    }
}
