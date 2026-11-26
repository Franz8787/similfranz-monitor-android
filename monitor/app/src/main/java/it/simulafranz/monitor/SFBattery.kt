// ANDROID: nessun file corrispondente sull'iPad. Batteria del tablet uguale in tutti i layout (richiesta utente 21/11/2026):
// icona con il riempimento proporzionale alla carica e colore secondo il livello (verde sopra il 40 %, giallo fino al 20 %,
// rosso sotto il 20 %), fulmine quando il tablet è in carica. La percentuale la scrive ogni layout nel suo stile.
package it.simulafranz.monitor

import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import it.simulafranz.uk.*

/** Stato della batteria del tablet: livello 0…1 (-1 = sconosciuto), in carica (o carica completa sull'alimentatore). */
class SFBatteryState(val level: Float, val charging: Boolean)

private var sfBattCache = SFBatteryState(-1f, false)
private var sfBattAt = -10.0

/** Letto dal sistema al più ogni 2 s (la lettura costa poco, ma i layout la chiedono a ogni giro). */
fun SFBatteryRead(): SFBatteryState {
    val now = CACurrentMediaTime()
    if (now - sfBattAt < 2.0) return sfBattCache
    sfBattAt = now
    sfBattCache = try {
        val i: Intent? = Uk.ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val lv = i?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1; val sc = i?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        val st = i?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        SFBatteryState(if (lv >= 0 && sc > 0) lv.toFloat() / sc else -1f,
                       st == BatteryManager.BATTERY_STATUS_CHARGING || st == BatteryManager.BATTERY_STATUS_FULL)
    } catch (e: Exception) { SFBatteryState(-1f, false) }
    return sfBattCache
}

/** Colore del livello: verde > 40 %, giallo 20–40 %, rosso < 20 % (anche in carica: il fulmine dice che si sta ricaricando). */
fun SFBatteryColor(level: Float): UIColor = when {
    level < 0 -> UIColor.colorWithWhite(0.7, 1)
    level < 0.20f -> UIColor.colorWithRed(1.0, 0.25, 0.22, 1)
    level <= 0.40f -> UIColor.colorWithRed(1.0, 0.82, 0.10, 1)
    else -> UIColor.colorWithRed(0.25, 0.90, 0.35, 1)
}

/** Icona della batteria: corpo, polo, riempimento colorato e fulmine in carica. outline = colore del bordo (stile del layout). */
class SFBatteryIcon(frame: CGRect, var outline: UIColor = UIColor.colorWithWhite(0.85, 1)) : UIView(frame) {
    private var level = -1f; private var charging = false
    init { backgroundColor = UIColor.clearColor; userInteractionEnabled = false }
    fun update(s: SFBatteryState) {
        if (s.level == level && s.charging == charging) return
        level = s.level; charging = s.charging; setNeedsDisplay()
    }
    override fun drawRect(ctx: CGContext, rect: CGRect) {
        val nub = maxOf(2.0, rect.width * 0.08)
        val body = CGRectMake(1.0, 1.0, rect.width - nub - 2.0, rect.height - 2.0)
        val lw = maxOf(1.2, rect.height * 0.10)
        ctx.setStrokeColor(outline); ctx.setLineWidth(lw); ctx.strokeRect(body)
        ctx.setFillColor(outline); ctx.fillRect(CGRectMake(body.maxX + 0.5, rect.height * 0.32, nub - 0.5, rect.height * 0.36))
        val pad = lw + 1.0
        val inner = CGRectMake(body.x + pad, body.y + pad, body.width - 2 * pad, body.height - 2 * pad)
        val f = if (level < 0) 1.0 else maxOf(0.06, level.toDouble())
        ctx.setFillColor(SFBatteryColor(level)); ctx.fillRect(CGRectMake(inner.x, inner.y, inner.width * f, inner.height))
        if (charging) {   // fulmine al centro, con bordo scuro per leggerlo su qualsiasi riempimento
            val b = SFBoltPath(CGRectMake(body.x, body.y - 1, body.width, body.height + 2))
            ctx.setStrokeColor(UIColor.colorWithWhite(0.0, 0.85)); ctx.setLineWidth(1.6); ctx.strokePath(b)
            ctx.setFillColor(UIColor.colorWithRed(1.0, 0.95, 0.35, 1)); ctx.fillPath(b)
        }
    }
}

/** ANDROID (guasti 22/11): batteria SIMULATA dei malfunzionamenti (battery_low gialla, battery_replace rossa quasi vuota,
 *  battery_dead vuota); null = nessun guasto della batteria: si mostra quella vera del tablet. */
fun SFBatterySimFor(s: it.simulafranz.core.SLSnapshot): SFBatteryState? = when {
    s.fault("battery_dead") -> SFBatteryState(0f, false)
    s.fault("battery_replace") -> SFBatteryState(0.07f, false)
    s.fault("battery_low") -> SFBatteryState(0.28f, false)
    else -> null
}

