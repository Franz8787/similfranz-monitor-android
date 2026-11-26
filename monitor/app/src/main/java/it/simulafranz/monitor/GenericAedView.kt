// Portato da src/GenericAedView.m (monitor iPad)
// similFranz Monitor — layout "DAE": defibrillatore semiautomatico generico ORIGINALE per l'accesso pubblico
// (non riproduce alcun dispositivo in commercio). Tre passi numerati con i loro comandi (1 accendi, 2 collega le placche,
// 3 scarica), schermo con le istruzioni a testo, tempo e barra della RCP, spia "non toccare", tasto Bambino discreto,
// presa delle placche con la spina. Niente allarmi da monitor né bip.
package it.simulafranz.monitor

import it.simulafranz.core.*
import it.simulafranz.uk.*
import java.util.Locale

private fun GA(r: Number, g: Number, b: Number): UIColor = UIColor.colorWithRed(r.toDouble() / 255.0, g.toDouble() / 255.0, b.toDouble() / 255.0, 1)

//  · corpo 22–30 cm di larghezza: qui 840 punti ≈ 28 cm → 1 cm ≈ 30 punti;
//  · tasto di accensione VERDE, rotondo, in alto, Ø 2,5–3 cm (≈ 10 % della larghezza) → Ø 84 punti;
//  · tasto di scarica ARANCIONE con il fulmine, il più grande ma proporzionato, Ø 3,5–4 cm, 1,2–1,4 volte l'accensione
//    → Ø 116 punti con la sua corona luminosa (lampeggia quando è carico);
//  · bambino: chiave o tastino discreto (≈ 1,5 cm) lontano dal percorso 1-2-3 → 52 punti, in basso a sinistra;
//  · presa delle placche grande e ben visibile (≈ 4 × 2,5 cm), spina grossa e afferrabile (≈ 3 × 2 cm di impugnatura);
//    in alto, altoparlante a griglia, spia "non toccare" che si accende durante l'analisi.
// Scocca: paraurti giallo sicurezza (il colore più riconoscibile dei DAE pubblici) attorno a un frontale grafite su cui
// verde, arancione e bianco si leggono a 2 metri.
private val GA_BODY1 = GA(250, 204, 30)          // paraurti giallo sicurezza
private val GA_BODY2 = GA(222, 166, 6)
private val GA_FACE1 = GA(50, 54, 59)            // frontale grafite
private val GA_FACE2 = GA(36, 39, 43)
private val GA_PANEL = GA(28, 31, 35)            // colonna dei comandi, appena più scura
private val GA_RULE = GA(70, 76, 82)             // filetti stampati
private val GA_TEXT = GA(238, 240, 242)
private val GA_TEXT2 = GA(158, 166, 172)
private val GA_POWER = GA(34, 166, 88)
private val GA_SHOCK = GA(242, 112, 26)
private val GA_LEDOFF = GA(70, 76, 80)
private val GA_LEDON = GA(70, 214, 116)
private val GA_STEPOFF = GA(48, 60, 54)          // lente della spia di un passo, spenta
private const val PLUG_OUT = 44.0                // ANDROID (DAE generico 21/11): spina sfilata = spostata a DESTRA di tanto

// Geometria (tela 1024×768, centrata sullo schermo intero 1280×800 davanti al palco SFAedStage).
// La presa PLACCHE è sul FIANCO destro: spina e cavo stanno tutti FUORI dal corpo (nessuna sovrapposizione con il
// frontale, né da collegate né da scollegate). Per far posto alla spina il corpo è spostato a sinistra: il gruppo
// corpo + spina + cavo è centrato sullo schermo.
private const val kBX = 40.0             // corpo (paraurti)
private const val kBY = 34.0
private const val kBW = 800.0
private const val kBH = 694.0
private const val kBump = 18.0           // spessore del paraurti
private const val kLX = kBX + kBump + 20 // colonna sinistra: schermo, spia "non toccare", altoparlante, bambino
private const val kLW = 500.0
private const val kCW = 200.0            // colonna destra dei comandi 1-2-3
private const val kCX = kBX + kBW - kBump - 20 - kCW
private const val kPY = 356.0            // asse della presa e del cavo delle placche
private const val kSpkW = 264.0          // larghezza dell'altoparlante
private fun GASetText(l: UILabel, t: String?) { if (l.text != t) l.text = t }
private fun GASetFrame(v: UIView, f: CGRect) { if (v.frame != f) v.frame = f }
private fun GASetColor(l: UILabel, c: UIColor) { if (l.textColor != c) l.textColor = c }
private fun GASetBg(v: UIView, c: UIColor) { if (v.backgroundColor != c) v.backgroundColor = c }

// ANDROID (icone DAE 21/11): pittogrammi da set con licenza libera al posto dei disegni a mano (crediti in
// CREDITI_ICONE.md): Material Symbols (Apache 2.0), stile «outlined» pieno. PNG 192×192 bianchi su trasparente
// (conta solo l'alfa) in res/drawable-nodpi/ic_dae_*.png, colorati qui nel colore del contesto (come CtlSondaIcon
// nel controller). Riduzione a mezzi passi: bordi puliti anche a 30 punti.
private val GAIconSrc = HashMap<Int, android.graphics.Bitmap>()
private fun GAIcon(res: Int, size: Double, color: UIColor): UIImage {
    var b = GAIconSrc.getOrPut(res) {
        android.graphics.BitmapFactory.decodeResource(Uk.ctx.resources, res, android.graphics.BitmapFactory.Options().apply { inScaled = false })
    }
    val px = maxOf(1, Uk.px(size))
    while (b.width / 2 >= px) b = android.graphics.Bitmap.createScaledBitmap(b, b.width / 2, b.height / 2, true)
    val out = android.graphics.Bitmap.createBitmap(px, px, android.graphics.Bitmap.Config.ARGB_8888)
    val p = android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG or android.graphics.Paint.ANTI_ALIAS_FLAG)
    p.colorFilter = android.graphics.PorterDuffColorFilter(color.argb, android.graphics.PorterDuff.Mode.SRC_IN)
    android.graphics.Canvas(out).drawBitmap(b, null, android.graphics.Rect(0, 0, px, px), p)
    return UIImage(out)
}
/** icona quadrata di lato `size` centrata in `c` (coordinate di `v`) */
private fun GAIconView(res: Int, c: CGPoint, size: Double, color: UIColor, v: UIView): UIImageView {
    val iv = UIImageView(CGRectMake(c.x - size / 2, c.y - size / 2, size, size))
    iv.image = GAIcon(res, size, color); v.addSubview(iv)
    return iv
}

