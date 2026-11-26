// Portato da src/CtlUI.m (controller iPad)
// similFranz Controller — elementi comuni dell'interfaccia: colori, carte, pulsanti, riga di regolazione di un parametro.
// ANDROID, differenze dall'originale (segnate con "ANDROID:" nel punto in cui sono):
//  - in `uk` UIButton.setTitle non si può ridefinire e layoutSubviews non riparte da solo quando cambiano titolo o carattere:
//    CtlButton ricontrolla prima di disegnarsi se titolo / carattere / misure sono cambiati e in quel caso si ridispone;
//  - in `uk` accessibilityIdentifier non si può ridefinire: CtlParamRow copia gli identificativi su − e + quando entra
//    a schermo, quando si disegna e a ogni valore ricevuto.
package it.simulafranz.controller

import it.simulafranz.core.SFLog
import it.simulafranz.uk.*
import java.util.Locale
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin

// ANDROID (BugFixController 3): tela del controller larga quanto lo schermo 16:10 (niente fasce nere ai lati).
// Altezza fissa 768 punti come l'iPad (stessa grandezza di testi e tasti); larghezza = schermo / scala:
// 1228 punti sul Tab Active 2 (1280×800). La calcola MainActivity prima di creare le viste; tutte le schermate
// del controller si dispongono a partire da qui (nessun 1024 cablato).
var CTL_W = 1228.0
const val CTL_H = 768.0

fun CtlRGB(r: Double, g: Double, b: Double): UIColor = UIColor.colorWithRed(r, g, b, 1)
val CTL_BG = CtlRGB(0.035, 0.045, 0.060)     // pagina
val CTL_CARD = CtlRGB(0.085, 0.105, 0.135)     // carte
val CTL_CARD2 = CtlRGB(0.130, 0.155, 0.195)     // elementi in rilievo dentro le carte
val CTL_LINE = UIColor.colorWithWhite(1, 0.09)
val CTL_INK = CtlRGB(0.94, 0.96, 0.98)
val CTL_INK2 = CtlRGB(0.66, 0.71, 0.77)
// ANDROID (affordance 22/11, CTL-20 / W-16): INK3 più chiaro (4,7:1 su CARD2, 5,4:1 su CARD; prima 3,7:1): contrasto AA anche per
// le didascalie piccole e le etichette di stato
val CTL_INK3 = CtlRGB(0.52, 0.57, 0.63)
val CTL_TINT = CtlRGB(0.04, 0.52, 1.00)        // azzurro di sistema: tutto ciò che si tocca
val CTL_GREEN = CtlRGB(0.19, 0.82, 0.35)
val CTL_RED = CtlRGB(1.00, 0.27, 0.23)
val CTL_ORANGE = CtlRGB(1.00, 0.62, 0.04)
val CTL_YELLOW = CtlRGB(1.00, 0.84, 0.04)
// ANDROID (affordance 22/11, SISTEMA §3): ambra = attenzione dell'istruttore (silenziato, comanda un altro, in pausa);
// l'arancione resta per la terapia elettrica e le cose «armate» (secondo tocco). Testo scuro sui fondi chiari pieni.
val CTL_AMBER = CtlRGB(1.00, 0.69, 0.13)
val CTL_ON_LIGHT = CtlRGB(0.04, 0.07, 0.05)
// colori dei parametri = convenzioni dei monitor
// ANDROID (affordance 22/11, cura visiva): IDENTICI al monitor dei discenti (MonitorViewController SL_GREEN, SL_RED, SL_CYAN,
// SL_YELLOW, SL_TEMP, SL_NIBP) e alla web regia: lo stesso parametro ha lo stesso colore nelle tre schermate
val CTL_HR = CtlRGB(0.20, 1.00, 0.35)
val CTL_BP = CtlRGB(1.00, 0.23, 0.19)
val CTL_NIBP = CtlRGB(0.94, 0.33, 0.48)
val CTL_SPO2 = CtlRGB(0.20, 0.90, 1.00)
val CTL_CO2 = CtlRGB(1.00, 0.88, 0.20)
val CTL_TEMP = CtlRGB(0.80, 0.62, 1.00)

/** ANDROID (affordance 22/11): luminanza relativa (WCAG) di un colore pieno: sopra 0,4 il testo va scuro */
fun CtlLuma(c: UIColor): Double {
    fun ch(v: Double) = if (v <= 0.03928) v / 12.92 else Math.pow((v + 0.055) / 1.055, 2.4)
    return 0.2126 * ch(c.r) + 0.7152 * ch(c.g) + 0.0722 * ch(c.b)
}

/** ANDROID (affordance 22/11, CTL-15): risposta tattile. 20 ms al tocco preso, 2×40 ms a comando eseguito (comandi critici),
 *  200 ms se il comando non arriva. Attiva di serie; si spegne in «Allarmi, audio, movimenti» (pref sf_haptic). Se il tablet
 *  non ha il motore di vibrazione non fa niente. */
object CtlHaptic {
    var enabled = true
    private val vib: android.os.Vibrator? by lazy { try { Uk.ctx.getSystemService(android.content.Context.VIBRATOR_SERVICE) as? android.os.Vibrator } catch (e: Exception) { null } }
    val available: Boolean get() = try { vib?.hasVibrator() == true } catch (e: Exception) { false }
    fun tap() = buzz(longArrayOf(0, 20))
    fun ok() = buzz(longArrayOf(0, 40, 70, 40))
    fun fail() = buzz(longArrayOf(0, 200))
    fun shock() = buzz(longArrayOf(0, 70, 60, 160))
    fun pace() = buzz(longArrayOf(0, 45))                  // impulso di pacing erogato sul monitor: lieve e breve        // ANDROID (guscio 25/11): scarica erogata sul monitor
    private fun buzz(p: LongArray) {
        if (!enabled) return
        try {
            val v = vib ?: return
            if (!v.hasVibrator()) return
            if (android.os.Build.VERSION.SDK_INT >= 26) v.vibrate(android.os.VibrationEffect.createWaveform(p, -1))
            else @Suppress("DEPRECATION") v.vibrate(p, -1)
        } catch (e: Exception) {}
    }
}

/** ANDROID (affordance 22/11, CTL-04): riquadro tratteggiato (stato «in attesa» come FORMA, non come colore) */
class CtlDashBox(frame: CGRect, color: UIColor) : UIView(frame) {
    var color: UIColor = color; set(v) { if (field != v) { field = v; setNeedsDisplay() } }
    var radius = 10.0
    init { backgroundColor = UIColor.clearColor; userInteractionEnabled = false }
    override fun drawRect(ctx: CGContext, rect: CGRect) {
        ctx.setStrokeColor(color); ctx.setLineWidth(2.0); ctx.setLineDash(0, doubleArrayOf(7.0, 5.0))
        ctx.strokePath(UIBezierPath.bezierPathWithRoundedRect(CGRectInset(bounds, 1.0, 1.0), radius))
        ctx.setLineDash(0, null)
    }
}

