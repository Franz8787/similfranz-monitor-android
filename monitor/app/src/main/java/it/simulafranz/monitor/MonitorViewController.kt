// Portato da src/MonitorViewController.m (monitor iPad)
// Parte 1 di 3: costruzione dell'interfaccia GENERIC ICU, ciclo di rendering, suoni sincronizzati, aggiornamenti.
// (menu, layout, cassetto, volumi, splash: MonitorViewControllerMenus.kt — audit e autotest: MonitorViewControllerSelfTest.kt)
// SimulaFranz_Monitor — schermata principale del monitor multiparametrico simulato.
package it.simulafranz.monitor

import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import it.simulafranz.core.*
import it.simulafranz.uk.*
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

private fun SLLim(hi: Int, lo: Int): String {
    if (hi >= 0 && lo >= 0) return String.format(Locale.US, "%d\n%d", hi, lo)
    return String.format(Locale.US, "%d", if (hi >= 0) hi else lo)
}

/** lround() del C: arrotonda al più vicino, i mezzi lontano dallo zero */
internal fun MVCLround(d: Double): Int = (if (d < 0) -Math.floor(-d + 0.5) else Math.floor(d + 0.5)).toInt()

/** [[NSBundle mainBundle] objectForInfoDictionaryKey:@"CFBundleShortVersionString"] ?: @"" */
internal fun MVCBundleVersion(): String =
    SFVersionShown()

/** [UIButton buttonWithType:UIButtonTypeInfoLight]: cerchietto con la "i", nel colore di tintColor (manca in `uk`) */
internal class MVCInfoLightButton : UIButton(CGRect(), UIButtonTypeCustom) {
    override fun stateChanged() { super.stateChanged(); invalidate() }
    override fun tintColorDidChange() { super.tintColorDidChange(); invalidate() }
    override fun drawRect(ctx: CGContext, rect: CGRect) {
        val base = tintColor ?: UIColor.systemBlue
        val c = if (highlighted) base.colorWithAlphaComponent(base.a * 0.35) else base
        val d = 22.0
        val r = CGRectMake(rect.midX - d / 2, rect.midY - d / 2, d, d)
        ctx.setStrokeColor(c); ctx.setLineWidth(1.0)
        ctx.strokeEllipseInRect(r.inset(0.5, 0.5))
        val f = UIFont.fontWithName("TimesNewRomanPS-BoldMT", 16)
        val sz = UkText.size("i", f)
        ctx.drawText("i", CGPointMake(rect.midX - sz.width / 2, rect.midY - sz.height / 2), f, c)
    }
}

class MonitorViewController(val state: SimulationState, val sound: SoundEngine, val prefs: SFPrefs) {
    private var lastTabVolSeq = -1; private var lastTabVolRead = 0.0     // ANDROID (guscio 25/11)
    private var nextPaceVib = 0.0
    private var lastShockHeard = -1         // ANDROID (guscio 25/11)
    private var lastChargeRefused = -1      // ANDROID (suoni 25/11)
    val view: UIView = UIView(CGRectMake(0, 0, 1024, 768))

    // Informazioni tecniche mostrate nel pannello (i)
    var pinProvider: (() -> String)? = null
    var serverPort: Int = 8080
    // Stato dei collegamenti per il pannello (i): version, directName, directOn, directError, peers, controller
    var linkInfo: (() -> Map<String, Any?>)? = null
    var serverStatus: String? = null
    // "Azzera e spegni" dal menu del monitor: stessa azione del controller (chiude lo scenario a fasi, nuova sessione del registro)
    var onResetScenario: (() -> Unit)? = null   // es. "attivo" o messaggio d'errore

    // Palette clinica (sfondo nero, colori per parametro)
    internal val SL_GREEN: UIColor get() = UIColor.colorWithRed(0.20, 1.00, 0.35, 1)
    internal val SL_CYAN: UIColor get() = UIColor.colorWithRed(0.20, 0.90, 1.00, 1)
    internal val SL_YELLOW: UIColor get() = UIColor.colorWithRed(1.00, 0.88, 0.20, 1)
    internal val SL_NIBP: UIColor get() = UIColor.colorWithRed(0.94, 0.33, 0.48, 1)   // rosso-lampone (diverso dall'ART)
    internal val SL_RED: UIColor get() = UIColor.colorWithRed(1.00, 0.23, 0.19, 1)   // rosso arterioso
    internal val SL_WHITE: UIColor get() = UIColor.colorWithWhite(0.92, 1)
    internal val SL_GREY: UIColor get() = UIColor.colorWithWhite(0.45, 1)
    internal val SL_AMBER: UIColor get() = UIColor.colorWithRed(1.00, 0.62, 0.10, 1)
    internal val SL_ALARM: UIColor get() = UIColor.colorWithRed(0.95, 0.15, 0.15, 1)
    internal val SL_LINE: UIColor get() = UIColor.colorWithWhite(0.18, 1)
    internal val SL_TEMP: UIColor get() = UIColor.colorWithRed(0.80, 0.62, 1.00, 1)   // lilla (temperatura)

    internal val engine: WaveformEngine = WaveformEngine((System.currentTimeMillis() / 1000).toInt())
    internal var link: CADisplayLink? = null
    private var linkFrames = 0                 // fotogrammi dello schermo dall'ultimo frame: (frameInterval di CADisplayLink)
    internal var lastRevision = 0
    internal lateinit var snap: SLSnapshot

    internal lateinit var ecg2: TraceView; internal lateinit var ecgV: TraceView; internal lateinit var pleth: TraceView
    internal lateinit var art: TraceView; internal lateinit var capno: TraceView
    internal lateinit var hrValue: UILabel; internal lateinit var hrHeart: UILabel; internal lateinit var hrLimits: UILabel
    internal lateinit var spo2Value: UILabel; internal lateinit var spo2Limits: UILabel; internal lateinit var prValue: UILabel
    internal lateinit var artValue: UILabel; internal lateinit var artMap: UILabel; internal lateinit var artLimits: UILabel
    internal lateinit var etco2Value: UILabel; internal lateinit var etco2Limits: UILabel
    internal lateinit var rrValue: UILabel; internal lateinit var rrLabel: UILabel; internal lateinit var rrLimits: UILabel
    internal lateinit var nibpValue: UILabel; internal lateinit var nibpMap: UILabel; internal lateinit var nibpStatus: UILabel; internal lateinit var nibpLimits: UILabel
    internal lateinit var banner: UILabel; internal lateinit var clock: UILabel; internal lateinit var netLabel: UILabel; internal lateinit var titleLabel: UILabel
    internal lateinit var inopField: UILabel     // ANDROID (ICU 21/11): campo degli allarmi tecnici, distinto da quello clinico
    internal lateinit var muteButton: UIButton; internal lateinit var nibpButton: UIButton; internal lateinit var infoButton: UIButton
    internal lateinit var infoPanel: UIView; internal lateinit var connect: SFConnectPanel      // _infoPanel = _connect (pannello Collegamento)

    internal var lastSlowTick = 0.0; internal var lastNetCheck = 0.0; internal var infoShownAt = 0.0
    internal var lastBeepBeat = 0.0
    internal var lastNibpPhase: SLNibpPhase = SLNibpPhaseNone; internal var lastNibpStep = -1
    internal var ip: String? = null
    internal var flashOn = false
    internal var tick = 0
    internal var alarmResult: SLAlarmResult? = null
    private val alarmPauseGuard = SLAlarmPauseGuard()   // ANDROID (IN-04)
    internal val clockFmt = SimpleDateFormat("HH:mm:ss", Locale.US); internal val nibpFmt = SimpleDateFormat("HH:mm", Locale.US)
    internal var frameCostAvg = 0.0
    internal var frameInterval = 2   // 60/2 = 30 fps
    internal var alarmBoxes: HashMap<String, UIView>? = null   // celle dei parametri che lampeggiano in allarme
    internal var cellRects = HashMap<String, CGRect>()    // area di ogni cella numerica (GENERIC ICU)
    internal var cellLabels: HashMap<String, List<Pair<UILabel, UIColor>>>? = null   // etichette contenute in ogni cella, con il colore normale
    internal lateinit var altFooter: UIButton; internal lateinit var altFooterText: UILabel
    internal var menuButton: SFMenuButton? = null; internal var menuLastPeers: Set<String>? = null   // ANDROID: menu dell'istruttore (SFMonitorMenu.kt)
    internal lateinit var altFooterIcon: UIView                                 // ANDROID (BugFix #11/#13): (i) del riquadro, riposizionato per layout
    internal var volPanel: UIView? = null                                       // pannello dei volumi per canale
    internal var volAlarmPreviewUntil = 0.0
    internal lateinit var battBody: UIView; internal lateinit var battFill: UIView; internal lateinit var battPct: UILabel; internal lateinit var battBolt: CAShapeLayer   // batteria dell'iPad nella barra inferiore            // piè di pagina dei layout dei dispositivi: indirizzo + (i)
    private var battLvl = -2f; private var battChg = false; private var battFull = false   // ultimo stato disegnato (si ridisegna solo se cambia)
    // menu parametro (tocco sul valore)
    internal var menuDim: UIView? = null; internal var menuPanel: UIView? = null
    internal var menuParam: String? = null
    internal var menuSteppers = LinkedHashMap<String, UIStepper>()
    internal var menuValues = LinkedHashMap<String, UILabel>()
    internal var menuSwitch: UISwitch? = null; internal var menuSwitchText: UILabel? = null; internal var menuError: UILabel? = null
    internal var menuInterval: UISegmentedControl? = null
    // variabilità fisiologica dei numeri mostrati (deriva lenta, aggiornata ogni ~2 s)
    internal var jHR = 0.0; internal var jSpO2 = 0.0; internal var jSys = 0.0; internal var jDia = 0.0; internal var lastJitter = 0.0
    internal var lowPerfSince = 0.0      // inizio della bassa perfusione (ciclo del "?" SpO2)
    // NIBP automatica
    internal lateinit var nibpAutoLabel: UILabel; internal lateinit var nibpBarTrack: UIView; internal lateinit var nibpBarFill: UIView
    // temperatura e refill capillare
    internal lateinit var tempValue: UILabel; internal lateinit var crtValue: UILabel
    internal var crtShown = -1; internal var crtAssessAt = 0.0; internal var crtSeqSeen = 0   // refill: valore "valutato" e inizio della valutazione
    internal lateinit var crtTitle: UILabel; internal lateinit var crtLine: UIView; internal lateinit var crtHit: UIButton
    // layout del monitor
    internal var alt: UIView? = null; internal var layoutShown: String? = null; internal var layoutMenu: UIView? = null
    internal val altL: SFAltLayout? get() = alt as? SFAltLayout    // lo stesso _alt, visto come UIView<SFAltLayout>
    internal var altKind: String? = null
    internal var pauseCover: UIView? = null                                       // "Scenario in PAUSA" a tutto schermo
    internal var icuHidden: ArrayList<UIView>? = null                     // viste ICU nascoste sotto un layout di dispositivo
    private var sweepWasOn = false; private var sweepLayout: String? = null   // ANDROID (onde 22/11)
    internal var standby: UIView? = null; internal var powerPushed = false                       // GENERIC ICU in standby + ultimo stato di accensione inviato al layout attivo
    // cassetto del defibrillatore
    internal lateinit var drawer: DefibDrawerView; internal lateinit var drawerTab: UIButton; internal var drawerOpen = false; internal var drawerY = 0.0

