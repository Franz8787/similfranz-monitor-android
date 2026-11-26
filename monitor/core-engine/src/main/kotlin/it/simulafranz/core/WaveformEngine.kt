// Portato da src/WaveformEngine.m (monitor iPad)
// SimulaFranz_Monitor — generatore di forme d'onda SIMULATE.
// Kotlin puro: nessuna dipendenza Android, testabile sul Mac.
//
// Modello temporale continuo: battiti e atti respiratori vengono programmati
// man mano che il tempo avanza; le curve sono funzioni del tempo calcolate
// sommando i contributi dei battiti vicini (niente immagini/GIF).
package it.simulafranz.core

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

typealias SLLead = Int
const val SLLeadII = 0
const val SLLeadV = 1
const val SLLeadPads = 2   // Pads = ECG dalle piastre

// Marcatori disegnati sopra i tracciati ECG
typealias SLMarker = Int
const val SLMarkerNone = 0
const val SLMarkerSync = 1
const val SLMarkerPace = 2

class WaveformEngine(seed: Int = 12345) {

    private class SLBeat(
        val t: Double,        // istante del picco R
        val rr: Double,       // intervallo rispetto al battito precedente (s)
        val morph: Int,
        val hasP: Boolean,
        val perfused: Boolean, // genera polso periferico (pleth/ART)
        // ANDROID (transizioni ritmo 22/11): battiti del preludio: ampiezza, allargamento 0..1, asse (±), complesso QRS sì/no
        val amp: Double = 1.0,
        val widen: Double = 0.0,
        val pol: Double = 1.0,
        // ANDROID (12 derivazioni 22/11): PR del battito (blocchi AV; -1 = quello della morfologia) e numero progressivo (alternanza)
        val pr: Double = -1.0,
        var seq: Int = 0
    ) {
        val qrs: Boolean get() = morph != SLMorphPOnly
        // ANDROID (sincronia polso 22/11): sistole del battito, fissata quando il battito entra nella coda (addBeat) e poi
        // condivisa da ECG, ART, pleth e bip: sv = gittata relativa 0..1 (0 = nessuna sistole efficace: deficit di polso),
        // pep = periodo di pre-eiezione dal picco R (s), mrr = RR medio del momento (forma dell'onda)
        var sv = 0.0; var pep = 0.09; var mrr = 0.8
    }

    // Battito efficace "corrente": parametri in uscita di pulseAt (tau dal piede dell'onda, intervallo fino al piede successivo)
    private class SLPulse(val tau: Double, val rrNext: Double, val amp: Double, val mrr: Double, val onset: Double)

    private var rhythm: String = SLRhythmSinus
    private var hr = 80.0; private var rr = 16.0; private var etco2 = 35.0; private var sys = 120.0; private var dia = 80.0
    private var pulse = true; private var signalValid = true
    private var peaNarrow = false                                 // ANDROID (EF-03)
    private var weakSig = false                                   // ANDROID (perfusione 22/11): segnale SpO2 troppo debole
    private var perfusion = 0.0               // 0.1..1: ampiezza pletismografica in funzione della PA
    private var cpr = false                   // compressioni toraciche in corso
    private var motion = 0                    // movimenti del paziente: 0 no, 1 lievi, 2 marcati
    private var touch = false                 // operatore che tocca il paziente
    private var cprStart = 0.0
    private var ecgOn = true; private var spo2On = true; private var etco2On = true; private var artOn = true
    private var padsOn = false; private var artOccl = false; private var spo2Occl = false
    // pacing transcutaneo e shock
    private var paceActive = false; private var paceCapture = false; private var paceMech = false
    private var paceRate = 0.0; private var paceOut = 0.0
    private var paceFalse = false              // ANDROID (funzioni 21/11): falsa cattura impostata (spike sopra soglia, nessuna cattura)
    private var nextPace = 0.0; private var lastBeatT = 0.0
    private val paces = DoubleArray(16); private var paceCount = 0; private var paceHead = 0
    private var shockAt = 0.0
    private var searching = false             // SpO2 in ricerca (sonda appena messa)

    private val beats = arrayOfNulls<SLBeat>(SL_MAX_BEATS)
    private var beatCount = 0; private var beatHead = 0
    private var nextBeat = 0.0
    private var started = false

    private var breathStart = 0.0; private var breathPeriod = 0.0
    private var breathStarted = false
    private var ventManual = false; private var breathTimes: List<Double> = emptyList()
    // ANDROID (EtCO2 RCP 22/11): picco di ogni insufflazione, oscillazioni da compressione, frequenza delle compressioni
    private var breathCo2: List<Double> = emptyList()
    private var co2Osc = 0.0
    private var cprPeriod = 60.0 / 110.0; private var cprPeriodNew = 60.0 / 110.0
    // ANDROID (ART RCP 22/11): pressione arteriosa in arresto (vedi SimulationState, «pressione arteriosa cruenta in arresto»)
    private var artBase = 0.0; private var artBaseTarget = 0.0; private var artBaseTau = 1.0; private var artBaseAt = 0.0
    private var artAmp = 0.0; private var artFrozen = false
    // ANDROID (sincronia polso 22/11): pressione dell'arresto (ultimo valore senza polso) e ritorno del polso
    private var artArrestV = -1.0; private var roscP0 = -1.0; private var roscAt = 0.0; private var roscOn = -1.0
    // ANDROID (sincronia polso 22/11, EF-19): PA e perfusione usate dalle CURVE, che seguono i valori dell'istantanea in modo
    // continuo (costante ~0,8 s): le rampe del cuore cambiano sist/diast a gradini interi ogni 0,5 s e la linea di base
    // dell'ART (= diastolica) faceva un gradino verticale a ogni cambio (preludi verso l'asistolia, EF-19). Raccordo all'arresto:
    // quando il polso sparisce la curva parte dal valore in cui era e raggiunge la pressione dell'arresto in ~1 s.
    private var sysC = -1.0; private var diaC = -1.0; private var perfC = -1.0; private var bpT = 0.0
    private var artLastV = -1.0; private var artLastT = 0.0; private var artBlendD = 0.0; private var artBlendAt = 0.0
    private fun smoothBp(t: Double) {
        if (sysC < 0 || t - bpT > 5.0 || t < bpT - 5.0) { sysC = sys; diaC = dia; perfC = perfusion; bpT = t; return }
        val dt = t - bpT; if (dt <= 0) return
        val a = 1.0 - exp(-dt / 0.8)
        sysC += (sys - sysC) * a; diaC += (dia - diaC) * a; perfC += (perfusion - perfC) * a; bpT = t
    }
    // ANDROID (audit 26/11, BUG-B2): perdita del polso sul pleth. Prima la curva cadeva a 0 in un pixel e l'onda dei QRS già a
    // schermo spariva; ora l'ultima sistole efficace arriva comunque al dito: si finisce l'onda in corso (e quella dei QRS già
    // disegnati prima della perdita) e si raccorda a zero dal valore appena disegnato, come l'ART (arrestOut).
    private var plLossAt = -1.0                 // istante (curve) della perdita del polso; -1 = nessuna
    private var plLastV = -1.0; private var plLastT = 0.0; private var plBlendD = 0.0; private var plBlendAt = -1.0
    private fun plethTailAt(t: Double): Double {
        var v = 0.0
        if (plLossAt > 0) {
            var found = -1; var on = 0.0
            for (i in 0 until beatCount) {
                val b = beatAt(i)
                if (b.t >= plLossAt) break
                if (!b.qrs || b.sv <= 0.0) continue
                val o = b.t + b.pep + PLETH_TRANSIT
                if (o <= t) { found = i; on = o } else break
            }
            if (found >= 0) {
                val b = beatAt(found)
                var next = -1.0
                for (i in found + 1 until beatCount) { val x = beatAt(i); if (x.t >= plLossAt) break; if (x.qrs && x.sv > 0.0) { next = x.t + x.pep + PLETH_TRANSIT; break } }
                val rrw = if (next > 0) max(0.2, next - on) else max(0.4, b.mrr)      // l'ultima onda finisce in un RR
                v = b.sv * fitPulse(::plethPulse, PLETH_UP, t - on, rrw, b.mrr) * perfC * (if (weakSig) 0.35 else 1.0) * 0.8
            }
        }
        if (plLastV >= 0) { if (t - plLastT < 0.5) { plBlendD = plLastV - v; plBlendAt = t }; plLastV = -1.0 }   // solo se appena disegnata
        val x = t - plBlendAt
        if (plBlendAt >= 0 && plBlendD != 0.0 && x >= 0 && x < 4.0) v += plBlendD * exp(-x / 0.35)
        return max(0.0, v)
    }

    private fun arrestOut(t: Double, v: Double): Double {
        if (artLastV >= 0) { if (t - artLastT < 0.5) { artBlendD = artLastV - v; artBlendAt = t }; artLastV = -1.0 }   // solo se la curva era appena disegnata
        artArrestV = v
        val x = t - artBlendAt
        return if (artBlendD != 0.0 && x < 4.0) v + artBlendD * exp(-x / 0.35) else v
    }

    private var seed: Int = if (seed != 0) seed else 1

    // ANDROID (transizioni ritmo 22/11): preludio del cambio di ritmo (piano del cuore, SFRhythmOnset.kt). Il motore disegna
    // i battiti del piano dall'istante `planBase` (agganciato al prossimo battito del ritmo di partenza se il piano lo chiede);
    // nelle fasi senza battiti propri del ritmo di partenza (native = false) li sopprime; dopo la fine disegna il ritmo nuovo.
    private var plan: SLOnsetPlan? = null
    private var planBase = 0.0; private var planDelta = 0.0; private var planIdx = 0; private var planCoreDone = false
    private var lastT = 0.0                     // istante più avanti già chiesto (i battiti dopo lastT + 0,2 s non sono ancora a schermo)
    // ANDROID (ampiezza FV 22/11): ampiezza (mV picco-picco tipico) e frequenza della FV; la frequenza cambia con un tempo
    // «deformato» continuo (niente salti di fase quando cambia)
    private var vfAmp = 1.0
    private var vfK = 1.0; private var vfKOld = 1.0; private var vfTc = 0.0; private var vfTau0 = 0.0
    private fun vfWarp(t: Double): Double = vfTau0 + (t - vfTc) * (if (t >= vfTc) vfK else vfKOld)