/** ANDROID (affordance 22/11): piccola pillola di stato (badge) — testo MAIUSCOLO 10 pt, fondo pieno, testo scuro o chiaro */
fun CtlPill(text: String, fill: UIColor): UILabel {
    val l = CtlLabel(CGRectZero, text, 10.0, true, if (CtlLuma(fill) > 0.4) CTL_ON_LIGHT else UIColor.whiteColor)
    l.backgroundColor = fill; l.textAlignment = NSTextAlignmentCenter; l.layer.cornerRadius = 4.0; l.clipsToBounds = true
    l.userInteractionEnabled = false
    l.frame = CGRectMake(0, 0, ceil(UkText.size(text, l.font).width) + 10, 16)
    return l
}


/** ANDROID: versione mostrata a schermo = numero dell'app + sigla dell'edizione (come SFVersionShown del monitor).
 *  Ciò che va al monitor o nel log resta il numero puro. */
const val CTL_EDITION_TAG = ""
fun CtlVersionShown(): String {
    val v = try { Uk.ctx.packageManager.getPackageInfo(Uk.ctx.packageName, 0).versionName ?: "" } catch (e: Exception) { "" }
    return if (CTL_EDITION_TAG.isEmpty()) v else if (v.isEmpty()) CTL_EDITION_TAG else "$v $CTL_EDITION_TAG"
}

var CtlFitLog = false      // diagnosi: scrive nel log come è stato adattato il testo di ogni pulsante

fun CtlCard(frame: CGRect): UIView {
    val v = UIView(frame)
    v.backgroundColor = CTL_CARD; v.layer.cornerRadius = 12.0
    return v
}
fun CtlLabel(frame: CGRect, text: String?, size: Double, bold: Boolean, color: UIColor): UILabel {
    val l = UILabel(frame)
    l.text = text; l.textColor = color; l.backgroundColor = UIColor.clearColor
    l.font = if (bold) UIFont.boldSystemFontOfSize(size) else UIFont.systemFontOfSize(size)
    return l
}
// cifre a larghezza fissa (i numeri non "ballano")
fun CtlDigits(size: Double, bold: Boolean): UIFont {
    return UIFont.monospacedDigitSystemFontOfSize(size, if (bold) UIFontWeightBold else UIFontWeightRegular)
}

// Elenco che scorre anche partendo con il dito sopra un pulsante (di norma un pulsante "trattiene" il tocco).
open class CtlScrollView(frame: CGRect) : UIScrollView(frame) {
    init { delaysContentTouches = false; canCancelContentTouches = true }
    override fun touchesShouldCancelInContentView(view: UIView): Boolean = view !is UISlider     // se il dito si muove vince lo scorrimento, anche sopra un pulsante (il righello a zone lascia scorrere in verticale)
}

// MARK: - aiuti privati (ANDROID: misure del testo che in `uk` non ci sono)

/** [UIFont isEqual:] */
private fun ctlFontEq(a: UIFont?, b: UIFont?): Boolean =
    a != null && b != null && a.pointSize == b.pointSize && a.weight == b.weight && a.mono == b.mono && a.italic == b.italic
/** UIFont.lineHeight */
private fun ctlLineHeight(f: UIFont): Double { val fm = UkText.paint(f).fontMetrics; return (fm.descent - fm.ascent).toDouble() }
/** [NSString boundingRectWithSize:CGSizeMake(w, 1000) options:NSStringDrawingUsesLineFragmentOrigin ...].size */
private fun ctlBounding(t: String, f: UIFont, w: Double): CGSize {
    val l = UkText.layout(t, UkText.paint(f), ceil(w).toInt(), NSTextAlignmentLeft, 0, false)
    var mw = 0f; for (i in 0 until l.lineCount) mw = maxOf(mw, l.getLineWidth(i))
    return CGSize(mw.toDouble(), l.height.toDouble())
}
/** lround del C (metà lontano da zero) */
private fun ctlLround(v: Double): Long = if (v < 0) -Math.round(-v) else Math.round(v)
/** round del C (metà lontano da zero; kotlin.math.round arrotonda al pari) */
private fun ctlRound(v: Double): Double = if (v < 0) -floor(-v + 0.5) else floor(v + 0.5)

// MARK: - Icone

