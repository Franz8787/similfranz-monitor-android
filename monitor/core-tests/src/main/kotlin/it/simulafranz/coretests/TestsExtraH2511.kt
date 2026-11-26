// similFranz Monitor — test generici del layout extraH (25/11): pacing in demand senza elettrodi ECG e analisi del
// semiautomatico con il paziente in movimento. Valgono anche per l'edizione pubblica: nessun layout personalizzato.
package it.simulafranz.coretests

import it.simulafranz.core.*

fun testExtraH2511() {
    // ---- volume di sistema del tablet comandato dalla regia (25/11): ignoto finché il monitor non lo legge, poi comando e sequenza
    run {
        val s = NewState()
        CHECK(s.snapshot().tabletVolume == -1 && s.snapshot().tabletVolumeSeq == 0, "volume del tablet: ignoto all'avvio")
        s.reportTabletVolume(40)
        CHECK(s.snapshot().tabletVolume == 40 && s.snapshot().tabletVolumeSeq == 0, "volume del tablet: quello letto dal monitor, senza comando")
        Cmd(s, "set_tablet_volume", 73)
        CHECK(s.snapshot().tabletVolume == 73 && s.snapshot().tabletVolumeSeq == 1, "set_tablet_volume: valore e sequenza del comando")
    }
    // ---- pacing su extraH senza elettrodi ECG: il demand non può «sentire» il paziente, quindi stimola a frequenza
    //      fissa e lo dice; con gli elettrodi torna in demand e un ritmo proprio più veloce lo inibisce
    run {
        val s = NewState(); Cmd(s, "set_layout", "extrah"); RhythmNow(s, "sinus"); Cmd(s, "set_heart_rate", 90)
        Sensor(s, "ecg", true); Sensor(s, "pads", true); Cmd(s, "defib_mode", "pacer")
        Cmd(s, "defib_pacer", mapOf("rate" to 70, "output_ma" to 90, "demand" to true, "running" to true)); Tick(s, 2)
        CHECK(s.snapshot().defib.paceRunning && !s.snapshot().defib.paceActive && !s.snapshot().defib.paceLeadsOff,
              "extraH: demand con elettrodi, FC propria 90 > 70 ppm: pacing inibito")
        Sensor(s, "ecg", false); Tick(s, 2)
        var d = s.snapshot().defib
        CHECK(d.paceRunning && d.paceLeadsOff && d.paceActive && HASSTR(d.message, "NON-DEMAND"),
              { "extraH: demand senza elettrodi ECG: stimola a frequenza fissa e lo segnala (${d.message})" })
        Sensor(s, "ecg", true); Tick(s, 2)
        d = s.snapshot().defib
        CHECK(d.paceRunning && !d.paceLeadsOff && !d.paceActive, { "extraH: elettrodi ricollegati: di nuovo demand, inibito (${d.message})" })
    }
    // ---- semiautomatico su extraH con il paziente in movimento: l'analisi aspetta, non carica; a movimento finito riparte
    run {
        val s = NewState(); Cmd(s, "set_layout", "extrah"); RhythmNow(s, "vf"); Cmd(s, "defib_mode", "aed"); Sensor(s, "pads", true)
        Cmd(s, "vehicle_motion", true); Tick(s, 40)
        var d = s.snapshot().defib
        CHECK(d.aedPhase == SLAedAnalyzing && d.charge == SLChargeNone && HASSTR(d.message, "MOVIMENTO") && HASSTR(d.voicePrompt, "Fermare il movimento"),
              { "extraH: movimento durante l'analisi: analisi in attesa, nessuna carica (${d.message})" })
        Cmd(s, "vehicle_motion", false); Tick(s, 9)
        d = s.snapshot().defib
        CHECK(d.aedPhase == SLAedCharging || d.aedPhase == SLAedReady, { "extraH: movimento finito: l'analisi riparte e arriva alla carica (${d.message})" })
    }
}
