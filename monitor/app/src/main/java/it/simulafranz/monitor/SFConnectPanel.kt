// Portato da src/SFConnectPanel.m (monitor iPad)
// similFranz Monitor — pannello "Collegamento del controller" (si apre dal tasto (i) di ogni layout).
// Mostra i due modi di collegare un controller e chi sta comandando:
//   1 · con una rete Wi-Fi  -> web app (indirizzo) oppure app controller per iPad (trova da sola il monitor)
//   2 · diretto, senza rete -> app controller per iPad (sull'iPad: Multipeer, vedi SFPeerLink)
// più il PIN (uguale per tutti i canali) e la pagina "Reti Wi-Fi" per scegliere la rete senza uscire dall'app.
//
// ANDROID, differenze dall'originale (tutte segnate con "ANDROID:" nel punto in cui sono):
//  - riga del NOME di questo monitor nel riquadro 2: nome + tasto «Rinomina» + "codice XXXX" (vedi SFIdentity);
//  - nei testi che parlano di QUESTO dispositivo "iPad" è diventato "tablet" (gli iPad dei controller restano iPad);
//  - «Impostaz. tablet» apre le impostazioni Wi-Fi di Android.
package it.simulafranz.monitor

import it.simulafranz.core.*
import it.simulafranz.uk.*
import java.util.Locale
import kotlin.math.floor

private val CP_WHITE = UIColor.colorWithWhite(0.95, 1)
private val CP_GREY = UIColor.colorWithWhite(0.62, 1)
private val CP_DIM = UIColor.colorWithWhite(0.42, 1)
private val CP_CARD = UIColor.colorWithWhite(0.13, 1)
private val CP_CYAN = UIColor.colorWithRed(0.24, 0.86, 1.00, 1)
private val CP_GREEN = UIColor.colorWithRed(0.24, 1.00, 0.43, 1)
private val CP_AMBER = UIColor.colorWithRed(1.00, 0.75, 0.10, 1)
private val CP_RED = UIColor.colorWithRed(1.00, 0.35, 0.30, 1)

private const val kPW = 940.0
private const val kPH = 664.0
private const val kMaxRows = 8

class SFConnectPanel(screen: CGRect) : UIView(CGRectMake(floor((screen.width - kPW) / 2), floor((screen.height - kPH) / 2), kPW, kPH)) {
    // Dati da mostrare, riletti a ogni refresh. Chiavi (tutte facoltative):
    //   version, deviceId, ip, port (Number), pin, serverStatus, audio,
    //   directName, directOn (Boolean), directError, peers (List<String>),
    //   controller (Map {name, kind, transport}) oppure assente se nessuno comanda
    var info: (() -> Map<String, Any?>)? = null
    var onClose: (() -> Unit)? = null
    var onVolumes: (() -> Unit)? = null
    var lastInteraction: Double = 0.0; private set      // per la chiusura automatica
    val busy: Boolean get() = !wifi.hidden || SFWifi.shared().joining       // pagina Wi-Fi aperta o collegamento in corso: non chiudere

    private lateinit var main: UIView; private lateinit var wifi: UIView
    private lateinit var version: UILabel; private lateinit var ctl: UILabel
    private lateinit var net: UILabel; private lateinit var url: UILabel; private lateinit var webText: UILabel
    private lateinit var directName: UILabel; private lateinit var directStatus: UILabel
    private lateinit var pin: UILabel; private lateinit var diag: UILabel
    private lateinit var nameCode: UILabel                // ANDROID: "codice XXXX" sotto il nome del monitor
    // pagina Reti Wi-Fi
    private lateinit var wifiStatus: UILabel; private lateinit var wifiList: UIView; private var rows: List<SFWifiNetwork> = emptyList()
    private var statusText: String? = null; private var statusKind: Int = 0      // 0 info, 1 in corso, 2 riuscito, 3 errore

    // MARK: - Costruzione

    private fun label(f: CGRect, `in`: UIView, size: Number, bold: Boolean, color: UIColor): UILabel {
        val l = UILabel(f)
        l.font = if (bold) UIFont.boldSystemFontOfSize(size) else UIFont.systemFontOfSize(size)
        l.textColor = color; l.numberOfLines = 0; l.backgroundColor = UIColor.clearColor
        `in`.addSubview(l)
        return l
    }

