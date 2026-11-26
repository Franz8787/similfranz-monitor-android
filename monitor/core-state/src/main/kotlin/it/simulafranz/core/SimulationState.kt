// Portato da src/SimulationState.m (monitor iPad)
// Parte 2 di 2: la classe SimulationState (costanti, SLDefibInfo e SLSnapshot sono in SimulationStateSnapshot.kt).
// SimulaFranz_Monitor — modello clinico SIMULATO (nessun dato reale).
// Thread-safe: tutti gli accessi sono serializzati con synchronized(this).
package it.simulafranz.core

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.Random

// Errore di validazione di un comando: sull'iPad è il parametro in uscita `error` + ritorno NO.
// Qui i validatori lo lanciano e applyCommand / validateTransition lo trasformano nel messaggio di ritorno.
private class SLCmdError(message: String) : RuntimeException(message, null, false, false)

private fun SLFail(msg: String): Nothing = throw SLCmdError(msg)

private fun SLDefibDefaultConfig(): Map<String, Any?> = linkedMapOf(
    "shocks_to_convert" to 1L, "min_energy_j" to 120L, "post_shock_rhythm" to "sinus",
    "cv_shocks_to_convert" to 1L, "cv_min_energy_j" to 100L, "cv_post_rhythm" to "sinus",
    "unsync_shock_vf" to true, "sync_stays_on" to false,
    "capture_threshold_ma" to 70L, "capture_possible" to true, "mechanical_capture" to true,
    "aed_energy_j" to 150L, "aed_cpr_s" to 120L, "voice_prompts" to true,
    "outcome_mode" to "instructor",   // instructor (di serie) = dopo ogni shock, anche sincronizzato, decide l'istruttore; auto = regole qui sopra
    // ANDROID (funzioni 21/11): tutte facoltative, i valori di serie lasciano il comportamento di prima.
    // outcome_sequence (lista per scarica: "convert" | "stay" | ritmo) NON c'è di serie: si usa shocks_to_convert.
    "aed_ped_energy_j" to 50L,        // energia del DAE con placche pediatriche o in modalità bambino
    "device_realism" to false,
    "auto_advance" to false,          // avanzamenti automatici annullabili (cardioversione efficace -> sinusale, banner «Annulla»)
    "false_capture" to false)         // pacing: spike visibili ma nessuna cattura anche sopra soglia

// ANDROID (analisi 21/11, 1.7): energia di serie della PRIMA scarica del DAE per layout (adulto). Le scale crescenti
// le applicano le viste dei layout; al cambio di layout e al reset si riparte da qui, mai dall'energia del layout di prima.
private fun SLAedDefaultEnergy(layout: String): Int = when (layout) {
    else -> 150
}
private const val SLManualDefaultEnergy = 150
// ANDROID (difetti clinici 22/11, AE-06/AE-07): energia manuale di serie e limiti del dispositivo per layout (di serie:
// fino a 360 J). Fuori dai limiti: energia rifiutata, frequenza/corrente del pacer portate al limite.
private fun SLManualDefaultEnergyFor(layout: String): Int = when (layout) {
    else -> SLManualDefaultEnergy
}
private fun SLMaxEnergy(layout: String): Int = 360
private fun SLPacerRateRange(layout: String): IntRange = 30..210
private fun SLPacerMaxMa(layout: String): Int = 200

// Parametri modificabili con transizioni temporizzate e relativi range
private fun SLParamRanges(): Map<String, List<Int>> = linkedMapOf(
    "heart_rate_bpm" to listOf(0, 300), "spo2_percent" to listOf(0, 100), "respiratory_rate_rpm" to listOf(0, 60),
    "etco2_mmhg" to listOf(0, 150), "systolic_mmhg" to listOf(0, 300), "diastolic_mmhg" to listOf(0, 200),
    "cvp_mmhg" to listOf(0, 40))   // ANDROID (funzioni mancanti 22/11, IN-13): PVC in rampa come gli altri parametri

private fun SLOxiLimitRanges(): Map<String, List<Int>> = linkedMapOf(
    "spo2_low" to listOf(20, 99), "spo2_high" to listOf(21, 100), "pr_low" to listOf(20, 240), "pr_high" to listOf(25, 250))

private fun SLLimitRanges(): Map<String, List<Int>> = linkedMapOf(
    "hr_low" to listOf(15, 250), "hr_high" to listOf(20, 300), "spo2_low" to listOf(50, 99),
    "rr_low" to listOf(1, 50), "rr_high" to listOf(5, 60), "etco2_low" to listOf(1, 90), "etco2_high" to listOf(10, 150),
    "sys_low" to listOf(30, 250), "sys_high" to listOf(50, 300))

private fun SLRhythmName(r: String): String {
    val n = mapOf(SLRhythmSinus to "sinusale", SLRhythmSinusTachycardia to "tachicardia sinusale",
                  SLRhythmSinusBradycardia to "bradicardia sinusale", SLRhythmAtrialFibrillation to "FA",
                  SLRhythmVentricularTachyPulse to "TV con polso", SLRhythmVentricularTachy to "TV senza polso",
                  SLRhythmVentricularFib to "FV", SLRhythmAsystole to "asistolia", SLRhythmPEA to "PEA")
    return n[r] ?: r
}

// Validatori. Un Boolean non è un Number (come NSNumber booleano / numerico sull'iPad).
private fun SLIntValue(v: Any?, min: Int, max: Int, name: String): Int {
    if (v !is Number) SLFail("$name: numeric value required")
    val d = v.toDouble()
    if (d != d || d < min || d > max) {   // d != d -> NaN
        SLFail("$name: value out of range [$min..$max]")
    }
    return SLLround(d)
}

private fun SLBoolValue(v: Any?, name: String): Boolean {
    if (v !is Boolean) SLFail("$name: boolean value required")
    return v
}

private fun SLNibpStatusName(s: SLNibpStatus): String = when (s) {
    SLNibpMeasuring -> "measuring"
    SLNibpComplete -> "complete"
    SLNibpError -> "error"
    else -> "idle"
}

// rampa lineare di un parametro: {from,to,start,dur}
private class SLRamp(val from: Int, val to: Int, val start: Double, val dur: Double)
// rampa programmata nel futuro: {at,key,to,dur}
private class SLScheduled(val at: Double, val key: String, val to: Int, val dur: Double)
// risultato di pacingActive: attivo, cattura elettrica, cattura meccanica (+ ANDROID 21/11: falsa cattura)
private class SLPacing(val active: Boolean, val capture: Boolean, val mechanical: Boolean, val falseCapture: Boolean = false)

// ANDROID (funzioni 21/11): finestra per annullare un avanzamento automatico (s di orologio reale)
private const val kAutoActionWindow = 5.0
// ANDROID (funzioni 21/11): versione del formato di exportState
private const val kExportFormat = 1L
// ANDROID (ora 22/11): limiti di set_wall_clock (1/1/2000 – 1/1/2100, ms epoch)
private const val kWallClockMin = 946684800000.0
private const val kWallClockMax = 4102444800000.0
// ANDROID (audit 26/11, PERF-F5): nomi delle categorie RCP dell'istantanea, costanti (prima una listOf nuova a ogni snapshot)
private val kCprRateQ = arrayOf("ok", "slow", "fast"); private val kCprDepthQ = arrayOf("ok", "shallow", "deep")
private val kCprRecoilQ = arrayOf("ok", "incomplete"); private val kCprHandsQ = arrayOf("ok", "wrong")

class SimulationState(deviceId: String? = "monitor-01") {

    val deviceId: String = deviceId ?: "monitor-01"   // "monitor-01"

    // Accensione del dispositivo simulato: condivisa tra tutti i layout, parte spenta
    // e torna spenta con reset_scenario (la imposta la UI, non l'API).
    @Volatile var devicePowered: Boolean = false
        // ANDROID (guasti 22/11): con la batteria simulata esaurita (battery_dead) il dispositivo non si accende
        set(v) { if (v && powerBlocked) return; if (!v) powerBy = "partecipanti"; field = v }
    @Volatile private var powerBlocked = false
    // ANDROID (BugFixController 5): chi ha acceso il dispositivo — "partecipanti" (tasto del layout) o "istruttore"
    // (comando set_device_power dal controller, che fa la stessa cosa del tasto di accensione del layout attivo)
    @Volatile var powerBy: String = "partecipanti"

    var muteDuration: Double = 20.0      // default 20 s
        set(v) { field = if (v.isFinite() && v > 0) v else 20.0 }   // ANDROID (21/11): mai 0 o NaN

    // Clock iniettabile per i test (di serie: tempo monotono, come systemUptime sull'iPad)
    @Volatile var clock: () -> Double = { System.nanoTime() / 1e9 }

    private var revision = 0
    private var rhythm = SLRhythmSinus
    private var hr = 0; private var spo2 = 0; private var rr = 0; private var etco2 = 0
    private var sys = 0; private var dia = 0; private var volume = 0
    private var spo2Valid = false; private var muted = false; private var running = false; private var qrsBeep = false
    private var scenAccum = 0.0                          // secondi di scenario accumulati nelle fasi "in corsa"
    private var scenSince = 0.0                          // clock dell'ultima ripresa (0 = in pausa)
    // ANDROID (analisi 21/11, 1.1): istante (clock) in cui è iniziata la pausa; 0 = simulazione in corsa.
    // In pausa il «tempo clinico» (simNow) resta fermo a questo istante: rampe, catene, DAE, carica, NIBP e apnea non
    // avanzano; alla ripresa tutti gli istanti di riferimento si spostano in avanti della durata della pausa.
    private var pausedAt = 0.0
    // ANDROID (fine scenario 22/11): comando end_scenario. Simulazione in pausa (stessa pausa vera di simNow) e schermata
    // «Scenario terminato» sul monitor. Si azzera con reset_scenario, con la ripresa e con un nuovo avvio (reset_clock).
    private var scenEnded = false
    // ANDROID (avvio scenario 22/11): macchina a stati dello scenario. Dopo reset_scenario (e al primo avvio) lo scenario è
    // PRONTO: il monitor è vivo (curve, allarmi, defibrillatore funzionano: si familiarizza col dispositivo) ma l'orologio di
    // scenario resta a 00:00 e il registro non scrive. start_scenario (o set_simulation_running true da un client vecchio,
    // o l'avvio di uno scenario a fasi) lo fa partire: orologio da 00:00, registro in una sessione nuova.
    //   ready (non avviato) → running ⇄ paused (set_simulation_running) → ended (end_scenario) → reset_scenario → ready
    @Volatile private var scenStarted = false
    /** ANDROID (avvio scenario 22/11): cresce a ogni avvio vero (PRONTO/terminato → in corso); il registro lo usa per aprire la sessione */
    @Volatile var scenarioStartSeq = 0; private set
    /** ANDROID (avvio scenario 22/11): lo scenario è stato avviato (falso = PRONTO) */
    fun isScenarioStarted(): Boolean = scenStarted
    private var ecgOn = false; private var spo2On = false; private var nibpOn = false; private var etco2On = false; private var artOn = false
    private var sites: MutableMap<String, String> = LinkedHashMap()   // sensore -> posizione sul paziente
    private var mutedUntil = 0.0
    private var nibpStatus: SLNibpStatus = SLNibpIdle
    private var nibpError: String? = null
    private var nibpStartedAt = 0.0
    private var nibpTarget = 0.0; private var nibpEnd = 0.0
    private var nibpCuffOK = false
    private var nibpLastSys = 0; private var nibpLastDia = 0
    private var nibpMeasuredAt: Date? = null
    private var limits: MutableMap<String, Int> = LinkedHashMap()
    // transizioni (rampe lineari) e catene
    private var ramps: MutableMap<String, SLRamp> = LinkedHashMap()   // param -> {from,to,start,dur}
    private var autoPhysio = false
    private var baseline: Map<String, Int>? = null       // valori pre-arresto per il ROSC
    private var chainSteps: List<Any?>? = null
    private var chainName: String? = null
    private var chainIndex = 0
    private var chainPhaseStart = 0.0
    private var chainActive = false; private var chainWaiting = false
    private var rampStartOverride = 0.0
    // ANDROID (transizioni ritmo 22/11): preludio del cambio di ritmo (vedi SFRhythmOnset.kt e «PRELUDIO» più sotto)
    private var onset: SLOnsetPlan? = null
    private var onsetStart = 0.0                         // tempo clinico dell'inizio
    private var onsetActIdx = 0; private var onsetPhaseIdx = 0
    private var onsetDeferred: Map<*, *>? = null         // transizione (ritmo + parametri) da applicare all'arrivo; null = set_rhythm
    private var onsetPre: Map<String, Int>? = null       // valori all'inizio (si torna lì se il preludio viene interrotto)
    private var onsetBaselineSet = false                 // il preludio ha fissato i valori pre-arresto (baseline)
    private var onsetSeq = 0                             // numero progressivo dei cambi di ritmo (seme delle varianti)
    private var onsetLast: Map<String, Any?>? = null     // ultimo preludio arrivato: {id, from, to, text, variant}
    // ANDROID (ampiezza FV 22/11): ampiezza (mV picco-picco) e frequenza della FV, valore dell'istruttore (-1 = automatica)
    private var vfAmp = SLVfCoarseMv; private var vfFreq = 1.15; private var vfManual = -1.0; private var vfLastAt = 0.0
    private var vfShownAmp = SLVfCoarseMv; private var vfShownFreq = 1.15
    private var pendingVfAmp: Double? = null             // ampiezza chiesta con set_rhythm vf: vale all'arrivo della FV
    // ANDROID (12 derivazioni 22/11): morfologia dell'ECG (ST, blocchi di branca, blocchi AV, altre alterazioni: SFEcgMorphology.kt)
    // e frequenza ATRIALE del BAV 3° (con i blocchi «a rapporto» la frequenza atriale = FC ventricolare × rapporto)
    private var ecgMorph = SLEcgMorph.NORMAL
    private var atrialHr = 80
    private var ecg12Last: Map<String, Any?>? = null     // ultima carta a 12 derivazioni aperta dai partecipanti
    private var ecg12Count = 0
    // ANDROID (numerici realistici 22/11): numeri a schermo misurati come un monitor vero
    private val mon = SFMonitorNumerics()
    private var alarmOn: MutableMap<String, Boolean> = LinkedHashMap()   // configurazione del monitor (non si azzera col reset)
    private var nibpInterval = 0
    private var nibpNext = 0.0
    private var cpr = false
    private var dEvSeq = 0                               // numero progressivo degli eventi del defibrillatore
    private var breaths: MutableList<Double> = ArrayList()         // insufflazioni segnalate dall'istruttore (ventilazione manuale)
    private var scheduled: MutableList<SLScheduled> = ArrayList()  // rampe programmate nel futuro (es. picco EtCO2 al ROSC)
    private var apneaSince = 0.0; private var spo2PreApnea = 0
    // sonde aggiuntive, acquisizione SpO2, temperatura, refill
    private var tempOn = false; private var padsOn = false; private var spo2LowPerf = false
    // saturimetro palmare (SimulaFranz_Saturimetro): sensore, accensione, limiti, volume, silenziamento, ultimo contatto
    private var oxiOn = false; private var oxiPower = false
    private var oxiConnAt = 0.0; private var oxiMutedUntil = 0.0; private var oxiSeenAt = 0.0
    private var oxiVolume = 70
    private var oxiLimits: MutableMap<String, Int> = LinkedHashMap(defaultOximeterLimits())
    private var motion = 0; private var motionUntil = 0.0; private var aedMotionSaidAt = 0.0; private var touchUntil = 0.0
    private var nibpMotionHit = false
    private var spo2Delay = 0; private var spo2Stage = 0     // stage: 1 ricerca, 2 acquisizione, 3 valore pronto
    private var spo2ConnAt = 0.0
    private var tempT = 0; private var crtT = 0
    private var crtOn = false; private var layout = "generic_icu"; private var crtSeq = 0   // impostazioni del monitor: non si azzerano con il reset
    // defibrillatore
    private var dMode: SLDefibMode = SLDefibOff
    private var dEnergy = 0; private var dChargedEnergy = 0
    private var dCharge: SLDefibCharge = SLChargeNone
    private var dChargeStart = 0.0; private var dChargeDur = 0.0; private var dReadyAt = 0.0
    private var dSync = false; private var dSyncPending = false; private var dSyncSince = 0.0
    private var aedPhase: SLAedPhase = SLAedPads; private var aedPhaseStart = 0.0
    private var aedAdvised = false; private var aedMotion = false
    // ANDROID (proposta A8, 21/11): piastre applicate ma con contatto scarso (impedenza alta): il DAE non analizza e dice «Controllare le piastre»
    private var padsPoor = false; private var padsPoorSaidAt = 0.0
    // ANDROID (21/11, richiesta utente): qualità della RCP scelta dall'istruttore guardando le compressioni (0 = corretta):
    private var cprRateQ = 0; private var cprDepthQ = 0; private var cprFbAt = 0.0; private var cprFbBad = false
    // ANDROID (EtCO2 RCP 22/11): modello dell'EtCO2 in arresto (solo con la fisiologia automatica). Vedi co2Advance.
    private var co2Flow = 0.0          // 0..1: flusso polmonare prodotto dalle compressioni (1 = compressioni in corso da un po')
    private var co2Auto = 0.0          // mmHg: EtCO2 «a flusso pieno» del modello (qualità, affaticamento, adrenalina), già smussata
    private var co2Manual = false      // l'istruttore ha forzato l'EtCO2 durante l'arresto (set_etco2 / transition): vale il suo valore
    // ANDROID (EtCO2 visibile 24/11, richiesta utente): come compare l'EtCO2 in arresto (impostazione dell'istruttore, resta col reset):
    //  "auto" (di serie): il monitor ventila da solo dopo l'avvio della RCP, come la squadra: 30:2 con maschera (2 espirazioni a
    //   ogni ciclo di 30 compressioni) o 10/min con la via aerea avanzata; «Ventila» resta e non fa doppioni;
    //  "30_2": sempre 30:2 (2 espirazioni ogni 30 compressioni), anche con la via aerea avanzata;
    //  "always": numero sempre a schermo in arresto (onde a 10/min); un valore forzato dall'istruttore resta FISSO;
    //  "realistic": solo le ventilazioni vere («Ventila», ventilatore, pallone), come prima.
    private var co2Display = "auto"
    private var co2CprSeen = false     // in questo arresto la RCP è partita almeno una volta (prima nessuno ventila)
    private var co2AutoNext = 0.0      // prossima espirazione automatica (simNow; 0 = da programmare)
    private var co2AutoSecond = false  // 30:2: la prossima è la seconda insufflazione del ciclo
    private var co2RunSince = 0.0      // inizio delle compressioni in corso (massaggio buono e continuo: +2-4 mmHg in ~90 s)
    private var co2LastAt = 0.0        // ultimo istante (simNow) integrato dal modello (0 = modello non avviato)
    private var cprWork = 0.0          // secondi di compressioni dall'inizio dell'arresto o dall'ultimo cambio dell'operatore
    private var epiAt = 0.0            // adrenalina registrata durante l'arresto (simNow; 0 = nessuna)
    private var breathVals: MutableList<Int> = ArrayList()  // picco di CO2 di ogni insufflazione (parallelo a breaths)
    private var artBase = 0.0          // ANDROID (ART RCP 22/11): pressione di base in arresto (diastolica di rilasciamento o riempimento)
    // ANDROID (RCP dettagliata 22/11): rilascio 1 = incompleto (appoggio); mani 1 = posizione sbagliata. Di serie 0 = corretto.
    private var cprRecoilQ = 0; private var cprHandsQ = 0
    // ANDROID (RCP dettagliata 22/11): frequenza numerica delle compressioni (40-200/min, di serie 110). La categoria cprRateQ
    // ne deriva (<100 lenta, 100-120 corretta, >120 veloce) e resta per i consigli del DAE e i client vecchi.
    private var cprRateCpm = 110
    private var dNote: String? = null; private var dNoteAt = 0.0   // messaggio transitorio (shock erogato, carica annullata...)
    private var dVoice: String? = null; private var dPromptSeq = 0
    private var tabletVolume = -1; private var tabletVolumeSeq = 0   // ANDROID (guscio 25/11): volume di SISTEMA del tablet monitor (-1 = non ancora letto); la sequenza cresce a ogni comando
    private var dChargeRefused = 0      // ANDROID (suoni 25/11): CARICA premuto senza piastre o senza cavo (il dispositivo fa il suono di errore)
    private var dShocks = 0; private var dLastEnergy = 0
    private var dLastShock = 0.0; private var dSession = 0.0; private var dLastSync = false
    private var pRate = 0; private var pOut = 0
    private var pDemand = false; private var pRun = false; private var pPaused = false
    private var dEvents: MutableList<Map<String, Any?>> = ArrayList()
    private var dEpisode: String? = null; private var dEff = 0; private var cvEff = 0
    // esito dello shock in attesa dell'istruttore
    private var dDecision = false; private var dDecisionFrom: String? = null
    private var dDecisionShock = 0; private var dDecisionEnergy = 0; private var dDecisionSync = false
    // ANDROID (25/11, regola dell'utente): la domanda sull'esito resta aperta 10 s; senza risposta il ritmo resta quello di prima
    private var dDecisionAt = 0.0
    // ANDROID (25/11, richiesta dell'utente): «shock non consigliato» -> secondi fino alla ripresa della RCP (nel registro)
    private var noShockAt = 0.0
    private var laterSay: String? = null; private var laterSayAt = 0.0   // ANDROID (audit 25/11): frase detta dopo quella della fase
    private val kDecisionTimeoutS = 10.0
    private var cmdOutcomeShock: Any? = null   // ANDROID (fix analisi 22/11, terzo giro): campo "shock" del comando in corso
    private var dCfg: MutableMap<String, Any?> = LinkedHashMap()   // configurazione dell'istruttore (non si azzera col reset)
    // ANDROID (funzioni 21/11) — placche: cavo collegato al dispositivo, placche pediatriche, modalità bambino del DAE
    private var padsCable = true; private var padsPed = false; private var aedChild = false
    // device_realism: il DAE aspetta ANALIZZA; il movimento ritarda l'analisi al massimo di N s
    private var aedAwait = false; private var aedAwaitSaidAt = 0.0
    private var aedMotionSince = 0.0; private var aedMotionOverride = false
    // ANDROID (25/11, segnalazione dell'utente): all'inizio dell'analisi il DAE prima DICE «Sospendere la RCP. Analisi del ritmo
    // in corso. Non toccare il paziente.» e solo dopo questo tempo (frase detta + tempo per staccare le mani) controlla il
    // movimento: prima l'analisi resta ferma ma non si dice «Movimento rilevato»
    private var aedGraceUntil = 0.0
    private val kAedStopGraceS = 6.0
    private val kPadsRepeatS = 15.0
    private val kCprFatigueFullS = 120.0
    private var seqIdx = 0                               // prossima voce di outcome_sequence
    // distrattori: ambulanza in movimento, allarmi tecnici simulati
    private var vehicle = false
    private var techAlarms: MutableSet<String> = LinkedHashSet()
    // ANDROID (difetti clinici 22/11, IN-01): sensori usati in questa sessione (collegati almeno una volta dopo reset_scenario)
    private var everOn: MutableSet<String> = LinkedHashSet()
    // ANDROID (funzioni mancanti 22/11) — linea arteriosa (IN-03/OP-04): smorzamento 0 ottimale/1 sovra/2 sotto, livello del
    // trasduttore (cm sopra l'asse flebostatico), occlusione; azzeramento (zero obbligatorio = impostazione della regia, resta
    // col reset), fine dell'azzeramento in corso (clock), ora dell'ultimo; prova di lavaggio rapido (clock)
    private var artDampK = 0; private var artLevel = 0; private var artOcclK = false; private var artZeroReq = false
    private var artZeroed = true; private var cvpZeroed = true; private var artZeroUntil = 0.0; private var artZeroedMs = 0L; private var artFlushC = 0.0
    // PVC e temperatura centrale (IN-13)
    private var cvpOn = false; private var cvpV = 6; private var cvpWaveK = "normal"; private var temp2On = false; private var temp2T = 372
    // ventilazione (CC-04/AE-12/OP-05): spontaneous | ventilator | bag; frequenza e volume del ventilatore; scollegato;
    // istante di riferimento della fase (clock); prossima insufflazione automatica (simNow); FR prima dell'arresto
    private var ventModeK = "spontaneous"; private var ventRateV = 12; private var ventTidalV = 450; private var ventDisc = false
    private var ventStart = 0.0; private var ventNext = 0.0; private var ventPreArrest = 0; private var airwayAdv = false
    private var ventArrestRate = 10                                // ERC: in RCP con via aerea avanzata 10/min (0 = lascia la frequenza)
    private var ppvSet = -1.0                                      // -1 = automatica (dall'indice di shock)
    // elettrodi (IN-10): un elettrodo staccato, tracciato disturbato, inversione dei cavi delle braccia
    private var ecgElOff: String? = null; private var ecgNoiseK = 0; private var limbRev = false
    // ritardi d'allarme (IN-08, configurazione: restano col reset), condizioni in corso, ultimo respiro (apnea)
    private var alarmDelays: MutableMap<String, Int> = LinkedHashMap(SLDefaultAlarmDelays())
    private val alarmGate = SLAlarmGate()
    private var lastRespAt = 0.0
    // trend dei parametri a schermo (IN-14)
    private val trend = SFTrendStore()
    // IN-12: tecnici risolvibili dai partecipanti (impostazione della regia), inizio di ogni distrattore attivo (simNow)
    private var techResolv = false; private var techSince: MutableMap<String, Double> = LinkedHashMap()
    // IN-13: valori di laboratorio/bilancio della regia
    private var labs: MutableMap<String, Double> = LinkedHashMap()
    // voci del registro prodotte dal cuore (SFSessionLog le legge con stateEventsSince)
    private val stEvents = ArrayList<SLStateEvent>(); private var stEvSeq = 0
    // IN-17: ultimo invito a iniziare la RCP del DAE senza compressioni (simNow)
    private var aedNoCprSaidAt = 0.0
    // AE-08: contatto delle piastre «marginale» (scarica erogata con impedenza alta)
    private var padsMarginal = false
    // ANDROID (difetti clinici 22/11): polso effettivo già applicato alla fisiologia (null = da riallineare senza reazioni),
    // durata della ricerca SpO2 in corso (collegamento: spo2_delay; ROSC: almeno 8 s), PA prima/dopo la cattura (CC-05)
    private var hemoPulse: Boolean? = null
    private var spo2AcqLen = 0.0
    private var capPre: Map<String, Int>? = null
    private var capTarget: Map<String, Int>? = null
    // ANDROID (EM-02): CO2 alveolare in apnea con polso (mmHg; -1 = modello spento), istante dell'ultimo aggiornamento e
    // dell'ultima insufflazione
    private var apCo2 = -1.0; private var apCo2At = 0.0; private var apVentAt = 0.0
    private var co2Excess = 0.0                                  // ANDROID (AE-14): CO2 accumulata nell'asfissia, in arresto
    private var peaNarrow = false                                // ANDROID (EF-03): PEA a complessi stretti (causa meccanica)
    // ANDROID (perfusione 22/11): PAM smussata per la perfusione periferica, istante, inizio della PAM critica, segnale perso
    private var mapS = -1.0; private var mapSAt = 0.0; private var mapLowSince = 0.0; private var spo2Weak = false; private var perfStep = -1
    // ANDROID (guasti 22/11): malfunzionamenti simulati del dispositivo (di serie nessuno). Restano con lo spegnimento, la
    // riaccensione e il cambio di layout (un DAE guasto resta guasto); li azzera reset_scenario.
    private var faults: MutableSet<String> = LinkedHashSet()   // solo i tipi «propri» (non pads_unplugged / pads_poor_contact)
    private var faultBeepOn = true                            // bip da spento sì/no
    private var battShocks = -1                               // scariche residue della batteria simulata (-1 = non simulata)
    private var battOnS = 0.0; private var battLastAt = 0.0   // battery_replace: secondi acceso (tempo clinico) -> spegnimento
    private var battReplacedSay = false
    private var faultSelfTestUntil = 0.0                      // autotest breve dopo la sostituzione della batteria (clock)
    private var faultSaidAt = 0.0                             // ultima ripetizione di «Unità guasta…» / «Sostituire le piastre»
    // metriche RCP (tempo clinico, simNow): tempo in arresto, tempo con compressioni in arresto, pause attorno alle scariche
    private var cmLastAt = 0.0; private var cmArrest = 0.0; private var cmCpr = 0.0
    private var cprStoppedAt = 0.0                       // fine delle ultime compressioni (0 = mai)
    private var cmShocks: MutableList<MutableMap<String, Any?>> = ArrayList()
    private var cmPending: MutableMap<String, Any?>? = null   // scarica in attesa della ripresa delle compressioni
    private var cmShockAt = 0.0
    // avanzamento automatico annullabile (auto_advance)
    private var autoText: String? = null; private var autoUntil = 0.0; private var autoSeq = 0
    private var autoUndo: Map<String, Any?>? = null; private var autoUndoDecision: Map<String, Any?>? = null

    private val logTimeFormat = SimpleDateFormat("HH:mm:ss", Locale.US)
    // ANDROID (ora 22/11): ora di sistema mandata dal controller (set_wall_clock): scarto rispetto all'orologio del tablet.
    // Non la azzera reset_scenario (non è dello scenario); si salva nella ripresa dopo un crash.
    @Volatile private var wallOffsetMs = 0L
    @Volatile private var wallSynced = false
    /** ANDROID (ora 22/11): ora sincronizzata (ms epoch) per tutti gli orari scritti: registro, eventi, NIBP, fine scenario */
    fun wallNowMs(): Long = SFWallClock.system() + (if (wallSynced) wallOffsetMs else 0L)
    private val random = Random()

    init {
        resetValues()
        limits = LinkedHashMap(defaultAlarmLimits())                     // configurazione del monitor:
        autoPhysio = true
        alarmOn = linkedMapOf("hr" to true, "spo2" to true, "art" to true, "nibp" to true, "rr" to true, "etco2" to true, "temp" to true)
        dCfg = LinkedHashMap(SLDefibDefaultConfig())
        spo2Delay = 0; crtOn = true; layout = "generic_icu"
        revision = 1                                                     // NON si azzera con reset_scenario
    }

    companion object {
        /** Versione dell'app mostrata in /api/v1/state (sull'iPad: CFBundleShortVersionString). La imposta l'app all'avvio. */
        @JvmStatic @Volatile var appVersion: String = "?"

        @JvmStatic fun nibpIntervals(): List<Int> = listOf(0, 1, 2, 3, 5, 10, 15, 30, 60)   // minuti

        // Layout con il nome mostrato nei menu (monitor e controller): i controller leggono questo elenco dallo stato,
        // così i nomi stanno in un posto solo. I primi tre sono i layout ORIGINALI di similFranz; quelli che seguono
        // sono i layout personalizzati, se presenti (src/custom).
        // ANDROID (categorie dei layout 22/11): il catalogo (con categoria «Default» / «Personalizzati» e tipo) sta in
        // SFLayoutCatalog.kt; i layout personalizzati in src/custom (SFCustomLayoutCatalog), vuoti nella build pubblica
        @JvmStatic fun layoutCatalog(): List<Map<String, String>> = SFLayoutCatalogAll()
        @JvmStatic fun supportedLayouts(): List<String> = layoutCatalog().map { it["id"]!! }

        @JvmStatic fun defibEnergies(): List<Int> {
            return listOf(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 15, 20, 30, 50, 70, 75, 85, 100, 120, 125, 150, 170, 175,
                          200, 225, 250, 275, 300, 325, 360)
        }

        // Limiti predefiniti (adulto) e range ammessi per ciascun limite
        @JvmStatic fun defaultAlarmLimits(): Map<String, Int> = linkedMapOf(
            "hr_low" to 50, "hr_high" to 120, "spo2_low" to 90, "rr_low" to 8, "rr_high" to 30,
            "etco2_low" to 25, "etco2_high" to 50, "sys_low" to 90, "sys_high" to 180) + SLExtraLimitDefaults()   // ANDROID (IN-09)

        @JvmStatic fun defaultOximeterLimits(): Map<String, Int> = linkedMapOf(
            "spo2_low" to 85, "spo2_high" to 100, "pr_low" to 30, "pr_high" to 170)   // limiti di serie tipici di un saturimetro palmare

        @JvmStatic fun supportedRhythms(): List<String> = listOf(
            SLRhythmSinus, SLRhythmSinusTachycardia, SLRhythmSinusBradycardia,
            SLRhythmAtrialFibrillation, SLRhythmVentricularTachyPulse, SLRhythmVentricularTachy, SLRhythmVentricularFib,
            SLRhythmAsystole, SLRhythmPEA)

        @JvmStatic fun supportedSensors(): List<String> = listOf(
            SLSensorECG, SLSensorSpO2, SLSensorNIBP, SLSensorEtCO2, SLSensorART, SLSensorTemp, SLSensorPads, SLSensorOxi,
            SLSensorCVP, SLSensorTemp2)                                       // ANDROID (IN-13)

        @JvmStatic fun rhythmHasPulse(rhythm: String?): Boolean {
            // VT trattata come senza polso (scenario ACLS); PEA = attività elettrica senza polso.
            return !(rhythm == SLRhythmVentricularFib ||
                     rhythm == SLRhythmVentricularTachy ||
                     rhythm == SLRhythmAsystole ||
                     rhythm == SLRhythmPEA)
        }
    }

    private fun resetValues() {
        rhythm = SLRhythmSinus
        hr = 82; spo2 = 98; rr = 16; etco2 = 35; sys = 120; dia = 80
        spo2Valid = true; muted = false; running = true; qrsBeep = true; volume = 70
        pausedAt = 0.0                          // ANDROID (analisi 21/11, 1.1): il reset riparte sempre in corsa
        scenEnded = false                       // ANDROID (fine scenario 22/11)
        // ANDROID (avvio scenario 22/11): il reset riporta a PRONTO: timer a 00:00 e FERMO finché non arriva start_scenario
        scenStarted = false
        scenAccum = 0.0; scenSince = 0.0        // il timer di scenario riparte da zero
        // Avvio (e reset): paziente appena arrivato, NESSUN sensore collegato; si collegano uno alla volta
        ecgOn = false; spo2On = false; nibpOn = false; etco2On = false; artOn = false
        sites = LinkedHashMap()
        mutedUntil = 0.0
        nibpStatus = SLNibpIdle; nibpError = null; nibpStartedAt = 0.0
        nibpLastSys = -1; nibpLastDia = -1; nibpMeasuredAt = null
        ramps = LinkedHashMap()
        scheduled = ArrayList()
        baseline = null; cpr = false; apneaSince = 0.0; breaths = ArrayList(); breathVals = ArrayList()
        co2Flow = 0.0; co2Auto = 0.0; co2Manual = false; co2LastAt = 0.0; cprWork = 0.0; epiAt = 0.0   // ANDROID (EtCO2 RCP 22/11)
        co2CprSeen = false; co2AutoNext = 0.0; co2AutoSecond = false; co2RunSince = 0.0              // ANDROID (EtCO2 visibile 24/11)
        artBase = 0.0; cprRecoilQ = 0; cprHandsQ = 0; cprRateCpm = 110                                  // ANDROID (RCP dettagliata 22/11)
        chainActive = false; chainSteps = null; chainName = null; chainIndex = 0
        // ANDROID (transizioni ritmo 22/11) e (ampiezza FV 22/11)
        onset = null; onsetDeferred = null; onsetPre = null; onsetBaselineSet = false; onsetSeq = 0; onsetLast = null; pendingVfAmp = null
        vfAmp = SLVfCoarseMv; vfFreq = 1.15; vfManual = -1.0; vfLastAt = 0.0; vfShownAmp = vfAmp; vfShownFreq = vfFreq
        ecgMorph = SLEcgMorph(interpretation = ecgMorph.interpretation); atrialHr = 80; ecg12Last = null; ecg12Count = 0   // ANDROID (12 derivazioni 22/11)
        oxiOn = false; oxiPower = false; oxiConnAt = 0.0; oxiMutedUntil = 0.0
        // oxiLimits e oxiVolume (70): impostati una sola volta alla creazione, non si azzerano con il reset
        tempOn = false; padsOn = false; spo2LowPerf = false; motion = 0; motionUntil = 0.0; touchUntil = 0.0; nibpMotionHit = false; spo2Stage = 3; spo2ConnAt = 0.0
        tempT = 368; crtT = 20
        dMode = SLDefibOff; dEnergy = SLManualDefaultEnergyFor(layout); dChargedEnergy = 0; dCharge = SLChargeNone   // ANDROID (AE-07)
        // ANDROID (analisi 21/11, 1.7): il reset riporta l'energia del DAE al valore di serie del layout attivo
        // (dCfg è vuota solo durante la costruzione, quando vale già la configurazione di serie)
        if (dCfg.isNotEmpty()) { val c = LinkedHashMap(dCfg); c["aed_energy_j"] = SLAedDefaultEnergy(layout).toLong(); dCfg = c }
        dSync = false; dSyncPending = false; aedPhase = SLAedPads; aedAdvised = false; aedMotion = false; padsPoor = false; cprRateQ = 0; cprDepthQ = 0; cprFbAt = 0.0; cprFbBad = false
        dNote = null; dVoice = null; dPromptSeq = 0
        dShocks = 0; dLastEnergy = 0; dLastShock = 0.0; dSession = 0.0; dLastSync = false
        pRate = 70; pOut = 0; pDemand = true; pRun = false; pPaused = false
        dEvents = ArrayList()
        dEpisode = null; dEff = 0; cvEff = 0
        dDecision = false; dDecisionFrom = null; dDecisionShock = 0; dDecisionEnergy = 0; dDecisionSync = false
        // ANDROID (funzioni 21/11)
        padsCable = true; padsPed = false; aedChild = false
        aedAwait = false; aedAwaitSaidAt = 0.0; aedMotionSince = 0.0; aedMotionOverride = false; aedGraceUntil = 0.0; noShockAt = 0.0; dDecisionAt = 0.0; cprWorkAt = 0.0; laterSay = null; laterSayAt = 0.0; seqIdx = 0
        vehicle = false; techAlarms = LinkedHashSet(); everOn = LinkedHashSet()
        hemoPulse = null; capPre = null; capTarget = null; spo2AcqLen = 0.0     // ANDROID (EM-07, CC-05)
        mapS = -1.0; mapLowSince = 0.0; spo2Weak = false; co2Excess = 0.0; apCo2 = -1.0   // ANDROID (perfusione, AE-14, EM-02)
        // ANDROID (guasti 22/11): di serie nessun malfunzionamento
        faults = LinkedHashSet(); faultBeepOn = true; battShocks = -1; battOnS = 0.0; battLastAt = 0.0
        battReplacedSay = false; faultSelfTestUntil = 0.0; faultSaidAt = 0.0; powerBlocked = false
        cmLastAt = 0.0; cmArrest = 0.0; cmCpr = 0.0; cprStoppedAt = 0.0; cmShocks = ArrayList(); cmPending = null; cmShockAt = 0.0
        autoText = null; autoUntil = 0.0; autoUndo = null; autoUndoDecision = null
        // ANDROID (funzioni mancanti 22/11): paziente e distrattori nuovi di serie (impostazioni della regia — zero obbligatorio,
        // ritardi, tecnici risolvibili, limiti — restano)
        artDampK = 0; artLevel = 0; artOcclK = false; artZeroed = true; cvpZeroed = true; artZeroUntil = 0.0; artZeroedMs = 0L; artFlushC = 0.0
        cvpOn = false; cvpV = 6; cvpWaveK = "normal"; temp2On = false; temp2T = 372
        ventModeK = "spontaneous"; ventRateV = 12; ventTidalV = 450; ventDisc = false; ventStart = 0.0; ventNext = 0.0; ventPreArrest = 0
        airwayAdv = false; ppvSet = -1.0
        ecgElOff = null; ecgNoiseK = 0; limbRev = false
        lastRespAt = 0.0; techSince = LinkedHashMap(); labs = LinkedHashMap(); aedNoCprSaidAt = 0.0; padsMarginal = false
        alarmGate.clear(); trend.clear()
    }