// Icone vettoriali monocromatiche: pause, play, bell, bell.slash, gauge (NIBP), cpr, lungs, tap, monitor, plus, next, stop, trash
// ANDROID: in più ecg, pads, finger, cuff, artline, co2, thermo (sonde, proposta S4), power, bolt (terapia elettrica)
fun CtlIcon(name: String, size: Double, color: UIColor): UIImage {
    val S = size
    UIGraphicsBeginImageContextWithOptions(CGSizeMake(S, S), false, 0)
    val g = UIGraphicsGetCurrentContext()!!
    g.setStrokeColor(color); g.setFillColor(color)
    val u = S / 24.0                       // disegnate su una griglia 24x24
    g.setLineWidth(2 * u); g.setLineCap(kCGLineCapRound); g.setLineJoin(kCGLineJoinRound)
    fun PT(x: Number, y: Number) = CGPointMake(x.toDouble() * u, y.toDouble() * u)
    val p = UIBezierPath.bezierPath(); p.lineWidth = 2 * u; p.lineCapStyle = kCGLineCapRound; p.lineJoinStyle = kCGLineJoinRound
    if (name == "pause") {
        UIBezierPath.bezierPathWithRoundedRect(CGRectMake(6 * u, 5 * u, 4 * u, 14 * u), 1.2 * u).fill()
        UIBezierPath.bezierPathWithRoundedRect(CGRectMake(14 * u, 5 * u, 4 * u, 14 * u), 1.2 * u).fill()
    } else if (name == "play" || name == "next") {
        p.moveToPoint(PT(7, 5)); p.addLineToPoint(PT(19, 12)); p.addLineToPoint(PT(7, 19)); p.closePath(); p.fill(); p.stroke()
    } else if (name == "stop") {
        UIBezierPath.bezierPathWithRoundedRect(CGRectMake(6 * u, 6 * u, 12 * u, 12 * u), 2 * u).fill()
    } else if (name.startsWith("bell")) {
        p.moveToPoint(PT(5, 17)); p.addCurveToPoint(PT(7, 10), PT(7, 15.5), PT(7, 13))
        p.addCurveToPoint(PT(12, 5), PT(7, 7), PT(9.2, 5))
        p.addCurveToPoint(PT(17, 10), PT(14.8, 5), PT(17, 7))
        p.addCurveToPoint(PT(19, 17), PT(17, 13), PT(17, 15.5)); p.closePath(); p.stroke()
        val c = UIBezierPath.bezierPath(); c.lineWidth = 2 * u; c.lineCapStyle = kCGLineCapRound
        c.moveToPoint(PT(10.3, 20)); c.addQuadCurveToPoint(PT(13.7, 20), PT(12, 21.6)); c.stroke()
        if (name.endsWith("slash")) { val l = UIBezierPath.bezierPath(); l.lineWidth = 2 * u; l.lineCapStyle = kCGLineCapRound; l.moveToPoint(PT(4.5, 4.5)); l.addLineToPoint(PT(19.5, 20.5)); l.stroke() }
    } else if (name == "gauge") {            // manometro: misura della pressione
        UIBezierPath.bezierPathWithOvalInRect(CGRectMake(4 * u, 4 * u, 16 * u, 16 * u)).stroke()
        p.moveToPoint(PT(12, 12)); p.addLineToPoint(PT(16, 8.5)); p.stroke()
        UIBezierPath.bezierPathWithOvalInRect(CGRectMake(10.6 * u, 10.6 * u, 2.8 * u, 2.8 * u)).fill()
        for (k in 0 until 5) { val a = Math.PI * (1.0 + k / 4.0); val t = UIBezierPath.bezierPath(); t.lineWidth = 1.4 * u; t.lineCapStyle = kCGLineCapRound
            t.moveToPoint(PT(12 + 6.2 * cos(a), 12 + 6.2 * sin(a))); t.addLineToPoint(PT(12 + 5 * cos(a), 12 + 5 * sin(a))); t.stroke() }
    } else if (name == "cpr") {              // cuore con le compressioni
        p.moveToPoint(PT(12, 20)); p.addCurveToPoint(PT(4, 10.5), PT(8, 17), PT(4, 14))
        p.addCurveToPoint(PT(12, 8.5), PT(4, 6.2), PT(10, 5.2))
        p.addCurveToPoint(PT(20, 10.5), PT(14, 5.2), PT(20, 6.2))
        p.addCurveToPoint(PT(12, 20), PT(20, 14), PT(16, 17)); p.closePath(); p.stroke()
        val e = UIBezierPath.bezierPath(); e.lineWidth = 1.6 * u; e.lineCapStyle = kCGLineCapRound; e.lineJoinStyle = kCGLineJoinRound
        e.moveToPoint(PT(7, 12.5)); e.addLineToPoint(PT(10, 12.5)); e.addLineToPoint(PT(11.2, 10.2)); e.addLineToPoint(PT(12.8, 15)); e.addLineToPoint(PT(14, 12.5)); e.addLineToPoint(PT(17, 12.5)); e.stroke()
    } else if (name == "tap") {              // tocco: punto con due onde
        UIBezierPath.bezierPathWithOvalInRect(CGRectMake(9.5 * u, 9.5 * u, 5 * u, 5 * u)).fill()
        val r1 = UIBezierPath.bezierPathWithOvalInRect(CGRectMake(6 * u, 6 * u, 12 * u, 12 * u)); r1.lineWidth = 1.6 * u; r1.stroke()
        g.setAlpha(0.5)
        val r2 = UIBezierPath.bezierPathWithOvalInRect(CGRectMake(2.6 * u, 2.6 * u, 18.8 * u, 18.8 * u)); r2.lineWidth = 1.4 * u; r2.stroke()
    } else if (name == "monitor") {          // schermo con una traccia
        val r = UIBezierPath.bezierPathWithRoundedRect(CGRectMake(3 * u, 5 * u, 18 * u, 13 * u), 2.4 * u); r.lineWidth = 2 * u; r.stroke()
        val e = UIBezierPath.bezierPath(); e.lineWidth = 1.5 * u; e.lineCapStyle = kCGLineCapRound; e.lineJoinStyle = kCGLineJoinRound
        e.moveToPoint(PT(6, 12)); e.addLineToPoint(PT(9.5, 12)); e.addLineToPoint(PT(11, 8.5)); e.addLineToPoint(PT(13, 15)); e.addLineToPoint(PT(14.3, 12)); e.addLineToPoint(PT(18, 12)); e.stroke()
    } else if (name == "lungs") {            // polmoni: trachea e due lobi
        p.moveToPoint(PT(12, 4)); p.addLineToPoint(PT(12, 11)); p.moveToPoint(PT(12, 10)); p.addLineToPoint(PT(9.5, 12.5)); p.moveToPoint(PT(12, 10)); p.addLineToPoint(PT(14.5, 12.5)); p.stroke()
        val l = UIBezierPath.bezierPath(); l.lineWidth = 1.8 * u; l.lineJoinStyle = kCGLineJoinRound
        l.moveToPoint(PT(9.5, 8)); l.addCurveToPoint(PT(4, 19), PT(6, 9), PT(3.5, 14))
        l.addCurveToPoint(PT(9.5, 18), PT(5, 20.5), PT(8, 20)); l.closePath(); l.stroke()
        val r = UIBezierPath.bezierPath(); r.lineWidth = 1.8 * u; r.lineJoinStyle = kCGLineJoinRound
        r.moveToPoint(PT(14.5, 8)); r.addCurveToPoint(PT(20, 19), PT(18, 9), PT(20.5, 14))
        r.addCurveToPoint(PT(14.5, 18), PT(19, 20.5), PT(16, 20)); r.closePath(); r.stroke()
    } else if (name == "plus") {
        p.moveToPoint(PT(12, 5)); p.addLineToPoint(PT(12, 19)); p.moveToPoint(PT(5, 12)); p.addLineToPoint(PT(19, 12)); p.stroke()
    } else if (name == "ecg") {              // ANDROID (proposta S4): elettrodi ECG — tre bottoni e una traccia
        for (c in listOf(Pair(5.0, 6.0), Pair(19.0, 6.0), Pair(12.0, 19.5))) UIBezierPath.bezierPathWithOvalInRect(CGRectMake((c.first - 2.3) * u, (c.second - 2.3) * u, 4.6 * u, 4.6 * u)).fill()
        val e = UIBezierPath.bezierPath(); e.lineWidth = 1.6 * u; e.lineCapStyle = kCGLineCapRound; e.lineJoinStyle = kCGLineJoinRound
        e.moveToPoint(PT(3, 13)); e.addLineToPoint(PT(8, 13)); e.addLineToPoint(PT(10, 9)); e.addLineToPoint(PT(12.5, 16)); e.addLineToPoint(PT(14.5, 13)); e.addLineToPoint(PT(21, 13)); e.stroke()
    } else if (name == "pads") {             // ANDROID (proposta S4): piastre — due placche con il fulmine
        val a = UIBezierPath.bezierPathWithRoundedRect(CGRectMake(2.5 * u, 4 * u, 8 * u, 11 * u), 2 * u); a.lineWidth = 1.8 * u; a.stroke()
        val b = UIBezierPath.bezierPathWithRoundedRect(CGRectMake(13.5 * u, 9 * u, 8 * u, 11 * u), 2 * u); b.lineWidth = 1.8 * u; b.stroke()
        p.moveToPoint(PT(13, 2.5)); p.addLineToPoint(PT(10.5, 7.5)); p.addLineToPoint(PT(13.5, 7.5)); p.addLineToPoint(PT(11, 12.5)); p.stroke()
    } else if (name == "finger") {           // ANDROID (proposta S4): sonda SpO₂ — dito nella pinza
        val f = UIBezierPath.bezierPathWithRoundedRect(CGRectMake(8 * u, 2.5 * u, 8 * u, 19 * u), 4 * u); f.lineWidth = 1.8 * u; f.stroke()
        UIBezierPath.bezierPathWithRoundedRect(CGRectMake(5 * u, 9 * u, 14 * u, 7 * u), 2 * u).fill()
    } else if (name == "artline") {          // ANDROID (proposta S4): linea arteriosa — curva con l'incisura
        p.moveToPoint(PT(2.5, 17)); p.addCurveToPoint(PT(7.5, 7), PT(5, 16), PT(6, 7))
        p.addCurveToPoint(PT(10.5, 12), PT(9, 7), PT(9.5, 10.5)); p.addLineToPoint(PT(11.5, 11)); p.addCurveToPoint(PT(14, 17), PT(12.5, 13), PT(13, 17))
        p.addCurveToPoint(PT(18, 7), PT(15.5, 16), PT(16.5, 7)); p.addCurveToPoint(PT(21.5, 14), PT(19.5, 7), PT(20.5, 11)); p.stroke()
    } else if (name == "co2") {              // ANDROID (proposta S4): capnografia — onda quadra della CO₂
        p.moveToPoint(PT(2.5, 18)); p.addLineToPoint(PT(5, 18)); p.addLineToPoint(PT(6.5, 8)); p.addLineToPoint(PT(10.5, 7)); p.addLineToPoint(PT(11.5, 18))
        p.addLineToPoint(PT(13.5, 18)); p.addLineToPoint(PT(15, 8)); p.addLineToPoint(PT(19, 7)); p.addLineToPoint(PT(20, 18)); p.addLineToPoint(PT(21.5, 18)); p.stroke()
    } else if (name == "thermo") {           // ANDROID (proposta S4): termometro
        val t = UIBezierPath.bezierPathWithRoundedRect(CGRectMake(9.5 * u, 2.5 * u, 5 * u, 13 * u), 2.5 * u); t.lineWidth = 1.8 * u; t.stroke()
        UIBezierPath.bezierPathWithOvalInRect(CGRectMake(7.5 * u, 14 * u, 9 * u, 8 * u)).fill()
        p.moveToPoint(PT(12, 8)); p.addLineToPoint(PT(12, 15)); p.stroke()
    } else if (name == "cuff") {             // ANDROID (proposta S4): bracciale NIBP — fascia con il tubo e la pompetta
        val c = UIBezierPath.bezierPathWithRoundedRect(CGRectMake(3 * u, 4 * u, 12 * u, 9 * u), 2 * u); c.lineWidth = 1.8 * u; c.stroke()
        p.moveToPoint(PT(9, 13)); p.addCurveToPoint(PT(15, 19), PT(9, 17), PT(11, 19)); p.stroke()
        UIBezierPath.bezierPathWithOvalInRect(CGRectMake(15 * u, 15.5 * u, 6.5 * u, 6.5 * u)).fill()
    } else if (name == "power") {            // ANDROID (BugFixController 5): simbolo di accensione
        p.moveToPoint(PT(12, 3.5)); p.addLineToPoint(PT(12, 11.5)); p.stroke()
        val a = UIBezierPath.bezierPath(); a.lineWidth = 2 * u; a.lineCapStyle = kCGLineCapRound
        a.addArcWithCenter(PT(12, 13), 7.5 * u, -Math.PI / 2 + 0.75, -Math.PI / 2 - 0.75 + 2 * Math.PI, true); a.stroke()
    } else if (name == "bolt") {             // ANDROID (BugFixController 4): terapia elettrica — fulmine
        p.moveToPoint(PT(13.5, 2.5)); p.addLineToPoint(PT(6, 13.5)); p.addLineToPoint(PT(11.5, 13.5)); p.addLineToPoint(PT(10.5, 21.5)); p.addLineToPoint(PT(18, 10.5)); p.addLineToPoint(PT(12.5, 10.5)); p.closePath(); p.fill(); p.stroke()
    } else if (name == "camera") {           // ANDROID (sala 22/11): videocamera (pannello «Sala»)
        val r = UIBezierPath.bezierPathWithRoundedRect(CGRectMake(2.5 * u, 6.5 * u, 13 * u, 11 * u), 2.2 * u); r.lineWidth = 2 * u; r.stroke()
        p.moveToPoint(PT(15.5, 10.5)); p.addLineToPoint(PT(21.5, 7.5)); p.addLineToPoint(PT(21.5, 16.5)); p.addLineToPoint(PT(15.5, 13.5)); p.closePath(); p.stroke()
    } else if (name == "lock") {             // ANDROID (affordance 22/11, CTL-13): lucchetto (sola osservazione)
        val r = UIBezierPath.bezierPathWithRoundedRect(CGRectMake(5 * u, 10.5 * u, 14 * u, 10.5 * u), 2 * u); r.fill()
        p.moveToPoint(PT(8, 10.5)); p.addLineToPoint(PT(8, 8)); p.addCurveToPoint(PT(16, 8), PT(8, 2.5), PT(16, 2.5)); p.addLineToPoint(PT(16, 10.5)); p.stroke()
    } else if (name == "info") {             // ANDROID (affordance 22/11, CTL-19): «ⓘ» disegnato
        val c = UIBezierPath.bezierPathWithOvalInRect(CGRectMake(3 * u, 3 * u, 18 * u, 18 * u)); c.lineWidth = 1.8 * u; c.stroke()
        UIBezierPath.bezierPathWithOvalInRect(CGRectMake(10.8 * u, 6.6 * u, 2.4 * u, 2.4 * u)).fill()
        p.moveToPoint(PT(12, 11)); p.addLineToPoint(PT(12, 17)); p.stroke()
    } else if (name == "more") {             // ANDROID (affordance 22/11, CTL-06): «⋯» menu di regia
        for (x in listOf(6.0, 12.0, 18.0)) UIBezierPath.bezierPathWithOvalInRect(CGRectMake((x - 1.8) * u, 10.2 * u, 3.6 * u, 3.6 * u)).fill()
    } else if (name == "trash") {
        p.moveToPoint(PT(5, 7)); p.addLineToPoint(PT(19, 7)); p.moveToPoint(PT(9.5, 7)); p.addLineToPoint(PT(10.3, 4.5)); p.addLineToPoint(PT(13.7, 4.5)); p.addLineToPoint(PT(14.5, 7))
        p.moveToPoint(PT(7, 7)); p.addLineToPoint(PT(8, 20)); p.addLineToPoint(PT(16, 20)); p.addLineToPoint(PT(17, 7)); p.stroke()
    }
    val img = UIGraphicsGetImageFromCurrentImageContext()!!; UIGraphicsEndImageContext()
    return img
}

