// Portato da src/SimulationState.m (monitor iPad)
// Parte 1 di 2: costanti, SLDefibInfo, SLSnapshot, SLNibpPoint (la classe SimulationState è in SimulationState.kt).
// SimulaFranz_Monitor — modello clinico SIMULATO (nessun dato reale).
package it.simulafranz.core

import java.util.Date

// Ritmi supportati (valori stringa usati dall'API)
const val SLRhythmSinus = "sinus"
const val SLRhythmSinusTachycardia = "sinus_tachycardia"
const val SLRhythmSinusBradycardia = "sinus_bradycardia"
const val SLRhythmAtrialFibrillation = "afib"
const val SLRhythmVentricularTachy = "vt"                // TV senza polso
const val SLRhythmVentricularTachyPulse = "vt_pulse"     // TV con polso
const val SLRhythmVentricularFib = "vf"
const val SLRhythmAsystole = "asystole"
const val SLRhythmPEA = "pea"

// Sensori collegabili al "paziente" (valori stringa usati dall'API)
const val SLSensorECG = "ecg"       // elettrodi ECG
const val SLSensorSpO2 = "spo2"     // sonda saturimetrica
const val SLSensorOxi = "oxi"       // sensore a dito del saturimetro palmare (SimulaFranz_Saturimetro)
const val SLSensorNIBP = "nibp"     // bracciale
const val SLSensorEtCO2 = "etco2"   // sensore/linea capnografia
const val SLSensorART = "art"       // trasduttore pressione arteriosa cruenta
const val SLSensorTemp = "temp"     // sonda di temperatura
const val SLSensorPads = "pads"     // piastre adesive del defibrillatore
const val SLSensorCVP = "cvp"       // ANDROID (funzioni mancanti 22/11, IN-13): trasduttore della pressione venosa centrale
const val SLSensorTemp2 = "temp2"   // ANDROID (funzioni mancanti 22/11, IN-13): sonda di temperatura centrale (esofagea/vescicale)

// Defibrillatore (cassetto sotto il monitor)
typealias SLDefibMode = Int
const val SLDefibOff = 0
const val SLDefibAED = 1
const val SLDefibManual = 2
const val SLDefibPacer = 3

typealias SLDefibCharge = Int
const val SLChargeNone = 0
const val SLChargeCharging = 1
const val SLChargeReady = 2

// Fasi del DAE (semiautomatico)
typealias SLAedPhase = Int
const val SLAedPads = 0        // "applicare le piastre"
const val SLAedAnalyzing = 1   // analisi del ritmo (non toccare il paziente)
const val SLAedCharging = 2    // shock consigliato, carica in corso
const val SLAedReady = 3       // premere il pulsante shock
const val SLAedCPR = 4         // RCP per N secondi, poi nuova analisi

typealias SLNibpStatus = Int
const val SLNibpIdle = 0
const val SLNibpMeasuring = 1
const val SLNibpComplete = 2
const val SLNibpError = 3

// Fasi del ciclo NIBP simulato (solo software: nessuna pompa reale)
typealias SLNibpPhase = Int
const val SLNibpPhaseNone = 0
const val SLNibpPhaseInflating = 1   // pompa in funzione
const val SLNibpPhaseDeflating = 2   // sgonfiaggio a gradini (valvola)
const val SLNibpPhaseDumping = 3     // scarico finale rapido
const val SLNibpPhaseDone = 4

// Defibrillatore: tempi (s)
internal const val kAedAnalysisTime = 8.0       // analisi del ritmo del DAE
internal const val kAedReadyTimeout = 60.0
internal const val kManualReadyTimeout = 60.0
internal fun SLChargeTime(j: Int): Double = 1.0 + j * 0.02   // 150 J ~4 s, 200 J ~5 s

// ANDROID (funzioni 21/11): TEMPI DEL DAE PER LAYOUT, in un solo punto. I primi sei valgono sempre (sono i tempi di
// Gli ultimi tre valgono SOLO con la configurazione defib device_realism = true (comportamenti più realistici,
// movimento ritarda l'analisi al massimo di 10 s. Per aggiungere un dispositivo basta una riga qui.
class SLLayoutTiming(
    val analysisS: Double,            // durata dell'analisi del ritmo
    val aedReadyTimeoutS: Double,     // DAE carico senza SHOCK: carica annullata
    val manualReadyTimeoutS: Double,  // manuale carico senza SHOCK: scarica interna
    val motionRepeatS: Double,        // ripetizione di «Movimento rilevato»
    val padsPoorRepeatS: Double,      // ripetizione di «Controllare le piastre»
    val cprFeedbackRepeatS: Double,   // ripetizione dei consigli sulla RCP
    val realAwaitAnalyze: Boolean,    // device_realism: il DAE non analizza da solo, chiede «Premere ANALIZZA»
    val realAnalyzeRepeatS: Double,   // device_realism: ripetizione di «Premere ANALIZZA»
    val realMotionMaxDelayS: Double)  // device_realism: ritardo massimo dell'analisi per movimento (0 = nessun limite)

fun SLLayoutTimingFor(layout: String?): SLLayoutTiming = when (layout) {
    else -> SLLayoutTiming(kAedAnalysisTime, kAedReadyTimeout, kManualReadyTimeout, 6.0, 8.0, 10.0, false, 0.0, 0.0)
}

