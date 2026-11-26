// Controlli di UIKit disegnati nello stile di iOS 9: UISwitch, UISlider, UIStepper, UISegmentedControl,
// UITextField (ospita un vero EditText di Android) e UIAlertController (un AlertDialog di Android).
// Tutti sono UIControl: al cambio di valore inviano UIControlEventValueChanged.
package it.simulafranz.uk

import android.app.AlertDialog
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.text.Editable
import android.text.InputType
import android.text.TextPaint
import android.text.TextWatcher
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText

const val UIControlEventEditingDidBegin = 1 shl 16
const val UIControlEventEditingChanged = 1 shl 17
const val UIControlEventEditingDidEnd = 1 shl 18
const val UIControlEventEditingDidEndOnExit = 1 shl 19

/** verde degli interruttori accesi di iOS */
private val ukSwitchGreen = UIColor(0.298, 0.851, 0.392)

/** pomello bianco con ombra corta (interruttore e cursore) */
private fun ukDrawKnob(c: Canvas, p: Paint, cx: Float, cy: Float, r: Float) {
    p.style = Paint.Style.FILL; p.shader = null; p.pathEffect = null; p.xfermode = null
    p.color = 0x12000000; c.drawCircle(cx, cy + 2.5f, r + 0.8f, p)
    p.color = 0x1E000000; c.drawCircle(cx, cy + 1.5f, r + 0.4f, p)
    p.color = 0x26000000; c.drawCircle(cx, cy + 0.6f, r + 0.5f, p)
    p.color = 0xFFFFFFFF.toInt(); c.drawCircle(cx, cy, r, p)
}

private fun ukArgb(c: UIColor, alpha: Double): Int = UIColor(c.r, c.g, c.b, c.a * alpha).argb

// ---------------------------------------------------------------------------------------------------------------------
/** UISwitch: 51×31 punti qualunque sia il frame assegnato (come su iOS conta solo l'origine). */
open class UISwitch(frame: CGRect = CGRect()) : UIControl(CGRect(frame.x, frame.y, 51.0, 31.0)) {
    var onTintColor: UIColor? = null
        set(v) { field = v; invalidate() }
    var thumbTintColor: UIColor? = null
        set(v) { field = v; invalidate() }
    private var on = false
    private var pos = 0.0                    // posizione del pomello 0..1 (animata)
    private var anim: UkAnimation? = null
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)

    var isOn: Boolean
        get() = on
        set(v) { setOn(v, false) }

    fun setOn(on: Boolean, animated: Boolean) {
        if (on == this.on) return
        this.on = on
        anim?.stop(); anim = null
        val to = if (on) 1.0 else 0.0
        if (!animated || !isAttachedToWindow) { pos = to; invalidate(); return }
        val from = pos
        val a = UkAnimation(0.2) { t -> val e = 1 - (1 - t) * (1 - t); pos = from + (to - from) * e; invalidate() }
        anim = a
        a.start()
    }

    override fun layoutSubviews() {
        if (bounds.width != 51.0 || bounds.height != 31.0) frame = CGRect(frame.x, frame.y, 51.0, 31.0)
    }
    override fun sizeThatFits(size: CGSize): CGSize = CGSize(51.0, 31.0)

    override fun touchesEnded(p: CGPoint) {
        val was = tracking
        super.touchesEnded(p)
        if (was && p.x >= -44 && p.y >= -44 && p.x < bounds.width + 44 && p.y < bounds.height + 44) {
            setOn(!on, true)
            sendActionsForControlEvents(UIControlEventValueChanged)
        }
    }

    override fun drawRect(ctx: CGContext, rect: CGRect) {
        val c = ctx.canvas
        val k = if (isEnabled) 1.0 else 0.5
        val tint = onTintColor ?: ukSwitchGreen
        // spento: solo il contorno chiaro; acceso: pista piena nel colore di accensione
        p.style = Paint.Style.STROKE; p.strokeWidth = 1.5f; p.color = ukArgb(UIColor(0.90, 0.90, 0.90), (1 - pos) * k)
        if (pos < 0.999) c.drawRoundRect(0.75f, 0.75f, 50.25f, 30.25f, 14.75f, 14.75f, p)
        p.style = Paint.Style.FILL; p.color = ukArgb(tint, pos * k)
        if (pos > 0.001) c.drawRoundRect(0f, 0f, 51f, 31f, 15.5f, 15.5f, p)
        val cx = (15.5 + 20.0 * pos).toFloat()
        val layer = if (k < 1.0) c.saveLayerAlpha(0f, 0f, 51f, 36f, 150) else -1
        ukDrawKnob(c, p, cx, 15.5f, 13.5f)
        thumbTintColor?.let { p.color = it.argb; c.drawCircle(cx, 15.5f, 13.5f, p) }
        if (layer >= 0) c.restoreToCount(layer)
    }
}