// MARK: - Pulsante

const val CtlButtonFilled = 0
const val CtlButtonTinted = 1
const val CtlButtonQuiet = 2

// Pulsante con risposta al tocco (si abbassa di tono mentre è premuto) e tre stili.
open class CtlButton(frame: CGRect) : UIButton(frame) {
    private var style = CtlButtonFilled; private var color: UIColor? = null
    private var base: UIFont? = null; private var applied: UIFont? = null
    private var icon: String? = null; private var iconAbove = false; private var styled = false
    private var iconView: UIImageView? = null; private var iconInk: UIColor? = null
    var onTap: (() -> Unit)? = null
    var maxLines: Int = 2      // righe ammesse per il testo (default 2)
    // ANDROID: ultimo adattamento fatto (titolo, carattere, misure, icona): se cambia qualcosa si ridispone prima di disegnare
    private var fitKey: String? = null
    private var fitTitle: String? = null
    private var lastHighlighted = false
    // ANDROID (affordance 22/11, CTL-03 / SISTEMA §2): esito del comando SUL tasto toccato (… in invio, ✓ eseguito 1,5 s,
    // ✕ non arrivato fino al tocco successivo) e un'etichetta fissa a sinistra (⚡ SUBITO, lucchetto in sola osservazione)
    private var fbL: UILabel? = null; private var fbSeq = 0
    private var tagL: UILabel? = null; private var tagIcon: UIImageView? = null; private var tagKey = ""
    var minFont = 13.0          // ANDROID (affordance 22/11, CTL-14): il testo di un comando non scende sotto i 13 pt
    var haptic = true           // ANDROID (affordance 22/11, CTL-15): 20 ms al tocco preso

