// Portato da tests/run-tests.m (monitor iPad)
// Parte comune: contatore CHECK, orologio iniettato gNow, funzioni di aiuto (NewState, Cmd, Sensor, RunNibp, Tick, Req, J…).
package it.simulafranz.coretests

import it.simulafranz.core.*
import java.io.File
import java.util.Locale

var gPass = 0
var gFail = 0
var gTest = ""          // funzione di test in corso (sull'iPad: __FILE__:__LINE__)

fun CHECK(cond: Boolean, message: String) {
    if (cond) gPass++ else { gFail++; println("FAIL $gTest: $message") }
}
// messaggio con valori: calcolato solo se il controllo fallisce (come gli argomenti di fprintf nella macro dell'iPad)
fun CHECK(cond: Boolean, message: () -> String) {
    if (cond) gPass++ else { gFail++; println("FAIL $gTest: ${message()}") }
}
fun F(format: String, vararg args: Any?): String = String.format(Locale.US, format, *args)

var gNow = 1000.0

// NSString **err dell'iPad: resta l'ultimo errore ricevuto
class Err {
    var v: String? = null
    fun has(needle: String): Boolean = v?.contains(needle) == true     // [err containsString:] (NO se nil)
    override fun toString(): String = v ?: "(null)"
}
// esito "String? = errore oppure null" -> BOOL + err dell'iPad
fun Ok(e: String?, err: Err? = null): Boolean { if (e != null && err != null) err.v = e; return e == null }

// monitoraggio base collegato (ECG, SpO2, NIBP): premessa di molti test
fun BaseSensors(s: SimulationState) {
    for (k in listOf("ecg", "spo2", "nibp")) Cmd(s, "set_sensor", mapOf("sensor" to k, "connected" to true), null)
}
fun NewStateRaw(): SimulationState {
    val s = SimulationState("monitor-01")
    s.clock = { gNow }
    return s
}
fun NewState(): SimulationState { val s = NewStateRaw(); BaseSensors(s); return s }
fun Reset(s: SimulationState) { s.resetScenario(); BaseSensors(s) }
fun Cmd(s: SimulationState, c: String, v: Any? = null, err: Err? = null): Boolean {
    val d = LinkedHashMap<String, Any?>()
    d["command"] = c
    if (v != null) d["value"] = v
    return Ok(s.applyCommand(d), err)
}
fun Sensor(s: SimulationState, name: String, on: Boolean): Boolean {
    return Cmd(s, "set_sensor", mapOf("sensor" to name, "connected" to on), null)
}
fun RunNibp(s: SimulationState) {
    s.startNibpMeasurement()
    gNow += 60; s.tickAtTime(gNow)
}
fun Tick(s: SimulationState, sec: Double) { var x = 0.0; while (x < sec) { gNow += 0.5; s.tickAtTime(gNow); x += 0.5 } }
fun Tick(s: SimulationState, sec: Int) = Tick(s, sec.toDouble())

// --- dizionari "alla NSDictionary": un messaggio a nil vale nil / 0 / NO ---
@Suppress("UNCHECKED_CAST")
operator fun Any?.get(key: String): Any? = (this as? Map<Any?, Any?>)?.get(key as Any?)
operator fun Any?.get(index: Int): Any? = (this as? List<*>)?.getOrNull(index)
@Suppress("UNCHECKED_CAST")
fun HAS(d: Any?, key: String): Boolean = (d as? Map<Any?, Any?>)?.containsKey(key as Any?) == true   // d[key] != nil (NSNull = presente)
@Suppress("UNCHECKED_CAST")
fun ISNULL(d: Any?, key: String): Boolean { val m = d as? Map<Any?, Any?> ?: return false; return m.containsKey(key as Any?) && m[key as Any?] == null }   // [d[key] isKindOfClass:[NSNull class]]
fun BOOL(v: Any?): Boolean = when (v) { is Boolean -> v; is Number -> v.toDouble() != 0.0; else -> false }
fun INT(v: Any?): Int = when (v) { is Boolean -> if (v) 1 else 0; is Number -> v.toLong().toInt(); is String -> v.trim().toDoubleOrNull()?.toInt() ?: 0; else -> 0 }
fun LONG(v: Any?): Long = when (v) { is Boolean -> if (v) 1 else 0; is Number -> v.toLong(); else -> 0 }
fun DBL(v: Any?): Double = when (v) { is Number -> v.toDouble(); is String -> v.trim().toDoubleOrNull() ?: 0.0; else -> 0.0 }
fun COUNT(v: Any?): Int = when (v) { is Map<*, *> -> v.size; is Collection<*> -> v.size; else -> 0 }
fun LEN(v: Any?): Int = (v as? String)?.length ?: 0
fun STR(v: Any?): String? = v as? String
fun HASSTR(v: Any?, needle: String): Boolean = (v as? String)?.contains(needle) == true      // [v containsString:]
fun HASOBJ(v: Any?, o: Any?): Boolean = (v as? Collection<*>)?.contains(o) == true           // [v containsObject:]

// --- HTTP ---
fun Req(m: String, p: String, token: String?, body: String?): SLHTTPRequest {
    val r = SLHTTPRequest()
    r.method = m; r.path = p; r.remoteAddress = "192.168.4.9"
    val h = LinkedHashMap<String, String>()
    if (token != null) h["x-simulafranz-token"] = token
    if (body != null) { h["content-type"] = "application/json"; r.body = body.toByteArray(Charsets.UTF_8) }
    r.headers = h
    return r
}
@Suppress("UNCHECKED_CAST")
fun J(r: SLHTTPResponse): Map<String, Any?>? = Json.parseOrNull(String(r.body, Charsets.UTF_8)) as? Map<String, Any?>

// richiesta di un controller con identità (header X-SimulaFranz-Client*) da un certo indirizzo
fun CReq(m: String, p: String, body: String?, cid: String?, name: String?, remote: String?): SLHTTPRequest {
    val r = Req(m, p, "482913", body)
    val h = LinkedHashMap(r.headers)
    if (cid != null) h["x-simulafranz-client"] = cid
    if (name != null) h["x-simulafranz-client-name"] = name
    r.headers = h; if (remote != null) r.remoteAddress = remote
    return r
}

@Suppress("UNCHECKED_CAST")
fun SCJ(json: String): Map<String, Any?> = Json.parseOrNull(json) as Map<String, Any?>

// cartella temporanea pulita (sull'iPad: un file in NSTemporaryDirectory, tolto prima di cominciare)
fun TempDir(name: String): File {
    val d = File(System.getProperty("java.io.tmpdir"), name)
    d.deleteRecursively()
    return d
}

// ANDROID (transizioni ritmo 22/11): cambio di ritmo DI COLPO ("onset": "immediate"), come prima del preludio fisiologico.
// I test che non riguardano l'insorgenza dei ritmi (defibrillatore, curve, allarmi...) lo usano per partire dal ritmo nuovo.
fun RhythmNow(s: SimulationState, r: Any?, err: Err? = null): Boolean =
    Ok(s.applyCommand(linkedMapOf("command" to "set_rhythm", "value" to r, "onset" to "immediate")), err)
