// ANDROID (funzioni mancanti 22/11) — file nuovo, solo Android.
// Pezzi di supporto delle funzioni aggiunte dopo gli audit clinici (analisi-fisiologia/CORRETTI.md, «Funzioni mancanti 22/11»):
//  - SLAlarmGate: ritardi d'allarme per parametro (IN-08): un allarme di limite compare solo se la condizione dura da almeno
//    N secondi (tempo clinico). Lo tiene lo stato (uno per monitor), AlarmManager lo consulta dall'istantanea.
//  - SFTrendStore: storia dei parametri a schermo (IN-14) campionata ogni 5 s di scenario, per le viste Trend dei layout,
//    la regia e il debriefing.
//  - SLArtLineMath: effetto della linea arteriosa (smorzamento, livello del trasduttore, occlusione: IN-03/OP-04) sui numeri.
//  - SLStateEvent: voci per il registro prodotte dal cuore (azzeramento, lavaggio, limiti cambiati, allarmi silenziati…) con
//    chi le ha fatte (partecipanti dal monitor, nome della regia dall'API, «scenario» dalle fasi).
// Kotlin puro, testabile sul Mac.
package it.simulafranz.core

/** Ritardi d'allarme di serie (secondi). hr/art/nibp 0: come i monitor ICU (la FC e la PA sono già medie); SpO2 qualche
 */
fun SLDefaultAlarmDelays(): Map<String, Int> = linkedMapOf("hr" to 0, "spo2" to 5, "rr" to 5, "etco2" to 5, "art" to 0, "nibp" to 0,
    "temp" to 10, "apnea" to 20)

/** Limiti d'allarme NUOVI (IN-09). 0 = spento (di serie: nessun allarme nuovo, «non snaturare»). Temperature in decimi di °C. */
fun SLExtraLimitDefaults(): Map<String, Int> = linkedMapOf(
    "art_sys_low" to 90, "art_sys_high" to 180, "nibp_sys_low" to 90, "nibp_sys_high" to 180,
    "art_map_low" to 0, "art_map_high" to 0, "nibp_map_low" to 0, "nibp_map_high" to 0,
    "spo2_high" to 0, "temp_low" to 0, "temp_high" to 0)
/** Intervalli ammessi dei limiti nuovi (0 sempre ammesso per quelli spegnibili). */
fun SLExtraLimitRanges(): Map<String, List<Int>> = linkedMapOf(
    "art_sys_low" to listOf(30, 250), "art_sys_high" to listOf(50, 300), "nibp_sys_low" to listOf(30, 250), "nibp_sys_high" to listOf(50, 300),
    "art_map_low" to listOf(30, 150), "art_map_high" to listOf(40, 200), "nibp_map_low" to listOf(30, 150), "nibp_map_high" to listOf(40, 200),
    "spo2_high" to listOf(80, 100), "temp_low" to listOf(300, 380), "temp_high" to listOf(340, 420))
val SLOffableLimits: Set<String> = setOf("art_map_low", "art_map_high", "nibp_map_low", "nibp_map_high", "spo2_high", "temp_low", "temp_high")

/** Nome leggibile di un limite (registro, regia). */
fun SLLimitLabel(k: String): String = when (k) {
    "hr_low" -> "FC bassa"; "hr_high" -> "FC alta"; "spo2_low" -> "SpO₂ bassa"; "spo2_high" -> "SpO₂ alta"
    "rr_low" -> "FR bassa"; "rr_high" -> "FR alta"; "etco2_low" -> "EtCO₂ bassa"; "etco2_high" -> "EtCO₂ alta"
    "sys_low" -> "PA sistolica bassa"; "sys_high" -> "PA sistolica alta"
    "art_sys_low" -> "ART sistolica bassa"; "art_sys_high" -> "ART sistolica alta"; "nibp_sys_low" -> "NIBP sistolica bassa"; "nibp_sys_high" -> "NIBP sistolica alta"
    "art_map_low" -> "ART PAM bassa"; "art_map_high" -> "ART PAM alta"; "nibp_map_low" -> "NIBP PAM bassa"; "nibp_map_high" -> "NIBP PAM alta"
    "temp_low" -> "temperatura bassa"; "temp_high" -> "temperatura alta"
    else -> k
}
fun SLLimitValueText(k: String, v: Int): String = if (v == 0 && SLOffableLimits.contains(k)) "spento"
    else if (k.startsWith("temp")) String.format(java.util.Locale.ITALY, "%.1f °C", v / 10.0) else v.toString()

/** Ritardi d'allarme: istante (tempo clinico) dell'inizio di ogni condizione in corso. */
class SLAlarmGate {
    private val since = HashMap<String, Double>()
    /** true se cond vale da almeno delay secondi (delay <= 0: subito). Una condizione che cade si azzera. */
    fun held(key: String, cond: Boolean, now: Double, delay: Int): Boolean {
        synchronized(this) {
            if (!cond) { since.remove(key); return false }
            if (delay <= 0) { since.putIfAbsent(key, now); return true }
            val t0 = since.getOrPut(key) { now }
            if (now < t0) { since[key] = now; return false }
            return now - t0 >= delay
        }
    }
    fun clear() { synchronized(this) { since.clear() } }
}

