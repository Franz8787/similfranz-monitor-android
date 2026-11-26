// ANDROID (sala 22/11): nessun file corrispondente sull'iPad. «Sala» in diretta: immagine della fotocamera frontale del
// monitor (MJPEG, GET /api/v1/live/video) e audio del suo microfono (PCM 16 bit mono 16 kHz, GET /api/v1/live/audio).
// Decisione dell'utente: SOLO DIRETTA, niente registrazione, niente salvato su disco (qui solo l'ultimo fotogramma in memoria).
// - Ogni flusso ha la sua connessione e il suo thread: i comandi della regia (SFLink, gruppo di thread a parte) non
//   aspettano mai la «Sala». Il video si decodifica a priorità bassa; si mostra solo l'ultimo fotogramma (niente coda).
// - L'audio resta acceso anche chiudendo il pannello (si ascolta la squadra mentre si regola il paziente); il video si
//   ferma appena il pannello non è a schermo.
// - Se il collegamento cade (o il controller perde il comando) tutto si chiude da solo e la regia lo dice.
// - Ritardo: l'audio tiene al massimo ~0,4 s in coda (oltre, salta avanti); il video mostra sempre l'ultimo fotogramma.
package it.simulafranz.controller

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Process
import it.simulafranz.core.Json
import it.simulafranz.core.SFLog
import it.simulafranz.uk.*
import java.io.InputStream
import java.net.HttpURLConnection
import java.util.Locale

