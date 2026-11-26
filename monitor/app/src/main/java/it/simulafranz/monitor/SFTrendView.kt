// ANDROID (funzioni mancanti 22/11) — file nuovo, solo Android.
// Viste comuni ai layout per le funzioni aggiunte dopo gli audit clinici (BugFix.md «Funzioni mancanti 22/11»):
//  - SFTrendView (IN-14): finestra «Trend» sovrapposta, TABELLA (una riga ogni 1/5/15 min) e GRAFICO (FC, SpO2, ART S/D/M e
//    NIBP, FR ed EtCO2, T1/T2, PVC) dai numeri a schermo che il cuore registra ogni 5 s (SimulationState.trendSince). Aperta dai
//    partecipanti col tasto Trend del layout; si chiude con «Chiudi» (o di nuovo il tasto). Colori del layout (SFTrendPalette).
//  - SFLongPress (IN-12): tocco lungo (2,5 s) su traccia/riquadro = «controllo e sistemo»: resolve_technical col tipo giusto.
//  - SFTechKindFor: distrattore tecnico attivo del parametro (tipo per resolve_technical), null se non c'è.
//  - SFArtNumbers: testo S/D (M) dell'ART con azzeramento / linea da azzerare.
package it.simulafranz.monitor

import it.simulafranz.core.*
import it.simulafranz.uk.*
import java.util.Locale
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/** colori del layout per la finestra Trend */
class SFTrendPalette(
    val bg: UIColor, val panel: UIColor, val text: UIColor, val sub: UIColor, val grid: UIColor, val accent: UIColor,
    val hr: UIColor, val spo2: UIColor, val art: UIColor, val nibp: UIColor, val co2: UIColor, val rr: UIColor, val temp: UIColor, val cvp: UIColor)

val SFTrendPaletteIcu = SFTrendPalette(
    UIColor.colorWithWhite(0.0, 0.92), UIColor.colorWithWhite(0.07, 1), UIColor.colorWithWhite(0.92, 1), UIColor.colorWithWhite(0.55, 1),
    UIColor.colorWithWhite(0.20, 1), UIColor.colorWithRed(0.55, 0.80, 1.0, 1),
    UIColor.colorWithRed(0.20, 1.00, 0.35, 1), UIColor.colorWithRed(0.20, 0.90, 1.00, 1), UIColor.colorWithRed(1.00, 0.23, 0.19, 1),
    UIColor.colorWithRed(0.94, 0.33, 0.48, 1), UIColor.colorWithRed(1.00, 0.88, 0.20, 1), UIColor.colorWithWhite(0.92, 1),
    UIColor.colorWithRed(0.80, 0.62, 1.00, 1), UIColor.colorWithRed(0.40, 0.65, 1.00, 1))

class SFTrendView(frame: CGRect, private val state: SimulationState, private val pal: SFTrendPalette, private val onClose: () -> Unit) : UIView(frame) {
    private var graph = false
    private var step = 300                         // secondi per riga (tabella) / finestra del grafico (×24)
    private var rows: List<Map<String, Any?>> = emptyList()
    private var timer: NSTimer? = null
    private val keys = LinkedHashMap<String, UIButton>()
    private val body = CGRectMake(14.0, 64.0, frame.size.width - 28, frame.size.height - 78)

    init {
        backgroundColor = pal.bg
        layer.cornerRadius = 6.0; layer.borderWidth = 1.5; layer.borderColor = pal.grid.CGColor
        val t = UILabel(CGRectMake(16, 12, 220, 36)); t.text = "Trend"; t.font = UIFont.boldSystemFontOfSize(24); t.textColor = pal.text
        t.userInteractionEnabled = false; addSubview(t)
        var x = 130.0
        for ((k, title) in listOf("T-TAB" to "Tabella", "T-GRA" to "Grafico", "T-1" to "1 min", "T-5" to "5 min", "T-15" to "15 min")) {
            val b = key(k, title, CGRectMake(x, 10, if (k.startsWith("T-T") || k == "T-GRA") 120.0 else 92.0, 42)) { tap(k) }
            x += b.frame.size.width + 8
            if (k == "T-GRA") x += 16
        }
        key("T-CHIUDI", "Chiudi", CGRectMake(frame.size.width - 136, 10, 120, 42)) { close() }
        refresh()
        timer = NSTimer.scheduledTimerWithTimeInterval(5.0, true) { refresh() }
    }