    private fun button(title: String, frame: CGRect, `in`: UIView, color: UIColor, action: () -> Unit): UIButton {
        val b = UIButton.buttonWithType(UIButtonTypeCustom)
        b.frame = frame
        b.setTitle(title, UIControlStateNormal)
        b.setTitleColor(color, UIControlStateNormal)
        b.setTitleColor(UIColor.whiteColor, UIControlStateHighlighted)
        b.titleLabel.font = UIFont.boldSystemFontOfSize(17)
        b.titleLabel.adjustsFontSizeToFitWidth = true
        b.layer.borderColor = color.CGColor; b.layer.borderWidth = 1.5; b.layer.cornerRadius = 6.0
        b.accessibilityIdentifier = title
        b.addTarget(UIControlEventTouchUpInside) { action() }
        `in`.addSubview(b)
        return b
    }

    private fun card(f: CGRect, `in`: UIView): UIView {
        val c = UIView(f)
        c.backgroundColor = CP_CARD
        c.layer.borderColor = UIColor.colorWithWhite(0.28, 1).CGColor; c.layer.borderWidth = 1.0; c.layer.cornerRadius = 8.0
        `in`.addSubview(c)
        return c
    }

    init {
        backgroundColor = UIColor.colorWithWhite(0.07, 1)          // opaco: nessuna trasparenza sopra le onde
        layer.borderColor = CP_GREY.CGColor; layer.borderWidth = 1.0; layer.cornerRadius = 10.0
        lastInteraction = CACurrentMediaTime()
        buildMain()
        buildWifi()
        wifi.hidden = true
    }

