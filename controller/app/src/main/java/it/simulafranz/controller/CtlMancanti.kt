// ANDROID (funzioni mancanti 22/11) — pannello «Avanzate» della regia (file nuovo, solo controller).
// Stesse funzioni della web regia (BugFix.md / BugFixController.md «Funzioni mancanti 22/11»), tutte facoltative: di serie il
// monitor si comporta come prima. Quattro schede in un pannello sopra la regia (come «Come vedi l'RCP?», CtlCprPanel):
//  - Trend: storia dei parametri a schermo del monitor (GET /api/v1/trend), grafico + tabella, passo 1/5/15 min;
//  - Linee e ventilazione: linea arteriosa (smorzamento, livello, occlusione, zero obbligatorio, Zero, lavaggio), PVC con i
//    preset e la forma, temperatura centrale (T2), ventilazione (spontaneo / ventilatore / pallone, FR, volume, scollegato,
//    via aerea avanzata, frequenza in RCP), PPV;
//  - Allarmi: profili (di serie, ICU shock, post-ROSC), limiti separati ART/NIBP, PAM, SpO₂ alta, temperatura (0 = spento),
//    ritardi per parametro, riga d'aiuto per la RCP con «Silenzia 2 min» (AE-18);
//  - Distrattori ed esami: elettrodo singolo, disturbo, inversione braccia, NIBP tempo superato, contatto piastre non ottimale,
//    tecnici risolvibili dai partecipanti, esami/bilancio nel registro (set_labs).
// Regole della regia: in sola osservazione si consulta e basta (avviso breve m.onBlocked), i valori mostrati vengono dallo
// stato del monitor (un valore toccato si mostra 2,5 s in attesa della conferma), scelte = tasti con «●» pieni.
package it.simulafranz.controller

import it.simulafranz.uk.*
import java.util.Locale

private fun fxI(v: Any?, d: Int): Int = (v as? Number)?.toInt() ?: d
private fun fxD(v: Any?): Double? = (v as? Number)?.toDouble()
private fun fxB(v: Any?): Boolean = v == true || (v is Number && v.toInt() != 0)
private fun fxM(v: Any?): Map<String, Any?> = (v as? Map<String, Any?>) ?: emptyMap()
private fun fxF(f: String, vararg a: Any?) = String.format(Locale.ITALY, f, *a)
private fun fxMMSS(s: Int) = String.format(Locale.US, "%02d:%02d", s / 60, s % 60)

/** ANDROID (funzioni mancanti 22/11): grafico dei trend (tre fasce: FC e PA · SpO₂ · FR ed EtCO₂) */
class CtlTrendGraph(frame: CGRect) : UIView(frame) {
    var rows: List<Map<String, Any?>> = emptyList()
        set(v) { field = v; setNeedsDisplay() }
    init { backgroundColor = CTL_CARD2; layer.cornerRadius = 10.0; clipsToBounds = true }
    override fun drawRect(ctx: CGContext, rect: CGRect) {
        val W = bounds.width; val H = bounds.height
        val L = 44.0; val R = W - 10; val f = UIFont.systemFontOfSize(10)
        if (rows.size < 2) { ctx.drawText("Il trend parte con «Avvia»: un punto ogni 5 s di scenario.", CGRectMake(0, H / 2 - 10, W, 20), UIFont.systemFontOfSize(13), CTL_INK3, NSTextAlignmentCenter); return }
        val t0 = fxD(rows.first()["t"]) ?: 0.0; val t1 = Math.max(t0 + 1, fxD(rows.last()["t"]) ?: 1.0)
        fun x(t: Double) = L + (R - L) * (t - t0) / (t1 - t0)
        val bands = listOf(Triple(0.0, 200.0, "FC · PA"), Triple(70.0, 100.0, "SpO₂"), Triple(0.0, 60.0, "FR · EtCO₂"))
        val bh = (H - 22) / 3
        for ((i, b) in bands.withIndex()) {
            val top = 6 + i * bh; val bot = top + bh - 8
            ctx.setStrokeColor(CTL_LINE); ctx.setLineWidth(1.0); ctx.strokeRect(CGRectMake(L, top, R - L, bot - top))
            ctx.drawText(b.third, CGPointMake(4.0, top), f, CTL_INK2)
            ctx.drawText(fxF("%.0f", b.second), CGPointMake(4.0, top + 13), f, CTL_INK3)
            ctx.drawText(fxF("%.0f", b.first), CGPointMake(4.0, bot - 12), f, CTL_INK3)
            fun y(v: Double) = bot - (bot - top) * ((v - b.first) / (b.second - b.first)).coerceIn(0.0, 1.0)
            fun line(key: String, c: UIColor, w: Double = 2.0) {
                ctx.setStrokeColor(c); ctx.setLineWidth(w); ctx.beginPath(); var on = false
                for (r in rows) { val v = fxD(r[key]); val t = fxD(r["t"]) ?: continue
                    if (v == null) { on = false; continue }
                    if (!on) ctx.moveToPoint(x(t), y(v)) else ctx.addLineToPoint(x(t), y(v)); on = true }
                ctx.strokePath()
            }
            when (i) {
                0 -> { line("hr", CTL_HR); line("art_s", CTL_BP); line("art_d", CTL_BP, 1.2); line("art_m", CTL_BP.colorWithAlphaComponent(0.55), 1.0)
                       ctx.setFillColor(CTL_NIBP)
                       for (r in rows) { if (r["nibp_new"] != true) continue; val t = fxD(r["t"]) ?: continue
                           for (k in listOf("nibp_s", "nibp_d")) fxD(r[k])?.let { ctx.fillEllipseInRect(CGRectMake(x(t) - 3, y(it) - 3, 6.0, 6.0)) } } }
                1 -> line("spo2", CTL_SPO2)
                else -> { line("rr", CTL_INK); line("etco2", CTL_CO2) }
            }
        }
        ctx.drawText(fxMMSS(t0.toInt()), CGPointMake(L, H - 16), f, CTL_INK3)
        ctx.drawText(fxMMSS(t1.toInt()), CGRectMake(R - 60, H - 16, 60.0, 14.0), f, CTL_INK3, NSTextAlignmentRight)
        ctx.drawText("FC verde · ART rossa (S/D, PAM chiara) · NIBP punti rosa · SpO₂ azzurra · FR bianca · EtCO₂ gialla",
                     CGRectMake(L, H - 16, R - L, 14.0), f, CTL_INK3, NSTextAlignmentCenter)
    }
}

