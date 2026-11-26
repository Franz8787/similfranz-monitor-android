// Portato da src/CtlDebriefView.m (controller iPad)
// similFranz Controller — "Piano di debriefing": tutto lo scenario com'è andato, dal registro tenuto sul monitor.
// In alto il riepilogo (durata, tempi delle azioni critiche dall'arresto, scariche, farmaci, punti segnati),
// sotto la cronologia completa con i filtri per categoria.
package it.simulafranz.controller

import it.simulafranz.core.*
import it.simulafranz.uk.*
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// etichetta breve e colore di una categoria del registro (usati anche nella colonna Registro)
fun CtlLogKindName(kind: String?): String {
    return mapOf("phase" to "SCENARIO", "patient" to "PAZIENTE", "monitor" to "MONITOR", "electric" to "ELETTRICA", "timer" to "TIMER", "action" to "AZIONE",
        "drug" to "FARMACO", "plus" to "PUNTO DI FORZA", "minus" to "DA DISCUTERE", "note" to "NOTA", "alarm" to "ALLARME")[kind ?: ""] ?: "NOTA"
}
fun CtlLogKindColor(kind: String?): UIColor {
    return mapOf("phase" to CTL_TINT, "patient" to CTL_INK2, "monitor" to CTL_SPO2, "electric" to CTL_ORANGE, "timer" to CTL_INK3, "action" to CTL_GREEN,
        "drug" to CTL_TEMP, "plus" to CTL_GREEN, "minus" to CTL_RED, "note" to CTL_YELLOW, "alarm" to CTL_RED)[kind ?: ""] ?: CTL_INK2
}
// ANDROID (funzioni 21/11): colori delle metriche RCP secondo ERC. Pausa (pre o post scarica): verde ≤ 5 s, giallo ≤ 10 s,
// rosso oltre (pre-shock < 5 s ideale; −18 % di sopravvivenza ogni 5 s). Frazione di compressione: verde ≥ 80 %, giallo ≥ 60 %, rosso sotto.
fun CtlPauseColor(s: Double): UIColor = if (s <= 5) CTL_GREEN else (if (s <= 10) CTL_YELLOW else CTL_RED)
fun CtlCcfColor(p: Double): UIColor = if (p >= 80) CTL_GREEN else (if (p >= 60) CTL_YELLOW else CTL_RED)
fun CtlWorst(a: UIColor, b: UIColor): UIColor = if (a === CTL_RED || b === CTL_RED) CTL_RED else (if (a === CTL_YELLOW || b === CTL_YELLOW) CTL_YELLOW else CTL_GREEN)
private val wallFmt: SimpleDateFormat by lazy { SimpleDateFormat("HH:mm:ss", Locale.US) }
private fun WallText(epoch: Double?): String = if (epoch == null || epoch <= 0) "" else synchronized(wallFmt) { wallFmt.format(Date((epoch * 1000).toLong())) }

// ANDROID (guasti 22/11): voce del registro scritta dal cuore per un malfunzionamento simulato
internal fun IsFaultEntry(d: Map<String, Any?>): Boolean {
    val t = (d["text"] as? String ?: "").lowercase(Locale.ITALIAN)
    return t.startsWith("malfunzionamento simulato") || t.contains("(malfunzionamento simulato)") || t.contains("batteria esaurita")
}
/** ANDROID (guasti 22/11): avviso discreto quando l'orologio di questo tablet è palesemente sbagliato (usato anche dall'associazione) */
fun CtlClockHint(m: CtlModel): String = if (!m.controllerClockWrong) "" else
    String.format(Locale.US, "L'ora di questo tablet sembra sbagliata (anno %d): imposta data e ora del tablet (Impostazioni › Gestione generale › Data e ora)",
                  java.util.Calendar.getInstance().get(java.util.Calendar.YEAR))

private fun MMSS(s: Int): String = String.format(Locale.US, "%02d:%02d", s / 60, s % 60)
private fun Span(v: Any?): String {          // durata leggibile: "12 s", "1 min 05 s"
    if (v !is Number) return "—"
    val s = v.toInt()
    return if (s < 60) String.format(Locale.US, "%d s", s) else String.format(Locale.US, "%d min %02d s", s / 60, s % 60)
}
// ANDROID: [x integerValue] e "%@" di un NSNumber (i numeri JSON interi arrivano come Long, i decimali come Double)
private fun debInt(v: Any?): Int = when (v) { is Number -> v.toInt(); is String -> v.trim().toIntOrNull() ?: 0; is Boolean -> if (v) 1 else 0; else -> 0 }
private fun debObj(v: Any?): String = when (v) {
    null -> "(null)"
    is Double -> if (v == Math.floor(v) && !v.isInfinite()) v.toLong().toString() else v.toString()
    is Boolean -> if (v) "1" else "0"
    else -> v.toString()
}

private class CtlDebriefCell(st: Int, rid: String?) : UITableViewCell(st, rid) {
    val time: UILabel; val kind: UILabel; val body: UILabel
    // ANDROID (funzioni 21/11): sotto il timer di scenario l'orario (hh:mm:ss); a destra le pause pre/post delle righe RCP
    val wall: UILabel; val pre: UILabel; val post: UILabel
    init {
        backgroundColor = UIColor.clearColor; selectionStyle = UITableViewCellSelectionStyleNone
        // ANDROID (funzioni 21/11): in uk la riga è un UIControl e trattiene il dito: la cronologia non scorreva. Le righe non
        // si toccano (sola lettura): il tocco va all'elenco, che scorre.
        userInteractionEnabled = false
        separatorInset = UIEdgeInsets(); layoutMargins = UIEdgeInsets(); preservesSuperviewLayoutMargins = false
        time = CtlLabel(CGRectMake(16, 3, 66, 22), "", 17.0, true, CTL_INK); time.font = CtlDigits(17.0, true); contentView.addSubview(time)
        wall = CtlLabel(CGRectMake(16, 25, 66, 16), "", 11.0, false, CTL_INK3); wall.font = CtlDigits(11.0, false); contentView.addSubview(wall)
        val bx = CTL_W - 32 - 24 - 2 * 112 - 8
        pre = CtlLabel(CGRectMake(bx, 9, 112, 28), "", 14.0, true, CTL_INK); pre.textAlignment = NSTextAlignmentCenter; pre.layer.cornerRadius = 6.0; pre.layer.borderWidth = 1.5; contentView.addSubview(pre)
        post = CtlLabel(CGRectMake(bx + 120, 9, 112, 28), "", 14.0, true, CTL_INK); post.textAlignment = NSTextAlignmentCenter; post.layer.cornerRadius = 6.0; post.layer.borderWidth = 1.5; contentView.addSubview(post)
        kind = CtlLabel(CGRectMake(88, 12, 128, 20), "", 10.5, true, CTL_INK); kind.textAlignment = NSTextAlignmentCenter; kind.layer.cornerRadius = 5.0; kind.layer.borderWidth = 1.0; contentView.addSubview(kind)
        body = CtlLabel(CGRectMake(230, 0, CTL_W - 32 - 230 - 24, 44), "", 16.0, false, CTL_INK); body.numberOfLines = 2; contentView.addSubview(body)   // ANDROID (BugFixController 3): testo fino al bordo della tabella
    }
}

