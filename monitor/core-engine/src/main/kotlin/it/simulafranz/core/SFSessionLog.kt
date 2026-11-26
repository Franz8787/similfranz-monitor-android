// Portato da src/SFSessionLog.m (monitor iPad)
// similFranz Monitor — registro della sessione per il debriefing, tenuto sul monitor (web app e app controller
// leggono e scrivono lo stesso registro). Ogni voce ha il tempo di scenario, l'ora reale, una categoria e un testo.
//   - VOCI AUTOMATICHE: ciò che fanno i partecipanti sul monitor/defibrillatore (accensione, sonde applicate,
//     misure NIBP, allarmi silenziati, tutti gli eventi del defibrillatore: carica, scariche con l'energia, pacing, DAE…)
//     e ciò che fa l'istruttore dal controller (ritmi, parametri, fasi dello scenario, pausa, timer…).
//   - VOCI DELL'ISTRUTTORE: osservazioni da menu rapido (riconoscimento dell'arresto, farmaci, vie aeree…),
//     punti di forza, punti da discutere, note libere.
// Dal registro si ricava il riepilogo per il debriefing (tempi delle azioni critiche, scariche, farmaci).
// Il registro è diviso in SESSIONI, una per simulazione: una sessione nuova si apre da sola quando si avvia uno
// scenario o si azzera lo scenario (oppure a mano). Si conservano le ultime 20, salvate su file: restano anche se
// l'app viene chiusa, finché non si svuota il registro.
// Kotlin puro: testabile sul Mac.
package it.simulafranz.core

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// dir: cartella dove salvare il file "registro.json" (null = solo in memoria).
class SFSessionLog(dir: File?, state: SimulationState) {
    private val path: File? = if (dir != null) File(dir, "registro.json") else null
    private val state: SimulationState = state
    private val e = ArrayList<MutableMap<String, Any?>>(); private var next = 0
    private var prev: Map<String, Any?>? = null; private var lastDefibSeq = 0; private var dirty = false; private var savedAt = 0.0
    private val sessions = ArrayList<MutableMap<String, Any?>>(); private var session = 0; private var ventLoggedAt = 0.0      // {id, title, w}
    // ANDROID (avvio scenario 22/11): ultimo avvio visto (SimulationState.scenarioStartSeq). In PRONTO il registro non scrive;
    // al primo segno dell'avvio (voce, comando o giro di observe) si apre la sessione e si scrive «Scenario avviato».
    // Un ripristino dopo una chiusura forzata non fa crescere il contatore: nessuna sessione nuova.
    private var startSeqSeen = state.scenarioStartSeq
    // ANDROID (funzioni mancanti 22/11, IN-05/IN-06): voci prodotte dal cuore (ultimo numero letto) e allarmi in corso
    private var stSeq = state.stateEventSeq()
    private class AJ(val t0: Double, val lvl: Int, var seen: Double, var muteAfter: Int?)
    private val active = LinkedHashMap<String, AJ>()

    init {
        val d = if (path != null && path.isFile) (try { path.readText(Charsets.UTF_8) } catch (ex: Exception) { null }) else null
        val j = if (d != null) Json.parseOrNull(d) else null
        val entries: List<*>? = if (j is Map<*, *>) (j["entries"] as? List<*>) else (if (j is List<*>) j else null)   // vecchio formato: solo le voci
        if (entries != null) for (x in entries) if (x is Map<*, *> && x["text"] is String) e.add(mutableCopy(x))
        if (j is Map<*, *>) { val ss = j["sessions"]; if (ss is List<*>) for (x in ss) if (x is Map<*, *>) sessions.add(mutableCopy(x)) }
        next = if (e.isNotEmpty()) integerValue(e.last()["i"]) + 1 else 0
        if (sessions.isEmpty()) sessions.add(linkedMapOf<String, Any?>("id" to 1L, "title" to "Sessione libera", "w" to nowEpoch()))
        session = integerValue(sessions.last()["id"])
        if (countIn(session) != 0) addEntry("timer", "similFranz Monitor riavviato (il registro è stato conservato)", null, null, true)   // ANDROID (avvio scenario 22/11): anche in PRONTO
    }

    // ora reale in secondi (timeIntervalSince1970 troncato)
    // ANDROID (ora 22/11): l'ora sincronizzata con il controller (set_wall_clock), se l'ha mandata; altrimenti quella del tablet
    private fun nowEpoch(): Long = state.wallNowMs() / 1000

    private fun countIn(sid: Int): Int { var n = 0; for (d in e) if (integerValue(d["s"]) == sid) n++; return n }
    fun currentSession(): Int { synchronized(this) { return session } }

