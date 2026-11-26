// ANDROID (12 derivazioni 22/11) — file nuovo, solo Android (sull'iPad non c'è).
// SimulaFranz_Monitor — MORFOLOGIA dell'ECG (separata dal ritmo): alterazioni del tratto ST, blocchi di branca, blocchi AV,
// altre alterazioni utili in simulazione sanitaria d'emergenza. Qui solo lo STATO (impostazioni, validazione, testi in
// italiano); la forma per derivazione (12 derivazioni) la calcola il motore delle curve (core-engine, SFEcg12.kt) e i blocchi AV
// li genera il motore come veri disturbi di conduzione (onde P, PR, battiti non condotti, scappamento).
// Ricerca, morfologie per derivazione e fonti: ECG_12D.md.
//
// Comando (retrocompatibile, campi tutti facoltativi; quelli non indicati restano come sono):
//   {"command":"set_ecg_morphology","value":{"st":"stemi_inferior","st_mm":3,"bbb":"none","av_block":"first","pr_ms":280,
//     "escape":"ventricular","extra":["hyperkalemia"],"interpretation":true}}
//   {"command":"set_ecg_morphology","value":"normal"}  -> tutto normale (anche "none")
// Anche dentro una transizione / fase di scenario: {"command":"transition","value":{..., "ecg_morphology":{...}}}.
package it.simulafranz.core

/** Valori ammessi (nomi dell'API) */
val SLEcgStValues = listOf("none", "stemi_inferior", "stemi_inferoposterior", "stemi_anterior", "stemi_lateral", "stemi_posterior",
    "nstemi_depression", "left_main", "wellens", "de_winter", "pericarditis")
val SLEcgBbbValues = listOf("none", "rbbb", "lbbb", "lafb", "rbbb_lafb")
val SLEcgAvValues = listOf("none", "first", "mobitz1", "mobitz2", "2to1", "high_grade", "third")
val SLEcgExtraValues = listOf("hyperkalemia", "hyperkalemia_severe", "hypokalemia", "long_qt", "lvh", "wpw", "brugada", "pe",
    "hypothermia", "low_voltage")
val SLEcgEscapeValues = listOf("ventricular", "junctional")

private val SLEcgStNames = mapOf(
    "stemi_inferior" to "STEMI inferiore", "stemi_inferoposterior" to "STEMI infero-posteriore", "stemi_anterior" to "STEMI anteriore",
    "stemi_lateral" to "STEMI laterale", "stemi_posterior" to "STEMI posteriore isolato", "nstemi_depression" to "sottoslivellamento ST (NSTEMI / ischemia)",
    "left_main" to "sottoslivellamento ST diffuso con sopraslivellamento in aVR (tronco comune / multivasale)",
    "wellens" to "sindrome di Wellens (T negative profonde V2-V3)", "de_winter" to "pattern di de Winter (equivalente di STEMI anteriore)",
    "pericarditis" to "pericardite acuta")
private val SLEcgBbbNames = mapOf("rbbb" to "blocco di branca destro", "lbbb" to "blocco di branca sinistro",
    "lafb" to "emiblocco anteriore sinistro", "rbbb_lafb" to "blocco bifascicolare (BBD + EAS)")
private val SLEcgAvNames = mapOf("first" to "BAV 1° grado", "mobitz1" to "BAV 2° grado Mobitz I (Wenckebach)",
    "mobitz2" to "BAV 2° grado Mobitz II", "2to1" to "BAV 2° grado 2:1", "high_grade" to "BAV avanzato (3:1)", "third" to "BAV 3° grado (completo)")
private val SLEcgExtraNames = mapOf("hyperkalemia" to "iperkaliemia", "hyperkalemia_severe" to "iperkaliemia grave",
    "hypokalemia" to "ipokaliemia", "long_qt" to "QT lungo", "lvh" to "ipertrofia ventricolare sinistra", "wpw" to "preeccitazione (WPW)",
    "brugada" to "pattern di Brugada tipo 1", "pe" to "embolia polmonare (S1Q3T3, sovraccarico destro)", "hypothermia" to "ipotermia (onde J di Osborn)",
    "low_voltage" to "bassi voltaggi con alternanza elettrica (versamento pericardico)")