/** ANDROID (batteria in tutti i layout 22/11): spina di rete, mostrata accanto alla batteria quando il tablet è in carica. */
class SFMainsPlug(frame: CGRect, var color: UIColor) : UIView(frame) {
    init { backgroundColor = UIColor.clearColor; userInteractionEnabled = false }
    override fun drawRect(ctx: CGContext, rect: CGRect) {
        val w = rect.width; val h = rect.height
        ctx.setFillColor(color)
        ctx.fillRect(CGRectMake(w * 0.24, 0.0, w * 0.14, h * 0.34)); ctx.fillRect(CGRectMake(w * 0.62, 0.0, w * 0.14, h * 0.34))   // spinotti
        ctx.fillRect(CGRectMake(w * 0.08, h * 0.30, w * 0.84, h * 0.30))                                                           // corpo
        ctx.fillRect(CGRectMake(w * 0.24, h * 0.60, w * 0.52, h * 0.14))
        ctx.fillRect(CGRectMake(w * 0.42, h * 0.74, w * 0.16, h * 0.26))                                                           // cavo
    }
}

/** ANDROID (batteria in tutti i layout 22/11): indicatore completo e uguale per i layout nuovi: [spina di rete se in carica]
 *  icona comune (riempimento verde/giallo/rosso, fulmine in carica) e percentuale nel colore e nel carattere del layout.
 *  Mostra la batteria VERA del tablet oppure quella simulata dai malfunzionamenti (SFBatterySimFor).
 *  stacked = percentuale sotto l'icona (spazi stretti); align = lato a cui si appoggia (NSTextAlignmentLeft/Right/Center). */
class SFBatteryBadge(frame: CGRect, private val textColor: UIColor, font: UIFont, iconH: Double = 14.0, mains: Boolean = true,
                     stacked: Boolean = false, align: Int = NSTextAlignmentRight, outline: UIColor = textColor) : UIView(frame) {
    val icon: SFBatteryIcon; val pct: UILabel; val plug: SFMainsPlug?
    private var shown = ""
    init {
        backgroundColor = UIColor.clearColor; userInteractionEnabled = false
        val w = frame.size.width; val h = frame.size.height
        val iw = Math.rint(iconH * 2.15); val pw = if (mains) Math.rint(iconH * 0.80) else 0.0; val g = 4.0
        val ix: Double; val iy: Double; val lf: CGRect
        if (stacked) {
            ix = Math.rint((w - iw + (if (mains) pw + 2 else 0.0)) / 2); iy = 0.0
            lf = CGRectMake(0.0, iconH + 1, w, h - iconH - 1)
        } else {
            val lw = Math.ceil(font.pointSize * 2.5); val tot = (if (mains) pw + 3 else 0.0) + iw + g + lw
            val x0 = when (align) { NSTextAlignmentLeft -> 0.0; NSTextAlignmentCenter -> Math.rint((w - tot) / 2); else -> w - tot }
            ix = x0 + (if (mains) pw + 3 else 0.0); iy = Math.rint((h - iconH) / 2)
            lf = CGRectMake(ix + iw + g, 0.0, lw, h)
        }
        icon = SFBatteryIcon(CGRectMake(ix, iy, iw, iconH), outline); addSubview(icon)
        pct = UILabel(lf); pct.backgroundColor = UIColor.clearColor; pct.font = font; pct.textColor = textColor
        pct.textAlignment = if (stacked) NSTextAlignmentCenter else if (align == NSTextAlignmentRight) NSTextAlignmentRight else NSTextAlignmentLeft
        pct.adjustsFontSizeToFitWidth = true; pct.minimumScaleFactor = 0.6; addSubview(pct)
        plug = if (mains) SFMainsPlug(CGRectMake(ix - pw - 3, iy + 1, pw, iconH - 2), outline) else null
        plug?.let { it.hidden = true; addSubview(it) }
    }
    /** da chiamare nel giro lento del layout (la lettura vera è al più ogni 2 s) */
    fun update(s: it.simulafranz.core.SLSnapshot?) {
        val sim = s?.let { SFBatterySimFor(it) }; val b = sim ?: SFBatteryRead()
        icon.update(b)
        val t = if (b.level < 0) "--%" else String.format(java.util.Locale.US, "%d%%", Math.round(b.level * 100))
        val key = t + (if (sim != null) "s" else "") + (if (b.charging) "c" else "")
        if (key == shown) return
        shown = key
        pct.text = t
        pct.textColor = if (sim != null || (b.level >= 0 && b.level < 0.20f)) SFBatteryColor(b.level) else textColor
        plug?.let { if (it.hidden != !b.charging) it.hidden = !b.charging }
    }
}
