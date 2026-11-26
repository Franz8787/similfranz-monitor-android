// Portato da src/AltLayout.h (monitor iPad) — targhetta e testi per layout; il protocollo è in SFAltLayout.kt
package it.simulafranz.monitor

import it.simulafranz.core.SimulationState
import it.simulafranz.uk.*
import java.util.Locale
import kotlin.math.min

// Targhetta "SimulaFranz_Monitor" comune ai layout dei dispositivi: nome in evidenza + versione + "Layout",
// un tocco apre il menu dei layout (funziona anche a dispositivo spento).
fun SFLayoutButton(frame: CGRect, action: () -> Unit): UIButton {
    val f = frame
    val b = UIButton.buttonWithType(UIButtonTypeCustom)
    b.frame = f
    b.backgroundColor = UIColor.colorWithWhite(0.14, 0.94)
    b.layer.cornerRadius = 8.0; b.layer.borderWidth = 1.0; b.layer.borderColor = UIColor.colorWithWhite(0.48, 1).CGColor
    b.contentEdgeInsets = UIEdgeInsetsMake(2, 3, 2, 3)
    val ver = SFVersionShown()
    var fs = 12.0                                     // il nome deve stare su una riga sola
    while (fs > 8 && UkText.size("similFranz Monitor", UIFont.boldSystemFontOfSize(fs)).width > f.size.width - 10) fs -= 0.5
    var t = NSAttributedString("similFranz Monitor\n", UIFont.boldSystemFontOfSize(fs), UIColor.colorWithWhite(0.95, 1))
    t = t.append(String.format(Locale.US, "v%s  ·  Layout ▾︎", ver),
        UIFont.systemFontOfSize(10), UIColor.colorWithRed(0.55, 0.80, 1.0, 1))
    b.titleLabel.numberOfLines = 2; b.titleLabel.textAlignment = NSTextAlignmentCenter
    b.setAttributedTitle(t, UIControlStateNormal)
    b.accessibilityIdentifier = "LAYOUT"
    b.addTarget(UIControlEventTouchUpInside) { action() }
    // ANDROID (menu ☰ 21/11, richiesta utente): nome, versione e scelta del layout stanno SOLO nel menu dell'istruttore ☰:
    // la targhetta resta costruita (stessi tasti per i layout) ma non si vede e non si tocca, in tutti i layout.
    b.hidden = true; b.userInteractionEnabled = false
    return b
}


// La vista del layout registra le sue traduzioni (frase del cuore -> frase del dispositivo; null = nessuna traduzione,
// "" = il dispositivo non lo dice) e se il metronomo RCP del DAE suona. Valgono solo quando quel layout è attivo.
object SFAedLayoutTexts {
    val screen = HashMap<String, (String) -> String?>()
    val voice = HashMap<String, (String) -> String?>()
    val metronome = HashMap<String, () -> Boolean>()
    fun metronomeOn(layout: String?): Boolean = layout == null || (metronome[layout]?.invoke() ?: true)
}

// I layout personalizzati registrano qui sopra le loro traduzioni dei testi; senza traduzione vale la frase del modello.
fun SFLayoutIndex(layout: String?): Int = -1
@JvmName("SFAedScreenTextNonNull")
fun SFAedScreenText(layout: String?, msg: String): String = SFAedScreenText(layout, msg as String?)!!
fun SFAedScreenText(layout: String?, msg: String?): String? { if (layout != null && msg != null) SFAedLayoutTexts.screen[layout]?.invoke(msg)?.let { return it }; return msg }
@JvmName("SFAlarmTextNonNull")
fun SFAlarmText(layout: String?, msg: String): String = msg
fun SFAlarmText(layout: String?, msg: String?): String? = msg
@JvmName("SFAedVoiceTextNonNull")
fun SFAedVoiceText(layout: String?, v: String): String = SFAedVoiceText(layout, v as String?)!!
fun SFAedVoiceText(layout: String?, v: String?): String? { if (layout != null && v != null) SFAedLayoutTexts.voice[layout]?.invoke(v)?.let { return it }; return v }

const val SF_EDITION_TAG = ""
fun SFVersionShown(): String {
    val v = try { it.simulafranz.uk.Uk.ctx.packageManager.getPackageInfo(it.simulafranz.uk.Uk.ctx.packageName, 0).versionName ?: "" } catch (e: Exception) { "" }
    return if (SF_EDITION_TAG.isEmpty()) v else if (v.isEmpty()) SF_EDITION_TAG else "$v $SF_EDITION_TAG"
}