    private val rnd = java.util.Random()
    // ANDROID (funzioni mancanti 22/11): trend (IN-14), PVC e T2 (IN-13), PPV (OP-05), riga ART divisa con la PVC
    internal var trendView: SFTrendView? = null
    internal lateinit var cvpTrace: TraceView; internal lateinit var cvpValue: UILabel; internal lateinit var ppvLabel: UILabel
    internal lateinit var temp2Label: UILabel; internal lateinit var trendButton: UIButton
    internal var artRow = CGRect(); internal var cvpShown = false
    // ANDROID (audit 21/11, analisi 1.2): il cuore avanza anche quando lo schermo non disegna (finestra di sistema sopra,
    // app in secondo piano): un timer leggero fa il tick se il fotogramma non arriva da 0,2 s; mai due tick ravvicinati
    internal var lastStateTick = 0.0
    private var stateTimer: NSTimer? = null

    // MARK: - Costruzione UI

    internal fun label(f: CGRect, size: Number, bold: Boolean, color: UIColor, align: Int): UILabel {
        val l = UILabel(f)
        l.backgroundColor = UIColor.clearColor
        l.textColor = color
        l.font = if (bold) UIFont.boldSystemFontOfSize(size) else UIFont.systemFontOfSize(size)
        l.textAlignment = align
        l.adjustsFontSizeToFitWidth = true
        l.minimumScaleFactor = 0.5
        view.addSubview(l)
        return l
    }

    internal fun limits(f: CGRect, color: UIColor): UILabel {
        val l = label(f, 12, false, color, NSTextAlignmentRight)
        l.numberOfLines = 2; l.alpha = 0.8f
        return l
    }

    internal fun line(f: CGRect) {
        val v = UIView(f)
        v.backgroundColor = SL_LINE
        view.addSubview(v)
    }

    internal fun button(title: String, frame: CGRect, color: UIColor, action: () -> Unit): UIButton {
        val b = UIButton.buttonWithType(UIButtonTypeCustom)
        b.frame = frame
        b.setTitle(title, UIControlStateNormal)
        b.setTitleColor(color, UIControlStateNormal)
        b.setTitleColor(UIColor.whiteColor, UIControlStateHighlighted)
        b.titleLabel.font = UIFont.boldSystemFontOfSize(16)
        b.titleLabel.adjustsFontSizeToFitWidth = true
        b.layer.borderColor = color.CGColor; b.layer.borderWidth = 1.5; b.layer.cornerRadius = 6.0
        b.addTarget(UIControlEventTouchUpInside) { action() }
        view.addSubview(b)
        return b
    }

    internal fun trace(f: CGRect, color: UIColor, label: String, min: Double, max: Double, speed: Double,
                       sampler: DoubleSampler): TraceView {
        val t = TraceView(f)
        t.traceColor = color; t.label = label; t.minValue = min; t.maxValue = max; t.sweepSpeed = speed; t.sampler = sampler
        view.addSubview(t)
        return t
    }

    fun viewDidLoad() {
        view.backgroundColor = UIColor.blackColor
        viewDidLayoutSubviews()
        stateTimer?.invalidate()
        stateTimer = NSTimer.scheduledTimerWithTimeInterval(0.25, true) {
            val now = CACurrentMediaTime()
            if (now - lastStateTick >= 0.2) { lastStateTick = now; state.tickAtTime(now) }
        }
    }

    internal fun viewDidLayoutSubviews() {
        if (::ecg2.isInitialized) return          // layout fisso landscape, costruito una volta
        buildUI()
    }