    private fun key(name: String, title: String, f: CGRect, action: () -> Unit): UIButton {
        val b = UIButton.buttonWithType(UIButtonTypeCustom)
        b.frame = f; b.setTitle(title, UIControlStateNormal); b.setTitleColor(pal.text, UIControlStateNormal)
        b.titleLabel.font = UIFont.boldSystemFontOfSize(17); b.accessibilityIdentifier = name
        b.layer.cornerRadius = 5.0; b.layer.borderWidth = 1.2; b.layer.borderColor = pal.sub.CGColor
        b.addTarget(UIControlEventTouchUpInside) { action() }
        addSubview(b); keys[name] = b
        return b
    }
    private fun markKeys() {
        for ((k, b) in keys) {
            val sel = (k == "T-TAB" && !graph) || (k == "T-GRA" && graph) || (k == "T-1" && step == 60) || (k == "T-5" && step == 300) || (k == "T-15" && step == 900)
            val c = if (sel) pal.accent.colorWithAlphaComponent(0.30).CGColor else UIColor.clearColor.CGColor
            if (b.layer.backgroundColor != c) b.layer.backgroundColor = c
            b.layer.borderColor = (if (sel) pal.accent else pal.sub).CGColor
        }
    }
    /** tasti per le prove da Mac (tap?id=…) */
    fun tap(k: String) {
        when (k) { "T-TAB" -> graph = false; "T-GRA" -> graph = true; "T-1" -> step = 60; "T-5" -> step = 300; "T-15" -> step = 900; "T-CHIUDI" -> { close(); return } }
        refresh()
    }
    fun close() { timer?.invalidate(); timer = null; removeFromSuperview(); onClose() }
    fun refresh() {
        if (timer != null && superview == null && rows.isNotEmpty()) { timer?.invalidate(); timer = null; return }   // tolta dal layout (Home)
        // grafico: tutti i campioni dell'ultima finestra (step × 24: 24 min, 2 h, 6 h) — tabella: una riga per passo
        val now = state.snapshot().scenarioTime
        rows = if (graph) state.trendSince(max(0.0, now - step * 24.0), if (step >= 300) step / 30 else 0)
               else state.trendSince(0.0, step).let { t -> val last = state.trendSince(max(0.0, now - 10), 0).lastOrNull()   // + la riga di adesso
                   if (last != null && t.lastOrNull()?.get("t") != last["t"]) t + last else t }
        markKeys(); setNeedsDisplay()
    }

    override fun drawRect(ctx: CGContext, rect: CGRect) {
        ctx.setFillColor(pal.panel); ctx.fillRect(body)
        if (rows.isEmpty()) {
            ctx.drawText("Nessun dato: il trend si registra a scenario avviato (un campione ogni 5 s).",
                CGRectMake(body.x, body.y + body.size.height / 2 - 14, body.size.width, 28), UIFont.systemFontOfSize(18), pal.sub, NSTextAlignmentCenter)
            return
        }
        if (graph) drawGraph(ctx) else drawTable(ctx)
    }

    private fun n(r: Map<String, Any?>, k: String): Double? = (r[k] as? Number)?.toDouble()
    private fun mmss(t: Double): String { val s = t.toLong(); return String.format(Locale.US, "%02d:%02d", s / 60, s % 60) }
    private fun hhmm(w: Any?): String { val e = (w as? Number)?.toLong() ?: return ""; return java.text.SimpleDateFormat("HH:mm", Locale.US).format(java.util.Date(e * 1000)) }

