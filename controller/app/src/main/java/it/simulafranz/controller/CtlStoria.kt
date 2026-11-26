// ANDROID (storia sim 22/11): nessun file corrispondente sull'iPad. Contratto: STORIA_SIM.md.
// similFranz Controller — «Storia Sim»: bozza del debriefing (titolo e squadra, tenuta finché non si salva o si scarta,
// anche a scenario in corso), simulazioni salvate (copia locale filesDir/storia/<id>.json + archivio del monitor
// /api/v1/archive) e PDF in /sdcard/Documents/Storia Sim/ (CtlStoriaPdf.kt), aperti e condivisi da StoriaSimActivity.
package it.simulafranz.controller

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Environment
import it.simulafranz.core.Json
import it.simulafranz.core.SFLog
import it.simulafranz.uk.Uk
import java.io.File
import java.security.SecureRandom
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// ANDROID (storia sim 22/11, richiesta utente): solo tre ruoli, niente «Altro…»
val STORIA_ROLES = listOf("Medico", "Infermiere", "OTAS")

/** cartella dei PDF (la stessa che apre l'icona «Storia Sim» sulla schermata Home) */
fun StoriaPdfDir(): File = File(Environment.getExternalStorageDirectory(), "Documents/Storia Sim")

const val kStoragePermRequest = 73
private const val kDraftKey = "sf_storia_draft"
private const val kDelKey = "sf_storia_del"          // eliminate qui ma non ancora sul monitor

private fun sInt(v: Any?): Int = when (v) { is Number -> v.toInt(); is String -> v.trim().toIntOrNull() ?: 0; else -> 0 }