    internal fun buildUI() {
        // ANDROID (ICU 21/11): l'ICU usa TUTTO lo schermo (tela + fasce, 1228 punti): la tela si allarga quando l'ICU è a
        // schermo (fitCanvasForLayout, SFIcuWide.kt). Tracce 800 punti (~5 s di ECG alla stessa velocità, prima ~4,4 s),
        // colonna dei numeri 418 punti (prima 314): numeri più grandi, proporzione 65/35 come nei monitor da terapia intensiva
        val W = SFIcuScreenWidth()                                        // 1228 (1024 senza fasce)
        val H = min(view.bounds.size.width, view.bounds.size.height)   // 768
        val wide = W > SF_CANVAS_W + 100
        val nk = if (wide) 1.15 else 1.0                                  // ingrandimento dei numeri nella colonna più larga
        val top = 56.0; val bottom = H - 34               // intestazione più alta: SILENZIA e DEFIB a portata di mano
        val traceW = if (wide) floor(W * 0.652) else Math.round(W * 0.684).toDouble()   // 800 (~700 senza fasce)
        val numX = traceW + 4; val numW = W - numX - 6
        // righe: ECG II, ECG V, Pleth, ART, CO2, NIBP
        val yII = top; val hII = 118.0; val yV = yII + hII; val hV = 100.0; val yPl = yV + hV; val hPl = 104.0
        val yArt = yPl + hPl; val hArt = 118.0; val yCO2 = yArt + hArt; val hCO2 = 98.0
        val yNibp = yCO2 + hCO2; val hNibp = bottom - yNibp

        // --- barra superiore ---
        // ANDROID (ICU 21/11): da sinistra ☰ dell'istruttore (48×48, SFIcuMenuFrame) · «ICU» · campo allarmi clinici ·
        // campo allarmi tecnici · SILENZIA · DEFIB · orologio; tutti alti 44 e allineati sulla stessa riga
        titleLabel = label(CGRectMake(58, 6, 64, 44), 17, true, SL_GREY, NSTextAlignmentLeft)
        titleLabel.numberOfLines = 1
        titleLabel.text = "ICU"            // ANDROID (menu ☰ 21/11): nome, versione e codice del monitor sono nel menu ☰
        val clockW = 84.0; val defW = 140.0; val muteW = 160.0
        val defX = W - 6 - clockW - 8 - defW; val muteX = defX - 8 - muteW
        val alX = 124.0; val alW = muteX - 10 - alX                       // area allarmi: 690 punti (476 senza fasce)
        val clinW = floor(alW * 0.58)
        banner = label(CGRectMake(alX, 6, clinW, 44), 21, true, UIColor.blackColor, NSTextAlignmentCenter)
        banner.layer.cornerRadius = 6.0; banner.hidden = true   // sfondo sul layer: arrotondato senza ritaglio
        inopField = label(CGRectMake(alX + clinW + 8, 6, alW - clinW - 8, 44), 17, true, UIColor.blackColor, NSTextAlignmentCenter)
        inopField.layer.cornerRadius = 6.0; inopField.layer.backgroundColor = SL_CYAN.CGColor; inopField.hidden = true
        inopField.numberOfLines = 1
        muteButton = button("SILENZIA", CGRectMake(muteX, 6, muteW, 44), SL_AMBER) { toggleMute() }
        drawerTab = button("DEFIB  ▲", CGRectMake(defX, 6, defW, 44), UIColor.colorWithRed(1.00, 0.45, 0.05, 1)) { toggleDrawer() }
        clock = label(CGRectMake(W - 6 - clockW, 6, clockW, 44), 19, false, SL_WHITE, NSTextAlignmentRight)
        line(CGRectMake(0, top - 1, W, 1))

        // --- tracciati ---
        val eng = engine
        // ANDROID (ritardo R→polso 22/11): velocità come sul dispositivo vero (SFTraceSpeed.kt): 25 mm/s veri sul vetro per ECG, Pleth, ART (prima 160 punti/s = 22,3 mm/s
        // sul Tab Active 2), 6,25 mm/s per la CO2: il tablet è lo schermo del monitor, a grandezza naturale
        val v25 = SFSpeedLife(25.0); val v6 = SFSpeedLife(6.25)
        ecg2 = trace(CGRectMake(0, yII, traceW, hII), SL_GREEN, "II", -0.7, 1.5, v25) { t -> eng.ecgAt(t, SLLeadII) }
        ecgV = trace(CGRectMake(0, yV, traceW, hV), SL_GREEN, "V5", -0.6, 1.9, v25) { t -> eng.ecgAt(t, SLLeadV) }
        val spikes: MarkerSampler = MarkerSampler { t0, t1 ->
            eng.markersFrom(t0, t1, false)
        }
        ecg2.markerSampler = spikes; ecgV.markerSampler = spikes
        pleth = trace(CGRectMake(0, yPl, traceW, hPl), SL_CYAN, "Pleth", -0.05, 1.05, v25) { t -> eng.plethAt(t) }
        pleth.autoScale = 1; pleth.minSpan = 0.6 /* ANDROID (perfusione 22/11): onde piccole restano piccole */           // la curva si riadatta all'ampiezza del polso
        art = trace(CGRectMake(0, yArt, traceW, hArt), SL_RED, "ART  mmHg", 0.0, 160.0, v25) { t -> eng.artAt(t) }
        art.autoScale = 3; art.showScaleLabels = true         // scala "ottimale" attorno alla pressione, come in TI
        // ANDROID (IN-13): PVC nella parte bassa della riga ART, solo con il trasduttore PVC collegato
        artRow = CGRectMake(0, yArt, traceW, hArt)
        cvpTrace = trace(CGRectMake(0, yArt + 74, traceW, hArt - 74), UIColor.colorWithRed(0.40, 0.65, 1.00, 1), "PVC  mmHg", -2.0, 22.0, v25) { t -> eng.cvpAt(t) }
        cvpTrace.showScaleLabels = true; cvpTrace.hidden = true          // scala fissa 0-20 (la PVC sta in pochi mmHg)
        capno = trace(CGRectMake(0, yCO2, traceW, hCO2), SL_WHITE, "CO2  mmHg", -2.0, 60.0, v6) { t -> eng.capnoAt(t) }
        capno.filled = true                                     // capnogramma pieno (area sotto la curva)
        capno.autoScale = 4; capno.showScaleLabels = true      // ANDROID (OP-01): scala 60/75/100/150 mmHg, mai tagliata
        line(CGRectMake(0, yV, traceW, 1))      // tra II e V: solo sui tracciati (il valore FC occupa entrambe le righe)
        for (y in listOf(yPl, yArt, yCO2, yNibp)) line(CGRectMake(0, y, W, 1))
        line(CGRectMake(traceW + 1, top, 1, yNibp - top))

        // --- numerici: FC (sulle due derivazioni ECG) ---
        val limW = 52.0
        label(CGRectMake(numX + 6, yII + 4, 120, 20), 15, true, SL_GREEN, NSTextAlignmentLeft).text = "FC  bpm"
        hrHeart = label(CGRectMake(numX + 110, yII + 2, 30, 26), 22, true, SL_GREEN, NSTextAlignmentLeft)
        hrHeart.text = ""; hrHeart.alpha = 0.15f
        val hl = CAShapeLayer.layer()                   // cuore disegnato, verde come il numero (niente emoji)
        val hp = UIBezierPath.bezierPath()
        val hw = 20.0; val hx = 4.0; val hy = 5.0
        hp.moveToPoint(CGPointMake(hx + hw / 2, hy + hw * 0.9))
        hp.addCurveToPoint(CGPointMake(hx, hy + hw * 0.3), CGPointMake(hx + hw * 0.2, hy + hw * 0.7), CGPointMake(hx, hy + hw * 0.5))
        hp.addArcWithCenter(CGPointMake(hx + hw * 0.25, hy + hw * 0.27), hw * 0.25, Math.PI, 0, true)
        hp.addArcWithCenter(CGPointMake(hx + hw * 0.75, hy + hw * 0.27), hw * 0.25, Math.PI, 0, true)
        hp.addCurveToPoint(CGPointMake(hx + hw / 2, hy + hw * 0.9), CGPointMake(hx + hw, hy + hw * 0.5), CGPointMake(hx + hw * 0.8, hy + hw * 0.7))
        hp.closePath()
        hl.path = hp.CGPath; hl.fillColor = SL_GREEN.CGColor
        hrHeart.layer.addSublayer(hl)
        hrLimits = limits(CGRectMake(numX + numW - limW - 4, yII + 6, limW, 34), SL_GREEN)
        hrValue = label(CGRectMake(numX, yII + 30, numW, hII + hV - 40), 130 * nk, true, SL_GREEN, NSTextAlignmentCenter)

        // SpO2 + PR
        label(CGRectMake(numX + 6, yPl + 4, 120, 20), 15, true, SL_CYAN, NSTextAlignmentLeft).text = "SpO2  %"
        spo2Limits = limits(CGRectMake(numX + numW * 0.66 - limW - 6, yPl + 6, limW, 18), SL_CYAN)
        spo2Value = label(CGRectMake(numX, yPl + 20, numW * 0.66, hPl - 24), 76 * nk, true, SL_CYAN, NSTextAlignmentCenter)
        label(CGRectMake(numX + numW * 0.66, yPl + 22, numW * 0.34, 18), 13, true, SL_CYAN, NSTextAlignmentCenter).text = "FC  bpm"
        prValue = label(CGRectMake(numX + numW * 0.66, yPl + 40, numW * 0.34, 50), 36 * nk, true, SL_CYAN, NSTextAlignmentCenter)

        // ART
        label(CGRectMake(numX + 6, yArt + 4, 120, 20), 15, true, SL_RED, NSTextAlignmentLeft).text = "ART  mmHg"
        artLimits = limits(CGRectMake(numX + numW - limW - 4, yArt + 6, limW, 34), SL_RED)
        artValue = label(CGRectMake(numX + 4, yArt + 26, numW * 0.72, hArt - 34), 56 * nk, true, SL_RED, NSTextAlignmentCenter)
        artMap = label(CGRectMake(numX + numW * 0.72, yArt + 50, numW * 0.28, 44), 30 * nk, true, SL_RED, NSTextAlignmentCenter)
        // ANDROID (OP-05 / IN-13): PPV col ventilatore (accanto al titolo) e PVC (sotto la media), solo se presenti
        ppvLabel = label(CGRectMake(numX + 130, yArt + 4, 120, 20), 15, true, SL_RED, NSTextAlignmentLeft)
        cvpValue = label(CGRectMake(numX + numW * 0.52, yArt + 92, numW * 0.48 - 6, 24), 18, true, UIColor.colorWithRed(0.40, 0.65, 1.00, 1), NSTextAlignmentRight)

        // EtCO2 + FR
        label(CGRectMake(numX + 6, yCO2 + 4, 130, 20), 15, true, SL_WHITE, NSTextAlignmentLeft).text = "EtCO2  mmHg"
        etco2Limits = limits(CGRectMake(numX + numW * 0.55 - limW - 4, yCO2 + 6, limW, 34), SL_WHITE)
        etco2Value = label(CGRectMake(numX, yCO2 + 24, numW * 0.55, hCO2 - 28), 60 * nk, true, SL_WHITE, NSTextAlignmentCenter)
        rrLabel = label(CGRectMake(numX + numW * 0.57, yCO2 + 4, 90, 20), 15, true, SL_WHITE, NSTextAlignmentLeft)
        rrLimits = limits(CGRectMake(numX + numW - limW - 4, yCO2 + 6, limW, 34), SL_WHITE)
        rrValue = label(CGRectMake(numX + numW * 0.55, yCO2 + 24, numW * 0.45, hCO2 - 28), 48 * nk, true, SL_WHITE, NSTextAlignmentCenter)

        // celle numeriche: in allarme lampeggia l'intera cella (sfondo), non solo il numero
        cellRects = hashMapOf("hr" to CGRectMake(numX - 1, yII + 1, numW + 6, hII + hV - 1),
                              "spo2" to CGRectMake(numX - 1, yPl + 1, numW * 0.66 + 1, hPl - 1),   // senza la FC del saturimetro
                              "art" to CGRectMake(numX - 1, yArt + 1, numW + 6, hArt - 1),
                              "etco2" to CGRectMake(numX - 1, yCO2 + 1, numW * 0.56, hCO2 - 1),
                              "rr" to CGRectMake(numX + numW * 0.56, yCO2 + 1, numW * 0.44 + 6, hCO2 - 1),
                              "nibp" to CGRectMake(124, yNibp + 1, traceW - 124, hNibp - 1))

        // --- NIBP + pulsanti ---
        // AVVIA NIBP a sinistra del valore; poi valore, PAM, stato del bracciale
        val nx = 128.0
        label(CGRectMake(nx, yNibp + 6, 200, 20), 15, true, SL_NIBP, NSTextAlignmentLeft).text = "NIBP  mmHg"
        nibpLimits = limits(CGRectMake(nx + 200, yNibp + 6, limW, 34), SL_NIBP)
        nibpValue = label(CGRectMake(nx, yNibp + 24, 255, hNibp - 30), 66, true, SL_NIBP, NSTextAlignmentLeft)
        nibpMap = label(CGRectMake(nx + 258, yNibp + 34, 96, 46), 32, true, SL_NIBP, NSTextAlignmentLeft)
        val sx = nx + 358; val sw = traceW - sx - 8
        nibpStatus = label(CGRectMake(sx, yNibp + 84, sw, 24), 16, false, SL_NIBP, NSTextAlignmentLeft)

        // NIBP automatica: etichetta + barra che si accorcia fino alla prossima misura
        nibpAutoLabel = label(CGRectMake(sx, yNibp + 8, sw, 20), 14, true, SL_NIBP, NSTextAlignmentLeft)
        nibpAutoLabel.numberOfLines = 2; nibpAutoLabel.frame = CGRectMake(sx, yNibp + 6, sw, 40)
        nibpBarTrack = UIView(CGRectMake(sx, yNibp + hNibp - 12, sw, 5))
        nibpBarTrack.backgroundColor = SL_NIBP.colorWithAlphaComponent(0.2); nibpBarTrack.hidden = true
        nibpBarFill = UIView(nibpBarTrack.bounds)
        nibpBarFill.backgroundColor = SL_NIBP
        nibpBarTrack.addSubview(nibpBarFill); view.addSubview(nibpBarTrack)

        // aree toccabili sui valori: aprono il menu del parametro
        val hits = mapOf("hr" to CGRectMake(numX, yII, numW, hII + hV),
                         "spo2" to CGRectMake(numX, yPl, numW, hPl),
                         "art" to CGRectMake(numX, yArt, numW, hArt),
                         "etco2" to CGRectMake(numX, yCO2, numW * 0.55, hCO2),
                         "rr" to CGRectMake(numX + numW * 0.55, yCO2, numW * 0.45, hCO2),
                         "nibp" to CGRectMake(nx, yNibp, sx - nx, hNibp))   // solo valore + PAM
        val order = listOf("hr", "spo2", "art", "etco2", "rr", "nibp")
        for (i in order.indices) {
            val hb = UIButton.buttonWithType(UIButtonTypeCustom)
            hb.frame = hits[order[i]]!!; hb.uiTag = i
            hb.addTarget(UIControlEventTouchUpInside) { if (!longFired) hitParam(it as UIButton); longFired = false }
            val pk = order[i]
            SFLongPress.attach(hb) { resolveTechFromMonitor(if (pk == "hr" || pk == "rr") "ecg" else pk) }   // ANDROID (IN-12)
            view.addSubview(hb)
        }

        // tocco sulle tracce = collegare il sensore al paziente (solo collegare: un nuovo tocco non scollega)
        val traceHits = listOf(Pair(ecg2, "ecg"), Pair(ecgV, "ecg"), Pair(pleth, "spo2"), Pair(art, "art"), Pair(capno, "etco2"))
        for (th in traceHits) {
            val tb = UIButton.buttonWithType(UIButtonTypeCustom)
            tb.frame = th.first.frame
            tb.accessibilityIdentifier = th.second
            tb.addTarget(UIControlEventTouchUpInside) { connectSensor(it as UIButton) }
            val sk = th.second
            SFLongPress.attach(tb) { resolveTechFromMonitor(sk) }                                            // ANDROID (IN-12)
            view.addSubview(tb)
        }
        // bracciale: si mette toccando la colonna delle scritte di stato della NIBP (non apre mai il menu allarmi)
        val cuff = UIButton.buttonWithType(UIButtonTypeCustom)
        cuff.frame = CGRectMake(sx - 4, yNibp, sw + 8, hNibp)
        cuff.accessibilityIdentifier = "nibp"
        cuff.addTarget(UIControlEventTouchUpInside) { connectSensor(it as UIButton) }
        SFLongPress.attach(cuff) { resolveTechFromMonitor("nibp") }                                          // ANDROID (IN-12)
        view.addSubview(cuff)

        // temperatura e refill capillare (sopra i pulsanti)
        label(CGRectMake(numX + 6, yNibp + 6, 130, 18), 15, true, SL_TEMP, NSTextAlignmentLeft).text = "TEMP  °C"
        tempValue = label(CGRectMake(numX, yNibp + 28, numW * 0.5, hNibp - 36), 56 * nk, true, SL_TEMP, NSTextAlignmentCenter)
        temp2Label = label(CGRectMake(numX + numW * 0.5 - 104, yNibp + 6, 100, 18), 15, true, SL_TEMP, NSTextAlignmentRight)   // ANDROID (IN-13): T2
        crtTitle = label(CGRectMake(numX + numW * 0.5 + 6, yNibp + 6, 140, 18), 15, true, SL_WHITE, NSTextAlignmentLeft)
        crtTitle.text = "REFILL  s"
        crtValue = label(CGRectMake(numX + numW * 0.5, yNibp + 28, numW * 0.5, hNibp - 36), 56 * nk, true, SL_WHITE, NSTextAlignmentCenter)
        crtLine = UIView(CGRectMake(numX + numW * 0.5, yNibp + 8, 1, hNibp - 16))
        crtLine.backgroundColor = SL_LINE; view.addSubview(crtLine)
        // refill: è un esame clinico, non una misura del monitor -> si vede solo dopo averlo "eseguito" (tocco sul valore)
        val crtHit = UIButton.buttonWithType(UIButtonTypeCustom)
        crtHit.frame = CGRectMake(numX + numW * 0.5, yNibp, numW * 0.5, hNibp)
        crtHit.addTarget(UIControlEventTouchUpInside) { assessCrt() }
        view.addSubview(crtHit); this.crtHit = crtHit
        // ANDROID (audit 21/11): il titolo «ICU» non apre più il menu dei layout (con nome e versione): il layout si sceglie
        // SOLO dal menu ☰ dell'istruttore (BugFix 22); un partecipante che tocca il titolo non apre nulla
        val tempHit = UIButton.buttonWithType(UIButtonTypeCustom)      // sonda: si collega toccando il valore
        tempHit.frame = CGRectMake(numX, yNibp, numW * 0.5, hNibp); tempHit.accessibilityIdentifier = "temp"
        tempHit.addTarget(UIControlEventTouchUpInside) { if (snap.tempConnected) openMenu("temp") else connectSensor(it as UIButton) }   // ANDROID (IN-09): menu T
        view.addSubview(tempHit)
        nibpButton = button("AVVIA\nNIBP", CGRectMake(8, yNibp + 14, nx - 18, hNibp - 26), SL_NIBP) { startNibp() }
        nibpButton.titleLabel.numberOfLines = 2; nibpButton.titleLabel.textAlignment = NSTextAlignmentCenter
        nibpButton.titleLabel.font = UIFont.boldSystemFontOfSize(20)

        // --- barra inferiore ---
        line(CGRectMake(0, bottom, W, 1))
        val sim = label(CGRectMake(10, bottom + 5, 420, 24), 16, true, SL_AMBER, NSTextAlignmentLeft)
        sim.text = "SIMULAZIONE — NON PER USO CLINICO"
        // batteria (vera, dell'iPad): icona che si riempie col livello, percentuale, fulmine se in carica
        val bx = 470.0; val by = bottom + 9
        battBody = UIView(CGRectMake(bx, by, 38, 17))
        battBody.layer.borderWidth = 1.5; battBody.layer.borderColor = SL_GREY.CGColor; battBody.layer.cornerRadius = 3.0
        view.addSubview(battBody)
        val nub = UIView(CGRectMake(bx + 38, by + 5, 3, 7)); nub.backgroundColor = SL_GREY; view.addSubview(nub)
        battFill = UIView(CGRectMake(2.5, 2.5, 33, 12)); battFill.layer.cornerRadius = 1.5
        battBody.addSubview(battFill)
        battBolt = CAShapeLayer.layer()
        battBolt.path = SFBoltPath(CGRectMake(0, 0, 14, 20)).CGPath; battBolt.frame = CGRectMake(bx + 46, by - 2, 14, 20)
        battBolt.fillColor = SL_YELLOW.CGColor; battBolt.hidden = true
        view.layer.addSublayer(battBolt)
        battPct = label(CGRectMake(bx + 64, bottom + 5, 70, 24), 16, true, SL_WHITE, NSTextAlignmentLeft)
        // ANDROID (IN-14): tasto discreto «TREND» nella barra inferiore (apre la finestra dei trend, di nuovo = chiude)
        trendButton = button("TREND", CGRectMake(630, bottom + 3, 104, 28), SL_GREY) { toggleTrend() }
        trendButton.titleLabel.font = UIFont.boldSystemFontOfSize(14); trendButton.accessibilityIdentifier = "TREND"
        netLabel = label(CGRectMake(W - 380, bottom + 6, 330, 22), 13, false, SL_GREY, NSTextAlignmentRight)
        infoButton = MVCInfoLightButton()
        infoButton.tintColor = SL_GREY
        infoButton.frame = CGRectMake(W - 44, bottom + 1, 40, 32)
        infoButton.addTarget(UIControlEventTouchUpInside) { toggleInfo() }
        view.addSubview(infoButton)
        infoButton.hidden = true            // ANDROID (menu ☰ 21/11): la (i) è nel menu ☰ («Informazioni e collegamenti»)
        // piè di pagina dei layout dei dispositivi (in basso a sinistra, zona libera in tutti i modelli): indirizzo + (i)
        altFooter = UIButton.buttonWithType(UIButtonTypeCustom)
        altFooter.frame = CGRectMake(4, H - 46, 116, 42)
        altFooter.layer.backgroundColor = UIColor.colorWithRed(0.08, 0.09, 0.11, 0.88).CGColor
        altFooter.layer.cornerRadius = 8.0; altFooter.layer.borderWidth = 1.0; altFooter.layer.borderColor = UIColor.colorWithWhite(0.45, 1).CGColor
        altFooter.accessibilityIdentifier = "FOOTER-INFO"; altFooter.hidden = true
        altFooter.addTarget(UIControlEventTouchUpInside) { toggleInfo() }
        val fi = MVCInfoLightButton()
        fi.tintColor = UIColor.colorWithWhite(0.85, 1); fi.userInteractionEnabled = false
        fi.frame = CGRectMake(2, 9, 24, 24)
        altFooter.addSubview(fi)
        altFooterIcon = fi
        altFooterText = UILabel(CGRectMake(27, 3, 86, 36))
        altFooterText.numberOfLines = 2; altFooterText.font = UIFont.boldSystemFontOfSize(10.5)
        altFooterText.textColor = UIColor.colorWithWhite(0.92, 1); altFooterText.adjustsFontSizeToFitWidth = true; altFooterText.minimumScaleFactor = 0.6
        altFooterText.userInteractionEnabled = false
        altFooter.addSubview(altFooterText)
        view.addSubview(altFooter)
        layoutAltFooter(null)                                   // ANDROID (BugFix #11/#13): posizione per layout (MonitorViewControllerMenus)

        // pannello "Collegamento del controller" (tasto (i) di ogni layout): rete Wi-Fi / diretto, PIN, reti, volumi
        // ANDROID (audit 21/11): il pannello sta su un velo nella RADICE (tela + fasce): prima stava nella tela, quindi le prese
        // nelle fasce restavano toccabili, i tocchi attorno al pannello premevano i tasti del layout sotto e la plancia del
        connect = SFConnectPanel(CGRectMake(0, 0, W, H))
        infoPanel = fullScreenScrim(0.55) { if (!connect.busy) infoPanel.hidden = true }
        connect.frame = CGRectMake(floor((infoPanel.bounds.size.width - connect.frame.size.width) / 2), connect.frame.origin.y,
                                   connect.frame.size.width, connect.frame.size.height)
        infoPanel.addSubview(connect)
        infoPanel.hidden = true
        connect.info = { connectInfo() }
        connect.onClose = { infoPanel.hidden = true }
        connect.onVolumes = { showVolumes() }
        screenHost().addSubview(infoPanel)

        // cassetto del defibrillatore: pulsante DEFIB nell'intestazione, si apre sopra Pleth/ART/CO2/NIBP
        drawerTab.titleLabel.font = UIFont.boldSystemFontOfSize(18)
        muteButton.titleLabel.font = UIFont.boldSystemFontOfSize(17)
        drawerY = yPl
        drawer = DefibDrawerView(CGRectMake(0, H, W, bottom - yPl), state, engine, sound)
        drawer.hidden = true
        drawer.onClose = { toggleDrawer() }
        view.addSubview(drawer)

        // i limiti di allarme stanno SOPRA i riquadri colorati dei valori in allarme
        for (l in listOf(hrLimits, spo2Limits, artLimits, etco2Limits, rrLimits, nibpLimits)) view.bringSubviewToFront(l)
        view.bringSubviewToFront(drawer)
        view.bringSubviewToFront(drawerTab)

        val now = CACurrentMediaTime()
        for (t in traces()) t.resetAtTime(now)
        snap = state.snapshot()
        engine.updateWithSnapshot(snap)
        lastRevision = snap.revision
        drawer.updateWithSnapshot(snap)
        val saved = prefs.string("sf_layout")
        if (saved != null && saved != snap.layout) state.applyCommand(mapOf("command" to "set_layout", "value" to saved))
        // ANDROID (collaudo 22/11): nessun layout «a schermo» prima del primo disegno: il primo applyLayout non è un cambio
        // di layout (BugFix #1b non deve chiudere la terapia di uno scenario ripreso dopo una chiusura anomala)
        layoutShown = null

        // All'avvio dello scenario il monitor è SPENTO: GENERIC ICU mostra lo standby,
        // un tocco accende il monitoraggio. Lo stato di accensione è condiviso tra i layout.
        val standby = UIView(CGRectMake(0, 0, W, H))     // ANDROID (ICU 21/11): a tutto schermo come l'ICU
        this.standby = standby
        standby.backgroundColor = UIColor.blackColor
        val sbHit = UIButton.buttonWithType(UIButtonTypeCustom)
        sbHit.frame = standby.bounds; sbHit.accessibilityIdentifier = "STANDBY"
        sbHit.addTarget(UIControlEventTouchUpInside) { standbyTapped() }
        standby.addSubview(sbHit)
        val sbT = UILabel(CGRectMake(0, H / 2 - 76, W, 64))
        sbT.text = "Standby"; sbT.font = UIFont.boldSystemFontOfSize(54)
        sbT.textColor = UIColor.colorWithWhite(0.85, 1); sbT.textAlignment = NSTextAlignmentCenter
        sbT.userInteractionEnabled = false; standby.addSubview(sbT)
        val sbS = UILabel(CGRectMake(0, H / 2 + 2, W, 30))
        sbS.text = "tocca per iniziare il monitoraggio"; sbS.font = UIFont.systemFontOfSize(21)
        sbS.textColor = UIColor.colorWithWhite(0.52, 1); sbS.textAlignment = NSTextAlignmentCenter
        sbS.userInteractionEnabled = false; standby.addSubview(sbS)
        standby.addSubview(SFLayoutButton(CGRectMake(8, 8, 150, 40)) { showLayoutMenu() })
        val sbInfo = MVCInfoLightButton()
        sbInfo.tintColor = UIColor.colorWithWhite(0.6, 1)
        sbInfo.frame = CGRectMake(10, H - 46, 36, 36)
        sbInfo.addTarget(UIControlEventTouchUpInside) { toggleInfo() }
        standby.addSubview(sbInfo)
        sbInfo.hidden = true                // ANDROID (menu ☰ 21/11): anche in standby tutto passa dal menu ☰
        standby.hidden = state.devicePowered
        view.addSubview(standby)

        showSplash(false)

        refreshNetwork()
        updateNumerics()
        slowTick(now)
    }