/** ANDROID (funzioni mancanti 22/11): righe del trend come tabella (testo per colonne, la più recente in fondo) */
fun CtlTrendTableText(rows: List<Map<String, Any?>>): List<String> {
    fun n(v: Any?): String = when (v) { null -> "—"; is Double -> if (v == Math.floor(v)) v.toLong().toString() else fxF("%.1f", v); else -> v.toString() }
    fun pa(r: Map<String, Any?>, p: String): String = if (r["${p}_s"] == null) "—" else "${n(r["${p}_s"])}/${n(r["${p}_d"])} (${n(r["${p}_m"])})"
    return rows.map { r -> listOf(fxMMSS(fxI(r["t"], 0)), n(r["hr"]), n(r["spo2"]), pa(r, "art"), if (r["nibp_new"] == true) pa(r, "nibp") else (if (r["nibp_s"] != null) "(${n(r["nibp_s"])}/${n(r["nibp_d"])})" else "—"),
        n(r["rr"]), n(r["etco2"]), n(r["temp"]), n(r["cvp"])).joinToString("\t") }
}
/** ANDROID (funzioni mancanti 22/11, IN-06): riassunto degli allarmi della sessione (piano di debriefing, PDF) */
fun CtlAlarmSummaryText(al: Map<String, Any?>): String {
    val p = ArrayList<String>()
    p.add("${fxI(al["high"], 0)} alti · ${fxI(al["medium"], 0)} medi · ${fxI(al["technical"], 0)} tecnici")
    val ms = al["mean_silence_s"]
    p.add(if (fxI(al["silenced"], 0) > 0) "silenziati ${fxI(al["silenced"], 0)} volte, in media dopo ${ms ?: "—"} s (max ${al["max_silence_s"] ?: "—"} s)" else "mai silenziati")
    if (fxI(al["unanswered"], 0) > 0) p.add("${fxI(al["unanswered"], 0)} durati oltre 30 s senza risposta")
    if (fxI(al["limits_changed"], 0) > 0) p.add("limiti cambiati ${fxI(al["limits_changed"], 0)} volte")
    if (fxI(al["alarms_disabled"], 0) > 0) p.add("${fxI(al["alarms_disabled"], 0)} allarmi spenti")
    if (fxI(al["tech_resolved"], 0) > 0) p.add("${fxI(al["tech_resolved"], 0)} tecnici risolti col gesto")
    if (al["first_high_at_s"] is Number) p.add("primo allarme alto a " + fxMMSS(fxI(al["first_high_at_s"], 0)))
    return p.joinToString(" · ")
}
val CtlTrendCols = listOf("Tempo", "FC", "SpO₂", "ART S/D (PAM)", "NIBP", "FR", "EtCO₂", "T °C", "PVC")

class CtlFxPanel(frame: CGRect, private val m: CtlModel) : UIView(frame) {
    var observing: () -> Boolean = { false }
    var flash: (String) -> Unit = { _ -> }
    var obsWho: () -> String = { "" }
    private lateinit var subL: UILabel
    private val PW = 820.0; private val PH = 676.0
    private val card: UIView
    private val tabs = ArrayList<CtlButton>()
    private var tab = 0
    private lateinit var body: CtlScrollView
    private lateinit var msg: UILabel
    private val updaters = ArrayList<() -> Unit>()
    private val touchedAt = HashMap<String, Double>()
    private val local = HashMap<String, Any?>()
    private var downOutside = false
    // trend
    private var trendStep = 60; private var trendAt = -100.0
    private var graph: CtlTrendGraph? = null; private val colLabels = ArrayList<UILabel>(); private var tableScroll: CtlScrollView? = null
    private var trendRows: List<Map<String, Any?>> = emptyList()
    // esami (valori locali da registrare)
    private val labVals = LinkedHashMap<String, Double>(); private val labSent = HashSet<String>()

