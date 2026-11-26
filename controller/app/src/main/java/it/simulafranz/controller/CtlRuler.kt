// ANDROID (righello a zone 22/11, richiesta utente; seconda versione dopo «poco chiaro e usabile»): RIGHELLO A ZONE per tutti i
// parametri regolabili (FC, PA, SpO₂, FR, EtCO₂, temperatura, refill, frequenza della RCP, fasi dell'editor degli scenari).
//  - Il righello scorre sotto un indice FISSO al centro (triangolo + linea nel colore del parametro): si trascina il righello,
//    non un pallino; inerzia e aggancio al passo. Bordi sfumati (si capisce che continua) e «‹ trascina ›» finché non lo si trascina.
//  - Le zone cliniche sono lo SFONDO tenue dietro le tacche (verde norma, ambra fuori norma, rosso grave: SISTEMA DI SIGNIFICANTI
//    §3) con il nome in alto (13 pt, solo se c'è posto e mai sotto l'indice). Tacche dal bordo inferiore: maggiori con il numero
//    (15 pt) solo a passi larghi (FC 20, SpO₂ 5, PA 20, FR 5, EtCO₂ 10, Temp 1 °C), minori sottili.
//  - Limiti di allarme del monitor: piccoli triangoli rossi sul bordo inferiore. Transizione: linea bianca + «adesso» = valore di
//    adesso, l'indice colorato = arrivo.
//  - I valori rapidi NON stanno sul righello: sono una riga di tasti veri «Bradi / 40» (CtlParamRow), colorati con la loro zona.
//  - Prestazioni (GPU Mali del Tab Active 2): disegno diretto sul Canvas, Paint / Path / sfumature / testi creati una volta;
//    durante il trascinamento nessuna allocazione per fotogramma (i numeri delle tacche si mettono in memoria la prima volta).
// CtlSlider: cursore «tecnico» (volume) nello stesso stile: pista spessa, pomello grande, zona sensibile di 44 punti.
package it.simulafranz.controller

import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import it.simulafranz.uk.*
import java.util.Locale
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToLong

/** zona clinica [from, to) — level 0 = norma (verde), 1 = fuori norma (ambra), 2 = grave (rosso) */
class RulerZone(val from: Double, val to: Double, val name: String, val level: Int)
/** valore rapido: tasto «nome / valore»; text = seconda riga (di serie il valore), level = zona (-1 = dalla scala) */
class RulerPreset(val value: Double, val name: String, val text: String? = null, val level: Int = -1, val value2: Double = Double.NaN)
/** scala del righello: visible = unità visibili su tutta la larghezza; minor / major = passo delle tacche (major con il numero) */
class RulerSpec(val min: Double, val max: Double, val step: Double, val dec: Int, val visible: Double, val minor: Double, val major: Double,
                val zones: List<RulerZone>, val presets: List<RulerPreset>) {
    fun zoneLevel(v: Double): Int { for (z in zones) if (v >= z.from && v < z.to) return z.level; return 0 }
}