    // ANDROID (audit 26/11, PERF-F10): la lista si rifà solo se una traccia è cambiata (prima: una listOf nuova a ogni frame)
    private var tracesL: List<TraceView> = emptyList()
    internal fun traces(): List<TraceView> {
        val l = tracesL
        if (l.size != 6 || l[0] !== ecg2 || l[1] !== ecgV || l[2] !== pleth || l[3] !== art || l[4] !== capno || l[5] !== cvpTrace)
            tracesL = listOf(ecg2, ecgV, pleth, art, capno, cvpTrace)
        return tracesL
    }
    internal var longFired = false
    /** ANDROID (IN-12): tocco lungo = «controllo e sistemo» il sensore con il tecnico attivo (se la regia lo permette) */
    internal fun resolveTechFromMonitor(param: String) {
        longFired = true
        val k = SFTechKindFor(param, state.snapshot()) ?: return
        if (SFTryResolveTech(state, param)) menuToast(when (k) { "spo2_probe_off", "spo2_signal" -> "Sonda SpO2 controllata"; "nibp_cuff_error", "nibp_timeout" -> "Bracciale controllato"
            "etco2_occlusion" -> "Linea CO2 sostituita"; "art_line" -> "Linea arteriosa controllata"; else -> "Elettrodi ECG controllati" }, true)
    }
    /** ANDROID (IN-14): finestra Trend sopra tracce e numeri (il tasto TREND la apre e la chiude) */
    internal fun toggleTrend() {
        val W = SFIcuScreenWidth(); val H = min(view.bounds.size.width, view.bounds.size.height)
        trendView = SFTrendToggle(view, CGRectMake(6, 60, W - 12, H - 100), state, SFTrendPaletteIcu, trendView) { trendView = null }
        view.bringSubviewToFront(drawerTab)
    }

