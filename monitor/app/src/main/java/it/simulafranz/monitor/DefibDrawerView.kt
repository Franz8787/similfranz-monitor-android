// Portato da src/DefibDrawerView.m (monitor iPad)
// SimulaFranz_Monitor — cassetto del defibrillatore (sotto il monitor).
// Modalità: DAE (semiautomatico, con voce), MANUALE (energia, carica, shock, SYNC/cardioversione), PACER (transcutaneo).
// La logica (tempi, esiti degli shock, cattura) è in SimulationState: qui solo interfaccia, suoni e rilevazione dell'onda R.
package it.simulafranz.monitor

import it.simulafranz.core.*
import it.simulafranz.uk.*
import java.util.Locale

private val DF_GREEN = UIColor.colorWithRed(0.20, 1.00, 0.35, 1)
private val DF_YELLOW = UIColor.colorWithRed(1.00, 0.88, 0.20, 1)
private val DF_ORANGE = UIColor.colorWithRed(1.00, 0.45, 0.05, 1)
private val DF_RED = UIColor.colorWithRed(0.95, 0.15, 0.15, 1)
private val DF_WHITE = UIColor.colorWithWhite(0.92, 1)
private val DF_GREY = UIColor.colorWithWhite(0.45, 1)
private val DF_CYAN = UIColor.colorWithRed(0.20, 0.90, 1.00, 1)
private val DF_BG = UIColor.colorWithWhite(0.05, 1)

private fun DFTime(s: Int): String = String.format(Locale.US, "%d:%02d", maxOf(0, s) / 60, maxOf(0, s) % 60)

class DefibDrawerView(frame: CGRect, state: SimulationState, engine: WaveformEngine, sound: SoundEngine) : UIView(frame) {
    var onClose: (() -> Unit)? = null
    var active: Boolean = false

    private val state: SimulationState = state
    private val engine: WaveformEngine = engine
    private val sound: SoundEngine = sound
    private var snap: SLSnapshot? = null

    private lateinit var modeSeg: UISegmentedControl
    private lateinit var padsButton: UIButton
    private lateinit var closeButton: UIButton
    private lateinit var trace: TraceView
    private lateinit var hrTitle: UILabel
    private lateinit var hrValue: UILabel
    private lateinit var infoLabel: UILabel
    private lateinit var message: UILabel
    private lateinit var events: UILabel
    private lateinit var pulse: TraceView
    private lateinit var pulseTitle: UILabel
    private lateinit var pulseValue: UILabel
    private var pulseSrc: Int = 0   // onda di polso: 0 nessuna, 1 ART, 2 pleth
    private lateinit var offView: UIView
    private lateinit var aedView: UIView
    private lateinit var manualView: UIView
    private lateinit var pacerView: UIView
    // DAE
    private lateinit var analyzeButton: UIButton
    private lateinit var aedShock: UIButton
    private lateinit var aedCount: UILabel
    private lateinit var aedEnergy: UILabel
    private lateinit var aedBar: UIView
    private lateinit var aedBarFill: UIView
    // manuale
    private lateinit var energyValue: UILabel
    private lateinit var chargeButton: UIButton
    private lateinit var shockButton: UIButton
    private lateinit var disarmButton: UIButton
    private lateinit var syncButton: UIButton
    private lateinit var chargeTrack: UIView
    private lateinit var chargeFill: UIView
    // pacer
    private lateinit var rateValue: UILabel
    private lateinit var outValue: UILabel
    private lateinit var paceModeSeg: UISegmentedControl
    private lateinit var paceButton: UIButton

    private var lastPrompt: Int = 0
    private var lastShockCount: Int = 0
    private var lastSyncBeat: Double = 0.0
    private var lastMetronome: Double = 0.0
    private var repeat: NSTimer? = null
    private var built = false
    private var lastShockLook = -1          // aspetto dei pulsanti SHOCK già applicato (si aggiorna solo quando cambia)

    init {
        backgroundColor = DF_BG
        layer.borderColor = DF_ORANGE.CGColor; layer.borderWidth = 2.0
        lastShockCount = -1
        build()
    }

    // ---- Costruzione ----

    private fun label(f: CGRect, `in`: UIView, size: Number, bold: Boolean, color: UIColor, align: Int): UILabel {
        val l = UILabel(f)
        l.backgroundColor = UIColor.clearColor; l.textColor = color; l.textAlignment = align
        l.font = if (bold) UIFont.boldSystemFontOfSize(size) else UIFont.systemFontOfSize(size)
        l.adjustsFontSizeToFitWidth = true; l.minimumScaleFactor = 0.5
        `in`.addSubview(l)
        return l
    }

    private fun button(title: String, frame: CGRect, `in`: UIView, color: UIColor, size: Number, action: (() -> Unit)?): UIButton {
        val b = UIButton.buttonWithType(UIButtonTypeCustom).also { SFTasti.register(title, it) }
        b.frame = frame
        b.setTitle(title, UIControlStateNormal)
        b.setTitleColor(color, UIControlStateNormal)
        b.setTitleColor(UIColor.blackColor, UIControlStateHighlighted)
        b.titleLabel.font = UIFont.boldSystemFontOfSize(size)
        b.titleLabel.adjustsFontSizeToFitWidth = true; b.titleLabel.numberOfLines = 2; b.titleLabel.textAlignment = NSTextAlignmentCenter
        b.layer.borderColor = color.CGColor; b.layer.borderWidth = 2.0; b.layer.cornerRadius = 8.0
        b.backgroundColor = UIColor.colorWithWhite(0.10, 1)
        if (action != null) b.addTarget(UIControlEventTouchUpInside) { action() }
        `in`.addSubview(b)
        return b
    }

