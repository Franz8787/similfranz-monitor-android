// ANDROID (menu layout a tema 22/11, richiesta utente): pannello di scelta nel tema del controller, al posto degli action sheet
// di sistema (AlertDialog bianco/grigio di Android, che stonavano con la regia scura).
//  - CtlSheet: velo scuro su tutta la tela + carta CTL_CARD (angoli 16, bordo 1 punto a bassa opacità), titolo 20 pt, testo
//    ≥ 14 pt, voci come tessere (CtlButton, target ≥ 48 punti) in 1, 2 o 3 colonne, sezioni con titolo, scorrimento se
//    le voci non ci stanno. «Chiudi» (o «Annulla») in basso a destra; tocco fuori dalla carta = Chiudi; tasto Indietro = Chiudi.
//  - voce scelta / attuale: fondo azzurro + bordo 2 punti + «● ...» (SISTEMA_DI_SIGNIFICANTI §2, «pieno + ●»);
//    voce distruttiva: testo e bordo rossi; voce bloccata (sola osservazione): lucchetto ambra nell'angolo.
//  - di serie una voce chiude il pannello e poi esegue (come UIAlertAction); keepOpen = true: il pannello resta aperto e chi
//    lo usa mostra l'esito sulla tessera (… poi ✓/✕) e lo chiude lui (scelta del layout).
//  - modo «conferma» (alert = true): titolo, testo e i tasti affiancati; il tocco fuori NON chiude (come l'AlertDialog di
//    prima, setCanceledOnTouchOutside(false)), Indietro = Annulla: stesso flusso, solo l'aspetto nel tema.
// Il pannello si mette sopra tutto (UIViewController.host): anche sopra il piano di debriefing. CtlSheet.current serve ai
// deep link di verifica (tap?id=…) e al tasto Indietro (MainActivity).
package it.simulafranz.controller

import it.simulafranz.uk.*
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

const val CtlSheetNormal = 0
const val CtlSheetDestructive = 1
const val CtlSheetCancel = 2

class CtlSheetItem(
    val title: String,
    val sub: String? = null,
    val style: Int = CtlSheetNormal,
    var selected: Boolean = false,
    val thumb: String? = null,          // miniatura stilizzata del tipo di layout (icu, ward, defib, aed): CtlLayoutThumb
    val shot: String? = null,           // ANDROID (miniature 23/11): miniatura VERA (asset layout_thumbs/<id>.png); se manca, resta thumb
    val icon: String? = null,           // icona di CtlIcon a sinistra del testo
    val id: String? = null,
    val keepOpen: Boolean = false,
    val locked: Boolean = false,
    val selectedText: String = "● In uso",
    val handler: ((CtlButton) -> Unit)? = null)

class CtlSheetSection(val title: String?, val items: List<CtlSheetItem>, val empty: String? = null)

class CtlSheet(val title: String?, val message: String?) {
    val sections = ArrayList<CtlSheetSection>()
    private val loose = ArrayList<CtlSheetItem>()
    /** 0 = automatico (1 colonna fino a 7 voci, poi 2) */
    var columns = 0
    var cancelTitle = "Chiudi"
    var onCancel: (() -> Unit)? = null
    /** riga ambra sotto il titolo (es. sola osservazione) */
    var banner: String? = null
    /** modo conferma: tasti affiancati in basso, il tocco fuori non chiude */
    var alert = false
    var hasCancel = true
    var idPrefix = "sheet"

    lateinit var root: UIView; private set
    private lateinit var card: UIView
    private var noteL: UILabel? = null
    private val tiles = HashMap<CtlSheetItem, CtlButton>()
    private var open = false

    fun addItem(it: CtlSheetItem) { loose.add(it) }
    fun addItem(title: String, style: Int = CtlSheetNormal, handler: (() -> Unit)?) {
        if (style == CtlSheetCancel) { cancelTitle = title; onCancel = handler; return }
        loose.add(CtlSheetItem(title, style = style, handler = { handler?.invoke() }))
    }
    /** stessa forma di UIAlertController.addAction: converte un action sheet cambiando solo la riga che lo crea e quella che lo mostra */
    fun addAction(a: UIAlertAction) {
        if (a.style == UIAlertActionStyleCancel) { cancelTitle = a.title; onCancel = a.handler; hasCancel = true; return }
        loose.add(CtlSheetItem(a.title, style = if (a.style == UIAlertActionStyleDestructive) CtlSheetDestructive else CtlSheetNormal, handler = { a.handler?.invoke() }))
    }
    fun addSection(s: CtlSheetSection) { flushLoose(); sections.add(s) }
    private fun flushLoose() { if (loose.isNotEmpty()) { sections.add(CtlSheetSection(null, ArrayList(loose))); loose.clear() } }


