package it.simulafranz.uk

import android.graphics.Canvas
import android.text.StaticLayout
import kotlin.math.ceil

/** UILabel: testo centrato in verticale, una o più righe, con riduzione automatica del carattere. */
open class UILabel(frame: CGRect = CGRect()) : UIView(frame) {
    var text: String? = null
        set(v) { if (field == v && attributedText == null) return; field = v; attributedText0 = null; changed() }
    private var attributedText0: NSAttributedString? = null
    var attributedText: NSAttributedString?
        get() = attributedText0
        set(v) { attributedText0 = v; changed() }
    var font: UIFont = UIFont.systemFontOfSize(17)
        set(v) { field = v; changed() }
    var textColor: UIColor = UIColor.blackColor
        set(v) { if (field == v) return; field = v; changed() }
    // textAlignment esiste già nelle viste Android con lo stesso nome: qui vale NSTextAlignmentLeft/Center/Right
    private var align: Int = NSTextAlignmentLeft
    override fun getTextAlignment(): Int = align
    override fun setTextAlignment(textAlignment: Int) { align = textAlignment; changed() }
    var numberOfLines: Int = 1
        set(v) { field = v; changed() }
    var adjustsFontSizeToFitWidth: Boolean = false
        set(v) { field = v; changed() }
    var minimumScaleFactor: Double = 0.0
        set(v) { field = v; changed() }
    var lineBreakMode: Int = NSLineBreakByTruncatingTail
        set(v) { field = v; changed() }
    var shadowColor: UIColor? = null
        set(v) { field = v; changed() }
    var shadowOffset: CGSize = CGSize(0.0, -1.0)
        set(v) { field = v; changed() }
    /** spaziatura tra le lettere in punti (NSKernAttributeName) */
    var kern: Double = 0.0
        set(v) { field = v; changed() }

    private var cache: StaticLayout? = null
    private var cacheW = -1.0
    private fun changed() { cache = null; invalidate() }

    init { userInteractionEnabled = false }

    // ANDROID (simboli 22/11): i simboli (▶ ♥ ⚠ ✓ …) sono disegnati vettoriali, mai emoji del sistema (UkSymbols.kt)
    private fun content(k: Double): CharSequence = UkSymbols.apply(attributedText0?.let { UkText.styled(it, font, textColor, k) } ?: (text ?: ""))

    private fun build(w: Double, h: Double): StaticLayout {
        cache?.let { if (cacheW == w) return it }
        val lines = if (numberOfLines <= 0) 0 else numberOfLines
        var k = 1.0
        val minK = if (adjustsFontSizeToFitWidth) (if (minimumScaleFactor > 0) minimumScaleFactor else 0.5) else 1.0
        var l: StaticLayout
        while (true) {
            val tp = UkText.paint(font, textColor, k)
            if (kern != 0.0) tp.letterSpacing = (kern / (font.pointSize * k)).toFloat()
            // ombra del testo (UILabel.shadowColor / shadowOffset): netta, senza sfocatura, in punti
            shadowColor?.let { sc -> if (sc.a > 0) tp.setShadowLayer(0.01f, shadowOffset.width.toFloat(), shadowOffset.height.toFloat(), sc.argb) }
            val cs = content(k)
            if (numberOfLines == 1) {
                // una riga: se non ci sta si riduce il carattere, poi si tronca
                val need = android.text.Layout.getDesiredWidth(cs, tp)
                if (need > w && k > minK + 0.001) { k = maxOf(minK, minOf(k - 0.04, k * w / need)); continue }
                l = UkText.layout(cs, tp, ceil(w).toInt(), align, 1, true)
            } else {
                l = UkText.layout(cs, tp, ceil(w).toInt(), align, lines, true)
                if (adjustsFontSizeToFitWidth && k > minK + 0.001 && (l.height > h + 0.5 || (lines > 0 && l.lineCount >= lines && l.getEllipsisCount(l.lineCount - 1) > 0))) { k = maxOf(minK, k - 0.05); continue }
            }
            break
        }
        cache = l; cacheW = w
        return l
    }

    override fun drawRect(ctx: CGContext, rect: CGRect) {
        if (text.isNullOrEmpty() && attributedText0 == null) return
        if (rect.width <= 0) return
        val l = build(rect.width, rect.height)
        val c: Canvas = ctx.canvas
        c.save()
        c.translate(0f, ((rect.height - l.height) / 2).toFloat())
        l.draw(c)
        c.restore()
    }

    override fun layoutSubviews() { cache = null }

    override fun sizeThatFits(size: CGSize): CGSize {
        val tp = UkText.paint(font, textColor)
        val cs = content(1.0)
        if (numberOfLines == 1) { val fm = tp.fontMetrics; return CGSize(ceil(android.text.Layout.getDesiredWidth(cs, tp).toDouble()), ceil((fm.descent - fm.ascent).toDouble())) }
        val w = if (size.width > 0) size.width else 10000.0
        val l = UkText.layout(cs, tp, ceil(w).toInt(), NSTextAlignmentLeft, if (numberOfLines <= 0) 0 else numberOfLines, false)
        var mw = 0f; for (i in 0 until l.lineCount) mw = maxOf(mw, l.getLineWidth(i))
        return CGSize(ceil(mw.toDouble()), l.height.toDouble())
    }
}
