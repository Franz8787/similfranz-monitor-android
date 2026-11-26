# Istruzioni per Claude Code

Progetto: similFranz Monitor per Android, un monitor-defibrillatore **simulato** per la simulazione sanitaria. Due app:
`monitor/` (il tablet che vedono i partecipanti) e `controller/` (la regia). Rispondi all'utente in italiano, con passi
brevi: spesso non è uno sviluppatore. Guide per lui in `docs/`.

## Se l'utente chiede di installare

1. **Strumenti.** Serve `adb` (Android Studio o i platform-tools). Per compilare serve anche Java 17 (incluso in
   Android Studio). `source scripts/env.sh` li cerca da solo.
2. **APK.** Chiedi se ha scaricato gli APK dalla pagina Releases. Se sì, mettili in `apk/` come
   `similfranz-monitor.apk` e `similfranz-controller.apk`; se no, lancia `scripts/compila.sh`.
3. **Un tablet alla volta**, prima il monitor poi il controller:
   - fagli attivare Opzioni sviluppatore e Debug USB (`docs/02-installazione.md`, strada B), collegare il cavo e
     toccare «Consenti»;
   - verifica con `adb devices` che ce ne sia uno solo;
   - `scripts/installa.sh monitor` (o `controller`) e controlla che finisca con `OK`.
4. **Collegamento.** Sul monitor menu ☰ → Informazioni e collegamenti: PIN. Sul controller tocca il monitor nell'elenco e scrivi il PIN.
   Wi-Fi acceso su entrambi, nessuna rete necessaria.
5. **Tasti del guscio** (solo monitor, se usa il guscio): `scripts/tasti-guscio.sh attiva` abilita ACCENSIONE, CARICA e
   SCARICA dai tasti fisici, l'uscita alla Home con tre pressioni e «Blocca schermo». Spiega che sono due permessi di Android e chiedi un sì.
6. **Ottimizzazione**: è facoltativa. Proponila (`docs/ottimizzazione.md`), spiega che disattiva app e che si annulla
   con `scripts/ripristina-tablet.sh`, e lanciala solo dopo un sì esplicito, su un tablet dedicato.

## Regole

- Non presentare mai il progetto come dispositivo medico.
- I layout devono restare originali: nessun nome, logo o frontale di dispositivi in commercio.
- Non chiedere né scrivere password o dati personali; non inviare dati a servizi esterni.
- La Sala (videocamera e microfono) richiede il consenso dei partecipanti: ricordalo quando ne parli.
- Per modificare il cuore: test `cd monitor && ./gradlew :core-tests:run`, devono passare tutti.
