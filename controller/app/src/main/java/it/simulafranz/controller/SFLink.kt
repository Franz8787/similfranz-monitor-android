// Portato da src/SFLink.m (controller iPad) — stessa interfaccia, trasporto diverso.
// similFranz Controller — collegamento DIRETTO con similFranz Monitor, senza nessuna rete Wi-Fi della sala.
// ANDROID: Multipeer non esiste. Il monitor crea da solo un gruppo Wi-Fi Direct (è il "proprietario del gruppo") e lo
// annuncia con la ricerca dei servizi Wi-Fi Direct (DNS-SD, tipo _sfmonitor._tcp) con nome, codice, versione e le
// credenziali della sua rete (SSID "DIRECT-…" + password). Il controller:
//   1. cerca i monitor vicini (ricerca dei servizi Wi-Fi Direct, ripetuta ogni 12 s);
//   2. toccato un monitor, entra nella sua rete come un normale apparecchio Wi-Fi (nessuna finestra di conferma sul
//      monitor, nessuna uscita dall'app), e la ricorda per i collegamenti successivi;
//   3. parla con la stessa API JSON del monitor (HTTP sull'indirizzo del monitor nel gruppo, di solito 192.168.49.1).
// Richiesta {method, path, headers, body} -> risposta {status, body}, come il ponte SFPeerLink dell'iPad.
package it.simulafranz.controller

import android.annotation.SuppressLint
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiConfiguration
import android.net.wifi.WifiManager
import android.net.wifi.p2p.WifiP2pManager
import android.net.wifi.p2p.nsd.WifiP2pDnsSdServiceRequest
import android.os.Looper
import it.simulafranz.core.Json
import it.simulafranz.core.SFLog
import it.simulafranz.core.SFPrefs
import it.simulafranz.uk.CACurrentMediaTime
import it.simulafranz.uk.NSTimer
import it.simulafranz.uk.Uk
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import java.util.concurrent.Executors

const val SFLinkIdle = 0
const val SFLinkConnecting = 1
const val SFLinkConnected = 2

private const val kService = "_sfmonitor._tcp"          // stesso valore in SFDirect.kt (monitor)
private const val kRequestTimeout = 3.0
private const val kKnownKey = "sf_known_monitors"        // monitor già visti: codice -> {name, id, ver, s, p, port}

class SFLinkMonitor {
    var name: String = ""        // es. "similFranz Monitor 7F3A"
    var deviceId: String = ""    // es. "monitor-7F3A"
    var version: String = ""
    // ANDROID: in più rispetto all'iPad
    var code: String = ""        // codice del tablet (distingue due monitor con lo stesso nome)
    var ssid: String = ""        // rete Wi-Fi Direct del monitor
    var pass: String = ""
    var port: Int = 8080
    var busy: String = ""        // nome del controller che lo sta comandando ("" = libero)
    var level: Int = 0           // segnale della sua rete in dBm (0 = sconosciuto): più alto = più vicino
    internal var seenAt = 0.0
    /** tacche del segnale 0…4 (0 = sconosciuto) */
    val bars: Int get() = if (level == 0) 0 else if (level >= -50) 4 else if (level >= -62) 3 else if (level >= -74) 2 else 1
}

class SFLink(private val ctx: Context, displayName: String, private val prefs: SFPrefs) {
    private val me: String = displayName.ifEmpty { "Controller" }
    private val wifi = ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
    private val cm = ctx.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    private val p2p = ctx.applicationContext.getSystemService(Context.WIFI_P2P_SERVICE) as? WifiP2pManager
    private var channel: WifiP2pManager.Channel? = null
    private val io = Executors.newFixedThreadPool(3) { r -> Thread(r, "it.simulafranz.link").apply { isDaemon = true } }

