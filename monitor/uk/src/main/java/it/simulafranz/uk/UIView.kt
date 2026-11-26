package it.simulafranz.uk

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.view.animation.LinearInterpolator
import android.view.animation.OvershootInterpolator
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.roundToInt

/** Trasformazione affine [a b c d tx ty] come CGAffineTransform. */
data class CGAffineTransform(val a: Double = 1.0, val b: Double = 0.0, val c: Double = 0.0, val d: Double = 1.0, val tx: Double = 0.0, val ty: Double = 0.0) {
    val isIdentity get() = this == CGAffineTransformIdentity
    fun concat(o: CGAffineTransform) = CGAffineTransform(a * o.a + b * o.c, a * o.b + b * o.d, c * o.a + d * o.c, c * o.b + d * o.d, tx * o.a + ty * o.c + o.tx, tx * o.b + ty * o.d + o.ty)
}
val CGAffineTransformIdentity = CGAffineTransform()
fun CGAffineTransformMakeTranslation(tx: Number, ty: Number) = CGAffineTransform(tx = tx.toDouble(), ty = ty.toDouble())
fun CGAffineTransformMakeScale(sx: Number, sy: Number) = CGAffineTransform(a = sx.toDouble(), d = sy.toDouble())
fun CGAffineTransformMakeRotation(r: Number) = CGAffineTransform(Math.cos(r.toDouble()), Math.sin(r.toDouble()), -Math.sin(r.toDouble()), Math.cos(r.toDouble()))
fun CGAffineTransformScale(t: CGAffineTransform, sx: Number, sy: Number) = CGAffineTransformMakeScale(sx, sy).concat(t)
fun CGAffineTransformTranslate(t: CGAffineTransform, tx: Number, ty: Number) = CGAffineTransformMakeTranslation(tx, ty).concat(t)
fun CGAffineTransformRotate(t: CGAffineTransform, r: Number) = CGAffineTransformMakeRotation(r).concat(t)
fun CGAffineTransformIsIdentity(t: CGAffineTransform) = t.isIdentity

const val UIViewAnimationOptionCurveEaseInOut = 0
const val UIViewAnimationOptionAllowUserInteraction = 2
const val UIViewAnimationOptionBeginFromCurrentState = 4
const val UIViewAnimationOptionCurveEaseOut = 0x20000
const val UIViewAnimationOptionCurveLinear = 0x30000

/**
 * UIView su Android. Posizione e dimensioni in PUNTI (`frame`), figli in posizione assoluta.
 * Differenze di nome obbligate (Android usa già questi nomi):
 *   tag            → uiTag (Int)
 *   alpha          → è Float: `v.alpha = 0.5f`
 *   enabled/selected (UIControl) → isEnabled / isSelected
 *   drawRect:      → override fun drawRect(ctx: CGContext, rect: CGRect)
 */
open class UIView(frame: CGRect = CGRect()) : ViewGroup(Uk.ctx) {
    val layer = CALayer().also { it.ownerView = this; it.host = this }

    private var _frame: CGRect = frame
    var frame: CGRect
        get() = _frame
        set(v) {
            val an = animating
            if (an != null && attached && v != _frame) { val from = _frame; an.add { t -> setFrameNow(lerp(from, v, t)) }; return }
            setFrameNow(v)
        }
    private fun setFrameNow(v: CGRect) {
        val sizeChanged = v.width != _frame.width || v.height != _frame.height
        _frame = v
        applyFrame()
        if (sizeChanged) { layoutSubviews(); invalidate() }
    }
    private fun lerp(a: CGRect, b: CGRect, t: Double) = CGRect(a.x + (b.x - a.x) * t, a.y + (b.y - a.y) * t, a.width + (b.width - a.width) * t, a.height + (b.height - a.height) * t)

    val bounds: CGRect get() = CGRect(0.0, 0.0, _frame.width, _frame.height)
    var center: CGPoint
        get() = CGPoint(_frame.midX, _frame.midY)
        set(v) { frame = CGRect(v.x - _frame.width / 2, v.y - _frame.height / 2, _frame.width, _frame.height) }

