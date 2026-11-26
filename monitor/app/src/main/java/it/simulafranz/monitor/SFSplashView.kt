// Portato da src/SFSplashView.m (monitor iPad)
// similFranz Monitor — schermata di avvio: sfondo scuro con reticolo, tracce che scorrono (ECG, pletismografia, capnogramma), titolo,
// barra di caricamento che avanza con il caricamento VERO delle risorse (una fase per volta).
// La versione "statica" (senza traccia né barra) serve a generare l'immagine di avvio:
//   simulafranz-monitor://splash?static=1  ->  shot?name=launch
// ANDROID: le animazioni CoreAnimation (CABasicAnimation / CAKeyframeAnimation / CAAnimationGroup) sono `UkAnimation` a tempo
// con gli stessi tempi (sweep 6,4 s, riflesso 2,6 s, barra 1 s). La maschera sfumata delle tracce e l'alone non esistono in `uk`:
// li disegnano due viste private di questo file (SFSplashTraces, SFSplashBar) leggendo gli stessi strati dell'originale.
package it.simulafranz.monitor

import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import android.graphics.Shader
import it.simulafranz.uk.*
import java.util.Locale
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

private fun C(r: Double, g: Double, b: Double, a: Double): UIColor = UIColor.colorWithRed(r, g, b, a)
private val SP_GREEN = C(0.24, 1.00, 0.43, 1.0)
private val SP_CYAN = C(0.24, 0.86, 1.00, 1.0)
private const val kBarW = 420.0
private const val kBarH = 6.0
private const val kBarY = 620.0
private const val kBase = 284.0      // kBase = centro della zona delle tracce

private fun Gauss(x: Double, c: Double, w: Double): Double { val d = (x - c) / w; return exp(-d * d) }

/** fmod del C (stesso segno del dividendo) */
private fun fmod(a: Double, b: Double): Double = a % b

/** Punti di una traccia con le lunghezze progressive: per sapere dov'è la testa a una certa frazione del percorso. */
private class SFSplashPoints(val xs: DoubleArray, val ys: DoubleArray) {
    val cum = DoubleArray(xs.size)
    init { for (i in 1 until xs.size) cum[i] = cum[i - 1] + hypot(xs[i] - xs[i - 1], ys[i] - ys[i - 1]) }
    val minY: Double get() = ys.minOrNull() ?: 0.0
    val maxY: Double get() = ys.maxOrNull() ?: 0.0
    /** punto alla frazione f (0..1) della lunghezza: come l'animazione "paced" lungo il percorso */
    fun pointAt(f: Double): CGPoint {
        val n = xs.size; if (n == 0) return CGPoint()
        val d = f.coerceIn(0.0, 1.0) * cum[n - 1]
        var lo = 0; var hi = n - 1
        while (hi - lo > 1) { val m = (lo + hi) / 2; if (cum[m] <= d) lo = m else hi = m }
        if (hi == lo) return CGPoint(xs[lo], ys[lo])
        val seg = cum[hi] - cum[lo]; val k = if (seg > 0) (d - cum[lo]) / seg else 0.0
        return CGPoint(xs[lo] + (xs[hi] - xs[lo]) * k, ys[lo] + (ys[hi] - ys[lo]) * k)
    }
}

/**
 * ANDROID (aiuto privato): disegna le tre tracce con alone, punto luminoso in testa e maschera sfumata ai lati.
 * Ogni traccia intera è preparata una volta sola in un'immagine (alone + maschera già applicati); a ogni fotogramma
 * se ne mostra la parte fino alla testa (strokeEnd) con l'opacità dello strato: costa pochissimo anche sul tablet.
 */
private class SFSplashTraces(frame: CGRect, val lines: List<CAShapeLayer>, val dots: List<CALayer>, val pts: List<SFSplashPoints>, val mask: CAGradientLayer) : UIView(frame) {
    private var lineBmp: Array<Bitmap?>? = null
    private var dotBmp: Array<Bitmap?>? = null
    private val bandTop = DoubleArray(lines.size); private val bandH = DoubleArray(lines.size)
    private val bp = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val dst = RectF()
    private val kDot = 44.0                                  // lato dell'immagine del punto (punto 8 + alone)

    init { userInteractionEnabled = false }

