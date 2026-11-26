// ANDROID (funzioni mancanti 22/11): test delle funzioni aggiunte dopo gli audit clinici (analisi-fisiologia/CORRETTI.md,
// BugFix.md «Funzioni mancanti 22/11»). Un blocco per funzione.
package it.simulafranz.coretests

import it.simulafranz.core.*

private fun Msgs2(s: SimulationState): String = AlarmManager.evaluate(s.snapshot()).messages.joinToString(",")
/** valuta gli allarmi a ogni mezzo secondo (come il monitor): i ritardi d'allarme partono dalla prima valutazione */
private fun TickEval(s: SimulationState, sec: Double) { var x = 0.0; while (x < sec) { gNow += 0.5; s.tickAtTime(gNow); AlarmManager.evaluate(s.snapshot()); x += 0.5 } }
private fun TickEval(s: SimulationState, sec: Int) = TickEval(s, sec.toDouble())
private fun Started(): SimulationState { val s = NewState(); Cmd(s, "start_scenario"); s.devicePowered = true; return s }
/** campioni di una curva del motore: [t0, t0+dur) a passo dt */
private fun Samples(e: WaveformEngine, t0: Double, dur: Double, dt: Double, f: (Double) -> Double): DoubleArray {
    val n = (dur / dt).toInt(); val a = DoubleArray(n); for (i in 0 until n) a[i] = f(t0 + i * dt); return a
}

