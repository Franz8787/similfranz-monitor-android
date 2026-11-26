package it.simulafranz.uk

import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import android.util.LruCache

/**
 * Ombre sfumate calcolate UNA volta e conservate come immagine (su Android un'ombra con sfocatura disegnata a ogni
 * fotogramma costa moltissimo al thread grafico). L'immagine contiene già il "buco" della forma, come su iOS,
 * dove l'ombra sta solo fuori dalla vista.
 */
object UkShadow {
    private val cache = object : LruCache<String, Bitmap>(24 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }
    private val blurP = Paint(Paint.ANTI_ALIAS_FLAG)
    private val clearP = Paint(Paint.ANTI_ALIAS_FLAG).apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR) }
    private val drawP = Paint(Paint.FILTER_BITMAP_FLAG)
    private val dst = RectF()

    /**
     * Disegna l'ombra della forma `shape` (in punti, origine della vista). key identifica la forma (dimensioni, raggio
     * o percorso): stessa chiave = stessa immagine. blur = raggio di sfocatura in punti; color già con l'opacità dell'ombra.
     */
    fun draw(c: Canvas, key: String, shape: Path, w: Double, h: Double, blur: Double, dx: Double, dy: Double, color: Int) {
        val k = Uk.scale.toDouble()
        val pad = blur * 2 + 2
        val full = "$key|$blur|$dx|$dy|$color|${Uk.scale}"
        var bmp = cache.get(full)
        if (bmp == null) {
            val bw = Math.ceil((w + 2 * pad + Math.abs(dx)) * k).toInt().coerceAtLeast(1)
            val bh = Math.ceil((h + 2 * pad + Math.abs(dy)) * k).toInt().coerceAtLeast(1)
            if (bw * bh > 4_000_000) return
            bmp = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888)
            val bc = Canvas(bmp)
            bc.scale(k.toFloat(), k.toFloat())
            // la forma spostata dell'offset, sfocata
            val ox = pad + maxOf(0.0, -dx); val oy = pad + maxOf(0.0, -dy)
            bc.save(); bc.translate((ox + dx).toFloat(), (oy + dy).toFloat())
            blurP.color = color
            blurP.maskFilter = if (blur > 0.05) BlurMaskFilter((blur * k).toFloat() / k.toFloat(), BlurMaskFilter.Blur.NORMAL) else null
            bc.drawPath(shape, blurP); bc.restore()
            // il buco: dove c'è la vista l'ombra non si vede
            bc.save(); bc.translate(ox.toFloat(), oy.toFloat()); bc.drawPath(shape, clearP); bc.restore()
            cache.put(full, bmp)
        }
        val ox = pad + maxOf(0.0, -dx); val oy = pad + maxOf(0.0, -dy)
        dst.set((-ox).toFloat(), (-oy).toFloat(), (-ox + bmp.width / k).toFloat(), (-oy + bmp.height / k).toFloat())
        c.drawBitmap(bmp, null, dst, drawP)
    }
}
