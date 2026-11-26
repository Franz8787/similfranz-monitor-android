package it.simulafranz.monitor

import android.app.Activity
import android.app.admin.DeviceAdminReceiver
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle

// ANDROID (guscio 25/11): con il tablet nel guscio il tasto di accensione resta dietro lo sportellino.
// L'icona «Blocca schermo» sulla Home spegne lo schermo senza toccarlo; si riaccende con il tasto Home fisico.
// Serve il permesso di amministratore del dispositivo con la sola facoltà «blocca schermo»: la prima volta lo chiede.

class SFBloccaAdmin : DeviceAdminReceiver()

object SFBlocca {
    private fun dpm(c: Context) = c.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
    fun pronto(c: Context): Boolean = try { dpm(c).isAdminActive(ComponentName(c, SFBloccaAdmin::class.java)) } catch (e: Exception) { false }
    fun spegni(c: Context) { try { dpm(c).lockNow() } catch (e: Exception) {} }
}

class SFBloccaActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val dpm = getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        val admin = ComponentName(this, SFBloccaAdmin::class.java)
        if (dpm.isAdminActive(admin)) {
            try { dpm.lockNow() } catch (e: Exception) {}
        } else {
            try {
                startActivity(Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN)
                    .putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, admin)
                    .putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                        "Serve solo a spegnere lo schermo dall'icona «Blocca schermo», senza usare il tasto di accensione. Per riaccenderlo premi il tasto Home."))
            } catch (e: Exception) {}
        }
        finish()
    }
}
