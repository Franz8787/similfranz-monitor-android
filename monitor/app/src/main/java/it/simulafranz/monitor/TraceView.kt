// Portato da src/TraceView.m (monitor iPad)
// SimulaFranz_Monitor — vista tracciato a "sweep" (come un monitor reale):
// il cursore avanza da sinistra a destra, cancellando una piccola fascia davanti.
package it.simulafranz.monitor

import android.graphics.Paint
import android.graphics.Path
import android.text.TextPaint
import it.simulafranz.core.SFSweep
import it.simulafranz.uk.*
import java.util.Locale
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

private const val kSubsamples = 3     // sottocampioni per pixel (non perdere i picchi QRS)
private const val kEraseGap = 14      // pixel cancellati davanti al cursore

// ANDROID (audit 26/11, PERF-F6): campionatori con parametri primitivi. Un tipo funzione (Double) -> Double di Kotlin passa da
// Function1<Double, Double> e alloca due Double a ogni chiamata (~5000 oggetti al secondo nel disegno); con un'interfaccia
// funzionale le lambda passate a trace(…) { t -> … } restano uguali (conversione SAM), negli assegnamenti si scrive
// DoubleSampler { t -> … } / MarkerSampler { … }, e la chiamata sampler(x) non cambia (operator invoke).
fun interface DoubleSampler { operator fun invoke(t: Double): Double }
fun interface MarkerSampler { operator fun invoke(t0: Double, t1: Double): Int }

class TraceView(frame: CGRect) : UIView(frame) {
    var traceColor: UIColor = UIColor.greenColor
    var label: String? = null             // es. "II", "Pleth", "CO2"
    var minValue: Double = -1.0           // scala verticale
    // Cambio di scala (es. ART): ridisegno completo, solo se il valore cambia davvero.
    var maxValue: Double = 1.0
        set(v) { if (v != field) { field = v; setNeedsDisplay() } }
    // ANDROID (sincronia polso 22/11): orologio assoluto del tracciato (SFSweep): colonna = istante, uguale per tutte le tracce
    var sweepSpeed: Double = 100.0        // punti al secondo
        set(v) { field = v; sweep.setSpeed(v) }
    private val sweep = SFSweep(100.0)
    private var epochSeen = SFSweep.epoch       // ANDROID (onde 22/11): origine comune vista all'ultimo azzeramento
    /** ANDROID (ritardo R→polso 22/11): larghezza in colonne (sonda di sincronia) */
    internal val columns: Int get() = _width
    // Le scritte sovrapposte cambiano raramente: in quel caso ridisegno tutto.
    var flatWhenNoSignal: Boolean = false // se true, mostra la scritta noSignalText
        set(v) { if (v != field) { field = v; setNeedsDisplay() } }
    var noSignalText: String? = null      // es. "SONDA SCOLLEGATA"
        set(t) { if (t == field) return; field = t; setNeedsDisplay() }
    // Sensore scollegato: linea tratteggiata ferma sulla linea di base (come i monitor reali)
    var disconnected: Boolean = false
        set(d) {
            if (d == field) return
            field = d
            ensureBuffer()
            // come un monitor vero: niente cancellazione, il cursore prosegue e sovrascrive (onda vecchia visibile a destra)
            edgeT = lastT; edgeKind = if (edgeArtifacts) (if (d) 1 else 2) else 0
            if (rng == 0) rng = java.util.Random().nextInt() or 1
            setNeedsDisplay()
        }
    // Fronti realistici (tracciato ECG): allo stacco dell'elettrodo uno scatto al bordo del canale e poi il tratteggio;
    // al riattacco la linea di base oscilla e si assesta (~1.5 s). Il cursore continua a scorrere in entrambi i casi.
    var edgeArtifacts: Boolean = false
    var baselineValue: Double = 0.0       // valore della linea di base (default 0)
    var showScaleLabels: Boolean = false  // scrive i valori di inizio/fine scala sul tracciato
    var markerYOffset: Double = 0.0       // sposta in basso i triangoli SYNC (se sopra c'è l'etichetta della derivazione)
    var filled: Boolean = false           // area sotto la curva colorata (es. capnogramma)
    var fillAlpha: Double = 0.45
    // Scala automatica: 0 = fissa, 1 = continua (pleth), 2 = scale standard da 0,
    // 3 = "ottimale" per pressioni (attorno a sist/diast, arrotondata a 10 mmHg, come i monitor di TI)
    var autoScale: Int = 0
    private var co2Base = 0.0; private var co2LowSince = 0.0   // ANDROID (OP-01): autoScale = 4 (capnogramma)
    var minSpan: Double = 0.0             // ampiezza minima della scala continua
    var sampler: DoubleSampler? = null
    // Marcatori opzionali per colonna (bit 1 = sync sull'onda R, bit 2 = spike del pacemaker)
    var markerSampler: MarkerSampler? = null

