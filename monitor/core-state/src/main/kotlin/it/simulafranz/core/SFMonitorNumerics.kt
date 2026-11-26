// ANDROID (numerici realistici 22/11) — file nuovo, solo Android.
// SimulaFranz_Monitor — i NUMERI che il monitor mostra ai partecipanti, calcolati come fa un monitor vero e non come un
// contatore: il cuore conosce il valore «vero» (impostato, in rampa), il monitor lo MISURA con medie e ritardi.
//
//  - FC: dagli intervalli R-R (media degli ultimi 8 battiti, 4 sotto 40/min), numero aggiornato al primo battito dopo
//    almeno 1 s dall'ultimo aggiornamento: durante una rampa sale a gradini irregolari e con qualche secondo di ritardo.
//    (Monitor reali: media di 4-16 R-R o 5-10 s, aggiornamento ogni 1-2 s; es. IEC 60601-2-27 sul tempo di risposta
//    della FC ≤ 10 s ai cambi 80→120 e 80→40.)
//  - PR del saturimetro: stesso metodo sulla frequenza del polso (FA: deficit di polso).
//  - SpO2: media mobile di ~8 s (pulsossimetri in commercio: 2-16 s, di serie ~8) con il ritardo di circolazione polmone-dito
//    (~10 s; 15 s con perfusione bassa: Hamber EA et al., J Clin Monit Comput 1999; Young D et al., Crit Care Med 1992),
//    aggiornata ogni 1 s.
//  - EtCO2 (respiro spontaneo): il valore di fine espirazione di ogni atto; FR: dagli ultimi 4 atti. In ventilazione
//    manuale (RCP, apnea) il cuore li misura già atto per atto: passano così come sono.
//  - PA cruenta: media degli ultimi ~4 s, aggiornata ogni 1,5 s (con polso). In arresto i numeri seguono già la curva.
//  - Temperatura: sonda lenta (costante ~20 s), aggiornata ogni 2 s, in decimi.
//  - NIBP: cambia solo alla misura (com'era già).
// Le CURVE invece seguono subito la fisiologia (il motore usa i valori veri). La regia, il registro e i test usano i valori
// veri (heart_rate_bpm, spo2_percent...): questi numeri sono solo quelli «a schermo» (monitor_numerics nello stato).
//
// Uso: update() a ogni istantanea con il tempo clinico (fermo in pausa). Se tra due chiamate passano più di 5 s (nessuno ha
// guardato il monitor: test, schermo spento) i numeri si riallineano subito ai valori veri.
package it.simulafranz.core

internal class SFMonitorNumerics {
    // valori mostrati (-1 = non ancora calcolato)
    var hr = -1; var pr = -1; var spo2 = -1; var etco2 = -1; var rr = -1; var artS = -1; var artD = -1; var tempT = -1
    var cvp = -1; var temp2T = -1; private var cvpV = -1.0; private var cvpUpd = -1e9; private var temp2V = -1.0   // ANDROID (IN-13)
    private var last = -1.0

    // FC / PR: battiti simulati dalla frequenza vera (fase integrata), intervalli degli ultimi battiti
    private class RateMeter {
        val rr = DoubleArray(8); var n = 0; var head = 0
        var phase = 0.0; var lastBeat = -1.0; var lastUpd = -1e9; var shown = -1; var k = 0
        fun reset(v: Int) { n = 0; head = 0; phase = 0.0; lastBeat = -1.0; lastUpd = -1e9; shown = v }
        fun push(x: Double) { rr[(head + n) % 8] = x; if (n < 8) n++ else head = (head + 1) % 8 }
        fun mean(m: Int): Double { var s = 0.0; val c = Math.min(m, n); for (i in 0 until c) s += rr[(head + n - 1 - i + 8) % 8]; return s / c }
        // irregular: FA (intervalli 0,75-1,25 deterministici)
        fun advance(now: Double, dt: Double, rate: Int, irregular: Boolean) {
            if (rate <= 0) { reset(-1); return }                  // nessun QRS (FV, asistolia) / nessun polso: si riparte da capo
            phase += dt * rate / 60.0
            while (phase >= 1.0) {
                phase -= 1.0
                val tb = now - phase * 60.0 / rate
                if (lastBeat > 0) {
                    var x = tb - lastBeat
                    if (irregular) { k++; x *= 0.75 + 0.5 * (((k * 0.6180339887) % 1.0)) }
                    push(Math.max(0.2, x))
                }
                lastBeat = tb
                if (n >= 2 && tb - lastUpd >= 1.0) {
                    val m = if (rate < 40) 4 else 8
                    shown = Math.max(1, SLLround(60.0 / mean(m))); lastUpd = tb
                }
            }
            if (shown < 0 && lastBeat > 0 && n >= 1) shown = Math.max(1, SLLround(60.0 / mean(1)))   // primi battiti dopo l'arresto
        }
    }
    private val hrM = RateMeter(); private val prM = RateMeter()