private val sessLineFormatter: SimpleDateFormat by lazy { SimpleDateFormat("d MMM HH:mm", Locale("it", "IT")) }
private fun SessLine(m: Map<String, Any?>, off: Double = 0.0): String {      // ANDROID (funzioni 21/11): off = scarto dell'orologio del monitor
    var t = m["title"] as? String ?: ""; if (t.length > 26) t = t.substring(0, 25) + "…"
    return String.format(Locale.US, "%s · %s · %s", sessLineFormatter.format(Date(((((m["w"] as? Number)?.toDouble() ?: 0.0) + off) * 1000).toLong())), t, MMSS(debInt(m["duration_s"])))
}

// ANDROID (storia sim 22/11): archive = record di una simulazione salvata (Storia Sim): piano in sola lettura, dal record
class CtlDebriefView(val model: CtlModel, val archive: Map<String, Any?>? = null) : UIViewController(), UITableViewDataSource, UITableViewDelegate {
    private val m get() = model
    private lateinit var table: UITableView; private lateinit var tiles: UIView; private lateinit var filter: UISegmentedControl
    private lateinit var empty: UILabel; private lateinit var err: UILabel; private lateinit var clockHint: UILabel     // ANDROID (guasti 22/11)
    private var rows: List<Map<String, Any?>> = emptyList(); private lateinit var clear: CtlButton; private lateinit var sessBtn: CtlButton
    private var clearArmed = 0.0; private var shown = 0
    var startAll = false                        // ANDROID (funzioni 21/11): aperto da FINE SCENARIO: filtro «Tutto»
    // ANDROID (storia sim 22/11): aperto da FINE SCENARIO: «Chiudi» chiede se salvare in Storia Sim; dopo la scelta (salva /
    // non salvare) la regia azzera lo scenario (onEndChoice). onDeleted: simulazione eliminata dall'archivio.
    var fromEnd = false
    var onEndChoice: ((Boolean) -> Unit)? = null
    var onDeleted: (() -> Unit)? = null
    private val st get() = model.storia
    // ANDROID (debriefing scollegato 22/11): aperto da FINE SCENARIO i dati vengono dall'istantanea di fine (CtlModel.endSnap):
    // il piano resta completo anche con il tablet scollegato in sala debriefing
    @Suppress("UNCHECKED_CAST")
    private val snap: Map<String, Any?>? get() = if (fromEnd && archive == null && viewSession == 0) model.endSnap else null
    private val offline: Boolean get() = archive == null && model.stage != CtlStageReady
    private lateinit var teamScroll: CtlScrollView; private lateinit var card: UIView
    private var titleField: UITextField? = null; private var titleHint: UILabel? = null; private var dateL: UILabel? = null
    private val teamFields = ArrayList<UITextField>(); private var removeArmed = -1; private var removeUntil = 0.0
    private var archTitle: UILabel? = null; private var archSub: UILabel? = null; private var pdfBtn: CtlButton? = null
    fun notice(t: String?) { if (isViewLoaded) { err.text = t ?: ""; err.textColor = CTL_YELLOW } else pendingNotice = t }
    private var pendingNotice: String? = null
    private var sig: String? = null; private var viewSession = 0; private var pastEntries: List<Map<String, Any?>>? = null; private var pastSummary: Map<String, Any?>? = null      // 0 = sessione corrente (dal vivo)
    // ANDROID: prefersStatusBarHidden / supportedInterfaceOrientations non servono (schermo intero, sempre orizzontale)

    override fun viewDidLoad() {
        super.viewDidLoad()
        view.backgroundColor = CTL_BG
        val W = CTL_W                                                       // ANDROID (BugFixController 3): tela larga
        val close = CtlButton.buttonWithTitle("Chiudi", CtlButtonQuiet, null, CGRectMake(16, 12, 110, 40)) { close() }
        close.accessibilityIdentifier = "deb.close"; view.addSubview(close)
        view.addSubview(CtlLabel(CGRectMake(142, 12, 230, 40), "Piano di debriefing", 24.0, true, CTL_INK))
        // quale simulazione si sta guardando: quella in corso oppure una precedente
        sessBtn = CtlButton.buttonWithTitle("Sessione", CtlButtonTinted, CTL_TINT, CGRectMake(384, 12, W - 384 - 16 - 210 - 24, 40)) { pickSession() }   // ANDROID (BugFixController 3): fino a «Svuota»
        sessBtn.titleLabel.font = UIFont.boldSystemFontOfSize(14); sessBtn.maxLines = 1; sessBtn.accessibilityIdentifier = "deb.session"; view.addSubview(sessBtn)
        sessBtn.hidden = archive != null
        err = CtlLabel(CGRectMake(142, 44, W - 142 - 16 - 210 - 24, 18), "", 12.0, true, CTL_RED); view.addSubview(err)
        // ANDROID (guasti 22/11): sotto, discreto, l'orologio del tablet sbagliato (gli orari delle voci verrebbero da lì)
        clockHint = CtlLabel(CGRectMake(142, 44, W - 142 - 16 - 210 - 24, 18), "", 11.0, false, CTL_YELLOW); clockHint.adjustsFontSizeToFitWidth = true; clockHint.minimumScaleFactor = 0.75
        clockHint.accessibilityIdentifier = "deb.clockhint"; view.addSubview(clockHint)
        clear = CtlButton.buttonWithTitle("Svuota il registro", CtlButtonTinted, CTL_RED, CGRectMake(W - 16 - 210, 12, 210, 40)) { clearLog() }
        clear.titleLabel.font = UIFont.systemFontOfSize(14); clear.maxLines = 1; clear.accessibilityIdentifier = "deb.clear"; clear.setIcon("trash", false); view.addSubview(clear)
        // ANDROID (storia sim 22/11): simulazione salvata: titolo e dati al posto della sessione; «Apri il PDF» ed «Elimina»
        // (due tocchi, come «Svuota») al posto di «Svuota il registro»
        archive?.let { a ->
            clear.frame = CGRectMake(W - 16 - 130, 12, 130, 40); clear.setTitle("Elimina", UIControlStateNormal); clear.accessibilityIdentifier = "deb.delete"
            val pb = CtlButton.buttonWithTitle("Apri il PDF", CtlButtonFilled, CTL_TINT, CGRectMake(W - 16 - 130 - 10 - 160, 12, 160, 40)) { openPdf() }
            pb.titleLabel.font = UIFont.boldSystemFontOfSize(14); pb.maxLines = 1; pb.accessibilityIdentifier = "deb.pdf"; view.addSubview(pb); pdfBtn = pb
            val aw = W - 384 - 16 - 130 - 10 - 160 - 16
            val t = CtlLabel(CGRectMake(384, 8, aw, 26), a["title"] as? String ?: "Simulazione", 19.0, true, CTL_INK); t.adjustsFontSizeToFitWidth = true; t.minimumScaleFactor = 0.7
            t.accessibilityIdentifier = "deb.archtitle"; view.addSubview(t); archTitle = t
            val nTeam = (a["team"] as? List<*>)?.size ?: 0
            val sub = CtlLabel(CGRectMake(384, 33, aw, 16), String.format(Locale.US, "Storia Sim · %s · durata %s · %s", a["date_text"] as? String ?: "", MMSS(debInt(a["duration_s"])),
                if (nTeam == 1) "1 partecipante" else "$nTeam partecipanti"), 12.0, false, CTL_INK2)
            sub.adjustsFontSizeToFitWidth = true; sub.minimumScaleFactor = 0.8; view.addSubview(sub); archSub = sub
            err.frame = CGRectMake(142, 50, 230, 14); clockHint.hidden = true
        }
        tiles = UIView(CGRectMake(16, 64, W - 32, 196)); view.addSubview(tiles)
        // ANDROID (storia sim 22/11): primo segmento «Squadra ed esito» (titolo, data e ora, partecipanti con ruolo ed esito)
        filter = UISegmentedControl(listOf("Squadra ed esito", "Punti chiave", "Partecipanti", "Terapia elettrica", "Farmaci", "Istruttore", "Debriefing", "Tutto", "Allarmi", "Trend"))   // ANDROID (funzioni mancanti 22/11): + Allarmi, Trend
        filter.frame = CGRectMake(16, 270, W - 32, 34); filter.tintColor = CTL_TINT; filter.selectedSegmentIndex = if (startAll) 7 else (if (archive != null) 0 else 1); filter.accessibilityIdentifier = "deb.filter"
        filter.addTarget(UIControlEventValueChanged) { endEditingAll(); reloadRows() }
        view.addSubview(filter)
        card = CtlCard(CGRectMake(16, 314, W - 32, CTL_H - 314 - 16)); card.clipsToBounds = true; view.addSubview(card)
        table = UITableView(card.bounds, UITableViewStylePlain)
        table.backgroundColor = UIColor.clearColor; table.separatorColor = CTL_LINE; table.rowHeight = 46.0; table.dataSource = this; table.delegate = this
        table.separatorInset = UIEdgeInsets(); table.layoutMargins = UIEdgeInsets(); table.cellLayoutMarginsFollowReadableWidth = false
        table.indicatorStyle = UIScrollViewIndicatorStyleWhite; table.accessibilityIdentifier = "deb.table"
        table.registerClass("c") { CtlDebriefCell(UITableViewCellStyleDefault, "c") }
        card.addSubview(table)
        // ANDROID (funzioni mancanti 22/11, IN-14): segmento «Trend»: grafico e tabella a passo 1 min (dal record, dall'istantanea di fine o dal vivo)
        trendG = CtlTrendGraph(CGRectMake(12, 12, card.bounds.width - 24, 250)); trendG.hidden = true; card.addSubview(trendG)
        trendT = CtlScrollView(CGRectMake(12, 290, card.bounds.width - 24, card.bounds.height - 302)); trendT.hidden = true; trendT.indicatorStyle = UIScrollViewIndicatorStyleWhite; card.addSubview(trendT)
        trendH = UIView(CGRectMake(12, 268, card.bounds.width - 24, 18)); trendH.hidden = true; card.addSubview(trendH)
        empty = CtlLabel(CGRectInset(card.bounds, 40, 40), "", 16.0, false, CTL_INK3); empty.numberOfLines = 0; empty.textAlignment = NSTextAlignmentCenter; card.addSubview(empty)
        teamScroll = CtlScrollView(card.bounds); teamScroll.indicatorStyle = UIScrollViewIndicatorStyleWhite; teamScroll.hidden = true
        teamScroll.keyboardDismissMode = UIScrollViewKeyboardDismissModeOnDrag; teamScroll.accessibilityIdentifier = "deb.team"; card.addSubview(teamScroll)
        err.adjustsFontSizeToFitWidth = true; err.minimumScaleFactor = 0.8
        pendingNotice?.let { notice(it) }
        reload()
    }

