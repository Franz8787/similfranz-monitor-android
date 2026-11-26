// UIScrollView, UITableView, UITextView, UIActivityIndicatorView, UIViewController e contesto grafico su immagine.
// Aggiunti per il controller (il monitor non li usava). Stesse regole del resto di uk: punti, stessi nomi di UIKit.
package it.simulafranz.uk

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.text.Editable
import android.text.InputFilter
import android.text.InputType
import android.text.TextWatcher
import android.util.TypedValue
import android.view.Choreographer
import android.view.Gravity
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min

const val UIScrollViewIndicatorStyleDefault = 0
const val UIScrollViewIndicatorStyleBlack = 1
const val UIScrollViewIndicatorStyleWhite = 2
const val UIScrollViewKeyboardDismissModeNone = 0
const val UIScrollViewKeyboardDismissModeOnDrag = 1

/**
 * UIScrollView. I figli si aggiungono direttamente (come su iOS) e si vedono spostati di `contentOffset`.
 * Tocchi come UIKit: un pulsante dentro l'elenco riceve subito il tocco; se il dito si sposta di più di 10 punti
 * e `touchesShouldCancelInContentView` lo consente, il pulsante riceve "annullato" e l'elenco scorre.
 * Dopo il rilascio continua per inerzia; ai bordi rimbalza (`bounces`).
 */
open class UIScrollView(frame: CGRect = CGRect()) : UIView(frame) {
    var contentSize: CGSize = CGSize()
        set(v) { field = v; invalidate() }
    private var offset = CGPoint()
    var contentOffset: CGPoint
        get() = offset
        set(v) { setOffsetNow(v) }
    var contentInset: UIEdgeInsets = UIEdgeInsets()
    var scrollIndicatorInsets: UIEdgeInsets = UIEdgeInsets()
    var indicatorStyle: Int = UIScrollViewIndicatorStyleDefault
    var showsVerticalScrollIndicator = true
    var showsHorizontalScrollIndicator = true
    var scrollEnabled = true
    var bounces = true
    var alwaysBounceVertical = false
    var alwaysBounceHorizontal = false
    var delaysContentTouches = true            // accettato per compatibilità: qui i tocchi arrivano sempre subito
    var canCancelContentTouches = true
    var keyboardDismissMode: Int = UIScrollViewKeyboardDismissModeNone
    /** scrollViewDidScroll: */
    var onScroll: ((UIScrollView) -> Unit)? = null

    override val childShift: CGPoint get() = offset

    init { clipsToBounds = true }

    /** Di serie (UIKit): un controllo trattiene il tocco, il resto lascia scorrere. CtlScrollView lo cambia. */
    open fun touchesShouldCancelInContentView(view: UIView): Boolean = view !is UIControl

    private fun minX() = -contentInset.left
    private fun minY() = -contentInset.top
    private fun maxX() = max(minX(), contentSize.width + contentInset.right - bounds.width)
    private fun maxY() = max(minY(), contentSize.height + contentInset.bottom - bounds.height)
    internal fun canScrollX() = scrollEnabled && (contentSize.width + contentInset.left + contentInset.right > bounds.width + 0.5 || alwaysBounceHorizontal)
    internal fun canScrollY() = scrollEnabled && (contentSize.height + contentInset.top + contentInset.bottom > bounds.height + 0.5 || alwaysBounceVertical)

    private fun setOffsetNow(v: CGPoint) {
        if (v == offset) return
        offset = v
        for (i in 0 until childCount) (getChildAt(i) as? UIView)?.applyFrame()
        invalidate()
        scrolled()
        onScroll?.invoke(this)
    }
    /** per le sottoclassi (UITableView: crea le righe che entrano in vista) */
    protected open fun scrolled() {}

    fun setContentOffset(p: CGPoint, animated: Boolean) {
        stopDeceleration()
        if (!animated) { contentOffset = p; return }
        val from = offset
        UkAnimation(0.3) { t -> val e = 1 - (1 - t) * (1 - t); setOffsetNow(CGPoint(from.x + (p.x - from.x) * e, from.y + (p.y - from.y) * e)) }.start()
    }

