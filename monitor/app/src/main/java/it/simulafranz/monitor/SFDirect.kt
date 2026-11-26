// ANDROID: al posto di src/SFPeerLink.m (Multipeer, monitor iPad). Collegamento DIRETTO con le app controller, senza
// nessuna rete Wi-Fi della sala e senza uscire dall'app:
//   - all'avvio il monitor crea da solo un gruppo Wi-Fi Direct (ne è il "proprietario": una piccola rete "DIRECT-…"
//     con password, sempre la stessa da un avvio all'altro);
//   - lo annuncia con la ricerca dei servizi Wi-Fi Direct (DNS-SD, tipo _sfmonitor._tcp): nome, codice, versione e le
//     credenziali della rete. Il controller le legge, entra nella rete come un normale apparecchio Wi-Fi (nessuna
//     finestra di conferma qui) e parla con la stessa API del server HTTP (stessa porta, indirizzo 192.168.49.1);
//   - le richieste che arrivano dalla rete Wi-Fi Direct (192.168.49.x) contano come "collegamento diretto"
//     (indirizzo "peer:…" per l'APIController, come quelle del ponte Multipeer sull'iPad).
// La rete Wi-Fi normale del tablet (regia web dal computer) resta disponibile in parallelo.
// La password viaggia in chiaro nell'annuncio: la protezione resta il PIN, come sull'iPad (Multipeer senza cifratura).
package it.simulafranz.monitor

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.wifi.WifiManager
import android.net.wifi.p2p.WifiP2pGroup
import android.net.wifi.p2p.WifiP2pManager
import android.net.wifi.p2p.nsd.WifiP2pDnsSdServiceInfo
import android.os.Looper
import it.simulafranz.core.SFLog
import it.simulafranz.uk.CACurrentMediaTime
import it.simulafranz.uk.NSTimer
import it.simulafranz.uk.Uk
import java.util.Locale

private const val kService = "_sfmonitor._tcp"           // stesso valore in SFLink.kt (controller)

class SFDirect(private val ctx: Context) {
    private val p2p = ctx.applicationContext.getSystemService(Context.WIFI_P2P_SERVICE) as? WifiP2pManager
    private val wifi = ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
    private var channel: WifiP2pManager.Channel? = null
    private var running = false
    private var service: WifiP2pDnsSdServiceInfo? = null
    private var timer: NSTimer? = null
    private var creating = false

    /** dati dell'annuncio, chiesti ogni volta (il nome può cambiare): name, id, ver, code, port */
    var announce: (() -> Map<String, String>)? = null
    var ssid: String? = null; private set
    var passphrase: String? = null; private set
    var lastError: String? = null; private set
    val isOn: Boolean get() = ssid != null && lastError == null
    /** stato cambiato (main thread): il pannello (i) si aggiorna */
    var onChange: (() -> Unit)? = null

    // controller collegati in diretto: nome -> ultima richiesta (li mostra il pannello come "Collegato: …")
    private val seen = LinkedHashMap<String, Double>()
    // ANDROID (audit 21/11, ANALISI_ESPERTO B3): con un controller collegato in diretto NIENTE ricerca Wi-Fi Direct. Una ricerca
    // (discoverPeers) dura fino a 2 minuti e fa saltare la radio da un canale all'altro: il controller perdeva letture e ogni
    // minuto circa il collegamento cadeva («il monitor non risponde»). Al primo controller la ricerca si ferma; riparte (al
    // giro del timer) solo quando da 6 s non arriva più nessuna richiesta, cioè quando serve di nuovo farsi trovare.
    @Synchronized fun noteClient(name: String) {
        val now = CACurrentMediaTime()
        val first = seen.values.none { now - it <= 6 }
        seen[name] = now
        if (first) Uk.async { quiet() }
    }
    @Synchronized fun peers(): List<String> { val now = CACurrentMediaTime(); seen.entries.removeAll { now - it.value > 6 }; return seen.keys.toList() }
    @Synchronized private fun hasClient(): Boolean { val now = CACurrentMediaTime(); return seen.values.any { now - it <= 6 } }
    private var searching = false
    private fun quiet() {
        val p = p2p ?: return; val ch = channel ?: return
        if (!running || !searching) return
        searching = false
        try { p.stopPeerDiscovery(ch, null) } catch (e: Exception) {}
        SFLog.log("collegamento diretto: controller collegato, ricerca Wi-Fi Direct ferma")
    }

