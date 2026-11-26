// Portato da src/CtlModel.m (controller iPad)
// similFranz Controller — stato dell'app: collegamento, PIN, stato del paziente letto dal monitor, comandi, scenari.
// ANDROID: differenze segnate con "ANDROID:". Aggiornamento dell'app dal monitor: non ancora (come sul monitor Android).
package it.simulafranz.controller

import android.content.Context
import it.simulafranz.core.SFLog
import it.simulafranz.core.SFPrefs
import it.simulafranz.uk.CACurrentMediaTime
import it.simulafranz.uk.NSTimer
import it.simulafranz.uk.Uk
import java.net.URLEncoder
import java.security.SecureRandom
import java.util.Locale

typealias CtlStage = Int
const val CtlStageSearching = 0   // nessun monitor collegato: elenco dei monitor vicini
const val CtlStageConnecting = 1  // invito in corso
const val CtlStageNeedPin = 2     // collegato, manca il PIN (o è sbagliato)
const val CtlStageReady = 3       // collegato e autorizzato: si comanda
const val CtlStagePairing = 4     // ANDROID: collegato, si aspetta il tocco su «Associa» sul monitor (associazione rapida)

private const val kPinKey = "sf_pin"
private const val kMonKey = "sf_monitor"
private const val kClientKey = "sf_client"
// ANDROID (audit 21/11): identità del monitor abbinato = codice del tablet (non il nome) e un PIN per ciascun monitor
private const val kMonCodeKey = "sf_monitor_code"
private const val kPinsKey = "sf_pins"
/** per quanto la regia resta a schermo (con la fascia «Ricollegamento…») dopo una caduta, prima della scelta del monitor */
const val kReconnectGrace = 60.0
/** un comando di regia non arrivato si rimanda per al massimo 15 s (dopo, farlo partire sarebbe fuori tempo: lo si dice) */
private const val kCmdMaxAge = 15.0
/** una voce del registro si tiene in coda fino a 3 minuti: conserva il tempo del tocco */
private const val kLogMaxAge = 180.0

// ANDROID (debriefing scollegato 22/11): istantanea di fine (file, sopravvive alla chiusura dell'app) e azzeramento in sospeso
private const val kEndSnapFile = "fine_scenario.json"
private const val kResetPendingKey = "sf_reset_pending"

/** ANDROID (audit 21/11): comando in attesa di conferma dal monitor (coda dei comandi durante una caduta del collegamento) */
class CtlQueued(val path: String, var body: Map<String, Any?>, var done: ((Boolean, String?, Map<String, Any?>?) -> Unit)?,
                val label: String, val id: String, val key: String?, val isLog: Boolean, val tapClock: Int) {
    val at = CACurrentMediaTime()
    var sending = false
    var sentOnce = false            // ANDROID (audit 26/11, RETE-R07): partito almeno una volta senza risposta (esito incerto)
}
// ANDROID (audit 26/11, RETE-R07): un comando partito ma senza risposta che scade non è subito «NON ARRIVATO»: al ricollegamento
// si chiede al monitor se l'ha eseguito (GET /api/v1/command?id=…); si dichiara perso solo se non c'è, o se dopo 2 minuti non si
// è potuto chiedere. Non si rimanda mai (eseguirlo tardi sarebbe peggio).
private const val kUncertainMaxAge = 120.0

class CtlModel(ctx: Context, val prefs: SFPrefs) {
    val link: SFLink
    var stage: CtlStage = CtlStageSearching; private set
    var pinError: String? = null; private set              // "PIN non valido", "troppi tentativi…"
    var state: Map<String, Any?>? = null; private set      // ultimo stato del paziente (API /state), null se mai letto
    var controller: Map<String, Any?>? = null; private set // {active, name, mine, transport}
    var scenario: Map<String, Any?>? = null; private set   // conduzione in corso {active, phase, ...}
    var scenarios: List<Map<String, Any?>>? = null; private set
    var lastMessage: String? = null; private set           // esito dell'ultimo comando (errore leggibile) o null
    var lastMessageAt = 0.0; private set                   // ANDROID (audit 21/11): un errore vecchio non copre più le conferme
    private fun setMessage(t: String?) { lastMessage = t; lastMessageAt = CACurrentMediaTime() }
    // ANDROID (audit 21/11): ricollegamento dopo una caduta breve — la regia resta a schermo con la fascia «Ricollegamento…»
    var reconnectSince = 0.0; private set
    // ANDROID (debriefing scollegato 22/11): dopo FINE SCENARIO l'istruttore porta il tablet in sala debriefing e il Wi-Fi Direct
    // cade per 30–60 minuti. Finché c'è l'istantanea di fine o un azzeramento in sospeso il ricollegamento non «scade»: la regia
    // resta (velata, con una riga discreta) e non torna la schermata di scelta del monitor.
    val reconnecting: Boolean get() = reconnectSince > 0 && (awayOK || CACurrentMediaTime() - reconnectSince < kReconnectGrace)
    val awayOK: Boolean get() = endSnap != null || resetPending != null
    /** comandi e voci del registro non ancora confermati dal monitor */
    val queue = ArrayList<CtlQueued>()
    private val uncertain = ArrayList<CtlQueued>()      // ANDROID (audit 26/11, RETE-R07): scaduti con esito incerto, da verificare
    /** comandi scaduti senza arrivare (li mostra la regia in rosso finché l'istruttore non li legge) */
    val lost = ArrayList<String>()
    fun clearLost() { lost.clear(); notifyUpdate() }
    var lostAt = 0.0; private set
    private var lastPollAt = 0.0; private var lastCmdAt = 0.0
    private var userPicked = false                         // l'associazione rapida parte solo da un tocco dell'istruttore
    var onUpdate: (() -> Unit)? = null
    var observing: (() -> Boolean)? = null; var onBlocked: (() -> Unit)? = null      // ANDROID (sola osservazione senza velo 22/11)                     // main thread: qualcosa è cambiato

    // Registro della sessione (tenuto sul monitor, condiviso con la web app): voci e riepilogo per il debriefing
    var logSummary: Map<String, Any?>? = null; private set
    var logAvailable = false; private set                  // false con un monitor vecchio, senza registro
    var logSessions: List<Map<String, Any?>>? = null; private set    // {id, title, w, entries, duration_s, current}
    var logSession = 0; private set

    private var pin: String?
    private var wanted: String?                            // nome del monitor abbinato (solo da mostrare)
    private var wantedCode: String?                        // ANDROID (audit 21/11): codice del monitor abbinato (identità)
    private val clientId: String
    // ANDROID: nome di serie "similFranz Controller <codice>" come i monitor; rinominabile dall'app (vale subito)
    private val clientName: String get() = CtlIdentity.controllerName()
    private var timer: NSTimer? = null
    private var polling = false
    private var cmdSeq = 0
    private var authorized = false
    private var logEntries: ArrayList<Map<String, Any?>>? = null
    private var logNext = 0
    private var logLoading = false
    private var silent = 0
    private var idleSince = 0.0
    // ANDROID: associazione rapida
    private var pairing = false; private var pairSince = 0.0; private var pairPolling = false
    var pairRefused = false; private set

    init {
        loadAway()                                         // ANDROID (debriefing scollegato 22/11)
        pin = prefs.string(kPinKey); wanted = prefs.string(kMonKey); wantedCode = prefs.string(kMonCodeKey)
        wantedCode?.let { c -> pinFor(c)?.let { pin = it } }
        var cid = prefs.string(kClientKey)
        if (cid.isNullOrEmpty()) {
            val r = SecureRandom()
            cid = String.format(Locale.US, "android-%08x%08x", r.nextInt(), r.nextInt())   // ANDROID: "android-" invece di "ipad-"
            prefs.setString(kClientKey, cid)
        }
        clientId = cid
        link = SFLink(ctx, clientName, prefs)
        link.onChange = { linkChanged() }
    }