class CtlSala(private val m: CtlModel) {
    @Volatile var videoOn = false; private set
    @Volatile var audioOn = false; private set
    var volume: Double = 0.8; private set
    /** main thread: nuovo fotogramma (già ruotato) */
    var onFrame: ((Bitmap) -> Unit)? = null
    /** main thread: qualcosa da ridisegnare (stato, errori, chiusura) */
    var onChange: (() -> Unit)? = null
    /** main thread: chiusura da sola (collegamento caduto, comando perso): testo per la regia */
    var onClosed: ((String) -> Unit)? = null
    var lowQuality = false
    // stato per il pannello
    @Volatile var videoError: String? = null; @Volatile var audioError: String? = null
    @Volatile var fps = 0.0; @Volatile var kbps = 0.0; @Volatile var delayMs = -1L
    @Volatile var audioKbps = 0.0; @Volatile var audioLevel = 0.0; @Volatile var audioBufMs = 0L
    @Volatile var framesGot = 0L; @Volatile var audioBytesGot = 0L
    @Volatile var videoWaiting = false; @Volatile var audioWaiting = false
    private var vGen = 0; private var aGen = 0
    @Volatile private var vConn: HttpURLConnection? = null; @Volatile private var aConn: HttpURLConnection? = null
    @Volatile private var track: AudioTrack? = null
    private var framePosted = false
    private val decodeOpts = BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.RGB_565 }    // metà memoria
    private var watch: NSTimer? = null

    private fun changed() { Uk.async { onChange?.invoke() } }

    // MARK: - Comandi (main thread)

    fun startVideo() {
        if (videoOn) return
        videoOn = true; videoError = null; vGen++; videoWaiting = true
        val g = vGen
        Thread({ videoLoop(g) }, "it.simulafranz.sala.video").apply { isDaemon = true }.start()
        ensureWatch(); changed()
    }
    fun stopVideo() {
        if (!videoOn) return
        videoOn = false; vGen++
        val c = vConn; vConn = null
        Thread { try { c?.disconnect() } catch (e: Exception) {} }.start()
        fps = 0.0; kbps = 0.0; delayMs = -1
        stopWatchIfIdle(); changed()
    }
    fun restartVideo() { if (videoOn) { stopVideo(); startVideo() } }
    // ANDROID (fix analisi 22/11, BUG-06): l'errore del video (423 comanda un altro, 404 monitor senza Sala, monitor che non
    // risponde) resta finché l'istruttore non chiede di riprovare: il pannello NON riapre più da solo il video (prima lo faceva a
    // ogni ridisegno, in un ciclo continuo di connessioni rifiutate). Si azzera aprendo «Sala», con «Riprova» o con «Qualità».
    fun retryVideo() { if (!videoOn) videoError = null }
    fun setAudio(on: Boolean) {
        if (on == audioOn) return
        audioOn = on; aGen++
        if (on) {
            audioError = null; audioWaiting = true
            val g = aGen
            Thread({ audioLoop(g) }, "it.simulafranz.sala.audio").apply { isDaemon = true }.start()
            ensureWatch()
        } else {
            val c = aConn; aConn = null
            Thread { try { c?.disconnect() } catch (e: Exception) {} }.start()
            audioKbps = 0.0; audioLevel = 0.0
            stopWatchIfIdle()
        }
        changed()
    }
    fun setVolume(v: Double) { volume = Math.max(0.0, Math.min(1.0, v)); try { track?.setVolume(volume.toFloat()) } catch (e: Exception) {} }
    fun stopAll() { stopVideo(); setAudio(false) }

    // il collegamento cade: tutto si chiude da solo
    private fun ensureWatch() {
        if (watch != null) return
        val s0 = m.link.liveSession()
        watch = NSTimer.scheduledTimerWithTimeInterval(1.0, true) {
            val s = m.link.liveSession()
            if ((videoOn || audioOn) && (s < 0 || s != s0)) {
                SFLog.log("sala: collegamento caduto, video e audio chiusi")
                stopAll(); onClosed?.invoke("Sala chiusa: il collegamento con il monitor è caduto")
            }
        }
    }
    private fun stopWatchIfIdle() { if (!videoOn && !audioOn) { watch?.invalidate(); watch = null } }

    private fun stopFromThread(video: Boolean, gen: Int, why: String) {
        Uk.async {
            if (video && gen == vGen && videoOn) { stopVideo(); videoError = why; onClosed?.invoke(why) }
            if (!video && gen == aGen && audioOn) { setAudio(false); audioError = why; onClosed?.invoke(why) }
            onChange?.invoke()
        }
    }

    private fun errorText(c: HttpURLConnection, st: Int): String {
        val txt = try { c.errorStream?.use { it.readBytes().toString(Charsets.UTF_8) } ?: "" } catch (e: Exception) { "" }
        val j = Json.parseOrNull(txt) as? Map<*, *>
        val msg = j?.get("message") as? String
        return when {
            st == 423 -> "Sala non disponibile: comanda un altro controller"
            st == 401 -> "Sala non disponibile: PIN non valido"
            st == 404 -> "Questo monitor non ha ancora la «Sala»: aggiorna similFranz Monitor"
            msg != null -> msg
            else -> "Sala non disponibile (errore $st)"
        }
    }

    // MARK: - Video (MJPEG)

    private fun readLine(ins: InputStream, sb: StringBuilder): String? {
        sb.setLength(0)
        while (true) {
            val b = ins.read(); if (b < 0) return null
            if (b == '\n'.code) { if (sb.isNotEmpty() && sb[sb.length - 1] == '\r') sb.setLength(sb.length - 1); return sb.toString() }
            sb.append(b.toChar()); if (sb.length > 512) return null
        }
    }

    private fun videoLoop(g: Int) {
        try { Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND) } catch (e: Exception) {}
        var tries = 0
        while (videoOn && g == vGen) {
            val c = m.link.openStream("/api/v1/live/video" + (if (lowQuality) "?size=320" else ""), m.liveHeaders(), 6000)
            if (c == null) { if (++tries > 3) { stopFromThread(true, g, "Sala chiusa: il monitor non risponde"); return }; Thread.sleep(1000); continue }
            vConn = c
            try {
                val st = c.responseCode
                if (st != 200) {
                    val e = errorText(c, st); SFLog.log("sala: video rifiutato ($st): $e")
                    if (st == 403 && ++tries <= 10) { videoError = e; changed(); Thread.sleep(2000); continue }     // permesso in attesa sul monitor
                    stopFromThread(true, g, e); return
                }
                tries = 0; videoError = null
                val ins = c.inputStream.buffered(32 * 1024)
                val sb = StringBuilder()
                var minDelta = Long.MAX_VALUE; var winStart = System.nanoTime(); var winFrames = 0; var winBytes = 0L
                SFLog.log("sala: video in arrivo")
                while (videoOn && g == vGen) {
                    var len = -1; var t = 0L; var rot = 0
                    var line = readLine(ins, sb) ?: break
                    while (line.isEmpty()) line = readLine(ins, sb) ?: break
                    if (!line.startsWith("--")) continue
                    while (true) {
                        val h = readLine(ins, sb) ?: break
                        if (h.isEmpty()) break
                        val i = h.indexOf(':'); if (i < 0) continue
                        val k = h.substring(0, i).trim().lowercase(Locale.ROOT); val v = h.substring(i + 1).trim()
                        when (k) { "content-length" -> len = v.toIntOrNull() ?: -1; "x-sf-t" -> t = v.toLongOrNull() ?: 0L; "x-sf-rot" -> rot = v.toIntOrNull() ?: 0 }
                    }
                    if (len <= 0 || len > 2_000_000) break
                    val buf = ByteArray(len); var got = 0
                    while (got < len) { val n = ins.read(buf, got, len - got); if (n < 0) break; got += n }
                    if (got < len) break
                    val now = System.currentTimeMillis()
                    // ritardo: il minimo di (arrivo − partenza) vale come «quasi zero» (orologi diversi); sopra il minimo = ritardo
                    if (t > 0) { val d = now - t; if (d < minDelta) minDelta = d; delayMs = (d - minDelta) + 30 }
                    framesGot++; winFrames++; winBytes += len
                    val el = (System.nanoTime() - winStart) / 1e9
                    if (el >= 1.0) { fps = winFrames / el; kbps = winBytes / 1024.0 / el; winFrames = 0; winBytes = 0; winStart = System.nanoTime(); changed()
                        if (framesGot % 40 < 9) SFLog.log(String.format(Locale.US, "sala: video %.1f fps, %.0f kB/s, ritardo %d ms, %d fotogrammi", fps, kbps, delayMs, framesGot)) }
                    val behind = synchronized(this) { framePosted }
                    if (behind) continue                                   // il main thread è indietro: si salta (niente coda)
                    var bm = BitmapFactory.decodeByteArray(buf, 0, len, decodeOpts) ?: continue
                    if (rot % 360 != 0) { val mx = Matrix(); mx.postRotate(rot.toFloat()); bm = Bitmap.createBitmap(bm, 0, 0, bm.width, bm.height, mx, true) }
                    val b = bm
                    if (videoWaiting) { videoWaiting = false; changed() }
                    synchronized(this) { framePosted = true }
                    Uk.async { synchronized(this) { framePosted = false }; if (videoOn && g == vGen) onFrame?.invoke(b) }
                }
            } catch (e: Exception) {
                if (videoOn && g == vGen) SFLog.log("sala: video interrotto: $e")
            } finally { try { c.disconnect() } catch (e: Exception) {} }
            if (videoOn && g == vGen) { videoWaiting = true; changed(); Thread.sleep(700) }    // scaduto o interrotto: si riapre da solo
        }
    }

    // MARK: - Audio (PCM 16 bit mono 16 kHz)

    private fun audioLoop(g: Int) {
        val rate = 16000
        var tries = 0
        while (audioOn && g == aGen) {
            val c = m.link.openStream("/api/v1/live/audio", m.liveHeaders(), 6000)
            if (c == null) { if (++tries > 3) { stopFromThread(false, g, "Sala chiusa: il monitor non risponde"); return }; Thread.sleep(1000); continue }
            aConn = c
            var tr: AudioTrack? = null
            try {
                val st = c.responseCode
                if (st != 200) {
                    val e = errorText(c, st); SFLog.log("sala: audio rifiutato ($st): $e")
                    if (st == 403 && ++tries <= 10) { audioError = e; changed(); Thread.sleep(2000); continue }
                    stopFromThread(false, g, e); return
                }
                tries = 0; audioError = null
                val min = AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
                @Suppress("DEPRECATION")
                tr = AudioTrack(AudioManager.STREAM_MUSIC, rate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT,
                    Math.max(min, rate * 2 / 4), AudioTrack.MODE_STREAM)      // ~250 ms di buffer
                tr.setVolume(volume.toFloat())
                track = tr
                val ins = c.inputStream
                val buf = ByteArray(1280)
                var written = 0L; var started = false
                var winStart = System.nanoTime(); var winBytes = 0L; var lastLog = System.nanoTime()
                SFLog.log("sala: audio in arrivo")
                while (audioOn && g == aGen) {
                    var got = 0
                    while (got < buf.size) { val n = ins.read(buf, got, buf.size - got); if (n < 0) break; got += n }
                    if (got < buf.size) break
                    audioBytesGot += got; winBytes += got
                    // livello (RMS) per il contatore del pannello
                    var sum = 0.0; var i = 0
                    while (i + 1 < got) { val s = ((buf[i].toInt() and 0xff) or (buf[i + 1].toInt() shl 8)).toShort().toDouble(); sum += s * s; i += 2 }
                    audioLevel = Math.min(1.0, Math.sqrt(sum / (got / 2)) / 6000.0)
                    // quanto audio aspetta di essere suonato: oltre 400 ms si salta avanti (ritardo basso, niente eco della regia)
                    val played = if (started) (tr.playbackHeadPosition.toLong() and 0xffffffffL) * 2 else 0L
                    val queued = written - played
                    audioBufMs = queued * 1000 / (rate * 2)
                    val backlog = try { ins.available() } catch (e: Exception) { 0 }
                    if (queued > rate * 2 * 4 / 10 || backlog > rate * 2 * 4 / 10) { if (backlog > 0) ins.skip(backlog.toLong()); continue }
                    tr.write(buf, 0, got); written += got
                    if (!started && written >= rate * 2 / 10) { tr.play(); started = true; if (audioWaiting) { audioWaiting = false; changed() } }
                    val el = (System.nanoTime() - winStart) / 1e9
                    if (el >= 1.0) { audioKbps = winBytes / 1024.0 / el; winBytes = 0; winStart = System.nanoTime(); changed() }
                    if (System.nanoTime() - lastLog > 5_000_000_000L) { lastLog = System.nanoTime()
                        SFLog.log(String.format(Locale.US, "sala: audio %.1f kB/s, %d kB ricevuti, coda %d ms, livello %.2f", audioKbps, audioBytesGot / 1024, audioBufMs, audioLevel)) }
                }
            } catch (e: Exception) {
                if (audioOn && g == aGen) SFLog.log("sala: audio interrotto: $e")
            } finally {
                try { tr?.pause(); tr?.flush(); tr?.release() } catch (e: Exception) {}
                if (track === tr) track = null
                try { c.disconnect() } catch (e: Exception) {}
            }
            if (audioOn && g == aGen) { audioWaiting = true; changed(); Thread.sleep(700) }
        }
    }
}