    // MARK: costruzione

    fun present() {
        if (!Uk.onMain()) { Uk.async { present() }; return }
        flushLoose()
        current?.dismiss()
        val host = UIViewController.host ?: return
        val W = UIViewController.designWidth; val H = UIViewController.designHeight
        root = UIView(CGRectMake(0, 0, W, H)); root.accessibilityIdentifier = "$idPrefix.root"
        val dim = UIControl(CGRectMake(0, 0, W, H)); dim.backgroundColor = UIColor.colorWithWhite(0, 0.62); dim.accessibilityIdentifier = "$idPrefix.outside"
        dim.addTarget(UIControlEventTouchUpInside) { if (!alert) cancel() }
        root.addSubview(dim)
        build(W, H)
        root.addSubview(card)
        host.addSubview(root)
        open = true; current = this
        root.alpha = 0f
        UIView.animateWithDuration(0.12, { root.alpha = 1f })
    }

    private fun build(W: Double, H: Double) {
        val all = sections.flatMap { it.items }
        val anyThumb = all.any { it.thumb != null }
        val cols = if (alert) 1 else if (columns > 0) columns else if (anyThumb) (if (all.size <= 4) 2 else 3) else (if (all.size > 7) 2 else 1)
        val gap = 10.0; val pad = 20.0
        val CW = if (alert) 560.0 else when (cols) { 1 -> 560.0; 2 -> 760.0; else -> min(980.0, W - 48) }
        val innerW = CW - 2 * pad
        val tileW = floor((innerW - (cols - 1) * gap) / cols)
        // ANDROID (miniature 23/11): con le miniature vere la tessera è «foto sopra, nome e tipo sotto» (foto 16:10)
        shotH = if (all.any { it.shot != null && shotImage(it.shot) != null }) floor((tileW - 16) * 0.625) else 0.0
        val tileH = if (shotH > 0) 8 + shotH + 8 + 22 + 20 + 8 else if (anyThumb) 88.0 else if (all.any { it.sub != null }) 64.0 else 52.0
        card = UIView(CGRectMake(0, 0, CW, 100)); card.backgroundColor = CTL_CARD; card.layer.cornerRadius = 16.0
        card.layer.borderWidth = 1.0; card.layer.borderColor = UIColor.colorWithWhite(1, 0.12).CGColor; card.clipsToBounds = true
        var y = pad
        if (!title.isNullOrEmpty()) {
            val t = CtlLabel(CGRectMake(pad, y, innerW, 26), title, 20.0, true, CTL_INK); t.numberOfLines = 0
            val s = t.sizeThatFits(CGSizeMake(innerW, 0)); t.frame = CGRectMake(pad, y, innerW, max(26.0, ceil(s.height)))
            t.accessibilityIdentifier = "$idPrefix.title"; card.addSubview(t); y += t.frame.height + 6
        }
        if (!message.isNullOrEmpty()) {
            val m = CtlLabel(CGRectMake(pad, y, innerW, 20), message, if (alert) 15.0 else 14.0, false, if (alert) CTL_INK else CTL_INK2); m.numberOfLines = 0
            val s = m.sizeThatFits(CGSizeMake(innerW, 0)); m.frame = CGRectMake(pad, y, innerW, ceil(s.height)); card.addSubview(m); y += m.frame.height + 6
        }
        banner?.let { b ->
            y += 4
            val box = UIView(CGRectMake(pad, y, innerW, 40)); box.backgroundColor = CTL_AMBER.colorWithAlphaComponent(0.14); box.layer.cornerRadius = 10.0
            box.layer.borderWidth = 1.0; box.layer.borderColor = CTL_AMBER.colorWithAlphaComponent(0.45).CGColor
            val iv = UIImageView(CGRectMake(12, 11, 18, 18)); iv.image = CtlIcon("lock", 18.0, CTL_AMBER); box.addSubview(iv)
            val l = CtlLabel(CGRectMake(38, 4, innerW - 50, 32), b, 14.0, true, CTL_AMBER); l.numberOfLines = 2; l.adjustsFontSizeToFitWidth = true; l.minimumScaleFactor = 0.9
            box.addSubview(l); box.accessibilityIdentifier = "$idPrefix.banner"; card.addSubview(box); y += 40 + 4
        }
        y += 8
        val headerH = y
        // corpo: sezioni e tessere, dentro un elenco che scorre se serve
        val body = UIView(CGRectMake(0, 0, CW, 10))
        var by = 0.0; var n = 0
        if (!alert) for (sec in sections) {
            if (sec.title != null) {
                val cnt = sec.items.size
                val hl = CtlLabel(CGRectMake(pad, by, innerW, 22), if (cnt > 0) "${sec.title}  ·  $cnt" else sec.title, 14.0, true, CTL_INK2)
                hl.accessibilityIdentifier = "$idPrefix.section.${sections.indexOf(sec)}"; body.addSubview(hl); by += 28
            }
            if (sec.items.isEmpty() && sec.empty != null) {
                val d = CtlDashBox(CGRectMake(pad, by, innerW, 52), CTL_INK3); d.radius = 10.0; body.addSubview(d)
                val el = CtlLabel(CGRectMake(pad + 16, by, innerW - 32, 52), sec.empty, 14.0, false, CTL_INK2); el.numberOfLines = 2; body.addSubview(el)
                by += 52 + 16; continue
            }
            var c = 0
            for (it in sec.items) {
                val x = pad + c * (tileW + gap)
                body.addSubview(makeTile(it, CGRectMake(x, by, tileW, tileH), n)); n++
                c++; if (c == cols) { c = 0; by += tileH + gap }
            }
            if (c != 0) by += tileH + gap
            by += 8
        }
        val bodyH = max(0.0, by - 8)
        // piede: nota breve a sinistra, tasti a destra (modo conferma: tasti affiancati a tutta larghezza)
        val footH = 48.0 + pad + 12
        val maxH = H - 48
        val visBody = min(bodyH, max(80.0, maxH - headerH - footH))
        if (!alert) {
            val sc = CtlScrollView(CGRectMake(0, headerH, CW, visBody)); sc.showsHorizontalScrollIndicator = false; sc.accessibilityIdentifier = "$idPrefix.scroll"
            body.frame = CGRectMake(0, 0, CW, bodyH); sc.addSubview(body); sc.contentSize = CGSizeMake(CW, bodyH)
            card.addSubview(sc)
            if (bodyH > visBody + 1) {      // si vede che c'è altro sotto: una linea sottile sopra il piede
                val ln = UIView(CGRectMake(0, headerH + visBody, CW, 1)); ln.backgroundColor = CTL_LINE; card.addSubview(ln)
            }
        }
        val fy = (if (alert) headerH else headerH + visBody) + 12
        if (alert) {
            val btns = ArrayList<CtlSheetItem>()
            if (hasCancel) btns.add(CtlSheetItem(cancelTitle, style = CtlSheetCancel))
            btns.addAll(sections.flatMap { it.items })
            val bw = floor((innerW - (btns.size - 1) * gap) / btns.size)
            var x = pad
            for ((i, it) in btns.withIndex()) {
                val st = if (it.style == CtlSheetCancel) CtlButtonQuiet else CtlButtonTinted
                val col = if (it.style == CtlSheetDestructive) CTL_RED else CTL_TINT
                val b = CtlButton.buttonWithTitle(it.title, st, if (st == CtlButtonQuiet) null else col, CGRectMake(x, fy, bw, 48), null)
                b.titleLabel.font = UIFont.boldSystemFontOfSize(15); b.maxLines = 2; b.minFont = 13.0
                b.accessibilityIdentifier = if (it.style == CtlSheetCancel) "$idPrefix.close" else (it.id ?: "$idPrefix.item.${i - (if (hasCancel) 1 else 0)}")
                b.addTarget(UIControlEventTouchUpInside) { _ -> if (it.style == CtlSheetCancel) cancel() else choose(it, b) }
                card.addSubview(b); x += bw + gap
            }
        } else {
            val note = CtlLabel(CGRectMake(pad, fy, innerW - 160 - 12, 48), "", 14.0, true, CTL_INK2); note.numberOfLines = 2
            note.adjustsFontSizeToFitWidth = true; note.minimumScaleFactor = 0.9; note.accessibilityIdentifier = "$idPrefix.note"; card.addSubview(note); noteL = note
            if (hasCancel) {
                val cb = CtlButton.buttonWithTitle(cancelTitle, CtlButtonQuiet, null, CGRectMake(CW - pad - 160, fy, 160, 48)) { cancel() }
                cb.titleLabel.font = UIFont.boldSystemFontOfSize(16); cb.maxLines = 1; cb.accessibilityIdentifier = "$idPrefix.close"; card.addSubview(cb)
            }
        }
        val CH = fy + 48 + pad
        card.frame = CGRectMake(floor((W - CW) / 2), floor((H - CH) / 2), CW, CH)
    }

