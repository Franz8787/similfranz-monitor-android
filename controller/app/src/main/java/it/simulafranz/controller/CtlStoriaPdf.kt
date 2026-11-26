// ANDROID (storia sim 22/11): nessun file corrispondente sull'iPad.
// similFranz Controller — PDF di una simulazione salvata («verbale di simulazione»): A4, margini 2 cm, disegnato in
// vettoriale con android.graphics.pdf.PdfDocument (testo vero, selezionabile; nessuna cattura delle viste dell'app).
// Sfondo bianco, inchiostro scuro, colori solo come accenti. Due passate: la prima conta le pagine («Pagina N di M»).
package it.simulafranz.controller

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object CtlStoriaPdf {
    private const val PW = 595; private const val PH = 842            // A4 in punti tipografici
    private const val M = 56.7f                                         // 2 cm
    private const val TOP = M; private const val BOTTOM = PH - M
    private const val CW = PW - 2 * M

    private const val INK = 0xFF1E2328.toInt(); private const val INK2 = 0xFF5B6570.toInt(); private const val INK3 = 0xFF8A939D.toInt()
    private const val LINE = 0xFFD6DBE1.toInt(); private const val HEAD = 0xFFEEF2F6.toInt(); private const val ZEBRA = 0xFFF7F9FB.toInt()
    private const val BOX = 0xFFF4F6F9.toInt()
    private const val ACCENT = 0xFF1F6FB2.toInt(); private const val ACCENT2 = 0xFF0FA3A3.toInt()
    private const val GREEN = 0xFF2E7D32.toInt(); private const val AMBER = 0xFFE0A100.toInt(); private const val RED = 0xFFC62828.toInt()

    private val REG: Typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL)
    private val BOLD: Typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
    private val ITAL: Typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.ITALIC)

    private fun tp(size: Float, color: Int, face: Typeface = REG): TextPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textSize = size; this.color = color; typeface = face }
    private fun lay(text: String, p: TextPaint, w: Float, align: Layout.Alignment = Layout.Alignment.ALIGN_NORMAL): StaticLayout =
        StaticLayout.Builder.obtain(text, 0, text.length, p, maxOf(1, w.toInt())).setAlignment(align).setLineSpacing(0f, 1.12f).setIncludePad(false).build()

    private fun kindColor(k: String?): Int = mapOf("phase" to ACCENT, "patient" to INK2, "monitor" to ACCENT2, "electric" to 0xFFD35400.toInt(), "timer" to INK3,
        "action" to GREEN, "drug" to 0xFF6A1B9A.toInt(), "plus" to GREEN, "minus" to RED, "note" to 0xFFB28704.toInt())[k ?: ""] ?: INK2
    private fun pauseColor(s: Double) = if (s <= 5) GREEN else if (s <= 10) AMBER else RED
    private fun ccfColor(p: Double) = if (p >= 80) GREEN else if (p >= 60) AMBER else RED

    private fun num(v: Any?): Int = when (v) { is Number -> v.toInt(); is String -> v.trim().toIntOrNull() ?: 0; else -> 0 }
    private fun obj(v: Any?): String = when (v) { null -> ""; is Double -> if (v == Math.floor(v)) v.toLong().toString() else v.toString(); else -> v.toString() }
    private fun mmss(s: Int) = String.format(Locale.US, "%02d:%02d", s / 60, s % 60)
    private fun span(v: Any?): String { if (v !is Number) return "—"; val s = v.toInt(); return if (s < 60) "$s s" else String.format(Locale.US, "%d min %02d s", s / 60, s % 60) }
    private fun wall(v: Any?): String { val w = (v as? Number)?.toDouble() ?: return "—"; if (w <= 0) return "—"; return SimpleDateFormat("HH:mm:ss", Locale.ITALY).format(Date((w * 1000).toLong())) }

    private class Col(val title: String, val frac: Float, val align: Layout.Alignment = Layout.Alignment.ALIGN_NORMAL)
    private class Cell(val text: String, val bold: Boolean = false, val color: Int = INK, val dot: Int? = null, val small: Boolean = false, val italic: Boolean = false)

    private class Doc(val total: Int, val title: String, val gen: String) {
        val doc = PdfDocument()
        private var page: PdfDocument.Page? = null
        lateinit var c: Canvas
        var y = TOP; var n = 0
        val fill = Paint(Paint.ANTI_ALIAS_FLAG)
        val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }

        fun newPage() {
            page?.let { doc.finishPage(it) }
            n++
            val p = doc.startPage(PdfDocument.PageInfo.Builder(PW, PH, n).create()); page = p; c = p.canvas
            // intestazione discreta: marchio a sinistra, titolo a destra, sottile riga di colore
            val brand = tp(7.5f, ACCENT, BOLD); val rest = tp(7.5f, INK2)
            c.drawText("similFranz", M, 36f, brand)
            c.drawText("  ·  Storia Sim", M + brand.measureText("similFranz"), 36f, rest)
            var t = title; val rp = tp(7.5f, INK2)
            while (t.length > 4 && rp.measureText(t) > CW * 0.42f) t = t.substring(0, t.length - 2).trimEnd() + "…"
            c.drawText(t, PW - M - rp.measureText(t), 36f, rp)
            fill.color = ACCENT; c.drawRect(M, 42f, PW - M, 42.8f, fill)
            fill.color = ACCENT2; c.drawRect(M, 42f, M + 46f, 42.8f, fill)
            // piè di pagina: data di generazione, «Pagina N di M»
            fill.color = LINE; c.drawRect(M, PH - 42f, PW - M, PH - 41.6f, fill)
            val fp = tp(7f, INK3)
            c.drawText(gen, M, PH - 30f, fp)
            val pn = if (total > 0) "Pagina $n di $total" else "Pagina $n"
            c.drawText(pn, PW - M - fp.measureText(pn), PH - 30f, fp)
            y = TOP
        }
        fun ensure(h: Float): Boolean { if (y + h > BOTTOM) { newPage(); return true }; return false }
        fun draw(l: StaticLayout, x: Float, yy: Float) { c.save(); c.translate(x, yy); l.draw(c); c.restore() }
        fun finish(out: File?) {
            page?.let { doc.finishPage(it) }; page = null
            if (out != null) FileOutputStream(out).use { doc.writeTo(it) }
            doc.close()
        }

        fun heading(t: String, sub: String? = null) {
            ensure(100f)      // titolo + intestazione della tabella + almeno una riga: mai un titolo solo in fondo alla pagina
            y += if (y <= TOP + 1) 4f else 18f
            fill.color = ACCENT; c.drawRect(M, y + 1f, M + 3f, y + 14f, fill)
            c.drawText(t, M + 10f, y + 12.5f, tp(12.5f, INK, BOLD))
            if (sub != null) { val sp = tp(8f, INK2); c.drawText(sub, PW - M - sp.measureText(sub), y + 12.5f, sp) }
            y += 20f
        }
        fun para(t: String, size: Float = 9f, color: Int = INK2, face: Typeface = REG, x: Float = M, w: Float = CW) {
            val l = lay(t, tp(size, color, face), w); ensure(l.height.toFloat()); draw(l, x, y); y += l.height + 4f
        }

        fun table(cols: List<Col>, rows: List<List<Cell>>, zebra: Boolean = true) {
            val padX = 5f; val padY = 4f
            val ws = cols.map { it.frac * CW }
            fun header() {
                val hp = tp(7f, INK2, BOLD)
                val hl = cols.mapIndexed { i, col -> lay(col.title.uppercase(Locale.ITALY), hp, ws[i] - 2 * padX, col.align) }
                val h = hl.maxOf { it.height } + 2 * 5f
                fill.color = HEAD; c.drawRect(M, y, M + CW, y + h, fill)
                var x = M; for (i in cols.indices) { draw(hl[i], x + padX, y + 5f); x += ws[i] }
                y += h
            }
            ensure(60f); header()
            var i = 0
            for (r in rows) {
                val ls = r.mapIndexed { j, cell ->
                    val face = if (cell.bold) BOLD else if (cell.italic) ITAL else REG
                    val dotW = if (cell.dot != null) 9f else 0f
                    lay(cell.text, tp(if (cell.small) 7.3f else 8.6f, cell.color, face), ws[j] - 2 * padX - dotW, cols[j].align)
                }
                val h = ls.maxOf { it.height } + 2 * padY
                if (y + h > BOTTOM) { newPage(); header() }
                if (zebra && i % 2 == 1) { fill.color = ZEBRA; c.drawRect(M, y, M + CW, y + h, fill) }
                var x = M
                for (j in r.indices) {
                    val cell = r[j]
                    var tx = x + padX
                    cell.dot?.let { col -> fill.color = col; c.drawCircle(tx + 3f, y + padY + 5.2f, 2.8f, fill); tx += 9f }
                    draw(ls[j], tx, y + padY)
                    x += ws[j]
                }
                fill.color = LINE; c.drawRect(M, y + h - 0.4f, M + CW, y + h, fill)
                y += h; i++
            }
            y += 6f
        }
    }

    /** scrive il PDF del record (contratto STORIA_SIM.md) nel file indicato */
    fun write(rec: Map<String, Any?>, out: File) {
        val gen = "Generato il " + SimpleDateFormat("dd/MM/yyyy 'alle' HH:mm", Locale.ITALY).format(Date()) + " con similFranz Controller"
        val title = (rec["title"] as? String)?.ifBlank { null } ?: "Simulazione"
        val pass1 = Doc(0, title, gen); render(pass1, rec); val total = pass1.n; pass1.finish(null)
        val d = Doc(total, title, gen); render(d, rec); d.finish(out)
    }

    @Suppress("UNCHECKED_CAST")
    private fun render(d: Doc, rec: Map<String, Any?>) {
        d.newPage()
        val title = (rec["title"] as? String)?.ifBlank { null } ?: "Simulazione"
        // --- titolo ---
        val tl = lay(title, tp(21f, INK, BOLD), CW); d.draw(tl, M, d.y); d.y += tl.height + 4f
        d.c.drawText("Verbale di simulazione sanitaria · SIMULAZIONE, NON PER USO CLINICO", M, d.y + 9f, tp(9.5f, INK2)); d.y += 22f

        // --- blocco dati: 3 colonne × 2 righe ---
        val end = rec["end"] as? Map<String, Any?>
        val cells = listOf("Data e ora" to ((rec["date_text"] as? String) ?: "—"), "Durata" to mmss(num(rec["duration_s"])),
            "Fine scenario" to (if (end != null) "${mmss(num(end["t"]))} · ${wall(end["w"])}" else "—"),
            "Monitor" to ((rec["monitor"] as? String)?.ifBlank { null } ?: "—"),
            "Layout" to ((rec["layout_name"] as? String)?.ifBlank { null } ?: (rec["layout"] as? String) ?: "—"),
            "Salvato da" to ((rec["saved_by"] as? String) ?: "—"))
        val bw = CW / 3; val cap = tp(6.8f, INK2, BOLD); val vp = tp(10f, INK, BOLD)
        val rowsH = FloatArray(2)
        val ls = cells.map { lay(it.second, vp, bw - 20f) }
        for (i in 0 until 2) rowsH[i] = 10f + 10f + (0 until 3).maxOf { ls[i * 3 + it].height }.toFloat() + 9f
        val bh = rowsH[0] + rowsH[1]
        d.fill.color = BOX; d.c.drawRoundRect(RectF(M, d.y, M + CW, d.y + bh), 4f, 4f, d.fill)
        d.fill.color = LINE; d.c.drawRect(M + 10f, d.y + rowsH[0] - 0.25f, M + CW - 10f, d.y + rowsH[0] + 0.25f, d.fill)
        var yy = d.y
        for (r in 0 until 2) {
            for (k in 0 until 3) {
                val i = r * 3 + k; val x = M + k * bw + 10f
                d.c.drawText(cells[i].first.uppercase(Locale.ITALY), x, yy + 16f, cap)
                d.draw(ls[i], x, yy + 21f)
            }
            yy += rowsH[r]
        }
        d.y += bh + 4f

        // --- squadra ---
        val team = (rec["team"] as? List<Map<String, Any?>>) ?: emptyList()
        d.heading("Squadra ed esito", if (team.isNotEmpty()) (if (team.size == 1) "1 partecipante" else "${team.size} partecipanti") else null)
        if (team.isEmpty()) d.para("Nessun partecipante registrato.", 9f, INK2, ITAL)
        else d.table(listOf(Col("Nome", 0.19f), Col("Ruolo", 0.17f), Col("Obiettivi raggiunti", 0.32f), Col("Da rinforzare", 0.32f)),
            team.map { p -> listOf(Cell((p["name"] as? String)?.ifBlank { null } ?: "—", bold = true), Cell((p["role"] as? String)?.ifBlank { null } ?: "—", color = INK2),
                                   Cell((p["achieved"] as? String)?.ifBlank { null } ?: "—"), Cell((p["reinforce"] as? String)?.ifBlank { null } ?: "—")) })

        // --- riepilogo ---
        val s = (rec["summary"] as? Map<String, Any?>) ?: emptyMap()
        val arrest = s["arrest_at_s"] != null
        val shocks = (s["shocks"] as? List<Map<String, Any?>>) ?: emptyList(); val drugs = (s["drugs"] as? List<Map<String, Any?>>) ?: emptyList()
        val fromA = if (arrest) " dall'arresto" else ""
        fun sp(k: String) = if (s[k] is Number) span(s[k]) + fromA else "—"
        d.heading("Riepilogo")
        d.table(listOf(Col("Voce", 0.34f), Col("Valore", 0.66f)), listOf(
            listOf(Cell("Durata dello scenario", bold = true), Cell("${mmss(num(s["duration_s"] ?: rec["duration_s"]))} · ${obj(s["entries"] ?: 0)} voci nel registro")),
            listOf(Cell("Arresto cardiaco", bold = true), Cell(if (arrest) "al minuto ${mmss(num(s["arrest_at_s"]))} di scenario" else "non registrato", color = if (arrest) RED else INK2)),
            listOf(Cell("Riconoscimento", bold = true), Cell(sp("to_recognition_s"))),
            listOf(Cell("Prime compressioni", bold = true), Cell(sp("to_cpr_s"))),
            listOf(Cell("Prima scarica", bold = true), Cell(sp("to_first_shock_s"))),
            listOf(Cell("ROSC", bold = true), Cell(sp("to_rosc_s"))),
            listOf(Cell("Scariche erogate", bold = true), Cell(if (shocks.isEmpty()) "nessuna" else "${shocks.size}  —  " + shocks.joinToString("  ·  ") { "${obj(it["energy_j"])} J a ${mmss(num(it["t"]))}" })),
            listOf(Cell("Farmaci", bold = true), Cell(if (drugs.isEmpty()) "nessuno" else "${drugs.size}  —  " + drugs.joinToString("  ·  ") { "${mmss(num(it["t"]))} ${obj(it["text"])}" })),
            listOf(Cell("Segnati dall'istruttore", bold = true), Cell("${obj(s["plus"] ?: 0)} punti di forza · ${obj(s["minus"] ?: 0)} da discutere"))), zebra = true)

        // --- metriche RCP ---
        val cpr = rec["cpr"] as? Map<String, Any?>
        val cs = (cpr?.get("shocks") as? List<Map<String, Any?>>) ?: emptyList()
        val ccf = cpr?.get("ccf_percent") as? Number
        d.heading("Metriche RCP")
        if (cpr == null || (ccf == null && cs.isEmpty())) d.para("Nessuna metrica RCP registrata (nessun arresto cardiaco durante lo scenario).", 9f, INK2, ITAL)
        else {
            d.ensure(20f)
            if (ccf != null) { d.fill.color = ccfColor(ccf.toDouble()); d.c.drawCircle(M + 4f, d.y + 6f, 3.2f, d.fill) }
            val t = if (ccf == null) "Frazione di compressione: — (arresto troppo breve)"
                    else String.format(Locale.US, "Frazione di compressione %d %%   ·   arresto %s   ·   compressioni %s", Math.round(ccf.toDouble()), span(cpr["arrest_s"]), span(cpr["cpr_s"]))
            d.para(t, 10f, INK, BOLD, M + 12f, CW - 12f); d.y += 2f
            if (cs.isNotEmpty()) d.table(listOf(Col("N.", 0.07f), Col("Tempo", 0.11f), Col("Energia", 0.12f), Col("Pausa pre-scarica", 0.18f), Col("Pausa post-scarica", 0.18f), Col("Ritmo", 0.34f)),
                cs.mapIndexed { i, x ->
                    val pre = x["pre_s"] as? Number; val post = x["post_s"] as? Number
                    listOf(Cell(obj(x["n"] ?: (i + 1)), bold = true), Cell(mmss(num(x["t"]))), Cell("${obj(x["energy_j"])} J"),
                        Cell(if (pre != null) "${Math.round(pre.toDouble())} s" else "—", dot = pre?.let { pauseColor(it.toDouble()) }),
                        Cell(if (post != null) "${Math.round(post.toDouble())} s" else "—", dot = post?.let { pauseColor(it.toDouble()) }),
                        Cell(if (x["pulse"] == true) (if (x["sync"] == true) "con polso (SYNC)" else "con polso (senza SYNC)") else "senza polso", color = INK2))
                })
            d.para("Indicatori secondo le linee guida ERC — pause: verde ≤ 5 s, giallo ≤ 10 s, rosso oltre; frazione di compressione: verde ≥ 80 %, giallo ≥ 60 %, rosso sotto.", 7.3f, INK3)
        }

        // --- ANDROID (funzioni mancanti 22/11, IN-06): allarmi ---
        val al = s["alarms"] as? Map<String, Any?>
        d.heading("Allarmi")
        if (al == null) d.para("Il monitor di questa simulazione non registrava gli allarmi.", 9f, INK2, ITAL)
        else {
            d.para(CtlAlarmSummaryText(al), 9.5f, INK, BOLD)
            val items = (al["items"] as? List<Map<String, Any?>>) ?: emptyList()
            if (items.isNotEmpty()) d.table(listOf(Col("Inizio", 0.10f), Col("Priorità", 0.12f), Col("Allarme", 0.48f), Col("Durata", 0.14f), Col("Silenziato dopo", 0.16f)),
                items.map { x -> val lv = num(x["lvl"])
                    listOf(Cell(mmss(num(x["t"])), bold = true), Cell(when (lv) { 3 -> "ALTA"; 2 -> "MEDIA"; else -> "tecnica" }, bold = lv >= 2, color = if (lv == 3) RED else if (lv == 2) AMBER else INK2),
                           Cell(obj(x["alarm"])), Cell(if (x["dur_s"] is Number) span(x["dur_s"]) else "in corso"), Cell(if (x["mute_s"] is Number) "${num(x["mute_s"])} s" else "—", color = INK2)) })
        }
        // --- ANDROID (funzioni mancanti 22/11, IN-14): trend (passo 1 min) ---
        val tr = (rec["trend"] as? List<Map<String, Any?>>) ?: emptyList()
        d.heading("Trend dei parametri", if (tr.isEmpty()) null else "${tr.size} righe, una al minuto")
        if (tr.isEmpty()) d.para("Trend non registrato (monitor senza trend o simulazione senza «Avvia»).", 9f, INK2, ITAL)
        else {
            val lines = CtlTrendTableText(tr)
            val fr = listOf(0.09f, 0.08f, 0.08f, 0.20f, 0.18f, 0.08f, 0.09f, 0.10f, 0.10f)
            d.table(CtlTrendCols.mapIndexed { i, c -> Col(c, fr[i]) }, lines.map { l -> l.split("\t").mapIndexed { i, t -> Cell(t, bold = i == 0) } })
            d.para("Numeri come li mostrava il monitor ai partecipanti (— = non mostrato). NIBP senza parentesi = misura nuova in quel minuto.", 7.3f, INK3)
        }

        // --- cronologia ---
        val entries = ArrayList((rec["entries"] as? List<Map<String, Any?>>) ?: emptyList())
        if (end != null && entries.none { e -> num(e["t"]) >= num(end["t"]) - 3 && (e["tag"] == "scenario_end" || ((e["text"] as? String) ?: "").lowercase(Locale.ITALIAN).let { it.contains("scenario terminato") || it.contains("fine scenario") }) })
            entries.add(linkedMapOf("t" to end["t"], "wall" to end["w"], "k" to "phase", "text" to "Fine scenario", "_end" to true))
        val keyKinds = listOf("phase", "action", "drug", "plus", "minus", "note"); val keyTags = listOf("arrest", "rosc", "shock", "monitor_on")
        val key = entries.filter { (it["k"] as? String) in keyKinds || ((it["tag"] as? String) ?: "") in keyTags || IsFaultEntry(it) || it["_end"] == true }
        val cols = listOf(Col("Tempo", 0.09f), Col("Ora", 0.11f), Col("Categoria", 0.17f), Col("Evento", 0.63f))
        fun rowsOf(l: List<Map<String, Any?>>) = l.map { e ->
            val k = e["k"] as? String; val by = e["by"] as? String
            val strong = IsFaultEntry(e) || k == "plus" || k == "minus" || e["tag"] == "shock" || e["tag"] == "arrest" || e["tag"] == "rosc" || e["_end"] == true
            val txt = obj(e["text"]) + (if (!by.isNullOrEmpty() && by != "istruttore") "  — $by" else "")
            listOf(Cell(mmss(num(e["t"])), bold = true), Cell(wall(e["wall"]), color = INK2), Cell(CtlLogKindName(k), bold = true, color = kindColor(k), dot = kindColor(k), small = true),
                   Cell(txt, bold = strong, color = if (k == "timer") INK2 else INK))
        }
        d.heading("Cronologia — punti chiave", "${key.size} voci")
        if (key.isEmpty()) d.para("Nessuna voce.", 9f, INK2, ITAL) else d.table(cols, rowsOf(key))
        d.heading("Cronologia completa", "${entries.size} voci")
        if (entries.isEmpty()) d.para("Il registro di questa simulazione è vuoto.", 9f, INK2, ITAL) else d.table(cols, rowsOf(entries))
        d.para("Tempo = tempo di scenario (le pause non contano). Ora = orologio del dispositivo che ha salvato la simulazione.", 7.3f, INK3)
    }
}