    // ANDROID (12 derivazioni 22/11): morfologia dell'ECG (modello per derivazione, SFEcg12.kt) e blocchi AV come disturbo di
    // CONDUZIONE: con un blocco attivo i battiti si programmano dagli ATRI (onde P alla frequenza atriale, PR del battito,
    // battiti non condotti, dissociazione AV con lo scappamento del BAV 3°). Storico dei battiti degli ultimi ~12 s per la carta
    // a 12 derivazioni (stessi battiti del tracciato).
    private var morphNow = SLEcgMorph.NORMAL
    private var model = SFEcg12.model(SLEcgMorph.NORMAL)
    private var avMode = "none"; private var atrial = 80.0; private var escJunc = false
    private var avNextP = -1.0; private var avIdx = 0; private var avN = 4; private var avSeq = 0; private var avNextV = -1.0
    private var lastQrsT = 0.0; private var beatSeq = 0
    private var paceCaptureNow = false; private var avActiveNow = false
    private val hist = arrayOfNulls<SLBeat>(SL_HIST); private var histCount = 0; private var histHead = 0
    private val paceHist = DoubleArray(64); private var paceHistCount = 0; private var paceHistHead = 0
    private fun histAt(i: Int): SLBeat = hist[(histHead + i) % SL_HIST]!!
    // cambi di ritmo recenti (istante, ritmo): la carta 12D ridisegna gli ultimi 10 s con il ritmo di allora
    private val rhT = DoubleArray(8); private val rhR = arrayOfNulls<String>(8); private var rhN = 0; private var rhHead = 0
    private var capBeats: List<SLBeat>? = null       // durante la cattura 12D: battiti dello storico al posto dell'anello

    // stesso generatore dell'iPad (aritmetica a 32 bit senza segno: in Kotlin l'Int va in overflow allo stesso modo)
    private fun rand01(): Double {
        seed = seed * 1103515245 + 12345
        return ((seed ushr 8) and 0xFFFFFF) / 0x1000000.toDouble()
    }

    // Aggiorna i parametri (ritmo, FC, PA, FR, EtCO2, stato dei sensori...).
    fun updateWithSnapshot(s: SLSnapshot) {
        if (s.rhythm != rhythm) { rhHead = (rhHead + 1) % 8; rhT[rhHead] = lastT; rhR[rhHead] = s.rhythm; if (rhN < 8) rhN++ }   // ANDROID (12 derivazioni 22/11)
        rhythm = s.rhythm
        val hrOld = hr
        hr = s.heartRate.toDouble()
        // ANDROID (audit 26/11, BUG-A7): FC molto bassa poi alzata (es. 10 → 80): il prossimo QRS era programmato con il vecchio RR
        // (fino a 6 s di ECG piatto). Si anticipa alla nuova FC, solo il battito non ancora in coda (nextBeat sta sempre oltre
        // lastT + SL_LOOKAHEAD: niente di già disegnato si sposta, l'onda P ha il suo anticipo). Non durante preludi, blocchi AV e
        // stimolazione: lì i battiti li programma il piano, gli atri o il pacemaker.
        if (started && hr > hrOld && hr >= 1.0 && plan == null && avMode == "none" && !paceCapture) {
            val want = max(lastT + SL_LOOKAHEAD, lastBeatT + 60.0 / hr)
            if (nextBeat > want + 0.05) nextBeat = want
        }
        rr = s.respRate.toDouble()
        etco2 = s.etco2.toDouble()
        sys = s.systolic.toDouble(); dia = s.diastolic.toDouble()
        if (s.cprActive && !cpr) cprStart = -1.0   // il primo campione fissa l'inizio delle compressioni
        cpr = s.cprActive
        ventManual = s.ventilationManual; breathTimes = s.breathTimes
        breathCo2 = s.breathCo2; co2Osc = s.co2CompressionAmp                       // ANDROID (EtCO2 RCP 22/11)
        artBase = s.artCprBase; artBaseTarget = s.artCprBaseTarget; artBaseTau = max(0.1, s.artCprBaseTau)   // ANDROID (ART RCP 22/11)
        artBaseAt = s.artCprBaseAt; artAmp = s.artCprAmp; artFrozen = s.artCprPaused
        cprPeriodNew = 60.0 / max(40.0, min(200.0, s.cprCompressionRate))
        motion = s.patientMotion
        touch = s.operatorTouch
        // ANDROID (perfusione 22/11): dalla PAM (cuore: perfusionFraction, smussata); prima dalla sola sistolica
        perfusion = max(0.08, min(1.0, s.perfusionFraction)); weakSig = s.spo2WeakSignal
        if (pulse && !s.pulsePresent) plLossAt = lastT else if (s.pulsePresent) { plLossAt = -1.0; plBlendAt = -1.0 }   // ANDROID (audit 26/11, BUG-B2)
        pulse = s.pulsePresent
        signalValid = s.spo2SignalValid
        ecgOn = s.ecgConnected; spo2On = s.spo2Connected
        etco2On = s.etco2Connected; artOn = s.artConnected; artOccl = s.artCuffOccluded; spo2Occl = s.spo2CuffOccluded
        padsOn = s.padsConnected
        val d = s.defib
        paceActive = d.paceActive; paceCapture = d.paceCapture; paceMech = d.paceMechanical
        peaNarrow = s.peaNarrow                                   // ANDROID (EF-03)
        paceFalse = d.paceFalseCapture   // ANDROID (funzioni 21/11)
        paceRate = max(30.0, d.paceRate.toDouble()); paceOut = d.paceOutput.toDouble()
        shockAt = d.lastShockAt
        searching = s.spo2Searching
        if (s.spo2LowPerfusionFlag) perfusion = min(perfusion, 0.35)   // bassa perfusione impostata dall'istruttore (precedenza)
        // ANDROID (ampiezza FV 22/11)
        vfAmp = max(0.0, s.vfAmplitudeMv)
        val k = max(0.5, min(1.6, s.vfFrequencyScale))
        if (abs(k - vfK) > 1e-3) { vfTau0 = vfWarp(lastT); vfKOld = vfK; vfK = k; vfTc = lastT }
        // ANDROID (12 derivazioni 22/11)
        if (s.ecgMorph != morphNow) { morphNow = s.ecgMorph; model = SFEcg12.model(morphNow) }
        val am = if (s.avBlockActive) s.ecgMorph.avBlock else "none"
        if (am != avMode) { avMode = am; avNextP = -1.0; avNextV = -1.0; avIdx = 0; avN = avNextN() }
        atrial = s.atrialRate.toDouble(); escJunc = s.ecgMorph.escape == "junctional"
        avActiveNow = s.avBlockActive; paceCaptureNow = d.paceCapture
        // ANDROID (transizioni ritmo 22/11)
        updatePlan(s)
        // ANDROID (funzioni mancanti 22/11): linea arteriosa, PVC, ventilatore (PPV), elettrodi
        lnDamp = s.artDamping; lnOffset = s.artOffset; lnOccl = s.artOccluded; lnZeroUntil = if (s.artZeroing) s.artZeroingUntil else 0.0
        lnFlushAt = s.artFlushAt; cvpOn = s.cvpConnected; cvpMean = s.cvp.toDouble(); cvpWave = s.cvpWave; cvpNoZero = s.cvpNeedsZero
        ventMech = s.ventMode == "ventilator" && !s.ventDisconnected; ventStartE = s.ventStartAt; ppvE = s.ppv
        elOff = s.ecgElectrodeOff; ecgNoiseE = s.ecgNoise; limbRevE = s.limbReversal; afibNow = s.rhythm == SLRhythmAtrialFibrillation
    }

    // MARK: - ANDROID (funzioni mancanti 22/11) — linea arteriosa (IN-03/OP-04), PVC (IN-13), PPV (OP-05), elettrodi (IN-10)
    private var lnDamp = 0; private var lnOffset = 0.0; private var lnOccl = false; private var lnZeroUntil = 0.0; private var lnFlushAt = 0.0
    private var cvpOn = false; private var cvpMean = 6.0; private var cvpWave = "normal"; private var cvpNoZero = false
    private var ventMech = false; private var ventStartE = 0.0; private var ppvE = 0.0
    private var elOff: String? = null; private var ecgNoiseE = 0; private var limbRevE = false; private var afibNow = false
    // filtro del 2° ordine del trasduttore (stato: le query sono a tempi non decrescenti)
    private var fY = 0.0; private var fV = 0.0; private var fT = -1.0; private var fU = 0.0; private var occlMean = -1.0; private var occlT = -1.0

    /** fattore della variazione respiratoria dell'onda di polso: col ventilatore sale all'insufflazione e cala 1,5-3 s dopo (PPV),
     *  in respiro spontaneo la piccola variazione di prima */
    private fun respPulseMod(t: Double, k: Double): Double {
        if (ventMech && rr > 0 && ventStartE > 0) {
            val P = 60.0 / rr; val ti = min(1.4, 0.32 * P)
            var x = ((t - ventStartE) % P + P) % P - (P - ti); if (x < 0) x += P
            fun bump(a: Double, b: Double): Double = if (x < a || x > b) 0.0 else { val y = sin(PI * (x - a) / (b - a)); y * y }
            return 1.0 + k * ppvE * (0.3 * bump(0.0, 1.0) - 0.7 * bump(1.5, 3.0))
        }
        return if (rr > 0 && k == 1.0) 0.97 + 0.03 * sin(2 * PI * rr / 60.0 * t) else 1.0
    }

    // mmHg: pressione arteriosa letta dal trasduttore (zero, lavaggio, smorzamento, livello, occlusione)
    fun artAt(t: Double): Double {
        val raw = artTrueAt(t)
        if (!artOn || artOccl) { fT = -1.0; occlMean = -1.0; return raw }
        if (t < lnZeroUntil) { fT = -1.0; return 0.15 * sin(2 * PI * 0.7 * t) }          // azzeramento: aperto all'aria
        var u = raw
        if (lnOccl) {                                                                      // non pulsatile: la media, piatta
            if (occlMean < 0 || t - occlT > 2 || t < occlT) occlMean = raw
            occlMean += (raw - occlMean) * min(1.0, max(0.0, t - occlT) / 2.5); occlT = t
            u = occlMean + 0.3 * sin(2 * PI * 0.2 * t)
        } else occlMean = -1.0
        val inFlush = lnFlushAt > 0 && t >= lnFlushAt && t < lnFlushAt + 4.0
        if (inFlush && t < lnFlushAt + 1.2) u = 300.0                                      // sacca a 300 mmHg: onda quadra
        u += lnOffset
        if (lnDamp == 0 && !inFlush) { fT = t; fY = u; fV = 0.0; fU = u; return u }
        // ANDROID (audit 26/11, PERF-F10): due when invece di un Pair (un Pair e due Double a ogni sottocampione dell'ART)
        val fn = when (lnDamp) { 1 -> 3.5; 2 -> 11.0; else -> 18.0 }; val z = when (lnDamp) { 1 -> 1.4; 2 -> 0.12; else -> 0.35 }
        if (fT < 0 || t < fT || t - fT > 0.5) { fT = t; fY = u; fV = 0.0; fU = u; return u }
        val w = 2 * PI * fn
        val n = max(1, ((t - fT) / 0.002).toInt())
        val h = (t - fT) / n
        for (i in 1..n) {
            val ui = fU + (u - fU) * i / n
            val a = w * w * (ui - fY) - 2 * z * w * fV
            fV += a * h; fY += fV * h
        }
        fT = t; fU = u
        return fY
    }