    fun start() {
        if (running) return
        running = true
        if (p2p == null) { lastError = "questo tablet non ha Wi-Fi Direct"; onChange?.invoke(); return }
        if (!wifi.isWifiEnabled) {
            @Suppress("DEPRECATION") val ok = try { wifi.setWifiEnabled(true) } catch (e: Exception) { false }
            SFLog.log(if (ok) "Wi-Fi acceso dall'app (serve al collegamento diretto)" else "Wi-Fi spento: collegamento diretto non disponibile")
        }
        if (channel == null) channel = p2p.initialize(ctx.applicationContext, Looper.getMainLooper()) { channel = null; SFLog.log("canale Wi-Fi Direct perso"); if (running) Uk.after(2.0) { restart() } }
        try { ctx.applicationContext.registerReceiver(rx, IntentFilter(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION).apply { addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION) }) } catch (e: Exception) {}
        ensureGroup()
        // ogni 20 s: il gruppo c'è ancora? e una breve ricerca, senza la quale i Samsung non rispondono alle ricerche dei controller
        timer?.invalidate()
        timer = NSTimer.scheduledTimerWithTimeInterval(20.0, true) { ensureGroup(); listen() }     // listen() salta se c'è un controller
    }

    fun stop() {
        if (!running) return
        running = false
        timer?.invalidate(); timer = null
        try { ctx.applicationContext.unregisterReceiver(rx) } catch (e: Exception) {}
        val p = p2p; val ch = channel
        if (p != null && ch != null) {
            try { p.clearLocalServices(ch, null); p.stopPeerDiscovery(ch, null) } catch (e: Exception) {}
            searching = false
            // il gruppo resta: chiuderlo ogni volta che l'app va in secondo piano farebbe cadere i controller.
        }
        service = null
    }

    private fun restart() { if (!running) return; running = false; start() }

    /** nome cambiato dal pannello: l'annuncio riparte con il nome nuovo */
    fun republish() { service = null; publish() }

    private val rx = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) { if (running) Uk.after(0.5) { ensureGroup() } }
    }

    private fun ensureGroup() {
        val p = p2p ?: return; val ch = channel ?: return
        if (creating) return
        try {
            p.requestGroupInfo(ch) { g: WifiP2pGroup? ->
                if (g != null && g.isGroupOwner) { have(g); return@requestGroupInfo }
                if (g != null) {      // in un gruppo altrui (non dovrebbe capitare): lo si lascia e si crea il proprio
                    p.removeGroup(ch, null); Uk.after(1.5) { ensureGroup() }; return@requestGroupInfo
                }
                creating = true
                p.createGroup(ch, object : WifiP2pManager.ActionListener {
                    override fun onSuccess() { creating = false; SFLog.log("collegamento diretto: rete Wi-Fi Direct creata"); Uk.after(1.0) { ensureGroup() } }
                    override fun onFailure(reason: Int) {
                        creating = false
                        lastError = when (reason) { WifiP2pManager.BUSY -> "Wi-Fi Direct occupato, nuovo tentativo"; WifiP2pManager.P2P_UNSUPPORTED -> "Wi-Fi Direct non supportato"; else -> "Wi-Fi Direct non avviato (Wi-Fi acceso?)" }
                        SFLog.log("collegamento diretto: rete non creata (codice $reason)")
                        onChange?.invoke()
                        if (running) Uk.after(5.0) { ensureGroup() }
                    }
                })
            }
        } catch (e: SecurityException) { lastError = "manca il permesso di posizione"; onChange?.invoke() }
    }

    private fun have(g: WifiP2pGroup) {
        val changed = g.networkName != ssid || g.passphrase != passphrase
        ssid = g.networkName; passphrase = g.passphrase; lastError = null
        if (changed) { SFLog.log(String.format(Locale.US, "collegamento diretto: rete %s pronta", ssid)); service = null }
        publish()
        if (changed) onChange?.invoke()
    }

    private fun publish() {
        val p = p2p ?: return; val ch = channel ?: return
        if (service != null || ssid == null) return
        val a = announce?.invoke() ?: return
        val txt = HashMap<String, String>()
        // ANDROID (fix 21/11): il TXT deve restare CORTO. Con «busy» = «similFranz Controller F2D4» la risposta superava il limite
        // e il Wi-Fi Direct Samsung del controller la scartava («Malformed bonjour service response»): monitor in uso = invisibile.
        // Niente "api" (sottinteso v1), nomi senza il prefisso «similFranz », «busy» al massimo 20 caratteri.
        txt["type"] = "monitor"
        for (k in listOf("name", "id", "ver", "code", "port", "busy")) a[k]?.let {
            val v = it.removePrefix("similFranz ").replace("=", "-")
            if (v.isNotEmpty()) txt[k] = v.take(if (k == "busy") 20 else 40)
        }
        // ANDROID (fix 21/11, causa vera): Android scarta TUTTA la risposta se un campo TXT ha il valore vuoto («busy=»:
        // split("=") dà un solo pezzo → «Malformed bonjour service response»). Un monitor LIBERO era quindi invisibile.
        // Mai campi vuoti né «=» nei valori; il controller legge l'assenza di «busy» come «libero».
        ssid?.takeIf { it.isNotEmpty() }?.let { txt["s"] = it }; passphrase?.takeIf { it.isNotEmpty() }?.let { txt["p"] = it }
        val inst = (a["code"] ?: "monitor").lowercase(Locale.ROOT)
        val info = WifiP2pDnsSdServiceInfo.newInstance("similfranz-$inst", kService, txt)
        service = info
        try {
            p.clearLocalServices(ch, object : WifiP2pManager.ActionListener {
                override fun onSuccess() { add(info) }
                override fun onFailure(reason: Int) { add(info) }
            })
        } catch (e: SecurityException) { service = null }
    }
    private fun add(info: WifiP2pDnsSdServiceInfo) {
        val p = p2p ?: return; val ch = channel ?: return
        p.addLocalService(ch, info, object : WifiP2pManager.ActionListener {
            override fun onSuccess() { SFLog.log("collegamento diretto: annuncio pubblicato"); listen() }
            override fun onFailure(reason: Int) { SFLog.log("collegamento diretto: annuncio NON pubblicato (codice $reason)"); if (service === info) service = null }
        })
    }
    private fun listen() {
        val p = p2p ?: return; val ch = channel ?: return
        if (hasClient()) { quiet(); return }          // ANDROID (audit 21/11): collegati, la ricerca farebbe perdere il canale
        if (!searching) SFLog.log("collegamento diretto: ricerca Wi-Fi Direct (per farsi trovare dai controller)")
        searching = true
        try { p.discoverPeers(ch, null) } catch (e: SecurityException) {}
    }

    companion object {
        /** indirizzo di provenienza nella rete Wi-Fi Direct (192.168.49.x) */
        fun isDirectAddress(a: String?): Boolean = a != null && a.startsWith("192.168.49.")
    }
}