class GenericAedView(frame: CGRect, state: SimulationState, engine: WaveformEngine, sound: SoundEngine) : UIView(frame), SFAltLayout {
    override var onLayoutMenu: (() -> Unit)? = null
    override var onInfo: (() -> Unit)? = null
    override var onVolumes: (() -> Unit)? = null

    private var _cprTotal = 0      // durata della RCP del ciclo in corso (letta una volta)
    private val _state: SimulationState = state; private val _engine: WaveformEngine = engine; private val _sound: SoundEngine = sound
    private var _snap: SLSnapshot
    private var _on = false; private var _child = false; private var _flattened = false
    private val _keys = LinkedHashMap<String, UIButton>()
    private lateinit var _shockRing: UIView
    private lateinit var _screen: UIView; private lateinit var _offCover: UIView; private lateinit var _plug: UIView; private lateinit var _plugNose: UIView
    private lateinit var _childLed: UIView; private lateinit var _statusLamp: UIView; private lateinit var _socketLamp: UIView
    private lateinit var _barTrack: UIView; private lateinit var _barFill: UIView
    // tolta la spia tonda del metronomo (_beat) che sullo schermo non si capiva: il ritmo lo dà il metronomo sonoro.
    private lateinit var _rate: UILabel; private lateinit var _depth: UILabel
    private lateinit var _cprViews: List<UIView>; private lateinit var _depthBar: List<UIView>
    private var _jDepth = 0.0; private var _jRate = 0.0; private val _rnd = java.util.Random()
    private lateinit var _status: UILabel; private lateinit var _padsState: UILabel; private lateinit var _hint: UILabel; private lateinit var _cprHint: UILabel
    private var _plugIn = false
    private lateinit var _stepLed: List<UIView>
    private lateinit var _patient: UILabel; private lateinit var _elapsed: UILabel; private lateinit var _big: UILabel
    private lateinit var _cprTitle: UILabel; private lateinit var _cprTime: UILabel; private lateinit var _shocks: UILabel; private lateinit var _energy: UILabel
    private var _onAt = 0.0; private var _lastSlow = 0.0
    // ANDROID (DAE generico 21/11): scariche contate da quando il DAE è stato acceso (non quelle manuali o di altri layout)
    private var _shockBase = 0
    // ANDROID (guasti 22/11): malfunzionamenti simulati: bip da spento, batteria simulata nell'intestazione dello schermo
    private val _beeper = SFFaultBeeper()
    private lateinit var _battIcon: SFBatteryIcon
    private val _faultTexts = SFFaultScreenTexts("dae")

    init {
        _snap = state.snapshot()
        buildStage()
        buildBody()
        buildScreen()
        updateWithSnapshot(_snap)
    }

