// ANDROID (storia sim 22/11): nessun file corrispondente sull'iPad.
// «Storia Sim» sulla schermata Home del tablet controller: elenco dei PDF di /sdcard/Documents/Storia Sim (più recenti
// prima), visore interno (android.graphics.pdf.PdfRenderer, pagine una sotto l'altra, zoom semplice) e «Condividi»
// (scelta di sistema: mail, Bluetooth, Quick Share…). Viste Android normali (non uk). I PDF escono verso le altre app
// solo da StoriaPdfProvider (sola lettura, permesso temporaneo sull'URI condiviso).
package it.simulafranz.controller

import android.Manifest
import android.app.Activity
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.Icon
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import it.simulafranz.uk.Uk
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

const val STORIA_AUTHORITY = "it.simulafranz.controller.storia"

/** URI condivisibile di un PDF della cartella Storia Sim (servito da StoriaPdfProvider) */
fun StoriaPdfUri(f: File): Uri = Uri.Builder().scheme("content").authority(STORIA_AUTHORITY).appendPath("pdf").appendPath(f.name).build()

/** dal controller: apre un PDF nel visore di Storia Sim */
fun CtlOpenStoriaPdf(f: File) {
    val i = Intent(Uk.ctx, StoriaSimActivity::class.java).putExtra("pdf", f.absolutePath)
    i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    Uk.ctx.startActivity(i)
}
/** dal controller: icona «Storia Sim» sulla schermata Home (ShortcutManager, API 26+). Restituisce la frase da mostrare. */
fun CtlPinStoriaShortcut(ctx: Context): String {
    val sm = ctx.getSystemService(ShortcutManager::class.java) ?: return "Questo tablet non permette di aggiungere icone alla schermata Home"
    if (!sm.isRequestPinShortcutSupported) return "La schermata Home di questo tablet non accetta icone aggiunte dalle app: l'icona «Storia Sim» è comunque nell'elenco delle app"
    val intent = Intent(ctx, StoriaSimActivity::class.java).setAction(Intent.ACTION_MAIN)
    val info = ShortcutInfo.Builder(ctx, "storia_sim").setShortLabel("Storia Sim").setLongLabel("Storia Sim — simulazioni salvate")
        .setIcon(Icon.createWithResource(ctx, R.mipmap.ic_storia)).setIntent(intent).build()
    return if (sm.requestPinShortcut(info, null)) "Conferma sul tablet: l'icona «Storia Sim» va sulla schermata Home" else "Icona non aggiunta"
}

class StoriaSimActivity : Activity() {
    private val BG = Color.rgb(9, 11, 15); private val CARD = Color.rgb(22, 27, 34); private val CARD2 = Color.rgb(33, 40, 50)
    private val INK = Color.rgb(240, 245, 250); private val INK2 = Color.rgb(168, 181, 196); private val TINT = Color.rgb(10, 133, 255)
    private lateinit var root: LinearLayout; private lateinit var bar: LinearLayout; private lateinit var body: FrameLayout
    private var renderer: PdfRenderer? = null; private var pfd: ParcelFileDescriptor? = null
    private var current: File? = null; private var zoom = 0.8f
    private val zooms = floatArrayOf(0.55f, 0.8f, 1.0f, 1.4f)
    private val pages = ArrayList<ImageView>(); private var pageScroll: ScrollView? = null

