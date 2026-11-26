// ANDROID (categorie dei layout 22/11): registro delle viste dei layout PERSONALIZZATI. Il nome, la categoria e il tipo
// stanno nel catalogo del cuore (core-state: SFCustomLayoutCatalog); qui l'app associa a ogni id la sua vista.
// Le voci sono in SFCustomLayouts (src/custom); la build pubblica (-PsfPublic=true) usa src/public, dove l'elenco è
// vuoto, ed esclude dalla compilazione i file delle viste elencati in app/build.gradle.kts (sfCustomLayoutFiles).
// Come si aggiunge un layout: docs/layout-personalizzati.md.
package it.simulafranz.monitor

import it.simulafranz.core.SimulationState
import it.simulafranz.core.WaveformEngine
import it.simulafranz.uk.CGRect
import it.simulafranz.uk.UIView

/** Un layout personalizzato: id (lo stesso del catalogo del cuore) e la vista che lo disegna (un UIView che è SFAltLayout).
 *  wide = a tutto schermo come l'ICU (tela larga sulle fasce, ☰ compatto in alto a sinistra) invece del dispositivo
 *  da 1024 punti con prese e scocca nelle fasce laterali. */
class SFCustomLayout(
    val id: String,
    val wide: Boolean = false,
    val make: (CGRect, SimulationState, WaveformEngine, SoundEngine) -> UIView,
)

internal fun SFCustomLayoutFor(id: String?): SFCustomLayout? = if (id == null) null else SFCustomLayouts.all.firstOrNull { it.id == id }

/** Layout a tutto schermo (tela larga sulle fasce, ☰ compatto in alto a sinistra): ICU e i personalizzati con wide = true. */
internal fun SFIsWideLayout(layout: String?): Boolean = layout == null || layout == "generic_icu" || SFCustomLayoutFor(layout)?.wide == true
