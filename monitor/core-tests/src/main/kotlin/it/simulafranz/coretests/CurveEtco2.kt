// ANDROID (EtCO2 visibile 24/11): curve di andamento dell'EtCO2 in arresto per la documentazione (solo sviluppo):
// SF_CURVE_ETCO2=<cartella> -> un CSV per situazione (tempo, valore a monitor dell'ultima espirazione, livello alveolare).
// Via aerea avanzata + ventilazioni automatiche 10/min, così ogni curva ha un punto ogni 6 s.
package it.simulafranz.coretests

import it.simulafranz.core.*
import java.io.File

private fun arrest(asph: Boolean = false): SimulationState {
    val s = NewState(); Sensor(s, "etco2", true)
    Cmd(s, "set_ventilation", mapOf("etco2_cpr_display" to "always", "advanced_airway" to true))
    if (asph) { Cmd(s, "set_resp_rate", 0); Tick(s, 120) }
    RhythmNow(s, "vf"); Tick(s, 1)
    return s
}

private fun record(s: SimulationState, sec: Int, out: StringBuilder, t0: Double, step: ((Int) -> Unit)? = null) {
    for (i in 0 until sec * 2) {
        step?.invoke(i / 2)
        Tick(s, 0.5)
        if (i % 2 == 0) {
            val d = s.stateDictionary()
            out.append(String.format(java.util.Locale.US, "%.1f,%s,%d\n", gNow - t0, d["etco2_displayed"]?.toString() ?: "", INT(d["etco2_mmhg"])))
        }
    }
}

fun curveEtco2(dir: String) {
    File(dir).mkdirs()
    fun run(name: String, asph: Boolean = false, body: (SimulationState, StringBuilder, Double) -> Unit) {
        val s = arrest(asph); val sb = StringBuilder("t_s,etco2_monitor,etco2_alveolare\n"); val t0 = gNow
        body(s, sb, t0); File(dir, "$name.csv").writeText(sb.toString())
    }
    run("01_senza_compressioni") { s, sb, t0 -> record(s, 300, sb, t0) }
    run("02_rcp_corretta_cambio_ogni_2min") { s, sb, t0 -> Cmd(s, "set_cpr", true); record(s, 600, sb, t0) { t -> if (t > 0 && t % 120 == 0) Cmd(s, "cpr_rescuer_change") } }
    run("03_rcp_corretta_senza_cambio") { s, sb, t0 -> Cmd(s, "set_cpr", true); record(s, 600, sb, t0) }
    run("04_superficiale") { s, sb, t0 -> Cmd(s, "set_cpr", true); Cmd(s, "set_cpr_quality", mapOf("depth" to "shallow")); record(s, 300, sb, t0) { t -> if (t > 0 && t % 120 == 0) Cmd(s, "cpr_rescuer_change") } }
    run("05_rilascio_incompleto") { s, sb, t0 -> Cmd(s, "set_cpr", true); Cmd(s, "set_cpr_quality", mapOf("recoil" to "incomplete")); record(s, 300, sb, t0) { t -> if (t > 0 && t % 120 == 0) Cmd(s, "cpr_rescuer_change") } }
    run("06_lenta_85") { s, sb, t0 -> Cmd(s, "set_cpr", true); Cmd(s, "set_cpr_quality", mapOf("rate_cpm" to 85)); record(s, 300, sb, t0) { t -> if (t > 0 && t % 120 == 0) Cmd(s, "cpr_rescuer_change") } }
    run("07_veloce_140") { s, sb, t0 -> Cmd(s, "set_cpr", true); Cmd(s, "set_cpr_quality", mapOf("rate_cpm" to 140)); record(s, 300, sb, t0) { t -> if (t > 0 && t % 120 == 0) Cmd(s, "cpr_rescuer_change") } }
    run("08_mani_scorrette") { s, sb, t0 -> Cmd(s, "set_cpr", true); Cmd(s, "set_cpr_quality", mapOf("hands" to "wrong")); record(s, 300, sb, t0) { t -> if (t > 0 && t % 120 == 0) Cmd(s, "cpr_rescuer_change") } }
    run("09_pausa_30s_e_ripresa") { s, sb, t0 -> Cmd(s, "set_cpr", true); record(s, 300, sb, t0) { t -> if (t == 150) Cmd(s, "set_cpr", false); if (t == 180) Cmd(s, "set_cpr", true) } }
    run("10_rosc_durante_compressioni") { s, sb, t0 -> Cmd(s, "set_cpr", true); record(s, 420, sb, t0) { t -> if (t == 180) RhythmNow(s, "sinus") } }
    run("11_arresto_asfittico", asph = true) { s, sb, t0 -> Cmd(s, "set_cpr", true); record(s, 300, sb, t0) { t -> if (t > 0 && t % 120 == 0) Cmd(s, "cpr_rescuer_change") } }
    run("12_adrenalina_a_2min") { s, sb, t0 -> Cmd(s, "set_cpr", true); record(s, 420, sb, t0) { t -> if (t == 120) { Cmd(s, "cpr_rescuer_change"); SFSessionLog(null, s).add("drug", "Adrenalina 1 mg", null, "istr") }; if (t == 240 || t == 360) Cmd(s, "cpr_rescuer_change") } }
}