    // Apre una sessione nuova (titolo facoltativo). Se quella corrente è ancora vuota la riusa, cambiandole solo il titolo.
    fun newSession(title: String?): Int {
        synchronized(this) {
            val t = if (title != null && title.isNotEmpty()) (if (title.length > 60) title.substring(0, 60) else title) else "Sessione libera"
            val cur = sessions.last()
            if (countIn(session) == 0) { cur["title"] = t; cur["w"] = nowEpoch(); dirty = true; return session }
            session++
            sessions.add(linkedMapOf<String, Any?>("id" to session.toLong(), "title" to t, "w" to nowEpoch()))
            while (sessions.size > kMaxSessions) {            // si tengono le ultime: la più vecchia esce con le sue voci
                val old = integerValue(sessions.first()["id"]); sessions.removeAt(0)
                e.removeAll { d -> integerValue(d["s"]) == old }
            }
            dirty = true
            return session
        }
    }

    // [{id, title, w (ora di inizio), entries, duration_s, current}] dalla più vecchia alla più recente
    fun sessions(): List<Map<String, Any?>> {
        synchronized(this) {
            val out = ArrayList<Map<String, Any?>>()
            for (m in sessions) {
                val sid = integerValue(m["id"]); var n = 0; var dur = 0
                for (d in e) if (integerValue(d["s"]) == sid) { n++; dur = Math.max(dur, integerValue(d["t"])) }
                out.add(linkedMapOf<String, Any?>("id" to m["id"], "title" to (m["title"] ?: ""), "w" to (m["w"] ?: 0L), "entries" to n.toLong(), "duration_s" to dur.toLong(), "current" to (sid == session)))
            }
            return out
        }
    }

    // Salvataggio: si fotografa il registro sotto il lucchetto (altri thread possono aggiungere voci nello stesso momento)
    // e lo si scrive su disco in background, così il thread principale non si ferma. Da chiamare SENZA il lucchetto.
    private fun saveIfNeeded(force: Boolean) {
        var d: ByteArray? = null; var path: File? = null
        synchronized(this) {
            if (this.path == null || !dirty) return
            val now = System.currentTimeMillis() / 1000.0
            if (!force && now - savedAt < 5) return
            d = Json.stringify(linkedMapOf<String, Any?>("sessions" to sessions, "entries" to e)).toByteArray(Charsets.UTF_8)
            dirty = false; savedAt = now; path = this.path
        }
        val data = d ?: return; val file = path ?: return
        val w = Runnable {
            try {
                val dir = file.parentFile
                dir?.mkdirs()
                // scrittura atomica: file temporaneo nella stessa cartella, poi rinomina
                val tmp = File.createTempFile(file.name, ".tmp", dir)
                tmp.writeBytes(data)
                Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
            } catch (ex: Exception) {
                // come sull'iPad: gli errori di scrittura si ignorano
            }
        }
        if (force) w.run() else Thread(w, "it.simulafranz.registro").apply { priority = Thread.MIN_PRIORITY }.start()
    }

    // Voce esplicita. kind: phase, patient, monitor, electric, timer, action, drug, plus, minus, note. tag facoltativo
    // (ac, help, cpr_start, shock, arrest, rosc…) per il riepilogo. by = chi l'ha scritta (nome del controller).
    fun add(kind: String?, text: String?, tag: String?, by: String?): Map<String, Any?>? {
        // ANDROID (EtCO2 RCP 22/11): due voci del registro cambiano il paziente simulato (anche in PRONTO, quando il registro non scrive):
        // «Cambio dell'operatore alle compressioni» finisce l'affaticamento della RCP, l'adrenalina abbassa per poco l'EtCO2
        val low = text?.trim()?.lowercase(Locale.ITALY) ?: ""
        if (kind == "action" && low.contains("cambio") && low.contains("compressioni")) state.noteCprRescuerSwap()
        else if (kind == "drug" && (low.startsWith("adrenalina") || low.startsWith("epinefrina"))) state.noteEpinephrine()
        return addEntry(kind, text, tag, by)
    }