    // MARK: - Ciclo di rendering

    fun viewDidAppear() {
        // ANDROID (ICU 21/11): ora la vista è nella tela: l'ICU si allarga a tutto schermo e il ☰ va nella barra superiore
        if (alt == null || alt!!.hidden) fitCanvasForLayout(true)
        placeMenuButton()
        startRendering()
    }

    fun viewWillDisappear() {
        stopRendering()
    }

    // ANDROID (audit 26/11, PERF-F3/BUG-A3): attività chiusa: fermo disegno e tick dello stato (il timer si ripubblicava da solo
    // su Uk.main e teneva viva l'attività vecchia con tutta la sua gerarchia e il suo SimulationState)
    fun dispose() {
        stopRendering()
        stateTimer?.invalidate(); stateTimer = null
    }

    internal fun startRendering() {
        if (!sound.running && !sound.start()) SFLog.log(String.format(Locale.US, "audio non disponibile: %s", sound.lastError))
        if (link != null) return
        // frameInterval: il frame gira ogni _frameInterval fotogrammi dello schermo
        linkFrames = 0
        link = CADisplayLink { l -> linkFrames++; if (linkFrames >= frameInterval) { linkFrames = 0; frame(l) } }.start()
    }

    internal fun stopRendering() {
        link?.invalidate(); link = null
        sound.setPumpRunning(false, 0.0)
        sound.setAlarm(SFAlarmSoundNone)
    }

