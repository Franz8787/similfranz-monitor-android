// Portato da src/AltLayout.h (monitor iPad) — solo il protocollo; i testi dei dispositivi sono in AltLayout.kt
package it.simulafranz.monitor

import it.simulafranz.core.SLSnapshot

/** Interfaccia comune dei layout dei dispositivi (alternativi al layout ICU). Costruttore: (frame, state, engine, sound). */
interface SFAltLayout {
    var onLayoutMenu: (() -> Unit)?
    var onInfo: (() -> Unit)?
    var onVolumes: (() -> Unit)?      // menu › Volumi (pannello comune)
    fun updateWithSnapshot(s: SLSnapshot)
    fun tick(now: Double, snapshot: SLSnapshot)
    fun resetTracesAt(t: Double)
    fun setAlarmText(text: String?, level: Int, severity: Map<String, Int>?, muted: Boolean, remaining: Int)
    fun poweredOff(): Boolean          // dispositivo spento: niente allarmi, bip o spie
    fun setDevicePowered(on: Boolean)  // allinea il layout allo stato di accensione condiviso (idempotente)
    fun runSelfTestWithShot(shot: (String) -> Unit, done: (Int) -> Unit)
}