    var backgroundColor: UIColor? = null
        set(v) {
            val an = animating; val from = field
            if (an != null && attached && from != null && v != null && from != v) {
                an.add { t -> field = UIColor(from.r + (v.r - from.r) * t, from.g + (v.g - from.g) * t, from.b + (v.b - from.b) * t, from.a + (v.a - from.a) * t); invalidate() }; return
            }
            field = v; invalidate()
        }
    var hidden: Boolean = false
        set(v) { if (field == v) return; field = v; visibility = if (v) View.INVISIBLE else View.VISIBLE }
    var clipsToBounds: Boolean = false
        set(v) { field = v; invalidate() }
    var userInteractionEnabled: Boolean = true
    var uiTag: Int = 0
    var accessibilityIdentifier: String? = null
    var opaque: Boolean = false
    var contentMode: Int = 0
    private var tint: UIColor? = null
    /** come su iOS: se non è impostato vale quello del contenitore */
    var tintColor: UIColor?
        get() = tint ?: superview?.tintColor
        set(v) { tint = v; tintColorDidChange(); invalidate(); for (s in subviews) s.tintColorDidChange() }
    open fun tintColorDidChange() {}

    var transform: CGAffineTransform = CGAffineTransformIdentity
        set(v) {
            val an = animating
            if (an != null && attached && v != field) { val f = field; an.add { t -> applyTransform(CGAffineTransform(f.a + (v.a - f.a) * t, f.b + (v.b - f.b) * t, f.c + (v.c - f.c) * t, f.d + (v.d - f.d) * t, f.tx + (v.tx - f.tx) * t, f.ty + (v.ty - f.ty) * t)) }; field = v; return }
            field = v; applyTransform(v)
        }
    private fun applyTransform(t: CGAffineTransform) {
        val sx = hypot(t.a, t.b)
        translationX = Uk.pxf(t.tx); translationY = Uk.pxf(t.ty)
        rotation = Math.toDegrees(atan2(t.b, t.a)).toFloat()
        scaleX = sx.toFloat(); scaleY = (if (sx != 0.0) (t.a * t.d - t.b * t.c) / sx else 1.0).toFloat()
    }

    override fun setAlpha(a: Float) {
        val an = animating
        if (an != null && attached && a != alpha) { val from = alpha; an.add { t -> super.setAlpha(from + (a - from) * t.toFloat()) }; return }
        super.setAlpha(a)
    }

    init {
        setWillNotDraw(false)
        clipChildren = false; clipToPadding = false
        isMotionEventSplittingEnabled = false
    }

    // ---- gerarchia ----
    val subviews: List<UIView> get() { val l = ArrayList<UIView>(childCount); for (i in 0 until childCount) (getChildAt(i) as? UIView)?.let { l.add(it) }; return l }
    val superview: UIView? get() = parent as? UIView
    /** != null quando la vista è a schermo (come `view.window` su iOS): è la vista radice */
    val window: UIView? get() { if (!attached) return null; var v: UIView = this; while (true) v = v.superview ?: return v }
    private val attached get() = isAttachedToWindow

    fun addSubview(v: View) { (v.parent as? ViewGroup)?.removeView(v); addView(v) }
    fun insertSubview(v: View, atIndex: Int) { (v.parent as? ViewGroup)?.removeView(v); addView(v, atIndex.coerceIn(0, childCount)) }
    fun insertSubview(v: View, belowSubview: View) { (v.parent as? ViewGroup)?.removeView(v); val i = indexOfChild(belowSubview); addView(v, if (i < 0) childCount else i) }
    fun insertSubviewAbove(v: View, aboveSubview: View) { (v.parent as? ViewGroup)?.removeView(v); val i = indexOfChild(aboveSubview); addView(v, if (i < 0) childCount else i + 1) }
    fun bringSubviewToFront(v: View) { if (indexOfChild(v) >= 0 && indexOfChild(v) != childCount - 1) { removeView(v); addView(v) } }
    fun sendSubviewToBack(v: View) { if (indexOfChild(v) > 0) { removeView(v); addView(v, 0) } }
    fun removeFromSuperview() { (parent as? ViewGroup)?.removeView(this) }
    fun isDescendantOfView(v: UIView): Boolean { var p: UIView? = this; while (p != null) { if (p === v) return true; p = p.superview }; return false }
    fun viewWithTag(tag: Int): UIView? { if (uiTag == tag) return this; for (s in subviews) s.viewWithTag(tag)?.let { return it }; return null }

    /** spostamento dei figli (contentOffset di UIScrollView): i figli si vedono a frame - childShift */
    open val childShift: CGPoint get() = ZERO_SHIFT