    // ANDROID (storia sim 22/11): aperto da FINE SCENARIO si sceglie se salvare; poi la regia azzera lo scenario
    private fun close() {
        endEditingAll(); unlift()
        if (archive != null || !fromEnd) { dismissViewControllerAnimated(true, null); return }
        val n = st.team.count { p -> listOf("name", "role", "achieved", "reinforce").any { ((p[it] as? String) ?: "").isNotBlank() } }
        // ANDROID (debriefing scollegato 22/11; fix analisi 22/11, BUG-03 lato controller): il testo dice che cosa succederà davvero.
        // Senza monitor: salvataggio qui (PDF compreso) e azzeramento al rientro. Simulazione ripresa sul monitor: niente azzeramento.
        val ph = ScenPhase(m.state)
        val resumed = !offline && m.state != null && (ph == "running" || ph == "ready")
        val tail = if (offline) "Il monitor ora non è raggiungibile: la simulazione si salva su questo tablet (PDF compreso) e il monitor si azzera da solo quando torni nella sala di simulazione."
                   else if (resumed) "Sul monitor la simulazione non è più «terminata» (ripresa o già azzerata): non verrà azzerata."
                   else "Dopo «Salva in Storia Sim» o «Non salvare» lo scenario si azzera da solo: parametri, sonde, contatori e orologio tornano all'inizio e il monitor si spegne."
        val a = UIAlertController.alertControllerWithTitle("Chiudere il piano di debriefing?",
            String.format(Locale.US, "«%s» · %s.\n\n%s", st.title(), if (n == 1) "1 partecipante" else "$n partecipanti", tail), UIAlertControllerStyleAlert)
        val me = this
        a.addAction(UIAlertAction.actionWithTitle("Resta nel debriefing", UIAlertActionStyleCancel, null))
        a.addAction(UIAlertAction.actionWithTitle("Non salvare", UIAlertActionStyleDestructive) { me.finishEnd(false) })
        a.addAction(UIAlertAction.actionWithTitle("Salva in Storia Sim", UIAlertActionStyleDefault) { me.finishEnd(true) })
        presentViewController(a, true, null)
    }
    private fun finishEnd(save: Boolean) {
        if (save) st.save(st.buildRecord(), null)          // prima dell'azzeramento: il registro della sessione è ancora questo
        st.clearDraft()
        val cb = onEndChoice
        dismissViewControllerAnimated(true) { cb?.invoke(save) }
    }

    // MARK: - Simulazione salvata (ANDROID, storia sim 22/11)

    private fun openPdf() {
        val a = archive ?: return
        val f = st.pdfFile(a)
        if (f != null) { CtlOpenStoriaPdf(f); return }
        pdfBtn?.setTitle("Creo il PDF…", UIControlStateNormal)
        st.makePdf(a) { file ->
            pdfBtn?.setTitle("Apri il PDF", UIControlStateNormal)
            if (file != null) CtlOpenStoriaPdf(file) else notice(st.status ?: "PDF non creato")
        }
    }
    private fun deleteArchived() {
        val a = archive ?: return
        if (CACurrentMediaTime() < clearArmed) {
            clearArmed = 0.0
            st.delete(a["id"] as? String ?: return)
            val cb = onDeleted
            dismissViewControllerAnimated(true) { cb?.invoke() }
            return
        }
        clearArmed = CACurrentMediaTime() + 4
        clear.setTitle("Tocca di nuovo", UIControlStateNormal); clear.setStyle(CtlButtonFilled, CTL_RED)
        Uk.after(4.0) { if (CACurrentMediaTime() >= clearArmed) { clear.setTitle("Elimina", UIControlStateNormal); clear.setStyle(CtlButtonTinted, CTL_RED) } }
    }

