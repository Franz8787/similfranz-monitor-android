// Portato da src/SFControl.m (monitor iPad)
// similFranz Monitor — un solo controller alla volta, con regole chiare e visibili a tutti.
// Controller = web app, app per iPad (Wi-Fi o diretto), app per iPod (diretto). I "seguaci" (saturimetro) non passano di qui.
//  1. LEGGERE non prende il comando: chi apre l'app osserva e basta.
//  2. Il primo COMANDO prende il comando, se nessuno comanda.
//  3. Chi comanda ATTIVAMENTE (un comando negli ultimi actionIdle secondi, 60) è protetto: i comandi degli altri
//     sono rifiutati (423) e chi li ha inviati vede chi comanda e il pulsante «Prendi il controllo».
//  4. Chi comanda ma è FERMO da più di actionIdle secondi non blocca nessuno: il primo comando di un altro passa
//     e il comando cambia di mano da solo (un iPad dimenticato acceso non tiene in ostaggio il monitor).
//  5. «Prendi il controllo» (take) funziona sempre. Chi perde il comando lo sa: infoFor gli dice da chi e quando.
//  6. Chi comanda e non si fa più sentire per idleTimeout secondi (10, app chiusa o fuori portata) libera il posto.
//  Ogni cambio di mano produce un testo (lastEvent) che il monitor scrive nel registro della sessione.
// Kotlin puro: testabile sul Mac.
package it.simulafranz.core

import java.util.Locale

class SFControl {
    var idleTimeout: Double = 10.0              // default 10 s: senza nessuna richiesta il posto si libera
    var actionIdle: Double = 60.0               // default 60 s: senza comandi chi comanda non è più protetto
    var clock: () -> Double = { System.nanoTime() / 1e9 }
    var onChange: (() -> Unit)? = null          // controller attivo cambiato (thread qualsiasi)
    @Volatile var lastEvent: String? = null     // es. "Comando a «similFranz Pocket 1» (prima: «Web · Mac», fermo da 2 min)"
        private set

    private var ownerId: String? = null; private var name: String = ""; private var kind: String = ""; private var transport: String = ""
    private var seenAt = 0.0; private var actedAt = 0.0; private var since = 0.0
    private var lostId: String? = null; private var lostTo: String? = null; private var lostAt = 0.0     // l'ultimo che ha perso il comando, a favore di chi, quando

    // --- da chiamare con synchronized già acquisito ---
    // il controller attivo non si fa sentire da troppo: posto libero. Ritorna true se è appena successo.
    private fun expire(): Boolean {
        if (ownerId != null && clock() - seenAt > idleTimeout) {
            lastEvent = String.format(Locale.US, "Controllo libero: «%s» non risponde più", name)
            ownerId = null; return true
        }
        return false
    }
    private fun setOwner(cid: String, name: String?, kind: String?, transport: String?) {
        val newName = if (!name.isNullOrEmpty()) name else "controller"
        if (ownerId != null && ownerId != cid) { lostId = ownerId; lostTo = newName; lostAt = clock() }
        if (lostId == cid) lostId = null          // chi riprende il comando non ha più nulla da sapere
        ownerId = cid; this.name = newName
        this.kind = if (!kind.isNullOrEmpty()) kind else "altro"
        this.transport = if (!transport.isNullOrEmpty()) transport else "wifi"
        val now = clock()
        seenAt = now; actedAt = now; since = now
    }
    private fun via(): String { return if (transport == "direct") "collegamento diretto" else "Wi-Fi" }

    private fun notifyChange() { val cb = onChange; if (cb != null) cb() }

    // Lettura: tiene vivo il controller attivo (se è lui), non occupa mai il posto.
    fun seen(clientId: String?) {
        val cid = clientId
        if (cid.isNullOrEmpty()) return
        var changed = false
        synchronized(this) {
            changed = expire()
            if (ownerId != null && ownerId == cid) seenAt = clock()
        }
        if (changed) notifyChange()
    }

