package it.simulafranz.uk

import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.text.Layout
import android.text.SpannableString
import android.text.Spanned
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import android.text.style.ForegroundColorSpan
import kotlin.math.ceil

const val kCGLineCapButt = 0
const val kCGLineCapRound = 1
const val kCGLineCapSquare = 2
const val kCGLineJoinMiter = 0
const val kCGLineJoinRound = 1
const val kCGLineJoinBevel = 2
const val kCALineCapButt = "butt"; const val kCALineCapRound = "round"; const val kCALineCapSquare = "square"
const val kCALineJoinMiter = "miter"; const val kCALineJoinRound = "round"; const val kCALineJoinBevel = "bevel"

/** UIBezierPath in punti. Gli angoli sono in radianti come su iOS (0 = est, crescono in senso orario a schermo). */
class UIBezierPath {
    val path = Path()
    var lineWidth = 1.0
    var lineCapStyle = kCGLineCapButt
    var lineJoinStyle = kCGLineJoinMiter
    val CGPath: UIBezierPath get() = this
    var currentPoint = CGPoint(); private set

    fun moveToPoint(p: CGPoint) { path.moveTo(p.x.toFloat(), p.y.toFloat()); currentPoint = p }
    fun addLineToPoint(p: CGPoint) { path.lineTo(p.x.toFloat(), p.y.toFloat()); currentPoint = p }
    fun addQuadCurveToPoint(p: CGPoint, controlPoint: CGPoint) { path.quadTo(controlPoint.x.toFloat(), controlPoint.y.toFloat(), p.x.toFloat(), p.y.toFloat()); currentPoint = p }
    fun addCurveToPoint(p: CGPoint, controlPoint1: CGPoint, controlPoint2: CGPoint) {
        path.cubicTo(controlPoint1.x.toFloat(), controlPoint1.y.toFloat(), controlPoint2.x.toFloat(), controlPoint2.y.toFloat(), p.x.toFloat(), p.y.toFloat()); currentPoint = p
    }
    fun addArcWithCenter(center: CGPoint, radius: Number, startAngle: Number, endAngle: Number, clockwise: Boolean) {
        val r = radius.toDouble(); val a0 = Math.toDegrees(startAngle.toDouble()); val a1 = Math.toDegrees(endAngle.toDouble())
        var sweep = a1 - a0
        if (clockwise) { while (sweep < 0) sweep += 360.0; if (sweep > 360) sweep = 360.0 } else { while (sweep > 0) sweep -= 360.0; if (sweep < -360) sweep = -360.0 }
        val oval = RectF((center.x - r).toFloat(), (center.y - r).toFloat(), (center.x + r).toFloat(), (center.y + r).toFloat())
        if (Math.abs(sweep) >= 360.0) { path.arcTo(oval, a0.toFloat(), (sweep / 2).toFloat()); path.arcTo(oval, (a0 + sweep / 2).toFloat(), (sweep / 2).toFloat()) }
        else path.arcTo(oval, a0.toFloat(), sweep.toFloat())
        currentPoint = CGPoint(center.x + r * Math.cos(endAngle.toDouble()), center.y + r * Math.sin(endAngle.toDouble()))
    }
    fun closePath() { path.close() }
    fun appendPath(p: UIBezierPath) { path.addPath(p.path) }
    fun removeAllPoints() { path.reset() }
    val bounds: CGRect get() { val r = RectF(); path.computeBounds(r, true); return CGRect(r.left.toDouble(), r.top.toDouble(), r.width().toDouble(), r.height().toDouble()) }

    /** Dentro drawRect: riempie / traccia con il colore corrente del contesto. */
    fun fill() { CGContext.current?.fillPath(this) }
    fun stroke() { CGContext.current?.let { it.setLineWidth(lineWidth); it.setLineCap(lineCapStyle); it.setLineJoin(lineJoinStyle); it.strokePath(this) } }
    fun addClip() { CGContext.current?.clip(this) }

    companion object {
        fun bezierPath() = UIBezierPath()
        fun bezierPathWithRect(r: CGRect) = UIBezierPath().apply { path.addRect(r.x.toFloat(), r.y.toFloat(), r.maxX.toFloat(), r.maxY.toFloat(), Path.Direction.CW) }
        fun bezierPathWithOvalInRect(r: CGRect) = UIBezierPath().apply { path.addOval(r.x.toFloat(), r.y.toFloat(), r.maxX.toFloat(), r.maxY.toFloat(), Path.Direction.CW) }
        fun bezierPathWithRoundedRect(r: CGRect, cornerRadius: Number) = UIBezierPath().apply {
            val c = cornerRadius.toFloat().coerceAtMost((minOf(r.width, r.height) / 2).toFloat()).coerceAtLeast(0f)
            path.addRoundRect(r.x.toFloat(), r.y.toFloat(), r.maxX.toFloat(), r.maxY.toFloat(), c, c, Path.Direction.CW)
        }
        fun bezierPathWithArcCenter(center: CGPoint, radius: Number, startAngle: Number, endAngle: Number, clockwise: Boolean) =
            UIBezierPath().apply { addArcWithCenter(center, radius, startAngle, endAngle, clockwise) }
    }
}

