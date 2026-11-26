// Portato da src/LocalHTTPServer.m (monitor iPad)
// SimulaFranz_Monitor — server HTTP/1.1 minimale.
// - Una richiesta per connessione (Connection: close), sufficiente per il polling.
// - Limiti: header 8 KB, body 64 KB, timeout 5 s,
//   ANDROID (storia sim 22/11): 2 MB e 30 s per POST /api/v1/archive,
//   max 8 connessioni simultanee.
// - Non tocca mai il main thread.
// ANDROID: java.net.ServerSocket + un piccolo gruppo di thread al posto di socket BSD + GCD. Sull'iPad tutto gira
// su una coda seriale: qui la lettura è un thread per connessione, ma l'handler resta chiamato UNO ALLA VOLTA.
package it.simulafranz.core

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

private const val kMaxHeaderBytes = 8 * 1024
private const val kMaxBodyBytes = 64 * 1024   // come il collegamento diretto: uno scenario lungo ci sta
// ANDROID (storia sim 22/11): POST /api/v1/archive porta una simulazione salvata (con il registro): fino a 2 MB, 30 s
private const val kMaxArchiveBytes = 2 * 1024 * 1024
private const val kArchiveTimeoutNs = 30L * 1_000_000_000L
private const val kTimeoutNs = 5L * 1_000_000_000L
private const val kMaxConnections = 12    // ANDROID (sala 22/11): 8 + 4 per i flussi in diretta della «Sala» (video e audio)

class SLHTTPRequest {
    var method: String = ""
    var path: String = ""
    var query: String? = ""
    var headers: Map<String, String> = emptyMap() // chiavi minuscole
    var body: ByteArray? = ByteArray(0)
    var remoteAddress: String? = null
}

class SLHTTPResponse {
    var status: Int = 0
    var contentType: String? = null
    var body: ByteArray = ByteArray(0)
    var extraHeaders: Map<String, String>? = null
    // ANDROID (sala 22/11): risposta in diretta senza lunghezza (MJPEG, audio PCM). Se c'è, dopo l'intestazione il server la
    // chiama sul thread della connessione, FUORI dal lucchetto dell'handler (i comandi non aspettano), con un flusso in cui
    // ogni scrittura ferma per 5 s chiude il socket. Finisce quando ritorna o quando una scrittura fallisce (client andato).
    var stream: ((java.io.OutputStream) -> Unit)? = null

    companion object {
        fun status(status: Int, json: Any?): SLHTTPResponse {
            val d = try { Json.stringify(json).toByteArray(Charsets.UTF_8) } catch (e: Exception) { ByteArray(0) }
            return status(status, "application/json; charset=utf-8", d)
        }

        fun status(status: Int, type: String?, data: ByteArray?): SLHTTPResponse {
            val r = SLHTTPResponse()
            r.status = status; r.contentType = type; r.body = data ?: ByteArray(0)
            return r
        }
    }
}

typealias SLHTTPHandler = (SLHTTPRequest) -> SLHTTPResponse

private fun SLReason(s: Int): String = when (s) {
    200 -> "OK"; 204 -> "No Content"
    400 -> "Bad Request"; 401 -> "Unauthorized"
    404 -> "Not Found"; 405 -> "Method Not Allowed"
    408 -> "Request Timeout"; 409 -> "Conflict"
    413 -> "Payload Too Large"; 429 -> "Too Many Requests"
    431 -> "Request Header Fields Too Large"
    503 -> "Service Unavailable"
    else -> if (s < 400) "OK" else "Error"
}

private fun SLErrorBody(status: Int, msg: String): SLHTTPResponse =
    SLHTTPResponse.status(status, json = linkedMapOf<String, Any?>("ok" to false, "error" to msg))

// ANDROID (audit 26/11, RETE-R05): le richieste che leggono o scrivono file grossi (archivio Storia Sim, registro, trend)
// hanno un lucchetto loro: un archivio da 1–2 MB che si salva non ferma più comandi e letture dello stato. Archivio, registro
// e stato sono già protetti ognuno dal proprio synchronized; fra due richieste «pesanti» resta l'ordine di arrivo.
private val kDiskLock = Any()
fun SLUsesDiskLock(path: String): Boolean = path == "/api/v1/archive" || path == "/api/v1/log" || path == "/api/v1/trend"

// MARK: - Connessione

