// Portato da src/SFScenarioStore.m (monitor iPad)
// similFranz Monitor — scenari a fasi, salvati sul monitor (così web app e app controller vedono gli stessi).
// Uno scenario = nome + note + elenco ordinato di FASI. Una fase = nome + blocco di parametri del paziente
// (ritmo, FC, PA, SpO2, FR, EtCO2, temperatura, refill) + tempo di transizione + nota per l'istruttore.
// Le fasi NON avanzano da sole: è l'istruttore che decide quando passare alla successiva (o a una qualsiasi).
// Fra una fase e l'altra il paziente resta dov'è, e l'istruttore può sempre ritoccare i parametri a mano.
// Kotlin puro: testabile sul Mac.
package it.simulafranz.core

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.Locale
import java.util.Random

// Risultato di save / normalize: lo scenario normalizzato, oppure null con error leggibile.
data class SFScenarioResult(val scenario: Map<String, Any?>?, val error: String?)

// dir: cartella dove salvare il file "scenari.json" (null = solo in memoria). Se il file non esiste si parte con gli scenari di esempio.
class SFScenarioStore(dir: File?, state: SimulationState) {
    private val path: File? = if (dir != null) File(dir, "scenari.json") else null
    private val state: SimulationState = state
    private val list = ArrayList<Map<String, Any?>>()
    private var runId: String? = null; private var phase = -1; private var phaseAt = 0.0

    init {
        val d = if (path != null && path.isFile) (try { path.readText(Charsets.UTF_8) } catch (e: Exception) { null }) else null
        val j = if (d != null) Json.parseOrNull(d) else null
        if (j is List<*>) {
            for (s in j) { val n = normalize(s).scenario; if (n != null) list.add(n) }
        } else {
            for (s in exampleScenarios()) { val n = normalize(s).scenario; if (n != null) list.add(n) }
        }
    }


    private fun persist() {
        val path = this.path ?: return
        try {
            val d = Json.stringify(list).toByteArray(Charsets.UTF_8)
            val dir = path.parentFile
            dir?.mkdirs()
            // scrittura atomica: file temporaneo nella stessa cartella, poi rinomina
            val tmp = File.createTempFile(path.name, ".tmp", dir)
            tmp.writeBytes(d)
            Files.move(tmp.toPath(), path.toPath(), StandardCopyOption.REPLACE_EXISTING)
        } catch (e: Exception) {
            // come sull'iPad: gli errori di scrittura si ignorano
        }
    }