class CtlStoria(val m: CtlModel) {
    companion object {
        private val permWaiters = ArrayList<(Boolean) -> Unit>()
        /** da MainActivity / StoriaSimActivity: risposta alla richiesta del permesso di scrittura */
        fun permissionResult(ok: Boolean) { val w = ArrayList(permWaiters); permWaiters.clear(); for (f in w) f(ok) }
        // ANDROID (fix analisi 22/11, BUG-05): visore/cartella Storia Sim (attività nostra) aperti sopra la regia: MainActivity.onStop
        // non deve sospendere il controller (rilascio del comando, collegamento chiuso). viewerGone: il visore si è chiuso senza
        // tornare alla regia (tasto Home, schermo spento) → lì sì si sospende.
        var viewerOpen = 0
        var viewerGone: (() -> Unit)? = null
        fun canWrite(): Boolean = Uk.ctx.checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
        fun askWrite(then: (Boolean) -> Unit) {
            if (canWrite()) { then(true); return }
            permWaiters.add(then)
            if (permWaiters.size == 1) (Uk.ctx as? Activity)?.requestPermissions(arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE, Manifest.permission.READ_EXTERNAL_STORAGE), kStoragePermRequest)
                ?: permissionResult(false)
        }
    }

    // MARK: - Bozza (titolo + squadra)

    /** titolo scritto dall'istruttore; null = proposta automatica */
    var draftTitle: String? = null
    val team = ArrayList<LinkedHashMap<String, Any?>>()
    /** nome dello scenario a fasi visto attivo e ultimo stato attivato (per la proposta del titolo) */
    var scenarioName: String? = null; private set
    var stateName: String? = null; private set
    /** cresce a ogni cambiamento delle simulazioni salvate (per ricostruire l'elenco solo se serve) */
    var rev = 0; private set
    var status: String? = null; private set               // esito dell'ultima sincronizzazione con il monitor (riga discreta)

    init {
        @Suppress("UNCHECKED_CAST")
        (m.prefs.json(kDraftKey) as? Map<String, Any?>)?.let { d ->
            draftTitle = d["title"] as? String
            scenarioName = d["scenario"] as? String; stateName = d["state"] as? String
            for (p in (d["team"] as? List<*>) ?: emptyList<Any?>()) if (p is Map<*, *>) team.add(LinkedHashMap(p.entries.associate { it.key.toString() to it.value }))
        }
    }

    fun saveDraft() {
        m.prefs.setJson(kDraftKey, linkedMapOf("title" to draftTitle, "scenario" to scenarioName, "state" to stateName, "team" to ArrayList(team)))
    }
    fun clearDraft() {
        draftTitle = null; team.clear(); scenarioName = null; stateName = null
        m.prefs.setString(kDraftKey, null)
    }
    fun autoTitle(): String = scenarioName ?: stateName ?: ("Simulazione del " + SimpleDateFormat("dd/MM/yyyy", Locale.ITALY).format(Date()))
    fun title(): String = draftTitle?.trim()?.takeIf { it.isNotEmpty() } ?: autoTitle()
    /** chiamato a ogni aggiornamento: ricorda il nome dello scenario a fasi in corso */
    fun noteScenario(sc: Map<String, Any?>?) {
        if (sc?.get("active") != true) return
        val n = (sc["name"] as? String)?.trim()
        if (!n.isNullOrEmpty() && n != scenarioName) { scenarioName = n; saveDraft() }
    }
    fun noteState(n: String?) { if (!n.isNullOrEmpty() && n != stateName) { stateName = n; saveDraft() } }
    fun addMember() { team.add(linkedMapOf("name" to "", "role" to "", "achieved" to "", "reinforce" to "")); saveDraft() }
    fun removeMember(i: Int) { if (i in team.indices) { team.removeAt(i); saveDraft() } }
    fun setMember(i: Int, key: String, v: String) { if (i in team.indices) { team[i][key] = v; saveDraft() } }

    // MARK: - Record

    private val localDir: File get() = File(Uk.ctx.filesDir, "storia").also { it.mkdirs() }

    @Suppress("UNCHECKED_CAST")
    fun buildRecord(): LinkedHashMap<String, Any?> {
        val now = Date()
        val r = SecureRandom()
        val id = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(now) + String.format(Locale.US, "-%04x", r.nextInt(0x10000))
        // ANDROID (debriefing scollegato 22/11): dopo FINE SCENARIO tutto viene dall'istantanea di fine (anche senza monitor)
        val snap = m.endSnap
        val summary = (if (snap != null) snap["summary"] as? Map<String, Any?> else m.logSummary) ?: emptyMap()
        val e = if (snap != null) snap["end"] as? Map<String, Any?> else m.endMark?.takeIf { sInt(it["session"]) == m.logSession }
        val entries = if (snap != null) ((snap["entries"] as? List<Map<String, Any?>>) ?: emptyList())
                      else m.log.map { d -> LinkedHashMap(d).apply { this["wall"] = m.wallOf(d["w"]) } }
        val dur = (summary["duration_s"] as? Number)?.toInt() ?: e?.let { sInt(it["t"]) } ?: sInt(snap?.get("scenario_time_s") ?: m.state?.get("scenario_time_s"))
        val members = team.filter { p -> listOf("name", "role", "achieved", "reinforce").any { ((p[it] as? String) ?: "").isNotBlank() } }
            .map { p -> linkedMapOf<String, Any?>("name" to ((p["name"] as? String) ?: "").trim(), "role" to ((p["role"] as? String) ?: "").trim(),
                                                   "achieved" to ((p["achieved"] as? String) ?: "").trim(), "reinforce" to ((p["reinforce"] as? String) ?: "").trim()) }
        return linkedMapOf(
            "id" to id, "v" to 1, "saved_w" to now.time / 1000,
            "date_text" to SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.ITALY).format(now),
            "title" to title(), "source" to "controller", "saved_by" to CtlIdentity.controllerName(),
            "monitor" to (snap?.get("monitor") ?: m.monitorName), "layout" to (if (snap != null) snap["layout"] else m.state?.get("layout")),
            "layout_name" to (if (snap != null) snap["layout_name"] else layoutName()), "duration_s" to dur,
            "team" to members, "summary" to summary, "cpr" to ((if (snap != null) snap["cpr"] else m.state?.get("cpr_metrics")) as? Map<String, Any?>),
            "end" to e?.let { linkedMapOf("t" to it["t"], "w" to it["w"]) }, "entries" to entries,
            "trend" to ((if (snap != null) snap["trend"] else null) ?: m.trendCache))      // ANDROID (funzioni mancanti 22/11, IN-14): passo 60 s
    }

    /** nome leggibile del layout attivo (dal catalogo del monitor; campo in più rispetto al contratto, solo per il PDF) */
    private fun layoutName(): String? {
        val lid = m.state?.get("layout") ?: return null
        for (l in (m.state?.get("layouts") as? List<*>) ?: emptyList<Any?>()) if (l is Map<*, *> && l["id"] == lid) return l["name"]?.toString()
        return lid.toString()
    }
    private fun fileOf(id: String) = File(localDir, id.replace(Regex("[^A-Za-z0-9_-]"), "_") + ".json")
    private fun localMeta(rec: Map<String, Any?>): Map<*, *> = (rec["_local"] as? Map<*, *>) ?: emptyMap<String, Any?>()
    private fun clean(rec: Map<String, Any?>): Map<String, Any?> = rec.filterKeys { it != "_local" }
    private fun write(rec: Map<String, Any?>, unsent: Boolean, pdf: String?) {
        val out = LinkedHashMap(clean(rec)); out["_local"] = linkedMapOf("unsent" to unsent, "pdf" to pdf)
        try { fileOf(rec["id"] as String).writeText(Json.stringify(out), Charsets.UTF_8) } catch (x: Exception) { SFLog.log("storia: non scritto: $x") }
        cache = null; rev++
    }
    @Suppress("UNCHECKED_CAST")
    fun load(id: String): Map<String, Any?>? = try { Json.parseOrNull(fileOf(id).readText(Charsets.UTF_8)) as? Map<String, Any?> } catch (x: Exception) { null }

    private var cache: List<Map<String, Any?>>? = null
    /** simulazioni salvate in questo tablet, più recenti prima (record completi, con _local) */
    @Suppress("UNCHECKED_CAST")
    fun list(): List<Map<String, Any?>> {
        cache?.let { return it }
        val out = ArrayList<Map<String, Any?>>()
        for (f in localDir.listFiles() ?: emptyArray()) if (f.name.endsWith(".json"))
            (try { Json.parseOrNull(f.readText(Charsets.UTF_8)) as? Map<String, Any?> } catch (x: Exception) { null })?.let { if (it["id"] is String) out.add(it) }
        out.sortByDescending { (it["saved_w"] as? Number)?.toDouble() ?: 0.0 }
        cache = out; return out
    }
    val unsentCount: Int get() = list().count { localMeta(it)["unsent"] == true }

    // MARK: - PDF

    fun pdfName(rec: Map<String, Any?>): String {
        val w = ((rec["saved_w"] as? Number)?.toLong() ?: 0L) * 1000
        var t = ((rec["title"] as? String) ?: "Simulazione").replace(Regex("[\\\\/:*?\"<>|\\n\\r\\t]"), "-").trim().trimEnd('.')
        if (t.length > 60) t = t.substring(0, 60).trim()
        return SimpleDateFormat("yyyy-MM-dd HH.mm", Locale.US).format(Date(w)) + " " + t + ".pdf"
    }
    /** il PDF di una simulazione salvata (null se non c'è ancora) */
    fun pdfFile(rec: Map<String, Any?>): File? {
        val n = localMeta(rec)["pdf"] as? String ?: return null
        val f = File(StoriaPdfDir(), n); return if (f.exists()) f else null
    }
    /** scrive il PDF (in un thread a parte); done(file o null) sul main thread */
    fun makePdf(rec: Map<String, Any?>, done: ((File?) -> Unit)?) {
        askWrite { ok ->
            if (!ok) { status = "PDF non creato: manca il permesso di scrivere nella memoria del tablet"; rev++; done?.invoke(null); return@askWrite }
            val r = clean(rec)
            Thread {
                var f: File? = null
                try {
                    val dir = StoriaPdfDir(); dir.mkdirs()
                    val old = localMeta(rec)["pdf"] as? String
                    var name = pdfName(r)
                    if (name != old && File(dir, name).exists()) name = name.removeSuffix(".pdf") + " (" + (r["id"] as String).takeLast(4) + ").pdf"
                    val tmp = File(dir, ".$name.tmp")
                    CtlStoriaPdf.write(r, tmp)
                    val dst = File(dir, name); if (dst.exists()) dst.delete()
                    if (!tmp.renameTo(dst)) { tmp.copyTo(dst, true); tmp.delete() }
                    if (old != null && old != name) File(dir, old).delete()
                    f = dst
                } catch (x: Throwable) { SFLog.log("storia: PDF non creato: $x") }
                Uk.async {
                    val cur = load(r["id"] as String)
                    if (cur != null && f != null) write(cur, localMeta(cur)["unsent"] == true, f!!.name)
                    if (f == null) status = "PDF non creato (memoria del tablet piena o non scrivibile?)"
                    // avvisa il sistema dei file (così compare subito anche nelle app dei file e via USB)
                    f?.let { try { Uk.ctx.sendBroadcast(Intent(Intent.ACTION_MEDIA_SCANNER_SCAN_FILE, android.net.Uri.fromFile(it))) } catch (x: Exception) {} }
                    done?.invoke(f)
                }
            }.start()
        }
    }

    // MARK: - Salvataggio, eliminazione, monitor

    /** salva in locale, manda al monitor (se non arriva resta «da inviare»), poi il PDF. done(record salvato) sul main thread */
    fun save(rec: Map<String, Any?>, done: ((Map<String, Any?>) -> Unit)?) {
        write(rec, true, null)
        SFLog.log("storia: salvata «${rec["title"]}» (${rec["id"]})")
        send(rec, false)
        makePdf(rec) { _ -> done?.invoke(load(rec["id"] as String) ?: rec) }
    }
    // ANDROID (debriefing scollegato 22/11): retry = invio rimasto in sospeso (sync al ricollegamento): se arriva lo si dice
    private val sending = HashSet<String>()
    private fun send(rec: Map<String, Any?>, retry: Boolean) {
        val id0 = rec["id"] as String
        if (!sending.add(id0)) return
        m.api("POST", "/api/v1/archive", clean(rec)) { st, b ->
            val id = rec["id"] as String
            sending.remove(id)
            if (st == 200 && b?.get("ok") == true) {
                load(id)?.let { cur -> write(cur, false, localMeta(cur)["pdf"] as? String) }; status = null
                if (retry) m.notice(String.format(Locale.ITALY, "Storia Sim: «%s» inviata al monitor", rec["title"] ?: "simulazione"))
            }
            else { status = if (st == 404) "Il monitor non ha ancora la Storia Sim (aggiorna similFranz Monitor): le simulazioni restano su questo tablet"
                            else if (st == 0) "Monitor non raggiungibile: le simulazioni «da inviare» partono da sole appena torna raggiungibile" else "Monitor: " + ((b?.get("error") as? String) ?: "salvataggio non riuscito ($st)") }
            rev++
        }
    }

    private fun pendingDeletes(): MutableList<String> = ((m.prefs.json(kDelKey) as? List<*>) ?: emptyList<Any?>()).mapNotNull { it as? String }.toMutableList()
    private fun setPendingDeletes(l: List<String>) = m.prefs.setJson(kDelKey, if (l.isEmpty()) null else l)

    /** elimina la copia locale, il PDF e quella sul monitor (se il monitor non risponde, si riprova alla sincronizzazione) */
    fun delete(id: String) {
        load(id)?.let { rec -> pdfFile(rec)?.delete() }
        fileOf(id).delete(); cache = null; rev++
        val pd = pendingDeletes(); if (!pd.contains(id)) { pd.add(id); setPendingDeletes(pd) }
        sendDeletes()
    }
    private fun sendDeletes() {
        for (id in pendingDeletes()) m.api("POST", "/api/v1/archive", mapOf("delete" to id)) { st, _ ->
            if (st == 200 || st == 404) { val pd = pendingDeletes(); pd.remove(id); setPendingDeletes(pd) }
        }
    }

    private var syncing = false; private var lastSyncAt = 0L
    /** all'apertura di Storia Sim (e ogni tanto): eliminazioni e invii rimasti in sospeso, poi le simulazioni solo sul monitor */
    @Suppress("UNCHECKED_CAST")
    fun sync(force: Boolean = false) {
        if (syncing || m.stage != CtlStageReady) return
        if (!force && System.currentTimeMillis() - lastSyncAt < 30_000) return
        syncing = true; lastSyncAt = System.currentTimeMillis()
        sendDeletes()
        for (r in list()) if (localMeta(r)["unsent"] == true) send(r, true)
        // PDF mancanti (permesso dato dopo, o scaricate prima del permesso)
        if (canWrite()) for (r in list()) if (pdfFile(r) == null) makePdf(r, null)
        m.api("GET", "/api/v1/archive", null) { st, b ->
            syncing = false
            if (st == 404) { status = "Il monitor non ha ancora la Storia Sim (aggiorna similFranz Monitor)"; rev++; return@api }
            if (st != 200 || b == null) return@api
            val have = list().map { it["id"] }.toSet(); val del = pendingDeletes().toSet()
            for (it0 in (b["items"] as? List<*>) ?: emptyList<Any?>()) {
                val id = (it0 as? Map<*, *>)?.get("id") as? String ?: continue
                if (id in have || id in del) continue
                m.api("GET", "/api/v1/archive?id=" + java.net.URLEncoder.encode(id, "UTF-8"), null) { s2, b2 ->
                    val rec = b2?.get("item") as? Map<String, Any?>
                    if (s2 == 200 && rec != null && rec["id"] == id && load(id) == null) {
                        write(rec, false, null); SFLog.log("storia: scaricata dal monitor «${rec["title"]}»")
                        if (canWrite()) makePdf(rec, null)
                    }
                }
            }
        }
    }
}