    // mmHg: pressione venosa centrale (onde a, c, v; discese x, y), dalla media impostata e dai battiti dell'ECG
    fun cvpAt(t: Double): Double {
        scheduleUntil(t)
        if (!cvpOn) return 0.0
        if (t < lnZeroUntil) return 0.1 * sin(2 * PI * 0.7 * t)
        var v = cvpMean + lnOffset
        // respiro: in spontaneo cala in inspirazione, col ventilatore sale all'insufflazione
        if (ventMech) v += 1.2 * (respPulseMod(t, 1.0 / max(0.05, ppvE)) - 1.0)
        else if (rr > 0) v += 0.9 * sin(2 * PI * rr / 60.0 * t)
        if (!pulse) { if (cpr) v += 6.0 * cprPulse(cprPhaseAt(t)); return v }
        val amp = 1.6 + 0.08 * cvpMean
        val kv = if (cvpWave == "large_v") 3.0 else 1.0
        val ky = if (cvpWave == "tamponade") 0.15 else 1.0
        val kx = if (cvpWave == "tamponade") 1.6 else 1.0
        for (i in 0 until beatCount) {
            val b = beatAt(i)
            if (!b.qrs) continue
            val tau = t - b.t
            if (tau < -0.3 || tau > 1.2) continue
            var w = 0.0
            if (b.hasP && !afibNow) w += 0.9 * gauss(tau, -0.06, 0.045)                 // a: contrazione atriale
            w += 0.45 * gauss(tau, 0.07, 0.03)                                          // c: chiusura della tricuspide
            w -= 0.55 * kx * gauss(tau, 0.20, 0.06)                                     // discesa x
            w += 0.75 * kv * gauss(tau, 0.40, 0.07)                                     // v: riempimento atriale
            w -= 0.55 * ky * gauss(tau, 0.56, 0.07)                                     // discesa y
            v += amp * w
        }
        return v
    }

    // MARK: - ANDROID (transizioni ritmo 22/11) — preludio

    private fun updatePlan(s: SLSnapshot) {
        val p = s.rhythmOnset
        val cur = plan
        if (p != null) {
            if (cur == null || cur.id != p.id) {
                if (cur != null) dropBeatsAfter(lastT + 0.2)              // preludio interrotto e sostituito
                plan = p; planIdx = 0; planCoreDone = false
                var b = s.rhythmOnsetStartAt
                if (p.anchor) {
                    // aggancio al prossimo battito del ritmo di partenza non ancora a schermo: il piano lo sostituisce
                    var cand = -1.0
                    for (i in 0 until beatCount) { val bt = beatAt(i).t; if (bt > lastT + 0.2) { cand = bt; break } }
                    if (cand < 0) cand = max(nextBeat, lastT + 0.2)
                    b = max(b, cand)
                    dropBeatsAfter(b - 0.001); nextBeat = max(nextBeat, b)
                }
                planDelta = b - s.rhythmOnsetStartAt
            }
            planBase = s.rhythmOnsetStartAt + planDelta
        } else if (cur != null && !planCoreDone) {
            // il cuore ha finito: se il ritmo è quello di arrivo si completa il piano (pochi decimi di secondo), altrimenti
            // è stato interrotto (scarica, reset, ritmo diverso): si toglie subito
            if (s.rhythm == cur.to) planCoreDone = true
            else { dropBeatsAfter(lastT + 0.2); plan = null }
        }
    }

    private fun dropBeatsAfter(t: Double) {
        while (beatCount > 0 && beatAt(beatCount - 1).t > t) beatCount--
        while (histCount > 0 && histAt(histCount - 1).t > t) histCount--          // ANDROID (12 derivazioni 22/11)
        lastQrsT = 0.0; for (i in 0 until beatCount) if (beatAt(i).qrs) lastQrsT = beatAt(i).t
        avNextP = -1.0; avNextV = -1.0
        lastBeatT = if (beatCount > 0) beatAt(beatCount - 1).t else 0.0
        if (nextBeat > t + 0.001) nextBeat = max(t, lastBeatT + 0.25)
    }

    private fun planEnd(): Double { val p = plan ?: return 0.0; return planBase + p.duration }
    private fun inPlan(t: Double): Boolean { val p = plan ?: return false; return t >= planBase && t < planBase + p.duration }

    // ritmo da disegnare all'istante t: durante il preludio quello di partenza, dopo la fine quello di arrivo
    private fun rhythmAt(t: Double): String {
        val p = plan ?: return rhythm
        return if (t >= planBase + p.duration) p.to else if (t >= planBase) p.from else rhythm
    }

    // i battiti propri del ritmo di partenza sono soppressi nelle fasi del piano che non li vogliono: primo istante permesso
    private fun nativeAllowedFrom(tb: Double): Double {
        val p = plan ?: return tb
        if (tb < planBase || tb >= planBase + p.duration) return tb
        val off = tb - planBase
        if (p.phaseAt(off).native) return tb
        for (x in p.phases) if (x.at > off && x.native) return planBase + x.at
        return planBase + p.duration
    }

    private fun morphOfKind(k: Int): Int = when (k) {
        SLBeatSinus, SLBeatNarrow -> SLMorphNarrow
        SLBeatPAC -> SLMorphPAC
        SLBeatPVC -> SLMorphPVC
        SLBeatVT -> SLMorphWide
        SLBeatPolyVT -> SLMorphPoly
        SLBeatEscape -> SLMorphEscape
        SLBeatPOnly -> SLMorphPOnly
        SLBeatWidened -> SLMorphWidened
        SLBeatPEA -> SLMorphPEA
        else -> SLMorphNarrow
    }

    // MARK: - Programmazione battiti

    private fun nextIntervalForRhythm(rhythm: String = this.rhythm): Double {
        val base = 60.0 / max(hr, 1.0)
        if (rhythm == SLRhythmAtrialFibrillation) return base * (0.6 + 0.8 * rand01())
        if (rhythm == SLRhythmVentricularTachy) return base * (0.98 + 0.04 * rand01())
        return base * (0.97 + 0.06 * rand01())
    }

    private fun beatAt(i: Int): SLBeat = beats[(beatHead + i) % SL_MAX_BEATS]!!

    private fun addBeat(b: SLBeat) {
        var idx = (beatHead + beatCount) % SL_MAX_BEATS
        if (beatCount == SL_MAX_BEATS) { beatHead = (beatHead + 1) % SL_MAX_BEATS; beatCount--; idx = (beatHead + beatCount) % SL_MAX_BEATS }
        beats[idx] = b
        beatCount++
        lastBeatT = b.t
        if (b.qrs) systoleOf(b)                                  // ANDROID (sincronia polso 22/11)
        // ANDROID (12 derivazioni 22/11): numero progressivo (alternanza elettrica), ultimo QRS, storico per la carta 12D
        if (b.qrs) { beatSeq++; b.seq = beatSeq; lastQrsT = b.t }
        histPush(b)
    }
    // ANDROID (sincronia polso 22/11): sistole meccanica del QRS appena programmato. Il riempimento dipende dall'intervallo dal
    // QRS PRECEDENTE (Frank-Starling): con un RR come quello medio la gittata è piena (l'onda raggiunge la PA impostata), con un
    // battito precoce (extrasistole, FA con RR corto) è piccola e sotto ~35% del RR medio (o sotto ~0,16 s assoluti) la valvola
    // aortica non si apre: QRS senza onda di polso (deficit). Mai oltre il valore impostato. Il periodo di pre-eiezione (PEP,
    // dal picco R all'apertura aortica) si accorcia un po' in tachicardia (~105 ms a 60/min, ~75 ms a 140/min), è più lungo
    // nei battiti ventricolari/stimolati (attivazione asincrona) e in quelli poco riempiti.
    private fun systoleOf(b: SLBeat) {
        val mrr = 60.0 / max(if (paceCapture) paceRate else hr, 1.0)
        val prev = if (lastQrsT > 0 && b.t > lastQrsT) b.t - lastQrsT else mrr
        val rel = prev / mrr
        var sv = if (!b.perfused) 0.0 else min(1.0, max(0.0, (rel - 0.35) / 0.65)) * min(1.0, max(0.0, (prev - 0.16) / 0.08))
        if (sv < 0.12) sv = 0.0
        val ventr = b.morph == SLMorphWide || b.morph == SLMorphPVC || b.morph == SLMorphPoly || b.morph == SLMorphEscape ||
                    b.morph == SLMorphPaced || b.morph == SLMorphPEA
        b.sv = sv; b.mrr = mrr
        b.pep = min(0.14, max(0.07, min(0.12, 0.06 + 0.04 * min(prev, 1.5))) + (if (ventr) 0.02 else 0.0) + 0.02 * (1.0 - sv))
    }
    private fun histPush(b: SLBeat) {
        if (histCount == SL_HIST) { histHead = (histHead + 1) % SL_HIST; histCount-- }
        hist[(histHead + histCount) % SL_HIST] = b; histCount++
    }
    // ANDROID (12 derivazioni 22/11): inserimento in ordine di tempo (onde P del BAV 3° durante la stimolazione con cattura)
    private fun insertBeatSorted(b: SLBeat) {
        if (beatCount == SL_MAX_BEATS) { beatHead = (beatHead + 1) % SL_MAX_BEATS; beatCount-- }
        var i = beatCount
        beats[(beatHead + i) % SL_MAX_BEATS] = b; beatCount++
        while (i > 0 && beatAt(i - 1).t > b.t) { val k0 = (beatHead + i - 1) % SL_MAX_BEATS; val k1 = (beatHead + i) % SL_MAX_BEATS; val x = beats[k0]; beats[k0] = beats[k1]; beats[k1] = x; i-- }
        if (histCount == SL_HIST) { histHead = (histHead + 1) % SL_HIST; histCount-- }
        var j = histCount
        hist[(histHead + j) % SL_HIST] = b; histCount++
        while (j > 0 && histAt(j - 1).t > b.t) { val k0 = (histHead + j - 1) % SL_HIST; val k1 = (histHead + j) % SL_HIST; val x = hist[k0]; hist[k0] = hist[k1]; hist[k1] = x; j-- }
        lastBeatT = max(lastBeatT, b.t)
    }

    // MARK: - ANDROID (12 derivazioni 22/11) — blocchi AV (conduzione)

    // lunghezza del prossimo ciclo (onde P per ciclo; l'ultima non condotta nei Mobitz): sequenze fisse, varie ma ripetibili
    private fun avNextN(): Int {
        avSeq++
        return when (avMode) {
            "mobitz1" -> intArrayOf(4, 3, 5, 4, 4, 3, 5, 4)[avSeq % 8]
            "mobitz2" -> intArrayOf(4, 5, 3, 4, 6, 4, 3, 5)[avSeq % 8]
            "2to1" -> 2
            "high_grade" -> intArrayOf(3, 3, 4, 3, 2, 3)[avSeq % 6]
            else -> 1
        }
    }