    init {
        backgroundColor = UIColor.colorWithWhite(0, 0.45)
        card = CtlCard(CGRectMake(Math.max(8.0, (frame.width - PW) / 2), Math.floor((frame.height - PH) / 2), PW, PH))
        card.layer.borderWidth = 1.5; card.layer.borderColor = CTL_TINT.colorWithAlphaComponent(0.55).CGColor
        addSubview(card)
        card.addSubview(CtlLabel(CGRectMake(20, 14, PW - 180, 28), "Funzioni avanzate", 22.0, true, CTL_INK))
        subL = CtlLabel(CGRectMake(20, 42, PW - 180, 18), "", 12.5, false, CTL_INK2); subL.adjustsFontSizeToFitWidth = true; subL.minimumScaleFactor = 0.8; card.addSubview(subL)
        val close = CtlButton.buttonWithTitle("Chiudi", CtlButtonQuiet, null, CGRectMake(PW - 20 - 120, 16, 120, 40)) { close() }
        close.accessibilityIdentifier = "fx.close"; card.addSubview(close)
        val names = listOf("Trend", "Linee e ventilazione", "Allarmi", "Distrattori ed esami")
        val tw = (PW - 40 - 3 * 8) / 4
        for ((i, t) in names.withIndex()) {
            val b = CtlButton.buttonWithTitle(t, CtlButtonQuiet, null, CGRectMake(20 + i * (tw + 8), 68, tw, 44)) { select(i) }
            b.titleLabel.font = UIFont.boldSystemFontOfSize(14); b.maxLines = 1; b.accessibilityIdentifier = "fx.tab.$i"; tabs.add(b); card.addSubview(b)
        }
        msg = CtlLabel(CGRectMake(20, PH - 30, PW - 40, 20), "", 12.5, false, CTL_INK2); msg.adjustsFontSizeToFitWidth = true; msg.minimumScaleFactor = 0.75; card.addSubview(msg)
        body = CtlScrollView(CGRectMake(20, 122, PW - 40, PH - 122 - 36)); body.indicatorStyle = UIScrollViewIndicatorStyleWhite; card.addSubview(body)
    }

    // MARK: apertura
    val isOpen: Boolean get() = !hidden && superview != null
    fun open(host: UIView, t: Int = tab) {
        if (superview !== host) { removeFromSuperview(); host.addSubview(this) }
        hidden = false; host.bringSubviewToFront(this); select(t)
    }
    fun close() { hidden = true }
    /** verifica dal Mac: fxpanel?tab=N&y=Y scorre la scheda */
    fun scrollTo(y: Double) { body.setContentOffset(CGPointMake(0, Math.max(0.0, Math.min(y, body.contentSize.height - body.bounds.height))), false) }
    override fun touchesBegan(p: CGPoint) { downOutside = !CGRectContainsPoint(card.frame, p) }
    override fun touchesEnded(p: CGPoint) { if (downOutside && !CGRectContainsPoint(card.frame, p)) close(); downOutside = false }
    override fun touchesCancelled(p: CGPoint) { downOutside = false }
    fun setMsg(t: String?, c: UIColor) { if (!hidden) { msg.text = t ?: ""; msg.textColor = c } }

    private fun st(): Map<String, Any?> = m.state ?: emptyMap()
    private fun send(name: String, value: Any?, ok: String? = null): Boolean {
        if (observing()) { m.onBlocked?.invoke(); return false }
        m.command(name, value) { good, e -> msg.text = if (good) "✓ " + (ok ?: "Eseguito dal monitor") else "✕ Non eseguito: " + (e ?: "il monitor non risponde"); msg.textColor = if (good) CTL_GREEN else CTL_RED }
        return true
    }
    private fun justTouched(k: String) = CACurrentMediaTime() - (touchedAt[k] ?: -100.0) < 2.5
    private fun touch(k: String, v: Any?) { touchedAt[k] = CACurrentMediaTime(); local[k] = v }

    fun select(i: Int) {
        tab = i
        for ((k, b) in tabs.withIndex()) b.setStyle(if (k == i) CtlButtonFilled else CtlButtonQuiet, CTL_TINT)
        for (v in body.subviews.toList()) v.removeFromSuperview()
        updaters.clear(); graph = null; colLabels.clear(); tableScroll = null
        val W = body.bounds.width
        val v = UIView(CGRectMake(0, 0, W, 10)); body.addSubview(v)
        val h = when (i) { 0 -> buildTrend(v); 1 -> buildLines(v); 2 -> buildAlarms(v); else -> buildDistractors(v) }
        v.frame = CGRectMake(0, 0, W, h + 8); body.contentSize = CGSizeMake(W, h + 8); body.setContentOffset(CGPointMake(0, 0), false)
        refresh()
    }
    fun refresh() {
        if (hidden) return
        val o = observing()
        val stx = if (o) "Solo osservazione: comanda ${obsWho()}. Qui consulti; per regolare tocca «Prendi il comando»." else "Tutte facoltative: di serie il monitor si comporta come prima"
        if (subL.text != stx) { subL.text = stx; subL.textColor = if (o) CTL_AMBER else CTL_INK2 }
        for (u in updaters) u()
        if (tab == 0 && CACurrentMediaTime() - trendAt > 5) loadTrend()
    }