    // ANDROID (analisi 21/11, 1.1): tempo clinico. In corsa è il clock; in pausa resta fermo all'inizio della pausa,
    // così ciò che si avvia durante la pausa (rampa, fase del DAE, misura...) parte davvero solo alla ripresa.
    private fun simNow(): Double = if (!running && pausedAt > 0) pausedAt else clock()
    // durata della pausa in corso (0 in corsa): gli istanti che la UI confronta con il suo orologio (NIBP, insufflazioni,
    // attesa dell'onda R, prossima NIBP) si mostrano spostati di questa durata, così in pausa restano fermi
    private fun pauseLag(): Double = if (!running && pausedAt > 0) Math.max(0.0, clock() - pausedAt) else 0.0

    // ANDROID (analisi 21/11, 1.1): ripresa dopo una pausa lunga `d` secondi: tutti i riferimenti temporali del paziente
    // e dei dispositivi si spostano in avanti di d, il paziente riparte esattamente da dove era
    private fun shiftClinicalTimes(d: Double) {
        if (d <= 0) return
        for (k in ArrayList(ramps.keys)) { val r = ramps[k]!!; ramps[k] = SLRamp(r.from, r.to, r.start + d, r.dur) }
        scheduled = scheduled.mapTo(ArrayList()) { SLScheduled(it.at + d, it.key, it.to, it.dur) }
        if (chainActive) chainPhaseStart += d
        if (onset != null) onsetStart += d                   // ANDROID (transizioni ritmo 22/11): il preludio si ferma
        if (vfLastAt > 0) vfLastAt += d                      // ANDROID (ampiezza FV 22/11)
        aedPhaseStart += d; dChargeStart += d; dReadyAt += d; dSyncSince += d
        if (dNoteAt > 0) dNoteAt += d
        if (aedMotionSaidAt > 0) aedMotionSaidAt += d
        if (aedGraceUntil > 0) aedGraceUntil += d
        if (dDecisionAt > 0) dDecisionAt += d
        if (noShockAt > 0) noShockAt += d
        if (laterSayAt > 0) laterSayAt += d
        if (cprWorkAt > 0) cprWorkAt += d
        if (padsPoorSaidAt > 0) padsPoorSaidAt += d
        if (cprFbAt > 0) cprFbAt += d
        if (nibpStatus == SLNibpMeasuring) nibpStartedAt += d
        if (nibpNext > 0) nibpNext += d
        if (apneaSince > 0) apneaSince += d
        if (spo2ConnAt > 0) spo2ConnAt += d
        if (motionUntil > 0) motionUntil += d
        breaths = breaths.mapTo(ArrayList()) { it + d }
        if (co2LastAt > 0) co2LastAt += d; if (epiAt > 0) epiAt += d     // ANDROID (EtCO2 RCP 22/11)
        // ANDROID (funzioni 21/11)
        if (aedAwaitSaidAt > 0) aedAwaitSaidAt += d
        if (aedMotionSince > 0) aedMotionSince += d
        if (cmLastAt > 0) cmLastAt += d
        if (cprStoppedAt > 0) cprStoppedAt += d
        if (cmShockAt > 0) cmShockAt += d
    }

    // MARK: Parametri e rampe (chiamare con synchronized già acquisito)

    private fun param(k: String): Int {
        if (k == "heart_rate_bpm") return hr
        if (k == "spo2_percent") return spo2
        if (k == "respiratory_rate_rpm") return rr
        if (k == "etco2_mmhg") return etco2
        if (k == "systolic_mmhg") return sys
        if (k == "diastolic_mmhg") return dia
        if (k == "cvp_mmhg") return cvpV                     // ANDROID (IN-13)
        return 0
    }

    private fun setParam(k: String, value: Int) {
        val v = value
        if (k == "heart_rate_bpm") hr = v
        else if (k == "spo2_percent") spo2 = v
        else if (k == "respiratory_rate_rpm") rr = v
        else if (k == "etco2_mmhg") etco2 = v
        else if (k == "systolic_mmhg") sys = v
        else if (k == "diastolic_mmhg") dia = v
        else if (k == "cvp_mmhg") cvpV = v                   // ANDROID (IN-13)
    }

    private fun rampParam(k: String, to: Int, duration: Double) {
        val dur = duration
        if (dur <= 0) { ramps.remove(k); setParam(k, to); return }
        // inizio della rampa: istante programmato (catene) oppure adesso
        val start = if (rampStartOverride > 0) rampStartOverride else simNow()
        ramps[k] = SLRamp(param(k), to, start, dur)
    }

    // Cambio ritmo con preset di FC e reazioni fisiologiche automatiche (arresto / ROSC)
    private fun applyRhythm(rhythm: String) {
        // ANDROID (ampiezza FV 22/11): la FV appena insorta è grossolana e rapida; il valore dell'istruttore vale finché non
        // cambia il ritmo
        if (rhythm != this.rhythm) {
            vfManual = -1.0
            if (rhythm == SLRhythmVentricularFib) { vfAmp = SLVfCoarseMv; vfFreq = 1.15; vfLastAt = simNow() }
        }
        // ANDROID (difetti clinici 22/11, EM-07): stato EMODINAMICO già applicato (con il pacing a cattura meccanica il polso
        // c'è anche in un ritmo «senza polso»): l'arresto e il ROSC seguono il polso effettivo, non solo il nome del ritmo
        val wasPulse = hemoPulse ?: rhythmHasPulse(this.rhythm)
        // ANDROID (difetti clinici 22/11, EF-03): PEA STRETTA (stesso ritmo di prima, con P, rapido) quando nasce da un ritmo
        // sinusale/FA rapido (ipovolemia, causa ostruttiva); LARGA e lenta negli altri casi (ipossia, acidosi, iperkaliemia)
        if (rhythm == SLRhythmPEA && this.rhythm != SLRhythmPEA)
            peaNarrow = (SFRhythmOnset.isSinusFamily(this.rhythm) && (hr >= 100 || this.rhythm == SLRhythmSinusTachycardia))
        this.rhythm = rhythm
        // ANDROID (12 derivazioni 22/11): con un blocco AV la FC tipica del ritmo sinusale è quella degli ATRI; la FC (ventricolare)
        // segue il blocco (rapporto, oppure lo scappamento del BAV 3°)
        if (SFRhythmOnset.isSinusFamily(this.rhythm) && (SFEcgMorphology.avRatio(ecgMorph.avBlock) > 0 || ecgMorph.avBlock == "third")) {
            var a = atrialRateNow()
            if (this.rhythm == SLRhythmSinusTachycardia && a <= 100) a = 130
            if (this.rhythm == SLRhythmSinusBradycardia && a >= 60) a = 45
            if (this.rhythm == SLRhythmSinus && (a < 60 || a > 100)) a = 80
            val k = SFEcgMorphology.avRatio(ecgMorph.avBlock)
            if (k > 0) hr = Math.max(15, SLLround(a / k)) else { atrialHr = a; if (hr < 15 || hr > 60) hr = escapeRate() }
        } else {
        if (this.rhythm == SLRhythmSinusTachycardia && hr <= 100) hr = 130
        if (this.rhythm == SLRhythmSinusBradycardia && hr >= 60) hr = 45
        if (this.rhythm == SLRhythmSinus && (hr < 60 || hr > 100)) hr = 80
        }
        if (this.rhythm == SLRhythmVentricularTachy && hr < 120) hr = 180
        if (this.rhythm == SLRhythmVentricularTachyPulse && hr < 120) hr = 170
        if (this.rhythm == SLRhythmAtrialFibrillation && hr < 60) hr = 110
        if (this.rhythm == SLRhythmPEA && (hr < 20 || hr > 150)) hr = 60
        val nowPulse = effectivePulse(); hemoPulse = nowPulse
        hemoTransition(wasPulse, nowPulse)
    }

    // ANDROID (difetti clinici 22/11, EM-07): reazioni di arresto / ROSC al cambio del polso EFFETTIVO (cambio di ritmo oppure
    // inizio/fine della cattura meccanica del pacing in un ritmo senza polso)
    private fun hemoTransition(wasPulse: Boolean, nowPulse: Boolean) {
        // ANDROID (ART RCP 22/11): inizio dell'arresto: la pressione parte da quella di adesso e crolla (o va alla diastolica di
        // rilasciamento se si sta già comprimendo); fine dell'arresto: il modello si ferma
        if (wasPulse && !nowPulse) { artBase = if (dia > 0) dia.toDouble() else kArtMsfp; co2LastAt = simNow(); epiAt = 0.0 }   // ANDROID (25/11): la stanchezza è di chi comprime, non si azzera col ritmo
        else if (!wasPulse && nowPulse) co2LastAt = 0.0
        if (!autoPhysio) return
        // ANDROID (EM-07): ROSC senza valori pre-arresto (scenario partito in arresto): valori plausibili post-ROSC
        val b = baseline ?: (if (!wasPulse && nowPulse) linkedMapOf("spo2_percent" to 96, "respiratory_rate_rpm" to 14, "etco2_mmhg" to 38,
                                                                    "systolic_mmhg" to 100, "diastolic_mmhg" to 60) else null)
        // ANDROID (CC-06): rosc_params (set_defib_config) = dove va il paziente al ROSC quando la causa è stata rimossa
        val rp = roscParamsCfg()
        if (wasPulse && !nowPulse) {
            // ARRESTO: desaturazione, apnea, crollo EtCO2 (senza RCP)
            if (baseline == null) baseline = linkedMapOf("spo2_percent" to spo2, "respiratory_rate_rpm" to rr, "etco2_mmhg" to etco2,
                                                         "systolic_mmhg" to sys, "diastolic_mmhg" to dia)
            rampParam("systolic_mmhg", 45, 15.0)      // crollo della pressione del paziente
            rampParam("diastolic_mmhg", 25, 15.0)
            if (ventModeK == "ventilator" && !ventDisc) {
                // ANDROID (CC-04): il ventilatore continua a insufflare; in RCP 10/min (ERC, via aerea avanzata) se impostato
                ventPreArrest = ventRateV
                if (ventArrestRate > 0 && ventRateV != ventArrestRate) { ventRateV = ventArrestRate; stEvent("action", "Ventilatore a ${ventArrestRate}/min per la RCP (era ${ventPreArrest}/min)", "vent", "cuore") }
                rampParam("respiratory_rate_rpm", ventRateV, 0.0); ventNext = simNow() + 60.0 / ventRateV
            }
            else if (cpr) rampParam("respiratory_rate_rpm", 10, 5.0) else rampParam("respiratory_rate_rpm", 0, 8.0)
            // ANDROID (EtCO2 RCP 22/11): l'EtCO2 in arresto la governa il modello (co2Advance), non più una rampa fissa a 15/0:
            // si parte dal valore di adesso con il flusso pieno; senza compressioni crolla in pochi secondi
            ramps.remove("etco2_mmhg"); scheduled.removeAll { it.key == "etco2_mmhg" }
            co2Flow = 1.0; co2Auto = etco2.toDouble(); co2Manual = false
            co2CprSeen = false; co2AutoNext = 0.0; co2AutoSecond = false; co2RunSince = 0.0   // ANDROID (EtCO2 visibile 24/11)
            // ANDROID (difetti clinici 22/11, AE-14): arresto ASFITTICO (dopo più di 1 min di apnea con polso): la CO2 accumulata
            // dà valori alti nelle prime ventilazioni dell'RCP, che poi scendono in 2-3 min (Grmec 2003)
            val asph = apCo2 > 0 && apneaSince > 0 && simNow() - apneaSince > 60
            co2Excess = if (asph) Math.max(0.0, Math.max(apCo2, etco2.toDouble()) - 20.0) else 0.0
            rampParam("spo2_percent", 0, 40.0)
        } else if (!wasPulse && nowPulse && b != null) {
            // ROSC: recupero graduale verso i valori pre-arresto
            if (ventModeK == "ventilator" && ventPreArrest > 0) { ventRateV = ventPreArrest; ventPreArrest = 0; ventStart = clock() }   // ANDROID (CC-04)
            if (ventModeK == "ventilator" && !ventDisc) rampParam("respiratory_rate_rpm", ventRateV, 0.0)
            else rampParam("respiratory_rate_rpm", rp["respiratory_rate_rpm"] ?: b["respiratory_rate_rpm"] ?: 16, 15.0)   // ANDROID (21/11): ripiego, mai 0 per errore
            // EtCO2: picco da wash-out (CO2 accumulata) nei primi secondi, poi ritorno al valore pre-arresto
            val be = rp["etco2_mmhg"] ?: b["etco2_mmhg"] ?: 35
            co2LastAt = 0.0; co2Manual = false                         // ANDROID (EtCO2 RCP 22/11): fine del modello d'arresto
            // ANDROID (24/11, richiesta utente): il circolo che riparte (anche a compressioni in corso) porta ai polmoni la CO2
            // accumulata: salita brusca in pochi atti (>= +10 mmHg, Pokorná 2010), poi smaltimento lento in ~2-3 min (prima 40 s)
            rampParam("etco2_mmhg", Math.min(90, be + 15), 6.0)
            scheduled.add(SLScheduled(simNow() + 6, "etco2_mmhg", be, 150.0))
            if (cpr) cprStoppedAt = simNow()                           // ANDROID (21/11): metriche RCP
            cpr = false                                                // al ROSC le compressioni si fermano
            // ANDROID (difetti clinici 22/11, EM-05): al ROSC il saturimetro CERCA il segnale qualche secondo («---», ricerca
            // polso) e poi mostra un valore plausibile che sale: mai 0 % (nessun pulsossimetro lo mostra)
            spo2 = Math.max(spo2, 75); spo2Search(8.0)
            rampParam("spo2_percent", rp["spo2_percent"] ?: b["spo2_percent"] ?: 98, 45.0)
            // pressione: riparte bassa (ipotensione post-ROSC) e risale gradualmente in ~2 minuti
            val bs = rp["systolic_mmhg"] ?: b["systolic_mmhg"] ?: 0
            val bd = rp["diastolic_mmhg"] ?: b["diastolic_mmhg"] ?: 0
            if (bs > 0) {
                // ANDROID (perfusione 22/11): ipotensione post-ROSC ~metà dei valori di arrivo (prima min() con la PA d'arresto
                // 45/25 = PAM 32: saturimetro senza segnale per mezzo minuto dopo la ricerca)
                sys = Math.max(55, bs / 2); dia = Math.max(32, bd / 2); if (dia >= sys) dia = sys - 10
                rampParam("systolic_mmhg", bs, 120.0)
                rampParam("diastolic_mmhg", bd, 120.0)
            }
            baseline = null
        }
    }

    // Ventilazione alveolare: a parità di produzione di CO2, EtCO2 ~ inversamente proporzionale alla FR
    private fun coupleEtCO2FromRR(oldRR: Int, to: Int, duration: Double) {
        val newRR = to
        if (!autoPhysio || !rhythmHasPulse(rhythm) || oldRR <= 0 || newRR <= 0 || oldRR == newRR || etco2 <= 0) return
        val target = SLLround(Math.max(15.0, Math.min(90.0, etco2.toDouble() * oldRR / newRR)))
        // ANDROID (difetti clinici 22/11, AE-13): per le riserve di CO2 il nuovo equilibrio arriva in minuti (Farhi & Rahn 1960):
        // ~3 min in discesa (iperventilazione), ~5 min in salita (ipoventilazione); la durata dell'istruttore vale se più lunga
        rampParam("etco2_mmhg", target, Math.max(if (target < etco2) 180.0 else 300.0, duration))
    }

    // MARK: ANDROID (EtCO2 RCP 22/11) — modello dell'EtCO2 durante l'arresto e la RCP
    //
    // Come funziona nella realtà (ERC 2021 ALS, Soar et al., Resuscitation 2021;161:115-151 e ERC 2025; AHA 2020/2025):
    //  - il numero EtCO2 è il picco di CO2 di ogni ESPIRAZIONE: in RCP c'è solo quando qualcuno ventila (pallone, presidio
    //    avanzato); senza ventilazioni non c'è plateau e il monitor dà APNEA (resta la gestione «Ventila» di prima);
    //  - con le compressioni la CO2 che arriva ai polmoni dipende dalla gittata prodotta: RCP buona ~15-20 mmHg (>20 associato
    //    a più ROSC: Paiva et al., Resuscitation 2018;123:1-7); conta soprattutto la PROFONDITÀ (Sheak et al., Resuscitation
    //    2015;89:149-154: +1,4 mmHg ogni 10 mm; la frequenza nello studio pesa poco: qui lenta e veloce abbassano di meno);
    //  - l'affaticamento di chi comprime abbassa la qualità già entro 1-2 minuti (cambio ogni 2 min, ERC): calo graduale di
    //    qualche mmHg, azzerato dalla voce di registro «Cambio dell'operatore alle compressioni» (noteCprRescuerSwap);
    //  - pausa delle compressioni senza ROSC: il flusso si ferma e l'EtCO2 crolla in pochi secondi;
    //  - ROSC: aumento brusco e sostenuto, spesso >= 10 mmHg (Pokorná et al., J Emerg Med 2010;38:614-621): lo fa applyRhythm
    //    (picco da wash-out e poi il valore pre-arresto);
    //  - adrenalina: calo transitorio e variabile di qualche mmHg (Cantineau et al., Am J Emerg Med 1994;12:267-270; Callaham
    //    et al., Crit Care Med 1992;20:337-343) (noteEpinephrine);
    //  - iperventilazione: ogni ventilazione «lava» più CO2, il valore per atto scende (Aufderheide, Circulation 2004);
    //  - EtCO2 < 10 mmHg dopo 20 min di ALS = prognosi sfavorevole (solo contesto, mai da sola per fermare la RCP: ERC 2021).
    //
    // PRIORITÀ tra istruttore e modello (solo con la fisiologia automatica e paziente senza polso):
    //  - di serie decide il MODELLO (etco2_cpr_mode "auto"): EtCO2 = valore della qualità RCP × flusso delle compressioni;
    //    il valore impostato (etco2_mmhg) segue il modello, così lo slider del controller mostra cosa succede;
    //  - se l'istruttore imposta l'EtCO2 durante l'arresto (set_etco2, transition o fase di uno scenario con etco2_mmhg, tranne
    //    la fase che AVVIA l'arresto), il suo valore diventa l'EtCO2 «con le compressioni» ("instructor"): le pause la fanno
    //    comunque crollare e la ripresa la riporta al suo valore, le ventilazioni la mostrano; la qualità RCP non la cambia più;
    //  - si torna al modello toccando la qualità della RCP (set_cpr_quality), a ogni nuovo arresto, al ROSC e con il reset.
    //  Senza fisiologia automatica tutto resta come prima: vale solo il valore impostato.
    private fun co2ModelOn(): Boolean = autoPhysio && !effectivePulse()   // ANDROID (EM-07): polso effettivo (pacing)

    // MARK: ANDROID (ART RCP 22/11) — pressione arteriosa cruenta in arresto
    //
    // Come funziona nella realtà (AHA 2020/2025 e ERC 2021/2025; consenso AHA «CPR Quality», Meaney et al., Circulation
    // 2013;128:417-435):
    //  - senza compressioni non c'è flusso: la curva è quasi piatta e scende in pochi secondi verso la pressione di riempimento
    //    sistemica (~10-20 mmHg; qui 15);
    //  - ogni compressione genera un'onda (picco «sistolico»), il rilasciamento la «diastolica»: RCP buona ~80/28 mmHg; la
    //    diastolica di rilasciamento >= 25 mmHg è l'obiettivo (Sutton et al., Circulation 2018;137:e7-e13: diastolica < 25
    //    associata a minore sopravvivenza; AHA 2020: «ragionevole» guidare la RCP con la diastolica se c'è la linea arteriosa);
    //  - superficiale: picchi bassi (~45) e diastolica < 20; lenta: onde più rade e diastolica più bassa (più tempo per
    //    svuotarsi); troppo veloce: riempimento ridotto, picchi più bassi; mani in posizione sbagliata: come una profondità
    //    ridotta ma meno marcata; rilascio incompleto (appoggio): la pressione intratoracica non torna a zero, la diastolica
    //    sembra più alta di qualche mmHg ma la pressione di perfusione coronarica e la gittata calano (Zuercher et al., Crit
    //    Care Med 2010;38:1141-1146; Yannopoulos et al., Resuscitation 2005;64:363-372): picchi più bassi;
    //  - affaticamento: calo graduale come per l'EtCO2; iperventilazione: pressione intratoracica più alta, meno ritorno
    //    venoso, pressioni un po' più basse (Aufderheide et al., Circulation 2004;109:1960-1965);
    //  - adrenalina: vasocostrizione, diastolica (e pressione di perfusione coronarica) più alta per qualche minuto, mentre
    //    l'EtCO2 cala un poco: è la dissociazione vera (più resistenze, meno flusso polmonare);
    //  - pause: crollo immediato, onde assenti; ROSC: tornano le onde proprie del ritmo (applyRhythm, ipotensione post-ROSC).
    // Il modello ART c'è sempre in arresto (anche senza fisiologia automatica: è la fisica delle compressioni, come prima).

    // fattore di affaticamento 0..0,20: al massimo (-20%) dopo 2 min di compressioni dello stesso soccorritore, cioè quando
    // il ciclo di RCP del DAE finisce e l'ERC chiede il cambio (ANDROID 25/11: prima era pieno a 150 s, non coincideva)
    private fun cprFatigueFrac(): Double = Math.min(0.20, 0.20 * cprWork / kCprFatigueFullS)
    // ANDROID (25/11, regola dell'utente): chi comprime e non viene cambiato peggiora da solo: dopo 2 min di compressioni
    // diventano SUPERFICIALI, dopo altri 2 min anche il RILASCIO è INCOMPLETO. Il cambio dell'operatore (cprWork = 0) le
    // riporta come le ha impostate l'istruttore. Qui le qualità EFFETTIVE (fisiologia, consigli del DAE, stato mostrato).
    private fun dQ(): Int = if (cprDepthQ == 0 && cprWork >= kCprFatigueFullS) 1 else cprDepthQ
    private fun rQ(): Int = if (cprRecoilQ == 0 && cprWork >= 2 * kCprFatigueFullS) 1 else cprRecoilQ

    // adrenalina 0..1: sale in 1 min, piena fino a 2 min, finita a 5 min (Cantineau 1994: effetto misurato a 3 min)
    private fun epiShape(now: Double): Double {
        if (epiAt <= 0) return 0.0
        val x = now - epiAt
        return if (x < 0) 0.0 else if (x < 60) x / 60.0 else if (x < 120) 1.0 else if (x < 300) (300 - x) / 180.0 else 0.0
    }

    // ventilazioni nell'ultimo minuto (insufflazioni segnalate con «Ventila»)
    private fun ventPerMin(now: Double): Int { var n = 0; for (b in breaths) if (now - b < 59.5) n++; return n }   // ANDROID (24/11): < 59,5 s (10/min = 10)

    // ANDROID (RCP dettagliata 22/11): efficacia in funzione della frequenza (continua): piena tra 100 e 120/min, cala
    // gradualmente fuori. lo = pendenza per ogni compressione/min sotto 100, hi = sopra 120, min = valore minimo.
    private fun rateFactor(lo: Double, hi: Double, min: Double): Double {
        val r = cprRateCpm.toDouble()
        val f = if (r < 100) 1.0 - lo * (100 - r) else if (r > 120) 1.0 - hi * (r - 120) else 1.0
        return Math.max(min, f)
    }
    private fun rateCategory(cpm: Int): Int = if (cpm < 100) 1 else if (cpm > 120) 2 else 0

    // pressioni prodotte dalle compressioni (mmHg): picco e diastolica di rilasciamento a regime
    private fun artCprPeak(now: Double): Double {
        val qd = when (dQ()) { 1 -> 0.56; 2 -> 1.15; else -> 1.0 }
        val qr = rateFactor(0.004, 0.0075, 0.55)          // lenta: picchi quasi uguali; veloce: riempimento ridotto (140/min ~0,85)
        val qh = if (cprHandsQ == 1) 0.78 else 1.0
        val qc = if (rQ() == 1) 0.88 else 1.0
        val nv = ventPerMin(now); val qv = if (nv > 10) Math.max(0.82, 1.0 - 0.012 * (nv - 10)) else 1.0
        return 80.0 * qd * qr * qh * qc * qv * (1.0 - cprFatigueFrac()) * (1.0 + 0.12 * epiShape(now))
    }
    private fun artCprRelax(now: Double): Double {
        val qd = when (dQ()) { 1 -> 0.60; 2 -> 1.04; else -> 1.0 }
        val qr = rateFactor(0.0187, 0.005, 0.40)          // lenta: più tempo per svuotarsi (85/min ~0,72); veloce 140/min ~0,90
        val qh = if (cprHandsQ == 1) 0.82 else 1.0
        val nv = ventPerMin(now); val qv = if (nv > 10) Math.max(0.85, 1.0 - 0.01 * (nv - 10)) else 1.0
        var r = 28.0 * qd * qr * qh * qv * (1.0 - cprFatigueFrac()) * (1.0 + 0.35 * epiShape(now))
        if (rQ() == 1) r += 4.0                     // appoggio: pressione intratoracica residua (diastolica «falsamente» alta)
        return Math.max(kArtMsfp + 2, r)
    }
    private fun artBaseTarget(now: Double): Double = if (cpr) artCprRelax(now) else kArtMsfp
    private fun artBaseTau(): Double = if (cpr) 4.0 else 2.5   // la diastolica si costruisce in ~10-15 s, crolla in pochi secondi

    // EtCO2 a flusso pieno secondo la qualità della RCP (mmHg)
    private fun co2AutoTarget(now: Double): Double {
        val qd = when (dQ()) { 1 -> 0.55; 2 -> 0.92; else -> 1.0 }      // superficiale ~10, troppo profonda ~17
        val qr = rateFactor(0.0233, 0.009, 0.30)                             // lenta 85/min ~12, troppo veloce 140/min ~15 (riempimento ridotto)
        val qh = if (cprHandsQ == 1) 0.75 else 1.0                           // mani mal posizionate ~14
        val qc = if (rQ() == 1) 0.85 else 1.0                          // rilascio incompleto ~16 (meno ritorno venoso)
        val epi = 0.15 * epiShape(now)                                       // adrenalina: -15% al massimo
        return Math.max(3.0, 19.0 * qd * qr * qh * qc * (1.0 - cprFatigueFrac() - epi))
    }

    // CO2 alveolare di adesso (mmHg) nel modello: quello che una ventilazione porterebbe al sensore
    private fun co2Level(now: Double = simNow()): Double {
        if (!co2Manual) return (co2Auto + co2Excess) * co2Flow                       // ANDROID (AE-14): + CO2 accumulata
        if (co2Display == "always") return etco2.toDouble()                          // ANDROID (EtCO2 visibile 24/11): forzato e fisso
        return co2ManualTarget(now) * co2Flow
    }

    // ANDROID (EtCO2 visibile 24/11, scelta dell'utente): il valore forzato è quello «con il massaggio in corso». La qualità lo
    // modula come il modello (superficiale, lenta, mani, rilascio, stanchezza, adrenalina: stesso rapporto di co2AutoTarget), e un
    // massaggio corretto e continuo lo fa salire di qualche mmHg (+15 % in ~60 s: 15 -> ~17, 20 -> 23); la stanchezza poi lo
    // riabbassa piano (-16 % a 2 min): il cambio dell'operatore lo riporta su.
    private fun co2ManualTarget(now: Double): Double {
        val q = co2AutoTarget(now) / 19.0
        val warm = if (cpr && co2RunSince > 0) Math.min(1.0, (now - co2RunSince) / 60.0) else 0.0
        val good = dQ() == 0 && cprHandsQ == 0 && rQ() == 0 && rateCategory(cprRateCpm) == 0
        return etco2.toDouble() * q * (1.0 + (if (good) 0.15 else 0.0) * warm)
    }

    // Integra il modello d'arresto fino a `now` (soluzione esatta del primo ordine: il risultato non dipende dal passo dei
    // tick). ART sempre in arresto; EtCO2 solo con la fisiologia automatica.
    // ANDROID (25/11): la stanchezza conta OGNI secondo di compressioni, con o senza polso (dopo una scarica che converte il DAE
    // chiede comunque 2 min di RCP: prima qui non si contavano e i 2 min della stanchezza non coincidevano con quelli del DAE)
    private var cprWorkAt = 0.0
    private fun fatigueAdvance(now: Double) {
        val dt = if (cprWorkAt > 0) now - cprWorkAt else 0.0
        cprWorkAt = now
        if (!cpr || dt <= 0) return
        val w0 = cprWork; cprWork += dt
        if (w0 < kCprFatigueFullS && cprWork >= kCprFatigueFullS)
            defibLog("RCP: nessun cambio dell'operatore da 2 minuti, compressioni superficiali per stanchezza", false)
        if (w0 < 2 * kCprFatigueFullS && cprWork >= 2 * kCprFatigueFullS)
            defibLog("RCP: nessun cambio dell'operatore da 4 minuti, anche il rilascio è incompleto", false)
    }
    private fun co2Advance(now: Double) {
        fatigueAdvance(now)
        if (effectivePulse()) { co2LastAt = 0.0; return }   // ANDROID (EM-07)
        if (co2LastAt <= 0) {                                   // modello avviato a metà (ripristino, autoPhysio riattivata)
            co2LastAt = now; co2Flow = if (cpr) 1.0 else 0.0; co2Auto = co2AutoTarget(now); artBase = artBaseTarget(now)
        }
        val dt = now - co2LastAt
        if (dt > 0) {
            co2LastAt = now
            if (cpr) { co2CprSeen = true; if (co2RunSince <= 0) co2RunSince = now } else co2RunSince = 0.0   // ANDROID (EtCO2 visibile 24/11)
            if (epiAt > 0 && now - epiAt >= 300) epiAt = 0.0
            val bt = artBaseTarget(now)
            artBase = bt + (artBase - bt) * Math.exp(-dt / artBaseTau())
            if (autoPhysio) {
                // flusso: le compressioni lo portano su in ~20 s (tau 8 s), la pausa lo azzera in pochi secondi (tau 3 s)
                val ft = if (cpr) 1.0 else 0.0
                // ANDROID (EtCO2 visibile 24/11, verificato sulla letteratura): con le compressioni il flusso sale in ~20 s; nella
                // PAUSA non cala col tempo ma a ogni insufflazione (-11 % per ventilazione senza ROSC: Gutiérrez et al., PLoS ONE
                // 2021;16:e0251511, pause di ~16 s), vedi insufflate: senza ventilazioni la CO2 resta negli alveoli
                if (cpr) co2Flow = 1.0 + (co2Flow - 1.0) * Math.exp(-dt / 8.0)
                val at = co2AutoTarget(now)                                   // cambio di qualità: si vede in ~20-30 s
                co2Auto = at + (co2Auto - at) * Math.exp(-dt / 10.0)
                if (co2Excess > 0) co2Excess *= Math.exp(-dt / (if (cpr) 70.0 else 600.0))   // ANDROID (AE-14): lavaggio in 2-3 min di RCP
            }
        }
        if (co2ModelOn() && !co2Manual) {
            val v = Math.max(kEtCO2Min, Math.min(kEtCO2Max, SLLround((co2Auto + co2Excess) * co2Flow)))
            if (v != etco2) { etco2 = v; revision++ }
        }
    }

    /** ANDROID (EtCO2 visibile 24/11): espirazioni che il monitor genera da solo in arresto (vedi co2Display). Niente se ventila
     *  già il ventilatore/pallone automatico, in modalità realistica, col polso; un «Ventila» recente (< 4 s) fa saltare il turno. */
    private fun co2AutoBreaths(now: Double, autoRate: Int) {
        if (co2Display == "realistic" || autoRate > 0 || effectivePulse()) { co2AutoNext = 0.0; co2AutoSecond = false; return }
        if (cpr) co2CprSeen = true
        val continuous = co2Display == "always" || (co2Display == "auto" && airwayAdv)
        if (!co2CprSeen && co2Display != "always") { co2AutoNext = 0.0; return }   // prima della RCP nessuno ventila ancora
        if (!continuous && !cpr) { co2AutoNext = 0.0; co2AutoSecond = false; return }   // 30:2: in pausa (analisi, polso) niente insufflazioni
        val cycle = 30.0 * 60.0 / Math.max(40, cprRateCpm) + 4.0                  // 30 compressioni + le 2 insufflazioni
        if (co2AutoNext <= 0 || co2AutoNext < now - 15) { co2AutoNext = now + (if (continuous) 1.0 else cycle - 4.0); co2AutoSecond = false }
        var guard = 0
        while (now >= co2AutoNext && guard++ < 20) {
            val t = co2AutoNext
            val recent = breaths.isNotEmpty() && t - breaths.last() < 4.0 && t - breaths.last() > -0.5
            if (!recent || co2AutoSecond) { insufflate(t); revision++ }
            if (continuous) co2AutoNext = t + 6.0
            else if (!co2AutoSecond) { co2AutoSecond = true; co2AutoNext = t + 2.5 }
            else { co2AutoSecond = false; co2AutoNext = t - 2.5 + cycle }
        }
    }

    /** ANDROID (EtCO2 RCP 22/11): cambio dell'operatore alle compressioni (comando cpr_rescuer_change o voce di registro):
     *  finisce l'affaticamento */
    /** il monitor riferisce il volume di sistema che ha letto dal tablet (in percento) */
    fun reportTabletVolume(percent: Int) { synchronized(this) { val v = percent.coerceIn(0, 100); if (v != tabletVolume) { tabletVolume = v; revision++ } } }
    fun noteCprRescuerSwap() { synchronized(this) { co2Advance(simNow()); cprWork = 0.0; revision++ } }
    /** ANDROID (EtCO2 RCP 22/11): adrenalina registrata: in arresto EtCO2 un po' più bassa e diastolica ART più alta per qualche minuto */
    fun noteEpinephrine() { synchronized(this) { if (!rhythmHasPulse(rhythm)) { co2Advance(simNow()); epiAt = simNow(); revision++ } } }

    // Apnea con polso: dopo ~20 s la SpO2 scende progressivamente (~0,35 %/s); alla ripresa del respiro risale
    private fun apneaPhysiologyAt(now: Double) {
        val pulse = effectivePulse()   // ANDROID (EM-07)
        if (autoPhysio && pulse && rr == 0 && !ramps.containsKey("respiratory_rate_rpm")) {
            if (apneaSince == 0.0) { apneaSince = now; spo2PreApnea = spo2 }
            // ANDROID (difetti clinici 22/11, AE-11): il valore che l'istruttore dà con una rampa durante l'apnea è il nuovo punto di
            // partenza (prima a fine rampa ricadeva al valore calcolato dall'inizio dell'apnea); con una ventilazione efficace
            // (>= 6 insufflazioni/min) la SpO2 non scende (EM-01, parte)
            if (ramps.containsKey("spo2_percent") || ventPerMin(now) >= 6) { apneaSince = now; spo2PreApnea = spo2 }
            val el = now - apneaSince
            if (el > 20 && !ramps.containsKey("spo2_percent")) {
                val v = Math.max(40, spo2PreApnea - Math.floor((el - 20) * 0.35).toInt())
                if (v < spo2) { spo2 = v; revision++ }
            }
        } else if (apneaSince > 0) {
            if (pulse && rr > 0 && autoPhysio && spo2 < spo2PreApnea) rampParam("spo2_percent", spo2PreApnea, 40.0)
            apneaSince = 0.0
        }
    }

    // ANDROID (funzioni mancanti 22/11): una insufflazione (Ventila dei partecipanti o atto automatico del ventilatore/pallone):
    // onda di CO2 e valore a monitor (stesso corpo di prima del comando ventilate)
    private fun insufflate(now: Double) {
        if (breaths.isEmpty() || now - breaths.last() > 0.8) {    // due tocchi ravvicinati = uno
            // ANDROID (EtCO2 RCP 22/11): ogni insufflazione porta al sensore la CO2 alveolare di quel momento
            var v = etco2.toDouble()
            if (apneaCo2On()) {                                          // ANDROID (EM-02)
                v = apneaCo2Now(now)
                apCo2 = 40.0 + (apCo2 - 40.0) * Math.exp(-1.0 / 25.0); apVentAt = now
            }
            if (co2ModelOn()) {
                co2Advance(now)
                v = co2Level(now)
                if (!(co2Manual && co2Display == "always")) {                // ANDROID (EtCO2 visibile 24/11): forzato e fisso = esatto
                    // ventilazioni nell'ultimo minuto; ANDROID (24/11): finestra < 59,5 s, così 10/min esatte (un atto ogni 6 s, uno
                    // proprio a -60 s) non contano 11 e non passano per iperventilazione
                    val n = breaths.count { now - it < 59.5 } + 1
                    if (n > 10) v *= Math.max(0.45, Math.pow(10.0 / n, 0.6))     // iperventilazione: meno CO2 per atto
                    if (breaths.isNotEmpty() && now - breaths.last() < 6) v *= 0.92   // 2a insufflazione del 30:2: un po' meno
                }
            }
            breaths.add(now); breathVals.add(Math.max(0, Math.min(kEtCO2Max, SLLround(v))))
            if (co2ModelOn() && !cpr) co2Flow *= 0.89                        // ANDROID (24/11): pausa, ogni atto lava l'11 % (Gutiérrez 2021)
        }
    }

    // MARK: ANDROID (funzioni mancanti 22/11) — linea arteriosa, PVC, ventilazione, eventi per il registro, trend