    // Prossimo evento della conduzione AV (onda P non condotta, battito condotto con il suo PR, scappamento): false = oltre il
    // limite di programmazione. Le onde P marciano alla frequenza atriale indipendentemente dai QRS.
    private fun avStep(t: Double): Boolean {
        val r = rhythm
        val pp = 60.0 / max(20.0, min(200.0, atrial))
        val atria = r != SLRhythmAtrialFibrillation
        if (avNextP < 0 || avNextP < lastT - 2.0) { avNextP = max(lastT + 0.05, max(lastBeatT + 0.30, nextBeat - 0.20)); avIdx = 0 }
        if (avMode == "third" || r == SLRhythmAsystole) {
            // dissociazione AV: P sempre non condotte; QRS di scappamento alla FC impostata (in asistolia nessuno)
            val esc = avMode == "third" && r != SLRhythmAsystole && hr >= 1.0
            if (esc && (avNextV < 0 || avNextV < lastT - 2.0)) avNextV = max(lastT + 0.3, lastQrsT + 60.0 / max(hr, 1.0))
            val tP = if (atria) avNextP else Double.MAX_VALUE
            val tV = if (esc) avNextV else Double.MAX_VALUE
            if (min(tP + 0.06, tV) > t + SL_LOOKAHEAD + 0.06) return false
            if (tP <= tV) {
                addBeat(SLBeat(t = tP, rr = 1.0, morph = SLMorphPOnly, hasP = false, perfused = false))
                avNextP = tP + pp * (0.985 + 0.03 * rand01())
            } else {
                val tv = max(tV, lastQrsT + 0.30)
                val rrv = tv - lastQrsT
                addBeat(if (escJunc) SLBeat(t = tv, rr = rrv, morph = SLMorphNarrow, hasP = false, perfused = pulse)
                        else SLBeat(t = tv, rr = rrv, morph = SLMorphEscape, hasP = false, perfused = pulse, widen = 0.15))
                avNextV = tv + 60.0 / max(hr, 1.0) * (0.98 + 0.04 * rand01())
            }
        } else {
            var conducts = true; var pr = 0.17
            when (avMode) {
                "first" -> pr = if (model.prFixed > 0) model.prFixed else 0.28
                "mobitz1" -> { conducts = avIdx < avN - 1; pr = 0.20 + 0.20 * (1 - Math.pow(0.5, avIdx.toDouble())) }   // incrementi decrescenti
                "mobitz2" -> conducts = avIdx < avN - 1                                                                    // PR costante
                else -> conducts = avIdx == 0                                                                              // 2:1, 3:1
            }
            if (hr < 1.0) conducts = false
            pr = min(pr, 0.8 * pp)
            if (avNextP - 0.10 - pr > t + SL_LOOKAHEAD) return false
            val tR = avNextP + pr - 0.05 - model.qrsOn * model.qrsK
            if (conducts && tR > lastQrsT + 0.25) {
                addBeat(SLBeat(t = tR, rr = if (lastQrsT > 0) tR - lastQrsT else 60.0 / max(hr, 1.0), morph = SLMorphNarrow, hasP = true, perfused = pulse, pr = pr))
            } else addBeat(SLBeat(t = avNextP, rr = 1.0, morph = SLMorphPOnly, hasP = false, perfused = false))
            avNextP += pp * (if (avMode == "first") 0.97 + 0.06 * rand01() else 0.985 + 0.03 * rand01())
            avIdx++; if (avIdx >= avN) { avIdx = 0; avN = avNextN() }
        }
        nextBeat = max(nextBeat, lastBeatT + 0.25)
        return true
    }

    private fun schedulePacerUntil(t: Double) {
        if (!paceActive) { nextPace = 0.0; return }
        if (nextPace <= 0 || nextPace < t - 1.0) nextPace = t + 0.05
        while (nextPace <= t + SL_LOOKAHEAD) {
            paces[(paceHead + paceCount) % 16] = nextPace
            if (paceCount == 16) paceHead = (paceHead + 1) % 16 else paceCount++
            // ANDROID (12 derivazioni 22/11): storico degli spike per la carta 12D
            paceHist[(paceHistHead + paceHistCount) % 64] = nextPace
            if (paceHistCount == 64) paceHistHead = (paceHistHead + 1) % 64 else paceHistCount++
            val rr = 60.0 / paceRate
            // cattura: complesso largo subito dopo lo spike (non dentro il refrattario del battito precedente)
            // ANDROID (difetti clinici 22/11, EF-02): refrattarietà solo dopo un QRS (lastQrsT): le onde P non condotte del BAV 3°
            // (programmate in anticipo) non rendono refrattario il ventricolo; prima ~30% degli spike non catturavano a caso
            if (paceCapture && nextPace + 0.03 > lastQrsT + 0.25) {
                val b = SLBeat(t = nextPace + 0.03, rr = rr, morph = SLMorphPaced, hasP = false, perfused = paceMech)
                addBeat(b)
            }
            nextPace += rr
        }
    }

    private fun scheduleUntil(t: Double) {
        if (!started) { nextBeat = t; started = true }
        if (t > lastT) lastT = t                 // ANDROID (EF-12-01): anche durante la cattura (carta 12D, preludi, FV)
        schedulePacerUntil(t)
        if (paceCapture) {                       // stimolazione efficace: il ritmo proprio è soppresso (overdrive)
            // ANDROID (12 derivazioni 22/11): nel BAV 3° gli atri continuano: le onde P marciano anche sotto la stimolazione
            if (avMode == "third" && rhythm != SLRhythmAtrialFibrillation) {
                val pp = 60.0 / max(20.0, min(200.0, atrial))
                if (avNextP < 0 || avNextP < t - 2.0) avNextP = t + 0.1
                while (avNextP <= t + SL_LOOKAHEAD) { insertBeatSorted(SLBeat(t = avNextP, rr = 1.0, morph = SLMorphPOnly, hasP = false, perfused = false)); avNextP += pp * (0.985 + 0.03 * rand01()) }
            }
            if (nextBeat < t + SL_LOOKAHEAD + 0.3) nextBeat = t + SL_LOOKAHEAD + 0.3
            return
        }
        if (t > lastT) lastT = t
        val p = plan
        if (p != null && planCoreDone && t > planEnd() + 1.0) plan = null          // ANDROID (transizioni ritmo 22/11)
        if (nextBeat < lastBeatT + 0.25) nextBeat = lastBeatT + 0.25
        while (true) {
            // ANDROID (12 derivazioni 22/11): blocco AV attivo (fuori dai preludi): battiti programmati dagli atri
            if (avMode != "none" && plan == null) { if (avStep(t)) continue else break }
            // ANDROID (transizioni ritmo 22/11): battiti del piano e del ritmo, in ordine di tempo
            val pl = plan
            val tp = if (pl != null && planIdx < pl.beats.size) planBase + pl.beats[planIdx].at else Double.MAX_VALUE
            val na = nativeAllowedFrom(nextBeat); if (na > nextBeat) nextBeat = na
            if (min(tp, nextBeat) > t + SL_LOOKAHEAD) break
            if (pl != null && tp <= nextBeat) {
                val ob = pl.beats[planIdx]; planIdx++
                if (tp < lastBeatT - 1e-6) continue                              // già passato (ripresa dopo una pausa)
                val m = morphOfKind(ob.kind)
                addBeat(SLBeat(t = tp, rr = ob.rr, morph = m, hasP = ob.hasP && ob.kind != SLBeatNarrow, perfused = ob.perfused && pulse,
                               amp = ob.amp, widen = ob.widen, pol = ob.pol))
                if (nextBeat < lastBeatT + 0.25) nextBeat = lastBeatT + 0.25
                continue
            }
            val r = rhythmAt(nextBeat)
            if (!(r == SLRhythmVentricularFib || r == SLRhythmAsystole) && hr >= 1.0) {
                val rr = nextIntervalForRhythm(r)
                val vt = r == SLRhythmVentricularTachy || r == SLRhythmVentricularTachyPulse
                val b = SLBeat(
                    t = nextBeat, rr = rr,
                    // ANDROID (EF-03): PEA stretta (causa meccanica): stesso complesso stretto con P, un po' più piccolo
                    morph = if (vt) SLMorphWide else (if (r == SLRhythmPEA && !peaNarrow) SLMorphPEA else SLMorphNarrow),
                    hasP = !(r == SLRhythmAtrialFibrillation || vt),
                    amp = if (r == SLRhythmPEA && peaNarrow) 0.85 else 1.0,
                    perfused = pulse
                )
                addBeat(b)
                nextBeat += rr
            } else {
                nextBeat += 0.25   // ritmi disorganizzati: nessun QRS
            }
        }
    }

    // MARK: - ECG

    private fun cprPhaseAt(t: Double): Double {
        if (cprStart < 0) cprStart = t
        // ANDROID (EtCO2 RCP 22/11): frequenza delle compressioni secondo la qualità scelta (110 corretta, 80 lenta, 140 veloce),
        // uguale per tutti gli artefatti (ECG, impedenza, pleth, ART, capnogramma); al cambio la fase resta continua
        if (cprPeriodNew != cprPeriod) {
            val ph = (max(0.0, t - cprStart) % cprPeriod) / cprPeriod
            cprStart = t - ph * cprPeriodNew; cprPeriod = cprPeriodNew
        }
        return (max(0.0, t - cprStart) % cprPeriod) / cprPeriod
    }

    // respiro da impedenza toracica (dagli elettrodi ECG): sinusoide alla FR; movimenti, tocco e RCP la disturbano come l'ECG
    fun respImpedanceAt(t: Double, rate: Int): Double {
        val rr = rate
        if (!ecgOn) return 0.0
        // ANDROID (22/11, utente: «la sinusoide sembra artificiale»): forma del respiro spontaneo come sull'impedenza vera:
        // inspirazione attiva più breve (~38 % del ciclo, I:E ≈ 1:1,6) che sale arrotondata, espirazione passiva con discesa
        // esponenziale e pausa di fine espirazione; ampiezza e durata che variano un poco da un atto all'altro (deterministico)
        var v = 0.0
        if (rr > 0) {
            val ph = rr / 60.0 * t; val n = floor(ph); var p = ph - n
            val h = sin(n * 12.9898 + 4.1) * 43758.5453; val r = h - floor(h)          // 0..1 per atto
            val ti = 0.34 + 0.08 * r
            p = min(1.0, p * (0.97 + 0.06 * r))
            val shape = if (p < ti) 0.5 - 0.5 * cos(PI * p / ti)
                        else { val x = (p - ti) / (1 - ti); (exp(-4.2 * x) - exp(-4.2)) / (1 - exp(-4.2)) }
            v = (2 * shape - 1) * (0.9 + 0.2 * r)
        }
        if (cpr) v += 0.9 * cprArtifact(cprPhaseAt(t))                 // compressioni: grandi oscillazioni dell'impedenza
        if (motion != 0) v += 1.1 * motionArtifact(t * 0.7 + 1.9, motion)        // il paziente che si muove altera l'impedenza toracica
        if (touch) v += 0.8 * touchArtifact(t * 0.6 + 0.5)
        return v
    }

