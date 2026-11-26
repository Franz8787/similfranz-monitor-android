// Portato da src/ExtraHView.m (monitor iPad)
// similFranz Monitor — layout "extraH": monitor-defibrillatore preospedaliero ORIGINALE (non riproduce alcun
// dispositivo in commercio: disposizione dei tasti, colori, proporzioni e testi sono di similFranz).
// ANDROID (layout 21/11): disposizione per lo schermo intero del tablet (vedi "Scocca" più sotto):
//   fianco sinistro  le prese dei sensori (ECG, SpO2, NIBP, CO2, TEMP, piastre)
//   al centro        lo schermo grande: tracce ECG / pletismografia / CO2, numerici, riquadro della terapia elettrica
//   sotto            MONITORAGGIO (NIBP, derivazione) e PACER (frequenza, uscita, modo, avvio)
//   a destra         la console di terapia: DEFIBRILLATORE 1 energia, 2 carica, 3 scarica e, nella fascia destra,
//                    accensione, selettore del modo, allarmi, altoparlante, menu
// Frontale digitale pensato per un guscio stampato in 3D.
package it.simulafranz.monitor

import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import it.simulafranz.core.*
import it.simulafranz.uk.*
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.WeakHashMap

private fun EH(r: Number, g: Number, b: Number): UIColor = UIColor.colorWithRed(r.toDouble() / 255.0, g.toDouble() / 255.0, b.toDouble() / 255.0, 1)

// Aggiornamenti "solo se cambia": il layout si aggiorna a ogni lettura dello stato e ogni 0,25 s. Riassegnare lo stesso
// testo, colore o titolo a etichette e tasti costringeva UIKit a ridisegnarli ogni volta (con i tasti in rilievo
// costa molto, sia all'app sia al compositore di sistema): ora si tocca una vista solo quando il valore cambia davvero.
private fun EHSetText(l: UILabel, t: String?) { if (l.text != t) l.text = t }
private fun EHSetColor(l: UILabel, c: UIColor) { if (l.textColor != c) l.textColor = c }
private fun EHSetBg(v: UIView, c: UIColor) { if (v.backgroundColor != c) v.backgroundColor = c }
private fun EHSetFrame(v: UIView, f: CGRect) { if (v.frame != f) v.frame = f }
private fun EHSetHidden(v: UIView, h: Boolean) { if (v.hidden != h) v.hidden = h }
private fun EHSetAlpha(v: UIView, a: Double) { if (Math.abs(v.alpha - a) > 0.001) v.alpha = a.toFloat() }
private fun EHSetTitle(b: UIButton, t: String) { if (b.titleForState(UIControlStateNormal) != t) b.setTitle(t, UIControlStateNormal) }
private fun EHSetTitleColor(b: UIButton, c: UIColor) { if (b.titleColorForState(UIControlStateNormal) != c) b.setTitleColor(c, UIControlStateNormal) }
private val EH_ACCENT = EH(214, 132, 52)      // arancio smorzato: il colore di extraH (solo filetti e scritte piccole)
private val EH_KEY = EH(74, 79, 86)           // tasti in gomma scura
private val EH_KEYHI = EH(96, 102, 110)
private val EH_HR = EH(70, 235, 110)
private val EH_SPO2 = EH(60, 205, 255)
private val EH_NIBP = EH(240, 240, 245)
private val EH_CO2 = EH(245, 215, 70)
private val EH_TEMP = EH(205, 160, 255)
private val EH_WHITE = EH(236, 238, 242)
private val EH_GREY = EH(150, 156, 164)
private val EH_RED = EH(226, 70, 56)
private val EH_AMBER = EH(214, 168, 70)
private val EH_MODE_ON = EH(255, 160, 50)     // spia e bordo del modo scelto
private val EH_SHOCK = EH(226, 82, 34)        // ANDROID (layout 21/11): tasto di scarica arancio-rosso (come sulle macchine vere)
private val EH_LINE = EH(96, 101, 110)        // filetti dei pannelli

// I messaggi del modello dicono "shock": su extraH il tasto si chiama SCARICA, e i messaggi lo seguono.
private fun EHText(m0: String?): String? {
    var m = m0
    if (m.isNullOrEmpty()) return m
    for (r in listOf(listOf("SHOCK NON CONSIGLIATO", "SCARICA NON CONSIGLIATA"), listOf("SHOCK CONSIGLIATO", "SCARICA CONSIGLIATA"),
                     listOf("SHOCK EROGATO", "SCARICA EROGATA"), listOf("SHOCK", "SCARICA"), listOf("Shock", "Scarica")))
        m = m!!.replace(r[0], r[1])
    return m
}

// --- aiuti privati (non esistono in `uk`) ---
// [[NSBundle mainBundle] objectForInfoDictionaryKey:@"CFBundleShortVersionString"]
private fun EHBundleVersion(): String =
    SFVersionShown()
// UIDevice batteryLevel (0..1, < 0 = sconosciuto) e batteryState (in carica o piena)
private class EHBatteryInfo(val level: Float, val charging: Boolean)
// ANDROID (audit 21/11): lettura comune SFBatteryRead (al più ogni 2 s; prima registerReceiver 4 volte al secondo)
private fun EHBattery(): EHBatteryInfo { val b = SFBatteryRead(); return EHBatteryInfo(b.level, b.charging) }

class ExtraHView(frame: CGRect, state: SimulationState, engine: WaveformEngine, sound: SoundEngine) : UIView(frame), SFAltLayout {
    override var onLayoutMenu: (() -> Unit)? = null
    override var onInfo: (() -> Unit)? = null
    override var onVolumes: (() -> Unit)? = null

    private val _state: SimulationState = state; private val _engine: WaveformEngine = engine; private val _sound: SoundEngine = sound
    private var _snap: SLSnapshot
    private var _off = false; private var _flattened = false; private var _pads = false        // _pads: la traccia ECG viene dalle piastre (nessun elettrodo collegato)
    private val _keys = LinkedHashMap<String, UIButton>()
    private val _plugMark = LinkedHashMap<String, UIView>(); private val _modeLed = LinkedHashMap<String, UIView>()
    private lateinit var _screen: UIView; private lateinit var _offCover: UIView; private lateinit var _powerLed: UIView
    private lateinit var _chargeTrack: UIView; private lateinit var _chargeFill: UIView; private lateinit var _alarmLamp: UIView
    private lateinit var _ecg: TraceView; private lateinit var _pleth: TraceView; private lateinit var _co2: TraceView
    private lateinit var _clock: UILabel; private lateinit var _modeLbl: UILabel; private lateinit var _batt: UILabel; private lateinit var _battIcon: SFBatteryIcon
    private lateinit var _elapsed: UILabel; private lateinit var _msg: UILabel; private lateinit var _ecgLbl: UILabel
    private lateinit var _hr: UILabel; private lateinit var _sp: UILabel; private lateinit var _nibp: UILabel; private lateinit var _nibpSt: UILabel
    private lateinit var _et: UILabel; private lateinit var _rr: UILabel; private lateinit var _temp: UILabel
    private lateinit var _shockLamp: UIView; private lateinit var _leadKeyNote: UILabel
    private var _leadPads = false      // _leadPads: con elettrodi e piastre collegati, la derivazione scelta dall'operatore
    private lateinit var _thTitle: UILabel; private lateinit var _thBig: UILabel; private lateinit var _thBig2: UILabel
    private lateinit var _thState: UILabel; private lateinit var _thSub: UILabel; private lateinit var _thSync: UILabel
    private var _popup: UIView? = null
    // ANDROID (extraH 25/11): ECG a 12 derivazioni e trasmissione simulata, dal MENU del dispositivo
    private var _ecg12: SFEcg12View? = null
    private var _tx: SFEcg12TxJob? = null
    private var _txRec: SFEcg12Tx.Record? = null
    private var _alarmText: String? = null; private var _note: String? = null
    private var _alarmLevel = 0; private var _mutedRem = 0; private var _muted = false; private var _severity: Map<String, Int>? = null
    private var _onAt = 0.0; private var _lastSlow = 0.0; private var _noteUntil = 0.0
    private val _beeper = SFFaultBeeper()          // ANDROID (guasti 22/11): bip da spento dei malfunzionamenti simulati
    private val _fmt = SimpleDateFormat("dd/MM/yyyy  HH:mm:ss", Locale.getDefault())
    private val _fmtHM = SimpleDateFormat("HH:mm", Locale.getDefault())
    // objc_setAssociatedObject(b, "ehbg", …): colore di fondo originale di un tasto
    private val _ehbg = WeakHashMap<UIButton, UIColor>()

    init {
        _snap = state.snapshot()
        _off = !state.devicePowered
        backgroundColor = EH(22, 23, 26)
        buildBody()
        buildScreen()
        buildKeys()
        _offCover.hidden = !_off
        if (!_off) _onAt = CACurrentMediaTime()
        updateWithSnapshot(_snap)
    }

    // la decorazione fissa (scocca, scritte, sfumature) diventa una sola immagine: meno lavoro per il compositore
    override fun didMoveToWindow() {
        super.didMoveToWindow()
        if (window == null || _flattened) return
        _flattened = true
        Uk.async {
            val keep = arrayListOf(_powerLed, _alarmLamp)
            keep.addAll(_modeLed.values)
            SFFlattenStatic(this, keep)
        }
    }

    // MARK: - Utilità

