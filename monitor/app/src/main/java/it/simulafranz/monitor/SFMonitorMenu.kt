// ANDROID: nessun file corrispondente sull'iPad. Menu dell'istruttore sul monitor (richiesta dell'utente 21/11/2026):
// al posto del riquadro "Controller senza rete" (che copriva i dispositivi), UN SOLO tasto ☰ nello stesso punto in tutti
// i layout: in alto nella fascia laterale SINISTRA, sopra le prese delle sonde e allineato al loro asse (scelta dell'utente),
// FUORI dall'apparecchio (i partecipanti vedono il dispositivo pulito).
// Il pallino sul tasto dice se un controller è collegato (verde) o no (grigio).
// Il tasto apre un menu con sottomenu: Controller ▸, Layout ▸, Volumi, Reti Wi-Fi e collegamenti, Azzera lo scenario.
// Quando un controller si collega o si scollega compare per 3 s un avviso discreto in alto.
package it.simulafranz.monitor

import it.simulafranz.uk.*
import java.util.Locale

private val MM_BG = UIColor.colorWithRed(0.075, 0.085, 0.10, 0.97)
private val MM_LINE = UIColor.colorWithWhite(1, 0.10)
private val MM_TEXT = UIColor.colorWithWhite(0.94, 1)
private val MM_SUB = UIColor.colorWithWhite(0.62, 1)
private val MM_GREEN = UIColor.colorWithRed(0.30, 0.88, 0.42)
private val MM_RED = UIColor.colorWithRed(1.0, 0.45, 0.40)

/** Il tasto ☰ con il pallino di stato. */
class SFMenuButton(frame: CGRect) : UIButton(frame) {
    var connected = false; set(v) { if (field != v) { field = v; invalidate() } }
    /** ANDROID (ICU 21/11): disegno più piccolo (nella barra superiore dell'ICU); l'area di tocco resta il frame intero (≥ 44) */
    var compact = false; set(v) { if (field != v) { field = v; invalidate() } }
    init { accessibilityIdentifier = "MONITOR-MENU" }
    override fun stateChanged() { super.stateChanged(); invalidate() }
    override fun drawRect(ctx: CGContext, rect: CGRect) {
        val d = minOf(rect.width, rect.height) - (if (compact) 12 else 4)
        val c = CGRectMake(rect.midX - d / 2, rect.midY - d / 2, d, d)
        ctx.setFillColor(UIColor.colorWithWhite(0.05, if (highlighted) 0.85 else 0.62)); ctx.fillEllipseInRect(c)
        ctx.setStrokeColor(UIColor.colorWithWhite(1, 0.28)); ctx.setLineWidth(1.0); ctx.strokeEllipseInRect(c.inset(0.5, 0.5))
        // tre righe
        ctx.setStrokeColor(UIColor.colorWithWhite(0.92, 1)); ctx.setLineWidth(2.2); ctx.setLineCap(kCGLineCapRound)
        val w = d * 0.40; val x0 = rect.midX - w / 2
        for (i in -1..1) { val y = rect.midY + i * d * 0.15; ctx.beginPath(); ctx.moveToPoint(x0, y); ctx.addLineToPoint(x0 + w, y); ctx.strokePath() }
        // pallino di stato del controller
        val r = if (compact) maxOf(4.5, d * 0.14) else d * 0.13        // nel disegno piccolo il pallino resta ben visibile
        val dot = CGRectMake(c.maxX - r * 2.1, c.minY + r * 0.1, r * 2, r * 2)
        ctx.setFillColor(UIColor.colorWithWhite(0.05, 1)); ctx.fillEllipseInRect(dot.inset(-1.5, -1.5))
        ctx.setFillColor(if (connected) MM_GREEN else UIColor.colorWithWhite(0.45, 1)); ctx.fillEllipseInRect(dot)
    }
}

/** Il pannello del menu (si apre accanto al tasto, allineato al bordo sinistro dello schermo). */
class SFMonitorMenuPanel(private val host: UIView, private val m: MonitorViewController, startAtLayouts: Boolean = false) : UIView(host.bounds) {
    private val panel: UIView
    private val pw = 380.0
    init {
        backgroundColor = UIColor.colorWithWhite(0, 0.35)
        val bg = UIButton(bounds); bg.addTarget(UIControlEventTouchUpInside) { close() }; addSubview(bg)
        panel = UIView(CGRectMake(10, 10, pw, 100))
        panel.backgroundColor = MM_BG; panel.layer.cornerRadius = 14.0
        panel.layer.borderWidth = 1.0; panel.layer.borderColor = UIColor.colorWithWhite(1, 0.16)
        addSubview(panel)
        uiTag = SF_MENU_TAG
        if (startAtLayouts) showLayouts() else showRoot()
    }
    fun close() { removeFromSuperview() }

