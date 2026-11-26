// ANDROID (pannello RCP 22/11) — «Come vedi l'RCP?»
// Richiesta dell'utente del 22/11/2026: il tocco su «Avvia la RCP» (tessera RIANIMAZIONE, anche nella regia DAE) fa partire
// SUBITO la RCP e apre questo pannello con tutto «corretto» (di base l'RCP è fatta bene: l'istruttore cambia solo ciò che vede
// fare male). Con la RCP in corso lo stesso tasto NON la ferma: riapre il pannello; si ferma solo con il grande «Interrompi RCP».
// Stesse scelte della web regia (BugFix.md, «Pannello RCP — web regia 22/11», PR1–PR9):
//  - a ogni avvio (anche alla ripresa dopo la pausa dello shock) la qualità torna corretta con UN solo set_cpr_quality
//    {rate_cpm 110, depth/recoil/hands ok}, mandato solo se qualcosa nello stato era scorretto;
//  - frequenza: cursore 60–160/min (100–120 in verde) inviato dopo 350 ms di quiete; «Batti il tempo» dal 5° tocco = media
//    degli ultimi 4–6 intervalli (un intervallo > 2 s ricomincia); invii di frequenza al massimo ogni 1,2 s e solo se cambia;
//  - profondità / rilascio / mani: un set_cpr_quality con la sola voce cambiata; «Cambio operatore» = cpr_rescuer_change;
//  - «Torna al modello» (EtCO₂ impostata a mano) = rimandare le qualità vere di adesso con set_cpr_quality;
//  - in sola osservazione il pannello si consulta, i controlli non mandano niente e mostrano l'avviso breve già esistente;
//  - se la RCP si ferma per altra via a pannello aperto, «Interrompi RCP» diventa «Riprendi la RCP».
// I valori mostrati vengono sempre dallo stato del monitor (un valore toccato qui si mostra per 2,5 s in attesa della conferma).
package it.simulafranz.controller

import it.simulafranz.uk.*
import java.util.Locale

/** ANDROID (pannello RCP 22/11): istante (CACurrentMediaTime) dell'ultimo dito appoggiato sullo schermo, da MainActivity */
@Volatile var CtlTouchDownAt = 0.0

private fun cpB(v: Any?): Boolean = v == true || (v is Number && v.toInt() != 0)
private fun cpI(v: Any?, d: Int): Int = (v as? Number)?.toInt() ?: d
private fun cpD(v: Any?, d: Double): Double = (v as? Number)?.toDouble() ?: d
private fun cpF(f: String, vararg a: Any?) = String.format(Locale.ITALY, f, *a)
private fun cpRateCat(cpm: Int): String = if (cpm < 100) "slow" else if (cpm > 120) "fast" else "ok"
private fun cpRateName(cpm: Int): String = when (cpRateCat(cpm)) { "slow" -> "lenta"; "fast" -> "veloce"; else -> "corretta" }

class CtlCprPanel(frame: CGRect, private val m: CtlModel) : UIView(frame) {
    /** sola osservazione (comanda un altro) e chi comanda; avviso breve della regia */
    var observing: () -> Boolean = { false }
    var obsWho: () -> String = { "" }
    var flash: (String) -> Unit = { _ -> }
    var onClose: (() -> Unit)? = null

    private val PW = 640.0; private val PH = 690.0   // ANDROID (EtCO2 visibile 24/11): +68 per la riga VENTILAZIONI
    private val card: UIView
    private lateinit var sub: UILabel; private lateinit var effect: UILabel; private lateinit var manual: UIView; private lateinit var manualText: UILabel; private lateinit var hint: UILabel
    private lateinit var rateValue: UILabel; private lateinit var slider: CtlRuler; private lateinit var tapBtn: CtlButton; private lateinit var tapSub: UILabel
    private val ventBtns = ArrayList<CtlButton>()   // ANDROID (EtCO2 visibile 24/11)
    private val VENT_MODES = listOf("auto", "30_2", "realistic", "always")
    private val depthBtns = ArrayList<CtlButton>(); private val recoilBtns = ArrayList<CtlButton>(); private val handsBtns = ArrayList<CtlButton>()
    private lateinit var fatBar: UIView; private lateinit var fatFill: UIView; private lateinit var fatText: UILabel; private lateinit var changeBtn: CtlButton
    private lateinit var msg: UILabel; private lateinit var stopBtn: CtlButton
    private var downOutside = false