/** Testo con stili a tratti (NSAttributedString). */
class NSAttributedString(val string: String = "") {
    class Run(val start: Int, val end: Int, val font: UIFont?, val color: UIColor?)
    val runs = ArrayList<Run>()
    constructor(string: String, font: UIFont?, color: UIColor?) : this(string) { runs.add(Run(0, string.length, font, color)) }
    /** NSMutableAttributedString appendAttributedString: ritorna un nuovo testo */
    operator fun plus(o: NSAttributedString): NSAttributedString {
        val n = NSAttributedString(string + o.string)
        n.runs.addAll(runs); for (r in o.runs) n.runs.add(Run(r.start + string.length, r.end + string.length, r.font, r.color))
        return n
    }
    fun append(text: String, font: UIFont?, color: UIColor?) = this + NSAttributedString(text, font, color)
    val length get() = string.length
}

/** Misura e disegno del testo, in punti. */
object UkText {
    private val tp = TextPaint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG)
    fun paint(font: UIFont, color: UIColor? = null, scale: Double = 1.0): TextPaint {
        tp.typeface = font.typeface; tp.textSize = (font.pointSize * scale).toFloat(); tp.letterSpacing = 0f; tp.clearShadowLayer()
        if (color != null) tp.color = color.argb
        return tp
    }
    /** [NSString sizeWithAttributes:] su una riga */
    fun size(text: String, font: UIFont): CGSize {
        val p = paint(font)
        var w = 0f
        for (line in text.split('\n')) w = maxOf(w, p.measureText(line))
        val fm = p.fontMetrics
        return CGSize(ceil(w.toDouble()), ceil((fm.descent - fm.ascent).toDouble()) * text.split('\n').size)
    }
    fun styled(a: NSAttributedString, base: UIFont, baseColor: UIColor, k: Double): CharSequence {
        if (a.runs.isEmpty()) return a.string
        val s = SpannableString(a.string)
        for (r in a.runs) {
            if (r.end <= r.start) continue
            r.font?.let { f -> s.setSpan(UkSizeSpan((f.pointSize * k).toFloat()), r.start, r.end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                s.setSpan(UkTypefaceSpan(f.typeface), r.start, r.end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE) }
            r.color?.let { c -> s.setSpan(ForegroundColorSpan(c.argb), r.start, r.end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE) }
        }
        return s
    }
    fun layout(cs0: CharSequence, p: TextPaint, width: Int, align: Int, maxLines: Int, ellipsize: Boolean): StaticLayout {
        val cs = UkSymbols.apply(cs0)      // ANDROID (simboli 22/11): ▶ ♥ ⚠ ✓ … disegnati vettoriali, mai emoji (UkSymbols.kt)
        val al = when (align) { NSTextAlignmentCenter -> Layout.Alignment.ALIGN_CENTER; NSTextAlignmentRight -> Layout.Alignment.ALIGN_OPPOSITE; else -> Layout.Alignment.ALIGN_NORMAL }
        val b = StaticLayout.Builder.obtain(cs, 0, cs.length, TextPaint(p), maxOf(width, 0)).setAlignment(al).setIncludePad(false)
        if (maxLines > 0) { b.setMaxLines(maxLines); if (ellipsize) b.setEllipsize(TextUtils.TruncateAt.END) }
        return b.build()
    }
}
class UkSizeSpan(private val size: Float) : android.text.style.MetricAffectingSpan() {
    override fun updateDrawState(tp: TextPaint) { tp.textSize = size }
    override fun updateMeasureState(tp: TextPaint) { tp.textSize = size }
}
class UkTypefaceSpan(private val tf: android.graphics.Typeface) : android.text.style.MetricAffectingSpan() {
    override fun updateDrawState(tp: TextPaint) { tp.typeface = tf }
    override fun updateMeasureState(tp: TextPaint) { tp.typeface = tf }
}