    open fun layoutSubviews() {}
    open fun didMoveToWindow() {}
    open fun didMoveToSuperview() {}
    fun setNeedsDisplay() { invalidate() }
    fun setNeedsLayout() { layoutSubviews() }
    fun layoutIfNeeded() {}
    open fun sizeThatFits(size: CGSize): CGSize = bounds.size
    open fun sizeToFit() { val s = sizeThatFits(bounds.size); frame = CGRect(_frame.x, _frame.y, s.width, s.height) }

    override fun onAttachedToWindow() { super.onAttachedToWindow(); applyFrame(); didMoveToWindow() }
    override fun onDetachedFromWindow() { super.onDetachedFromWindow(); didMoveToWindow() }
    override fun onViewAdded(child: View) { super.onViewAdded(child); (child as? UIView)?.let { it.applyFrame(); it.didMoveToSuperview() } }

    // ---- disposizione: posizione assoluta, in pixel arrotondati sui bordi (niente fessure tra viste adiacenti) ----
    internal fun applyFrame() {
        val sh = superview?.childShift ?: ZERO_SHIFT
        val f = if (sh === ZERO_SHIFT) _frame else CGRect(_frame.x - sh.x, _frame.y - sh.y, _frame.width, _frame.height)
        val l = Uk.px(f.x); val t = Uk.px(f.y); val r = Uk.px(f.x + f.width); val b = Uk.px(f.y + f.height)
        if (l != left || t != top || r != right || b != bottom) layout(l, t, r, b)
    }
    override fun onMeasure(w: Int, h: Int) { if (UkProfile.on) UkProfile.measures++; setMeasuredDimension(Uk.px(_frame.width), Uk.px(_frame.height)) }
    override fun requestLayout() { if (UkProfile.on) UkProfile.requestLayouts++; super.requestLayout() }
    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        if (UkProfile.on) UkProfile.layouts++
        for (i in 0 until childCount) {
            val c = getChildAt(i)
            if (c is UIView) c.applyFrame()
            else { c.measure(MeasureSpec.makeMeasureSpec(r - l, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(b - t, MeasureSpec.EXACTLY)); c.layout(0, 0, r - l, b - t) }
        }
    }

    // ---- disegno ----
    open fun drawRect(ctx: CGContext, rect: CGRect) {}
    private val clipPath = Path()
    private val shadowRect = Path()
    private val clipPath2 = Path()
    private var ctxCache: CGContext? = null
    private fun roundPath(w: Float, h: Float, r: Float): Path { clipPath.reset(); clipPath.addRoundRect(0f, 0f, w, h, r, r, Path.Direction.CW); return clipPath }

    override fun onDraw(canvas: Canvas) {
        val t0 = if (UkProfile.on) System.nanoTime() else 0L
        onDraw0(canvas)
        if (UkProfile.on) UkProfile.draw(javaClass.simpleName, System.nanoTime() - t0)
    }
    private fun onDraw0(canvas: Canvas) {
        val fw = _frame.width; val fh = _frame.height
        if (fw <= 0 || fh <= 0 || width == 0 || height == 0) return
        canvas.save()
        canvas.scale(width / fw.toFloat(), height / fh.toFloat())
        val w = fw.toFloat(); val h = fh.toFloat()
        val r = layer.cornerRadius.toFloat().coerceAtMost(minOf(w, h) / 2).coerceAtLeast(0f)
        val bg = backgroundColor
        // ombra
        val sc = layer.shadowColor
        if (layer.shadowOpacity > 0.01 && sc != null && !(clipsToBounds || layer.masksToBounds)) {
            val sp = layer.shadowPath?.path ?: shadowRect.apply { reset(); addRoundRect(0f, 0f, w, h, r, r, Path.Direction.CW) }
            val key = layer.shadowPath?.let { "p" + System.identityHashCode(it) } ?: "r$w,$h,$r"
            UkShadow.draw(canvas, key, sp, fw, fh, layer.shadowRadius * 0.8, layer.shadowOffset.width, layer.shadowOffset.height,
                UIColor(sc.r, sc.g, sc.b, (sc.a * layer.shadowOpacity).coerceIn(0.0, 1.0)).argb)
        }
        if (clipsToBounds || layer.masksToBounds) { if (r <= 0f) canvas.clipRect(0f, 0f, w, h) else canvas.clipPath(roundPath(w, h, r)) }
        if (bg != null && bg.a > 0) { BG.color = bg.argb; if (r > 0) canvas.drawRoundRect(0f, 0f, w, h, r, r, BG) else canvas.drawRect(0f, 0f, w, h, BG) }
        for (s in layer.sublayers) s.drawFree(canvas)
        val cg = ctxCache?.also { it.canvas = canvas } ?: CGContext(canvas).also { ctxCache = it }
        val prev = CGContext.current; CGContext.current = cg
        try { drawRect(cg, bounds) } finally { CGContext.current = prev }
        canvas.restore()
    }

