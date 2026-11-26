// Portato da tests/run-tests.m (monitor iPad)
// Test di logica di SimilFranz Monitor (moduli core-*, Kotlin puro). Esecuzione: scripts/gradle.sh :core-tests:run
// File: Support.kt (aiuti), TestsModello.kt (stato, transizioni, forme d'onda, allarmi), TestsApi.kt (API, controllo,
// registro, scenari), TestsDefib.kt (defibrillatore, SpO2/temperatura/refill, movimento, posizioni, aggiornamenti).
package it.simulafranz.coretests

import kotlin.system.exitProcess

private fun run(name: String, test: () -> Unit) {
    gTest = name
    try { test() } catch (e: Throwable) {
        gFail++
        println("FAIL $name: eccezione non prevista, i controlli successivi di questa funzione NON sono stati eseguiti: $e")
        e.printStackTrace(System.out)
    }
}

fun main() {
    // ANDROID (12 derivazioni 22/11): anteprima della carta 12D (solo sviluppo): SF_ECG12_ANTEPRIMA=<cartella>
    System.getenv("SF_ECG12_ANTEPRIMA")?.let { ecg12Anteprima(it); exitProcess(0) }
    // ANDROID (sincronia polso 22/11): solo la misura QRS -> polso (SF_POLSO=<minuti>), con la tabella
    System.getenv("SF_CURVE_ETCO2")?.let { curveEtco2(it); exitProcess(0) }   // ANDROID (EtCO2 visibile 24/11)
    System.getenv("SF_POLSO")?.let { polsoStampa(polsoSimula(it.toDoubleOrNull() ?: 30.0)); exitProcess(0) }
    run("testState", ::testState); run("testTransitions", ::testTransitions); run("testWaveforms", ::testWaveforms)
    run("testAlarms", ::testAlarms); run("testAPI", ::testAPI); run("testControl", ::testControl); run("testDefib", ::testDefib)
    run("testScenarios", ::testScenarios); run("testSessionLog", ::testSessionLog); run("testSpO2TempCrt", ::testSpO2TempCrt)
    run("testMotion", ::testMotion); run("testSites", ::testSites); run("testCuffError", ::testCuffError)
    run("testCrtAssess", ::testCrtAssess)   // ANDROID (audit 26/11, PUL-F5): testUpdates tolto con la pipeline .deb
    run("testAnalisi2111", ::testAnalisi2111)   // ANDROID (analisi 21/11)
    run("testFunzioni2111", ::testFunzioni2111) // ANDROID (funzioni 21/11)
    run("testExtraH2511", ::testExtraH2511) // ANDROID (extraH 25/11)
    run("testRcpDae2511", ::testRcpDae2511) // ANDROID (RCP e DAE 25/11)
    run("testIntegrazione2111", ::testIntegrazione2111) // ANDROID (funzioni 21/11): integrazione nel monitor, fine scenario
    run("testGuasti2211", ::testGuasti2211)     // ANDROID (guasti 22/11): malfunzionamenti simulati del dispositivo
    run("testOra2211", ::testOra2211)           // ANDROID (ora 22/11): ora di sistema sincronizzata (set_wall_clock)
    run("testStoria2211", ::testStoria2211)     // ANDROID (storia sim 22/11): archivio Storia Sim e /api/v1/archive
    run("testAvvio2211", ::testAvvio2211)       // ANDROID (avvio scenario 22/11): PRONTO → Avvia → pausa → fine → PRONTO
    run("testEtco2Rcp2211", ::testEtco2Rcp2211) // ANDROID (EtCO2 RCP 22/11): capnografia e ART in RCP, qualità RCP dettagliata
    run("testEtco2Visibile2411", ::testEtco2Visibile2411) // ANDROID (EtCO2 visibile 24/11): EtCO2 in arresto senza «Ventila»
    run("testFixAnalisi2211", ::testFixAnalisi2211) // ANDROID (fix analisi 22/11): BUG-09, BUG-11, F3
    run("testFixAnalisi2211c", ::testFixAnalisi2211c) // ANDROID (fix analisi 22/11, terzo giro): esito legato al numero della scarica
    run("testTransizioni2211", ::testTransizioni2211) // ANDROID (transizioni ritmo 22/11): preludio dei ritmi, ampiezza FV, numeri a schermo
    run("testEcg12_2211", ::testEcg12_2211)     // ANDROID (12 derivazioni 22/11): morfologie ECG, blocchi AV, carta 12D
    run("testPolso2211", ::testPolso2211)       // ANDROID (sincronia polso 22/11): QRS -> ART / pleth / bip, deficit, tracciati
    run("testDifetti2211", ::testDifetti2211)   // ANDROID (difetti clinici evidenti 22/11): IN/EM/OP/EF/CC
    run("testMancanti2211", ::testMancanti2211) // ANDROID (funzioni mancanti 22/11): trend, linea arteriosa, PVC, allarmi, ventilato…
    run("testRcpPeriodo2311", ::testRcpPeriodo2311) // ANDROID (BugFix 23/11): periodo RCP del DAE senza compressioni = RCP non in corso
    run("testAudit2611", ::testAudit2611)       // ANDROID (audit 26/11): fix dell'audit pre-release
    println("\nTOTALE: $gPass ok, $gFail falliti")
    exitProcess(if (gFail != 0) 1 else 0)
}