    // pulsanti +/- con ripetizione tenendo premuto
    private fun stepButton(title: String, frame: CGRect, `in`: UIView, tag: Int): UIButton {
        val b = button(title, frame, `in`, DF_WHITE, 34, null)
        b.uiTag = tag
        b.addTarget(UIControlEventTouchDown) { stepDown(it as UIButton) }
        b.addTarget(UIControlEventTouchUpInside or UIControlEventTouchUpOutside or UIControlEventTouchCancel) { stepUp(it as UIButton) }
        return b
    }

    private fun build() {
        val W = bounds.size.width
        // ANDROID (ICU 21/11): il cassetto è largo come l'ICU a tutto schermo (1228 punti): le posizioni orizzontali del
        // progetto (1024) si riportano con X(); le altezze restano quelle di prima. Traccia delle piastre più lunga (più secondi
        // visibili alla stessa velocità), tasti più larghi e più distanziati, niente testo stirato (i caratteri non cambiano)
        val k = W / 1024.0
        fun X(v: Number): Double = Math.floor(v.toDouble() * k)
        label(CGRectMake(12, 10, 190, 30), this, 19, true, DF_ORANGE, NSTextAlignmentLeft).text = "DEFIBRILLATORE"
        modeSeg = UISegmentedControl(listOf("SPENTO", "DAE", "MANUALE", "PACER"))
        modeSeg.frame = CGRectMake(210, 8, X(440), 36)
        modeSeg.tintColor = DF_ORANGE
        modeSeg.setTitleTextAttributes(UIFont.boldSystemFontOfSize(15), UIControlStateNormal)
        modeSeg.addTarget(UIControlEventValueChanged) { modeChanged() }
        addSubview(modeSeg)
        padsButton = button("", CGRectMake(X(665), 8, X(235), 36), this, DF_CYAN, 15) { togglePads() }
        closeButton = button("▼ CHIUDI", CGRectMake(W - 112, 8, 102, 36), this, DF_GREY, 15) { closeTapped() }

        val eng = engine
        trace = TraceView(CGRectMake(2, 48, X(700), 96))
        trace.traceColor = DF_GREEN; trace.label = "Piastre"; trace.minValue = -0.8; trace.maxValue = 1.6; trace.sweepSpeed = SFSpeedLife(25.0)   // ANDROID (ritardo R→polso 22/11): velocità come sul dispositivo vero (SFTraceSpeed.kt), come l'ICU
        trace.sampler = DoubleSampler { t ->
            val s = snap
            if (s != null && s.padsConnected) eng.ecgAt(t, SLLeadPads) else 0.0   // solo piastre: gli elettrodi sono del monitor
        }
        trace.markerSampler = MarkerSampler { t0, t1 ->
            val s = snap
            if (s == null || !s.padsConnected) 0
            else eng.markersFrom(t0, t1, s.defib.mode == SLDefibManual && s.defib.sync)
        }
        addSubview(trace)
        // curva ART: mostra se la cattura del pacing genera pressione
        pulse = TraceView(CGRectMake(2, 144, X(700), 78))
        pulse.sweepSpeed = SFSpeedLife(25.0); pulse.traceColor = UIColor.colorWithRed(1.00, 0.23, 0.19, 1)
        pulse.label = "ART  mmHg"; pulse.disconnected = true
        pulse.sampler = DoubleSampler { t ->
            if (pulseSrc == 1) eng.artAt(t) else (if (pulseSrc == 2) eng.plethAt(t) else 0.0)
        }
        addSubview(pulse)
        val sep = UIView(CGRectMake(X(704), 48, 1, 174)); sep.backgroundColor = UIColor.colorWithWhite(0.2, 1)
        addSubview(sep)
        val sep2 = UIView(CGRectMake(0, 144, W, 1)); sep2.backgroundColor = UIColor.colorWithWhite(0.15, 1)
        addSubview(sep2)
        hrTitle = label(CGRectMake(X(712), 50, 150, 18), this, 14, true, DF_GREEN, NSTextAlignmentLeft)
        hrValue = label(CGRectMake(X(706), 64, X(160), 80), this, 64, true, DF_GREEN, NSTextAlignmentCenter)
        infoLabel = label(CGRectMake(X(872), 50, W - X(882), 92), this, 14, false, DF_WHITE, NSTextAlignmentLeft)
        infoLabel.numberOfLines = 4
        pulseTitle = label(CGRectMake(X(712), 148, 300, 18), this, 14, true, DF_RED, NSTextAlignmentLeft)
        pulseValue = label(CGRectMake(X(706), 164, W - X(716), 56), this, 42, true, DF_RED, NSTextAlignmentCenter)

        message = label(CGRectMake(10, 226, W - 20, 38), this, 21, true, DF_YELLOW, NSTextAlignmentCenter)
        message.layer.backgroundColor = UIColor.blackColor.CGColor
        message.layer.borderColor = UIColor.colorWithWhite(0.25, 1).CGColor; message.layer.borderWidth = 1.0; message.layer.cornerRadius = 6.0

        val area = CGRectMake(0, 268, W, 152)
        offView = UIView(area); aedView = UIView(area)
        manualView = UIView(area); pacerView = UIView(area)
        for (v in listOf(offView, aedView, manualView, pacerView)) { v.backgroundColor = UIColor.clearColor; addSubview(v) }

        // spento
        val off = label(CGRectMake(40, 10, W - 80, 120), offView, 19, false, DF_GREY, NSTextAlignmentCenter)
        off.numberOfLines = 0
        off.text = "DAE: semiautomatico con istruzioni vocali (analisi, carica, shock, RCP 2 minuti).\n" +
                   "MANUALE: scelta dell'energia, carica, shock e SYNC per la cardioversione.\n" +
                   "PACER: pacing transcutaneo (frequenza, corrente in mA, demand o fisso)."

        // DAE
        analyzeButton = button("ANALIZZA", CGRectMake(10, 12, X(240), 100), aedView, DF_YELLOW, 28) { dfAnalyze() }
        aedShock = button("SHOCK", CGRectMake(X(270), 12, X(260), 100), aedView, DF_ORANGE, 34) { dfShock() }
        aedCount = label(CGRectMake(X(550), 12, X(300), 100), aedView, 46, true, DF_WHITE, NSTextAlignmentCenter)
        aedEnergy = label(CGRectMake(X(860), 12, W - X(870), 100), aedView, 20, true, DF_GREY, NSTextAlignmentCenter)
        aedEnergy.numberOfLines = 2
        aedBar = UIView(CGRectMake(X(560), 58, X(280), 12))
        aedBar.backgroundColor = DF_YELLOW.colorWithAlphaComponent(0.2); aedBar.layer.cornerRadius = 4.0; aedBar.hidden = true
        aedBarFill = UIView(CGRectMake(0, 0, 0, 12)); aedBarFill.backgroundColor = DF_YELLOW; aedBarFill.layer.cornerRadius = 4.0
        aedBar.addSubview(aedBarFill); aedView.addSubview(aedBar)

        // manuale
        label(CGRectMake(X(82), 14, X(156), 20), manualView, 14, true, DF_GREY, NSTextAlignmentCenter).text = "ENERGIA"
        stepButton("−", CGRectMake(10, 12, X(70), 96), manualView, 1)
        energyValue = label(CGRectMake(X(82), 32, X(156), 70), manualView, 46, true, DF_WHITE, NSTextAlignmentCenter)
        stepButton("+", CGRectMake(X(240), 12, X(70), 96), manualView, 2)
        chargeButton = button("CARICA", CGRectMake(X(330), 12, X(200), 96), manualView, DF_YELLOW, 28) { dfCharge() }
        chargeTrack = UIView(CGRectMake(X(330), 112, X(200), 6))
        chargeTrack.backgroundColor = DF_YELLOW.colorWithAlphaComponent(0.2)
        chargeFill = UIView(CGRectMake(0, 0, 0, 6)); chargeFill.backgroundColor = DF_YELLOW
        chargeTrack.addSubview(chargeFill); manualView.addSubview(chargeTrack)
        shockButton = button("SHOCK", CGRectMake(X(550), 12, X(230), 96), manualView, DF_ORANGE, 30) { dfShock() }
        disarmButton = button("ANNULLA\nCARICA", CGRectMake(X(800), 12, X(100), 96), manualView, DF_GREY, 16) { dfDisarm() }
        syncButton = button("SYNC", CGRectMake(X(912), 12, W - X(922), 96), manualView, DF_WHITE, 24) { toggleSync() }
        val hint = label(CGRectMake(10, 120, W - 20, 22), manualView, 13, false, DF_GREY, NSTextAlignmentCenter)
        hint.text = "SYNC: lo shock parte sulla prossima onda R (▼). Senza QRS riconoscibili (FV) lo shock sincronizzato non viene erogato."

        // pacer
        label(CGRectMake(10, 0, X(280), 22), pacerView, 15, true, DF_WHITE, NSTextAlignmentCenter).text = "FREQUENZA  ppm"
        stepButton("−", CGRectMake(10, 24, X(70), 84), pacerView, 3)
        rateValue = label(CGRectMake(X(82), 24, X(136), 84), pacerView, 46, true, DF_WHITE, NSTextAlignmentCenter)
        stepButton("+", CGRectMake(X(220), 24, X(70), 84), pacerView, 4)
        label(CGRectMake(X(310), 0, X(280), 22), pacerView, 15, true, DF_WHITE, NSTextAlignmentCenter).text = "CORRENTE  mA"
        stepButton("−", CGRectMake(X(310), 24, X(70), 84), pacerView, 5)
        outValue = label(CGRectMake(X(382), 24, X(136), 84), pacerView, 46, true, DF_WHITE, NSTextAlignmentCenter)
        stepButton("+", CGRectMake(X(520), 24, X(70), 84), pacerView, 6)
        paceModeSeg = UISegmentedControl(listOf("DEMAND", "FISSO"))
        paceModeSeg.frame = CGRectMake(X(610), 44, X(200), 44)
        paceModeSeg.tintColor = DF_WHITE
        paceModeSeg.setTitleTextAttributes(UIFont.boldSystemFontOfSize(16), UIControlStateNormal)
        paceModeSeg.addTarget(UIControlEventValueChanged) { paceModeChanged() }
        pacerView.addSubview(paceModeSeg)
        paceButton = button("AVVIA\nPACING", CGRectMake(X(830), 12, W - X(840), 96), pacerView, DF_GREEN, 24) { togglePacing() }
        val phint = label(CGRectMake(10, 120, W - 20, 22), pacerView, 13, false, DF_GREY, NSTextAlignmentCenter)
        phint.text = "Aumentare i mA fino alla cattura elettrica (QRS largo + onda T dopo ogni spike), poi verificare il polso (cattura meccanica)."

        events = label(CGRectMake(12, 422, W - 24, 36), this, 14, false, DF_GREY, NSTextAlignmentLeft)
        events.numberOfLines = 2
        built = true
    }