/** Scale, zone cliniche (adulto) e valori rapidi di ogni parametro. Le stesse della web regia (RULER in app.js). */
fun CtlRulerSpec(k: String, min: Double, max: Double, step: Double, dec: Int): RulerSpec {
    fun z(a: Double, b: Double, n: String, l: Int) = RulerZone(a, b, n, l)
    fun p(v: Double, n: String) = RulerPreset(v, n)
    val lo = -1e9; val hi = 1e9
    return when (k) {
        "heart_rate_bpm" -> RulerSpec(min, max, step, dec, 120.0, 5.0, 20.0,
            listOf(z(lo, 40.0, "Bradi grave", 2), z(40.0, 60.0, "Bradicardia", 1), z(60.0, 101.0, "Normale", 0), z(101.0, 150.0, "Tachicardia", 1), z(150.0, hi, "Tachi grave", 2)),
            listOf(p(40.0, "Bradi"), p(60.0, "Bassa"), p(80.0, "Normale"), p(100.0, "Alta"), p(130.0, "Tachi"), p(160.0, "Tachi grave")))
        "spo2_percent" -> RulerSpec(min, max, step, dec, 36.0, 1.0, 5.0,
            listOf(z(lo, 85.0, "Grave", 2), z(85.0, 94.0, "Ipossia", 1), z(94.0, hi, "Normale", 0)),
            listOf(p(75.0, "Grave"), p(85.0, "Ipossia"), p(90.0, "Lieve"), p(94.0, "Limite"), p(98.0, "Normale")))
        "systolic_mmhg" -> RulerSpec(min, max, step, dec, 120.0, 5.0, 20.0,
            listOf(z(lo, 90.0, "Ipotensione", 1), z(90.0, 140.0, "Normale", 0), z(140.0, 180.0, "Ipertensione", 1), z(180.0, hi, "Crisi", 2)),
            listOf(RulerPreset(70.0, "Shock", "70/40", 2, 40.0), RulerPreset(90.0, "Ipotesa", "90/55", 1, 55.0), RulerPreset(120.0, "Normale", "120/80", 0, 80.0),
                   RulerPreset(170.0, "Ipertesa", "170/100", 1, 100.0), RulerPreset(220.0, "Crisi", "220/125", 2, 125.0)))
        "diastolic_mmhg" -> RulerSpec(min, max, step, dec, 90.0, 5.0, 20.0,
            listOf(z(lo, 60.0, "Ipotensione", 1), z(60.0, 90.0, "Normale", 0), z(90.0, 120.0, "Ipertensione", 1), z(120.0, hi, "Crisi", 2)), emptyList())
        "respiratory_rate_rpm" -> RulerSpec(min, max, step, dec, 36.0, 1.0, 5.0,
            listOf(z(lo, 1.0, "Apnea", 2), z(1.0, 12.0, "Bradipnea", 1), z(12.0, 21.0, "Normale", 0), z(21.0, 30.0, "Tachipnea", 1), z(30.0, hi, "Grave", 2)),
            listOf(p(0.0, "Apnea"), p(8.0, "Bradi"), p(12.0, "Bassa"), p(16.0, "Normale"), p(24.0, "Tachi"), p(32.0, "Tachi grave")))
        "etco2_mmhg" -> RulerSpec(min, max, step, dec, 60.0, 2.0, 10.0,
            listOf(z(lo, 35.0, "Ipocapnia", 1), z(35.0, 46.0, "Normale", 0), z(46.0, hi, "Ipercapnia", 1)),
            listOf(p(10.0, "Molto bassa"), p(20.0, "Ipocapnia"), p(35.0, "Normale"), p(45.0, "Limite"), p(60.0, "Ipercapnia")))
        "temperature_c" -> RulerSpec(min, max, step, dec, 7.0, 0.2, 1.0,
            listOf(z(lo, 32.0, "Grave", 2), z(32.0, 35.0, "Ipotermia", 1), z(35.0, 38.0, "Normale", 0), z(38.0, 40.0, "Febbre", 1), z(40.0, hi, "Iperpiressia", 2)),
            listOf(p(34.0, "Ipotermia"), p(35.5, "Bassa"), p(36.8, "Normale"), p(38.5, "Febbre"), p(40.0, "Iperpiressia")))
        "crt_s" -> RulerSpec(min, max, step, dec, 6.0, 0.5, 1.0,
            listOf(z(lo, 2.01, "Normale", 0), z(2.01, 3.01, "Al limite", 1), z(3.01, hi, "Allungato", 2)),
            listOf(p(1.0, "Rapido"), p(2.0, "Normale"), p(3.0, "Limite"), p(4.0, "Allungato"), p(6.0, "Molto lungo")))
        "cpr_rate" -> RulerSpec(min, max, step, dec, 60.0, 2.0, 10.0,
            listOf(z(lo, 100.0, "Lenta", 1), z(100.0, 121.0, "Corretta", 0), z(121.0, hi, "Veloce", 1)), emptyList())
        else -> RulerSpec(min, max, step, dec, (max - min) / 2, step, step * 10, emptyList(), emptyList())
    }
}

fun RulerNum(v: Double, dec: Int): String =
    if (dec > 0) String.format(Locale.US, "%.${dec}f", v).replace(".", ",") else String.format(Locale.US, "%d", v.roundToLong())

/**
 * Righello a zone. onChange(valore, definitivo): definitivo = false mentre scorre (trascinamento / inerzia), true quando si
 * ferma sul valore agganciato al passo. compact = senza i nomi delle zone e con numeri più piccoli (editor degli scenari).
 */
