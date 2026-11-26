// ANDROID (solo tablet): i "fianchi" dell'apparecchio nelle fasce ai lati della tela 1024×768.
// Sugli apparecchi veri le prese delle sonde stanno sul fianco: qui le prese dei layout dei dispositivi
// Con la sonda collegata la spina è inserita e il cavo esce verso sinistra, verso il paziente.
// Le fasce prolungano la scocca del layout pixel per pixel (SFBodyFill): nessuno stacco al bordo della tela.
package it.simulafranz.monitor

import android.view.View
import it.simulafranz.uk.*

object SFSideBand {
    /** fasce sinistra e destra dello schermo (impostate dall'attività prima di creare il monitor) */
    var left: UIView? = null
    var right: UIView? = null
    // ANDROID (audit 21/11): il valore è un riferimento DEBOLE al layout. Prima era forte: il layout tiene la sua vista di
    // fascia (chiave), quindi la voce della WeakHashMap non spariva mai e OGNI layout scartato al cambio di layout restava in
    // memoria per sempre (viste, tracce, immagini appiattite), una copia in più a ogni cambio.
    private val owned = java.util.WeakHashMap<UIView, java.lang.ref.WeakReference<UIView>>()      // vista di fascia -> layout a cui appartiene
    internal fun register(view: UIView, owner: UIView) { owned[view] = java.lang.ref.WeakReference(owner); view.hidden = owner.hidden }
    /** Le viste di fascia seguono la visibilità del loro layout (un layout nascosto, es. tornando a ICU, non lascia prese o scocca). */
    fun syncVisibility() { for ((v, r) in owned) { val o = r.get(); val h = o == null || o.hidden || !o.isAttachedToWindow; if (v.hidden != h) v.hidden = h } }
}

/** Aggancia una vista di fascia al ciclo di vita del layout: compare con lui e se ne va con lui; onShown dopo che è a schermo. */
internal fun SFAttachToBand(owner: UIView, band: UIView?, view: UIView, onShown: (() -> Unit)? = null) {
    SFSideBand.register(view, owner)
    owner.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
        override fun onViewAttachedToWindow(v: View) {
            if (band != null && view.superview !== band) band.addSubview(view)
            onShown?.let { Uk.after(0.35) { it() } }
        }
        override fun onViewDetachedFromWindow(v: View) { view.removeFromSuperview() }
    })
    if (owner.isAttachedToWindow && band != null) { band.addSubview(view); onShown?.let { Uk.after(0.35) { it() } } }
}

/**
 * Prolungamento della scocca: fotografa UNA colonna di pixel del layout (sul bordo, dove c'è solo scocca) e la stira
 * su tutta la fascia, così colori e sfumature verticali continuano identici, senza stacchi e senza angoli che sbordano.
 * Verso il bordo esterno dello schermo un'ombra leggera; outerRadius > 0 arrotonda gli spigoli esterni.
 * sampleX = x (in punti, nel layout) della colonna da prolungare; outerOnLeft = il lato esterno è a sinistra.
 */
class SFBodyFill(private val owner: UIView, frame: CGRect, private val sampleX: Double, private val outerOnLeft: Boolean,
                 private val outerRadius: Double, private val shade: Double = 0.30) : UIView(frame) {
    private var col: android.graphics.Bitmap? = null
    private val P = android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG)
    private val S = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
    private val clip = android.graphics.Path()
    private val dst = android.graphics.RectF()
    init { userInteractionEnabled = false }

    /** rilegge la colonna dal layout (dopo che è stato disegnato) */
    fun sample() {
        if (owner.width <= 0 || owner.height <= 0) return
        val full = owner.snapshotBitmap()
        val x = (sampleX * Uk.scale).toInt().coerceIn(0, full.width - 1)
        col = android.graphics.Bitmap.createBitmap(full, x, 0, 1, full.height)
        full.recycle()
        invalidate()
    }

    override fun drawRect(ctx: CGContext, rect: CGRect) {
        val c = ctx.canvas; val w = rect.width.toFloat(); val h = rect.height.toFloat()
        val b = col ?: return
        c.save()
        if (outerRadius > 0) {
            val r = outerRadius.toFloat()
            clip.reset()
            // arrotondato solo sul lato esterno: il lato verso la tela resta dritto (attaccato al layout)
            if (outerOnLeft) clip.addRoundRect(0f, 0f, w + r, h, r, r, android.graphics.Path.Direction.CW)
            else clip.addRoundRect(-r, 0f, w, h, r, r, android.graphics.Path.Direction.CW)
            c.clipPath(clip)
        }
        dst.set(0f, 0f, w, h)
        c.drawBitmap(b, null, dst, P)
        // ombra verso il bordo esterno: parte a 30 pt dalla tela, così attaccato al layout il colore è identico
        if (shade > 0) {
            val a = (shade * 255).toInt()
            val g0 = if (outerOnLeft) w - 30f else 30f
            val g1 = if (outerOnLeft) 0f else w
            S.shader = android.graphics.LinearGradient(g0, 0f, g1, 0f, 0x00000000, (a shl 24), android.graphics.Shader.TileMode.CLAMP)
            c.drawRect(0f, 0f, w, h, S)
        }
        c.restore()
    }
}

