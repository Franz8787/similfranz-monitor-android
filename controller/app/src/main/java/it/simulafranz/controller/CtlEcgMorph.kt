// ANDROID (regia morfologie ECG 22/11) — file nuovo, solo Android.
// Morfologia dell'ECG nella regia del controller: dati e nomi clinici (uguali alla web regia), combinazioni che il monitor rifiuta
// (spente con il motivo), un pannello a gruppi riusato nella regia (pannello «Morfologia ECG» della tessera Ritmo · FC) e nell'editor
// degli scenari (finestra «Morfologia ECG della fase»).
// API del cuore: {"command":"set_ecg_morphology","value":{…campi parziali…}} oppure "normal"; "ecg_morphology" anche dentro
// "transition" (Prepara / Applica, fasi degli scenari). Stato: ecg_morphology {st, st_mm, bbb, av_block, pr_ms, escape, extra,
// interpretation, label}, av_block_active, atrial_rate_bpm, ecg12_last {seq, …}.
package it.simulafranz.controller

import it.simulafranz.core.*
import it.simulafranz.uk.*
import java.util.Locale

val ECG_ST = listOf("none" to "Nessuna alterazione", "stemi_inferior" to "STEMI inferiore", "stemi_inferoposterior" to "STEMI infero-posteriore",
    "stemi_anterior" to "STEMI anteriore", "stemi_lateral" to "STEMI laterale", "stemi_posterior" to "STEMI posteriore isolato",
    "nstemi_depression" to "Sottoslivellamento ST (NSTEMI)", "left_main" to "Tronco comune (ST ↓ diffuso, ↑ aVR)", "wellens" to "Wellens",
    "de_winter" to "de Winter", "pericarditis" to "Pericardite")
val ECG_BBB = listOf("none" to "Nessuno", "rbbb" to "Blocco di branca destro", "lbbb" to "Blocco di branca sinistro", "lafb" to "Emiblocco anteriore sinistro",
    "rbbb_lafb" to "Bifascicolare (BBD + EAS)")
val ECG_AV = listOf("none" to "Conduzione normale", "first" to "BAV 1° grado", "mobitz1" to "BAV 2° Mobitz I (Wenckebach)", "mobitz2" to "BAV 2° Mobitz II",
    "2to1" to "BAV 2° 2:1", "high_grade" to "BAV avanzato (3:1)", "third" to "BAV 3° grado (completo)")
val ECG_EXTRA = listOf("hyperkalemia" to "Iperkaliemia", "hyperkalemia_severe" to "Iperkaliemia grave", "hypokalemia" to "Ipokaliemia", "long_qt" to "QT lungo",
    "lvh" to "Ipertrofia ventr. sinistra", "wpw" to "Preeccitazione (WPW)", "brugada" to "Brugada tipo 1", "pe" to "Embolia polmonare (S1Q3T3)",
    "hypothermia" to "Ipotermia (onde J)", "low_voltage" to "Bassi voltaggi con alternanza")
private val ECG_SHORT = mapOf("stemi_inferior" to "STEMI inf", "stemi_inferoposterior" to "STEMI inf-post", "stemi_anterior" to "STEMI ant", "stemi_lateral" to "STEMI lat",
    "stemi_posterior" to "STEMI post", "nstemi_depression" to "ST ↓", "left_main" to "ST ↓ diffuso", "wellens" to "Wellens", "de_winter" to "de Winter", "pericarditis" to "Pericardite",
    "rbbb" to "BBD", "lbbb" to "BBS", "lafb" to "EAS", "rbbb_lafb" to "BBD+EAS",
    "first" to "BAV 1°", "mobitz1" to "Mobitz I", "mobitz2" to "Mobitz II", "2to1" to "BAV 2:1", "high_grade" to "BAV 3:1", "third" to "BAV 3°",
    "hyperkalemia" to "iper-K", "hyperkalemia_severe" to "iper-K grave", "hypokalemia" to "ipo-K", "long_qt" to "QT lungo", "lvh" to "IVS", "wpw" to "WPW",
    "brugada" to "Brugada", "pe" to "EP", "hypothermia" to "ipotermia", "low_voltage" to "bassi voltaggi")