    // ---- Aggiornamento ----

    // a ogni cambio di revisione dello stato
    fun updateWithSnapshot(s: SLSnapshot) {
        snap = s
        val d = s.defib
        if (modeSeg.selectedSegmentIndex != d.mode) modeSeg.selectedSegmentIndex = d.mode
        offView.hidden = d.mode != SLDefibOff; aedView.hidden = d.mode != SLDefibAED
        manualView.hidden = d.mode != SLDefibManual; pacerView.hidden = d.mode != SLDefibPacer
        // ANDROID (funzioni 21/11): piastre sul paziente ma cavo staccato dal dispositivo -> il tasto lo dice (toccandolo il
        // partecipante ricollega il cavo, come sull'apparecchio vero); placche pediatriche indicate
        val cableOff = d.padsApplied && !d.padsCable
        val padsTitle = if (cableOff) "COLLEGA IL CAVO PIASTRE"
                        else if (d.padsConnected) (if (d.padsPediatric) "PIASTRE PEDIATRICHE ✓" else "PIASTRE COLLEGATE ✓") else "APPLICA LE PIASTRE"
        val pc = if (cableOff) DF_YELLOW else if (d.padsConnected) DF_GREEN else DF_CYAN
        if (padsButton.titleForState(UIControlStateNormal) != padsTitle || padsButton.titleColorForState(UIControlStateNormal) != pc) {
            padsButton.setTitle(padsTitle, UIControlStateNormal)
            padsButton.setTitleColor(pc, UIControlStateNormal); padsButton.layer.borderColor = pc.CGColor
        }

        // traccia e FC dal monitoraggio del defibrillatore (piastre, altrimenti elettrodi)
        val src = s.padsConnected
        trace.label = "Piastre"
        trace.disconnected = !src
        trace.noSignalText = if (src) null else (if (cableOff) "CAVO DELLE PIASTRE SCOLLEGATO" else "PIASTRE NON APPLICATE")
        hrTitle.text = "FC  (piastre)"
        var hr = "---"
        if (src) {
            val r = s.rhythm
            if (d.paceCapture) hr = String.format(Locale.US, "%d", d.paceRate)
            else if (r == SLRhythmVentricularFib) hr = "FV"
            else if (r == SLRhythmVentricularTachy || r == SLRhythmVentricularTachyPulse) hr = "TV"
            else if (r == SLRhythmAsystole) hr = "Asy"
            else hr = String.format(Locale.US, "%d", s.monHeartRate)
        }
        hrValue.text = hr

        showMessage(d)
        updatePulse(s)
        // manuale
        energyValue.text = String.format(Locale.US, "%d J", d.energy)
        val sync = d.sync
        val syncTitle = if (sync) "SYNC\nON" else "SYNC\noff"
        val sc = if (sync) DF_GREEN else DF_GREY
        if (syncButton.titleForState(UIControlStateNormal) != syncTitle || syncButton.titleColorForState(UIControlStateNormal) != sc) {
            syncButton.setTitle(syncTitle, UIControlStateNormal)
            syncButton.setTitleColor(sc, UIControlStateNormal); syncButton.layer.borderColor = sc.CGColor
            syncButton.backgroundColor = if (sync) DF_GREEN.colorWithAlphaComponent(0.15) else UIColor.colorWithWhite(0.10, 1)
        }
        disarmButton.alpha = if (d.charge != SLChargeNone) 1.0f else 0.4f
        // ANDROID (guasti 22/11): defibrillatore guasto (service_required): CARICA, ANALIZZA e AVVIA PACING grigi (il cuore
        // li rifiuta comunque; il messaggio del cassetto dice «DEFIBRILLATORE NON DISPONIBILE»). Il monitoraggio resta.
        val svc = s.fault("service_required")
        chargeButton.alpha = if (svc) 0.25f else if (d.padsConnected) 1.0f else 0.5f
        analyzeButton.alpha = if (svc) 0.25f else 1.0f
        paceButton.alpha = if (svc && !d.paceRunning) 0.25f else 1.0f
        // DAE
        // ANDROID (funzioni 21/11): l'energia che il DAE caricherà davvero (pediatrica con placche pediatriche o modalità bambino)
        val ae = if (d.aedEffectiveEnergy > 0) d.aedEffectiveEnergy else d.energy
        val ped = d.padsPediatric || d.aedChildMode
        aedEnergy.text = String.format(Locale.US, "%d J\n%s", ae, if (ped) "pediatrico" else "bifasico")
        aedEnergy.textColor = if (ped) DF_CYAN else DF_GREY
        // pacer
        rateValue.text = String.format(Locale.US, "%d", d.paceRate)
        outValue.text = String.format(Locale.US, "%d", d.paceOutput)
        val pm = if (d.paceDemand) 0 else 1
        if (paceModeSeg.selectedSegmentIndex != pm) paceModeSeg.selectedSegmentIndex = pm
        val paceTitle = if (d.paceRunning) "FERMA\nPACING" else "AVVIA\nPACING"
        val pb = if (d.paceRunning) DF_RED else DF_GREEN
        if (paceButton.titleForState(UIControlStateNormal) != paceTitle || paceButton.titleColorForState(UIControlStateNormal) != pb) {
            paceButton.setTitle(paceTitle, UIControlStateNormal)
            paceButton.setTitleColor(pb, UIControlStateNormal); paceButton.layer.borderColor = pb.CGColor
        }

        // registro: ultimi due eventi visibili al discente
        val lines = ArrayList<String>()
        for (e in d.events.asReversed()) {
            if (e["instructor"] == true) continue
            lines.add(0, String.format(Locale.US, "%s  (%s)  %s", e["time"], e["elapsed"], e["text"]))
            if (lines.size == 2) break
        }
        events.text = lines.joinToString("\n")
    }