    // valori toccati qui, mostrati fino alla conferma del monitor (al più 2,5 s)
    private val local = HashMap<String, Pair<Any, Double>>()
    private var localRate = 110; private var localRateAt = -100.0
    private val taps = ArrayList<Double>()
    private var sliderSeq = 0; private var rateSentAt = -100.0; private var rateSent = -1; private var ratePushWaiting = false
    private var stopAt = -100.0; private var changeAt = -100.0; private var modelAt = -100.0; private val qAt = HashMap<String, Double>()
    private var startedAt = -100.0; private var stoppedAt = -100.0
    private var styleKey = ""

    init {
        backgroundColor = UIColor.colorWithWhite(0, 0.45)
        val cx = Math.max(8.0, Math.min(frame.width - PW - 8, (frame.width - PW) / 2))
        card = CtlCard(CGRectMake(cx, Math.floor((frame.height - PH) / 2), PW, PH))
        card.layer.borderWidth = 1.5; card.layer.borderColor = CTL_ORANGE.colorWithAlphaComponent(0.55).CGColor
        addSubview(card)
        build()
    }

    // MARK: - costruzione
    private fun section(t: String, x: Double, y: Double, w: Double) { card.addSubview(CtlLabel(CGRectMake(x, y, w, 16), t, 10.5, true, CTL_INK2)) }
    private fun build() {
        val W = PW - 40; val X = 20.0
        card.addSubview(CtlLabel(CGRectMake(X, 14, W - 140, 28), "Come vedi l'RCP?", 22.0, true, CTL_INK))
        sub = CtlLabel(CGRectMake(X, 42, W - 140, 18), "Di base è corretta: cambia solo ciò che vedi fare male", 12.5, false, CTL_INK2)
        sub.adjustsFontSizeToFitWidth = true; sub.minimumScaleFactor = 0.8; card.addSubview(sub)
        val close = CtlButton.buttonWithTitle("Chiudi", CtlButtonQuiet, null, CGRectMake(PW - 20 - 120, 16, 120, 40)) { close() }
        close.accessibilityIdentifier = "cpr.close"; card.addSubview(close)

        // effetto atteso (riga in parole semplici) + EtCO2 impostata a mano
        val eb = UIView(CGRectMake(X, 68, W, 56)); eb.backgroundColor = CTL_CARD2; eb.layer.cornerRadius = 9.0; card.addSubview(eb)
        effect = CtlLabel(CGRectMake(12, 4, W - 24, 48), "", 14.0, true, CTL_INK); effect.numberOfLines = 2
        effect.adjustsFontSizeToFitWidth = true; effect.minimumScaleFactor = 0.8; eb.addSubview(effect)
        // sotto, quando l'EtCO2 non è impostata a mano: quando si vede l'effetto
        hint = CtlLabel(CGRectMake(X + 4, 130, W - 8, 34), "Stima a regime: sul monitor si vede in 20–30 s. L'EtCO₂ compare solo con le ventilazioni.", 12.0, false, CTL_INK3)
        hint.numberOfLines = 2; card.addSubview(hint)
        manual = UIView(CGRectMake(X, 128, W, 40)); manual.backgroundColor = CtlRGB(0.24, 0.17, 0.02); manual.layer.cornerRadius = 9.0
        manual.layer.borderWidth = 1.0; manual.layer.borderColor = CTL_YELLOW.colorWithAlphaComponent(0.7).CGColor; card.addSubview(manual)
        manualText = CtlLabel(CGRectMake(12, 0, W - 24 - 180, 40), "", 13.0, true, CtlRGB(1.0, 0.9, 0.6)); manualText.adjustsFontSizeToFitWidth = true; manualText.minimumScaleFactor = 0.75
        manual.addSubview(manualText)
        val back = CtlButton.buttonWithTitle("Torna al modello", CtlButtonFilled, CTL_YELLOW, CGRectMake(W - 6 - 170, 4, 170, 32)) { backToModel() }
        back.titleLabel.font = UIFont.boldSystemFontOfSize(14); back.maxLines = 1; back.setTitleColor(CtlRGB(0.10, 0.08, 0.0), UIControlStateNormal)
        back.accessibilityIdentifier = "cpr.model"; manual.addSubview(back)

        // ANDROID (EtCO2 visibile 24/11): chi ventila durante l'arresto (decide quando compare l'EtCO2)
        section("VENTILAZIONI IN ARRESTO (EtCO₂)", X, 172.0, W)
        val vw = (W - 3 * 8) / 4
        for ((i, t) in listOf("Automatiche", "30:2", "A mano", "Sempre visibile").withIndex()) {
            val b = CtlButton.buttonWithTitle(t, CtlButtonQuiet, null, CGRectMake(X + i * (vw + 8), 190.0, vw, 44.0)) { ventMode(VENT_MODES[i]) }
            b.maxLines = 1; b.accessibilityIdentifier = "cpr.vent.$i"; ventBtns.add(b); card.addSubview(b)
        }

        // frequenza: cursore a sinistra, «Batti il tempo» a destra
        val y0 = 244.0; val TW = 200.0; val SW = W - TW - 16
        section("FREQUENZA", X, y0, SW)
        rateValue = CtlLabel(CGRectMake(X, y0 + 16, SW, 30), "", 22.0, true, CTL_INK); rateValue.font = CtlDigits(22.0, true); card.addSubview(rateValue)
        // ANDROID (righello a zone 22/11): righello a zone (Lenta < 100 · Corretta 100–120 · Veloce > 120) al posto del cursore con la
        // pista verde: si trascina sotto l'indice fisso, con le tacche con nome «Lenta 80», «Corretta 110», «Veloce 140»
        slider = CtlRuler(CGRectMake(X, y0 + 46, SW, 68), CtlRulerSpec("cpr_rate", 60.0, 160.0, 1.0, 0), CTL_GREEN)
        slider.accessibilityIdentifier = "cpr.rate"
        slider.onChange = { v, final -> sliderMoved(v, final) }
        card.addSubview(slider)
        tapBtn = CtlButton.buttonWithTitle("Batti il tempo", CtlButtonTinted, CTL_TINT, CGRectMake(X + SW + 16, y0 + 2, TW, 88), null)
        tapBtn.titleLabel.font = UIFont.boldSystemFontOfSize(18); tapBtn.maxLines = 1; tapBtn.accessibilityIdentifier = "cpr.tap"
        tapBtn.layer.borderWidth = 1.5; tapBtn.layer.borderColor = CTL_TINT.colorWithAlphaComponent(0.8).CGColor
        // ANDROID: il tocco conta quando il dito si APPOGGIA (TouchDown), con l'istante vero dell'evento
        tapBtn.addTarget(UIControlEventTouchDown) { tapped() }
        card.addSubview(tapBtn)
        tapSub = CtlLabel(CGRectMake(X + SW + 16, y0 + 92, TW, 18), "a ogni compressione che vedi", 11.0, false, CTL_INK3); tapSub.textAlignment = NSTextAlignmentCenter
        tapSub.adjustsFontSizeToFitWidth = true; tapSub.minimumScaleFactor = 0.8; card.addSubview(tapSub)

        // profondità
        var y = y0 + 122
        section("PROFONDITÀ", X, y, W)
        val dw = (W - 16) / 3
        for ((i, t) in listOf("Superficiale", "Corretta", "Troppo profonda").withIndex()) {
            val b = CtlButton.buttonWithTitle(t, CtlButtonQuiet, null, CGRectMake(X + i * (dw + 8), y + 18, dw, 46)) { quality("depth", listOf("shallow", "ok", "deep")[i]) }
            b.maxLines = 1; b.accessibilityIdentifier = "cpr.depth.$i"; depthBtns.add(b); card.addSubview(b)
        }
        // rilascio e mani, affiancati
        y += 74
        val hw = (W - 16) / 2; val bw = (hw - 8) / 2
        section("RILASCIO DEL TORACE", X, y, hw); section("POSIZIONE DELLE MANI", X + hw + 16, y, hw)
        for ((i, t) in listOf("Completo", "Incompleto").withIndex()) {
            val b = CtlButton.buttonWithTitle(t, CtlButtonQuiet, null, CGRectMake(X + i * (bw + 8), y + 18, bw, 46)) { quality("recoil", listOf("ok", "incomplete")[i]) }
            b.maxLines = 1; b.accessibilityIdentifier = "cpr.recoil.$i"; recoilBtns.add(b); card.addSubview(b)
        }
        for ((i, t) in listOf("Corrette", "Scorrette").withIndex()) {
            val b = CtlButton.buttonWithTitle(t, CtlButtonQuiet, null, CGRectMake(X + hw + 16 + i * (bw + 8), y + 18, bw, 46)) { quality("hands", listOf("ok", "wrong")[i]) }
            b.maxLines = 1; b.accessibilityIdentifier = "cpr.hands.$i"; handsBtns.add(b); card.addSubview(b)
        }
        // stanchezza e cambio dell'operatore
        y += 76
        section("STANCHEZZA DI CHI COMPRIME", X, y, W - 220)
        fatBar = UIView(CGRectMake(X, y + 22, W - 220, 12)); fatBar.backgroundColor = CTL_CARD2; fatBar.layer.cornerRadius = 6.0; fatBar.clipsToBounds = true; card.addSubview(fatBar)
        fatFill = UIView(CGRectMake(0, 0, 0, 12)); fatFill.backgroundColor = CTL_GREEN; fatBar.addSubview(fatFill)
        fatText = CtlLabel(CGRectMake(X, y + 38, W - 220, 18), "", 12.5, false, CTL_INK2); card.addSubview(fatText)
        changeBtn = CtlButton.buttonWithTitle("Cambio operatore", CtlButtonQuiet, null, CGRectMake(X + W - 204, y + 8, 204, 48)) { rescuerChange() }
        changeBtn.maxLines = 1; changeBtn.accessibilityIdentifier = "cpr.change"; changeBtn.layer.borderWidth = 1.5; card.addSubview(changeBtn)
        // avviso breve (lo stesso della regia, che qui sotto è coperta) e il grande tasto rosso
        y += 62
        msg = CtlLabel(CGRectMake(X, y, W, 20), "", 13.0, false, CTL_INK2); msg.adjustsFontSizeToFitWidth = true; msg.minimumScaleFactor = 0.75; card.addSubview(msg)
        stopBtn = CtlButton.buttonWithTitle("Interrompi RCP", CtlButtonFilled, CTL_RED, CGRectMake(X, PH - 20 - 64, W, 64)) { stopOrResume() }
        stopBtn.titleLabel.font = UIFont.boldSystemFontOfSize(22); stopBtn.maxLines = 1; stopBtn.accessibilityIdentifier = "cpr.stop"; card.addSubview(stopBtn)
    }

