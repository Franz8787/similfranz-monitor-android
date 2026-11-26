// Portato da tests/run-tests.m (monitor iPad)
// testAPI, testControl, testSessionLog, testScenarios
package it.simulafranz.coretests

import it.simulafranz.core.*

fun testAPI() {
    val s = NewState()
    val api = APIController(s, { "482913" }, mapOf("index.html" to "<html>".toByteArray(Charsets.UTF_8)))
    api.clock = { gNow }
    var r = api.handle(Req("GET", "/api/v1/health", null, null))
    CHECK(r.status == 200 && BOOL(J(r)["ok"]) && !HAS(J(r), "pin"), "health senza auth e senza segreti")
    CHECK(api.handle(Req("GET", "/", null, null)).status == 200, "controller statico servito")
    CHECK(api.handle(Req("GET", "/../etc/passwd", null, null)).status == 404, "path non in whitelist -> 404")
    CHECK(api.handle(Req("GET", "/api/v1/state", null, null)).status == 401, "state senza token -> 401")
    r = api.handle(Req("GET", "/api/v1/state", "482913", null))
    CHECK(r.status == 200 && INT(J(r)["state"]["heart_rate_bpm"]) == 82 && HAS(J(r)["state"], "sensors") && HAS(J(r)["state"], "alarm_limits"),
          "state con token, sensori e limiti")
    r = api.handle(Req("POST", "/api/v1/command", "482913", "{\"device_id\":\"monitor-01\",\"command\":\"set_heart_rate\",\"value\":120}"))
    CHECK(r.status == 200 && INT(J(r)["state"]["heart_rate_bpm"]) == 120 && HAS(J(r), "revision"), "comando FC 80->120")
    r = api.handle(Req("POST", "/api/v1/command", "482913", "{\"command\":\"set_sensor\",\"value\":{\"sensor\":\"art\",\"connected\":true}}"))
    CHECK(r.status == 200 && BOOL(J(r)["state"]["sensors"]["art"]), "set_sensor via API")
    r = api.handle(Req("POST", "/api/v1/command", "482913", "{not json"))
    CHECK(r.status == 400 && !BOOL(J(r)["ok"]), "JSON malformato -> 400")
    r = api.handle(Req("POST", "/api/v1/command", "482913", "{\"command\":\"set_spo2\",\"value\":140}"))
    CHECK(r.status == 400 && HASSTR(J(r)["error"], "range"), "valore fuori range -> 400 leggibile")
    CHECK(api.handle(Req("GET", "/api/v1/command", "482913", null)).status == 405, "metodo errato -> 405")
    CHECK(api.handle(Req("POST", "/api/v1/nibp/start", "482913", "{}")).status == 200, "nibp/start")
    CHECK(api.handle(Req("POST", "/api/v1/nibp/start", "482913", "{}")).status == 409, "nibp già in corso -> 409")
    for (i in 0 until 5) api.handle(Req("GET", "/api/v1/state", "000000", null))
    CHECK(api.handle(Req("GET", "/api/v1/state", "482913", null)).status == 429, "blocco dopo 5 tentativi errati")
    gNow += 31
    CHECK(api.handle(Req("GET", "/api/v1/state", "482913", null)).status == 200, "sblocco dopo 30 s")
}

