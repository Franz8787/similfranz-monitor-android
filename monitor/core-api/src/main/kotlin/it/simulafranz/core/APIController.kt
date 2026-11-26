// Portato da src/APIController.m (monitor iPad)
// SimulaFranz_Monitor — API JSON v1 + SimulaFranz_Controller Web (servita dall'app).
// Kotlin puro: testabile sul Mac con un SimulationState reale.
package it.simulafranz.core

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.util.Base64
import java.util.Locale

private fun SLError(status: Int, msg: String): SLHTTPResponse {
    return SLHTTPResponse.status(status, json = linkedMapOf<String, Any?>("ok" to false, "error" to msg))
}

// Confronto a tempo costante (evita di rivelare il PIN tramite tempi di risposta)
private fun SLConstantTimeEquals(a: String, b: String): Boolean {
    val da = a.toByteArray(Charsets.UTF_8)
    val db = b.toByteArray(Charsets.UTF_8)
    if (da.size != db.size || da.isEmpty()) return false
    var diff = 0
    for (i in da.indices) diff = diff or (da[i].toInt() xor db[i].toInt())
    return diff == 0
}

private fun SLClean(s: String?, allowed: String?, max: Int): String? {
    if (s == null || s.isEmpty()) return null
    val o = StringBuilder()
    var i = 0
    while (i < s.length && o.length < max) {
        val c = s[i]
        if (if (allowed != null) allowed.indexOf(c) >= 0 else (c.code >= 0x20 && c.code != 0x7f)) o.append(c)
        i++
    }
    return if (o.isNotEmpty()) o.toString() else null
}

// testo UTF-8 valido, oppure null
private fun SLUtf8OrNull(b: ByteArray): String? = try {
    Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
        .decode(ByteBuffer.wrap(b)).toString()
} catch (e: CharacterCodingException) { null }

// come stringByRemovingPercentEncoding: null se una sequenza %XX non è valida o il risultato non è UTF-8
private fun SLRemovingPercentEncoding(s: String?): String? {
    if (s == null) return null
    val src = s.toByteArray(Charsets.UTF_8)
    val out = ByteArrayOutputStream(src.size)
    var i = 0
    while (i < src.size) {
        val c = src[i]
        if (c == '%'.code.toByte()) {
            if (i + 2 >= src.size) return null
            val h = Character.digit(src[i + 1].toInt().toChar(), 16)
            val l = Character.digit(src[i + 2].toInt().toChar(), 16)
            if (src[i + 1] < 0 || src[i + 2] < 0 || h < 0 || l < 0) return null
            out.write(h * 16 + l); i += 3
        } else { out.write(c.toInt()); i++ }
    }
    return SLUtf8OrNull(out.toByteArray())
}

// come NSJSONSerialization JSONObjectWithData: null se il body è vuoto o non è JSON valido
private fun SLJson(body: ByteArray?): Any? {
    if (body == null || body.isEmpty()) return null
    val text = SLUtf8OrNull(body) ?: return null
    return Json.parseOrNull(text)
}

// come respondsToSelector:@selector(integerValue) + integerValue (numeri, booleani, stringhe)
private fun SLIntegerValue(v: Any?): Int = when (v) {
    is Boolean -> if (v) 1 else 0
    is Number -> v.toLong().toInt()
    is String -> SFLongLongValue(v).toInt()
    else -> 0
}


// ANDROID (audit 26/11, PUL-F5): era in SFUpdate.kt (tolto), lo usano registro e query
// come -[NSString longLongValue] / integerValue: spazi iniziali, segno facoltativo, cifre; altrimenti 0
internal fun SFLongLongValue(s: String?): Long {
    if (s == null) return 0
    var i = 0
    val n = s.length
    while (i < n && (s[i] == ' ' || s[i] == '\t' || s[i] == '\n' || s[i] == '\r')) i++
    var neg = false
    if (i < n && (s[i] == '+' || s[i] == '-')) { neg = s[i] == '-'; i++ }
    var v = 0L
    var any = false
    while (i < n && s[i] in '0'..'9') {
        val d = (s[i] - '0').toLong()
        if (v > (Long.MAX_VALUE - d) / 10) return if (neg) Long.MIN_VALUE else Long.MAX_VALUE
        v = v * 10 + d; any = true; i++
    }
    if (!any) return 0
    return if (neg) -v else v
}

