// Portato da src/CtlScenarioEditor.m (controller iPad)
// similFranz Controller — costruzione di uno scenario a fasi (salvato sul monitor, condiviso con la web app).
// A sinistra l'elenco delle fasi (aggiungi, duplica, sposta, elimina); a destra la fase scelta:
// nome, ritmo, parametri (ognuno con il suo interruttore "cambia in questa fase"), tempo di transizione, nota.
package it.simulafranz.controller

import it.simulafranz.core.*
import it.simulafranz.uk.*
import java.util.Collections
import java.util.Locale

// CtlRhythmName e CtlDescribeBlock sono in CtlViewController.kt

private fun EdParams(): List<List<Any>> {   // chiave, titolo, min, max, passo, decimali, valore iniziale
    return listOf(listOf("heart_rate_bpm", "FC  bpm", 0, 250, 1, 0, 80), listOf("systolic_mmhg", "Sistolica  mmHg", 40, 260, 1, 0, 120),
        listOf("diastolic_mmhg", "Diastolica  mmHg", 20, 160, 1, 0, 80), listOf("spo2_percent", "SpO₂  %", 0, 100, 1, 0, 97),
        listOf("respiratory_rate_rpm", "FR  atti/min", 0, 50, 1, 0, 14), listOf("etco2_mmhg", "EtCO₂  mmHg", 0, 100, 1, 0, 38),
        // ANDROID (funzioni 21/11): minimo 25 °C (scenario dell'ipotermia a 28,5 °C; il monitor accetta 25–45)
        listOf("temperature_c", "Temperatura  °C", 25, 43, 0.1, 1, 36.8), listOf("crt_s", "Refill  s", 0.5, 8, 0.5, 1, 2))
}
private fun EdDurations(): List<Int> = listOf(0, 10, 30, 60, 120, 300)
private const val kMaxPhases = 24

// ANDROID: [x integerValue] / [x doubleValue] di un NSNumber (nil → 0)
private fun edInt(v: Any?): Int = when (v) { is Number -> v.toInt(); is String -> v.trim().toIntOrNull() ?: 0; is Boolean -> if (v) 1 else 0; else -> 0 }
private fun edDouble(v: Any?): Double = when (v) { is Number -> v.toDouble(); is String -> v.trim().toDoubleOrNull() ?: 0.0; else -> 0.0 }
// ANDROID: [self.view endEditing:YES] non c'è in uk: si chiude la tastiera del campo che la ha
private fun UIView.endEditing(force: Boolean) {
    for (s in subviews) {
        if (s is UITextField && s.isFirstResponder) s.resignFirstResponder()
        else if (s is UITextView && s.isFirstResponder) s.resignFirstResponder()
        else s.endEditing(force)
    }
}

private fun Fmt(v: Number, dec: Int): String {
    return if (dec > 0) String.format(Locale.US, "%." + dec + "f", v.toDouble()).replace(".", ",") else String.format(Locale.US, "%d", Math.round(v.toDouble()))
}

// scenario null = nuovo. currentBlock = {rhythm, params, temperature_c, crt_s} del paziente adesso ("fase dai valori attuali").
// ANDROID (BugFixController 3): larghezza dell'elenco delle fasi (280 sull'iPad a 1024): sulla tela larga 340,
// il resto va al modulo della fase (cursori più lunghi)
private val kEdListW: Double get() = if (CTL_W >= 1200) 340.0 else 280.0

class CtlScenarioEditor(scenario: Map<String, Any?>?, currentBlock: Map<String, Any?>?) : UIViewController() {
    var onSave: ((Map<String, Any?>) -> Unit)? = null
    var onDelete: ((String) -> Unit)? = null