    private fun buildMain() {
        main = UIView(bounds)
        addSubview(main)
        label(CGRectMake(20, 12, 520, 34), main, 25, true, CP_WHITE).text = "Collegamento del controller"
        version = label(CGRectMake(kPW - 420, 18, 400, 22), main, 13, false, CP_GREY)
        version.textAlignment = NSTextAlignmentRight

        // chi comanda adesso
        val strip = card(CGRectMake(20, 56, kPW - 40, 42), main)
        ctl = label(CGRectMake(14, 0, strip.bounds.width - 28, 42), strip, 17, true, CP_GREY)
        ctl.numberOfLines = 1; ctl.adjustsFontSizeToFitWidth = true; ctl.minimumScaleFactor = 0.7

        val cy = 110.0; val ch = 384.0; val cw = (kPW - 60) / 2
        // --- 1 · regia web, solo dal computer sulla stessa rete (niente QR: telefoni e tablet non la aprono) ---
        val a = card(CGRectMake(20, cy, cw, ch), main)
        label(CGRectMake(16, 10, cw - 32, 26), a, 20, true, CP_CYAN).text = "1 · Regia web, dal computer"
        label(CGRectMake(16, 38, cw - 32, 20), a, 14, false, CP_GREY).text = "Il computer deve essere sulla stessa rete Wi-Fi di questo tablet."   // ANDROID: "iPad" -> "tablet"
        label(CGRectMake(16, 68, 50, 24), a, 16, false, CP_GREY).text = "Rete:"
        net = label(CGRectMake(64, 68, cw - 80, 24), a, 18, true, CP_WHITE)
        net.numberOfLines = 1; net.adjustsFontSizeToFitWidth = true; net.minimumScaleFactor = 0.6
        label(CGRectMake(16, 112, cw - 32, 20), a, 13, true, CP_GREY).text = "SUL COMPUTER, NEL BROWSER"
        webText = label(CGRectMake(16, 132, cw - 32, 44), a, 15, false, CP_WHITE)
        url = label(CGRectMake(16, 180, cw - 32, 44), a, 30, true, CP_CYAN)
        url.numberOfLines = 1; url.adjustsFontSizeToFitWidth = true; url.minimumScaleFactor = 0.5
        label(CGRectMake(16, 228, cw - 32, 20), a, 15, false, CP_GREY).text = "e poi il PIN qui sotto."
        label(CGRectMake(16, 282, cw - 32, 92), a, 14, false, CP_GREY).text =
            "La regia web si usa solo dal computer: da telefono e tablet non si apre. " +
            "In mano usa l'app del riquadro 2: similFranz Controller sul tablet Android."      // ANDROID

        // --- 2 · diretto, senza rete ---
        val b = card(CGRectMake(40 + cw, cy, cw, ch), main)
        label(CGRectMake(16, 10, cw - 32, 26), b, 20, true, CP_CYAN).text = "2 · App regia, diretto"
        // ANDROID: collegamento diretto = Wi-Fi Direct fra tablet Android (testi adattati)
        label(CGRectMake(16, 38, cw - 32, 20), b, 14, false, CP_GREY).text = "App similFranz Controller sul tablet Android."
        label(CGRectMake(16, 68, cw - 32, 110), b, 15, false, CP_WHITE).text =
            "Non serve nessuna rete: il monitor crea da solo la sua rete Wi-Fi Direct. Basta che il controller " +
            "sia vicino e abbia il Wi-Fi acceso.\n\nNell'app scegliere il monitor con questo nome:"
        // ANDROID: riga del nome = nome di questo monitor + «Rinomina»; sotto, il codice che distingue due tablet con lo stesso nome.
        // (sull'iPad il nome è largo cw - 32 e si cambia da Impostazioni; qui lascia il posto al tasto)
        directName = label(CGRectMake(16, 186, cw - 32 - 128, 40), b, 24, true, CP_WHITE)
        directName.numberOfLines = 1; directName.adjustsFontSizeToFitWidth = true; directName.minimumScaleFactor = 0.5
        button("Rinomina", CGRectMake(cw - 16 - 118, 188, 118, 36), b, CP_CYAN) { askName() }
        nameCode = label(CGRectMake(16, 226, cw - 32, 18), b, 13, false, CP_GREY)
        nameCode.numberOfLines = 1
        label(CGRectMake(16, 250, cw - 32, 20), b, 13, true, CP_GREY).text = "STATO"
        directStatus = label(CGRectMake(16, 270, cw - 32, 104), b, 17, true, CP_GREY)
        directStatus.lineBreakMode = NSLineBreakByWordWrapping

        // --- PIN, uguale per tutti i canali ---
        val p = card(CGRectMake(20, 506, kPW - 40, 80), main)
        label(CGRectMake(16, 0, 60, 80), p, 20, true, CP_GREY).text = "PIN"
        pin = label(CGRectMake(76, 0, 330, 80), p, 54, true, CP_WHITE)
        pin.font = UIFont.fontWithName("Menlo-Bold", 54)
        pin.numberOfLines = 1; pin.adjustsFontSizeToFitWidth = true; pin.minimumScaleFactor = 0.4
        label(CGRectMake(420, 0, kPW - 40 - 436, 80), p, 14, false, CP_GREY).text =
            "È lo stesso per la web app e per l'app controller, con o senza rete.\n" +
            "Comanda un solo controller alla volta: un secondo controller può prendere il comando, e il primo viene avvisato."

        diag = label(CGRectMake(20, 598, 420, 54), main, 12, false, CP_DIM)
        button("Reti Wi-Fi…", CGRectMake(kPW - 20 - 3 * 150 - 20, 604, 150, 46), main, CP_CYAN) { showWifi() }
        button("Volumi…", CGRectMake(kPW - 20 - 2 * 150 - 10, 604, 150, 46), main, CP_CYAN) { volumes() }
        button("Chiudi", CGRectMake(kPW - 20 - 150, 604, 150, 46), main, CP_GREY) { close() }
    }

    private fun buildWifi() {
        wifi = UIView(bounds)
        addSubview(wifi)
        label(CGRectMake(20, 12, 520, 34), wifi, 25, true, CP_WHITE).text = "Reti Wi-Fi"
        val hint = label(CGRectMake(kPW - 520, 18, 500, 22), wifi, 13, false, CP_GREY)
        hint.textAlignment = NSTextAlignmentRight
        hint.text = "Toccare una rete per collegare il tablet alla stessa rete del controller."      // ANDROID: "l'iPad" -> "il tablet"
        val box = card(CGRectMake(20, 56, kPW - 40, 52), wifi)
        wifiStatus = label(CGRectMake(14, 0, box.bounds.width - 28, 52), box, 16, true, CP_GREY)
        wifiStatus.numberOfLines = 2; wifiStatus.adjustsFontSizeToFitWidth = true; wifiStatus.minimumScaleFactor = 0.7
        wifiList = UIView(CGRectMake(20, 120, kPW - 40, kMaxRows * 58))
        wifi.addSubview(wifiList)
        button("Aggiorna elenco", CGRectMake(20, 604, 190, 46), wifi, CP_CYAN) { rescan() }
        button("Impostaz. tablet", CGRectMake(220, 604, 190, 46), wifi, CP_GREY) { openSettings() }       // ANDROID: «Impostaz. iPad»
        button("Indietro", CGRectMake(kPW - 20 - 150, 604, 150, 46), wifi, CP_GREY) { showMain() }
    }

