// ANDROID (transizioni ritmo 22/11) — file nuovo, solo Android (sull'iPad non c'è).
// SimulaFranz_Monitor — «preludio» fisiologico quando cambia il ritmo: come INIZIA davvero il ritmo nuovo
// (extrasistoli, R su T, salve di TV, extrasistoli atriali, bradicardia progressiva, blocco AV, FV che si affina, ROSC...).
//
// Il preludio è una SEQUENZA DI BATTITI con il loro TIPO (sinusale, extrasistole ventricolare, complesso di TV, onda P non
// condotta, scappamento...) e alcune azioni sui parametri (rampe di FC/PA): NON contiene forme d'onda. La forma di ogni tipo di
// battito, per ogni derivazione, la decide il motore delle curve (WaveformEngine.beatShape): così ritmo/conduzione e
// morfologia restano separati (una morfologia nuova per derivazione, ST, blocchi di branca, 12 derivazioni, si aggancia lì).
//
// Il piano è deterministico (generatore con seme = numero progressivo del cambio di ritmo dall'ultimo reset): stesse scelte
// a parità di sequenza di comandi, ma varianti diverse da un cambio all'altro (variabilità plausibile).
//
// Fonti (sintesi; dettagli e numeri in TRANSIZIONI_RITMI.md):
//  - Bayés de Luna A et al., Am Heart J 1989;117:151-159 (157 Holter di morte improvvisa): ~84% tachiaritmie ventricolari,
//    la FV di solito preceduta da TV che degenera; torsione di punta ~13%; bradiaritmie ~16% (più spesso depressione sinusale
//    che blocco AV); innesco con extrasistoli ventricolari e aumento della frequenza nei minuti precedenti.
//  - Kempf FC, Josephson ME, Am J Cardiol 1984;53:1577-1582 (Holter, morte improvvisa): TV che accelera e degenera in FV.
//  - Haïssaguerre M et al., N Engl J Med 1998;339:659-666: FA parossistica innescata da extrasistoli atriali, 94% dei foci
//    nelle vene polmonari; spesso salve/brevi tachicardie atriali prima della FA.
//  - Kay GN et al., J Am Coll Cardiol 1983;2:806-817; Viskin S et al., J Am Coll Cardiol 1996: torsione di punta e TV
//    polimorfa innescate dalla sequenza breve-lunga-breve.
//  - Weaver WD et al., Ann Intern Med 1985;102:53-55: ampiezza della FV 0,55 ± 0,25 mV all'arrivo dei soccorsi, FV fine
//    (≤ 0,2 mV) nel 17%, legata al ritardo; la FV non trattata si affina in minuti e finisce in asistolia.
//  - ERC 2021/2025 ALS (Soar J et al., Resuscitation 2021;161:115-151) e AHA 2020/2025: PEA da 4 I e 4 T; PEA ipossica/
//    acidosica/iperkaliemica con bradicardia e QRS che si allarga; PEA da ipovolemia/ostruttiva con tachicardia e pressione
//    che crolla; asistolia spesso preceduta da bradicardia estrema/blocco AV («asistolia con onde P»).
//  - Littmann L et al. / Kosugi: pausa post-conversione della FA (sinus node recovery) 1-3 s; pausa elettrica post-shock /
//    dopo la fine della FV prima dei primi complessi organizzati (ERC: «stordimento» post-ROSC, primi complessi lenti).
package it.simulafranz.core

import java.util.Random

// Tipi di battito del preludio (la forma la decide il motore per derivazione)
const val SLBeatSinus = 0      // QRS stretto con onda P sinusale
const val SLBeatNarrow = 1     // QRS stretto senza P (FA condotta, ritmo giunzionale)
const val SLBeatPAC = 2        // extrasistole atriale: P precoce di forma diversa + QRS stretto
const val SLBeatPVC = 3        // extrasistole ventricolare: QRS largo, T discordante, nessuna P (pol = asse ±)
const val SLBeatVT = 4         // complesso di TV monomorfa (stessa forma del ritmo «vt»)
const val SLBeatPolyVT = 5     // TV polimorfa: ampiezza (amp) e asse (pol) cambiano battito per battito
const val SLBeatEscape = 6     // scappamento idioventricolare: QRS molto largo, lento (widen 0..1 = più largo)
const val SLBeatPOnly = 7      // onda P non condotta (blocco AV): nessun QRS
const val SLBeatWidened = 8    // QRS che si allarga progressivamente (ipossia/acidosi/iperkaliemia): widen 0..1
const val SLBeatPEA = 9        // complesso della PEA (stessa forma del ritmo «pea»)

// Sfondo dell'ECG durante una fase del preludio (oltre ai battiti)
const val SLOnsetBgFrom = 0    // quello del ritmo di partenza (onde f della FA, onde della FV, linea dell'asistolia, deriva)
const val SLOnsetBgBase = 1    // linea di base pulita (pausa elettrica: solo la deriva respiratoria)
const val SLOnsetBgAsys = 2    // linea dell'asistolia (ondulazione lenta irregolare)

/** Un battito del preludio. at = secondi dall'inizio (base) del preludio; rr = intervallo dal battito prima. */
class SLOnsetBeat(val at: Double, val kind: Int, val rr: Double, val perfused: Boolean,
                  val amp: Double = 1.0, val widen: Double = 0.0, val pol: Double = 1.0, val hasP: Boolean = true)