    private var shotH = 0.0
    private val shots = HashMap<String, UIImage?>()
    /** miniatura vera dagli asset dell'app (null se manca: la tessera usa l'icona vettoriale) */
    private fun shotImage(name: String): UIImage? = shots.getOrPut(name) {
        val ctx = root.context
        try { ctx.assets.open("layout_thumbs/$name.png").use { s -> android.graphics.BitmapFactory.decodeStream(s)?.let { UIImage(it) } } } catch (e: Exception) { null }
    }

    private fun makeTile(it: CtlSheetItem, f: CGRect, n: Int): CtlButton {
        val b = CtlButton.buttonWithTitle(null, CtlButtonQuiet, null, f, null)
        b.layer.cornerRadius = 12.0
        b.accessibilityIdentifier = it.id ?: "$idPrefix.item.$n"
        b.addTarget(UIControlEventTouchUpInside) { _ -> choose(it, b) }
        if (shotH > 0) {      // ANDROID (miniature 23/11): foto del layout sopra (angoli arrotondati, bordo sottile), nome e tipo sotto
            val iw = f.width - 16
            val img = it.shot?.let { s -> shotImage(s) }
            if (img != null) {
                val iv = UIImageView(CGRectMake(8, 8, iw, shotH)); iv.image = img; iv.layer.cornerRadius = 8.0; iv.clipsToBounds = true
                iv.layer.borderWidth = 1.0; iv.layer.borderColor = UIColor.colorWithWhite(1, 0.16).CGColor; iv.userInteractionEnabled = false; b.addSubview(iv)
            } else if (it.thumb != null) {
                val th = CtlLayoutThumb(CGRectMake(8 + (iw - 120) / 2, 8 + (shotH - 86) / 2, 120.0, 86.0), it.thumb); b.addSubview(th)
            }
            var ly = 8 + shotH + 8
            val tl = CtlLabel(CGRectMake(12, ly, iw - 8, 22), it.title, 17.0, true, CTL_INK)
            tl.adjustsFontSizeToFitWidth = true; tl.minimumScaleFactor = 0.82; tl.userInteractionEnabled = false; tl.uiTag = 9102; b.addSubview(tl); ly += 22
            val subW = floor((iw - 8) * 0.55)
            if (it.sub != null) { val sl = CtlLabel(CGRectMake(12, ly, subW, 20), it.sub, 14.0, false, CTL_INK2); sl.adjustsFontSizeToFitWidth = true; sl.minimumScaleFactor = 0.85; sl.userInteractionEnabled = false; sl.uiTag = 9103; b.addSubview(sl) }
            val ul = CtlLabel(CGRectMake(12 + subW, ly, iw - 8 - subW, 20), "", 14.0, true, CTL_INK); ul.textAlignment = NSTextAlignmentRight; ul.userInteractionEnabled = false; ul.uiTag = 9104; b.addSubview(ul)
            tiles[it] = b
            paint(it, b)
            if (it.locked) b.setTag(null, CTL_AMBER, "lock")
            return b
        }
        val thumbW = 64.0; val thumbH = 46.0
        var tx = 16.0
        if (it.thumb != null) {
            val th = CtlLayoutThumb(CGRectMake(14, floor((f.height - thumbH) / 2), thumbW, thumbH), it.thumb); b.addSubview(th); tx = 14 + thumbW + 14
        } else if (it.icon != null) {
            val iv = UIImageView(CGRectMake(14, floor((f.height - 22) / 2), 22, 22)); iv.userInteractionEnabled = false; iv.uiTag = 9101; b.addSubview(iv); tx = 14 + 22 + 12.0
        }
        val tw = f.width - tx - 12
        val lines = 1 + (if (it.sub != null) 1 else 0) + (if (it.thumb != null) 1 else 0)
        val blockH = 22.0 + (lines - 1) * 20.0
        var ly = floor((f.height - blockH) / 2)
        val tl = CtlLabel(CGRectMake(tx, ly, tw, 22), it.title, if (it.thumb != null) 17.0 else 16.0, true, CTL_INK)
        tl.adjustsFontSizeToFitWidth = true; tl.minimumScaleFactor = 0.82; tl.userInteractionEnabled = false; tl.uiTag = 9102; b.addSubview(tl); ly += 22
        if (it.sub == null && it.thumb == null) { tl.frame = CGRectMake(tx, 4, tw, f.height - 8); tl.numberOfLines = 2; tl.adjustsFontSizeToFitWidth = false }      // voci lunghe: due righe
        if (it.sub != null) { val sl = CtlLabel(CGRectMake(tx, ly, tw, 20), it.sub, 14.0, false, CTL_INK2); sl.adjustsFontSizeToFitWidth = true; sl.minimumScaleFactor = 0.85; sl.userInteractionEnabled = false; sl.uiTag = 9103; b.addSubview(sl); ly += 20 }
        if (it.thumb != null) { val ul = CtlLabel(CGRectMake(tx, ly, tw, 20), "", 14.0, true, CTL_INK); ul.userInteractionEnabled = false; ul.uiTag = 9104; b.addSubview(ul) }
        tiles[it] = b
        paint(it, b)
        if (it.locked) b.setTag(null, CTL_AMBER, "lock")
        return b
    }