    /** il ventilatore insuffla da solo (non scollegato) */
    private fun ventAuto(): Boolean = ventModeK == "ventilator" && !ventDisc
    /** OP-05: ampiezza della variazione respiratoria della pressione col ventilatore (0..0,3): impostata o dall'indice di shock */
    private fun ppvNow(): Double {
        if (ppvSet >= 0) return ppvSet / 100.0
        if (!effectivePulse() || sys <= 0) return 0.05
        val si = hr.toDouble() / Math.max(40, sys)
        return 0.05 + 0.15 * Math.max(0.0, Math.min(1.0, (si - 0.8) / 0.6))
    }
    private fun stEvent(kind: String, text: String, tag: String?, by: String? = SLCommandSource.current() ?: "partecipanti", extra: Map<String, Any?>? = null) {
        stEvents.add(SLStateEvent(++stEvSeq, kind, text, tag, by, extra)); while (stEvents.size > 300) stEvents.removeAt(0)
    }
    /** voci per il registro prodotte dal cuore dopo il numero seq (SFSessionLog) */
    fun stateEventsSince(seq: Int): List<SLStateEvent> = synchronized(this) { stEvents.filter { it.seq > seq } }
    fun stateEventSeq(): Int = synchronized(this) { stEvSeq }
    /** IN-14: righe del trend (t = secondi di scenario) da since, una ogni step secondi */
    fun trendSince(since: Double, step: Int): List<Map<String, Any?>> = trend.since(since, step)
    fun trendIntervalS(): Double = trend.intervalS
    private var zeroBy: String? = null
    private var trendNibpAt: Long = -1L
    private fun ventMinuteAlv(rate: Int, tidal: Int): Double = rate * Math.max(50.0, tidal - 150.0)
    private fun ventCoupleEtco2(oldVA: Double, newVA: Double) {
        if (!autoPhysio || !effectivePulse() || oldVA <= 0 || newVA <= 0 || Math.abs(oldVA - newVA) < 1 || etco2 <= 0) return
        val target = SLLround(Math.max(15.0, Math.min(90.0, etco2.toDouble() * oldVA / newVA)))
        rampParam("etco2_mmhg", target, if (target < etco2) 180.0 else 300.0)   // AE-13: minuti, come la FR
    }
    private fun setCvpWave(v: Any?) {
        if (v !is String || v !in listOf("normal", "large_v", "tamponade")) SLFail("cvp waveform: normal, large_v or tamponade")
        cvpWaveK = v
    }
    private fun techName(kind: String): String = when (kind) {
        "ecg_leads_off" -> "Elettrodi ECG ricollegati"; "spo2_probe_off" -> "Sonda SpO₂ ricollegata"; "spo2_signal" -> "Sonda SpO₂ riposizionata"
        "nibp_cuff_error" -> "Bracciale NIBP controllato e riposizionato"; "nibp_timeout" -> "Bracciale NIBP controllato"
        "etco2_occlusion" -> "Linea della CO₂ sostituita"; "ecg_electrode" -> "Elettrodo ECG riattaccato"; "ecg_noise" -> "Elettrodi ECG sostituiti (cute preparata)"
        "art_line" -> "Linea arteriosa controllata (lavata, rubinetti aperti)"; "low_battery" -> "Batteria sostituita"
        else -> kind
    }
    /** IN-14: una riga del trend con i numeri come li mostra il monitor (null = «---» o sensore non collegato) */
    private fun trendSample() {
        if (!scenStarted || !running) return
        val t = scenTimeNow()
        if (!trend.due(t)) return
        val s = snapshot()
        fun n(v: Int): Any? = if (v >= 0) v.toLong() else null
        val row = linkedMapOf<String, Any?>("t" to Math.floor(t).toLong(), "w" to wallNowMs() / 1000, "rhythm" to s.rhythm,
            "hr" to (if (s.ecgConnected || s.padsConnected) n(if (s.monDisplayedHeartRate >= 0) s.monDisplayedHeartRate else -1) else null),
            "spo2" to (if (s.spo2Displayable) s.monSpo2.toLong() else null), "pr" to (if (s.spo2Displayable) s.monPulseRate.toLong() else null),
            "rr" to (if (s.etco2Connected || s.ecgConnected) s.monRespRate.toLong() else null),
            "etco2" to (if (s.etco2Connected && s.respRate > 0) s.monEtco2.toLong() else null),
            "art_s" to (if (s.artConnected && s.monArtSystolic >= 0 && !s.artNoNumbers) s.monArtSystolic.toLong() else null),
            "art_d" to (if (s.artConnected && s.monArtDiastolic >= 0 && !s.artNoNumbers) s.monArtDiastolic.toLong() else null),
            "art_m" to (if (s.artConnected) n(s.artDisplayedMap) else null),
            "temp" to (if (s.tempConnected) s.monTemperatureTenths / 10.0 else null),
            "temp2" to (if (s.temp2Connected) s.monTemperature2Tenths / 10.0 else null),
            "cvp" to n(s.monCvp), "ppv" to n(s.ppvShown))
        val nAt = nibpMeasuredAt?.time ?: -1L
        if (nibpLastSys >= 0 && nibpLastDia >= 0) {
            row["nibp_s"] = nibpLastSys.toLong(); row["nibp_d"] = nibpLastDia.toLong(); row["nibp_m"] = SLLround((nibpLastSys + 2.0 * nibpLastDia) / 3.0).toLong()
            row["nibp_new"] = nAt != trendNibpAt
        }
        trendNibpAt = nAt
        trend.add(t, row)
    }
    /** a ogni tick: azzeramento che finisce, ultimo respiro (apnea), insufflazioni automatiche, DAE senza RCP, trend */
    private fun tickFunzioni(now: Double) {
        val ck = clock()
        if (artZeroUntil > 0 && ck >= artZeroUntil) {
            artZeroUntil = 0.0
            if (artOn) artZeroed = true
            if (cvpOn) cvpZeroed = true
            artZeroedMs = wallNowMs()
            stEvent("monitor", "Pressioni invasive azzerate (ore ${synchronized(logTimeFormat) { logTimeFormat.format(Date(artZeroedMs)) }})", "art_zero", zeroBy)
            revision++
        }
        val manual = cpr || rr < 1 || co2ModelOn() || ventModeK == "bag"
        if (!manual) lastRespAt = now
        else if (breaths.isNotEmpty()) lastRespAt = Math.max(lastRespAt, breaths.last())
        // CC-04: il ventilatore continua a insufflare (arresto, RCP); AE-12: pallone su via aerea avanzata in RCP 10/min
        val autoRate = if (ventAuto() && manual) ventRateV else if (ventModeK == "bag" && airwayAdv && cpr) 10 else 0
        if (autoRate > 0) {
            if (ventNext <= 0 || ventNext < now - 10) ventNext = now
            var g = 0
            while (now >= ventNext && g++ < 20) { insufflate(ventNext); ventNext += 60.0 / autoRate; revision++ }
        } else ventNext = 0.0
        co2AutoBreaths(now, autoRate)                                     // ANDROID (EtCO2 visibile 24/11)
        if (ventAuto() && effectivePulse() && rr >= 1 && !ramps.containsKey("respiratory_rate_rpm")) ventRateV = rr   // la FR impostata = il ventilatore
        trendSample()
    }

    // FC 0 in un ritmo organizzato = asistolia (non una linea piatta artificiale)
    private fun asystoleIfZeroRate() {
        val organized = !(rhythm == SLRhythmVentricularFib || rhythm == SLRhythmAsystole)
        if (hr == 0 && organized) applyRhythm(SLRhythmAsystole)
    }

    // MARK: ANDROID (transizioni ritmo 22/11) — PRELUDIO del cambio di ritmo
    //
    // Nella realtà un ritmo nuovo non parte «come uno sparo»: la FV è preceduta da extrasistoli (R su T) o da una TV che
    // degenera; la TV parte con un'extrasistole o dopo salve non sostenute; la FA da extrasistoli atriali; l'asistolia da una
    // bradicardia progressiva / blocco AV o dalla FV che si affina; la PEA da tachicardia con pressione che crolla oppure da
    // bradicardia con QRS che si allarga; il ROSC da una pausa elettrica e primi complessi lenti (fonti in SFRhythmOnset.kt
    // e TRANSIZIONI_RITMI.md). Regole:
    //  - "onset": "auto" (di serie) = preludio; "immediate" = di colpo, come prima. Vale per set_rhythm e per transition
    //    (anche le fasi degli scenari e i passi delle catene; la prima fase di uno scenario parte sempre di colpo);
    //  - il preludio COMINCIA SUBITO e arriva al ritmo di arresto entro pochi secondi (FV/TV 2-6 s; asistolia/PEA da un ritmo
    //    con polso 10-18 s; FV -> asistolia 8-10 s); il ritmo (polso, allarmi, DAE) resta quello di partenza finché il ritmo
    //    nuovo non arriva; alla fine si applica il ritmo (con le reazioni di arresto/ROSC) e gli eventuali parametri della
    //    transizione, come se fossero arrivati in quel momento;
    //  - un nuovo comando di ritmo (o una scarica che converte, o il reset) lo interrompe e lo sostituisce; i parametri mossi
    //    dal preludio tornano ai valori di partenza in ~8 s;
    //  - in pausa si ferma (tempo clinico); una sola voce di registro quando il ritmo arriva («Ritmo: FV — insorgenza: …»);
    //  - tra ritmi sinusali (sinusale / tachicardia / bradicardia) con set_rhythm il ritmo cambia subito ma la FC va al valore
    //    tipico con una rampa di 12 s (prima: a scatto).
    private fun onsetModeOf(v: Any?, what: String): Boolean {   // true = auto
        if (v == null) return true
        if (v == "auto") return true
        if (v == "immediate") return false
        SLFail("$what: onset must be \"auto\" or \"immediate\"")
    }

    // Avvia il preludio verso `to` (deferred = transizione da applicare all'arrivo). false = nessuna insorgenza tipica.
    private fun startOnset(to: String, deferred: Map<*, *>?): Boolean {
        onsetSeq++
        val vfA = if (rhythm == SLRhythmVentricularFib) vfAmpNow(simNow()) else vfAmp
        val p = SFRhythmOnset.build(onsetSeq, rhythm, to, hr, sys, dia, vfA, autoPhysio,
                                    if (avBlockActive()) ecgMorph.avBlock else "none", atrialRateNow()) ?: return false   // ANDROID (12 derivazioni 22/11)
        onset = p; onsetStart = simNow(); onsetActIdx = 0; onsetPhaseIdx = 0; onsetDeferred = deferred
        onsetPre = linkedMapOf("heart_rate_bpm" to hr, "systolic_mmhg" to sys, "diastolic_mmhg" to dia)
        onsetBaselineSet = false
        // verso un arresto: i valori pre-arresto (per il ROSC) sono quelli di PRIMA del preludio
        if (autoPhysio && rhythmHasPulse(rhythm) && !rhythmHasPulse(to) && baseline == null) {
            baseline = linkedMapOf("spo2_percent" to spo2, "respiratory_rate_rpm" to rr, "etco2_mmhg" to etco2,
                                   "systolic_mmhg" to sys, "diastolic_mmhg" to dia)
            onsetBaselineSet = true
        }
        onsetAdvance(onsetStart)
        return true
    }

    // Interrompe il preludio in corso (nuovo comando di ritmo, scarica, ripristino): i parametri mossi tornano indietro
    private fun cancelOnset() {
        val p = onset ?: return
        onset = null; onsetDeferred = null; pendingVfAmp = null
        val pre = onsetPre
        if (pre != null) for (key in p.actions.take(onsetActIdx).map { it.key }.distinct()) {
            val v = pre[key] ?: continue
            if (v != param(key)) rampParam(key, v, 8.0) else ramps.remove(key)
        }
        if (onsetBaselineSet && rhythmHasPulse(rhythm)) baseline = null
        onsetPre = null; onsetBaselineSet = false
        revision++
    }

    // Avanza il preludio fino a `now` (azioni sui parametri all'inizio di ogni fase, arrivo del ritmo)
    private fun onsetAdvance(now: Double) {
        val p = onset ?: return
        val off = now - onsetStart
        while (onsetActIdx < p.actions.size && p.actions[onsetActIdx].at <= off) {
            val a = p.actions[onsetActIdx]
            rampStartOverride = onsetStart + a.at
            rampParam(a.key, a.to, a.dur)
            rampStartOverride = 0.0
            onsetActIdx++
            if (ramps.isNotEmpty()) advanceRampsAt(now)
            revision++
        }
        val k = p.phaseIndexAt(off)
        if (k != onsetPhaseIdx) { onsetPhaseIdx = k; revision++ }
        if (off >= p.duration) finishOnset(p)
    }

    // Il ritmo nuovo arriva: si applica (reazioni di arresto/ROSC) con l'eventuale transizione rimandata
    private fun finishOnset(p: SLOnsetPlan) {
        if (ramps.isNotEmpty()) advanceRampsAt(onsetStart + p.duration)
        for (a in p.actions) { val r = ramps[a.key]; if (r != null && r.start + r.dur <= onsetStart + p.duration + 0.01) ramps.remove(a.key) }
        onset = null; onsetPre = null
        val d = onsetDeferred; onsetDeferred = null
        onsetLast = linkedMapOf("id" to p.id.toLong(), "from" to p.from, "to" to p.to, "text" to p.text, "variant" to p.variant)
        val hrRamp0 = ramps["heart_rate_bpm"]
        if (d != null) transition(d, true, false) else applyRhythm(p.to)
        pendingVfAmp?.let { if (rhythm == SLRhythmVentricularFib) vfManual = it }; pendingVfAmp = null
        // ROSC: i primi complessi sono lenti e accelerano verso la FC del ritmo nuovo
        if (p.roscHrStart > 0 && rhythm == p.to && rhythmHasPulse(rhythm)) {
            val r = ramps["heart_rate_bpm"]
            val target = if (r != null && r !== hrRamp0) r.to else hr
            val dur = if (r != null && r !== hrRamp0) Math.max(12.0, r.start + r.dur - simNow()) else 15.0
            if (target > p.roscHrStart) { hr = p.roscHrStart; rampStartOverride = 0.0; ramps.remove("heart_rate_bpm"); rampParam("heart_rate_bpm", target, dur) }
        }
        onsetBaselineSet = false
        revision++
    }

    // Comando di ritmo (set_rhythm): con "auto" preludio o rampa di FC fra ritmi sinusali; con "immediate" come prima
    private fun setRhythmCommand(to: String, auto: Boolean) {
        cancelOnset()
        if (auto && to != rhythm) {
            if (SFRhythmOnset.isSinusFamily(rhythm) && SFRhythmOnset.isSinusFamily(to)) {
                val hr0 = hr
                applyRhythm(to)
                if (hr != hr0 && hr0 > 0) { val t = hr; hr = hr0; rampParam("heart_rate_bpm", t, 12.0) }
                return
            }
            if (startOnset(to, null)) return
        }
        applyRhythm(to)
    }

    // MARK: ANDROID (ampiezza FV 22/11) — evoluzione dell'ampiezza e della frequenza della FV (vedi SFRhythmOnset.kt)
    // Priorità: il valore dell'istruttore (set_vf_amplitude, o "vf_amplitude" con set_rhythm vf) vale finché il ritmo non
    // cambia (come per l'EtCO2 «instructor»); "auto" torna all'evoluzione automatica. Durante il preludio FV -> asistolia
    // decide il preludio (la FV si affina).
    private fun vfAdvance(now: Double) {
        if (rhythm != SLRhythmVentricularFib) { vfLastAt = 0.0; return }
        if (vfLastAt <= 0) vfLastAt = now
        val dt = now - vfLastAt
        if (dt <= 0) return
        vfLastAt = now
        if (vfManual > 0) return
        // obiettivo e costante di tempo: senza RCP si affina (min); RCP buona: si mantiene/migliora; RCP scadente: cala piano
        val good = cpr && dQ() != 1 && cprRateQ == 0 && cprHandsQ == 0
        val (at, atau, ft, ftau) = when {
            good -> listOf(0.85, 120.0, 1.05, 120.0)
            cpr -> listOf(0.05, 900.0, 0.9, 600.0)
            else -> listOf(0.03, 480.0, 0.7, 480.0)
        }
        vfAmp = at + (vfAmp - at) * Math.exp(-dt / atau)
        vfFreq = ft + (vfFreq - ft) * Math.exp(-dt / ftau)
    }
    // ampiezza adesso (mV): istruttore > preludio > modello (con l'effetto dell'adrenalina)
    private fun vfAmpNow(now: Double): Double {
        if (vfManual > 0) return vfManual
        val p = onset
        if (p != null && p.vfAmpFrom > 0) return p.vfAmpAt(now - onsetStart)
        return Math.max(SLVfMinMv * 0.6, Math.min(SLVfMaxMv, vfAmp * (1.0 + 0.35 * epiShape(now))))
    }
    private fun vfFreqNow(now: Double): Double {
        val p = onset
        if (p != null && p.vfAmpFrom > 0) return vfFreq * p.vfFreqAt(now - onsetStart)
        return vfFreq * (1.0 + 0.08 * epiShape(now))
    }
    // valori per il motore: si pubblicano (revision) quando cambiano di almeno il 2% (1% la frequenza)
    private fun vfPublish(now: Double) {
        if (rhythm != SLRhythmVentricularFib) return
        val a = vfAmpNow(now); val f = vfFreqNow(now)
        if (Math.abs(a - vfShownAmp) > 0.02 * vfShownAmp || Math.abs(f - vfShownFreq) > 0.01 * vfShownFreq) { vfShownAmp = a; vfShownFreq = f; revision++ }
    }
    // il DAE riconosce la FV solo sopra la soglia di ampiezza
    private fun vfDetectable(): Boolean = rhythm != SLRhythmVentricularFib || vfAmpNow(simNow()) >= SLAedVfMinMv
    private fun parseVfAmplitude(v: Any?): Double {   // -1 = auto
        if (v == "auto") return -1.0
        if (v == "coarse") return SLVfCoarseMv
        if (v == "fine") return SLVfFineMv
        if (v is Number && v !is Boolean) { val x = v.toDouble(); if (x >= SLVfMinMv && x <= SLVfMaxMv) return Math.round(x * 100) / 100.0 }
        SLFail("vf_amplitude: \"coarse\", \"fine\", \"auto\" or mV 0.05-1.5")
    }

    // Valida una transizione {params:{...}, duration_s, rhythm?}; se apply, la esegue.
    // (lancia SLCmdError con il messaggio se non è valida)
    // MARK: ANDROID (12 derivazioni 22/11) — morfologia dell'ECG e blocchi AV (SFEcgMorphology.kt, ECG_12D.md)
    //
    // La morfologia è del PAZIENTE (substrato), separata dal ritmo: resta anche se il ritmo cambia (uno STEMI resta dopo FV e
    // ROSC); si azzera con reset_scenario o con "normal". I blocchi AV sono disturbi di CONDUZIONE: li genera il motore (onde P,
    // PR, battiti non condotti, dissociazione AV con scappamento). Valgono con i ritmi sinusali; con la FA solo il BAV 3°
    // (FA con risposta ventricolare lenta e regolare); in asistolia i blocchi avanzati lasciano le onde P (asistolia
    // ventricolare). La FC (heart_rate_bpm) è sempre quella VENTRICOLARE (QRS, polso, numeri a schermo, allarmi).
    private fun escapeRate(): Int = if (ecgMorph.escape == "junctional") 45 else 35
    private fun atrialRateNow(): Int {
        val k = SFEcgMorphology.avRatio(ecgMorph.avBlock)
        return when {
            k > 0 -> Math.min(200, SLLround(hr * k))
            ecgMorph.avBlock == "third" -> atrialHr
            else -> hr
        }
    }
    private fun avBlockActive(): Boolean = when {
        ecgMorph.avBlock == "none" -> false
        SFRhythmOnset.isSinusFamily(rhythm) -> true
        rhythm == SLRhythmAtrialFibrillation -> ecgMorph.avBlock == "third"
        rhythm == SLRhythmAsystole -> ecgMorph.avBlock in listOf("mobitz2", "2to1", "high_grade", "third")
        else -> false
    }
    // Nuova morfologia: con un cambio del blocco AV la FC ventricolare diventa coerente subito (il blocco si instaura di colpo)
    private fun applyEcgMorph(m: SLEcgMorph, log: Boolean) {
        val old = ecgMorph
        if (old == m) return
        if (old.avBlock != m.avBlock || (m.avBlock == "third" && old.escape != m.escape)) {
            val sinusLike = SFRhythmOnset.isSinusFamily(rhythm) || (rhythm == SLRhythmAtrialFibrillation && (m.avBlock == "third" || old.avBlock == "third"))
            val atrial0 = atrialRateNow()                  // frequenza degli atri adesso (con il blocco di prima)
            ecgMorph = m
            if (sinusLike) {
                ramps.remove("heart_rate_bpm")
                val k = SFEcgMorphology.avRatio(m.avBlock)
                when {
                    k > 0 -> hr = Math.max(15, SLLround(atrial0 / k))
                    m.avBlock == "third" -> { if (old.avBlock != "third") atrialHr = Math.max(40, Math.min(150, atrial0)); hr = escapeRate() }
                    else -> hr = atrial0                   // blocco tolto (o 1° grado): i ventricoli seguono di nuovo gli atri
                }
            } else if (m.avBlock == "third" && old.avBlock != "third") atrialHr = 80
        }
        ecgMorph = m
        if (log) defibLog("ECG: " + SFEcgMorphology.describe(m), true)
        revision++
    }

    private fun transition(value: Any?, apply: Boolean, onsetOk: Boolean = true) {
        if (value !is Map<*, *>) SLFail("transition: value must be an object")
        val params: Any? = if (value.containsKey("params")) value["params"] else emptyMap<String, Any?>()
        if (params !is Map<*, *>) SLFail("transition: params must be an object")
        var dur = 0
        if (value.containsKey("duration_s")) dur = SLIntValue(value["duration_s"], 0, 3600, "duration_s")
        val rhythm = value["rhythm"]
        if (rhythm != null && (rhythm !is String || !supportedRhythms().contains(rhythm))) {
            SLFail("transition: unsupported rhythm")
        }
        // ANDROID (12 derivazioni 22/11): "ecg_morphology" facoltativo (stesso valore di set_ecg_morphology), vale all'arrivo
        var morph: SLEcgMorph? = null
        if (value.containsKey("ecg_morphology")) {
            val (mm, err) = SFEcgMorphology.parse(value["ecg_morphology"], ecgMorph)
            if (err != null) SLFail(err.replace("set_ecg_morphology", "transition: ecg_morphology"))
            morph = mm
        }
        if (params.isEmpty() && rhythm == null && morph == null) SLFail("transition: nothing to change")
        val ranges = SLParamRanges()
        val targets = LinkedHashMap<String, Int>()
        for ((kk, pv) in params) {
            val k = kk.toString()
            val r = ranges[k] ?: SLFail("transition: unknown parameter '$k'")
            val v = SLIntValue(pv, r[0], r[1], k)
            targets[k] = v
        }
        val s = targets["systolic_mmhg"] ?: sys
        val d = targets["diastolic_mmhg"] ?: dia
        // ANDROID (audit 26/11, BUG-A6): anche nel salvataggio degli scenari (apply = false) se la fase ha ENTRAMBI i valori
        // (prima la coppia invertita si salvava e la fase poi «non partiva» davanti ai partecipanti)
        val pair = targets.containsKey("systolic_mmhg") && targets.containsKey("diastolic_mmhg")
        if ((apply || pair) && d >= s && s > 0) SLFail("transition: diastolic must be lower than systolic")
        val auto = onsetModeOf(value["onset"], "transition")   // ANDROID (transizioni ritmo 22/11)
        if (!apply) return
        // ANDROID (transizioni ritmo 22/11): con un ritmo nuovo (non tra sinusali con una durata: quella è già una rampa)
        // il preludio parte subito e la transizione intera (ritmo + parametri) si applica all'arrivo del ritmo
        if (rhythm is String && onsetOk) {
            cancelOnset()
            val sinusRamp = SFRhythmOnset.isSinusFamily(this.rhythm) && SFRhythmOnset.isSinusFamily(rhythm)
            if (auto && rhythm != this.rhythm && !sinusRamp && startOnset(rhythm, LinkedHashMap(value))) return
        }
        val pulseBefore = hemoPulse ?: rhythmHasPulse(this.rhythm)        // ANDROID (EtCO2 RCP 22/11; EM-07: polso effettivo)
        if (morph != null) applyEcgMorph(morph, true)        // ANDROID (12 derivazioni 22/11): prima del ritmo (FC coerente col blocco)
        if (rhythm is String) {
            // tra ritmi sinusali (sinusale / tachicardia / bradicardia) la frequenza cambia gradualmente nel tempo scelto:
            // il ritmo cambia subito, la FC va verso il valore tipico del nuovo ritmo con una rampa (non a scatto)
            val gradual = listOf(SLRhythmSinus, SLRhythmSinusTachycardia, SLRhythmSinusBradycardia)
            val wasGradual = gradual.contains(this.rhythm)
            val ramp = dur > 0 && !targets.containsKey("heart_rate_bpm") && gradual.contains(this.rhythm) && gradual.contains(rhythm)
            val hr0 = hr
            applyRhythm(rhythm)
            if (ramp && hr != hr0) { val to = hr; hr = hr0; targets["heart_rate_bpm"] = to }
            // ritmo sinusale -> sinusale con una FC di arrivo e una durata: la rampa parte dalla FC di adesso,
            // non dal valore tipico del nuovo ritmo (prima faceva 96 -> 130 di scatto -> 122)
            else if (dur > 0 && targets.containsKey("heart_rate_bpm") && gradual.contains(rhythm) && wasGradual && hr0 > 0) hr = hr0
        }
        val oldRR = rr
        // ANDROID (EtCO2 RCP 22/11): EtCO2 scelta durante l'arresto = valore dell'istruttore (vedi «PRIORITÀ»); nella fase che
        // avvia l'arresto il valore si ignora (lo scenario descrive il paziente di prima, il crollo lo fa il modello)
        if (targets.containsKey("etco2_mmhg") && co2ModelOn()) {
            if (pulseBefore) targets.remove("etco2_mmhg")
            else { co2Advance(simNow()); co2Manual = true }
        }
        // ANDROID (difetti clinici 22/11, EM-06): fase di ROSC con un'EtCO2: il valore della fase vale (prima la rampa programmata
        // del ROSC lo riportava al valore pre-arresto dopo 6 s) con il picco da wash-out (+12) nei primi secondi
        if (autoPhysio && !pulseBefore && effectivePulse()) {
            val te = targets.remove("etco2_mmhg")
            if (te != null) {
                scheduled.removeAll { it.key == "etco2_mmhg" }
                rampParam("etco2_mmhg", Math.min(90, Math.max(te, etco2) + 12), 6.0)
                scheduled.add(SLScheduled(simNow() + 6, "etco2_mmhg", te, Math.max(30.0, dur.toDouble())))
            }
        }
        for ((k, t) in targets) rampParam(k, t, dur.toDouble())
        val trr = targets["respiratory_rate_rpm"]
        if (trr != null && !targets.containsKey("etco2_mmhg"))
            coupleEtCO2FromRR(oldRR, trr, dur.toDouble())
    }

    private fun advanceRampsAt(now: Double) {
        var changed = false
        for (k in ArrayList(ramps.keys)) {
            val r = ramps[k] ?: continue
            var x = if (r.dur > 0) (now - r.start) / r.dur else 1.0
            x = Math.max(0.0, Math.min(1.0, x))
            val from = r.from.toDouble()
            val to = r.to.toDouble()
            val v = SLLround(from + (to - from) * x)
            if (v != param(k)) { setParam(k, v); changed = true }
            if (x >= 1) ramps.remove(k)
        }
        if (dia >= sys && sys > 0) { dia = Math.max(0, sys - 1); changed = true }
        if (changed) { asystoleIfZeroRate(); revision++ }
    }

    private fun advanceChainAt(now: Double) {
        var guard = 0
        while (chainActive && guard < 64) {
            val steps = chainSteps
            if (steps == null || chainIndex >= steps.size) { chainActive = false; revision++; return }
            val st = steps[chainIndex] as Map<*, *>
            val wait = (st["wait_s"] as? Number)?.toDouble() ?: 0.0
            val dur = (st["duration_s"] as? Number)?.toDouble() ?: 0.0
            if (chainWaiting) {
                if (now - chainPhaseStart < wait) return
                chainPhaseStart = chainPhaseStart + wait
                rampStartOverride = chainPhaseStart
                try { transition(st, true) } catch (e: SLCmdError) { /* come error:NULL sull'iPad */ }
                rampStartOverride = 0.0
                chainWaiting = false; revision++
            } else {
                if (now - chainPhaseStart < dur) return
                chainIndex++; chainWaiting = true; chainPhaseStart = chainPhaseStart + dur; revision++
            }
            guard++
        }
    }

    // MARK: Defibrillatore (chiamare con synchronized già acquisito)

    private fun cfg(k: String): Int = (dCfg[k] as? Number)?.toInt() ?: 0
    private fun cfgBool(k: String): Boolean = dCfg[k] == true

    private fun defibLog(text: String, instructor: Boolean) {
        val now = clock()
        val el = if (dSession > 0) Math.max(0.0, now - dSession).toInt() else 0
        dEvSeq++
        dEvents.add(linkedMapOf("time" to logTimeFormat.format(Date(wallNowMs())),   // ANDROID (ora 22/11)
                                "elapsed" to String.format(Locale.US, "%02d:%02d", el / 60, el % 60),
                                "text" to text, "instructor" to instructor, "seq" to dEvSeq.toLong()))
        while (dEvents.size > 60) dEvents.removeAt(0)
    }

    private fun defibNote(note: String) { dNote = note; dNoteAt = simNow() }

    // Messaggio vocale del DAE: nuovo prompt (la UI lo pronuncia quando cambia promptSeq)
    private fun aedSay(text: String) { dVoice = text; dPromptSeq++ }

    private fun aedPhase(ph: SLAedPhase, say: String?) {
        aedPhase = ph; aedPhaseStart = simNow(); aedMotion = false
        if (ph == SLAedCPR) cprFbAt = simNow()               // ANDROID (audit 25/11): il primo consiglio non copre «Iniziare la RCP»
        aedAwait = false; aedMotionOverride = false          // ANDROID (funzioni 21/11)
        if (say != null) aedSay(say)
    }

    // ANDROID (funzioni 21/11): tempi del layout attivo (tabella unica SLLayoutTimingFor)
    private fun timing(): SLLayoutTiming = SLLayoutTimingFor(layout)
    private fun realism(): Boolean = cfgBool("device_realism")
    // il defibrillatore «vede» le piastre: applicate sul paziente E cavo collegato
    private fun padsLive(): Boolean = padsOn && padsCable
    // elettrodi ECG che danno segnale (l'allarme tecnico simulato «elettrodi scollegati» li toglie)
    private fun ecgEff(): Boolean = ecgOn && !techAlarms.contains("ecg_leads_off")
    private fun pedEff(): Boolean = padsPed || aedChild
    private fun aedEnergyEff(): Int = if (pedEff()) cfg("aed_ped_energy_j") else cfg("aed_energy_j")
    // messaggio vocale quando mancano le piastre o il cavo
    private fun padsMissingVoice(): String = when {
        !padsOn -> if (pedEff()) "Applicare le piastre pediatriche sul torace nudo del paziente." else "Applicare le piastre sul torace nudo del paziente."
        else -> "Collegare il cavo delle piastre."
    }
    // inizio dell'analisi automatica (piastre applicate, accensione in DAE, fine del ciclo di RCP). Con device_realism
    // ANDROID (guasti 22/11): prefix = frase dei malfunzionamenti detta all'accensione prima della voce normale
    private fun aedStartAnalysis(say: String, prefix: String? = null) {
        fun j(v: String): String = if (prefix != null) "$prefix $v" else v
        if (svcFault()) { aedPhase(SLAedPads, prefix); return }                 // unità guasta: niente analisi
        if (padsDamaged()) { aedPhase(SLAedAnalyzing, j(fVoice("pads_damaged"))); faultSaidAt = simNow(); return }
        if (realism() && timing().realAwaitAnalyze) {
            aedPhase(SLAedAnalyzing, j("Premere ANALIZZA.")); aedAwait = true; aedAwaitSaidAt = simNow()
        } else { aedPhase(SLAedAnalyzing, j(say)); aedGraceUntil = simNow() + kAedStopGraceS }
    }

    // MARK: ANDROID (guasti 22/11) — malfunzionamenti simulati del dispositivo (RICERCA_MALFUNZIONAMENTI_DAE.md, cap. 4).
    // Senza malfunzionamenti tutte queste funzioni rispondono «no» e il comportamento è identico a prima.

    // attivo E sensato nel layout attivo (ICU: solo batteria come allarme, guasto e errore di carica del cassetto)
    private fun fx(kind: String): Boolean = faults.contains(kind) && SLDeviceFaultApplies(layout, kind)
    private fun svcFault(): Boolean = fx("service_required")
    private fun padsDamaged(): Boolean = fx("pads_damaged") && padsLive()
    private fun dedicatedAed(): Boolean = SFLayoutIsAed(layout)
    private fun fVoice(key: String): String = SLDeviceFaultVoice(layout, key)
    private fun fScreen(key: String): String = SLDeviceFaultScreen(layout, key)
    private fun updatePowerBlock() { powerBlocked = fx("battery_dead") }
    private fun deviceOn(): Boolean = devicePowered || dMode != SLDefibOff
    // batteria simulata con le scariche finite: la carica non riesce (l'ICU è in rete: la batteria non limita)
    private fun battNoCharge(): Boolean = layout != "generic_icu" && fx("battery_replace") && battShocks <= 0

    // elenco per lo stato: i tipi propri più cavo staccato e contatto scarso (stati che esistevano già), nell'ordine dell'elenco
    private fun faultList(): List<String> {
        val l = HashSet(faults)
        if (!padsCable) l.add("pads_unplugged")
        if (padsPoor) l.add("pads_poor_contact")
        return SLDeviceFaultKinds.filter { l.contains(it) }
    }

    // spia di prontezza da spento: dark (batteria esaurita: nessuna spia), fault (guasto), attention, ready
    private fun readiness(): String {
        if (fx("battery_dead")) return "dark"
        if (svcFault()) return "fault"
        for (k in listOf("battery_low", "battery_replace", "pads_expired", "pads_damaged", "maintenance", "temperature", "shock_button_stuck")) if (fx(k)) return "attention"
        if (!padsCable) return "attention"          // novità: il cavo staccato conta anche a dispositivo spento
        return "ready"
    }

    // bip da spento ADESSO: (intervallo in s, doppio). ogni 30 s, come molti DAE; guasto grave doppio bip ogni 15 s.
    private fun faultBeepNow(): Pair<Int, Boolean> {
        if (!faultBeepOn || deviceOn() || fx("battery_dead") || clock() < faultSelfTestUntil) return Pair(0, false)
        val svc = svcFault()
        when (layout) {
            "dae", "extrah", in SFLayoutAedIds -> {
                if (svc) return Pair(15, true)
                val beeping = listOf("battery_low", "battery_replace", "pads_expired", "pads_damaged", "maintenance", "shock_button_stuck").any { fx(it) } || !padsCable
                if (beeping) return Pair(30, false)
            }
        }
        return Pair(0, false)
    }

    private fun faultPowerOnVoice(): String? {
        if (!dedicatedAed()) return null
        if (svcFault()) return fVoice("svc")
        val a = ArrayList<String>()
        if (fx("battery_replace")) a.add(fVoice("battery_replace")) else if (fx("battery_low")) a.add(fVoice("battery_low"))
        if (fx("pads_expired")) a.add(fVoice("pads_expired"))
        if (fx("maintenance")) a.add(fVoice("maintenance"))
        if (fx("shock_button_stuck")) a.add(fVoice("shock_stuck"))
        val replaced = battReplacedSay; battReplacedSay = false
        return if (a.isEmpty()) null else a.joinToString(" ")
    }

    // scritta all'accensione dei DAE dedicati (5 s), la più importante
    private fun faultPowerOnNote(): String? {
        if (!dedicatedAed() || svcFault()) return null
        val order = listOf("battery_replace" to "battery_replace", "battery_low" to "battery_low", "pads_expired" to "pads_expired",
                           "maintenance" to "maintenance", "shock_button_stuck" to "shock_stuck", "memory_full" to "memory")
        for ((k, key) in order) if (fx(k)) return fScreen(key)
        return null
    }

    // spegnimento dovuto a un malfunzionamento (batteria esaurita, assistenza eseguita)
    private fun faultShutdown(why: String) {
        defibDisarm(null, false)
        if (pRun) { pRun = false; pPaused = false }
        if (dMode != SLDefibOff) defibSetMode(SLDefibOff)
        devicePowered = false
        defibLog(why, false)
    }

    // set_pads_contact (A8, 21/11): piastre attaccate male (pelle bagnata, peli, piastra sollevata)
    private fun padsContactSet(poor: Boolean) {
        if (poor != padsPoor) {
            padsPoor = poor
            defibLog(if (poor) "Contatto delle piastre scarso" else "Contatto delle piastre ripristinato", true)
            if (dMode == SLDefibAED && padsLive()) {
                if (poor) { padsPoorSaidAt = 0.0; if (aedPhase == SLAedAnalyzing) aedPhaseStart = simNow() }
                else if (aedPhase == SLAedAnalyzing && !aedAwait && !padsDamaged()) { aedPhaseStart = simNow(); aedSay("Analisi del ritmo in corso. Non toccare il paziente.") }
            }
        }
    }

    // set_device_fault {kind, on}
    private fun setDeviceFault(kind: String, on: Boolean) {
        if (kind == "pads_unplugged" || kind == "pads_poor_contact") {      // stati che esistevano già: stessi comandi di prima
            val was = if (kind == "pads_unplugged") !padsCable else padsPoor
            if (was == on) return
            if (kind == "pads_unplugged") defibCommand("set_pads_cable", if (on) "disconnected" else "connected") else padsContactSet(on)
            defibLog("Malfunzionamento simulato: ${SLDeviceFaultName(kind)} ${if (on) "attivato" else "risolto (${SLDeviceFaultFix(kind)})"}", true)
            return
        }
        if (on == faults.contains(kind)) return
        val battery = SLDeviceFaultBattery.contains(kind)
        if (on) {
            if (battery) {
                for (b in SLDeviceFaultBattery) if (b != kind && faults.remove(b)) defibLog("Malfunzionamento simulato: ${SLDeviceFaultName(b)} tolto", true)
                battShocks = when (kind) { "battery_low" -> kBatteryLowShocks; "battery_replace" -> kBatteryReplaceShocks; else -> -1 }
                battOnS = 0.0; battLastAt = 0.0
            }
            faults.add(kind)
            defibLog("Malfunzionamento simulato: ${SLDeviceFaultName(kind)} attivato", true)
            updatePowerBlock()
            // effetto immediato a dispositivo acceso
            if (kind == "battery_dead" && fx(kind) && deviceOn()) faultShutdown("Dispositivo spento: batteria esaurita")
            if (kind == "service_required" && fx(kind)) {
                defibDisarm(null, false)
                if (pRun) { pRun = false; pPaused = false; defibNote(fScreen("svc")) }
                if (dMode == SLDefibAED) { aedPhase(SLAedPads, fVoice("svc")); faultSaidAt = simNow() }
            }
            if (kind == "pads_damaged" && dMode == SLDefibAED && padsLive() && aedPhase == SLAedAnalyzing) { aedPhaseStart = simNow(); faultSaidAt = 0.0 }
        } else {
            faults.remove(kind)
            defibLog("Malfunzionamento simulato: ${SLDeviceFaultName(kind)} risolto (${SLDeviceFaultFix(kind)})", true)
            updatePowerBlock()
            if (battery) {
                battShocks = -1; battOnS = 0.0; battLastAt = 0.0
                if (!deviceOn()) { battReplacedSay = true; faultSelfTestUntil = clock() + kFaultSelfTestS }   // autotest all'inserimento
            }
            // assistenza eseguita a dispositivo acceso: si spegne e va riacceso (l'ICU resta in rete)
            if (kind == "service_required" && layout != "generic_icu" && deviceOn()) faultShutdown("Dispositivo spento dopo l'assistenza: riaccendere")
            if (kind == "pads_damaged" && dMode == SLDefibAED && padsLive() && aedPhase == SLAedAnalyzing && !aedAwait && !padsPoor) {
                aedPhaseStart = simNow(); aedSay("Analisi del ritmo in corso. Non toccare il paziente.")
            }
        }
    }

