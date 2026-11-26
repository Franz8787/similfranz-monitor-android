package it.simulafranz.monitor

import android.content.Context
import android.media.AudioManager
import it.simulafranz.uk.Uk

// ANDROID (guscio 25/11): volume di SISTEMA del tablet (flusso multimediale), dal menu ☰ e dal controller:
// con il tablet nel guscio i tasti fisici del volume restano dietro lo sportellino.
// ANDROID (guscio 25/11): vibrazione del tablet a ogni scarica erogata (se il tablet ha il motore)
object SFVibra {
    private val vib: android.os.Vibrator? by lazy { try { Uk.ctx.getSystemService(Context.VIBRATOR_SERVICE) as? android.os.Vibrator } catch (e: Exception) { null } }
    val available: Boolean get() = try { vib?.hasVibrator() == true } catch (e: Exception) { false }
    const val PACE_MS = 45L      // sotto i 30-40 ms il motore del Tab Active2 non fa in tempo ad avviarsi e non si sente nulla
    /** impulso di pacing: lieve e breve */
    fun pace() { try { val v = vib ?: return; if (v.hasVibrator()) v.vibrate(android.os.VibrationEffect.createOneShot(SFVibra.PACE_MS, android.os.VibrationEffect.DEFAULT_AMPLITUDE)) } catch (e: Exception) {} }
    fun shock() {
        try {
            val v = vib ?: return; if (!v.hasVibrator()) return
            v.vibrate(android.os.VibrationEffect.createWaveform(longArrayOf(0, 70, 60, 180), intArrayOf(0, 255, 0, 255), -1))
        } catch (e: Exception) {}
    }
}

object SFTabletVolume {
    private const val ST = AudioManager.STREAM_MUSIC
    private fun am(): AudioManager? = try { Uk.ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager } catch (e: Exception) { null }
    fun level(): Int = try { am()?.getStreamVolume(ST) ?: -1 } catch (e: Exception) { -1 }
    fun max(): Int = try { (am()?.getStreamMaxVolume(ST) ?: 15).coerceAtLeast(1) } catch (e: Exception) { 15 }
    fun setLevel(v: Int) { try { am()?.setStreamVolume(ST, v.coerceIn(0, max()), 0) } catch (e: Exception) {} }
    fun percent(): Int { val l = level(); return if (l < 0) -1 else Math.round(l * 100.0 / max()).toInt() }
    fun setPercent(p: Int) { setLevel(Math.round(p.coerceIn(0, 100) * max() / 100.0).toInt()) }
}
