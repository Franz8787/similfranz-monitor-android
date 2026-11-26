// I DAE non hanno prese sul fianco né una scocca da prolungare nelle fasce: l'apparecchio sta al centro dello schermo
// INTERO (non della tela 1024×768) ed è appoggiato su un piano, davanti a un fondale con una luce morbida alle spalle,
// come nelle foto ufficiali dei prodotti. Il fondale è UNO solo, disegnato in coordinate dello schermo intero e diviso
// in tre viste (fascia sinistra | tela | fascia destra): nessuno stacco ai bordi della tela, niente bande nere.
// Il tasto ☰ del menu istruttore (in alto nella fascia sinistra) resta sopra il fondale.
package it.simulafranz.monitor

import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.view.View
import it.simulafranz.uk.*

/** Colori del palco (ARGB). floorY = linea dell'orizzonte del piano d'appoggio, in punti (0 = nessun piano). */
class SFStageStyle(
    val glow: Int,          // luce alle spalle dell'apparecchio
    val wall: Int,          // fondale a metà
    val edge: Int,          // fondale ai bordi dello schermo (vignettatura)
    val floorTop: Int,      // piano, verso l'orizzonte
    val floorBottom: Int,   // piano, in primo piano
    val floorY: Double,
    val glowY: Double = 330.0
)

/** Una porzione del palco: ox = x (in punti, sullo schermo intero) del suo bordo sinistro. */
class SFStageView(frame: CGRect, private val ox: Double, private val stageW: Double, private val style: SFStageStyle,
                  private val extra: ((Canvas) -> Unit)?) : UIView(frame) {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG or Paint.DITHER_FLAG)
    private val m = Matrix()
    init { userInteractionEnabled = false; opaque = true }

    override fun drawRect(ctx: CGContext, rect: CGRect) {
        val c = ctx.canvas
        val w = rect.width.toFloat(); val h = rect.height.toFloat()
        c.save()
        c.translate(-ox.toFloat(), 0f)
        val x0 = ox.toFloat(); val x1 = x0 + w
        val cx = (stageW / 2).toFloat(); val cy = style.glowY.toFloat()
        // fondale: luce ellittica alle spalle dell'apparecchio che sfuma verso i bordi
        val r = (stageW * 0.62).toFloat()
        val g = RadialGradient(0f, 0f, 1f, intArrayOf(style.glow, style.wall, style.edge), floatArrayOf(0f, 0.45f, 1f), Shader.TileMode.CLAMP)
        m.reset(); m.setScale(r, r * 0.80f); m.postTranslate(cx, cy); g.setLocalMatrix(m)
        p.shader = g
        c.drawRect(x0, 0f, x1, h, p)
        // piano d'appoggio: dall'orizzonte verso chi guarda, più chiaro al centro (riflesso della luce)
        val fy = style.floorY.toFloat()
        if (fy > 0 && fy < h) {
            p.shader = LinearGradient(0f, fy, 0f, h, style.floorTop, style.floorBottom, Shader.TileMode.CLAMP)
            c.drawRect(x0, fy, x1, h, p)
            val pool = RadialGradient(0f, 0f, 1f, intArrayOf(0x26FFFFFF, 0x00FFFFFF), floatArrayOf(0f, 1f), Shader.TileMode.CLAMP)
            m.reset(); m.setScale((stageW * 0.42).toFloat(), (h - fy) * 1.1f); m.postTranslate(cx, fy); pool.setLocalMatrix(m)
            p.shader = pool
            c.drawRect(x0, fy, x1, h, p)
            // spigolo del piano: un filo di luce che sfuma ai lati
            p.shader = LinearGradient(0f, 0f, stageW.toFloat(), 0f, intArrayOf(0x00FFFFFF, 0x30FFFFFF, 0x00FFFFFF), floatArrayOf(0.05f, 0.5f, 0.95f), Shader.TileMode.CLAMP)
            c.drawRect(x0, fy, x1, fy + 1.2f, p)
        }
        p.shader = null
        extra?.invoke(c)
        c.restore()
    }
}

/**
 * Monta il palco per il layout `owner` (che occupa la tela): una vista sotto a tutto nel layout e una in fondo a
 * ciascuna fascia, che compaiono e se ne vanno con il layout. `extra` disegna (in coordinate dello schermo intero,
 * in punti) ciò che deve attraversare le fasce, es. il cavo delle piastre che entra dal bordo sinistro.
 */