    private fun clear() { for (v in panel.subviews) v.removeFromSuperview() }
    private fun fit(h: Double) { panel.frame = CGRectMake(panel.frame.x, panel.frame.y, pw, h) }
    private fun header(title: String, back: Boolean, onBack: () -> Unit = { showRoot() }): Double {
        if (back) {
            val b = UIButton(CGRectMake(8, 8, 110, 40)); b.setTitle("‹ Indietro"); b.titleLabel.font = UIFont.systemFontOfSize(16)
            b.setTitleColor(UIColor.colorWithRed(0.40, 0.70, 1.0)); b.contentHorizontalAlignment = 1
            b.addTarget(UIControlEventTouchUpInside) { onBack() }; panel.addSubview(b)
        }
        val t = UILabel(CGRectMake(if (back) 120.0 else 18.0, 12, pw - (if (back) 180.0 else 80.0), 30)); t.text = title
        t.textColor = MM_TEXT; t.font = UIFont.boldSystemFontOfSize(19); panel.addSubview(t)
        val x = UIButton(CGRectMake(pw - 52, 8, 44, 40)); x.setTitle("✕"); x.titleLabel.font = UIFont.systemFontOfSize(20)
        x.setTitleColor(MM_SUB); x.addTarget(UIControlEventTouchUpInside) { close() }; panel.addSubview(x)
        return 56.0
    }
    /** riga del menu: titolo, sottotitolo, freccia se apre un sottomenu */
    private fun row(y: Double, title: String, sub: String?, color: UIColor = MM_TEXT, arrow: Boolean = true, id: String? = null, tap: () -> Unit): Double {
        val h = if (sub != null) 62.0 else 50.0
        panel.addSubview(UIView(CGRectMake(16, y, pw - 32, 1)).also { it.backgroundColor = MM_LINE })
        val b = UIButton(CGRectMake(0, y + 1, pw, h - 1)); b.accessibilityIdentifier = id
        b.addTarget(UIControlEventTouchDown or UIControlEventTouchDragEnter) { b.backgroundColor = UIColor.colorWithWhite(1, 0.06) }
        b.addTarget(UIControlEventTouchUpOutside or UIControlEventTouchCancel or UIControlEventTouchDragExit) { b.backgroundColor = null }
        b.addTarget(UIControlEventTouchUpInside) { b.backgroundColor = null; tap() }
        val t = UILabel(CGRectMake(18, if (sub != null) 9.0 else 12.0, pw - 70, 24)); t.text = title; t.textColor = color
        t.font = UIFont.boldSystemFontOfSize(17); t.userInteractionEnabled = false; b.addSubview(t)
        if (sub != null) { val s = UILabel(CGRectMake(18, 34, pw - 70, 20)); s.text = sub; s.textColor = MM_SUB; s.font = UIFont.systemFontOfSize(14); s.userInteractionEnabled = false; b.addSubview(s) }
        if (arrow) { val a = UILabel(CGRectMake(pw - 40, 0, 24, h - 1)); a.text = "›"; a.textColor = MM_SUB; a.font = UIFont.systemFontOfSize(26); a.userInteractionEnabled = false; b.addSubview(a) }
        panel.addSubview(b)
        return y + h
    }