    private fun drawTable(ctx: CGContext) {
        val cols = listOf(Triple("Ora", 96.0, pal.text), Triple("FC", 64.0, pal.hr), Triple("SpO2", 64.0, pal.spo2), Triple("ART S/D (M)", 150.0, pal.art),
            Triple("NIBP S/D (M)", 150.0, pal.nibp), Triple("FR", 56.0, pal.rr), Triple("EtCO2", 70.0, pal.co2), Triple("T1", 64.0, pal.temp),
            Triple("T2", 64.0, pal.temp), Triple("PVC", 60.0, pal.cvp))
        val used = cols.filter { c -> c.first in listOf("Ora", "FC", "SpO2", "FR", "EtCO2") ||
            rows.any { r -> when (c.first) { "ART S/D (M)" -> r["art_s"] != null; "NIBP S/D (M)" -> r["nibp_s"] != null; "T1" -> r["temp"] != null; "T2" -> r["temp2"] != null; else -> r["cvp"] != null } } }
        val totW = used.sumOf { it.second }; val k = min(1.6, (body.size.width - 12) / totW)
        val fH = UIFont.boldSystemFontOfSize(15); val fV = UIFont.systemFontOfSize(18)
        var x = body.x + 6.0; val y0 = body.y + 6
        for (c in used) { ctx.drawText(c.first, CGRectMake(x, y0, c.second * k, 22), fH, c.third, NSTextAlignmentCenter); x += c.second * k }
        ctx.setFillColor(pal.grid); ctx.fillRect(CGRectMake(body.x + 4, y0 + 26, body.size.width - 8, 1))
        val rh = 27.0; val maxRows = floor((body.size.height - 40) / rh).toInt()
        var y = y0 + 32
        for (r in rows.reversed().take(maxRows)) {                         // la più recente in alto
            x = body.x + 6.0
            for (c in used) {
                val t = when (c.first) {
                    "Ora" -> mmss(n(r, "t") ?: 0.0) + "  " + hhmm(r["w"])
                    "FC" -> f0(n(r, "hr")); "SpO2" -> f0(n(r, "spo2")); "FR" -> f0(n(r, "rr")); "EtCO2" -> f0(n(r, "etco2")); "PVC" -> f0(n(r, "cvp"))
                    "ART S/D (M)" -> if (r["art_s"] == null) "---" else "${f0(n(r, "art_s"))}/${f0(n(r, "art_d"))} (${f0(n(r, "art_m"))})"
                    "NIBP S/D (M)" -> if (r["nibp_s"] == null) "---" else "${f0(n(r, "nibp_s"))}/${f0(n(r, "nibp_d"))} (${f0(n(r, "nibp_m"))})"
                    "T1" -> f1(n(r, "temp")); "T2" -> f1(n(r, "temp2")); else -> ""
                }
                ctx.drawText(t, CGRectMake(x, y, c.second * k, rh - 4), if (c.first == "Ora") UIFont.systemFontOfSize(14) else fV,
                    if (c.first == "Ora") pal.sub else c.third, NSTextAlignmentCenter)
                x += c.second * k
            }
            y += rh
        }
        ctx.drawText("Una riga ogni ${step / 60} min · la più recente in alto · tempo di scenario e ora", CGRectMake(body.x, body.maxY - 22, body.size.width - 8, 18),
            UIFont.systemFontOfSize(12), pal.sub, NSTextAlignmentRight)
    }
    private fun f0(v: Double?): String = if (v == null) "---" else Math.round(v).toString()
    private fun f1(v: Double?): String = if (v == null) "---" else String.format(Locale.ITALY, "%.1f", v)

    private fun drawGraph(ctx: CGContext) {
        val span = step * 24.0
        val tEnd = max(span, n(rows.last(), "t") ?: 0.0); val tStart = tEnd - span
        class Ser(val key: String, val color: UIColor, val dots: Boolean = false)
        class Strip(val title: String, val lo: Double, val hi: Double, val series: List<Ser>)
        val strips = ArrayList<Strip>()
        strips.add(Strip("FC 1/min", 0.0, 200.0, listOf(Ser("hr", pal.hr))))
        strips.add(Strip("SpO2 %", 60.0, 100.0, listOf(Ser("spo2", pal.spo2))))
        strips.add(Strip("PA mmHg", 0.0, 200.0, listOf(Ser("art_s", pal.art), Ser("art_m", pal.art), Ser("art_d", pal.art), Ser("nibp_s", pal.nibp, true), Ser("nibp_d", pal.nibp, true))))
        strips.add(Strip("FR · EtCO2", 0.0, 60.0, listOf(Ser("rr", pal.rr), Ser("etco2", pal.co2))))
        if (rows.any { it["temp"] != null || it["temp2"] != null }) strips.add(Strip("T °C", 34.0, 41.0, listOf(Ser("temp", pal.temp), Ser("temp2", pal.cvp))))
        if (rows.any { it["cvp"] != null }) strips.add(Strip("PVC mmHg", 0.0, 30.0, listOf(Ser("cvp", pal.cvp))))
        val axisW = 96.0; val gx = body.x + axisW; val gw = body.size.width - axisW - 10
        val sh = (body.size.height - 30) / strips.size
        val fT = UIFont.boldSystemFontOfSize(14); val fS = UIFont.systemFontOfSize(11)
        for ((i, st) in strips.withIndex()) {
            val y = body.y + i * sh + 4; val h = sh - 10
            ctx.drawText(st.title, CGRectMake(body.x + 6, y, axisW - 10, 18), fT, st.series.first().color, NSTextAlignmentLeft)
            ctx.drawText(f0(st.hi), CGRectMake(body.x + 6, y + 18, axisW - 14, 14), fS, pal.sub, NSTextAlignmentRight)
            ctx.drawText(f0(st.lo), CGRectMake(body.x + 6, y + h - 14, axisW - 14, 14), fS, pal.sub, NSTextAlignmentRight)
            ctx.setStrokeColor(pal.grid); ctx.setLineWidth(1); ctx.strokeRect(CGRectMake(gx, y, gw, h))
            // ultimo valore a destra del titolo
            val last = rows.last()
            val lastTxt = st.series.filter { !it.dots && last[it.key] != null }.joinToString(" / ") { if (it.key.startsWith("temp")) f1(n(last, it.key)) else f0(n(last, it.key)) }
            if (lastTxt.isNotEmpty()) ctx.drawText(lastTxt, CGRectMake(body.x + 6, y + h / 2 - 9, axisW - 14, 18), UIFont.boldSystemFontOfSize(15), pal.text, NSTextAlignmentRight)
            for (se in st.series) {
                ctx.setStrokeColor(se.color); ctx.setFillColor(se.color); ctx.setLineWidth(if (se.key == "art_m") 1.2 else 2.0)
                var pen = false; ctx.beginPath()
                for (r in rows) {
                    val v = n(r, se.key); val t = n(r, "t") ?: continue
                    if (se.dots) {
                        if (v != null && r["nibp_new"] == true) {
                            val px = gx + (t - tStart) / span * gw; val py = y + h - (min(st.hi, max(st.lo, v)) - st.lo) / (st.hi - st.lo) * h
                            ctx.fillEllipseInRect(CGRectMake(px - 3.5, py - 3.5, 7, 7))
                        }
                        continue
                    }
                    if (v == null || t < tStart) { pen = false; continue }
                    val px = gx + (t - tStart) / span * gw; val py = y + h - (min(st.hi, max(st.lo, v)) - st.lo) / (st.hi - st.lo) * h
                    if (pen) ctx.addLineToPoint(px, py) else ctx.moveToPoint(px, py); pen = true
                }
                if (!se.dots) ctx.strokePath()
            }
        }
        // asse del tempo
        val ay = body.maxY - 22
        for (k in 0..4) {
            val t = tStart + span * k / 4
            ctx.drawText(if (t < 0) "" else mmss(t), CGRectMake(gx + gw * k / 4 - 40, ay, 80, 18), fS, pal.sub, NSTextAlignmentCenter)
        }
        ctx.drawText("ultimi ${(span / 60).toInt()} min", CGRectMake(body.x + 4, ay, axisW, 18), fS, pal.sub, NSTextAlignmentLeft)
    }
}

