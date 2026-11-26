# Guida all'uso

Per formatori, istruttori e facilitatori di simulazione. Qui trovi tutto quello che serve dal giorno prima del corso al
debriefing. Per un esempio completo, passo per passo, leggi il [caso d'uso](04-caso-d-uso.md).

> similFranz Monitor è un simulatore per la **simulazione sanitaria**: non è un dispositivo medico, non misura e non eroga
> nulla, e non va mai usato su persone.

Guide per dispositivo: [monitor](monitor.md) · [controller](controller.md) · [regia web](regia-web.md)

Preferisci vederlo? Il [videotutorial](https://franz8787.github.io/similfranz-monitor-android/#videotutorial) mostra controller e monitor affiancati,
capitolo per capitolo, con le pause per provare sui tuoi tablet. Nel video l'immagine della Sala, il nome della rete e
l'indirizzo sono sfocati per riservatezza.

## 1. I pezzi del sistema

| Pezzo | Dove gira | Chi lo usa |
|---|---|---|
| **Monitor** | Samsung® Galaxy® Tab Active2 nel guscio | i partecipanti: è il monitor-defibrillatore dello scenario |
| **Controller** | un secondo Tab Active2 (o un altro tablet Android™ 8 o successivo) | tu: è la regia, in mano o sul banco |
| **Regia web** | il browser di un computer | tu o un secondo facilitatore, seduti al banco di regia |

Puoi usare il controller, la regia web o tutti e due insieme. Comanda una regia alla volta: le altre osservano e
annotano nel registro (vedi §8).

## 2. Il giorno prima

- **Carica** i due tablet. Il monitor regge una sessione; per una giornata intera tieni a portata il caricatore USB-C.
- **Prepara lo scenario** nel controller (vista «Stati e scenari» → «Nuovo scenario»): fasi, ritmi, parametri, note per
  te. Lo trovi pronto il giorno dopo.
- **Prova il collegamento** fra i due tablet (§3) e, se la usi, la regia web dal computer che porterai.
- **Stampa** il guscio, se non l'hai già, e controlla che nella borsa posteriore ci siano bracciale, elettrodi e sensore
  di saturazione finti (servono come oggetti di scena: il monitor non legge nulla).

## 3. Accendere e collegare

1. Accendi il **monitor** e apri l'app *similFranz Monitor*. Lo schermo resta «spento»: lo accenderanno i partecipanti,
   come in reparto.
2. Sul monitor apri il menu **☰ → Informazioni e collegamenti**: leggi il **PIN**.
3. Accendi il **controller** e apri *similFranz Controller*: il monitor compare nell'elenco. Toccalo e scrivi il PIN.
   Il collegamento è **diretto, tablet con tablet** (Wi-Fi Direct®): nessuna rete, nessun internet. Tieni il Wi-Fi acceso
   su entrambi.
4. Il controller ricorda il monitor: le volte successive si ricollega da solo.

**Regia web (facoltativa).** Computer e monitor sulla stessa rete Wi-Fi®: nel menu ☰ del monitor leggi l'indirizzo, scrivilo
nel browser del computer, poi il PIN. Usala dal computer, non da telefono o tablet: è pensata per uno schermo grande.

## 4. Scegliere il layout

Il layout è il «dispositivo» che vedono i partecipanti. Lo scegli dal controller (tasto **Layout**), dalla regia web o dal
menu ☰ del monitor.

| Layout | Che cos'è | Quando usarlo |
|---|---|---|
| **ICU** | monitor da terapia intensiva con defibrillatore a scomparsa | reparto, terapia intensiva, sala operatoria |
| **extraH** | monitor-defibrillatore da emergenza territoriale | ambulanza, soccorso avanzato, pronto soccorso |
| **DAE** | defibrillatore semiautomatico con voce guida | corsi di BLSD, primo soccorso, laici |
| **Personalizzati** | i frontali che scrivi tu | vedi la [guida ai layout personalizzati](layout-personalizzati.md) |

Cambiare layout non cambia il paziente: ritmo e parametri restano quelli.

## 5. La schermata del controller

- **In alto, le tessere** con i valori veri del paziente: ritmo e FC, pressione, SpO₂, FR ed EtCO₂, temperatura e
  refill, terapia elettrica. Sotto ogni valore leggi che cosa vede chi è al monitor (per esempio «senza ECG» se gli
  elettrodi non sono collegati). **Tocca una tessera per regolarla.**
- **A sinistra**: Stati e scenari, Registro, Storia Sim.
- **Al centro**: il pannello del parametro scelto, con i valori rapidi, il cursore e i tasti − e +.
- **A destra**: le **sonde sul paziente**, la **rianimazione** (Avvia la RCP, Ventila), le **azioni rapide** (NIBP,
  Silenzia, Tocco), il **dispositivo del partecipante** (Accendi il dispositivo), Layout, **Sala** e «Allarmi, audio,
  movimenti».
- **In basso**: il tempo dello scenario con **Avvia**, **FINE SCENARIO** e la barra **APPLICA LE MODIFICHE** con
  **Subito** e **Prepara**.

### Subito o Prepara

- **Subito**: ogni modifica parte appena tocchi. La FC e gli altri valori arrivano al nuovo valore gradualmente, in circa
  20 secondi; i ritmi di arresto partono di colpo.
- **Prepara**: cambi più parametri, scegli una **durata** (per esempio 1 minuto) e tocchi **Applica**: partono insieme e
  arrivano insieme. È il modo per un deterioramento progressivo. I ritmi di arresto partono comunque subito.

### Le sonde

Il monitor mostra solo quello che è collegato, come nella pratica clinica. Quando vedi un partecipante applicare
elettrodi, sensore di saturazione, bracciale, capnografo, piastre o linea arteriosa, tocca la sonda corrispondente nella
griglia «Sonde sul paziente». Toccala di nuovo per staccarla: il monitor darà l'allarme tecnico giusto.

## 6. Condurre lo scenario

1. **Avvia**: parte il tempo dello scenario e il registro della sessione.
2. Se hai preparato uno scenario a fasi: in «Stati e scenari» aprilo e tocca **Avvia lo scenario**. Resti in una fase
   finché non decidi tu di passare alla successiva («Andare a questa fase»): lo scenario non avanza mai da solo.
3. Nel frattempo ritocchi a mano quello che serve. Gli **Stati** pronti (per esempio shock, insufficienza respiratoria,
   arresto in FV) applicano un intero quadro clinico con un tocco.
4. **Pausa** ferma lo scenario per un chiarimento; **Riprendi** lo fa ripartire.

### Arresto cardiaco

- Scegli il ritmo di arresto (fibrillazione ventricolare, TV senza polso, asistolia, PEA): parte subito, la tessera
  diventa rossa «senza polso».
- Quando iniziano le compressioni tocca **Avvia la RCP**: si apre il pannello «Come vedi l'RCP?» per dire al monitor
  com'è la qualità (frequenza, profondità, interruzioni).
- Tocca **Ventila** a ogni insufflazione: solo allora compaiono capnogramma ed EtCO₂, come nella realtà. Con via aerea
  avanzata o pallone automatico le ventilazioni possono andare da sole («Linee e ventilazione»).

### Defibrillazione e pacing

Le scariche le erogano i **partecipanti** sul monitor: carica, scarica, sincronizzata, pacing con soglia di cattura. A
ogni scarica il monitor fa il «cia-clack» dei relè e vibra (nel pacing, una vibrazione lieve a ogni impulso), e vibra
anche il tuo controller, pacing compreso: sai che la scarica è partita anche se stai guardando il gruppo. Nel
pannello «Esito delle scariche» scegli come rispondere:

- **Decido io dopo ogni scarica** (consigliato): dopo ogni scarica il controller ti chiede che cosa succede (resta il
  ritmo, ROSC, cambia ritmo). Sei tu a tenere lo scenario sugli obiettivi.
- **Esito di ogni scarica (sequenza)**: prepari in anticipo, per esempio «la prima no, la seconda ROSC».

Contatto delle piastre scarso, impedenza alta, cattura elettrica senza cattura meccanica: si regolano dal pannello del
defibrillatore e finiscono nel registro.

### Allarmi

Gli allarmi suonano sul monitor con la loro priorità. **Silenzia** dal controller li silenzia come farebbe un
partecipante. Limiti, ritardi, volume e movimenti del paziente (artefatti) sono in «Allarmi, audio, movimenti».

## 7. La Sala: audio e video in diretta

Se conduci da un'altra stanza, tocca **Sala** sul controller o nella regia web del computer: vedi e senti la sala
dalla fotocamera frontale e dal microfono del monitor. Arriva solo alla regia che ha il comando. È **solo una diretta**: nulla viene registrato o salvato. Finché la Sala è aperta, sul monitor compare sempre
**«● IN DIRETTA»**.

> **Privacy.** Prima di usare la Sala informa i partecipanti e raccogli il loro consenso, secondo il GDPR e le regole
> della tua struttura. Dillo anche nella familiarizzazione.

## 8. Più facilitatori

Puoi collegare più regie allo stesso monitor (un secondo tablet, la regia web). **Comanda una regia alla volta**: le
altre vedono «Comanda …: da qui osservi e annoti nel registro» e possono scrivere osservazioni. Per comandare tocca
**Prendi il comando**: chi lo perde lo vede subito, e ogni passaggio resta nel registro.

## 9. Registro, debriefing e Storia Sim

- **Da solo** il registro annota quello che fanno i partecipanti: sonde applicate, misure NIBP, cariche, scariche con
  l'energia, pacing, allarmi con i tempi di risposta.
- **Tu** aggiungi le osservazioni con un tocco nella vista **Registro**: arresto riconosciuto, chiamata d'aiuto, farmaci,
  vie aeree, **Punto di forza…** e **Da discutere…** con due parole.
- **FINE SCENARIO** (con conferma) apre il **piano di debriefing**: tempi dall'arresto al riconoscimento, alle
  compressioni, alla prima scarica e al ROSC; scariche, farmaci, punti segnati. Scrivi il titolo e, in **Squadra ed
  esito**, i partecipanti con il ruolo (Medico, Infermiere, OTAS) e per ciascuno «Obiettivi raggiunti» e «Da rinforzare».
- Chiudendo scegli **Salva in Storia Sim** oppure **Non salvare**: in entrambi i casi lo scenario si azzera, pronto per
  il gruppo successivo.
- **Storia Sim** raccoglie le simulazioni salvate. Ognuna è anche un **PDF** (cartella «Storia Sim» nei Documenti del
  controller, con un'icona sulla schermata Home) da aprire e condividere.

> Se nel verbale scrivi nomi di persone, trattali come dati personali: conservali e condividili secondo le regole della
> tua struttura.

## 10. Consigli dal campo

- **Tablet scollegati?** Il controller mostra una fascia gialla «Ricollegamento…» e mette in coda i comandi; se qualcosa
  non arriva lo dice in rosso. Il monitor continua lo scenario da solo.
- **Debriefing in un'altra stanza.** Dopo FINE SCENARIO puoi portare il controller in sala debriefing: il piano funziona
  anche lontano dal monitor.
- **Tablet nel guscio.** Non serve aprire lo sportello: accensione, carica e scarica si fanno anche dai tasti fisici, lo schermo si
  spegne dall'icona «Blocca schermo» (si riaccende con Home) e per uscire alla Home servono tre pressioni, così i
  partecipanti non escono per sbaglio. Vedi [Tasti fisici del tablet e schermo](monitor.md#tasti-fisici-del-tablet-e-schermo).
- **Volume dimenticato?** Se a scenario avviato il monitor è troppo forte o troppo piano, non entrare in sala: sul
  controller apri «Allarmi, audio, movimenti» e regola, in cima, **Volume generale** o **Volume del tablet monitor**.
- **Schermo.** Disattiva la luminosità automatica del monitor: il guscio copre il sensore di luce.
- **Non perdere la testa sui comandi.** Il dettaglio non è l'obiettivo: tieni lo sguardo sul gruppo e lascia che il
  registro faccia il suo lavoro.

---
© 2026 Francesco Palmisano · GPL-3.0. Samsung® e Galaxy® sono marchi registrati di Samsung Electronics Co., Ltd.;
Android™ è un marchio di Google LLC; Wi-Fi® e Wi-Fi Direct® sono marchi registrati di Wi-Fi Alliance. Progetto
indipendente, non affiliato ai titolari dei marchi.