    // ANDROID (guscio 25/11): volume di sistema del tablet dal menu, perché i tasti fisici sono dietro lo sportellino del guscio
    private fun tabletVolumeRow(y: Double): Double {
        if (SFTabletVolume.level() < 0) return y
        val h = 62.0
        panel.addSubview(UIView(CGRectMake(16, y, pw - 32, 1)).also { it.backgroundColor = MM_LINE })
        val t = UILabel(CGRectMake(18, y + 10, pw - 170, 24)); t.text = "Volume del tablet"; t.textColor = MM_TEXT; t.font = UIFont.boldSystemFontOfSize(17); panel.addSubview(t)
        val s = UILabel(CGRectMake(18, y + 35, pw - 170, 20)); s.textColor = MM_SUB; s.font = UIFont.systemFontOfSize(14); s.accessibilityIdentifier = "MENU-TABVOL"; panel.addSubview(s)
        fun show() { val v = SFTabletVolume.level(); s.text = if (v <= 0) "muto" else "▮".repeat(v) + "▯".repeat(SFTabletVolume.max() - v) }
        fun key(x: Double, title: String, id: String, d: Int) {
            val b = UIButton(CGRectMake(x, y + 9, 60, 44)); b.setTitle(title); b.titleLabel.font = UIFont.boldSystemFontOfSize(26)
            b.setTitleColor(MM_TEXT); b.backgroundColor = UIColor.colorWithWhite(1, 0.10); b.layer.cornerRadius = 8.0; b.accessibilityIdentifier = id
            b.addTarget(UIControlEventTouchUpInside) { SFTabletVolume.setLevel(SFTabletVolume.level() + d); show(); try { m.sound.txProgress() } catch (e: Exception) {} }
            panel.addSubview(b)
        }
        key(pw - 146, "−", "MENU-TABVOL-GIU", -1); key(pw - 78, "+", "MENU-TABVOL-SU", 1)
        show()
        // resta allineato se intanto il volume cambia dal controller (o dai tasti fisici): si rilegge finché la riga è a schermo
        fun again() { if (s.superview != null && superview != null) { show(); Uk.after(0.4) { again() } } }
        Uk.after(0.4) { again() }
        return y + h
    }

    private fun layoutName(id: String?): String =
        it.simulafranz.core.SimulationState.layoutCatalog().firstOrNull { it["id"] == id }?.get("name") ?: "Monitor generico"

    fun showRoot() {
        clear()
        var y = header("similFranz Monitor", false)
        // ANDROID (menu ☰ 21/11): nome e versione in testa al menu (prima erano una targhetta sopra i layout)
        val vl = UILabel(CGRectMake(18, 40, pw - 80, 18)); vl.text = "versione " + SFVersionShown(); vl.textColor = MM_SUB; vl.font = UIFont.systemFontOfSize(13); panel.addSubview(vl)
        y += 10
        val peers = m.menuPeers()
        y = row(y, "Controller", if (peers.isEmpty()) "nessun controller collegato" else "collegato: " + peers.joinToString(", "), id = "MENU-CONTROLLER") { showController() }
        y = row(y, "Layout", layoutName(m.layoutShown), id = "MENU-LAYOUT") { showLayouts() }
        y = row(y, "Volumi", "allarmi, battiti, voce", id = "MENU-VOLUMI") { close(); m.showVolumes() }
        y = tabletVolumeRow(y)
        if (SFBlocca.pronto(Uk.ctx)) y = row(y, "Spegni lo schermo", "si riaccende con il tasto Home", arrow = false, id = "MENU-SPEGNI-SCHERMO") { close(); SFBlocca.spegni(Uk.ctx) }
        y = row(y, "Informazioni e collegamenti", "regia web, PIN, rete del tablet, versione", id = "MENU-RETI") { close(); m.showInfo() }      // ANDROID (audit 21/11): apre sempre (toggleInfo lo chiudeva se era già aperto)
        y = row(y, "Azzera lo scenario e spegni", null, MM_RED, false, "MENU-RESET") { close(); m.confirmResetScenario() }
        val v = UILabel(CGRectMake(18, y + 8, pw - 36, 18))
        v.text = String.format(Locale.US, "%s · codice %s · %s", SFIdentity.monitorName(), SFIdentity.code(), SFVersionShown())
        v.textColor = MM_SUB; v.font = UIFont.systemFontOfSize(12); v.numberOfLines = 1; v.adjustsFontSizeToFitWidth = true; v.minimumScaleFactor = 0.7
        panel.addSubview(v)
        fit(y + 36)
    }

