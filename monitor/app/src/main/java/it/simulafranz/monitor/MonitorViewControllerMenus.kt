// Portato da src/MonitorViewController.m (monitor iPad)
// Parte 2 di 3: menu del parametro, azioni locali, layout del monitor, splash, pausa, tocchi di prova, cassetto, volumi, pannello (i).
package it.simulafranz.monitor

import android.graphics.Bitmap
import it.simulafranz.core.*
import it.simulafranz.uk.*
import java.io.File
import java.io.FileOutputStream
import java.util.Locale
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

// Configurazione del menu di ciascun parametro: titolo, colore, limiti (chiave, etichetta, passo, min, max)
private fun SFMenuConfig(): Map<String, Map<String, Any>> {
    return mapOf(
      "hr" to mapOf("title" to "FC", "limits" to listOf(listOf("hr_high", "Limite alto", 5, 20, 300), listOf("hr_low", "Limite basso", 5, 15, 250))),
      // ANDROID (funzioni mancanti 22/11, IN-09): 6° elemento = valore dello stepper che vuol dire «spento» (0 per il cuore);
      // temperatura in decimi (mostrata in °C). ART e NIBP hanno limiti propri, più la PAM (spenta di serie)
      "spo2" to mapOf("title" to "SpO₂", "limits" to listOf(listOf("spo2_high", "Limite alto", 1, 80, 101, 101), listOf("spo2_low", "Limite basso", 1, 50, 99))),
      "art" to mapOf("title" to "ART", "limits" to listOf(listOf("art_sys_high", "Sistolica alta", 5, 50, 300), listOf("art_sys_low", "Sistolica bassa", 5, 30, 250),
                                                       listOf("art_map_high", "PAM alta", 5, 40, 205, 205), listOf("art_map_low", "PAM bassa", 5, 25, 150, 25))),
      "nibp" to mapOf("title" to "NIBP", "limits" to listOf(listOf("nibp_sys_high", "Sistolica alta", 5, 50, 300), listOf("nibp_sys_low", "Sistolica bassa", 5, 30, 250),
                                                         listOf("nibp_map_high", "PAM alta", 5, 40, 205, 205), listOf("nibp_map_low", "PAM bassa", 5, 25, 150, 25))),
      "temp" to mapOf("title" to "Temperatura", "limits" to listOf(listOf("temp_high", "Limite alto", 1, 340, 421, 421), listOf("temp_low", "Limite basso", 1, 299, 380, 299))),
      "etco2" to mapOf("title" to "EtCO₂", "limits" to listOf(listOf("etco2_high", "Limite alto", 1, 10, 150), listOf("etco2_low", "Limite basso", 1, 1, 90))),
      "rr" to mapOf("title" to "FR", "limits" to listOf(listOf("rr_high", "Limite alto", 1, 5, 60), listOf("rr_low", "Limite basso", 1, 1, 50))),
    )
}

// MARK: - Pannelli a tutto schermo (ANDROID, audit 21/11)

// ANDROID (audit 21/11): la tela del monitor (`view`, 1024×768) ritaglia i figli e sta fra le due fasce laterali: un
// pannello aggiunto a `view` non copre prese, ☰ e scocca nelle fasce, e finisce SOTTO le parti dei layout montate nella
/** radice dello schermo (fascia sinistra | tela | fascia destra); `view` se le fasce non ci sono */
internal fun MonitorViewController.screenHost(): UIView = SFSideBand.left?.superview ?: view
/** x della tela nella radice (= larghezza della fascia sinistra: la tela sta subito dopo, per costruzione di UkRootView) */
internal fun MonitorViewController.canvasX(): Double = if (screenHost() === view) 0.0 else (SFSideBand.left?.frame?.let { it.x + it.width } ?: 0.0)
/** riquadro della tela in coordinate della radice */
// ANDROID (ICU 21/11): larghezza di progetto (1024), non quella della vista: con l'ICU a tutto schermo la vista è larga 1228
internal fun MonitorViewController.canvasFrameInHost(): CGRect = CGRectMake(canvasX(), 0, SF_CANVAS_W, view.bounds.size.height)
/** velo scuro su tutto lo schermo: assorbe ogni tocco (niente tasti premuti sotto); un tocco fuori dal pannello chiama onOutside */
internal fun MonitorViewController.fullScreenScrim(alpha: Double, onOutside: () -> Unit): UIView {
    val host = screenHost()
    val s = UIView(host.bounds)
    s.backgroundColor = UIColor.colorWithWhite(0, alpha)
    val bg = UIButton.buttonWithType(UIButtonTypeCustom); bg.frame = s.bounds
    bg.addTarget(UIControlEventTouchUpInside) { onOutside() }
    s.addSubview(bg)
    return s
}
/** splash di avvio (tag 7777), ovunque sia montata */
internal fun MonitorViewController.splashView(): UIView? = screenHost().viewWithTag(7777)
internal fun MonitorViewController.removeSplash() { splashView()?.removeFromSuperview() }

// MARK: - Menu del parametro

internal fun MonitorViewController.connectSensor(b: UIButton) {
    val k = b.accessibilityIdentifier
    val on = mapOf("ecg" to snap.ecgConnected, "spo2" to snap.spo2Connected, "art" to snap.artConnected,
                   "etco2" to snap.etco2Connected, "nibp" to snap.nibpConnected, "temp" to snap.tempConnected)
    if (on[k] == true) return                                    // mai scollegare con un tocco
    state.applyCommand(mapOf("command" to "set_sensor", "value" to mapOf("sensor" to k, "connected" to true)))
}

internal fun MonitorViewController.hitParam(b: UIButton) {
    val order = listOf("hr", "spo2", "art", "etco2", "rr", "nibp")
    openMenu(order[b.uiTag])
}

internal fun MonitorViewController.colorFor(k: String): UIColor {
    val c = mapOf("hr" to SL_GREEN, "spo2" to SL_CYAN, "art" to SL_RED, "nibp" to SL_NIBP, "etco2" to SL_WHITE, "rr" to SL_WHITE, "temp" to SL_TEMP)
    return c[k] ?: SL_WHITE
}