    private fun notifyUpdate() { onUpdate?.invoke() }
    // ANDROID (funzioni mancanti 22/11, IN-14): trend del monitor a passo 60 s, letto ogni minuto a scenario avviato: serve al piano
    // di debriefing, alla Storia Sim e al PDF anche se a fine scenario il collegamento cade
    @Volatile var trendCache: List<Map<String, Any?>>? = null
    private var trendReadAt = -100.0
    private fun trendTick() {
        val st = state ?: return
        if (st["trend_interval_s"] == null || st["scenario_started"] != true) return
        if (CACurrentMediaTime() - trendReadAt < 60) return
        trendReadAt = CACurrentMediaTime(); loadTrend60(null)
    }
    @Suppress("UNCHECKED_CAST")
    fun loadTrend60(done: ((List<Map<String, Any?>>?) -> Unit)?) {
        api("GET", "/api/v1/trend?since=0&step=60", null) { status, b ->
            val rows = if (status == 200) (b?.get("samples") as? List<Map<String, Any?>>) else null
            if (rows != null) { trendCache = rows; endSnap?.let { s -> if (s["end_ok"] != true || s["trend"] == null || rows.size >= ((s["trend"] as? List<*>)?.size ?: 0)) { s["trend"] = rows; saveEndSnap() } } }
            done?.invoke(rows)
        }
    }

    // ANDROID (funzioni 21/11): ora del monitor. Il cuore non ha (ancora) un comando per ricevere l'ora del controller
    // (G8, vedi BugFixController «Funzioni nuove 21/11»): i tablet senza SIM possono essere fermi al 3 gennaio. Si stima lo
    // scarto fra l'orologio del controller e quello del monitor dalle voci del registro appena nate (campo "w", ora del monitor):
    // scarto = ora del controller all'arrivo − w. Il ritardo di arrivo lo fa solo crescere, quindi si tiene il minimo.
    // ANDROID (guasti 22/11): ora il controller MANDA la sua ora al monitor (set_wall_clock, vedi sendWallClock). Se il monitor
    // dice wall_clock_synced = true le sue ore sono già giuste: scarto 0. La stima qui sotto resta come ripiego per i monitor vecchi.
    private var clockEstimate: Double? = null
    val wallClockSynced: Boolean get() = state?.get("wall_clock_synced") == true
    // con l'orologio del controller sbagliato (anno < 2025) la stima porterebbe le ore del monitor su un'ora sbagliata: 0
    val clockOffset: Double? get() = if (wallClockSynced || controllerClockWrong) 0.0 else clockEstimate     // secondi da sommare a un'ora del monitor; null = non ancora stimato
    private fun noteMonitorClock(w: Any?) {
        if (wallClockSynced) return
        val wv = (w as? Number)?.toDouble() ?: return
        val off = System.currentTimeMillis() / 1000.0 - wv
        val c = clockEstimate
        if (c == null || off < c) clockEstimate = off
    }
    // ANDROID (guasti 22/11): ora del controller al monitor ({"command":"set_wall_clock","value":<epoch ms>}) a ogni collegamento
    // e ricollegamento e poi ogni 10 minuti. Fuori dalla coda dei comandi: niente messaggi, niente «NON ARRIVATO». Un monitor
    // che non conosce il comando («unknown command») si lascia stare fino al collegamento successivo. Con l'orologio del
    // controller palesemente sbagliato (anno < 2025) non si manda nulla: si rovinerebbe un'ora del monitor magari giusta.
    private var wallSentAt = -1e9; private var wallUnsupported = false
    /** l'orologio di questo tablet sembra sbagliato (anno prima del 2025) */
    val controllerClockWrong: Boolean get() = java.util.Calendar.getInstance().get(java.util.Calendar.YEAR) < 2025
    /** force = solo verifiche da Mac (deep link wallsend): manda anche con l'orologio sbagliato */
    fun sendWallClock(force: Boolean = false) {
        if (!force && (wallUnsupported || controllerClockWrong)) return
        if (link.state != SFLinkConnected || !authorized) return
        wallSentAt = CACurrentMediaTime()
        val h = LinkedHashMap(headers())
        link.request("POST", "/api/v1/command", h, mapOf("command" to "set_wall_clock", "value" to System.currentTimeMillis())) { status, b ->
            val e = b?.get("error") as? String ?: ""
            if (status != 200 && e.contains("unknown command")) { wallUnsupported = true; SFLog.log("ora: il monitor non conosce set_wall_clock (versione vecchia)") }
            else if (status != 200) wallSentAt = CACurrentMediaTime() - 600 + 30      // non arrivato: si riprova fra 30 s
            else SFLog.log("ora del controller mandata al monitor")
        }
    }
    /** ora vera (epoch, s) di un'ora scritta dal monitor */
    fun wallOf(w: Any?): Double? { val wv = (w as? Number)?.toDouble() ?: return null; return wv + (clockOffset ?: 0.0) }
    // ANDROID (funzioni 21/11): «FINE SCENARIO» toccato sul controller: tempo di scenario, ora del CONTROLLER, sessione del registro
    var endMark: Map<String, Any?>? = null
    fun markEnd() {
        endMark = linkedMapOf("t" to ((state?.get("scenario_time_s") as? Number)?.toInt() ?: 0), "w" to System.currentTimeMillis() / 1000.0, "session" to logSession)
    }
    // ANDROID (funzioni 21/11): solo per le verifiche da Mac (deep link fake?v=<JSON>): valori sovrapposti allo stato del monitor,
    // per vedere a schermo stati che il monitor sul tavolo non produce (banner «Annulla», metriche RCP…). Mai usato altrove.
    private var debugOverlay: Map<String, Any?>? = null
    @Suppress("UNCHECKED_CAST")
    private fun overlaid(st: Map<String, Any?>): Map<String, Any?> {
        val o = debugOverlay ?: return st
        val out = LinkedHashMap(st)
        for ((k, v) in o) { val cur = out[k]; out[k] = if (v is Map<*, *> && cur is Map<*, *>) LinkedHashMap(cur as Map<String, Any?>).apply { putAll(v as Map<String, Any?>) } else v }
        return out
    }
    fun applyDebugOverlay(o: Map<String, Any?>?) { debugOverlay = o; state?.let { state = overlaid(it) }; notifyUpdate() }
    val log: List<Map<String, Any?>> get() = logEntries?.let { ArrayList(it) } ?: emptyList()
    // si può comandare: nessuno comanda, comandiamo noi, oppure chi comanda è fermo da più di un minuto (il primo comando passa)
    val mine: Boolean get() { val c = controller; return c == null || c["mine"] == true || c["active"] != true || c["protected"] == false }
    val monitorName: String? get() = link.connectedName ?: wanted

    // MARK: - PIN per monitor (ANDROID, audit 21/11)
    // Prima c'era un solo PIN per tutti i monitor: scegliendo un altro monitor partiva il PIN del primo (401 = tentativo
    // sbagliato contato dall'anti-forza-bruta, e il PIN giusto del primo veniva cancellato).
    @Suppress("UNCHECKED_CAST")
    private fun pins(): LinkedHashMap<String, Any?> = LinkedHashMap((prefs.json(kPinsKey) as? Map<String, Any?>) ?: emptyMap())
    private fun pinFor(code: String?): String? = if (code.isNullOrEmpty()) null else pins()[code] as? String
    private fun savePin(p: String?) {
        val c = link.targetCode ?: wantedCode
        prefs.setString(kPinKey, p)                        // l'ultimo PIN resta anche qui (versioni vecchie)
        if (c.isNullOrEmpty()) return
        val all = pins(); if (p.isNullOrEmpty()) all.remove(c) else all[c] = p
        prefs.setJson(kPinsKey, all)
    }
    /** il monitor che si sta per usare diventa quello abbinato: nome, codice e il SUO PIN */
    private fun choose(m: SFLinkMonitor) {
        // dati di una versione vecchia (nome senza codice): il PIN unico era di quel monitor
        val legacy = if (wantedCode.isNullOrEmpty() && m.name == wanted) prefs.string(kPinKey) else null
        wanted = m.name; prefs.setString(kMonKey, wanted)
        if (m.code.isNotEmpty()) { wantedCode = m.code; prefs.setString(kMonCodeKey, wantedCode) }
        pin = pinFor(m.code) ?: legacy
    }
    private fun isWanted(m: SFLinkMonitor): Boolean { val c = wantedCode; return if (!c.isNullOrEmpty()) m.code == c else (wanted != null && m.name == wanted) }

