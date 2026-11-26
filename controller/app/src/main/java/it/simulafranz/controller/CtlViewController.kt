// Portato da src/CtlViewController.m (controller iPad)
// similFranz Controller — schermata unica dell'istruttore ("cabina di regia"):
//   in alto     le tessere con i valori VERI del paziente (toccarne una apre la sua regolazione al centro)
//   a sinistra  Stati (pacchetti one-tap) oppure Scenario (fasi che l'istruttore fa avanzare)
//   al centro   la regolazione del parametro scelto (cursore, − / +, valori rapidi, ritmi)
//   a destra    sonde applicate al paziente e azioni rapide
//   in basso    orologio di scenario, pausa, "applica le modifiche: subito (10 s) / prepara + Applica (N)", monitor collegato
// ANDROID: tela larga 1228×768 (BugFixController 3), «Subito»/«Prepara» (BugFixController 1–2, proposta S1), tessera unica
// Ritmo · FC e tessera «Terapia elettrica» (proposta S3, BugFixController 4), sonde a icone (proposta S4), accensione a
// distanza e modi DAE / Manuale / Pacer (BugFixController 5, proposta A1).
// verifica da Mac (URL simulafranz-controller://...): shot?name=, tap?id=, seg?id=&i=, val?id=&v=, sw?id=&on=, pin?v=, list
//   ANDROID: sw?id=sw.<sonda>&on= vale anche per le tessere delle sonde (non sono più interruttori)
package it.simulafranz.controller

import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.os.BatteryManager
import it.simulafranz.core.*
import it.simulafranz.uk.*
import java.io.File
import java.io.FileOutputStream
import java.util.Locale
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

// MARK: - Aiuti per i valori dei dizionari (ANDROID: al posto dei messaggi di NSNumber / NSString)

/** [v boolValue]: NSNumber ≠ 0, NSString che comincia per Y/y/T/t/1-9 */
private fun BoolOf(v: Any?): Boolean = when (v) {
    is Boolean -> v
    is Number -> v.toDouble() != 0.0
    is String -> v.trim().firstOrNull()?.let { it in "YyTt123456789" } ?: false
    else -> false
}
/** [v integerValue] */
private fun IntOf(v: Any?): Int = when (v) {
    is Boolean -> if (v) 1 else 0
    is Number -> v.toDouble().toInt()
    is String -> v.trim().toDoubleOrNull()?.toInt() ?: 0
    else -> 0
}
/** [v doubleValue] */
private fun DblOf(v: Any?): Double = when (v) {
    is Boolean -> if (v) 1.0 else 0.0
    is Number -> v.toDouble()
    is String -> v.trim().toDoubleOrNull() ?: 0.0
    else -> 0.0
}
/** %@ di un valore: i numeri interi senza ",0" come NSNumber; nil → "(null)" */
private fun Desc(v: Any?): String = when (v) {
    null -> "(null)"
    is Boolean -> if (v) "1" else "0"
    is Double -> if (v == Math.floor(v) && !v.isInfinite() && abs(v) < 1e15) v.toLong().toString() else v.toString()
    is Float -> Desc(v.toDouble())
    is Number -> v.toLong().toString()
    else -> v.toString()
}
@Suppress("UNCHECKED_CAST")
private fun DictOf(v: Any?): Map<String, Any?> = (v as? Map<String, Any?>) ?: emptyMap()
/** lround: metà lontano dallo zero */
private fun lround(x: Double): Long = if (x < 0) -Math.round(-x) else Math.round(x)
private fun CGRectIsEmpty(r: CGRect) = r.width <= 0 || r.height <= 0
private fun fmt(f: String, vararg a: Any?) = String.format(Locale.US, f, *a)

// MARK: - Tabelle

// parametri regolabili: chiave API, titolo, unità, min, max, passo, decimali, valori rapidi, comando "subito" (se non è una rampa)
private val PDefTable: Map<String, List<Any>> = mapOf(
    "heart_rate_bpm"       to listOf("Frequenza cardiaca", "bpm", 0, 250, 1, 0, listOf<Number>(40, 60, 80, 100, 130, 160), ""),
    "spo2_percent"         to listOf("SpO₂", "%", 0, 100, 1, 0, listOf<Number>(75, 85, 90, 94, 98), ""),
    "systolic_mmhg"        to listOf("Sistolica", "mmHg", 40, 260, 1, 0, listOf<Number>(), ""),
    "diastolic_mmhg"       to listOf("Diastolica", "mmHg", 20, 160, 1, 0, listOf<Number>(), ""),
    "respiratory_rate_rpm" to listOf("Frequenza respiratoria", "atti/min", 0, 50, 1, 0, listOf<Number>(0, 8, 12, 16, 24, 32), ""),
    "etco2_mmhg"           to listOf("EtCO₂", "mmHg", 0, 100, 1, 0, listOf<Number>(10, 20, 35, 45, 60), ""),
    "temperature_c"        to listOf("Temperatura", "°C", 25, 43, 0.1, 1, listOf<Number>(34, 35.5, 36.8, 38.5, 40), "set_temperature"),
    "crt_s"                to listOf("Refill capillare", "s", 0.5, 8, 0.5, 1, listOf<Number>(1, 2, 3, 4, 6), "set_crt"),
)
private fun PDef(key: String): Map<String, Any> {
    val t = PDefTable[key]!!
    return mapOf("title" to t[0], "unit" to t[1], "min" to t[2], "max" to t[3], "step" to t[4], "dec" to t[5], "quick" to t[6], "now" to t[7])
}
private fun PColor(key: String): UIColor {
    if (key.startsWith("heart")) return CTL_HR
    if (key.startsWith("spo2")) return CTL_SPO2
    if (key.endsWith("olic_mmhg")) return CTL_BP
    if (key.startsWith("temp") || key.startsWith("crt")) return CTL_TEMP
    return CTL_CO2
}
private fun RhythmOrder(): List<String> = listOf("sinus", "sinus_tachycardia", "sinus_bradycardia", "afib", "vt_pulse", "vt", "vf", "asystole", "pea")
// ANDROID: in più "android" → "tablet" (il tipo di questo controller)
private fun CtlKindName(k: String?): String = mapOf("web" to "web app", "ipad" to "iPad", "ipod" to "iPod", "android" to "tablet")[k ?: ""] ?: "controller"
fun CtlRhythmName(r: String?): String {
    return mapOf("sinus" to "Sinusale", "sinus_tachycardia" to "Tachicardia sinusale", "sinus_bradycardia" to "Bradicardia sinusale",
                 "afib" to "Fibrillazione atriale", "vt_pulse" to "TV con polso", "vt" to "TV senza polso", "vf" to "Fibrillazione ventricolare",
                 "asystole" to "Asistolia", "pea" to "PEA")[r ?: ""] ?: (r ?: "--")
}
// tessere in alto -> parametri del pannello centrale
// ANDROID (proposta S3): «Ritmo» e «FC» aprivano la stessa regolazione: ora sono una tessera sola ("hr"); il posto liberato
// va alla tessera «Terapia elettrica» (che prima c'era già, ma stretta in 7)
// ANDROID (proposta A8): layout del monitor che sono un DAE (id dal catalogo del monitor)
private fun IsDaeLayout(l: Any?, st: Map<*, *>? = null): Boolean = l == "dae" ||
    ((st?.get("layouts") as? List<*>) ?: emptyList<Any?>()).any { it is Map<*, *> && it["id"] == l && it["kind"] == "aed" }
private fun TileOrder(): List<String> = listOf("hr", "bp", "spo2", "resp", "exam", "defib")
private fun TileParams(tile: String?): List<String> {
    return mapOf("rhythm" to listOf("heart_rate_bpm"), "hr" to listOf("heart_rate_bpm"), "bp" to listOf("systolic_mmhg", "diastolic_mmhg"), "spo2" to listOf("spo2_percent"),
                 "resp" to listOf("respiratory_rate_rpm", "etco2_mmhg"), "exam" to listOf("temperature_c", "crt_s"))[tile] ?: emptyList()
}
// ANDROID (proposta S4): nome corto e icona per la griglia delle sonde
private fun SensorShort(k: String): Pair<String, String> = mapOf("ecg" to Pair("Elettrodi", "ecg"), "pads" to Pair("Placche", "pads"), "spo2" to Pair("SpO₂", "finger"),
    "nibp" to Pair("Bracciale", "cuff"), "art" to Pair("Arteria", "artline"), "etco2" to Pair("Capno", "co2"), "temp" to Pair("Temp", "thermo"))[k] ?: Pair(k, "plus")
// ANDROID (BugFixController 1): «Subito» non è più uno scatto: il valore arriva con una transizione graduale
// ANDROID (funzioni 21/11, richiesta utente): 20 s (erano 10), senza dichiararlo sul tasto
private const val kSubitoSec = 20
// ANDROID (BugFixController 2): durate di «Prepara» → «Applica (N)»
private val PrepDurations = listOf(10, 30, 60, 120)
// ANDROID (avvio scenario 22/11): fase dello scenario dallo stato del monitor (ready | running | paused | ended). Un monitor
// vecchio non manda scenario_phase: si ricava come prima (mai «ready», quindi niente tasto «Avvia»)
internal const val NotStartedText = "Prima di «Avvia» non si registra niente e il piano di debriefing non si apre: premi «Avvia»"
// ANDROID (trasmissione 12D 22/11): risposte rapide dell'istruttore che fa da centrale (stesse della web regia, TX12_REPLIES in app.js)
// ANDROID (centrale 25/11): la centrale decide solo la destinazione: centralizzare o no. Il resto si scrive nel testo libero.
internal val Tx12Replies = listOf("Centralizzare il paziente", "Non centralizzare il paziente")
internal fun ScenPhase(st: Map<String, Any?>?): String {
    if (st == null) return "running"
    (st["scenario_phase"] as? String)?.let { return it }
    return if (st["scenario_ended"] == true) "ended" else (if (st["simulation_running"] == false) "paused" else "running")
}
private fun Sensors(): List<Triple<String, String, UIColor>> {      // chiave API, nome, colore
    // le piastre stanno accanto agli elettrodi (tutte e due danno l'ECG) e hanno lo stesso colore
    return listOf(Triple("ecg", "Elettrodi ECG", CTL_HR), Triple("pads", "Placche defibrillatore", CTL_HR), Triple("spo2", "Sonda SpO₂", CTL_SPO2),
                  Triple("nibp", "Bracciale NIBP", CTL_BP), Triple("art", "Linea arteriosa", CTL_BP), Triple("etco2", "Capnografia", CTL_CO2),
                  Triple("temp", "Sonda temperatura", CTL_TEMP))
}
// stati pronti (gli stessi della web app): nome, transizione, ritmo, parametri, valori immediati
private fun ST(n: String, d: Int, r: String, hr: Int, s: Int, dd: Int, sp: Int, rr: Int, et: Int, crt: Int): Map<String, Any?> =
    mapOf("n" to n, "d" to d, "r" to r, "p" to linkedMapOf<String, Any?>("heart_rate_bpm" to hr, "systolic_mmhg" to s, "diastolic_mmhg" to dd, "spo2_percent" to sp, "respiratory_rate_rpm" to rr, "etco2_mmhg" to et),
          "now" to mapOf<String, Any?>("crt_s" to crt))
private val PresetStatesList: List<Map<String, Any?>> = listOf(
    ST("Sano", 10, "sinus", 80, 120, 80, 98, 14, 38, 2),
    ST("Ipoteso", 30, "sinus", 105, 90, 55, 95, 20, 32, 3),
    ST("Shock", 60, "sinus_tachycardia", 135, 75, 45, 90, 28, 25, 5),
    ST("Insufficienza respiratoria", 60, "sinus_tachycardia", 120, 135, 85, 82, 32, 55, 2),
    ST("Bradicardia instabile", 30, "sinus_bradycardia", 38, 80, 50, 91, 10, 30, 4),
    ST("TV con polso", 10, "vt_pulse", 180, 85, 55, 92, 22, 30, 3),
    mapOf("n" to "Arresto in FV", "d" to 0, "r" to "vf", "p" to emptyMap<String, Any?>(), "now" to emptyMap<String, Any?>()),
    ST("Post-ROSC", 30, "sinus_tachycardia", 110, 95, 60, 94, 12, 40, 3))
private fun PresetStates(): List<Map<String, Any?>> = PresetStatesList
// Sezione a parte nella tab «Stati», dopo gli stati di serie e quelli salvati; non si eliminano.
@Suppress("UNCHECKED_CAST")
private val StatesExtra: List<Map<String, Any?>> = emptyList()
private fun Dur(s: Int): String = if (s <= 0) "subito" else (if (s < 60) fmt("%d s", s) else (if (s % 60 != 0) fmt("%d min %d s", s / 60, s % 60) else fmt("%d min", s / 60)))
private fun Clock(s: Int): String = fmt("%02d:%02d", s / 60, s % 60)
private fun Num(v: Any?, dec: Int): String {
    if (v !is Number) return "--"
    return if (dec > 0) fmt("%.${dec}f", v.toDouble()).replace(".", ",") else fmt("%d", lround(v.toDouble()))
}
// riassunto leggibile di un blocco di parametri (stato o fase)
fun CtlDescribeBlock(rhythm: String?, params: Map<String, Any?>?, extra: Map<String, Any?>?): String {
    val a = ArrayList<String>()
    if (!rhythm.isNullOrEmpty()) a.add(CtlRhythmName(rhythm))
    val s = params?.get("systolic_mmhg"); val d = params?.get("diastolic_mmhg")
    params?.get("heart_rate_bpm")?.let { a.add("FC " + Desc(it)) }
    if (s != null && d != null) a.add(fmt("PA %s/%s", Desc(s), Desc(d)))
    else { if (s != null) a.add("PAs " + Desc(s)); if (d != null) a.add("PAd " + Desc(d)) }
    params?.get("spo2_percent")?.let { a.add("SpO₂ " + Desc(it)) }
    params?.get("respiratory_rate_rpm")?.let { a.add("FR " + Desc(it)) }
    params?.get("etco2_mmhg")?.let { a.add("EtCO₂ " + Desc(it)) }
    extra?.get("temperature_c")?.let { a.add("Temp " + Num(it, 1)) }
    extra?.get("crt_s")?.let { a.add("Refill " + Num(it, 1) + " s") }
    extra?.get("ecg_morphology")?.let { a.add("ECG " + CtlEcg.shortOf(it)) }       // ANDROID (regia morfologie ECG 22/11)
    return if (a.isNotEmpty()) a.joinToString(" · ") else "nessun cambiamento"
}

// ANDROID (funzioni 21/11): nome leggibile dei comandi nuovi del cuore, per il messaggio «monitor vecchio»
private fun NewFeatureName(c: String): String = mapOf("set_pads_type" to "placche pediatriche", "set_pads_cable" to "cavo delle placche",
    "set_aed_child_mode" to "modalità bambino", "vehicle_motion" to "ambulanza in movimento", "set_technical_alarm" to "allarmi tecnici",
    "undo_auto_action" to "annulla l'avanzamento automatico", "set_pads_contact" to "contatto delle placche", "set_cpr_quality" to "qualità della RCP",
    "set_device_fault" to "malfunzionamenti del dispositivo", "clear_device_faults" to "dispositivo a posto", "set_device_fault_beep" to "bip da spento")[c] ?: c.replace('_', ' ')

// ANDROID (guasti 22/11): testi dei malfunzionamenti simulati nel controller. Nomi del cuore (SLDeviceFaultName) con «placche»
// come nel resto della regia; azione reale che li risolve; che cosa succede nel layout attivo (bip da spento come nel motore:
private fun FaultName(kind: String): String = SLDeviceFaultName(kind).replace("Piastre", "Placche").replace("piastre", "placche")
private fun FaultFixTitle(kind: String): String = when (kind) {
    "battery_low", "battery_replace", "battery_dead" -> "Batteria sostituita"
    "pads_expired", "pads_damaged" -> "Placche sostituite"
    "pads_unplugged" -> "Cavo ricollegato"
    "pads_poor_contact" -> "Placche riapplicate"
    "temperature" -> "Spostato in ambiente idoneo"
    "memory_full" -> "Dati scaricati"
    else -> "Assistenza eseguita"
}
private fun FaultBeepS(layout: String, kind: String): Int {
    val attention = kind in listOf("battery_low", "battery_replace", "pads_expired", "pads_damaged", "pads_unplugged", "maintenance", "shock_button_stuck")
    return when (layout) {
        "dae", "extrah" -> if (kind == "service_required") 15 else (if (attention) 30 else 0)
        else -> 0
    }
}
private fun FaultHint(layout: String, kind: String): String {
    val dedicated = layout == "dae"
    val b = FaultBeepS(layout, kind)
    val beep = if (b == 15) "bip doppio da spento; " else (if (b == 30) "bip da spento; " else "")
    fun said(key: String): String { val v = if (dedicated) SLDeviceFaultVoice(layout, key) else ""; return "«" + (if (v.isNotEmpty()) v.trimEnd('.') else SLDeviceFaultScreen(layout, key)) + "»" }
    return beep + when (kind) {
        "battery_low" -> "all'accensione " + said("battery_low") + "; 6 scariche, poi «da sostituire»"
        "battery_replace" -> said("battery_replace") + "; 2 scariche, poi non scarica; dopo 3 min acceso si spegne"
        "battery_dead" -> "nessuna spia né bip: il dispositivo non si accende"
        "pads_expired" -> "all'accensione " + said("pads_expired") + "; poi funziona del tutto"
        "pads_damaged" -> "con le placche applicate " + said("pads_damaged") + ": niente analisi né scarica"
        "pads_unplugged" -> "«Collegare il cavo»: niente analisi; conta anche da spento"
        "pads_poor_contact" -> "«Controllare le piastre»: niente analisi finché non le riapplicano"
        "maintenance" -> said("maintenance") + ": solo un avviso, funziona"
        "service_required" -> "«" + SLDeviceFaultScreen(layout, "svc") + "»: niente analisi né carica"
        "temperature" -> "solo la spia di attenzione (nessun bip, nessuna voce); funziona"
        "charge_fault" -> "analizza e carica, poi «Scarica non erogata» (errore interno)"
        "shock_button_stuck" -> said("shock_stuck") + ": la scarica non parte"
        "memory_full" -> "all'accensione «" + SLDeviceFaultScreen(layout, "memory") + "»: solo un avviso"
        else -> ""
    }
}
private fun FaultReadinessWord(r: String, selfTest: Boolean): String = if (selfTest) "AUTOTEST" else
    (mapOf("ready" to "PRONTO", "attention" to "ATTENZIONE", "fault" to "GUASTO", "dark" to "SPENTA")[r] ?: r.uppercase(Locale.ITALIAN))
private fun FaultReadinessColor(r: String): UIColor = mapOf("ready" to CTL_GREEN, "attention" to CTL_ORANGE, "fault" to CTL_RED)[r] ?: CTL_INK3
/** che cosa vede il partecipante sul dispositivo spento, nel layout attivo (layout fatti in NUOVE_FUNZIONI_CUORE, blocco 2) */
private fun FaultReadinessSeen(layout: String, r: String, selfTest: Boolean): String {
    if (selfTest) return when (layout) { "dae" -> "finestrella «AUTOTEST» (spia gialla) per 3 s dopo la batteria nuova"; else -> "autotest breve (3 s) dopo la batteria nuova" }
    return when (layout) {
        "dae" -> mapOf("ready" to "finestrella «PRONTO» con la spia verde", "attention" to "finestrella «ATTENZIONE» con la spia ambra, bip periodico",
                       "fault" to "finestrella «GUASTO» con la spia rossa, bip doppio", "dark" to "nessuna spia e nessun bip: sembra scarico")[r]
        "extrah" -> mapOf("ready" to "spia verde sopra il tasto ACCENSIONE", "attention" to "spia ambra sopra ACCENSIONE, bip periodico",
                          "fault" to "spia rossa sopra ACCENSIONE, bip doppio", "dark" to "spia spenta e nessun bip: sembra scarico")[r]
        "generic_icu" -> "monitor da letto, nessuna spia: la batteria compare come allarme tecnico"
        else -> null
    } ?: "questo layout non ha ancora la spia da spento: i malfunzionamenti si vedono all'accensione e sulla terapia"
}

// ANDROID: [view endEditing:YES] non c'è in uk: si toglie il fuoco ai campi di testo dentro la vista
private fun endEditing(v: UIView) {
    if (v is UITextField) v.resignFirstResponder()
    if (v is UITextView) v.resignFirstResponder()
    for (s in v.subviews) endEditing(s)
}

// ANDROID (icone sonde 21/11): icone delle sonde prese da Healthicons (CC0, healthicons.org), PNG bianche 96×96 in
// res/drawable-nodpi/ic_sonda_*.png (conta solo l'alfa). Qui si colorano (SRC_IN) e si rimpiccioliscono a mezzi passi
// fino ai pixel veri dello schermo, così restano nitide a 24-28 punti. Elenco e licenze: LICENZE_ICONE.md.
private val SondaRes = mapOf("ecg" to R.drawable.ic_sonda_ecg, "pads" to R.drawable.ic_sonda_pads, "spo2" to R.drawable.ic_sonda_spo2,
    "nibp" to R.drawable.ic_sonda_nibp, "art" to R.drawable.ic_sonda_art, "etco2" to R.drawable.ic_sonda_etco2, "temp" to R.drawable.ic_sonda_temp)
private val sondaSrc = HashMap<String, Bitmap>()
private fun CtlSondaIcon(key: String, size: Double, color: UIColor): UIImage? {
    val id = SondaRes[key] ?: return null
    var b = sondaSrc.getOrPut(key) {
        android.graphics.BitmapFactory.decodeResource(Uk.ctx.resources, id, android.graphics.BitmapFactory.Options().apply { inScaled = false })
    }
    val px = max(1, Uk.px(size))
    while (b.width / 2 >= px) b = Bitmap.createScaledBitmap(b, b.width / 2, b.height / 2, true)   // mezzi passi: niente scalettature
    val out = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888)
    val p = android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG or android.graphics.Paint.ANTI_ALIAS_FLAG)
    p.colorFilter = android.graphics.PorterDuffColorFilter(color.argb, android.graphics.PorterDuff.Mode.SRC_IN)
    android.graphics.Canvas(out).drawBitmap(b, null, android.graphics.Rect(0, 0, px, px), p)
    return UIImage(out)
}

// ANDROID (proposta S4): tessera di una sonda, a due stati — attaccata (colore della sonda, pieno) / staccata (spenta, tratteggio).
// Tocco = attacca o stacca (stesso comando set_sensor degli interruttori di prima). Per SpO₂, bracciale e arteria, a sonda
// attaccata compare a destra il lato («dx»/«sx»): toccarlo sposta la sonda dall'altro lato.
private class CtlSensorTile(frame: CGRect, val key: String, name: String, private val icon: String, private val color: UIColor) : UIControl(frame) {
    var isOn = false; private set
    private var shown: Boolean? = null
    private val iconView = UIImageView(CGRectMake(8, (frame.height - 28) / 2, 28, 28))   // ANDROID (icone sonde 21/11): 28 punti (era 24)
    private val nameL = CtlLabel(CGRectMake(42, 3, frame.width - 48, 20), name, 14.0, true, CTL_INK)
    private val stateL = CtlLabel(CGRectMake(42, 22, frame.width - 48, 16), "", 11.0, true, CTL_INK2)
    init {
        layer.cornerRadius = 10.0
        for (v in listOf(iconView, nameL, stateL)) { v.userInteractionEnabled = false; addSubview(v) }
        nameL.adjustsFontSizeToFitWidth = true; nameL.minimumScaleFactor = 0.8
        show(false)
    }
    // ANDROID (affordance 22/11, CTL-02): armata = il prossimo tocco stacca (arancione, «tocca di nuovo: stacca»)
    private var armed = false
    fun setArmed(a: Boolean) {
        if (armed == a) return
        armed = a
        if (a) {
            backgroundColor = CTL_ORANGE.colorWithAlphaComponent(0.22); layer.borderWidth = 2.0; layer.borderColor = CTL_ORANGE.CGColor
            stateL.text = "di nuovo: stacca"; stateL.textColor = CTL_ORANGE
        } else { shown = null; show(isOn) }
    }
    fun show(on: Boolean) {
        isOn = on
        if (shown == on || armed) return
        shown = on
        backgroundColor = if (on) color.colorWithAlphaComponent(0.20) else CTL_CARD2
        layer.borderWidth = 1.5; layer.borderColor = (if (on) color else CTL_LINE).CGColor
        // ANDROID (icone sonde 21/11): icona Healthicons colorata; CtlIcon resta solo come riserva
        iconView.image = CtlSondaIcon(key, 28.0, if (on) color else CTL_INK3) ?: CtlIcon(icon, 24.0, if (on) color else CTL_INK3)
        nameL.textColor = if (on) CTL_INK else CTL_INK2
        // ANDROID (affordance 22/11, CTL-20 / CTL-02): stato anche come forma (● / ○) e «staccata» in INK2 (contrasto AA)
        stateL.text = if (on) "● attaccata" else "○ staccata"; stateL.textColor = if (on) color else CTL_INK2
    }
    /** spazio a destra occupato dal tasto del lato: il testo si ferma prima */
    fun reserve(w: Double) { nameL.frame = CGRectMake(42, 3, frame.width - 48 - w, 20); stateL.frame = CGRectMake(42, 22, frame.width - 48 - w, 16); stateL.adjustsFontSizeToFitWidth = true; stateL.minimumScaleFactor = 0.8 }
    override fun stateChanged() { super.stateChanged(); alpha = if (highlighted) 0.6f else 1f }
}

// MARK: -

class CtlViewController(val model: CtlModel) : UIViewController() {
    // ANDROID (BugFixController 3): misure della tela larga (1228 punti sul Tab Active 2): colonne più larghe, centro più ampio.
    // 8 | sinistra 320 | 8 | centro 560 | 8 | destra 316 | 8 = 1228. Con una tela di 1024 si torna alle misure dell'iPad.
    private val W = CTL_W; private val H = CTL_H
    private val LW = if (CTL_W >= 1200) 320.0 else 252.0          // colonna sinistra (stati, scenario, registro)
    private val RW = if (CTL_W >= 1200) 316.0 else 264.0          // colonna destra (sonde, azioni)
    private val CX = 16 + LW; private val CW = CTL_W - 32 - LW - RW; private val RX = CTL_W - 8 - RW
    // ANDROID (affordance 22/11, CTL-01): barra di regia alta 64 punti (era 56) con tasti da 44-48; le colonne finiscono 8 punti prima
    private val BARY = 696.0; private val BARH = 64.0; private val COLB = 688.0; private val LEFTH = COLB - 160
    private val m: CtlModel = model
    // tessere
    private val tiles = LinkedHashMap<String, UIControl>(); private val tileValue = HashMap<String, UILabel>(); private val tileSub = HashMap<String, UILabel>()
    private lateinit var tileRhythm: UILabel                      // ANDROID (proposta S3): nome del ritmo accanto alla FC
    private var sel: String? = "hr"
    // sinistra
    private lateinit var leftSeg: UISegmentedControl; private lateinit var leftScroll: UIScrollView; private lateinit var leftFooter: UIView; private var leftSig: String? = null; private var open = -1
    private val custom = ArrayList<Map<String, Any?>>(); private var lastState: String? = null; private var endArmed = 0.0
    private var phaseClock: UILabel? = null; private var openRect = CGRectZero; private var pauseInit = false; private lateinit var clockBtn: UIControl
    private var regLast: UILabel? = null; private var regDeb: CtlButton? = null; private var quickSession = 0
    private val updaters = ArrayList<() -> Unit>(); private val setTouched = HashMap<String, Double>(); private var outcome: UIView? = null; private var outcomeSig: String? = null; private var outcomeShock: Int = 0; private var outcomeSheet: CtlSheet? = null; private var occlSaid = false; private lateinit var moreBtn: CtlButton
    private val siteBtn = LinkedHashMap<String, CtlButton>()
    private val quickCount = LinkedHashMap<String, Int>(); private var quickFlash: String? = null; private var quickFlashUntil = 0.0; private var debrief: CtlDebriefView? = null
    // centro
    private lateinit var center: UIView; private lateinit var edBody: UIView; private lateinit var edTitle: UILabel; private lateinit var edSub: UILabel; private lateinit var msg: UILabel; private var edBuilt: String? = null
    // ANDROID (affordance 22/11): striscia dei messaggi (CTL-03), ⓘ al posto del paragrafo d'aiuto (CTL-19), tessere: linea della
    // scheda aperta e «›» (CTL-16), riquadro tratteggiato «in attesa» (CTL-04), freccia «→ arrivo» (CTL-07), fuori limiti (CTL-21)
    private lateinit var msgBox: UIView; private lateinit var msgIcon: UILabel; private lateinit var msgDash: CtlDashBox; private lateinit var infoBtn: CtlButton
    private var flashKind = "ok"
    private val tileLine = HashMap<String, UIView>(); private val tileDash = HashMap<String, CtlDashBox>(); private val tileArrow = HashMap<String, UILabel>()
    private val tileCap = HashMap<String, UILabel>(); private val tileCapText = HashMap<String, String>(); private var warnPhase = false
    private val quickBtns = LinkedHashMap<String, MutableList<Pair<Double, CtlButton>>>()
    private val sensorArmed = HashMap<String, Double>()            // ANDROID (affordance 22/11, CTL-02): sonda da staccare armata fino a
    private var cancelArmed = 0.0; private var discarded: Pair<Map<String, Number>, String?>? = null; private var discardedUntil = 0.0   // CTL-10
    private var lockShown: Boolean? = null                          // CTL-13
    private val trSeen = HashMap<String, Pair<String, Double>>()    // CTL-07: durata totale di una transizione (primo «mancano» visto)
    private val rows = LinkedHashMap<String, CtlParamRow>(); private val rhythmBtn = LinkedHashMap<String, CtlButton>()
    private var nibpInfo: UILabel? = null
    // ANDROID (insorgenza e ampiezza FV 22/11): come parte un ritmo nuovo (false = «Realistica», preludio fisiologico, di serie;
    // true = «Di colpo»), per tutta la sessione dell'app; lastRhy = ultimo comando di ritmo mandato da qui (nome, valore, extra) per
    // «Subito ora»; vfPick = ampiezza della FV scelta durante il preludio verso la FV (il monitor la applica all'arrivo)
    private var onsetImmediate = false
    private var lastRhy: Triple<String, Any?, Map<String, Any?>?>? = null
    private var vfPick: Any? = null
    private lateinit var onsetCap: UILabel; private val onsetSeg = ArrayList<CtlButton>()
    private var onsetBand: UIView? = null; private var onsetTitleL: UILabel? = null; private var onsetTextL: UILabel? = null
    private var onsetBar: UIView? = null; private var onsetSkipBtn: CtlButton? = null
    private var vfRow: UIView? = null; private val vfBtns = LinkedHashMap<String, CtlButton>(); private var vfInfo1: UILabel? = null; private var vfInfo2: UILabel? = null
    private var onsetSkipAt = 0.0; private var vfTapAt = 0.0; private val rhyTapAt = HashMap<String, Double>()
    // ANDROID (regia morfologie ECG 22/11): pannello «Morfologia ECG» (sel = "ecg", dalla tessera Ritmo · FC), morfologia preparata
    // in «Prepara» (null | "normal" | mappa dei campi: parte con «Applica» dentro la transition), tasto di navigazione nel pannello del
    // ritmo, indicatore nella tessera, ultimo 12 derivazioni acquisito visto (avviso «I partecipanti hanno acquisito il 12 derivazioni»)
    private var pendingMorph: Any? = null; private var discardedMorph: Any? = null
    private var ecgPanel: CtlEcgPanel? = null; private var ecgNavBtn: CtlButton? = null; private var ecgNowL: UILabel? = null; private var ecgNormBtn: CtlButton? = null
    private val ecgTapAt = HashMap<String, Double>(); private var ecg12Seen: Long? = null; private lateinit var tileEcg: UILabel
    // destra
    private val sw = LinkedHashMap<String, UISwitch>(); private val swTouched = HashMap<String, Double>()
    private val sensorTile = LinkedHashMap<String, CtlSensorTile>()      // ANDROID (proposta S4)
    private lateinit var powerBtn: CtlButton; private var powerArmed = 0.0   // ANDROID (BugFixController 5): accensione a distanza
    private lateinit var cprBtn: CtlButton; private lateinit var ventBtn: CtlButton; private lateinit var nibpBtn: CtlButton; private lateinit var muteBtn: CtlButton; private lateinit var touchBtn: CtlButton; private lateinit var layoutBtn: CtlButton
    private lateinit var batt: UILabel; private lateinit var battBody: UIView; private lateinit var battFill: UIView; private var muteShown = false; private var pauseShown = false
    // regia
    private lateinit var clock: UILabel; private lateinit var pendLabel: UILabel; private lateinit var busyText: UILabel
    private lateinit var pauseBtn: CtlButton; private lateinit var applyBtn: CtlButton; private lateinit var cancelBtn: CtlButton; private lateinit var monBtn: CtlButton
    // ANDROID (avvio scenario 22/11, richiesta utente «Subito e Prepara si mimetizzano»): al posto dei due UISegmentedControl,
    // tasti veri: quello scelto PIENO (Subito blu, Prepara e durata gialli), gli altri con il bordo colorato e il testo chiaro
    private lateinit var modeBtns: List<CtlButton>; private lateinit var durBtns: List<CtlButton>; private lateinit var durLabel: UILabel
    private var choiceKey = ""                                    // ultimo aspetto dei tasti (si ridisegnano solo se cambia)
    private lateinit var clockSub: UILabel; private var phaseKey = ""   // ANDROID (avvio scenario 22/11): scritta sotto il timer, fase mostrata
    private lateinit var modeLabel: UILabel                       // ANDROID (storia sim 22/11): il gruppo si ricentra nel refresh
    private lateinit var busyBar: UIView; private lateinit var bar: UIView; private lateinit var takeBtn: CtlButton; private var wasMine = false; private var wasIdle = false
    // ANDROID (audit 21/11): ricollegamento con la regia a schermo, comandi non arrivati, «Prendi il comando» a due tocchi
    private lateinit var linkBar: UIView; private lateinit var linkText: UILabel; private lateinit var linkBtn: CtlButton; private lateinit var staleCover: UIView
    private var takeArmed = 0.0; private var lastRefreshAt = 0.0; private var battAt = -100.0; private var battCache = Pair(-1.0, false)
    private var scenBusy = false; private var stateBusyUntil = 0.0; private var scenBusyUntil = 0.0
    // ANDROID (funzioni 21/11): FINE SCENARIO, «Prossima fase», sola osservazione, banner in alto al centro (avanzamento
    // automatico con «Annulla», monitor diventato DAE), tessera live delle metriche RCP
    private lateinit var endBtn: CtlButton; private lateinit var phaseLine: UILabel; private lateinit var obsCover: UIView; private lateinit var obsText: UILabel
    private lateinit var noticeBox: UIView; private lateinit var noticeText: UILabel; private lateinit var noticeBtn: CtlButton
    private var autoSeqSeen = -1L; private var autoUntilLocal = 0.0; private var autoUndone = -1L; private var noticeKind: String? = null
    private var undoInFlight = -1L        // ANDROID (fix analisi 22/11, BUG-10): «Annulla» partito per questo avanzamento, risposta in attesa
    private var tx12Dismissed = -1L; private var tx12Flashed = -1L        // ANDROID (trasmissione 12D 22/11): avviso chiuso qui / già segnalato
    private var daePending: Boolean? = null; private var layoutAskedAt = -100.0
    private var foldTail: UIView? = null; private var obsForce = false
    private lateinit var roleTag: UILabel; private var observing = false; private var obsWho = ""; private var obsSaidAt = 0.0; private var obsBlockedAt = 0.0      // ANDROID (sola osservazione senza velo 22/11)
    // ANDROID (BugFixController 2, proposta S1): due modi soli. prepare = false «Subito» (transizione di 10 s);
    // prepare = true «Prepara»: tutto si accumula in pending (anche temperatura e refill) finché non si preme «Applica (N)»,
    // che parte con la durata prepSec. Valori Number: temperatura e refill hanno i decimali.
    private val pending = LinkedHashMap<String, Number>(); private var pendingRhythm: String? = null
    private var prepare = false; private var prepSec = 30
    private var flash: String? = null; private var flashUntil = 0.0
    private var endReopenTried = false; private val loadedAt = CACurrentMediaTime()                    // ANDROID (debriefing scollegato 22/11)
    private lateinit var pairing: CtlPairingView
    private var refreshTimer: NSTimer? = null
    private var rampTimer: NSTimer? = null
    // ANDROID (proposta A8, richiesta utente 21/11): regia DAE quando sul monitor c'è un layout DAE
    private var daeMode: Boolean? = null; private lateinit var sensorHint: UILabel
    private val origFrame = HashMap<UIView, CGRect>()
    // ANDROID (sala 22/11): «Sala» in diretta (fotocamera frontale e microfono del monitor, niente registrazione): CtlSala.kt
    private val sala = CtlSala(model); private lateinit var salaBtn: CtlButton
    // ANDROID (pannello RCP 22/11): «Come vedi l'RCP?» (CtlCprPanel.kt) sopra la regia; tocco unico sul tasto RCP
    private lateinit var cprPanel: CtlCprPanel; private var cprTapAt = -100.0
    // ANDROID (funzioni mancanti 22/11): pannello «Funzioni avanzate» (CtlMancanti.kt): trend, linee e ventilazione, allarmi, distrattori
    private lateinit var fxPanel: CtlFxPanel

    init {
        // NSUserDefaults "sf_states" → model.prefs (JSON)
        val saved = m.prefs.json("sf_states") as? List<*>
        if (saved != null) for (o in saved) DictOf(o).takeIf { o is Map<*, *> }?.let { custom.add(it) }
        CtlHaptic.enabled = m.prefs.bool("sf_haptic", true)       // ANDROID (affordance 22/11, CTL-15)
    }

    // MARK: - Costruzione

    override fun viewDidLoad() {
        super.viewDidLoad()
        view.backgroundColor = CTL_BG
        val W = this.W; val H = this.H                                   // ANDROID (BugFixController 3): tela larga
        buildTiles()
        buildLeft()
        center = CtlCard(CGRectMake(CX, 108, CW, COLB - 108)); view.addSubview(center)
        edTitle = CtlLabel(CGRectMake(20, 14, CW - 40 - 56, 28), "", 22.0, true, CTL_INK); center.addSubview(edTitle)
        // ANDROID (affordance 22/11, CTL-19): il paragrafo d'aiuto sotto il titolo diventa un «ⓘ» (le regole importanti ora stanno
        // sui tasti: «Subito · 20 s», «SUBITO» sui ritmi di arresto); lo spazio liberato va ai comandi più grandi (CTL-01)
        edSub = CtlLabel(CGRectMake(20, 42, CW - 40, 32), "", 12.5, false, CTL_INK2); edSub.numberOfLines = 2; edSub.hidden = true; center.addSubview(edSub)
        infoBtn = CtlButton.buttonWithTitle("", CtlButtonQuiet, null, CGRectMake(CW - 20 - 48, 8, 48, 40)) { showEdInfo() }
        infoBtn.setIcon("info", false); infoBtn.accessibilityIdentifier = "ed.info"; center.addSubview(infoBtn)
        // ANDROID (insorgenza 22/11): «INSORGENZA [Realistica] [Di colpo]» nella riga del titolo del pannello Ritmo, prima di «ⓘ»
        // (scelta uno fra due: contorno, quella scelta piena con «●»). Non manda comandi: vale per i prossimi cambi di ritmo.
        run {
            val right = CW - 20 - 48 - 8; val w2 = 90.0; val w1 = 104.0
            onsetCap = CtlLabel(CGRectMake(right - w2 - 4 - w1 - 6 - 74, 8, 74, 40), "INSORGENZA", 11.0, true, CTL_INK2)
            onsetCap.textAlignment = NSTextAlignmentRight; onsetCap.adjustsFontSizeToFitWidth = true; onsetCap.minimumScaleFactor = 0.85
            onsetCap.hidden = true; center.addSubview(onsetCap)
            val me = this
            for ((i, v) in listOf("auto", "immediate").withIndex()) {
                val f = if (i == 0) CGRectMake(right - w2 - 4 - w1, 8, w1, 40) else CGRectMake(right - w2, 8, w2, 40)
                val b = CtlButton.buttonWithTitle(if (i == 0) "Realistica" else "Di colpo", CtlButtonQuiet, null, f, null)
                b.titleLabel.font = UIFont.boldSystemFontOfSize(15); b.maxLines = 1; b.accessibilityIdentifier = "onset.$v"; b.hidden = true
                b.onTap = { me.onsetChosen(v == "immediate") }
                center.addSubview(b); onsetSeg.add(b)
            }
        }
        edBody = UIView(CGRectMake(20, 52, CW - 40, 474)); center.addSubview(edBody)
        // ANDROID (affordance 22/11, CTL-03): striscia dei messaggi in fondo al pannello centrale, larga quanto il pannello, testo di
        // 15 pt con il segno dello stato (✓ eseguito, ✕ non eseguito, ⓘ avviso); con modifiche preparate mostra l'elenco in attesa
        msgBox = UIView(CGRectMake(8, COLB - 108 - 8 - 44, CW - 16, 44)); msgBox.layer.cornerRadius = 10.0; msgBox.layer.borderWidth = 1.0
        msgBox.hidden = true; msgBox.accessibilityIdentifier = "msg.strip"; center.addSubview(msgBox)
        msgDash = CtlDashBox(msgBox.bounds, CTL_YELLOW); msgDash.hidden = true; msgBox.addSubview(msgDash)
        msgIcon = CtlLabel(CGRectMake(12, 0, 22, 44), "", 18.0, true, CTL_INK); msgIcon.textAlignment = NSTextAlignmentCenter; msgBox.addSubview(msgIcon)
        msg = CtlLabel(CGRectMake(40, 0, CW - 16 - 52, 44), "", 15.0, true, CTL_INK2); msg.numberOfLines = 2; msg.adjustsFontSizeToFitWidth = true; msg.minimumScaleFactor = 0.8
        msgBox.addSubview(msg)
        // ANDROID (funzioni 21/11): banner in alto nel pannello centrale (copre solo titolo e sottotitolo): avanzamento automatico
        // con «Annulla · N s» oppure «Il monitor ora è un DAE». Ben visibile, ma il pannello sotto resta usabile.
        noticeBox = UIView(CGRectMake(8, 6, CW - 16, 72)); noticeBox.backgroundColor = CtlRGB(0.24, 0.17, 0.02); noticeBox.layer.cornerRadius = 10.0
        noticeBox.layer.borderWidth = 1.5; noticeBox.layer.borderColor = CTL_YELLOW.CGColor; noticeBox.hidden = true; noticeBox.accessibilityIdentifier = "notice"
        noticeText = CtlLabel(CGRectMake(14, 0, CW - 16 - 14 - 210, 72), "", 15.0, true, CtlRGB(1.0, 0.9, 0.6)); noticeText.numberOfLines = 3
        noticeText.adjustsFontSizeToFitWidth = true; noticeText.minimumScaleFactor = 0.75; noticeBox.addSubview(noticeText)
        noticeBtn = CtlButton.buttonWithTitle("Annulla", CtlButtonFilled, CTL_YELLOW, CGRectMake(CW - 16 - 200, 14, 188, 44)) { noticeTapped() }
        noticeBtn.titleLabel.font = UIFont.boldSystemFontOfSize(15); noticeBtn.maxLines = 1; noticeBtn.setTitleColor(CtlRGB(0.10, 0.08, 0.0), UIControlStateNormal)
        noticeBtn.accessibilityIdentifier = "notice.action"; noticeBox.addSubview(noticeBtn)
        center.addSubview(noticeBox)
        buildRight()
        buildBar()
        pairing = CtlPairingView(CGRectMake(0, 0, W, H), m)
        view.addSubview(pairing)
        // ANDROID (pannello RCP 22/11): il pannello si aggiunge alla regia solo quando si apre
        cprPanel = CtlCprPanel(CGRectMake(0, 0, W, H), m); cprPanel.hidden = true
        cprPanel.observing = { observing }; cprPanel.obsWho = { obsWho }; cprPanel.flash = { t -> flash(t) }
        fxPanel = CtlFxPanel(CGRectMake(0, 0, W, H), m); fxPanel.hidden = true
        fxPanel.observing = { observing }; fxPanel.flash = { t -> flash(t) }; fxPanel.obsWho = { obsWho }
        m.onUpdate = { refresh() }
        // ANDROID (sola osservazione senza velo 22/11): in sola osservazione i comandi non partono; un avviso breve, non bloccante
        m.observing = { observing }
        m.onBlocked = {
            obsBlockedAt = CACurrentMediaTime()
            if (CACurrentMediaTime() - obsSaidAt > 1.5) { obsSaidAt = CACurrentMediaTime(); flash(fmt("Comanda %s: tocca «Prendi il comando» per regolare", obsWho), 4.0) }
            refresh()
        }
        m.onNotice = { t ->      // due avvisi di fila (azzerato + inviata) restano insieme
            val old = flash; flash(if (old != null && CACurrentMediaTime() < flashUntil - 4 && old != t) "$old · $t" else t, 12.0); refresh() }       // ANDROID (debriefing scollegato 22/11): azzeramento al rientro, invii
        // ANDROID (audit 21/11, B7): il timer serve solo a far scadere scritte e tasti armati: se lo stato è appena arrivato
        // (una lettura al secondo) non si ridisegna due volte
        refreshTimer = NSTimer.scheduledTimerWithTimeInterval(1.0, true) { if (CACurrentMediaTime() - lastRefreshAt > 0.9) refresh() }
        rampTimer = NSTimer.scheduledTimerWithTimeInterval(0.25, true) { refreshRampNotes() }      // ANDROID (26/11)
        refresh()
        showSplash(false)
    }

    private fun buildTiles() {
        // ANDROID (proposta S3, BugFixController 4): sei tessere; «Ritmo · FC» insieme, «Terapia elettrica» al posto di «Defibrillatore»
        val caps = mapOf("hr" to "RITMO · FC  bpm", "bp" to "PA  mmHg", "spo2" to "SpO₂  %", "resp" to "FR · EtCO₂", "exam" to "TEMP · REFILL", "defib" to "TERAPIA ELETTRICA")
        val cols = mapOf("hr" to CTL_HR, "bp" to CTL_BP, "spo2" to CTL_SPO2, "resp" to CTL_CO2, "exam" to CTL_TEMP, "defib" to CTL_ORANGE)
        val n = TileOrder().size
        var x = 8.0; val w = (W - 16 - (n - 1) * 8) / n
        for (k in TileOrder()) {
            val t = UIControl(CGRectMake(x, 8, w, 92))
            t.backgroundColor = CTL_CARD; t.layer.cornerRadius = 12.0; t.layer.borderWidth = 2.0; t.layer.borderColor = UIColor.clearColor.CGColor
            t.accessibilityIdentifier = "tile.$k"
            t.addTarget(UIControlEventTouchUpInside) { tileTapped(it) }
            val c = CtlLabel(CGRectMake(12, 8, w - 24, 14), caps[k], 11.0, true, CTL_INK2); c.userInteractionEnabled = false; t.addSubview(c)
            c.adjustsFontSizeToFitWidth = true; c.minimumScaleFactor = 0.8; tileCap[k] = c; tileCapText[k] = caps[k]!!
            val words = k == "defib"
            val v = CtlLabel(CGRectMake(12, 24, if (k == "hr") 74.0 else w - 24, 40), "--", if (words) 20.0 else 32.0, true, cols[k]!!)
            v.font = if (words) UIFont.boldSystemFontOfSize(20) else CtlDigits(32.0, true)
            v.adjustsFontSizeToFitWidth = true; v.minimumScaleFactor = 0.5; v.numberOfLines = if (words) 2 else 1; v.userInteractionEnabled = false
            t.addSubview(v)
            if (k == "hr") {      // ANDROID (proposta S3): accanto alla FC il nome del ritmo (due righe)
                val r = CtlLabel(CGRectMake(90, 24, w - 102, 40), "--", 15.0, true, CTL_INK); r.numberOfLines = 2
                r.adjustsFontSizeToFitWidth = true; r.minimumScaleFactor = 0.7; r.userInteractionEnabled = false; t.addSubview(r); tileRhythm = r
            }
            val s = CtlLabel(CGRectMake(12, 68, w - 24 - 14, 16), "", 11.0, false, CTL_INK2); s.adjustsFontSizeToFitWidth = true; s.minimumScaleFactor = 0.7; s.userInteractionEnabled = false; t.addSubview(s)
            // ANDROID (affordance 22/11, CTL-16 / SISTEMA §1): la tessera è una scheda: «›» in basso a destra e, se aperta, una linea
            // azzurra di 3 punti in basso (non più il bordo blu, che ora vale per «fuori limiti» e «in attesa»)
            extraTile(t, k, w)
            if (k == "hr") {      // ANDROID (regia morfologie ECG 22/11): sotto il ritmo la morfologia attiva («STEMI inf · BAV 1°»), solo se alterata
                // (aggiunta per ultima: switchDaeMode ridispone le prime viste della tessera per posizione)
                val e = CtlLabel(CGRectMake(90, 46, w - 102, 18), "", 12.0, true, CTL_INK2); e.adjustsFontSizeToFitWidth = true; e.minimumScaleFactor = 0.75
                e.userInteractionEnabled = false; e.hidden = true; e.accessibilityIdentifier = "tile.hr.ecg"; t.addSubview(e); tileEcg = e
            }
            view.addSubview(t)
            tiles[k] = t; tileValue[k] = v; tileSub[k] = s
            x += w + 8
        }
    }

    // ANDROID (affordance 22/11, CTL-19): «ⓘ» del pannello centrale: il testo d'aiuto che prima stava sotto il titolo
    private fun showEdInfo() {
        val t = edSub.text ?: ""
        if (t.isEmpty()) return
        val a = UIAlertController.alertControllerWithTitle(edTitle.text ?: "Aiuto", t, UIAlertControllerStyleAlert)
        a.addAction(UIAlertAction.actionWithTitle("Ho capito", UIAlertActionStyleCancel, null))
        presentViewController(a, true, null)
    }
    // ANDROID (affordance 22/11, CTL-13, scelta dell'utente del 22/11: niente velo né comandi attenuati in sola osservazione):
    // significante leggero, non bloccante — un piccolo lucchetto ambra nell'angolo dei comandi che cambiano il paziente.
    // Il registro e il piano di debriefing restano come sono.
    private fun refreshLocks(busy: Boolean) {
        if (lockShown == busy && !busy) return
        lockShown = busy
        val list = ArrayList<CtlButton>(listOf(cprBtn, ventBtn, nibpBtn, muteBtn, touchBtn, powerBtn, layoutBtn))
        list.addAll(rhythmBtn.values); for (q in quickBtns.values) for (p in q) list.add(p.second)
        list.addAll(vfBtns.values); onsetSkipBtn?.let { list.add(it) }          // ANDROID (insorgenza e ampiezza FV 22/11)
        for (b in list) if (busy) b.setTag(null, CTL_AMBER, "lock") else if (!rhythmBtn.values.contains(b)) b.setTag(null, null)
    }
    private fun extraTile(t: UIControl, k: String, w: Double) {
        val ch = CtlLabel(CGRectMake(w - 22, 64, 14, 22), "›", 20.0, true, CTL_INK3); ch.userInteractionEnabled = false; ch.textAlignment = NSTextAlignmentCenter; t.addSubview(ch)
        val ln = UIView(CGRectMake(12, 88, w - 24, 3)); ln.backgroundColor = CTL_TINT; ln.layer.cornerRadius = 1.5; ln.hidden = true; ln.userInteractionEnabled = false; t.addSubview(ln); tileLine[k] = ln
        val d = CtlDashBox(CGRectMake(0, 0, w, 92), CTL_YELLOW); d.radius = 12.0; d.hidden = true; t.addSubview(d); tileDash[k] = d
        val a = CtlLabel(CGRectMake(w - 106, 68, 80, 16), "", 12.0, true, CTL_INK); a.textAlignment = NSTextAlignmentRight; a.userInteractionEnabled = false; a.font = CtlDigits(12.0, true)
        a.hidden = true; t.addSubview(a); tileArrow[k] = a
        t.layer.borderWidth = 1.0; t.layer.borderColor = CTL_LINE.CGColor
    }
    private fun relayoutTileExtras(k: String, w: Double) {
        val t = tiles[k] ?: return
        for (v in t.subviews) {
            if (v === tileLine[k]) v.frame = CGRectMake(12, 88, w - 24, 3)
            else if (v === tileDash[k]) { v.frame = CGRectMake(0, 0, w, 92); v.setNeedsDisplay() }
            else if (v === tileArrow[k]) v.frame = CGRectMake(w - 106, 68, 80, 16)
            else if (v is UILabel && v.text == "›") v.frame = CGRectMake(w - 22, 64, 14, 22)
        }
    }

    // ANDROID (affordance 22/11): stato delle tessere.
    //  - scheda aperta (CTL-16): fondo in rilievo + linea azzurra in basso;
    //  - modifiche preparate (CTL-04): riquadro tratteggiato giallo (prima un bordo giallo uguale al giallo della CO₂);
    //  - fuori limiti sul monitor (CTL-21): stessa regola della web regia (warnTiles, alarm_limits): bordo rosso di 2 punti,
    //    «· FUORI LIMITI» nella didascalia e il valore che pulsa piano; allarmi silenziati = riquadro rosso tratteggiato.
    //    Temperatura e refill hanno soglie fisse (35-38,5 °C, refill > 3 s): «FUORI NORMA» (W-13);
    //  - transizione in corso (CTL-07): «→ 130» in alto a destra.
    private fun refreshTileStates(st: Map<String, Any?>, pulse: Boolean) {
        val L = DictOf(st["alarm_limits"])
        fun n(k: String): Double? = (st[k] as? Number)?.toDouble()
        fun lt(k: String, l: String): Boolean { val v = n(k); val x = (L[l] as? Number)?.toDouble(); return v != null && x != null && v < x }
        fun gt(k: String, l: String): Boolean { val v = n(k); val x = (L[l] as? Number)?.toDouble(); return v != null && x != null && v > x }
        val warn = mapOf(
            "hr" to (!pulse && st.containsKey("pulse_present") || lt("heart_rate_bpm", "hr_low") || gt("heart_rate_bpm", "hr_high")),
            "bp" to (lt("systolic_mmhg", "sys_low") || gt("systolic_mmhg", "sys_high")),
            "spo2" to lt("spo2_percent", "spo2_low"),
            "resp" to (lt("respiratory_rate_rpm", "rr_low") || gt("respiratory_rate_rpm", "rr_high") || lt("etco2_mmhg", "etco2_low") || gt("etco2_mmhg", "etco2_high")),
            "exam" to ((n("temperature_c") ?: 37.0) < 35 || (n("temperature_c") ?: 37.0) > 38.5 || (n("crt_s") ?: 0.0) > 3))
        val muted = IntOf(st["alarms_muted_remaining_s"]) > 0
        val tr = DictOf(st["transitions"])
        warnPhase = !warnPhase
        for (k in tiles.keys) {
            val t = tiles[k]!!
            val on = k == sel || (k == "hr" && (sel == "rhythm" || sel == "ecg"))   // ritmo e FC: stesso pannello (anche la morfologia ECG)
            val pend = (k == "hr" && pendingRhythm != null) || TileParams(k).any { pending.containsKey(it) }
            val w = warn[k] == true
            t.backgroundColor = if (on) CTL_CARD2 else CTL_CARD
            tileLine[k]?.hidden = !on
            t.layer.borderWidth = if (w && !muted) 2.0 else 1.0
            t.layer.borderColor = (if (w && !muted) CTL_RED else CTL_LINE).CGColor
            val d = tileDash[k]
            if (d != null) { d.hidden = !(pend || (w && muted)); d.color = if (pend) CTL_YELLOW else CTL_RED }
            val cap = tileCap[k]; val base = tileCapText[k] ?: ""
            val ct = if (w && k != "hr") base + (if (k == "exam") " · FUORI NORMA" else " · FUORI LIMITI") else (if (w && pulse) "$base · FUORI LIMITI" else base)
            if (cap != null) { if (cap.text != ct) cap.text = ct; cap.textColor = if (w) CtlRGB(1.0, 0.62, 0.58) else CTL_INK2 }
            val v = tileValue[k]
            if (v != null) { val a = if (w && warnPhase) 0.6f else 1f; if (v.alpha != a) UIView.animateWithDuration(0.6) { v.alpha = a } }
            // freccia del valore di arrivo
            val arrow = tileArrow[k]
            if (arrow != null) {
                val key = TileParams(k).firstOrNull { tr.containsKey(it) }
                val to = key?.let { (tr[it] as? Map<*, *>)?.get("to") }
                val at = if (to != null && !(k == "hr" && !pulse)) "→ " + (if (k == "bp") fmt("%s/%s", Desc((tr["systolic_mmhg"] as? Map<*, *>)?.get("to") ?: st["systolic_mmhg"]), Desc((tr["diastolic_mmhg"] as? Map<*, *>)?.get("to") ?: st["diastolic_mmhg"])) else Desc(to)) else ""
                arrow.hidden = at.isEmpty(); if (arrow.text != at) arrow.text = at
                // la riga sotto si stringe per lasciare posto alla freccia
                tileSub[k]?.let { sl -> val sw = t.bounds.width - 24 - 14 - (if (at.isEmpty()) 0.0 else 86.0); if (sl.frame.width != sw) sl.frame = CGRectMake(12, 68, sw, 16) }
                arrow.textColor = if (k == "hr") CTL_HR else (tileValue[k]?.textColor ?: CTL_INK)
            }
        }
    }

    private fun buildLeft() {
        // ANDROID (storia sim 22/11): quarto segmento «Storia Sim» (simulazioni salvate)
        leftSeg = UISegmentedControl(listOf("Stati", "Scenario", "Registro", "Storia Sim"))
        leftSeg.frame = CGRectMake(8, 108, LW, 44); leftSeg.tintColor = CTL_TINT       // ANDROID (affordance 22/11, CTL-01): 44 punti (erano 34)
        leftSeg.selectedSegmentIndex = m.prefs.int("sf_left")
        leftSeg.accessibilityIdentifier = "left.seg"
        leftSeg.addTarget(UIControlEventValueChanged) { leftChanged() }
        view.addSubview(leftSeg)
        val card = CtlCard(CGRectMake(8, 160, LW, LEFTH)); view.addSubview(card)
        leftScroll = CtlScrollView(CGRectMake(0, 0, LW, LEFTH))
        leftScroll.indicatorStyle = UIScrollViewIndicatorStyleWhite
        card.addSubview(leftScroll)
        leftFooter = UIView(CGRectMake(0, LEFTH, LW, 0)); leftFooter.backgroundColor = CTL_CARD
        card.addSubview(leftFooter)
        card.clipsToBounds = true
    }

    private fun buildRight() {
        // ANDROID (proposta S4): le 7 righe con interruttore diventano una griglia 2 × 4 di tessere a due stati
        // (attaccata / staccata): stessi comandi, metà dell'altezza; lo spazio liberato va alle azioni (accensione a distanza).
        val gw = (RW - 24 - 8) / 2; val gh = 42.0; val gg = 6.0      // ANDROID (affordance 22/11, CTL-01): 42 (erano 46): lo spazio va alle azioni
        val sh = 34 + 4 * gh + 3 * gg + 12
        val s = CtlCard(CGRectMake(RX, 108, RW, sh)); view.addSubview(s)
        s.addSubview(CtlLabel(CGRectMake(16, 10, 160, 18), "SONDE SUL PAZIENTE", 11.0, true, CTL_INK2))
        var i = 0
        for (d in Sensors()) {
            val (short, icon) = SensorShort(d.first)
            val f = CGRectMake(12 + (i % 2) * (gw + 8), 34 + (i / 2) * (gh + gg), gw, gh)
            val t = CtlSensorTile(f, d.first, short, icon, d.third); t.accessibilityIdentifier = "sw." + d.first
            t.addTarget(UIControlEventTouchUpInside) { sensorTapped(it as CtlSensorTile) }
            s.addSubview(t); sensorTile[d.first] = t
            if (listOf("spo2", "nibp", "art").contains(d.first)) {       // lato della sonda: tocco = sposta dall'altro lato
                // ANDROID (affordance 22/11, CTL-02 / 4.3, versione minima): il lato è un segmento a tutta altezza sul bordo destro
                // della tessera (40×42, era 38×32 in mezzo), separato da una linea; un tocco che lo manca e prende la tessera, a
                // scenario in corso, non stacca più la sonda: chiede un secondo tocco
                t.reserve(44.0)
                val sb = CtlButton.buttonWithTitle("dx", CtlButtonQuiet, null, CGRectMake(gw - 42, 0, 42, gh)) { siteTapped(it) }
                sb.titleLabel.font = UIFont.boldSystemFontOfSize(13); sb.maxLines = 1; sb.accessibilityIdentifier = "site." + d.first; sb.hidden = true
                sb.backgroundColor = CTL_CARD; sb.layer.cornerRadius = 10.0
                t.addSubview(sb); siteBtn[d.first] = sb
            }
            i++
        }
        // ottava casella: come si usa la griglia
        val hint = CtlLabel(CGRectMake(12 + gw + 8 + 6, 34 + 3 * (gh + gg), gw - 12, gh), "Tocca una sonda per attaccarla o staccarla", 11.0, false, CTL_INK3)
        hint.numberOfLines = 3; s.addSubview(hint); sensorHint = hint
        val ay = 108 + sh + 8
        val a = CtlCard(CGRectMake(RX, ay, RW, COLB - ay)); view.addSubview(a)
        // quattro gruppi con un razionale: 1) rianimazione (RCP e ventilazioni, i due tasti più usati in arresto),
        // 2) azioni rapide sul monitor e sul paziente, 3) il dispositivo del partecipante (accensione a distanza),
        // 4) impostazioni del monitor
        val IW = RW - 24
        // ANDROID (affordance 22/11, CTL-01): RCP e Ventila 70 punti (erano 52), azioni rapide 56; intestazioni più compatte
        a.addSubview(CtlLabel(CGRectMake(16, 6, IW, 14), "RIANIMAZIONE", 10.5, true, CTL_INK2))
        cprBtn = CtlButton.buttonWithTitle("RCP in corso", CtlButtonQuiet, null, CGRectMake(12, 22, (IW - 8) / 2, 70)) { cprTapped() }      // ANDROID (pannello RCP 22/11): era toggleCpr()
        ventBtn = CtlButton.buttonWithTitle("Ventila", CtlButtonQuiet, null, CGRectMake(12 + (IW - 8) / 2 + 8, 22, (IW - 8) / 2, 70)) { ventilate() }
        a.addSubview(CtlLabel(CGRectMake(16, 98, IW, 14), "AZIONI RAPIDE", 10.5, true, CTL_INK2))
        val qw = (IW - 16) / 3
        nibpBtn = CtlButton.buttonWithTitle("NIBP", CtlButtonQuiet, null, CGRectMake(12, 114, qw, 56)) { measureNibp() }
        muteBtn = CtlButton.buttonWithTitle("Silenzia", CtlButtonQuiet, null, CGRectMake(12 + qw + 8, 114, qw, 56)) { toggleMute() }
        touchBtn = CtlButton.buttonWithTitle("Tocco", CtlButtonQuiet, null, CGRectMake(12 + 2 * (qw + 8), 114, qw, 56)) { touchPatient() }
        val ids = listOf("act.cpr", "act.vent", "act.nibp", "act.mute", "act.touch"); var k = 0
        val icons = listOf("cpr", "lungs", "gauge", "bell", "tap")       // ANDROID (affordance 22/11, CTL-11): «Silenzia» con la campanella intera
        for (b in listOf(cprBtn, ventBtn, nibpBtn, muteBtn, touchBtn)) { b.titleLabel.font = UIFont.boldSystemFontOfSize(if (k < 2) 14 else 13); b.accessibilityIdentifier = ids[k]; b.setIcon(icons[k], true); k++; a.addSubview(b) }
        muteBtn.minFont = 12.0
        // ANDROID (BugFixController 5, proposta A1): accensione a distanza del dispositivo del partecipante (monitor o defibrillatore),
        // per esempio se il partecipante va in panico: stessa cosa del tasto di accensione del layout attivo
        a.addSubview(CtlLabel(CGRectMake(16, 176, IW, 14), "DISPOSITIVO DEL PARTECIPANTE", 10.5, true, CTL_INK2))
        powerBtn = CtlButton.buttonWithTitle("Accendi il dispositivo", CtlButtonTinted, CTL_GREEN, CGRectMake(12, 192, IW, 40)) { powerTapped() }
        powerBtn.titleLabel.font = UIFont.boldSystemFontOfSize(13); powerBtn.maxLines = 1; powerBtn.accessibilityIdentifier = "act.power"; powerBtn.setIcon("power", false); a.addSubview(powerBtn)
        // impostazioni del monitor: layout (anche da qui) e il pannello allarmi/audio/movimenti
        val sy = a.bounds.height - 10 - 40 - 6 - 44      // ANDROID (affordance 22/11, CTL-01): 44 e 40 punti (erano 34)
        // ANDROID (sala 22/11): accanto al layout il tasto «Sala» (fotocamera e microfono del monitor in diretta)
        layoutBtn = CtlButton.buttonWithTitle("Layout del monitor", CtlButtonTinted, CTL_TINT, CGRectMake(12, sy, IW - 8 - 92, 44)) { layoutMenu() }
        salaBtn = CtlButton.buttonWithTitle("Sala", CtlButtonQuiet, null, CGRectMake(12 + IW - 92, sy, 92, 44)) { openSala() }
        salaBtn.titleLabel.font = UIFont.boldSystemFontOfSize(13); salaBtn.maxLines = 1; salaBtn.accessibilityIdentifier = "act.sala"; salaBtn.setIcon("camera", false); a.addSubview(salaBtn)
        layoutBtn.titleLabel.font = UIFont.boldSystemFontOfSize(13); layoutBtn.maxLines = 1; layoutBtn.accessibilityIdentifier = "act.layout"; layoutBtn.setIcon("monitor", false); a.addSubview(layoutBtn)
        moreBtn = CtlButton.buttonWithTitle("Allarmi, audio, movimenti", CtlButtonQuiet, null, CGRectMake(12, sy + 50, IW - 8 - 92, 40)) { openMonitorPanel() }
        moreBtn.titleLabel.font = UIFont.boldSystemFontOfSize(13); moreBtn.maxLines = 1; moreBtn.accessibilityIdentifier = "act.more"; moreBtn.setIcon("bell", false); a.addSubview(moreBtn)
        // ANDROID (funzioni mancanti 22/11): accanto, «Trend» apre il pannello delle funzioni avanzate sulla scheda Trend
        val trendBtn = CtlButton.buttonWithTitle("Trend", CtlButtonQuiet, null, CGRectMake(12 + IW - 92, sy + 50, 92, 40)) { fxPanel.open(view, 0); refresh() }
        trendBtn.titleLabel.font = UIFont.boldSystemFontOfSize(13); trendBtn.maxLines = 1; trendBtn.accessibilityIdentifier = "act.trend"; trendBtn.setIcon("gauge", false); a.addSubview(trendBtn)
        // batteria di QUESTO tablet (il controller), in alto a destra come nella barra di stato di iOS
        batt = CtlLabel(CGRectMake(RW - 114, 8, 70, 18), "", 11.0, true, CTL_INK2); batt.textAlignment = NSTextAlignmentRight; batt.font = CtlDigits(11.0, true); s.addSubview(batt)
        battBody = UIView(CGRectMake(RW - 40, 10, 24, 12)); battBody.layer.borderColor = CTL_INK2.CGColor; battBody.layer.borderWidth = 1.2; battBody.layer.cornerRadius = 3.0; s.addSubview(battBody)
        val tip = UIView(CGRectMake(RW - 15, 13, 2, 6)); tip.backgroundColor = CTL_INK2; s.addSubview(tip)
        battFill = UIView(CGRectMake(2, 2, 20, 8)); battFill.backgroundColor = CTL_GREEN; battFill.layer.cornerRadius = 1.5; battBody.addSubview(battFill)
    }

    private fun buildBar() {
        val BW = W - 16                                                    // ANDROID (BugFixController 3): barra larga quanto la tela
        bar = CtlCard(CGRectMake(8, BARY, BW, BARH)); view.addSubview(bar)
        // ANDROID (affordance 22/11, CTL-06 / CTL-16): il timer è un tasto vero («menu di regia ⋯»: azzera il timer, azzera lo
        // scenario), non più un'area invisibile; «Azzera lo scenario» esce dal menu del collegamento al monitor
        val clockCard = UIView(CGRectMake(6, 6, 108, 52)); clockCard.backgroundColor = CTL_CARD2; clockCard.layer.cornerRadius = 10.0
        clockCard.layer.borderWidth = 1.0; clockCard.layer.borderColor = UIColor.colorWithWhite(1, 0.08).CGColor; clockCard.userInteractionEnabled = false; bar.addSubview(clockCard)
        clock = CtlLabel(CGRectMake(14, 6, 86, 34), "00:00", 28.0, true, CTL_INK); clock.font = CtlDigits(28.0, true); bar.addSubview(clock)
        val dots = UIImageView(CGRectMake(96, 12, 14, 14)); dots.image = CtlIcon("more", 14.0, CTL_INK2); dots.userInteractionEnabled = false; bar.addSubview(dots)
        val cz = CtlLabel(CGRectMake(14, 40, 96, 14), "TIMER · menu", 10.0, true, CTL_TINT); bar.addSubview(cz); clockSub = cz
        cz.adjustsFontSizeToFitWidth = true; cz.minimumScaleFactor = 0.7
        clockBtn = UIControl(CGRectMake(4, 0, 114, BARH)); clockBtn.accessibilityIdentifier = "bar.clock"
        clockBtn.addTarget(UIControlEventTouchDown) { if (CtlHaptic.enabled) CtlHaptic.tap() }
        clockBtn.addTarget(UIControlEventTouchUpInside) { clockMenu() }; bar.addSubview(clockBtn)
        // ANDROID (affordance 22/11, CTL-01 / SISTEMA §4): tasti della barra alti 48 punti (erano 40); FINE SCENARIO a 16 punti da
        // Pausa (erano 8)
        pauseBtn = CtlButton.buttonWithTitle("Pausa", CtlButtonQuiet, null, CGRectMake(122, 8, 104, 48)) { togglePause() }
        pauseBtn.accessibilityIdentifier = "bar.pause"; bar.addSubview(pauseBtn)
        // ANDROID (funzioni 21/11, richiesta utente): barra riordinata. A sinistra timer, Pausa e FINE SCENARIO (sempre con conferma);
        // al centro «Subito / Prepara» (e la durata in «Prepara»); a destra fase dello scenario / modifiche in attesa, Applica, Annulla, Monitor.
        endBtn = CtlButton.buttonWithTitle("FINE SCENARIO", CtlButtonTinted, CTL_RED, CGRectMake(242, 8, 128, 48)) { endTapped() }
        endBtn.titleLabel.font = UIFont.boldSystemFontOfSize(13); endBtn.maxLines = 2; endBtn.accessibilityIdentifier = "bar.end"; bar.addSubview(endBtn)
        // ANDROID (BugFixController 1–2, proposta S1): due modi. «Subito» = parte subito e arriva al valore gradualmente;
        // «Prepara» = le modifiche si accumulano e partono tutte insieme con «Applica (N)», nella durata scelta qui accanto
        modeLabel = CtlLabel(CGRectMake(348, 2, 240, 13), "APPLICA LE MODIFICHE", 10.5, true, CTL_INK2); bar.addSubview(modeLabel)
        // ANDROID (avvio scenario 22/11): due tasti veri (prima un UISegmentedControl 160×30 che si perdeva sul fondo scuro)
        // ANDROID (22/11, richiesta utente): la durata di «Subito» la spiega (i), non il tasto. Prima: (affordance X-01) «Subito» dichiarava la sua durata («Subito · 20 s»: arriva al valore in 20 s, non di colpo)
        modeBtns = listOf("Subito", "Prepara").mapIndexed { i, t ->
            val b = CtlButton.buttonWithTitle(t, CtlButtonQuiet, null, CGRectMake(348.0 + i * 126, 16, 132, 44)) { modeChanged(i == 1) }
            b.titleLabel.font = UIFont.boldSystemFontOfSize(16); b.maxLines = 1; b.layer.cornerRadius = 9.0
            b.accessibilityIdentifier = if (i == 0) "bar.mode.now" else "bar.mode.prep"; bar.addSubview(b); b
        }
        durLabel = CtlLabel(CGRectMake(600, 2, 200, 13), "DURATA", 10.5, true, CTL_INK2); bar.addSubview(durLabel)
        durBtns = listOf("10 s", "30 s", "1 min", "2 min").mapIndexed { i, t ->
            val b = CtlButton.buttonWithTitle(t, CtlButtonQuiet, null, CGRectMake(600.0 + i * 55, 16, 52, 44)) {
                prepSec = PrepDurations.getOrElse(i) { 30 }; refresh() }
            b.titleLabel.font = UIFont.boldSystemFontOfSize(13); b.maxLines = 1; b.layer.cornerRadius = 8.0
            b.accessibilityIdentifier = fmt("bar.dur.%d", i); bar.addSubview(b); b
        }
        pendLabel = CtlLabel(CGRectMake(688, 0, 150, BARH), "", 12.0, false, CTL_YELLOW); pendLabel.numberOfLines = 3; bar.addSubview(pendLabel)
        // ANDROID (funzioni 21/11, proposta G3): «Prossima fase» resta visibile anche con modifiche in attesa (riga sotto)
        phaseLine = CtlLabel(CGRectMake(688, 44, 150, 16), "", 11.0, true, CTL_GREEN); phaseLine.adjustsFontSizeToFitWidth = true; phaseLine.minimumScaleFactor = 0.6
        phaseLine.hidden = true; bar.addSubview(phaseLine)
        // ANDROID (affordance 22/11, CTL-06): il tasto del monitor è un indicatore del collegamento («● D17F»); il suo menu ha
        // solo le voci del collegamento
        monBtn = CtlButton.buttonWithTitle("Monitor", CtlButtonTinted, CTL_GREEN, CGRectMake(BW - 136, 8, 124, 48)) { monitorMenu() }
        monBtn.titleLabel.font = UIFont.boldSystemFontOfSize(13); monBtn.accessibilityIdentifier = "bar.monitor"; monBtn.maxLines = 1; monBtn.setIcon("monitor", false); bar.addSubview(monBtn)
        // ANDROID (affordance 22/11, CTL-10): «Scarta (N)» al posto di «Annulla», con un secondo tocco e lontano da «Applica»
        // (sta a sinistra, dopo il gruppo Subito/Prepara); dopo lo scarto per 5 s diventa «Ripristina»
        cancelBtn = CtlButton.buttonWithTitle("Scarta", CtlButtonQuiet, null, CGRectMake(BW - 136 - 10 - 80, 8, 80, 48)) { cancelTapped() }
        cancelBtn.titleLabel.font = UIFont.boldSystemFontOfSize(14); cancelBtn.maxLines = 2; cancelBtn.accessibilityIdentifier = "bar.cancel"; bar.addSubview(cancelBtn)
        // il grande tasto giallo: compare appena c'è qualcosa in attesa, con il numero delle modifiche
        applyBtn = CtlButton.buttonWithTitle("Applica", CtlButtonFilled, CTL_YELLOW, CGRectMake(BW - 136 - 10 - 80 - 8 - 140, 6, 140, 52)) { applyPending() }
        applyBtn.titleLabel.font = UIFont.boldSystemFontOfSize(18); applyBtn.setTitleColor(CtlRGB(0.10, 0.08, 0.0), UIControlStateNormal)
        applyBtn.accessibilityIdentifier = "bar.apply"; applyBtn.maxLines = 1; bar.addSubview(applyBtn)
        // un altro controller sta comandando: al posto della barra di regia
        busyBar = CtlCard(CGRectMake(8, BARY, BW, BARH)); busyBar.backgroundColor = CtlRGB(0.28, 0.19, 0.03); busyBar.hidden = true
        busyBar.layer.borderWidth = 1.0; busyBar.layer.borderColor = CTL_AMBER.colorWithAlphaComponent(0.6).CGColor
        busyText = CtlLabel(CGRectMake(18, 0, BW - 260, BARH), "", 16.0, true, CtlRGB(1.0, 0.85, 0.5)); busyText.numberOfLines = 2; busyBar.addSubview(busyText)
        val take = CtlButton.buttonWithTitle("Prendi il comando", CtlButtonFilled, CTL_ORANGE, CGRectMake(BW - 220, 8, 204, 48)) { takeControl() }
        take.accessibilityIdentifier = "busy.take"; take.maxLines = 1; busyBar.addSubview(take); takeBtn = take
        view.addSubview(busyBar)
        // ANDROID (audit 21/11, ANALISI_ESPERTO 3.1–3.2): caduta del collegamento. La regia resta a schermo (velata: i valori sono
        // quelli dell'ultima lettura) e al posto della barra una fascia dice «Ricollegamento a … 8 s» e quanti comandi aspettano;
        // i comandi toccati intanto vanno in coda e partono da soli al rientro. Se un comando scade senza arrivare la fascia
        // diventa rossa: «NON ARRIVATI: …», finché l'istruttore non tocca «Ho visto».
        staleCover = UIView(CGRectMake(0, 0, W, BARY - 4)); staleCover.backgroundColor = UIColor.colorWithWhite(0, 0.38)
        staleCover.userInteractionEnabled = false; staleCover.hidden = true; view.addSubview(staleCover)
        // ANDROID (funzioni 21/11, audit «Rimasti aperti»): la fascia del ricollegamento sta IN ALTO, sopra le tessere (che mostrano
        // valori vecchi): la barra in basso (Pausa, timer, Subito/Prepara, FINE SCENARIO) resta raggiungibile; i comandi vanno in coda.
        linkBar = CtlCard(CGRectMake(8, 8, BW, 92)); linkBar.hidden = true; linkBar.layer.borderWidth = 1.5
        linkText = CtlLabel(CGRectMake(18, 0, BW - 290, 92), "", 16.0, true, CTL_INK); linkText.numberOfLines = 3
        linkText.adjustsFontSizeToFitWidth = true; linkText.minimumScaleFactor = 0.75; linkBar.addSubview(linkText)
        linkBtn = CtlButton.buttonWithTitle("Scegli un altro monitor", CtlButtonQuiet, null, CGRectMake(BW - 260, 26, 244, 40)) { linkAction() }
        linkBtn.titleLabel.font = UIFont.boldSystemFontOfSize(14); linkBtn.maxLines = 1; linkBtn.accessibilityIdentifier = "link.action"; linkBar.addSubview(linkBtn)
        view.addSubview(linkBar)
        // ANDROID (funzioni 21/11, audit «Rimasti aperti»): in sola osservazione i comandi non devono sembrare attivi. Un velo che
        // prende i tocchi copre la regia e spiega perché; resta aperto il piano di debriefing (solo lettura).
        obsCover = UIView(CGRectMake(0, 0, W, 700)); obsCover.backgroundColor = UIColor.colorWithWhite(0, 0.55); obsCover.hidden = true
        obsCover.accessibilityIdentifier = "obs.cover"
        val ob = CtlCard(CGRectMake((W - 560) / 2, 270, 560, 150)); ob.layer.borderWidth = 1.5; ob.layer.borderColor = CTL_ORANGE.CGColor; obsCover.addSubview(ob)
        obsText = CtlLabel(CGRectMake(20, 12, 520, 70), "", 16.0, true, CtlRGB(1.0, 0.85, 0.5)); obsText.numberOfLines = 3; obsText.adjustsFontSizeToFitWidth = true; obsText.minimumScaleFactor = 0.8; ob.addSubview(obsText)
        val od = CtlButton.buttonWithTitle("Apri il piano di debriefing", CtlButtonQuiet, null, CGRectMake(20, 92, 520, 44)) { openDebrief(false) }
        od.titleLabel.font = UIFont.boldSystemFontOfSize(14); od.maxLines = 1; od.accessibilityIdentifier = "obs.debrief"; ob.addSubview(od)
        view.addSubview(obsCover)
        // ANDROID (due facilitatori 22/11): etichetta del ruolo quando comanda un altro (in alto a destra del pannello centrale)
        roleTag = CtlLabel(CGRectMake(CX + CW - 20 - 48 - 8 - 250, 118, 250, 24), "", 12.0, true, CTL_ON_LIGHT)
        roleTag.backgroundColor = CTL_AMBER; roleTag.textAlignment = NSTextAlignmentCenter; roleTag.layer.cornerRadius = 6.0; roleTag.clipsToBounds = true
        roleTag.adjustsFontSizeToFitWidth = true; roleTag.minimumScaleFactor = 0.7; roleTag.hidden = true; roleTag.userInteractionEnabled = false
        roleTag.accessibilityIdentifier = "obs.role"; view.addSubview(roleTag)
        view.bringSubviewToFront(busyBar)
    }

    private fun appVersion(): String = CtlVersionShown()

    // Schermata di avvio (stesso stile del Monitor): la barra segue il caricamento vero e l'ultima fase aspetta
    // il monitor già abbinato; se non risponde entro qualche secondo si passa alla scelta del monitor.
    private fun showSplash(staticOnly: Boolean) {
        view.viewWithTag(7777)?.removeFromSuperview()
        val sp = CtlSplashView(view.bounds, appVersion())
        sp.uiTag = 7777; view.addSubview(sp)
        if (staticOnly) { sp.makeStatic(); return }
        val me = this; val m = this.m
        val paired = (m.prefs.string("sf_monitor") ?: "").isNotEmpty()
        sp.runStages(listOf(
            CtlSplashStage("Pannelli di regolazione", { for (t in listOf("bp", "spo2", "resp", "exam", "defib", "monitor", "hr")) { me.sel = t; me.buildEditor() } }),
            CtlSplashStage("Stati e scenari", { me.leftSig = null; me.refreshLeft() }),
            CtlSplashStage("Collegamento diretto", { m.start() }),
            CtlSplashStage(if (paired) "Cerco il monitor abbinato" else "Cerco i monitor vicini", null,
                { if (paired) m.stage == CtlStageReady || m.stage == CtlStageNeedPin else m.link.monitors.isNotEmpty() }, if (paired) 7.0 else 3.0),
        ), 1.1, null)
    }

    // MARK: - Aggiornamento

    private fun flash(t: String) { flash(t, 4.0) }
    private fun flash(t: String, sec: Double) {
        // ANDROID (sola osservazione senza velo 22/11): un comando appena bloccato non deve sembrare eseguito («Ritmo: …»)
        if (CACurrentMediaTime() - obsBlockedAt < 0.5 && !t.startsWith("Comanda ")) return
        // ANDROID (affordance 22/11, CTL-03): i messaggi che non sono una conferma (avvisi, istruzioni) sono «ⓘ» azzurri, non ✓ verdi
        flashKind = if (t.startsWith("Comanda ") || t.startsWith("Tocca ") || t.startsWith("Il timer") || t.startsWith("Prima ") || t.startsWith("Azzeramento in sospeso") ||
                        t.startsWith("Bracciale sullo") || t.startsWith("Troppo tardi") || t.startsWith("Scarica n.") || t.startsWith("Monitor non") || t.startsWith("Scarico") || t.startsWith("Regia ")) "info" else "ok"
        flash = t; flashUntil = CACurrentMediaTime() + sec; refreshMsg()
    }
    private fun flashInfo(t: String, sec: Double = 4.0) { flash(t, sec); flashKind = "info"; refreshMsg() }
    private fun refreshMsg() {
        var lm = m.lastMessage
        // ANDROID (BugFixController 5d): un monitor vecchio non conosce set_device_power: lo si dice in chiaro
        if (lm != null && lm.contains("unknown command") && lm.contains("set_device_power"))
            lm = "Questo monitor non si accende a distanza (versione vecchia): aggiorna similFranz Monitor, oppure accendilo dal suo tasto."
        else if (lm != null && lm.contains("battery depleted")) lm = "Batteria simulata esaurita: il dispositivo non si accende. Tocca «Batteria sostituita» nei malfunzionamenti."      // ANDROID (guasti 22/11)
        else if (lm != null && lm.contains("(simulated fault)")) lm = "Non eseguito per un malfunzionamento simulato attivo (vedi «Malfunzionamenti del dispositivo»)"      // ANDROID (guasti 22/11)
        else if (lm != null && lm.contains("nothing to undo")) lm = "Troppo tardi: i 5 s per annullare l'avanzamento automatico sono passati."      // ANDROID (funzioni 21/11)
        // ANDROID (funzioni 21/11): comandi nuovi su un monitor vecchio: in chiaro che cosa manca, non «unknown command»
        else if (lm != null && lm.contains("unknown command")) {
            val c = Regex("unknown command '([a-z_0-9]+)'").find(lm)?.groupValues?.get(1) ?: ""
            lm = if (c == "end_scenario") "Il monitor non ha ancora la schermata «Scenario terminato» (versione vecchia): simulazione messa in pausa."
                 else fmt("Questo monitor non conosce ancora «%s» (versione vecchia): aggiorna similFranz Monitor.", NewFeatureName(c))
        }
        // ANDROID (audit 21/11, ANALISI_ESPERTO 3.10): un errore vecchio (più di 8 s) non copre più le conferme successive
        // ANDROID (affordance 22/11, CTL-03): striscia a tre stati — ✕ rosso (non eseguito / non arrivato), ✓ verde (eseguito dal
        // monitor), ⓘ azzurro (avviso); senza messaggi, con modifiche preparate, l'elenco IN ATTESA in un riquadro tratteggiato giallo
        var kind = ""
        if (!lm.isNullOrEmpty() && CACurrentMediaTime() - m.lastMessageAt < 8) { msg.text = lm; msg.textColor = CTL_RED; kind = "err" }
        else if (flash != null && CACurrentMediaTime() < flashUntil) { msg.text = flash; msg.textColor = if (flashKind == "info") CtlRGB(0.62, 0.82, 1.0) else CTL_GREEN; kind = flashKind }
        else if (pendingCount() > 0) {
            msg.text = fmt("IN ATTESA (%d): %s — partono insieme con «Applica» in %s", pendingCount(), pendingSummary(), Dur(prepSec))
            msg.textColor = CtlRGB(1.0, 0.9, 0.55); kind = "pend"
        }
        else { msg.text = "" }
        if (::msgBox.isInitialized) {
            msgBox.hidden = kind.isEmpty()
            val c = when (kind) { "err" -> CTL_RED; "ok" -> CTL_GREEN; "pend" -> CTL_YELLOW; else -> CTL_TINT }
            msgBox.backgroundColor = if (kind == "pend") CtlRGB(0.16, 0.14, 0.03) else c.colorWithAlphaComponent(0.13)
            msgBox.layer.borderColor = (if (kind == "pend") UIColor.clearColor else c.colorWithAlphaComponent(0.55)).CGColor
            msgDash.hidden = kind != "pend"
            msgIcon.text = when (kind) { "err" -> "✕"; "ok" -> "✓"; "info" -> "i"; else -> "" }
            msgIcon.textColor = c
            val mx = if (kind == "pend") 14.0 else 40.0
            if (msg.frame.x != mx) msg.frame = CGRectMake(mx, 0, msgBox.bounds.width - mx - 12, msgBox.bounds.height)
            center.bringSubviewToFront(msgBox)
        }
        if (::cprPanel.isInitialized && cprPanel.isOpen) cprPanel.setMsg(msg.text, msg.textColor)      // ANDROID (pannello RCP 22/11): la regia sotto è coperta
        if (::fxPanel.isInitialized && fxPanel.isOpen && !msg.text.isNullOrEmpty()) fxPanel.setMsg(msg.text, msg.textColor)   // ANDROID (funzioni mancanti 22/11)
    }

    private fun valueFor(key: String): Number? { val v = m.state?.get(key); return if (v is Number) v else null }
    // ANDROID (fix analisi 22/11, BUG-01): base di «+/−», cursori e pressione = valore di ARRIVO (modifica in attesa, poi
    // bersaglio della transizione in corso, poi valore attuale). Prima si partiva dal valore di metà rampa e il bersaglio si perdeva.
    private fun targetFor(key: String): Number? {
        pending[key]?.let { return it }
        ((DictOf(m.state?.get("transitions"))[key] as? Map<*, *>)?.get("to") as? Number)?.let { return it }
        return valueFor(key)
    }

    // ANDROID: UIDevice.batteryLevel / batteryState → ACTION_BATTERY_CHANGED (livello 0…1, -1 se sconosciuto)
    private fun batteryInfo(): Pair<Double, Boolean> {
        val i: Intent = try { Uk.ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) } catch (e: Exception) { null } ?: return Pair(-1.0, false)
        val level = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1); val scale = i.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        val status = i.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        val lvl = if (level < 0 || scale <= 0) -1.0 else level.toDouble() / scale
        return Pair(lvl, status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL)
    }

    private var lastShockSeen = -1
    private var paceVibRate = 0; private var paceVibOn = false
    private fun paceVibTick() {
        if (paceVibRate <= 0 || CACurrentMediaTime() - lastRefreshAt > 3.0) { paceVibOn = false; return }   // fermo anche se la regia non riceve più lo stato
        CtlHaptic.pace(); Uk.after(60.0 / paceVibRate) { paceVibTick() }
    }
    private fun refresh() {
        if (!isViewLoaded) return
        lastRefreshAt = CACurrentMediaTime()
        // ANDROID (guscio 25/11): a ogni scarica erogata dal monitor il controller dà una vibrazione
        run {
            val n = ((m.state?.get("defib") as? Map<*, *>)?.get("shock_count") as? Number)?.toInt()
            if (n != null) { if (lastShockSeen >= 0 && n > lastShockSeen) CtlHaptic.shock(); lastShockSeen = n }
        }
        // pacing che stimola davvero sul monitor: vibrazione lieve a ogni impulso, alla frequenza erogata
        run {
            val pc = (m.state?.get("defib") as? Map<*, *>)?.get("pacer") as? Map<*, *>
            paceVibRate = if (pc != null && BoolOf(pc["active"])) ((pc["rate_ppm"] as? Number)?.toInt() ?: 0) else 0
            if (paceVibRate > 0 && !paceVibOn) { paceVibOn = true; paceVibTick() }
        }
        // ANDROID (audit 21/11): durante un ricollegamento breve (fino a un minuto) la regia resta; la schermata di scelta solo dopo
        val ready = m.stage == CtlStageReady || (m.reconnecting && m.state != null)
        pairing.hidden = ready
        // ANDROID (debriefing scollegato 22/11): il piano di debriefing (sopra la regia) resta vivo anche senza monitor; dopo una
        // chiusura dell'app con l'istantanea di fine ancora aperta (né salvata né scartata) il piano si riapre da solo
        if (debrief?.presentingViewController != null) debrief?.reload()
        // (non dal primo refresh di viewDidLoad: la regia non è ancora nella finestra e finirebbe sopra il piano)
        if (m.endSnap != null && !endReopenTried && presentedViewController == null && view.superview != null && view.viewWithTag(7777) == null && CACurrentMediaTime() - loadedAt > 1.5) { endReopenTried = true; openDebrief(true) }
        if (!ready) { pairing.refresh(); view.bringSubviewToFront(pairing); val sp = view.viewWithTag(7777); if (sp != null) view.bringSubviewToFront(sp); return }
        val st = m.state ?: emptyMap()
        val sens = DictOf(st["sensors"])
        applyDaeMode(IsDaeLayout(st["layout"], st))

        // --- tessere: valore vero + che cosa vede il discente ---
        val pulse = BoolOf(st["pulse_present"])
        // ANDROID (proposta S3): una tessera sola per ritmo e FC; sotto, polso e FC che vede il partecipante
        // ANDROID (affordance 22/11, X-02): in arresto (nessun polso) il numero grande NON è più la FC (82 verde = «polso normale»):
        // diventa la sigla del ritmo in rosso (FV, TV, Asy, PEA); la FC impostata resta leggibile come «al ROSC: 82»
        val rhy = st["rhythm"] as? String
        if (!pulse && st.containsKey("pulse_present")) {
            tileValue["hr"]!!.text = mapOf("vf" to "FV", "vt" to "TV", "asystole" to "Asy", "pea" to "PEA")[rhy ?: ""] ?: "—"
            tileValue["hr"]!!.textColor = CTL_RED
            tileSub["hr"]!!.text = "SENZA POLSO · al ROSC: " + Num(st["heart_rate_bpm"], 0)
        } else {
            tileValue["hr"]!!.text = Num(st["heart_rate_bpm"], 0)
            tileValue["hr"]!!.textColor = CTL_HR
            tileSub["hr"]!!.text = "con polso · " + (if (BoolOf(sens["ecg"]) || BoolOf(sens["pads"])) "monitor " + Num(st["displayed_heart_rate_bpm"], 0) else "senza ECG")
        }
        tileRhythm.text = CtlRhythmName(rhy)
        // ANDROID (regia morfologie ECG 22/11): morfologia attiva sotto il ritmo; nei blocchi AV la FC è dei ventricoli, accanto gli atri
        run {
            val em = CtlEcg.of(st); val on = !em.isNormal; val tw = tiles["hr"]!!.bounds.width
            tileEcg.hidden = !on; if (on) { val t = CtlEcg.short(em, false); if (tileEcg.text != t) tileEcg.text = t }
            val f = if (on) CGRectMake(90, 22, tw - 102, 24) else CGRectMake(90, 24, tw - 102, 40)
            if (tileRhythm.frame != f) { tileRhythm.frame = f; tileRhythm.numberOfLines = if (on) 1 else 2 }
            val fe = CGRectMake(90, 46, tw - 102, 18); if (tileEcg.frame != fe) tileEcg.frame = fe
            val atr = st["atrial_rate_bpm"] as? Number
            if (pulse && BoolOf(st["av_block_active"]) && em.av != "first" && atr != null) tileSub["hr"]!!.text = "con polso · atri " + Num(atr, 0) + " · " +
                (if (BoolOf(sens["ecg"]) || BoolOf(sens["pads"])) "monitor " + Num(st["displayed_heart_rate_bpm"], 0) else "senza ECG")
            // i partecipanti hanno aperto la carta a 12 derivazioni (la voce nel registro la scrive il monitor)
            val l12 = st["ecg12_last"] as? Map<*, *>; val seq = (l12?.get("seq") as? Number)?.toLong() ?: 0L
            val seen = ecg12Seen
            if (seen != null && seq > seen) {
                val fc = if (l12?.get("electrodes") == false) " (senza elettrodi: carta vuota)" else (l12?.get("heart_rate_bpm") as? Number)?.let { " · FC " + Num(it, 0) } ?: ""
                flashInfo("I partecipanti hanno acquisito il 12 derivazioni$fc" + ((l12?.get("interpretation") as? String)?.substringBefore(";")?.trim()?.takeIf { it.isNotEmpty() }?.let { " · referto: $it" } ?: ""), 8.0)
            }
            if (m.state != null) ecg12Seen = seq
        }
        tileRhythm.textColor = if (pulse) CTL_INK else CtlRGB(1.0, 0.62, 0.58)
        tileSub["hr"]!!.textColor = if (pulse) CTL_INK2 else CTL_RED
        tileValue["bp"]!!.text = fmt("%s/%s", Num(st["systolic_mmhg"], 0), Num(st["diastolic_mmhg"], 0))
        val bp = ArrayList<String>()
        if (BoolOf(sens["art"])) bp.add(fmt("ART %s/%s", Num(st["art_displayed_systolic_mmhg"], 0), Num(st["art_displayed_diastolic_mmhg"], 0)))
        if (st["nibp_last_systolic_mmhg"] is Number) bp.add(fmt("NIBP %s/%s", Num(st["nibp_last_systolic_mmhg"], 0), Num(st["nibp_last_diastolic_mmhg"], 0)))
        tileSub["bp"]!!.text = if (bp.isNotEmpty()) "monitor: " + bp.joinToString(" · ") else "monitor: --"
        tileSub["bp"]!!.textColor = CTL_INK2
        tileValue["spo2"]!!.text = Num(st["spo2_percent"], 0)
        tileSub["spo2"]!!.text = "monitor: " + Num(st["spo2_displayed"], 0)
        tileValue["resp"]!!.text = fmt("%s · %s", Num(st["respiratory_rate_rpm"], 0), Num(st["etco2_mmhg"], 0))
        tileSub["resp"]!!.text = if (!BoolOf(sens["etco2"])) "monitor: senza CO₂"
            else (if (BoolOf(st["ventilation_manual"])) fmt("monitor: FR %s · EtCO₂ %s (ventilazioni)", Num(st["respiratory_rate_measured"], 0), Num(st["etco2_displayed"], 0))
                  else "monitor: EtCO₂ " + Num(st["etco2_displayed"], 0))
        tileValue["exam"]!!.text = fmt("%s° · %s s", Num(st["temperature_c"], 1), Num(st["crt_s"], 1))
        tileSub["exam"]!!.text = if (BoolOf(sens["temp"])) "monitor: temperatura visibile" else "monitor: temp senza sonda"
        run {   // terapia elettrica: che cosa stanno facendo i partecipanti
            val df = DictOf(st["defib"])
            val md = df["mode"] ?: "off"
            val ch = mapOf("charging" to " · in carica", "ready" to " · CARICO")[df["charge"] ?: ""] ?: ""
            // ANDROID (BugFixController 4): "off" = terapia elettrica non attiva (il dispositivo può essere acceso): «Non attiva», non «Spento»
            tileValue["defib"]!!.text = if (md == "off") "Non attiva" else (if (md == "aed") "DAE" else (if (md == "pacer") "Pacer" else fmt("Manuale %s J", Desc(df["energy_j"] ?: "--"))))
            tileSub["defib"]!!.text = if (BoolOf(df["outcome_pending"])) "ATTENDE LA TUA DECISIONE"
                else (if (monitorOn() == false) fmt("dispositivo spento · %s scariche", Desc(df["shock_count"] ?: 0)) else fmt("%s scariche%s", Desc(df["shock_count"] ?: 0), ch))
            tileSub["defib"]!!.textColor = if (BoolOf(df["outcome_pending"])) CTL_YELLOW else CTL_INK2
            // ANDROID (funzioni 21/11): durante l'arresto la tessera dice anche la qualità della RCP (cpr_metrics del cuore):
            // «CCF 78 % · pre 6 s» (frazione di compressione e ultima pausa prima della scarica), colorata secondo ERC
            val cm = DictOf(st["cpr_metrics"]); val ccf = cm["ccf_percent"] as? Number
            if (!BoolOf(df["outcome_pending"]) && !pulse && ccf != null) {
                val pre = cm["last_pre_shock_s"] as? Number
                tileSub["defib"]!!.text = fmt("%s scariche · CCF %d %%%s", Desc(df["shock_count"] ?: 0), lround(ccf.toDouble()), if (pre != null) fmt(" · pre %d s", lround(pre.toDouble())) else "")
                tileSub["defib"]!!.textColor = CtlWorst(CtlCcfColor(ccf.toDouble()), if (pre != null) CtlPauseColor(pre.toDouble()) else CTL_GREEN)
            }
        }
        refreshTileStates(st, pulse)

        refreshEditor()
        refreshLeft()

        // --- sonde (non si corregge l'interruttore appena toccato: il monitor risponde dopo un attimo) ---
        val now = CACurrentMediaTime()
        for (k in sw.keys.toList()) {
            val s = sw[k]!!
            if (s.window == null) { if (k.startsWith("spo2.") || k.startsWith("exam.")) sw.remove(k); continue }
            if (now - (swTouched[k] ?: 0.0) < 1.5) continue
            val on = if (k == "spo2.lowperf") BoolOf(st["spo2_low_perfusion_flag"])
                     else (if (k == "spo2.nosignal") !BoolOf(st["spo2_signal_valid"]) else BoolOf(sens[k]))
            if (s.isOn != on) s.setOn(on, true)
        }
        for ((k, t) in sensorTile) { if (now - (swTouched[k] ?: 0.0) >= 1.5) t.show(BoolOf(sens[k])); t.setArmed(now < (sensorArmed[k] ?: 0.0)) }      // ANDROID (proposta S4; affordance 22/11, CTL-02)
        val sites = DictOf(st["sensor_sites"])
        for (k in siteBtn.keys) {
            val sb = siteBtn[k]!!; val on = BoolOf(sens[k])
            if (sb.hidden == (sensorTile[k]?.isOn ?: on)) sb.hidden = !(sensorTile[k]?.isOn ?: on)
            val site = (sites[k] as? String) ?: mapOf("spo2" to "finger_l2", "nibp" to "upper_arm_r", "art" to "radial_l")[k] ?: ""
            val t = if (site.contains("_r")) "dx" else "sx"
            if (sb.titleForState(UIControlStateNormal) != t) sb.setTitle(t, UIControlStateNormal)
        }
        val cpr = BoolOf(st["cpr_active"])
        // ANDROID (affordance 22/11, CTL-12): «in corso» = verde pieno con ● e la parola di stato (l'arancione resta alla terapia
        // elettrica e alle cose armate); testo scuro sul verde (CTL-09)
        cprBtn.setStyle(if (cpr) CtlButtonFilled else CtlButtonQuiet, CTL_GREEN)
        // ANDROID (audit 21/11, ANALISI_ESPERTO § 2): «RCP in corso» a RCP ferma era ambiguo: ora dice che cosa fa il tocco
        // ANDROID (pannello RCP 22/11): con la RCP in corso il tocco non la ferma più: apre «Come vedi l'RCP?»
        // ANDROID (affordance 22/11, CTL-14): testo più corto, così non scende sotto i 13 pt
        val ct = if (cpr) "● RCP IN CORSO" else "Avvia la RCP"
        if (cprBtn.titleForState(UIControlStateNormal) != ct) cprBtn.setTitle(ct, UIControlStateNormal)
        if (cprPanel.isOpen) cprPanel.refresh()             // ANDROID (pannello RCP 22/11): valori veri del monitor
        if (fxPanel.isOpen) fxPanel.refresh()               // ANDROID (funzioni mancanti 22/11)
        val mute = IntOf(st["alarms_muted_remaining_s"])
        // ANDROID (affordance 22/11, CTL-11): da silenziato il tasto dice lo STATO («SILENZIATI 17 s», ambra pieno, testo scuro);
        // non silenziato dice l'azione («Silenzia» con la campanella intera)
        muteBtn.setStyle(if (mute > 0) CtlButtonFilled else CtlButtonQuiet, CTL_AMBER)
        val mt = if (mute > 0) fmt("SILENZIATI\n%d s", mute) else "Silenzia"
        if (muteBtn.titleForState(UIControlStateNormal) != mt) muteBtn.setTitle(mt, UIControlStateNormal)
        if ((mute > 0) != muteShown) { muteShown = mute > 0; muteBtn.maxLines = if (mute > 0) 2 else 1; muteBtn.setIcon(if (mute > 0) null else "bell", true) }
        val measuring = st["nibp_status"] == "measuring"
        // ventilazioni: attive con l'RCP in corso o in apnea (il paziente non respira da solo)
        val vm = BoolOf(st["ventilation_manual"]); val br = IntOf(st["breaths_last_min"])
        if (ventBtn.isEnabled != vm) ventBtn.isEnabled = vm
        val vt = if (!vm) "Ventila" else (if (br != 0) fmt("Ventila · %d/min", br) else "Ventila")
        if (ventBtn.titleForState(UIControlStateNormal) != vt) ventBtn.setTitle(vt, UIControlStateNormal)
        // batteria del controller
        // ANDROID (audit 21/11, B7): la batteria si rilegge ogni 30 s, non a ogni aggiornamento
        if (CACurrentMediaTime() - battAt > 30) { battAt = CACurrentMediaTime(); battCache = batteryInfo() }
        val (lvl, chg) = battCache
        val bt = if (lvl < 0) "tablet" else fmt("%s%d%%", if (chg) "⚡︎ " else "", lround(lvl * 100))   // ANDROID: "tablet" invece di "iPad"
        if (batt.text != bt) batt.text = bt
        val bc = if (lvl >= 0 && lvl < 0.2 && !chg) CTL_RED else (if (lvl >= 0 && lvl < 0.4 && !chg) CTL_ORANGE else CTL_GREEN)
        battFill.backgroundColor = bc; batt.textColor = if (bc === CTL_GREEN) CTL_INK2 else bc
        battFill.frame = CGRectMake(2, 2, 20 * max(0.08, if (lvl < 0) 1.0 else lvl), 8)
        val nt = if (measuring) "In misura" else "NIBP"
        if (nibpBtn.titleForState(UIControlStateNormal) != nt) nibpBtn.setTitle(nt, UIControlStateNormal)
        nibpBtn.isEnabled = !measuring
        val lt = fmt("Layout: %s", layoutName(st["layout"]))
        if (layoutBtn.titleForState(UIControlStateNormal) != lt) layoutBtn.setTitle(lt, UIControlStateNormal)
        stylePower(powerBtn)

        // --- regia ---
        m.storia.noteScenario(m.scenario)                  // ANDROID (storia sim 22/11): per la proposta del titolo
        val running = BoolOf(st["simulation_running"])
        // ANDROID (avvio scenario 22/11): PRONTO (prima di «Avvia») → timer fermo a 00:00, tasto «Avvia» verde pieno, FINE
        // SCENARIO spento; in corso «Pausa»; in pausa «Riprendi» arancione pieno (come prima)
        val scReady = ScenPhase(st) == "ready"
        clock.text = Clock(IntOf(st["scenario_time_s"]))
        clock.textColor = if (scReady) CTL_INK2 else (if (running) CTL_INK else CTL_AMBER)
        val resetWait = m.resetPending != null              // ANDROID (debriefing scollegato 22/11): azzeramento in sospeso
        val pk = (if (scReady) "ready" else (if (running) "run" else "pause")) + (if (resetWait) "|rp" else "")
        if (pk != phaseKey || pauseBtn.titleForState(UIControlStateNormal).isNullOrEmpty() || !pauseInit) {
            pauseInit = true; pauseShown = !running; phaseKey = pk
            pauseBtn.setTitle(if (scReady) "Avvia" else (if (running) "Pausa" else "Riprendi"), UIControlStateNormal)
            // ANDROID (affordance 22/11, CTL-09 / CTL-12): «Avvia» e «Riprendi» verdi pieni con testo scuro (13:1; prima bianco su
            // verde 2:1 e «Riprendi» arancione); la pausa la dice il timer ambra con «IN PAUSA»
            pauseBtn.setStyle(if (scReady) CtlButtonFilled else (if (running) CtlButtonQuiet else CtlButtonFilled), CTL_GREEN)
            pauseBtn.setIcon(if (running && !scReady) "pause" else "play", false)
            clockSub.text = if (resetWait) "AZZERAMENTO IN SOSPESO" else (if (scReady) "PRONTO · tocca Avvia" else (if (running) "TIMER · menu" else "IN PAUSA · menu"))
            clockSub.textColor = if (resetWait) CTL_AMBER else (if (scReady) CTL_GREEN else (if (running) CTL_TINT else CTL_AMBER))
            // ANDROID (affordance 22/11, CTL-09): FINE SCENARIO spento resta leggibile e dice perché («dopo Avvia»)
            endBtn.isEnabled = !scReady
            endBtn.setTitle(if (scReady) "FINE SCENARIO\ndopo Avvia" else "FINE SCENARIO", UIControlStateNormal)
            pauseBtn.alpha = if (resetWait) 0.45f else 1f       // ANDROID (debriefing scollegato 22/11): si tocca, ma spiega e non avvia
        }
        // ANDROID (BugFixController 2e, proposta S1): in «Prepara» la durata accanto; con modifiche in attesa la barra diventa
        // un riquadro giallo con l'elenco leggibile e il grande «Applica (N)»
        val n = pendingCount()
        styleChoices()                                     // ANDROID (avvio scenario 22/11): Subito/Prepara e durata ben visibili
        for (b in durBtns) b.hidden = !prepare
        durLabel.hidden = !prepare
        applyBtn.hidden = n == 0
        // ANDROID (affordance 22/11, CTL-10): «Scarta (N)» a due tocchi; dopo lo scarto «Ripristina» per 5 s
        val canRestore = n == 0 && discarded != null && CACurrentMediaTime() < discardedUntil
        cancelBtn.hidden = n == 0 && !canRestore
        val cArmed = n > 0 && CACurrentMediaTime() < cancelArmed
        val cnt = if (canRestore) fmt("Ripristina (%d)", discarded!!.first.size + (if (discarded!!.second != null) 1 else 0) + (if (discardedMorph != null) 1 else 0)) else (if (cArmed) "Conferma\nscarta" else fmt("Scarta (%d)", n))
        if (cancelBtn.titleForState(UIControlStateNormal) != cnt) cancelBtn.setTitle(cnt, UIControlStateNormal)
        cancelBtn.setStyle(if (cArmed) CtlButtonFilled else (if (canRestore) CtlButtonTinted else CtlButtonQuiet), if (cArmed) CTL_ORANGE else CTL_TINT)
        bar.backgroundColor = if (n != 0) CtlRGB(0.20, 0.17, 0.03) else CTL_CARD
        bar.layer.borderWidth = if (n != 0) 1.5 else 1.0; bar.layer.borderColor = (if (n != 0) CTL_YELLOW else CTL_LINE).CGColor
        val px = layoutBarGroup(n)                         // ANDROID (storia sim 22/11): gruppo Subito/Prepara sempre al centro
        val run = m.scenario; val scenOn = BoolOf(run?.get("active"))
        // ANDROID (funzioni 21/11, proposta G3): «Prossima fase: <nome>» sempre visibile mentre lo scenario gira
        val nextName = run?.get("next_name") as? String
        val nextText = if (nextName != null) "Prossima fase: $nextName" else "Ultima fase dello scenario"
        phaseLine.hidden = !(scenOn && n != 0)
        if (n != 0) {
            // ANDROID (affordance 22/11): l'elenco in attesa sta nella striscia sopra (refreshMsg); qui resta solo se c'è spazio
            pendLabel.text = fmt("In attesa: %s", pendingSummary())
            pendLabel.numberOfLines = if (scenOn) 2 else 3
            // nella riga stretta sotto le modifiche in attesa: «▸ Poi: <fase>» (il nome intero è nella colonna Scenario)
            val shortNext = if (nextName != null) "▸ Poi: $nextName" else "▸ Ultima fase"
            if (scenOn && phaseLine.text != shortNext) phaseLine.text = shortNext
            val at = fmt("Applica (%d)", n)
            if (applyBtn.titleForState(UIControlStateNormal) != at) applyBtn.setTitle(at, UIControlStateNormal)
        } else {
            pendLabel.numberOfLines = 3
            if (scenOn) pendLabel.text = fmt("Fase %d di %s · %s\n%s", IntOf(run?.get("phase")) + 1, Desc(run?.get("phases")), Desc(run?.get("phase_name")), nextText)
            else if (lastState != null) pendLabel.text = fmt("Ultimo stato attivato\n%s", lastState)
            else if (prepare) pendLabel.text = "Prepara: cambia i parametri,\npoi «Applica» li fa partire insieme"
            else pendLabel.text = ""
        }
        pendLabel.textColor = if (n != 0) CTL_YELLOW else (if (scenOn) CTL_INK else CTL_INK2)
        pendLabel.adjustsFontSizeToFitWidth = true; pendLabel.minimumScaleFactor = 0.8
        val pw = max(0.0, (if (n != 0) applyBtn.frame.x - 10 else monBtn.frame.x - 10) - px)
        // (con modifiche in attesa l'elenco è nella striscia sopra la barra: qui non serve ripeterlo stretto)
        pendLabel.hidden = pw < 40; phaseLine.hidden = phaseLine.hidden || pw < 40
        val pf = CGRectMake(px, 0, pw, if (n != 0 && scenOn) 44.0 else BARH); if (pendLabel.frame != pf) pendLabel.frame = pf
        val lf = CGRectMake(px, 44, pw, 16); if (phaseLine.frame != lf) phaseLine.frame = lf
        val mn = "● " + (m.monitorName?.replace("similFranz ", "") ?: "Monitor")
        if (monBtn.titleForState(UIControlStateNormal) != mn) monBtn.setIcon(null, false)      // ANDROID (affordance 22/11, CTL-06): indicatore
        if (monBtn.titleForState(UIControlStateNormal) != mn) monBtn.setTitle(mn, UIControlStateNormal)
        // Un solo controller alla volta (regole sul monitor): si vede sempre chi comanda, con che cosa e da dove.
        // Chi comanda attivamente è protetto (barra arancione, «Prendi il comando»); chi è fermo da più di un minuto
        // non blocca: si comanda normalmente e il primo comando passa da solo (lo si dice una volta).
        val c = m.controller ?: emptyMap()
        val other = BoolOf(c["active"]) && !BoolOf(c["mine"]); val idle = other && c["protected"] == false
        // ANDROID (due facilitatori 22/11, regola dell'utente): con un altro che comanda (anche fermo da più di un minuto) questo
        // controller ANNOTA: i comandi non partono e il comando si prende solo con «Prendi il comando» (due tocchi). Prima il primo
        // tocco di un controllo prendeva il comando a chi era fermo.
        val busy = other || obsForce; val mineNow = BoolOf(c["mine"])
        if (obsForce && !other) { busyText.text = "Comanda «Prova» (tablet, Wi-Fi): da qui osservi e annoti nel registro."
            obsText.text = "Sola osservazione: comanda «Prova» (tablet, Wi-Fi).\nI valori sono quelli veri, ma da qui i comandi non partono. Per comandare tocca «Prendi il comando» qui sotto." }
        busyBar.hidden = !busy; bar.hidden = busy
        // ANDROID (funzioni 21/11, audit «Rimasti aperti»): sola osservazione = regia velata, con la spiegazione
        // ANDROID (sola osservazione senza velo 22/11, richiesta utente): niente più velo a tutto schermo. La regia resta visibile e
        // dal vivo; solo la barra in basso diventa la fascia arancione con «Prendi il comando». I comandi non partono: al tocco
        // un avviso breve (CtlModel.observing / onBlocked). Piano di debriefing, Storia Sim e Sala restano come sono.
        obsCover.hidden = true
        observing = busy; obsWho = if (other) fmt("«%s»", Desc(c["name"] ?: "un altro controller")) else "«Prova»"
        if (busy) view.bringSubviewToFront(busyBar)
        placeOutcome()
        // ANDROID (due facilitatori 22/11): in alto, sopra il pannello centrale, il ruolo di questo controller
        roleTag.hidden = !busy
        if (busy) { val rt = fmt("ANNOTI · comanda %s", obsWho); if (roleTag.text != rt) roleTag.text = rt; view.bringSubviewToFront(roleTag) }
        // ANDROID (pannello RCP 22/11): il pannello copre l'etichetta del ruolo ma non la fascia «Prendi il comando»
        if (busy && cprPanel.isOpen) { view.bringSubviewToFront(cprPanel); view.bringSubviewToFront(busyBar) }
        if (busy && fxPanel.isOpen) { view.bringSubviewToFront(fxPanel); view.bringSubviewToFront(busyBar) }      // ANDROID (funzioni mancanti 22/11)
        refreshLocks(busy)                                 // ANDROID (affordance 22/11, CTL-13)
        val ta = CACurrentMediaTime() < takeArmed
        if (other) {
            val who = fmt("«%s» (%s, %s)", Desc(c["name"] ?: "un altro controller"), CtlKindName(c["kind"] as? String),
                          if (c["transport"] == "direct") "collegamento diretto" else "Wi-Fi")
            if (busy) busyText.text = if (c["lost_to"] != null) fmt("%s ha preso il comando: da qui ora osservi e annoti nel registro.", who)
                                      else fmt("Comanda %s: da qui osservi e annoti nel registro.", who)
            if (busy) obsText.text = fmt("Sola osservazione: comanda %s.\nI valori sono quelli veri, ma da qui i comandi non partono. Per comandare tocca «%s» qui sotto.", who,
                                         if (c["lost_to"] != null) "Riprendi il comando" else "Prendi il comando")
            val tt = if (ta) "Tocca di nuovo" else (if (c["lost_to"] != null) "Riprendi il comando" else "Prendi il comando")
            if (takeBtn.titleForState(UIControlStateNormal) != tt) takeBtn.setTitle(tt, UIControlStateNormal)
            takeBtn.setStyle(if (ta) CtlButtonFilled else CtlButtonTinted, if (ta) CTL_ORANGE else CTL_AMBER)
            // ANDROID (due facilitatori 22/11): niente più «il tuo primo comando prende il comando»
        }
        if (wasMine && other && c["lost_to"] != null) flash(fmt("«%s» ha preso il comando", Desc(c["lost_to"])))
        wasMine = mineNow; wasIdle = idle
        refreshNotice(st)
        refreshLink()
        refreshMsg()
    }

    // ANDROID (storia sim 22/11): il gruppo «APPLICA LE MODIFICHE» Subito/Prepara (con DURATA accanto in «Prepara») sta sempre
    // al centro della barra (centro = BW/2), chiuso o allargato. A destra Applica e Annulla si stringono un po' se, in «Prepara»
    // con modifiche in attesa, alla scritta resterebbe troppo poco. Restituisce dove comincia la scritta (bordo destro + 10).
    // ANDROID (avvio scenario 22/11): tasti veri. «Subito»/«Prepara» 120×37 (96×37 con la DURATA accanto, che ha 4 tasti 46×37):
    // chiuso 246 punti (483–729 su BW 1212), aperto 401 (405–806). Con modifiche in attesa in «Prepara» Applica 104 e Annulla 60:
    // alla scritta restano 76 punti (816–892).
    private fun layoutBarGroup(n: Int): Double {
        // ANDROID (affordance 22/11, CTL-01 / CTL-10): tasti alti 44 (Subito 132, 106 con la DURATA accanto, durate 52); «Scarta» o
        // «Ripristina» subito dopo il gruppo, poi almeno 16 punti di vuoto, poi «Applica» accanto al monitor
        val BW = bar.bounds.width; val mw = if (prepare) 106.0 else 132.0; val mg = 6.0; val dw = 52.0; val dg = 3.0; val gap = 10.0
        val mtot = mw * 2 + mg; val dtot = dw * 4 + dg * 3
        val gw = if (prepare) mtot + gap + dtot else mtot
        val gx = max(CGRectGetMaxX(endBtn.frame) + 12, Math.floor(BW / 2 - gw / 2))
        fun put(v: UIView, f: CGRect) { if (v.frame != f) v.frame = f }
        put(modeLabel, CGRectMake(gx, 2, mtot, 13))
        for ((i, b) in modeBtns.withIndex()) put(b, CGRectMake(gx + i * (mw + mg), 16, mw, 44))
        val dx = gx + mtot + gap
        put(durLabel, CGRectMake(dx, 2, dtot, 13))
        for ((i, b) in durBtns.withIndex()) put(b, CGRectMake(dx + i * (dw + dg), 16, dw, 44))
        var px = gx + gw + 12
        if (!cancelBtn.hidden) { put(cancelBtn, CGRectMake(px, 8, 88, 48)); px += 88 + 24 }
        val aw = if (monBtn.frame.x - 10 - 140 - px > 0) 140.0 else 120.0
        put(applyBtn, CGRectMake(monBtn.frame.x - 10 - aw, 6, aw, 52))
        val af = UIFont.boldSystemFontOfSize(if (aw < 140) 17 else 18); if (applyBtn.titleLabel.font.pointSize != af.pointSize) applyBtn.titleLabel.font = af
        return px
    }

    // ANDROID (avvio scenario 22/11): aspetto dei tasti di scelta. Scelto = PIENO (Subito blu con testo bianco; Prepara e la
    // durata gialli con testo scuro, come «Applica»); non scelto = fondo in rilievo, bordo 1.5 nel colore del tasto, testo chiaro
    private fun styleChoices() {
        val di = PrepDurations.indexOf(prepSec)
        val key = fmt("%b/%d", prepare, di)
        if (key == choiceKey) return
        choiceKey = key
        val dark = CtlRGB(0.10, 0.08, 0.0)
        fun paint(b: CtlButton, on: Boolean, c: UIColor, darkInk: Boolean) {
            b.setStyle(if (on) CtlButtonFilled else CtlButtonQuiet, c)
            b.setTitleColor(if (on) (if (darkInk) dark else UIColor.whiteColor) else CTL_INK, UIControlStateNormal)
            b.layer.borderWidth = if (on) 0.0 else 1.5
            b.layer.borderColor = c.colorWithAlphaComponent(0.8).CGColor
        }
        paint(modeBtns[0], !prepare, CTL_TINT, false)
        paint(modeBtns[1], prepare, CTL_YELLOW, true)
        for ((i, b) in durBtns.withIndex()) paint(b, i == di, CTL_YELLOW, true)
    }

    // ANDROID (audit 21/11): fascia del collegamento (ricollegamento in corso / comandi non arrivati) al posto della barra
    private fun refreshLink() {
        val rec = m.stage != CtlStageReady && m.reconnecting
        val lost = m.lost
        // la fascia rossa nasconde pausa e timer: resta 20 s (o fino a «Ho visto»), poi l'avviso resta solo nel registro dell'app
        if (lost.isNotEmpty() && !rec && CACurrentMediaTime() - m.lostAt > 20) { m.clearLost(); return }
        val show = rec || lost.isNotEmpty()
        staleCover.hidden = !rec
        linkBar.hidden = !show
        // ANDROID (funzioni 21/11): la fascia sta in alto; la barra resta (prima la sostituiva e la pausa non si raggiungeva)
        if (show) { view.bringSubviewToFront(staleCover); view.bringSubviewToFront(linkBar); view.bringSubviewToFront(if (bar.hidden) busyBar else bar) }
        if (!show) return
        val q = m.queued
        if (rec && m.awayOK) {
            // ANDROID (debriefing scollegato 22/11): tablet in sala debriefing: niente conto dei secondi né toni d'allarme
            linkText.text = (if (m.resetPending != null) "Monitor non raggiungibile: azzeramento in sospeso, parte da solo al rientro nella sala di simulazione"
                             else "Monitor non raggiungibile: il debriefing funziona lo stesso") + "\nMi ricollego da solo a " + (m.monitorName?.replace("similFranz ", "") ?: "monitor") +
                             (if (m.storia.unsentCount > 0) fmt(" · %d in Storia Sim da inviare", m.storia.unsentCount) else "")
            linkText.textColor = CTL_INK2
            linkBar.backgroundColor = CTL_CARD; linkBar.layer.borderColor = CTL_LINE.CGColor
            if (linkBtn.titleForState(UIControlStateNormal) != "Scegli un altro monitor") linkBtn.setTitle("Scegli un altro monitor", UIControlStateNormal)
        } else if (rec) {
            val sec = (CACurrentMediaTime() - m.reconnectSince).toInt()
            linkText.text = fmt("Ricollegamento a %s… %d s%s", m.monitorName?.replace("similFranz ", "") ?: "monitor", sec,
                if (q > 0) fmt("\n%d %s in coda: partono appena il monitor risponde", q, if (q == 1) "comando" else "comandi")
                else "\nI valori qui sono quelli dell'ultima lettura") + "\nPausa, timer e FINE SCENARIO in basso restano a disposizione"
            linkText.textColor = CTL_YELLOW
            linkBar.backgroundColor = CtlRGB(0.22, 0.18, 0.03); linkBar.layer.borderColor = CTL_YELLOW.CGColor
            if (linkBtn.titleForState(UIControlStateNormal) != "Scegli un altro monitor") linkBtn.setTitle("Scegli un altro monitor", UIControlStateNormal)
        } else {
            linkText.text = fmt("NON ARRIVATI al monitor: %s\nRifalli se servono ancora.", lost.joinToString(" · "))
            linkText.textColor = CtlRGB(1.0, 0.75, 0.72)
            linkBar.backgroundColor = CtlRGB(0.30, 0.06, 0.05); linkBar.layer.borderColor = CTL_RED.CGColor
            if (linkBtn.titleForState(UIControlStateNormal) != "Ho visto") linkBtn.setTitle("Ho visto", UIControlStateNormal)
        }
    }
    private fun linkAction() {
        if (m.stage != CtlStageReady && m.reconnecting) {
            val a = UIAlertController.alertControllerWithTitle("Lasciare questo monitor?",
                fmt("Il ricollegamento a %s è in corso. Scegliendo un altro monitor i comandi in coda non partiranno.", m.monitorName ?: "monitor"), UIAlertControllerStyleAlert)
            a.addAction(UIAlertAction.actionWithTitle("Aspetta", UIAlertActionStyleCancel, null))
            a.addAction(UIAlertAction.actionWithTitle("Scegli un altro monitor", UIAlertActionStyleDestructive) { m.forgetMonitor() })
            presentViewController(a, true, null)
        } else m.clearLost()
    }

    // MARK: - Regia DAE (proposta A8)

    // a ciò che un DAE vede e fa: ritmo e polso, terapia elettrica (analisi, scariche, esito), piastre, RCP, ventilazioni e tocco.
    // Spariscono PA, SpO₂, respiro/EtCO₂, esame, le altre sonde, NIBP e Silenzia (un DAE non ha allarmi). Tornando a un monitor
    // completo tutto ricompare com'era. I valori del paziente non cambiano: si nascondono solo i comandi.
    private fun keep(v: UIView) { if (!origFrame.containsKey(v)) origFrame[v] = v.frame }
    private fun applyDaeMode(dae: Boolean) {
        if (daeMode == dae) { daePending = null; return }
        // ANDROID (funzioni 21/11, ANALISI_ESPERTO 3.10): il pannello non cambia più sotto il dito. Se il layout cambia sul monitor
        // (un partecipante, un collega, la web app) si mostra solo un avviso «Il monitor ora è un DAE: apri la regia DAE»;
        // la regia cambia al tocco dell'avviso o di una tessera. Subito solo al primo stato o se il layout l'ha scelto questo controller.
        if (daeMode != null && CACurrentMediaTime() - layoutAskedAt > 10) { daePending = dae; return }
        switchDaeMode(dae)
    }
    private fun switchDaeMode(dae: Boolean) {
        daePending = null
        daeMode = dae
        // tessere: solo «Ritmo · FC» e «Terapia elettrica», larghe metà schermo ciascuna
        val keys = if (dae) listOf("hr", "defib") else TileOrder()
        val n = keys.size; val w = (W - 16 - (n - 1) * 8) / n; var x = 8.0
        for (k in TileOrder()) {
            val t = tiles[k]!!; t.hidden = !keys.contains(k)
            if (t.hidden) continue
            t.frame = CGRectMake(x, 8, w, 92)
            val sv = t.subviews
            sv[0].frame = CGRectMake(12, 8, w - 24, 14)                                          // didascalia
            sv[1].frame = CGRectMake(12, 24, if (k == "hr") 74.0 else w - 24, 40)                 // valore
            if (k == "hr") { sv[2].frame = CGRectMake(90, 24, w - 102, 40); sv[3].frame = CGRectMake(12, 68, w - 24 - 14, 16) }
            else sv[2].frame = CGRectMake(12, 68, w - 24 - 14, 16)                                // riga sotto
            relayoutTileExtras(k, w)                                                              // ANDROID (affordance 22/11)
            x += w + 8
        }
        (tiles["defib"]!!.subviews[0] as? UILabel)?.text = if (dae) "DAE · ANALISI E SCARICHE" else "TERAPIA ELETTRICA"
        tileCapText["defib"] = if (dae) "DAE · ANALISI E SCARICHE" else "TERAPIA ELETTRICA"
        // sonde: solo le piastre, in prima posizione
        for ((k, t) in sensorTile) {
            keep(t)
            t.hidden = dae && k != "pads"
            t.frame = if (dae && k == "pads") origFrame[sensorTile["ecg"]!!] ?: origFrame[t]!! else origFrame[t]!!
        }
        keep(sensorHint)
        sensorHint.text = if (dae) "Layout DAE: conta solo il cavo delle placche. Tocca per collegarle o staccarle." else "Tocca una sonda per attaccarla o staccarla"
        sensorHint.frame = if (dae) { val f = origFrame[sensorTile["pads"]!!]!!; CGRectMake(f.x + 6, f.y, f.width - 12, f.height) } else origFrame[sensorHint]!!
        // azioni rapide: niente NIBP né Silenzia; «Tocco» (artefatto durante l'analisi) prende tutta la riga
        keep(touchBtn); keep(nibpBtn)
        nibpBtn.hidden = dae; muteBtn.hidden = dae
        touchBtn.frame = if (dae) { val a = origFrame[nibpBtn]!!; val b = origFrame[touchBtn]!!; CGRectMake(a.x, a.y, b.x + b.width - a.x, a.height) } else origFrame[touchBtn]!!
        touchBtn.setTitle(if (dae) "Tocco del paziente (artefatto)" else "Tocco", UIControlStateNormal)
        // pannello centrale: se mostrava un parametro che non c'è più, si torna a ritmo e frequenza
        // entrando nella regia DAE si apre sempre il pannello «DAE»; uscendo, se si guardava il DAE si torna a ritmo e frequenza
        if (dae) sel = "defib" else if (sel == "defib") sel = "hr"
        edBuilt = null
        edBuilt = null                                    // il pannello «Terapia elettrica» diventa «DAE» e viceversa
        flash(if (dae) "Regia DAE: sul monitor c'è un DAE" else "Regia completa: sul monitor c'è un monitor")
    }

    // MARK: - Tessere e pannello centrale

    private fun tileTapped(t: UIControl) {
        // ANDROID (funzioni 21/11): un tocco su una tessera applica il cambio di regia rimandato (monitor diventato DAE o viceversa)
        daePending?.let { switchDaeMode(it) }
        if (!t.hidden) sel = t.accessibilityIdentifier!!.substring(5)
        refresh()
    }

    // ANDROID (funzioni 21/11): banner in alto nel pannello centrale. Priorità all'avanzamento automatico (dura 5 s), poi
    // all'avviso del layout cambiato sul monitor.
    private fun refreshNotice(st: Map<String, Any?>) {
        val now = CACurrentMediaTime()
        val aa = st["auto_action"] as? Map<*, *>
        var kind: String? = null; var text = ""; var btn = ""
        // ANDROID (fix analisi 22/11, BUG-13): il numero dell'avanzamento (seq) vive solo nella memoria del monitor e riparte da 1
        // a ogni avvio dell'app del monitor (o con un altro monitor): finito un avanzamento si dimentica quello visto/annullato,
        // altrimenti il primo avanzamento nuovo con lo stesso numero non mostrava «Annulla». In più: stesso numero ma tempo
        // rimasto più lungo di quello che resterebbe = avanzamento nuovo.
        if (aa == null) { if (undoInFlight < 0) { autoSeqSeen = -1L; autoUndone = -1L } }
        else {
            val seq = (aa["seq"] as? Number)?.toLong() ?: 0L
            val remS = DblOf(aa["remaining_s"])
            if (seq == autoSeqSeen && undoInFlight != seq && remS > autoUntilLocal - now + 1.5) { autoSeqSeen = -1L; if (autoUndone == seq) autoUndone = -1L }
            if (seq != autoSeqSeen) { autoSeqSeen = seq; autoUntilLocal = now + remS }
            val rem = ceil(autoUntilLocal - now).toInt()
            if (rem > 0 && seq != autoUndone) { kind = "auto"; text = fmt("Automatico: %s", Desc(aa["text"])); btn = fmt("Annulla · %d s", rem) }
        }
        if (kind == null && daePending != null) {
            kind = "dae"
            text = if (daePending == true) "Il monitor ora è un DAE (layout «${layoutName(st["layout"])}»): apri la regia DAE."
                   else "Sul monitor ora c'è «${layoutName(st["layout"])}»: torna alla regia completa."
            btn = if (daePending == true) "Apri la regia DAE" else "Regia completa"
        }
        // ANDROID (trasmissione 12D 22/11): i partecipanti hanno trasmesso il 12 derivazioni dal monitor (voce del registro con tag
        // "ecg12_tx"): l'istruttore fa da centrale e risponde (voce "ecg12_rx"). L'avviso resta finché qualcuno (anche un'altra
        // regia) risponde o lo si chiude.
        if (kind == null) {
            val tx = tx12Pending()
            if (tx != null) {
                kind = "tx12"; text = tx12Text(tx, st); btn = "Rispondi come centrale"
                val i = (tx["i"] as? Number)?.toLong() ?: -1L
                if (i != tx12Flashed) { tx12Flashed = i; flashInfo("ECG 12D trasmesso alla Centrale Operativa per valutazione: tocca «Rispondi come centrale»", 6.0) }
            }
        }
        noticeKind = kind
        noticeBox.hidden = kind == null
        if (kind == null) return
        // ANDROID (trasmissione 12D 22/11): l'avviso della trasmissione è azzurro (informazione), gli altri gialli come prima
        val tx = kind == "tx12"
        noticeBox.backgroundColor = if (tx) CtlRGB(0.03, 0.15, 0.28) else CtlRGB(0.24, 0.17, 0.02)
        noticeBox.layer.borderColor = (if (tx) CTL_TINT else CTL_YELLOW).CGColor
        noticeText.textColor = if (tx) CtlRGB(0.78, 0.90, 1.0) else CtlRGB(1.0, 0.9, 0.6)
        noticeBtn.setStyle(CtlButtonFilled, if (tx) CTL_TINT else CTL_YELLOW)
        noticeBtn.setTitleColor(if (tx) UIColor.whiteColor else CtlRGB(0.10, 0.08, 0.0), UIControlStateNormal)
        noticeBox.frame = CGRectMake(8, if (outcome != null) 262.0 else 6.0, center.bounds.width - 16, 72)
        if (noticeText.text != text) noticeText.text = text
        if (noticeBtn.titleForState(UIControlStateNormal) != btn) noticeBtn.setTitle(btn, UIControlStateNormal)
        noticeBtn.isEnabled = !(kind == "auto" && undoInFlight == autoSeqSeen)      // ANDROID (fix analisi 22/11, BUG-10): non riattivato dal ridisegno
        center.bringSubviewToFront(noticeBox)
        if (kind == "auto") Uk.after(0.5) { if (noticeKind == "auto") refresh() }      // conto alla rovescia al mezzo secondo
    }
    // MARK: ANDROID (trasmissione 12D 22/11) — ECG 12D trasmesso dal monitor: avviso e «Rispondi come centrale»

    // ultima trasmissione del registro (sessione in corso) ancora senza risposta: non chiusa qui, recente (15 min dalla voce più
    // nuova del registro). Una risposta da un'altra regia (web o un altro controller) la chiude anche qui.
    private fun tx12Pending(): Map<String, Any?>? {
        // in PRONTO (prima di «Avvia», o dopo un azzeramento) niente avviso: una trasmissione di prima non si valuta più e il
        // registro non scriverebbe la risposta
        if (ScenPhase(m.state) == "ready") return null
        val log = m.log
        if (log.isEmpty()) return null
        var tx: Map<String, Any?>? = null
        for (d in log) { val tg = d["tag"]; if (tg == "ecg12_tx") tx = d else if (tg == "ecg12_rx") tx = null }
        if (tx == null) return null
        val i = (tx["i"] as? Number)?.toLong() ?: -1L
        if (i == tx12Dismissed) return null
        val wl = (log.last()["w"] as? Number)?.toLong() ?: 0L; val wt = (tx["w"] as? Number)?.toLong() ?: 0L
        return if (wl - wt <= 900) tx else null
    }
    private fun tx12Parts(tx: Map<String, Any?>): Pair<String, String> {
        val t = tx["text"] as? String ?: ""
        val dest = Regex("trasmesso a (.+?) \\(").find(t)?.groupValues?.get(1) ?: "Centrale Operativa"
        val det = t.substringAfter("(", "").substringBeforeLast(")").trim()
        return Pair(dest, det)
    }
    private fun tx12Time(tx: Map<String, Any?>): String {
        val w = (tx["w"] as? Number)?.toLong() ?: return "--:--"
        return java.text.SimpleDateFormat("HH:mm", java.util.Locale.ITALY).format(java.util.Date(w * 1000))
    }
    // tre righe brevi: titolo, ora e destinatario, referto (prima voce dell'interpretazione) e morfologia; il resto nella risposta
    private fun tx12Text(tx: Map<String, Any?>, st: Map<String, Any?>): String {
        val (dest, det) = tx12Parts(tx)
        val fc = det.substringBefore(",").trim()
        val ref = det.substringAfter("interpretazione: ", "").substringBefore(";").trim()
        val ecg = Regex("ECG: ([^;]+)").find(det)?.groupValues?.get(1)?.trim()
        val now = (st["ecg_morphology"] as? Map<*, *>)?.get("label") as? String
        return fmt("ECG 12D trasmesso alla Centrale Operativa per valutazione\nore %s · destinatario: %s\n%s%s%s%s", tx12Time(tx), dest, fc,
                   if (ref.isNotEmpty()) " · $ref" else "", if (ecg != null) " · ECG: $ecg" else "",
                   if (now != null && ecg != null && now != ecg) " (adesso: $now)" else "")
    }
    private fun tx12Reply() {
        val tx = tx12Pending() ?: return
        val (dest, _) = tx12Parts(tx)
        val me = this
        val a = CtlSheet.actions("Rispondi come centrale",
            fmt("12D delle %s → %s · nel registro con il tuo nome", tx12Time(tx), dest))
        for (r in Tx12Replies) a.addAction(UIAlertAction.actionWithTitle(r, UIAlertActionStyleDefault) { me.tx12Send(tx, r) })
        a.addAction(UIAlertAction.actionWithTitle("Testo libero…", UIAlertActionStyleDefault) { me.tx12Free(tx) })
        a.addAction(UIAlertAction.actionWithTitle("Chiudi l'avviso senza rispondere", UIAlertActionStyleDestructive) {
            me.tx12Dismissed = (tx["i"] as? Number)?.toLong() ?: -1L; me.refresh() })
        a.addAction(UIAlertAction.actionWithTitle("Annulla", UIAlertActionStyleCancel, null))
        a.present()      // ANDROID (menu layout a tema 22/11): pannello CtlSheet nel tema (era un AlertDialog di sistema)
    }
    private fun tx12Free(tx: Map<String, Any?>) {
        val a = UIAlertController.alertControllerWithTitle("Risposta della centrale", "Scrivi la risposta: finisce nel registro con il tempo di scenario.", UIAlertControllerStyleAlert)
        a.addTextField { tf -> tf.autocapitalizationType = UITextAutocapitalizationTypeSentences; tf.placeholder = "es. Centralizzare: emodinamica allertata" }
        val me = this
        a.addAction(UIAlertAction.actionWithTitle("Annulla", UIAlertActionStyleCancel, null))
        a.addAction(UIAlertAction.actionWithTitle("Invia risposta", UIAlertActionStyleDefault) {
            val t = (a.textFields.firstOrNull()?.text ?: "").trim(' ', '\t')
            if (t.isNotEmpty()) me.tx12Send(tx, t)
        })
        presentViewController(a, true, null)
    }
    private fun tx12Send(tx: Map<String, Any?>, reply: String) {
        if (ScenPhase(m.state) == "ready") { flash(NotStartedText); return }
        val (dest, _) = tx12Parts(tx)
        // la voce chiude l'avviso su tutte le regie (tag "ecg12_rx"); by = nome di questo controller (lo mette il monitor)
        logQuick("note", fmt("Risposta della centrale al 12D (inviato a %s alle %s): %s", dest, tx12Time(tx), reply), "ecg12_rx", "tx12:$reply")
    }

    private fun noticeTapped() {
        if (noticeKind == "tx12") { tx12Reply(); return }                  // ANDROID (trasmissione 12D 22/11)
        if (noticeKind == "dae") { daePending?.let { switchDaeMode(it) }; refresh(); return }
        if (noticeKind != "auto") return
        val seq = autoSeqSeen; noticeBtn.isEnabled = false
        // ANDROID (fix analisi 22/11, BUG-10): un solo «Annulla» per avanzamento: prima un ridisegno riattivava il tasto prima della
        // risposta, un secondo tocco mandava un secondo undo («nothing to undo») e «Troppo tardi» copriva l'annullamento riuscito
        if (undoInFlight == seq || autoUndone == seq) return
        undoInFlight = seq
        val me = this
        m.command("undo_auto_action", null) { ok, e ->
            me.undoInFlight = -1L
            if (ok) { me.autoUndone = seq; me.flash("Annullato: il paziente torna com'era prima dell'esito") }
            else if (e?.contains("nothing to undo") == true && me.autoUndone != seq) { me.autoUndone = seq; me.flash("Troppo tardi: i 5 s per annullare sono passati") }
            me.refresh()
        }
    }

    private fun buildEditor() {
        for (v in edBody.subviews.toList()) v.removeFromSuperview()
        rows.clear(); rhythmBtn.clear(); nibpInfo = null; quickBtns.clear()
        onsetBand = null; onsetTitleL = null; onsetTextL = null; onsetBar = null; onsetSkipBtn = null; vfRow = null; vfBtns.clear(); vfInfo1 = null; vfInfo2 = null   // ANDROID (insorgenza 22/11)
        var quickY = -1.0
        val W = edBody.bounds.width
        val titles = mapOf("rhythm" to "Ritmo e frequenza", "hr" to "Ritmo e frequenza", "bp" to "Pressione arteriosa", "spo2" to "Saturazione", "resp" to "Respiro", "exam" to "Esame clinico")
        val heart = sel == "rhythm" || sel == "hr"
        updaters.clear()
        if (sel == "defib" || sel == "monitor") { buildSettings(); edBuilt = sel; return }
        if (sel == "sala") { buildSala(); edBuilt = sel; return }                 // ANDROID (sala 22/11)
        ecgPanel = null; ecgNavBtn = null; ecgNowL = null; ecgNormBtn = null       // ANDROID (regia morfologie ECG 22/11)
        if (sel == "ecg") { buildEcg(); edBuilt = sel; return }
        edTitle.text = titles[sel]
        run {
            // ANDROID (21/11, richiesta utente): nella regia DAE niente regolazione fine della FC: solo i ritmi
            val keys = if (daeMode == true && heart) emptyList() else TileParams(sel)
            // ANDROID (BugFixController 1–2): testi dei due modi «Subito» (10 s) e «Prepara»
            // ANDROID (funzioni 21/11): «Subito» senza numero (la transizione è di 20 s, non dichiarata)
            edSub.text = if (sel == "exam") "Temperatura e refill arrivano al valore senza transizione (in «Prepara» quando premi «Applica»). Il refill lo valuta il partecipante toccando il monitor, o lo fai valutare tu."
                         else (if (heart && daeMode == true) "Regia DAE: scegli il ritmo; la frequenza la decide il ritmo. I ritmi di arresto partono sempre subito."
                         else if (heart) fmt("«Subito»: la FC arriva al valore gradualmente in %d s. I ritmi di arresto, e l'uscita da un arresto, cambiano di colpo. In «Prepara» il ritmo aspetta «Applica», ma i ritmi di arresto partono sempre subito: in «Prepara» lo dice l'etichetta rossa SUBITO sul tasto. In arresto il numero grande della tessera è la sigla del ritmo; la FC impostata vale al ROSC. Righello della FC: trascinalo o tocca una tacca con nome («Bradi 40», «Normale 80», «Tachi 130»); in FV al suo posto c'è l'ampiezza della FV (− e + regolano la FC al ROSC).", kSubitoSec)
                               else fmt("Righello: trascinalo (scorre sotto l'indice fisso al centro e si ferma sul passo), oppure tocca una tacca con nome («Normale 80») o − / + (tenere premuto per scorrere). Fascia colorata: zone cliniche (verde norma, ambra fuori norma, rosso grave); linee rosse tratteggiate: limiti di allarme del monitor. «Subito»: arriva al valore gradualmente in %d s; «Prepara»: aspetta «Applica» (riquadro tratteggiato IN ATTESA). Durante una transizione: l'indice colorato è l'arrivo, la linea bianca «adesso» il valore di adesso («79 →» accanto al numero).", kSubitoSec))
            var y = 0.0
            val me = this
            // ANDROID (righello a zone 22/11, richiesta utente): la barra «Morfologia ECG» è IN CIMA al pannello Ritmo, fra
            // l'intestazione della FC («Frequenza cardiaca … 82 bpm») e il suo righello: una riga sola (38 punti) con la morfologia
            // di adesso e «Modifica ›» (navigazione: contorno azzurro e «›»), IN ATTESA in «Prepara». Nella regia DAE (senza la FC)
            // sta in cima ai ritmi.
            fun ecgBar(by: Double) {
                val nb = CtlButton.buttonWithTitle("Morfologia ECG: normale  ·  Modifica ›", CtlButtonQuiet, null, CGRectMake(0, by, W, 36), null)
                nb.titleLabel.font = UIFont.boldSystemFontOfSize(14); nb.maxLines = 1; nb.accessibilityIdentifier = "ecg.open"
                nb.layer.borderColor = CTL_TINT.colorWithAlphaComponent(0.55).CGColor
                nb.onTap = { sel = "ecg"; refresh() }
                edBody.addSubview(nb); ecgNavBtn = nb
            }
            for (k in keys) {
                val d = PDef(k)
                // ANDROID (righello a zone 22/11): nome e valore, tasti rapidi «nome / valore», righello a zone fra − e +
                val hrRow = heart && k == "heart_rate_bpm"
                val acc = if (hrRow) 42.0 else 0.0; val rh = if (hrRow) 62.0 else 64.0
                val hasPre = CtlRulerSpec(k, 0.0, 1.0, 1.0, 0).presets.isNotEmpty()
                val rH = CtlParamRow.height(acc, hasPre, rh)
                val r = CtlParamRow(CGRectMake(0, y, W, rH), d["title"] as String, d["unit"] as String, PColor(k),
                                    DblOf(d["min"]), DblOf(d["max"]), DblOf(d["step"]), IntOf(d["dec"]), k, acc, rh)
                r.accessibilityIdentifier = "row.$k"
                r.onChange = { v, final -> me.param(k, v, final) }
                // PA: i tasti rapidi sono le coppie di prima (Shock 70/40 …): sistolica e diastolica insieme
                if (k == "systolic_mmhg") r.onPreset = { p -> me.setBP(p.value.toInt(), p.value2.toInt()) }
                edBody.addSubview(r); rows[k] = r
                if (hrRow) { ecgBar(y + 52); quickY = y + r.presetsY }
                y += rH + (if (heart) 6 else 14)
            }
            if (sel == "bp") {
                val box = UIView(CGRectMake(0, y, W, 64)); box.backgroundColor = CTL_CARD2; box.layer.cornerRadius = 10.0; edBody.addSubview(box)
                val ni = CtlLabel(CGRectMake(14, 0, W - 180, 64), "", 14.0, false, CTL_INK); ni.numberOfLines = 3; box.addSubview(ni); nibpInfo = ni
                val mb = CtlButton.buttonWithTitle("Misura ora", CtlButtonFilled, CTL_TINT, CGRectMake(W - 150, 8, 138, 48)) { measureNibp(it) }
                mb.accessibilityIdentifier = "bp.measure"; box.addSubview(mb)
            }
            if (heart) {                 // ritmo e frequenza vanno insieme: sotto la FC la scelta del ritmo
                if (keys.isEmpty()) { ecgBar(0.0); y = 44.0 }
                // ANDROID (affordance 22/11, CTL-01): tasti dei ritmi alti 56 punti (erano 50); ANDROID (righello a zone 22/11): 52 con la
                // FC regolabile (sopra ci sono la morfologia, i tasti rapidi e il righello: il pannello resta senza tagli in fondo)
                val bw = (W - 16) / 3; val bh = if (keys.isEmpty()) 56.0 else 52.0; var i = 0
                val top = y; val lab = 17.0
                edBody.addSubview(CtlLabel(CGRectMake(0, top, W, 15), "SINUSALI E SOPRAVENTRICOLARI", 11.0, true, CTL_INK2))
                edBody.addSubview(CtlLabel(CGRectMake(0, top + lab + 2 * (bh + 6), W, 15), "VENTRICOLARI E ARRESTO", 11.0, true, CTL_INK2))
                for (r in RhythmOrder()) {
                    val row = if (i < 4) i / 3 else 2 + (i - 4) / 3; val col = if (i < 4) i % 3 else (i - 4) % 3
                    val by = top + lab + row * (bh + 6) + (if (i >= 4) lab else 0.0)
                    val b = CtlButton.buttonWithTitle(CtlRhythmName(r), CtlButtonQuiet, null, CGRectMake(col * (bw + 8), by, bw, bh)) { rhythmTapped(it) }
                    b.titleLabel.font = UIFont.boldSystemFontOfSize(14); b.accessibilityIdentifier = "rhythm.$r"
                    edBody.addSubview(b); rhythmBtn[r] = b; i++
                }
                buildOnsetBand(top + lab + (bh + 6), bw, bh, W)
                // la riga dell'ampiezza della FV: al posto dei tasti rapidi della FC (in FV la FC impostata vale al ROSC: il righello
                // resta); nella regia DAE (senza la riga della FC) sotto i ritmi
                if (quickY >= 0) buildVfRow(0.0, quickY, W, 44.0) else buildVfRow(0.0, top + 2 * lab + 4 * (bh + 6) + 6, W, 48.0)
            }
            if (sel == "exam") {      // il partecipante chiede il refill a voce: lo fai misurare tu
                val box = UIView(CGRectMake(0, y, W, 62)); box.backgroundColor = CTL_CARD2; box.layer.cornerRadius = 10.0; edBody.addSubview(box)
                box.addSubview(CtlLabel(CGRectMake(14, 8, W - 210, 22), "Refill chiesto a voce?", 16.0, false, CTL_INK))
                val d = CtlLabel(CGRectMake(14, 30, W - 210, 26), "Sul monitor parte la valutazione, dopo 3 s compare il tempo.", 11.5, false, CTL_INK3); d.numberOfLines = 2; box.addSubview(d)
                val b = CtlButton.buttonWithTitle("Fai valutare", CtlButtonFilled, CTL_TEMP, CGRectMake(W - 184, 10, 172, 42)) { assessCrt() }
                b.titleLabel.font = UIFont.boldSystemFontOfSize(15); b.maxLines = 1; b.accessibilityIdentifier = "exam.assess"; box.addSubview(b)
                y += 70
            }
            if (sel == "spo2") {      // qualità del segnale: quello che il saturimetro riesce a leggere
                val opts = listOf(Triple("Bassa perfusione / artefatti", "«?» alternato al valore e onda disturbata, anche con pressione normale", "spo2.lowperf"),
                                  Triple("Segnale assente (sonda che non legge)", "il monitor non mostra il valore anche con la sonda applicata", "spo2.nosignal"))
                for (o in opts) {
                    val box = UIView(CGRectMake(0, y, W, 62)); box.backgroundColor = CTL_CARD2; box.layer.cornerRadius = 10.0; edBody.addSubview(box)
                    box.addSubview(CtlLabel(CGRectMake(14, 8, W - 90, 22), o.first, 16.0, false, CTL_INK))
                    val d = CtlLabel(CGRectMake(14, 30, W - 90, 26), o.second, 11.5, false, CTL_INK3); d.numberOfLines = 2; box.addSubview(d)
                    val swi = UISwitch(CGRectMake(W - 65, 15, 51, 31)); swi.onTintColor = CTL_ORANGE; swi.accessibilityIdentifier = o.third
                    swi.addTarget(UIControlEventValueChanged) { spo2Option(it as UISwitch) }
                    box.addSubview(swi); sw[o.third] = swi
                    y += 70
                }
            }
        }
        edBuilt = sel
    }

    private fun refreshEditor() {
        if (sel != "sala" && sala.videoOn) sala.stopVideo()       // ANDROID (sala 22/11): il video solo con il pannello a schermo (l'audio resta)
        styleSala()
        if (edBuilt != sel) buildEditor()
        for (u in ArrayList(updaters)) u()
        refreshOutcome()
        // bracciale in misura sullo stesso braccio di SpO2 o arteria: il monitor perde il segnale, la regia lo dice
        val s0 = m.state
        if (BoolOf(s0?.get("spo2_cuff_occluded")) && !occlSaid) { occlSaid = true; flash("Bracciale sullo stesso braccio della SpO₂: segnale perso durante la misura") }
        else if (BoolOf(s0?.get("art_cuff_occluded")) && !occlSaid) { occlSaid = true; flash("Bracciale sullo stesso braccio dell'arteria: curva smorzata durante la misura") }
        else if (!BoolOf(s0?.get("spo2_cuff_occluded")) && !BoolOf(s0?.get("art_cuff_occluded"))) occlSaid = false
        val st = m.state ?: emptyMap()
        val tr = DictOf(st["transitions"])
        for (k in rows.keys) {
            val t = tr[k] as? Map<*, *>
            // ANDROID (fix analisi 22/11, BUG-01): la riga mostra (e «+/−» parte da) il valore di arrivo; la nota dice il valore di adesso
            val p = pending[k]; val v: Number? = p ?: (t?.get("to") as? Number) ?: valueFor(k)
            if (v != null) rows[k]!!.showValue(v.toDouble(), p != null)
            // ANDROID (numerici realistici 22/11): FC e SpO₂ con «sul monitor ora: N» (numero a schermo, con il ritardo vero) in una rampa
            val mn = monNote(k, st, t != null)
            if (p != null) rows[k]!!.setNote(fmt("in attesa · ora %s", Num(valueFor(k), IntOf(PDef(k)["dec"]))))
            else if (t != null) rows[k]!!.setNote(rampNote(k, t, mn))
            else rows[k]!!.setNote(mn)
            // ANDROID (affordance 22/11, CTL-07): «adesso → arrivo» con la barra di avanzamento e la tacca sul cursore
            val nowV = valueFor(k)?.toDouble()
            if (p == null && t != null && nowV != null) {
                // il monitor dice solo quanto manca: la durata totale è il primo «mancano» visto per questo arrivo
                val rem = DblOf(t["remaining_s"]); val to = Desc(t["to"]); val seen = trSeen[k]
                val dur = if (seen == null || seen.first != to || rem > seen.second) rem.also { trSeen[k] = Pair(to, rem) } else seen.second
                rows[k]!!.setTransition(nowV, if (dur > 0) 1 - rem / dur else null)
            } else rows[k]!!.setTransition(null, null)
        }
        // ANDROID (righello a zone 22/11): la tacca con nome uguale al valore di arrivo la segna il righello («●», pieno tenue);
        // qui i limiti di allarme del monitor (alarm_limits) e il lucchetto della sola osservazione
        run {
            val lim = DictOf(st["alarm_limits"])
            fun n(x: String): Double? = (lim[x] as? Number)?.toDouble()
            val lk = mapOf("heart_rate_bpm" to Pair("hr_low", "hr_high"), "spo2_percent" to Pair("spo2_low", ""), "systolic_mmhg" to Pair("sys_low", "sys_high"),
                           "respiratory_rate_rpm" to Pair("rr_low", "rr_high"), "etco2_mmhg" to Pair("etco2_low", "etco2_high"))
            for ((k, r) in rows) { val p = lk[k]; r.setLimits(p?.let { n(it.first) }, p?.let { n(it.second) }); r.setLocked(observing) }
        }
        val cur = st["rhythm"] as? String
        // ANDROID (affordance 22/11, X-02): senza polso la FC impostata vale al ROSC
        rows["heart_rate_bpm"]?.setTitle(if (st.containsKey("pulse_present") && !BoolOf(st["pulse_present"])) "FC al ROSC" else "Frequenza cardiaca")
        val onsetTo = (st["rhythm_onset"] as? Map<*, *>)?.get("to") as? String        // ANDROID (insorgenza 22/11)
        for (r in rhythmBtn.keys) {
            val b = rhythmBtn[r]!!
            val on = r == cur; val pend = r == pendingRhythm && !on; val arr = r == onsetTo
            // ANDROID (affordance 22/11, CTL-08): il ritmo attuale è pieno nel colore del gruppo (verde con polso, rosso in arresto)
            // con «●»; il blu resta per le azioni. CTL-05: in «Prepara» i tasti che partono comunque al tocco (ritmi di arresto e,
            // durante un arresto, tutti) portano l'etichetta rossa SUBITO e il bordo rosso. In attesa: etichetta gialla IN ATTESA.
            // ANDROID (insorgenza 22/11): il ritmo che sta arrivando (preludio in corso) ha il bordo e l'etichetta «IN ARRIVO» nel colore
            // del suo gruppo (rosso arresto, verde con polso)
            val immediate = prepare && !on && !arr && (isArrest(r) || isArrest(cur))
            val arrC = if (isArrest(r)) CTL_RED else CTL_GREEN
            b.setStyle(if (on) CtlButtonFilled else CtlButtonQuiet, if (on) (if (isArrest(r)) CTL_RED else CTL_GREEN) else CTL_TINT)
            b.layer.borderWidth = if (on) 0.0 else (if (immediate || pend || arr) 2.0 else 1.0)
            b.layer.borderColor = (if (arr) arrC else if (immediate) CTL_RED.colorWithAlphaComponent(0.85) else (if (pend) CTL_YELLOW else UIColor.colorWithWhite(1, 0.08))).CGColor
            val tt = (if (on) "● " else "") + CtlRhythmName(r)
            if (b.titleForState(UIControlStateNormal) != tt) b.setTitle(tt, UIControlStateNormal)
            if (observing) b.setTag(null, CTL_AMBER, "lock") else if (arr) b.setTag("IN ARRIVO", arrC) else if (immediate) b.setTag("SUBITO", CTL_RED) else if (pend) b.setTag("IN ATTESA", CTL_YELLOW) else b.setTag(null, null)
        }
        refreshOnset(st, cur)
        refreshEcg(st)                                    // ANDROID (regia morfologie ECG 22/11)
        nibpInfo?.let { ni ->
            val last = if (st["nibp_last_systolic_mmhg"] is Number) fmt("%s/%s", Desc(st["nibp_last_systolic_mmhg"]), Desc(st["nibp_last_diastolic_mmhg"])) else "nessuna"
            val stt = mapOf("idle" to "", "measuring" to " · misura in corso", "complete" to "", "error" to " · misura non riuscita")
            ni.text = fmt("Ultima NIBP a monitor: %s%s%s", last, stt[st["nibp_status"] ?: ""] ?: "",
                          if (BoolOf(DictOf(st["sensors"])["nibp"])) "" else "\nBracciale non applicato")
        }
    }

    // MARK: - Pannelli a impostazioni: Defibrillatore, Monitor e allarmi

    // Righe di impostazione dentro un elenco che scorre. Ogni riga registra un blocco che la tiene allineata allo stato
    // del monitor (saltato per un attimo dopo un tocco, così il valore non "torna indietro" prima della risposta).
    private fun justTouched(key: String): Boolean = CACurrentMediaTime() - (setTouched[key] ?: 0.0) < 1.5
    private fun touched(key: String) { setTouched[key] = CACurrentMediaTime() }

    private fun header(t: String, v: UIView, y: Double): Double {
        v.addSubview(CtlLabel(CGRectMake(0, y + 6, v.bounds.width, 16), t, 11.0, true, CTL_INK3)); return y + 28
    }
    // reserve = larghezza occupata a destra dai comandi della riga: il testo si ferma prima
    private fun rowIn(v: UIView, y: Double, title: String, sub: String?, reserve: Double = 232.0): UIView {
        val W = v.bounds.width
        val r = UIView(CGRectMake(0, y, W, 50)); r.backgroundColor = CTL_CARD2; r.layer.cornerRadius = 10.0
        val t = CtlLabel(CGRectMake(14, if (sub != null) 6 else 0, W - 14 - reserve, if (sub != null) 22 else 50), title, 15.0, false, CTL_INK); t.adjustsFontSizeToFitWidth = true; t.minimumScaleFactor = 0.75; r.addSubview(t)
        if (sub != null) { val d = CtlLabel(CGRectMake(14, 27, W - 14 - reserve, 17), sub, 11.0, false, CTL_INK3); d.adjustsFontSizeToFitWidth = true; d.minimumScaleFactor = 0.8; r.addSubview(d) }
        v.addSubview(r)
        return r
    }
    // numero con − e +: get legge il valore dallo stato, set manda il comando
    // ANDROID (funzioni 21/11): avail = la funzione c'è sul monitor collegato; se no la riga è velata e non si tocca
    private fun veil(r: UIView, avail: (() -> Boolean)?) {
        if (avail == null) return
        updaters.add { val ok = avail(); val a = if (ok) 1f else 0.38f; if (r.alpha != a) r.alpha = a; if (r.userInteractionEnabled != ok) r.userInteractionEnabled = ok }
    }
    private fun stepRow(title: String, sub: String?, key: String, v: UIView, y: Double, step: Int, min: Int, max: Int, unit: String,
                        get: () -> Any?, set: (Int) -> Unit, avail: (() -> Boolean)? = null): Double {
        val r = rowIn(v, y, title, sub); val W = r.bounds.width; veil(r, avail)
        val vl = CtlLabel(CGRectMake(W - 168, 0, 74, 50), "--", 19.0, true, CTL_INK); vl.font = CtlDigits(19.0, true); vl.textAlignment = NSTextAlignmentCenter; vl.adjustsFontSizeToFitWidth = true; vl.minimumScaleFactor = 0.6; r.addSubview(vl)
        val me = this; var cur = 0
        for (dir in listOf(-1, 1)) {
            val b = CtlButton.buttonWithTitle(if (dir < 0) "−" else "+", CtlButtonQuiet, null, CGRectMake(if (dir < 0) W - 214 else W - 52, 6, 44, 38), null)
            b.backgroundColor = CTL_CARD; b.titleLabel.font = UIFont.systemFontOfSize(24); b.accessibilityIdentifier = fmt("set.%s.%s", key, if (dir < 0) "meno" else "piu")
            b.onTap = { val n = max(min, min(max, cur + dir * step)); if (n != cur) { cur = n; me.touched(key); vl.text = fmt("%d%s", n, unit); set(n) } }
            r.addSubview(b)
        }
        updaters.add { if (!me.justTouched(key)) { val n = get(); if (n != null) { cur = IntOf(n); val t = fmt("%d%s", cur, unit); if (vl.text != t) vl.text = t } } }
        return y + 56
    }
    private fun switchRow(title: String, sub: String?, key: String, v: UIView, y: Double, color: UIColor?, get: () -> Boolean, set: (Boolean) -> Unit,
                          avail: (() -> Boolean)? = null): Double {
        val r = rowIn(v, y, title, sub, 80.0); val W = r.bounds.width; veil(r, avail)
        val s = UISwitch(CGRectMake(W - 65, 9, 51, 31)); s.onTintColor = color ?: CTL_TINT; s.accessibilityIdentifier = "set.$key"
        r.addSubview(s)
        val me = this
        val proxy = CtlButton.buttonWithTitle("", CtlButtonQuiet, null, CGRectZero, null); proxy.hidden = true; r.addSubview(proxy)
        proxy.onTap = { me.touched(key); set(s.isOn) }
        s.addTarget(UIControlEventValueChanged) { proxy.onTap?.invoke() }      // [sw addTarget:proxy action:@selector(fire)]
        updaters.add { if (!me.justTouched(key)) { val on = get(); if (s.isOn != on) s.setOn(on, true) } }
        return y + 56
    }
    private fun segRow(title: String?, key: String, items: List<String>, v: UIView, y0: Double, get: () -> Int, set: (Int) -> Unit,
                       avail: (() -> Boolean)? = null): Double {
        var y = y0
        val W = v.bounds.width
        if (title != null) { val tl = CtlLabel(CGRectMake(0, y, W, 20), title, 14.0, false, CTL_INK); v.addSubview(tl); veil(tl, avail); y += 24 }
        val sg = UISegmentedControl(items); sg.frame = CGRectMake(0, y, W, 34); sg.tintColor = CTL_TINT; sg.accessibilityIdentifier = "set.$key"
        v.addSubview(sg); veil(sg, avail)
        val me = this
        val proxy = CtlButton.buttonWithTitle("", CtlButtonQuiet, null, CGRectZero, null); proxy.hidden = true; v.addSubview(proxy)
        proxy.onTap = { me.touched(key); set(sg.selectedSegmentIndex) }
        sg.addTarget(UIControlEventValueChanged) { proxy.onTap?.invoke() }
        updaters.add { if (!me.justTouched(key)) { val i = get(); if (sg.selectedSegmentIndex != i) sg.selectedSegmentIndex = i } }
        return y + 44
    }

    // ANDROID (proposta A8, richiesta utente 21/11): pannello «DAE» della regia DAE. In alto ciò che serve durante lo scenario:
    // accensione, i problemi che l'istruttore fa capitare (movimento durante l'analisi, piastre con contatto scarso o staccate),
    // poi dopo quante scariche il ritmo si converte e in che cosa, la RCP fra le analisi e le istruzioni vocali.
    private fun buildDaeRegia(v0: UIView, y0: Double, df: () -> Map<String, Any?>, cfg: () -> Map<String, Any?>, setCfg: (String, Any?) -> Unit): Double {
        val me = this; val m = this.m; var y = y0; var v = v0; val W = v0.bounds.width
        edTitle.text = "DAE"
        edSub.text = "Sul monitor c'è un DAE: da qui fai capitare i problemi tipici e decidi quando la scarica converte il ritmo."
        y = header("DISPOSITIVO DEL PARTECIPANTE", v, y)
        val pb = CtlButton.buttonWithTitle("Accendi il DAE", CtlButtonTinted, CTL_GREEN, CGRectMake(0, y, W, 46), null)
        pb.titleLabel.font = UIFont.boldSystemFontOfSize(15); pb.maxLines = 1; pb.accessibilityIdentifier = "dae.power"; pb.setIcon("power", false)
        pb.onTap = { me.powerTapped() }; v.addSubview(pb)
        updaters.add { me.stylePower(pb) }
        y += 54
        y = faultSection(v, y)            // ANDROID (guasti 22/11): malfunzionamenti del dispositivo (chiusa di serie)
        // ANDROID (21/11, richiesta utente): qualità delle compressioni che l'istruttore vede; il DAE con feedback la commenta
        // («Spingere più forte», «Spingere più velocemente»…, poi «Compressioni efficaci»)
        y = cprQualityRows(v, y)
        y = header("PROBLEMI DURANTE L'USO", v, y)
        // movimento / tocco: interrompe l'analisi («Movimento rilevato»), qualunque sia il ritmo
        val bw = (W - 8) / 2
        val t3 = CtlButton.buttonWithTitle("Tocco del paziente · 3 s", CtlButtonTinted, CTL_ORANGE, CGRectMake(0, y, bw, 46), null)
        val t10 = CtlButton.buttonWithTitle("Movimento · 10 s", CtlButtonTinted, CTL_ORANGE, CGRectMake(bw + 8, y, bw, 46), null)
        for ((b, sec) in listOf(Pair(t3, 3), Pair(t10, 10))) {
            b.titleLabel.font = UIFont.boldSystemFontOfSize(14); b.maxLines = 1; b.accessibilityIdentifier = "dae.touch.$sec"; b.setIcon("tap", false)
            b.onTap = { m.command("patient_touch", sec); me.flash(fmt("Movimento del paziente per %d s: il DAE interrompe l'analisi", sec)) }
            v.addSubview(b)
        }
        y += 52
        val note = CtlLabel(CGRectMake(0, y, W, 16), "Durante l'analisi: «Movimento rilevato». Anche la RCP in corso disturba l'analisi.", 11.5, false, CTL_INK3)
        note.numberOfLines = 1; note.adjustsFontSizeToFitWidth = true; note.minimumScaleFactor = 0.8; v.addSubview(note)
        y += 22
        // ANDROID (funzioni 21/11): ambulanza in movimento (vehicle_motion): artefatti con qualsiasi ritmo, «Movimento rilevato» nel DAE
        y = switchRow("Ambulanza in movimento", "artefatti con qualsiasi ritmo; il DAE ferma analisi e carica", "vehicle_motion", v, y, CTL_ORANGE,
                      { BoolOf(m.state?.get("vehicle_motion")) }, { on -> m.command("vehicle_motion", on) }, { m.state?.containsKey("vehicle_motion") == true })
        y = switchRow("Placche con contatto scarso", "«Controllare le piastre»: il DAE non analizza finché non le sistemi (o le riapplicano)", "pads_poor", v, y, CTL_ORANGE,
                      { BoolOf(df()["pads_poor_contact"]) }, { on -> m.command("set_pads_contact", if (on) "poor" else "good") })
        // ANDROID (funzioni 21/11): ora il cavo è un comando a parte (set_pads_cable): questo interruttore dice solo se le placche
        // sono SUL PAZIENTE (pads_applied; con il monitor vecchio pads_connected, come prima)
        y = switchRow("Placche collegate", "spento = placche tolte dal paziente: «Applicare le piastre»", "pads_on", v, y, null,
                      { BoolOf(df()["pads_applied"] ?: df()["pads_connected"]) }, { on -> m.command("set_sensor", mapOf("sensor" to "pads", "connected" to on)) })
        y = switchRow("Cavo delle placche collegato", "spento = placche sul paziente ma cavo staccato: «Collegare il cavo»", "pads_cable", v, y, CTL_ORANGE,
                      { df()["pads_cable"] != "disconnected" }, { on -> m.command("set_pads_cable", if (on) "connected" else "disconnected") }, { df().containsKey("pads_cable") })
        // ANDROID (funzioni 21/11): paziente pediatrico: placche pediatriche o tasto Bambino del DAE (energia pediatrica)
        y = header("PAZIENTE PEDIATRICO", v, y)
        y = segRow(null, "pads_type", listOf("Placche per adulti", "Placche pediatriche"), v, y,
                   { if (df()["pads_type"] == "pediatric") 1 else 0 }, { i -> m.command("set_pads_type", if (i == 1) "pediatric" else "adult") }, { df().containsKey("pads_type") })
        y = switchRow("Modalità bambino del DAE", "come il tasto pediatrico del dispositivo", "aed_child", v, y, null,
                      { BoolOf(df()["aed_child_mode"]) }, { on -> m.command("set_aed_child_mode", on) }, { df().containsKey("aed_child_mode") })
        y = stepRow("Energia pediatrica", "con placche pediatriche o modalità bambino", "aed_ped_energy_j", v, y, 10, 10, 200, " J",
                    { cfg()["aed_ped_energy_j"] }, { n -> setCfg("aed_ped_energy_j", n) }, { cfg().containsKey("aed_ped_energy_j") })
        y = header("QUANDO LA SCARICA CONVERTE", v, y)
        y = segRow(null, "outcome_mode", listOf("Decido io dopo ogni scarica", "Regole automatiche"), v, y,
                   { if (cfg()["outcome_mode"] == "auto") 1 else 0 }, { i -> setCfg("outcome_mode", if (i != 0) "auto" else "instructor") })
        y = stepRow("Scariche per convertire", "FV / TV senza polso · 0 = mai (con «Regole automatiche»)", "shocks_to_convert", v, y, 1, 0, 9, "",
                    { cfg()["shocks_to_convert"] }, { n -> setCfg("shocks_to_convert", n) })
        val r = rowIn(v, y, "Ritmo dopo la scarica efficace", null)
        val rb = CtlButton.buttonWithTitle("--", CtlButtonTinted, CTL_TINT, CGRectMake(r.bounds.width - 196, 6, 188, 38), null)
        rb.titleLabel.font = UIFont.boldSystemFontOfSize(13); rb.maxLines = 1; rb.accessibilityIdentifier = "dae.post_shock_rhythm"; r.addSubview(rb)
        rb.onTap = { me.pickRhythm("Ritmo dopo la scarica efficace", rb) { rh -> setCfg("post_shock_rhythm", rh) } }
        updaters.add { val t = CtlRhythmName(cfg()["post_shock_rhythm"] as? String); if (rb.titleForState(UIControlStateNormal) != t) rb.setTitle(t, UIControlStateNormal) }
        y += 56
        // ANDROID (funzioni 21/11): sequenza degli esiti, solo con «Regole automatiche»; il resto del pannello scorre sotto
        v = seqFold(v, y, df, cfg, setCfg); y = 0.0
        y = header("CICLO DEL DAE", v, y)
        y = stepRow("RCP fra le analisi", null, "aed_cpr_s", v, y, 30, 30, 300, " s", { cfg()["aed_cpr_s"] }, { n -> setCfg("aed_cpr_s", n) })
        y = switchRow("Istruzioni vocali", null, "voice_prompts", v, y, null, { BoolOf(cfg()["voice_prompts"]) }, { on -> setCfg("voice_prompts", on) })
        y = realismRow(v, y, cfg, setCfg)
        return y
    }

    private fun cprQualityRows(v: UIView, y0: Double): Double {
        val m = this.m; var y = y0
        y = header("QUALITÀ DELLA RCP (guardi e scegli)", v, y)
        y = segRow("Frequenza", "cpr_rate", listOf("Lenta", "Corretta", "Veloce"), v, y,
                   { when (m.state?.get("cpr_rate_quality")) { "slow" -> 0; "fast" -> 2; else -> 1 } },
                   { i -> m.command("set_cpr_quality", mapOf("rate" to listOf("slow", "ok", "fast")[i])) })
        y = segRow("Profondità", "cpr_depth", listOf("Superficiale", "Corretta", "Troppo profonda"), v, y,
                   { when (m.state?.get("cpr_depth_quality")) { "shallow" -> 0; "deep" -> 2; else -> 1 } },
                   { i -> m.command("set_cpr_quality", mapOf("depth" to listOf("shallow", "ok", "deep")[i])) })
        return y
    }
    private fun realismRow(v: UIView, y: Double, cfg: () -> Map<String, Any?>, setCfg: (String, Any?) -> Unit): Double =
        switchRow("Comportamento fedele al dispositivo", "es. in DAE: «Premere ANALIZZA» ogni 20 s; spento = come prima", "device_realism", v, y, null, { BoolOf(cfg()["device_realism"]) }, { on -> setCfg("device_realism", on) }, { cfg().containsKey("device_realism") })

    // MARK: ANDROID (guasti 22/11) — malfunzionamenti simulati del dispositivo (NUOVE_FUNZIONI_CUORE.md «Malfunzionamenti del
    // dispositivo», RICERCA_MALFUNZIONAMENTI_DAE.md 4.6). Sezione chiusa di serie, nella regia DAE e in «Terapia elettrica»,
    // usabile anche a dispositivo spento (è lo scopo principale: il controllo giornaliero del DAE). Tutto viene dallo stato del
    // monitor (device_faults, device_readiness…): con più controller tutti vedono lo stesso elenco.
    private var faultsOpen = false; private var faultsMore = false; private var faultClearArmed = 0.0
    private val faultLocal = HashMap<String, Boolean>()
    private fun faultList(): List<String> = ((m.state?.get("device_faults") as? List<*>) ?: emptyList<Any?>()).map { it.toString() }
    private fun faultOn(kind: String): Boolean = if (justTouched("fault.$kind")) (faultLocal[kind] ?: false) else faultList().contains(kind)
    private fun faultSupported(kind: String): Boolean { val l = m.state?.get("device_faults_supported") as? List<*> ?: return true; return l.any { it == kind } }
    private fun faultsAvail(): Boolean = m.state?.containsKey("device_faults") == true
    private fun faultLayout(): String = (m.state?.get("layout") as? String) ?: ""
    /** si ricostruisce il pannello (sezione aperta / chiusa) restando dove si era nello scorrimento */
    private fun rebuildKeepingScroll() {
        val off = (edBody.subviews.firstOrNull { it is UIScrollView } as? UIScrollView)?.contentOffset?.y ?: 0.0
        edBuilt = null; refresh()
        val sc = edBody.subviews.firstOrNull { it is UIScrollView } as? UIScrollView ?: return
        sc.setContentOffset(CGPointMake(0, max(0.0, min(off, sc.contentSize.height - sc.bounds.height))), false)
    }
    private fun faultFoldRow(v: UIView, y: Double, title: String, id: String, open: Boolean, sub: () -> String, color: () -> UIColor, tap: () -> Unit): Double {
        val W = v.bounds.width
        val row = UIControl(CGRectMake(0, y, W, 54)); row.backgroundColor = CTL_CARD2; row.layer.cornerRadius = 10.0; row.accessibilityIdentifier = id
        val bar = UIView(CGRectMake(0, 10, 4, 34)); bar.layer.cornerRadius = 2.0; bar.userInteractionEnabled = false; row.addSubview(bar)
        val t = CtlLabel(CGRectMake(14, 6, W - 14 - 48, 22), title, 15.0, true, CTL_INK); t.adjustsFontSizeToFitWidth = true; t.minimumScaleFactor = 0.8; t.userInteractionEnabled = false; row.addSubview(t)
        val d = CtlLabel(CGRectMake(14, 29, W - 14 - 48, 17), "", 11.0, false, CTL_INK3); d.adjustsFontSizeToFitWidth = true; d.minimumScaleFactor = 0.8; d.userInteractionEnabled = false; row.addSubview(d)
        val ch = CtlLabel(CGRectMake(W - 42, 0, 30, 54), if (open) "▾" else "▸", 18.0, true, CTL_INK2); ch.textAlignment = NSTextAlignmentCenter; ch.userInteractionEnabled = false; row.addSubview(ch)
        row.addTarget(UIControlEventTouchUpInside) { tap() }
        v.addSubview(row)
        updaters.add { val s = sub(); if (d.text != s) d.text = s; bar.backgroundColor = color() }
        return y + 60
    }
    private fun faultSection(v: UIView, y0: Double): Double {
        val me = this; val m = this.m; val W = v.bounds.width
        var y = header("MALFUNZIONAMENTI (anche a dispositivo spento)", v, y0)
        val readiness: () -> String = { (m.state?.get("device_readiness") as? String) ?: "ready" }
        y = faultFoldRow(v, y, "Malfunzionamenti del dispositivo", "faults.fold", faultsOpen, {
            if (!me.faultsAvail()) "Questo monitor non li ha ancora: aggiorna similFranz Monitor"
            else {
                val l = me.faultList()
                val sp = "spia: " + FaultReadinessWord(readiness(), BoolOf(m.state?.get("device_self_test"))).lowercase(Locale.ITALIAN)
                if (l.isEmpty()) "nessuno · $sp" + (if (me.faultsOpen) "" else " · tocca per aprire")
                else fmt("%d %s: %s · %s", l.size, if (l.size == 1) "attivo" else "attivi", l.joinToString(", ") { FaultName(it) }, sp)
            }
        }, { if (me.faultList().isEmpty()) CTL_GREEN else FaultReadinessColor(readiness()) }) {
            if (me.faultsAvail() || me.faultsOpen) { me.faultsOpen = !me.faultsOpen; me.rebuildKeepingScroll() }
            else me.flash("Questo monitor non ha ancora i malfunzionamenti simulati: aggiorna similFranz Monitor")
        }
        if (!faultsOpen) return y
        val first = v.subviews.size
        // spia replicata e che cosa vede il partecipante nel layout attivo
        val box = UIView(CGRectMake(0, y, W, 86)); box.backgroundColor = CTL_CARD2; box.layer.cornerRadius = 10.0; box.accessibilityIdentifier = "faults.status"; v.addSubview(box)
        val dot = UIView(CGRectMake(14, 12, 16, 16)); dot.layer.cornerRadius = 8.0; box.addSubview(dot)
        val l1 = CtlLabel(CGRectMake(38, 6, W - 52, 26), "", 16.0, true, CTL_INK); l1.adjustsFontSizeToFitWidth = true; l1.minimumScaleFactor = 0.8; box.addSubview(l1)
        val l2 = CtlLabel(CGRectMake(14, 33, W - 28, 30), "", 12.0, false, CTL_INK2); l2.numberOfLines = 2; box.addSubview(l2)
        val l3 = CtlLabel(CGRectMake(14, 64, W - 28, 16), "", 11.5, false, CTL_INK3); l3.adjustsFontSizeToFitWidth = true; l3.minimumScaleFactor = 0.8; box.addSubview(l3)
        updaters.add {
            val st = m.state ?: emptyMap(); val r = readiness(); val test = BoolOf(st["device_self_test"])
            val c = if (test) CTL_YELLOW else FaultReadinessColor(r)
            dot.backgroundColor = c; l1.textColor = c
            val t1 = "Spia: " + FaultReadinessWord(r, test) + (if (monitorOn() == true) "  ·  dispositivo acceso" else "  ·  dispositivo spento")
            if (l1.text != t1) l1.text = t1
            val t2 = (if (monitorOn() == true) "Da spento il partecipante vedrebbe: " else "Il partecipante vede: ") + FaultReadinessSeen(me.faultLayout(), r, test)
            if (l2.text != t2) l2.text = t2
            val bi = IntOf(st["fault_beep_interval_s"]); val sh = st["battery_shocks_left"] as? Number
            val parts = ArrayList<String>()
            parts.add(if (bi > 0) fmt("Bip adesso: %sogni %d s", if (BoolOf(st["fault_beep_double"])) "doppio, " else "", bi) else "Bip adesso: nessuno")
            if (sh != null) parts.add(fmt("Batteria simulata: %d %s", sh.toInt(), if (sh.toInt() == 1) "scarica rimasta" else "scariche rimaste"))
            val t3 = parts.joinToString("  ·  ")
            if (l3.text != t3) l3.text = t3
        }
        y += 92
        y = switchRow("Bip da spento", "con un malfunzionamento: bip ogni 30 s (guasto grave: doppio ogni 15 s)", "fault_beep", v, y, null,
                      { m.state?.get("device_fault_beep") != false }, { on -> m.command("set_device_fault_beep", on) })
        y = header("BATTERIA (una alla volta)", v, y)
        for (k in listOf("battery_low", "battery_replace", "battery_dead")) y = faultRow(v, y, k)
        y = header("PLACCHE", v, y)
        for (k in listOf("pads_expired", "pads_damaged", "pads_unplugged", "pads_poor_contact")) y = faultRow(v, y, k)
        y = header("DISPOSITIVO E AMBIENTE", v, y)
        for (k in listOf("maintenance", "service_required", "temperature")) y = faultRow(v, y, k)
        y += 4
        val adv = listOf("charge_fault", "shock_button_stuck", "memory_full")
        y = faultFoldRow(v, y, "Altri (avanzati)", "faults.more", faultsMore, {
            val on = adv.filter { me.faultList().contains(it) }
            if (on.isNotEmpty()) "attivo: " + on.joinToString(", ") { FaultName(it) } else "errore di carica, tasto di scarica bloccato, memoria piena"
        }, { if (adv.any { me.faultList().contains(it) }) CTL_ORANGE else CTL_INK3 }) { me.faultsMore = !me.faultsMore; me.rebuildKeepingScroll() }
        if (faultsMore) for (k in adv) y = faultRow(v, y, k)
        // «Dispositivo a posto» = clear_device_faults (risolve tutto, anche cavo e contatto), con conferma a due tocchi
        val ok = CtlButton.buttonWithTitle("Dispositivo a posto (risolve tutto)", CtlButtonTinted, CTL_GREEN, CGRectMake(0, y + 2, W, 46), null)
        ok.titleLabel.font = UIFont.boldSystemFontOfSize(15); ok.maxLines = 1; ok.accessibilityIdentifier = "faults.clear"; v.addSubview(ok)
        ok.onTap = {
            if (CACurrentMediaTime() < me.faultClearArmed) {
                me.faultClearArmed = 0.0
                for (k in SLDeviceFaultKinds) { me.faultLocal[k] = false; me.touched("fault.$k") }
                m.command("clear_device_faults", null) { done, _ -> if (done) me.flash("Dispositivo a posto: nessun malfunzionamento") }
            } else { me.faultClearArmed = CACurrentMediaTime() + 3; Uk.after(3.1) { me.refresh() } }
            me.refresh()
        }
        updaters.add {
            val armed = CACurrentMediaTime() < me.faultClearArmed; val any = me.faultList().isNotEmpty()
            val t = if (armed) "Tocca di nuovo: risolve tutti i malfunzionamenti" else (if (any) "Dispositivo a posto (risolve tutto)" else "Dispositivo a posto")
            if (ok.titleForState(UIControlStateNormal) != t) ok.setTitle(t, UIControlStateNormal)
            ok.setStyle(if (armed) CtlButtonFilled else CtlButtonTinted, CTL_GREEN)
            if (ok.isEnabled != any) ok.isEnabled = any
        }
        y += 56
        // monitor senza malfunzionamenti (vecchio): tutta la sezione velata
        // ANDROID (fix analisi 22/11, BUG-15): le righe dei singoli malfunzionamenti hanno il loro velo (non rilevato da questo
        // dispositivo): il velo di sezione, registrato dopo, le riaccendeva e restavano attivabili
        for (x in v.subviews.drop(first)) if (x.accessibilityIdentifier?.startsWith("fault.row.") != true) veil(x) { me.faultsAvail() }
        return y
    }
    // una riga per tipo: nome, che cosa succede nel layout attivo; spento = interruttore, attivo = pulsante con l'azione reale
    private fun faultRow(v: UIView, y: Double, kind: String): Double {
        val me = this; val m = this.m; val W = v.bounds.width
        val r = UIView(CGRectMake(0, y, W, 64)); r.backgroundColor = CTL_CARD2; r.layer.cornerRadius = 10.0; r.accessibilityIdentifier = "fault.row.$kind"
        val tw = W - 14 - 84
        val t = CtlLabel(CGRectMake(14, 5, tw, 22), FaultName(kind), 15.0, false, CTL_INK); t.adjustsFontSizeToFitWidth = true; t.minimumScaleFactor = 0.75; r.addSubview(t)
        val d = CtlLabel(CGRectMake(14, 27, tw, 32), "", 11.0, false, CTL_INK3); d.numberOfLines = 2; r.addSubview(d)
        val grave = kind == "service_required" || kind == "battery_dead"
        val s = UISwitch(CGRectMake(W - 65, 16, 51, 31)); s.onTintColor = if (grave) CTL_RED else CTL_ORANGE; s.accessibilityIdentifier = "fault.$kind"; r.addSubview(s)
        val fx = CtlButton.buttonWithTitle(FaultFixTitle(kind), CtlButtonFilled, CTL_GREEN, CGRectMake(W - 194, 10, 184, 44), null)
        fx.titleLabel.font = UIFont.boldSystemFontOfSize(13); fx.maxLines = 2; fx.accessibilityIdentifier = "fault.fix.$kind"; fx.hidden = true; r.addSubview(fx)
        val send: (Boolean) -> Unit = { on ->
            me.faultLocal[kind] = on; me.touched("fault.$kind")
            m.command("set_device_fault", mapOf("kind" to kind, "on" to on)) { done, _ ->
                if (done) me.flash(if (on) "Malfunzionamento simulato attivato: " + FaultName(kind) else FaultFixTitle(kind) + ": " + FaultName(kind) + " risolto")
            }
            me.refresh()
        }
        s.addTarget(UIControlEventValueChanged) { send(s.isOn) }
        fx.onTap = { send(false) }
        v.addSubview(r)
        updaters.add {
            val on = me.faultOn(kind); val sup = me.faultSupported(kind); val lay = me.faultLayout()
            if (s.hidden != on) s.hidden = on
            if (fx.hidden == on) fx.hidden = !on
            if (!on && s.isOn && !me.justTouched("fault.$kind")) s.setOn(false, false)
            val txt = if (!sup) (if (lay == "generic_icu") "non si applica al monitor da letto (sempre in rete)" else "non rilevato da questo dispositivo")
                      else FaultHint(lay, kind)
            if (d.text != txt) d.text = txt
            val dw = W - 14 - (if (on) 202 else 84)      // con l'interruttore il testo arriva più a destra
            if (d.frame.width != dw) { d.frame = CGRectMake(14, 27, dw, 32); t.frame = CGRectMake(14, 5, dw, 22) }
            d.textColor = if (on) CTL_INK2 else CTL_INK3
            t.font = if (on) UIFont.boldSystemFontOfSize(15) else UIFont.systemFontOfSize(15)
            t.textColor = if (on) (if (grave) CTL_RED else CTL_ORANGE) else CTL_INK
            val en = me.faultsAvail() && (sup || on)      // ANDROID (fix analisi 22/11, BUG-15): attivo = monitor con malfunzionamenti e guasto rilevato (o da risolvere)
            val a = if (en) 1f else 0.38f; if (r.alpha != a) r.alpha = a
            if (r.userInteractionEnabled != en) r.userInteractionEnabled = en
        }
        return y + 70
    }

    // ANDROID (funzioni 21/11): editor semplice di outcome_sequence. Per ogni scarica (1, 2, 3…) «resta», «converte» o un ritmo;
    // dopo l'ultima vale l'ultima. Si vede solo con «Regole automatiche»: quando è nascosto, il resto del pannello (la «coda»
    // restituita) sale al suo posto. La scarica che tocca adesso è evidenziata (outcome_sequence_index).
    private var seqLocal: List<String>? = null
    @Suppress("UNCHECKED_CAST")
    private fun seqFold(v: UIView, y: Double, df: () -> Map<String, Any?>, cfg: () -> Map<String, Any?>, setCfg: (String, Any?) -> Unit): UIView {
        val me = this; val W = v.bounds.width
        val nChip = 8; val cols = 4; val cw = (W - 28 - (cols - 1) * 8) / cols
        val boxH = 54 + 2 * 46.0 + 46
        val box = UIView(CGRectMake(0, y, W, boxH)); box.backgroundColor = CTL_CARD2; box.layer.cornerRadius = 10.0; box.accessibilityIdentifier = "seq.box"
        box.addSubview(CtlLabel(CGRectMake(14, 6, W - 28, 22), "Esito di ogni scarica (sequenza)", 15.0, false, CTL_INK))
        val sub = CtlLabel(CGRectMake(14, 28, W - 28, 17), "", 11.0, false, CTL_INK3); sub.adjustsFontSizeToFitWidth = true; sub.minimumScaleFactor = 0.8; box.addSubview(sub)
        val emptyL = CtlLabel(CGRectMake(14, 54, W - 28, 40), "Nessuna sequenza: vale «Scariche per convertire». Tocca «+ scarica» per scegliere scarica per scarica.", 12.5, false, CTL_INK2)
        emptyL.numberOfLines = 2; box.addSubview(emptyL)
        val seq: () -> List<String> = { (if (me.justTouched("seq")) me.seqLocal else null) ?: ((cfg()["outcome_sequence"] as? List<*>)?.map { it.toString() } ?: emptyList()) }
        val send: (List<String>) -> Unit = { l -> me.seqLocal = l; me.touched("seq"); setCfg("outcome_sequence", l); me.refresh() }
        val chips = ArrayList<CtlButton>()
        for (i in 0 until nChip) {
            val c = CtlButton.buttonWithTitle("", CtlButtonTinted, CTL_TINT, CGRectMake(14 + (i % cols) * (cw + 8), 54 + (i / cols) * 46.0, cw, 40), null)
            c.titleLabel.font = UIFont.boldSystemFontOfSize(12); c.maxLines = 2; c.accessibilityIdentifier = "seq.$i"
            c.onTap = { me.pickOutcome(i + 1) { o -> val l = ArrayList(seq()); if (i < l.size) { l[i] = o; send(l) } } }
            box.addSubview(c); chips.add(c)
        }
        val bw = (W - 28 - 16) / 3; val by = 54 + 2 * 46.0
        val add = CtlButton.buttonWithTitle("+ scarica", CtlButtonQuiet, null, CGRectMake(14, by, bw, 38), null)
        val del = CtlButton.buttonWithTitle("− ultima", CtlButtonQuiet, null, CGRectMake(14 + bw + 8, by, bw, 38), null)
        val clr = CtlButton.buttonWithTitle("Togli la sequenza", CtlButtonQuiet, null, CGRectMake(14 + 2 * (bw + 8), by, bw, 38), null)
        for ((b, id) in listOf(Pair(add, "seq.add"), Pair(del, "seq.del"), Pair(clr, "seq.clear"))) { b.backgroundColor = CTL_CARD; b.titleLabel.font = UIFont.boldSystemFontOfSize(13); b.maxLines = 1; b.accessibilityIdentifier = id; box.addSubview(b) }
        add.onTap = { val l = ArrayList(seq()); if (l.size < nChip) { l.add(l.lastOrNull() ?: "stay"); send(l) } }
        del.onTap = { val l = ArrayList(seq()); if (l.isNotEmpty()) { l.removeAt(l.size - 1); send(l) } }
        clr.onTap = { send(emptyList()) }
        v.addSubview(box)
        veil(box) { df().containsKey("outcome_sequence_index") }
        val tail = UIView(CGRectMake(0, y + boxH + 6, W, 10)); v.addSubview(tail)
        foldTail = tail
        updaters.add {
            val auto = cfg()["outcome_mode"] == "auto"
            val l = seq(); val used = IntOf(df()["outcome_sequence_index"])
            sub.text = if (l.isEmpty()) "Sostituisce «Scariche per convertire» per FV e TV senza polso"
                       else fmt("Scarica %d è la prossima · dopo l'ultima vale l'ultima · cambiarla riparte dalla 1", min(used, l.size - 1) + 1)
            emptyL.hidden = l.isNotEmpty()
            for (i in 0 until nChip) {
                val c = chips[i]; c.hidden = i >= l.size
                if (c.hidden) continue
                val o = l[i]
                val t = fmt("%d · %s", i + 1, if (o == "convert") "converte" else (if (o == "stay") "resta" else CtlRhythmName(o)))
                if (c.titleForState(UIControlStateNormal) != t) c.setTitle(t, UIControlStateNormal)
                val nextOne = i == min(used, l.size - 1)
                c.setStyle(if (nextOne) CtlButtonFilled else CtlButtonTinted, if (o == "convert") CTL_GREEN else (if (o == "stay") CTL_ORANGE else CTL_TINT))
                c.alpha = if (i < used && !nextOne) 0.5f else 1f
            }
            add.isEnabled = l.size < nChip; del.isEnabled = l.isNotEmpty(); clr.isEnabled = l.isNotEmpty()
            if (box.hidden == auto) box.hidden = !auto
            me.relayoutFold()
        }
        return tail
    }
    // la «coda» del pannello sta sotto il riquadro della sequenza, oppure al suo posto se è nascosto
    private fun relayoutFold() {
        val tail = foldTail ?: return
        val v = tail.superview ?: return
        val box = v.subviews.firstOrNull { it.accessibilityIdentifier == "seq.box" } ?: return
        val ty = box.frame.y + (if (box.hidden) 0.0 else box.frame.height + 6)
        if (tail.frame.y != ty) {
            tail.frame = CGRectMake(0, ty, tail.frame.width, tail.frame.height)
            val h = ty + tail.frame.height + 4
            v.frame = CGRectMake(0, 0, v.frame.width, h)
            (v.superview as? UIScrollView)?.let { it.contentSize = CGSizeMake(it.bounds.width, h) }
        }
    }
    private fun pickOutcome(n: Int, done: (String) -> Unit) {
        val a = CtlSheet.actions(fmt("Scarica %d: che cosa succede?", n), null)
        a.addAction(UIAlertAction.actionWithTitle("Resta nel ritmo", UIAlertActionStyleDefault) { done("stay") })
        a.addAction(UIAlertAction.actionWithTitle("Converte (ritmo dopo la scarica efficace)", UIAlertActionStyleDefault) { done("convert") })
        for (r in listOf("sinus", "sinus_tachycardia", "sinus_bradycardia", "afib", "vf", "vt", "asystole", "pea"))
            a.addAction(UIAlertAction.actionWithTitle("Passa a: " + CtlRhythmName(r), UIAlertActionStyleDefault) { done(r) })
        a.addAction(UIAlertAction.actionWithTitle("Annulla", UIAlertActionStyleCancel, null))
        a.present()      // ANDROID (menu layout a tema 22/11): pannello CtlSheet nel tema (era un AlertDialog di sistema)
    }

    private fun buildSettings() {
        for (x in edBody.subviews.toList()) x.removeFromSuperview()
        rows.clear(); rhythmBtn.clear(); nibpInfo = null
        val sc = CtlScrollView(edBody.bounds); sc.indicatorStyle = UIScrollViewIndicatorStyleWhite; edBody.addSubview(sc)
        val v0 = UIView(CGRectMake(0, 0, edBody.bounds.width - 6, 10)); sc.addSubview(v0)
        var v = v0                                     // ANDROID (funzioni 21/11): dopo la sequenza degli esiti si scrive nella «coda»
        foldTail = null
        val me = this; val m = this.m; var y = 0.0
        val df: () -> Map<String, Any?> = { DictOf(m.state?.get("defib")) }
        val cfg: () -> Map<String, Any?> = { DictOf(df()["config"]) }
        val setCfg: (String, Any?) -> Unit = { k, value -> m.command("set_defib_config", mapOf(k to value)) }
        if (sel == "defib") {
            // ANDROID (BugFixController 4): «Terapia elettrica», come la tessera
            edTitle.text = "Terapia elettrica"
            edSub.text = "La usano i partecipanti sul monitor. Da qui puoi accendere il loro dispositivo e cambiare modo; sotto, che cosa succede dopo le scariche e con il pacing."
            // stato dal vivo
            val box = UIView(CGRectMake(0, y, v.bounds.width, 58)); box.backgroundColor = CTL_CARD2; box.layer.cornerRadius = 10.0; v.addSubview(box)
            val live = CtlLabel(CGRectMake(14, 0, box.bounds.width - 28, 58), "", 14.0, false, CTL_INK); live.numberOfLines = 3; box.addSubview(live)
            updaters.add {
                val d = df(); val pc = DictOf(d["pacer"])
                val md = mapOf("off" to "non attiva", "aed" to "modo DAE", "manual" to "modo manuale", "pacer" to "modo pacer")[d["mode"] ?: ""] ?: "--"
                val pw = me.monitorOn(); val pws = if (pw == false) "dispositivo spento · " else ""
                var t = fmt("Adesso: %s%s · %s J · %s scariche%s%s", pws, md, Desc(d["energy_j"] ?: "--"), Desc(d["shock_count"] ?: 0),
                            // ANDROID (funzioni 21/11): cavo staccato e placche pediatriche / modalità bambino con l'energia vera del DAE
                            (if (d["pads_cable"] == "disconnected") " · CAVO PLACCHE STACCATO" else if (!BoolOf(d["pads_connected"])) " · piastre non applicate" else (if (BoolOf(d["pads_poor_contact"])) " · piastre: CONTATTO SCARSO" else " · piastre applicate")) +
                            (if (d["pads_type"] == "pediatric" || BoolOf(d["aed_child_mode"])) fmt(" · pediatrico %s J", Desc(d["aed_effective_energy_j"] ?: "--")) else ""),
                            if (BoolOf(pc["running"])) fmt("\nPacing %s ppm, %s mA%s", Desc(pc["rate_ppm"]), Desc(pc["output_ma"]), if (BoolOf(pc["capture"])) " — cattura" else " — senza cattura") else "")
                val dm = d["message"] as? String
                if (!dm.isNullOrEmpty()) t += fmt("\n«%s»", dm)
                if (live.text != t) live.text = t
            }
            y += 66
            if (daeMode == true) { y = buildDaeRegia(v, y, df, cfg, setCfg); foldTail?.let { v = it } }      // ANDROID (proposta A8): regia del DAE
            if (daeMode != true) {
            // ANDROID (BugFixController 5, proposta A1): accensione a distanza e modi rapidi (comandi set_device_power e defib_mode)
            y = header("DISPOSITIVO DEL PARTECIPANTE", v, y)
            val pb = CtlButton.buttonWithTitle("Accendi il dispositivo", CtlButtonTinted, CTL_GREEN, CGRectMake(0, y, v.bounds.width, 46), null)
            pb.titleLabel.font = UIFont.boldSystemFontOfSize(15); pb.maxLines = 1; pb.accessibilityIdentifier = "defib.power"; pb.setIcon("power", false)
            pb.onTap = { me.powerTapped() }; v.addSubview(pb)
            updaters.add { me.stylePower(pb) }
            y += 54
            val modes = listOf(Pair("off", "Monitoraggio"), Pair("aed", "DAE"), Pair("manual", "Manuale"), Pair("pacer", "Pacer"))
            val mw = (v.bounds.width - 3 * 8) / 4; var mi = 0
            for (mo in modes) {
                val b = CtlButton.buttonWithTitle(mo.second, CtlButtonQuiet, null, CGRectMake(mi * (mw + 8), y, mw, 44), null)
                b.titleLabel.font = UIFont.boldSystemFontOfSize(14); b.maxLines = 1; b.accessibilityIdentifier = "defib.mode." + mo.first
                b.onTap = { me.setDefibMode(mo.first) }; v.addSubview(b)
                updaters.add { val on = (df()["mode"] ?: "off") == mo.first; b.setStyle(if (on) CtlButtonFilled else CtlButtonQuiet, if (mo.first == "off") CTL_TINT else CTL_ORANGE) }
                mi++
            }
            y += 50
            val hint = CtlLabel(CGRectMake(0, y, v.bounds.width, 30), "Monitoraggio = terapia elettrica non attiva. A dispositivo spento, DAE, Manuale e Pacer prima lo accendono.", 11.5, false, CTL_INK3)
            hint.numberOfLines = 2; v.addSubview(hint)
            y += 34
            y = faultSection(v, y)        // ANDROID (guasti 22/11): malfunzionamenti del dispositivo (chiusa di serie)
            y = header("ESITO DELLE SCARICHE", v, y)
            y = segRow(null, "outcome_mode", listOf("Decido io dopo ogni scarica", "Regole automatiche"), v, y,
                       { if (cfg()["outcome_mode"] == "auto") 1 else 0 }, { i -> setCfg("outcome_mode", if (i != 0) "auto" else "instructor") })
            y = stepRow("Scariche per convertire", "FV / TV senza polso · 0 = mai", "shocks_to_convert", v, y, 1, 0, 9, "",
                        { cfg()["shocks_to_convert"] }, { n -> setCfg("shocks_to_convert", n) })
            y = stepRow("Energia minima efficace", "sotto, la scarica non conta", "min_energy_j", v, y, 10, 10, 360, " J",
                        { cfg()["min_energy_j"] }, { n -> setCfg("min_energy_j", n) })
            val r = rowIn(v, y, "Ritmo dopo la scarica", null)
            val rb = CtlButton.buttonWithTitle("--", CtlButtonTinted, CTL_TINT, CGRectMake(r.bounds.width - 196, 6, 188, 38), null)
            rb.titleLabel.font = UIFont.boldSystemFontOfSize(13); rb.maxLines = 1; rb.accessibilityIdentifier = "set.post_shock_rhythm"; r.addSubview(rb)
            rb.onTap = { me.pickRhythm("Ritmo dopo la scarica efficace", rb) { rh -> setCfg("post_shock_rhythm", rh) } }
            updaters.add { val t = CtlRhythmName(cfg()["post_shock_rhythm"] as? String); if (rb.titleForState(UIControlStateNormal) != t) rb.setTitle(t, UIControlStateNormal) }
            y += 56
            y = switchRow("Scarica non sincronizzata → FV", "su un ritmo con polso (R su T): l'errore ha una conseguenza", "unsync_shock_vf", v, y, CTL_ORANGE,
                          { BoolOf(cfg()["unsync_shock_vf"]) }, { on -> setCfg("unsync_shock_vf", on) })
            // ANDROID (funzioni 21/11): sequenza degli esiti (con «Regole automatiche»); il resto del pannello sta nella «coda»
            v = seqFold(v, y, df, cfg, setCfg); y = 0.0
            y = header("CARDIOVERSIONE E AVANZAMENTI AUTOMATICI", v, y)
            // sync_off_after_shock = nome «positivo» di sync_stays_on: al monitor vecchio si manda la chiave vecchia
            y = switchRow("La SYNC si spegne dopo la scarica", "come nella maggior parte dei dispositivi reali", "sync_off", v, y, null,
                          { !BoolOf(cfg()["sync_stays_on"]) }, { on -> if (cfg().containsKey("device_realism")) setCfg("sync_off_after_shock", on) else setCfg("sync_stays_on", !on) })
            y = switchRow("Avanzamenti automatici annullabili", "cardioversione efficace → ritmo qui sotto senza aspettarti; banner «Annulla» per 5 s", "auto_advance", v, y, CTL_ORANGE,
                          { BoolOf(cfg()["auto_advance"]) }, { on -> setCfg("auto_advance", on) }, { cfg().containsKey("auto_advance") })
            val cr = rowIn(v, y, "Ritmo dopo la cardioversione", null)
            val cb = CtlButton.buttonWithTitle("--", CtlButtonTinted, CTL_TINT, CGRectMake(cr.bounds.width - 196, 6, 188, 38), null)
            cb.titleLabel.font = UIFont.boldSystemFontOfSize(13); cb.maxLines = 1; cb.accessibilityIdentifier = "set.cv_post_rhythm"; cr.addSubview(cb)
            cb.onTap = { me.pickRhythm("Ritmo dopo la cardioversione efficace", cb) { rh -> setCfg("cv_post_rhythm", rh) } }
            updaters.add { val t = CtlRhythmName(cfg()["cv_post_rhythm"] as? String); if (cb.titleForState(UIControlStateNormal) != t) cb.setTitle(t, UIControlStateNormal) }
            y += 56
            y = header("PACING", v, y)
            y = stepRow("Soglia di cattura", "sotto, il pacing non cattura", "capture_threshold_ma", v, y, 5, 0, 200, " mA",
                        { cfg()["capture_threshold_ma"] }, { n -> setCfg("capture_threshold_ma", n) })
            y = switchRow("La cattura è possibile", null, "capture_possible", v, y, null, { BoolOf(cfg()["capture_possible"]) }, { on -> setCfg("capture_possible", on) })
            y = switchRow("La cattura genera polso e pressione", "spento: cattura solo elettrica", "mechanical_capture", v, y, null, { BoolOf(cfg()["mechanical_capture"]) }, { on -> setCfg("mechanical_capture", on) })
            // ANDROID (funzioni 21/11): falsa cattura (spike visibili, nessuna cattura anche sopra soglia)
            y = switchRow("Falsa cattura", "spike visibili ma nessuna cattura, anche sopra soglia", "false_capture", v, y, CTL_ORANGE,
                          { BoolOf(cfg()["false_capture"]) }, { on -> setCfg("false_capture", on) }, { cfg().containsKey("false_capture") })
            y = header("DAE", v, y)
            y = stepRow("RCP fra le analisi", null, "aed_cpr_s", v, y, 30, 30, 300, " s",
                        { cfg()["aed_cpr_s"] }, { n -> setCfg("aed_cpr_s", n) })
            y = switchRow("Istruzioni vocali", null, "voice_prompts", v, y, null, { BoolOf(cfg()["voice_prompts"]) }, { on -> setCfg("voice_prompts", on) })
            y = cprQualityRows(v, y)
            y = header("DISPOSITIVO", v, y)
            y = realismRow(v, y, cfg, setCfg)
            }
        } else {
            edTitle.text = "Monitor, allarmi e paziente"
            edSub.text = "Movimenti del paziente, audio del monitor, NIBP automatica e limiti di allarme."
            // ANDROID (guscio 25/11): i volumi in cima al pannello
            y = header("AUDIO DEL MONITOR", v, y)
            y = stepRow("Volume generale", null, "volume", v, y, 10, 0, 100, "%", { m.state?.get("volume_percent") }, { n -> m.command("set_volume", n) })
            // ANDROID (guscio 25/11): volume di SISTEMA del tablet monitor (i tasti fisici sono dentro il guscio); 15 scatti ≈ 7 % l'uno
            if (m.state?.get("tablet_volume_percent")?.let { IntOf(it) >= 0 } == true)
                y = stepRow("Volume del tablet monitor", null, "tablet_volume", v, y, 7, 0, 100, "%", { m.state?.get("tablet_volume_percent") }, { n -> m.command("set_tablet_volume", n) })
            y = switchRow("Bip a ogni battito", null, "qrs_beep", v, y, null, { BoolOf(m.state?.get("qrs_beep_enabled")) }, { on -> m.command("set_qrs_beep", on) })
            // ANDROID (funzioni mancanti 22/11): in cima, le quattro schede del pannello delle funzioni avanzate (facoltative)
            y = header("FUNZIONI AVANZATE (facoltative)", v, y)
            run {
                val names = listOf("Trend", "Linee e ventilazione", "Limiti e ritardi", "Distrattori ed esami"); val bw = (v.bounds.width - 3 * 8) / 4
                for ((i, t) in names.withIndex()) {
                    val b = CtlButton.buttonWithTitle(t + " ›", CtlButtonTinted, CTL_TINT, CGRectMake(i * (bw + 8), y, bw, 46)) { fxPanel.open(view, i); refresh() }
                    b.titleLabel.font = UIFont.boldSystemFontOfSize(13); b.maxLines = 2; b.accessibilityIdentifier = "set.fx.$i"; v.addSubview(b)
                }
                y += 54
            }
            y = header("MOVIMENTI DEL PAZIENTE (solo con un ritmo con polso)", v, y)
            y = segRow(null, "motion", listOf("Fermo", "Lievi", "Marcati"), v, y,
                       { listOf("off", "light", "strong").indexOf(m.state?.get("patient_motion") ?: "off") },
                       { i -> m.command("set_patient_motion", listOf("off", "light", "strong")[i]) })
            y = switchRow("Fisiologia automatica", "in arresto SpO₂, FR ed EtCO₂ scendono da soli", "auto_physiology", v, y, null,
                          { BoolOf(m.state?.get("auto_physiology")) }, { on -> m.command("set_auto_physiology", on) })
            // ANDROID (funzioni 21/11): ambulanza in movimento, con qualsiasi ritmo
            y = switchRow("Ambulanza in movimento", "artefatti ECG e SpO₂ con qualsiasi ritmo", "vehicle_motion", v, y, CTL_ORANGE,
                          { BoolOf(m.state?.get("vehicle_motion")) }, { on -> m.command("vehicle_motion", on) }, { m.state?.containsKey("vehicle_motion") == true })
            // ANDROID (funzioni 21/11): allarmi tecnici come distrattori (set_technical_alarm). La sonda resta attaccata: tolto
            // l'allarme il segnale torna da solo.
            y = header("ALLARMI TECNICI (distrattori: la sonda resta attaccata)", v, y)
            val techOk: () -> Boolean = { m.state?.containsKey("technical_alarms") == true }
            val techOn: (String) -> Boolean = { k -> ((m.state?.get("technical_alarms") as? List<*>) ?: emptyList<Any?>()).any { (it as? Map<*, *>)?.get("kind") == k || it == k } }
            for (t in listOf(Triple("ecg_leads_off", "Elettrodi ECG scollegati", "ECG tratteggiato, FC «---»"), Triple("spo2_probe_off", "Sonda SpO₂ scollegata", "niente SpO₂ né pletismografia"),
                             Triple("nibp_cuff_error", "Errore del bracciale NIBP", "le misure finiscono in errore"), Triple("etco2_occlusion", "Linea CO₂ occlusa", "capnografia piatta, «---»"),
                             Triple("low_battery", "Batteria scarica", "solo il messaggio del monitor"))) {
                y = switchRow(t.second, t.third, "tech." + t.first, v, y, CTL_ORANGE, { techOn(t.first) },
                              { on -> m.command("set_technical_alarm", mapOf("kind" to t.first, "on" to on)) }, techOk)
            }
            // ANDROID (affordance 22/11, CTL-15): vibrazione di QUESTO controller (attiva di serie)
            y = header("QUESTO CONTROLLER", v, y)
            y = switchRow("Vibrazione ai comandi", if (CtlHaptic.available) "breve al tocco, doppia a comando eseguito, lunga se non arriva" else "questo tablet non ha il motore di vibrazione",
                          "haptic", v, y, null, { CtlHaptic.enabled }, { on -> CtlHaptic.enabled = on; m.prefs.setBool("sf_haptic", on); if (on) CtlHaptic.ok() })
            y = header("NIBP AUTOMATICA (minuti fra le misure)", v, y)
            val iv = listOf(0, 1, 2, 3, 5, 10, 15)
            y = segRow(null, "nibp_interval", listOf("No", "1", "2", "3", "5", "10", "15"), v, y,
                       { val i = iv.indexOf(IntOf(m.state?.get("nibp_interval_min"))); if (i < 0) UISegmentedControlNoSegment else i },
                       { i -> m.command("set_nibp_interval", iv[i]) })
            y = header("LIMITI DI ALLARME", v, y)
            val lim = listOf(listOf("hr_high", "FC alta", 5, " bpm"), listOf("hr_low", "FC bassa", 5, " bpm"), listOf("spo2_low", "SpO₂ bassa", 1, "%"),
                             listOf("sys_high", "Sistolica alta", 5, " mmHg"), listOf("sys_low", "Sistolica bassa", 5, " mmHg"),
                             listOf("rr_high", "FR alta", 1, "/min"), listOf("rr_low", "FR bassa", 1, "/min"), listOf("etco2_high", "EtCO₂ alta", 1, " mmHg"), listOf("etco2_low", "EtCO₂ bassa", 1, " mmHg"))
            for (l in lim) {
                val k = l[0] as String
                y = stepRow(l[1] as String, null, k, v, y, l[2] as Int, 1, 300, l[3] as String,
                            { (m.state?.get("alarm_limits") as? Map<*, *>)?.get(k) },
                            { n -> m.command("set_alarm_limits", mapOf(k to n)) })
            }
            val rs = CtlButton.buttonWithTitle("Ripristina i limiti predefiniti", CtlButtonQuiet, null, CGRectMake(0, y + 2, v.bounds.width, 42), null)
            rs.backgroundColor = CTL_CARD2; rs.titleLabel.font = UIFont.systemFontOfSize(14); rs.accessibilityIdentifier = "set.limits.reset"
            rs.onTap = { m.command("reset_alarm_limits", null); me.flash("Limiti di allarme ripristinati") }
            v.addSubview(rs); y += 52
        }
        // ANDROID (funzioni 21/11): con la sequenza degli esiti y è l'altezza della «coda»; relayoutFold la mette al posto giusto
        val tl = foldTail
        if (tl != null) { tl.frame = CGRectMake(0, tl.frame.y, tl.frame.width, y); y = tl.frame.y + y; tl.frame = CGRectMake(0, -1.0, tl.frame.width, tl.frame.height) }
        v0.frame = CGRectMake(0, 0, v0.bounds.width, y + 4)
        sc.contentSize = CGSizeMake(sc.bounds.width, y + 4)
        if (tl != null) relayoutFold()
    }

    private fun pickRhythm(title: String, src: UIView, done: (String) -> Unit) {
        val a = CtlSheet.actions(title, null)
        for (r in listOf("sinus", "sinus_tachycardia", "sinus_bradycardia", "afib", "asystole", "pea"))
            a.addAction(UIAlertAction.actionWithTitle(CtlRhythmName(r), UIAlertActionStyleDefault) { done(r) })
        a.addAction(UIAlertAction.actionWithTitle("Annulla", UIAlertActionStyleCancel, null))
        // ANDROID: niente popoverPresentationController (sourceView = src): l'avviso compare al centro
        a.present()      // ANDROID (menu layout a tema 22/11): pannello CtlSheet nel tema (era un AlertDialog di sistema)
    }

    // "Decido io": dopo una scarica il monitor aspetta l'istruttore. La domanda compare sopra il pannello centrale,
    // qualunque cosa si stia guardando, finché non si risponde.
    private var outcomeLeft: UILabel? = null
    private fun refreshOutcome() {
        val d = DictOf(m.state?.get("defib"))
        val pending = BoolOf(d["outcome_pending"])
        val from = d["outcome_from"] as? String
        // ANDROID (fix analisi 22/11, terzo giro): la domanda è legata al numero della scarica (outcome_shock). Una scarica nuova
        // chiude la domanda vecchia (il monitor la chiude da sé) oppure la rifà per la scarica nuova, e lo dice; l'esito parte con
        // "shock" = numero della scarica, così il monitor lo rifiuta se nel frattempo è arrivata un'altra scarica.
        val shockN = IntOf(d["outcome_shock"] ?: d["shock_count"])
        val sig = if (pending) fmt("%d|%s", shockN, Desc(from)) else null
        // ANDROID (25/11): la domanda resta aperta 10 s, poi il monitor la chiude da solo e il ritmo resta quello di prima
        val left = IntOf(d["outcome_remaining_s"])
        outcomeLeft?.text = if (left > 0) fmt("Senza scelta fra %d s resta in %s", left, CtlRhythmName(from).lowercase()) else ""
        if (sig == outcomeSig) return
        val prevShock = if (outcome != null) outcomeShock else 0
        outcomeSig = sig
        outcome?.removeFromSuperview(); outcome = null
        outcomeSheet?.dismiss(); outcomeSheet = null
        if (!pending) {
            outcomeShock = 0
            val nowShocks = IntOf(d["shock_count"])
            if (prevShock > 0 && nowShocks > prevShock)
                flash(fmt("Scarica n. %d: la domanda sull'esito della scarica n. %d si è chiusa da sola", nowShocks, prevShock), 6.0)
            return
        }
        outcomeShock = shockN
        val renewed = prevShock > 0 && prevShock != shockN
        val W = center.bounds.width
        val oc = UIView(CGRectMake(0, 0, W, if (renewed) 296.0 else 270.0)); outcome = oc
        oc.backgroundColor = CtlRGB(0.26, 0.19, 0.02); oc.layer.cornerRadius = 12.0; oc.layer.borderWidth = 2.0; oc.layer.borderColor = CTL_YELLOW.CGColor
        val sync = BoolOf(d["outcome_sync"]); val joule = IntOf(d["outcome_energy_j"])
        oc.addSubview(CtlLabel(CGRectMake(20, 12, W - 40, 26), if (sync) "Cardioversione erogata: che cosa succede adesso?" else "Scarica erogata: che cosa succede adesso?", 20.0, true, CTL_INK))
        val sub = CtlLabel(CGRectMake(20, 38, W - 40, 20), fmt("%s n. %s%s · il paziente è ancora in %s", if (sync) "Sincronizzata" else "Scarica",
                                                            Desc(d["outcome_shock"] ?: d["shock_count"] ?: ""), if (joule > 0) fmt(" · %d J", joule) else "", CtlRhythmName(from).lowercase()), 13.0, false, CtlRGB(1.0, 0.88, 0.6))
        oc.addSubview(sub)
        val top = if (renewed) 26.0 else 0.0
        // ANDROID (26/11): il conto alla rovescia ha una riga sua sotto il sottotitolo (prima stava a destra sopra il titolo e lo copriva)
        val lf = CtlLabel(CGRectMake(20, 62 + top, W - 40, 22), "", 14.0, true, CTL_YELLOW)
        lf.accessibilityIdentifier = "outcome.countdown"; oc.addSubview(lf); outcomeLeft = lf
        val left0 = IntOf(d["outcome_remaining_s"])
        if (left0 > 0) lf.text = fmt("Senza scelta fra %d s resta in %s", left0, CtlRhythmName(from).lowercase())
        if (renewed) {
            val nb = CtlLabel(CGRectMake(20, 60, W - 40, 20), fmt("Domanda aggiornata alla scarica n. %d: quella della scarica n. %d non vale più", shockN, prevShock), 13.0, true, CTL_YELLOW)
            nb.accessibilityIdentifier = "outcome.renewed"; oc.addSubview(nb)
        }
        // le scelte più frequenti a un tocco; «Altro ritmo…» apre tutti i ritmi
        val opts = listOf(Pair("stay", fmt("Resta in %s", CtlRhythmName(from).lowercase())), Pair("sinus", "Sinusale (ROSC)"), Pair("sinus_tachycardia", "Tachicardia sinusale (ROSC)"),
                          Pair("vf", "Fibrillazione ventricolare"), Pair("asystole", "Asistolia"), Pair("pea", "PEA"), Pair("other", "Altro ritmo…"))
        val bw = (W - 40 - 2 * 8) / 3; var i = 0; val m = this.m
        for (o in opts) {
            val b = CtlButton.buttonWithTitle(o.second, if (i == 0) CtlButtonQuiet else CtlButtonFilled, if (o.first.startsWith("sinus")) CTL_GREEN else CTL_TINT, CGRectMake(20 + (i % 3) * (bw + 8), 90 + top + (i / 3) * 58, bw, 50), null)
            b.titleLabel.font = UIFont.boldSystemFontOfSize(13); b.accessibilityIdentifier = "outcome." + o.first
            val me = this
            // ANDROID (audit 21/11): dopo il tocco la domanda si blocca finché il monitor risponde (niente doppio esito)
            b.onTap = if (o.first == "other") { { me.outcomeOtherFrom(b, oc, shockN) } } else { { if (me.outcome === oc) { oc.userInteractionEnabled = false; oc.alpha = 0.6f
                m.command("defib_outcome", o.first, mapOf("shock" to shockN)) { ok, _ -> if (!ok && me.outcome === oc) { oc.userInteractionEnabled = true; oc.alpha = 1f } } } } }
            if (o.first == "other") b.frame = CGRectMake(20, 90 + top + 2 * 58, W - 40, 50)
            oc.addSubview(b); i++
        }
        center.addSubview(oc)
        placeOutcome()
    }

    // ANDROID (terzo giro): in sola osservazione l'etichetta «ANNOTI · comanda …» copriva il titolo della domanda: la domanda scende
    private fun placeOutcome() {
        val oc = outcome ?: return
        val oy = if (observing) 30.0 else 0.0
        if (oc.frame.y != oy) oc.frame = CGRectMake(0, oy, oc.frame.width, oc.frame.height)
    }

    // «Altro ritmo…»: tutti i ritmi del monitor
    // ANDROID (fix analisi 22/11, BUG-09): anche «Altro ritmo…» blocca la domanda finché il monitor risponde (prima solo i tasti
    // diretti: un «Altro ritmo» seguito da «Resta in FV» durante un ricollegamento mandava due esiti)
    // ANDROID (terzo giro): l'elenco porta il numero della scarica e si chiude da solo se arriva una scarica nuova
    private fun outcomeOtherFrom(src: UIView, oc: UIView, shockN: Int) {
        val a = CtlSheet.actions(fmt("Dopo la scarica n. %d il paziente passa a", shockN), null)
        val m = this.m; val me = this
        outcomeSheet = a
        for (r in listOf("sinus", "sinus_tachycardia", "sinus_bradycardia", "afib", "vt_pulse", "vt", "vf", "asystole", "pea"))
            a.addAction(UIAlertAction.actionWithTitle(CtlRhythmName(r), UIAlertActionStyleDefault) {
                if (me.outcomeSheet === a) me.outcomeSheet = null
                if (!oc.userInteractionEnabled || me.outcome !== oc) return@actionWithTitle
                oc.userInteractionEnabled = false; oc.alpha = 0.6f
                m.command("defib_outcome", r, mapOf("shock" to shockN)) { ok, _ -> if (!ok && me.outcome === oc) { oc.userInteractionEnabled = true; oc.alpha = 1f } }
            })
        a.addAction(UIAlertAction.actionWithTitle("Annulla", UIAlertActionStyleCancel, null))
        // ANDROID: niente popoverPresentationController
        a.present()      // ANDROID (menu layout a tema 22/11): pannello CtlSheet nel tema (era un AlertDialog di sistema)
    }

    // sede della sonda (lato destro/sinistro): conta, perché il bracciale sullo stesso braccio della SpO₂ la fa sparire
    private fun siteTapped(b: CtlButton) {
        val k = b.accessibilityIdentifier!!.substring(5)
        val sites = DictOf(m.state?.get("sensor_sites"))
        val def = mapOf("spo2" to "finger_l2", "nibp" to "upper_arm_r", "art" to "radial_l")
        val cur = (sites[k] as? String) ?: def[k] ?: ""
        val nw = if (cur.contains("_r")) cur.replace("_r", "_l") else cur.replace("_l", "_r")
        swTouched[k] = CACurrentMediaTime()
        // la sonda spostata va tolta e rimessa: il monitor riparte da capo, come nella realtà
        val m = this.m
        m.command("set_sensor", mapOf("sensor" to k, "connected" to false)) { _, _ -> m.command("set_sensor", mapOf("sensor" to k, "connected" to true, "site" to nw)) }
        flash(fmt("%s spostata a %s", Desc(mapOf("spo2" to "Sonda SpO₂", "nibp" to "Bracciale", "art" to "Linea arteriosa")[k]), if (nw.contains("_r")) "destra" else "sinistra"))
    }
    private fun openMonitorPanel() { sel = "monitor"; refresh() }

    // MARK: - Sala in diretta (ANDROID, sala 22/11)

    private fun openSala() { sel = if (sel == "sala") "hr" else "sala"; if (sel == "sala") sala.retryVideo(); refresh() }      // ANDROID (fix analisi 22/11, BUG-06)

    private fun styleSala() {
        if (!::salaBtn.isInitialized) return
        val on = sel == "sala"; val ear = sala.audioOn
        salaBtn.setTitle(if (ear && !on) "Sala ·  audio" else "Sala", UIControlStateNormal)
        salaBtn.setStyle(if (on) CtlButtonFilled else (if (ear) CtlButtonTinted else CtlButtonQuiet), if (ear || on) CTL_GREEN else null)
    }

    private fun buildSala() {
        for (x in edBody.subviews.toList()) x.removeFromSuperview()
        rows.clear(); rhythmBtn.clear(); nibpInfo = null
        val W = edBody.bounds.width
        edTitle.text = "Sala in diretta"
        edSub.text = "Fotocamera frontale e microfono del monitor, solo in diretta: niente viene registrato né salvato. Sul monitor intanto compare «● IN DIRETTA»."
        val BH = 340.0
        val box = UIView(CGRectMake(0, 0, W, BH)); box.backgroundColor = UIColor.blackColor; box.layer.cornerRadius = 10.0; box.clipsToBounds = true
        box.accessibilityIdentifier = "sala.video"; edBody.addSubview(box)
        val iv = UIImageView(CGRectMake((W - BH * 4 / 3) / 2, 0, BH * 4 / 3, BH)); box.addSubview(iv)
        val wait = CtlLabel(CGRectMake(20, BH / 2 - 30, W - 40, 60), "", 15.0, true, CTL_INK2); wait.textAlignment = NSTextAlignmentCenter; wait.numberOfLines = 3; box.addSubview(wait)
        val stats = CtlLabel(CGRectMake(8, 8, 300, 20), "", 11.5, true, UIColor.whiteColor); stats.backgroundColor = UIColor.colorWithWhite(0, 0.55)
        stats.layer.cornerRadius = 5.0; stats.textAlignment = NSTextAlignmentCenter; stats.font = CtlDigits(11.5, true); box.addSubview(stats)
        val y = BH + 12
        val ab = CtlButton.buttonWithTitle("Audio spento", CtlButtonQuiet, null, CGRectMake(0, y, 160, 44)) { sala.setAudio(!sala.audioOn); refresh() }
        ab.titleLabel.font = UIFont.boldSystemFontOfSize(14); ab.maxLines = 1; ab.accessibilityIdentifier = "sala.audio"; ab.setIcon("bell", false); edBody.addSubview(ab)
        val vl = CtlLabel(CGRectMake(172, y - 2, 190, 16), "", 10.5, true, CTL_INK3); edBody.addSubview(vl)
        // ANDROID (righello a zone 22/11): volume = controllo tecnico: CtlSlider (pista spessa, pomello grande, zona sensibile 44 punti)
        val sl = CtlSlider(CGRectMake(172, y + 10, 190, 44), CTL_GREEN); sl.minimumValue = 0.0; sl.maximumValue = 1.0; sl.value = sala.volume
        sl.accessibilityIdentifier = "sala.volume"
        sl.addTarget(UIControlEventValueChanged) { sala.setVolume(sl.value); vl.text = fmt("VOLUME %d %%", lround(sala.volume * 100)) }
        edBody.addSubview(sl)
        val qb = CtlButton.buttonWithTitle("", CtlButtonQuiet, null, CGRectMake(W - 146, y, 146, 44)) { sala.lowQuality = !sala.lowQuality; sala.retryVideo(); sala.restartVideo(); refresh() }
        // ANDROID (fix analisi 22/11, BUG-06): dopo un errore il video non si riapre da solo: «Riprova» sotto il messaggio
        val rb = CtlButton.buttonWithTitle("Riprova", CtlButtonTinted, CTL_GREEN, CGRectMake((W - 160) / 2, BH / 2 + 40, 160, 40)) { sala.retryVideo(); refresh() }
        rb.titleLabel.font = UIFont.boldSystemFontOfSize(14); rb.maxLines = 1; rb.accessibilityIdentifier = "sala.retry"; rb.hidden = true; box.addSubview(rb)
        qb.titleLabel.font = UIFont.systemFontOfSize(13); qb.maxLines = 2; qb.accessibilityIdentifier = "sala.quality"; edBody.addSubview(qb)
        val y2 = y + 56
        val meterBg = UIView(CGRectMake(0, y2 + 6, 160, 8)); meterBg.backgroundColor = CTL_CARD2; meterBg.layer.cornerRadius = 4.0; edBody.addSubview(meterBg)
        val meter = UIView(CGRectMake(0, 0, 0, 8)); meter.backgroundColor = CTL_GREEN; meter.layer.cornerRadius = 4.0; meterBg.addSubview(meter)
        val al = CtlLabel(CGRectMake(172, y2, W - 172 - 186, 22), "", 11.5, false, CTL_INK3); al.adjustsFontSizeToFitWidth = true; al.minimumScaleFactor = 0.8; edBody.addSubview(al)
        val cb = CtlButton.buttonWithTitle("Chiudi la sala", CtlButtonTinted, CTL_RED, CGRectMake(W - 176, y2 - 4, 176, 38)) { sala.stopAll(); sel = "hr"; refresh() }
        cb.titleLabel.font = UIFont.boldSystemFontOfSize(13); cb.maxLines = 1; cb.accessibilityIdentifier = "sala.close"; edBody.addSubview(cb)
        sala.onFrame = { bm ->
            val r = bm.width.toDouble() / Math.max(1, bm.height)
            val w = Math.min(W, BH * r); val h = w / r
            iv.frame = CGRectMake((W - w) / 2, (BH - h) / 2, w, h)
            iv.image = UIImage(bm)
            if (!wait.hidden) wait.hidden = true
        }
        sala.onChange = { if (sel == "sala") for (u in ArrayList(updaters)) u(); styleSala() }
        sala.onClosed = { why -> flash(why); refresh() }
        vl.text = fmt("VOLUME %d %%", lround(sala.volume * 100))
        updaters.add {
            // ANDROID (fix analisi 22/11, BUG-06): con un errore del video niente riapertura automatica (prima: ciclo continuo)
            if (sel == "sala" && !sala.videoOn && sala.videoError == null && m.stage == CtlStageReady) sala.startVideo()
            val err = sala.videoError
            rb.hidden = err == null || sala.videoOn
            wait.hidden = err == null && !sala.videoWaiting
            wait.text = err ?: (if (sala.videoWaiting) "Collegamento alla fotocamera del monitor…" else "")
            if (err != null || sala.videoWaiting) iv.image = null
            stats.hidden = !sala.videoOn || sala.videoWaiting
            stats.text = fmt("%.0f fps · ritardo ≈ %s · %.0f kB/s%s", sala.fps, if (sala.delayMs >= 0) fmt("%d ms", sala.delayMs) else "--", sala.kbps, if (sala.lowQuality) " · 320" else "")
            ab.setTitle(if (sala.audioOn) "Audio acceso" else "Audio spento", UIControlStateNormal)
            ab.setStyle(if (sala.audioOn) CtlButtonFilled else CtlButtonQuiet, if (sala.audioOn) CTL_GREEN else null)
            qb.setTitle(if (sala.lowQuality) "Qualità: bassa\n(rete debole)" else "Qualità: normale\n(tocca per ridurre)", UIControlStateNormal)
            meter.frame = CGRectMake(0, 0, 160 * (if (sala.audioOn) sala.audioLevel else 0.0), 8)
            al.text = sala.audioError ?: (if (!sala.audioOn) "Audio spento: tocca per ascoltare la sala"
                else if (sala.audioWaiting) "Collegamento al microfono…"
                else fmt("audio %.0f kB/s · in coda %d ms", sala.audioKbps, sala.audioBufMs))
            al.textColor = if (sala.audioError != null) CTL_ORANGE else CTL_INK3
        }
    }

    private fun assessCrt() {
        m.command("assess_crt", null) { ok, _ -> if (ok) flash("Refill: valutazione in corso sul monitor") }
    }
    private fun spo2Option(s: UISwitch) {
        swTouched[s.accessibilityIdentifier ?: ""] = CACurrentMediaTime()
        if (s.accessibilityIdentifier == "spo2.lowperf") m.command("set_spo2_low_perfusion", s.isOn)
        else m.command("set_spo2_signal_valid", !s.isOn)
    }

    // MARK: - ANDROID (insorgenza e ampiezza FV 22/11)

    private fun onsetMode(): String = if (onsetImmediate) "immediate" else "auto"
    // «sul monitor ora: N» solo se il numero a schermo (monitor_numerics) è diverso dal valore impostato durante una rampa
    // (transizione in corso, preludio, o valore cambiato da meno di 30 s)
    private val monChangedAt = HashMap<String, Double>(); private val monLastVal = HashMap<String, Double>()
    // ANDROID (26/11): «adesso» durante una rampa calcolato sul controller fra una lettura e l'altra (il monitor ramp lineare:
    // valore letto + (arrivo − valore) × tempo trascorso / secondi esatti mancanti). Prima restava indietro fino a 1 s.
    private var rampStateObj: Any? = null; private var rampStateAt = 0.0
    private fun liveValue(k: String, t: Map<*, *>): Number? {
        val v0 = valueFor(k) ?: return null
        val st = m.state
        if (st !== rampStateObj) { rampStateObj = st; rampStateAt = CACurrentMediaTime() }
        val re = (t["remaining_exact_s"] as? Number)?.toDouble() ?: return v0
        val to = (t["to"] as? Number)?.toDouble() ?: return v0
        if (st?.get("simulation_running") == false) return v0
        val x = if (re <= 0) 1.0 else Math.min(1.0, (CACurrentMediaTime() - rampStateAt) / re)
        val dec = IntOf(PDef(k)["dec"]); val f = Math.pow(10.0, dec.toDouble())
        return Math.round((v0.toDouble() + (to - v0.toDouble()) * x) * f) / f
    }
    private fun rampNote(k: String, t: Map<*, *>, mn: String): String {
        val re = (t["remaining_exact_s"] as? Number)?.toDouble()
        val left = if (re != null && rampStateObj === m.state) Math.max(0.0, Math.ceil(re - (CACurrentMediaTime() - rampStateAt))).toLong() else t["remaining_s"]
        val now = Num(liveValue(k, t), IntOf(PDef(k)["dec"]))
        return if (mn.isEmpty()) fmt("adesso %s → arrivo %s · ancora %s s", now, Desc(t["to"]), Desc(left))
               else fmt("adesso %s → %s · %s s · %s", now, Desc(t["to"]), Desc(left), mn.replace("sul monitor ora: ", "sul monitor "))
    }
    // solo le note delle righe in rampa, 4 volte al secondo e solo se c'è una transizione (niente ridisegno del resto)
    private fun refreshRampNotes() {
        val st = m.state ?: return
        val tr = DictOf(st["transitions"]); if (tr.isEmpty()) return
        for (k in rows.keys) {
            val t = tr[k] as? Map<*, *> ?: continue
            if (pending[k] != null) continue
            rows[k]!!.setNote(rampNote(k, t, monNote(k, st, true)))
        }
    }
    private fun monNote(k: String, st: Map<String, Any?>, ramp: Boolean): String {
        if (k != "heart_rate_bpm" && k != "spo2_percent") return ""
        val v = (st[k] as? Number)?.toDouble() ?: return ""
        val now = CACurrentMediaTime()
        monLastVal[k]?.let { if (it != v) monChangedAt[k] = now }
        monLastVal[k] = v
        val mv = (DictOf(st["monitor_numerics"])[k] as? Number)?.toDouble() ?: return ""
        if (abs(mv - v) < (if (k == "heart_rate_bpm") 2.0 else 1.0)) return ""
        if (!(ramp || st["rhythm_onset"] != null || now - (monChangedAt[k] ?: -1e9) < 30)) return ""
        return fmt("sul monitor ora: %d", mv.toInt())
    }
    private fun onsetChosen(immediate: Boolean) {
        if (onsetImmediate != immediate) {
            onsetImmediate = immediate
            flashInfo(if (immediate) "Insorgenza di colpo: i prossimi cambi di ritmo partono subito, senza preludio"
                      else "Insorgenza realistica: i prossimi cambi di ritmo arrivano con il preludio fisiologico (2–18 s)")
        }
        refresh()
    }
    private fun mvText(v: Double): String = String.format(java.util.Locale.ITALY, "%.2f", v)
    private fun vfClassName(c: Any?): String = mapOf("coarse" to "grossolana", "medium" to "media", "fine" to "fine")[c as? String ?: ""] ?: ""

    // fascia «INSORGENZA IN CORSO · N s / fase → ritmo» con «Subito ora», nei due posti liberi accanto a «Fibrillazione atriale»
    private fun buildOnsetBand(y: Double, bw: Double, bh: Double, W: Double) {
        val x = bw + 8; val w = W - x
        val band = UIView(CGRectMake(x, y, w, bh)); band.layer.cornerRadius = 10.0; band.layer.borderWidth = 1.5; band.hidden = true
        band.accessibilityIdentifier = "onset.band"; edBody.addSubview(band)
        val tw = w - 12 - 116 - 8
        val t = CtlLabel(CGRectMake(12, 4, tw, 17), "", 12.0, true, CTL_INK); t.adjustsFontSizeToFitWidth = true; t.minimumScaleFactor = 0.85; band.addSubview(t)
        val d = CtlLabel(CGRectMake(12, 20, tw, 28), "", 13.0, false, CTL_INK); d.numberOfLines = 2; d.adjustsFontSizeToFitWidth = true; d.minimumScaleFactor = 0.8; band.addSubview(d)
        val track = UIView(CGRectMake(12, bh - 5, tw, 3)); track.backgroundColor = UIColor.colorWithWhite(1, 0.10); track.layer.cornerRadius = 1.5; band.addSubview(track)
        val bar = UIView(CGRectMake(0, 0, 0, 3)); bar.backgroundColor = CTL_INK2; bar.layer.cornerRadius = 1.5; track.addSubview(bar)
        val me = this
        val sk = CtlButton.buttonWithTitle("Subito ora", CtlButtonQuiet, null, CGRectMake(w - 116 - 6, 6, 116, bh - 12), null)
        sk.titleLabel.font = UIFont.boldSystemFontOfSize(15); sk.maxLines = 1; sk.accessibilityIdentifier = "onset.skip"
        sk.onTap = { me.onsetSkip(sk) }
        band.addSubview(sk)
        onsetBand = band; onsetTitleL = t; onsetTextL = d; onsetBar = bar; onsetSkipBtn = sk
    }
    // riga «Grossolana · Fine · Automatica» + valore attuale e avviso del DAE sotto 0,1 mV
    // ANDROID (righello a zone 22/11): due forme. Larga (regia DAE, sotto i ritmi): tasti a sinistra e le due righe d'informazione a
    // destra, come prima. Stretta (al posto del righello della FC, fra − e +): tre tasti in alto (44 punti) e le informazioni sotto.
    private fun buildVfRow(x: Double, y: Double, W: Double, H: Double) {
        val row = UIView(CGRectMake(x, y, W, H)); row.hidden = true; row.accessibilityIdentifier = "vf.row"; edBody.addSubview(row)
        val me = this; val wide = W > 450
        val bw = if (wide) 104.0 else (W - 16) / 3; val bh = if (wide) min(48.0, H) else 44.0
        for ((i, v) in listOf("coarse" to "Grossolana", "fine" to "Fine", "auto" to "Automatica").withIndex()) {
            val b = CtlButton.buttonWithTitle(v.second, CtlButtonQuiet, null, CGRectMake(i * (bw + 8), 0, bw, bh), null)
            b.titleLabel.font = UIFont.boldSystemFontOfSize(14); b.maxLines = 1; b.accessibilityIdentifier = "vf." + v.first
            b.onTap = { me.vfSet(b, v.first) }
            row.addSubview(b); vfBtns[v.first] = b
        }
        val ix = if (wide) 3 * bw + 16 + 6 else 4.0; val iw = W - ix - (if (wide) 0.0 else 4.0)
        val l1 = CtlLabel(CGRectMake(ix, if (wide) 2.0 else bh + 2, iw, if (wide) 22.0 else 16.0), "", 13.5, true, CTL_INK); l1.adjustsFontSizeToFitWidth = true; l1.minimumScaleFactor = 0.8; row.addSubview(l1)
        val l2 = CtlLabel(CGRectMake(ix, if (wide) 24.0 else bh + 17, iw, if (wide) 22.0 else 15.0), "", 12.5, false, CTL_INK2); l2.adjustsFontSizeToFitWidth = true; l2.minimumScaleFactor = 0.75; row.addSubview(l2)
        vfRow = row; vfInfo1 = l1; vfInfo2 = l2
    }
    private fun refreshOnset(st: Map<String, Any?>, cur: String?) {
        val heart = sel == "rhythm" || sel == "hr"
        // in sola osservazione al suo posto c'è l'etichetta «ANNOTI · comanda …» (e da qui non parte nessun cambio di ritmo)
        val showSeg = heart && !observing
        onsetCap.hidden = !showSeg
        val titleW = if (heart) onsetCap.frame.x - 4 - 20 else CW - 40 - 56
        if (edTitle.frame.width != titleW) { edTitle.frame = CGRectMake(20, 14, titleW, 28); edTitle.adjustsFontSizeToFitWidth = true; edTitle.minimumScaleFactor = 0.85 }
        for (b in onsetSeg) {
            b.hidden = !showSeg
            val imm = b.accessibilityIdentifier == "onset.immediate"; val on = imm == onsetImmediate
            // scelta: quella scelta piena con «●» (azzurro = Realistica, ambra = Di colpo: l'istruttore ha tolto il realismo), l'altra a contorno
            b.setStyle(if (on) CtlButtonFilled else CtlButtonQuiet, if (on) (if (imm) CTL_AMBER else CTL_TINT) else null)
            if (!on) { b.layer.borderWidth = 1.5; b.layer.borderColor = UIColor.colorWithWhite(1, 0.22).CGColor }
            val tt = (if (on) "● " else "") + (if (imm) "Di colpo" else "Realistica")
            if (b.titleForState(UIControlStateNormal) != tt) b.setTitle(tt, UIControlStateNormal)
        }
        val o = st["rhythm_onset"] as? Map<*, *>
        val to = o?.get("to") as? String
        onsetBand?.let { band ->
            band.hidden = o == null
            if (o != null) {
                val c = if (isArrest(to)) CTL_RED else CTL_GREEN
                band.backgroundColor = c.colorWithAlphaComponent(0.12); band.layer.borderColor = c.CGColor
                onsetTitleL?.textColor = c
                onsetTitleL?.text = fmt("INSORGENZA IN CORSO · %d s", ceil(DblOf(o["remaining_s"])).toInt())
                val short = mapOf("vf" to "FV", "vt" to "TV senza polso", "vt_pulse" to "TV con polso", "afib" to "FA", "pea" to "PEA", "asystole" to "asistolia",
                                  "sinus" to "sinusale", "sinus_tachycardia" to "tachicardia sinusale", "sinus_bradycardia" to "bradicardia sinusale")[to ?: ""] ?: CtlRhythmName(to)
                onsetTextL?.text = fmt("%s → %s", Desc(o["phase_label"] ?: o["text"]), short)
                val dur = DblOf(o["duration_s"]); val f = if (dur > 0) min(1.0, DblOf(o["elapsed_s"]) / dur) else 0.0
                onsetBar?.let { bar -> val tw = bar.superview?.bounds?.width ?: 0.0; bar.frame = CGRectMake(0, 0, tw * f, 3) }
            }
        }
        if (to != "vf" && cur != "vf") vfPick = null
        // ampiezza della FV: con la FV attuale (valori del monitor) o in arrivo (la scelta vale all'arrivo)
        val mv = st["vf_amplitude_mv"] as? Number
        val vfNow = cur == "vf" && mv != null; val vfComing = to == "vf"
        val row = vfRow ?: return
        row.hidden = !(vfNow || vfComing)
        rows["heart_rate_bpm"]?.setPresetsHidden(!row.hidden)              // ANDROID (righello a zone 22/11): in FV l'ampiezza al posto dei tasti rapidi
        if (row.hidden) return
        var selV: String? = null; var shown: Double? = null
        if (vfNow) {
            val v = mv!!.toDouble(); shown = v
            selV = if (st["vf_amplitude_mode"] == "instructor") (if (abs(v - 1.0) < 0.005) "coarse" else if (abs(v - 0.15) < 0.005) "fine" else null) else "auto"
            vfInfo1?.text = fmt("FV %s mV · %s", mvText(v), vfClassName(st["vf_amplitude_class"]))
        } else {
            selV = vfPick as? String; shown = (vfPick as? Number)?.toDouble()
            vfInfo1?.text = "FV in arrivo"
        }
        val low = shown != null && shown < 0.1
        if (low) { vfInfo2?.text = "⚠ sotto 0,1 mV il DAE non consiglierà la scarica"; vfInfo2?.textColor = CTL_RED; vfInfo2?.font = UIFont.boldSystemFontOfSize(12.5) }
        else {
            vfInfo2?.textColor = CTL_INK2; vfInfo2?.font = UIFont.systemFontOfSize(12.5)
            vfInfo2?.text = if (vfNow) (if (st["vf_amplitude_mode"] == "instructor") "ampiezza impostata da te (fino al cambio di ritmo)" else "ampiezza automatica · si affina senza RCP")
                            else "ampiezza all'arrivo: " + (if (vfPick == null) "grossolana, poi automatica" else (mapOf("coarse" to "grossolana", "fine" to "fine", "auto" to "automatica")[selV] ?: ""))
        }
        for ((k, b) in vfBtns) {
            val on = k == selV
            b.setStyle(if (on) CtlButtonFilled else CtlButtonQuiet, if (on) CTL_TINT else null)
            if (!on) { b.layer.borderWidth = 1.5; b.layer.borderColor = UIColor.colorWithWhite(1, 0.22).CGColor }
            val nm = mapOf("coarse" to "Grossolana", "fine" to "Fine", "auto" to "Automatica")[k]!!
            val tt = (if (on) "● " else "") + nm
            if (b.titleForState(UIControlStateNormal) != tt) b.setTitle(tt, UIControlStateNormal)
        }
    }
    // «Subito ora»: lo stesso comando di ritmo rimandato di colpo (la transizione di «Applica», di «Subito» o di uno stato, se era quella)
    private fun onsetSkip(b: CtlButton) {
        val o = m.state?.get("rhythm_onset") as? Map<*, *> ?: return
        val to = o["to"] as? String ?: return
        val now = CACurrentMediaTime(); if (now - onsetSkipAt < 0.9) return
        onsetSkipAt = now
        val lr = lastRhy
        var name = "set_rhythm"; var value: Any? = to; var extra: Map<String, Any?>? = mapOf("onset" to "immediate")
        if (lr != null && lr.first == "transition" && (lr.second as? Map<*, *>)?.get("rhythm") == to) {
            name = "transition"; value = LinkedHashMap(lr.second as Map<*, *>).apply { this["onset"] = "immediate" }; extra = null
        }
        val pick = if (to == "vf") vfPick else null
        val me = this
        send(b, name, value, true, fmt("Eseguito dal monitor: %s subito (preludio saltato)", CtlRhythmName(to)), extra) { ok ->
            if (ok && pick != null) me.m.command("set_vf_amplitude", pick)      // saltare il preludio cancella la scelta in attesa: si rimanda
        }
    }
    private fun vfSet(b: CtlButton, v: Any) {
        val now = CACurrentMediaTime(); if (now - vfTapAt < 0.5) return
        vfTapAt = now
        val st = m.state
        val coming = st?.get("rhythm") != "vf" && (st?.get("rhythm_onset") as? Map<*, *>)?.get("to") == "vf"
        val nm = mapOf("coarse" to "grossolana", "fine" to "fine", "auto" to "automatica")[v as? String ?: ""] ?: (if (v is Number) mvText(v.toDouble()) + " mV" else "")
        val me = this
        send(b, "set_vf_amplitude", v, false, fmt("Eseguito dal monitor: ampiezza FV %s%s", nm, if (coming) " (all'arrivo della FV)" else "")) { ok ->
            if (ok && coming) { me.vfPick = v; me.refresh() }
        }
    }

    // MARK: - ANDROID (regia morfologie ECG 22/11)

    /** com'è la morfologia dopo «Applica» (in «Subito» è quella di adesso) */
    private fun effMorph(): CtlMorph = CtlEcg.merge(CtlEcg.of(m.state), pendingMorph)
    // pannello «Morfologia ECG»: «‹ Ritmi», com'è adesso + «ECG normale», poi i gruppi (CtlEcgPanel) in una vista che scorre
    private fun buildEcg() {
        for (x in edBody.subviews.toList()) x.removeFromSuperview()
        rows.clear(); rhythmBtn.clear(); nibpInfo = null; quickBtns.clear()
        edTitle.text = "Morfologia ECG"
        edSub.text = ("La morfologia dell'ECG è separata dal ritmo: resta quando il ritmo cambia e si vede sul tracciato (II) e sulla carta a 12 derivazioni. Con «Subito» parte subito, senza transizione; in «Prepara» aspetta «Applica» e parte insieme agli altri cambi. Nei blocchi AV la FC impostata è quella dei ventricoli: gli atri vanno per conto loro. Le combinazioni che il monitor non accetta sono spente, con il motivo sotto.")
        val sc = CtlScrollView(edBody.bounds); sc.indicatorStyle = UIScrollViewIndicatorStyleWhite; edBody.addSubview(sc)
        val W = edBody.bounds.width - 6
        val v = UIView(CGRectMake(0, 0, W, 10)); sc.addSubview(v)
        val me = this
        var y = 0.0
        val back = CtlButton.buttonWithTitle("‹ Ritmi", CtlButtonQuiet, null, CGRectMake(0, y, 104, 52), null)
        back.titleLabel.font = UIFont.boldSystemFontOfSize(15); back.maxLines = 1; back.accessibilityIdentifier = "ecg.back"; back.onTap = { me.sel = "hr"; me.refresh() }
        v.addSubview(back)
        val nl = CtlLabel(CGRectMake(116, y, W - 116 - 150, 52), "", 14.0, true, CTL_INK); nl.numberOfLines = 3; nl.adjustsFontSizeToFitWidth = true; nl.minimumScaleFactor = 0.75
        nl.accessibilityIdentifier = "ecg.now"; v.addSubview(nl); ecgNowL = nl
        val nb = CtlButton.buttonWithTitle("ECG normale", CtlButtonQuiet, null, CGRectMake(W - 140, y, 140, 52), null)
        nb.titleLabel.font = UIFont.boldSystemFontOfSize(15); nb.maxLines = 1; nb.accessibilityIdentifier = "ecg.normal"
        nb.onTap = { if (me.effMorph().isNormal) me.flashInfo("L'ECG è già normale (il ritmo non cambia)") else me.morphPick("normal", "normal", nb) }
        v.addSubview(nb); ecgNormBtn = nb
        y += 64
        val pn = CtlEcgPanel(v, W, { CtlEcg.of(me.m.state) }, { me.effMorph() }, { part, key, b -> me.morphPick(part, key, b) }, true) { on ->
            me.m.command("set_ecg_morphology", mapOf("interpretation" to on)) { ok, _ ->
                if (ok) me.flash("Eseguito dal monitor: interpretazione automatica sul 12D " + (if (on) "attiva" else "spenta")); me.refresh() }
        }
        y = pn.build(y)
        ecgPanel = pn
        v.frame = CGRectMake(0, 0, W, y + 8); sc.contentSize = CGSizeMake(sc.bounds.width, y + 8)
    }
    /** una scelta: con «Subito» set_ecg_morphology (campi parziali), con «Prepara» nel pacchetto di «Applica» */
    private fun morphPick(part: Any, key: String, b: CtlButton?) {
        val now = CACurrentMediaTime(); if (now - (ecgTapAt[key] ?: 0.0) < 0.8) return
        ecgTapAt[key] = now
        if (prepare) {
            val p: Any = if (part == "normal") "normal" else {
                val base = if (pendingMorph == "normal") linkedMapOf<String, Any?>("st" to "none", "st_mm" to null, "bbb" to "none", "av_block" to "none", "extra" to ArrayList<String>())
                           else LinkedHashMap((pendingMorph as? Map<*, *>)?.entries?.associate { it.key.toString() to it.value } ?: emptyMap())
                for ((k, x) in (part as Map<*, *>)) base[k.toString()] = x
                base
            }
            pendingMorph = p
            if (CtlEcg.payload(effMorph()) == CtlEcg.payload(CtlEcg.of(m.state))) pendingMorph = null      // uguale a com'è adesso: niente in attesa
            refresh(); return
        }
        if (!observing) b?.fbSending()
        val me = this
        m.command("set_ecg_morphology", part) { ok, e ->
            if (ok) { b?.fbDone(true); me.flash("Eseguito dal monitor: ECG " + Desc((me.m.state?.get("ecg_morphology") as? Map<*, *>)?.get("label") ?: "")) }
            else if (e != null) { b?.fbDone(false); me.flash("Morfologia non applicata: " + CtlEcg.errIt(e)) } else b?.fbClear()
            me.refresh()
        }
    }
    private fun refreshEcg(st: Map<String, Any?>) {
        ecgNavBtn?.let { nb ->
            val em = CtlEcg.of(st)
            // ANDROID (righello a zone 22/11): una riga sola in cima al pannello: «Morfologia ECG: STEMI inf · Modifica ›»
            val t = "Morfologia ECG: " + (if (em.isNormal && pendingMorph == null) "normale"
                    else (if (pendingMorph != null) "→ " + CtlEcg.short(effMorph()) else CtlEcg.short(em))) + "  ·  Modifica ›"
            if (nb.titleForState(UIControlStateNormal) != t) nb.setTitle(t, UIControlStateNormal)
            if (pendingMorph != null) nb.setTag("IN ATTESA", CTL_YELLOW) else nb.setTag(null, null)
        }
        ecgPanel?.refresh(observing)
        ecgNowL?.let { l ->
            val lab = Desc((st["ecg_morphology"] as? Map<*, *>)?.get("label") ?: "normale")
            val t = "Adesso: " + lab + (if (pendingMorph != null) "\nIN ATTESA → " + (if (pendingMorph == "normal") "normale" else CtlEcg.short(effMorph())) else "")
            if (l.text != t) l.text = t
            l.textColor = if (pendingMorph != null) CTL_YELLOW else CTL_INK
        }
        ecgNormBtn?.let { if (observing) it.setTag(null, CTL_AMBER, "lock") else it.setTag(null, null) }
    }

    // ANDROID (audit 21/11, ANALISI_ESPERTO 3.7): la TV CON POLSO non è un arresto: segue «Prepara» e la transizione di 10 s
    private fun isArrest(r: Any?): Boolean = listOf("vt", "vf", "asystole", "pea").contains(r)

    private fun rhythmTapped(b: CtlButton) {
        val r = b.accessibilityIdentifier!!.substring(7)
        val cur = m.state?.get("rhythm")
        // ANDROID (insorgenza 22/11): il ritmo già in arrivo non riparte da capo (un secondo tocco ricominciava il preludio): si dice
        // come farlo partire adesso; doppio tocco sullo stesso ritmo = uno (0,8 s)
        val ro = m.state?.get("rhythm_onset") as? Map<*, *>
        if (ro?.get("to") == r) { flashInfo(fmt("%s è già in arrivo (%d s): «Subito ora» la fa partire adesso", CtlRhythmName(r), ceil(DblOf(ro["remaining_s"])).toInt())); return }
        val tNow = CACurrentMediaTime()
        if (tNow - (rhyTapAt[r] ?: 0.0) < 0.8) return
        rhyTapAt[r] = tNow
        // ANDROID (BugFixController 2): in «Prepara» il ritmo aspetta «Applica», TRANNE i ritmi di arresto: partono sempre
        // subito, anche in «Prepara» (decisione dell'utente 21/11: l'arresto è improvviso)
        // ANDROID (audit 21/11, ANALISI_ESPERTO 3.7): anche l'USCITA da un arresto (ROSC) parte subito, come dice il testo del
        // pannello: prima in «Prepara» il «Sinusale» da FV restava in attesa senza che si capisse perché
        if (prepare && !isArrest(r) && !isArrest(cur)) { pendingRhythm = if (r == cur) null else r; refresh(); return }
        pendingRhythm = null
        // ANDROID (BugFixController 1): «Subito» = transizione di 10 s; il monitor fa la rampa della FC fra i ritmi sinusali.
        // I ritmi di arresto (e l'uscita da un arresto) cambiano di colpo, come prima
        // ANDROID (affordance 22/11, CTL-03 / 1.8): la conferma verde «Ritmo: …» la scrive la RISPOSTA del monitor, non il tocco;
        // sul tasto «…» finché il monitor risponde, poi ✓ (o ✕ se non arriva); doppia vibrazione a comando eseguito
        val name = CtlRhythmName(r)
        // ANDROID (insorgenza 22/11): ogni cambio di ritmo porta "onset" (auto = preludio realistico, immediate = di colpo)
        val om = onsetMode(); val how = if (onsetImmediate) " (di colpo)" else ""
        if (isArrest(r) || isArrest(cur)) {
            val ex = mapOf<String, Any?>("onset" to om)
            lastRhy = Triple("set_rhythm", r, ex)
            send(b, "set_rhythm", r, true, fmt("Eseguito dal monitor: ritmo %s%s", name, how), ex)
        } else {
            val v = linkedMapOf<String, Any?>("rhythm" to r, "duration_s" to kSubitoSec, "onset" to om)
            lastRhy = Triple("transition", v, null)
            send(b, "transition", v, true, fmt("Eseguito dal monitor: ritmo %s%s (FC in %d s)", name, how, kSubitoSec))
        }
        refresh()
    }

    /** ANDROID (affordance 22/11, CTL-03): manda un comando partito da un tasto: «…» sul tasto, poi ✓ (con il messaggio verde, se
     *  c'è) o ✕. Un comando bloccato (sola osservazione) o sostituito da uno più nuovo non lascia segni. */
    private fun send(b: CtlButton?, name: String, value: Any?, critical: Boolean = false, okText: String? = null, extra: Map<String, Any?>? = null, then: ((Boolean) -> Unit)? = null) {
        val me = this
        if (!observing) b?.fbSending()
        m.command(name, value, extra) { ok, e ->
            if (ok) { b?.fbDone(true, critical); if (okText != null) me.flash(okText) }
            else if (e != null) b?.fbDone(false) else b?.fbClear()
            then?.invoke(ok)
        }
    }

    private fun param(k: String, v: Double, final: Boolean) {
        if (!final) return
        val now = PDef(k)["now"] as String
        if (now.isNotEmpty()) {
            // ANDROID (BugFixController 2a): temperatura e refill entrano anche loro nel pacchetto di «Prepara»;
            // il monitor non ha una rampa per questi due: all'«Applica» (o subito) arrivano al valore senza transizione
            if (prepare) { val dec = IntOf(PDef(k)["dec"]); val f = Math.pow(10.0, dec.toDouble()); pending[k] = Math.round(v * f) / f; refresh() }
            else m.command(now, v)
            return
        }
        val iv = lround(v).toInt()
        // la diastolica resta sotto la sistolica
        if (k == "systolic_mmhg" || k == "diastolic_mmhg") {
            var s = if (k.startsWith("sys")) iv else IntOf(targetFor("systolic_mmhg"))      // ANDROID (fix analisi 22/11, BUG-01)
            var d = if (k.startsWith("dia")) iv else IntOf(targetFor("diastolic_mmhg"))
            if (d >= s) { if (k.startsWith("sys")) d = max(20, s - 10) else s = d + 10 }
            if (!prepare) { m.command("transition", mapOf("params" to mapOf("systolic_mmhg" to s, "diastolic_mmhg" to d), "duration_s" to kSubitoSec)) }
            else { pending["systolic_mmhg"] = s; pending["diastolic_mmhg"] = d }
            refresh(); return
        }
        if (!prepare) m.command("transition", mapOf("params" to mapOf(k to iv), "duration_s" to kSubitoSec))      // ANDROID (BugFixController 1): 10 s, non uno scatto
        else pending[k] = iv
        refresh()
    }

    private fun setBP(s: Int, d: Int) {
        if (!prepare) m.command("transition", mapOf("params" to mapOf("systolic_mmhg" to s, "diastolic_mmhg" to d), "duration_s" to kSubitoSec))
        else { pending["systolic_mmhg"] = s; pending["diastolic_mmhg"] = d }
        refresh()
    }

    // MARK: - Regia

    // ANDROID (BugFixController 2, proposta S1): numero e riassunto leggibile delle modifiche in attesa
    private fun pendingCount(): Int = pending.size + (if (pendingRhythm != null) 1 else 0) + (if (pendingMorph != null) 1 else 0)   // ANDROID (regia morfologie ECG 22/11)
    private fun pendingSummary(): String {
        val p = LinkedHashMap<String, Any?>(); val x = LinkedHashMap<String, Any?>()
        for ((k, v) in pending) if ((PDef(k)["now"] as String).isEmpty()) p[k] = v else x[k] = v
        val b = if (pending.isEmpty() && pendingRhythm == null) "" else CtlDescribeBlock(pendingRhythm, p, x)
        val e = if (pendingMorph == null) "" else "ECG → " + (if (pendingMorph == "normal") "normale" else CtlEcg.short(effMorph()))   // ANDROID (regia morfologie ECG 22/11)
        return listOf(b, e).filter { it.isNotEmpty() }.joinToString(" · ").ifEmpty { "nessun cambiamento" }
    }

    private fun modeChanged(want: Boolean) {
        // ANDROID (BugFixController 2c): tornare a «Subito» NON applica di nascosto le modifiche preparate: si chiede
        if (!want && prepare && pendingCount() > 0) { askLeavePrepare(); return }
        prepare = want
        refresh()
    }
    private fun askLeavePrepare() {
        val n = pendingCount()
        val a = UIAlertController.alertControllerWithTitle(if (n == 1) "1 modifica in attesa" else fmt("%d modifiche in attesa", n),
            fmt("%s.\nPassando a «Subito», che cosa ne faccio?", pendingSummary()), UIAlertControllerStyleAlert)
        val me = this
        a.addAction(UIAlertAction.actionWithTitle("Resta in «Prepara»", UIAlertActionStyleCancel, null))
        a.addAction(UIAlertAction.actionWithTitle(fmt("Applica ora (in %s)", Dur(prepSec)), UIAlertActionStyleDefault) { me.applyPending(); me.prepare = false; me.refresh() })
        a.addAction(UIAlertAction.actionWithTitle("Scarta le modifiche", UIAlertActionStyleDestructive) { me.cancelPending(); me.prepare = false; me.refresh() })
        presentViewController(a, true, null)
    }
    // ANDROID (BugFixController 2d, proposta G4): stati, azzeramento e fasi dello scenario cancellano le modifiche in attesa: prima si avvisa
    private fun guardPending(what: String, go: () -> Unit) {
        val n = pendingCount()
        if (n == 0) { go(); return }
        val a = UIAlertController.alertControllerWithTitle("Modifiche preparate non applicate",
            fmt("%s scarta %s in attesa: %s.", what, if (n == 1) "la modifica" else fmt("le %d modifiche", n), pendingSummary()), UIAlertControllerStyleAlert)
        a.addAction(UIAlertAction.actionWithTitle("Annulla", UIAlertActionStyleCancel, null))
        a.addAction(UIAlertAction.actionWithTitle("Scarta e continua", UIAlertActionStyleDestructive) { go() })
        presentViewController(a, true, null)
    }
    private fun applyPending() {
        if (pendingCount() == 0) return
        val sec = prepSec
        val params = LinkedHashMap<String, Any?>(); val extra = LinkedHashMap<String, Number>()
        for ((k, v) in pending) { val now = PDef(k)["now"] as String; if (now.isEmpty()) params[k] = v.toInt() else extra[k] = v }
        val what = pendingSummary(); val r = pendingRhythm; val em = pendingMorph
        val me = this
        if (params.isNotEmpty() || r != null || em != null) {
            val v = linkedMapOf<String, Any?>("params" to params, "duration_s" to sec)
            r?.let { v["rhythm"] = it; v["onset"] = onsetMode(); lastRhy = Triple("transition", v, null) }      // ANDROID (insorgenza 22/11)
            if (em != null) v["ecg_morphology"] = em                     // ANDROID (regia morfologie ECG 22/11): nel pacchetto di «Applica»
            m.command("transition", v) { ok, e -> if (ok) me.flash(fmt("Avviato (%s): %s", Dur(sec), what)) else if (e != null && em != null) me.flash("Non avviato: " + CtlEcg.errIt(e)) }
        }
        // temperatura e refill: comandi diretti (sul monitor non hanno una rampa)
        for ((k, v) in extra) m.command(PDef(k)["now"] as String, v)
        if (params.isEmpty() && r == null && em == null) flash(fmt("Applicato: %s", what))
        pending.clear(); pendingRhythm = null; pendingMorph = null
        refresh()
    }
    private fun cancelPending() { pending.clear(); pendingRhythm = null; pendingMorph = null; cancelArmed = 0.0; refresh() }
    // ANDROID (affordance 22/11, CTL-10): «Scarta (N)» = secondo tocco entro 3 s; dopo lo scarto «Ripristina» per 5 s
    private fun cancelTapped() {
        val now = CACurrentMediaTime()
        if (pendingCount() == 0) {
            val d = discarded ?: return
            if (now < discardedUntil) { pending.clear(); pending.putAll(d.first); pendingRhythm = d.second; pendingMorph = discardedMorph; prepare = true; discarded = null; flash("Modifiche preparate ripristinate") }
            refresh(); return
        }
        if (now < cancelArmed) {
            discarded = Pair(LinkedHashMap(pending), pendingRhythm); discardedMorph = pendingMorph; discardedUntil = now + 5
            val n = pendingCount(); cancelPending()
            flashInfo(if (n == 1) "1 modifica scartata · «Ripristina» per 5 s" else fmt("%d modifiche scartate · «Ripristina» per 5 s", n), 5.0)
            Uk.after(5.1) { refresh() }
            return
        }
        cancelArmed = now + 3; refresh()
        Uk.after(3.1) { refresh() }
    }
    // NB: i sì/no vanno sempre scritti @((BOOL)...): @(!x) in Objective-C è un intero e il monitor lo rifiuta
    private fun togglePause() {
        // ANDROID (debriefing scollegato 22/11): con l'azzeramento del debriefing ancora in sospeso niente Avvia/Riprendi: prima
        // deve arrivare l'azzeramento (se il monitor risponde lo si manda adesso)
        if (m.resetPending != null) {
            if (m.stage == CtlStageReady) { m.kickReset(); flash("Prima l'azzeramento rimasto in sospeso dal debriefing: lo mando adesso. Poi tocca di nuovo «Avvia».", 6.0) }
            else flash("Azzeramento in sospeso: il monitor non è raggiungibile. Si potrà avviare appena il monitor è azzerato.", 6.0)
            return
        }
        // ANDROID (avvio scenario 22/11): in PRONTO il tasto è «Avvia» = start_scenario (timer da 00:00, registro, simulazione
        // in corso); un monitor vecchio non conosce il comando: lì vale il «Riprendi» di prima
        if (ScenPhase(m.state) == "ready") {
            val me = this
            if (!observing) pauseBtn.fbSending()
            m.command("start_scenario", null) { ok, e ->
                if (!ok && e?.contains("unknown command") == true) { me.pauseBtn.fbClear(); me.m.command("set_simulation_running", true) }
                else if (ok) { me.pauseBtn.fbDone(true, true); me.flash("Scenario avviato: timer e registro partono adesso") }
                else if (e != null) me.pauseBtn.fbDone(false) else me.pauseBtn.fbClear()
            }
            return
        }
        val run = !BoolOf(m.state?.get("simulation_running"))
        send(pauseBtn, "set_simulation_running", run, true, if (run) "Eseguito dal monitor: simulazione ripresa" else "Eseguito dal monitor: simulazione in pausa")
    }
    // ANDROID (affordance 22/11, CTL-06): il timer è il «menu di regia» (⋯): azzera il timer e, separato, azzera lo scenario
    // (prima stava nel menu del tasto del monitor, che sembrava un indicatore del collegamento)
    private fun clockMenu() {
        val ready = ScenPhase(m.state) == "ready"
        val a = CtlSheet.actions("Regia",
            if (ready) "Il timer è fermo a 00:00: parte quando tocchi «Avvia»." else "Il timer conta il tempo di scenario (le pause non contano). Riparte da solo quando avvii uno scenario.")
        val me = this
        // ANDROID (avvio scenario 22/11): in PRONTO il timer è fermo a 00:00 e parte con «Avvia»: niente da azzerare
        if (!ready) a.addAction(UIAlertAction.actionWithTitle("Azzera il timer", UIAlertActionStyleDefault) {
            me.m.command("reset_clock", null) { ok, _ -> if (ok) me.flash("Timer azzerato: parametri e sonde restano come sono") }
        })
        a.addAction(UIAlertAction.actionWithTitle("Azzera lo scenario e spegni il monitor…", UIAlertActionStyleDestructive) { me.confirmReset() })
        a.addAction(UIAlertAction.actionWithTitle("Annulla", UIAlertActionStyleCancel, null))
        // ANDROID: niente popoverPresentationController (sourceView = clockBtn)
        a.present()      // ANDROID (menu layout a tema 22/11): pannello CtlSheet nel tema (era un AlertDialog di sistema)
    }
    // ANDROID (audit 21/11, ANALISI_ESPERTO 3.10): «Prendi il comando» toglie il comando a un collega: due tocchi, come «Accendi»
    private fun takeControl() {
        if (CACurrentMediaTime() < takeArmed) { takeArmed = 0.0; m.takeControl(); refresh(); return }
        takeArmed = CACurrentMediaTime() + 3; refresh()
        Uk.after(3.1) { refresh() }
    }

    // MARK: - FINE SCENARIO (ANDROID, funzioni 21/11, richiesta utente)

    // Sempre raggiungibile nella barra, anche senza uno scenario a fasi; chiede SEMPRE conferma. Al monitor manda end_scenario
    // (schermata «Scenario terminato» + pausa); se c'è uno scenario a fasi lo termina; poi apre il piano di debriefing completo.
    private fun endTapped() {
        val pn = pendingCount()
        val a = CtlSheet.confirm("Fine scenario?",
            "Sul monitor compare «Scenario terminato» e la simulazione va in pausa. Qui si apre il piano di debriefing con tutta la cronologia." +
            (if (pn > 0) fmt("\n\n%s in attesa (%s) non verranno applicate.", if (pn == 1) "1 modifica" else fmt("%d modifiche", pn), pendingSummary()) else ""))
        val me = this
        a.addAction(UIAlertAction.actionWithTitle("Annulla", UIAlertActionStyleCancel, null))
        a.addAction(UIAlertAction.actionWithTitle("Fine scenario", UIAlertActionStyleDestructive) { me.endScenarioNow() })
        a.present()      // ANDROID (menu layout a tema 22/11): pannello CtlSheet nel tema (era un AlertDialog di sistema)
    }
    private fun endScenarioNow() {
        val me = this; val m = this.m
        m.markEnd()
        m.takeEndSnapshot()                                // ANDROID (debriefing scollegato 22/11): tutto fissato qui, anche senza monitor dopo
        pending.clear(); pendingRhythm = null; pendingMorph = null
        val scenOn = BoolOf(m.scenario?.get("active"))
        // end_scenario (NUOVE_FUNZIONI_CUORE, fine scenario 22/11): pausa vera, «Scenario terminato» sul monitor, riga nel registro;
        // l'API chiude da sola lo scenario a fasi. Con un monitor vecchio: pausa e «Termina» dello scenario a fasi da qui.
        m.command("end_scenario", null) { ok, e ->
            if (!ok && e?.contains("unknown command") == true && scenOn) m.scenarioAction(mapOf("action" to "stop")) { _, _, _ -> me.leftSig = null; me.refresh() }
            if (ok) { me.leftSig = null; m.refreshScenarios(); m.noteEndConfirmed() }
            // ANDROID (debriefing scollegato 22/11): senza risposta niente errori strani nel debriefing: una riga discreta
            val note = if (ok) null
                else if (e?.contains("unknown command") == true) "Il monitor non ha ancora la schermata «Scenario terminato» (versione vecchia): ho messo la simulazione in pausa."
                else if (e == null || e == "offline" || e.contains("NON ARRIVATO") || e.contains("non risponde")) "Monitor non raggiungibile: il debriefing funziona lo stesso (il monitor si azzera al rientro)"
                else fmt("Fine scenario non arrivata al monitor: %s", e)
            if (!ok && e?.contains("unknown command") == true) m.command("set_simulation_running", false)
            if (note != null) { me.debrief?.notice(note); me.flash(note) }
        }
        Uk.after(0.15) { me.openDebrief(true) }      // dopo la chiusura dell'avviso di conferma
    }

    private fun monitorMenu() {
        val a = CtlSheet.actions(m.monitorName,
            "Collegato · Wi-Fi Direct fra i due tablet. Per azzerare lo scenario: tocca il timer (menu di regia).")      // ANDROID (audit 21/11): era «fra iPad»
        val me = this
        // ANDROID (affordance 22/11, CTL-06): qui solo il collegamento; «Azzera lo scenario» è nel menu di regia (tocca il timer)
        a.addAction(UIAlertAction.actionWithTitle("Scollega e scegli un altro monitor", UIAlertActionStyleDefault) { me.m.forgetMonitor() })
        a.addAction(UIAlertAction.actionWithTitle("Annulla", UIAlertActionStyleCancel, null))
        // ANDROID: niente popoverPresentationController (sourceView = monBtn)
        a.present()      // ANDROID (menu layout a tema 22/11): pannello CtlSheet nel tema (era un AlertDialog di sistema)
    }
    private fun confirmReset() {
        // ANDROID (BugFixController 2d): se ci sono modifiche preparate, lo si dice nello stesso avviso
        val pn = pendingCount()
        val a = CtlSheet.confirm("Azzerare lo scenario?",
            "Parametri, sonde, contatori e orologio tornano all'inizio e il monitor si spegne. Gli scenari salvati restano." +
            (if (pn > 0) fmt("\n\nAttenzione: %s in attesa (%s) verranno scartate.", if (pn == 1) "1 modifica" else fmt("%d modifiche", pn), pendingSummary()) else ""))
        val me = this
        a.addAction(UIAlertAction.actionWithTitle("Annulla", UIAlertActionStyleCancel, null))
        a.addAction(UIAlertAction.actionWithTitle("Azzera", UIAlertActionStyleDestructive) {
            val s = me
            s.pending.clear(); s.pendingRhythm = null; s.pendingMorph = null; s.lastState = null
            s.m.command("reset_scenario", null)
        })
        a.present()      // ANDROID (menu layout a tema 22/11): pannello CtlSheet nel tema (era un AlertDialog di sistema)
    }

    // MARK: - Sonde e azioni

    // Layout del monitor: stesso comando della web app (set_layout); la scelta resta salvata sul monitor
    // elenco e nomi dei layout arrivano dal monitor (campo "layouts" dello stato): qui non è scritto nessun nome
    private fun layoutName(lid: Any?): String {
        for (l in (m.state?.get("layouts") as? List<*>) ?: emptyList<Any?>()) if (l is Map<*, *> && l["id"] == lid) return Desc(l["name"])
        return (lid as? String) ?: "--"
    }
    // ANDROID (menu layout a tema 22/11, richiesta utente): pannello CtlSheet nel tema della regia al posto dell'action sheet di
    // sistema. Due sezioni «Default» e «Personalizzati» (campo "category" del catalogo; un monitor che non lo manda: tutto in
    // «Default»), nell'ordine del catalogo (per tipo: icu, ward, defib, aed). Ogni layout è una tessera con miniatura del tipo,
    // nome, tipo e «● In uso». Tocco = set_layout con l'esito sulla tessera (… poi ✓/✕): al ✓ il pannello si chiude da solo,
    // al ✕ resta aperto. Sola osservazione: consultabile, lucchetto sulle tessere, al tocco l'avviso breve (anche nel pannello).
    private fun layoutMenu() {
        val cur = m.state?.get("layout") as? String
        val list = (m.state?.get("layouts") as? List<*>) ?: emptyList<Any?>()
        val me = this
        val kindRank = listOf("icu", "ward", "defib", "aed")
        val obs = observing
        val sh = CtlSheet("Layout del monitor", "Che cosa vede il partecipante sul monitor")
        sh.idPrefix = "layout"; sh.columns = 3
        if (obs) sh.banner = fmt("Sola osservazione: comanda %s. Puoi consultare l'elenco; per cambiare layout tocca «Prendi il comando».", obsWho)
        var busy = false
        for ((cat, title) in SFLayoutCategories) {      // nomi delle categorie: core-state (SFLayoutCatalog.kt)
            val group = list.map { DictOf(it) }.filter { ((it["category"] as? String) ?: "default").let { c -> if (c == "custom") "custom" else "default" } == cat }
                .sortedBy { val i = kindRank.indexOf(it["kind"] as? String); if (i < 0) kindRank.size else i }      // stabile: a parità di tipo l'ordine del catalogo
            if (group.isEmpty() && cat == "default") continue
            val items = ArrayList<CtlSheetItem>()
            for (item in group) {
                val l = item["id"] as? String; val nm = (item["name"] as? String) ?: l ?: ""
                var self: CtlSheetItem? = null
                val it = CtlSheetItem(nm, sub = CtlLayoutKindName(item["kind"] as? String), selected = l != null && l == cur, thumb = (item["kind"] as? String) ?: "",
                    shot = l, id = "layout.${l ?: nm}", keepOpen = true, locked = obs, handler = h@{ b ->
                    if (me.observing) {
                        sh.note(fmt("Comanda %s: tocca «Prendi il comando» per cambiare layout", me.obsWho), CTL_AMBER)
                        me.m.command("set_layout", l)       // non parte: CtlModel lo blocca e mostra l'avviso breve come per gli altri comandi
                        return@h
                    }
                    if (busy) return@h
                    if (l == null) return@h
                    busy = true
                    b.fbSending(); sh.note("Invio al monitor…")
                    me.layoutAskedAt = CACurrentMediaTime()      // ANDROID (funzioni 21/11): scelto da qui: la regia cambia subito
                    me.m.command("set_layout", l) { ok, e ->
                        busy = false
                        if (ok) {
                            b.fbDone(true); self?.let { s -> sh.markSelected(s) }; sh.note("Eseguito dal monitor: $nm", CTL_GREEN)
                            me.flash("Layout del monitor: $nm")
                            Uk.after(0.9) { sh.dismiss() }
                        } else if (e != null) { b.fbDone(false); sh.note(fmt("Non arrivato al monitor: %s", e), CTL_RED, 6.0) }
                        else { b.fbClear(); sh.note(null) }
                    }
                })
                self = it
                items.add(it)
            }
            sh.addSection(CtlSheetSection(title, items, if (cat == "custom") SFLayoutCustomEmptyText else null))
        }
        sh.cancelTitle = "Chiudi"
        sh.present()
    }

    // ANDROID (proposta S4): tocco sulla tessera della sonda = attacca / stacca (stesso comando dell'interruttore di prima)
    // ANDROID (affordance 22/11, CTL-02): a scenario avviato (non in PRONTO) staccare una sonda attaccata chiede un secondo tocco
    // entro 3 s («Tocca di nuovo per staccare»), come «Accendi il dispositivo»: un tocco sbagliato non fa più comparire
    // «elettrodi staccati» davanti ai discenti. Attaccare resta a un tocco.
    private fun sensorTapped(t: CtlSensorTile) {
        val now = CACurrentMediaTime()
        if (t.isOn && !observing && ScenPhase(m.state) != "ready") {
            if (now < (sensorArmed[t.key] ?: 0.0)) { sensorArmed.remove(t.key); sensorSet(t, false); refresh(); return }
            sensorArmed[t.key] = now + 3; t.setArmed(true)
            flashInfo(fmt("Tocca di nuovo «%s» entro 3 s per staccarla dal paziente", SensorShort(t.key).first), 3.0)
            Uk.after(3.1) { refresh() }
            return
        }
        sensorSet(t, !t.isOn)
    }
    private fun sensorSet(t: CtlSensorTile, on: Boolean) {
        val k = t.key
        swTouched[k] = CACurrentMediaTime(); t.show(on)
        siteBtn[k]?.hidden = !on
        m.command("set_sensor", mapOf("sensor" to k, "connected" to on))
    }

    // MARK: - Dispositivo del partecipante (ANDROID, BugFixController 5 / proposta A1)

    /** monitor_on dello stato: null se il monitor non lo dice */
    private fun monitorOn(): Boolean? = m.state?.get("monitor_on") as? Boolean
    // conferma leggera: il primo tocco arma il tasto per 3 s («Tocca di nuovo per accendere»), il secondo accende
    private fun powerTapped() {
        if (monitorOn() == true) return
        if (CACurrentMediaTime() < powerArmed) { powerArmed = 0.0; powerOn(null); refresh(); return }
        powerArmed = CACurrentMediaTime() + 3; refresh()
        Uk.after(3.1) { refresh() }
    }
    // stessa cosa del tasto di accensione del layout attivo, come se l'avesse premuto il partecipante (parte in monitoraggio)
    private fun powerOn(then: (() -> Unit)?) {
        val me = this
        m.command("set_device_power", true) { ok, _ -> if (ok) me.flash("Dispositivo del partecipante acceso dall'istruttore"); then?.invoke() }
    }
    private fun stylePower(b: CtlButton) {
        val on = monitorOn(); val armed = CACurrentMediaTime() < powerArmed
        val t = if (on == true) (if (m.state?.get("monitor_on_by") == "istruttore") "Acceso dall'istruttore" else "Dispositivo acceso")
                else (if (armed) "Tocca di nuovo per accendere" else "Accendi il dispositivo")
        if (b.titleForState(UIControlStateNormal) != t) b.setTitle(t, UIControlStateNormal)
        b.setStyle(if (armed && on != true) CtlButtonFilled else (if (on == true) CtlButtonQuiet else CtlButtonTinted), if (armed) CTL_ORANGE else CTL_GREEN)
        if (b.isEnabled != (on != true)) b.isEnabled = on != true
    }
    // tasti rapidi DAE / Manuale / Pacer / Monitoraggio (defib_mode: aed, manual, pacer, off). A dispositivo spento prima lo si accende.
    private fun setDefibMode(md: String) {
        val names = mapOf("off" to "monitoraggio (terapia elettrica non attiva)", "aed" to "DAE", "manual" to "manuale", "pacer" to "pacer")
        val me = this; val m = this.m
        val send = { m.command("defib_mode", md) { ok, _ -> if (ok) me.flash("Terapia elettrica: " + (names[md] ?: md)) } }
        if (md != "off" && monitorOn() == false) powerOn { send() } else send()
    }
    private fun ventilate() {
        send(ventBtn, "ventilate", null)
        UIView.animateWithDuration(0.12, { ventBtn.backgroundColor = CTL_GREEN.colorWithAlphaComponent(0.45) },
                                   { _ -> UIView.animateWithDuration(0.35) { ventBtn.backgroundColor = CTL_CARD2 } })
    }
    private fun toggleCpr() { m.command("set_cpr", !BoolOf(m.state?.get("cpr_active"))) }
    // ANDROID (pannello RCP 22/11): RCP ferma → parte SUBITO (qualità tutta corretta) e si apre il pannello; RCP in corso → si
    // riapre il pannello (mai fermarla per un tocco sbagliato: si ferma solo con «Interrompi RCP»). In sola osservazione il
    // pannello si apre in lettura e non parte niente. Un tocco doppio = un tocco.
    private fun cprTapped() {
        val now = CACurrentMediaTime()
        if (now - cprTapAt < 0.8) return
        cprTapAt = now
        if (!observing && !cprPanel.cprActive()) cprPanel.startCpr()
        cprPanel.open(view); refresh(); refreshMsg()
    }
    private fun measureNibp() = measureNibp(nibpBtn)
    private fun measureNibp(b: CtlButton) {
        val me = this
        if (!observing) b.fbSending()
        m.startNibp { ok, e -> if (ok) { b.fbDone(true); me.flash("Eseguito dal monitor: misura NIBP partita") } else if (e != null) b.fbDone(false) else b.fbClear() }
    }
    private fun toggleMute() {
        val on = IntOf(m.state?.get("alarms_muted_remaining_s")) <= 0
        send(muteBtn, "mute_alarms", on, false, if (on) "Eseguito dal monitor: allarmi silenziati" else "Eseguito dal monitor: allarmi riattivati")
    }
    // ANDROID (affordance 22/11, CTL-03): «Tocco operatore» confermato dalla risposta del monitor, non dal tocco
    private fun touchPatient() { send(touchBtn, "patient_touch", null, false, "Eseguito dal monitor: tocco operatore, 3 s di artefatto") }

    // MARK: - Colonna sinistra: stati e scenario

    private fun leftChanged() {
        m.prefs.setInt("sf_left", leftSeg.selectedSegmentIndex)
        open = -1; leftSig = null
        leftResetTop = true                                // ANDROID (fix tab 21/11): ogni tab si apre dall'inizio
        if (leftSeg.selectedSegmentIndex == 1) m.refreshScenarios()
        if (leftSeg.selectedSegmentIndex == 3) m.storia.sync(true)      // ANDROID (storia sim 22/11): invii in sospeso e archivio del monitor
        refresh()
    }

    private fun allStates(): List<Map<String, Any?>> = PresetStates() + custom + StatesExtra      // ANDROID (funzioni 21/11): + stati aggiuntivi in fondo

    // ANDROID (fix tab 21/11, segnalato dall'utente): dopo ogni ricostruzione della colonna sinistra lo scorrimento va riportato
    // dentro i limiti del NUOVO contenuto e della NUOVA altezza (il piede cambia altezza da tab a tab). Prima restava quello vecchio:
    // con un elenco più corto il contenuto finiva sopra il bordo, tagliato, e non si poteva più scorrere.
    private var leftResetTop = false
    private fun fixLeftScroll(contentH: Double, keep: Double) {
        leftScroll.contentSize = CGSizeMake(LW, contentH)
        val maxY = max(0.0, contentH - leftScroll.bounds.height)
        val y = if (leftResetTop) 0.0 else min(max(0.0, keep), maxY)
        leftResetTop = false
        if (leftScroll.contentOffset.y != y) leftScroll.contentOffset = CGPointMake(0, y)
    }

    // riga dell'elenco: titolo + dettaglio a destra; se aperta, sotto compaiono testo e pulsanti
    private fun addRow(title: String, detail: String, marked: Boolean, idx: Int, y0: Double, body: String, buttons: List<CtlButton>): Double {
        var y = y0
        val W = LW; val open = idx == this.open      // ANDROID (BugFixController 3): colonna più larga
        val row = UIControl(CGRectMake(6, y, W - 12, 50))
        row.backgroundColor = if (open) CTL_CARD2 else UIColor.clearColor; row.layer.cornerRadius = 9.0; row.uiTag = idx
        row.accessibilityIdentifier = fmt("left.row.%d", idx)
        row.addTarget(UIControlEventTouchUpInside) { rowTapped(it) }
        if (marked) {       // "siamo qui": barra verde + freccia, mai solo colore
            val bar = UIView(CGRectMake(0, 8, 4, 34)); bar.backgroundColor = CTL_GREEN; bar.layer.cornerRadius = 2.0; bar.userInteractionEnabled = false; row.addSubview(bar)
        }
        val t = CtlLabel(CGRectMake(12, 0, W - 12 - 24 - 62, 50), if (marked) "▸ $title" else title, 15.0, marked, if (marked) CTL_GREEN else CTL_INK)
        t.numberOfLines = 2; t.adjustsFontSizeToFitWidth = true; t.minimumScaleFactor = 0.8; t.userInteractionEnabled = false; row.addSubview(t)
        val d = CtlLabel(CGRectMake(W - 12 - 66, 0, 58, 50), detail, 12.0, false, CTL_INK3); d.textAlignment = NSTextAlignmentRight; d.font = CtlDigits(12.0, false); d.userInteractionEnabled = false; row.addSubview(d)
        leftScroll.addSubview(row)
        y += 52
        if (!open) return y
        val ex = UIView(CGRectMake(6, y - 2, W - 12, 10)); ex.backgroundColor = CTL_CARD2; ex.layer.cornerRadius = 9.0
        val b = CtlLabel(CGRectMake(12, 4, W - 36, 10), body, 12.5, false, CTL_INK2); b.numberOfLines = 0
        val sz = b.sizeThatFits(CGSizeMake(W - 36, 400)); b.frame = CGRectMake(12, 4, W - 36, min(sz.height, 170.0)); ex.addSubview(b)
        var by = CGRectGetMaxY(b.frame) + 8
        for (btn in buttons) { btn.frame = CGRectMake(10, by, W - 32, if (btn.frame.height != 0.0) btn.frame.height else 42.0); ex.addSubview(btn); by += btn.frame.height + 6 }
        ex.frame = CGRectMake(6, y - 2, W - 12, by + 4)
        leftScroll.addSubview(ex)
        openRect = CGRectMake(0, row.frame.y - 4, W, CGRectGetMaxY(ex.frame) - row.frame.y + 12)
        return CGRectGetMaxY(ex.frame) + 6
    }

    private var showExtra = m.prefs.bool("sf_showExtra", true)

    private fun rowTapped(row: UIControl) { open = if (open == row.uiTag) -1 else row.uiTag; leftSig = null; refresh() }

    private fun setFooter(views: List<UIView>, h: Double) {
        for (v in leftFooter.subviews.toList()) v.removeFromSuperview()
        leftFooter.frame = CGRectMake(0, LEFTH - h, LW, h)
        leftScroll.frame = CGRectMake(0, 0, LW, LEFTH - h)
        if (h > 0) { val ln = UIView(CGRectMake(0, 0, LW, 0.5)); ln.backgroundColor = CTL_LINE; leftFooter.addSubview(ln) }
        for (v in views) leftFooter.addSubview(v)
    }

    @Suppress("UNCHECKED_CAST")
    private fun refreshLeft() {
        val scen = leftSeg.selectedSegmentIndex == 1; val reg = leftSeg.selectedSegmentIndex == 2
        if (debrief?.presentingViewController != null) debrief?.reload()
        if (m.logSession != quickSession) { quickSession = m.logSession; quickCount.clear(); if (reg) leftSig = null }
        if (reg) { refreshRegistro(); return }
        if (leftSeg.selectedSegmentIndex == 3) { refreshStoria(); return }      // ANDROID (storia sim 22/11)
        val run = m.scenario ?: emptyMap()
        val active = BoolOf(run["active"])
        // si ricostruisce solo quando cambia qualcosa che si vede (non a ogni lettura dello stato)
        val sig = if (scen) fmt("S|%d|%s|%s|%d|%d|%s|%d", if (active) 1 else 0, Desc(run["id"]), Desc(run["phase"]), open, m.scenarios?.hashCode() ?: 0, if (m.scenarios != null) "y" else "n", if (CACurrentMediaTime() < endArmed) 1 else 0)
                  else fmt("T|%d|%s|%d|%d", open, lastState, custom.size, if (showExtra) 1 else 0)
        if (scen && active) phaseClock?.text = Clock(IntOf(run["phase_time_s"]))
        if (sig == leftSig) return
        if (leftScroll.isDragging || leftScroll.isDecelerating) return       // ANDROID (fix tab): mai ricostruire sotto il dito
        leftSig = sig; openRect = CGRectZero
        val keep = leftScroll.contentOffset.y
        for (v in leftScroll.subviews.toList()) v.removeFromSuperview()
        phaseClock = null
        var y = 6.0; val me = this

        if (!scen) {
            // ----- Stati: pacchetti di parametri attivabili con un tocco -----
            val sts = allStates(); var i = 0
            val firstExtra = PresetStates().size + custom.size
            for (st in sts) {
                // ANDROID (funzioni 21/11): intestazione comprimibile della sezione degli stati aggiuntivi (gli stati di serie restano in cima, invariati)
                val idx = i; val isCustom = i >= PresetStates().size && i < firstExtra
                val go = CtlButton.buttonWithTitle(fmt("Attiva · %s", Dur(IntOf(st["d"]))), CtlButtonFilled, CTL_TINT, CGRectMake(0, 0, 0, 44), null)
                go.accessibilityIdentifier = "state.go"; go.onTap = { me.guardPending(fmt("Attivare lo stato «%s»", Desc(st["n"]))) { me.activateState(idx) } }      // ANDROID (BugFixController 2d)
                val btns = arrayListOf(go)
                if (isCustom) {
                    val del = CtlButton.buttonWithTitle("Elimina questo stato", CtlButtonTinted, CTL_RED, CGRectMake(0, 0, 0, 36), null)
                    del.titleLabel.font = UIFont.systemFontOfSize(13); del.onTap = { me.deleteState(idx) }
                    btns.add(del)
                }
                y = addRow(Desc(st["n"]), Dur(IntOf(st["d"])), st["n"] == lastState, i, y,
                           CtlDescribeBlock(st["r"] as? String, st["p"] as? Map<String, Any?>, LinkedHashMap((st["now"] as? Map<String, Any?>) ?: emptyMap()).apply { st["ecg"]?.let { this["ecg_morphology"] = it } }), btns)
                i++
            }
            val save = CtlButton.buttonWithTitle("Salva lo stato attuale", CtlButtonTinted, CTL_TINT, CGRectMake(10, 8, LW - 20, 40)) { saveState() }
            save.titleLabel.font = UIFont.boldSystemFontOfSize(14); save.accessibilityIdentifier = "state.save"; save.setIcon("plus", false)
            setFooter(listOf(save), 56.0)
        } else if (m.scenarios == null) {
            val l = CtlLabel(CGRectMake(16, 16, LW - 32, 120), "Questo monitor non ha ancora gli scenari: va aggiornato similFranz Monitor (versione 0.12 o successiva).", 14.0, false, CTL_INK2)
            l.numberOfLines = 0; leftScroll.addSubview(l); y = 140.0
            setFooter(emptyList(), 0.0)
        } else if (!active) {
            // ----- Scenari salvati sul monitor -----
            var i = 0
            for (sc in m.scenarios!!) {
                val ph = (sc["phases"] as? List<*>) ?: emptyList<Any?>(); val sid = sc["id"]
                val body = StringBuilder()
                val notes = sc["notes"] as? String
                if (!notes.isNullOrEmpty()) body.append(fmt("%s\n\n", notes))
                var n = 1; for (p in ph) body.append(fmt("%d. %s\n", n++, Desc(DictOf(p)["name"])))
                val go = CtlButton.buttonWithTitle("Avvia lo scenario", CtlButtonFilled, CTL_GREEN, CGRectMake(0, 0, 0, 44), null)
                go.accessibilityIdentifier = "scen.start"; go.onTap = { me.scenario(mapOf("action" to "start", "id" to sid, "phase" to 0), "Scenario avviato: fase 1") }
                val ed = CtlButton.buttonWithTitle("Modifica", CtlButtonQuiet, null, CGRectMake(0, 0, 0, 38), null)
                ed.accessibilityIdentifier = "scen.edit"; ed.onTap = { me.editScenario(sc) }
                y = addRow(Desc(sc["name"]), fmt("%d fasi", ph.size), false, i, y, body.toString(), listOf(go, ed))
                i++
            }
            val nw = CtlButton.buttonWithTitle("Nuovo scenario", CtlButtonTinted, CTL_TINT, CGRectMake(10, 8, LW - 20, 40), null)
            nw.titleLabel.font = UIFont.boldSystemFontOfSize(14); nw.accessibilityIdentifier = "scen.new"; nw.setIcon("plus", false); nw.onTap = { me.editScenario(null) }
            setFooter(listOf(nw), 56.0)
        } else {
            // ----- Scenario in corso: le fasi avanzano solo quando lo dice l'istruttore -----
            var sc: Map<String, Any?>? = null; for (s in m.scenarios!!) if (s["id"] == run["id"]) sc = s
            val nm = CtlLabel(CGRectMake(16, y + 2, LW - 32, 36), run["name"] as? String, 13.0, true, CTL_INK2); nm.numberOfLines = 2; leftScroll.addSubview(nm)
            y += 42
            val cur = IntOf(run["phase"]); var i = 0
            for (p0 in (sc?.get("phases") as? List<*>) ?: emptyList<Any?>()) {
                val p = DictOf(p0)
                val idx = i; val here = i == cur
                val note = p["note"] as? String
                val body = fmt("%s\nTransizione: %s%s", CtlDescribeBlock(p["rhythm"] as? String, p["params"] as? Map<String, Any?>, p),
                               Dur(IntOf(p["duration_s"])), if (!note.isNullOrEmpty()) "\n\n" + note else "")
                val btns = ArrayList<CtlButton>()
                val go = CtlButton.buttonWithTitle(if (here) "Riapplica questa fase" else "Vai a questa fase", if (here) CtlButtonQuiet else CtlButtonFilled, CTL_TINT, CGRectMake(0, 0, 0, 42), null)
                go.accessibilityIdentifier = "scen.goto"
                go.onTap = { me.open = -1; me.scenario(mapOf("action" to "goto", "phase" to idx), fmt("Fase %d: %s", idx + 1, Desc(p["name"]))) }
                btns.add(go)
                val y0 = y
                y = addRow(fmt("%d. %s", i + 1, Desc(p["name"])), if (here) Clock(IntOf(run["phase_time_s"])) else (if (IntOf(p["duration_s"]) > 0) Dur(IntOf(p["duration_s"])) else ""),
                           here, i, y, body, btns)
                if (here) {
                    for (v in leftScroll.subviews) if (v.uiTag == i && v is UIControl && abs(v.frame.y - y0) < 1)
                        for (l in v.subviews) if (l is UILabel && l.textAlignment == NSTextAlignmentRight) { phaseClock = l; l.textColor = CTL_GREEN }
                    if (!note.isNullOrEmpty() && open != i) {          // la nota della fase corrente è sempre in vista
                        val nl = CtlLabel(CGRectMake(22, y - 2, LW - 38, 10), note, 12.0, false, CTL_INK2); nl.numberOfLines = 0
                        val sz = nl.sizeThatFits(CGSizeMake(LW - 38, 300)); nl.frame = CGRectMake(22, y - 2, LW - 38, sz.height); leftScroll.addSubview(nl)
                        y += sz.height + 8
                    }
                }
                i++
            }
            val next = run["next_name"] as? String
            val nx = CtlButton.buttonWithTitle(if (next != null) fmt("Fase successiva\n%s", next) else "Ultima fase raggiunta", CtlButtonFilled, CTL_GREEN, CGRectMake(10, 8, LW - 20, 64), null)
            nx.isEnabled = next != null; nx.accessibilityIdentifier = "scen.next"
            // ANDROID (fix analisi 22/11, BUG-02): fase di arrivo ASSOLUTA (goto N+1, idempotente) invece di «next» relativo:
            // un secondo tocco o un comando ripetuto non fanno saltare una fase
            val nextIdx = IntOf(run["phase"]) + 1
            nx.onTap = { me.open = -1; me.scenario(mapOf("action" to "goto", "phase" to nextIdx), "Fase successiva: " + (next ?: "")) }
            val armed = CACurrentMediaTime() < endArmed
            val end = CtlButton.buttonWithTitle(if (armed) "Tocca di nuovo per terminare" else "Termina lo scenario", if (armed) CtlButtonFilled else CtlButtonQuiet, CTL_RED, CGRectMake(10, 80, LW - 20, 36)) { endScenario() }
            end.titleLabel.font = UIFont.systemFontOfSize(13); end.accessibilityIdentifier = "scen.end"
            setFooter(listOf(nx, end), 124.0)
        }
        fixLeftScroll(y + 6, keep)                                            // ANDROID (fix tab): dopo setFooter, con l'altezza giusta
        if (open >= 0 && !CGRectIsEmpty(openRect)) leftScroll.scrollRectToVisible(openRect, false)
    }

    // MARK: - Colonna sinistra: registro (osservazioni dell'istruttore) e piano di debriefing

    private fun refreshRegistro() {
        val last = m.log.lastOrNull()
        val lastText = if (last != null) fmt("%s  %s", Clock(IntOf(last["t"])), Desc(last["text"])) else "Registro di questa sessione: vuoto"
        // ANDROID (avvio scenario 22/11, richiesta utente): prima di «Avvia» niente registro e niente piano di debriefing
        val ready = ScenPhase(m.state) == "ready"
        val debTitle = if (ready) "Piano di debriefing · dopo «Avvia»" else fmt("Piano di debriefing · %d voci", m.log.size)
        val sig = fmt("R|%d|%s|%d|%d", if (m.logAvailable) 1 else 0, quickCount.toString(), if (CACurrentMediaTime() < quickFlashUntil) 1 else 0, if (ready) 1 else 0)
        if (sig == leftSig) {          // niente da ricostruire: si aggiornano solo le due scritte in fondo
            regLast?.let { if (it.text != lastText) it.text = lastText }
            regDeb?.let { if (it.titleForState(UIControlStateNormal) != debTitle) it.setTitle(debTitle, UIControlStateNormal) }
            return
        }
        if (leftScroll.isDragging || leftScroll.isDecelerating) return       // mai ricostruire sotto il dito: si rifà al prossimo giro
        leftSig = sig; openRect = CGRectZero; phaseClock = null
        val keep = leftScroll.contentOffset.y
        for (v in leftScroll.subviews.toList()) v.removeFromSuperview()
        var y = 4.0; var idx = 0; val me = this
        if (!m.logAvailable) {
            val l = CtlLabel(CGRectMake(16, 16, LW - 32, 120), "Questo monitor non ha ancora il registro: va aggiornato similFranz Monitor.", 14.0, false, CTL_INK2); l.numberOfLines = 0; leftScroll.addSubview(l)
            setFooter(emptyList(), 0.0); fixLeftScroll(140.0, 0.0); return
        }
        // ANDROID (registro in osservazione 22/11, segnalazione utente): in PRONTO le voci sono attenuate perché il registro parte con
        // «Avvia», non perché si osserva: lo si dice sopra l'elenco (a scenario avviato chi annota ha il registro pieno e attivo)
        if (ready) {
            val rn = CtlLabel(CGRectMake(12, y + 6, LW - 24, 38), "Il registro parte con «Avvia»: prima le voci restano spente (anche per chi annota).", 12.5, true, CTL_YELLOW)
            rn.numberOfLines = 2; rn.accessibilityIdentifier = "log.readyNote"; leftScroll.addSubview(rn); y += 46
        }
        for (q in QuickMenu()) {
            if (q.size == 1) { leftScroll.addSubview(CtlLabel(CGRectMake(14, y + 8, LW - 28, 16), q[0], 10.5, true, CTL_INK3)); y += 28; continue }
            val kind = q[0]; val text = q[1]; @Suppress("UNUSED_VARIABLE") val tag = if (q[2].isNotEmpty()) q[2] else null
            val col = CtlLogKindColor(kind)
            val just = text == quickFlash && CACurrentMediaTime() < quickFlashUntil
            val row = UIControl(CGRectMake(6, y, LW - 12, 40))
            row.backgroundColor = if (just) col.colorWithAlphaComponent(0.30) else CTL_CARD2; row.layer.cornerRadius = 9.0
            row.accessibilityIdentifier = fmt("quick.%d", idx)
            val bar = UIView(CGRectMake(0, 7, 4, 26)); bar.backgroundColor = col; bar.layer.cornerRadius = 2.0; bar.userInteractionEnabled = false; row.addSubview(bar)
            val n = quickCount[text] ?: 0
            val t = CtlLabel(CGRectMake(14, 0, if (n != 0) LW - 66 else LW - 34, 40), if (just) "✓ $text" else text, 14.0, false, CTL_INK)
            t.numberOfLines = 2; t.adjustsFontSizeToFitWidth = true; t.minimumScaleFactor = 0.85; t.userInteractionEnabled = false; row.addSubview(t)
            if (n != 0) { val c = CtlLabel(CGRectMake(LW - 48, 0, 28, 40), fmt("%d", n), 14.0, true, col); c.font = CtlDigits(14.0, true); c.textAlignment = NSTextAlignmentRight; c.userInteractionEnabled = false; row.addSubview(c) }
            row.addTarget(UIControlEventTouchUpInside) { quickTapped(it) }; row.uiTag = idx
            if (ready) row.alpha = 0.4f
            leftScroll.addSubview(row)
            y += 44; idx++
        }
        val contentH = y + 6
        // in fondo: l'ultima voce del registro e il piano di debriefing
        val lastL = CtlLabel(CGRectMake(12, 6, LW - 24, 34), lastText, 11.5, false, CTL_INK2)
        lastL.numberOfLines = 2; regLast = lastL
        val deb = CtlButton.buttonWithTitle(debTitle, CtlButtonFilled, CTL_TINT, CGRectMake(10, 44, LW - 20, 44), null)
        deb.titleLabel.font = UIFont.boldSystemFontOfSize(14); deb.maxLines = 1; deb.accessibilityIdentifier = "log.debrief"; deb.onTap = { me.openDebrief() }; regDeb = deb
        if (ready) deb.setStyle(CtlButtonQuiet, null)
        setFooter(listOf(lastL, deb), 96.0)
        fixLeftScroll(contentH, keep)                                         // ANDROID (fix tab): dopo setFooter, con l'altezza giusta
    }

    private fun quickTapped(row: UIControl) {
        var idx = 0; var hit: List<String>? = null
        for (q in QuickMenu()) { if (q.size == 1) continue; if (idx == row.uiTag) { hit = q; break }; idx++ }
        if (hit == null) return
        if (ScenPhase(m.state) == "ready") { flash(NotStartedText); return }
        val kind = hit[0]; val text = hit[1]; val tag = if (hit[2].isNotEmpty()) hit[2] else null
        if (text.endsWith("…")) { askQuick(kind, text.substring(0, text.length - 1)); return }
        logQuick(kind, text, tag, text)
    }
    // ANDROID (audit 21/11, ANALISI_ESPERTO 3.2): «Registrato» e il contatore solo quando il monitor ha confermato la voce.
    // Al tocco: «In invio…»; se il collegamento è giù la voce resta in coda con il tempo del tocco; se non arriva, in rosso.
    private fun logQuick(kind: String, text: String, tag: String?, key: String) {
        val t = IntOf(m.state?.get("scenario_time_s"))
        val me = this
        quickFlash = key; quickFlashUntil = CACurrentMediaTime() + 1.6; leftSig = null
        flash(fmt("In invio al registro (%s): %s", Clock(t), text))
        m.logAdd(kind, text, tag, t) { ok, _ ->
            if (!ok) return@logAdd
            me.quickCount[key] = (me.quickCount[key] ?: 0) + 1
            me.quickFlash = key; me.quickFlashUntil = CACurrentMediaTime() + 1.6; me.leftSig = null
            me.flash(fmt("Registrato a %s: %s", Clock(t), text))
            me.refresh()
            Uk.after(1.7) { me.leftSig = null; me.refresh() }
        }
        if (!m.reconnecting || m.stage == CtlStageReady) { refresh(); Uk.after(1.7) { leftSig = null; refresh() } }
        else { flash(fmt("Collegamento giù: «%s» resta in coda con il tempo %s", text, Clock(t))); refresh() }
    }
    // voce con testo libero: due parole dell'istruttore (il tempo registrato è quello del tocco di conferma)
    private fun askQuick(kind: String, title: String) {
        val a = UIAlertController.alertControllerWithTitle(title, "Scrivi due parole: finiscono nel registro con il tempo di scenario.", UIAlertControllerStyleAlert)
        a.addTextField { tf -> tf.autocapitalizationType = UITextAutocapitalizationTypeSentences; tf.placeholder = if (kind == "drug") "es. Midazolam 2 mg" else "es. Comunicazione a circuito chiuso" }
        val me = this
        a.addAction(UIAlertAction.actionWithTitle("Annulla", UIAlertActionStyleCancel, null))
        a.addAction(UIAlertAction.actionWithTitle("Registra", UIAlertActionStyleDefault) {
            val t = (a.textFields.firstOrNull()?.text ?: "").trim(' ', '\t')
            if (t.isNotEmpty()) me.logQuick(kind, t, null, "$title…")
        })
        presentViewController(a, true, null)
    }
    private fun openDebrief() = openDebrief(false)
    // ANDROID (funzioni 21/11): all = dopo FINE SCENARIO: cronologia completa («Tutto»), con la riga «Fine scenario»
    private fun openDebrief(all: Boolean) {
        if (presentedViewController != null) return
        // ANDROID (debriefing scollegato 22/11): con l'istantanea di fine il piano si apre comunque (anche senza stato del monitor)
        if (ScenPhase(m.state) == "ready" && !(all && m.endSnap != null)) { flash(NotStartedText); return }      // ANDROID (avvio scenario 22/11)
        val d = CtlDebriefView(m); debrief = d; d.startAll = all
        // ANDROID (storia sim 22/11): dopo FINE SCENARIO «Chiudi» chiede se salvare in Storia Sim; poi si azzera lo scenario
        d.fromEnd = all
        val me = this
        d.onEndChoice = { saved -> me.afterEndChoice(saved) }
        // ANDROID: modalPresentationStyle non serve (la presentazione in uk è sempre a tutto schermo)
        presentViewController(d, true, null)
    }
    // ANDROID (storia sim 22/11): «Salva in Storia Sim» o «Non salvare»: azzeramento automatico (come confirmReset, senza chiedere)
    // ANDROID (debriefing scollegato 22/11): l'azzeramento non passa più dalla coda dei comandi (scadeva dopo 15 s): resta «in
    // sospeso» (anche a app chiusa) e parte al ricollegamento, solo se il monitor è ancora sulla stessa simulazione terminata
    private fun afterEndChoice(saved: Boolean) {
        pending.clear(); pendingRhythm = null; pendingMorph = null; lastState = null; m.endMark = null
        m.requestReset(saved)
        m.clearEndSnap()
        if (m.stage != CtlStageReady) flash(fmt("%s · monitor non raggiungibile: lo azzero da solo appena torni nella sala di simulazione",
                                                if (saved) "Salvata in Storia Sim (PDF pronto)" else "Non salvata"), 10.0)
        leftSig = null; refresh()
    }

    // MARK: - Colonna sinistra: Storia Sim (ANDROID, storia sim 22/11)

    private fun refreshStoria() {
        val st = m.storia
        st.sync()                                          // al più ogni 30 s; all'apertura del segmento subito
        val list = st.list()
        val sig = fmt("H|%d|%d|%s", st.rev, list.size, st.status ?: "")
        if (sig == leftSig) return
        if (leftScroll.isDragging || leftScroll.isDecelerating) return
        leftSig = sig; openRect = CGRectZero; phaseClock = null
        val keep = leftScroll.contentOffset.y
        for (v in leftScroll.subviews.toList()) v.removeFromSuperview()
        var y = 6.0; val me = this
        if (list.isEmpty()) {
            val l = CtlLabel(CGRectMake(16, 16, LW - 32, 160), "Nessuna simulazione salvata.\n\nA fine simulazione: FINE SCENARIO → piano di debriefing (titolo e squadra in «Squadra ed esito») → «Chiudi» → «Salva in Storia Sim».", 14.0, false, CTL_INK2)
            l.numberOfLines = 0; leftScroll.addSubview(l); y = 180.0
        }
        for ((i, r) in list.withIndex()) {
            val row = UIControl(CGRectMake(6, y, LW - 12, 64)); row.backgroundColor = CTL_CARD2; row.layer.cornerRadius = 9.0
            row.accessibilityIdentifier = fmt("storia.row.%d", i)
            val bar = UIView(CGRectMake(0, 10, 4, 44)); bar.backgroundColor = CTL_TINT; bar.layer.cornerRadius = 2.0; bar.userInteractionEnabled = false; row.addSubview(bar)
            val t = CtlLabel(CGRectMake(14, 6, LW - 12 - 24, 22), Desc(r["title"]), 15.0, true, CTL_INK); t.adjustsFontSizeToFitWidth = true; t.minimumScaleFactor = 0.75; t.userInteractionEnabled = false; row.addSubview(t)
            val nTeam = (r["team"] as? List<*>)?.size ?: 0
            val unsent = (r["_local"] as? Map<*, *>)?.get("unsent") == true
            val sub = fmt("%s · %s · %s", Desc(r["date_text"]), Clock(IntOf(r["duration_s"])), if (nTeam == 1) "1 partecipante" else fmt("%d partecipanti", nTeam))
            val d = CtlLabel(CGRectMake(14, 30, LW - 12 - 24, 16), sub, 12.0, false, CTL_INK2); d.adjustsFontSizeToFitWidth = true; d.minimumScaleFactor = 0.8; d.userInteractionEnabled = false; row.addSubview(d)
            val u = CtlLabel(CGRectMake(14, 46, LW - 12 - 24, 14), if (unsent) "solo su questo tablet: da inviare al monitor" else "inviata al monitor", 10.5, unsent, if (unsent) CTL_ORANGE else CTL_INK3)
            u.userInteractionEnabled = false; row.addSubview(u)
            row.addTarget(UIControlEventTouchUpInside) { me.openArchived(r) }
            leftScroll.addSubview(row)
            y += 70
        }
        val views = ArrayList<UIView>(); var fh = 0.0
        st.status?.let { s0 -> val l = CtlLabel(CGRectMake(12, 6, LW - 24, 30), s0, 11.0, false, CTL_YELLOW); l.numberOfLines = 2; l.adjustsFontSizeToFitWidth = true; l.minimumScaleFactor = 0.8; views.add(l); fh = 36.0 }
        val folder = CtlButton.buttonWithTitle("Apri la cartella dei PDF", CtlButtonQuiet, null, CGRectMake(10, fh + 8, LW - 20, 40)) { me.openStoriaFolder() }
        folder.titleLabel.font = UIFont.boldSystemFontOfSize(14); folder.maxLines = 1; folder.accessibilityIdentifier = "storia.folder"; views.add(folder)
        val pin = CtlButton.buttonWithTitle("Metti sulla schermata Home", CtlButtonTinted, CTL_TINT, CGRectMake(10, fh + 54, LW - 20, 40)) { me.flash(CtlPinStoriaShortcut(Uk.ctx)) }
        pin.titleLabel.font = UIFont.boldSystemFontOfSize(14); pin.maxLines = 1; pin.setIcon("plus", false); pin.accessibilityIdentifier = "storia.pin"; views.add(pin)
        setFooter(views, fh + 102)
        fixLeftScroll(y + 6, keep)
    }
    private fun openArchived(r: Map<String, Any?>) {
        if (presentedViewController != null) return
        val id = r["id"] as? String ?: return
        val rec = m.storia.load(id) ?: r
        val d = CtlDebriefView(m, rec)
        val me = this
        d.onDeleted = { me.leftSig = null; me.flash("Simulazione eliminata da Storia Sim"); me.refresh() }
        presentViewController(d, true, null)
    }
    private fun openStoriaFolder() {
        try { Uk.ctx.startActivity(Intent(Uk.ctx, StoriaSimActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (e: Exception) { flash("Cartella Storia Sim non disponibile") }
    }

    private fun scenario(body: Map<String, Any?>, text: String) {
        // ANDROID (BugFixController 2d): avvio, fase e fase successiva cambiano i parametri: prima si avvisa delle modifiche in attesa.
        // «Termina» lascia il paziente com'è: le modifiche preparate restano.
        val act = body["action"]
        if (act == "stop") { doScenario(body, text, false); return }
        val what = mapOf("start" to "Avviare lo scenario", "goto" to "Andare a questa fase", "next" to "Passare alla fase successiva")[act] ?: "Cambiare fase"
        guardPending(what) { doScenario(body, text, true) }
    }
    private fun doScenario(body: Map<String, Any?>, text: String, clearPending: Boolean) {
        // ANDROID (audit 21/11): un tocco doppio su «Fase successiva» faceva saltare DUE fasi: una richiesta di scenario alla volta
        // ANDROID (fix analisi 22/11, BUG-02): in più una finestra di 1 s dopo il tocco (la risposta arriva in decine di ms e il
        // nuovo «Fase successiva» compare sotto il secondo tocco del doppio tocco)
        if (scenBusy || CACurrentMediaTime() < scenBusyUntil) return
        scenBusy = true; scenBusyUntil = CACurrentMediaTime() + 1.0
        val me = this
        m.scenarioAction(body) { ok, _, _ ->
            val s = me
            s.scenBusy = false
            if (ok) { if (clearPending) { s.pending.clear(); s.pendingRhythm = null; s.pendingMorph = null }; s.flash(text) }
            s.leftSig = null; s.refresh()
        }
    }
    // ANDROID (22/11, richiesta utente): uno scenario a fasi avviato da qui avvia anche la simulazione, quindi «Termina» la
    // termina davvero: è lo stesso FINE SCENARIO della barra (conferma, «Scenario terminato» sul monitor, piano di debriefing,
    // salva/non salvare, azzeramento). end_scenario chiude da solo anche lo scenario a fasi.
    private fun endScenario() { endArmed = 0.0; endTapped() }

    @Suppress("UNCHECKED_CAST")
    private fun activateState(idx: Int) {
        // ANDROID (audit 21/11): doppio tocco su «Attiva» = un solo stato (prima due transizioni e due voci nel registro)
        if (CACurrentMediaTime() < stateBusyUntil) return
        stateBusyUntil = CACurrentMediaTime() + 1.5
        val st = allStates().getOrNull(idx) ?: return
        val p = (st["p"] as? Map<String, Any?>) ?: emptyMap()
        val name = fmt("Stato «%s» attivato · %s", Desc(st["n"]), Dur(IntOf(st["d"])))
        val me = this
        // ANDROID (audit 21/11, ANALISI_ESPERTO 3.10): la voce del registro solo quando il monitor ha accettato lo stato
        val logIt = { me.m.logAdd("phase", name, null); me.flash(name) }
        if (p.isNotEmpty() || st["r"] != null || st["ecg"] != null) {
            val v = linkedMapOf<String, Any?>("params" to p, "duration_s" to (st["d"] ?: 0))
            st["ecg"]?.let { v["ecg_morphology"] = it }                  // ANDROID (regia morfologie ECG 22/11)
            st["r"]?.let { v["rhythm"] = it; v["onset"] = onsetMode(); lastRhy = Triple("transition", v, null) }   // ANDROID (insorgenza 22/11)
            m.command("transition", v) { ok, _ -> if (ok) logIt() }
        }
        val now = st["now"] as? Map<String, Any?>
        now?.get("crt_s")?.let { m.command("set_crt", it) }
        now?.get("temperature_c")?.let { m.command("set_temperature", it) }
        if (p.isEmpty() && st["r"] == null && st["ecg"] == null) logIt()
        lastState = st["n"] as? String; open = -1; pending.clear(); pendingRhythm = null; pendingMorph = null
        m.storia.noteState(lastState)                      // ANDROID (storia sim 22/11)
        if (m.stage != CtlStageReady) flash(fmt("Collegamento giù: «%s» in coda", Desc(st["n"])))
        leftSig = null; refresh()
    }
    private fun deleteState(idx: Int) {
        val i = idx - PresetStates().size
        if (i < 0 || i >= custom.size) return
        custom.removeAt(i); m.prefs.setJson("sf_states", ArrayList(custom))
        open = -1; leftSig = null; refresh()
    }
    private fun currentBlock(): Map<String, Any?> {
        val p = linkedMapOf<String, Any?>()
        for (k in listOf("heart_rate_bpm", "systolic_mmhg", "diastolic_mmhg", "spo2_percent", "respiratory_rate_rpm", "etco2_mmhg")) { val v = valueFor(k); if (v != null) p[k] = v }
        val b = linkedMapOf<String, Any?>("params" to p)
        (m.state?.get("rhythm") as? String)?.let { b["rhythm"] = it }
        valueFor("temperature_c")?.let { b["temperature_c"] = it }
        valueFor("crt_s")?.let { b["crt_s"] = it }
        CtlEcg.of(m.state).let { if (!it.isNormal) b["ecg_morphology"] = CtlEcg.payload(it) }      // ANDROID (regia morfologie ECG 22/11)
        return b
    }
    private fun saveState() {
        val a = UIAlertController.alertControllerWithTitle("Salva lo stato attuale",
            "I valori veri del paziente in questo momento diventano uno stato richiamabile con un tocco.", UIAlertControllerStyleAlert)
        a.addTextField { tf -> tf.placeholder = "Nome dello stato"; tf.autocapitalizationType = UITextAutocapitalizationTypeSentences }
        val me = this
        a.addAction(UIAlertAction.actionWithTitle("Annulla", UIAlertActionStyleCancel, null))
        a.addAction(UIAlertAction.actionWithTitle("Salva", UIAlertActionStyleDefault) {
            val s = me
            var n = (a.textFields.firstOrNull()?.text ?: "").trim(' ', '\t')
            if (n.isEmpty()) n = fmt("Stato %d", s.custom.size + 1)
            val b = s.currentBlock()
            val now = linkedMapOf<String, Any?>()
            b["crt_s"]?.let { now["crt_s"] = it }
            b["temperature_c"]?.let { now["temperature_c"] = it }
            val stt = linkedMapOf<String, Any?>("n" to n, "d" to 30, "r" to (b["rhythm"] ?: "sinus"), "p" to b["params"], "now" to now)
            b["ecg_morphology"]?.let { stt["ecg"] = it }                // ANDROID (regia morfologie ECG 22/11)
            s.custom.add(stt)
            s.m.prefs.setJson("sf_states", ArrayList(s.custom))
            s.leftSig = null; s.refresh()
        })
        presentViewController(a, true, null)
    }

    private fun editScenario(sc: Map<String, Any?>?) {
        val ed = CtlScenarioEditor(sc, currentBlock())
        val me = this
        ed.onSave = { scenario ->
            me.m.scenarioAction(mapOf("action" to "save", "scenario" to scenario)) { ok, e, _ ->
                if (ok) { ed.dismissViewControllerAnimated(true, null); me.open = -1; me.leftSig = null; me.flash("Scenario salvato sul monitor"); me.refresh() }
                else ed.showError(e)
            }
        }
        ed.onDelete = { sid ->
            me.m.scenarioAction(mapOf("action" to "delete", "id" to sid)) { ok, e, _ ->
                if (ok) { ed.dismissViewControllerAnimated(true, null); me.open = -1; me.leftSig = null; me.refresh() }
                else ed.showError(e)
            }
        }
        // ANDROID: modalPresentationStyle non serve
        presentViewController(ed, true, null)
    }

    // MARK: - Verifica da Mac

    fun debug(host: String, query: Map<String, String>) {
        val q = query
        val w = view.window ?: view      // anche con una schermata modale aperta (ANDROID: la radice della gerarchia uk)
        // ANDROID (regia morfologie ECG 22/11): anche una finestra aperta da un'altra finestra (editor → «Morfologia ECG della fase»)
        var top: UIViewController? = presentedViewController
        while (top?.presentedViewController != null) top = top.presentedViewController
        val root = CtlSheet.current?.root ?: top?.view ?: view      // ANDROID (menu layout a tema 22/11): anche dentro un pannello CtlSheet aperto
        if (host == "shot") {
            // ANDROID: fotografia della radice in cacheDir/shot-<nome>.png (sull'iPad Caches/shots/<nome>.png)
            val img = w.snapshotBitmap()
            try { FileOutputStream(File(Uk.ctx.cacheDir, "shot-" + (q["name"] ?: "shot") + ".png")).use { img.compress(Bitmap.CompressFormat.PNG, 100, it) } }
            catch (e: Exception) { SFLog.log("shot: " + e) }
            return
        }
        if (host == "audit") {
            val out = ArrayList<String>(); val n = IntArray(1)
            AuditText(w, null, out, n)
            SFLog.log(fmt("AUDIT %s: %d testi, %d problemi", q["name"] ?: "", n[0], out.size))
            for (o in out) SFLog.log("AUDIT   $o")
            return
        }
        if (host == "splash") { if (q["off"] != null) view.viewWithTag(7777)?.removeFromSuperview() else showSplash(q["static"] != null); return }
        if (host == "fitlog") { CtlFitLog = true; edBuilt = null; refresh(); view.layoutIfNeeded(); CtlFitLog = false; return }
        // ANDROID (funzioni 21/11): fake?v=<JSON> sovrappone valori allo stato del monitor (solo verifiche); fake?off toglie
        if (host == "fake") { m.applyDebugOverlay(if (q["off"] != null) null else (Json.parseOrNull(q["v"] ?: "{}") as? Map<String, Any?>)); return }
        if (host == "obs") { obsForce = q["off"] == null; refresh(); return }       // ANDROID (funzioni 21/11): sola osservazione finta
        if (host == "dae") { m.applyDebugOverlay(mapOf("layout" to (q["v"] ?: "dae"))); return }
        if (host == "logadd") { logQuick(q["k"] ?: "note", q["t"] ?: "prova", q["tag"], q["t"] ?: "prova"); return }
        if (host == "cmd") {        // comando qualsiasi al monitor: cmd?n=nome&v=<JSON del valore>
            val v = q["v"]?.let { Json.parseOrNull(it) }
            m.command(q["n"] ?: "", v); return
        }
        if (host == "forget") { m.forgetMonitor(); return }
        if (host == "cprpanel") { if (q["off"] != null) cprPanel.close() else cprPanel.open(view); return }
        if (host == "fxpanel") { if (q["off"] != null) fxPanel.close() else { val t = q["tab"]?.toIntOrNull(); if (t != null || !fxPanel.isOpen) fxPanel.open(view, t ?: 0); q["y"]?.toDoubleOrNull()?.let { fxPanel.scrollTo(it) }; refresh() }; return }   // ANDROID (funzioni mancanti 22/11)      // ANDROID (pannello RCP 22/11): apre/chiude senza comandi
        // ANDROID (storia sim 22/11): storiasave = salva la sessione in corso in Storia Sim (senza azzerare); storiaopen?i=N apre
        // la N-esima salvata nel piano (archivio); storiapdf?i=N apre il suo PDF; storiasync forza la sincronizzazione;
        // debend apre il piano come dopo FINE SCENARIO (senza mandare end_scenario)
        if (host == "storiasave") { m.storia.save(m.storia.buildRecord()) { r -> SFLog.log("storia: salvata " + Desc(r["id"]) + " pdf " + Desc((r["_local"] as? Map<*, *>)?.get("pdf"))); leftSig = null; refresh() }; return }
        if (host == "storiaopen") { m.storia.list().getOrNull(IntOf(q["i"]))?.let { openArchived(it) }; return }
        if (host == "storiapdf") { m.storia.list().getOrNull(IntOf(q["i"]))?.let { r -> m.storia.pdfFile(r)?.let { CtlOpenStoriaPdf(it) } ?: m.storia.makePdf(r) { f -> if (f != null) CtlOpenStoriaPdf(f) } }; return }
        if (host == "storiasync") { m.storia.sync(true); return }
        if (host == "debend") { openDebrief(true); return }
        // ANDROID (sala 22/11): sala[?audio=1|0][&low=1|0][&off] apre/chiude il pannello «Sala»; salainfo scrive i contatori nel log
        if (host == "sala") {
            if (q["off"] != null) { sala.stopAll(); sel = "hr"; refresh(); return }
            q["low"]?.let { sala.lowQuality = it == "1"; sala.restartVideo() }
            sel = "sala"; sala.retryVideo(); refresh(); q["audio"]?.let { sala.setAudio(it == "1") }; refresh(); return
        }
        if (host == "salainfo") { SFLog.log(fmt("sala: video %s %d fotogrammi %.1f fps ritardo %d ms · audio %s %d byte livello %.2f · errori %s / %s", sala.videoOn, sala.framesGot, sala.fps, sala.delayMs, sala.audioOn, sala.audioBytesGot, sala.audioLevel, sala.videoError, sala.audioError)); return }
        if (host == "pairpreview") {      // ANDROID (guasti 22/11): schermata di associazione sopra la regia, solo per le fotografie
            view.viewWithTag(7778)?.removeFromSuperview()
            if (q["off"] == null) { val pv = CtlPairingView(CGRectMake(0, 0, W, H), m); pv.uiTag = 7778; view.addSubview(pv); pv.refresh() }
            return
        }
        if (host == "has") { SFLog.log(fmt("stato: %s = %s", q["k"], if (m.state?.containsKey(q["k"] ?: "") == true) Desc(m.state?.get(q["k"] ?: "")) else "(assente)")); return }   // ANDROID (guasti 22/11)
        if (host == "wallsend") { m.sendWallClock(true); return }      // ANDROID (guasti 22/11): manda l'ora anche con l'orologio sbagliato (verifiche)
        if (host == "faults") { faultsOpen = q["off"] == null; faultsMore = q["more"] != null; rebuildKeepingScroll(); return }   // ANDROID (guasti 22/11)
        if (host == "scroll") { (edBody.subviews.firstOrNull { it is UIScrollView } as? UIScrollView)?.setContentOffset(CGPointMake(0, DblOf(q["y"])), false); return }
        if (host == "list") { val a = ArrayList<String>(); ListIds(root, a); SFLog.log("ID visibili: " + a.joinToString(" ")); return }
        if (host == "layout") { m.command("set_layout", q["v"] ?: "generic_icu"); return }
        if (host == "pin") { m.submitPin(q["v"] ?: ""); return }
        if (host == "dismiss" && CtlSheet.current != null) { CtlSheet.current?.cancel(); return }      // ANDROID (menu layout a tema 22/11)
        if (host == "dismiss") { presentedViewController?.dismissViewControllerAnimated(false, null); return }
        val v = q["id"]?.let { FindId(root, it) }
        if (v == null) { SFLog.log(fmt("debug %s: elemento \"%s\" non trovato", host, q["id"])); return }
        // stesso controllo di un dito: l'elemento deve essere quello che riceve il tocco al suo centro
        val c = v.convertPoint(CGPointMake(CGRectGetMidX(v.bounds), CGRectGetMidY(v.bounds)), null)
        val hit = v.window?.hitTest(c)
        val reach = hit === v || (hit != null && hit.isDescendantOfView(v))
        SFLog.log(fmt("debug %s %s %s", host, q["id"], if (reach) "ok" else fmt("COPERTO da %s (%s)", hit?.javaClass?.simpleName, hit?.accessibilityIdentifier)))
        if (!reach) return
        if (host == "tap" && v is UIControl) v.sendActionsForControlEvents(UIControlEventTouchUpInside)
        else if (host == "seg" && v is UISegmentedControl) { v.selectedSegmentIndex = IntOf(q["i"]); v.sendActionsForControlEvents(UIControlEventValueChanged) }
        else if (host == "sw" && v is UISwitch) { v.setOn(BoolOf(q["on"]), true); v.sendActionsForControlEvents(UIControlEventValueChanged) }
        else if (host == "sw" && v is CtlSensorTile) sensorSet(v, BoolOf(q["on"]))          // ANDROID (proposta S4): sonde a tessere
        else if (host == "val" && v is CtlParamRow) v.debugSet(DblOf(q["v"]))
        else if (host == "text") {
            when (v) {
                is UITextField -> { v.text = q["v"] ?: ""; v.sendActionsForControlEvents(UIControlEventEditingChanged) }
                is UITextView -> v.text = q["v"] ?: ""
                is UILabel -> v.text = q["v"] ?: ""
                else -> {}
            }
        }
    }
}

// Menu rapido: un tocco = una voce nel registro del monitor, con il tempo di scenario. Stesso elenco della web app.
// [categoria, testo, tag] ; il testo che finisce con "…" chiede di scrivere due parole.
private fun QuickMenu(): List<List<String>> {
    return listOf( listOf("RICONOSCIMENTO"),
                   listOf("action", "Riconosciuto l'arresto cardiaco", "ac"), listOf("action", "Chiamato aiuto / attivato il team", "help"),
                   listOf("action", "Valutazione ABCDE", ""), listOf("action", "Valutato il ritmo", ""),
                   listOf("RCP"),
                   listOf("action", "Iniziate le compressioni", "cpr_start"), listOf("action", "Cambio dell'operatore alle compressioni", ""), listOf("action", "Ventilazioni", ""),
                   listOf("VIE AEREE E ACCESSI"),
                   listOf("action", "Ossigeno", ""), listOf("action", "Presidio sovraglottico", ""), listOf("action", "Intubazione", ""),
                   listOf("action", "Accesso venoso", ""), listOf("action", "Accesso intraosseo", ""),
                   listOf("FARMACI"),
                   listOf("drug", "Adrenalina 1 mg", ""), listOf("drug", "Amiodarone 300 mg", ""), listOf("drug", "Amiodarone 150 mg", ""),
                   listOf("drug", "Atropina 0,5 mg", ""), listOf("drug", "Liquidi 500 ml", ""), listOf("drug", "Altro farmaco…", ""),
                   listOf("PER IL DEBRIEFING"),
                   listOf("plus", "Punto di forza…", ""), listOf("minus", "Da discutere…", ""), listOf("note", "Nota libera…", "") )
}

// MARK: - Verifica da Mac (aiuti)

private fun FindId(root: UIView, ident: String): UIView? {
    if (root.accessibilityIdentifier == ident && !root.hidden) return root
    for (v in root.subviews) { if (v.hidden) continue; val f = FindId(v, ident); if (f != null) return f }
    return null
}
private fun ListIds(root: UIView, out: MutableList<String>) {
    if (root.hidden) return
    if (!root.accessibilityIdentifier.isNullOrEmpty()) out.add(root.accessibilityIdentifier!!)
    for (v in root.subviews) ListIds(v, out)
}

// Controllo dei testi a schermo: troncati (puntini), parole spezzate a metà, righe con una o due lettere sole.
// ANDROID: NSLayoutManager → StaticLayout di uk (UkText.layout), stesse regole.
private fun AuditText(root: UIView, owner0: String?, out: MutableList<String>, count: IntArray) {
    if (root.hidden || root.alpha < 0.05f) return
    var owner = owner0
    if (!root.accessibilityIdentifier.isNullOrEmpty()) owner = root.accessibilityIdentifier
    if (root is UILabel) {
        val l = root; val t = l.text
        if (!t.isNullOrEmpty() && l.bounds.width > 1 && l.uiTag != 4242) {      // 4242 = testo con più caratteri diversi (marchio): verificato a vista
            count[0]++
            var f = l.font
            if (l.adjustsFontSizeToFitWidth) f = f.fontWithSize(f.pointSize * (if (l.minimumScaleFactor > 0) l.minimumScaleFactor else 0.5))   // può rimpicciolirsi fin qui
            val lay = UkText.layout(t, UkText.paint(f), ceil(l.bounds.width + 0.5).toInt(), NSTextAlignmentLeft, 0, false)
            val lines = (0 until lay.lineCount).map { IntRange(lay.getLineStart(it), lay.getLineEnd(it) - 1) }
            val height = lay.height.toDouble()
            var problem: String? = null
            val allowed = l.numberOfLines
            if ((allowed > 0 && lines.size > allowed) || height > l.bounds.height + 3) problem = "TRONCATO"
            var i = 0
            while (problem == null && i < lines.size) {
                val r = lines[i]
                val end = r.last + 1
                val line = t.substring(r.first, end).trim()
                if (lines.size > 1 && line.isNotEmpty() && line.length <= 2 && line[0].isLetter()) problem = "RIGA DI UNA-DUE LETTERE"
                if (i + 1 < lines.size && end > r.first && end < t.length) {
                    val a = t[end - 1]; val b = t[end]
                    if (a.isLetter() && b.isLetter()) problem = "PAROLA SPEZZATA"
                }
                i++
            }
            if (problem != null) out.add(fmt("%s [%s] «%s» (%.0fx%.0f, %.1f pt)", problem, owner ?: "?", t.replace("\n", " / "), l.bounds.width, l.bounds.height, l.font.pointSize))
        }
    }
    for (v in root.subviews) AuditText(v, owner, out, count)
}