    private fun box(f: CGRect, color: UIColor, radius: Number, `in`: UIView): UIView {
        val b = UIView(f)
        b.backgroundColor = color; b.layer.cornerRadius = radius.toDouble(); b.userInteractionEnabled = false
        `in`.addSubview(b)
        return b
    }
    private fun label(f: CGRect, `in`: UIView, size: Number, bold: Boolean, color: UIColor, align: Int): UILabel {
        val l = UILabel(f)
        l.backgroundColor = UIColor.clearColor; l.textColor = color; l.textAlignment = align
        l.font = if (bold) UIFont.boldSystemFontOfSize(size) else UIFont.systemFontOfSize(size)
        l.adjustsFontSizeToFitWidth = true; l.minimumScaleFactor = 0.5; l.userInteractionEnabled = false
        `in`.addSubview(l)
        return l
    }
    private fun gradient(f: CGRect, colors: List<UIColor>, radius: Number, `in`: UIView): CAGradientLayer {
        val h = UIView(f); h.userInteractionEnabled = false; h.layer.cornerRadius = radius.toDouble()
        val g = CAGradientLayer.layer(); g.frame = h.bounds; g.cornerRadius = radius.toDouble()
        val cg = ArrayList<UIColor>(); for (c in colors) cg.add(c.CGColor)
        g.colors = cg; h.layer.addSublayer(g); `in`.addSubview(h)
        return g
    }
    // tasto fisico: gomma scura con scritta chiara (o colore dato), in rilievo e con animazione di pressione
    private fun key(name: String, title: String, frame: CGRect, bg: UIColor, fg: UIColor, size: Number, action: ((UIButton) -> Unit)?): UIButton {
        return key(name, title, frame, bg, fg, size, 9, action)
    }
    // ANDROID (layout 21/11): `parent` = dove sta il tasto (il frontale, oppure la parte della console nella fascia destra)
    private fun key(name: String, title: String, frame: CGRect, bg: UIColor, fg: UIColor, size: Number, radius: Number, action: ((UIButton) -> Unit)?, parent: UIView = this): UIButton {
        val b = UIButton.buttonWithType(UIButtonTypeCustom).also { SFTasti.register(name, it) }
        b.frame = frame; b.backgroundColor = bg; b.layer.cornerRadius = radius.toDouble()
        b.layer.borderWidth = 1.0; b.layer.borderColor = UIColor.colorWithWhite(0, 0.55).CGColor
        b.setTitle(title, UIControlStateNormal)
        b.setTitleColor(fg, UIControlStateNormal)
        b.titleLabel.font = UIFont.boldSystemFontOfSize(size)
        b.titleLabel.numberOfLines = 2; b.titleLabel.textAlignment = NSTextAlignmentCenter; b.titleLabel.lineBreakMode = NSLineBreakByWordWrapping
        b.titleEdgeInsets = UIEdgeInsetsMake(0, 4, 0, 4)
        if (action != null) b.addTarget(UIControlEventTouchUpInside) { action(it as UIButton) }
        parent.addSubview(b)
        _keys[name] = b
        SFTactile.applyToFace(b, b, radius.toDouble(), 1.0)
        // tasto fermo = una sola immagine per il compositore (ombra, riflesso e scritta insieme): si rifà solo se il tasto cambia
        b.layer.shouldRasterize = true; b.layer.rasterizationScale = Uk.scale.toDouble()
        return b
    }

    // MARK: - Scocca

    // ANDROID (layout 21/11): extraH riprogettato per lo schermo intero del tablet (tela 1024 + fascia destra 102 punti).
    // Disposizione da monitor-defibrillatore professionale:
    //   in alto        targhetta e spia d'allarme
    //   a sinistra     lo schermo grande (808×604), sotto la fila MONITORAGGIO + PACER (il pacer è un gruppo a sé, come sui
    //                  defibrillatori con stimolatore: frequenza e uscita con −/+ affiancati, modo, avvio)
    //   a destra       la CONSOLE DI TERAPIA a tutta altezza: colonna DEFIBRILLATORE 1-2-3 (analisi, energia, carica, scarica:
    //                  il tasto di scarica è il più grande, in basso a destra, dove cade la mano) e, nella fascia destra dello
    //                  schermo, la colonna di comando: accensione, selettore del MODO, allarmi, altoparlante, MENU.
    // La console è un solo pannello: la parte oltre il bordo della tela è disegnata nella fascia (SFSideDeck), con un
    // filetto verticale proprio sul bordo che separa le due colonne (e nasconde la giunzione).
    // (misure in companion object: servono già durante init, prima degli inizializzatori della classe)
    private lateinit var _deck: UIView
    // rettangolo della colonna di comando in coordinate della fascia
    private fun B(x: Double, y: Double, w: Double, h: Double): CGRect = CGRectMake(x - DX, y, w, h)

    // pannello di un gruppo di tasti: fondo appena più scuro, filetto sottile e legenda piccola in alto a sinistra
    private fun panel(f: CGRect, legend: String) {
        val p = box(f, EH(40, 43, 48), 10, this)
        p.layer.borderWidth = 1.0; p.layer.borderColor = EH_LINE.CGColor
        label(CGRectMake(f.x + 10, f.y + 5, f.width - 20, 13), this, 9.5, true, EH_GREY, NSTextAlignmentLeft).text = legend
    }
    // passo numerato del defibrillatore: numero in un cerchietto pieno + nome del passo, su tutta la colonna
    private fun stepLabel(n: String, text: String, at: CGPoint) {
        val o = at
        val c = box(CGRectMake(o.x, o.y, 20, 20), EH_ACCENT, 10, this)
        label(c.bounds, c, 12.5, true, EH(30, 22, 14), NSTextAlignmentCenter).text = n
        label(CGRectMake(o.x + 27, o.y, COLAW - 27, 20), this, 13, true, EH_WHITE, NSTextAlignmentLeft).text = text
    }
    // legenda centrata della colonna di comando (nella fascia), con filetto sopra
    private fun deckLegend(y: Double, text: String, rule: Boolean) {
        if (rule) box(B(COLB, y - 10, COLBW, 1.0), EH_LINE, 0, _deck)
        label(B(COLB - 4, y, COLBW + 8, 13.0), _deck, 9.5, true, EH_GREY, NSTextAlignmentCenter).text = text
    }

    private fun buildBody() {
        val W = bounds.width; val H = bounds.height
        // scocca grafite opaca, luce appena accennata dall'alto, bordo sottile
        // ANDROID (tablet): la scocca va oltre il bordo sinistro della tela e prosegue nelle fasce (SFSidePanel, SFSideDeck)
        val body = gradient(CGRectMake(-60, 4, W + 120, H - 8), listOf(EH(58, 61, 67), EH(46, 49, 54), EH(36, 38, 42)), 22, this)
        body.borderWidth = 1.0; body.borderColor = EH(84, 88, 96).CGColor
        _deck = SFSideDeck(this, 2.0).view

        // targhetta (in alto a sinistra, sopra lo schermo) e spia d'allarme (in alto, al centro dello schermo)
        val brand = label(CGRectMake(20, 6, 80, 22), this, 17, true, EH_WHITE, NSTextAlignmentLeft)
        var t = NSAttributedString("extra", UIFont.systemFontOfSize(17, UIFontWeightLight), EH_WHITE)
        t = t.append("H", UIFont.boldSystemFontOfSize(17), EH_ACCENT)
        brand.attributedText = t
        label(CGRectMake(88, 11, 330, 13), this, 9, false, EH_GREY, NSTextAlignmentLeft).text = "similFranz  ·  SIMULAZIONE  ·  NON PER USO CLINICO"
        _alarmLamp = box(CGRectMake(366, 12, 100, 9), EH(60, 20, 18), 4.5, this)
        _alarmLamp.layer.borderWidth = 1.0; _alarmLamp.layer.borderColor = EH(20, 20, 22).CGColor
        label(CGRectMake(476, 10, 60, 13), this, 8.5, false, EH_GREY, NSTextAlignmentLeft).text = "ALLARME"

        // cornice dello schermo (le prese sono sul fianco sinistro: lo schermo prende tutta la larghezza fino alla console)
        val bez = box(CGRectMake(12, 30, 808, 604), EH(14, 15, 17), 8, this)
        bez.layer.borderWidth = 1.0; bez.layer.borderColor = EH(84, 88, 96).CGColor

        // fila sotto lo schermo: monitoraggio e pacer
        panel(CGRectMake(12, 646, 236, 110), "MONITORAGGIO")
        panel(CGRectMake(260, 646, 560, 110), "PACER  ·  attivo nel modo PACER")
        label(CGRectMake(272, 667, 134, 13), this, 10, true, EH_WHITE, NSTextAlignmentCenter).text = "FREQUENZA  ppm"
        label(CGRectMake(422, 667, 134, 13), this, 10, true, EH_WHITE, NSTextAlignmentCenter).text = "USCITA  mA"
        box(CGRectMake(414, 670, 1, 74), EH(74, 78, 86), 0, this)
        box(CGRectMake(564, 670, 1, 74), EH(74, 78, 86), 0, this)

        // console di terapia: un solo pannello a tutta altezza, filetto arancio; la parte oltre la tela sta nella fascia
        for (host in listOf(this, _deck)) {
            val ox = if (host === this) 0.0 else -DX
            val c = box(CGRectMake(CONSOLE.x + ox, CONSOLE.y, CONSOLE.width, CONSOLE.height), EH(40, 43, 48), 12, host)
            c.layer.borderWidth = 1.0; c.layer.borderColor = EH_ACCENT.CGColor
        }
        box(B(DX, 42.0, 1.0, 702.0), EH_LINE, 0, _deck)        // filetto tra le due colonne, proprio sul bordo della tela
        label(CGRectMake(COLA, 38, COLAW, 13), this, 10, true, EH_ACCENT, NSTextAlignmentLeft).text = "DEFIBRILLATORE"
        stepLabel("1", "ENERGIA", CGPointMake(COLA, 146))
        stepLabel("2", "CARICA", CGPointMake(COLA, 284))
        stepLabel("3", "SCARICA", CGPointMake(COLA, 500))

        // colonna di comando (fascia destra): accensione, modo, allarmi, altoparlante, menu
        _powerLed = box(B(COLB + 24, 116.0, 24.0, 5.0), EH(40, 60, 44), 2.5, _deck)
        label(B(COLB - 4, 125.0, COLBW + 8, 12.0), _deck, 8.5, false, EH_GREY, NSTextAlignmentCenter).text = "ACCENSIONE"
        deckLegend(158.0, "MODO", true)
        val hint = label(B(COLB - 4, 432.0, COLBW + 8, 24.0), _deck, 8.5, false, EH_GREY, NSTextAlignmentCenter)
        hint.numberOfLines = 2; hint.text = "di nuovo:\nsolo monitor"
        deckLegend(480.0, "ALLARMI", true)
        // griglia dell'altoparlante
        box(B(COLB, 584.0, COLBW, 1.0), EH_LINE, 0, _deck)
        for (rr in 0 until 6) for (c in 0 until 5)
            box(B(COLB + 9 + c * 12.0, 600.0 + rr * 12, 6.0, 6.0), EH(22, 23, 26), 3, _deck)
    }

    // MARK: - Schermo