    private fun prepare() {
        val sc = Uk.scale.toDouble(); val W = bounds.width
        val lb = arrayOfNulls<Bitmap>(lines.size); val db = arrayOfNulls<Bitmap>(dots.size)
        for (k in lines.indices) {
            val l = lines[k]; val p = pts[k]; val col = l.strokeColor ?: UIColor.whiteColor
            val pad = l.shadowRadius * 2 + l.lineWidth + 4
            bandTop[k] = floor(p.minY - pad); bandH[k] = ceil(p.maxY + pad) - bandTop[k]
            val bw = max(1, ceil(W * sc).toInt()); val bh = max(1, ceil(bandH[k] * sc).toInt())
            val b = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888); val c = Canvas(b)
            val path = Path()
            for (i in p.xs.indices) {
                val x = (p.xs[i] * sc).toFloat(); val y = ((p.ys[i] - bandTop[k]) * sc).toFloat()
                if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            val sp = Paint(Paint.ANTI_ALIAS_FLAG)
            sp.style = Paint.Style.STROKE; sp.strokeWidth = (l.lineWidth * sc).toFloat()
            sp.strokeJoin = Paint.Join.ROUND; sp.strokeCap = Paint.Cap.ROUND
            // alone leggero (shadowRadius 7, shadowOpacity 0.55, senza spostamento)
            val sh = l.shadowColor ?: col
            sp.color = UIColor(sh.r, sh.g, sh.b, l.shadowOpacity).argb
            sp.maskFilter = BlurMaskFilter((l.shadowRadius * 0.85 * sc).toFloat().coerceAtLeast(0.5f), BlurMaskFilter.Blur.NORMAL)
            c.drawPath(path, sp)
            sp.maskFilter = null; sp.color = col.argb
            c.drawPath(path, sp)
            // maschera: le tracce sfumano verso i bordi dello schermo
            val mp = Paint(Paint.ANTI_ALIAS_FLAG)
            mp.xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN)
            val loc = mask.locations
            mp.shader = LinearGradient((mask.startPoint.x * bw).toFloat(), 0f, (mask.endPoint.x * bw).toFloat(), 0f,
                IntArray(mask.colors.size) { mask.colors[it].argb }, if (loc != null) FloatArray(loc.size) { loc[it].toFloat() } else null, Shader.TileMode.CLAMP)
            c.drawRect(0f, 0f, bw.toFloat(), bh.toFloat(), mp)
            lb[k] = b
        }
        for (k in dots.indices) {
            val d = dots[k]; val n = max(1, ceil(kDot * sc).toInt())
            val b = Bitmap.createBitmap(n, n, Bitmap.Config.ARGB_8888); val c = Canvas(b)
            val r = (d.bounds.width / 2 * sc).toFloat(); val m = n / 2f
            val dp = Paint(Paint.ANTI_ALIAS_FLAG); dp.style = Paint.Style.FILL
            val sh = d.shadowColor ?: UIColor.whiteColor
            dp.color = UIColor(sh.r, sh.g, sh.b, d.shadowOpacity).argb
            dp.maskFilter = BlurMaskFilter((d.shadowRadius * 0.85 * sc).toFloat().coerceAtLeast(0.5f), BlurMaskFilter.Blur.NORMAL)
            c.drawCircle(m, m, r * 1.15f, dp)
            dp.maskFilter = null; dp.color = (d.backgroundColor ?: UIColor.whiteColor).argb
            c.drawCircle(m, m, r, dp)
            db[k] = b
        }
        lineBmp = lb; dotBmp = db
    }

    /** opacità della maschera sfumata alla posizione x (0..1) */
    private fun maskAlpha(x: Double): Double {
        val loc = mask.locations ?: return 1.0
        val u = x / bounds.width
        if (u <= loc[0].toDouble()) return mask.colors[0].a
        for (i in 1 until loc.size) {
            val a = loc[i - 1].toDouble(); val b = loc[i].toDouble()
            if (u <= b) { val k = if (b > a) (u - a) / (b - a) else 1.0; return mask.colors[i - 1].a + (mask.colors[i].a - mask.colors[i - 1].a) * k }
        }
        return mask.colors[loc.size - 1].a
    }

    override fun drawRect(ctx: CGContext, rect: CGRect) {
        if (lineBmp == null) prepare()
        val lb = lineBmp ?: return; val db = dotBmp ?: return
        val c = ctx.canvas; val W = rect.width
        for (k in lines.indices) {
            val l = lines[k]; val b = lb[k] ?: continue
            if (l.hidden || l.opacity <= 0.003 || l.strokeEnd <= 0.0) continue
            val head = pts[k].pointAt(l.strokeEnd)
            c.save()
            if (l.strokeEnd < 1.0) c.clipRect(0f, bandTop[k].toFloat(), (head.x + l.lineWidth / 2).toFloat(), (bandTop[k] + bandH[k]).toFloat())
            bp.alpha = (l.opacity.coerceIn(0.0, 1.0) * 255).toInt()
            dst.set(0f, bandTop[k].toFloat(), W.toFloat(), (bandTop[k] + bandH[k]).toFloat())
            c.drawBitmap(b, null, dst, bp)
            c.restore()
        }
        // punto luminoso in testa alla traccia
        for (k in dots.indices) {
            val d = dots[k]; val b = db[k] ?: continue
            if (d.hidden || d.opacity <= 0.003) continue
            val p = d.position
            val a = d.opacity.coerceIn(0.0, 1.0) * maskAlpha(p.x)
            if (a <= 0.003) continue
            bp.alpha = (a * 255).toInt()
            dst.set((p.x - kDot / 2).toFloat(), (p.y - kDot / 2).toFloat(), (p.x + kDot / 2).toFloat(), (p.y + kDot / 2).toFloat())
            c.drawBitmap(b, null, dst, bp)
        }
    }
}