    fun scrollRectToVisible(r: CGRect, animated: Boolean) {
        var x = offset.x; var y = offset.y
        val w = bounds.width; val h = bounds.height
        if (r.maxY > y + h) y = r.maxY - h
        if (r.y < y) y = r.y
        if (r.maxX > x + w) x = r.maxX - w
        if (r.x < x) x = r.x
        x = x.coerceIn(minX(), maxX()); y = y.coerceIn(minY(), maxY())
        setContentOffset(CGPoint(x, y), animated)
    }

    fun flashScrollIndicators() { indicatorUntil = CACurrentMediaTime() + 0.8; invalidate(); Uk.after(0.85) { invalidate() } }

    // ---- trascinamento e inerzia ----
    private var panStart = CGPoint(); private var offStart = CGPoint()
    private var lockX = false; private var lockY = false
    private val samples = ArrayList<Triple<Double, Double, Double>>()     // tempo, x, y
    private var vx = 0.0; private var vy = 0.0; private var lastFrame = 0L
    private var decelerating = false
    val isDecelerating: Boolean get() = decelerating
    var isDragging = false; private set
    private var indicatorUntil = 0.0

    private fun rubber(v: Double, lo: Double, hi: Double): Double =
        if (!bounces) v.coerceIn(lo, hi) else if (v < lo) lo - (lo - v) * 0.5 else if (v > hi) hi + (v - hi) * 0.5 else v

    internal fun panBegan(p: CGPoint, t: Double) {
        stopDeceleration()
        isDragging = true
        panStart = p; offStart = offset
        lockX = canScrollX(); lockY = canScrollY()
        samples.clear(); samples.add(Triple(t, p.x, p.y))
        if (keyboardDismissMode == UIScrollViewKeyboardDismissModeOnDrag) hideKeyboard()
    }
    internal fun panMoved(p: CGPoint, t: Double) {
        val nx = if (lockX) rubber(offStart.x - (p.x - panStart.x), minX(), maxX()) else offset.x
        val ny = if (lockY) rubber(offStart.y - (p.y - panStart.y), minY(), maxY()) else offset.y
        samples.add(Triple(t, p.x, p.y)); while (samples.size > 2 && t - samples[0].first > 0.1) samples.removeAt(0)
        indicatorUntil = CACurrentMediaTime() + 0.6
        setOffsetNow(CGPoint(nx, ny))
    }
    internal fun panEnded(p: CGPoint, t: Double) {
        isDragging = false
        samples.add(Triple(t, p.x, p.y)); while (samples.size > 2 && t - samples[0].first > 0.1) samples.removeAt(0)
        val a = samples.first(); val b = samples.last(); val dt = b.first - a.first
        vx = if (lockX && dt > 0.005) -(b.second - a.second) / dt else 0.0      // punti al secondo, nel verso del contenuto
        vy = if (lockY && dt > 0.005) -(b.third - a.third) / dt else 0.0
        if (t - b.first > 0.05) { vx = 0.0; vy = 0.0 }
        startDeceleration()
    }

    private val frameCb = object : Choreographer.FrameCallback {
        override fun doFrame(now: Long) {
            if (!decelerating) return
            val dt = if (lastFrame == 0L) 1.0 / 60 else ((now - lastFrame) / 1e9).coerceIn(0.001, 0.05)
            lastFrame = now
            var x = offset.x; var y = offset.y
            var done = true
            fun axis(pos: Double, v0: Double, lo: Double, hi: Double): Pair<Double, Double> {
                var v = v0; var q = pos
                if (q < lo || q > hi) {
                    // fuori dal bordo: molla verso il bordo
                    val target = if (q < lo) lo else hi
                    q += (target - q) * min(1.0, dt * 12); v = 0.0
                    if (abs(target - q) < 0.5) q = target else done = false
                } else {
                    v *= exp(-dt * 2.0 * 1.3)                 // attrito simile a UIScrollViewDecelerationRateNormal
                    q += v * dt
                    if (q < lo || q > hi) { if (!bounces) { q = q.coerceIn(lo, hi); v = 0.0 } else v *= 0.3 }
                    if (abs(v) > 12) done = false else v = 0.0
                    if (q < lo || q > hi) done = false
                }
                return Pair(q, v)
            }
            val ax = axis(x, vx, minX(), maxX()); x = ax.first; vx = ax.second
            val ay = axis(y, vy, minY(), maxY()); y = ay.first; vy = ay.second
            indicatorUntil = CACurrentMediaTime() + 0.5
            setOffsetNow(CGPoint(x, y))
            if (done) { decelerating = false; lastFrame = 0; Uk.after(0.55) { invalidate() } }
            else Choreographer.getInstance().postFrameCallback(this)
        }
    }
    private fun startDeceleration() {
        decelerating = true; lastFrame = 0
        Choreographer.getInstance().removeFrameCallback(frameCb)
        Choreographer.getInstance().postFrameCallback(frameCb)
    }
    fun stopDeceleration() {
        if (!decelerating) return
        decelerating = false; Choreographer.getInstance().removeFrameCallback(frameCb)
        val x = offset.x.coerceIn(minX(), maxX()); val y = offset.y.coerceIn(minY(), maxY())
        if (x != offset.x || y != offset.y) setOffsetNow(CGPoint(x, y))
    }
    override fun onDetachedFromWindow() { stopDeceleration(); super.onDetachedFromWindow() }