/** Morfologia (immutabile) come la vede la regia */
data class CtlMorph(val st: String = "none", val stMm: Double = 0.0, val bbb: String = "none", val av: String = "none", val pr: Int = 0,
                    val esc: String = "ventricular", val extra: List<String> = emptyList(), val interp: Boolean = true) {
    val isNormal: Boolean get() = st == "none" && bbb == "none" && av == "none" && extra.isEmpty()
    fun get(f: String): String = when (f) { "st" -> st; "bbb" -> bbb; "av_block" -> av; else -> "" }
}

object CtlEcg {
    fun of(st: Map<String, Any?>?): CtlMorph = parse(st?.get("ecg_morphology"))
    /** da un valore dello stato o di una fase (oggetto, "normal", null) */
    fun parse(v: Any?): CtlMorph {
        val m = v as? Map<*, *> ?: return CtlMorph()
        fun s(k: String, d: String) = (m[k] as? String)?.takeIf { it.isNotEmpty() } ?: d
        return CtlMorph(s("st", "none"), (m["st_mm"] as? Number)?.toDouble() ?: 0.0, s("bbb", "none"), s("av_block", "none"),
            (m["pr_ms"] as? Number)?.toInt() ?: 0, s("escape", "ventricular"), (m["extra"] as? List<*>)?.mapNotNull { it as? String } ?: emptyList(),
            m["interpretation"] != false)
    }
    /** base + una modifica parziale (mappa dei campi dell'API) o "normal" */
    fun merge(base: CtlMorph, p: Any?): CtlMorph {
        if (p == "normal") return CtlMorph(interp = base.interp)
        val m = p as? Map<*, *> ?: return base
        var r = base
        if (m.containsKey("st")) { val v = m["st"] as? String ?: "none"; r = r.copy(st = v, stMm = if (v != base.st) 0.0 else r.stMm) }
        if (m.containsKey("st_mm")) r = r.copy(stMm = (m["st_mm"] as? Number)?.toDouble() ?: 0.0)
        if (m.containsKey("bbb")) r = r.copy(bbb = m["bbb"] as? String ?: "none")
        if (m.containsKey("av_block")) r = r.copy(av = m["av_block"] as? String ?: "none")
        if (m.containsKey("pr_ms")) r = r.copy(pr = (m["pr_ms"] as? Number)?.toInt() ?: 0)
        if (m.containsKey("escape")) r = r.copy(esc = m["escape"] as? String ?: "ventricular")
        if (m.containsKey("extra")) r = r.copy(extra = ECG_EXTRA.map { it.first }.filter { ((m["extra"] as? List<*>) ?: emptyList<Any?>()).contains(it) })
        if (m.containsKey("interpretation")) r = r.copy(interp = m["interpretation"] != false)
        if (r.av != "first") r = r.copy(pr = 0)
        return r
    }
    /** valore da mandare per una fase o uno stato: "normal" oppure i soli campi che contano */
    fun payload(m: CtlMorph): Any {
        if (m.isNormal) return "normal"
        val o = linkedMapOf<String, Any?>("st" to m.st, "bbb" to m.bbb, "av_block" to m.av, "extra" to ArrayList(m.extra))
        if (m.stMm > 0) o["st_mm"] = m.stMm
        if (m.av == "first" && m.pr > 0) o["pr_ms"] = m.pr
        if (m.av == "third") o["escape"] = m.esc
        return o
    }
    fun name(list: List<Pair<String, String>>, v: String): String = list.firstOrNull { it.first == v }?.second ?: v
    /** «STEMI inf · BAV 1°» (al massimo tre voci, poi «+N») */
    fun short(m: CtlMorph, mm: Boolean = true): String {
        if (m.isNormal) return "normale"
        val p = ArrayList<String>()
        if (m.st != "none") p.add((ECG_SHORT[m.st] ?: m.st) + (if (mm && m.stMm > 0) " " + String.format(Locale.ITALY, "%s mm", trimNum(m.stMm)) else ""))
        if (m.bbb != "none") p.add(ECG_SHORT[m.bbb] ?: m.bbb)
        if (m.av != "none") p.add(ECG_SHORT[m.av] ?: m.av)
        for (x in m.extra) p.add(ECG_SHORT[x] ?: x)
        return if (p.size > 3) p.take(3).joinToString(" · ") + " +" + (p.size - 3) else p.joinToString(" · ")
    }
    fun shortOf(v: Any?): String = if (v == "normal") "ECG normale" else short(parse(v))
    fun trimNum(v: Double): String = if (v == Math.floor(v)) String.format(Locale.US, "%d", v.toLong()) else String.format(Locale.ITALY, "%.1f", v)
    /** motivo per cui la scelta (f = st|bbb|av_block|extra, v) non è permessa con la morfologia m; null = permessa */
    fun why(m: CtlMorph, f: String, v: String): String? {
        val ex = m.extra
        if (f == "bbb" && v != "none" && ex.contains("wpw")) return "con la preeccitazione (WPW) niente blocchi di branca"
        if (f == "av_block" && v != "none" && ex.contains("wpw")) return "con la preeccitazione (WPW) niente blocchi AV"
        if (f == "extra" && !ex.contains(v)) {
            if (v == "wpw" && m.bbb != "none") return "la preeccitazione (WPW) non si combina con un blocco di branca"
            if (v == "wpw" && m.av != "none") return "la preeccitazione (WPW) non si combina con un blocco AV"
            if (v == "hyperkalemia" && ex.contains("hyperkalemia_severe")) return "c'è già l'iperkaliemia grave"
            if (v == "hyperkalemia_severe" && ex.contains("hyperkalemia")) return "c'è già l'iperkaliemia"
            if (v == "hypokalemia" && (ex.contains("hyperkalemia") || ex.contains("hyperkalemia_severe"))) return "ipo- e iperkaliemia non stanno insieme"
            if ((v == "hyperkalemia" || v == "hyperkalemia_severe") && ex.contains("hypokalemia")) return "ipo- e iperkaliemia non stanno insieme"
        }
        return null
    }
    fun errIt(e: String?): String {
        val t = e ?: return "comando non eseguito"
        if (t.contains("wpw cannot be combined with a bundle")) return "la preeccitazione (WPW) non si combina con un blocco di branca"
        if (t.contains("wpw cannot be combined with an AV")) return "la preeccitazione (WPW) non si combina con un blocco AV"
        if (t.contains("hyperkalemia or hyperkalemia_severe")) return "scegli iperkaliemia oppure iperkaliemia grave"
        if (t.contains("mutually exclusive")) return "ipo- e iperkaliemia non stanno insieme"
        return t.replace("Non eseguito: ", "")
    }
}