/** apre/chiude la finestra Trend nel contenitore (secondo tocco del tasto = chiude). Ritorna la vista aperta (null = chiusa). */
fun SFTrendToggle(host: UIView, frame: CGRect, state: SimulationState, pal: SFTrendPalette, current: SFTrendView?, onClose: () -> Unit): SFTrendView? {
    if (current != null) { current.close(); return null }
    val v = SFTrendView(frame, state, pal, onClose)
    host.addSubview(v)
    return v
}

/** IN-12: distrattore tecnico attivo del parametro (tipo per resolve_technical), null se non ce n'è */
fun SFTechKindFor(param: String, s: SLSnapshot): String? {
    val ta = s.technicalAlarms
    return when (param) {
        "spo2" -> if (ta.contains("spo2_probe_off")) "spo2_probe_off" else if (s.spo2SignalPoorSet) "spo2_signal" else null
        "ecg", "hr", "rr" -> if (ta.contains("ecg_leads_off")) "ecg_leads_off" else if (s.ecgElectrodeOff != null) "ecg_electrode" else if (s.ecgNoise > 0) "ecg_noise" else null
        "nibp" -> if (ta.contains("nibp_cuff_error")) "nibp_cuff_error" else if (ta.contains("nibp_timeout")) "nibp_timeout" else null
        "etco2", "co2" -> if (ta.contains("etco2_occlusion")) "etco2_occlusion" else null
        "art" -> if (s.artDamping != 0 || s.artOccluded) "art_line" else null
        else -> null
    }
}
/** IN-12: tenta di risolvere il tecnico del parametro (il cuore rifiuta se la regia non l'ha reso risolvibile). true = risolto */
fun SFTryResolveTech(state: SimulationState, param: String): Boolean {
    val k = SFTechKindFor(param, state.snapshot()) ?: return false
    return state.applyCommand(mapOf("command" to "resolve_technical", "value" to mapOf("kind" to k))) == null
}

/** IN-12: tocco lungo su un tasto (di solito l'area trasparente di una traccia/riquadro): azione dopo `seconds` */
object SFLongPress {
    fun attach(b: UIButton, seconds: Double = 2.5, action: () -> Unit) {
        var t: NSTimer? = null
        b.addTarget(UIControlEventTouchDown) { t?.invalidate(); t = NSTimer.scheduledTimerWithTimeInterval(seconds, false) { t = null; action() } }
        b.addTarget(UIControlEventTouchUpInside or UIControlEventTouchUpOutside or UIControlEventTouchCancel or UIControlEventTouchDragExit) { t?.invalidate(); t = null }
    }
}

/** IN-03: testo S/D dell'ART a schermo («---/---» durante lo zero o se la linea va azzerata) e stato della linea */
fun SFArtLineStatus(s: SLSnapshot): String? = when {
    !s.artConnected -> null
    s.artZeroing -> "ZERO IN CORSO"
    s.artNeedsZero -> "AZZERARE"
    s.artOccluded -> "VERIFICARE LINEA"
    else -> null
}