    private fun hideKeyboard() {
        val f = findFocus() ?: return
        (Uk.ctx.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager)?.hideSoftInputFromWindow(f.windowToken, 0)
        f.clearFocus()
    }

    // ---- indicatore di scorrimento (barra sottile a destra, mentre si scorre) ----
    private val ind = Paint(Paint.ANTI_ALIAS_FLAG)
    override fun dispatchDraw(canvas: Canvas) {
        super.dispatchDraw(canvas)
        if (!showsVerticalScrollIndicator || CACurrentMediaTime() > indicatorUntil) return
        val h = bounds.height; val ch = contentSize.height + contentInset.top + contentInset.bottom
        if (ch <= h + 0.5 || h <= 0) return
        val k = if (bounds.width > 0) width / bounds.width.toFloat() else 1f
        val ih = h - scrollIndicatorInsets.top - scrollIndicatorInsets.bottom - 6
        val bar = max(36.0, ih * h / ch)
        val frac = ((offset.y - minY()) / (maxY() - minY())).coerceIn(0.0, 1.0)
        val y0 = scrollIndicatorInsets.top + 3 + (ih - bar) * frac
        ind.color = if (indicatorStyle == UIScrollViewIndicatorStyleWhite) 0x99FFFFFF.toInt() else 0x80000000.toInt()
        val x1 = ((bounds.width - 3) * k).toFloat(); val x0 = x1 - 3f * k
        canvas.drawRoundRect(RectF(x0, (y0 * k).toFloat(), x1, ((y0 + bar) * k).toFloat()), 1.5f * k, 1.5f * k, ind)
    }

    companion object {
        /** l'elenco più vicino che sta scorrendo per inerzia, sopra la vista toccata (o la vista stessa) */
        internal fun deceleratingAncestor(v: UIView): UIScrollView? {
            var p: UIView? = v
            while (p != null) { if (p is UIScrollView && p.decelerating) return p; p = p.superview }
            return null
        }
        /** a chi passa il tocco quando il dito si sposta di (dx, dy): l'elenco più vicino che scorre in quel verso */
        internal fun scrollerFor(v: UIView, dx: Double, dy: Double): UIScrollView? {
            val vertical = abs(dy) >= abs(dx)
            var p: UIView? = v
            while (p != null) {
                if (p is UIScrollView && (if (vertical) p.canScrollY() else p.canScrollX())) {
                    if (p === v || (p.canCancelContentTouches && p.touchesShouldCancelInContentView(v))) return p
                    return null       // l'elenco giusto c'è, ma il controllo trattiene il tocco
                }
                p = p.superview
            }
            return null
        }
    }
}

// ---------------------------------------------------------------------------------------------------------------------
// UITableView (semplice: una sezione, righe di altezza fissa `rowHeight` o da `heightForRowAtIndexPath`).
// Le celle si creano solo per le righe visibili e si riusano (dequeueReusableCellWithIdentifier).

class NSIndexPath(val row: Int, val section: Int = 0) {
    companion object { fun indexPathForRow(row: Int, inSection: Int = 0) = NSIndexPath(row, inSection) }
}
const val UITableViewStylePlain = 0
const val UITableViewStyleGrouped = 1
const val UITableViewCellStyleDefault = 0
const val UITableViewCellSelectionStyleNone = 0
const val UITableViewCellSelectionStyleDefault = 3
const val UITableViewScrollPositionNone = 0
const val UITableViewScrollPositionTop = 1
const val UITableViewScrollPositionMiddle = 2
const val UITableViewScrollPositionBottom = 3