// ANDROID (funzioni 21/11): allarmi tecnici simulati (distrattori) e testo mostrato
val SLTechnicalAlarmKinds: List<String> = listOf("ecg_leads_off", "spo2_probe_off", "nibp_cuff_error", "low_battery", "etco2_occlusion", "nibp_timeout")   // ANDROID (IN-18): + tempo di misura NIBP superato
fun SLTechnicalAlarmText(kind: String): String = when (kind) {
    "ecg_leads_off" -> "ECG ELETTRODI SCOLLEGATI"
    "spo2_probe_off" -> "SpO2 SONDA SCOLLEGATA"
    "nibp_cuff_error" -> "NIBP ERRORE BRACCIALE"
    "low_battery" -> "BATTERIA SCARICA"
    "etco2_occlusion" -> "CO2 LINEA OCCLUSA"
    "nibp_timeout" -> "NIBP TEMPO DI MISURA SUPERATO"
    else -> kind
}

// MARK: ANDROID (guasti 22/11) — malfunzionamenti simulati del dispositivo (RICERCA_MALFUNZIONAMENTI_DAE.md, cap. 4)
// Di serie nessuno. pads_unplugged e pads_poor_contact non sono stati nuovi: sono il cavo (set_pads_cable) e il contatto
// (set_pads_contact) che esistevano già; compaiono nell'elenco device_faults per comodità del controller.
val SLDeviceFaultKinds: List<String> = listOf("battery_low", "battery_replace", "battery_dead", "pads_expired", "pads_damaged",
    "pads_unplugged", "pads_poor_contact", "maintenance", "service_required", "temperature",
    "charge_fault", "shock_button_stuck", "memory_full")
internal val SLDeviceFaultBattery: List<String> = listOf("battery_low", "battery_replace", "battery_dead")   // esclusivi
internal const val kBatteryLowShocks = 6           // battery_low: scariche garantite, poi passa a battery_replace
internal const val kBatteryReplaceShocks = 2       // battery_replace: scariche possibili, poi la carica fallisce
internal const val kBatteryReplaceShutdownS = 180.0
internal const val kFaultSelfTestS = 3.0           // autotest breve dopo la sostituzione della batteria (spia gialla)
internal const val kFaultVoiceRepeatS = 30.0       // «Unità guasta…» ripetuto finché il dispositivo resta acceso

/** Nome del malfunzionamento (registro della sessione e controller). */
fun SLDeviceFaultName(kind: String): String = when (kind) {
    "battery_low" -> "Batteria quasi scarica"
    "battery_replace" -> "Batteria da sostituire (critica)"
    "battery_dead" -> "Batteria esaurita o assente"
    "pads_expired" -> "Piastre scadute"
    "pads_damaged" -> "Piastre danneggiate / confezione aperta"
    "pads_unplugged" -> "Cavo delle piastre staccato dal dispositivo"
    "pads_poor_contact" -> "Contatto scarso / impedenza alta"
    "maintenance" -> "Autotest fallito (non critico)"
    "service_required" -> "Guasto: chiamare l'assistenza"
    "temperature" -> "Temperatura fuori intervallo"
    "charge_fault" -> "Errore di carica/scarica interno"
    "shock_button_stuck" -> "Tasto di scarica bloccato"
    "memory_full" -> "Memoria piena"
    else -> kind
}

/** Azione reale che risolve il malfunzionamento (pulsante del controller, registro). */
fun SLDeviceFaultFix(kind: String): String = when (kind) {
    "battery_low", "battery_replace", "battery_dead" -> "batteria sostituita"
    "pads_expired", "pads_damaged" -> "piastre sostituite"
    "pads_unplugged" -> "cavo ricollegato"
    "pads_poor_contact" -> "piastre riapplicate"
    "temperature" -> "spostato in ambiente idoneo"
    "memory_full" -> "dati scaricati"
    else -> "sostituito / assistenza eseguita"
}

/** Il malfunzionamento ha senso nel layout? ICU = monitor da letto sempre in rete: solo batteria (come allarme), guasto e
 *  non hanno il chip, la scadenza non è rilevata. */
fun SLDeviceFaultApplies(layout: String?, kind: String): Boolean = when (layout) {
    "generic_icu" -> kind in listOf("battery_low", "battery_replace", "service_required", "charge_fault", "pads_unplugged", "pads_poor_contact")
    else -> !SFLayoutIsBedsideNoDefib(layout) && SLDeviceFaultKinds.contains(kind)
}

/** Frasi dei malfunzionamenti nello stile del layout: voce (testo pronunciato dal DAE) e scritta a schermo.
 *  key: svc, battery_low, battery_replace, battery_replaced, no_shock_battery, no_shock_internal, pads_expired,
 *  pads_damaged, maintenance, shock_stuck, memory, unit_ok. Testi italiani proposti in RICERCA_MALFUNZIONAMENTI_DAE.md. */
fun SLDeviceFaultVoice(layout: String?, key: String): String = when (layout) {
    else -> when (key) {
        "svc" -> "Unità guasta. Richiedere assistenza. Continuare la RCP."
        "battery_low" -> "Batteria scarica."
        "battery_replace" -> "Sostituire la batteria."
        "battery_replaced" -> "Batteria sostituita."
        "no_shock_battery" -> "Scarica non erogata. Sostituire la batteria. Continuare la RCP."
        "no_shock_internal" -> "Scarica non erogata. Continuare la RCP."
        "pads_expired" -> "Piastre scadute. Sostituire le piastre."
        "pads_damaged" -> "Sostituire le piastre."
        "maintenance" -> "Manutenzione necessaria. Continuare il soccorso."
        "shock_stuck" -> "Rilasciare il pulsante di scarica."
        else -> ""
    }
}