/** ANDROID (IN-14): storia dei parametri MOSTRATI dal monitor (null = «---» a schermo o sensore non collegato). */
class SFTrendStore {
    private val rows = ArrayList<Map<String, Any?>>()
    private var lastT = -1e9
    val intervalS = 5.0
    private val maxRows = 4 * 3600 / 5            // 4 ore
    fun clear() { synchronized(this) { rows.clear(); lastT = -1e9 } }
    fun due(t: Double): Boolean = synchronized(this) { t - lastT >= intervalS || t < lastT }
    fun add(t: Double, row: Map<String, Any?>) {
        synchronized(this) {
            if (t < lastT) { rows.clear() }        // orologio di scenario azzerato: si riparte
            lastT = t
            rows.add(row)
            while (rows.size > maxRows) rows.removeAt(0)
        }
    }
    /** Righe con t >= since; step > intervallo = una riga ogni step secondi (la prima di ogni finestra). */
    fun since(since: Double, step: Int): List<Map<String, Any?>> {
        synchronized(this) {
            val out = ArrayList<Map<String, Any?>>()
            var next = -1e9
            for (r in rows) {
                val t = (r["t"] as? Number)?.toDouble() ?: continue
                if (t < since) continue
                if (step > intervalS && t < next) continue
                out.add(r); if (step > intervalS) next = Math.floor(t / step) * step + step
            }
            return out
        }
    }
    fun size(): Int = synchronized(this) { rows.size }
}

/** ANDROID (IN-03/OP-04): effetto della linea arteriosa sui numeri S/D (la curva la modifica WaveformEngine allo stesso modo).
 *  damping: 0 ottimale, 1 sovrasmorzata (bolla, coagulo: S−, D+, PAM invariata), 2 sottosmorzata (tubi lunghi: S+, D−). */
object SLArtLineMath {
    fun apply(s: Int, d: Int, damping: Int, occluded: Boolean, offset: Double): Pair<Int, Int> {
        if (s < 0 || d < 0) return Pair(s, d)
        val m = (s + 2.0 * d) / 3.0
        var ss = s.toDouble(); var dd = d.toDouble()
        if (occluded) { ss = m + 1; dd = m - 1 }
        else if (damping == 1) { ss = m + 0.55 * (s - m); dd = m + 0.55 * (d - m) }
        else if (damping == 2) { val pp = (s - d).toDouble(); ss = s + 0.22 * pp; dd = d - 0.06 * pp }
        ss += offset; dd += offset
        val si = Math.max(0, SLLround(ss)); val di = Math.max(0, Math.min(si - 1, SLLround(dd)))
        return Pair(si, if (si == 0) 0 else di)
    }
    /** mmHg letti in più (+) o in meno (−) per il livello del trasduttore: +cm = trasduttore SOPRA l'asse flebostatico. */
    fun levelOffset(levelCm: Int): Double = -0.74 * levelCm
    fun dampingName(k: Int): String = when (k) { 1 -> "over"; 2 -> "under"; else -> "optimal" }
    fun dampingIndex(n: Any?): Int = when (n) { "over", "overdamped" -> 1; "under", "underdamped" -> 2; "optimal" -> 0; else -> -1 }
}

/** ANDROID (funzioni mancanti 22/11): voce per il registro prodotta dal cuore. kind come SFSessionLog (monitor, alarm, action…). */
class SLStateEvent(val seq: Int, val kind: String, val text: String, val tag: String?, val by: String?, val extra: Map<String, Any?>? = null)

/** ANDROID (funzioni mancanti 22/11): sorgente del comando in corso sul thread (regia dall'API, scenario a fasi). Senza sorgente
 *  il comando viene dal monitor = «partecipanti». */
object SLCommandSource {
    private val tl = ThreadLocal<String?>()
    fun current(): String? = tl.get()
    fun <T> with(by: String?, f: () -> T): T { val old = tl.get(); tl.set(by); try { return f() } finally { tl.set(old) } }
}

/** ANDROID (IN-13): valori di regia «da laboratorio/bilancio» (non sul monitor: in reparto non stanno lì), mostrati in regia e
 *  scritti nel registro. Chiave -> (min, max, decimali, etichetta, unità). */
val SLLabFields: Map<String, List<Any>> = linkedMapOf(
    "ph" to listOf(6.8, 7.8, 2, "pH", ""), "pao2_mmhg" to listOf(20.0, 600.0, 0, "PaO₂", "mmHg"), "paco2_mmhg" to listOf(10.0, 150.0, 0, "PaCO₂", "mmHg"),
    "hco3_mmol" to listOf(2.0, 50.0, 1, "HCO₃⁻", "mmol/l"), "be_mmol" to listOf(-30.0, 30.0, 1, "BE", "mmol/l"),
    "lactate_mmol" to listOf(0.2, 25.0, 1, "lattati", "mmol/l"), "glucose_mgdl" to listOf(10.0, 900.0, 0, "glicemia", "mg/dl"),
    "potassium_mmol" to listOf(1.0, 10.0, 1, "K⁺", "mmol/l"), "hb_gdl" to listOf(2.0, 22.0, 1, "Hb", "g/dl"),
    "urine_ml_h" to listOf(0.0, 1000.0, 0, "diuresi", "ml/h"), "balance_ml" to listOf(-20000.0, 20000.0, 0, "bilancio", "ml"))
fun SLLabText(k: String, v: Double): String {
    val f = SLLabFields[k] ?: return "$k $v"
    val dec = f[2] as Int
    val num = if (dec == 0) Math.round(v).toString() else String.format(java.util.Locale.ITALY, "%.${dec}f", v)
    val u = f[4] as String
    return "${f[3]} $num" + (if (u.isNotEmpty()) " $u" else "")
}