internal fun MonitorViewController.openMenu(param: String) {
    menuDim?.removeFromSuperview()
    menuParam = param
    val cfg = SFMenuConfig()[param]!!
    @Suppress("UNCHECKED_CAST")
    val limits = cfg["limits"] as List<List<Any>>
    val col = colorFor(param)
    val nibp = param == "nibp"

    // ANDROID (audit 21/11): velo su tutto lo schermo (anche le fasce), pannello centrato sullo schermo intero
    val menuDim = fullScreenScrim(0.45) { closeMenu() }
    this.menuDim = menuDim

    val extra = if (param == "art") 56.0 else if (param == "temp") 56.0 else 0.0   // ANDROID (IN-03/IN-13): Zero, PVC, T2
    val W = 420.0; val rowH = 52.0; val H = 70 + rowH * (limits.size + 1) + (if (nibp) 120 else 0) + 70 + extra
    val menuPanel = UIView(CGRectMake(floor((menuDim.bounds.size.width - W) / 2), floor((menuDim.bounds.size.height - H) / 2), W, H))
    this.menuPanel = menuPanel
    menuPanel.backgroundColor = UIColor.colorWithWhite(0.09, 0.98)
    menuPanel.layer.cornerRadius = 10.0; menuPanel.layer.borderWidth = 2.0; menuPanel.layer.borderColor = col.CGColor
    menuDim.addSubview(menuPanel)

    val title = UILabel(CGRectMake(20, 14, W - 40, 30))
    title.text = String.format(Locale.US, "%s — allarmi", cfg["title"])
    title.textColor = col; title.font = UIFont.boldSystemFontOfSize(22)
    menuPanel.addSubview(title)

    var y = 60.0
    // interruttore allarme
    val swl = UILabel(CGRectMake(20, y, 200, 30))
    swl.text = "Allarme"; swl.textColor = SL_WHITE; swl.font = UIFont.systemFontOfSize(18)
    menuPanel.addSubview(swl)
    val menuSwitch = UISwitch()
    this.menuSwitch = menuSwitch
    menuSwitch.frame = CGRectMake(W - 20 - menuSwitch.bounds.size.width, y, menuSwitch.bounds.size.width, menuSwitch.bounds.size.height)
    menuSwitch.onTintColor = col
    menuSwitch.addTarget(UIControlEventValueChanged) { menuSwitchChanged() }
    menuPanel.addSubview(menuSwitch)
    val menuSwitchText = UILabel(CGRectMake(20, y + 28, W - 40, 18))
    this.menuSwitchText = menuSwitchText
    menuSwitchText.textColor = SL_GREY; menuSwitchText.font = UIFont.systemFontOfSize(12)
    menuPanel.addSubview(menuSwitchText)
    y += rowH + 4

    // limiti
    menuSteppers = LinkedHashMap(); menuValues = LinkedHashMap()
    for (l in limits) {
        val n = UILabel(CGRectMake(20, y, 160, 30))
        n.text = l[1] as String; n.textColor = SL_WHITE; n.font = UIFont.systemFontOfSize(18)
        menuPanel.addSubview(n)
        val v = UILabel(CGRectMake(180, y - 4, 80, 38))
        v.textColor = col; v.font = UIFont.boldSystemFontOfSize(28); v.textAlignment = NSTextAlignmentRight
        menuPanel.addSubview(v)
        val st = UIStepper()
        st.frame = CGRectMake(W - 20 - st.bounds.size.width, y, st.bounds.size.width, st.bounds.size.height)
        st.stepValue = (l[2] as Number).toDouble(); st.minimumValue = (l[3] as Number).toDouble(); st.maximumValue = (l[4] as Number).toDouble()
        if (l.size > 5) menuOffAt[l[0] as String] = (l[5] as Number).toInt()
        st.tintColor = col; st.accessibilityIdentifier = l[0] as String
        st.addTarget(UIControlEventValueChanged) { menuStepperChanged(it as UIStepper) }
        menuPanel.addSubview(st)
        menuSteppers[l[0] as String] = st; menuValues[l[0] as String] = v
        y += rowH
    }
    if (param == "art" || nibp || param == "temp" || param == "spo2") {
        val note = UILabel(CGRectMake(20, y - 10, W - 40, 16))
        note.text = if (param == "temp") "Valgono per T1 e T2 · OFF = limite spento" else if (param == "spo2") "Limite alto OFF di serie (post-ROSC: 98)" else "Limiti propri (ART e NIBP separati) · PAM OFF = spenta"
        note.textColor = SL_GREY; note.font = UIFont.systemFontOfSize(12)
        menuPanel.addSubview(note); y += 10
    }
    // ANDROID (funzioni mancanti 22/11): ART: Zero e trasduttore PVC; temperatura: sonda centrale T2
    if (param == "art" || param == "temp") {
        val items = if (param == "art") listOf(Pair("Zero (all'aria)") { menuZero() }, Pair(if (snap.cvpConnected) "Scollega PVC" else "Collega PVC") { menuSensor("cvp") })
                    else listOf(Pair(if (snap.temp2Connected) "Scollega T2" else "Collega T2 (centrale)") { menuSensor("temp2") })
        val bw = floor((W - 40 - (items.size - 1) * 10) / items.size)
        for ((i, it) in items.withIndex()) {
            val b = UIButton.buttonWithType(UIButtonTypeSystem)
            b.frame = CGRectMake(20 + i * (bw + 10), y, bw, 42)
            b.setTitle(it.first, UIControlStateNormal); b.accessibilityIdentifier = if (i == 0 && param == "art") "MENU-ZERO" else "MENU-SENS-$i"
            b.tintColor = col; b.titleLabel.font = UIFont.boldSystemFontOfSize(17)
            b.layer.borderColor = col.CGColor; b.layer.borderWidth = 1.5; b.layer.cornerRadius = 6.0
            val act = it.second
            b.addTarget(UIControlEventTouchUpInside) { act() }
            menuPanel.addSubview(b)
        }
        y += 56
    }

    // NIBP: misura automatica a intervalli
    menuInterval = null
    if (nibp) {
        val ni = UILabel(CGRectMake(20, y, W - 40, 26))
        ni.text = "Misura automatica ogni (minuti)"; ni.textColor = SL_WHITE; ni.font = UIFont.systemFontOfSize(18)
        menuPanel.addSubview(ni)
        val items = ArrayList<String>()
        for (m in SimulationState.nibpIntervals()) items.add(if (m != 0) m.toString() else "Off")
        val menuInterval = UISegmentedControl(items)
        this.menuInterval = menuInterval
        menuInterval.frame = CGRectMake(20, y + 32, W - 40, 34)
        menuInterval.tintColor = col
        menuInterval.addTarget(UIControlEventValueChanged) { menuIntervalChanged() }
        menuPanel.addSubview(menuInterval)
        val now = UIButton.buttonWithType(UIButtonTypeSystem)
        now.frame = CGRectMake(20, y + 74, W - 40, 36)
        now.setTitle("Avvia misura adesso", UIControlStateNormal)
        now.tintColor = col; now.titleLabel.font = UIFont.boldSystemFontOfSize(17)
        now.layer.borderColor = col.CGColor; now.layer.borderWidth = 1.5; now.layer.cornerRadius = 6.0
        now.addTarget(UIControlEventTouchUpInside) { startNibp() }
        menuPanel.addSubview(now)
        y += 120
    }

    val menuError = UILabel(CGRectMake(20, y, W - 40, 18))
    this.menuError = menuError
    menuError.textColor = SL_AMBER; menuError.font = UIFont.systemFontOfSize(13)
    menuPanel.addSubview(menuError)
    val close = UIButton.buttonWithType(UIButtonTypeSystem)
    close.frame = CGRectMake(W - 140, H - 52, 120, 38)
    close.setTitle("Chiudi", UIControlStateNormal)
    close.tintColor = SL_WHITE; close.titleLabel.font = UIFont.boldSystemFontOfSize(18)
    close.layer.borderColor = SL_GREY.CGColor; close.layer.borderWidth = 1.0; close.layer.cornerRadius = 6.0
    close.addTarget(UIControlEventTouchUpInside) { closeMenu() }
    menuPanel.addSubview(close)

    screenHost().addSubview(menuDim)
    refreshMenu()
}

