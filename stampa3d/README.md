# Guscio stampabile in 3D (Samsung® Galaxy® Tab Active2)

![Il guscio montato, visto di tre quarti](img/render_fronte.jpg)

Trasforma il tablet in un monitor trasportabile per la simulazione sanitaria: cornice scura, scocca con schermo inclinato
di 5°, maniglia incassata, sportello posteriore per i tasti laterali e borsa posteriore per bracciale, elettrodi e
sensore di saturazione.

| dietro, borsa e sportello chiusi | sportello aperto (tasti laterali) |
|---|---|
| ![Retro del guscio](img/render_retro.jpg) | ![Sportello aperto](img/render_sportello.jpg) |

## Il guscio stampato

Non è solo un progetto al computer: cornice, scocca, maniglia e tasca posteriore a due vani sono stati stampati e
montati sul tablet. Le foto qui sotto sono dei pezzi veri.

![Il tablet appoggiato nella cornice nera e, accanto, la scocca bianca vuota vista dall'alto](img/foto/cornice_e_scocca_aperte.jpg)

*Cornice e scocca aperte: il tablet entra nella cornice; nella scocca si vedono i quattro fori a goccia per i cavi, la
griglia dell'altoparlante e l'apertura dello sportello.*

| monitor montato e tablet controller | dentro la scocca |
|---|---|
| ![Il monitor nel guscio con i tasti blu di accensione, giallo CARICA e rosso di scarica; davanti il tablet controller](img/foto/monitor_e_controller_montati.jpg) | ![Colonna d'angolo della scocca con l'inserto a caldo in ottone](img/foto/scocca_interno_inserti.jpg) |
| I tre tasti fisici segnati a colori: accensione, CARICA, scarica (schermo sfocato nella foto). | Inserto a caldo M3 in ottone in una colonna della scocca. |

| retro, tasca a due vani aperta | retro con il controller |
|---|---|
| ![Retro del guscio con la tasca a due vani nera aperta e le calamite agli angoli](img/foto/retro_tasca_aperta.jpg) | ![Retro del guscio con la tasca aperta e il tablet controller davanti](img/foto/retro_tasca_e_controller.jpg) |
| Tasca a due vani in PLA ([tasca_due_vani/](tasca_due_vani/)), sportello aperto: calamite agli angoli. | Lo stesso retro, con il controller in primo piano. |

## I pezzi

Tutti i pezzi in un colpo d'occhio: [tavola dei pezzi](img/pezzi/tavola_pezzi.jpg).

| | pezzo | file | materiale |
|---|---|---|---|
| <img src="img/pezzi/cornice.jpg" width="160" alt="cornice"> | 1 · cornice | [`tab_cornice_v5.stl`](stl/tab_cornice_v5.stl) | PLA scuro |
| <img src="img/pezzi/scocca.jpg" width="160" alt="scocca"> | 2 · scocca | [`tab_scocca_v4.stl`](stl/tab_scocca_v4.stl) | PLA chiaro, 8 inserti a caldo |
| <img src="img/pezzi/maniglia.jpg" width="160" alt="maniglia"> | 3 · maniglia | [`tab_maniglia_v4.stl`](stl/tab_maniglia_v4.stl) | PLA scuro |
| <img src="img/pezzi/sportello.jpg" width="160" alt="sportello"> | 4 · sportello | [`tab_sportello_v3.stl`](stl/tab_sportello_v3.stl) | PLA chiaro |
| <img src="img/pezzi/borsa_posteriore.jpg" width="160" alt="borsa posteriore"> | 5 · borsa posteriore | [`tab_borsa_posteriore_v3.stl`](stl/tab_borsa_posteriore_v3.stl) | TPU 95A, 4 dadi M3 |
| <img src="img/pezzi/coperchio_borsa.jpg" width="160" alt="coperchio della borsa"> | 6 · coperchio della borsa | [`tab_coperchio_borsa_v3.stl`](stl/tab_coperchio_borsa_v3.stl) | TPU 95A |
| <img src="img/pezzi/borsa_PLA.jpg" width="160" alt="borsa rigida"> | 7 · borsa rigida (facoltativa, al posto della 5) | [`tab_borsa_PLA_v2.stl`](stl/modulo_borsa_PLA/tab_borsa_PLA_v2.stl) | PLA |
| <img src="img/pezzi/coperchio_PLA.jpg" width="160" alt="coperchio rigido"> | 8 · coperchio rigido (facoltativo, al posto del 6) | [`tab_coperchio_PLA_v2.stl`](stl/modulo_borsa_PLA/tab_coperchio_PLA_v2.stl) | PLA |

Gli STL sono già orientati per la stampa.

**Tasca posteriore a due vani (prototipo stampato).** Un vano stretto per la busta delle piastre e una tasca con
sportello a cerniera per i cavi, sugli stessi fori della borsa. È stampata e montata (foto sopra), ora in prova
d'uso: [tasca_due_vani/](tasca_due_vani/).

## Montaggio e verifiche

- **Viteria**: inserti a caldo M3×5×5 nelle parti in PLA, dadi solo nella borsa in TPU.
- **Montaggio passo per passo, orientamento e tempi di stampa**: [MONTAGGIO.md](MONTAGGIO.md) (con l'esploso e
  un'immagine per ogni passo).
- **Videotutorial**: [▶ Guarda il videotutorial di montaggio](https://franz8787.github.io/similfranz-monitor-android/#montaggio) sulla pagina del progetto (un minuto e
  mezzo, 7 passi). Il file è [docs/video/montaggio.mp4](../docs/video/montaggio.mp4): GitHub non mostra in anteprima i
  video di queste dimensioni, va scaricato.

  [![Guarda il videotutorial di montaggio](img/montaggio_video_poster.jpg)](https://franz8787.github.io/similfranz-monitor-android/#montaggio)
- **Verifiche** (stabilità sulla pedana inclinata, montabilità, controllo delle mesh):
  [verifiche/VERIFICHE.md](verifiche/VERIFICHE.md). Sono simulazioni al computer: la prova sulla pedana segue il metodo
  della norma IEC 60601-1, ma non è una certificazione. Il montaggio, invece, è stato provato sui pezzi stampati.

Le posizioni di tasti, fotocamera e prese vengono dalla scheda tecnica del produttore (±0,5 mm): prima di stampare tutto
conviene una prova della sola cornice.

Licenza: CC BY-NC 4.0 ([LICENSE](LICENSE)). Galaxy® e Samsung® sono marchi registrati di Samsung Electronics Co., Ltd.
Progetto indipendente, non affiliato.