    private fun faultTickAt(now: Double): Boolean {
        if (layout != "generic_icu" && fx("battery_replace") && deviceOn()) {
            if (battLastAt > 0) { val dt = now - battLastAt; if (dt > 0 && dt < 5) battOnS += dt }
            battLastAt = now
            if (battOnS >= kBatteryReplaceShutdownS) {
                faults.remove("battery_replace"); faults.add("battery_dead"); battShocks = -1; battOnS = 0.0; battLastAt = 0.0
                defibLog("Malfunzionamento simulato: la batteria si è esaurita (Batteria esaurita o assente)", true)
                updatePowerBlock()
                faultShutdown("Dispositivo spento: batteria esaurita")
                return true
            }
        } else battLastAt = 0.0
        return false
    }

    // aedRecover: in modalità DAE, dopo il disarmo il DAE non resta su «Premere SHOCK» (vedi sotto). Lo spengono i
    // chiamanti che subito dopo portano il DAE in un'altra fase (cambio modalità, movimento, piastre staccate, scadenza).
    private fun defibDisarm(why: String?, aedRecover: Boolean = true) {
        if (dCharge == SLChargeNone && !dSyncPending) return
        dCharge = SLChargeNone; dSyncPending = false; dChargedEnergy = 0
        if (why != null) { defibNote(why); defibLog(why, false) }
        // ANDROID (analisi 21/11, 1.3): qualunque disarmo in modalità DAE durante la carica o con lo shock pronto riporta
        // il DAE in uno stato coerente, come i DAE reali: shock non erogato, RCP (poi nuova analisi a fine ciclo)
        if (aedRecover && dMode == SLDefibAED && (aedPhase == SLAedCharging || aedPhase == SLAedReady)) {
            aedPhase(SLAedCPR, "Shock non erogato. Iniziare la RCP.")
        }
    }

    // ANDROID (analisi 21/11, 1.4): ritmo che il DAE considera defibrillabile (FV, TV senza polso, TV rapida >= 150:
    // il DAE non sente il polso)
    // ANDROID (ampiezza FV 22/11): una FV sotto 0,1 mV picco-picco il DAE non la riconosce (come asistolia)
    private fun aedShockable(): Boolean = (rhythm == SLRhythmVentricularFib && vfDetectable()) || rhythm == SLRhythmVentricularTachy ||
        (rhythm == SLRhythmVentricularTachyPulse && hr >= 150)

    // ANDROID (analisi 21/11, 1.4): in DAE, durante la carica o con lo shock pronto, il ritmo non è più defibrillabile
    // Ritorna true se ha annullato.
    private fun aedCancelIfRhythmChanged(): Boolean {
        if (dMode != SLDefibAED || !(aedPhase == SLAedCharging || aedPhase == SLAedReady) || aedShockable()) return false
        defibDisarm(null, false)
        defibNote("RITMO CAMBIATO — SHOCK ANNULLATO")   // a schermo per 5 s, poi «RCP IN CORSO»
        defibLog("DAE: ritmo cambiato, scarica annullata", false)
        aedPhase(SLAedCPR, "Ritmo cambiato. Shock annullato. Iniziare la RCP.")
        return true
    }

    private fun defibStartChargeEnergy(j: Int) {
        dCharge = SLChargeCharging; dChargeStart = simNow(); dChargeDur = SLChargeTime(j); dChargedEnergy = j
        dSyncPending = false; dNote = null
    }

    // Pacing: attivo, cattura elettrica e meccanica (derivati dallo stato corrente)
    private fun altPacingRuleA(): Boolean = false
    private fun paceEffRate(): Int = if (pPaused) Math.max(1, pRate / 4) else pRate   // PAUSE: 25% della frequenza
    // ANDROID (AE-02): stessa regola per extraH e ICU (senza sensing il demand non esiste: si stimola a frequenza fissa con il
    private fun demandFallbackRule(): Boolean = altPacingRuleA() || layout == "extrah" || layout == "generic_icu"
    private fun paceDemandEff(): Boolean = pDemand && !(demandFallbackRule() && !ecgEff())

    private fun pacingActive(): SLPacing {
        val on = dMode == SLDefibPacer && pRun && padsLive()
        val rate = paceEffRate()
        val organized = !(rhythm == SLRhythmVentricularFib || rhythm == SLRhythmAsystole)
        val inhibited = on && paceDemandEff() && organized && hr >= rate   // demand: ritmo proprio sufficiente
        val act = on && !inhibited
        val ventricular = rhythm == SLRhythmVentricularFib || rhythm == SLRhythmVentricularTachy ||
                          rhythm == SLRhythmVentricularTachyPulse
        // ANDROID (difetti clinici 22/11, AE-01): con il contatto delle piastre scarso (impedenza alta) la corrente non arriva al
        // cuore: spike visibili ma nessuna cattura, con il messaggio «controllare le piastre» (defibMessageAt)
        val capRaw = act && !padsPoor && cfgBool("capture_possible") && pOut >= cfg("capture_threshold_ma") && pOut > 0 &&
                     !ventricular && (!organized || rate > hr)
        // ANDROID (funzioni 21/11): false_capture = spike visibili (e artefatto) ma nessuna cattura, nemmeno sopra soglia
        val fake = capRaw && cfgBool("false_capture")
        val cap = capRaw && !fake
        // ANDROID (AE-03): soglia MECCANICA facoltativa (mechanical_threshold_ma, di serie = quella elettrica): tra le due soglie
        // cattura elettrica senza polso
        val mth = if (dCfg.containsKey("mechanical_threshold_ma")) cfg("mechanical_threshold_ma") else 0
        return SLPacing(act, cap, cap && cfgBool("mechanical_capture") && pOut >= mth, fake)   // istruttore: la cattura genera polso o no
    }

    // ANDROID (difetti clinici 22/11, EM-05): ricerca del segnale SpO2 (stato 1 «RICERCA POLSO» -> 2 -> 3 valore)
    private fun spo2Search(sec: Double) {
        if (!spo2On) return
        spo2ConnAt = simNow(); spo2AcqLen = Math.max(spo2Delay.toDouble(), sec); spo2Stage = 1
    }

    // ANDROID (CC-05/CC-06): parametri facoltativi in set_defib_config (oggetto parametro -> valore)
    @Suppress("UNCHECKED_CAST")
    private fun cfgParams(k: String): Map<String, Int> = (dCfg[k] as? Map<String, Number>)?.mapValues { it.value.toInt() } ?: emptyMap()
    private fun roscParamsCfg(): Map<String, Int> = cfgParams("rosc_params")

    // ANDROID (difetti clinici 22/11, EM-07 / CC-05): sorveglia il polso effettivo a ogni tick.
    //  - asistolia/PEA + pacing con cattura MECCANICA = polso: si comporta come un ROSC (PA, SpO2 con ricerca, EtCO2 di ROSC);
    //    la perdita della cattura come un arresto;
    //  - ritmo proprio con polso (bradicardia) + cattura meccanica a frequenza più alta: la PA migliora (capture_params se
    //    impostati, altrimenti un aumento moderato proporzionale alla frequenza solo se la PA era bassa); alla perdita della
    //    cattura torna ai valori di prima se nessuno li ha cambiati nel frattempo.
    private fun hemoCheck() {
        val ep = effectivePulse()
        val was = hemoPulse
        if (was == null) { hemoPulse = ep }
        else if (ep != was) { hemoPulse = ep; hemoTransition(was, ep); revision++ }
        if (!autoPhysio) { capPre = null; capTarget = null; return }
        val p = pacingActive()
        val capBrady = p.capture && p.mechanical && rhythmHasPulse(rhythm)
        if (capBrady && capPre == null) {
            val rate = paceEffRate()
            val cp = cfgParams("capture_params")
            val t = LinkedHashMap<String, Int>()
            if (cp.isNotEmpty()) t.putAll(cp)
            else if (sys < 100 && hr > 0 && rate > hr) {
                val f = Math.sqrt(Math.min(1.6, rate.toDouble() / hr))       // gittata ~ FC (con riempimento che cala): radice
                t["systolic_mmhg"] = Math.min(125, SLLround(sys * f)); t["diastolic_mmhg"] = Math.min(80, SLLround(dia * f))
            }
            capPre = linkedMapOf("systolic_mmhg" to sys, "diastolic_mmhg" to dia, "spo2_percent" to spo2)
            capTarget = t
            for ((k, v) in t) rampParam(k, v, 15.0)
            if (t.isNotEmpty()) revision++
        } else if (!capBrady && capPre != null) {
            val pre = capPre!!; val t = capTarget ?: emptyMap()
            // si torna indietro solo per i parametri che sono ancora quelli portati dalla cattura (l'istruttore non li ha cambiati)
            if (rhythmHasPulse(rhythm)) for ((k, v) in t) {
                val r = ramps[k]
                if ((r == null && param(k) == v) || (r != null && r.to == v)) pre[k]?.let { rampParam(k, it, 10.0) }
            }
            capPre = null; capTarget = null; revision++
        }
    }

    // ANDROID (difetti clinici 22/11, EM-02 / AE-10): apnea con polso (fisiologia automatica, senza RCP). La CO2 non si elimina e
    // si accumula: +10 mmHg nel primo minuto, poi +3 mmHg/min (Eger & Severinghaus 1961), al massimo 110; ogni insufflazione
    // porta al sensore la CO2 alveolare di quel momento (le prime ALTE) e la riporta verso 40 con una costante di ~25 atti
    // (2-3 min a 10-12/min). EtCO2 impostata a 0 dall'istruttore = intubazione esofagea / nessuno scambio: insufflazioni piatte.
    private fun apneaCo2On(): Boolean = autoPhysio && !cpr && rr < 1 && effectivePulse() && etco2 > 0
    private fun apneaCo2Now(now: Double): Double {
        if (apCo2 < 0) { apCo2 = Math.max(etco2.toDouble(), 30.0); apCo2At = now; apVentAt = now }
        val dt = now - apCo2At
        if (dt > 0) {
            val since = now - apVentAt
            // ventilato negli ultimi 10 s: la CO2 prodotta se ne va con le insufflazioni (nessun accumulo)
            apCo2 = Math.min(110.0, apCo2 + dt * (if (since < 10) 0.0 else if (since < 70) 10.0 / 60 else 3.0 / 60))
            apCo2At = now
        }
        return apCo2
    }

    // ANDROID (perfusione 22/11): la perfusione del dito segue la PAM con ~6 s di ritardo (vasocostrizione); sotto 45 mmHg per
    // più di 8 s il saturimetro perde il segnale (fisiologia automatica), lo ritrova sopra 48 mmHg
    private fun mapNow(): Double = (sys + 2.0 * dia) / 3.0
    private fun perfAdvance(now: Double) {
        val m = mapNow()
        if (!effectivePulse()) { mapS = -1.0; mapLowSince = 0.0; if (spo2Weak) { spo2Weak = false; revision++ }; return }   // arresto: al ROSC si riparte dalla PA di allora
        if (mapS < 0 || now < mapSAt || now - mapSAt > 30) { mapS = m; mapSAt = now }
        else { mapS += (m - mapS) * (1.0 - Math.exp(-(now - mapSAt) / 6.0)); mapSAt = now }
        val crit = autoPhysio && effectivePulse() && mapS < 45
        if (crit) { if (mapLowSince <= 0) mapLowSince = now } else if (mapS >= 48 || !effectivePulse() || !autoPhysio) mapLowSince = 0.0
        val weak = mapLowSince > 0 && now - mapLowSince >= 8
        if (weak != spo2Weak) { spo2Weak = weak; revision++ }
        // le viste e il motore delle curve si aggiornano ai cambi di revisione: la perfusione che cambia (a passi del 5%) è un cambio
        val step = Math.round(perfFraction(mapS) * 20).toInt()
        if (step != perfStep) { perfStep = step; revision++ }
    }
    private fun perfFraction(map: Double): Double {
        val pts = doubleArrayOf(40.0, 0.10, 45.0, 0.18, 55.0, 0.35, 65.0, 0.60, 75.0, 1.0)
        if (map <= pts[0]) return pts[1]
        for (i in 0 until 4) { val a = pts[2 * i]; val b = pts[2 * i + 2]; if (map <= b) return pts[2 * i + 1] + (pts[2 * i + 3] - pts[2 * i + 1]) * (map - a) / (b - a) }
        return 1.0
    }

    private fun effectivePulse(): Boolean {
        val p = pacingActive()
        return if (p.capture) p.mechanical else rhythmHasPulse(rhythm)
    }

    private fun defibConvertTo(rhythm: Any?) {
        if (rhythm !is String || !supportedRhythms().contains(rhythm)) return
        defibLog("Ritmo dopo lo shock: ${SLRhythmName(rhythm)}", true)
        cancelOnset()                                              // ANDROID (transizioni ritmo 22/11): la scarica decide
        val from = this.rhythm
        val sysBefore = sys; val diaBefore = dia
        // ANDROID (difetti clinici 22/11, EF-04): scarica efficace da un ritmo senza polso: breve pausa elettrica (stordimento)
        // e primi complessi lenti che accelerano (preludio «rosc» già usato da set_rhythm); dopo la cardioversione di FA/TV la
        // pausa di recupero del seno (preludio «termination»). post_shock_onset "immediate" = come prima (ritmo subito).
        val prelude = dCfg["post_shock_onset"] != "immediate" && rhythm != from && rhythmHasPulse(rhythm) &&
                      (!rhythmHasPulse(from) || from == SLRhythmAtrialFibrillation || from == SLRhythmVentricularTachyPulse)
        if (!(prelude && startOnset(rhythm, null))) applyRhythm(rhythm)
        dEpisode = rhythm; dEff = 0; cvEff = 0
        // ANDROID (difetti clinici 22/11, CC-05): cardioversione efficace di una tachiaritmia con polso: la PA migliora
        // (cv_post_params se impostati; altrimenti, se la PA era bassa, +20/+10 mmHg in 30 s fino a 120/75)
        if ((from == SLRhythmAtrialFibrillation || from == SLRhythmVentricularTachyPulse) && rhythmHasPulse(rhythm) && autoPhysio) {
            val cp = cfgParams("cv_post_params")
            if (cp.isNotEmpty()) for ((k, v) in cp) rampParam(k, v, 30.0)
            else if (sysBefore < 100) {
                rampParam("systolic_mmhg", Math.min(120, sysBefore + 20), 30.0)
                rampParam("diastolic_mmhg", Math.min(75, Math.max(diaBefore + 10, 40)), 30.0)
            }
        }
    }

    // Erogazione dello shock con l'esito configurato dall'istruttore
    private fun defibDeliverSynced(synced: Boolean) {
        val j = dChargedEnergy
        val now = clock()
        dShocks++; dLastShock = now; dLastEnergy = j; dLastSync = synced
        dCharge = SLChargeNone; dSyncPending = false; dChargedEnergy = 0
        // ANDROID (fix analisi 22/11, terzo giro): una domanda sull'esito rimasta aperta per una scarica precedente si chiude con
        // la scarica nuova (prima restava aperta: con l'avanzamento automatico un esito scelto dopo si applicava al ritmo di adesso).
        // Va chiusa prima della copia per «Annulla» dell'avanzamento automatico, così l'annullamento non la riapre.
        if (dDecision) {
            defibLog("Domanda sull'esito della scarica $dDecisionShock chiusa senza risposta: nuova scarica", true)
            dDecision = false; dDecisionFrom = null; dDecisionShock = 0; dDecisionEnergy = 0; dDecisionSync = false
        }
        val what = "Shock $dShocks: $j J${if (synced) " SYNC" else ""}${if (dMode == SLDefibAED) " (DAE)" else ""}"
        // ANDROID (25/11, richiesta dell'utente): quanto si è aspettato a premere SHOCK dopo che il dispositivo l'ha chiesto, e se
        // qualcuno era a contatto col paziente (tocco simulato o compressioni in corso) nel momento della scarica
        val nowS = simNow(); noShockAt = 0.0
        val waited = if (dReadyAt > 0 && nowS >= dReadyAt) SLLround(nowS - dReadyAt).toLong() else -1L
        val touching = touchUntil > clock(); val compressing = cpr
        defibNote("SHOCK EROGATO  $j J${if (synced) " (SYNC)" else ""}")
        defibLog(what + (if (padsMarginal) " — impedenza alta (~${150 + random.nextInt(30)} Ω): energia erogata, controllare le piastre" else ""), false)   // ANDROID (AE-08)
        defibLog("Ritmo al momento dello shock: ${SLRhythmName(rhythm)}", true)
        if (waited >= 0) defibLog(if (dMode == SLDefibAED) "Scarica $dShocks: SHOCK premuto $waited s dopo l'invito del DAE" else "Scarica $dShocks: SHOCK premuto $waited s dopo la carica completata", true)
        if (touching || compressing) defibLog("ATTENZIONE: scarica $dShocks erogata con un operatore a contatto con il paziente" +
            (if (compressing) " (compressioni in corso)" else " (paziente toccato)"), false)
        cprMetricsShock(j, synced)                         // ANDROID (funzioni 21/11): pause attorno alla scarica
        // ANDROID (funzioni 21/11): auto_advance -> si conserva il paziente di prima dell'esito per poterlo annullare
        val autoOn = cfgBool("auto_advance")
        val undo = if (autoOn) exportPatient(clock()) else null
        val rhythmBefore = rhythm
        var autoTxt: String? = null
        var undoDecision: Map<String, Any?>? = null

        // ANDROID (BugFix #18): scarica NON sincronizzata su un ritmo con polso (sinusale, tachi/bradicardia sinusale,
        // fibrillazione atriale, TV con polso) = R su T -> FIBRILLAZIONE VENTRICOLARE, SEMPRE: anche quando l'esito degli
        // shock lo decide l'istruttore (modalità di serie). Si spegne solo con la configurazione unsync_shock_vf = false.
        val perfusing = rhythm == SLRhythmSinus || rhythm == SLRhythmSinusTachycardia || rhythm == SLRhythmSinusBradycardia ||
                        rhythm == SLRhythmAtrialFibrillation || rhythm == SLRhythmVentricularTachyPulse
        // ANDROID (analisi 21/11, 1.5): la regola vale per le scariche MANUALI non sincronizzate. La scarica consigliata ed
        // erogata dal DAE (TV con polso >= 150, che il DAE tratta come defibrillabile) segue l'esito degli altri ritmi
        // defibrillabili: decisione dell'istruttore oppure regole automatiche di FV/TV senza polso.
        val aedShock = dMode == SLDefibAED
        if (!synced && perfusing && cfgBool("unsync_shock_vf") && !aedShock) {
            defibLog("Shock NON sincronizzato su ritmo con polso: R su T", true)
            defibConvertTo(SLRhythmVentricularFib)
        } else if (dCfg["outcome_mode"] == "instructor") {
            val cvR = rhythm == SLRhythmAtrialFibrillation || rhythm == SLRhythmVentricularTachyPulse
            if (autoOn && synced && !aedShock && cvR && j >= cfg("cv_min_energy_j")) {
                // ANDROID (funzioni 21/11): avanzamento automatico: cardioversione sincronizzata efficace su un ritmo con
                // polso -> ritmo di cv_post_rhythm (di serie sinusale) senza aspettare l'istruttore, che può annullare per 5 s
                undoDecision = linkedMapOf("from" to rhythm, "shock" to dShocks.toLong(), "energy_j" to j.toLong(), "sync" to synced)
                defibLog("Avanzamento automatico: cardioversione efficace", true)
                defibConvertTo(dCfg["cv_post_rhythm"])
                autoTxt = "Cardioversione efficace: ${SLRhythmName(rhythm)}"
            } else {
                // il ritmo resta quello pre-shock finché l'istruttore non sceglie l'esito (defib_outcome)
                dDecision = true; dDecisionFrom = rhythm; dDecisionShock = dShocks; dDecisionEnergy = j; dDecisionSync = synced; dDecisionAt = simNow()
                defibLog("In attesa della decisione dell'istruttore", true)
            }
        } else {
            if (dEpisode != rhythm) { dEpisode = rhythm; dEff = 0; cvEff = 0 }
            val r = rhythm
            val defibRhythm = r == SLRhythmVentricularFib || r == SLRhythmVentricularTachy ||
                              (aedShock && r == SLRhythmVentricularTachyPulse)   // ANDROID (1.5): TV con polso scaricata dal DAE
            val cvRhythm = !defibRhythm && (r == SLRhythmAtrialFibrillation || r == SLRhythmVentricularTachyPulse)
            val sinusLike = r == SLRhythmSinus || r == SLRhythmSinusTachycardia ||
                            r == SLRhythmSinusBradycardia
            val rOnT = cfgBool("unsync_shock_vf")
            val seq = dCfg["outcome_sequence"] as? List<*>
            if (defibRhythm && seq != null && seq.isNotEmpty()) {
                // ANDROID (funzioni 21/11): regia DAE: esito di ogni scarica efficace dalla sequenza (oltre la fine vale l'ultima voce)
                if (j >= cfg("min_energy_j")) {
                    val e = seq[Math.min(seqIdx, seq.size - 1)]
                    seqIdx++
                    defibLog("Sequenza degli esiti ${Math.min(seqIdx, seq.size)}/${seq.size}: ${if (e == "stay") "resta" else if (e == "convert") "converte" else SLRhythmName(e.toString())}", true)
                    if (e == "convert") defibConvertTo(dCfg["post_shock_rhythm"])
                    else if (e != "stay") defibConvertTo(e)
                } else defibLog("Nessuna conversione (energia sotto la soglia di ${cfg("min_energy_j")} J)", true)
            } else if (defibRhythm) {
                if (j >= cfg("min_energy_j")) dEff++
                val n = cfg("shocks_to_convert")
                if (n > 0 && dEff >= n) defibConvertTo(dCfg["post_shock_rhythm"])
                else defibLog("Nessuna conversione (shock efficaci $dEff/$n)", true)
            } else if (cvRhythm) {
                if (!synced && rOnT && r == SLRhythmAtrialFibrillation) {
                    defibLog("Shock NON sincronizzato su ritmo con polso: R su T", true)
                    defibConvertTo(SLRhythmVentricularFib)
                } else {
                    if (j >= cfg("cv_min_energy_j")) cvEff++
                    val n = cfg("cv_shocks_to_convert")
                    if (n > 0 && cvEff >= n) defibConvertTo(dCfg["cv_post_rhythm"])
                    else defibLog("Cardioversione senza effetto ($cvEff/$n)", true)
                }
            } else if (sinusLike && !synced && rOnT && !aedShock) {
                defibLog("Shock NON sincronizzato su ritmo con polso: R su T", true)
                defibConvertTo(SLRhythmVentricularFib)
            }
        }
        if (autoOn && autoTxt == null && rhythm != rhythmBefore) autoTxt = "Dopo la scarica: ${SLRhythmName(rhythm)}"
        if (autoTxt != null && undo != null) autoActionSet(autoTxt, undo, undoDecision)
        if (synced && !cfgBool("sync_stays_on")) dSync = false   // dopo lo shock torna asincrono (configurabile)
        // ANDROID (guasti 22/11): batteria simulata: una scarica in meno; finite quelle di battery_low passa a battery_replace
        var battWarn = ""
        if (layout != "generic_icu" && (fx("battery_low") || fx("battery_replace")) && battShocks > 0) {
            battShocks--
            if (fx("battery_low") && battShocks <= 0) {
                faults.remove("battery_low"); faults.add("battery_replace"); battShocks = kBatteryReplaceShocks; battOnS = 0.0; battLastAt = 0.0
                defibLog("Malfunzionamento simulato: la batteria passa a «${SLDeviceFaultName("battery_replace")}»", true)
                battWarn = " " + fVoice("battery_replace")
            }
        }
        if (dMode == SLDefibAED) {
            aedPhase(SLAedCPR, "Shock erogato. Iniziare subito la RCP.")   // ANDROID (audit 25/11): l'avviso batteria si dice dopo
            if (battWarn.isNotEmpty()) { laterSay = battWarn.trim(); laterSayAt = simNow() + 4.0 }
        }
        cprMetricsCheck()
    }

    // MARK: ANDROID (funzioni 21/11) — metriche RCP per il debriefing

    // tempo di scenario adesso (esclude le pause), come snapshot().scenarioTime
    // ANDROID (avvio scenario 22/11): avvio dello scenario (start_scenario). Da PRONTO o da terminato: orologio da 00:00
    // e simulazione in corso (se era in pausa riprende, spostando i tempi clinici); da pausa: riprende e basta; in corso: niente.
    private fun startScenario() {
        if (scenStarted && running && !scenEnded) return
        val fresh = !scenStarted || scenEnded
        if (!running) { if (pausedAt > 0) shiftClinicalTimes(clock() - pausedAt); pausedAt = 0.0; running = true }
        if (fresh) {
            scenAccum = 0.0
            trend.clear(); alarmGate.clear()                              // ANDROID (IN-14): il trend conta dall'avvio
            // le metriche RCP del debriefing contano da qui (le prove fatte in PRONTO non entrano)
            cmArrest = 0.0; cmCpr = 0.0; cmShocks = ArrayList(); cmPending = null; cmLastAt = simNow()
        }
        scenSince = clock()
        scenStarted = true; scenEnded = false
        if (fresh) scenarioStartSeq++
    }

    private fun scenTimeNow(): Double = scenAccum + (if (running && scenSince > 0) Math.max(0.0, clock() - scenSince) else 0.0)

    // accumula il tempo trascorso dall'ultima chiamata: in arresto (nessun polso efficace) e con compressioni in arresto.
    // Va chiamata PRIMA di ogni cambiamento (comandi, tick): l'intervallo appena trascorso ha lo stato di prima.
    private fun cprAccrue() {
        val now = simNow()
        if (cmLastAt > 0) {
            val dt = now - cmLastAt
            if (dt > 0 && dt < 21600 && !effectivePulse()) { cmArrest += dt; if (cpr) cmCpr += dt }
        }
        cmLastAt = now
    }

    private fun cprMetricsShock(j: Int, synced: Boolean) {
        val now = simNow()
        cmPending?.let { cprMetricsFinish(it, null) }      // nuova scarica prima della ripresa: pausa post non misurabile
        val arrest = !effectivePulse()
        val rec = linkedMapOf<String, Any?>("n" to dShocks.toLong(), "t" to Math.floor(scenTimeNow()).toLong(), "energy_j" to j.toLong(),
                                            "pre_s" to null, "post_s" to null, "sync" to synced, "pulse" to !arrest)
        if (arrest) {
            if (cpr) { rec["pre_s"] = 0L; rec["post_s"] = 0L }            // scarica a compressioni in corso
            else if (cprStoppedAt > 0) rec["pre_s"] = SLLround(Math.max(0.0, now - cprStoppedAt)).toLong()
        }
        cmShocks.add(rec)
        while (cmShocks.size > 40) cmShocks.removeAt(0)
        cmShockAt = now
        if (arrest && !cpr) cmPending = rec else cprMetricsFinish(rec, rec["post_s"] as? Long)
    }

    private fun cprMetricsFinish(rec: MutableMap<String, Any?>, post: Long?) {
        rec["post_s"] = post
        if (cmPending === rec) cmPending = null
        fun f(v: Any?): String = if (v is Number) "${v.toLong()} s" else "—"
        val txt = if (rec["pulse"] == true) "Scarica ${rec["n"]}: su ritmo con polso${if (rec["sync"] == true) " (SYNC)" else " (senza SYNC)"}"
                  else "Scarica ${rec["n"]}: pausa pre ${f(rec["pre_s"])}, post ${f(post)}"
        defibLog(txt, true)
    }

    // compressioni riprese dopo una scarica -> pausa post; polso tornato senza ripresa -> pausa post non misurabile
    private fun cprMetricsCheck() {
        val p = cmPending ?: return
        if (cpr) cprMetricsFinish(p, SLLround(Math.max(0.0, simNow() - cmShockAt)).toLong())
        else if (effectivePulse()) cprMetricsFinish(p, null)
    }

    private fun cprMetricsMap(): Map<String, Any?> {
        var arrest = cmArrest; var comp = cmCpr
        val now = simNow()
        if (cmLastAt > 0 && now > cmLastAt && !effectivePulse()) { arrest += now - cmLastAt; if (cpr) comp += now - cmLastAt }
        val last = cmShocks.lastOrNull()
        return linkedMapOf(
            "ccf_percent" to (if (arrest >= 1) SLLround(100.0 * comp / arrest).toLong() else null),
            "arrest_s" to Math.floor(arrest).toLong(), "cpr_s" to Math.floor(comp).toLong(),
            "last_pre_shock_s" to last?.get("pre_s"), "last_post_shock_s" to last?.get("post_s"),
            "shocks" to cmShocks.map { LinkedHashMap(it) })
    }

    // MARK: ANDROID (funzioni 21/11) — avanzamento automatico annullabile

    private fun autoActionSet(text: String, undo: Map<String, Any?>, decision: Map<String, Any?>?) {
        autoSeq++; autoText = text; autoUntil = clock() + kAutoActionWindow; autoUndo = undo; autoUndoDecision = decision
        defibLog("Automatico: $text (annullabile per ${kAutoActionWindow.toInt()} s)", true)
    }

    private fun autoActionActive(): Boolean = autoText != null && clock() < autoUntil


    private fun defibEcgAvailable(): Boolean = padsLive() || (layout != "generic_icu" && ecgEff())
    private fun altPacingRuleB(): Boolean = false

    private fun defibSetMode(m: SLDefibMode) {
        if (m == dMode) return
        defibDisarm(null, false)
        if (dMode == SLDefibPacer && pRun) { pRun = false; defibLog("Pacing interrotto (cambio modalità)", false) }
        val old = dMode
        dMode = m; dNote = null
        if (old == SLDefibOff && m != SLDefibOff && dSession == 0.0) dSession = clock()
        val names = listOf("spento", "DAE", "manuale", "pacer")
        defibLog("Modalità: ${names[m]}", false)
        if (m == SLDefibAED) {
            dSync = false
            // ANDROID (guasti 22/11): all'accensione dei DAE dedicati la voce dice prima i malfunzionamenti
            val note = if (old == SLDefibOff) faultPowerOnNote() else null
            val intro = if (old == SLDefibOff) faultPowerOnVoice() else null
            if (svcFault()) { aedPhase(SLAedPads, intro ?: fVoice("svc")); faultSaidAt = simNow() }
            else if (padsLive()) aedStartAnalysis("Analisi del ritmo in corso. Non toccare il paziente.", intro)
            else aedPhase(SLAedPads, if (intro != null) "$intro ${padsMissingVoice()}" else padsMissingVoice())
            if (note != null) defibNote(note)
        }
        if (m == SLDefibOff) { dSync = false; dVoice = null }
    }

    // ANDROID (funzioni 21/11): chiamata quando cambia padsLive() (piastre sul paziente o, con cable = true, il cavo)
    private fun defibPadsChanged(cable: Boolean = false) {
        if (padsLive()) {
            defibLog(if (cable) "Cavo delle piastre collegato" else "Piastre applicate", false)
            if (dMode == SLDefibAED && aedPhase == SLAedPads) {
                aedStartAnalysis("Analisi del ritmo in corso. Non toccare il paziente.")
            }
        } else {
            defibLog(if (cable) "Cavo delle piastre scollegato" else "Piastre staccate", false)
            if (!cable) padsPoor = false                       // ANDROID (A8): riapplicate, le piastre fanno di nuovo buon contatto
            defibDisarm(null, false)
            if (pRun) { pRun = false; defibNote(if (cable) "CAVO SCOLLEGATO: PACING INTERROTTO" else "PIASTRE STACCATE: PACING INTERROTTO") }
            if (dMode == SLDefibAED) aedPhase(SLAedPads, if (cable) "Collegare il cavo delle piastre." else "Controllare le piastre.")
        }
    }

    /** ANDROID (21/11): consiglio sulla RCP (testo a schermo, voce) o null se le compressioni sono buone; prima la profondità */
    private fun cprFeedback(): Pair<String, String>? = if (pedEff()) null else when {
        dQ() == 1 -> Pair("SPINGERE PIÙ FORTE", "Spingere più forte.")
        dQ() == 2 -> Pair("SPINGERE MENO A FONDO", "Spingere meno a fondo.")
        cprRateQ == 1 -> Pair("SPINGERE PIÙ VELOCEMENTE", "Spingere più velocemente.")
        cprRateQ == 2 -> Pair("SPINGERE PIÙ LENTAMENTE", "Spingere più lentamente.")
        else -> null
    }

    private fun defibTickAt(now: Double) {
        var changed = false
        if (aedCancelIfRhythmChanged()) changed = true   // ANDROID (analisi 21/11, 1.4)
        if (dCharge == SLChargeCharging && now - dChargeStart >= dChargeDur && fx("charge_fault")) {
            // ANDROID (guasti 22/11): errore interno di carica: la carica non va a buon fine, «Scarica non erogata»
            dCharge = SLChargeNone; dChargedEnergy = 0; dSyncPending = false; changed = true
            defibNote(fScreen("no_shock_internal")); defibLog("Scarica non erogata: errore interno (malfunzionamento simulato)", false)
            if (dMode == SLDefibAED) aedPhase(SLAedCPR, fVoice("no_shock_internal"))
        }
        if (dCharge == SLChargeCharging && now - dChargeStart >= dChargeDur) {
            dCharge = SLChargeReady; dReadyAt = now; changed = true
            if (dMode == SLDefibAED) aedPhase(SLAedReady, "Allontanarsi dal paziente. Premere ora il pulsante di scarica.")
        }
        // ANDROID (guasti 22/11): unità guasta: niente terapia (carica tolta, pacing fermo); in DAE resta ferma e lo ripete
        if (svcFault()) {
            if (dCharge != SLChargeNone || dSyncPending) { defibDisarm(fScreen("svc"), false); changed = true }
            if (pRun) { pRun = false; pPaused = false; defibNote(fScreen("svc")); changed = true }
            if (dMode == SLDefibAED) {
                if (aedPhase != SLAedPads) { aedPhase = SLAedPads; aedAwait = false; aedMotion = false; changed = true }
                aedPhaseStart = now
                if (now - faultSaidAt >= kFaultVoiceRepeatS) { aedSay(fVoice("svc")); faultSaidAt = now; changed = true }
            }
        }
        val tm = timing()                                // ANDROID (funzioni 21/11): tempi del layout (tabella unica)
        if (dCharge == SLChargeReady) {
            val limit = if (dMode == SLDefibAED) tm.aedReadyTimeoutS else tm.manualReadyTimeoutS
            if (now - dReadyAt >= limit) {
                defibDisarm("SCARICA INTERNA: shock non erogato", false)
                if (dMode == SLDefibAED) aedPhase(SLAedCPR, "Shock non erogato. Iniziare la RCP.")
                changed = true
            }
        }
        val moving = motion != 0 || touchUntil > now || vehicle   // ANDROID (21/11): anche l'ambulanza in movimento
        val motionStopsCharge = !(realism() && tm.realMotionMaxDelayS > 0)
        if (dMode == SLDefibAED && aedPhase == SLAedCharging && (cpr || (moving && motionStopsCharge))) {
            // ANDROID (21/11, regola dell'utente): in modalità DAE durante l'ANALISI (sotto) e la CARICA, compressioni riprese,
            // paziente toccato o paziente che si muove interrompono: carica annullata, «Movimento rilevato», nuova analisi appena
            // tutto è fermo. Con lo shock già pronto nessuna interruzione (tocca a chi preme SHOCK allontanarsi).
            // In manuale e nel pacer il movimento non fa nulla e non si dice nulla.
            defibDisarm(null, false)
            defibLog(if (cpr) "DAE: carica annullata (movimento: RCP ripresa)" else "DAE: carica annullata (movimento del paziente)", false)
            aedPhase(SLAedAnalyzing, if (cpr) "Movimento rilevato. Sospendere la RCP." else "Movimento rilevato. Fermare il movimento.")
            aedMotion = true; aedMotionSaidAt = now; aedMotionSince = now; changed = true
        }
        if (dMode == SLDefibAED) {
            val el = now - aedPhaseStart
            // ANDROID (25/11): senza placche il DAE ripete l'invito ad applicarle (o a collegare il cavo) ogni 15 s finché non ci sono
            if (aedPhase == SLAedPads && !padsLive() && !svcFault() && now - aedPhaseStart >= kPadsRepeatS) {
                aedSay(padsMissingVoice()); aedPhaseStart = now; changed = true
            }
            if (aedPhase == SLAedAnalyzing && svcFault()) {
                // ANDROID (guasti 22/11): (non succede: con il guasto il DAE resta in SLAedPads, vedi sopra)
            } else if (aedPhase == SLAedAnalyzing) {
                if (padsDamaged()) {                          // ANDROID (guasti 22/11): piastre danneggiate: niente analisi finché non si sostituiscono
                    if (now - faultSaidAt >= tm.padsPoorRepeatS) { aedSay(fVoice("pads_damaged")); faultSaidAt = now; changed = true }
                    aedPhaseStart = now
                } else if (padsLive() && padsPoor) {                 // ANDROID (A8): contatto scarso: niente analisi finché non si sistemano
                    if (now - padsPoorSaidAt >= tm.padsPoorRepeatS) { aedSay("Controllare le piastre."); padsPoorSaidAt = now; changed = true }
                    aedPhaseStart = now
                } else if (aedAwait) {                               // ANDROID (21/11, device_realism): aspetta il tasto ANALIZZA
                    if (tm.realAnalyzeRepeatS > 0 && now - aedAwaitSaidAt >= tm.realAnalyzeRepeatS) {
                        aedSay("Premere ANALIZZA."); aedAwaitSaidAt = now; changed = true
                    }
                    aedPhaseStart = now
                } else if (now < aedGraceUntil && (cpr || moving)) {
                    aedPhaseStart = now                              // ANDROID (25/11): si sta ancora dicendo di sospendere la RCP
                } else if (cpr) {                                    // compressioni durante l'analisi: artefatto da movimento
                    if (!aedMotion) { aedSay("Movimento rilevato. Sospendere la RCP."); aedMotion = true; aedMotionSaidAt = now; aedMotionSince = now; changed = true }
                    aedPhaseStart = now
                } else if (moving && !aedMotionOverride) {          // paziente che si muove / viene toccato / mezzo in moto: analisi interrotta
                    if (!aedMotion) { defibLog("DAE: analisi interrotta (movimento)", false); aedMotionSince = now }
                    if (realism() && tm.realMotionMaxDelayS > 0 && aedMotion && now - aedMotionSince >= tm.realMotionMaxDelayS) {
                        aedMotionOverride = true; aedMotion = false; aedPhaseStart = now
                        defibLog("DAE: analisi eseguita nonostante il movimento (ritardo massimo)", false)
                        aedSay("Analisi del ritmo in corso. Non toccare il paziente."); changed = true
                    } else {
                        if (!aedMotion || now - aedMotionSaidAt >= tm.motionRepeatS) {   // il messaggio si ripete finché il movimento continua
                            aedSay("Movimento rilevato. Fermare il movimento."); aedMotionSaidAt = now; changed = true
                        }
                        aedMotion = true; aedPhaseStart = now   // l'analisi riparte da capo quando il paziente è fermo
                    }
                } else if (aedMotion) {
                    aedMotion = false; aedPhaseStart = now
                    aedSay("Analisi del ritmo in corso. Non toccare il paziente."); changed = true
                } else if (el >= tm.analysisS) {
                    val shockable = aedShockable()   // il DAE non sente il polso (TV >= 150 consigliata)
                    aedAdvised = shockable
                    defibLog(if (shockable) "DAE: shock consigliato" else "DAE: shock non consigliato", false)
                    if (shockable && battNoCharge()) {
                        // ANDROID (guasti 22/11): batteria simulata finita: la carica non riesce, si continua la RCP
                        defibNote(fScreen("no_shock_battery")); defibLog("Scarica non erogata: batteria (malfunzionamento simulato)", false)
                        aedPhase(SLAedCPR, fVoice("no_shock_battery"))
                    } else if (shockable) {
                        aedPhase(SLAedCharging, "Shock consigliato. Carica in corso. Allontanarsi dal paziente.")
                        defibStartChargeEnergy(aedEnergyEff())      // ANDROID (21/11): pediatrica con placche ped./bambino
                    } else {
                        aedPhase(SLAedCPR, "Shock non consigliato. Iniziare subito la RCP.")
                        noShockAt = if (cpr) 0.0 else now
                    }
                    changed = true
                }
            } else if (aedPhase == SLAedCPR && cpr && el < cfg("aed_cpr_s")) {
                // «Compressioni efficaci» una volta quando tornano buone
                val fb = cprFeedback()
                if (fb != null && now - cprFbAt >= tm.cprFeedbackRepeatS) { aedSay(fb.second); cprFbAt = now; cprFbBad = true; changed = true }
                else if (fb == null && cprFbBad) { aedSay("Compressioni efficaci."); cprFbBad = false; cprFbAt = now; changed = true }
            } else if (aedPhase == SLAedCPR && el >= cfg("aed_cpr_s")) {
                aedStartAnalysis("Sospendere la RCP. Analisi del ritmo in corso. Non toccare il paziente.")
                changed = true
            }
        }
        if (dNote != null && now - dNoteAt > 5) { dNote = null; changed = true }
        laterSay?.let { if (now >= laterSayAt) { if (dMode == SLDefibAED) aedSay(it); laterSay = null; changed = true } }
        if (dDecision && dDecisionAt > 0 && now - dDecisionAt >= kDecisionTimeoutS) {   // ANDROID (25/11): nessuna scelta in 10 s
            defibLog("Esito: nessuna scelta in 10 s, resta in ${SLRhythmName(rhythm)}", true)
            dDecision = false; dDecisionFrom = null; changed = true
        }
        if (d3LeadsOffCheck()) changed = true
        if (autoText != null && clock() >= autoUntil) { autoText = null; autoUndo = null; autoUndoDecision = null; changed = true }
        if (changed) revision++
    }

