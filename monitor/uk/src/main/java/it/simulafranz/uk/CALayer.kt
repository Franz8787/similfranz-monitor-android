package it.simulafranz.uk

import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PathMeasure
import android.graphics.Shader

/**
 * Strato grafico. Ogni UIView ha il suo (`view.layer`: angoli, bordo, ombra, sottostrati);
 * gli strati creati a mano (CALayer(), CAGradientLayer(), CAShapeLayer()) si aggiungono con addSublayer.
 * Dopo aver cambiato una proprietà di uno strato già a schermo la vista si ridisegna da sola.
 */
open class CALayer {
    internal var host: UIView? = null          // vista che lo disegna (sua o di un antenato)
    internal var ownerView: UIView? = null     // != null se è lo strato principale di una vista
    var superlayer: CALayer? = null; internal set

    open var frame: CGRect = CGRect()
        get() = ownerView?.frame ?: field
        set(v) { val o = ownerView; if (o != null) o.frame = v else { field = v; dirty() } }
    var bounds: CGRect
        get() = CGRect(0.0, 0.0, frame.width, frame.height)
        set(v) { val f = frame; frame = CGRect(f.x, f.y, v.width, v.height) }
    var position: CGPoint
        get() = CGPoint(frame.midX, frame.midY)
        set(v) { val f = frame; frame = CGRect(v.x - f.width / 2, v.y - f.height / 2, f.width, f.height) }
    var backgroundColor: UIColor? = null
        get() = ownerView?.backgroundColor ?: field
        set(v) { val o = ownerView; if (o != null) o.backgroundColor = v else { field = v; dirty() } }
    var cornerRadius: Double = 0.0; set(v) { field = v; dirty() }
    var borderWidth: Double = 0.0; set(v) { field = v; dirty() }
    var borderColor: UIColor? = null; set(v) { field = v; dirty() }
    var shadowColor: UIColor? = UIColor.blackColor; set(v) { field = v; dirty() }
    var shadowOpacity: Double = 0.0; set(v) { field = v; dirty() }
    var shadowRadius: Double = 3.0; set(v) { field = v; dirty() }
    var shadowOffset: CGSize = CGSize(0.0, -3.0); set(v) { field = v; dirty() }
    var shadowPath: UIBezierPath? = null; set(v) { field = v; dirty() }
    var masksToBounds: Boolean = false; set(v) { field = v; dirty() }
    var opacity: Double = 1.0
        set(v) { field = v; val o = ownerView; if (o != null) o.alpha = v.toFloat() else dirty() }
    var hidden: Boolean = false
        set(v) { field = v; val o = ownerView; if (o != null) o.hidden = v else dirty() }
    /** senza effetto su Android (qui le viste ferme non vengono ricomposte) */
    var shouldRasterize: Boolean = false
    var rasterizationScale: Double = 1.0
    var zPosition: Double = 0.0
    var name: String? = null
    val sublayers = ArrayList<CALayer>()

    fun addSublayer(l: CALayer) { l.removeFromSuperlayer(); sublayers.add(l); adopt(l) }
    fun insertSublayer(l: CALayer, atIndex: Int) { l.removeFromSuperlayer(); sublayers.add(atIndex.coerceIn(0, sublayers.size), l); adopt(l) }
    fun removeFromSuperlayer() { superlayer?.let { it.sublayers.remove(this); it.dirty() }; superlayer = null; attachHost(null) }
    private fun adopt(l: CALayer) { l.superlayer = this; l.attachHost(host); dirty() }
    internal fun attachHost(h: UIView?) { host = h; for (s in sublayers) s.attachHost(h) }
    fun setNeedsDisplay() = dirty()
    internal fun dirty() { host?.invalidate() }

    /** Disegna lo strato libero (non di una vista) nel sistema di coordinate del genitore, in punti. */
    internal fun drawFree(c: Canvas) {
        if (hidden || opacity <= 0.001) return
        val f = frame
        val sc = if (opacity < 0.999) c.saveLayerAlpha(f.x.toFloat() - 4, f.y.toFloat() - 4, f.maxX.toFloat() + 4, f.maxY.toFloat() + 4, (opacity * 255).toInt()) else c.save()
        c.translate(f.x.toFloat(), f.y.toFloat())
        drawShadow(c, f.width.toFloat(), f.height.toFloat())
        drawContent(c, f.width.toFloat(), f.height.toFloat())
        for (s in sublayers) s.drawFree(c)
        c.restoreToCount(sc)
    }