    private fun updatePulse(s: SLSnapshot) {
        val src = if (s.artConnected) 1 else 0          // solo pressione arteriosa cruenta (la pleth resta sul monitor)
        if (src != pulseSrc) {
            pulseSrc = src
            val c = if (src == 2) DF_CYAN else DF_RED
            pulse.traceColor = c; pulseTitle.textColor = c; pulseValue.textColor = c
            if (src == 1) { pulse.label = "ART  mmHg"; pulse.autoScale = 3; pulse.showScaleLabels = true; pulse.minValue = 0.0; pulse.maxValue = 160.0 }
            else { pulse.label = "Pleth"; pulse.autoScale = 1; pulse.showScaleLabels = false; pulse.minValue = -0.05; pulse.maxValue = 1.05; pulse.minSpan = 0.15 }
            pulse.resetAtTime(CACurrentMediaTime())
        }
        pulse.disconnected = src == 0
        pulse.noSignalText = if (src == 0) "ART NON COLLEGATA" else null
        if (src == 1) {
            pulseTitle.text = "ART  mmHg"
            val a = s.monArtSystolic; val b = s.monArtDiastolic
            pulseValue.text = String.format(Locale.US, "%d/%d (%d)", a, b, (a + 2 * b) / 3)
        } else if (src == 2) {
            pulseTitle.text = "SpO2 %  ·  PR"
            pulseValue.text = if (s.spo2Displayable) String.format(Locale.US, "%d  ·  %d", s.monSpo2, s.monPulseRate) else "--  ·  --"
        } else { pulseTitle.text = "ART  mmHg"; pulseValue.text = "--/--" }
    }