internal fun MonitorViewController.refreshMenu() {
    val menuParam = menuParam ?: return
    val s = snap
    val on = s.alarmOn(menuParam)
    menuSwitch?.isOn = on
    menuSwitchText?.text = if (on) "attivo" else (if (menuParam == "hr")
        "DISATTIVATO — FV, TV e asistolia danno comunque allarme" else "DISATTIVATO finché non lo riattivi")
    for (k in menuSteppers.keys) {
        val v = s.limit(k); val off = menuOffAt[k]
        menuSteppers[k]!!.value = (if (v == 0 && off != null) off else v).toDouble()
        menuValues[k]!!.text = menuLimitText(k, v)
    }
    menuInterval?.let {
        val idx = SimulationState.nibpIntervals().indexOf(s.nibpIntervalMin)
        it.selectedSegmentIndex = if (idx < 0) 0 else idx
    }
}

internal fun MonitorViewController.menuSwitchChanged() {
    state.applyCommand(mapOf("command" to "set_alarm_enabled", "value" to mapOf("param" to menuParam, "enabled" to (menuSwitch?.isOn == true))))
}

internal fun MonitorViewController.menuStepperChanged(st: UIStepper) {
    val id = st.accessibilityIdentifier ?: ""
    val off = menuOffAt[id]
    val v = if (off != null && st.value.toInt() == off) 0 else st.value.toInt()          // ANDROID (IN-09): valore «spento» = 0
    val err = state.applyCommand(mapOf("command" to "set_alarm_limits", "value" to mapOf(id to v)))
    if (err != null) {
        menuError?.text = if (err.contains("lower") || err.contains("higher")) "Il limite basso deve restare sotto quello alto" else err
        val cur = snap.limit(id); st.value = (if (cur == 0 && off != null) off else cur).toDouble()
    } else {
        menuError?.text = ""
        menuValues[id]?.text = menuLimitText(id, v)
    }
}

/** ANDROID (IN-09): testo del limite nel menu (OFF, °C per la temperatura) */
internal fun menuLimitText(k: String, v: Int): String = if (v == 0 && SLOffableLimits.contains(k)) "OFF"
    else if (k.startsWith("temp")) String.format(Locale.ITALY, "%.1f", v / 10.0) else String.format(Locale.US, "%d", v)
internal val menuOffAt = HashMap<String, Int>()
/** ANDROID (IN-03): Zero dal menu ART (azzera ART e PVC collegate) */
internal fun MonitorViewController.menuZero() {
    val err = state.applyCommand(mapOf("command" to "art_zero"))
    menuError?.text = if (err == null) "Zero in corso: 3 s con il trasduttore aperto all'aria" else "Nessun trasduttore di pressione collegato"
}
/** ANDROID (IN-13): collega/scollega PVC o T2 dal menu del parametro */
internal fun MonitorViewController.menuSensor(k: String) {
    val on = if (k == "cvp") snap.cvpConnected else snap.temp2Connected
    state.applyCommand(mapOf("command" to "set_sensor", "value" to mapOf("sensor" to k, "connected" to !on)))
    val p = menuParam; snap = state.snapshot(); if (p != null) openMenu(p)
}

internal fun MonitorViewController.menuIntervalChanged() {
    val m = SimulationState.nibpIntervals()[menuInterval!!.selectedSegmentIndex]
    state.applyCommand(mapOf("command" to "set_nibp_interval", "value" to m))
}

internal fun MonitorViewController.closeMenu() {
    menuDim?.removeFromSuperview(); menuDim = null; menuPanel = null; menuParam = null
}

// MARK: - Azioni locali

internal fun MonitorViewController.toggleMute() {
    val muted = snap.alarmsMuted
    state.applyCommand(mapOf("command" to "mute_alarms", "value" to !muted))
}