    private var values: FloatArray? = null     // un valore per colonna (NaN = vuoto)
    private var marks = ByteArray(0)           // marcatori per colonna
    private var dash = BooleanArray(0)         // colonna disegnata tratteggiata (sensore staccato quando il cursore ci è passato)
    private var edgeT = 0.0                    // istante dell'ultimo stacco / riattacco
    private var edgeKind = 0                   // 0 nessuno, 1 stacco, 2 riattacco
    private var rng = 0
    private var _width = 0
    private var cursor = 0
    private var lastT = 0.0
    private var lastScaleCheck = 0.0
    private var hasTime = false

    // Android: oggetti di disegno riusati (nessuna allocazione per fotogramma)
    private var lines = FloatArray(0)          // segmenti x0,y0,x1,y1 per drawLines
    private val fillP = Paint().apply { style = Paint.Style.FILL }
    private val strokeP = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val path = android.graphics.Path()
    private val textP = TextPaint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG)
    private val fm = Paint.FontMetrics()
    private val tri = Path()
    private var hiS = ""; private var loS = ""; private var hiV = Double.NaN; private var loVc = Double.NaN
    private var nsText: String? = null; private var nsW15 = 0.0; private var nsH15 = 0.0; private var nsW16 = 0.0

    init {
        opaque = true
        backgroundColor = UIColor.blackColor
        // contentMode = UIViewContentModeRedraw: su Android la vista si ridisegna sempre per intero
    }

    private fun noise(): Double { rng = rng * 1664525 + 1013904223; return ((rng ushr 8) / 8388608.0) - 1.0 }

    private fun ensureBuffer() {
        val w = floor(bounds.width).toInt()
        if (w == _width && values != null) return
        _width = max(w, 1)
        values = FloatArray(_width) { Float.NaN }
        marks = ByteArray(_width)
        dash = BooleanArray(_width)
        lines = FloatArray(4 * _width + 8)
        cursor = if (sweep.lastPix == Long.MIN_VALUE) 0 else SFSweep.colOf(sweep.lastPix + 1, _width)
    }

    override fun layoutSubviews() { super.layoutSubviews(); ensureBuffer() }

    fun resetAtTime(t: Double) {
        ensureBuffer()
        val values = values!!
        for (i in 0 until _width) { values[i] = Float.NaN; marks[i] = 0; dash[i] = false }
        // ANDROID (sincronia polso 22/11): il cursore resta alla colonna dell'istante t (non torna a sinistra): un tracciato
        // azzerato da solo (cambio di derivazione, piastre) resta allineato agli altri
        epochSeen = SFSweep.epoch
        sweep.reset(t); cursor = SFSweep.colOf(sweep.lastPix + 1, _width)
        lastT = t; hasTime = true; edgeKind = 0
        setNeedsDisplay()
    }

    // Avanza fino al tempo t, campionando "sampler". Ritorna true se ridisegnare.
    fun advanceTo(t: Double): Boolean {
        ensureBuffer()
        // ANDROID (onde 22/11): origine comune cambiata (accensione, cambio di layout, ripresa): la traccia riparte pulita dal
        // bordo sinistro insieme a tutte le altre, non dalla colonna in cui si trovava il cursore
        if (hasTime && epochSeen != SFSweep.epoch) { resetAtTime(t); return true }
        if (!hasTime) { epochSeen = SFSweep.epoch; sweep.reset(t); cursor = SFSweep.colOf(sweep.lastPix + 1, _width); lastT = t; hasTime = true; return false }
        // ANDROID (sincronia polso 22/11): pixel nuovi dall'orologio assoluto (dopo una pausa lunga solo l'ultimo secondo, come
        // prima; le colonne saltate si svuotano: niente pezzi di curva di un altro istante)
        val n = sweep.advance(t)
        if (n <= 0) return false
        val values = values!!
        val p0 = sweep.lastPix - n + 1
        if (sweep.skipped > 0) {
            val k = min(sweep.skipped, _width.toLong()).toInt()
            for (g in 0 until k) { val j = SFSweep.colOf(p0 - 1 - g, _width); values[j] = Float.NaN; marks[j] = 0; dash[j] = false }
        }
        cursor = SFSweep.colOf(p0, _width)
        val sampler = sampler; val markerSampler = markerSampler
        val secPerPx = 1.0 / sweepSpeed
        var tPix = sweep.timeOf(p0)
        for (i in 0 until n) {
            tPix = sweep.timeOf(p0 + i)
            // tra i sottocampioni tengo quello più lontano dalla linea di base (picchi visibili)
            var best = 0.0; var bestAbs = -1.0
            for (k in 1..kSubsamples) {
                val v = if (sampler != null) sampler(tPix + secPerPx * k / kSubsamples) else 0.0
                if (abs(v) > bestAbs) { bestAbs = abs(v); best = v }
            }
            marks[cursor] = if (markerSampler != null && !disconnected) markerSampler(tPix, tPix + secPerPx).toByte() else 0
            tPix += secPerPx
            val range = maxValue - minValue; val de = tPix - edgeT
            var dsh = false
            if (disconnected) {
                if (edgeKind == 1 && de < 0.45) {          // elettrodo che si stacca: scatto al bordo, poi ricade
                    val a = if (de < 0.10) 1.0 else exp(-(de - 0.10) / 0.07)
                    best = baselineValue + range * 0.92 * a + range * 0.03 * noise()
                } else { best = baselineValue; dsh = true }
            } else if (edgeKind == 2 && de < 1.8) {        // riattacco: linea di base che oscilla e si assesta
                best += range * (-0.45 * exp(-de / 0.35) * cos(de * 5.0)) + range * 0.06 * exp(-de / 0.6) * noise()
            }
            values[cursor] = best.toFloat()
            if (SFSyncProbe.on) SFSyncProbe.record(this, p0 + i, best)   // ANDROID (ritardo R→polso 22/11): sonda di debug
            dash[cursor] = dsh
            cursor = (cursor + 1) % _width
            for (g in 0 until kEraseGap) { val j = (cursor + g) % _width; values[j] = Float.NaN; marks[j] = 0; dash[j] = false }
        }
        lastT = tPix
        if (autoScale != 0 && !disconnected && t - lastScaleCheck > 0.5) { lastScaleCheck = t; rescaleIfNeeded() }
        // iPad: ridisegno PARZIALE delle sole colonne cambiate. Su Android (disegno accelerato) la vista
        // viene comunque ri-registrata per intero: si invalida tutta, il disegno usa drawLines (GPU).
        setNeedsDisplay()
        return true
    }

    // Riadatta la scala ai valori visibili (ultimo giro di sweep) quando escono dalla scala
    // o la occupano troppo poco: i valori sono memorizzati grezzi, quindi basta ridisegnare.
    private fun rescaleIfNeeded() {
        val values = values ?: return
        var mn = Double.MAX_VALUE; var mx = -Double.MAX_VALUE
        for (i in 0 until _width) { val v = values[i]; if (!v.isNaN() && !dash[i]) { mn = min(mn, v.toDouble()); mx = max(mx, v.toDouble()) } }
        if (mn > mx || !mn.isFinite() || !mx.isFinite()) return
        if (autoScale == 3) {
            var lo = floor((mn - 12) / 10.0) * 10.0; var hi = ceil((mx + 12) / 10.0) * 10.0
            lo = max(0.0, lo); if (hi - lo < 40) hi = lo + 40
            // cambia scala solo se serve davvero (onda fuori scala o troppo schiacciata): niente "saltellamenti"
            val out = mx > maxValue || mn < minValue
            val loose = (mx - mn) < (maxValue - minValue) * 0.45
            if (out || loose || minValue == 0.0) {
                if (lo != minValue || hi != maxValue) { minValue = lo; maxValue = hi; setNeedsDisplay() }
            }
            return
        }
        if (autoScale == 4) {
            // ANDROID (difetti clinici 22/11, OP-01): capnogramma: scala del layout (50/60 mmHg) e, se serve, 75/100/150 come i
            // capnografi veri (mai una curva tagliata in silenzio): sale appena il picco supera il 92% della scala, torna giù
            // quando per più di 10 s il picco resta sotto il 40% della scala inferiore
            if (co2Base <= 0) co2Base = maxValue
            val steps = listOf(co2Base) + listOf(75.0, 100.0, 150.0).filter { it > co2Base }
            var top = steps.last()
            for (st in steps) if (mx <= st * 0.92) { top = st; break }
            val now = CACurrentMediaTime()
            if (top > maxValue) { maxValue = top; co2LowSince = 0.0; setNeedsDisplay() }
            else if (top < maxValue) {
                val lower = steps.lastOrNull { it < maxValue } ?: co2Base
                if (mx < lower * 0.40 || top <= lower) {
                    if (co2LowSince <= 0) co2LowSince = now
                    else if (now - co2LowSince > 10) { maxValue = top; co2LowSince = 0.0; setNeedsDisplay() }
                } else co2LowSince = 0.0
            } else co2LowSince = 0.0
            return
        }
        if (autoScale == 2) {
            var top = 300.0
            for (i in 0 until 5) if (mx <= scales[i] * 0.92) { top = scales[i]; break }
            if (top != maxValue || minValue != 0.0) { minValue = 0.0; maxValue = top; setNeedsDisplay() }
            return
        }
        val span = max(mx - mn, if (minSpan > 0) minSpan else 0.1)
        val cur = maxValue - minValue
        val outOfRange = mx > maxValue || mn < minValue
        val tooSmall = span < cur * 0.55                       // tracciato troppo schiacciato
        if (outOfRange || tooSmall) {
            val pad = span * 0.12
            minValue = mn - pad; maxValue = mn - pad + span + 2 * pad
            setNeedsDisplay()
        }
    }

    // ANDROID (audit 26/11, PERF-F9): carattere e metriche si chiedono al nativo solo quando il carattere cambia (prima a ogni disegno)
    private var fontNow: UIFont? = null
    private fun setFont(f: UIFont, argb: Int) {
        if (f !== fontNow) { textP.typeface = f.typeface; textP.textSize = f.pointSize.toFloat(); textP.getFontMetrics(fm); fontNow = f }
        textP.color = argb
    }
    private fun withAlpha(argb: Int, a: Double): Int = (argb and 0x00FFFFFF) or (((a * 255.0 + 0.5).toInt() and 0xFF) shl 24)
    private fun intersects(r: CGRect, x: Double, y: Double, w: Double, h: Double) = r.x < x + w && x < r.x + r.width && r.y < y + h && y < r.y + r.height
    private fun measureNoSignal(t: String) {
        if (t == nsText) return
        nsText = t
        setFont(F15B, 0); nsW15 = ceil(textP.measureText(t).toDouble()); nsH15 = ceil((fm.descent - fm.ascent).toDouble())
        setFont(F16B, 0); nsW16 = ceil(textP.measureText(t).toDouble())
    }

    override fun drawRect(ctx: CGContext, rect: CGRect) {
        val c = ctx.canvas
        val bw = frame.width; val bh = frame.height
        val color = traceColor.argb
        fillP.color = BLACK
        c.drawRect(rect.x.toFloat(), rect.y.toFloat(), (rect.x + rect.width).toFloat(), (rect.y + rect.height).toFloat(), fillP)

        // etichetta del canale (solo se l'area da ridisegnare la tocca)
        val label = label
        if (!label.isNullOrEmpty() && intersects(rect, 0.0, 0.0, 140.0, 20.0)) {
            setFont(F13B, color)
            c.drawText(label, 6f, 2f - fm.ascent, textP)
        }
        val values = values ?: return

        var range = maxValue - minValue
        if (range <= 0) range = 1.0
        val top = 18.0; val h = bh - top - 4

        val x0 = max(0, floor(rect.minX).toInt() - 2)
        val x1 = min(_width, ceil(rect.maxX).toInt() + 2)

        // riempimento sotto la curva: una colonna verticale per pixel, dalla linea di base al valore
        if (filled && !disconnected) {
            val yBase = top + h * (1.0 - max(-0.05, min(1.05, (baselineValue - minValue) / range)))
            strokeP.color = withAlpha(color, fillAlpha)
            strokeP.strokeWidth = 1f; strokeP.strokeCap = Paint.Cap.BUTT
            var n = 0
            for (x in x0 until x1) {
                val v = values[x]
                if (v.isNaN() || dash[x]) continue
                val yv = top + h * (1.0 - max(-0.05, min(1.05, (v - minValue) / range)))
                if (yBase - yv < 1) continue
                lines[n++] = x + 0.5f; lines[n++] = yBase.toFloat(); lines[n++] = x + 0.5f; lines[n++] = yv.toFloat()
            }
            if (n > 0) c.drawLines(lines, 0, n, strokeP)
        }

        strokeP.color = color
        strokeP.strokeWidth = 2f
        // sensore scollegato: il tracciato continua a scorrere ma TRATTEGGIATO (9 px pieni, 7 vuoti),
        // con i tratti ancorati alla x assoluta: allineati anche nei ridisegni parziali
        // (Android: un segmento per colonna con estremi tondi = giunzioni tonde, disegnati dalla GPU con drawLines)
        strokeP.strokeJoin = Paint.Join.ROUND
        strokeP.strokeCap = Paint.Cap.ROUND
        val mode = UkProfile.traceMode
        if (mode == 2) strokeP.strokeCap = Paint.Cap.SQUARE
        var penDown = false
        var n = 0; var px = 0f; var py = 0f
        if (mode == 1) path.reset()
        for (x in x0 until x1) {
            if (mode == 3) break
            val v = values[x]
            if (v.isNaN() || (dash[x] && (x % 16) >= 9)) { penDown = false; continue }
            if (x > 0 && dash[x] != dash[x - 1]) penDown = false
            var norm = (v - minValue) / range
            norm = max(-0.05, min(1.05, norm))
            val y = (top + h * (1.0 - norm)).toFloat()
            if (mode == 1) { if (penDown) path.lineTo(x.toFloat(), y) else path.moveTo(x.toFloat(), y) }
            else if (penDown) { lines[n++] = px; lines[n++] = py; lines[n++] = x.toFloat(); lines[n++] = y }
            px = x.toFloat(); py = y; penDown = true
        }
        if (mode == 1) c.drawPath(path, strokeP)
        else if (n > 0) c.drawLines(lines, 0, n, strokeP)

        // marcatori: spike del pacemaker (linea verticale) e sync (triangolo bianco sopra l'onda R)
        for (x in x0 until x1) {
            val m = marks[x].toInt()
            if (m == 0) continue
            if (m and 2 != 0) {
                strokeP.color = WHITE
                strokeP.strokeWidth = 1.5f
                c.drawLine(x + 0.5f, (top + h * 0.25).toFloat(), x + 0.5f, (top + h * 0.80).toFloat(), strokeP)
            }
            if (m and 1 != 0) {
                fillP.color = WHITE; fillP.isAntiAlias = true
                val my = (top + markerYOffset).toFloat()
                tri.rewind()
                tri.moveTo(x - 5f, my - 14f); tri.lineTo(x + 5f, my - 14f)
                tri.lineTo(x.toFloat(), my - 5f); tri.close(); c.drawPath(tri, fillP)
                fillP.isAntiAlias = false
            }
        }

        if (showScaleLabels && !disconnected) {
            // il margine sotto lo zero (es. -1 per staccare la linea di base dal bordo) si scrive come 0
            val loV = if (minValue < 0 && minValue > -5) 0.0 else minValue
            if (maxValue != hiV) { hiV = maxValue; hiS = String.format(Locale.US, "%.0f", maxValue) }
            if (loV != loVc) { loVc = loV; loS = String.format(Locale.US, "%.0f", loV) }
            val yHi = top.toFloat(); val yLo = (top + h).toFloat()
            // linee tratteggiate sottili ai bordi della scala (3 pieni, 5 vuoti)
            // ANDROID (audit 26/11, PERF-F7): stessi trattini (3 pieni, 5 vuoti, stesse x e y) in un solo drawLines, ma a filo
            // sottile (strokeWidth 0 = un pixel): la GPU li disegna in un colpo; con 1 pt e antialias Skia faceva un percorso
            // per ogni trattino, a ogni frame
            strokeP.color = withAlpha(color, 0.25)
            strokeP.strokeWidth = 0f; strokeP.strokeCap = Paint.Cap.BUTT
            val xa = max(x0, 0).toFloat(); val xb = min(x1.toDouble(), bw - 38).toFloat()
            var k = 0; var xx = xa
            while (xx < xb && k + 8 <= lines.size) {
                val xe = min(xx + 3f, xb)
                lines[k++] = xx; lines[k++] = yHi; lines[k++] = xe; lines[k++] = yHi
                lines[k++] = xx; lines[k++] = yLo; lines[k++] = xe; lines[k++] = yLo
                xx += 8f
            }
            if (k > 0) c.drawLines(lines, 0, k, strokeP)
            setFont(F11, withAlpha(color, 0.7))
            if (intersects(rect, bw - 34, yHi - 1.0, 32.0, 13.0)) c.drawText(hiS, (bw - 34).toFloat(), yHi - 1f - fm.ascent, textP)
            if (intersects(rect, bw - 34, yLo - 13.0, 32.0, 13.0)) c.drawText(loS, (bw - 34).toFloat(), yLo - 13f - fm.ascent, textP)
        }

        val ns = noSignalText
        if (disconnected && !ns.isNullOrEmpty()) {
            measureNoSignal(ns)
            val tx = (bw - nsW15) / 2; val ty = top + 4
            if (intersects(rect, tx - 10, ty - 4, nsW15 + 20, nsH15 + 8)) {
                fillP.color = BLACK
                c.drawRect((tx - 8).toFloat(), (ty - 2).toFloat(), (tx + nsW15 + 8).toFloat(), (ty + nsH15 + 2).toFloat(), fillP)
                setFont(F15B, color)
                c.drawText(ns, tx.toFloat(), ty.toFloat() - fm.ascent, textP)
            }
            return
        }

        if (flatWhenNoSignal && !ns.isNullOrEmpty()) {
            measureNoSignal(ns)
            setFont(F16B, color)
            c.drawText(ns, ((bw - nsW16) / 2).toFloat(), (top + 8).toFloat() - fm.ascent, textP)
        }
    }

    companion object {
        private val scales = doubleArrayOf(100.0, 150.0, 200.0, 250.0, 300.0)
        private val F13B = UIFont.boldSystemFontOfSize(13)
        private val F11 = UIFont.systemFontOfSize(11)
        private val F15B = UIFont.boldSystemFontOfSize(15)
        private val F16B = UIFont.boldSystemFontOfSize(16)
        private const val BLACK = 0xFF000000.toInt()
        private const val WHITE = 0xFFFFFFFF.toInt()
    }
}