    // ANDROID (avvio scenario 22/11): avvio dello scenario visto per la prima volta: sessione nuova (se quella corrente ha già
    // voci; se è vuota la si tiene, con il suo titolo, e le si aggiorna l'ora d'inizio) e, se withEntry, «Scenario avviato · ore …».
    // Lo scenario a fasi scrive la sua voce di avvio (tag scenario_start) e non vuole quella generica.
    private fun syncStart(withEntry: Boolean) {
        synchronized(this) {
            val seq = state.scenarioStartSeq
            if (seq == startSeqSeen) return
            startSeqSeen = seq
            if (countIn(session) != 0) newSession(null) else { sessions.last()["w"] = nowEpoch(); dirty = true }
            // i cambi fatti in PRONTO non diventano voci: il confronto riparte dallo stato del momento dell'avvio
            val st = state.stateDictionary(); prev = st
            stSeq = state.stateEventSeq(); active.clear()        // ANDROID (IN-06): le prove in PRONTO non sono voci
            val ev = dict(st["defib"])["events"]; if (ev is List<*> && ev.isNotEmpty()) lastDefibSeq = Math.max(lastDefibSeq, integerValue(dict(ev.last())["seq"]))
            if (withEntry) addEntry("phase", String.format(Locale.US, "Scenario avviato · ore %s", synchronized(f) { f.format(Date(state.wallNowMs())) }), "scenario_start", null, true)
        }
    }

    private fun addEntry(kind: String?, text: Any?, tag: String?, by: String?, force: Boolean = false): MutableMap<String, Any?>? {
        var kind = kind
        if (text !is String) return null
        // ANDROID (avvio scenario 22/11): in PRONTO (scenario non avviato) il registro non scrive niente
        if (!force) { syncStart(tag != "scenario_start"); if (!state.isScenarioStarted()) return null }
        var t = text.trim()
        if (t.isEmpty()) return null
        if (t.length > 240) t = t.substring(0, 240)
        if (kind == null || !kinds().contains(kind)) kind = "note"
        synchronized(this) {
            val d = linkedMapOf<String, Any?>("i" to (next++).toLong(), "s" to session.toLong(), "t" to state.snapshot().scenarioTime.toLong(), "w" to nowEpoch(),
                                              "k" to kind, "text" to t)
            if (tag != null && tag.isNotEmpty() && tag.length <= 24) d["tag"] = tag
            if (by != null && by.isNotEmpty()) d["by"] = if (by.length > 40) by.substring(0, 40) else by
            e.add(d)
            while (e.size > kMaxEntries) e.removeAt(0)
            dirty = true
            return d
        }
    }

    fun flush() { saveIfNeeded(true) }                // salva subito su file (di norma salva da solo ogni pochi secondi)
    fun clear() {
        synchronized(this) {
            e.clear(); sessions.clear(); session++
            sessions.add(linkedMapOf<String, Any?>("id" to session.toLong(), "title" to "Sessione libera", "w" to nowEpoch()))
            dirty = true
        }
        saveIfNeeded(true)
    }
    fun nextIndex(): Int { synchronized(this) { return next } }
    // voci con i >= index; session 0 = tutte le sessioni
    fun entriesSince(index: Int, session: Int = 0): List<Map<String, Any?>> {
        val sid = session
        synchronized(this) {
            val a = ArrayList<Map<String, Any?>>()
            for (d in e) if (integerValue(d["i"]) >= index && (sid == 0 || integerValue(d["s"]) == sid)) a.add(LinkedHashMap(d))      // copie: la voce originale può ancora cambiare (etichetta della scarica)
            return a
        }
    }

    // MARK: - Voci automatiche