internal fun MonitorViewController.startNibp() {
    state.startNibpMeasurement()
}

// Refill capillare: "--" finché non lo si valuta; tocco = pressione sul letto ungueale (~3 s), poi il tempo.
// Se l'istruttore cambia il valore, va rivalutato.
internal fun MonitorViewController.assessCrt() { crtAssessAt = CACurrentMediaTime(); updateCrt() }
internal fun MonitorViewController.updateCrt() {
    val s = snap
    if (s.crtAssessSeq != crtSeqSeen) {                       // valutazione chiesta dall'istruttore dal controller
        crtSeqSeen = s.crtAssessSeq; if (s.crtAssessSeq > 0) crtAssessAt = CACurrentMediaTime()
    }
    val on = s.crtEnabled                                    // disattivabile dall'istruttore
    crtTitle.hidden = !on; crtValue.hidden = !on; crtLine.hidden = !on; crtHit.hidden = !on
    if (!on) return
    val now = CACurrentMediaTime()
    if (crtAssessAt > 0 && now - crtAssessAt >= 3.0) { crtShown = s.crtTenths; crtAssessAt = 0.0 }
    if (crtAssessAt > 0) { crtValue.text = "…"; return }
    if (crtShown != s.crtTenths) { crtShown = -1; crtValue.text = "--"; return }
    crtValue.text = if (s.crtTenths % 10 != 0) String.format(Locale.US, "%.1f", s.crtTenths / 10.0)
                    else String.format(Locale.US, "%d", s.crtTenths / 10)
}

// MARK: - Layout del monitor

// classe della vista di un layout di dispositivo (nil = layout ICU o layout non disponibile in questa versione)
internal fun MonitorViewController.classForLayout(layout: String?): ((CGRect, SimulationState, WaveformEngine, SoundEngine) -> UIView)? {
    if (layout == "extrah") return { f, st, en, so -> ExtraHView(f, st, en, so) }
    if (layout == "dae") return { f, st, en, so -> GenericAedView(f, st, en, so) }
    // ANDROID (categorie dei layout 22/11): i layout personalizzati dal loro registro (SFCustomLayouts, vuoto nella build pubblica)
    SFCustomLayoutFor(layout)?.let { return it.make }
    return null
}

internal fun MonitorViewController.applyLayout(layout0: String) {
    var layout = layout0
    if (layout != "generic_icu" && classForLayout(layout) == null) layout = "generic_icu"   // layout non disponibile: si torna a ICU
    // ANDROID (BugFix #1, #1b): cambiando layout il dispositivo riparte sempre in monitoraggio e nessun metronomo resta acceso
    val prevLayout = layoutShown
    if (prevLayout != null && prevLayout != layout) resetDeviceForLayoutChange(prevLayout, layout)
    // ANDROID (audit 21/11): il menu allarmi del parametro è di ICU: non resta aperto (né torna a galla) su un altro layout
    if (prevLayout != layout) closeMenu()
    layoutShown = layout
    prefs.setString("sf_layout", layout)
    val lp = layout != "generic_icu"
    // ANDROID (ICU 21/11): ICU a tutto schermo (tela larga sulle fasce); un layout di dispositivo riporta la tela a 1024 fra
    // le fasce PRIMA di crearne la vista (SFIcuWide.kt)
    fitCanvasForLayout(!lp || SFIsWideLayout(layout))
    if (lp && altKind != layout) {             // crea la vista del layout richiesto (una alla volta)
        alt?.removeFromSuperview()
        val cls = classForLayout(layout)!!
        alt = cls(view.bounds, state, engine, sound)
        altKind = layout
        altL!!.onLayoutMenu = { showLayoutMenu() }
        altL!!.onInfo = { toggleInfo() }
        altL!!.onVolumes = { showVolumes() }
        view.addSubview(alt!!)
    }
    if (lp && drawerOpen) toggleDrawer()
    alt?.hidden = !lp
    SFSideBand.syncVisibility()          // ANDROID: prese e scocca nelle fasce solo con il layout del dispositivo a schermo
    val now = CACurrentMediaTime()
    if (lp) { altL!!.setDevicePowered(state.devicePowered); powerPushed = state.devicePowered
              altL!!.resetTracesAt(now); altL!!.updateWithSnapshot(snap); view.bringSubviewToFront(alt!!)
              val sp = splashView(); if (sp != null) sp.superview?.bringSubviewToFront(sp) }
    else for (t in traces()) t.resetAtTime(now)
    standby?.let { it.hidden = lp || state.devicePowered }
    altFooter.hidden = !lp
    layoutAltFooter(layout)              // ANDROID (BugFix #11/#13): posizione del riquadro "Controller" per layout
    altFooter.hidden = true              // ANDROID: sostituito dal tasto ☰ del menu dell'istruttore (SFMonitorMenu.kt)
    placeMenuButton()
    if (lp) view.bringSubviewToFront(altFooter)
    hideIcuUnder(lp)
    SFLog.log(String.format(Locale.US, "layout: %s", layout))
}

// ANDROID (BugFix #1, #1b): al cambio di layout
// - la terapia elettrica in corso si chiude con i comandi normali (pacing fermato, poi modalità "off" = monitoraggio:
//   disarma la carica e chiude il DAE; il registro della sessione lo annota solo se una terapia era davvero attiva);
//   il metronomo RCP del DAE (DefibDrawerView, suona in ogni layout finché il DAE è in fase RCP) si ferma con lui;
// - la vista del dispositivo precedente viene eliminata (non solo nascosta): il layout si ricrea pulito alla prossima
// - voce del DAE e toni di carica si zittiscono subito.
internal fun MonitorViewController.resetDeviceForLayoutChange(from: String, to: String) {
    val d = state.snapshot().defib
    val therapy = d.mode != SLDefibOff || d.paceRunning
    if (d.paceRunning) state.applyCommand(mapOf("command" to "defib_pacer", "value" to mapOf("running" to false)))
    if (d.mode != SLDefibOff) state.applyCommand(mapOf("command" to "defib_mode", "value" to "off"))
    if (alt != null) {
        alt?.removeFromSuperview(); alt = null; altKind = null
    }
    SFVoice.stop()
    sound.setDefibTone(0, 0.0)
    snap = state.snapshot()
    if (therapy) SFLog.log(String.format(Locale.US, "cambio layout %s -> %s: terapia elettrica chiusa, dispositivo in monitoraggio", from, to))
}

