// ANDROID (audit 26/11): test dei fix dell'audit pre-release (RETE-R04 associazione rapida, e i successivi).
package it.simulafranz.coretests

import it.simulafranz.core.*

private fun PairReq(cid: String, remote: String): SLHTTPRequest {
    val r = Req("POST", "/api/v1/pair", null, "{\"client\":\"$cid\",\"name\":\"Ctl $cid\"}")
    r.remoteAddress = remote
    return r
}

fun testAudit2611() {
    // --- RETE-R04: POST /api/v1/pair solo dal collegamento diretto, al massimo una finestra ogni 60 s ---
    run {
        val s = NewState()
        val api = APIController(s, { "482913" }, emptyMap()); api.clock = { gNow }
        var shown = 0
        api.onPairRequest = { _, _ -> shown++ }
        // dalla LAN (regia web, computer): rifiutata, nessuna finestra
        var r = api.handle(PairReq("aaa", "10.0.0.5"))
        CHECK(r.status == 403 && J(r)["error"] == "pair_direct_only" && shown == 0, { "LAN: 403 pair_direct_only senza finestra (${r.status}, $shown)" })
        r = api.handle(PairReq("aaa", "192.168.4.9"))
        CHECK(r.status == 403 && shown == 0, "altra rete privata: rifiutata")
        // dal collegamento diretto (il server la marca «peer:»): finestra
        r = api.handle(PairReq("aaa", "peer:192.168.49.23"))
        CHECK(r.status == 200 && shown == 1, { "Wi-Fi Direct: accettata con la finestra (${r.status}, $shown)" })
        r = api.handle(PairReq("aaa", "192.168.49.23"))
        CHECK(r.status == 200 && shown == 1, "stessa richiesta ripetuta: nessuna finestra nuova")
        // un altro controller entro 60 s: 429
        gNow += 30
        r = api.handle(PairReq("bbb", "peer:192.168.49.40"))
        CHECK(r.status == 429 && shown == 1, { "secondo controller entro 60 s: 429 (${r.status})" })
        // rifiutata sul monitor: subito dopo nessuna finestra nuova, nemmeno dallo stesso controller
        api.pairAnswer("aaa", false)
        r = api.handle(PairReq("aaa", "peer:192.168.49.23"))
        CHECK(r.status == 429 && shown == 1, { "dopo «Rifiuta» entro 60 s: 429 (${r.status})" })
        // passati i 60 s dall'ultima finestra: di nuovo possibile
        gNow += 31
        r = api.handle(PairReq("bbb", "peer:192.168.49.40"))
        CHECK(r.status == 200 && shown == 2, { "dopo 60 s: nuova finestra (${r.status}, $shown)" })
        // la consegna del PIN resta uguale
        api.pairAnswer("bbb", true)
        val g = Req("GET", "/api/v1/pair", null, null); g.query = "client=bbb"
        val j = J(api.handle(g))
        CHECK(j["status"] == "approved" && j["pin"] == "482913", "PIN consegnato al controller associato")
    }

    // --- RETE-R07: esito di un comando chiesto per id (GET /api/v1/command?id=…) ---
    run {
        val s = NewState()
        val api = APIController(s, { "482913" }, emptyMap()); api.clock = { gNow }
        fun post(id: String): SLHTTPResponse {
            val r = CReq("POST", "/api/v1/command", "{\"command\":\"set_heart_rate\",\"value\":97}", "ctlA", "Ctl A", null)
            r.headers = LinkedHashMap(r.headers).apply { this["x-simulafranz-cmd-id"] = id }
            return api.handle(r)
        }
        fun ask(id: String, cid: String = "ctlA"): Map<String, Any?>? {
            val r = CReq("GET", "/api/v1/command", null, cid, "Ctl", null); r.query = "id=$id"
            return J(api.handle(r))
        }
        CHECK(ask("x-1")["done"] == false, "comando mai arrivato: done falso")
        CHECK(post("x-1").status == 200 && s.snapshot().heartRate == 97, "comando eseguito")
        CHECK(ask("x-1")["done"] == true, "dopo l'esecuzione: done vero")
        CHECK(ask("x-1", "ctlB")["done"] == false, "un altro controller con lo stesso id: done falso (chiave per controller)")
        Cmd(s, "set_heart_rate", 60)
        CHECK(post("x-1").status == 200 && s.snapshot().heartRate == 60, "ripetizione con lo stesso id: non rieseguito")
        val g = Req("GET", "/api/v1/command", null, null); g.query = "id=x-1"
        CHECK(api.handle(g).status == 401, "senza PIN: 401")
        val g2 = CReq("GET", "/api/v1/command", null, "ctlA", "Ctl A", null); g2.query = ""
        CHECK(api.handle(g2).status == 405, "senza id: 405 come prima")
        for (k in 1..300) post("y-$k")
        CHECK(ask("y-1")["done"] == true, "ricordati più di 64 comandi (512)")
    }

    // --- BUG-A6: diastolica < sistolica anche nella validazione delle fasi (salvataggio scenari) ---
    run {
        val s = NewState()
        fun tr(p: Map<String, Any?>): String? = s.validateTransition(mapOf("params" to p))
        CHECK(tr(mapOf("systolic_mmhg" to 60, "diastolic_mmhg" to 80))?.contains("diastolic must be lower") == true, "coppia invertita rifiutata in validazione")
        CHECK(tr(mapOf("systolic_mmhg" to 80, "diastolic_mmhg" to 80)) != null, "coppia uguale rifiutata")
        CHECK(tr(mapOf("systolic_mmhg" to 120, "diastolic_mmhg" to 70)) == null, "coppia giusta accettata")
        CHECK(tr(mapOf("diastolic_mmhg" to 130)) == null, "solo la diastolica: accettata (si controlla quando si applica)")
        CHECK(tr(mapOf("systolic_mmhg" to 50)) == null, "solo la sistolica: accettata")
        val d = TempDir("sf_audit2611_scen"); d.mkdirs()
        val st = SFScenarioStore(d, s)
        val bad = mapOf("name" to "Prova A6", "phases" to listOf(mapOf("name" to "fase", "duration_s" to 10,
            "params" to mapOf("systolic_mmhg" to 60, "diastolic_mmhg" to 80))))
        val r = st.save(bad)
        CHECK(r.scenario == null && r.error?.contains("diastolic must be lower") == true, { "SFScenarioStore: scenario con la coppia invertita non salvato (${r.error})" })
        val good = mapOf("name" to "Prova A6 ok", "phases" to listOf(mapOf("name" to "fase", "duration_s" to 10,
            "params" to mapOf("systolic_mmhg" to 120, "diastolic_mmhg" to 80))))
        CHECK(st.save(good).error == null, "SFScenarioStore: coppia giusta salvata")
    }

    // --- BUG-B2: perdita del polso sul pleth: niente taglio verticale, ultima onda finita, poi zero ---
    run {
        val s = NewState(); Cmd(s, "set_heart_rate", 75)
        val e = WaveformEngine(3); e.updateWithSnapshot(s.snapshot())
        var t = 0.0; val dt = 1.0 / 120
        while (t < 15.0) { e.ecgAt(t, SLLeadII); e.plethAt(t); t += dt }
        CHECK(s.applyCommand(mapOf("command" to "set_rhythm", "value" to "vf", "onset" to "immediate")) == null, "FV immediata")
        val sn = s.snapshot()
        CHECK(!sn.pulsePresent, "FV: senza polso")
        var prev = e.plethAt(t - dt); e.updateWithSnapshot(sn)
        var maxStep = 0.0; var peakAfter = 0.0; val tl = t
        while (t < tl + 4.0) { e.ecgAt(t, SLLeadII); val v = e.plethAt(t); maxStep = Math.max(maxStep, Math.abs(v - prev)); if (t < tl + 1.0) peakAfter = Math.max(peakAfter, v); prev = v; t += dt }
        CHECK(maxStep < 0.1, { F("pleth alla perdita del polso: nessun salto (passo massimo %.3f)", maxStep) })
        CHECK(peakAfter > 0.05, { F("pleth: l'onda in corso continua dopo la perdita (massimo %.3f)", peakAfter) })
        CHECK(e.plethAt(t) < 0.01, { F("pleth: a zero dopo 4 s (%.3f)", e.plethAt(t)) })
    }

    // --- BUG-A7: FC molto bassa → alta: il QRS successivo arriva alla nuova FC, non un vecchio RR dopo ---
    run {
        val s = NewState(); Cmd(s, "set_heart_rate", 10)
        val e = WaveformEngine(7); e.updateWithSnapshot(s.snapshot())
        var t = 0.0
        while (t < 20.0) { e.ecgAt(t, SLLeadII); t += 0.004 }
        val r0 = e.lastBeatAtOrBefore(20.0)
        while (e.lastBeatAtOrBefore(t) <= r0 && t < 40.0) { e.ecgAt(t, SLLeadII); t += 0.004 }
        val r = e.lastBeatAtOrBefore(t)
        while (t < r + 0.5) { e.ecgAt(t, SLLeadII); t += 0.004 }
        Cmd(s, "set_heart_rate", 80); e.updateWithSnapshot(s.snapshot())
        val qs = ArrayList<Double>(); var last = r
        while (t < r + 8.0 && qs.size < 4) { e.ecgAt(t, SLLeadII); val b = e.lastBeatAtOrBefore(t); if (b > last + 0.05) { qs.add(b); last = b }; t += 0.004 }
        CHECK(r > 0 && qs.isNotEmpty() && qs[0] - r < 1.5, { F("FC 10 → 80: primo QRS dopo %.2f s (prima fino a 6 s)", if (qs.isEmpty()) -1.0 else qs[0] - r) })
        CHECK(qs.size >= 4 && (1 until qs.size).all { Math.abs(qs[it] - qs[it - 1] - 0.75) < 0.15 }, { "poi RR della nuova FC (${qs.zipWithNext { a, b -> F("%.2f", b - a) }})" })
        // FC che scende: nessun anticipo (si allunga normalmente al battito dopo)
        Cmd(s, "set_heart_rate", 40); e.updateWithSnapshot(s.snapshot())
        val q2 = ArrayList<Double>(); last = e.lastBeatAtOrBefore(t)
        while (q2.size < 3 && t < r + 30) { e.ecgAt(t, SLLeadII); val b = e.lastBeatAtOrBefore(t); if (b > last + 0.05) { q2.add(b); last = b }; t += 0.004 }
        CHECK(q2.size == 3 && Math.abs(q2[2] - q2[1] - 1.5) < 0.25, { "FC 80 → 40: RR 1,5 s (${q2.zipWithNext { a, b -> F("%.2f", b - a) }})" })
    }

    // --- RETE-R05: archivio, registro e trend con un lucchetto loro (non fermano comandi e stato) ---
    CHECK(SLUsesDiskLock("/api/v1/archive") && SLUsesDiskLock("/api/v1/log") && SLUsesDiskLock("/api/v1/trend"), "archive/log/trend: lucchetto del disco")
    CHECK(!SLUsesDiskLock("/api/v1/command") && !SLUsesDiskLock("/api/v1/state") && !SLUsesDiskLock("/api/v1/scenarios") && !SLUsesDiskLock("/api/v1/control") && !SLUsesDiskLock("/api/v1/pair"),
          "command/state/scenarios/control/pair: lucchetto comune")
}