    private val found = ArrayList<SFLinkMonitor>()
    /** monitor visibili: prima l'ultimo usato, poi i più vicini (segnale più forte) */
    // ANDROID (audit 21/11): l'ultimo usato si riconosce dal CODICE del tablet, non dal nome (rinominato o due monitor con lo
    // stesso nome nell'armadio); il nome resta solo per i dati salvati da versioni vecchie, senza codice
    val monitors: List<SFLinkMonitor> get() = found.sortedWith(compareBy<SFLinkMonitor>({ if (isLastUsed(it)) 0 else 1 }, { if (it.level == 0) 1 else 0 }, { -it.level }, { it.name }))
    val lastUsed: String? get() = prefs.string("sf_monitor")
    val lastUsedCode: String? get() = prefs.string("sf_monitor_code")
    fun isLastUsed(m: SFLinkMonitor): Boolean { val c = lastUsedCode; return if (!c.isNullOrEmpty()) m.code == c else m.name == lastUsed }
    val isRunning: Boolean get() = running
    /** codice del monitor a cui si è collegati o ci si sta collegando */
    val targetCode: String? get() = target?.code
    var state: Int = SFLinkIdle; private set
    var connectedName: String? = null; private set
    var lastError: String? = null; private set
    /** ANDROID: a che punto è il collegamento, in parole semplici (per la schermata "Collegamento a …") */
    var phase: String = ""; private set
    private fun setPhase(p: String) { if (p != phase) { phase = p; changed() } }
    /** main thread: elenco o stato cambiati */
    var onChange: (() -> Unit)? = null

    private var running = false
    private var target: SFLinkMonitor? = null
    // ANDROID (audit 26/11, RETE-R12): scritti sul main thread, letti anche dai thread di rete e della Sala
    @Volatile private var network: Network? = null
    @Volatile private var host: String = "192.168.49.1"
    private var watch: NSTimer? = null
    private var joinTimer: NSTimer? = null
    private var joinStarted = 0.0
    private var probing = false
    private var session = 0                      // cambia a ogni collegamento: le risposte vecchie si ignorano
    private var fails = 0

    private fun changed() { onChange?.invoke() }
    // diagnosi del collegamento: lo stesso messaggio una volta sola di fila (il controllo gira ogni mezzo secondo)
    private var lastDiag: String? = null
    private fun diag(s: String) { if (s != lastDiag) { lastDiag = s; SFLog.log(s) } }

    // MARK: - Avvio

    fun start() {
        if (running) return
        running = true
        if (!wifi.isWifiEnabled) {
            @Suppress("DEPRECATION") val ok = try { wifi.setWifiEnabled(true) } catch (e: Exception) { false }
            SFLog.log(if (ok) "Wi-Fi acceso dall'app" else "Wi-Fi spento e non riattivabile dall'app")
        }
        if (p2p != null && channel == null) channel = p2p.initialize(ctx.applicationContext, Looper.getMainLooper()) { channel = null; SFLog.log("canale Wi-Fi Direct perso") }
        setupDiscovery()
        SFLog.log(String.format(Locale.US, "ricerca dei monitor avviata (io sono \"%s\")", me))
        watch?.invalidate()
        watch = NSTimer.scheduledTimerWithTimeInterval(12.0, true) { tick() }
        tick()
        cm.registerNetworkCallback(NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build(), netCb)
    }

    fun stop() {
        if (!running) return
        running = false
        watch?.invalidate(); watch = null
        try { cm.unregisterNetworkCallback(netCb) } catch (e: Exception) {}
        stopDiscovery()
        dropSession(null)
        found.clear()
        changed()
    }

    // MARK: - Ricerca

    private fun setupDiscovery() {
        val p = p2p ?: return; val ch = channel ?: return
        p.setDnsSdResponseListeners(ch, { _, _, _ -> }, { domain, txt, dev ->
            if (!domain.lowercase(Locale.ROOT).contains(kService)) return@setDnsSdResponseListeners
            if (txt["type"] != "monitor") return@setDnsSdResponseListeners
            val code = txt["code"] ?: dev.deviceAddress
            var m = found.firstOrNull { it.code == code }
            if (m == null) { m = SFLinkMonitor(); m.code = code; found.add(m); SFLog.log(String.format(Locale.US, "monitor trovato: \"%s\" (%s, v%s)", txt["name"] ?: "?", txt["id"] ?: "", txt["ver"] ?: "")) }
            m.name = txt["name"] ?: dev.deviceName ?: code
            m.deviceId = txt["id"] ?: ""; m.version = txt["ver"] ?: ""
            m.ssid = txt["s"] ?: ""; m.pass = txt["p"] ?: ""
            m.port = txt["port"]?.toIntOrNull() ?: 8080
            m.busy = txt["busy"] ?: ""
            m.seenAt = CACurrentMediaTime()
            readLevels()
            remember(m)
            changed()
        })
    }