class CtlRuler(frame: CGRect, val spec: RulerSpec, color0: UIColor, val compact: Boolean = false) : UIView(frame) {
    var color: UIColor = color0; set(v) { field = v; idxFill.color = v.argb; invalidate() }
    var onChange: ((Double, Boolean) -> Unit)? = null
    /** valore di adesso durante una transizione (null = nessuna) */
    var nowValue: Double? = null; set(v) { if (field != v) { field = v; invalidate() } }
    /** limiti di allarme del monitor (NaN = nessuno) */
    var limLo = Double.NaN; private set
    var limHi = Double.NaN; private set
    var value: Double = spec.min; private set
    /** il dito è sul righello o il righello sta ancora scorrendo: i valori dal modello si ignorano */
    val busy: Boolean get() = touching || link != null
    var enabledRuler = true

    private var pos = spec.min                    // valore al centro (continuo)
    private var lastEmitted = Double.NaN
    private var touching = false; private var moved = false
    private var downX = 0.0; private var downY = 0.0; private var startPos = 0.0
    private val sx = DoubleArray(6); private val st = DoubleArray(6); private var sn = 0
    private var link: CADisplayLink? = null
    private var vel = 0.0; private var lastT = 0.0
    private var animFrom = 0.0; private var animTo = 0.0; private var animT0 = 0.0; private var animDur = 0.0; private var animFinal = false