// ANDROID (BugFix #11/#13): riquadro "Controller" (indirizzo + (i)) dei layout dei dispositivi, posizionato per layout:
//   laterali, in alto come la cornice dello schermo); compatto, icona sopra e testo sotto
//   inferiore dei tasti (x 101) e sotto di essa (y 716): non più a cavallo del bordo del pannello
// - gli altri layout: come prima (in basso a sinistra, zona libera)
internal fun MonitorViewController.layoutAltFooter(layout: String?) {
    // ANDROID (BugFix #11/#13, rifatto dopo le prove): UNA regola per tutti i layout dei dispositivi. Il riquadro
    // "Controller" sta FUORI dall'apparecchio, in fondo alla fascia laterale destra, centrato nella fascia: non copre
    // mai tasti, schermo o bordi del dispositivo e ha lo stesso posto in ogni modello. Con ICU resta in basso a sinistra.
    val H = view.bounds.size.height
    val band = SFSideBand.right
    if (layout != null && layout != "generic_icu" && band != null && band.bounds.width >= 60) {
        if (altFooter.superview !== band) band.addSubview(altFooter)
        val bw = band.bounds.width; val w = bw - 16; val h = 62.0
        altFooter.frame = CGRectMake(8, H - 10 - h, w, h)
        altFooterIcon.frame = CGRectMake((w - 22) / 2, 6, 22, 22)
        altFooterText.frame = CGRectMake(4, 29, w - 8, 30)
        altFooterText.textAlignment = NSTextAlignmentCenter; altFooterText.minimumScaleFactor = 0.6
        band.bringSubviewToFront(altFooter)
        Uk.after(0.4) { if (altFooter.superview === band) band.bringSubviewToFront(altFooter) }   // sopra la scocca che arriva dopo
    } else {
        if (altFooter.superview !== view) view.addSubview(altFooter)
        altFooter.frame = CGRectMake(4, H - 46, 116, 42)
        altFooterIcon.frame = CGRectMake(2, 9, 24, 24)
        altFooterText.frame = CGRectMake(27, 3, 86, 36)
        altFooterText.textAlignment = NSTextAlignmentLeft; altFooterText.minimumScaleFactor = 0.6
    }
}

// Con un layout di dispositivo a schermo, la schermata ICU sotto non si vede ma continuava ad aggiornarsi (allarmi
// che lampeggiano, valori): il compositore di sistema la ricomponeva lo stesso a ogni fotogramma. Ora le sue viste
// vengono nascoste finché è attivo il dispositivo, e ripristinate esattamente com'erano quando si torna a ICU.
internal fun MonitorViewController.hideIcuUnder(hide: Boolean) {
    if (hide) {
        if (icuHidden != null) return
        val icuHidden = ArrayList<UIView>()
        this.icuHidden = icuHidden
        val keep = listOf<UIView?>(alt, altFooter, infoPanel)
        for (v in view.subviews) {
            if (keep.any { it === v } || v.hidden || v === volPanel || v === layoutMenu || v === pauseCover || v === drawer || v.uiTag == 7777) continue
            v.hidden = true; icuHidden.add(v)
        }
    } else {
        icuHidden?.let { for (v in it) v.hidden = false }
        icuHidden = null
    }
}

// ANDROID (audit 21/11): la scelta del layout, il nome e la versione stanno SOLO nel menu ☰ dell'istruttore (BugFix 22).
// Il vecchio pannello «Layout del monitor» (con «similFranz Monitor v…») si apriva ancora toccando il titolo «ICU»:
// ora ogni richiesta di menu dei layout porta al menu ☰, sottomenu Layout.
internal fun MonitorViewController.showLayoutMenu() {
    closeLayoutMenu()
    openMonitorMenu(true)
}
internal fun MonitorViewController.closeLayoutMenu() { layoutMenu?.removeFromSuperview(); layoutMenu = null }
internal fun MonitorViewController.standbyTapped() {
    state.devicePowered = true
    standby?.hidden = true
}
// Azzera contatori, stati e sonde e spegne il monitor (stessa azione del "Reset scenario" della web app).
internal fun MonitorViewController.confirmResetScenario() {
    closeLayoutMenu()
    val st = state
    val a = UIAlertController.alertControllerWithTitle("Azzerare lo scenario?",
        "Il paziente torna alle condizioni iniziali, le sonde vengono staccate, contatori e registro si azzerano e il monitor si spegne.",
        UIAlertControllerStyleAlert)
    a.addAction(UIAlertAction.actionWithTitle("Annulla", UIAlertActionStyleCancel, null))
    val onReset = onResetScenario
    a.addAction(UIAlertAction.actionWithTitle("Azzera e spegni", UIAlertActionStyleDestructive) {
        if (onReset != null) onReset() else st.applyCommand(mapOf("command" to "reset_scenario"))
    })
    presentViewController(a, true, null)
}
internal fun MonitorViewController.layoutChosen(b: UIButton) {
    state.applyCommand(mapOf("command" to "set_layout", "value" to b.accessibilityIdentifier))
    closeLayoutMenu()
}

/** [self presentViewController:animated:completion:] — qui l'avviso si presenta sopra la vista del monitor */
internal fun MonitorViewController.presentViewController(a: UIAlertController, animated: Boolean, completion: (() -> Unit)?) {
    a.present()
    completion?.invoke()
}