    private fun stopDiscovery() {
        val p = p2p ?: return; val ch = channel ?: return
        try { p.clearServiceRequests(ch, null); p.stopPeerDiscovery(ch, null) } catch (e: Exception) {}
    }

    /** ogni 12 s: nuova ricerca (se non si è collegati), toglie i monitor non più visti da 45 s */
    private var idleTicks = 0; private var emptySince = 0.0
    private fun tick() {
        if (!running) return
        val now = CACurrentMediaTime()
        val before = found.size
        found.removeAll { now - it.seenAt > 45 && !(state != SFLinkIdle && it.name == connectedName) }
        if (found.size != before) changed()
        // ANDROID (audit 21/11): niente ricerca anche mentre ci si COLLEGA (prima solo da collegati): la ricerca Wi-Fi Direct fa
        // saltare la radio fra i canali e rallenta proprio l'ingresso nella rete del monitor durante un ricollegamento
        if (state != SFLinkIdle) return
        // ANDROID (audit 21/11, ANALISI_ESPERTO B9): nessun monitor da 2 minuti: una ricerca al minuto invece che ogni 12 s
        if (found.isEmpty()) { if (emptySince == 0.0) emptySince = now } else emptySince = 0.0
        if (emptySince > 0 && now - emptySince > 120 && (idleTicks++ % 5) != 0) return
        readLevels()
        @Suppress("DEPRECATION") try { wifi.startScan() } catch (e: Exception) {}     // segnale delle reti dei monitor: chi è più vicino
        val p = p2p ?: return; val ch = channel ?: return
        try {
            p.clearServiceRequests(ch, object : WifiP2pManager.ActionListener {
                override fun onSuccess() { addRequest() }
                override fun onFailure(reason: Int) { addRequest() }
            })
        } catch (e: SecurityException) { lastError = "manca il permesso di posizione (serve ad Android per Wi-Fi Direct)"; changed() }
    }
    private fun addRequest() {
        val p = p2p ?: return; val ch = channel ?: return
        try {
            p.addServiceRequest(ch, WifiP2pDnsSdServiceRequest.newInstance(), null)
            // i Samsung rispondono alla ricerca dei servizi solo con la ricerca dei dispositivi attiva
            p.discoverPeers(ch, null)
            p.discoverServices(ch, object : WifiP2pManager.ActionListener {
                override fun onSuccess() {}
                override fun onFailure(reason: Int) { if (state == SFLinkIdle) SFLog.log("ricerca Wi-Fi Direct non avviata (codice $reason)") }
            })
        } catch (e: SecurityException) { lastError = "manca il permesso di posizione (serve ad Android per Wi-Fi Direct)"; changed() }
    }

    // segnale delle reti "DIRECT-…" dei monitor dall'ultima scansione Wi-Fi (serve il permesso di posizione)
    private fun readLevels() {
        val res = try { wifi.scanResults } catch (e: SecurityException) { null } ?: return
        var ch = false
        for (m in found) {
            val r = res.firstOrNull { it.SSID == m.ssid } ?: continue
            if (r.level != m.level) { m.level = r.level; ch = true }
        }
        if (ch) changed()
    }