    // Controlla e ripulisce uno scenario. I parametri delle fasi sono validati dal modello (stessi limiti di "transition").
    private fun normalize(input: Any?): SFScenarioResult {
        fun FAIL(format: String, vararg args: Any?): SFScenarioResult = SFScenarioResult(null, String.format(Locale.US, format, *args))
        if (input !is Map<*, *>) return FAIL("scenario must be an object")
        val name = Text(input["name"], 60)
        if (name.isEmpty()) return FAIL("scenario: name is required")
        val phases = input["phases"]
        if (phases !is List<*> || phases.size < 1 || phases.size > kMaxPhases) return FAIL("scenario: phases must be 1..%d", kMaxPhases)
        val out = ArrayList<Map<String, Any?>>()
        var i = 0
        for (p in phases) {
            i++
            if (p !is Map<*, *>) return FAIL("phase %d is not an object", i)
            val q = LinkedHashMap<String, Any?>()
            val pn = Text(p["name"], 40)
            q["name"] = if (pn.isNotEmpty()) pn else String.format(Locale.US, "Fase %d", i)
            q["note"] = Text(p["note"], 200)
            @Suppress("UNCHECKED_CAST")
            val params: Map<String, Any?> = if (p["params"] is Map<*, *>) p["params"] as Map<String, Any?> else emptyMap()
            val rhythm: String? = (p["rhythm"] as? String)?.takeIf { it.isNotEmpty() }
            val dur = integerValue(p["duration_s"])
            if (dur < 0 || dur > 3600) return FAIL("phase %d: duration_s out of range [0..3600]", i)
            q["duration_s"] = dur
            var hasExtra = false
            if (isNumber(p["temperature_c"])) {
                val t = doubleValue(p["temperature_c"])
                if (t < 25 || t > 45) return FAIL("phase %d: temperature_c out of range [25..45]", i)
                q["temperature_c"] = round(t * 10) / 10; hasExtra = true
            }
            if (isNumber(p["crt_s"])) {
                val c = doubleValue(p["crt_s"])
                if (c < 0.5 || c > 10) return FAIL("phase %d: crt_s out of range [0.5..10]", i)
                q["crt_s"] = round(c * 2) / 2; hasExtra = true
            }
            // ANDROID (guasti 22/11): facoltativo, malfunzionamenti simulati del dispositivo all'inizio della fase
            val df = p["device_faults"]
            if (df != null) {
                if (df !is List<*> || df.size > SLDeviceFaultKinds.size || df.any { it !is String || !SLDeviceFaultKinds.contains(it) })
                    return FAIL("phase %d: device_faults must be a list of %s", i, SLDeviceFaultKinds.joinToString(", "))
                q["device_faults"] = ArrayList(df.distinct()); hasExtra = true
            }
            // ANDROID (funzioni mancanti 22/11): facoltativi, ventilazione (CC-04/AE-12), T2, linea arteriosa, esami (oggetti
            // degli stessi comandi set_ventilation / set_temperature2 / set_art_line / set_labs: controllati su uno stato di prova)
            if (isNumber(p["temperature2_c"])) {
                val t = doubleValue(p["temperature2_c"])
                if (t < 25 || t > 45) return FAIL("phase %d: temperature2_c out of range [25..45]", i)
                q["temperature2_c"] = round(t * 10) / 10; hasExtra = true
            }
            for ((key, cmd) in listOf("ventilation" to "set_ventilation", "art_line" to "set_art_line", "labs" to "set_labs")) {
                val v = p[key] ?: continue
                if (v !is Map<*, *>) return FAIL("phase %d: %s must be an object", i, key)
                val err = SimulationState("probe").applyCommand(linkedMapOf("command" to cmd, "value" to v))
                if (err != null) return FAIL("phase %d: %s: %s", i, key, err)
                q[key] = LinkedHashMap(v.entries.associate { it.key.toString() to it.value }); hasExtra = true
            }
            // ANDROID (regia morfologie ECG 22/11): facoltativa, la morfologia dell'ECG della fase (stesso valore di
            // set_ecg_morphology: oggetto parziale o "normal"); controllata su un ECG normale, applicata dentro la transizione
            val em = p["ecg_morphology"]
            if (em != null) {
                val (mm, err) = SFEcgMorphology.parse(em, SLEcgMorph.NORMAL)
                if (err != null || mm == null) return FAIL("phase %d: %s", i, (err ?: "bad ecg_morphology").replace("set_ecg_morphology", "ecg_morphology"))
                q["ecg_morphology"] = if (em is String) "normal" else LinkedHashMap((em as Map<*, *>).entries.associate { it.key.toString() to it.value })
                hasExtra = true
            }
            if (params.isNotEmpty() || rhythm != null) {
                val t = LinkedHashMap<String, Any?>()
                t["params"] = params; t["duration_s"] = dur
                if (rhythm != null) t["rhythm"] = rhythm
                val e = state.validateTransition(t)
                if (e != null) return FAIL("phase %d: %s", i, e)
                val s = params["systolic_mmhg"]; val d = params["diastolic_mmhg"]
                if (s != null && d != null && integerValue(d) >= integerValue(s)) return FAIL("phase %d: diastolic must be lower than systolic", i)
                val pp = LinkedHashMap<String, Any?>()
                for (k in params.keys) pp[k] = integerValue(params[k])
                q["params"] = pp
                if (rhythm != null) q["rhythm"] = rhythm
            } else {
                if (!hasExtra) return FAIL("phase %d is empty: set a rhythm or at least one parameter", i)
                q["params"] = LinkedHashMap<String, Any?>()
            }
            out.add(q)
        }
        var sid = Text(input["id"], 40)
        if (sid.isEmpty()) sid = String.format(Locale.US, "sc-%08x%04x", (System.currentTimeMillis() / 1000).toInt(), rnd.nextInt(0xffff))
        return SFScenarioResult(linkedMapOf<String, Any?>("id" to sid, "name" to name, "notes" to Text(input["notes"], 600), "phases" to out), null)
    }

    fun scenarios(): List<Map<String, Any?>> { synchronized(this) { return ArrayList(list) } }

    private fun indexOf(sid: Any?): Int {
        for (i in list.indices) if (sid != null && list[i]["id"] == sid) return i
        return -1
    }

