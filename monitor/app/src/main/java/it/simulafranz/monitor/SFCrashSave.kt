// ANDROID (funzioni 21/11): non portato dall'iPad. Salvataggio periodico dello scenario in corso e ripresa dopo una
// chiusura anomala (crash, «Forza chiusura», sistema che chiude l'app, tablet riavviato).
//
// Cosa: {"state": SimulationState.exportState(), "run": SFScenarioStore.exportRun()} in <files>/similFranz/ripresa.json
// (scrittura atomica: file temporaneo e poi rinomina). Quando: ogni 5 s, subito quando lo scenario passa da «in corso»
// a «non in corso» (Azzera, Fine scenario, monitor spento), e a onPause/onStop.
//
// REGOLA DI RIPRISTINO (all'avvio, una volta sola, prima che parta il server):
//  - il salvataggio ha meno di 30 minuti (orologio del tablet; un salvataggio «dal futuro» non vale), e
//  - lo scenario era IN CORSO: dispositivo del partecipante acceso e scenario non terminato (end_scenario).
//    Dopo «Azzera» il dispositivo è spento: il salvataggio successivo lo dice e all'avvio non si ripristina niente.
//    Uno scenario in pausa ma acceso si ripristina (in pausa).
// Prima del ripristino il file si rinomina in ripresa.json.in-corso: se il ripristino stesso facesse chiudere l'app,
// all'avvio successivo non lo si riprova (niente giri di crash). Il paziente riparte da dove era (stesso ritmo, stessa
// fase dello scenario a fasi, stessi tempi relativi); nel registro del defibrillatore c'è «Stato ripristinato dopo il riavvio».
package it.simulafranz.monitor

import it.simulafranz.core.*
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.Locale
import java.util.concurrent.Executors

class SFCrashSave(dir: File, private val state: SimulationState, private val scenarios: SFScenarioStore?) {
    private val file = File(dir, "ripresa.json")
    private val writer = Executors.newSingleThreadExecutor()
    private var lastSaveAt = 0L
    private var lastInProgress: Boolean? = null

    private fun inProgress(): Boolean { val s = state.snapshot(); return s.devicePowered && !s.scenarioEnded }

    // chiamata ogni secondo (timer del registro in MainActivity)
    fun tick() {
        val now = System.currentTimeMillis()
        val ip = inProgress()
        if (ip != lastInProgress || now - lastSaveAt >= kEveryMs) save()
    }

    fun save() {
        val ip = inProgress()
        val d = linkedMapOf<String, Any?>("format" to 1L, "saved_at_ms" to System.currentTimeMillis(), "in_progress" to ip,
                                          "state" to state.exportState(), "run" to (scenarios?.exportRun() ?: mapOf("active" to false)))
        lastSaveAt = System.currentTimeMillis(); lastInProgress = ip
        val text = try { Json.stringify(d) } catch (e: Exception) { SFLog.log("ripresa: salvataggio non riuscito: $e"); return }
        writer.execute {
            try {
                file.parentFile?.mkdirs()
                val tmp = File.createTempFile(file.name, ".tmp", file.parentFile)
                tmp.writeText(text, Charsets.UTF_8)
                Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
            } catch (e: Exception) { SFLog.log("ripresa: scrittura non riuscita: $e") }
        }
    }

    // All'avvio: ripristina se la regola lo permette. Ritorna una riga per il log (null = niente da ripristinare).
    fun restoreIfNeeded(): String? {
        if (!file.isFile) return null
        val busy = File(file.parentFile, file.name + ".in-corso")
        busy.delete()
        if (!file.renameTo(busy)) return null
        @Suppress("UNCHECKED_CAST")
        val d = (try { Json.parseOrNull(busy.readText(Charsets.UTF_8)) } catch (e: Exception) { null }) as? Map<String, Any?>
        val age = System.currentTimeMillis() - ((d?.get("saved_at_ms") as? Number)?.toLong() ?: 0L)
        @Suppress("UNCHECKED_CAST") val st = d?.get("state") as? Map<String, Any?>
        val why = when {
            d == null || st == null -> "salvataggio illeggibile"
            age < 0 || age > kMaxAgeMs -> String.format(Locale.US, "salvataggio troppo vecchio (%d min)", age / 60000)
            d["in_progress"] != true -> "nessuno scenario in corso (azzerato, terminato o monitor spento)"
            else -> null
        }
        if (why != null) { busy.delete(); return "ripresa: niente da ripristinare: $why" }
        val err = state.restoreState(st!!)
        if (err != null) { busy.delete(); return "ripresa: ripristino non riuscito: $err" }
        @Suppress("UNCHECKED_CAST")
        val re = scenarios?.restoreRun(d!!["run"] as? Map<String, Any?>)
        busy.delete()
        save()                                                  // da qui riparte il salvataggio normale
        val sn = state.snapshot(); val run = scenarios?.runInfo()
        return String.format(Locale.US, "ripresa: scenario ripristinato (salvato %d s fa): ritmo %s, FC %d, layout %s%s%s", age / 1000, sn.rhythm, sn.heartRate, sn.layout,
            if (run?.get("active") == true) String.format(Locale.US, ", scenario «%s» fase %d", run["name"], ((run["phase"] as? Number)?.toInt() ?: 0) + 1) else "",
            if (re != null) " (scenario a fasi: $re)" else "")
    }

    private companion object {
        const val kEveryMs = 5000L
        const val kMaxAgeMs = 30 * 60 * 1000L
    }
}