    // (ANDROID 21/11: in una funzione a parte perché vale anche in pausa)
    private fun d3LeadsOffCheck(): Boolean {
        if (altPacingRuleB() && dMode == SLDefibPacer && pRun && pDemand && !ecgEff()) {
            pRun = false; pPaused = false
            defibNote("PACING ARRESTATO: DERIVAZIONI ECG SCOLLEGATE")
            defibLog("Pacing arrestato: derivazioni ECG scollegate", false)
            return true
        }
        return false
    }

    // Defibrillatore: shock sincronizzato in attesa -> la UI segnala ogni onda R rilevata dall'ECG
    fun defibRWaveAt(t: Double) {
        synchronized(this) {
            if (dSyncPending && dCharge == SLChargeReady && t >= dSyncSince) { cprAccrue(); defibDeliverSynced(true); revision++ }
        }
    }

    // Testo del display per la modalità corrente
    private fun defibMessageAt(now: Double): String {
        dNote?.let { return it }
        if (dMode != SLDefibOff && svcFault()) return fScreen("svc")    // ANDROID (guasti 22/11): unità guasta
        when (dMode) {
            SLDefibOff -> return "Defibrillatore spento: scegliere DAE, MANUALE o PACER"
            SLDefibAED -> {
                when (aedPhase) {
                    SLAedPads -> return if (padsOn && !padsCable) "COLLEGARE IL CAVO DELLE PIASTRE" else if (pedEff()) "APPLICARE LE PIASTRE PEDIATRICHE" else "APPLICARE LE PIASTRE"
                    SLAedAnalyzing -> return if (padsDamaged()) fScreen("pads_damaged") else if (padsLive() && padsPoor) "CONTROLLARE LE PIASTRE" else if (aedAwait) "PREMERE ANALIZZA" else if (aedMotion) (if (cpr) "MOVIMENTO RILEVATO — SOSPENDERE LA RCP" else "MOVIMENTO RILEVATO — FERMARE IL MOVIMENTO") else "ANALISI IN CORSO — NON TOCCARE IL PAZIENTE"
                    SLAedCharging -> return "SHOCK CONSIGLIATO — CARICA IN CORSO"
                    SLAedReady -> return "ALLONTANARSI — PREMERE SHOCK"
                    // ANDROID (BugFix 23/11, RCP solo se avviata): nel periodo RCP senza compressioni (cpr_active falso) il DAE invita a
                    // iniziarla; «RCP IN CORSO» (e i consigli) solo con le compressioni avviate dall'istruttore
                    SLAedCPR -> return if (cpr) (cprFeedback()?.first ?: "RCP IN CORSO") else "INIZIARE LA RCP"
                }
                return ""
            }
            SLDefibManual -> {
                if (!padsOn) return "COLLEGARE LE PIASTRE"
                if (!padsCable) return "COLLEGARE IL CAVO DELLE PIASTRE"                         // ANDROID (21/11)
                // ANDROID (A8; 21/11: da carico il messaggio resta visibile, in alternanza ogni 2 s con quello della carica)
                if (padsPoor && (dCharge == SLChargeNone || Math.floor(now / 2.0).toLong() % 2L == 0L)) return "CONTATTO PIASTRE SCARSO — CONTROLLARE LE PIASTRE"
                if (dSyncPending) return "SYNC: SHOCK IN ATTESA DELL'ONDA R…"
                if (dCharge == SLChargeCharging) return "CARICA IN CORSO  $dChargedEnergy J"
                if (dCharge == SLChargeReady) return "$dChargedEnergy J PRONTI — PREMERE SHOCK"
                return "MANUALE${if (dSync) " SYNC" else ""} — ENERGIA $dEnergy J"
            }
            SLDefibPacer -> {
                if (!padsOn) return "COLLEGARE LE PIASTRE"
                if (!padsCable) return "COLLEGARE IL CAVO DELLE PIASTRE"                         // ANDROID (21/11)
                if (!pRun) return "PACER PRONTO — premere AVVIA PACING"
                val act = pacingActive().active
                // ANDROID (AE-05): in PAUSA il messaggio della pausa (prima «DEMAND INIBITO: FC propria 34 ≥ 70 ppm»)
                if (pPaused) return "PAUSA: STIMOLAZIONE AL 25% (${paceEffRate()} ppm)"
                if (padsPoor) return "CONTATTO PIASTRE SCARSO — CONTROLLARE LE PIASTRE"     // ANDROID (AE-01): nessuna cattura
                if (!act) return "DEMAND INIBITO: FC propria $hr ≥ $pRate ppm — alzare la frequenza o passare a FISSO"
                if (pDemand && !paceDemandEff()) return "ELETTRODI ECG SCOLLEGATI — STIMOLAZIONE NON-DEMAND"
                return "PACING ${if (pDemand) "DEMAND" else "FISSO"} IN CORSO  $pRate ppm · $pOut mA${if (pOut == 0) " — AUMENTARE LA CORRENTE" else ""}"
            }
        }
        return ""
    }

    private fun defibInfoAt(now: Double): SLDefibInfo {
        val d = SLDefibInfo()
        d.mode = dMode; d.padsConnected = padsLive()   // ANDROID (21/11): piastre applicate E cavo collegato (di serie il cavo è collegato)
        d.padsCable = padsCable; d.padsApplied = padsOn; d.padsPediatric = padsPed; d.aedChildMode = aedChild
        d.aedEffectiveEnergy = aedEnergyEff(); d.aedAwaitingAnalyze = dMode == SLDefibAED && aedPhase == SLAedAnalyzing && aedAwait
        d.syncMarkers = dMode == SLDefibManual && dSync && defibEcgAvailable() && rhythm != SLRhythmVentricularFib && rhythm != SLRhythmAsystole
        d.energy = if (dMode == SLDefibAED) cfg("aed_energy_j") else dEnergy
        d.charge = dCharge; d.chargedEnergy = dChargedEnergy
        d.chargeProgress = if (dCharge == SLChargeCharging) Math.min(1.0, (now - dChargeStart) / Math.max(0.1, dChargeDur)) else (if (dCharge == SLChargeReady) 1.0 else 0.0)
        d.sync = dSync; d.syncPending = dSyncPending; d.syncPendingSince = dSyncSince + pauseLag()   // ANDROID (1.1)
        d.aedPhase = aedPhase; d.aedShockAdvised = aedAdvised
        val len = if (aedPhase == SLAedCPR) cfg("aed_cpr_s").toDouble() else (if (aedPhase == SLAedAnalyzing && !aedAwait) timing().analysisS else 0.0)
        d.aedRemaining = if (len > 0) Math.ceil(Math.max(0.0, aedPhaseStart + len - now)).toInt() else 0
        d.message = defibMessageAt(now)
        d.voicePrompt = dVoice; d.promptSeq = dPromptSeq
        d.chargeRefusedSeq = dChargeRefused
        d.shockCount = dShocks; d.lastShockAt = dLastShock; d.lastShockEnergy = dLastEnergy; d.lastShockSynced = dLastSync
        d.sessionStart = dSession
        d.paceRate = paceEffRate(); d.paceSetRate = pRate; d.pacePaused = pPaused
        d.paceOutput = pOut; d.paceDemand = pDemand; d.paceRunning = pRun
        d.paceLeadsOff = dMode == SLDefibPacer && pRun && pDemand && demandFallbackRule() && !ecgEff()
        val p = pacingActive()
        d.paceActive = p.active; d.paceCapture = p.capture; d.paceMechanical = p.mechanical; d.paceFalseCapture = p.falseCapture
        d.voiceEnabled = cfgBool("voice_prompts")
        d.events = ArrayList(dEvents)
        return d
    }

    // Comandi del defibrillatore. Ritorna true se il comando è suo (handled); lancia SLCmdError se invalido.
    private fun defibCommand(name: String, value: Any?): Boolean {
        if (name == "defib_mode") {
            val modes = listOf("off", "aed", "manual", "pacer")
            if (value !is String || !modes.contains(value)) {
                SLFail("defib_mode: allowed off, aed, manual, pacer")
            }
            // ANDROID (guasti 22/11): batteria simulata esaurita: il dispositivo non si accende
            if (value != "off" && powerBlocked && dMode == SLDefibOff) SLFail("defib_mode: battery depleted (simulated fault)")
            defibSetMode(modes.indexOf(value))
        } else if (name == "defib_energy") {
            val v = SLIntValue(value, 1, 360, "energy_j")
            if (!defibEnergies().contains(v)) SLFail("energy_j: not a selectable energy")
            if (v > SLMaxEnergy(layout)) SLFail("energy_j: above the maximum of this device (${SLMaxEnergy(layout)} J)")   // ANDROID (AE-06)
            if (dMode == SLDefibAED) SLFail("defib_energy: not available in AED mode")
            if (v != dEnergy) {
                dEnergy = v
                if (dCharge != SLChargeNone) defibDisarm("ENERGIA CAMBIATA: CARICA ANNULLATA")
            }
        } else if (name == "defib_charge") {
            if (dMode != SLDefibManual) SLFail("defib_charge: manual mode only")
            if (!padsLive()) { dChargeRefused++; defibNote(if (padsOn) "COLLEGARE IL CAVO DELLE PIASTRE" else "COLLEGARE LE PIASTRE"); SLFail("defib_charge: pads not connected") }
            if (dCharge != SLChargeNone) return true          // già in carica o carico: nessuna nuova carica
            // ANDROID (guasti 22/11): unità guasta / batteria simulata finita: la carica non parte
            if (svcFault()) { defibNote(fScreen("svc")); defibLog("Carica non eseguita: dispositivo guasto (malfunzionamento simulato)", false); SLFail("defib_charge: device fault (simulated)") }
            if (battNoCharge()) { defibNote(fScreen("no_shock_battery")); defibLog("Scarica non erogata: batteria (malfunzionamento simulato)", false); SLFail("defib_charge: battery too low (simulated fault)") }
            defibStartChargeEnergy(dEnergy)
            defibLog("Carica $dEnergy J${if (dSync) " (SYNC)" else ""}", false)
        } else if (name == "defib_disarm") {
            defibDisarm("CARICA ANNULLATA")
        } else if (name == "defib_shock_release") {
            // ANDROID (difetti clinici 22/11, AE-09): in SYNC lo SHOCK va TENUTO premuto fino all'onda R: rilasciato prima, la
            // scarica non parte (la carica resta), come nei dispositivi veri. Senza scarica in attesa non fa nulla.
            if (dSyncPending) {
                dSyncPending = false
                defibNote("SYNC: TASTO RILASCIATO PRIMA DELL'ONDA R — SHOCK NON EROGATO")
                defibLog("SYNC: SHOCK rilasciato prima dell'onda R, scarica non erogata", false)
            }
        } else if (name == "defib_shock") {
            if (dCharge != SLChargeReady) SLFail("defib_shock: not charged")
            if (!padsLive()) { defibDisarm(if (padsOn) "COLLEGARE IL CAVO DELLE PIASTRE" else "COLLEGARE LE PIASTRE"); SLFail("defib_shock: pads not connected") }
            // ANDROID (analisi 21/11, 1.4): il DAE ricontrolla il ritmo prima di erogare
            if (aedCancelIfRhythmChanged()) { revision++; SLFail("defib_shock: rhythm changed, shock cancelled") }
            // ANDROID (analisi 21/11, 1.11): con il contatto scarso (impedenza alta) i dispositivi reali NON erogano
            // ANDROID (guasti 22/11): tasto di scarica bloccato / piastre danneggiate: la scarica non parte (la carica resta)
            if (fx("shock_button_stuck")) {
                defibNote(fScreen("shock_stuck")); defibLog("Tasto di scarica bloccato: scarica non erogata (malfunzionamento simulato)", false)
                if (dMode == SLDefibAED) aedSay(fVoice("shock_stuck"))
                revision++; SLFail("defib_shock: shock button stuck (simulated fault)")
            }
            if (padsDamaged()) {
                defibNote("PIASTRE DANNEGGIATE — SHOCK NON EROGATO"); defibLog("Piastre danneggiate: shock non erogato (malfunzionamento simulato)", false)
                if (dMode == SLDefibAED) aedSay(fVoice("pads_damaged"))
                revision++; SLFail("defib_shock: pads damaged (simulated fault), shock not delivered")
            }
            if (padsPoor) {
                defibNote("IMPEDENZA ALTA — SHOCK NON EROGATO. CONTROLLARE LE PIASTRE")
                defibLog("Impedenza alta: shock non erogato", false)
                if (dMode == SLDefibAED) aedSay("Impedenza alta. Scarica non erogata. Controllare le piastre.")
                revision++; SLFail("defib_shock: high impedance (poor pads contact), shock not delivered")
            }
            if (dMode == SLDefibManual && dSync) {
                if (!defibEcgAvailable()) { defibNote("SYNC: NESSUN ECG DALLE PIASTRE"); SLFail("defib_shock: sync needs pads ECG") }
                dSyncPending = true; dSyncSince = simNow()     // si eroga alla prossima onda R (defibRWaveAt)
            } else {
                defibDeliverSynced(false)
            }
        } else if (name == "defib_sync") {
            val b = SLBoolValue(value, "sync")
            if (dMode != SLDefibManual) SLFail("defib_sync: manual mode only")
            if (b != dSync) { dSync = b; dSyncPending = false; defibLog(if (b) "SYNC attivato" else "SYNC disattivato", false) }
        } else if (name == "defib_analyze") {
            if (dMode != SLDefibAED) SLFail("defib_analyze: AED mode only")
            if (aedPhase == SLAedCharging || aedPhase == SLAedReady) return true
            if (svcFault()) { aedSay(fVoice("svc")); SLFail("defib_analyze: device fault (simulated)") }   // ANDROID (guasti 22/11)
            if (!padsLive()) aedPhase(SLAedPads, padsMissingVoice())
            else if (padsDamaged()) { aedPhase(SLAedAnalyzing, fVoice("pads_damaged")); faultSaidAt = simNow() }   // ANDROID (guasti 22/11)
            else {   // ANDROID (audit 25/11): anche da ANALIZZA prima l'invito, poi il controllo del movimento
                aedPhase(SLAedAnalyzing, if (cpr) "Sospendere la RCP. Analisi del ritmo in corso. Non toccare il paziente." else "Analisi del ritmo in corso. Non toccare il paziente.")
                aedGraceUntil = simNow() + kAedStopGraceS
            }
        } else if (name == "defib_aed_cpr") {
            if (dMode != SLDefibAED) SLFail("defib_aed_cpr: AED mode only")
            defibDisarm(null, false)
            aedPhase(SLAedCPR, "Pausa. Se necessario, iniziare la RCP.")
        } else if (name == "defib_pacer") {
            // {"rate":70,"output_ma":60,"demand":true,"running":true} (anche parziale)
            if (value !is Map<*, *> || value.isEmpty()) SLFail("defib_pacer: value must be an object")
            var rate = pRate; var out = pOut
            var demand = pDemand; var run = pRun; var paused = pPaused
            if (value.containsKey("rate")) rate = SLIntValue(value["rate"], 30, 210, "rate")
            if (value.containsKey("output_ma")) out = SLIntValue(value["output_ma"], 0, 200, "output_ma")
            rate = Math.max(SLPacerRateRange(layout).first, Math.min(SLPacerRateRange(layout).last, rate))   // ANDROID (AE-06)
            out = Math.min(SLPacerMaxMa(layout), out)
            if (value.containsKey("demand")) demand = SLBoolValue(value["demand"], "demand")
            if (value.containsKey("running")) run = SLBoolValue(value["running"], "running")
            if (value.containsKey("paused")) paused = SLBoolValue(value["paused"], "paused")
            if (run && dMode != SLDefibPacer) SLFail("defib_pacer: pacer mode only")
            if (run && !padsLive()) { defibNote(if (padsOn) "COLLEGARE IL CAVO DELLE PIASTRE" else "COLLEGARE LE PIASTRE"); SLFail("defib_pacer: pads not connected") }
            if (run && svcFault()) { defibNote(fScreen("svc")); SLFail("defib_pacer: device fault (simulated)") }   // ANDROID (guasti 22/11)
            if (run && !pRun && altPacingRuleB() && demand && !ecgEff()) {
                defibNote("DERIVAZIONI ECG SCOLLEGATE: PACING NON AVVIATO"); SLFail("defib_pacer: demand pacing needs ECG leads")
            }
            if (run != pRun) defibLog(if (run) "Pacing avviato $rate ppm $out mA ${if (demand) "demand" else "fisso"}"
                                      else "Pacing fermato", false)
            else if (run && (rate != pRate || out != pOut)) defibLog("Pacing $rate ppm $out mA", false)
            pRate = rate; pOut = out; pDemand = demand; pRun = run; pPaused = run && paused
        } else if (name == "set_pads_type") {
            // ANDROID (funzioni 21/11): "adult" (di serie) | "pediatric": placche pediatriche -> il DAE usa aed_ped_energy_j
            if (value != "adult" && value != "pediatric") SLFail("set_pads_type: adult or pediatric")
            val ped = value == "pediatric"
            if (ped != padsPed) {
                padsPed = ped
                if (dCharge != SLChargeNone && dMode == SLDefibAED) defibDisarm("PIASTRE CAMBIATE: CARICA ANNULLATA")
                defibLog(if (ped) "Placche pediatriche (DAE ${cfg("aed_ped_energy_j")} J)" else "Placche per adulti", false)
            }
        } else if (name == "set_aed_child_mode") {
            // ANDROID (funzioni 21/11): modalità bambino del DAE (tasto pediatrico): energia pediatrica, niente consigli RCP
            val b = SLBoolValue(value, "aed_child_mode")
            if (b != aedChild) {
                aedChild = b
                if (dCharge != SLChargeNone && dMode == SLDefibAED) defibDisarm("MODALITÀ CAMBIATA: CARICA ANNULLATA")
                defibLog(if (b) "DAE: modalità bambino" else "DAE: modalità adulto", false)
            }
        } else if (name == "set_pads_cable") {
            // ANDROID (funzioni 21/11): "connected" (di serie) | "disconnected": cavo delle piastre staccato dal dispositivo,
            // diverso dalle piastre sul paziente (che restano dove sono)
            if (value != "connected" && value != "disconnected") SLFail("set_pads_cable: connected or disconnected")
            val on = value == "connected"
            if (on != padsCable) {
                val was = padsLive()
                padsCable = on
                if (was != padsLive()) defibPadsChanged(true)
                else defibLog(if (on) "Cavo delle piastre collegato" else "Cavo delle piastre scollegato", false)
            }
        } else if (name == "defib_event") {
            // tasto EVENT: segna un evento nel registro (testo opzionale)
            val t = if (value is String && value.isNotEmpty()) value.substring(0, Math.min(value.length, 60)) else "Evento marcato"
            if (dSession == 0.0) dSession = clock()
            defibLog(t, false)
        } else if (name == "defib_outcome") {
            // esito dell'ultimo shock: "stay" (resta nel ritmo pre-shock) oppure un ritmo
            if (value !is String || !(value == "stay" || supportedRhythms().contains(value))) {
                SLFail("defib_outcome: value must be \"stay\" or a rhythm")
            }
            // ANDROID (fix analisi 22/11, BUG-09): un esito vale solo per una scarica in attesa di decisione. Prima il monitor lo
            // accettava sempre: due esiti partiti insieme (tasto e «Altro ritmo…», o da due regie) venivano eseguiti entrambi e il
            // ritmo finale era il secondo. Il primo chiude la domanda, il secondo ora è rifiutato (400) e la regia lo dice.
            if (!dDecision) SLFail("defib_outcome: no shock awaiting a decision")
            // ANDROID (fix analisi 22/11, terzo giro): con "shock" (numero della scarica a cui la regia risponde) l'esito vale solo
            // per quella scarica: una risposta a una domanda vecchia (o rimasta in coda) non tocca la scarica nuova
            val sh = cmdOutcomeShock
            if (sh != null) {
                val n = (sh as? Number)?.toDouble() ?: SLFail("defib_outcome: shock must be a number")
                if (n != dDecisionShock.toDouble()) SLFail("defib_outcome: shock ${if (n == Math.floor(n)) n.toLong().toString() else n.toString()} is not the one awaiting a decision ($dDecisionShock)")
            }
            if (value == "stay") defibLog("Esito: resta in ${SLRhythmName(rhythm)}", true)
            else defibConvertTo(value)
            dDecision = false; dDecisionFrom = null
        } else if (name == "set_defib_config") {
            if (value !is Map<*, *> || value.isEmpty()) SLFail("set_defib_config: value must be an object")
            val ints = mapOf("shocks_to_convert" to listOf(0, 10), "min_energy_j" to listOf(0, 360), "cv_shocks_to_convert" to listOf(0, 10),
                             "cv_min_energy_j" to listOf(0, 360), "capture_threshold_ma" to listOf(0, 200), "aed_energy_j" to listOf(50, 360),
                             "aed_cpr_s" to listOf(30, 300), "aed_ped_energy_j" to listOf(1, 200),
                             "mechanical_threshold_ma" to listOf(0, 200))   // ANDROID (AE-03)
            val bools = listOf("unsync_shock_vf", "sync_stays_on", "capture_possible", "mechanical_capture", "voice_prompts",
                               "device_realism", "auto_advance", "false_capture")   // ANDROID (funzioni 21/11)
            val rhythms = listOf("post_shock_rhythm", "cv_post_rhythm")
            if (value.containsKey("outcome_mode")) {
                val om = value["outcome_mode"]
                if (!(om == "instructor" || om == "auto")) SLFail("outcome_mode: allowed instructor, auto")
            }
            val next = LinkedHashMap(dCfg)
            var seqReset = false
            for ((kk, x) in value) {
                val k = kk.toString()
                val ir = ints[k]
                if (ir != null) { val v = SLIntValue(x, ir[0], ir[1], k); next[k] = v.toLong() }
                else if (bools.contains(k)) { val b = SLBoolValue(x, k); next[k] = b }
                else if (rhythms.contains(k)) {
                    if (x !is String || !supportedRhythms().contains(x)) {
                        SLFail("$k: unsupported rhythm")
                    }
                    next[k] = x
                } else if (k == "outcome_mode") { next[k] = x }
                else if (k == "post_shock_onset") {
                    // ANDROID (EF-04): "auto" (di serie) = pausa e complessi lenti dopo la scarica efficace; "immediate" = come prima
                    if (!(x == "auto" || x == "immediate")) SLFail("post_shock_onset: allowed auto, immediate")
                    next[k] = x
                } else if (k == "rosc_params" || k == "capture_params" || k == "cv_post_params") {
                    // ANDROID (CC-05/CC-06): {parametro: valore} (systolic_mmhg, diastolic_mmhg, spo2_percent, etco2_mmhg,
                    // respiratory_rate_rpm); null o {} = di serie
                    if (x == null || (x is Map<*, *> && x.isEmpty())) next.remove(k)
                    else {
                        if (x !is Map<*, *>) SLFail("$k: object expected")
                        val ok = listOf("systolic_mmhg", "diastolic_mmhg", "spo2_percent", "etco2_mmhg", "respiratory_rate_rpm")
                        val ranges = SLParamRanges(); val m = LinkedHashMap<String, Long>()
                        for ((pk, pv) in x) {
                            val ks = pk.toString()
                            if (!ok.contains(ks)) SLFail("$k: unsupported parameter '$ks'")
                            val r = ranges[ks]!!
                            m[ks] = SLIntValue(pv, r[0], r[1], ks).toLong()
                        }
                        if ((m["diastolic_mmhg"] ?: 0) >= (m["systolic_mmhg"] ?: Long.MAX_VALUE)) SLFail("$k: diastolic must be lower than systolic")
                        next[k] = m
                    }
                }
                else if (k == "sync_off_after_shock") {
                    // ANDROID (funzioni 21/11): nome «positivo» di sync_stays_on (stesso interruttore, valore opposto)
                    next["sync_stays_on"] = !SLBoolValue(x, k)
                } else if (k == "outcome_sequence") {
                    // ANDROID (funzioni 21/11): lista per scarica "convert" | "stay" | ritmo; null o [] = si torna a shocks_to_convert
                    if (x == null || (x is List<*> && x.isEmpty())) next.remove(k)
                    else {
                        if (x !is List<*> || x.size > 20) SLFail("outcome_sequence: array of 1..20 items")
                        for (e in x) if (!(e == "convert" || e == "stay" || (e is String && supportedRhythms().contains(e)))) {
                            SLFail("outcome_sequence: items must be \"convert\", \"stay\" or a rhythm")
                        }
                        next[k] = ArrayList(x)
                    }
                    seqReset = true
                }
                else { SLFail("set_defib_config: unknown key '$k'") }
            }
            dCfg = next
            if (seqReset) seqIdx = 0
        } else {
            return false
        }
        return true
    }

    private fun defibDictionary(d: SLDefibInfo): Map<String, Any?> {
        val modes = listOf("off", "aed", "manual", "pacer")
        val phases = listOf("pads", "analyzing", "charging", "ready", "cpr")
        val charges = listOf("none", "charging", "ready")
        return linkedMapOf(
            "mode" to modes[d.mode], "pads_connected" to d.padsConnected, "pads_poor_contact" to padsPoor, "energy_j" to d.energy.toLong(),
            "charge" to charges[d.charge], "charge_progress" to Math.round(d.chargeProgress * 100) / 100.0,
            "charged_energy_j" to d.chargedEnergy.toLong(), "sync" to d.sync, "sync_pending" to d.syncPending,
            // ANDROID (funzioni 21/11)
            "pads_cable" to (if (d.padsCable) "connected" else "disconnected"), "pads_applied" to d.padsApplied,
            "pads_type" to (if (d.padsPediatric) "pediatric" else "adult"), "aed_child_mode" to d.aedChildMode,
            "aed_effective_energy_j" to d.aedEffectiveEnergy.toLong(), "aed_awaiting_analyze" to d.aedAwaitingAnalyze,
            "sync_markers" to d.syncMarkers, "outcome_sequence_index" to seqIdx.toLong(),
            "aed_phase" to phases[d.aedPhase], "aed_remaining_s" to d.aedRemaining.toLong(), "aed_shock_advised" to d.aedShockAdvised,
            "message" to d.message, "shock_count" to d.shockCount.toLong(), "last_shock_energy_j" to d.lastShockEnergy.toLong(),
            "pacer" to linkedMapOf<String, Any?>(
                "rate_ppm" to d.paceRate.toLong(), "output_ma" to d.paceOutput.toLong(), "demand" to d.paceDemand,
                "running" to d.paceRunning, "paused" to d.pacePaused, "active" to d.paceActive, "capture" to d.paceCapture,
                "mechanical_capture" to d.paceMechanical, "false_capture" to d.paceFalseCapture),
            "outcome_pending" to dDecision, "outcome_shock" to dDecisionShock.toLong(),
            "outcome_remaining_s" to (if (dDecision && dDecisionAt > 0) Math.max(0L, Math.ceil(kDecisionTimeoutS - (simNow() - dDecisionAt)).toLong()) else 0L),
            "outcome_from" to dDecisionFrom, "outcome_sync" to dDecisionSync, "outcome_energy_j" to dDecisionEnergy.toLong(),
            "config" to LinkedHashMap(dCfg).also { it["sync_off_after_shock"] = !cfgBool("sync_stays_on") }, "energies" to defibEnergies().map { it.toLong() }, "events" to d.events)
    }

    // MARK: ANDROID (funzioni 21/11) — salvataggio e ripristino dello stato (ripartenza dopo un crash o un riavvio)
    //
    // exportState() ritorna una mappa JSON-serializzabile (Json.stringify) con TUTTO lo stato clinico: paziente, sensori,
    // NIBP, configurazione del monitor, defibrillatore (con registro e configurazione), catena in corso, metriche RCP.
    // Gli istanti si salvano RELATIVI al momento del salvataggio, quindi il ripristino funziona anche con un orologio
    // diverso (riavvio del tablet): il paziente riparte da dove era al salvataggio (il tempo fermo non conta).
    // restoreState(m) ritorna null se riuscito, altrimenti il messaggio d'errore; ogni valore è controllato e riportato
    // nei limiti (valori di ripiego se manca o non è valido): un file rovinato non può portare un parametro a NaN o fuori range.
    // Lo scenario a fasi (SFScenarioStore, modulo core-engine) si salva a parte con exportRun()/restoreRun().

    private fun exportPatient(now: Double): Map<String, Any?> {
        fun rel(t: Double): Any? = if (t > 0) t - now else null
        return linkedMapOf(
            "rhythm" to rhythm, "hr" to hr.toLong(), "spo2" to spo2.toLong(), "rr" to rr.toLong(), "etco2" to etco2.toLong(),
            "sys" to sys.toLong(), "dia" to dia.toLong(), "spo2_valid" to spo2Valid, "auto_physio" to autoPhysio,
            "baseline" to baseline?.mapValues { it.value.toLong() },
            "ramps" to ramps.map { (k, r) -> linkedMapOf<String, Any?>("key" to k, "from" to r.from.toLong(), "to" to r.to.toLong(), "start" to r.start - now, "dur" to r.dur) },
            "scheduled" to scheduled.map { linkedMapOf<String, Any?>("at" to it.at - now, "key" to it.key, "to" to it.to.toLong(), "dur" to it.dur) },
            "cpr" to cpr, "cpr_rate_q" to cprRateQ.toLong(), "cpr_depth_q" to cprDepthQ.toLong(),
            "breaths" to breaths.map { it - now }, "apnea_since" to rel(apneaSince), "spo2_pre_apnea" to spo2PreApnea.toLong(),
            // ANDROID (EtCO2 RCP 22/11)
            "breath_co2" to breathVals.map { it.toLong() }, "co2_flow" to co2Flow, "co2_auto" to co2Auto, "co2_manual" to co2Manual,
            "co2_display" to co2Display, "co2_cpr_seen" to co2CprSeen,   // ANDROID (EtCO2 visibile 24/11)
            "co2_last" to rel(co2LastAt), "cpr_work" to cprWork, "epi_at" to rel(epiAt),
            "art_base" to artBase, "cpr_recoil_q" to cprRecoilQ.toLong(), "cpr_hands_q" to cprHandsQ.toLong(),   // ANDROID (RCP dettagliata 22/11)
            "cpr_rate_cpm" to cprRateCpm.toLong(),
            "temp_t" to tempT.toLong(), "crt_t" to crtT.toLong(), "motion" to motion.toLong(), "motion_until" to rel(motionUntil),
            "spo2_low_perf" to spo2LowPerf, "episode" to dEpisode, "eff" to dEff.toLong(), "cv_eff" to cvEff.toLong(),
            // ANDROID (transizioni ritmo 22/11): preludio in corso -> al ripristino il ritmo nuovo arriva subito
            "onset_to" to onset?.to, "onset_deferred" to onsetDeferred,
            // ANDROID (ampiezza FV 22/11)
            "vf_amp" to vfAmp, "vf_freq" to vfFreq, "vf_manual" to vfManual, "vf_last" to rel(vfLastAt), "onset_last" to onsetLast, "onset_seq" to onsetSeq.toLong(),
            "ecg_morph" to ecgMorph.toMap(), "atrial_hr" to atrialHr.toLong())   // ANDROID (12 derivazioni 22/11)
    }

    // lettori con controllo e ripiego
    private fun rI(m: Map<*, *>, k: String, def: Int, lo: Int, hi: Int): Int {
        val v = m[k]
        if (v !is Number) return def
        val d = v.toDouble()
        if (!d.isFinite()) return def
        return SLLround(Math.max(lo.toDouble(), Math.min(hi.toDouble(), d)))
    }
    private fun rD(m: Map<*, *>, k: String, def: Double, lo: Double, hi: Double): Double {
        val v = m[k]
        if (v !is Number) return def
        val d = v.toDouble()
        if (!d.isFinite()) return def
        return Math.max(lo, Math.min(hi, d))
    }
    private fun rB(m: Map<*, *>, k: String, def: Boolean): Boolean = (m[k] as? Boolean) ?: def
    private fun rAbs(m: Map<*, *>, k: String, now: Double): Double {
        val v = m[k]
        if (v !is Number || !v.toDouble().isFinite()) return 0.0
        return Math.max(1e-6, now + Math.max(-86400.0, Math.min(86400.0, v.toDouble())))   // 0 = "mai" resta riservato
    }

    private fun restorePatient(m: Map<*, *>, now: Double) {
        val r = m["rhythm"]
        if (r is String && supportedRhythms().contains(r)) rhythm = r
        hr = rI(m, "hr", hr, kHRMin, kHRMax); spo2 = rI(m, "spo2", spo2, kSpO2Min, kSpO2Max)
        rr = rI(m, "rr", rr, kRRMin, kRRMax); etco2 = rI(m, "etco2", etco2, kEtCO2Min, kEtCO2Max)
        sys = rI(m, "sys", sys, kSysMin, kSysMax); dia = rI(m, "dia", dia, kDiaMin, kDiaMax)
        if (dia >= sys && sys > 0) dia = Math.max(0, sys - 1)
        spo2Valid = rB(m, "spo2_valid", spo2Valid); autoPhysio = rB(m, "auto_physio", autoPhysio)
        val ranges = SLParamRanges()
        val bl = m["baseline"]
        baseline = if (bl is Map<*, *>) {
            val b = LinkedHashMap<String, Int>()
            val defs = mapOf("spo2_percent" to 98, "respiratory_rate_rpm" to 16, "etco2_mmhg" to 35, "systolic_mmhg" to 120, "diastolic_mmhg" to 80)
            for ((k, d) in defs) { val rg = ranges[k]!!; b[k] = rI(bl, k, d, rg[0], rg[1]) }
            b
        } else null
        val rp = LinkedHashMap<String, SLRamp>()
        (m["ramps"] as? List<*>)?.forEach { x ->
            val e = x as? Map<*, *> ?: return@forEach
            val k = e["key"] as? String ?: return@forEach
            val rg = ranges[k] ?: return@forEach
            val dur = rD(e, "dur", 0.0, 0.0, 7200.0)
            if (dur <= 0) return@forEach
            rp[k] = SLRamp(rI(e, "from", param(k), rg[0], rg[1]), rI(e, "to", param(k), rg[0], rg[1]), now + rD(e, "start", 0.0, -86400.0, 86400.0), dur)
        }
        ramps = rp
        val sc = ArrayList<SLScheduled>()
        (m["scheduled"] as? List<*>)?.forEach { x ->
            val e = x as? Map<*, *> ?: return@forEach
            val k = e["key"] as? String ?: return@forEach
            val rg = ranges[k] ?: return@forEach
            sc.add(SLScheduled(now + rD(e, "at", 0.0, -86400.0, 86400.0), k, rI(e, "to", param(k), rg[0], rg[1]), rD(e, "dur", 0.0, 0.0, 7200.0)))
        }
        scheduled = sc
        cpr = rB(m, "cpr", cpr); cprRateQ = rI(m, "cpr_rate_q", 0, 0, 2); cprDepthQ = rI(m, "cpr_depth_q", 0, 0, 2)
        breaths = ((m["breaths"] as? List<*>) ?: emptyList<Any?>()).mapNotNullTo(ArrayList()) { (it as? Number)?.toDouble()?.takeIf { d -> d.isFinite() }?.let { d -> now + Math.max(-120.0, Math.min(0.0, d)) } }
        apneaSince = rAbs(m, "apnea_since", now); spo2PreApnea = rI(m, "spo2_pre_apnea", spo2, kSpO2Min, kSpO2Max)
        // ANDROID (EtCO2 RCP 22/11): modello EtCO2 in RCP (file vecchi: il modello riparte da capo al primo tick)
        val bv = (m["breath_co2"] as? List<*>)?.map { ((it as? Number)?.toDouble()?.takeIf { d -> d.isFinite() } ?: etco2.toDouble()).let { d -> Math.max(0, Math.min(kEtCO2Max, SLLround(d))) } }
        breathVals = if (bv != null && bv.size == breaths.size) ArrayList(bv) else breaths.mapTo(ArrayList()) { etco2 }
        co2Flow = rD(m, "co2_flow", 0.0, 0.0, 1.0); co2Auto = rD(m, "co2_auto", 0.0, 0.0, kEtCO2Max.toDouble())
        co2Manual = rB(m, "co2_manual", false); co2LastAt = rAbs(m, "co2_last", now)
        co2Display = (m["co2_display"] as? String)?.takeIf { it in listOf("auto", "30_2", "always", "realistic") } ?: "auto"   // ANDROID (EtCO2 visibile 24/11)
        co2CprSeen = rB(m, "co2_cpr_seen", false); co2AutoNext = 0.0; co2AutoSecond = false; co2RunSince = 0.0
        cprWork = rD(m, "cpr_work", 0.0, 0.0, 86400.0); epiAt = rAbs(m, "epi_at", now)
        artBase = rD(m, "art_base", 0.0, 0.0, 300.0); cprRecoilQ = rI(m, "cpr_recoil_q", 0, 0, 1); cprHandsQ = rI(m, "cpr_hands_q", 0, 0, 1)
        cprRateCpm = rI(m, "cpr_rate_cpm", listOf(110, 85, 140)[cprRateQ], 40, 200); cprRateQ = rateCategory(cprRateCpm)
        tempT = rI(m, "temp_t", tempT, 250, 450); crtT = rI(m, "crt_t", crtT, 5, 100)
        motion = rI(m, "motion", 0, 0, 2); motionUntil = rAbs(m, "motion_until", now)
        spo2LowPerf = rB(m, "spo2_low_perf", spo2LowPerf)
        dEpisode = (m["episode"] as? String)?.takeIf { supportedRhythms().contains(it) }
        dEff = rI(m, "eff", 0, 0, 100); cvEff = rI(m, "cv_eff", 0, 0, 100)
        // ANDROID (ampiezza FV 22/11)
        vfAmp = rD(m, "vf_amp", SLVfCoarseMv, SLVfMinMv * 0.6, SLVfMaxMv); vfFreq = rD(m, "vf_freq", 1.0, 0.5, 1.5)
        vfManual = rD(m, "vf_manual", -1.0, -1.0, SLVfMaxMv).let { if (it < SLVfMinMv) -1.0 else it }; vfLastAt = rAbs(m, "vf_last", now)
        onsetLast = (m["onset_last"] as? Map<*, *>)?.let { ol -> linkedMapOf<String, Any?>("id" to ((ol["id"] as? Number)?.toLong() ?: 0L),
            "from" to (ol["from"] as? String), "to" to (ol["to"] as? String), "text" to (ol["text"] as? String)?.take(200), "variant" to (ol["variant"] as? String)?.take(40)) }
        onsetSeq = rI(m, "onset_seq", 0, 0, 1000000)
        // ANDROID (12 derivazioni 22/11)
        (m["ecg_morph"] as? Map<*, *>)?.let { em ->
            val mm = LinkedHashMap<String, Any?>(); for (k in listOf("st", "st_mm", "bbb", "av_block", "pr_ms", "escape", "extra", "interpretation")) if (em[k] != null) mm[k] = em[k]
            SFEcgMorphology.parse(mm, SLEcgMorph.NORMAL).first?.let { ecgMorph = it }
        }
        atrialHr = rI(m, "atrial_hr", 80, 40, 150)
        // ANDROID (transizioni ritmo 22/11): un preludio interrotto dal salvataggio: il ritmo nuovo arriva subito
        onset = null; onsetDeferred = null; onsetPre = null
        val ot = m["onset_to"]
        if (ot is String && supportedRhythms().contains(ot) && ot != rhythm) {
            val od = m["onset_deferred"]
            try { if (od is Map<*, *>) transition(od, true, false) else applyRhythm(ot) } catch (e: SLCmdError) { applyRhythm(ot) }
        }
    }

