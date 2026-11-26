// ANDROID (ora 22/11): nessun file corrispondente sull'iPad. ORA DI SISTEMA sincronizzata con il controller.
// Il tablet monitor spesso non ha rete Internet (Wi-Fi Direct, hotspot senza dati) e la sua ora può essere sbagliata:
// il controller manda {"command":"set_wall_clock","value":<epoch ms>} a ogni collegamento e ogni 10 minuti. Il cuore
// (SimulationState) memorizza lo SCARTO rispetto all'orologio del tablet e lo usa per gli orari del registro, degli
// eventi del defibrillatore, della misura NIBP e di «Fine scenario · ore …». Questo oggetto è lo specchio comune per
// l'interfaccia: tutti gli orologi a schermo dei layout leggono SFWallClock.now() al posto di Date().
// Senza set_wall_clock lo scarto è 0: l'ora è quella del tablet, come prima.
package it.simulafranz.core

import java.util.Date

object SFWallClock {
    /** orologio del tablet (iniettabile nei test) */
    @Volatile var system: () -> Long = { System.currentTimeMillis() }
    /** scarto in ms: ora del controller − ora del tablet (0 = non sincronizzato) */
    @Volatile var offsetMs: Long = 0L; private set
    @Volatile var synced: Boolean = false; private set

    /** ora sincronizzata in ms epoch (quella del tablet se il controller non l'ha mai mandata) */
    fun nowMs(): Long = system() + offsetMs
    fun now(): Date = Date(nowMs())

    /** lo imposta SimulationState (set_wall_clock e ripristino dopo un crash) */
    fun apply(offset: Long, isSynced: Boolean) { offsetMs = if (isSynced) offset else 0L; synced = isSynced }
}
