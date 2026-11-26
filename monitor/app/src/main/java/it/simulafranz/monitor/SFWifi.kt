// Portato da src/SFWifi.m (monitor iPad)
// similFranz Monitor — Wi-Fi gestito dall'app (elenco reti, collegamento, password) senza passare da Impostazioni.
// ANDROID: sull'iPad usa MobileWiFi.framework (funzioni private); qui la stessa interfaccia è fatta con
// android.net.wifi.WifiManager (Android 9, targetSdk 28): startScan + SCAN_RESULTS_AVAILABLE, addNetwork + enableNetwork.
// Per leggere l'elenco delle reti Android 9 chiede il permesso di posizione (lo chiede MainActivity alla prima
// apertura della pagina «Reti Wi-Fi») e la Posizione accesa. Se qualcosa manca resta il tasto che apre le impostazioni.
@file:Suppress("DEPRECATION")

package it.simulafranz.monitor

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.NetworkInfo
import android.net.wifi.ScanResult
import android.net.wifi.SupplicantState
import android.net.wifi.WifiConfiguration
import android.net.wifi.WifiManager
import android.os.Build
import android.provider.Settings
import it.simulafranz.core.SFLog
import it.simulafranz.uk.Uk
import java.util.Locale

class SFWifiNetwork {
    var ssid: String = ""
    var rssi: Int = 0; var channel: Int = 0             // dBm, canale (>14 = 5 GHz)
    var secured: Boolean = false; var known: Boolean = false; var current: Boolean = false   // con password / già salvata sul tablet / collegata ora
    val bars: Int get() = if (rssi >= -60) 3 else (if (rssi >= -75) 2 else 1)               // 1..3
}

class SFWifi private constructor() {
    companion object {
        private val s: SFWifi by lazy { SFWifi() }
        fun shared(): SFWifi = s
        /** come `SFWifi.shared()` (comodo da scrivere) */
        val shared: SFWifi get() = s

        // codici d'errore del collegamento (stessi testi dell'iPad)
        private const val kErrTimeout = -1          // nostro tempo scaduto
        private const val kErrAuth = -3924          // autenticazione rifiutata (sull'iPad: password sbagliata)
        private const val kErrConfig = -2           // ANDROID: addNetwork rifiutato
        private const val kErrEnable = -3           // ANDROID: enableNetwork rifiutato
    }

    private val ctx: Context = Uk.ctx.applicationContext
    private val mgr: WifiManager? = try { ctx.getSystemService(Context.WIFI_SERVICE) as? WifiManager } catch (e: Exception) { null }
    private val refs = HashMap<String, ScanResult>()      // ssid -> rete dell'ultima scansione
    private var joinDoneBlock: ((Boolean, String?) -> Unit)? = null
    private var joinNet: Int = -1                          // networkId della rete a cui ci si sta collegando
    private var joinCreated = false                        // la rete è stata salvata da questo tentativo (se fallisce si toglie)
    private var scanToken = 0

    val available: Boolean
    var scanning: Boolean = false; private set
    var joining: Boolean = false; private set
    var joiningSSID: String? = null; private set
    var lastError: String? = null; private set
    var networks: List<SFWifiNetwork> = emptyList(); private set    // ultimo elenco (la più forte per ogni nome)
    var onChange: (() -> Unit)? = null                               // elenco o stato cambiati (main thread)