    internal fun frame(link: CADisplayLink) {
        if (UkProfile.on) { val t0 = System.nanoTime(); frame0(link); UkProfile.tick("frame", System.nanoTime() - t0); UkProfile.frames++; UkProfile.maybeReport { SFLog.log(it) }; return }
        frame0(link)
    }
    private fun frame0(link: CADisplayLink) {
        val now = CACurrentMediaTime()
        state.tickAtTime(now); lastStateTick = now
        var s = state.snapshot()
        snap = s
        if (s.revision != lastRevision) {
            lastRevision = s.revision
            engine.updateWithSnapshot(s)
            updateNumerics()
            drawer.updateWithSnapshot(s)
            if (s.layout != layoutShown) {
                applyLayout(s.layout)
                // ANDROID (BugFix #1b): il cambio di layout può aver riportato il defibrillatore in monitoraggio:
                // il nuovo layout riceve lo stato aggiornato, non quello di prima (DAE/carica/pacing)
                s = snap
            }
            if (alt != null && !alt!!.hidden) altL!!.updateWithSnapshot(s)
        }
        // ANDROID (onde 22/11): all'accensione del monitor, alla ripresa della simulazione e al cambio di layout le onde ripartono
        // TUTTE dal bordo sinistro (origine comune SFSweep.epoch), non dalla colonna a metà schermo in cui cadeva il cursore
        run {
            val altNow = alt != null && !alt!!.hidden
            val onNow = s.simulationRunning && (if (altNow) !altL!!.poweredOff() || state.devicePowered else state.devicePowered)
            if ((onNow && !sweepWasOn) || sweepLayout != layoutShown) it.simulafranz.core.SFSweep.epoch = now
            sweepWasOn = onNow; sweepLayout = layoutShown
        }
        drawer.tick(now, s, drawerOpen)
        val lp = alt != null && !alt!!.hidden
        if (lp && state.devicePowered != powerPushed) altL!!.setDevicePowered(state.devicePowered)   // reset o comando esterno
        powerPushed = state.devicePowered
        standby?.let { it.hidden = lp || state.devicePowered }
        if (lp) { val t1 = if (UkProfile.on) System.nanoTime() else 0L; altL!!.tick(now, s); if (UkProfile.on) UkProfile.tick("alt.tick", System.nanoTime() - t1) }
        if (lp) {
            if (!altL!!.poweredOff()) beatSoundsAt(now, s)   // le tracce GENERIC ICU sono nascoste: non si disegnano
        } else if (s.simulationRunning && state.devicePowered) {
            val t1 = if (UkProfile.on) System.nanoTime() else 0L
            for (t in traces()) t.advanceTo(now)
            if (UkProfile.on) UkProfile.tick("traces.advance", System.nanoTime() - t1)
            beatSoundsAt(now, s)
        } else {
            for (t in traces()) t.resetAtTime(now)
        }
        nibpSoundsAt(now, s)
        // ANDROID (suoni 25/11): CARICA premuto senza piastre o senza cavo: suono di errore, su qualsiasi layout
        run { val q = s.defib.chargeRefusedSeq; if (lastChargeRefused >= 0 && q > lastChargeRefused) sound.errorBuzz(); lastChargeRefused = q }
        // ANDROID (guscio 25/11): scarica erogata, su qualsiasi layout e modo: «cia-clack» del relè e vibrazione del tablet
        run { val q = s.defib.shockCount; if (lastShockHeard >= 0 && q > lastShockHeard) { sound.shockSound(); SFVibra.shock() }; lastShockHeard = q }
        // pacing che stimola davvero: una vibrazione lieve e breve a ogni impulso, alla frequenza effettiva
        run {
            val d = s.defib
            if (d.paceActive && d.paceRate > 0) { if (now >= nextPaceVib) { SFVibra.pace(); nextPaceVib = (if (nextPaceVib > 0 && now - nextPaceVib < 0.5) nextPaceVib else now) + 60.0 / d.paceRate } }
            else nextPaceVib = 0.0
        }
        // ANDROID (guscio 25/11): volume di sistema del tablet comandato dal controller; ogni secondo si rilegge quello vero
        run {
            val q = s.tabletVolumeSeq
            if (lastTabVolSeq >= 0 && q > lastTabVolSeq && s.tabletVolume >= 0) { SFTabletVolume.setPercent(s.tabletVolume); sound.txProgress(); lastTabVolRead = 0.0 }
            lastTabVolSeq = q
            if (now - lastTabVolRead >= 1.0) { lastTabVolRead = now; val p = SFTabletVolume.percent(); if (p >= 0 && p != s.tabletVolume) state.reportTabletVolume(p) }
        }
        if (now - lastSlowTick >= 0.25) { val t1 = if (UkProfile.on) System.nanoTime() else 0L; slowTick(now); if (UkProfile.on) UkProfile.tick("slowTick", System.nanoTime() - t1) }

        val cost = CACurrentMediaTime() - now
        frameCostAvg = frameCostAvg * 0.95 + cost * 0.05
        if (frameInterval == 2 && frameCostAvg > 0.024) {
            frameInterval = 3
            SFLog.log(String.format(Locale.US, "rendering ridotto a 20 fps (costo medio frame %.1f ms)", frameCostAvg * 1000))
        } else if (frameInterval == 3 && frameCostAvg < 0.014) {      // carico passato: si torna a 30 fps
            frameInterval = 2
            SFLog.log(String.format(Locale.US, "rendering di nuovo a 30 fps (costo medio frame %.1f ms)", frameCostAvg * 1000))
        }
    }

    // MARK: - Suoni sincronizzati

    // Bip a ogni battito: sorgente ECG (QRS) se gli elettrodi sono collegati, altrimenti polso SpO2.
    // Tono legato alla SpO2 quando è misurabile, fisso altrimenti (come i monitor reali).
    internal fun beatSoundsAt(now: Double, snapshot: SLSnapshot) {
        val s = snapshot
        var beat = -1.0
        // il bip parte con un piccolo anticipo pari alla latenza dell'audio, così si sente quando il QRS compare sullo schermo
        val kAudioLead = 0.04
        if (s.ecgConnected && s.ecgOrganized) {
            beat = engine.lastBeatAtOrBefore(now + kAudioLead)
        } else if (s.spo2Displayable) {
            // ANDROID (sincronia polso 22/11): arrivo del polso al dito = piede dell'onda pleth dello stesso battito (PEP + transito
            // del battito, dal motore), solo per i battiti con sistole efficace: il bip non suona sui QRS del deficit di polso
            beat = engine.lastPulseArrivalAtOrBefore(now + kAudioLead)
        }
        val isNew = beat > 0 && beat > lastBeepBeat + 0.05
        if (isNew) {
            lastBeepBeat = beat
            hrHeart.alpha = 1.0f
            if (s.qrsBeepEnabled && now - beat < 0.2) {
                val f = if (s.spo2Displayable) SoundEngine.beepFrequencyForSpO2(s.monSpo2) else 660.0
                sound.beepWithFrequency(f, (s.ecgConnected && s.ecgOrganized))
            }
        } else if (now - lastBeepBeat > 0.15) {
            hrHeart.alpha = 0.15f
        }
    }

    internal fun nibpSoundsAt(now: Double, snapshot: SLSnapshot) {
        val s = snapshot
        // ANDROID (audit 21/11): in pausa la pompa e le valvole tacciono (prima, in pausa durante il gonfiaggio, la pompa
        // poteva continuare a suonare finché la fase restava «gonfiaggio»); alla ripresa il suono riparte dalla fase vera
        if (!s.simulationRunning) {
            sound.setPumpRunning(false, 0.0); lastNibpPhase = SLNibpPhaseNone; lastNibpStep = -1
            return
        }
        val pt = s.nibpPhaseAt(now)
        val p = pt.pressure; val step = pt.step
        val ph = pt.phase
        sound.setPumpRunning((ph == SLNibpPhaseInflating), p)
        if (ph == SLNibpPhaseDeflating && step != lastNibpStep) sound.valveStep()
        if (ph == SLNibpPhaseDumping && lastNibpPhase != SLNibpPhaseDumping) sound.valveDump()
        lastNibpPhase = ph; lastNibpStep = if (ph == SLNibpPhaseDeflating) step else -1
    }

    // MARK: - Aggiornamenti

