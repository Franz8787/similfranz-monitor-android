// ANDROID (12 derivazioni 22/11) — file nuovo, solo Android (sull'iPad non c'è).
// similFranz Monitor — CARTA DEL 12 DERIVAZIONI a tutto schermo (sopra il layout), aperta dal tasto 12 derivazioni dei layout
// che lo prevedono. Si chiude con «Chiudi» o toccando fuori dalla carta.
//
// Carta millimetrata (quadretti 1 mm e 5 mm), 25 mm/s, 10 mm/mV, impulso di calibrazione 1 mV, disposizione standard 3×4
// (I, II, III | aVR, aVL, aVF | V1-V3 | V4-V6: ogni colonna 2,5 s, le derivazioni di una colonna nello stesso tempo) e striscia
// del ritmo (II) di 10 s; intestazione nello stile del dispositivo (data/ora, paziente «—», FC, PR, QRS, QT/QTc, assi,
// interpretazione automatica sobria). I dati sono gli ULTIMI 10 s del motore delle curve (WaveformEngine.capture12): stessi
// battiti, stessa FC e stesso ritmo del tracciato del monitor.
// Prestazioni (GPU Mali debole): la carta si disegna UNA volta in una bitmap (su un thread di sfondo) e poi si mostra ferma.
// Nota: per disegnare nella bitmap si usano Canvas/Paint di Android (non sono viste: uk non ha un disegno fuori schermo).
package it.simulafranz.monitor

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import it.simulafranz.core.*
import it.simulafranz.uk.*
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// stili della carta: Alt = intestazione alternativa (blu), B = intestazione estesa (verde)
const val SFEcg12StyleAlt = 0
private const val kTxAlt = "Trasmetti"
const val SFEcg12StyleB = 1