    private fun trace(f: CGRect, color: UIColor, speed: Double, min: Double, max: Double, sampler: DoubleSampler): TraceView {
        val t = TraceView(f)
        t.traceColor = color; t.label = ""; t.minValue = min; t.maxValue = max; t.sweepSpeed = speed; t.sampler = sampler
        _screen.addSubview(t)
        return t
    }
    // riquadro numerico: intestazione piccola colorata + valore grande
    private fun numeric(title: String, unit: String, frame: CGRect, color: UIColor, size: Number): UILabel {
        val f = frame; val c = color
        box(CGRectMake(f.x, f.y, f.width, 1), EH(50, 54, 60), 0, _screen)
        label(CGRectMake(f.x + 4, f.y + 3, f.width * 0.6, 15), _screen, 12.5, true, c, NSTextAlignmentLeft).text = title
        label(CGRectMake(f.x + f.width * 0.5, f.y + 3, f.width * 0.5 - 4, 15), _screen, 10.5, false, EH_GREY, NSTextAlignmentRight).text = unit
        val v = label(CGRectMake(f.x + 2, f.y + 18, f.width - 4, f.height - 20), _screen, size, true, c, NSTextAlignmentCenter)
        v.font = UIFont.monospacedDigitSystemFontOfSize(size, UIFontWeightBold)
        return v
    }

    // ANDROID (layout 21/11): schermo 796×592 (iPad 628×462): tracce larghe TW, numerici larghi 246, terapia sotto le tracce

    private fun buildScreen() {
        val sf = CGRectMake(18, 36, 796, 592)
        _screen = UIView(sf)
        _screen.backgroundColor = UIColor.blackColor; _screen.opaque = true      // niente ritaglio: le tracce stanno già dentro lo schermo
        addSubview(_screen)
        val SW = sf.width
        // barra di stato
        box(CGRectMake(0, 0, SW, 24), EH(26, 28, 32), 0, _screen)
        _clock = label(CGRectMake(6, 2, 190, 20), _screen, 12, false, EH_WHITE, NSTextAlignmentLeft)
        _modeLbl = label(CGRectMake(SW / 2 - 140, 2, 280, 20), _screen, 13, true, EH_ACCENT, NSTextAlignmentCenter)
        _batt = label(CGRectMake(SW - 196, 2, 96, 20), _screen, 12, true, EH_WHITE, NSTextAlignmentRight)
        // ANDROID (batteria 21/11): icona comune con riempimento colorato e fulmine in carica, a sinistra della percentuale
        _battIcon = SFBatteryIcon(CGRectMake(SW - 196, 5, 30, 14), EH_WHITE); _screen.addSubview(_battIcon)
        _elapsed = label(CGRectMake(SW - 94, 2, 88, 20), _screen, 14, true, EH_WHITE, NSTextAlignmentRight)
        _elapsed.font = UIFont.monospacedDigitSystemFontOfSize(14, UIFontWeightBold)
        // riga dei messaggi e degli allarmi
        _msg = label(CGRectMake(6, 26, SW - 12, 24), _screen, 16, true, EH_WHITE, NSTextAlignmentLeft)

        val eng = _engine; val me = this
        val tw = TW; val tx = 4.0
        // ANDROID (ritardo R→polso 22/11): velocità come sul dispositivo vero (SFTraceSpeed.kt): extraH è un dispositivo originale a grandezza naturale
        // (schermo 111 mm sul vetro): 25 mm/s veri per ECG e pleth, 6,25 mm/s per la CO2 (prima 90 e 30 punti/s: 12,6 e 4,2 mm/s)
        val v25 = SFSpeedLife(25.0); val v6 = SFSpeedLife(6.25)
        _ecg = trace(CGRectMake(tx, 54, tw, 150), EH_HR, v25, -0.9, 1.7) { t ->
            val s = me._snap
            if (if (me._pads) !s.padsConnected else !s.ecgConnected) 0.0
            else eng.ecgAt(t, if (me._pads) SLLeadPads else SLLeadII)
        }
        _ecg.edgeArtifacts = true; _ecg.markerYOffset = 16.0
        _ecg.markerSampler = MarkerSampler { t0, t1 ->
            val s = me._snap
            if (if (me._pads) !s.padsConnected else !s.ecgConnected) 0
            else eng.markersFrom(t0, t1, s.defib.mode == SLDefibManual && s.defib.sync)
        }
        _ecgLbl = label(CGRectMake(tx + 4, 54, 200, 15), _screen, 12, false, EH_HR, NSTextAlignmentLeft)
        _pleth = trace(CGRectMake(tx, 208, tw, 100), EH_SPO2, v25, -0.05, 1.05) { t -> eng.plethAt(t) }
        _pleth.autoScale = 1; _pleth.minSpan = 0.6 /* ANDROID (perfusione 22/11): onde piccole restano piccole */
        label(CGRectMake(tx + 4, 208, 200, 15), _screen, 12, false, EH_SPO2, NSTextAlignmentLeft).text = "Pletismografia"
        _co2 = trace(CGRectMake(tx, 312, tw, 100), EH_CO2, v6, -1.0, 60.0) { t -> eng.capnoAt(t) }
        _co2.filled = true; _co2.showScaleLabels = true; _co2.autoScale = 4   // ANDROID (OP-01)
        label(CGRectMake(tx + 4, 312, 200, 15), _screen, 12, false, EH_CO2, NSTextAlignmentLeft).text = "CO₂   mmHg"

        // numerici a destra, ognuno all'altezza della sua traccia
        val nx = tx + tw + 4; val nw = SW - nx - 2
        _hr = numeric("FC", "bpm", CGRectMake(nx, 54, nw, 150), EH_HR, 86)
        _sp = numeric("SpO₂", "%", CGRectMake(nx, 208, nw, 100), EH_SPO2, 58)
        _nibp = numeric("NIBP", "mmHg", CGRectMake(nx, 312, nw, 100), EH_NIBP, 32)
        _nibp.frame = CGRectMake(nx + 2, 332, nw - 4, 44)
        _nibpSt = label(CGRectMake(nx + 4, 380, nw - 8, 22), _screen, 11.5, false, EH_GREY, NSTextAlignmentCenter)
        _et = numeric("EtCO₂", "mmHg", CGRectMake(nx, 416, nw / 2, 84), EH_CO2, 40)
        _rr = numeric("FR", "/min", CGRectMake(nx + nw / 2, 416, nw / 2, 84), EH_WHITE, 40)
        _temp = numeric("TEMP", "°C", CGRectMake(nx, 504, nw, 84), EH_TEMP, 34)

        // riquadro della terapia elettrica, sotto le tracce
        val th = box(CGRectMake(tx, 418, tw, 170), EH(18, 20, 23), 0, _screen)
        th.layer.borderWidth = 1.5; th.layer.borderColor = EH_ACCENT.CGColor
        _thTitle = label(CGRectMake(12, 8, 320, 18), th, 13, true, EH_ACCENT, NSTextAlignmentLeft)
        _thSync = label(CGRectMake(tw - 90, 8, 78, 20), th, 13, true, EH(20, 20, 20), NSTextAlignmentCenter)
        _thSync.layer.backgroundColor = EH_AMBER.CGColor; _thSync.text = "SYNC"; _thSync.hidden = true
        _thBig = label(CGRectMake(12, 32, 230, 80), th, 62, true, EH_ACCENT, NSTextAlignmentLeft)
        _thBig.font = UIFont.monospacedDigitSystemFontOfSize(62, UIFontWeightBold)
        _thBig2 = label(CGRectMake(250, 32, 230, 80), th, 62, true, EH_ACCENT, NSTextAlignmentLeft)
        _thBig2.font = UIFont.monospacedDigitSystemFontOfSize(62, UIFontWeightBold)
        _thState = label(CGRectMake(250, 34, tw - 262, 76), th, 21, true, EH_WHITE, NSTextAlignmentLeft)
        _thState.numberOfLines = 3
        _chargeTrack = box(CGRectMake(12, 118, tw - 24, 12), EH(48, 52, 58), 0, th)
        _chargeFill = box(CGRectMake(0, 0, 0, 12), EH_ACCENT, 0, _chargeTrack)
        _thSub = label(CGRectMake(12, 138, tw - 24, 22), th, 14, false, EH_GREY, NSTextAlignmentLeft)

        _offCover = UIView(_screen.bounds); _offCover.backgroundColor = EH(6, 6, 7)
        _screen.addSubview(_offCover)
    }

    // MARK: - Tasti

    // ANDROID (tablet): le prese stanno sul fianco sinistro dell'apparecchio, nella fascia accanto alla tela (SFSidePanel);
    // il riquadro SENSORI del frontale resta scocca liscia.
    // creato al primo uso: si usa già durante la costruzione della vista (prima degli inizializzatori dichiarati più sotto)
    private lateinit var _sideP: SFSidePanel
    private val _side: SFSidePanel get() { if (!::_sideP.isInitialized) _sideP = SFSidePanel(this, sampleX = 2.0, style = SFSideStyle(EH(52, 55, 61), EH(22, 23, 26), EH_WHITE, EH(30, 32, 36), EH(30, 31, 34))); return _sideP }
    private lateinit var _socketsL: ArrayList<String>
    private val _sockets: ArrayList<String> get() { if (!::_socketsL.isInitialized) _socketsL = ArrayList(); return _socketsL }
    @Suppress("UNUSED_PARAMETER")
    private fun socket(sensor: String, label: String, key: String, y: Double, color: UIColor) {
        val b = _side.addSocket(sensor, label, color) { plugTapped(it) }
        b.accessibilityIdentifier = sensor
        _sockets.add(sensor); _keys[key] = b
    }