// Schermata di avvio: montata PRIMA del primo frame (stesso disegno dell'immagine di avvio, quindi il passaggio non si vede).
// La barra segue il caricamento vero: ogni fase prepara ciò che al primo uso è lento sull'A5.
internal fun MonitorViewController.showSplash(staticOnly: Boolean) {
    removeSplash()
    val sp = SFSplashView(CGRectMake(0, 0, SF_CANVAS_W, view.bounds.size.height), MVCBundleVersion())   // ANDROID (ICU 21/11): tela di progetto
    sp.uiTag = 7777
    // ANDROID (audit 21/11): la splash sta nella RADICE, al posto della tela (le sue fasce sono i "backdrops" di SFSplashView):
    sp.frame = canvasFrameInHost()
    screenHost().addSubview(sp)
    if (staticOnly) { sp.makeStatic(); return }
    sp.runStages(listOf(
        Pair("Tracciati e parametri vitali", {
            snap = state.snapshot(); engine.updateWithSnapshot(snap); updateNumerics() }),
        Pair("Audio e istruzioni vocali", { SFVoice.start(Uk.ctx) }),
        Pair("Rete Wi-Fi", { SFWifi.shared().currentSSID(); refreshNetwork() }),
        Pair("Collegamento del controller", { connect.refresh() }),   // aggiorna rete, indirizzo e PIN
    ), 1.25, null)
}
// verifica da Mac: splash?static=1 (fotogramma fisso per l'immagine di avvio), splash?off=1, splash (animazione completa)
fun MonitorViewController.debugSplash(query: String?) {
    if (query?.contains("off=1") == true) { removeSplash(); return }
    showSplash(query?.contains("static=1") == true)
}

fun MonitorViewController.saveShot(name: String) {
    val img: Bitmap = (view.window ?: view).snapshotBitmap()   // ANDROID: tutto lo schermo, fasce laterali comprese
    val dir = File(Uk.ctx.cacheDir, "selftest")
    dir.mkdirs()
    try { FileOutputStream(File(dir, "$name.png")).use { img.compress(Bitmap.CompressFormat.PNG, 100, it) } } catch (e: Exception) { }
}

// ANDROID (fine scenario 22/11): quando il monitor ha visto la fine dello scenario (per i 3 minuti del sottotitolo)
private object EndSub { const val SECONDS = 180.0; var since = 0.0 }

// Pausa dal controller: schermata a tutto schermo sopra qualsiasi layout
internal fun MonitorViewController.updatePauseCover(s: SLSnapshot) {
    val paused = !s.simulationRunning
    // ANDROID (21/11, segnalato dall'utente): la copertura va nella RADICE e copre anche le fasce laterali (prese, ☰),
    // non solo la tela centrale: prima ai lati restavano visibili prese e scocca del layout
    val host = SFSideBand.left?.superview ?: view
    if (paused && pauseCover == null) {
        val pauseCover = UIView(host.bounds)
        this.pauseCover = pauseCover
        pauseCover.backgroundColor = UIColor.colorWithWhite(0, 0.86)
        val l = UILabel(CGRectInset(pauseCover.bounds, 40, 200))
        l.numberOfLines = 2; l.textAlignment = NSTextAlignmentCenter; l.textColor = UIColor.whiteColor
        l.font = UIFont.boldSystemFontOfSize(88); l.adjustsFontSizeToFitWidth = true
        l.text = "Scenario in\nPAUSA"
        l.uiTag = 7801                       // ANDROID (fine scenario 22/11): testi cambiati da «Scenario terminato»
        pauseCover.addSubview(l)
        val sub = UILabel(CGRectMake(0, pauseCover.bounds.size.height - 150, pauseCover.bounds.size.width, 40))
        sub.textAlignment = NSTextAlignmentCenter; sub.textColor = UIColor.colorWithWhite(0.7, 1); sub.font = UIFont.systemFontOfSize(22)
        sub.text = "La simulazione riprende dal controller"
        sub.uiTag = 7802                     // ANDROID (fine scenario 22/11)
        pauseCover.addSubview(sub)
        host.addSubview(pauseCover)
        keepMenuButtonOnTop()                // ANDROID (ICU 21/11): il ☰ resta sopra la pausa
    }
    // ANDROID (audit 21/11): in cima SOLO quando la pausa comincia, non a ogni giro (4 volte al secondo): così ciò che
    // compare DOPO nella radice resta sopra la pausa, in particolare la richiesta «Associa» di un controller (prima finiva
    // sotto la pausa: invisibile e senza risposta) e l'avviso «Controller collegato». Le parti dei layout stanno nelle
    // fasce o subito sopra la tela, quindi restano comunque sotto.
    val c = pauseCover ?: return
    // ANDROID (fine scenario 22/11): end_scenario = la stessa copertura (è già una pausa) con «Scenario terminato» e
    // il sottotitolo del debriefing; quasi opaca, perché il paziente non serve più ai partecipanti
    val ended = paused && s.scenarioEnded
    val title = if (ended) "Scenario\nterminato" else "Scenario in\nPAUSA"
    val tl = c.viewWithTag(7801) as? UILabel
    if (tl != null && tl.text != title) {
        tl.text = title
        c.backgroundColor = UIColor.colorWithWhite(0, if (ended) 0.96 else 0.86)
    }
    // ANDROID (fine scenario 22/11, richiesta utente): per 3 minuti «Lasciate tutto com'è e andate in sala debriefing» con una
    // barra che si svuota, poi «Debriefing in corso». Il tempo parte quando il monitor vede la fine (ora reale, non di scenario).
    if (!ended) EndSub.since = 0.0 else if (EndSub.since == 0.0) EndSub.since = CACurrentMediaTime()
    val left = if (ended) EndSub.SECONDS - (CACurrentMediaTime() - EndSub.since) else 0.0
    val subText = if (!ended) "La simulazione riprende dal controller"
                  else if (left > 0) "Lasciate tutto com'è e andate in sala debriefing" else "Debriefing in corso"
    val sl = c.viewWithTag(7802) as? UILabel
    if (sl != null && sl.text != subText) {
        sl.text = subText
        sl.font = if (ended && left > 0) UIFont.boldSystemFontOfSize(28) else UIFont.systemFontOfSize(22)
        sl.textColor = UIColor.colorWithWhite(if (ended && left > 0) 0.95 else 0.7, 1)
    }
    var track = c.viewWithTag(7803)
    if (track == null) {
        val W = 520.0; val y = c.bounds.size.height - 98
        track = UIView(CGRectMake((c.bounds.size.width - W) / 2, y, W, 8)); track.uiTag = 7803
        track.backgroundColor = UIColor.colorWithWhite(1, 0.15); track.layer.cornerRadius = 4.0; track.clipsToBounds = true
        val fill = UIView(CGRectMake(0, 0, W, 8)); fill.uiTag = 7804; fill.backgroundColor = UIColor.colorWithWhite(1, 0.85); fill.layer.cornerRadius = 4.0
        track.addSubview(fill); c.addSubview(track)
    }
    track.hidden = !(ended && left > 0)
    if (!track.hidden) {
        val f = track.viewWithTag(7804)
        val w = Math.round(track.bounds.size.width * (left / EndSub.SECONDS).coerceIn(0.0, 1.0)).toDouble()
        if (f != null && f.frame.size.width != w) f.frame = CGRectMake(0, 0, w, 8)
    }
    // ANDROID (ICU 21/11, decisione dell'utente): il ☰ dell'istruttore resta SOPRA la pausa, toccabile, con il suo pallino
    if (paused && c.hidden) { c.hidden = false; host.bringSubviewToFront(c); keepMenuButtonOnTop() }
    else if (!paused && !c.hidden) c.hidden = true
}