    // monitor già visti: si ricordano le credenziali, così il ricollegamento funziona anche se la ricerca tace
    private fun remember(m: SFLinkMonitor) {
        @Suppress("UNCHECKED_CAST") val all = LinkedHashMap((prefs.json(kKnownKey) as? Map<String, Any?>) ?: emptyMap())
        val d = linkedMapOf<String, Any?>("name" to m.name, "id" to m.deviceId, "ver" to m.version, "s" to m.ssid, "p" to m.pass, "port" to m.port.toLong())
        if (all[m.code] != d) { all[m.code] = d; prefs.setJson(kKnownKey, all) }
    }
    /** monitor ricordato con quel nome (anche se ora non è nell'elenco) */
    fun known(name: String?): SFLinkMonitor? = knownWhere { code, d -> d["name"] == name && name != null }
    /** ANDROID (audit 21/11): monitor ricordato con quel CODICE (identità del tablet, non cambia rinominandolo) */
    fun knownCode(code: String?): SFLinkMonitor? = knownWhere { c, _ -> code != null && c == code }
    private fun knownWhere(match: (String, Map<*, *>) -> Boolean): SFLinkMonitor? {
        @Suppress("UNCHECKED_CAST") val all = (prefs.json(kKnownKey) as? Map<String, Any?>) ?: return null
        for ((code, v) in all) {
            val d = v as? Map<*, *> ?: continue
            if (!match(code, d)) continue
            val m = SFLinkMonitor(); m.code = code; m.name = d["name"] as? String ?: code
            m.deviceId = d["id"] as? String ?: ""; m.version = d["ver"] as? String ?: ""
            m.ssid = d["s"] as? String ?: ""; m.pass = d["p"] as? String ?: ""; m.port = (d["port"] as? Number)?.toInt() ?: 8080
            return m
        }
        return null
    }

    // MARK: - Collegamento

    fun connectTo(monitor: SFLinkMonitor) {
        // ANDROID (audit 21/11): mai collegarsi a ricerca ferma (app in secondo piano): stop() avvisa del cambio di stato e il
        // modello provava subito a ricollegarsi, lasciando un tentativo di collegamento attivo con l'app chiusa
        if (!running) return
        if (monitor.ssid.isEmpty()) { lastError = "il monitor non ha ancora la sua rete Wi-Fi Direct"; changed(); return }
        teardown()
        target = monitor; connectedName = monitor.name; state = SFLinkConnecting; lastError = null; phase = "1 di 2 · Entro nella rete del monitor…"
        session++
        SFLog.log(String.format(Locale.US, "collegamento a \"%s\" (rete %s)", monitor.name, monitor.ssid))
        join(monitor)
        changed()
        joinStarted = CACurrentMediaTime()
        joinTimer?.invalidate()
        joinTimer = NSTimer.scheduledTimerWithTimeInterval(0.5, true) { checkJoin() }
    }

    @SuppressLint("MissingPermission")
    @Suppress("DEPRECATION")
    private fun join(m: SFLinkMonitor) {
        if (!wifi.isWifiEnabled) try { wifi.setWifiEnabled(true) } catch (e: Exception) {}
        val q = "\"" + m.ssid + "\""
        val cur = wifi.connectionInfo
        if (cur != null && cur.ssid == q && cur.ipAddress != 0) return              // già nella rete giusta
        var id = -1
        try { for (c in wifi.configuredNetworks ?: emptyList()) if (c.SSID == q) { id = c.networkId; break } } catch (e: SecurityException) {}
        val conf = WifiConfiguration()
        conf.SSID = q; conf.preSharedKey = "\"" + m.pass + "\""
        conf.allowedKeyManagement.set(WifiConfiguration.KeyMgmt.WPA_PSK)
        if (id >= 0) { conf.networkId = id; val u = wifi.updateNetwork(conf); if (u >= 0) id = u }
        else id = wifi.addNetwork(conf)
        if (id < 0) { SFLog.log("rete del monitor non aggiunta"); return }
        wifi.enableNetwork(id, true)
        wifi.reconnect()
    }