    // MARK: - apertura e chiusura (toccare fuori chiude)
    val isOpen: Boolean get() = !hidden && superview != null
    fun open(host: UIView) {
        if (superview !== host) { removeFromSuperview(); host.addSubview(this) }
        hidden = false; host.bringSubviewToFront(this); styleKey = ""; msg.text = ""; refresh()
    }
    fun close() { if (hidden) return; hidden = true; taps.clear(); onClose?.invoke() }
    override fun touchesBegan(p: CGPoint) { downOutside = !CGRectContainsPoint(card.frame, p) }
    override fun touchesEnded(p: CGPoint) { if (downOutside && !CGRectContainsPoint(card.frame, p)) close(); downOutside = false }
    override fun touchesCancelled(p: CGPoint) { downOutside = false }

    fun setMsg(t: String?, c: UIColor) { if (!hidden) { msg.text = t ?: ""; msg.textColor = c } }

    // MARK: - avvio (lo chiama anche la regia dal tasto «Avvia la RCP»)
    private fun st(): Map<String, Any?> = m.state ?: emptyMap()
    /** RCP in corso secondo il monitor (subito dopo un nostro avvio o stop: quello che abbiamo chiesto, per 2,5 s) */
    fun cprActive(): Boolean {
        val now = CACurrentMediaTime()
        if (now - startedAt < 2.5 && startedAt > stoppedAt) return true
        if (now - stoppedAt < 2.5 && stoppedAt > startedAt) return false
        return cpB(st()["cpr_active"])
    }
    fun startCpr() {
        if (observing()) { m.onBlocked?.invoke(); return }
        val s = st()
        m.command("set_cpr", true)
        startedAt = CACurrentMediaTime()
        // di base l'RCP è fatta bene: se nello stato è rimasto qualcosa di scorretto, UN solo comando rimette tutto a posto
        val wrong = (s["cpr_rate_quality"] ?: "ok") != "ok" || (s["cpr_depth_quality"] ?: "ok") != "ok" ||
                    (s["cpr_recoil_quality"] ?: "ok") != "ok" || (s["cpr_hands_quality"] ?: "ok") != "ok"
        if (wrong) {
            m.command("set_cpr_quality", mapOf("rate_cpm" to 110, "depth" to "ok", "recoil" to "ok", "hands" to "ok"))
            val t = CACurrentMediaTime()
            local["depth"] = Pair("ok", t); local["recoil"] = Pair("ok", t); local["hands"] = Pair("ok", t); localRate = 110; localRateAt = t; rateSent = 110; rateSentAt = t
        }
        refresh()
    }
    private fun stopOrResume() {
        val now = CACurrentMediaTime()
        if (now - stopAt < 0.9) return                       // un tocco doppio = un comando solo
        stopAt = now
        if (!cprActive()) { startCpr(); flash("RCP ripresa: qualità corretta"); return }
        if (observing()) { m.onBlocked?.invoke(); return }
        m.command("set_cpr", false)
        stoppedAt = now; flash("RCP interrotta")
        stopBtn.isEnabled = false; refresh()
        // il pannello si chiude poco dopo: il secondo tocco di un doppio tocco cade ancora sul tasto spento, non sulla regia
        Uk.after(0.45) { stopBtn.isEnabled = true; close() }
    }

