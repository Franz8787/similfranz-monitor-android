// Portato da src/SFJitter.h (monitor iPad)
// SimulaFranz_Monitor — variabilità fisiologica dei numerici nei layout dei dispositivi (come GENERIC ICU):
// piccola deriva casuale limitata, aggiornata ogni ~2 s, uguale per tutti i riquadri che mostrano lo stesso parametro.
package it.simulafranz.core

import java.util.Random
import kotlin.math.max
import kotlin.math.min

private object SFJitterState {
    val st = HashMap<String, DoubleArray>()   // chiave -> [v, at]
    val rnd = Random()
    // lround del C: arrotonda al più vicino, i mezzi lontano dallo zero
    fun lround(x: Double): Int = (if (x < 0) -Math.floor(-x + 0.5) else Math.floor(x + 0.5)).toInt()
}

fun SFJitterOffset(key: String, lim: Double): Double = synchronized(SFJitterState) {
    val st = SFJitterState.st
    val now = System.nanoTime() / 1e9
    val e = st[key]
    var v = if (e != null) e[0] else 0.0; val at = if (e != null) e[1] else 0.0
    if (now - at >= 2.0) {
        v += (SFJitterState.rnd.nextInt(1001).toDouble() / 1000.0 - 0.5) * lim * 0.9
        v = max(-lim, min(lim, v * 0.85))
        st[key] = doubleArrayOf(v, now)
    }
    v
}

// valore mostrato = valore impostato + deriva (mai sotto 0; SpO2 mai sopra 100)
fun SFJ(key: String, value: Int, lim: Double): Int {
    if (value <= 0) return value
    val r = max(0, value + SFJitterState.lround(SFJitterOffset(key, lim)))
    return if (key == "spo2") min(100, r) else r
}