/** [_alt valueForKey:@"_keys"]: il dizionario dei tasti del layout (campo `keys` o `_keys`), se c'è */
private fun MVCKeysOf(v: Any): Map<*, *>? {
    var c: Class<*>? = v.javaClass
    while (c != null) {
        for (f in c.declaredFields) {
            if (f.name == "keys" || f.name == "_keys") {
                try { f.isAccessible = true; (f.get(v) as? Map<*, *>)?.let { return it } } catch (e: Exception) { }
            }
        }
        c = c.superclass
    }
    return null
}
/** [_alt respondsToSelector:@selector(tapKey:)] */
private fun MVCTapKeyMethod(v: Any): java.lang.reflect.Method? {
    var c: Class<*>? = v.javaClass
    while (c != null) {
        for (m in c.declaredMethods) {
            if ((m.name == "tapKey" || m.name.startsWith("tapKey$")) && m.parameterTypes.size == 1 && m.parameterTypes[0] == String::class.java) {
                try { m.isAccessible = true; return m } catch (e: Exception) { }
            }
        }
        c = c.superclass
    }
    return null
}

fun MonitorViewController.debugTap(k: String) {
    if (k == "VOLUMI") { showVolumes(); return }          // verifica da Mac: pannello volumi
    if (k == "INFO") { toggleInfo(); return }
    if (k == "WIFI") { if (infoPanel.hidden) toggleInfo(); connect.showWifi(); return }     // pagina Reti Wi-Fi
    if (k.startsWith("JOIN:")) {                                                                                   // JOIN:nome[:password]
        val a = k.substring(5).split(":")
        connect.debugJoin(a[0], if (a.size > 1) a[1] else null); return
    }
    if (k == "STANDBY") { standbyTapped(); return }       // verifica da Mac: accende dallo standby
    // ANDROID (audit 21/11): verifica da Mac delle schermate sovrapposte (pausa, menu ☰, avviso, menu allarmi ICU)
    if (k == "PAUSA") { state.applyCommand(mapOf("command" to "set_simulation_running", "value" to !state.snapshot().simulationRunning)); return }
    if (k == "MENU-ISTR") { openMonitorMenu(false); return }      // (MENU resta il tasto Menu dei layout)
    if (k == "MENU-CHIUDI") { closeMonitorMenu(); hideVolumes(); infoPanel.hidden = true; closeMenu(); return }
    if (k == "TOAST") { menuToast("Controller collegato: prova", true); menuToast("Controller scollegato: prova", false); return }
    if (k == "ALLARMI-FC" && alt?.hidden != false) { openMenu("hr"); return }
    // ANDROID (funzioni mancanti 22/11): prove da Mac dell'ICU: menu ART/NIBP/SpO2/T, Zero, Trend
    if (k.startsWith("ALLARMI-") && alt?.hidden != false) { openMenu(k.substring(8).lowercase(Locale.ROOT)); return }
    if (k == "ZERO" && alt?.hidden != false) { menuZero(); return }
    if (k == "TREND" && alt?.hidden != false) { toggleTrend(); return }
    if (k.startsWith("T-") && trendView != null) { trendView?.tap(k); return }
    val alt = alt
    val tapKey = if (alt != null && !alt.hidden) MVCTapKeyMethod(alt) else null
    if (alt != null && !alt.hidden && tapKey != null) {
        if (k.startsWith("T-")) { tapKey.invoke(alt, k); return }       // ANDROID (IN-14): tasti della finestra Trend del layout
        val keys = MVCKeysOf(alt)
        if (keys?.get(k) == null) { SFLog.log(String.format(Locale.US, "tap %s: tasto non trovato. Tasti: %s", k, (keys?.keys?.map { it.toString() } ?: emptyList()).sorted().joinToString(" "))); return }
        tapKey.invoke(alt, k)
        return
    }
    if (k == "DEFIB") { toggleDrawer(); return }
    if (k == "DEFIB-CHIUDI") { if (drawerOpen) toggleDrawer(); return }   // ANDROID (ICU 21/11): verifica da Mac, chiude soltanto
    if (k == "SILENZIA") { muteButton.sendActionsForControlEvents(UIControlEventTouchUpInside); return }
    if (k == "NIBP") { nibpButton.sendActionsForControlEvents(UIControlEventTouchUpInside); return }
    if (!drawerOpen) toggleDrawer()
    drawer.tapNamed(k)
}