// ---------------------------------------------------------------------------------------------------------------------
/** UISlider: pista sottile, parte "piena" nel colore minimumTrackTintColor, pomello bianco. */
open class UISlider(frame: CGRect = CGRect()) : UIControl(frame) {
    var minimumValue: Double = 0.0
        set(v) { field = v; invalidate() }
    var maximumValue: Double = 1.0
        set(v) { field = v; invalidate() }
    var value: Double = 0.0
        set(v) { field = clamp(v); invalidate() }
    /** true (predefinito): ValueChanged a ogni spostamento; false: solo al rilascio */
    var continuous: Boolean = true
    var minimumTrackTintColor: UIColor? = null
        set(v) { field = v; invalidate() }
    var maximumTrackTintColor: UIColor? = null
        set(v) { field = v; invalidate() }
    var thumbTintColor: UIColor? = null
        set(v) { field = v; invalidate() }
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val knobR = 14.0
    private var grab = 0.0                   // distanza tra il dito e il centro del pomello
    private var startValue = 0.0

    fun setValue(v: Number, animated: Boolean) { value = v.toDouble() }
    private fun clamp(v: Double): Double { val lo = minOf(minimumValue, maximumValue); val hi = maxOf(minimumValue, maximumValue); return v.coerceIn(lo, hi) }
    private fun fraction(): Double = if (maximumValue > minimumValue) ((value - minimumValue) / (maximumValue - minimumValue)).coerceIn(0.0, 1.0) else 0.0
    private fun knobX(): Double = knobR + fraction() * maxOf(bounds.width - 2 * knobR, 0.0)
    private fun valueAt(x: Double): Double {
        val span = bounds.width - 2 * knobR
        val f = if (span > 0) ((x - knobR) / span).coerceIn(0.0, 1.0) else 0.0
        return minimumValue + f * (maximumValue - minimumValue)
    }
    private fun move(x: Double, notify: Boolean) {
        val nv = clamp(valueAt(x - grab))
        if (nv == value) return
        value = nv
        if (notify) sendActionsForControlEvents(UIControlEventValueChanged)
    }

    // la zona sensibile è un po' più alta della pista (il pomello è largo 28 punti)
    override fun pointInside(p: CGPoint): Boolean = p.x >= -8 && p.x < bounds.width + 8 && p.y >= minOf(0.0, bounds.height / 2 - 22) && p.y < maxOf(bounds.height, bounds.height / 2 + 22)

    override fun touchesBegan(p: CGPoint) {
        if (!isEnabled) return
        super.touchesBegan(p)
        startValue = value
        // dito sul pomello: lo si trascina da dove si trova; dito sulla pista: il pomello va sotto il dito
        val d = p.x - knobX()
        grab = if (Math.abs(d) <= 22) d else 0.0
        move(p.x, continuous)
    }
    override fun touchesMoved(p: CGPoint) {
        if (!tracking) return
        super.touchesMoved(p)
        move(p.x, continuous)
    }
    override fun touchesEnded(p: CGPoint) {
        if (!tracking) return
        move(p.x, continuous)
        if (!continuous && value != startValue) sendActionsForControlEvents(UIControlEventValueChanged)
        super.touchesEnded(p)                // TouchUpInside / TouchUpOutside dopo l'ultimo valore
    }
    override fun touchesCancelled(p: CGPoint) {
        if (!tracking) return
        if (!continuous && value != startValue) sendActionsForControlEvents(UIControlEventValueChanged)
        super.touchesCancelled(p)
    }

    override fun drawRect(ctx: CGContext, rect: CGRect) {
        val c = ctx.canvas
        val w = bounds.width.toFloat(); val cy = (bounds.height / 2).toFloat()
        val kx = knobX().toFloat()
        val layer = if (!isEnabled) c.saveLayerAlpha(-4f, cy - 20f, w + 4f, cy + 20f, 128) else -1
        p.style = Paint.Style.FILL; p.shader = null
        p.color = (maximumTrackTintColor ?: UIColor(0.72, 0.72, 0.72)).argb
        c.drawRoundRect(kx, cy - 1f, w - 2f, cy + 1f, 1f, 1f, p)
        p.color = (minimumTrackTintColor ?: tintColor ?: UIColor.systemBlue).argb
        c.drawRoundRect(2f, cy - 1f, kx, cy + 1f, 1f, 1f, p)
        ukDrawKnob(c, p, kx, cy, knobR.toFloat())
        thumbTintColor?.let { p.color = it.argb; c.drawCircle(kx, cy, knobR.toFloat(), p) }
        if (layer >= 0) c.restoreToCount(layer)
    }
}