/**
 * Pannello a gruppi della morfologia (dentro una vista che scorre). now() = com'è adesso (pieno con «●»), eff() = come sarà
 * (diverso da now in «Prepara»: contorno tratteggiato giallo + IN ATTESA), pick(parte, chiave, tasto) = scelta dell'utente
 * (parte: mappa dei campi dell'API oppure "normal"). Ritorna l'altezza usata; refresh() ridisegna gli stati.
 */
class CtlEcgPanel(val v: UIView, val W: Double, val now: () -> CtlMorph, val eff: () -> CtlMorph, val pick: (Any, String, CtlButton?) -> Unit,
                  val showInterp: Boolean, val onInterp: ((Boolean) -> Unit)?) {
    val buttons = ArrayList<CtlButton>()
    private val opt = ArrayList<Triple<String, String, CtlButton>>()          // gruppo, valore, tasto
    private var mmRow: UIView? = null; private var mmLabel: UILabel? = null; private var mmMinus: CtlButton? = null; private var mmPlus: CtlButton? = null
    private var prRow: UIView? = null; private var prLabel: UILabel? = null; private var prMinus: CtlButton? = null; private var prPlus: CtlButton? = null
    private var escRow: UIView? = null; private val escBtns = LinkedHashMap<String, CtlButton>()
    private var whyL: UILabel? = null; var interpSw: UISwitch? = null; var height = 0.0

    fun build(y0: Double): Double {
        var y = y0
        y = group(y, "TRATTO ST (territorio)", "st", ECG_ST, 3)
        // ampiezza nella derivazione più alterata (0 = di serie del quadro)
        run {
            val r = UIView(CGRectMake(0, y, W, 48)); v.addSubview(r); mmRow = r
            r.addSubview(CtlLabel(CGRectMake(0, 0, W - 250, 48), "Ampiezza nella derivazione più alterata", 14.0, false, CTL_INK2))
            val l = CtlLabel(CGRectMake(W - 186, 0, 122, 48), "", 17.0, true, CTL_INK); l.textAlignment = NSTextAlignmentCenter; l.adjustsFontSizeToFitWidth = true; l.minimumScaleFactor = 0.7; r.addSubview(l); mmLabel = l
            mmMinus = stepBtn(r, CGRectMake(W - 244, 2, 56, 44), "−", "ecg.mm.meno") { step("mm", -1) }
            mmPlus = stepBtn(r, CGRectMake(W - 56, 2, 56, 44), "+", "ecg.mm.piu") { step("mm", 1) }
            y += 56
        }
        y = group(y, "BLOCCHI DI BRANCA ED EMIBLOCCHI", "bbb", ECG_BBB, 3)
        y = group(y, "BLOCCHI ATRIOVENTRICOLARI (la FC impostata resta quella dei ventricoli)", "av_block", ECG_AV, 3)
        run {     // PR del BAV 1°
            val r = UIView(CGRectMake(0, y, W, 48)); v.addSubview(r); prRow = r
            r.addSubview(CtlLabel(CGRectMake(0, 0, W - 250, 48), "PR del BAV 1° grado", 14.0, false, CTL_INK2))
            val l = CtlLabel(CGRectMake(W - 186, 0, 122, 48), "", 17.0, true, CTL_INK); l.textAlignment = NSTextAlignmentCenter; r.addSubview(l); prLabel = l
            prMinus = stepBtn(r, CGRectMake(W - 244, 2, 56, 44), "−", "ecg.pr.meno") { step("pr", -1) }
            prPlus = stepBtn(r, CGRectMake(W - 56, 2, 56, 44), "+", "ecg.pr.piu") { step("pr", 1) }
        }
        run {     // scappamento del BAV 3° (stessa riga del PR: le due righe non si vedono mai insieme)
            val r = UIView(CGRectMake(0, y, W, 48)); v.addSubview(r); escRow = r
            r.addSubview(CtlLabel(CGRectMake(0, 0, 110, 48), "Scappamento", 14.0, false, CTL_INK2))
            val bw = (W - 118 - 8) / 2
            for ((i, e) in listOf("ventricular" to "Ventricolare · 35/min", "junctional" to "Giunzionale · 45/min").withIndex()) {
                val b = CtlButton.buttonWithTitle(e.second, CtlButtonQuiet, null, CGRectMake(118 + i * (bw + 8), 0, bw, 48), null)
                b.titleLabel.font = UIFont.boldSystemFontOfSize(14); b.maxLines = 1; b.accessibilityIdentifier = "ecg.escape." + e.first
                b.onTap = { if (eff().esc != e.first) pick(mapOf("escape" to e.first), "esc_" + e.first, b) }
                r.addSubview(b); escBtns[e.first] = b; buttons.add(b)
            }
            y += 56
        }
        y = group(y, "ALTRE ALTERAZIONI (anche più di una)", "extra", ECG_EXTRA, 2)
        val wl = CtlLabel(CGRectMake(0, y, W, 34), "", 12.0, false, CTL_INK2); wl.numberOfLines = 2; wl.adjustsFontSizeToFitWidth = true; wl.minimumScaleFactor = 0.8
        v.addSubview(wl); whyL = wl; y += 38
        if (showInterp) {
            val r = UIView(CGRectMake(0, y, W, 56)); r.backgroundColor = CTL_CARD2; r.layer.cornerRadius = 10.0; v.addSubview(r)
            r.addSubview(CtlLabel(CGRectMake(14, 6, W - 100, 22), "Interpretazione automatica sul 12D", 15.0, false, CTL_INK))
            val d = CtlLabel(CGRectMake(14, 28, W - 100, 20), "il referto stampato sulla carta a 12 derivazioni", 11.5, false, CTL_INK3)
            d.adjustsFontSizeToFitWidth = true; d.minimumScaleFactor = 0.8; r.addSubview(d)
            val s = UISwitch(CGRectMake(W - 65, 12, 51, 31)); s.onTintColor = CTL_TINT; s.accessibilityIdentifier = "ecg.interp"; r.addSubview(s); interpSw = s
            s.addTarget(UIControlEventValueChanged) { onInterp?.invoke((it as UISwitch).isOn) }
            y += 64
        }
        height = y - y0
        return y
    }
    private fun stepBtn(r: UIView, f: CGRect, t: String, id: String, a: () -> Unit): CtlButton {
        val b = CtlButton.buttonWithTitle(t, CtlButtonQuiet, null, f, null); b.titleLabel.font = UIFont.systemFontOfSize(24); b.accessibilityIdentifier = id
        b.onTap = a; r.addSubview(b); buttons.add(b); return b
    }
    private fun step(k: String, dir: Int) {
        val e = eff()
        if (k == "mm") {
            val vals = listOf(0.0, 0.5, 1.0, 1.5, 2.0, 2.5, 3.0, 4.0, 5.0, 6.0, 8.0)
            var i = vals.indexOfFirst { Math.abs(it - e.stMm) < 0.01 }; if (i < 0) i = 0
            val n = Math.max(0, Math.min(vals.size - 1, i + dir)); if (n == i) return
            pick(mapOf("st_mm" to (if (vals[n] > 0) vals[n] else null)), "mm", if (dir < 0) mmMinus else mmPlus)
        } else {
            val cur = if (e.pr > 0) e.pr else 280
            val n = Math.max(210, Math.min(400, cur + dir * 10)); if (n == cur && e.pr > 0) return
            pick(mapOf("pr_ms" to n), "pr", if (dir < 0) prMinus else prPlus)
        }
    }
    private fun group(y0: Double, title: String, f: String, list: List<Pair<String, String>>, cols: Int): Double {
        var y = y0
        val t = CtlLabel(CGRectMake(0, y, W, 16), title, 11.0, true, CTL_INK2); t.adjustsFontSizeToFitWidth = true; t.minimumScaleFactor = 0.8; v.addSubview(t); y += 20
        val bw = (W - (cols - 1) * 8) / cols; val bh = 52.0
        for ((i, o) in list.withIndex()) {
            val b = CtlButton.buttonWithTitle(o.second, CtlButtonQuiet, null, CGRectMake((i % cols) * (bw + 8), y + (i / cols) * (bh + 6), bw, bh), null)
            b.titleLabel.font = UIFont.boldSystemFontOfSize(14); b.accessibilityIdentifier = "ecg.$f.${o.first}"
            b.onTap = { tapped(f, o.first, b) }
            v.addSubview(b); opt.add(Triple(f, o.first, b)); buttons.add(b)
        }
        return y + ((list.size + cols - 1) / cols) * (bh + 6) + 8
    }
    private fun tapped(f: String, value: String, b: CtlButton) {
        val e = eff()
        if (CtlEcg.why(e, f, value) != null) return
        if (f == "extra") {
            val ex = ArrayList(e.extra); if (ex.contains(value)) ex.remove(value) else ex.add(value)
            pick(mapOf("extra" to ECG_EXTRA.map { it.first }.filter { ex.contains(it) }), "extra_$value", b)
        } else {
            if (e.get(f) == value) return
            pick(mapOf(f to value), "${f}_$value", b)
        }
    }
    fun refresh(locked: Boolean = false) {
        val n = now(); val e = eff()
        val whys = ArrayList<String>()
        for ((f, value, b) in opt) {
            val cur = if (f == "extra") n.extra.contains(value) else n.get(f) == value
            val want = if (f == "extra") e.extra.contains(value) else e.get(f) == value
            val why = CtlEcg.why(e, f, value)
            if (why != null && !whys.contains(why)) whys.add(why)
            b.isEnabled = why == null
            b.setStyle(if (cur) CtlButtonFilled else CtlButtonQuiet, CTL_TINT)
            val pend = want != cur
            b.layer.borderWidth = if (pend) 2.0 else (if (cur) 0.0 else 1.0)
            b.layer.borderColor = (if (pend) CTL_YELLOW else UIColor.colorWithWhite(1, 0.08)).CGColor
            val nm = CtlEcg.name(when (f) { "st" -> ECG_ST; "bbb" -> ECG_BBB; "av_block" -> ECG_AV; else -> ECG_EXTRA }, value)
            val tt = (if (cur) (if (f == "extra") "✓ " else "● ") else "") + nm
            if (b.titleForState(UIControlStateNormal) != tt) b.setTitle(tt, UIControlStateNormal)
            if (locked) b.setTag(null, CTL_AMBER, "lock") else if (pend) b.setTag(if (want) "IN ATTESA" else "TOLTA", CTL_YELLOW) else b.setTag(null, null)
        }
        mmRow?.let { r -> r.alpha = if (e.st == "none") 0.4f else 1f; r.userInteractionEnabled = e.st != "none" }
        mmLabel?.text = if (e.st == "none") "—" else if (e.stMm > 0) CtlEcg.trimNum(e.stMm) + " mm" else "di serie"
        prRow?.hidden = e.av != "first"; escRow?.hidden = e.av != "third"
        prLabel?.text = (if (e.pr > 0) e.pr else 280).toString() + " ms" + (if (e.pr > 0) "" else " (serie)")
        for ((k, b) in escBtns) {
            val on = e.esc == k; val curOn = n.av == "third" && n.esc == k
            b.setStyle(if (curOn) CtlButtonFilled else CtlButtonQuiet, CTL_TINT)
            b.layer.borderWidth = if (on != curOn) 2.0 else 1.0; b.layer.borderColor = (if (on != curOn) CTL_YELLOW else UIColor.colorWithWhite(1, 0.08)).CGColor
            val tt = (if (curOn) "● " else "") + (if (k == "ventricular") "Ventricolare · 35/min" else "Giunzionale · 45/min")
            if (b.titleForState(UIControlStateNormal) != tt) b.setTitle(tt, UIControlStateNormal)
            if (locked) b.setTag(null, CTL_AMBER, "lock") else b.setTag(null, null)
        }
        for (b in listOfNotNull(mmMinus, mmPlus, prMinus, prPlus)) if (locked) b.setTag(null, CTL_AMBER, "lock") else b.setTag(null, null)
        whyL?.text = if (whys.isEmpty()) "" else "Spente: " + whys.joinToString("; ") + "."
        interpSw?.let { if (it.isOn != n.interp) it.setOn(n.interp, false) }
    }
}