    // MARK: righe
    private fun header(v: UIView, y: Double, t: String): Double { v.addSubview(CtlLabel(CGRectMake(0, y + 6, v.bounds.width, 16), t, 11.0, true, CTL_INK2)); return y + 26 }
    private fun note(v: UIView, y: Double, t: String, color: UIColor = CTL_INK3): Double {
        val l = CtlLabel(CGRectMake(0, y, v.bounds.width, 32), t, 12.0, false, color); l.numberOfLines = 2; v.addSubview(l); return y + 34
    }
    private fun live(v: UIView, y: Double, text: () -> String): Double {
        val l = CtlLabel(CGRectMake(0, y, v.bounds.width, 22), "", 13.0, true, CTL_INK); l.adjustsFontSizeToFitWidth = true; l.minimumScaleFactor = 0.75; v.addSubview(l)
        updaters.add { val t = text(); if (l.text != t) l.text = t }
        return y + 26
    }
    /** scelta fra tasti: get = indice adesso (-1 nessuno), set manda il comando */
    private fun choice(v: UIView, y: Double, title: String?, key: String, items: List<String>, get: () -> Int, set: (Int) -> Boolean, color: UIColor = CTL_TINT): Double {
        var yy = y; val W = v.bounds.width
        var x0 = 0.0
        if (title != null) { val t = CtlLabel(CGRectMake(0, yy, 190, 44), title, 14.0, false, CTL_INK); t.numberOfLines = 2; t.adjustsFontSizeToFitWidth = true; t.minimumScaleFactor = 0.75; v.addSubview(t); x0 = 198.0 }
        val bw = (W - x0 - (items.size - 1) * 6) / items.size
        val bs = ArrayList<CtlButton>()
        for ((i, s) in items.withIndex()) {
            val b = CtlButton.buttonWithTitle(s, CtlButtonQuiet, null, CGRectMake(x0 + i * (bw + 6), yy, bw, 44), null)
            b.titleLabel.font = UIFont.boldSystemFontOfSize(13); b.maxLines = 2; b.accessibilityIdentifier = "fx.$key.$i"
            b.onTap = { if (set(i)) { touch(key, i); updaters.forEach { it() } } }
            v.addSubview(b); bs.add(b)
        }
        updaters.add {
            val cur = if (justTouched(key)) (local[key] as? Int ?: get()) else get()
            for ((i, b) in bs.withIndex()) { val on = i == cur; b.setStyle(if (on) CtlButtonFilled else CtlButtonQuiet, if (i == 0) CTL_TINT else color)   // la scelta di serie (prima) è neutra
                val t = (if (on) "● " else "") + items[i]; if (b.titleForState(UIControlStateNormal) != t) b.setTitle(t, UIControlStateNormal) }
        }
        return yy + 52
    }
    /** numero con − e +; off = valore che vale «spento» (sotto il minimo), show = testo del valore */
    private fun stepper(v: UIView, y: Double, title: String, key: String, step: Int, min: Int, max: Int, get: () -> Int?, set: (Int) -> Boolean,
                        show: (Int) -> String, off: Int? = null, offStart: Int = min): Double {
        val W = v.bounds.width
        val r = UIView(CGRectMake(0, y, W, 50)); r.backgroundColor = CTL_CARD2; r.layer.cornerRadius = 10.0; v.addSubview(r)
        val t = CtlLabel(CGRectMake(14, 0, W - 14 - 300, 50), title, 14.0, false, CTL_INK); t.adjustsFontSizeToFitWidth = true; t.minimumScaleFactor = 0.7; r.addSubview(t)
        val vl = CtlLabel(CGRectMake(W - 238, 0, 176, 50), "--", 17.0, true, CTL_INK); vl.textAlignment = NSTextAlignmentCenter; vl.adjustsFontSizeToFitWidth = true; vl.minimumScaleFactor = 0.6; r.addSubview(vl)
        for (dir in listOf(-1, 1)) {
            val b = CtlButton.buttonWithTitle(if (dir < 0) "−" else "+", CtlButtonQuiet, null, CGRectMake(if (dir < 0) W - 290 else W - 54, 6, 48, 38), null)
            b.backgroundColor = CTL_CARD; b.titleLabel.font = UIFont.systemFontOfSize(24); b.accessibilityIdentifier = "fx.$key.${if (dir < 0) "meno" else "piu"}"
            b.onTap = {
                val cur = (if (justTouched(key)) local[key] as? Int else null) ?: get() ?: offStart
                var n = if (off != null && cur == off) (if (dir > 0) offStart else off) else cur + dir * step
                if (off != null && cur != off && n < min) n = off
                if (off == null || n != off) n = Math.max(min, Math.min(max, n))
                if (n != cur && set(n)) { touch(key, n); vl.text = show(n) }
            }
            r.addSubview(b)
        }
        updaters.add { if (!justTouched(key)) { val n = get(); val s = if (n == null) "--" else show(n); if (vl.text != s) vl.text = s } }
        return y + 56
    }
    private fun action(v: UIView, x: Double, y: Double, w: Double, title: String, id: String, color: UIColor = CTL_TINT, go: () -> Unit): CtlButton {
        val b = CtlButton.buttonWithTitle(title, CtlButtonTinted, color, CGRectMake(x, y, w, 46), null)
        b.titleLabel.font = UIFont.boldSystemFontOfSize(14); b.maxLines = 2; b.accessibilityIdentifier = "fx.$id"; b.onTap = { go() }; v.addSubview(b); return b
    }
    private fun lim(k: String): Int? = (fxM(st()["alarm_limits"])[k] as? Number)?.toInt()