    /** colori della tessera secondo lo stato (scelta / distruttiva / normale) */
    private fun paint(it: CtlSheetItem, b: CtlButton) {
        val sel = it.selected
        val ink = if (it.style == CtlSheetDestructive) CTL_RED else CTL_INK
        b.backgroundColor = if (sel) CTL_TINT.colorWithAlphaComponent(0.26) else CTL_CARD2
        b.layer.borderWidth = if (sel) 2.0 else 1.0
        b.layer.borderColor = (if (sel) CTL_TINT else if (it.style == CtlSheetDestructive) CTL_RED.colorWithAlphaComponent(0.4) else UIColor.colorWithWhite(1, 0.08)).CGColor
        for (v in b.subviews) {
            if (v is UILabel && v.uiTag == 9102) { v.textColor = ink; v.text = if (sel && it.thumb == null) "● " + it.title else it.title }
            if (v is UILabel && v.uiTag == 9104) { v.text = if (sel) it.selectedText else ""; v.textColor = UIColor.whiteColor }
            if (v is UIImageView && v.uiTag == 9101 && it.icon != null) v.image = CtlIcon(it.icon, 22.0, ink)
        }
    }

    /** cambia la voce scelta (es. dopo il ✓ del monitor) senza ricostruire il pannello */
    fun markSelected(item: CtlSheetItem) {
        for ((it, b) in tiles) { val s = it === item; if (it.selected != s) { it.selected = s; paint(it, b) } }
    }