interface UITableViewDataSource {
    fun numberOfRowsInSection(tableView: UITableView, section: Int): Int
    fun cellForRowAtIndexPath(tableView: UITableView, indexPath: NSIndexPath): UITableViewCell
}
interface UITableViewDelegate {
    fun heightForRowAtIndexPath(tableView: UITableView, indexPath: NSIndexPath): Double? = null
    fun didSelectRowAtIndexPath(tableView: UITableView, indexPath: NSIndexPath) {}
}

open class UITableViewCell(val style: Int = UITableViewCellStyleDefault, val reuseIdentifier: String? = null) : UIControl(CGRect()) {
    val contentView = UIView(CGRect())
    var selectionStyle: Int = UITableViewCellSelectionStyleDefault
    var separatorInset = UIEdgeInsets()
    var layoutMargins = UIEdgeInsets()
    var preservesSuperviewLayoutMargins = true
    internal var separatorColor: UIColor? = null
    internal var row = -1
    init { contentView.userInteractionEnabled = false; addSubview(contentView); backgroundColor = UIColor.clearColor }
    override fun layoutSubviews() { contentView.frame = bounds }
    open fun prepareForReuse() {}
    private val sep = Paint()
    override fun dispatchDraw(canvas: Canvas) {
        super.dispatchDraw(canvas)
        val c = separatorColor ?: return
        if (c.a <= 0) return
        sep.color = c.argb
        val k = if (bounds.width > 0) width / bounds.width.toFloat() else 1f
        canvas.drawRect((separatorInset.left * k).toFloat(), height - max(1f, 0.5f * k), width - (separatorInset.right * k).toFloat(), height.toFloat(), sep)
    }
    override fun stateChanged() {
        if (selectionStyle != UITableViewCellSelectionStyleNone) alpha = if (highlighted) 0.6f else 1f
        super.stateChanged()
    }
}

class UITableView(frame: CGRect = CGRect(), val tableStyle: Int = UITableViewStylePlain) : UIScrollView(frame) {
    var dataSource: UITableViewDataSource? = null
    var delegate: UITableViewDelegate? = null
    var rowHeight: Double = 44.0
    var separatorColor: UIColor? = UIColor.colorWithWhite(1, 0.12)
    var separatorInset = UIEdgeInsets()
    var layoutMargins = UIEdgeInsets()
    var cellLayoutMarginsFollowReadableWidth = false
    var tableFooterView: UIView? = null
    private val factories = HashMap<String, () -> UITableViewCell>()
    private val pool = HashMap<String, ArrayList<UITableViewCell>>()
    private val visible = HashMap<Int, UITableViewCell>()
    private var tops = DoubleArray(1)          // tops[i] = inizio della riga i; tops[n] = altezza totale
    private var count = 0

    init { alwaysBounceVertical = true; showsHorizontalScrollIndicator = false }

    /** registerClass:forCellReuseIdentifier: — qui si passa chi crea la cella */
    fun registerClass(identifier: String, factory: () -> UITableViewCell) { factories[identifier] = factory }

    fun dequeueReusableCellWithIdentifier(identifier: String, forIndexPath: NSIndexPath? = null): UITableViewCell {
        val l = pool[identifier]
        if (l != null && l.isNotEmpty()) { val c = l.removeAt(l.size - 1); c.prepareForReuse(); return c }
        val f = factories[identifier] ?: return UITableViewCell(UITableViewCellStyleDefault, identifier)
        return f()
    }

    fun reloadData() {
        val ds = dataSource
        count = ds?.numberOfRowsInSection(this, 0) ?: 0
        tops = DoubleArray(count + 1)
        var y = 0.0
        for (i in 0 until count) { tops[i] = y; y += delegate?.heightForRowAtIndexPath(this, NSIndexPath(i)) ?: rowHeight }
        tops[count] = y
        for ((_, c) in visible) recycle(c)
        visible.clear()
        contentSize = CGSize(bounds.width, y)
        val mx = max(0.0, y - bounds.height)
        if (contentOffset.y > mx) contentOffset = CGPoint(0.0, mx) else fill()
    }