    override fun dispatchDraw(canvas: Canvas) {
        val clip = clipsToBounds || layer.masksToBounds
        val fw = _frame.width.toFloat(); val fh = _frame.height.toFloat()
        val kx = if (fw > 0) width / fw else 1f; val ky = if (fh > 0) height / fh else 1f
        val r = layer.cornerRadius.toFloat().coerceAtMost(minOf(fw, fh) / 2).coerceAtLeast(0f)
        if (clip) { canvas.save(); if (r <= 0f) canvas.clipRect(0, 0, width, height) else { clipPath2.reset(); clipPath2.addRoundRect(0f, 0f, width.toFloat(), height.toFloat(), r * kx, r * ky, Path.Direction.CW); canvas.clipPath(clipPath2) } }
        super.dispatchDraw(canvas)
        if (clip) canvas.restore()
        val bc = layer.borderColor
        if (layer.borderWidth > 0 && bc != null && bc.a > 0 && fw > 0 && fh > 0) {
            canvas.save(); canvas.scale(kx, ky)
            val bw = layer.borderWidth.toFloat()
            BD.strokeWidth = bw; BD.color = bc.argb
            val rr = (r - bw / 2).coerceAtLeast(0f)
            canvas.drawRoundRect(bw / 2, bw / 2, fw - bw / 2, fh - bw / 2, rr, rr, BD)
            canvas.restore()
        }
    }

    // ---- tocchi: stesse regole di UIKit (vince la vista più in alto con userInteractionEnabled) ----
    open fun pointInside(p: CGPoint): Boolean = p.x >= 0 && p.y >= 0 && p.x < _frame.width && p.y < _frame.height
    open fun hitTest(p: CGPoint): UIView? {
        if (hidden || !userInteractionEnabled || alpha < 0.01f) return null
        if (!pointInside(p)) return null
        val sh = childShift
        for (i in childCount - 1 downTo 0) {
            val c = getChildAt(i) as? UIView ?: continue
            c.hitTest(CGPoint(p.x + sh.x - c._frame.x - c.transform.tx, p.y + sh.y - c._frame.y - c.transform.ty))?.let { return it }
        }
        return this
    }
    fun convertPoint(p: CGPoint, toView: UIView?): CGPoint {
        val a = originInRoot(); val b = toView?.originInRoot() ?: CGPoint()
        return CGPoint(p.x + a.x - b.x, p.y + a.y - b.y)
    }
    fun convertPointFromView(p: CGPoint, fromView: UIView?): CGPoint = (fromView ?: window ?: this).convertPoint(p, this)
    fun convertRect(r: CGRect, toView: UIView?): CGRect { val o = convertPoint(r.origin, toView); return CGRect(o.x, o.y, r.width, r.height) }
    private fun originInRoot(): CGPoint {
        var x = 0.0; var y = 0.0; var v: UIView? = this
        while (v != null) { val s = v.superview; x += v._frame.x; y += v._frame.y; if (s != null) { val sh = s.childShift; x -= sh.x; y -= sh.y }; v = s }
        return CGPoint(x, y)
    }

    open fun touchesBegan(p: CGPoint) { superview?.touchesBegan(CGPoint(p.x + _frame.x, p.y + _frame.y)) }
    open fun touchesMoved(p: CGPoint) { superview?.touchesMoved(CGPoint(p.x + _frame.x, p.y + _frame.y)) }
    open fun touchesEnded(p: CGPoint) { superview?.touchesEnded(CGPoint(p.x + _frame.x, p.y + _frame.y)) }
    open fun touchesCancelled(p: CGPoint) { superview?.touchesCancelled(CGPoint(p.x + _frame.x, p.y + _frame.y)) }

    /** true = questa vista contiene un controllo Android vero (campo di testo…): i tocchi vanno a lui */
    open val isNativeHost: Boolean get() = false