    fun start() {
        suspended = false                                  // ANDROID (fix analisi 22/11, BUG-05)
        link.start()
        if (timer == null) timer = NSTimer.scheduledTimerWithTimeInterval(0.5, true) { tick() }
        // ANDROID (audit 21/11): di ritorno dal secondo piano con un monitor già autorizzato: la regia resta a schermo
        // con «Ricollegamento…» invece della schermata di scelta
        if (resumeReconnect) { resumeReconnect = false; if (reconnectSince == 0.0) reconnectSince = CACurrentMediaTime() }
        linkChanged()
        // ANDROID (audit 21/11): monitor abbinato con il suo PIN: ci si collega subito con le credenziali ricordate
        // (prima si aspettavano 5 s che la ricerca lo trovasse)
        if (!pin.isNullOrEmpty()) reconnectWanted()
    }
    private var resumeReconnect = false
    // ANDROID (fix analisi 22/11, BUG-05): da sospeso la risposta al «release» non deve ri-autorizzare il controller in secondo piano
    private var suspended = false
    fun suspend() {
        suspended = true
        timer?.invalidate(); timer = null
        // app in background (tablet bloccato): lascia libero il comando, così nessuno resta bloccato
        val release = authorized && controller?.get("mine") == true
        if (release) control("release")
        resumeReconnect = authorized || reconnecting
        authorized = false; stage = CtlStageSearching; reconnectSince = 0.0
        if (release) Uk.after(0.4) { if (timer == null) link.stop() }
        else link.stop()
    }

    fun connect(m: SFLinkMonitor) {
        pairRefused = false
        userPicked = true                                  // ANDROID (audit 21/11): scelto a mano: si può chiedere «Associa»
        reconnectSince = 0.0; failQueue("scelto un altro monitor")
        choose(m)
        pinError = null
        link.connectTo(m)
    }

    fun forgetMonitor() {
        pairing = false; userPicked = false
        reconnectSince = 0.0; failQueue("monitor scollegato")
        wanted = null; prefs.setString(kMonKey, null); clockEstimate = null      // ANDROID (funzioni 21/11): un altro monitor, un altro orologio
        wantedCode = null; prefs.setString(kMonCodeKey, null)
        if (authorized && mine) control("release")
        authorized = false; state = null; controller = null; scenario = null
        Uk.after(0.3) { link.disconnect() }
    }

    /** ANDROID (audit 21/11): ricollegarsi al monitor abbinato, per CODICE: prima quello visibile (credenziali fresche), poi quello ricordato */
    private fun reconnectWanted() {
        if (!link.isRunning || link.state != SFLinkIdle) return
        val seen = link.monitors.firstOrNull { isWanted(it) }
        val m = seen ?: (if (!wantedCode.isNullOrEmpty()) link.knownCode(wantedCode) else link.known(wanted)) ?: return
        if (m.name != wanted && m.name.isNotEmpty()) { wanted = m.name; prefs.setString(kMonKey, wanted) }     // rinominato: si aggiorna solo il nome mostrato
        if (wantedCode.isNullOrEmpty() && m.code.isNotEmpty()) { wantedCode = m.code; prefs.setString(kMonCodeKey, wantedCode); pinFor(m.code) ?: pin?.let { savePin(it) } }
        link.connectTo(m)
    }

    private fun linkChanged() {
        when (link.state) {
            SFLinkIdle -> {
                // ANDROID (audit 21/11): caduta mentre si comandava: si entra nel «Ricollegamento…» (la regia resta visibile)
                if (authorized && reconnectSince == 0.0) { reconnectSince = CACurrentMediaTime(); SFLog.log("collegamento caduto: ricollegamento (la regia resta a schermo)") }
                authorized = false; stage = CtlStageSearching; pairing = false
                if (idleSince == 0.0) idleSince = CACurrentMediaTime()
                // il monitor abbinato è di nuovo visibile: ci si ricollega da soli
                // ANDROID (audit 21/11): durante il ricollegamento subito anche con le credenziali ricordate (prima si aspettavano 5 s)
                if (wanted != null || wantedCode != null) {
                    if (reconnecting) reconnectWanted()
                    else { val m = link.monitors.firstOrNull { isWanted(it) }; if (m != null && link.isRunning) link.connectTo(m) }
                }
            }
            SFLinkConnecting -> { if (authorized && reconnectSince == 0.0) reconnectSince = CACurrentMediaTime(); authorized = false; stage = CtlStageConnecting }
            SFLinkConnected -> {
                // ANDROID: senza PIN si chiede l'associazione rapida (tocco su «Associa» sul monitor) invece del tastierino
                // ANDROID (audit 21/11): ma solo se l'istruttore ha appena toccato il monitor nell'elenco: mai da un ricollegamento
                // automatico (la richiesta comparirebbe da sola sul monitor davanti ai partecipanti)
                if (!authorized) {
                    if (!pin.isNullOrEmpty()) { stage = CtlStageConnecting; poll() }
                    else if (!pairing && stage != CtlStageNeedPin) { if (userPicked) requestPair() else { reconnectSince = 0.0; stage = CtlStageNeedPin } }
                }
            }
        }
        if (link.state != SFLinkIdle) idleSince = 0.0
        notifyUpdate()
    }

    fun liveHeaders(): Map<String, String> = headers()         // ANDROID (sala 22/11): stesse credenziali per i flussi della «Sala»
    private fun headers(): Map<String, String> = mapOf(
        "X-SimulaFranz-Token" to (pin ?: ""), "X-SimulaFranz-Client" to clientId,
        "X-SimulaFranz-Client-Name" to (try { URLEncoder.encode(clientName, "UTF-8").replace("+", "%20") } catch (e: Exception) { "tablet" }),
        "X-SimulaFranz-Client-Kind" to "android")           // ANDROID: "android" invece di "ipad"

    fun submitPin(p: String) {
        pin = p; pinError = null; userPicked = true
        stage = CtlStageConnecting; notifyUpdate()
        poll()
    }