    // --- risorse di disegno (create una volta) ---
    private val bgC = CtlRGB(0.043, 0.063, 0.090)
    private val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = bgC.argb }
    private val outRange = Paint().apply { color = UIColor.colorWithWhite(0, 0.55).argb }
    private val tickP = Paint().apply { color = UIColor.colorWithWhite(1, 0.32).argb }
    private val tickMajor = Paint().apply { color = UIColor.colorWithWhite(1, 0.85).argb }
    private val numP = Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply { color = CTL_INK.argb; textSize = 15f; typeface = Typeface.create("sans-serif", Typeface.BOLD); textAlign = Paint.Align.CENTER }
    private val zoneFill = arrayOf(Paint().apply { color = CTL_GREEN.colorWithAlphaComponent(0.15).argb },
                                   Paint().apply { color = CTL_AMBER.colorWithAlphaComponent(0.15).argb },
                                   Paint().apply { color = CTL_RED.colorWithAlphaComponent(0.20).argb })
    private val zoneText = arrayOf(CtlRGB(0.62, 0.94, 0.71), CtlRGB(1.0, 0.82, 0.54), CtlRGB(1.0, 0.67, 0.64)).map {
        Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply { color = it.argb; textSize = 13f; typeface = Typeface.create("sans-serif", Typeface.BOLD); textAlign = Paint.Align.CENTER }
    }.toTypedArray()
    private val zoneSep = Paint().apply { color = UIColor.colorWithWhite(1, 0.14).argb }
    private val limFlag = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = CTL_RED.argb }
    private val idxFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color0.argb }
    private val nowP = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = UIColor.whiteColor.argb }
    private val nowText = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = CTL_ON_LIGHT.argb; textSize = 11f; typeface = Typeface.create("sans-serif", Typeface.BOLD); textAlign = Paint.Align.CENTER }
    private val hintBg = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = bgC.colorWithAlphaComponent(0.94).argb }
    private val hintText = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = CtlRGB(0.84, 0.87, 0.91).argb; textSize = 12f; typeface = Typeface.create("sans-serif", Typeface.BOLD); textAlign = Paint.Align.CENTER }
    private val edgeL = Paint(); private val edgeR = Paint()
    private val clip = Path(); private val tri = Path(); private val limTri = Path()
    private val rf = RectF()
    private var builtW = -1f; private var builtH = -1f
    private val nums = HashMap<Long, String>()          // numeri delle tacche maggiori (in memoria alla prima volta)
    private val zW: FloatArray
    private val hintW: Float
    private val lockIcon: UIImageView

    companion object {
        /** «‹ trascina ›» finché non si trascina un righello (per avvio dell'app) */
        var hintDone = false
        private const val HINT = "‹ trascina ›"
    }

    init {
        backgroundColor = UIColor.clearColor
        zW = FloatArray(spec.zones.size) { zoneText[0].measureText(spec.zones[it].name) }
        hintW = hintText.measureText(HINT) + 14
        if (compact) { numP.textSize = 12f }
        lockIcon = UIImageView(CGRectMake(frame.width - 18, 3, 14, 14)); lockIcon.image = CtlIcon("lock", 14.0, CTL_AMBER)
        lockIcon.userInteractionEnabled = false; lockIcon.hidden = true; addSubview(lockIcon)
        layer.cornerRadius = 10.0
    }

    fun setLocked(on: Boolean) { if (lockIcon.hidden == on) lockIcon.hidden = !on }
    fun setLimits(lo: Double?, hi: Double?) {
        val a = lo ?: Double.NaN; val b = hi ?: Double.NaN
        if (a.equals(limLo) && b.equals(limHi)) return
        limLo = a; limHi = b; invalidate()
    }

    private fun snap(v: Double): Double { val s = spec.step; return (Math.round(v / s) * s).coerceIn(spec.min, spec.max) }
    private val ppu: Double get() = bounds.width / spec.visible

    /** dal modello: ignorato mentre il dito è sul righello o il righello scorre */
    fun showValue(v: Double) {
        if (busy) return
        val s = snap(v)
        if (s == value && s == pos) return
        value = s; pos = s; lastEmitted = s; invalidate()
    }
    /** va al valore (animato) e lo rende definitivo — come una tacca con nome o «debugSet» */
    fun jumpTo(v: Double, notify: Boolean) {
        val s = snap(v)
        value = s; lastEmitted = s
        if (notify) onChange?.invoke(s, true)
        animate(pos, s, 0.22, false)
    }
    /** − / +: sposta il valore senza animazione lunga (la ripetizione la gestisce la riga) */
    fun nudgeTo(v: Double) { stopLink(); val s = snap(v); value = s; pos = s; lastEmitted = s; invalidate() }

    // MARK: - tocchi
    override fun touchesBegan(p: CGPoint) {
        if (!enabledRuler) return
        stopLink(); touching = true; moved = false
        downX = p.x; downY = p.y; startPos = pos; sn = 0; sample(p.x)
    }
    override fun touchesMoved(p: CGPoint) {
        if (!touching) return
        val dx = p.x - downX
        if (!moved && abs(dx) > 6) { moved = true; downX = p.x; startPos = pos; CtlHaptic.tap(); hintDone = true }
        if (!moved) return
        sample(p.x)
        pos = (startPos - (p.x - downX) / ppu).coerceIn(spec.min, spec.max)
        emit(false); invalidate()
    }
    override fun touchesEnded(p: CGPoint) {
        if (!touching) return
        touching = false
        if (!moved) { tapAt(p); return }
        sample(p.x)
        // velocità dagli ultimi campioni (al massimo 100 ms)
        val i1 = (sn - 1) % sx.size; var i0 = i1; var k = 1
        while (k < min(sn, sx.size)) { val j = (sn - 1 - k) % sx.size; if (st[i1] - st[j] > 0.1) break; i0 = j; k++ }
        val dt = st[i1] - st[i0]
        val v = if (dt > 0.005) (sx[i1] - sx[i0]) / dt else 0.0          // punti al secondo
        if (abs(v) > 180) { vel = -v / ppu; lastT = CACurrentMediaTime(); startLink() } else settle()
    }
    override fun touchesCancelled(p: CGPoint) { if (!touching) return; touching = false; if (moved) settle() }

    private fun sample(x: Double) { val i = sn % sx.size; sx[i] = x; st[i] = CACurrentMediaTime(); sn++ }

    private fun tapAt(p: CGPoint) {}       // i valori rapidi sono tasti veri sopra il righello (CtlParamRow)

    private fun emit(final: Boolean) {
        val s = snap(pos)
        if (final) { value = s; lastEmitted = s; onChange?.invoke(s, true); return }
        if (s != lastEmitted) { lastEmitted = s; value = s; onChange?.invoke(s, false) }
    }
    private fun settle() { animate(pos, snap(pos), 0.12, true) }
    private fun animate(from: Double, to: Double, dur: Double, final: Boolean) {
        stopLink()
        animFrom = from; animTo = to; animT0 = CACurrentMediaTime(); animDur = dur; animFinal = final; vel = 0.0
        if (abs(from - to) < 1e-9) { pos = to; invalidate(); if (final) emit(true); return }
        startLink()
    }
    private fun startLink() { if (link == null) link = CADisplayLink { tick() }.start() }
    private fun stopLink() { link?.invalidate(); link = null }
    private fun tick() {
        val t = CACurrentMediaTime()
        if (vel != 0.0) {                                    // inerzia
            val dt = min(0.05, t - lastT); lastT = t
            vel *= exp(-dt * 5.0)
            pos += vel * dt
            if (pos <= spec.min || pos >= spec.max) { pos = pos.coerceIn(spec.min, spec.max); vel = 0.0 }
            emit(false); invalidate()
            if (abs(vel) * ppu < 30) { vel = 0.0; stopLink(); settle() }
            return
        }
        val f = ((t - animT0) / animDur).coerceIn(0.0, 1.0)
        val e = 1 - (1 - f) * (1 - f) * (1 - f)
        pos = animFrom + (animTo - animFrom) * e
        invalidate()
        if (f >= 1.0) { pos = animTo; stopLink(); if (animFinal) emit(true) }
    }
    override fun didMoveToWindow() { if (window == null) { if (link != null) { stopLink(); if (vel != 0.0 || animFinal) { vel = 0.0; pos = snap(pos); emit(true) } }; touching = false } }

    // MARK: - disegno
    private fun build(w: Float, h: Float) {
        builtW = w; builtH = h
        clip.reset(); clip.addRoundRect(0f, 0f, w, h, 10f, 10f, Path.Direction.CW)
        val c0 = bg.color; val c1 = bg.color and 0x00ffffff
        edgeL.shader = LinearGradient(0f, 0f, 40f, 0f, c0, c1, Shader.TileMode.CLAMP)
        edgeR.shader = LinearGradient(w - 40f, 0f, w, 0f, c1, c0, Shader.TileMode.CLAMP)
        lockIcon.frame = CGRectMake(w - 18.0, 3.0, 14.0, 14.0)
    }

    override fun drawRect(ctx: CGContext, rect: CGRect) {
        val c: Canvas = ctx.canvas
        val w = bounds.width.toFloat(); val h = bounds.height.toFloat()
        if (w <= 0 || h <= 0) return
        if (w != builtW || h != builtH) build(w, h)
        c.save(); c.clipPath(clip)
        c.drawRect(0f, 0f, w, h, bg)
        val cx = w / 2f
        val k = ppu.toFloat()
        // zone: sfondo tenue dietro le tacche, nome in alto (mai sotto l'indice)
        for (i in spec.zones.indices) {
            val z = spec.zones[i]
            val a = max(xOf(max(z.from, spec.min), cx, k), 0f); val b = min(xOf(min(z.to, spec.max), cx, k), w)
            if (b <= a) continue
            c.drawRect(a, 0f, b, h, zoneFill[z.level])
            val xs = xOf(z.from, cx, k)
            if (z.from > spec.min && xs > 0 && xs < w) c.drawRect(xs - 0.5f, 0f, xs + 0.5f, h, zoneSep)
            if (compact || !hintDone) continue          // con «‹ trascina ›» in alto i nomi delle zone aspettano
            val tw = zW[i]
            if (b - a <= tw + 16) continue
            var tx = ((a + b) / 2).coerceIn(a + tw / 2 + 8, b - tw / 2 - 8)
            if (abs(tx - cx) < tw / 2 + 14) {
                val l = cx - 14 - tw / 2; val r = cx + 14 + tw / 2
                tx = if (l >= a + tw / 2 + 6 && (abs(l - (a + b) / 2) <= abs(r - (a + b) / 2) || r > b - tw / 2 - 6)) l
                     else if (r <= b - tw / 2 - 6) r else Float.NaN
            }
            if (!tx.isNaN()) c.drawText(z.name, tx, 17f, zoneText[z.level])
        }
        // fuori dall'intervallo del parametro: più scuro
        val xa = xOf(spec.min, cx, k); val xb = xOf(spec.max, cx, k)
        if (xa > 0) c.drawRect(0f, 0f, xa, h, outRange)
        if (xb < w) c.drawRect(xb, 0f, w, h, outRange)
        // tacche dal bordo inferiore: maggiori con il numero, minori sottili
        val majH = if (compact) 10f else 15f; val minH = if (compact) 5f else 7f
        val numBase = h - majH - (if (compact) 3f else 5f)
        val rm = Math.round(spec.major / spec.minor).toInt().coerceAtLeast(1)
        val half = spec.visible / 2 + spec.major
        val k0 = ceil((pos - half) / spec.minor).toLong(); val k1 = floor((pos + half) / spec.minor).toLong()
        var kk = k0
        while (kk <= k1) {
            val v = kk * spec.minor
            if (v >= spec.min - 1e-9 && v <= spec.max + 1e-9) {
                val x = xOf(v, cx, k)
                if (Math.floorMod(kk, rm.toLong()) == 0L) {
                    c.drawRect(x - 1f, h - majH, x + 1f, h, tickMajor)
                    val key = Math.round(v / spec.major)
                    val s = nums[key] ?: RulerNum(key * spec.major, if (spec.major < 1) 1 else 0).also { nums[key] = it }
                    c.drawText(s, x, numBase, numP)
                } else c.drawRect(x - 0.5f, h - minH, x + 0.5f, h, tickP)
            }
            kk++
        }
        // limiti di allarme: piccoli triangoli rossi sul bordo inferiore
        for (li in 0..1) {
            val lv = if (li == 0) limLo else limHi
            if (lv.isNaN()) continue
            val x = xOf(lv, cx, k); if (x < -6 || x > w + 6) continue
            limTri.reset(); limTri.moveTo(x - 5f, h); limTri.lineTo(x + 5f, h); limTri.lineTo(x, h - 8f); limTri.close()
            c.drawPath(limTri, limFlag)
        }
        // bordi sfumati: il righello continua oltre
        c.drawRect(0f, 0f, 40f, h, edgeL); c.drawRect(w - 40f, 0f, w, h, edgeR)
        // adesso (transizione)
        nowValue?.let { nv ->
            val x = xOf(nv, cx, k).coerceIn(4f, w - 4f)
            if (abs(x - cx) > 3) {
                c.drawRect(x - 1f, 22f, x + 1f, h, nowP)
                val pw = 46f; val px = x.coerceIn(pw / 2 + 2, w - pw / 2 - 2)
                rf.set(px - pw / 2, 4f, px + pw / 2, 21f); c.drawRoundRect(rf, 5f, 5f, nowP)
                c.drawText("adesso", px, 16.5f, nowText)
            }
        }
        // indice fisso: triangolo + linea nel colore del parametro
        // la linea si interrompe sulla riga dei numeri (il numero sotto l'indice resta leggibile)
        val nt = numBase - numP.textSize * 0.78f - 2f
        c.drawRect(cx - 1.5f, 10f, cx + 1.5f, nt, idxFill); c.drawRect(cx - 1.5f, numBase + 3f, cx + 1.5f, h, idxFill)
        tri.reset(); tri.moveTo(cx - 9f, 0f); tri.lineTo(cx + 9f, 0f); tri.lineTo(cx, 12f); tri.close()
        c.drawPath(tri, idxFill)
        if (!hintDone && !compact) {
            rf.set(cx - 14 - hintW, 2f, cx - 14, 20f); c.drawRoundRect(rf, 5f, 5f, hintBg)
            c.drawText(HINT, cx - 14 - hintW / 2, 15.5f, hintText)
        }
        c.restore()
    }
    private fun xOf(v: Double, cx: Float, k: Float): Float = cx + ((v - pos) * k).toFloat()
}