/** Una fase del preludio: da `at` secondi; native = il motore genera i battiti del ritmo di partenza (rampa di FC del cuore). */
class SLOnsetPhase(val at: Double, val name: String, val label: String, val native: Boolean, val bg: Int)

/** Azione del cuore all'inizio di una fase: rampa di un parametro (chiave dell'API) verso `to` in `dur` secondi. */
class SLOnsetAction(val at: Double, val key: String, val to: Int, val dur: Double)

/** Piano completo di un preludio (immutabile: lo condividono cuore, istantanee e motore). */
class SLOnsetPlan(
    val id: Int,
    val from: String,
    val to: String,
    val variant: String,                 // nome della variante (es. "vt_to_vf", "r_on_t", "sinus_arrest")
    val text: String,                    // descrizione per il registro e la regia
    val duration: Double,                // secondi: alla fine il cuore applica il ritmo `to`
    val anchor: Boolean,                 // il motore aggancia l'inizio al prossimo battito del ritmo di partenza
    val phases: List<SLOnsetPhase>,
    val beats: List<SLOnsetBeat>,
    val actions: List<SLOnsetAction>,
    val vfAmpFrom: Double = -1.0,        // FV che si affina (FV -> asistolia): ampiezza picco-picco (mV) da / a; -1 = non usata
    val vfAmpTo: Double = -1.0,
    val vfFreqFrom: Double = 1.0,        // fattore della frequenza dominante della FV da / a
    val vfFreqTo: Double = 1.0,
    val roscHrStart: Int = 0             // ROSC: FC dei primi complessi (poi rampa verso quella del ritmo nuovo); 0 = nessuna
) {
    fun phaseAt(off: Double): SLOnsetPhase {
        var p = phases[0]
        for (x in phases) if (off >= x.at) p = x
        return p
    }
    fun phaseIndexAt(off: Double): Int {
        var k = 0
        for (i in phases.indices) if (off >= phases[i].at) k = i
        return k
    }
    /** ampiezza della FV (mV) a `off` secondi (solo FV -> asistolia): calo esponenziale */
    fun vfAmpAt(off: Double): Double {
        if (vfAmpFrom < 0) return -1.0
        val x = Math.max(0.0, Math.min(1.0, off / duration))
        return vfAmpFrom * Math.pow(vfAmpTo / vfAmpFrom, x)
    }
    fun vfFreqAt(off: Double): Double {
        val x = Math.max(0.0, Math.min(1.0, off / duration))
        return vfFreqFrom + (vfFreqTo - vfFreqFrom) * x
    }
}

internal object SFRhythmOnset {

    private val sinusFamily = listOf(SLRhythmSinus, SLRhythmSinusTachycardia, SLRhythmSinusBradycardia)
    fun isSinusFamily(r: String): Boolean = sinusFamily.contains(r)
    private fun isVT(r: String): Boolean = r == SLRhythmVentricularTachy || r == SLRhythmVentricularTachyPulse

    private fun nativeKind(from: String): Int = when {
        from == SLRhythmAtrialFibrillation -> SLBeatNarrow
        isVT(from) -> SLBeatVT
        from == SLRhythmPEA -> SLBeatPEA
        else -> SLBeatSinus
    }

    // costruttore di sequenze: tiene il tempo e l'intervallo dal battito prima
    private class Seq(val perfused: Boolean) {
        val beats = ArrayList<SLOnsetBeat>(); var t = 0.0; var last = -1.0
        fun add(at: Double, kind: Int, perf: Boolean = perfused, amp: Double = 1.0, widen: Double = 0.0, pol: Double = 1.0, hasP: Boolean = true) {
            val rr = if (last < 0) 1.0 else Math.max(0.15, at - last)
            beats.add(SLOnsetBeat(at, kind, rr, perf, amp, widen, pol, hasP)); last = at; t = at
        }
        // onda P non condotta: non conta come battito (rr resta quello del QRS)
        fun p(at: Double) { beats.add(SLOnsetBeat(at, SLBeatPOnly, 1.0, false)) }
    }

    private fun r(rnd: Random, lo: Double, hi: Double): Double = lo + (hi - lo) * rnd.nextDouble()

