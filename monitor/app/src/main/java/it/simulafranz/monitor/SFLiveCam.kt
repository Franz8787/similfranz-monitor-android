// ANDROID (sala 22/11): nessun file corrispondente sull'iPad. «Sala» in diretta per il controller che comanda:
// fotocamera FRONTALE del monitor come MJPEG (GET /api/v1/live/video, multipart/x-mixed-replace) e microfono come PCM
// 16 bit mono 16 kHz little-endian (GET /api/v1/live/audio). Decisione dell'utente: SOLO DIRETTA. Niente viene salvato
// su disco: i fotogrammi e l'audio stanno solo in memoria (l'ultimo fotogramma, al massimo 1 s di audio) e spariscono
// alla chiusura.
// - Fotocamera e microfono si aprono solo quando un controller autorizzato (PIN + posto di comando) apre il flusso, e si
//   chiudono 2 s dopo che l'ultimo flusso finisce: controller che chiude, che non legge più (scrittura ferma 5 s), rete
//   caduta, controller che perde il comando, oppure scadenza (10 min: il controller riapre da solo).
// - Mentre sono accesi, sul monitor c'è SEMPRE l'avviso «● IN DIRETTA» (fascia destra, sopra tutto, anche in pausa):
//   i partecipanti devono saperlo.
// - Carico: fotocamera 640×480 (o 320×240 con ?size=320) a 8 fps, JPEG qualità 60; thread a priorità bassa, fuori dal
//   lucchetto dell'API (i comandi non aspettano mai il video).
package it.simulafranz.monitor

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.Image
import android.media.ImageReader
import android.media.MediaRecorder
import android.os.Handler
import android.os.HandlerThread
import android.os.Process
import android.util.Range
import android.view.Surface
import it.simulafranz.core.Json
import it.simulafranz.core.SFLog
import it.simulafranz.core.SLHTTPResponse
import it.simulafranz.uk.*
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.util.Locale
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

object SFLiveCam {
    const val kPermRequest = 72
    private const val kRate = 16000
    private const val kChunk = 1280                // 40 ms di audio (640 campioni da 2 byte)
    private const val kFps = 8
    private const val kMaxStreamS = 600.0          // scadenza di un flusso: il controller lo riapre da solo
    private const val kBoundary = "sfframe"

    private var act: Activity? = null
    /** main thread: avviso sul monitor (fotocamera accesa, microfono acceso) */
    var onIndicator: ((Boolean, Boolean) -> Unit)? = null

