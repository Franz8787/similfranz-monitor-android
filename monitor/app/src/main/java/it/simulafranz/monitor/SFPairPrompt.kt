// ANDROID: nessun file corrispondente sull'iPad. Associazione rapida di un controller Android: quando un controller
// tocca questo monitor nel suo elenco, qui compare a tutto schermo la richiesta con il nome del controller.
// «Associa» = il controller riceve il PIN da solo e comanda; «Rifiuta» o 60 s senza risposta = niente.
// Serve anche a riconoscere il tablet giusto nell'armadio: si accende la richiesta solo su quello scelto.
package it.simulafranz.monitor

import it.simulafranz.uk.*

// ANDROID (audit 21/11): compact = true quando il dispositivo del partecipante è acceso (scenario in corso o paziente a
// schermo): niente velo scuro a tutto schermo davanti al partecipante, solo una fascia stretta in alto, chiusa da sola dopo 30 s.
// A tutto schermo resta solo in standby (dispositivo spento), quando il monitor si prepara nell'armadio.
class SFPairPrompt(frame: CGRect, controllerName: String, code: String, compact: Boolean = false, private val answer: (Boolean) -> Unit) : UIView(frame) {
    private var done = false

    init {
        if (compact) {
            val W = Math.min(frame.width - 40, 760.0); val H = 64.0
            this.frame = CGRectMake((frame.width - W) / 2, 6, W, H)
            backgroundColor = UIColor.colorWithRed(0.09, 0.11, 0.14, 0.94)
            layer.cornerRadius = 12.0; layer.borderWidth = 1.5; layer.borderColor = UIColor.colorWithRed(0.25, 0.55, 1.0)
            val l = UILabel(CGRectMake(16, 0, W - 16 - 300, H)); l.numberOfLines = 2; l.textColor = UIColor.whiteColor
            l.font = UIFont.systemFontOfSize(15); l.adjustsFontSizeToFitWidth = true; l.minimumScaleFactor = 0.7
            l.text = "Il controller «$controllerName» chiede di comandare questo monitor (codice $code)"
            addSubview(l)
            addSubview(button(CGRectMake(W - 290, 10, 130, 44), "Rifiuta", UIColor.colorWithWhite(0.25), 18.0) { finish(false) })
            addSubview(button(CGRectMake(W - 150, 10, 140, 44), "Associa", UIColor.colorWithRed(0.13, 0.70, 0.30), 18.0) { finish(true) })
            Uk.after(30.0) { finish(false) }
        } else buildFull(frame, controllerName, code)
    }

    private fun buildFull(frame: CGRect, controllerName: String, code: String) {
        backgroundColor = UIColor.colorWithWhite(0, 0.78)
        val W = 600.0; val H = 330.0
        val card = UIView(CGRectMake((frame.width - W) / 2, (frame.height - H) / 2, W, H))
        card.backgroundColor = UIColor.colorWithRed(0.09, 0.11, 0.14)
        card.layer.cornerRadius = 18.0
        card.layer.borderWidth = 2.0; card.layer.borderColor = UIColor.colorWithRed(0.25, 0.55, 1.0)
        addSubview(card)
        fun label(r: CGRect, t: String, size: Double, bold: Boolean, c: UIColor): UILabel {
            val l = UILabel(r); l.text = t; l.textColor = c; l.textAlignment = NSTextAlignmentCenter
            l.font = if (bold) UIFont.boldSystemFontOfSize(size) else UIFont.systemFontOfSize(size); l.numberOfLines = 0
            card.addSubview(l); return l
        }
        label(CGRectMake(24, 26, W - 48, 34), "Associare questo monitor?", 28.0, true, UIColor.whiteColor)
        label(CGRectMake(24, 74, W - 48, 26), "Il controller", 17.0, false, UIColor.colorWithWhite(0.75))
        val n = label(CGRectMake(24, 102, W - 48, 40), controllerName, 30.0, true, UIColor.colorWithRed(0.40, 0.75, 1.0))
        n.numberOfLines = 1; n.adjustsFontSizeToFitWidth = true; n.minimumScaleFactor = 0.5
        label(CGRectMake(24, 146, W - 48, 44), "chiede di comandare questo monitor (codice $code).", 17.0, false, UIColor.colorWithWhite(0.75))
        val no = button(CGRectMake(40, 222, 220, 72), "Rifiuta", UIColor.colorWithWhite(0.25)) { finish(false) }
        val yes = button(CGRectMake(W - 40 - 260, 222, 260, 72), "Associa", UIColor.colorWithRed(0.13, 0.70, 0.30)) { finish(true) }
        card.addSubview(no); card.addSubview(yes)
        Uk.after(60.0) { finish(false) }          // nessuna risposta: la richiesta scade
    }

    private fun button(r: CGRect, t: String, c: UIColor, size: Double = 26.0, tap: () -> Unit): UIButton {
        val b = UIButton(r)
        b.setTitle(t); b.titleLabel.font = UIFont.boldSystemFontOfSize(size)
        b.backgroundColor = c; b.layer.cornerRadius = 14.0
        b.addTarget(UIControlEventTouchUpInside) { tap() }
        return b
    }

    fun finish(ok: Boolean) {
        if (done) return
        done = true
        answer(ok)
        UIView.animateWithDuration(0.2, { alpha = 0f }) { removeFromSuperview() }
    }
}