    internal fun updateNumerics() {
        val s = snap
        sound.volume = s.volume / 100.0

        // ECG
        var hr = s.monDisplayedHeartRate
        // ritmi di arresto riconosciuti dall'ECG: sigla al posto del numero
        var code: String? = null
        if (s.ecgConnected) {
            if (s.rhythm == SLRhythmVentricularTachy || s.rhythm == SLRhythmVentricularTachyPulse) code = "TV"
            else if (s.rhythm == SLRhythmVentricularFib) code = "FV"
            else if (s.rhythm == SLRhythmAsystole && !s.defib.paceCapture) code = "Asy"
        }
        if (hr > 0 && code == null) hr = max(1, hr + MVCLround(jHR * (if (s.rhythm == SLRhythmAtrialFibrillation) 2.5 else 1.0)))
        hrValue.text = code ?: (if (hr < 0) "---" else String.format(Locale.US, "%d", hr))
        hrLimits.text = if (s.alarmOn("hr")) SLLim(s.limit("hr_high"), s.limit("hr_low")) else "OFF"
        // ANDROID (IN-01): «SCOLLEGATI» solo se gli elettrodi erano in uso; mai collegati = ECG spento («NON COLLEGATI»)
        val leadsOff = if (s.ecgConnected) null else (if (s.ecgLeadsOffAlarm) "ELETTRODI SCOLLEGATI" else "ELETTRODI NON COLLEGATI")
        ecg2.noSignalText = leadsOff; ecg2.disconnected = !s.ecgConnected
        ecgV.noSignalText = leadsOff; ecgV.disconnected = !s.ecgConnected

        // SpO2
        val spo2ok = s.spo2Displayable
        spo2Value.text = spo2TextAt(CACurrentMediaTime(), s)
        prValue.text = if (spo2ok) String.format(Locale.US, "%d", max(1, s.monPulseRate + MVCLround(jHR))) else "--"
        spo2Limits.text = if (s.alarmOn("spo2")) SLLim(if (s.limit("spo2_high") > 0) s.limit("spo2_high") else -1, s.limit("spo2_low")) else "OFF"   // ANDROID (IN-09)
        pleth.disconnected = !s.spo2Connected
        pleth.flatWhenNoSignal = s.spo2Connected && (!s.pulsePresent || s.spo2Searching)   // collegata ma senza polso: linea piatta
        pleth.noSignalText = if (!s.spo2Connected) (if (s.spo2ProbeOffAlarm) "SONDA SpO2 SCOLLEGATA" else "SONDA SpO2 NON COLLEGATA")
                             else (if (s.spo2Searching) "RICERCA POLSO…" else (if (!s.pulsePresent) "NESSUN POLSO" else null))

        // ART
        if (s.artConnected && (s.artNoNumbers || s.monArtSystolic < 0)) {      // ANDROID (IN-03): zero in corso / da azzerare
            artValue.text = "---/---"; artMap.text = "(---)"
        } else if (s.artConnected) {
            var `as` = s.monArtSystolic; var ad = s.monArtDiastolic
            if (s.pulsePresent && !s.artOccluded) { `as` = max(1, `as` + MVCLround(jSys)); ad = max(0, min(`as` - 5, ad + MVCLround(jDia))) }
            artValue.text = String.format(Locale.US, "%d/%d", `as`, ad)
            artMap.text = String.format(Locale.US, "(%d)", (`as` + 2 * ad) / 3)
        } else { artValue.text = "--/--"; artMap.text = "" }
        // ANDROID (IN-09): limiti propri dell'ART (sistolica); la PAM, se accesa, nel menu del parametro
        artLimits.text = if (s.alarmOn("art")) SLLim(s.limit("art_sys_high"), s.limit("art_sys_low")) else "OFF"
        ppvLabel.text = if (s.ppvShown >= 0) String.format(Locale.US, "PPV %d %%", s.ppvShown) else (SFArtLineStatus(s) ?: "")
        // ANDROID (IN-13): PVC nella riga ART (traccia sotto, numero sotto la media) solo col trasduttore collegato
        if (icuHidden == null && s.cvpConnected != cvpShown) {
            cvpShown = s.cvpConnected
            art.frame = if (cvpShown) CGRectMake(artRow.x, artRow.y, artRow.width, 74.0) else artRow
            cvpTrace.hidden = !cvpShown; cvpTrace.resetAtTime(CACurrentMediaTime()); art.resetAtTime(CACurrentMediaTime())
        }
        cvpValue.text = if (!s.cvpConnected) "" else if (s.cvpNeedsZero) "PVC  AZZERARE" else if (s.monCvp < 0) "PVC  ---" else String.format(Locale.US, "PVC  %d", s.monCvp)
        art.noSignalText = if (s.artConnected) null else (if (s.artLineOffAlarm) "TRASDUTTORE ART SCOLLEGATO" else "TRASDUTTORE ART NON COLLEGATO"); art.disconnected = !s.artConnected

        // CO2 / FR (FR da capnografia; se CO2 assente, da impedenza toracica ECG)
        etco2Value.text = if (s.etco2Connected && s.monRespRate > 0) String.format(Locale.US, "%d", s.monEtco2) else "--"
        etco2Limits.text = if (s.alarmOn("etco2")) SLLim(s.limit("etco2_high"), s.limit("etco2_low")) else "OFF"
        rrLabel.text = "FR  atti/min"   // misurata: da capnografia oppure, senza CO2, dall'impedenza toracica degli elettrodi ECG
        rrValue.text = if (s.etco2Connected || s.ecgConnected) String.format(Locale.US, "%d", s.monRespRate) else "--"
        rrLimits.text = if (s.alarmOn("rr")) SLLim(s.limit("rr_high"), s.limit("rr_low")) else "OFF"
        // ANDROID (ICU 21/11): con la linea occlusa (distrattore) il sensore è collegato: la traccia dice l'occlusione
        capno.noSignalText = if (s.etco2Connected) null
                             else (if (s.technicalAlarms.contains("etco2_occlusion")) "CO2 LINEA OCCLUSA" else "SENSORE CO2 NON COLLEGATO")
        capno.disconnected = !s.etco2Connected

        // NIBP
        if (s.nibpLastSystolic >= 0) {
            nibpValue.text = String.format(Locale.US, "%d/%d", s.nibpLastSystolic, s.nibpLastDiastolic)
            nibpMap.text = String.format(Locale.US, "(%d)", s.meanArterialPressure)
        } else if (s.nibpStatus == SLNibpError && s.nibpError == "no_pulse") {
            nibpValue.text = "?/?"; nibpMap.text = ""             // misura fallita: nessun polso rilevato
        } else { nibpValue.text = "---/---"; nibpMap.text = "" }
        nibpLimits.text = if (s.alarmOn("nibp")) SLLim(s.limit("nibp_sys_high"), s.limit("nibp_sys_low")) else "OFF"   // ANDROID (IN-09)

        // temperatura (solo con la sonda) e refill capillare (valutazione clinica impostata dall'istruttore)
        tempValue.text = if (s.tempConnected) String.format(Locale.US, "%.1f", s.monTemperatureTenths / 10.0) else "--.-"
        temp2Label.text = if (s.temp2Connected) String.format(Locale.US, "T2  %.1f", s.monTemperature2Tenths / 10.0) else ""   // ANDROID (IN-13)
        updateCrt()
        if (menuPanel != null && menuDim?.hidden == false) refreshMenu()

    }

