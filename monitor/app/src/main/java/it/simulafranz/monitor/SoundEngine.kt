// Portato da src/SoundEngine.m (monitor iPad)
// SimulaFranz_Monitor — sintetizzatore audio in tempo reale (iPad: AudioQueue; Android: AudioTrack in streaming).
// Tutti i suoni sono generati dal codice (nessun file audio, nessun copyright):
//  - bip QRS/polso con tono legato alla SpO2 (acuto = alta, grave = bassa)
//  - allarmi a priorità alta / media / bassa con cadenze distinte
//  - NIBP: motore della pompa durante il gonfiaggio, "tss" della valvola a ogni
//    gradino di sgonfiaggio, sfiato finale.
package it.simulafranz.monitor

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Process
import it.simulafranz.core.SFLog
import it.simulafranz.core.SFPrefs
import java.util.Locale
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import it.simulafranz.uk.Uk

typealias SFAlarmSound = Int
const val SFAlarmSoundNone = 0
const val SFAlarmSoundLow = 1
const val SFAlarmSoundMedium = 2
const val SFAlarmSoundHigh = 3

// Volumi per canale (0..1, relativi al volume generale), salvati e ripristinati alla riapertura dell'app
typealias SFVolChannel = Int
const val SFVolBeepECG = 0
const val SFVolBeepSpO2 = 1
const val SFVolAlarm = 2
const val SFVolMonitor = 3
const val SFVolVoice = 4
const val SFVolCount = 5

// La sintesi resta a 22050 Hz, campione per campione come sull'iPad (i filtri a un polo e gli inviluppi
// hanno coefficienti "per campione": cambiando frequenza cambierebbe il timbro). L'uscita verso AudioTrack
// è a 44100 Hz: ogni campione viene raddoppiato con interpolazione lineare.
private const val SR = 22050.0
private const val OUT_SR = 44100
private const val FRAMES = 384          // ~17 ms per buffer: bip del battito in sincrono con il QRS
private const val MAXVOICE = 12

private const val VTone = 1
private const val VNoise = 2

private class Voice {
    var kind = 0
    var freq = 0.0; var phase = 0.0; var amp = 0.0
    var pos = 0L; var len = 0L          // campioni
    var attack = 0.0; var release = 0.0 // campioni
    var decayTau = 0.0                  // per rumore: costante di decadimento (campioni)
    var harm3 = 0.0                     // ampiezza armonica (timbro allarme)
    var lp = 0.0                        // stato filtro
    var click = false
    fun set(o: Voice) {
        kind = o.kind; freq = o.freq; phase = o.phase; amp = o.amp; pos = o.pos; len = o.len
        attack = o.attack; release = o.release; decayTau = o.decayTau; harm3 = o.harm3; lp = o.lp; click = o.click
    }
}

// Cadenze allarme: elenco di (inizio s, durata s, freq Hz); periodo totale
private val kHigh = doubleArrayOf(0.0, .12, 960.0, .20, .12, 960.0, .40, .12, 960.0, .85, .12, 960.0, 1.05, .12, 960.0)   // 3+2 (alta priorità)
private val kMed = doubleArrayOf(0.0, .20, 660.0, .32, .20, 660.0, .64, .20, 660.0)                                       // 3 impulsi (media)
private val kLow = doubleArrayOf(0.0, .22, 520.0, .40, .22, 520.0)                                                        // 2 impulsi (bassa)

private fun alarmSample(a: Int, pos: Long): Double {
    val p: DoubleArray; val n: Int; val period: Double
    when (a) {
        SFAlarmSoundHigh -> { p = kHigh; n = 5; period = 6.0 }
        SFAlarmSoundMedium -> { p = kMed; n = 3; period = 8.0 }
        SFAlarmSoundLow -> { p = kLow; n = 2; period = 16.0 }
        else -> return 0.0
    }
    val t = (pos / SR) % period
    for (i in 0 until n) {
        val pt = p[i * 3]; val pd = p[i * 3 + 1]; val pf = p[i * 3 + 2]
        val x = t - pt
        if (x >= 0 && x < pd) {
            val env = min(1.0, min(x, pd - x) / 0.012)
            val w = 2 * PI * pf * x
            return env * 0.42 * (sin(w) + 0.35 * sin(3 * w) + 0.15 * sin(5 * w)) / 1.5
        }
    }
    return 0.0
}