    // Risposta comune: aggiorna stato, controller, scenario; gestisce PIN errato e "monitor in uso"
    @Suppress("UNCHECKED_CAST")
    private fun absorb(status: Int, b: Map<String, Any?>?): Boolean {
        if (status == 0 || b == null) return false
        if (status == 401 || status == 429) {
            authorized = false; stage = CtlStageNeedPin; reconnectSince = 0.0
            pinError = if (status == 401) "PIN non valido" else "Troppi tentativi: riprovare fra mezzo minuto"
            if (status == 401) {
                pin = null; savePin(null)
                // ANDROID (audit 21/11): PIN cambiato sul monitor: NIENTE richiesta automatica di associazione (compariva da sola
                // sul monitor del partecipante). Si mostra il tastierino con «Chiedi sul monitor»: decide l'istruttore.
                failQueue("PIN non più valido")
            }
            return false
        }
        (b["controller"] as? Map<String, Any?>)?.let { controller = it }
        (b["scenario"] as? Map<String, Any?>)?.let { scenario = it }
        (b["scenarios"] as? List<Map<String, Any?>>)?.let { scenarios = it }
        (b["state"] as? Map<String, Any?>)?.let { state = overlaid(it) }      // ANDROID (funzioni 21/11): overlaid = solo verifiche
        trendTick()                                                             // ANDROID (funzioni mancanti 22/11)
        val ln = b["log_next"]
        if (ln is Number && ln !is Boolean) {            // il registro è cresciuto (o è stato svuotato): si rilegge
            val n = ln.toInt()
            logAvailable = true
            val sid = (b["log_session"] as? Number)?.toInt() ?: 0
            if (sid != logSession) { logSession = sid; logEntries = null; logNext = 0; loadLog() }      // sessione nuova: si riparte da lì
            else if (n != logNext && status == 200) loadLog()
        }
        if (status == 200 && !authorized && !suspended) {      // ANDROID (fix analisi 22/11, BUG-05): non da sospeso
            authorized = true; stage = CtlStageReady; pinError = null
            savePin(pin)
            val back = reconnectSince > 0
            reconnectSince = 0.0
            SFLog.log(String.format(Locale.US, "autorizzato dal monitor \"%s\"%s", link.connectedName, if (back) " (ricollegato)" else ""))
            refreshScenarios()
            wallUnsupported = false; Uk.async { sendWallClock() }      // ANDROID (guasti 22/11): ora del controller al monitor
            // ANDROID (audit 21/11): dopo un ricollegamento il registro si rilegge solo se è cambiata la sessione (absorb più sopra),
            // non da capo: le voci già lette restano
            if (!back) { logEntries = null; logNext = 0 }
            Uk.async { flushQueue() }                      // i comandi rimasti in coda partono adesso, in ordine
            // ANDROID (debriefing scollegato 22/11): al ricollegamento le simulazioni «da inviare al monitor» partono da sole
            Uk.async { storia.sync(true) }
        }
        if (status == 200 && queue.isNotEmpty()) Uk.async { flushQueue() }
        if (status == 200 && authorized) {
            updateEndSnap()                                // ANDROID (debriefing scollegato 22/11): istantanea di fine aggiornata
            if (resetPending != null) trySendReset()       // azzeramento in sospeso (solo con lo stato appena letto)
            if (uncertain.isNotEmpty()) verifyUncertain()  // ANDROID (audit 26/11, RETE-R07)
            if (storia.unsentCount > 0) storia.sync()      // al più ogni 30 s
        }
        return status == 200
    }

    // MARK: - Associazione rapida (ANDROID)

    /** chiede al monitor collegato di mostrare «Associa»; il PIN arriva da solo quando l'istruttore tocca */
    fun requestPair() {
        if (link.state != SFLinkConnected) return
        pairing = true; pairSince = CACurrentMediaTime(); pairRefused = false; pinError = null
        stage = CtlStagePairing; notifyUpdate()
        link.request("POST", "/api/v1/pair", headers(), mapOf("client" to clientId, "name" to clientName)) { status, b ->
            if (status == 404) { pairing = false; stage = CtlStageNeedPin; notifyUpdate() }     // monitor senza associazione rapida
            else if (status != 200) {
                pairing = false
                if (link.state == SFLinkConnected) {
                    stage = CtlStageNeedPin
                    // ANDROID (audit 21/11): il monitor ora rifiuta se un altro controller comanda (423) o se c'è già una richiesta (429)
                    pinError = when (status) {
                        423 -> String.format(Locale.US, "Il monitor è comandato da %s: usa il suo PIN (menu ☰ → Informazioni e collegamenti)", ((b?.get("controller") as? Map<*, *>)?.get("name") as? String) ?: "un altro controller")
                        429 -> "C'è già una richiesta sul monitor: attendi un minuto o usa il PIN"   // ANDROID (audit 26/11, RETE-R04): 60 s
                        403 -> "Associazione rapida solo con il collegamento diretto: usa il PIN"    // ANDROID (audit 26/11, RETE-R04)
                        else -> "Richiesta non arrivata al monitor"
                    }
                }
                notifyUpdate()
            }
        }
    }
    /** «Usa il PIN»: si lascia l'associazione rapida e si digita il PIN letto sul monitor */
    fun usePin() { pairing = false; stage = CtlStageNeedPin; notifyUpdate() }

    private fun pollPair() {
        if (pairPolling) return
        if (CACurrentMediaTime() - pairSince > 75) { pairing = false; stage = CtlStageNeedPin; pinError = "Nessuna risposta dal monitor: riprova o usa il PIN"; notifyUpdate(); return }
        pairPolling = true
        link.request("GET", "/api/v1/pair?client=" + clientId, headers(), null) { status, b ->
            pairPolling = false
            if (!pairing || status != 200 || b == null) return@request
            when (b["status"]) {
                "approved" -> { val p = b["pin"] as? String; pairing = false; if (!p.isNullOrEmpty()) { SFLog.log("associazione accettata sul monitor"); submitPin(p) } else { stage = CtlStageNeedPin; notifyUpdate() } }
                "denied" -> { pairing = false; pairRefused = true; stage = CtlStageNeedPin; pinError = "Il monitor ha rifiutato l'associazione"; notifyUpdate() }
            }
        }
    }

    private fun tick() {
        val now = CACurrentMediaTime()
        if (pairing && link.state == SFLinkConnected) pollPair()
        // ANDROID (audit 21/11, ANALISI_ESPERTO B7): lettura dello stato ogni secondo (prima ogni mezzo secondo, sempre),
        // ogni mezzo secondo solo per 3 s dopo un comando, quando si vuole vedere subito l'effetto
        val every = if (now - lastCmdAt < 3) 0.5 else 1.0
        if (link.state == SFLinkConnected && !pin.isNullOrEmpty() && (authorized || stage == CtlStageConnecting) && now - lastPollAt >= every - 0.05) poll()
        // ANDROID: la ricerca Wi-Fi Direct può tacere a lungo; il monitor abbinato è ricordato con la sua rete,
        // quindi dopo 5 s senza vederlo ci si prova a ricollegare lo stesso (il tablet entra nella rete quando c'è)
        if ((wanted != null || wantedCode != null) && link.state == SFLinkIdle && idleSince > 0 && now - idleSince > 5) {
            idleSince = now
            reconnectWanted()
        }
        // ANDROID (audit 21/11): comandi in coda troppo vecchi = non arrivati; finito il tempo del ricollegamento, la scelta del monitor
        expireQueue()
        if (authorized && now - wallSentAt >= 600) sendWallClock()      // ANDROID (guasti 22/11): ogni 10 minuti
        if (reconnectSince > 0 && !awayOK && now - reconnectSince >= kReconnectGrace) {      // ANDROID (debriefing scollegato 22/11)
            reconnectSince = 0.0; failQueue("il monitor non risponde da un minuto")
            SFLog.log("ricollegamento non riuscito in un minuto: torna la scelta del monitor")
            notifyUpdate()
        }
    }

    private fun poll() {
        if (polling) return
        lastPollAt = CACurrentMediaTime()
        polling = true; val seq = cmdSeq
        link.request("GET", "/api/v1/state", headers(), null) { status, b ->
            polling = false
            // un collegamento può morire in silenzio: dopo due letture senza risposta si chiude e ci si ricollega
            if (status == 0) { if (++silent >= 2) { silent = 0; link.resetAfterSilence() } }
            else silent = 0
            if (status == 503) { notifyUpdate(); return@request }    // ANDROID (fix analisi 22/11, BUG-11): occupato, si rilegge al prossimo giro
            if (seq != cmdSeq && status == 200) return@request      // nel frattempo è partito un comando: lettura superata
            absorb(status, b)
            notifyUpdate()
        }
    }

