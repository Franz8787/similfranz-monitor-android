// Portato da src/CtlPairingView.m (controller iPad)
// similFranz Controller — abbinamento al monitor: elenco dei monitor vicini -> tocco -> PIN sul tastierino.
// Il monitor e il PIN restano memorizzati: alle accensioni successive il collegamento riparte da solo.
// ANDROID, differenze dall'originale (segnate con "ANDROID:" nel punto in cui sono):
//  - nei testi "iPad" -> "tablet", e il collegamento diretto è Wi-Fi Direct (niente Bluetooth);
//  - sotto la carta, riga "Questo controller: <nome>" con il tasto «Rinomina» (sull'iPad il nome si cambia da Impostazioni).
package it.simulafranz.controller

import it.simulafranz.uk.*
import java.util.Locale

class CtlPairingView(frame: CGRect, val model: CtlModel) : UIView(frame) {
    private val m: CtlModel = model
    private val card: UIView; private val body: UIView; private val title: UILabel; private val sub: UILabel
    private var sig: String? = null; private val digits = StringBuilder(); private var dots: UILabel? = null; private var err: UILabel? = null
    private val myName: UILabel        // ANDROID: nome di questo controller
    private val clockHint: UILabel     // ANDROID (guasti 22/11): orologio del tablet sbagliato

    init {
        val f = frame
        backgroundColor = CTL_BG
        val brand = CtlLabel(CGRectMake(0, 44, f.width, 44), "", 34.0, true, CTL_INK); brand.textAlignment = NSTextAlignmentCenter
        val t = NSAttributedString("similFranz", UIFont.fontWithName("AvenirNext-DemiBold", 36), CTL_INK) +
                NSAttributedString(" Controller", UIFont.fontWithName("AvenirNext-UltraLight", 36), CTL_TINT)
        brand.attributedText = t; brand.uiTag = 4242; addSubview(brand)
        card = CtlCard(CGRectMake((f.width - 560) / 2, 112, 560, 580)); addSubview(card)
        title = CtlLabel(CGRectMake(28, 22, 504, 30), "", 23.0, true, CTL_INK); card.addSubview(title)
        sub = CtlLabel(CGRectMake(28, 54, 504, 44), "", 14.0, false, CTL_INK2); sub.numberOfLines = 2; card.addSubview(sub)
        body = UIView(CGRectMake(28, 108, 504, 452)); card.addSubview(body)
        // ANDROID: nome di questo controller + «Rinomina», fra la carta e la riga in fondo, allineati ai bordi della carta
        val cx = (f.width - 560) / 2
        myName = CtlLabel(CGRectMake(cx + 4, 698, 560 - 4 - 110 - 12, 30), "", 14.0, false, CTL_INK2); addSubview(myName)
        val rn = CtlButton.buttonWithTitle("Rinomina", CtlButtonQuiet, null, CGRectMake(cx + 560 - 110, 698, 110, 30), null)
        rn.titleLabel.font = UIFont.systemFontOfSize(14); rn.layer.cornerRadius = 8.0
        rn.accessibilityIdentifier = "pair.rename"; rn.onTap = { askName() }
        addSubview(rn)
        val foot = CtlLabel(CGRectMake(0, f.height - 40, f.width, 20), "SIMULAZIONE SANITARIA  ·  NON PER USO CLINICO", 12.0, false, CTL_INK3)
        foot.textAlignment = NSTextAlignmentCenter; addSubview(foot)
        clockHint = CtlLabel(CGRectMake(16, f.height - 20, f.width - 32, 16), "", 11.0, false, CTL_YELLOW); clockHint.textAlignment = NSTextAlignmentCenter
        clockHint.adjustsFontSizeToFitWidth = true; clockHint.minimumScaleFactor = 0.8; clockHint.accessibilityIdentifier = "pair.clockhint"; addSubview(clockHint)
    }

    private fun clear() { for (v in body.subviews) v.removeFromSuperview(); dots = null; err = null }

