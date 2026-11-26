// Portato da src/SFTactile.m (monitor iPad)
// SimulaFranz_Monitor — tasti "fisici" dei layout dei dispositivi (non GENERIC ICU):
// rilievo lieve (riflesso dall'alto + ombra corta) e animazione di pressione (il tasto scende di 1 pt,
// si stringe del 4 %, l'ombra si accorcia; al rilascio torna su con un piccolo rimbalzo).
package it.simulafranz.monitor

import android.animation.ValueAnimator
import android.view.animation.OvershootInterpolator
import it.simulafranz.uk.*
import java.lang.ref.WeakReference
import java.util.WeakHashMap
import kotlin.math.min

object SFTactile {
    // al posto di objc_setAssociatedObject (valori deboli: la faccia può essere il controllo stesso)
    private val kFaceKey = WeakHashMap<UIView, WeakReference<UIView>>()
    private val kSheenKey = WeakHashMap<UIView, WeakReference<CAGradientLayer>>()
    private val kSideKey = WeakHashMap<UIView, Int>()          // -1 = metà sinistra del bilanciere, +1 = metà destra
    private val rockerAnim = WeakHashMap<UIView, ValueAnimator>()

    /** faccia = pulsante stesso */
    fun applyToButton(button: UIButton?) {
        if (button == null) return
        applyToFace(button, button, button.layer.cornerRadius, 1.0)
    }

    // face: la parte visibile del tasto (può coincidere con control); control: chi riceve i tocchi.
    // radius: raggio degli angoli della faccia. strength: intensità del rilievo (1 = normale; 0 = solo animazione di pressione).
    fun applyToFace(face: UIView?, control: UIControl?, radius: Number, strength: Number) {
        var k = strength.toDouble()
        if (face == null || control == null || kFaceKey[control]?.get() != null) return
        if (k <= 0) {                                         // solo animazione di pressione (tasti già disegnati in rilievo)
            kFaceKey[control] = WeakReference(face)
            addTargetsTo(control)
            return
        }
        k = min(1.5, k)
        val old = kSheenKey[face]?.get()
        if (old != null) {                                    // stessa faccia, altro controllo (es. ▼ ... ▲): niente doppio rilievo
            kFaceKey[control] = WeakReference(face)
            kSheenKey[control] = WeakReference(old)
            addTargetsTo(control)
            return
        }
        // riflesso: chiaro in alto, neutro al centro, leggermente scuro in basso (sopra lo sfondo, sotto testo e icone)
        val g = CAGradientLayer.layer()
        g.frame = face.bounds; g.cornerRadius = radius.toDouble()
        g.colors = listOf(UIColor.colorWithWhite(1, 0.30 * k).CGColor,
                          UIColor.colorWithWhite(1, 0.06 * k).CGColor,
                          UIColor.colorWithWhite(0, 0.0).CGColor,
                          UIColor.colorWithWhite(0, 0.16 * k).CGColor)
        g.locations = listOf(0.0, 0.42, 0.6, 1.0)
        face.layer.insertSublayer(g, 0)
        // ombra corta sotto il tasto (dà la sensazione di tasto in rilievo)
        face.layer.masksToBounds = false
        face.layer.shadowColor = UIColor.blackColor.CGColor
        face.layer.shadowOpacity = 0.38 * min(1.0, k)
        face.layer.shadowRadius = 1.6
        face.layer.shadowOffset = CGSizeMake(0, 1.8)
        face.layer.shadowPath = UIBezierPath.bezierPathWithRoundedRect(face.bounds, radius).CGPath
        kFaceKey[control] = WeakReference(face)
        kSheenKey[control] = WeakReference(g)
        kSheenKey[face] = WeakReference(g)
        addTargetsTo(control)
    }

    // tasto a bilanciere (es. ▼ ENERGIA ▲): la faccia si inclina verso il lato premuto
    fun applyRockerToFace(face: UIView?, left: UIControl?, right: UIControl?, radius: Number) {
        applyToFace(face, left, radius, 1.0)
        applyToFace(face, right, radius, 1.0)
        if (left != null) kSideKey[left] = -1
        if (right != null) kSideKey[right] = 1
        // ANDROID (BugFix #5): ombra di metà tasto, accesa sul lato premuto (il lato che "affonda" si scurisce)
        if (face != null && kShade[face] == null) {
            // sfumata: scura sul bordo del lato premuto, trasparente oltre la metà, chiara sul lato che si solleva
            val r = radius.toDouble()
            fun shadeView(left: Boolean): UIView {
                val v = UIView(face.bounds); v.alpha = 0f; v.userInteractionEnabled = false
                v.clipsToBounds = true; v.layer.cornerRadius = r
                val g = CAGradientLayer.layer(); g.frame = face.bounds
                g.startPoint = CGPointMake(if (left) 0 else 1, 0.5); g.endPoint = CGPointMake(if (left) 1 else 0, 0.5)
                g.colors = listOf(UIColor.colorWithWhite(0, 0.45).CGColor, UIColor.colorWithWhite(0, 0.12).CGColor,
                                  UIColor.colorWithWhite(0, 0.0).CGColor, UIColor.colorWithWhite(1, 0.10).CGColor)
                g.locations = listOf(0.0, 0.35, 0.6, 1.0)
                v.layer.addSublayer(g); face.addSubview(v); return v
            }
            val l = shadeView(true); val rr = shadeView(false)
            kShade[face] = Pair(WeakReference(l), WeakReference(rr))
        }
    }
    private val kShade = WeakHashMap<UIView, Pair<WeakReference<UIView>, WeakReference<UIView>>>()
    private fun shade(face: UIView, side: Int) {
        val p = kShade[face] ?: return
        val l = p.first.get(); val r = p.second.get()
        UIView.animateWithDuration(if (side != 0) 0.05 else 0.18, { l?.alpha = if (side < 0) 1f else 0f; r?.alpha = if (side > 0) 1f else 0f })
    }