    // a ogni frame (anche a cassetto chiuso: voce, toni di carica, sync)
    fun tick(now: Double, snapshot: SLSnapshot, visible: Boolean) {
        val s = snapshot
        snap = s
        val d = s.defib

        // voce del DAE
        if (d.promptSeq != lastPrompt) {
            lastPrompt = d.promptSeq
            val vp = d.voicePrompt
            if (d.mode == SLDefibAED && d.voiceEnabled && vp != null && vp.isNotEmpty()) {
                val u = SFAedVoiceText(s.layout, vp)   // voce nello stile del layout
                if (u.isNotEmpty()) {
                    SFVoice.stop()
                    SFVoice.volume = maxOf(0.3, sound.volume) * sound.gainFor(SFVolVoice)   // volume "Istruzioni vocali"
                    SFVoice.speak(u)
                    SFLog.log(String.format(Locale.US, "voce DAE (%s): %s", s.layout, u))
                }
            }
        }
        if (d.mode == SLDefibOff && SFVoice.speaking) SFVoice.stop()

        // suoni di carica / pronto / scarica
        if (d.charge == SLChargeCharging) sound.setDefibTone(1, d.chargeProgress)
        else if (d.charge == SLChargeReady && !d.syncPending) sound.setDefibTone(2, 1.0)
        else sound.setDefibTone(0, 0.0)
        // ANDROID (guscio 25/11): il suono della scarica ora parte per tutti i layout da MonitorViewController
        lastShockCount = d.shockCount

        // shock sincronizzato: erogato sulla prima onda R rilevata dopo la pressione
        if (d.syncPending) {
            val b = engine.lastBeatAtOrBefore(now)
            if (b > 0 && b >= d.syncPendingSince && b > lastSyncBeat) { lastSyncBeat = b; state.defibRWaveAt(b) }
        }

        // metronomo RCP del DAE (100/min)
        if (d.mode == SLDefibAED && d.aedPhase == SLAedCPR && now - lastMetronome >= 60.0 / 100.0 && SFAedLayoutTexts.metronomeOn(s.layout)) {
            if (now - lastMetronome > 2.0) SFLog.log(String.format(Locale.US, "metronomo RCP 100/min avviato (%s)", s.layout))
            lastMetronome = now; sound.metronomeTick()
        }

        if (!visible) return
        if (s.simulationRunning) { trace.advanceTo(now); pulse.advanceTo(now) }
        else { trace.resetAtTime(now); pulse.resetAtTime(now) }

        val blink = now % 0.5 < 0.25
        // pulsanti SHOCK lampeggianti quando carichi
        val ready = d.charge == SLChargeReady && !d.syncPending
        val look = (if (ready) 1 else 0) or (if (ready && blink) 2 else 0)
        if (look != lastShockLook) {        // (iPad: a ogni frame; qui solo quando cambia)
            lastShockLook = look
            for (b in listOf(shockButton, aedShock)) {
                b.alpha = if (ready) 1.0f else 0.35f
                b.backgroundColor = if (ready && blink) DF_ORANGE else UIColor.colorWithWhite(0.10, 1)
                b.setTitleColor(if (ready && blink) UIColor.blackColor else DF_ORANGE, UIControlStateNormal)
            }
        }
        chargeTrack.hidden = d.charge == SLChargeNone      // la barra compare solo durante la carica / da carico
        val tb = chargeTrack.bounds
        val f = CGRectMake(tb.x, tb.y, tb.width * d.chargeProgress, tb.height)
        if (chargeFill.frame != f) chargeFill.frame = f
        val fc = if (d.charge == SLChargeReady) DF_ORANGE else DF_YELLOW
        if (chargeFill.backgroundColor != fc) chargeFill.backgroundColor = fc
        showMessage(d)

        // conto alla rovescia del DAE
        if (d.mode == SLDefibAED) {
            if (d.aedPhase == SLAedCPR) aedCount.text = String.format(Locale.US, "RCP  %s", DFTime(d.aedRemaining))
            else if (d.aedPhase == SLAedAnalyzing) aedCount.text = "ANALISI…"
            else if (d.aedPhase == SLAedCharging) aedCount.text = ""            // solo la barra di carica
            else if (d.aedPhase == SLAedReady) aedCount.text = "PREMERE SHOCK"
            else aedCount.text = ""
            aedCount.textColor = if (d.aedPhase == SLAedReady && blink) DF_ORANGE else DF_WHITE
            aedBar.hidden = d.aedPhase != SLAedCharging
            val bf = CGRectMake(0, 0, aedBar.bounds.size.width * d.chargeProgress, 12)   // ANDROID (ICU 21/11): larghezza vera della barra
            if (aedBarFill.frame != bf) aedBarFill.frame = bf
        }
        // riquadro informazioni: shock, ultimo shock, tempo dall'accensione
        val el = if (d.sessionStart > 0) (now - d.sessionStart).toInt() else 0
        infoLabel.text = String.format(Locale.US, "Shock: %d\nUltimo: %s\nTempo: %s%s",
            d.shockCount,
            if (d.shockCount != 0) String.format(Locale.US, "%d J%s", d.lastShockEnergy, if (d.lastShockSynced) " sync" else "") else "—",
            if (d.sessionStart > 0) DFTime(el) else "—",
            if (d.lastShockAt > 0) String.format(Locale.US, "\nda ultimo: %s", DFTime((now - d.lastShockAt).toInt())) else "")
    }