    private val sid: String? = scenario?.get("id") as? String
    private val current: Map<String, Any?> = currentBlock ?: emptyMap()
    private val phases = ArrayList<MutableMap<String, Any?>>(); private var selIdx = 0
    private lateinit var name: UITextField; private lateinit var phName: UITextField; private lateinit var phNote: UITextField
    private lateinit var notes: UITextView; private lateinit var notesHint: UILabel
    private lateinit var error: UILabel; private lateinit var count: UILabel
    private lateinit var list: UIScrollView; private lateinit var form: UIScrollView
    private lateinit var dur: UISegmentedControl
    private val sw = HashMap<String, UISwitch>(); private val sl = HashMap<String, CtlRuler>(); private val `val` = HashMap<String, UILabel>()
    private val rh = LinkedHashMap<String, CtlButton>()
    private val ecgB = LinkedHashMap<String, CtlButton>()      // ANDROID (regia morfologie ECG 22/11): non cambia / ECG normale / con alterazioni
    private var del: CtlButton? = null; private var delArmed = 0.0
    private val initName: String? = scenario?.get("name") as? String
    private val initNotes: String? = scenario?.get("notes") as? String
    private var reloadListSeq = 0          // ANDROID: al posto di cancelPreviousPerformRequestsWithTarget:selector:object:

    init {
        for (p in (scenario?.get("phases") as? List<*>) ?: emptyList<Any?>()) {
            @Suppress("UNCHECKED_CAST")
            (p as? Map<String, Any?>)?.let { phases.add(mutablePhase(it)) }
        }
        if (phases.isEmpty()) phases.add(blankPhase())
    }
    // ANDROID: prefersStatusBarHidden / supportedInterfaceOrientations non servono (schermo intero, sempre orizzontale)

    private fun mutablePhase(p: Map<String, Any?>): MutableMap<String, Any?> {
        val m = LinkedHashMap(p)
        m["params"] = (p["params"] as? Map<*, *>)?.let { pp -> LinkedHashMap<String, Any?>().also { for ((k, v) in pp) it[k.toString()] = v } } ?: LinkedHashMap<String, Any?>()
        return m
    }
    private fun blankPhase(): MutableMap<String, Any?> {
        return linkedMapOf("name" to "", "note" to "", "duration_s" to 0, "rhythm" to "sinus", "params" to LinkedHashMap<String, Any?>())
    }

    private fun field(f: CGRect, placeholder: String, `in`: UIView, ident: String): UITextField {
        val t = UITextField(f)
        t.backgroundColor = CTL_CARD2; t.textColor = CTL_INK; t.font = UIFont.systemFontOfSize(17); t.layer.cornerRadius = 9.0
        t.textInset = 12.0                                                 // ANDROID: al posto di leftView 12×10 sempre visibile
        t.placeholder = placeholder; t.placeholderColor = CTL_INK3
        // ANDROID: clearButtonMode, returnKeyType (la tastiera ha già "Fine") e keyboardAppearance non ci sono in uk
        t.onReturn = { textFieldShouldReturn(t) }
        t.autocapitalizationType = UITextAutocapitalizationTypeSentences
        t.accessibilityIdentifier = ident
        t.addTarget(UIControlEventEditingChanged) { textChanged(it as UITextField) }
        `in`.addSubview(t)
        return t
    }

