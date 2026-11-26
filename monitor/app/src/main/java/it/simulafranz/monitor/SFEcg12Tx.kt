// ANDROID (trasmissione 12D 22/11) — file nuovo, solo Android (sull'iPad non c'è).
// verso nessuno. Il dispositivo fa vedere il flusso vero (scelta del destinatario e del report, messaggi di stato con tempi
// realistici, esito) e alla fine scrive nel registro della sessione la voce «12 derivazioni trasmesso a …» con il tag
// "ecg12_tx": controller e web regia la leggono (avviso «ECG 12D trasmesso alla Centrale Operativa per valutazione» e risposta
// dell'istruttore che fa da centrale, voce "ecg12_rx"). Il cuore non cambia: si usa il registro che c'è già (SFSessionLog.add).
// Flussi reali e fonti: ECG_12D.md, sezione «Trasmissione».
package it.simulafranz.monitor

import it.simulafranz.core.*
import it.simulafranz.uk.*
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object SFEcg12Tx {
    /** Scrive una voce nel registro della sessione (kind, testo, tag). La collega MainActivity; false = non scritta (PRONTO). */
    var log: ((String, String, String?) -> Boolean)? = null

    /** Un 12 derivazioni acquisito (in memoria come sul dispositivo: «ECG 1» = l'ultimo). */
    class Record(val wallMs: Long, val hr: Int, val interp: String, val morph: String, val electrodes: Boolean) {
        fun time(): String = SimpleDateFormat("HH:mm", Locale.ITALY).format(Date(wallMs))
        val sent = ArrayList<String>()                 // destinatari a cui è già stato trasmesso (icona di esito nell'elenco)
    }
    private val records = ArrayList<Record>()          // il più recente in fondo

    // ANDROID (centrale 25/11): un solo destinatario, da tutti i layout: la Centrale Operativa. Risponde l'istruttore dalla regia
    // («Centralizzare il paziente» / «Non centralizzare il paziente»): la risposta compare sul monitor, sopra qualsiasi layout.
    val sites = listOf("Centrale Operativa")

    /** Voci del registro con indice >= i (la collega MainActivity) e indice della prossima voce. */
    var entriesSince: ((Int) -> List<Map<String, Any?>>)? = null
    var nextIndex: (() -> Int)? = null
    private var rxSeen = -1
    /** Suoni della trasmissione (li collega MainActivity): fase -> suono. */
    var sound: ((String) -> Unit)? = null
    /** Layout in uso (lo collega MainActivity) e layout da cui è partita l'ultima trasmissione. */
    var currentLayout: (() -> String?)? = null
    private var lastTxLayout: String? = null
    /** Risposte della centrale (voci con tag "ecg12_rx") arrivate dall'ultima chiamata: solo il testo della risposta.
     *  Solo se sul monitor c'è ancora il layout che ha trasmesso: i layout senza 12 derivazioni e trasmissione non mostrano nulla. */
    fun newReplies(): List<String> {
        val nx = nextIndex?.invoke() ?: return emptyList()
        if (rxSeen < 0 || nx < rxSeen) { rxSeen = nx; return emptyList() }       // primo giro o registro svuotato: si parte da adesso
        if (nx == rxSeen) return emptyList()
        val out = (entriesSince?.invoke(rxSeen) ?: emptyList()).filter { it["tag"] == "ecg12_rx" }
            .map { ((it["text"] as? String) ?: "").substringAfter("): ", (it["text"] as? String) ?: "").trim() }.filter { it.isNotEmpty() }
        rxSeen = nx
        if (lastTxLayout == null || currentLayout?.invoke() != lastTxLayout) return emptyList()
        return out
    }

    fun add(r: Record) { synchronized(records) { records.add(r); while (records.size > 8) records.removeAt(0) } }

    /** I 12 derivazioni in memoria, dal più recente. Dopo un azzeramento dello scenario (ecg12_last tolto dal cuore) la memoria si vuota. */
    fun list(state: SimulationState): List<Record> {
        if (state.stateDictionary()["ecg12_last"] == null) synchronized(records) { records.clear() }
        return synchronized(records) { records.reversed() }
    }

    /** Durate realistiche (s) delle fasi: connessione e invio. I sistemi di trasmissione dichiarano tempi dell'ordine di 30 secondi per l'invio di un 12D. */
    fun connectSeconds(): Double = 2.5 + Math.random() * 2.5
    fun sendSeconds(): Double = 7.0 + Math.random() * 6.0

    /** Voce del registro a trasmissione completata. Ritorna il testo (anche se il registro non scrive: scenario in PRONTO). */
    fun logSent(site: String, r: Record, layout: String): String {
        val fc = if (!r.electrodes) "elettrodi non collegati" else if (r.hr < 0) "FC ---" else "FC ${r.hr}"
        val interp = if (r.interp.isEmpty()) "nessuna" else (if (r.interp.length > 110) r.interp.take(109) + "…" else r.interp)
        val t = "12 derivazioni trasmesso a $site ($fc, interpretazione: $interp; ECG: ${r.morph}; 12D delle ${r.time()})"
        val ok = log?.invoke("action", t, "ecg12_tx") ?: false
        lastTxLayout = currentLayout?.invoke() ?: layout
        SFLog.log("12D trasmesso ($layout) a $site: ${if (ok) "nel registro" else "registro non scritto (scenario non avviato)"}")
        return t
    }
}

/**
 * Una trasmissione in corso: chiama status(fase) per ogni passo e done(ok) alla fine. Fasi: "attempt", "connecting",
 * "connected", "sending", "done", "cancelled". Il testo lo sceglie il layout (stile del dispositivo).
 */
class SFEcg12TxJob(val site: String, val record: SFEcg12Tx.Record?, private val layout: String,
                   private val status: (String) -> Unit, private val done: (Boolean) -> Unit) {
    var phase = "attempt"; private set
    private var live = true
    private var timer: NSTimer? = null
    val active: Boolean get() = live

    fun start() {
        status(phase); SFEcg12Tx.sound?.invoke(phase)
        // un passo alla volta: il tempo di ogni fase è casuale entro valori realistici (vedi SFEcg12Tx)
        val steps = listOf("connecting" to 1.4, "connected" to SFEcg12Tx.connectSeconds(), "sending" to 1.2,
                           "done" to (if (record != null) SFEcg12Tx.sendSeconds() else 4.0 + Math.random() * 3))
        var at = 0.0
        for ((ph, d) in steps) {
            at += d
            Uk.after(at) {
                if (!live) return@after
                phase = ph; status(ph); if (ph == "done") SFEcg12Tx.sound?.invoke(ph)
                if (ph == "done") {
                    live = false; timer?.invalidate(); timer = null
                    if (record != null) { record.sent.add(site); SFEcg12Tx.logSent(site, record, layout) }
                    else SFEcg12Tx.log?.invoke("action", "Riepilogo degli eventi trasmesso a $site", null)
                    done(true)
                }
            }
        }
        // la riga dei messaggi si spegne dopo 3 s: la fase in corso si ripete ogni secondo finché la trasmissione è viva
        timer = NSTimer.scheduledTimerWithTimeInterval(1.0, true) { if (live) { status(phase); SFEcg12Tx.sound?.invoke(phase) } }
    }

    fun cancel() {
        if (!live) return
        live = false; timer?.invalidate(); timer = null; phase = "cancelled"; status(phase); done(false)
        SFLog.log("12D: trasmissione a $site annullata ($layout)")
    }
}
