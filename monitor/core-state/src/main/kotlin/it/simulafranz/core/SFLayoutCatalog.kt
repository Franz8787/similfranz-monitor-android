// ANDROID (categorie dei layout 22/11): catalogo UNICO dei layout, letto dai menu del monitor, dai controller e dalla
// regia web (campo "layouts" dello stato). Ogni voce: id, name (nome mostrato), category, kind.
//  - category: "default" (i layout originali di similFranz) o "custom" (i layout personalizzati, in src/custom; nella
//    build pubblica -PsfPublic=true l'elenco è vuoto). Nomi mostrati: «Default» e «Personalizzati».
//  - kind: il tipo di dispositivo, che decide il posto nell'elenco (stesso ordine logico in entrambe le categorie):
//    icu (terapia intensiva) → ward (reparto) → defib (monitor-defibrillatore da emergenza/trasporto) → aed (DAE).
//    A parità di tipo resta l'ordine in cui le voci sono scritte. Un tipo sconosciuto va in fondo.
// Chi legge l'elenco senza "category" (monitor vecchi) mette tutto in «Default».
package it.simulafranz.core

const val SFLayoutCategoryDefault = "default"
const val SFLayoutCategoryCustom = "custom"
const val SFLayoutKindIcu = "icu"
const val SFLayoutKindWard = "ward"
const val SFLayoutKindDefib = "defib"
const val SFLayoutKindAed = "aed"

/** Categorie nell'ordine dei menu, con il nome mostrato. */
val SFLayoutCategories: List<Pair<String, String>> = listOf(SFLayoutCategoryDefault to "Default", SFLayoutCategoryCustom to "Personalizzati")
/** Testo dei menu quando «Personalizzati» non ha voci. */
const val SFLayoutCustomEmptyText = "Nessun layout personalizzato: vedi la guida per crearne uno"
private val SFLayoutKindOrder = listOf(SFLayoutKindIcu, SFLayoutKindWard, SFLayoutKindDefib, SFLayoutKindAed)

fun SFLayoutEntry(id: String, name: String, category: String, kind: String): Map<String, String> =
    linkedMapOf("id" to id, "name" to name, "category" to category, "kind" to kind)

/** I layout originali di similFranz (versione pubblica compresa). */
private fun SFDefaultLayouts(): List<Map<String, String>> = listOf(
    SFLayoutEntry("generic_icu", "ICU", SFLayoutCategoryDefault, SFLayoutKindIcu),
    SFLayoutEntry("extrah", "extraH", SFLayoutCategoryDefault, SFLayoutKindDefib),
    SFLayoutEntry("dae", "DAE", SFLayoutCategoryDefault, SFLayoutKindAed),
)

/** Catalogo completo: prima «Default», poi «Personalizzati»; dentro ciascuna, per tipo (ordinamento stabile). */
fun SFLayoutCatalogAll(): List<Map<String, String>> {
    fun rank(m: Map<String, String>): Int { val i = SFLayoutKindOrder.indexOf(m["kind"]); return if (i < 0) SFLayoutKindOrder.size else i }
    val def = SFDefaultLayouts()
    val ids = def.map { it["id"] }.toHashSet()
    val cus = SFCustomLayoutCatalog.entries().filter { ids.add(it["id"]) }          // un id già usato non si duplica
        .map { if (it["category"] == SFLayoutCategoryCustom) it else LinkedHashMap(it).also { c -> c["category"] = SFLayoutCategoryCustom } }
    return def.sortedBy { rank(it) } + cus.sortedBy { rank(it) }
}

// dedicati (spenti al cambio di layout, voce dei malfunzionamenti all'accensione, bip da spento), senza elencarli a mano.
val SFLayoutAedIds: Set<String> by lazy { SFLayoutCatalogAll().filter { it["kind"] == SFLayoutKindAed }.mapNotNull { it["id"] }.toSet() }
fun SFLayoutIsAed(id: String?): Boolean = id != null && SFLayoutAedIds.contains(id)
/** ANDROID (22/11): monitor da letto senza defibrillatore (kind icu/ward dei Personalizzati): nessun malfunzionamento del
 *  defibrillatore. L'ICU generico resta a parte (ha il cassetto DEFIB). Letto dal catalogo, senza nomi di layout nel codice comune. */
fun SFLayoutIsBedsideNoDefib(id: String?): Boolean = id != null && id != "generic_icu" &&
    SFLayoutCatalogAll().any { it["id"] == id && it["category"] == "custom" && (it["kind"] == "icu" || it["kind"] == "ward") }