    // SpO2: campioni ogni 0,5 s (ultimi ~40 s)
    private val sp = DoubleArray(80); private var spN = 0; private var spHead = 0; private var spAt = -1.0; private var spUpd = -1e9
    // respiro spontaneo: fase integrata
    private var brPhase = 0.0; private var brLast = -1.0; private val brRR = DoubleArray(4); private var brN = 0
    // ART: campioni ogni 0,5 s
    private val as_ = DoubleArray(8); private val ad_ = DoubleArray(8); private var aN = 0; private var aHead = 0; private var aAt = -1.0; private var aUpd = -1e9
    private var tempV = -1.0; private var tempUpd = -1e9

    /** Riallinea tutto ai valori veri (reset, salto di tempo). */
    fun settle(s: SLSnapshot) {
        hrM.reset(s.heartRate); prM.reset(s.pulseRate)
        hr = s.heartRate; pr = s.pulseRate; spo2 = s.spo2; etco2 = s.etco2; rr = s.respRate
        artS = s.artDisplayedSystolic; artD = s.artDisplayedDiastolic; tempT = s.temperatureTenths
        spN = 0; spAt = -1.0; spUpd = -1e9; brPhase = 0.0; brLast = -1.0; brN = 0; aN = 0; aAt = -1.0; aUpd = -1e9
        tempV = s.temperatureTenths.toDouble(); tempUpd = -1e9
        cvp = if (s.cvpConnected && !s.cvpNeedsZero) Math.max(0, SLLround(s.cvp + s.artOffset)) else -1; cvpV = -1.0; cvpUpd = -1e9   // ANDROID (IN-13)
        temp2T = s.temperature2Tenths; temp2V = s.temperature2Tenths.toDouble()
    }