/** Impostazioni della morfologia ECG (immutabile: la condividono cuore, istantanee e motore). */
class SLEcgMorph(
    val st: String = "none",
    val stMm: Double = 0.0,              // ampiezza dello ST nella derivazione più alterata (mm); 0 = di serie del quadro
    val bbb: String = "none",
    val avBlock: String = "none",
    val prMs: Int = 0,                   // PR del BAV 1° grado (ms, 210-400); 0 = di serie (280)
    val escape: String = "ventricular",  // BAV 3°: scappamento ventricolare (QRS largo) o giunzionale (QRS stretto)
    val extras: List<String> = emptyList(),
    val interpretation: Boolean = true   // interpretazione automatica stampata sulla carta 12D (come i dispositivi)
) {
    val isNormal: Boolean get() = st == "none" && bbb == "none" && avBlock == "none" && extras.isEmpty()
    fun has(x: String): Boolean = extras.contains(x)
    override fun equals(other: Any?): Boolean = other is SLEcgMorph && st == other.st && stMm == other.stMm && bbb == other.bbb &&
        avBlock == other.avBlock && prMs == other.prMs && escape == other.escape && extras == other.extras && interpretation == other.interpretation
    override fun hashCode(): Int = listOf(st, stMm, bbb, avBlock, prMs, escape, extras, interpretation).hashCode()

    fun toMap(): Map<String, Any?> = linkedMapOf("st" to st, "st_mm" to (if (stMm > 0) stMm else null), "bbb" to bbb, "av_block" to avBlock,
        "pr_ms" to (if (avBlock == "first") (if (prMs > 0) prMs else 280).toLong() else null), "escape" to (if (avBlock == "third") escape else null),
        "extra" to extras, "interpretation" to interpretation, "label" to SFEcgMorphology.describe(this))

    companion object { val NORMAL = SLEcgMorph() }
}

object SFEcgMorphology {

    /** Rapporto fra atri e ventricoli nei blocchi «a rapporto» (FC = frequenza VENTRICOLARE): 0 = non a rapporto */
    fun avRatio(av: String): Double = when (av) {
        "mobitz1", "mobitz2" -> 4.0 / 3.0       // cicli 3:2 / 4:3 / 5:4, in media 4:3
        "2to1" -> 2.0
        "high_grade" -> 3.0
        else -> 0.0
    }

    fun avName(av: String): String = SLEcgAvNames[av] ?: "conduzione AV normale"
    fun stName(st: String): String? = SLEcgStNames[st]
    fun bbbName(b: String): String? = SLEcgBbbNames[b]
    fun extraName(x: String): String? = SLEcgExtraNames[x]

    /** Descrizione breve in italiano (registro, regia) */
    fun describe(m: SLEcgMorph): String {
        if (m.isNormal) return "normale"
        val p = ArrayList<String>()
        SLEcgStNames[m.st]?.let { p.add(if (m.stMm > 0) String.format(java.util.Locale.ITALY, "%s (%.1f mm)", it, m.stMm) else it) }
        SLEcgBbbNames[m.bbb]?.let { p.add(it) }
        SLEcgAvNames[m.avBlock]?.let {
            p.add(when (m.avBlock) {
                "first" -> String.format(java.util.Locale.US, "%s (PR %d ms)", it, if (m.prMs > 0) m.prMs else 280)
                "third" -> it + (if (m.escape == "junctional") ", scappamento giunzionale" else ", scappamento ventricolare")
                else -> it
            })
        }
        for (x in m.extras) SLEcgExtraNames[x]?.let { p.add(it) }
        return p.joinToString(", ")
    }