    /**
     * Piano del preludio da `from` a `to`, oppure null se il ritmo cambia di colpo (nessuna insorgenza tipica, o stesso ritmo).
     * hr/sys/dia = valori di adesso; vfAmp = ampiezza della FV adesso (per FV -> asistolia); auto = fisiologia automatica.
     */
    // ANDROID (12 derivazioni 22/11): avBlock/atrial = blocco AV impostato (morfologia ECG) e frequenza atriale: con un BAV avanzato
    // l'asistolia arriva per blocco (P che continuano), con il BAV 3° per cedimento dello scappamento
    fun build(id: Int, from: String, to: String, hr: Int, sys: Int, dia: Int, vfAmp: Double, auto: Boolean,
              avBlock: String = "none", atrial: Int = 0): SLOnsetPlan? {
        if (from == to) return null
        // seme mescolato (splitmix64): numeri progressivi vicini danno varianti davvero diverse
        var z = id * -0x61c8864680b583ebL + from.hashCode() * 0x632BE59BD9B4E019L + to.hashCode()
        z = (z xor (z ushr 30)) * -0x40a7b892e31b1a47L; z = (z xor (z ushr 27)) * -0x6b2fb644ecceee15L; z = z xor (z ushr 31)
        val rnd = Random(z)
        val pulseFrom = SimulationState.rhythmHasPulse(from)
        val pulseTo = SimulationState.rhythmHasPulse(to)
        val rr0 = 60.0 / Math.max(35, Math.min(170, if (hr > 0) hr else 80))
        return when {
            // --- FV ---
            to == SLRhythmVentricularFib && isVT(from) -> vtToVf(id, from, rr0, rnd)
            to == SLRhythmVentricularFib && pulseFrom -> pulsedToVf(id, from, rr0, rnd)
            // --- TV (con o senza polso) da un ritmo con polso non ventricolare ---
            isVT(to) && pulseFrom && !isVT(from) -> pulsedToVt(id, from, to, hr, rr0, rnd)
            // --- FA da un ritmo sinusale ---
            to == SLRhythmAtrialFibrillation && isSinusFamily(from) -> sinusToAf(id, from, rr0, rnd)
            // --- asistolia ---
            to == SLRhythmAsystole && from == SLRhythmVentricularFib -> vfToAsystole(id, vfAmp, rnd)
            to == SLRhythmAsystole && isVT(from) -> slowingToAsystole(id, from, rr0, rnd, SLBeatVT)
            to == SLRhythmAsystole && from == SLRhythmPEA -> slowingToAsystole(id, from, rr0, rnd, SLBeatPEA)
            to == SLRhythmAsystole && pulseFrom && avBlock == "third" -> escapeFailure(id, from, hr, sys, dia, atrial, rnd, auto)
            to == SLRhythmAsystole && pulseFrom -> pulsedToAsystole(id, from, hr, sys, dia, rr0, rnd, auto,
                                                                    avBlock in listOf("mobitz2", "2to1", "high_grade"))
            // --- PEA da un ritmo con polso non ventricolare ---
            to == SLRhythmPEA && pulseFrom && !isVT(from) -> pulsedToPea(id, from, hr, sys, dia, rr0, rnd, auto)
            // --- ROSC: da un ritmo senza polso a sinusale / FA ---
            (isSinusFamily(to) || to == SLRhythmAtrialFibrillation) && !pulseFrom -> rosc(id, from, to, hr, rr0, rnd)
            // --- fine di una tachiaritmia con polso: pausa e poi il ritmo nuovo ---
            isSinusFamily(to) && (from == SLRhythmAtrialFibrillation || from == SLRhythmVentricularTachyPulse) -> termination(id, from, to, rr0, rnd)
            else -> null
        }
    }

    // FV da ritmo con polso (sinusale, FA): extrasistoli ventricolari, poi una precoce che cade sulla T (R su T) e innesca
    // una TV che degenera in FV in pochi secondi (la più frequente), oppure la FV subito, oppure una salva polimorfa.
    private fun pulsedToVf(id: Int, from: String, rr0: Double, rnd: Random): SLOnsetPlan {
        val nk = nativeKind(from); val q = Seq(true)
        val x = rnd.nextDouble()
        val variant = if (x < 0.45) "vt_to_vf" else if (x < 0.80) "r_on_t" else "polymorphic"
        q.add(0.0, nk)
        var text = ""
        // extrasistoli che precedono (molto frequenti negli Holter): isolata o coppia, con pausa compensatoria
        val pre = rnd.nextDouble()
        if (pre < 0.75) {
            val lastNat = q.t
            val c = Math.max(0.38, Math.min(0.56, rr0 * 0.6))
            q.add(lastNat + c, SLBeatPVC, pol = 1.0)
            var desc = "extrasistole ventricolare"
            if (pre < 0.25) { q.add(q.t + 0.44, SLBeatPVC, pol = -0.7, amp = 0.9); desc = "coppia di extrasistoli ventricolari" }
            q.add(lastNat + 2 * rr0 + (if (pre < 0.25) 0.3 else 0.0), nk)       // pausa compensatoria
            q.add(q.t + rr0, nk)
            text = "$desc, "
        } else q.add(rr0, nk)
        // innesco: extrasistole con accoppiamento corto, sul tratto discendente dell'onda T (R su T)
        val qt = 0.40 * Math.sqrt(Math.min(Math.max(rr0, 0.25), 2.0))
        q.add(q.t + qt * r(rnd, 0.66, 0.78), SLBeatPVC, perf = false, amp = 1.1)
        val trig = q.t
        val ph = ArrayList<SLOnsetPhase>()
        ph.add(SLOnsetPhase(0.0, "ectopy", "Extrasistoli ventricolari", false, SLOnsetBgFrom))
        ph.add(SLOnsetPhase(trig - 0.05, "r_on_t", "R su T", false, SLOnsetBgFrom))
        var end = trig + 0.20
        when (variant) {
            "vt_to_vf" -> {
                // TV rapida (~200-240/min) che accelera e diventa polimorfa: degenera in FV in 1,5-3 s
                val n = 6 + rnd.nextInt(5)
                var cl = r(rnd, 0.27, 0.30)
                for (k in 0 until n) {
                    q.add(q.t + cl, if (k < n / 2) SLBeatVT else SLBeatPolyVT, perf = false,
                          amp = if (k < n / 2) 1.0 else 0.6 + 0.4 * Math.abs(Math.cos(k * 0.9)), pol = if (k < n / 2) 1.0 else Math.cos(k * 0.9))
                    cl = Math.max(0.20, cl * 0.95)
                }
                ph.add(SLOnsetPhase(trig + 0.1, "vt", "TV che degenera in FV", false, SLOnsetBgFrom))
                end = q.t + cl * 0.6; text += "R su T, TV che degenera in FV"
            }
            "polymorphic" -> {
                // salva polimorfa (asse che ruota, ~250/min) che diventa FV
                val n = 7 + rnd.nextInt(5)
                for (k in 0 until n) { val a = Math.PI * k / 5.0
                    q.add(q.t + r(rnd, 0.22, 0.26), SLBeatPolyVT, perf = false, amp = 0.45 + 0.65 * Math.abs(Math.sin(a + 0.6)), pol = if (Math.cos(a) >= 0) 1.0 else -1.0) }
                ph.add(SLOnsetPhase(trig + 0.1, "polymorphic_vt", "TV polimorfa che diventa FV", false, SLOnsetBgFrom))
                end = q.t + 0.15; text += "R su T, salva di TV polimorfa, FV"
            }
            else -> text += "R su T, FV"
        }
        return SLOnsetPlan(id, from, SLRhythmVentricularFib, variant, text.replaceFirstChar { it.uppercase() }, end, true, ph, q.beats, emptyList())
    }