/**
 * ANDROID (aiuto privato): barra di caricamento. Il binario è la vista stessa (sfondo, angoli e ritaglio sono quelli
 * dello strato `track`); il riempimento sfumato e il riflesso sono ritagliati sulla larghezza di `fill`.
 */
private class SFSplashBar(frame: CGRect, val fill: CALayer, val grad: CAGradientLayer, val shine: CAGradientLayer) : UIView(frame) {
    init { userInteractionEnabled = false }
    override fun drawRect(ctx: CGContext, rect: CGRect) {
        val fw = fill.bounds.width
        if (fw <= 0.01) return
        ctx.saveGState()
        ctx.clip(UIBezierPath.bezierPathWithRoundedRect(CGRectMake(0, 0, fw, fill.bounds.height), fill.cornerRadius))
        val gf = grad.frame
        ctx.drawLinearGradient(grad.colors, null, CGPointMake(gf.x + grad.startPoint.x * gf.width, 0), CGPointMake(gf.x + grad.endPoint.x * gf.width, 0))
        val sf = shine.frame
        if (sf.maxX > 0 && sf.x < fw) {
            ctx.clipToRect(sf)
            ctx.drawLinearGradient(shine.colors, null, CGPointMake(sf.x + shine.startPoint.x * sf.width, 0), CGPointMake(sf.x + shine.endPoint.x * sf.width, 0))
        }
        ctx.restoreGState()
    }
}