fun testMancanti2211() {
    // ---- IN-14: trend dei parametri a schermo, ogni 5 s di scenario ----
    run {
        val s = Started(); Sensor(s, "art", true)
        Tick(s, 60)
        val rows = s.trendSince(0.0, 0)
        CHECK(rows.size in 11..13, { "IN-14: 60 s = ~12 righe di trend (${rows.size})" })
        val last = rows.last()
        CHECK(INT(last["hr"]) in 78..86 && INT(last["spo2"]) in 96..100 && INT(last["art_s"]) in 110..130 && INT(last["art_m"]) in 85..100,
              { "IN-14: riga con FC/SpO2/ART a schermo ($last)" })
        CHECK(last.containsKey("etco2") && last["etco2"] == null && last["cvp"] == null, "IN-14: sensori non collegati = null")
        CHECK(s.trendSince(0.0, 30).size in 2..3, "IN-14: passo 30 s = una riga ogni 30 s")
        RunNibp(s); Tick(s, 6)
        CHECK(s.trendSince(0.0, 0).last()["nibp_s"] != null, "IN-14: NIBP nel trend dopo la misura")
        Cmd(s, "reset_scenario"); Tick(s, 10)
        CHECK(s.trendSince(0.0, 0).isEmpty(), "IN-14: in PRONTO dopo il reset il trend è vuoto")
        val api = APIController(s, { "482913" }, emptyMap()); api.clock = { gNow }
        val q = CReq("GET", "/api/v1/trend", null, "web-A", "Web%20A", null); q.query = "since=0&step=60"; val r = api.handle(q)
        CHECK(r.status == 200 && J(r)?.get("samples") is List<*> && INT(J(r)?.get("interval_s")) == 5, "IN-14: GET /api/v1/trend")
    }
    // ---- IN-03/OP-04: linea arteriosa (smorzamento, livello, zero, lavaggio, occlusione) ----
    run {
        val s = Started(); Sensor(s, "art", true); Tick(s, 10)
        var sn = s.snapshot()
        CHECK(sn.artDisplayedSystolic == 120 && sn.artDisplayedDiastolic == 80, "IN-03: linea ottimale = numeri di prima")
        Cmd(s, "set_art_line", mapOf("damping" to "under")); sn = s.snapshot()
        CHECK(sn.artDisplayedSystolic >= 128 && sn.artDisplayedDiastolic <= 79, { "IN-03: sottosmorzata: S sovrastimata (${sn.artDisplayedSystolic}/${sn.artDisplayedDiastolic})" })
        Cmd(s, "set_art_line", mapOf("damping" to "over")); sn = s.snapshot()
        val mOver = (sn.artDisplayedSystolic + 2 * sn.artDisplayedDiastolic) / 3
        CHECK(sn.artDisplayedSystolic <= 112 && sn.artDisplayedDiastolic >= 84 && mOver in 91..95, { "IN-03: sovrasmorzata: S bassa, D alta, PAM invariata (${sn.artDisplayedSystolic}/${sn.artDisplayedDiastolic})" })
        Cmd(s, "set_art_line", mapOf("damping" to "optimal", "level_cm" to 10)); sn = s.snapshot()
        CHECK(sn.artDisplayedSystolic == 113 && sn.artDisplayedDiastolic == 73, { "IN-03: trasduttore 10 cm sopra = −7 mmHg (${sn.artDisplayedSystolic}/${sn.artDisplayedDiastolic})" })
        Cmd(s, "set_art_line", mapOf("level_cm" to 0, "zero_required" to true))
        CHECK(s.snapshot().artDisplayedSystolic == 120, "IN-03: zero obbligatorio non tocca la linea già in uso")
        Sensor(s, "art", false); Sensor(s, "art", true); sn = s.snapshot()
        CHECK(sn.artNeedsZero && sn.artDisplayedSystolic < 0 && Msgs2(s).contains("ART: AZZERARE") && !Msgs2(s).contains("ART SIST"),
              { "IN-03: ricollegata con zero obbligatorio: «---» e «ART: AZZERARE» (${Msgs2(s)})" })
        Cmd(s, "art_zero"); sn = s.snapshot()
        CHECK(sn.artZeroing && Msgs2(s).contains("ART AZZERAMENTO IN CORSO"), "IN-03: Zero: 3 s di azzeramento")
        val e = WaveformEngine(7); e.updateWithSnapshot(sn)
        CHECK(Math.abs(e.artAt(gNow + 1.0)) < 1.0, "IN-03: durante lo zero la curva è a 0 mmHg")
        Tick(s, 4); sn = s.snapshot()
        CHECK(!sn.artZeroing && !sn.artNeedsZero && sn.artDisplayedSystolic == 120 && sn.artZeroedAtMs > 0, "IN-03: dopo lo zero i numeri tornano")
        // lavaggio rapido: onda quadra (300) e oscillazioni secondo lo smorzamento
        fun flushOsc(damp: String): Int {
            Cmd(s, "set_art_line", mapOf("damping" to damp)); Tick(s, 2)
            val eng = WaveformEngine(3); eng.updateWithSnapshot(s.snapshot())
            Samples(eng, gNow - 2.0, 2.0, 0.004) { eng.artAt(it) }
            Cmd(s, "art_flush"); eng.updateWithSnapshot(s.snapshot())
            val a = Samples(eng, gNow, 1.7, 0.002) { eng.artAt(it) }
            val top = a.take(500).maxOrNull() ?: 0.0
            CHECK(top > 250, { "IN-03: lavaggio ($damp): onda quadra verso 300 mmHg ($top)" })
            // estremi locali nei 0,35 s dopo il rilascio (1,2 s)
            var n = 0; for (i in 601 until 775) if ((a[i] - a[i - 1]) * (a[i + 1] - a[i]) < 0) n++
            return n
        }
        val oOpt = flushOsc("optimal"); val oUnder = flushOsc("under"); val oOver = flushOsc("over")
        CHECK(oUnder > oOpt && oOpt >= 1 && oOver <= 1, { "IN-03/OP-04: oscillazioni dopo il lavaggio: sotto $oUnder > ottimale $oOpt, sovra $oOver" })
        Cmd(s, "set_art_line", mapOf("damping" to "optimal", "occluded" to true)); Tick(s, 4); sn = s.snapshot()
        CHECK(sn.artDisplayedSystolic - sn.artDisplayedDiastolic <= 3 && Msgs2(s).contains("ART: VERIFICARE LINEA"), { "IN-03: linea occlusa: non pulsatile (${sn.artDisplayedSystolic}/${sn.artDisplayedDiastolic})" })
    }
    // ---- IN-13: PVC (curva a/c/v, valore, rampa, zero) e temperatura centrale ----
    run {
        val s = Started(); Sensor(s, "cvp", true); Sensor(s, "temp2", true)
        Cmd(s, "set_cvp", 12); Cmd(s, "set_temperature2", 38.4); Tick(s, 60)
        var sn = s.snapshot()
        CHECK(sn.monCvp == 12 && sn.monTemperature2Tenths == 384, { "IN-13: PVC ${sn.monCvp}, T2 ${sn.monTemperature2Tenths}" })
        val e = WaveformEngine(5); e.updateWithSnapshot(sn)
        val a = Samples(e, gNow, 8.0, 0.01) { e.cvpAt(it) }
        val mean = a.average(); val span = (a.maxOrNull() ?: 0.0) - (a.minOrNull() ?: 0.0)
        CHECK(Math.abs(mean - 12) < 1.5 && span in 2.0..9.0, { "IN-13: curva PVC media ${"%.1f".format(mean)}, escursione ${"%.1f".format(span)}" })
        Cmd(s, "transition", mapOf("params" to mapOf("cvp_mmhg" to 3), "duration_s" to 20)); Tick(s, 40)
        CHECK(s.snapshot().monCvp in 2..4, "IN-13: PVC in rampa (ipovolemia)")
        Cmd(s, "set_cvp", mapOf("mmhg" to 18, "waveform" to "tamponade"))
        CHECK(s.stateDictionary()["cvp_waveform"] == "tamponade", "IN-13: forma della PVC (tamponamento)")
        CHECK(!Cmd(s, "set_cvp", 60), "IN-13: PVC fuori intervallo rifiutata")
    }
    // ---- IN-08: ritardi d'allarme (apnea 20 s, SpO2 5 s) ----
    run {
        val s = Started(); Sensor(s, "etco2", true); TickEval(s, 10)
        Cmd(s, "set_resp_rate", 0); TickEval(s, 10)
        CHECK(!Msgs2(s).contains("APNEA"), { "IN-08: apnea da 10 s: ancora nessun allarme (${Msgs2(s)})" })
        TickEval(s, 12)
        CHECK(Msgs2(s).contains("APNEA"), "IN-08: apnea da 22 s: APNEA")
        Cmd(s, "set_alarm_delays", mapOf("apnea" to 0, "spo2" to 0)); Cmd(s, "set_resp_rate", 16); TickEval(s, 10)
        Cmd(s, "set_resp_rate", 0); TickEval(s, 1.0)
        CHECK(Msgs2(s).contains("APNEA"), "IN-08: ritardo 0 = subito")
        CHECK(!Cmd(s, "set_alarm_delays", mapOf("xx" to 3)), "IN-08: parametro sconosciuto rifiutato")
    }
    // ---- IN-09: limiti ART e NIBP separati, PAM, SpO2 alta, temperatura ----
    run {
        val s = Started(); Sensor(s, "art", true); Sensor(s, "temp", true)
        Cmd(s, "set_bp", mapOf("systolic" to 95, "diastolic" to 50)); Tick(s, 10); RunNibp(s); TickEval(s, 3)
        CHECK(!Msgs2(s).contains("SIST") && !Msgs2(s).contains("PAM"), { "IN-09: 95/50 con i limiti di serie: nessun allarme (${Msgs2(s)})" })
        Cmd(s, "set_alarm_limits", mapOf("art_map_low" to 70, "nibp_sys_low" to 100)); TickEval(s, 2)
        val m = Msgs2(s)
        CHECK(m.contains("ART PAM < 70") && m.contains("NIBP SIST < 100") && !m.contains("ART SIST"), { "IN-09: PAM ART e NIBP separata ($m)" })
        Cmd(s, "set_alarm_limits", mapOf("sys_low" to 80))
        val lim = s.snapshot().alarmLimits
        CHECK(lim["art_sys_low"] == 80 && lim["nibp_sys_low"] == 80, "IN-09: il limite «sys» di prima vale per entrambe")
        Cmd(s, "set_spo2", 100); Cmd(s, "set_alarm_limits", mapOf("spo2_high" to 98, "temp_high" to 375)); Cmd(s, "set_temperature", 38.6); TickEval(s, 60)
        CHECK(Msgs2(s).contains("SpO2 > 98") && Msgs2(s).contains("T1 > 37,5"), { "IN-09: SpO2 alta e temperatura (${Msgs2(s)})" })
        CHECK(!Cmd(s, "set_alarm_limits", mapOf("art_map_low" to 20)) && Cmd(s, "set_alarm_limits", mapOf("art_map_low" to 0)), "IN-09: PAM 20 rifiutata, 0 = spento")
        Cmd(s, "reset_alarm_limits")
        CHECK(s.snapshot().alarmLimits["spo2_high"] == 0, "IN-09: di serie spenti")
    }
    // ---- IN-05/IN-06: allarmi nel registro (inizio, silenzio con tempo di risposta, fine), limiti e allarmi spenti ----
    run {
        val s = NewStateRaw(); BaseSensors(s); s.devicePowered = true
        val lg = SFSessionLog(null, s)
        Cmd(s, "start_scenario"); lg.observe(); Tick(s, 5); lg.observe()
        RhythmNow(s, "vf"); for (i in 0 until 9) { Tick(s, 1); lg.observe() }
        SLCommandSource.with("Regia web") { Cmd(s, "mute_alarms", 120) }; lg.observe()
        RhythmNow(s, "sinus"); for (i in 0 until 12) { Tick(s, 1); lg.observe() }
        Cmd(s, "set_alarm_limits", mapOf("spo2_low" to 85)); Cmd(s, "set_alarm_enabled", mapOf("param" to "rr", "enabled" to false)); lg.observe()
        val en = lg.entriesSince(0)
        val on = en.firstOrNull { it["tag"] == "alarm_on" && HASSTR(it["text"], "FIBRILLAZIONE") }
        val mu = en.firstOrNull { it["tag"] == "alarm_mute" }
        val off = en.firstOrNull { it["tag"] == "alarm_off" && HASSTR(it["text"], "FIBRILLAZIONE") }
        CHECK(on != null && on["k"] == "alarm" && INT(on["lvl"]) == 3, { "IN-06: «Allarme ALTO: FV» nel registro (${en.map { it["text"] }})" })
        CHECK(mu != null && INT(mu["resp_s"]) in 8..11 && mu["by"] == "Regia web", { "IN-06: silenziato con il tempo di risposta e chi ($mu)" })
        CHECK(off != null && INT(off["mute_s"]) in 8..11 && HASSTR(off["text"], "silenziato dopo"), { "IN-06: FV cessata con durata e silenzio ($off)" })
        val lim = en.firstOrNull { it["tag"] == "alarm_limit" }
        CHECK(lim != null && lim["by"] == "partecipanti" && HASSTR(lim["text"], "SpO₂ bassa 90 → 85"), { "IN-06: limite cambiato dai partecipanti ($lim)" })
        CHECK(en.any { it["tag"] == "alarm_disabled" && HASSTR(it["text"], "FR spento") }, "IN-06: allarme spento nel registro")
        val al = lg.summary()["alarms"] as Map<*, *>
        CHECK(INT(al["high"]) >= 1 && INT(al["mean_silence_s"]) in 8..11 && INT(al["limits_changed"]) == 1 && INT(al["alarms_disabled"]) == 1 && COUNT(al["items"]) >= 1,
              { "IN-06: riepilogo degli allarmi per il debriefing ($al)" })
    }
    // ---- CC-04/AE-12/OP-05: paziente ventilato ----
    run {
        val s = Started(); Sensor(s, "etco2", true); Sensor(s, "art", true); Tick(s, 10)
        Cmd(s, "set_ventilation", mapOf("mode" to "ventilator", "rate_rpm" to 14, "tidal_ml" to 500)); TickEval(s, 10)
        var sn = s.snapshot()
        CHECK(sn.respRate == 14 && !sn.ventilationManual && sn.ventMode == "ventilator", "CC-04: ventilatore 14/min")
        // PPV: normovolemia piccola, ipovolemia grande (anche misurata sulla curva)
        fun ppvCurve(): Double {
            val e = WaveformEngine(11); e.updateWithSnapshot(s.snapshot())
            val a = Samples(e, gNow, 15.0, 0.01) { e.artAt(it) }
            // pressione differenziale battito per battito: massimo e minimo in finestre di 0,6 s
            val per = (60.0 / s.snapshot().heartRate / 0.01).toInt()   // un battito per finestra
            val pp = ArrayList<Double>(); var i = 0
            while (i + per <= a.size) { val w = a.copyOfRange(i, i + per); pp.add((w.maxOrNull() ?: 0.0) - (w.minOrNull() ?: 0.0)); i += per }
            val mx = pp.maxOrNull() ?: 0.0; val mn = pp.minOrNull() ?: 0.0
            return 200.0 * (mx - mn) / (mx + mn)
        }
        val ppvN = ppvCurve()
        Cmd(s, "transition", mapOf("params" to mapOf("heart_rate_bpm" to 130, "systolic_mmhg" to 80, "diastolic_mmhg" to 45), "duration_s" to 0)); Tick(s, 20)
        sn = s.snapshot()
        val ppvH = ppvCurve()
        CHECK(sn.ppvShown >= 15 && ppvH > ppvN + 5 && ppvN < 12, { "OP-05: PPV ${sn.ppvShown}% in ipovolemia; curva ${"%.0f".format(ppvN)}% → ${"%.0f".format(ppvH)}%" })
        Cmd(s, "transition", mapOf("params" to mapOf("heart_rate_bpm" to 82, "systolic_mmhg" to 120, "diastolic_mmhg" to 80), "duration_s" to 0)); Tick(s, 10)
        // arresto: il ventilatore continua (10/min), niente APNEA, capnogramma atto per atto
        RhythmNow(s, "vf"); Cmd(s, "set_cpr", true); TickEval(s, 30); sn = s.snapshot()
        CHECK(sn.ventRate == 10 && sn.breathTimes.size >= 4 && !Msgs2(s).contains("APNEA") && sn.respRate in 4..12,
              { "CC-04: in arresto 10/min automatici (${sn.ventRate}, ${sn.breathTimes.size} atti, FR ${sn.respRate}, ${Msgs2(s)})" })
        Cmd(s, "set_cpr", false); TickEval(s, 10)
        CHECK(s.snapshot().respRateSet >= 10 && !Msgs2(s).contains("APNEA"), "CC-04: pausa delle compressioni: il ventilatore continua")
        RhythmNow(s, "sinus"); Tick(s, 20); sn = s.snapshot()
        CHECK(sn.ventRate == 14 && sn.respRate == 14, { "CC-04: al ROSC torna la frequenza di prima (${sn.ventRate})" })
        Cmd(s, "set_ventilation", mapOf("disconnected" to true)); TickEval(s, 25)
        CHECK(Msgs2(s).contains("APNEA"), "CC-04: ventilatore scollegato = APNEA (dopo il ritardo)")
        Cmd(s, "set_ventilation", mapOf("disconnected" to false, "rate_rpm" to 20)); TickEval(s, 10)
        CHECK(!Msgs2(s).contains("APNEA") && s.snapshot().respRate == 20, "CC-04: ricollegato")
        CHECK((s.stateDictionary()["transitions"] as Map<*, *>).containsKey("etco2_mmhg"), "CC-04: più ventilazione = EtCO2 che scende in minuti")
        // pallone: Ventila sempre attivo; con via aerea avanzata in RCP 10/min da soli
        val b = Started(); Sensor(b, "etco2", true)
        Cmd(b, "set_ventilation", mapOf("mode" to "bag")); CHECK(Cmd(b, "ventilate") && b.snapshot().ventilationManual, "AE-12: pallone: Ventila con FR > 0")
        Cmd(b, "set_ventilation", mapOf("advanced_airway" to true)); RhythmNow(b, "vf"); Cmd(b, "set_cpr", true); Tick(b, 30)
        CHECK(b.snapshot().breathTimes.size in 4..6, { "AE-12: via aerea avanzata in RCP: 10/min (${b.snapshot().breathTimes.size} in 30 s)" })
        CHECK(!Cmd(b, "set_ventilation", mapOf("mode" to "jet")), "set_ventilation: modo sconosciuto rifiutato")
    }
    // ---- IN-10: elettrodo singolo, disturbo, inversione dei cavi ----
    run {
        val s = Started(); Tick(s, 5)
        Cmd(s, "set_ecg_electrode", "ra")
        CHECK(Msgs2(s).contains("ECG RA SCOLLEGATO") && s.snapshot().monDisplayedHeartRate > 0, "IN-10: RA staccato: tecnico, la FC resta")
        val e = WaveformEngine(2); e.updateWithSnapshot(s.snapshot())
        val ii = Samples(e, gNow, 3.0, 0.004) { e.ecgAt(it, SLLeadII) }
        CHECK(ii.all { it == 0.0 }, "IN-10: RA staccato: II piatta")
        Cmd(s, "set_ecg_electrode", "none"); Cmd(s, "set_ecg_noise", 2)
        CHECK(Msgs2(s).contains("ECG DISTURBO"), "IN-10: disturbo")
        Cmd(s, "set_ecg_noise", 0); Cmd(s, "set_limb_reversal", true)
        val e1 = WaveformEngine(2); e1.updateWithSnapshot(s.snapshot()); val e0 = WaveformEngine(2)
        Cmd(s, "set_limb_reversal", false); e0.updateWithSnapshot(s.snapshot())
        val a1 = Samples(e1, gNow, 3.0, 0.004) { e1.ecgAt(it, SLLeadII) }; val a0 = Samples(e0, gNow, 3.0, 0.004) { e0.ecgAt(it, SLLeadII) }
        CHECK((a1.maxOrNull() ?: 0.0) < (a0.maxOrNull() ?: 0.0) - 0.1, "IN-10: inversione braccia: II diventa III (R più bassa)")
    }
    // ---- IN-12: risolvere il tecnico col gesto ----
    run {
        val s = Started(); val lg = SFSessionLog(null, s); lg.observe()
        Cmd(s, "set_technical_alarm", mapOf("kind" to "spo2_probe_off", "on" to true)); Tick(s, 40)
        CHECK(!Cmd(s, "resolve_technical", mapOf("kind" to "spo2_probe_off")), "IN-12: non abilitato: il gesto non risolve")
        Cmd(s, "set_tech_resolvable", true)
        CHECK(Cmd(s, "resolve_technical", mapOf("kind" to "spo2_probe_off")) && s.snapshot().technicalAlarms.isEmpty(), "IN-12: abilitato: risolto")
        lg.observe()
        val v = lg.entriesSince(0).firstOrNull { it["tag"] == "tech_resolved" }
        CHECK(v != null && HASSTR(v["text"], "Sonda SpO₂ ricollegata dopo 0:40") && v["by"] == "partecipanti", { "IN-12: nel registro con il tempo ($v)" })
    }
    // ---- IN-13: esami/bilancio della regia nel registro ----
    run {
        val s = Started(); val lg = SFSessionLog(null, s); lg.observe()
        SLCommandSource.with("Regia") { Cmd(s, "set_labs", mapOf("ph" to 7.21, "lactate_mmol" to 4.5, "urine_ml_h" to 15)) }; lg.observe()
        val v = lg.entriesSince(0).firstOrNull { it["tag"] == "labs" }
        CHECK(v != null && HASSTR(v["text"], "pH 7,21") && HASSTR(v["text"], "lattati 4,5 mmol/l") && HASSTR(v["text"], "diuresi 15 ml/h"), { "IN-13: esami ($v)" })
        CHECK(!Cmd(s, "set_labs", mapOf("ph" to 9.0)), "IN-13: pH fuori intervallo rifiutato")
    }
    // ---- IN-19: NIBP automatica, prima misura subito ----
    run {
        val s = Started(); Cmd(s, "set_nibp_interval", 5)
        CHECK(s.snapshot().nibpStatus == SLNibpMeasuring, "IN-19: automatica: prima misura subito")
    }
    // ---- IN-18: testi tecnici ----
    run {
        val s = Started(); Cmd(s, "set_technical_alarm", mapOf("kind" to "nibp_timeout", "on" to true)); RunNibp(s)
        CHECK(Msgs2(s).contains("NIBP TEMPO DI MISURA SUPERATO"), "IN-18: NIBP tempo di misura superato")
    }
    // ---- AE-08: impedenza «marginale»: scarica erogata, impedenza alta nel registro ----
    run {
        val s = Started(); RhythmNow(s, "vf"); Sensor(s, "pads", true); Cmd(s, "defib_mode", "manual"); Cmd(s, "set_pads_contact", "marginal")
        Cmd(s, "defib_energy", 200); Cmd(s, "defib_charge"); Tick(s, 8); Cmd(s, "defib_shock")
        val ev = (s.stateDictionary()["defib"] as Map<*, *>)["events"] as List<*>
        CHECK(s.snapshot().defib.shockCount == 1 && ev.any { HASSTR((it as Map<*, *>)["text"], "impedenza alta") }, "AE-08: scarica erogata con impedenza alta nel registro")
    }
    // ---- AE-15: cattura elettrica senza meccanica su un ritmo con polso = PEA (arresto), meccanica = di nuovo polso ----
    run {
        val s = Started(); Sensor(s, "art", true); Sensor(s, "etco2", true)
        Cmd(s, "set_rhythm", "sinus_bradycardia"); Cmd(s, "set_heart_rate", 34); Tick(s, 10)
        Cmd(s, "set_defib_config", mapOf("mechanical_capture" to false))
        Sensor(s, "pads", true); Cmd(s, "defib_mode", "pacer"); Cmd(s, "defib_pacer", mapOf("rate" to 70, "output_ma" to 90, "demand" to false, "running" to true))
        Tick(s, 40); var sn = s.snapshot()
        CHECK(sn.defib.paceCapture && !sn.pulsePresent && sn.systolic <= 55, { "AE-15: cattura senza meccanica: arresto (PA ${sn.systolic})" })
        Cmd(s, "set_defib_config", mapOf("mechanical_capture" to true)); Tick(s, 120); sn = s.snapshot()
        CHECK(sn.pulsePresent && sn.systolic >= 70, { "AE-15: cattura meccanica: di nuovo polso (PA ${sn.systolic})" })
    }
}
