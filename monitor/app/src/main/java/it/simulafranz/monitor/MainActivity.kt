// Portato da src/AppDelegate.m + src/main.m (monitor iPad)
// SimulaFranz_Monitor — avvio app, configurazione (PIN/porta), server HTTP e Bonjour.
// ANDROID: l'AppDelegate dell'iPad qui è l'attività unica dell'app. Differenze (segnate con "ANDROID:" dove sono):
//  - impostazioni: SFAndroidPrefs (SharedPreferences "it.simulafranz.monitor") con le stesse chiavi di NSUserDefaults;
//  - identità: sf_device_uid + nome (SFIdentity); il deviceId dello stato è "monitor-<codice>" invece di "monitor-01",
//    così due tablet nella stessa sala non si confondono mai (i controller rimandano il device_id che leggono dallo stato);
//  - Bonjour: android.net.nsd.NsdManager; collegamento diretto (SFPeerLink, Multipeer): Wi-Fi Direct (SFDirect.kt);
//  - URL di verifica (simulafranz-monitor://…): arrivano come Intent VIEW (onCreate / onNewIntent);
//  - permesso di posizione per l'elenco delle reti Wi-Fi: chiesto qui quando la pagina «Reti Wi-Fi» fa la prima ricerca.
package it.simulafranz.monitor

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Bundle
import android.util.DisplayMetrics
import android.util.Log
import android.view.View
import android.view.WindowManager
import it.simulafranz.core.*
import it.simulafranz.uk.*
import java.io.File
import java.security.SecureRandom
import java.util.Locale

private const val kPinKey = "sf_pin"      // stesse chiavi dell'iPad (Settings.bundle)
private const val kPortKey = "sf_port"
private const val kEdition = "pubblica"
private const val kLocationRequest = 71

/** Attività unica dell'app: schermo intero orizzontale, tela 1024×768 punti come l'iPad. */
class MainActivity : Activity() {
    private lateinit var prefs: SFAndroidPrefs
    private lateinit var state: SimulationState
    private lateinit var api: APIController
    private var server: LocalHTTPServer? = null
    private lateinit var monitor: MonitorViewController
    private lateinit var sound: SoundEngine
    private var bonjour: NsdManager.RegistrationListener? = null
    private var bonjourApp: NsdManager.RegistrationListener? = null
    private var directAsked = false
    private var pairPrompt: SFPairPrompt? = null         // ANDROID: richiesta di associazione rapida a schermo
    private lateinit var direct: SFDirect                   // ANDROID: collegamento diretto Wi-Fi Direct (al posto di SFPeerLink)
    private var port: Int = 0
    private var ver: String = ""
    private var logTimer: NSTimer? = null
    private var locationDone: ((Boolean) -> Unit)? = null
    private var crashSave: SFCrashSave? = null              // ANDROID (funzioni 21/11): salvataggio e ripresa dopo una chiusura anomala
    // ANDROID (audit 26/11, BUG-A1): con «Blocca schermo» fra due momenti dello scenario il Wi-Fi andava in risparmio e il controller
    // perdeva il monitor. WifiLock ad alte prestazioni finché il server è acceso. L'orologio della simulazione resta
    // CACurrentMediaTime (System.nanoTime): è la stessa base del vsync, dell'audio e dei tocchi; passarlo a elapsedRealtime
    // solo per lo stato sfaserebbe tick, curve e suoni. A schermo spento con la CPU sospesa la simulazione resta ferma (come prima).
    private var wifiLock: android.net.wifi.WifiManager.WifiLock? = null
    private fun holdWifi(on: Boolean) {
        try {
            if (on) {
                if (wifiLock == null) {
                    val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as? android.net.wifi.WifiManager ?: return
                    @Suppress("DEPRECATION")
                    wifiLock = wm.createWifiLock(android.net.wifi.WifiManager.WIFI_MODE_FULL_HIGH_PERF, "similFranz:server").apply { setReferenceCounted(false) }
                }
                if (wifiLock?.isHeld == false) { wifiLock?.acquire(); SFLog.log("wifi: blocco ad alte prestazioni attivo") }
            } else {
                if (wifiLock?.isHeld == true) wifiLock?.release()
                wifiLock = null
            }
        } catch (e: Exception) { SFLog.log("wifi: blocco non disponibile: $e") }
    }
    // ANDROID (audit 26/11, BUG-A3): funzioni date agli oggetti statici, tenute qui per toglierle in onDestroy solo se sono ancora le nostre
    private val onRename: () -> Unit = { republishName() }
    private val askLoc: ((Boolean) -> Unit) -> Unit = { done -> askLocation(done) }
    private val tShockDown: () -> Unit = { keyShockDown() }
    private val tShockUp: () -> Unit = { keyShockUp() }
    private val tHint: (String) -> Unit = { t -> try { monitor.menuToast(t, false, 1.5, alert = true) } catch (e: Exception) {} }
    private val tPower: () -> Unit = { keyPower() }
    private val tCharge: () -> Unit = { keyCharge() }
    private val ecgTxSound: (String) -> Unit = { ph -> if (ph == "done") sound.txDone() else if (ph != "cancelled") sound.txProgress() }
    private val ecgTxLayout: () -> String? = { state.stateDictionary()["layout"] as? String }