fun testControl() {
    val s = NewState()
    val api = APIController(s, { "482913" }, emptyMap())
    api.clock = { gNow }
    var changes = 0; api.control.onChange = { changes++ }
    val hr = "{\"command\":\"set_heart_rate\",\"value\":90}"
    var r = api.handle(Req("GET", "/api/v1/health", null, null))
    CHECK(!BOOL(J(r)["controller_active"]) && HASOBJ(J(r)["transports"], "direct"), "controllo: all'inizio nessun controller")
    CHECK(api.control.owner() == null, "controllo: owner nil")
    // A (web) apre la pagina: legge e basta, non prende il comando
    r = api.handle(CReq("GET", "/api/v1/state", null, "web-A", "Web%20%C2%B7%20Mac", null))
    CHECK(r.status == 200 && !BOOL(J(r)["controller"]["active"]) && changes == 0, "controllo: leggere non prende il comando")
    // il primo comando prende il comando
    CHECK(api.handle(CReq("POST", "/api/v1/command", hr, "web-A", "Web%20%C2%B7%20Mac", null)).status == 200, "controllo: il primo comando passa")
    r = api.handle(CReq("GET", "/api/v1/state", null, "web-A", null, null))
    CHECK(BOOL(J(r)["controller"]["mine"]) && J(r)["controller"]["name"] == "Web · Mac"
          && J(r)["controller"]["transport"] == "wifi" && J(r)["controller"]["kind"] == "web"
          && BOOL(J(r)["controller"]["protected"]), "controllo: chi comanda è il controller attivo (nome decodificato, canale wifi, protetto)")
    CHECK(changes == 1 && HASSTR(api.control.lastEvent, "Comanda «Web · Mac»"), "controllo: notifica e testo per il registro")
    // B (iPad in collegamento diretto) legge ma non comanda finché A è attivo
    r = api.handle(CReq("GET", "/api/v1/state", null, "ipad-B", "iPad di Franz", "peer:iPad di Franz"))
    CHECK(r.status == 200 && !BOOL(J(r)["controller"]["mine"]) && BOOL(J(r)["controller"]["active"])
          && J(r)["controller"]["name"] == "Web · Mac", "controllo: il secondo legge lo stato e vede chi comanda")
    for (i in 0 until 6) { gNow += 5; api.handle(CReq("GET", "/api/v1/state", null, "web-A", null, null)) }   // A legge di continuo (come le app)
    r = api.handle(CReq("POST", "/api/v1/command", "{\"command\":\"set_heart_rate\",\"value\":50}", "ipad-B", null, "peer:iPad di Franz"))
    CHECK(r.status == 423 && J(r)["error"] == "controller_busy" && HASSTR(J(r)["message"], "Web · Mac")
          && s.snapshot().heartRate == 90, "controllo: chi ha comandato da 30 s è protetto (423), stato invariato")
    CHECK(api.handle(CReq("POST", "/api/v1/nibp/start", "{}", "ipad-B", null, "peer:x")).status == 423, "controllo: anche nibp/start rifiutato")
    // A resta collegato (legge) ma non comanda da più di un minuto: il primo comando di B passa da solo
    for (i in 0 until 7) { gNow += 5; api.handle(CReq("GET", "/api/v1/state", null, "web-A", null, null)) }
    r = api.handle(CReq("GET", "/api/v1/state", null, "ipad-B", null, "peer:iPad di Franz"))
    CHECK(!BOOL(J(r)["controller"]["protected"]) && INT(J(r)["controller"]["action_idle_s"]) >= 60, "controllo: chi è fermo da un minuto non è più protetto")
    r = api.handle(CReq("POST", "/api/v1/command", "{\"command\":\"set_heart_rate\",\"value\":50}", "ipad-B", "iPad di Franz", "peer:iPad di Franz"))
    CHECK(r.status == 200 && s.snapshot().heartRate == 50 && HASSTR(api.control.lastEvent, "era fermo"), "controllo: il comando passa da solo da chi è fermo (e il registro lo dice)")
    r = api.handle(CReq("GET", "/api/v1/state", null, "web-A", null, null))
    CHECK(!BOOL(J(r)["controller"]["mine"]) && J(r)["controller"]["lost_to"] == "iPad di Franz", "controllo: chi perde il comando sa a chi è passato")
    // A riprende il controllo in modo esplicito
    r = api.handle(CReq("POST", "/api/v1/control", "{\"action\":\"take\"}", "web-A", "Web%20%C2%B7%20Mac", null))
    CHECK(r.status == 200 && BOOL(J(r)["controller"]["mine"]) && !HAS(J(r)["controller"], "lost_to")
          && HASSTR(api.control.lastEvent, "ha preso il controllo"), "controllo: «Prendi il controllo» funziona sempre")
    // B prende il controllo (canale diretto)
    r = api.handle(CReq("POST", "/api/v1/control", "{\"action\":\"take\"}", "ipad-B", "iPad di Franz", "peer:iPad di Franz"))
    CHECK(r.status == 200 && BOOL(J(r)["controller"]["mine"]) && J(r)["controller"]["transport"] == "direct"
          && J(r)["controller"]["kind"] == "ipad", "controllo: presa di controllo (canale diretto)")
    CHECK(api.handle(CReq("POST", "/api/v1/command", hr, "web-A", null, null)).status == 423, "controllo: il precedente non comanda più")
    r = api.handle(CReq("GET", "/api/v1/control", null, "web-A", null, null))
    CHECK(r.status == 200 && !BOOL(J(r)["controller"]["mine"]) && J(r)["controller"]["name"] == "iPad di Franz", "controllo: GET control dice chi comanda")
    CHECK(api.handle(CReq("POST", "/api/v1/control", "{\"action\":\"boh\"}", "web-A", null, null)).status == 400, "controllo: azione sconosciuta -> 400")
    CHECK(api.handle(Req("GET", "/api/v1/control", null, null)).status == 401, "controllo: senza PIN -> 401")
    // rilascio di chi non comanda: nessun effetto; rilascio del controller attivo: posto libero
    api.handle(CReq("POST", "/api/v1/control", "{\"action\":\"release\"}", "web-A", null, null))
    CHECK(api.control.owner() != null, "controllo: il rilascio di chi non comanda non cambia nulla")
    r = api.handle(CReq("POST", "/api/v1/control", "{\"action\":\"release\"}", "ipad-B", null, "peer:iPad di Franz"))
    CHECK(r.status == 200 && !BOOL(J(r)["controller"]["active"]) && api.control.owner() == null, "controllo: rilascio")
    // chi comanda sparisce (app chiusa, fuori portata): dopo 10 s senza farsi sentire il posto torna libero
    api.handle(CReq("POST", "/api/v1/command", hr, "web-A", null, null))
    gNow += 6; CHECK(api.handle(CReq("POST", "/api/v1/command", hr, "ipad-B", null, null)).status == 423, "controllo: 6 s di silenzio non bastano")
    gNow += 6; CHECK(api.handle(CReq("POST", "/api/v1/command", hr, "ipad-B", null, null)).status == 200, "controllo: dopo 12 s il posto è libero e lo prende chi comanda")
    api.handle(CReq("POST", "/api/v1/control", "{\"action\":\"release\"}", "ipad-B", null, null))
    // il saturimetro (seguace) non occupa il posto e non viene bloccato
    var ox = Req("GET", "/api/v1/state", "482913", null)
    var h = LinkedHashMap(ox.headers); h["x-simulafranz-device"] = "oximeter"; ox.headers = h
    r = api.handle(ox)
    CHECK(r.status == 200 && api.control.owner() == null && !BOOL(J(r)["controller"]["active"]), "controllo: il saturimetro non diventa controller")
    api.handle(CReq("GET", "/api/v1/state", null, "web-A", null, null))
    ox = Req("POST", "/api/v1/command", "482913", "{\"command\":\"set_oximeter\",\"value\":{\"power\":true}}")
    h = LinkedHashMap(ox.headers); h["x-simulafranz-device"] = "oximeter"; ox.headers = h
    CHECK(api.handle(ox).status != 423, "controllo: i comandi del saturimetro non sono bloccati dal controller attivo")
    // vecchi client senza identità: vale l'indirizzo
    api.handle(CReq("POST", "/api/v1/control", "{\"action\":\"release\"}", "web-A", null, null))
    CHECK(api.handle(Req("POST", "/api/v1/command", "482913", hr)).status == 200, "controllo: client senza identità accettato (indirizzo)")
    val other = Req("POST", "/api/v1/command", "482913", hr); other.remoteAddress = "192.168.4.77"
    CHECK(api.handle(other).status == 423, "controllo: altro indirizzo senza identità rifiutato")
    // identità "sporca": caratteri non ammessi tolti, lunghezza limitata
    api.control.releaseClient(null)
    api.handle(CReq("POST", "/api/v1/command", hr, "a b<c>\"", "%0Anome%7F", null))
    r = api.handle(CReq("GET", "/api/v1/state", null, "a b<c>\"", "%0Anome%7F", null))
    CHECK(BOOL(J(r)["controller"]["mine"]) && J(r)["controller"]["name"] == "nome", "controllo: identità ripulita")
}

