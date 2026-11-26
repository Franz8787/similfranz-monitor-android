// ANDROID (storia sim 22/11): file nuovo, non c'è sull'iPad (vedi STORIA_SIM.md).
// similFranz Monitor — «Storia Sim»: archivio delle simulazioni salvate dal debriefing (controller o web regia).
//   - Un file JSON per simulazione: <dir>/archivio/<id>.json (accanto a registro.json).
//   - Scrittura atomica (file temporaneo nella stessa cartella, poi rinomina): un'interruzione non lascia file a metà.
//   - Al massimo kArchiveMax simulazioni: oltre, si toglie la più vecchia (saved_w più piccolo).
//   - L'id è controllato (solo lettere, cifre e «-», al massimo 64): nessun accesso fuori dalla cartella.
//   - Elenco tenuto in memoria (riassunto di ogni simulazione), letto dai file la prima volta che serve.
// Kotlin puro: testabile sul Mac.
package it.simulafranz.core

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

const val kArchiveMax = 500
const val kArchiveMaxBytes = 2 * 1024 * 1024

// esito di save: id salvato oppure errore
data class SFArchiveSave(val id: String?, val error: String?)

// dir: cartella del registro (la stessa di SFSessionLog); null = solo in memoria (test)
class SFArchive(dir: File?) {
    val folder: File? = if (dir != null) File(dir, "archivio") else null
    private var index: LinkedHashMap<String, Map<String, Any?>>? = null     // id -> riassunto
    private val memory = HashMap<String, Map<String, Any?>>()               // senza cartella: record completi in memoria
    var max: Int = kArchiveMax

    companion object {
        private val kIdRegex = Regex("[0-9A-Za-z-]{1,64}")
        fun validId(id: Any?): Boolean = id is String && kIdRegex.matches(id)

        private fun longValue(v: Any?): Long = when (v) {
            is Boolean -> if (v) 1 else 0
            is Number -> v.toLong()
            is String -> v.trim().toDoubleOrNull()?.toLong() ?: 0
            else -> 0
        }
        private fun str(v: Any?): String = v as? String ?: ""

        // riassunto per l'elenco: {id,title,date_text,saved_w,duration_s,team_n,source,saved_by}
        fun summaryOf(r: Map<String, Any?>): Map<String, Any?> = linkedMapOf(
            "id" to r["id"], "title" to str(r["title"]), "date_text" to str(r["date_text"]),
            "saved_w" to longValue(r["saved_w"]), "duration_s" to longValue(r["duration_s"]),
            "team_n" to ((r["team"] as? List<*>)?.size ?: 0).toLong(),
            "source" to str(r["source"]), "saved_by" to str(r["saved_by"]))
    }

    private fun fileFor(id: String): File? = folder?.let { File(it, "$id.json") }

    @Suppress("UNCHECKED_CAST")
    private fun readFile(f: File): Map<String, Any?>? = try {
        Json.parseOrNull(String(f.readBytes(), Charsets.UTF_8)) as? Map<String, Any?>
    } catch (e: Throwable) { null }

    // carica l'elenco dai file (una volta sola). Da chiamare col lucchetto.
    private fun loadIndex(): LinkedHashMap<String, Map<String, Any?>> {
        index?.let { return it }
        val idx = LinkedHashMap<String, Map<String, Any?>>()
        val fs = folder?.listFiles() ?: emptyArray()
        for (f in fs) {
            val n = f.name
            if (!n.endsWith(".json")) continue
            val id = n.substring(0, n.length - 5)
            if (!validId(id)) continue
            val r = readFile(f) ?: continue
            idx[id] = summaryOf(r + ("id" to id))
        }
        index = idx
        return idx
    }

    /** elenco dei riassunti, i più recenti prima */
    fun items(): List<Map<String, Any?>> {
        synchronized(this) {
            return loadIndex().values.sortedWith(compareByDescending<Map<String, Any?>> { longValue(it["saved_w"]) }
                .thenByDescending { str(it["id"]) })
        }
    }

    fun count(): Int { synchronized(this) { return loadIndex().size } }

    /** record completo, oppure null se non c'è (o l'id non è valido) */
    fun item(id: String?): Map<String, Any?>? {
        if (!validId(id)) return null
        synchronized(this) {
            if (!loadIndex().containsKey(id!!)) return null
            if (folder == null) return memory[id]
            val f = fileFor(id) ?: return null
            return readFile(f)
        }
    }

    /** salva (stesso id = sostituisce); oltre max si toglie la più vecchia (mai quella appena salvata) */
    fun save(record: Map<String, Any?>?): SFArchiveSave {
        if (record == null) return SFArchiveSave(null, "body must be a valid JSON object")
        val id = record["id"]
        if (!validId(id)) return SFArchiveSave(null, "archive: id must be 1-64 characters [0-9a-zA-Z-]")
        id as String
        val data = Json.stringify(record).toByteArray(Charsets.UTF_8)
        if (data.size > kArchiveMaxBytes) return SFArchiveSave(null, "payload too large")
        synchronized(this) {
            val idx = loadIndex()
            val dirF = folder
            if (dirF == null) memory[id] = record
            else {
                try {
                    dirF.mkdirs()
                    val tmp = File.createTempFile("$id.", ".tmp", dirF)
                    try {
                        tmp.writeBytes(data)
                        Files.move(tmp.toPath(), File(dirF, "$id.json").toPath(), StandardCopyOption.REPLACE_EXISTING)     // stessa cartella: rinomina atomica
                    } finally { if (tmp.exists()) tmp.delete() }
                } catch (e: Exception) {
                    return SFArchiveSave(null, "archive: write failed")
                }
            }
            idx.remove(id)
            idx[id] = summaryOf(record)
            // tetto: si tolgono le più vecchie (escluso il record appena salvato)
            while (idx.size > max) {
                val oldest = idx.values.filter { it["id"] != id }
                    .minWithOrNull(compareBy<Map<String, Any?>> { longValue(it["saved_w"]) }.thenBy { str(it["id"]) }) ?: break
                removeLocked(str(oldest["id"]))
            }
            return SFArchiveSave(id, null)
        }
    }

    private fun removeLocked(id: String): Boolean {
        val had = loadIndex().remove(id) != null
        memory.remove(id)
        fileFor(id)?.delete()
        return had
    }

    /** toglie una simulazione; true se c'era */
    fun delete(id: String?): Boolean {
        if (!validId(id)) return false
        synchronized(this) { return removeLocked(id!!) }
    }
}