    // MARK: - Squadra ed esito (ANDROID, storia sim 22/11)

    /** ANDROID: la finestra non si ridimensiona con la tastiera (adjustNothing): il piano sale finché il campo resta sopra la tastiera */
    private fun lift(v: UIView) {
        val p = v.convertPoint(CGPointMake(0, v.bounds.size.height), view)
        val up = Math.max(0.0, p.y - 360)
        if (up > 0 && view.frame.y != -up) view.frame = CGRectMake(0, -up, view.frame.size.width, view.frame.size.height)
    }
    private fun unlift() { if (view.frame.y != 0.0) view.frame = CGRectMake(0, 0, view.frame.size.width, view.frame.size.height) }
    private fun endEditingAll() { for (f in teamFields) if (f.isFirstResponder) f.resignFirstResponder(); titleField?.let { if (it.isFirstResponder) it.resignFirstResponder() } }
    private fun anyEditing(): Boolean = teamFields.any { it.isFirstResponder } || titleField?.isFirstResponder == true

    private fun field(f: CGRect, text: String, placeholder: String, maxLen: Int, ident: String, changed: (String) -> Unit): UITextField {
        val t = UITextField(f)
        t.backgroundColor = CTL_CARD; t.textColor = CTL_INK; t.font = UIFont.systemFontOfSize(16); t.layer.cornerRadius = 8.0
        t.textInset = 10.0; t.placeholder = placeholder; t.placeholderColor = CTL_INK3; t.maxLength = maxLen
        t.autocapitalizationType = UITextAutocapitalizationTypeSentences; t.text = text; t.accessibilityIdentifier = ident
        t.onReturn = { t.resignFirstResponder() }
        t.addTarget(UIControlEventEditingDidBegin) { lift(t) }
        t.addTarget(UIControlEventEditingDidEnd) { Uk.after(0.12) { if (!anyEditing()) unlift() } }
        t.addTarget(UIControlEventEditingChanged) { changed(t.text ?: "") }
        teamFields.add(t)
        return t
    }