    // Salva (nuovo o modifica, per id). Ritorna lo scenario normalizzato, o null con error leggibile.
    fun save(scenario: Map<String, Any?>?): SFScenarioResult {
        synchronized(this) {
            val r = normalize(scenario)
            val n = r.scenario ?: return r
            val i = indexOf(n["id"])
            if (i >= 0) {
                list[i] = n
                // scenario in corso modificato: si resta sulla stessa fase se esiste ancora
                val count = (n["phases"] as List<*>).size
                if (runId != null && runId == n["id"] && phase >= count) phase = count - 1
            } else {
                if (list.size >= kMaxScenarios) return SFScenarioResult(null, String.format(Locale.US, "too many scenarios (max %d): delete one first", kMaxScenarios))
                list.add(n)
            }
            persist()
            return r
        }
    }

    fun deleteId(sid: String?): String? {
        synchronized(this) {
            val i = indexOf(sid)
            if (i < 0) return "scenario not found"
            list.removeAt(i)
            if (runId != null && runId == sid) { runId = null; phase = -1 }
            persist()
            return null
        }
    }

    // chiamare con synchronized già acquisito
    // ANDROID (transizioni ritmo 22/11): immediate = la fase da cui parte lo scenario (il paziente è già così: niente preludio);
    // le fasi successive cambiano ritmo con il preludio fisiologico (onset auto)
    private fun applyPhase(idx: Int, of: Map<String, Any?>, immediate: Boolean = false): String? =
        SLCommandSource.with("scenario") { applyPhase0(idx, of, immediate) }   // ANDROID (funzioni mancanti 22/11): registro «scenario»
    private fun applyPhase0(idx: Int, of: Map<String, Any?>, immediate: Boolean): String? {
        val sc = of
        val phases = sc["phases"] as List<*>
        if (idx < 0 || idx >= phases.size) return "phase out of range"
        val p = phases[idx] as Map<*, *>
        val params = (p["params"] as? Map<*, *>) ?: emptyMap<String, Any?>()
        if (params.isNotEmpty() || p["rhythm"] != null || p["ecg_morphology"] != null) {
            val t = LinkedHashMap<String, Any?>()
            t["params"] = params; t["duration_s"] = p["duration_s"] ?: 0L
            if (p["rhythm"] != null) t["rhythm"] = p["rhythm"]
            if (p["ecg_morphology"] != null) t["ecg_morphology"] = p["ecg_morphology"]      // ANDROID (regia morfologie ECG 22/11)
            if (immediate) t["onset"] = "immediate"
            val e = state.applyCommand(linkedMapOf<String, Any?>("command" to "transition", "value" to t))
            if (e != null) return e
        }
        if (p["temperature_c"] != null) state.applyCommand(linkedMapOf<String, Any?>("command" to "set_temperature", "value" to p["temperature_c"]))
        if (p["crt_s"] != null) state.applyCommand(linkedMapOf<String, Any?>("command" to "set_crt", "value" to p["crt_s"]))
        // ANDROID (funzioni mancanti 22/11)
        if (p["temperature2_c"] != null) state.applyCommand(linkedMapOf<String, Any?>("command" to "set_temperature2", "value" to p["temperature2_c"]))
        for ((key, cmd) in listOf("ventilation" to "set_ventilation", "art_line" to "set_art_line", "labs" to "set_labs"))
            p[key]?.let { state.applyCommand(linkedMapOf<String, Any?>("command" to cmd, "value" to it)) }
        // ANDROID (guasti 22/11): la fase dichiara i malfunzionamenti: si parte da «dispositivo a posto» e si attivano quelli elencati
        (p["device_faults"] as? List<*>)?.let { l ->
            state.applyCommand(linkedMapOf<String, Any?>("command" to "clear_device_faults"))
            for (k in l) state.applyCommand(linkedMapOf<String, Any?>("command" to "set_device_fault", "value" to linkedMapOf("kind" to k, "on" to true)))
        }
        runId = sc["id"] as String; phase = idx; phaseAt = state.clock()
        return null
    }

    // Conduzione: avvio dalla fase indicata (di solito 0), salto a una fase, fase successiva, fine.
    fun start(sid: String?, phase: Int): String? {
        synchronized(this) {
            val i = indexOf(sid)
            if (i < 0) return "scenario not found"
            // ANDROID (difetti clinici 22/11, CC-01): uno scenario parte da un ECG normale (la morfologia di uno scenario o di uno
            // stato di prima non resta); la fase la imposta se ne ha una
            state.applyCommand(linkedMapOf<String, Any?>("command" to "set_ecg_morphology", "value" to "normal"))
            val e = applyPhase(phase, list[i], true)
            if (e != null) return e
            // ANDROID (avvio scenario 22/11): da PRONTO (o da terminato) avviare uno scenario a fasi = start_scenario (orologio da
            // 00:00, registro, simulazione in corso); con lo scenario già avviato l'orologio riparte da zero come prima
            val sn = state.snapshot()
            if (!sn.scenarioStarted || sn.scenarioEnded) state.applyCommand(linkedMapOf<String, Any?>("command" to "start_scenario"))
            else state.applyCommand(linkedMapOf<String, Any?>("command" to "reset_clock"))      // l'orologio di scenario riparte con lo scenario
            return null
        }
    }