    override fun viewDidLoad() {
        super.viewDidLoad()
        view.backgroundColor = CTL_BG
        val W = CTL_W                                                       // ANDROID (BugFixController 3): tela larga
        val L = kEdListW
        val cancel = CtlButton.buttonWithTitle("Annulla", CtlButtonQuiet, null, CGRectMake(16, 12, 110, 40)) { cancel() }
        cancel.accessibilityIdentifier = "ed.cancel"; view.addSubview(cancel)
        val t = CtlLabel(CGRectMake(140, 12, W - 280 - 260, 40), if (sid != null) "Modifica lo scenario" else "Nuovo scenario", 22.0, true, CTL_INK); view.addSubview(t)
        val save = CtlButton.buttonWithTitle("Salva sul monitor", CtlButtonFilled, CTL_TINT, CGRectMake(W - 16 - 190, 12, 190, 40)) { save() }
        save.accessibilityIdentifier = "ed.save"; view.addSubview(save)
        if (sid != null) {
            val d = CtlButton.buttonWithTitle("Elimina scenario", CtlButtonTinted, CTL_RED, CGRectMake(W - 16 - 190 - 12 - 200, 12, 200, 40)) { deleteScenario() }
            d.titleLabel.font = UIFont.systemFontOfSize(14); d.accessibilityIdentifier = "ed.delete"; view.addSubview(d); del = d
        }
        name = field(CGRectMake(16, 64, L, 44), "Nome dello scenario", view, "ed.name")
        name.text = initName; name.font = UIFont.boldSystemFontOfSize(17)
        name.maxLength = 60                                                // textField:shouldChangeCharactersInRange:
        notes = UITextView(CGRectMake(L + 28, 60, W - L - 44, 52))           // due righe: paziente, contesto, obiettivi
        notes.backgroundColor = CTL_CARD2; notes.textColor = CTL_INK; notes.font = UIFont.systemFontOfSize(14); notes.layer.cornerRadius = 9.0
        notes.textContainerInset = UIEdgeInsetsMake(8, 8, 6, 8); notes.keyboardAppearance = 1
        notes.onChange = { textViewDidChange(it) }
        notes.onReturn = { it.resignFirstResponder() }                     // textView:shouldChangeTextInRange: "\n" chiude la tastiera
        notes.maxLength = 600
        notes.accessibilityIdentifier = "ed.notes"; notes.text = initNotes
        view.addSubview(notes)
        notesHint = CtlLabel(CGRectMake(L + 42, 60, 400, 34), "Paziente, contesto, obiettivi (facoltativo)", 14.0, false, CTL_INK3); notesHint.userInteractionEnabled = false
        notesHint.hidden = (initNotes?.length ?: 0) > 0; view.addSubview(notesHint)
        error = CtlLabel(CGRectMake(16, 112, W - 32, 24), "", 14.0, true, CTL_RED); view.addSubview(error)

        val lc = CtlCard(CGRectMake(16, 140, L, 612)); lc.clipsToBounds = true; view.addSubview(lc)
        lc.addSubview(CtlLabel(CGRectMake(14, 10, 160, 18), "FASI", 11.0, true, CTL_INK2))
        count = CtlLabel(CGRectMake(L - 130, 10, 116, 18), "", 11.0, false, CTL_INK3); count.textAlignment = NSTextAlignmentRight; lc.addSubview(count)
        list = CtlScrollView(CGRectMake(0, 32, L, 612 - 32 - 150)); list.indicatorStyle = UIScrollViewIndicatorStyleWhite; lc.addSubview(list)
        val fy = 612.0 - 146
        val tools: List<Triple<String, String, () -> Unit>> = listOf(Triple("Su", "ed.up", { moveUp() }), Triple("Giù", "ed.down", { moveDown() }),
            Triple("Duplica", "ed.dup", { duplicate() }), Triple("Elimina", "ed.delphase", { deletePhase() }))
        val tw = (L - 20 - 3 * 6) / 4.0; var i = 0
        for (tl in tools) {
            val b = CtlButton.buttonWithTitle(tl.first, if (i == 3) CtlButtonTinted else CtlButtonQuiet, CTL_RED, CGRectMake(10 + i * (tw + 6), fy, tw, 38)) { tl.third() }
            b.titleLabel.font = UIFont.systemFontOfSize(13); b.accessibilityIdentifier = tl.second; lc.addSubview(b); i++
        }
        val add = CtlButton.buttonWithTitle("+ Aggiungi una fase", CtlButtonTinted, CTL_TINT, CGRectMake(10, fy + 46, L - 20, 42)) { addPhase() }
        add.titleLabel.font = UIFont.boldSystemFontOfSize(14); add.accessibilityIdentifier = "ed.add"; lc.addSubview(add)
        val snap = CtlButton.buttonWithTitle("+ Fase dai valori attuali del paziente", CtlButtonTinted, CTL_GREEN, CGRectMake(10, fy + 94, L - 20, 42)) { addSnapshot() }
        snap.titleLabel.font = UIFont.boldSystemFontOfSize(13); snap.accessibilityIdentifier = "ed.snap"; lc.addSubview(snap)

        val fc = CtlCard(CGRectMake(L + 28, 140, W - L - 44, 612)); fc.clipsToBounds = true; view.addSubview(fc)
        form = CtlScrollView(fc.bounds); form.keyboardDismissMode = UIScrollViewKeyboardDismissModeOnDrag; fc.addSubview(form)
        buildForm()
        reloadList(); loadPhase()
        // ANDROID: niente UIKeyboardWillShow/WillHideNotification — sul tablet la finestra non si ridimensiona (adjustNothing)
    }

