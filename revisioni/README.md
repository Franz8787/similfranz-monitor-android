# Revisioni specialistiche simulate

> **Importante.** Le revisioni descritte qui **non** sono state fatte da professionisti in carne e ossa. Sono state condotte
> con **Claude Code** (Claude™ è un marchio di Anthropic, PBC), un assistente di programmazione basato sull'intelligenza artificiale: a ogni agente è stato
> assegnato con un prompt il ruolo e il punto di vista di uno specialista, poi l'autore ha guidato il lavoro e verificato
> ogni correzione. Sono uno strumento per trovare difetti, **non** una validazione clinica né uno studio sull'efficacia
> formativa. Se sei un professionista e trovi qualcosa che non torna, apri una segnalazione: è il contributo più prezioso.

similFranz Monitor è un simulatore per la simulazione sanitaria: **non è un dispositivo medico** e non va mai usato su
pazienti.

## Come sono state fatte (21–22/11/2026)

1. **Un ruolo per agente.** Ogni agente ha ricevuto il proprio punto di vista: quale specialista è, in quale contesto lavora,
   che cosa guarda per primo.
2. **Solo lettura.** I revisori hanno letto il codice del cuore del simulatore (ritmi, curve, allarmi, defibrillatore,
   scenari) e le schermate del monitor senza modificare nulla. Ogni rilievo indica dove sta nel codice, che cosa succede,
   perché conta in uno scenario e che cosa proporre.
3. **Gravità.** Alta = può rovinare uno scenario o ingannare l'istruttore; media = un partecipante esperto lo nota;
   bassa = rifinitura.
4. **Correzione e test.** L'autore ha scelto che cosa correggere. Ogni correzione ha un **test automatico** nel cuore del
   simulatore (`monitor/core-tests`), che gira a ogni modifica. Dove serviva, la correzione è stata provata anche sul tablet.

## I punti di vista

| Area | Ruoli simulati | Che cosa hanno guardato |
|---|---|---|
| Simulazione | esperto di simulazione sanitaria | conduzione dello scenario sotto pressione, pausa, fasi, esito delle scariche, debriefing |
| Rianimazione ed emergenza | anestesista-rianimatore; medico dell'emergenza territoriale | onde di polso, pletismografia, capnografia in RCP e dopo il ROSC, pacing, monitor-defibrillatore in ambulanza |
| Cardiologia | cardiologo clinico; cardiologo emodinamista; cardiochirurgo | pressioni, perfusione, sindromi coronariche, blocchi, cardioversione, paziente ventilato |
| Elettrofisiologia | elettrofisiologo | morfologie dei ritmi, transizioni, pacing con cattura elettrica e meccanica, ECG a 12 derivazioni |
| Infermieristica | infermiere di terapia intensiva; infermiere di area critica | allarmi tecnici e clinici, sonde staccate, linea arteriosa, trend, registro degli allarmi |
| Qualità | collaudatore; analista dell'usabilità | bug riprodotti con script, interfaccia in piedi, con una mano e con i guanti, leggibilità a distanza |

## Che cosa è cambiato

Sigle: IN infermieristica, EM emodinamica, OP onde di polso, EF elettrofisiologia, CC cardiologia e cardiochirurgia,
AE anestesia ed emergenza. Ogni riga ha il suo test automatico.

### Allarmi e sonde
| Rilievo | Correzione |
|---|---|
| IN-01 | «Sonda o elettrodi staccati» solo se il sensore era in uso; un sensore mai collegato lascia il parametro spento, senza suoni |
| IN-02 | Linea arteriosa usata e poi staccata: allarme tecnico «trasduttore scollegato», mai un allarme clinico rosso |
| IN-04 | Un nuovo allarme letale (FV, TV, asistolia, apnea) interrompe la pausa audio; quelli già presenti restano silenziati |
| IN-07 | Movimento marcato: «SpO₂ artefatto da movimento», non «bassa perfusione» |
| IN-11 | Con le sole piastre: allarmi di FV, TV e asistolia e FC dalle piastre, senza «elettrodi staccati» |
| IN-05 / IN-06 | Diario degli allarmi nel registro: inizio, priorità, fine, silenzio con tempo di risposta, limiti cambiati |
| IN-08 | Ritardi d'allarme per parametro; apnea dopo 20 s |
| IN-09 / IN-16 | Limiti separati per pressione invasiva e non invasiva, PAM, SpO₂ alta, temperatura; profili «shock» e «post-ROSC» |
| IN-10 | Elettrodo singolo, disturbo dell'ECG, inversione dei cavi delle braccia |
| IN-12 | Allarmi tecnici risolvibili con un tocco lungo (facoltativo), con il tempo nel registro |
| IN-18 | Testi tecnici nuovi o corretti: ricerca del polso, linea da verificare, tempo NIBP superato |
| IN-19 | NIBP automatica: la prima misura parte subito |