    /**
     * ANDROID: chiede all'utente il permesso di posizione (serve per l'elenco delle reti) e richiama il blocco con l'esito.
     * Lo imposta MainActivity; la richiesta parte alla prima ricerca, cioè alla prima apertura di «Reti Wi-Fi».
     */
    var requestLocationPermission: (((Boolean) -> Unit) -> Unit)? = null

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, intent: Intent?) {
            when (intent?.action) {
                WifiManager.SCAN_RESULTS_AVAILABLE_ACTION -> if (scanning) scanDone(results(), 0)
                WifiManager.SUPPLICANT_STATE_CHANGED_ACTION -> {
                    if (joining && intent.getIntExtra(WifiManager.EXTRA_SUPPLICANT_ERROR, -1) == WifiManager.ERROR_AUTHENTICATING) joinDone(kErrAuth)
                }
                WifiManager.NETWORK_STATE_CHANGED_ACTION -> {
                    ssidAt = -10.0
                    val ni = intent.getParcelableExtra<NetworkInfo>(WifiManager.EXTRA_NETWORK_INFO)
                    if (joining && ni != null && ni.isConnected && mgr?.connectionInfo?.networkId == joinNet) joinDone(0)
                }
            }
        }
    }

    init {
        available = mgr != null && ctx.packageManager.hasSystemFeature(PackageManager.FEATURE_WIFI)
        if (available) {
            val f = IntentFilter()
            f.addAction(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION)
            f.addAction(WifiManager.SUPPLICANT_STATE_CHANGED_ACTION)
            f.addAction(WifiManager.NETWORK_STATE_CHANGED_ACTION)
            try { ctx.registerReceiver(receiver, f) } catch (e: Exception) { SFLog.log("wifi: avvisi di sistema non disponibili: $e") }
        }
        SFLog.log(String.format(Locale.US, "wifi in app: %s%s", if (available) "disponibile" else "NON disponibile",
            if (mgr == null) " — WifiManager non disponibile" else ""))
    }

    private fun changed() { onChange?.invoke() }

    private fun unquote(s: String?): String? {
        if (s == null) return null
        return if (s.length >= 2 && s.startsWith("\"") && s.endsWith("\"")) s.substring(1, s.length - 1) else s
    }
    // ANDROID (audit 26/11, lint): il permesso di posizione si chiede prima (askLocation); senza, la SecurityException è presa qui
    @android.annotation.SuppressLint("MissingPermission")
    private fun configured(): List<WifiConfiguration> = try { mgr?.configuredNetworks ?: emptyList() } catch (e: Exception) { emptyList() }
    @android.annotation.SuppressLint("MissingPermission")
    private fun results(): List<ScanResult> = try { mgr?.scanResults ?: emptyList() } catch (e: Exception) { emptyList() }

    // ANDROID: il pannello (i) rilegge il nome della rete a ogni fotogramma: la risposta del sistema vale per 1 secondo
    private var ssidCache: String? = null; private var ssidAt = -10.0
    fun currentSSID(): String? {
        val now = System.nanoTime() / 1e9
        if (now - ssidAt < 1.0) return ssidCache
        ssidCache = readSSID(); ssidAt = now
        return ssidCache
    }
    private fun readSSID(): String? {
        if (!available) return null
        val info = try { mgr?.connectionInfo } catch (e: Exception) { null } ?: return null
        if (info.networkId == -1 || info.supplicantState != SupplicantState.COMPLETED) return null
        val s = unquote(info.ssid)
        if (!s.isNullOrEmpty() && s != "<unknown ssid>" && s != "0x") return s
        // ANDROID: senza permesso di posizione il nome non viene detto: lo si ritrova fra le reti salvate
        for (c in configured()) if (c.networkId == info.networkId) return unquote(c.SSID)
        return null
    }
    fun powerOn(): Boolean {                                                      // Wi-Fi del tablet acceso?
        if (!available) return false
        return try { mgr?.isWifiEnabled ?: false } catch (e: Exception) { false }
    }
    fun setPower(on: Boolean) { if (available) try { mgr?.isWifiEnabled = on } catch (e: Exception) { SFLog.log("wifi: accensione non riuscita: $e") } }

    private fun knownSSIDs(): Set<String> {
        val s = HashSet<String>()
        for (c in configured()) { val n = unquote(c.SSID); if (n != null) s.add(n) }
        return s
    }

    // ANDROID: permesso e Posizione, richiesti dal sistema per leggere l'elenco delle reti
    private fun hasLocationPermission(): Boolean =
        ctx.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
    private fun locationOn(): Boolean {
        return try {
            if (Build.VERSION.SDK_INT >= 28) (ctx.getSystemService(Context.LOCATION_SERVICE) as LocationManager).isLocationEnabled
            else Settings.Secure.getInt(ctx.contentResolver, Settings.Secure.LOCATION_MODE, Settings.Secure.LOCATION_MODE_OFF) != Settings.Secure.LOCATION_MODE_OFF
        } catch (e: Exception) { true }
    }

    fun scan() {
        if (!available || scanning) return
        scanning = true; lastError = null
        if (!hasLocationPermission()) {
            val ask = requestLocationPermission
            if (ask == null) { scanDenied(); return }
            changed()
            ask { ok -> scanning = false; if (ok) scan() else scanDenied() }
            return
        }
        if (!locationOn()) {
            scanning = false
            lastError = "Posizione del tablet spenta: Android la richiede per elencare le reti Wi-Fi. Accenderla (pannello rapido in alto) e toccare «Aggiorna elenco»."
            SFLog.log("wifi: $lastError"); changed(); return
        }
        val token = ++scanToken
        if (!powerOn()) {                                             // ANDROID: Wi-Fi spento: si accende da solo, poi si cerca
            SFLog.log("wifi: Wi-Fi spento, lo accendo")
            setPower(true)
            changed()
            Uk.after(3.0) { if (scanning && token == scanToken) startScan(token) }
            return
        }
        startScan(token)
        changed()
    }
    private fun scanDenied() {
        scanning = false
        lastError = "Permesso di posizione negato: Android lo richiede per elencare le reti Wi-Fi. Concederlo in Impostazioni › App › similFranz Monitor › Autorizzazioni, oppure usare «Impostaz. tablet»."
        SFLog.log("wifi: $lastError"); changed()
    }
    private fun startScan(token: Int) {
        val r = try { if (mgr?.startScan() == true) 0 else 1 } catch (e: Exception) { SFLog.log("wifi: startScan: $e"); 2 }
        if (r == 2) { scanning = false; lastError = String.format(Locale.US, "ricerca non avviata (%d)", r); SFLog.log("wifi: $lastError"); changed(); return }
        if (r == 1) {
            // ANDROID: il sistema limita le ricerche (4 ogni 2 minuti): si mostra l'elenco dell'ultima ricerca
            SFLog.log("wifi: ricerca limitata dal sistema, uso l'ultimo elenco")
            Uk.async { if (scanning && token == scanToken) scanDone(results(), 0) }
            return
        }
        Uk.after(12.0) { if (scanning && token == scanToken) scanDone(results(), 0) }   // nessun avviso dal sistema: non restare appesi
    }
    private fun scanDone(results: List<ScanResult>?, err: Int) {
        scanning = false; scanToken++
        if (err != 0) { lastError = String.format(Locale.US, "ricerca fallita (%d)", err); SFLog.log("wifi: $lastError"); changed(); return }
        val known = knownSSIDs(); val cur = currentSSID()
        val best = HashMap<String, SFWifiNetwork>()
        refs.clear()
        for (n in results ?: emptyList()) {
            val ssid = n.SSID
            if (ssid.isNullOrEmpty()) continue                           // reti nascoste: non elencate
            val rssi = n.level
            var o = best[ssid]
            if (o != null && o.rssi >= rssi) continue
            o = SFWifiNetwork(); o.ssid = ssid; o.rssi = rssi
            o.channel = channelFor(n.frequency)
            o.secured = isWPA(n) || isWEP(n) || isEAP(n)
            o.known = known.contains(ssid); o.current = ssid == cur
            best[ssid] = o; refs[ssid] = n
        }
        networks = best.values.sortedWith(Comparator { a, b ->
            if (a.current != b.current) return@Comparator if (a.current) -1 else 1
            if (a.known != b.known) return@Comparator if (a.known) -1 else 1
            if (a.rssi > b.rssi) -1 else (if (a.rssi < b.rssi) 1 else a.ssid.compareTo(b.ssid))
        })
        SFLog.log(String.format(Locale.US, "wifi: %d reti trovate", networks.size))
        changed()
    }

    private fun channelFor(mhz: Int): Int = when {
        mhz == 2484 -> 14
        mhz in 2412..2472 -> (mhz - 2407) / 5
        mhz >= 5000 -> (mhz - 5000) / 5
        else -> 0
    }
    private fun isWPA(n: ScanResult): Boolean { val c = n.capabilities ?: ""; return c.contains("PSK") || c.contains("SAE") || (c.contains("WPA") && !c.contains("EAP")) }
    private fun isWEP(n: ScanResult): Boolean = (n.capabilities ?: "").contains("WEP")
    private fun isEAP(n: ScanResult): Boolean = (n.capabilities ?: "").contains("EAP")

    private fun configFor(n: ScanResult, password: String?): WifiConfiguration? {
        val c = WifiConfiguration()
        c.SSID = "\"" + n.SSID + "\""
        if (isEAP(n)) return null                                        // reti aziendali (802.1X): non gestite dall'app
        if (isWPA(n)) {
            if (password.isNullOrEmpty()) return null
            c.allowedKeyManagement.set(WifiConfiguration.KeyMgmt.WPA_PSK)
            c.preSharedKey = if (password.length == 64 && password.all { Character.digit(it, 16) >= 0 }) password else "\"" + password + "\""
        } else if (isWEP(n)) {
            if (password.isNullOrEmpty()) return null
            c.allowedKeyManagement.set(WifiConfiguration.KeyMgmt.NONE)
            c.allowedAuthAlgorithms.set(WifiConfiguration.AuthAlgorithm.OPEN); c.allowedAuthAlgorithms.set(WifiConfiguration.AuthAlgorithm.SHARED)
            val hex = (password.length == 10 || password.length == 26) && password.all { Character.digit(it, 16) >= 0 }
            c.wepKeys[0] = if (hex) password else "\"" + password + "\""; c.wepTxKeyIndex = 0
        } else {
            c.allowedKeyManagement.set(WifiConfiguration.KeyMgmt.NONE)
        }
        return c
    }

    fun join(net: SFWifiNetwork, password: String?, completion: ((Boolean, String?) -> Unit)?) {
        val done = completion
        val n = refs[net.ssid]
        val m = mgr
        if (!available || n == null || m == null) { done?.invoke(false, "rete non più visibile: aggiornare l'elenco"); return }
        if (joining) { done?.invoke(false, "collegamento già in corso"); return }
        var saved: WifiConfiguration? = null
        for (k in configured()) if (unquote(k.SSID) == net.ssid) { saved = k; break }
        var id = -1; var created = false
        try {
            if (!password.isNullOrEmpty() || (saved == null && !net.secured)) {
                val c = configFor(n, password)
                if (c != null) {
                    if (saved != null) {                                  // rete già salvata: si aggiorna la password (se l'ha salvata l'app)
                        c.networkId = saved.networkId; id = m.updateNetwork(c)
                        if (id == -1 && m.removeNetwork(saved.networkId)) { c.networkId = -1; id = m.addNetwork(c); created = id != -1 }
                    } else { id = m.addNetwork(c); created = id != -1 }
                }
                SFLog.log(String.format(Locale.US, "wifi: password impostata=%d", if (id != -1) 1 else 0))
            } else if (saved != null) {
                id = saved.networkId                                      // rete già salvata: si riusa la password che il tablet ricorda
            }
        } catch (e: Exception) { SFLog.log("wifi: configurazione rifiutata: $e"); id = -1 }
        joining = true; joiningSSID = net.ssid; joinDoneBlock = done; joinNet = id; joinCreated = created; lastError = null
        var r = if (id == -1) kErrConfig else 0
        if (r == 0) {
            r = try { m.disconnect(); if (m.enableNetwork(id, true)) 0 else kErrEnable } catch (e: Exception) { kErrEnable }
            if (r == 0) try { m.reconnect() } catch (e: Exception) {}
        }
        SFLog.log(String.format(Locale.US, "wifi: collegamento a \"%s\" avviato (%d)", net.ssid, r))
        if (r != 0) { joinDone(r); return }
        changed()
        val ssid = net.ssid                                               // rete che non risponde: non restare appesi
        Uk.after(25.0) { if (joining && joiningSSID == ssid) joinDone(kErrTimeout) }
    }
    private fun joinDone(err: Int) {
        if (!joining) return
        val ok = err == 0
        SFLog.log(String.format(Locale.US, "wifi: collegamento a \"%s\" %s (%d)", joiningSSID, if (ok) "riuscito" else "FALLITO", err))
        if (ok && joinNet != -1) {                                        // la rete resta salvata sul tablet: alla prossima accensione si ricollega da solo
            try { mgr?.saveConfiguration() } catch (e: Exception) {}
            if (SFIdentity.ready) SFIdentity.prefs.setString("sf_last_ssid", joiningSSID)
        }
        if (!ok && joinCreated && joinNet != -1) {                        // tentativo fallito: la rete appena aggiunta non resta salvata (come sull'iPad)
            try { mgr?.removeNetwork(joinNet); mgr?.reconnect() } catch (e: Exception) {}
        }
        // -3924 = autenticazione rifiutata (visto sull'iPad con una password sbagliata); -1 = nostro tempo scaduto
        val e: String? = if (ok) null else (if (err == kErrTimeout) "nessuna risposta dalla rete (hotspot spento o troppo lontano?)"
                                  else (if (err == kErrAuth) "password errata" else String.format(Locale.US, "non riuscito (errore %d): controllare password e sicurezza dell'hotspot (WPA2)", err)))
        lastError = e; joining = false
        ssidAt = -10.0
        joinNet = -1; joinCreated = false
        for (n in networks) { n.current = ok && n.ssid == joiningSSID; if (n.current) n.known = true }
        val d = joinDoneBlock; joinDoneBlock = null
        d?.invoke(ok, e)
        changed()
    }

    /** ANDROID: apre Impostazioni › Wi-Fi del tablet (riserva, tasto «Impostaz. tablet» del pannello). */
    fun openSettings(): Boolean {
        return try { ctx.startActivity(Intent(Settings.ACTION_WIFI_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); true }
        catch (e: Exception) { SFLog.log("wifi: impostazioni non aperte: $e"); false }
    }

    /**
     * ANDROID (non c'è sull'iPad): toglie una rete salvata. Android 9 lo permette solo per le reti salvate da questa app.
     * Ritorna il messaggio d'errore, oppure null se è andato bene.
     */
    fun forget(net: SFWifiNetwork): String? {
        val m = mgr
        if (!available || m == null) return "rete non più visibile: aggiornare l'elenco"
        if (joining) return "collegamento già in corso"
        var saved: WifiConfiguration? = null
        for (k in configured()) if (unquote(k.SSID) == net.ssid) { saved = k; break }
        if (saved == null) { net.known = false; changed(); return null }
        val ok = try { m.removeNetwork(saved.networkId) } catch (e: Exception) { false }
        SFLog.log(String.format(Locale.US, "wifi: rete \"%s\" %s", net.ssid, if (ok) "dimenticata" else "NON dimenticata"))
        if (!ok) {
            val e = "non riuscito: la rete è stata salvata dalle impostazioni del tablet, si toglie solo da lì («Impostaz. tablet»)"
            lastError = e; changed(); return e
        }
        try { m.saveConfiguration() } catch (e: Exception) {}
        net.known = false; net.current = false
        changed()
        return null
    }
}