    private fun buildForm() {
        val W = form.bounds.size.width; val x = 18.0; val w = W - 36; var y = 14.0
        form.addSubview(CtlLabel(CGRectMake(x, y, 300, 16), "NOME DELLA FASE", 11.0, true, CTL_INK2))
        form.addSubview(CtlLabel(CGRectMake(x + 300, y, w - 300, 16), "TEMPO DI TRANSIZIONE (quanto ci mette ad arrivare ai valori)", 11.0, true, CTL_INK2))
        y += 20
        phName = field(CGRectMake(x, y, 284, 40), "es. Peggiora", form, "ed.phname")
        phName.maxLength = 40                                              // textField:shouldChangeCharactersInRange:
        dur = UISegmentedControl(listOf("Subito", "10 s", "30 s", "1 min", "2 min", "5 min"))
        dur.frame = CGRectMake(x + 300, y + 4, w - 300, 32); dur.tintColor = CTL_TINT; dur.accessibilityIdentifier = "ed.dur"
        dur.addTarget(UIControlEventValueChanged) { durChanged() }
        form.addSubview(dur)
        y += 52
        form.addSubview(CtlLabel(CGRectMake(x, y, w, 16), "RITMO", 11.0, true, CTL_INK2)); y += 20
        val rhs = listOf("", "sinus", "sinus_tachycardia", "sinus_bradycardia", "afib", "vt_pulse", "vt", "vf", "asystole", "pea")
        val bw = (w - 4 * 6) / 5.0; var i = 0
        for (r in rhs) {
            val b = CtlButton.buttonWithTitle(if (r.isNotEmpty()) CtlRhythmName(r) else "Non cambia", CtlButtonQuiet, null, CGRectMake(x + (i % 5) * (bw + 6), y + (i / 5) * 48, bw, 42)) { rhythmTapped(it) }
            b.titleLabel.font = UIFont.boldSystemFontOfSize(12.5); b.accessibilityIdentifier = "ed.rh." + (if (r.isNotEmpty()) r else "none")
            form.addSubview(b); rh[r] = b; i++
        }
        y += 2 * 48 + 8
        // ANDROID (regia morfologie ECG 22/11): morfologia dell'ECG della fase (ecg_morphology): non cambia, ECG normale o con
        // alterazioni (finestra con gli stessi gruppi della regia)
        form.addSubview(CtlLabel(CGRectMake(x, y, w, 16), "MORFOLOGIA ECG (resta anche se il ritmo cambia)", 11.0, true, CTL_INK2)); y += 20
        run {
            val bw = (w - 2 * 6) / 3.0
            for ((i, e) in listOf("none" to "Non cambia", "normal" to "ECG normale", "alt" to "Con alterazioni…").withIndex()) {
                val b = CtlButton.buttonWithTitle(e.second, CtlButtonQuiet, null, CGRectMake(x + i * (bw + 6), y, bw, 48)) { ecgTapped(e.first) }
                b.titleLabel.font = UIFont.boldSystemFontOfSize(13); b.accessibilityIdentifier = "ed.ecg." + e.first
                form.addSubview(b); ecgB[e.first] = b
            }
            y += 56
        }
        form.addSubview(CtlLabel(CGRectMake(x, y, w, 16), "PARAMETRI — acceso = cambia in questa fase, spento = resta com'è", 11.0, true, CTL_INK2)); y += 20
        for (d in EdParams()) {
            val k = d[0] as String
            val s = UISwitch(CGRectMake(x, y + 6, 51, 31)); s.onTintColor = CTL_TINT
            s.accessibilityIdentifier = "ed.sw.$k"
            s.addTarget(UIControlEventValueChanged) { paramSwitch(it as UISwitch) }
            form.addSubview(s); sw[k] = s
            form.addSubview(CtlLabel(CGRectMake(x + 64, y, 150, 43), d[1] as String, 15.0, false, CTL_INK))
            val v = CtlLabel(CGRectMake(x + 214, y, 70, 43), "", 22.0, true, CTL_INK); v.font = CtlDigits(22.0, true); v.textAlignment = NSTextAlignmentRight; form.addSubview(v); `val`[k] = v
            val minus = CtlButton.buttonWithTitle("−", CtlButtonQuiet, null, CGRectMake(x + 298, y + 2, 44, 39)) { stepTapped(it) }
            val plus = CtlButton.buttonWithTitle("+", CtlButtonQuiet, null, CGRectMake(x + w - 44, y + 2, 44, 39)) { stepTapped(it) }
            minus.titleLabel.font = UIFont.systemFontOfSize(24); plus.titleLabel.font = minus.titleLabel.font
            minus.accessibilityIdentifier = "ed.minus.$k"; plus.accessibilityIdentifier = "ed.plus.$k"
            form.addSubview(minus); form.addSubview(plus)
            // ANDROID (righello a zone 22/11): righello a zone compatto (senza tacche con nome: è un'impostazione della fase) al posto
            // del cursore: stesse zone cliniche della regia
            val l = CtlRuler(CGRectMake(x + 352, y + 1, w - 352 - 54, 41), CtlRulerSpec(k, edDouble(d[2]), edDouble(d[3]), edDouble(d[4]), edInt(d[5])), CTL_TINT, true)
            l.accessibilityIdentifier = "ed.sl.$k"
            l.onChange = { v, _ -> paramSlid(k, v) }
            form.addSubview(l); sl[k] = l
            y += 45
        }
        y += 8
        form.addSubview(CtlLabel(CGRectMake(x, y, w, 16), "NOTA PER L'ISTRUTTORE (quando passare alla fase successiva)", 11.0, true, CTL_INK2)); y += 20
        phNote = field(CGRectMake(x, y, w, 40), "es. Avanzare quando hanno somministrato l'ossigeno", form, "ed.phnote")
        phNote.maxLength = 200                                             // textField:shouldChangeCharactersInRange:
        y += 54
        form.contentSize = CGSizeMake(W, y)
    }