    // MARK: - frequenza
    private fun shownRate(): Int {
        val now = CACurrentMediaTime()
        return if (now - localRateAt < 2.5 || slider.busy) localRate else cpI(st()["cpr_rate_cpm"], 110)
    }
    private fun sliderMoved(v: Double, final: Boolean) {
        if (observing()) { if (final) { m.onBlocked?.invoke(); Uk.after(0.05) { slider.showValue(cpI(st()["cpr_rate_cpm"], 110).toDouble().coerceIn(60.0, 160.0)) } }; return }
        localRate = Math.round(v).toInt(); localRateAt = CACurrentMediaTime(); taps.clear()
        val seq = ++sliderSeq
        Uk.after(0.35) { if (seq == sliderSeq && !slider.busy) pushRate() else if (seq == sliderSeq) waitRelease(seq) }
        refreshRate()
    }
    private fun waitRelease(seq: Int) { Uk.after(0.15) { if (seq != sliderSeq) return@after; if (slider.busy) waitRelease(seq) else pushRate() } }
    private fun tapped() {
        val now = CACurrentMediaTime()
        // istante vero del dito (da MainActivity) se è di questo tocco; altrimenti l'arrivo dell'evento
        val t = if (now - CtlTouchDownAt in 0.0..0.3) CtlTouchDownAt else now
        if (observing()) { m.onBlocked?.invoke(); return }
        if (taps.isNotEmpty() && t - taps.last() > 2.0) taps.clear()     // pausa > 2 s: si ricomincia
        taps.add(t); while (taps.size > 7) taps.removeAt(0)
        if (taps.size >= 5) {
            val n = Math.min(6, taps.size - 1)
            val mean = (taps.last() - taps[taps.size - 1 - n]) / n
            if (mean > 0) {
                localRate = Math.round(60.0 / mean).toInt().coerceIn(40, 200); localRateAt = now
                pushRate()
            }
        }
        refreshRate()
    }
    /** manda la frequenza voluta: al più ogni 1,2 s e solo se diversa da quella vera (o dall'ultima mandata) */
    private fun pushRate() {
        val v = localRate
        val now = CACurrentMediaTime()
        val truth = cpI(st()["cpr_rate_cpm"], 110)
        if (v == truth && (rateSent == v || now - rateSentAt > 3)) return
        if (v == rateSent && now - rateSentAt < 3) return
        val wait = rateSentAt + 1.2 - now
        if (wait > 0) { if (!ratePushWaiting) { ratePushWaiting = true; Uk.after(wait + 0.02) { ratePushWaiting = false; pushRate() } }; return }
        m.command("set_cpr_quality", mapOf("rate_cpm" to v))
        it.simulafranz.core.SFLog.log(cpF("RCP: frequenza %d/min inviata (%s)", v, if (taps.size >= 5) "batti il tempo, intervalli " + (1 until taps.size).joinToString(",") { Math.round((taps[it] - taps[it - 1]) * 1000).toString() } + " ms" else "cursore"))
        rateSent = v; rateSentAt = now
    }