    fun exportState(): Map<String, Any?> {
        synchronized(this) {
            cprAccrue()
            val now = clock()
            fun rel(t: Double): Any? = if (t > 0) t - now else null
            val sim = linkedMapOf<String, Any?>("running" to running, "paused_at" to rel(pausedAt), "scenario_time_s" to scenTimeNow(),
                "device_powered" to devicePowered, "power_by" to powerBy, "revision" to revision.toLong(), "ended" to scenEnded,
                "started" to scenStarted)                                // ANDROID (avvio scenario 22/11)
            val sensors = linkedMapOf<String, Any?>("ecg" to ecgOn, "spo2" to spo2On, "nibp" to nibpOn, "etco2" to etco2On, "art" to artOn,
                "temp" to tempOn, "pads" to padsOn, "pads_cable" to padsCable, "pads_poor" to padsPoor, "pads_ped" to padsPed,
                "aed_child" to aedChild, "oxi" to oxiOn, "oxi_power" to oxiPower, "sites" to LinkedHashMap(sites),
                "spo2_stage" to spo2Stage.toLong(), "spo2_conn_at" to rel(spo2ConnAt), "oxi_conn_at" to rel(oxiConnAt),
                "spo2_delay" to spo2Delay.toLong(), "vehicle" to vehicle, "tech_alarms" to ArrayList(techAlarms), "touch_until" to rel(touchUntil),
                "ever_on" to ArrayList(everOn))
            val nibp = linkedMapOf<String, Any?>("status" to nibpStatus.toLong(), "error" to nibpError, "started_at" to rel(nibpStartedAt),
                "target" to nibpTarget, "end" to nibpEnd, "cuff_ok" to nibpCuffOK, "last_sys" to nibpLastSys.toLong(), "last_dia" to nibpLastDia.toLong(),
                "measured_at_ms" to nibpMeasuredAt?.time, "motion_hit" to nibpMotionHit, "interval" to nibpInterval.toLong(), "next" to rel(nibpNext))
            val monitor = linkedMapOf<String, Any?>("limits" to limits.mapValues { it.value.toLong() }, "alarm_on" to LinkedHashMap(alarmOn),
                "volume" to volume.toLong(), "qrs_beep" to qrsBeep, "muted" to muted, "muted_until" to rel(mutedUntil),
                "crt_on" to crtOn, "crt_seq" to crtSeq.toLong(), "oxi_limits" to oxiLimits.mapValues { it.value.toLong() },
                "oxi_volume" to oxiVolume.toLong(), "oxi_muted_until" to rel(oxiMutedUntil))
            val defib = linkedMapOf<String, Any?>("mode" to dMode.toLong(), "energy" to dEnergy.toLong(), "charged_energy" to dChargedEnergy.toLong(),
                "charge" to dCharge.toLong(), "charge_start" to rel(dChargeStart), "charge_dur" to dChargeDur, "ready_at" to rel(dReadyAt),
                "sync" to dSync, "sync_pending" to dSyncPending, "sync_since" to rel(dSyncSince),
                "aed_phase" to aedPhase.toLong(), "aed_phase_start" to rel(aedPhaseStart), "aed_advised" to aedAdvised, "aed_motion" to aedMotion,
                "aed_await" to aedAwait, "aed_await_said_at" to rel(aedAwaitSaidAt), "aed_motion_since" to rel(aedMotionSince),
                "aed_motion_override" to aedMotionOverride, "aed_motion_said_at" to rel(aedMotionSaidAt), "pads_poor_said_at" to rel(padsPoorSaidAt),
                "cpr_fb_at" to rel(cprFbAt), "cpr_fb_bad" to cprFbBad, "note" to dNote, "note_at" to rel(dNoteAt), "voice" to dVoice,
                "prompt_seq" to dPromptSeq.toLong(), "shocks" to dShocks.toLong(), "last_energy" to dLastEnergy.toLong(),
                "last_shock" to rel(dLastShock), "session" to rel(dSession), "last_sync" to dLastSync,
                "pace_rate" to pRate.toLong(), "pace_out" to pOut.toLong(), "pace_demand" to pDemand, "pace_run" to pRun, "pace_paused" to pPaused,
                "events" to dEvents.map { LinkedHashMap(it) }, "ev_seq" to dEvSeq.toLong(),
                "decision" to dDecision, "decision_from" to dDecisionFrom, "decision_shock" to dDecisionShock.toLong(),
                "decision_energy" to dDecisionEnergy.toLong(), "decision_sync" to dDecisionSync,
                "config" to LinkedHashMap(dCfg), "seq_idx" to seqIdx.toLong())
            val chain = linkedMapOf<String, Any?>("active" to chainActive, "waiting" to chainWaiting, "name" to chainName,
                "index" to chainIndex.toLong(), "phase_start" to rel(chainPhaseStart), "steps" to chainSteps?.let { ArrayList(it) })
            val metrics = linkedMapOf<String, Any?>("arrest" to cmArrest, "cpr" to cmCpr, "stopped_at" to rel(cprStoppedAt),
                "shock_at" to rel(cmShockAt), "shocks" to cmShocks.map { LinkedHashMap(it) }, "pending_n" to cmPending?.get("n"))
            return linkedMapOf("format" to kExportFormat, "saved_at_ms" to System.currentTimeMillis(), "device_id" to deviceId,
                "layout" to layout, "patient" to exportPatient(now), "sim" to sim, "sensors" to sensors, "nibp" to nibp,
                "monitor" to monitor, "defib" to defib, "chain" to chain, "cpr_metrics" to metrics,
                // ANDROID (guasti 22/11)
                "device_faults" to linkedMapOf<String, Any?>("kinds" to ArrayList(faults), "beep" to faultBeepOn, "batt_shocks" to battShocks.toLong(),
                    "batt_on_s" to battOnS, "batt_replaced_say" to battReplacedSay),
                // ANDROID (ora 22/11): scarto dell'ora del controller (lo stesso orologio del tablet dopo il riavvio: resta valido)
                "wall_clock" to linkedMapOf<String, Any?>("synced" to wallSynced, "offset_ms" to wallOffsetMs))
        }
    }

    fun restoreState(state: Map<String, Any?>): String? {
        if ((state["format"] as? Number)?.toLong() != kExportFormat) return "restore: unsupported format"
        val p = state["patient"] as? Map<*, *> ?: return "restore: patient missing"
        synchronized(this) {
            val now = clock()
            resetValues()
            fun M(k: String): Map<*, *> = state[k] as? Map<*, *> ?: emptyMap<String, Any?>()
            val lay = state["layout"]
            if (lay is String && supportedLayouts().contains(lay)) layout = lay
            // configurazione del defibrillatore: chiave per chiave con la stessa validazione di set_defib_config
            val df = M("defib")
            (df["config"] as? Map<*, *>)?.forEach { (k, v) ->
                if (k is String && k != "sync_off_after_shock") {
                    try { defibCommand("set_defib_config", linkedMapOf(k to v)) } catch (e: SLCmdError) { /* valore non valido: resta quello di serie */ }
                }
            }
            restorePatient(p, now)
            // ANDROID (guasti 22/11): malfunzionamenti PRIMA dell'accensione (la batteria esaurita la impedisce)
            val fz = M("device_faults")
            for (k in ((fz["kinds"] as? List<*>) ?: emptyList<Any?>())) if (k is String && SLDeviceFaultKinds.contains(k) && k != "pads_unplugged" && k != "pads_poor_contact") faults.add(k)
            val bk = SLDeviceFaultBattery.filter { faults.contains(it) }
            if (bk.size > 1) faults.removeAll(bk.drop(1).toSet())             // esclusivi: resta il primo
            faultBeepOn = rB(fz, "beep", true); battShocks = rI(fz, "batt_shocks", -1, -1, 100)
            if (!faults.contains("battery_low") && !faults.contains("battery_replace")) battShocks = -1
            battOnS = rD(fz, "batt_on_s", 0.0, 0.0, kBatteryReplaceShutdownS); battReplacedSay = rB(fz, "batt_replaced_say", false)
            updatePowerBlock()
            // ANDROID (ora 22/11): ora sincronizzata con il controller (se c'era)
            val wc = M("wall_clock")
            val wOff = (wc["offset_ms"] as? Number)?.takeIf { wc["offset_ms"] !is Boolean }?.toDouble()
            if (rB(wc, "synced", false) && wOff != null && wOff.isFinite() && Math.abs(wOff) < 4.0e12) {
                wallOffsetMs = Math.round(wOff); wallSynced = true; SFWallClock.apply(wallOffsetMs, true)
            }
            // simulazione
            val sm = M("sim")
            running = rB(sm, "running", true)
            pausedAt = if (!running) (rAbs(sm, "paused_at", now).takeIf { it > 0 } ?: now) else 0.0
            scenEnded = !running && rB(sm, "ended", false)               // ANDROID (fine scenario 22/11)
            // ANDROID (avvio scenario 22/11): un salvataggio senza "started" (versione di prima) era sempre avviato
            scenStarted = rB(sm, "started", true) || scenEnded
            scenAccum = if (scenStarted) rD(sm, "scenario_time_s", 0.0, 0.0, 864000.0) else 0.0
            scenSince = if (running && scenStarted) now else 0.0
            devicePowered = rB(sm, "device_powered", false)
            powerBy = (sm["power_by"] as? String)?.takeIf { it == "istruttore" || it == "partecipanti" } ?: "partecipanti"
            // sensori
            val se = M("sensors")
            ecgOn = rB(se, "ecg", false); spo2On = rB(se, "spo2", false); nibpOn = rB(se, "nibp", false); etco2On = rB(se, "etco2", false)
            artOn = rB(se, "art", false); tempOn = rB(se, "temp", false); padsOn = rB(se, "pads", false); padsCable = rB(se, "pads_cable", true)
            padsPoor = rB(se, "pads_poor", false); padsPed = rB(se, "pads_ped", false); aedChild = rB(se, "aed_child", false)
            oxiOn = rB(se, "oxi", false); oxiPower = rB(se, "oxi_power", false)
            val ok = "abcdefghijklmnopqrstuvwxyz0123456789_"
            sites = LinkedHashMap()
            (se["sites"] as? Map<*, *>)?.forEach { (k, v) ->
                if (k is String && supportedSensors().contains(k) && v is String && v.isNotEmpty() && v.length <= 24 && v.all { ok.indexOf(it) >= 0 }) sites[k] = v
            }
            spo2Stage = rI(se, "spo2_stage", 3, 1, 3); spo2ConnAt = rAbs(se, "spo2_conn_at", now); oxiConnAt = rAbs(se, "oxi_conn_at", now)
            spo2Delay = rI(se, "spo2_delay", spo2Delay, 0, 120); vehicle = rB(se, "vehicle", false); spo2AcqLen = spo2Delay.toDouble()
            hemoPulse = null                                     // ANDROID (EM-07): si riallinea al primo tick, senza reazioni
            techAlarms = LinkedHashSet(((se["tech_alarms"] as? List<*>) ?: emptyList<Any?>()).filterIsInstance<String>().filter { SLTechnicalAlarmKinds.contains(it) })
            touchUntil = rAbs(se, "touch_until", now)
            everOn = LinkedHashSet(((se["ever_on"] as? List<*>) ?: emptyList<Any?>()).filterIsInstance<String>())   // ANDROID (IN-01)
            if (ecgOn) everOn.add(SLSensorECG); if (spo2On) everOn.add(SLSensorSpO2); if (artOn) everOn.add(SLSensorART)
            // NIBP
            val nb = M("nibp")
            nibpStatus = rI(nb, "status", SLNibpIdle, SLNibpIdle, SLNibpError)
            nibpError = (nb["error"] as? String)?.takeIf { it == "cuff" || it == "no_pulse" || it == "motion" }
            if (nibpStatus == SLNibpError && nibpError == null) nibpError = "cuff"
            nibpStartedAt = rAbs(nb, "started_at", now)
            if (nibpStatus == SLNibpMeasuring && nibpStartedAt <= 0) nibpStartedAt = now
            nibpTarget = rD(nb, "target", 140.0, 20.0, 400.0); nibpEnd = rD(nb, "end", 20.0, 0.0, 400.0); nibpCuffOK = rB(nb, "cuff_ok", nibpOn)
            nibpLastSys = rI(nb, "last_sys", -1, -1, kSysMax); nibpLastDia = rI(nb, "last_dia", -1, -1, kDiaMax)
            nibpMeasuredAt = (nb["measured_at_ms"] as? Number)?.let { Date(it.toLong()) }
            nibpMotionHit = rB(nb, "motion_hit", false)
            nibpInterval = rI(nb, "interval", 0, 0, 60).let { if (nibpIntervals().contains(it)) it else 0 }
            nibpNext = if (nibpInterval > 0) (rAbs(nb, "next", now).takeIf { it > 0 } ?: (now + nibpInterval * 60.0)) else 0.0
            // monitor
            val mo = M("monitor")
            (mo["limits"] as? Map<*, *>)?.let { lm ->
                val next = LinkedHashMap(defaultAlarmLimits())
                for ((k, rg) in SLLimitRanges()) next[k] = rI(lm, k, next[k] ?: rg[0], rg[0], rg[1])
                for ((k, rg) in SLExtraLimitRanges()) { val x = (lm[k] as? Number)?.toInt() ?: continue; if ((x == 0 && SLOffableLimits.contains(k)) || x in rg[0]..rg[1]) next[k] = x }   // ANDROID (IN-09)
                var good = true
                for (pfx in listOf("hr", "rr", "etco2", "sys")) if ((next[pfx + "_low"] ?: 0) >= (next[pfx + "_high"] ?: 0)) good = false
                limits = if (good) next else LinkedHashMap(defaultAlarmLimits())
            }
            (mo["alarm_on"] as? Map<*, *>)?.forEach { (k, v) -> if (k is String && alarmOn.containsKey(k) && v is Boolean) alarmOn[k] = v }
            volume = rI(mo, "volume", volume, 0, 100); qrsBeep = rB(mo, "qrs_beep", qrsBeep)
            muted = rB(mo, "muted", false); mutedUntil = if (muted) rAbs(mo, "muted_until", now) else 0.0
            crtOn = rB(mo, "crt_on", crtOn); crtSeq = rI(mo, "crt_seq", crtSeq, 0, Int.MAX_VALUE)
            (mo["oxi_limits"] as? Map<*, *>)?.let { lm ->
                val next = LinkedHashMap(defaultOximeterLimits())
                for ((k, rg) in SLOxiLimitRanges()) next[k] = rI(lm, k, next[k] ?: rg[0], rg[0], rg[1])
                oxiLimits = if ((next["spo2_low"] ?: 0) < (next["spo2_high"] ?: 0) && (next["pr_low"] ?: 0) < (next["pr_high"] ?: 0)) next
                            else LinkedHashMap(defaultOximeterLimits())
            }
            oxiVolume = rI(mo, "oxi_volume", oxiVolume, 0, 100); oxiMutedUntil = rAbs(mo, "oxi_muted_until", now)
            // defibrillatore
            dMode = rI(df, "mode", SLDefibOff, SLDefibOff, SLDefibPacer)
            dEnergy = rI(df, "energy", SLManualDefaultEnergy, 1, 360).let { if (defibEnergies().contains(it)) it else SLManualDefaultEnergy }
            dCharge = rI(df, "charge", SLChargeNone, SLChargeNone, SLChargeReady)
            dChargedEnergy = if (dCharge == SLChargeNone) 0 else rI(df, "charged_energy", dEnergy, 1, 360)
            dChargeStart = rAbs(df, "charge_start", now); dChargeDur = rD(df, "charge_dur", 1.0, 0.1, 30.0); dReadyAt = rAbs(df, "ready_at", now)
            if (dCharge == SLChargeReady && dReadyAt <= 0) dReadyAt = now
            dSync = rB(df, "sync", false); dSyncPending = rB(df, "sync_pending", false) && dCharge == SLChargeReady; dSyncSince = rAbs(df, "sync_since", now)
            aedPhase = rI(df, "aed_phase", SLAedPads, SLAedPads, SLAedCPR); aedPhaseStart = rAbs(df, "aed_phase_start", now).takeIf { it > 0 } ?: now
            aedAdvised = rB(df, "aed_advised", false); aedMotion = rB(df, "aed_motion", false)
            aedAwait = rB(df, "aed_await", false); aedAwaitSaidAt = rAbs(df, "aed_await_said_at", now); aedMotionSince = rAbs(df, "aed_motion_since", now)
            aedMotionOverride = rB(df, "aed_motion_override", false); aedMotionSaidAt = rAbs(df, "aed_motion_said_at", now)
            padsPoorSaidAt = rAbs(df, "pads_poor_said_at", now); cprFbAt = rAbs(df, "cpr_fb_at", now); cprFbBad = rB(df, "cpr_fb_bad", false)
            dNote = df["note"] as? String; dNoteAt = rAbs(df, "note_at", now); dVoice = df["voice"] as? String
            dPromptSeq = rI(df, "prompt_seq", 0, 0, Int.MAX_VALUE); dShocks = rI(df, "shocks", 0, 0, 10000)
            dLastEnergy = rI(df, "last_energy", 0, 0, 360); dLastShock = rAbs(df, "last_shock", now); dSession = rAbs(df, "session", now)
            dLastSync = rB(df, "last_sync", false)
            pRate = rI(df, "pace_rate", 70, 30, 210); pOut = rI(df, "pace_out", 0, 0, 200); pDemand = rB(df, "pace_demand", true)
            pRun = rB(df, "pace_run", false) && dMode == SLDefibPacer; pPaused = rB(df, "pace_paused", false) && pRun
            dEvents = ArrayList()
            ((df["events"] as? List<*>) ?: emptyList<Any?>()).takeLast(60).forEach { e ->
                val m = e as? Map<*, *> ?: return@forEach
                val t = m["text"] as? String ?: return@forEach
                dEvents.add(linkedMapOf("time" to (m["time"] as? String ?: ""), "elapsed" to (m["elapsed"] as? String ?: ""), "text" to t,
                                        "instructor" to (m["instructor"] == true), "seq" to ((m["seq"] as? Number)?.toLong() ?: 0L)))
            }
            dEvSeq = Math.max(rI(df, "ev_seq", 0, 0, Int.MAX_VALUE), dEvents.maxOfOrNull { (it["seq"] as Long).toInt() } ?: 0)
            dDecision = rB(df, "decision", false); dDecisionFrom = (df["decision_from"] as? String)?.takeIf { supportedRhythms().contains(it) }
            dDecisionAt = if (dDecision) simNow() else 0.0
            dDecisionShock = rI(df, "decision_shock", 0, 0, 10000); dDecisionEnergy = rI(df, "decision_energy", 0, 0, 360); dDecisionSync = rB(df, "decision_sync", false)
            seqIdx = rI(df, "seq_idx", 0, 0, 1000)
            // catena (transizioni programmate): ogni passo ricontrollato come in run_chain
            val ch = M("chain")
            val steps = ch["steps"] as? List<*>
            var stepsOk = steps != null && steps.isNotEmpty() && steps.size <= 40
            if (stepsOk) for (st in steps!!) {
                if (st !is Map<*, *>) { stepsOk = false; break }
                try { transition(st, false) } catch (e: SLCmdError) { stepsOk = false; break }
            }
            if (stepsOk && rB(ch, "active", false)) {
                chainSteps = ArrayList(steps!!); chainName = (ch["name"] as? String)?.take(60) ?: "catena"
                chainIndex = rI(ch, "index", 0, 0, steps.size); chainWaiting = rB(ch, "waiting", true)
                chainPhaseStart = rAbs(ch, "phase_start", now).takeIf { it > 0 } ?: now; chainActive = true
            }
            // metriche RCP
            val cm = M("cpr_metrics")
            cmArrest = rD(cm, "arrest", 0.0, 0.0, 864000.0); cmCpr = Math.min(cmArrest, rD(cm, "cpr", 0.0, 0.0, 864000.0))
            cprStoppedAt = rAbs(cm, "stopped_at", now); cmShockAt = rAbs(cm, "shock_at", now); cmLastAt = if (running) now else pausedAt
            ((cm["shocks"] as? List<*>) ?: emptyList<Any?>()).takeLast(40).forEach { e ->
                val m = e as? Map<*, *> ?: return@forEach
                val rec = linkedMapOf<String, Any?>("n" to ((m["n"] as? Number)?.toLong() ?: 0L), "t" to ((m["t"] as? Number)?.toLong() ?: 0L),
                    "energy_j" to ((m["energy_j"] as? Number)?.toLong() ?: 0L), "pre_s" to (m["pre_s"] as? Number)?.toLong(),
                    "post_s" to (m["post_s"] as? Number)?.toLong(), "sync" to (m["sync"] == true), "pulse" to (m["pulse"] == true))
                cmShocks.add(rec)
            }
            val pn = (cm["pending_n"] as? Number)?.toLong()
            cmPending = if (pn != null) cmShocks.lastOrNull { it["n"] == pn } else null
            // coerenza finale
            if (!padsLive() && (dCharge != SLChargeNone || pRun)) { dCharge = SLChargeNone; dChargedEnergy = 0; dSyncPending = false; pRun = false; pPaused = false }
            if (dMode != SLDefibAED) aedAwait = false
            revision = Math.max(revision, rI(sm, "revision", 0, 0, Int.MAX_VALUE - 10)) + 1
            defibLog("Stato ripristinato dopo il riavvio", true)
        }
        return null
    }

    // Il saturimetro (client HTTP) ha appena interrogato lo stato: "online" per qualche secondo
    fun noteOximeterSeen() { synchronized(this) { oxiSeenAt = clock() } }

    fun resetScenario() {
        synchronized(this) { resetValues(); devicePowered = false; revision++ }
    }

    fun snapshot(): SLSnapshot {
        val s = SLSnapshot()
        synchronized(this) {
            // ANDROID (transizioni ritmo 22/11): il preludio arriva anche senza tick (client/istanze che leggono solo lo stato)
            if (onset != null && running) onsetAdvance(simNow())
            s.revision = revision; s.rhythm = rhythm
            s.heartRate = hr; s.spo2 = spo2; s.respRate = rr; s.etco2 = etco2
            s.systolic = sys; s.diastolic = dia
            s.spo2SignalSetting = spo2Valid
            // posizioni effettive: quelle indicate dalle regie, altrimenti quelle di serie per i sensori collegati
            val sites = LinkedHashMap(this.sites)
            val on = linkedMapOf(SLSensorSpO2 to spo2On, SLSensorNIBP to nibpOn, SLSensorART to artOn, SLSensorOxi to oxiOn)
            for ((k, v) in on) if (v && !sites.containsKey(k)) SLDefaultSite(k)?.let { sites[k] = it }
            s.sensorSites = sites
            // bracciale in misura sullo stesso braccio della sonda: il dito non è perfuso, niente pletismo
            val cuffSide = SLSiteSide(sites[SLSensorNIBP])
            val spo2Side = SLSiteSide(sites[SLSensorSpO2])
            val cuffUpperArm = sites[SLSensorNIBP]?.startsWith("upper_arm") == true
            s.spo2CuffOccluded = spo2On && nibpOn && nibpStatus == SLNibpMeasuring && cuffSide != null && spo2Side != null &&
                                 cuffUpperArm && sites[SLSensorSpO2]?.startsWith("finger") == true && cuffSide == spo2Side
            s.spo2SignalValid = spo2Valid && !s.spo2CuffOccluded
            // linea arteriosa sullo stesso braccio del bracciale in misura: a valle del bracciale chiuso la curva perde la pulsatilità
            val artSide = SLSiteSide(sites[SLSensorART])
            s.artCuffOccluded = artOn && nibpOn && nibpStatus == SLNibpMeasuring && cuffSide != null && artSide != null &&
                                cuffUpperArm && SLArmArterySite(sites[SLSensorART]) && cuffSide == artSide
            // ANDROID (funzioni 21/11): con un allarme tecnico simulato il monitor si comporta come se il segnale mancasse
            // (il sensore resta collegato nello stato: sensors del dizionario); le piastre contano solo con il cavo collegato
            s.ecgConnected = ecgEff(); s.spo2Connected = spo2On && !techAlarms.contains("spo2_probe_off"); s.nibpConnected = nibpOn
            s.etco2Connected = etco2On && !techAlarms.contains("etco2_occlusion"); s.artConnected = artOn
            s.tempConnected = tempOn; s.padsConnected = padsLive(); s.peaNarrow = rhythm == SLRhythmPEA && peaNarrow   // ANDROID (EF-03)
            run {   // ANDROID (perfusione 22/11)
                val pf = perfFraction(if (mapS >= 0) mapS else mapNow())
                s.perfusionFraction = if (spo2LowPerf) Math.min(pf, 0.35) else pf
                s.perfusionIndex = Math.round((0.2 + 3.8 * s.perfusionFraction) * 10) / 10.0
                s.spo2WeakSignal = spo2On && spo2Weak
            }
            // ANDROID (difetti clinici 22/11, IN-01/IN-02): «sensore staccato» solo se era in uso o se il distrattore lo simula
            s.ecgLeadsOffAlarm = !s.ecgConnected && (everOn.contains(SLSensorECG) || techAlarms.contains("ecg_leads_off"))
            s.spo2ProbeOffAlarm = !s.spo2Connected && (everOn.contains(SLSensorSpO2) || techAlarms.contains("spo2_probe_off"))
            s.artLineOffAlarm = !artOn && everOn.contains(SLSensorART)
            s.oxiConnected = oxiOn; s.oxiPower = oxiPower; s.oxiVolume = oxiVolume; s.oxiLimits = LinkedHashMap(oxiLimits)
            s.oxiSensorSince = if (oxiOn) Math.max(0.0, clock() - oxiConnAt) else -1.0
            s.oxiOnline = oxiSeenAt > 0 && clock() - oxiSeenAt < 6
            s.oxiMutedRemaining = Math.ceil(Math.max(0.0, oxiMutedUntil - clock())).toInt()
            val oxiSide = SLSiteSide(sites[SLSensorOxi])
            s.oxiCuffOccluded = oxiOn && nibpOn && nibpStatus == SLNibpMeasuring && cuffSide != null && oxiSide != null &&
                                cuffUpperArm && sites[SLSensorOxi]?.startsWith("finger") == true && cuffSide == oxiSide
            s.spo2DelaySec = spo2Delay; s.spo2LowPerfusionFlag = spo2LowPerf
            s.operatorTouch = touchUntil > clock()
            s.patientMotion = Math.max(motion, if (vehicle) 1 else 0); s.patientMotionSet = motion; s.vehicleMotion = vehicle   // ANDROID (21/11)
            s.patientMotionRemaining = if (motion != 0 && motionUntil > 0) Math.ceil(Math.max(0.0, motionUntil - simNow())).toInt() else 0
            s.spo2Searching = spo2On && spo2Stage == 1; s.spo2Acquiring = spo2On && spo2Stage < 3
            s.temperatureTenths = tempT; s.crtTenths = crtT; s.crtEnabled = crtOn; s.crtAssessSeq = crtSeq; s.layout = layout
            s.devicePowered = devicePowered
            s.defib = defibInfoAt(simNow())
            s.nibpStatus = nibpStatus; s.nibpError = nibpError; s.nibpStartedAt = nibpStartedAt + pauseLag()   // ANDROID (1.1): misura ferma in pausa
            s.nibpTarget = nibpTarget; s.nibpEnd = nibpEnd; s.nibpCuffOK = nibpCuffOK
            s.nibpLastSystolic = nibpLastSys; s.nibpLastDiastolic = nibpLastDia; s.nibpMeasuredAt = nibpMeasuredAt
            s.alarmsMuted = muted
            s.alarmsMutedRemaining = if (muted) Math.ceil(Math.max(0.0, mutedUntil - clock())).toInt() else 0; s.qrsBeepEnabled = qrsBeep; s.volume = volume; s.tabletVolume = tabletVolume; s.tabletVolumeSeq = tabletVolumeSeq
            s.simulationRunning = running
            s.scenarioEnded = scenEnded                                   // ANDROID (fine scenario 22/11)
            s.scenarioStarted = scenStarted                               // ANDROID (avvio scenario 22/11)
            s.scenarioTime = scenAccum + (if (running && scenSince > 0) clock() - scenSince else 0.0)
            s.alarmLimits = LinkedHashMap(limits)
            s.autoPhysiology = autoPhysio
            s.cprActive = cpr
            s.cprRateQuality = kCprRateQ[cprRateQ]; s.cprDepthQuality = kCprDepthQ[dQ()]     // ANDROID (audit 26/11, PERF-F5): nomi costanti
            // ventilazione manuale: con l'RCP in corso, o in apnea, il paziente non respira da solo: il capnogramma mostra
            // solo le insufflazioni dei partecipanti (segnalate dall'istruttore) e la FR è quella delle insufflazioni.
            s.respRateSet = rr.toDouble(); s.etco2Set = etco2
            // ANDROID (EtCO2 RCP 22/11): in arresto (fisiologia automatica) il paziente non respira mai da solo, anche nei secondi
            // in cui la FR impostata scende a 0 dopo lo stop delle compressioni (prima: capnogramma «spontaneo» nella pausa)
            val manual = cpr || rr < 1 || co2ModelOn() || ventModeK == "bag"     // ANDROID (AE-12): pallone = sempre insufflazioni
            s.ventilationManual = manual
            if (manual) {
                val now = simNow()
                while (breaths.isNotEmpty() && now - breaths.first() > 60) { breaths.removeAt(0); if (breathVals.isNotEmpty()) breathVals.removeAt(0) }
                val lag = pauseLag()                                   // ANDROID (1.1): in pausa le insufflazioni restano ferme
                s.breathTimes = if (lag > 0) breaths.map { it + lag } else ArrayList(breaths)
                val recent = breaths.isNotEmpty() && now - breaths.last() <= 30      // dopo 30 s senza ventilare: EtCO2 "---"
                s.respRate = if (recent) Math.max(1, breaths.size) else 0
                if ((co2ModelOn() || apneaCo2On() || (etco2 < 1 && !cpr)) && breathVals.size == breaths.size) {   // ANDROID (EM-02, AE-10)
                    // ANDROID (EtCO2 RCP 22/11): ogni onda ha il suo picco; il numero è quello dell'ultima espirazione finita
                    // (come i monitor veri, che lo aggiornano a fine espirazione), oppure dell'unica in corso
                    s.breathCo2 = breathVals.map { it.toDouble() }
                    var k = breaths.size - 1
                    while (k > 0 && now - breaths[k] < 2.7) k--
                    if (k >= 0) s.etco2 = breathVals[k]
                } else if (etco2 < 1 && recent) s.etco2 = if (cpr) 15 else 35          // un'insufflazione porta sempre un po' di CO2
                // ANDROID (24/11): in arresto la CO2 resta negli alveoli finché nessuno ventila (etco2 = livello del modello),
                // ma senza espirazioni recenti il monitor non misura niente: valore mostrato 0 / «---»
                if (!recent && co2ModelOn()) s.etco2 = 0
            } else s.breathTimes = emptyList()
            // ANDROID (EtCO2 RCP 22/11): oscillazioni da compressione sul capnogramma (ben note: Leturiondo et al., Resuscitation
            // 2018; Gutiérrez et al., PLoS ONE 2018): piccole (pochi mmHg), alla frequenza delle compressioni, più ampie con più
            // CO2 nei polmoni e con compressioni profonde; ci sono anche senza ventilazioni (vicino alla linea di base)
            s.cprCompressionRate = cprRateCpm.toDouble()          // ANDROID (RCP dettagliata 22/11): esattamente la frequenza impostata
            s.etco2CprMode = if (co2ModelOn()) (if (co2Manual) "instructor" else "auto") else null
            // ANDROID (ART RCP 22/11): pressione in arresto per la curva (il motore la fa scendere/salire da solo tra due
            // istantanee) e per i numeri S/D
            s.cprRecoilQuality = kCprRecoilQ[rQ()]; s.cprHandsQuality = kCprHandsQ[cprHandsQ]
            s.cprFatigue = cprFatigueFrac() / 0.20
            if (!effectivePulse()) {   // ANDROID (EM-07)
                val nowA = simNow()
                val bt = artBaseTarget(nowA); val tau = artBaseTau()
                val base = if (co2LastAt > 0) bt + (artBase - bt) * Math.exp(-Math.max(0.0, nowA - co2LastAt) / tau) else bt
                s.artCprBase = base; s.artCprBaseTarget = bt; s.artCprBaseTau = tau
                s.artCprBaseAt = nowA + pauseLag()
                s.artCprAmp = if (cpr) Math.max(8.0, artCprPeak(nowA) - artCprRelax(nowA)) else 0.0
                s.artCprPaused = !running && pausedAt > 0
            }
            if (cpr) {
                val lvl = if (co2ModelOn()) co2Level() else etco2.toDouble()
                val kd = when (dQ()) { 1 -> 0.6; 2 -> 1.15; else -> 1.0 }
                s.co2CompressionAmp = Math.max(0.6, Math.min(4.0, 0.18 * lvl)) * kd
            }
            s.alarmEnabled = LinkedHashMap(alarmOn)                // copia: il dizionario originale cambia con i comandi
            s.nibpIntervalMin = nibpInterval; s.nibpNextAt = if (nibpNext > 0) nibpNext + pauseLag() else nibpNext   // ANDROID (1.1)
            val now = simNow()
            val tr = LinkedHashMap<String, Any?>()
            for ((k, r) in ramps) {
                val rem = r.start + r.dur - now
                tr[k] = linkedMapOf<String, Any?>("to" to r.to.toLong(), "remaining_s" to Math.ceil(Math.max(0.0, rem)).toLong(),
                                         "remaining_exact_s" to Math.round(Math.max(0.0, rem) * 100) / 100.0)   // ANDROID (26/11): «adesso» calcolato sul controller
            }
            s.transitions = tr
            val steps = chainSteps
            if (chainActive && steps != null && chainIndex < steps.size) {
                val st = steps[chainIndex] as Map<*, *>
                val len = ((if (chainWaiting) st["wait_s"] else st["duration_s"]) as? Number)?.toDouble() ?: 0.0
                val lbl = st["label"] as? String ?: ""
                s.chain = linkedMapOf("active" to true, "name" to (chainName ?: ""), "step" to (chainIndex + 1).toLong(), "steps" to steps.size.toLong(),
                                      "label" to lbl, "phase" to (if (chainWaiting) "waiting" else "running"),
                                      "remaining_s" to Math.ceil(Math.max(0.0, chainPhaseStart + len - now)).toLong())
            } else {
                s.chain = linkedMapOf<String, Any?>("active" to false)
            }
            // ANDROID (funzioni 21/11)
            s.cprMetrics = cprMetricsMap()
            s.autoAction = if (autoActionActive()) linkedMapOf("seq" to autoSeq.toLong(), "text" to autoText,
                "until" to Math.floor(scenTimeNow() + (autoUntil - clock())).toLong(),
                "remaining_s" to Math.ceil(Math.max(0.0, autoUntil - clock())).toLong()) else null
            s.technicalAlarms = ArrayList(techAlarms)
            // ANDROID (guasti 22/11)
            s.deviceFaults = faultList(); s.deviceReadiness = readiness(); s.deviceSelfTest = clock() < faultSelfTestUntil
            s.deviceFaultBeep = faultBeepOn
            s.wallClockSynced = wallSynced                                // ANDROID (ora 22/11)
            val fb = faultBeepNow(); s.faultBeepIntervalS = fb.first; s.faultBeepDouble = fb.second
            s.batteryShocksLeft = if (fx("battery_low") || fx("battery_replace")) Math.max(0, battShocks) else -1
            // ANDROID (transizioni ritmo 22/11): preludio in corso (il motore lo disegna; fermo in pausa)
            val op = onset
            s.rhythmOnset = op
            if (op != null) { s.rhythmOnsetStartAt = onsetStart + pauseLag(); s.rhythmOnsetOffset = Math.max(0.0, now - onsetStart) }
            // ANDROID (12 derivazioni 22/11)
            s.ecgMorph = ecgMorph; s.avBlockActive = avBlockActive(); s.atrialRate = atrialRateNow()
            // ANDROID (ampiezza FV 22/11)
            s.vfAmplitudeMv = if (rhythm == SLRhythmVentricularFib) vfAmpNow(now) else vfAmp
            s.vfFrequencyScale = if (rhythm == SLRhythmVentricularFib) vfFreqNow(now) else vfFreq
            s.vfAmplitudeMode = if (vfManual > 0) "instructor" else "auto"
            // ANDROID (funzioni mancanti 22/11): linea arteriosa, PVC, T2, ventilazione, elettrodi, ritardi d'allarme
            val ck = clock()
            s.clockNow = ck; s.alarmNow = now; s.alarmGate = alarmGate; s.alarmDelays = LinkedHashMap(alarmDelays)
            s.artDamping = artDampK; s.artOccluded = artOcclK && artOn; s.artLevelCm = artLevel; s.artZeroRequired = artZeroReq
            s.artOffset = SLArtLineMath.levelOffset(artLevel)
            s.artZeroing = artZeroUntil > ck; s.artZeroingUntil = artZeroUntil
            s.artNeedsZero = artOn && !artZeroed && !s.artZeroing
            s.artFlushAt = artFlushC; s.artZeroedAtMs = artZeroedMs
            s.cvpConnected = cvpOn; s.cvp = cvpV; s.cvpWave = cvpWaveK; s.cvpNeedsZero = cvpOn && !cvpZeroed && !s.artZeroing
            s.cvpLineOffAlarm = !cvpOn && everOn.contains(SLSensorCVP)
            s.temp2Connected = temp2On; s.temperature2Tenths = temp2T
            s.ventMode = ventModeK; s.ventRate = ventRateV; s.ventTidal = ventTidalV; s.ventDisconnected = ventDisc
            s.ventStartAt = ventStart + pauseLag(); s.advancedAirway = airwayAdv
            s.ppvAuto = ppvSet < 0; s.ppv = ppvNow()
            s.ecgElectrodeOff = if (ecgOn) ecgElOff else null; s.ecgNoise = if (ecgOn) ecgNoiseK else 0; s.limbReversal = ecgOn && limbRev
            s.techResolvable = techResolv; s.spo2SignalPoorSet = !spo2Valid
            s.apneaSeconds = if (s.respRate > 0) 0.0 else if (lastRespAt > 0) Math.max(0.0, now - lastRespAt) else 1e6
            // ANDROID (numerici realistici 22/11): i numeri a schermo, misurati dai valori veri come un monitor
            val qrsRate = if (s.defib.paceCapture) s.defib.paceRate else if (s.ecgOrganized) hr else 0
            mon.update(now, s, qrsRate, rhythm == SLRhythmAtrialFibrillation || (s.avBlockActive && (ecgMorph.avBlock == "mobitz1" || ecgMorph.avBlock == "mobitz2")))
            s.monHeartRate = mon.hr; s.monPulseRate = mon.pr; s.monSpo2 = mon.spo2; s.monEtco2 = mon.etco2; s.monRespRate = mon.rr
            s.monArtSystolic = mon.artS; s.monArtDiastolic = mon.artD; s.monTemperatureTenths = mon.tempT
            s.monCvp = mon.cvp; s.monTemperature2Tenths = mon.temp2T          // ANDROID (IN-13)
        }
        return s
    }