    // MARK: - Tocchi

    override fun hitTest(p: CGPoint): UIView? {
        val v = super.hitTest(p)
        if (v != null) lastInteraction = CACurrentMediaTime()
        return v
    }

    private fun close() { showMain(); onClose?.invoke() }
    private fun volumes() { onVolumes?.invoke() }

    // MARK: - Pagina principale

    fun refresh() {
        val d = info?.invoke() ?: emptyMap()
        val ip = d["ip"] as? String; val pin = (d["pin"] as? String) ?: "?"
        var port = (d["port"] as? Number)?.toInt() ?: 0; if (port == 0) port = 8080
        version.text = String.format(Locale.US, "similFranz Monitor %s  ·  %s", d["version"] ?: "", d["deviceId"] ?: "")

        val c = d["controller"] as? Map<*, *>
        if (c != null) {
            ctl.text = String.format(Locale.US, "Controller attivo:  %s  ·  %s", c["name"],
                if (c["transport"] == "direct") "collegamento diretto" else "rete Wi-Fi")
            ctl.textColor = CP_GREEN
        } else {
            ctl.text = "Controller attivo:  nessuno  —  il primo che si collega con il PIN prende il comando"
            ctl.textColor = CP_GREY
        }

        val ssid = SFWifi.shared().currentSSID()
        if (ip != null) { net.text = if (!ssid.isNullOrEmpty()) ssid else "collegata"; net.textColor = CP_WHITE }
        else { net.text = "nessuna — toccare «Reti Wi-Fi…»"; net.textColor = CP_AMBER }
        val url = if (ip != null) String.format(Locale.US, "http://%s:%d", ip, port) else null
        webText.text = if (ip != null) "Apri il browser del computer e scrivi questo indirizzo:"
                       else "Prima collega questo tablet a una rete Wi-Fi («Reti Wi-Fi…»), la stessa del computer."      // ANDROID: "iPad" -> "tablet"
        this.url.text = url ?: ""

        // ANDROID: il nome è quello salvato sul tablet (sf_name); accanto il codice corto che non cambia mai
        val name = (d["directName"] as? String)?.takeIf { it.isNotEmpty() } ?: (if (SFIdentity.ready) SFIdentity.monitorName() else "")
        directName.text = name
        nameCode.text = if (SFIdentity.ready) "codice " + SFIdentity.code() else ""
        val peers = d["peers"] as? List<*>
        if (peers != null && peers.isNotEmpty()) {
            directStatus.text = "Collegato:  " + peers.joinToString(", ")
            directStatus.textColor = CP_GREEN
        } else if (d["directOn"] == true) {
            directStatus.text = "Pronto: in attesa dell'app controller"; directStatus.textColor = CP_GREY
        } else {
            directStatus.text = String.format(Locale.US, "Non disponibile%s", if (d["directError"] != null) ": " + d["directError"] else "")
            directStatus.textColor = CP_AMBER
        }

        // PIN con le cifre distanziate (leggibile da lontano)
        val sp = StringBuilder()
        for (i in pin.indices) { sp.append(if (i != 0 && i % 3 == 0) " " else ""); sp.append(pin[i]) }
        this.pin.text = sp.toString()
        diag.text = String.format(Locale.US, "Server: %s\nAudio: %s\nSIMULAZIONE — NON PER USO CLINICO", d["serverStatus"] ?: "?", d["audio"] ?: "?")
        if (!wifi.hidden) refreshWifiStatus()
    }

    // MARK: - Nome di questo monitor (ANDROID)