    private fun explain(status: Int, b: Map<String, Any?>?): String {
        if (status == 0) return "Il monitor non risponde"
        if (status == 423) return String.format(Locale.US, "Comando non eseguito: il monitor è comandato da %s", ((b?.get("controller") as? Map<*, *>)?.get("name") as? String) ?: "un altro controller")
        if (status == 503) return "Monitor occupato (troppe connessioni): comando non eseguito"      // ANDROID (fix analisi 22/11, BUG-11)
        val e = b?.get("error") as? String
        // ANDROID (fix analisi 22/11, BUG-09): il monitor rifiuta un secondo esito della stessa scarica
        if (e?.contains("no shock awaiting") == true) return "Esito della scarica già deciso: nessuna scarica in attesa"
        // ANDROID (fix analisi 22/11, terzo giro): esito mandato per una scarica che non è più quella in attesa
        if (e?.contains("is not the one awaiting") == true) return "Esito non applicato: nel frattempo è arrivata un'altra scarica (rispondi alla domanda nuova)"
        return if (!e.isNullOrEmpty()) "Non eseguito: $e" else "Comando non eseguito"
    }

    // MARK: - Comandi con coda (ANDROID, audit 21/11)
    // Prima un comando mandato a collegamento caduto falliva subito, con una riga rossa piccola; il registro diceva
    // «Registrato» anche se la voce non era arrivata. Ora ogni comando ha un identificativo (X-SimulaFranz-Cmd-Id: il monitor
    // non lo esegue due volte) e, se non arriva risposta, resta in coda e riparte da solo appena il monitor risponde, in ordine.
    // Dopo 15 s (3 minuti per le voci del registro, che conservano il tempo del tocco) è dichiarato NON ARRIVATO, in rosso.
    // I comandi «di stato» uguali (stessa sonda, stessa impostazione) in coda si sostituiscono: parte solo l'ultimo valore.
    private var cmdNum = 0
    private var flushing = false
    private fun post(path: String, body: Map<String, Any?>, done: ((Boolean, String?, Map<String, Any?>?) -> Unit)?) =
        enqueue(path, body, done, labelOf(body), coalesceKey(path, body), false, -1)

    @Suppress("UNCHECKED_CAST")
    private fun coalesceKey(path: String, body: Map<String, Any?>): String? {
        if (path != "/api/v1/command") return null
        val c = body["command"] as? String ?: return null
        if (!(c.startsWith("set_") || c == "defib_mode" || c == "mute_alarms" || c == "vehicle_motion")) return null
        val v = body["value"]
        if (v is Map<*, *>) return c + "|" + (v["sensor"] ?: v["kind"] ?: "") + "|" + v.keys.joinToString(",")      // ANDROID (funzioni 21/11): kind = allarme tecnico
        return c
    }
    private fun labelOf(body: Map<String, Any?>): String {
        val c = (body["command"] ?: body["action"]) as? String ?: "comando"
        val v = body["value"]
        return when (c) {
            "set_rhythm" -> "Ritmo " + CtlRhythmName(v as? String)
            "transition" -> "Transizione dei parametri"
            "set_cpr" -> if (v == true) "Avvio RCP" else "Stop RCP"
            "set_sensor" -> "Sonda " + ((v as? Map<*, *>)?.get("sensor") ?: "")
            "defib_outcome" -> "Esito della scarica"
            "set_simulation_running" -> if (v == true) "Riprendi" else "Pausa"
            "set_device_power" -> "Accensione del dispositivo"
            "next" -> "Fase successiva"; "goto" -> "Cambio di fase"; "start" -> "Avvio dello scenario"; "stop" -> "Fine dello scenario"
            else -> mapOf("set_volume" to "Volume", "set_tablet_volume" to "Volume del tablet monitor", "set_qrs_beep" to "Bip del battito", "set_layout" to "Layout del monitor", "defib_mode" to "Modo della terapia elettrica",
                          "set_defib_config" to "Impostazione della terapia elettrica", "set_alarm_limits" to "Limiti di allarme", "mute_alarms" to "Silenzia allarmi",
                          "ventilate" to "Ventilazione", "patient_touch" to "Tocco del paziente", "set_patient_motion" to "Movimenti del paziente",
                          "set_temperature" to "Temperatura", "set_crt" to "Refill", "reset_scenario" to "Azzeramento dello scenario", "reset_clock" to "Azzeramento del timer",
                          "set_pads_contact" to "Contatto delle placche",
                          // ANDROID (funzioni 21/11)
                          "set_pads_type" to "Placche pediatriche", "set_pads_cable" to "Cavo delle placche", "set_aed_child_mode" to "Modalità bambino",
                          "vehicle_motion" to "Ambulanza in movimento", "set_technical_alarm" to "Allarme tecnico", "undo_auto_action" to "Annulla l'avanzamento automatico",
                          "end_scenario" to "Fine scenario",
                          "set_ecg_morphology" to "Morfologia ECG", "set_vf_amplitude" to "Ampiezza della FV",      // ANDROID (regia morfologie ECG 22/11)
                          "set_device_fault" to "Malfunzionamento del dispositivo", "clear_device_faults" to "Dispositivo a posto", "set_device_fault_beep" to "Bip da spento",   // ANDROID (guasti 22/11) "set_cpr_quality" to "Qualità della RCP", "assess_crt" to "Valutazione del refill",
                          "set_spo2_low_perfusion" to "Bassa perfusione SpO₂", "set_spo2_signal_valid" to "Segnale SpO₂", "set_auto_physiology" to "Fisiologia automatica",
                          "set_nibp_interval" to "NIBP automatica", "reset_alarm_limits" to "Limiti di allarme predefiniti", "save" to "Salvataggio dello scenario",
                          "delete" to "Eliminazione dello scenario")[c] ?: c.replace('_', ' ')
        }
    }

    private fun enqueue(path: String, body: Map<String, Any?>, done: ((Boolean, String?, Map<String, Any?>?) -> Unit)?, label: String, key: String?, isLog: Boolean, tapClock: Int) {
        cmdSeq++; lastCmdAt = CACurrentMediaTime()
        val id = String.format(Locale.US, "%s-%d-%d", clientId.takeLast(6), (System.currentTimeMillis() / 1000) % 100000, ++cmdNum)
        val q = CtlQueued(path, body, done, label, id, key, isLog, tapClock)
        if (key != null) {        // stesso comando di stato non ancora partito: vale solo l'ultimo valore
            val old = queue.firstOrNull { it.key == key && !it.sending }
            if (old != null) { queue.remove(old); old.done?.invoke(false, null, null) }
        }
        // ANDROID (sola osservazione senza velo 22/11): comanda un altro (web regia / altro controller): il comando non parte, la
        // regia mostra un avviso breve. Le voci del registro passano (il monitor le accetta anche da chi osserva).
        if (!isLog && observing?.invoke() == true) { done?.invoke(false, null, null); onBlocked?.invoke(); return }
        if (wanted == null && wantedCode == null && link.state != SFLinkConnected) {     // nessun monitor: non c'è nessuno a cui mandarlo
            setMessage("Nessun monitor collegato"); done?.invoke(false, lastMessage, null); notifyUpdate(); return
        }
        queue.add(q)
        flushQueue()
        if (!authorized) notifyUpdate()
    }

