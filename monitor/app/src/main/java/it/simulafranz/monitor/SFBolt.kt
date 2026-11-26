// Portato da src/SFBolt.h (monitor iPad)
// SimulaFranz_Monitor — fulmine vettoriale per i tasti SHOCK (stesso disegno in tutti i layout).
// Forma piena a zig-zag con punta in basso, angoli smussati, contorno sottile e ombra corta.
package it.simulafranz.monitor

import it.simulafranz.uk.*
import kotlin.math.max

private val kBoltPoints = doubleArrayOf(0.50, 0.00, 0.92, 0.00, 0.60, 0.40, 0.98, 0.40, 0.22, 1.00, 0.42, 0.55, 0.04, 0.55)

fun SFBoltPath(r: CGRect): UIBezierPath {
    // proporzioni 0.62 x 1 centrate nel rettangolo
    val h = r.size.height; val w = h * 0.62
    val x = r.origin.x + (r.size.width - w) / 2; val y = r.origin.y
    val b = UIBezierPath.bezierPath()
    for (i in 0 until 7) {
        val q = CGPointMake(x + kBoltPoints[2 * i] * w, y + kBoltPoints[2 * i + 1] * h)
        if (i == 0) b.moveToPoint(q) else b.addLineToPoint(q)
    }
    b.closePath()
    return b
}

// Aggiunge (o sostituisce) il fulmine dentro la vista, alto `frac` della vista, nel colore dato.
fun SFAddBolt(v: UIView, fill: UIColor, edge: UIColor?, frac: Number): CAShapeLayer {
    for (l in ArrayList(v.layer.sublayers)) if (l.name == "SFBolt") l.removeFromSuperlayer()
    val h = v.bounds.size.height * frac.toDouble()
    val r = CGRectMake(0, (v.bounds.size.height - h) / 2, v.bounds.size.width, h)
    val s = CAShapeLayer.layer()
    s.name = "SFBolt"; s.frame = v.bounds; s.path = SFBoltPath(r).CGPath
    s.fillColor = fill.CGColor; s.strokeColor = (edge ?: fill).CGColor
    s.lineWidth = max(1.0, h * 0.035); s.lineJoin = kCALineJoinRound   // spigoli smussati
    s.shadowColor = UIColor.blackColor.CGColor; s.shadowOpacity = 0.30; s.shadowRadius = 1.5; s.shadowOffset = CGSizeMake(0, 1)
    s.shadowPath = s.path                          // ombra precalcolata: senza, verrebbe ricalcolata a ogni fotogramma
    v.layer.addSublayer(s)
    return s
}
