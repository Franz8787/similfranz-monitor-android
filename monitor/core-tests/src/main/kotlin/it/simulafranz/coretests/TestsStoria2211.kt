// ANDROID (storia sim 22/11): test dell'archivio «Storia Sim» (SFArchive) e dell'API /api/v1/archive (STORIA_SIM.md).
package it.simulafranz.coretests

import it.simulafranz.core.*
import java.io.File

private fun rec(id: String, savedW: Long, title: String = "Arresto in ambulanza", extra: String = ""): String =
    "{\"id\":\"$id\",\"v\":1,\"saved_w\":$savedW,\"date_text\":\"22/11/2026 09:30\",\"title\":\"$title\",\"source\":\"controller\"," +
    "\"saved_by\":\"similFranz Controller 3F2A\",\"layout\":\"dae\",\"duration_s\":754," +
    "\"team\":[{\"name\":\"Mario\",\"role\":\"Team leader\",\"achieved\":\"ok\",\"reinforce\":\"\"},{\"name\":\"Anna\",\"role\":\"Compressioni\"}]," +
    "\"summary\":{},\"cpr\":null,\"end\":{\"t\":754,\"w\":1795339990},\"entries\":[]$extra}"

fun testStoria2211() {
    // --- SFArchive su file ---
    val dir = TempDir("sf-storia-test")
    var a = SFArchive(dir)
    CHECK(a.items().isEmpty(), "archivio: vuoto all'inizio")
    CHECK(a.save(SCJ(rec("20261122-093012-a1b2", 1795340000))).error == null, "archivio: salvataggio")
    CHECK(File(dir, "archivio/20261122-093012-a1b2.json").isFile, "archivio: un file per record in archivio/")
    CHECK(File(dir, "archivio").listFiles()!!.none { it.name.endsWith(".tmp") }, "archivio: nessun file temporaneo rimasto")
    CHECK(a.save(SCJ(rec("20261122-100000-c3d4", 1795342000, "Secondo"))).error == null, "archivio: secondo record")
    var it0 = a.items()
    CHECK(it0.size == 2 && it0[0]["id"] == "20261122-100000-c3d4", "archivio: più recenti prima")
    CHECK(INT(it0[1]["team_n"]) == 2 && LONG(it0[1]["duration_s"]) == 754L && it0[1]["source"] == "controller"
          && it0[1]["date_text"] == "22/11/2026 09:30" && it0[1]["saved_by"] == "similFranz Controller 3F2A", "archivio: riassunto completo")
    CHECK(COUNT(it0[0]) == 8, "archivio: riassunto con 8 campi")
    // stesso id = sostituisce
    CHECK(a.save(SCJ(rec("20261122-093012-a1b2", 1795340000, "Rinominato"))).error == null, "archivio: sostituzione")
    CHECK(a.count() == 2 && a.item("20261122-093012-a1b2")["title"] == "Rinominato", "archivio: stesso id sostituisce")
    // persistenza: un'altra istanza rilegge i file
    a = SFArchive(dir)
    CHECK(a.count() == 2 && a.item("20261122-093012-a1b2")["title"] == "Rinominato", "archivio: riletto dai file")
    CHECK(COUNT(a.item("20261122-093012-a1b2")["team"]) == 2, "archivio: record completo")
    // id non validi
    CHECK(a.save(SCJ(rec("../evil", 1))).error != null, "archivio: id con / rifiutato")
    CHECK(a.save(SCJ(rec("a_b", 1))).error != null, "archivio: id con _ rifiutato")
    CHECK(a.save(SCJ(rec("x".repeat(65), 1))).error != null, "archivio: id oltre 64 rifiutato")
    CHECK(a.save(SCJ(rec("x".repeat(64), 1))).error == null, "archivio: id di 64 accettato")
    CHECK(a.save(SCJ("{\"title\":\"senza id\"}")).error != null, "archivio: senza id rifiutato")
    CHECK(a.item("../registro") == null, "archivio: lettura con id non valido = null")
    CHECK(a.delete("x".repeat(64)) && !a.delete("x".repeat(64)), "archivio: cancellazione")
    CHECK(!File(dir, "archivio/" + "x".repeat(64) + ".json").exists(), "archivio: file tolto")
    // tetto: oltre max si toglie il più vecchio (mai quello appena salvato)
    val d2 = TempDir("sf-storia-max")
    val b = SFArchive(d2); b.max = 3
    for (i in 1..3) b.save(SCJ(rec("r$i", 1000L + i)))
    b.save(SCJ(rec("r4", 2000)))
    CHECK(b.count() == 3 && b.item("r1") == null && b.item("r4") != null, "archivio: tetto, tolto il più vecchio")
    CHECK(!File(d2, "archivio/r1.json").exists(), "archivio: tetto, file del più vecchio tolto")
    b.save(SCJ(rec("r0", 10)))       // più vecchio di tutti ma appena salvato: resta
    CHECK(b.count() == 3 && b.item("r0") != null && b.item("r2") == null, "archivio: tetto, il record appena salvato resta")
    CHECK(kArchiveMax == 500, "archivio: massimo 500 record")

    // --- API /api/v1/archive ---
    val s = NewState()
    val api = APIController(s, { "482913" }, emptyMap()); api.clock = { gNow }
    CHECK(api.handle(CReq("GET", "/api/v1/archive", null, "ctl-A", "Ctl%20A", null)).status == 404, "API archivio: assente senza archivio")
    api.archive = SFArchive(TempDir("sf-storia-api"))
    CHECK(api.handle(Req("GET", "/api/v1/archive", null, null)).status == 401, "API archivio: senza PIN 401")
    CHECK(api.handle(Req("POST", "/api/v1/archive", "000000", rec("abc", 1))).status == 401, "API archivio: PIN errato 401")
    CHECK(api.handle(CReq("PUT", "/api/v1/archive", null, "ctl-A", "Ctl%20A", null)).status == 405, "API archivio: metodo 405")
    var r = api.handle(CReq("POST", "/api/v1/archive", rec("20261122-093012-a1b2", 1795340000), "ctl-A", "Ctl%20A", null))
    CHECK(r.status == 200 && J(r)["ok"] == true && J(r)["id"] == "20261122-093012-a1b2", { "API archivio: salva -> {ok,id} " + String(r.body) })
    // salvare non richiede il comando: un secondo controller (non comanda) può salvare
    api.handle(CReq("POST", "/api/v1/control", "{\"action\":\"take\"}", "web-B", "Web%20B", null))
    r = api.handle(CReq("POST", "/api/v1/archive", rec("20261122-100000-c3d4", 1795342000, "Secondo"), "ctl-A", "Ctl%20A", null))
    CHECK(r.status == 200, "API archivio: salva anche chi non comanda")
    // seguace (saturimetro): non scrive, legge sì
    val ox = Req("POST", "/api/v1/archive", "482913", rec("oxi", 5)); ox.headers = ox.headers + ("x-simulafranz-device" to "oximeter")
    CHECK(api.handle(ox).status == 403, "API archivio: seguace non scrive")
    r = api.handle(CReq("GET", "/api/v1/archive", null, "ctl-A", "Ctl%20A", null))
    val items = J(r)["items"]
    CHECK(r.status == 200 && J(r)["ok"] == true && COUNT(items) == 2 && items[0]["id"] == "20261122-100000-c3d4", "API archivio: elenco, più recenti prima")
    CHECK(INT(items[0]["team_n"]) == 2 && items[0]["title"] == "Secondo" && HAS(items[0], "saved_w") && !HAS(items[0], "entries"), "API archivio: elenco con i riassunti")
    r = api.handle(CReq("GET", "/api/v1/archive", null, "ctl-A", "Ctl%20A", null).also { it.query = "id=20261122-093012-a1b2" })
    CHECK(r.status == 200 && J(r)["item"]["title"] == "Arresto in ambulanza" && COUNT(J(r)["item"]["team"]) == 2 && HAS(J(r)["item"], "entries"), "API archivio: record completo")
    r = api.handle(CReq("GET", "/api/v1/archive", null, "ctl-A", "Ctl%20A", null).also { it.query = "id=nonc-e" })
    CHECK(r.status == 404 && J(r)["ok"] == false, "API archivio: 404 se non c'è")
    r = api.handle(CReq("GET", "/api/v1/archive", null, "ctl-A", "Ctl%20A", null).also { it.query = "id=..%2Fregistro" })
    CHECK(r.status == 400, "API archivio: id non valido in lettura 400")
    CHECK(api.handle(CReq("POST", "/api/v1/archive", rec("bad/id", 1), "ctl-A", "Ctl%20A", null)).status == 400, "API archivio: id non valido 400")
    CHECK(api.handle(CReq("POST", "/api/v1/archive", "[1,2]", "ctl-A", "Ctl%20A", null)).status == 400, "API archivio: corpo non oggetto 400")
    CHECK(api.handle(CReq("POST", "/api/v1/archive", "{\"delete\":\"a/b\"}", "ctl-A", "Ctl%20A", null)).status == 400, "API archivio: cancellazione con id non valido 400")
    // corpo oltre 2 MB
    val big = rec("big", 1, extra = ",\"pad\":\"" + "x".repeat(kArchiveMaxBytes) + "\"")
    CHECK(api.handle(CReq("POST", "/api/v1/archive", big, "ctl-A", "Ctl%20A", null)).status == 413, "API archivio: oltre 2 MB 413")
    // cancellazione (anche ripetuta)
    r = api.handle(CReq("POST", "/api/v1/archive", "{\"delete\":\"20261122-093012-a1b2\"}", "ctl-A", "Ctl%20A", null))
    CHECK(r.status == 200 && J(r)["ok"] == true && COUNT(J(r)) == 1, "API archivio: cancellazione -> {ok:true}")
    CHECK(api.handle(CReq("POST", "/api/v1/archive", "{\"delete\":\"20261122-093012-a1b2\"}", "ctl-A", "Ctl%20A", null)).status == 200, "API archivio: cancellazione ripetuta va bene")
    CHECK(COUNT(J(api.handle(CReq("GET", "/api/v1/archive", null, "ctl-A", "Ctl%20A", null)))["items"]) == 1, "API archivio: resta un record")
}