    /** manda in ordine i comandi in coda; uno alla volta, così l'ordine resta quello dei tocchi */
    private fun flushQueue() {
        if (flushing || queue.isEmpty()) return
        if (link.state != SFLinkConnected || !authorized) return
        if (resetSending) return             // ANDROID (audit 26/11, RETE-R06): prima la risposta dell'azzeramento in volo
        val q = queue.first()
        flushing = true; q.sending = true
        var body = q.body
        // voce del registro arrivata in ritardo: nel testo il tempo vero del tocco
        if (q.isLog && q.tapClock >= 0 && CACurrentMediaTime() - q.at > 3) {
            val t = body["text"] as? String ?: ""
            body = LinkedHashMap(body).apply { this["text"] = String.format(Locale.US, "%s · segnata a %02d:%02d (arrivata in ritardo)", t, q.tapClock / 60, q.tapClock % 60) }
        }
        val h = LinkedHashMap(headers()); h["X-SimulaFranz-Cmd-Id"] = q.id
        link.request("POST", q.path, h, body) { status, b ->
            flushing = false; q.sending = false
            if (status == 0) {          // nessuna risposta: resta in coda (il monitor non lo ripete se l'aveva già eseguito)
                q.sentOnce = true
                SFLog.log(String.format(Locale.US, "%s: nessuna risposta, resta in coda", q.label))
                if (++silent >= 2) { silent = 0; link.resetAfterSilence() }
                notifyUpdate(); return@request
            }
            // ANDROID (fix analisi 22/11, BUG-11): 503 = monitor con i posti di connessione finiti: ha rifiutato la connessione
            // PRIMA di leggere la richiesta (niente eseguito). Prima il comando veniva tolto dalla coda («Comando non eseguito»);
            // ora resta in coda e riparte dopo 1 s (Retry-After), fino alla scadenza normale della coda (15 s). Il monitor è vivo:
            // non conta come lettura a vuoto.
            if (status == 503) {
                silent = 0
                SFLog.log(String.format(Locale.US, "%s: monitor occupato (503), riprovo fra 1 s", q.label))
                Uk.after(1.0) { flushQueue() }
                notifyUpdate(); return@request
            }
            silent = 0
            queue.remove(q)
            val ok: Boolean
            if (q.isLog) {
                ok = status == 200 && b != null
                if (ok) absorbLog(b!!, false, true)
                if (ok) setMessage(null) else setMessage("Voce non registrata: " + q.label)
            } else {
                ok = absorb(status, b)
                setMessage(if (ok) null else explain(status, b))
                if (!ok) SFLog.log(String.format(Locale.US, "%s %s -> %d %s", q.path, q.body["command"] ?: q.body["action"] ?: "", status, b?.get("error") ?: ""))
            }
            q.done?.invoke(ok, lastMessage, b)
            notifyUpdate()
            if (queue.isNotEmpty()) Uk.async { flushQueue() }
        }
    }

    private fun expireQueue() {
        if (queue.isEmpty()) return
        val now = CACurrentMediaTime()
        // ANDROID (audit 26/11, RETE-R07): esito incerto non più verificabile dopo 2 minuti: perso (con il motivo vero)
        val stale = uncertain.filter { !it.sending && now - it.at > kUncertainMaxAge }
        for (q in stale) { uncertain.remove(q); lostOne(q, "esito non verificato: il monitor non ha risposto") }
        val old = queue.filter { !it.sending && now - it.at > (if (it.isLog) kLogMaxAge else kCmdMaxAge) }
        if (old.isEmpty()) { if (stale.isNotEmpty()) notifyUpdate(); return }
        for (q in old) {
            queue.remove(q)
            if (q.sentOnce) { uncertain.add(q); SFLog.log(String.format(Locale.US, "%s: scaduto senza risposta, esito da verificare al ricollegamento", q.label)) }
            else lostOne(q, "il monitor non ha risposto")
        }
        notifyUpdate()
    }
    /** ANDROID (audit 26/11, RETE-R07): con il monitor di nuovo raggiungibile, chiede l'esito dei comandi incerti (per id) */
    private fun verifyUncertain() {
        for (q in uncertain.filter { !it.sending }) {
            q.sending = true
            link.request("GET", "/api/v1/command?id=" + q.id, headers(), null) { status, b ->
                q.sending = false
                if (status == 0 || !uncertain.contains(q)) return@request          // ancora niente: si riprova alla prossima lettura
                uncertain.remove(q)
                if (status == 200 && b?.get("done") == true) {
                    SFLog.log(String.format(Locale.US, "%s: eseguito dal monitor (verificato dopo la scadenza)", q.label))
                    q.done?.invoke(true, null, null)
                } else lostOne(q, if (status == 200) "il monitor non l'ha ricevuto" else "il monitor non ha risposto")
                notifyUpdate()
            }
        }
    }
    private fun failQueue(why: String) {
        for (q in uncertain.filter { !it.sending }) { uncertain.remove(q); lostOne(q, why) }    // ANDROID (audit 26/11, RETE-R07)
        if (queue.isEmpty()) return
        for (q in queue.filter { !it.sending }) { queue.remove(q); lostOne(q, why) }
    }
    private fun lostOne(q: CtlQueued, why: String) {
        SFLog.log(String.format(Locale.US, "NON ARRIVATO: %s (%s)", q.label, why))
        // ANDROID (debriefing scollegato 22/11): la fine scenario persa durante l'uscita verso la sala debriefing non è un errore da
        // mostrare: ci pensa l'azzeramento in sospeso al rientro (che sa che la fine non era arrivata)
        if (endSnap != null && (q.body["command"] == "end_scenario")) { q.done?.invoke(false, "offline", null); return }
        lost.add(q.label); while (lost.size > 6) lost.removeAt(0); lostAt = CACurrentMediaTime()
        setMessage(String.format(Locale.US, "NON ARRIVATO: %s (%s)", q.label, why))
        q.done?.invoke(false, lastMessage, null)
    }
    /** quanti comandi aspettano ancora la conferma */
    val queued: Int get() = queue.size

    fun command(name: String, value: Any?) = command(name, value, null)
    fun command(name: String, value: Any?, done: ((Boolean, String?) -> Unit)?) = command(name, value, null, done)
    // ANDROID (fix analisi 22/11, terzo giro): campi in più nel corpo del comando (es. "shock" per defib_outcome)
    fun command(name: String, value: Any?, extra: Map<String, Any?>?, done: ((Boolean, String?) -> Unit)?) {
        val b = linkedMapOf<String, Any?>("command" to name)
        if (value != null) b["value"] = value
        if (extra != null) b.putAll(extra)
        post("/api/v1/command", b) { ok, e, _ -> done?.invoke(ok, e) }
    }
    fun startNibp() = startNibp(null)
    // ANDROID (affordance 22/11, CTL-03): con l'esito, per il segno «… / ✓ / ✕» sul tasto
    fun startNibp(done: ((Boolean, String?) -> Unit)?) = enqueue("/api/v1/nibp/start", emptyMap(), if (done == null) null else { ok, e, _ -> done(ok, e) }, "Misura NIBP", "nibp", false, -1)
    // il posto di comando non passa dalla coda (prendere o lasciare il comando ha senso solo adesso)
    private fun control(action: String) {
        cmdSeq++
        link.request("POST", "/api/v1/control", headers(), mapOf("action" to action)) { status, b ->
            val ok = absorb(status, b)
            setMessage(if (ok) null else explain(status, b))
            notifyUpdate()
        }
    }
    fun takeControl() = control("take")

    // ANDROID (audit 26/11, PUL-F5): niente aggiornamento dal monitor (era il pacchetto .deb dell'iPad): l'app si aggiorna con l'APK nuovo

    fun refreshScenarios() {
        link.request("GET", "/api/v1/scenarios", headers(), null) { status, b ->
            if (status == 200) { absorb(status, b); notifyUpdate() }
            else if (status == 404) scenarios = null      // monitor vecchio, senza scenari
        }
    }

    // MARK: - Registro

    @Suppress("UNCHECKED_CAST")
    private fun absorbLog(b: Map<String, Any?>, reset: Boolean, live: Boolean = false) {
        if (reset || logEntries == null) logEntries = ArrayList()
        val le = logEntries!!
        val entries = (b["log"] as? List<Map<String, Any?>>) ?: emptyList()
        val firstNew = if (entries.isNotEmpty()) (entries.first()["i"] as? Number)?.toInt() ?: Int.MAX_VALUE else Int.MAX_VALUE
        // registro svuotato sul monitor: gli indici ripartono da voci che abbiamo già "oltre" -> si tiene solo il nuovo
        while (le.isNotEmpty() && ((le.last()["i"] as? Number)?.toInt() ?: 0) >= firstNew) le.removeAt(le.size - 1)
        le.addAll(entries)
        if (!reset && live) for (d in entries) noteMonitorClock(d["w"])      // ANDROID (funzioni 21/11): voci appena nate = ora del monitor
        logNext = (b["next"] as? Number)?.toInt() ?: 0
        (b["summary"] as? Map<String, Any?>)?.let { logSummary = it }
        (b["sessions"] as? List<Map<String, Any?>>)?.let { logSessions = it }
    }

