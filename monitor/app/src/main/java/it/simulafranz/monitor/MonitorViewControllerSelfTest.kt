// Portato da src/MonitorViewController.m (monitor iPad)
// Parte 3 di 3: audit di sovrapposizioni e autotest (GENERIC ICU e layout dei dispositivi).
package it.simulafranz.monitor

import it.simulafranz.core.*
import it.simulafranz.uk.*
import java.math.BigDecimal
import java.math.MathContext
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

// MARK: - Audit di sovrapposizioni (pulsanti, etichette, testi tagliati)

private fun SFVisible(v: UIView): Boolean {
    var c: UIView? = v
    while (c != null) { if (c.hidden || c.alpha < 0.05f) return false; c = c.superview }
    return true
}
/** testo di un'etichetta come lo dà UILabel.text su iOS (anche quando è un testo con stili) */
private fun MVCLabelText(l: UILabel): String? = l.attributedText?.string ?: l.text
/** b.currentAttributedTitle.string ?: b.currentTitle */
private fun MVCButtonTitle(b: UIButton): String? = b.titleLabel.attributedText?.string ?: b.currentTitle
/** b.allTargets.count (in `uk` l'elenco dei bersagli è privato: si legge per riflessione; se non si riesce, si considera collegato) */
private fun MVCTargetCount(b: UIControl): Int {
    return try {
        val f = UIControl::class.java.getDeclaredField("targets")
        f.isAccessible = true
        (f.get(b) as? Collection<*>)?.size ?: 1
    } catch (e: Exception) { 1 }
}
private fun SFName(v: UIView): String {
    if (v is UIButton) {
        val b = v
        val t = MVCButtonTitle(b)
        if (!t.isNullOrEmpty()) return String.format(Locale.US, "tasto '%s'", t.replace("\n", " "))
        return if (!b.accessibilityIdentifier.isNullOrEmpty()) String.format(Locale.US, "tasto %s", b.accessibilityIdentifier) else "tasto (senza testo)"
    }
    if (v is UILabel) return String.format(Locale.US, "etichetta '%s'", (MVCLabelText(v) ?: "").replace("\n", " "))
    return v.javaClass.simpleName
}
private fun MVCg(d: Double): String {
    if (d == Math.rint(d) && Math.abs(d) < 1e15) return d.toLong().toString()
    return BigDecimal(d).round(MathContext(6)).stripTrailingZeros().toPlainString()
}
private fun NSStringFromCGRect(r: CGRect): String = "{{${MVCg(r.x)}, ${MVCg(r.y)}}, {${MVCg(r.width)}, ${MVCg(r.height)}}}"
private fun CGRectContainsRect(a: CGRect, b: CGRect): Boolean = b.minX >= a.minX && b.minY >= a.minY && b.maxX <= a.maxX && b.maxY <= a.maxY
/** CGRectIntersection: null al posto di CGRectNull */
private fun CGRectIntersection(a: CGRect, b: CGRect): CGRect? {
    val x0 = max(a.minX, b.minX); val y0 = max(a.minY, b.minY); val x1 = min(a.maxX, b.maxX); val y1 = min(a.maxY, b.maxY)
    if (x1 < x0 || y1 < y0) return null
    return CGRect(x0, y0, x1 - x0, y1 - y0)
}
/** boundingRectWithSize:options:NSStringDrawingUsesLineFragmentOrigin del testo dell'etichetta, alla larghezza data */
private fun MVCBoundingSize(l: UILabel, width: Double): CGSize {
    val tp = UkText.paint(l.font, l.textColor)
    val cs: CharSequence = l.attributedText?.let { UkText.styled(it, l.font, l.textColor, 1.0) } ?: (l.text ?: "")
    val lay = UkText.layout(cs, tp, ceil(min(width, 100000.0)).toInt(), NSTextAlignmentLeft, 0, false)
    var mw = 0f
    for (i in 0 until lay.lineCount) mw = max(mw, lay.getLineWidth(i))
    return CGSize(mw.toDouble(), lay.height.toDouble())
}