    override fun didMoveToWindow() {
        super.didMoveToWindow()
        if (window == null || _flattened) return
        _flattened = true
        Uk.async {
            val keep = arrayListOf<UIView>(_plug, _statusLamp, _status, _socketLamp, _shockRing, _padsState, _hint)
            keep.addAll(_stepLed)
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
    // ANDROID (icone DAE 21/11): tolta la sagoma disegnata a mano (figure): il tasto Bambino usa ic_dae_bambino

    private fun shape(p: UIBezierPath, fill: UIColor?, stroke: UIColor?, width: Number, `in`: UIView): CAShapeLayer {
        val l = CAShapeLayer.layer()
        l.path = p.CGPath; l.fillColor = (fill ?: UIColor.clearColor).CGColor; l.strokeColor = (stroke ?: UIColor.clearColor).CGColor
        l.lineWidth = width.toDouble(); l.lineCap = kCALineCapRound; l.lineJoin = kCALineJoinRound
        `in`.layer.addSublayer(l)
        return l
    }
    private fun button(name: String, frame: CGRect, action: () -> Unit): UIButton {
        val b = UIButton.buttonWithType(UIButtonTypeCustom).also { SFTasti.register(name, it) }
        b.frame = frame
        b.addTarget(UIControlEventTouchUpInside) { action() }
        addSubview(b)
        _keys[name] = b
        return b
    }

    // MARK: - Scocca

    // ANDROID (DAE generico 21/11): spia a lente incassata (anello scuro, lente, riflesso). Ritorna la lente.
    private fun lens(c: CGPoint, d: Double, `in`: UIView): UIView {
        val rim = box(CGRectMake(c.x - d / 2 - 3, c.y - d / 2 - 3, d + 6, d + 6), GA(14, 15, 17), (d + 6) / 2, `in`)
        rim.layer.borderWidth = 1.0; rim.layer.borderColor = GA_RULE.CGColor
        val l = box(CGRectMake(3, 3, d, d), GA_STEPOFF, d / 2, rim)
        box(CGRectMake(d * 0.22, d * 0.14, d * 0.38, d * 0.26), UIColor.colorWithWhite(1, 0.40), d * 0.13, l)
        return l
    }

    // ANDROID (DAE generico 21/11): intestazione di un passo nella colonna dei comandi: numero stampato nel tondo giallo,
    // verbo in bianco, spia del passo a destra. Ritorna la lente della spia.
    private fun stepHeader(n: Int, title: String, y: Double): UIView {
        val nb = box(CGRectMake(kCX + 16, y, 32, 32), GA_BODY1, 16, this)
        val num = label(CGRectMake(0, 0, 32, 32), nb, 19, true, GA(30, 30, 26), NSTextAlignmentCenter)
        num.text = String.format(Locale.US, "%d", n)
        val t = label(CGRectMake(kCX + 58, y + 3, 110, 26), this, 19, true, GA_TEXT, NSTextAlignmentLeft); t.text = title
        return lens(CGPointMake(kCX + kCW - 28, y + 16), 14.0, this)
    }

    // ANDROID (DAE generico 21/11): palco a schermo intero (fondale grafite-petrolio, piano d'appoggio, ombra di contatto)
    // e il tratto del cavo delle placche che dalla tela attraversa la fascia DESTRA (libera) fino al bordo dello schermo:
    // il cavo esce dalla spina, a lato del corpo, e non attraversa mai il frontale.
    private fun buildStage() {
        val bw = SFSideBand.left?.bounds?.width ?: 0.0
        SFAedStage(this, SFStageStyle(glow = 0xFF34505A.toInt(), wall = 0xFF1A2C33.toInt(), edge = 0xFF070D10.toInt(),
                                      floorTop = 0xFF1E3239.toInt(), floorBottom = 0xFF080F12.toInt(), floorY = 612.0, glowY = 330.0)) { c ->
            val p = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
            // ombra di contatto sotto il corpo
            val cx = (bw + kBX + kBW / 2).toFloat(); val cy = (kBY + kBH + 2).toFloat()
            val g = android.graphics.RadialGradient(0f, 0f, 1f, intArrayOf(0xC0000000.toInt(), 0x58000000, 0x00000000), floatArrayOf(0f, 0.6f, 1f), android.graphics.Shader.TileMode.CLAMP)
            val m = android.graphics.Matrix(); m.setScale((kBW * 0.56).toFloat(), 16f); m.postTranslate(cx, cy); g.setLocalMatrix(m)
            p.shader = g
            c.drawRect(cx - (kBW * 0.6).toFloat(), cy - 18f, cx + (kBW * 0.6).toFloat(), cy + 18f, p)
            p.shader = null
            // cavo delle placche: dal bordo destro della tela al bordo destro dello schermo (prosegue la spina, vedi _plug)
            val x0 = (bw + 1024.0).toFloat(); val y0 = (kPY - 6).toFloat()
            p.color = 0xFF2A2D31.toInt(); c.drawRect(x0, y0, x0 + 400f, y0 + 12f, p)
            p.color = 0x2EFFFFFF; c.drawRect(x0, y0 + 2f, x0 + 400f, y0 + 3.6f, p)
        }
    }

    private fun buildBody() {
        val body = CGRectMake(kBX, kBY, kBW, kBH)
        // paraurti giallo sicurezza con ombra corta e filo di luce in alto
        val sh = box(CGRectOffset(body, 0, 3), UIColor.blackColor, 46, this)
        sh.layer.shadowColor = UIColor.blackColor.CGColor; sh.layer.shadowOpacity = 0.55; sh.layer.shadowRadius = 8.0; sh.layer.shadowOffset = CGSizeMake(0, 4)
        sh.layer.shadowPath = UIBezierPath.bezierPathWithRoundedRect(sh.bounds, 46).CGPath
        val bg = gradient(body, listOf(GA(255, 220, 70), GA_BODY1, GA_BODY2), 46, this)
        bg.borderWidth = 1.0; bg.borderColor = GA(255, 236, 150).CGColor
        // frontale grafite incassato nel paraurti (bordo scuro = la fessura tra le due plastiche)
        val face = CGRectMake(kBX + kBump, kBY + kBump, kBW - 2 * kBump, kBH - 2 * kBump)
        val fg = gradient(face, listOf(GA_FACE1, GA_FACE2), 30, this)
        fg.borderWidth = 2.0; fg.borderColor = GA(120, 92, 8).CGColor

        // ── colonna sinistra ──────────────────────────────────────────────
        val t1 = label(CGRectMake(kLX + 2, 72, 360, 26), this, 19, true, GA_TEXT, NSTextAlignmentLeft)
        t1.text = "DEFIBRILLATORE SEMIAUTOMATICO"
        val t2 = label(CGRectMake(kLX + 2, 98, 360, 18), this, 12.5, false, GA_TEXT2, NSTextAlignmentLeft)
        t2.text = "DAE · per adulti e bambini"
        // spia di prontezza (autoverifica superata), come le finestrelle "pronto" dei DAE veri
        val wx = kLX + kLW - 136
        val win = box(CGRectMake(wx, 76, 136, 40), GA(16, 18, 21), 20, this)
        win.layer.borderWidth = 1.0; win.layer.borderColor = GA_RULE.CGColor
        _statusLamp = box(CGRectMake(wx + 16, 89, 14, 14), GA_LEDON, 7, this)
        _status = label(CGRectMake(wx + 40, 80, 88, 32), this, 16, true, GA(200, 240, 214), NSTextAlignmentLeft)
        _status.text = "PRONTO"

        // schermo: cornice nera incassata (lo schermo vero lo crea buildScreen)
        val bez = box(CGRectMake(kLX, 132, kLW, 316), GA(12, 13, 15), 16, this)
        bez.layer.borderWidth = 1.5; bez.layer.borderColor = GA(96, 102, 108).CGColor

        // spia "non toccare": si accende ambra durante l'analisi e la carica
        val nt = box(CGRectMake(kLX, 464, kLW, 76), GA_PANEL, 14, this)
        nt.layer.borderWidth = 1.0; nt.layer.borderColor = GA_RULE.CGColor
        GAIconView(R.drawable.ic_dae_non_toccare, CGPointMake(kLX + 46, 502), 50.0, GA(255, 196, 92), this)
        val n1 = label(CGRectMake(kLX + 88, 474, 360, 28), this, 20, true, GA_TEXT, NSTextAlignmentLeft); n1.text = "NON TOCCARE IL PAZIENTE"
        val n2 = label(CGRectMake(kLX + 88, 504, 360, 20), this, 12.5, false, GA_TEXT2, NSTextAlignmentLeft); n2.text = "durante l'analisi e la scarica"
        val ntLed = lens(CGPointMake(kLX + kLW - 36, 502), 22.0, this)

        // altoparlante: griglia di fori in un campo arrotondato
        val spk = box(CGRectMake(kLX, 556, kSpkW, 134), GA_PANEL, 20, this)
        spk.layer.borderWidth = 1.0; spk.layer.borderColor = GA_RULE.CGColor
        val holes = UIBezierPath.bezierPath()
        val hp = 14.4; val hd = 7.0                                   // passo e diametro dei fori, griglia centrata
        val nc = ((kSpkW - 30 - hd) / hp).toInt() + 1
        val hx0 = (kSpkW - ((nc - 1) * hp + hd)) / 2; val hy0 = (134 - (7 * hp + hd)) / 2
        for (r in 0 until 8) for (k in 0 until nc) {
            val hx = hx0 + k * hp; val hy = hy0 + r * hp
            holes.appendPath(UIBezierPath.bezierPathWithOvalInRect(CGRectMake(hx, hy, 7, 7)))
        }
        shape(holes, GA(8, 9, 10), null, 0, spk)

        // bambino: tastino discreto fuori dal percorso 1-2-3 (come il tasto pediatrico dei DAE), con la sua spia
        val cbx = kLX + kSpkW + 16; val cbw = kLW - kSpkW - 16
        val cbk = box(CGRectMake(cbx, 556, cbw, 134), GA_PANEL, 20, this)
        cbk.layer.borderWidth = 1.0; cbk.layer.borderColor = GA_RULE.CGColor
        val ch = button("CHILD", CGRectMake(cbx + 18, 597, 52, 52)) { kChild() }
        ch.backgroundColor = GA(204, 208, 210); ch.layer.cornerRadius = 13.0; ch.layer.borderWidth = 1.0; ch.layer.borderColor = GA(120, 126, 130).CGColor
        GAIconView(R.drawable.ic_dae_bambino, CGPointMake(26, 26), 36.0, GA(34, 38, 42), ch)
        SFTactile.applyToButton(ch)
        val kl = label(CGRectMake(cbx + 82, 598, cbw - 82 - 34, 24), this, 17, true, GA_TEXT, NSTextAlignmentLeft); kl.text = "BAMBINO"
        val ks = label(CGRectMake(cbx + 82, 624, cbw - 82 - 12, 18), this, 11.5, false, GA_TEXT2, NSTextAlignmentLeft); ks.text = "sotto 8 anni o 25 kg"
        _childLed = box(CGRectMake(cbx + cbw - 28, 604, 12, 12), GA_LEDOFF, 6, this)
        _childLed.layer.borderWidth = 1.0; _childLed.layer.borderColor = GA(20, 22, 24).CGColor

        // ── colonna destra: i tre passi con i loro comandi ───────────────
        val pnl = box(CGRectMake(kCX, 72, kCW, 618), GA_PANEL, 20, this)
        pnl.layer.borderWidth = 1.0; pnl.layer.borderColor = GA_RULE.CGColor
        for (y in listOf(252.0, 462.0)) box(CGRectMake(kCX + 16, y, kCW - 32, 1.5), GA_RULE, 0.75, this)
        val cx = kCX + kCW / 2
        val leds = arrayOfNulls<UIView>(4)

        // 1 ACCENDI: tasto verde rotondo Ø 84 in una corona scura
        leds[0] = stepHeader(1, "ACCENDI", 86.0)
        val pr = box(CGRectMake(cx - 54, 134, 108, 108), GA(16, 18, 21), 54, this)
        pr.layer.borderWidth = 1.0; pr.layer.borderColor = GA_RULE.CGColor
        val pw = button("POWER", CGRectMake(cx - 42, 146, 84, 84)) { kPower() }
        pw.backgroundColor = GA_POWER; pw.layer.cornerRadius = 42.0; pw.layer.borderWidth = 1.5; pw.layer.borderColor = GA(16, 104, 52).CGColor
        GAIconView(R.drawable.ic_dae_accensione, CGPointMake(42, 42), 48.0, UIColor.whiteColor, pw)   // simbolo IEC 60417-5009
        SFTactile.applyToFace(pw, pw, 42.0, 1.0)

        // 2 COLLEGA: targa stampata "PLACCHE ▶" che indica la presa sul fianco destro, spia e stato sotto
        leds[1] = stepHeader(2, "COLLEGA", 266.0)
        val pl = box(CGRectMake(kCX + 14, kPY - 34, kCW - 28, 68), GA(16, 18, 21), 12, this)
        pl.layer.borderWidth = 1.0; pl.layer.borderColor = GA_RULE.CGColor
        GAIconView(R.drawable.ic_dae_placche, CGPointMake(kCX + 44, kPY), 40.0, GA(220, 224, 228), this)
        val sl = label(CGRectMake(kCX + 70, kPY - 24, 90, 24), this, 17, true, GA_TEXT, NSTextAlignmentLeft); sl.text = "PLACCHE"   // ANDROID (21/11): «Placche» (richiesta utente)
        val s2 = label(CGRectMake(kCX + 70, kPY + 1, 90, 18), this, 11, false, GA_TEXT2, NSTextAlignmentLeft); s2.text = "presa sul fianco"
        val ar = UIBezierPath.bezierPath()                   // freccia gialla verso la presa
        ar.moveToPoint(CGPointMake(kCX + kCW - 36, kPY - 11)); ar.addLineToPoint(CGPointMake(kCX + kCW - 22, kPY)); ar.addLineToPoint(CGPointMake(kCX + kCW - 36, kPY + 11)); ar.closePath()
        shape(ar, GA_BODY1, null, 0, this)
        _socketLamp = box(CGRectMake(kCX + 22, kPY + 54, 12, 12), GA_LEDOFF, 6, this)
        _socketLamp.layer.borderWidth = 1.0; _socketLamp.layer.borderColor = GA(20, 22, 24).CGColor
        _padsState = label(CGRectMake(kCX + 44, kPY + 44, kCW - 58, 34), this, 12.5, true, GA(110, 226, 150), NSTextAlignmentLeft)
        _padsState.numberOfLines = 2
        _hint = label(CGRectMake(kCX + 44, kPY + 44, kCW - 58, 34), this, 11, false, GA_TEXT2, NSTextAlignmentLeft)
        _hint.numberOfLines = 2
        // presa sul fianco: apertura scura incassata nel paraurti, all'altezza della freccia
        val sx = kBX + kBW - 16
        val sock = box(CGRectMake(sx, kPY - 34, 16, 68), GA(10, 11, 12), 5, this)
        sock.layer.borderWidth = 1.5; sock.layer.borderColor = GA(150, 112, 6).CGColor
        box(CGRectMake(5, 10, 3, 48), GA(60, 64, 68), 1.5, sock)                                 // contatti in fondo alla presa

        // 3 SCARICA: tasto arancione Ø 116 con il fulmine; la corona attorno lampeggia quando è carico
        leds[3] = stepHeader(3, "SCARICA", 476.0)
        _shockRing = box(CGRectMake(cx - 74, 516, 148, 148), GA(56, 46, 40), 74, this)
        _shockRing.layer.borderWidth = 1.0; _shockRing.layer.borderColor = GA(96, 84, 76).CGColor
        val shk = button("SHOCK", CGRectMake(cx - 58, 532, 116, 116)) { kShock() }
        shk.backgroundColor = GA_SHOCK; shk.layer.cornerRadius = 58.0; shk.layer.borderWidth = 1.5; shk.layer.borderColor = GA(160, 64, 8).CGColor
        GAIconView(R.drawable.ic_dae_scarica, CGPointMake(58, 58), 70.0, UIColor.whiteColor, shk)
        SFTactile.applyToFace(shk, shk, 58.0, 1.0)
        val sc = label(CGRectMake(kCX + 10, 666, kCW - 20, 18), this, 11, false, GA_TEXT2, NSTextAlignmentCenter)
        sc.text = "lampeggia quando è carico"

        leds[2] = ntLed
        _stepLed = leds.map { it!! }

        // spina sul cavo, tutta FUORI dal corpo: inserita, il naso sparisce nella presa del fianco e l'impugnatura
        // appoggia al paraurti; sfilata, spostata a destra di PLUG_OUT (si vede il naso metallico). Spina e cavo sono
        // ritagliati tra il fianco del corpo e il bordo destro della tela; il cavo prosegue disegnato dal palco
        // (buildStage) fino al bordo dello schermo.
        val ox = kBX + kBW
        val clip = UIView(CGRectMake(ox, kPY - 50, 1024 - ox, 100)); clip.userInteractionEnabled = false; clip.clipsToBounds = true; addSubview(clip)
        _plug = UIView(CGRectMake(-30, 0, 500, 100)); _plug.userInteractionEnabled = false; clip.addSubview(_plug)
        val cab = box(CGRectMake(140, 44, 360, 12), GA(42, 45, 49), 0, _plug)                    // cavo
        box(CGRectMake(0, 2, 360, 1.6), UIColor.colorWithWhite(1, 0.18), 0, cab)
        box(CGRectMake(116, 34, 30, 32), GA(46, 50, 54), 8, _plug)                               // pressacavo
        _plugNose = box(CGRectMake(0, 36, 30, 28), GA(176, 180, 182), 3, _plug)                  // naso metallico
        val ph = box(CGRectMake(28, 16, 92, 68), GA(66, 70, 76), 12, _plug)                       // impugnatura
        ph.layer.borderWidth = 1.0; ph.layer.borderColor = GA(28, 30, 33).CGColor
        box(CGRectMake(14, 5, 70, 3), GA(122, 128, 134), 1.5, ph)                                // riflesso
        for (k in 0 until 6) box(CGRectMake(24 + k * 9, 18, 3.5, 34), GA(48, 52, 56), 1.75, ph)  // zigrinatura
        _plug.transform = CGAffineTransformMakeTranslation(PLUG_OUT, 0)

        // tutta la sezione 2 e la spina sono il tasto: un tocco inserisce la spina
        button("PADS", CGRectMake(kCX, 254, 1024 - kCX, 206)) { kPads() }
        // ANDROID (connettori 22/11): toccando la SPINA (sul fianco, fuori dal frontale) la si stacca se è inserita e la si inserisce
        // se è fuori; il resto della sezione 2 continua solo a collegare (un tocco distratto sul frontale non stacca le placche)
        button("PADS-PLUG", CGRectMake(ox, kPY - 50, 1024 - ox, 100)) { kPadsPlug() }

        // targhetta "similFranz Monitor · Layout": costruita ma nascosta (il nome e la scelta del layout stanno nel menu ☰)
        val lay = SFLayoutButton(CGRectMake(kLX, 72, 150, 44)) { titleTapped() }; addSubview(lay)
        _keys["LAYOUT"] = lay
    }

    // MARK: - Schermo

    private fun buildScreen() {
        val sf = CGRectMake(kLX + 10, 142, kLW - 20, 296)       // ANDROID (DAE generico 21/11): schermo 510×296
        _screen = UIView(sf)
        _screen.backgroundColor = GA(10, 12, 14); _screen.opaque = true; _screen.layer.cornerRadius = 6.0
        addSubview(_screen)
        val W = sf.width; val H = sf.height
        box(CGRectMake(0, 0, W, 28), GA(30, 34, 38), 0, _screen)
        _patient = label(CGRectMake(14, 3, 200, 22), _screen, 14, true, GA(210, 216, 222), NSTextAlignmentLeft)
        _elapsed = label(CGRectMake(W - 150, 3, 136, 22), _screen, 14, false, GA(210, 216, 222), NSTextAlignmentRight)
        _elapsed.font = UIFont.monospacedDigitSystemFontOfSize(14, UIFontWeightMedium)
        // ANDROID (guasti 22/11): batteria simulata, visibile solo con un malfunzionamento della batteria (di serie nascosta).
        // La batteria VERA del tablet sta solo fuori dal dispositivo, in basso a destra (regola dell'utente 22/11).
        _battIcon = SFBatteryIcon(CGRectMake(W - 196, 8, 30, 14), GA(210, 216, 222)); _battIcon.hidden = true; _screen.addSubview(_battIcon)
        _big = label(CGRectMake(18, 36, W - 36, 206), _screen, 28, false, UIColor.whiteColor, NSTextAlignmentCenter)
        _big.font = UIFont.systemFontOfSize(28, UIFontWeightMedium); _big.numberOfLines = 3
        // RCP (ANDROID DAE generico 21/11): sotto il messaggio, tre riquadri FREQUENZA · PROFONDITÀ · TEMPO, poi la barra
        // del ciclo di RCP. Numeri grandi a cifre fisse, titoli piccoli: si leggono a 2 metri.
        val cw = (W - 36 - 2 * 10) / 3; val cy = 86.0; val chh = 118.0
        val cv = ArrayList<UIView>()
        val titles = listOf("FREQUENZA", "PROFONDITÀ", "TEMPO RCP")
        val units = listOf("compressioni/min", "cm", "rimanente")
        val vals = ArrayList<UILabel>()
        for (i in 0 until 3) {
            val x = 18 + i * (cw + 10)
            val c = box(CGRectMake(x, cy, cw, chh), GA(24, 28, 32), 10, _screen); cv.add(c)
            val tl = label(CGRectMake(10, 8, cw - 20, 16), c, 11.5, true, GA(150, 158, 164), NSTextAlignmentCenter); tl.text = titles[i]
            val v = label(CGRectMake(6, 26, cw - 12, 54), c, 46, false, GA(240, 242, 244), NSTextAlignmentCenter)
            v.font = UIFont.monospacedDigitSystemFontOfSize(46, UIFontWeightMedium); vals.add(v)
            val u = label(CGRectMake(10, 80, cw - 20, 16), c, 11.5, false, GA(150, 158, 164), NSTextAlignmentCenter); u.text = units[i]
        }
        _rate = vals[0]; _depth = vals[1]; _cprTime = vals[2]
        // barra della profondità: 5 tacche (1 tacca = 1 cm, verde da 5 cm)
        val bars = ArrayList<UIView>()
        val bw2 = (cw - 40 - 4 * 4) / 5
        for (i in 0 until 5) bars.add(box(CGRectMake(20 + i * (bw2 + 4), chh - 16, bw2, 6), if (i < 4) GA(230, 190, 80) else GA(110, 226, 150), 2, cv[1]))
        _depthBar = bars
        _cprTitle = label(CGRectMake(18, cy + chh + 8, 160, 14), _screen, 10.5, true, GA(150, 158, 164), NSTextAlignmentLeft)
        _cprTitle.text = "CICLO DI RCP"
        _cprHint = label(CGRectMake(W - 260, cy + chh + 8, 242, 14), _screen, 10.5, false, GA(150, 158, 164), NSTextAlignmentRight)
        _cprHint.text = "segui il ritmo del metronomo"
        _barTrack = box(CGRectMake(18, cy + chh + 26, W - 36, 6), GA(46, 52, 56), 3, _screen)
        _barFill = box(CGRectMake(0, 0, 0, 6), GA(150, 158, 164), 3, _barTrack)
        _cprViews = cv
        box(CGRectMake(0, H - 40, W, 1), GA(46, 52, 56), 0, _screen)
        _shocks = label(CGRectMake(14, H - 34, 250, 24), _screen, 14, false, GA(190, 196, 202), NSTextAlignmentLeft)
        _energy = label(CGRectMake(W - 222, H - 34, 208, 24), _screen, 14, false, GA(190, 196, 202), NSTextAlignmentRight)
        _offCover = UIView(_screen.bounds); _offCover.backgroundColor = GA(10, 11, 12); _offCover.layer.cornerRadius = 6.0
        _screen.addSubview(_offCover)
    }

    // MARK: - Stato

    override fun setAlarmText(text: String?, level: Int, severity: Map<String, Int>?, muted: Boolean, remaining: Int) {}
    // un DAE pubblico non ha allarmi da monitor né bip del battito: per il monitor è sempre "spento"
    override fun poweredOff(): Boolean = true
    override fun setDevicePowered(on: Boolean) { if (on != _on) kPower() }
    private fun titleTapped() { onLayoutMenu?.invoke() }
    override fun resetTracesAt(t: Double) {}

    // energia crescente con le scariche: adulto 150 · 200 · 200 J, bambino 50 · 50 · 70 J
    // ANDROID (funzioni 21/11): la scala per adulti va in aed_energy_j (energy_j), quella pediatrica in aed_ped_energy_j;
    // il cuore carica quella giusta secondo la modalità bambino (set_aed_child_mode) e la dice in aed_effective_energy_j
    private fun energies(child: Boolean = _child): List<Int> = if (child) listOf(50, 50, 70) else listOf(150, 200, 200)

    override fun updateWithSnapshot(s: SLSnapshot) {
        _snap = s
        val d = s.defib
        _child = d.aedChildMode                                   // ANDROID (funzioni 21/11): la modalità bambino sta nel cuore (anche dal controller)
        if (_on && d.mode != SLDefibAED) { _on = false; _state.devicePowered = false }           // spento da un comando esterno
        if (!_on && d.mode == SLDefibAED) { _state.devicePowered = true; _on = true; _onAt = CACurrentMediaTime(); _shockBase = d.shockCount }   // acceso dal controller
        if (_offCover.hidden != _on) _offCover.hidden = _on
        // spina: scorre dentro la presa quando la si collega (il cavo resta attaccato)
        val conn = s.padsConnected
        if (conn != _plugIn) {
            _plugIn = conn
            val t = if (conn) CGAffineTransformIdentity else CGAffineTransformMakeTranslation(PLUG_OUT, 0)
            if (conn && window != null) UIView.animateWithDuration(0.35, 0.0, UIViewAnimationOptionCurveEaseOut, { _plug.transform = t },
                                                  { f -> _plugNose.hidden = _plugIn })
            else { _plug.transform = t; _plugNose.hidden = conn }
        }
        GASetText(_padsState, if (conn) "PLACCHE COLLEGATE\nE APPLICATE" else "")   // ANDROID (DAE generico 21/11): «placche»
        GASetText(_hint, if (conn) "" else "Tocca la spina o la presa\nper collegare le placche")
        GASetBg(_childLed, if (_on && _child) GA_LEDON else GA_LEDOFF)
        GASetText(_patient, if (_child) "BAMBINO" else "ADULTO")
        GASetText(_shocks, String.format(Locale.US, "Scariche erogate: %d", maxOf(0, d.shockCount - _shockBase)))
        GASetText(_energy, String.format(Locale.US, "Energia: %d J", d.aedEffectiveEnergy))   // ANDROID (funzioni 21/11): i J che il DAE carica davvero
        GASetText(_status, statusText())                           // ANDROID (guasti 22/11): PRONTO / ATTENZIONE / GUASTO / AUTOTEST
        // ANDROID (revisione finale 23/11): regola dell'utente «solo la batteria esterna»: l'icona sullo schermo del DAE resta
        // sempre nascosta, anche con un guasto simulato della batteria (lo mostrano la batteria esterna e i messaggi del guasto)
        if (!_battIcon.hidden) _battIcon.hidden = true
        if (_on && d.charge == SLChargeNone) {
            val e = energies(false)
            val i = minOf(e.size - 1, maxOf(0, d.shockCount - _shockBase))   // ANDROID (DAE generico 21/11): scariche di questa accensione
            val want = e[i]
            if (d.energy != want) _state.applyCommand(mapOf("command" to "set_defib_config", "value" to mapOf("aed_energy_j" to want)))
            val ped = energies(true)[i]                                     // ANDROID (funzioni 21/11): scala pediatrica
            if ((_child || d.padsPediatric) && d.aedEffectiveEnergy != ped) _state.applyCommand(mapOf("command" to "set_defib_config", "value" to mapOf("aed_ped_energy_j" to ped)))
        }
        // spia del passo in corso: 2 piastre, 3 analisi (non toccare), 4 scarica
        val stepOn = if (!_on) -1 else (if (d.aedPhase == SLAedPads || !conn) 1 else (if (d.aedPhase == SLAedAnalyzing || d.aedPhase == SLAedCharging) 2 else (if (d.aedPhase == SLAedReady) 3 else -1)))
        // ANDROID (DAE generico 21/11): la spia "non toccare" (passo di analisi) si accende ambra, le altre verdi
        for (i in 0 until 4) GASetBg(_stepLed[i], if (i == stepOn) (if (i == 2) GA(255, 186, 60) else GA(110, 255, 150)) else GA_STEPOFF)
    }


    override fun tick(now: Double, snapshot: SLSnapshot) {
        val s = snapshot
        _snap = s
        if (now - _lastSlow < 0.2) return
        _lastSlow = now
        val d = s.defib
        val blink = now % 0.5 < 0.25
        val el = if (_on) (now - _onAt).toInt() else 0
        GASetText(_elapsed, String.format(Locale.US, "%02d:%02d", el / 60, el % 60))
        val cpr = _on && d.aedPhase == SLAedCPR
        for (v in listOf<UIView>(_cprTitle, _cprHint, _barTrack, _cprViews[2])) if (v.hidden != !cpr) v.hidden = !cpr
        // ANDROID (BugFix 23/11, RCP solo se avviata): frequenza e profondità solo con le compressioni in corso (cpr_active);
        // nel periodo RCP senza compressioni restano il tempo del ciclo, la scritta «INIZIARE LA RCP» e il metronomo
        val live = cpr && s.cprActive
        for (v in listOf(_cprViews[0], _cprViews[1])) if (v.hidden != !live) v.hidden = !live
        if (!cpr) _cprTotal = 0
        var m = d.message
        // ANDROID (DAE generico 21/11): i messaggi del cuore scritti come frasi («Shock non erogato. Iniziare la RCP.»,
        // «Allontanarsi dal paziente. Premere ora…») a schermo in maiuscolo, una frase per riga, senza il punto finale
        if (m != m.uppercase(Locale.ITALIAN)) m = m.trim().removeSuffix(".").replace(". ", "\n").uppercase(Locale.ITALIAN)
        for (r in listOf(listOf("SHOCK NON CONSIGLIATO", "SCARICA NON CONSIGLIATA"), listOf("SHOCK ANNULLATO", "SCARICA ANNULLATA"),
                         listOf("SHOCK NON EROGATO", "SCARICA NON EROGATA"), listOf("SHOCK CONSIGLIATO", "SCARICA CONSIGLIATA"),
                         listOf("SHOCK EROGATO", "SCARICA EROGATA"), listOf("PREMERE SHOCK", "PREMERE IL TASTO SCARICA"), listOf("SHOCK", "SCARICA")))
            m = m.replace(r[0], r[1])      // il tasto si chiama SCARICA
        m = m.replace(" — ", "\n")        // una frase per riga
        m = m.replace("PIASTRE", "PLACCHE")   // ANDROID (DAE generico 21/11): «placche» in tutti i testi (decisione utente)
        if (d.aedPhase == SLAedPads && _child) m = "APPLICARE LE PLACCHE\nPEDIATRICHE"
        // ANDROID (guasti 22/11): le scritte dei malfunzionamenti (unità guasta, batteria…) non si coprono con «Collegare il connettore»
        if (!s.padsConnected && !_faultTexts.contains(d.message)) m = if (_child) "COLLEGARE IL CONNETTORE\nDELLE PLACCHE PEDIATRICHE" else "COLLEGARE IL CONNETTORE\nDELLE PLACCHE"
        // ANDROID (DAE generico 21/11): in RCP con compressioni in corso «BUONE COMPRESSIONI» oppure il consiglio
        val good = cpr && s.cprActive && d.message == "RCP IN CORSO"
        if (good) m = "BUONE COMPRESSIONI"
        if (!_on) m = ""
        GASetText(_big, m)
        GASetFrame(_big, if (cpr) CGRectMake(18, 32, _screen.bounds.width - 36, 50) else CGRectMake(18, 36, _screen.bounds.width - 36, 206))
        // spia della presa: lampeggia finché il connettore non è inserito
        GASetBg(_socketLamp, if (_on && !s.padsConnected) (if (blink) GA(250, 190, 70) else GA_LEDOFF) else (if (s.padsConnected && _on) GA_LEDON else GA_LEDOFF))
        // stato a riposo: spia verde che pulsa lenta (autoverifica superata); acceso: fissa
        // ANDROID (guasti 22/11): ambra lampeggiante = attenzione, rossa fissa = guasto, spenta = batteria esaurita,
        // ambra fissa = autotest dopo la sostituzione della batteria; acceso: fissa nel colore dello stato
        GASetBg(_statusLamp, statusLamp(now, s))
        GASetText(_status, statusText())
        GASetColor(_status, when { !_on && s.deviceSelfTest -> GA(255, 214, 140); s.deviceReadiness == "fault" -> GA(255, 150, 140); !_on && s.deviceReadiness == "attention" -> GA(255, 214, 140); else -> GA(200, 240, 214) })
        _beeper.tick(now, s, _sound)
        GASetColor(_big, if (d.aedPhase == SLAedReady) GA(255, 196, 176) else if (good) GA(120, 230, 150) else if (cpr && s.cprActive) GA(255, 200, 100) else UIColor.whiteColor)
        if (cpr) {
            if (_cprTotal == 0) _cprTotal = maxOf(1, (((_state.stateDictionary()["defib"] as? Map<*, *>)?.get("config") as? Map<*, *>)?.get("aed_cpr_s") as? Number)?.toInt() ?: 0)   // una volta per ciclo di RCP
            val total = _cprTotal
            GASetText(_cprTime, String.format(Locale.US, "%d:%02d", d.aedRemaining / 60, d.aedRemaining % 60))
            GASetFrame(_barFill, CGRectMake(0, 0, _barTrack.bounds.width * maxOf(0.0, minOf(1.0, 1.0 - d.aedRemaining.toDouble() / total)), 6))
            // ANDROID (DAE generico 21/11): frequenza e profondità secondo la qualità scelta dall'istruttore (corretta
            // 5–6 cm e 100–120/min), con una piccola oscillazione; solo con compressioni in corso; bambino: niente profondità
            if (now % 2.0 < 0.21) {
                _jDepth = maxOf(-0.4, minOf(0.4, _jDepth * 0.7 + (_rnd.nextInt(100) / 100.0 - 0.5) * 0.3))
                _jRate = maxOf(-6.0, minOf(6.0, _jRate * 0.7 + (_rnd.nextInt(100) / 100.0 - 0.5) * 4))
            }
            val dC = when (s.cprDepthQuality) { "shallow" -> 3.6; "deep" -> 6.9; else -> 5.3 } + _jDepth
            val rC = when (s.cprRateQuality) { "slow" -> 86.0; "fast" -> 136.0; else -> 108.0 } + _jRate
            GASetText(_rate, if (s.cprActive) String.format(Locale.US, "%d", Math.round(rC)) else "--")
            GASetText(_depth, if (s.cprActive && !_child) String.format(Locale.US, "%.1f", dC) else "--")
            val n = if (s.cprActive && !_child) Math.round(dC - 0.6).toInt().coerceIn(1, 5) else 0
            for (i in 0 until 5) { val a = if (i < n) 1f else 0.15f; if (_depthBar[i].alpha != a) _depthBar[i].alpha = a }
        }
        val ready = _on && d.charge == SLChargeReady
        GASetBg(_shockRing, if (ready) (if (blink) GA(255, 214, 120) else GA(150, 80, 36)) else GA(56, 46, 40))     // lampeggia la corona, non il tasto (ANDROID DAE generico 21/11: toni dell'arancione)
    }

    // MARK: - Azioni

    private fun cmd(name: String, value: Any?) {
        val c = LinkedHashMap<String, Any?>(); c["command"] = name
        if (value != null) c["value"] = value
        val ok = _state.applyCommand(c) == null
        if (name.startsWith("defib_")) SFLog.log(String.format(Locale.US, "GenericAedView: %s %s -> %s", name, value ?: "", if (ok) "ok" else "rifiutato"))
        _snap = _state.snapshot(); updateWithSnapshot(_snap)
    }
    // ANDROID (guasti 22/11): finestrella di prontezza secondo device_readiness (di serie: PRONTO verde, IN USO)
    private fun statusText(): String {
        val s = _snap
        if (_on) return if (s.deviceReadiness == "fault") "GUASTO" else "IN USO"
        if (s.deviceSelfTest) return "AUTOTEST"
        return when (s.deviceReadiness) { "attention" -> "ATTENZIONE"; "fault" -> "GUASTO"; "dark" -> ""; else -> "PRONTO" }
    }
    private fun statusLamp(now: Double, s: SLSnapshot): UIColor {
        val amber = GA(255, 176, 40); val red = GA(240, 56, 46)
        if (_on) return when (s.deviceReadiness) { "attention" -> amber; "fault" -> red; else -> GA_LEDON }
        if (s.deviceSelfTest) return amber
        return when (s.deviceReadiness) {
            "attention" -> if (now % 1.0 < 0.5) amber else GA(80, 56, 20)
            "fault" -> red
            "dark" -> GA_LEDOFF
            else -> if (now % 2.0 < 0.4) GA_LEDON else GA(40, 80, 54)
        }
    }

    private fun kPower() {
        _on = !_on
        _state.devicePowered = _on
        if (_on) { _onAt = CACurrentMediaTime(); _shockBase = _state.snapshot().defib.shockCount; cmd("defib_mode", "aed") }
        else cmd("defib_mode", "off")
        updateWithSnapshot(_state.snapshot())
    }
    private fun kPads() { if (!_snap.padsConnected) cmd("set_sensor", mapOf("sensor" to "pads", "connected" to true)) }     // un tocco collega; non scollega mai
    private fun kPadsPlug() { cmd("set_sensor", mapOf("sensor" to "pads", "connected" to !_snap.padsConnected)) }   // ANDROID (connettori 22/11)
    private fun kShock() { if (_on) cmd("defib_shock", null) }
    private fun kChild() {
        if (!_on) return
        cmd("set_aed_child_mode", !_child)     // ANDROID (funzioni 21/11): il cuore registra «DAE: modalità bambino/adulto»
    }

    // MARK: - Autotest

    fun tapKey(key: String): Boolean {
        val k = key
        val target = _keys[k]
        if (target == null) { SFLog.log(String.format(Locale.US, "SELFTEST tasto %s inesistente", k)); return false }
        val p = target.convertPoint(CGPointMake(CGRectGetMidX(target.bounds), CGRectGetMidY(target.bounds)), null)
        var c: UIView? = window?.hitTest(p); while (c != null && c !is UIControl) c = c.superview
        if (c is UIControl) { c.sendActionsForControlEvents(UIControlEventTouchDown); c.sendActionsForControlEvents(UIControlEventTouchUpInside) }
        _snap = _state.snapshot(); updateWithSnapshot(_snap)
        return c === target
    }
    private fun reachable(k: String): Boolean {
        val target = _keys[k] ?: return false
        val p = target.convertPoint(CGPointMake(CGRectGetMidX(target.bounds), CGRectGetMidY(target.bounds)), null)
        var c: UIView? = window?.hitTest(p); while (c != null && c !is UIControl) c = c.superview
        return c === target
    }

    override fun runSelfTestWithShot(shot: (String) -> Unit, done: (Int) -> Unit) {
        var fails = 0
        val me = this; val st = _state
        val check = { c: Boolean, what: String -> if (!c) fails++; SFLog.log(String.format(Locale.US, "SELFTEST %s %s", if (c) "PASS" else "FAIL", what)) }
        val D = { st.snapshot().defib }
        val steps = ArrayList<Pair<Double, () -> Unit>>()
        val step = { delay: Double, b: () -> Unit -> steps.add(Pair(delay, b)); Unit }
        step(0.8) { st.applyCommand(mapOf("command" to "set_aed_child_mode", "value" to false)); me.updateWithSnapshot(st.snapshot()); shot("G00-spento")
                    var all = true; for (k in me._keys.keys) if (k != "LAYOUT" && !me.reachable(k)) { all = false; SFLog.log(String.format(Locale.US, "SELFTEST tasto non raggiungibile: %s", k)) }
                    check(all, String.format(Locale.US, "tasti tutti raggiungibili (%d)", me._keys.size))
                    st.applyCommand(mapOf("command" to "set_rhythm", "value" to "vf"))
                    me.tapKey("SHOCK"); check(D().shockCount == 0, "spento: SCARICA non fa nulla")
                    check(me.tapKey("POWER") && D().mode == SLDefibAED && D().aedPhase == SLAedPads, "accensione: applicare le piastre") }
        step(1.0) { check(me._big.text?.startsWith("COLLEGARE IL CONNETTORE") == true, "schermo: collegare il connettore delle placche"); shot("G01-piastre"); check(me.tapKey("PADS") && st.snapshot().padsConnected && D().aedPhase == SLAedAnalyzing, "piastre collegate: analisi")
                    me.tapKey("PADS"); check(st.snapshot().padsConnected, "un secondo tocco non scollega le piastre") }
        step(2.0) { shot("G02-analisi"); check(CGAffineTransformIsIdentity(me._plug.transform), "spina inserita nella presa"); st.applyCommand(mapOf("command" to "patient_touch", "value" to 3)) }
        step(1.0) { check(D().aedPhase == SLAedAnalyzing && D().message.contains("FERMARE IL MOVIMENTO"), "movimento: analisi interrotta") }
        step(18.5) { check(D().aedPhase == SLAedReady && D().chargedEnergy == 150, String.format(Locale.US, "scarica consigliata, carico a 150 J (%d)", D().chargedEnergy)); shot("G03-pronto")
                     check(me.tapKey("SHOCK") && D().aedPhase == SLAedCPR && D().shockCount == 1, "SCARICA: poi RCP") }
        step(1.0) { st.applyCommand(mapOf("command" to "set_cpr", "value" to true)) }
        step(2.0) { shot("G04-rcp"); check(D().energy == 200, String.format(Locale.US, "energia successiva 200 J (%d)", D().energy))
                    check(me.tapKey("CHILD") && me._child, "tasto bambino"); check(D().aedChildMode && D().aedEffectiveEnergy == 50 && D().energy == 200, String.format(Locale.US, "bambino: 50 J (%d)", D().aedEffectiveEnergy))
                    me.tapKey("CHILD"); st.applyCommand(mapOf("command" to "set_cpr", "value" to false))
                    check(me.tapKey("POWER") && D().mode == SLDefibOff, "spegnimento") }
        var at = 0.0
        for (sp in steps) { at += sp.first; Uk.after(at, sp.second) }
        Uk.after(at + 0.3) {
            SFLog.log(String.format(Locale.US, "SELFTEST FINE DAE: %d errori", fails))
            done(fails)
        }
    }
}