    // Comando: ritorna true se il client può comandare (prendendo il posto se libero o se chi comandava è fermo).
    fun act(clientId: String?, name: String?, kind: String?, transport: String?): Boolean {
        val cid = clientId
        if (cid.isNullOrEmpty()) return false
        var changed = false; var mine = false
        synchronized(this) {
            changed = expire()
            val now = clock()
            if (ownerId == null) {
                setOwner(cid, name, kind, transport)
                lastEvent = String.format(Locale.US, "Comanda «%s» (%s)", this.name, via()); changed = true
            } else if (ownerId != cid && now - actedAt > actionIdle) {
                val prev = this.name; val idle = now - actedAt
                setOwner(cid, name, kind, transport)
                lastEvent = String.format(Locale.US, "Comando passato a «%s» (%s): «%s» era fermo da %s", this.name, via(), prev, Ago(idle))
                changed = true
            }
            mine = ownerId == cid
            if (mine) { seenAt = now; actedAt = now }
        }
        if (changed) notifyChange()
        return mine
    }

    // Presa di controllo esplicita: sempre accettata.
    fun take(clientId: String?, name: String?, kind: String?, transport: String?) {
        val cid = clientId
        if (cid.isNullOrEmpty()) return
        var changed = false
        synchronized(this) {
            expire()
            changed = ownerId != cid
            val prev = if (ownerId != null) this.name else null
            setOwner(cid, name, kind, transport)
            if (changed) lastEvent = if (prev != null) String.format(Locale.US, "«%s» ha preso il controllo (%s) da «%s»", this.name, via(), prev)
                                     else String.format(Locale.US, "Comanda «%s» (%s)", this.name, via())
        }
        if (changed) notifyChange()
    }

    // Rilascio (solo se clientId è il controller attivo). clientId null = rilascio forzato dal monitor.
    fun releaseClient(clientId: String?) {
        val cid = clientId
        var changed = false
        synchronized(this) {
            if (ownerId != null && (cid == null || ownerId == cid)) {
                lastEvent = String.format(Locale.US, "«%s» ha lasciato il controllo", name)
                ownerId = null; changed = true
            }
        }
        if (changed) notifyChange()
    }

    // Per le risposte dell'API. Con un controller attivo:
    //   {active, name, kind, transport, mine, idle_s, action_idle_s, since_s, protected}
    //   protected = false: chi comanda è fermo, il prossimo comando di un altro passa da solo.
    // Senza controller: {active:false, mine:false}. In più, a chi ha perso il comando negli ultimi 5 minuti:
    //   lost_to (nome di chi l'ha preso), lost_ago_s.
    fun infoFor(clientId: String?): Map<String, Any?> {
        val cid = clientId
        var expired = false; val d = LinkedHashMap<String, Any?>()
        synchronized(this) {
            expired = expire()
            val now = clock()
            if (ownerId == null) { d["active"] = false; d["mine"] = false }
            else {
                d["active"] = true; d["name"] = name; d["kind"] = kind; d["transport"] = transport
                d["mine"] = (cid != null && ownerId == cid)
                d["idle_s"] = (now - seenAt).toLong(); d["action_idle_s"] = (now - actedAt).toLong()
                d["since_s"] = (now - since).toLong(); d["protected"] = (now - actedAt <= actionIdle)
            }
            if (cid != null && lostId == cid && now - lostAt < 300) { d["lost_to"] = lostTo; d["lost_ago_s"] = (now - lostAt).toLong() }
        }
        if (expired) notifyChange()
        return d
    }

    // Per l'interfaccia del tablet: null se libero, altrimenti {name, kind, transport}
    fun owner(): Map<String, Any?>? {
        var expired = false; var d: Map<String, Any?>? = null
        synchronized(this) {
            expired = expire()
            if (ownerId != null) d = linkedMapOf<String, Any?>("name" to name, "kind" to kind, "transport" to transport)
        }
        if (expired) notifyChange()
        return d
    }

    private companion object {
        fun Ago(s: Double): String {
            return if (s < 90) String.format(Locale.US, "%d s", s.toLong()) else String.format(Locale.US, "%d min", Math.round(s / 60))
        }
    }
}
