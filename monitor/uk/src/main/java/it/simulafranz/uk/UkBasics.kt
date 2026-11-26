// "uk": un piccolo UIKit sopra Android. Serve a tradurre le schermate dell'iPad riga per riga:
// stesse classi (UIView, UILabel, UIButton, CALayer…), stesse coordinate in PUNTI su una tela 1024×768.
// Tutto ciò che è geometria è in punti (Double); la conversione in pixel avviene qui dentro (Uk.scale).
package it.simulafranz.uk

import android.content.Context
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.view.Choreographer
import kotlin.math.roundToInt

typealias CGFloat = Double

object Uk {
    lateinit var ctx: Context
    /** pixel per punto (tela 1024×768 adattata allo schermo) */
    var scale: Float = 1f
    val main = Handler(Looper.getMainLooper())
    fun px(pt: Double): Int = (pt * scale).roundToInt()
    fun pxf(pt: Double): Float = (pt * scale).toFloat()
    /** dispatch_after(…, main queue) */
    fun after(seconds: Double, block: () -> Unit) { main.postDelayed(block, (seconds * 1000).toLong().coerceAtLeast(0)) }
    /** dispatch_async(main queue) */
    fun async(block: () -> Unit) { main.post(block) }
    fun onMain(): Boolean = Looper.myLooper() == Looper.getMainLooper()
    /** dispatch_async(coda di sfondo) */
    fun background(block: () -> Unit) { Thread(block).apply { isDaemon = true }.start() }
}

/** CACurrentMediaTime(): secondi monotoni */
fun CACurrentMediaTime(): Double = System.nanoTime() / 1e9

data class CGPoint(val x: Double = 0.0, val y: Double = 0.0)
data class CGSize(val width: Double = 0.0, val height: Double = 0.0)
data class CGRect(val x: Double = 0.0, val y: Double = 0.0, val width: Double = 0.0, val height: Double = 0.0) {
    val origin get() = CGPoint(x, y)
    val size get() = CGSize(width, height)
    val minX get() = x; val minY get() = y
    val maxX get() = x + width; val maxY get() = y + height
    val midX get() = x + width / 2; val midY get() = y + height / 2
    fun inset(dx: Number, dy: Number) = CGRect(x + dx.toDouble(), y + dy.toDouble(), width - 2 * dx.toDouble(), height - 2 * dy.toDouble())
    fun offset(dx: Number, dy: Number) = CGRect(x + dx.toDouble(), y + dy.toDouble(), width, height)
    fun contains(p: CGPoint) = p.x >= x && p.x < x + width && p.y >= y && p.y < y + height
    fun intersects(r: CGRect) = x < r.maxX && r.x < maxX && y < r.maxY && r.y < maxY
    companion object { val zero = CGRect() }
}
val CGRectZero = CGRect()
val CGSizeZero = CGSize()
val CGPointZero = CGPoint()
fun CGRectMake(x: Number, y: Number, w: Number, h: Number) = CGRect(x.toDouble(), y.toDouble(), w.toDouble(), h.toDouble())
fun CGPointMake(x: Number, y: Number) = CGPoint(x.toDouble(), y.toDouble())
fun CGSizeMake(w: Number, h: Number) = CGSize(w.toDouble(), h.toDouble())
fun CGRectInset(r: CGRect, dx: Number, dy: Number) = r.inset(dx, dy)
fun CGRectOffset(r: CGRect, dx: Number, dy: Number) = r.offset(dx, dy)
fun CGRectGetMidX(r: CGRect) = r.midX
fun CGRectGetMidY(r: CGRect) = r.midY
fun CGRectGetMaxX(r: CGRect) = r.maxX
fun CGRectGetMaxY(r: CGRect) = r.maxY
fun CGRectGetMinX(r: CGRect) = r.minX
fun CGRectGetMinY(r: CGRect) = r.minY
fun CGRectContainsPoint(r: CGRect, p: CGPoint) = r.contains(p)
fun CGRectIntersectsRect(a: CGRect, b: CGRect) = a.intersects(b)

data class UIEdgeInsets(val top: Double = 0.0, val left: Double = 0.0, val bottom: Double = 0.0, val right: Double = 0.0)
fun UIEdgeInsetsMake(t: Number, l: Number, b: Number, r: Number) = UIEdgeInsets(t.toDouble(), l.toDouble(), b.toDouble(), r.toDouble())
fun UIEdgeInsetsInsetRect(r: CGRect, i: UIEdgeInsets) = CGRect(r.x + i.left, r.y + i.top, r.width - i.left - i.right, r.height - i.top - i.bottom)

