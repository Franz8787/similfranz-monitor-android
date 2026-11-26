// Portato da src/AppDelegate.m + src/main.m (controller iPad)
// similFranz Controller — avvio dell'app e comandi di verifica via URL (simulafranz-controller://...).
// ANDROID: l'AppDelegate dell'iPad qui è l'attività unica. In più: permesso di posizione (Android 9 lo vuole per la
// ricerca Wi-Fi Direct), chiesto al primo avvio; identità del tablet (CtlIdentity).
package it.simulafranz.controller

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.DisplayMetrics
import android.util.Log
import android.view.View
import android.view.WindowManager
import it.simulafranz.core.SFLog
import it.simulafranz.uk.*
import java.net.URLDecoder
import java.util.Locale

private const val kLocationRequest = 72

class MainActivity : Activity() {
    private lateinit var model: CtlModel
    private lateinit var vc: CtlViewController
    private var started = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)      // lo schermo resta acceso durante lo scenario
        Uk.ctx = this
        SFLog.sink = { line -> Log.i("SimilFranzCtl", line) }
        SFLog.prefix = "[similFranz Controller] "
        SFLog.fileName = "SimulaFranz_Controller.log"
        SFLog.directory = cacheDir
        val prefs = CtlAndroidPrefs(this)
        CtlIdentity.ensure(prefs)
        SFLog.log(String.format(Locale.US, "avvio similFranz Controller %s (codice %s, «%s»)", version(), CtlIdentity.code(), CtlIdentity.controllerName()))

        val dm = DisplayMetrics()
        @Suppress("DEPRECATION") windowManager.defaultDisplay.getRealMetrics(dm)
        // ANDROID (BugFixController 3): tela larga quanto lo schermo, altezza 768 punti come l'iPad. Larghezza = schermo / scala
        // (1280×800 → 1228×768): stessa grandezza di testi e tasti, nessuna fascia nera. Mai meno dei 1024 dell'iPad.
        val sw = maxOf(dm.widthPixels, dm.heightPixels); val sh = minOf(dm.widthPixels, dm.heightPixels)
        CTL_W = maxOf(1024.0, Math.floor(CTL_H * sw / sh))
        UkRootView.prepare(sw, sh, CTL_W, CTL_H)
        val rv = UkRootView(CTL_W, CTL_H)
        rv.root.backgroundColor = CTL_BG                                     // eventuale avanzo di un punto: stesso colore della pagina
        UIViewController.designWidth = CTL_W; UIViewController.designHeight = CTL_H      // schermate modali (editor, debriefing)
        UIViewController.host = rv.canvas
        model = CtlModel(this, prefs)
        vc = CtlViewController(model)
        setContentView(rv)
        rv.canvas.addSubview(vc.view)
        hideBars()

        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED)
            requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION), kLocationRequest)
        handleIntent(intent)
        CtlStoria.viewerGone = { if (!started && CtlStoria.viewerOpen == 0) { SFLog.log("Storia Sim chiusa senza tornare alla regia: controller sospeso"); model.suspend() } }
    }

    private fun version(): String = try { packageManager.getPackageInfo(packageName, 0).versionName ?: "?" } catch (e: Exception) { "?" }

    @Suppress("DEPRECATION")
    private fun hideBars() {
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or View.SYSTEM_UI_FLAG_LAYOUT_STABLE or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
    }
    override fun onWindowFocusChanged(hasFocus: Boolean) { super.onWindowFocusChanged(hasFocus); if (hasFocus) hideBars() }
    // ANDROID (pannello RCP 22/11): istante vero dell'ultimo dito appoggiato (ev.eventTime, sulla scala di CACurrentMediaTime):
    // «Batti il tempo» misura gli intervalli fra i tocchi da qui, non dal momento (a volte ritardato) in cui l'evento arriva alla vista
    override fun dispatchTouchEvent(ev: android.view.MotionEvent): Boolean {
        if (ev.actionMasked == android.view.MotionEvent.ACTION_DOWN)
            CtlTouchDownAt = CACurrentMediaTime() - (android.os.SystemClock.uptimeMillis() - ev.eventTime) / 1000.0
        return super.dispatchTouchEvent(ev)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        // ANDROID (storia sim 22/11): permesso di scrittura per i PDF della Storia Sim
        if (requestCode == kStoragePermRequest) { CtlStoria.permissionResult(grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED); return }
        if (requestCode != kLocationRequest) return
        val ok = grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED
        SFLog.log(if (ok) "permesso di posizione concesso (ricerca Wi-Fi Direct)" else "permesso di posizione NEGATO: la ricerca dei monitor non funzionerà")
        if (ok && started) { model.link.stop(); model.link.start() }
    }

    // applicationDidBecomeActive / applicationDidEnterBackground
    override fun onResume() { super.onResume(); window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON); started = true; model.start() }
    // ANDROID (fix analisi 22/11, BUG-05): se a coprire la regia è il visore/cartella di Storia Sim (attività nostra) il controller NON
    // si sospende (prima: comando rilasciato sul monitor, collegamento chiuso, ri-autorizzazione fantasma). Si sospende quando il
    // visore se ne va senza tornare alla regia (tasto Home, schermo spento): viewerGone.
    override fun onStop() {
        super.onStop(); started = false
        if (CtlStoria.viewerOpen > 0) { SFLog.log("regia coperta da Storia Sim: il controller resta collegato"); return }
        model.suspend()
    }

    // ANDROID (menu layout a tema 22/11): Indietro chiude il pannello CtlSheet aperto (come faceva l'AlertDialog di sistema)
    @Deprecated("Activity.onBackPressed") override fun onBackPressed() { if (CtlSheet.handleBack()) return; super.onBackPressed() }
    override fun onNewIntent(intent: Intent?) { super.onNewIntent(intent); setIntent(intent); handleIntent(intent) }

    // application:openURL: — simulafranz-controller://<comando>?a=b
    private fun handleIntent(i: Intent?) {
        val u = i?.data ?: return
        if (u.scheme != "simulafranz-controller") return
        // ANDROID (audit 26/11, PUL-F1): i comandi via URL solo nelle build di prova (debuggable); in quella distribuita nulla
        if ((applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) == 0) { SFLog.log("openURL ignorato: solo nelle build di prova"); return }
        val q = HashMap<String, String>()
        for (kv in (u.encodedQuery ?: "").split("&")) {
            val r = kv.indexOf('=')
            if (r >= 0) q[kv.substring(0, r)] = try { URLDecoder.decode(kv.substring(r + 1), "UTF-8") } catch (e: Exception) { "" }
        }
        val host = u.host ?: return
        if (host.isNotEmpty()) Uk.after(0.2) { vc.debug(host, q) }
    }
}