// ---------------------------------------------------------------------------------------------------------------------
/** UIStepper: 94×29 punti qualunque sia il frame assegnato; metà sinistra "−", metà destra "+". */
open class UIStepper(frame: CGRect = CGRect()) : UIControl(CGRect(frame.x, frame.y, 94.0, 29.0)) {
    var minimumValue: Double = 0.0
        set(v) { field = v; if (maximumValue < v) maximumValue = v; if (value < v) value = v; invalidate() }
    var maximumValue: Double = 100.0
        set(v) { field = v; if (minimumValue > v) minimumValue = v; if (value > v) value = v; invalidate() }
    var stepValue: Double = 1.0
    var value: Double = 0.0
        set(v) { field = v.coerceIn(minimumValue, maxOf(minimumValue, maximumValue)); invalidate() }
    /** tenendo premuto il valore continua a cambiare */
    var autorepeat: Boolean = true
    /** true: ValueChanged a ogni passo durante la ripetizione; false: solo al rilascio */
    var continuous: Boolean = true
    /** oltre il massimo riparte dal minimo (e viceversa) */
    var wraps: Boolean = false
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val half = Path()
    private var pressed = 0                  // -1 metà sinistra, +1 metà destra
    private var startValue = 0.0
    private var repeats = 0
    private val repeater = object : Runnable {
        override fun run() {
            if (pressed == 0 || !autorepeat) return
            step(pressed, continuous)
            repeats++
            Uk.main.postDelayed(this, if (repeats > 20) 40 else if (repeats > 8) 70 else 110)
        }
    }

    override fun layoutSubviews() {
        if (bounds.width != 94.0 || bounds.height != 29.0) frame = CGRect(frame.x, frame.y, 94.0, 29.0)
    }
    override fun sizeThatFits(size: CGSize): CGSize = CGSize(94.0, 29.0)

    private fun canStep(side: Int): Boolean = wraps || (if (side < 0) value > minimumValue else value < maximumValue)
    private fun step(side: Int, notify: Boolean) {
        var nv = value + side * stepValue
        if (wraps) { if (nv > maximumValue) nv = minimumValue else if (nv < minimumValue) nv = maximumValue }
        nv = nv.coerceIn(minimumValue, maxOf(minimumValue, maximumValue))
        if (nv == value) return
        value = nv
        if (notify) sendActionsForControlEvents(UIControlEventValueChanged)
    }
    private fun release() { pressed = 0; Uk.main.removeCallbacks(repeater); invalidate() }

    override fun touchesBegan(p: CGPoint) {
        if (!isEnabled) return
        super.touchesBegan(p)
        startValue = value; repeats = 0
        pressed = if (p.x < bounds.width / 2) -1 else 1
        invalidate()
        step(pressed, continuous || !autorepeat)
        Uk.main.removeCallbacks(repeater)
        if (autorepeat) Uk.main.postDelayed(repeater, 500)
    }
    override fun touchesMoved(p: CGPoint) {
        if (!tracking) return
        super.touchesMoved(p)
        // uscendo dalla metà premuta la ripetizione si ferma
        val side = if (p.x < bounds.width / 2) -1 else 1
        if (pressed != 0 && (side != pressed || p.y < -30 || p.y > bounds.height + 30 || p.x < -30 || p.x > bounds.width + 30)) release()
    }
    override fun touchesEnded(p: CGPoint) {
        if (!tracking) return
        release()
        if (!continuous && autorepeat && value != startValue) sendActionsForControlEvents(UIControlEventValueChanged)
        super.touchesEnded(p)
    }
    override fun touchesCancelled(p: CGPoint) {
        if (!tracking) return
        release()
        if (!continuous && autorepeat && value != startValue) sendActionsForControlEvents(UIControlEventValueChanged)
        super.touchesCancelled(p)
    }
    override fun onDetachedFromWindow() { release(); super.onDetachedFromWindow() }