    private val lock = Object()
    private val ctl = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "it.simulafranz.live.ctl").apply { isDaemon = true } }
    private var videoUsers = 0; private var audioUsers = 0
    private var audioGen = 0
    // fotocamera
    private var camThread: HandlerThread? = null; private var camHandler: Handler? = null
    private var camDev: CameraDevice? = null; private var camSession: CameraCaptureSession? = null; private var reader: ImageReader? = null
    @Volatile private var camOn = false
    @Volatile private var camError: String? = null
    private var frame: ByteArray? = null; private var frameSeq = 0L; private var frameRot = 0; private var frameW = 0; private var frameH = 0
    private var lastFrameAt = 0L; private var sensorOrient = 270
    private var wantSize = 640
    // microfono
    @Volatile private var micOn = false
    private var micThread: Thread? = null
    private val audioQ = ArrayBlockingQueue<ByteArray>(25)      // al massimo 1 s in memoria: chi resta indietro perde il più vecchio
    @Volatile private var micError: String? = null
    // contatori per la verifica (status)
    @Volatile private var framesSent = 0L; @Volatile private var audioBytesSent = 0L

    fun attach(a: Activity) { act = a }
    // ANDROID (audit 26/11, BUG-A3): l'attività chiusa non resta trattenuta qui (solo se è ancora quella collegata)
    fun detach(a: Activity) { if (act === a) { act = null; onIndicator = null } }

    private fun hasPerm(p: String): Boolean = act?.checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED

    private fun askPerms() {
        val a = act ?: return
        Uk.async {
            val need = listOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO).filter { a.checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
            if (need.isNotEmpty()) { SFLog.log("sala: chiedo i permessi " + need.joinToString()); a.requestPermissions(need.toTypedArray(), kPermRequest) }
        }
    }

    /** dalla MainActivity: true se la risposta era nostra */
    fun onPermissionResult(code: Int, perms: Array<out String>, res: IntArray): Boolean {
        if (code != kPermRequest) return false
        for (i in perms.indices) SFLog.log(String.format(Locale.US, "sala: permesso %s %s", perms[i].substringAfterLast('.'), if (res.getOrNull(i) == PackageManager.PERMISSION_GRANTED) "concesso" else "NEGATO"))
        return true
    }

    private fun err(status: Int, code: String, msg: String) =
        SLHTTPResponse.status(status, json = linkedMapOf<String, Any?>("ok" to false, "error" to code, "message" to msg))

    // MARK: - API

    fun handle(kind: String, query: String, allowed: () -> Boolean): SLHTTPResponse {
        val q = HashMap<String, String>()
        for (kv in query.split("&")) { val i = kv.indexOf('='); if (i > 0) q[kv.substring(0, i)] = kv.substring(i + 1) }
        when (kind) {
            "status" -> return SLHTTPResponse.status(200, json = linkedMapOf<String, Any?>("ok" to true,
                "camera_permission" to hasPerm(Manifest.permission.CAMERA), "mic_permission" to hasPerm(Manifest.permission.RECORD_AUDIO),
                "camera_on" to camOn, "mic_on" to micOn, "camera_error" to camError, "mic_error" to micError,
                "video_users" to videoUsers, "audio_users" to audioUsers, "frames_sent" to framesSent, "audio_bytes_sent" to audioBytesSent,
                "size" to String.format(Locale.US, "%dx%d", frameW, frameH), "luma" to luma, "fps" to kFps, "audio" to "s16le;16000;1"))
            "video" -> {
                if (!hasPerm(Manifest.permission.CAMERA)) { askPerms(); return err(403, "camera_permission", "Sul monitor manca il permesso della fotocamera: tocca «Consenti» sul monitor") }
                synchronized(lock) { if (videoUsers >= 2) return err(429, "busy", "video già aperto") }
                if (q["size"] == "320") wantSize = 320 else if (videoUsers == 0) wantSize = 640
                val r = SLHTTPResponse.status(200, "multipart/x-mixed-replace; boundary=$kBoundary", null)
                r.extraHeaders = mapOf("X-SF-Live" to "video")
                r.stream = { out -> runVideo(out, allowed) }
                return r
            }
            "audio" -> {
                if (!hasPerm(Manifest.permission.RECORD_AUDIO)) { askPerms(); return err(403, "mic_permission", "Sul monitor manca il permesso del microfono: tocca «Consenti» sul monitor") }
                val r = SLHTTPResponse.status(200, "audio/L16; rate=16000; channels=1", null)
                r.extraHeaders = mapOf("X-SF-Live" to "audio", "X-SF-Audio" to "s16le;16000;1")
                r.stream = { out -> runAudio(out, allowed) }
                return r
            }
        }
        return err(404, "not_found", "not found")
    }

    // MARK: - Flussi (thread della connessione HTTP, priorità bassa)

    private inline fun lowPriority(block: () -> Unit) {
        val tid = Process.myTid(); val old = try { Process.getThreadPriority(tid) } catch (e: Exception) { 0 }
        try { Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND) } catch (e: Exception) {}
        try { block() } finally { try { Process.setThreadPriority(old) } catch (e: Exception) {} }      // il thread torna al gruppo dell'HTTP
    }

    private fun runVideo(out: OutputStream, allowed: () -> Boolean) = lowPriority {
        synchronized(lock) { videoUsers++; if (videoUsers == 1) ctl.execute { startCamera() } }
        changed()
        SFLog.log("sala: video aperto")
        val t0 = System.nanoTime(); var lastCheck = t0; var seen = -1L; var lastAny = System.nanoTime()
        try {
            while (true) {
                val now = System.nanoTime()
                if ((now - t0) / 1e9 > kMaxStreamS) break
                if (now - lastCheck > 1_000_000_000L) { lastCheck = now; if (!allowed()) { SFLog.log("sala: il controller non comanda più, video chiuso"); break } }
                var data: ByteArray? = null; var rot = 0
                synchronized(lock) {
                    if (frameSeq == seen) (lock as Object).wait(500)
                    if (frameSeq != seen) { data = frame; rot = frameRot; seen = frameSeq }
                }
                val d = data
                if (d == null) {
                    if (camError != null && (System.nanoTime() - lastAny) > 3_000_000_000L) break     // fotocamera guasta: il controller lo dice
                    if ((System.nanoTime() - lastAny) > 8_000_000_000L) break
                    continue
                }
                lastAny = System.nanoTime()
                val head = String.format(Locale.US, "--%s\r\nContent-Type: image/jpeg\r\nContent-Length: %d\r\nX-SF-T: %d\r\nX-SF-Rot: %d\r\n\r\n",
                    kBoundary, d.size, System.currentTimeMillis(), rot).toByteArray(Charsets.US_ASCII)
                out.write(head); out.write(d); out.write("\r\n".toByteArray(Charsets.US_ASCII))
                framesSent++
                if (framesSent % 80 == 1L) SFLog.log(String.format(Locale.US, "sala: %d fotogrammi inviati (%d byte l'ultimo, luminosità %d/255, rotazione %d°)", framesSent, d.size, luma, rot))
            }
        } catch (e: java.io.IOException) {
        } finally {
            synchronized(lock) { videoUsers--; if (videoUsers == 0) ctl.schedule({ synchronized(lock) { if (videoUsers == 0) stopCamera() } }, 2, TimeUnit.SECONDS) }
            SFLog.log("sala: video chiuso")
            changed()
        }
    }

    private fun runAudio(out: OutputStream, allowed: () -> Boolean) = lowPriority {
        val gen: Int
        synchronized(lock) { audioGen++; gen = audioGen; audioUsers++; if (audioUsers == 1) ctl.execute { startMic() } }
        audioQ.clear()
        changed()
        SFLog.log("sala: audio aperto")
        val t0 = System.nanoTime(); var lastCheck = t0; var silentSince = System.nanoTime()
        try {
            while (true) {
                val now = System.nanoTime()
                if ((now - t0) / 1e9 > kMaxStreamS) break
                if (gen != audioGen) break                           // un ascolto più recente prende il microfono
                if (now - lastCheck > 1_000_000_000L) { lastCheck = now; if (!allowed()) { SFLog.log("sala: il controller non comanda più, audio chiuso"); break } }
                val c = audioQ.poll(500, TimeUnit.MILLISECONDS)
                if (c == null) { if (micError != null || System.nanoTime() - silentSince > 6_000_000_000L) break; continue }
                silentSince = System.nanoTime()
                out.write(c)
                audioBytesSent += c.size
            }
        } catch (e: java.io.IOException) {
        } finally {
            synchronized(lock) { audioUsers--; if (audioUsers == 0) ctl.schedule({ synchronized(lock) { if (audioUsers == 0) stopMic() } }, 2, TimeUnit.SECONDS) }
            SFLog.log("sala: audio chiuso")
            changed()
        }
    }

    private fun changed() {
        val cam = camOn || videoUsers > 0; val mic = micOn || audioUsers > 0
        Uk.async { onIndicator?.invoke(cam, mic) }
    }

    /** chiusura dell'app: spegne tutto subito */
    fun stopAll() { ctl.execute { synchronized(lock) { stopCamera(); stopMic() } } }

    // MARK: - Fotocamera frontale (Camera2 + ImageReader YUV → JPEG in memoria)

    @Suppress("DEPRECATION")
    private fun startCamera() {
        synchronized(lock) { if (camOn || videoUsers == 0) return; camOn = true; camError = null }
        changed()
        val a = act ?: return
        try {
            val cm = a.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val id = cm.cameraIdList.firstOrNull { cm.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_FRONT }
                ?: cm.cameraIdList.firstOrNull() ?: throw IllegalStateException("nessuna fotocamera")
            val ch = cm.getCameraCharacteristics(id)
            sensorOrient = ch.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 270
            val map = ch.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)!!
            val sizes = map.getOutputSizes(ImageFormat.YUV_420_888).toList()
            // la più piccola ≥ della larghezza voluta, preferendo il 4:3
            val ok = sizes.filter { it.width >= wantSize && it.width * 3 == it.height * 4 }.ifEmpty { sizes.filter { it.width >= wantSize } }.ifEmpty { sizes }
            val size = ok.minByOrNull { it.width * it.height }!!
            // gamma di fps che scende di più (esposizione più lunga: più luce in una sala in penombra), poi la più bassa in alto
            val ranges = ch.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES) ?: emptyArray()
            val fr: Range<Int>? = ranges.filter { it.upper >= 10 }.minWithOrNull(compareBy<Range<Int>>({ it.lower }, { it.upper }))
            SFLog.log("sala: gamme fps disponibili " + ranges.joinToString())
            val ht = HandlerThread("it.simulafranz.live.cam", Process.THREAD_PRIORITY_BACKGROUND); ht.start()
            val h = Handler(ht.looper)
            camThread = ht; camHandler = h
            val rd = ImageReader.newInstance(size.width, size.height, ImageFormat.YUV_420_888, 3)
            reader = rd
            frameW = size.width; frameH = size.height
            val minGapNs = 1_000_000_000L / kFps * 8 / 10        // margine: una fotocamera a 8 fps con un po' di tremolio non perde metà dei fotogrammi
            rd.setOnImageAvailableListener({ r ->
                val img = try { r.acquireLatestImage() } catch (e: Exception) { null } ?: return@setOnImageAvailableListener
                try {
                    val now = System.nanoTime()
                    if (now - lastFrameAt < minGapNs) return@setOnImageAvailableListener
                    lastFrameAt = now
                    val jpg = toJpeg(img)
                    val rot = rotation()
                    synchronized(lock) { frame = jpg; frameRot = rot; frameSeq++; (lock as Object).notifyAll() }
                } catch (e: Exception) { camError = "conversione: $e" } finally { img.close() }
            }, h)
            SFLog.log(String.format(Locale.US, "sala: apro la fotocamera %s (%dx%d, fps %s, sensore %d°)", id, size.width, size.height, fr?.toString() ?: "?", sensorOrient))
            cm.openCamera(id, object : CameraDevice.StateCallback() {
                override fun onOpened(d: CameraDevice) {
                    synchronized(lock) { if (!camOn) { d.close(); return } ; camDev = d }
                    try {
                        d.createCaptureSession(listOf(rd.surface), object : CameraCaptureSession.StateCallback() {
                            override fun onConfigured(s: CameraCaptureSession) {
                                synchronized(lock) { if (!camOn) { s.close(); return }; camSession = s }
                                try {
                                    val b = d.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
                                    b.addTarget(rd.surface)
                                    if (fr != null) b.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, fr)
                                    b.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
                                    s.setRepeatingRequest(b.build(), null, h)
                                } catch (e: Exception) { camError = "avvio: $e"; SFLog.log("sala: fotocamera non avviata: $e") }
                            }
                            override fun onConfigureFailed(s: CameraCaptureSession) { camError = "configurazione non riuscita"; SFLog.log("sala: configurazione della fotocamera non riuscita") }
                        }, h)
                    } catch (e: Exception) { camError = "sessione: $e"; SFLog.log("sala: sessione non creata: $e") }
                }
                override fun onDisconnected(d: CameraDevice) { camError = "fotocamera scollegata"; try { d.close() } catch (e: Exception) {} }
                override fun onError(d: CameraDevice, error: Int) { camError = "errore fotocamera $error"; SFLog.log("sala: errore della fotocamera $error"); try { d.close() } catch (e: Exception) {} }
            }, h)
        } catch (e: SecurityException) { camError = "permesso della fotocamera negato"; SFLog.log("sala: $e")
        } catch (e: Exception) { camError = e.toString(); SFLog.log("sala: fotocamera non aperta: $e") }
    }

    private fun stopCamera() {
        if (!camOn) return
        camOn = false
        try { camSession?.close() } catch (e: Exception) {}
        try { camDev?.close() } catch (e: Exception) {}
        try { reader?.close() } catch (e: Exception) {}
        camSession = null; camDev = null; reader = null
        camThread?.quitSafely(); camThread = null; camHandler = null
        frame = null; frameSeq++                            // niente fotogrammi vecchi in memoria
        SFLog.log("sala: fotocamera spenta")
        changed()
    }

    // rotazione da applicare sul controller per vedere l'immagine dritta (fotocamera frontale, schermo in orizzontale)
    @Suppress("DEPRECATION")
    private fun rotation(): Int {
        val r = act?.windowManager?.defaultDisplay?.rotation ?: Surface.ROTATION_90
        val deg = when (r) { Surface.ROTATION_90 -> 90; Surface.ROTATION_180 -> 180; Surface.ROTATION_270 -> 270; else -> 0 }
        return (sensorOrient + deg) % 360
    }

    private var nv21: ByteArray? = null
    @Volatile private var luma = 0                       // luminosità media (0…255) dell'ultimo fotogramma, per la verifica
    private val jpegOut = ByteArrayOutputStream(64 * 1024)

    private fun toJpeg(img: Image): ByteArray {
        val w = img.width; val h = img.height
        var buf = nv21
        if (buf == null || buf.size != w * h * 3 / 2) { buf = ByteArray(w * h * 3 / 2); nv21 = buf }
        val yP = img.planes[0]; val uP = img.planes[1]; val vP = img.planes[2]
        val yB = yP.buffer; val ys = yP.rowStride
        var o = 0
        for (row in 0 until h) { yB.position(row * ys); yB.get(buf, o, w); o += w }
        var sum = 0L; var n = 0; var k = 0
        while (k < w * h) { sum += buf[k].toInt() and 0xff; n++; k += 997 }
        luma = if (n > 0) (sum / n).toInt() else 0
        val uB = uP.buffer; val vB = vP.buffer; val cs = uP.rowStride; val ps = uP.pixelStride
        for (row in 0 until h / 2) {
            val base = row * cs
            for (col in 0 until w / 2) {
                val i = base + col * ps
                buf[o++] = vB.get(i); buf[o++] = uB.get(i)
            }
        }
        jpegOut.reset()
        YuvImage(buf, ImageFormat.NV21, w, h, null).compressToJpeg(Rect(0, 0, w, h), 60, jpegOut)
        return jpegOut.toByteArray()
    }

    // MARK: - Microfono (AudioRecord 16 kHz mono, in memoria)

    private fun startMic() {
        synchronized(lock) { if (micOn || audioUsers == 0) return; micOn = true; micError = null }
        changed()
        val t = Thread({
            var ar: AudioRecord? = null
            try {
                val min = AudioRecord.getMinBufferSize(kRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
                ar = AudioRecord(MediaRecorder.AudioSource.MIC, kRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, Math.max(min, kChunk * 8))
                if (ar.state != AudioRecord.STATE_INITIALIZED) throw IllegalStateException("microfono non disponibile")
                ar.startRecording()
                SFLog.log("sala: microfono acceso (16 kHz mono)")
                while (micOn) {
                    val c = ByteArray(kChunk); var got = 0
                    while (got < kChunk && micOn) { val n = ar.read(c, got, kChunk - got); if (n <= 0) throw IllegalStateException("lettura $n"); got += n }
                    if (!micOn) break
                    if (!audioQ.offer(c)) { audioQ.poll(); audioQ.offer(c) }
                }
            } catch (e: SecurityException) { micError = "permesso del microfono negato"; SFLog.log("sala: $e")
            } catch (e: Exception) { micError = e.toString(); SFLog.log("sala: microfono: $e")
            } finally {
                try { ar?.stop() } catch (e: Exception) {}
                try { ar?.release() } catch (e: Exception) {}
                audioQ.clear()
            }
        }, "it.simulafranz.live.mic")
        t.isDaemon = true; t.start(); micThread = t
    }

    private fun stopMic() {
        if (!micOn) return
        micOn = false
        micThread = null                 // il thread finisce da solo entro 40 ms e libera il microfono
        SFLog.log("sala: microfono spento")
        changed()
    }
}