fun SLDeviceFaultScreen(layout: String?, key: String): String = when (key) {
    "svc" -> when (layout) {
        "extrah" -> "GUASTO TERAPIA — ASSISTENZA"
        "generic_icu" -> "DEFIBRILLATORE NON DISPONIBILE"
        else -> "UNITÀ GUASTA — RICHIEDERE ASSISTENZA"
    }
    "battery_low" -> when (layout) { "extrah" -> "BATTERIA BASSA"; else -> "BATTERIA SCARICA" }
    "battery_replace" -> when (layout) { "extrah" -> "SOSTITUIRE BATTERIA"; else -> "SOSTITUIRE LA BATTERIA" }
    "battery_replaced" -> "BATTERIA SOSTITUITA"
    "no_shock_battery" -> "SCARICA NON EROGATA — BATTERIA"
    "no_shock_internal" -> "SCARICA NON EROGATA"
    "pads_expired" -> "PIASTRE SCADUTE"
    "pads_damaged" -> "SOSTITUIRE LE PIASTRE"
    "maintenance" -> "MANUTENZIONE NECESSARIA"
    "shock_stuck" -> "RILASCIARE IL PULSANTE DI SCARICA"
    "memory" -> "MEMORIA PIENA"
    "temperature" -> "TEMPERATURA FUORI INTERVALLO"
    else -> ""
}

// Range di validazione (valori simulati)
internal const val kHRMin = 0
internal const val kHRMax = 300
internal const val kSpO2Min = 0
internal const val kSpO2Max = 100
internal const val kRRMin = 0
internal const val kRRMax = 60
internal const val kEtCO2Min = 0
internal const val kEtCO2Max = 150
internal const val kArtMsfp = 15.0   // ANDROID (ART RCP 22/11): pressione di riempimento sistemico in arresto senza compressioni (mmHg)
internal const val kSysMin = 0
internal const val kSysMax = 300
internal const val kDiaMin = 0
internal const val kDiaMax = 200

// Profilo NIBP (oscillometrico simulato)
internal const val kNibpStep = 8.0        // mmHg per gradino
internal const val kNibpStepTime = 1.1    // s per gradino
internal const val kNibpDumpTime = 1.6    // s scarico finale
internal const val kNibpLeakTime = 5.0    // s di tentativo con bracciale non collegato

// lround di C: arrotonda al più vicino, i mezzi lontano dallo zero
internal fun SLLround(d: Double): Int = (if (d < 0) -Math.floor(-d + 0.5) else Math.floor(d + 0.5)).toInt()

// lato del corpo di una posizione: "upper_arm_r" / "finger_r3" / "radial_l" -> "r" / "l"; null se centrale
internal fun SLSiteSide(site: Any?): String? {
    if (site !is String) return null
    for (tok in site.split("_")) {
        if (tok.length >= 1 && (tok.startsWith("r") || tok.startsWith("l")) &&
            (tok.length == 1 || tok.substring(1).all { it.isDigit() }))
            return tok.substring(0, 1)
    }
    return null
}

// posizione di serie quando una regia collega il sensore senza dire dove (uguale in tutte le regie)
internal fun SLDefaultSite(sensor: String): String? =
    mapOf("spo2" to "finger_l2", "nibp" to "upper_arm_r", "art" to "radial_l", "oxi" to "finger_r2")[sensor]

// la linea arteriosa è a valle di un bracciale sul braccio dello stesso lato (radiale, ulnare, brachiale)
internal fun SLArmArterySite(site: Any?): Boolean =
    site is String && (site.startsWith("radial") || site.startsWith("ulnar") || site.startsWith("brachial"))

// Risultato di SLSnapshot.nibpPhaseAt: fase, pressione (mmHg), indice del gradino di sgonfiaggio
data class SLNibpPoint(val phase: Int, val pressure: Double, val step: Int)

// Stato del defibrillatore nell'istantanea (immutabile)
class SLDefibInfo {
    var mode: SLDefibMode = SLDefibOff; internal set
    var padsConnected: Boolean = false; internal set
    var energy: Int = 0; internal set                    // J selezionati (manuale) o del DAE
    var charge: SLDefibCharge = SLChargeNone; internal set
    var chargeProgress: Double = 0.0; internal set       // 0..1 durante la carica
    var chargedEnergy: Int = 0; internal set
    var sync: Boolean = false; internal set
    var syncPending: Boolean = false; internal set       // shock sincronizzato in attesa dell'onda R
    var syncPendingSince: Double = 0.0; internal set
    var aedPhase: SLAedPhase = SLAedPads; internal set
    var aedRemaining: Int = 0; internal set              // s rimanenti della fase (RCP, analisi)
    var aedShockAdvised: Boolean = false; internal set
    var message: String = ""; internal set               // messaggio sul display (italiano)
    var promptSeq: Int = 0; internal set                 // cambia a ogni nuovo messaggio vocale
    var voicePrompt: String? = null; internal set        // testo da pronunciare (DAE), null = nessuno
    var chargeRefusedSeq: Int = 0; internal set          // ANDROID (suoni 25/11): cresce a ogni CARICA rifiutata per piastre non collegate
    var shockCount: Int = 0; internal set
    var lastShockAt: Double = 0.0; internal set          // 0 = nessuno (clock del modello)
    var lastShockEnergy: Int = 0; internal set
    var lastShockSynced: Boolean = false; internal set
    var sessionStart: Double = 0.0; internal set         // accensione del defibrillatore
    // pacing
    var paceRate: Int = 0; internal set                  // ppm
    var paceOutput: Int = 0; internal set                // mA
    var paceDemand: Boolean = false; internal set
    var paceRunning: Boolean = false; internal set
    var pacePaused: Boolean = false; internal set        // PAUSE tenuto premuto: stimola al 25% della frequenza
    var paceSetRate: Int = 0; internal set               // frequenza impostata (paceRate = effettiva)
    var paceLeadsOff: Boolean = false; internal set
    var paceActive: Boolean = false; internal set        // stimola davvero (piastre ok, non inibito)
    var paceCapture: Boolean = false; internal set       // cattura elettrica
    var paceMechanical: Boolean = false; internal set    // cattura meccanica (polso)
    var voiceEnabled: Boolean = false; internal set
    // ANDROID (funzioni 21/11)
    var padsCable: Boolean = true; internal set          // cavo delle piastre collegato al dispositivo
    var padsApplied: Boolean = false; internal set       // piastre sul paziente (padsConnected = piastre E cavo)
    var padsPediatric: Boolean = false; internal set     // placche pediatriche
    var aedChildMode: Boolean = false; internal set      // DAE in modalità bambino
    var aedEffectiveEnergy: Int = 0; internal set        // J che il DAE caricherà davvero (pediatrica se placche ped./bambino)
    var aedAwaitingAnalyze: Boolean = false; internal set // device_realism: il DAE aspetta il tasto ANALIZZA
    var syncMarkers: Boolean = false; internal set       // marker di sincronizzazione sul QRS visibili (manuale, SYNC, ECG disponibile)
    var paceFalseCapture: Boolean = false; internal set  // falsa cattura: spike sopra soglia ma nessuna cattura
    var events: List<Map<String, Any?>> = emptyList(); internal set   // registro eventi {time, elapsed, text, instructor, seq}
}