    // Da chiamare un paio di volte al secondo: confronta lo stato con quello di prima e registra i cambiamenti.
    fun observe() {
        val s = state.stateDictionary()
        // ANDROID (avvio scenario 22/11): in PRONTO niente voci automatiche (si tiene solo lo stato per il confronto)
        if (!state.isScenarioStarted()) {
            val ev0 = dict(s["defib"])["events"]
            synchronized(this) { prev = s; if (ev0 is List<*> && ev0.isNotEmpty()) lastDefibSeq = Math.max(lastDefibSeq, integerValue(dict(ev0.last())["seq"])); stSeq = state.stateEventSeq(); active.clear() }
            return
        }
        syncStart(true)
        synchronized(this) { stateEventsAndAlarms(s) }          // ANDROID (funzioni mancanti 22/11, IN-05/IN-06)
        var p: Map<String, Any?>? = null; synchronized(this) { p = prev; prev = s }
        val pp = p
        if (pp == null) { val ev = dict(s["defib"])["events"]; lastDefibSeq = if (ev is List<*>) integerValue(dict(ev.lastOrNull())["seq"]) else 0; return }
        fun B(d: Map<*, *>, k: String): Boolean = boolValue(d[k])
        if (B(s, "monitor_on") != B(pp, "monitor_on")) add("monitor", if (B(s, "monitor_on")) (if (s["monitor_on_by"] == "istruttore") "Monitor acceso dall'istruttore (a distanza)" else "Monitor acceso dai partecipanti") else "Monitor spento", if (B(s, "monitor_on")) "monitor_on" else null, null)
        val sn = dict(s["sensors"]); val pn = dict(pp["sensors"])
        for (k in sn.keys) if (boolValue(sn[k]) != boolValue(pn[k]) && k != "pads")     // le piastre arrivano dagli eventi del defibrillatore
            add("monitor", String.format(Locale.US, "%s: %s", SName(k), if (boolValue(sn[k])) "applicata" else "tolta"), null, null)
        // defibrillatore: tutti gli eventi nuovi (carica, scariche, DAE, pacing, sync…), con chi li ha fatti
        val df = dict(s["defib"]); val pdf = dict(pp["defib"])
        val ev: List<*> = (df["events"] as? List<*>) ?: emptyList<Any?>()
        // eventi nuovi = numero progressivo più alto dell'ultimo visto (due eventi uguali nello stesso secondo restano due)
        val added = ArrayList<MutableMap<String, Any?>>()
        for (x0 in ev) {
            val x = dict(x0)
            if (integerValue(x["seq"]) <= lastDefibSeq) continue
            val d = addEntry("electric", x["text"], null, if (boolValue(x["instructor"])) "istruttore" else "partecipanti")
            if (d != null) added.add(d)
        }
        if (ev.isNotEmpty()) lastDefibSeq = Math.max(lastDefibSeq, integerValue(dict(ev.last())["seq"]))
        // (pdf) il cambio di modalità è già fra gli eventi del defibrillatore ("Modalità: manuale")
        val sc = integerValue(df["shock_count"]); val psc = integerValue(pdf["shock_count"])
        if (sc > psc) {                      // scarica erogata: la voce porta l'energia e il numero progressivo (per il riepilogo)
            var hit: MutableMap<String, Any?>? = null
            for (d in added) { val l = (d["text"] as String).lowercase(); if (hit == null && (l.startsWith("shock") || l.startsWith("scarica") || l.contains("erogat"))) hit = d }
            if (hit == null) for (d in added) { val l = (d["text"] as String).lowercase(); if (hit == null && (l.contains("scaric") || l.contains("shock"))) hit = d }
            if (hit == null) hit = addEntry("electric", String.format(Locale.US, "Scarica n. %d erogata: %s J", sc, desc(df["energy_j"])), null, "partecipanti")
            val h = hit
            synchronized(this) { if (h != null) { h["tag"] = "shock"; h["energy_j"] = df["energy_j"] ?: 0L; h["n"] = sc.toLong() }; dirty = true }
        }
        if (!isEqual(s["rhythm"], pp["rhythm"])) {
            // ANDROID (transizioni ritmo 22/11): una sola voce quando il ritmo arriva, con il modo in cui è iniziato
            val ol = dict(s["rhythm_onset_last"]); val ol0 = dict(pp["rhythm_onset_last"])
            val how = if (isEqual(ol["to"], s["rhythm"]) && !isEqual(ol["id"], ol0["id"]) && ol["text"] is String) " — insorgenza: " + ol["text"] else ""
            add("patient", "Ritmo: " + RName(s["rhythm"]) + how, null, null)
        }
        if (B(s, "pulse_present") != B(pp, "pulse_present"))
            add("patient", if (B(s, "pulse_present")) "Il paziente ha di nuovo il polso (ROSC)" else "Paziente senza polso: arresto cardiaco", if (B(s, "pulse_present")) "rosc" else "arrest", null)
        if (B(s, "cpr_active") != B(pp, "cpr_active")) add("action", if (B(s, "cpr_active")) "RCP in corso: inizio delle compressioni" else "RCP: compressioni interrotte", if (B(s, "cpr_active")) "cpr_start" else "cpr_stop", null)
        if (B(s, "simulation_running") != B(pp, "simulation_running") && !(B(s, "scenario_ended") && !B(pp, "scenario_ended")))   // ANDROID (fine scenario 22/11): la fine ha già la sua voce
            add("timer", if (B(s, "simulation_running")) "Scenario ripreso" else "Scenario in pausa", null, null)
        if (isEqual(s["nibp_status"], "complete") && !isEqual(pp["nibp_status"], "complete"))
            add("monitor", String.format(Locale.US, "NIBP misurata: %s/%s", desc(s["nibp_last_systolic_mmhg"]), desc(s["nibp_last_diastolic_mmhg"])), null, null)
        else if (isEqual(s["nibp_status"], "measuring") && !isEqual(pp["nibp_status"], "measuring")) add("monitor", "Misura NIBP avviata", null, null)
        else if (isEqual(s["nibp_status"], "error") && !isEqual(pp["nibp_status"], "error")) add("monitor", "Misura NIBP non riuscita", null, null)
        // ANDROID (IN-06): «Allarmi silenziati» ora arriva dal cuore (con chi e il tempo di risposta: stateEventsAndAlarms)
        if (!isEqual(s["layout"], pp["layout"])) add("monitor", "Layout del monitor cambiato", null, null)
        saveIfNeeded(false)
    }