    // MARK: - Dati <-> interfaccia

    private fun phase(): MutableMap<String, Any?> = phases[maxOf(0, minOf(selIdx, phases.size - 1))]
    private fun defFor(k: String): List<Any>? { for (d in EdParams()) if (d[0] == k) return d; return null }
    private fun isExtra(k: String): Boolean = k == "temperature_c" || k == "crt_s"
    private fun valueOf(k: String, `in`: Map<String, Any?>): Number? = (if (isExtra(k)) `in`[k] else (`in`["params"] as? Map<*, *>)?.get(k)) as? Number
    @Suppress("UNCHECKED_CAST")
    private fun set(v: Number?, key: String) {
        val k = key
        val p = phase()
        if (isExtra(k)) { if (v != null) p[k] = v else p.remove(k) }
        else { val pp = p["params"] as MutableMap<String, Any?>; if (v != null) pp[k] = v else pp.remove(k) }
    }

    private fun loadPhase() {
        val p = phase()
        phName.text = p["name"] as? String; phNote.text = p["note"] as? String
        val di = EdDurations().indexOf(edInt(p["duration_s"]))
        dur.selectedSegmentIndex = if (di < 0) UISegmentedControlNoSegment else di
        val cur = p["rhythm"] as? String ?: ""
        for (r in rh.keys) rh[r]!!.setStyle(if (r == cur) CtlButtonFilled else CtlButtonQuiet, CTL_TINT)
        // ANDROID (regia morfologie ECG 22/11)
        val em = p["ecg_morphology"]; val ek = if (em == null) "none" else if (em == "normal") "normal" else "alt"
        for ((k, b) in ecgB) {
            b.setStyle(if (k == ek) CtlButtonFilled else CtlButtonQuiet, CTL_TINT)
            val t = if (k == "alt") (if (ek == "alt") "ECG: " + CtlEcg.shortOf(em) + " ›" else "Con alterazioni…") else (if (k == "none") "Non cambia" else "ECG normale")
            if (b.titleForState(UIControlStateNormal) != t) b.setTitle(t, UIControlStateNormal)
        }
        for (d in EdParams()) {
            val k = d[0] as String; val v = valueOf(k, p)
            sw[k]!!.isOn = v != null; sl[k]!!.enabledRuler = v != null; sl[k]!!.alpha = if (v != null) 1f else 0.35f
            if (v != null) sl[k]!!.showValue(v.toDouble())
            `val`[k]!!.text = if (v != null) Fmt(v, edInt(d[5])) else "—"; `val`[k]!!.textColor = if (v != null) CTL_INK else CTL_INK3
        }
    }