    fun numberOfRowsInSection(section: Int): Int = count
    fun rectForRowAtIndexPath(ip: NSIndexPath): CGRect = if (ip.row in 0 until count) CGRect(0.0, tops[ip.row], bounds.width, tops[ip.row + 1] - tops[ip.row]) else CGRect()
    fun cellForRowAtIndexPath(ip: NSIndexPath): UITableViewCell? = visible[ip.row]
    val visibleCells: List<UITableViewCell> get() = visible.entries.sortedBy { it.key }.map { it.value }

    fun scrollToRowAtIndexPath(ip: NSIndexPath, atScrollPosition: Int, animated: Boolean) {
        if (ip.row !in 0 until count) return
        val r = rectForRowAtIndexPath(ip)
        val mx = max(0.0, contentSize.height - bounds.height)
        val y = when (atScrollPosition) {
            UITableViewScrollPositionTop -> r.y
            UITableViewScrollPositionMiddle -> r.midY - bounds.height / 2
            UITableViewScrollPositionBottom -> r.maxY - bounds.height
            else -> { scrollRectToVisible(r, animated); return }
        }.coerceIn(0.0, mx)
        setContentOffset(CGPoint(0.0, y), animated)
    }

    private fun recycle(c: UITableViewCell) {
        c.removeFromSuperview(); c.row = -1
        val id = c.reuseIdentifier ?: return
        pool.getOrPut(id) { ArrayList() }.add(c)
    }

    override fun scrolled() { fill() }
    override fun layoutSubviews() { fill() }

    private fun rowAt(y: Double): Int {
        var lo = 0; var hi = count
        while (lo < hi) { val m = (lo + hi) / 2; if (tops[m + 1] <= y) lo = m + 1 else hi = m }
        return lo
    }
    private fun fill() {
        val ds = dataSource ?: return
        if (count == 0) return
        val y0 = contentOffset.y - 40; val y1 = contentOffset.y + bounds.height + 40
        val first = rowAt(max(0.0, y0)); val last = min(count - 1, rowAt(y1))
        for (r in visible.keys.toList()) if (r < first || r > last) recycle(visible.remove(r)!!)
        for (r in first..last) {
            if (visible.containsKey(r)) continue
            val c = ds.cellForRowAtIndexPath(this, NSIndexPath(r))
            c.row = r; c.separatorColor = separatorColor; c.separatorInset = separatorInset
            c.frame = CGRect(0.0, tops[r], bounds.width, tops[r + 1] - tops[r])
            c.removeAllTargets()
            c.addTarget(UIControlEventTouchUpInside) { delegate?.didSelectRowAtIndexPath(this, NSIndexPath(c.row)) }
            visible[r] = c
            addSubview(c)
        }
    }
}

// ---------------------------------------------------------------------------------------------------------------------
/**
 * UITextView: testo su più righe modificabile (ospita un EditText di Android).
 * Al posto del delegato: `onChange` (textViewDidChange:), `onReturn` (Invio; se impostato l'a capo non viene inserito),
 * `maxLength` (shouldChangeTextInRange: con un limite di lunghezza).
 */
open class UITextView(frame: CGRect = CGRect()) : UIView(frame) {
    val editText = EditText(Uk.ctx)
    private var settingText = false
    var text: String?
        get() = editText.text?.toString() ?: ""
        set(v) { settingText = true; editText.setText(v ?: ""); settingText = false }
    var font: UIFont = UIFont.systemFontOfSize(12)
        set(v) { field = v; editText.setTextSize(TypedValue.COMPLEX_UNIT_PX, (v.pointSize * Uk.scale).toFloat()); editText.typeface = v.typeface }
    var textColor: UIColor = UIColor.blackColor
        set(v) { field = v; editText.setTextColor(v.argb) }
    var textContainerInset: UIEdgeInsets = UIEdgeInsetsMake(8, 0, 8, 0)
        set(v) { field = v; requestLayout() }
    var editable = true
        set(v) { field = v; editText.isFocusable = v; editText.isFocusableInTouchMode = v }
    var keyboardAppearance: Int = 0
    var maxLength: Int = 0
        set(v) { field = v; editText.filters = if (v > 0) arrayOf(InputFilter.LengthFilter(v)) else arrayOf() }
    var onChange: ((UITextView) -> Unit)? = null
    var onReturn: ((UITextView) -> Unit)? = null
    val isFirstResponder: Boolean get() = editText.hasFocus()
    override val isNativeHost: Boolean get() = true