    fun gotoPhase(phase: Int): String? {
        synchronized(this) {
            val i = if (runId != null) indexOf(runId) else -1
            if (i < 0) return "no scenario is running"
            return applyPhase(phase, list[i])
        }
    }

    fun next(): String? {
        synchronized(this) {
            val i = if (runId != null) indexOf(runId) else -1
            if (i < 0) return "no scenario is running"
            if (phase + 1 >= (list[i]["phases"] as List<*>).size) return "already at the last phase"
            return applyPhase(phase + 1, list[i])
        }
    }

    fun stop() { synchronized(this) { runId = null; phase = -1 } }

    // ANDROID (funzioni 21/11): scenario in corso da salvare insieme a SimulationState.exportState() (ripartenza dopo un
    // crash). Ripristinare NON riapplica la fase (il paziente lo ripristina restoreState): rimette solo id, fase e tempo di fase.
    fun exportRun(): Map<String, Any?> {
        synchronized(this) {
            if (runId == null) return linkedMapOf<String, Any?>("active" to false)
            return linkedMapOf<String, Any?>("active" to true, "id" to runId, "phase" to phase.toLong(),
                                             "phase_elapsed_s" to Math.max(0.0, state.clock() - phaseAt))
        }
    }
    fun restoreRun(run: Map<String, Any?>?): String? {
        synchronized(this) {
            if (run == null || run["active"] != true) { runId = null; phase = -1; return null }
            val i = indexOf(run["id"])
            if (i < 0) { runId = null; phase = -1; return "scenario not found" }
            val n = (list[i]["phases"] as List<*>).size
            val ph = (run["phase"] as? Number)?.toInt() ?: 0
            val el = (run["phase_elapsed_s"] as? Number)?.toDouble()?.takeIf { it.isFinite() && it >= 0 } ?: 0.0
            runId = list[i]["id"] as String; phase = Math.max(0, Math.min(n - 1, ph)); phaseAt = state.clock() - Math.min(el, 86400.0)
            return null
        }
    }

    // {active, id, name, phase, phases, phase_name, phase_note, phase_time_s, next_name}; senza scenario: {active:false}
    fun runInfo(): Map<String, Any?> {
        synchronized(this) {
            val i = if (runId != null) indexOf(runId) else -1
            if (i < 0) return linkedMapOf<String, Any?>("active" to false)
            val sc = list[i]; val ph = sc["phases"] as List<*>
            val p = ph[Math.min(phase, ph.size - 1)] as Map<*, *>
            val d = linkedMapOf<String, Any?>("active" to true, "id" to sc["id"], "name" to sc["name"], "phase" to phase.toLong(), "phases" to ph.size.toLong(),
                                              "phase_name" to p["name"], "phase_note" to (p["note"] ?: ""),
                                              "phase_time_s" to Math.max(0.0, state.clock() - phaseAt).toLong())
            if (phase + 1 < ph.size) d["next_name"] = (ph[phase + 1] as Map<*, *>)["name"]
            return d
        }
    }