    @Suppress("UNCHECKED_CAST")
    fun loadSession(sid: Int, done: ((List<Map<String, Any?>>, Map<String, Any?>?) -> Unit)?) {
        val all = ArrayList<Map<String, Any?>>()
        fun step(since: Int) {
            link.request("GET", String.format(Locale.US, "/api/v1/log?session=%d&since=%d", sid, since), headers(), null) { status, b ->
                if (status != 200 || b == null) { done?.invoke(all, null); return@request }
                all.addAll((b["log"] as? List<Map<String, Any?>>) ?: emptyList())
                if (b["more"] == true) { step((b["next"] as? Number)?.toInt() ?: 0); return@request }
                done?.invoke(all, b["summary"] as? Map<String, Any?>)
            }
        }
        step(0)
    }

    fun newSession(title: String?) {
        val body = linkedMapOf<String, Any?>("action" to "new_session"); if (!title.isNullOrEmpty()) body["title"] = title
        link.request("POST", "/api/v1/log", headers(), body) { status, b ->
            if (status == 200 && b != null) { logSession = (b["current_session"] as? Number)?.toInt() ?: 0; absorbLog(b, true) }
            else setMessage(explain(status, b))
            notifyUpdate()
        }
    }

    private fun loadLog() {
        if (logLoading) return
        logLoading = true
        val since = if (logEntries != null) logNext else 0
        link.request("GET", String.format(Locale.US, "/api/v1/log?since=%d", since), headers(), null) { status, b ->
            logLoading = false
            if (status == 404) { logAvailable = false; return@request }
            if (status != 200 || b == null) return@request
            // registro svuotato o sessione cambiata sul monitor mentre si leggeva: si rilegge da capo
            val entriesN = ((b["summary"] as? Map<*, *>)?.get("entries") as? Number)?.toInt() ?: 0
            val cs = (b["current_session"] as? Number)?.toInt() ?: 0
            val cleared = entriesN < (logEntries?.size ?: 0) || cs != logSession
            if (cs != 0) logSession = cs
            if (cleared && since > 0) { logEntries = null; logNext = 0; loadLog(); return@request }
            absorbLog(b, since == 0, since > 0)
            if (b["more"] == true) loadLog()
            notifyUpdate()
        }
    }

    // ANDROID (audit 21/11): la voce passa dalla coda; done(true) solo quando il monitor l'ha davvero scritta nel registro.
    // tapClock = tempo di scenario al tocco (se arriva in ritardo finisce nel testo).
    fun logAdd(kind: String, text: String, tag: String?) = logAdd(kind, text, tag, -1, null)
    fun logAdd(kind: String, text: String, tag: String?, tapClock: Int, done: ((Boolean, String?) -> Unit)?) {
        val body = linkedMapOf<String, Any?>("action" to "add", "kind" to kind, "text" to text)
        if (tag != null) body["tag"] = tag
        enqueue(String.format(Locale.US, "/api/v1/log?since=%d", logNext), body, { ok, e, _ -> done?.invoke(ok, e) }, "Registro: $text", null, true, tapClock)
    }

    fun logClear(done: ((Boolean, String?) -> Unit)?) {
        link.request("POST", "/api/v1/log", headers(), mapOf("action" to "clear")) { status, b ->
            if (status == 200 && b != null) absorbLog(b, true)
            done?.invoke(status == 200, if (status == 200) null else explain(status, b))
            notifyUpdate()
        }
    }

    // ANDROID (storia sim 22/11): richiesta qualsiasi all'API del monitor con le credenziali di questo controller
    // (archivio della Storia Sim, /api/v1/archive). Senza collegamento risponde subito con status 0.
    fun api(method: String, path: String, body: Map<String, Any?>?, done: ((Int, Map<String, Any?>?) -> Unit)?) {
        if (link.state != SFLinkConnected || !authorized) { done?.invoke(0, null); return }
        link.request(method, path, headers(), body, done)
    }
    // MARK: - Debriefing scollegato (ANDROID, debriefing scollegato 22/11)
    // FINE SCENARIO → l'istruttore porta il tablet in sala debriefing e il collegamento cade. Tutto quello che serve al piano di
    // debriefing e al salvataggio è fissato qui al tocco di FINE SCENARIO («istantanea di fine»: registro completo della sessione,
    // riepilogo, metriche RCP, riga di fine, monitor e layout), aggiornato finché il collegamento c'è e la sessione è la stessa,
    // salvato in un file (sopravvive alla chiusura dell'app). Dopo «Salva» / «Non salvare» l'azzeramento resta «in sospeso»
    // (preferenze) finché il monitor non risponde; parte solo se il monitor è ancora sulla stessa simulazione terminata.

    /** messaggi per l'istruttore nati qui (azzeramento al rientro, simulazione inviata): la regia li mostra nella riga dei messaggi */
    var onNotice: ((String) -> Unit)? = null
    fun notice(t: String) { SFLog.log("avviso: $t"); onNotice?.invoke(t) }
    var endSnap: LinkedHashMap<String, Any?>? = null; private set
    var endSnapRev = 0; private set
    var resetPending: Map<String, Any?>? = null; private set
    private val endSnapFile get() = java.io.File(Uk.ctx.filesDir, kEndSnapFile)