    fun refresh() {
        myName.text = "Questo controller: " + CtlIdentity.controllerName()     // ANDROID
        // ANDROID (debriefing scollegato 22/11): azzeramento rimasto in sospeso dal debriefing (anche dopo una chiusura dell'app)
        clockHint.text = if (m.resetPending != null) "Azzeramento del monitor in sospeso dal debriefing: parte da solo appena il monitor risponde (solo se la simulazione è ancora quella terminata)"
                         else CtlClockHint(m)                                          // ANDROID (guasti 22/11)
        val st = m.stage
        val sig = StringBuilder(String.format(Locale.US, "%d|%s|%s|%s|", st, m.monitorName, if (st == CtlStageConnecting) m.link.phase else "", m.pinError ?: ""))
        for (mon in m.link.monitors) sig.append(mon.name).append(":").append(mon.bars).append(":").append(mon.busy).append(",")    // ANDROID: anche segnale e "in uso"
        if (st == CtlStageNeedPin) { renderPin() }
        if (sig.toString() == this.sig) return
        this.sig = sig.toString()
        clear()
        val me = this      // vista permanente: nessun problema di ciclo
        if (st == CtlStageSearching) {
            val mons = m.link.monitors
            title.text = if (mons.isNotEmpty()) "Scegli il monitor" else "Cerco i monitor vicini…"
            sub.text = "Collegamento diretto fra tablet: non serve una rete. Basta che il Wi-Fi sia acceso su tutti e due."     // ANDROID: Wi-Fi Direct fra tablet (iPad: Wi-Fi e Bluetooth)
            // ANDROID: associazione smart (tanti monitor uguali nell'armadio): in cima l'ultimo usato, poi i più vicini;
            // per ognuno il codice del tablet (lo stesso del pannello (i) del monitor), le tacche del segnale, libero / in uso.
            // Toccato un monitor, su QUEL monitor compare «Associa»: un tocco là e il controller comanda, senza PIN.
            val list = CtlScrollView(CGRectMake(0, 0, 504, 400)); body.addSubview(list)
            var y = 0.0
            for ((i, mon) in mons.withIndex()) {
                val b = CtlButton.buttonWithTitle("", CtlButtonQuiet, null, CGRectMake(0, y, 504, 72), null)
                b.accessibilityIdentifier = "pair." + mon.name
                val codeBox = CtlLabel(CGRectMake(14, 14, 72, 44), mon.code, 20.0, true, CTL_INK); codeBox.font = CtlDigits(20.0, true)
                codeBox.textAlignment = NSTextAlignmentCenter; codeBox.layer.cornerRadius = 8.0; codeBox.backgroundColor = CTL_CARD; codeBox.userInteractionEnabled = false; b.addSubview(codeBox)
                val n = CtlLabel(CGRectMake(100, 10, 290, 26), mon.name, 19.0, true, CTL_INK); n.userInteractionEnabled = false; b.addSubview(n)
                val tag = when {
                    m.link.isLastUsed(mon) -> "Ultimo usato"          // ANDROID (audit 21/11): per codice, non per nome
                    i == 0 && mon.bars > 0 -> "Il più vicino"
                    else -> ""
                }
                val st = if (mon.busy.isNotEmpty()) "In uso da " + mon.busy + " · puoi annotare" else "Libero"      // ANDROID (due facilitatori 22/11)
                val d = CtlLabel(CGRectMake(100, 40, 300, 20), if (tag.isNotEmpty()) "$tag  ·  $st" else st, 13.0, false, if (mon.busy.isNotEmpty()) CTL_ORANGE else CTL_INK2)
                d.adjustsFontSizeToFitWidth = true; d.minimumScaleFactor = 0.75
                d.userInteractionEnabled = false; b.addSubview(d)
                b.addSubview(SignalBars(CGRectMake(404, 24, 28, 24), mon.bars))
                val go = CtlLabel(CGRectMake(436, 0, 56, 72), "›", 30.0, true, CTL_TINT); go.textAlignment = NSTextAlignmentRight; go.userInteractionEnabled = false; b.addSubview(go)
                b.onTap = { me.m.connect(mon) }
                list.addSubview(b); y += 80
            }
            list.contentSize = CGSizeMake(504, y)
            if (mons.isEmpty()) {
                val sp = UIActivityIndicatorView(UIActivityIndicatorViewStyleWhiteLarge)
                sp.center = CGPointMake(252, 70); sp.startAnimating(); body.addSubview(sp)
                // ANDROID: "iPad" -> "tablet"
                val h = CtlLabel(CGRectMake(20, 120, 464, 200), "1.  Sull'altro tablet apri similFranz Monitor.\n\n2.  Tieni i due tablet vicini (stessa stanza).\n\n3.  Il monitor compare qui da solo in pochi secondi.", 17.0, false, CTL_INK)
                h.numberOfLines = 0; body.addSubview(h)
            }
            val le = m.link.lastError
            if (!le.isNullOrEmpty()) { val e = CtlLabel(CGRectMake(0, 410, 504, 40), le, 14.0, false, CTL_ORANGE); e.numberOfLines = 2; body.addSubview(e) }
        } else if (st == CtlStageConnecting) {
            title.text = String.format(Locale.US, "Collegamento a %s…", m.monitorName ?: "monitor")
            sub.text = "Qualche secondo: i due tablet si stanno accordando."      // ANDROID: "iPad" -> "tablet"
            val sp = UIActivityIndicatorView(UIActivityIndicatorViewStyleWhiteLarge)
            sp.center = CGPointMake(252, 110); sp.startAnimating(); body.addSubview(sp)
            // ANDROID: a che punto siamo e, se si blocca, cosa fare (niente rotella muta)
            val ph = m.link.phase
            val pl = CtlLabel(CGRectMake(0, 160, 504, 90), ph, 18.0, true, if (ph.contains("non risponde")) CTL_ORANGE else CTL_INK)
            pl.numberOfLines = 3; pl.textAlignment = NSTextAlignmentCenter; body.addSubview(pl)
            val c = CtlButton.buttonWithTitle("Annulla", CtlButtonQuiet, null, CGRectMake(152, 380, 200, 48), null)
            c.accessibilityIdentifier = "pair.cancel"; c.onTap = { me.m.forgetMonitor() }
            body.addSubview(c)
        } else if (st == CtlStagePairing) {
            // ANDROID: associazione rapida — si aspetta il tocco su «Associa» sul monitor
            title.text = "Conferma sul monitor"
            sub.text = String.format(Locale.US, "Su %s è comparsa la richiesta: tocca «Associa» sul suo schermo.", m.monitorName ?: "monitor")
            val sp = UIActivityIndicatorView(UIActivityIndicatorViewStyleWhiteLarge)
            sp.center = CGPointMake(252, 90); sp.startAnimating(); body.addSubview(sp)
            val big = CtlLabel(CGRectMake(0, 150, 504, 90), "Tocca «Associa»\nsul monitor", 30.0, true, CTL_INK); big.numberOfLines = 2; big.textAlignment = NSTextAlignmentCenter; body.addSubview(big)
            val pinB = CtlButton.buttonWithTitle("Usa il PIN", CtlButtonTinted, CTL_TINT, CGRectMake(40, 380, 200, 48), null)
            pinB.accessibilityIdentifier = "pair.usepin"; pinB.onTap = { me.m.usePin() }; body.addSubview(pinB)
            val c = CtlButton.buttonWithTitle("Annulla", CtlButtonQuiet, null, CGRectMake(264, 380, 200, 48), null)
            c.accessibilityIdentifier = "pair.cancel"; c.onTap = { me.m.forgetMonitor() }; body.addSubview(c)
        } else if (st == CtlStageNeedPin) {
            title.text = String.format(Locale.US, "PIN di %s", m.monitorName ?: "monitor")
            sub.text = "Il PIN si legge sul monitor: menu ☰ → «Informazioni e collegamenti». Oppure «Chiedi sul monitor» e lì tocca «Associa»."      // ANDROID (audit 21/11): il tasto (i) non c'è più
            digits.setLength(0)
            val dots = CtlLabel(CGRectMake(0, 0, 504, 56), "", 40.0, true, CTL_INK); dots.font = CtlDigits(40.0, true); dots.textAlignment = NSTextAlignmentCenter; body.addSubview(dots)
            this.dots = dots
            val err = CtlLabel(CGRectMake(0, 56, 504, 22), "", 14.0, true, CTL_RED); err.textAlignment = NSTextAlignmentCenter; body.addSubview(err)
            this.err = err
            val keys = listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "⌫", "0", "OK")
            val kw = 120.0; val kh = 66.0; val x0 = (504 - 3 * kw - 2 * 12) / 2
            for (i in keys.indices) {
                val k = keys[i]; val ok = k == "OK"
                val b = CtlButton.buttonWithTitle(k, if (ok) CtlButtonFilled else CtlButtonQuiet, CTL_TINT, CGRectMake(x0 + (i % 3) * (kw + 12), 88 + (i / 3) * (kh + 10), kw, kh), null)
                b.titleLabel.font = if (ok) UIFont.boldSystemFontOfSize(20) else CtlDigits(28.0, false)
                b.accessibilityIdentifier = "key." + (if (ok) "ok" else (if (k == "⌫") "del" else k))
                b.onTap = { me.key(k) }
                body.addSubview(b)
            }
            // ANDROID: «Chiedi sul monitor» = di nuovo l'associazione rapida, accanto a «Scegli un altro monitor»
            val again = CtlButton.buttonWithTitle("Chiedi sul monitor", CtlButtonTinted, CTL_GREEN, CGRectMake(12, 404, 230, 40), null)
            again.titleLabel.font = UIFont.systemFontOfSize(14); again.accessibilityIdentifier = "pair.ask"; again.onTap = { me.m.requestPair() }
            body.addSubview(again)
            val other = CtlButton.buttonWithTitle("Scegli un altro monitor", CtlButtonTinted, CTL_TINT, CGRectMake(262, 404, 230, 40), null)
            other.titleLabel.font = UIFont.systemFontOfSize(14); other.accessibilityIdentifier = "pair.other"; other.onTap = { me.m.forgetMonitor() }
            body.addSubview(other)
            renderPin()
        }
    }

    private fun renderPin() {
        val dots = this.dots ?: return
        val s = StringBuilder()
        for (i in 0 until maxOf(6, digits.length)) s.append(if (i < digits.length) "●  " else "○  ")
        dots.text = s.toString().trim(' ', '\t')
        err?.text = if (digits.isNotEmpty()) "" else (m.pinError ?: "")
    }

    private fun key(k: String) {
        if (k == "⌫") { if (digits.isNotEmpty()) digits.deleteCharAt(digits.length - 1) }
        else if (k == "OK") { if (digits.length >= 4) { val p = digits.toString(); digits.setLength(0); m.submitPin(p); return } }
        else if (digits.length < 12) digits.append(k)
        renderPin()
        if (digits.length == 6) {      // i PIN generati dal monitor hanno 6 cifre: parte da solo
            val p = digits.toString(); digits.setLength(0)
            Uk.after(0.15) { m.submitPin(p) }
        }
    }

    // MARK: - Nome di questo controller (ANDROID)

    // «Rinomina»: avviso con un campo di testo. Spazi tolti ai lati, al massimo 32 caratteri, vuoto = resta il nome di prima.
    // Cambia solo il nome (sf_name): il codice del tablet resta lo stesso. Il nome nuovo vale dalla richiesta successiva al monitor.
    private fun askName() {
        if (window == null) return
        val a = UIAlertController.alertControllerWithTitle("Nome di questo controller",
            String.format(Locale.US, "È il nome con cui il controller compare sul monitor (massimo %d caratteri).\nIl codice %s resta lo stesso e distingue due controller con lo stesso nome.", CtlIdentity.kMaxName, CtlIdentity.code()),
            UIAlertControllerStyleAlert)
        a.addTextField { tf ->
            tf.text = CtlIdentity.controllerName(); tf.placeholder = CtlIdentity.defaultName()
            tf.autocorrectionType = UITextAutocorrectionTypeNo
            tf.maxLength = CtlIdentity.kMaxName
        }
        a.addAction(UIAlertAction.actionWithTitle("Annulla", UIAlertActionStyleCancel, null))
        a.addAction(UIAlertAction.actionWithTitle("Rinomina", UIAlertActionStyleDefault) {
            CtlIdentity.rename(a.textFields.firstOrNull()?.text)
            refresh()
        })
        a.present()
    }
}

// ANDROID: tacche del segnale (vicinanza del monitor), 4 barrette; 0 = sconosciuto (barrette spente)
private class SignalBars(frame: CGRect, private val bars: Int) : UIView(frame) {
    init { userInteractionEnabled = false }
    override fun drawRect(ctx: CGContext, rect: CGRect) {
        val w = (rect.width - 3 * 3) / 4
        for (i in 0 until 4) {
            val h = rect.height * (i + 1) / 4
            ctx.setFillColor(if (i < bars) CTL_GREEN else UIColor.colorWithWhite(1, 0.15))
            UIBezierPath.bezierPathWithRoundedRect(CGRectMake(i * (w + 3), rect.height - h, w, h), 1.5).fill()
        }
    }
}