    init {
        editText.background = null
        editText.setPadding(0, 0, 0, 0)
        editText.gravity = Gravity.TOP or Gravity.START
        editText.includeFontPadding = false
        editText.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        editText.imeOptions = EditorInfo.IME_FLAG_NO_EXTRACT_UI or EditorInfo.IME_FLAG_NO_FULLSCREEN
        editText.setTextColor(textColor.argb)
        font = font
        editText.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                if (settingText) return
                val h = onReturn
                if (h != null && s != null) { val i = s.indexOf('\n'); if (i >= 0) { settingText = true; s.delete(i, i + 1); settingText = false; h(this@UITextView); return } }
                onChange?.invoke(this@UITextView)
            }
        })
        addView(editText, LayoutParams(1, 1))
    }
    fun becomeFirstResponder(): Boolean {
        val ok = editText.requestFocus()
        editText.post { (Uk.ctx.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager)?.showSoftInput(editText, InputMethodManager.SHOW_IMPLICIT) }
        return ok
    }
    fun resignFirstResponder(): Boolean {
        (Uk.ctx.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager)?.hideSoftInputFromWindow(editText.windowToken, 0)
        editText.clearFocus(); return true
    }
    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val i = textContainerInset
        val x0 = Uk.px(i.left); val y0 = Uk.px(i.top)
        val w = max(0, (r - l) - x0 - Uk.px(i.right)); val h = max(0, (b - t) - y0 - Uk.px(i.bottom))
        editText.layoutParams?.let { it.width = w; it.height = h }
        editText.measure(MeasureSpec.makeMeasureSpec(w, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(h, MeasureSpec.EXACTLY))
        editText.layout(x0, y0, x0 + w, y0 + h)
    }
}

// ---------------------------------------------------------------------------------------------------------------------
const val UIActivityIndicatorViewStyleWhiteLarge = 0
const val UIActivityIndicatorViewStyleWhite = 1
const val UIActivityIndicatorViewStyleGray = 2

/** Rotellina di attesa (12 raggi che sfumano, come su iOS). */
class UIActivityIndicatorView(val activityIndicatorViewStyle: Int = UIActivityIndicatorViewStyleWhite) :
    UIView(CGRect(0.0, 0.0, if (activityIndicatorViewStyle == UIActivityIndicatorViewStyleWhiteLarge) 37.0 else 20.0, if (activityIndicatorViewStyle == UIActivityIndicatorViewStyleWhiteLarge) 37.0 else 20.0)) {
    var color: UIColor = if (activityIndicatorViewStyle == UIActivityIndicatorViewStyleGray) UIColor.grayColor else UIColor.whiteColor
    var hidesWhenStopped = true
    private var anim: UkAnimation? = null
    private var phase = 0.0
    val isAnimating: Boolean get() = anim != null
    init { userInteractionEnabled = false; hidden = true }
    fun startAnimating() { if (anim != null) return; hidden = false; anim = UkAnimation(1.0, true) { t -> phase = t; invalidate() }.start() }
    fun stopAnimating() { anim?.stop(); anim = null; if (hidesWhenStopped) hidden = true }
    override fun onDetachedFromWindow() { anim?.stop(); super.onDetachedFromWindow() }
    override fun onAttachedToWindow() { super.onAttachedToWindow(); if (anim != null) { anim = null; startAnimating() } }
    override fun drawRect(ctx: CGContext, rect: CGRect) {
        val s = min(rect.width, rect.height); val cx = rect.width / 2; val cy = rect.height / 2
        val lw = s * 0.09; val r0 = s * 0.24; val r1 = s * 0.46
        ctx.setLineWidth(lw); ctx.setLineCap(kCGLineCapRound)
        val head = (phase * 12).toInt()
        for (i in 0 until 12) {
            val a = -Math.PI / 2 + i * Math.PI / 6
            val age = ((head - i) % 12 + 12) % 12
            ctx.setStrokeColor(color.colorWithAlphaComponent(color.a * (1.0 - age / 12.0 * 0.85)))
            ctx.beginPath(); ctx.moveToPoint(cx + r0 * Math.cos(a), cy + r0 * Math.sin(a)); ctx.addLineToPoint(cx + r1 * Math.cos(a), cy + r1 * Math.sin(a)); ctx.strokePath()
        }
    }
}