    override fun drawRect(ctx: CGContext, rect: CGRect) {
        val c = ctx.canvas
        val tint = tintColor ?: UIColor.systemBlue
        val k = if (isEnabled) 1.0 else 0.4
        val w = 94f; val h = 29f; val r = 4f
        if (pressed != 0) {
            half.rewind()
            val rr = if (pressed < 0) floatArrayOf(r, r, 0f, 0f, 0f, 0f, r, r) else floatArrayOf(0f, 0f, r, r, r, r, 0f, 0f)
            if (pressed < 0) half.addRoundRect(0f, 0f, w / 2, h, rr, Path.Direction.CW) else half.addRoundRect(w / 2, 0f, w, h, rr, Path.Direction.CW)
            p.style = Paint.Style.FILL; p.color = ukArgb(tint, 0.22 * k)
            c.drawPath(half, p)
        }
        p.style = Paint.Style.STROKE; p.strokeWidth = 1f; p.strokeCap = Paint.Cap.BUTT; p.color = ukArgb(tint, k)
        c.drawRoundRect(0.5f, 0.5f, w - 0.5f, h - 0.5f, r, r, p)
        c.drawLine(w / 2, 0.5f, w / 2, h - 0.5f, p)
        p.strokeWidth = 1.5f
        val cy = h / 2
        p.color = ukArgb(tint, k * (if (canStep(-1)) 1.0 else 0.35))
        c.drawLine(w / 4 - 7.5f, cy, w / 4 + 7.5f, cy, p)
        p.color = ukArgb(tint, k * (if (canStep(1)) 1.0 else 0.35))
        c.drawLine(3 * w / 4 - 7.5f, cy, 3 * w / 4 + 7.5f, cy, p)
        c.drawLine(3 * w / 4, cy - 7.5f, 3 * w / 4, cy + 7.5f, p)
    }
}

// ---------------------------------------------------------------------------------------------------------------------
const val UISegmentedControlNoSegment = -1

/**
 * UISegmentedControl nello stile di iOS 7–12: contorno e scritte nel colore `tintColor`, segmento scelto pieno
 * con la scritta "forata" (si vede lo sfondo). Segmenti di larghezza uguale. Il tocco sceglie al rilascio.
 */