class SoundEngine(private val prefs: SFPrefs) {
    private var track: AudioTrack? = null
    private var thread: Thread? = null
    private val lock = Any()
    private val voices = Array(MAXVOICE) { Voice() }
    // allarme (sequenziato nel thread audio, preciso al campione)
    private var alarm = SFAlarmSoundNone
    private var alarmPos = 0L
    // pompa
    private var pump = false
    private var pumpPressure = 0.0; private var pumpPhase = 0.0; private var pumpEnv = 0.0; private var pumpLP = 0.0
    private var rng = 22222
    @Volatile private var master = 0.7
    // defibrillatore
    private var dTone = 0; private var dProg = 0.0; private var dPhase = 0.0; private var dEnv = 0.0; private var dPos = 0L
    private val gain = DoubleArray(SFVolCount)          // volumi per canale

    @Volatile var running: Boolean = false; private set
    @Volatile var lastError: String? = null; private set   // diagnostica

    // MARK: - Avvio

    init {
        for (c in 0 until SFVolCount) {
            val key = String.format(Locale.US, "sf_vol_%d", c)
            gain[c] = if (prefs.has(key)) max(0.0, min(1.0, prefs.double(key, 1.0))) else 1.0
        }
    }

    /** configura AudioTrack e avvia il thread di sintesi */
    // ANDROID (audit 26/11, lint): Range su setSessionId(AUDIO_SESSION_ID_GENERATE) è un falso positivo dell'annotazione di AudioTrack.Builder
    @android.annotation.SuppressLint("Range")
    fun start(): Boolean {
        if (running) return true
        val t: AudioTrack
        try {
            val minBuf = AudioTrack.getMinBufferSize(OUT_SR, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
            if (minBuf <= 0) { lastError = String.format(Locale.US, "AudioTrack getMinBufferSize %d", minBuf); SFLog.log("audio: $lastError"); return false }
            val bufBytes = max(minBuf, FRAMES * 2 * 2 * 2)      // almeno due buffer di uscita (16 bit mono)
            // USAGE_MEDIA: suona anche con il telefono in "silenzioso" (come un monitor reale)
            t = AudioTrack.Builder()
                .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
                .setAudioFormat(AudioFormat.Builder().setSampleRate(OUT_SR).setEncoding(AudioFormat.ENCODING_PCM_16BIT).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                .setBufferSizeInBytes(bufBytes)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
                .setSessionId(AudioManager.AUDIO_SESSION_ID_GENERATE)   // (lint Range: falso positivo, 0 = GENERATE è documentato)
                .build()
            if (t.state != AudioTrack.STATE_INITIALIZED) { t.release(); lastError = "AudioTrack non inizializzato"; SFLog.log("audio: $lastError"); return false }
            t.setVolume(1.0f)
            t.play()
            SFLog.log(String.format(Locale.US, "audio: buffer %d byte (minimo %d)", bufBytes, minBuf))
        } catch (e: Throwable) {
            lastError = "AudioTrack $e"; SFLog.log("audio: $lastError"); return false
        }
        track = t
        running = true; lastError = null
        thread = Thread({ audioLoop(t) }, "SoundEngine").apply { isDaemon = true; priority = Thread.MAX_PRIORITY; start() }
        SFLog.log(String.format(Locale.US, "audio: AudioTrack avviata (%d Hz, sintesi %.0f Hz)", OUT_SR, SR))
        return true
    }

    fun stop() {
        if (!running) return
        running = false
        val th = thread; thread = null
        try { track?.pause() } catch (e: Throwable) {}     // sblocca una write in corso
        try { th?.join(300) } catch (e: InterruptedException) {}
        try { track?.flush(); track?.release() } catch (e: Throwable) {}
        track = null
    }

    var volume: Double                  // 0..1 (master)
        get() = master
        set(v) { master = max(0.0, min(1.0, v)) }

    // MARK: - Comandi (main thread)

    private fun addVoice(v: Voice) {
        synchronized(lock) {
            for (i in 0 until MAXVOICE) if (voices[i].kind == 0) { voices[i].set(v); break }
        }
    }

    fun gainFor(c: Int): Double = if (c >= 0 && c < SFVolCount) gain[c] else 1.0
    fun setGain(g: Double, forChannel: Int) {
        val c = forChannel
        if (c < 0 || c >= SFVolCount) return
        synchronized(lock) { gain[c] = max(0.0, min(1.0, g)) }
        prefs.setDouble(String.format(Locale.US, "sf_vol_%d", c), gain[c])
    }

    // Bip del battito: frequenza in Hz (fromECG: sorgente QRS -> volume "Bip ECG", altrimenti "Bip SpO2")
    fun beepWithFrequency(hz: Double) { beepWithFrequency(hz, true) }
    fun beepWithFrequency(hz: Double, fromECG: Boolean) {
        val v = Voice()
        v.kind = VTone; v.freq = hz; v.amp = 0.45 * gain[if (fromECG) SFVolBeepECG else SFVolBeepSpO2]
        v.len = (0.075 * SR).toLong(); v.attack = 0.004 * SR; v.release = 0.025 * SR
        addVoice(v)
    }

    fun setAlarm(alarm: Int) {
        synchronized(lock) {
            if (alarm != this.alarm) { this.alarm = alarm; alarmPos = 0 }
        }
    }

    // Pompa NIBP: in funzione mentre il bracciale si gonfia; pressione per il timbro
    fun setPumpRunning(running: Boolean, pressure: Double) {
        synchronized(lock) {
            pump = running; pumpPressure = pressure
        }
    }

    /** breve sfiato a ogni gradino */
    fun valveStep() {
        val v = Voice()
        v.kind = VNoise; v.amp = 0.30 * gain[SFVolMonitor]; v.len = (0.20 * SR).toLong()
        v.attack = 0.003 * SR; v.release = 0.04 * SR; v.decayTau = 0.07 * SR; v.click = true
        addVoice(v)
    }

    /** sfiato finale lungo */
    fun valveDump() {
        val v = Voice()
        v.kind = VNoise; v.amp = 0.40 * gain[SFVolMonitor]; v.len = (1.5 * SR).toLong()
        v.attack = 0.01 * SR; v.release = 0.2 * SR; v.decayTau = 0.55 * SR; v.click = true
        addVoice(v)
    }

    // Defibrillatore: 0 = silenzio, 1 = carica (tono che sale con progress 0..1), 2 = carico/pronto (bip continui)
    fun setDefibTone(mode: Int, progress: Double) {
        synchronized(lock) {
            if (mode != dTone) dPos = 0
            dTone = mode; dProg = max(0.0, min(1.0, progress))
        }
    }

    /** scarica: «cia-clack» dei relè (uno scatto secco e chiaro, subito dopo quello più forte e pieno) con il tonfo dell'erogazione */
    fun shockSound() {
        fun scatto(amp: Double, sec: Double, tau: Double) {
            val v = Voice()
            v.kind = VNoise; v.amp = amp * gain[SFVolMonitor]; v.len = (sec * SR).toLong()
            v.attack = 0.0005 * SR; v.release = 0.02 * SR; v.decayTau = tau * SR; v.click = true
            addVoice(v)
        }
        fun colpo(hz: Double, amp: Double, sec: Double) {
            val t = Voice()
            t.kind = VTone; t.freq = hz; t.amp = amp * gain[SFVolMonitor]; t.len = (sec * SR).toLong(); t.attack = 0.001 * SR; t.release = sec * 0.7 * SR
            addVoice(t)
        }
        scatto(0.55, 0.07, 0.012); colpo(2100.0, 0.16, 0.03)                                       // «cia»
        Uk.after(0.085) { scatto(0.95, 0.30, 0.045); colpo(180.0, 0.45, 0.09); colpo(70.0, 0.6, 0.26) }   // «clack» + tonfo
    }

    /** metronomo RCP del DAE */
    fun metronomeTick() {
        val v = Voice()
        v.kind = VTone; v.freq = 1250.0; v.amp = 0.38 * gain[SFVolMonitor]; v.len = (0.06 * SR).toLong(); v.attack = 0.001 * SR; v.release = 0.025 * SR   // "tic" ben udibile sopra la voce
        addVoice(v)
    }

    /** ANDROID (guasti 22/11): «cip» breve e acuto del dispositivo SPENTO con un malfunzionamento (come i chirp dei DAE
     *  reali, diverso dal bip del battito e dal metronomo). Volume generale × «Suoni del monitor». */
    fun faultChirp() {
        val v = Voice()
        v.kind = VTone; v.freq = 2900.0; v.amp = 0.30 * gain[SFVolMonitor]; v.len = (0.07 * SR).toLong(); v.attack = 0.002 * SR; v.release = 0.03 * SR
        addVoice(v)
    }

    // ANDROID (suoni 25/11): trasmissione dell'ECG, risposta della centrale, errore. Volume generale × «Suoni del monitor».
    private fun blip(hz: Double, sec: Double, amp: Double) {
        val v = Voice()
        v.kind = VTone; v.freq = hz; v.amp = amp * gain[SFVolMonitor]; v.len = (sec * SR).toLong(); v.attack = 0.003 * SR; v.release = 0.03 * SR
        addVoice(v)
    }
    /** trasmissione in corso: due note brevi «da modem», una volta al secondo */
    fun txProgress() { blip(1400.0, 0.07, 0.22); Uk.after(0.11) { blip(1900.0, 0.07, 0.22) } }
    /** trasmissione completata: tre note che salgono */
    fun txDone() { blip(880.0, 0.12, 0.34); Uk.after(0.14) { blip(1175.0, 0.12, 0.34) }; Uk.after(0.28) { blip(1568.0, 0.22, 0.36) } }
    /** risposta della centrale: doppio «din-don», ben udibile */
    fun rxChime() { for (k in 0 until 2) { Uk.after(0.75 * k) { blip(1320.0, 0.22, 0.5) }; Uk.after(0.75 * k + 0.26) { blip(990.0, 0.34, 0.5) } } }
    /** errore (es. CARICA senza piastre collegate): due note basse e ruvide */
    fun errorBuzz() { for (k in 0 until 2) Uk.after(0.22 * k) { blip(196.0, 0.16, 0.5); blip(294.0, 0.16, 0.3) } }

    // MARK: - Sintesi (thread audio)

    private fun noise(): Double {
        rng = rng * 1664525 + 1013904223
        return ((rng ushr 9) / (1 shl 23).toDouble()) * 2.0 - 1.0
    }

    private fun render(out: ShortArray, n: Int) {
        synchronized(lock) {
            val master = master
            for (i in 0 until n) {
                var s = 0.0
                // voci (bip e valvola)
                for (k in 0 until MAXVOICE) {
                    val v = voices[k]
                    if (v.kind == 0) continue
                    var env = 1.0
                    if (v.pos < v.attack) env = v.pos / v.attack
                    val left = v.len - v.pos
                    if (left < v.release) env *= max(0.0, left / v.release)
                    if (v.kind == VTone) {
                        s += v.amp * env * sin(v.phase)
                        v.phase += 2 * PI * v.freq / SR
                    } else {
                        // sibilo dell'aria: rumore filtrato passa-alto (lp sottratto) con decadimento
                        val w = noise()
                        v.lp += 0.25 * (w - v.lp)
                        var hiss = (w - v.lp) * exp(-v.pos / v.decayTau)
                        if (v.click && v.pos < 0.004 * SR) hiss += 0.8 * (1.0 - v.pos / (0.004 * SR)) * (if (v.pos % 2 != 0L) 1 else -1)
                        s += v.amp * env * hiss
                    }
                    if (++v.pos >= v.len) v.kind = 0
                }
                // pompa: ronzio del motorino (dente di sega ~100-130 Hz) + rumore meccanico
                val target = if (pump) 1.0 else 0.0
                pumpEnv += (target - pumpEnv) * 0.0015
                if (pumpEnv > 0.0005) {
                    val f0 = 95.0 + pumpPressure * 0.18            // il timbro sale con la pressione
                    pumpPhase += f0 / SR; if (pumpPhase >= 1) pumpPhase -= 1
                    val saw = 2 * pumpPhase - 1
                    val rattle = noise() * (0.5 + 0.5 * sin(2 * PI * pumpPhase))
                    pumpLP += 0.18 * ((0.55 * saw + 0.45 * rattle) - pumpLP)
                    s += 0.33 * gain[SFVolMonitor] * pumpEnv * pumpLP * (0.9 + 0.1 * sin(2 * PI * 7 * i / SR))
                }
                // defibrillatore: carica = sibilo che sale; pronto = bip ripetuti a 1 kHz
                val dTarget = if (dTone != 0) 1.0 else 0.0
                dEnv += (dTarget - dEnv) * 0.004
                if (dEnv > 0.0005) {
                    val f = if (dTone == 1) 420.0 + 900.0 * dProg else 1000.0
                    dPhase += 2 * PI * f / SR; if (dPhase > 2 * PI) dPhase -= 2 * PI
                    var gate = 1.0
                    if (dTone == 2) { val ph = (dPos / SR) % 0.5; gate = if (ph < 0.25) min(1.0, min(ph, 0.25 - ph) / 0.01) else 0.0 }
                    s += 0.20 * gain[SFVolMonitor] * dEnv * gate * (sin(dPhase) + 0.2 * sin(2 * dPhase))
                    dPos++
                }
                // allarme
                if (alarm != SFAlarmSoundNone) { s += gain[SFVolAlarm] * alarmSample(alarm, alarmPos); alarmPos++ }

                s *= master
                if (s > 1) s = 1.0; if (s < -1) s = -1.0
                out[i] = (s * 30000).toInt().toShort()
            }
        }
    }

    // Al posto della callback di AudioQueue: un thread ad alta priorità che sintetizza un buffer alla volta
    // (~17 ms) e lo scrive nell'AudioTrack (la write blocca finché c'è posto: è lei a dare il ritmo).
    private fun audioLoop(t: AudioTrack) {
        try { Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO) } catch (e: Throwable) {}
        val buf = ShortArray(FRAMES)
        val out = ShortArray(FRAMES * 2)
        var prev = 0
        while (running) {
            render(buf, FRAMES)
            for (i in 0 until FRAMES) {
                val cur = buf[i].toInt()
                out[2 * i] = ((prev + cur) / 2).toShort()
                out[2 * i + 1] = cur.toShort()
                prev = cur
            }
            val w = try { t.write(out, 0, out.size) } catch (e: Throwable) { -1 }
            if (w < 0) {
                if (running) { lastError = String.format(Locale.US, "AudioTrack write %d", w); SFLog.log("audio: $lastError") }
                break
            }
            if (w == 0 && running) try { Thread.sleep(5) } catch (e: InterruptedException) {}
        }
    }

    companion object {
        // Frequenza del bip in funzione della SpO2 (stile monitor reali)
        fun beepFrequencyForSpO2(spo2: Int): Double {
            // ~1/3 di semitono per punto di SpO2: 100% = 880 Hz, 90% ≈ 698 Hz, 80% ≈ 554 Hz, 70% ≈ 440 Hz
            val s = max(50, min(100, spo2)).toDouble()
            return 880.0 * 2.0.pow((s - 100.0) / 30.0)
        }

        fun nameForChannel(c: Int): String =
            listOf("Bip ECG (QRS)", "Bip SpO2 (polso)", "Allarmi", "Suoni del monitor (NIBP, carica, scarica)", "Istruzioni vocali")[c]
    }
}