    companion object {
        private const val kMaxScenarios = 40
        private const val kMaxPhases = 24
        private val rnd = Random()

        private fun Text(v: Any?, max: Int): String {
            if (v !is String) return ""
            val s = v.trim()
            return if (s.length > max) s.substring(0, max) else s
        }

        // -[NSNumber/NSString integerValue]: un numero (anche booleano) o un testo che comincia con un numero; altrimenti 0
        private fun integerValue(v: Any?): Long = when (v) {
            is Boolean -> if (v) 1 else 0
            is Number -> v.toDouble().toLong()
            is String -> Regex("^\\s*[+-]?\\d+").find(v)?.value?.trim()?.toDoubleOrNull()?.toLong() ?: 0
            else -> 0
        }
        // isKindOfClass:[NSNumber class]: sull'iPad anche un booleano è un NSNumber
        private fun isNumber(v: Any?): Boolean = v is Number || v is Boolean
        private fun doubleValue(v: Any?): Double = when (v) {
            is Boolean -> if (v) 1.0 else 0.0
            is Number -> v.toDouble()
            else -> 0.0
        }
        // round del C: i mezzi lontano dallo zero
        private fun round(x: Double): Double = if (x < 0) -Math.floor(-x + 0.5) else Math.floor(x + 0.5)

        private fun P(hr: Long, s: Long, d: Long, sp: Long, rr: Long, et: Long): Map<String, Any?> =
            linkedMapOf("heart_rate_bpm" to hr, "systolic_mmhg" to s, "diastolic_mmhg" to d, "spo2_percent" to sp, "respiratory_rate_rpm" to rr, "etco2_mmhg" to et)

        private fun D(vararg pairs: Pair<String, Any?>): Map<String, Any?> = linkedMapOf(*pairs)

        fun exampleScenarios(): List<Map<String, Any?>> {
            return listOf(
              D("id" to "es-arresto-fv", "name" to "Esempio · Dolore toracico, arresto in FV, ROSC",
                "notes" to "Uomo, 58 anni, dolore toracico da 40 minuti. Obiettivi: riconoscere il peggioramento, defibrillazione precoce, gestione post-ROSC.",
                "phases" to listOf(
                  D("name" to "Arrivo: dolore toracico", "note" to "Monitoraggio, ECG, accesso venoso. Avanzare quando la squadra ha completato la valutazione.", "duration_s" to 0L, "rhythm" to "sinus", "params" to P(96, 150, 92, 96, 20, 36), "crt_s" to 2L,
                    "ecg_morphology" to D("st" to "nstemi_depression")),   // ANDROID (difetti clinici 22/11, CC-01)
                  D("name" to "Peggiora: ipoteso e tachicardico", "note" to "Avanzare se non riconoscono il peggioramento o dopo la rivalutazione.", "duration_s" to 60L, "rhythm" to "sinus_tachycardia", "params" to P(122, 92, 58, 93, 24, 30), "crt_s" to 3L),
                  D("name" to "Arresto: fibrillazione ventricolare", "note" to "RCP e defibrillazione. L'esito delle scariche si decide dal pannello Defibrillatore.", "duration_s" to 0L, "rhythm" to "vf", "params" to D()),
                  D("name" to "ROSC", "note" to "Dopo la scarica efficace: valutazione ABCDE, ECG a 12 derivazioni.", "duration_s" to 30L, "rhythm" to "sinus_tachycardia", "params" to P(108, 98, 62, 94, 12, 42), "crt_s" to 3L),
                )),
              D("id" to "es-shock-settico", "name" to "Esempio · Shock settico che risponde ai liquidi",
                "notes" to "Donna, 72 anni, febbre e confusione. Obiettivi: riconoscere la sepsi, liquidi, rivalutazione.",
                "phases" to listOf(
                  D("name" to "Arrivo: febbre e tachicardia", "note" to "", "duration_s" to 0L, "rhythm" to "sinus_tachycardia", "params" to P(112, 105, 60, 94, 24, 32), "temperature_c" to 38.9, "crt_s" to 3L),
                  D("name" to "Shock", "note" to "Avanzare qui se i liquidi tardano.", "duration_s" to 120L, "rhythm" to "sinus_tachycardia", "params" to P(134, 78, 42, 90, 30, 26), "temperature_c" to 39.2, "crt_s" to 5L),
                  D("name" to "Dopo il carico di liquidi", "note" to "Avanzare quando il bolo è stato somministrato.", "duration_s" to 90L, "rhythm" to "sinus_tachycardia", "params" to P(110, 98, 58, 95, 22, 32), "crt_s" to 3L),
                )),
              D("id" to "es-insuff-resp", "name" to "Esempio · Insufficienza respiratoria acuta",
                "notes" to "Uomo, 66 anni, BPCO riacutizzata. Obiettivi: ossigeno controllato, ventilazione, rivalutazione.",
                "phases" to listOf(
                  D("name" to "Arrivo: dispnea", "note" to "", "duration_s" to 0L, "rhythm" to "sinus_tachycardia", "params" to P(108, 145, 88, 88, 28, 50)),
                  D("name" to "Si esaurisce", "note" to "Avanzare se non viene supportata la ventilazione.", "duration_s" to 120L, "rhythm" to "sinus_tachycardia", "params" to P(124, 150, 90, 80, 34, 62)),
                  D("name" to "Bradipnea e sopore", "note" to "", "duration_s" to 60L, "rhythm" to "sinus", "params" to P(88, 110, 70, 74, 8, 78)),
                  D("name" to "Migliora con il supporto", "note" to "Avanzare quando ventilano in modo efficace.", "duration_s" to 90L, "rhythm" to "sinus", "params" to P(96, 128, 78, 94, 18, 46)),
                )),
            )
        }
    }
}