    // TV che degenera in FV: il ciclo si accorcia e i complessi cambiano forma (TV polimorfa) per 2-4 s
    private fun vtToVf(id: Int, from: String, rr0: Double, rnd: Random): SLOnsetPlan {
        val q = Seq(from == SLRhythmVentricularTachyPulse)
        q.add(0.0, SLBeatVT)
        var cl = Math.min(rr0, 0.36)
        val n = 7 + rnd.nextInt(5)
        for (k in 0 until n) {
            cl = Math.max(0.20, cl * 0.93)
            val poly = k >= n / 2
            q.add(q.t + cl, if (poly) SLBeatPolyVT else SLBeatVT, perf = false,
                  amp = if (poly) 0.55 + 0.45 * Math.abs(Math.cos(k * 1.1)) else 1.0, pol = if (poly) Math.cos(k * 1.1) else 1.0)
        }
        val ph = listOf(SLOnsetPhase(0.0, "vt_accelerating", "TV che accelera", false, SLOnsetBgFrom),
                        SLOnsetPhase(q.beats[n / 2 + 1].at - 0.05, "polymorphic_vt", "TV polimorfa che degenera in FV", false, SLOnsetBgFrom))
        return SLOnsetPlan(id, from, SLRhythmVentricularFib, "vt_to_vf", "TV che accelera e degenera in FV", q.t + cl * 0.6, true, ph, q.beats, emptyList())
    }

    // TV da ritmo con polso: extrasistole della stessa forma, poi la TV parte con un'extrasistole; oppure 1-2 salve di TV
    // non sostenuta (3-5 battiti) prima di quella sostenuta.
    private fun pulsedToVt(id: Int, from: String, to: String, hr: Int, rr0: Double, rnd: Random): SLOnsetPlan {
        val nk = nativeKind(from); val q = Seq(true)
        val hrT = if (hr >= 120) hr else (if (to == SLRhythmVentricularTachy) 180 else 170)
        val cl = 60.0 / hrT
        // in bradicardia le salve allungherebbero troppo il passaggio: solo l'extrasistole che avvia la TV
        val variant = if (rnd.nextDouble() < 0.5 || rr0 > 0.9) "pvc_then_vt" else "nsvt_then_vt"
        q.add(0.0, nk)
        val ph = ArrayList<SLOnsetPhase>()
        ph.add(SLOnsetPhase(0.0, "ectopy", "Extrasistoli ventricolari", false, SLOnsetBgFrom))
        val text: String
        if (variant == "pvc_then_vt") {
            q.add(rr0, nk)
            val lastNat = q.t
            q.add(lastNat + Math.max(0.40, Math.min(0.56, rr0 * 0.6)), SLBeatVT)
            q.add(lastNat + 2 * rr0, nk)
            q.add(q.t + rr0, nk)
            text = "Extrasistole ventricolare, poi la TV parte con un'extrasistole"
        } else {
            q.add(rr0, nk)
            val salvos = 1 + rnd.nextInt(2)
            for (sv in 0 until salvos) {
                val n = if (salvos == 2) 3 else 3 + rnd.nextInt(3)                    // 3-5 battiti (3 se le salve sono due)
                q.add(q.t + Math.max(0.38, rr0 * 0.55), SLBeatVT)
                for (k in 1 until n) q.add(q.t + cl, SLBeatVT)
                q.add(q.t + rr0 * 1.35, nk)                                       // pausa dopo la salva
                if (sv == salvos - 1 || salvos == 1) q.add(q.t + rr0, nk)
            }
            text = if (salvos == 1) "Salva di TV non sostenuta, poi TV sostenuta" else "Due salve di TV non sostenuta, poi TV sostenuta"
        }
        val start = q.t + Math.max(0.38, rr0 * 0.55)
        q.add(start, SLBeatVT)                                                    // l'extrasistole che avvia la TV sostenuta
        ph.add(SLOnsetPhase(start - 0.05, "vt_start", "Inizio della TV", false, SLOnsetBgFrom))
        return SLOnsetPlan(id, from, to, variant, text, start + cl * 0.95, true, ph, q.beats, emptyList())
    }