open class UISegmentedControl(items: List<String> = emptyList()) : UIControl(CGRect(0.0, 0.0, maxOf(items.size * 60.0, 60.0), 29.0)) {
    private val titles = ArrayList<String>(items)
    private val enabledSeg = ArrayList<Boolean>(items.map { true })
    private var font: UIFont = UIFont.systemFontOfSize(13)
    private var fontSelected: UIFont? = null
    private var pressed = -1
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val tp = TextPaint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG)
    private val seg = Path()
    private val fm = Paint.FontMetrics()
    private val cut = PorterDuffXfermode(PorterDuff.Mode.DST_OUT)

    var selectedSegmentIndex: Int = UISegmentedControlNoSegment
        set(v) { field = if (v < 0 || v >= titles.size) UISegmentedControlNoSegment else v; invalidate() }
    val numberOfSegments: Int get() = titles.size
    /** true: il segmento non resta scelto dopo il tocco (si comporta come una fila di pulsanti) */
    var momentary: Boolean = false

    fun setTitle(title: String?, forSegmentAtIndex: Int) { if (forSegmentAtIndex in titles.indices) { titles[forSegmentAtIndex] = title ?: ""; invalidate() } }
    fun titleForSegmentAtIndex(index: Int): String? = titles.getOrNull(index)
    fun insertSegmentWithTitle(title: String?, atIndex: Int, animated: Boolean = false) {
        val i = atIndex.coerceIn(0, titles.size); titles.add(i, title ?: ""); enabledSeg.add(i, true)
        if (selectedSegmentIndex >= i) selectedSegmentIndex += 1 else invalidate()
    }
    fun removeAllSegments() { titles.clear(); enabledSeg.clear(); selectedSegmentIndex = UISegmentedControlNoSegment }
    fun setEnabled(enabled: Boolean, forSegmentAtIndex: Int) { if (forSegmentAtIndex in enabledSeg.indices) { enabledSeg[forSegmentAtIndex] = enabled; invalidate() } }
    fun isEnabledForSegmentAtIndex(index: Int): Boolean = enabledSeg.getOrNull(index) ?: false
    /** [seg setTitleTextAttributes:@{NSFontAttributeName: f} forState:] — conta solo il carattere */
    fun setTitleTextAttributes(font: UIFont, forState: Int = UIControlStateNormal) {
        if (forState == UIControlStateSelected) fontSelected = font else this.font = font
        invalidate()
    }

    private fun segmentAt(p: CGPoint): Int {
        if (titles.isEmpty() || bounds.width <= 0) return -1
        if (p.y < -30 || p.y > bounds.height + 30 || p.x < 0 || p.x >= bounds.width) return -1
        return (p.x / (bounds.width / titles.size)).toInt().coerceIn(0, titles.size - 1)
    }
    private fun press(i: Int) { val v = if (i >= 0 && enabledSeg[i]) i else -1; if (v != pressed) { pressed = v; invalidate() } }

    override fun touchesBegan(p: CGPoint) {
        if (!isEnabled) return
        super.touchesBegan(p)
        press(segmentAt(p))
    }
    override fun touchesMoved(p: CGPoint) {
        if (!tracking) return
        super.touchesMoved(p)
        press(segmentAt(p))
    }
    override fun touchesEnded(p: CGPoint) {
        if (!tracking) return
        val i = pressed
        press(-1)
        if (i >= 0 && segmentAt(p) == i) {
            if (momentary) sendActionsForControlEvents(UIControlEventValueChanged)
            else if (i != selectedSegmentIndex) { selectedSegmentIndex = i; sendActionsForControlEvents(UIControlEventValueChanged) }
        }
        super.touchesEnded(p)
    }
    override fun touchesCancelled(p: CGPoint) {
        if (!tracking) return
        press(-1)
        super.touchesCancelled(p)
    }

    private fun segmentPath(i: Int, sw: Float, h: Float, r: Float): Path {
        val n = titles.size
        val l = if (i == 0) r else 0f; val rt = if (i == n - 1) r else 0f
        seg.rewind()
        seg.addRoundRect(i * sw, 0f, if (i == n - 1) bounds.width.toFloat() else (i + 1) * sw, h, floatArrayOf(l, l, rt, rt, rt, rt, l, l), Path.Direction.CW)
        return seg
    }
    private fun drawTitle(c: Canvas, i: Int, sw: Float, h: Float, f: UIFont) {
        val s = titles[i]
        if (s.isEmpty()) return
        tp.typeface = f.typeface; tp.textSize = f.pointSize.toFloat(); tp.textScaleX = 1f
        var tw = tp.measureText(s)
        val avail = sw - 6f
        if (tw > avail && avail > 0) {                  // scritta troppo larga: si riduce il carattere
            tp.textSize = maxOf(f.pointSize.toFloat() * avail / tw, f.pointSize.toFloat() * 0.6f); tw = tp.measureText(s)
        }
        tp.getFontMetrics(fm)
        c.save(); c.clipRect(i * sw, 0f, (i + 1) * sw, h)
        c.drawText(s, i * sw + (sw - tw) / 2, (h - (fm.descent - fm.ascent)) / 2 - fm.ascent, tp)
        c.restore()
    }

    override fun drawRect(ctx: CGContext, rect: CGRect) {
        val n = titles.size
        if (n == 0 || bounds.width <= 0) return
        val c = ctx.canvas
        val tint = tintColor ?: UIColor.systemBlue
        val k = if (isEnabled) 1.0 else 0.4
        val w = bounds.width.toFloat(); val h = bounds.height.toFloat(); val r = 4f
        val sw = w / n
        val sel = selectedSegmentIndex
        p.shader = null; p.pathEffect = null; p.xfermode = null
        // segmento premuto: velatura leggera
        if (pressed >= 0 && pressed != sel) { p.style = Paint.Style.FILL; p.color = ukArgb(tint, 0.20 * k); c.drawPath(segmentPath(pressed, sw, h, r), p) }
        // segmento scelto: pieno, con la scritta forata
        if (sel in 0 until n) {
            val layer = c.saveLayer(sel * sw - 1, 0f, (sel + 1) * sw + 1, h, null)
            p.style = Paint.Style.FILL; p.color = ukArgb(tint, k * (if (pressed == sel) 0.8 else 1.0))
            c.drawPath(segmentPath(sel, sw, h, r), p)
            tp.color = 0xFF000000.toInt(); tp.xfermode = cut
            drawTitle(c, sel, sw, h, fontSelected ?: font)
            tp.xfermode = null
            c.restoreToCount(layer)
        }
        for (i in 0 until n) {
            if (i == sel) continue
            tp.color = ukArgb(tint, k * (if (enabledSeg[i]) 1.0 else 0.35))
            drawTitle(c, i, sw, h, font)
        }
        // contorno e divisori
        p.style = Paint.Style.STROKE; p.strokeWidth = 1f; p.color = ukArgb(tint, k)
        c.drawRoundRect(0.5f, 0.5f, w - 0.5f, h - 0.5f, r, r, p)
        for (i in 1 until n) c.drawLine(i * sw, 0.5f, i * sw, h - 0.5f, p)
    }
}

// ---------------------------------------------------------------------------------------------------------------------
const val UITextAutocorrectionTypeDefault = 0
const val UITextAutocorrectionTypeNo = 1
const val UITextAutocorrectionTypeYes = 2
const val UITextAutocapitalizationTypeNone = 0
const val UITextAutocapitalizationTypeWords = 1
const val UITextAutocapitalizationTypeSentences = 2
const val UITextAutocapitalizationTypeAllCharacters = 3
const val UITextFieldViewModeNever = 0
const val UITextFieldViewModeWhileEditing = 1
const val UITextFieldViewModeUnlessEditing = 2
const val UITextFieldViewModeAlways = 3
const val UIKeyboardTypeDefault = 0
const val UIKeyboardTypeNumbersAndPunctuation = 2
const val UIKeyboardTypeURL = 3
const val UIKeyboardTypeNumberPad = 4
const val UIKeyboardTypeDecimalPad = 8