    // MARK: - qualità, cambio operatore, modello
    private fun quality(key: String, v: String) {
        val now = CACurrentMediaTime()
        if (now - (qAt[key] ?: -100.0) < 0.5) return
        qAt[key] = now
        if (observing()) { m.onBlocked?.invoke(); return }
        if (shownQ(key) == v) return
        m.command("set_cpr_quality", mapOf(key to v))
        local[key] = Pair(v, now); styleKey = ""; refresh()
    }
    private fun shownQ(key: String): String {
        val l = local[key]
        if (l != null && CACurrentMediaTime() - l.second < 2.5) return l.first as String
        return (st()["cpr_${key}_quality"] as? String) ?: "ok"
    }
    private fun rescuerChange() {
        val now = CACurrentMediaTime()
        if (now - changeAt < 1.0) return
        changeAt = now
        if (observing()) { m.onBlocked?.invoke(); return }
        m.command("cpr_rescuer_change", null)
        local["fatigue"] = Pair(0.0, now); flash("Cambio dell'operatore: la stanchezza riparte da zero"); refresh()
    }
    // ANDROID (EtCO2 visibile 24/11)
    private fun ventMode(v: String) {
        val now = CACurrentMediaTime()
        if (now - (qAt["vent"] ?: -100.0) < 0.5) return
        qAt["vent"] = now
        if (observing()) { m.onBlocked?.invoke(); return }
        if (shownVent() == v) return
        m.command("set_ventilation", mapOf("etco2_cpr_display" to v))
        local["vent"] = Pair(v, now); flash(ventHint(v)); refresh()
    }
    private fun shownVent(): String {
        val l = local["vent"]
        if (l != null && CACurrentMediaTime() - l.second < 2.5) return l.first as String
        return ((st()["ventilation"] as? Map<*, *>)?.get("etco2_cpr_display") as? String) ?: "auto"
    }
    private fun ventHint(v: String): String = when (v) {
        "30_2" -> "Il monitor mostra 2 espirazioni ogni 30 compressioni, anche con la via aerea avanzata."
        "realistic" -> "L'EtCO₂ compare solo quando premi «Ventila» (o col ventilatore/pallone automatico)."
        "always" -> "EtCO₂ sempre a schermo in arresto; un valore impostato a mano resta fisso."
        else -> "Il monitor ventila da solo con l'RCP: 30:2 con la maschera, 10/min con la via aerea avanzata."
    }