    private fun buildKeys() {
        // (4) prese dei sensori
        val socks = listOf(arrayOf<Any>("ecg", "ECG", "PLUG-ECG", EH_HR), arrayOf<Any>("spo2", "SpO₂", "PLUG-SPO2", EH_SPO2), arrayOf<Any>("nibp", "NIBP", "PLUG-NIBP", EH_NIBP),
                           arrayOf<Any>("etco2", "CO₂", "PLUG-CO2", EH_CO2), arrayOf<Any>("temp", "TEMP", "PLUG-TEMP", EH_TEMP), arrayOf<Any>("pads", "PLACCHE", "PLUG-PADS", EH_ACCENT)   /* ANDROID (21/11): «Placche» ovunque */)
        var y = 64.0
        for (s in socks) { socket(s[0] as String, s[1] as String, s[2] as String, y, s[3] as UIColor); y += 75 }

        // colonna di comando (fascia destra): accensione in alto, come sui monitor-defibrillatori (tasto tondo, anello verde)
        val pw = key("POWER", "", B(COLB + 4, 44.0, 64.0, 64.0), EH_KEY, EH_WHITE, 12, 32, { kPower() }, _deck)
        pw.layer.borderWidth = 1.5; pw.layer.borderColor = EH(70, 190, 110).CGColor
        val sym = CAShapeLayer.layer(); val c = CGPointMake(32, 34)
        val p = UIBezierPath.bezierPathWithArcCenter(c, 13, -Math.PI / 2 + 0.62, -Math.PI / 2 - 0.62 + 2 * Math.PI, true)
        p.moveToPoint(CGPointMake(c.x, c.y - 18)); p.addLineToPoint(CGPointMake(c.x, c.y - 4))
        sym.path = p.CGPath; sym.strokeColor = EH(120, 220, 150).CGColor; sym.fillColor = null; sym.lineWidth = 2.5; sym.lineCap = kCALineCapRound
        pw.layer.addSublayer(sym)
        // selettore del MODO: tre tasti alti, spia sopra ciascuno (di nuovo lo stesso tasto: solo monitoraggio)
        val modes = listOf(listOf("MODE-DAE", "DAE"), listOf("MODE-MAN", "MANUALE"), listOf("MODE-PACE", "PACER"))
        for (i in 0 until modes.size) {
            val ky = 182.0 + i * 88
            val b = key(modes[i][0], modes[i][1], B(COLB, ky, COLBW, 68.0), EH_KEYHI, EH_WHITE, 12.5, 9, { modeTapped(it) }, _deck)
            b.uiTag = i + 1; b.titleEdgeInsets = UIEdgeInsetsMake(0, 1, 0, 1); b.titleLabel.adjustsFontSizeToFitWidth = true; b.titleLabel.minimumScaleFactor = 0.8; b.titleLabel.numberOfLines = 1
            _modeLed[modes[i][0]] = box(B(COLB + 14, ky - 10, 44.0, 6.0), EH(70, 50, 30), 3, _deck)
        }
        key("SILENCE", "SILENZIA\nALLARMI", B(COLB, 498.0, COLBW, 68.0), EH_KEY, EH_AMBER, 11, 9, { kSilence() }, _deck)
        key("MENU", "MENU", B(COLB, 684.0, COLBW, 60.0), EH_KEY, EH_WHITE, 13, 9, { kMenu() }, _deck)

        // monitoraggio, sotto lo schermo
        key("NIBP", "AVVIA\nNIBP", CGRectMake(24, 668, 100, 78), EH_KEY, EH_WHITE, 14) { kNibp() }
        val ld = key("LEAD", "DERIVAZIONE", CGRectMake(136, 668, 100, 78), EH_KEY, EH_WHITE, 12) { kLead() }
        ld.titleEdgeInsets = UIEdgeInsetsMake(0, 4, 18, 4)
        _leadKeyNote = label(CGRectMake(4, 46, 92, 16), ld, 12, false, EH_HR, NSTextAlignmentCenter)

        // colonna DEFIBRILLATORE: analisi, poi 1 energia, 2 carica, 3 scarica dall'alto in basso
        key("ANALYZE", "ANALIZZA (DAE)", CGRectMake(COLA, 60, COLAW, 58), EH_KEY, EH(150, 196, 240), 14.5) { kAnalyze() }
        key("ENERGY-", "−", CGRectMake(COLA, 172, 82, 80), EH_KEYHI, EH_WHITE, 32) { kEnergyDown() }
        key("ENERGY+", "+", CGRectMake(COLA + 88, 172, 82, 80), EH_KEYHI, EH_WHITE, 32) { kEnergyUp() }
        key("CHARGE", "CARICA", CGRectMake(COLA, 310, COLAW, 96), EH(120, 96, 44), EH(250, 232, 190), 21) { kCharge() }
        key("DISARM", "ANNULLA\nCARICA", CGRectMake(COLA, 414, 82, 56), EH_KEY, EH_WHITE, 11.5) { kDisarm() }
        key("SYNC", "SYNC", CGRectMake(COLA + 88, 414, 82, 56), EH_KEY, EH_AMBER, 14) { kSync() }
        // 3 scarica (ANDROID, layout 21/11 — ridisegnato su richiesta dell'utente): tasto di scarica da defibrillatore
        // professionale. Grande tasto TONDO arancio-rosso a cupola con il fulmine bianco, incassato in una ghiera scura;
        // tra ghiera e tasto un anello luminoso (la spia di "carico"): spento a riposo, si accende e lampeggia quando il
        // defibrillatore è carico e pronto alla scarica. Il tasto resta sempre arancio (più scuro quando non è attivo).
        let3Shock()
        // ANDROID: niente "3" sul tasto (il passo 3 è già nella scritta SCARICA sopra il tasto) — richiesta dell'utente

        // pacer, sotto lo schermo accanto al monitoraggio: frequenza −/+, uscita −/+, modo, avvio
        key("RATE-", "−", CGRectMake(272, 684, 64, 62), EH_KEY, EH_WHITE, 28) { kRateDown() }
        key("RATE+", "+", CGRectMake(342, 684, 64, 62), EH_KEY, EH_WHITE, 28) { kRateUp() }
        key("MA-", "−", CGRectMake(422, 684, 64, 62), EH_KEY, EH_WHITE, 28) { kMaDown() }
        key("MA+", "+", CGRectMake(492, 684, 64, 62), EH_KEY, EH_WHITE, 28) { kMaUp() }
        key("DEMAND", "DEMAND /\nFISSO", CGRectMake(576, 668, 108, 78), EH_KEY, EH_WHITE, 13) { kDemand() }
        key("PACE", "AVVIA", CGRectMake(694, 668, 114, 78), EH_KEY, EH(120, 220, 150), 18) { kPace() }
    }

    // ANDROID (layout 21/11): tasto di scarica — ghiera incassata, anello-spia, tasto tondo a cupola, fulmine
    private fun let3Shock() {
        val cx = COLA + COLAW / 2; val cy = 634.0
        val dG = 170.0; val dL = 158.0; val dK = 142.0                   // ghiera, anello-spia, tasto
        // ghiera: incasso scuro con un filo di luce sul bordo inferiore (lo spigolo che prende luce)
        val g = box(CGRectMake(cx - dG / 2, cy - dG / 2, dG, dG), EH(20, 21, 24), dG / 2, this)
        g.layer.borderWidth = 1.5; g.layer.borderColor = EH(78, 82, 90).CGColor
        val gi = box(CGRectMake(cx - dG / 2 + 3, cy - dG / 2 + 3, dG - 6, dG - 6), EH(14, 15, 17), (dG - 6) / 2, this)
        gi.layer.borderWidth = 1.0; gi.layer.borderColor = EH(0, 0, 0).CGColor
        // anello-spia (cambia colore in tick)
        _shockLamp = box(CGRectMake(cx - dL / 2, cy - dL / 2, dL, dL), EH(58, 26, 20), dL / 2, this)
        _shockLamp.layer.borderWidth = 1.0; _shockLamp.layer.borderColor = EH(10, 10, 11).CGColor
        // tasto tondo a cupola
        val sh = key("SHOCK", "", CGRectMake(cx - dK / 2, cy - dK / 2, dK, dK), EH_SHOCK, EH_WHITE, 12, dK / 2, { kShock() })
        sh.layer.borderWidth = 1.5; sh.layer.borderColor = EH(96, 30, 14).CGColor
        sh.addTarget(UIControlEventTouchDown) { kShockDown() }; sh.addTarget(UIControlEventTouchUpOutside or UIControlEventTouchCancel) { kShockRelease() }   // ANDROID (AE-09)
        // fulmine bianco pieno, con contorno scuro sottile e un'ombra corta: leggibile e "stampato" sul tasto
        val bs = 70.0
        val shadow = CAShapeLayer.layer(); shadow.path = SFBoltPath(CGRectMake((dK - bs) / 2 + 1.5, (dK - bs) / 2 + 3, bs, bs)).CGPath
        shadow.fillColor = UIColor.colorWithWhite(0, 0.28).CGColor; shadow.lineJoin = kCALineJoinRound
        sh.layer.addSublayer(shadow)
        val bolt = CAShapeLayer.layer(); bolt.path = SFBoltPath(CGRectMake((dK - bs) / 2, (dK - bs) / 2, bs, bs)).CGPath
        bolt.fillColor = EH(255, 250, 244).CGColor; bolt.strokeColor = EH(110, 34, 16).CGColor; bolt.lineWidth = 1.2; bolt.lineJoin = kCALineJoinRound
        sh.layer.addSublayer(bolt)
        // dicitura sotto la ghiera, come sulle macchine vere (la spia accesa = carico)
        label(CGRectMake(COLA, cy + dG / 2 + 4, COLAW, 13), this, 9, true, EH_GREY, NSTextAlignmentCenter).text = "SPIA ACCESA = CARICO"
    }

    // MARK: - Stato

    override fun setAlarmText(text: String?, level: Int, severity: Map<String, Int>?, muted: Boolean, remaining: Int) {
        _alarmText = text; _alarmLevel = level; _severity = severity; _muted = muted; _mutedRem = remaining
    }
    override fun poweredOff(): Boolean = _off
    override fun setDevicePowered(on: Boolean) { if (on != !_off) kPower() }
    private fun on(): Boolean = !_off
    private fun note(t: String) { _note = t; _noteUntil = CACurrentMediaTime() + 3 }