    // MARK: Trend
    private fun buildTrend(v: UIView): Double {
        var y = 0.0
        y = choice(v, y, "Passo della tabella e del grafico", "trend.step", listOf("1 min", "5 min", "15 min"), { listOf(60, 300, 900).indexOf(trendStep) },
                   { i -> trendStep = listOf(60, 300, 900)[i]; trendAt = -100.0; loadTrend(); true })
        val g = CtlTrendGraph(CGRectMake(0, y, v.bounds.width, 250)); v.addSubview(g); graph = g; y += 258
        val W = v.bounds.width; val ws = listOf(0.09, 0.07, 0.07, 0.19, 0.18, 0.07, 0.08, 0.08, 0.07).map { it * W / 1.0 }
        var x = 0.0
        for ((i, c) in CtlTrendCols.withIndex()) { v.addSubview(CtlLabel(CGRectMake(x, y, ws[i], 16), c.uppercase(Locale.ITALY), 10.5, true, CTL_INK2)); x += ws[i] }
        y += 18
        val ts = CtlScrollView(CGRectMake(0, y, W, 156)); ts.backgroundColor = CTL_CARD2; ts.layer.cornerRadius = 8.0; v.addSubview(ts); tableScroll = ts
        x = 0.0
        for (i in CtlTrendCols.indices) { val l = CtlLabel(CGRectMake(x + 4, 4, ws[i] - 4, 10), "", 13.0, i == 0, CTL_INK); l.font = CtlDigits(13.0, i == 0); l.numberOfLines = 0; ts.addSubview(l); colLabels.add(l); x += ws[i] }
        y += 162
        y = note(v, y, "Numeri come li mostra il monitor ai partecipanti (— = «---» o sensore non collegato); NIBP tra parentesi = ultima misura, senza parentesi = misura nuova. Si aggiorna da solo.")
        showTrend()
        return y
    }
    private fun loadTrend() {
        trendAt = CACurrentMediaTime()
        m.api("GET", "/api/v1/trend?since=0&step=$trendStep", null) { status, b ->
            if (status != 200 || b == null) { if (tab == 0) { msg.text = "Trend non disponibile: il monitor non risponde o va aggiornato"; msg.textColor = CTL_INK3 }; return@api }
            @Suppress("UNCHECKED_CAST")
            trendRows = (b["samples"] as? List<Map<String, Any?>>) ?: emptyList()
            if (trendStep == 60) m.trendCache = trendRows
            showTrend()
        }
    }
    private fun showTrend() {
        graph?.rows = trendRows
        val lines = CtlTrendTableText(trendRows)
        for ((i, l) in colLabels.withIndex()) {
            val t = lines.joinToString("\n") { it.split("\t").getOrElse(i) { "" } }
            if (l.text != t) { l.text = t; l.sizeToFit(); l.frame = CGRectMake(l.frame.x, 4, l.frame.width, Math.max(10.0, l.frame.height)) }
        }
        val ts = tableScroll ?: return
        val h = (colLabels.maxOfOrNull { it.frame.height } ?: 10.0) + 8
        ts.contentSize = CGSizeMake(ts.bounds.width, h)
        if (!ts.isDragging) ts.setContentOffset(CGPointMake(0, Math.max(0.0, h - ts.bounds.height)), false)
    }