private class SLHTTPConnection(
    private val fd: Socket,
    private val remote: String,
    private val handlerLock: Any,
    private val handler: SLHTTPHandler,
    private val watchdog: ScheduledExecutorService,
    private var onClose: ((SLHTTPConnection) -> Unit)?
) {
    private val buffer = ByteArrayOutputStream(1024)
    private var headerEnd = -1       // indice fine header (dopo \r\n\r\n), -1 se non ancora
    private var contentLength = 0
    @Volatile private var closed = false
    private var responded = false
    private var archive = false      // ANDROID (storia sim 22/11): POST /api/v1/archive

    // Legge la richiesta, chiama l'handler, risponde e chiude. Gira sul thread della connessione.
    fun startReading() {
        try {
            // timeout complessivo della richiesta (protezione da client lenti)
            var deadline = System.nanoTime() + kTimeoutNs
            val input = fd.getInputStream()
            val buf = ByteArray(4096)
            var req: SLHTTPRequest? = null
            while (!closed && !responded) {
                val left = deadline - System.nanoTime()
                if (left <= 0) { respond(SLErrorBody(408, "request timeout")); return }
                fd.soTimeout = Math.max(1L, left / 1_000_000L).toInt()
                val n = try { input.read(buf) } catch (e: SocketTimeoutException) {
                    respond(SLErrorBody(408, "request timeout")); return
                }
                if (n <= 0) { close(); return }
                buffer.write(buf, 0, n)

                if (headerEnd < 0) {
                    val data = buffer.toByteArray()
                    val r = indexOfHeaderEnd(data)
                    if (r < 0) {
                        if (data.size > kMaxHeaderBytes) { respond(SLErrorBody(431, "headers too large")); return }
                        continue
                    }
                    headerEnd = r + 4
                    if (headerEnd > kMaxHeaderBytes) { respond(SLErrorBody(431, "headers too large")); return }
                    val wasArchive = archive
                    req = parseHeaders(data)
                    // un pacchetto è più grande e più lento: limite e tempo dedicati
                    if (archive && !wasArchive) deadline = System.nanoTime() + kArchiveTimeoutNs   // ANDROID (storia sim 22/11)
                }
                val rq = req
                if (rq == null) { respond(SLErrorBody(400, "malformed request")); return }
                if (contentLength > (if (archive) kMaxArchiveBytes else kMaxBodyBytes)) {
                    respond(SLErrorBody(413, "payload too large")); return
                }
                val have = buffer.size() - headerEnd
                if (have < contentLength) continue   // attendo il resto del body
                rq.body = buffer.toByteArray().copyOfRange(headerEnd, headerEnd + contentLength)

                var resp: SLHTTPResponse? = null
                // ANDROID (fix analisi 22/11, F3): un'eccezione nell'handler diventava un 500 senza nessuna traccia nel log
                try { resp = synchronized(if (SLUsesDiskLock(rq.path)) kDiskLock else handlerLock) { handler(rq) } } catch (e: Throwable) {
                    resp = null
                    try {
                        val at = e.stackTrace.take(4).joinToString(" < ") { "${it.fileName}:${it.lineNumber}" }
                        SFLog.log("http: errore interno su ${rq.method} ${rq.path}: $e @ $at")
                    } catch (x: Throwable) {}
                }
                if (resp == null) resp = SLErrorBody(500, "internal error")
                respond(resp)
            }
        } catch (e: IOException) {
            close()
        } catch (e: RuntimeException) {
            close()
        } finally {
            close()
        }
    }

    private fun indexOfHeaderEnd(d: ByteArray): Int {
        var i = 0
        while (i + 3 < d.size) {
            if (d[i] == '\r'.code.toByte() && d[i + 1] == '\n'.code.toByte() && d[i + 2] == '\r'.code.toByte() && d[i + 3] == '\n'.code.toByte()) return i
            i++
        }
        return -1
    }

    // come NSScanner scanLongLong + isAtEnd: spazi iniziali/finali, segno facoltativo, solo cifre; fuori scala = estremo
    private fun scanLongLong(s: String): Long? {
        val t = s.trim()
        if (!Regex("[+-]?[0-9]+").matches(t)) return null
        return t.toLongOrNull() ?: if (t.startsWith("-")) Long.MIN_VALUE else Long.MAX_VALUE
    }

    private fun parseHeaders(data: ByteArray): SLHTTPRequest? {
        val head = String(data, 0, headerEnd, Charsets.ISO_8859_1)
        val lines = head.split("\r\n")
        if (lines.size < 1) return null
        val parts = lines[0].split(" ")
        if (parts.size != 3 || !parts[2].startsWith("HTTP/1.")) return null
        val target = parts[1]
        if (!target.startsWith("/") || target.length > 1024) return null

        val req = SLHTTPRequest()
        req.method = parts[0].uppercase(Locale.ROOT)
        val q = target.indexOf("?")
        req.path = if (q < 0) target else target.substring(0, q)
        req.query = if (q < 0) "" else target.substring(q + 1)
        req.remoteAddress = remote

        val h = LinkedHashMap<String, String>()
        for (i in 1 until lines.size) {
            val line = lines[i]
            if (line.isEmpty()) continue
            val c = line.indexOf(":")
            if (c < 0) return null
            val k = line.substring(0, c).lowercase(Locale.ROOT)
            val v = line.substring(c + 1).trim { it == ' ' || it == '\t' }
            h[k] = v
        }
        req.headers = h
        val cl = h["content-length"]
        if (cl != null) {
            val len = scanLongLong(cl)
            if (len == null || len < 0) return null
            val ar = req.method == "POST" && req.path == "/api/v1/archive"      // ANDROID (storia sim 22/11)
            if (ar && !archive) archive = true
            contentLength = Math.min(len, (if (ar) kMaxArchiveBytes else kMaxBodyBytes).toLong() + 1).toInt()
        } else {
            contentLength = 0
        }
        if (h.containsKey("transfer-encoding")) return null   // chunked non supportato
        return req
    }

    fun respond(resp: SLHTTPResponse) {
        if (closed || responded) return
        responded = true

        val head = StringBuilder(String.format(Locale.US, "HTTP/1.1 %d %s\r\n", resp.status, SLReason(resp.status)))
        head.append("Content-Type: ").append(resp.contentType ?: "text/plain").append("\r\n")
        val live = resp.stream
        if (live == null) head.append("Content-Length: ").append(resp.body.size).append("\r\n")
        head.append("Connection: close\r\nCache-Control: no-store\r\nX-Content-Type-Options: nosniff\r\n")
        resp.extraHeaders?.forEach { (k, v) -> head.append(k).append(": ").append(v).append("\r\n") }
        head.append("\r\n")
        if (live != null) { streamLive(head.toString(), live); close(); return }     // ANDROID (sala 22/11)
        val out = ByteArrayOutputStream(head.length + resp.body.size)
        out.write(head.toString().toByteArray(Charsets.US_ASCII))
        out.write(resp.body)
        val bytes = out.toByteArray()

        // scrittura bloccante con timeout di invio (su thread di rete, mai sul main thread)
        // ANDROID: java.net.Socket non ha SO_SNDTIMEO: si scrive a blocchi e, se un blocco resta fermo 5 s, si chiude il socket.
        try {
            val os = fd.getOutputStream()
            var p = 0
            while (p < bytes.size) {
                val w = Math.min(16 * 1024, bytes.size - p)
                val guard = watchdog.schedule({ try { fd.close() } catch (e: IOException) {} }, 5, TimeUnit.SECONDS)
                try { os.write(bytes, p, w); os.flush() } finally { guard.cancel(false) }
                p += w
            }
        } catch (e: IOException) {
        } catch (e: RuntimeException) {       // server fermato durante l'invio (watchdog chiuso)
        }
        close()
    }

    // ANDROID (sala 22/11): flusso in diretta. Ogni scrittura ha il suo cane da guardia di 5 s (client che non legge più o
    // rete caduta: il socket si chiude e il flusso finisce con un errore di scrittura).
    private fun streamLive(head: String, live: (java.io.OutputStream) -> Unit) {
        try {
            try { fd.sendBufferSize = 64 * 1024 } catch (e: Exception) {}
            val os = fd.getOutputStream()
            val guarded = object : java.io.OutputStream() {
                override fun write(b: Int) { write(byteArrayOf(b.toByte()), 0, 1) }
                override fun write(b: ByteArray, off: Int, len: Int) {
                    if (closed) throw IOException("closed")
                    val guard = watchdog.schedule({ try { fd.close() } catch (e: IOException) {} }, 5, TimeUnit.SECONDS)
                    try { os.write(b, off, len); os.flush() } finally { guard.cancel(false) }
                }
            }
            guarded.write(head.toByteArray(Charsets.US_ASCII))
            live(guarded)
        } catch (e: IOException) {
        } catch (e: RuntimeException) {
        } catch (e: InterruptedException) {
        }
    }

    fun close() {
        val cb: ((SLHTTPConnection) -> Unit)?
        synchronized(this) {
            if (closed) return
            closed = true
            cb = onClose
            onClose = null
        }
        try { fd.shutdownInput() } catch (e: IOException) {}
        try { fd.shutdownOutput() } catch (e: IOException) {}
        try { fd.close() } catch (e: IOException) {}
        cb?.invoke(this)
    }
}