internal fun MonitorViewController.collect(v: UIView, into: ArrayList<UIView>) {
    val out = into
    if (!SFVisible(v)) return
    if (v is UIButton) {
        val b = v
        val hasText = !MVCButtonTitle(b).isNullOrEmpty()
        if (hasText || MVCTargetCount(b) > 0) out.add(v)
        return                                      // il titleLabel interno non conta a parte
    }
    if (v is UILabel && !MVCLabelText(v).isNullOrEmpty()) out.add(v)
    for (c in v.subviews) collect(c, out)
}
internal fun MonitorViewController.auditOverlapsIn(root: UIView, name: String): Int {
    val els = ArrayList<UIView>()
    collect(root, els)
    var issues = 0
    val screen = view.bounds
    val frames = ArrayList<CGRect>()
    for (v in els) frames.add(v.convertRect(v.bounds, view))
    for (i in els.indices) {
        val a = els[i]; val fa = frames[i]
        if (!CGRectContainsRect(CGRectInset(screen, -1, -1), fa)) { issues++; SFLog.log(String.format(Locale.US, "AUDIT %s fuori schermo: %s %s", name, SFName(a), NSStringFromCGRect(fa))) }
        // testo che non ci sta
        val l: UILabel = if (a is UIButton) a.titleLabel else a as UILabel
        if (!MVCLabelText(l).isNullOrEmpty() && !l.adjustsFontSizeToFitWidth) {
            val avail = if (a is UIButton) UIEdgeInsetsInsetRect(a.bounds, a.titleEdgeInsets).size else a.bounds.size
            val need = MVCBoundingSize(l, if (l.numberOfLines == 1) Double.MAX_VALUE else avail.width)
            if (need.width > avail.width + 1.5 || (l.numberOfLines != 1 && need.height > avail.height + 2))
                { issues++; SFLog.log(String.format(Locale.US, "AUDIT %s testo tagliato: %s serve %.0fx%.0f, ha %.0fx%.0f", name, SFName(a), need.width, need.height, avail.width, avail.height)) }
        }
        for (j in i + 1 until els.size) {
            val b = els[j]; val fb = frames[j]
            if (a.isDescendantOfView(b) || b.isDescendantOfView(a)) continue
            val x = CGRectIntersection(CGRectInset(fa, 1, 1), CGRectInset(fb, 1, 1))
            if (x == null || x.size.width < 2 || x.size.height < 2) continue
            val bothButtons = a is UIButton && b is UIButton
            val bothLabels = a is UILabel && b is UILabel
            // etichetta sopra un tasto senza testo = scritta del tasto (voluta); tasto invisibile su testo = area di tocco (voluta)
            if (!bothButtons && !bothLabels) {
                val btn = (if (a is UIButton) a else b) as UIButton
                val titled = !MVCButtonTitle(btn).isNullOrEmpty()
                if (!titled) continue
            }
            if (bothButtons) {
                val ba = a as UIButton; val bb = b as UIButton
                val ta = !MVCButtonTitle(ba).isNullOrEmpty(); val tb = !MVCButtonTitle(bb).isNullOrEmpty()
                if (!ta && !tb && fa == fb) continue
            }
            issues++
            SFLog.log(String.format(Locale.US, "AUDIT %s sovrapposti: %s %s / %s %s", name, SFName(a), NSStringFromCGRect(fa), SFName(b), NSStringFromCGRect(fb)))
        }
    }
    SFLog.log(String.format(Locale.US, "AUDIT %s: %d elementi, %d segnalazioni", name, els.size, issues))
    return issues
}
internal fun MonitorViewController.checkLayoutKeyNamed(name: String) {
    var k: UIView? = null; val st = arrayListOf<UIView>(alt ?: view)
    while (st.isNotEmpty() && k == null) { val v = st.removeAt(st.size - 1); if (v.accessibilityIdentifier == "LAYOUT" && v is UIButton) k = v else st.addAll(v.subviews) }
    var ok = false
    if (k != null) { val p = k.convertPoint(CGPointMake(CGRectGetMidX(k.bounds), CGRectGetMidY(k.bounds)), view.window)
             var h = view.window?.hitTest(p); while (h != null && h !is UIControl) h = h.superview; ok = (h === k) }
    var aedLike = name == "dae"
    aedLike = aedLike || SFLayoutIsAed(name)
    // ANDROID (menu ☰ 21/11): la targhetta Layout non c'è più a schermo (il layout si sceglie dal menu ☰)
    if (aedLike && ok) SFLog.log(String.format(Locale.US, "SELFTEST FAIL targhetta Layout ancora visibile (%s)", name))
    auditOverlapsIn(alt ?: view, name)
}

