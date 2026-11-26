# La regia web: guida alla pagina del computer

> La regia web comanda un simulatore per la **simulazione sanitaria**: non è un dispositivo medico e non va mai usata su
> persone.

La regia web è una pagina servita dal monitor stesso: la apri nel browser di un computer, senza installare niente. È la
stessa regia del [controller](controller.md), con uno schermo grande. Per il giorno del corso leggi la
[guida all'uso](03-uso.md).

## Indice

1. [Quando usarla](#1-quando-usarla)
2. [Accesso](#2-accesso)
3. [Struttura della pagina](#3-struttura-della-pagina)
4. [Modi di applicazione](#4-modi-di-applicazione)
5. [Stati e scenari](#5-stati-e-scenari)
6. [Il paziente: sonde sulla figura](#6-il-paziente-sonde-sulla-figura)
7. [Defibrillatore](#7-defibrillatore)
8. [Allarmi e altri pannelli](#8-allarmi-e-altri-pannelli)
9. [Registro e debriefing](#9-registro-e-debriefing)
10. [Storia Sim e PDF](#10-storia-sim-e-pdf)
11. [Sala dal computer](#11-sala-dal-computer)
12. [Comando condiviso](#12-comando-condiviso)
13. [Vedi anche](#vedi-anche)

## 1. Quando usarla

- Hai un **computer** al banco di regia e vuoi tutti i pannelli in vista insieme.
- Un **secondo facilitatore** vuole annotare nel registro mentre un altro conduce dal tablet.
- Prepari gli scenari con più comodità (tastiera e schermo grandi).

Per condurre in mano usa l'app controller sul tablet.

## 2. Accesso

1. Computer e monitor devono essere sulla **stessa rete Wi-Fi®**. (Se il monitor non è su nessuna rete, scegli la rete da
   **menu ☰ → Informazioni e collegamenti → Reti Wi-Fi…**.)
2. Sul monitor apri **menu ☰ → Informazioni e collegamenti**: leggi l'**indirizzo** (del tipo `http://…`) e il **PIN**.
3. Scrivi l'indirizzo nel browser del computer. Nella schermata «similFranz Controller» scrivi il **PIN** e tocca
   **Entra**. Se il PIN è sbagliato lo dice («PIN non valido»); troppi tentativi bloccano per mezzo minuto.
4. **Esci** (in alto) chiude la sessione.

Si usa **solo da computer**: da telefono o tablet la pagina non si apre e rimanda all'app controller.

## 3. Struttura della pagina

- **In alto**: titolo «similFranz Controller → monitor», **SIMULAZIONE**, stato del collegamento, pulsante **Sala**,
  **Esci**. Se un'altra regia comanda compare «Comanda …: da qui puoi solo osservare» con **Prendi il comando**.
- **Tessere dei valori**: **Ritmo**, **FC bpm**, **PA mmHg**, **SpO₂ %**, **FR · EtCO₂**, **Temp · Refill**,
  **Defibrillatore**. Con un clic le regoli.
- **Azioni**: **▶ RCP**, **Ventila**, **Misura NIBP**, **Silenzia 20 s**, **Tocco operatore** (artefatto per 3 secondi),
  **Movimenti** (*Fermo*, *Lievi*, *Marcati*, con durata).
- **Schede dei pannelli** (barra con i nomi): **Ritmo · FC**, **Pressione**, **Respiro**, **Esame · Refill**,
  **Fisiologia**, **Defibrillatore**, **Monitor**, **Saturimetro**, **Allarmi**, **Linee · PVC**, **Ventilazione**,
  **Distrattori**, **Esami**, **Trend**. Il pannello **Defibrillatore** ha un punto esclamativo quando richiede la tua
  decisione.
- **Colonna di destra**: **Stati** e **Scenario ▸**, il **Registro** con le osservazioni rapide.
- **Barra in basso**: tempo di scenario con **Azzera**, **II Pausa** (o **▶ Avvia** / **▶ Riprendi**), **■ FINE SCENARIO**,
  **Applica le modifiche** e **Azzera scenario…**.

Scorciatoie da tastiera (in **Monitor**, «Questa regia»): **P** pausa/riprendi, **R** RCP, **V** Ventila, **N** misura NIBP,
**S** silenzia, **A** avvia le modifiche in attesa. Non valgono mentre scrivi in un campo.

## 4. Modi di applicazione

Nella barra in basso, **Applica le modifiche**:

| Tasto | Che cosa fa |
|---|---|
| **Scatto · 0 s** | Il valore arriva all'istante. |
| **10 s**, **20 s**, **30 s**, **60 s**, **2 min**, o un numero tuo (campo «altro», in secondi) | Le modifiche restano **IN ATTESA**; con **▶ Avvia** partono insieme e arrivano insieme. **Scarta** le butta; **■ Interrompi** ferma una transizione in corso. |

**Torna a Scatto** riporta al modo immediato. Per avere lo stesso comportamento di «Subito» del controller (20 secondi)
scegli **20 s** e poi **▶ Avvia**. I ritmi di arresto partono comunque di colpo.

## 5. Stati e scenari

- **Stati**: «Un tocco prepara l'intero quadro clinico». **+ Salva lo stato attuale** fotografa i valori veri di adesso.
- **Scenario ▸**: elenco degli scenari salvati sul monitor, con **Avvia lo scenario**, **Vai a questa fase** e il
  costruttore. Lo scenario non avanza mai da solo.
- **Costruttore di scenari** (finestra a tutta altezza): nome, fasi, **+ Aggiungi fase**, **+ Fase dai valori attuali**,
  **Salva lo scenario**, **Duplica scenario**, **Elimina scenario**, **Chiudi**. Per ogni fase: ritmo, morfologia ECG,
  parametri, tempo di transizione e nota per l'istruttore.
- **Prima di «▶ Avvia»** (il tasto verde al posto di «II Pausa») il timer è fermo e non si registra niente.

## 6. Il paziente: sonde sulla figura

In fondo alla pagina, **«Paziente supino · dalla testa alle ginocchia»**. Clicca un punto segnato per applicare o spostare
il sensore:

| Punto | Sensore |
|---|---|
| fronte | temperatura |
| naso-bocca | capnografia |
| torace | ECG |
| sottoclavicola destra e fianco sinistro | piastre |
| braccio | bracciale |
| polso o inguine | arteria |
| dito | SpO₂ |

Il monitor mostra solo quello che è collegato. Un secondo clic stacca la sonda.

## 7. Defibrillatore

Scheda **Defibrillatore**: **Stato** con la modalità (*spento*, *DAE*, *manuale*, *pacer*).

Le scariche le fanno i partecipanti sul monitor; qui decidi che cosa succede dopo:

- **Esito delle scariche**: **Decido io** (poi risolvi dalla finestra di decisione con *→ Sinusale*, *Resta nel ritmo
  pre-scarica* o *Altro ritmo*) oppure **Regole automatiche**.
- **Defibrillazione · FV / TV senza polso**: **Scariche efficaci per convertire** (0 = mai), **Energia minima efficace**,
  **Ritmo dopo la conversione**.
- **Cardioversione · FA / TV con polso**: gli stessi tre e l'opzione «scarica NON sincronizzata su ritmo con polso → FV».
- **Pacing**: **Soglia di cattura (mA)**, **cattura elettrica possibile**, **la cattura genera polso e pressione**.
- **DAE e SYNC**: **Energia del DAE (J)**, **RCP tra due analisi (s)**, **istruzioni vocali del DAE**, **SYNC resta attivo
  dopo la scarica**.
- **Applica impostazioni** salva questi valori. Le piastre si applicano dalla figura del paziente.

Durante l'RCP il pannello **«Come vedi l'RCP?»** (**Batti il tempo**, profondità, rilascio, posizione delle mani, stanchezza,
**Cambio operatore**, **Interrompi RCP**) funziona come sul controller.

## 8. Allarmi e altri pannelli

- **Allarmi**: tabella dei limiti con attivo/spento, **Applica limiti**, **Ripristina predefiniti**; limiti separati ART e
  NIBP, profili (*ICU shock*, *Post-ROSC*), **ritardi d'allarme** con **Applica ritardi**, **Tutti a 0**, **Di serie**.
- **Linee · PVC**: linea arteriosa (smorzamento, livello, occlusione, **Zero**, **Lavaggio rapido**), **PVC**, temperatura
  centrale **T2**.
- **Ventilazione**: *Respiro spontaneo*, *Ventilatore*, *Pallone*; frequenza, volume, frequenza in RCP, PPV.
- **Distrattori**: elettrodi ECG, tracciato disturbato, cavi invertiti, allarmi tecnici simulati, **Contatto delle piastre**
  (*Buono*, *Non ottimale*, *Scarso*).
- **Saturimetro**: solo se hai il saturimetro palmare collegato, con i suoi limiti.
- **Esami**: **Invia al registro** (gli esami si dicono a voce: finiscono nel registro, non sul monitor).
- **Trend**: storia dei parametri a schermo, con passo 1, 5 o 15 min.
- **Monitor**: layout del monitor (le stesse categorie del menu ☰: **Default** e **Personalizzati**), audio e volume, le
  scorciatoie da tastiera e l'**aggiornamento delle app**.

## 9. Registro e debriefing

- Il **Registro** (colonna di destra, con **Espandi** / **Chiudi**) mostra le voci: ciò che fanno i partecipanti, i ritmi,
  l'arresto e il ROSC, le fasi, i tuoi comandi. Sono le stesse voci che vede il controller.
- **Osservazioni dell'istruttore**: i gruppi **Riconoscimento**, **RCP**, **Vie aeree e accessi**, **Farmaci** e
  **Debriefing** (*Punto di forza…*, *Da discutere…*, *Nota libera…*); un campo **Nota** con **Registra**.
- **Debriefing** apre il **Piano di debriefing**: i tempi dall'arresto, scariche, farmaci, punti segnati e la cronologia,
  il **Titolo**, **Squadra ed esito** (nome, ruolo *Medico*, *Infermiere*, *OTAS*, **Obiettivi raggiunti**, **Da
  rinforzare**) e la scelta della **Sessione**. **Scarica** salva la sessione in un file .txt; **Nuova sessione**,
  **Elimina**, **Elimina tutte le sessioni** per la pulizia.
- **■ FINE SCENARIO** (con conferma) mette il monitor in «Scenario terminato», in pausa, e apre il piano. Alla chiusura
  scegli **Salva in Storia Sim** o **Non salvare**: in entrambi i casi lo scenario si azzera da solo.
- Se il monitor non risponde, il debriefing resta disponibile e la simulazione salvata resta in coda in questo browser.

> **Privacy.** Nomi e ruoli dei partecipanti sono dati personali: tratta, conserva e condividi secondo il GDPR e le regole
> della tua struttura. La Storia Sim e il file .txt contengono quello che hai scritto.

**Centrale Operativa.** Se i partecipanti trasmettono l'ECG a 12 derivazioni da extraH, compare l'avviso con «Rispondi
come centrale»: «Centralizzare il paziente», «Non centralizzare il paziente» o testo libero. La risposta si vede sul
monitor e resta nel registro.

## 10. Storia Sim e PDF

**Storia Sim** elenca le simulazioni salvate con **Aggiorna** e **Chiudi**. Aprine una per rivederla (sola lettura),
**PDF** per stamparla e **Elimina**. Il **PDF** si crea con la **stampa del browser**: si apre una pagina impaginata come
verbale (A4, margini 2 cm); nella finestra di stampa scegli «Salva come PDF». Se il browser blocca la finestra, consenti
le finestre a comparsa per questa pagina e riprova.

## 11. Sala dal computer

**Sala** (in alto) apre «Sala in diretta»: l'immagine della fotocamera frontale e l'audio del microfono del monitor. Tasti:
**Ascolta** (l'audio parte spento), **Volume**, **Qualità: normale / bassa**, **Ingrandisci**, **Chiudi**, **Chiudi la
sala**. Quando ascolti, in alto compare «Audio sala acceso · Spegni».

- La vedi **solo se hai il comando**; con un'altra regia al comando compare **Prendi il comando**.
- È **solo una diretta**: niente viene registrato né salvato. Sul monitor compare «● IN DIRETTA» finché guardi o ascolti.

> **Privacy.** Informa i partecipanti e raccogli il loro consenso prima di usare la Sala, secondo il GDPR e le regole della
> tua struttura.

## 12. Comando condiviso

Comanda **una regia alla volta**, anche se mescoli controller e regia web.

- Chi non comanda vede «Comanda …: da qui puoi solo osservare» e **può annotare nel registro**.
- **Prendi il comando** (poi «Riprendi il comando» se l'hai perso) cambia chi comanda; il monitor avvisa chi lo perde e il
  passaggio resta nel registro.
- Se il monitor chiede di nuovo il PIN (riavviato, PIN cambiato, troppi tentativi), la pagina torna alla schermata di
  accesso; il debriefing funziona lo stesso.

## Vedi anche

- [Guida all'uso](03-uso.md)
- [Il monitor](monitor.md)
- [Il controller](controller.md)
- [Layout personalizzati](layout-personalizzati.md)

---
© 2026 Francesco Palmisano · GPL-3.0. Samsung® e Galaxy® sono marchi registrati di Samsung Electronics Co., Ltd.;
Android™ è un marchio di Google LLC; Wi-Fi® e Wi-Fi Direct® sono marchi registrati di Wi-Fi Alliance. Progetto
indipendente, non affiliato ai titolari dei marchi.
