// ANDROID (fix analisi 22/11): test dei fix dell'analisi dei bug (analisi-bug/PIANO_TEST A-03, A-04 e area F3).
//   BUG-09: defib_outcome solo con una scarica in attesa di decisione (un secondo esito è rifiutato)
//   BUG-11: 503 «troppe connessioni» con corpo JSON e Retry-After (la richiesta non è stata eseguita: si ritenta)
//   F3:     un'eccezione nell'handler HTTP lascia una riga nel log (prima: 500 muto)
package it.simulafranz.coretests

import it.simulafranz.core.*
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket

private fun rawGet(port: Int, path: String, busy: Boolean = false): String {
    Socket().use { s ->
        s.connect(InetSocketAddress("127.0.0.1", port), 2000); s.soTimeout = 3000
        // il 503 arriva subito e la connessione si chiude: si legge quello che è arrivato anche se poi c'è un reset
        if (!busy) try { s.getOutputStream().write("GET $path HTTP/1.1\r\nHost: x\r\nConnection: close\r\n\r\n".toByteArray(Charsets.US_ASCII)) } catch (e: Exception) {}
        val out = java.io.ByteArrayOutputStream(); val b = ByteArray(4096)
        try { val ins = s.getInputStream(); while (true) { val n = ins.read(b); if (n < 0) break; out.write(b, 0, n) } } catch (e: Exception) {}
        return out.toString("UTF-8")
    }
}
private fun freePort(): Int = ServerSocket(0).use { it.localPort }

fun testFixAnalisi2211() {
    // --- BUG-09 (A-03): esito della scarica
    val s = NewState(); val err = Err()
    Cmd(s, "set_defib_config", mapOf("outcome_mode" to "instructor"))
    Cmd(s, "defib_mode", "manual"); Sensor(s, "pads", true); RhythmNow(s, "vf")
    CHECK(!Cmd(s, "defib_outcome", "sinus", err) && err.has("no shock awaiting") && s.snapshot().rhythm == "vf",
          "BUG-09: esito senza scarica in attesa rifiutato, il ritmo resta FV")
    Cmd(s, "defib_charge"); Tick(s, 5); Cmd(s, "defib_shock")
    CHECK(BOOL(s.stateDictionary()["defib"]["outcome_pending"]), "BUG-09: dopo la scarica l'esito è in attesa")
    CHECK(Cmd(s, "defib_outcome", "sinus_bradycardia", err) && PS(s) && s.snapshot().rhythm == "sinus_bradycardia", "BUG-09: primo esito eseguito")
    val e2 = Err()
    CHECK(!Cmd(s, "defib_outcome", "stay", e2) && e2.has("no shock awaiting") && s.snapshot().rhythm == "sinus_bradycardia",
          { "BUG-09: secondo esito (doppio invio) rifiutato, resta il primo (" + e2 + ")" })
    // annullare l'avanzamento automatico rimette la domanda: lì l'esito torna valido (come in testFunzioni2111)
    val s2 = NewState()
    Cmd(s2, "set_defib_config", mapOf("auto_advance" to true))
    Sensor(s2, "pads", true); Cmd(s2, "defib_mode", "manual"); RhythmNow(s2, "afib"); Cmd(s2, "defib_energy", 100); Cmd(s2, "defib_sync", true)
    Cmd(s2, "defib_charge"); Tick(s2, 5); Cmd(s2, "defib_shock"); s2.defibRWaveAt(gNow + 0.2)
    CHECK(!Cmd(s2, "defib_outcome", "stay", Err()), "BUG-09: avanzamento automatico già applicato: niente esito in attesa")
    Tick(s2, 1)
    CHECK(Cmd(s2, "undo_auto_action", null, err) && Cmd(s2, "defib_outcome", "stay", err) && s2.snapshot().rhythm == "afib",
          { "BUG-09: dopo «Annulla» dell'automatico l'esito è di nuovo accettato ($err)" })

    // --- BUG-11 (A-04): 503 con JSON quando i posti sono finiti
    val port = freePort()
    val logged = ArrayList<String>(); val oldSink = SFLog.sink
    SFLog.sink = { l -> synchronized(logged) { logged.add(l) } }
    var boom = false
    val srv = LocalHTTPServer(port) { rq ->
        if (boom && rq.path == "/boom") throw IllegalStateException("prova F3")
        SLHTTPResponse.status(200, mapOf("ok" to true))
    }
    try {
        CHECK(srv.start() == null, "server di prova avviato")
        val idle = ArrayList<Socket>()
        try {
            for (i in 0 until 12) { val c = Socket(); c.connect(InetSocketAddress("127.0.0.1", port), 2000); idle.add(c) }
            Thread.sleep(300)
            val r = rawGet(port, "/x", true)
            val body = r.substringAfter("\r\n\r\n", "")
            val j = Json.parseOrNull(body) as? Map<*, *>
            CHECK(r.startsWith("HTTP/1.1 503") && r.contains("Retry-After: 1") && r.contains("application/json") && j?.get("error") == "busy",
                  { "BUG-11: 503 con JSON {error: busy} e Retry-After (" + r.replace("\r\n", " | ") + ")" })
        } finally { for (c in idle) try { c.close() } catch (e: Exception) {} }
        Thread.sleep(400)
        CHECK(rawGet(port, "/x").startsWith("HTTP/1.1 200"), "BUG-11: liberati i posti, la richiesta passa")
        // --- F3: eccezione nell'handler -> 500 + riga nel log
        boom = true
        val r5 = rawGet(port, "/boom")
        Thread.sleep(200)
        val line = synchronized(logged) { logged.firstOrNull { it.contains("errore interno") && it.contains("/boom") } }
        CHECK(r5.startsWith("HTTP/1.1 500") && line != null && line.contains("prova F3"), { "F3: eccezione nell'handler scritta nel log (" + line + ")" })
    } finally { srv.stop(); SFLog.sink = oldSink }
}