    // MARK: Validazione

    private fun spo2Connect(on: Boolean) {
        if (on && !spo2On) {                         // sonda appena messa: ricerca del polso, poi il valore
            spo2ConnAt = simNow(); spo2AcqLen = spo2Delay.toDouble()
            spo2Stage = if (spo2Delay > 0) 1 else 3
        }
        spo2On = on
    }

    private fun setSensor(sensor: String, connected: Boolean) {
        val on = connected
        if (on) everOn.add(sensor)                            // ANDROID (IN-01): da ora il sensore è «in uso»
        if (sensor == SLSensorECG) ecgOn = on
        else if (sensor == SLSensorSpO2) spo2Connect(on)
        else if (sensor == SLSensorNIBP) {
            if (on && !nibpOn && nibpStatus == SLNibpError && nibpError == "cuff") { nibpStatus = SLNibpIdle; nibpError = null }   // bracciale messo: l'errore di perdita sparisce
            nibpOn = on
            if (!on && nibpStatus == SLNibpMeasuring) {         // bracciale staccato durante la misura
                nibpStatus = SLNibpError; nibpError = "cuff"; nibpLastSys = -1; nibpLastDia = -1; nibpMeasuredAt = null   // perdita: il valore precedente non è più valido
            }
        }
        else if (sensor == SLSensorEtCO2) etco2On = on
        else if (sensor == SLSensorART) { if (on && !artOn && artZeroReq) artZeroed = false; artOn = on }   // ANDROID (IN-03): da azzerare
        else if (sensor == SLSensorCVP) { if (on && !cvpOn && artZeroReq) cvpZeroed = false; cvpOn = on }   // ANDROID (IN-13)
        else if (sensor == SLSensorTemp2) temp2On = on
        else if (sensor == SLSensorTemp) tempOn = on
        else if (sensor == SLSensorPads) {
            if (on != padsOn) {
                val was = padsLive()
                padsOn = on
                if (was != padsLive()) defibPadsChanged()
                else { defibLog(if (on) "Piastre applicate (cavo scollegato)" else "Piastre staccate", false); if (!on) padsPoor = false }   // ANDROID (21/11)
            }
        }
        else if (sensor == SLSensorOxi) { if (on && !oxiOn) oxiConnAt = clock(); oxiOn = on }
        else {
            SLFail("sensor: unsupported value; allowed: " + supportedSensors().joinToString(", "))
        }
    }

    // Controlla il valore di un comando "transition" (parametri, ritmo, durata) SENZA applicarlo.
    // Ritorna il messaggio d'errore, oppure null se è valido.
    fun validateTransition(value: Map<String, Any?>): String? {
        synchronized(this) {
            try { transition(value, false) } catch (e: SLCmdError) { return e.message }
            return null
        }
    }

    // Applica un comando API. Ritorna null se applicato; altrimenti
    // un messaggio leggibile (in inglese tecnico, stabile per i client).
    fun applyCommand(command: Map<String, Any?>): String? {
        val cmd = command
        // (sull'iPad: se cmd non è un dizionario -> "body must be a JSON object"; qui lo garantisce il tipo)
        val dev = cmd["device_id"]
        if (cmd.containsKey("device_id") && (dev !is String || dev != deviceId)) {
            return "device_id does not match this monitor"
        }
        val name = cmd["command"] as? String ?: return "missing 'command'"
        val value = cmd["value"]
        // sull'iPad un "value": null del JSON è NSNull (presente, non nil): hasValue distingue i due casi
        val hasValue = cmd.containsKey("value")

        synchronized(this) {
            try {
                cprAccrue()                                           // ANDROID (funzioni 21/11): metriche RCP fino a questo istante
                if (onset != null && running) onsetAdvance(simNow())  // ANDROID (transizioni ritmo 22/11): il preludio arrivato nel frattempo
                cmdOutcomeShock = cmd["shock"]                        // ANDROID (terzo giro): numero della scarica per defib_outcome
                if (defibCommand(name, value)) { cprMetricsCheck(); revision++; return null }
                if (name == "set_heart_rate") {
                    val v = SLIntValue(value, kHRMin, kHRMax, "heart_rate_bpm")
                    hr = v; ramps.remove("heart_rate_bpm")
                    asystoleIfZeroRate()
                } else if (name == "set_rhythm") {
                    if (value !is String || !supportedRhythms().contains(value)) {
                        SLFail("rhythm: unsupported value; allowed: " + supportedRhythms().joinToString(", "))
                    }
                    // ANDROID (transizioni ritmo 22/11): "onset": "auto" (di serie, preludio) | "immediate" (di colpo, come prima)
                    val auto = onsetModeOf(cmd["onset"], "set_rhythm")
                    // ANDROID (ampiezza FV 22/11): "vf_amplitude" facoltativo con la FV (vale dall'arrivo della FV)
                    val vfA = if (cmd.containsKey("vf_amplitude")) parseVfAmplitude(cmd["vf_amplitude"]) else null
                    setRhythmCommand(value, auto)
                    if (vfA != null && value == SLRhythmVentricularFib) {
                        if (rhythm == SLRhythmVentricularFib) { vfAdvance(simNow()); vfManual = vfA }
                        else pendingVfAmp = vfA
                    }
                } else if (name == "set_spo2") {
                    val v = SLIntValue(value, kSpO2Min, kSpO2Max, "spo2_percent")
                    spo2 = v; ramps.remove("spo2_percent")
                    if (apneaSince > 0) { spo2PreApnea = v; apneaSince = simNow() - 20 }   // in apnea: riparte da qui
                } else if (name == "set_spo2_delay") {
                    // secondi tra il collegamento della sonda e la comparsa del valore (metà: ricerca, metà: onda senza numero)
                    val v = SLIntValue(value, 0, 120, "spo2_delay_s")
                    spo2Delay = v
                } else if (name == "patient_touch") {
                    // PUSH: un operatore tocca / scuote il paziente per N secondi (1-10, predefinito 3), con qualsiasi ritmo
                    var dur = 3.0
                    if (value != null) {
                        // ANDROID (21/11): anche NaN rifiutato (prima passava il controllo e il tocco durava "per sempre")
                        if (value !is Number || !(value.toDouble() >= 1 && value.toDouble() <= 10)) SLFail("patient_touch: seconds 1-10")
                        dur = value.toDouble()
                    }
                    touchUntil = Math.max(touchUntil, clock() + dur)
                    defibLog("Operatore tocca il paziente", true)
                } else if (name == "set_cpr_quality") {
                    // ANDROID (21/11): {"rate": "ok"|"slow"|"fast", "depth": "ok"|"shallow"|"deep"} (anche uno solo dei due)
                    val mp = value as? Map<*, *> ?: SLFail("set_cpr_quality: {rate, depth}")
                    co2Advance(simNow()); co2Manual = false                  // ANDROID (EtCO2 RCP 22/11): l'EtCO2 torna al modello
                    // ANDROID (RCP dettagliata 22/11): "rate_cpm" (40-200) ha la precedenza; la sola categoria vale 110 / 85 / 140
                    val cpmV = mp["rate_cpm"]
                    if (cpmV != null) { cprRateCpm = SLIntValue(cpmV, 40, 200, "rate_cpm"); cprRateQ = rateCategory(cprRateCpm) }
                    else mp["rate"]?.let { val i = listOf("ok", "slow", "fast").indexOf(it); if (i < 0) SLFail("cpr rate: ok, slow or fast"); cprRateQ = i; cprRateCpm = listOf(110, 85, 140)[i] }
                    mp["depth"]?.let { val i = listOf("ok", "shallow", "deep").indexOf(it); if (i < 0) SLFail("cpr depth: ok, shallow or deep"); cprDepthQ = i }
                    // ANDROID (RCP dettagliata 22/11): rilascio del torace e posizione delle mani (facoltativi, retrocompatibili)
                    mp["recoil"]?.let { val i = listOf("ok", "incomplete").indexOf(it); if (i < 0) SLFail("cpr recoil: ok or incomplete"); cprRecoilQ = i }
                    mp["hands"]?.let { val i = listOf("ok", "wrong").indexOf(it); if (i < 0) SLFail("cpr hands: ok or wrong"); cprHandsQ = i }
                    cprFbAt = 0.0
                    defibLog(String.format(java.util.Locale.ITALY, "RCP: frequenza %s (%d/min), profondità %s, rilascio %s, mani %s", listOf("corretta", "lenta", "veloce")[cprRateQ], cprRateCpm,
                        listOf("corretta", "superficiale", "eccessiva")[cprDepthQ], listOf("completo", "incompleto")[cprRecoilQ], listOf("corrette", "in posizione sbagliata")[cprHandsQ]), true)
                } else if (name == "cpr_rescuer_change") {
                    // ANDROID (RCP dettagliata 22/11): cambio dell'operatore alle compressioni (senza valore): finisce l'affaticamento
                    co2Advance(simNow()); cprWork = 0.0
                    defibLog("RCP: cambio dell'operatore alle compressioni", true)
                } else if (name == "set_pads_contact") {
                    // ANDROID (proposta A8, 21/11): "good" | "poor" — piastre attaccate male (pelle bagnata, peli, piastra sollevata)
                    // ANDROID (AE-08): "marginal" = piastre non ottimali: la scarica parte, il registro riporta l'impedenza alta
                    if (value != "good" && value != "poor" && value != "marginal") SLFail("set_pads_contact: good, marginal or poor")
                    if ((value == "marginal") != padsMarginal) defibLog(if (value == "marginal") "Contatto delle piastre non ottimale (impedenza alta)" else "Impedenza delle piastre normale", true)
                    padsMarginal = value == "marginal"
                    padsContactSet(value == "poor")          // ANDROID (guasti 22/11): in una funzione (la usa anche set_device_fault)
                } else if (name == "set_patient_motion") {
                    // "off" | "light" | "strong"  oppure  {"level": ..., "duration_s": 0-120}  (0 = finché non viene fermato)
                    var lv: Any? = value
                    var dur = 0.0
                    if (value is Map<*, *>) {
                        lv = value["level"]
                        if (value.containsKey("duration_s")) {
                            val d = value["duration_s"]
                            if (d !is Number || !(d.toDouble() >= 0 && d.toDouble() <= 120)) SLFail("duration_s must be 0-120")   // ANDROID (21/11): anche NaN
                            dur = d.toDouble()
                        }
                    }
                    val li = if (lv is String) listOf("off", "light", "strong").indexOf(lv) else -1
                    if (li < 0) SLFail("patient motion must be off, light or strong")
                    if (li > 0 && !effectivePulse()) SLFail("patient motion requires a rhythm with pulse (use patient_touch)")
                    motion = li
                    motionUntil = if (motion != 0 && dur > 0) simNow() + dur else 0.0
                    if (motion == 2 && nibpStatus == SLNibpMeasuring) nibpMotionHit = true
                    if (motion != 0) defibLog(if (motion == 2) "Movimenti marcati del paziente" else "Movimenti lievi del paziente", true)
                } else if (name == "vehicle_motion") {
                    // ANDROID (funzioni 21/11): true/false o "on"/"off": ambulanza in movimento. Artefatto ECG come il movimento
                    // lieve del paziente e, in DAE, «Movimento rilevato» durante analisi e carica. Vale con qualsiasi ritmo.
                    val b = when (value) { "on" -> true; "off" -> false; else -> SLBoolValue(value, "vehicle_motion") }
                    if (b != vehicle) { vehicle = b; defibLog(if (b) "Ambulanza in movimento" else "Ambulanza ferma", true) }
                } else if (name == "set_device_fault") {
                    // ANDROID (guasti 22/11): {"kind": SLDeviceFaultKinds, "on": bool}: malfunzionamento simulato del dispositivo,
                    // valido anche a dispositivo spento (è lo scopo: il controllo giornaliero del DAE)
                    val mp = value as? Map<*, *> ?: SLFail("set_device_fault: value must be {\"kind\":name,\"on\":bool}")
                    val kind = mp["kind"]
                    if (kind !is String || !SLDeviceFaultKinds.contains(kind)) SLFail("set_device_fault: kind must be one of " + SLDeviceFaultKinds.joinToString(", "))
                    setDeviceFault(kind, SLBoolValue(mp["on"], "on"))
                } else if (name == "clear_device_faults") {
                    // ANDROID (guasti 22/11): «Dispositivo a posto»: tutti risolti (compresi cavo staccato e contatto scarso)
                    for (k in faultList()) setDeviceFault(k, false)
                } else if (name == "set_wall_clock") {
                    // ANDROID (ora 22/11): ora di sistema del controller in ms epoch (numero), mandata a ogni collegamento e ogni
                    // 10 min: il cuore tiene lo scarto rispetto all'orologio del tablet e lo usa per tutti gli orari scritti
                    val ms = (value as? Number)?.takeIf { value !is Boolean }?.toDouble()
                    if (ms == null || !ms.isFinite() || ms < kWallClockMin || ms > kWallClockMax)
                        SLFail("set_wall_clock: value must be epoch milliseconds (number, years 2000-2100)")
                    wallOffsetMs = Math.round(ms) - SFWallClock.system(); wallSynced = true
                    SFWallClock.apply(wallOffsetMs, true)
                } else if (name == "set_device_fault_beep") {
                    // ANDROID (guasti 22/11): bip da spento sì/no (di serie sì)
                    val b = SLBoolValue(value, "device_fault_beep")
                    if (b != faultBeepOn) { faultBeepOn = b; defibLog(if (b) "Bip da spento dei malfunzionamenti: sì" else "Bip da spento dei malfunzionamenti: no", true) }
                } else if (name == "set_technical_alarm") {
                    // ANDROID (funzioni 21/11): {"kind": ecg_leads_off|spo2_probe_off|nibp_cuff_error|low_battery|etco2_occlusion, "on": bool}
                    // Allarme tecnico simulato (distrattore): il sensore resta collegato nello stato (sensors), il monitor
                    // si comporta come se il segnale mancasse (snapshot) finché l'allarme è acceso.
                    val mp = value as? Map<*, *> ?: SLFail("set_technical_alarm: value must be {\"kind\":name,\"on\":bool}")
                    val kind = mp["kind"]
                    if (kind !is String || !SLTechnicalAlarmKinds.contains(kind)) SLFail("set_technical_alarm: kind must be one of " + SLTechnicalAlarmKinds.joinToString(", "))
                    val on = SLBoolValue(mp["on"], "on")
                    if (on != techAlarms.contains(kind)) {
                        if (on) techAlarms.add(kind) else techAlarms.remove(kind)
                        if (on) techSince[kind] = simNow() else techSince.remove(kind)   // ANDROID (IN-12)
                        if (kind == "nibp_cuff_error") {
                            if (on && nibpStatus != SLNibpMeasuring && nibpOn) { nibpStatus = SLNibpError; nibpError = "cuff" }
                            else if (!on && nibpStatus == SLNibpError && nibpError == "cuff" && nibpOn) { nibpStatus = SLNibpIdle; nibpError = null }
                        }
                        defibLog("Allarme tecnico simulato: ${SLTechnicalAlarmText(kind)} ${if (on) "attivato" else "tolto"}", true)
                    }
                } else if (name == "undo_auto_action") {
                    // ANDROID (funzioni 21/11): annulla l'ultimo avanzamento automatico entro 5 s (paziente di prima dell'esito)
                    val u = autoUndo
                    if (u == null || !autoActionActive()) SLFail("undo_auto_action: nothing to undo")
                    restorePatient(u, clock())
                    autoUndoDecision?.let {
                        dDecision = true; dDecisionFrom = it["from"] as? String; dDecisionShock = (it["shock"] as? Number)?.toInt() ?: dShocks; dDecisionAt = simNow()
                        dDecisionEnergy = (it["energy_j"] as? Number)?.toInt() ?: 0; dDecisionSync = it["sync"] == true
                    }
                    defibLog("Annullato dall'istruttore: ${autoText ?: ""}", true)
                    autoText = null; autoUndo = null; autoUndoDecision = null
                } else if (name == "set_spo2_low_perfusion") {
                    val b = SLBoolValue(value, "spo2_low_perfusion")
                    spo2LowPerf = b
                } else if (name == "set_device_power") {
                    // ANDROID (BugFixController 5): accensione/spegnimento a distanza, come il tasto di accensione del layout
                    val b = SLBoolValue(value, "device_power")
                    if (b && powerBlocked) SLFail("set_device_power: battery depleted (simulated fault)")   // ANDROID (guasti 22/11)
                    devicePowered = b
                    if (b) powerBy = "istruttore"
                } else if (name == "set_temperature") {
                    if (value !is Number || value.toDouble() != value.toDouble()
                        || value.toDouble() < 25.0 || value.toDouble() > 45.0) {
                        SLFail("temperature_c: value out of range [25..45]")
                    }
                    tempT = SLLround(value.toDouble() * 10.0)
                } else if (name == "set_crt") {
                    if (value !is Number || value.toDouble() != value.toDouble()
                        || value.toDouble() < 0.5 || value.toDouble() > 10.0) {
                        SLFail("crt_s: value out of range [0.5..10]")
                    }
                    crtT = SLLround(value.toDouble() * 10.0)
                } else if (name == "assess_crt") {
                    // l'istruttore fa valutare il refill (il partecipante lo chiede a voce invece di toccare il monitor):
                    // sul monitor parte la stessa valutazione del tocco, ~3 s poi compare il tempo
                    crtSeq++; crtOn = true
                } else if (name == "set_crt_enabled") {
                    val b = SLBoolValue(value, "crt_enabled")
                    crtOn = b
                } else if (name == "set_layout") {
                    if (value !is String || !supportedLayouts().contains(value)) {
                        SLFail("layout: allowed " + supportedLayouts().joinToString(", "))
                    }
                    if (value != layout) {
                        // ANDROID (analisi 21/11, 1.6 + regola utente 1b): passando a un layout DAE il dispositivo è spento
                        // (appena tolto dalla borsa) e nessuna terapia resta attiva: non si accende e non analizza da solo
                        if (SFLayoutIsAed(value)) {
                            defibSetMode(SLDefibOff)
                            devicePowered = false
                        }
                        // ANDROID (analisi 21/11, 1.7): le energie non passano da un layout all'altro: valori di serie
                        val c = LinkedHashMap(dCfg); c["aed_energy_j"] = SLAedDefaultEnergy(value).toLong(); dCfg = c
                        dEnergy = SLManualDefaultEnergyFor(value)
                        // ANDROID (AE-07): un altro dispositivo ha il suo contatore delle scariche (il totale resta nel registro)
                        if (dShocks > 0) defibLog("Cambio dispositivo: contatore delle scariche azzerato (scariche finora: $dShocks)", true)
                        dShocks = 0; dDecision = false; dDecisionFrom = null; dDecisionShock = 0
                    }
                    layout = value
                    // ANDROID (guasti 22/11): i malfunzionamenti restano; la batteria esaurita conta solo fuori dall'ICU (in rete)
                    updatePowerBlock()
                    if (powerBlocked && deviceOn()) faultShutdown("Dispositivo spento: batteria esaurita")
                } else if (name == "set_spo2_signal_valid") {
                    val b = SLBoolValue(value, "spo2_signal_valid")
                    spo2Valid = b
                } else if (name == "set_sensor") {
                    // {"command":"set_sensor","value":{"sensor":"ecg","connected":false}}
                    if (value !is Map<*, *> || value["sensor"] !is String) {
                        SLFail("set_sensor: value must be {\"sensor\":name,\"connected\":bool}")
                    }
                    val sensor = value["sensor"] as String
                    val b = SLBoolValue(value["connected"], "connected")
                    val site = value["site"]           // facoltativo: dove è messo il sensore sul paziente
                    if (site != null) {
                        val ok = "abcdefghijklmnopqrstuvwxyz0123456789_"
                        if (site !is String || site.length == 0 || site.length > 24 || site.any { ok.indexOf(it) < 0 }) {
                            SLFail("site: lowercase letters, digits and _ (max 24)")
                        }
                    }
                    setSensor(sensor, b)
                    if (!b) sites.remove(sensor)
                    else if (site is String) sites[sensor] = site
                    // senza lato esplicito vale quello di serie (SLDefaultSite, applicato nello snapshot): uguale in monitor e regie
                } else if (name == "set_probe_connected") {          // compatibilità v0.1 (SpO2)
                    val b = SLBoolValue(value, "probe_connected")
                    spo2Connect(b)
                } else if (name == "set_resp_rate") {
                    val v = SLIntValue(value, kRRMin, kRRMax, "respiratory_rate_rpm")
                    val oldRR = rr
                    rr = v; ramps.remove("respiratory_rate_rpm")
                    coupleEtCO2FromRR(oldRR, v, 45.0)
                } else if (name == "set_etco2") {
                    val v = SLIntValue(value, kEtCO2Min, kEtCO2Max, "etco2_mmhg")
                    if (co2ModelOn()) { co2Advance(simNow()); co2Manual = true }   // ANDROID (EtCO2 RCP 22/11): vale il valore dell'istruttore
                    etco2 = v; ramps.remove("etco2_mmhg")
                } else if (name == "set_bp" || name == "set_nibp") {
                    // PA del paziente: la mostra l'ART in continuo e la misura la NIBP
                    if (value !is Map<*, *>) SLFail("set_bp: value must be {\"systolic\":n,\"diastolic\":n}")
                    val s = SLIntValue(value["systolic"], kSysMin, kSysMax, "systolic_mmhg")
                    val d = SLIntValue(value["diastolic"], kDiaMin, kDiaMax, "diastolic_mmhg")
                    if (d >= s && s > 0) SLFail("set_bp: diastolic must be lower than systolic")
                    sys = s; dia = d
                    ramps.remove("systolic_mmhg"); ramps.remove("diastolic_mmhg")
                    val bl = baseline
                    if (bl != null) {   // durante l'arresto: la nuova PA diventa l'obiettivo del ROSC
                        val nb = LinkedHashMap(bl); nb["systolic_mmhg"] = s; nb["diastolic_mmhg"] = d; baseline = nb
                    }
                } else if (name == "mute_alarms") {
                    var b = true
                    var secs = 0
                    if (value is Number) {
                        secs = SLIntValue(value, 1, 900, "alarms_muted_s")
                    } else if (hasValue) b = SLBoolValue(value, "alarms_muted")
                    // ANDROID (IN-06): voce per il registro con chi ha silenziato (il tempo di risposta lo calcola il registro)
                    val srcM = SLCommandSource.current()
                    if (b) stEvent("monitor", "Allarmi silenziati" + (if (secs > 0) " per $secs s" else ""), "alarm_mute")
                    else if (muted) stEvent("monitor", if (srcM == "cuore") "Pausa audio interrotta da un nuovo allarme letale" else "Allarmi riattivati", "alarm_unmute")
                    muted = b
                    mutedUntil = if (b) clock() + (if (secs > 0) secs.toDouble() else muteDuration) else 0.0
                } else if (name == "alarms_ack") {
                    stEvent("monitor", "Allarmi riconosciuti (ripristino)", "alarm_ack")
                } else if (name == "set_oximeter") {
                    // saturimetro palmare: {power, volume, alarm_limits{spo2_low,spo2_high,pr_low,pr_high}, mute_s (0 = riattiva)}
                    if (value !is Map<*, *>) SLFail("value: object expected")
                    if (value.containsKey("power")) { val b = SLBoolValue(value["power"], "power"); oxiPower = b; if (!b) oxiMutedUntil = 0.0 }
                    if (value.containsKey("volume")) { val v = SLIntValue(value["volume"], 0, 100, "volume"); oxiVolume = v }
                    if (value.containsKey("mute_s")) { val v = SLIntValue(value["mute_s"], 0, 900, "mute_s"); oxiMutedUntil = if (v != 0) clock() + v else 0.0 }
                    if (value.containsKey("alarm_limits")) {
                        val lim = value["alarm_limits"]
                        if (lim !is Map<*, *>) SLFail("alarm_limits: object expected")
                        val next = LinkedHashMap(oxiLimits)
                        val rg = SLOxiLimitRanges()
                        for ((kk, x) in lim) {
                            val k = kk.toString()
                            val r = rg[k] ?: SLFail("alarm_limits.$k: unknown limit")
                            val v = SLIntValue(x, r[0], r[1], k); next[k] = v
                        }
                        if ((next["spo2_low"] ?: 0) >= (next["spo2_high"] ?: 0) || (next["pr_low"] ?: 0) >= (next["pr_high"] ?: 0)) {
                            SLFail("alarm_limits: low limit must be below high limit")
                        }
                        oxiLimits = next
                    }
                } else if (name == "set_qrs_beep") {
                    val b = SLBoolValue(value, "qrs_beep_enabled")
                    qrsBeep = b
                } else if (name == "set_tablet_volume") {
                    // {"command":"set_tablet_volume","value":40}  volume di sistema del tablet monitor, in percento (i tasti fisici sono nel guscio)
                    tabletVolume = SLIntValue(value, 0, 100, "tablet_volume_percent"); tabletVolumeSeq++
                } else if (name == "set_volume") {
                    val v = SLIntValue(value, 0, 100, "volume_percent")
                    volume = v
                } else if (name == "set_alarm_limits") {
                    // {"command":"set_alarm_limits","value":{"hr_low":45,"hr_high":130}}  (anche parziale)
                    if (value !is Map<*, *> || value.isEmpty()) {
                        SLFail("set_alarm_limits: value must be an object like {\"hr_low\":50}")
                    }
                    val next = LinkedHashMap(limits)
                    val ranges = SLLimitRanges()
                    val extra = SLExtraLimitRanges()   // ANDROID (IN-09): ART e NIBP separati, PAM, SpO2 alta, temperatura (0 = spento)
                    for ((kk, x) in value) {
                        val k = kk.toString()
                        val r = ranges[k] ?: extra[k] ?: SLFail("set_alarm_limits: unknown limit '$k'")
                        val v = if (SLOffableLimits.contains(k) && x is Number && x.toDouble() == 0.0) 0 else SLIntValue(x, r[0], r[1], k)
                        next[k] = v
                    }
                    // i limiti «sys» di prima valgono per ART e NIBP insieme (client vecchi), se non arrivano quelli separati
                    for (side in listOf("low", "high")) if (value.containsKey("sys_$side")) {
                        if (!value.containsKey("art_sys_$side")) next["art_sys_$side"] = next["sys_$side"]!!
                        if (!value.containsKey("nibp_sys_$side")) next["nibp_sys_$side"] = next["sys_$side"]!!
                    }
                    for (pfx in listOf("hr", "rr", "etco2", "sys", "art_sys", "nibp_sys")) {
                        val lo = pfx + "_low"
                        val hi = pfx + "_high"
                        if ((next[lo] ?: 0) >= (next[hi] ?: 0)) {
                            SLFail("set_alarm_limits: $lo must be lower than $hi")
                        }
                    }
                    for (pfx in listOf("art_map", "nibp_map", "temp")) {
                        val lo = next[pfx + "_low"] ?: 0; val hi = next[pfx + "_high"] ?: 0
                        if (lo > 0 && hi > 0 && lo >= hi) SLFail("set_alarm_limits: ${pfx}_low must be lower than ${pfx}_high")
                    }
                    if ((next["spo2_high"] ?: 0) > 0 && (next["spo2_high"] ?: 0) <= (next["spo2_low"] ?: 0)) SLFail("set_alarm_limits: spo2_high must be higher than spo2_low")
                    val ch = ArrayList<String>()
                    val sysCh = listOf("low", "high").filter { value.containsKey("sys_$it") }
                    for ((k, v) in next) {
                        val o = limits[k] ?: continue
                        if (o == v) continue
                        if (sysCh.any { k == "art_sys_$it" || k == "nibp_sys_$it" } && next["sys_" + k.substringAfterLast('_')] == v) continue
                        ch.add("${SLLimitLabel(k)} ${SLLimitValueText(k, o)} → ${SLLimitValueText(k, v)}")
                    }
                    limits = next
                    if (ch.isNotEmpty()) stEvent("monitor", "Limiti d'allarme: " + ch.joinToString(", "), "alarm_limit")
                } else if (name == "reset_alarm_limits") {
                    if (limits != defaultAlarmLimits()) stEvent("monitor", "Limiti d'allarme riportati ai valori di serie", "alarm_limit")
                    limits = LinkedHashMap(defaultAlarmLimits())
                } else if (name == "set_ecg_morphology") {
                    // ANDROID (12 derivazioni 22/11): morfologia dell'ECG (vedi SFEcgMorphology.kt, ECG_12D.md)
                    val (m, err) = SFEcgMorphology.parse(value, ecgMorph)
                    if (err != null) SLFail(err)
                    applyEcgMorph(m!!, true)
                } else if (name == "ecg12_acquired") {
                    // ANDROID (12 derivazioni 22/11): i partecipanti hanno aperto la carta a 12 derivazioni (tasto del layout):
                    // azione da debriefing. value facoltativo: {"layout": "...", "interpretation": "testo stampato"}
                    val mp = value as? Map<*, *>
                    ecg12Count++
                    val hrShown = if (!ecgEff()) -1 else if (rhythm == SLRhythmVentricularFib) -1 else if (rhythm == SLRhythmAsystole) 0 else hr
                    val interp = (mp?.get("interpretation") as? String)?.take(160)
                    ecg12Last = linkedMapOf("seq" to ecg12Count.toLong(), "scenario_time_s" to Math.floor(scenTimeNow()).toLong(),
                        "rhythm" to rhythm, "heart_rate_bpm" to (if (hrShown >= 0) hrShown.toLong() else null), "ecg_morphology" to SFEcgMorphology.describe(ecgMorph),
                        "layout" to ((mp?.get("layout") as? String)?.take(40) ?: layout), "interpretation" to interp, "electrodes" to ecgEff())
                    val fc = if (!ecgEff()) "elettrodi non collegati" else if (hrShown < 0) "FC ---" else "FC $hrShown"
                    defibLog("12 derivazioni acquisito ($fc) — ritmo: " + SLRhythmName(rhythm) + "; ECG: " + SFEcgMorphology.describe(ecgMorph) +
                             (if (interp != null && interp.isNotEmpty()) "; referto stampato: $interp" else ""), false)
                } else if (name == "set_vf_amplitude") {
                    // ANDROID (ampiezza FV 22/11): {"command":"set_vf_amplitude","value":"coarse"|"fine"|"auto"|0.05-1.5 (mV)}.
                    // Vale finché il ritmo non cambia; con un ritmo diverso dalla FV si rifiuta (con un preludio verso la FV vale
                    // all'arrivo)
                    val a = parseVfAmplitude(value)
                    if (rhythm == SLRhythmVentricularFib) {
                        vfAdvance(simNow())
                        if ((onset?.vfAmpFrom ?: -1.0) > 0) cancelOnset()        // FV -> asistolia interrotta: resta la FV
                        if (a < 0 && vfManual > 0) vfAmp = vfManual                // si riparte dal valore dell'istruttore
                        vfManual = a
                    } else if (onset?.to == SLRhythmVentricularFib) pendingVfAmp = a
                    else SLFail("set_vf_amplitude: the rhythm is not VF")
                    defibLog(if (a < 0) "Ampiezza della FV: automatica" else String.format(java.util.Locale.ITALY, "Ampiezza della FV: %.2f mV (%s)", a,
                             mapOf("coarse" to "grossolana", "medium" to "media", "fine" to "fine")[SLVfAmplitudeClass(a)]), true)
                } else if (name == "set_art_line") {
                    // ANDROID (funzioni mancanti 22/11, IN-03/OP-04): {"damping": optimal|over|under, "level_cm": -40..40 (+ = sopra
                    // l'asse flebostatico), "occluded": bool, "zero_required": bool} (anche una sola voce)
                    val mp = value as? Map<*, *> ?: SLFail("set_art_line: value must be an object")
                    val parts = ArrayList<String>()
                    var dk = artDampK; var lv = artLevel; var oc = artOcclK; var zr = artZeroReq
                    if (mp.containsKey("damping")) { dk = SLArtLineMath.dampingIndex(mp["damping"]); if (dk < 0) SLFail("set_art_line: damping must be optimal, over or under") }
                    if (mp.containsKey("level_cm")) lv = SLIntValue(mp["level_cm"], -40, 40, "level_cm")
                    if (mp.containsKey("occluded")) oc = SLBoolValue(mp["occluded"], "occluded")
                    if (mp.containsKey("zero_required")) zr = SLBoolValue(mp["zero_required"], "zero_required")
                    if (dk != artDampK) parts.add(listOf("smorzamento ottimale", "sovrasmorzata (bolla, coagulo)", "sottosmorzata (tubi lunghi/rigidi)")[dk])
                    if (lv != artLevel) parts.add(if (lv == 0) "trasduttore all'asse flebostatico" else String.format(Locale.ITALY, "trasduttore %d cm %s l'asse flebostatico (%+.0f mmHg)", Math.abs(lv), if (lv > 0) "sopra" else "sotto", SLArtLineMath.levelOffset(lv)))
                    if (oc != artOcclK) parts.add(if (oc) "linea occlusa (non pulsatile)" else "linea di nuovo pervia")
                    if (zr != artZeroReq) parts.add(if (zr) "zero obbligatorio dopo il collegamento" else "zero non obbligatorio")
                    if (dk == 0 && artDampK != 0 || !oc && artOcclK) techSince.remove("art_line")
                    if ((dk != 0 && artDampK == 0) || (oc && !artOcclK)) techSince["art_line"] = simNow()
                    artDampK = dk; artLevel = lv; artOcclK = oc
                    if (zr != artZeroReq) { artZeroReq = zr; if (!zr) { artZeroed = true; cvpZeroed = true } }
                    if (parts.isNotEmpty()) stEvent("monitor", "Linea arteriosa: " + parts.joinToString(", "), "art_line")
                } else if (name == "art_zero") {
                    // ANDROID (IN-03): azzeramento all'aria delle pressioni invasive (tasto Zero del monitor o regia): 3 s a 0 mmHg,
                    // poi le linee sono azzerate (il livello del trasduttore NON si corregge con lo zero: va spostato)
                    if (!artOn && !cvpOn) SLFail("art_zero: no invasive pressure line connected")
                    if (artZeroUntil <= clock()) {
                        artZeroUntil = clock() + 3.0; zeroBy = SLCommandSource.current() ?: "partecipanti"
                        stEvent("monitor", "Azzeramento " + listOfNotNull(if (artOn) "ART" else null, if (cvpOn) "PVC" else null).joinToString(" e ") + " avviato", "art_zero_start")
                    }
                } else if (name == "art_flush") {
                    // ANDROID (IN-03/OP-04): prova del lavaggio rapido (fast flush): onda quadra e poi oscillazioni secondo lo smorzamento
                    if (!artOn) SLFail("art_flush: arterial line not connected")
                    artFlushC = clock()
                    val res = if (artOcclK) "la curva non torna pulsatile: linea occlusa"
                              else listOf("1-2 oscillazioni: smorzamento ottimale", "nessuna oscillazione, ritorno lento: sovrasmorzata", "molte oscillazioni: sottosmorzata")[artDampK]
                    stEvent("action", "Prova di lavaggio rapido della linea arteriosa (onda quadra): $res", "art_flush")
                } else if (name == "set_cvp") {
                    // ANDROID (IN-13): PVC media (mmHg) — {"command":"set_cvp","value":8} oppure {"mmhg":8,"waveform":"large_v"}
                    if (value is Map<*, *>) {
                        if (value.containsKey("mmhg")) { cvpV = SLIntValue(value["mmhg"], 0, 40, "cvp_mmhg"); ramps.remove("cvp_mmhg") }
                        if (value.containsKey("waveform")) setCvpWave(value["waveform"])
                    } else { cvpV = SLIntValue(value, 0, 40, "cvp_mmhg"); ramps.remove("cvp_mmhg") }
                } else if (name == "set_cvp_waveform") {
                    setCvpWave(value)
                } else if (name == "set_temperature2") {
                    if (value !is Number || value.toDouble() != value.toDouble() || value.toDouble() < 25.0 || value.toDouble() > 45.0) SLFail("temperature2_c: value out of range [25..45]")
                    temp2T = SLLround(value.toDouble() * 10.0)
                } else if (name == "set_ventilation") {
                    // ANDROID (CC-04/AE-12): {"mode": spontaneous|ventilator|bag, "rate_rpm": 4-40, "tidal_ml": 200-1000,
                    // "disconnected": bool, "advanced_airway": bool, "arrest_rate_rpm": 0|6-30} (anche una sola voce)
                    val mp = value as? Map<*, *> ?: SLFail("set_ventilation: value must be an object")
                    var mode = ventModeK; var rate = ventRateV; var tid = ventTidalV; var disc = ventDisc
                    if (mp.containsKey("mode")) { val m = mp["mode"]; if (m !is String || m !in listOf("spontaneous", "ventilator", "bag")) SLFail("set_ventilation: mode must be spontaneous, ventilator or bag"); mode = m }
                    if (mp.containsKey("rate_rpm")) rate = SLIntValue(mp["rate_rpm"], 4, 40, "rate_rpm")
                    if (mp.containsKey("tidal_ml")) tid = SLIntValue(mp["tidal_ml"], 200, 1000, "tidal_ml")
                    if (mp.containsKey("disconnected")) disc = SLBoolValue(mp["disconnected"], "disconnected")
                    if (mp.containsKey("advanced_airway")) airwayAdv = SLBoolValue(mp["advanced_airway"], "advanced_airway")
                    if (mp.containsKey("arrest_rate_rpm")) { val a = SLIntValue(mp["arrest_rate_rpm"], 0, 30, "arrest_rate_rpm"); if (a in 1..5) SLFail("arrest_rate_rpm: 0 or 6-30"); ventArrestRate = a }
                    // ANDROID (EtCO2 visibile 24/11): come compare l'EtCO2 in arresto
                    if (mp.containsKey("etco2_cpr_display")) {
                        val d = mp["etco2_cpr_display"]
                        if (d !is String || d !in listOf("auto", "30_2", "always", "realistic")) SLFail("set_ventilation: etco2_cpr_display must be auto, 30_2, always or realistic")
                        if (d != co2Display) {
                            co2Display = d; co2AutoNext = 0.0; co2AutoSecond = false
                            stEvent("action", "EtCO₂ in arresto: " + when (d) { "auto" -> "ventilazioni automatiche (30:2 con maschera, 10/min con via aerea avanzata)"; "30_2" -> "ventilazioni automatiche 30:2"; "always" -> "sempre visibile"; else -> "ventilazioni a mano («Ventila»)" }, "vent", "cuore")
                        }
                    }
                    if (mode != "ventilator") disc = false
                    val pulse = effectivePulse()
                    val oldVA = if (ventAuto()) ventMinuteAlv(ventRateV, ventTidalV) else ventMinuteAlv(Math.max(1, rr), 450)
                    val was = ventModeK; val wasDisc = ventDisc; val wasRate = ventRateV; val wasTid = ventTidalV
                    ventModeK = mode; ventRateV = rate; ventTidalV = tid; ventDisc = disc
                    if (mode == "ventilator") {
                        if (was != "ventilator" || (wasDisc && !disc)) ventStart = clock()
                        if (disc) { ramps.remove("respiratory_rate_rpm"); rr = 0 }
                        else {
                            if (!pulse && autoPhysio && ventArrestRate > 0 && !mp.containsKey("rate_rpm")) { if (ventPreArrest == 0) ventPreArrest = ventRateV; ventRateV = ventArrestRate }
                            ramps.remove("respiratory_rate_rpm"); rr = ventRateV
                            if (pulse) ventCoupleEtco2(oldVA, ventMinuteAlv(ventRateV, ventTidalV))
                        }
                    } else if (was == "ventilator" && mode == "spontaneous" && rr < 1 && pulse) rr = 0
                    val txt = when {
                        mode == "ventilator" && disc -> "Ventilatore SCOLLEGATO dal paziente"
                        mode == "ventilator" && (was != mode || wasDisc) -> "Paziente in ventilazione meccanica: ${ventRateV}/min, $ventTidalV ml"
                        mode == "ventilator" && (wasRate != ventRateV || wasTid != ventTidalV) -> "Ventilatore: ${ventRateV}/min, $ventTidalV ml"
                        mode == "bag" && was != mode -> "Ventilazione con pallone" + (if (airwayAdv) " su via aerea avanzata" else "")
                        mode == "spontaneous" && was != mode -> "Respiro spontaneo (ventilazione assistita sospesa)"
                        else -> null
                    }
                    if (txt != null) stEvent("action", txt, "vent")
                } else if (name == "set_ppv") {
                    // ANDROID (OP-05): "auto" (dall'indice di shock) oppure 0-30 (%)
                    ppvSet = if (value == "auto" || value == null) -1.0 else SLIntValue(value, 0, 30, "ppv_percent").toDouble()
                } else if (name == "set_alarm_delays") {
                    // ANDROID (IN-08): {"spo2": 5, "apnea": 20, ...} secondi 0-60 (0 = subito)
                    val mp = value as? Map<*, *> ?: SLFail("set_alarm_delays: value must be an object")
                    val next = LinkedHashMap(alarmDelays)
                    for ((kk, x) in mp) { val k = kk.toString(); if (!SLDefaultAlarmDelays().containsKey(k)) SLFail("set_alarm_delays: unknown parameter '$k'"); next[k] = SLIntValue(x, 0, 60, k) }
                    val ch = next.filter { alarmDelays[it.key] != it.value }.map { "${it.key} ${it.value} s" }
                    alarmDelays = next
                    if (ch.isNotEmpty()) stEvent("monitor", "Ritardi d'allarme: " + ch.joinToString(", "), "alarm_delay")
                } else if (name == "set_ecg_electrode") {
                    // ANDROID (IN-10): un solo elettrodo staccato: "ra" | "la" | "ll" | "v" | "none"
                    val v = if (value == null || value == "none") null else value as? String
                    if (v != null && v !in listOf("ra", "la", "ll", "v")) SLFail("set_ecg_electrode: ra, la, ll, v or none")
                    if (v != ecgElOff) { if (v != null) techSince["ecg_electrode"] = simNow() else techSince.remove("ecg_electrode") }
                    ecgElOff = v
                } else if (name == "set_ecg_noise") {
                    val v = if (value is Boolean) (if (value) 1 else 0) else SLIntValue(value, 0, 2, "ecg_noise")
                    if ((v > 0) != (ecgNoiseK > 0)) { if (v > 0) techSince["ecg_noise"] = simNow() else techSince.remove("ecg_noise") }
                    ecgNoiseK = v
                } else if (name == "set_limb_reversal") {
                    limbRev = SLBoolValue(value, "limb_reversal")
                } else if (name == "set_tech_resolvable") {
                    techResolv = SLBoolValue(value, "tech_resolvable")
                } else if (name == "resolve_technical") {
                    // ANDROID (IN-12): il partecipante controlla e sistema (tocco lungo sulla traccia/presa): il distrattore si spegne
                    // solo se la regia lo ha reso risolvibile (dalla regia si risolve sempre)
                    val mp = value as? Map<*, *>
                    val kind = (mp?.get("kind") ?: value) as? String ?: SLFail("resolve_technical: kind required")
                    val src = SLCommandSource.current()
                    if (src == null && !techResolv) SLFail("resolve_technical: not enabled for this scenario")
                    var done = true
                    when (kind) {
                        "spo2_signal" -> { if (spo2Valid) done = false; spo2Valid = true }
                        "ecg_electrode" -> { if (ecgElOff == null) done = false; ecgElOff = null }
                        "ecg_noise" -> { if (ecgNoiseK == 0) done = false; ecgNoiseK = 0 }
                        "art_line" -> { if (artDampK == 0 && !artOcclK) done = false; artDampK = 0; artOcclK = false }
                        else -> {
                            if (!SLTechnicalAlarmKinds.contains(kind)) SLFail("resolve_technical: unknown kind '$kind'")
                            if (!techAlarms.remove(kind)) done = false
                            if (kind == "nibp_cuff_error" && nibpStatus == SLNibpError && nibpOn) { nibpStatus = SLNibpIdle; nibpError = null }
                        }
                    }
                    if (done) {
                        val t0 = techSince.remove(kind)
                        val dt = if (t0 != null) Math.max(0, SLLround(simNow() - t0)) else -1
                        stEvent("action", techName(kind) + (if (dt >= 0) " dopo ${dt / 60}:${String.format(Locale.US, "%02d", dt % 60)}" else ""), "tech_resolved",
                                extra = linkedMapOf("kind" to kind, "after_s" to (if (dt >= 0) dt.toLong() else null)))
                    }
                } else if (name == "set_labs") {
                    // ANDROID (IN-13): {"ph": 7.21, "lactate_mmol": 4.5, "glucose_mgdl": 180, "urine_ml_h": 20, ...}; null toglie la voce
                    val mp = value as? Map<*, *> ?: SLFail("set_labs: value must be an object")
                    val next = LinkedHashMap(labs); val txt = ArrayList<String>()
                    for ((kk, x) in mp) {
                        val k = kk.toString(); val f = SLLabFields[k] ?: SLFail("set_labs: unknown field '$k'")
                        if (x == null) { next.remove(k); continue }
                        if (x !is Number || x.toDouble() != x.toDouble() || x.toDouble() < (f[0] as Double) || x.toDouble() > (f[1] as Double)) SLFail("set_labs: $k out of range [${f[0]}..${f[1]}]")
                        next[k] = x.toDouble(); txt.add(SLLabText(k, x.toDouble()))
                    }
                    labs = next
                    if (txt.isNotEmpty()) stEvent("patient", "Esami/bilancio: " + txt.joinToString(", "), "labs")
                } else if (name == "transition") {
                    // {"command":"transition","value":{"params":{"heart_rate_bpm":140},"duration_s":60,"rhythm":"afib"}}
                    transition(value, true)
                } else if (name == "cancel_transitions") {
                    // senza valore: tutte; con ["heart_rate_bpm", ...]: solo quei parametri (restano al valore raggiunto)
                    if (value is List<*>) { for (k in value) if (k is String) ramps.remove(k) }
                    else if (hasValue) SLFail("cancel_transitions: value must be an array of parameter names")
                    else ramps.clear()
                } else if (name == "run_chain") {
                    // {"command":"run_chain","value":{"name":"...","steps":[{"wait_s":10,"duration_s":30,"rhythm":"vf","params":{...},"label":"..."}]}}
                    val steps = if (value is Map<*, *>) value["steps"] else null
                    if (steps !is List<*> || steps.size == 0 || steps.size > 40) {
                        SLFail("run_chain: value.steps must be an array of 1..40 steps")
                    }
                    for (i in 0 until steps.size) {
                        val st = steps[i]
                        if (st !is Map<*, *>) SLFail("run_chain: step ${i + 1} is not an object")
                        if (st.containsKey("wait_s")) SLIntValue(st["wait_s"], 0, 3600, "wait_s")
                        try { transition(st, false) } catch (e2: SLCmdError) { SLFail("run_chain: step ${i + 1}: ${e2.message}") }
                    }
                    chainSteps = ArrayList(steps)
                    val nm = (value as Map<*, *>)["name"]
                    chainName = if (nm is String) nm.substring(0, Math.min(nm.length, 60)) else "catena"
                    chainIndex = 0; chainWaiting = true; chainPhaseStart = simNow(); chainActive = true
                    advanceChainAt(chainPhaseStart)
                } else if (name == "stop_chain") {
                    chainActive = false; ramps.clear()
                } else if (name == "set_auto_physiology") {
                    val b = SLBoolValue(value, "auto_physiology")
                    autoPhysio = b; if (!b) baseline = null
                    co2LastAt = 0.0; co2Manual = false                    // ANDROID (EtCO2 RCP 22/11): il modello riparte da capo
                } else if (name == "set_alarm_enabled") {
                    // {"command":"set_alarm_enabled","value":{"param":"hr","enabled":false}}
                    val pk = if (value is Map<*, *>) value["param"] else null
                    if (pk !is String || !alarmOn.containsKey(pk)) {
                        SLFail("set_alarm_enabled: param must be one of hr, spo2, art, nibp, rr, etco2, temp")
                    }
                    val b = SLBoolValue((value as Map<*, *>)["enabled"], "enabled")
                    val nm = mapOf("hr" to "FC", "spo2" to "SpO₂", "art" to "ART", "nibp" to "NIBP", "rr" to "FR", "etco2" to "EtCO₂", "temp" to "temperatura")[pk] ?: pk
                    if (alarmOn[pk] != b) stEvent("monitor", if (b) "Allarme $nm riattivato" else "Allarme $nm spento", if (b) "alarm_enabled" else "alarm_disabled")   // ANDROID (IN-06)
                    alarmOn[pk] = b
                } else if (name == "ventilate") {
                    // una insufflazione dei partecipanti (pallone, ventilatore, bocca a bocca): onda di CO2 e valore a monitor
                    if (!(cpr || rr < 1 || co2ModelOn() || ventModeK == "bag")) SLFail("ventilate: the patient is breathing on his own (only during CPR or apnoea)")
                    insufflate(simNow())
                } else if (name == "set_cpr") {
                    val b = SLBoolValue(value, "cpr_active")
                    if (b != cpr) {
                        if (!b) cprStoppedAt = simNow()                    // ANDROID (21/11): fine delle compressioni (pausa pre-shock)
                        if (b && noShockAt > 0) {                          // ANDROID (25/11)
                            defibLog("Shock non consigliato: RCP ripresa dopo ${SLLround(Math.max(0.0, simNow() - noShockAt)).toLong()} s", true)
                            noShockAt = 0.0
                        }
                        co2Advance(simNow())                               // ANDROID (EtCO2 RCP 22/11): il modello fino a adesso
                        cpr = b
                        if (autoPhysio && !effectivePulse() && !ventAuto()) {   // ANDROID (EM-07; CC-04: col ventilatore la FR resta)
                            // ventilazioni 10/min (FR impostata); l'EtCO2 la fa il modello (co2Advance): sale con le compressioni,
                            // crolla in pochi secondi alla pausa (ANDROID EtCO2 RCP 22/11: prima rampa fissa a 15 / a 0)
                            if (b) rampParam("respiratory_rate_rpm", 10, 5.0) else rampParam("respiratory_rate_rpm", 0, 5.0)
                        }
                    }
                } else if (name == "set_nibp_interval") {
                    // minuti tra due misure automatiche (0 = spenta); la prima parte dopo un intervallo
                    val v = SLIntValue(value, 0, 60, "nibp_interval_min")
                    if (!nibpIntervals().contains(v)) SLFail("nibp_interval_min: allowed 0,1,2,3,5,10,15,30,60")
                    // ANDROID (IN-19): passando dalla manuale all'automatica la prima misura parte SUBITO (come i monitor), poi a intervalli
                    val wasInt = nibpInterval
                    nibpInterval = v; nibpNext = if (v > 0) simNow() + v * 60.0 else 0.0
                    if (v > 0 && wasInt == 0 && nibpOn && nibpStatus != SLNibpMeasuring) {
                        nibpStatus = SLNibpMeasuring; nibpError = null; nibpMotionHit = (motion == 2); nibpStartedAt = simNow(); nibpCuffOK = nibpOn
                        nibpTarget = Math.max(140.0, sys.toDouble() + 30.0)
                        nibpEnd = if (effectivePulse() && sys > 0) Math.max(20.0, dia.toDouble() - 12.0) else 20.0
                    }
                } else if (name == "set_simulation_running") {
                    val b = SLBoolValue(value, "simulation_running")
                    // ANDROID (avvio scenario 22/11): «riprendi» in PRONTO (client vecchio, tasto PAUSA del monitor) = start_scenario
                    if (b && !scenStarted) startScenario()
                    else if (b != running) {                              // il timer di scenario non conta le pause
                        if (b) { scenSince = if (scenStarted) clock() else 0.0 }   // ANDROID (avvio scenario 22/11): in PRONTO resta fermo
                        else if (scenSince > 0) { scenAccum += clock() - scenSince; scenSince = 0.0 }
                        // ANDROID (analisi 21/11, 1.1): la pausa ferma anche il paziente e i dispositivi; alla ripresa
                        // ogni riferimento temporale si sposta della durata della pausa (riparte da dove era)
                        if (!b) pausedAt = clock()
                        else { if (pausedAt > 0) shiftClinicalTimes(clock() - pausedAt); pausedAt = 0.0 }
                    }
                    running = b
                    if (b) scenEnded = false                              // ANDROID (fine scenario 22/11): la ripresa riapre lo scenario
                } else if (name == "end_scenario") {
                    // ANDROID (fine scenario 22/11): fine dello scenario (tasto FINE SCENARIO del controller, poi il debriefing).
                    // Mette in pausa come set_simulation_running false (paziente e dispositivi fermi, timer fermo) e lo segna.
                    if (running) {
                        if (scenSince > 0) { scenAccum += clock() - scenSince; scenSince = 0.0 }
                        pausedAt = clock()
                        running = false
                    }
                    scenEnded = true
                } else if (name == "start_scenario") {
                    // ANDROID (avvio scenario 22/11): tasto «Avvia» (controller, web regia): vedi startScenario
                    startScenario()
                } else if (name == "reset_clock") {
                    // azzera SOLO l'orologio di scenario (parametri, sonde e contatori restano): lo usa l'istruttore
                    // e l'avvio di uno scenario a fasi
                    scenAccum = 0.0; scenSince = if (running && scenStarted) clock() else 0.0   // ANDROID (avvio scenario 22/11): in PRONTO resta a 00:00
                    scenEnded = false                                     // ANDROID (fine scenario 22/11): nuovo avvio
                } else if (name == "reset_scenario") {
                    resetValues()
                    devicePowered = false // il reset spegne sempre il monitor (torna lo standby)
                } else {
                    SLFail("unknown command '$name'")
                }
                cprMetricsCheck()                                     // ANDROID (funzioni 21/11)
                revision++
            } catch (e: SLCmdError) {
                return e.message
            }
        }
        return null
    }