    fun resetTraceAt(t: Double) { trace.resetAtTime(t); pulse.resetAtTime(t) }

    // ---- Azioni ----

    private fun cmd(name: String, value: Any?) {
        if (!active) { SFLog.log(String.format(Locale.US, "cassetto: %s IGNORATO (cassetto chiuso o in chiusura)", name)); return }
        val c = HashMap<String, Any?>(); c["command"] = name
        if (value != null) c["value"] = value
        val ok = state.applyCommand(c) == null       // gli errori diventano messaggi sul display (modello)
        SFLog.log(String.format(Locale.US, "cassetto: %s %s -> %s", name, value ?: "", if (ok) "ok" else "rifiutato"))
    }

    private fun modeChanged() {
        val m = listOf("off", "aed", "manual", "pacer")
        cmd("defib_mode", m[modeSeg.selectedSegmentIndex])
    }
    // ANDROID (funzioni 21/11): con le piastre sul paziente e il cavo staccato, il tasto ricollega il cavo (non stacca le piastre)
    private fun togglePads() {
        val d = snap?.defib
        if (d != null && d.padsApplied && !d.padsCable) { cmd("set_pads_cable", "connected"); return }
        cmd("set_sensor", mapOf("sensor" to "pads", "connected" to !(d?.padsApplied ?: (snap?.padsConnected ?: false))))
    }
    // ANDROID (funzioni 21/11): i messaggi sulle piastre (impedenza alta / shock non erogato, contatto scarso, cavo) sono
    // avvisi tecnici del defibrillatore: arancio con bordo arancio, distinti dalle istruzioni (giallo)
    private var msgWarn = -1
    private fun showMessage(d: SLDefibInfo) {
        val m = d.message
        if (message.text != m) message.text = m
        val warn = if (m.contains("IMPEDENZA") || m.contains("CAVO") || m.contains("CONTATTO") || m.contains("CONTROLLARE LE PIASTRE") ||
                       m.contains("NON DISPONIBILE") || m.contains("NON EROGATA") || m.contains("RILASCIARE IL PULSANTE")) 1 else 0   // ANDROID (guasti 22/11)
        if (warn == msgWarn) return
        msgWarn = warn
        message.textColor = if (warn == 1) DF_ORANGE else DF_YELLOW
        message.layer.borderColor = (if (warn == 1) DF_ORANGE else UIColor.colorWithWhite(0.25, 1)).CGColor
        message.layer.borderWidth = if (warn == 1) 2.0 else 1.0
    }
    private fun closeTapped() { onClose?.invoke() }
    // NB: nomi con prefisso "df": su iOS 9 UIKit invoca da solo -charge sulle viste durante i tocchi
    // (proprietà delle dinamiche), e un'azione chiamata "charge" faceva caricare il defibrillatore a ogni tocco nel cassetto.
    private fun dfAnalyze() { cmd("defib_analyze", null) }
    private fun dfCharge() { cmd("defib_charge", null) }
    private fun dfShock() { cmd("defib_shock", null) }
    private fun dfDisarm() { cmd("defib_disarm", null) }
    private fun toggleSync() { cmd("defib_sync", !(snap?.defib?.sync ?: false)) }
    private fun togglePacing() { cmd("defib_pacer", mapOf("running" to !(snap?.defib?.paceRunning ?: false))) }
    private fun paceModeChanged() { cmd("defib_pacer", mapOf("demand" to (paceModeSeg.selectedSegmentIndex == 0))) }