    // ANDROID (funzioni mancanti 22/11, IN-05/IN-06): voci del cuore (zero, lavaggio, limiti, silenzi, ventilazione, esami…) e
    // diario degli allarmi: inizio con la priorità, fine con la durata, silenzio con il tempo di risposta. Sotto il lucchetto.
    private fun stateEventsAndAlarms(s: Map<String, Any?>) {
        val sn = state.snapshot()
        val t = sn.scenarioTime
        fun mmss(x: Int): String = String.format(Locale.US, "%d:%02d", x / 60, x % 60)
        for (ev in state.stateEventsSince(stSeq)) {
            stSeq = ev.seq
            var text = ev.text
            var resp: Int? = null
            if (ev.tag == "alarm_mute") {
                val first = active.entries.filter { it.value.lvl >= SLAlarmMedium }.minWithOrNull(compareBy({ it.value.t0 }, { -it.value.lvl }))   // a parità d'inizio il più grave
                if (first != null) {
                    resp = Math.max(0, Math.floor(t - first.value.t0).toInt())
                    text += " — dopo ${resp} s dall'allarme ${if (first.value.lvl == SLAlarmHigh) "ALTO" else "MEDIO"} «${first.key}»"
                    for (a in active.values) if (a.muteAfter == null && a.lvl >= SLAlarmMedium) a.muteAfter = Math.max(0, Math.floor(t - a.t0).toInt())
                } else text += " (nessun allarme attivo)"
            }
            val k = if (ev.tag?.startsWith("alarm") == true && ev.tag != "alarm_delay") "alarm" else ev.kind
            val d = addEntry(k, text, ev.tag, ev.by) ?: continue
            if (resp != null) d["resp_s"] = resp.toLong()
            ev.extra?.forEach { (kk, vv) -> if (vv != null) d[kk] = vv }
            dirty = true
        }
        // diario degli allarmi (solo a scenario in corso, monitor acceso)
        if (!sn.simulationRunning) return
        val r = if (sn.devicePowered) AlarmManager.evaluate(sn) else null
        val now = t
        val cur = r?.levels ?: emptyMap()
        for ((m, lvl) in cur) {
            if (m == "SpO2 RICERCA POLSO" || m == "ART AZZERAMENTO IN CORSO") continue      // stati brevi, non allarmi da discutere
            val a = active[m]
            if (a != null) { a.seen = now; continue }
            active[m] = AJ(now, lvl, now, if (sn.alarmsMuted) 0 else null)
            val ln = when (lvl) { SLAlarmHigh -> "ALTO"; SLAlarmMedium -> "MEDIO"; else -> "TECNICO" }
            val d = addEntry("alarm", "Allarme $ln: $m" + (if (sn.alarmsMuted) " (audio in pausa)" else ""), "alarm_on", null) ?: continue
            d["lvl"] = lvl.toLong(); d["alarm"] = m
        }
        val it = active.entries.iterator()
        while (it.hasNext()) {
            val (m, a) = it.next()
            if (cur.containsKey(m) || now - a.seen < 3.0) continue                          // isteresi: brevi interruzioni = stesso allarme
            it.remove()
            val dur = Math.max(0, Math.floor(a.seen - a.t0).toInt())
            val how = if (a.lvl == SLAlarmTechnical) "" else if (a.muteAfter != null) ", silenziato dopo ${a.muteAfter} s" else ", senza silenziare"
            val d = addEntry("alarm", "Allarme cessato: $m (durata ${mmss(dur)}$how)" + (if (r == null) " — monitor spento" else ""), "alarm_off", null) ?: continue
            d["lvl"] = a.lvl.toLong(); d["alarm"] = m; d["dur_s"] = dur.toLong(); d["a_t0"] = Math.floor(a.t0).toLong()
            if (a.muteAfter != null) d["mute_s"] = a.muteAfter!!.toLong()
        }
    }

