// ANDROID (simboli 22/11): nessun carattere Unicode «da icona» passa dal carattere di sistema. Android può renderli come
// emoji a colori (▶ ◀ ♥ ⚠ ⚡ ★ ✚ …) o con un altro font: qui ogni simbolo è DISEGNATO vettoriale nel colore e nella
// dimensione del testo in cui si trova (ReplacementSpan). Vale per tutte le UILabel, i titoli dei tasti e drawText(inRect):
// passano tutti da UkText.layout. Il testo dell'etichetta non cambia (le prove continuano a leggere «✓», «▲», …).
package it.simulafranz.uk

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ReplacementSpan
import kotlin.math.cos
import kotlin.math.sin

/** Simbolo disegnato al posto del carattere `ch` (più l'eventuale selettore di variante che lo segue). */
class UkSymbolSpan(private val ch: Char) : ReplacementSpan() {
    override fun getSize(paint: Paint, text: CharSequence?, start: Int, end: Int, fm: Paint.FontMetricsInt?): Int {
        if (fm != null) { val m = paint.fontMetricsInt; fm.ascent = m.ascent; fm.descent = m.descent; fm.top = m.top; fm.bottom = m.bottom; fm.leading = m.leading }
        return Math.round(paint.textSize * UkSymbols.advance(ch))
    }
    override fun draw(canvas: Canvas, text: CharSequence?, start: Int, end: Int, x: Float, top: Int, y: Int, bottom: Int, paint: Paint) {
        val em = paint.textSize; val s = em * 0.78f; val adv = em * UkSymbols.advance(ch)
        UkSymbols.draw(canvas, ch, x + (adv - s) / 2, y + em * 0.05f - s, s, paint.color)
    }
}

object UkSymbols {
    private const val FILLED = "▶►▸◀◄◂▲▴▼▾♥❤⚡★✚●■⧗"
    private const val STROKED = "△▽☆⚠✓✔✗✕✖○◉◷⌂▭∿↑↓←→☰⏻︿﹀"
    private val small = "▸◂▴▾"
    fun handles(c: Char): Boolean = c >= '←' && (FILLED.indexOf(c) >= 0 || STROKED.indexOf(c) >= 0)
    /** larghezza occupata, in frazione del corpo del carattere */
    fun advance(c: Char): Float = if (small.indexOf(c) >= 0) 0.62f else if (c == '⚡') 0.62f else if (c == '▭') 1.0f else 0.88f

    /** Se nel testo ci sono simboli, restituisce una copia con gli span di disegno; altrimenti il testo com'è. */
    fun apply(cs: CharSequence): CharSequence {
        var any = false
        for (i in 0 until cs.length) if (cs[i] >= '←' && handles(cs[i])) { any = true; break }
        if (!any) return cs
        if (cs is Spanned && cs.getSpans(0, cs.length, UkSymbolSpan::class.java).isNotEmpty()) return cs
        val s = SpannableString(cs)
        var i = 0
        while (i < cs.length) {
            val c = cs[i]
            if (handles(c)) {
                var e = i + 1
                if (e < cs.length && (cs[e] == '︎' || cs[e] == '️')) e++      // selettore di variante (testo/emoji)
                s.setSpan(UkSymbolSpan(c), i, e, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE); i = e
            } else i++
        }
        return s
    }

    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()
    private fun poly(x: Float, y: Float, s: Float, vararg q: Float) {
        path.rewind()
        var i = 0; while (i < q.size) { if (i == 0) path.moveTo(x + q[i] * s, y + q[i + 1] * s) else path.lineTo(x + q[i] * s, y + q[i + 1] * s); i += 2 }
    }

