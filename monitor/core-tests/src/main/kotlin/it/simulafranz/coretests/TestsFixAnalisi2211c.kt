// ANDROID (fix analisi 22/11, terzo giro): la domanda sull'esito è legata al numero della scarica.
//   - una scarica nuova chiude la domanda rimasta aperta per la precedente (anche con l'avanzamento automatico)
//   - defib_outcome con "shock" vale solo per la scarica in attesa; senza "shock" come prima (regie vecchie)
package it.simulafranz.coretests

import it.simulafranz.core.*

private fun Outcome(s: SimulationState, v: String, shock: Any?, err: Err? = null): Boolean {
    val d = linkedMapOf<String, Any?>("command" to "defib_outcome", "value" to v)
    if (shock != null) d["shock"] = shock
    return Ok(s.applyCommand(d), err)
}

fun testFixAnalisi2211c() {
    // 1) scarica 1 senza risposta (FV), poi cardioversione sincronizzata con avanzamento automatico (scarica 2)
    val s = NewState(); val err = Err()
    Cmd(s, "set_defib_config", mapOf("auto_advance" to true))
    Sensor(s, "pads", true); Cmd(s, "defib_mode", "manual"); RhythmNow(s, "vf")
    Cmd(s, "defib_charge"); Tick(s, 5); Cmd(s, "defib_shock")
    val d1 = s.stateDictionary()["defib"]
    CHECK(BOOL(d1["outcome_pending"]) && INT(d1["outcome_shock"]) == 1, "terzo giro: scarica 1 in attesa di decisione")
    RhythmNow(s, "afib"); Cmd(s, "defib_energy", 100); Cmd(s, "defib_sync", true)
    Cmd(s, "defib_charge"); Tick(s, 5); Cmd(s, "defib_shock"); s.defibRWaveAt(gNow + 0.2)
    val d2 = s.stateDictionary()["defib"]
    CHECK(!BOOL(d2["outcome_pending"]) && PS(s, 2.5) && s.snapshot().rhythm == "sinus", "terzo giro: la scarica 2 (automatico) chiude la domanda della scarica 1")
    val e1 = Err()
    CHECK(!Cmd(s, "defib_outcome", "vf", e1) && e1.has("no shock awaiting") && s.snapshot().rhythm == "sinus",
          { "terzo giro: risposta alla domanda vecchia rifiutata, resta sinusale ($e1)" })
    CHECK(!Outcome(s, "vf", 1, Err()) && s.snapshot().rhythm == "sinus", "terzo giro: risposta con shock 1 rifiutata")
    Tick(s, 1)
    CHECK(Cmd(s, "undo_auto_action", null, err), { "terzo giro: annulla dell'automatico ($err)" })
    val dd3 = s.stateDictionary()["defib"]
    CHECK(BOOL(dd3["outcome_pending"]) && INT(dd3["outcome_shock"]) == 2 && s.snapshot().rhythm == "afib",
          "terzo giro: dopo «Annulla» la domanda è quella della scarica 2 (FA), non della 1")
    val e2 = Err()
    CHECK(!Outcome(s, "sinus", 1, e2) && e2.has("not the one awaiting") && s.snapshot().rhythm == "afib",
          { "terzo giro: esito per la scarica 1 rifiutato mentre aspetta la 2 ($e2)" })
    CHECK(Outcome(s, "stay", 2, err) && s.snapshot().rhythm == "afib" && !BOOL(s.stateDictionary()["defib"]["outcome_pending"]),
          { "terzo giro: esito per la scarica 2 accettato ($err)" })

    // 2) modalità istruttore senza automatico: due scariche senza risposta -> la domanda è della seconda
    val t = NewState()
    Cmd(t, "set_defib_config", mapOf("outcome_mode" to "instructor"))
    Sensor(t, "pads", true); Cmd(t, "defib_mode", "manual"); RhythmNow(t, "vf")
    Cmd(t, "defib_charge"); Tick(t, 5); Cmd(t, "defib_shock")
    Cmd(t, "defib_charge"); Tick(t, 5); Cmd(t, "defib_shock")
    CHECK(INT(t.stateDictionary()["defib"]["outcome_shock"]) == 2, "terzo giro: dopo due scariche la domanda è della scarica 2")
    CHECK(!Outcome(t, "sinus", 1, Err()) && t.snapshot().rhythm == "vf", "terzo giro: esito con shock 1 rifiutato")
    CHECK(!Outcome(t, "sinus", "2", Err()) && t.snapshot().rhythm == "vf", "terzo giro: shock non numerico rifiutato")
    CHECK(Outcome(t, "sinus", 2.0, err) && PS(t) && t.snapshot().rhythm == "sinus", { "terzo giro: esito con shock 2 accettato ($err)" })
    CHECK((t.stateDictionary()["defib"]["events"] as List<*>).any { HASSTR(it["text"], "scarica 1 chiusa senza risposta") }, "terzo giro: nel registro la domanda della scarica 1 chiusa")
    // senza "shock" (regie vecchie) come prima
    RhythmNow(t, "vf"); Cmd(t, "defib_charge"); Tick(t, 5); Cmd(t, "defib_shock")
    CHECK(Cmd(t, "defib_outcome", "stay", err) && t.snapshot().rhythm == "vf", { "terzo giro: esito senza shock accettato ($err)" })
}