    private fun reloadList() {
        for (v in list.subviews) v.removeFromSuperview()
        var y = 4.0; var i = 0
        for (p in phases) {
            val on = i == selIdx
            val row = UIControl(CGRectMake(6, y, kEdListW - 12, 74))
            row.backgroundColor = if (on) CTL_CARD2 else UIColor.clearColor; row.layer.cornerRadius = 9.0; row.uiTag = i
            row.layer.borderWidth = if (on) 2.0 else 0.0; row.layer.borderColor = CTL_TINT.CGColor
            row.accessibilityIdentifier = String.format(Locale.US, "ed.row.%d", i)
            row.addTarget(UIControlEventTouchUpInside) { rowTapped(it) }
            val pn = p["name"] as? String
            val nm = if (!pn.isNullOrEmpty()) pn else String.format(Locale.US, "Fase %d", i + 1)
            val t = CtlLabel(CGRectMake(12, 6, kEdListW - 36, 22), String.format(Locale.US, "%d.  %s", i + 1, nm), 15.0, true, CTL_INK); t.userInteractionEnabled = false; row.addSubview(t)
            @Suppress("UNCHECKED_CAST")
            val s = CtlLabel(CGRectMake(12, 27, kEdListW - 36, 42), CtlDescribeBlock(p["rhythm"] as? String, p["params"] as? Map<String, Any?>, p), 11.0, false, CTL_INK2); s.numberOfLines = 3; s.userInteractionEnabled = false; row.addSubview(s)
            list.addSubview(row)
            y += 78; i++
        }
        list.contentSize = CGSizeMake(kEdListW, y + 4)
        count.text = String.format(Locale.US, "%d di %d al massimo", phases.size, kMaxPhases)
    }

    // MARK: - Azioni

