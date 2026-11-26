// ANDROID (sincronia polso 22/11) — file nuovo, solo Android.
// SimulaFranz_Monitor — orologio di un tracciato a «sweep»: ogni pixel (colonna) corrisponde a un istante ASSOLUTO
// (pixel p = intervallo [p/v, (p+1)/v) con v = pixel al secondo) e la colonna sullo schermo è p modulo la larghezza.
// Prima ogni tracciato contava i propri pixel da quando era stato azzerato: azzerare solo l'ECG (cambio di derivazione,
// piastre/elettrodi), una vista nascosta o ferma per più di 1 s, un tracciato creato dopo gli altri spostavano per sempre il
// suo cursore rispetto a quello della pleth/ART: il QRS e la sua onda di polso non erano più uno sopra l'altra. Con
// l'orologio assoluto le tracce con la stessa velocità e larghezza hanno il cursore sempre alla stessa colonna, e quelle a
// velocità diverse (es. 25 e 12,5 mm/s) disegnano comunque fino allo stesso istante.
// Kotlin puro (nessuna dipendenza Android): lo usa TraceView, i test lo controllano sul Mac.
package it.simulafranz.core

import kotlin.math.floor

class SFSweep(speed: Double) {
    /** pixel al secondo */
    var speed: Double = speed
        private set
    /** ultimo pixel disegnato (Long.MIN_VALUE = nessuno) */
    var lastPix: Long = Long.MIN_VALUE
        private set
    /** pixel saltati nell'ultimo advance (pausa lunga: si disegna solo l'ultimo secondo) */
    var skipped: Long = 0
        private set

    fun pixAt(t: Double): Long = floor((t - epoch) * speed).toLong()

    /** nuova velocità: l'ultimo istante disegnato resta lo stesso */
    fun setSpeed(v: Double) {
        if (v == speed || v <= 0) return
        if (lastPix != Long.MIN_VALUE) lastPix = floor((lastPix + 1) / speed * v).toLong() - 1
        speed = v
    }

    /** azzera all'istante t (il prossimo pixel è quello di t) */
    fun reset(t: Double) { lastPix = pixAt(t) - 1; skipped = 0 }

    /** avanza fino a t: numero di pixel nuovi da disegnare (gli ultimi, lastPix - n + 1 .. lastPix), al massimo ~1 s */
    fun advance(t: Double, maxSeconds: Double = 1.0): Int {
        skipped = 0
        if (lastPix == Long.MIN_VALUE) { reset(t); return 0 }
        val np = pixAt(t) - 1                    // pixel completi fino a t
        var n = np - lastPix
        if (n <= 0) return 0
        val mx = Math.max(1L, Math.ceil(speed * maxSeconds).toLong())
        if (n > mx) { skipped = n - mx; n = mx }
        lastPix = np
        return n.toInt()
    }

    /** istante d'inizio del pixel p */
    fun timeOf(p: Long): Double = p / speed + epoch

    /** colonna dell'ultimo pixel disegnato su un tracciato largo w */
    fun column(w: Int): Int = colOf(lastPix, w)

    companion object {
        /** ANDROID (onde 22/11): origine COMUNE dell'orologio dei tracciati. All'accensione del monitor (e al cambio di layout o
         *  alla ripresa della simulazione) chi comanda la mette all'istante attuale: tutte le tracce, di qualunque velocità e
         *  larghezza, ripartono insieme dal bordo sinistro come sui monitor veri, e restano allineate fra loro (stessa origine).
         *  Chi la cambia deve azzerare i tracciati (TraceView lo fa da sola quando vede l'origine cambiata). */
        @JvmStatic var epoch: Double = 0.0
        fun colOf(p: Long, w: Int): Int { val m = p % w; return (if (m < 0) m + w else m).toInt() }
    }
}
