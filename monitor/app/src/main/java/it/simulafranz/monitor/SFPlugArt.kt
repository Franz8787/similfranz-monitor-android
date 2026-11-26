// ANDROID (connettori 22/11): SPINA MASCHIO INSERITA nelle prese del fianco (SFSidePanel), al posto del disco pieno a righe
// con il moncone di cavo, e (female = true) PRESA LIBERA al posto della ghiera con tre pin: stessa misura, stessa ghiera nel
// colore della sonda e stessa etichetta di prima; la spina inserita copre la presa lasciando a vista la ghiera colorata.
// Disegno "quasi fotografico" con forme semplici: spina a 90° (come i connettori circolari a innesto dei monitor) vista di fronte:
//  - testa sulla presa: guscio in plastica opaca con volume (riflesso morbido in alto, ombra in basso), zigrinatura dove si
//    impugna, linea di giunzione, faccia con scritta incisa e puntino d'indice rosso;
//  - corpo a gomito verso il bordo esterno con il collarino nel colore della sonda (sobrio, smorzato);
//  - pressacavo in gomma con anelli, poi cavo cilindrico (chiaro al centro, scuro ai bordi) con una curva naturale fino al
//    bordo dello schermo; ombra portata sul corpo del dispositivo (luce dall'alto, come i tasti SFTactile).
// Varianti: circolare multipin (SpO2, IBP, TEMP), ECG multipolare rettangolare, raccordo NIBP metallico con tubo,
// luer della linea CO2 con tubicino trasparente, spina grossa delle placche con levetta di sgancio.
// PRESTAZIONI (GPU Mali): tutto è disegnato UNA volta in una bitmap (cache per sonda, tono del layout e misura); a schermo
// la vista disegna solo quella bitmap, e solo quando cambia (la spina compare/scompare con hidden).
package it.simulafranz.monitor

import android.graphics.*
import android.util.LruCache
import it.simulafranz.uk.*