// MARK: - Server

class LocalHTTPServer(val port: Int, private val handler: SLHTTPHandler) {
    @Volatile private var running = false
    val isRunning: Boolean get() = running

    private val handlerLock = Any()
    private var listenFD: ServerSocket? = null
    private var pool: ExecutorService? = null
    private var watchdog: ScheduledExecutorService? = null
    private val connections = HashSet<SLHTTPConnection>()

    // Ritorna il messaggio d'errore, oppure null se è partito
    @Synchronized
    fun start(): String? {
        if (running) return null
        val fd: ServerSocket
        try {
            fd = ServerSocket()
        } catch (e: IOException) {
            return String.format(Locale.US, "HTTP server %s: %s", "socket", e.message ?: e.toString())
        }
        try {
            fd.reuseAddress = true
            fd.bind(InetSocketAddress(port), 16)
        } catch (e: Exception) {
            try { fd.close() } catch (x: IOException) {}
            return String.format(Locale.US, "HTTP server %s: %s", "bind/listen", e.message ?: e.toString())
        }
        listenFD = fd
        val p = Executors.newCachedThreadPool { r -> Thread(r, "it.simulafranz.monitor.http").apply { isDaemon = true } }
        val w = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "it.simulafranz.monitor.http.timeout").apply { isDaemon = true } }
        pool = p; watchdog = w
        running = true
        Thread({ acceptConnections(fd, p, w) }, "it.simulafranz.monitor.http.accept").apply { isDaemon = true }.start()
        return null
    }

    private fun acceptConnections(fd: ServerSocket, pool: ExecutorService, watchdog: ScheduledExecutorService) {
        while (true) {
            val c: Socket
            try { c = fd.accept() } catch (e: IOException) { return }   // socket d'ascolto chiuso: server fermato
            catch (e: RuntimeException) { return }
            try {
                try { c.tcpNoDelay = true } catch (e: IOException) {}
                val ip = c.inetAddress?.hostAddress ?: "?"

                var conn: SLHTTPConnection? = null
                synchronized(connections) {
                    if (running && connections.size < kMaxConnections) {
                        conn = SLHTTPConnection(c, ip, handlerLock, handler, watchdog) { closed ->
                            synchronized(connections) { connections.remove(closed) }
                        }
                        connections.add(conn!!)
                    }
                }
                val cn = conn
                if (cn == null) {
                    // ANDROID (fix analisi 22/11, BUG-11): 503 con corpo JSON e Retry-After: la richiesta NON è stata letta né
                    // eseguita, i client (controller, web regia) la ritentano da soli invece di buttarla come un rifiuto
                    val bb = "{\"ok\":false,\"error\":\"busy\",\"message\":\"monitor occupato (troppe connessioni): riprova\"}"
                    val busy = "HTTP/1.1 503 Service Unavailable\r\nContent-Type: application/json; charset=utf-8\r\nRetry-After: 1\r\nContent-Length: ${bb.length}\r\nConnection: close\r\n\r\n$bb"
                    try { c.getOutputStream().write(busy.toByteArray(Charsets.US_ASCII)) } catch (e: IOException) {}
                    try { c.close() } catch (e: IOException) {}
                    continue
                }
                try { pool.execute { cn.startReading() } } catch (e: RuntimeException) { cn.close() }
            } catch (e: Throwable) {
                try { c.close() } catch (x: IOException) {}
            }
        }
    }

    @Synchronized
    fun stop() {
        if (!running) return
        running = false
        try { listenFD?.close() } catch (e: IOException) {}
        listenFD = null
        val copy = synchronized(connections) { ArrayList(connections) }
        for (c in copy) c.close()
        synchronized(connections) { connections.clear() }
        pool?.shutdown(); pool = null
        watchdog?.shutdownNow(); watchdog = null
    }
}