    // FA parossistica: extrasistoli atriali (vene polmonari), una salva atriale, poi la FA
    private fun sinusToAf(id: Int, from: String, rr0: Double, rnd: Random): SLOnsetPlan {
        val q = Seq(true)
        q.add(0.0, SLBeatSinus)
        if (rr0 < 1.0) q.add(rr0, SLBeatSinus)                                  // in bradicardia un battito in meno
        val x = rnd.nextDouble()
        val variant: String; val text: String
        if (x < 0.5) {
            variant = "pac_then_af"
            q.add(q.t + rr0 * 0.62, SLBeatPAC)
            q.add(q.t + rr0 * 1.08, SLBeatSinus)                                  // pausa non compensatoria
            q.add(q.t + rr0, SLBeatSinus)
            q.add(q.t + rr0 * 0.55, SLBeatPAC)
            text = "Extrasistole atriale, poi un'altra precoce avvia la FA"
        } else {
            variant = "atrial_salvo_then_af"
            q.add(q.t + rr0 * 0.58, SLBeatPAC)
            val n = 2 + rnd.nextInt(2)
            for (k in 0 until n) q.add(q.t + r(rnd, 0.32, 0.40), SLBeatPAC, amp = 0.95)
            text = "Extrasistoli atriali a salve, poi la FA"
        }
        val ph = listOf(SLOnsetPhase(0.0, "ectopy", "Extrasistoli atriali", false, SLOnsetBgFrom),
                        SLOnsetPhase(q.t - 0.05, "af_start", "Inizio della FA", false, SLOnsetBgFrom))
        return SLOnsetPlan(id, from, SLRhythmAtrialFibrillation, variant, text, q.t + 0.40, true, ph, q.beats, emptyList())
    }

    // FV non trattata che si affina fino all'asistolia (nella realtà minuti: qui compresso in 8-10 s su richiesta)
    private fun vfToAsystole(id: Int, vfAmp: Double, rnd: Random): SLOnsetPlan {
        val d = r(rnd, 8.0, 10.0)
        val a0 = Math.max(0.12, if (vfAmp > 0) vfAmp else 1.0)
        val ph = listOf(SLOnsetPhase(0.0, "vf_fine", "La FV si affina", true, SLOnsetBgFrom),
                        SLOnsetPhase(d * 0.6, "vf_very_fine", "FV molto fine", true, SLOnsetBgFrom))
        return SLOnsetPlan(id, SLRhythmVentricularFib, SLRhythmAsystole, "vf_fine_to_asystole", "La FV si affina fino all'asistolia",
                           d, false, ph, emptyList(), emptyList(), vfAmpFrom = a0, vfAmpTo = 0.04, vfFreqFrom = 1.0, vfFreqTo = 0.7)
    }

    // TV o PEA che rallenta, si allarga e perde ampiezza (ritmo agonico) fino all'asistolia
    private fun slowingToAsystole(id: Int, from: String, rr0: Double, rnd: Random, kind: Int): SLOnsetPlan {
        val q = Seq(SimulationState.rhythmHasPulse(from))
        q.add(0.0, kind)
        var rr = Math.max(rr0, 0.33); var k = 0
        while (rr < 1.8 && k < 12) {
            rr *= r(rnd, 1.25, 1.40); k++
            val w = Math.min(1.0, k / 6.0)
            q.add(q.t + rr, if (k < 3) kind else SLBeatEscape, perf = false, amp = Math.max(0.35, 1.0 - 0.08 * k), widen = w)
        }
        val ph = listOf(SLOnsetPhase(0.0, "slowing", if (kind == SLBeatVT) "La TV rallenta e si allarga" else "Complessi sempre più lenti e larghi", false, SLOnsetBgFrom),
                        SLOnsetPhase(q.beats[Math.min(3, q.beats.size - 1)].at - 0.05, "agonal", "Ritmo agonico", false, SLOnsetBgAsys))
        val text = if (kind == SLBeatVT) "La TV rallenta e si allarga (ritmo agonico), poi asistolia" else "Complessi sempre più lenti e larghi (ritmo agonico), poi asistolia"
        return SLOnsetPlan(id, from, SLRhythmAsystole, "agonal", text, q.t + 1.6, true, ph, q.beats, emptyList())
    }