// Istantanea immutabile dello stato, usata da UI, suoni e waveform senza lock.
class SLSnapshot {
    var revision: Int = 0; internal set
    var rhythm: String = ""; internal set
    var heartRate: Int = 0; internal set          // FC impostata (ritmo)
    var spo2: Int = 0; internal set
    var spo2SignalValid: Boolean = false; internal set
    var respRate: Int = 0; internal set
    var etco2: Int = 0; internal set
    var systolic: Int = 0; internal set           // PA "vera" del paziente simulato
    var diastolic: Int = 0; internal set
    // sensori
    var ecgConnected: Boolean = false; internal set
    var spo2Connected: Boolean = false; internal set
    var nibpConnected: Boolean = false; internal set
    var etco2Connected: Boolean = false; internal set
    var artConnected: Boolean = false; internal set
    var tempConnected: Boolean = false; internal set
    var padsConnected: Boolean = false; internal set
    // ANDROID (difetti clinici 22/11, IN-01/IN-02): allarme tecnico «sensore staccato» SOLO se quel sensore era in uso in
    // questa sessione (collegato almeno una volta dopo reset_scenario) o se l'istruttore lo simula (set_technical_alarm).
    // Una sonda mai collegata = parametro spento: riquadro vuoto/«---», nessun allarme, nessun tono (come i monitor veri).
    var ecgLeadsOffAlarm: Boolean = false; internal set
    var spo2ProbeOffAlarm: Boolean = false; internal set
    var artLineOffAlarm: Boolean = false; internal set
    // ANDROID (EF-03): PEA a complessi stretti con P (ipovolemia / causa ostruttiva); false = larga e lenta (metabolica)
    var peaNarrow: Boolean = false; internal set
    // ANDROID (perfusione 22/11, richiesta utente + AE-16/OP-12): perfusione periferica dalla PAM (smussata, ~6 s): 1 = piena
    // (PAM >= 75), 0,6 a 65, 0,35 a 55, 0,18 a 45, 0,10 sotto 40; indice di perfusione (PI %) per i layout che lo mostrano;
    // segnale SpO2 troppo debole (PAM < 45 per più di 8 s, con la fisiologia automatica): «---», ricerca del polso
    var perfusionFraction: Double = 1.0; internal set
    var perfusionIndex: Double = 4.0; internal set
    var spo2WeakSignal: Boolean = false; internal set
    // Saturimetro palmare (secondo dispositivo, iPod touch): sensore a dito, tempo dal collegamento, bracciale sullo stesso braccio
    var oxiConnected: Boolean = false; internal set
    var oxiCuffOccluded: Boolean = false; internal set
    var oxiPower: Boolean = false; internal set
    var oxiOnline: Boolean = false; internal set
    var oxiSensorSince: Double = 0.0; internal set        // secondi dal collegamento del sensore (-1 = scollegato)
    var oxiVolume: Int = 0; internal set
    var oxiMutedRemaining: Int = 0; internal set
    var oxiLimits: Map<String, Int> = emptyMap(); internal set   // spo2_low, spo2_high, pr_low, pr_high
    // posizione dei sensori sul paziente (es. nibp -> "upper_arm_r", spo2 -> "finger_l2"); solo per i controller
    var sensorSites: Map<String, String> = emptyMap(); internal set
    var spo2SignalSetting: Boolean = false; internal set  // impostazione dell'istruttore (spo2SignalValid include l'occlusione)
    var spo2CuffOccluded: Boolean = false; internal set   // bracciale NIBP in misura sullo stesso braccio della sonda SpO2
    var artCuffOccluded: Boolean = false; internal set    // linea arteriosa a valle del bracciale in misura
    // SpO2: acquisizione dopo il collegamento della sonda (ritardo impostabile) e bassa perfusione forzata
    var spo2DelaySec: Int = 0; internal set
    var spo2Searching: Boolean = false; internal set      // prima metà del ritardo: niente onda, "ricerca"
    var spo2Acquiring: Boolean = false; internal set      // valore non ancora mostrato
    var spo2LowPerfusionFlag: Boolean = false; internal set
    var patientMotion: Int = 0; internal set              // movimenti del paziente: 0 no, 1 lievi, 2 marcati (artefatti)
    var patientMotionRemaining: Int = 0; internal set     // secondi rimanenti (0 = finché non viene fermato)
    var operatorTouch: Boolean = false; internal set      // un operatore sta toccando il paziente (PUSH dal controller)
    // temperatura (decimi di °C) e refill capillare (decimi di secondo)
    var temperatureTenths: Int = 0; internal set
    var crtTenths: Int = 0; internal set
    var crtAssessSeq: Int = 0; internal set               // cresce a ogni "fai valutare il refill" dell'istruttore
    var crtEnabled: Boolean = false; internal set         // refill visibile sul monitor (disattivabile)
    var layout: String = "generic_icu"; internal set
    var devicePowered: Boolean = false; internal set      // monitor/defibrillatore acceso (stato condiviso tra i layout)
    // defibrillatore
    var defib: SLDefibInfo = SLDefibInfo(); internal set
    // NIBP
    var nibpStatus: SLNibpStatus = SLNibpIdle; internal set
    var nibpError: String? = null; internal set           // null, "cuff", "no_pulse", "motion"
    var nibpLastSystolic: Int = 0; internal set           // -1 = nessuna misura
    var nibpLastDiastolic: Int = 0; internal set
    var nibpStartedAt: Double = 0.0; internal set
    var nibpMeasuredAt: Date? = null; internal set
    // audio / allarmi
    var alarmsMuted: Boolean = false; internal set
    var alarmsMutedRemaining: Int = 0; internal set       // secondi al ritorno degli allarmi
    var qrsBeepEnabled: Boolean = false; internal set
    var tabletVolume: Int = -1; internal set              // ANDROID (guscio 25/11): volume di sistema del tablet, percento (-1 = ignoto)
    var tabletVolumeSeq: Int = 0; internal set            // cresce a ogni comando set_tablet_volume
    var volume: Int = 0; internal set                     // 0..100
    var simulationRunning: Boolean = false; internal set
    var scenarioTime: Double = 0.0; internal set          // secondi di scenario (esclude le pause; 0 dopo reset)
    // limiti di allarme (chiavi: hr_low, hr_high, spo2_low, rr_low, rr_high,
    // etco2_low, etco2_high, sys_low, sys_high)
    var alarmLimits: Map<String, Int> = emptyMap(); internal set
    // allarme attivo per parametro (hr, spo2, art, nibp, rr, etco2): se false non dà allarmi di limite
    // (i ritmi letali FV/TV/asistolia suonano comunque)
    var alarmEnabled: Map<String, Boolean> = emptyMap(); internal set
    // NIBP automatica: intervallo in minuti (0 = spenta) e istante della prossima misura
    var nibpIntervalMin: Int = 0; internal set
    var nibpNextAt: Double = 0.0; internal set
    // reazioni automatiche (arresto -> SpO2/FR/EtCO2 a 0; ROSC -> recupero)
    var autoPhysiology: Boolean = false; internal set
    var cprActive: Boolean = false; internal set          // RCP (compressioni toraciche) in corso
    var cprRateQuality: String = "ok"; internal set        // ANDROID (21/11): ok | slow | fast (scelta dall'istruttore)
    var cprDepthQuality: String = "ok"; internal set       // ANDROID (21/11): ok | shallow | deep
    // Ventilazione manuale (RCP in corso o apnea): ogni insufflazione la segnala l'istruttore ("ventilate") quando i
    // partecipanti ventilano; solo allora il capnogramma mostra un'onda e compare l'EtCO2. FR = insufflazioni nell'ultimo minuto.
    var ventilationManual: Boolean = false; internal set
    var breathTimes: List<Double> = emptyList(); internal set   // istanti (clock) delle insufflazioni dell'ultimo minuto
    var respRateSet: Double = 0.0; internal set           // FR impostata (in ventilazione manuale respRate è quella misurata)
    var etco2Set: Int = 0; internal set                   // EtCO2 impostata (in ventilazione manuale etco2 può essere quella dell'ultima insufflazione)
    // ANDROID (EtCO2 RCP 22/11): capnografia in RCP (vedi SimulationState, «modello EtCO2 in RCP»)
    var breathCo2: List<Double> = emptyList(); internal set      // picco di CO2 (mmHg) di ciascuna insufflazione, parallelo a breathTimes (vuoto = usa etco2)
    var co2CompressionAmp: Double = 0.0; internal set            // ampiezza (mmHg) delle oscillazioni da compressione sul capnogramma (0 = nessuna)
    var cprCompressionRate: Double = 110.0; internal set         // compressioni/min per gli artefatti (110 corretta, 80 lenta, 140 veloce)
    var etco2CprMode: String? = null; internal set               // "auto" (modello) | "instructor" (valore forzato) | null (modello non attivo)
    // ANDROID (RCP dettagliata 22/11): altre dimensioni della qualità (di serie "ok") e affaticamento 0..1
    var cprRecoilQuality: String = "ok"; internal set            // ok | incomplete (appoggio sul torace)
    var cprHandsQuality: String = "ok"; internal set             // ok | wrong (mani troppo in basso / di lato)
    var cprFatigue: Double = 0.0; internal set                   // 0 = soccorritore fresco, 1 = affaticato (~150 s di compressioni)
    // ANDROID (ART RCP 22/11): pressione arteriosa cruenta in arresto (0 = paziente con polso)
    var artCprBase: Double = 0.0; internal set        // mmHg all'istante artCprBaseAt: diastolica di rilasciamento o riempimento
    var artCprBaseTarget: Double = 0.0; internal set  // valore verso cui tende (esponenziale con artCprBaseTau)
    var artCprBaseTau: Double = 1.0; internal set
    var artCprBaseAt: Double = 0.0; internal set      // istante (clock) di artCprBase
    var artCprAmp: Double = 0.0; internal set         // ampiezza dell'onda di compressione (picco - rilasciamento); 0 = nessuna compressione
    var artCprPaused: Boolean = false; internal set   // simulazione in pausa: la curva non evolve
    // transizioni attive: param -> {to, remaining_s}; catena: {active, name, step, steps, label, phase, remaining_s}
    // ANDROID (transizioni ritmo 22/11): preludio del cambio di ritmo in corso (null = nessuno). Durante il preludio `rhythm`
    // resta quello di partenza (polso, allarmi, DAE): il motore disegna i battiti del piano e alla fine il cuore applica `to`.
    var rhythmOnset: SLOnsetPlan? = null; internal set
    var rhythmOnsetStartAt: Double = 0.0; internal set     // istante (clock) dell'inizio del preludio (fermo in pausa)
    var rhythmOnsetOffset: Double = 0.0; internal set      // secondi trascorsi dall'inizio (tempo clinico)
    // ANDROID (ampiezza FV 22/11): ampiezza picco-picco tipica della FV (mV) e fattore della frequenza dominante (1 = ~4,7 Hz)
    var vfAmplitudeMv: Double = 1.0; internal set
    // ANDROID (12 derivazioni 22/11): morfologia dell'ECG, blocco AV in atto adesso (dipende dal ritmo), frequenza degli atri
    var ecgMorph: SLEcgMorph = SLEcgMorph.NORMAL; internal set
    var avBlockActive: Boolean = false; internal set
    var atrialRate: Int = 0; internal set
    var vfFrequencyScale: Double = 1.0; internal set
    var vfAmplitudeMode: String = "auto"; internal set     // auto (evolve da sola) | instructor (valore impostato)
    // ANDROID (numerici realistici 22/11): i numeri A SCHERMO (misurati come un monitor vero: medie, ritardi, atto per atto).
    // I layout mostrano questi; heartRate, spo2... restano i valori veri (regia, registro, test).
    var monHeartRate: Int = 0; internal set
    var monPulseRate: Int = 0; internal set
    var monSpo2: Int = 0; internal set
    var monEtco2: Int = 0; internal set
    var monRespRate: Int = 0; internal set
    var monArtSystolic: Int = -1; internal set
    var monArtDiastolic: Int = -1; internal set
    var monTemperatureTenths: Int = 0; internal set
    var transitions: Map<String, Any?> = emptyMap(); internal set
    var chain: Map<String, Any?> = emptyMap(); internal set
    // ANDROID (funzioni 21/11)
    var cprMetrics: Map<String, Any?> = emptyMap(); internal set     // {ccf_percent, arrest_s, cpr_s, last_pre_shock_s, last_post_shock_s, shocks:[...]}
    var autoAction: Map<String, Any?>? = null; internal set          // {seq, text, until, remaining_s} oppure null
    var technicalAlarms: List<String> = emptyList(); internal set    // allarmi tecnici simulati attivi (SLTechnicalAlarmKinds)
    var vehicleMotion: Boolean = false; internal set                 // ambulanza in movimento (artefatto ECG come il movimento lieve)
    var scenarioEnded: Boolean = false; internal set                 // ANDROID (fine scenario 22/11): end_scenario (in pausa, schermata «Scenario terminato»)
    var scenarioStarted: Boolean = false; internal set               // ANDROID (avvio scenario 22/11): start_scenario dato (falso = PRONTO: timer fermo, registro fermo)
    var patientMotionSet: Int = 0; internal set                      // movimento del paziente impostato (patientMotion include il mezzo)
    // ANDROID (guasti 22/11): malfunzionamenti simulati del dispositivo (di serie nessuno: tutto come prima)
    var deviceFaults: List<String> = emptyList(); internal set       // kind attivi (SLDeviceFaultKinds), anche pads_unplugged/pads_poor_contact
    var deviceReadiness: String = "ready"; internal set              // spia da spento: ready | attention | fault | dark
    var deviceSelfTest: Boolean = false; internal set                // autotest breve dopo la sostituzione della batteria (3 s)
    var wallClockSynced: Boolean = false; internal set               // ANDROID (ora 22/11): ora del controller ricevuta (set_wall_clock)
    var deviceFaultBeep: Boolean = true; internal set                // bip da spento sì/no (set_device_fault_beep)
    var faultBeepIntervalS: Int = 0; internal set                    // bip da spento ADESSO: ogni N s (0 = nessun bip)
    var faultBeepDouble: Boolean = false; internal set               // doppio bip (guasto grave)
    var batteryShocksLeft: Int = -1; internal set                    // scariche residue della batteria simulata (-1 = non simulata)
    // ANDROID (funzioni mancanti 22/11) — linea arteriosa (IN-03/OP-04), PVC e T2 (IN-13), ventilazione (CC-04/AE-12/OP-05),
    // ritardi d'allarme (IN-08), elettrodi (IN-10). Di serie tutto come prima.
    var artDamping: Int = 0; internal set                            // 0 ottimale, 1 sovrasmorzata, 2 sottosmorzata
    var artOffset: Double = 0.0; internal set                        // mmHg aggiunti dal livello del trasduttore (curve e numeri)
    var artOccluded: Boolean = false; internal set                   // linea occlusa (rubinetto chiuso, coagulo): non pulsatile
    var artZeroing: Boolean = false; internal set                    // azzeramento in corso (linea aperta all'aria: 0 mmHg)
    var artNeedsZero: Boolean = false; internal set                  // collegata ma non ancora azzerata (con zero obbligatorio): «---»
    var artZeroingUntil: Double = 0.0; internal set                  // istante (clock) della fine dell'azzeramento
    var artFlushAt: Double = 0.0; internal set                       // istante (clock) dell'ultima prova di lavaggio rapido (0 = mai)
    var artZeroedAtMs: Long = 0L; internal set                       // ora (epoch ms) dell'ultimo azzeramento (0 = mai)
    var artZeroRequired: Boolean = false; internal set
    var artLevelCm: Int = 0; internal set
    val artFlushing: Boolean get() = artFlushAt > 0 && clockNow - artFlushAt < 3.0
    var cvpConnected: Boolean = false; internal set
    var cvp: Int = 6; internal set                                   // PVC media «vera» (mmHg)
    var cvpWave: String = "normal"; internal set                     // normal | large_v (insufficienza tricuspidale) | tamponade (y smussata)
    var cvpNeedsZero: Boolean = false; internal set
    var cvpLineOffAlarm: Boolean = false; internal set
    var temp2Connected: Boolean = false; internal set
    var temperature2Tenths: Int = 372; internal set                  // temperatura centrale (decimi di °C)
    var monCvp: Int = -1; internal set                               // PVC a schermo (-1 = «---»)
    var monTemperature2Tenths: Int = 0; internal set
    var ventMode: String = "spontaneous"; internal set               // spontaneous | ventilator | bag
    var ventRate: Int = 12; internal set; var ventTidal: Int = 450; internal set
    var ventDisconnected: Boolean = false; internal set
    var ventStartAt: Double = 0.0; internal set                      // istante (clock) di riferimento della fase del ventilatore
    var ppv: Double = 0.0; internal set                              // variazione respiratoria della pressione (0..0,3) col ventilatore
    var ppvAuto: Boolean = true; internal set
    var advancedAirway: Boolean = false; internal set
    var ecgElectrodeOff: String? = null; internal set               // ra | la | ll | v (un solo elettrodo staccato) | null
    var ecgNoise: Int = 0; internal set                              // 0 no, 1 tracciato disturbato (gel secco, 50 Hz), 2 forte
    var limbReversal: Boolean = false; internal set                  // inversione dei cavi delle braccia (RA/LA)
    var alarmDelays: Map<String, Int> = emptyMap(); internal set     // secondi per parametro (SLDefaultAlarmDelays)
    var alarmGate: SLAlarmGate? = null; internal set                 // condizioni in corso (dello stato: una per monitor)
    var alarmNow: Double = 0.0; internal set                         // tempo clinico (per i ritardi)
    var clockNow: Double = 0.0; internal set                         // clock dell'istantanea
    var apneaSeconds: Double = 0.0; internal set                     // secondi senza respiri (spontanei o insufflazioni)
    var techResolvable: Boolean = false; internal set                // IN-12: i partecipanti risolvono il tecnico col gesto
    var spo2SignalPoorSet: Boolean = false; internal set
    fun alarmDelay(k: String): Int = alarmDelays[k] ?: 0
    /** il malfunzionamento è attivo e ha senso nel layout attivo */
    fun fault(kind: String): Boolean = deviceFaults.contains(kind) && SLDeviceFaultApplies(layout, kind)
    // parametri del ciclo NIBP fissati all'avvio
    internal var nibpTarget: Double = 0.0
    internal var nibpEnd: Double = 0.0
    internal var nibpCuffOK: Boolean = false