### Linea arteriosa, trend e parametri
| Rilievo | Correzione |
|---|---|
| IN-03 / OP-04 | Linea arteriosa: zero, livello del trasduttore, lavaggio rapido con onda quadra, curva sovra- o sottosmorzata, occlusione |
| IN-13 | Pressione venosa centrale con le sue onde, temperatura centrale, esami e bilancio in regia |
| IN-14 | Trend sul monitor, nelle regie, nel debriefing e nel verbale |
| CC-02 | Pressione media dal campione giusto: al ROSC non ricompare la pressione di prima dell'arresto |

### Perfusione, ROSC e capnografia
| Rilievo | Correzione |
|---|---|
| Perfusione | La pletismografia si riduce con la pressione media: «bassa perfusione» sotto ~55 mmHg, «segnale debole» sotto ~45, ritorno graduale |
| EM-05 | Al ROSC la SpO₂ cerca il segnale per ~8 s e riparte da un valore plausibile |
| EM-06 | Fase di ROSC con EtCO₂: vale il valore della fase, con il picco iniziale |
| EM-02 | Apnea con polso: la CO₂ si accumula, le prime insufflazioni sono alte, poi scende ventilando |
| OP-01 | Capnogramma in scala automatica (50/60, 75, 100, 150 mmHg) |
| OP-02 | Fasi del capnogramma con tempi realistici, plateau lungo a frequenza bassa |
| OP-05 | Variazione della pressione di polso con le insufflazioni, che cresce con l'indice di shock |
| AE-10 | EtCO₂ zero in apnea («tubo in esofago»): insufflazioni piatte |
| AE-11 | SpO₂ rialzata dall'istruttore in apnea: tiene; con ventilazioni adeguate non scende |
| AE-13 | Cambio della frequenza respiratoria: l'EtCO₂ si adegua in minuti, non di colpo |
| AE-14 | Arresto asfittico: EtCO₂ alta nelle prime ventilazioni della RCP, poi scende |
| AE-16 | Pletismografia a bassa perfusione piccola ma regolare |
| AE-12 / CC-04 | Paziente ventilato (ventilatore o pallone): atti regolari, nessuna apnea; scollegato = apnea |

### Ritmi, pacing e defibrillazione
| Rilievo | Correzione |
|---|---|
| EF-01 | Il QRS stimolato parte dallo spike |
| EF-02 | Blocco AV di III grado con pacing sopra soglia: ogni spike cattura |
| EF-12-01 | ECG a 12 derivazioni aggiornato durante il pacing con cattura |
| EF-03 | PEA stretta se nasce da un ritmo rapido (ipovolemia, causa ostruttiva), larga e lenta negli altri casi |
| EF-04 | Scarica efficace: pausa elettrica e primi complessi lenti che accelerano; cardioversione con pausa di recupero |
| EM-07 | Asistolia o PEA con cattura meccanica = circolo presente; perdita della cattura = arresto |
| AE-15 | Cattura elettrica senza cattura meccanica = arresto |
| CC-05 | Cattura meccanica su bradicardia ipotesa: la pressione sale; cardioversione efficace: la pressione migliora |
| CC-06 | L'istruttore può impostare dove va il paziente al ROSC se la causa è stata rimossa |
| AE-01 | Contatto delle piastre scarso durante il pacing: nessuna cattura e messaggio «controllare le piastre» |
| AE-03 | Soglia di cattura meccanica regolabile, di serie uguale a quella elettrica |
| AE-05 | In pausa compare il messaggio della pausa |
| AE-08 | Contatto delle piastre non ottimale: scarica erogata, impedenza alta nel registro |
| AE-09 | Cardioversione sincronizzata: il tasto di scarica va tenuto premuto fino all'onda R; rilasciato prima, nessuna scarica |
| AE-18 | Riga d'aiuto in RCP e silenzio degli allarmi per 2 minuti |

In tutto **44 correzioni**. Altre proposte dei revisori (per esempio pacing epicardico, farmaci con effetto automatico,
supporto meccanico al circolo) sono state lasciate fuori per scelta: il simulatore resta guidato dall'istruttore.

## Le prove fisiche del guscio

Le prove di stabilità, montabilità e stampabilità del guscio stampato in 3D sono nella cartella
[`stampa3d/verifiche/VERIFICHE.md`](../stampa3d/verifiche/VERIFICHE.md). Anche queste sono simulazioni al computer (motore fisico e calcoli sul
modello 3D): la prova sulla pedana segue il metodo della norma IEC 60601-1, ma **non è una certificazione** e il guscio
non è un dispositivo medico.

---

© 2026 Francesco Palmisano. Documento distribuito con licenza GPL-3.0 insieme al codice. Tutti i marchi citati
appartengono ai rispettivi titolari; il progetto è indipendente e non affiliato ad Anthropic né ad alcun produttore.