    override fun updateWithSnapshot(s: SLSnapshot) {
        _snap = s
        val d = s.defib
        // sorgente della traccia ECG: gli elettrodi; se ci sono solo le piastre, le piastre
        // derivazione: con una sola sorgente collegata si usa quella; con entrambe decide l'operatore (tasto DERIVAZIONE)
        val pads = s.padsConnected && (!s.ecgConnected || _leadPads)
        if (pads != _pads) { _pads = pads; _ecg.resetAtTime(CACurrentMediaTime()) }
        EHSetText(_leadKeyNote, if (_pads) "piastre" else "II")
        val src = if (_pads) s.padsConnected else s.ecgConnected
        _ecg.disconnected = !src; _ecg.noSignalText = null
        EHSetText(_ecgLbl, if (_pads) "ECG dalle piastre" else "ECG  II")
        _pleth.disconnected = !s.spo2Connected; _pleth.noSignalText = null
        _pleth.flatWhenNoSignal = s.spo2Connected && (!s.pulsePresent || s.spo2Searching)
        _co2.disconnected = !s.etco2Connected; _co2.noSignalText = null
        for (k in _sockets) {
            val on = mapOf("ecg" to s.ecgConnected, "spo2" to s.spo2Connected, "nibp" to s.nibpConnected, "etco2" to s.etco2Connected,
                           "temp" to s.tempConnected, "pads" to s.padsConnected)[k] ?: false
            _side.setConnected(k, on)
        }
        var hr = "---"
        if (src) {
            val r = s.rhythm
            if (d.paceCapture) hr = String.format(Locale.US, "%d", d.paceRate)
            else if (r == SLRhythmVentricularFib) hr = "FV"
            else if (r == SLRhythmVentricularTachy || r == SLRhythmVentricularTachyPulse) hr = "TV"
            else if (r == SLRhythmAsystole) hr = "Asi"
            else hr = String.format(Locale.US, "%d", SFJ("hr", s.monHeartRate, 2.0))
        }
        EHSetText(_hr, hr)
        val leak = s.nibpStatus == SLNibpError && s.nibpError == "cuff"
        EHSetText(_nibp, if (s.nibpLastSystolic >= 0) String.format(Locale.US, "%d/%d (%d)", s.nibpLastSystolic, s.nibpLastDiastolic, s.meanArterialPressure) else "---/---")
        val measuredAt: Date? = s.nibpMeasuredAt
        EHSetText(_nibpSt, if (s.nibpStatus == SLNibpMeasuring) "misura in corso…" else (if (leak) "bracciale non collegato" else
                       (if (s.nibpStatus == SLNibpError) (if (s.nibpError == "motion") "artefatto da movimento" else "nessun polso rilevato") else
                       (if (measuredAt != null) "misurata alle " + _fmtHM.format(measuredAt) else (if (s.nibpConnected) "premere AVVIA NIBP" else "bracciale non collegato")))))
        EHSetText(_et, if (s.etco2Connected && s.monRespRate > 0) String.format(Locale.US, "%d", SFJ("co2", s.monEtco2, 1.0)) else "---")
        EHSetText(_rr, if (s.etco2Connected || s.ecgConnected) String.format(Locale.US, "%d", SFJ("rr", s.monRespRate, 1.0)) else "---")
        EHSetText(_temp, if (s.tempConnected) String.format(Locale.US, "%.1f", s.monTemperatureTenths / 10.0).replace(".", ",") else "---")

        // modo attivo: spia accesa e nome nella barra di stato
        val active = if (d.mode == SLDefibAED) "MODE-DAE" else (if (d.mode == SLDefibManual) "MODE-MAN" else (if (d.mode == SLDefibPacer) "MODE-PACE" else "MODE-MON"))
        // ANDROID (25/11): il modo scelto si vede a colpo d'occhio: spia accesa sopra il tasto, bordo luminoso e scritta ambra
        for ((k, led) in _modeLed) {
            val on = !_off && k == active
            EHSetBg(led, if (on) EH_MODE_ON else EH(70, 50, 30))
            val b = _keys[k] ?: continue
            val bw = if (on) 3.0 else 1.0
            if (b.layer.borderWidth != bw) { b.layer.borderWidth = bw; b.layer.borderColor = (if (on) EH_MODE_ON else UIColor.colorWithWhite(0, 0.55)).CGColor }
            EHSetTitleColor(b, if (on) EH(255, 200, 130) else EH_WHITE)
        }
        // i tasti di ogni terapia sono attivi solo nel loro modo (gli altri restano visibili ma spenti)
        val pm = !_off && d.mode == SLDefibPacer; val mm = !_off && d.mode == SLDefibManual; val am = !_off && d.mode == SLDefibAED
        // tasto spento = stesso tasto, più scuro e con la scritta grigia: OPACO (niente trasparenze, che costano a ogni fotogramma)
        val groups = mapOf("RATE-" to pm, "RATE+" to pm, "MA-" to pm, "MA+" to pm, "DEMAND" to pm, "PACE" to pm,
                           "ENERGY-" to mm, "ENERGY+" to mm, "CHARGE" to mm, "DISARM" to mm, "SYNC" to mm,
                           "ANALYZE" to am, "SHOCK" to (mm || am))
        for (k in groups.keys) {
            val b = _keys[k]!!; val on = groups[k]!!
            if (b.isEnabled == on && b.uiTag != 0) continue            // nessun cambiamento: non si tocca il tasto
            if (b.uiTag == 0) { b.uiTag = 77; b.backgroundColor?.let { _ehbg[b] = it } }
            b.isEnabled = on
            b.backgroundColor = if (on) _ehbg[b] else (if (k == "SHOCK") EH(112, 46, 26) else EH(50, 53, 58))   // la scarica resta arancio (spento = scuro)
            b.setTitleColor(EH(104, 109, 116), UIControlStateDisabled)
        }
        EHSetText(_modeLbl, mapOf("MODE-MON" to "MONITOR", "MODE-DAE" to "DAE", "MODE-MAN" to "DEFIBRILLATORE MANUALE", "MODE-PACE" to "PACER")[active])
        EHSetTitle(_keys["PACE"]!!, if (d.paceRunning) "FERMA" else "AVVIA")
        EHSetTitleColor(_keys["PACE"]!!, if (d.paceRunning) EH_RED else EH(120, 220, 150))
        EHSetTitleColor(_keys["SYNC"]!!, if (d.sync) EH(30, 24, 8) else EH_AMBER)
        if (mm) EHSetBg(_keys["SYNC"]!!, if (d.sync) EH_AMBER else EH_KEY)
        EHSetTitle(_keys["SILENCE"]!!, if (s.alarmsMuted) "RIATTIVA\nALLARMI" else "SILENZIA\nALLARMI")
        updateTherapy(d)
    }

    // riquadro della terapia: cambia contenuto con il modo
    private fun updateTherapy(d: SLDefibInfo) {
        val full = _chargeTrack.bounds.width
        EHSetHidden(_thSync, !(d.mode == SLDefibManual && d.sync))
        EHSetHidden(_thBig2, true); EHSetHidden(_thState, false); EHSetHidden(_chargeTrack, true)
        EHSetFrame(_thBig, CGRectMake(12, 32, 230, 80))
        val shocks = if (d.shockCount != 0) String.format(Locale.US, "Scariche erogate: %d", d.shockCount) else "Nessuna scarica erogata"
        if (d.mode == SLDefibManual) {
            EHSetText(_thTitle, "DEFIBRILLATORE MANUALE")
            EHSetText(_thBig, String.format(Locale.US, "%d J", if (d.charge == SLChargeNone) d.energy else (if (d.chargedEnergy != 0) d.chargedEnergy else d.energy)))
            EHSetText(_thState, if (d.charge == SLChargeCharging) "IN CARICA…" else (if (d.charge == SLChargeReady) (if (d.syncPending) "IN ATTESA\nDELL'ONDA R" else "CARICO\nPREMERE SCARICA") else "PRONTO\nPER LA CARICA"))
            EHSetColor(_thState, if (d.charge == SLChargeReady) EH_RED else EH_WHITE)
            EHSetHidden(_chargeTrack, false)
            EHSetFrame(_chargeFill, CGRectMake(0, 0, full * (if (d.charge == SLChargeReady) 1.0 else (if (d.charge == SLChargeCharging) d.chargeProgress else 0.0)), 12))
            EHSetText(_thSub, String.format(Locale.US, "%s%s", shocks, if (d.lastShockEnergy != 0) String.format(Locale.US, "  ·  ultima: %d J%s", d.lastShockEnergy, if (d.lastShockSynced) " sincronizzata" else "") else ""))
        } else if (d.mode == SLDefibAED) {
            EHSetText(_thTitle, "DAE — SEGUIRE LE ISTRUZIONI")
            EHSetFrame(_thBig, CGRectMake(12, 32, 130, 80))
            EHSetText(_thBig, if ((d.aedPhase == SLAedCPR || d.aedPhase == SLAedAnalyzing) && d.aedRemaining > 0) String.format(Locale.US, "%d:%02d", d.aedRemaining / 60, d.aedRemaining % 60) else "")
            val has = !_thBig.text.isNullOrEmpty()
            EHSetFrame(_thState, CGRectMake(if (has) 150.0 else 12.0, 34.0, if (has) TW - 162 else TW - 24, 76.0))
            EHSetText(_thState, EHText(d.message) ?: "")
            EHSetColor(_thState, if (d.aedPhase == SLAedReady) EH_RED else EH_WHITE)
            EHSetHidden(_chargeTrack, d.charge == SLChargeNone)
            EHSetFrame(_chargeFill, CGRectMake(0, 0, full * (if (d.charge == SLChargeReady) 1.0 else d.chargeProgress), 12))
            // ANDROID (funzioni 21/11): l'energia che il DAE caricherà davvero (pediatrica con placche pediatriche/bambino)
            EHSetText(_thSub, String.format(Locale.US, "%s  ·  energia %d J", shocks, if (d.aedEffectiveEnergy > 0) d.aedEffectiveEnergy else d.energy))
            return
        } else if (d.mode == SLDefibPacer) {
            EHSetText(_thTitle, if (d.paceDemand) "PACER — MODO DEMAND" else "PACER — MODO FISSO")
            EHSetText(_thBig, String.format(Locale.US, "%d", d.paceSetRate))
            EHSetHidden(_thBig2, false); EHSetHidden(_thState, true)
            EHSetText(_thBig2, String.format(Locale.US, "%d", d.paceOutput))
            val st = if (!d.paceRunning) "fermo" else (if (d.pacePaused) "in pausa" else (if (!d.paceActive) "non stimola" else (if (d.paceCapture) (if (d.paceMechanical) "CATTURA con polso" else "cattura elettrica") else "nessuna cattura")))
            EHSetText(_thSub, String.format(Locale.US, "ppm (frequenza)          mA (uscita)          stato: %s", st))
            EHSetFrame(_thBig, CGRectMake(12, 32, 180, 80)); EHSetFrame(_thBig2, CGRectMake(220, 32, 180, 80))
            return
        } else {
            EHSetText(_thTitle, "TERAPIA ELETTRICA")
            EHSetText(_thBig, "")
            EHSetFrame(_thState, CGRectMake(12, 34, TW - 24, 76))
            EHSetText(_thState, "Solo monitoraggio.\nPer la terapia scegliere il modo:\nDAE · MANUALE · PACER"); EHSetColor(_thState, EH_GREY)
            EHSetText(_thSub, shocks)
            return
        }
        EHSetFrame(_thState, CGRectMake(250, 34, TW - 262, 76))
    }