/** Avviso sul monitor: «● IN DIRETTA» + che cosa è acceso. Sta sopra tutto (radice della finestra), anche in pausa. */
class SFLiveBadge(private val root: UIView, bandWidth: Double, canvasWidth: Double) : UIView(CGRectZero) {
    private val dot = UIView(CGRectMake(7, 5, 8, 8))
    private val title = UILabel(CGRectMake(19, 1, 74, 15))
    private val what = UILabel(CGRectMake(7, 15, 86, 12))
    private var timer: NSTimer? = null

    init {
        // nella fascia destra se c'è (fuori dalla tela del dispositivo), altrimenti nell'angolo in alto a destra
        val W = 96.0; val H = 29.0
        frame = if (bandWidth >= W + 4) CGRectMake(bandWidth + canvasWidth + (bandWidth - W) / 2, 2, W, H) else CGRectMake(root.bounds.width - W - 4, 2, W, H)
        backgroundColor = UIColor.colorWithRed(0.10, 0.02, 0.02, 0.88)
        layer.cornerRadius = 6.0; layer.borderWidth = 1.0; layer.borderColor = UIColor.colorWithRed(0.95, 0.20, 0.20)
        userInteractionEnabled = false
        dot.backgroundColor = UIColor.colorWithRed(1.0, 0.18, 0.18); dot.layer.cornerRadius = 4.0; addSubview(dot)
        title.text = "IN DIRETTA"; title.font = UIFont.boldSystemFontOfSize(11); title.textColor = UIColor.colorWithRed(1.0, 0.45, 0.45)
        title.adjustsFontSizeToFitWidth = true; title.minimumScaleFactor = 0.7; addSubview(title)
        what.font = UIFont.systemFontOfSize(9); what.textColor = UIColor.colorWithWhite(0.85); what.adjustsFontSizeToFitWidth = true; what.minimumScaleFactor = 0.7
        addSubview(what)
        hidden = true
        accessibilityIdentifier = "live.badge"
    }

    fun show(cam: Boolean, mic: Boolean) {
        val on = cam || mic
        what.text = if (cam && mic) "Sala: video e audio" else if (cam) "Sala: video" else "Sala: audio"
        if (on && hidden) SFLog.log("sala: avviso IN DIRETTA a schermo (" + what.text + ")")
        hidden = !on
        if (on) {
            root.bringSubviewToFront(this)
            if (timer == null) timer = NSTimer.scheduledTimerWithTimeInterval(1.0, true) { root.bringSubviewToFront(this) }   // sopra anche alle finestre aperte dopo
        } else { timer?.invalidate(); timer = null }
    }
}