    private fun showController() {
        clear()
        var y = header("Controller", true)
        val peers = m.menuPeers()
        val st = UILabel(CGRectMake(18, y + 4, pw - 36, 26))
        st.text = if (peers.isEmpty()) "Nessun controller collegato" else "Collegato: " + peers.joinToString(", ")
        st.textColor = if (peers.isEmpty()) MM_SUB else MM_GREEN; st.font = UIFont.boldSystemFontOfSize(17)
        st.numberOfLines = 1; st.adjustsFontSizeToFitWidth = true; st.minimumScaleFactor = 0.6
        panel.addSubview(st); y += 38
        fun info(label: String, value: String, big: Boolean) {
            val l = UILabel(CGRectMake(18, y, 150, 22)); l.text = label; l.textColor = MM_SUB; l.font = UIFont.systemFontOfSize(14); panel.addSubview(l)
            val v = UILabel(CGRectMake(150, y - (if (big) 6.0 else 0.0), pw - 168, if (big) 34.0 else 22.0)); v.text = value; v.textColor = MM_TEXT
            v.font = if (big) UIFont.fontWithName("Menlo-Bold", 26) else UIFont.boldSystemFontOfSize(15)
            v.numberOfLines = 1; v.adjustsFontSizeToFitWidth = true; v.minimumScaleFactor = 0.6; panel.addSubview(v)
            y += if (big) 40.0 else 30.0
        }
        info("Nome del monitor", SFIdentity.monitorName(), false)
        info("Codice", SFIdentity.code(), false)
        info("PIN", m.menuPin(), true)
        val hint = UILabel(CGRectMake(18, y, pw - 36, 58))
        hint.text = "Sul controller tocca questo monitor nell'elenco: qui comparirà «Associa». Il PIN serve solo se lo chiede."
        hint.textColor = MM_SUB; hint.font = UIFont.systemFontOfSize(13); hint.numberOfLines = 3; panel.addSubview(hint)
        y += 64
        y = row(y, "Rinomina, PIN e reti…", "pannello completo dei collegamenti", id = "MENU-PANNELLO") { close(); m.showInfo() }
        fit(y + 8)
    }

    // ANDROID (categorie dei layout 22/11): Layout ▸ apre le categorie (sotto-menu «Default» e «Personalizzati»),
    // ognuna con i suoi layout nell'ordine del catalogo del cuore (per tipo: terapia intensiva, reparto, defibrillatore, DAE)
    private fun layoutsIn(cat: String) = it.simulafranz.core.SimulationState.layoutCatalog().filter { (it["category"] ?: "default") == cat }

    private fun showLayouts() {
        clear()
        var y = header("Layout", true)
        val curId = m.layoutShown ?: "generic_icu"
        for ((cat, title) in it.simulafranz.core.SFLayoutCategories) {
            val list = layoutsIn(cat)
            val cur = list.firstOrNull { it["id"] == curId }
            val sub = when {
                cur != null -> "in uso: " + (cur["name"] ?: curId)
                list.isEmpty() -> "nessun layout"
                else -> list.joinToString(", ") { it["name"] ?: "" }
            }
            y = row(y, (if (cur != null) "✓  " else "     ") + title, sub, if (cur != null) MM_GREEN else MM_TEXT, true, "MENU-LC-" + cat) { showCategory(cat, title) }
        }
        fit(y + 10)
    }

    private fun showCategory(cat: String, title: String) {
        clear()
        var y = header(title, true) { showLayouts() }
        val list = layoutsIn(cat)
        if (list.isEmpty()) {
            val l = UILabel(CGRectMake(18, y + 6, pw - 36, 48)); l.text = it.simulafranz.core.SFLayoutCustomEmptyText
            l.textColor = MM_SUB; l.font = UIFont.systemFontOfSize(15); l.numberOfLines = 2; panel.addSubview(l)
            y += 62
        }
        for (it in list) {
            val id = it["id"]; val cur = id == (m.layoutShown ?: "generic_icu")
            y = row(y, (if (cur) "✓  " else "     ") + (it["name"] ?: id ?: ""), null, if (cur) MM_GREEN else MM_TEXT, false, "MENU-L-" + id) {
                close(); m.state.applyCommand(mapOf("command" to "set_layout", "value" to id))
            }
        }
        fit(y + 10)
    }
}

private const val SF_MENU_TAG = 7790
private const val SF_TOAST_TAG = 7791
private const val SF_CENTRALE_TAG = 7798