/** Colore RGBA 0..1. `CGColor` ritorna se stesso (così `c.CGColor` si porta uguale). */
class UIColor(val r: Double, val g: Double, val b: Double, val a: Double = 1.0) {
    val CGColor: UIColor get() = this
    val argb: Int get() = android.graphics.Color.argb(c(a), c(r), c(g), c(b))
    private fun c(v: Double) = (v.coerceIn(0.0, 1.0) * 255).roundToInt()
    fun colorWithAlphaComponent(alpha: Number) = UIColor(r, g, b, alpha.toDouble())
    override fun equals(other: Any?) = other is UIColor && other.argb == argb
    override fun hashCode() = argb
    companion object {
        fun colorWithRed(red: Number, green: Number, blue: Number, alpha: Number = 1.0) = UIColor(red.toDouble(), green.toDouble(), blue.toDouble(), alpha.toDouble())
        fun colorWithWhite(white: Number, alpha: Number = 1.0) = UIColor(white.toDouble(), white.toDouble(), white.toDouble(), alpha.toDouble())
        fun fromArgb(c: Int) = UIColor(android.graphics.Color.red(c) / 255.0, android.graphics.Color.green(c) / 255.0, android.graphics.Color.blue(c) / 255.0, android.graphics.Color.alpha(c) / 255.0)
        val clearColor = UIColor(0.0, 0.0, 0.0, 0.0)
        val blackColor = UIColor(0.0, 0.0, 0.0)
        val whiteColor = UIColor(1.0, 1.0, 1.0)
        val redColor = UIColor(1.0, 0.0, 0.0)
        val greenColor = UIColor(0.0, 1.0, 0.0)
        val blueColor = UIColor(0.0, 0.0, 1.0)
        val yellowColor = UIColor(1.0, 1.0, 0.0)
        val cyanColor = UIColor(0.0, 1.0, 1.0)
        val orangeColor = UIColor(1.0, 0.5, 0.0)
        val grayColor = UIColor(0.5, 0.5, 0.5)
        val darkGrayColor = UIColor(1.0 / 3, 1.0 / 3, 1.0 / 3)
        val lightGrayColor = UIColor(2.0 / 3, 2.0 / 3, 2.0 / 3)
        /** azzurro dei controlli di sistema iOS */
        val systemBlue = UIColor(0.0, 0.478, 1.0)
    }
}
fun CGColorGetAlpha(c: UIColor?): Double = c?.a ?: 0.0

const val UIFontWeightUltraLight = -0.8
const val UIFontWeightLight = -0.4
const val UIFontWeightRegular = 0.0
const val UIFontWeightMedium = 0.23
const val UIFontWeightSemibold = 0.3
const val UIFontWeightBold = 0.4

class UIFont(val pointSize: Double, val weight: Double = UIFontWeightRegular, val mono: Boolean = false, val italic: Boolean = false) {
    val typeface: Typeface by lazy {
        val family = when {
            mono -> "monospace"
            weight <= UIFontWeightUltraLight -> "sans-serif-thin"
            weight <= UIFontWeightLight -> "sans-serif-light"
            weight >= UIFontWeightBold -> "sans-serif"
            weight >= UIFontWeightMedium -> "sans-serif-medium"
            else -> "sans-serif"
        }
        val style = (if (weight >= UIFontWeightBold) Typeface.BOLD else 0) or (if (italic) Typeface.ITALIC else 0)
        Typeface.create(family, style)
    }
    fun fontWithSize(size: Number) = UIFont(size.toDouble(), weight, mono, italic)
    companion object {
        fun systemFontOfSize(size: Number) = UIFont(size.toDouble())
        fun systemFontOfSize(size: Number, weight: Double) = UIFont(size.toDouble(), weight)
        fun boldSystemFontOfSize(size: Number) = UIFont(size.toDouble(), UIFontWeightBold)
        fun italicSystemFontOfSize(size: Number) = UIFont(size.toDouble(), italic = true)
        /** Le cifre di Roboto hanno già larghezza fissa. */
        fun monospacedDigitSystemFontOfSize(size: Number, weight: Double = UIFontWeightRegular) = UIFont(size.toDouble(), weight)
        /** Menlo → monospazio; AvenirNext-* → sans con il peso corrispondente. */
        fun fontWithName(name: String, size: Number): UIFont {
            val n = name.lowercase()
            val w = when {
                "ultralight" in n -> UIFontWeightUltraLight
                "light" in n -> UIFontWeightLight
                "demibold" in n || "semibold" in n -> UIFontWeightSemibold
                "bold" in n || "heavy" in n -> UIFontWeightBold
                "medium" in n -> UIFontWeightMedium
                else -> UIFontWeightRegular
            }
            return UIFont(size.toDouble(), w, mono = "menlo" in n || "courier" in n)
        }
    }
}

const val NSTextAlignmentLeft = 0
const val NSTextAlignmentCenter = 1
const val NSTextAlignmentRight = 2
const val NSLineBreakByWordWrapping = 0
const val NSLineBreakByTruncatingTail = 4
const val NSLineBreakByTruncatingMiddle = 5

/** CADisplayLink: chiama `tick` a ogni fotogramma finché non viene invalidato. */
class CADisplayLink(private val tick: (CADisplayLink) -> Unit) : Choreographer.FrameCallback {
    private var running = false
    var paused = false
    var timestamp = 0.0; private set
    fun start(): CADisplayLink { if (!running) { running = true; Choreographer.getInstance().postFrameCallback(this) }; return this }
    fun invalidate() { running = false; Choreographer.getInstance().removeFrameCallback(this) }
    override fun doFrame(frameTimeNanos: Long) {
        if (!running) return
        timestamp = frameTimeNanos / 1e9
        Choreographer.getInstance().postFrameCallback(this)
        if (!paused) tick(this)
    }
}