    private fun rowTapped(r: UIControl) { view.endEditing(true); selIdx = r.uiTag; reloadList(); loadPhase() }
    private fun textChanged(t: UITextField) {
        if (t === phName) { phase()["name"] = t.text ?: ""; reloadList() }
        else if (t === phNote) phase()["note"] = t.text ?: ""
        error.text = ""
    }
    private fun textViewDidChange(tv: UITextView) { notesHint.hidden = (tv.text?.length ?: 0) > 0 }
    // textView:shouldChangeTextInRange:replacementText: → notes.onReturn + notes.maxLength (600)
    private fun textFieldShouldReturn(t: UITextField): Boolean { t.resignFirstResponder(); return true }
    // textField:shouldChangeCharactersInRange:replacementString: → maxLength (nome 60, nome della fase 40, nota 200)
    private fun durChanged() { phase()["duration_s"] = EdDurations()[dur.selectedSegmentIndex] }
    private fun rhythmTapped(b: CtlButton) {
        val r = (b.accessibilityIdentifier ?: "").substring(6)
        if (r == "none") phase().remove("rhythm") else phase()["rhythm"] = r
        loadPhase(); reloadList()
    }
    // ANDROID (regia morfologie ECG 22/11)
    private fun ecgTapped(k: String) {
        view.endEditing(true)
        val p = phase()
        when (k) {
            "none" -> p.remove("ecg_morphology")
            "normal" -> p["ecg_morphology"] = "normal"
            else -> {
                val pk = CtlEcgPicker(p["ecg_morphology"] as? Map<*, *> ?: current["ecg_morphology"], CtlEcg.parse(current["ecg_morphology"]))
                val me = this
                pk.onDone = { v -> if (v == "normal") p["ecg_morphology"] = "normal" else p["ecg_morphology"] = v; me.loadPhase(); me.reloadList() }
                presentViewController(pk, true, null)
                return
            }
        }
        error.text = ""; loadPhase(); reloadList()
    }
    private fun paramSwitch(sw: UISwitch) {
        val k = (sw.accessibilityIdentifier ?: "").substring(6)
        if (sw.isOn) {
            val cur = if (isExtra(k)) current[k] else (current["params"] as? Map<*, *>)?.get(k)     // si parte dal valore attuale del paziente
            set(if (cur is Number) cur else defFor(k)!![6] as Number, k)
        } else set(null, k)
        loadPhase(); reloadList()
    }
    private fun snap(v0: Double, key: String): Number {
        val k = key
        var v = v0
        val d = defFor(k)!!; val st = edDouble(d[4])
        v = Math.round(v / st) * st; v = maxOf(edDouble(d[2]), minOf(edDouble(d[3]), v))
        return if (edInt(d[5]) > 0) Math.round(v * 10) / 10.0 else Math.round(v)
    }
    private fun paramSlid(k: String, v0: Double) {
        val v = snap(v0, k)
        set(v, k)
        `val`[k]!!.text = Fmt(v, edInt(defFor(k)!![5]))
        val seq = ++reloadListSeq
        Uk.after(0.25) { if (seq == reloadListSeq) reloadList() }
    }
    private fun stepTapped(b: CtlButton) {
        val id = b.accessibilityIdentifier ?: ""
        val plus = id.startsWith("ed.plus.")
        val k = id.substring(if (plus) 8 else 9)
        val v = valueOf(k, phase())
        if (v == null) { sw[k]!!.isOn = true; paramSwitch(sw[k]!!); return }
        set(snap(v.toDouble() + (if (plus) 1 else -1) * edDouble(defFor(k)!![4]), k), k)
        loadPhase(); reloadList()
    }

    private fun room(): Boolean { if (phases.size >= kMaxPhases) { error.text = String.format(Locale.US, "Uno scenario può avere al massimo %d fasi.", kMaxPhases); return false }; return true }
    private fun insert(p: MutableMap<String, Any?>) { view.endEditing(true); phases.add(minOf(selIdx + 1, phases.size), p); selIdx = minOf(selIdx + 1, phases.size - 1); reloadList(); loadPhase() }
    private fun addPhase() { if (room()) { val p = blankPhase(); p.remove("rhythm"); insert(p) } }
    private fun addSnapshot() {
        if (!room()) return
        val p = mutablePhase(current)
        p["name"] = ""; p["note"] = ""; p["duration_s"] = 30
        insert(p)
    }
    private fun duplicate() {
        if (room()) {
            val p = mutablePhase(phase())
            val n = p["name"] as? String
            if (!n.isNullOrEmpty()) p["name"] = (n + " (copia)").substring(0, minOf(40, n.length + 8))
            insert(p)
        }
    }
    private fun deletePhase() {
        if (phases.size <= 1) { error.text = "Lo scenario deve avere almeno una fase."; return }
        phases.removeAt(selIdx); selIdx = minOf(selIdx, phases.size - 1); reloadList(); loadPhase()
    }
    private fun moveUp() { if (selIdx > 0) { Collections.swap(phases, selIdx, selIdx - 1); selIdx--; reloadList() } }
    private fun moveDown() { if (selIdx + 1 < phases.size) { Collections.swap(phases, selIdx, selIdx + 1); selIdx++; reloadList() } }

