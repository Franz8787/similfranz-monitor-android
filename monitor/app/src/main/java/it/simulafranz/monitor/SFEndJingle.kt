// ANDROID (storia sim 22/11): file nuovo, non c'è sull'iPad (vedi STORIA_SIM.md, punto 6).
// Jingle di fine scenario: quando arriva end_scenario (scenarioEnded passa da falso a vero) suona UNA volta
// assets/sounds/fine_scenario.wav (5,2 s, composto e sintetizzato da noi, CC0: scripts/audio/make_jingle.py).
//   - Volume = volume generale del monitor (lo stesso di SoundEngine): a volume 0 non suona.
//   - Il primo stato visto all'avvio fa solo da riferimento: dopo una ripresa (chiusura forzata dell'app) con lo
//     scenario già terminato il jingle NON si risuona.
//   - Scenario azzerato (scenarioEnded torna falso) mentre suona: si ferma subito.
package it.simulafranz.monitor

import android.media.AudioAttributes
import android.media.MediaPlayer
import it.simulafranz.core.SFLog
import it.simulafranz.uk.Uk

object SFEndJingle {
    private var lastEnded: Boolean? = null       // null = nessuno stato ancora visto (avvio dell'app)
    private var player: MediaPlayer? = null

    /** da chiamare a ogni aggiornamento dello stato (main thread); volume 0..1 = volume generale del monitor */
    fun update(ended: Boolean, volume: Double) {
        val prev = lastEnded
        lastEnded = ended
        if (prev == null) return                 // primo stato: solo riferimento (niente jingle alla ripresa)
        if (ended && !prev) play(volume)
        else if (!ended && prev) stop()          // scenario azzerato / ripreso: il jingle si ferma
        else if (player != null) setVolume(volume)
    }

    private fun setVolume(volume: Double) {
        val v = Math.max(0.0, Math.min(1.0, volume)).toFloat()
        try { player?.setVolume(v, v) } catch (e: Throwable) { }
    }

    private fun play(volume: Double) {
        stop()
        if (volume <= 0.0) return                // monitor a volume zero: niente jingle
        var mp: MediaPlayer? = null
        try {
            val m = MediaPlayer()
            mp = m
            // USAGE_MEDIA come SoundEngine: suona anche con il tablet in "silenzioso"
            m.setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
            Uk.ctx.assets.openFd("sounds/fine_scenario.wav").use { fd -> m.setDataSource(fd.fileDescriptor, fd.startOffset, fd.length) }
            m.setOnCompletionListener { p -> if (player === p) player = null; try { p.release() } catch (e: Throwable) { } }
            m.setOnErrorListener { p, _, _ -> if (player === p) player = null; try { p.release() } catch (e: Throwable) { }; true }
            m.prepare()
            player = m
            setVolume(volume)
            m.start()
        } catch (e: Throwable) {
            SFLog.log("jingle fine scenario: $e")
            player = null
            try { mp?.release() } catch (e2: Throwable) { }
        }
    }

    fun stop() {
        val p = player ?: return
        player = null
        try { p.stop() } catch (e: Throwable) { }
        try { p.release() } catch (e: Throwable) { }
    }
}