    private fun gateway(): String? {
        @Suppress("DEPRECATION") val ci = wifi.connectionInfo ?: return null
        if (ci.ipAddress == 0) return null
        @Suppress("DEPRECATION") val g = wifi.dhcpInfo?.gateway ?: 0
        if (g == 0) return null
        return String.format(Locale.US, "%d.%d.%d.%d", g and 0xff, (g shr 8) and 0xff, (g shr 16) and 0xff, (g shr 24) and 0xff)
    }
    // la rete Android che ha DAVVERO un indirizzo nella rete del monitor (192.168.49.x): Android tiene in elenco anche
    // reti Wi-Fi di tentativi precedenti già chiuse, e legarsi a una di quelle fallisce ("Machine is not on the network")
    private fun wifiNetwork(): Network? {
        for (n in cm.allNetworks) {
            val c = cm.getNetworkCapabilities(n) ?: continue
            if (!c.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) continue
            val lp = cm.getLinkProperties(n) ?: continue
            if (lp.linkAddresses.any { it.address.hostAddress?.startsWith("192.168.49.") == true }) return n
        }
        return null
    }

    // mentre ci si collega: rete del monitor raggiunta? allora si chiede /api/v1/health (libero, non conta come PIN errato)
    private fun checkJoin() {
        val m = target
        if (state != SFLinkConnecting || m == null) { joinTimer?.invalidate(); joinTimer = null; return }
        if (CACurrentMediaTime() - joinStarted > 25) { joinTimer?.invalidate(); joinTimer = null; dropSession("il monitor non ha risposto"); return }
        if (probing) return
        val gw = gateway()
        val net = wifiNetwork()
        val el = CACurrentMediaTime() - joinStarted
        if (gw == null || net == null) {
            diag("in attesa della rete del monitor (gateway=$gw, rete=${net != null})")
            setPhase(if (el < 8) "1 di 2 · Entro nella rete del monitor…" else "1 di 2 · La rete del monitor non risponde: il monitor è acceso e vicino?")
            return
        }
        setPhase(if (el < 6) "2 di 2 · Rete del monitor raggiunta, lo chiamo…" else "2 di 2 · Il monitor non risponde: sul monitor l'app similFranz Monitor deve essere aperta.")
        probing = true
        val s = session
        io.execute {
            val r = http(net, gw, m.port, "GET", "/api/v1/health", null, null)
            Uk.async {
                probing = false
                if (s != session || state != SFLinkConnecting) return@async
                val b = r.second
                diag("prova del monitor su $gw:${m.port} -> ${r.first} ${b?.get("device_id") ?: ""}")
                if (r.first == 200 && b?.get("service") == "SimulaFranz_Monitor" && (m.deviceId.isEmpty() || b["device_id"] == m.deviceId)) {
                    joinTimer?.invalidate(); joinTimer = null
                    network = net; host = gw
                    state = SFLinkConnected; lastError = null; fails = 0
                    SFLog.log(String.format(Locale.US, "COLLEGATO a \"%s\" (%s:%d)", m.name, gw, m.port))
                    stopDiscovery()
                    changed()
                }
            }
        }
    }

    fun disconnect() {
        val m = target
        dropSession(null)
        // il controller lascia la rete del monitor (scollegato a mano: non ci si deve ricollegare da soli)
        if (m != null) leave(m)
    }
    @Suppress("DEPRECATION")
    private fun leave(m: SFLinkMonitor) {
        try {
            val q = "\"" + m.ssid + "\""
            for (c in wifi.configuredNetworks ?: emptyList()) if (c.SSID == q) wifi.disableNetwork(c.networkId)
            if (wifi.connectionInfo?.ssid == q) wifi.disconnect()
            // le altre reti salvate tornano disponibili (enableNetwork(…, true) le aveva disattivate)
            for (c in wifi.configuredNetworks ?: emptyList()) if (c.SSID != q) wifi.enableNetwork(c.networkId, false)
        } catch (e: SecurityException) {}
    }

    /** Il collegamento sembra vivo ma il monitor non risponde più: lo si chiude e si riparte da una ricerca pulita. */
    fun resetAfterSilence() {
        if (state != SFLinkIdle) { SFLog.log("il monitor non risponde: collegamento chiuso e ricerca riavviata"); dropSession("il monitor non rispondeva più") }
    }