    override fun tick(now: Double, snapshot: SLSnapshot) {
        val s = snapshot
        _snap = s
        val d = s.defib
        val run = s.simulationRunning && !_off
        // ANDROID (audit 26/11, PERF-F10): senza la listOf a ogni frame (stesso ordine)
        if (run) { _ecg.advanceTo(now); _pleth.advanceTo(now); _co2.advanceTo(now) } else { _ecg.resetAtTime(now); _pleth.resetAtTime(now); _co2.resetAtTime(now) }
        if (now - _lastSlow < 0.25) return
        _lastSlow = now
        val blink = now % 0.5 < 0.25
        EHSetText(_clock, _fmt.format(SFWallClock.now()))   // ANDROID (ora 22/11): ora sincronizzata con il controller
        val el = (now - _onAt).toInt()
        EHSetText(_elapsed, String.format(Locale.US, "%02d:%02d:%02d", el / 3600, (el / 60) % 60, el % 60))
        val dev = EHBattery()
        val lvl = dev.level
        val chg = dev.charging
        // ANDROID (funzioni 21/11): allarme tecnico simulato «BATTERIA SCARICA»: la batteria del dispositivo appare quasi vuota
        val lowBatt = s.technicalAlarms.contains("low_battery")
        // ANDROID (guasti 22/11): con un malfunzionamento della batteria l'icona mostra la batteria SIMULATA (non quella del tablet)
        val sim = SFBatterySimFor(s)
        EHSetText(_batt, if (sim != null) (if (s.batteryShocksLeft >= 0) String.format(Locale.US, "ϟ%d", s.batteryShocksLeft) else "BATT.")
                         else if (lowBatt) "BATT." else (if (lvl < 0) "--%" else String.format(Locale.US, "%d%%", Math.round(lvl * 100))))
        EHSetColor(_batt, if (sim != null) SFBatteryColor(sim.level) else if (lowBatt || (lvl >= 0 && lvl < 0.2)) EH_RED else EH_WHITE)
        _battIcon.update(sim ?: if (lowBatt) SFBatteryState(0.05f, false) else SFBatteryState(lvl, chg))
        EHSetText(_sp, if (s.spo2Displayable) (if (s.lowPerfusion && now % 6 < 4) "?" else String.format(Locale.US, "%d", SFJ("spo2", s.monSpo2, 1.0))) else "---")
        // valori in allarme lampeggianti, spia in alto
        val map = mapOf("hr" to _hr, "spo2" to _sp, "etco2" to _et, "nibp" to _nibp)
        for (k in map.keys) { val l2 = _severity?.get(k) ?: 0; EHSetAlpha(map[k]!!, if (l2 >= 2 && !blink) 0.3 else 1.0) }
        EHSetBg(_alarmLamp, if (_off) EH(60, 20, 18) else (if (_alarmLevel == 3) (if (blink) EH(255, 40, 30) else EH(90, 20, 18)) else (if (_alarmLevel > 0) (if (now % 1 < 0.5) EH(250, 200, 30) else EH(90, 70, 16)) else EH(60, 20, 18))))
        // ANDROID (guasti 22/11): da spento la spia sopra ACCENSIONE fa da spia di prontezza (come il DAE): ambra lampeggiante
        // = attenzione, rossa fissa = guasto, ambra fissa = autotest dopo la batteria; di serie (pronto) spenta come prima
        EHSetBg(_powerLed, if (!_off) EH(70, 235, 110) else when {
            s.deviceSelfTest -> EH(250, 176, 40)
            s.deviceReadiness == "attention" -> if (now % 1.0 < 0.5) EH(250, 176, 40) else EH(80, 60, 24)
            s.deviceReadiness == "fault" -> EH(240, 56, 46)
            else -> EH(40, 60, 44)
        })
        _beeper.tick(now, s, _sound)
        val ready = !_off && d.charge == SLChargeReady && !d.syncPending
        EHSetBg(_shockLamp, if (ready) (if (blink) EH(255, 196, 92) else EH(236, 96, 40)) else EH(58, 26, 20))      // anello-spia: acceso e lampeggiante solo a defibrillatore carico
        // riga dei messaggi: nota del dispositivo, messaggio della terapia (manuale e pacer), poi l'allarme
        var m: String? = if (_note != null && now < _noteUntil) _note else null
        if (m == null) _note = null
        if (m == null && (d.mode == SLDefibManual || d.mode == SLDefibPacer)) m = EHText(d.message)
        var isAlarm = false
        if (m.isNullOrEmpty() && _alarmLevel > 0) { m = if (_muted) String.format(Locale.US, "Allarmi silenziati %d s · %s", _mutedRem, _alarmText ?: "") else _alarmText; isAlarm = true }
        // ANDROID (funzioni 21/11): allarmi tecnici simulati che AlarmManager non produce (CO2 linea occlusa, batteria scarica);
        // ECG, SpO2 e bracciale arrivano già come allarmi tecnici normali
        if (m.isNullOrEmpty() && !_off) {
            val tech = s.technicalAlarms.filter { it == "etco2_occlusion" || it == "low_battery" }.map { SLTechnicalAlarmText(it) }.toMutableList()
            // ANDROID (guasti 22/11): malfunzionamenti simulati nella riga degli allarmi tecnici, con i testi di extraH
            tech.addAll(faultAlarms(s).filter { !tech.contains(it) })
            if (tech.isNotEmpty()) { m = tech.joinToString("  ·  "); isAlarm = true }
        }
        EHSetText(_msg, m ?: "")
        EHSetColor(_msg, if (isAlarm) (if (_alarmLevel == 3) EH_RED else EH_AMBER) else EH_WHITE)
        updateTherapy(d)
    }

    override fun resetTracesAt(t: Double) { for (v in listOf(_ecg, _pleth, _co2)) v.resetAtTime(t) }

    // MARK: - Azioni

    private fun cmd(name: String, value: Any?): Boolean {
        val c = LinkedHashMap<String, Any?>(); c["command"] = name
        if (value != null) c["value"] = value
        val err: String? = _state.applyCommand(c)
        val ok = err == null
        if (name.startsWith("defib_")) SFLog.log(String.format(Locale.US, "ExtraHView: %s %s -> %s", name, value ?: "", if (ok) "ok" else "rifiutato"))
        _snap = _state.snapshot(); updateWithSnapshot(_snap)
        return ok
    }

    // ANDROID (guasti 22/11): allarmi tecnici dei malfunzionamenti (il monitoraggio resta; il guasto blocca solo la terapia)
    private fun faultAlarms(s: SLSnapshot): List<String> {
        val a = ArrayList<String>()
        if (s.fault("service_required")) a.add(SLDeviceFaultScreen("extrah", "svc"))
        if (s.fault("battery_replace")) a.add(SLDeviceFaultScreen("extrah", "battery_replace")) else if (s.fault("battery_low")) a.add(SLDeviceFaultScreen("extrah", "battery_low"))
        if (s.fault("pads_damaged")) a.add("PIASTRE DANNEGGIATE")
        if (s.fault("pads_expired")) a.add(SLDeviceFaultScreen("extrah", "pads_expired"))
        if (s.fault("maintenance")) a.add(SLDeviceFaultScreen("extrah", "maintenance"))
        if (s.fault("shock_button_stuck")) a.add("TASTO SCARICA BLOCCATO")
        if (s.fault("temperature")) a.add(SLDeviceFaultScreen("extrah", "temperature"))
        if (s.fault("memory_full")) a.add(SLDeviceFaultScreen("extrah", "memory"))
        return a
    }

    private fun kPower() {
        _off = !_off
        _state.devicePowered = !_off
        // ANDROID (guasti 22/11): batteria simulata esaurita: il tasto non accende
        if (!_off && !_state.devicePowered) { _off = true; return }
        _offCover.hidden = !_off
        closePopup()
        if (_off) { _ecg12?.close(); _ecg12 = null; _tx?.cancel() }      // ANDROID (extraH 25/11)
        if (_off) { if (_snap.defib.paceRunning) cmd("defib_pacer", mapOf("running" to false)); cmd("defib_mode", "off") }
        else { _onAt = CACurrentMediaTime(); resetTracesAt(_onAt); note(faultAlarms(_state.snapshot()).firstOrNull()?.let { "Autotest: $it" } ?: "Autotest superato") }   // ANDROID (guasti 22/11)
        updateWithSnapshot(_state.snapshot())
    }

    private fun plugTapped(b: UIButton) {
        // ANDROID (connettori 22/11): le sonde si collegano e si staccano anche a dispositivo SPENTO (come nella realtà: il cavo
        // si inserisce a prescindere); all'accensione il monitor le trova già collegate. Prima il tocco era ignorato da spento.
        val k = b.accessibilityIdentifier
        val on = mapOf("ecg" to _snap.ecgConnected, "spo2" to _snap.spo2Connected, "nibp" to _snap.nibpConnected, "etco2" to _snap.etco2Connected,
                       "temp" to _snap.tempConnected, "pads" to _snap.padsConnected)[k] ?: false
        cmd("set_sensor", mapOf("sensor" to k, "connected" to !on))
    }

    private fun kLead() {
        if (!on()) return
        if (_snap.ecgConnected && _snap.padsConnected) { _leadPads = !_leadPads; updateWithSnapshot(_snap) }
        else note(if (_snap.ecgConnected || _snap.padsConnected) "Una sola sorgente ECG collegata" else "Collegare gli elettrodi ECG o le piastre")
    }
    // i tasti del modo sono a scatto: di nuovo lo stesso tasto spegne la terapia (resta il monitoraggio)
    private fun modeTapped(b0: UIButton) {
        var b: UIButton? = b0
        if (!on()) return
        val d = _snap.defib
        var want = listOf("off", "aed", "manual", "pacer")[b0.uiTag]
        if ((b0.uiTag == 1 && d.mode == SLDefibAED) || (b0.uiTag == 2 && d.mode == SLDefibManual) || (b0.uiTag == 3 && d.mode == SLDefibPacer)) { want = "off"; b = null }
        val tag = b?.uiTag ?: 0
        if (d.paceRunning && tag != 3) cmd("defib_pacer", mapOf("running" to false))
        if (d.sync && tag != 2) cmd("defib_sync", false)
        cmd("defib_mode", want)
    }