    // Asistolia da ritmo con polso: bradicardia progressiva, poi depressione sinusale (più frequente negli Holter) oppure
    // blocco AV avanzato con scappamenti idioventricolari e infine solo onde P (asistolia ventricolare); dalla FA: risposta
    // ventricolare sempre più lenta. Il blocco AV qui è una versione semplice (P non condotte a frequenza fissa).
    private fun pulsedToAsystole(id: Int, from: String, hr: Int, sys: Int, dia: Int, rr0: Double, rnd: Random, auto: Boolean,
                                 forceAv: Boolean = false): SLOnsetPlan {
        val act = ArrayList<SLOnsetAction>(); val ph = ArrayList<SLOnsetPhase>(); val q = Seq(true)
        val af = from == SLRhythmAtrialFibrillation
        // ANDROID (12 derivazioni 22/11): con un BAV avanzato già presente l'asistolia arriva sempre per blocco
        val variant = if (af) "af_slow_response" else if (forceAv) "av_block" else if (rnd.nextDouble() < 0.6) "sinus_arrest" else "av_block"
        val text: String; val end: Double
        if (variant == "av_block") {
            // P sinusali che continuano; conduzione 2:1, poi 3:1, poi blocco completo con 2-3 scappamenti larghi, poi solo P
            val pp = Math.max(0.6, Math.min(1.0, rr0))
            var t = 0.0; var n = 0
            q.add(0.0, SLBeatSinus)
            for (c in 0 until 2) { t += pp; q.p(t); t += pp; q.add(t, SLBeatSinus); n++ }                   // 2:1
            ph.add(SLOnsetPhase(0.0, "av_block_2to1", "Blocco AV 2:1", false, SLOnsetBgFrom))
            act.add(SLOnsetAction(0.0, "heart_rate_bpm", Math.max(20, SLLround(30.0 / pp)), 2.0))
            val t31 = t
            run { t += pp; q.p(t); t += pp; q.p(t); t += pp; q.add(t, SLBeatSinus) }                         // 3:1
            ph.add(SLOnsetPhase(t31 + 0.1, "av_block_3to1", "Blocco AV avanzato 3:1", false, SLOnsetBgFrom))
            act.add(SLOnsetAction(t31, "heart_rate_bpm", Math.max(20, SLLround(20.0 / pp)), 2.0))
            val tc = t
            ph.add(SLOnsetPhase(tc + 0.1, "complete_block", "Blocco AV completo, scappamento idioventricolare", false, SLOnsetBgFrom))
            act.add(SLOnsetAction(tc, "heart_rate_bpm", 24, 3.0))
            var nextEsc = tc + r(rnd, 2.2, 2.6); val escN = 2 + rnd.nextInt(2); var e = 0
            var tp = t
            val stop = tc + 2.6 * escN + 2.6
            while (tp + pp < stop) {
                tp += pp
                if (e < escN && nextEsc <= tp - 0.15) { q.add(nextEsc, SLBeatEscape, amp = 1.0 - 0.12 * e, widen = 0.3 + 0.3 * e); e++; nextEsc += r(rnd, 2.5, 3.0) }
                q.p(tp)
            }
            ph.add(SLOnsetPhase(q.last + 1.2, "p_wave_asystole", "Solo onde P (asistolia ventricolare)", false, SLOnsetBgAsys))
            end = tp + pp * 0.9
            text = "Blocco AV avanzato, scappamento idioventricolare, solo onde P, asistolia"
        } else {
            // 1) bradicardia progressiva (battiti del ritmo di partenza, FC in rampa)
            val t1 = r(rnd, 6.0, 8.0)
            ph.add(SLOnsetPhase(0.0, "bradycardia", if (af) "Risposta ventricolare sempre più lenta" else "Bradicardia progressiva", true, SLOnsetBgFrom))
            act.add(SLOnsetAction(0.0, "heart_rate_bpm", Math.min(hr, 38), t1))
            // 2) pause sempre più lunghe; 1 scappamento; poi nulla
            ph.add(SLOnsetPhase(t1, "pauses", if (af) "Pause lunghe, scappamento" else "Pause sinusali, scappamento", false, if (af) SLOnsetBgFrom else SLOnsetBgFrom))
            act.add(SLOnsetAction(t1, "heart_rate_bpm", 20, 5.0))
            var t = t1 + 1.6
            val nk = if (af) SLBeatNarrow else SLBeatSinus
            for (g in listOf(1.9, 2.5)) { q.add(t, nk); t += g * r(rnd, 0.95, 1.08) }
            if (rnd.nextDouble() < 0.6) { q.add(t, SLBeatNarrow, hasP = false); t += 3.0 }            // scappamento giunzionale
            q.add(t, SLBeatEscape, amp = 0.8, widen = 0.6)                                            // ultimo complesso largo
            ph.add(SLOnsetPhase(t + 1.0, "standstill", "Nessun complesso", false, if (af) SLOnsetBgFrom else SLOnsetBgAsys))
            end = t + 2.6
            text = if (af) "Risposta ventricolare sempre più lenta, pause, scappamento, asistolia" else "Bradicardia sinusale progressiva, pause sinusali, scappamento, asistolia"
        }
        if (auto && sys > 0) {        // la pressione cala con la frequenza
            act.add(SLOnsetAction(0.0, "systolic_mmhg", Math.min(sys, 70), end * 0.6))
            act.add(SLOnsetAction(0.0, "diastolic_mmhg", Math.min(dia, 40), end * 0.6))
            act.add(SLOnsetAction(end * 0.6, "systolic_mmhg", Math.min(sys, 45), end * 0.4))
            act.add(SLOnsetAction(end * 0.6, "diastolic_mmhg", Math.min(dia, 25), end * 0.4))
        }
        return SLOnsetPlan(id, from, SLRhythmAsystole, variant, text, end, variant == "av_block", ph, q.beats.sortedBy { it.at }, act)
    }