    private fun backToModel() {
        val now = CACurrentMediaTime()
        if (now - modelAt < 1.0) return
        modelAt = now
        if (observing()) { m.onBlocked?.invoke(); return }
        val s = st()
        // nel cuore è set_cpr_quality che rimette l'EtCO2 al modello: si rimandano le qualità VERE di adesso (niente cambia)
        m.command("set_cpr_quality", mapOf("rate_cpm" to cpI(s["cpr_rate_cpm"], 110), "depth" to ((s["cpr_depth_quality"] as? String) ?: "ok"),
                                           "recoil" to ((s["cpr_recoil_quality"] as? String) ?: "ok"), "hands" to ((s["cpr_hands_quality"] as? String) ?: "ok")))
        local["model"] = Pair(true, now); flash("EtCO₂: di nuovo il modello (segue la qualità della RCP)"); refresh()
    }

    // MARK: - aggiornamento (a ogni stato del monitor)
    private fun paint(b: CtlButton, on: Boolean, good: Boolean) {
        val c = if (good) CTL_GREEN else CTL_ORANGE
        b.setStyle(if (on) CtlButtonFilled else CtlButtonQuiet, c)
        b.setTitleColor(if (on) CtlRGB(0.04, 0.08, 0.04) else CTL_INK, UIControlStateNormal)
        b.layer.borderWidth = if (on) 0.0 else 1.5
        b.layer.borderColor = c.colorWithAlphaComponent(if (on) 0.8 else 0.45).CGColor
    }
    private fun refreshRate() {
        val r = shownRate()
        rateValue.text = cpF("%d/min · %s", r, cpRateName(r))
        rateValue.textColor = if (cpRateCat(r) == "ok") CTL_GREEN else CTL_ORANGE
        slider.showValue(r.toDouble().coerceIn(60.0, 160.0)); slider.setLocked(observing())
        val now = CACurrentMediaTime()
        val active = taps.isNotEmpty() && now - taps.last() <= 2.0
        tapSub.text = if (!active) "tocca a ogni compressione che vedi" else if (taps.size < 5) cpF("ancora %d tocchi…", 5 - taps.size) else cpF("%d/min dai tuoi tocchi", localRate)
        tapSub.textColor = if (active) CTL_TINT else CTL_INK3
        if (active) Uk.after(2.1) { if (!hidden) refreshRate() }
    }
    fun refresh() {
        if (hidden) return
        val s = st()
        val obs = observing()
        sub.text = if (obs) cpF("Solo osservazione: comanda %s · i comandi da qui non partono", obsWho()) else "Di base è corretta: cambia solo ciò che vedi fare male"
        sub.textColor = if (obs) CTL_ORANGE else CTL_INK2
        refreshRate()
        val d = shownQ("depth"); val rc = shownQ("recoil"); val h = shownQ("hands")
        for ((i, b) in depthBtns.withIndex()) paint(b, listOf("shallow", "ok", "deep")[i] == d, i == 1)
        val vm = shownVent()
        for ((i, b) in ventBtns.withIndex()) paint(b, VENT_MODES[i] == vm, i == 0)
        hint.text = "Stima a regime (sul monitor in 20–30 s). " + ventHint(vm)
        for ((i, b) in recoilBtns.withIndex()) paint(b, listOf("ok", "incomplete")[i] == rc, i == 0)
        for ((i, b) in handsBtns.withIndex()) paint(b, listOf("ok", "wrong")[i] == h, i == 0)
        // stanchezza (cpr_fatigue 0..1, piena a 2 min di compressioni): al 100 % «Cambio consigliato»
        val lf = local["fatigue"]
        val fat = if (lf != null && CACurrentMediaTime() - lf.second < 2.5) 0.0 else cpD(s["cpr_fatigue"], 0.0).coerceIn(0.0, 1.0)
        fatFill.frame = CGRectMake(0, 0, fatBar.bounds.width * fat, 12)
        fatFill.backgroundColor = if (fat > 0.8) CTL_ORANGE else if (fat > 0.5) CTL_YELLOW else CTL_GREEN
        val adv = fat >= 0.99
        fatText.text = if (adv) cpF("%d%% · Cambio consigliato (ogni 2 minuti)", Math.round(fat * 100)) else cpF("%d%% · si cambia ogni 2 minuti", Math.round(fat * 100))
        fatText.textColor = if (adv) CTL_ORANGE else CTL_INK2; fatText.font = if (adv) UIFont.boldSystemFontOfSize(12.5) else UIFont.systemFontOfSize(12.5)
        changeBtn.setStyle(if (adv) CtlButtonFilled else CtlButtonQuiet, CTL_ORANGE)
        changeBtn.setTitleColor(if (adv) CtlRGB(0.10, 0.06, 0.0) else CTL_INK, UIControlStateNormal)
        changeBtn.layer.borderWidth = if (adv) 0.0 else 1.5; changeBtn.layer.borderColor = CTL_ORANGE.colorWithAlphaComponent(0.6).CGColor
        // effetto atteso e EtCO2 a mano
        val active = cprActive()
        effect.text = expected(s, active, shownRate(), d, rc, h, fat)
        val lm = local["model"]
        val manualOn = s["etco2_cpr_mode"] == "instructor" && !(lm != null && CACurrentMediaTime() - lm.second < 2.5)
        manual.hidden = !manualOn; hint.hidden = manualOn
        if (manualOn) manualText.text = if (vm == "always") cpF("EtCO₂ impostata a mano (%d mmHg): resta fissa", cpI(s["etco2_mmhg"], 0))
            else cpF("EtCO₂ a mano (%d mmHg): col massaggio buono sale un po', fatto male scende", cpI(s["etco2_mmhg"], 0))
        // il grande tasto in fondo: rosso «Interrompi RCP»; se la RCP si è fermata per altra via, verde «Riprendi la RCP»
        val key = if (active) "stop" else "go"
        if (key != styleKey) {
            styleKey = key
            if (active) { stopBtn.setStyle(CtlButtonFilled, CTL_RED); stopBtn.setTitleColor(UIColor.whiteColor, UIControlStateNormal); stopBtn.setTitle("Interrompi RCP", UIControlStateNormal) }
            else { stopBtn.setStyle(CtlButtonFilled, CTL_GREEN); stopBtn.setTitleColor(CtlRGB(0.04, 0.08, 0.04), UIControlStateNormal); stopBtn.setTitle("Riprendi la RCP", UIControlStateNormal) }
        }
    }