    private fun energyStep(dir: Int) {
        if (!on()) return
        if (_snap.defib.mode != SLDefibManual) { note("L'energia si sceglie nel modo MANUALE"); return }
        val list = energies()
        val cur = _snap.defib.energy; var i = 0
        for (k in 0 until list.size) if (list[k] <= cur) i = k
        i = maxOf(0, minOf(list.size - 1, i + dir))
        cmd("defib_energy", list[i])
    }
    private fun kEnergyUp() { energyStep(1) }
    private fun kEnergyDown() { energyStep(-1) }
    private fun kCharge() {
        if (!on()) return
        if (_snap.defib.mode != SLDefibManual) { note("La carica manuale è nel modo MANUALE"); return }
        if (!cmd("defib_charge", null) && !_snap.padsConnected) note("Collegare le piastre")
    }
    private fun kDisarm() { if (on() && _snap.defib.charge != SLChargeNone) { cmd("defib_disarm", null); note("Carica annullata") } }
    private fun kShock() { if (!on()) return; if (shockDownSent) { kShockRelease(); return }; cmd("defib_shock", null) }
    // ANDROID (difetti clinici 22/11, AE-09): SYNC = tenere premuto SHOCK fino all'onda R. Alla pressione (TouchDown) la scarica va
    // in attesa dell'onda R; rilasciato prima, non parte (defib_shock_release). Senza SYNC la scarica parte al tocco come prima.
    // Un tocco «sintetico» (autotest, solo TouchUpInside) scarica come prima.
    private var shockDownSent = false
    private fun shockSyncArmed(): Boolean { val d = _state.snapshot().defib; return d.mode == SLDefibManual && d.sync }
    private fun kShockDown() { if (on() && shockSyncArmed()) { shockDownSent = true; cmd("defib_shock", null) } }
    private fun kShockRelease() { if (shockDownSent) { shockDownSent = false; cmd("defib_shock_release", null) } }

    private fun kSync() {
        if (!on()) return
        if (_snap.defib.mode != SLDefibManual) { note("SYNC è disponibile nel modo MANUALE"); return }
        cmd("defib_sync", !_snap.defib.sync)
    }
    private fun kAnalyze() {
        if (!on()) return
        if (_snap.defib.mode != SLDefibAED) { note("L'analisi è nel modo DAE"); return }
        cmd("defib_analyze", null)
    }
    private fun kNibp() { if (on()) { _state.startNibpMeasurement(); updateWithSnapshot(_state.snapshot()) } }
    private fun kSilence() { if (on()) cmd("mute_alarms", if (_state.snapshot().alarmsMuted) false else 120) }
    private fun pacer(): Boolean { if (!on()) return false; if (_snap.defib.mode != SLDefibPacer) { note("Prima scegliere il modo PACER"); return false }; return true }
    private fun kRateUp() { if (pacer()) cmd("defib_pacer", mapOf("rate" to minOf(180, _snap.defib.paceSetRate + 5))) }
    private fun kRateDown() { if (pacer()) cmd("defib_pacer", mapOf("rate" to maxOf(30, _snap.defib.paceSetRate - 5))) }
    private fun kMaUp() { if (pacer()) cmd("defib_pacer", mapOf("output_ma" to minOf(200, _snap.defib.paceOutput + 5))) }
    private fun kMaDown() { if (pacer()) cmd("defib_pacer", mapOf("output_ma" to maxOf(0, _snap.defib.paceOutput - 5))) }
    private fun kDemand() { if (pacer()) cmd("defib_pacer", mapOf("demand" to !_snap.defib.paceDemand)) }
    private fun kPace() { if (pacer()) cmd("defib_pacer", mapOf("running" to !_snap.defib.paceRunning)) }

    // MARK: - Menu

    private fun closePopup() { _popup?.removeFromSuperview(); _popup = null; for (k in listOf("M-LAYOUT", "M-VOLUMI", "M-INFO", "M-CHIUDI", "M-ECG12", "M-TX", "TX-SITE-0", "TX-SITE-1", "TX-SITE-2", "TX-SITE-3", "TX-ANNULLA")) _keys.remove(k) }
    private fun kMenu() {
        if (!on()) return                                   // ANDROID (menu ☰ 21/11): il layout si sceglie dal menu ☰, non dal dispositivo
        if (_popup != null) { closePopup(); return }
        val popup = UIView(CGRectMake(18 + 208, 36 + 120, 380, 300))      // ANDROID (layout 21/11): al centro del nuovo schermo
        _popup = popup
        popup.backgroundColor = EH(24, 26, 30); popup.layer.borderWidth = 2.0; popup.layer.borderColor = EH_ACCENT.CGColor
        label(CGRectMake(16, 10, 348, 26), popup, 18, true, EH_ACCENT, NSTextAlignmentLeft).text = "Menu"
        val ver = EHBundleVersion()
        val rows = listOf<Triple<String, String, () -> Unit>>(
                          // ANDROID (menu ☰ 21/11): niente «Layout del monitor» né «Controller e PIN» qui: sono nel menu ☰ dell'istruttore
                          Triple("M-ECG12", "ECG a 12 derivazioni", { m12() }),          // ANDROID (extraH 25/11)
                          Triple("M-TX", if (_tx?.active == true) "Annulla la trasmissione" else "Trasmetti l'ultimo ECG", { mTx() }),
                          Triple("M-VOLUMI", "Volumi", { mVolumes() }),
                          Triple("M-CHIUDI", "Chiudi", { closePopup() }))
        var y = 46.0
        for (r in rows) {
            val b = UIButton.buttonWithType(UIButtonTypeCustom)
            b.frame = CGRectMake(16, y, 348, 52); b.backgroundColor = EH_KEY; b.layer.borderWidth = 1.0; b.layer.borderColor = EH(110, 115, 122).CGColor
            b.setTitle(r.second, UIControlStateNormal); b.setTitleColor(EH_WHITE, UIControlStateNormal)
            b.titleLabel.font = UIFont.boldSystemFontOfSize(16); b.titleLabel.adjustsFontSizeToFitWidth = true; b.titleLabel.minimumScaleFactor = 0.6
            b.titleEdgeInsets = UIEdgeInsetsMake(0, 8, 0, 8)
            val action = r.third
            b.addTarget(UIControlEventTouchUpInside) { action() }
            popup.addSubview(b); _keys[r.first] = b
            y += 60
        }
        // ANDROID (audit 21/11): con due sole voci (Volumi, Chiudi) il riquadro alto 300 restava vuoto per metà:
        // ora è alto quanto le voci e resta centrato dove stava
        val ph = y + 8
        popup.frame = CGRectMake(popup.frame.origin.x, popup.frame.origin.y + Math.floor((300 - ph) / 2), popup.frame.size.width, ph)
        addSubview(popup)
    }
    // MARK: ANDROID (extraH 25/11) — ECG a 12 derivazioni e trasmissione simulata
    // La carta è quella comune (SFEcg12View); la trasmissione non usa nessuna rete: mostra le fasi con tempi realistici e
    // scrive nel registro «12 derivazioni trasmesso a …» (tag "ecg12_tx"): la regia risponde facendo da centrale.
    private fun m12() {
        closePopup()
        if (_ecg12 != null) return
        if (!_snap.ecgConnected) { note("12 derivazioni: collegare gli elettrodi ECG"); return }
        val v = SFEcg12Show(this, _state, _engine, SFEcg12StyleB, "extrah", { r -> _txRec = r; txMenu() }) { _ecg12 = null; _keys.remove("ECG12-TX") }
        _ecg12 = v; v.txButton?.let { _keys["ECG12-TX"] = it }
    }
    private fun mTx() {
        closePopup()
        if (_tx?.active == true) { _tx?.cancel(); return }
        val r = SFEcg12Tx.list(_state).firstOrNull()
        if (r == null) { _txRec = null; note("Nessun ECG a 12 derivazioni in memoria"); return }
        _txRec = r; txMenu()
    }
    private fun txMenu() {
        val rec = _txRec ?: return
        if (!on()) return
        if (_tx?.active == true) { note("Trasmissione già in corso"); return }
        closePopup()
        val n = SFEcg12Tx.sites.size
        val popup = UIView(CGRectMake(18 + 178, 36 + 70, 440, 46.0 + 60 * (n + 1) + 8))
        _popup = popup
        popup.backgroundColor = EH(24, 26, 30); popup.layer.borderWidth = 2.0; popup.layer.borderColor = EH_ACCENT.CGColor
        label(CGRectMake(16, 10, 408, 26), popup, 17, true, EH_ACCENT, NSTextAlignmentLeft).text = "Trasmetti l'ECG delle " + rec.time() + " a…"
        var y = 46.0
        fun row(k: String, title: String, action: () -> Unit) {
            val b = UIButton.buttonWithType(UIButtonTypeCustom)
            b.frame = CGRectMake(16, y, 408, 52); b.backgroundColor = EH_KEY; b.layer.borderWidth = 1.0; b.layer.borderColor = EH(110, 115, 122).CGColor
            b.setTitle(title, UIControlStateNormal); b.setTitleColor(EH_WHITE, UIControlStateNormal)
            b.titleLabel.font = UIFont.boldSystemFontOfSize(16); b.titleLabel.adjustsFontSizeToFitWidth = true; b.titleLabel.minimumScaleFactor = 0.6
            b.titleEdgeInsets = UIEdgeInsetsMake(0, 8, 0, 8)
            b.addTarget(UIControlEventTouchUpInside) { action() }
            popup.addSubview(b); _keys[k] = b
            y += 60
        }
        for (i in 0 until n) { val site = SFEcg12Tx.sites[i]; row("TX-SITE-$i", (if (rec.sent.contains(site)) "✓ " else "") + site) { closePopup(); txStart(site, rec) } }
        row("TX-ANNULLA", "Annulla") { closePopup() }
        addSubview(popup)
    }
    private fun txStart(site: String, rec: SFEcg12Tx.Record) {
        if (_tx?.active == true) return
        val job = SFEcg12TxJob(site, rec, "extrah", { ph ->
            note(when (ph) {
                "attempt" -> "Trasmissione dell'ECG: avvio"
                "connecting" -> "Trasmissione: connessione in corso"
                "connected" -> "Trasmissione: connesso"
                "sending" -> "Trasmissione dell'ECG a $site"
                "done" -> "ECG trasmesso a $site"
                else -> "Trasmissione annullata"
            })
        }) { ok -> if (ok) Uk.after(2.9) { if (_tx?.active != true && on()) note("ECG trasmesso a $site") } }
        _tx = job; job.start()
    }

