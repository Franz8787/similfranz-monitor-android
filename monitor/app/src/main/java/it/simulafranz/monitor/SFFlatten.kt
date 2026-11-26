// Portato da src/SFFlatten.h (monitor iPad)
// SimulaFranz_Monitor — prestazioni dei layout dei dispositivi sull'iPad mini (A5).
// A ogni fotogramma delle onde il sistema ricompone TUTTI gli strati visibili: decine di pannelli, scritte fisse,
// sfumature e ombre del frontale costano molto (backboardd fino al 60%). La decorazione che non cambia mai viene
// "fotografata" una volta sola in un'unica immagine; restano strati separati solo tasti, schermo e parti animate.
package it.simulafranz.monitor

import it.simulafranz.uk.*

fun SFHasControl(v: UIView): Boolean {
    if (v is UIControl) return true
    for (s in v.subviews) if (SFHasControl(s)) return true
    return false
}

// keep: viste figlie dirette di root che cambiano aspetto (spie, lancette, finestre con etichette che cambiano)
// Su Android non fa nulla: ogni vista conserva la propria lista di disegno e le viste ferme non vengono
// ridisegnate né ricomposte a ogni fotogramma delle onde, quindi non serve "fotografare" la decorazione.
// La chiamata resta nei layout così il codice corrisponde riga per riga a quello dell'iPad.
@Suppress("UNUSED_PARAMETER")
fun SFFlattenStatic(root: UIView, keep: List<UIView?>) {}