    // MARK: Linee e ventilazione
    private fun buildLines(v: UIView): Double {
        var y = 0.0
        val al = { fxM(st()["art_line"]) }
        y = header(v, y, "LINEA ARTERIOSA (si collega dalla tessera ART delle sonde)")
        y = live(v, y) {
            val a = al(); val s = st()
            if (fxB(fxM(s["sensors"])["art"]) != true) "Linea arteriosa non collegata"
            else if (fxB(a["zeroing"])) "Azzeramento in corso…"
            else if (fxB(a["needs_zero"])) "Da azzerare: il monitor mostra «---» finché non si preme Zero"
            else "Collegata" + (if (a["zeroed_at"] != null) " · azzerata alle ${a["zeroed_at"]}" else "") + (if (fxD(a["offset_mmhg"]) ?: 0.0 != 0.0) fxF(" · errore di livello %+.0f mmHg", fxD(a["offset_mmhg"])) else "")
        }
        y = choice(v, y, "Smorzamento", "art.damp", listOf("Ottimale", "Sovrasmorzata (bolla, coagulo)", "Sottosmorzata (tubi lunghi)"),
                   { listOf("optimal", "over", "under").indexOf(al()["damping"] ?: "optimal") }, { i -> send("set_art_line", mapOf("damping" to listOf("optimal", "over", "under")[i])) }, CTL_ORANGE)
        y = stepper(v, y, "Livello del trasduttore rispetto all'asse flebostatico", "art.level", 5, -30, 30, { fxI(al()["level_cm"], 0) },
                    { n -> send("set_art_line", mapOf("level_cm" to n)) }, { n -> if (n == 0) "all'asse" else fxF("%+d cm (%+.0f mmHg)", n, -0.74 * n) })
        y = choice(v, y, "Linea occlusa (non pulsatile)", "art.occl", listOf("No", "Sì"), { if (fxB(al()["occluded"])) 1 else 0 }, { i -> send("set_art_line", mapOf("occluded" to (i == 1))) }, CTL_ORANGE)
        y = choice(v, y, "Zero obbligatorio dopo il collegamento", "art.zreq", listOf("No (di serie)", "Sì, come i monitor"), { if (fxB(al()["zero_required"])) 1 else 0 }, { i -> send("set_art_line", mapOf("zero_required" to (i == 1))) })
        val bw = (v.bounds.width - 8) / 2
        action(v, 0.0, y, bw, "Zero (lo fanno i partecipanti)", "art.zero") { send("art_zero", null, "Azzeramento avviato sul monitor (3 s)") }
        action(v, bw + 8, y, bw, "Prova di lavaggio rapido", "art.flush") { send("art_flush", null, "Lavaggio: onda quadra sul monitor") }
        y += 54
        y = header(v, y, "PRESSIONE VENOSA CENTRALE (PVC)")
        y = choice(v, y, "Trasduttore PVC", "cvp.on", listOf("Non collegato", "Collegato"), { if (fxB(fxM(st()["sensors"])["cvp"])) 1 else 0 },
                   { i -> send("set_sensor", mapOf("sensor" to "cvp", "connected" to (i == 1))) }, CTL_GREEN)
        y = live(v, y) { val s = st(); val d = fxM(s["monitor_numerics"])["cvp_mmhg"]; "PVC impostata ${fxI(s["cvp_mmhg"], 6)} mmHg · sul monitor " + (d?.toString() ?: "---") }
        val presets = listOf(Triple("Ipovolemia", 2, "normal"), Triple("Normale", 6, "normal"), Triple("Insuff. destra", 16, "large_v"), Triple("Tamponamento", 18, "tamponade"))
        val pw = (v.bounds.width - 3 * 6) / 4
        for ((i, p) in presets.withIndex()) action(v, i * (pw + 6), y, pw, "${p.first} · ${p.second}", "cvp.preset.$i", CTL_TINT) { send("set_cvp", mapOf("mmhg" to p.second, "waveform" to p.third), "PVC ${p.second} mmHg (${p.first.lowercase(Locale.ITALY)})") }
        y += 54
        y = stepper(v, y, "PVC", "cvp.v", 1, 0, 40, { fxI(st()["cvp_mmhg"], 6) }, { n -> send("set_cvp", n) }, { n -> "$n mmHg" })
        y = choice(v, y, "Forma dell'onda", "cvp.wave", listOf("Normale (a, c, v)", "Onde v grandi", "Tamponamento (y smussata)"),
                   { listOf("normal", "large_v", "tamponade").indexOf(st()["cvp_waveform"] ?: "normal") }, { i -> send("set_cvp_waveform", listOf("normal", "large_v", "tamponade")[i]) })
        y = header(v, y, "TEMPERATURA CENTRALE (T2)")
        y = choice(v, y, "Sonda esofagea/vescicale", "t2.on", listOf("Non collegata", "Collegata"), { if (fxB(fxM(st()["sensors"])["temp2"])) 1 else 0 },
                   { i -> send("set_sensor", mapOf("sensor" to "temp2", "connected" to (i == 1))) }, CTL_GREEN)
        y = stepper(v, y, "T2 (centrale)", "t2.v", 1, 300, 420, { fxD(st()["temperature2_c"])?.let { Math.round(it * 10).toInt() } },
                    { n -> send("set_temperature2", n / 10.0) }, { n -> fxF("%.1f °C", n / 10.0) })
        y = header(v, y, "VENTILAZIONE")
        val ve = { fxM(st()["ventilation"]) }
        y = live(v, y) { val w = ve(); val ppv = fxM(st()["monitor_numerics"])["ppv_percent"]
            when (w["mode"]) { "ventilator" -> if (fxB(w["disconnected"])) "Ventilatore SCOLLEGATO: il monitor dà APNEA dopo il ritardo" else "Ventilatore ${fxI(w["rate_rpm"], 12)}/min, ${fxI(w["tidal_ml"], 450)} ml" + (if (ppv != null) " · PPV $ppv %" else "")
                "bag" -> "Pallone: «Ventila» sempre attivo, FR = insufflazioni" + (if (fxB(w["advanced_airway"])) " · via aerea avanzata (in RCP 10/min da sole)" else "")
                else -> "Respiro spontaneo (come prima)" } }
        y = choice(v, y, "Modo", "vent.mode", listOf("Respiro spontaneo", "Ventilatore", "Pallone"), { listOf("spontaneous", "ventilator", "bag").indexOf(ve()["mode"] ?: "spontaneous") },
                   { i -> send("set_ventilation", mapOf("mode" to listOf("spontaneous", "ventilator", "bag")[i])) })
        y = stepper(v, y, "Frequenza del ventilatore", "vent.rate", 1, 4, 40, { fxI(ve()["rate_rpm"], 12) }, { n -> send("set_ventilation", mapOf("rate_rpm" to n)) }, { n -> "$n/min" })
        y = stepper(v, y, "Volume corrente", "vent.tidal", 50, 200, 1000, { fxI(ve()["tidal_ml"], 450) }, { n -> send("set_ventilation", mapOf("tidal_ml" to n)) }, { n -> "$n ml" })
        y = stepper(v, y, "Frequenza in RCP (ERC 10/min)", "vent.arrest", 1, 6, 30, { fxI(ve()["arrest_rate_rpm"], 10) }, { n -> send("set_ventilation", mapOf("arrest_rate_rpm" to n)) },
                    { n -> if (n == 0) "lascia quella impostata" else "$n/min" }, off = 0, offStart = 10)
        y = choice(v, y, "Ventilatore scollegato (guasto)", "vent.disc", listOf("Collegato", "Scollegato"), { if (fxB(ve()["disconnected"])) 1 else 0 },
                   { i -> if (ve()["mode"] != "ventilator") { msg.text = "ⓘ Prima scegli «Ventilatore»"; msg.textColor = CTL_YELLOW; false } else send("set_ventilation", mapOf("disconnected" to (i == 1))) }, CTL_ORANGE)
        y = choice(v, y, "Via aerea avanzata (pallone)", "vent.aw", listOf("No", "Sì"), { if (fxB(ve()["advanced_airway"])) 1 else 0 }, { i -> send("set_ventilation", mapOf("advanced_airway" to (i == 1))) })
        y = choice(v, y, "PPV (variazione respiratoria della pressione)", "vent.ppv", listOf("Automatica", "5 %", "10 %", "15 %", "20 %"),
                   { if (ve()["ppv_mode"] != "instructor") 0 else listOf(5, 10, 15, 20).indexOf(fxI(ve()["ppv_percent"], 0)) + 1 },
                   { i -> send("set_ppv", if (i == 0) "auto" else listOf(5, 10, 15, 20)[i - 1]) })
        y = note(v, y, "Automatica: cresce con l'ipovolemia (FC alta, PA bassa). Si vede solo col ventilatore e la linea arteriosa.")
        return y
    }

