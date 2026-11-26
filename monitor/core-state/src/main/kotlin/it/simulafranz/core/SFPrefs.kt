package it.simulafranz.core

/** Al posto di NSUserDefaults: sull'app Android è collegato alle SharedPreferences, nei test resta in memoria. */
interface SFPrefs {
    fun string(key: String): String?
    fun setString(key: String, value: String?)
    fun has(key: String): Boolean = string(key) != null
    fun int(key: String, def: Int = 0): Int = string(key)?.toDoubleOrNull()?.toInt() ?: def
    fun double(key: String, def: Double = 0.0): Double = string(key)?.toDoubleOrNull() ?: def
    fun bool(key: String, def: Boolean = false): Boolean = string(key)?.let { it == "1" || it == "true" } ?: def
    fun setInt(key: String, v: Int) = setString(key, v.toString())
    fun setDouble(key: String, v: Double) = setString(key, v.toString())
    fun setBool(key: String, v: Boolean) = setString(key, if (v) "1" else "0")
    /** Valori strutturati (dizionari, liste): salvati come JSON. */
    fun json(key: String): Any? = string(key)?.let { Json.parseOrNull(it) }
    fun setJson(key: String, v: Any?) = setString(key, if (v == null) null else Json.stringify(v))
}