    // Le query devono avvenire con tempi non decrescenti (per canale).
    // mV circa, 0 = linea di base
    // ANDROID (IN-10): elettrodo singolo staccato (la derivazione che lo usa è piatta), disturbo (gel secco, 50 Hz, muscoli),
    // inversione dei cavi delle braccia (II diventa III)
    fun ecgAt(t: Double, lead: Int): Double {
        if (lead == SLLeadPads || (elOff == null && ecgNoiseE == 0 && !limbRevE)) return ecgRawAt(t, lead)
        var v = ecgRawAt(t, lead)
        if (!ecgOn) return v
        val off = elOff
        if (off != null && ((lead == SLLeadII && (off == "ra" || off == "ll")) || (lead == SLLeadV && off == "v"))) return 0.0
        if (limbRevE && lead == SLLeadII) v -= rhythmEcgAt(t, 0)
        if (ecgNoiseE > 0) {
            val k = if (ecgNoiseE >= 2) 2.2 else 1.0
            v += k * (0.05 * sin(2 * PI * 50.0 * t) + 0.05 * sin(2 * PI * 37.3 * t) * sin(2 * PI * 91.7 * t + 0.4) +
                      0.03 * sin(2 * PI * 143.0 * t) * sin(2 * PI * 1.9 * t) + 0.12 * sin(2 * PI * 0.31 * t + 1.0))
        }
        return v
    }

    private fun ecgRawAt(t: Double, lead: Int): Double {
        scheduleUntil(t)
        val pads = lead == SLLeadPads
        if (if (pads) !padsOn else !ecgOn) return 0.0   // elettrodi / piastre staccati: linea piatta
        val ml = if (pads) SLLeadII else lead        // le piastre (apice-sottoclaveare) vedono un'onda simile a II
        // ANDROID (12 derivazioni 22/11): II e V5 sono due delle 12 derivazioni del modello (indipendenti: 1 = II, 6 = V5)
        var v = rhythmEcgAt(t, if (ml == SLLeadV) 6 else 1) * (if (pads) 0.9 else 1.0)
        if (cpr) v += cprArtifact(cprPhaseAt(t)) * (if (lead == SLLeadV) 0.8 else (if (pads) 1.3 else 1.0))
        if (motion != 0) v += motionArtifact(t + (if (lead == SLLeadV) 0.37 else 0.0), motion) * (if (pads) 1.2 else 1.0)
        if (touch) v += touchArtifact(t + (if (lead == SLLeadV) 0.21 else 0.0)) * (if (pads) 1.3 else 0.9)
        // pacing senza cattura: grande artefatto dopo lo spike, senza onda T ("falsa cattura")
        if (paceActive && !paceCapture && paceFalse) {
            // ANDROID (funzioni 21/11): falsa cattura impostata (config false_capture). Sopra soglia ogni spike è seguito da
            // un artefatto grosso e largo che SEMBRA un QRS stimolato (deflessione ampia ~0,15 s, ritorno lento) ma
            // non ha l'onda T discordante e non c'è polso: il partecipante deve controllare il polso, non fidarsi dell'ECG.
            val k = 1.25 * (if (lead == SLLeadV) -0.8 else 1.0) * (if (lead == SLLeadPads) 1.15 else 1.0)
            for (i in 0 until paceCount) {
                val tau = t - paces[(paceHead + i) % 16]
                if (tau < 0 || tau > 0.45) continue
                v += k * (1.0 * gauss(tau, 0.040, 0.026) + 0.45 * gauss(tau, 0.120, 0.050) - 0.12 * gauss(tau, 0.260, 0.060))
            }
        } else if (paceActive && !paceCapture && paceOut >= 20) {
            val k = min(1.3, (paceOut - 10) / 90.0) * (if (lead == SLLeadV) -0.8 else 1.0)
            for (i in 0 until paceCount) {
                val tau = t - paces[(paceHead + i) % 16]
                if (tau < 0 || tau > 0.35) continue
                v += k * (0.9 * gauss(tau, 0.035, 0.022) + 0.35 * gauss(tau, 0.10, 0.04))
            }
        }
        // shock: deflessione fuori scala e ritorno lento alla linea di base (~2-3 s)
        if (shockAt > 0 && t >= shockAt && t - shockAt < 3.0) {
            val x = t - shockAt
            v += (if (lead == SLLeadV) -1.0 else 1.0) * (if (x < 0.05) 4.0 else 3.2 * exp(-(x - 0.05) / 0.45))
        }
        return v
    }

    // Marcatori nell'intervallo [t0, t1): spike del pacemaker e (se sync) onde R riconosciute.
    fun markersFrom(t0: Double, to: Double, sync: Boolean): Int {
        val t1 = to
        var m = SLMarkerNone
        for (i in 0 until paceCount) {
            val p = paces[(paceHead + i) % 16]
            if (p >= t0 && p < t1) { m = m or SLMarkerPace; break }
        }
        if (sync) for (i in 0 until beatCount) {
            val bb = beatAt(i); if (!bb.qrs) continue                  // ANDROID (transizioni ritmo 22/11): un'onda P non è un'onda R
            val b = bb.t
            if (b >= t0 && b < t1) { m = m or SLMarkerSync; break }
        }
        return m
    }

    // ANDROID (12 derivazioni 22/11): `i` = derivazione indipendente del modello (0 I, 1 II, 2..7 V1..V6; 1 = II e 6 = V5 del
    // monitor). Finestra un po' più ampia (PR lunghi dei blocchi AV, QT lungo, onde U).
    private fun beatSumAt(t: Double, i: Int): Double {
        var v = 0.0
        val cb = capBeats
        if (cb != null) {
            for (k in cb.indices) { val b = cb[k]; val tau = t - b.t; if (tau < -0.55 || tau > 1.05) continue; v += beatShape(b, tau, i) }
            return v
        }
        for (k in 0 until beatCount) {
            val b = beatAt(k)
            val tau = t - b.t
            if (tau < -0.55 || tau > 1.05) continue
            v += beatShape(b, tau, i)
        }
        return v
    }

    // ANDROID (transizioni ritmo 22/11): FORMA di un battito per derivazione (tipo di battito -> complesso). È il punto unico in
    // cui il ritmo/conduzione (sequenza dei battiti) diventa morfologia.
    // ANDROID (12 derivazioni 22/11): i battiti CONDOTTI (sinusali, FA, giunzionali, extrasistoli atriali, QRS che si allarga,
    // onde P) seguono il modello a 12 derivazioni della morfologia impostata (SFEcg12.kt: ST, blocchi di branca, WPW, elettroliti...);
    // i battiti di origine ventricolare (TV, extrasistoli, scappamenti, stimolati, PEA) hanno la forma di prima scalata per
    // derivazione (asse del focus): in II e V5 restano identici a prima.
    private fun beatShape(b: SLBeat, tau: Double, i: Int): Double = when (b.morph) {
        SLMorphWide -> ecgWide(tau, b.rr, SLLeadII) * K_WIDE[i]
        SLMorphPaced -> ecgPaced(tau, b.rr, SLLeadII) * K_PACED[i]
        SLMorphPEA -> ecgPEA(tau, b.rr, SLLeadII) * K_PEA[i]
        SLMorphPVC -> b.amp * ecgPvc(tau, SLLeadII, b.pol) * K_PVC[i]
        SLMorphPoly -> b.amp * b.pol * ecgWide(tau, b.rr, SLLeadII) * K_WIDE[i]
        SLMorphEscape -> b.amp * ecgEscape(tau, b.rr, SLLeadII, b.widen) * K_ESC[i]
        SLMorphPAC -> model.ind(i, tau, b.rr, false, -1.0, qa(b)) + K_PACP[i] * (0.10 * gauss(tau, -0.13, 0.018) - 0.025 * gauss(tau, -0.095, 0.015))
        SLMorphPOnly -> model.ind(i, tau, 1.0, false, pOnly = true)
        SLMorphWidened -> b.amp * model.ind(i, tau / (1.0 + 2.6 * max(0.0, min(1.0, b.widen))), b.rr, b.hasP)
        else -> model.ind(i, tau, b.rr, b.hasP, b.pr, qa(b))
    }
    private fun qa(b: SLBeat): Double = if (model.alternans == 0.0) 1.0 else 1.0 + model.alternans * (if (b.seq % 2 == 0) 1.0 else -1.0)

    // ANDROID (12 derivazioni 22/11): fondo dell'ECG per derivazione indipendente (onde della FV, onde f della FA, linea
    // dell'asistolia, deriva respiratoria). In II (i = 1) e V5 (i = 6) sono esattamente quelli di prima.
    private fun drift(t: Double, i: Int): Double = 0.02 * sin(2 * PI * max(rr, 6.0) / 60.0 * t + DRIFT_PH[i])
    private fun asysAt(t: Double, i: Int): Double = when (i) {
        1 -> asystoleBaseline(t, false); 6 -> asystoleBaseline(t, true)
        0 -> 0.55 * asystoleBaseline(t + 2.3, false)
        else -> (0.6 + 0.08 * i) * asystoleBaseline(t + 0.6 * i, true)
    }
    private fun vfAt(t: Double, i: Int, a: Double): Double {
        val u = vfWarp(t)
        val env = 0.55 + 0.45 * sin(2 * PI * 0.23 * t + 0.7 * sin(2 * PI * 0.07 * t))
        val kII = VF_II[i]; val kV = VF_V[i]
        var v = 0.0
        if (kII != 0.0) v += kII * (0.45 * sin(2 * PI * 4.7 * u + 1.3 * sin(2 * PI * 0.5 * t)) + 0.25 * sin(2 * PI * 6.3 * u + 0.4) + 0.15 * sin(2 * PI * 3.1 * u + 2.1))
        if (kV != 0.0) v += kV * (0.50 * sin(2 * PI * 5.2 * u + 0.8 + 1.1 * sin(2 * PI * 0.4 * t)) + 0.22 * sin(2 * PI * 3.7 * u + 1.7) + 0.12 * sin(2 * PI * 7.1 * u))
        return a * env * v
    }
    private fun afAt(t: Double, i: Int): Double {
        if (i == 6) return 0.5 * (0.045 * sin(2 * PI * 5.7 * t + 0.9) + 0.03 * sin(2 * PI * 7.3 * t + 1.1) + 0.02 * sin(2 * PI * 4.3 * t + 2.0))
        return AF_K[i] * (0.045 * sin(2 * PI * 5.7 * t) + 0.03 * sin(2 * PI * 7.3 * t + 1.1) + 0.02 * sin(2 * PI * 4.3 * t + 2.0))
    }

    private fun rhythmEcgAt(t: Double, i: Int): Double {
        // ANDROID (transizioni ritmo 22/11): ritmo e sfondo dell'istante t (durante il preludio: quelli della fase)
        val rhythm = if (capBeats != null && plan == null) rhythmPast(t) else rhythmAt(t)
        val p = plan
        if (p != null && inPlan(t)) {
            val ph = p.phaseAt(t - planBase); val bg = ph.bg
            if (bg == SLOnsetBgBase) return beatSumAt(t, i) + drift(t, i)
            if (bg == SLOnsetBgAsys) {
                // la linea dell'asistolia entra in 1,5 s (niente gradino rispetto alla deriva di prima)
                val w = if (ph.at <= 0 && p.from == SLRhythmAsystole) 1.0 else max(0.0, min(1.0, (t - planBase - ph.at) / 1.5))
                return w * asysAt(t, i) + (1 - w) * drift(t, i) + beatSumAt(t, i)
            }
        }
        if (rhythm == SLRhythmVentricularFib) {
            // ANDROID (ampiezza FV 22/11): stessa forma di prima (picco-picco tipico ~1 mV) scalata all'ampiezza della FV e con la
            // frequenza dominante del momento (tempo deformato u); nel preludio FV -> asistolia l'ampiezza la decide il piano
            val a = if (p != null && p.vfAmpFrom > 0 && inPlan(t)) p.vfAmpAt(t - planBase) else vfAmp
            return vfAt(t, i, a)
        }
        if (rhythm == SLRhythmAsystole) {
            // Asistolia reale: nessun QRS, ma linea di base che ondeggia lentamente e irregolarmente
            // (respiro agonico/movimenti, deriva degli elettrodi) con piccole irregolarità.
            return asysAt(t, i) + beatSumAt(t, i)   // + eventuali battiti stimolati (e onde P dei blocchi AV avanzati)
        }

        var v = beatSumAt(t, i)
        if (rhythm == SLRhythmAtrialFibrillation) v += afAt(t, i)       // in V5 le onde f sono poco evidenti
        v += drift(t, i)   // deriva respiratoria
        return v
    }