    /** Disegna il simbolo nel quadrato (x, y, s, s) col colore dato. */
    fun draw(c: Canvas, ch: Char, x0: Float, y0: Float, s0: Float, color: Int) {
        var x = x0; var y = y0; var s = s0
        if (small.indexOf(ch) >= 0) { x += s * 0.16f; y += s * 0.18f; s *= 0.68f }
        p.color = color; p.shader = null; p.pathEffect = null
        p.strokeWidth = s * 0.11f; p.strokeCap = Paint.Cap.ROUND; p.strokeJoin = Paint.Join.ROUND
        p.style = if (FILLED.indexOf(ch) >= 0) Paint.Style.FILL else Paint.Style.STROKE
        when (ch) {
            '▶', '►', '▸' -> { poly(x, y, s, 0.14f, 0.06f, 0.94f, 0.5f, 0.14f, 0.94f); path.close(); c.drawPath(path, p) }
            '◀', '◄', '◂' -> { poly(x, y, s, 0.86f, 0.06f, 0.06f, 0.5f, 0.86f, 0.94f); path.close(); c.drawPath(path, p) }
            '▲', '▴' -> { poly(x, y, s, 0.5f, 0.10f, 0.96f, 0.90f, 0.04f, 0.90f); path.close(); c.drawPath(path, p) }
            '▼', '▾' -> { poly(x, y, s, 0.04f, 0.14f, 0.96f, 0.14f, 0.5f, 0.94f); path.close(); c.drawPath(path, p) }
            '△' -> { poly(x, y, s, 0.5f, 0.12f, 0.94f, 0.88f, 0.06f, 0.88f); path.close(); c.drawPath(path, p) }
            '▽' -> { poly(x, y, s, 0.06f, 0.14f, 0.94f, 0.14f, 0.5f, 0.90f); path.close(); c.drawPath(path, p) }
            '♥', '❤' -> {
                path.rewind(); path.moveTo(x + 0.5f * s, y + 0.94f * s)
                path.cubicTo(x + 0.10f * s, y + 0.64f * s, x - 0.02f * s, y + 0.40f * s, x + 0.07f * s, y + 0.24f * s)
                path.cubicTo(x + 0.19f * s, y + 0.03f * s, x + 0.44f * s, y + 0.07f * s, x + 0.5f * s, y + 0.28f * s)
                path.cubicTo(x + 0.56f * s, y + 0.07f * s, x + 0.81f * s, y + 0.03f * s, x + 0.93f * s, y + 0.24f * s)
                path.cubicTo(x + 1.02f * s, y + 0.40f * s, x + 0.90f * s, y + 0.64f * s, x + 0.5f * s, y + 0.94f * s)
                path.close(); c.drawPath(path, p)
            }
            '⚡' -> { val w = 0.62f; val o = (1 - w) / 2
                poly(x, y, s, o + 0.50f * w, 0f, o + 0.92f * w, 0f, o + 0.60f * w, 0.40f, o + 0.98f * w, 0.40f, o + 0.22f * w, 1f, o + 0.42f * w, 0.55f, o + 0.04f * w, 0.55f)
                path.close(); c.drawPath(path, p) }
            '★', '☆' -> {
                path.rewind()
                for (i in 0 until 10) { val r = if (i % 2 == 0) 0.50 else 0.21; val a = -Math.PI / 2 + i * Math.PI / 5
                    val px = x + (0.5 + r * cos(a)).toFloat() * s; val py = y + (0.53 + r * sin(a)).toFloat() * s
                    if (i == 0) path.moveTo(px, py) else path.lineTo(px, py) }
                path.close(); if (ch == '☆') p.strokeWidth = s * 0.07f; c.drawPath(path, p)
            }
            '✚' -> { c.drawRect(x + 0.36f * s, y + 0.08f * s, x + 0.64f * s, y + 0.92f * s, p); c.drawRect(x + 0.08f * s, y + 0.36f * s, x + 0.92f * s, y + 0.64f * s, p) }
            '●' -> c.drawCircle(x + 0.5f * s, y + 0.5f * s, 0.40f * s, p)
            '■' -> c.drawRect(x + 0.12f * s, y + 0.12f * s, x + 0.88f * s, y + 0.88f * s, p)
            '○' -> c.drawCircle(x + 0.5f * s, y + 0.5f * s, 0.37f * s, p)
            '◉' -> { c.drawCircle(x + 0.5f * s, y + 0.5f * s, 0.37f * s, p); p.style = Paint.Style.FILL; c.drawCircle(x + 0.5f * s, y + 0.5f * s, 0.19f * s, p) }
            '◷' -> { c.drawCircle(x + 0.5f * s, y + 0.5f * s, 0.40f * s, p); poly(x, y, s, 0.5f, 0.24f, 0.5f, 0.5f, 0.70f, 0.5f); c.drawPath(path, p) }
            '⚠' -> { p.strokeWidth = s * 0.09f; poly(x, y, s, 0.5f, 0.10f, 0.95f, 0.88f, 0.05f, 0.88f); path.close(); c.drawPath(path, p)
                c.drawLine(x + 0.5f * s, y + 0.40f * s, x + 0.5f * s, y + 0.60f * s, p); p.style = Paint.Style.FILL; c.drawCircle(x + 0.5f * s, y + 0.75f * s, 0.055f * s, p) }
            '✓', '✔' -> { p.strokeWidth = s * 0.14f; poly(x, y, s, 0.10f, 0.55f, 0.38f, 0.83f, 0.92f, 0.16f); c.drawPath(path, p) }
            '✗', '✕', '✖' -> { p.strokeWidth = s * 0.13f; c.drawLine(x + 0.14f * s, y + 0.14f * s, x + 0.86f * s, y + 0.86f * s, p); c.drawLine(x + 0.86f * s, y + 0.14f * s, x + 0.14f * s, y + 0.86f * s, p) }
            '⌂' -> { poly(x, y, s, 0.06f, 0.52f, 0.5f, 0.10f, 0.94f, 0.52f); c.drawPath(path, p)
                poly(x, y, s, 0.20f, 0.44f, 0.20f, 0.92f, 0.80f, 0.92f, 0.80f, 0.44f); c.drawPath(path, p) }
            '▭' -> { p.strokeWidth = s * 0.08f; c.drawRect(x - 0.06f * s, y + 0.26f * s, x + 1.06f * s, y + 0.78f * s, p) }
            '∿' -> { path.rewind(); path.moveTo(x + 0.04f * s, y + 0.5f * s)
                path.cubicTo(x + 0.20f * s, y + 0.02f * s, x + 0.36f * s, y + 0.02f * s, x + 0.5f * s, y + 0.5f * s)
                path.cubicTo(x + 0.64f * s, y + 0.98f * s, x + 0.80f * s, y + 0.98f * s, x + 0.96f * s, y + 0.5f * s); c.drawPath(path, p) }
            '⧗' -> { poly(x, y, s, 0.18f, 0.06f, 0.82f, 0.06f, 0.5f, 0.5f, 0.82f, 0.94f, 0.18f, 0.94f, 0.5f, 0.5f); path.close(); c.drawPath(path, p) }
            '☰' -> { for (k in 0 until 3) c.drawLine(x + 0.10f * s, y + (0.24f + 0.26f * k) * s, x + 0.90f * s, y + (0.24f + 0.26f * k) * s, p) }
            '⏻' -> { c.drawArc(x + 0.12f * s, y + 0.16f * s, x + 0.88f * s, y + 0.92f * s, -60f, 300f, false, p); c.drawLine(x + 0.5f * s, y + 0.06f * s, x + 0.5f * s, y + 0.48f * s, p) }
            '︿' -> { poly(x, y, s, 0.06f, 0.68f, 0.5f, 0.30f, 0.94f, 0.68f); c.drawPath(path, p) }
            '﹀' -> { poly(x, y, s, 0.06f, 0.32f, 0.5f, 0.70f, 0.94f, 0.32f); c.drawPath(path, p) }
            '↑' -> { c.drawLine(x + 0.5f * s, y + 0.12f * s, x + 0.5f * s, y + 0.92f * s, p); poly(x, y, s, 0.20f, 0.42f, 0.5f, 0.12f, 0.80f, 0.42f); c.drawPath(path, p) }
            '↓' -> { c.drawLine(x + 0.5f * s, y + 0.08f * s, x + 0.5f * s, y + 0.88f * s, p); poly(x, y, s, 0.20f, 0.58f, 0.5f, 0.88f, 0.80f, 0.58f); c.drawPath(path, p) }
            '←' -> { c.drawLine(x + 0.08f * s, y + 0.5f * s, x + 0.92f * s, y + 0.5f * s, p); poly(x, y, s, 0.38f, 0.20f, 0.08f, 0.5f, 0.38f, 0.80f); c.drawPath(path, p) }
            '→' -> { c.drawLine(x + 0.08f * s, y + 0.5f * s, x + 0.92f * s, y + 0.5f * s, p); poly(x, y, s, 0.62f, 0.20f, 0.92f, 0.5f, 0.62f, 0.80f); c.drawPath(path, p) }
        }
    }
}