    companion object {
        fun buttonWithTitle(title: String?, style: Int, color: UIColor?, frame: CGRect, action: ((CtlButton) -> Unit)?): CtlButton {
            val b = CtlButton(CGRectZero)
            b.frame = frame
            b.setTitle(title, UIControlStateNormal)
            b.titleLabel.font = UIFont.boldSystemFontOfSize(16)
            b.maxLines = 2; b.titleLabel.textAlignment = NSTextAlignmentCenter; b.titleLabel.lineBreakMode = NSLineBreakByWordWrapping
            b.layer.cornerRadius = 10.0
            b.setStyle(style, color)
            if (action != null) b.addTarget(UIControlEventTouchUpInside) { action(b) }
            b.addTarget(UIControlEventTouchUpInside) { b.fire() }
            return b
        }
    }
    private fun fire() { onTap?.invoke() }
    // ANDROID (affordance 22/11, CTL-09): su un fondo pieno chiaro (verde, giallo, arancione, ambra) il testo è scuro (13:1 invece
    // di 2:1 del bianco su verde); sui pieni scuri (blu, rosso) resta bianco
    private fun inkColor(): UIColor = if (style == CtlButtonFilled) (if (CtlLuma(color ?: CTL_TINT) > 0.4) CTL_ON_LIGHT else UIColor.whiteColor)
                                      else (if (style == CtlButtonTinted) color!! else CTL_INK)

    fun setStyle(style: Int, color: UIColor?) {
        val c = color ?: CTL_TINT
        if (styled && style == this.style && c == this.color) return       // chiamato a ogni aggiornamento: niente lavoro se non cambia nulla
        styled = true
        this.style = style; this.color = c
        when (style) {
            CtlButtonFilled -> backgroundColor = c
            CtlButtonTinted -> backgroundColor = c.colorWithAlphaComponent(0.18)
            CtlButtonQuiet -> backgroundColor = CTL_CARD2
        }
        // ANDROID (affordance 22/11, cura visiva): superfici con un bordo di 1 punto a bassa opacità (rilievo sobrio, niente ombre)
        layer.borderWidth = 1.0
        layer.borderColor = (when (style) { CtlButtonFilled -> UIColor.clearColor; CtlButtonTinted -> c.colorWithAlphaComponent(0.35); else -> UIColor.colorWithWhite(1, 0.08) }).CGColor
        setTitleColor(inkColor(), UIControlStateNormal)
        setTitleColor(CTL_INK3, UIControlStateDisabled)
        icon?.let { setIcon(it, iconAbove) }       // l'icona segue il colore del testo
    }

    // Icona disegnata (nessuna emoji): nome fra quelli di CtlIcon. sopra = true: icona sopra il testo; false: a sinistra.
    fun setIcon(name: String?, above: Boolean) {
        val ink = inkColor()
        if (iconView != null && name == icon && above == iconAbove && ink == iconInk) return
        iconInk = ink
        icon = name; iconAbove = above
        val iv = iconView ?: UIImageView().also { it.userInteractionEnabled = false; addSubview(it); iconView = it }
        iv.image = if (name != null) CtlIcon(name, if (above) 24.0 else 20.0, inkColor()) else null
        iv.hidden = name == null
        setNeedsLayout()
    }

    // Il testo deve stare nel pulsante SENZA puntini e SENZA parole spezzate o lettere orfane:
    // si riduce il carattere finché ogni riga (o ogni parola, se va a capo) entra nello spazio disponibile.
    override fun layoutSubviews() {
        super.layoutSubviews()
        val l = titleLabel; val t = titleForState(UIControlStateNormal) ?: ""
        if (t != fitTitle) { applied = null; fitTitle = t }           // ANDROID: al posto di setTitle:forState: ridefinito (_applied = nil)
        if (applied == null || !ctlFontEq(l.font, applied)) base = l.font            // carattere scelto da chi ha creato il pulsante
        val b0 = base!!
        val W = bounds.width; val H = bounds.height
        val icon = this.icon != null
        val isz = if (iconAbove) 24.0 else 20.0
        var area = CGRectInset(bounds, 8, 4)
        if (icon && iconAbove) area = CGRectMake(6, 6 + isz + 2, W - 12, H - 12 - isz - 2)
        else if (icon) area = CGRectMake(10 + isz + 6, 4, W - 10 - isz - 6 - 8, H - 8)
        // ANDROID (affordance 22/11): con un'etichetta visibile in alto a sinistra (SUBITO, IN ATTESA) il testo scende sotto di lei
        if (tagL != null && tagL?.hidden == false && !(icon && iconAbove) && H >= 50) area = CGRectMake(area.x, area.y + 14, area.width, area.height - 12)
        val maxLines = if (icon && iconAbove) 1 else maxOf(1, this.maxLines)     // (UIButton su iOS 9 riporta a 1 il numberOfLines dell'etichetta: si tiene qui)
        l.numberOfLines = maxLines; l.lineBreakMode = NSLineBreakByWordWrapping
        val explicit = t.split("\n")
        var f = b0; var size = b0.pointSize; var fits = true
        while (size >= minFont) {
            f = b0.fontWithSize(size)
            var ok = true
            val lh = ctlLineHeight(f)
            if (explicit.size > 1 || maxLines == 1) {           // righe decise da chi scrive: ognuna deve stare su una riga
                for (line in explicit) if (ceil(UkText.size(line, f).width) > area.width) ok = false
                if (explicit.size * lh > area.height + 1) ok = false
            } else {                                              // a capo libero: nessuna parola più larga del pulsante, al massimo maxLines righe
                for (w in t.split(' ', '\t')) if (ceil(UkText.size(w, f).width) > area.width) ok = false
                val r = ctlBounding(t, f, area.width)
                if (ctlLround(r.height / lh) > maxLines || r.height > area.height + 2) ok = false
            }
            fits = ok
            if (ok) break
            if (size - 0.5 < minFont) break          // ANDROID (affordance 22/11, CTL-14): mai sotto minFont
            size -= 0.5
        }
        if (CtlFitLog) SFLog.log(String.format(Locale.US, "FIT «%s» base %.1f -> %.1f area %.0fx%.0f maxLines %d%s", t, b0.pointSize, size, area.width, area.height, maxLines, if (fits) "" else " NON ENTRA"))
        if (!ctlFontEq(l.font, f)) l.font = f
        applied = l.font
        l.frame = area
        val iv = iconView
        if (icon && iv != null) {
            val ts = ctlBounding(t, f, area.width)
            if (iconAbove) {
                val total = isz + 3 + ceil(ts.height); val y0 = floor((H - total) / 2)
                iv.frame = CGRectMake(floor((W - isz) / 2), y0, isz, isz)
                l.frame = CGRectMake(6, y0 + isz + 3, W - 12, ceil(ts.height))
            } else {
                val total = isz + 6 + ceil(ts.width); val x0 = maxOf(8.0, floor((W - total) / 2))
                iv.frame = CGRectMake(x0, floor((H - isz) / 2), isz, isz)
                l.frame = CGRectMake(x0 + isz + 6, 4, minOf(area.width, ceil(ts.width) + 2), H - 8)
            }
        }
        layoutMarks()
        fitKey = currentFitKey()
    }