// pinProvider: PIN/token attuale (letto a ogni richiesta, quindi modificabile a caldo)
// staticFiles: nome file -> contenuto (whitelist; nessun accesso al filesystem dal path)
class APIController(
    private val state: SimulationState,
    private val pinProvider: (() -> String?)?,
    staticFiles: Map<String, ByteArray>?
) {
    private val files: Map<String, ByteArray> = staticFiles?.toMap() ?: emptyMap()
    private val failures = HashMap<String, MutableList<Double>>() // ip -> timestamps

    // ANDROID: associazione rapida dei controller Android (non c'è sull'iPad). Il controller chiede (POST /api/v1/pair,
    // senza PIN), il monitor mostra «Associa» a tutto schermo, l'istruttore tocca e il controller riceve il PIN da solo
    // (GET /api/v1/pair?client=…). Una sola richiesta alla volta, valida 90 s. null = funzione spenta (404).
    var onPairRequest: ((clientId: String, name: String) -> Unit)? = null
    private var pairCid: String? = null; private var pairName = ""; private var pairAt = 0.0; private var pairStatus = "none"
    /** risposta dell'istruttore sul monitor (true = Associa) */
    fun pairAnswer(clientId: String, ok: Boolean) {
        synchronized(this) { if (clientId == pairCid && pairStatus == "waiting") { pairStatus = if (ok) "approved" else "denied"; pairAt = clock() } }
    }
    // ANDROID (audit 21/11): una richiesta di associazione al massimo ogni pairMinGap secondi (un controller che riprova da solo,
    // o un collega che tocca il monitor sbagliato, non deve riempire lo schermo del partecipante di richieste)
    // ANDROID (audit 26/11, RETE-R04): 60 s (prima 30) e vale per ogni nuova finestra, anche dopo un «Rifiuta»
    var pairMinGap: Double = 60.0
    private var pairShownAt = -1e9

    // ANDROID (audit 21/11): comandi già eseguiti, per identificativo (header X-SimulaFranz-Cmd-Id). Un controller che non ha
    // ricevuto la risposta (collegamento caduto a metà) rimanda lo stesso comando con lo stesso identificativo: qui non si
    // ripete (niente doppia scarica, doppia adrenalina nel registro, doppio «Fase successiva»). Si ricordano gli ultimi 64.
    private val doneCmds = object : LinkedHashMap<String, Boolean>() {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Boolean>?): Boolean = size > 512   // ANDROID (audit 26/11, RETE-R07): prima 64
    }
    private fun cmdKey(req: SLHTTPRequest, c: List<String>?): String? {
        val id = SLClean(req.headers["x-simulafranz-cmd-id"], "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789_.:-", 64) ?: return null
        return (c?.get(0) ?: (req.remoteAddress ?: "?")) + "|" + id
    }
    private fun alreadyDone(key: String?): Boolean { if (key == null) return false; synchronized(doneCmds) { return doneCmds.containsKey(key) } }
    private fun markDone(key: String?) { if (key == null) return; synchronized(doneCmds) { doneCmds[key] = true } }
    private val lockedUntil = HashMap<String, Double>()           // ip -> time
    private var startTime: Double

    // Parametri anti-forza-bruta (per IP): dopo maxFailures errori in failureWindow s,
    // blocco per lockoutSeconds.
    var maxFailures: Int = 5
    var failureWindow: Double = 60.0
    var lockoutSeconds: Double = 30.0

    // Un solo controller alla volta (vedi SFControl): chi comanda, presa di controllo, rilascio.
    val control: SFControl = SFControl()

    // l'orologio dell'API vale anche per il controllo (un solo controller alla volta)
    var clock: () -> Double = { System.nanoTime() / 1e9 }
        set(value) { field = value; control.clock = value }

    // Scenari a fasi salvati sul monitor (vedi SFScenarioStore). null = endpoint /api/v1/scenarios non disponibile.
    var scenarios: SFScenarioStore? = null

    // Registro della sessione per il debriefing (vedi SFSessionLog). null = endpoint /api/v1/log non disponibile.
    var sessionLog: SFSessionLog? = null

    // ANDROID (storia sim 22/11): «Storia Sim», simulazioni salvate (vedi SFArchive). null = endpoint /api/v1/archive non disponibile.
    var archive: SFArchive? = null

    // ANDROID (audit 26/11, PUL-F5): tolta la pipeline di aggiornamento dei pacchetti .deb dell'iPad (SFUpdate, /api/v1/update*):
    // su Android le app si aggiornano installando l'APK nuovo
    var appVersion: String? = null

    // ANDROID (sala 22/11): fotocamera frontale e microfono del monitor in diretta per il controller che comanda (vedi SFLiveCam
    // nell'app). kind = "video" | "audio" | "status"; allowed() dice, durante il flusso, se chi guarda comanda ancora.
    // null = funzione assente (404). Niente viene salvato.
    var liveHandler: ((kind: String, query: String, allowed: () -> Boolean) -> SLHTTPResponse)? = null

    init {
        startTime = clock()
    }

    // Ritorna null se autorizzato, altrimenti la risposta di errore
    private fun checkAuth(req: SLHTTPRequest): SLHTTPResponse? {
        val ip = req.remoteAddress ?: "?"
        val now = clock()
        synchronized(this) {
            val until = lockedUntil[ip]
            if (until != null && now < until) {
                val r = SLError(429, "too many failed attempts, retry later")
                r.extraHeaders = mapOf("Retry-After" to String.format(Locale.US, "%.0f", Math.ceil(until - now)))
                return r
            }
            val token = req.headers["x-simulafranz-token"]
            val pin = pinProvider?.invoke()
            if (token != null && pin != null && SLConstantTimeEquals(token, pin)) {
                failures.remove(ip)
                if (req.headers["x-simulafranz-device"] == "oximeter") state.noteOximeterSeen()   // il saturimetro è in linea
                return null
            }
            val f = failures[ip] ?: ArrayList()
            f.add(now)
            f.retainAll { t -> now - t <= failureWindow }
            failures[ip] = f
            if (f.size >= maxFailures) {
                lockedUntil[ip] = now + lockoutSeconds
                failures.remove(ip)
            }
        }
        return SLError(401, "missing or invalid X-SimulaFranz-Token")
    }

    private fun okWithState(cid: String?): SLHTTPResponse {
        val st = state.stateDictionary()
        val sc = scenarios
        val log = sessionLog
        return SLHTTPResponse.status(200, json = linkedMapOf<String, Any?>(
            "ok" to true, "state" to st, "revision" to st["revision"], "error" to null,
            "controller" to control.infoFor(cid),
            "scenario" to (if (sc != null) sc.runInfo() else mapOf<String, Any?>("active" to false)),
            "log_next" to (if (log != null) log.nextIndex() else 0),
            "log_session" to (if (log != null) log.currentSession() else 0)))
    }

    // MARK: - Un solo controller alla volta

    // Identità del controller che fa la richiesta: [id, nome, tipo, canale]. null = dispositivo seguace (non è un controller).
    // Canale: "direct" per il collegamento diretto fra iPad (richieste del ponte SFPeerLink), altrimenti "wifi".
    // Senza l'header X-SimulaFranz-Client (vecchi client, curl) l'identità è l'indirizzo di provenienza.
    private fun clientOf(req: SLHTTPRequest): List<String>? {
        if (!req.headers["x-simulafranz-device"].isNullOrEmpty()) return null
        val remote = req.remoteAddress ?: "?"
        val direct = remote.startsWith("peer:")
        var cid = SLClean(req.headers["x-simulafranz-client"], "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789_.:-", 64)
        var name = SLClean(SLRemovingPercentEncoding(req.headers["x-simulafranz-client-name"]), null, 40)
        val kind = SLClean(req.headers["x-simulafranz-client-kind"], "abcdefghijklmnopqrstuvwxyz0123456789_-", 16)
        if (cid == null) cid = "addr:" + remote
        if (name == null) name = if (direct) remote.substring(5) else "browser " + remote
        return listOf(cid, name, kind ?: (if (direct) "ipad" else "web"), if (direct) "direct" else "wifi")
    }

    // Ritorna null se chi chiede è il controller attivo (o un seguace), altrimenti 423 "monitor in uso".
    private fun checkControl(c: List<String>?): SLHTTPResponse? {
        if (c == null) return null
        if (control.act(c[0], c[1], c[2], c[3])) return null
        val info = control.infoFor(c[0])
        return SLHTTPResponse.status(423, json = linkedMapOf<String, Any?>("ok" to false, "error" to "controller_busy",
            "message" to String.format(Locale.US, "monitor in uso da %s", info["name"] ?: "un altro controller"),
            "controller" to info))
    }

    fun handle(request: SLHTTPRequest): SLHTTPResponse {
        val req = request
        val m = req.method
        val p = req.path

        // --- Console Web statica (whitelist) ---
        val statics = mapOf("/" to listOf("index.html", "text/html; charset=utf-8"),
                            "/index.html" to listOf("index.html", "text/html; charset=utf-8"),
                            "/style.css" to listOf("style.css", "text/css; charset=utf-8"),
                            "/app.js" to listOf("app.js", "application/javascript; charset=utf-8"),
                            "/body.svg" to listOf("body.svg", "image/svg+xml"))
        val sp = statics[p]
        if (sp != null) {
            if (m != "GET" && m != "HEAD") return SLError(405, "method not allowed")
            val d = files[sp[0]] ?: return SLError(404, "console file missing from app bundle")
            return SLHTTPResponse.status(200, sp[1], d)
        }

        // ANDROID (miniature dei layout 23/11): /thumbs/<id>.png (solo nomi semplici, solo i file caricati all'avvio)
        if (p.startsWith("/thumbs/") && Regex("^/thumbs/[a-z0-9_]{1,40}\\.png$").matches(p)) {
            if (m != "GET" && m != "HEAD") return SLError(405, "method not allowed")
            val d = files[p.substring(1)] ?: return SLError(404, "not found")
            return SLHTTPResponse.status(200, "image/png", d)
        }

        // --- API v1 ---
        if (p == "/api/v1/health") {
            if (m != "GET") return SLError(405, "method not allowed")
            return SLHTTPResponse.status(200, json = linkedMapOf<String, Any?>(
                "ok" to true, "service" to "SimulaFranz_Monitor", "device_id" to state.deviceId,
                // ANDROID: sull'iPad è CFBundleShortVersionString del bundle; qui la versione che l'app mette in appVersion
                "device_type" to "multiparameter", "version" to (appVersion ?: "?"), "api" to "v1",
                "uptime_s" to (clock() - startTime).toInt(),
                "auth" to "X-SimulaFranz-Token", "transports" to listOf("wifi", "direct"),
                "controller_active" to (control.owner() != null)))
        }
        // ANDROID: associazione rapida (vedi onPairRequest)
        if (p == "/api/v1/pair") {
            val cb = onPairRequest ?: return SLError(404, "not found")
            if (m == "POST") {
                // ANDROID (audit 26/11, RETE-R04): «Associa» solo dal collegamento diretto (Wi-Fi Direct, 192.168.49.x): dalla rete
                // LAN (regia web, altri computer) la finestra a tutto schermo non deve comparire davanti ai partecipanti; lì si usa il PIN
                if (!(req.remoteAddress ?: "").removePrefix("peer:").startsWith("192.168.49."))
                    return SLHTTPResponse.status(403, json = linkedMapOf<String, Any?>("ok" to false, "error" to "pair_direct_only",
                        "message" to "associazione rapida solo dal collegamento diretto: usa il PIN"))
                val json = SLJson(req.body) as? Map<*, *> ?: return SLError(400, "body must be a valid JSON object")
                val cid = SLClean(json["client"] as? String, "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789_.:-", 64) ?: return SLError(400, "client required")
                val name = SLClean(json["name"] as? String, null, 40) ?: "controller"
                // ANDROID (audit 21/11): con un altro controller che comanda il monitor non si chiede nulla al partecipante:
                // chi vuole comandare usa il PIN (menu ☰ → Informazioni e collegamenti) e «Prendi il comando»
                val owner = control.infoFor(cid)
                // ANDROID (due facilitatori 22/11): un secondo controller (che annota) si può associare con «Associa» quando la
                // simulazione NON sta andando (pronto, pausa, terminato): la richiesta sul monitor non disturba i partecipanti.
                // A simulazione in corso resta il PIN, come prima.
                val snap = state.snapshot()
                val live = snap.scenarioStarted && snap.simulationRunning && !snap.scenarioEnded
                if (live && owner["active"] == true && owner["mine"] != true && owner["protected"] == true)
                    return SLHTTPResponse.status(423, json = linkedMapOf<String, Any?>("ok" to false, "error" to "controller_busy",
                        "message" to String.format(Locale.US, "monitor in uso da %s", owner["name"] ?: "un altro controller"), "controller" to owner))
                var show = true
                synchronized(this) {
                    val now = clock()
                    if (pairStatus == "waiting" && now - pairAt < pairMinGap && pairCid == cid) show = false   // stessa richiesta ripetuta: la finestra è già a schermo
                    else if (now - pairShownAt < pairMinGap) return SLHTTPResponse.status(429, json = linkedMapOf<String, Any?>("ok" to false, "error" to "pair_pending",
                            "message" to "c'è già una richiesta di associazione sul monitor"))
                    if (show) { pairCid = cid; pairName = name; pairAt = now; pairStatus = "waiting"; pairShownAt = now }
                }
                if (show) cb(cid, name)
                return SLHTTPResponse.status(200, json = linkedMapOf<String, Any?>("ok" to true, "status" to "waiting", "error" to null))
            }
            if (m != "GET") return SLError(405, "method not allowed")
            var cid: String? = null
            for (kv in (req.query ?: "").split("&")) { val i = kv.indexOf('='); if (i > 0 && kv.substring(0, i) == "client") cid = kv.substring(i + 1) }
            val out = linkedMapOf<String, Any?>("ok" to true)
            synchronized(this) {
                if (cid == null || cid != pairCid || clock() - pairAt > 90) out["status"] = "none"
                else {
                    out["status"] = pairStatus
                    if (pairStatus == "approved") { out["pin"] = pinProvider?.invoke(); pairCid = null; pairStatus = "none" }    // il PIN si consegna una volta sola
                }
            }
            return SLHTTPResponse.status(200, json = out)
        }
        if (p == "/api/v1/state") {
            if (m != "GET") return SLError(405, "method not allowed")
            val deny = checkAuth(req); if (deny != null) return deny
            val c = clientOf(req)
            if (c != null) control.seen(c[0])     // leggere è sempre permesso
            return okWithState(c?.get(0))
        }
        // Controllo del monitor: GET = chi comanda; POST {"action":"take"} prende il controllo, {"action":"release"} lo lascia
        if (p == "/api/v1/control") {
            val post = m == "POST"
            if (!post && m != "GET") return SLError(405, "method not allowed")
            val deny = checkAuth(req); if (deny != null) return deny
            val c = clientOf(req)
            if (post) {
                if (c == null) return SLError(403, "a follower device cannot take control")
                val json = SLJson(req.body)
                val action = if (json is Map<*, *>) json["action"] else null
                if (action == "take") control.take(c[0], c[1], c[2], c[3])
                else if (action == "release") control.releaseClient(c[0])
                else return SLError(400, "action must be \"take\" or \"release\"")
            } else if (c != null) control.seen(c[0])
            return SLHTTPResponse.status(200, json = linkedMapOf<String, Any?>("ok" to true, "controller" to control.infoFor(c?.get(0)), "error" to null))
        }
        if (p == "/api/v1/command") {
            // ANDROID (audit 26/11, RETE-R07): GET ?id=<Cmd-Id> → {"ok":true,"id":…,"done":true|false}: il controller che non ha avuto
            // la risposta chiede se il comando è stato eseguito (stessa chiave della deduplica: chi chiede + id), invece di ripeterlo
            if (m == "GET" && (req.query ?: "").split("&").any { it.startsWith("id=") }) {     // senza id resta 405 come prima
                val deny = checkAuth(req); if (deny != null) return deny
                val c = clientOf(req)
                var id: String? = null
                for (kv in (req.query ?: "").split("&")) if (kv.startsWith("id=")) id = SLClean(kv.substring(3), "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789_.:-", 64)
                if (id == null) return SLError(400, "id: 1-64 characters [A-Za-z0-9_.:-]")
                val key = (c?.get(0) ?: (req.remoteAddress ?: "?")) + "|" + id
                return SLHTTPResponse.status(200, json = linkedMapOf<String, Any?>("ok" to true, "id" to id, "done" to alreadyDone(key), "error" to null))
            }
            if (m != "POST") return SLError(405, "method not allowed")
            var deny = checkAuth(req); if (deny != null) return deny
            val c = clientOf(req)
            deny = checkControl(c); if (deny != null) return deny
            val ct = req.headers["content-type"]?.lowercase(Locale.ROOT) ?: ""
            if (!ct.startsWith("application/json")) return SLError(400, "Content-Type must be application/json")
            val parsed = SLJson(req.body)
            if (parsed !is Map<*, *>) return SLError(400, "body must be a valid JSON object")
            @Suppress("UNCHECKED_CAST") val json = parsed as Map<String, Any?>
            val ck = cmdKey(req, c)
            if (alreadyDone(ck)) return okWithState(c?.get(0))          // ANDROID (audit 21/11): ripetizione, già eseguito
            // ANDROID (funzioni mancanti 22/11): il cuore sa chi ha dato il comando (registro: «istruttore»/nome della regia)
            val err = SLCommandSource.with(c?.get(1)?.takeIf { it.isNotEmpty() } ?: "istruttore") { state.applyCommand(json) }
            if (err != null) return SLError(400, err)
            markDone(ck)
            if (json["command"] == "reset_scenario") scenarios?.stop()      // il reset chiude anche lo scenario a fasi
            if (json["command"] == "end_scenario") scenarios?.stop()        // ANDROID (fine scenario 22/11): la fine chiude lo scenario a fasi
            sessionLog?.noteCommand(json, c?.get(1))
            return okWithState(c?.get(0))
        }
        // Scenari a fasi: GET = elenco + conduzione in corso; POST {"action": save|delete|start|goto|next|stop, ...}
        val sc = scenarios
        if (p == "/api/v1/scenarios" && sc != null) {
            val post = m == "POST"
            if (!post && m != "GET") return SLError(405, "method not allowed")
            var deny = checkAuth(req); if (deny != null) return deny
            val c = clientOf(req)
            if (!post) {
                if (c != null) control.seen(c[0])
                return SLHTTPResponse.status(200, json = linkedMapOf<String, Any?>("ok" to true, "scenarios" to sc.scenarios(), "scenario" to sc.runInfo(), "error" to null))
            }
            deny = checkControl(c); if (deny != null) return deny
            val parsed = SLJson(req.body)
            if (parsed !is Map<*, *>) return SLError(400, "body must be a valid JSON object")
            @Suppress("UNCHECKED_CAST") val json = parsed as Map<String, Any?>
            val action = json["action"] as? String ?: ""
            val sid = json["id"] as? String
            val phase = SLIntegerValue(json["phase"])
            val ck = cmdKey(req, c)
            if (alreadyDone(ck)) {       // ANDROID (audit 21/11): ripetizione, già eseguito: si risponde con lo stato attuale
                val st0 = state.stateDictionary()
                return SLHTTPResponse.status(200, json = linkedMapOf<String, Any?>("ok" to true, "error" to null, "scenario" to sc.runInfo(), "state" to st0,
                    "revision" to st0["revision"], "controller" to control.infoFor(c?.get(0)), "scenarios" to sc.scenarios()))
            }
            var err: String? = null
            var ok = true
            var saved: Map<String, Any?>? = null
            if (action == "save") { @Suppress("UNCHECKED_CAST") val r = sc.save(json["scenario"] as? Map<String, Any?>); saved = r.scenario; err = r.error; ok = saved != null }
            else if (action == "delete") { err = sc.deleteId(sid); ok = err == null }
            else if (action == "start") {
                // ANDROID (avvio scenario 22/11): da PRONTO l'avvio fa anche start_scenario; avvio e voci del registro sotto il
                // lucchetto del registro, così il giro di observe non apre prima una sessione «libera» con la sua voce di avvio
                val lg = sessionLog
                if (lg != null) synchronized(lg) {
                    err = sc.start(sid, phase); ok = err == null
                    if (ok) { val r0 = sc.runInfo(); lg.newSession(r0["name"] as? String)        // ogni scenario avviato = una sessione del registro
                        lg.add("phase", String.format(Locale.US, "Scenario «%s» avviato · fase %d: %s", r0["name"], SLIntegerValue(r0["phase"]) + 1, r0["phase_name"]), "scenario_start", c?.get(1)) }
                } else { err = sc.start(sid, phase); ok = err == null }
            }
            else if (action == "goto") { err = sc.gotoPhase(phase); ok = err == null }
            else if (action == "next") { err = sc.next(); ok = err == null }
            else if (action == "stop") sc.stop()
            else return SLError(400, "action must be save, delete, start, goto, next or stop")
            if (!ok) return SLError(400, err ?: "scenario error")
            markDone(ck)
            val run = sc.runInfo()            // nel registro: avvio, cambio di fase, fine
            val log = sessionLog
            val by = c?.get(1)
            if (action == "start") { }                                          // ANDROID (avvio scenario 22/11): voci già scritte sopra
            else if (action == "goto" || action == "next") log?.add("phase", String.format(Locale.US, "Fase %d: %s", SLIntegerValue(run["phase"]) + 1, run["phase_name"]), null, by)
            else if (action == "stop") log?.add("phase", "Scenario terminato dall'istruttore", null, by)
            val st = state.stateDictionary()
            val out = linkedMapOf<String, Any?>("ok" to true, "error" to null, "scenario" to sc.runInfo(), "state" to st, "revision" to st["revision"],
                                                "controller" to control.infoFor(c?.get(0)))
            if (saved != null) out["saved"] = saved
            if (saved != null || action == "delete") out["scenarios"] = sc.scenarios()
            return SLHTTPResponse.status(200, json = out)
        }
        // Registro della sessione: GET [?since=n] = voci (da n) + riepilogo per il debriefing; GET ?format=text = testo completo;
        // POST {"action":"add","kind":...,"text":...,"tag":...} aggiunge una voce (anche da chi non comanda: un secondo
        // istruttore può annotare); POST {"action":"clear"} svuota (solo chi comanda).
        val log = sessionLog
        if (p == "/api/v1/log" && log != null) {
            val post = m == "POST"
            if (!post && m != "GET") return SLError(405, "method not allowed")
            var deny = checkAuth(req); if (deny != null) return deny
            val c = clientOf(req)
            val ck = if (post) cmdKey(req, c) else null
            if (post && alreadyDone(ck)) {
                // ANDROID (audit 21/11): voce già registrata (ripetizione dopo una risposta persa): si risponde senza aggiungerla
            } else if (post) {
                val json = SLJson(req.body)
                if (json !is Map<*, *>) return SLError(400, "body must be a valid JSON object")
                val action = json["action"]
                if (action == "add") {
                    val text = json["text"]
                    if (text !is String || text.trim { it.isWhitespace() }.isEmpty()) return SLError(400, "log: text is required")
                    log.add(json["kind"] as? String ?: "note", text, json["tag"] as? String, c?.get(1))
                } else if (action == "clear") {
                    deny = checkControl(c); if (deny != null) return deny
                    log.clear()
                } else if (action == "new_session") {
                    deny = checkControl(c); if (deny != null) return deny
                    log.newSession(json["title"] as? String)
                } else return SLError(400, "action must be add, new_session or clear")
                markDone(ck)
            } else if (c != null) control.seen(c[0])
            // ?session=N sceglie la sessione (predefinita: quella corrente; "all" = tutte)
            var since = 0
            var sid = log.currentSession()
            var text = false
            for (kv in (req.query ?: "").split("&")) {
                if (kv.startsWith("since=")) since = SFLongLongValue(kv.substring(6)).toInt()
                if (kv == "format=text") text = true
                if (kv.startsWith("session=")) sid = if (kv.substring(8) == "all") 0 else SFLongLongValue(kv.substring(8)).toInt()
            }
            if (text) return SLHTTPResponse.status(200, "text/plain; charset=utf-8", log.plainTextForSession(sid).toByteArray(Charsets.UTF_8))
            var entries = log.entriesSince(since, sid)
            if (entries.size > 400) entries = entries.subList(0, 400)      // a blocchi: il client richiede il resto con since
            val next = if (entries.isNotEmpty()) SLIntegerValue(entries.last()["i"]) + 1 else log.nextIndex()
            return SLHTTPResponse.status(200, json = linkedMapOf<String, Any?>("ok" to true, "error" to null, "log" to entries, "next" to next,
                "more" to (entries.size == 400), "summary" to log.summaryForSession(if (sid != 0) sid else log.currentSession()),
                "session" to sid, "current_session" to log.currentSession(), "sessions" to log.sessions()))
        }
        // ANDROID (storia sim 22/11): «Storia Sim», archivio delle simulazioni salvate (vedi SFArchive e STORIA_SIM.md).
        // Stessa autenticazione del registro: leggere e salvare non richiede di comandare (il controller può rimandare
        // più tardi una simulazione rimasta solo in locale). Un dispositivo seguace (saturimetro) non scrive.
        //   GET            → {"ok":true,"items":[{id,title,date_text,saved_w,duration_s,team_n,source,saved_by}]} (più recenti prima)
        //   GET ?id=<id>   → {"ok":true,"item":{…record completo…}} · 404 se non c'è
        //   POST record    → salva (stesso id = sostituisce) → {"ok":true,"id":"…"}
        //   POST {"delete":"<id>"} → {"ok":true}
        val arc = archive
        if (p == "/api/v1/archive" && arc != null) {
            val post = m == "POST"
            if (!post && m != "GET") return SLError(405, "method not allowed")
            val deny = checkAuth(req); if (deny != null) return deny
            val c = clientOf(req)
            if (!post) {
                if (c != null) control.seen(c[0])
                var id: String? = null
                for (kv in (req.query ?: "").split("&")) if (kv.startsWith("id=")) id = SLRemovingPercentEncoding(kv.substring(3)) ?: ""
                if (id == null) return SLHTTPResponse.status(200, json = linkedMapOf<String, Any?>("ok" to true, "items" to arc.items()))
                if (!SFArchive.validId(id)) return SLError(400, "archive: id must be 1-64 characters [0-9a-zA-Z-]")
                val item = arc.item(id) ?: return SLError(404, "archive: not found")
                return SLHTTPResponse.status(200, json = linkedMapOf<String, Any?>("ok" to true, "item" to item))
            }
            if (c == null) return SLError(403, "a follower device cannot write the archive")
            if ((req.body?.size ?: 0) > kArchiveMaxBytes) return SLError(413, "payload too large")
            val parsed = SLJson(req.body)
            if (parsed !is Map<*, *>) return SLError(400, "body must be a valid JSON object")
            @Suppress("UNCHECKED_CAST") val json = parsed as Map<String, Any?>
            if (json.containsKey("delete")) {
                val did = json["delete"]
                if (!SFArchive.validId(did)) return SLError(400, "archive: id must be 1-64 characters [0-9a-zA-Z-]")
                arc.delete(did as String)          // già tolta (ripetizione) = va bene lo stesso
                return SLHTTPResponse.status(200, json = linkedMapOf<String, Any?>("ok" to true))
            }
            val r = arc.save(json)
            if (r.id == null) return SLError(if (r.error == "payload too large") 413 else 400, r.error ?: "archive error")
            return SLHTTPResponse.status(200, json = linkedMapOf<String, Any?>("ok" to true, "id" to r.id))
        }
        // ANDROID (funzioni mancanti 22/11, IN-14): trend dei parametri a schermo. GET [?since=<s di scenario>][&step=<s>]
        //   → {"ok":true,"interval_s":5,"scenario_time_s":…,"samples":[{t,w,rhythm,hr,spo2,pr,rr,etco2,art_s,art_d,art_m,nibp_s,…}]}
        if (p == "/api/v1/trend") {
            if (m != "GET") return SLError(405, "method not allowed")
            val deny = checkAuth(req); if (deny != null) return deny
            val c = clientOf(req); if (c != null) control.seen(c[0])
            var since = 0.0; var step = 0
            for (kv in (req.query ?: "").split("&")) {
                if (kv.startsWith("since=")) since = kv.substring(6).toDoubleOrNull() ?: 0.0
                if (kv.startsWith("step=")) step = Math.max(0, Math.min(3600, kv.substring(5).toIntOrNull() ?: 0))
            }
            var rows = state.trendSince(since, step)
            if (rows.size > 3000) rows = rows.subList(rows.size - 3000, rows.size)
            return SLHTTPResponse.status(200, json = linkedMapOf<String, Any?>("ok" to true, "error" to null, "interval_s" to state.trendIntervalS().toLong(),
                "scenario_time_s" to Math.floor(state.snapshot().scenarioTime).toLong(), "samples" to rows))
        }
        if (p == "/api/v1/nibp/start") {
            if (m != "POST") return SLError(405, "method not allowed")
            var deny = checkAuth(req); if (deny != null) return deny
            val c = clientOf(req)
            deny = checkControl(c); if (deny != null) return deny
            val ck = cmdKey(req, c)
            if (alreadyDone(ck)) return okWithState(c?.get(0))          // ANDROID (audit 21/11): ripetizione
            val err = state.startNibpMeasurement()
            if (err != null) return SLError(409, err)
            markDone(ck)
            return okWithState(c?.get(0))
        }
        // ANDROID (sala 22/11): «Sala» in diretta (solo il controller che comanda; il flusso si chiude se perde il comando)
        if (p.startsWith("/api/v1/live/")) {
            val lh = liveHandler ?: return SLError(404, "not found")
            if (m != "GET") return SLError(405, "method not allowed")
            var deny = checkAuth(req); if (deny != null) return deny
            val c = clientOf(req) ?: return SLError(403, "a follower device cannot open the room view")
            deny = checkControl(c); if (deny != null) return deny
            val cid = c[0]
            return lh(p.substring("/api/v1/live/".length), req.query ?: "", { control.infoFor(cid)["mine"] == true })
        }
        return SLError(404, "not found")
    }
}