internal fun MonitorViewController.toggleDrawer() {
    drawerOpen = !drawerOpen
    val H = min(view.bounds.size.width, view.bounds.size.height)
    var f = drawer.frame
    drawerTab.setTitle(if (drawerOpen) "DEFIB  ▼" else "DEFIB  ▲", UIControlStateNormal)
    drawer.active = drawerOpen                      // in chiusura: nessun tocco arriva più ai pulsanti del cassetto
    drawer.userInteractionEnabled = drawerOpen
    SFLog.log(String.format(Locale.US, "cassetto %s (modo %d, piastre %d, carica %d)", if (drawerOpen) "aperto" else "chiuso",
          snap.defib.mode, if (snap.padsConnected) 1 else 0, snap.defib.charge))
    if (drawerOpen) {
        drawer.resetTraceAt(CACurrentMediaTime())
        drawer.updateWithSnapshot(snap)
        drawer.hidden = false
        f = CGRect(f.x, H, f.width, f.height); drawer.frame = f; f = CGRect(f.x, drawerY, f.width, f.height)
        UIView.animateWithDuration(0.25, { drawer.frame = f })
    } else {
        f = CGRect(f.x, H, f.width, f.height)
        UIView.animateWithDuration(0.25, { drawer.frame = f },
                                   { done -> if (!drawerOpen) drawer.hidden = true })
    }
}

// Volumi per canale (salvati): bip ECG, bip SpO2, allarmi, suoni del monitor, istruzioni vocali
internal fun MonitorViewController.showVolumes() {
    infoPanel.hidden = true
    volPanel?.removeFromSuperview()
    // ANDROID (audit 21/11): velo su tutto lo schermo (prima il pannello galleggiava sulla tela e i tocchi attorno premevano
    // i tasti del layout sotto e le prese nelle fasce); pannello centrato sullo schermo intero, tocco fuori = Chiudi
    val scrim = fullScreenScrim(0.45) { hideVolumes() }
    this.volPanel = scrim
    val W = scrim.bounds.size.width; val H = scrim.bounds.size.height; val pw = 580.0; val ph = 64.0 + SFVolCount * 56 + 60
    val volPanel = UIView(CGRectMake(floor((W - pw) / 2), floor((H - ph) / 2), pw, ph))
    scrim.addSubview(volPanel)
    volPanel.backgroundColor = UIColor.colorWithWhite(0.08, 0.97)
    volPanel.layer.borderColor = SL_GREY.CGColor; volPanel.layer.borderWidth = 1.0; volPanel.layer.cornerRadius = 8.0
    val t = UILabel(CGRectMake(20, 12, pw - 40, 28))
    t.text = "Volumi  (relativi al volume generale; salvati)"; t.textColor = SL_WHITE; t.font = UIFont.boldSystemFontOfSize(19)
    volPanel.addSubview(t)
    for (c in 0 until SFVolCount) {
        val y = 56.0 + c * 56
        val n = UILabel(CGRectMake(20, y, 230, 40))
        n.text = SoundEngine.nameForChannel(c); n.textColor = SL_WHITE; n.font = UIFont.systemFontOfSize(15)
        n.numberOfLines = 2; n.adjustsFontSizeToFitWidth = true; n.minimumScaleFactor = 0.7
        volPanel.addSubview(n)
        val sl = UISlider(CGRectMake(256, y + 4, 230, 32))
        sl.minimumValue = 0.0; sl.maximumValue = 1.0; sl.value = sound.gainFor(c); sl.uiTag = c
        sl.minimumTrackTintColor = SL_CYAN
        sl.addTarget(UIControlEventValueChanged) { volChanged(it as UISlider) }
        sl.addTarget(UIControlEventTouchUpInside or UIControlEventTouchUpOutside) { volPreview(it as UISlider) }
        volPanel.addSubview(sl)
        val pct = UILabel(CGRectMake(496, y, 64, 40))
        pct.uiTag = 100 + c; pct.textColor = SL_CYAN; pct.font = UIFont.boldSystemFontOfSize(17); pct.textAlignment = NSTextAlignmentRight
        pct.text = String.format(Locale.US, "%d%%", MVCLround(sl.value * 100))
        volPanel.addSubview(pct)
    }
    val close = button("Chiudi", CGRectMake(pw - 150, ph - 52, 130, 40), SL_GREY) { hideVolumes() }
    close.removeFromSuperview(); volPanel.addSubview(close)
    screenHost().addSubview(scrim)
}
internal fun MonitorViewController.hideVolumes() { volPanel?.removeFromSuperview(); volPanel = null }
internal fun MonitorViewController.volChanged(sl: UISlider) {
    sound.setGain(sl.value, sl.uiTag)
    (volPanel?.viewWithTag(100 + sl.uiTag) as? UILabel)?.text = String.format(Locale.US, "%d%%", MVCLround(sl.value * 100))
}
internal fun MonitorViewController.volPreview(sl: UISlider) {                            // prova del suono al rilascio del cursore
    when (sl.uiTag) {
        SFVolBeepECG -> sound.beepWithFrequency(880.0, true)
        SFVolBeepSpO2 -> sound.beepWithFrequency(880.0, false)
        SFVolAlarm -> {
            volAlarmPreviewUntil = CACurrentMediaTime() + 1.6
        }
        SFVolMonitor -> sound.valveStep()
        SFVolVoice -> {
            SFVoice.volume = (max(0.3, sound.volume) * sound.gainFor(SFVolVoice))
            SFVoice.speak("Prova istruzioni vocali.")
        }
        else -> {}
    }
}

internal fun MonitorViewController.toggleInfo() {
    if (infoPanel.hidden) showInfo() else { infoPanel.hidden = true; connect.showMain() }
}
/** ANDROID (audit 21/11): apre (mai chiude) il pannello «Informazioni e collegamenti»: dal menu ☰ un secondo tocco non deve chiuderlo */
internal fun MonitorViewController.showInfo() {
    hideVolumes()
    infoPanel.hidden = false
    refreshNetwork()
    connect.showMain()
    infoShownAt = CACurrentMediaTime(); updateInfoText()
    infoPanel.superview?.bringSubviewToFront(infoPanel)
}

/** ANDROID: verifica dal Mac (URL layout?name=…): stesso percorso del menu dei layout */
internal fun MonitorViewController.debugLayout(name: String) { state.applyCommand(mapOf("command" to "set_layout", "value" to name)) }