    // ANDROID (12 derivazioni 22/11): ritmo a un istante passato (carta 12D)
    private fun rhythmPast(t: Double): String {
        var r = rhythm
        for (k in 0 until rhN) { val i = (rhHead - k + 8) % 8; if (rhT[i] > t) r = rhR[(i + 7) % 8] ?: r else break }
        return r
    }

    // MARK: - ANDROID (12 derivazioni 22/11) — carta a 12 derivazioni

    /** Carta 12D: 12 derivazioni (ordine SL12…) campionate a `fs` Hz per `seconds` s fino a adesso; spike del pacemaker (s
     *  dall'inizio); FC dagli R-R; misure del battito condotto (null se non misurabili); interpretazione automatica. */
    class SLEcg12Capture(val fs: Int, val seconds: Double, val leads: Array<FloatArray>, val paceAt: DoubleArray, val hr: Int,
                         val measures: SLEcgMeasures?, val reading: SFEcg12.SLEcgReading, val electrodes: Boolean, val rhythm: String,
                         val morph: SLEcgMorph)

    /** Stessi battiti e stesso ritmo del tracciato (storico del motore); da chiamare sul thread che disegna le curve. */
    fun capture12(seconds: Double = 10.0, fs: Int = 250, tEnd: Double = lastT): SLEcg12Capture {
        val n = Math.max(1, (seconds * fs).toInt()); val t0 = tEnd - seconds
        val out = Array(12) { FloatArray(n) }
        val bl = ArrayList<SLBeat>()
        for (k in 0 until histCount) { val b = histAt(k); if (b.t > t0 - 1.1 && b.t < tEnd + 0.6) bl.add(b) }
        val v = DoubleArray(SLInd)
        capBeats = bl
        try {
            for (k in 0 until n) {
                val t = t0 + k.toDouble() / fs
                for (i in 0 until SLInd) {
                    if (!ecgOn) { v[i] = 0.0; continue }
                    var x = rhythmEcgAt(t, i)
                    val pre = i >= 2
                    if (cpr && cprStart >= 0) x += cprArtifact((Math.max(0.0, t - cprStart) % cprPeriod) / cprPeriod) * ART_K[i]
                    if (motion != 0) x += motionArtifact(t + 0.062 * i, motion) * ART_K[i]
                    if (touch) x += touchArtifact(t + 0.035 * i) * 0.9 * ART_K[i]
                    if (shockAt > 0 && t >= shockAt && t - shockAt < 3.0) { val y = t - shockAt; x += (if (pre) -1.0 else 1.0) * (if (y < 0.05) 4.0 else 3.2 * exp(-(y - 0.05) / 0.45)) }
                    v[i] = x
                }
                for (l in 0 until 12) out[l][k] = SL12FromInd(v, l).toFloat()
            }
        } finally { capBeats = null }
        val pz = ArrayList<Double>()
        for (k in 0 until paceHistCount) { val p = paceHist[(paceHistHead + k) % 64]; if (p >= t0 && p < tEnd) pz.add(p - t0) }
        // FC dagli R-R dei QRS sulla carta (come la stampa del dispositivo)
        val q = bl.filter { it.qrs && it.t >= t0 && it.t <= tEnd }
        val rhy = rhythm
        val hrShown = when {
            !ecgOn -> -1
            paceCaptureNow -> paceRate.toInt()
            rhy == SLRhythmVentricularFib -> -1
            rhy == SLRhythmAsystole -> 0
            q.size >= 2 -> Math.round(60.0 * (q.size - 1) / (q.last().t - q.first().t)).toInt()
            else -> hr.toInt()
        }
        // ANDROID (EF-03): la PEA stretta si legge come il ritmo che si vede (il polso non lo vede nessun ECG)
        val rhyR = if (rhy == SLRhythmPEA && peaNarrow) (if (hr >= 100) SLRhythmSinusTachycardia else if (hr < 60) SLRhythmSinusBradycardia else SLRhythmSinus) else rhy
        val sinusF = rhyR == SLRhythmSinus || rhyR == SLRhythmSinusTachycardia || rhyR == SLRhythmSinusBradycardia
        val conducted = ecgOn && !paceCaptureNow && (sinusF || rhy == SLRhythmAtrialFibrillation) && !(avMode == "third" && !escJunc)
        val meas = if (!conducted || hrShown <= 0) null else {
            val rrM = 60.0 / Math.max(20, hrShown)
            val prB = when (avMode) { "mobitz1" -> 0.20; "mobitz2", "2to1", "high_grade" -> 0.17; else -> -1.0 }
            model.measures(rrM, sinusF && avMode != "third" && !morphNow.has("hyperkalemia_severe"), prB)
        }
        val reading = SFEcg12.interpret(morphNow, rhyR, if (hrShown > 0) hrShown else hr.toInt(), atrial.toInt(), avActiveNow, paceCaptureNow, meas)
        return SLEcg12Capture(fs, seconds, out, pz.toDoubleArray(), hrShown, meas, reading, ecgOn, rhy, morphNow)
    }

    // linea dell'asistolia: nessun QRS, ma linea di base che ondeggia lentamente e irregolarmente
    private fun asystoleBaseline(t: Double, v5: Boolean): Double {
        val ph = if (v5) 1.7 else 0.0
        val wander = 0.11 * sin(2 * PI * 0.33 * t + ph + 0.8 * sin(2 * PI * 0.07 * t)) +
                     0.07 * sin(2 * PI * 0.71 * t + 0.4 + ph * 0.5) +
                     0.04 * sin(2 * PI * 1.27 * t + 1.3 + ph) * (0.6 + 0.4 * sin(2 * PI * 0.19 * t))
        val ripple = 0.012 * sin(2 * PI * 3.1 * t + ph) * (0.5 + 0.5 * sin(2 * PI * 0.23 * t))
        return (if (v5) 0.8 else 1.0) * (wander + ripple)
    }

    // Tempo dell'ultimo complesso QRS <= t, -1 se nessuno.
    fun lastBeatAtOrBefore(t: Double): Double {
        var last = -1.0
        for (i in 0 until beatCount) {
            val b = beatAt(i)
            if (b.qrs && b.t <= t) last = b.t
        }
        return last
    }

    // Tempo dell'ultimo battito PERFUSO <= t, -1 se nessuno.
    // ANDROID (sincronia polso 22/11): perfuso = con sistole efficace (sv > 0, la stessa che genera le onde di polso)
    fun lastPerfusedBeatAtOrBefore(t: Double): Double {
        var last = -1.0
        for (i in 0 until beatCount) {
            val b = beatAt(i)
            if (b.qrs && b.perfused && b.sv > 0.0 && b.t <= t) last = b.t
        }
        return last
    }

    // MARK: - Onde di polso (pletismografia / ART)

    // ANDROID (sincronia polso 22/11): onde di polso ANCORATE AGLI ISTANTI DEI QRS EFFICACI della coda dei battiti (la stessa
    // dell'ECG e del bip): piede dell'onda = picco R + PEP del battito + tempo di transito al sito (radiale ~85 ms, dito
    // ~190 ms). Onda "corrente" = quella dell'ultimo battito efficace con il piede <= t; dura fino al piede del battito
    // efficace successivo: un QRS senza sistole efficace non genera un'onda (l'onda prima continua a scendere, come nel
    // deficit di polso). Prima: ritardo fisso dal picco R (ART 120 ms, pleth 220 ms, picchi a 150-210 / 270-350 ms secondo la
    // FC perché anche la salita veniva compressa nell'RR) e ampiezza dall'intervallo SUCCESSIVO (in FA).
    // Battito sconosciuto oltre l'orizzonte di programmazione: il piede successivo è stimato dall'orizzonte (cresce con t e
    // coincide con quello vero quando il battito viene programmato: niente salti della curva).
    private fun pulseAt(t: Double, transit: Double): SLPulse? {
        var found = -1; var on = 0.0
        for (i in 0 until beatCount) {
            val b = beatAt(i)
            if (!b.qrs || b.sv <= 0.0) continue                   // onda P o QRS senza sistole efficace: nessuna onda
            val o = b.t + b.pep + transit
            if (o <= t) { found = i; on = o } else break
        }
        if (found < 0) return null
        val b = beatAt(found)
        var next = -1.0
        for (i in found + 1 until beatCount) { val x = beatAt(i); if (x.qrs && x.sv > 0.0) { next = x.t + x.pep + transit; break } }
        if (next < 0) next = max(t, lastT) + SL_LOOKAHEAD + 0.07 + transit   // il prossimo QRS efficace non è ancora programmato
        return SLPulse(t - on, max(0.2, next - on), b.sv, b.mrr, on)
    }

    // Arrivo dell'onda pletismografica al dito (piede) dell'ultimo battito efficace <= t: istante del bip del saturimetro. -1 se nessuno.
    fun lastPulseArrivalAtOrBefore(t: Double): Double {
        if (!pulse) return -1.0
        var last = -1.0
        for (i in 0 until beatCount) {
            val b = beatAt(i)
            if (!b.qrs || b.sv <= 0.0) continue
            val o = b.t + b.pep + PLETH_TRANSIT
            if (o <= t) last = o
        }
        return last
    }

    // MARK: - Pletismografia

    // ~0..1 (può superare 1: nessun taglio)
    fun plethAt(t: Double): Double {
        scheduleUntil(t)
        if (!spo2On || searching) return 0.0      // sonda appena messa: ricerca del polso, nessuna onda
        if (motion != 0) {                               // dito/mano che si muove: curva disturbata
            val base = plethCleanAt(t)
            return max(0.0, min(1.1, base * (if (motion == 2) 0.5 else 0.8) + 0.45 * motionArtifact(t * 0.8 + 3.1, motion) + (if (motion == 2) 0.35 else 0.1)))
        }
        return plethCleanAt(t)
    }