    /** forma che proietta l'ombra: shadowPath, altrimenti il rettangolo arrotondato (le forme la ridefiniscono) */
    protected open fun shadowShape(w: Float, h: Float): Path? {
        shadowPath?.let { return it.path }
        val r = cornerRadius.toFloat().coerceAtMost(minOf(w, h) / 2)
        if (backgroundColor == null && borderWidth <= 0) return null
        tmp.reset(); tmp.addRoundRect(0f, 0f, w, h, r, r, Path.Direction.CW); return tmp
    }
    private val tmp = Path()
    private fun drawShadow(c: Canvas, w: Float, h: Float) {
        val sc = shadowColor ?: return
        if (shadowOpacity <= 0.01 || masksToBounds) return
        val sp = shadowShape(w, h) ?: return
        val key = shadowPath?.let { "p" + System.identityHashCode(it) } ?: (if (this is CAShapeLayer) "s" + System.identityHashCode(this) + "," + System.identityHashCode((this as CAShapeLayer).path) else "r$w,$h,$cornerRadius")
        UkShadow.draw(c, key, sp, w.toDouble(), h.toDouble(), shadowRadius * 0.8, shadowOffset.width, shadowOffset.height,
            UIColor(sc.r, sc.g, sc.b, (sc.a * shadowOpacity).coerceIn(0.0, 1.0)).argb)
    }

    protected open fun drawContent(c: Canvas, w: Float, h: Float) {
        val r = cornerRadius.toFloat().coerceAtMost(minOf(w, h) / 2)
        backgroundColor?.let { if (it.a > 0) { P.style = Paint.Style.FILL; P.shader = null; P.pathEffect = null; P.color = it.argb; c.drawRoundRect(0f, 0f, w, h, r, r, P) } }
        val bc = borderColor
        if (borderWidth > 0 && bc != null) {
            val bw = borderWidth.toFloat()
            P.style = Paint.Style.STROKE; P.shader = null; P.pathEffect = null; P.strokeWidth = bw; P.color = bc.argb
            c.drawRoundRect(bw / 2, bw / 2, w - bw / 2, h - bw / 2, (r - bw / 2).coerceAtLeast(0f), (r - bw / 2).coerceAtLeast(0f), P)
        }
    }
    companion object {
        internal val P = Paint(Paint.ANTI_ALIAS_FLAG)
        private val SHP = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL; color = 0xFF000000.toInt() }
        fun layer() = CALayer()
    }
}

class CAGradientLayer : CALayer() {
    var colors: List<UIColor> = emptyList(); set(v) { field = v; dirty() }
    /** posizioni 0..1 (null = equidistanti) */
    var locations: List<Number>? = null; set(v) { field = v; dirty() }
    var startPoint = CGPoint(0.5, 0.0); set(v) { field = v; dirty() }
    var endPoint = CGPoint(0.5, 1.0); set(v) { field = v; dirty() }
    override fun drawContent(c: Canvas, w: Float, h: Float) {
        if (colors.size < 2) { if (colors.size == 1) { P.style = Paint.Style.FILL; P.shader = null; P.color = colors[0].argb; val r = cornerRadius.toFloat(); c.drawRoundRect(0f, 0f, w, h, r, r, P) }; return }
        val loc = locations
        G.shader = LinearGradient((startPoint.x * w).toFloat(), (startPoint.y * h).toFloat(), (endPoint.x * w).toFloat(), (endPoint.y * h).toFloat(),
            IntArray(colors.size) { colors[it].argb }, if (loc != null && loc.size == colors.size) FloatArray(loc.size) { loc[it].toFloat() } else null, Shader.TileMode.CLAMP)
        val r = cornerRadius.toFloat().coerceAtMost(minOf(w, h) / 2)
        c.drawRoundRect(0f, 0f, w, h, r, r, G)
        val bc = borderColor
        if (borderWidth > 0 && bc != null) { val bw = borderWidth.toFloat(); P.style = Paint.Style.STROKE; P.shader = null; P.pathEffect = null; P.strokeWidth = bw; P.color = bc.argb; c.drawRoundRect(bw / 2, bw / 2, w - bw / 2, h - bw / 2, r, r, P) }
    }
    companion object { private val G = Paint(Paint.ANTI_ALIAS_FLAG); fun layer() = CAGradientLayer() }
}