/**
 * UITextField: ospita un vero EditText di Android (tastiera, cursore, selezione sono quelli di sistema).
 * Misure e carattere restano in punti. `rightView` (es. il tasto "mostra") è una normale vista uk sul bordo destro.
 * Eventi: UIControlEventEditingChanged a ogni modifica (e UIControlEventValueChanged), `onReturn` al tasto Invio.
 */
open class UITextField(frame: CGRect = CGRect()) : UIControl(frame) {
    val editText = EditText(Uk.ctx)
    private var settingText = false

    var text: String?
        get() = editText.text?.toString() ?: ""
        set(v) { settingText = true; editText.setText(v ?: ""); editText.setSelection(editText.text.length); settingText = false }
    var placeholder: String? = null
        set(v) { field = v; editText.hint = v }
    var font: UIFont = UIFont.systemFontOfSize(17)
        set(v) { field = v; applyFont() }
    var textColor: UIColor = UIColor.blackColor
        set(v) { field = v; editText.setTextColor(v.argb) }
    var placeholderColor: UIColor = UIColor(0.0, 0.0, 0.1, 0.30)
        set(v) { field = v; editText.setHintTextColor(v.argb) }
    var secureTextEntry: Boolean = false
        set(v) { if (field == v) return; field = v; applyInputType() }
    var autocorrectionType: Int = UITextAutocorrectionTypeDefault
        set(v) { field = v; applyInputType() }
    var autocapitalizationType: Int = UITextAutocapitalizationTypeSentences
        set(v) { field = v; applyInputType() }
    var keyboardType: Int = UIKeyboardTypeDefault
        set(v) { field = v; applyInputType() }
    /** margine sinistro del testo, in punti */
    var textInset: Double = 0.0
        set(v) { field = v; requestLayout() }
    var rightView: UIView? = null
        set(v) { field?.let { if (it.parent === this) removeView(it) }; field = v; if (v != null && rightViewMode != UITextFieldViewModeNever) addSubview(v); placeRightView(); requestLayout() }
    var rightViewMode: Int = UITextFieldViewModeNever
        set(v) {
            field = v
            val rv = rightView
            if (rv != null) { if (v == UITextFieldViewModeNever) { if (rv.parent === this) removeView(rv) } else if (rv.parent !== this) addSubview(rv) }
            placeRightView(); requestLayout()
        }
    /** tasto Invio della tastiera (textFieldShouldReturn:) */
    var onReturn: (() -> Unit)? = null
    /** lunghezza massima del testo (shouldChangeCharactersInRange: con un limite), 0 = nessun limite */
    var maxLength: Int = 0
        set(v) { field = v; editText.filters = if (v > 0) arrayOf(android.text.InputFilter.LengthFilter(v)) else arrayOf() }
    val isFirstResponder: Boolean get() = editText.hasFocus()

    override val isNativeHost: Boolean get() = true