    internal fun slowTick(now: Double) {
        updateBattery()
        lastSlowTick = now
        tick++
        if (tick % 2 == 0) flashOn = !flashOn       // lampeggio a 1 Hz
        clock.text = clockFmt.format(SFWallClock.now())   // ANDROID (ora 22/11): ora sincronizzata con il controller

        // stato NIBP con pressione del bracciale in tempo reale
        val s = snap
        val pt = s.nibpPhaseAt(now)
        val p = pt.pressure
        val ph = pt.phase
        if (s.nibpStatus == SLNibpMeasuring) {
            val what = if (ph == SLNibpPhaseInflating) "Gonfiaggio" else if (ph == SLNibpPhaseDumping) "Sgonfiaggio" else "Misurazione"
            nibpStatus.text = String.format(Locale.US, "%s…  bracciale %d mmHg", what, MVCLround(p))
        } else if (s.nibpStatus == SLNibpError) {
            nibpStatus.text = if (s.nibpError == "cuff") "Errore: bracciale non collegato / perdita"
                              else if (s.nibpError == "timeout") "Tempo di misura superato"       // ANDROID (IN-18)
                              else (if (s.nibpError == "motion") "Artefatto da movimento: misura non riuscita" else "Nessun polso rilevato")
        } else if (s.nibpStatus == SLNibpComplete && s.nibpMeasuredAt != null) {
            nibpStatus.text = String.format(Locale.US, "Ultima misura %s", nibpFmt.format(s.nibpMeasuredAt!!))
        } else {
            nibpStatus.text = if (s.nibpConnected) "Nessuna misura" else "Bracciale non collegato"
        }

        // SpO2 in bassa perfusione: il "?" SOSTITUISCE il valore a intermittenza (come i monitor reali)
        // ciclo di 3 s: "?" per 2 s, valore per 1 s
        // variabilità fisiologica: piccola deriva casuale limitata, aggiornata ogni ~2 s (come i numerici dei monitor reali)
        if (now - lastJitter >= 2.0) {
            lastJitter = now
            val walk: (Double, Double) -> Double = { v0, lim ->
                var v = v0
                v += (rnd.nextInt(1001).toDouble() / 1000.0 - 0.5) * lim * 0.9
                max(-lim, min(lim, v * 0.85))
            }
            jHR = walk(jHR, 2.0); jSpO2 = walk(jSpO2, 1.0); jSys = walk(jSys, 3.0); jDia = walk(jDia, 2.0)
            updateNumerics()
            if (alt != null && !alt!!.hidden) { snap = state.snapshot(); altL!!.updateWithSnapshot(snap) }   // anche i dispositivi: valori non fissi (stato attuale, mai una copia vecchia)
        }
        spo2Value.text = spo2TextAt(now, s)
        if (crtAssessAt > 0) updateCrt()

        // NIBP automatica: etichetta e barra che si accorcia
        if (s.nibpIntervalMin > 0) {
            val rem = max(0.0, s.nibpNextAt - now); val tot = s.nibpIntervalMin * 60.0
            nibpAutoLabel.text = String.format(Locale.US, "AUTO ogni %d min\nprossima tra %d:%02d",
                                               s.nibpIntervalMin, rem.toLong() / 60, rem.toLong() % 60)
            nibpBarTrack.hidden = false
            var f = nibpBarTrack.bounds; f = CGRect(f.x, f.y, f.width * min(1.0, rem / tot), f.height)
            if (nibpBarFill.frame != f) nibpBarFill.frame = f
        } else {
            nibpAutoLabel.text = ""; nibpBarTrack.hidden = true
        }

        // allarmi
        val alarmResult = AlarmManager.evaluate(s)
        this.alarmResult = alarmResult
        // ANDROID (difetti clinici 22/11, IN-04): un NUOVO allarme alto letale (FV/TV/asistolia/apnea) durante la pausa audio
        // la interrompe in tutti i layout (quelli già presenti quando si è silenziato restano silenziati)
        if (alarmPauseGuard.update(s.alarmsMuted && s.simulationRunning, alarmResult)) {
            SLCommandSource.with("cuore") { state.applyCommand(mapOf("command" to "mute_alarms", "value" to false)) }   // ANDROID (IN-06): registro
            snap = state.snapshot()
        }
        var snd = SFAlarmSoundNone
        if (!s.alarmsMuted) {
            if (alarmResult.level == SLAlarmHigh) snd = SFAlarmSoundHigh
            else if (alarmResult.level == SLAlarmMedium) snd = SFAlarmSoundMedium
            else if (alarmResult.level == SLAlarmTechnical) snd = SFAlarmSoundLow
        }
        snd = SFTechnicalOnlySound(s, snd)     // ANDROID (ICU 21/11): CO2 occlusa / batteria scarica simulate: tono tecnico
        val altOff = if (alt != null && !alt!!.hidden) altL!!.poweredOff() else !state.devicePowered
        if (altOff) snd = SFAlarmSoundNone                         // dispositivo spento o in standby: nessun allarme sonoro
        if (CACurrentMediaTime() < volAlarmPreviewUntil && snd == SFAlarmSoundNone) snd = SFAlarmSoundMedium   // prova del volume allarmi
        sound.setAlarm(snd)
        updatePauseCover(s)
        SFEndJingle.update(s.scenarioEnded, sound.volume)   // ANDROID (storia sim 22/11): jingle una volta a end_scenario
        if (alt != null && !alt!!.hidden) {
            var msg: String? = alarmResult.messages.firstOrNull()
            if (alarmResult.messages.size > 1) msg = msg + String.format(Locale.US, "  (+%d)", alarmResult.messages.size - 1)
            msg = SFAlarmText(s.layout, msg)                            // testi nello stile del dispositivo
            altL!!.setAlarmText(msg, alarmResult.level, alarmResult.severity, s.alarmsMuted, s.alarmsMutedRemaining)
        }
        muteButton.setTitle(if (s.alarmsMuted) String.format(Locale.US, "SILENZIATO %d s", s.alarmsMutedRemaining) else "SILENZIA 20 s",
                            UIControlStateNormal)
        // ANDROID (ICU 21/11): allarmi clinici (rosso/giallo, lampeggianti) e tecnici (ciano fisso) in due campi
        // distinti della barra superiore, ognuno a rotazione (SFIcuWide.kt). Il tempo residuo del silenzio sta sul pulsante.
        updateIcuAlarmFields(alarmResult, s)
        // valori in allarme: lampeggia TUTTA la cella del parametro (titolo, limiti e valori)
        //   medio  -> cella GIALLA con testi NERI, alternata all'aspetto normale (1 Hz)
        //   alto   -> cella ROSSA con testi BIANCHI, alternata all'aspetto normale (2 Hz)
        val sev = alarmResult.severity
        if (alarmBoxes == null) alarmBoxes = HashMap()
        if (cellLabels == null) cellLabels = HashMap()
        for (k in listOf("hr", "spo2", "art", "etco2", "rr", "nibp")) {
            val cell = cellRects[k]!!
            var box = alarmBoxes!![k]
            if (box == null) {
                box = UIView(cell)
                box.hidden = true; box.userInteractionEnabled = false
                view.insertSubview(box, 0)                // dietro a tutto il contenuto della cella
                alarmBoxes!![k] = box
                val ls = ArrayList<Pair<UILabel, UIColor>>()            // etichette della cella e loro colore normale
                for (v in view.subviews)
                    if (v is UILabel && CGRectContainsPoint(cell, CGPointMake(CGRectGetMidX(v.frame), CGRectGetMidY(v.frame))))
                        ls.add(Pair(v, v.textColor))
                cellLabels!![k] = ls
            }
            if (icuHidden != null) { box.hidden = true; continue }              // sotto un layout di dispositivo: niente lampeggi invisibili
            val lvl = sev[k] ?: 0
            val on = if (lvl == SLAlarmHigh) (tick % 2 == 0) else (if (lvl == SLAlarmMedium) flashOn else false)
            box.hidden = !on
            if (on) { val bc = if (lvl == SLAlarmHigh) SL_ALARM else SL_YELLOW; if (box.backgroundColor != bc) box.backgroundColor = bc }
            val ink = if (lvl == SLAlarmHigh) UIColor.whiteColor else UIColor.blackColor
            for (pair in cellLabels!![k]!!) {
                val l = pair.first
                if (l.backgroundColor != UIColor.clearColor) l.backgroundColor = UIColor.clearColor
                l.textColor = if (on) ink else pair.second
            }
            if (k == "hr") {                          // anche il cuore cambia colore con la cella
                val heart = hrHeart.layer.sublayers.firstOrNull()
                if (heart is CAShapeLayer) { val hc = (if (on) ink else SL_GREEN).CGColor; if (heart.fillColor != hc) heart.fillColor = hc }
            }
        }

        if (now - lastNetCheck >= 3.0) refreshNetwork()
        if (!infoPanel.hidden) {
            updateInfoText()
            if (!connect.busy && now - max(infoShownAt, connect.lastInteraction) > 45) infoPanel.hidden = true   // il PIN non resta in vista
        }
    }


    // Testo SpO2 (unico punto di decisione, niente sfarfallii):
    // bassa perfusione -> "?" per 4 s e valore per 2 s, ciclo che parte sempre dal "?"
    internal fun spo2TextAt(now: Double, snapshot: SLSnapshot): String {
        val s = snapshot
        if (!s.spo2Displayable) { lowPerfSince = 0.0; return "--" }
        val `val` = String.format(Locale.US, "%d", shownSpO2(s))
        if (!s.lowPerfusion) { lowPerfSince = 0.0; return `val` }
        if (lowPerfSince == 0.0) lowPerfSince = now
        return if ((now - lowPerfSince) % 6.0 < 4.0) "?" else `val`
    }

    // SpO2 mostrata: valore impostato ± 1 (mai oltre 100)
    internal fun shownSpO2(s: SLSnapshot): Int {
        return max(0, min(100, s.monSpo2 + MVCLround(jSpO2)))
    }

    internal fun updateBattery() {
        // ANDROID (audit 21/11): lettura comune SFBatteryRead (al più ogni 2 s: prima registerReceiver 4 volte al secondo)
        // e colore comune SFBatteryColor, come in tutti gli altri layout (prima qui il giallo 20–40 % era l'arancio SL_AMBER)
        val bs = SFBatteryRead()
        // ANDROID (ICU 21/11): batteria scarica simulata (distrattore low_battery): l'icona mostra 5 % in rosso, senza carica
        val simLow = ::snap.isInitialized && snap.simulationRunning && snap.technicalAlarms.contains("low_battery")
        // ANDROID (guasti 22/11): malfunzionamento simulato della batteria (battery_low gialla, battery_replace rossa quasi vuota)
        val sim = if (::snap.isInitialized) SFBatterySimFor(snap) else null
        val lvl: Float = if (sim != null) sim.level else if (simLow) 0.05f else bs.level
        val chg = if (simLow || sim != null) false else bs.charging; val full = false
        if (lvl == battLvl && chg == battChg && full == battFull) return     // niente di cambiato: non si ridisegna
        battLvl = lvl; battChg = chg; battFull = full
        val f = if (lvl < 0) 1.0 else max(0.04, lvl.toDouble())
        // stessa regola in tutti i layout (SFBatteryColor): verde > 40 %, giallo 20–40 %, rosso < 20 %
        val c = SFBatteryColor(lvl)
        battFill.frame = CGRectMake(2.5, 2.5, 33 * f, 12); battFill.backgroundColor = c
        battPct.text = if (lvl < 0) "--%" else String.format(Locale.US, "%d%%", Math.round(lvl * 100))
        battPct.textColor = if (lvl < 0 || lvl > 0.40f) SL_WHITE else c
        CATransaction.begin(); CATransaction.setDisableActions(true)
        battBolt.hidden = !(chg || full)
        CATransaction.commit()
    }

    internal fun refreshNetwork() {
        lastNetCheck = CACurrentMediaTime()
        ip = NetworkInfo.wifiIPv4Address()
        netLabel.text = if (ip != null) String.format(Locale.US, "http://%s:%d", ip, serverPort)
                        else "Nessuna rete — collegamento diretto disponibile"
        // ANDROID: con il Wi-Fi Direct il controller funziona anche senza rete: il riquadro dice lo stato vero
        refreshMenuStatus()                                    // ANDROID: pallino del tasto ☰ e avvisi di collegamento
        val ctl = (linkInfo?.invoke()?.get("controller") as? Map<*, *>)
        altFooterText.text = if (ctl != null) "Controller\ncollegato" else "Controller\nin attesa"
        altFooterText.textColor = if (ctl != null) UIColor.colorWithRed(0.35, 0.90, 0.45) else UIColor.colorWithWhite(0.80, 1)
    }

    // dati del pannello "Collegamento": quelli dell'app (server, canale diretto, controller attivo) + rete e audio
    internal fun connectInfo(): Map<String, Any?> {
        val d = LinkedHashMap<String, Any?>(linkInfo?.invoke() ?: emptyMap())
        if (ip != null) d["ip"] = ip
        d["port"] = serverPort
        d["pin"] = pinProvider?.invoke() ?: "?"
        d["serverStatus"] = serverStatus ?: "?"
        d["audio"] = if (sound.running) "attivo" else (sound.lastError ?: "non attivo")
        d["deviceId"] = state.deviceId
        return d
    }

    internal fun updateInfoText() { connect.refresh() }
}