fun SFAedStage(owner: UIView, style: SFStageStyle, extra: ((Canvas) -> Unit)? = null) {
    val bw = SFSideBand.left?.bounds?.width ?: 0.0
    val W = owner.bounds.width; val H = owner.bounds.height
    val stageW = W + 2 * bw
    owner.backgroundColor = UIColor.clearColor
    owner.insertSubview(SFStageView(CGRect(0.0, 0.0, W, H), bw, stageW, style, extra), 0)
    for ((band, ox) in listOf(SFSideBand.left to 0.0, SFSideBand.right to bw + W)) {
        if (band == null || band.bounds.width <= 0) continue
        val v = SFStageView(CGRect(0.0, 0.0, band.bounds.width, H), ox, stageW, style, extra)
        SFSideBand.register(v, owner)
        // in fondo alla fascia: il tasto ☰ del menu istruttore resta sopra
        owner.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(x: View) { if (v.superview !== band) band.insertSubview(v, 0) }
            override fun onViewDetachedFromWindow(x: View) { v.removeFromSuperview() }
        })
        if (owner.isAttachedToWindow) band.insertSubview(v, 0)
    }
    // ANDROID (resa dei DAE 22/11, richiesta utente): batteria DEL TABLET (per l'istruttore, mai quella simulata) sempre
    // visibile, anche a DAE spento, FUORI dal dispositivo: in basso a destra dello schermo, nella fascia destra (o
    // nell'angolo della tela se la fascia manca), su una pastiglia scura, sopra a tutto.
    val rb = SFSideBand.right
    val bw2 = 96.0; val bh = 26.0; val m = 8.0
    val tb = SFAedTabletBattery(if (rb != null && rb.bounds.width >= bw2 + 2 * m) CGRectMake(rb.bounds.width - bw2 - m, H - bh - m, bw2, bh)
                                else CGRectMake(W - bw2 - m, H - bh - m, bw2, bh))
    if (rb != null && rb.bounds.width >= bw2 + 2 * m) SFAttachToBand(owner, rb, tb) else owner.addSubview(tb)
}

/** Pastiglia scura con la batteria vera del tablet (icona comune SFBatteryIcon, percentuale, fulmine in carica);
 *  si aggiorna da sola ogni 2 s finché è a schermo. */
class SFAedTabletBattery(frame: CGRect) : UIView(frame) {
    private val icon: SFBatteryIcon; private val pct: UILabel
    private val tick = object : Runnable { override fun run() { refresh(); if (isAttachedToWindow) postDelayed(this, 2000) } }
    init {
        userInteractionEnabled = false
        backgroundColor = UIColor.colorWithWhite(0.0, 0.55); layer.cornerRadius = frame.height / 2
        layer.borderWidth = 1.0; layer.borderColor = UIColor.colorWithWhite(1.0, 0.18).CGColor
        val ih = 13.0
        icon = SFBatteryIcon(CGRectMake(10.0, (frame.height - ih) / 2, 28.0, ih), UIColor.colorWithWhite(0.88, 1)); addSubview(icon)
        pct = UILabel(CGRectMake(42.0, 0.0, frame.width - 48.0, frame.height)); pct.backgroundColor = UIColor.clearColor
        pct.font = UIFont.boldSystemFontOfSize(13); pct.textColor = UIColor.colorWithWhite(0.92, 1); pct.textAlignment = NSTextAlignmentRight
        addSubview(pct)
        addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) { removeCallbacks(tick); post(tick) }
            override fun onViewDetachedFromWindow(v: View) { removeCallbacks(tick) }
        })
        refresh()
    }
    private fun refresh() {
        val b = SFBatteryRead()                 // sempre il tablet: mai SFBatterySimFor (guasti simulati del DAE)
        icon.update(b)
        val t = if (b.level < 0) "--%" else String.format(java.util.Locale.US, "%d%%", Math.round(b.level * 100))
        if (pct.text != t) pct.text = t
        val c = if (b.level >= 0 && b.level < 0.20f) SFBatteryColor(b.level) else UIColor.colorWithWhite(0.92, 1)
        if (pct.textColor != c) pct.textColor = c
    }
}