    // ANDROID (12 derivazioni 22/11): BAV 3° -> asistolia: le onde P continuano alla frequenza atriale, lo scappamento rallenta,
    // si allarga e cede (2-3 complessi sempre più lenti), poi solo onde P (asistolia ventricolare, «P-wave asystole»).
    // Fonti: ERC 2021 ALS (asistolia con onde P: indicazione al pacing); ESC 2021 pacing (BAV 3° con scappamento instabile).
    private fun escapeFailure(id: Int, from: String, hr: Int, sys: Int, dia: Int, atrial: Int, rnd: Random, auto: Boolean): SLOnsetPlan {
        val act = ArrayList<SLOnsetAction>(); val ph = ArrayList<SLOnsetPhase>(); val q = Seq(true)
        val pp = 60.0 / Math.max(50, Math.min(150, if (atrial > 0) atrial else 80))
        var rrE = 60.0 / Math.max(20, Math.min(60, if (hr > 0) hr else 35))
        ph.add(SLOnsetPhase(0.0, "escape_slowing", "Lo scappamento rallenta e si allarga", false, SLOnsetBgFrom))
        act.add(SLOnsetAction(0.0, "heart_rate_bpm", Math.max(12, SLLround(60.0 / (rrE * 1.8))), 6.0))
        var tv = 0.0; val esc = ArrayList<Double>()
        esc.add(tv)
        for (k in 1..3) { rrE *= r(rnd, 1.25, 1.45); tv += rrE; esc.add(tv) }
        val lastEsc = tv
        ph.add(SLOnsetPhase(lastEsc + 1.0, "p_wave_asystole", "Solo onde P (asistolia ventricolare)", false, SLOnsetBgAsys))
        val end = lastEsc + 3.2
        // onde P e scappamenti in ordine di tempo (le P cadono dove capita: dissociazione AV)
        var tp = r(rnd, 0.1, pp); var e = 0
        while (tp < end || e < esc.size) {
            if (e < esc.size && (esc[e] <= tp || tp >= end)) {
                q.add(esc[e], SLBeatEscape, amp = Math.max(0.55, 1.0 - 0.12 * e), widen = Math.min(1.0, 0.3 + 0.25 * e), hasP = false); e++
            } else { q.p(tp); tp += pp }
        }
        if (auto && sys > 0) {
            act.add(SLOnsetAction(0.0, "systolic_mmhg", Math.min(sys, 60), end * 0.7))
            act.add(SLOnsetAction(0.0, "diastolic_mmhg", Math.min(dia, 35), end * 0.7))
        }
        return SLOnsetPlan(id, from, SLRhythmAsystole, "escape_failure", "Blocco AV completo: lo scappamento rallenta e cede, solo onde P, asistolia",
                           end, true, ph, q.beats.sortedBy { it.at }, act)
    }

    // PEA da ritmo con polso. Tachicardico (FC >= 100 o tachicardia sinusale): ipovolemia / causa ostruttiva, la FC sale,
    // la pressione e la differenziale crollano, l'onda pletismografica si rimpicciolisce, poi il polso sparisce con lo stesso
    // ritmo. Altrimenti: ipossia / acidosi / iperkaliemia, bradicardia con QRS che si allarga e P che scompare.
    private fun pulsedToPea(id: Int, from: String, hr: Int, sys: Int, dia: Int, rr0: Double, rnd: Random, auto: Boolean): SLOnsetPlan {
        val act = ArrayList<SLOnsetAction>()
        val tachy = hr >= 100 || from == SLRhythmSinusTachycardia
        if (tachy) {
            val d = r(rnd, 10.0, 14.0)
            act.add(SLOnsetAction(0.0, "heart_rate_bpm", Math.min(150, Math.max(hr + 12, 115)), d))
            if (auto && sys > 0) { act.add(SLOnsetAction(0.0, "systolic_mmhg", Math.min(sys, 58), d)); act.add(SLOnsetAction(0.0, "diastolic_mmhg", Math.min(dia, 44), d)) }
            val ph = listOf(SLOnsetPhase(0.0, "shock", "Tachicardia e pressione che crolla", true, SLOnsetBgFrom),
                            SLOnsetPhase(d * 0.65, "weak_pulse", "Polso sempre più debole", true, SLOnsetBgFrom))
            return SLOnsetPlan(id, from, SLRhythmPEA, "hypovolemic_obstructive",
                               "Tachicardia con pressione che crolla (ipovolemia / causa ostruttiva), poi PEA", d, false, ph, emptyList(), act)
        }
        val d = r(rnd, 12.0, 15.0)
        val q = Seq(true); val af = from == SLRhythmAtrialFibrillation
        val rrEnd = 60.0 / 38
        var t = 0.0
        q.add(0.0, SLBeatWidened, hasP = !af)
        while (true) {
            val x = Math.min(1.0, t / d)
            val rr = rr0 + (rrEnd - rr0) * x
            if (t + rr > d - 0.3) break
            t += rr
            val w = Math.min(1.0, t / d)
            q.add(t, SLBeatWidened, perf = w < 0.9, amp = 1.0 - 0.3 * w, widen = w, hasP = !af && w < 0.55)
        }
        act.add(SLOnsetAction(0.0, "heart_rate_bpm", Math.min(hr, 38), d))
        if (auto && sys > 0) { act.add(SLOnsetAction(0.0, "systolic_mmhg", Math.min(sys, 60), d)); act.add(SLOnsetAction(0.0, "diastolic_mmhg", Math.min(dia, 38), d)) }
        val ph = listOf(SLOnsetPhase(0.0, "brady_widening", "Bradicardia e QRS che si allarga", false, SLOnsetBgFrom),
                        SLOnsetPhase(d * 0.6, "weak_pulse", "Polso sempre più debole", false, SLOnsetBgFrom))
        return SLOnsetPlan(id, from, SLRhythmPEA, "hypoxic_metabolic",
                           "Bradicardia con QRS che si allarga (ipossia / acidosi / iperkaliemia), poi PEA", t + rrEnd, true, ph, q.beats, act)
    }

