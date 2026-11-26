// Portato da src/AlarmManager.m (monitor iPad)
// SimulaFranz_Monitor — valutazione allarmi (Kotlin puro, testabile sul Mac).
// Usa i limiti configurabili (SLSnapshot.alarmLimits) e lo stato dei sensori.
package it.simulafranz.core

import java.util.Locale

typealias SLAlarmLevel = Int
const val SLAlarmNone = 0
const val SLAlarmTechnical = 1   // sensore scollegato / segnale scarso (ciano, priorità bassa)
const val SLAlarmMedium = 2      // giallo
const val SLAlarmHigh = 3        // rosso

class SLAlarmResult(
    val level: Int,
    val messages: List<String>,          // ordinati per priorità
    val violated: Set<String>,           // parametri in allarme: hr, spo2, rr, etco2, nibp, art
    val severity: Map<String, Int>,      // parametro -> SLAlarmLevel (medio/alto)
    val levels: Map<String, Int> = emptyMap()   // ANDROID (IN-05/IN-06): messaggio -> priorità (registro degli allarmi)
)

/** ANDROID (IN-11): monitor-defibrillatori che leggono l'ECG anche dalle piastre (derivazione PIASTRE). */
fun SLPadsEcgLayout(layout: String?): Boolean = layout == "extrah" ||
    (layout != null && SFLayoutCatalogAll().any { it["id"] == layout && it["kind"] == "defib" })

/** ANDROID (IN-04): allarmi di priorità ALTA «letali» che interrompono la pausa audio quando compaiono durante la pausa. */
val SLPauseBreakingAlarms: Set<String> = setOf("FIBRILLAZIONE VENTRICOLARE", "TACHICARDIA VENTRICOLARE", "ASISTOLIA", "APNEA")

/** ANDROID (IN-04): sorveglia la pausa audio. Gli allarmi letali già presenti quando si silenzia restano silenziati; uno
 *  NUOVO (o che ricompare dopo essere cessato) interrompe la pausa: update() ritorna true e chi chiama riattiva il suono
 *  (mute_alarms false). Comportamento della maggior parte dei monitor e dei monitor-defibrillatori (IEC 60601-1-8). */
class SLAlarmPauseGuard {
    private var wasMuted = false
    private val known = HashSet<String>()
    fun update(muted: Boolean, r: SLAlarmResult): Boolean {
        val cur = r.messages.filter { SLPauseBreakingAlarms.contains(it) }.toSet()
        if (!muted) { wasMuted = false; known.clear(); return false }
        if (!wasMuted) { wasMuted = true; known.clear(); known.addAll(cur); return false }
        known.retainAll(cur)
        if (cur.any { !known.contains(it) }) { wasMuted = false; known.clear(); return true }
        return false
    }
}

object AlarmManager {

    /** FC contata dalle piastre (stesse regole di displayedHeartRate, che vale per gli elettrodi). */
    private fun padsHeartRate(s: SLSnapshot): Int {
        if (s.defib.paceCapture) return s.defib.paceRate
        if (s.rhythm == SLRhythmVentricularFib) return -1
        if (s.rhythm == SLRhythmAsystole) return 0
        return if (s.monHeartRate > 0) s.monHeartRate else -1
    }