    /** riga breve nel piede del pannello (es. «Comanda … : tocca Prendi il comando») */
    fun note(text: String?, color: UIColor = CTL_INK2, sec: Double = 4.0) {
        val l = noteL ?: return
        l.text = text ?: ""; l.textColor = color
        if (text != null) { val t = text; Uk.after(sec) { if (l.text == t) l.text = "" } }
    }

    private fun choose(it: CtlSheetItem, b: CtlButton) {
        if (!open) return
        if (it.keepOpen) { it.handler?.invoke(b); return }
        dismiss()
        it.handler?.invoke(b)
    }

    fun cancel() { if (!open) return; dismiss(); onCancel?.invoke() }

    fun dismiss() {
        if (!open) return
        open = false
        if (current === this) current = null
        val r = root
        UIView.animateWithDuration(0.1, { r.alpha = 0f }) { _ -> r.removeFromSuperview() }
        r.userInteractionEnabled = false
    }

    val isOpen: Boolean get() = open

    companion object {
        var current: CtlSheet? = null; private set
        /** tasto Indietro: chiude il pannello aperto (come Annulla). true = gestito */
        fun handleBack(): Boolean {
            val s = current ?: return false
            if (s.hasCancel) s.cancel()
            return true
        }
        /** foglio di azioni (ex UIAlertController action sheet): stesso uso, nel tema */
        fun actions(title: String?, message: String?): CtlSheet = CtlSheet(title, message)
        /** finestra di conferma (ex UIAlertController alert): tocco fuori non chiude */
        fun confirm(title: String?, message: String?): CtlSheet = CtlSheet(title, message).also { it.alert = true; it.cancelTitle = "Annulla" }
    }
}