/** Colori del fianco per layout. */
class SFSideStyle(
    val body: UIColor,          // scocca del fianco (usata solo se il pannello non prolunga la scocca del layout)
    val shade: UIColor,         // non più usato per il fondo (resta per compatibilità con i layout)
    val label: UIColor,         // scritte sulla scocca
    val socket: UIColor,        // ghiera della presa
    val cable: UIColor          // guaina dei cavi
)

/** Colore della presa per sonda (codice colore come sulle prese vere). */
fun SFSideAccent(sensor: String): UIColor = when (sensor) {
    "ecg" -> UIColor.colorWithRed(0.30, 0.85, 0.35, 1)
    "spo2" -> UIColor.colorWithRed(0.25, 0.70, 1.00, 1)
    "nibp" -> UIColor.colorWithWhite(0.86, 1)
    "etco2" -> UIColor.colorWithRed(1.00, 0.86, 0.25, 1)
    "art" -> UIColor.colorWithRed(1.00, 0.32, 0.30, 1)
    "temp" -> UIColor.colorWithRed(1.00, 0.60, 0.30, 1)
    "pads" -> UIColor.colorWithRed(1.00, 0.45, 0.10, 1)
    else -> UIColor.colorWithWhite(0.7, 1)
}

/**
 * Fianco sinistro con le prese. sampleX >= 0: il fondo è il prolungamento esatto della scocca del layout (colonna sampleX);
 */
class SFSidePanel(owner: UIView, private val style: SFSideStyle, sampleX: Double = -1.0) {
    // ANDROID (connettori 22/11): port = presa libera (femmina), plug = spina maschio inserita disegnata da SFPlugArt (testa, gomito, pressacavo, cavo e ombra
    // in un'unica bitmap in cache), al posto del disco pieno a righe e del moncone di cavo; creata in relayout (serve la misura)
    private class Sock(val sensor: String, val label: String, val button: UIButton, var port: SFPlugArt?, var plug: SFPlugArt?, val accent: UIColor, val tag: UILabel, val apart: Boolean, var on: Boolean = false)
    private val socks = ArrayList<Sock>()
    private val band = SFSideBand.left
    val W: Double = band?.bounds?.width ?: 0.0
    val H: Double = band?.bounds?.height ?: 768.0
    val view = UIView(CGRect(0.0, 0.0, W, H))

    init {
        var fill: SFBodyFill? = null
        if (sampleX >= 0) { fill = SFBodyFill(owner, view.bounds, sampleX, true, 30.0); view.addSubview(fill) }
        else view.backgroundColor = style.body
        SFAttachToBand(owner, band, view, fill?.let { f -> { f.sample() } })
    }

    /** Aggiunge una presa (nell'ordine del fianco, dall'alto) e ridistribuisce le prese sull'altezza.
     *  apart = presa staccata dalle altre, in fondo (es. cavo di terapia del defibrillatore). */
    fun addSocket(sensor: String, label: String, accent: UIColor = SFSideAccent(sensor), apart: Boolean = false, action: (UIButton) -> Unit): UIButton {
        val b = UIButton.buttonWithType(UIButtonTypeCustom)
        b.accessibilityIdentifier = "PLUG-$label"
        b.addTarget(UIControlEventTouchUpInside) { action(it as UIButton) }
        val tag = UILabel(); tag.textAlignment = NSTextAlignmentCenter; tag.font = UIFont.boldSystemFontOfSize(11)
        tag.textColor = style.label; tag.text = label; tag.adjustsFontSizeToFitWidth = true; tag.minimumScaleFactor = 0.6
        b.addSubview(tag)
        view.addSubview(b)
        socks.add(Sock(sensor, label, b, null, null, accent, tag, apart))
        relayout()
        return b
    }

