// Catalogo dei layout PERSONALIZZATI: nella build pubblica è vuoto (categoria «Personalizzati» senza voci).
// Per aggiungere un tuo layout segui docs/layout-personalizzati.md: una riga qui + la vista nel registro dell'app.
package it.simulafranz.core

object SFCustomLayoutCatalog {
    /** Ogni voce: id (minuscole, cifre e _), nome mostrato nei menu, tipo (SFLayoutKind*: decide il posto nell'elenco). */
    @JvmStatic fun entries(): List<Map<String, String>> = emptyList()
}