    fun limit(key: String): Int = alarmLimits[key] ?: 0

    // PR periferica (FA rapida: deficit di polso)
    val pulseRate: Int
        get() {
            if (defib.paceMechanical) return defib.paceRate   // pacing con cattura meccanica
            // FA rapida: alcuni battiti non generano un polso periferico valido (deficit di polso)
            if (rhythm == SLRhythmAtrialFibrillation && heartRate > 100)
                return SLLround(heartRate * (1.0 - Math.min(0.25, (heartRate - 100) / 400.0)))
            return heartRate
        }

    fun alarmOn(param: String): Boolean = alarmEnabled[param] ?: true

    // derivati
    // Polso: dal ritmo; durante il pacing con cattura decide la cattura meccanica (QRS stimolati che generano pressione o no)
    val pulsePresent: Boolean get() = if (defib.paceCapture) defib.paceMechanical else SimulationState.rhythmHasPulse(rhythm)
    // ritmo con complessi QRS
    val ecgOrganized: Boolean get() = !(rhythm == SLRhythmVentricularFib || rhythm == SLRhythmAsystole)
    // sonda + segnale + polso
    val spo2Displayable: Boolean get() = spo2Connected && spo2SignalValid && pulsePresent && !spo2Acquiring && !spo2WeakSignal
    // ipotensione marcata (sist < 70): SpO2 con "?"
    val lowPerfusion: Boolean get() = spo2Displayable && (perfusionFraction < 0.36 || spo2LowPerfusionFlag || patientMotion == 2)   // ANDROID (perfusione 22/11): PAM < ~55
    // FC da ECG, -1 = "---"
    val displayedHeartRate: Int
        get() {
            if (!ecgConnected) return -1                       // elettrodi staccati
            if (defib.paceCapture) return defib.paceRate       // il monitor conta i battiti stimolati
            if (rhythm == SLRhythmVentricularFib) return -1
            if (rhythm == SLRhythmAsystole) return 0
            return heartRate
        }
    // ANDROID (numerici realistici 22/11): FC A SCHERMO (stesse regole di displayedHeartRate, numero misurato dagli R-R)
    val monDisplayedHeartRate: Int
        get() {
            val d = displayedHeartRate
            return if (d <= 0 || defib.paceCapture) d else if (monHeartRate > 0) monHeartRate else -1   // -1: «---» finché non conta i battiti
        }
    // ANDROID (ampiezza FV 22/11): classe dell'ampiezza della FV (null se il ritmo non è FV)
    val vfAmplitudeClass: String?
        get() = if (rhythm != SLRhythmVentricularFib) null else SLVfAmplitudeClass(vfAmplitudeMv)
    // ultima NIBP, -1 se assente
    val meanArterialPressure: Int
        get() {
            if (nibpLastSystolic < 0 || nibpLastDiastolic < 0) return -1
            return (nibpLastSystolic + 2 * nibpLastDiastolic) / 3
        }
    // -1 = "--"
    // ANDROID (funzioni mancanti 22/11, IN-03): numeri ART senza gli effetti della linea (cuore, regia)
    val artRawSystolic: Int
        get() {
            if (!artConnected) return -1
            if (artCuffOccluded) return 26                     // bracciale chiuso a monte: curva piatta, nessuna pulsatilità
            // ANDROID (ART RCP 22/11): in arresto i numeri seguono la curva: con le compressioni picco/rilasciamento,
            // senza quasi piatta verso la pressione di riempimento sistemico
            if (pulsePresent) return systolic
            return if (artCprAmp > 0) SLLround(artCprBase + artCprAmp) else SLLround(artCprBase) + 1
        }
    val artRawDiastolic: Int
        get() {
            if (!artConnected) return -1
            if (artCuffOccluded) return 24
            if (pulsePresent) return diastolic
            return Math.max(0, if (artCprAmp > 0) SLLround(artCprBase) else SLLround(artCprBase) - 1)
        }
    /** ANDROID (IN-03): niente numeri ART (azzeramento in corso o linea da azzerare) */
    val artNoNumbers: Boolean get() = artConnected && (artZeroing || artNeedsZero)
    // ANDROID (funzioni mancanti 22/11, IN-03/OP-04): i numeri come li misura il trasduttore (smorzamento, livello, occlusione)
    val artDisplayedSystolic: Int
        get() {
            if (!artConnected) return -1
            if (artNoNumbers) return -1
            if (artCuffOccluded || (artDamping == 0 && !artOccluded && artOffset == 0.0)) return artRawSystolic
            return SLArtLineMath.apply(artRawSystolic, artRawDiastolic, artDamping, artOccluded, artOffset).first
        }
    val artDisplayedDiastolic: Int
        get() {
            if (!artConnected) return -1
            if (artNoNumbers) return -1
            if (artCuffOccluded || (artDamping == 0 && !artOccluded && artOffset == 0.0)) return artRawDiastolic
            return SLArtLineMath.apply(artRawSystolic, artRawDiastolic, artDamping, artOccluded, artOffset).second
        }
    /** ANDROID (IN-09): PAM a schermo della linea arteriosa (-1 = «---») */
    val artDisplayedMap: Int
        get() {
            val s = if (monArtSystolic >= 0) monArtSystolic else artDisplayedSystolic
            val d = if (monArtDiastolic >= 0) monArtDiastolic else artDisplayedDiastolic
            return if (s < 0 || d < 0 || artNoNumbers) -1 else SLLround((s + 2.0 * d) / 3.0)
        }
    /** ANDROID (OP-05): PPV a schermo (%), solo col ventilatore e la linea arteriosa (-1 = non mostrata) */
    val ppvShown: Int get() = if (ventMode == "ventilator" && !ventDisconnected && artConnected && pulsePresent && !artNoNumbers && !artOccluded) SLLround(ppv * 100) else -1

