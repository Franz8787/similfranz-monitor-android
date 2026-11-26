// ANDROID (ritardo R→polso 22/11) — file nuovo, solo Android, solo app di prova (debuggable).
// Sonda di sincronia: misura ciò che VEDE l'utente. Per qualche secondo registra, per ogni tracciato che si disegna, il pixel
// assoluto (orologio SFSweep) e il valore effettivamente scritto nella colonna; poi trova i picchi R disegnati sull'ECG e i
// picchi disegnati di ART e pletismografia e scrive nel log (SFLog, «SYNCPROBE»):
//  - ritardo R→picco in ms (dalle colonne disegnate) e in mm sul vetro del tablet (distanza orizzontale sullo schermo);
//  - allineamento: di quanti punti la colonna dello stesso istante è spostata fra ECG e ART/pleth (0 = cursori allineati);
//  - velocità di ogni traccia in punti/s e mm/s sul vetro.
// Uso: adb shell am start -a android.intent.action.VIEW -d "simulafranz-monitor://syncprobe?s=12&tag=icu60"
package it.simulafranz.monitor

import it.simulafranz.core.SFLog
import it.simulafranz.core.SFSweep
import it.simulafranz.uk.*
import java.util.Locale
import kotlin.math.floor

object SFSyncProbe {
    @Volatile var on = false
        private set
    private const val N = 4096

    private class Buf { val pix = LongArray(N); val v = FloatArray(N); var n = 0 }
    private val bufs = LinkedHashMap<TraceView, Buf>()

    fun record(tv: TraceView, p: Long, v: Double) {
        val b = bufs.getOrPut(tv) { Buf() }
        if (b.n >= N) return
        b.pix[b.n] = p; b.v[b.n] = v.toFloat(); b.n++
    }

    fun start(seconds: Double, tag: String, layout: String) {
        bufs.clear(); on = true
        SFLog.log("SYNCPROBE avvio tag=$tag layout=$layout ${seconds}s")
        Uk.after(seconds) { on = false; try { analyze(tag, layout) } catch (e: Throwable) { SFLog.log("SYNCPROBE errore $e") }; bufs.clear() }
    }

    private fun kind(t: TraceView): String = when {
        t.filled || t.autoScale == 4 -> "co2"
        t.autoScale == 1 -> "pleth"
        t.autoScale == 3 || (t.label ?: "").contains("ART") || (t.minValue >= 0 && t.maxValue >= 100) -> "art"
        t.markerSampler != null || t.edgeArtifacts -> "ecg"
        else -> "altro"
    }

    private fun originX(v: UIView): Double { var x = 0.0; var c: UIView? = v; while (c != null) { x += c.frame.x; c = c.superview }; return x }
    private fun originY(v: UIView): Double { var y = 0.0; var c: UIView? = v; while (c != null) { y += c.frame.y; c = c.superview }; return y }

    /** picchi locali (indice nel buffer): massimo entro ±win pixel e sopra la soglia */
    private fun peaks(b: Buf, win: Int, thr: Double): List<Int> {
        val out = ArrayList<Int>()
        var i = 0
        while (i < b.n) {
            val v = b.v[i]
            if (v >= thr) {
                var best = true
                for (k in maxOf(0, i - win)..minOf(b.n - 1, i + win)) if (b.v[k] > v || (b.v[k] == v && k < i)) { best = false; break }
                if (best) { out.add(i); i += win; continue }
            }
            i++
        }
        return out
    }

    private fun analyze(tag: String, layout: String) {
        val mmPt = 1.0 / SFPtPerMm()
        val traces = bufs.keys.filter { bufs[it]!!.n > 50 }
        val desc = traces.joinToString(" ") { t ->
            String.format(Locale.US, "%s(x=%.0f,w=%d,v=%.1fpt/s=%.2fmm/s)", kind(t), originX(t), t.columns, t.sweepSpeed, t.sweepSpeed * mmPt)
        }
        SFLog.log("SYNCPROBE $tag layout=$layout tracce: $desc")
        val ecg = traces.filter { kind(it) == "ecg" }.minByOrNull { originY(it) } ?: run { SFLog.log("SYNCPROBE $tag nessun ECG"); return }
        val eb = bufs[ecg]!!; val ev = ecg.sweepSpeed
        var mn = Double.MAX_VALUE; var mx = -Double.MAX_VALUE
        for (i in 0 until eb.n) { mn = minOf(mn, eb.v[i].toDouble()); mx = maxOf(mx, eb.v[i].toDouble()) }
        val rIdx = peaks(eb, (0.20 * ev).toInt(), mn + 0.6 * (mx - mn))
        val rT = rIdx.map { eb.pix[it] / ev }
        val rr = if (rT.size > 1) (rT.last() - rT.first()) / (rT.size - 1) else 1.0
        SFLog.log(String.format(Locale.US, "SYNCPROBE %s R disegnati %d, RR medio %.0f ms (FC %.0f)", tag, rT.size, rr * 1000, 60 / rr))
        for (kd in listOf("art", "pleth")) {
            for (t in traces.filter { kind(it) == kd }) {
                val b = bufs[t]!!; val v = t.sweepSpeed
                var lo = Double.MAX_VALUE; var hi = -Double.MAX_VALUE
                for (i in 0 until b.n) { lo = minOf(lo, b.v[i].toDouble()); hi = maxOf(hi, b.v[i].toDouble()) }
                val pk = peaks(b, (0.06 * v).toInt().coerceAtLeast(2), lo + 0.5 * (hi - lo)).map { b.pix[it] }
                val w0 = if (kd == "art") 0.12 else 0.25
                val dts = ArrayList<Double>(); val dxs = ArrayList<Double>(); val als = ArrayList<Double>()
                val ox = originX(t); val oe = originX(ecg)
                for ((j, tr) in rT.withIndex()) {
                    val p = pk.firstOrNull { val tp = it / v; tp >= tr + w0 && tp < tr + w0 + rr } ?: continue
                    val dt = p / v - tr
                    // distanza orizzontale sullo schermo fra la colonna del picco R (ECG) e quella del picco (questa traccia)
                    val xr = oe + SFSweep.colOf(eb.pix[rIdx[j]], ecg.columns); val xp = ox + SFSweep.colOf(p, t.columns)
                    var dx = xp - xr; if (dx < -t.columns / 2.0) dx += t.columns
                    // allineamento: colonna dello STESSO istante del picco R sulle due tracce
                    val xs = ox + SFSweep.colOf(floor(tr * v).toLong(), t.columns)
                    var al = xs - xr; if (al > t.columns / 2.0) al -= t.columns; if (al < -t.columns / 2.0) al += t.columns
                    dts.add(dt * 1000); dxs.add(dx * mmPt); als.add(al)
                }
                if (dts.isEmpty()) { SFLog.log("SYNCPROBE $tag $kd: nessuna coppia R-picco"); continue }
                SFLog.log(String.format(Locale.US,
                    "SYNCPROBE %s %s R→picco %.0f ms (%.0f-%.0f, n=%d) = %.1f mm sul vetro (%.1f-%.1f); colonna stesso istante vs ECG %.1f pt (%.0f..%.0f); v ECG %.1f / %s %.1f pt/s",
                    tag, kd, dts.average(), dts.min(), dts.max(), dts.size, dxs.average(), dxs.min(), dxs.max(),
                    als.average(), als.min(), als.max(), ev, kd, v))
            }
        }
    }
}