class SFSplashView(frame: CGRect, version: String?) : UIView(frame) {
    // ANDROID (tablet): durante la splash le fasce laterali prendono lo stesso sfondo, la splash occupa tutto lo schermo
    private val backdrops = ArrayList<UIView>()
    private fun addBackdrops() {
        if (backdrops.isNotEmpty()) return
        for (band in listOf(SFSideBand.left, SFSideBand.right)) {
            if (band == null || band.bounds.width <= 0) continue
            // ANDROID (21/11): la copertura va nella RADICE sopra la fascia, non dentro la fascia: i layout che aggiungono
            val host = band.superview ?: band
            val v = UIView(if (host === band) band.bounds else band.frame); v.userInteractionEnabled = true      // copre anche i tocchi sulle prese
            val g = CAGradientLayer(); g.frame = v.bounds
            g.colors = listOf(C(0.055, 0.075, 0.095, 1.0), C(0.020, 0.026, 0.034, 1.0)); v.layer.addSublayer(g)
            host.addSubview(v); backdrops.add(v)
        }
    }
    private fun removeBackdrops() { for (v in backdrops) v.removeFromSuperview(); backdrops.clear() }
    override fun setAlpha(a: Float) { super.setAlpha(a); for (v in backdrops) v.alpha = a }
    init {
        addOnAttachStateChangeListener(object : android.view.View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: android.view.View) { addBackdrops() }
            override fun onViewDetachedFromWindow(v: android.view.View) { removeBackdrops() }
        })
    }
    private val traces: CALayer; private val track: CALayer; private val fill: CALayer
    private val lines = ArrayList<CAShapeLayer>(); private val dots = ArrayList<CALayer>()   // ECG, pletismografia, capnogramma
    private val shine: CAGradientLayer
    private val status: UILabel; private val percent: UILabel; private val _version: UILabel
    private var stages: List<List<Any?>> = emptyList(); private var minStage = 0.0; private var done: (() -> Unit)? = null
    private var progress = 0.0
    // ANDROID: viste che disegnano tracce e barra, punti delle tracce, animazioni in corso
    private val tracesView: SFSplashTraces; private val barView: SFSplashBar
    private val points = ArrayList<SFSplashPoints>()
    private var sweep: UkAnimation? = null; private var shineAnim: UkAnimation? = null; private var load: UkAnimation? = null
    private var statusGhost: UILabel? = null

    // Tre tracce come sul monitor: ECG II (verde), pletismografia SpO2 (azzurra, in ritardo sul QRS), capnogramma (giallo)
    private fun pathFor(kind: Int): UIBezierPath {
        val p = UIBezierPath.bezierPath(); val W = bounds.width; val period = 176.0
        val xs = ArrayList<Double>(); val ys = ArrayList<Double>()
        var x = 40.0
        while (x <= W - 40) {
            val t = fmod(x - 40 + period * 0.45, period)          // posizione dentro il battito
            val y: Double
            if (kind == 0) {
                val v = 7 * Gauss(t, 44.0, 9.0) -                                                          // P
                        7 * Gauss(t, 73.0, 2.6) + 66 * Gauss(t, 79.0, 3.0) - 15 * Gauss(t, 85.5, 3.0) +    // QRS
                        14 * Gauss(t, 124.0, 15.0)                                                         // T
                y = 218 - v
            } else if (kind == 1) {
                val u = fmod(t - 79 - 34 + 2 * period, period)    // l'onda di polso arriva dopo il QRS
                val v = 34 * Gauss(u, 22.0, if (u < 22) 9.0 else 17.0) + 9 * Gauss(u, 62.0, 13.0)          // picco sistolico + onda dicrota
                y = 322 - v
            } else {
                val u = fmod(x - 40 + 60, 372.0) / 372            // un atto respiratorio ogni 372 pt
                val up = 1 / (1 + exp(-(u - 0.22) * 90)); val down = 1 / (1 + exp(-(u - 0.60) * 110))
                val v = (30 + 8 * (u - 0.22) / 0.38) * up * (1 - down)                                     // plateau in lieve salita
                y = 410 - v
            }
            if (x == 40.0) p.moveToPoint(CGPointMake(x, y)) else p.addLineToPoint(CGPointMake(x, y))
            xs.add(x); ys.add(y)
            x += 1.5
        }
        points.add(SFSplashPoints(xs.toDoubleArray(), ys.toDoubleArray()))
        return p
    }

    init {
        opaque = true; userInteractionEnabled = true      // durante la splash i tocchi non arrivano al monitor
        val W = frame.width; val H = frame.height
        val cols = listOf(SP_GREEN, SP_CYAN, C(0.95, 0.88, 0.35, 1.0))
        for (k in 0 until 3) {
            val col = cols[k]
            val l = CAShapeLayer.layer(); l.frame = bounds; l.path = pathFor(k).CGPath
            l.strokeColor = col.CGColor; l.fillColor = null; l.lineWidth = if (k == 0) 2.5 else 2.2
            l.lineJoin = kCALineJoinRound; l.lineCap = kCALineCapRound
            l.shadowColor = col.CGColor; l.shadowRadius = 7.0; l.shadowOpacity = 0.55; l.shadowOffset = CGSizeZero   // alone leggero
            l.strokeEnd = 0.0
            lines.add(l)
            // punto luminoso in testa alla traccia
            val d = CALayer.layer(); d.bounds = CGRectMake(0, 0, 8, 8); d.cornerRadius = 4.0
            d.backgroundColor = C(0.95, 1.0, 0.97, 1.0).CGColor
            d.shadowColor = col.CGColor; d.shadowRadius = 8.0; d.shadowOpacity = 1.0; d.shadowOffset = CGSizeZero
            d.opacity = 0.0
            dots.add(d)
        }
        val mask = CAGradientLayer.layer(); mask.frame = bounds
        mask.startPoint = CGPointMake(0, 0.5); mask.endPoint = CGPointMake(1, 0.5)
        mask.colors = listOf(C(0.0, 0.0, 0.0, 0.0).CGColor, C(0.0, 0.0, 0.0, 1.0).CGColor, C(0.0, 0.0, 0.0, 1.0).CGColor, C(0.0, 0.0, 0.0, 0.0).CGColor)
        mask.locations = listOf(0.05, 0.20, 0.80, 0.95)
        // ANDROID: _traces è lo strato della vista che disegna tracce, punti e maschera (in `uk` gli strati non hanno `mask`)
        tracesView = SFSplashTraces(bounds, lines, dots, points, mask)
        traces = tracesView.layer; traces.frame = bounds
        addSubview(tracesView)

        // barra di caricamento: binario sottile, riempimento sfumato (verde -> azzurro) con un riflesso che scorre
        fill = CALayer.layer(); fill.frame = CGRectMake(0, 0, 0, kBarH)      // (anchorPoint 0,0: qui la larghezza cresce già da sinistra)
        fill.masksToBounds = true; fill.cornerRadius = kBarH / 2
        val grad = CAGradientLayer.layer(); grad.frame = CGRectMake(0, 0, kBarW, kBarH)
        grad.startPoint = CGPointMake(0, 0.5); grad.endPoint = CGPointMake(1, 0.5)
        grad.colors = listOf(SP_GREEN.CGColor, SP_CYAN.CGColor)
        shine = CAGradientLayer.layer(); shine.frame = CGRectMake(-90, 0, 90, kBarH)
        shine.startPoint = CGPointMake(0, 0.5); shine.endPoint = CGPointMake(1, 0.5)
        shine.colors = listOf(C(1.0, 1.0, 1.0, 0.0).CGColor, C(1.0, 1.0, 1.0, 0.75).CGColor, C(1.0, 1.0, 1.0, 0.0).CGColor)
        // ANDROID: _track è lo strato della vista che disegna la barra (riempimento e riflesso ritagliati su `fill`)
        barView = SFSplashBar(CGRectMake((W - kBarW) / 2, kBarY, kBarW, kBarH), fill, grad, shine)
        track = barView.layer; track.frame = CGRectMake((W - kBarW) / 2, kBarY, kBarW, kBarH)
        track.backgroundColor = C(1.0, 1.0, 1.0, 0.10).CGColor; track.cornerRadius = kBarH / 2; track.masksToBounds = true
        addSubview(barView)

        val num = UIFont.fontWithName("AvenirNext-Medium", 15)
        status = UILabel(CGRectMake((W - kBarW) / 2, kBarY + 14, kBarW - 60, 24))
        status.font = UIFont.fontWithName("AvenirNext-Regular", 15)
        status.textColor = C(0.62, 0.66, 0.70, 1.0); status.backgroundColor = UIColor.clearColor
        addSubview(status)
        percent = UILabel(CGRectMake((W + kBarW) / 2 - 60, kBarY + 14, 60, 24))
        percent.font = num; percent.textAlignment = NSTextAlignmentRight
        percent.textColor = C(0.80, 0.84, 0.88, 1.0); percent.backgroundColor = UIColor.clearColor
        addSubview(percent)
        _version = UILabel(CGRectMake(W - 220, H - 34, 204, 20))
        _version.textAlignment = NSTextAlignmentRight; _version.font = UIFont.systemFontOfSize(13)
        _version.textColor = C(0.40, 0.42, 0.45, 1.0); _version.backgroundColor = UIColor.clearColor
        _version.text = if (!version.isNullOrEmpty()) "versione $version" else ""
        addSubview(_version)
    }

    /** ANDROID: testo centrato su una riga con spaziatura tra le lettere (NSKernAttributeName), y = bordo alto della riga */
    private fun drawCentered(g: CGContext, s: String, y: Double, W: Double, font: UIFont, color: UIColor, kern: Double) {
        val tp = UkText.paint(font, color)
        tp.letterSpacing = (kern / font.pointSize).toFloat()
        val w = tp.measureText(s)
        g.canvas.drawText(s, ((W - w) / 2).toFloat(), (y - tp.fontMetrics.ascent).toFloat(), tp)
        tp.letterSpacing = 0f
    }


    override fun drawRect(ctx: CGContext, rect: CGRect) {
        val g = ctx; val b = bounds; val W = b.width; val H = b.height
        g.clipToRect(b)
        // sfondo: sfumatura verticale + luce morbida dietro alla traccia
        g.drawLinearGradient(listOf(C(0.055, 0.075, 0.095, 1.0), C(0.020, 0.026, 0.034, 1.0)), null, CGPointMake(0, 0), CGPointMake(0, H))
        g.drawRadialGradient(listOf(C(0.10, 0.50, 0.36, 0.20), C(0.10, 0.50, 0.36, 0.0)), null, CGPointMake(W / 2, kBase), 540)
        // reticolo da monitor appena accennato, che sfuma verso i bordi
        val gy0 = 140.0; val gy1 = 428.0; val st = 32.0; val cy = (gy0 + gy1) / 2; val hh = (gy1 - gy0) / 2
        var x = 0.0
        while (x <= W) {
            var y = gy0
            while (y < gy1) {
                val fx = max(0.0, 1 - (abs(x - W / 2) / (W * 0.46)).pow(2.2)); val fx2 = max(0.0, 1 - (abs(x + st / 2 - W / 2) / (W * 0.46)).pow(2.2))
                val fy = max(0.0, 1 - (abs(y + st / 2 - cy) / hh).pow(2.2)); val fy2 = max(0.0, 1 - (abs(y - cy) / hh).pow(2.2))
                g.setFillColor(C(1.0, 1.0, 1.0, (if (fmod(x, 160.0) == 0.0) 0.06 else 0.03) * fx * fy)); g.fillRect(CGRectMake(x, y, 1, st))
                g.setFillColor(C(1.0, 1.0, 1.0, 0.035 * fx2 * fy2)); g.fillRect(CGRectMake(x, y, st, 1))
                y += st
            }
            x += st
        }
        // titolo
        val f1 = UIFont.fontWithName("AvenirNext-DemiBold", 66)
        val f2 = UIFont.fontWithName("AvenirNext-UltraLight", 66)
        val w1 = UkText.paint(f1).measureText("similFranz").toDouble(); val w2 = UkText.paint(f2).measureText(" Monitor").toDouble()
        val tsw = w1 + w2
        val titleY = 446.0
        g.drawText("similFranz", CGPointMake((W - tsw) / 2, titleY), f1, C(0.96, 0.97, 0.97, 1.0))
        g.drawText(" Monitor", CGPointMake((W - tsw) / 2 + w1, titleY), f2, SP_GREEN)
        var s = "Monitor multiparametrico per la simulazione sanitaria"
        val subY = 538.0
        drawCentered(g, s, subY, W, UIFont.fontWithName("AvenirNext-Regular", 18), C(0.62, 0.66, 0.70, 1.0), 0.6)
        s = "SIMULAZIONE  ·  NON PER USO CLINICO"
        drawCentered(g, s, H - 34, W, UIFont.systemFontOfSize(13), C(0.45, 0.47, 0.50, 1.0), 1.6)
    }

    fun makeStatic() { traces.hidden = true; track.hidden = true; status.hidden = true; percent.hidden = true; _version.hidden = true }

    fun setProgress(p0: Double) {
        val p = max(progress, min(1.0, p0))
        // la barra cresce in 1 s (ease in/out) dalla larghezza che ha in questo momento
        val from = fill.bounds.width; val to = kBarW * p
        load?.stop()
        load = UkAnimation(1.0) { t ->
            val e = t * t * (3 - 2 * t)
            fill.bounds = CGRectMake(0, 0, from + (to - from) * e, kBarH)
            barView.setNeedsDisplay()
        }.start()
        progress = p
        percent.text = String.format(Locale.US, "%d%%", Math.round(p * 100))
    }

    fun setStatus(s: String?) {
        // dissolvenza incrociata di 0,5 s tra il testo vecchio e quello nuovo (CATransition fade)
        statusGhost?.removeFromSuperview(); statusGhost = null
        val old = status.text
        if (!old.isNullOrEmpty() && !status.hidden && window != null) {
            val gh = UILabel(status.frame)
            gh.font = status.font; gh.textColor = status.textColor; gh.backgroundColor = UIColor.clearColor; gh.text = old
            insertSubview(gh, status); statusGhost = gh
            status.alpha = 0f
            UIView.animateWithDuration(0.5, { gh.alpha = 0f; status.alpha = 1f }, { _ -> gh.removeFromSuperview(); if (statusGhost === gh) statusGhost = null })
        } else if (window != null && !status.hidden) {
            status.alpha = 0f
            UIView.animateWithDuration(0.5, { status.alpha = 1f })
        }
        status.text = s
    }

    private fun startAnimations() {
        // sweep da monitor: la traccia si disegna da sinistra a destra con il punto in testa, sfuma e ricomincia
        val T = 6.4                 // lento come lo sweep di un monitor vero
        sweep?.stop()
        sweep = UkAnimation(T, true) { t ->
            if (parent == null) { stopAnimations(); return@UkAnimation }      // ANDROID: tolta dallo schermo, le animazioni a tempo si fermano
            for (k in lines.indices) {
                val line = lines[k]; val dot = dots[k]
                // la traccia si disegna nel 78% del giro, resta, poi sfuma (opacità 1, 1, 0 ai tempi 0, 0.84, 1)
                val draw = min(1.0, t / 0.78)
                line.strokeEnd = draw
                line.opacity = if (t <= 0.84) 1.0 else max(0.0, 1 - (t - 0.84) / 0.16)
                // il punto corre lungo la traccia a velocità costante, poi sparisce (opacità 1, 1, 0, 0 ai tempi 0, 0.76, 0.80, 1)
                dot.position = points[k].pointAt(draw)
                dot.opacity = if (t <= 0.76) 1.0 else (if (t < 0.80) 1 - (t - 0.76) / 0.04 else 0.0)
            }
            tracesView.setNeedsDisplay()
        }.start()
        // riflesso che scorre sulla barra
        shineAnim?.stop()
        shineAnim = UkAnimation(2.6, true) { t ->
            shine.position = CGPointMake(-45 + (kBarW + 45 - (-45)) * t, kBarH / 2)
            barView.setNeedsDisplay()
        }.start()
    }

    private fun stopAnimations() {
        sweep?.stop(); sweep = null
        shineAnim?.stop(); shineAnim = null
        load?.stop(); load = null
    }


    // stages: [ [etichetta, blocco di lavoro (() -> Unit) oppure null], ... ] eseguiti in ordine sul main thread;
    // ogni fase resta visibile almeno minStage secondi. Alla fine dissolve, si toglie da sola e chiama done.
    // (ogni fase può essere una lista [etichetta, blocco] oppure una coppia Pair(etichetta, blocco))
    fun runStages(stages: List<Any?>, minStage: Double, completion: (() -> Unit)?) {
        this.stages = stages.map { st -> when (st) { is Pair<*, *> -> listOf(st.first, st.second); is List<*> -> st; else -> listOf(st) } }
        this.minStage = minStage; this.done = completion
        startAnimations()
        setProgress(0.04)
        runStage(0)
    }

    private fun runStage(i: Int) {
        if (superview == null) { stopAnimations(); return }             // tolta dall'esterno (autotest): la catena si ferma
        val n = stages.size
        if (i >= n) {
            setProgress(1.0); setStatus("Pronto")
            Uk.after(0.9) {
                UIView.animateWithDuration(0.9, { this.alpha = 0f }, { _ ->
                    removeFromSuperview(); stopAnimations(); done?.invoke()
                })
            }
            return
        }
        val st = stages[i]
        setStatus(st[0] as? String)
        setProgress((i + 0.45) / n)
        // un attimo perché l'etichetta arrivi sullo schermo, poi il lavoro vero della fase
        Uk.after(0.55) {
            val t0 = CACurrentMediaTime()
            if (st.size > 1 && st[1] != null) {
                @Suppress("UNCHECKED_CAST")
                (st[1] as? Function0<Any?>)?.invoke()
            }
            setProgress((i + 1.0) / n)
            val left = max(0.05, minStage - 0.55 - (CACurrentMediaTime() - t0))
            Uk.after(left) { runStage(i + 1) }
        }
    }
}