    @Suppress("UNCHECKED_CAST")
    private fun loadAway() {
        try { if (endSnapFile.exists()) (it.simulafranz.core.Json.parseOrNull(endSnapFile.readText(Charsets.UTF_8)) as? Map<String, Any?>)?.let { endSnap = LinkedHashMap(it) } }
        catch (x: Exception) { SFLog.log("fine scenario: istantanea non letta: $x") }
        resetPending = prefs.json(kResetPendingKey) as? Map<String, Any?>
        if (endSnap != null || resetPending != null) SFLog.log(String.format(Locale.US, "avvio: istantanea di fine %s, azzeramento in sospeso %s", if (endSnap != null) "presente" else "no", if (resetPending != null) "sì" else "no"))
    }
    private fun saveEndSnap() {
        val s = endSnap
        try { if (s == null) endSnapFile.delete() else endSnapFile.writeText(it.simulafranz.core.Json.stringify(s), Charsets.UTF_8) } catch (x: Exception) { SFLog.log("fine scenario: istantanea non scritta: $x") }
        endSnapRev++
    }
    /** nome leggibile del layout attivo (dal catalogo del monitor) */
    fun layoutNameNow(): String? {
        val lid = state?.get("layout") ?: return null
        for (l in (state?.get("layouts") as? List<*>) ?: emptyList<Any?>()) if (l is Map<*, *> && l["id"] == lid) return l["name"]?.toString()
        return lid.toString()
    }
    private fun snapEntries(): ArrayList<Map<String, Any?>> = ArrayList(log.map { d -> LinkedHashMap(d).apply { this["wall"] = wallOf(d["w"]) } })
    /** al tocco di FINE SCENARIO (dopo markEnd): fissa tutto e scarica subito il registro completo della sessione */
    fun takeEndSnapshot() {
        val e = endMark
        val s = linkedMapOf<String, Any?>("session" to logSession, "taken_w" to System.currentTimeMillis() / 1000,
            "monitor" to monitorName, "monitor_code" to (link.targetCode ?: wantedCode), "layout" to state?.get("layout"), "layout_name" to layoutNameNow(),
            "end" to e?.let { linkedMapOf("t" to it["t"], "w" to it["w"]) }, "end_ok" to false,
            "entries" to snapEntries(), "summary" to logSummary, "cpr" to state?.get("cpr_metrics"),
            "session_info" to logSessions?.firstOrNull { it["current"] == true }, "scenario_time_s" to state?.get("scenario_time_s"),
            "trend" to trendCache)                                    // ANDROID (funzioni mancanti 22/11, IN-14)
        endSnap = s; saveEndSnap()
        if (authorized) loadTrend60(null)                             // ANDROID (funzioni mancanti 22/11): il trend fino alla fine
        SFLog.log(String.format(Locale.US, "fine scenario: istantanea presa (sessione %d, %d voci)", logSession, log.size))
        val sid = logSession
        if (authorized) loadSession(sid) { all, sum ->
            val cur = endSnap
            if (cur == null || debInt0(cur["session"]) != sid || all.size < ((cur["entries"] as? List<*>)?.size ?: 0)) return@loadSession
            cur["entries"] = ArrayList(all.map { d -> LinkedHashMap(d).apply { this["wall"] = wallOf(d["w"]) } }); if (sum != null) cur["summary"] = sum
            saveEndSnap(); SFLog.log(String.format(Locale.US, "fine scenario: registro completo scaricato (%d voci)", all.size)); notifyUpdate()
        }
    }
    private fun debInt0(v: Any?): Int = (v as? Number)?.toInt() ?: -1
    /** la fine scenario è arrivata al monitor */
    fun noteEndConfirmed() { endSnap?.let { it["end_ok"] = true; saveEndSnap() } }
    /** finché il collegamento c'è e la sessione del registro è quella terminata, l'istantanea segue le voci arrivate dopo (riga di fine…) */
    private fun updateEndSnap() {
        val s = endSnap ?: return
        if (debInt0(s["session"]) != logSession || logEntries == null) return
        val n = log.size; val old = (s["entries"] as? List<*>)?.size ?: 0
        val cpr = state?.get("cpr_metrics")
        val changed = n > old || (n == old && logSummary != null && logSummary != s["summary"]) || (cpr != null && cpr != s["cpr"])
        if (!changed || n < old) return
        s["entries"] = snapEntries(); logSummary?.let { s["summary"] = it }; if (cpr != null) s["cpr"] = cpr
        logSessions?.firstOrNull { it["current"] == true }?.let { s["session_info"] = it }
        saveEndSnap()
    }
    fun clearEndSnap() { if (endSnap != null) { endSnap = null; saveEndSnap() } }

    /** «Salva in Storia Sim» / «Non salvare»: azzeramento del monitor, subito se risponde, altrimenti appena torna raggiungibile */
    fun requestReset(saved: Boolean) {
        val s = endSnap
        val rp = linkedMapOf<String, Any?>("session" to (s?.get("session") ?: logSession), "code" to ((s?.get("monitor_code") as? String) ?: link.targetCode ?: wantedCode),
            "end_ok" to (s?.get("end_ok") == true), "saved" to saved, "at" to System.currentTimeMillis() / 1000,
            "id" to String.format(Locale.US, "%s-r%d", clientId.takeLast(6), System.currentTimeMillis() % 100000000))
        resetPending = rp; prefs.setJson(kResetPendingKey, rp)
        resetAskedAt = CACurrentMediaTime()
        SFLog.log(String.format(Locale.US, "azzeramento in sospeso (sessione %s, fine arrivata %s)", rp["session"], rp["end_ok"]))
        if (link.state == SFLinkConnected && authorized) poll()      // stato fresco: l'azzeramento parte dalla risposta (absorb)
        notifyUpdate()
    }
    private fun dropReset(why: String?) {
        resetPending = null; prefs.setString(kResetPendingKey, null)
        if (why != null) notice(why)
        notifyUpdate()
    }
    private var resetSending = false; private var resetAskedAt = 0.0; private var resetRetryAt = 0.0
    /** chiamato da absorb (stato e sessione appena letti): manda l'azzeramento solo sulla stessa simulazione terminata */
    private fun trySendReset() {
        val rp = resetPending ?: return
        if (resetSending || link.state != SFLinkConnected || !authorized || CACurrentMediaTime() < resetRetryAt) return
        // ANDROID (audit 26/11, RETE-R06): l'azzeramento sta nell'ordine della coda: parte solo a coda vuota (i comandi toccati prima
        // arrivano prima, poi l'azzeramento li cancella tutti) e mentre è in volo la coda aspetta (flushQueue). Prima viaggiava in
        // parallelo sul pool di 3 thread e un comando vecchio poteva arrivare DOPO e sporcare lo scenario nuovo.
        if (flushing || queue.isNotEmpty()) return
        val code = link.targetCode ?: wantedCode
        val rc = rp["code"] as? String
        if (!rc.isNullOrEmpty() && !code.isNullOrEmpty() && rc != code) { SFLog.log("azzeramento in sospeso di un altro monitor: lasciato"); dropReset(null); return }
        val st = state ?: return
        val phase = st["scenario_phase"] as? String
        val sameSession = !logAvailable || logSession == debInt0(rp["session"])
        val endOk = rp["end_ok"] == true
        val go = sameSession && (phase == null || phase == "ended" || (!endOk && (phase == "running" || phase == "paused")))
        if (!go) {
            SFLog.log(String.format(Locale.US, "azzeramento in sospeso NON mandato: fase %s, sessione %d (era %s)", phase, logSession, rp["session"]))
            dropReset(if (phase == "ready" || !sameSession) "Al rientro il monitor era già stato azzerato (o c'è già una simulazione nuova): non l'ho azzerato di nuovo"
                      else "Al rientro la simulazione sul monitor era ripresa: non l'ho azzerata")
            return
        }
        resetSending = true
        val quick = CACurrentMediaTime() - resetAskedAt < 8
        val h = LinkedHashMap(headers()); h["X-SimulaFranz-Cmd-Id"] = rp["id"] as String
        cmdSeq++; lastCmdAt = CACurrentMediaTime()
        link.request("POST", "/api/v1/command", h, mapOf("command" to "reset_scenario")) { status, b ->
            resetSending = false
            if (queue.isNotEmpty()) Uk.async { flushQueue() }   // ANDROID (audit 26/11, RETE-R06): i comandi arrivati intanto ora partono
            if (status == 0) return@request                    // nessuna risposta: si riprova alla prossima lettura dello stato
            if (resetPending?.get("id") != rp["id"]) return@request
            if (status == 200) {
                SFLog.log("azzeramento in sospeso eseguito" + if (quick) "" else " (al rientro)")
                dropReset(if (quick) (if (rp["saved"] == true) "Salvata in Storia Sim · scenario azzerato" else "Non salvata · scenario azzerato") else "Monitor azzerato al rientro")
                absorb(status, b)                              // dopo dropReset: absorb non deve rimandarlo
            } else {
                SFLog.log(String.format(Locale.US, "azzeramento in sospeso rifiutato: %d %s", status, b?.get("error") ?: ""))
                if (status == 423) { resetRetryAt = CACurrentMediaTime() + 3; return@request }      // comanda un altro controller: si riprova
                dropReset("Azzeramento non eseguito: " + explain(status, b).removePrefix("Non eseguito: "))
            }
        }
    }
    /** «Avvia» con l'azzeramento ancora in sospeso: si prova subito (la regia non avvia finché non è andato) */
    fun kickReset() { if (resetPending != null) { resetRetryAt = 0.0; if (link.state == SFLinkConnected && authorized) poll() } }

    /** ANDROID (storia sim 22/11): bozza del debriefing (titolo, squadra) e simulazioni salvate: CtlStoria.kt */
    val storia: CtlStoria by lazy { CtlStoria(this) }

    // action: save | delete | start | goto | next | stop
    fun scenarioAction(body: Map<String, Any?>, done: ((Boolean, String?, Map<String, Any?>?) -> Unit)?) {
        post("/api/v1/scenarios", body, done)
    }
}