    private fun plethCleanAt(t: Double): Double {
        if (spo2Occl) return 0.02 + 0.01 * sin(2 * PI * 0.2 * t)   // bracciale chiuso a monte: il dito non è perfuso, linea piatta
        if (!signalValid) {
            return 0.35 + 0.25 * sin(2 * PI * 1.3 * t) * sin(2 * PI * 0.37 * t) + 0.15 * (rand01() - 0.5)
        }
        var v: Double
        if (!pulse && cpr) return 0.18 * cprPulse((cprPhaseAt(t) + 0.85) % 1.0)   // RCP: onde piccole dalle compressioni
        if (!pulse) return plethTailAt(t)            // arresto senza RCP: linea piatta (niente rumore da bassa perfusione), dopo l'ultima onda (BUG-B2)
        val p = pulseAt(t, PLETH_TRANSIT) ?: return 0.0     // nessun polso: linea piatta
        smoothBp(t)                                                                // ANDROID (sincronia polso 22/11, EF-19)
        val perfusion = perfC
        v = p.amp * fitPulse(::plethPulse, PLETH_UP, p.tau, p.rrNext, p.mrr) * perfusion
        if (ventMech) v *= respPulseMod(t - 0.1, 1.5)                                // ANDROID (OP-05): il pleth «respira» col ventilatore
        if (weakSig) v *= 0.35                                                    // ANDROID (perfusione): segnale quasi perso
        if (perfusion < 0.55) {
            // bassa perfusione: onda piccola ma REGOLARE (ANDROID AE-16: ampiezza battito per battito entro ±8%, prima variava
            // di 3 volte con l'ART stabile), rumore e deriva della linea di base
            val bad = min(1.0, (0.55 - perfusion) / 0.43)                         // 0..1
            v *= 0.92 + 0.08 * sin(2 * PI * 0.37 * t + 1.1 * sin(2 * PI * 0.11 * t))
            // rumore di fondo e deriva lenta, piccoli (ANDROID AE-16: prima confondevano l'ampiezza dei battiti)
            v += bad * (0.022 * sin(2 * PI * 0.45 * t) + 0.012 * sin(2 * PI * 3.3 * t) * sin(2 * PI * 0.7 * t)
                        + 0.014 * (rand01() - 0.5))
        }
        plLastV = v * 0.8; plLastT = t                // ANDROID (audit 26/11, BUG-B2): ultimo valore disegnato, per il raccordo
        return v * 0.8      // nessun taglio: l'ampiezza la gestisce la scala automatica del tracciato
    }

    // MARK: - Pressione arteriosa cruenta

    // mmHg (pressione «vera» all'arteria; artAt aggiunge gli effetti della linea)
    private fun artTrueAt(t: Double): Double {
        scheduleUntil(t)
        if (!artOn) { artLastV = -1.0; return 0.0 }                     // trasduttore staccato: 0 mmHg
        if (artOccl) { artLastV = -1.0; return 25 + 0.4 * sin(2 * PI * 0.2 * t) }   // bracciale chiuso a monte: pressione intrappolata, niente polso
        if (!pulse) {
            // ANDROID (ART RCP 22/11): base (diastolica di rilasciamento con le compressioni, riempimento sistemico senza) che
            // evolve in modo continuo tra due istantanee; un'onda per compressione alla loro frequenza, ampiezza dalla qualità
            val base = if (artBase <= 0) 20.0
                       else if (artFrozen) artBase
                       else artBaseTarget + (artBase - artBaseTarget) * exp(-max(0.0, t - artBaseAt) / artBaseTau)
            if (cpr && artAmp > 0) return arrestOut(t, base + artAmp * cprPulse(cprPhaseAt(t)))
            // arresto: pressione di riempimento (~15 mmHg); in FV piccole oscillazioni da artefatto
            var v = base + 0.6 * sin(2 * PI * 0.25 * t)
            // ANDROID (ART RCP 22/11): tremolio della FV più piccolo e irregolare (prima 1,5 mmHg regolari: con la scala automatica
            // del tracciato sembravano onde in una pausa delle compressioni)
            if (rhythm == SLRhythmVentricularFib) v += 0.45 * sin(2 * PI * 4.9 * t) + 0.3 * sin(2 * PI * 7.3 * t + 1.1)
            return arrestOut(t, v)
        }
        smoothBp(t)                                                                // ANDROID (sincronia polso 22/11, EF-19)
        val sys = sysC; val dia = diaC
        if (artArrestV >= 0) { roscP0 = if (artArrestV < dia) artArrestV else -1.0; roscAt = t; roscOn = -1.0; artArrestV = -1.0 }   // ANDROID (sincronia polso 22/11)
        var pulse = 0.0
        val p = pulseAt(t, ART_TRANSIT)
        if (p != null) pulse = p.amp * fitPulse(::artPulse, ART_UP, p.tau, p.rrNext, p.mrr)
        // variazione respiratoria della pressione (PPV ~6%): l'onda "respira" con la ventilazione
        pulse *= respPulseMod(t, 1.0)                    // ANDROID (OP-05): col ventilatore PPV dalla volemia, allineata alle insufflazioni
        // ANDROID (sincronia polso 22/11): ritorno del polso (ROSC): la pressione resta quella dell'arresto fino alla prima
        // sistole efficace e la diastolica sale in pochi battiti (prima saltava subito alla diastolica, senza un QRS)
        if (roscP0 >= 0) {
            if (p == null || p.onset < roscAt) return roscP0
            if (roscOn < 0) roscOn = p.onset
            val x = (t - roscOn) / 1.2
            if (x > 6) roscP0 = -1.0
            else { val d0 = dia + (roscP0 - dia) * exp(-x); val v = d0 + (sys - d0) * pulse; artLastV = v; artLastT = t; return v }
        }
        val v = dia + (sys - dia) * pulse
        artLastV = v; artLastT = t
        return v
    }

    // MARK: - Capnografia

    // mmHg
    fun capnoAt(t: Double): Double {
        if (ventManual) {
            // un'onda per ogni insufflazione: ~1 s di insufflazione (niente CO2), poi l'espirazione con il plateau, poi zero.
            // ANDROID (EtCO2 RCP 22/11): ogni onda ha il suo picco (breathCo2); con le compressioni in corso piccole oscillazioni
            // alla loro frequenza: sulla linea di base (un po' di gas spinto fuori a ogni compressione) e sul plateau.
            // Niente allocazioni: cicli con indice su liste già pronte.
            if (!etco2On) return 0.0
            val osc = if (cpr && co2Osc > 0) co2Osc else 0.0
            val bump = if (osc > 0) capnoCompression(cprPhaseAt(t)) else 0.0
            var tb = -1.0; var peak = etco2
            val n = breathTimes.size
            for (i in 0 until n) { val b = breathTimes[i]; if (b <= t) { tb = b; peak = if (i < breathCo2.size) breathCo2[i] else etco2 } }
            val dt = if (tb < 0) -1.0 else t - tb
            if (dt < 0 || dt > 3.0 || peak <= 0) return osc * bump                 // tra un atto e l'altro: solo le oscillazioni
            if (dt < 1.0) return osc * bump * min(1.0, 1.0 - dt)                   // insufflazione: gas fresco, oscillazioni smorzate
            val w: Double
            if (dt < 1.3) { val x = (dt - 1.0) / 0.3; w = peak * 0.85 * (0.5 - 0.5 * cos(PI * x)) }
            else if (dt < 2.7) { val x = (dt - 1.3) / 1.4; w = peak * (0.85 + 0.15 * x) }
            else { val x = (dt - 2.7) / 0.3; w = peak * (0.5 + 0.5 * cos(PI * x)) }
            // sul plateau l'oscillazione è più piccola rispetto al valore (gas alveolare uniforme) e mai sopra il picco reale
            return max(osc * bump, w + (if (dt >= 1.3 && dt < 2.7) osc * 0.6 * (bump - 0.6) else 0.0))
        }
        if (!etco2On || rr < 1 || etco2 <= 0) return 0.0
        if (ventMech && ventStartE > 0) {                  // ANDROID (CC-04): atti del ventilatore regolari, in fase con la PPV
            breathPeriod = 60.0 / rr; breathStart = ventStartE + Math.floor((t - ventStartE) / breathPeriod) * breathPeriod; breathStarted = true
        }
        if (!breathStarted) { breathStart = t; breathPeriod = 60.0 / rr; breathStarted = true }
        while (t - breathStart >= breathPeriod) {
            breathStart += breathPeriod
            breathPeriod = 60.0 / max(rr, 1.0)
        }
        // ANDROID (difetti clinici 22/11, OP-02): fasi con durate FISIOLOGICHE in secondi, quasi indipendenti dalla FR: fase I
        // (spazio morto) ~0,15 s, salita (fase II) ~0,25 s, discesa inspiratoria (fase 0) ~0,18 s, inspirazione al massimo 1,4 s;
        // nella bradipnea si allunga il PLATEAU (onda «a scatola» larga), con pendenza fissa ~1,5 mmHg/s. Prima le fasi erano
        // frazioni del periodo: a FR 5-6 salita di 1 s e discesa obliqua = forma da ostruzione/rirespirazione.
        // A FR 16 la forma resta quella di prima (plateau ~44% del ciclo).
        val P = breathPeriod; val tt = t - breathStart
        val f1 = min(0.15, 0.08 * P); val f2 = min(0.25, 0.10 * P); val f0 = min(0.18, 0.06 * P); val ti = min(1.4, 0.32 * P)
        val pl = max(0.05, P - ti - f1 - f2 - f0)
        val pS = max(etco2 * 0.85, etco2 - 1.5 * pl)              // inizio del plateau: sale fino all'EtCO2 a fine espirazione
        if (tt < f1) return 0.0
        if (tt < f1 + f2) { val x = (tt - f1) / f2; return pS * (0.5 - 0.5 * cos(PI * x)) }
        if (tt < f1 + f2 + pl) { val x = (tt - f1 - f2) / pl; return pS + (etco2 - pS) * x }
        if (tt < f1 + f2 + pl + f0) { val x = (tt - f1 - f2 - pl) / f0; return etco2 * (0.5 + 0.5 * cos(PI * x)) }
        return 0.0
    }

