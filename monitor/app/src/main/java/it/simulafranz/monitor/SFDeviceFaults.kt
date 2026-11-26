// ANDROID (guasti 22/11): nessun file corrispondente sull'iPad. Aiuti comuni ai layout per i malfunzionamenti simulati
// del dispositivo (RICERCA_MALFUNZIONAMENTI_DAE.md, cap. 4): il cuore dice nello snapshot QUANDO suonare
// (faultBeepIntervalS, faultBeepDouble: 0 = nessun bip, a dispositivo acceso, nel layout senza bip...), il layout suona.
package it.simulafranz.monitor

import it.simulafranz.core.*
import it.simulafranz.uk.*

/** Bip da spento: un «cip» ogni N s (doppio con il guasto grave). In pausa e a scenario terminato tace. */
class SFFaultBeeper {
    private var nextAt = 0.0
    fun tick(now: Double, s: SLSnapshot, sound: SoundEngine) {
        val iv = s.faultBeepIntervalS
        if (iv <= 0 || !s.simulationRunning || s.scenarioEnded) { nextAt = 0.0; return }
        if (nextAt == 0.0) { nextAt = now + 1.5; return }        // il primo poco dopo lo spegnimento / l'attivazione
        if (now >= nextAt) {
            sound.faultChirp()
            SFLog.log("bip da spento (malfunzionamento simulato)${if (s.faultBeepDouble) ", doppio" else ""}, ogni $iv s")
            if (s.faultBeepDouble) Uk.after(0.22) { sound.faultChirp() }
            nextAt = now + iv
        }
    }
}

/** Scritte dei malfunzionamenti del layout (per non coprirle con i messaggi propri del layout, es. «Collegare il connettore»). */
fun SFFaultScreenTexts(layout: String): Set<String> =
    listOf("svc", "battery_low", "battery_replace", "battery_replaced", "no_shock_battery", "no_shock_internal", "pads_expired",
           "pads_damaged", "maintenance", "shock_stuck", "memory").map { SLDeviceFaultScreen(layout, it) }.toSet() +
    setOf("PIASTRE DANNEGGIATE — SHOCK NON EROGATO")