/**
 * Cursore tecnico (volume e simili) nello stile del righello: pista di 10 punti con il riempimento nel colore, pomello di
 * 30 punti con il bordo, zona sensibile alta quanto la vista (≥ 44 punti). Stesse azioni di UISlider (ValueChanged / TouchUp).
 */
class CtlSlider(frame: CGRect, color: UIColor) : UISlider(frame) {
    private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = CtlRGB(0.075, 0.09, 0.115).argb }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color.argb }
    private val knob = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = CTL_INK.argb }
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color.argb; style = Paint.Style.STROKE; strokeWidth = 3f }
    private val tick = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = UIColor.colorWithWhite(1, 0.25).argb; strokeWidth = 1.2f }
    private val rf = RectF()
    init { minimumTrackTintColor = UIColor.clearColor; maximumTrackTintColor = UIColor.clearColor }
    override fun drawRect(ctx: CGContext, rect: CGRect) {
        val c = ctx.canvas
        val w = bounds.width.toFloat(); val h = bounds.height.toFloat(); val cy = h / 2
        val r = 14f
        val f = if (maximumValue > minimumValue) ((value - minimumValue) / (maximumValue - minimumValue)).toFloat().coerceIn(0f, 1f) else 0f
        val x = r + f * (w - 2 * r)
        rf.set(r - 5, cy - 5, w - r + 5, cy + 5); c.drawRoundRect(rf, 5f, 5f, track)
        for (i in 1..3) { val tx = r + (w - 2 * r) * i / 4f; c.drawLine(tx, cy + 8, tx, cy + 12, tick) }
        rf.set(r - 5, cy - 5, x, cy + 5); c.drawRoundRect(rf, 5f, 5f, fill)
        c.drawCircle(x, cy, r, knob); c.drawCircle(x, cy, r - 1.5f, ring)
    }
}
