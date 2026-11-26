// ANDROID (12 derivazioni 22/11): anteprima della carta 12D sul Mac (solo per chi sviluppa il modello, non è un test).
// Uso: SF_ECG12_ANTEPRIMA=/cartella scripts/gradle.sh :core-tests:run  -> un PNG per morfologia (carta 25 mm/s, 10 mm/mV).
package it.simulafranz.coretests

import it.simulafranz.core.*
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Font
import java.awt.RenderingHints
import java.awt.geom.Path2D
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO

fun ecg12Anteprima(dir: String) {
    val cases = listOf(
        "normale" to mapOf<String, Any?>(),
        "stemi_inferiore" to mapOf("st" to "stemi_inferior"),
        "stemi_inferoposteriore" to mapOf("st" to "stemi_inferoposterior"),
        "stemi_anteriore" to mapOf("st" to "stemi_anterior"),
        "stemi_laterale" to mapOf("st" to "stemi_lateral"),
        "stemi_posteriore" to mapOf("st" to "stemi_posterior"),
        "nstemi" to mapOf("st" to "nstemi_depression"),
        "tronco_comune" to mapOf("st" to "left_main"),
        "wellens" to mapOf("st" to "wellens"),
        "de_winter" to mapOf("st" to "de_winter"),
        "pericardite" to mapOf("st" to "pericarditis"),
        "bbd" to mapOf("bbb" to "rbbb"), "bbs" to mapOf("bbb" to "lbbb"), "eas" to mapOf("bbb" to "lafb"), "bifascicolare" to mapOf("bbb" to "rbbb_lafb"),
        "bav1" to mapOf("av_block" to "first"), "bav2_mobitz1" to mapOf("av_block" to "mobitz1"), "bav2_mobitz2" to mapOf("av_block" to "mobitz2"),
        "bav_2a1" to mapOf("av_block" to "2to1"), "bav_avanzato" to mapOf("av_block" to "high_grade"), "bav3" to mapOf("av_block" to "third"),
        "bav3_giunzionale" to mapOf("av_block" to "third", "escape" to "junctional"),
        "iperk" to mapOf("extra" to listOf("hyperkalemia")), "iperk_grave" to mapOf("extra" to listOf("hyperkalemia_severe")),
        "ipok" to mapOf("extra" to listOf("hypokalemia")), "qt_lungo" to mapOf("extra" to listOf("long_qt")), "ivs" to mapOf("extra" to listOf("lvh")),
        "wpw" to mapOf("extra" to listOf("wpw")), "brugada" to mapOf("extra" to listOf("brugada")), "ep" to mapOf("extra" to listOf("pe")),
        "ipotermia" to mapOf("extra" to listOf("hypothermia")), "bassi_voltaggi" to mapOf("extra" to listOf("low_voltage")),
        "bbs_stemi_ant" to mapOf("bbb" to "lbbb", "st" to "stemi_anterior"))
    File(dir).mkdirs()
    for ((name, m) in cases) {
        val s = NewState()
        Cmd(s, "set_ecg_morphology", m)
        val e = WaveformEngine(11)
        var t = gNow
        e.updateWithSnapshot(s.snapshot())
        while (t < gNow + 12.0) { e.ecgAt(t, SLLeadII); e.ecgAt(t, SLLeadV); t += 0.004; if (((t - gNow) * 250).toInt() % 125 == 0) e.updateWithSnapshot(s.snapshot()) }
        val c = e.capture12()
        draw(c, File(dir, "$name.png"))
    }
    println("anteprime 12D in $dir")
}

private fun draw(c: WaveformEngine.SLEcg12Capture, f: File) {
    val mm = 5.0
    val W = (262 * mm).toInt(); val H = (190 * mm).toInt()
    val img = BufferedImage(W, H, BufferedImage.TYPE_INT_RGB)
    val g = img.createGraphics()
    g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
    g.color = Color(255, 247, 247); g.fillRect(0, 0, W, H)
    for (i in 0..(W / mm).toInt()) { g.color = if (i % 5 == 0) Color(240, 150, 150) else Color(250, 215, 215); g.drawLine((i * mm).toInt(), 0, (i * mm).toInt(), H) }
    for (i in 0..(H / mm).toInt()) { g.color = if (i % 5 == 0) Color(240, 150, 150) else Color(250, 215, 215); g.drawLine(0, (i * mm).toInt(), W, (i * mm).toInt()) }
    g.color = Color.BLACK; g.font = Font("SansSerif", Font.PLAIN, 18)
    val ms = c.measures
    g.drawString(String.format("FC %d  PR %s  QRS %s  QT/QTc %s  Assi %s", c.hr, ms?.prMs ?: "---", ms?.qrsMs ?: "---",
        if (ms != null) "${ms.qtMs}/${ms.qtcMs}" else "---", if (ms != null) "${ms.axisP ?: "--"} ${ms.axisQrs} ${ms.axisT}" else "---"), 20, 24)
    g.drawString((c.reading.headline ?: "") + "  " + c.reading.lines.joinToString(" | "), 20, 48)
    g.stroke = BasicStroke(1.6f)
    val x0 = 6 * mm
    val order = arrayOf(intArrayOf(SL12I, SL12aVR, SL12V1, SL12V4), intArrayOf(SL12II, SL12aVL, SL12V2, SL12V5), intArrayOf(SL12III, SL12aVF, SL12V3, SL12V6))
    fun trace(lead: Int, s0: Int, s1: Int, xs: Double, yb: Double) {
        val p = Path2D.Double()
        for (k in s0 until s1) { val x = xs + (k - s0) / c.fs.toDouble() * 25 * mm; val y = yb - c.leads[lead][k] * 10 * mm; if (k == s0) p.moveTo(x, y) else p.lineTo(x, y) }
        g.draw(p)
    }
    val n = c.leads[0].size; val q = n / 4
    for (r in 0 until 3) {
        val yb = (35 + r * 38) * mm
        // calibrazione
        g.drawLine(5, yb.toInt(), (2 * mm).toInt(), yb.toInt())
        for (col in 0 until 4) {
            val l = order[r][col]
            trace(l, col * q, (col + 1) * q, x0 + col * 62.5 * mm, yb)
            g.drawString(SL12LeadNames[l], (x0 + col * 62.5 * mm + 4).toInt(), (yb - 12 * mm).toInt())
        }
    }
    trace(SL12II, 0, n, x0, 170 * mm); g.drawString("II", (x0 + 4).toInt(), (158 * mm).toInt())
    ImageIO.write(img, "png", f)
}
