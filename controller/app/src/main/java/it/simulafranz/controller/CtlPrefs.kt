// ANDROID: nessun file corrispondente sull'iPad. Sostituisce NSUserDefaults (stesse chiavi: sf_pin, sf_monitor, sf_client,
// sf_name, sf_states …) e tiene l'identità del tablet, come SFAndroidPrefs/SFIdentity del monitor Android.
package it.simulafranz.controller

import android.content.Context
import android.content.SharedPreferences
import it.simulafranz.core.SFLog
import it.simulafranz.core.SFPrefs
import java.util.UUID

/** SFPrefs sopra le SharedPreferences "it.simulafranz.controller". Tutti i valori sono salvati come testo. */
class CtlAndroidPrefs(ctx: Context) : SFPrefs {
    private val sp: SharedPreferences = ctx.applicationContext.getSharedPreferences("it.simulafranz.controller", Context.MODE_PRIVATE)
    override fun string(key: String): String? = try { sp.getString(key, null) } catch (e: ClassCastException) { sp.all[key]?.toString() }
    override fun setString(key: String, value: String?) { val e = sp.edit(); if (value == null) e.remove(key) else e.putString(key, value); e.apply() }
}

/**
 * Identità di questo controller (più controller nella stessa sala non devono confondersi):
 *  - sf_device_uid: UUID casuale creato al primo avvio, non cambia mai; codice = primi 4 caratteri esadecimali ("7F3A");
 *  - sf_name: nome scelto dall'utente (di serie "similFranz Controller " + codice). Il nome arriva al monitor con le richieste.
 */
object CtlIdentity {
    const val kUidKey = "sf_device_uid"
    const val kNameKey = "sf_name"
    const val kMaxName = 32
    lateinit var prefs: SFPrefs

    fun ensure(prefs: SFPrefs) {
        this.prefs = prefs
        var uid = prefs.string(kUidKey)
        if (uid == null || code(uid).length != 4) {
            uid = UUID.randomUUID().toString(); prefs.setString(kUidKey, uid)
            SFLog.log("identità: nuovo codice del controller " + code(uid))
        }
        if ((prefs.string(kNameKey)?.trim() ?: "").isEmpty()) prefs.setString(kNameKey, defaultName())
    }
    private fun code(uid: String): String = uid.filter { it.isDigit() || it.lowercaseChar() in 'a'..'f' }.take(4).uppercase()
    fun code(): String = code(prefs.string(kUidKey) ?: "")
    fun defaultName(): String = "similFranz Controller " + code()
    fun controllerName(): String { val n = prefs.string(kNameKey)?.trim() ?: ""; return n.ifEmpty { defaultName() } }
    fun cleanName(name: String?): String? {
        var n = (name ?: "").replace('\n', ' ').replace('\r', ' ').trim()
        if (n.length > kMaxName) n = n.substring(0, kMaxName).trim()
        return n.ifEmpty { null }
    }
    /** Rinomina. Il nome nuovo vale dalla richiesta successiva al monitor. */
    fun rename(name: String?): Boolean {
        val n = cleanName(name) ?: return false
        if (n == controllerName()) return false
        prefs.setString(kNameKey, n)
        SFLog.log("identità: controller rinominato «$n» (codice ${code()})")
        return true
    }
}