    // la vista radice (senza UIView sopra) smista i tocchi con le regole di UIKit
    private var touchTarget: UIView? = null
    private var nativeGesture = false
    // scorrimento: se il dito si sposta di più di 10 punti sopra un UIScrollView, il tocco passa a lui (come UIKit)
    private var scroller: UIScrollView? = null
    private var downAt = CGPoint()
    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (superview != null) return super.dispatchTouchEvent(ev)
        val k = if (width > 0) _frame.width / width else 1.0
        val p = CGPoint(ev.x * k, ev.y * k)
        val t = ev.eventTime / 1000.0
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val hit = hitTest(p)
                nativeGesture = hit?.isNativeHost == true
                scroller = null; downAt = p
                if (nativeGesture) { touchTarget = null; return super.dispatchTouchEvent(ev) }
                // un elenco che sta ancora scorrendo si ferma al tocco, e il tocco è suo (non preme il pulsante sotto)
                val moving = hit?.let { UIScrollView.deceleratingAncestor(it) }
                if (moving != null) { touchTarget = null; scroller = moving; moving.panBegan(p, t); return true }
                touchTarget = hit
                hit?.touchesBegan(convertPoint(p, hit))
            }
            MotionEvent.ACTION_MOVE -> {
                if (nativeGesture) return super.dispatchTouchEvent(ev)
                val sc = scroller
                if (sc != null) { sc.panMoved(p, t); return true }
                val tt = touchTarget ?: return true
                val dx = p.x - downAt.x; val dy = p.y - downAt.y
                if (dx * dx + dy * dy > 100) {
                    val cand = UIScrollView.scrollerFor(tt, dx, dy)
                    if (cand != null) {
                        tt.touchesCancelled(convertPoint(p, tt)); touchTarget = null
                        scroller = cand; cand.panBegan(p, t); return true
                    }
                }
                tt.touchesMoved(convertPoint(p, tt))
            }
            MotionEvent.ACTION_UP -> {
                if (nativeGesture) return super.dispatchTouchEvent(ev)
                val sc = scroller
                if (sc != null) { sc.panEnded(p, t); scroller = null; return true }
                touchTarget?.let { it.touchesEnded(convertPoint(p, it)) }; touchTarget = null
            }
            MotionEvent.ACTION_CANCEL -> {
                if (nativeGesture) return super.dispatchTouchEvent(ev)
                val sc = scroller
                if (sc != null) { sc.panEnded(p, t); scroller = null; return true }
                touchTarget?.let { it.touchesCancelled(convertPoint(p, it)) }; touchTarget = null
            }
            else -> if (nativeGesture) return super.dispatchTouchEvent(ev)
        }
        return true
    }

    /** Fotografia della vista (drawViewHierarchyInRect / UIGraphicsGetImageFromCurrentImageContext). */
    fun snapshotBitmap(): Bitmap {
        val b = Bitmap.createBitmap(maxOf(width, 1), maxOf(height, 1), Bitmap.Config.ARGB_8888)
        draw(Canvas(b)); return b
    }

    companion object {
        val ZERO_SHIFT = CGPoint()
        private val BG = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
        private val BD = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
        private val SH = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }

        // ---- animazioni: dentro il blocco, frame / alpha / transform / backgroundColor vanno al nuovo valore gradualmente ----
        internal class Anim { val steps = ArrayList<(Double) -> Unit>(); fun add(s: (Double) -> Unit) { steps.add(s) } }
        internal var animating: Anim? = null

        fun animateWithDuration(duration: Double, animations: () -> Unit) = animateWithDuration(duration, 0.0, 0, animations, null)
        fun animateWithDuration(duration: Double, animations: () -> Unit, completion: ((Boolean) -> Unit)?) = animateWithDuration(duration, 0.0, 0, animations, completion)
        fun animateWithDuration(duration: Double, delay: Double, options: Int, animations: () -> Unit, completion: ((Boolean) -> Unit)? = null) =
            run(duration, delay, if (options and UIViewAnimationOptionCurveLinear == UIViewAnimationOptionCurveLinear) 2 else if (options and UIViewAnimationOptionCurveEaseOut != 0) 1 else 0, animations, completion)
        /** animateWithDuration:delay:usingSpringWithDamping:initialSpringVelocity:options:animations:completion: */
        fun animateWithDuration(duration: Double, delay: Double, usingSpringWithDamping: Double, initialSpringVelocity: Double, options: Int, animations: () -> Unit, completion: ((Boolean) -> Unit)? = null) =
            run(duration, delay, 3, animations, completion)

        private fun run(duration: Double, delay: Double, curve: Int, animations: () -> Unit, completion: ((Boolean) -> Unit)?) {
            val a = Anim(); val prev = animating; animating = a
            try { animations() } finally { animating = prev }
            if (a.steps.isEmpty() || duration <= 0) { for (s in a.steps) s(1.0); if (completion != null) Uk.after(maxOf(duration, 0.0) + delay) { completion(true) }; return }
            val va = ValueAnimator.ofFloat(0f, 1f)
            va.duration = (duration * 1000).toLong(); va.startDelay = (delay * 1000).toLong()
            va.interpolator = when (curve) { 1 -> DecelerateInterpolator(); 2 -> LinearInterpolator(); 3 -> OvershootInterpolator(2.2f); else -> android.view.animation.AccelerateDecelerateInterpolator() }
            va.addUpdateListener { val t = (it.animatedValue as Float).toDouble(); for (s in a.steps) s(t) }
            va.addListener(object : AnimatorListenerAdapter() { override fun onAnimationEnd(animation: Animator) { completion?.invoke(true) } })
            va.start()
        }
    }
}

