// ANDROID: nessun file corrispondente sull'iPad. Sostituisce NSUserDefaults (stesse chiavi: sf_pin, sf_port, sf_name,
// sf_layout, sf_vol_N, sf_last_ssid …) e tiene l'identità del tablet (sf_device_uid + nome), che sull'iPad stava in
// Impostazioni › similFranz Monitor › Nome.
package it.simulafranz.monitor

import android.content.Context
import android.content.SharedPreferences
import it.simulafranz.core.SFLog
import it.simulafranz.core.SFPrefs
import java.util.UUID

/** SFPrefs sopra le SharedPreferences "it.simulafranz.monitor". Tutti i valori sono salvati come testo. */
class SFAndroidPrefs(ctx: Context) : SFPrefs {
    private val sp: SharedPreferences = ctx.applicationContext.getSharedPreferences("it.simulafranz.monitor", Context.MODE_PRIVATE)

    override fun string(key: String): String? =
        try { sp.getString(key, null) } catch (e: ClassCastException) { sp.all[key]?.toString() }

    override fun setString(key: String, value: String?) {
        val e = sp.edit()
        if (value == null) e.remove(key) else e.putString(key, value)
        e.apply()
    }
}

/**
 * Identità di questo monitor. Più tablet possono lavorare nella stessa sala, quindi non devono mai confondersi:
 *  - sf_device_uid: UUID casuale creato al primo avvio, non cambia mai;
 *  - codice: i primi 4 caratteri esadecimali dell'uid, in maiuscolo (es. "7F3A"), mostrato accanto al nome;
 *  - sf_name: nome scelto dall'utente (di serie "similFranz Monitor " + codice). Rinominare cambia SOLO sf_name.
 */
object SFIdentity {
    const val kUidKey = "sf_device_uid"
    const val kNameKey = "sf_name"
    const val kMaxName = 32

    /** Impostazioni dell'app (le imposta MainActivity all'avvio, prima di creare qualunque vista). */
    lateinit var prefs: SFPrefs
    val ready: Boolean get() = ::prefs.isInitialized

    /** Nome cambiato (main thread): l'app ripubblica il servizio Bonjour con il nome nuovo. */
    var onRename: (() -> Unit)? = null

    /** Al primo avvio crea uid e nome di serie; agli avvii successivi non tocca niente. */
    fun ensure(prefs: SFPrefs) {
        this.prefs = prefs
        var uid = prefs.string(kUidKey)
        if (uid == null || code(uid).length != 4) {
            uid = UUID.randomUUID().toString()
            prefs.setString(kUidKey, uid)
            SFLog.log("identità: nuovo codice del monitor " + code(uid))
        }
        val n = prefs.string(kNameKey)?.trim() ?: ""
        if (n.isEmpty()) prefs.setString(kNameKey, defaultName())
    }

    fun uid(): String = prefs.string(kUidKey) ?: ""

    private fun code(uid: String): String = uid.filter { it.isDigit() || it.lowercaseChar() in 'a'..'f' }.take(4).uppercase()

    /** Codice corto del tablet (4 caratteri esadecimali maiuscoli): distingue due monitor con lo stesso nome. */
    fun code(): String = code(uid())

    fun defaultName(): String = "similFranz Monitor " + code()

    /** Nome con cui il monitor compare nei controller. */
    fun monitorName(): String {
        val n = prefs.string(kNameKey)?.trim() ?: ""
        return if (n.isNotEmpty()) n else defaultName()
    }

    /** Nome ripulito come verrà salvato (spazi tolti ai lati, al massimo 32 caratteri), oppure null se vuoto. */
    fun cleanName(name: String?): String? {
        var n = (name ?: "").replace('\n', ' ').replace('\r', ' ').trim()
        if (n.length > kMaxName) n = n.substring(0, kMaxName).trim()
        return if (n.isEmpty()) null else n
    }

    /** Rinomina il monitor. Vuoto = resta il nome di prima. Ritorna true se il nome è cambiato. */
    fun rename(name: String?): Boolean {
        val n = cleanName(name) ?: return false
        if (n == monitorName()) return false
        prefs.setString(kNameKey, n)
        SFLog.log("identità: monitor rinominato «$n» (codice ${code()})")
        onRename?.invoke()
        return true
    }
}