    private fun relayout() {
        val n = socks.size; if (n == 0 || W <= 0) return
        val top = 84.0; val bottom = H - 28.0                          // ANDROID (menu 21/11): in alto c'è il tasto ☰ del menu istruttore
        val gapApart = 46.0                                            // spazio prima di una presa staccata
        val nApart = socks.count { it.apart }
        val row = minOf(104.0, (bottom - top - gapApart * nApart) / n)
        val groupH = row * n + gapApart * nApart
        // con prese staccate il gruppo arriva fino in fondo; altrimenti è centrato
        var yAcc = if (nApart > 0) bottom - groupH else top + ((bottom - top) - groupH) / 2
        val d = minOf(54.0, W - 34, row - 26)          // diametro della ghiera
        val cx = W * 0.56
        for (s in socks) {
            if (s.apart) yAcc += gapApart
            val y = yAcc; yAcc += row
            s.button.frame = CGRect(0.0, y, W, row)                       // tutta la riga è toccabile
            val cy = 6 + d / 2
            // ANDROID (connettori 22/11): spina inserita (stato collegato), centrata sulla presa; il cavo arriva al bordo esterno (x 0)
            val ah = d + 24
            val lightBody = style.body.r * 0.3 + style.body.g * 0.59 + style.body.b * 0.11 > 0.5
            val af = CGRect(0.0, cy - ah / 2, W, ah)
            // presa libera: stessa misura (diametro d) e stessa ghiera colorata di prima, disegnata come parte vera (SFPlugArt)
            s.port?.removeFromSuperview(); s.plug?.removeFromSuperview()
            val port = SFPlugArt(af, s.sensor, s.accent, cx, d, lightBody, style.cable, female = true, faceTone = style.socket)
            s.button.insertSubview(port, 0)
            val art = SFPlugArt(af, s.sensor, s.accent, cx, d, lightBody, style.cable)
            art.hidden = !s.on
            s.button.insertSubviewAbove(art, port)
            s.port = port; s.plug = art
            // ANDROID (BugFix #12, #14): la scritta è centrata sull'asse della presa (cx), non sul centro della fascia:
            // prima stava a W/2 mentre la ghiera è a 0,56·W, e le scritte risultavano spostate rispetto ai connettori.
            val lw = 2 * minOf(cx - 2, W - 2 - cx)
            s.tag.frame = CGRect(cx - lw / 2, cy + d / 2 + 3, lw, 15.0)
        }
    }

    /** Spina inserita (sonda collegata) o presa libera. Cambia solo se serve. */
    fun setConnected(sensor: String, on: Boolean) {
        for (s in socks) if (s.sensor == sensor && s.on != on) { s.on = on; s.plug?.hidden = !on }
    }
}

/**
 * Fianco destro: la scocca del layout prosegue oltre il bordo della tela (prolungamento esatto della sua colonna di bordo,
 * vedi SFBodyFill) e si chiude con uno spigolo arrotondato verso il bordo dello schermo. Niente del layout viene stirato.
 * sampleX = colonna del layout da prolungare (in punti; di solito 1021: sulla scocca, vicino al bordo destro).
 */
class SFSideBody(owner: UIView, sampleX: Double = 1021.0, depth: Double = 0.80) {
    private val band = SFSideBand.right
    val view: UIView
    init {
        val W = band?.bounds?.width ?: 0.0; val H = band?.bounds?.height ?: 768.0
        view = UIView(CGRect(0.0, 0.0, W, H)); view.userInteractionEnabled = false
        val fill = SFBodyFill(owner, CGRect(0.0, 0.0, W * depth, H), sampleX, false, 30.0)
        view.addSubview(fill)
        SFAttachToBand(owner, band, view) { fill.sample() }
    }
}

/** Cavo che dal layout prosegue nella fascia sinistra fino al bordo dello schermo. y = centro, in punti. */
class SFSideCable(owner: UIView, y: Double, thickness: Double, color: UIColor) {
    private val band = SFSideBand.left
    val view: UIView
    init {
        val W = band?.bounds?.width ?: 0.0
        view = UIView(CGRect(0.0, y - thickness / 2, W, thickness)); view.userInteractionEnabled = false
        view.backgroundColor = color
        val shine = UIView(CGRect(0.0, 1.5, W, 1.2)); shine.backgroundColor = UIColor.colorWithWhite(1, 0.14); shine.userInteractionEnabled = false
        view.addSubview(shine)
        SFAttachToBand(owner, band, view)
    }
}

/**
 * ANDROID (layout 21/11): fianco destro "attivo" per i layout originali (extraH): la console di terapia del layout prosegue
 * nella fascia destra con tasti veri. Il fondo è la scocca del layout (colonna sampleX, sul bordo sinistro dove c'è solo
 * scocca) con lo spigolo esterno arrotondato; sopra, il layout disegna in `view` le parti della console che stanno oltre
 * il bordo della tela (coordinate della fascia: x = x_tela − larghezza della tela). Compare e sparisce con il layout.
 */
class SFSideDeck(owner: UIView, sampleX: Double = 2.0) {
    private val band = SFSideBand.right
    val W: Double = band?.bounds?.width ?: 0.0
    val H: Double = band?.bounds?.height ?: 768.0
    val view = UIView(CGRect(0.0, 0.0, W, H))
    init {
        val fill = SFBodyFill(owner, view.bounds, sampleX, false, 30.0, 0.22)
        view.addSubview(fill)
        SFAttachToBand(owner, band, view) { fill.sample() }
    }
}