    // ANDROID (affordance 22/11): pillole sul tasto. Etichetta fissa in alto a sinistra, esito in alto a destra.
    private fun layoutMarks() {
        val W = bounds.width
        fbL?.let { it.frame = CGRectMake(W - it.frame.width - 4, 4, it.frame.width, 16); bringSubviewToFront(it) }
        tagL?.let { l ->
            val iw = if (tagIcon?.hidden == false) 14.0 else 0.0
            tagIcon?.let { it.frame = CGRectMake(6, 5, 12, 12); bringSubviewToFront(it) }
            l.frame = CGRectMake(4 + iw, 4, l.frame.width, 16); bringSubviewToFront(l)
        }
    }
    /** etichetta fissa (es. «SUBITO»); icon = nome CtlIcon (es. "lock"); null = nessuna */
    fun setTag(text: String?, fill: UIColor?, icon: String? = null) {
        val key = (text ?: "") + "|" + (fill?.argb ?: 0) + "|" + (icon ?: "") + "|" + style
        if (key == tagKey) return
        tagKey = key
        tagL?.removeFromSuperview(); tagL = null; tagIcon?.removeFromSuperview(); tagIcon = null
        if (icon != null) { val iv = UIImageView(CGRectMake(6, 5, 12, 12)); iv.image = CtlIcon(icon, 12.0, if (style == CtlButtonFilled) inkColor() else (fill ?: CTL_INK2)); iv.userInteractionEnabled = false; addSubview(iv); tagIcon = iv }
        if (text != null) { val l = CtlPill(text, fill ?: CTL_RED); addSubview(l); tagL = l }
        else if (icon != null) { val l = UILabel(CGRectZero); l.hidden = true; addSubview(l); tagL = l }
        setNeedsLayout(); layoutSubviews()
    }
    private fun showFb(t: String?, fill: UIColor?) {
        fbL?.removeFromSuperview(); fbL = null
        if (t == null || fill == null) return
        val l = CtlPill(t, fill); addSubview(l); fbL = l; layoutMarks()
    }
    /** comando partito da questo tasto: «…» finché il monitor non risponde */
    fun fbSending() { fbSeq++; showFb("…", CTL_TINT) }
    /** esito: ✓ verde per 1,5 s (poi lo stato vero) oppure ✕ rosso fino al tocco successivo */
    fun fbDone(ok: Boolean, critical: Boolean = false) {
        val seq = ++fbSeq
        if (ok) { showFb("✓", CTL_GREEN); if (critical) CtlHaptic.ok(); Uk.after(1.5) { if (fbSeq == seq) showFb(null, null) } }
        else { showFb("✕", CTL_RED); CtlHaptic.fail() }
    }
    fun fbClear() { fbSeq++; showFb(null, null) }

    // ANDROID: su iOS il pulsante si ridispone da solo al giro successivo; qui prima di disegnarsi, se qualcosa è cambiato
    private fun currentFitKey(): String {
        val f = titleLabel.font
        return (titleForState(UIControlStateNormal) ?: "") + "|" + f.pointSize + "," + f.weight + "," + f.mono + "|" +
            bounds.width + "x" + bounds.height + "|" + icon + "," + iconAbove + "," + maxLines + "|" + tagKey
    }
    override fun drawRect(ctx: CGContext, rect: CGRect) {
        if (currentFitKey() != fitKey) layoutSubviews()
    }
    override fun didMoveToWindow() { super.didMoveToWindow(); if (window != null && currentFitKey() != fitKey) layoutSubviews() }

    // setHighlighted:
    override fun stateChanged() {
        super.stateChanged()
        val h = highlighted
        if (h == lastHighlighted) return
        lastHighlighted = h
        // ANDROID (affordance 22/11, CTL-15 / cura visiva): vibrazione breve al tocco preso; un ✕ rimasto si toglie al tocco nuovo;
        // animazioni brevi (≤ 150 ms)
        if (h) { if (haptic) CtlHaptic.tap(); if (fbL?.text == "✕") fbClear() }
        UIView.animateWithDuration(if (h) 0.05 else 0.12, { alpha = if (h) 0.6f else (if (isEnabled) 1f else 0.45f); transform = if (h) CGAffineTransformMakeScale(0.97, 0.97) else CGAffineTransformIdentity })
        // ANDROID (22/11): con un tocco rapidissimo l'animazione «premuto» poteva finire DOPO quella di rilascio e il tasto
        // restava attenuato (visto su Bradicardia e «100» dopo Annulla). A rilascio avvenuto si fissa lo stato finale.
        if (!h) Uk.after(0.2) { if (!highlighted) { alpha = if (isEnabled) 1f else 0.45f; transform = CGAffineTransformIdentity } }
    }
    override fun setEnabled(enabled: Boolean) { super.setEnabled(enabled); alpha = if (enabled) 1f else 0.45f }
}

// MARK: - Riga di regolazione