    // MARK: Allarmi
    private fun buildAlarms(v: UIView): Double {
        var y = 0.0
        y = header(v, y, "PROFILI RAPIDI")
        val bw = (v.bounds.width - 2 * 8) / 3
        action(v, 0.0, y, bw, "Di serie", "lim.prof.0") { send("reset_alarm_limits", null, "Limiti di serie") }
        action(v, bw + 8, y, bw, "ICU shock (PAM < 65)", "lim.prof.1", CTL_ORANGE) { send("set_alarm_limits", mapOf("art_map_low" to 65, "nibp_map_low" to 65), "Profilo ICU shock: PAM < 65") }
        action(v, 2 * (bw + 8), y, bw, "Post-ROSC", "lim.prof.2", CTL_ORANGE) { send("set_alarm_limits", mapOf("spo2_low" to 94, "spo2_high" to 98, "art_map_low" to 65, "nibp_map_low" to 65, "temp_low" to 360, "temp_high" to 375), "Profilo post-ROSC: SpO₂ 94–98, PAM ≥ 65, T 36–37,5") }
        y += 54
        y = note(v, y, "Post-ROSC (ERC-ESICM 2021): SpO₂ 94–98 %, PAM ≥ 65, temperatura 36–37,5 °C. Le modifiche finiscono nel registro.")
        y = header(v, y, "LIMITI SEPARATI (0 = spento)")
        val rows = listOf(listOf("art_sys_high", "ART sistolica alta", 5, 50, 300, false), listOf("art_sys_low", "ART sistolica bassa", 5, 30, 250, false),
            listOf("nibp_sys_high", "NIBP sistolica alta", 5, 50, 300, false), listOf("nibp_sys_low", "NIBP sistolica bassa", 5, 30, 250, false),
            listOf("art_map_low", "ART PAM bassa", 5, 30, 150, true), listOf("art_map_high", "ART PAM alta", 5, 40, 200, true),
            listOf("nibp_map_low", "NIBP PAM bassa", 5, 30, 150, true), listOf("nibp_map_high", "NIBP PAM alta", 5, 40, 200, true),
            listOf("spo2_high", "SpO₂ alta (iperossia)", 1, 80, 100, true), listOf("temp_high", "Temperatura alta", 1, 340, 420, true), listOf("temp_low", "Temperatura bassa", 1, 300, 380, true))
        for (r in rows) {
            val k = r[0] as String; val offOk = r[5] as Boolean
            val start = mapOf("art_map_low" to 65, "nibp_map_low" to 65, "art_map_high" to 110, "nibp_map_high" to 110, "spo2_high" to 98, "temp_high" to 380, "temp_low" to 360)[k] ?: (r[3] as Int)
            y = stepper(v, y, r[1] as String, "lim.$k", r[2] as Int, r[3] as Int, r[4] as Int, { lim(k) }, { n -> send("set_alarm_limits", mapOf(k to n)) },
                        { n -> if (n == 0 && offOk) "spento" else if (k.startsWith("temp")) fxF("%.1f °C", n / 10.0) else if (k.startsWith("spo2")) "$n %" else "$n mmHg" },
                        off = if (offOk) 0 else null, offStart = start)
        }
        y = header(v, y, "RITARDI D'ALLARME (secondi: la condizione deve durare tanto)")
        val dl = { fxM(st()["alarm_delays"]) }
        for (d in listOf("apnea" to "Apnea", "spo2" to "SpO₂", "hr" to "FC", "rr" to "FR", "etco2" to "EtCO₂", "art" to "ART", "temp" to "Temperatura")) {
            y = stepper(v, y, d.second, "dl.${d.first}", if (d.first == "apnea") 5 else 1, 0, 60, { (dl()[d.first] as? Number)?.toInt() },
                        { n -> send("set_alarm_delays", mapOf(d.first to n)) }, { n -> if (n == 0) "subito" else "$n s" })
        }
        y = note(v, y, "Ritardo 0 = allarme immediato: utile per far vedere quanti allarmi in più suonano. FV, TV e asistolia sono sempre immediati.")
        y = header(v, y, "DURANTE L'RCP")
        y = note(v, y, "Con l'RCP in corso i monitor veri continuano a suonare per FR, EtCO₂ e SpO₂ con i limiti dell'adulto: è normale, non è un errore.", CTL_INK2)
        action(v, 0.0, y, v.bounds.width, "Silenzia gli allarmi 2 min", "mute120", CTL_ORANGE) { send("mute_alarms", 120, "Allarmi silenziati 2 min") }
        y += 54
        return y
    }