    private fun mLayout() { closePopup(); onLayoutMenu?.invoke() }
    private fun mVolumes() { closePopup(); onVolumes?.invoke() }
    private fun mInfo() { closePopup(); onInfo?.invoke() }

    // MARK: - Autotest

    // tocco simulato di un tasto (verifica da Mac e autotest)
    fun tapKey(key: String): Boolean {
        val k = key
        val target = _keys[k]
        if (target == null) { SFLog.log(String.format(Locale.US, "SELFTEST tasto %s inesistente", k)); return false }
        val w = window
        val p = target.convertPoint(CGPointMake(CGRectGetMidX(target.bounds), CGRectGetMidY(target.bounds)), null)
        var c: UIView? = w?.hitTest(p); while (c != null && c !is UIControl) c = c.superview
        val ok = c === target
        if (!ok) SFLog.log(String.format(Locale.US, "SELFTEST tasto %s -> SBAGLIATO (%s)", k, c?.javaClass?.simpleName ?: "(null)"))
        if (c is UIControl) { c.sendActionsForControlEvents(UIControlEventTouchDown); c.sendActionsForControlEvents(UIControlEventTouchUpInside) }
        _snap = _state.snapshot(); updateWithSnapshot(_snap)
        return ok
    }

    override fun runSelfTestWithShot(shot: (String) -> Unit, done: (Int) -> Unit) {
        var fails = 0
        val me = this; val st = _state
        val check = { c: Boolean, what: String -> if (!c) fails++; SFLog.log(String.format(Locale.US, "SELFTEST %s %s", if (c) "PASS" else "FAIL", what)) }
        val set = { cmd: String, v: Any? -> val d = LinkedHashMap<String, Any?>(); d["command"] = cmd; if (v != null) d["value"] = v; if (d["command"] == "set_rhythm") d["onset"] = "immediate"; st.applyCommand(d); Unit }
        val D = { st.snapshot().defib }
        val steps = ArrayList<Pair<Double, () -> Unit>>()
        val step = { delay: Double, b: () -> Unit -> steps.add(Pair(delay, b)); Unit }

        step(0.8) { shot("E00-spento")
                    run { var lm = false; val old = me.onLayoutMenu; me.onLayoutMenu = { lm = true }; me.tapKey("MENU"); check(!lm, "spento: MENU non apre i layout (sono nel menu ☰)"); me.onLayoutMenu = old }
                    // ANDROID (connettori 22/11): da spento le prese collegano e staccano (prima: «spento: le prese non collegano»)
                    run { val was = st.snapshot().ecgConnected; me.tapKey("PLUG-ECG"); check(st.snapshot().ecgConnected == !was, "spento: la presa ECG collega/stacca")
                          me.tapKey("PLUG-ECG"); check(st.snapshot().ecgConnected == was, "spento: un secondo tocco la riporta com'era") }
                    check(me.tapKey("POWER") && !me._off && st.snapshot().devicePowered, "accensione") }
        step(0.4) { var bad = 0
                    for (k in me._keys.keys) { val t = me._keys[k]!!
                        val p = t.convertPoint(CGPointMake(CGRectGetMidX(t.bounds), CGRectGetMidY(t.bounds)), null)
                        var h: UIView? = me.window?.hitTest(p); while (h != null && h !is UIControl) h = h.superview
                        if (!t.isEnabled) continue                    // i tasti di un'altra terapia sono spenti: si provano nel loro modo
                        if (h !== t) { bad++; SFLog.log(String.format(Locale.US, "SELFTEST tasto non raggiungibile: %s", k)) } }
                    check(bad == 0, String.format(Locale.US, "tasti attivi tutti raggiungibili (%d in tutto)", me._keys.size))
                    check(!me._keys["CHARGE"]!!.isEnabled && !me._keys["PACE"]!!.isEnabled && !me._keys["ANALYZE"]!!.isEnabled && !me._keys["SHOCK"]!!.isEnabled, "solo monitoraggio: i tasti della terapia sono spenti")
                    for (k in listOf("PLUG-ECG", "PLUG-SPO2", "PLUG-NIBP", "PLUG-CO2", "PLUG-TEMP", "PLUG-PADS")) me.tapKey(k)
                    val sn = st.snapshot()
                    check(sn.ecgConnected && sn.spo2Connected && sn.nibpConnected && sn.etco2Connected && sn.tempConnected && sn.padsConnected, "prese: tutti i sensori collegati")
                    check(me.tapKey("LEAD") && me._pads, "DERIVAZIONE: piastre"); me.tapKey("LEAD"); check(!me._pads, "DERIVAZIONE: II")
                    // ANDROID (BugFix #2): un secondo tocco stacca il sensore, un terzo lo ricollega
                    me.tapKey("PLUG-ECG"); check(!st.snapshot().ecgConnected, "un secondo tocco scollega"); me.tapKey("PLUG-ECG"); check(st.snapshot().ecgConnected, "un terzo tocco ricollega")
                    check(me.tapKey("NIBP") && st.snapshot().nibpStatus == SLNibpMeasuring, "AVVIA NIBP")
                    check(me.tapKey("SILENCE") && st.snapshot().alarmsMuted, "SILENZIA ALLARMI"); me.tapKey("SILENCE"); check(!st.snapshot().alarmsMuted, "RIATTIVA ALLARMI")
                    check(me.tapKey("MENU") && me._popup != null, "MENU: finestra")
                    run { var v = false; val old = me.onVolumes; me.onVolumes = { v = true }; me.tapKey("M-VOLUMI"); check(v && me._popup == null, "Menu › Volumi"); me.onVolumes = old }
                    }
        step(2.5) { shot("E01-monitor") }
        // manuale su FV: energia, carica, scarica
        step(0.3) { set("set_rhythm", "vf")
                    check(me.tapKey("MODE-MAN") && D().mode == SLDefibManual, "modo MANUALE")
                    val e0 = D().energy; me.tapKey("ENERGY+"); check(D().energy > e0, String.format(Locale.US, "1 energia + (%d -> %d J)", e0, D().energy))
                    me.tapKey("ENERGY-"); check(D().energy == e0, "1 energia −")
                    while (D().energy < 200) me.tapKey("ENERGY+"); check(D().energy == 200, "energia 200 J")
                    check(me.tapKey("CHARGE") && D().charge == SLChargeCharging, "2 CARICA") }
        step(2.5) { shot("E02-in-carica") }
        step(4.5) { check(D().charge == SLChargeReady && D().chargedEnergy == 200, "carico a 200 J"); shot("E03-carico")
                    check(me.tapKey("DISARM") && D().charge == SLChargeNone, "ANNULLA CARICA"); me.tapKey("CHARGE") }
        step(7.0) { check(me.tapKey("SHOCK") && D().shockCount == 1, "3 SCARICA erogata") }
        step(1.5) { shot("E04-dopo-scarica")
                    set("set_rhythm", "afib"); check(me.tapKey("SYNC") && D().sync, "SYNC attivo"); me.tapKey("SYNC"); check(!D().sync, "SYNC spento") }
        // pacer su bradicardia
        step(0.3) { set("set_rhythm", "sinus_bradycardia"); set("set_heart_rate", 38)
                    check(!me._keys["RATE+"]!!.isEnabled, "tasti del pacer spenti fuori dal modo PACER")
                    check(me.tapKey("MODE-PACE") && D().mode == SLDefibPacer, "modo PACER")
                    val r0 = D().paceSetRate; me.tapKey("RATE+"); me.tapKey("RATE+"); check(D().paceSetRate == r0 + 10, "FREQ + (5 ppm a tocco)")
                    while (D().paceOutput < 90) me.tapKey("MA+"); check(D().paceOutput == 90, "mA + fino a 90 mA")
                    val dm = D().paceDemand; me.tapKey("DEMAND"); check(D().paceDemand != dm, "DEMAND / FISSO"); me.tapKey("DEMAND")
                    check(me.tapKey("PACE") && D().paceRunning, "AVVIA PACER") }
        step(3.5) { check(D().paceCapture, "cattura sopra la soglia"); shot("E05-pacer")
                    check(me.tapKey("PACE") && !D().paceRunning, "FERMA PACER")
                    check(me.tapKey("MODE-PACE") && D().mode == SLDefibOff, "di nuovo PACER: terapia spenta, solo monitoraggio") }
        // DAE su FV
        step(0.3) { set("set_rhythm", "vf"); check(me.tapKey("MODE-DAE") && D().mode == SLDefibAED && D().aedPhase == SLAedAnalyzing, "modo DAE: analisi") }
        step(17.0) { check(D().aedPhase == SLAedReady, "DAE: scarica consigliata, carico"); shot("E06-dae")
                     check(me.tapKey("SHOCK") && D().aedPhase == SLAedCPR && D().shockCount == 2, "DAE: scarica, poi RCP") }
        step(1.5) { shot("E07-dae-rcp"); check(me.tapKey("ANALYZE") && D().aedPhase == SLAedAnalyzing, "ANALIZZA: nuova analisi")
                    check(me.tapKey("MODE-DAE") && D().mode == SLDefibOff, "di nuovo DAE: solo monitoraggio")
                    check(me.tapKey("POWER") && me._off && D().mode == SLDefibOff, "spegnimento") }

        var at = 0.0
        for (sp in steps) { at += sp.first; Uk.after(at, sp.second) }
        Uk.after(at + 0.3) {
            SFLog.log(String.format(Locale.US, "SELFTEST FINE EXTRAH: %d errori", fails))
            done(fails)
        }
    }

    companion object {
        // ANDROID (layout 21/11): misure della nuova disposizione
        private const val DX = 1024.0                                   // bordo destro della tela: da qui in poi si disegna nella fascia
        private val CONSOLE = CGRectMake(832, 30, 282, 726)             // console di terapia (coordinate della tela; oltre 1024 = fascia)
        private const val COLA = 844.0; private const val COLAW = 170.0 // colonna DEFIBRILLATORE (nella tela)
        private const val COLB = 1032.0; private const val COLBW = 72.0 // colonna di comando (nella fascia)
        private const val TW = 540.0                                    // larghezza delle tracce e del riquadro della terapia
        // scala delle energie di extraH (bifasico): sottoinsieme di quelle del modello
        fun energies(): List<Int> = listOf(5, 10, 20, 30, 50, 70, 100, 120, 150, 170, 200, 300, 360)
    }
}
