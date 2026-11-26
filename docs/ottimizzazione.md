# Dedicare il tablet alla simulazione (facoltativo)

Se un tablet serve **solo** come monitor o controller di simulazione, conviene togliergli di mezzo tutto il resto:
meno memoria occupata e tracce più fluide, nessuna notifica o finestra che compare a metà scenario, batteria che dura di
più. È **facoltativo**: l'app funziona anche su un tablet normale.

> Fallo solo su un tablet **dedicato**, senza account personali né dati tuoi. Le operazioni si annullano con lo script
> di ripristino, e un ripristino di fabbrica rimette comunque tutto com'era.

## A mano, senza computer

1. **Modalità aereo** e poi riaccendi solo il **Wi-Fi** (serve al collegamento diretto, non serve una rete).
2. Impostazioni → **Notifiche**: spegnile per tutte le app tranne similFranz.
3. Impostazioni → **Display**: luminosità alta e fissa (il guscio copre il sensore di luce), spegnimento dello schermo il
   più lungo possibile.
4. Impostazioni → **Aggiornamento software**: disattiva il download automatico.
5. Impostazioni → **App**: disattiva quelle che non usi (giochi, social, posta, negozi, assistenti vocali).
6. Durante il corso tieni il tablet in carica o con la batteria piena.

## Con lo script (tablet Samsung)

Lo script `scripts/ottimizza-tablet.sh` fa il punto 5 in un colpo solo sul Galaxy® Tab Active2: disattiva circa
sessanta app e servizi che non servono (suite da ufficio, social, negozi, Bixby, meteo, sincronizzazioni, diagnostica
inviata al produttore, aggiornamenti automatici, la finestra «USB» che copre l'app quando colleghi il cavo) e spegne
l'NFC. Su altri modelli salta da solo i pacchetti che non esistono.

**Non tocca** Impostazioni, telefono e rubrica di sistema, Archivio (serve per gli APK), Chrome, fotocamera, galleria,
la **sintesi vocale** (serve alla voce dei DAE) e i servizi Google di base.

```bash
scripts/ottimizza-tablet.sh              # disattiva: le app restano sul tablet, spente
scripts/ottimizza-tablet.sh --cancella   # in più toglie le app dell'elenco CANCELLA (si possono rimettere)
scripts/ripristina-tablet.sh             # rimette tutto com'era
```

Serve il tablet collegato con il cavo e il Debug USB attivo (vedi [installazione](02-installazione.md), strada B).
Lo script mostra il modello e chiede conferma prima di fare qualsiasi cosa. Alla fine stampa la memoria libera.

Prima di lanciarlo leggi l'elenco nello script: se una delle app ti serve, togli la sua riga.

> **Responsabilità.** Disattivare app di sistema è un'operazione prevista da Android (`pm disable-user`) e reversibile,
> ma la fai sotto la tua responsabilità: l'autore non risponde di malfunzionamenti del tablet.

---
© 2026 Francesco Palmisano · GPL-3.0. Samsung® e Galaxy® sono marchi registrati di Samsung Electronics Co., Ltd.;
Android™ è un marchio di Google LLC.