    // Un comando dell'istruttore è stato eseguito: lo traduce in una voce leggibile (se merita di stare nel registro).
    fun noteCommand(command: Map<String, Any?>, by: String?) {
        val c = command
        val n = c["command"]; val v = c["value"]
        if (n !is String) return
        syncStart(true)                          // ANDROID (avvio scenario 22/11): start_scenario (o «riprendi» in PRONTO) apre subito la sessione
        val names = mapOf("cvp_mmhg" to "PVC", "heart_rate_bpm" to "FC", "spo2_percent" to "SpO₂", "systolic_mmhg" to "PAs", "diastolic_mmhg" to "PAd", "respiratory_rate_rpm" to "FR", "etco2_mmhg" to "EtCO₂")
        var text: String? = null; var kind = "patient"
        if (n == "transition" && v is Map<*, *>) {
            val a = ArrayList<String>()
            val pr: Map<*, *> = (v["params"] as? Map<*, *>) ?: emptyMap<String, Any?>()
            for (k in listOf("heart_rate_bpm", "systolic_mmhg", "diastolic_mmhg", "spo2_percent", "respiratory_rate_rpm", "etco2_mmhg", "cvp_mmhg")) if (pr[k] != null) a.add(String.format(Locale.US, "%s %s", names[k], desc(pr[k])))
            val d = integerValue(v["duration_s"])
            if (a.isNotEmpty()) text = String.format(Locale.US, "Parametri: %s (%s)", a.joinToString(", "), if (d > 0) String.format(Locale.US, "in %d s", d) else "subito")
        }
        else if (n == "set_heart_rate") text = String.format(Locale.US, "Parametri: FC %s", desc(v))
        else if (n == "set_spo2") text = String.format(Locale.US, "Parametri: SpO₂ %s", desc(v))
        else if (n == "set_resp_rate") text = String.format(Locale.US, "Parametri: FR %s", desc(v))
        else if (n == "set_etco2") text = String.format(Locale.US, "Parametri: EtCO₂ %s", desc(v))
        else if ((n == "set_bp" || n == "set_nibp") && v is Map<*, *>) text = String.format(Locale.US, "Parametri: PA %s/%s", desc(v["systolic"]), desc(v["diastolic"]))
        else if (n == "set_temperature") text = String.format(Locale.US, "Parametri: temperatura %s °C", desc(v))
        else if (n == "set_temperature2") text = String.format(Locale.US, "Parametri: temperatura centrale %s °C", desc(v))   // ANDROID (IN-13)
        else if (n == "set_cvp") text = String.format(Locale.US, "Parametri: PVC %s", if (v is Map<*, *>) desc(v["mmhg"] ?: v["waveform"]) else desc(v))
        else if (n == "ventilate") {        // le insufflazioni si raggruppano: una voce ogni ciclo, non ogni tocco
            val now = System.currentTimeMillis() / 1000.0
            if (now - ventLoggedAt < 20) return
            ventLoggedAt = now; text = "Ventilazioni (CO₂ a monitor)"; kind = "action"
        }
        else if (n == "assess_crt") { text = "Refill valutato su richiesta (fatto misurare dall'istruttore)"; kind = "action" }
        else if (n == "set_crt") text = String.format(Locale.US, "Parametri: refill %s s", desc(v))
        else if (n == "reset_clock") { text = "Timer di scenario azzerato"; kind = "timer" }
        else if (n == "end_scenario") {          // ANDROID (fine scenario 22/11): orario e timer dello scenario
            val ts = Math.max(0, Math.floor(state.snapshot().scenarioTime).toInt())
            add("phase", String.format(Locale.US, "Fine scenario · ore %s · timer %02d:%02d", synchronized(f) { f.format(Date(state.wallNowMs())) }, ts / 60, ts % 60), "scenario_end", by)
            return
        }
        else if (n == "reset_scenario") { newSession(null); synchronized(this) { prev = null; startSeqSeen = state.scenarioStartSeq; stSeq = state.stateEventSeq(); active.clear() }; return }      // azzeramento = sessione nuova; i cambi provocati dall'azzeramento stesso (ritmo, polso) non sono voci
        else if (n == "defib_outcome") { text = String.format(Locale.US, "Esito della scarica deciso dall'istruttore: %s", if (isEqual(v, "stay")) "nessun cambiamento" else RName(v)); kind = "electric" }
        if (text != null) add(kind, text, null, by)
    }

    // MARK: - Riepilogo per il debriefing