    // Profilo del bracciale al tempo t: fase, pressione (mmHg), indice del gradino di sgonfiaggio.
    fun nibpPhaseAt(t: Double): SLNibpPoint {
        var pressure = 0.0
        var step = -1
        if (nibpStatus != SLNibpMeasuring) return SLNibpPoint(SLNibpPhaseNone, pressure, step)
        var el = t - nibpStartedAt
        if (el < 0) el = 0.0
        if (!nibpCuffOK) {                                     // bracciale staccato: la pompa gira a vuoto
            pressure = Math.min(4.0, el * 2.0)
            return SLNibpPoint(if (el < kNibpLeakTime) SLNibpPhaseInflating else SLNibpPhaseDone, pressure, step)
        }
        val inflate = 2.0 + nibpTarget / 40.0
        if (el < inflate) {
            pressure = nibpTarget * Math.pow(el / inflate, 0.85)
            return SLNibpPoint(SLNibpPhaseInflating, pressure, step)
        }
        el -= inflate
        val steps = Math.ceil((nibpTarget - nibpEnd) / kNibpStep).toInt()
        if (el < steps * kNibpStepTime) {
            val k = Math.floor(el / kNibpStepTime).toInt()
            val within = el - k * kNibpStepTime
            val hi = nibpTarget - k * kNibpStep
            val lo = Math.max(nibpEnd, hi - kNibpStep)
            val p = if (within < 0.18) hi - (hi - lo) * (within / 0.18) else lo   // la valvola scarica in ~180 ms
            pressure = p
            step = k
            return SLNibpPoint(SLNibpPhaseDeflating, pressure, step)
        }
        el -= steps * kNibpStepTime
        if (el < kNibpDumpTime) {
            pressure = nibpEnd * Math.exp(-el * 3.0)
            return SLNibpPoint(SLNibpPhaseDumping, pressure, step)
        }
        return SLNibpPoint(SLNibpPhaseDone, pressure, step)
    }
}

internal fun SLNibpTotalDuration(s: SLSnapshot): Double {
    if (!s.nibpCuffOK) return kNibpLeakTime
    val inflate = 2.0 + s.nibpTarget / 40.0
    val steps = Math.ceil((s.nibpTarget - s.nibpEnd) / kNibpStep)
    return inflate + steps * kNibpStepTime + kNibpDumpTime
}
