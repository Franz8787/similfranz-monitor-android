// ANDROID (transizioni ritmo 22/11): preludio fisiologico del cambio di ritmo («onset»), ampiezza della FV, numeri a schermo
// misurati come un monitor vero.
package it.simulafranz.coretests

import it.simulafranz.core.*

private fun onsetOf(s: SimulationState): Any? = s.stateDictionary()["rhythm_onset"]
// avanza a passi di 0,25 s finché il ritmo diventa `to` (massimo `max` s): ritorna i secondi impiegati (-1 se mai)
private fun until(s: SimulationState, to: String, max: Double, each: (() -> Unit)? = null): Double {
    var t = 0.0
    while (t <= max) { if (s.snapshot().rhythm == to) return t; gNow += 0.25; s.tickAtTime(gNow); each?.invoke(); t += 0.25 }
    return -1.0
}
private fun planOf(s: SimulationState): SLOnsetPlan? = s.snapshot().rhythmOnset
private fun kinds(p: SLOnsetPlan?): Set<Int> = p?.beats?.map { it.kind }?.toSet() ?: emptySet()

fun testTransizioni2211() {
    // --- sinusale -> FV (auto): extrasistoli, R su T, poi FV entro pochi secondi; polso fino all'arrivo ---
    var s = NewState()
    CHECK(Cmd(s, "set_rhythm", "vf"), "set_rhythm vf (auto) accettato")
    var p = planOf(s)
    CHECK(p != null && s.snapshot().rhythm == "sinus" && s.snapshot().pulsePresent, "FV: parte il preludio, il ritmo resta sinusale con polso")
    CHECK(onsetOf(s)["to"] == "vf" && onsetOf(s)["from"] == "sinus" && LEN(onsetOf(s)["phase_label"]) > 0 && DBL(onsetOf(s)["remaining_s"]) > 0,
          "FV: rhythm_onset nello stato (to, from, fase, secondi che mancano)")
    CHECK(p != null && p.duration in 1.0..6.5 && kinds(p).contains(SLBeatPVC)) { F("FV: extrasistoli ventricolari, durata %.1f s (<= 6,5)", p?.duration ?: -1.0) }
    var pulseOk = true
    val tVf = until(s, "vf", 8.0) { if (s.snapshot().rhythm == "sinus" && !s.snapshot().pulsePresent) pulseOk = false }
    CHECK(tVf in 1.0..6.75 && pulseOk && onsetOf(s) == null) { F("FV arrivata in %.2f s, polso presente fino all'arrivo", tVf) }
    CHECK(!s.snapshot().pulsePresent && s.stateDictionary()["rhythm_onset_last"]["to"] == "vf", "FV: arresto all'arrivo, ultimo preludio registrato")
    // varianti diverse da un cambio all'altro (deterministiche): almeno due modi di iniziare su 6 prove
    val variants = HashSet<String>()
    for (i in 0 until 6) { RhythmNow(s, "sinus"); Cmd(s, "set_rhythm", "vf"); planOf(s)?.let { variants.add(it.variant) } }
    CHECK(variants.size >= 2) { "FV: varianti plausibili diverse ($variants)" }
    val a = NewState(); val b = NewState()
    for (x in listOf(a, b)) { Cmd(x, "set_rhythm", "vf") }
    CHECK(planOf(a)!!.variant == planOf(b)!!.variant && planOf(a)!!.beats.size == planOf(b)!!.beats.size, "FV: stessa sequenza a parità di comandi (deterministico)")

    // --- durate massime di tutte le transizioni (20 varianti per coppia di ritmi) ---
    run {
        val all = SimulationState.supportedRhythms()
        val bad = ArrayList<String>(); var n = 0
        fun limit(from: String, to: String): Double = when {
            to == "vf" || to == "vt" || to == "vt_pulse" -> 7.0
            to == "afib" && SimulationState.rhythmHasPulse(from) -> 6.0
            to == "asystole" && from == "vf" -> 10.0
            to == "asystole" && SimulationState.rhythmHasPulse(from) -> 20.0
            to == "asystole" -> 12.0
            to == "pea" -> 16.0
            !SimulationState.rhythmHasPulse(from) -> 7.0          // ROSC
            else -> 2.6                                         // fine della FA / TV con polso
        }
        for (from in all) for (to in all) {
            val st = NewState(); RhythmNow(st, from)
            for (k in 0 until 20) {
                RhythmNow(st, from); Cmd(st, "set_rhythm", to)
                val pl = st.snapshot().rhythmOnset ?: continue
                n++
                if (pl.duration > limit(from, to) || pl.beats.zipWithNext().any { it.second.at < it.first.at }) bad.add("$from->$to ${pl.variant} ${"%.1f".format(pl.duration)} s")
            }
        }
        CHECK(n > 300 && bad.isEmpty()) { "preludi: durate entro i limiti, battiti in ordine ($n piani; fuori: ${bad.take(6)})" }
    }

    // --- "onset": "immediate" = di colpo, come prima ---
    s = NewState()
    CHECK(RhythmNow(s, "vf") && s.snapshot().rhythm == "vf" && onsetOf(s) == null, "onset immediate: FV di colpo")
    val err = Err()
    CHECK(!Ok(s.applyCommand(mapOf("command" to "set_rhythm", "value" to "sinus", "onset" to "boh")), err) && err.has("onset"), "onset non valido rifiutato")
    CHECK(!Cmd(s, "transition", mapOf("rhythm" to "sinus", "onset" to 3), err) && err.has("onset"), "transition: onset non valido rifiutato")

    // --- interruzione: un nuovo comando di ritmo sostituisce il preludio ---
    s = NewState(); Cmd(s, "set_rhythm", "vf"); Tick(s, 1.0)
    Cmd(s, "set_rhythm", "sinus")
    CHECK(onsetOf(s) == null && s.snapshot().rhythm == "sinus", "interruzione: il preludio verso la FV si annulla")
    Tick(s, 10)
    CHECK(s.snapshot().rhythm == "sinus", "interruzione: la FV non arriva più")
    s = NewState(); Cmd(s, "set_rhythm", "asystole"); Tick(s, 4.0)
    val hrMid = s.snapshot().heartRate
    Cmd(s, "set_rhythm", "pea")
    CHECK(onsetOf(s)["to"] == "pea", "interruzione: asistolia sostituita dalla PEA (nuovo preludio)")
    CHECK(hrMid < 82, "interruzione: durante la bradicardia progressiva la FC scendeva")
    // la scarica che converte interrompe il preludio
    s = NewState(); Cmd(s, "set_rhythm", "asystole"); Tick(s, 2.0); RhythmNow(s, "sinus"); Tick(s, 20)
    CHECK(s.snapshot().rhythm == "sinus" && onsetOf(s) == null, "interruzione: comando immediato, nessun arrivo tardivo")
    Tick(s, 10)
    CHECK(s.snapshot().heartRate in 78..86) { F("interruzione: la FC mossa dal preludio torna al valore di partenza (%d)", s.snapshot().heartRate) }

    // --- pausa: il preludio si ferma e riparte da dove era ---
    s = NewState(); Cmd(s, "set_rhythm", "vf"); Tick(s, 1.0)
    val rem0 = DBL(onsetOf(s)["remaining_s"])
    Cmd(s, "set_simulation_running", false); gNow += 30; s.tickAtTime(gNow)
    CHECK(s.snapshot().rhythm == "sinus" && Math.abs(DBL(onsetOf(s)["remaining_s"]) - rem0) < 0.11, "pausa: il preludio è fermo")
    val st0 = s.snapshot().rhythmOnsetStartAt
    Cmd(s, "set_simulation_running", true)
    CHECK(Math.abs(s.snapshot().rhythmOnsetStartAt - st0) < 0.01, "pausa: l'inizio per il motore resta allineato alla ripresa")
    CHECK(until(s, "vf", rem0 + 0.6) in (rem0 - 0.3)..(rem0 + 0.6), "pausa: alla ripresa arriva dopo il tempo che mancava")

    // --- sinusale -> TV (con e senza polso) ---
    s = NewState(); Cmd(s, "set_rhythm", "vt")
    p = planOf(s)
    CHECK(p != null && kinds(p).contains(SLBeatVT) && p.duration <= 7.5) { F("TV: extrasistole/salve della stessa forma (durata %.1f)", p?.duration ?: -1.0) }
    val tVt = until(s, "vt", 8.0)
    CHECK(tVt > 0.5 && !s.snapshot().pulsePresent && s.snapshot().heartRate >= 170) { F("TV senza polso arrivata in %.2f s, FC %d", tVt, s.snapshot().heartRate) }
    s = NewState(); Cmd(s, "set_rhythm", "vt_pulse")
    val tVp = until(s, "vt_pulse", 8.0)
    CHECK(tVp > 0.5 && s.snapshot().pulsePresent) { F("TV con polso: arriva dopo il preludio (%.2f s, %s), polso presente", tVp, s.snapshot().rhythm) }

    // --- sinusale -> FA: extrasistoli atriali ---
    s = NewState(); Cmd(s, "set_rhythm", "afib")
    p = planOf(s)
    CHECK(p != null && kinds(p).contains(SLBeatPAC) && p.duration <= 6.0, "FA: extrasistoli atriali, pochi secondi")
    CHECK(until(s, "afib", 6.5) > 0.5, "FA: arrivata")

    // --- sinusale -> asistolia: bradicardia progressiva / blocco AV, 10-20 s ---
    for (i in 0 until 3) {
        s = NewState(); Sensor(s, "art", true)
        for (k in 0 until i) { Cmd(s, "set_rhythm", "asystole"); Cmd(s, "set_rhythm", "sinus", null) }   // varianti diverse
        Cmd(s, "set_rhythm", "asystole"); p = planOf(s)
        var minHr = 999; var pulse = true
        val tA = until(s, "asystole", 22.0) { if (s.snapshot().rhythm == "sinus") { minHr = Math.min(minHr, s.snapshot().heartRate); if (!s.snapshot().pulsePresent) pulse = false } }
        CHECK(tA in 9.0..20.5 && pulse && minHr < 45) { F("asistolia (%s): arrivata in %.1f s (10-20), FC scesa a %d, polso fino all'arrivo", p?.variant, tA, minHr) }
        if (p?.variant == "av_block") CHECK(kinds(p).contains(SLBeatPOnly) && kinds(p).contains(SLBeatEscape), "asistolia da blocco AV: onde P non condotte e scappamento")
    }

    // --- FV -> asistolia: la FV si affina ---
    s = NewState(); RhythmNow(s, "vf"); Tick(s, 1)
    val amp0 = DBL(s.stateDictionary()["vf_amplitude_mv"])
    Cmd(s, "set_rhythm", "asystole"); Tick(s, 4.0)
    val ampMid = DBL(s.stateDictionary()["vf_amplitude_mv"])
    CHECK(s.snapshot().rhythm == "vf" && ampMid < amp0 * 0.6 && ampMid > 0.04) { F("FV -> asistolia: l'ampiezza cala (%.2f -> %.2f mV)", amp0, ampMid) }
    CHECK(until(s, "asystole", 8.0) >= 0, "FV -> asistolia: arrivata entro 8-10 s")

    // --- PEA: ipovolemica (tachicardia) o ipossica (bradicardia con QRS che si allarga) ---
    s = NewState(); Sensor(s, "art", true); RhythmNow(s, "sinus_tachycardia"); Cmd(s, "set_heart_rate", 128)
    Cmd(s, "set_rhythm", "pea"); p = planOf(s)
    CHECK(p?.variant == "hypovolemic_obstructive", "PEA da tachicardia: ipovolemia / causa ostruttiva")
    Tick(s, 7.0)
    CHECK(s.snapshot().rhythm == "sinus_tachycardia" && s.snapshot().heartRate > 128 && s.snapshot().systolic < 100 && s.snapshot().pulsePresent) {
        F("PEA ipovolemica: FC sale (%d), pressione che crolla (%d), polso ancora presente", s.snapshot().heartRate, s.snapshot().systolic) }
    CHECK(until(s, "pea", 9.0) >= 0 && !s.snapshot().pulsePresent, "PEA ipovolemica: arrivata, senza polso")
    s = NewState(); Cmd(s, "set_rhythm", "pea"); p = planOf(s)
    CHECK(p?.variant == "hypoxic_metabolic" && kinds(p).contains(SLBeatWidened) && (p.beats.last().widen) > 0.7, "PEA da FC normale: QRS che si allarga")
    CHECK(until(s, "pea", 16.0) >= 9.0 && s.snapshot().heartRate in 30..45) { F("PEA ipossica: arrivata con FC bassa (%d)", s.snapshot().heartRate) }

    // --- ROSC dalla FV: pausa elettrica, primi complessi lenti che accelerano ---
    s = NewState(); RhythmNow(s, "vf"); Tick(s, 30)
    Cmd(s, "set_rhythm", "sinus")
    CHECK(onsetOf(s)["to"] == "sinus" && onsetOf(s)["phase"] == "electrical_pause" && !s.snapshot().pulsePresent, "ROSC: pausa elettrica, ancora senza polso")
    CHECK(until(s, "sinus", 8.0) > 1.0 && s.snapshot().heartRate in 45..60 && s.snapshot().pulsePresent) { F("ROSC: primi complessi lenti (FC %d)", s.snapshot().heartRate) }
    Tick(s, 16)
    CHECK(s.snapshot().heartRate in 76..84) { F("ROSC: poi la FC sale al valore del ritmo (%d)", s.snapshot().heartRate) }

    // --- tra ritmi sinusali: ritmo subito, FC in rampa (12 s) invece del salto ---
    s = NewState(); Cmd(s, "set_rhythm", "sinus_tachycardia")
    CHECK(s.snapshot().rhythm == "sinus_tachycardia" && s.snapshot().heartRate == 82, "sinusale -> tachicardia: ritmo subito, FC ancora 82")
    Tick(s, 6); val hr6 = s.snapshot().heartRate
    Tick(s, 7)
    CHECK(hr6 in 95..118 && s.snapshot().heartRate == 130) { F("sinusale -> tachicardia: rampa (%d a 6 s, 130 a 13 s)", hr6) }

    // --- fine della FA: pausa di recupero del seno ---
    s = NewState(); RhythmNow(s, "afib"); Cmd(s, "set_rhythm", "sinus")
    p = planOf(s)
    CHECK(p?.variant == "termination" && p.duration in 1.0..2.6 && p.phases.any { it.bg == SLOnsetBgBase }, "FA -> sinusale: pausa post-conversione 1-2,5 s")
    CHECK(until(s, "sinus", 3.0) > 0.5, "FA -> sinusale: arrivato")

    // --- registro: una sola voce, all'arrivo ---
    run {
        val st = SimulationState("monitor-01"); st.clock = { gNow }
        val log = SFSessionLog(null, st)
        Cmd(st, "start_scenario"); log.observe()
        Cmd(st, "set_rhythm", "vf"); log.observe()
        for (i in 0 until 30) { gNow += 0.25; st.tickAtTime(gNow); log.observe() }
        val txt = log.entriesSince(0).map { it["text"] as? String ?: "" }
        val rh = txt.filter { it.startsWith("Ritmo:") }
        CHECK(rh.size == 1 && rh[0].contains("fibrillazione ventricolare") && rh[0].contains("insorgenza")) { "registro: una sola voce del passaggio ($rh)" }
    }

    // --- scenario: la prima fase parte di colpo, le successive con il preludio ---
    run {
        val st = NewState()
        val sc = SFScenarioStore(null, st)
        val r = sc.save(SCJ("{\"name\":\"T\",\"phases\":[{\"rhythm\":\"vf\"},{\"rhythm\":\"sinus\",\"duration_s\":0}]}"))
        CHECK(sc.start(r.scenario!!["id"] as String, 0) == null && st.snapshot().rhythm == "vf" && onsetOf(st) == null, "scenario: la prima fase parte di colpo")
        sc.next()
        CHECK(onsetOf(st)["to"] == "sinus", "scenario: la fase successiva cambia ritmo con il preludio")
    }

    // --- motore: durante il preludio battiti (extrasistoli), poi FV senza QRS ---
    run {
        val st = NewState(); Cmd(st, "set_rhythm", "vf")
        val e = WaveformEngine(3); e.updateWithSnapshot(st.snapshot())
        val pl = planOf(st)!!
        var t = gNow; var lastBeat = -1.0; var maxAfter = 0.0; var minAfter = 0.0
        val end = gNow + pl.duration + 4.0
        while (t < end) {
            if (Math.abs((t * 4) - Math.round(t * 4)) < 1e-6 && t - gNow > 0) { gNow = t; st.tickAtTime(gNow); e.updateWithSnapshot(st.snapshot()) }
            val v = e.ecgAt(t, SLLeadII)
            if (t > gNow - 1 && st.snapshot().rhythm == "vf" && t > end - 2.5) { maxAfter = Math.max(maxAfter, v); minAfter = Math.min(minAfter, v) }
            lastBeat = e.lastBeatAtOrBefore(t)
            t += 0.004
        }
        CHECK(lastBeat > 0 && lastBeat < end - 2.5, "motore: nessun QRS dopo l'arrivo della FV")
        CHECK(maxAfter - minAfter > 0.5) { F("motore: FV grossolana all'insorgenza (%.2f mV picco-picco)", maxAfter - minAfter) }
    }
    // la FV fine si vede più piccola
    run {
        val st = NewState(); RhythmNow(st, "vf")
        fun pp(): Double { val e = WaveformEngine(5); e.updateWithSnapshot(st.snapshot()); var mn = 9.0; var mx = -9.0; var t = gNow
            while (t < gNow + 6) { val v = e.ecgAt(t, SLLeadII); mn = Math.min(mn, v); mx = Math.max(mx, v); t += 0.004 }; return mx - mn }
        val coarse = pp()
        Cmd(st, "set_vf_amplitude", "fine")
        val fine = pp()
        CHECK(fine < coarse * 0.25 && fine > 0.05) { F("motore: FV fine %.2f mV contro grossolana %.2f mV", fine, coarse) }
    }

    // --- ampiezza della FV ---
    s = NewState(); RhythmNow(s, "vf")
    var d = s.stateDictionary()
    CHECK(DBL(d["vf_amplitude_mv"]) == 1.0 && d["vf_amplitude_class"] == "coarse" && d["vf_amplitude_mode"] == "auto", "FV appena insorta: grossolana 1 mV, automatica")
    Tick(s, 300)
    d = s.stateDictionary()
    CHECK(DBL(d["vf_amplitude_mv"]) in 0.45..0.65) { F("FV senza RCP per 5 min: ~0,55 mV (%.2f)", DBL(d["vf_amplitude_mv"])) }
    Cmd(s, "set_cpr", true)
    for (k in 0 until 3) { Tick(s, 100); Cmd(s, "cpr_rescuer_change") }   // RCP buona = cambio dell'operatore ogni 2 min (25/11)
    CHECK(DBL(s.stateDictionary()["vf_amplitude_mv"]) >= 0.75, "FV con RCP buona: si mantiene / migliora")
    CHECK(Cmd(s, "set_vf_amplitude", "fine") && s.stateDictionary()["vf_amplitude_class"] == "fine" && s.stateDictionary()["vf_amplitude_mode"] == "instructor", "set_vf_amplitude fine")
    Cmd(s, "set_cpr", false); Tick(s, 120)
    CHECK(DBL(s.stateDictionary()["vf_amplitude_mv"]) == 0.15, "valore dell'istruttore: non evolve")
    CHECK(Cmd(s, "set_vf_amplitude", 0.4) && DBL(s.stateDictionary()["vf_amplitude_mv"]) == 0.4 && s.stateDictionary()["vf_amplitude_class"] == "medium", "set_vf_amplitude numerico (mV)")
    CHECK(!Cmd(s, "set_vf_amplitude", 3.0, err) && !Cmd(s, "set_vf_amplitude", "boh", err), "ampiezza fuori intervallo rifiutata")
    CHECK(Cmd(s, "set_vf_amplitude", "auto") && s.stateDictionary()["vf_amplitude_mode"] == "auto", "set_vf_amplitude auto: torna l'evoluzione")
    RhythmNow(s, "sinus")
    CHECK(!Cmd(s, "set_vf_amplitude", "fine", err) && err.has("not VF") && s.stateDictionary()["vf_amplitude_mv"] == null, "ampiezza solo con la FV")
    // con set_rhythm: vale all'arrivo
    s = NewState(); s.applyCommand(mapOf("command" to "set_rhythm", "value" to "vf", "vf_amplitude" to "fine"))
    until(s, "vf", 8.0)
    CHECK(DBL(s.stateDictionary()["vf_amplitude_mv"]) == 0.15, "set_rhythm vf con vf_amplitude: vale all'arrivo")
    RhythmNow(s, "sinus"); RhythmNow(s, "vf")
    CHECK(s.stateDictionary()["vf_amplitude_mode"] == "auto" && DBL(s.stateDictionary()["vf_amplitude_mv"]) == 1.0, "cambio di ritmo: il valore dell'istruttore decade")
    // adrenalina: più grossolana per qualche minuto
    s = NewState(); RhythmNow(s, "vf"); Tick(s, 300); val beforeEpi = DBL(s.stateDictionary()["vf_amplitude_mv"])
    s.noteEpinephrine(); Tick(s, 90)
    CHECK(DBL(s.stateDictionary()["vf_amplitude_mv"]) > beforeEpi * 1.1, "adrenalina: FV più grossolana")

    // --- DAE: FV molto fine (< 0,1 mV) = scarica non consigliata ---
    for ((amp, adv) in listOf(0.08 to false, 0.15 to true, 1.0 to true)) {
        s = NewState(); RhythmNow(s, "vf"); Cmd(s, "set_vf_amplitude", amp)
        Cmd(s, "defib_mode", "aed"); Sensor(s, "pads", true); Tick(s, 9)
        val dd = s.snapshot().defib
        CHECK(dd.aedShockAdvised == adv) { F("DAE: FV %.2f mV -> %s", amp, if (adv) "scarica consigliata" else "scarica NON consigliata") }
    }

    // --- numeri a schermo misurati come un monitor vero ---
    s = NewState(); Sensor(s, "etco2", true)
    s.snapshot()
    Cmd(s, "transition", mapOf("params" to mapOf("heart_rate_bpm" to 130, "spo2_percent" to 85), "duration_s" to 20))
    val shown = ArrayList<Int>(); val sp = ArrayList<Int>()
    for (i in 0 until 40 * 30) {                      // 40 s a 30 fotogrammi al secondo
        gNow += 1.0 / 30; s.tickAtTime(gNow); val sn = s.snapshot()
        if (i % 30 == 29) { shown.add(sn.monDisplayedHeartRate); sp.add(sn.monSpo2) }
    }
    val steps = shown.zipWithNext { x, y -> y - x }.filter { it != 0 }
    CHECK(steps.isNotEmpty() && steps.count { it >= 3 } >= 3 && shown.zipWithNext().count { it.first == it.second } >= 2) { "FC a schermo a gradini irregolari, non +1 alla volta ($shown)" }
    CHECK(shown[4] < 82 + (130 - 82) * 5 / 20 && shown.last() in 128..132) { "FC a schermo: segue la rampa con un lieve ritardo, poi 130 ($shown)" }
    CHECK(sp[7] >= 96 && sp.last() in 84..86) { "SpO2 a schermo: ~10 s di ritardo del dito, poi media fino a 85 ($sp)" }
    CHECK(INT(s.stateDictionary()["heart_rate_bpm"]) == 130 && INT(s.stateDictionary()["monitor_numerics"]["heart_rate_bpm"]) in 128..132,
          "valori veri per la regia; monitor_numerics per lo schermo")
    // EtCO2 a ogni espirazione
    Cmd(s, "set_etco2", 50); gNow += 0.05; s.tickAtTime(gNow)
    CHECK(s.snapshot().monEtco2 != 50, "EtCO2 a schermo: cambia alla prossima espirazione, non subito")
    for (i in 0 until 6 * 30) { gNow += 1.0 / 30; s.tickAtTime(gNow); s.snapshot() }
    CHECK(s.snapshot().monEtco2 == 50, "EtCO2 a schermo: valore dell'ultimo atto")
}