    fun summary(): Map<String, Any?> { return summaryForSession(currentSession()) }
    fun summaryForSession(sid: Int): Map<String, Any?> {
        synchronized(this) {
            val e = ArrayList<Map<String, Any?>>()          // solo le voci della sessione chiesta
            for (d in this.e) if (integerValue(d["s"]) == sid) e.add(d)
            val first = LinkedHashMap<String, Any?>()     // tag -> tempo della prima voce dopo l'ultimo azzeramento
            val shocks = ArrayList<Map<String, Any?>>(); val drugs = ArrayList<Map<String, Any?>>()
            var plus = 0; var minus = 0; var last = 0
            for (d in e) {
                val tag = d["tag"] as? String
                if (tag != null && first[tag] == null) first[tag] = d["t"]
                if (tag == "shock") shocks.add(linkedMapOf<String, Any?>("t" to d["t"], "energy_j" to (d["energy_j"] ?: 0L)))
                if (d["k"] == "drug") drugs.add(linkedMapOf<String, Any?>("t" to d["t"], "text" to d["text"]))
                if (d["k"] == "plus") plus++
                if (d["k"] == "minus") minus++
                last = Math.max(last, integerValue(d["t"]))        // durata = tempo di scenario più alto raggiunto
            }
            val out = linkedMapOf<String, Any?>("entries" to e.size.toLong(), "duration_s" to last.toLong(), "shocks" to shocks, "drugs" to drugs, "plus" to plus.toLong(), "minus" to minus.toLong())
            out["alarms"] = alarmSummary(e)                    // ANDROID (funzioni mancanti 22/11, IN-06)
            val arrest = first["arrest"]
            if (arrest != null) {
                out["arrest_at_s"] = arrest
                // tempi delle azioni critiche contati dall'arresto
                for (m in listOf(listOf("ac", "to_recognition_s"), listOf("help", "to_help_s"), listOf("cpr_start", "to_cpr_s"), listOf("shock", "to_first_shock_s"), listOf("rosc", "to_rosc_s"))) {
                    var t: Any? = null
                    for (d in e) if (d["tag"] == m[0] && integerValue(d["t"]) >= integerValue(arrest)) { t = d["t"]; break }
                    if (t != null) out[m[1]] = (integerValue(t) - integerValue(arrest)).toLong()
                }
            }
            return out
        }
    }

    // ANDROID (IN-06): metriche degli allarmi della sessione per il debriefing (piano, Storia Sim, PDF)
    private fun alarmSummary(e: List<Map<String, Any?>>): Map<String, Any?> {
        var hi = 0; var me = 0; var te = 0; var firstHigh: Any? = null; var lim = 0; var dis = 0; var res = 0; var ack = 0
        val mutes = ArrayList<Int>(); val items = ArrayList<Map<String, Any?>>(); val open = LinkedHashMap<String, Map<String, Any?>>()
        var noMute = 0
        for (d in e) {
            when (d["tag"]) {
                "alarm_on" -> { when (integerValue(d["lvl"])) { 3 -> { hi++; if (firstHigh == null) firstHigh = d["t"] }; 2 -> me++; else -> te++ }; open[desc(d["alarm"])] = d }
                "alarm_off" -> {
                    val a = desc(d["alarm"]); open.remove(a)
                    if (items.size < 60) items.add(linkedMapOf("t" to (d["a_t0"] ?: d["t"]), "alarm" to a, "lvl" to d["lvl"], "dur_s" to d["dur_s"], "mute_s" to d["mute_s"]))
                    if (integerValue(d["lvl"]) >= 2 && d["mute_s"] == null && integerValue(d["dur_s"]) >= 30) noMute++
                }
                "alarm_mute" -> { val r = d["resp_s"]; if (r is Number) mutes.add(r.toInt()) }
                "alarm_limit" -> lim++
                "alarm_disabled" -> dis++
                "tech_resolved" -> res++
                "alarm_ack" -> ack++
            }
        }
        for ((a, d) in open) if (items.size < 60) items.add(linkedMapOf("t" to d["t"], "alarm" to a, "lvl" to d["lvl"], "dur_s" to null, "mute_s" to null, "active" to true))
        items.sortBy { integerValue(it["t"]) }
        return linkedMapOf("high" to hi.toLong(), "medium" to me.toLong(), "technical" to te.toLong(), "total" to (hi + me + te).toLong(),
            "first_high_at_s" to firstHigh, "silenced" to mutes.size.toLong(),
            "mean_silence_s" to (if (mutes.isNotEmpty()) Math.round(mutes.average()).toLong() else null),
            "max_silence_s" to (if (mutes.isNotEmpty()) mutes.max().toLong() else null),
            "unanswered" to noMute.toLong(), "still_active" to open.size.toLong(),
            "limits_changed" to lim.toLong(), "alarms_disabled" to dis.toLong(), "tech_resolved" to res.toLong(), "acknowledged" to ack.toLong(),
            "items" to items)
    }