class SFPlugArt(frame: CGRect, private val sensor: String, private val accent: UIColor, private val cx: Double, private val d: Double,
                private val lightBody: Boolean, private val cableTone: UIColor,
                private val female: Boolean = false, private val faceTone: UIColor = UIColor.colorWithWhite(0.15, 1)) : UIView(frame) {
    init { userInteractionEnabled = false }
    private val bp = Paint(Paint.FILTER_BITMAP_FLAG)
    private val dst = RectF()

    override fun drawRect(ctx: CGContext, rect: CGRect) {
        val w = rect.width; val h = rect.height
        if (w <= 0 || h <= 0) return
        val key = "$female|${faceTone.argb}|$sensor|$lightBody|${accent.argb}|${cableTone.argb}|$w|$h|$cx|$d|${Uk.scale}"
        var b = cache.get(key)
        if (b == null) {
            val k = Uk.scale
            b = Bitmap.createBitmap(Math.ceil(w * k).toInt().coerceAtLeast(1), Math.ceil(h * k).toInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888)
            val c = Canvas(b); c.scale(k, k)
            render(c, w.toFloat(), h.toFloat())
            cache.put(key, b)
        }
        dst.set(0f, 0f, w.toFloat(), h.toFloat())
        ctx.canvas.drawBitmap(b, null, dst, bp)
    }

    // ---------- disegno (una volta sola) ----------
    private val P = Paint(Paint.ANTI_ALIAS_FLAG)

    private fun gray(v: Double, a: Int = 255) = Color.argb(a, (v.coerceIn(0.0, 1.0) * 255).toInt(), (v.coerceIn(0.0, 1.0) * 255).toInt(), (v.coerceIn(0.0, 1.0) * 255).toInt())
    private fun shade(col: Int, f: Double): Int {          // f > 0 schiarisce verso il bianco, f < 0 scurisce
        val r = Color.red(col); val g = Color.green(col); val b = Color.blue(col)
        fun m(x: Int) = if (f >= 0) (x + (255 - x) * f).toInt() else (x * (1 + f)).toInt()
        return Color.argb(Color.alpha(col), m(r).coerceIn(0, 255), m(g).coerceIn(0, 255), m(b).coerceIn(0, 255))
    }
    /** colore della sonda smorzato (collarino sobrio come sui connettori veri, non fluo) */
    private fun probeColor(): Int {
        val r = accent.r; val g = accent.g; val b = accent.b
        val l = 0.3 * r + 0.59 * g + 0.11 * b
        fun m(x: Double) = ((x * 0.62 + l * 0.38) * 0.80).coerceIn(0.0, 1.0)
        return Color.rgb((m(r) * 255).toInt(), (m(g) * 255).toInt(), (m(b) * 255).toInt())
    }

    private fun lin(x0: Float, y0: Float, x1: Float, y1: Float, cols: IntArray, pos: FloatArray?) =
        LinearGradient(x0, y0, x1, y1, cols, pos, Shader.TileMode.CLAMP)

    /** ombra portata morbida di una forma (luce dall'alto: scende di dy) */
    private fun dropShadow(c: Canvas, p: Path, dx: Float, dy: Float, blur: Float, alpha: Int) {
        P.reset(); P.isAntiAlias = true; P.color = Color.argb(alpha, 0, 0, 0)
        P.maskFilter = BlurMaskFilter(blur, BlurMaskFilter.Blur.NORMAL)
        c.save(); c.translate(dx, dy); c.drawPath(p, P); c.restore()
        P.maskFilter = null
    }

    /** cilindro orizzontale (corpo, pressacavo): chiaro poco sopra il centro, scuro ai bordi */
    private fun cylinderShader(top: Float, bot: Float, base: Int, gloss: Double): Shader =
        lin(0f, top, 0f, bot, intArrayOf(shade(base, -0.35), shade(base, 0.10 + gloss), shade(base, 0.03), shade(base, -0.22), shade(base, -0.55)),
            floatArrayOf(0f, 0.30f, 0.50f, 0.80f, 1f))

    private fun render(c: Canvas, w: Float, h: Float) {
        if (female) { renderSocket(c, w, h); return }
        val cy = h / 2
        val X = cx.toFloat(); val D = d.toFloat()
        val shellBase = if (lightBody) gray(0.33) else gray(0.25)       // plastica del connettore: grigio scuro opaco
        val shAlpha = if (lightBody) 95 else 150                          // ombra portata: si vede di più sulla scocca chiara
        val kind = when (sensor) { "ecg" -> "ecg"; "pads" -> "pads"; "nibp" -> "nibp"; "etco2" -> "co2"; else -> "circ" }
        val probe = probeColor()

        // misure della testa (sta dentro la ghiera della presa: l'anello colorato della presa resta visibile attorno)
        val capR = D / 2 - 4f
        val capW: Float; val capH: Float
        when (kind) {
            "ecg" -> { capW = D - 4f; capH = D * 0.70f }
            "pads" -> { capW = D + 4f; capH = D * 0.80f }
            "nibp" -> { capW = D * 0.66f; capH = capW }
            "co2" -> { capW = D * 0.56f; capH = capW }
            else -> { capW = 2 * capR; capH = capW }
        }
        val capL = X - capW / 2
        // gomito, collarino, pressacavo, cavo (da destra verso sinistra)
        val bodyH = when (kind) { "pads" -> D * 0.52f; "ecg" -> D * 0.46f; "nibp" -> D * 0.34f; "co2" -> D * 0.26f; else -> D * 0.42f }
        val cableT = when (kind) { "pads" -> 11f; "ecg" -> 9.5f; "nibp" -> 8.5f; "co2" -> 4.5f; "circ" -> if (sensor == "temp") 6f else 7.5f; else -> 7.5f }
        val bodyR = capL + 6f                              // il corpo entra sotto la testa
        val bodyL = capL - (if (kind == "co2") 4f else 7f)
        val bootL = maxOf(6f, bodyL - (if (kind == "co2" || kind == "nibp") 9f else 15f))
        val sag = 5f                                        // il cavo scende un poco verso il bordo (peso)
        val cableEnd = cy + sag

        // ---------- ombra portata di tutto il connettore ----------
        val sh = Path()
        sh.addRoundRect(bootL, cy - bodyH / 2, bodyR, cy + bodyH / 2, bodyH / 2, bodyH / 2, Path.Direction.CW)
        if (kind == "ecg" || kind == "pads") sh.addRoundRect(capL, cy - capH / 2, capL + capW, cy + capH / 2, 7f, 7f, Path.Direction.CW)
        else sh.addCircle(X, cy, capW / 2, Path.Direction.CW)
        dropShadow(c, sh, 1.2f, 3.2f, 3.2f, shAlpha)
        val cablePath = Path().apply { moveTo(bootL + 2f, cy); cubicTo(bootL * 0.55f, cy, bootL * 0.35f, cableEnd, -3f, cableEnd) }
        P.reset(); P.isAntiAlias = true; P.style = Paint.Style.STROKE; P.strokeWidth = cableT; P.color = Color.argb(shAlpha, 0, 0, 0)
        P.maskFilter = BlurMaskFilter(2.6f, BlurMaskFilter.Blur.NORMAL)
        c.save(); c.translate(0.8f, 3.4f); c.drawPath(cablePath, P); c.restore(); P.maskFilter = null

        // ---------- cavo (o tubo) ----------
        drawCable(c, cablePath, cableT, kind)

        // ---------- pressacavo in gomma con anelli (rastremato verso il cavo) ----------
        val bootBase = if (kind == "co2") gray(0.86) else if (kind == "nibp") shade(cableTone.argb, 0.05) else shade(shellBase, -0.30)
        val bTopR = bodyH * 0.40f; val bTopL = cableT * 0.62f
        val boot = Path().apply {
            moveTo(bodyL + 1f, cy - bTopR); lineTo(bootL, cy - bTopL); lineTo(bootL, cy + bTopL); lineTo(bodyL + 1f, cy + bTopR); close()
        }
        P.reset(); P.isAntiAlias = true; P.color = Color.BLACK; P.shader = cylinderShader(cy - bTopR, cy + bTopR, bootBase, 0.04); c.drawPath(boot, P); P.shader = null
        if (kind != "co2") {                                // anelli: solco scuro + filo di luce
            val n = if (kind == "nibp") 2 else 4
            for (i in 1..n) {
                val t = i / (n + 1f); val x = bodyL + 1f - (bodyL + 1f - bootL) * t
                val hh = bTopR + (bTopL - bTopR) * t
                P.reset(); P.isAntiAlias = true; P.strokeWidth = 1.1f; P.color = Color.argb(150, 0, 0, 0)
                c.drawLine(x, cy - hh + 0.6f, x, cy + hh - 0.6f, P)
                P.color = Color.argb(45, 255, 255, 255); P.strokeWidth = 0.7f
                c.drawLine(x + 1.0f, cy - hh + 1f, x + 1.0f, cy + hh * 0.2f, P)
            }
        }

        // ---------- corpo a gomito con il collarino colorato ----------
        val body = RectF(bodyL, cy - bodyH / 2, bodyR, cy + bodyH / 2)
        val bodyCol = if (kind == "nibp") gray(0.62) else if (kind == "co2") gray(0.90) else shellBase
        P.reset(); P.isAntiAlias = true; P.color = Color.BLACK; P.shader = cylinderShader(body.top, body.bottom, bodyCol, if (kind == "nibp") 0.30 else 0.10)
        c.drawRoundRect(body, bodyH * 0.22f, bodyH * 0.22f, P); P.shader = null
        // collarino nel colore della sonda (fascia sul corpo, stessa luce cilindrica)
        val colW = if (kind == "co2") 3.5f else 5.5f
        val colL = bodyL + 1.5f
        P.reset(); P.isAntiAlias = true
        P.color = Color.BLACK; P.shader = cylinderShader(body.top + 0.3f, body.bottom - 0.3f, if (kind == "co2") Color.rgb(196, 168, 52) else probe, 0.08)
        c.drawRect(colL, body.top + 0.3f, colL + colW, body.bottom - 0.3f, P); P.shader = null
        P.color = Color.argb(110, 0, 0, 0); P.strokeWidth = 0.7f
        c.drawLine(colL, body.top + 0.6f, colL, body.bottom - 0.6f, P); c.drawLine(colL + colW, body.top + 0.6f, colL + colW, body.bottom - 0.6f, P)
        // linea di giunzione delle due metà del guscio
        if (kind != "co2") {
            P.color = Color.argb(120, 0, 0, 0); P.strokeWidth = 0.7f
            c.drawLine(colL + colW + 1f, cy + 0.4f, bodyR - 2f, cy + 0.4f, P)
            P.color = Color.argb(30, 255, 255, 255); c.drawLine(colL + colW + 1f, cy + 1.2f, bodyR - 2f, cy + 1.2f, P)
        }

        // ---------- testa sulla presa ----------
        when (kind) {
            "ecg" -> rectHead(c, capL, cy - capH / 2, capW, capH, shellBase, probe, "ECG", false)
            "pads" -> rectHead(c, capL, cy - capH / 2, capW, capH, shade(shellBase, -0.08), probe, null, true)
            "nibp" -> nibpHead(c, X, cy, capW / 2)
            "co2" -> luerHead(c, X, cy, capW / 2)
            else -> roundHead(c, X, cy, capW / 2, shellBase, when (sensor) { "spo2" -> "SpO2"; "art" -> "IBP"; "temp" -> "TEMP"; else -> "" })
        }
    }

    private fun drawCable(c: Canvas, path: Path, t: Float, kind: String) {
        P.reset(); P.isAntiAlias = true; P.style = Paint.Style.STROKE; P.strokeCap = Paint.Cap.BUTT
        if (kind == "co2") {
            // tubicino trasparente: bordi grigi, interno chiaro, riflesso netto
            P.strokeWidth = t; P.color = gray(0.62, 235); c.drawPath(path, P)
            P.strokeWidth = t * 0.55f; P.color = gray(0.90, 230); c.drawPath(path, P)
            P.strokeWidth = t * 0.22f; P.color = Color.argb(220, 255, 255, 255)
            c.save(); c.translate(0f, -t * 0.18f); c.drawPath(path, P); c.restore()
            return
        }
        val base = if (kind == "nibp") shade(cableTone.argb, 0.10) else cableTone.argb
        // cilindro: bordo scuro, corpo, banda di luce poco sopra il centro, filo di riflesso
        P.strokeWidth = t; P.color = shade(base, -0.55); c.drawPath(path, P)
        P.strokeWidth = t * 0.78f; P.color = base
        c.save(); c.translate(0f, -t * 0.04f); c.drawPath(path, P); c.restore()
        P.strokeWidth = t * 0.42f; P.color = shade(base, 0.16)
        c.save(); c.translate(0f, -t * 0.12f); c.drawPath(path, P); c.restore()
        P.strokeWidth = maxOf(0.8f, t * 0.12f); P.color = Color.argb(if (kind == "nibp") 90 else 60, 255, 255, 255)
        c.save(); c.translate(0f, -t * 0.20f); c.drawPath(path, P); c.restore()
    }

    /** testa circolare: zigrinatura sul bordo, faccia leggermente incassata con scritta incisa e indice rosso */
    private fun roundHead(c: Canvas, x: Float, y: Float, r: Float, base: Int, text: String) {
        // guscio: volume sferico schiacciato, luce in alto a sinistra
        P.reset(); P.isAntiAlias = true
        P.color = Color.BLACK; P.shader = RadialGradient(x - r * 0.30f, y - r * 0.45f, r * 1.55f, intArrayOf(shade(base, 0.30), shade(base, 0.02), shade(base, -0.45)),
                                  floatArrayOf(0f, 0.45f, 1f), Shader.TileMode.CLAMP)
        c.drawCircle(x, y, r, P); P.shader = null
        // zigrinatura: tacche radiali sulla corona
        val n = 40; val r0 = r - 5.5f; val r1 = r - 0.8f
        P.strokeWidth = 1.1f
        for (i in 0 until n) {
            val a = (i * 2 * Math.PI / n); val ca = Math.cos(a).toFloat(); val sa = Math.sin(a).toFloat()
            val lit = (-sa * 0.7f - ca * 0.3f)              // le tacche verso la luce hanno il filo chiaro più evidente
            P.color = Color.argb(120, 0, 0, 0)
            c.drawLine(x + ca * r0, y + sa * r0, x + ca * r1, y + sa * r1, P)
            val a2 = a + 0.045; val c2 = Math.cos(a2).toFloat(); val s2 = Math.sin(a2).toFloat()
            P.color = Color.argb((28 + 40 * maxOf(0f, lit)).toInt(), 255, 255, 255)
            c.drawLine(x + c2 * r0, y + s2 * r0, x + c2 * r1, y + s2 * r1, P)
        }
        // bordo esterno: filo di luce in alto, scuro in basso
        P.style = Paint.Style.STROKE; P.strokeWidth = 1f
        P.color = Color.BLACK; P.shader = lin(0f, y - r, 0f, y + r, intArrayOf(Color.argb(90, 255, 255, 255), Color.argb(0, 255, 255, 255), Color.argb(140, 0, 0, 0)), floatArrayOf(0f, 0.5f, 1f))
        c.drawCircle(x, y, r - 0.5f, P); P.shader = null; P.style = Paint.Style.FILL
        // faccia interna (incassata: ombra in alto, luce in basso) + linea di giunzione
        val fr = r - 6.5f
        P.color = Color.BLACK; P.shader = lin(0f, y - fr, 0f, y + fr, intArrayOf(shade(base, -0.28), shade(base, 0.0), shade(base, 0.10)), null)
        c.drawCircle(x, y, fr, P); P.shader = null
        P.style = Paint.Style.STROKE; P.strokeWidth = 0.9f
        P.color = Color.BLACK; P.shader = lin(0f, y - fr, 0f, y + fr, intArrayOf(Color.argb(150, 0, 0, 0), Color.argb(40, 0, 0, 0), Color.argb(70, 255, 255, 255)), floatArrayOf(0f, 0.55f, 1f))
        c.drawCircle(x, y, fr, P); P.shader = null; P.style = Paint.Style.FILL
        // indice rosso in alto (allineamento della spina, come sui connettori a innesto)
        P.color = Color.rgb(170, 40, 34); c.drawCircle(x, y - fr + 3.6f, 1.7f, P)
        P.color = Color.argb(110, 255, 255, 255); c.drawCircle(x - 0.5f, y - fr + 3.1f, 0.6f, P)
        engrave(c, text, x, y + 3.2f, minOf(8.5f, fr * 0.50f))
    }

    /** spina rettangolare: ECG multipolare (zigrinatura sui fianchi, etichetta colorata) o placche (grossa, con levetta) */
    private fun rectHead(c: Canvas, l: Float, t: Float, w: Float, h: Float, base: Int, probe: Int, text: String?, pads: Boolean) {
        val r = if (pads) 8f else 7f
        val rr = RectF(l, t, l + w, t + h)
        P.reset(); P.isAntiAlias = true
        P.color = Color.BLACK; P.shader = lin(0f, t, 0f, t + h, intArrayOf(shade(base, 0.26), shade(base, 0.06), shade(base, -0.06), shade(base, -0.42)), floatArrayOf(0f, 0.30f, 0.70f, 1f))
        c.drawRoundRect(rr, r, r, P); P.shader = null
        // riflesso morbido in alto
        P.color = Color.BLACK; P.shader = lin(0f, t, 0f, t + h * 0.45f, intArrayOf(Color.argb(55, 255, 255, 255), Color.argb(0, 255, 255, 255)), null)
        c.drawRoundRect(RectF(l + 2f, t + 1.2f, l + w - 2f, t + h * 0.45f), r - 1.5f, r - 1.5f, P); P.shader = null
        // bordo: filo chiaro sopra, scuro sotto
        P.style = Paint.Style.STROKE; P.strokeWidth = 1f
        P.color = Color.BLACK; P.shader = lin(0f, t, 0f, t + h, intArrayOf(Color.argb(80, 255, 255, 255), Color.argb(0, 0, 0, 0), Color.argb(160, 0, 0, 0)), floatArrayOf(0f, 0.5f, 1f))
        c.drawRoundRect(RectF(l + 0.5f, t + 0.5f, l + w - 0.5f, t + h - 0.5f), r, r, P); P.shader = null; P.style = Paint.Style.FILL
        // zigrinatura dove si impugna: righe orizzontali sui due fianchi
        val gw = if (pads) 9f else 7f
        val n = if (pads) 9 else 7
        for (side in 0..1) {
            val gx = if (side == 0) l + 3f else l + w - 3f - gw
            for (i in 0 until n) {
                val gy = t + h * 0.18f + (h * 0.64f) * i / (n - 1)
                P.color = Color.argb(140, 0, 0, 0); c.drawRect(gx, gy - 0.6f, gx + gw, gy + 0.5f, P)
                P.color = Color.argb(38, 255, 255, 255); c.drawRect(gx, gy + 0.5f, gx + gw, gy + 1.2f, P)
            }
        }
        // linea di giunzione verticale tra zigrinatura e faccia
        val fl = l + gw + 5f; val fr = l + w - gw - 5f
        P.color = Color.argb(110, 0, 0, 0); c.drawRect(fl, t + 3f, fl + 0.7f, t + h - 3f, P); c.drawRect(fr - 0.7f, t + 3f, fr, t + h - 3f, P)
        P.color = Color.argb(30, 255, 255, 255); c.drawRect(fl + 0.7f, t + 3f, fl + 1.4f, t + h - 3f, P)
        if (pads) {
            // levetta di sgancio in alto (rialzata) e simbolo del fulmine inciso; etichetta arancio smorzato in basso
            val lw = (fr - fl) * 0.62f; val lx = (fl + fr) / 2 - lw / 2
            val lever = RectF(lx, t + 3.5f, lx + lw, t + 3.5f + h * 0.22f)
            P.color = Color.BLACK; P.shader = lin(0f, lever.top, 0f, lever.bottom, intArrayOf(shade(base, 0.32), shade(base, -0.20)), null)
            c.drawRoundRect(lever, 2.5f, 2.5f, P); P.shader = null
            P.color = Color.argb(150, 0, 0, 0); c.drawRect(lever.left + 1f, lever.bottom, lever.right - 1f, lever.bottom + 1f, P)
            for (i in 0 until 4) { val gx = lever.left + lw * (0.2f + 0.2f * i); P.color = Color.argb(90, 0, 0, 0); c.drawRect(gx, lever.top + 2f, gx + 0.8f, lever.bottom - 2f, P) }
            val cx0 = (fl + fr) / 2; val cy0 = t + h * 0.54f; val s = h * 0.15f
            val bolt = Path().apply {
                moveTo(cx0 + s * 0.25f, cy0 - s); lineTo(cx0 - s * 0.55f, cy0 + s * 0.12f); lineTo(cx0 - s * 0.02f, cy0 + s * 0.12f)
                lineTo(cx0 - s * 0.25f, cy0 + s); lineTo(cx0 + s * 0.55f, cy0 - s * 0.12f); lineTo(cx0 + s * 0.02f, cy0 - s * 0.12f); close()
            }
            P.color = Color.argb(55, 255, 255, 255); c.save(); c.translate(0f, 0.7f); c.drawPath(bolt, P); c.restore()
            P.color = Color.argb(170, 0, 0, 0); c.drawPath(bolt, P)
            val tag = RectF(fl + 3f, t + h - 3.5f - h * 0.13f, fr - 3f, t + h - 3.5f)
            P.color = Color.BLACK; P.shader = lin(0f, tag.top, 0f, tag.bottom, intArrayOf(shade(probe, 0.10), shade(probe, -0.20)), null)
            c.drawRoundRect(tag, 1.5f, 1.5f, P); P.shader = null
        } else {
            // faccia: etichetta colorata della sonda con la scritta incisa
            val tag = RectF(fl + 3f, t + h * 0.30f, fr - 3f, t + h * 0.70f)
            P.color = Color.BLACK; P.shader = lin(0f, tag.top, 0f, tag.bottom, intArrayOf(shade(probe, 0.10), shade(probe, -0.22)), null)
            c.drawRoundRect(tag, 2f, 2f, P); P.shader = null
            P.style = Paint.Style.STROKE; P.strokeWidth = 0.7f; P.color = Color.argb(120, 0, 0, 0)
            c.drawRoundRect(tag, 2f, 2f, P); P.style = Paint.Style.FILL
            if (text != null) engrave(c, text, (tag.left + tag.right) / 2, tag.centerY() + 3f, minOf(9f, tag.height() * 0.62f))
        }
    }

    /** raccordo NIBP: ghiera metallica zigrinata con il tubo che entra al centro */
    private fun nibpHead(c: Canvas, x: Float, y: Float, r: Float) {
        P.reset(); P.isAntiAlias = true
        P.color = Color.BLACK; P.shader = RadialGradient(x - r * 0.35f, y - r * 0.45f, r * 1.6f, intArrayOf(gray(0.92), gray(0.66), gray(0.36)), floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP)
        c.drawCircle(x, y, r, P); P.shader = null
        val n = 28; val r0 = r - 3.5f; val r1 = r - 0.6f
        P.strokeWidth = 1f
        for (i in 0 until n) {
            val a = i * 2 * Math.PI / n; val ca = Math.cos(a).toFloat(); val sa = Math.sin(a).toFloat()
            P.color = Color.argb(90, 0, 0, 0); c.drawLine(x + ca * r0, y + sa * r0, x + ca * r1, y + sa * r1, P)
        }
        P.style = Paint.Style.STROKE; P.strokeWidth = 1f; P.color = Color.argb(140, 0, 0, 0); c.drawCircle(x, y, r - 0.5f, P); P.style = Paint.Style.FILL
        // manicotto del tubo (plastica grigia) al centro
        val tr = r * 0.50f
        P.color = Color.BLACK; P.shader = RadialGradient(x - tr * 0.3f, y - tr * 0.4f, tr * 1.5f, intArrayOf(shade(cableTone.argb, 0.35), shade(cableTone.argb, 0.05), shade(cableTone.argb, -0.45)), null, Shader.TileMode.CLAMP)
        c.drawCircle(x, y, tr, P); P.shader = null
        P.color = Color.argb(160, 0, 0, 0); c.drawCircle(x, y, tr * 0.38f, P)
        P.color = Color.argb(60, 255, 255, 255); c.drawCircle(x, y + tr * 0.10f, tr * 0.38f, Paint(P).apply { style = Paint.Style.STROKE; strokeWidth = 0.6f })
    }

    /** linea CO2: ghiera luer in plastica bianca con alette, tubicino trasparente */
    private fun luerHead(c: Canvas, x: Float, y: Float, r: Float) {
        P.reset(); P.isAntiAlias = true
        // alette della ghiera (due orecchie)
        val ear = Path().apply { addRoundRect(x - r * 1.18f, y - r * 0.32f, x + r * 1.18f, y + r * 0.32f, r * 0.3f, r * 0.3f, Path.Direction.CW) }
        P.color = Color.BLACK; P.shader = lin(0f, y - r * 0.32f, 0f, y + r * 0.32f, intArrayOf(gray(0.97), gray(0.86), gray(0.62)), null)
        c.drawPath(ear, P); P.shader = null
        P.color = Color.BLACK; P.shader = RadialGradient(x - r * 0.3f, y - r * 0.45f, r * 1.6f, intArrayOf(gray(1.0), gray(0.88), gray(0.60)), floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP)
        c.drawCircle(x, y, r, P); P.shader = null
        P.style = Paint.Style.STROKE; P.strokeWidth = 0.9f; P.color = Color.argb(120, 0, 0, 0); c.drawCircle(x, y, r - 0.5f, P)
        P.color = Color.argb(70, 0, 0, 0); c.drawCircle(x, y, r * 0.62f, P); P.style = Paint.Style.FILL
        // anello giallo della linea (codice CO2) e foro del tubicino
        P.color = Color.rgb(196, 168, 52); c.drawCircle(x, y, r * 0.40f, P)
        P.color = Color.argb(170, 40, 40, 40); c.drawCircle(x, y, r * 0.18f, P)
    }

    // ---------- PRESA LIBERA (femmina) ----------
    // ANDROID (connettori 22/11): stessa ghiera colorata e stessa misura della presa di prima (diametro d), ma da parte vera:
    // sede incassata nella scocca (filo d'ombra sopra, filo di luce sotto), ghiera nel colore della sonda con volume,
    // fondo in plastica scura concavo e, dentro, la porta tipica della sonda. La spina inserita copre tutto tranne la ghiera.
    private fun renderSocket(c: Canvas, w: Float, h: Float) {
        val x = cx.toFloat(); val y = h / 2; val R = d.toFloat() / 2
        val acc = accent.argb
        val kind = when (sensor) { "ecg" -> "ecg"; "pads" -> "pads"; "nibp" -> "nibp"; "etco2" -> "co2"; else -> "circ" }
        // sede incassata nella scocca: anello appena più largo, ombra interna in alto e filo di luce in basso
        P.reset(); P.isAntiAlias = true; P.style = Paint.Style.STROKE; P.strokeWidth = 2.2f; P.color = Color.BLACK
        P.shader = lin(0f, y - R - 1.5f, 0f, y + R + 1.5f, intArrayOf(Color.argb(if (lightBody) 120 else 200, 0, 0, 0), Color.argb(40, 0, 0, 0),
            Color.argb(if (lightBody) 200 else 55, 255, 255, 255)), floatArrayOf(0f, 0.55f, 1f))
        c.drawCircle(x, y, R + 0.6f, P); P.shader = null; P.style = Paint.Style.FILL
        // ghiera colorata (codice colore della sonda), con volume: luce in alto, ombra in basso
        P.color = Color.BLACK
        P.shader = lin(0f, y - R, 0f, y + R, intArrayOf(shade(acc, 0.22), acc, shade(acc, -0.30), shade(acc, -0.48)), floatArrayOf(0f, 0.35f, 0.8f, 1f))
        c.drawCircle(x, y, R - 0.2f, P); P.shader = null
        // fondo della presa: plastica scura, concavo (scuro in alto, un filo più chiaro in basso)
        val fr = R - 3.4f
        val face = faceTone.argb
        P.color = Color.BLACK
        P.shader = lin(0f, y - fr, 0f, y + fr, intArrayOf(shade(face, -0.55), shade(face, -0.10), shade(face, 0.14)), floatArrayOf(0f, 0.45f, 1f))
        c.drawCircle(x, y, fr, P); P.shader = null
        P.style = Paint.Style.STROKE; P.strokeWidth = 1f
        P.shader = lin(0f, y - fr, 0f, y + fr, intArrayOf(Color.argb(170, 0, 0, 0), Color.argb(0, 0, 0, 0), Color.argb(60, 255, 255, 255)), floatArrayOf(0f, 0.5f, 1f))
        c.drawCircle(x, y, fr - 0.4f, P); P.shader = null; P.style = Paint.Style.FILL
        when (kind) {
            "circ" -> portCirc(c, x, y, fr)
            "ecg" -> portRect(c, x, y, fr * 1.30f, fr * 0.78f, 2, 6, false)
            "pads" -> portRect(c, x, y, fr * 1.42f, fr * 0.92f, 1, 3, true)
            "nibp" -> portNibp(c, x, y, fr)
            else -> portLuer(c, x, y, fr)
        }
    }

    private fun hole(c: Canvas, x: Float, y: Float, r: Float) {
        // foro con la boccola dorata: filo d'oro attorno, fondo nero
        P.reset(); P.isAntiAlias = true; P.color = Color.BLACK
        P.shader = lin(0f, y - r, 0f, y + r, intArrayOf(Color.rgb(120, 98, 44), Color.rgb(222, 190, 104), Color.rgb(150, 120, 52)), null)
        c.drawCircle(x, y, r, P); P.shader = null
        P.color = Color.rgb(8, 8, 8); c.drawCircle(x, y + 0.15f, r * 0.55f, P)
    }

    /** circolare multipin: boccola metallica, isolante scuro, fori a corona e tacca di guida in alto */
    private fun portCirc(c: Canvas, x: Float, y: Float, fr: Float) {
        val mr = fr * 0.80f
        P.reset(); P.isAntiAlias = true; P.color = Color.BLACK
        P.shader = lin(0f, y - mr, 0f, y + mr, intArrayOf(gray(0.30), gray(0.78), gray(0.52), gray(0.86)), floatArrayOf(0f, 0.25f, 0.7f, 1f))
        c.drawCircle(x, y, mr, P); P.shader = null
        val ir = mr - 2.4f
        P.color = Color.BLACK
        P.shader = lin(0f, y - ir, 0f, y + ir, intArrayOf(gray(0.02), gray(0.10)), null)
        c.drawCircle(x, y, ir, P); P.shader = null
        // tacca di guida in alto
        P.color = gray(0.48); c.drawRect(x - 1.6f, y - mr - 0.2f, x + 1.6f, y - ir + 2.2f, P)
        P.color = gray(0.04); c.drawRect(x - 1.0f, y - ir - 0.4f, x + 1.0f, y - ir + 2.0f, P)
        val n = if (sensor == "temp") 4 else 6
        val pr = ir * 0.56f; val hr = minOf(1.9f, ir * 0.16f)
        for (i in 0 until n) { val a = -Math.PI / 2 + Math.PI / n + i * 2 * Math.PI / n
            hole(c, x + (pr * Math.cos(a)).toFloat(), y + (pr * Math.sin(a)).toFloat(), hr) }
        if (sensor != "temp") hole(c, x, y, hr)
    }

    /** presa rettangolare: ECG multipolare (2 file di fori) o placche (3 contatti grossi tra due guide) */
    private fun portRect(c: Canvas, x: Float, y: Float, w: Float, h: Float, rows: Int, cols: Int, pads: Boolean) {
        val l = x - w / 2; val t = y - h / 2
        // cornice metallica/plastica rialzata
        P.reset(); P.isAntiAlias = true; P.color = Color.BLACK
        P.shader = lin(0f, t - 2f, 0f, t + h + 2f, intArrayOf(gray(0.20), gray(if (pads) 0.42 else 0.62), gray(0.36), gray(0.70)), floatArrayOf(0f, 0.2f, 0.75f, 1f))
        c.drawRoundRect(RectF(l - 2f, t - 2f, l + w + 2f, t + h + 2f), 5f, 5f, P); P.shader = null
        P.color = Color.BLACK
        P.shader = lin(0f, t, 0f, t + h, intArrayOf(gray(0.01), gray(0.09)), null)
        c.drawRoundRect(RectF(l, t, l + w, t + h), 3.5f, 3.5f, P); P.shader = null
        // smussi di guida (forma a D: angoli in basso tagliati)
        P.color = gray(0.30)
        val ch = h * 0.28f
        c.drawPath(Path().apply { moveTo(l, t + h - ch); lineTo(l + ch, t + h); lineTo(l, t + h); close() }, P)
        c.drawPath(Path().apply { moveTo(l + w, t + h - ch); lineTo(l + w - ch, t + h); lineTo(l + w, t + h); close() }, P)
        if (pads) {
            // tre contatti grossi e una guida centrale
            for (i in 0 until cols) hole(c, l + w * (0.22f + 0.28f * i), y + 0.5f, minOf(3.6f, h * 0.20f))
            P.color = gray(0.34); c.drawRoundRect(RectF(l + w * 0.30f, t + 1.6f, l + w * 0.70f, t + 4f), 1f, 1f, P)
        } else {
            // due file sfalsate (6 + 5 fori), come le prese ECG multipolari
            val hr = minOf(1.6f, w / (cols * 4.2f))
            val pitch = w * 0.72f / (cols - 1)
            for (r in 0 until rows) {
                val n = if (r == 0) cols else cols - 1
                val x0 = l + w * 0.14f + (if (r == 0) 0f else pitch / 2)
                val py = t + h * (if (rows == 1) 0.5f else (0.34f + 0.34f * r))
                for (i in 0 until n) hole(c, x0 + pitch * i, py, hr)
            }
        }
    }

    /** NIBP: raccordo metallico a innesto (dado esagonale e ugello con il foro dell'aria) */
    private fun portNibp(c: Canvas, x: Float, y: Float, fr: Float) {
        val hr = fr * 0.74f
        val hex = Path()
        for (i in 0 until 6) { val a = Math.PI / 6 + i * Math.PI / 3; val px = x + (hr * Math.cos(a)).toFloat(); val py = y + (hr * Math.sin(a)).toFloat()
            if (i == 0) hex.moveTo(px, py) else hex.lineTo(px, py) }
        hex.close()
        P.reset(); P.isAntiAlias = true; P.color = Color.BLACK
        P.shader = lin(0f, y - hr, 0f, y + hr, intArrayOf(gray(0.86), gray(0.58), gray(0.38)), null)
        c.drawPath(hex, P); P.shader = null
        P.style = Paint.Style.STROKE; P.strokeWidth = 0.8f; P.color = Color.argb(140, 0, 0, 0); c.drawPath(hex, P); P.style = Paint.Style.FILL
        val nr = hr * 0.62f
        P.color = Color.BLACK
        P.shader = RadialGradient(x - nr * 0.3f, y - nr * 0.4f, nr * 1.5f, intArrayOf(gray(0.97), gray(0.70), gray(0.40)), floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP)
        c.drawCircle(x, y, nr, P); P.shader = null
        P.style = Paint.Style.STROKE; P.strokeWidth = 0.8f; P.color = Color.argb(90, 0, 0, 0); c.drawCircle(x, y, nr * 0.70f, P); P.style = Paint.Style.FILL
        P.color = gray(0.06); c.drawCircle(x, y, nr * 0.34f, P)
    }

    /** CO2: porta luer femmina (filetto e foro della linea di campionamento) */
    private fun portLuer(c: Canvas, x: Float, y: Float, fr: Float) {
        val r = fr * 0.62f
        P.reset(); P.isAntiAlias = true; P.color = Color.BLACK
        P.shader = RadialGradient(x - r * 0.3f, y - r * 0.4f, r * 1.6f, intArrayOf(gray(0.92), gray(0.74), gray(0.44)), floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP)
        c.drawCircle(x, y, r, P); P.shader = null
        P.style = Paint.Style.STROKE; P.strokeWidth = 0.8f
        for (i in 1..2) { P.color = Color.argb(80, 0, 0, 0); c.drawCircle(x, y, r * (1f - 0.18f * i), P) }
        P.style = Paint.Style.FILL
        P.color = gray(0.05); c.drawCircle(x, y, r * 0.30f, P)
    }

    /** scritta incisa nella plastica: scura, con il filo di luce sotto */
    private fun engrave(c: Canvas, text: String, x: Float, baseline: Float, size: Float) {
        if (text.isEmpty()) return
        val tp = Paint(Paint.ANTI_ALIAS_FLAG); tp.typeface = Typeface.create("sans-serif-condensed", Typeface.BOLD)
        tp.textSize = size; tp.textAlign = Paint.Align.CENTER
        tp.color = Color.argb(60, 255, 255, 255); c.drawText(text, x, baseline + 0.7f, tp)
        tp.color = Color.argb(185, 0, 0, 0); c.drawText(text, x, baseline, tp)
    }

    companion object {
        private val cache = object : LruCache<String, Bitmap>(6 * 1024 * 1024) { override fun sizeOf(key: String, value: Bitmap) = value.byteCount }
    }
}