/** nome mostrato del tipo di layout (campo "kind" del catalogo) */
fun CtlLayoutKindName(kind: String?): String = when (kind) {
    "icu" -> "Terapia intensiva"; "ward" -> "Reparto"; "defib" -> "Defibrillatore"; "aed" -> "DAE"; else -> "Monitor"
}

// Miniatura stilizzata di un tipo di layout, disegnata a vettori (niente loghi né marchi):
//  icu   = monitor largo con tre tracce (ECG verde, pletismografia ciano, CO₂ gialla) e i numeri a destra
//  ward  = monitor piccolo sull'asta, una traccia e due numeri grandi
//  defib = monitor-defibrillatore: maniglia sopra, schermo con la traccia, tasto della scarica arancione con il fulmine
//  aed   = DAE: scatola verde arrotondata con il cuore e il fulmine, tasto della scarica
class CtlLayoutThumb(frame: CGRect, val kind: String) : UIView(frame) {
    init { backgroundColor = UIColor.clearColor; userInteractionEnabled = false }
    override fun drawRect(ctx: CGContext, rect: CGRect) {
        val w = bounds.width; val h = bounds.height
        fun line(c: UIColor, lw: Double, vararg p: Double) {
            val b = UIBezierPath.bezierPath(); b.lineWidth = lw; b.lineCapStyle = kCGLineCapRound; b.lineJoinStyle = kCGLineJoinRound
            b.moveToPoint(CGPointMake(p[0], p[1])); var i = 2; while (i + 1 < p.size) { b.addLineToPoint(CGPointMake(p[i], p[i + 1])); i += 2 }
            ctx.setStrokeColor(c); b.stroke()
        }
        fun box(r: CGRect, rad: Double, fill: UIColor?, stroke: UIColor?, lw: Double = 1.5) {
            val b = UIBezierPath.bezierPathWithRoundedRect(r, rad)
            if (fill != null) { ctx.setFillColor(fill); b.fill() }
            if (stroke != null) { b.lineWidth = lw; ctx.setStrokeColor(stroke); b.stroke() }
        }
        val frameC = CTL_INK2; val screen = CtlRGB(0.02, 0.03, 0.04)
        when (kind) {
            "icu" -> {
                box(CGRectMake(1.5, 1.5, w - 3, h - 3), 5.0, screen, frameC)
                val r = w - 20
                fun ecg(y: Double) = line(CTL_HR, 1.4, 6.0, y, 12.0, y, 14.0, y - 6, 16.5, y + 3, 18.0, y, 26.0, y, 28.0, y - 6, 30.5, y + 3, 32.0, y, r - 4, y)
                ecg(13.0)
                line(CTL_SPO2, 1.4, 6.0, 26.0, 10.0, 21.0, 14.0, 25.0, 16.0, 24.0, 20.0, 27.0, 24.0, 21.0, 28.0, 25.0, 30.0, 24.0, 34.0, 27.0, r - 4, 26.0)
                line(CTL_CO2, 1.4, 6.0, 38.0, 9.0, 38.0, 11.0, 33.0, 19.0, 32.5, 21.0, 38.0, 25.0, 38.0, 27.0, 33.0, 35.0, 32.5, 37.0, 38.0, r - 4, 38.0)
                ctx.setFillColor(CTL_HR); ctx.fillRect(CGRectMake(r, 8.0, 12.0, 7.0))
                ctx.setFillColor(CTL_SPO2); ctx.fillRect(CGRectMake(r, 21.0, 12.0, 7.0))
                ctx.setFillColor(CTL_CO2); ctx.fillRect(CGRectMake(r, 33.0, 12.0, 7.0))
            }
            "ward" -> {
                val sw = w * 0.70; val sx = (w - sw) / 2
                box(CGRectMake(sx, 1.5, sw, h * 0.66), 4.0, screen, frameC)
                line(CTL_HR, 1.4, sx + 4, 12.0, sx + 9, 12.0, sx + 11, 6.0, sx + 13.5, 15.0, sx + 15, 12.0, sx + sw - 4, 12.0)
                ctx.setFillColor(CTL_HR); ctx.fillRect(CGRectMake(sx + 5, 19.0, 13.0, 8.0))
                ctx.setFillColor(CTL_SPO2); ctx.fillRect(CGRectMake(sx + sw - 18, 19.0, 13.0, 8.0))
                line(frameC, 2.0, w / 2, h * 0.66 + 2, w / 2, h - 4)
                line(frameC, 2.0, w / 2 - 10, h - 2, w / 2 + 10, h - 2)
            }
            "defib" -> {
                line(frameC, 2.0, w * 0.30, 8.0, w * 0.30, 3.0, w * 0.70, 3.0, w * 0.70, 8.0)       // maniglia
                box(CGRectMake(1.5, 8.0, w - 3, h - 9.5), 5.0, CtlRGB(0.16, 0.19, 0.23), frameC)
                box(CGRectMake(6.0, 13.0, w * 0.58, h - 20), 2.5, screen, null)
                line(CTL_HR, 1.4, 9.0, 26.0, 14.0, 26.0, 16.0, 19.0, 18.5, 31.0, 20.0, 26.0, w * 0.58 + 3, 26.0)
                val cx = w * 0.82; val cy = 25.0
                ctx.setFillColor(CTL_ORANGE); ctx.fillEllipseInRect(CGRectMake(cx - 7.5, cy - 7.5, 15.0, 15.0))
                val bolt = UIBezierPath.bezierPath()
                bolt.moveToPoint(CGPointMake(cx + 1.2, cy - 5)); bolt.addLineToPoint(CGPointMake(cx - 2.5, cy + 0.8)); bolt.addLineToPoint(CGPointMake(cx, cy + 0.8))
                bolt.addLineToPoint(CGPointMake(cx - 1.2, cy + 5)); bolt.addLineToPoint(CGPointMake(cx + 2.5, cy - 0.8)); bolt.addLineToPoint(CGPointMake(cx, cy - 0.8)); bolt.closePath()
                ctx.setFillColor(CTL_ON_LIGHT); bolt.fill()
            }
            "aed" -> {
                val bw = w * 0.66; val bx = (w - bw) / 2
                box(CGRectMake(bx, 1.5, bw, h - 3), 9.0, CTL_GREEN.colorWithAlphaComponent(0.22), CTL_GREEN, 1.8)
                // cuore con il fulmine
                val cx = w / 2; val cy = 17.0
                val heart = UIBezierPath.bezierPath()
                heart.moveToPoint(CGPointMake(cx, cy + 9))
                heart.addCurveToPoint(CGPointMake(cx - 10, cy - 2), CGPointMake(cx - 5, cy + 5), CGPointMake(cx - 10, cy + 2))
                heart.addCurveToPoint(CGPointMake(cx, cy - 4), CGPointMake(cx - 10, cy - 9), CGPointMake(cx - 2, cy - 9))
                heart.addCurveToPoint(CGPointMake(cx + 10, cy - 2), CGPointMake(cx + 2, cy - 9), CGPointMake(cx + 10, cy - 9))
                heart.addCurveToPoint(CGPointMake(cx, cy + 9), CGPointMake(cx + 10, cy + 2), CGPointMake(cx + 5, cy + 5))
                heart.closePath(); ctx.setFillColor(CTL_GREEN); heart.fill()
                val bolt = UIBezierPath.bezierPath()
                bolt.moveToPoint(CGPointMake(cx + 1.5, cy - 6)); bolt.addLineToPoint(CGPointMake(cx - 3, cy + 1)); bolt.addLineToPoint(CGPointMake(cx, cy + 1))
                bolt.addLineToPoint(CGPointMake(cx - 1.5, cy + 7)); bolt.addLineToPoint(CGPointMake(cx + 3, cy - 1)); bolt.addLineToPoint(CGPointMake(cx, cy - 1)); bolt.closePath()
                ctx.setFillColor(CTL_ON_LIGHT); bolt.fill()
                ctx.setFillColor(CTL_ORANGE); ctx.fillEllipseInRect(CGRectMake(cx - 5, h - 14.0, 10.0, 10.0))
            }
            else -> {
                box(CGRectMake(1.5, 1.5, w - 3, h - 3), 5.0, screen, frameC)
                line(CTL_HR, 1.4, 6.0, h / 2, 16.0, h / 2, 18.0, h / 2 - 7, 21.0, h / 2 + 4, 23.0, h / 2, w - 6, h / 2)
            }
        }
    }
}