fun LogHas(l: SFSessionLog, needle: String): Boolean { for (d in l.entriesSince(0)) if (HASSTR(d["text"], needle)) return true; return false }

fun testSessionLog() {
    val s = NewStateRaw()
    val path = TempDir("sf-test-registro")      // sull'iPad: il file sf-test-registro.json
    val lg = SFSessionLog(path, s)
    lg.observe()
    CHECK(lg.entriesSince(0).size == 0, "registro: vuoto all'inizio")
    // ANDROID (avvio scenario 22/11): in PRONTO il registro non scrive; start_scenario lo fa partire
    s.devicePowered = true; lg.observe(); lg.noteCommand(mapOf("command" to "set_spo2", "value" to 90), "istr")
    CHECK(lg.entriesSince(0).size == 0 && lg.add("note", "nota in PRONTO", null, "istr") == null, "registro: in PRONTO non scrive (voci automatiche, comandi, note)")
    s.devicePowered = false; lg.observe()
    Cmd(s, "start_scenario"); lg.noteCommand(mapOf("command" to "start_scenario"), "istr")
    CHECK(lg.entriesSince(0).size == 1 && HASSTR(lg.entriesSince(0)[0]["text"], "Scenario avviato · ore ") && lg.entriesSince(0)[0]["tag"] == "scenario_start", "registro: start_scenario = «Scenario avviato · ore …»")
    // partecipanti: accendono, applicano le sonde, misurano la pressione
    s.devicePowered = true; Sensor(s, "ecg", true); Sensor(s, "nibp", true); lg.observe()
    CHECK(LogHas(lg, "Monitor acceso") && LogHas(lg, "Elettrodi ECG: applicata") && LogHas(lg, "Bracciale NIBP: applicata"), "registro: accensione e sonde")
    s.startNibpMeasurement(); lg.observe(); gNow += 60; s.tickAtTime(gNow); lg.observe()
    CHECK(LogHas(lg, "Misura NIBP avviata") && LogHas(lg, "NIBP misurata: "), "registro: misura NIBP con il valore")
    // istruttore: comandi tradotti in voci leggibili, con il nome del controller
    lg.noteCommand(SCJ("{\"command\":\"transition\",\"value\":{\"params\":{\"heart_rate_bpm\":130,\"spo2_percent\":88},\"duration_s\":30}}"), "similFranz Controller 1")
    val last = lg.entriesSince(0).lastOrNull()
    CHECK(last["text"] == "Parametri: FC 130, SpO₂ 88 (in 30 s)" && last["by"] == "similFranz Controller 1" && last["k"] == "patient") { F("registro: comando dell'istruttore (%s)", last["text"]) }
    lg.noteCommand(SCJ("{\"command\":\"set_sensor\",\"value\":{\"sensor\":\"ecg\",\"connected\":true}}"), "x")
    CHECK(STR(lg.entriesSince(0).lastOrNull()["text"])?.startsWith("Parametri") == true, "registro: i comandi già registrati dall'osservazione non si duplicano")
    // arresto, osservazioni dell'istruttore, RCP, scarica, ROSC -> riepilogo con i tempi dall'arresto
    gNow += 100; RhythmNow(s, "vf", null); lg.observe()
    CHECK(LogHas(lg, "Ritmo: fibrillazione ventricolare") && LogHas(lg, "arresto cardiaco"), "registro: arresto")
    val t0 = s.snapshot().scenarioTime.toInt()
    gNow += 12; lg.add("action", "Riconosciuto l'arresto", "ac", "istr")
    gNow += 8; Cmd(s, "set_cpr", true, null); lg.observe()
    gNow += 5; lg.add("drug", "Adrenalina 1 mg", null, "istr")
    Cmd(s, "defib_mode", "manual", null); Sensor(s, "pads", true); Cmd(s, "defib_energy", 200, null); lg.observe()
    Cmd(s, "defib_charge", null, null); Tick(s, 6); lg.observe()
    Cmd(s, "defib_shock", null, null); lg.observe()
    val sm = lg.summary()
    CHECK(INT(sm["arrest_at_s"]) == t0 && INT(sm["to_recognition_s"]) == 12 && INT(sm["to_cpr_s"]) == 20) { F("registro: tempi dall'arresto (riconoscimento %s, RCP %s)", sm["to_recognition_s"], sm["to_cpr_s"]) }
    CHECK(COUNT(sm["shocks"]) == 1 && INT(sm["shocks"][0]["energy_j"]) == 200 && INT(sm["to_first_shock_s"]) >= 30) { F("registro: scarica con energia e tempo (%s)", sm["shocks"]) }
    CHECK(COUNT(sm["drugs"]) == 1 && sm["drugs"][0]["text"] == "Adrenalina 1 mg", "registro: farmaci nel riepilogo")
    CHECK(LogHas(lg, "manuale") && LogHas(lg, "Piastre applicate"), "registro: eventi del defibrillatore fatti dai partecipanti")
    var shockEntries = 0; for (d in lg.entriesSince(0)) if (d["tag"] == "shock") shockEntries++
    CHECK(shockEntries == 1) { F("registro: una sola voce per la scarica (%d)", shockEntries) }
    Cmd(s, "set_simulation_running", false, null); lg.observe(); Cmd(s, "set_simulation_running", true, null); lg.observe()
    lg.noteCommand(mapOf("command" to "reset_clock"), "istr")
    CHECK(LogHas(lg, "Scenario in pausa") && LogHas(lg, "Scenario ripreso") && LogHas(lg, "Timer di scenario azzerato"), "registro: azioni sul timer")
    lg.add("plus", "Ottima comunicazione a circuito chiuso", null, "istr"); lg.add("minus", "Ritardo nel cambio alle compressioni", null, "istr")
    CHECK(INT(lg.summary()["plus"]) == 1 && INT(lg.summary()["minus"]) == 1, "registro: punti di forza e da discutere")
    CHECK(lg.add("boh", "x", null, null) != null && lg.entriesSince(0).lastOrNull()["k"] == "note" && lg.add("note", "   ", null, null) == null, "registro: categoria sconosciuta = nota, testo vuoto rifiutato")
    CHECK(lg.plainText().contains("FARMACO") && lg.plainText().contains("Adrenalina 1 mg"), "registro: esportazione in testo")
    val n = lg.nextIndex()
    CHECK(lg.entriesSince(n - 2).size == 2, "registro: lettura incrementale")
    // persistenza
    lg.observe(); lg.flush(); gNow += 10
    val lg2 = SFSessionLog(path, s)
    CHECK(lg2.entriesSince(0).size >= n - 1 || lg2.entriesSince(0).size > 10) { F("registro: riletto dal file dopo il riavvio (%d voci)", lg2.entriesSince(0).size) }
    // API
    val api = APIController(s, { "482913" }, emptyMap())
    api.clock = { gNow }; api.sessionLog = lg
    api.handle(CReq("POST", "/api/v1/control", "{\"action\":\"take\"}", "web-A", "Web%20A", null))     // A comanda
    var r = api.handle(CReq("GET", "/api/v1/log", null, "web-A", "Web%20A", null))
    CHECK(r.status == 200 && COUNT(J(r)["log"]) > 10 && HAS(J(r)["summary"], "shocks") && INT(J(r)["next"]) == lg.nextIndex(), "registro API: lettura con riepilogo")
    var q = CReq("GET", "/api/v1/log", null, "web-A", null, null); q.query = F("since=%d", lg.nextIndex() - 1)
    CHECK(COUNT(J(api.handle(q))["log"]) == 1, "registro API: since")
    q = CReq("GET", "/api/v1/log", null, "web-A", null, null); q.query = "format=text"
    CHECK(String(api.handle(q).body, Charsets.UTF_8).contains("registro della sessione"), "registro API: testo")
    // un secondo istruttore (che non comanda) può annotare ma non svuotare
    r = api.handle(CReq("POST", "/api/v1/log", "{\"action\":\"add\",\"kind\":\"note\",\"text\":\"Nota del secondo istruttore\"}", "web-B", "Secondo", null))
    CHECK(r.status == 200 && lg.entriesSince(0).lastOrNull()["by"] == "Secondo", "registro API: annota anche chi non comanda")
    CHECK(api.handle(CReq("POST", "/api/v1/log", "{\"action\":\"clear\"}", "web-B", null, null)).status == 423, "registro API: svuota solo chi comanda")
    CHECK(api.handle(CReq("POST", "/api/v1/log", "{\"action\":\"add\",\"text\":\"\"}", "web-A", null, null)).status == 400, "registro API: testo obbligatorio")
    api.handle(CReq("POST", "/api/v1/command", "{\"command\":\"set_spo2\",\"value\":91}", "web-A", "Web%20A", null))
    CHECK(lg.entriesSince(0).lastOrNull()["text"] == "Parametri: SpO₂ 91", "registro API: i comandi finiscono nel registro")
    // sessioni: l'azzeramento dello scenario e l'avvio di uno scenario aprono una sessione nuova
    val s1 = lg.currentSession(); val n1 = lg.entriesSince(0, s1).size
    api.handle(CReq("POST", "/api/v1/command", "{\"command\":\"reset_scenario\"}", "web-A", null, null))
    CHECK(lg.currentSession() == s1 + 1 && lg.entriesSince(0, s1).size == n1, "sessioni: l'azzeramento apre una sessione nuova, la precedente resta intatta")
    lg.observe(); lg.observe()
    CHECK(lg.entriesSince(0, s1 + 1).size == 0) { F("sessioni: l'azzeramento non lascia voci false (ritmo/ROSC) nella sessione nuova (%d)", lg.entriesSince(0, s1 + 1).size) }
    api.handle(CReq("POST", "/api/v1/log", "{\"action\":\"new_session\",\"title\":\"Prova a mano\"}", "web-A", null, null))
    CHECK(lg.currentSession() == s1 + 1 && lg.sessions().lastOrNull()["title"] == "Prova a mano", "sessioni: una sessione ancora vuota viene riusata (cambia solo il titolo)")
    // ANDROID (avvio scenario 22/11): dopo l'azzeramento si è in PRONTO: la nota entra solo dopo start_scenario
    CHECK(lg.add("note", "nota prima dell'avvio", null, null) == null, "sessioni: dopo l'azzeramento (PRONTO) il registro è fermo")
    api.handle(CReq("POST", "/api/v1/command", "{\"command\":\"start_scenario\"}", "web-A", null, null))
    CHECK(lg.currentSession() == s1 + 1 && lg.sessions().lastOrNull()["title"] == "Prova a mano" && lg.entriesSince(0, s1 + 1).size == 1, "sessioni: start_scenario su una sessione vuota la tiene (con il titolo)")
    lg.add("note", "prima nota della sessione nuova", null, null)
    r = api.handle(CReq("GET", "/api/v1/log", null, "web-A", null, null))
    CHECK(COUNT(J(r)["log"]) == 2 && INT(J(r)["session"]) == s1 + 1 && COUNT(J(r)["sessions"]) == 2 && COUNT(J(r)["summary"]["shocks"]) == 0, "sessioni API: per default la sessione corrente, con il suo riepilogo")
    q = CReq("GET", "/api/v1/log", null, "web-A", null, null); q.query = F("session=%d", s1)
    CHECK(COUNT(J(api.handle(q))["log"]) == n1 && COUNT(J(api.handle(q))["summary"]["shocks"]) == 1, "sessioni API: una sessione precedente con il suo riepilogo")
    api.scenarios = SFScenarioStore(null, s)
    api.handle(CReq("POST", "/api/v1/scenarios", "{\"action\":\"start\",\"id\":\"es-arresto-fv\"}", "web-A", null, null))
    CHECK(lg.currentSession() == s1 + 2 && HASSTR(lg.sessions().lastOrNull()["title"], "Dolore toracico") && lg.entriesSince(0, s1 + 2).size >= 1, "sessioni: ogni scenario avviato è una sessione con il suo nome")
    CHECK(INT(J(api.handle(CReq("GET", "/api/v1/state", null, "web-A", null, null)))["log_session"]) == s1 + 2, "sessioni API: log_session nello stato")
    r = api.handle(CReq("POST", "/api/v1/log", "{\"action\":\"clear\"}", "web-A", null, null))
    CHECK(r.status == 200 && COUNT(J(r)["log"]) == 0 && COUNT(J(r)["sessions"]) == 1, "registro API: svuotato, resta una sessione nuova e vuota")
    CHECK(INT(J(api.handle(CReq("GET", "/api/v1/state", null, "web-A", null, null)))["log_next"]) == lg.nextIndex(), "registro API: log_next nello stato")
    path.deleteRecursively()
}