/** Animazione libera a tempo (al posto di CABasicAnimation / CAKeyframeAnimation): `step` riceve la frazione 0..1. */
class UkAnimation(val duration: Double, val repeats: Boolean = false, val step: (Double) -> Unit) {
    private var va: ValueAnimator? = null
    fun start(): UkAnimation {
        va?.cancel()
        va = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = (this@UkAnimation.duration * 1000).toLong(); interpolator = LinearInterpolator()
            if (repeats) { repeatCount = ValueAnimator.INFINITE; repeatMode = ValueAnimator.RESTART }
            addUpdateListener { step((it.animatedValue as Float).toDouble()) }
            start()
        }
        return this
    }
    fun stop() { va?.cancel(); va = null }
}

/**
 * Vista radice: adatta la tela di progetto (in punti) all'altezza dello schermo e la centra.
 * Una sola gerarchia UIKit larga quanto lo schermo: fascia sinistra | tela di progetto | fascia destra.
 * Così tocchi, hitTest, convertPoint e fotografie valgono anche per le fasce laterali.
 */
class UkRootView(val designWidth: Double, val designHeight: Double) : ViewGroup(Uk.ctx) {
    /** larghezza di ciascuna fascia laterale, in punti (0 se lo schermo ha le stesse proporzioni della tela) */
    val bandWidth: Double = bandPt
    val root = UIView(CGRect(0.0, 0.0, designWidth + 2 * bandWidth, designHeight))
    val leftBand = UIView(CGRect(0.0, 0.0, bandWidth, designHeight))
    val rightBand = UIView(CGRect(bandWidth + designWidth, 0.0, bandWidth, designHeight))
    /** la tela di progetto (1024×768 punti): qui dentro va tutto ciò che c'era sull'iPad */
    val canvas = UIView(CGRect(bandWidth, 0.0, designWidth, designHeight))
    init {
        setBackgroundColor(0xFF000000.toInt()); clipChildren = true
        root.backgroundColor = UIColor.blackColor
        canvas.clipsToBounds = true
        root.addSubview(leftBand); root.addSubview(rightBand); root.addSubview(canvas)
        addView(root)
    }
    override fun onMeasure(w: Int, h: Int) { setMeasuredDimension(MeasureSpec.getSize(w), MeasureSpec.getSize(h)) }
    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val w = Uk.px(designWidth + 2 * bandWidth); val h = Uk.px(designHeight)
        val x = ((r - l) - w) / 2; val y = ((b - t) - h) / 2
        root.layout(x, y, x + w, y + h)
    }
    companion object {
        private var bandPt = 0.0
        /** Da chiamare prima di creare qualunque vista: sceglie la scala che fa stare la tela nello schermo. */
        fun prepare(screenWpx: Int, screenHpx: Int, designWidth: Double, designHeight: Double) {
            Uk.scale = minOf(screenWpx / designWidth, screenHpx / designHeight).toFloat()
            bandPt = maxOf(0.0, Math.floor((screenWpx / Uk.scale - designWidth) / 2))
        }
    }
}

class UIImage(val bitmap: Bitmap) { val size get() = CGSize(bitmap.width / Uk.scale.toDouble(), bitmap.height / Uk.scale.toDouble()) }
class UIImageView(frame: CGRect = CGRect()) : UIView(frame) {
    var image: UIImage? = null; set(v) { field = v; invalidate() }
    private val ip = Paint(Paint.FILTER_BITMAP_FLAG)
    init { userInteractionEnabled = false }
    override fun drawRect(ctx: CGContext, rect: CGRect) {
        val b = image?.bitmap ?: return
        ctx.canvas.drawBitmap(b, null, android.graphics.RectF(0f, 0f, rect.width.toFloat(), rect.height.toFloat()), ip)
    }
}