    init {
        editText.background = null
        editText.setPadding(0, 0, 0, 0)
        editText.setSingleLine(true)
        editText.gravity = Gravity.CENTER_VERTICAL or Gravity.START
        editText.includeFontPadding = false
        // IME_FLAG_NO_EXTRACT_UI: in orizzontale la tastiera non deve coprire tutto lo schermo
        editText.imeOptions = EditorInfo.IME_ACTION_DONE or EditorInfo.IME_FLAG_NO_EXTRACT_UI or EditorInfo.IME_FLAG_NO_FULLSCREEN
        editText.setTextColor(textColor.argb)
        editText.setHintTextColor(placeholderColor.argb)
        applyInputType()
        editText.setOnEditorActionListener { _, actionId, ev ->
            val enter = ev != null && ev.keyCode == KeyEvent.KEYCODE_ENTER
            if (enter && ev!!.action != KeyEvent.ACTION_DOWN) true
            else if (actionId == EditorInfo.IME_ACTION_DONE || actionId == EditorInfo.IME_ACTION_GO || actionId == EditorInfo.IME_ACTION_NEXT || actionId == EditorInfo.IME_ACTION_SEND || enter) {
                sendActionsForControlEvents(UIControlEventEditingDidEndOnExit)
                val h = onReturn
                if (h != null) h() else resignFirstResponder()
                true
            } else false
        }
        editText.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) { if (!settingText) sendActionsForControlEvents(UIControlEventEditingChanged or UIControlEventValueChanged) }
        })
        editText.setOnFocusChangeListener { _, has -> sendActionsForControlEvents(if (has) UIControlEventEditingDidBegin else UIControlEventEditingDidEnd) }
        // misure fisse in pixel (aggiornate in onLayout): cambiando il testo l'EditText non chiede una nuova disposizione
        addView(editText, LayoutParams(1, 1))
    }

    private fun applyFont() {
        editText.setTextSize(TypedValue.COMPLEX_UNIT_PX, (font.pointSize * Uk.scale).toFloat())
        editText.typeface = font.typeface
    }
    private fun applyInputType() {
        val sel = editText.selectionEnd
        var t = when (keyboardType) {
            UIKeyboardTypeNumberPad -> InputType.TYPE_CLASS_NUMBER
            UIKeyboardTypeDecimalPad -> InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            UIKeyboardTypeNumbersAndPunctuation -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
            UIKeyboardTypeURL -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            else -> InputType.TYPE_CLASS_TEXT
        }
        if (t and InputType.TYPE_MASK_CLASS == InputType.TYPE_CLASS_TEXT) {
            if (secureTextEntry) t = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            else {
                if (autocorrectionType == UITextAutocorrectionTypeNo) t = t or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                when (autocapitalizationType) {
                    UITextAutocapitalizationTypeWords -> t = t or InputType.TYPE_TEXT_FLAG_CAP_WORDS
                    UITextAutocapitalizationTypeSentences -> t = t or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
                    UITextAutocapitalizationTypeAllCharacters -> t = t or InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS
                }
                // senza correzione né maiuscole (password in chiaro, indirizzi): la tastiera non deve proporre parole
                if (autocorrectionType == UITextAutocorrectionTypeNo && autocapitalizationType == UITextAutocapitalizationTypeNone && keyboardType == UIKeyboardTypeDefault)
                    t = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
            }
        }
        editText.inputType = t
        applyFont()                                   // il tipo "password" cambia il carattere: lo si rimette
        val n = editText.text?.length ?: 0
        if (sel in 0..n) editText.setSelection(sel)
    }

    fun becomeFirstResponder(): Boolean {
        val ok = editText.requestFocus()
        editText.post { (Uk.ctx.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager)?.showSoftInput(editText, InputMethodManager.SHOW_IMPLICIT) }
        return ok
    }
    fun resignFirstResponder(): Boolean {
        (Uk.ctx.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager)?.hideSoftInputFromWindow(editText.windowToken, 0)
        editText.clearFocus()
        return true
    }

    private fun placeRightView() {
        val rv = rightView ?: return
        val f = rv.frame
        rv.frame = CGRect(bounds.width - f.width, (bounds.height - f.height) / 2, f.width, f.height)
    }
    override fun layoutSubviews() { placeRightView() }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val w = r - l; val h = b - t
        var right = w
        val rv = rightView
        if (rv != null && rv.parent === this) { rv.applyFrame(); right = w - Uk.px(rv.frame.width) }
        val left = Uk.px(textInset)
        val ew = maxOf(right - left, 0)
        editText.layoutParams?.let { it.width = ew; it.height = h }
        editText.measure(MeasureSpec.makeMeasureSpec(ew, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(h, MeasureSpec.EXACTLY))
        editText.layout(left, 0, left + ew, h)
    }
}

// ---------------------------------------------------------------------------------------------------------------------
const val UIAlertActionStyleDefault = 0
const val UIAlertActionStyleCancel = 1
const val UIAlertActionStyleDestructive = 2
const val UIAlertControllerStyleActionSheet = 0
const val UIAlertControllerStyleAlert = 1

class UIAlertAction(val title: String, val style: Int = UIAlertActionStyleDefault, val handler: (() -> Unit)? = null) {
    var enabled: Boolean = true
    companion object { fun actionWithTitle(title: String, style: Int = UIAlertActionStyleDefault, handler: (() -> Unit)? = null) = UIAlertAction(title, style, handler) }
}

/**
 * UIAlertController → AlertDialog di Android (Uk.ctx deve essere l'Activity).
 * `presentViewController:animated:completion:` diventa `present()`. Come su iOS non si chiude toccando fuori;
 * il tasto Indietro vale come l'azione "Annulla" (UIAlertActionStyleCancel), se c'è.
 */
class UIAlertController(var title: String?, var message: String?) {
    val actions = ArrayList<UIAlertAction>()
    val textFields = ArrayList<UITextField>()
    private var dialog: AlertDialog? = null

    fun addAction(action: UIAlertAction) { actions.add(action) }
    fun addAction(title: String, style: Int = UIAlertActionStyleDefault, handler: (() -> Unit)? = null) { actions.add(UIAlertAction(title, style, handler)) }