    // registro completo in testo (per salvarlo o stamparlo)
    fun plainText(): String { return plainTextForSession(0) }
    fun plainTextForSession(sid: Int): String {
        synchronized(this) {
            val e = ArrayList<Map<String, Any?>>()
            for (d in this.e) if (sid == 0 || integerValue(d["s"]) == sid) e.add(d)
            var shownSession = -1
            val kn = mapOf("phase" to "SCENARIO", "patient" to "PAZIENTE", "monitor" to "MONITOR", "electric" to "TERAPIA ELETTRICA", "timer" to "TIMER", "action" to "AZIONE",
                           "drug" to "FARMACO", "plus" to "PUNTO DI FORZA", "minus" to "DA DISCUTERE", "note" to "NOTA", "alarm" to "ALLARME")
            val s = StringBuilder("similFranz — registro della sessione\n\n")
            for (d in e) {
                val t = integerValue(d["t"])
                if (integerValue(d["s"]) != shownSession) {
                    shownSession = integerValue(d["s"])
                    var title = ""; for (m in sessions) if (integerValue(m["id"]) == shownSession) title = desc(m["title"])
                    s.append(String.format(Locale.US, "\n=== Sessione %d · %s ===\n", shownSession, title))
                }
                s.append(String.format(Locale.US, "%02d:%02d  %s  %-17s %s%s\n", t / 60, t % 60, synchronized(f) { f.format(Date((doubleValue(d["w"]) * 1000).toLong())) },
                    kn[d["k"]] ?: "", desc(d["text"]), if (d["by"] != null) String.format(Locale.US, "  (%s)", desc(d["by"])) else ""))
            }
            return s.toString()
        }
    }

    companion object {
        private const val kMaxEntries = 3000
        private const val kMaxSessions = 20
        private val f = SimpleDateFormat("HH:mm:ss", Locale.US)

        fun kinds(): List<String> = listOf("phase", "patient", "monitor", "electric", "timer", "action", "drug", "plus", "minus", "note", "alarm")   // ANDROID (IN-06): + alarm

        private fun RName(r: Any?): String {
            return mapOf("sinus" to "sinusale", "sinus_tachycardia" to "tachicardia sinusale", "sinus_bradycardia" to "bradicardia sinusale",
                         "afib" to "fibrillazione atriale", "vt_pulse" to "TV con polso", "vt" to "TV senza polso", "vf" to "fibrillazione ventricolare",
                         "asystole" to "asistolia", "pea" to "PEA")[(r as? String) ?: ""] ?: (if (r != null) desc(r) else "?")
        }
        private fun SName(s: String): String {
            return mapOf("ecg" to "Elettrodi ECG", "spo2" to "Sonda SpO₂", "nibp" to "Bracciale NIBP", "art" to "Linea arteriosa", "etco2" to "Capnografia",
                         "temp" to "Sonda temperatura", "pads" to "Piastre", "oxi" to "Sensore del saturimetro",
                         "cvp" to "Trasduttore PVC", "temp2" to "Sonda temperatura centrale")[s] ?: s   // ANDROID (IN-13)
        }


        private fun mutableCopy(m: Map<*, *>): MutableMap<String, Any?> {
            val out = LinkedHashMap<String, Any?>()
            for ((k, v) in m) out[k.toString()] = v
            return out
        }
        // un dizionario, o vuoto se il valore è altro (sull'iPad: messaggio a nil)
        private fun dict(v: Any?): Map<String, Any?> {
            @Suppress("UNCHECKED_CAST")
            return if (v is Map<*, *>) v as Map<String, Any?> else emptyMap()
        }
        // -[NSNumber/NSString integerValue]: un numero (anche booleano) o un testo che comincia con un numero; altrimenti 0
        private fun integerValue(v: Any?): Int = when (v) {
            is Boolean -> if (v) 1 else 0
            is Number -> v.toDouble().toInt()
            is String -> Regex("^\\s*[+-]?\\d+").find(v)?.value?.trim()?.toDoubleOrNull()?.toInt() ?: 0
            else -> 0
        }
        private fun doubleValue(v: Any?): Double = when (v) {
            is Boolean -> if (v) 1.0 else 0.0
            is Number -> v.toDouble()
            is String -> v.trim().toDoubleOrNull() ?: 0.0
            else -> 0.0
        }
        // -boolValue
        private fun boolValue(v: Any?): Boolean = when (v) {
            is Boolean -> v
            is Number -> v.toDouble() != 0.0
            is String -> Regex("^\\s*[+-]?0*[YyTt1-9]").containsMatchIn(v)
            else -> false
        }
        // -isEqual: (un messaggio a nil vale false)
        private fun isEqual(a: Any?, b: Any?): Boolean {
            if (a == null) return false
            if (a is Number && b is Number && a !is Boolean && b !is Boolean) return a.toDouble() == b.toDouble()
            return a == b
        }
        // come "%@": i numeri interi senza decimali, nil come "(null)"
        private fun desc(v: Any?): String = when (v) {
            null -> "(null)"
            is Boolean -> if (v) "1" else "0"
            is Double -> if (v == Math.rint(v) && Math.abs(v) < 1e15) v.toLong().toString() else v.toString()
            is Float -> desc(v.toDouble())
            else -> v.toString()
        }
    }
}