// ---------------------------------------------------------------------------------------------------------------------
/**
 * UIViewController minimo: `view` (tela 1024×768 di serie), viewDidLoad, presentazione modale a tutto schermo.
 * `presentViewController` mette la vista del controller presentato sopra quella di chi presenta (stessa tela),
 * `dismissViewControllerAnimated` la toglie. `UIViewController.host` è la tela dove compare il primo controller.
 */
open class UIViewController {
    private var loaded: UIView? = null
    val view: UIView
        get() { loaded?.let { return it }; val v = UIView(CGRect(0.0, 0.0, designWidth, designHeight)); loaded = v; loadView(v); viewDidLoad(); return v }
    val isViewLoaded: Boolean get() = loaded != null
    var presentedViewController: UIViewController? = null; private set
    var presentingViewController: UIViewController? = null; private set

    open fun loadView(v: UIView) {}
    open fun viewDidLoad() {}
    open fun viewWillAppear(animated: Boolean) {}
    open fun viewDidAppear(animated: Boolean) {}
    open fun viewWillDisappear(animated: Boolean) {}
    open fun viewDidDisappear(animated: Boolean) {}

    fun presentViewController(vc: UIViewController, animated: Boolean, completion: (() -> Unit)? = null) {
        val parent = view.superview ?: host ?: return
        presentedViewController = vc; vc.presentingViewController = this
        val v = vc.view
        v.frame = CGRect(0.0, if (animated) designHeight else 0.0, designWidth, designHeight)
        vc.viewWillAppear(animated)
        parent.addSubview(v)
        if (animated) UIView.animateWithDuration(0.3, 0.0, UIViewAnimationOptionCurveEaseOut, { v.frame = CGRect(0.0, 0.0, designWidth, designHeight) }) { vc.viewDidAppear(true); completion?.invoke() }
        else { vc.viewDidAppear(false); completion?.invoke() }
    }
    fun presentViewController(a: UIAlertController, animated: Boolean, completion: (() -> Unit)? = null) { a.present(); completion?.invoke() }

    /** su iOS chiude il controller presentato da questo, oppure questo se è stato presentato */
    fun dismissViewControllerAnimated(animated: Boolean, completion: (() -> Unit)? = null) {
        val target = presentedViewController ?: this
        val from = target.presentingViewController ?: return
        target.presentedViewController?.let { it.dismissViewControllerAnimated(false) }
        val v = target.view
        target.viewWillDisappear(animated)
        val end = { v.removeFromSuperview(); target.viewDidDisappear(animated); from.presentedViewController = null; target.presentingViewController = null; completion?.invoke() }
        if (animated) UIView.animateWithDuration(0.25, 0.0, UIViewAnimationOptionCurveEaseInOut, { v.frame = CGRect(0.0, designHeight, designWidth, designHeight) }) { end() }
        else end()
    }

    companion object {
        var designWidth = 1024.0
        var designHeight = 768.0
        /** contenitore del primo controller (la tela di UkRootView) */
        var host: UIView? = null
    }
}

// ---------------------------------------------------------------------------------------------------------------------
// UIGraphicsBeginImageContextWithOptions / UIGraphicsGetImageFromCurrentImageContext / UIGraphicsEndImageContext:
// si disegna in punti su un'immagine alla risoluzione dello schermo (opaque e scale accettati per compatibilità).
private class UkImageCtx(val bitmap: Bitmap, val prev: CGContext?)
private val ukImageStack = ArrayList<UkImageCtx>()

fun UIGraphicsBeginImageContextWithOptions(size: CGSize, opaque: Boolean = false, scale: Number = 0) {
    val b = Bitmap.createBitmap(max(1, Uk.px(size.width)), max(1, Uk.px(size.height)), Bitmap.Config.ARGB_8888)
    val c = Canvas(b); c.scale(Uk.scale, Uk.scale)
    ukImageStack.add(UkImageCtx(b, CGContext.current))
    CGContext.current = CGContext(c)
}
fun UIGraphicsGetImageFromCurrentImageContext(): UIImage? = ukImageStack.lastOrNull()?.let { UIImage(it.bitmap) }
fun UIGraphicsEndImageContext() {
    val top = if (ukImageStack.isEmpty()) return else ukImageStack.removeAt(ukImageStack.size - 1)
    CGContext.current = top.prev
}