/** NSTimer sul thread principale. */
class NSTimer private constructor(private val interval: Double, private val repeats: Boolean, private val block: (NSTimer) -> Unit) : Runnable {
    var valid = true; private set
    override fun run() { if (!valid) return; if (repeats) Uk.main.postDelayed(this, (interval * 1000).toLong()) else valid = false; block(this) }
    fun invalidate() { valid = false; Uk.main.removeCallbacks(this) }
    companion object {
        fun scheduledTimerWithTimeInterval(interval: Double, repeats: Boolean, block: (NSTimer) -> Unit): NSTimer =
            NSTimer(interval, repeats, block).also { Uk.main.postDelayed(it, (interval * 1000).toLong()) }
    }
}


/**
 * Misuratore delle prestazioni (solo diagnostica, spento di norma): tempo di disegno per classe di vista,
 * richieste di layout e invalidazioni. Si accende con simulafranz-monitor://profile e scrive nel log ogni 5 s.
 */
object UkProfile {
    @JvmField var on = false
    /** disegno delle tracce: 1 = percorso unico (normale: veloce sul Tab Active 2), 0 = segmenti tondi (lento), 2 = segmenti con estremità quadrate, 3 = niente tracce */
    @JvmField var traceMode = 1
    private val drawNs = HashMap<String, Long>(); private val drawN = HashMap<String, Int>()
    private val tickNs = HashMap<String, Long>(); private val tickN = HashMap<String, Int>()
    @JvmField var layouts = 0; @JvmField var measures = 0; @JvmField var requestLayouts = 0; @JvmField var frames = 0
    private var since = System.nanoTime()
    fun draw(cls: String, ns: Long) { drawNs[cls] = (drawNs[cls] ?: 0) + ns; drawN[cls] = (drawN[cls] ?: 0) + 1 }
    fun tick(name: String, ns: Long) { tickNs[name] = (tickNs[name] ?: 0) + ns; tickN[name] = (tickN[name] ?: 0) + 1 }
    fun maybeReport(log: (String) -> Unit) {
        val now = System.nanoTime(); if (now - since < 5_000_000_000L) return
        val sec = (now - since) / 1e9
        val top = drawNs.entries.sortedByDescending { it.value }.take(8).joinToString("  ") { String.format(java.util.Locale.US, "%s %.1fms/s(%d)", it.key, it.value / 1e6 / sec, drawN[it.key]) }
        val tk = tickNs.entries.sortedByDescending { it.value }.joinToString("  ") { String.format(java.util.Locale.US, "%s %.1fms/s(%d)", it.key, it.value / 1e6 / sec, tickN[it.key]) }
        log(String.format(java.util.Locale.US, "PROFILE %.0f fps  layout %d  measure %d  requestLayout %d | tick: %s | draw: %s", frames / sec, layouts, measures, requestLayouts, tk, top))
        drawNs.clear(); drawN.clear(); tickNs.clear(); tickN.clear(); layouts = 0; measures = 0; requestLayouts = 0; frames = 0; since = now
    }
}


/** Campionatore del thread principale (diagnostica): ogni 5 ms annota le funzioni in cima allo stack. */
object UkSampler {
    @Volatile private var running = false
    fun start(log: (String) -> Unit) {
        if (running) return; running = true
        val main = android.os.Looper.getMainLooper().thread
        Thread {
            val top = HashMap<String, Int>(); val own = HashMap<String, Int>(); var n = 0; var t0 = System.currentTimeMillis()
            while (running) {
                val st = main.stackTrace
                if (st.isNotEmpty()) {
                    n++
                    val f = st[0]; val k = f.className.substringAfterLast('.') + "." + f.methodName
                    top[k] = (top[k] ?: 0) + 1
                    // prima funzione "nostra" nello stack (it.simulafranz.*)
                    val o = st.firstOrNull { it.className.startsWith("it.simulafranz") }
                    if (o != null) { val ko = o.className.substringAfterLast('.') + "." + o.methodName + ":" + o.lineNumber; own[ko] = (own[ko] ?: 0) + 1 }
                    else { val idle = st.any { it.methodName == "nativePollOnce" }; val ko = if (idle) "(inattivo)" else "(sistema)"; own[ko] = (own[ko] ?: 0) + 1 }
                }
                try { Thread.sleep(5) } catch (e: InterruptedException) { break }
                if (System.currentTimeMillis() - t0 > 5000 && n > 0) {
                    fun fmt(m: Map<String, Int>) = m.entries.sortedByDescending { it.value }.take(12).joinToString("  ") { "${it.key} ${it.value * 100 / n}%" }
                    log("SAMPLE cima: " + fmt(top)); log("SAMPLE nostro: " + fmt(own))
                    top.clear(); own.clear(); n = 0; t0 = System.currentTimeMillis()
                }
            }
        }.apply { isDaemon = true; name = "UkSampler" }.start()
    }
    fun stop() { running = false }
}