/** Contesto grafico di drawRect: un Canvas già scalato in punti, con lo stato corrente di CoreGraphics. */
class CGContext(canvas: Canvas) {
    /** tela corrente (lo stesso contesto viene riusato a ogni fotogramma) */
    var canvas: Canvas = canvas; internal set
    val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 1f }
    private val p = Path()

    fun setFillColor(c: UIColor) { fillPaint.color = c.argb }
    fun setStrokeColor(c: UIColor) { strokePaint.color = c.argb }
    fun setLineWidth(w: Number) { strokePaint.strokeWidth = w.toFloat() }
    fun setLineCap(cap: Int) { strokePaint.strokeCap = when (cap) { kCGLineCapRound -> Paint.Cap.ROUND; kCGLineCapSquare -> Paint.Cap.SQUARE; else -> Paint.Cap.BUTT } }
    fun setLineJoin(j: Int) { strokePaint.strokeJoin = when (j) { kCGLineJoinRound -> Paint.Join.ROUND; kCGLineJoinBevel -> Paint.Join.BEVEL; else -> Paint.Join.MITER } }
    /** CGContextSetLineDash: lengths vuoto = linea continua */
    fun setLineDash(phase: Number, lengths: DoubleArray?) {
        strokePaint.pathEffect = if (lengths == null || lengths.size < 2) null else DashPathEffect(FloatArray(lengths.size) { lengths[it].toFloat() }, phase.toFloat())
    }
    fun setAlpha(a: Number) { val v = (a.toDouble().coerceIn(0.0, 1.0) * 255).toInt(); fillPaint.alpha = v; strokePaint.alpha = v }
    fun fillRect(r: CGRect) { canvas.drawRect(r.x.toFloat(), r.y.toFloat(), r.maxX.toFloat(), r.maxY.toFloat(), fillPaint) }
    fun strokeRect(r: CGRect) { canvas.drawRect(r.x.toFloat(), r.y.toFloat(), r.maxX.toFloat(), r.maxY.toFloat(), strokePaint) }
    fun fillEllipseInRect(r: CGRect) { canvas.drawOval(r.x.toFloat(), r.y.toFloat(), r.maxX.toFloat(), r.maxY.toFloat(), fillPaint) }
    fun strokeEllipseInRect(r: CGRect) { canvas.drawOval(r.x.toFloat(), r.y.toFloat(), r.maxX.toFloat(), r.maxY.toFloat(), strokePaint) }
    // percorso corrente (CGContextMoveToPoint / AddLineToPoint / StrokePath / FillPath)
    fun beginPath() { p.reset() }
    fun moveToPoint(x: Number, y: Number) { p.moveTo(x.toFloat(), y.toFloat()) }
    fun addLineToPoint(x: Number, y: Number) { p.lineTo(x.toFloat(), y.toFloat()) }
    fun closePath() { p.close() }
    fun strokePath() { canvas.drawPath(p, strokePaint); p.reset() }
    fun fillPath() { canvas.drawPath(p, fillPaint); p.reset() }
    fun fillPath(b: UIBezierPath) { canvas.drawPath(b.path, fillPaint) }
    fun strokePath(b: UIBezierPath) { canvas.drawPath(b.path, strokePaint) }
    fun clip(b: UIBezierPath) { canvas.clipPath(b.path) }
    fun clipToRect(r: CGRect) { canvas.clipRect(r.x.toFloat(), r.y.toFloat(), r.maxX.toFloat(), r.maxY.toFloat()) }
    fun saveGState() { canvas.save() }
    fun restoreGState() { canvas.restore() }
    fun translateCTM(x: Number, y: Number) { canvas.translate(x.toFloat(), y.toFloat()) }
    fun scaleCTM(x: Number, y: Number) { canvas.scale(x.toFloat(), y.toFloat()) }
    fun rotateCTM(radians: Number) { canvas.rotate(Math.toDegrees(radians.toDouble()).toFloat()) }

    /** CGContextDrawLinearGradient (riempie la zona di ritaglio corrente; usare clip prima) */
    fun drawLinearGradient(colors: List<UIColor>, locations: DoubleArray?, start: CGPoint, end: CGPoint) {
        val pa = Paint(Paint.ANTI_ALIAS_FLAG)
        pa.shader = LinearGradient(start.x.toFloat(), start.y.toFloat(), end.x.toFloat(), end.y.toFloat(), IntArray(colors.size) { colors[it].argb },
            locations?.let { l -> FloatArray(l.size) { l[it].toFloat() } }, Shader.TileMode.CLAMP)
        canvas.drawPaint(pa)
    }
    fun drawRadialGradient(colors: List<UIColor>, locations: DoubleArray?, center: CGPoint, radius: Number) {
        val pa = Paint(Paint.ANTI_ALIAS_FLAG)
        pa.shader = RadialGradient(center.x.toFloat(), center.y.toFloat(), radius.toFloat().coerceAtLeast(0.01f), IntArray(colors.size) { colors[it].argb },
            locations?.let { l -> FloatArray(l.size) { l[it].toFloat() } }, Shader.TileMode.CLAMP)
        canvas.drawPaint(pa)
    }
    /** [NSString drawAtPoint:withAttributes:] — p è l'angolo in alto a sinistra */
    fun drawText(text: String, at: CGPoint, font: UIFont, color: UIColor) {
        val tp = UkText.paint(font, color)
        canvas.drawText(text, at.x.toFloat(), (at.y - tp.fontMetrics.ascent).toFloat(), tp)
    }
    /** [NSString drawInRect:withAttributes:] con allineamento */
    fun drawText(text: String, inRect: CGRect, font: UIFont, color: UIColor, align: Int = NSTextAlignmentLeft) {
        val tp = UkText.paint(font, color)
        val l = UkText.layout(text, tp, ceil(inRect.width).toInt(), align, 0, false)
        canvas.save(); canvas.translate(inRect.x.toFloat(), inRect.y.toFloat()); l.draw(canvas); canvas.restore()
    }
    companion object {
        /** contesto del drawRect in corso (UIGraphicsGetCurrentContext) */
        var current: CGContext? = null
    }
}
fun UIGraphicsGetCurrentContext(): CGContext? = CGContext.current