    // chiude senza avvisare nessuno (chi chiama decide quando notificare il cambiamento)
    private fun teardown() {
        joinTimer?.invalidate(); joinTimer = null
        session++
        target = null; network = null; state = SFLinkIdle; connectedName = null; probing = false
    }
    private fun dropSession(error: String?) {
        teardown()
        if (error != null) {
            lastError = error
            fails++
            // si riparte con una ricerca pulita dopo una breve pausa (il monitor ricompare quando è raggiungibile)
            Uk.after(2.5) { if (running && state == SFLinkIdle) tick() }
        }
        changed()
    }

    // la rete Wi-Fi è caduta mentre si era collegati
    private val netCb = object : ConnectivityManager.NetworkCallback() {
        override fun onLost(n: Network) {
            Uk.async { if (state == SFLinkConnected && n == network) { SFLog.log(String.format(Locale.US, "collegamento con \"%s\" chiuso", connectedName)); dropSession("collegamento interrotto") } }
        }
    }

    // MARK: - Richieste

    /** status 0 = nessuna risposta (collegamento caduto o tempo scaduto). Completion sul main thread. */
    fun request(method: String, path: String, headers: Map<String, String>?, body: Map<String, Any?>?, completion: ((Int, Map<String, Any?>?) -> Unit)?) {
        val net = network; val m = target
        if (state != SFLinkConnected || net == null || m == null) { completion?.invoke(0, null); return }
        val s = session; val h = host
        io.execute {
            val r = http(net, h, m.port, method, path, headers, body)
            Uk.async { if (s != session) completion?.invoke(0, null) else completion?.invoke(r.first, r.second) }
        }
    }

    // ANDROID (sala 22/11): flusso in diretta della «Sala» (MJPEG / audio) su una connessione tutta sua, fuori dal gruppo di
    // thread dei comandi (che restano sempre liberi). Da chiamare FUORI dal main thread. null = non collegati o errore;
    // il chiamante legge c.inputStream e chiude con c.disconnect(). Con il collegamento cambiato liveSession() cambia.
    fun openStream(path: String, headers: Map<String, String>, readTimeoutMs: Int): HttpURLConnection? {
        val net = network; val m = target
        if (state != SFLinkConnected || net == null || m == null) return null
        return try {
            val c = net.openConnection(URL("http://$host:${m.port}$path")) as HttpURLConnection
            c.connectTimeout = 3000; c.readTimeout = readTimeoutMs; c.useCaches = false
            for ((k, v) in headers) c.setRequestProperty(k, v)
            c.connect(); c
        } catch (e: Exception) { SFLog.log("sala: flusso non aperto: $e"); null }
    }
    fun liveSession(): Int = if (state == SFLinkConnected) session else -1

    private fun http(net: Network, h: String, port: Int, method: String, path: String, headers: Map<String, String>?, body: Map<String, Any?>?): Pair<Int, Map<String, Any?>?> {
        var c: HttpURLConnection? = null
        return try {
            c = net.openConnection(URL("http://$h:$port$path")) as HttpURLConnection
            val ms = (kRequestTimeout * 1000).toInt()
            c.connectTimeout = ms; c.readTimeout = ms
            c.requestMethod = method; c.useCaches = false
            c.setRequestProperty("Accept", "application/json")
            if (headers != null) for ((k, v) in headers) c.setRequestProperty(k, v)
            if (body != null) {
                val d = Json.stringify(body).toByteArray(Charsets.UTF_8)
                c.doOutput = true
                c.setRequestProperty("Content-Type", "application/json")
                c.setFixedLengthStreamingMode(d.size)
                c.outputStream.use { it.write(d) }
            }
            val st = c.responseCode
            val stream = if (st >= 400) c.errorStream else c.inputStream
            val txt = stream?.use { it.readBytes().toString(Charsets.UTF_8) } ?: ""
            @Suppress("UNCHECKED_CAST") val j = Json.parseOrNull(txt) as? Map<String, Any?>
            Pair(st, j ?: emptyMap())
        } catch (e: Exception) {
            if (path == "/api/v1/health") Uk.async { diag("richiesta fallita: $e") }
            Pair(0, null)
        } finally { try { c?.disconnect() } catch (e: Exception) {} }
    }
}