// [st save:… error:&err] dell'iPad: lo scenario normalizzato, oppure nil con l'errore in err
fun Save(st: SFScenarioStore, scenario: Map<String, Any?>?, err: Err): Map<String, Any?>? {
    val r = st.save(scenario)
    if (r.scenario == null) err.v = r.error
    return r.scenario
}

fun testScenarios() {
    val s = NewState()
    val path = TempDir("sf-test-scenari")       // sull'iPad: il file sf-test-scenari.json
    val st = SFScenarioStore(path, s)
    val N0 = 3
    CHECK(st.scenarios().size == N0 && !BOOL(st.runInfo()["active"]), "scenari: tre esempi all'inizio, nessuno in corso")
    val err = Err()
    val SC = { json: String -> SCJ(json) }
    CHECK(Save(st, SC("{\"name\":\"\",\"phases\":[{\"rhythm\":\"sinus\"}]}"), err) == null && err.v != null, "scenari: nome obbligatorio")
    CHECK(Save(st, SC("{\"name\":\"x\",\"phases\":[]}"), err) == null, "scenari: almeno una fase")
    CHECK(Save(st, SC("{\"name\":\"x\",\"phases\":[{\"name\":\"vuota\"}]}"), err) == null && err.has("empty"), "scenari: fase vuota rifiutata")
    CHECK(Save(st, SC("{\"name\":\"x\",\"phases\":[{\"params\":{\"spo2_percent\":140}}]}"), err) == null && err.has("phase 1"), "scenari: valore fuori range con numero di fase")
    CHECK(Save(st, SC("{\"name\":\"x\",\"phases\":[{\"rhythm\":\"boh\"}]}"), err) == null, "scenari: ritmo sconosciuto rifiutato")
    CHECK(Save(st, SC("{\"name\":\"x\",\"phases\":[{\"params\":{\"systolic_mmhg\":80,\"diastolic_mmhg\":90}}]}"), err) == null, "scenari: diastolica >= sistolica rifiutata")
    val sc = Save(st, SC("{\"name\":\"  Prova  \",\"notes\":\"note\",\"phases\":[" +
        "{\"name\":\"A\",\"rhythm\":\"sinus\",\"duration_s\":0,\"params\":{\"heart_rate_bpm\":100,\"spo2_percent\":95},\"temperature_c\":38.44,\"crt_s\":3}," +
        "{\"rhythm\":\"vf\"}," +
        "{\"name\":\"C\",\"note\":\"dopo la scarica\",\"rhythm\":\"sinus\",\"duration_s\":20,\"params\":{\"heart_rate_bpm\":60}}]}"), err)
    CHECK(sc != null && sc["name"] == "Prova" && LEN(sc["id"]) > 0 && sc["phases"][1]["name"] == "Fase 2"
          && Math.abs(DBL(sc["phases"][0]["temperature_c"]) - 38.4) < 1e-6) { F("scenari: salvataggio normalizzato (%s)", err) }
    CHECK(st.scenarios().size == N0 + 1, "scenari: aggiunto all'elenco")
    val sid = STR(sc["id"])
    CHECK(!Ok(st.next(), err), "scenari: fase successiva senza scenario in corso -> errore")
    CHECK(!Ok(st.start("non-esiste", 0), err), "scenari: avvio di uno scenario inesistente")
    CHECK(Ok(st.start(sid, 0), err) && s.snapshot().heartRate == 100 && s.snapshot().spo2 == 95 && s.snapshot().temperatureTenths == 384,
          "scenari: avvio = fase 1 applicata subito")
    CHECK(INT(s.stateDictionary()["scenario_time_s"]) == 0, "scenari: l'avvio azzera l'orologio di scenario")
    val r = st.runInfo()
    CHECK(BOOL(r["active"]) && INT(r["phase"]) == 0 && INT(r["phases"]) == 3 && r["phase_name"] == "A" && r["next_name"] == "Fase 2", "scenari: stato della conduzione")
    gNow += 40; CHECK(INT(s.stateDictionary()["scenario_time_s"]) == 40, "orologio: 40 s dopo l'avvio")
    Cmd(s, "reset_clock", null, null)
    CHECK(INT(s.stateDictionary()["scenario_time_s"]) == 0 && s.snapshot().heartRate == 100, "orologio: azzerato a mano, i parametri restano")
    gNow += 5; CHECK(INT(s.stateDictionary()["scenario_time_s"]) == 5, "orologio: riparte da zero")
    gNow -= 45
    // la fase NON avanza da sola; l'istruttore può ritoccare a mano
    gNow += 300; s.tickAtTime(gNow)
    CHECK(INT(st.runInfo()["phase"]) == 0 && INT(st.runInfo()["phase_time_s"]) >= 300, "scenari: dopo 5 minuti è ancora nella fase 1 (decide l'istruttore)")
    Cmd(s, "set_heart_rate", 140, null)
    CHECK(s.snapshot().heartRate == 140 && INT(st.runInfo()["phase"]) == 0, "scenari: modifica a mano durante la fase")
    // ANDROID (transizioni ritmo 22/11): la fase successiva cambia ritmo con il preludio (la FV arriva in pochi secondi)
    CHECK(Ok(st.next(), err) && s.stateDictionary()["rhythm_onset"]["to"] == "vf" && INT(st.runInfo()["phase"]) == 1 && INT(st.runInfo()["phase_time_s"]) == 0, "scenari: fase successiva (FV): preludio")
    Tick(s, 7.0)
    CHECK(s.snapshot().rhythm == "vf", "scenari: fase successiva (FV)")
    CHECK(Ok(st.next(), err) && s.stateDictionary()["rhythm_onset"]["to"] == "sinus" && !HAS(st.runInfo(), "next_name"), "scenari: ultima fase (ROSC con il preludio)")
    Tick(s, 28)
    CHECK(s.snapshot().rhythm == "sinus", "scenari: ultima fase: sinusale")
    CHECK(s.snapshot().heartRate == 60) { F("scenari: la fase con transizione arriva al valore nel tempo scelto (FC %d)", s.snapshot().heartRate) }
    CHECK(!Ok(st.next(), err) && err.has("last"), "scenari: oltre l'ultima fase -> errore")
    CHECK(Ok(st.gotoPhase(0), err) && s.snapshot().heartRate == 100, "scenari: salto a una fase qualsiasi")
    CHECK(!Ok(st.gotoPhase(9), err), "scenari: fase inesistente")
    // sinusale -> tachicardia con FC di arrivo e durata: la FC parte da dov'è (niente salto al valore tipico del nuovo ritmo)
    RhythmNow(s, "sinus", null); Cmd(s, "set_heart_rate", 96, null)
    Cmd(s, "transition", SC("{\"rhythm\":\"sinus_tachycardia\",\"duration_s\":60,\"params\":{\"heart_rate_bpm\":122}}"), null)
    CHECK(s.snapshot().heartRate == 96) { F("scenari: cambio di ritmo con FC di arrivo, la rampa parte dalla FC attuale (FC %d)", s.snapshot().heartRate) }
    Tick(s, 30); CHECK(s.snapshot().heartRate > 100 && s.snapshot().heartRate < 118) { F("scenari: a metà rampa FC intermedia (%d)", s.snapshot().heartRate) }
    Tick(s, 35); CHECK(s.snapshot().heartRate == 122, "scenari: fine rampa FC 122")
    // persistenza
    val st2 = SFScenarioStore(path, s)
    CHECK(st2.scenarios().size == N0 + 1 && st2.scenarios().getOrNull(N0)["name"] == "Prova", "scenari: riletti dal file")
    // modifica per id, eliminazione dello scenario in corso
    val mod = LinkedHashMap<String, Any?>(sc ?: emptyMap()); mod["name"] = "Prova 2"
    CHECK(Save(st, mod, err) != null && st.scenarios().size == N0 + 1 && st.scenarios().getOrNull(N0)["name"] == "Prova 2", "scenari: modifica per id")
    CHECK(Ok(st.deleteId(sid), err) && st.scenarios().size == N0 && !BOOL(st.runInfo()["active"]), "scenari: eliminare lo scenario in corso lo chiude")
    CHECK(!Ok(st.deleteId(sid), err), "scenari: eliminazione di uno inesistente")
    // API
    val api = APIController(s, { "482913" }, emptyMap())
    api.clock = { gNow }
    api.scenarios = st
    var resp = api.handle(CReq("GET", "/api/v1/scenarios", null, "web-A", null, null))
    CHECK(resp.status == 200 && COUNT(J(resp)["scenarios"]) == N0 && !BOOL(J(resp)["scenario"]["active"]), "scenari API: elenco")
    CHECK(api.handle(Req("GET", "/api/v1/scenarios", null, null)).status == 401, "scenari API: serve il PIN")
    resp = api.handle(CReq("POST", "/api/v1/scenarios", "{\"action\":\"start\",\"id\":\"es-arresto-fv\"}", "web-A", null, null))
    CHECK(resp.status == 200 && INT(J(resp)["scenario"]["phase"]) == 0 && INT(J(resp)["state"]["heart_rate_bpm"]) == 96, "scenari API: avvio")
    resp = api.handle(CReq("GET", "/api/v1/state", null, "web-A", null, null))
    CHECK(BOOL(J(resp)["scenario"]["active"]) && INT(J(resp)["scenario"]["phases"]) == 4, "scenari API: la conduzione è nello stato")
    CHECK(api.handle(CReq("POST", "/api/v1/scenarios", "{\"action\":\"next\"}", "altro", null, null)).status == 423, "scenari API: chi non comanda non avanza le fasi")
    resp = api.handle(CReq("POST", "/api/v1/scenarios", "{\"action\":\"goto\",\"phase\":2}", "web-A", null, null))
    CHECK(resp.status == 200 && J(resp)["state"]["rhythm_onset"]["to"] == "vf", "scenari API: salto alla fase 3 (FV, con il preludio)")
    resp = api.handle(CReq("POST", "/api/v1/scenarios", "{\"action\":\"save\",\"scenario\":{\"name\":\"Da API\",\"phases\":[{\"rhythm\":\"afib\",\"params\":{\"heart_rate_bpm\":140}}]}}", "web-A", null, null))
    CHECK(resp.status == 200 && COUNT(J(resp)["scenarios"]) == N0 + 1 && LEN(J(resp)["saved"]["id"]) > 0, "scenari API: salvataggio")
    resp = api.handle(CReq("POST", "/api/v1/scenarios", "{\"action\":\"save\",\"scenario\":{\"name\":\"\",\"phases\":[]}}", "web-A", null, null))
    CHECK(resp.status == 400 && LEN(J(resp)["error"]) > 0, "scenari API: errore leggibile")
    CHECK(api.handle(CReq("POST", "/api/v1/scenarios", "{\"action\":\"boh\"}", "web-A", null, null)).status == 400, "scenari API: azione sconosciuta")
    api.handle(CReq("POST", "/api/v1/command", "{\"command\":\"reset_scenario\"}", "web-A", null, null))
    CHECK(!BOOL(st.runInfo()["active"]), "scenari API: il reset chiude lo scenario a fasi")
    path.deleteRecursively()
}