    private fun dateNow(): String = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.ITALY).format(Date())

    @Suppress("UNCHECKED_CAST")
    private fun buildTeam() {
        val keep = teamScroll.contentOffset.y
        for (v in teamScroll.subviews.toList()) v.removeFromSuperview()
        teamFields.clear(); titleField = null; titleHint = null; dateL = null
        val CWc = card.bounds.size.width; val me = this
        val ro = archive != null
        // un tocco fuori dai campi chiude la tastiera e riporta giù il piano (anche se la tastiera è stata chiusa con «Indietro»)
        val bg = UIControl(CGRectMake(0, 0, CWc, 2000)); bg.addTarget(UIControlEventTouchUpInside) { me.endEditingAll(); me.unlift() }; teamScroll.addSubview(bg)
        var y = 14.0
        val dw = 300.0; val fw = CWc - 32 - dw - 16
        teamScroll.addSubview(CtlLabel(CGRectMake(16, y, fw, 14), "TITOLO DELLA SIMULAZIONE", 10.5, true, CTL_INK2))
        teamScroll.addSubview(CtlLabel(CGRectMake(CWc - 16 - dw, y, dw, 14), "DATA E ORA", 10.5, true, CTL_INK2))
        y += 18
        if (ro) {
            val t = CtlLabel(CGRectMake(16, y, fw, 40), archive!!["title"] as? String ?: "", 22.0, true, CTL_INK); t.adjustsFontSizeToFitWidth = true; t.minimumScaleFactor = 0.6; teamScroll.addSubview(t)
        } else {
            val tf = field(CGRectMake(16, y, fw, 42), st.draftTitle ?: st.autoTitle(), "Titolo della simulazione", 80, "deb.title") { tx ->
                val t = tx.trim(); st.draftTitle = if (t.isEmpty() || t == st.autoTitle()) null else tx; st.saveDraft()
                titleHint?.hidden = st.draftTitle != null
            }
            tf.font = UIFont.boldSystemFontOfSize(19); teamScroll.addSubview(tf); titleField = tf
            val h = CtlLabel(CGRectMake(16, y + 44, fw, 16), "Proposta automatica (scenario a fasi o ultimo stato attivato): toccala per cambiarla", 11.0, false, CTL_INK3)
            h.hidden = st.draftTitle != null; teamScroll.addSubview(h); titleHint = h
        }
        val dl = CtlLabel(CGRectMake(CWc - 16 - dw, y, dw, 26), if (ro) archive!!["date_text"] as? String ?: "" else dateNow(), 20.0, true, CTL_INK)
        dl.font = CtlDigits(20.0, true); dl.accessibilityIdentifier = "deb.date"; teamScroll.addSubview(dl); if (!ro) dateL = dl
        val ds = CtlLabel(CGRectMake(CWc - 16 - dw, y + 26, dw, 16), if (ro) "salvata da " + (archive!!["saved_by"] as? String ?: "") else
            (if (m.controllerClockWrong) "orologio di questo tablet (sembra sbagliato)" else "orologio di questo tablet"), 11.0, false, if (!ro && m.controllerClockWrong) CTL_YELLOW else CTL_INK3)
        ds.adjustsFontSizeToFitWidth = true; ds.minimumScaleFactor = 0.8; teamScroll.addSubview(ds)
        y += 72
        val team: List<Map<String, Any?>> = if (ro) ((archive!!["team"] as? List<Map<String, Any?>>) ?: emptyList()) else st.team
        teamScroll.addSubview(CtlLabel(CGRectMake(16, y, CWc - 32, 16), String.format(Locale.US, "SQUADRA ED ESITO · %s", if (team.size == 1) "1 partecipante" else "${team.size} partecipanti"), 10.5, true, CTL_INK2))
        y += 22
        val RW = CWc - 24; val hw = (RW - 40 - 12 - 12) / 2
        for ((i, p) in team.withIndex()) {
            val row = UIView(CGRectMake(12, y, RW, 106)); row.backgroundColor = CTL_CARD2; row.layer.cornerRadius = 10.0; teamScroll.addSubview(row)
            val n = CtlLabel(CGRectMake(10, 8, 26, 38), "${i + 1}", 17.0, true, CTL_INK2); n.textAlignment = NSTextAlignmentCenter; row.addSubview(n)
            val name = p["name"] as? String ?: ""; val role = p["role"] as? String ?: ""
            val ach = p["achieved"] as? String ?: ""; val rei = p["reinforce"] as? String ?: ""
            if (ro) {
                val nl = CtlLabel(CGRectMake(40, 8, 330, 38), name.ifBlank { "—" }, 17.0, true, CTL_INK); nl.adjustsFontSizeToFitWidth = true; nl.minimumScaleFactor = 0.7; row.addSubview(nl)
                row.addSubview(CtlLabel(CGRectMake(380, 8, RW - 392, 38), role.ifBlank { "ruolo non indicato" }, 15.0, false, if (role.isBlank()) CTL_INK3 else CTL_INK2))
            } else {
                row.addSubview(field(CGRectMake(40, 8, 330, 38), name, "Nome", 60, "team.name.$i") { st.setMember(i, "name", it) })
                val rb = CtlButton.buttonWithTitle(if (role.isBlank()) "Scegli il ruolo ▾" else "$role ▾", CtlButtonTinted, CTL_TINT, CGRectMake(380, 8, 300, 38)) { pickRole(i) }
                rb.titleLabel.font = UIFont.boldSystemFontOfSize(14); rb.maxLines = 1; rb.accessibilityIdentifier = "team.role.$i"; row.addSubview(rb)
                val armed = removeArmed == i && CACurrentMediaTime() < removeUntil
                val del = CtlButton.buttonWithTitle(if (armed) "Tocca di nuovo" else "Togli", if (armed) CtlButtonFilled else CtlButtonQuiet, CTL_RED, CGRectMake(RW - 12 - 150, 8, 150, 38)) { removeTapped(i) }
                del.titleLabel.font = UIFont.systemFontOfSize(14); del.maxLines = 1; del.accessibilityIdentifier = "team.remove.$i"; row.addSubview(del)
            }
            row.addSubview(CtlLabel(CGRectMake(40, 52, hw, 14), "OBIETTIVI RAGGIUNTI", 10.0, true, CTL_GREEN))
            row.addSubview(CtlLabel(CGRectMake(40 + hw + 12, 52, hw, 14), "DA RINFORZARE", 10.0, true, CTL_ORANGE))
            var rh = 106.0
            if (ro) {
                val a = CtlLabel(CGRectMake(40, 68, hw, 10), ach.ifBlank { "—" }, 14.0, false, CTL_INK); a.numberOfLines = 0
                val b = CtlLabel(CGRectMake(40 + hw + 12, 68, hw, 10), rei.ifBlank { "—" }, 14.0, false, CTL_INK); b.numberOfLines = 0
                val ha = a.sizeThatFits(CGSizeMake(hw, 600)).height; val hb = b.sizeThatFits(CGSizeMake(hw, 600)).height
                a.frame = CGRectMake(40, 68, hw, ha); b.frame = CGRectMake(40 + hw + 12, 68, hw, hb); row.addSubview(a); row.addSubview(b)
                rh = 68 + Math.max(Math.max(ha, hb), 18.0) + 12
                row.frame = CGRectMake(12, y, RW, rh)
            } else {
                row.addSubview(field(CGRectMake(40, 68, hw, 32), ach, "che cosa ha fatto bene", 300, "team.ach.$i") { st.setMember(i, "achieved", it) })
                row.addSubview(field(CGRectMake(40 + hw + 12, 68, hw, 32), rei, "su che cosa lavorare", 300, "team.rei.$i") { st.setMember(i, "reinforce", it) })
            }
            y += rh + 8
        }
        if (ro && team.isEmpty()) { teamScroll.addSubview(CtlLabel(CGRectMake(16, y, CWc - 32, 30), "Nessun partecipante registrato.", 15.0, false, CTL_INK3)); y += 36 }
        if (!ro) {
            val add = CtlButton.buttonWithTitle("Aggiungi un partecipante", CtlButtonTinted, CTL_TINT, CGRectMake(12, y + 2, 300, 42)) { me.endEditingAll(); me.st.addMember(); me.buildTeam(); me.scrollTeamToEnd() }
            add.titleLabel.font = UIFont.boldSystemFontOfSize(14); add.maxLines = 1; add.setIcon("plus", false); add.accessibilityIdentifier = "team.add"; teamScroll.addSubview(add)
            val note = CtlLabel(CGRectMake(328, y + 2, CWc - 328 - 16, 42), "Titolo e squadra restano su questo tablet come bozza (anche a scenario in corso) finché non li salvi in Storia Sim o li scarti, alla chiusura dopo FINE SCENARIO.", 11.5, false, CTL_INK3)
            note.numberOfLines = 2; note.adjustsFontSizeToFitWidth = true; note.minimumScaleFactor = 0.85; teamScroll.addSubview(note)
            y += 52
        }
        teamScroll.contentSize = CGSizeMake(CWc, y + 12)
        val maxY = Math.max(0.0, y + 12 - teamScroll.bounds.size.height)
        teamScroll.contentOffset = CGPointMake(0, Math.min(keep, maxY))
    }
    private fun scrollTeamToEnd() { val maxY = Math.max(0.0, teamScroll.contentSize.height - teamScroll.bounds.size.height); teamScroll.setContentOffset(CGPointMake(0, maxY), false) }
    private fun removeTapped(i: Int) {
        endEditingAll()
        if (removeArmed == i && CACurrentMediaTime() < removeUntil) { removeArmed = -1; st.removeMember(i); buildTeam(); return }
        removeArmed = i; removeUntil = CACurrentMediaTime() + 3; buildTeam()
        Uk.after(3.1) { if (removeArmed == i && CACurrentMediaTime() >= removeUntil) { removeArmed = -1; if (filter.selectedSegmentIndex == 0 && !anyEditing()) buildTeam() } }
    }
    private fun pickRole(i: Int) {
        endEditingAll()
        val me = this
        val a = CtlSheet.actions("Ruolo nella squadra", null)
        for (r in STORIA_ROLES) a.addAction(UIAlertAction.actionWithTitle(r, UIAlertActionStyleDefault) { me.st.setMember(i, "role", r); me.buildTeam() })
        a.addAction(UIAlertAction.actionWithTitle("Nessun ruolo", UIAlertActionStyleDefault) { me.st.setMember(i, "role", ""); me.buildTeam() })
        a.addAction(UIAlertAction.actionWithTitle("Annulla", UIAlertActionStyleCancel, null))
        a.present()      // ANDROID (menu layout a tema 22/11): pannello CtlSheet nel tema (era un AlertDialog di sistema)
    }

    private fun pickSession() {
        val a = CtlSheet.actions("Sessioni registrate", "Una sessione per ogni simulazione: si apre da sola quando avvii uno scenario o azzeri lo scenario.")
        for (m in (this.m.logSessions ?: emptyList()).reversed()) {
            if (debInt(m["entries"]) == 0 && m["current"] != true) continue
            val sid = debInt(m["id"]); val cur = m["current"] == true
            a.addAction(UIAlertAction.actionWithTitle(String.format(Locale.US, "%s%s", if (cur) "In corso · " else "", SessLine(m, this.m.clockOffset ?: 0.0)), UIAlertActionStyleDefault) { show(if (cur) 0 else sid) })
        }
        a.addAction(UIAlertAction.actionWithTitle("Chiudi questa sessione e aprine una nuova", UIAlertActionStyleDefault) { m.newSession(null); show(0) })
        a.addAction(UIAlertAction.actionWithTitle("Annulla", UIAlertActionStyleCancel, null))
        // ANDROID: popoverPresentationController (sourceView/sourceRect) non c'è: l'elenco compare come finestra di dialogo
        a.present()      // ANDROID (menu layout a tema 22/11): pannello CtlSheet nel tema (era un AlertDialog di sistema)
    }
    private fun show(sid: Int) {
        sig = null; viewSession = sid; pastEntries = null; pastSummary = null; shown = 0
        if (sid != 0) { m.loadSession(sid) { e, sum -> if (viewSession != sid) return@loadSession; pastEntries = e; pastSummary = sum; reload() } }
        reload()
    }

    private fun clearLog() {
        if (archive != null) { deleteArchived(); return }          // ANDROID (storia sim 22/11)
        if (CACurrentMediaTime() < clearArmed) {
            clearArmed = 0.0
            m.logClear { ok, e -> err.text = if (ok) "" else e; resetClear(); sig = null; reload() }
            return
        }
        clearArmed = CACurrentMediaTime() + 4
        clear.setTitle("Tocca di nuovo: svuota", UIControlStateNormal); clear.setStyle(CtlButtonFilled, CTL_RED)
        Uk.after(4.0) { if (CACurrentMediaTime() >= clearArmed) resetClear() }
    }
    private fun resetClear() { clear.setTitle("Svuota il registro", UIControlStateNormal); clear.setStyle(CtlButtonTinted, CTL_RED) }

    private fun tile(caption: String, value: String, detail: String, color: UIColor, frame: CGRect) {
        val f = frame
        val t = CtlCard(f)
        val c = CtlLabel(CGRectMake(12, 8, f.size.width - 24, 14), caption, 10.5, true, CTL_INK2); c.adjustsFontSizeToFitWidth = true; c.minimumScaleFactor = 0.75; t.addSubview(c)
        val v = CtlLabel(CGRectMake(12, 24, f.size.width - 24, 38), value, 28.0, true, color); v.font = CtlDigits(28.0, true); v.adjustsFontSizeToFitWidth = true; v.minimumScaleFactor = 0.5; t.addSubview(v)
        val d = CtlLabel(CGRectMake(12, 62, f.size.width - 24, f.size.height - 68), detail, 12.0, false, CTL_INK2); d.numberOfLines = 0; t.addSubview(d)
        d.sizeToFit(); d.frame = CGRectMake(12, 62, f.size.width - 24, minOf(d.frame.size.height, f.size.height - 68))
        t.clipsToBounds = true                     // ANDROID (funzioni 21/11): con molte scariche il dettaglio usciva dalla tessera
        tiles.addSubview(t)
    }

    @Suppress("UNCHECKED_CAST")
    fun reload() {
        if (!isViewLoaded) return
        // si ricostruisce solo se è cambiato qualcosa (la regia chiama reload a ogni lettura dello stato)
        // ANDROID (funzioni 21/11): anche le metriche RCP dal vivo, la fine dello scenario e l'orologio del monitor
        // ANDROID (storia sim 22/11): bozza dal vivo: data e ora del tablet e proposta automatica del titolo
        dateL?.let { val t = dateNow(); if (it.text != t) it.text = t }
        if (view.frame.y != 0.0 && !anyEditing()) unlift()
        titleField?.let { tf -> if (st.draftTitle == null && !tf.isFirstResponder) { val a = st.autoTitle(); if (tf.text != a) tf.text = a } }
        val sig = if (archive != null) "A" else String.format(Locale.US, "%d|%s|%d|%d|%d|%d|%d|%s", viewSession, debObj(m.log.lastOrNull()?.get("i")), m.log.size,
            m.logSessions?.size ?: 0, if (pastSummary != null) 1 else 0, cprSig(), m.endMark?.hashCode() ?: 0,
            debObj(m.clockOffset?.let { Math.round(it) })) + "|" + (if (snap != null) m.endSnapRev else -1)      // ANDROID (debriefing scollegato 22/11)
        // ANDROID (debriefing scollegato 22/11): senza monitor una sola riga discreta (al posto dell'avviso sull'orologio)
        val ch = if (!err.text.isNullOrEmpty()) "" else if (fromEnd && offline) "Monitor non raggiungibile: il debriefing funziona lo stesso" else CtlClockHint(m)      // ANDROID (guasti 22/11)
        if (clockHint.text != ch) clockHint.text = ch
        if (sig == this.sig) return
        if (table.isDragging || table.isDecelerating) return      // ANDROID (funzioni 21/11): mai ricostruire sotto il dito
        this.sig = sig
        for (v in tiles.subviews) v.removeFromSuperview()
        @Suppress("UNCHECKED_CAST")
        val sn = snap
        val s = (if (archive != null) archive["summary"] as? Map<String, Any?> else if (viewSession != 0) pastSummary else if (sn != null) sn["summary"] as? Map<String, Any?> else m.logSummary) ?: emptyMap()
        var st = "Sessione in corso"
        for (m in this.m.logSessions ?: emptyList()) if (if (viewSession != 0) debInt(m["id"]) == viewSession else m["current"] == true) st = String.format(Locale.US, "%s%s", if (viewSession != 0) "" else "In corso · ", SessLine(m, this.m.clockOffset ?: 0.0))
        (sn?.get("session_info") as? Map<String, Any?>)?.let { st = "Terminata · " + SessLine(it, this.m.clockOffset ?: 0.0) }      // ANDROID (debriefing scollegato 22/11)
        if (archive == null && sessBtn.titleForState(UIControlStateNormal) != st) sessBtn.setTitle(st, UIControlStateNormal)
        val W = tiles.bounds.size.width; val g = 8.0; val w = (W - 5 * g) / 6; val h1 = 94.0
        val arrest = s["arrest_at_s"] != null
        val shocks = s["shocks"] as? List<Map<String, Any?>> ?: emptyList(); val drugs = s["drugs"] as? List<Map<String, Any?>> ?: emptyList()
        // prima riga: tempi delle azioni critiche (contati dall'arresto)
        val row1: List<List<Any>> = listOf(listOf("DURATA", MMSS(debInt(s["duration_s"])), String.format(Locale.US, "%s voci nel registro", debObj(s["entries"] ?: 0)), CTL_INK),
            listOf("ARRESTO CARDIACO", if (arrest) MMSS(debInt(s["arrest_at_s"])) else "—", if (arrest) "minuto di scenario" else "non registrato", if (arrest) CTL_RED else CTL_INK3),
            listOf("RICONOSCIMENTO", Span(s["to_recognition_s"]), if (arrest) "dall'arresto" else "", CTL_GREEN),
            listOf("PRIME COMPRESSIONI", Span(s["to_cpr_s"]), if (arrest) "dall'arresto" else "", CTL_GREEN),
            listOf("PRIMA SCARICA", Span(s["to_first_shock_s"]), if (arrest) "dall'arresto" else "", CTL_ORANGE),
            listOf("ROSC", Span(s["to_rosc_s"]), if (arrest) "dall'arresto" else "", CTL_HR))
        for (i in row1.indices) tile(row1[i][0] as String, row1[i][1] as String, row1[i][2] as String, row1[i][3] as UIColor, CGRectMake(i * (w + g), 0, w, h1))
        // seconda riga: scariche, farmaci, punti segnati
        val sh = ArrayList<String>(); for (x in shocks) sh.add(String.format(Locale.US, "%s J a %s", debObj(x["energy_j"]), MMSS(debInt(x["t"]))))
        val dr = ArrayList<String>(); for (x in drugs) dr.add(String.format(Locale.US, "%s  %s", MMSS(debInt(x["t"])), debObj(x["text"])))
        val y2 = h1 + g; val h2 = tiles.bounds.size.height - y2; val w3 = (W - 3 * g) / 4      // ANDROID (funzioni mancanti 22/11): 4 tessere (+ Allarmi)
        tile("SCARICHE EROGATE", String.format(Locale.US, "%d", shocks.size), if (sh.isNotEmpty()) (if (sh.size > 4) sh.take(4).joinToString("   ·   ") + String.format(Locale.US, "   ·   e altre %d", sh.size - 4) else sh.joinToString("   ·   ")) else "nessuna", CTL_ORANGE, CGRectMake(0, y2, w3, h2))
        tile("FARMACI", String.format(Locale.US, "%d", drugs.size), if (dr.isNotEmpty()) dr.joinToString("   ·   ") else "nessuno", CTL_TEMP, CGRectMake(w3 + g, y2, w3, h2))
        tile("SEGNATI DALL'ISTRUTTORE", String.format(Locale.US, "%s · %s", debObj(s["plus"] ?: 0), debObj(s["minus"] ?: 0)),
            String.format(Locale.US, "%s punti di forza, %s da discutere (filtro «Debriefing»)", debObj(s["plus"] ?: 0), debObj(s["minus"] ?: 0)), CTL_INK, CGRectMake(2 * (w3 + g), y2, w3, h2))
        // ANDROID (funzioni mancanti 22/11, IN-06): allarmi della sessione (registro del monitor)
        val al = s["alarms"] as? Map<String, Any?>
        if (al == null) tile("ALLARMI", "—", "monitor senza il registro degli allarmi", CTL_INK3, CGRectMake(3 * (w3 + g), y2, w3, h2))
        else tile("ALLARMI", String.format(Locale.US, "%d", debInt(al["total"])), CtlAlarmSummaryText(al) + " (filtro «Allarmi»)", if (debInt(al["high"]) > 0) CTL_RED else CTL_INK, CGRectMake(3 * (w3 + g), y2, w3, h2))
        reloadRows()
    }

    // ANDROID (funzioni mancanti 22/11, IN-14): trend del record salvato, dell'istantanea di fine o letto adesso dal monitor
    private lateinit var trendG: CtlTrendGraph; private lateinit var trendT: CtlScrollView; private lateinit var trendH: UIView
    private var trendLive: List<Map<String, Any?>>? = null; private var trendLiveAt = -100.0
    @Suppress("UNCHECKED_CAST")
    private fun trendRows(): List<Map<String, Any?>>? = if (archive != null) archive["trend"] as? List<Map<String, Any?>>
        else if (viewSession != 0) null else (snap?.get("trend") as? List<Map<String, Any?>>) ?: trendLive ?: m.trendCache
    private fun showTrend() {
        if (archive == null && viewSession == 0 && CACurrentMediaTime() - trendLiveAt > 10) { trendLiveAt = CACurrentMediaTime(); m.loadTrend60 { r -> if (r != null) { trendLive = r; if (filter.selectedSegmentIndex == 9) showTrend() } } }
        val rows = trendRows()
        trendG.rows = rows ?: emptyList()
        for (v in trendT.subviews.toList()) v.removeFromSuperview(); for (v in trendH.subviews.toList()) v.removeFromSuperview()
        empty.hidden = rows != null && rows.isNotEmpty()
        empty.text = if (viewSession != 0) "Il trend c'è solo per la simulazione in corso o salvata in Storia Sim." else "Nessun trend: il monitor lo registra da «Avvia» (va aggiornato se non lo fa)."
        if (rows.isNullOrEmpty()) { trendG.hidden = true; return }
        val W = trendT.bounds.width; val ws = listOf(0.09, 0.07, 0.07, 0.19, 0.18, 0.07, 0.08, 0.08, 0.07).map { it * W }
        var x = 0.0
        for ((i, c) in CtlTrendCols.withIndex()) { trendH.addSubview(CtlLabel(CGRectMake(x, 0, ws[i], 16), c.uppercase(Locale.ITALY), 10.5, true, CTL_INK2)); x += ws[i] }
        val lines = CtlTrendTableText(rows); x = 0.0; var h = 10.0
        for (i in CtlTrendCols.indices) {
            val l = CtlLabel(CGRectMake(x, 0, ws[i], 10), lines.joinToString("\n") { it.split("\t").getOrElse(i) { "" } }, 13.0, i == 0, CTL_INK); l.font = CtlDigits(13.0, i == 0); l.numberOfLines = 0
            l.sizeToFit(); l.frame = CGRectMake(x, 0, ws[i], l.frame.height); h = Math.max(h, l.frame.height); trendT.addSubview(l); x += ws[i]
        }
        trendT.contentSize = CGSizeMake(W, h + 8)
    }

    private fun reloadRows() {
        // "Punti chiave" (vista di partenza): ciò che serve per discutere — fasi, arresto e ROSC, azioni osservate, scariche,
        // farmaci, punti segnati. Il resto (ogni parametro ritoccato, sonde, timer) resta sotto "Tutto" e negli altri filtri.
        // ANDROID (storia sim 22/11): segmento 0 = «Squadra ed esito» (al posto della cronologia)
        val teamOn = filter.selectedSegmentIndex == 0
        val trendOn = filter.selectedSegmentIndex == 9                 // ANDROID (funzioni mancanti 22/11)
        trendG.hidden = !trendOn; trendT.hidden = !trendOn; trendH.hidden = !trendOn
        if (trendOn) { teamScroll.hidden = true; table.hidden = true; showTrend(); return }
        if (!teamOn && teamScroll.hidden) { table.hidden = false; empty.hidden = false }
        if (teamOn != !teamScroll.hidden || (teamOn && teamScroll.subviews.isEmpty())) {
            teamScroll.hidden = !teamOn; table.hidden = teamOn; empty.hidden = teamOn
            if (teamOn) buildTeam() else unlift()
        }
        if (teamOn) return
        val sets = listOf(listOf("KEY"), listOf("monitor", "electric", "action"), listOf("electric"), listOf("drug"), listOf("patient", "phase", "timer"), listOf("plus", "minus", "note"), listOf(), listOf("alarm"))
        val want = sets[maxOf(0, filter.selectedSegmentIndex - 1)]
        val key = want.firstOrNull() == "KEY"
        val keyKinds = listOf("phase", "action", "drug", "plus", "minus", "note"); val keyTags = listOf("arrest", "rosc", "shock", "monitor_on")
        val a = ArrayList<Map<String, Any?>>()
        for (d in entries()) {
            // ANDROID (guasti 22/11): anche i malfunzionamenti simulati (attivati / risolti dall'istruttore e i loro effetti:
            // «Scarica non erogata: batteria (malfunzionamento simulato)», «Dispositivo spento: batteria esaurita») sono punti chiave
            val ok = if (key) ((d["k"] as? String) in keyKinds || ((d["tag"] as? String) ?: "") in keyTags || IsFaultEntry(d)) else (want.isEmpty() || (d["k"] as? String) in want)
            if (ok) a.add(d)
        }
        // ANDROID (funzioni 21/11): in testa le metriche RCP (frazione di compressione e una riga per scarica con le pause),
        // in fondo la riga «Fine scenario» con i suoi orari. Solo per la sessione in corso: il monitor le tiene dal vivo.
        if (viewSession == 0 && (key || want.isEmpty() || want.contains("electric"))) a.addAll(0, rcpRows())
        if (viewSession == 0) endRow(a)?.let { a.add(it) }
        val atEnd = table.contentOffset.y >= table.contentSize.height - table.bounds.size.height - 60 || rows.isEmpty()
        rows = a; table.reloadData()
        empty.text = if (a.isNotEmpty()) "" else (if (m.logAvailable || archive != null) "Nessuna voce in questa vista." else "Questo monitor non ha il registro: va aggiornato similFranz Monitor.")
        if (a.isNotEmpty() && atEnd && a.size != shown) table.scrollToRowAtIndexPath(NSIndexPath.indexPathForRow(a.size - 1, 0), UITableViewScrollPositionBottom, false)
        shown = a.size
    }

    // ANDROID (funzioni 21/11): in arresto i secondi crescono di continuo: si ricostruisce solo se cambiano scariche o CCF (in %)
    // ANDROID (storia sim 22/11): voci, metriche RCP e fine scenario dal record salvato oppure dal vivo
    @Suppress("UNCHECKED_CAST")
    private fun entries(): List<Map<String, Any?>> = if (archive != null) ((archive["entries"] as? List<Map<String, Any?>>) ?: emptyList())
        else if (viewSession != 0) (pastEntries ?: emptyList()) else snap?.let { (it["entries"] as? List<Map<String, Any?>>) ?: emptyList() } ?: m.log
    @Suppress("UNCHECKED_CAST")
    private fun cprMetrics(): Map<String, Any?>? = if (archive != null) archive["cpr"] as? Map<String, Any?> else if (snap != null) snap!!["cpr"] as? Map<String, Any?> else m.state?.get("cpr_metrics") as? Map<String, Any?>
    @Suppress("UNCHECKED_CAST")
    private fun endInfo(): Map<String, Any?>? {
        if (archive != null) return archive["end"] as? Map<String, Any?>
        snap?.let { return it["end"] as? Map<String, Any?> }      // ANDROID (debriefing scollegato 22/11)
        val e = m.endMark ?: return null
        return if (debInt(e["session"]) != m.logSession) null else e
    }
    private fun cprSig(): Int {
        val cm = m.state?.get("cpr_metrics") as? Map<*, *> ?: return 0
        return ((cm["shocks"] as? List<*>)?.hashCode() ?: 0) * 31 + ((cm["ccf_percent"] as? Number)?.let { Math.round(it.toDouble()).toInt() } ?: -1)
    }
    @Suppress("UNCHECKED_CAST")
    private fun rcpRows(): List<Map<String, Any?>> {
        val cm = cprMetrics() ?: return emptyList()
        val shocks = (cm["shocks"] as? List<Map<String, Any?>>) ?: emptyList()
        if (cm["ccf_percent"] == null && shocks.isEmpty()) return emptyList()
        val out = ArrayList<Map<String, Any?>>()
        out.add(linkedMapOf("_rcp" to "sum", "ccf" to cm["ccf_percent"], "arrest_s" to cm["arrest_s"], "cpr_s" to cm["cpr_s"]))
        for (x in shocks) out.add(LinkedHashMap(x).apply { this["_rcp"] = "shock" })
        return out
    }
    private fun endRow(a: List<Map<String, Any?>>): Map<String, Any?>? {
        val e = endInfo() ?: return null
        val t = debInt(e["t"])
        // se il monitor ha già scritto la sua riga di fine (comando end_scenario), basta quella
        for (d in entries()) { if (d["tag"] == "scenario_end" && debInt(d["t"]) >= t - 3) return null; val tx = (d["text"] as? String ?: "").lowercase(Locale.ITALIAN)
            if ((tx.contains("scenario terminato") || tx.contains("fine scenario")) && debInt(d["t"]) >= t - 3) return null }
        return linkedMapOf("_end" to true, "t" to t, "wall" to e["w"], "k" to "phase", "text" to "Fine scenario")
    }

    override fun numberOfRowsInSection(tableView: UITableView, section: Int): Int = rows.size
    override fun cellForRowAtIndexPath(tableView: UITableView, indexPath: NSIndexPath): UITableViewCell {
        val c = tableView.dequeueReusableCellWithIdentifier("c", indexPath) as CtlDebriefCell
        val d = rows[indexPath.row]; val col = CtlLogKindColor(d["k"] as? String)
        // ANDROID (funzioni 21/11): righe delle metriche RCP
        val rcp = d["_rcp"] as? String
        c.pre.hidden = rcp != "shock"; c.post.hidden = rcp != "shock"
        c.body.frame = CGRectMake(230, 0, CTL_W - 32 - 230 - 24 - (if (rcp == "shock") 2 * 112 + 16 else 0), 44)
        if (rcp != null) {
            c.kind.text = "RCP"; c.kind.textColor = CTL_ORANGE; c.kind.layer.borderColor = CTL_ORANGE.CGColor
            c.body.font = UIFont.boldSystemFontOfSize(16)
            if (rcp == "sum") {
                val ccf = d["ccf"] as? Number
                c.time.text = ""; c.wall.text = ""
                c.body.text = if (ccf == null) "Frazione di compressione: — (arresto troppo breve)"
                              else String.format(Locale.US, "Frazione di compressione %d %%  ·  arresto %s, compressioni %s", Math.round(ccf.toDouble()), Span(d["arrest_s"]), Span(d["cpr_s"]))
                c.body.textColor = if (ccf == null) CTL_INK2 else CtlCcfColor(ccf.toDouble())
            } else {
                c.time.text = MMSS(debInt(d["t"])); c.wall.text = ""
                val pulse = d["pulse"] == true
                c.body.text = String.format(Locale.US, "Scarica %s · %s J%s", debObj(d["n"]), debObj(d["energy_j"]),
                    if (pulse) (if (d["sync"] == true) " · ritmo con polso (SYNC)" else " · ritmo con polso (senza SYNC)") else "")
                c.body.textColor = CTL_INK
                for ((lab, k, nm) in listOf(Triple(c.pre, "pre_s", "pre"), Triple(c.post, "post_s", "post"))) {
                    val v = d[k] as? Number
                    lab.text = if (v != null) String.format(Locale.US, "%s %d s", nm, Math.round(v.toDouble())) else "$nm —"
                    val lc = if (v != null) CtlPauseColor(v.toDouble()) else CTL_INK3
                    lab.textColor = lc; lab.layer.borderColor = lc.CGColor; lab.backgroundColor = lc.colorWithAlphaComponent(0.15)
                }
            }
            return c
        }
        c.time.text = MMSS(debInt(d["t"]))
        c.wall.text = if (d["_end"] == true || archive != null || d["wall"] is Number) WallText((d["wall"] as? Number)?.toDouble()) else WallText(m.wallOf(d["w"]))
        c.kind.text = CtlLogKindName(d["k"] as? String); c.kind.textColor = col; c.kind.layer.borderColor = col.CGColor
        val by = d["by"] as? String; val who = !by.isNullOrEmpty() && by != "istruttore"
        c.body.text = if (who) String.format(Locale.US, "%s   — %s", debObj(d["text"]), by) else d["text"] as? String
        val strong = IsFaultEntry(d) || d["k"] == "plus" || d["k"] == "minus" || d["tag"] == "shock" || d["tag"] == "arrest" || d["tag"] == "rosc" || d["_end"] == true
        c.body.font = if (strong) UIFont.boldSystemFontOfSize(16) else UIFont.systemFontOfSize(16)
        c.body.textColor = if (d["k"] == "timer") CTL_INK3 else CTL_INK
        return c
    }
}