/** Avviso temporaneo (3 s) in alto al centro: un controller si è collegato o scollegato. */
internal fun MonitorViewController.menuToast(text: String, ok: Boolean, seconds: Double = 3.0, alert: Boolean = false) {
    val host = SFSideBand.left?.superview ?: view
    val w = minOf(maxOf(520.0, host.bounds.width - 240.0), 60.0 + UkText.size(text, UIFont.boldSystemFontOfSize(17)).width)
    // ANDROID (audit 21/11): due avvisi insieme (es. uno si scollega e un altro si collega nello stesso giro) si
    // sovrapponevano nello stesso punto e non si leggevano: ora si impilano, uno sotto l'altro
    val busy = host.subviews.count { it.uiTag == SF_TOAST_TAG }
    val t = UILabel(CGRectMake(Math.floor((host.bounds.width - w) / 2), 10.0 + 48.0 * busy, w, 40))
    t.uiTag = SF_TOAST_TAG
    t.text = text; t.textAlignment = NSTextAlignmentCenter; t.font = UIFont.boldSystemFontOfSize(17)
    t.textColor = if (ok) MM_GREEN else MM_TEXT
    t.backgroundColor = UIColor.colorWithWhite(0.06, 0.90); t.layer.cornerRadius = 20.0; t.clipsToBounds = true
    t.layer.borderWidth = 1.0; t.layer.borderColor = UIColor.colorWithWhite(1, 0.18)
    // avviso rosso (uscita dal monitor): fondo rosso pieno e testo bianco, ben visibile
    if (alert) { t.textColor = UIColor.whiteColor; t.backgroundColor = UIColor.colorWithRed(0.78, 0.10, 0.10, 0.96); t.layer.borderColor = UIColor.colorWithRed(1.0, 0.55, 0.55, 0.9); t.layer.borderWidth = 2.0 }
    t.alpha = 0f; t.userInteractionEnabled = false
    host.addSubview(t)
    UIView.animateWithDuration(0.25, { t.alpha = 1f })
    Uk.after(seconds) { UIView.animateWithDuration(0.4, { t.alpha = 0f }) { t.removeFromSuperview() } }
}

/** ANDROID (centrale 25/11): risposta della Centrale Operativa: fascia grande in alto, 15 s, lampeggia tre volte all'arrivo. */
internal fun MonitorViewController.centraleBanner(reply: String) {
    val host = SFSideBand.left?.superview ?: view
    for (v in host.subviews.filter { it.uiTag == SF_CENTRALE_TAG }) v.removeFromSuperview()
    val w = minOf(host.bounds.width - 120.0, 760.0); val h = 92.0
    val box = UIView(CGRectMake(Math.floor((host.bounds.width - w) / 2), 14.0, w, h))
    box.uiTag = SF_CENTRALE_TAG; box.userInteractionEnabled = false
    box.backgroundColor = UIColor.colorWithRed(0.04, 0.30, 0.16, 0.97); box.layer.cornerRadius = 16.0; box.clipsToBounds = true
    box.layer.borderWidth = 3.0; box.layer.borderColor = UIColor.colorWithRed(0.45, 1.0, 0.65, 1).CGColor
    val a = UILabel(CGRectMake(20, 10, w - 40, 22)); a.text = "RISPOSTA DELLA CENTRALE OPERATIVA"; a.font = UIFont.boldSystemFontOfSize(15)
    a.textColor = UIColor.colorWithRed(0.62, 1.0, 0.76, 1); a.textAlignment = NSTextAlignmentCenter; a.backgroundColor = UIColor.clearColor
    val b = UILabel(CGRectMake(20, 34, w - 40, 48)); b.text = reply; b.font = UIFont.boldSystemFontOfSize(30); b.textColor = UIColor.whiteColor
    b.textAlignment = NSTextAlignmentCenter; b.adjustsFontSizeToFitWidth = true; b.minimumScaleFactor = 0.5; b.backgroundColor = UIColor.clearColor
    box.addSubview(a); box.addSubview(b); host.addSubview(box)
    for (k in 0 until 3) { Uk.after(0.5 * k + 0.25) { box.alpha = 0.35f }; Uk.after(0.5 * k + 0.5) { box.alpha = 1f } }
    Uk.after(15.0) { UIView.animateWithDuration(0.4, { box.alpha = 0f }) { box.removeFromSuperview() } }
}