    private companion object {
        const val SL_MAX_BEATS = 16
        const val SL_HIST = 128          // ANDROID (12 derivazioni 22/11): storico dei battiti (carta 12D, 10 s)
        // ANDROID (12 derivazioni 22/11): fattori per derivazione indipendente [I, II, V1, V2, V3, V4, V5, V6] dei battiti di origine
        // ventricolare (II = 1 e V5 = valore di prima: tracciato del monitor invariato) e degli sfondi
        val K_WIDE = doubleArrayOf(-0.30, 1.0, 0.80, 0.60, 0.20, -0.40, -0.90, -0.90)
        val K_PACED = doubleArrayOf(0.60, 1.0, -0.90, -1.00, -0.80, -0.60, -0.85, -0.70)
        val K_PEA = doubleArrayOf(0.60, 1.0, -0.50, -0.30, 0.40, 0.90, 1.15, 0.90)
        val K_PVC = doubleArrayOf(0.40, 1.0, -0.80, -1.00, -0.70, -0.60, -0.90, -0.80)
        val K_ESC = doubleArrayOf(0.20, 1.0, 0.70, 0.50, 0.10, -0.40, -0.85, -0.80)
        val K_PACP = doubleArrayOf(0.60, 1.0, 0.60, 0.60, 0.65, 0.70, 0.75, 0.70)
        val DRIFT_PH = doubleArrayOf(0.4, 0.0, 2.1, 2.5, 2.9, 0.8, 1.3, 1.7)
        val VF_II = doubleArrayOf(0.45, 1.0, 0.20, 0.30, 0.40, 0.30, 0.0, 0.25)
        val VF_V = doubleArrayOf(0.25, 0.0, 0.90, 1.10, 1.00, 1.00, 1.0, 0.70)
        val AF_K = doubleArrayOf(0.35, 1.0, 1.40, 1.00, 0.70, 0.55, 0.5, 0.40)
        val ART_K = doubleArrayOf(0.65, 1.0, 0.75, 0.85, 0.90, 0.90, 0.80, 0.70)   // artefatti (RCP, movimento, contatto)
        const val SL_LOOKAHEAD = 0.35   // s: serve per disegnare l'onda P prima del QRS

        // SLMorph
        const val SLMorphNarrow = 0
        const val SLMorphWide = 1
        const val SLMorphPEA = 2
        const val SLMorphPaced = 3
        // ANDROID (transizioni ritmo 22/11): battiti del preludio
        const val SLMorphPVC = 4
        const val SLMorphPoly = 5
        const val SLMorphEscape = 6
        const val SLMorphPAC = 7
        const val SLMorphPOnly = 8
        const val SLMorphWidened = 9

        // Extrasistole ventricolare: QRS largo (~140 ms) senza P, T ampia discordante; pol = asse (foco diverso = segno/ampiezza)
        fun ecgPvc(tau: Double, lead: Int, pol: Double): Double {
            val s = (if (lead == SLLeadV) -0.9 else 1.0) * pol
            var v = 1.35 * gauss(tau, 0.000, 0.036) - 0.45 * gauss(tau, 0.075, 0.034)
            v += -0.42 * gauss(tau, 0.30, 0.07)
            return s * v
        }
        // Scappamento idioventricolare: QRS molto largo (160-220 ms), lento, T larga discordante
        fun ecgEscape(tau: Double, rr: Double, lead: Int, widen: Double): Double {
            val s = if (lead == SLLeadV) -0.85 else 1.0
            val wf = 1.0 + 0.6 * max(0.0, min(1.0, widen))
            var v = 0.95 * gauss(tau, 0.0, 0.05 * wf) - 0.35 * gauss(tau, 0.11 * wf, 0.05 * wf)
            v += -0.30 * gauss(tau, min(0.42, rr * 0.3) + 0.05 * wf, 0.09)
            return s * v
        }
        // ANDROID (audit 26/11, codice non usato): tolte ecgPac, ecgP, ecgWidened (abbozzi mai collegati)

        fun gauss(x: Double, mu: Double, sigma: Double): Double {
            val z = (x - mu) / sigma
            return exp(-0.5 * z * z)
        }

        // Complesso PQRST attorno al picco R (tau = t - tR), ampiezze in mV.
        fun ecgNarrow(tau: Double, rr: Double, hasP: Boolean, lead: Int): Double {
            val qt = 0.40 * sqrt(min(max(rr, 0.25), 2.0))
            val pT = -0.16 * min(1.0, sqrt(rr))
            var v = 0.0
            if (lead == SLLeadII) {
                if (hasP) v += 0.12 * gauss(tau, pT, 0.025)
                v += -0.10 * gauss(tau, -0.028, 0.008)
                v +=  1.00 * gauss(tau,  0.000, 0.010)
                v += -0.25 * gauss(tau,  0.026, 0.009)
                v +=  0.28 * gauss(tau,  qt * 0.62, qt * 0.11)
            } else {
                // V = V5 (precordiale laterale, monitoraggio a 5 elettrodi): P positiva piccola,
                // complesso qR (piccola q, R alta più ampia che in II, piccola s), ST isoelettrico, T positiva ampia.
                if (hasP) v += 0.09 * gauss(tau, pT, 0.024)
                v += -0.08 * gauss(tau, -0.024, 0.007)            // q settale
                v +=  1.45 * gauss(tau,  0.002, 0.0105)           // R alta
                v += -0.18 * gauss(tau,  0.028, 0.008)            // piccola s
                v +=  0.40 * gauss(tau,  qt * 0.63, qt * 0.12)    // T positiva
            }
            return v
        }

        // PEA: complessi più larghi (~120 ms) e di ampiezza ridotta, T ampia e smussata
        fun ecgPEA(tau: Double, rr: Double, lead: Int): Double {
            val k = if (lead == SLLeadII) 1.0 else 1.15
            var v = 0.04 * gauss(tau, -0.17, 0.03)
            v += 0.72 * gauss(tau, 0.000, 0.026)
            v += -0.28 * gauss(tau, 0.065, 0.030)
            v += 0.20 * gauss(tau, min(0.36, rr * 0.45), 0.075)
            return k * v
        }

        // Compressione toracica: artefatto ampio e lento sull'ECG (forma asimmetrica)
        fun cprArtifact(ph: Double): Double {
            return 0.85 * gauss(ph, 0.18, 0.07) - 0.30 * gauss(ph, 0.45, 0.10)
        }
        // ANDROID (EtCO2 RCP 22/11): oscillazione da compressione sul capnogramma (0..1): picco breve a ogni compressione
        // (gas spinto fuori), ritorno durante il rilasciamento
        fun capnoCompression(ph: Double): Double {
            if (ph < 0.25) return 0.5 - 0.5 * cos(PI * ph / 0.25)
            if (ph < 0.60) return 0.5 + 0.5 * cos(PI * (ph - 0.25) / 0.35)
            return 0.0
        }
        // Onda di pressione generata dalla compressione (0..1)
        fun cprPulse(ph: Double): Double {
            if (ph < 0.12) return 0.5 * (1 - cos(PI * ph / 0.12))
            return exp(-(ph - 0.12) / 0.16)
        }

        // Complesso stimolato (cattura elettrica): QRS largo ~160 ms e T ampia discordante
        fun ecgPaced(tau: Double, rr: Double, lead: Int): Double {
            val s = if (lead == SLLeadV) -0.85 else 1.0
            // ANDROID (difetti clinici 22/11, EF-01): il QRS stimolato SEGUE lo spike (a tau = -0,03): prima della stimolazione
            // nessuna deflessione, salita rapida dopo lo spike (prima la gaussiana partiva ~70 ms PRIMA dello spike)
            val w = if (tau <= -0.03) 0.0 else if (tau >= 0.0) 1.0 else { val x = (tau + 0.03) / 0.03; x * x * (3 - 2 * x) }
            var v = (1.30 * gauss(tau, 0.000, 0.040) - 0.50 * gauss(tau, 0.090, 0.045)) * w
            v += -0.45 * gauss(tau, min(0.32, rr * 0.5), 0.085)
            return s * v
        }

        fun ecgWide(tau: Double, rr: Double, lead: Int): Double {
            // QRS largo (~160 ms) e T discordante, tipico TV monomorfa; polarità opposta in V
            val s = if (lead == SLLeadII) 1.0 else -0.9
            var v = 0.0
            v +=  1.25 * gauss(tau, 0.000, 0.040)
            v += -0.55 * gauss(tau, 0.090, 0.040)
            v += -0.35 * gauss(tau, min(0.22, rr * 0.55), 0.06)
            return s * v
        }

        // artefatto da movimento: oscillazioni lente della linea di base + raffiche irregolari più rapide (deterministico nel tempo)
        fun motionArtifact(t: Double, level: Int): Double {
            if (level <= 0) return 0.0
            var env = 0.5 + 0.5 * sin(2 * PI * 0.23 * t + 0.7); env *= env            // raffiche che vanno e vengono
            val slow = 0.55 * sin(2 * PI * 0.61 * t) + 0.35 * sin(2 * PI * 1.37 * t + 1.1)
            val fast = 0.40 * sin(2 * PI * 3.9 * t + 0.4) + 0.30 * sin(2 * PI * 7.7 * t + 2.0) + 0.20 * sin(2 * PI * 11.3 * t + 0.9)
            return (if (level == 2) 1.1 else 0.35) * (slow + (0.4 + env) * fast)
        }

        // artefatto da contatto (operatore che tocca/scuote): sbalzi rapidi e ampi della linea di base con spike irregolari
        fun touchArtifact(t: Double): Double {
            val jolt = 0.9 * sin(2 * PI * 2.3 * t) + 0.6 * sin(2 * PI * 5.1 * t + 0.8)
            val spikes = max(0.0, sin(2 * PI * 3.3 * t + 1.7)).pow(12) * 2.2 - max(0.0, sin(2 * PI * 2.7 * t + 0.3)).pow(14) * 1.8
            return jolt + spikes + 0.35 * sin(2 * PI * 13.0 * t)
        }

        // ANDROID (sincronia polso 22/11): tempi di transito dal QRS (oltre al PEP del battito) e durata della salita sistolica
        // (piede -> picco). Ritardi risultanti: ART radiale piede ~160-210 ms e picco ~260-310 ms dopo R; pleth al dito piede
        // ~265-320 ms e picco ~425-480 ms (pulse transit time; letteratura: PEP 60-110 ms, PTT dito 250-350 ms al piede).
        const val ART_TRANSIT = 0.085
        const val PLETH_TRANSIT = 0.19
        const val ART_UP = 0.10
        const val PLETH_UP = 0.16

        // ANDROID (sincronia polso 22/11): onda di polso nell'intervallo fino al piede successivo. La SALITA dura sempre uguale
        // (il picco resta a distanza costante dal QRS a qualsiasi FC; solo se l'intervallo è cortissimo si accorcia); la
        // discesa diastolica si comprime con l'RR medio (FC alte: l'onda torna alla base prima del battito successivo) e una
        // correzione lineare porta a 0 esatto al piede successivo (niente accumulo, niente gradini).
        fun fitPulse(shape: (Double) -> Double, up: Double, tau: Double, rr: Double, mrr: Double): Double {
            if (tau < 0 || tau >= rr) return 0.0
            val k = min(1.0, max(0.25, mrr) / 0.9)
            val u = min(up, 0.6 * rr)
            fun x(a: Double) = if (a < u) a * up / u else up + (a - u) / k
            return max(0.0, shape(x(tau)) - shape(x(rr)) * (tau / rr))
        }

        fun plethPulse(tau: Double): Double {
            if (tau < 0) return 0.0
            val up = PLETH_UP
            if (tau < up) return 0.5 * (1 - cos(PI * tau / up))
            val d = tau - up
            return exp(-d / 0.30) * 0.85 + 0.15 * exp(-d / 0.9) + 0.12 * gauss(d, 0.23, 0.035) * exp(-d / 0.5)
        }

        fun artPulse(tau: Double): Double {
            // 0..~1: salita rapida, spalla sistolica, incisura dicrota, decadimento diastolico
            if (tau < 0) return 0.0
            val up = ART_UP
            if (tau < up) return 0.5 * (1 - cos(PI * tau / up))
            val d = tau - up
            var v = 0.78 * exp(-d / 0.17) + 0.22 * exp(-d / 0.55)
            v -= 0.11 * gauss(d, 0.21, 0.014)          // incisura
            v += 0.07 * gauss(d, 0.255, 0.035)         // onda dicrota
            return v
        }
    }
}