    // Riga di effetto atteso: stessi fattori del modello del cuore (SimulationState.co2AutoTarget / artCprPeak / artCprRelax),
    // con la stanchezza vera ma senza adrenalina e iperventilazione: è una stima a regime (si vede in ~20–30 s).
    private fun expected(s: Map<String, Any?>, active: Boolean, rate: Int, d: String, rc: String, h: String, fat: Double): String {
        if (cpB(s["pulse_present"])) return "Il paziente ha polso: le compressioni non cambiano EtCO₂ e pressione (la RCP serve in arresto)."
        if (!active) return "RCP ferma: ogni ventilazione abbassa l'EtCO₂ (~10% ad atto), l'arteriosa resta piatta (~15 mmHg)."
        fun rf(lo: Double, hi: Double, min: Double): Double { val r = rate.toDouble(); val f = if (r < 100) 1.0 - lo * (100 - r) else if (r > 120) 1.0 - hi * (r - 120) else 1.0; return Math.max(min, f) }
        val ff = 1.0 - 0.20 * fat
        val co2 = Math.max(3.0, 19.0 * (when (d) { "shallow" -> 0.55; "deep" -> 0.92; else -> 1.0 }) * rf(0.0233, 0.009, 0.30) *
                  (if (h == "wrong") 0.75 else 1.0) * (if (rc == "incomplete") 0.85 else 1.0) * ff)
        val peak = 80.0 * (when (d) { "shallow" -> 0.56; "deep" -> 1.15; else -> 1.0 }) * rf(0.004, 0.0075, 0.55) *
                   (if (h == "wrong") 0.78 else 1.0) * (if (rc == "incomplete") 0.88 else 1.0) * ff
        var dia = 28.0 * (when (d) { "shallow" -> 0.60; "deep" -> 1.04; else -> 1.0 }) * rf(0.0187, 0.005, 0.40) * (if (h == "wrong") 0.82 else 1.0) * ff
        if (rc == "incomplete") dia += 4.0
        dia = Math.max(17.0, dia)
        val c = Math.round(co2); val pk = Math.round(peak); val di = Math.round(dia)
        val auto = s["auto_physiology"] != false
        val co2t = if (!auto) cpF("EtCO₂ come impostata (%d)", cpI(s["etco2_mmhg"], 0)) else if (s["etco2_cpr_mode"] == "instructor") cpF("EtCO₂ a mano (%d)", cpI(s["etco2_mmhg"], 0)) else cpF("EtCO₂ ~%d", c)
        val good = co2 >= 15 && dia >= 25
        val tail = if (dia < 25) " · diastolica sotto 25" else ""
        return cpF("%s: %s, arteriosa ~%d/%d%s.", if (good) "RCP efficace" else "RCP poco efficace", co2t, pk, di, tail)
    }
}
