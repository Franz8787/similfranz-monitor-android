// ANDROID (ICU 21/11): nessun file corrispondente sull'iPad. GENERIC ICU a tutto schermo sul Tab Active 2.
// Richiesta dell'utente: niente bande nere ai lati dell'ICU. Un monitor vero usa tutto lo schermo: con l'ICU a schermo la
// tela (1024×768) si allarga sulle due fasce laterali (1228×768 punti = 1280×800 px) e l'ICU è costruito per quella larghezza:
// tracce più lunghe (stessa velocità in mm/s, si vedono più secondi), colonna dei numeri più larga (numeri più grandi),
// barre superiore e inferiore a tutta larghezza. Con un layout di dispositivo la tela torna 1024 al centro, fra le fasce
// (prese a sinistra, scocca), esattamente come prima: gli altri layout non cambiano.
// L'ICU non ha prese nella fascia: i sensori si collegano toccando la traccia (o il valore) come prima, nulla è duplicato.
package it.simulafranz.monitor

import it.simulafranz.core.*
import it.simulafranz.uk.*
import java.util.Locale
import kotlin.math.floor

/** larghezza della tela di progetto (come l'iPad) */
internal const val SF_CANVAS_W = 1024.0

/** larghezza dell'ICU a tutto schermo: tela + le due fasce (1228 punti sul Tab Active 2; 1024 se non ci sono fasce) */
internal fun SFIcuScreenWidth(): Double = SF_CANVAS_W + 2 * (SFSideBand.left?.bounds?.width ?: 0.0)

/** Tela larga (ICU) o al centro fra le fasce (layout dei dispositivi). Da chiamare PRIMA di creare la vista di un layout. */
internal fun MonitorViewController.fitCanvasForLayout(icu: Boolean) {
    val canvas = view.superview ?: return
    if (canvas.superview == null || canvas === SFSideBand.left || canvas === SFSideBand.right) return
    val b = SFSideBand.left?.bounds?.width ?: 0.0
    val H = view.bounds.size.height
    val cf = if (icu) CGRectMake(0, 0, SF_CANVAS_W + 2 * b, H) else CGRectMake(b, 0, SF_CANVAS_W, H)
    if (canvas.frame != cf) canvas.frame = cf
    val vf = CGRectMake(0, 0, cf.size.width, H)
    if (view.frame != vf) view.frame = vf
}

/** il ☰ dell'istruttore nell'ICU: in alto a sinistra nella barra superiore, area di tocco 48×48 (disegno compatto) */
internal fun SFIcuMenuFrame(): CGRect = CGRectMake(4, 4, 48, 48)

// ---- Allarmi: campo clinico e campo tecnico separati, come nei monitor multiparametrici veri ----

// testi tecnici di AlarmManager (sensore staccato, segnale scarso, errore della misura): il resto è clinico
private val ICU_TECH_PREFIX = listOf("ECG ELETTRODI", "SpO2 SONDA", "SpO2: NIBP", "SpO2 SEGNALE", "SpO2 BASSA",
                                     "ART SMORZATA", "NIBP BRACCIALE", "NIBP ARTEFATTO", "NIBP NESSUN POLSO",
                                     "ART TRASDUTTORE", "SpO2 ARTEFATTO",   // ANDROID (IN-02, IN-07)
                                     // ANDROID (funzioni mancanti 22/11): IN-03, IN-10, IN-13, IN-18
                                     "ART NON PULSATILE", "ART AZZERAMENTO", "ART: ", "PVC", "ECG RA ", "ECG LA ", "ECG LL ", "ECG V ",
                                     "ECG DISTURBO", "SpO2 RICERCA", "NIBP TEMPO")
internal fun SFIsTechnicalAlarmText(m: String): Boolean = ICU_TECH_PREFIX.any { m.startsWith(it) }

/** allarmi tecnici simulati (distrattori, technical_alarms) che AlarmManager non mostra già: linea CO2 occlusa, batteria scarica.
 *  Gli altri (elettrodi, sonda, bracciale) arrivano già da AlarmManager perché il cuore toglie il segnale. */
internal fun SFExtraTechnicalAlarms(s: SLSnapshot): List<String> =
    if (!s.simulationRunning) emptyList()
    else (s.technicalAlarms.filter { it == "etco2_occlusion" || it == "low_battery" }.map { SLTechnicalAlarmText(it) } +
          // ANDROID (guasti 22/11): malfunzionamento simulato della batteria = allarme tecnico (come set_technical_alarm low_battery):
          // «SOSTITUIRE LA BATTERIA» con battery_replace, «BATTERIA SCARICA» con battery_low. Il guasto del defibrillatore
          // e l'errore di carica si vedono nel cassetto del defibrillatore (DefibDrawerView).
          (if (s.fault("battery_replace")) listOf(SLDeviceFaultScreen("generic_icu", "battery_replace"))
           else if (s.fault("battery_low")) listOf(SLDeviceFaultScreen("generic_icu", "battery_low")) else emptyList())).distinct()

/** Campo allarmi clinici (rosso/giallo lampeggiante) e campo tecnico (ciano fisso, testo nero), a rotazione ciascuno. */
internal fun MonitorViewController.updateIcuAlarmFields(r: SLAlarmResult, s: SLSnapshot) {
    val now = CACurrentMediaTime()
    val clinical = r.messages.filter { !SFIsTechnicalAlarmText(it) }
    val tech = ArrayList(r.messages.filter { SFIsTechnicalAlarmText(it) })
    for (t in SFExtraTechnicalAlarms(s)) if (!tech.contains(t)) tech.add(t)
    val lvl = if (r.level >= SLAlarmMedium) r.level else SLAlarmNone

    // clinici: più allarmi a rotazione, 5 s ciascuno, con l'indice (es. "2/3")
    if (lvl == SLAlarmNone || clinical.isEmpty()) banner.hidden = true
    else {
        banner.hidden = false
        val n = clinical.size
        val idx = if (n > 1) (floor(now / 5.0).toLong() % n).toInt() else 0
        var msg = clinical[idx]
        if (n > 1) msg += String.format(Locale.US, "   %d/%d", idx + 1, n)
        if (banner.text != msg) banner.text = msg
        val c = if (lvl == SLAlarmHigh) SL_ALARM else SL_YELLOW
        val on = if (lvl == SLAlarmHigh) (tick % 2 == 0) else flashOn   // rosso lampeggia a 2 Hz, giallo a 1 Hz
        val bg = (if (on) c else UIColor.colorWithWhite(0.15, 1)).CGColor
        if (banner.layer.backgroundColor != bg) banner.layer.backgroundColor = bg
        banner.textColor = if (on) UIColor.blackColor else c
    }
    // tecnici: campo a sé, ciano FISSO (non lampeggia: priorità bassa), testo nero; a rotazione ogni 4 s
    if (tech.isEmpty()) inopField.hidden = true
    else {
        inopField.hidden = false
        val n = tech.size
        val idx = if (n > 1) (floor(now / 4.0).toLong() % n).toInt() else 0
        var msg = tech[idx]
        if (n > 1) msg += String.format(Locale.US, "  %d/%d", idx + 1, n)
        if (inopField.text != msg) inopField.text = msg
    }
}

/** suono di priorità bassa anche per i soli distrattori che AlarmManager non conosce (CO2 occlusa, batteria scarica) */
internal fun SFTechnicalOnlySound(s: SLSnapshot, current: Int): Int =
    if (current == SFAlarmSoundNone && !s.alarmsMuted && SFExtraTechnicalAlarms(s).isNotEmpty()) SFAlarmSoundLow else current
