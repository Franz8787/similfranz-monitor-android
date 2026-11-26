// ANDROID (difetti clinici evidenti 22/11): un test per ogni difetto corretto dalle revisioni cliniche
// (analisi-fisiologia/specialisti/INFERMIERISTICA.md IN-xx, EMODINAMICA.md EM-xx, ONDE_DI_POLSO.md OP-xx,
// ELETTROFISIOLOGIA.md EF-xx, CARDIOLOGIA_CARDIOCHIRURGIA.md CC-xx). Elenco con le prove: analisi-fisiologia/CORRETTI.md
package it.simulafranz.coretests

import it.simulafranz.core.*

private fun Msgs(s: SimulationState): String = AlarmManager.evaluate(s.snapshot()).messages.joinToString(",")

fun testDifetti2211() {
    // ---- IN-01: sonde mai collegate = parametri spenti, nessun allarme tecnico; staccate dopo l'uso = allarme ----
    run {
        val s = NewStateRaw()                                   // nessun sensore mai collegato
        Tick(s, 2)
        var r = AlarmManager.evaluate(s.snapshot())
        CHECK(r.level == SLAlarmNone && r.messages.isEmpty(), { "IN-01: nessun sensore mai collegato: nessun allarme (${r.messages})" })
        Sensor(s, "ecg", true); Tick(s, 2)
        r = AlarmManager.evaluate(s.snapshot())
        CHECK(!r.messages.any { it.contains("SpO2") }, { "IN-01: solo ECG, SpO2 mai collegata: niente «SONDA SCOLLEGATA» (${r.messages})" })
        CHECK(r.level == SLAlarmNone, "IN-01: solo ECG in sinusale: nessun allarme, nessun tono")
        Sensor(s, "spo2", true); Tick(s, 15); Sensor(s, "spo2", false); Tick(s, 1)
        CHECK(Msgs(s).contains("SpO2 SONDA SCOLLEGATA") && s.snapshot().spo2ProbeOffAlarm, "IN-01: sonda SpO2 usata e poi staccata: allarme tecnico")
        Sensor(s, "ecg", false); Tick(s, 1)
        CHECK(Msgs(s).contains("ECG ELETTRODI SCOLLEGATI"), "IN-01: elettrodi usati e poi staccati: allarme tecnico")
        s.resetScenario(); Tick(s, 1)
        CHECK(AlarmManager.evaluate(s.snapshot()).messages.isEmpty(), "IN-01: reset_scenario: di nuovo nessun sensore in uso, nessun allarme")
        // distrattore dell'istruttore: allarme anche se la sonda non era mai stata messa
        val t = NewStateRaw()
        Cmd(t, "set_technical_alarm", mapOf("kind" to "spo2_probe_off", "on" to true)); Tick(t, 1)
        CHECK(Msgs(t).contains("SpO2 SONDA SCOLLEGATA"), { "IN-01: distrattore «sonda staccata»: allarme anche senza sonda (${Msgs(t)})" })
        // sopravvive al salvataggio/ripresa
        val u = NewState(); Sensor(u, "spo2", false)
        val v = NewStateRaw(); v.restoreState(u.exportState())
        CHECK(v.snapshot().spo2ProbeOffAlarm, "IN-01: dopo la ripresa la sonda risulta ancora «usata e staccata»")
    }
    // ---- IN-02: linea arteriosa staccata dopo l'uso: allarme TECNICO (mai rosso); mai collegata: niente ----
    run {
        val s = NewState()
        Tick(s, 1)
        CHECK(!Msgs(s).contains("ART"), "IN-02: ART mai collegata: nessun allarme")
        Sensor(s, "art", true); Tick(s, 5); Sensor(s, "art", false); Tick(s, 1)
        val r = AlarmManager.evaluate(s.snapshot())
        CHECK(r.messages.contains("ART TRASDUTTORE SCOLLEGATO") && r.level == SLAlarmTechnical && (r.severity["art"] ?: 0) == 0,
              { "IN-02: ART staccata: solo tecnico «ART TRASDUTTORE SCOLLEGATO», nessun allarme clinico (${r.messages}, livello ${r.level})" })
        Sensor(s, "art", true); Tick(s, 1)
        CHECK(!Msgs(s).contains("ART TRASDUTTORE"), "IN-02: ricollegata: allarme sparito")
    }
    // ---- IN-04: un NUOVO allarme letale interrompe la pausa audio; quelli già presenti restano silenziati ----
    run {
        val s = NewState(); val g = SLAlarmPauseGuard()
        Cmd(s, "set_sensor", mapOf("sensor" to "etco2", "connected" to true))
        Tick(s, 2); Cmd(s, "set_spo2", 82); Tick(s, 30)
        CHECK(!g.update(s.snapshot().alarmsMuted, AlarmManager.evaluate(s.snapshot())), "IN-04: non silenziato: niente da interrompere")
        Cmd(s, "mute_alarms", 120); Tick(s, 1)
        CHECK(!g.update(true, AlarmManager.evaluate(s.snapshot())), "IN-04: silenzio della desaturazione: pausa attiva")
        RhythmNow(s, "vf"); Tick(s, 1)
        CHECK(g.update(true, AlarmManager.evaluate(s.snapshot())), "IN-04: FV durante la pausa: la pausa si interrompe (suona)")
        // silenziare DURANTE la FV: resta silenziata finché non compare un allarme letale nuovo
        val g2 = SLAlarmPauseGuard()
        g2.update(true, AlarmManager.evaluate(s.snapshot())); Tick(s, 5)
        CHECK(!g2.update(true, AlarmManager.evaluate(s.snapshot())), "IN-04: FV già presente quando si silenzia: resta silenziata")
        RhythmNow(s, "asystole"); Tick(s, 1)
        CHECK(g2.update(true, AlarmManager.evaluate(s.snapshot())), "IN-04: FV → asistolia durante la pausa: nuovo allarme, suona")
    }
    // ---- IN-07: movimento marcato = artefatto, non bassa perfusione ----
    run {
        val s = NewState(); Tick(s, 20)
        Cmd(s, "set_patient_motion", "strong"); Tick(s, 1)
        val m = Msgs(s)
        CHECK(m.contains("SpO2 ARTEFATTO DA MOVIMENTO") && !m.contains("BASSA PERFUSIONE"), { "IN-07: movimento: «ARTEFATTO DA MOVIMENTO» ($m)" })
        Cmd(s, "set_patient_motion", "off"); Cmd(s, "set_bp", mapOf("systolic" to 70, "diastolic" to 40)); Tick(s, 25)
        CHECK(Msgs(s).contains("SpO2 BASSA PERFUSIONE"), { "IN-07: la bassa perfusione vera (PA 70/40) resta «bassa perfusione» (${Msgs(s)})" })
    }
    // ---- IN-11: monitor-defibrillatore con le sole piastre: allarme del ritmo, non «elettrodi staccati» ----
    run {
        val i = NewStateRaw(); Sensor(i, "pads", true); RhythmNow(i, "vf"); Tick(i, 2)   // ICU: le piastre del cassetto non arrivano al monitor
        CHECK(!Msgs(i).contains("FIBRILLAZIONE"), "IN-11: ICU senza elettrodi: il monitor da letto non vede le piastre")
    }
    // ---- EM-05: al ROSC SpO2 in ricerca qualche secondo, poi un valore plausibile (mai 0 %) ----
    run {
        val s = NewState(); Tick(s, 20)
        RhythmNow(s, "vf"); Tick(s, 60)
        CHECK(s.snapshot().spo2 < 30, "EM-05: in arresto la SpO2 vera crolla (premessa)")
        RhythmNow(s, "sinus"); Tick(s, 1)
        var sn = s.snapshot()
        CHECK(sn.spo2Searching && !sn.spo2Displayable, "EM-05: al ROSC il saturimetro cerca il polso («---»)")
        CHECK(!Msgs(s).contains("SpO2 <"), { "EM-05: durante la ricerca nessun allarme SpO2 rosso (${Msgs(s)})" })
        Tick(s, 9); sn = s.snapshot()
        CHECK(sn.spo2Displayable && sn.spo2 >= 75 && sn.monSpo2 >= 70, { "EM-05: dopo la ricerca SpO2 plausibile (${sn.spo2} / schermo ${sn.monSpo2})" })
    }
    // ---- EM-06: fase di ROSC dello scenario con EtCO2: vale il valore della fase (con il picco iniziale) ----
    run {
        val s = NewState(); Cmd(s, "set_etco2", 36); Tick(s, 5)
        RhythmNow(s, "vf"); Tick(s, 30)
        Cmd(s, "transition", mapOf("rhythm" to "sinus", "onset" to "immediate", "duration_s" to 20, "params" to mapOf("etco2_mmhg" to 44)))
        Tick(s, 6)
        CHECK(s.snapshot().etco2 >= 50, { "EM-06: picco da wash-out al ROSC (${s.snapshot().etco2})" })
        Tick(s, 60)
        CHECK(s.snapshot().etco2 == 44, { "EM-06: dopo 1 min l'EtCO2 è quella della fase, 44 (${s.snapshot().etco2})" })
    }
    // ---- EM-07: asistolia + pacing con cattura meccanica = paziente con polso (PA, SpO2, EtCO2 di ROSC) ----
    run {
        val s = NewState(); Sensor(s, "etco2", true); Sensor(s, "art", true); Tick(s, 10)
        RhythmNow(s, "asystole"); Tick(s, 40)
        CHECK(s.snapshot().systolic <= 50, "EM-07: asistolia: PA d'arresto (premessa)")
        Sensor(s, "pads", true); Cmd(s, "defib_mode", "pacer"); Cmd(s, "defib_pacer", mapOf("rate" to 70, "output_ma" to 90, "demand" to false, "running" to true))
        Tick(s, 2)
        var sn = s.snapshot()
        CHECK(sn.defib.paceCapture && sn.pulsePresent, { "EM-07: cattura meccanica: polso (${sn.defib.message})" })
        Tick(s, 120); sn = s.snapshot()
        CHECK(sn.systolic >= 80 && sn.spo2 >= 85 && sn.etco2 >= 25 && !sn.ventilationManual,
              { "EM-07: con la cattura PA ${sn.systolic}, SpO2 ${sn.spo2}, EtCO2 ${sn.etco2} da paziente con polso" })
        Cmd(s, "defib_pacer", mapOf("running" to false)); Tick(s, 30); sn = s.snapshot()
        CHECK(!sn.pulsePresent && sn.systolic <= 50, { "EM-07: perdita della cattura = di nuovo arresto (PA ${sn.systolic})" })
    }
    // ---- CC-05: bradicardia ipotesa + cattura meccanica a frequenza più alta: la PA migliora; cardioversione efficace idem ----
    run {
        val s = NewState(); Cmd(s, "set_rhythm", "sinus_bradycardia"); Cmd(s, "set_heart_rate", 38); Cmd(s, "set_bp", mapOf("systolic" to 78, "diastolic" to 45)); Tick(s, 15)
        Sensor(s, "pads", true); Cmd(s, "defib_mode", "pacer"); Cmd(s, "defib_pacer", mapOf("rate" to 70, "output_ma" to 90, "demand" to false, "running" to true))
        Tick(s, 20)
        CHECK(s.snapshot().systolic >= 95, { "CC-05: pacing con cattura a 70 su bradicardia a 38: PA sale (${s.snapshot().systolic})" })
        Cmd(s, "defib_pacer", mapOf("running" to false)); Tick(s, 15)
        CHECK(s.snapshot().systolic <= 80, { "CC-05: fine della cattura: PA di prima (${s.snapshot().systolic})" })
        val c = NewState(); Cmd(c, "set_defib_config", mapOf("outcome_mode" to "auto"))
        RhythmNow(c, "afib"); Cmd(c, "set_heart_rate", 165); Cmd(c, "set_bp", mapOf("systolic" to 86, "diastolic" to 56)); Tick(c, 5)
        Sensor(c, "pads", true); Cmd(c, "defib_mode", "manual"); Cmd(c, "defib_energy", 120); Cmd(c, "defib_sync", true)
        Cmd(c, "defib_charge"); Tick(c, 6); Cmd(c, "defib_shock"); c.defibRWaveAt(gNow + 0.2); Tick(c, 35)
        CHECK(c.snapshot().rhythm == "sinus" && c.snapshot().systolic >= 100, { "CC-05: cardioversione efficace: PA migliora (${c.snapshot().systolic})" })
        val e = Err()
        CHECK(Cmd(c, "set_defib_config", mapOf("capture_params" to mapOf("systolic_mmhg" to 110, "diastolic_mmhg" to 70)), e), { "CC-05: capture_params accettati ($e)" })
        CHECK(!Cmd(c, "set_defib_config", mapOf("capture_params" to mapOf("heart_rate_bpm" to 80)), Err()), "CC-05: parametro non ammesso rifiutato")
    }
    // ---- CC-06: rosc_params: al ROSC il paziente va dove dice l'istruttore (causa rimossa), non ai valori di prima ----
    run {
        val s = NewState(); Cmd(s, "set_bp", mapOf("systolic" to 72, "diastolic" to 62)); Tick(s, 5)
        RhythmNow(s, "pea"); Tick(s, 30)
        Cmd(s, "set_defib_config", mapOf("rosc_params" to mapOf("systolic_mmhg" to 118, "diastolic_mmhg" to 72)))
        RhythmNow(s, "sinus_tachycardia"); Tick(s, 130)
        CHECK(s.snapshot().systolic == 118 && s.snapshot().diastolic == 72, { "CC-06: ROSC verso rosc_params 118/72 (${s.snapshot().systolic}/${s.snapshot().diastolic})" })
    }
    // ---- CC-02: al ROSC la ART a schermo non rimostra la PA di prima dell'arresto ----
    run {
        val s = NewState(); Sensor(s, "art", true); Cmd(s, "set_bp", mapOf("systolic" to 130, "diastolic" to 80))
        for (i in 0 until 130) { gNow += 0.25; s.tickAtTime(gNow); s.snapshot() }   // buffer della media pieno e «girato»
        RhythmNow(s, "vf")
        for (i in 0 until 240) { gNow += 0.25; s.tickAtTime(gNow); s.snapshot() }
        RhythmNow(s, "sinus")
        var mx = 0
        for (i in 0 until 16) { gNow += 0.25; s.tickAtTime(gNow); val sn = s.snapshot(); if (sn.pulsePresent && sn.monArtSystolic > mx) mx = sn.monArtSystolic }
        CHECK(mx in 1..90, { "CC-02: primi 4 s dopo il ROSC: ART a schermo bassa (max $mx), non 130 di prima" })
    }
    // ---- EF-04: dopo una scarica efficace pausa elettrica e complessi lenti che accelerano ----
    run {
        val s = NewState(); Cmd(s, "set_defib_config", mapOf("outcome_mode" to "auto"))
        Sensor(s, "pads", true); Cmd(s, "defib_mode", "manual"); RhythmNow(s, "vf"); Tick(s, 5)
        Cmd(s, "defib_charge"); Tick(s, 6); Cmd(s, "defib_shock"); Tick(s, 0.5)
        val sn = s.stateDictionary()
        CHECK(s.snapshot().rhythm == "vf" && sn["rhythm_onset"]["variant"] == "rosc" && sn["rhythm_onset"]["to"] == "sinus",
              { "EF-04: subito dopo la scarica: preludio ROSC (pausa) in corso (${sn["rhythm_onset"]})" })
        Tick(s, 7)
        CHECK(s.snapshot().rhythm == "sinus" && s.snapshot().heartRate < 70, { "EF-04: poi sinusale con complessi lenti (FC ${s.snapshot().heartRate})" })
        Tick(s, 20)
        CHECK(s.snapshot().heartRate >= 75, { "EF-04: che accelerano (FC ${s.snapshot().heartRate})" })
        val t = NewState(); Cmd(t, "set_defib_config", mapOf("outcome_mode" to "auto", "post_shock_onset" to "immediate"))
        Sensor(t, "pads", true); Cmd(t, "defib_mode", "manual"); RhythmNow(t, "vf"); Cmd(t, "defib_charge"); Tick(t, 6); Cmd(t, "defib_shock")
        CHECK(t.snapshot().rhythm == "sinus", "EF-04: post_shock_onset immediate = come prima")
    }
    // ---- AE-02 / AE-05: demand senza elettrodi e messaggio della PAUSA ----
    run {
        for ((lay, stops) in listOf("extrah" to false, "generic_icu" to false)) {
            val s = NewState(); Cmd(s, "set_layout", lay); RhythmNow(s, "sinus_bradycardia"); Cmd(s, "set_heart_rate", 34)
            Sensor(s, "pads", true); Cmd(s, "defib_mode", "pacer"); Cmd(s, "defib_pacer", mapOf("rate" to 70, "output_ma" to 90, "demand" to true, "running" to true)); Tick(s, 2)
            Sensor(s, "ecg", false); Tick(s, 2)
            val d = s.snapshot().defib
            if (stops) CHECK(!d.paceRunning, { "AE-02: $lay: elettrodi staccati in demand: pacing fermato (${d.message})" })
            else CHECK(d.paceRunning && HASSTR(d.message, "NON-DEMAND"), { "AE-02: $lay: elettrodi staccati in demand: stimolazione fissa con messaggio (${d.message})" })
        }
        val s = NewState(); RhythmNow(s, "sinus_bradycardia"); Cmd(s, "set_heart_rate", 34)
        Sensor(s, "pads", true); Cmd(s, "defib_mode", "pacer"); Cmd(s, "defib_pacer", mapOf("rate" to 70, "output_ma" to 90, "demand" to true, "running" to true, "paused" to true)); Tick(s, 1)
        CHECK(HASSTR(s.snapshot().defib.message, "PAUSA"), { "AE-05: in PAUSA il messaggio della pausa (${s.snapshot().defib.message})" })
    }
    // ---- AE-03: soglia meccanica più alta (facoltativa) ----
    run {
        val s = NewState(); RhythmNow(s, "asystole"); Sensor(s, "pads", true)
        Cmd(s, "set_defib_config", mapOf("mechanical_threshold_ma" to 80))
        Cmd(s, "defib_mode", "pacer"); Cmd(s, "defib_pacer", mapOf("rate" to 70, "output_ma" to 75, "demand" to false, "running" to true)); Tick(s, 2)
        var d = s.snapshot().defib
        CHECK(d.paceCapture && !d.paceMechanical && !s.snapshot().pulsePresent, "AE-03: tra le soglie: cattura elettrica senza polso")
        Cmd(s, "defib_pacer", mapOf("output_ma" to 85)); Tick(s, 2); d = s.snapshot().defib
        CHECK(d.paceCapture && d.paceMechanical && s.snapshot().pulsePresent, "AE-03: sopra la soglia meccanica: polso")
    }
    // ---- EM-02 / AE-10 / AE-14: CO2 in apnea con polso, intubazione esofagea, arresto asfittico ----
    run {
        val s = NewState(); Sensor(s, "etco2", true); Cmd(s, "set_etco2", 50); Cmd(s, "set_resp_rate", 0); Tick(s, 90)
        Cmd(s, "ventilate"); Tick(s, 3)
        val first = s.snapshot().etco2
        CHECK(first >= 60, { "EM-02: apnea di 90 s: la prima insufflazione porta CO2 ALTA ($first)" })
        for (i in 0 until 30) { Cmd(s, "ventilate"); Tick(s, 6) }
        CHECK(s.snapshot().etco2 < first - 10, { "EM-02: ventilando bene l'EtCO2 scende (${s.snapshot().etco2} da $first)" })
        val e = NewState(); Sensor(e, "etco2", true); Cmd(e, "set_resp_rate", 0); Cmd(e, "set_etco2", 0); Tick(e, 5)
        Cmd(e, "ventilate"); Tick(e, 3)
        CHECK(e.snapshot().etco2 == 0 && (e.snapshot().breathCo2.all { it == 0.0 }), { "AE-10: EtCO2 0 (tubo in esofago): insufflazioni piatte (${e.snapshot().etco2})" })
        val a = NewState(); Sensor(a, "etco2", true); Cmd(a, "set_etco2", 50); Cmd(a, "set_resp_rate", 0); Tick(a, 180)
        RhythmNow(a, "pea"); Cmd(a, "set_cpr", true); Tick(a, 40); Cmd(a, "ventilate"); Tick(a, 3)
        CHECK(a.snapshot().etco2 >= 35, { "AE-14: arresto asfittico: prime ventilazioni in RCP con EtCO2 alta (${a.snapshot().etco2})" })
        val f = NewState(); Sensor(f, "etco2", true); Tick(f, 5)
        RhythmNow(f, "vf"); Cmd(f, "set_cpr", true); Tick(f, 40); Cmd(f, "ventilate"); Tick(f, 3)
        CHECK(f.snapshot().etco2 < 25, { "AE-14: arresto cardiaco primario: EtCO2 in RCP più bassa (${f.snapshot().etco2})" })
    }
    // ---- AE-11: SpO2 rialzata dall'istruttore in apnea: tiene ----
    run {
        val s = NewState(); Cmd(s, "set_resp_rate", 0); Tick(s, 120)
        val low = s.snapshot().spo2
        Cmd(s, "transition", mapOf("params" to mapOf("spo2_percent" to 95), "duration_s" to 30)); Tick(s, 32)
        CHECK(s.snapshot().spo2 >= 93, { "AE-11: a fine rampa la SpO2 resta quella dell'istruttore (${s.snapshot().spo2}, prima $low)" })
        Tick(s, 15)
        CHECK(s.snapshot().spo2 >= 93, { "AE-11: e non ricade di colpo (${s.snapshot().spo2})" })
    }
    // ---- AE-13: EtCO2 dopo un cambio di FR: equilibrio in minuti ----
    run {
        val s = NewState(); Cmd(s, "set_etco2", 36); Tick(s, 2); Cmd(s, "set_resp_rate", 25); Tick(s, 40)
        CHECK(s.snapshot().etco2 >= 28, { "AE-13: dopo 40 s di iperventilazione l'EtCO2 è ancora in discesa (${s.snapshot().etco2})" })
        Tick(s, 150)
        CHECK(s.snapshot().etco2 <= 25, { "AE-13: dopo ~3 min al nuovo equilibrio (${s.snapshot().etco2})" })
    }
    // ---- EF-01 / EF-02 / EF-12-01: pacing con cattura (BAV III): ogni spike cattura, QRS DOPO lo spike, carta 12D aggiornata ----
    run {
        val s = NewState(); RhythmNow(s, "sinus"); Cmd(s, "set_ecg_morphology", mapOf("av_block" to "third", "escape" to "ventricular")); Tick(s, 3)
        Sensor(s, "pads", true); Cmd(s, "defib_mode", "pacer"); Cmd(s, "defib_pacer", mapOf("rate" to 70, "output_ma" to 90, "demand" to false, "running" to true))
        val e = WaveformEngine(31); e.updateWithSnapshot(s.snapshot())
        var tt = gNow; val end = gNow + 24.0; var k = 0
        while (tt < end) { e.ecgAt(tt, SLLeadII); e.ecgAt(tt, SLLeadV); e.plethAt(tt); tt += 0.004; k++; if (k % 125 == 0) { gNow = tt; s.tickAtTime(gNow); e.updateWithSnapshot(s.snapshot()) } }
        gNow = end
        val c = e.capture12()
        val ii = c.leads[1]
        fun v(at: Double): Double { val i = (at * c.fs).toInt(); return if (i in ii.indices) ii[i].toDouble() else 0.0 }   // paceAt: secondi dall'inizio della carta
        val sp = c.paceAt.filter { it > 0.2 && it < c.seconds - 0.6 }
        CHECK(sp.size >= 8, { "EF-12-01: carta 12D durante il pacing: spike nella carta (${sp.size}, FC ${c.hr})" })
        var before = 0.0; var missed = 0
        for (p in sp) {
            before = Math.max(before, Math.abs(v(p - 0.02) - v(p - 0.07)))
            var mx = 0.0; var x = p + 0.01; while (x < p + 0.12) { mx = Math.max(mx, Math.abs(v(x) - v(p - 0.07))); x += 0.004 }
            if (mx < 0.5) missed++
        }
        CHECK(before < 0.08, { F("EF-01: prima dello spike nessuna deflessione del QRS stimolato (max %.2f mV)", before) })
        CHECK(missed == 0, { "EF-02: BAV III + pacing sopra soglia: ogni spike cattura (${missed} su ${sp.size} senza QRS)" })
    }
    // ---- EF-03: PEA stretta per causa meccanica (da tachicardia), larga per le metaboliche ----
    run {
        val s = NewState(); RhythmNow(s, "sinus_tachycardia"); Cmd(s, "set_heart_rate", 135); Tick(s, 2)
        RhythmNow(s, "pea"); Tick(s, 1)
        CHECK(s.snapshot().peaNarrow && s.snapshot().heartRate >= 120, { "EF-03: PEA da tachicardia: complessi stretti e rapidi (FC ${s.snapshot().heartRate})" })
        val e = WaveformEngine(5); e.updateWithSnapshot(s.snapshot())
        var tt = gNow; val end = gNow + 12.0; var k = 0
        while (tt < end) { e.ecgAt(tt, SLLeadII); tt += 0.004; k++; if (k % 125 == 0) { gNow = tt; s.tickAtTime(gNow); e.updateWithSnapshot(s.snapshot()) } }
        gNow = end
        val rd = e.capture12().reading
        CHECK(HASSTR(rd.headline, "inusale") || rd.lines.any { it.contains("inusale") }, { "EF-03: la 12D descrive il ritmo che vede (${rd.headline} ${rd.lines})" })
        val w = NewState(); Tick(w, 2); RhythmNow(w, "pea"); Tick(w, 1)
        CHECK(!w.snapshot().peaNarrow, "EF-03: PEA da ritmo normale/lento: larga (metabolica)")
    }
    // ---- OP-02: capnogramma a FR bassa: salita e discesa brevi, plateau lungo (non obliquo) ----
    run {
        val s = NewState(); Sensor(s, "etco2", true); Cmd(s, "set_resp_rate", 6); Cmd(s, "set_etco2", 60); Tick(s, 2)
        val e = WaveformEngine(3); e.updateWithSnapshot(s.snapshot())
        val t0 = gNow; var rise0 = -1.0; var rise1 = -1.0; var fall0 = -1.0; var fall1 = -1.0; var prev = 0.0; var mx = 0.0
        var tt = t0; while (tt < t0 + 10.0) { val c = e.capnoAt(tt); mx = Math.max(mx, c); tt += 0.002 }
        tt = t0
        while (tt < t0 + 10.0) {
            val c = e.capnoAt(tt)
            if (rise0 < 0 && prev < 0.1 * mx && c >= 0.1 * mx) rise0 = tt
            if (rise0 > 0 && rise1 < 0 && c >= 0.8 * mx) rise1 = tt
            if (rise1 > 0 && fall0 < 0 && prev >= 0.9 * mx && c < 0.9 * mx) fall0 = tt
            if (fall0 > 0 && fall1 < 0 && c < 0.1 * mx) { fall1 = tt; break }
            prev = c; tt += 0.002
        }
        CHECK(rise1 - rise0 in 0.0..0.35 && fall1 - fall0 in 0.0..0.25 && fall0 - rise1 > 4.0,
              { F("OP-02: FR 6: salita %.2f s, discesa %.2f s, plateau %.1f s (fisiologici)", rise1 - rise0, fall1 - fall0, fall0 - rise1) })
    }
    // ---- Perfusione dalla PAM (richiesta utente, AE-16/OP-12): onda più piccola, «bassa perfusione», poi «---», e ritorno ----
    run {
        val s = NewState(); Tick(s, 20)
        fun bp(a: Int, b: Int) { Cmd(s, "set_bp", mapOf("systolic" to a, "diastolic" to b)) }
        var sn = s.snapshot()
        CHECK(sn.perfusionFraction > 0.95 && !sn.lowPerfusion && sn.spo2Displayable, "perfusione: 120/80 piena")
        bp(80, 45); Tick(s, 30); sn = s.snapshot()
        CHECK(sn.perfusionFraction in 0.3..0.55 && sn.spo2Displayable && sn.perfusionIndex < 2.5, { F("perfusione: 80/45 (PAM 57) onda ridotta (%.2f, PI %.1f)", sn.perfusionFraction, sn.perfusionIndex) })
        bp(60, 30); Tick(s, 4); sn = s.snapshot()
        CHECK(sn.lowPerfusion && sn.spo2Displayable && Msgs(s).contains("BASSA PERFUSIONE"), { "perfusione: 60/30 nei primi secondi: «bassa perfusione», numero ancora presente (${Msgs(s)})" })
        Tick(s, 30); sn = s.snapshot()
        CHECK(sn.spo2WeakSignal && !sn.spo2Displayable && Msgs(s).contains("SpO2 SEGNALE DEBOLE") && !Msgs(s).contains("SpO2 <"),
              { "perfusione: PAM 40 per >8 s: «---», «segnale debole», nessun allarme di limite (${Msgs(s)})" })
        // forma: onda piccola ma regolare (ampiezza tra battiti entro ±10%)
        bp(72, 40); Tick(s, 30)
        val e = WaveformEngine(7); e.updateWithSnapshot(s.snapshot())
        val tS = gNow; var tt = gNow; val end = gNow + 20.0; var k = 0; val pk = ArrayList<Double>(); var cur = 0.0; var last = e.lastBeatAtOrBefore(tt)
        var lo = 9.0
        while (tt < end) {
            val v = e.plethAt(tt); cur = Math.max(cur, v); lo = Math.min(lo, v)
            val b = e.lastBeatAtOrBefore(tt); if (b > last + 0.2) { if (tt > tS + 3) pk.add(cur - lo); cur = 0.0; lo = 9.0; last = b }
            tt += 0.004; k++; if (k % 125 == 0) { gNow = tt; s.tickAtTime(gNow); e.updateWithSnapshot(s.snapshot()) }
        }
        gNow = end
        val ratio = if (pk.isEmpty()) 0.0 else pk.minOrNull()!! / pk.maxOrNull()!!
        CHECK(pk.size >= 10 && ratio > 0.70, { F("AE-16: PA 72/40: picchi del pleth regolari (min/max %.2f su %d battiti)", ratio, pk.size) })
        bp(120, 80); Tick(s, 40); sn = s.snapshot()
        CHECK(!sn.spo2WeakSignal && sn.spo2Displayable && sn.perfusionFraction > 0.9 && !sn.lowPerfusion, "perfusione: PA di nuovo 120/80: tutto torna")
        // ampiezza dell'onda: 80/45 visibilmente più piccola che a 120/80
        fun ampAt(a: Int, b: Int): Double {
            bp(a, b); Tick(s, 30)
            val e2 = WaveformEngine(8); e2.updateWithSnapshot(s.snapshot())
            var t2 = gNow; var mx2 = -9.0; var mn2 = 9.0; var j = 0
            while (t2 < gNow + 8.0) { val v = e2.plethAt(t2); if (t2 > gNow + 2) { mx2 = Math.max(mx2, v); mn2 = Math.min(mn2, v) }; t2 += 0.004; j++; if (j % 125 == 0) e2.updateWithSnapshot(s.snapshot()) }
            gNow = t2
            return mx2 - mn2
        }
        val aN = ampAt(120, 80); val aL = ampAt(80, 45)
        CHECK(aL < aN * 0.6, { F("perfusione: ampiezza del pleth a 80/45 = %.2f contro %.2f a 120/80", aL, aN) })
        val f = NewState(); Cmd(f, "set_spo2_low_perfusion", true); Tick(f, 2)
        CHECK(f.snapshot().lowPerfusion && f.snapshot().perfusionFraction <= 0.35, "perfusione: l'opzione manuale «bassa perfusione» ha la precedenza")
    }
    // ---- AE-09: SYNC: SHOCK rilasciato prima dell'onda R = scarica non erogata (la carica resta) ----
    run {
        val s = NewState(); RhythmNow(s, "afib"); Sensor(s, "pads", true); Cmd(s, "defib_mode", "manual"); Cmd(s, "defib_energy", 120); Cmd(s, "defib_sync", true)
        Cmd(s, "defib_charge"); Tick(s, 6); Cmd(s, "defib_shock")
        CHECK(s.snapshot().defib.syncPending, "AE-09: SYNC: scarica in attesa dell'onda R (premessa)")
        Cmd(s, "defib_shock_release")
        val d = s.snapshot().defib
        CHECK(!d.syncPending && d.shockCount == 0 && d.charge == SLChargeReady, "AE-09: rilasciato prima della R: niente scarica, carica pronta")
        s.defibRWaveAt(gNow + 0.2)
        CHECK(s.snapshot().defib.shockCount == 0, "AE-09: l'onda R dopo il rilascio non scarica")
    }
}

/** ANDROID (EF-04): lascia finire la pausa elettrica e i primi complessi lenti dopo una scarica efficace (preludio «rosc» /
 *  «termination»); ritorna true per stare dentro un CHECK. */
fun PS(s: SimulationState, sec: Double = 7.0): Boolean { Tick(s, sec); return true }
