# Contribuire

Grazie dell'interesse. similFranz Monitor per Android nasce per formatori, istruttori e facilitatori di simulazione
sanitaria: le segnalazioni di chi lo usa in aula valgono quanto il codice.

## Segnalazioni

- **Problema** (qualcosa non funziona o si comporta in modo strano): apri una segnalazione con il modello «Problema».
- **Proposta** (una funzione, un ritmo, un layout, un testo da chiarire): usa il modello «Proposta».
- Per la sicurezza leggi [SECURITY.md](SECURITY.md): niente segnalazioni pubbliche.

## Modifiche al codice

- Le modifiche al cuore del simulatore (`monitor/core-*`) devono lasciare verdi tutti i test:
  `cd monitor && ./gradlew :core-tests:run`. Se correggi un comportamento, aggiungi il test che lo dimostra.
- I testi a schermo, i messaggi dell'API e i valori clinici si cambiano solo con un motivo spiegato nella richiesta.
- I layout devono restare originali: nessun nome, logo o frontale di dispositivi in commercio.
- Le richieste di modifica vengono riportate a mano nei sorgenti dell'autore e ripubblicate: per questo il commit
  finale può non coincidere con il tuo, ma il contributo viene citato.

Contribuendo accetti che il tuo lavoro sia distribuito con la licenza del progetto (GPL-3.0; modelli 3D CC BY-NC 4.0).

© 2026 Francesco Palmisano · GPL-3.0