    // inclinazione del bilanciere: rotazione attorno all'asse verticale con prospettiva (il lato premuto "affonda").
    // iPad: CATransform3D con m34 = -1/420, rotazione di side × 9° attorno a Y, traslazione di 0.6 pt in basso.
    // Android: View.rotationY (+ cameraDistance per la stessa prospettiva: 420 pt) e translationY.
    private fun SFRockerTransform(face: UIView, side: Int, duration: Double, spring: Boolean) {
        rockerAnim.remove(face)?.cancel()
        // la telecamera di Android sta a cameraDistance / dpi pollici, con 72 px per pollice
        val dpi = face.resources.displayMetrics.densityDpi
        face.cameraDistance = 420f * Uk.scale * dpi / 72f
        val r0 = face.rotationY; val r1 = side * 14.0f        // ANDROID (BugFix #5): 14° (9° dell'iPad quasi non si vedevano sul tablet)
        val y0 = face.translationY; val y1 = 0f                // ANDROID (BugFix #5): niente abbassamento di tutto il tasto
        val va = ValueAnimator.ofFloat(0f, 1f)
        va.duration = (duration * 1000).toLong()
        if (spring) va.interpolator = OvershootInterpolator(2.2f)
        va.addUpdateListener { val t = it.animatedValue as Float; face.rotationY = r0 + (r1 - r0) * t; face.translationY = y0 + (y1 - y0) * t }
        rockerAnim[face] = va
        va.start()
    }

    private fun addTargetsTo(control: UIControl) {
        control.addTarget(UIControlEventTouchDown or UIControlEventTouchDragEnter) { down(it) }
        control.addTarget(UIControlEventTouchUpInside or UIControlEventTouchUpOutside
                          or UIControlEventTouchCancel or UIControlEventTouchDragExit) { up(it) }
    }

    private fun down(c: UIControl) {
        val face = kFaceKey[c]?.get()
        val g = kSheenKey[c]?.get()
        if (face == null) return
        val side = kSideKey[c] ?: 0
        // ANDROID (BugFix #5): bilanciere = si inclina e si scurisce solo il lato premuto; il riflesso di tutto il tasto NON si spegne
        if (side != 0) { SFRockerTransform(face, side, 0.06, false); shade(face, side); return }
        UIView.animateWithDuration(0.06, 0.0, UIViewAnimationOptionBeginFromCurrentState or UIViewAnimationOptionAllowUserInteraction,
            { face.transform = CGAffineTransformScale(CGAffineTransformMakeTranslation(0, 1.0), 0.96, 0.96) }, null)
        CATransaction.begin(); CATransaction.setAnimationDuration(0.06)
        if (g != null) { face.layer.shadowOffset = CGSizeMake(0, 0.4); face.layer.shadowRadius = 0.8; g.opacity = 0.35 }                                     // il riflesso si spegne: tasto "schiacciato"
        CATransaction.commit()
    }

    private fun up(c: UIControl) {
        val face = kFaceKey[c]?.get()
        val g = kSheenKey[c]?.get()
        if (face == null) return
        // iPad: face.layer.transform = CATransform3DIdentity (azzera sia l'inclinazione sia la pressione)
        if ((kSideKey[c] ?: 0) != 0) { SFRockerTransform(face, 0, 0.22, true); shade(face, 0); return }
        UIView.animateWithDuration(0.22, 0.0, 0.55, 0.6,
            UIViewAnimationOptionBeginFromCurrentState or UIViewAnimationOptionAllowUserInteraction,
            { face.transform = CGAffineTransformIdentity }, null)
        CATransaction.begin(); CATransaction.setAnimationDuration(0.16)
        if (g != null) { face.layer.shadowOffset = CGSizeMake(0, 1.8); face.layer.shadowRadius = 1.6; g.opacity = 1.0 }
        CATransaction.commit()
    }
}
