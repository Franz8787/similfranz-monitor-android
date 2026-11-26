// ANDROID (ora 22/11): test dell'ora di sistema sincronizzata con il controller (set_wall_clock, wall_clock_synced):
// scarto rispetto all'orologio del tablet usato per gli eventi del defibrillatore, la misura NIBP, il registro della sessione
// e «Fine scenario · ore …»; salvato nella ripresa dopo un crash. Senza il comando tutto come prima (ora del tablet).
package it.simulafranz.coretests

import it.simulafranz.core.*
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private fun HMS(ms: Long): String = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date(ms))
@Suppress("UNCHECKED_CAST")
private fun LastEventTime(s: SimulationState): String? =
    ((s.stateDictionary()["defib"] as? Map<String, Any?>)?.get("events") as? List<Map<String, Any?>>)?.lastOrNull()?.get("time") as? String

fun testOra2211() {
    val tablet = 1_763_800_000_000L                   // orologio del tablet fermo (iniettato)
    val oldSys = SFWallClock.system
    SFWallClock.system = { tablet }
    try {
        // --- di serie: non sincronizzato, ora del tablet ---
        var s = NewState()
        CHECK(s.stateDictionary()["wall_clock_synced"] == false && !s.snapshot().wallClockSynced, "di serie: ora non sincronizzata")
        CHECK(s.wallNowMs() == tablet, "di serie: ora del tablet")
        Cmd(s, "vehicle_motion", true)
        CHECK(LastEventTime(s) == HMS(tablet), { "di serie: evento con l'ora del tablet (${LastEventTime(s)})" })

        // --- errori ---
        val e = Err()
        CHECK(!Cmd(s, "set_wall_clock", "12:00", e) && e.has("set_wall_clock: value must be epoch milliseconds"), "stringa rifiutata")
        CHECK(!Cmd(s, "set_wall_clock", true, e), "booleano rifiutato")
        CHECK(!Cmd(s, "set_wall_clock", Double.NaN, e), "NaN rifiutato")
        CHECK(!Cmd(s, "set_wall_clock", 1_000L, e), "secondi o valori fuori intervallo rifiutati")
        CHECK(!Cmd(s, "set_wall_clock", null, e), "valore mancante rifiutato")
        CHECK(!s.snapshot().wallClockSynced, "dopo gli errori: ancora non sincronizzata")

        // --- sincronizzazione: ora del controller 1 h 2 min 3 s avanti ---
        val ctl = tablet + 3_723_000L
        CHECK(Cmd(s, "set_wall_clock", ctl), "set_wall_clock con un Long")
        CHECK(s.stateDictionary()["wall_clock_synced"] == true && s.snapshot().wallClockSynced, "wall_clock_synced vero")
        CHECK(s.wallNowMs() == ctl && SFWallClock.nowMs() == ctl && SFWallClock.synced, "ora sincronizzata nel cuore e nello specchio comune")
        Cmd(s, "vehicle_motion", false)
        CHECK(LastEventTime(s) == HMS(ctl), { "evento del defibrillatore con l'ora del controller (${LastEventTime(s)} invece di ${HMS(ctl)})" })
        CHECK(Cmd(s, "set_wall_clock", ctl.toDouble() + 0.4), "set_wall_clock con un Double (JSON)")
        CHECK(s.wallNowMs() == ctl, "Double arrotondato al ms")
        // NIBP: orario della misura
        RunNibp(s)
        CHECK(s.snapshot().nibpMeasuredAt?.time == ctl, { "NIBP: misura con l'ora del controller (${s.snapshot().nibpMeasuredAt?.time})" })

        // --- il reset dello scenario non la toglie ---
        Reset(s)
        CHECK(s.snapshot().wallClockSynced && s.wallNowMs() == ctl, "reset_scenario: resta sincronizzata")

        // --- registro della sessione e fine scenario ---
        val lg = SFSessionLog(null, s); Cmd(s, "start_scenario"); lg.observe()   // ANDROID (avvio scenario 22/11): dopo il reset si è in PRONTO
        lg.add("note", "prova ora", null, null)
        val ent = lg.entriesSince(0).lastOrNull { it["text"] == "prova ora" }
        CHECK(ent != null && LONG(ent["w"]) == ctl / 1000, { "registro: voce con l'ora del controller (${ent?.get("w")})" })
        CHECK(LONG(lg.sessions().last()["w"]) <= ctl / 1000, "registro: sessione con un'ora valida")
        Cmd(s, "end_scenario"); lg.noteCommand(mapOf("command" to "end_scenario"), null); lg.observe()
        val fin = lg.entriesSince(0).lastOrNull { HASSTR(it["text"], "Fine scenario") }
        CHECK(fin != null && HASSTR(fin["text"], "ore " + HMS(ctl)), { "fine scenario: «ore ${HMS(ctl)}» (${fin?.get("text")})" })
        CHECK(lg.plainText().contains(HMS(ctl)), "registro in testo: ora del controller")

        // --- l'orologio del tablet avanza: l'ora sincronizzata avanza con lui ---
        SFWallClock.system = { tablet + 10_000L }
        CHECK(s.wallNowMs() == ctl + 10_000L && SFWallClock.nowMs() == ctl + 10_000L, "lo scarto resta: l'ora avanza con il tablet")

        // --- ripresa dopo un crash ---
        val saved = s.exportState()
        @Suppress("UNCHECKED_CAST")
        val wc = saved["wall_clock"] as? Map<String, Any?>
        CHECK(wc != null && wc["synced"] == true && LONG(wc["offset_ms"]) == 3_723_000L, "exportState: scarto salvato")
        SFWallClock.apply(0L, false)
        val r = NewStateRaw()
        CHECK(!r.snapshot().wallClockSynced, "stato nuovo: non sincronizzato")
        CHECK(r.restoreState(Json.parse(Json.stringify(saved)) as Map<String, Any?>) == null, "ripristino riuscito")
        CHECK(r.snapshot().wallClockSynced && r.wallNowMs() == ctl + 10_000L && SFWallClock.synced && SFWallClock.nowMs() == ctl + 10_000L,
              "ripristino: ora sincronizzata come prima del crash")
        // salvataggio senza sincronizzazione: il ripristino non la inventa
        val plain = NewState().exportState()
        val r2 = NewStateRaw()
        CHECK(r2.restoreState(Json.parse(Json.stringify(plain)) as Map<String, Any?>) == null && !r2.snapshot().wallClockSynced && r2.wallNowMs() == tablet + 10_000L,
              "ripristino di uno stato non sincronizzato: ora del tablet")
        // valore non valido nel file: ignorato
        val bad = LinkedHashMap(plain); bad["wall_clock"] = mapOf("synced" to true, "offset_ms" to "x")
        val r3 = NewStateRaw()
        CHECK(r3.restoreState(bad) == null && !r3.snapshot().wallClockSynced, "ripristino: scarto non valido ignorato")
    } finally {
        SFWallClock.system = oldSys
        SFWallClock.apply(0L, false)                // gli altri test usano l'ora del tablet
    }
}