    private fun cancel() { view.endEditing(true); dismissViewControllerAnimated(true, null) }
    fun showError(error: String?) { saving = false; this.error.text = error ?: "" }

    // ANDROID (audit 21/11): doppio tocco su «Salva sul monitor» con uno scenario nuovo = DUE scenari uguali sul monitor.
    // Un salvataggio alla volta: si riapre alla risposta (errore) o si chiude l'editor (salvato).
    private var saving = false
    private fun save() {
        if (saving) return
        view.endEditing(true)
        // stringByTrimmingCharactersInSet:whitespaceCharacterSet (spazi e tabulazioni, non gli a capo)
        val name = (this.name.text ?: "").trim { it == '\t' || Character.getType(it) == Character.SPACE_SEPARATOR.toInt() }
        if (name.isEmpty()) { error.text = "Manca il nome dello scenario."; this.name.becomeFirstResponder(); return }
        val out = ArrayList<Map<String, Any?>>(); var i = 0
        for (p in phases) {
            i++
            val pr = p["params"] as? Map<*, *> ?: emptyMap<String, Any?>()
            if (pr.isEmpty() && p["rhythm"] == null && p["temperature_c"] == null && p["crt_s"] == null && p["ecg_morphology"] == null) { selIdx = i - 1; reloadList(); loadPhase(); error.text = String.format(Locale.US, "La fase %d è vuota: scegli un ritmo, la morfologia ECG o accendi almeno un parametro.", i); return }
            val s = pr["systolic_mmhg"]; val d = pr["diastolic_mmhg"]
            if (s != null && d != null && edInt(d) >= edInt(s)) { selIdx = i - 1; reloadList(); loadPhase(); error.text = String.format(Locale.US, "Fase %d: la diastolica deve essere più bassa della sistolica.", i); return }
            val q = linkedMapOf<String, Any?>("name" to (p["name"] ?: ""), "note" to (p["note"] ?: ""), "duration_s" to (p["duration_s"] ?: 0), "params" to pr)
            for (k in listOf("rhythm", "temperature_c", "crt_s", "ecg_morphology")) if (p[k] != null) q[k] = p[k]      // ANDROID (regia morfologie ECG 22/11): + ecg_morphology
            out.add(q)
        }
        val sc = linkedMapOf<String, Any?>("name" to name, "notes" to (notes.text ?: ""), "phases" to out)
        if (sid != null) sc["id"] = sid
        error.text = "Salvataggio sul monitor…"
        saving = true
        onSave?.invoke(sc)
    }

    private fun deleteScenario() {
        if (CACurrentMediaTime() < delArmed) { onDelete?.invoke(sid ?: ""); return }
        delArmed = CACurrentMediaTime() + 4
        del?.setTitle("Tocca di nuovo: elimina", UIControlStateNormal); del?.setStyle(CtlButtonFilled, CTL_RED)
        Uk.after(4.0) {
            del?.setTitle("Elimina scenario", UIControlStateNormal); del?.setStyle(CtlButtonTinted, CTL_RED)
        }
    }

    // la tastiera non deve coprire il campo che si sta scrivendo (la nota è in fondo al modulo)
    // ANDROID: keyboard: omesso — sul tablet Android la finestra non si ridimensiona (adjustNothing) e la tastiera
    // non manda UIKeyboardWillShowNotification; contentInset/scrollRectToVisible del modulo restano quelli di partenza.
}