    // MARK: - Configurazione

    // PIN del controller. Se assente o troppo corto, ne genera uno casuale di 6 cifre e lo salva.
    private fun currentPin(): String {
        var pin = (prefs.string(kPinKey) ?: "").trim()
        if (pin.length < 4) {
            pin = String.format(Locale.US, "%06d", SecureRandom().nextInt(1000000))
            prefs.setString(kPinKey, pin)
        }
        return pin
    }

    // Nome con cui il monitor compare nei controller (pannello (i) › Rinomina).
    // ANDROID: il nome di serie non è "similFranz Monitor 1" ma "similFranz Monitor <codice del tablet>" (vedi SFIdentity).
    private fun monitorName(): String = SFIdentity.monitorName()

    private fun configuredPort(): Int {
        val p = prefs.int(kPortKey, 8080)       // registerDefaults: 8080
        return if (p in 1024..65535) p else 8080
    }

    private fun bundleVersion(): String =
        try { packageManager.getPackageInfo(packageName, 0).versionName ?: "?" } catch (e: Exception) { "?" }

    // MARK: - Ciclo di vita

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)      // lo schermo resta acceso durante la simulazione
        Uk.ctx = this
        SFLog.sink = { line -> Log.i("SimulaFranz", line) }
        SFLog.directory = cacheDir                                           // <cache>/SimulaFranz_Monitor.log
        prefs = SFAndroidPrefs(this)
        SFIdentity.ensure(prefs)                                             // al primo avvio: sf_device_uid + nome di serie
        ver = bundleVersion()
        SFLog.log(String.format(Locale.US, "avvio similFranz Monitor %s (%s, codice %s, «%s»)", ver, kEdition, SFIdentity.code(), monitorName()))
        SimulationState.appVersion = ver

        val dm = DisplayMetrics()
        @Suppress("DEPRECATION") windowManager.defaultDisplay.getRealMetrics(dm)
        UkRootView.prepare(maxOf(dm.widthPixels, dm.heightPixels), minOf(dm.widthPixels, dm.heightPixels), 1024.0, 768.0)
        val rv = UkRootView(1024.0, 768.0)
        SFSideBand.left = rv.leftBand          // fianco sinistro: prese delle sonde dei layout dei dispositivi
        SFSideBand.right = rv.rightBand        // fianco destro: prosecuzione della scocca

        // ANDROID: sull'iPad il deviceId è fisso ("monitor-01"); qui porta il codice del tablet
        state = SimulationState("monitor-" + SFIdentity.code())
        state.clock = { CACurrentMediaTime() }

        val files = HashMap<String, ByteArray>()
        for (name in listOf("index.html", "style.css", "app.js", "body.svg")) {
            val d = try { assets.open("web-console/$name").use { it.readBytes() } } catch (e: Exception) { null }
            if (d != null) files[name] = d else SFLog.log("file controller mancante: $name")
        }
        // ANDROID (miniature dei layout 23/11): miniature vere dei layout per il menu della regia (web-console/thumbs/<id>.png;
        // quelle dei layout personalizzati stanno in src/custom/assets)
        for (f in (try { assets.list("web-console/thumbs") } catch (e: Exception) { null }) ?: emptyArray()) {
            if (!f.endsWith(".png")) continue
            try { files["thumbs/$f"] = assets.open("web-console/thumbs/$f").use { it.readBytes() } } catch (e: Exception) { }
        }
        api = APIController(state, { currentPin() }, files)
        api.clock = { CACurrentMediaTime() }
        // scenari a fasi: salvati sul monitor, condivisi da web app e app controller
        val support = File(filesDir, "similFranz")
        support.mkdirs()
        api.scenarios = SFScenarioStore(support, state)                      // <files>/similFranz/scenari.json
        // registro della sessione per il debriefing: osserva lo stato una volta al secondo (leggero) e resta salvato su file
        api.sessionLog = SFSessionLog(support, state)                        // <files>/similFranz/registro.json
        // ANDROID (trasmissione 12D 22/11): la trasmissione simulata del 12D scrive nel registro (tag "ecg12_tx") per controller e web
        run { val lg = api.sessionLog; SFEcg12Tx.log = { k, t, tag -> lg?.add(k, t, tag, null) != null }
              SFEcg12Tx.entriesSince = { i -> lg?.entriesSince(i) ?: emptyList() }; SFEcg12Tx.nextIndex = { lg?.nextIndex() ?: 0 }
              SFEcg12Tx.sound = ecgTxSound   // ANDROID (suoni 25/11)
              SFEcg12Tx.currentLayout = ecgTxLayout }   // ANDROID (centrale 25/11)
        api.archive = SFArchive(support)                                     // ANDROID (storia sim 22/11): <files>/similFranz/archivio/<id>.json
        // ANDROID (funzioni 21/11): ripresa dopo un crash / chiusura anomala (regola in SFCrashSave.kt), prima del monitor e del server
        val cs = SFCrashSave(support, state, api.scenarios)                  // <files>/similFranz/ripresa.json
        crashSave = cs
        cs.restoreIfNeeded()?.let { SFLog.log(it) }
        api.appVersion = ver                                                 // ANDROID (audit 26/11, PUL-F5): niente più pacchetti .deb
        // ANDROID (sala 22/11): fotocamera frontale e microfono in diretta per il controller che comanda (SFLiveCam.kt)
        SFLiveCam.attach(this)
        api.liveHandler = { kind, q, allowed -> SFLiveCam.handle(kind, q, allowed) }
        val slog = api.sessionLog
        logTimer?.invalidate()
        val api = this.api
        // ANDROID (audit 21/11, ANALISI_ESPERTO 3.6): il posto di comando resta riservato a chi comanda per 45 s di silenzio
        // (prima 10 s), perché il ricollegamento del controller dopo una caduta del Wi-Fi Direct dura fino a 25 s: prima il posto
        // si liberava a metà e al rientro il controller si ritrovava in sola osservazione. La scadenza si controlla anche qui,
        // una volta al secondo insieme al registro (prima solo all'arrivo di una richiesta).
        api.control.idleTimeout = 45.0
        logTimer = NSTimer.scheduledTimerWithTimeInterval(1.0, true) { slog?.observe(); api.control.owner(); cs.tick() }   // ANDROID (funzioni 21/11): + salvataggio ogni 5 s

        // ANDROID: suono e voce creati qui e passati al monitor (sull'iPad li crea MonitorViewController)
        sound = SoundEngine(prefs)
        SFVoice.start(this)
        monitor = MonitorViewController(state, sound, prefs)
        monitor.pinProvider = { currentPin() }

        // Un solo controller alla volta, su qualsiasi canale (web app, app nativa via Wi-Fi, app nativa in diretto)
        // ANDROID: associazione rapida dei controller Android: «Associa» a tutto schermo (sopra anche le fasce laterali)
        api.onPairRequest = { cid, name ->
            Uk.async {
                pairPrompt?.removeFromSuperview()
                SFLog.log(String.format(Locale.US, "associazione: il controller «%s» chiede di comandare", name))
                // ANDROID (audit 21/11): a tutto schermo solo con il dispositivo del partecipante spento (standby/armadio);
                // a dispositivo acceso una fascia stretta in alto, per non coprire il paziente davanti ai partecipanti
                val pp = SFPairPrompt(rv.root.bounds, name, SFIdentity.code(), state.devicePowered) { ok ->
                    api.pairAnswer(cid, ok)
                    SFLog.log(String.format(Locale.US, "associazione con «%s»: %s", name, if (ok) "accettata" else "rifiutata"))
                    if (ok) api.sessionLog?.add("note", "Controller associato: $name", null, "regia")
                }
                pairPrompt = pp
                rv.root.addSubview(pp)
            }
        }
        api.control.onChange = {
            if (::direct.isInitialized) Uk.async { direct.republish() }      // ANDROID: l'annuncio dice chi sta comandando
            val o = api.control.owner()
            SFLog.log(String.format(Locale.US, "controller attivo: %s", if (o != null) String.format(Locale.US, "%s (%s, %s)", o["name"], o["kind"], o["transport"]) else "nessuno"))
            val ev = api.control.lastEvent           // ogni cambio di mano resta nel registro della sessione (trasparenza)
            if (!ev.isNullOrEmpty()) api.sessionLog?.add("note", ev, null, "regia")
        }
        // Collegamento diretto (Multipeer sull'iPad): stessa API del server HTTP, senza rete Wi-Fi
        // ANDROID: Wi-Fi Direct (SFDirect) al posto di Multipeer: il monitor crea la sua rete e la annuncia con
        // {type: monitor, name, id, ver, code, port} + credenziali; i controller usano lo stesso server HTTP.
        direct = SFDirect(this)
        direct.announce = { mapOf("name" to monitorName(), "id" to state.deviceId, "ver" to ver, "code" to SFIdentity.code(), "port" to port.toString(),
                                  "busy" to ((api.control.owner()?.get("name") as? String) ?: "")) }
        direct.onChange = { if (::monitor.isInitialized) monitor.updateInfoText() }
        val st = state
        monitor.onResetScenario = {
            val a = api
            a.scenarios?.stop()
            st.applyCommand(mapOf("command" to "reset_scenario"))
            a.sessionLog?.noteCommand(mapOf("command" to "reset_scenario"), "monitor")
            crashSave?.save()                                                // ANDROID (funzioni 21/11): azzerato = niente da riprendere
        }
        monitor.linkInfo = {
            val d = LinkedHashMap<String, Any?>()
            d["version"] = ver
            d["directName"] = monitorName()
            d["directOn"] = direct.isOn                                      // ANDROID: rete Wi-Fi Direct del monitor pronta
            if (!direct.isOn) d["directError"] = direct.lastError ?: "rete Wi-Fi Direct in preparazione"
            d["peers"] = direct.peers()
            val o = api.control.owner()
            if (o != null) d["controller"] = o
            d
        }

        setContentView(rv)
        // ANDROID (sala 22/11): avviso «● IN DIRETTA» quando fotocamera o microfono sono accesi (sopra tutto, anche in pausa)
        val liveBadge = SFLiveBadge(rv.root, rv.bandWidth, 1024.0)
        rv.root.addSubview(liveBadge)
        SFLiveCam.onIndicator = { cam, mic -> liveBadge.show(cam, mic) }
        monitor.viewDidLoad()
        rv.canvas.addSubview(monitor.view)

        // ANDROID: permesso di posizione (elenco delle reti) chiesto alla prima ricerca dalla pagina «Reti Wi-Fi»
        SFWifi.shared().requestLocationPermission = askLoc
        // ANDROID: «Rinomina» nel pannello (i): il servizio Bonjour riparte con il nome nuovo
        SFIdentity.onRename = onRename

        currentPin()   // genera il PIN al primo avvio
        startServer()
        handleIntent(intent)
    }

    private fun startServer() {
        // ANDROID: collegamento diretto (sull'iPad [_peer start]); chiede prima il permesso di posizione (Android 9 lo vuole)
        // (una sola richiesta per avvio: se è negato non lo si richiede a ogni ritorno in primo piano)
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) direct.start()
        else if (!directAsked) { directAsked = true; askLocation { ok -> if (ok) direct.start() } }
        if (server?.isRunning == true) return
        port = configuredPort()
        val api = this.api
        // ANDROID: le richieste dalla rete Wi-Fi Direct sono "collegamento diretto" (come il ponte SFPeerLink: "peer:…")
        val dir = direct
        val s = LocalHTTPServer(port) { req ->
            if (SFDirect.isDirectAddress(req.remoteAddress)) {
                req.remoteAddress = "peer:" + req.remoteAddress
                val n = req.headers["x-simulafranz-client-name"]
                if (!n.isNullOrEmpty()) dir.noteClient(try { java.net.URLDecoder.decode(n, "UTF-8") } catch (e: Exception) { n })
            }
            api.handle(req)
        }
        server = s
        val err = s.start()
        if (err == null) {
            monitor.serverStatus = String.format(Locale.US, "attivo sulla porta %d", port)
            holdWifi(true)                                                   // ANDROID (audit 26/11, BUG-A1)
            SFLog.log(String.format(Locale.US, "server HTTP attivo sulla porta %d", port))
            // Bonjour opzionale: il fallback resta sempre l'IP mostrato a schermo
            bonjour = publish("_http._tcp", String.format(Locale.US, "similFranz Monitor %s", state.deviceId), null)
            // Servizio dedicato: l'app controller sulla stessa rete Wi-Fi trova il monitor senza scrivere l'indirizzo
            // (non "_similfranz._tcp": quel nome è già usato da Multipeer per il collegamento diretto)
            bonjourApp = publishApp()
        } else {
            monitor.serverStatus = String.format(Locale.US, "ERRORE: %s", err)
            SFLog.log(String.format(Locale.US, "avvio server fallito: %s", err))
        }
        monitor.serverPort = port
    }

    private fun publishApp(): NsdManager.RegistrationListener? {
        // ANDROID: "code" in più nel TXT = codice del tablet (distingue due monitor con lo stesso nome)
        val txt = listOf("type" to "monitor", "id" to state.deviceId, "ver" to ver, "api" to "v1", "code" to SFIdentity.code())
        return publish("_similfranz-http._tcp", monitorName(), txt)
    }

    // ANDROID: NSNetService -> NsdManager. Ritorna l'oggetto con cui togliere il servizio (null = non pubblicato).
    private fun publish(type: String, name: String, txt: List<Pair<String, String>>?): NsdManager.RegistrationListener? {
        val nsd = try { applicationContext.getSystemService(Context.NSD_SERVICE) as? NsdManager } catch (e: Exception) { null }
        if (nsd == null) { SFLog.log("Bonjour non pubblicato: NsdManager non disponibile"); return null }
        val info = NsdServiceInfo()
        info.serviceName = name; info.serviceType = type; info.port = port
        if (txt != null) for (kv in txt) info.setAttribute(kv.first, kv.second)
        val l = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(i: NsdServiceInfo) { SFLog.log(String.format(Locale.US, "Bonjour pubblicato: %s «%s»", type, i.serviceName)) }
            override fun onRegistrationFailed(i: NsdServiceInfo, errorCode: Int) { SFLog.log(String.format(Locale.US, "Bonjour non pubblicato: %s «%s» (%d)", type, name, errorCode)) }
            override fun onServiceUnregistered(i: NsdServiceInfo) {}
            override fun onUnregistrationFailed(i: NsdServiceInfo, errorCode: Int) {}
        }
        return try { nsd.registerService(info, NsdManager.PROTOCOL_DNS_SD, l); l }
        catch (e: Exception) { SFLog.log("Bonjour non pubblicato: $e"); null }
    }

    private fun unpublish(l: NsdManager.RegistrationListener?) {
        if (l == null) return
        try { (applicationContext.getSystemService(Context.NSD_SERVICE) as? NsdManager)?.unregisterService(l) } catch (e: Exception) {}
    }

    // ANDROID: nome cambiato dal pannello: il servizio dell'app controller viene ripubblicato con il nome nuovo
    private fun republishName() {
        if (server?.isRunning != true) return
        unpublish(bonjourApp); bonjourApp = null
        bonjourApp = publishApp()
        direct.republish()                                     // ANDROID: anche l'annuncio Wi-Fi Direct
        if (::monitor.isInitialized) monitor.updateInfoText()
    }

    private fun stopServer() {
        // ANDROID: collegamento diretto (sull'iPad [_peer stop]): si ferma l'annuncio, la rete resta (i controller non cadono)
        if (::direct.isInitialized) direct.stop()
        unpublish(bonjour); bonjour = null
        unpublish(bonjourApp); bonjourApp = null
        server?.stop(); server = null
        holdWifi(false)                                                      // ANDROID (audit 26/11, BUG-A1)
    }

    // In background il sistema può sospendere l'app: chiudo il socket e lo riapro al ritorno.
    // ANDROID: applicationDidEnterBackground = onStop, applicationWillEnterForeground = onStart, applicationDidBecomeActive = onResume.
    // ANDROID: sull'iPad il server si chiude in background. Qui NO: una finestra di sistema (es. la richiesta USB
    // di Samsung quando si attacca il cavo) manda l'app in onStop e i controller perdevano il monitor (BugFix #16).
    // Il server e il Wi-Fi Direct restano attivi finché l'app non viene chiusa davvero (onDestroy).
    override fun onStop() { super.onStop(); api.sessionLog?.flush(); crashSave?.save() }   // ANDROID (funzioni 21/11): + salvataggio per la ripresa
    override fun onStart() { super.onStart(); startServer() }
    override fun onResume() {
        super.onResume()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        startServer()
        monitor.viewDidAppear()
        // ANDROID (guscio 25/11): con il monitor in primo piano Home = CARICA, tre pressioni per uscire (SFTastiService)
        SFTasti.inFront = true
        SFTasti.shockDown = tShockDown; SFTasti.shockUp = tShockUp
        SFTasti.hint = tHint
        SFTasti.power = tPower
        SFTasti.charge = tCharge
    }
    override fun onPause() { SFTasti.inFront = false; keyShockUp(); monitor.viewWillDisappear(); crashSave?.save(); super.onPause() }   // ANDROID (funzioni 21/11): + salvataggio per la ripresa
    override fun onDestroy() {
        stopServer()
        SFLiveCam.stopAll()                                                  // ANDROID (sala 22/11)
        SFEndJingle.stop()                                                   // ANDROID (storia sim 22/11)
        logTimer?.invalidate(); logTimer = null
        // ANDROID (audit 26/11, PERF-F3/BUG-A3): niente più monitor, suono e riferimenti statici dell'attività chiusa. I campi statici
        // si azzerano solo se puntano ancora a QUESTA attività: Android può chiamare onDestroy della vecchia dopo onCreate della nuova.
        if (::monitor.isInitialized) monitor.dispose()
        if (::sound.isInitialized) sound.stop()
        if (SFIdentity.onRename === onRename) SFIdentity.onRename = null
        if (SFWifi.shared().requestLocationPermission === askLoc) SFWifi.shared().requestLocationPermission = null
        if (SFTasti.shockDown === tShockDown) SFTasti.shockDown = null
        if (SFTasti.shockUp === tShockUp) SFTasti.shockUp = null
        if (SFTasti.hint === tHint) SFTasti.hint = null
        if (SFTasti.power === tPower) SFTasti.power = null
        if (SFTasti.charge === tCharge) SFTasti.charge = null
        if (SFEcg12Tx.sound === ecgTxSound) SFEcg12Tx.sound = null
        if (SFEcg12Tx.currentLayout === ecgTxLayout) SFEcg12Tx.currentLayout = null
        if (SFSideBand.left?.context === this) SFSideBand.left = null
        if (SFSideBand.right?.context === this) SFSideBand.right = null
        SFLiveCam.detach(this)
        if (Uk.ctx === this) Uk.ctx = applicationContext
        super.onDestroy()
    }

    // ANDROID: il tasto Indietro non chiude il monitor (non si esce per sbaglio durante la simulazione)
    @Suppress("OVERRIDE_DEPRECATION")
    override fun onBackPressed() {}

    // ANDROID (guscio 25/11): tasto fisico di SCARICA, su tutti i layout. Con il servizio dei tasti attivo (SFTastiService) è il
    // tasto «app recenti», e «indietro» apre le app recenti; senza servizio resta il tasto «indietro». Manda lo stesso
    // comando del tasto a schermo (premuto = defib_shock, rilasciato = defib_shock_release, così in SYNC va tenuto fino all'onda R).
    // A dispositivo spento non fa nulla; se la scarica non è possibile (non carico, piastre staccate) decide il modello, come a schermo.
    private var backShockDown = false
    private fun keyCmd(name: String) { try { val c = HashMap<String, Any?>(); c["command"] = name; val e = state.applyCommand(c); SFLog.log("tasto fisico: " + name + " -> " + (e ?: "ok")) } catch (e: Exception) {} }
    // Il tasto fisico «preme» il tasto a schermo del layout (stessa animazione e stesso comportamento); se a schermo non c'è
    // (per esempio il cassetto del defibrillatore chiuso) manda il comando al modello.
    private var keyShockBtn: UIButton? = null
    private fun keyShockDown() {
        if (backShockDown) return
        val b = SFTasti.onScreen("SHOCK")
        if (b != null) { backShockDown = true; keyShockBtn = b; b.touchesBegan(SFTasti.centro(b)) }
        else if (state.snapshot().devicePowered) { backShockDown = true; keyCmd("defib_shock") }
    }
    private fun keyShockUp() {
        if (!backShockDown) return
        backShockDown = false
        val b = keyShockBtn; keyShockBtn = null
        if (b != null) b.touchesEnded(SFTasti.centro(b)) else keyCmd("defib_shock_release")
    }
    // accensione: preme il tasto a schermo; se il layout non reagisce (tasto assente, o spegnimento che chiede di tenerlo
    // premuto) accende o spegne direttamente, come fa il tasto del layout
    private fun keyPower() {
        val was = state.devicePowered
        val b = SFTasti.onScreen("POWER")
        if (b != null) { b.touchesBegan(SFTasti.centro(b)); Uk.after(0.18) { b.touchesEnded(SFTasti.centro(b)) } }
        Uk.after(if (b != null) 0.7 else 0.0) { if (state.devicePowered == was) { state.devicePowered = !was; SFLog.log("tasto fisico: accensione -> " + state.devicePowered) } }
    }
    private fun keyCharge() {
        val b = SFTasti.onScreen("CHARGE")
        if (b != null) { b.touchesBegan(SFTasti.centro(b)); Uk.after(0.18) { b.touchesEnded(SFTasti.centro(b)) } }
        else if (state.snapshot().devicePowered) keyCmd("defib_charge")
    }
    override fun onKeyDown(keyCode: Int, event: android.view.KeyEvent): Boolean {
        if (keyCode == android.view.KeyEvent.KEYCODE_BACK) {
            if (event.repeatCount == 0 && !SFTasti.attivo) keyShockDown()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }
    override fun onKeyUp(keyCode: Int, event: android.view.KeyEvent): Boolean {
        if (keyCode == android.view.KeyEvent.KEYCODE_BACK) {
            if (!SFTasti.attivo) keyShockUp()
            return true
        }
        return super.onKeyUp(keyCode, event)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        @Suppress("DEPRECATION")
        if (hasFocus) window.decorView.systemUiVisibility =
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or View.SYSTEM_UI_FLAG_FULLSCREEN or
            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
            View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
    }

    // MARK: - Permesso di posizione (elenco delle reti Wi-Fi)

    private fun askLocation(done: (Boolean) -> Unit) {
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) { done(true); return }
        val prev = locationDone; locationDone = done
        if (prev != null) { prev(false); }          // una richiesta alla volta
        SFLog.log("wifi: chiedo il permesso di posizione (serve ad Android per elencare le reti)")
        requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION), kLocationRequest)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (SFLiveCam.onPermissionResult(requestCode, permissions, grantResults)) return     // ANDROID (sala 22/11)
        if (requestCode != kLocationRequest) return
        val ok = grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED
        SFLog.log(String.format(Locale.US, "wifi: permesso di posizione %s", if (ok) "concesso" else "NEGATO"))
        val d = locationDone; locationDone = null
        d?.invoke(ok)
    }

    // MARK: - URL di verifica (dal Mac: adb shell am start -a android.intent.action.VIEW -d "simulafranz-monitor://…")

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        if (intent == null || intent.action != Intent.ACTION_VIEW) return
        val url = intent.data ?: return
        if (url.scheme != "simulafranz-monitor") return
        SFLog.log("openURL: " + url.scheme + "://" + (url.host ?: ""))   // ANDROID (audit 26/11, PUL-F4): senza la query (JOIN:ssid:password, comandi)
        openURL(url)
    }

    // simulafranz-monitor://selftest -> autotest dell'interfaccia del defibrillatore (log + screenshot in <cache>/selftest)
    private fun openURL(url: Uri): Boolean {
        // ANDROID (audit 26/11, PUL-F2): tutti i comandi via URL solo nelle build di prova (debuggable); in quella distribuita nulla
        if ((applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) == 0) { SFLog.log("openURL ignorato: solo nelle build di prova"); return true }
        val host = url.host ?: ""
        val query = url.encodedQuery
        if (host == "tracemode") { UkProfile.traceMode = (query ?: "0").filter { it.isDigit() }.toIntOrNull() ?: 0; SFLog.log("tracemode " + UkProfile.traceMode); return true }
        // ANDROID (ritardo R→polso 22/11): sonda di sincronia (solo app di prova): simulafranz-monitor://syncprobe?s=12&tag=xx
        if (host == "syncprobe") {
            val kv = (query ?: "").split("&").mapNotNull { val i = it.indexOf('='); if (i > 0) it.substring(0, i) to Uri.decode(it.substring(i + 1)) else null }.toMap()
            SFSyncProbe.start(kv["s"]?.toDoubleOrNull() ?: 12.0, kv["tag"] ?: "probe", state.snapshot().layout)
            return true
        }
        // ANDROID (audit 26/11, PUL-F3): profilatore, campionatore e sonda di sincronia solo nelle build di prova (controllo all'inizio di openURL)
        if (host == "profile") { UkProfile.on = !UkProfile.on; if (UkProfile.on) UkSampler.start { SFLog.log(it) } else UkSampler.stop(); SFLog.log("PROFILE " + (if (UkProfile.on) "acceso" else "spento")); return true }
        // ANDROID (guscio 25/11): prova dei tasti fisici senza premerli (collaudo e riprese): simulafranz-monitor://tasto?k=accensione|carica|scarica|avviso
        // fa esattamente quello che fa il tasto fisico; la scarica resta «premuta» mezzo secondo (o ?ms=...)
        if (host == "tasto") {
            val kv = (query ?: "").split("&").mapNotNull { val i = it.indexOf('='); if (i > 0) it.substring(0, i) to Uri.decode(it.substring(i + 1)) else null }.toMap()
            when (kv["k"]) {
                "accensione" -> keyPower()
                "carica" -> keyCharge()
                "scarica" -> { keyShockDown(); Uk.after((kv["ms"]?.toDoubleOrNull() ?: 500.0) / 1000.0) { keyShockUp() } }
                "avviso" -> SFTasti.hint?.invoke("Premi ancora Home per uscire dal monitor")
            }
            return true
        }
        // ANDROID (ICU 21/11): autotest dell'ICU con lo stesso schema degli altri layout (scripts/prova-autotest.sh generic_icu)
        if (host == "selftest-generic_icu") { Uk.after(1.5) { monitor.runSelfTest() }; return true }
        // ANDROID (ICU 21/11): verifica da Mac di uno stato del paziente (solo app di prova, debuggable):
        // simulafranz-monitor://cmd?j=<comando JSON url-encoded>, es. {"command":"set_technical_alarm","value":{"kind":"low_battery","on":true}}
        // ANDROID (miniature dei layout 23/11): misura NIBP dal Mac (solo app di prova): scripts/miniature-layout.sh
        if (host == "nibp") {
            val e = state.startNibpMeasurement(); SFLog.log("nibp di verifica -> ${e ?: "ok"}"); return true
        }
        if (host == "cmd") {
            val j = (query ?: "").split("&").firstOrNull { it.startsWith("j=") }?.substring(2)
            @Suppress("UNCHECKED_CAST")
            val c = j?.let { t -> Json.parseOrNull(Uri.decode(t)) } as? Map<String, Any?>
            if (c != null) { val e = state.applyCommand(c); SFLog.log("cmd di verifica: $c -> ${e ?: "ok"}") }
            return true
        }
        if (host == "selftest-extrah") {
            Uk.after(1.5) { monitor.runSelfTestExtraH() }
        }
        if (host == "selftest-dae") {
            Uk.after(1.5) { monitor.runSelfTestDae() }
        }
        // simulafranz-monitor://tap?key=XXX -> tocco simulato del discente sul tasto XXX (verifica degli scenari da Mac)
        if (host == "layout") {
            val n = (query ?: "").split("&").firstOrNull { it.startsWith("name=") }?.substring(5)
            if (!n.isNullOrEmpty()) monitor.debugLayout(n)
            return true
        }
        if (host == "tap") {
            var key: String? = null
            for (kv in (query ?: "").split("&")) if (kv.startsWith("key=")) key = Uri.decode(kv.substring(4))
            val k = key
            if (k != null) Uk.after(0.2) { monitor.debugTap(k) }
        }
        // simulafranz-monitor://shot?name=xxx -> screenshot immediato (verifica grafica da Mac)
        if (host == "shot") {
            var name = "shot"
            for (kv in (query ?: "").split("&")) if (kv.startsWith("name=")) name = File(kv.substring(5)).name
            Uk.after(0.3) { monitor.saveShot(name) }
        }
        if (host == "splash") { monitor.debugSplash(query ?: "") }
        if (host == "selftest") {
            Uk.after(1.5) { monitor.runSelfTest() }
        }
        return true
    }
}