// Riga di regolazione: titolo, valore grande, righello a zone con − e + ai lati.
// onChange(valore, definitivo): definitivo = false mentre il righello scorre, true quando si ferma, con una tacca o con − / +.
// ANDROID (righello a zone 22/11): tre fasce: (1) nome e valore grande, (2) i valori rapidi come una riga di tasti veri «Bradi / 40»
// colorati con la loro zona (quello del valore scelto pieno con «●»), (3) il righello a zone (CtlRuler.kt) fra − e + (64 punti).
// accessoryH = spazio fra l'intestazione e i tasti rapidi (la barra «Morfologia ECG» nella riga della FC).
// Altezza = CtlParamRow.height(accessoryH, conTasti, rulerH).
class CtlParamRow(frame: CGRect, title: String, unit: String, color: UIColor, min: Double, max: Double, step: Double, decimals: Int,
                  key: String = "", val accessoryH: Double = 0.0, val rulerH: Double = 64.0) : UIView(frame) {
    companion object {
        fun height(accessoryH: Double, presets: Boolean, rulerH: Double) = 54 + accessoryH + (if (presets) 50.0 else 0.0) + rulerH
    }
    val spec: RulerSpec = CtlRulerSpec(key, min, max, step, decimals)
    private val presetBtns = ArrayList<Pair<RulerPreset, CtlButton>>()
    private var presetAt = 0.0
    /** y della riga dei tasti rapidi (la riga dell'ampiezza della FV va qui) */
    val presetsY: Double get() = 54 + accessoryH
    private val title: UILabel; private val valueLabel: UILabel; private val unit: UILabel; private val note: UILabel
    val ruler: CtlRuler; private val minus: CtlButton; private val plus: CtlButton
    private val min: Double = min; private val max: Double = max; private val step: Double = step; private val dec: Int = decimals; private val color: UIColor = color
    private var repeat: NSTimer? = null; private var repeatDir = 0.0; private var holdTicks = 0
    // ANDROID (affordance 22/11, CTL-04): «in attesa» = riquadro tratteggiato + pillola IN ATTESA (il numero resta nel colore del
    // parametro); CTL-07: in transizione «79 →» a sinistra del valore di arrivo, una barra di avanzamento sotto e, sul righello,
    // la linea bianca «adesso» (l'indice colorato al centro è l'arrivo)
    private val dash: CtlDashBox; private val pendPill: UILabel
    private val nowL: UILabel; private val progBack: UIView; private val progFill: UIView
    var value: Double = min; private set
    val dragging: Boolean get() = ruler.busy
    var onChange: ((Double, Boolean) -> Unit)? = null
    /** tasto rapido toccato (PA: sistolica e diastolica insieme); null = la riga va al valore del tasto */
    var onPreset: ((RulerPreset) -> Unit)? = null

    init {
        val W = frame.width
        this.title = CtlLabel(CGRectMake(0, 0, W - 190, 24), title, 17.0, true, CTL_INK); addSubview(this.title)
        note = CtlLabel(CGRectMake(0, 26, W - 190, 18), "", 13.0, false, CTL_INK2); addSubview(note)
        dash = CtlDashBox(CGRectMake(W - 214, -2, 214, 56), CTL_YELLOW); dash.hidden = true; addSubview(dash)
        valueLabel = CtlLabel(CGRectMake(W - 250, 0, 180, 50), "--", 46.0, true, color)
        valueLabel.font = CtlDigits(46.0, true); valueLabel.textAlignment = NSTextAlignmentRight
        valueLabel.adjustsFontSizeToFitWidth = true; valueLabel.minimumScaleFactor = 0.6
        addSubview(valueLabel)
        this.unit = CtlLabel(CGRectMake(W - 64, 22, 60, 20), unit, 13.0, false, CTL_INK2); addSubview(this.unit)
        pendPill = CtlPill("IN ATTESA", CTL_YELLOW); pendPill.hidden = true; addSubview(pendPill)
        pendPill.frame = CGRectMake(W - 214 - 8 - pendPill.frame.width, 18, pendPill.frame.width, 18)
        nowL = CtlLabel(CGRectMake(0, 10, 120, 34), "", 24.0, true, CTL_INK2); nowL.font = CtlDigits(24.0, true); nowL.textAlignment = NSTextAlignmentRight
        nowL.hidden = true; addSubview(nowL)
        progBack = UIView(CGRectMake(W - 200, 51, 196, 3)); progBack.backgroundColor = CTL_CARD2; progBack.layer.cornerRadius = 1.5; progBack.hidden = true; addSubview(progBack)
        progFill = UIView(CGRectMake(0, 0, 0, 3)); progFill.backgroundColor = color; progFill.layer.cornerRadius = 1.5; progBack.addSubview(progFill)
        // tasti rapidi «nome / valore» a tutta larghezza (44 punti), colorati con la zona del valore
        val pr = spec.presets
        if (pr.isNotEmpty()) {
            val bw = (W - (pr.size - 1) * 6) / pr.size
            for ((i, p) in pr.withIndex()) {
                val b = CtlButton.buttonWithTitle(p.name + "\n" + (p.text ?: RulerNum(p.value, decimals)), CtlButtonQuiet, null, CGRectMake(i * (bw + 6), 54 + accessoryH, bw, 44), null)
                b.titleLabel.font = UIFont.boldSystemFontOfSize(14); b.maxLines = 2
                b.accessibilityIdentifier = "quick." + key + "." + RulerNum(p.value, decimals).replace(",", ".")
                b.onTap = { presetTapped(p) }
                addSubview(b); presetBtns.add(Pair(p, b))
            }
        }
        // − e + grandi ai lati (64 punti), il righello in mezzo (≥ 70 % della larghezza)
        val y0 = 54 + accessoryH + (if (pr.isNotEmpty()) 50.0 else 0.0)
        minus = CtlButton.buttonWithTitle("−", CtlButtonQuiet, null, CGRectMake(0, y0, 64, rulerH), null)
        plus = CtlButton.buttonWithTitle("+", CtlButtonQuiet, null, CGRectMake(W - 64, y0, 64, rulerH), null)
        for (b in listOf(minus, plus)) {
            b.titleLabel.font = UIFont.systemFontOfSize(32)
            b.addTarget(UIControlEventTouchDown) { stepDown(it as UIButton) }
            b.addTarget(UIControlEventTouchUpInside or UIControlEventTouchUpOutside or UIControlEventTouchCancel) { stepUp(it as UIButton) }
            addSubview(b)
        }
        ruler = CtlRuler(CGRectMake(72, y0, W - 144, rulerH), spec, color)
        ruler.onChange = { v, final -> rulerMoved(v, final) }
        addSubview(ruler)
        value = min
    }

    /** ANDROID (affordance 22/11, CTL-07): transizione in corso. now = valore di adesso (null = nessuna transizione),
     *  frac = avanzamento 0…1 */
    fun setTransition(now: Double?, frac: Double?) {
        val on = now != null && !dashOn
        nowL.hidden = !on; progBack.hidden = !on || frac == null
        ruler.nowValue = now
        if (now == null) return
        val t = (if (dec > 0) String.format(Locale.US, "%.${dec}f", now).replace(".", ",") else String.format(Locale.US, "%d", ctlLround(now))) + " →"
        if (nowL.text != t) nowL.text = t
        val vw = ceil(UkText.size(valueLabel.text ?: "", valueLabel.font).width)
        val right = valueLabel.frame.x + valueLabel.frame.width - minOf(vw, valueLabel.frame.width) - 8
        nowL.frame = CGRectMake(right - 130, 10, 130, 34)
        frac?.let { progFill.frame = CGRectMake(0, 0, progBack.bounds.width * it.coerceIn(0.0, 1.0), 3) }
    }
    private var dashOn = false

    /** ANDROID (affordance 22/11, X-02): in arresto la riga della FC si chiama «FC al ROSC» */
    fun setTitle(t: String) { if (title.text != t) title.text = t }
    /** ANDROID (righello a zone 22/11): limiti di allarme del monitor sul righello */
    fun setLimits(lo: Double?, hi: Double?) = ruler.setLimits(lo, hi)
    /** ANDROID (righello a zone 22/11): sola osservazione = lucchetto ambra su righello, −, + e tasti rapidi */
    fun setLocked(on: Boolean) {
        ruler.setLocked(on)
        for (b in listOf(minus, plus) + presetBtns.map { it.second }) if (on) b.setTag(null, CTL_AMBER, "lock") else b.setTag(null, null)
    }
    /** ANDROID (righello a zone 22/11): in FV al posto dei tasti rapidi c'è la riga dell'ampiezza (il righello resta per la FC al ROSC) */
    fun setPresetsHidden(h: Boolean) { for (p in presetBtns) if (p.second.hidden != h) p.second.hidden = h }
    // tasto rapido: anti doppio tocco 0,8 s, poi come prima (valore definitivo), o la coppia della PA
    private fun presetTapped(p: RulerPreset) {
        val now = CACurrentMediaTime(); if (now - presetAt < 0.8) return
        presetAt = now
        val cb = onPreset
        if (cb != null) { cb(p); value = snap(p.value); ruler.jumpTo(value, false); render(true) } else debugSet(p.value)
    }
    // tasto del valore scelto pieno con «●» nel colore della sua zona; gli altri a contorno tenue nel colore della zona
    private var presetKey = Double.NaN
    private fun stylePresets() {
        if (presetBtns.isEmpty() || presetKey == value) return
        presetKey = value
        for ((p, b) in presetBtns) {
            val lv = if (p.level >= 0) p.level else spec.zoneLevel(p.value)
            val zc = if (lv == 2) CTL_RED else if (lv == 1) CTL_AMBER else CTL_GREEN
            val on = abs(p.value - value) < step / 2
            b.setStyle(if (on) CtlButtonFilled else CtlButtonQuiet, if (on) zc else null)
            if (!on) {
                b.backgroundColor = zc.colorWithAlphaComponent(0.10); b.layer.borderWidth = 1.0; b.layer.borderColor = zc.colorWithAlphaComponent(0.5).CGColor
                b.setTitleColor(if (lv == 2) CtlRGB(1.0, 0.70, 0.67) else if (lv == 1) CtlRGB(1.0, 0.84, 0.58) else CtlRGB(0.66, 0.95, 0.74), UIControlStateNormal)
            } else b.layer.borderWidth = 0.0
            val t = (if (on) "● " else "") + p.name + "\n" + (p.text ?: RulerNum(p.value, dec))
            if (b.titleForState(UIControlStateNormal) != t) b.setTitle(t, UIControlStateNormal)
        }
    }

    // setAccessibilityIdentifier: — ANDROID: copiato su −, + e righello (in `uk` il setter non si può ridefinire)
    private fun syncIds() {
        val i = accessibilityIdentifier ?: return
        minus.accessibilityIdentifier = i + ".meno"; plus.accessibilityIdentifier = i + ".piu"; ruler.accessibilityIdentifier = i + ".righello"
    }
    override fun drawRect(ctx: CGContext, rect: CGRect) { syncIds() }

    private fun snap(v0: Double): Double { val v = ctlRound(v0 / step) * step; return maxOf(min, minOf(max, v)) }
    private fun render(pending: Boolean) {
        valueLabel.text = if (dec > 0) String.format(Locale.US, "%.${dec}f", value).replace(".", ",")
                          else String.format(Locale.US, "%d", ctlLround(value))
        // ANDROID (affordance 22/11, CTL-04): il numero resta nel colore del parametro; mentre il righello scorre (o si tiene premuto
        // − / +) è chiaro (non ancora mandato); «in attesa» lo dice il riquadro tratteggiato
        valueLabel.textColor = if (dragging || repeat != null) CTL_INK else color
        // «al limite»: − spento al minimo, + spento al massimo (MATRICE_STATI C.5)
        val lo = value > min + step / 2; val hi = value < max - step / 2
        if (repeat == null) { if (minus.isEnabled != lo) minus.isEnabled = lo; if (plus.isEnabled != hi) plus.isEnabled = hi }
        stylePresets()
    }
    /** ANDROID (affordance 22/11, CTL-04): modifica preparata in «Prepara» che aspetta «Applica» */
    fun setPending(on: Boolean) {
        if (dashOn == on) return
        dashOn = on; dash.hidden = !on; pendPill.hidden = !on
        if (on) { nowL.hidden = true; progBack.hidden = true }
    }
    // dal modello (ignorato mentre il righello scorre o si tiene premuto − / +)
    fun showValue(value: Double, pending: Boolean) {
        syncIds()
        if (dragging || repeat != null) return
        this.value = snap(value); ruler.showValue(this.value); render(pending); setPending(pending)
    }
    // riga piccola sotto il titolo (es. "→ 45 in 11 s")
    fun setNote(note: String?) { this.note.text = note ?: "" }

    private fun rulerMoved(v: Double, final: Boolean) { value = v; render(true); onChange?.invoke(v, final) }

    // − / +: un tocco = un passo; tenendo premuto i passi si ripetono (dopo mezzo secondo, poi più in fretta);
    // al rilascio il valore diventa definitivo
    private fun stepDown(b: UIButton) {
        repeatDir = if (b === minus) -1.0 else 1.0; holdTicks = 0
        bump(1)
        repeat?.invalidate()
        repeat = NSTimer.scheduledTimerWithTimeInterval(0.12, true) { repeatTick() }
    }
    private fun repeatTick() {
        holdTicks++
        if (holdTicks > 4) bump(if (holdTicks > 20) 5 else 1)
    }
    private fun bump(steps: Int) {
        value = snap(value + repeatDir * step * steps); ruler.nudgeTo(value); render(true)
        onChange?.invoke(value, false)
    }
    private fun stepUp(b: UIButton?) {
        if (repeat == null) return
        repeat?.invalidate(); repeat = null
        render(true)
        onChange?.invoke(value, true)
    }
    // riga ricostruita mentre il dito tiene premuto: ferma la ripetizione e rende definitivo il valore
    override fun didMoveToWindow() { if (window == null && repeat != null) stepUp(null); if (window != null) syncIds() }
    // verifica da Mac: come una tacca del righello
    fun debugSet(value: Double) { syncIds(); this.value = snap(value); ruler.jumpTo(this.value, false); render(true); onChange?.invoke(this.value, true) }
}