    // «Rinomina»: avviso con un campo di testo. Spazi tolti ai lati, al massimo 32 caratteri, vuoto = resta il nome di prima.
    // Cambia solo il nome (sf_name): il codice del tablet resta lo stesso.
    private fun askName() {
        if (!SFIdentity.ready) return
        if (window == null) return
        val a = UIAlertController.alertControllerWithTitle("Nome di questo monitor",
            String.format(Locale.US, "È il nome con cui il monitor compare nei controller (massimo %d caratteri).\nIl codice %s resta lo stesso e distingue due monitor con lo stesso nome.", SFIdentity.kMaxName, SFIdentity.code()),
            UIAlertControllerStyleAlert)
        a.addTextField { tf ->
            tf.text = SFIdentity.monitorName(); tf.placeholder = SFIdentity.defaultName()
            tf.autocorrectionType = UITextAutocorrectionTypeNo
        }
        a.addAction(UIAlertAction.actionWithTitle("Annulla", UIAlertActionStyleCancel, null))
        a.addAction(UIAlertAction.actionWithTitle("Rinomina", UIAlertActionStyleDefault) {
            SFIdentity.rename(a.textFields.firstOrNull()?.text)
            refresh()
        })
        a.present()
    }

    // MARK: - Pagina Reti Wi-Fi

    fun showMain() { wifi.hidden = true; main.hidden = false; SFWifi.shared().onChange = null; refresh() }

    fun showWifi() {
        val w = SFWifi.shared()
        main.hidden = true; wifi.hidden = false
        statusText = null; statusKind = 0
        w.onChange = { rebuildRows(); refreshWifiStatus() }
        rebuildRows(); refreshWifiStatus()
        if (w.available) w.scan()
    }

    private fun rescan() { statusText = null; statusKind = 0; SFWifi.shared().scan(); refreshWifiStatus() }

    private fun setStatus(t: String?, kind: Int) { statusText = t; statusKind = kind; refreshWifiStatus() }

    private fun refreshWifiStatus() {
        val w = SFWifi.shared()
        var t = statusText; var k = statusKind
        // ANDROID: nei testi "iPad" -> "tablet"
        if (!w.available) { t = "Elenco delle reti non disponibile su questo tablet: usare «Impostaz. tablet»."; k = 3 }
        else if (w.joining) { t = String.format(Locale.US, "Collegamento a «%s» in corso…", w.joiningSSID); k = 1 }
        else if (t == null && w.scanning) { t = "Ricerca delle reti in corso…"; k = 1 }
        else if (t == null && w.lastError != null) { t = w.lastError; k = 3 }
        else if (t == null) {
            val cur = w.currentSSID()
            t = if (!cur.isNullOrEmpty()) String.format(Locale.US, "Il tablet è collegato a «%s».", cur) else "Il tablet non è collegato a nessuna rete."
        }
        wifiStatus.text = t
        wifiStatus.textColor = if (k == 1) CP_AMBER else (if (k == 2) CP_GREEN else (if (k == 3) CP_RED else CP_GREY))
    }

    private fun rebuildRows() {
        for (v in wifiList.subviews) v.removeFromSuperview()
        var nets = SFWifi.shared().networks
        if (nets.size > kMaxRows) nets = nets.subList(0, kMaxRows)
        rows = nets
        val w = wifiList.bounds.width
        if (nets.isEmpty()) {
            val e = label(CGRectMake(0, 20, w, 60), wifiList, 16, false, CP_GREY)
            e.textAlignment = NSTextAlignmentCenter
            e.text = if (SFWifi.shared().scanning) "" else "Nessuna rete trovata. Accendere l'hub o l'hotspot e toccare «Aggiorna elenco»."
            return
        }
        nets.forEachIndexed { i, n ->
            val b = UIButton.buttonWithType(UIButtonTypeCustom)
            b.frame = CGRectMake(0, i * 58, w, 50); b.uiTag = i
            b.backgroundColor = if (n.current) UIColor.colorWithRed(0.08, 0.22, 0.12, 1) else CP_CARD
            b.layer.borderColor = (if (n.current) CP_GREEN else UIColor.colorWithWhite(0.28, 1)).CGColor; b.layer.borderWidth = 1.0; b.layer.cornerRadius = 6.0
            b.accessibilityIdentifier = "RETE:" + n.ssid
            b.addTarget(UIControlEventTouchUpInside) { rowTapped(it as UIButton) }
            val name = label(CGRectMake(16, 0, w - 380, 50), b, 19, true, CP_WHITE)
            name.text = n.ssid; name.numberOfLines = 1; name.lineBreakMode = NSLineBreakByTruncatingMiddle; name.userInteractionEnabled = false
            // tacche del segnale disegnate (niente simboli emoji)
            for (k in 0 until 3) {
                val bar = UIView(CGRectMake(w - 350 + k * 10, 32 - k * 7, 7, 8 + k * 7))
                bar.backgroundColor = if (k < n.bars) CP_WHITE else UIColor.colorWithWhite(0.3, 1); bar.userInteractionEnabled = false
                b.addSubview(bar)
            }
            val det = label(CGRectMake(w - 300, 0, 284, 50), b, 15, false, if (n.current) CP_GREEN else CP_GREY)
            det.textAlignment = NSTextAlignmentRight; det.numberOfLines = 1; det.userInteractionEnabled = false
            det.text = String.format(Locale.US, "%s  ·  %s", if (n.channel > 14) "5 GHz" else "2,4 GHz",
                if (n.current) "collegata" else (if (n.known) "salvata" else (if (n.secured) "con password" else "aperta")))
            wifiList.addSubview(b)
        }
    }