    // MARK: Distrattori ed esami
    private fun buildDistractors(v: UIView): Double {
        var y = 0.0
        y = header(v, y, "ELETTRODI ECG")
        y = choice(v, y, "Un elettrodo staccato", "el.off", listOf("Tutti attaccati", "RA", "LA", "LL", "V"), { listOf(null, "ra", "la", "ll", "v").indexOf(st()["ecg_electrode_off"]) },
                   { i -> send("set_ecg_electrode", listOf("none", "ra", "la", "ll", "v")[i]) }, CTL_ORANGE)
        y = choice(v, y, "Tracciato disturbato (gel secco, 50 Hz)", "el.noise", listOf("No", "Disturbo", "Forte"), { fxI(st()["ecg_noise"], 0) }, { i -> send("set_ecg_noise", i) }, CTL_ORANGE)
        y = choice(v, y, "Cavi delle braccia invertiti", "el.rev", listOf("No", "Sì (DI negativa)"), { if (fxB(st()["limb_reversal"])) 1 else 0 }, { i -> send("set_limb_reversal", i == 1) }, CTL_ORANGE)
        y = header(v, y, "ALTRI DISTRATTORI")
        val techOn: (String) -> Boolean = { k -> ((st()["technical_alarms"] as? List<*>) ?: emptyList<Any?>()).any { (it as? Map<*, *>)?.get("kind") == k || it == k } }
        y = choice(v, y, "NIBP: tempo di misura superato", "nibp.to", listOf("No", "Sì"), { if (techOn("nibp_timeout")) 1 else 0 },
                   { i -> send("set_technical_alarm", mapOf("kind" to "nibp_timeout", "on" to (i == 1))) }, CTL_ORANGE)
        y = choice(v, y, "Contatto delle piastre", "pads.c", listOf("Buono", "Non ottimale (scarica con impedenza alta)", "Scarso (shock non erogato)"),
                   { val pc = st()["pads_contact"]; if (pc is String) listOf("good", "marginal", "poor").indexOf(pc) else if (fxB(fxM(st()["defib"])["pads_poor_contact"])) 2 else (local["pads.c.sent"] as? Int ?: 0) },
                   { i -> local["pads.c.sent"] = i; send("set_pads_contact", listOf("good", "marginal", "poor")[i]) }, CTL_ORANGE)
        y = choice(v, y, "I partecipanti risolvono i tecnici col gesto", "tech.res", listOf("No (di serie)", "Sì, tocco lungo sulla traccia"), { if (fxB(st()["tech_resolvable"])) 1 else 0 },
                   { i -> send("set_tech_resolvable", i == 1) })
        y = note(v, y, "Con «Sì» un tocco lungo sulla traccia del sensore spegne il distrattore; il registro scrive il gesto e dopo quanto è arrivato.")
        y = header(v, y, "ESAMI E BILANCIO (nel registro, non sul monitor)")
        y = live(v, y) { val l = fxM(st()["labs"]); if (l.isEmpty()) "Nessun esame registrato" else "Ultimi: " + l.entries.joinToString(" · ") { "${labName(it.key)} ${labFmt(it.key, fxD(it.value) ?: 0.0)}" } }
        val labs = listOf(Triple("ph", 7.40, 0.01), Triple("pao2_mmhg", 90.0, 5.0), Triple("paco2_mmhg", 40.0, 1.0), Triple("hco3_mmol", 24.0, 0.5), Triple("be_mmol", 0.0, 0.5),
                          Triple("lactate_mmol", 1.0, 0.5), Triple("glucose_mgdl", 100.0, 10.0), Triple("potassium_mmol", 4.0, 0.1), Triple("hb_gdl", 13.0, 0.5),
                          Triple("urine_ml_h", 60.0, 5.0), Triple("balance_ml", 0.0, 100.0))
        val lims = mapOf("ph" to (6.8 to 7.8), "pao2_mmhg" to (20.0 to 600.0), "paco2_mmhg" to (10.0 to 150.0), "hco3_mmol" to (2.0 to 50.0), "be_mmol" to (-30.0 to 30.0),
                         "lactate_mmol" to (0.2 to 25.0), "glucose_mgdl" to (10.0 to 900.0), "potassium_mmol" to (1.0 to 10.0), "hb_gdl" to (2.0 to 22.0), "urine_ml_h" to (0.0 to 1000.0), "balance_ml" to (-20000.0 to 20000.0))
        for ((k, def, stp) in labs) {
            if (!labVals.containsKey(k)) labVals[k] = fxD(fxM(st()["labs"])[k]) ?: def
            val W = v.bounds.width
            val r = UIView(CGRectMake(0, y, W, 46)); r.backgroundColor = CTL_CARD2; r.layer.cornerRadius = 10.0; v.addSubview(r)
            val chk = CtlLabel(CGRectMake(14, 0, 30, 46), "", 16.0, true, CTL_GREEN); r.addSubview(chk)
            r.addSubview(CtlLabel(CGRectMake(40, 0, W - 330, 46), labName(k), 14.0, false, CTL_INK))
            val vl = CtlLabel(CGRectMake(W - 234, 0, 172, 46), labFmt(k, labVals[k]!!), 16.0, true, CTL_INK); vl.textAlignment = NSTextAlignmentCenter; r.addSubview(vl)
            for (dir in listOf(-1, 1)) {
                val b = CtlButton.buttonWithTitle(if (dir < 0) "−" else "+", CtlButtonQuiet, null, CGRectMake(if (dir < 0) W - 286 else W - 54, 4, 48, 38), null)
                b.backgroundColor = CTL_CARD; b.titleLabel.font = UIFont.systemFontOfSize(24); b.accessibilityIdentifier = "fx.lab.$k.${if (dir < 0) "meno" else "piu"}"
                b.onTap = { val (lo, hi) = lims[k]!!; val n = Math.round((labVals[k]!! + dir * stp).coerceIn(lo, hi) * 100) / 100.0
                            labVals[k] = n; labSent.add(k); vl.text = labFmt(k, n); chk.text = "●" }
                r.addSubview(b)
            }
            y += 52
        }
        action(v, 0.0, y, v.bounds.width, "Registra gli esami toccati", "lab.send", CTL_GREEN) {
            if (labSent.isEmpty()) { msg.text = "ⓘ Tocca − o + sui valori da registrare (●)"; msg.textColor = CTL_YELLOW; return@action }
            val body = LinkedHashMap<String, Any?>(); for (k in labSent) body[k] = labVals[k]
            if (send("set_labs", body, "Esami nel registro")) { labSent.clear(); select(3) }
        }
        y += 54
        return y
    }
    private fun labName(k: String) = mapOf("ph" to "pH", "pao2_mmhg" to "PaO₂", "paco2_mmhg" to "PaCO₂", "hco3_mmol" to "HCO₃⁻", "be_mmol" to "BE", "lactate_mmol" to "Lattati",
        "glucose_mgdl" to "Glicemia", "potassium_mmol" to "K⁺", "hb_gdl" to "Hb", "urine_ml_h" to "Diuresi", "balance_ml" to "Bilancio")[k] ?: k
    private fun labFmt(k: String, v: Double): String = when (k) {
        "ph" -> fxF("%.2f", v); "pao2_mmhg", "paco2_mmhg" -> fxF("%.0f mmHg", v); "glucose_mgdl" -> fxF("%.0f mg/dl", v); "urine_ml_h" -> fxF("%.0f ml/h", v)
        "balance_ml" -> fxF("%+.0f ml", v); "hb_gdl" -> fxF("%.1f g/dl", v); else -> fxF("%.1f mmol/l", v) }
}