// ---- stato del collegamento, letto da linkInfo (MainActivity): controller che hanno parlato con il monitor negli ultimi secondi
internal fun MonitorViewController.menuPeers(): List<String> {
    val d = linkInfo?.invoke() ?: return emptyList()
    val p = (d["peers"] as? List<*>)?.mapNotNull { it as? String } ?: emptyList()
    if (p.isNotEmpty()) return p
    val o = d["controller"] as? Map<*, *>                 // controller su Wi-Fi (regia web, app sulla stessa rete)
    val n = o?.get("name") as? String
    return if (n != null) listOf(n) else emptyList()
}
internal fun MonitorViewController.menuPin(): String {
    val p = pinProvider?.invoke() ?: ""
    val sb = StringBuilder(); for (i in p.indices) { if (i != 0 && i % 3 == 0) sb.append(' '); sb.append(p[i]) }
    return sb.toString()
}

/** Crea il tasto ☰ (una volta sola), lo mette al posto giusto per il layout e lo tiene in cima; chiamato a ogni cambio di layout.
 *  ANDROID (ICU 21/11): il tasto sta nella RADICE (fasce + tela), sopra tutto, anche sopra la schermata «Scenario in PAUSA»
 *  (decisione dell'utente: il menu dell'istruttore resta sempre raggiungibile, con il pallino del controller visibile).
 *  Posizione per layout: nell'ICU a tutto schermo è in alto a sinistra nella barra superiore, più piccolo (SFIcuMenuFrame);
 *  negli altri layout resta in alto nella fascia sinistra, sull'asse delle prese (56×56, y 12), come prima. */
internal fun MonitorViewController.placeMenuButton() {
    val band = SFSideBand.left ?: return
    val host = band.superview ?: return
    var b = menuButton
    if (b == null) {
        b = SFMenuButton(CGRectMake(0, 12, 56, 56))
        b.addTarget(UIControlEventTouchUpInside) { openMonitorMenu() }
        menuButton = b
    }
    val icu = SFIsWideLayout(layoutShown) && view.superview?.frame?.x == 0.0 && band.bounds.width > 0
    if (icu) {
        b.compact = true
        b.frame = SFIcuMenuFrame()                                   // la tela larga parte da x 0: stesse coordinate dell'ICU
    } else {
        b.compact = false
        val s = minOf(56.0, band.bounds.width - 16)
        val cx = band.frame.x + band.bounds.width * 0.56             // stesso asse delle prese delle sonde (SFSidePanel)
        b.frame = CGRectMake(cx - s / 2, 12, s, s)
    }
    if (b.superview !== host) host.addSubview(b)
    keepMenuButtonOnTop()
    Uk.after(0.4) { keepMenuButtonOnTop() }                        // sopra le parti del layout che arrivano appena è a schermo
}
/** ☰ in cima alla radice (sopra pausa e layout), ma sotto la schermata di avvio e sotto i pannelli che si aprono dopo */
internal fun MonitorViewController.keepMenuButtonOnTop() {
    val b = menuButton ?: return
    val host = b.superview ?: return
    host.bringSubviewToFront(b)
    for (v in host.subviews.filter { it.uiTag == SF_MENU_TAG || it.uiTag == 7777 }) host.bringSubviewToFront(v)   // menu aperto, splash
}
internal fun MonitorViewController.openMonitorMenu(atLayouts: Boolean = false) {
    val host = SFSideBand.left?.superview ?: view
    closeMonitorMenu()                                          // ANDROID (audit 21/11): mai due menu uno sopra l'altro
    host.addSubview(SFMonitorMenuPanel(host, this, atLayouts))
}
internal fun MonitorViewController.closeMonitorMenu() {
    val host = SFSideBand.left?.superview ?: view
    for (v in host.subviews.filter { it.uiTag == SF_MENU_TAG }) v.removeFromSuperview()
}
/** Ogni 3 s (refreshNetwork): pallino del tasto e avviso quando un controller arriva o se ne va. */
internal fun MonitorViewController.refreshMenuStatus() {
    val now = menuPeers().toSet()
    menuButton?.connected = now.isNotEmpty()
    val before = menuLastPeers
    if (before != null) {
        for (n in now - before) menuToast("Controller collegato: $n", true)
        for (n in before - now) menuToast("Controller scollegato: $n", false)
    }
    menuLastPeers = now
    // ANDROID (centrale 25/11): la risposta della Centrale Operativa al 12 derivazioni trasmesso compare qui, sopra ogni layout
    for (r in SFEcg12Tx.newReplies()) { centraleBanner(r); sound.rxChime() }
}