    private fun rowTapped(b: UIButton) {
        if (b.uiTag < 0 || b.uiTag >= rows.size) return
        val n = rows[b.uiTag]
        if (n.current) { setStatus(String.format(Locale.US, "Il tablet è già collegato a «%s».", n.ssid), 2); return }
        if (n.known || !n.secured) { join(n, null); return }
        askPassword(n)
    }

    private fun join(n: SFWifiNetwork, password: String?) {
        statusText = null; statusKind = 0
        SFWifi.shared().join(n, password) { ok, error ->
            if (ok) {
                setStatus(String.format(Locale.US, "Collegato a «%s». La rete resta salvata: alla prossima accensione il tablet si ricollega da solo.", n.ssid), 2)
                // l'esito resta visibile un paio di secondi, poi si torna alla pagina con l'indirizzo
                Uk.after(2.5) {
                    if (!wifi.hidden && statusKind == 2) showMain()
                }
            } else {
                setStatus(String.format(Locale.US, "«%s»: %s", n.ssid, error ?: "collegamento non riuscito"), 3)
            }
        }
        refreshWifiStatus()
    }

    private fun togglePwVisible(eye: UIButton, tf: UITextField) {
        tf.secureTextEntry = !tf.secureTextEntry
        eye.setTitle(if (tf.secureTextEntry) "mostra" else "nascondi", UIControlStateNormal)
    }

    private fun askPassword(n: SFWifiNetwork) {
        if (window == null) return
        val a = UIAlertController.alertControllerWithTitle(n.ssid, "Password della rete Wi-Fi", UIAlertControllerStyleAlert)
        a.addTextField { tf ->
            tf.secureTextEntry = true; tf.placeholder = "password"
            tf.autocorrectionType = UITextAutocorrectionTypeNo; tf.autocapitalizationType = UITextAutocapitalizationTypeNone
            val eye = UIButton.buttonWithType(UIButtonTypeSystem)
            eye.frame = CGRectMake(0, 0, 72, 24); eye.titleLabel.font = UIFont.systemFontOfSize(13)
            eye.setTitle("mostra", UIControlStateNormal)
            eye.addTarget(UIControlEventTouchUpInside) { togglePwVisible(eye, tf) }
            tf.rightView = eye; tf.rightViewMode = UITextFieldViewModeAlways
        }
        a.addAction(UIAlertAction.actionWithTitle("Annulla", UIAlertActionStyleCancel, null))
        a.addAction(UIAlertAction.actionWithTitle("Collega", UIAlertActionStyleDefault) {
            val pw = a.textFields.firstOrNull()?.text
            if (!pw.isNullOrEmpty()) join(n, pw) else setStatus("Password non inserita.", 3)
        })
        a.present()
    }

    // verifica da Mac: collega alla rete con questo nome (password facoltativa)
    fun debugJoin(ssid: String, password: String?) {
        val pw = password
        for (n in SFWifi.shared().networks) if (n.ssid == ssid) {
            if (!pw.isNullOrEmpty() || n.known || !n.secured) join(n, pw) else askPassword(n)
            return
        }
        setStatus(String.format(Locale.US, "«%s» non è nell'elenco.", ssid), 3)
    }

    // Impostazioni > Wi-Fi del tablet (riserva). Va chiamata in differita, come sull'iPad.
    private fun openSettings() {
        Uk.async {
            val ok = SFWifi.shared().openSettings()
            SFLog.log(String.format(Locale.US, "impostazioni Wi-Fi: %s", if (ok) "aperte" else "NON aperte"))
        }
    }
}