    /** addTextFieldWithConfigurationHandler: */
    fun addTextField(configurationHandler: ((UITextField) -> Unit)? = null) {
        val d = Uk.ctx.resources.displayMetrics.density
        val k = d / Uk.scale                                   // dp → punti
        val tf = UITextField(CGRect(0.0, 0.0, 240.0 * k, 40.0 * k))
        tf.font = UIFont.systemFontOfSize(16.0 * k)
        tf.backgroundColor = UIColor(0.95, 0.95, 0.96)
        tf.layer.cornerRadius = 5.0 * k
        tf.layer.borderWidth = 1.0 * k; tf.layer.borderColor = UIColor(0.78, 0.78, 0.80)
        tf.textColor = UIColor.blackColor
        tf.textInset = 8.0 * k
        textFields.add(tf)
        configurationHandler?.invoke(tf)
    }

    /** contenitore dei campi di testo dentro la finestra: li dispone in colonna, larghi quanto la finestra */
    private class Fields(ctx: Context, val fields: List<UITextField>) : ViewGroup(ctx) {
        private val d = ctx.resources.displayMetrics.density
        init { for (f in fields) { (f.parent as? ViewGroup)?.removeView(f); addView(f) } }
        override fun onMeasure(wSpec: Int, hSpec: Int) {
            val w = MeasureSpec.getSize(wSpec)
            val pad = 24 * d; val gap = 8 * d; val top = 6 * d
            var y = top
            for (f in fields) {
                val hPt = f.frame.height
                f.frame = CGRect(pad / Uk.scale.toDouble(), y / Uk.scale.toDouble(), maxOf(w - 2 * pad, 0f) / Uk.scale.toDouble(), hPt)
                f.measure(0, 0)
                y += Uk.px(hPt) + gap
            }
            setMeasuredDimension(w, (y - gap + 4 * d).toInt())
        }
        override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) { for (f in fields) f.applyFrame() }
    }

    fun present() {
        if (!Uk.onMain()) { Uk.async { present() }; return }
        try {
            val ctx = Uk.ctx
            val b = AlertDialog.Builder(ctx)
            val cancel = actions.firstOrNull { it.style == UIAlertActionStyleCancel }
            val others = actions.filter { it !== cancel }
            if (others.size > 2) {
                // più di due scelte: elenco (qui il messaggio non può comparire insieme all'elenco: va nel titolo)
                b.setTitle(listOfNotNull(title, message).filter { it.isNotEmpty() }.joinToString("\n"))
                b.setItems(others.map { it.title }.toTypedArray()) { _, i -> others[i].handler?.invoke() }
            } else {
                if (!title.isNullOrEmpty()) b.setTitle(title)
                if (!message.isNullOrEmpty()) b.setMessage(message)
                if (others.isNotEmpty()) b.setPositiveButton(others.last().title) { _, _ -> others.last().handler?.invoke() }
                if (others.size == 2) b.setNeutralButton(others[0].title) { _, _ -> others[0].handler?.invoke() }
            }
            if (cancel != null) b.setNegativeButton(cancel.title) { _, _ -> cancel.handler?.invoke() }
            if (textFields.isNotEmpty()) b.setView(Fields(ctx, textFields))
            b.setCancelable(cancel != null || actions.isEmpty())
            b.setOnCancelListener { cancel?.handler?.invoke() }
            val dlg = b.create()
            dlg.setCanceledOnTouchOutside(false)
            if (textFields.isNotEmpty()) dlg.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE or WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
            dialog = dlg
            dlg.setOnDismissListener { if (dialog === dlg) dialog = null }
            dlg.show()
            // azione distruttiva in rosso; azioni disattivate
            fun style(which: Int, a: UIAlertAction?) {
                val btn = dlg.getButton(which) ?: return
                if (a == null) return
                btn.isAllCaps = false
                if (a.style == UIAlertActionStyleDestructive) btn.setTextColor(0xFFFF3B30.toInt())
                btn.isEnabled = a.enabled
            }
            if (others.size <= 2) { style(AlertDialog.BUTTON_POSITIVE, others.lastOrNull()); if (others.size == 2) style(AlertDialog.BUTTON_NEUTRAL, others[0]) }
            style(AlertDialog.BUTTON_NEGATIVE, cancel)
            textFields.firstOrNull()?.let { tf ->
                // Invio nel campo = prima azione non di annullamento (come il tasto predefinito dell'avviso)
                if (tf.onReturn == null && others.isNotEmpty()) tf.onReturn = { dismiss(); others.last().handler?.invoke() }
                tf.editText.requestFocus()
            }
        } catch (e: Throwable) {
            android.util.Log.e("uk", "UIAlertController.present: $e")
        }
    }

    /** dismissViewControllerAnimated: */
    fun dismiss() { try { dialog?.dismiss() } catch (e: Throwable) {}; dialog = null }

    companion object {
        fun alertControllerWithTitle(title: String?, message: String?, preferredStyle: Int = UIAlertControllerStyleAlert) = UIAlertController(title, message)
    }
}