    private fun dp(v: Float): Int = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        root = LinearLayout(this); root.orientation = LinearLayout.VERTICAL; root.setBackgroundColor(BG)
        bar = LinearLayout(this); bar.orientation = LinearLayout.HORIZONTAL; bar.gravity = Gravity.CENTER_VERTICAL
        bar.setPadding(dp(16f), dp(10f), dp(16f), dp(10f)); bar.setBackgroundColor(CARD)
        body = FrameLayout(this)
        root.addView(bar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        root.addView(body, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)
        if (!canRead()) requestPermissions(arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE), kStoragePermRequest)
        route(intent)
    }
    override fun onNewIntent(intent: Intent?) { super.onNewIntent(intent); setIntent(intent); route(intent) }
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        if (requestCode == kStoragePermRequest) { if (current == null) showList() else open(current!!) }
    }
    override fun onDestroy() { closeRenderer(); super.onDestroy() }
    // ANDROID (fix analisi 22/11, BUG-05): visore aperto sopra la regia = il controller resta collegato e al comando
    private var counted = false
    override fun onStart() { super.onStart(); if (!counted) { counted = true; CtlStoria.viewerOpen++ } }
    override fun onStop() { super.onStop(); if (counted) { counted = false; CtlStoria.viewerOpen = maxOf(0, CtlStoria.viewerOpen - 1) }; CtlStoria.viewerGone?.invoke() }
    @Deprecated("Deprecated in Java")
    override fun onBackPressed() { if (current != null && intent?.getStringExtra("pdf") == null) showList() else super.onBackPressed() }

    private fun canRead() = checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
    private fun route(i: Intent?) {
        val p = i?.getStringExtra("pdf")
        if (p != null && File(p).exists()) open(File(p)) else showList()
    }

    // MARK: - Elementi

    private fun text(t: String, sp: Float, color: Int, bold: Boolean = false): TextView = TextView(this).apply {
        text = t; setTextColor(color); setTextSize(TypedValue.COMPLEX_UNIT_SP, sp); if (bold) typeface = Typeface.DEFAULT_BOLD
    }
    private fun button(t: String, filled: Boolean, tap: () -> Unit): Button = Button(this).apply {
        text = t; isAllCaps = false; setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f); setTextColor(if (filled) Color.WHITE else TINT)
        background = GradientDrawable().apply { cornerRadius = dp(10f).toFloat(); setColor(if (filled) TINT else CARD2) }
        setPadding(dp(18f), 0, dp(18f), 0); minHeight = dp(44f); minimumHeight = dp(44f); stateListAnimator = null
        setOnClickListener { tap() }
    }
    private fun barReset(title: String, sub: String?) {
        bar.removeAllViews()
        val col = LinearLayout(this); col.orientation = LinearLayout.VERTICAL
        col.addView(text(title, 21f, INK, true).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END })
        if (sub != null) col.addView(text(sub, 13f, INK2))
        bar.addView(col, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
    }
    private fun barAdd(v: View) { bar.addView(v, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(44f)).apply { leftMargin = dp(10f) }) }

    // MARK: - Elenco

    private fun pdfs(): List<File> = (StoriaPdfDir().listFiles() ?: emptyArray()).filter { it.isFile && it.name.endsWith(".pdf", true) && !it.name.startsWith(".") }
        .sortedWith(compareByDescending<File> { it.name.take(16) }.thenByDescending { it.lastModified() })

    private fun showList() {
        closeRenderer(); current = null
        val files = if (canRead()) pdfs() else emptyList()
        barReset("Storia Sim", if (files.isEmpty()) "Simulazioni salvate dal controller" else if (files.size == 1) "1 simulazione salvata" else "${files.size} simulazioni salvate")
        barAdd(button("Aggiorna", false) { showList() })
        body.removeAllViews()
        if (files.isEmpty()) {
            val t = text(if (canRead()) "Nessuna simulazione salvata.\n\nNel controller similFranz: FINE SCENARIO → piano di debriefing → «Chiudi» → «Salva in Storia Sim». Il PDF compare qui (cartella Documenti › Storia Sim)."
                         else "Per mostrare i PDF serve il permesso di accedere alla memoria del tablet.", 17f, INK2)
            t.gravity = Gravity.CENTER; t.setPadding(dp(60f), dp(40f), dp(60f), dp(40f))
            body.addView(t, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            return
        }
        val sv = ScrollView(this); val list = LinearLayout(this); list.orientation = LinearLayout.VERTICAL; list.setPadding(dp(16f), dp(12f), dp(16f), dp(24f))
        val df = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.ITALY)
        for (f in files) {
            val row = LinearLayout(this); row.orientation = LinearLayout.HORIZONTAL; row.gravity = Gravity.CENTER_VERTICAL
            row.background = GradientDrawable().apply { cornerRadius = dp(12f).toFloat(); setColor(CARD) }
            row.setPadding(dp(18f), dp(12f), dp(12f), dp(12f))
            val (title, date) = parseName(f)
            val col = LinearLayout(this); col.orientation = LinearLayout.VERTICAL
            col.addView(text(title, 18f, INK, true))
            col.addView(text(String.format(Locale.ITALY, "%s · %d kB", date ?: df.format(Date(f.lastModified())), (f.length() + 1023) / 1024), 13f, INK2))
            row.addView(col, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            row.addView(button("Condividi", false) { share(f) }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(44f)))
            row.addView(button("Apri", true) { open(f) }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(44f)).apply { leftMargin = dp(10f) })
            row.setOnClickListener { open(f) }
            list.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(10f) })
        }
        sv.addView(list)
        body.addView(sv, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
    }
    /** «2026-11-22 09.30 Titolo.pdf» → («Titolo», «22/11/2026 09:30») */
    private fun parseName(f: File): Pair<String, String?> {
        val n = f.name.removeSuffix(".pdf").removeSuffix(".PDF")
        val m = Regex("^(\\d{4})-(\\d{2})-(\\d{2}) (\\d{2})\\.(\\d{2}) (.*)$").find(n) ?: return Pair(n, null)
        val g = m.groupValues
        return Pair(g[6].ifBlank { "Simulazione" }, "${g[3]}/${g[2]}/${g[1]} ${g[4]}:${g[5]}")
    }

    // MARK: - Visore

    private fun closeRenderer() {
        for (iv in pages) recycle(iv); pages.clear(); pageScroll = null
        try { renderer?.close() } catch (e: Exception) {}; renderer = null
        try { pfd?.close() } catch (e: Exception) {}; pfd = null
    }
    private fun recycle(iv: ImageView) { val b = iv.tag as? Bitmap; iv.setImageDrawable(null); iv.tag = null; b?.recycle() }

    private fun open(f: File) {
        closeRenderer(); current = f
        val (title, date) = parseName(f)
        barReset(title, date)
        if (intent?.getStringExtra("pdf") == null) barAdd(button("‹ Elenco", false) { showList() })
        barAdd(button("−", false) { setZoom(-1) }); barAdd(button("+", false) { setZoom(1) })
        barAdd(button("Condividi", true) { share(f) })
        body.removeAllViews()
        val r = try { pfd = ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY); PdfRenderer(pfd!!) } catch (e: Exception) { null }
        if (r == null) { body.addView(text("Questo PDF non si apre (file danneggiato o non leggibile).", 17f, INK2).apply { gravity = Gravity.CENTER }); return }
        renderer = r
        layoutPages()
    }
    private fun setZoom(dir: Int) {
        var i = zooms.indexOfFirst { it == zoom }; if (i < 0) i = 1
        i = (i + dir).coerceIn(0, zooms.size - 1)
        if (zooms[i] == zoom) return
        val sv = pageScroll; val frac = if (sv != null && sv.getChildAt(0) != null && sv.getChildAt(0).height > 0) sv.scrollY.toFloat() / sv.getChildAt(0).height else 0f
        zoom = zooms[i]; layoutPages(frac)
    }
    private fun layoutPages(frac: Float = 0f) {
        val r = renderer ?: return
        for (iv in pages) recycle(iv); pages.clear()
        body.removeAllViews()
        val availW = resources.displayMetrics.widthPixels - dp(32f)
        val pw = (availW * zoom).toInt()
        val sv = ScrollView(this); val hs = HorizontalScrollView(this)
        val col = LinearLayout(this); col.orientation = LinearLayout.VERTICAL; col.gravity = Gravity.CENTER_HORIZONTAL
        col.setPadding(dp(16f), dp(16f), dp(16f), dp(24f))
        for (i in 0 until r.pageCount) {
            val p = r.openPage(i); val ph = (pw.toFloat() * p.height / p.width).toInt(); p.close()
            val iv = ImageView(this); iv.setBackgroundColor(Color.WHITE); iv.scaleType = ImageView.ScaleType.FIT_XY
            col.addView(iv, LinearLayout.LayoutParams(pw, ph).apply { bottomMargin = dp(14f) })
            pages.add(iv)
        }
        val minW = resources.displayMetrics.widthPixels
        col.minimumWidth = minW      // HorizontalScrollView misura il figlio «a contenuto»: senza questo la pagina resta a sinistra
        hs.addView(col, FrameLayout.LayoutParams(maxOf(minW, pw + dp(32f)), ViewGroup.LayoutParams.WRAP_CONTENT))
        sv.addView(hs)
        body.addView(sv, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        pageScroll = sv
        sv.setOnScrollChangeListener { _, _, _, _, _ -> renderVisible() }
        sv.post { if (frac > 0) sv.scrollTo(0, (frac * (sv.getChildAt(0)?.height ?: 0)).toInt()); renderVisible() }
    }
    /** si disegnano solo le pagine vicine a quella a schermo (memoria): le altre si liberano */
    private fun renderVisible() {
        val r = renderer ?: return; val sv = pageScroll ?: return
        val top = sv.scrollY - sv.height; val bottom = sv.scrollY + 2 * sv.height
        for ((i, iv) in pages.withIndex()) {
            val y0 = iv.top; val y1 = iv.bottom
            val near = y1 >= top && y0 <= bottom
            if (near && iv.tag == null && iv.width > 0) {
                try {
                    val b = Bitmap.createBitmap(iv.width, iv.height, Bitmap.Config.ARGB_8888); b.eraseColor(Color.WHITE)
                    val p = r.openPage(i); p.render(b, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY); p.close()
                    iv.setImageBitmap(b); iv.tag = b
                } catch (e: Throwable) {}
            } else if (!near && iv.tag != null) recycle(iv)
        }
    }

    // MARK: - Condividi

    private fun share(f: File) {
        val uri = StoriaPdfUri(f)
        val i = Intent(Intent.ACTION_SEND).setType("application/pdf")
        i.putExtra(Intent.EXTRA_STREAM, uri)
        i.putExtra(Intent.EXTRA_SUBJECT, "Storia Sim — " + parseName(f).first)
        i.clipData = ClipData.newRawUri(f.name, uri)
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        try { startActivity(Intent.createChooser(i, "Condividi il PDF")) }
        catch (e: Exception) { Toast.makeText(this, "Nessuna app per condividere", Toast.LENGTH_LONG).show() }
    }
}