class SFEcg12View(frame: CGRect, private val cap: WaveformEngine.SLEcg12Capture, private val style: Int, private val wallMs: Long,
                  private val onClose: () -> Unit, onTransmit: (() -> Unit)? = null) : UIView(frame) {

    // la carta al centro di TUTTO lo schermo (vista radice, sopra le fasce laterali dei layout): quadretto da ~4,3 punti per
    // mm, griglia 262 × 150 mm (calibrazione + 10 s; 3 righe + striscia del ritmo a 37,5 mm), proporzioni da stampa
    private val mmPt = Math.min((frame.size.height - 38 - HEAD - 18) / 150.0, (frame.size.width - 24 - 16) / 262.0)
    private val paper = CGRectMake(Math.floor((frame.size.width - (262 * mmPt + 16)) / 2), 6, Math.floor(262 * mmPt + 16), Math.ceil(HEAD + 150 * mmPt + 18))
    private val img = UIImageView(paper)
    private var closed = false
    private var front: NSTimer? = null
    var txButton: UIButton? = null; private set          // ANDROID (trasmissione 12D 22/11): per gli autotest (tasto ECG12-TX)
    private companion object { const val HEAD = 84.0 }

    init {
        backgroundColor = UIColor.colorWithWhite(0.06, 1)          // opaco: niente prese, ☰ o scocca visibili ai lati
        val wait = UIView(paper); wait.backgroundColor = UIColor.colorWithWhite(0.99, 1); addSubview(wait)
        val wl = UILabel(CGRectMake(0, 320, 1004, 40)); wl.text = "Acquisizione 12 derivazioni…"; wl.textAlignment = NSTextAlignmentCenter
        wl.font = UIFont.boldSystemFontOfSize(20); wl.textColor = UIColor.colorWithWhite(0.3, 1); wait.addSubview(wl)
        addSubview(img)
        val hint = UILabel(CGRectMake(paper.origin.x, paper.origin.y + paper.size.height + 4, paper.size.width, 26)); hint.text = "Tocca fuori dalla carta per chiudere"
        hint.textAlignment = NSTextAlignmentCenter
        hint.font = UIFont.systemFontOfSize(16); hint.textColor = UIColor.colorWithWhite(0.8, 1); addSubview(hint)
        // «Chiudi» ben visibile: in alto a destra sulla carta (nello spazio dell'intestazione)
        val b = UIButton.buttonWithType(UIButtonTypeCustom)
        b.frame = CGRectMake(paper.origin.x + paper.size.width - 166, paper.origin.y + 10, 156, 50); b.backgroundColor = UIColor.colorWithRed(0.80, 0.12, 0.10, 1)
        b.layer.cornerRadius = 8.0; b.layer.borderWidth = 2.0; b.layer.borderColor = UIColor.whiteColor.CGColor
        b.setTitle("Chiudi", UIControlStateNormal); b.setTitleColor(UIColor.whiteColor, UIControlStateNormal)
        b.titleLabel.font = UIFont.boldSystemFontOfSize(22)
        b.addTarget(UIControlEventTouchUpInside) { close() }
        b.accessibilityIdentifier = "ecg12-chiudi"
        addSubview(b)
        // quando il report è pronto). Chiude la carta e apre il menu di trasmissione del dispositivo con questo 12D già scelto.
        if (onTransmit != null) {
            val t = UIButton.buttonWithType(UIButtonTypeCustom)
            t.frame = CGRectMake(paper.origin.x + paper.size.width - 166 - 12 - 176, paper.origin.y + 10, 176, 50)
            t.backgroundColor = if (style == SFEcg12StyleB) UIColor.colorWithRed(0.10, 0.45, 0.20, 1) else UIColor.colorWithRed(0.10, 0.32, 0.70, 1)
            t.layer.cornerRadius = 8.0; t.layer.borderWidth = 2.0; t.layer.borderColor = UIColor.whiteColor.CGColor
            t.setTitle(if (style == SFEcg12StyleB) "Trasmetti" else kTxAlt, UIControlStateNormal); t.setTitleColor(UIColor.whiteColor, UIControlStateNormal)
            t.titleLabel.font = UIFont.boldSystemFontOfSize(21)
            t.addTarget(UIControlEventTouchUpInside) { close(); onTransmit() }
            t.accessibilityIdentifier = "ecg12-trasmetti"
            txButton = t
            addSubview(t)
        }
        val sc = Uk.scale.toDouble().coerceAtLeast(1.0)
        Uk.background {
            val bmp = try { render(sc) } catch (e: Throwable) { SFLog.log("12D: disegno non riuscito: $e"); null }
            Uk.async { if (!closed && bmp != null) { img.image = UIImage(bmp); wait.removeFromSuperview() } }
        }
        // sopra a tutto anche ciò che arriva dopo nella radice (☰ rimesso in cima, parti dei layout, finestre)
        front = NSTimer.scheduledTimerWithTimeInterval(0.5, true) { superview?.bringSubviewToFront(this) }
    }

    fun close() { if (closed) return; closed = true; front?.invalidate(); front = null; removeFromSuperview(); onClose() }

    // tocco fuori dalla carta: chiude
    override fun touchesBegan(p: CGPoint) {}
    override fun touchesMoved(p: CGPoint) {}
    override fun touchesCancelled(p: CGPoint) {}
    override fun touchesEnded(p: CGPoint) { if (!CGRectContainsPoint(paper, p)) close() }

    // MARK: - disegno della carta (pixel)

    private fun render(s: Double): Bitmap {
        val W = (paper.size.width * s).toInt(); val H = (paper.size.height * s).toInt()
        val bmp = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        fun px(pt: Double): Float = (pt * s).toFloat()
        c.drawColor(android.graphics.Color.rgb(255, 253, 252))
        // griglia: 262 mm (6 mm di calibrazione + 250 mm + margine) × 160 mm
        val mm = mmPt; val gx = 8.0; val gw = 262 * mm; val gy = HEAD; val gh = 150 * mm
        val minor = Paint().apply { color = android.graphics.Color.rgb(246, 200, 200); strokeWidth = maxOf(1f, (0.5 * s).toFloat()) }
        val major = Paint().apply { color = android.graphics.Color.rgb(226, 120, 120); strokeWidth = maxOf(1.4f, (0.9 * s).toFloat()) }
        for (i in 0..262) { val x = px(gx + i * mm); c.drawLine(x, px(gy), x, px(gy + gh), if (i % 5 == 0) major else minor) }
        for (i in 0..150) { val y = px(gy + i * mm); c.drawLine(px(gx), y, px(gx + gw), y, if (i % 5 == 0) major else minor) }

        val ink = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = android.graphics.Color.rgb(20, 20, 24); style = Paint.Style.STROKE
            strokeWidth = (0.95 * s).toFloat(); strokeJoin = Paint.Join.ROUND; strokeCap = Paint.Cap.ROUND }
        val txt = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = android.graphics.Color.rgb(20, 20, 24); typeface = Typeface.SANS_SERIF; textSize = px(10.5) }
        val bold = Paint(txt).apply { typeface = Typeface.DEFAULT_BOLD }
        val fs = cap.fs.toDouble(); val n = cap.leads[0].size
        val x0 = gx + 6 * mm                                     // inizio delle tracce (dopo la calibrazione)
        fun y(base: Double, mv: Float): Float = px(base - mv * 10 * mm)
        fun cal(base: Double) {
            val p = Path(); p.moveTo(px(gx + 0.5 * mm), px(base)); p.lineTo(px(gx + 1.5 * mm), px(base)); p.lineTo(px(gx + 1.5 * mm), px(base - 10 * mm))
            p.lineTo(px(gx + 6.5 * mm - 1.5 * mm), px(base - 10 * mm)); p.lineTo(px(gx + 5 * mm), px(base)); p.lineTo(px(x0), px(base)); c.drawPath(p, ink)
        }
        fun trace(lead: Int, s0: Int, s1: Int, xs: Double, base: Double) {
            val p = Path(); val d = cap.leads[lead]
            for (k in s0 until s1) { val x = px(xs + (k - s0) / fs * 25 * mm); val yy = y(base, d[k]); if (k == s0) p.moveTo(x, yy) else p.lineTo(x, yy) }
            c.drawPath(p, ink)
            for (pt in cap.paceAt) {                               // spike del pacemaker
                val k = (pt * fs).toInt(); if (k < s0 || k >= s1) continue
                val x = px(xs + (pt - s0 / fs) * 25 * mm); c.drawLine(x, px(base), x, px(base - 7 * mm), ink)
            }
        }
        val order = arrayOf(intArrayOf(SL12I, SL12aVR, SL12V1, SL12V4), intArrayOf(SL12II, SL12aVL, SL12V2, SL12V5), intArrayOf(SL12III, SL12aVF, SL12V3, SL12V6))
        val q = n / 4
        c.save(); c.clipRect(px(gx), px(gy), px(gx + gw), px(gy + gh))
        for (r in 0 until 3) {
            val base = gy + (19 + r * 37.5) * mm
            cal(base)
            for (col in 0 until 4) {
                val xs = x0 + col * 62.5 * mm
                trace(order[r][col], col * q, (col + 1) * q, xs, base)
                if (col > 0) c.drawLine(px(xs), px(base - 3 * mm), px(xs), px(base + 3 * mm), ink)        // cambio di derivazione
                c.drawText(SL12LeadNames[order[r][col]], px(xs + 3 * mm), px(base - 11 * mm), bold)
            }
        }
        val rb = gy + 131.5 * mm
        cal(rb); trace(SL12II, 0, n, x0, rb)
        c.drawText("II", px(x0 + 2 * mm), px(rb - 11 * mm), bold)
        c.restore()

        // intestazione
        val ms = cap.measures
        val date = SimpleDateFormat("dd/MM/yyyy  HH:mm:ss", Locale.ITALY).format(Date(wallMs))
        fun f(v: Int?): String = v?.toString() ?: "---"
        val hrT = if (cap.hr < 0) "---" else cap.hr.toString()
        val axes = if (ms != null) "${ms.axisP?.toString() ?: "---"}  ${ms.axisQrs}  ${ms.axisT}" else "---"
        val qt = if (ms != null) "${ms.qtMs}/${ms.qtcMs}" else "---"
        val big = Paint(bold).apply { textSize = px(14.0) }
        val head = cap.reading.headline
        val lines = cap.reading.lines
        if (style == SFEcg12StyleB) {
            c.drawText("ECG A 12 DERIVAZIONI", px(12.0), px(20.0), big)
            c.drawText("Nome: —", px(12.0), px(38.0), txt); c.drawText("ID paziente: —", px(12.0), px(52.0), txt)
            c.drawText("Età: —   Sesso: —", px(12.0), px(66.0), txt)
            c.drawText(date, px(190.0), px(20.0), txt)
            val m = listOf("FC          $hrT /min", "PR          ${f(ms?.prMs)} ms", "QRSd      ${f(ms?.qrsMs)} ms", "QT/QTc    $qt ms", "P-QRS-T  $axes")
            for ((i, l) in m.withIndex()) c.drawText(l, px(190.0), px(34.0 + 11.5 * i), txt)
            var yy = 20.0
            if (head != null) { c.drawText(head, px(420.0), px(yy), big); yy += 15 }
            for (l in lines.take(if (head != null) 5 else 6)) { c.drawText(clip(l, txt, px(paper.size.width - 420 - 180)), px(420.0), px(yy), txt); yy += 11.5 }
        } else {
            c.drawText("12 DERIVAZIONI", px(12.0), px(20.0), big)
            c.drawText(date, px(150.0), px(20.0), txt)
            c.drawText("Paziente: —     ID: —     Età: —     Sesso: —", px(12.0), px(40.0), txt)
            c.drawText("FC $hrT bpm    PR ${f(ms?.prMs)} ms    QRS ${f(ms?.qrsMs)} ms", px(12.0), px(58.0), bold)
            c.drawText("QT/QTc $qt ms    Assi P-QRS-T $axes", px(12.0), px(74.0), txt)
            var yy = 18.0
            if (head != null) { c.drawText(head, px(440.0), px(yy), big); yy += 15 }
            for (l in lines.take(if (head != null) 5 else 6)) { c.drawText(clip(l, txt, px(paper.size.width - 440 - 180)), px(440.0), px(yy), txt); yy += 11.5 }
        }
        // piede
        val small = Paint(txt).apply { textSize = px(9.5) }
        c.drawText("25 mm/s    10 mm/mV    0,05-150 Hz    50 Hz ~", px(gx), px(gy + gh + 11), small)
        val sim = "Simulazione SimilFranz"
        c.drawText(sim, px(gx + gw) - small.measureText(sim), px(gy + gh + 11), small)
        return bmp
    }

    private fun clip(s: String, p: Paint, w: Float): String {
        if (p.measureText(s) <= w) return s
        var t = s
        while (t.length > 4 && p.measureText("$t…") > w) t = t.dropLast(1)
        return "$t…"
    }
}