    private fun stepTag(tag: Int) {
        val d = snap?.defib ?: SLDefibInfo()
        if (tag == 1 || tag == 2) {
            val list = SimulationState.defibEnergies()
            var i = list.indexOf(d.energy)
            if (i < 0) i = list.indexOf(150)
            i = maxOf(0, minOf(list.size - 1, i + (if (tag == 2) 1 else -1)))
            cmd("defib_energy", list[i])
        } else if (tag == 3 || tag == 4) {
            cmd("defib_pacer", mapOf("rate" to maxOf(30, minOf(180, d.paceRate + (if (tag == 4) 5 else -5)))))
        } else if (tag == 5 || tag == 6) {
            cmd("defib_pacer", mapOf("output_ma" to maxOf(0, minOf(200, d.paceOutput + (if (tag == 6) 5 else -5)))))
        }
        val ns = state.snapshot()
        snap = ns
        updateWithSnapshot(ns)
    }

    private fun stepDown(b: UIButton) {
        stepTag(b.uiTag)
        repeat?.invalidate()
        val tag = b.uiTag
        repeat = NSTimer.scheduledTimerWithTimeInterval(0.4, false) { repeatStart(tag) }
    }
    private fun repeatStart(tag: Int) {
        repeat = NSTimer.scheduledTimerWithTimeInterval(0.12, true) { t -> repeatStep(t, tag) }
    }
    private fun repeatStep(t: NSTimer, tag: Int) {
        if (window == null || hidden) { t.invalidate(); repeat = null; return }
        stepTag(tag)
    }
    // willMoveToWindow:nil
    override fun onDetachedFromWindow() { super.onDetachedFromWindow(); repeat?.invalidate(); repeat = null }
    private fun stepUp(b: UIButton) { repeat?.invalidate(); repeat = null }

    // ---- Autotest ----

    // Tocco simulato: hit-test della finestra nel centro del controllo atteso, poi azioni del controllo colpito
    private fun tap(target: UIView, name: String): Boolean {
        val w = window
        val p = target.convertPoint(CGPointMake(CGRectGetMidX(target.bounds), CGRectGetMidY(target.bounds)), null)
        val hit = w?.hitTest(p)
        var c: UIView? = hit; while (c != null && c !is UIControl) c = c.superview
        val ok = c === target
        SFLog.log(String.format(Locale.US, "SELFTEST tocco %s @(%.0f,%.0f) -> %s %s", name, p.x, p.y, hit?.javaClass?.simpleName,
            if (ok) "OK" else String.format(Locale.US, "SBAGLIATO (controllo colpito: %s '%s')", c?.javaClass?.simpleName,
                if (c is UIButton) c.titleForState(UIControlStateNormal) else "")))
        if (c is UIControl) {
            c.sendActionsForControlEvents(UIControlEventTouchDown)
            c.sendActionsForControlEvents(UIControlEventTouchUpInside)
        }
        val ns = state.snapshot(); snap = ns; updateWithSnapshot(ns)
        return ok
    }

    // verifica da Mac: CARICA, SHOCK, ANALIZZA, ANNULLA, SYNC, PIASTRE, CHIUDI, PACING, MODO:off|aed|manual|pacer
    // true solo a cassetto aperto: i pulsanti agiscono solo allora
    fun tapNamed(name: String): Boolean {
        val n = name
        val m: Map<String, UIView> = mapOf("CARICA" to chargeButton, "SHOCK" to (if (snap?.defib?.mode == SLDefibAED) aedShock else shockButton),
            "ANALIZZA" to analyzeButton, "ANNULLA" to disarmButton, "SYNC" to syncButton,
            "PIASTRE" to padsButton, "CHIUDI" to closeButton, "PACING" to paceButton)
        if (n.startsWith("MODO:")) {
            val i = listOf("off", "aed", "manual", "pacer").indexOf(n.substring(5))
            return i >= 0 && tapSegment(modeSeg, i, n)
        }
        val t = m[n]
        if (t == null) { SFLog.log(String.format(Locale.US, "tap: tasto del cassetto sconosciuto %s (noti: %s)", n, m.keys.joinToString(","))); return false }
        return tap(t, n)
    }

    private fun tapSegment(seg: UISegmentedControl, index: Int, name: String): Boolean {
        val i = index
        val w = window
        val sw = seg.bounds.size.width / seg.numberOfSegments
        val p = seg.convertPoint(CGPointMake(sw * (i + 0.5), CGRectGetMidY(seg.bounds)), null)
        val hit = w?.hitTest(p)
        var c: UIView? = hit; while (c != null && c !is UIControl) c = c.superview
        val ok = c === seg
        SFLog.log(String.format(Locale.US, "SELFTEST segmento %s -> %s", name, if (ok) "OK" else c?.javaClass?.simpleName))
        if (ok) { seg.selectedSegmentIndex = i; seg.sendActionsForControlEvents(UIControlEventValueChanged) }
        val ns = state.snapshot(); snap = ns; updateWithSnapshot(ns)
        return ok
    }

