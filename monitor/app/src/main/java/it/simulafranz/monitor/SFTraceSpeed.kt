// ANDROID (ritardo R→polso 22/11) — file nuovo, solo Android.
// Velocità di scorrimento dei tracciati come sul dispositivo vero.
//  - Layout «a grandezza naturale» (GENERIC ICU, extraH: il vetro del tablet È lo schermo del monitor): mm/s veri sul vetro,
//    dai dpi reali del pannello (Tab Active 2: 189,9 dpi, 1 punto = 0,1396 mm). Prima 160 punti/s = 25 mm/s sull'iPad
//    (163 dpi, 1 punto = 1 pixel) ma 22,3 mm/s sul Tab Active 2.
//    (larghezza in punti) rappresenta lo schermo vero (larghezza in mm): la velocità di serie si scala con lo schermo, così
//    una traccia mostra gli stessi secondi del dispositivo vero e il QRS e la sua onda di polso hanno la stessa distanza
//    in proporzione allo schermo. Prima ogni layout aveva un numero «a occhio».
// Larghezza dell'area visibile dello schermo, per layout:
package it.simulafranz.monitor

import it.simulafranz.uk.Uk


/** punti della tela per millimetro del vetro del tablet (dpi reali del pannello; se assurdi, dpi della densità) */
internal fun SFPtPerMm(): Double {
    val dm = Uk.ctx.resources.displayMetrics
    val dpi = if (dm.xdpi in 100f..600f) dm.xdpi.toDouble() else dm.densityDpi.toDouble()
    return dpi / 25.4 / Uk.scale
}

/** velocità a grandezza naturale: mm/s veri sul vetro del tablet → punti/s */
internal fun SFSpeedLife(mmPerS: Double): Double = mmPerS * SFPtPerMm()

/** velocità di un layout in scala: schermo del layout largo screenPt punti = realMm sul dispositivo vero → punti/s */
internal fun SFSpeedScaled(mmPerS: Double, screenPt: Double, realMm: Double): Double = mmPerS * screenPt / realMm