    // Avvia un ciclo NIBP simulato (solo software). Ritorna l'errore se già in corso, altrimenti null.
    fun startNibpMeasurement(): String? {
        synchronized(this) {
            if (nibpStatus == SLNibpMeasuring) return "NIBP measurement already in progress"
            nibpStatus = SLNibpMeasuring
            nibpError = null; nibpMotionHit = (motion == 2)
            nibpStartedAt = simNow()
            nibpCuffOK = nibpOn
            // gonfia ~30 mmHg sopra la sistolica (minimo 140); se non trova oscillazioni scende fino a 20
            nibpTarget = Math.max(140.0, sys.toDouble() + 30.0)
            val pulse = effectivePulse() && sys > 0
            nibpEnd = if (pulse) Math.max(20.0, dia.toDouble() - 12.0) else 20.0
            revision++
        }
        return null
    }

    // Da chiamare periodicamente: completa la misura NIBP e fa scadere il silenziamento.
    fun tickAtTime(now: Double) {
        synchronized(this) {
            if (!running && pausedAt > 0) { pausedTickAt(now); return }   // ANDROID (analisi 21/11, 1.1): in pausa il paziente è fermo
            cprAccrue()                                                   // ANDROID (funzioni 21/11)
            defibTickAt(now)
            if (faultTickAt(now)) revision++                              // ANDROID (guasti 22/11): batteria da sostituire -> si spegne
            hemoCheck()                                                   // ANDROID (difetti clinici 22/11, EM-07 / CC-05)
            if (apneaCo2On()) apneaCo2Now(now) else apCo2 = -1.0          // ANDROID (EM-02)
            perfAdvance(now)                                              // ANDROID (perfusione 22/11)
            if (spo2On && spo2Stage < 3) {          // acquisizione SpO2: ricerca -> onda -> valore
                val st = if (now - spo2ConnAt >= spo2AcqLen) 3 else (if (now - spo2ConnAt >= spo2AcqLen / 2.0) 2 else 1)
                if (st != spo2Stage) { spo2Stage = st; revision++ }
            }
            advanceChainAt(now)
            onsetAdvance(now)                                             // ANDROID (transizioni ritmo 22/11)
            vfAdvance(now)                                                // ANDROID (ampiezza FV 22/11)
            vfPublish(now)
            apneaPhysiologyAt(now)
            tickFunzioni(now)                                             // ANDROID (funzioni mancanti 22/11)
            // NIBP automatica: quando scade l'intervallo parte una misura (se non ce n'è già una)
            if (nibpInterval > 0 && nibpNext > 0 && now >= nibpNext) {
                if (nibpStatus != SLNibpMeasuring) {
                    nibpStatus = SLNibpMeasuring; nibpError = null; nibpMotionHit = (motion == 2); nibpStartedAt = now; nibpCuffOK = nibpOn
                    nibpTarget = Math.max(140.0, sys.toDouble() + 30.0)
                    val pulse = effectivePulse() && sys > 0
                    nibpEnd = if (pulse) Math.max(20.0, dia.toDouble() - 12.0) else 20.0
                }
                nibpNext = now + nibpInterval * 60.0
                revision++
            }
            if (ramps.isNotEmpty()) advanceRampsAt(now)
            for (sc in ArrayList(scheduled)) {
                if (now >= sc.at) {
                    rampStartOverride = sc.at                             // la rampa parte all'istante programmato
                    rampParam(sc.key, sc.to, sc.dur)
                    rampStartOverride = 0.0
                    scheduled.remove(sc)
                    advanceRampsAt(now)                                   // applica subito il tratto già trascorso
                }
            }
            co2Advance(now)                                               // ANDROID (EtCO2 RCP 22/11)
            fatigueAdvance(now)                                           // ANDROID (25/11): anche con il polso (co2Advance esce prima)
            if (motion != 0 && motionUntil > 0 && now >= motionUntil) { motion = 0; motionUntil = 0.0; revision++ }   // fine del movimento a tempo
            if (motion != 0 && !effectivePulse()) { motion = 0; motionUntil = 0.0; revision++ }                       // senza polso il paziente non si muove
            if (touchUntil > 0 && now >= touchUntil) { touchUntil = 0.0; revision++ }
            if (nibpStatus == SLNibpMeasuring && motion == 2) nibpMotionHit = true
            if (nibpStatus == SLNibpMeasuring) {
                val s = SLSnapshot()
                s.nibpStatus = nibpStatus; s.nibpStartedAt = nibpStartedAt
                s.nibpTarget = nibpTarget; s.nibpEnd = nibpEnd; s.nibpCuffOK = nibpCuffOK
                if (now - nibpStartedAt >= SLNibpTotalDuration(s)) {
                    if (!nibpCuffOK) {
                        nibpStatus = SLNibpError; nibpError = "cuff"; nibpLastSys = -1; nibpLastDia = -1; nibpMeasuredAt = null   // perdita: il valore precedente non è più valido
                    } else if (techAlarms.contains("nibp_cuff_error")) {
                        nibpStatus = SLNibpError; nibpError = "cuff"           // ANDROID (21/11): errore del bracciale simulato
                    } else if (techAlarms.contains("nibp_timeout")) {
                        nibpStatus = SLNibpError; nibpError = "timeout"        // ANDROID (IN-18): tempo di misura superato
                    } else if (nibpMotionHit) {
                        nibpStatus = SLNibpError; nibpError = "motion"      // artefatto da movimento: misura non riuscita
                    } else if (effectivePulse() && sys > 0) {
                        // l'oscillometria non coincide mai esattamente con la PA invasiva: scarto di pochi mmHg
                        val js = random.nextInt(9) - 4
                        val jd = random.nextInt(7) - 3
                        nibpLastSys = Math.max(0, sys + js); nibpLastDia = Math.max(0, Math.min(nibpLastSys - 5, dia + jd))
                        nibpStatus = SLNibpComplete
                        nibpMeasuredAt = Date(wallNowMs())                 // ANDROID (ora 22/11): ora sincronizzata
                    } else {
                        // Senza polso il bracciale non rileva oscillazioni
                        nibpStatus = SLNibpError; nibpError = "no_pulse"
                    }
                    revision++
                }
            }
            if (muted && mutedUntil > 0 && now >= mutedUntil) {
                muted = false; mutedUntil = 0.0; revision++
            }
            if (cmPending != null) { cprMetricsCheck(); if (cmPending == null) revision++ }   // ANDROID (funzioni 21/11)
        }
    }

    // ANDROID (analisi 21/11, 1.1): tick durante la pausa: avanza solo ciò che non è tempo clinico (silenziamento degli
    // allarmi, tocco dell'operatore) e le regole di coerenza che non dipendono dal tempo
    private fun pausedTickAt(now: Double) {
        if (motion != 0 && !effectivePulse()) { motion = 0; motionUntil = 0.0; revision++ }   // senza polso il paziente non si muove
        if (touchUntil > 0 && now >= touchUntil) { touchUntil = 0.0; revision++ }
        if (d3LeadsOffCheck()) revision++
        if (muted && mutedUntil > 0 && now >= mutedUntil) { muted = false; mutedUntil = 0.0; revision++ }
        if (autoText != null && clock() >= autoUntil) { autoText = null; autoUndo = null; autoUndoDecision = null; revision++ }   // ANDROID (21/11)
    }

    // Rappresentazione JSON-serializzabile per /api/v1/state
    fun stateDictionary(): Map<String, Any?> {
        val s = snapshot()
        val defib = synchronized(this) { defibDictionary(s.defib) }
        // ANDROID (funzioni 21/11): i sensori come sono davvero collegati (lo snapshot li toglie durante gli allarmi tecnici simulati)
        val sensors = synchronized(this) { linkedMapOf<String, Any?>(
                SLSensorECG to ecgOn, SLSensorSpO2 to spo2On, SLSensorNIBP to nibpOn, SLSensorEtCO2 to etco2On,
                SLSensorART to artOn, SLSensorTemp to tempOn, SLSensorPads to padsOn, SLSensorOxi to oxiOn,
                SLSensorCVP to cvpOn, SLSensorTemp2 to temp2On) }   // ANDROID (funzioni mancanti 22/11, IN-13)
        return linkedMapOf(
            "device_id" to deviceId,
            "device_type" to "multiparameter",
            "product" to "SimulaFranz_Monitor",
            "version" to appVersion,
            "revision" to s.revision.toLong(),
            "monitor_on" to s.devicePowered,
            "monitor_on_by" to powerBy,                     // ANDROID: chi l'ha acceso (registro della sessione)
            "rhythm" to s.rhythm,
            "heart_rate_bpm" to s.heartRate.toLong(),
            "displayed_heart_rate_bpm" to (if (s.displayedHeartRate >= 0) s.displayedHeartRate.toLong() else null),
            "pulse_present" to s.pulsePresent,
            "spo2_percent" to s.spo2.toLong(),
            "spo2_signal_valid" to s.spo2SignalSetting,
            "spo2_cuff_occluded" to s.spo2CuffOccluded, "art_cuff_occluded" to s.artCuffOccluded,
            "sensor_sites" to s.sensorSites,
            "probe_connected" to sensors[SLSensorSpO2],
            "spo2_displayed" to (if (s.spo2Displayable) s.spo2.toLong() else null),
            "spo2_low_perfusion" to s.lowPerfusion,
            "perfusion_index" to s.perfusionIndex, "spo2_weak_signal" to s.spo2WeakSignal,   // ANDROID (perfusione 22/11)
            // valori VERI impostati dall'istruttore; in ventilazione manuale il monitor mostra quelli misurati (sotto)
            "respiratory_rate_rpm" to SLLround(s.respRateSet).toLong(), "respiratory_rate_measured" to s.respRate.toLong(),
            "etco2_mmhg" to s.etco2Set.toLong(),
            "etco2_displayed" to (if (s.etco2Connected && s.respRate > 0) s.etco2.toLong() else null),
            "systolic_mmhg" to s.systolic.toLong(),
            "diastolic_mmhg" to s.diastolic.toLong(),
            "nibp_systolic_mmhg" to s.systolic.toLong(),
            "nibp_diastolic_mmhg" to s.diastolic.toLong(),
            "art_displayed_systolic_mmhg" to (if (s.artDisplayedSystolic >= 0) s.artDisplayedSystolic.toLong() else null),
            "art_displayed_diastolic_mmhg" to (if (s.artDisplayedDiastolic >= 0) s.artDisplayedDiastolic.toLong() else null),
            "nibp_status" to SLNibpStatusName(s.nibpStatus),
            "nibp_error" to s.nibpError,
            "nibp_last_systolic_mmhg" to (if (s.nibpLastSystolic >= 0) s.nibpLastSystolic.toLong() else null),
            "nibp_last_diastolic_mmhg" to (if (s.nibpLastDiastolic >= 0) s.nibpLastDiastolic.toLong() else null),
            "sensors" to sensors,
            "alarms_muted" to s.alarmsMuted,
            "alarms_muted_remaining_s" to s.alarmsMutedRemaining.toLong(),
            "qrs_beep_enabled" to s.qrsBeepEnabled,
            "volume_percent" to s.volume.toLong(),
            "tablet_volume_percent" to s.tabletVolume.toLong(),      // -1 finché il monitor non l'ha letto
            "simulation_running" to s.simulationRunning,
            "scenario_time_s" to Math.floor(s.scenarioTime).toLong(),
            "supported_rhythms" to supportedRhythms(),
            "supported_sensors" to supportedSensors(),
            "alarm_limits" to s.alarmLimits.mapValues { it.value.toLong() },
            "auto_physiology" to s.autoPhysiology,
            "cpr_active" to s.cprActive, "cpr_rate_quality" to s.cprRateQuality, "cpr_depth_quality" to s.cprDepthQuality, "ventilation_manual" to s.ventilationManual, "breaths_last_min" to s.breathTimes.size.toLong(),
            "etco2_cpr_mode" to s.etco2CprMode,   // ANDROID (EtCO2 RCP 22/11): auto | instructor | null
            // ANDROID (RCP dettagliata 22/11)
            "cpr_recoil_quality" to s.cprRecoilQuality, "cpr_hands_quality" to s.cprHandsQuality, "cpr_rate_cpm" to s.cprCompressionRate.toLong(),
            "cpr_fatigue" to Math.round(s.cprFatigue * 100) / 100.0,
            "pulse_rate_bpm" to s.pulseRate.toLong(),
            "alarm_enabled" to s.alarmEnabled,
            "nibp_interval_min" to s.nibpIntervalMin.toLong(),
            "nibp_next_in_s" to (if (s.nibpIntervalMin > 0) Math.ceil(Math.max(0.0, s.nibpNextAt - clock())).toLong() else null),
            "transitions" to s.transitions,
            "chain" to s.chain,
            "spo2_delay_s" to s.spo2DelaySec.toLong(),
            "spo2_acquiring" to s.spo2Acquiring,
            "spo2_low_perfusion_flag" to s.spo2LowPerfusionFlag,
            "patient_motion" to listOf("off", "light", "strong")[Math.max(0, Math.min(2, s.patientMotionSet))], "patient_motion_remaining_s" to s.patientMotionRemaining.toLong(), "operator_touch" to s.operatorTouch,
            "temperature_c" to s.temperatureTenths / 10.0,
            "temperature_displayed" to (if (s.tempConnected) s.temperatureTenths / 10.0 else null),
            "crt_s" to s.crtTenths / 10.0,
            "crt_enabled" to s.crtEnabled, "crt_assess_seq" to s.crtAssessSeq.toLong(),
            "layout" to s.layout,
            "layouts" to layoutCatalog(),
            "oximeter" to linkedMapOf<String, Any?>(
                "power" to s.oxiPower, "online" to s.oxiOnline, "sensor_connected" to s.oxiConnected,
                "sensor_since_s" to (if (s.oxiConnected) Math.floor(s.oxiSensorSince) else null), "site" to s.sensorSites[SLSensorOxi],
                "cuff_occluded" to s.oxiCuffOccluded, "alarm_limits" to s.oxiLimits.mapValues { it.value.toLong() }, "volume" to s.oxiVolume.toLong(),
                "muted_remaining_s" to s.oxiMutedRemaining.toLong()),
            "defib" to defib,
            // ANDROID (funzioni 21/11)
            "cpr_metrics" to s.cprMetrics,
            "auto_action" to s.autoAction,
            "technical_alarms" to s.technicalAlarms.map { linkedMapOf("kind" to it, "text" to SLTechnicalAlarmText(it)) },
            "vehicle_motion" to s.vehicleMotion,
            "scenario_ended" to s.scenarioEnded,                          // ANDROID (fine scenario 22/11)
            // ANDROID (avvio scenario 22/11): fase dello scenario: ready (PRONTO, prima di start_scenario) | running | paused | ended
            "scenario_started" to s.scenarioStarted,
            "scenario_phase" to (if (s.scenarioEnded) "ended" else if (!s.scenarioStarted) "ready" else if (!s.simulationRunning) "paused" else "running"),
            // ANDROID (guasti 22/11): malfunzionamenti simulati del dispositivo
            "device_faults" to s.deviceFaults,
            "device_readiness" to s.deviceReadiness,
            "device_self_test" to s.deviceSelfTest,
            "device_fault_beep" to s.deviceFaultBeep,
            "fault_beep_interval_s" to s.faultBeepIntervalS.toLong(),     // 0 = adesso nessun bip (acceso, nessun guasto, layout senza bip)
            "fault_beep_double" to s.faultBeepDouble,
            "battery_shocks_left" to (if (s.batteryShocksLeft >= 0) s.batteryShocksLeft.toLong() else null),
            "device_faults_supported" to SLDeviceFaultKinds.filter { SLDeviceFaultApplies(s.layout, it) },
            "wall_clock_synced" to s.wallClockSynced,                     // ANDROID (ora 22/11): ora mandata dal controller (set_wall_clock)
            // ANDROID (transizioni ritmo 22/11): preludio del cambio di ritmo in corso (null = nessuno) e ultimo arrivato
            "rhythm_onset" to s.rhythmOnset?.let { p -> val ph = p.phaseAt(s.rhythmOnsetOffset)
                linkedMapOf<String, Any?>("to" to p.to, "from" to p.from, "variant" to p.variant, "text" to p.text,
                    "phase" to ph.name, "phase_label" to ph.label, "elapsed_s" to Math.round(s.rhythmOnsetOffset * 10) / 10.0,
                    "remaining_s" to Math.round(Math.max(0.0, p.duration - s.rhythmOnsetOffset) * 10) / 10.0,
                    "duration_s" to Math.round(p.duration * 10) / 10.0) },
            "rhythm_onset_last" to synchronized(this) { onsetLast },
            // ANDROID (12 derivazioni 22/11): morfologia dell'ECG, blocco AV attivo adesso, frequenza atriale, ultima carta 12D
            "ecg_morphology" to s.ecgMorph.toMap(),
            "av_block_active" to s.avBlockActive,
            "atrial_rate_bpm" to (if (s.avBlockActive || s.ecgMorph.avBlock == "first") s.atrialRate.toLong() else null),
            "ecg12_last" to synchronized(this) { ecg12Last },
            // ANDROID (ampiezza FV 22/11): solo con la FV (null altrimenti)
            "vf_amplitude_mv" to (if (s.rhythm == SLRhythmVentricularFib) Math.round(s.vfAmplitudeMv * 100) / 100.0 else null),
            "vf_amplitude_class" to s.vfAmplitudeClass,
            "vf_amplitude_mode" to (if (s.rhythm == SLRhythmVentricularFib) s.vfAmplitudeMode else null),
            // ANDROID (numerici realistici 22/11): i numeri come li mostra adesso il monitor (null = «---» a schermo)
            "monitor_numerics" to linkedMapOf<String, Any?>(
                "heart_rate_bpm" to (if (s.monDisplayedHeartRate >= 0) s.monDisplayedHeartRate.toLong() else null),
                "pulse_rate_bpm" to (if (s.spo2Displayable) s.monPulseRate.toLong() else null),
                "spo2_percent" to (if (s.spo2Displayable) s.monSpo2.toLong() else null),
                "etco2_mmhg" to (if (s.etco2Connected && s.respRate > 0) s.monEtco2.toLong() else null),
                "respiratory_rate_rpm" to (if (s.etco2Connected || s.ecgConnected) s.monRespRate.toLong() else null),
                "art_systolic_mmhg" to (if (s.monArtSystolic >= 0 && s.artConnected) s.monArtSystolic.toLong() else null),
                "art_diastolic_mmhg" to (if (s.monArtDiastolic >= 0 && s.artConnected) s.monArtDiastolic.toLong() else null),
                "temperature_c" to (if (s.tempConnected) s.monTemperatureTenths / 10.0 else null),
                // ANDROID (funzioni mancanti 22/11)
                "art_map_mmhg" to (if (s.artConnected && s.artDisplayedMap >= 0) s.artDisplayedMap.toLong() else null),
                "cvp_mmhg" to (if (s.monCvp >= 0) s.monCvp.toLong() else null),
                "temperature2_c" to (if (s.temp2Connected) s.monTemperature2Tenths / 10.0 else null),
                "ppv_percent" to (if (s.ppvShown >= 0) s.ppvShown.toLong() else null)),
            // ANDROID (funzioni mancanti 22/11): linea arteriosa (IN-03), PVC/T2 (IN-13), ventilazione (CC-04/AE-12/OP-05),
            // ritardi d'allarme (IN-08), elettrodi (IN-10), tecnici risolvibili (IN-12), esami (IN-13)
            "art_line" to linkedMapOf<String, Any?>("damping" to SLArtLineMath.dampingName(s.artDamping), "level_cm" to s.artLevelCm.toLong(),
                "offset_mmhg" to Math.round(s.artOffset * 10) / 10.0, "occluded" to s.artOccluded, "zero_required" to s.artZeroRequired,
                "needs_zero" to s.artNeedsZero, "cvp_needs_zero" to s.cvpNeedsZero, "zeroing" to s.artZeroing,
                "zeroing_remaining_s" to (if (s.artZeroing) Math.ceil(s.artZeroingUntil - s.clockNow).toLong() else null),
                "zeroed_at" to (if (s.artZeroedAtMs > 0) synchronized(logTimeFormat) { logTimeFormat.format(Date(s.artZeroedAtMs)) } else null),
                "flushing" to s.artFlushing),
            "cvp_mmhg" to s.cvp.toLong(), "cvp_waveform" to s.cvpWave,
            "temperature2_c" to s.temperature2Tenths / 10.0,
            "ventilation" to linkedMapOf<String, Any?>("mode" to s.ventMode, "rate_rpm" to s.ventRate.toLong(), "tidal_ml" to s.ventTidal.toLong(),
                "disconnected" to s.ventDisconnected, "advanced_airway" to s.advancedAirway, "arrest_rate_rpm" to synchronized(this) { ventArrestRate }.toLong(),
                "etco2_cpr_display" to synchronized(this) { co2Display },
                "ppv_percent" to Math.round(s.ppv * 100).toLong(), "ppv_mode" to (if (s.ppvAuto) "auto" else "instructor")),
            "alarm_delays" to s.alarmDelays.mapValues { it.value.toLong() },
            "ecg_electrode_off" to s.ecgElectrodeOff, "ecg_noise" to s.ecgNoise.toLong(), "limb_reversal" to s.limbReversal,
            "tech_resolvable" to s.techResolvable,
            "pads_contact" to synchronized(this) { if (padsPoor) "poor" else if (padsMarginal) "marginal" else "good" },   // ANDROID (AE-08)
            "labs" to synchronized(this) { LinkedHashMap(labs) },
            "trend_interval_s" to trend.intervalS.toLong(),
        )
    }
}