    /** s = istantanea con i valori veri; now = tempo clinico; hrTrue = frequenza dei QRS (pacing incluso). */
    fun update(now: Double, s: SLSnapshot, hrTrue: Int, irregular: Boolean) {
        if (last < 0 || now - last > 5.0 || now < last) { settle(s); hrM.reset(if (hrTrue > 0) hrTrue else -1); hr = hrTrue; last = now; return }
        val dt = now - last
        if (dt <= 0) return
        last = now
        // --- FC / PR ---
        hrM.advance(now, dt, hrTrue, irregular); hr = hrM.shown
        prM.advance(now, dt, if (s.spo2Displayable) s.pulseRate else 0, irregular); pr = prM.shown
        // --- SpO2 (ritardo di circolazione + media) ---
        if (!s.spo2Displayable) { spN = 0; spAt = -1.0; spo2 = s.spo2 }
        else {
            if (spAt < 0) { spAt = now; for (i in 0 until 40) pushSp(s.spo2.toDouble()); spo2 = s.spo2 }
            while (now - spAt >= 0.5) { spAt += 0.5; pushSp(s.spo2.toDouble()) }
            if (now - spUpd >= 1.0) {
                spUpd = now
                val delay = if (s.systolic in 1..79 || s.spo2LowPerfusionFlag) 15.0 else 10.0
                // campioni tra (adesso - ritardo - 8 s) e (adesso - ritardo)
                val back0 = (delay / 0.5).toInt(); val back1 = ((delay + 8.0) / 0.5).toInt()
                var sum = 0.0; var c = 0
                for (b in back0 until back1) { if (b >= spN) break; sum += sp[(spHead + spN - 1 - b + 80) % 80]; c++ }
                if (c == 0) { sum = sp[spHead % 80]; c = 1 }          // storia più corta del ritardo: il campione più vecchio
                spo2 = Math.max(0, Math.min(100, SLLround(sum / c)))
            }
        }
        // --- respiro: EtCO2 e FR atto per atto (spontaneo); in ventilazione manuale li misura già il cuore ---
        if (s.ventilationManual || s.respRate <= 0) { etco2 = s.etco2; rr = s.respRate; brPhase = 0.0; brLast = -1.0; brN = 0 }
        else {
            if (etco2 < 0 || rr <= 0) { etco2 = s.etco2; rr = s.respRate }
            // un atto almeno ogni 7,5 s per la lettura (la FR che riparte da 0 dopo l'arresto non blocca il numero per 10 s)
            val br = Math.max(8, s.respRate)
            brPhase += dt * br / 60.0
            while (brPhase >= 1.0) {
                brPhase -= 1.0
                val tb = now - brPhase * 60.0 / br
                if (brLast > 0) { brRR[brN % 4] = tb - brLast; brN++ }
                brLast = tb
                etco2 = s.etco2                                              // fine espirazione: il valore di questo atto
                if (s.respRate < 8) rr = s.respRate                           // bradipnea: il numero vero (gli atti sono rari)
                else if (brN >= 2) { var sum = 0.0; val c = Math.min(4, brN); for (i in 0 until c) sum += brRR[i]; rr = Math.max(1, SLLround(60.0 / (sum / c))) }
            }
        }
        // --- ART ---
        if (s.artFlushing && aAt >= 0) { aAt = now }   // ANDROID (IN-03): durante il lavaggio rapido i numeri restano quelli di prima
        else if (!s.artConnected || !s.pulsePresent || s.artCuffOccluded || s.artNoNumbers || s.artOccluded) { aN = 0; aAt = -1.0; artS = s.artDisplayedSystolic; artD = s.artDisplayedDiastolic }
        else {
            if (aAt < 0) { aAt = now; artS = s.artDisplayedSystolic; artD = s.artDisplayedDiastolic; pushA(s) }
            while (now - aAt >= 0.5) { aAt += 0.5; pushA(s) }
            if (now - aUpd >= 1.5) {
                aUpd = now
                // ANDROID (difetti clinici 22/11, CC-02): media dal buffer circolare a partire da aHead (prima leggeva dall'indice
                // 0 e al ROSC rimostrava per 2-4 s la PA di prima dell'arresto rimasta nel buffer)
                var ss = 0.0; var dd = 0.0; for (i in 0 until aN) { val j = (aHead + i) % 8; ss += as_[j]; dd += ad_[j] }
                artS = SLLround(ss / aN); artD = Math.min(artS - 1, SLLround(dd / aN))
            }
        }
        // --- ANDROID (IN-13): PVC (media di ~4 s) e temperatura centrale ---
        if (!s.cvpConnected || s.cvpNeedsZero || s.artZeroing) { cvpV = -1.0; cvp = -1 }
        else {
            val target = s.cvp + s.artOffset
            if (cvpV < 0) cvpV = target
            cvpV = target + (cvpV - target) * Math.exp(-dt / 3.0)
            if (now - cvpUpd >= 2.0 || cvp < 0) { cvpUpd = now; cvp = Math.max(0, SLLround(cvpV)) }
        }
        if (temp2V < 0) temp2V = s.temperature2Tenths.toDouble()
        temp2V = s.temperature2Tenths + (temp2V - s.temperature2Tenths) * Math.exp(-dt / 20.0)
        if (now - tempUpd >= 2.0 || temp2T < 0) temp2T = SLLround(temp2V)
        // --- temperatura ---
        if (tempV < 0) tempV = s.temperatureTenths.toDouble()
        tempV = s.temperatureTenths + (tempV - s.temperatureTenths) * Math.exp(-dt / 20.0)
        if (now - tempUpd >= 2.0) { tempUpd = now; tempT = SLLround(tempV) }
    }

    private fun pushSp(v: Double) { sp[(spHead + spN) % 80] = v; if (spN < 80) spN++ else spHead = (spHead + 1) % 80 }
    private fun pushA(s: SLSnapshot) {
        val i = (aHead + aN) % 8
        as_[i] = s.artDisplayedSystolic.toDouble(); ad_[i] = s.artDisplayedDiastolic.toDouble()
        if (aN < 8) aN++ else aHead = (aHead + 1) % 8
    }
}