    // ROSC: dopo la FV / TV pausa elettrica di 1,5-3 s, poi primi complessi lenti che accelerano; dall'asistolia prima
    // compaiono le onde P; dalla PEA i complessi si restringono e il polso torna.
    private fun rosc(id: Int, from: String, to: String, hr: Int, rr0: Double, rnd: Random): SLOnsetPlan {
        val q = Seq(false); val ph = ArrayList<SLOnsetPhase>()
        val text: String; val end: Double; var start = 0
        when (from) {
            SLRhythmPEA -> {
                q.add(0.0, SLBeatPEA)
                q.add(rr0, SLBeatWidened, widen = 0.7, hasP = false)
                q.add(q.t + rr0 * 0.95, SLBeatWidened, widen = 0.35)
                ph.add(SLOnsetPhase(0.0, "narrowing", "I complessi si restringono", false, SLOnsetBgFrom))
                end = q.t + rr0 * 0.9; text = "I complessi della PEA si restringono e il polso torna"; start = Math.max(40, Math.min(hr, 70))
            }
            SLRhythmAsystole -> {
                ph.add(SLOnsetPhase(0.0, "p_waves", "Compaiono le onde P", false, SLOnsetBgAsys))
                q.p(0.5); q.p(1.5)
                q.add(2.5, SLBeatNarrow, hasP = false)
                q.add(q.t + 1.3, SLBeatSinus)
                ph.add(SLOnsetPhase(2.4, "first_beats", "Primi complessi lenti", false, SLOnsetBgBase))
                end = q.t + 1.2; text = "Compaiono onde P, poi primi complessi lenti che accelerano"; start = 48
            }
            else -> {   // FV, TV senza polso
                val pause = r(rnd, 1.5, 3.0)
                ph.add(SLOnsetPhase(0.0, "electrical_pause", "Pausa elettrica", false, SLOnsetBgBase))
                val esc = rnd.nextDouble() < 0.3
                q.add(pause, if (esc) SLBeatEscape else SLBeatNarrow, amp = 0.9, widen = 0.4, hasP = false)
                q.add(q.t + 1.35, SLBeatNarrow, hasP = false)
                q.add(q.t + 1.2, SLBeatSinus)
                ph.add(SLOnsetPhase(pause - 0.05, "first_beats", "Primi complessi lenti", false, SLOnsetBgBase))
                end = q.t + 1.15; text = "Pausa elettrica, poi primi complessi lenti che accelerano"; start = 50
            }
        }
        return SLOnsetPlan(id, from, to, "rosc", text, end, from == SLRhythmPEA || isVT(from), ph, q.beats, emptyList(),
                           roscHrStart = if (to == SLRhythmAtrialFibrillation) 0 else start)
    }

    // Fine di una FA o di una TV con polso: l'ultimo complesso, una pausa (recupero del nodo del seno), poi il ritmo nuovo
    private fun termination(id: Int, from: String, to: String, rr0: Double, rnd: Random): SLOnsetPlan {
        val q = Seq(true)
        q.add(0.0, nativeKind(from))
        val pause = if (from == SLRhythmAtrialFibrillation) r(rnd, 1.3, 2.4) else r(rnd, 1.0, 1.6)
        val ph = listOf(SLOnsetPhase(0.1, "post_conversion_pause", "Pausa dopo la conversione", false, SLOnsetBgBase))
        val text = if (from == SLRhythmAtrialFibrillation) "La FA si interrompe: pausa di recupero del seno, poi ritmo sinusale" else "La TV si interrompe: breve pausa, poi ritmo sinusale"
        return SLOnsetPlan(id, from, to, "termination", text, pause, true, ph, q.beats, emptyList())
    }
}

// MARK: ANDROID (ampiezza FV 22/11) — ampiezza della fibrillazione ventricolare
//
// Ampiezza = picco-picco TIPICO dell'ECG in FV (mV; 1 mV = 10 mm alla scala di serie). Nella realtà (Weaver WD et al., Ann
// Intern Med 1985;102:53-55: 0,55 ± 0,25 mV all'arrivo dei soccorsi, «fine» ≤ 0,2 mV nel 17%, legata al ritardo; Callaway
// CW, Menegazzi JJ et al.: ampiezza e frequenza dominante calano nei minuti senza trattamento, la RCP le mantiene o le
// migliora; Strohmenger HU / Achleitner U: l'adrenalina rende la FV più ampia e più rapida per qualche minuto):
//  - appena insorta la FV è grossolana (~1 mV) e più rapida;
//  - senza RCP né scarica si affina in minuti (qui costante ~8 min: ~0,55 mV a 5 min, fine < 0,2 mV dopo ~13 min) e la
//    frequenza dominante cala; con RCP buona si mantiene o migliora un po'; con RCP scadente cala più lentamente;
//  - adrenalina: più grossolana per qualche minuto.
// DAE: sotto 0,1 mV picco-picco la FV non è riconosciuta (trattata come asistolia: «scarica non consigliata»), come i
// criteri AHA (Kerber RE et al., Circulation 1997;95:1677-1682: asistolia < 100 µV; FV fine 100-200 µV ritmo
const val SLVfCoarseMv = 1.0           // preset "coarse" e ampiezza all'insorgenza
const val SLVfFineMv = 0.15            // preset "fine": ancora riconosciuta dal DAE, ma può sembrare asistolia
const val SLVfMinMv = 0.05; const val SLVfMaxMv = 1.5
const val SLAedVfMinMv = 0.10          // soglia del DAE
fun SLVfAmplitudeClass(mv: Double): String = if (mv >= 0.5) "coarse" else if (mv >= 0.2) "medium" else "fine"