class CAShapeLayer : CALayer() {
    var path: UIBezierPath? = null; set(v) { field = v; dirty() }
    var fillColor: UIColor? = UIColor.blackColor; set(v) { field = v; dirty() }
    var strokeColor: UIColor? = null; set(v) { field = v; dirty() }
    var lineWidth: Double = 1.0; set(v) { field = v; dirty() }
    var lineCap: String = kCALineCapButt; set(v) { field = v; dirty() }
    var lineJoin: String = kCALineJoinMiter; set(v) { field = v; dirty() }
    var lineDashPattern: List<Number>? = null; set(v) { field = v; dirty() }
    /** frazione 0..1 del tracciato disegnata (per le animazioni "si disegna da sola") */
    var strokeStart: Double = 0.0; set(v) { field = v; dirty() }
    var strokeEnd: Double = 1.0; set(v) { field = v; dirty() }
    private val seg = Path()
    override fun shadowShape(w: Float, h: Float): Path? = shadowPath?.path ?: path?.path ?: super.shadowShape(w, h)
    override fun drawContent(c: Canvas, w: Float, h: Float) {
        super.drawContent(c, w, h)
        val p = path ?: return
        fillColor?.let { if (it.a > 0) { P.style = Paint.Style.FILL; P.shader = null; P.pathEffect = null; P.color = it.argb; c.drawPath(p.path, P) } }
        val sc = strokeColor
        if (sc != null && sc.a > 0 && lineWidth > 0 && strokeEnd > strokeStart) {
            S.color = sc.argb; S.strokeWidth = lineWidth.toFloat()
            S.strokeCap = when (lineCap) { kCALineCapRound -> Paint.Cap.ROUND; kCALineCapSquare -> Paint.Cap.SQUARE; else -> Paint.Cap.BUTT }
            S.strokeJoin = when (lineJoin) { kCALineJoinRound -> Paint.Join.ROUND; kCALineJoinBevel -> Paint.Join.BEVEL; else -> Paint.Join.MITER }
            val d = lineDashPattern
            S.pathEffect = if (d != null && d.size >= 2) DashPathEffect(FloatArray(d.size) { d[it].toFloat() }, 0f) else null
            if (strokeStart <= 0.0 && strokeEnd >= 1.0) c.drawPath(p.path, S)
            else {
                val pm = PathMeasure(p.path, false); var total = 0f
                do { total += pm.length } while (pm.nextContour())
                val pm2 = PathMeasure(p.path, false); var acc = 0f
                val a = (strokeStart * total).toFloat(); val b = (strokeEnd * total).toFloat()
                seg.reset()
                do { val len = pm2.length; val s0 = (a - acc).coerceIn(0f, len); val s1 = (b - acc).coerceIn(0f, len); if (s1 > s0) pm2.getSegment(s0, s1, seg, true); acc += len } while (pm2.nextContour())
                c.drawPath(seg, S)
            }
        }
    }
    companion object { private val S = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }; fun layer() = CAShapeLayer() }
}

/** Punto a una certa frazione (0..1) lungo un percorso: per far correre un pallino su una traccia. */
fun UIBezierPath.pointAtFraction(f: Double): CGPoint {
    val pm = PathMeasure(path, false); val pos = FloatArray(2)
    pm.getPosTan((f.coerceIn(0.0, 1.0) * pm.length).toFloat(), pos, null)
    return CGPoint(pos[0].toDouble(), pos[1].toDouble())
}

/** [CATransaction begin/commit/setDisableActions]: qui i cambi sono sempre immediati. */
object CATransaction { fun begin() {}; fun commit() {}; fun setDisableActions(b: Boolean) {}; fun setAnimationDuration(d: Double) {} }
