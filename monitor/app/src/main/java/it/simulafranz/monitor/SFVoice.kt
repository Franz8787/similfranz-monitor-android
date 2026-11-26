// Portato da src/DefibDrawerView.m e src/MonitorViewController.m (monitor iPad): al posto di AVSpeechSynthesizer.
// Sull'iPad: voce "it-IT", velocità predefinita, ogni nuova frase interrompe subito quella in corso
// (stopSpeakingAtBoundary:AVSpeechBoundaryImmediate), volume = max(0.3, volume generale) × volume "Istruzioni vocali".
package it.simulafranz.monitor

import android.content.Context
import android.os.Bundle
import android.speech.tts.TextToSpeech
import it.simulafranz.core.SFLog
import java.util.Locale

object SFVoice {
    private var tts: TextToSpeech? = null
    private var ready = false
    private var pending: String? = null      // frase chiesta prima che il motore vocale fosse pronto

    /** true se la sintesi vocale italiana è pronta all'uso */
    @Volatile var available: Boolean = false; private set

    /** volume della voce 0..1 (u.volume dell'iPad) */
    @Volatile var volume: Double = 1.0
        set(v) { field = v.coerceIn(0.0, 1.0) }

    /** true mentre sta parlando (_speech.speaking) */
    // ANDROID (audit 26/11, PERF-F8): valore tenuto dagli avvisi del motore vocale (inizio/fine frase): tts.isSpeaking è una
    // chiamata al servizio di sintesi (binder) e il cassetto DEFIB la chiedeva a ogni frame
    @Volatile private var talking = false
    @Volatile private var talkId = ""            // frase in corso: gli avvisi delle frasi interrotte (QUEUE_FLUSH) non la spengono
    private var talkN = 0
    val speaking: Boolean get() = available && talking

    /** Prepara il motore vocale (una volta sola). Senza voce italiana installata: available resta false, nessun errore. */
    fun start(ctx: Context) {
        if (tts != null) return
        try {
            tts = TextToSpeech(ctx.applicationContext) { status ->
                val t = tts
                try {
                    if (status != TextToSpeech.SUCCESS || t == null) {
                        SFLog.log("voce: sintesi vocale non disponibile (stato $status)")
                    } else {
                        val r = t.setLanguage(Locale.ITALIAN)
                        if (r == TextToSpeech.LANG_MISSING_DATA || r == TextToSpeech.LANG_NOT_SUPPORTED) {
                            SFLog.log("voce: voce italiana non installata ($r)")
                        } else {
                            t.setOnUtteranceProgressListener(object : android.speech.tts.UtteranceProgressListener() {
                                override fun onStart(utteranceId: String?) { if (utteranceId == talkId) talking = true }
                                override fun onDone(utteranceId: String?) { if (utteranceId == talkId) talking = false }
                                @Deprecated("API 21") override fun onError(utteranceId: String?) { if (utteranceId == talkId) talking = false }
                                override fun onError(utteranceId: String?, errorCode: Int) { if (utteranceId == talkId) talking = false }
                                override fun onStop(utteranceId: String?, interrupted: Boolean) { if (utteranceId == talkId) talking = false }
                            })
                            t.setSpeechRate(1.0f)        // AVSpeechUtteranceDefaultSpeechRate * 1.0
                            t.setPitch(1.0f)
                            ready = true; available = true
                            SFLog.log("voce: sintesi vocale italiana pronta")
                            pending?.let { p -> pending = null; speak(p) }
                        }
                    }
                } catch (e: Throwable) {
                    SFLog.log("voce: errore di avvio $e")
                }
                pending = null
            }
        } catch (e: Throwable) {
            tts = null
            SFLog.log("voce: errore di avvio $e")
        }
    }

    /** Pronuncia il testo interrompendo subito la frase in corso (come sull'iPad). */
    fun speak(text: String) {
        if (text.isEmpty()) return
        val t = tts ?: return
        if (!ready) { pending = text; return }
        if (!available) return
        try {
            val params = Bundle()
            params.putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, volume.toFloat())
            val id = "sf_voice_" + (++talkN); talkId = id
            if (t.speak(text, TextToSpeech.QUEUE_FLUSH, params, id) == TextToSpeech.SUCCESS) talking = true
        } catch (e: Throwable) {
            SFLog.log("voce: errore $e")
        }
    }

    /** Interrompe subito la voce (stopSpeakingAtBoundary:AVSpeechBoundaryImmediate). */
    fun stop() {
        pending = null
        try { tts?.stop() } catch (e: Throwable) {}
        talking = false
    }
}