// autotest di un layout di dispositivo: stesso schema per tutti (azzeramento, layout, esito "decide l'istruttore", voce spenta)
internal fun MonitorViewController.runDeviceSelfTest(layout: String, title: String, after: Map<String, Any?>?) {
    val restore = after
    removeSplash()
    SFLog.log(String.format(Locale.US, "SELFTEST inizio %s", title))
    state.resetScenario()
    state.applyCommand(mapOf("command" to "set_layout", "value" to layout))
    state.applyCommand(mapOf("command" to "set_defib_config", "value" to mapOf("outcome_mode" to "instructor", "voice_prompts" to false)))
    applyLayout(layout)
    checkLayoutKeyNamed(layout)
    altL!!.runSelfTestWithShot({ name -> saveShot(name) }, { fails ->
        val cfg = linkedMapOf<String, Any?>("outcome_mode" to "instructor", "voice_prompts" to true); cfg.putAll(restore ?: emptyMap())
        state.applyCommand(mapOf("command" to "set_defib_config", "value" to cfg))
        state.resetScenario()
    })
}
fun MonitorViewController.runSelfTestExtraH() { runDeviceSelfTest("extrah", "EXTRAH", null) }  // layout originale extraH
fun MonitorViewController.runSelfTestDae() { runDeviceSelfTest("dae", "DAE", mapOf("aed_energy_j" to 150)) }     // layout originale DAE

fun MonitorViewController.runSelfTest() {
    removeSplash()          // niente splash durante l'autotest
    SFLog.log("SELFTEST inizio")
    state.resetScenario()
    state.applyCommand(mapOf("command" to "set_layout", "value" to "generic_icu"))
    applyLayout("generic_icu")
    standbyTapped()                                         // il reset spegne il monitor: l'autotest lavora a monitor acceso
    auditOverlapsIn(view, "generic_icu")
    for (k in listOf("ecg", "spo2", "nibp")) state.applyCommand(mapOf("command" to "set_sensor", "value" to mapOf("sensor" to k, "connected" to true)))
    state.applyCommand(mapOf("command" to "set_defib_config", "value" to mapOf("outcome_mode" to "instructor", "voice_prompts" to false)))
    for (b in listOf(muteButton, drawerTab)) {
        val p = b.convertPoint(CGPointMake(CGRectGetMidX(b.bounds), CGRectGetMidY(b.bounds)), view.window)
        val hit = view.window?.hitTest(p)
        SFLog.log(String.format(Locale.US, "SELFTEST %s pulsante intestazione %s", if (hit === b) "PASS" else "FAIL", b.titleForState(UIControlStateNormal)))
    }
    toggleMute()
    SFLog.log(String.format(Locale.US, "SELFTEST %s SILENZIA dall'intestazione", if (state.snapshot().alarmsMuted) "PASS" else "FAIL"))
    toggleMute()
    if (!drawerOpen) toggleDrawer()
    drawer.runSelfTestWithShot({ name -> saveShot(name) }, { fails ->
        toggleDrawer()
        Uk.after(0.6) {
            saveShot("09-monitor")
            assessCrt(); Uk.after(3.6) {
                updateCrt(); SFLog.log(String.format(Locale.US, "SELFTEST %s refill dopo la valutazione: %s", if (crtValue.text == "2") "PASS" else "FAIL", crtValue.text))
                saveShot("10-refill") }
            state.applyCommand(mapOf("command" to "set_defib_config", "value" to mapOf("outcome_mode" to "instructor", "voice_prompts" to true)))
            state.resetScenario()
        }
    })
}
