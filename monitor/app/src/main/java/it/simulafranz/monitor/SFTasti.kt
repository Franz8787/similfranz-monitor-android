package it.simulafranz.monitor

import android.accessibilityservice.AccessibilityService
import android.os.SystemClock
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import it.simulafranz.uk.Uk

// ANDROID (guscio 25/11): tasti fisici del tablet nel guscio. Con il monitor in primo piano: «app recenti» = SCARICA,
// «indietro» = ACCENSIONE (tre pressioni di fila = app recenti), Home = CARICA (tre pressioni di fila = schermata Home),
// per non uscire per sbaglio dalla simulazione. Un'app normale non riceve il tasto Home: lo vede solo un servizio di accessibilità che filtra i tasti.
// Il servizio guarda SOLO i tre tasti fisici e solo con il monitor a schermo; non legge lo schermo e non raccoglie nulla.
// Va attivato una volta: Impostazioni › Accessibilità › Servizi installati › «similFranz: tasti del monitor».
object SFTasti {
    @Volatile var inFront = false
    @Volatile var hint: ((String) -> Unit)? = null
    @Volatile var power: (() -> Unit)? = null
    // con il monitor a schermo: «app recenti» (il più basso nel guscio) = SCARICA, «indietro» = ACCENSIONE, Home = CARICA
    @Volatile var attivo = false                  // il servizio è in funzione: l'attività lascia a lui i tasti
    @Volatile var shockDown: (() -> Unit)? = null
    @Volatile var shockUp: (() -> Unit)? = null
    const val FINESTRA_MS = 1500L         // le tre pressioni di Home per uscire devono stare in questo tempo
    const val ATTESA_MS = 450L            // attesa prima della CARICA, per distinguere la pressione singola
    @Volatile var charge: (() -> Unit)? = null

    // Tasti a schermo CARICA e SCARICA dei layout: il tasto fisico «preme» quello a schermo (stessa animazione, stesso
    // comportamento del layout). Ogni layout registra i suoi quando li crea; vale quello che in questo momento è a schermo e toccabile.
    private val tasti = ArrayList<Pair<String, java.lang.ref.WeakReference<it.simulafranz.uk.UIButton>>>()
    fun register(name: String?, b: it.simulafranz.uk.UIButton) {
        val k = when (name) { "SHOCK", "SCARICA" -> "SHOCK"; "CHARGE", "CARICA" -> "CHARGE"; "POWER", "ON" -> "POWER"; else -> return }
        synchronized(tasti) { tasti.removeAll { it.second.get() == null }; tasti.add(Pair(k, java.lang.ref.WeakReference(b))) }
    }
    /** il tasto a schermo con quel ruolo ("SHOCK" o "CHARGE"), se è visibile e toccabile adesso */
    fun onScreen(role: String): it.simulafranz.uk.UIButton? {
        val c = synchronized(tasti) { tasti.filter { it.first == role }.mapNotNull { it.second.get() } }
        for (b in c.asReversed()) {
            try {
                val w = b.window ?: continue
                val p = b.convertPoint(it.simulafranz.uk.CGPointMake(b.bounds.width / 2, b.bounds.height / 2), w)
                var h = w.hitTest(p); while (h != null && h !is it.simulafranz.uk.UIControl) h = h.superview
                if (h === b) return b
            } catch (e: Exception) {}
        }
        return null
    }
    fun centro(b: it.simulafranz.uk.UIButton) = it.simulafranz.uk.CGPointMake(b.bounds.width / 2, b.bounds.height / 2)
}

class SFTastiService : AccessibilityService() {
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}
    override fun onServiceConnected() { super.onServiceConnected(); SFTasti.attivo = true }
    override fun onUnbind(intent: android.content.Intent?): Boolean { SFTasti.attivo = false; return super.onUnbind(intent) }
    private var scaricaGiu = false

    /** tasto con due lavori: una pressione = funzione del monitor, tre pressioni di fila = la sua funzione di Android.
     *  La funzione del monitor parte dopo un attimo (ATTESA_MS), per capire se arrivano altre pressioni. */
    private inner class Triplo(val una: () -> Unit, val tre: () -> Unit, val avviso: String) {
        var primo = 0L; var pressioni = 0; var ingoiato = false
        fun tasto(event: KeyEvent): Boolean {
            if (event.action == KeyEvent.ACTION_DOWN) {
                if (!SFTasti.inFront) { ingoiato = false; return false }
                if (event.repeatCount > 0) return ingoiato
                ingoiato = true
                val now = SystemClock.uptimeMillis()
                if (now - primo > SFTasti.FINESTRA_MS) { primo = now; pressioni = 0 }
                pressioni++; val n = pressioni; val serie = primo
                if (n >= 3) { primo = 0L; pressioni = 0; try { tre() } catch (e: Exception) {} }
                else if (n == 1) Uk.after(SFTasti.ATTESA_MS / 1000.0) { if (pressioni == 1 && primo == serie && SFTasti.inFront) una() }
                else Uk.async { SFTasti.hint?.invoke(avviso) }
                return true
            }
            val s = ingoiato; if (event.action == KeyEvent.ACTION_UP) ingoiato = false
            return s
        }
    }
    // Home: CARICA, tre pressioni = schermata Home. Indietro (il tasto in alto nel guscio): ACCENSIONE, tre pressioni = app recenti.
    private val home = Triplo({ SFTasti.charge?.invoke() }, { performGlobalAction(GLOBAL_ACTION_HOME) }, "Premi ancora Home per uscire dal monitor")
    private val indietro = Triplo({ SFTasti.power?.invoke() }, { performGlobalAction(GLOBAL_ACTION_RECENTS) }, "Premi ancora per aprire le app recenti")

    override fun onKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode == KeyEvent.KEYCODE_APP_SWITCH) {            // tasto «app recenti» (in basso nel guscio) = SCARICA
            if (event.action == KeyEvent.ACTION_DOWN) {
                if (!SFTasti.inFront) { scaricaGiu = false; return false }
                if (event.repeatCount == 0) { scaricaGiu = true; Uk.async { SFTasti.shockDown?.invoke() } }
                return scaricaGiu
            }
            val s = scaricaGiu; if (s && event.action == KeyEvent.ACTION_UP) { scaricaGiu = false; Uk.async { SFTasti.shockUp?.invoke() } }
            return s
        }
        if (event.keyCode == KeyEvent.KEYCODE_BACK) return indietro.tasto(event)
        if (event.keyCode == KeyEvent.KEYCODE_HOME) return home.tasto(event)
        return false
    }
}