/**
 * Apre la carta 12D sopra `host` (vista del layout) con gli ultimi 10 s del motore e scrive nel registro «12 derivazioni
 * acquisito» (azione dei partecipanti utile al debriefing). Ritorna la vista (per chiuderla dal layout).
 */
fun SFEcg12Show(host: UIView, state: SimulationState, engine: WaveformEngine, style: Int, layout: String,
                onTransmit: ((SFEcg12Tx.Record) -> Unit)? = null, onClose: () -> Unit = {}): SFEcg12View {
    val cap = engine.capture12()
    val r = cap.reading
    val interp = ((r.headline?.let { listOf(it) } ?: emptyList()) + r.lines.filter { it != "Referto non confermato" && it != "ECG anormale" }).take(4).joinToString("; ")
    val wall = state.wallNowMs()
    // ANDROID (trasmissione 12D 22/11): il 12D resta in memoria per la trasmissione (anche dopo, dal tasto di trasmissione)
    var rec: SFEcg12Tx.Record? = null
    // sopra TUTTO (anche le parti del frontale nelle fasce laterali): nella vista radice dello schermo
    val top = SFSideBand.left?.superview ?: host.window ?: host
    val v = SFEcg12View(CGRectMake(0, 0, top.bounds.size.width, top.bounds.size.height), cap, style, wall, onClose,
                        if (onTransmit != null) ({ rec?.let { onTransmit(it) } }) else null)
    top.addSubview(v); top.bringSubviewToFront(v)
    state.applyCommand(mapOf("command" to "ecg12_acquired", "value" to mapOf("layout" to layout, "interpretation" to interp)))
    val morph = ((state.stateDictionary()["ecg12_last"] as? Map<*, *>)?.get("ecg_morphology") as? String) ?: "normale"
    rec = SFEcg12Tx.Record(wall, cap.hr, interp, morph, cap.electrodes).also { SFEcg12Tx.add(it) }
    SFLog.log("12D aperto ($layout): FC ${cap.hr}, $interp")
    return v
}