    /**
     * Nuova morfologia da `value` (oggetto parziale o "normal"/"none") a partire da `cur`. Ritorna (morfologia, errore).
     * Combinazioni non sensate rifiutate: WPW con blocchi di branca o con BAV; iperkaliemia e ipokaliemia insieme.
     */
    fun parse(value: Any?, cur: SLEcgMorph): Pair<SLEcgMorph?, String?> {
        if (value == "normal" || value == "none") return Pair(SLEcgMorph(interpretation = cur.interpretation), null)
        if (value !is Map<*, *>) return Pair(null, "set_ecg_morphology: value must be an object or \"normal\"")
        for (k in value.keys) if (k !in listOf("st", "st_mm", "bbb", "av_block", "pr_ms", "escape", "extra", "interpretation"))
            return Pair(null, "set_ecg_morphology: unknown field '$k'")
        fun str(k: String, allowed: List<String>, def: String): Pair<String?, String?> {
            if (!value.containsKey(k)) return Pair(def, null)
            val v = value[k] ?: "none"
            if (v !is String || !allowed.contains(v)) return Pair(null, "set_ecg_morphology: $k must be one of " + allowed.joinToString(", "))
            return Pair(v, null)
        }
        val (st, e1) = str("st", SLEcgStValues, cur.st); if (e1 != null) return Pair(null, e1)
        val (bbb, e2) = str("bbb", SLEcgBbbValues, cur.bbb); if (e2 != null) return Pair(null, e2)
        val (av, e3) = str("av_block", SLEcgAvValues, cur.avBlock); if (e3 != null) return Pair(null, e3)
        val (esc, e4) = str("escape", SLEcgEscapeValues, cur.escape); if (e4 != null) return Pair(null, e4)
        var stMm = if (st != cur.st) 0.0 else cur.stMm
        if (value.containsKey("st_mm")) {
            val v = value["st_mm"]
            if (v == null) stMm = 0.0
            else if (v !is Number || v is Boolean || !(v.toDouble() >= 0.5 && v.toDouble() <= 8.0)) return Pair(null, "set_ecg_morphology: st_mm must be 0.5-8")
            else stMm = v.toDouble()
        }
        var pr = if (av != "first") 0 else cur.prMs
        if (value.containsKey("pr_ms")) {
            val v = value["pr_ms"]
            if (v == null) pr = 0
            else if (v !is Number || v is Boolean || !(v.toDouble() >= 210 && v.toDouble() <= 400)) return Pair(null, "set_ecg_morphology: pr_ms must be 210-400")
            else pr = v.toInt()
        }
        var extras = cur.extras
        if (value.containsKey("extra")) {
            val v = value["extra"]
            val l = when (v) { null -> emptyList<Any?>(); "none" -> emptyList(); is String -> listOf(v); is List<*> -> v; else -> return Pair(null, "set_ecg_morphology: extra must be an array") }
            val out = ArrayList<String>()
            for (x in l) {
                if (x !is String || !SLEcgExtraValues.contains(x)) return Pair(null, "set_ecg_morphology: extra values: " + SLEcgExtraValues.joinToString(", "))
                if (!out.contains(x)) out.add(x)
            }
            extras = SLEcgExtraValues.filter { out.contains(it) }       // ordine fisso (confronti stabili)
        }
        var interp = cur.interpretation
        if (value.containsKey("interpretation")) {
            val v = value["interpretation"]
            if (v !is Boolean) return Pair(null, "set_ecg_morphology: interpretation must be true or false")
            interp = v
        }
        if (extras.contains("wpw") && bbb != "none") return Pair(null, "set_ecg_morphology: wpw cannot be combined with a bundle branch block")
        if (extras.contains("wpw") && av != "none") return Pair(null, "set_ecg_morphology: wpw cannot be combined with an AV block")
        if (extras.contains("hyperkalemia") && extras.contains("hyperkalemia_severe")) return Pair(null, "set_ecg_morphology: choose hyperkalemia or hyperkalemia_severe")
        if (extras.contains("hypokalemia") && (extras.contains("hyperkalemia") || extras.contains("hyperkalemia_severe")))
            return Pair(null, "set_ecg_morphology: hypokalemia and hyperkalemia are mutually exclusive")
        return Pair(SLEcgMorph(st!!, stMm, bbb!!, av!!, pr, esc!!, extras, interp), null)
    }
}