    // Autotest dell'interfaccia (avviato con simulafranz-monitor://selftest): tocchi simulati con hit-test reale
    fun runSelfTestWithShot(shot: (String) -> Unit, done: (Int) -> Unit) {
        var fails = 0
        val me = this          // autotest breve: riferimento forte, rilasciato a fine sequenza
        val st = state
        fun check(c: Boolean, what: String) {
            if (!c) fails++
            SFLog.log(String.format(Locale.US, "SELFTEST %s %s", if (c) "PASS" else "FAIL", what))
        }
        fun set(cmd: String, v: Any?) {
            val d = HashMap<String, Any?>(); d["command"] = cmd; if (v != null) d["value"] = v; if (d["command"] == "set_rhythm") d["onset"] = "immediate"; st.applyCommand(d)
        }
        fun D(): SLDefibInfo = st.snapshot().defib
        // passi: {ritardo dal passo precedente, azione}
        val steps = ArrayList<Pair<Double, () -> Unit>>()
        fun step(delay: Double, b: () -> Unit) { steps.add(Pair(delay, b)) }

        step(0.6) { shot("01-cassetto-spento"); check(D().mode == SLDefibOff, "cassetto aperto, spento") }
        step(0.3) { check(me.tap(me.padsButton, "PIASTRE") && D().padsConnected, "piastre applicate") }
        step(0.3) { check(me.tapSegment(me.modeSeg, 2, "MANUALE") && D().mode == SLDefibManual, "modalità manuale") }
        step(0.3) { check(me.tap(me.syncButton, "SYNC (da scarico)") && D().sync && D().charge == SLChargeNone, "SYNC ON da scarico: nessuna carica") }
        step(3.0) { check(D().sync && D().charge == SLChargeNone, "SYNC ON: dopo 3 s ancora scarico"); shot("02a-sync-on-scarico")
                    check(me.tap(me.syncButton, "SYNC off") && !D().sync && D().charge == SLChargeNone, "SYNC off: nessuna carica") }
        step(0.3) { set("set_rhythm", "afib"); check(me.tap(me.chargeButton, "CARICA") && D().charge == SLChargeCharging, "CARICA avvia la carica") }
        step(6.5) { check(D().charge == SLChargeReady, "carico dopo ~4 s"); shot("02-manuale-carico") }
        step(0.3) { check(me.tap(me.syncButton, "SYNC") && D().sync && D().charge == SLChargeReady, "SYNC attiva il sync e NON ricarica") }
        step(0.3) { check(me.tap(me.disarmButton, "ANNULLA CARICA") && D().charge == SLChargeNone, "ANNULLA CARICA disarma") }
        step(0.3) { check(me.tap(me.chargeButton, "CARICA") && D().charge == SLChargeCharging, "seconda carica") }
        step(6.5) { check(me.tap(me.shockButton, "SHOCK"), "SHOCK premuto (sync)") }
        step(3.5) { val d = D(); check(d.shockCount == 1 && d.lastShockSynced && !d.syncPending, "scarica sincronizzata erogata sull'onda R"); shot("03-dopo-sync") }
        step(0.3) { set("defib_sync", false); set("set_rhythm", "vf")
                    check(me.tap(me.chargeButton, "CARICA"), "carica per FV") }
        step(6.5) { check(me.tap(me.shockButton, "SHOCK") && D().shockCount == 2 && !D().lastShockSynced, "SHOCK asincrono erogato subito") }
        step(0.3) { val e0 = D().energy; me.stepTag(2); me.stepTag(2)
                    check(D().energy > e0, "energia +"); me.stepTag(1); me.stepTag(1); check(D().energy == e0, "energia −") }
        step(0.3) { check(me.tapSegment(me.modeSeg, 3, "PACER") && D().mode == SLDefibPacer, "modalità pacer"); shot("04-pacer") }
        step(0.3) { set("set_rhythm", "sinus_bradycardia"); set("set_sensor", mapOf("sensor" to "art", "connected" to true))
                    for (i in 0 until 16) me.stepTag(6)
                    check(D().paceOutput == 80, String.format(Locale.US, "corrente 80 mA (%d)", D().paceOutput))
                    check(me.tap(me.paceButton, "AVVIA PACING") && D().paceRunning, "AVVIA PACING") }
        step(0.5) { val d = D(); check(d.paceActive && d.paceCapture && d.paceMechanical && st.snapshot().displayedHeartRate == 70,
                    "pacing attivo con cattura a 70") }
        step(4.0) { shot("05-pacing-cattura"); check(st.snapshot().pulsePresent && me.pulseSrc == 1, "cattura meccanica: polso e onda ART nel cassetto") }
        step(0.3) { set("set_defib_config", mapOf("mechanical_capture" to false)); set("set_rhythm", "asystole")
                    check(D().paceCapture && !st.snapshot().pulsePresent, "solo cattura elettrica: nessun polso") }
        step(4.0) { shot("05b-pacing-solo-elettrica"); set("set_defib_config", mapOf("mechanical_capture" to true))
                    check(me.tap(me.paceButton, "FERMA PACING") && !D().paceRunning, "FERMA PACING") }
        step(0.3) { set("set_rhythm", "vf"); check(me.tapSegment(me.modeSeg, 1, "DAE") && D().aedPhase == SLAedAnalyzing, "DAE: analisi") }
        step(4.0) { shot("06-dae-analisi") }
        step(12.0) { check(D().charge == SLChargeReady && D().aedPhase == SLAedReady, "DAE: shock consigliato e carico"); shot("07-dae-pronto") }
        step(0.3) { check(me.tap(me.aedShock, "SHOCK DAE") && D().aedPhase == SLAedCPR, "DAE: scarica e RCP") }
        step(1.5) { shot("08-dae-rcp"); check(me.tapSegment(me.modeSeg, 0, "SPENTO") && D().mode == SLDefibOff, "spento") }

        // tutti i passi programmati in anticipo a tempi cumulativi (nessun blocco ricorsivo)
        var at = 0.0
        for (sp in steps) {
            at += sp.first
            val b = sp.second
            Uk.after(at, b)
        }
        Uk.after(at + 0.2) {
            SFLog.log(String.format(Locale.US, "SELFTEST FINE: %d errori", fails))
            done(fails)
        }
    }
}