    fun evaluate(snapshot: SLSnapshot): SLAlarmResult {
        val s = snapshot
        val high = ArrayList<String>(); val med = ArrayList<String>(); val tech = ArrayList<String>()
        val sev = LinkedHashMap<String, Int>()   // parametro -> gravità massima
        fun MARK(k: String, lvl: Int) { if ((sev[k] ?: 0) < lvl) sev[k] = lvl }
        // ANDROID (funzioni mancanti 22/11, IN-08): ritardo d'allarme per parametro (la condizione deve durare N s)
        fun H(key: String, cond: Boolean, param: String): Boolean = s.alarmGate?.held(key, cond, s.alarmNow, s.alarmDelay(param)) ?: cond
        // limite nuovo con ripiego sul vecchio (stati salvati prima dei limiti separati)
        fun LIM(k: String, old: String): Int = s.alarmLimits[k] ?: s.limit(old)
        if (s.simulationRunning) {
            // --- ECG: il monitor "vede" il ritmo con gli elettrodi collegati oppure, nei monitor-defibrillatori, dalle
            // allarmano FV/TV/asistolia dalla derivazione PIASTRE; «elettrodi staccati» solo se gli elettrodi erano in uso)
            val padsSrc = !s.ecgConnected && s.padsConnected && SLPadsEcgLayout(s.layout)
            if (!s.ecgConnected && !padsSrc) {
                if (s.ecgLeadsOffAlarm) tech.add("ECG ELETTRODI SCOLLEGATI")   // IN-01: mai collegati = ECG spento, nessun allarme
            } else {
                // ANDROID (IN-10/IN-18): un solo elettrodo staccato, tracciato disturbato
                s.ecgElectrodeOff?.let { if (s.ecgConnected) tech.add("ECG ${it.uppercase(Locale.ROOT)} SCOLLEGATO") }
                if (s.ecgConnected && s.ecgNoise > 0) tech.add("ECG DISTURBO/RUMORE")
                val r = s.rhythm
                if (r == SLRhythmVentricularFib) { high.add("FIBRILLAZIONE VENTRICOLARE"); MARK("hr", SLAlarmHigh) }
                else if (r == SLRhythmVentricularTachy || r == SLRhythmVentricularTachyPulse) { high.add("TACHICARDIA VENTRICOLARE"); MARK("hr", SLAlarmHigh) }
                else if (r == SLRhythmAsystole && !s.defib.paceCapture) { high.add("ASISTOLIA"); MARK("hr", SLAlarmHigh) }
                val hr = if (padsSrc) padsHeartRate(s) else s.monDisplayedHeartRate   // ANDROID (numerici realistici 22/11): il numero a schermo
                // regola dei layout personalizzati
                val pacedAlt = false
                if (s.alarmOn("hr") && hr > 0 && !pacedAlt && r != SLRhythmVentricularTachy && r != SLRhythmVentricularTachyPulse) {
                    val lo = s.limit("hr_low"); val hi = s.limit("hr_high")
                    if (H("hr_low", hr < lo, "hr")) { val crit = hr < lo - 10; (if (crit) high else med).add(String.format(Locale.US, "FC < %d", lo)); MARK("hr", if (crit) SLAlarmHigh else SLAlarmMedium) }
                    if (H("hr_high", hr > hi, "hr")) { val crit = hr > hi + 30; (if (crit) high else med).add(String.format(Locale.US, "FC > %d", hi)); MARK("hr", if (crit) SLAlarmHigh else SLAlarmMedium) }
                }
            }
            // --- SpO2 ---
            if (!s.spo2Connected) { if (s.spo2ProbeOffAlarm) tech.add("SpO2 SONDA SCOLLEGATA") }   // IN-01: mai collegata = spenta
            else if (s.spo2CuffOccluded) tech.add("SpO2: NIBP STESSO BRACCIO")
            else if (!s.spo2SignalValid) tech.add("SpO2 SEGNALE SCARSO")
            else if (s.spo2WeakSignal && s.pulsePresent) tech.add("SpO2 SEGNALE DEBOLE")   // ANDROID (perfusione 22/11): PAM critica
            // IN-07: il movimento marcato dà un artefatto (segnale instabile), non una «bassa perfusione»
            else if (s.patientMotion == 2 && s.spo2Displayable && s.systolic >= 70 && !s.spo2LowPerfusionFlag) tech.add("SpO2 ARTEFATTO DA MOVIMENTO")
            else if (s.lowPerfusion) tech.add("SpO2 BASSA PERFUSIONE")
            else if (s.spo2Acquiring && s.pulsePresent) tech.add("SpO2 RICERCA POLSO")   // ANDROID (IN-18)
            if (!s.spo2Connected || !s.spo2SignalValid) {}
            else if (!s.alarmOn("spo2")) {}
            else if (!s.pulsePresent) { med.add("SpO2 NESSUN POLSO"); MARK("spo2", SLAlarmMedium) }
            else if (s.spo2Acquiring || s.spo2WeakSignal) {}   // ANDROID (EM-05, perfusione): «---»: nessun numero, nessun allarme di limite
            else {
                val lo = s.limit("spo2_low")
                val sp = s.monSpo2                          // ANDROID (numerici realistici 22/11): il numero a schermo (con il ritardo)
                if (H("spo2_low", sp < lo, "spo2")) { val crit = sp < lo - 5; (if (crit) high else med).add(String.format(Locale.US, "SpO2 < %d", lo)); MARK("spo2", if (crit) SLAlarmHigh else SLAlarmMedium) }
                val sh = s.limit("spo2_high")                // ANDROID (IN-09): iperossia (post-ROSC), spento di serie
                if (H("spo2_high", sh > 0 && sp > sh, "spo2")) { med.add(String.format(Locale.US, "SpO2 > %d", sh)); MARK("spo2", SLAlarmMedium) }
            }
            // --- Respiro: FR dalla capnografia oppure dall'impedenza toracica degli elettrodi ECG ---
            if (s.etco2Connected || s.ecgConnected) {
                val rlo = s.limit("rr_low"); val rhi = s.limit("rr_high")
                if (!s.alarmOn("rr")) {}
                // ANDROID (IN-08/EM-18): APNEA dopo il ritardo d'apnea (di serie 20 s senza respiri né insufflazioni)
                else if (s.respRate == 0) { if (s.apneaSeconds >= s.alarmDelay("apnea")) { high.add("APNEA"); MARK("rr", SLAlarmHigh) } }
                else if (H("rr_low", s.monRespRate in 1 until rlo, "rr")) { med.add(String.format(Locale.US, "FR < %d", rlo)); MARK("rr", SLAlarmMedium) }
                else if (H("rr_high", s.monRespRate > rhi, "rr")) { med.add(String.format(Locale.US, "FR > %d", rhi)); MARK("rr", SLAlarmMedium) }
                if (s.etco2Connected && s.respRate > 0 && s.alarmOn("etco2")) {          // EtCO2 solo con il sensore CO2
                    val elo = s.limit("etco2_low"); val ehi = s.limit("etco2_high")
                    if (H("etco2_low", s.monEtco2 < elo, "etco2")) { med.add(String.format(Locale.US, "EtCO2 < %d", elo)); MARK("etco2", SLAlarmMedium) }
                    else if (H("etco2_high", s.monEtco2 > ehi, "etco2")) { med.add(String.format(Locale.US, "EtCO2 > %d", ehi)); MARK("etco2", SLAlarmMedium) }
                }
            }
            // --- Pressione: ART in continuo, NIBP all'ultima misura ---
            // ANDROID (IN-09): limiti ART e NIBP separati (di serie uguali ai «sys» di prima), PAM (spenta di serie)
            val plo = LIM("art_sys_low", "sys_low"); val phi = LIM("art_sys_high", "sys_high")
            val nlo = LIM("nibp_sys_low", "sys_low"); val nhi = LIM("nibp_sys_high", "sys_high")
            // ANDROID (IN-18): la linea a valle del bracciale non è smorzata ma NON PULSATILE
            if (s.artCuffOccluded) tech.add("ART NON PULSATILE: NIBP STESSO BRACCIO")
            // IN-02: linea arteriosa che era in uso e ora è staccata (trasduttore scollegato): allarme TECNICO a bassa priorità,
            // come i monitor veri (allarme tecnico «trasduttore scollegato»); mai collegata = nessun allarme (IN-01)
            else if (s.artLineOffAlarm) tech.add("ART TRASDUTTORE SCOLLEGATO")
            // ANDROID (IN-03): azzeramento, linea da azzerare, linea occlusa
            else if (s.artConnected && s.artZeroing) tech.add("ART AZZERAMENTO IN CORSO")
            else if (s.artConnected && s.artNeedsZero) tech.add("ART: AZZERARE")
            else if (s.artConnected && s.artOccluded) tech.add("ART: VERIFICARE LINEA")
            else if (s.artConnected && s.alarmOn("art") && !s.artFlushing) {
                val sys = if (s.monArtSystolic >= 0) s.monArtSystolic else s.artDisplayedSystolic   // ANDROID (numerici realistici 22/11)
                if (H("art_low", sys < plo, "art")) { val crit = sys < plo - 20; (if (crit) high else med).add(String.format(Locale.US, "ART SIST < %d", plo)); MARK("art", if (crit) SLAlarmHigh else SLAlarmMedium) }
                else if (H("art_high", sys > phi, "art")) { med.add(String.format(Locale.US, "ART SIST > %d", phi)); MARK("art", SLAlarmMedium) }
                val m = s.artDisplayedMap; val mlo = s.limit("art_map_low"); val mhi = s.limit("art_map_high")
                if (H("art_map_low", m >= 0 && mlo > 0 && m < mlo, "art")) { val crit = m < mlo - 15; (if (crit) high else med).add(String.format(Locale.US, "ART PAM < %d", mlo)); MARK("art", if (crit) SLAlarmHigh else SLAlarmMedium) }
                else if (H("art_map_high", m >= 0 && mhi > 0 && m > mhi, "art")) { med.add(String.format(Locale.US, "ART PAM > %d", mhi)); MARK("art", SLAlarmMedium) }
            }
            if (s.nibpLastSystolic >= 0 && s.alarmOn("nibp")) {
                if (s.nibpLastSystolic < nlo) { med.add(String.format(Locale.US, "NIBP SIST < %d", nlo)); MARK("nibp", SLAlarmMedium) }
                else if (s.nibpLastSystolic > nhi) { med.add(String.format(Locale.US, "NIBP SIST > %d", nhi)); MARK("nibp", SLAlarmMedium) }
                val m = s.meanArterialPressure; val mlo = s.limit("nibp_map_low"); val mhi = s.limit("nibp_map_high")
                if (m >= 0 && mlo > 0 && m < mlo) { med.add(String.format(Locale.US, "NIBP PAM < %d", mlo)); MARK("nibp", SLAlarmMedium) }
                else if (m >= 0 && mhi > 0 && m > mhi) { med.add(String.format(Locale.US, "NIBP PAM > %d", mhi)); MARK("nibp", SLAlarmMedium) }
            }
            // ANDROID (IN-13): PVC (solo allarmi tecnici)
            if (s.cvpConnected && s.artZeroing) {}
            else if (s.cvpConnected && s.cvpNeedsZero) tech.add("PVC: AZZERARE")
            else if (s.cvpLineOffAlarm) tech.add("PVC TRASDUTTORE SCOLLEGATO")
            // ANDROID (IN-09): temperatura (T1 e T2), limiti spenti di serie
            val tlo = s.limit("temp_low"); val thi = s.limit("temp_high")
            if (s.alarmOn("temp") && (tlo > 0 || thi > 0)) {
                for ((lab, on, v) in listOf(Triple("T1", s.tempConnected, s.monTemperatureTenths), Triple("T2", s.temp2Connected, s.monTemperature2Tenths))) {
                    if (!on) continue
                    if (H("${lab}_low", tlo > 0 && v < tlo, "temp")) { med.add(String.format(Locale.ITALY, "%s < %.1f", lab, tlo / 10.0)); MARK("temp", SLAlarmMedium) }
                    else if (H("${lab}_high", thi > 0 && v > thi, "temp")) { med.add(String.format(Locale.ITALY, "%s > %.1f", lab, thi / 10.0)); MARK("temp", SLAlarmMedium) }
                }
            }
            if (s.nibpStatus == SLNibpError) {
                tech.add(if (s.nibpError == "cuff") "NIBP BRACCIALE NON COLLEGATO"
                         else if (s.nibpError == "timeout") "NIBP TEMPO DI MISURA SUPERATO"      // ANDROID (IN-18)
                         else (if (s.nibpError == "motion") "NIBP ARTEFATTO DA MOVIMENTO" else "NIBP NESSUN POLSO RILEVATO"))
            }
        }
        val all = ArrayList<String>(high)
        all.addAll(med); all.addAll(tech)
        return SLAlarmResult(
            level = if (high.isNotEmpty()) SLAlarmHigh else if (med.isNotEmpty()) SLAlarmMedium else if (tech.isNotEmpty()) SLAlarmTechnical else SLAlarmNone,
            messages = all,
            violated = sev.keys.toSet(),
            severity = sev,
            levels = LinkedHashMap<String, Int>().also { m -> tech.forEach { m[it] = SLAlarmTechnical }; med.forEach { m[it] = SLAlarmMedium }; high.forEach { m[it] = SLAlarmHigh } }
        )
    }
}
