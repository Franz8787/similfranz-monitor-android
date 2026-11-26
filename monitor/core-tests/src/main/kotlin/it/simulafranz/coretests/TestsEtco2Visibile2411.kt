// ANDROID (EtCO2 visibile 24/11, richiesta utente): in arresto l'EtCO2 compare anche se l'istruttore non preme «Ventila».
// "auto" (di serie): espirazioni automatiche dopo l'avvio della RCP, 30:2 con maschera o 10/min con via aerea avanzata;
// "always": numero sempre a schermo, valore forzato fisso; "realistic": solo le ventilazioni vere.
package it.simulafranz.coretests

import it.simulafranz.core.*

private fun shown(s: SimulationState): Int? = s.stateDictionary()["etco2_displayed"]?.let { INT(it) }
private fun vfArrest(airway: Boolean = false, mode: String? = null): SimulationState {
    val s = NewState(); Sensor(s, "etco2", true)
    if (mode != null) Cmd(s, "set_ventilation", mapOf("etco2_cpr_display" to mode))
    if (airway) Cmd(s, "set_ventilation", mapOf("mode" to "bag", "advanced_airway" to true))
    RhythmNow(s, "vf"); Tick(s, 20)
    return s
}

fun testEtco2Visibile2411() {
    // di serie: modalità automatica, esposta nello stato
    var s = vfArrest()
    val ve = s.stateDictionary()["ventilation"] as Map<*, *>
    CHECK(ve["etco2_cpr_display"] == "auto", "di serie: EtCO2 in arresto con espirazioni automatiche")
    CHECK(shown(s) == null, "arresto senza RCP: nessuno ventila ancora, niente numero")

    // maschera 30:2: due espirazioni per ciclo, numero 15-20 senza premere «Ventila»
    Cmd(s, "set_cpr", true); Tick(s, 60)
    var sn = s.snapshot()
    CHECK(shown(s) in 14..21) { F("30:2 automatico: EtCO2 a monitor con le compressioni 14-21 (%s)", shown(s)) }
    CHECK(sn.breathTimes.size in 4..8) { F("30:2 automatico: 2 espirazioni per ciclo di ~20 s (%d al minuto)", sn.breathTimes.size) }
    // in pausa con la maschera non si ventila: dopo 30 s il numero sparisce (---)
    Cmd(s, "set_cpr", false); Tick(s, 35)
    CHECK(shown(s) == null, "30:2: in pausa niente insufflazioni, dopo 30 s il numero sparisce")

    // via aerea avanzata: 10/min continue; la pausa porta l'EtCO2 verso 0 in ~30 s, col numero a schermo
    s = vfArrest(airway = true)
    Cmd(s, "set_cpr", true); Tick(s, 60)
    sn = s.snapshot()
    CHECK(sn.breathTimes.size in 9..11) { F("via aerea avanzata: 10 espirazioni al minuto (%d)", sn.breathTimes.size) }
    val run = shown(s) ?: -1
    CHECK(run in 14..21) { F("via aerea avanzata: EtCO2 con RCP 14-21 (%d)", run) }
    Cmd(s, "set_cpr", false); Tick(s, 12)
    val mid = shown(s) ?: -1
    Tick(s, 24)
    val end = shown(s) ?: -1
    // Gutiérrez 2021: -11 % per ventilazione in pausa (a 10/min: ~-45 % in 30 s)
    CHECK(mid < run && end < mid && end in (run * 0.35).toInt()..(run * 0.7).toInt()) { F("pausa: l'EtCO2 cala a ogni ventilazione (%d -> %d -> %d)", run, mid, end) }

    // «Ventila» non fa doppioni con le automatiche
    s = vfArrest(airway = true); Cmd(s, "set_cpr", true); Tick(s, 30)
    val n0 = s.snapshot().breathTimes.size
    Cmd(s, "ventilate"); Tick(s, 6)
    val n1 = s.snapshot().breathTimes.size
    CHECK(n1 - n0 in 1..2) { F("«Ventila» + automatica: nessun doppione (%d atti in 6 s)", n1 - n0) }

    // valore forzato = «con il massaggio»: massaggio buono e continuo +2-4 mmHg, superficiale più basso
    s = vfArrest(airway = true); Cmd(s, "set_cpr", true); Tick(s, 10)
    Cmd(s, "set_etco2", 20); Cmd(s, "cpr_rescuer_change"); Tick(s, 60)
    val good = shown(s) ?: -1
    CHECK(good in 21..24) { F("forzato 20 con RCP buona e continua: sale a 21-24 (%d)", good) }
    Cmd(s, "set_cpr_quality", mapOf("depth" to "shallow")); Tick(s, 60)
    // toccare la qualità riporta al modello (comportamento di prima): si rimette il valore forzato
    Cmd(s, "set_etco2", 20); Tick(s, 40)
    val sh = shown(s) ?: -1
    CHECK(sh in 8..14) { F("forzato 20 con RCP superficiale: più basso (%d)", sh) }

    // sempre visibile: valore forzato fisso, anche in pausa e senza RCP
    s = vfArrest(mode = "always")
    CHECK(shown(s) != null, "sempre visibile: numero a schermo anche prima della RCP")
    Cmd(s, "set_etco2", 25); Tick(s, 15)
    CHECK(shown(s) == 25, "sempre visibile: valore forzato a schermo")
    Cmd(s, "set_cpr", true); Tick(s, 60); Cmd(s, "set_cpr", false); Tick(s, 60)
    CHECK(shown(s) == 25, "sempre visibile: il valore forzato resta fisso in pausa")

    // realistica: senza «Ventila» niente numero (come prima)
    s = vfArrest(mode = "realistic"); Cmd(s, "set_cpr", true); Tick(s, 60)
    CHECK(shown(s) == null, "realistica: senza ventilazioni nessun numero")
    CHECK(!Cmd(s, "set_ventilation", mapOf("etco2_cpr_display" to "boh"), Err()), "modalità sconosciuta rifiutata")

    // ROSC: le automatiche si fermano, torna il respiro del paziente
    s = vfArrest(); Cmd(s, "set_cpr", true); Tick(s, 60)
    RhythmNow(s, "sinus"); Tick(s, 30)
    CHECK(s.snapshot().pulsePresent && (shown(s) ?: 0) >= 30) { F("ROSC: EtCO2 del paziente (%s)", shown(s)) }
}