/** Finestra «Morfologia ECG della fase» dell'editor degli scenari: stessi gruppi della regia, si sceglie senza mandare comandi;
 *  «Fatto» restituisce "normal" o i campi da salvare nella fase (ecg_morphology). */
class CtlEcgPicker(initial: Any?, private val current: CtlMorph) : UIViewController() {
    var onDone: ((Any) -> Unit)? = null
    private var mm: CtlMorph = CtlEcg.parse(initial)
    private var panel: CtlEcgPanel? = null; private var sumL: UILabel? = null

    override fun viewDidLoad() {
        super.viewDidLoad()
        view.backgroundColor = CTL_BG
        val W = CTL_W
        val me = this
        val cancel = CtlButton.buttonWithTitle("Annulla", CtlButtonQuiet, null, CGRectMake(16, 12, 120, 48)) { me.dismissViewControllerAnimated(true, null) }
        cancel.accessibilityIdentifier = "edecg.cancel"; view.addSubview(cancel)
        view.addSubview(CtlLabel(CGRectMake(152, 12, W - 152 - 380, 48), "Morfologia ECG della fase", 22.0, true, CTL_INK))
        val snap = CtlButton.buttonWithTitle("Come il paziente adesso", CtlButtonTinted, CTL_GREEN, CGRectMake(W - 16 - 170 - 12 - 220, 12, 220, 48)) {
            me.mm = me.current.copy(interp = true); me.refresh() }
        snap.titleLabel.font = UIFont.boldSystemFontOfSize(14); snap.accessibilityIdentifier = "edecg.snap"; view.addSubview(snap)
        val done = CtlButton.buttonWithTitle("Fatto", CtlButtonFilled, CTL_TINT, CGRectMake(W - 16 - 170, 12, 170, 48)) {
            me.onDone?.invoke(CtlEcg.payload(me.mm)); me.dismissViewControllerAnimated(true, null) }
        done.accessibilityIdentifier = "edecg.done"; view.addSubview(done)
        val s = CtlLabel(CGRectMake(16, 66, W - 32, 22), "", 15.0, true, CTL_INK2); s.adjustsFontSizeToFitWidth = true; s.minimumScaleFactor = 0.8; view.addSubview(s); sumL = s
        val card = CtlCard(CGRectMake(16, 94, W - 32, CTL_H - 94 - 12)); card.clipsToBounds = true; view.addSubview(card)
        val sc = CtlScrollView(CGRectMake(16, 12, W - 64, CTL_H - 94 - 12 - 24)); sc.indicatorStyle = UIScrollViewIndicatorStyleWhite; card.addSubview(sc)
        val pw = W - 64 - 8
        val v = UIView(CGRectMake(0, 0, pw, 10)); sc.addSubview(v)
        val pn = CtlEcgPanel(v, pw, { me.mm }, { me.mm }, { part, _, _ -> me.mm = CtlEcg.merge(me.mm, part); me.refresh() }, false, null)
        val y = pn.build(0.0); panel = pn
        v.frame = CGRectMake(0, 0, pw, y + 8); sc.contentSize = CGSizeMake(sc.bounds.width, y + 8)
        refresh()
    }
    private fun refresh() {
        panel?.refresh()
        sumL?.text = "In questa fase l'ECG diventa: " + (if (mm.isNormal) "normale" else CtlEcg.short(mm))
    }
}
