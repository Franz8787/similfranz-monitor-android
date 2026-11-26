// similFranz Controller (web) — servito da similFranz Monitor, same-origin, nessuna dipendenza esterna.
// v3: in alto i valori VERI del paziente (sempre visibili) con la riga "monitor:" per ciò che vede il discente;
//     a sinistra il paziente stilizzato (testa-ginocchia) solo per i sensori; al centro i controlli in una scheda unica.
(function () {
  'use strict';
  if (window.SF_PHONE) return;     // su telefoni e tablet la console non parte (vedi index.html)
  var RHYTHMS = {
    sinus: 'Sinusale', sinus_tachycardia: 'Tachicardia sinusale', sinus_bradycardia: 'Bradicardia sinusale',
    afib: 'Fibrillazione atriale', vt_pulse: 'TV con polso', vt: 'TV senza polso', vf: 'Fibrillazione ventricolare',
    asystole: 'Asistolia', pea: 'PEA'
  };
  var ARREST = ['vt_pulse', 'vt', 'vf', 'asystole', 'pea'];
  // ANDROID (affordance 22/11, W-01 / X-02): ritmi SENZA polso (la TV con polso non è un arresto): partono sempre subito, anche con
  // una durata scelta, come sul tablet controller; in arresto la tessera FC mostra la sigla del ritmo, non il numero
  var NOPULSE = ['vt', 'vf', 'asystole', 'pea'];
  var RCODE = { vt: 'TV', vf: 'FV', asystole: 'Asy', pea: 'PEA' };
  function isArrestR(r) { return NOPULSE.indexOf(r) >= 0; }
  // sensori: nome, colore (come sul monitor), scheda dei controlli da evidenziare, posizione predefinita
  var SENS = {
    ecg:   { name: 'ECG',     full: 'Elettrodi ECG',          color: '#33ff59', panel: 'patient', card: 'card_rhythm', def: 'chest' },
    spo2:  { name: 'SpO₂',    full: 'Sonda SpO₂',             color: '#33e6ff', panel: 'patient', card: 'card_resp',   def: 'finger_l2' },
    nibp:  { name: 'NIBP',    full: 'Bracciale NIBP',         color: '#f0557a', panel: 'patient', card: 'card_bp',     def: 'upper_arm_r' },
    art:   { name: 'ART',     full: 'Linea arteriosa',        color: '#ff3b30', panel: 'patient', card: 'card_bp',     def: 'radial_l' },
    etco2: { name: 'EtCO₂',   full: 'Capnografia',            color: '#ffe033', panel: 'patient', card: 'card_resp',   def: 'airway' },
    temp:  { name: 'Temp',    full: 'Sonda temperatura',      color: '#cc9eff', panel: 'patient', card: 'card_exam',   def: 'forehead' },
    pads:  { name: 'Piastre', full: 'Piastre defibrillatore', color: '#ff7a1a', panel: 'defib',   card: null,          def: 'chest' },
    oxi:   { name: 'Satur.',  full: 'Sensore del saturimetro', color: '#8fd6ff', panel: 'patient', card: 'panel_oxi',   def: 'finger_r2' }
  };
  var ORDER = ['ecg', 'spo2', 'nibp', 'art', 'etco2', 'temp', 'pads', 'oxi'];
  var FINGERS = ['pollice', 'indice', 'medio', 'anulare', 'mignolo'];
  // parametri regolabili: le transizioni ("in N s") valgono per quelli senza "now"
  var P = {
    heart_rate_bpm:       { l: 'FC', u: 'bpm', min: 0, max: 250, step: 1, q: [40, 60, 80, 100, 130, 160], c: 'hr' },
    spo2_percent:         { l: 'SpO₂', u: '%', min: 0, max: 100, step: 1, q: [75, 85, 90, 94, 98], c: 'spo2' },
    systolic_mmhg:        { l: 'Sistolica', u: 'mmHg', min: 40, max: 260, step: 1, c: 'art' },
    diastolic_mmhg:       { l: 'Diastolica', u: 'mmHg', min: 20, max: 160, step: 1, c: 'art' },
    respiratory_rate_rpm: { l: 'FR', u: 'atti/min', min: 0, max: 50, step: 1, q: [0, 8, 12, 16, 24, 32], c: 'co2' },
    etco2_mmhg:           { l: 'EtCO₂', u: 'mmHg', min: 0, max: 100, step: 1, q: [10, 20, 35, 45, 60], c: 'co2' },
    temperature_c:        { l: 'Temperatura', u: '°C · subito', min: 30, max: 43, step: 0.1, c: 'temp', now: 'set_temperature', dec: 1, q: [34, 35.5, 36.8, 38.5, 40] },
    crt_s:                { l: 'Refill capillare', u: 's · subito', min: 0.5, max: 8, step: 0.5, c: 'temp', now: 'set_crt', q: [1, 2, 3, 4, 6] }
  };
  var PNAME = { heart_rate_bpm: 'FC', spo2_percent: 'SpO₂', systolic_mmhg: 'PAs', diastolic_mmhg: 'PAd',
                respiratory_rate_rpm: 'FR', etco2_mmhg: 'EtCO₂' };
  var BPQ = [['Shock', 70, 40], ['Ipotesa', 90, 55], ['Normale', 120, 80], ['Ipertesa', 170, 100], ['Crisi', 220, 125]];
  var DCFG_NUM = ['shocks_to_convert', 'min_energy_j', 'cv_shocks_to_convert', 'cv_min_energy_j', 'capture_threshold_ma', 'aed_energy_j', 'aed_cpr_s'];
  var DCFG_BOOL = ['unsync_shock_vf', 'sync_stays_on', 'capture_possible', 'mechanical_capture', 'voice_prompts'];
  var DCFG_RHY = ['post_shock_rhythm', 'cv_post_rhythm'];
  var DMODE = { off: 'spento', aed: 'DAE', manual: 'manuale', pacer: 'pacer' };
  var LIMITS = ['hr_low', 'hr_high', 'spo2_low', 'sys_low', 'sys_high', 'rr_low', 'rr_high', 'etco2_low', 'etco2_high'];
  // pannelli della plancia: card dei parametri + pannelli secondari
  var CARDS = ['card_rhythm', 'card_bp', 'card_resp', 'card_exam', 'card_physio', 'panel_defib', 'panel_monitor', 'panel_oxi', 'panel_alarms'];

  var $ = function (id) { return document.getElementById(id); };
  var all = function (sel, root) { return Array.prototype.slice.call((root || document).querySelectorAll(sel)); };
  var token = sessionStorage.getItem('sf_token') || '';
  // collegamento con PIN già nel link: http://IP:porta/#pin=XXXXXX  (il frammento resta nel browser, non viaggia in rete)
  (function () {
    var m = /[#&]pin=([^&]+)/.exec(location.hash || '');
    if (!m) return;
    token = decodeURIComponent(m[1]);
    try { sessionStorage.setItem('sf_token', token); history.replaceState(null, '', location.pathname); } catch (e) {}
  })();
  // identità di questo controller: il monitor accetta i comandi di UN controller alla volta
  var clientId = '';
  try { clientId = localStorage.getItem('sf_client') || ''; } catch (e) {}
  if (!clientId) {
    clientId = 'web-' + Date.now().toString(36) + '-' + Math.random().toString(36).slice(2, 10);
    try { localStorage.setItem('sf_client', clientId); } catch (e) {}
  }
  var clientName = 'Web app · ' + (function () {
    var u = navigator.userAgent || '';
    if (/iPad/.test(u) || (/Macintosh/.test(u) && navigator.maxTouchPoints > 1)) return 'iPad';
    if (/iPhone|iPod/.test(u)) return 'iPhone';
    if (/Android/.test(u)) return 'Android';
    if (/Macintosh/.test(u)) return 'Mac';
    if (/Windows/.test(u)) return 'PC Windows';
    if (/Linux/.test(u)) return 'PC Linux';
    return 'browser';
  })();
  var ctlMine = false;      // diventa vero quando il monitor dice che comandiamo noi
  var state = null, pollTimer = null, failures = 0, editing = {}, limitsDirty = false, dcfgDirty = false;
  var mode = 0, pending = {}, pendingRhythm = null, cmdSeq = 0, POLL_MS = 500;
  var card = sessionStorage.getItem('sf_card') || 'card_rhythm', selSensor = null;
  if (CARDS.indexOf(card) < 0) card = 'card_rhythm';
  // ANDROID (insorgenza e ampiezza FV 22/11): come parte un ritmo nuovo — 'auto' = preludio fisiologico (di serie), 'immediate' = di
  // colpo. Vale per ogni cambio di ritmo di questa regia (clic, «▶ Avvia» di una preparazione, stati del paziente) e resta per la
  // sessione del browser. lastRhy = l'ultimo comando di ritmo mandato da qui (per «Subito ora»: si rimanda uguale, di colpo);
  // vfPick = ampiezza della FV scelta durante il preludio verso la FV (il monitor la applica all'arrivo)
  var onsetMode = 'auto';
  try { if (sessionStorage.getItem('sf_onset') === 'immediate') onsetMode = 'immediate'; } catch (e) {}
  var lastRhy = null, vfPick = null, monChangedAt = {}, monLastVal = {};

  // ---------- rete ----------
  // ANDROID (fix analisi 22/11, BUG-11): 503 = «troppe connessioni»: il monitor ha rifiutato la connessione PRIMA di leggere
  // la richiesta (niente eseguito), quindi si riprova da soli fino a 3 volte a 1 s (Retry-After). Solo dopo, chi chiama vede il
  // 503 (status 503, error 'busy') e lo dice come «monitor occupato», non come un rifiuto.
  // ANDROID (audit 26/11, RETE-R11): cmdId facoltativo = X-SimulaFranz-Cmd-Id, lo stesso in ogni ripetizione della stessa richiesta:
  // il monitor non riesegue un comando che ha già eseguito
  function api(method, path, body, tries, cmdId) {
    tries = tries || 0;
    var opt = { method: method, headers: { 'X-SimulaFranz-Token': token, 'X-SimulaFranz-Client': clientId,
      'X-SimulaFranz-Client-Name': encodeURIComponent(clientName), 'X-SimulaFranz-Client-Kind': 'web' }, cache: 'no-store' };
    if (cmdId) opt.headers['X-SimulaFranz-Cmd-Id'] = cmdId;
    if (body !== undefined) { opt.headers['Content-Type'] = 'application/json'; opt.body = JSON.stringify(body); }
    return fetch(path, opt).then(function (r) {
      if (r.status === 503 && tries < 3) {
        var ra = parseInt(r.headers.get('Retry-After'), 10), wait = (ra > 0 && ra < 5 ? ra : 1) * 1000;
        return new Promise(function (ok) { setTimeout(ok, wait); }).then(function () { return api(method, path, body, tries + 1, cmdId); });
      }
      return r.json().catch(function () { return { ok: false, error: 'HTTP ' + r.status }; })
        .then(function (j) { j._status = r.status; return j; });
    });
  }
  // fetch senza risposta (iPad uscito dal Wi-Fi) non deve bloccare il ciclo di lettura
  function withTimeout(p, ms) {
    return new Promise(function (ok, ko) {
      var t = setTimeout(function () { ko(new Error('timeout')); }, ms);
      p.then(function (v) { clearTimeout(t); ok(v); }, function (e) { clearTimeout(t); ko(e); });
    });
  }
  // ANDROID (fix analisi 22/11, BUG-07): command() risolve con l'ESITO (true = eseguito dal monitor). Rifiuto (423, 400, 409…),
  // monitor che non risponde o nessuna risposta entro CMD_TIMEOUT_MS = false, sempre con il messaggio a schermo; il motivo
  // resta in lastCmdErr per chi deve dirlo con parole sue. Chi registra o segna «fatto» lo fa solo con true.
  // ANDROID (debriefing offline web 22/11): lastCmdNet = l'ultimo comando non è arrivato per la RETE (monitor non raggiungibile,
  // nessuna risposta, PIN da reinserire), non per un rifiuto del monitor: chi può rimandarlo più tardi lo mette in sospeso
  var CMD_TIMEOUT_MS = 8000, lastCmdErr = '', lastCmdNet = false;
  function command(name, value, extra) {
    cmdSeq++;
    var body = { command: name };
    if (state) body.device_id = state.device_id;
    if (value !== undefined) body.value = value;
    if (extra) Object.keys(extra).forEach(function (k) { body[k] = extra[k]; });   // ANDROID (terzo giro): es. "shock" di defib_outcome
    var cmdId = 'web-' + Date.now().toString(36) + '-' + cmdSeq;       // ANDROID (audit 26/11, RETE-R11)
    return withTimeout(api('POST', '/api/v1/command', body, 0, cmdId), CMD_TIMEOUT_MS).then(function (j) {
      try { handle(j); } catch (e) { drawFail(e); }      // ANDROID (fix analisi 22/11, F3): il comando è arrivato lo stesso
      var ok = !!(j && j.ok);
      lastCmdNet = !ok && !!(j && (j._status === 401 || j._status === 429));
      lastCmdNet = lastCmdNet || !!(j && j._status === 503);       // ANDROID (fix analisi 22/11, BUG-11): occupato = transitorio
      lastCmdErr = ok ? '' : (j && j._status === 423 ? 'il monitor è comandato da un altro controller'
                            : j && j._status === 503 ? 'il monitor è occupato (troppe connessioni)' : ((j && j.error) || 'errore sconosciuto'));
      return ok;
    }, function (e) {
      var late = e && e.message === 'timeout';
      lastCmdNet = true;
      lastCmdErr = late ? 'nessuna risposta dal monitor entro ' + (CMD_TIMEOUT_MS / 1000) + ' s' : 'il monitor non risponde';
      msg('Comando non confermato: ' + lastCmdErr + (late ? ' (potrebbe essere arrivato lo stesso: controlla i valori)' : ''), true);
      return false;
    });
  }
  // fromPoll = risposta della lettura periodica dello stato (non di un comando)
  // ANDROID (fix analisi 22/11, F3): un errore di disegno dentro handle (da qualunque chiamata: poll, comandi, scenari, Avvia)
  // non deve diventare «monitor non raggiungibile» né «il monitor non risponde»: lo dice drawFail
  function handle(j, fromPoll) {
    try { handle0(j, fromPoll); } catch (e) { drawFail(e); }
  }
  function handle0(j, fromPoll) {
    if (!j) return;
    if (j._status === 401) { token = ''; sessionStorage.removeItem('sf_token'); loginOrShield('PIN non valido'); return; }
    if (j._status === 429) { loginOrShield('Troppi tentativi: riprova tra qualche secondo'); return; }
    if (j.controller) showControl(j.controller);
    if (j.scenarios) setScenarios(j.scenarios);
    if (j.scenario) onScenario(j.scenario);
    if (j.log_next !== undefined) logSync(j.log_next, j.log_session);
    if (j._status === 423) { msg('Comando non eseguito: il monitor è comandato da un altro controller', true); return; }
    if (j._status === 503) { msg('Monitor occupato (troppe connessioni): comando non eseguito, riprova tra un attimo', true); return; }   // ANDROID (fix analisi 22/11, BUG-11)
    // ANDROID (fix analisi 22/11, BUG-09): il monitor rifiuta il secondo esito della stessa scarica
    if (!j.ok && /no shock awaiting/.test(j.error || '')) { msg('Esito della scarica già deciso: nessuna scarica in attesa', true); return; }
    // ANDROID (fix analisi 22/11, terzo giro): esito mandato per una scarica che non è più quella in attesa
    if (!j.ok && /is not the one awaiting/.test(j.error || '')) { msg('Esito non applicato: nel frattempo è arrivata un\'altra scarica (rispondi alla domanda nuova)', true); return; }
    if (!j.ok) { msg('Errore: ' + ecgErrIt(j.error || 'sconosciuto'), true); return; }      // ANDROID (regia morfologie ECG 22/11): in italiano
    msgAutoClear(fromPoll);          // ANDROID (fix analisi 22/11, BUG-04): prima il poll cancellava ogni messaggio in ~0,2 s
    if (j.state) render(j.state);
  }
  // Un solo controller alla volta (regole sul monitor, SFControl): leggere non prende il comando, il primo comando sì;
  // chi comanda attivamente è protetto, chi è fermo da più di un minuto cede al primo comando di un altro.
  // Qui si vede sempre chi comanda, con che cosa e da dove; chi perde il comando sa a chi è passato.
  var CKIND = { web: 'web app', ipad: 'iPad', ipod: 'iPod', android: 'tablet' };
  function ago(s) { s = s || 0; return s < 90 ? s + ' s' : Math.round(s / 60) + ' min'; }
  function showControl(c) {
    var other = !!(c.active && !c.mine), idle = other && c.protected === false;
    if (other) {
      $('busyName').textContent = c.name || 'un altro controller';
      $('busyVia').textContent = ' (' + (CKIND[c.kind] || 'controller') + ', ' + (c.transport === 'direct' ? 'collegamento diretto' : 'Wi-Fi') + ')';
      $('busyHow').textContent = idle ? ', fermo da ' + ago(c.action_idle_s) + ': il tuo primo comando prende il comando.'
                                      : ': da qui puoi solo osservare.';
      $('takeCtl').textContent = c.lost_to ? 'Riprendi il comando' : 'Prendi il comando';      // ANDROID (affordance 22/11, SISTEMA §7): un solo termine nelle due regie
    }
    $('busy').hidden = !other;
    $('busy').classList.toggle('idle', idle);
    document.body.classList.toggle('observer', other && !idle);
    obsKeyboard();                 // ANDROID (fix analisi 22/11, terzo giro): in osservazione i tasti di regia fuori dalla tastiera
    if (other && ctlMine) msg(c.lost_to ? '«' + c.lost_to + '» ha preso il comando' : 'Il comando è passato a un altro controller');
    ctlMine = !!c.mine;
  }
  // ANDROID (fix analisi 22/11, terzo giro): sola osservazione anche da TASTIERA. Il CSS (body.observer, pointer-events:none)
  // fermava solo il mouse: con Tab + Invio/Spazio i tasti di regia partivano lo stesso (il monitor rispondeva 423). Ora gli stessi
  // controlli del CSS escono dall'ordine di Tab (tabindex -1, ripristinato quando si torna al comando), Tab li salta anche se sono
  // appena stati ridisegnati, e i tasti premuti su uno di loro (o il clic che la tastiera genera) non fanno niente e lo dicono.
  // Restano attivi: «Prendi il controllo», registro e note, Debriefing, Storia Sim, Sala, schede, pannello RCP in lettura.
  var OBS_SEL = '.quick button, .quick select, .rstates .railpane button, .rstates .railpane input, .rstates .railpane select, ' +
    '.plancia .decide, .plancia .decide button, .plancia .decide select, .plancia .decide input, ' +
    '.plancia .pcard button, .plancia .pcard input, .plancia .pcard select, .plancia .pcard .sw, .plancia .pcard textarea, ' +
    '#stage, #stage button, #stage [tabindex], #figExpand, .regiabar button, .regiabar input, .regiabar select, ' +
    '.rcppan .rcpsec button, .rcppan .rcpsec input, .rcppan #rcpModel, .rcppan #rcpStop, .plancia .prepband button';
  var OBS_FREE = '#cpr, #onsetSeg';      // ANDROID (insorgenza 22/11): la scelta dell'insorgenza non manda comandi
  function isObserver() { return document.body.classList.contains('observer'); }
  function obsBlocked(el) {
    if (!el || el.nodeType !== 1 || !isObserver()) return false;
    if (el.closest(OBS_FREE)) return false;
    return !!el.closest(OBS_SEL);
  }
  function obsKeyboard() {
    var on = isObserver();
    if (on) all(OBS_SEL).forEach(function (el) {
      if (el.closest(OBS_FREE) || el.getAttribute('data-obs-ti') !== null) return;
      el.setAttribute('data-obs-ti', el.hasAttribute('tabindex') ? el.getAttribute('tabindex') : '');
      el.setAttribute('tabindex', '-1');
    });
    else all('[data-obs-ti]').forEach(function (el) {
      var t = el.getAttribute('data-obs-ti'); el.removeAttribute('data-obs-ti');
      if (t === '') el.removeAttribute('tabindex'); else el.setAttribute('tabindex', t);
    });
    if (on && obsBlocked(document.activeElement)) document.activeElement.blur();
  }
  var obsSaidAt = 0;
  function obsSay() {
    if (Date.now() - obsSaidAt < 1500) return;
    obsSaidAt = Date.now();
    msg('Sola osservazione: comanda ' + ($('busyName').textContent || 'un altro controller') + '. Per regolare premi «Prendi il comando».', true);
  }
  function obsFocusable() {
    return all('a[href], button, input, select, textarea, [tabindex]').filter(function (el) {
      if (el.disabled || el.getAttribute('tabindex') === '-1' || obsBlocked(el)) return false;
      if (el.closest('[hidden]') || el.closest('[inert]')) return false;
      var r = el.getBoundingClientRect(); return r.width > 0 && r.height > 0 && getComputedStyle(el).visibility !== 'hidden';
    });
  }
  document.addEventListener('keydown', function (e) {
    if (!isObserver()) return;
    if (e.key === 'Tab') {
      // Tab salta anche i controlli di regia ridisegnati dopo l'ultimo giro (senza tabindex -1)
      var f = obsFocusable(); if (!f.length) return;
      var a = document.activeElement, i = f.indexOf(a), n;
      if (i >= 0) n = f[(i + (e.shiftKey ? f.length - 1 : 1)) % f.length];
      else {
        // fuoco su un controllo spento o nel vuoto: il primo dopo (o prima) nell'ordine del documento
        var after = a && a !== document.body ? f.filter(function (el) { return a.compareDocumentPosition(el) & Node.DOCUMENT_POSITION_FOLLOWING; }) : f;
        var before = f.filter(function (el) { return after.indexOf(el) < 0; });
        n = e.shiftKey ? (before[before.length - 1] || f[f.length - 1]) : (after[0] || f[0]);
      }
      e.preventDefault(); n.focus();
      return;
    }
    if (obsBlocked(e.target)) { e.preventDefault(); e.stopImmediatePropagation(); if (e.key === 'Enter' || e.key === ' ' || e.key === 'Spacebar') obsSay(); }
  }, true);
  ['keyup', 'keypress'].forEach(function (t) {
    document.addEventListener(t, function (e) { if (obsBlocked(e.target) && e.key !== 'Tab') { e.preventDefault(); e.stopImmediatePropagation(); } }, true);
  });
  // il clic che nasce dalla tastiera (o da uno screen reader) su un controllo spento: fermato. I click() degli script non sono isTrusted.
  document.addEventListener('click', function (e) {
    if (e.isTrusted && obsBlocked(e.target)) { e.preventDefault(); e.stopImmediatePropagation(); obsSay(); }
  }, true);
  document.addEventListener('focusin', function (e) { if (obsBlocked(e.target)) e.target.blur(); }, true);

  function takeControl() {
    api('POST', '/api/v1/control', { action: 'take' }).then(function (j) {
      handle(j); if (j && j.ok) { msg('Ora comandi tu'); poll(); }
    });
  }
  // ANDROID (fix analisi 22/11, F3): un'eccezione nel disegno (render, debriefing, sala…) finiva nel .catch del poll e la
  // pagina diceva «monitor non raggiungibile» (e rallentava le letture) anche con il monitor che rispondeva benissimo. Ora
  // si scrive in console (con lo stack) e a schermo una riga che dice che è un errore della pagina, al massimo ogni 10 s.
  var drawFailAt = 0;
  function drawFail(e) {
    try { console.error('web regia: errore della pagina (non del monitor):', e && e.stack || e); } catch (x) {}
    if (Date.now() - drawFailAt < 10000) return;
    drawFailAt = Date.now();
    msg('Errore della pagina (il monitor risponde): se qualcosa non si aggiorna, ricarica la pagina. Dettaglio: ' + ((e && e.message) || e), true);
  }
  var pollAt = 0;                 // ANDROID (fix analisi 22/11, F2): inizio dell'ultima lettura (guardiano delle schede nascoste)
  function poll() {
    clearTimeout(pollTimer);
    pollAt = Date.now();
    var seqAtStart = cmdSeq;
    withTimeout(api('GET', '/api/v1/state'), 4000).then(function (j) {
      if (j && (j._status === 401 || j._status === 429)) { failures++; setConn(false, 'il monitor chiede il PIN'); handle(j, true); return; }
      // ANDROID (fix analisi 22/11, BUG-11): occupato anche dopo i nuovi tentativi: conta come lettura mancata, non «collegato»
      if (j && j._status === 503) { failures++; setConn(false, 'monitor occupato (troppe connessioni): riprovo'); return; }
      var wasDown = failures > 0, first = !monOnline;
      failures = 0; setConn(true, 'collegato al monitor');      // ANDROID (affordance 22/11, W-08): niente nome ripetuto
      if (seqAtStart !== cmdSeq) return;        // nel frattempo è partito un comando: lettura superata
      try {
        handle(j, true);
        offSync(j, wasDown, first);                         // ANDROID (debriefing offline web 22/11): istantanea, invii e azzeramento in sospeso
      } catch (e) { drawFail(e); }
    }).catch(function () {
      failures++; setConn(false, 'monitor non raggiungibile');
    }).then(function () {
      clearTimeout(pollTimer);                  // un solo ciclo di lettura, anche se poll() è partito due volte
      if (!$('console').hidden && !authLost) pollTimer = setTimeout(poll, failures ? Math.min(5000, POLL_MS * (failures + 1)) : POLL_MS);
    });
  }

  // ANDROID (fix analisi 22/11, F2): in una scheda NASCOSTA Chrome rallenta le catene di setTimeout (1/s, e dopo 5 min fino a
  // 1/min): le letture si diradavano oltre i 45 s dopo cui il monitor considera sparito un controller, e la web regia che comandava
  // perdeva il posto («controller attivo: nessuno») e il monitor ne perdeva la protezione. Ora un piccolo worker (i suoi timer non
  // vengono diradati così) sveglia la pagina ogni 5 s: se l'ultima lettura è partita da più di 8 s se ne fa una subito. Tornando
  // visibile la pagina rilegge subito (niente schermo vecchio). Senza worker (browser vecchio) resta il comportamento di prima.
  function pollKick(why) {
    if ($('console').hidden || authLost) return;
    if (Date.now() - pollAt > 8000) poll();
  }
  (function hiddenGuard() {
    try {
      var src = 'setInterval(function(){postMessage(1)},5000);';
      var w = new Worker(URL.createObjectURL(new Blob([src], { type: 'text/javascript' })));
      w.onmessage = function () { if (document.hidden) pollKick('worker'); };
    } catch (e) {}
    document.addEventListener('visibilitychange', function () {
      if (!document.hidden && !$('console').hidden && !authLost && Date.now() - pollAt > 1000) poll();
    });
  })();

  // ---------- utilità ----------
  var monOnline = false;          // ANDROID (debriefing offline web 22/11): ultima lettura dello stato riuscita
  // ANDROID (affordance 22/11, W-02): monitor non raggiungibile → dopo 2 letture mancate (~1 s) velo su tessere, azioni e plancia
  // (i valori sono vecchi e i comandi non partono) e una fascia a tutta larghezza «Monitor non raggiungibile da N s — valori
  // delle hh:mm:ss — riprovo». La barra di regia, il registro, il debriefing e Storia Sim restano usabili (debriefing offline).
  var lastOkAt = 0, downSince = 0, staleT = null;
  function staleRender() {
    var down = !monOnline && failures >= 2 && !$('console').hidden;
    document.body.classList.toggle('stale', down);
    $('staleBar').hidden = !down;
    clearTimeout(staleT);
    if (!down) return;
    var sec = Math.max(1, Math.round((Date.now() - downSince) / 1000));
    var at = lastOkAt ? new Date(lastOkAt) : null;
    $('staleBar').textContent = 'Monitor non raggiungibile da ' + sec + ' s — ' +
      (at ? 'i valori qui sono quelli delle ' + p2(at.getHours()) + ':' + p2(at.getMinutes()) + ':' + p2(at.getSeconds()) : 'nessun valore letto') +
      ' — i comandi non partono · riprovo da solo · il debriefing funziona lo stesso';
    staleT = setTimeout(staleRender, 1000);
  }
  function setConn(on, text) {
    if (on) lastOkAt = Date.now(); else if (monOnline || !downSince) downSince = Date.now() - 500;
    monOnline = !!on; offRender();
    ['conn', 'conn2'].forEach(function (id) { var c = $(id); c.className = 'conn ' + (on ? 'on' : 'off'); c.textContent = (on ? '● ' : '✕ ') + text; });
    if (on) downSince = 0;
    staleRender();
  }
  // ANDROID (fix analisi 22/11, BUG-04): ogni messaggio resta almeno MSG_MIN_MS (la lettura dello stato ogni 0,5 s non lo
  // cancella prima); un errore (err = true, in rosso) non lo cancella nemmeno la risposta di un comando partito subito dopo
  // (MSG_ERR_CMD_MS). Un messaggio nuovo sostituisce sempre il precedente. «… adeguata …» resta finché non ne arriva un altro.
  var msgAt = 0, msgErr = false, MSG_MIN_MS = 6000, MSG_ERR_CMD_MS = 2500;
  // ANDROID (affordance 22/11, W-04): tre aspetti — ✓ verde per le conferme del monitor, ✕ rosso per gli errori, ⓘ azzurro per gli
  // avvisi (prima tutto ambra, il colore degli avvisi). L'aspetto si ricava dal testo; err = true resta il segnale degli errori.
  var MSG_OK = /^(Eseguito|Ora comandi tu|Registrat|Registro scaricato|Simulazione «|Simulazione eliminata|Stato «[^»]*» attivato$|Scenario (avviato|salvato|eliminato)|Layout del monitor|Refill:|RCP interrotta|Cambio dell|EtCO₂ di nuovo|Azzeramento in sospeso eseguito|Misura NIBP|Allarmi |Tocco operatore|Modifiche|Sonda )/;
  var MSG_BAD = /^(Voce non registrata|Registro non scaricato|Fine scenario non arrivata|Monitor non raggiungibile|monitor non raggiungibile|Scenario: |Comando non)/;
  function msg(t, err) {
    var el = $('msg');
    el.textContent = t || ''; msgAt = t ? Date.now() : 0; msgErr = !!(t && err);
    el.classList.toggle('err', msgErr || !!(t && MSG_BAD.test(t)));
    el.classList.toggle('ok', !!(t && !msgErr && MSG_OK.test(t)));
  }
  function msgAutoClear(fromPoll) {
    var t = $('msg').textContent;
    if (!t || /adeguata/.test(t)) return;
    var age = Date.now() - msgAt;
    if (fromPoll ? age < MSG_MIN_MS : (msgErr && age < MSG_ERR_CMD_MS)) return;
    msg('');
  }
  // ANDROID (fix analisi 22/11, BUG-02): un tocco ogni ms millisecondi per chiave (doppio clic, secondo clic sul pulsante
  // ridisegnato nello stesso punto). Restituisce false se il tocco va ignorato.
  var tapAt = {};
  function tapOnce(key, ms) {
    var now = Date.now();
    if (tapAt[key] && now - tapAt[key] < ms) return false;
    tapAt[key] = now; return true;
  }
  function showLogin(err) {
    if (SALA) { salaStopAll(); $('sala').hidden = true; salaShow(); }     // ANDROID (sala web 22/11): uscendo, la sala si chiude
    $('console').hidden = true; $('login').hidden = false; $('loginErr').textContent = err || ''; clearTimeout(pollTimer); }
  // ANDROID (debriefing offline web 22/11): se il monitor chiede di nuovo il PIN (riavviato, PIN cambiato, troppi tentativi)
  // mentre è aperto il piano di debriefing, il piano NON viene coperto dall'accesso: resta usabile (istantanea della fine),
  // una riga discreta lo dice e l'accesso compare chiudendo il piano. Le cose in sospeso partono dopo il nuovo accesso.
  var authLost = '';
  function loginOrShield(err) {
    if (!$('debrief').hidden && !$('console').hidden) { authLost = err; clearTimeout(pollTimer); setConn(false, 'il monitor chiede il PIN'); offRender(); return; }
    showLogin(err);
  }
  function showConsole() { authLost = ''; $('login').hidden = true; $('console').hidden = false; poll(); loadScenarios(); }
  // ANDROID (fix analisi 22/11, BUG-14): anche le virgolette, perché esc() finisce dentro attributi value="…"/title="…" (un nome
  // con " troncava il campo dell'editor e poteva aggiungere attributi alla pagina)
  function esc(t) { var d = document.createElement('div'); d.textContent = t; return d.innerHTML.replace(/"/g, '&quot;').replace(/'/g, '&#39;'); }
  function isPending() { return pendingRhythm !== null || Object.keys(pending).length > 0 || pendingMorph !== null; }   // ANDROID (regia morfologie ECG 22/11): + la morfologia
  function on(k) { return !!(state && state.sensors && state.sensors[k]); }
  function siteOf(k) { return (state && state.sensor_sites && state.sensor_sites[k]) || SENS[k].def; }
  function siteLabel(site) {
    if (!site) return '';
    var m = /^finger_([rl])(\d)$/.exec(site);
    if (m) return FINGERS[+m[2] - 1] + (m[1] === 'r' ? ' destro' : ' sinistro');
    var lab = { chest: 'torace', pads_r: 'sottoclaveare destra', pads_l: 'apice (ascellare sinistra)', airway: 'naso e bocca', forehead: 'fronte',
      upper_arm_r: 'braccio destro', upper_arm_l: 'braccio sinistro',
      radial_r: 'polso destro (radiale)', radial_l: 'polso sinistro (radiale)',
      femoral_r: 'inguine destro (femorale)', femoral_l: 'inguine sinistro (femorale)' };
    return lab[site] || site;
  }
  function siteSensor(site) {
    if (/^upper_arm_/.test(site)) return 'nibp';
    if (/^(radial|femoral)_/.test(site)) return 'art';
    if (/^finger_/.test(site)) {
      if (selSensor === 'oxi') return 'oxi';
      if (on('oxi') && siteOf('oxi') === site && !(on('spo2') && siteOf('spo2') === site)) return 'oxi';
      return 'spo2';
    }
    if (site === 'airway') return 'etco2';
    if (site === 'forehead') return 'temp';
    if (site === 'chest') return 'ecg';
    if (/^pads_/.test(site)) return 'pads';
    return null;
  }
  function sideOf(site) { var m = /_([rl])\d?$/.exec(site || ''); return m ? m[1] : null; }

  // =====================================================================
  // PAZIENTE (SVG): sagoma stilizzata, supina, dalla testa alle ginocchia
  // =====================================================================
  var CX = 180;
  function f1(x) { return Math.round(x * 10) / 10; }
  function nrm(x, y) { var m = Math.hypot(x, y) || 1; return [x / m, y / m]; }
  function cr(P2, closed) {                         // Catmull-Rom -> Bézier
    var n = P2.length, d = 'M' + f1(P2[0][0]) + ' ' + f1(P2[0][1]), last = closed ? n : n - 1;
    for (var i = 0; i < last; i++) {
      var p0 = P2[(i - 1 + n) % n], p1 = P2[i], p2 = P2[(i + 1) % n], p3 = P2[(i + 2) % n];
      if (!closed) { if (i === 0) p0 = p1; if (i + 2 >= n) p3 = p2; }
      d += 'C' + f1(p1[0] + (p2[0] - p0[0]) / 6) + ' ' + f1(p1[1] + (p2[1] - p0[1]) / 6) + ' ' +
           f1(p2[0] - (p3[0] - p1[0]) / 6) + ' ' + f1(p2[1] - (p3[1] - p1[1]) / 6) + ' ' + f1(p2[0]) + ' ' + f1(p2[1]);
    }
    return d + (closed ? 'Z' : '');
  }
  function limb(cl, pad) {                          // linea mediana [x,y,raggio] -> contorno chiuso con estremi arrotondati
    pad = pad || 0;
    var L = [], R = [], n = cl.length, i;
    for (i = 0; i < n; i++) {
      var a = cl[Math.max(0, i - 1)], b = cl[Math.min(n - 1, i + 1)], t = nrm(b[0] - a[0], b[1] - a[1]), r = cl[i][2] + pad;
      L.push([cl[i][0] - t[1] * r, cl[i][1] + t[0] * r]); R.push([cl[i][0] + t[1] * r, cl[i][1] - t[0] * r]);
    }
    var e = cl[n - 1], te = nrm(e[0] - cl[n - 2][0], e[1] - cl[n - 2][1]), re = e[2] + pad;
    var s = cl[0], ts = nrm(s[0] - cl[1][0], s[1] - cl[1][1]), rs = s[2] + pad;
    return cr(L.concat([[e[0] + te[0] * re * 0.9, e[1] + te[1] * re * 0.9]], R.reverse(), [[s[0] + ts[0] * rs * 0.9, s[1] + ts[1] * rs * 0.9]]), true);
  }
  function mir(p) { return [2 * CX - p[0], p[1]].concat(p.slice(2)); }

  // Il corpo è /body.svg ("Male front 3d-shaded human illustration" di Goran tek-en,
  // Wikimedia Commons, CC BY-SA 4.0), ritagliato testa-ginocchia e reso "manichino"
  // dal filtro CSS. Lato destro del paziente = sinistra dello schermo. viewBox 0 0 360 620.
  var IMG = { x: 15, y: 6, w: 330 }; IMG.h = IMG.w * 946.13 / 402.94;
  // coordinate dei punti sul corpo (calibrate sull'immagine resa, viewBox 0 0 360 620)
  var G = {
    forehead: [180, 32], airway: [180, 72],
    chest: [[128, 146], [232, 146], [240, 190], [238, 250], [230, 308], [180, 322], [130, 308], [122, 250], [120, 190]],
    ecg: [['R', 146, 160, '#e53935'], ['L', 214, 160, '#fdd835'], ['C', 194, 228, '#f4f4f4'], ['N', 152, 290, '#1d1d1d'], ['F', 208, 290, '#43a047']],
    padR: [143, 170, -12, 40, 50], padL: [230, 235, -25, 42, 54],
    cuffA: [104, 206, 14], cuffB: [102, 240, 15],     // omero: ascella ~195, gomito ~257 -> bracciale centrato (bordi braccio: 87..118)
    armHot: [[104, 196, 13], [103, 222, 14], [102, 246, 15]],
    radial: [63, 359], forearmDir: [-0.35, 0.93], femoral: [140, 400], femAngR: -115,
    fingers: [                                      // destro, pollice..mignolo: [punta x, punta y, direzione x, direzione y]
      [23, 399, -0.47, 0.88], [31, 438, -0.33, 0.95], [43, 447, -0.20, 0.98],
      [52, 444, -0.25, 0.97], [66, 437, -0.05, 1]
    ],
    cpr: [180, 225], breath: [180, 222, 64, 76], touch: [116, 160], fadeY: 572, clipY: 612
  };
  function handGeo(side) {                          // mani aperte, dita a ventaglio verso i piedi
    var g = { radial: side === 'r' ? G.radial.slice() : mir(G.radial),
              dir: side === 'r' ? nrm(G.forearmDir[0], G.forearmDir[1]) : nrm(-G.forearmDir[0], G.forearmDir[1]),
              fingers: [] };
    G.fingers.forEach(function (f, i) {
      var tip = [f[0], f[1]], dir = nrm(f[2], f[3]);
      if (side === 'l') { tip = mir(tip); dir = [-dir[0], dir[1]]; }
      g.fingers.push({ n: i + 1, tip: tip, dir: dir, site: 'finger_' + side + (i + 1),
        cl: [[tip[0] - dir[0] * 24, tip[1] - dir[1] * 24, 5], [tip[0], tip[1], 4.5]] });
    });
    return g;
  }
  var HAND, FINGER_BY_SITE, MARKS;
  function buildGeo() {
    HAND = { r: handGeo('r'), l: handGeo('l') };
    FINGER_BY_SITE = {};
    ['r', 'l'].forEach(function (s) { HAND[s].fingers.forEach(function (fg) { FINGER_BY_SITE[fg.site] = fg; }); });
    MARKS = [['forehead', G.forehead[0], G.forehead[1]], ['airway', G.airway[0], G.airway[1]],
             ['chest', G.ecg[2][1], G.ecg[2][2]], ['pads_r', G.padR[0], G.padR[1]], ['pads_l', G.padL[0], G.padL[1]],
             ['upper_arm_r', G.armHot[1][0], G.armHot[1][1]], ['upper_arm_l', mir(G.armHot[1])[0], G.armHot[1][1]],
             ['radial_r', G.radial[0], G.radial[1]], ['radial_l', mir(G.radial)[0], G.radial[1]],
             ['femoral_r', G.femoral[0], G.femoral[1]], ['femoral_l', mir(G.femoral)[0], G.femoral[1]],
             ['finger_r3', G.fingers[2][0], G.fingers[2][1]], ['finger_l3', mir(G.fingers[2])[0], G.fingers[2][1]]];
  }
  buildGeo();
  var hoverSite = null;
  function T(x, y, attrs, txt) { return '<text x="' + x + '" y="' + y + '" ' + attrs + '>' + txt + '</text>'; }

  function buildFigure() {
    var hot = [];
    hot.push(['chest', '<path d="' + cr(G.chest, true) + '"/>']);
    [['pads_r', G.padR], ['pads_l', G.padL]].forEach(function (pq) {
      var q = pq[1];
      hot.push([pq[0], '<ellipse cx="' + q[0] + '" cy="' + q[1] + '" rx="' + (q[3] / 2 + 5) + '" ry="' + (q[4] / 2 + 5) + '" transform="rotate(' + q[2] + ' ' + q[0] + ' ' + q[1] + ')"/>']);
    });
    hot.push(['airway', '<ellipse cx="' + G.airway[0] + '" cy="' + G.airway[1] + '" rx="16" ry="13"/>']);
    hot.push(['forehead', '<ellipse cx="' + G.forehead[0] + '" cy="' + G.forehead[1] + '" rx="22" ry="11"/>']);
    ['r', 'l'].forEach(function (s) {
      var mk = s === 'r' ? function (p) { return p; } : mir, h = HAND[s];
      hot.push(['upper_arm_' + s, '<path d="' + limb(G.armHot.map(mk), 5) + '"/>']);
      hot.push(['radial_' + s, '<circle cx="' + f1(h.radial[0]) + '" cy="' + f1(h.radial[1]) + '" r="12"/>']);
      hot.push(['femoral_' + s, '<ellipse cx="' + mk(G.femoral)[0] + '" cy="' + G.femoral[1] + '" rx="14" ry="12"/>']);
      h.fingers.forEach(function (fg) { hot.push([fg.site, '<path d="' + limb(fg.cl, 3) + '"/>']); });
    });
    var hots = hot.map(function (h) { return h[1].replace(/^<(\w+)/, '<$1 class="hot" data-site="' + h[0] + '"'); }).join('');
    var marks = MARKS.map(function (m) {
      return '<g class="mark" data-site="' + m[0] + '" transform="translate(' + m[1] + ',' + m[2] + ')"><circle class="ring" r="8"/><circle class="dot" r="2.2"/></g>';
    }).join('');
    var la = 'font-size="14" font-weight="700" fill="#7f91a4" text-anchor="middle"';
    return '<svg viewBox="0 0 360 620" preserveAspectRatio="xMidYMid meet" xmlns="http://www.w3.org/2000/svg">' +
      '<defs>' +
      '<linearGradient id="bedG" x1="0" x2="1"><stop offset="0" stop-color="#151d27"/><stop offset=".5" stop-color="#1b2531"/><stop offset="1" stop-color="#151d27"/></linearGradient>' +
      '<linearGradient id="fadeG" x1="0" y1="0" x2="0" y2="1"><stop offset="0" stop-color="#0a0e13" stop-opacity="0"/><stop offset=".85" stop-color="#0a0e13" stop-opacity=".97"/><stop offset="1" stop-color="#0a0e13"/></linearGradient>' +
      '<radialGradient id="breathG"><stop offset="0" stop-color="#ffffff" stop-opacity=".5"/><stop offset="1" stop-color="#ffffff" stop-opacity="0"/></radialGradient>' +
      '<filter id="glow" x="-50%" y="-50%" width="200%" height="200%"><feGaussianBlur in="SourceAlpha" stdDeviation="3.5" result="b"/>' +
      '<feFlood flood-color="#3cdcff" flood-opacity=".95"/><feComposite in2="b" operator="in" result="g"/><feMerge><feMergeNode in="g"/><feMergeNode in="SourceGraphic"/></feMerge></filter>' +
      '<filter id="led" x="-200%" y="-200%" width="500%" height="500%"><feGaussianBlur stdDeviation="2"/></filter>' +
      '<clipPath id="bodyClip"><rect x="0" y="0" width="360" height="' + G.clipY + '"/></clipPath>' +
      '</defs>' +
      '<rect x="16" y="4" width="328" height="616" rx="26" fill="url(#bedG)" stroke="#273546" stroke-width="2"/>' +
      '<rect x="28" y="16" width="304" height="596" rx="20" fill="none" stroke="#1f2b38" stroke-width="1.5"/>' +
      '<rect x="118" y="10" width="124" height="104" rx="38" fill="#1e2935" stroke="#2b3a4b"/>' +
      T(52, 28, la, 'DX') + T(308, 28, la, 'SX') +
      '<g id="bodyAll" clip-path="url(#bodyClip)">' +
      '<image id="bodyImg" class="body" href="/body.svg" x="' + IMG.x + '" y="' + IMG.y + '" width="' + f1(IMG.w) + '" height="' + f1(IMG.h) + '"/>' +
      '<ellipse id="breath" cx="' + G.breath[0] + '" cy="' + G.breath[1] + '" rx="' + G.breath[2] + '" ry="' + G.breath[3] + '" fill="url(#breathG)" opacity="0"/>' +
      '<g id="devs"></g><g id="fx"></g>' +
      '</g>' +
      '<rect x="0" y="' + G.fadeY + '" width="360" height="' + (620 - G.fadeY) + '" fill="url(#fadeG)" pointer-events="none"/>' +
      '<g id="marks">' + marks + '</g>' +
      '<g id="hots">' + hots + '</g>' +
      '</svg>';
  }

  // ---------- disegno dei sensori applicati ----------
  function rot(p, ang, inner) { return '<g transform="translate(' + f1(p[0]) + ',' + f1(p[1]) + ') rotate(' + f1(ang) + ')">' + inner + '</g>'; }
  function deg(d) { return Math.atan2(d[1], d[0]) * 180 / Math.PI; }
  function drawSensor(k, site, s) {
    var out = '';
    if (k === 'ecg') {
      G.ecg.forEach(function (e) {
        out += '<circle cx="' + e[1] + '" cy="' + e[2] + '" r="8.5" fill="#eef1f4" stroke="#9aa7b4" stroke-width="1.1"/>' +
               '<circle cx="' + e[1] + '" cy="' + e[2] + '" r="3.8" fill="' + e[3] + '" stroke="#2b2b2b" stroke-width=".8"/>';
      });
    } else if (k === 'pads') {
      var pad = function (q) {
        var w = q[3], h = q[4];
        return rot(q, q[2], '<rect x="' + (-w / 2) + '" y="' + (-h / 2) + '" width="' + w + '" height="' + h + '" rx="8" fill="#f3f5f7" stroke="#b9c3cd" stroke-width="1.3"/>' +
          '<rect x="' + (-w / 2 + 5) + '" y="' + (-h / 2 + 5) + '" width="' + (w - 10) + '" height="' + (h - 10) + '" rx="5" fill="#ffe8d6" stroke="#ff7a1a" stroke-width="1" opacity=".9"/>' +
          '<rect x="' + (-w / 2 + 5) + '" y="' + (-h / 2 - 1) + '" width="' + (w - 10) + '" height="4" rx="2" fill="#ff7a1a"/>');
      };
      out += pad(G.padR) + pad(G.padL);
    } else if (k === 'spo2') {
      var fg = FINGER_BY_SITE[site] || FINGER_BY_SITE[SENS.spo2.def];
      var ctr = [fg.tip[0] - fg.dir[0] * 6, fg.tip[1] - fg.dir[1] * 6];
      out += rot(ctr, deg(fg.dir), '<rect x="-12" y="-7.5" width="22" height="15" rx="6" fill="#28313b" stroke="#0b0f13" stroke-width="1.1"/>' +
        '<rect x="-12" y="-7.5" width="7" height="15" rx="3.5" fill="#3a4552"/>' +
        '<circle cx="2" cy="0" r="4" fill="#ff2a2a" filter="url(#led)"/><circle cx="2" cy="0" r="1.6" fill="#ffd0d0"/>');
    } else if (k === 'oxi') {
      var fo = FINGER_BY_SITE[site] || FINGER_BY_SITE[SENS.oxi.def];
      var co = [fo.tip[0] - fo.dir[0] * 7, fo.tip[1] - fo.dir[1] * 7];
      out += rot(co, deg(fo.dir), '<rect x="-13" y="-8" width="23" height="16" rx="5" fill="#50607a" stroke="#1c2430" stroke-width="1.1"/>' +
        '<rect x="-13" y="-8" width="8" height="16" rx="4" fill="#6b7d9b"/><line x1="-13" y1="0" x2="-26" y2="0" stroke="#6a5acd" stroke-width="2.2" stroke-linecap="round"/>' +
        '<circle cx="3" cy="0" r="3.6" fill="#8fd6ff" filter="url(#led)"/><circle cx="3" cy="0" r="1.4" fill="#ffffff"/>');
    } else if (k === 'nibp') {
      var sd = sideOf(site) || 'r', A = G.cuffA.slice(), B = G.cuffB.slice();
      if (sd === 'l') { A = mir(A); B = mir(B); }
      var t = nrm(B[0] - A[0], B[1] - A[1]), n = [-t[1], t[0]];
      var q = [[A[0] + n[0] * A[2], A[1] + n[1] * A[2]], [B[0] + n[0] * B[2], B[1] + n[1] * B[2]], [B[0] - n[0] * B[2], B[1] - n[1] * B[2]], [A[0] - n[0] * A[2], A[1] - n[1] * A[2]]];
      var poly = q.map(function (p) { return f1(p[0]) + ',' + f1(p[1]); }).join(' ');
      var stripes = [0.35, 0.65].map(function (u) {
        var a = [A[0] + (B[0] - A[0]) * u, A[1] + (B[1] - A[1]) * u], r = A[2] + (B[2] - A[2]) * u - 3;
        return '<line x1="' + f1(a[0] + n[0] * r) + '" y1="' + f1(a[1] + n[1] * r) + '" x2="' + f1(a[0] - n[0] * r) + '" y2="' + f1(a[1] - n[1] * r) + '" stroke="#36577c" stroke-width="2"/>';
      }).join('');
      var meas = s.nibp_status === 'measuring';
      out += '<g class="cuff' + (meas ? ' inflating' : '') + '"><polygon points="' + poly + '" fill="#1f3a5a" stroke="' + (meas ? '#f0557a' : '#0e1a28') + '" stroke-width="' + (meas ? 2.5 : 1.3) + '" stroke-linejoin="round"/>' + stripes + '</g>';
    } else if (k === 'art') {
      var pt, ang;
      if (/^radial/.test(site)) { var h = HAND[sideOf(site) || 'l']; pt = h.radial; ang = deg(h.dir) + 180; }
      else { pt = sideOf(site) === 'r' ? G.femoral : mir(G.femoral); ang = sideOf(site) === 'r' ? G.femAngR : -180 - G.femAngR; }
      out += rot(pt, ang, '<rect x="-11" y="-8" width="22" height="16" rx="4" fill="rgba(225,238,248,.30)" stroke="rgba(235,245,255,.75)" stroke-width="1.1"/>' +
        '<line x1="-6" y1="0" x2="6" y2="0" stroke="#eef3f7" stroke-width="2.2" stroke-linecap="round"/><rect x="5" y="-3.4" width="7" height="6.8" rx="1.8" fill="#d32f2f"/>');
    } else if (k === 'etco2') {
      var a = G.airway;
      out += '<path d="M' + (a[0] - 22) + ' ' + (a[1] - 9) + 'Q' + (a[0] - 11) + ' ' + (a[1] + 4) + ' ' + a[0] + ' ' + a[1] +
             'Q' + (a[0] + 11) + ' ' + (a[1] + 4) + ' ' + (a[0] + 22) + ' ' + (a[1] - 9) + '" fill="none" stroke="#dfe7ee" stroke-width="2.3" stroke-linecap="round" opacity=".95"/>' +
             '<path d="M' + (a[0] - 4) + ' ' + a[1] + 'l0 -4M' + (a[0] + 4) + ' ' + a[1] + 'l0 -4" stroke="#dfe7ee" stroke-width="2" stroke-linecap="round"/>';
    } else if (k === 'temp') {
      var fh = G.forehead;
      out += '<rect x="' + (fh[0] - 14) + '" y="' + (fh[1] - 6) + '" width="28" height="12" rx="4" fill="rgba(245,242,230,.55)" stroke="rgba(200,190,170,.7)"/>' +
             '<circle cx="' + fh[0] + '" cy="' + fh[1] + '" r="5" fill="#e6dcf7" stroke="#8a72b8" stroke-width="1.4"/>';
    }
    return '<g class="dev' + (selSensor === k ? ' sel' : '') + '" data-k="' + k + '">' + out + '</g>';
  }
  var devSig = '';
  function renderFigure(s) {
    var sig = JSON.stringify([s.sensors, s.sensor_sites, selSensor, s.nibp_status, s.cpr_active, s.operator_touch, s.patient_motion, s.spo2_percent < 85 || !s.pulse_present]);
    if (sig === devSig) return;
    devSig = sig;
    var devs = '';
    ORDER.forEach(function (k) { if (on(k)) devs += drawSensor(k, siteOf(k), s); });
    $('devs').innerHTML = devs;
    var fx = '';
    if (s.cpr_active) fx += '<g class="cprmark"><circle class="r1" cx="' + G.cpr[0] + '" cy="' + G.cpr[1] + '" r="8" stroke-width="3"/>' +
      '<ellipse cx="' + G.cpr[0] + '" cy="' + G.cpr[1] + '" rx="19" ry="14" fill="rgba(255,107,107,.22)" stroke="#ff6b6b" stroke-width="2"/>' +
      T(G.cpr[0], G.cpr[1] + 34, 'text-anchor="middle" font-size="12" font-weight="700" fill="#ff8b8b"', 'RCP') + '</g>';
    if (s.operator_touch) fx += '<g class="touchmark"><circle cx="' + G.touch[0] + '" cy="' + G.touch[1] + '" r="17" fill="rgba(127,178,255,.22)" stroke="#7fb2ff" stroke-width="2"/>' +
      T(G.touch[0], G.touch[1] + 30, 'text-anchor="middle" font-size="11" font-weight="700" fill="#9cc4ff"', 'tocco') + '</g>';
    $('fx').innerHTML = fx;
    $('bodyAll').setAttribute('class', s.patient_motion === 'strong' ? 'm2' : s.patient_motion === 'light' ? 'm1' : '');
    var cyan = s.spo2_percent < 85 || !s.pulse_present;   // cianosi: il corpo vira al blu (filtro CSS)
    $('bodyImg').setAttribute('class', 'body' + (cyan ? ' cyan' : ''));
    var hasSite = function (ds) {
      var tg = hotTarget(ds);
      return !!(tg && on(tg.k) && siteOf(tg.k) === tg.site);
    };
    all('.hot').forEach(function (h) { h.setAttribute('class', 'hot' + (hasSite(h.dataset.site) ? ' has' : '')); });
    all('.mark').forEach(function (m) {
      var ds = m.dataset.site, tg = hotTarget(ds);
      var hide = hasSite(ds) || (tg && /^finger_/.test(ds) && on(tg.k));   // nasconde il segnaposto del dito se la SpO₂ è già applicata
      m.setAttribute('class', 'mark' + (hide ? ' has' : ''));
    });
  }
  // torace che si alza con il respiro (FR del paziente)
  function breathLoop(t) {
    var el = $('breath');
    if (el && state) {
      var rr = state.respiratory_rate_rpm || 0, o = 0;
      if (rr > 0 && state.simulation_running && !state.cpr_active) o = 0.26 * (0.5 - 0.5 * Math.cos(2 * Math.PI * (t / 1000) * rr / 60));
      el.setAttribute('opacity', o.toFixed(3));
    }
    requestAnimationFrame(breathLoop);
  }

  // ---------- interazione sul paziente ----------
  // un clic = sensore posizionato + monitor avvisato (traccia/valore attivi) + scheda del parametro evidenziata
  function hotTarget(hs) {
    var k = siteSensor(hs); if (!k) return null;
    return { k: k, site: k === 'pads' ? 'chest' : hs };
  }
  function hotInfo(hs) {
    var tg = hotTarget(hs); if (!tg) return '';
    var k = tg.k, head = '<b>' + SENS[k].full + ' · ' + siteLabel(hs) + '</b>';
    if (!on(k)) return head + '<small>clicca: applica e mostra i controlli</small>';
    if (siteOf(k) === tg.site) return head + '<small>applicato · clicca per i controlli</small>';
    return head + '<small>clicca per spostarlo qui (ora: ' + siteLabel(siteOf(k)) + ')</small>';
  }
  function apply(k, site) {
    if (!on(k)) return command('set_sensor', { sensor: k, connected: true, site: site });
    if (siteOf(k) === site) return Promise.resolve();
    if (k === 'spo2' || k === 'nibp') {              // sonda/bracciale tolti e rimessi: il monitor riparte da capo
      return command('set_sensor', { sensor: k, connected: false }).then(function (ok) {
        if (!ok) return false;                         // ANDROID (fix analisi 22/11, BUG-07): tolto non riuscito, niente rimessa
        return command('set_sensor', { sensor: k, connected: true, site: site });
      });
    }
    return command('set_sensor', { sensor: k, connected: true, site: site });
  }
  function place(k, site) {                          // comando al monitor, poi subito disegno e scheda
    var was = on(k) ? siteOf(k) : null;
    var pr = apply(k, site);                         // (prima il comando: legge lo stato attuale del sensore)
    if (state) {
      state.sensors[k] = true;
      state.sensor_sites = state.sensor_sites || {}; state.sensor_sites[k] = site;
    }
    select(k);
    return pr;
  }
  function detach(k) {
    if (selSensor === k) selSensor = null;
    return command('set_sensor', { sensor: k, connected: false });
  }
  function stagePos(e) { var r = $('stage').getBoundingClientRect(); return [e.clientX - r.left, e.clientY - r.top]; }
  function wireFigure() {
    var fig = $('figure'), tip = $('tip');
    fig.addEventListener('mousemove', function (e) {
      var h = e.target.closest && e.target.closest('.hot');
      if (!h || !state) { tip.hidden = true; hoverSite = null; return; }
      hoverSite = h.dataset.site;
      tip.innerHTML = hotInfo(hoverSite); tip.hidden = false;
      var p = stagePos(e); tip.style.left = (p[0] + 16) + 'px'; tip.style.top = (p[1] + 14) + 'px';
    });
    fig.addEventListener('mouseleave', function () { tip.hidden = true; hoverSite = null; });
    fig.addEventListener('click', function (e) {
      var h = e.target.closest && e.target.closest('.hot');
      if (!h || !state) return;
      var tg = hotTarget(h.dataset.site); if (!tg) return;
      var was = on(tg.k) && siteOf(tg.k) !== tg.site;
      place(tg.k, tg.site);
      if (was && (tg.k === 'spo2' || tg.k === 'nibp')) msg(SENS[tg.k].full + ' spostato: il monitor riparte con la misura');
      $('tip').innerHTML = hotInfo(h.dataset.site);
    });
  }

  // ---------- registro della sessione (debriefing) ----------
  // Il registro sta SUL MONITOR (/api/v1/log): lo scrivono il monitor stesso (ciò che fanno i partecipanti sul
  // dispositivo, ritmi, arresto/ROSC, RCP, pause, timer, fasi dello scenario, comandi dell'istruttore), questa pagina
  // (stati one-tap, osservazioni da menu rapido, note) e l'app controller per iPad: tutti vedono le stesse voci.
  var LOGE = [], logNext = 0, logSum = {}, logBusy = false, logAgain = false, qCount = {}, qMode = null, dbFilter = 'key';
  // sessioni del registro: una per simulazione (il monitor ne apre una nuova all'avvio di uno scenario o all'azzeramento)
  var logSess = null, logSessions = [], dbSess = null, dbPast = null, dbSessSig = '', dbArch = null;      // dbArch = simulazione di Storia Sim mostrata      // dbSess null = sessione in corso
  var KIND = {                     // etichetta corta + nome per esteso: la categoria non è mai solo colore
    phase: ['SCEN', 'Scenario'], patient: ['PAZ', 'Paziente'], monitor: ['MON', 'Monitor'], electric: ['ELETTR', 'Terapia elettrica'],
    timer: ['TIMER', 'Timer'], action: ['AZIONE', 'Azione dei partecipanti'], drug: ['FARM', 'Farmaco'],
    plus: ['FORZA', 'Punto di forza'], minus: ['DISCUT', 'Da discutere'], note: ['NOTA', 'Nota'],
    alarm: ['ALLARME', 'Allarme']     // ANDROID (funzioni mancanti 22/11, IN-06): voci degli allarmi (inizio, fine, silenzi, limiti)
  };
  // menu rapido delle osservazioni dell'istruttore: lo stesso catalogo dell'app controller per iPad
  var QCAT = [
    { g: 'Riconoscimento', it: [
      { k: 'action', t: "Riconosciuto l'arresto cardiaco", tag: 'ac' },
      { k: 'action', t: 'Chiamato aiuto / attivato il team', tag: 'help' },
      { k: 'action', t: 'Valutazione ABCDE' }, { k: 'action', t: 'Valutato il ritmo' } ] },
    { g: 'RCP', it: [
      { k: 'action', t: 'Iniziate le compressioni', tag: 'cpr_start' },
      { k: 'action', t: "Cambio dell'operatore alle compressioni" }, { k: 'action', t: 'Ventilazioni' } ] },
    { g: 'Vie aeree e accessi', it: [
      { k: 'action', t: 'Ossigeno' }, { k: 'action', t: 'Presidio sovraglottico' }, { k: 'action', t: 'Intubazione' },
      { k: 'action', t: 'Accesso venoso' }, { k: 'action', t: 'Accesso intraosseo' } ] },
    { g: 'Farmaci', it: [
      { k: 'drug', t: 'Adrenalina 1 mg' }, { k: 'drug', t: 'Amiodarone 300 mg' }, { k: 'drug', t: 'Amiodarone 150 mg' },
      { k: 'drug', t: 'Atropina 0,5 mg' }, { k: 'drug', t: 'Liquidi 500 ml' },
      { k: 'drug', t: 'Altro farmaco…', ask: 'Farmaco e dose' } ] },
    { g: 'Debriefing', it: [
      { k: 'plus', t: 'Punto di forza…', ask: 'Che cosa è andato bene' },
      { k: 'minus', t: 'Da discutere…', ask: 'Che cosa riprendere nel debriefing' },
      { k: 'note', t: 'Nota libera…', ask: 'Annotazione libera…' } ] }
  ];
  var QFLAT = [];
  QCAT.forEach(function (g) { g.it.forEach(function (it) { it.i = QFLAT.length; QFLAT.push(it); }); });

  function fmtMMSS(sec) {
    if (sec === null || sec === undefined || isNaN(sec)) return '--:--';
    sec = Math.max(0, Math.floor(sec));
    var m = Math.floor(sec / 60);
    return (m < 100 ? ('0' + m).slice(-2) : m) + ':' + ('0' + (sec % 60)).slice(-2);
  }
  function fmtDur(sec) {            // durata leggibile: "45 s", "2 min 05 s"
    if (sec === null || sec === undefined || isNaN(sec)) return '—';
    sec = Math.max(0, Math.round(sec));
    return sec < 60 ? sec + ' s' : Math.floor(sec / 60) + ' min ' + ('0' + (sec % 60)).slice(-2) + ' s';
  }
  // legge dal monitor le voci nuove (a blocchi finché ce ne sono); lo chiama handle() quando log_next cresce
  function loadLog() {
    if (logBusy) { logAgain = true; return; }
    logBusy = true; logAgain = false;
    withTimeout(api('GET', '/api/v1/log?since=' + logNext), 8000).then(function (j) {      // ANDROID (debriefing offline web 22/11): mai appeso
      logBusy = false;
      if (!j || !j.ok) return;
      takeLog(j, false);
      if (j.more || logAgain) loadLog();
    }).catch(function () { logBusy = false; });
  }
  function takeLog(j, replace) {
    if (replace) LOGE = [];
    (j.log || []).forEach(function (e) { if (!LOGE.length || e.i > LOGE[LOGE.length - 1].i) LOGE.push(e); });
    if (j.next !== undefined) logNext = j.next;
    if (j.summary) logSum = j.summary;
    if (j.sessions) logSessions = j.sessions;
    if (j.current_session !== undefined) logSess = j.current_session;
    renderLog(); renderDebrief(); tx12Render();      // ANDROID (trasmissione 12D 22/11)
    snapSoon();                     // ANDROID (debriefing offline web 22/11): voci nuove della fine → istantanea aggiornata
  }
  function logSync(n, sid) {        // n = log_next dello stato, sid = log_session (sessione in corso)
    if (sid !== undefined && sid !== null && sid !== logSess) {      // sessione nuova: registro e contatori ripartono da lì
      logSess = sid; LOGE = []; logNext = 0; logSum = {}; qCount = {}; renderQuick(); renderLog(); loadLog(); return;
    }
    if (n === undefined || n === null || n === logNext) return;
    var last = LOGE.length ? LOGE[LOGE.length - 1].i : -1;
    if (n <= last) { LOGE = []; logNext = 0; qCount = {}; renderQuick(); }      // registro svuotato (da qui o da un altro controller)
    loadLog();
  }
  // scrive una voce sul monitor (permesso anche quando comanda un altro controller: un secondo istruttore può annotare)
  function logAdd(txt, kind, tag) {
    var body = { action: 'add', kind: kind || 'note', text: txt };
    if (tag) body.tag = tag;
    return api('POST', '/api/v1/log', body).then(function (j) {
      if (j && j._status === 401) { handle(j); return false; }
      if (!j || !j.ok) { msg('Voce non registrata: ' + ((j && j.error) || 'il monitor non risponde')); return false; }
      // ANDROID (fix analisi 22/11, BUG-16): la risposta del POST porta le PRIME voci (since=0): sostituirle a LOGE (e rimettere
      // logNext a quel punto) con una lettura in volo oltre le 400 voci faceva saltare un blocco di voci. Si legge da logNext.
      loadLog();
      return true;
    }).catch(function () { msg('Voce non registrata: il monitor non risponde'); return false; });
  }
  // ---------- ANDROID (trasmissione 12D 22/11): ECG 12D trasmesso dal monitor, l'istruttore risponde come centrale ----------
  // la risposta è una nota con tag "ecg12_rx" (by = chi risponde). L'avviso resta finché qualcuno (anche un'altra regia) risponde,
  // o finché lo si chiude qui; solo trasmissioni recenti (15 min dalla voce più nuova del registro).
  var TX12_REPLIES = ['Centralizzare il paziente', 'Non centralizzare il paziente'];      // = Tx12Replies del controller
  var tx12Closed = -1, tx12Seen = -1, tx12Sig = '';
  function tx12Pending() {
    if (notStarted()) return null;    // in PRONTO (prima di «Avvia» o dopo un azzeramento) niente avviso: la risposta non si registrerebbe
    var tx = null;
    LOGE.forEach(function (e) { if (e.tag === 'ecg12_tx') tx = e; else if (e.tag === 'ecg12_rx') tx = null; });
    if (!tx || tx.i === tx12Closed) return null;
    var wl = LOGE[LOGE.length - 1].w || 0;
    return wl - (tx.w || 0) <= 900 ? tx : null;
  }
  function tx12Parts(tx) {
    var t = tx.text || '', m = /trasmesso a (.+?) \(/.exec(t), a = t.indexOf('('), b = t.lastIndexOf(')');
    return { dest: m ? m[1] : 'Centrale Operativa', det: a >= 0 && b > a ? t.slice(a + 1, b).trim() : '' };
  }
  function tx12Hm(tx) { var d = new Date((tx.w || 0) * 1000); return ('0' + d.getHours()).slice(-2) + ':' + ('0' + d.getMinutes()).slice(-2); }
  function tx12Render() {
    var box = $('tx12'); if (!box) return;
    var tx = tx12Pending();
    box.hidden = !tx;
    if (!tx) { $('tx12Opts').hidden = true; tx12Sig = ''; return; }
    var p = tx12Parts(tx), now = state && state.ecg_morphology && state.ecg_morphology.label;
    var off = notStarted();           // (difesa: l'avviso in PRONTO non compare)
    var sig = JSON.stringify([tx.i, now, off]);
    if (sig !== tx12Sig) {
      tx12Sig = sig;
      $('tx12When').textContent = 'ore ' + tx12Hm(tx) + ' · destinatario: ' + p.dest;
      $('tx12Det').textContent = p.det + (now && p.det.indexOf(now) < 0 ? ' · ECG adesso: ' + now : '');
      $('tx12Quick').innerHTML = TX12_REPLIES.map(function (r, i) { return '<button data-r="' + i + '"' + (off ? ' disabled title="' + esc(NOT_STARTED) + '"' : '') + '>' + esc(r) + '</button>'; }).join('');
      $('tx12FreeGo').disabled = off; $('tx12Reply').title = off ? NOT_STARTED : 'Risposte rapide o testo libero: finiscono nel registro';
    }
    if (tx.i !== tx12Seen) { if (tx12Seen !== -1 || LOGE.length) msg('ECG 12D trasmesso alla Centrale Operativa per valutazione (' + p.dest + ')'); tx12Seen = tx.i; }
  }
  function tx12Send(reply) {
    var tx = tx12Pending(); if (!tx) return;
    if (notStarted()) { msg(NOT_STARTED); return; }
    reply = String(reply || '').trim(); if (!reply) return;
    if (!tapOnce('tx12', 800)) return;
    var p = tx12Parts(tx);
    logAdd('Risposta della centrale al 12D (inviato a ' + p.dest + ' alle ' + tx12Hm(tx) + '): ' + reply, 'note', 'ecg12_rx').then(function (ok) {
      if (ok) { msg('Registrato: risposta della centrale — ' + reply); $('tx12Free').value = ''; $('tx12Opts').hidden = true; }
    });
  }
  function tx12Bind() {
    $('tx12Reply').addEventListener('click', function () { $('tx12Opts').hidden = !$('tx12Opts').hidden; if (!$('tx12Opts').hidden && !notStarted()) $('tx12Free').focus(); });
    $('tx12Close').addEventListener('click', function () { var tx = tx12Pending(); if (tx) tx12Closed = tx.i; tx12Render(); });
    $('tx12Quick').addEventListener('click', function (ev) { var b = ev.target.closest('button[data-r]'); if (b && !b.disabled) tx12Send(TX12_REPLIES[+b.dataset.r]); });
    $('tx12FreeGo').addEventListener('click', function () { tx12Send($('tx12Free').value); });
    $('tx12Free').addEventListener('keydown', function (ev) { if (ev.key === 'Enter') { ev.preventDefault(); tx12Send($('tx12Free').value); } });
  }

  function logLine(e, big) {
    var k = KIND[e.k] || KIND.note, hh = new Date((e.w || 0) * 1000);
    var w = ('0' + hh.getHours()).slice(-2) + ':' + ('0' + hh.getMinutes()).slice(-2) + (big ? ':' + ('0' + hh.getSeconds()).slice(-2) : '');
    var extra = e.tag === 'shock' && e.energy_j ? ' <em>' + esc(String(e.energy_j)) + ' J</em>' : '';
    return '<li class="k-' + esc(e.k || 'note') + '"><b>' + fmtMMSS(e.t) + '</b><i title="' + esc(k[1]) + '">' + (big ? esc(k[1]) : k[0]) + '</i>' +
           '<span>' + esc(e.text || '') + extra + (e.by ? ' <u>' + esc(e.by) + '</u>' : '') + '</span><small>' + w + '</small></li>';
  }
  function renderLog() {
    var ol = $('sessLog'); if (!ol) return;
    ol.innerHTML = LOGE.slice(-150).reverse().map(function (e) { return logLine(e, false); }).join('') ||
      '<li class="empty"><span>Ancora nessuna voce: il registro si riempie da solo con quello che fanno i partecipanti e l\'istruttore.</span></li>';
  }
  function renderQuick() {
    var el = $('ivGrid'); if (!el) return;
    el.innerHTML = QCAT.map(function (g) {
      return '<h4>' + esc(g.g) + '</h4><div class="qrow">' + g.it.map(function (it) {
        var c = qCount[it.i] || 0;
        return '<button data-q="' + it.i + '" class="q-' + it.k + (qMode === it.i ? ' active' : '') + '"' + (notStarted() ? ' disabled' : '') +
               ' title="' + (notStarted() ? esc(NOT_STARTED) : 'Registra: ' + esc(it.t)) + '">' +
               esc(it.t) + (c ? '<i>' + c + '</i>' : '') + '</button>';
      }).join('') + '</div>';
    }).join('');
  }
  function setAsk(i) {              // i = voce che chiede un testo (null = nota libera)
    qMode = i;
    var it = i === null ? null : QFLAT[i];
    var k = it ? it.k : 'note';
    $('askKind').textContent = (KIND[k] || KIND.note)[1];
    $('askKind').className = 'askkind k-' + k;
    $('comment').placeholder = it ? it.ask : 'Annotazione libera…';
    renderQuick();
    if (it) $('comment').focus();
  }
  function quickTap(i, btn) {
    var it = QFLAT[i]; if (!it) return;
    if (notStarted()) { msg(NOT_STARTED); return; }
    if (it.ask) { setAsk(qMode === i ? null : i); return; }
    logAdd(it.t, it.k, it.tag).then(function (ok) {
      if (!ok) return;
      qCount[i] = (qCount[i] || 0) + 1; renderQuick();
      var b = $('ivGrid').querySelector('button[data-q="' + i + '"]');
      if (b) { b.classList.add('done'); setTimeout(function () { b.classList.remove('done'); }, 900); }
      msg('Registrato: ' + it.t);
    });
  }
  function askSubmit() {
    if (notStarted()) { msg(NOT_STARTED); return; }
    var t = $('comment').value.trim();
    var it = qMode === null ? null : QFLAT[qMode];
    if (!t) { msg(it && it.k !== 'note' ? 'Scrivi il testo prima di registrare' : ''); $('comment').focus(); return; }
    var i = qMode;
    logAdd(t, it ? it.k : 'note', it ? it.tag : undefined).then(function (ok) {
      if (!ok) return;
      $('comment').value = '';
      if (i !== null) qCount[i] = (qCount[i] || 0) + 1;
      setAsk(null);
      msg('Registrato' + (it ? ': ' + (KIND[it.k] || KIND.note)[1].toLowerCase() : ''));
    });
  }
  function downloadLog() {          // testo completo preparato dal monitor
    var opt = { headers: { 'X-SimulaFranz-Token': token, 'X-SimulaFranz-Client': clientId,
      'X-SimulaFranz-Client-Name': encodeURIComponent(clientName), 'X-SimulaFranz-Client-Kind': 'web' }, cache: 'no-store' };
    var sid = !$('debrief').hidden && dbSess !== null ? dbSess : logSess;      // dal debriefing: la sessione che si sta guardando
    fetch('/api/v1/log?format=text' + (sid !== null ? '&session=' + sid : ''), opt).then(function (r) { if (!r.ok) throw new Error('HTTP ' + r.status); return r.text(); }).then(function (txt) {
      var a = document.createElement('a');
      a.href = URL.createObjectURL(new Blob([txt], { type: 'text/plain;charset=utf-8' }));
      a.download = 'registro_similfranz_' + new Date().toISOString().slice(0, 16).replace(/[T:]/g, '-') + '.txt';
      document.body.appendChild(a); a.click();
      setTimeout(function () { URL.revokeObjectURL(a.href); a.remove(); }, 500);
    }).catch(function () {
      // ANDROID (debriefing offline web 22/11): senza monitor si scarica il testo dall'istantanea della fine (o dal registro in memoria)
      var sn = snapShown();
      if (!sn) { msg('Registro non scaricato: il monitor non risponde'); return; }
      var txt = 'similFranz · registro della simulazione (copia di questo computer, monitor non raggiungibile)\n' +
        (sn.monitor ? 'Monitor: ' + sn.monitor + '\n' : '') + '\n' + sn.entries.map(function (e) {
          return fmtMMSS(e.t) + '  ' + hms(e.w) + '  ' + (KIND[e.k] || KIND.note)[1] + '  ' + (e.text || '') +
                 (e.tag === 'shock' && e.energy_j && (e.text || '').indexOf(e.energy_j + ' J') < 0 ? ' · ' + e.energy_j + ' J' : '') + (e.by ? ' · ' + e.by : '');
        }).join('\n') + '\n';
      var a = document.createElement('a');
      a.href = URL.createObjectURL(new Blob([txt], { type: 'text/plain;charset=utf-8' }));
      a.download = 'registro_similfranz_' + new Date().toISOString().slice(0, 16).replace(/[T:]/g, '-') + '.txt';
      document.body.appendChild(a); a.click();
      setTimeout(function () { URL.revokeObjectURL(a.href); a.remove(); }, 500);
      msg('Registro scaricato dalla copia di questo computer (monitor non raggiungibile)');
    });
  }

  // ---------- piano di debriefing: riepilogo + tutta la storia dello scenario ----------
  // "Punti chiave" (vista di partenza): ciò che serve per discutere — fasi, arresto e ROSC, azioni osservate, scariche,
  // farmaci, punti segnati. Il resto (ogni parametro ritoccato, sonde, timer) sta negli altri filtri e in "Tutto".
  var DBF = [['key', 'Punti chiave', 'KEY'], ['part', 'Partecipanti', ['monitor', 'electric', 'action']], ['el', 'Terapia elettrica', ['electric']],
             ['drug', 'Farmaci', ['drug']], ['istr', 'Istruttore', ['patient', 'phase', 'timer']], ['deb', 'Debriefing', ['plus', 'minus', 'note']],
             ['alarm', 'Allarmi', 'ALARM'],      // ANDROID (funzioni mancanti 22/11, IN-06): voci kind «alarm» e tag alarm_*
             ['all', 'Tutto', null]];
  var KEYK = ['phase', 'action', 'drug', 'plus', 'minus', 'note'], KEYT = ['arrest', 'rosc', 'shock', 'monitor_on'];
  function dbMatch(f, e) {
    if (!f[2]) return true;
    if (f[2] === 'KEY') return KEYK.indexOf(e.k) >= 0 || (e.tag && KEYT.indexOf(e.tag) >= 0);
    if (f[2] === 'ALARM') return e.k === 'alarm' || /^alarm_|^tech_resolved$/.test(e.tag || '');
    return f[2].indexOf(e.k) >= 0;
  }
  var MESI = ['gen', 'feb', 'mar', 'apr', 'mag', 'giu', 'lug', 'ago', 'set', 'ott', 'nov', 'dic'];
  function sessLabel(m) {
    var d = new Date((m.w || 0) * 1000);
    return (m.current ? 'In corso · ' : '') + d.getDate() + ' ' + MESI[d.getMonth()] + ' ' + ('0' + d.getHours()).slice(-2) + ':' + ('0' + d.getMinutes()).slice(-2) +
           ' · ' + (m.title || 'Sessione') + ' · ' + fmtMMSS(m.duration_s) + ' · ' + (m.entries || 0) + ' voci';
  }
  // una sessione precedente: si legge tutta (a blocchi) e si mostra in sola lettura
  function loadPast(sid) {
    var all = [];
    function step(since) {
      api('GET', '/api/v1/log?session=' + sid + '&since=' + since).then(function (j) {
        if (dbSess !== sid) return;
        if (!j || !j.ok) { $('dbErr').textContent = 'Sessione non letta: ' + ((j && j.error) || 'il monitor non risponde'); return; }
        all = all.concat(j.log || []);
        if (j.sessions) logSessions = j.sessions;
        if (j.more) { step(j.next); return; }
        dbPast = { entries: all, summary: j.summary || {} }; renderDebrief();
      }).catch(function () { if (dbSess === sid) $('dbErr').textContent = 'Sessione non letta: il monitor non risponde'; });
    }
    step(0);
  }
  function showSession(sid) {       // null = quella in corso (dal vivo)
    dbSess = sid; dbPast = null; $('dbErr').textContent = '';
    if (sid !== null) loadPast(sid);
    renderDebrief();
  }
  var dbClearArm = 0;
  function openDebrief() {
    if (notStarted()) { msg(NOT_STARTED); return; }
    dbArch = null; setDbMode();
    $('debrief').hidden = false; $('dbErr').textContent = ''; dbSess = null; dbPast = null; dbSessSig = '';
    draftEnsure(); renderDbMeta(); renderDebrief(); loadLog(); $('dbClose').focus();
  }
  function closeDebrief() {
    $('debrief').hidden = true; if (dbArch) { dbArch = null; setDbMode(); }
    if (draft && draft.stale) dropFromEnd();      // ANDROID (debriefing offline web 22/11): la fine non è più quella del monitor
    if (authLost) { var e = authLost; authLost = ''; showLogin(e); }      // ANDROID (debriefing offline web 22/11): PIN chiesto durante il piano
  }
  function renderDebrief() {
    var box = $('debrief'); if (!box || box.hidden) return;
    var arch = dbArch;
    if (!arch) {
    // elenco delle sessioni (più recenti in alto; le sessioni vuote non in corso non si mostrano)
    var list = logSessions.filter(function (m) { return m.current || m.entries > 0; }).slice().reverse();
    if (dbSess !== null && !list.some(function (m) { return m.id === dbSess && !m.current; })) { dbSess = null; dbPast = null; }
    var sig = list.map(function (m) { return m.id + '|' + sessLabel(m); }).join('\n') + '#' + dbSess;
    if (sig !== dbSessSig && document.activeElement !== $('dbSess')) {      // non si ricostruisce il menu mentre è aperto
      dbSessSig = sig;
      $('dbSess').innerHTML = list.map(function (m) {
        var sel = m.current ? dbSess === null : dbSess === m.id;
        return '<option value="' + (m.current ? '' : m.id) + '"' + (sel ? ' selected' : '') + '>' + esc(sessLabel(m)) + '</option>';
      }).join('') || '<option value="">In corso</option>';
    }
    }
    // ANDROID (debriefing offline web 22/11): aperto da FINE SCENARIO si mostra l'ISTANTANEA della fine (registro completo,
    // riepilogo, metriche RCP), aggiornata finché il monitor risponde: senza monitor il piano resta completo
    var sn = !arch && dbSess === null ? snapShown() : null;
    var past = !arch && dbSess !== null, ENT = arch ? (arch.entries || []) : past ? (dbPast ? dbPast.entries : []) : sn ? sn.entries : LOGE;
    $('dbPastNote').hidden = !past; $('dbArchNote').hidden = !arch;
    $('dbArchNote').textContent = arch && arch._local ? 'Simulazione salvata in questo computer, ' + (arch._sent ? 'inviata al monitor' : 'da inviare al monitor: si invia da sola appena risponde') + '. Sola lettura.' : 'Simulazione salvata in Storia Sim (sola lettura).';
    $('dbMeta').hidden = past;                      // titolo e squadra: della simulazione in corso (o di quella salvata)
    var sm = (arch ? arch.summary : past ? (dbPast ? dbPast.summary : {}) : sn ? sn.summary : logSum) || {};
    var cpr = arch ? arch.cpr : sn ? sn.cpr : (!past && state ? state.cpr_metrics : null);
    offRender();
    $('dbTiles').innerHTML = summaryTiles(sm, cpr).map(function (x) {
      return '<div class="dbtile' + (x.cls ? ' ' + x.cls : '') + '"><span>' + esc(x.lab) + '</span><b>' + esc(x.val) + '</b>' + (x.sub ? '<small>' + esc(x.sub) + '</small>' : '') + '</div>';
    }).join('');
    var f = DBF.filter(function (x) { return x[0] === dbFilter; })[0] || DBF[0];
    $('dbFilters').innerHTML = DBF.map(function (x) {
      var n = ENT.filter(function (e) { return dbMatch(x, e); }).length;
      return '<button data-f="' + x[0] + '"' + (x[0] === f[0] ? ' class="active" aria-pressed="true"' : ' aria-pressed="false"') + '>' + x[1] + ' <small>' + n + '</small></button>';
    }).join('');
    var rows = ENT.filter(function (e) { return dbMatch(f, e); });
    $('dbList').innerHTML = rows.map(function (e) { return logLine(e, true); }).join('') ||
      '<li class="empty"><span>' + (past && !dbPast ? 'Lettura della sessione…' : 'Nessuna voce in questa vista.') + '</span></li>';
    renderDebriefX();                 // ANDROID (funzioni mancanti 22/11): allarmi e trend
  }
  // riquadri del riepilogo (piano di debriefing, Storia Sim e PDF): {lab, val, sub, cls}
  function summaryTiles(sm, cpr) {
    sm = sm || {}; var tiles = [];
    function tile(lab, val, sub, cls) { tiles.push({ lab: lab, val: val, sub: sub, cls: cls }); }
    tile('Durata', fmtMMSS(sm.duration_s), (sm.entries || 0) + ' voci nel registro');
    if (sm.arrest_at_s !== undefined) {
      tile('Arresto cardiaco', fmtMMSS(sm.arrest_at_s), 'tempo di scenario', 'arr');
      [['to_recognition_s', 'Riconoscimento'], ['to_help_s', 'Chiamata di aiuto'], ['to_cpr_s', 'Prime compressioni'],
       ['to_first_shock_s', 'Prima scarica'], ['to_rosc_s', 'ROSC']].forEach(function (m) {
        tile(m[1], sm[m[0]] !== undefined ? fmtDur(sm[m[0]]) : '—', sm[m[0]] !== undefined ? "dall'arresto" : 'non registrato', sm[m[0]] !== undefined ? '' : 'miss');
      });
    }
    var sh = sm.shocks || [], dr = sm.drugs || [];
    tile('Scariche', String(sh.length), sh.length ? sh.map(function (x) { return x.energy_j + ' J a ' + fmtMMSS(x.t); }).join(' · ') : 'nessuna');
    tile('Farmaci', String(dr.length), dr.length ? dr.map(function (x) { return x.text + ' a ' + fmtMMSS(x.t); }).join(' · ') : 'nessuno');
    tile('Punti di forza', String(sm.plus || 0), '', 'plus');
    tile('Da discutere', String(sm.minus || 0), '', 'minus');
    var al = sm.alarms;                // ANDROID (funzioni mancanti 22/11, IN-06)
    if (al && (al.total || al.limits_changed || al.alarms_disabled))
      tile('Allarmi', String(al.total || 0), (al.high || 0) + ' alti · ' + (al.silenced || 0) + ' silenziati' + (al.mean_silence_s != null ? ' (dopo ' + al.mean_silence_s + ' s in media)' : '') +
           (al.limits_changed ? ' · ' + al.limits_changed + ' limiti cambiati' : '') + (al.alarms_disabled ? ' · ' + al.alarms_disabled + ' spenti' : ''), al.unanswered ? 'minus' : '');
    if (cpr && cpr.ccf_percent !== null && cpr.ccf_percent !== undefined)
      tile('Frazione di compressione', Math.round(cpr.ccf_percent) + ' %', 'arresto ' + fmtDur(cpr.arrest_s) + ', compressioni ' + fmtDur(cpr.cpr_s));
    return tiles;
  }
  function initLog() {
    renderQuick(); renderLog(); setAsk(null);
    $('ivGrid').addEventListener('click', function (e) {
      var b = e.target.closest('button[data-q]'); if (b) quickTap(parseInt(b.dataset.q, 10), b);
    });
    $('commentAdd').onclick = askSubmit;
    $('comment').addEventListener('keydown', function (e) {
      if (e.key === 'Enter') askSubmit();
      else if (e.key === 'Escape' && qMode !== null) { e.stopPropagation(); setAsk(null); }
    });
    $('logSave').onclick = downloadLog;
    $('dbSave').onclick = downloadLog;
    $('logDebrief').onclick = openDebrief;
    $('dbClose').onclick = requestCloseDebrief;
    $('dbFilters').addEventListener('click', function (e) {
      var b = e.target.closest('button[data-f]'); if (!b) return;
      dbFilter = b.dataset.f; renderDebrief();
    });
    $('dbSess').onchange = function () { var v = $('dbSess').value; showSession(v === '' ? null : parseInt(v, 10)); };
    $('dbNew').onclick = function () {                 // chiude la sessione in corso e ne apre una nuova (quella chiusa resta nell'elenco)
      api('POST', '/api/v1/log', { action: 'new_session' }).then(function (j) {
        if (j && j.controller) showControl(j.controller);
        if (j && j._status === 423) { $('dbErr').textContent = 'Sessione non aperta: il monitor è comandato da un altro controller. Prendi il comando e riprova.'; return; }
        if (!j || !j.ok) { $('dbErr').textContent = 'Sessione non aperta: ' + ((j && j.error) || 'il monitor non risponde'); return; }
        $('dbErr').textContent = ''; dbSess = null; dbPast = null; qCount = {}; renderQuick(); LOGE = []; logNext = 0; takeLog(j, true);
      }).catch(function () { $('dbErr').textContent = 'Sessione non aperta: il monitor non risponde'; });
    };
    $('dbClear').onclick = function () {               // doppio tocco: svuota il registro sul monitor (tutte le sessioni)
      var b = $('dbClear');
      if (dbClearArm && Date.now() - dbClearArm < 4000) {
        dbClearArm = 0; b.textContent = 'Elimina tutte le sessioni'; b.classList.remove('armed');
        api('POST', '/api/v1/log', { action: 'clear' }).then(function (j) {
          if (j && j.controller) showControl(j.controller);
          if (j && j._status === 423) { $('dbErr').textContent = 'Registro non svuotato: il monitor è comandato da un altro controller. Prendi il comando e riprova.'; return; }
          if (!j || !j.ok) { $('dbErr').textContent = 'Registro non svuotato: ' + ((j && j.error) || 'il monitor non risponde'); return; }
          $('dbErr').textContent = ''; dbSess = null; dbPast = null; qCount = {}; renderQuick(); LOGE = []; logNext = 0; takeLog(j, true);
        });
        return;
      }
      dbClearArm = Date.now(); b.textContent = 'Tocca di nuovo: elimina tutto'; b.classList.add('armed');
      setTimeout(function () { if (dbClearArm) { dbClearArm = 0; b.textContent = 'Elimina tutte le sessioni'; b.classList.remove('armed'); } }, 4000);
    };
    document.addEventListener('keydown', function (e) {
      if (e.key !== 'Escape') return;
      if (!$('sfDlg').hidden) { dlgClose(); return; }
      if (!$('debrief').hidden) requestCloseDebrief();
      else if (!$('storia').hidden) $('storia').hidden = true;
    });
    initStoria();
  }

  // ---------- FINE SCENARIO, squadra ed esito, Storia Sim (STORIA_SIM.md, 22/11) ----------
  // Ruoli: lo stesso elenco del tablet controller (richiesta utente 22/11: solo questi tre, niente «Altro…»)
  var ROLES = ['Medico', 'Infermiere', 'OTAS'];
  var OTHER = '__altro', DRAFT_KEY = 'sf_db_draft', draft = null, teamOpen = true;
  function p2(n) { return ('0' + n).slice(-2); }
  function dateText(ms) { var d = new Date(ms); return p2(d.getDate()) + '/' + p2(d.getMonth() + 1) + '/' + d.getFullYear() + ' ' + p2(d.getHours()) + ':' + p2(d.getMinutes()); }
  function hms(epochS) { if (!epochS) return ''; var d = new Date(epochS * 1000); return p2(d.getHours()) + ':' + p2(d.getMinutes()) + ':' + p2(d.getSeconds()); }

  // finestra di conferma in pagina: bt = [{t, cls, fn}] (il primo è la scelta prudente, senza fn = chiude e basta)
  var dlgBtns = [];
  function dialog(title, text, bt) {
    dlgBtns = bt;
    $('sfDlgT').textContent = title; $('sfDlgX').textContent = text;
    $('sfDlgB').innerHTML = bt.map(function (b, i) { return '<button data-i="' + i + '" class="' + (b.cls || '') + '">' + esc(b.t) + '</button>'; }).join('');
    $('sfDlg').hidden = false;
    var first = $('sfDlgB').firstChild; if (first) first.focus();      // il fuoco sulla scelta prudente (Annulla / Resta)
  }
  function dlgClose() { $('sfDlg').hidden = true; dlgBtns = []; }

  // proposta di titolo: scenario a fasi attivo → ultimo stato attivato → titolo della sessione → «Simulazione del gg/mm/aaaa»
  function proposeTitle() {
    if (scRun && scRun.active && scRun.name) return scRun.name;
    if (lastState && lastState.n) return lastState.n;
    var cur = logSessions.filter(function (m) { return m.current; })[0];
    if (cur && cur.title && cur.title !== 'Sessione libera') return cur.title;
    return 'Simulazione del ' + dateText(Date.now()).slice(0, 10);
  }
  // bozza (titolo + squadra) nel browser finché non si salva o si scarta
  function draftLoad() {
    if (draft) return draft;
    try { draft = JSON.parse(localStorage.getItem(DRAFT_KEY) || 'null'); } catch (e) { draft = null; }
    if (draft && !Array.isArray(draft.team)) draft.team = [];
    return draft;
  }
  function draftSave() { try { if (draft) localStorage.setItem(DRAFT_KEY, JSON.stringify(draft)); } catch (e) {} }
  function draftClear() { draft = null; try { localStorage.removeItem(DRAFT_KEY); } catch (e) {} }
  function draftEnsure() {
    if (!draftLoad()) { draft = { title: proposeTitle(), titleEdited: false, w: Date.now(), team: [], fromEnd: false }; draftSave(); }
    else if (!draft.titleEdited && !draft.fromEnd) { draft.title = proposeTitle(); draftSave(); }      // dopo FINE SCENARIO resta la proposta di quel momento
    return draft;
  }
  function memberRole(m) { return m.sel === OTHER ? (m.other || '').trim() || 'Altro' : (m.sel || ''); }

  function setDbMode() {           // testata del piano: simulazione in corso oppure simulazione di Storia Sim (sola lettura)
    var arch = !!dbArch;
    all('#debrief .dblive').forEach(function (el) { el.hidden = arch; });
    all('#debrief .dbarch').forEach(function (el) { el.hidden = !arch; });
    $('dbTitle').textContent = arch ? 'Storia Sim · ' + (dbArch.title || 'Simulazione') : 'Piano di debriefing';
    $('dbClose').textContent = arch ? "Torna all'elenco" : 'Chiudi';
  }

  function renderDbMeta() {
    var box = $('dbMeta'), h;
    if (dbArch) {
      var r = dbArch, team = r.team || [];
      h = '<div class="dbmrow"><div class="dbmro"><span>Titolo della simulazione</span><b>' + esc(r.title || '—') + '</b></div>' +
          '<div class="dbmdate"><span>Data e ora</span><b>' + esc(r.date_text || '') + '</b></div>' +
          '<div class="dbmdate"><span>Durata</span><b>' + fmtMMSS(r.duration_s) + '</b></div>' +
          '<div class="dbmdate"><span>Salvata da</span><b>' + esc(r.saved_by || (r.source === 'web' ? 'Web regia' : 'Controller')) + '</b></div></div>' +
          '<div class="dbteam ro"><h4>Squadra ed esito <small>' + team.length + (team.length === 1 ? ' partecipante' : ' partecipanti') + '</small></h4>' +
          (team.length ? '<div class="dbtlist">' + team.map(function (m, i) {
            return '<div class="dbtm"><div class="dbtmrow"><em>' + (i + 1) + '</em><b>' + esc(m.name || '—') + '</b><span class="dbrole">' + esc(m.role || '') + '</span></div>' +
                   '<div class="dbtmtx"><div class="dbok"><span>Obiettivi raggiunti</span><p>' + esc(m.achieved || '—') + '</p></div>' +
                   '<div class="dbko"><span>Da rinforzare</span><p>' + esc(m.reinforce || '—') + '</p></div></div></div>';
          }).join('') + '</div>' : '<p class="hint">Squadra non indicata.</p>') + '</div>';
      box.innerHTML = h; return;
    }
    var d = draftEnsure();
    h = '<div class="dbmrow"><label class="dbmtitle">Titolo della simulazione<input id="dbmTitle" type="text" maxlength="80" value="' + esc(d.title || '') + '" placeholder="es. Arresto in ambulanza"></label>' +
        '<div class="dbmdate"><span>Data e ora</span><b>' + dateText(d.w) + '</b></div></div>' +
        '<details class="dbteam" id="dbTeam"' + (teamOpen ? ' open' : '') + '><summary>Squadra ed esito <small>' + d.team.length + (d.team.length === 1 ? ' partecipante' : ' partecipanti') + '</small></summary>' +
        '<div class="dbtlist">' + d.team.map(function (m, i) {
          return '<div class="dbtm" data-i="' + i + '"><div class="dbtmrow"><em>' + (i + 1) + '</em>' +
            '<input type="text" data-f="name" maxlength="60" placeholder="Nome" value="' + esc(m.name || '') + '" aria-label="Nome del partecipante ' + (i + 1) + '">' +
            '<select data-f="sel" aria-label="Ruolo del partecipante ' + (i + 1) + '"><option value="">Ruolo…</option>' +
            ROLES.map(function (r) { return '<option' + (m.sel === r ? ' selected' : '') + '>' + esc(r) + '</option>'; }).join('') +
            '</select>' +
            '<input type="text" data-f="other" maxlength="40" placeholder="Quale ruolo" value="' + esc(m.other || '') + '"' + (m.sel === OTHER ? '' : ' hidden') + ' aria-label="Altro ruolo">' +
            '<button data-a="del" class="ghost small danger" title="Toglie questo partecipante">Togli</button></div>' +
            '<div class="dbtmtx"><label>Obiettivi raggiunti<textarea data-f="achieved" rows="2" maxlength="1000">' + esc(m.achieved || '') + '</textarea></label>' +
            '<label>Da rinforzare<textarea data-f="reinforce" rows="2" maxlength="1000">' + esc(m.reinforce || '') + '</textarea></label></div></div>';
        }).join('') + '</div>' +
        '<div class="dbtfoot"><button data-a="add" class="ghost small">+ Aggiungi partecipante</button>' +
        '<small>Bozza conservata in questo browser finché non la salvi in Storia Sim o la scarti.</small></div></details>';
    box.innerHTML = h;
  }
  function metaInput(e) {
    if (dbArch) return;
    var el = e.target, d = draftEnsure();
    if (el.id === 'dbmTitle') { d.title = el.value; d.titleEdited = true; draftSave(); return; }
    var row = el.closest('.dbtm'), f = el.dataset.f; if (!row || !f) return;
    var m = d.team[parseInt(row.dataset.i, 10)]; if (!m) return;
    m[f] = el.value; draftSave();
    if (f === 'sel') { var o = row.querySelector('[data-f="other"]'); o.hidden = el.value !== OTHER; if (!o.hidden) o.focus(); }
  }
  function metaClick(e) {
    var b = e.target.closest('button[data-a]'); if (!b || dbArch) return;
    var d = draftEnsure();
    if (b.dataset.a === 'add') {
      d.team.push({ name: '', sel: '', other: '', achieved: '', reinforce: '' }); draftSave(); teamOpen = true; renderDbMeta();
      var rows = all('#dbMeta .dbtm'), last = rows[rows.length - 1]; if (last) last.querySelector('[data-f="name"]').focus();
    } else if (b.dataset.a === 'del') {
      var i = parseInt(b.closest('.dbtm').dataset.i, 10), m = d.team[i]; if (!m) return;
      var go = function () { d.team.splice(i, 1); draftSave(); renderDbMeta(); };
      if (m.name || m.achieved || m.reinforce) dialog('Togliere ' + (m.name ? '«' + m.name + '»' : 'questo partecipante') + '?', 'Spariscono anche gli esiti scritti per lui.',
        [{ t: 'Annulla', cls: 'ghost' }, { t: 'Togli', cls: 'danger', fn: go }]);
      else go();
    }
  }

  // FINE SCENARIO: sempre con conferma (stessi testi del tablet controller), poi il piano di debriefing su «Tutto»
  function endTapped() {
    if (state && state.scenario_ended) { openFromEnd(); return; }        // già terminato: si torna al piano
    var pn = Object.keys(pending).length + (pendingRhythm !== null ? 1 : 0);
    dialog('Fine scenario?', 'Sul monitor compare «Scenario terminato» e la simulazione va in pausa. Qui si apre il piano di debriefing con tutta la cronologia.' +
      (pn > 0 ? '\n\n' + (pn === 1 ? '1 modifica' : pn + ' modifiche') + ' in attesa (' + $('pendSummary').textContent + ') non verranno applicate.' : ''),
      [{ t: 'Annulla', cls: 'ghost' }, { t: 'Fine scenario', cls: 'danger strong', fn: endScenarioNow }]);
  }
  function endScenarioNow() {
    var title = proposeTitle(), scenOn = !!(scRun && scRun.active);      // prima: end_scenario chiude lo scenario a fasi
    clearPending(); cmdSeq++;
    var body = { command: 'end_scenario' }; if (state) body.device_id = state.device_id;
    api('POST', '/api/v1/command', body).then(function (j) {
      var old = j && !j.ok && /unknown command/i.test(j.error || '');
      if (old) {                     // monitor vecchio: pausa (e «Termina» dello scenario a fasi) da qui
        command('set_simulation_running', false);
        if (scenOn) scPost({ action: 'stop' });
        msg('Il monitor non ha ancora la schermata «Scenario terminato» (versione vecchia): simulazione messa in pausa.');
      } else handle(j);
      if (j && (j.ok || old)) {
        var d = draftEnsure();
        if (!d.titleEdited) d.title = title;
        d.w = Date.now(); d.fromEnd = true;
        // ANDROID (fix analisi 22/11, BUG-12): sessione del registro e metriche RCP fissate ADESSO (un altro controller può aprire
        // una sessione nuova o azzerare durante il debriefing: «Salva» archivia comunque la simulazione terminata)
        d.sess = j.log_session !== undefined && j.log_session !== null ? j.log_session : logSess;
        d.cpr = j.state && j.state.cpr_metrics ? j.state.cpr_metrics : (state && state.cpr_metrics) || null;
        endEpisode = d.sess;
        snapFromMemory(d); draftSave();      // ANDROID (debriefing offline web 22/11): istantanea subito, poi completa dal monitor
        openFromEnd();                       // (openFromEnd chiede subito l'istantanea completa al monitor)
        if (scenOn) loadScenarios();
      }
    }).catch(function () { msg('Fine scenario non arrivata al monitor: il monitor non risponde'); });
  }
  function openFromEnd() {
    var d = draftEnsure(); d.fromEnd = true;
    // ANDROID (fix analisi 22/11, BUG-12): riaperto a scenario già terminato (anche da un altro controller): sessione e metriche
    // di quella fine, se la bozza non le ha già
    if (d.sess === undefined || d.sess === null) { d.sess = logSess; d.cpr = (state && state.cpr_metrics) || null; }
    if (endEpisode === null) endEpisode = d.sess;
    if (!d.snap || d.snap.sess !== d.sess) snapFromMemory(d);      // ANDROID (debriefing offline web 22/11)
    draftSave();
    dbFilter = 'all'; openDebrief(); snapTake(true);
  }
  // ANDROID (fix analisi 22/11, BUG-03): la bozza «da fine scenario» vale SOLO se il monitor è davvero in «Scenario terminato»
  // e per quella fine: stessa sessione del registro, oppure fine vista da questa pagina senza che lo scenario sia ripartito
  // (endEpisode, azzerato appena la fase non è più «ended»). Una bozza rimasta in localStorage (pagina chiusa durante il
  // debriefing, scenario ripreso o riavviato da un altro controller) non porta MAI ad azzerare un paziente dal vivo.
  var endEpisode = null;
  function fromEndValid(d) {
    if (!d || !d.fromEnd || !state || scenPhase(state) !== 'ended') return false;
    if (d.sess === undefined || d.sess === null) return false;                // bozza vecchia (prima del fix): mai
    return d.sess === logSess || d.sess === endEpisode;
  }
  function dropFromEnd() {
    var d = draftLoad();
    if (d && d.fromEnd) { d.fromEnd = false; delete d.sess; delete d.cpr; delete d.snap; delete d.stale; draftSave(); }
  }
  // ANDROID (debriefing offline web 22/11): se la fine non è più quella del monitor (ripreso, azzerato, riavviato…) MENTRE il piano
  // aperto da FINE SCENARIO è sullo schermo con la sua istantanea, il piano non si chiude e non si svuota: la simulazione si può
  // ancora salvare (dall'istantanea), ma chiudendo lo scenario NON si azzera (BUG-03 intatto). Altrimenti come prima.
  function keepStale() {
    var d = draftLoad();
    return !!(d && d.fromEnd && d.snap && d.snap.sess === d.sess && !$('debrief').hidden && !dbArch && dbSess === null);
  }
  function staleOrDrop() {
    if (keepStale()) { if (!draft.stale) { draft.stale = true; draftSave(); offRender(); } return; }
    dropFromEnd();
  }
  // chiusura: aperto da FINE SCENARIO si sceglie se salvare; dopo Salva o Non salvare lo scenario si azzera da solo
  function requestCloseDebrief() {
    if (dbArch) { closeDebrief(); return; }
    var d = draftLoad();
    if (!d || !d.fromEnd) { closeDebrief(); return; }
    if (d.stale && d.snap) {                  // ANDROID (debriefing offline web 22/11): si salva ancora, ma niente azzeramento
      dialog('Chiudere il piano di debriefing?',
        'Il monitor non è più a questa fine scenario (ripreso, azzerato o riavviato): puoi ancora salvare la simulazione in Storia Sim, ma lo scenario NON si azzera.',
        [{ t: 'Resta nel debriefing', cls: 'ghost' },
         { t: 'Non salvare', cls: 'danger', fn: function () { draftClear(); closeDebrief(); msg('Simulazione non salvata · lo scenario non è stato azzerato (il monitor non è più a quella fine)'); } },
         { t: 'Salva in Storia Sim', cls: 'primary', fn: function () { saveToArchive(true); } }]);
      return;
    }
    if (!fromEndValid(d)) { dropFromEnd(); closeDebrief(); return; }      // ANDROID (fix analisi 22/11, BUG-03)
    dialog('Chiudere il piano di debriefing?',
      'Puoi salvare questa simulazione in Storia Sim: titolo, squadra con gli esiti, riepilogo e cronologia. Dopo «Salva» o «Non salvare» lo scenario si azzera da solo, come con «Reset scenario».' +
      (monOnline ? '' : '\n\nIl monitor ora non risponde: la simulazione resta in questo computer e si invia da sola, l\'azzeramento si fa appena il monitor risponde (solo se è ancora a questa fine).'),
      [{ t: 'Resta nel debriefing', cls: 'ghost' },
       { t: 'Non salvare', cls: 'danger', fn: function () { var ctx = endCtx(draftLoad()); draftClear(); closeDebrief(); autoReset(ctx, 'Simulazione non salvata'); } },
       { t: 'Salva in Storia Sim', cls: 'primary', fn: function () { saveToArchive(false); } }]);
  }
  // tutte le voci di una sessione del registro (a blocchi)
  function fetchSession(sid, cb) {
    var got = [];
    (function step(since) {
      withTimeout(api('GET', '/api/v1/log?' + (sid !== null && sid !== undefined ? 'session=' + sid + '&' : '') + 'since=' + since), 8000).then(function (j) {      // ANDROID (debriefing offline web 22/11): mai appeso
        if (!j || !j.ok) { cb(null, (j && j.error) || 'il monitor non risponde'); return; }
        got = got.concat(j.log || []);
        if (j.more) { step(j.next); return; }
        cb({ entries: got, summary: j.summary || {} });
      }).catch(function () { cb(null, 'il monitor non risponde'); });
    })(0);
  }
  function newId(ms) {
    var d = new Date(ms), r = Math.floor(Math.random() * 65536).toString(16);
    return d.getFullYear() + p2(d.getMonth() + 1) + p2(d.getDate()) + '-' + p2(d.getHours()) + p2(d.getMinutes()) + p2(d.getSeconds()) + '-' + ('000' + r).slice(-4);
  }
  function buildRecord(log) {
    var d = draftEnsure(), now = Date.now(), sn = d.snap || {};
    var entries = log.entries.map(function (e) { var o = {}; Object.keys(e).forEach(function (k) { o[k] = e[k]; }); o.wall = e.w; return o; });
    var endE = null; entries.forEach(function (e) { if (e.tag === 'scenario_end') endE = e; });
    var sm = log.summary || {};
    var dur = sm.duration_s !== undefined ? sm.duration_s : endE ? endE.t : (state ? state.scenario_time_s : 0);
    return {
      id: newId(now), v: 1, saved_w: Math.floor(now / 1000), date_text: dateText(d.w || now),
      title: (d.title || '').trim() || proposeTitle(), source: 'web', saved_by: 'similFranz ' + clientName,
      monitor: sn.monitor || (state ? state.device_id : ''), layout: sn.layout || (state ? state.layout : ''), duration_s: dur || 0,
      layout_name: sn.layout_name || layoutName(state && state.layout),      // ANDROID (debriefing offline web 22/11): per il PDF senza monitor
      team: d.team.filter(function (m) { return (m.name || '').trim() || m.sel || (m.achieved || '').trim() || (m.reinforce || '').trim(); })
        .map(function (m) { return { name: (m.name || '').trim(), role: memberRole(m), achieved: (m.achieved || '').trim(), reinforce: (m.reinforce || '').trim() }; }),
      summary: sm, cpr: d.cpr ? d.cpr : sn.cpr ? sn.cpr : (state && state.cpr_metrics ? state.cpr_metrics : null),      // BUG-12: metriche della fine
      end: endE ? { t: endE.t, w: endE.w } : null, entries: entries,
      trend: sn.trend && sn.trend.length ? sn.trend : trendForRecord()      // ANDROID (funzioni mancanti 22/11, IN-14): passo 60 s
    };
  }
  function archErr(j, what) {
    if (j && j._status === 404 && !j.item) return what + ': il monitor non ha ancora Storia Sim (aggiorna l\'app del monitor).';
    if (j && j._status === 423) return what + ': il monitor è comandato da un altro controller. Prendi il comando e riprova.';
    if (j && j._status === 413) return what + ': simulazione troppo grande per l\'archivio del monitor.';
    return what + ': ' + ((j && j.error) || 'il monitor non risponde');
  }
  // ANDROID (debriefing offline web 22/11): il registro si rilegge dal monitor se risponde, altrimenti si usa l'ISTANTANEA della
  // fine. Se il monitor non risponde (o chiede il PIN) il record resta in coda in questo browser (localStorage) e si invia da
  // solo appena risponde; un rifiuto vero del monitor (423, 413, 404…) resta come prima: errore nel piano, niente azzeramento.
  // noReset = la fine non è più quella del monitor (keepStale): si salva soltanto.
  function saveToArchive(noReset) {
    $('dbErr').textContent = 'Salvataggio in Storia Sim…';
    var d0 = draftLoad(), sid = d0 && d0.sess !== undefined && d0.sess !== null ? d0.sess : logSess;   // BUG-12: la sessione terminata
    var ctx = noReset ? null : endCtx(d0);
    function finish(rec, queued) {
      $('dbErr').textContent = ''; draftClear(); closeDebrief();
      var what = queued ? 'Salvata in questo computer: «' + rec.title + '», da inviare al monitor (si invia da sola appena risponde; il PDF è già in Storia Sim)'
                        : 'Salvata in Storia Sim: «' + rec.title + '»';
      if (noReset) { msg(what + ' · lo scenario non è stato azzerato (il monitor non è più a quella fine)'); return; }
      autoReset(ctx, what);
    }
    var go = function (log) {
      var rec = buildRecord(log);
      if (!monOnline || authLost) { queuePut(rec); finish(rec, true); return; }
      withTimeout(api('POST', '/api/v1/archive', rec), 10000).then(function (j) {
        if (j && (j._status === 401 || j._status === 429)) { if (j._status === 401) handle(j); queuePut(rec); finish(rec, true); return; }
        if (!j || !j.ok) { $('dbErr').textContent = archErr(j, 'Simulazione non salvata') + ' Lo scenario non è stato azzerato.'; return; }
        finish(rec, false);
      }, function () { queuePut(rec); finish(rec, true); });      // nessuna risposta: in coda (stesso id = il monitor sostituisce)
    };
    var sn = d0 && d0.snap && d0.snap.sess === sid ? d0.snap : null;
    if (!monOnline || authLost) { if (sn) { go(sn); return; } }
    fetchSession(sid, function (log, err) {
      if (log) { go(log); return; }
      if (sn) { go(sn); return; }
      $('dbErr').textContent = 'Simulazione non salvata: registro non letto (' + err + '). Lo scenario non è stato azzerato.';
    });
  }

  // ---------- ANDROID (debriefing offline web 22/11): debriefing senza monitor ----------
  // Dopo FINE SCENARIO l'istruttore può andare in sala debriefing col computer e perdere il collegamento col monitor.
  // 1) ISTANTANEA della fine (bozza.snap): registro completo della sessione terminata, riepilogo, metriche RCP, nome del
  //    monitor e layout. Presa al tocco di FINE SCENARIO, aggiornata finché il monitor risponde, conservata con la bozza.
  // 2) CODA di Storia Sim (localStorage sf_arch_queue): record completi salvati senza monitor, inviati da soli al ritorno.
  // 3) AZZERAMENTO IN SOSPESO (localStorage sf_reset_pending): mandato al ritorno SOLO se il monitor è ancora in «Scenario
  //    terminato» della stessa fine (stessa sessione del registro, stesso monitor); altrimenti scartato e detto. «Avvia» lo
  //    manda prima di partire.
  var QUEUE_KEY = 'sf_arch_queue', RESET_KEY = 'sf_reset_pending', snapBusy = false, snapAgain = false, snapAt = 0, snapT = null;
  function lsGet(k, def) { try { var v = JSON.parse(localStorage.getItem(k) || 'null'); return v === null ? def : v; } catch (e) { return def; } }
  function lsSet(k, v) { try { if (v === null) localStorage.removeItem(k); else localStorage.setItem(k, JSON.stringify(v)); return true; } catch (e) { return false; } }
  function layoutName(id) {
    var n = id || '';
    ((state && state.layouts) || []).forEach(function (l) { if (l.id === id) n = l.name; });
    return n;
  }
  function snapFromMemory(d) {          // subito, da quello che la pagina ha già (il monitor può sparire un attimo dopo)
    if (!d || d.sess === undefined || d.sess === null) return;
    var old = d.snap && d.snap.sess === d.sess ? d.snap : null;
    var mine = logSess === d.sess ? LOGE.slice() : [];
    d.snap = { sess: d.sess, entries: old && old.entries.length > mine.length ? old.entries : mine,
               summary: old && old.entries.length > mine.length ? old.summary : (logSess === d.sess ? logSum : {}),
               cpr: d.cpr || (old && old.cpr) || null, monitor: state ? state.device_id : (old && old.monitor) || '',
               layout: state ? state.layout : (old && old.layout) || '', layout_name: state ? layoutName(state.layout) : (old && old.layout_name) || '',
               at: Date.now(), full: !!(old && old.full) };
  }
  // istantanea completa dal monitor (sessione fissata alla fine); force = subito, altrimenti al massimo ogni 2 s
  function snapTake(force) {
    var d = draftLoad();
    if (!d || !d.fromEnd || d.stale || d.sess === undefined || d.sess === null || !monOnline || authLost) return;
    if (snapBusy) { snapAgain = true; return; }
    if (!force && Date.now() - snapAt < 2000) { snapSoon(); return; }
    snapBusy = true; snapAgain = false; snapAt = Date.now();
    var sid = d.sess;
    fetchSession(sid, function (log) {
      snapBusy = false;
      var d2 = draftLoad();
      if (log && d2 && d2.fromEnd && !d2.stale && d2.sess === sid) {
        var endNow = state && scenPhase(state) === 'ended' && logSess === sid;
        if (endNow && state.cpr_metrics) d2.cpr = state.cpr_metrics;
        if (endNow) trendFetch();          // ANDROID (IN-14): il trend della fine (per Storia Sim)
        d2.snap = { sess: sid, entries: log.entries, summary: log.summary || {}, cpr: d2.cpr || null, trend: endNow ? trendForRecord() : (d2.snap && d2.snap.trend) || [],
                    monitor: state ? state.device_id : '', layout: state ? state.layout : '', layout_name: layoutName(state && state.layout),
                    at: Date.now(), full: true };
        draftSave();                // se localStorage è pieno l'istantanea resta comunque in memoria (draft)
        renderDebrief();
      }
      if (snapAgain) snapSoon();
    });
  }
  function snapSoon() {
    var d = draft;
    if (!d || !d.fromEnd || d.stale || d.sess !== logSess) return;
    clearTimeout(snapT); snapT = setTimeout(function () { snapTake(false); }, 600);
  }
  // istantanea da mostrare nel piano «in corso»: quella della fine; il registro dal vivo se è più avanti (stessa sessione)
  function snapShown() {
    var d = draft || draftLoad();
    if (!d || !d.fromEnd || !d.snap || d.snap.sess !== d.sess) {
      return LOGE.length ? { entries: LOGE, summary: logSum, cpr: state ? state.cpr_metrics : null, monitor: state ? state.device_id : '' } : null;
    }
    var sn = d.snap;
    if (monOnline && !d.stale && logSess === d.sess && LOGE.length > sn.entries.length)
      return { sess: sn.sess, entries: LOGE, summary: logSum, cpr: d.cpr || sn.cpr, monitor: sn.monitor, layout: sn.layout, layout_name: sn.layout_name };
    return sn;
  }
  // contesto dell'azzeramento automatico: quale fine (sessione) di quale monitor
  function endCtx(d) {
    return { sess: d && d.sess !== undefined && d.sess !== null ? d.sess : logSess, dev: (d && d.snap && d.snap.monitor) || (state ? state.device_id : ''), at: Date.now() };
  }
  // dopo Salva / Non salvare: azzera adesso se il monitor risponde, altrimenti «azzeramento in sospeso»
  function autoReset(ctx, what) {
    if (!monOnline || authLost) { lsSet(RESET_KEY, ctx); offRender(); msg(what + ' · azzeramento in sospeso: lo scenario si azzera appena il monitor risponde (solo se è ancora a questa fine)'); return; }
    resetScenarioNow().then(function (ok) {
      if (ok) { msg(what + ' · scenario azzerato'); return; }
      if (lastCmdNet) { lsSet(RESET_KEY, ctx); offRender(); msg(what + ' · azzeramento in sospeso: lo scenario si azzera appena il monitor risponde (solo se è ancora a questa fine)'); return; }
      msg(what + ', ma lo scenario NON è stato azzerato (' + lastCmdErr + '): usa «Azzera scenario…»', true);
    });
  }
  // azzeramento in sospeso: cb(true) se è stato mandato ed eseguito (o non c'era), cb(false) altrimenti
  var resetBusy = false;
  function resetPendingRun(j, cb) {
    var p = lsGet(RESET_KEY, null); cb = cb || function () {};
    if (!p) { cb(true); return; }
    if (resetBusy || !state) { cb(false); return; }
    var sid = j && j.log_session !== undefined ? j.log_session : logSess, ph = scenPhase(state);
    if (ph !== 'ended' || sid !== p.sess || (p.dev && state.device_id !== p.dev)) {
      lsSet(RESET_KEY, null); offRender();
      msg('Azzeramento in sospeso scartato: il monitor non è più a quella fine scenario (' +
          ({ ready: 'è PRONTO', running: 'scenario in corso', paused: 'scenario in pausa', ended: 'un\'altra fine scenario' })[ph] + '). Niente è stato azzerato.');
      cb(true); return;
    }
    resetBusy = true;
    resetScenarioNow().then(function (ok) {
      resetBusy = false;
      if (ok) { msg('Azzeramento in sospeso eseguito: scenario azzerato'); cb(true); return; }
      if (!lastCmdNet) { lsSet(RESET_KEY, null); offRender(); msg('Azzeramento in sospeso NON eseguito (' + lastCmdErr + '): usa «Azzera scenario…»', true); }
      cb(false);
    });
  }
  // coda di Storia Sim nel browser
  function queuePut(rec) {
    var q = lsGet(QUEUE_KEY, []).filter(function (x) { return x.rec && x.rec.id !== rec.id; });
    q.push({ rec: rec, at: Date.now() });
    if (!lsSet(QUEUE_KEY, q)) msg('Attenzione: questo browser non riesce a conservare la simulazione (memoria piena o privata). Stampa il PDF da Storia Sim prima di chiudere la pagina.', true);
    localQ[rec.id] = rec; offRender();
  }
  var localQ = {}, sentQ = {}, qBusy = false, qAt = 0;          // localQ: copia in memoria (anche se localStorage non va)
  function queueAll() {
    var q = lsGet(QUEUE_KEY, []), seen = {};
    q.forEach(function (x) { if (x.rec) seen[x.rec.id] = 1; });
    Object.keys(localQ).forEach(function (id) { if (!seen[id] && !sentQ[id]) q.push({ rec: localQ[id], at: 0 }); });
    return q;
  }
  function queueDrop(id) { lsSet(QUEUE_KEY, lsGet(QUEUE_KEY, []).filter(function (x) { return x.rec && x.rec.id !== id; })); delete localQ[id]; offRender(); }
  function queueFlush() {
    var q = queueAll();
    if (!q.length || qBusy || !monOnline || authLost || !token) return;
    if (Date.now() - qAt < 4000) return;
    qBusy = true; qAt = Date.now();
    var it = q[0].rec;
    withTimeout(api('POST', '/api/v1/archive', it), 15000).then(function (j) {
      qBusy = false;
      if (j && j.ok) {
        queueDrop(it.id); sentQ[it.id] = it;
        msg('Simulazione «' + (it.title || 'Simulazione') + '» inviata a Storia Sim del monitor');
        if (!$('storia').hidden) loadStoria();
        if (dbArch && dbArch.id === it.id) { dbArch._sent = true; renderDebrief(); }
        qAt = 0; setTimeout(queueFlush, 300);
        return;
      }
      if (j && (j._status === 401 || j._status === 429)) { handle(j); return; }
      var e = lsGet(QUEUE_KEY, []); e.forEach(function (x) { if (x.rec && x.rec.id === it.id) x.err = archErr(j, 'Invio non riuscito'); }); lsSet(QUEUE_KEY, e);
      qAt = Date.now() + 26000; offRender();      // rifiuto vero: si riprova fra mezzo minuto
    }, function () { qBusy = false; });
  }
  // ad ogni lettura riuscita dello stato: istantanea, coda, azzeramento in sospeso. wasDown = il monitor è appena tornato
  // dopo letture fallite; first = prima lettura riuscita di questa pagina
  function offSync(j, wasDown, first) {
    if (wasDown) {
      var d = draftLoad(), sid = j && j.log_session !== undefined ? j.log_session : logSess;
      if (d && d.fromEnd && !d.stale && d.sess !== undefined && d.sess !== null && sid !== d.sess) {
        endEpisode = null; staleOrDrop();          // durante il buco il monitor è passato a un'altra sessione: non è più quella fine
      }
    }
    if ((wasDown || first) && draft && draft.fromEnd && !draft.stale) snapTake(true);
    queueFlush();
    if (lsGet(RESET_KEY, null)) resetPendingRun(j);
  }
  // righe discrete: nel piano (monitor non raggiungibile) e sotto il Registro (in sospeso)
  function offRender() {
    var off = $('dbOff'), pq = $('offQ'); if (!off || !pq) return;
    var t = '';
    if (!$('debrief').hidden && authLost) t = 'Il monitor chiede di nuovo il PIN: il debriefing funziona lo stesso; l\'accesso compare chiudendo il piano.';
    else if (!$('debrief').hidden && !monOnline) t = 'Monitor non raggiungibile: il debriefing funziona lo stesso.';
    if (!t && draft && draft.stale && !$('debrief').hidden && !dbArch) t = 'Il monitor non è più a questa fine scenario: puoi ancora salvarla, ma lo scenario non si azzera.';
    off.textContent = t; off.hidden = !t;
    var q = queueAll(), r = lsGet(RESET_KEY, null), parts = [];
    if (q.length) parts.push((q.length === 1 ? '1 simulazione' : q.length + ' simulazioni') + ' da inviare al monitor' + (q.some(function (x) { return x.err; }) ? ' (il monitor l\'ha rifiutata: si riprova)' : ''));
    if (r) parts.push('azzeramento in sospeso');
    pq.textContent = parts.length ? '⏳ ' + parts.join(' · ') : ''; pq.hidden = !parts.length;
    pq.title = (q.length ? 'Le simulazioni salvate senza monitor sono in questo browser (Storia Sim, con il PDF) e si inviano da sole appena il monitor risponde. ' : '') +
               (r ? 'L\'azzeramento si fa appena il monitor risponde, solo se è ancora a quella fine scenario; «Avvia» lo manda prima di partire.' : '');
  }

  // Storia Sim: elenco dal monitor; aprendone una si vede nel piano di debriefing in sola lettura
  function openStoria() { $('storia').hidden = false; loadStoria(); $('stClose').focus(); }
  // ANDROID (debriefing offline web 22/11): in cima le simulazioni salvate in questo computer e non ancora arrivate al monitor
  // («da inviare al monitor»), apribili e stampabili in PDF anche senza monitor
  function localItems(skip) {
    return queueAll().filter(function (x) { return !(skip && skip[x.rec.id]); }).map(function (x) {
      var r = x.rec;
      return '<button class="stitem local" data-lid="' + esc(r.id) + '"><b>' + esc(r.title || 'Simulazione') + '</b>' +
             '<span>' + esc(r.date_text || '') + ' · ' + fmtMMSS(r.duration_s) + ' · ' + (r.team || []).length + ((r.team || []).length === 1 ? ' partecipante' : ' partecipanti') + '</span>' +
             '<small class="stq">' + (x.err ? 'da inviare al monitor · ' + esc(x.err) : 'da inviare al monitor') + '</small></button>';
    }).join('');
  }
  function loadStoria() {
    $('stErr').textContent = ''; $('stList').innerHTML = localItems() + '<p class="hint">Lettura dell\'archivio…</p>';
    withTimeout(api('GET', '/api/v1/archive'), 8000).then(function (j) {
      if (j && j._status === 401) { $('stList').innerHTML = localItems(); handle(j); return; }
      if (!j || !j.ok) { $('stList').innerHTML = localItems(); $('stErr').textContent = archErr(j, 'Storia Sim non letta'); return; }
      var it = j.items || [], onMon = {};
      it.forEach(function (x) { onMon[x.id] = 1; });
      $('stList').innerHTML = localItems(onMon) + it.map(function (x) {
        return '<button class="stitem" data-id="' + esc(x.id) + '"><b>' + esc(x.title || 'Simulazione') + '</b>' +
               '<span>' + esc(x.date_text || '') + ' · ' + fmtMMSS(x.duration_s) + ' · ' + (x.team_n || 0) + (x.team_n === 1 ? ' partecipante' : ' partecipanti') + '</span>' +
               '<small>' + esc(x.saved_by || (x.source === 'web' ? 'Web regia' : 'Controller')) + '</small></button>';
      }).join('') || (queueAll().length ? '' : '<p class="hint">Nessuna simulazione salvata. Si salvano chiudendo il piano di debriefing aperto da «FINE SCENARIO».</p>');
    }).catch(function () { $('stList').innerHTML = localItems(); $('stErr').textContent = 'Storia Sim del monitor non letta: il monitor non risponde' + (queueAll().length ? ' (sopra quelle salvate in questo computer)' : ''); });
  }
  function openLocal(id) {
    var x = queueAll().filter(function (q) { return q.rec.id === id; })[0] || (sentQ[id] ? { rec: sentQ[id] } : null);
    if (!x) { loadStoria(); return; }
    dbArch = JSON.parse(JSON.stringify(x.rec)); dbArch._local = true; dbArch._sent = !!sentQ[id]; dbFilter = 'key'; setDbMode();
    $('debrief').hidden = false; $('dbErr').textContent = '';
    renderDbMeta(); renderDebrief(); $('dbClose').focus();
  }
  function openArchived(id) {
    $('stErr').textContent = '';
    api('GET', '/api/v1/archive?id=' + encodeURIComponent(id)).then(function (j) {
      if (!j || !j.ok || !j.item) { $('stErr').textContent = j && j._status === 404 ? 'Simulazione non trovata: forse è stata eliminata.' : archErr(j, 'Simulazione non letta'); return; }
      dbArch = j.item; dbFilter = 'key'; setDbMode();
      $('debrief').hidden = false; $('dbErr').textContent = '';
      renderDbMeta(); renderDebrief(); $('dbClose').focus();
    }).catch(function () { $('stErr').textContent = 'Simulazione non letta: il monitor non risponde'; });
  }
  function deleteArchived() {
    var r = dbArch; if (!r) return;
    if (r._local && !r._sent) {          // ANDROID (debriefing offline web 22/11): solo in questo computer
      dialog('Eliminare questa simulazione?', '«' + (r.title || 'Simulazione') + '» del ' + (r.date_text || '') + ' non è ancora arrivata al monitor: eliminandola qui non verrà più inviata. I PDF già salvati restano.',
        [{ t: 'Annulla', cls: 'ghost' }, { t: 'Elimina', cls: 'danger strong', fn: function () { queueDrop(r.id); closeDebrief(); loadStoria(); msg('Simulazione eliminata da questo computer (non verrà inviata)'); } }]);
      return;
    }
    dialog('Eliminare questa simulazione?', '«' + (r.title || 'Simulazione') + '» del ' + (r.date_text || '') + ' sparisce dalla Storia Sim del monitor. I PDF già salvati restano.',
      [{ t: 'Annulla', cls: 'ghost' }, { t: 'Elimina', cls: 'danger strong', fn: function () {
        api('POST', '/api/v1/archive', { 'delete': r.id }).then(function (j) {
          if (!j || !j.ok) { $('dbErr').textContent = archErr(j, 'Simulazione non eliminata'); return; }
          closeDebrief(); loadStoria(); msg('Simulazione eliminata da Storia Sim');
        }).catch(function () { $('dbErr').textContent = 'Simulazione non eliminata: il monitor non risponde'; });
      } }]);
  }

  // PDF: pagina dedicata, impaginata come un verbale di simulazione (A4, margini 2 cm), stampata dal browser
  function pdfHtml(r) {
    var team = r.team || [], ent = r.entries || [], sm = r.summary || {}, cm = r.cpr, gen = dateText(Date.now());
    var layName = r.layout_name || r.layout || '—';      // ANDROID (debriefing offline web 22/11): nome fissato alla fine
    ((state && state.layouts) || []).forEach(function (l) { if (l.id === r.layout) layName = l.name; });
    function cssStr(t) { return String(t).replace(/[\\"<>]/g, '').replace(/[\r\n]+/g, ' '); }      // testo dentro content:"…" del CSS
    function row(k, v) { return '<tr><th>' + esc(k) + '</th><td>' + esc(v) + '</td></tr>'; }
    function cell(t) { return esc(t || '—').replace(/\n/g, '<br>'); }
    var h = '<!DOCTYPE html><html lang="it"><head><meta charset="utf-8"><title>' + esc((r.date_text || '').replace(/[\/:]/g, '-') + ' ' + (r.title || 'Simulazione')) + '</title><style>' +
      '@page{size:A4;margin:2cm;@bottom-left{content:"' + cssStr('similFranz · ' + (r.title || 'Simulazione') + ' · ' + (r.date_text || '')) + '";font:7.5pt -apple-system,"Segoe UI",Roboto,Arial,sans-serif;color:#6b7684}' +
      '@bottom-right{content:"Pagina " counter(page) " di " counter(pages);font:7.5pt -apple-system,"Segoe UI",Roboto,Arial,sans-serif;color:#6b7684}}' +
      '*{box-sizing:border-box}html,body{background:#fff;color:#1d232b;margin:0}' +
      'body{font:10pt/1.45 -apple-system,"Segoe UI",Roboto,"Helvetica Neue",Arial,sans-serif;-webkit-print-color-adjust:exact;print-color-adjust:exact}' +
      '@media screen{body{max-width:21cm;margin:24px auto;padding:2cm;box-shadow:0 0 0 1px #d6dbe1}}' +
      '.brand{display:flex;justify-content:space-between;align-items:baseline;font-size:8.5pt;color:#6b7684;letter-spacing:.04em;padding-bottom:5pt;border-bottom:1.5pt solid #1f7ea6}' +
      '.brand b{color:#1f7ea6;font-weight:700}' +
      'h1{font-size:19pt;line-height:1.2;margin:16pt 0 3pt;font-weight:700;color:#11161c}' +
      '.sub{color:#5b6673;font-size:10pt;margin:0 0 14pt}' +
      'h2{font-size:9pt;text-transform:uppercase;letter-spacing:.09em;color:#1f7ea6;margin:18pt 0 6pt;padding-bottom:3pt;border-bottom:.5pt solid #c9d1da;break-after:avoid;page-break-after:avoid}' +
      'table{width:100%;border-collapse:collapse}th,td{text-align:left;vertical-align:top;padding:4pt 6pt}' +
      'thead{display:table-header-group}tr{break-inside:avoid;page-break-inside:avoid}' +
      'thead th{font-size:8pt;text-transform:uppercase;letter-spacing:.06em;color:#5b6673;border-bottom:1pt solid #9aa5b1;font-weight:700}' +
      'tbody td{border-bottom:.5pt solid #e1e5ea}tbody tr:nth-child(even) td{background:#f5f7f9}' +
      '.meta td{background:none!important;border-bottom:none}.meta{width:auto;min-width:60%}.meta th{width:38mm;color:#5b6673;font-weight:600;padding:2.5pt 6pt 2.5pt 0}.meta td{padding:2.5pt 6pt;font-weight:600}' +
      '.team td.n{font-weight:700;width:30mm}.team td.r{width:34mm;color:#3b4652}.team td.ok{border-left:2pt solid #2f9a5a}.team td.ko{border-left:2pt solid #d08a12}' +
      '.num{text-align:right;font-variant-numeric:tabular-nums;white-space:nowrap}.mono{font-variant-numeric:tabular-nums;white-space:nowrap}' +
      '.cat{white-space:nowrap;font-size:8.5pt;color:#3b4652}.by{color:#6b7684;font-size:8.5pt}' +
      '.k-electric .cat{color:#b85a00}.k-drug .cat{color:#6a3fb0}.k-plus .cat{color:#23804a}.k-minus .cat{color:#a86a00}.k-phase .cat{color:#1f7ea6}' +
      '.tl td{padding:3pt 6pt;font-size:9pt}.empty{color:#6b7684;font-style:italic}' +
      '.foot{margin-top:22pt;padding-top:5pt;border-top:.5pt solid #c9d1da;font-size:8pt;color:#6b7684;display:flex;justify-content:space-between}' +
      '</style></head><body>' +
      '<div class="brand"><span><b>similFranz</b></span><span>Verbale di simulazione · Storia Sim</span></div>' +
      '<h1>' + esc(r.title || 'Simulazione') + '</h1><p class="sub">Simulazione sanitaria del ' + esc(r.date_text || '') + '</p>' +
      '<table class="meta"><tbody>' + row('Data e ora', r.date_text || '—') + row('Durata', fmtMMSS(r.duration_s) + ' (tempo di scenario)') +
      row('Monitor', r.monitor || '—') + row('Layout', layName) + row('Salvata da', r.saved_by || (r.source === 'web' ? 'Web regia' : 'Controller')) +
      (r.end ? row('Fine scenario', fmtMMSS(r.end.t) + (r.end.w ? ' · ore ' + hms(r.end.w) : '')) : '') + '</tbody></table>' +
      '<h2>Squadra ed esito</h2>' + (team.length ?
        '<table class="team"><thead><tr><th>Nome</th><th>Ruolo</th><th>Obiettivi raggiunti</th><th>Da rinforzare</th></tr></thead><tbody>' +
        team.map(function (m) { return '<tr><td class="n">' + cell(m.name) + '</td><td class="r">' + cell(m.role) + '</td><td class="ok">' + cell(m.achieved) + '</td><td class="ko">' + cell(m.reinforce) + '</td></tr>'; }).join('') +
        '</tbody></table>' : '<p class="empty">Squadra non indicata.</p>') +
      '<h2>Riepilogo</h2><table><thead><tr><th>Voce</th><th class="num">Valore</th><th>Dettaglio</th></tr></thead><tbody>' +
      summaryTiles(sm, null).map(function (x) { return '<tr><td>' + esc(x.lab) + '</td><td class="num"><b>' + esc(x.val) + '</b></td><td>' + esc(x.sub || '') + '</td></tr>'; }).join('') +
      '</tbody></table>';
    if (cm && (cm.ccf_percent !== null && cm.ccf_percent !== undefined || (cm.shocks || []).length)) {
      h += '<h2>Metriche RCP</h2><table><thead><tr><th>Metrica</th><th class="num">Valore</th></tr></thead><tbody>' +
        '<tr><td>Frazione di compressione (CCF)</td><td class="num">' + (cm.ccf_percent !== null && cm.ccf_percent !== undefined ? Math.round(cm.ccf_percent) + ' %' : '— (arresto troppo breve)') + '</td></tr>' +
        '<tr><td>Tempo in arresto</td><td class="num">' + fmtDur(cm.arrest_s) + '</td></tr><tr><td>Tempo di compressioni</td><td class="num">' + fmtDur(cm.cpr_s) + '</td></tr>' +
        (cm.shocks || []).map(function (s, i) {
          return '<tr><td>Scarica ' + (s.n || i + 1) + (s.pulse ? ' (su ritmo con polso' + (s.sync ? ', SYNC)' : ', senza SYNC)') : ': pause attorno alla scarica') + '</td><td class="num">' +
                 (s.pulse ? '—' : 'pre ' + fmtDur(s.pre_s) + ' · post ' + fmtDur(s.post_s)) + '</td></tr>';
        }).join('') + '</tbody></table>';
    }
    // ANDROID (funzioni mancanti 22/11): allarmi (IN-06) e trend (IN-14)
    if (sm.alarms) h += '<h2>Allarmi</h2>' + alarmBlock(sm.alarms, true);
    if (r.trend && r.trend.length > 1) h += '<h2>Trend dei parametri</h2>' + trendSvg(r.trend, true) + trendTable(r.trend, r.trend.length > 40 ? 300 : 60).replace('class="limits trtab"', 'class="tl"');
    h += '<h2>Cronologia</h2>' + (ent.length ?
      '<table class="tl"><thead><tr><th class="num">Tempo</th><th>Ora</th><th>Categoria</th><th>Evento</th></tr></thead><tbody>' +
      ent.map(function (e) {
        var k = KIND[e.k] || KIND.note, x = e.tag === 'shock' && e.energy_j && (e.text || '').indexOf(e.energy_j + ' J') < 0 ? ' · ' + e.energy_j + ' J' : '';
        return '<tr class="k-' + esc(e.k || 'note') + '"><td class="num">' + fmtMMSS(e.t) + '</td><td class="mono">' + hms(e.wall || e.w) + '</td><td class="cat">' + esc(k[1]) + '</td>' +
               '<td>' + esc((e.text || '') + x) + (e.by ? ' <span class="by">· ' + esc(e.by) + '</span>' : '') + '</td></tr>';
      }).join('') + '</tbody></table>' : '<p class="empty">Nessuna voce nel registro.</p>') +
      '<div class="foot"><span>Documento generato il ' + esc(gen) + ' · similFranz · SIMULAZIONE — NON PER USO CLINICO</span><span>' + esc(r.id || '') + '</span></div>' +
      '<script>window.onload=function(){setTimeout(function(){window.print();},250);};<\/script></body></html>';
    return h;
  }
  function printArchived() {
    if (!dbArch) return;
    var w = window.open('', '_blank');
    if (!w) { $('dbErr').textContent = 'Il browser ha bloccato la finestra di stampa: consenti le finestre a comparsa per questa pagina e riprova.'; return; }
    w.document.open(); w.document.write(pdfHtml(dbArch)); w.document.close();
  }

  function initStoria() {
    $('sfDlgB').addEventListener('click', function (e) {
      var b = e.target.closest('button[data-i]'); if (!b) return;
      var it = dlgBtns[parseInt(b.dataset.i, 10)]; dlgClose();
      if (it && it.fn) it.fn();
    });
    $('sfDlg').addEventListener('click', function (e) { if (e.target === $('sfDlg')) dlgClose(); });
    $('dbMeta').addEventListener('input', metaInput);
    $('dbMeta').addEventListener('change', metaInput);
    $('dbMeta').addEventListener('click', metaClick);
    $('dbMeta').addEventListener('toggle', function (e) { if (e.target.id === 'dbTeam') teamOpen = e.target.open; }, true);
    $('logStoria').onclick = openStoria;
    $('stClose').onclick = function () { $('storia').hidden = true; };
    $('stReload').onclick = loadStoria;
    $('stList').addEventListener('click', function (e) { var b = e.target.closest('.stitem'); if (b) { if (b.dataset.lid) openLocal(b.dataset.lid); else openArchived(b.dataset.id); } });
    $('dbPdf').onclick = printArchived;
    $('dbDel').onclick = deleteArchived;
    setDbMode();
  }

  var PRESET_STATES = [
    { n: 'Sano',                  d: 10, r: 'sinus',             p: { heart_rate_bpm: 80,  systolic_mmhg: 120, diastolic_mmhg: 80, spo2_percent: 98, respiratory_rate_rpm: 14, etco2_mmhg: 38 }, now: { crt_s: 2 } },
    { n: 'Ipoteso',               d: 30, r: 'sinus',             p: { heart_rate_bpm: 105, systolic_mmhg: 90,  diastolic_mmhg: 55, spo2_percent: 95, respiratory_rate_rpm: 20, etco2_mmhg: 32 }, now: { crt_s: 3 } },
    { n: 'Shock',                 d: 60, r: 'sinus_tachycardia', p: { heart_rate_bpm: 135, systolic_mmhg: 75,  diastolic_mmhg: 45, spo2_percent: 90, respiratory_rate_rpm: 28, etco2_mmhg: 25 }, now: { crt_s: 5 } },
    { n: 'Insuff. respiratoria',  d: 60, r: 'sinus_tachycardia', p: { heart_rate_bpm: 120, systolic_mmhg: 135, diastolic_mmhg: 85, spo2_percent: 82, respiratory_rate_rpm: 32, etco2_mmhg: 55 } },
    { n: 'Bradicardia instabile', d: 30, r: 'sinus_bradycardia', p: { heart_rate_bpm: 38,  systolic_mmhg: 80,  diastolic_mmhg: 50, spo2_percent: 91, respiratory_rate_rpm: 10, etco2_mmhg: 30 }, now: { crt_s: 4 } },
    { n: 'TV con polso',          d: 10, r: 'vt_pulse',          p: { heart_rate_bpm: 180, systolic_mmhg: 85,  diastolic_mmhg: 55, spo2_percent: 92, respiratory_rate_rpm: 22 } },
    { n: 'Arresto in FV',         d: 0,  r: 'vf',                p: {} },
    { n: 'Post-ROSC',             d: 30, r: 'sinus_tachycardia', p: { heart_rate_bpm: 110, systolic_mmhg: 95,  diastolic_mmhg: 60, spo2_percent: 94, respiratory_rate_rpm: 12, etco2_mmhg: 40 }, now: { crt_s: 3 } }
  ];
  var customStates = [];
  try { customStates = JSON.parse(localStorage.getItem('sf_states') || '[]'); } catch (e) {}
  var lastState = null;
  try { lastState = JSON.parse(sessionStorage.getItem('sf_last_state') || 'null'); } catch (e) {}
  var selState = null, savingState = false;          // selState: indice nello elenco completo
  function allStates() { return PRESET_STATES.concat(customStates); }
  function describeState(st) {
    var parts = [];
    if (st.r) parts.push(RHYTHMS[st.r] || st.r);
    Object.keys(st.p || {}).forEach(function (k) { parts.push(PNAME[k] + ' → ' + st.p[k]); });
    Object.keys(st.now || {}).forEach(function (k) { parts.push(P[k].l + ' → ' + st.now[k]); });
    if (st.ecg) parts.push('ECG → ' + morphShort(st.ecg));               // ANDROID (regia morfologie ECG 22/11)
    return parts.join(' · ') || '—';
  }
  function renderStates() {
    var sts = allStates();
    $('stateChips').innerHTML = sts.map(function (st, i) {
      var isLast = lastState && lastState.n === st.n;
      return '<button class="stchip' + (isLast ? ' last' : '') + (i === selState ? ' open' : '') + (i >= PRESET_STATES.length ? ' custom' : '') + '" data-i="' + i + '"' +
             ' title="' + esc(describeState(st)) + '">' + (isLast ? '<u>▸</u>' : '') + esc(st.n) +
             '<small>' + (st.d > 0 ? st.d + ' s' : 'subito') + '</small></button>';
    }).join('');
    var exp = $('stateExp');
    if (savingState) return;                         // il pannello sta mostrando il salvataggio
    if (selState === null || !sts[selState]) { exp.hidden = true; exp.innerHTML = ''; return; }
    var st = sts[selState];
    exp.hidden = false;
    exp.innerHTML = '<div class="sxhead"><b>' + esc(st.n) + '</b><span>' + esc(describeState(st)) + '</span></div>' +
      '<div class="sxrow"><span class="tlab">Transizione</span><span class="seg" id="sxDur">' +
      [0, 10, 30, 60, 120].map(function (t) { return '<button data-t="' + t + '"' + (t === st.d ? ' class="active"' : '') + '>' + (t ? (t === 120 ? '2 min' : t + ' s') : 'subito') + '</button>'; }).join('') +
      '</span><span class="spacer"></span>' +
      (selState >= PRESET_STATES.length ? '<button class="ghost small danger" id="sxDel">Elimina</button>' : '') +
      '<button class="ghost small" id="sxClose">Chiudi</button>' +
      '<button class="primary" id="sxGo">Attiva lo stato</button></div>';
    var dur = st.d;
    all('#sxDur button').forEach(function (b) {
      b.onclick = function () { dur = parseInt(b.dataset.t, 10); all('#sxDur button').forEach(function (x) { x.className = x === b ? 'active' : ''; }); };
    });
    $('sxClose').onclick = function () { selState = null; renderStates(); };
    if ($('sxDel')) $('sxDel').onclick = function () {
      customStates.splice(selState - PRESET_STATES.length, 1);
      localStorage.setItem('sf_states', JSON.stringify(customStates));
      selState = null; renderStates();
    };
    $('sxGo').onclick = function () { if (tapOnce('sxGo', 1500)) activateState(st, dur); };   // ANDROID (fix analisi 22/11, BUG-02): come il controller
  }
  // ANDROID (fix analisi 22/11, BUG-07): registro, «Stato attivato» e stato corrente SOLO se il monitor ha eseguito i comandi;
  // se li rifiuta (423, 400…) resta l'errore a schermo e la scheda dello stato aperta per riprovare
  function activateState(st, dur) {
    var hasTr = (st.p && Object.keys(st.p).length > 0) || st.r || st.ecg, prs = [];
    if (hasTr) {
      var val = { params: st.p || {}, duration_s: dur };
      if (st.ecg) val.ecg_morphology = st.ecg;                                    // ANDROID (regia morfologie ECG 22/11)
      if (st.r) { val.rhythm = st.r; val.onset = onsetMode; lastRhy = { name: 'transition', value: val }; }   // ANDROID (insorgenza 22/11)
      prs.push(command('transition', val));
    }
    Object.keys(st.now || {}).forEach(function (k) { prs.push(command(P[k].now, st.now[k])); });
    Promise.all(prs).then(function (oks) {
      var good = oks.filter(function (x) { return x; }).length;
      if (good === 0 && oks.length) return;              // nulla eseguito: il messaggio d'errore è già a schermo
      lastState = { n: st.n, scen: state && state.scenario_time_s !== undefined ? state.scenario_time_s : null, w: Date.now() };
      try { sessionStorage.setItem('sf_last_state', JSON.stringify(lastState)); } catch (e) {}
      var part = good < oks.length;
      logAdd('Stato «' + st.n + '» attivato' + (part ? ' in parte' : '') + ' · ' + (dur > 0 ? dur + ' s' : 'subito'), 'phase');
      if (part) msg('Stato «' + st.n + '» attivato solo in parte: il monitor ha rifiutato ' + (oks.length - good) + ' comando/i (controlla i valori)', true);
      else msg('Stato «' + st.n + '» attivato');
      selState = null; clearPending(); renderStates();
    });
  }
  function saveStateUI() {                           // fotografa i valori veri correnti in un nuovo stato
    if (!state) return;
    savingState = true; selState = null;
    var exp = $('stateExp'); exp.hidden = false;
    exp.innerHTML = '<div class="sxrow"><span class="tlab">Nome del nuovo stato</span>' +
      '<input type="text" id="sxName" maxlength="28" placeholder="es. Dopo il carico di fluidi">' +
      '<span class="spacer"></span><button class="ghost small" id="sxNCancel">Annulla</button>' +
      '<button class="primary" id="sxNSave">Salva</button></div>' +
      '<p class="hint">Verranno salvati: ' + esc(describeState(snapState('…'))) + '</p>';
    $('sxName').focus();
    var close = function () { savingState = false; renderStates(); };
    $('sxNCancel').onclick = close;
    var save = function () {
      var n = $('sxName').value.trim(); if (!n) { $('sxName').focus(); return; }
      customStates.push(snapState(n));
      try { localStorage.setItem('sf_states', JSON.stringify(customStates)); } catch (e) {}
      close();
    };
    $('sxNSave').onclick = save;
    $('sxName').addEventListener('keydown', function (e) { if (e.key === 'Enter') save(); });
  }
  function snapState(n) {
    var s = state;
    return { n: n, d: 30, r: s.rhythm,
      p: { heart_rate_bpm: s.heart_rate_bpm, systolic_mmhg: s.systolic_mmhg, diastolic_mmhg: s.diastolic_mmhg,
           spo2_percent: s.spo2_percent, respiratory_rate_rpm: s.respiratory_rate_rpm, etco2_mmhg: s.etco2_mmhg },
      now: { temperature_c: Number(s.temperature_c), crt_s: s.crt_s },
      // ANDROID (regia morfologie ECG 22/11): la morfologia dell'ECG entra nello stato salvato se è alterata
      ecg: morphIsNormal(morphOf(s)) ? undefined : morphPayload(morphOf(s)) };
  }


  // =====================================================================
  // SCENARI A FASI (salvati sul monitor: li vedono anche gli altri controller)
  // Uno scenario = fasi in ordine; ogni fase = blocco di parametri + tempo di transizione + nota.
  // Le fasi NON avanzano da sole: decide l'istruttore. I controlli manuali restano attivi in parallelo.
  // =====================================================================
  var scenarios = null, scRun = { active: false }, scSel = null, scJump = null, scStopArm = 0, scBusy = false, scSig = '';
  var railMode = 'states';
  try { railMode = sessionStorage.getItem('sf_rail') === 'scen' ? 'scen' : 'states'; } catch (e) {}
  var SC_KEYS = [['heart_rate_bpm', 'FC', 'bpm', 0, 300], ['systolic_mmhg', 'PA sist.', 'mmHg', 0, 300], ['diastolic_mmhg', 'PA diast.', 'mmHg', 0, 200],
                 ['spo2_percent', 'SpO₂', '%', 0, 100], ['respiratory_rate_rpm', 'FR', 'atti/min', 0, 60], ['etco2_mmhg', 'EtCO₂', 'mmHg', 0, 150],
                 ['cvp_mmhg', 'PVC', 'mmHg', 0, 40]];   // ANDROID (funzioni mancanti 22/11, IN-13)
  var SC_MAXPH = 24;

  function setRail(m) {
    railMode = m === 'scen' ? 'scen' : 'states';
    try { sessionStorage.setItem('sf_rail', railMode); } catch (e) {}
    all('#railSw button').forEach(function (b) {
      var onb = b.dataset.v === railMode; b.className = onb ? 'active' : ''; b.setAttribute('aria-selected', onb ? 'true' : 'false');
    });
    $('railStates').hidden = railMode !== 'states';
    $('railScen').hidden = railMode !== 'scen';
    if (railMode === 'scen') { if (scenarios === null) loadScenarios(); scSig = ''; renderScen(); }
  }
  function scenById(id) { return (scenarios || []).filter(function (s) { return s.id === id; })[0] || null; }
  function durTxt(t) { t = +t || 0; return !t ? 'subito' : (t % 60 === 0 ? (t / 60) + ' min' : t + ' s'); }
  function describePhase(ph) {
    var parts = [];
    if (ph.rhythm) parts.push(RHYTHMS[ph.rhythm] || ph.rhythm);
    var p = ph.params || {};
    if (p.heart_rate_bpm !== undefined) parts.push('FC ' + p.heart_rate_bpm);
    if (p.systolic_mmhg !== undefined || p.diastolic_mmhg !== undefined)
      parts.push('PA ' + (p.systolic_mmhg !== undefined ? p.systolic_mmhg : '·') + '/' + (p.diastolic_mmhg !== undefined ? p.diastolic_mmhg : '·'));
    if (p.spo2_percent !== undefined) parts.push('SpO₂ ' + p.spo2_percent);
    if (p.respiratory_rate_rpm !== undefined) parts.push('FR ' + p.respiratory_rate_rpm);
    if (p.etco2_mmhg !== undefined) parts.push('EtCO₂ ' + p.etco2_mmhg);
    if (ph.temperature_c !== undefined) parts.push('Temp ' + Number(ph.temperature_c).toFixed(1));
    if (p.cvp_mmhg !== undefined) parts.push('PVC ' + p.cvp_mmhg);                                   // ANDROID (funzioni mancanti 22/11)
    if (ph.ventilation && ph.ventilation.mode) parts.push({ ventilator: 'Ventilatore', bag: 'Pallone', spontaneous: 'Spontaneo' }[ph.ventilation.mode] || ph.ventilation.mode);
    if (ph.crt_s !== undefined) parts.push('Refill ' + ph.crt_s + ' s');
    if (ph.ecg_morphology) parts.push('ECG ' + morphShort(ph.ecg_morphology === 'normal' ? 'normal' : morphMerge(ECG_NORMAL, ph.ecg_morphology)));   // ANDROID (regia morfologie ECG 22/11)
    return parts.join(' · ') || '—';
  }
  function loadScenarios() {
    return api('GET', '/api/v1/scenarios').then(function (j) {
      if (!j || !j.ok) { if (scenarios === null) scenarios = []; scSig = ''; renderScen(); return; }
      scenarios = j.scenarios || [];
      if (j.scenario) onScenario(j.scenario);
      scSig = ''; renderScen();
    }).catch(function () {});
  }
  function setScenarios(list) { scenarios = list || []; scSig = ''; renderScen(); }
  // stato della conduzione, presente in ogni risposta del monitor
  function onScenario(r) {
    var prev = '';
    try { prev = sessionStorage.getItem('sf_sc_prev') || ''; } catch (e) {}
    var cur = r.active ? r.id + '#' + r.phase : '';
    if (cur !== prev) {
      try { sessionStorage.setItem('sf_sc_prev', cur); } catch (e) {}
      scJump = null;
      if (r.active && !scenById(r.id)) loadScenarios();       // avviato da un altro controller: serve l'elenco aggiornato
    }
    scRun = r;
    $('railSwDot').hidden = !r.active;
    renderScen();
  }
  function scPost(body) {
    if (scBusy) return Promise.resolve(null);
    scBusy = true; cmdSeq++;
    return api('POST', '/api/v1/scenarios', body).then(function (j) {
      scBusy = false;
      if (j && !j.ok && j._status !== 401 && j._status !== 423 && j._status !== 429) { msg('Scenario: ' + (j.error || 'errore')); return j; }
      handle(j); return j;
    }).catch(function () { scBusy = false; msg('Monitor non raggiungibile'); return null; });
  }
  function renderScen() {
    var box = $('railScen');
    if (!box || railMode !== 'scen') return;
    var run = scRun && scRun.active ? scRun : null, sc = run ? scenById(run.id) : null;
    var sig = [run ? run.id + '#' + run.phase + '#' + run.phases : '-', scSel, scJump, scenarios === null ? 'n' : scenarios.length, sc ? sc.phases.length : 0, scStopArm ? 1 : 0].join('|');
    if (sig === scSig) { var t = $('scT'); if (t && run) t.textContent = fmtMMSS(run.phase_time_s); return; }
    scSig = sig;
    var h = '';
    if (run) {
      h += '<div class="rhead"><h3>Scenario in corso</h3></div>' +
           '<div class="scname"><b>' + esc(run.name) + '</b><span>fase ' + (run.phase + 1) + ' di ' + run.phases + '</span></div>' +
           '<div class="statechips scphases">';
      var phs = sc ? sc.phases : [];
      for (var i = 0; i < run.phases; i++) {
        var ph = phs[i] || { name: i === run.phase ? run.phase_name : 'Fase ' + (i + 1) }, isCur = i === run.phase;
        h += '<button class="stchip scph' + (isCur ? ' last' : (i < run.phase ? ' done' : '')) + (i === scJump ? ' open' : '') + '" data-i="' + i + '"' +
             (isCur ? ' aria-current="step"' : '') + ' title="' + esc(describePhase(ph)) + '">' +
             '<em>' + (isCur ? '▸' : (i + 1)) + '</em><span>' + esc(ph.name) + (isCur ? '<i>in corso</i>' : '') + '</span>' +
             '<small' + (isCur ? ' id="scT"' : '') + '>' + (isCur ? fmtMMSS(run.phase_time_s) : durTxt(ph.duration_s)) + '</small></button>';
      }
      h += '</div>';
      if (scJump !== null && phs[scJump] && scJump !== run.phase) {
        h += '<div class="stateexp"><div class="sxhead"><b>' + (scJump + 1) + ' · ' + esc(phs[scJump].name) + '</b><span>' + esc(describePhase(phs[scJump])) +
             ' · transizione: ' + durTxt(phs[scJump].duration_s) + '</span>' + (phs[scJump].note ? '<span class="scnote">' + esc(phs[scJump].note) + '</span>' : '') + '</div>' +
             '<div class="sxrow"><button class="ghost small" id="scJumpNo">Annulla</button><button class="primary" id="scJumpGo">Vai a questa fase</button></div></div>';
      } else if (run.phase_note) {
        h += '<div class="scnotebox"><span class="tlab">Nota per l\'istruttore</span>' + esc(run.phase_note) + '</div>';
      }
      h += run.next_name ? '<button class="primary scnext" id="scNext" data-to="' + (run.phase + 1) + '"><small>Fase successiva</small>' + esc(run.next_name) + '</button>'
                         : '<p class="railhint sclast">Ultima fase: lo scenario resta qui finché non lo termini.</p>';
      h += '<div class="railfoot"><button id="scEditRun" class="ghost small wide">Modifica lo scenario</button>' +
           '<button id="scStop" class="ghost small wide danger' + (scStopArm ? ' armed' : '') + '">' + (scStopArm ? 'Tocca di nuovo: termina' : 'Termina scenario') + '</button></div>';
    } else {
      h += '<div class="rhead"><h3>Scenari</h3></div>' +
           '<p class="railhint">Fasi già pronte, che fai avanzare tu. Durante lo scenario i controlli manuali restano attivi.</p>';
      if (scenarios === null) h += '<p class="railhint">Lettura dal monitor…</p>';
      else if (!scenarios.length) h += '<p class="railhint">Nessuno scenario salvato sul monitor.</p>';
      else {
        h += '<div class="statechips">' + scenarios.map(function (s) {
          return '<button class="stchip scitem' + (s.id === scSel ? ' open' : '') + '" data-id="' + esc(s.id) + '"><span>' + esc(s.name) + '</span><small>' +
                 s.phases.length + (s.phases.length === 1 ? ' fase' : ' fasi') + '</small></button>';
        }).join('') + '</div>';
        var sel = scSel ? scenById(scSel) : null;
        if (sel) {
          h += '<div class="stateexp scprev"><div class="sxhead"><b>' + esc(sel.name) + '</b>' + (sel.notes ? '<span>' + esc(sel.notes) + '</span>' : '') + '</div>' +
               '<ol class="scplist">' + sel.phases.map(function (ph) {
                 return '<li><b>' + esc(ph.name) + '</b><span>' + esc(describePhase(ph)) + ' · ' + durTxt(ph.duration_s) + '</span></li>';
               }).join('') + '</ol>' +
               '<div class="sxrow"><button class="ghost small" id="scSelNo">Chiudi</button><button class="ghost small" id="scSelEdit">Modifica</button>' +
               '<button class="primary" id="scSelGo">Avvia lo scenario</button></div></div>';
        }
      }
      h += '<div class="railfoot"><button id="scNew" class="ghost small wide">+ Nuovo scenario</button></div>';
    }
    box.innerHTML = h;
    all('.scitem', box).forEach(function (b) { b.onclick = function () { scSel = scSel === b.dataset.id ? null : b.dataset.id; renderScen(); }; });
    all('.scph', box).forEach(function (b) {
      b.onclick = function () { var i = parseInt(b.dataset.i, 10); scJump = (i === scRun.phase || i === scJump) ? null : i; renderScen(); };
    });
    var bind = function (id, fn) { var el = $(id); if (el) el.onclick = fn; };
    bind('scSelNo', function () { scSel = null; renderScen(); });
    bind('scSelEdit', function () { openBuilder(scenById(scSel)); });
    bind('scSelGo', function () {
      var id = scSel; scSel = null;
      scPost({ action: 'start', id: id, phase: 0 }).then(function (j) { if (j && j.ok) { clearPending(); msg('Scenario avviato'); } });
    });
    bind('scNew', function () { openBuilder(null); });
    bind('scJumpNo', function () { scJump = null; renderScen(); });
    bind('scJumpGo', function () { if (!tapOnce('scen', 1000)) return; var n = scJump; scPost({ action: 'goto', phase: n }).then(function (j) { if (j && j.ok) clearPending(); }); });
    // ANDROID (fix analisi 22/11, BUG-02): «Fase successiva» = vai alla fase N+1 (bersaglio assoluto, scritto nel pulsante quando
    // è stato disegnato) invece di «next» (relativo): ripetuto non salta una fase. In più una finestra di 1 s, perché il pulsante
    // si ridisegna nello stesso punto con la fase dopo e il secondo clic di un doppio clic lo premerebbe.
    bind('scNext', function () {
      var to = parseInt(this.dataset.to, 10);
      if (isNaN(to) || !scRun || !scRun.active || scRun.phase >= to || !tapOnce('scen', 1000)) return;
      scPost({ action: 'goto', phase: to }).then(function (j) { if (j && j.ok) clearPending(); });
    });
    bind('scEditRun', function () { var s = scenById(scRun.id); if (s) openBuilder(s); else loadScenarios(); });
    // ANDROID (22/11, richiesta utente): «Termina» dello scenario a fasi = FINE SCENARIO (conferma, debriefing, salva/non salvare,
    // azzeramento), come nel controller: lo scenario avviato da qui ha avviato anche la simulazione
    bind('scStop', function () { scStopArm = 0; endTapped(); });
  }

  // ---------- costruzione dello scenario ----------
  var bd = null, bdDirty = false, bdCloseArm = 0, bdDelArm = 0;
  function bdPhase(src) {                              // fase della bozza: i campi vuoti significano "non cambia"
    var ph = { name: '', note: '', duration_s: 0, rhythm: '', v: {}, temp: '', crt: '', ecg: null };
    if (!src) return ph;
    // ANDROID (regia morfologie ECG 22/11): morfologia della fase: null = non cambia, 'normal' = ECG normale, oggetto = alterazioni
    if (src.ecg_morphology === 'normal') ph.ecg = 'normal';
    else if (src.ecg_morphology && typeof src.ecg_morphology === 'object') ph.ecg = morphMerge(ECG_NORMAL, src.ecg_morphology);
    ph.name = src.name || ''; ph.note = src.note || ''; ph.duration_s = +src.duration_s || 0; ph.rhythm = src.rhythm || '';
    SC_KEYS.forEach(function (k) { if (src.params && src.params[k[0]] !== undefined) ph.v[k[0]] = String(src.params[k[0]]); });
    if (src.temperature_c !== undefined) ph.temp = String(src.temperature_c);
    if (src.crt_s !== undefined) ph.crt = String(src.crt_s);
    // ANDROID (funzioni mancanti 22/11): campi della fase che l'editor non mostra (ventilazione, linea arteriosa, esami, T2,
    // malfunzionamenti): restano com'erano quando si salva
    ph.keep = {}; ['ventilation', 'art_line', 'labs', 'temperature2_c', 'device_faults'].forEach(function (k) { if (src[k] !== undefined) ph.keep[k] = src[k]; });
    return ph;
  }
  function openBuilder(sc) {
    bd = { id: sc ? sc.id : null, name: sc ? sc.name : '', notes: sc ? (sc.notes || '') : '', phases: sc ? sc.phases.map(bdPhase) : [bdPhase(null)] };
    bdDirty = false; bdCloseArm = 0; bdDelArm = 0;
    $('scbErr').textContent = '';
    $('scBuilder').hidden = false;
    renderBuilder();
    var n = $('scbName'); if (n && !sc) n.focus();
  }
  function closeBuilder() { $('scBuilder').hidden = true; bd = null; bdDirty = false; scSig = ''; renderScen(); }
  function bdErr(t) { $('scbErr').textContent = t || ''; }
  function renderBuilder() {
    if (!bd) return;
    $('scbTitle').textContent = bd.id ? 'Modifica scenario' : 'Nuovo scenario';
    $('scbDel').hidden = !bd.id; $('scbDup').hidden = !bd.id;
    $('scbDel').textContent = 'Elimina scenario'; $('scbDel').classList.remove('armed');
    $('scbClose').textContent = 'Chiudi';
    $('scbAdd').disabled = $('scbSnap').disabled = bd.phases.length >= SC_MAXPH;
    var DUR = [0, 10, 30, 60, 120];
    var h = '<div class="scbmeta"><label>Nome dello scenario<input type="text" id="scbName" maxlength="60" placeholder="es. Dolore toracico, arresto, ROSC" value="' + esc(bd.name) + '"></label>' +
            '<label>Paziente, contesto, obiettivi<textarea id="scbNotes" maxlength="600" rows="2" placeholder="Chi è il paziente, dove siamo, che cosa deve imparare il gruppo">' + esc(bd.notes) + '</textarea></label></div>' +
            '<p class="hint">Ogni fase è un quadro clinico: i campi vuoti non cambiano il paziente. Le fasi non avanzano da sole, le fai avanzare tu durante lo scenario. Massimo ' + SC_MAXPH + ' fasi.</p>';
    h += bd.phases.map(function (ph, i) {
      var custom = DUR.indexOf(ph.duration_s) < 0;
      return '<section class="scbph" data-i="' + i + '"><div class="scbphhead"><em>' + (i + 1) + '</em>' +
        '<input type="text" data-f="name" maxlength="40" placeholder="Nome della fase (es. Peggiora)" value="' + esc(ph.name) + '" aria-label="Nome della fase ' + (i + 1) + '">' +
        '<button class="ghost small" data-a="up" title="Sposta prima" aria-label="Sposta la fase prima"' + (i === 0 ? ' disabled' : '') + '>↑</button>' +
        '<button class="ghost small" data-a="down" title="Sposta dopo" aria-label="Sposta la fase dopo"' + (i === bd.phases.length - 1 ? ' disabled' : '') + '>↓</button>' +
        '<button class="ghost small" data-a="dup"' + (bd.phases.length >= SC_MAXPH ? ' disabled' : '') + '>Duplica</button>' +
        '<button class="ghost small danger" data-a="del"' + (bd.phases.length <= 1 ? ' disabled' : '') + '>Elimina</button></div>' +
        '<div class="scbgrid"><label class="wide2">Ritmo<select data-f="rhythm"><option value="">non cambia</option>' +
        Object.keys(RHYTHMS).map(function (r) { return '<option value="' + r + '"' + (r === ph.rhythm ? ' selected' : '') + '>' + RHYTHMS[r] + '</option>'; }).join('') + '</select></label>' +
        SC_KEYS.map(function (k) {
          return '<label>' + k[1] + ' <small>' + k[2] + '</small><input type="number" data-k="' + k[0] + '" min="' + k[3] + '" max="' + k[4] + '" step="1" placeholder="—" value="' + esc(ph.v[k[0]] || '') + '"></label>';
        }).join('') +
        '<label>Temp. <small>°C</small><input type="number" data-f="temp" min="25" max="45" step="0.1" placeholder="—" value="' + esc(ph.temp) + '"></label>' +
        '<label>Refill <small>s</small><input type="number" data-f="crt" min="0.5" max="10" step="0.5" placeholder="—" value="' + esc(ph.crt) + '"></label></div>' +
        bdEcgHtml(ph) +
        '<div class="scbrow"><span class="tlab">Arriva a questi valori</span><span class="seg scbdur">' +
        DUR.map(function (t) { return '<button data-t="' + t + '"' + (t === ph.duration_s ? ' class="active"' : '') + '>' + (t ? (t === 120 ? '2 min' : (t === 60 ? '1 min' : t + ' s')) : 'Subito') + '</button>'; }).join('') +
        '</span><label class="custom">altro <input type="number" data-f="dur" min="1" max="3600" placeholder="s" value="' + (custom ? ph.duration_s : '') + '"> s</label></div>' +
        '<label class="scbnote">Nota per l\'istruttore: quando avanzare<input type="text" data-f="note" maxlength="200" placeholder="es. Avanzare quando hanno somministrato ossigeno" value="' + esc(ph.note) + '"></label></section>';
    }).join('');
    $('scbBody').innerHTML = h;
    $('scbName').oninput = function () { bd.name = this.value; bdDirty = true; };
    $('scbNotes').oninput = function () { bd.notes = this.value; bdDirty = true; };
    all('.scbph', $('scbBody')).forEach(function (sec) {
      var i = parseInt(sec.dataset.i, 10), ph = bd.phases[i];
      all('input, select', sec).forEach(function (el) {
        if (el.dataset.e) { el.onchange = function () { bdDirty = true; bdErr(''); bdEcgSet(ph, el); renderBuilder(); }; return; }   // ANDROID (regia morfologie ECG 22/11)
        el.oninput = el.onchange = function () {
          bdDirty = true; bdErr('');
          if (el.dataset.k) ph.v[el.dataset.k] = el.value.trim();
          else if (el.dataset.f === 'dur') {
            var t = parseInt(el.value, 10);
            if (!isNaN(t)) { ph.duration_s = Math.max(0, Math.min(3600, t)); all('.scbdur button', sec).forEach(function (x) { x.className = ''; }); }
          } else ph[el.dataset.f] = el.dataset.f === 'rhythm' ? el.value : el.value;
        };
      });
      all('.scbdur button', sec).forEach(function (b) {
        b.onclick = function () {
          ph.duration_s = parseInt(b.dataset.t, 10); bdDirty = true;
          all('.scbdur button', sec).forEach(function (x) { x.className = x === b ? 'active' : ''; });
          sec.querySelector('input[data-f="dur"]').value = '';
        };
      });
      all('button[data-a]', sec).forEach(function (b) {
        b.onclick = function () {
          var a = b.dataset.a; bdDirty = true; bdErr('');
          if (a === 'up' && i > 0) { bd.phases.splice(i - 1, 0, bd.phases.splice(i, 1)[0]); }
          else if (a === 'down' && i < bd.phases.length - 1) { bd.phases.splice(i + 1, 0, bd.phases.splice(i, 1)[0]); }
          else if (a === 'dup' && bd.phases.length < SC_MAXPH) { bd.phases.splice(i + 1, 0, JSON.parse(JSON.stringify(ph))); }
          else if (a === 'del' && bd.phases.length > 1) { bd.phases.splice(i, 1); }
          renderBuilder();
        };
      });
    });
  }
  // ANDROID (regia morfologie ECG 22/11): riga «Morfologia ECG» della fase (non cambia / ECG normale / con alterazioni)
  function bdEcgHtml(ph) {
    var kind = ph.ecg === null ? '' : ph.ecg === 'normal' ? 'normal' : 'alt';
    var opt = function (list, v) { return list.map(function (o) { return '<option value="' + o[0] + '"' + (o[0] === v ? ' selected' : '') + '>' + esc(o[1]) + '</option>'; }).join(''); };
    var h = '<div class="scbecg"><label>Morfologia ECG<select data-e="kind"><option value=""' + (kind === '' ? ' selected' : '') + '>non cambia</option>' +
      '<option value="normal"' + (kind === 'normal' ? ' selected' : '') + '>ECG normale</option><option value="alt"' + (kind === 'alt' ? ' selected' : '') + '>con alterazioni…</option></select></label>';
    if (kind === 'alt') {
      var m = ph.ecg;
      h += '<label>Tratto ST<select data-e="st">' + opt(ECG_ST, m.st) + '</select></label>';
      if (m.st !== 'none') h += '<label>mm<select data-e="st_mm"><option value="">di serie</option>' + [0.5, 1, 1.5, 2, 2.5, 3, 4, 5, 6, 8].map(function (v) {
        return '<option value="' + v + '"' + (m.st_mm === v ? ' selected' : '') + '>' + String(v).replace('.', ',') + '</option>'; }).join('') + '</select></label>';
      h += '<label>Blocco di branca<select data-e="bbb">' + opt(ECG_BBB, m.bbb) + '</select></label>';
      h += '<label>Blocco AV<select data-e="av_block">' + opt(ECG_AV, m.av_block) + '</select></label>';
      if (m.av_block === 'first') { var pr = '<option value="">di serie (280)</option>'; for (var v = 210; v <= 400; v += 10) pr += '<option value="' + v + '"' + (m.pr_ms === v ? ' selected' : '') + '>' + v + '</option>'; h += '<label>PR ms<select data-e="pr_ms">' + pr + '</select></label>'; }
      if (m.av_block === 'third') h += '<label>Scappamento<select data-e="escape">' + opt([['ventricular', 'ventricolare'], ['junctional', 'giunzionale']], m.escape || 'ventricular') + '</select></label>';
      h += '<div class="scbecgx">' + ECG_EXTRA.map(function (o) {
        return '<label class="chk"><input type="checkbox" data-e="x" data-x="' + o[0] + '"' + ((m.extra || []).indexOf(o[0]) >= 0 ? ' checked' : '') + '> ' + esc(o[1]) + '</label>'; }).join('') + '</div>';
    }
    return h + '</div>';
  }
  function bdEcgSet(ph, el) {
    var f = el.dataset.e;
    if (f === 'kind') { ph.ecg = el.value === '' ? null : el.value === 'normal' ? 'normal' : morphMerge(ECG_NORMAL, (state && !morphIsNormal(morphOf(state))) ? morphPayload(morphOf(state)) : {}); return; }
    if (!ph.ecg || ph.ecg === 'normal') return;
    if (f === 'x') { var ex = (ph.ecg.extra || []).slice(), i = ex.indexOf(el.dataset.x); if (el.checked && i < 0) ex.push(el.dataset.x); if (!el.checked && i >= 0) ex.splice(i, 1); ph.ecg.extra = ex; return; }
    var part = {}; part[f] = (f === 'st_mm' || f === 'pr_ms') ? (el.value === '' ? null : Number(el.value)) : el.value;
    ph.ecg = morphMerge(ph.ecg, part);
  }
  function bdAdd(fromState) {
    if (!bd || bd.phases.length >= SC_MAXPH) return;
    var ph = bdPhase(null);
    if (fromState) {
      if (!state) { bdErr('Valori del paziente non ancora letti dal monitor.'); return; }
      ph.rhythm = state.rhythm || '';
      SC_KEYS.forEach(function (k) { if (state[k[0]] !== undefined && state[k[0]] !== null && (k[0] !== 'cvp_mmhg' || on('cvp'))) ph.v[k[0]] = String(state[k[0]]); });
      if (state.temperature_c !== undefined) ph.temp = String(Number(state.temperature_c).toFixed(1));
      if (state.crt_s !== undefined) ph.crt = String(state.crt_s);
      if (!morphIsNormal(morphOf(state))) ph.ecg = morphOf(state);         // ANDROID (regia morfologie ECG 22/11)
      ph.duration_s = 30;
    }
    bd.phases.push(ph); bdDirty = true; bdErr('');
    renderBuilder();
    var secs = all('.scbph', $('scbBody')), last = secs[secs.length - 1];
    if (last) { last.scrollIntoView({ block: 'nearest' }); var inp = last.querySelector('input[data-f="name"]'); if (inp) inp.focus(); }
  }
  // dalla bozza al formato del monitor; ritorna null (con messaggio) se un campo non è un numero valido
  function bdBuild() {
    var name = bd.name.trim();
    if (!name) { bdErr('Dai un nome allo scenario.'); $('scbName').focus(); return null; }
    var out = { name: name, notes: bd.notes.trim(), phases: [] }, bad = null;
    if (bd.id) out.id = bd.id;
    bd.phases.forEach(function (ph, i) {
      if (bad) return;
      var o = { name: ph.name.trim() || ('Fase ' + (i + 1)), note: ph.note.trim(), duration_s: ph.duration_s || 0, params: {} };
      if (ph.keep) Object.keys(ph.keep).forEach(function (k) { o[k] = ph.keep[k]; });      // ANDROID (funzioni mancanti 22/11)
      if (ph.rhythm) o.rhythm = ph.rhythm;
      SC_KEYS.forEach(function (k) {
        var raw = ph.v[k[0]]; if (raw === undefined || raw === '') return;
        var n = Number(raw);
        if (isNaN(n) || n < k[3] || n > k[4]) { bad = 'Fase ' + (i + 1) + ': ' + k[1] + ' deve essere fra ' + k[3] + ' e ' + k[4] + '.'; return; }
        o.params[k[0]] = Math.round(n);
      });
      if (ph.temp !== '') { var t = Number(ph.temp); if (isNaN(t) || t < 25 || t > 45) bad = bad || 'Fase ' + (i + 1) + ': la temperatura deve essere fra 25 e 45 °C.'; else o.temperature_c = t; }
      if (ph.crt !== '') { var c = Number(ph.crt); if (isNaN(c) || c < 0.5 || c > 10) bad = bad || 'Fase ' + (i + 1) + ': il refill deve essere fra 0,5 e 10 s.'; else o.crt_s = c; }
      if (!bad && o.params.systolic_mmhg !== undefined && o.params.diastolic_mmhg !== undefined && o.params.diastolic_mmhg >= o.params.systolic_mmhg)
        bad = 'Fase ' + (i + 1) + ': la diastolica deve essere più bassa della sistolica.';
      // ANDROID (regia morfologie ECG 22/11): la morfologia della fase (le combinazioni che il monitor rifiuta, dette in italiano)
      if (!bad && ph.ecg) {
        if (ph.ecg === 'normal') o.ecg_morphology = 'normal';
        else {
          var em = ph.ecg, ex = em.extra || [];
          if (ex.indexOf('wpw') >= 0 && em.bbb !== 'none') bad = 'Fase ' + (i + 1) + ': la preeccitazione (WPW) non si combina con un blocco di branca.';
          else if (ex.indexOf('wpw') >= 0 && em.av_block !== 'none') bad = 'Fase ' + (i + 1) + ': la preeccitazione (WPW) non si combina con un blocco AV.';
          else if (ex.indexOf('hyperkalemia') >= 0 && ex.indexOf('hyperkalemia_severe') >= 0) bad = 'Fase ' + (i + 1) + ': scegli iperkaliemia oppure iperkaliemia grave.';
          else if (ex.indexOf('hypokalemia') >= 0 && (ex.indexOf('hyperkalemia') >= 0 || ex.indexOf('hyperkalemia_severe') >= 0)) bad = 'Fase ' + (i + 1) + ': ipo- e iperkaliemia non stanno insieme.';
          o.ecg_morphology = morphPayload(em);
        }
      }
      if (!bad && !o.rhythm && !Object.keys(o.params).length && o.temperature_c === undefined && o.crt_s === undefined && o.ecg_morphology === undefined && !(ph.keep && Object.keys(ph.keep).length))
        bad = 'Fase ' + (i + 1) + ' vuota: scegli un ritmo, un parametro o la morfologia ECG.';
      out.phases.push(o);
    });
    if (bad) { bdErr(bad); return null; }
    return out;
  }
  function bdServerError(j) {
    if (!j) return 'Monitor non raggiungibile: lo scenario non è stato salvato.';
    if (j._status === 423) { if (j.controller) showControl(j.controller); return 'Il monitor è comandato da un altro controller: prendi il comando e salva di nuovo.'; }
    if (j._status === 401) return 'PIN non più valido: esci e rientra.';
    if (j._status === 413) return 'Scenario troppo lungo per il monitor: accorcia le note o togli qualche fase.';
    var e = String(j.error || 'errore sconosciuto');
    return 'Il monitor non ha accettato lo scenario. ' + e.replace(/^phase (\d+)/, 'Fase $1') + '.';
  }
  function bdSave() {
    var sc = bdBuild(); if (!sc) return;
    var body = { action: 'save', scenario: sc };
    if (unescape(encodeURIComponent(JSON.stringify(body))).length > 15000) { bdErr('Scenario troppo lungo per il monitor: accorcia le note o togli qualche fase.'); return; }
    $('scbSave').disabled = true; cmdSeq++;
    api('POST', '/api/v1/scenarios', body).then(function (j) {
      $('scbSave').disabled = false;
      if (!j || !j.ok) { bdErr(bdServerError(j)); return; }
      if (j.saved) scSel = j.saved.id;
      closeBuilder(); handle(j); msg('Scenario salvato sul monitor');
    }).catch(function () { $('scbSave').disabled = false; bdErr(bdServerError(null)); });
  }
  function initScen() {
    all('#railSw button').forEach(function (b) { b.onclick = function () { setRail(b.dataset.v); }; });
    $('scbAdd').onclick = function () { bdAdd(false); };
    $('scbSnap').onclick = function () { bdAdd(true); };
    $('scbSave').onclick = bdSave;
    $('scbClose').onclick = function () {
      if (!bdDirty || (bdCloseArm && Date.now() - bdCloseArm < 4000)) { closeBuilder(); return; }
      bdCloseArm = Date.now(); $('scbClose').textContent = 'Tocca di nuovo: chiudi senza salvare';
      setTimeout(function () { if (bd && bdCloseArm && Date.now() - bdCloseArm >= 3900) { bdCloseArm = 0; $('scbClose').textContent = 'Chiudi'; } }, 4000);
    };
    $('scbDup').onclick = function () {
      if (!bd) return;
      bd.id = null; bd.name = (bd.name.trim() + ' (copia)').slice(0, 60); bdDirty = true; bdErr('');
      renderBuilder(); $('scbName').focus();
    };
    $('scbDel').onclick = function () {
      if (!bd || !bd.id) return;
      if (bdDelArm && Date.now() - bdDelArm < 4000) {
        var id = bd.id, nm = bd.name; cmdSeq++;
        api('POST', '/api/v1/scenarios', { action: 'delete', id: id }).then(function (j) {
          if (!j || !j.ok) { bdErr(bdServerError(j).replace('non ha accettato lo scenario', 'non ha eliminato lo scenario').replace('salva di nuovo', 'riprova')); return; }
          if (scSel === id) scSel = null;
          closeBuilder(); handle(j); msg('Scenario eliminato');
        }).catch(function () { bdErr('Monitor non raggiungibile: lo scenario non è stato eliminato.'); });
        return;
      }
      bdDelArm = Date.now(); $('scbDel').textContent = 'Tocca di nuovo: elimina'; $('scbDel').classList.add('armed');
      setTimeout(function () { if (bd && bdDelArm && Date.now() - bdDelArm >= 3900) { bdDelArm = 0; $('scbDel').textContent = 'Elimina scenario'; $('scbDel').classList.remove('armed'); } }, 4000);
    };
    document.addEventListener('keydown', function (e) { if (e.key === 'Escape' && bd && !bdDirty) closeBuilder(); });
    setRail(railMode);
  }

  // ---------- evidenza dei valori fuori range sulle tessere ----------
  function markTile(id, w) {
    var t = $(id); while (t && !t.classList.contains('tile')) t = t.parentElement;
    if (t) t.classList.toggle('warn', !!w);
  }
  function warnTiles(s) {
    var L = s.alarm_limits || {};
    markTile('v_rhythm', !s.pulse_present);           // (CSS: «· ARRESTO», non «fuori limiti»: W-13)
    markTile('v_hr', s.heart_rate_bpm < L.hr_low || s.heart_rate_bpm > L.hr_high);
    markTile('v_bp', s.systolic_mmhg < L.sys_low || s.systolic_mmhg > L.sys_high);
    markTile('v_spo2', s.spo2_percent < L.spo2_low);
    markTile('v_co2', s.respiratory_rate_rpm < L.rr_low || s.respiratory_rate_rpm > L.rr_high ||
                      s.etco2_mmhg < L.etco2_low || s.etco2_mmhg > L.etco2_high);
    markTile('v_temp', Number(s.temperature_c) < 35 || Number(s.temperature_c) > 38.5 || s.crt_s > 3);
  }
  // ANDROID (avvio scenario 22/11): fase dello scenario dal monitor (ready | running | paused | ended); un monitor vecchio
  // non la manda: si ricava come prima da simulation_running e scenario_ended
  function scenPhase(s) {
    if (!s) return 'running';
    return s.scenario_phase || (s.scenario_ended ? 'ended' : (s.simulation_running ? 'running' : 'paused'));
  }
  // ANDROID (avvio scenario 22/11, richiesta utente): prima di «Avvia» niente registro e niente piano di debriefing
  var curPhase = null, NOT_STARTED = 'Prima di «Avvia» non si registra niente e il piano di debriefing non si apre: premi «Avvia».';
  function notStarted() { return curPhase === 'ready'; }
  function applyStartGate() {
    var off = notStarted();
    $('logDebrief').disabled = off; $('commentAdd').disabled = off; $('comment').disabled = off;
    $('logDebrief').title = off ? NOT_STARTED : 'Riepilogo e storia completa dello scenario, da usare nel debriefing';
    $('comment').placeholder = off ? 'Prima premi «Avvia»…' : (qMode === null ? 'Annotazione libera…' : $('comment').placeholder);
    if (off && !$('debrief').hidden && !dbArch && !keepStale()) closeDebrief();      // ANDROID (debriefing offline web 22/11)
  }
  function renderClock(s) {
    var ph = scenPhase(s);
    if (ph !== curPhase) {
      curPhase = ph; renderQuick(); applyStartGate(); tx12Render();      // ANDROID (trasmissione 12D 22/11)
      // ANDROID (fix analisi 22/11, BUG-03): fuori da «Scenario terminato» (ripreso, azzerato, riavviato, anche da un altro
      // controller o mentre questa pagina era chiusa) la bozza non è più «da fine scenario»: titolo e squadra restano
      if (ph !== 'ended') { endEpisode = null; staleOrDrop(); }      // ANDROID (debriefing offline web 22/11): piano aperto = resta
    }
    if (s.scenario_time_s !== undefined)
      $('clock').textContent = fmtMMSS(s.scenario_time_s) + (ph === 'ready' ? ' · PRONTO · premi Avvia' : (s.simulation_running ? '' : ' · PAUSA'));
    $('clock').classList.toggle('ready', ph === 'ready');
    $('clockReset').hidden = ph === 'ready';              // in PRONTO il timer è già fermo a 00:00
    var it = $('inState');
    if (lastState) {
      var el = lastState.scen !== null && s.scenario_time_s !== undefined
        ? s.scenario_time_s - lastState.scen : (Date.now() - lastState.w) / 1000;
      it.textContent = 'in «' + lastState.n + '» da ' + fmtMMSS(el);
    } else it.textContent = '';
  }

  // ---------- schede e navigazione ----------
  function flashCard(id) {
    if (!id) return;
    var el = $(id); if (!el) return;
    el.scrollIntoView({ block: 'nearest', behavior: 'smooth' });
    el.classList.remove('flash'); void el.offsetWidth; el.classList.add('flash');
  }
  function select(k) {
    selSensor = k; devSig = '';
    showCard(SENS[k].card || 'panel_defib', true);
    flashCard(SENS[k].card || 'panel_defib');
    if (state) render(state);
  }
  function showCard(id, keepSensor) {
    card = id; sessionStorage.setItem('sf_card', id);
    if (!keepSensor) { selSensor = id === 'panel_defib' ? 'pads' : null; devSig = ''; }
    all('.pcard').forEach(function (p) { p.classList.toggle('show', p.id === id); });
    all('#pnav button').forEach(function (b) { b.className = b.dataset.card === id ? 'active' : ''; });
    // ANDROID (affordance 22/11, W-12): la tessera della scheda aperta ha la linea azzurra in basso (le tessere sono schede)
    all('.tile[data-card]').forEach(function (t) { t.classList.toggle('open', t.dataset.card === id); });
    if (state) renderFigure(state);
  }

  // ANDROID (audit 26/11, PUL-F5): tolto l'aggiornamento delle app dalla regia (pacchetti .deb dell'iPad, ricerca su GitHub):
  // su Android le app si aggiornano installando l'APK nuovo

  // ---------- ANDROID (righello a zone 22/11, richiesta utente): righello a zone ----------
  // Stesso componente del tablet controller (CtlRuler.kt). Per ogni parametro: (1) nome e valore, (2) i valori rapidi come una riga di
  // tasti veri con il nome clinico e il valore («Bradi / 40»), colorati con la loro zona (quello del valore scelto pieno con «●»),
  // (3) il righello: scorre sotto l'indice FISSO al centro (si trascina il righello; anche rotella del mouse e frecce ←/→ con il
  // fuoco), con inerzia e aggancio al passo; le zone cliniche sono lo sfondo tenue dietro le tacche (verde norma, ambra fuori norma,
  // rosso grave: SISTEMA DI SIGNIFICANTI §3) con il nome in alto; i limiti di allarme del monitor sono piccoli triangoli rossi sul
  // bordo inferiore; in una transizione la linea bianca «adesso» è il valore di adesso, l'indice colorato l'arrivo.
  // Il valore vive ancora nell'<input type=range> nascosto: il righello lo scrive e manda 'input' (mentre scorre) e 'change'
  // (quando si ferma), come faceva il cursore: il resto della regia (invio, Subito/Prepara, IN ATTESA, BUG-01) non cambia.
  var RZ = -1e9, RZ2 = 1e9;
  var RULER = {
    heart_rate_bpm:       { vis: 120, minor: 5, major: 20, zones: [[RZ, 40, 'Bradi grave', 2], [40, 60, 'Bradicardia', 1], [60, 101, 'Normale', 0], [101, 150, 'Tachicardia', 1], [150, RZ2, 'Tachi grave', 2]],
                            pre: [[40, 'Bradi'], [60, 'Bassa'], [80, 'Normale'], [100, 'Alta'], [130, 'Tachi'], [160, 'Tachi grave']] },
    spo2_percent:         { vis: 36, minor: 1, major: 5, zones: [[RZ, 85, 'Grave', 2], [85, 94, 'Ipossia', 1], [94, RZ2, 'Normale', 0]],
                            pre: [[75, 'Grave'], [85, 'Ipossia'], [90, 'Lieve'], [94, 'Limite'], [98, 'Normale']] },
    systolic_mmhg:        { vis: 120, minor: 5, major: 20, zones: [[RZ, 90, 'Ipotensione', 1], [90, 140, 'Normale', 0], [140, 180, 'Ipertensione', 1], [180, RZ2, 'Crisi', 2]] },
    diastolic_mmhg:       { vis: 90, minor: 5, major: 20, zones: [[RZ, 60, 'Ipotensione', 1], [60, 90, 'Normale', 0], [90, 120, 'Ipertensione', 1], [120, RZ2, 'Crisi', 2]] },
    respiratory_rate_rpm: { vis: 36, minor: 1, major: 5, zones: [[RZ, 1, 'Apnea', 2], [1, 12, 'Bradipnea', 1], [12, 21, 'Normale', 0], [21, 30, 'Tachipnea', 1], [30, RZ2, 'Grave', 2]],
                            pre: [[0, 'Apnea'], [8, 'Bradi'], [12, 'Bassa'], [16, 'Normale'], [24, 'Tachi'], [32, 'Tachi grave']] },
    etco2_mmhg:           { vis: 60, minor: 2, major: 10, zones: [[RZ, 35, 'Ipocapnia', 1], [35, 46, 'Normale', 0], [46, RZ2, 'Ipercapnia', 1]],
                            pre: [[10, 'Molto bassa'], [20, 'Ipocapnia'], [35, 'Normale'], [45, 'Limite'], [60, 'Ipercapnia']] },
    temperature_c:        { vis: 7, minor: 0.2, major: 1, zones: [[RZ, 32, 'Grave', 2], [32, 35, 'Ipotermia', 1], [35, 38, 'Normale', 0], [38, 40, 'Febbre', 1], [40, RZ2, 'Iperpiressia', 2]],
                            pre: [[34, 'Ipotermia'], [35.5, 'Bassa'], [36.8, 'Normale'], [38.5, 'Febbre'], [40, 'Iperpiressia']] },
    crt_s:                { vis: 6, minor: 0.5, major: 1, zones: [[RZ, 2.01, 'Normale', 0], [2.01, 3.01, 'Al limite', 1], [3.01, RZ2, 'Allungato', 2]],
                            pre: [[1, 'Rapido'], [2, 'Normale'], [3, 'Limite'], [4, 'Allungato'], [6, 'Molto lungo']] },
    cpr_rate:             { vis: 60, minor: 2, major: 10, zones: [[RZ, 100, 'Lenta', 1], [100, 121, 'Corretta', 0], [121, RZ2, 'Veloce', 1]] },
    vf_mv:                { vis: 0.8, minor: 0.05, major: 0.2, dec: 2, zones: [[RZ, 0.1, 'DAE: no scarica', 2], [0.1, 0.2, 'Fine', 1], [0.2, 0.5, 'Media', 0], [0.5, RZ2, 'Grossolana', 0]] }
  };
  var ZCOL = [['rgba(48,209,88,.15)', '#9ff0b4', '#30d158'], ['rgba(255,176,32,.15)', '#ffd28a', '#ffb020'], ['rgba(255,69,58,.20)', '#ffaaa4', '#ff453a']];
  function zoneOf(spec, v) { for (var i = 0; i < spec.zones.length; i++) { var z = spec.zones[i]; if (v >= z[0] && v < z[1]) return z[3]; } return 0; }
  var rulerHintDone = false;
  try { rulerHintDone = localStorage.getItem('sf_ruler_hint') === '1'; } catch (e) {}
  function makeRuler(inp, key, color) {
    var spec = RULER[key], min = parseFloat(inp.min), max = parseFloat(inp.max), step = parseFloat(inp.step) || 1;
    var cv = document.createElement('canvas'); cv.className = 'ruler'; cv.tabIndex = 0;
    cv.setAttribute('role', 'slider'); cv.setAttribute('aria-valuemin', min); cv.setAttribute('aria-valuemax', max);
    cv.title = 'Trascina il righello (o rotella, ← →): si ferma sul valore sotto l\'indice';
    var R = { cv: cv, pos: parseFloat(inp.value), now: null, lo: null, hi: null, busy: false, raf: 0, vel: 0 };
    var dec = spec.dec != null ? spec.dec : (step < 1 ? 1 : 0);
    function snap(v) { return Math.max(min, Math.min(max, Math.round(v / step) * step)); }
    var majDec = spec.major < 1 ? 1 : 0;
    function majLabel(v) { return majDec ? v.toFixed(majDec).replace('.', ',') : String(Math.round(v)); }
    var W = 0, H = 0, dpr = 1, ctx = cv.getContext('2d'), lastVal = null;
    function size() {
      var r = cv.getBoundingClientRect(); dpr = window.devicePixelRatio || 1;
      if (r.width < 2) return false;
      if (Math.round(r.width) !== W || Math.round(r.height) !== H) { W = Math.round(r.width); H = Math.round(r.height); cv.width = W * dpr; cv.height = H * dpr; }
      return true;
    }
    function draw() {
      if (!size()) return;
      var c = ctx, k = W / spec.vis, cx = W / 2, pos = R.pos;
      function X(v) { return cx + (v - pos) * k; }
      c.setTransform(dpr, 0, 0, dpr, 0, 0); c.clearRect(0, 0, W, H);
      c.save(); c.beginPath(); if (c.roundRect) c.roundRect(0, 0, W, H, 10); else c.rect(0, 0, W, H); c.clip();
      c.fillStyle = '#0b1017'; c.fillRect(0, 0, W, H);
      // zone: sfondo tenue dietro le tacche, nome in alto
      c.font = '700 13px system-ui, -apple-system, Roboto, sans-serif'; c.textAlign = 'center'; c.textBaseline = 'alphabetic';
      spec.zones.forEach(function (z) {
        var a = Math.max(X(Math.max(z[0], min)), 0), b = Math.min(X(Math.min(z[1], max)), W);
        if (b <= a) return;
        c.fillStyle = ZCOL[z[3]][0]; c.fillRect(a, 0, b - a, H);
        if (z[0] > min && X(z[0]) > 0 && X(z[0]) < W) { c.fillStyle = 'rgba(255,255,255,.14)'; c.fillRect(X(z[0]) - 0.5, 0, 1, H); }
        var tw = c.measureText(z[2]).width;
        if (rulerHintDone && b - a > tw + 16) {      // il nome (con «‹ trascina ›» in alto i nomi aspettano) non passa sotto l'indice: si sposta di lato o non si scrive
          var tx = Math.max(a + tw / 2 + 8, Math.min(b - tw / 2 - 8, (a + b) / 2));
          if (Math.abs(tx - cx) < tw / 2 + 14) { var l = cx - 14 - tw / 2, r = cx + 14 + tw / 2; tx = (l >= a + tw / 2 + 6 && (l - (a + b) / 2) * (l - (a + b) / 2) <= (r - (a + b) / 2) * (r - (a + b) / 2) || r > b - tw / 2 - 6) ? (l >= a + tw / 2 + 6 ? l : null) : r; }
          if (tx != null) { c.fillStyle = ZCOL[z[3]][1]; c.fillText(z[2], tx, 17); }
        }
      });
      var xa = X(min), xb = X(max); c.fillStyle = 'rgba(0,0,0,.55)';
      if (xa > 0) c.fillRect(0, 0, xa, H); if (xb < W) c.fillRect(xb, 0, W - xb, H);
      // tacche dal bordo inferiore: maggiori con il numero, minori sottili
      var rm = Math.max(1, Math.round(spec.major / spec.minor)), half = spec.vis / 2 + spec.major;
      var k0 = Math.ceil((pos - half) / spec.minor), k1 = Math.floor((pos + half) / spec.minor);
      c.font = '700 15px system-ui, -apple-system, Roboto, sans-serif';
      for (var i = k0; i <= k1; i++) {
        var v = i * spec.minor; if (v < min - 1e-9 || v > max + 1e-9) continue;
        var x = Math.round(X(v)) + 0.5, maj = ((i % rm) + rm) % rm === 0;
        if (maj) { c.fillStyle = 'rgba(255,255,255,.85)'; c.fillRect(x - 1, H - 15, 2, 15); c.fillStyle = '#edf2f7'; c.fillText(majLabel(Math.round(v / spec.major) * spec.major), x, H - 20); }
        else { c.fillStyle = 'rgba(255,255,255,.32)'; c.fillRect(x - 0.5, H - 7, 1, 7); }
      }
      // limiti di allarme: piccoli triangoli rossi sul bordo inferiore
      [R.lo, R.hi].forEach(function (lv) {
        if (lv == null) return; var x = X(lv); if (x < -6 || x > W + 6) return;
        c.fillStyle = '#ff453a'; c.beginPath(); c.moveTo(x - 5, H); c.lineTo(x + 5, H); c.lineTo(x, H - 8); c.closePath(); c.fill();
      });
      // bordi sfumati: il righello continua oltre (si capisce che scorre)
      var g = c.createLinearGradient(0, 0, 40, 0); g.addColorStop(0, 'rgba(11,16,23,1)'); g.addColorStop(1, 'rgba(11,16,23,0)'); c.fillStyle = g; c.fillRect(0, 0, 40, H);
      g = c.createLinearGradient(W - 40, 0, W, 0); g.addColorStop(0, 'rgba(11,16,23,0)'); g.addColorStop(1, 'rgba(11,16,23,1)'); c.fillStyle = g; c.fillRect(W - 40, 0, 40, H);
      // adesso (transizione)
      if (R.now != null) {
        var xn = Math.max(4, Math.min(W - 4, X(R.now)));
        if (Math.abs(xn - cx) > 3) {
          c.fillStyle = '#fff'; c.fillRect(xn - 1, 22, 2, H - 22);
          c.font = '800 11px system-ui, sans-serif'; var pw = 46, px = Math.max(pw / 2 + 2, Math.min(W - pw / 2 - 2, xn));
          c.beginPath(); if (c.roundRect) c.roundRect(px - pw / 2, 4, pw, 17, 5); else c.rect(px - pw / 2, 4, pw, 17); c.fill();
          c.fillStyle = '#0a120d'; c.fillText('adesso', px, 16.5);
        }
      }
      // indice fisso: triangolo + linea nel colore del parametro
      c.fillStyle = color; c.fillRect(cx - 1.5, 10, 3, H - 20 - 14 - 10); c.fillRect(cx - 1.5, H - 17, 3, 17);     // interrotta sulla riga dei numeri
      c.beginPath(); c.moveTo(cx - 9, 0); c.lineTo(cx + 9, 0); c.lineTo(cx, 12); c.closePath(); c.fill();
      if (!rulerHintDone && !R.compact) {
        c.font = '700 12px system-ui, sans-serif'; var ht = '‹ trascina ›', hw = c.measureText(ht).width + 14;
        c.fillStyle = 'rgba(11,16,23,.94)'; c.fillRect(cx - 14 - hw, 2, hw, 18); c.fillStyle = '#d5dee8'; c.fillText(ht, cx - 14 - hw / 2, 15.5);
      }
      c.restore();
      cv.setAttribute('aria-valuenow', snap(pos));
    }
    function emit(final) {
      var v = snap(R.pos), s = String(+v.toFixed(4));
      if (final || s !== lastVal) { lastVal = s; inp.value = s; inp.dispatchEvent(new Event('input')); }
      if (final) inp.dispatchEvent(new Event('change'));
    }
    function stopAnim() { if (R.raf) cancelAnimationFrame(R.raf); R.raf = 0; R.vel = 0; }
    function animTo(to, ms, final) {
      stopAnim(); var from = R.pos, t0 = performance.now(); R.busy = true;
      (function f() { var t = Math.min(1, (performance.now() - t0) / ms), e = 1 - Math.pow(1 - t, 3); R.pos = from + (to - from) * e; draw();
        if (t < 1) R.raf = requestAnimationFrame(f); else { R.raf = 0; R.pos = to; R.busy = false; draw(); if (final) emit(true); } })();
    }
    function settle() { animTo(snap(R.pos), 120, true); }
    function fling(vel) {      // vel in unità al secondo
      stopAnim(); R.busy = true; var last = performance.now(); R.vel = vel;
      (function f() {
        var t = performance.now(), dt = Math.min(0.05, (t - last) / 1000); last = t;
        R.vel *= Math.exp(-dt * 5); R.pos += R.vel * dt;
        if (R.pos <= min || R.pos >= max) { R.pos = Math.max(min, Math.min(max, R.pos)); R.vel = 0; }
        emit(false); draw();
        if (Math.abs(R.vel) * (W / spec.vis) < 30) { R.raf = 0; settle(); } else R.raf = requestAnimationFrame(f);
      })();
    }
    var drag = null;
    cv.addEventListener('pointerdown', function (e) {
      if (e.button !== 0 || inp.disabled) return;
      stopAnim(); R.busy = true; cv.setPointerCapture(e.pointerId);
      drag = { x: e.clientX, p: R.pos, moved: false, s: [[performance.now(), e.clientX]] }; e.preventDefault(); cv.focus();
    });
    cv.addEventListener('pointermove', function (e) {
      if (!drag) return;
      var dx = e.clientX - drag.x;
      if (!drag.moved && Math.abs(dx) < 4) return;
      if (!drag.moved) { drag.moved = true; drag.x = e.clientX; drag.p = R.pos; dx = 0; if (!rulerHintDone) { rulerHintDone = true; try { localStorage.setItem('sf_ruler_hint', '1'); } catch (er) {} } }
      R.pos = Math.max(min, Math.min(max, drag.p - dx / (W / spec.vis)));
      drag.s.push([performance.now(), e.clientX]); if (drag.s.length > 6) drag.s.shift();
      emit(false); draw();
    });
    function up(e) {
      if (!drag) return; var d = drag; drag = null;
      if (!d.moved) { R.busy = false; return; }
      var s = d.s, a = s[0], b = s[s.length - 1]; for (var i = s.length - 1; i >= 0; i--) { if (b[0] - s[i][0] > 100) break; a = s[i]; }
      var v = b[0] - a[0] > 5 ? (b[1] - a[1]) / ((b[0] - a[0]) / 1000) : 0;
      if (Math.abs(v) > 180) fling(-v / (W / spec.vis)); else settle();
    }
    cv.addEventListener('pointerup', up); cv.addEventListener('pointercancel', up);
    var wheelT = null;
    function nudge(n) {
      if (inp.disabled) return;
      stopAnim(); R.busy = true; R.pos = snap(snap(R.pos) + n * step); emit(false); draw();
      clearTimeout(wheelT); wheelT = setTimeout(function () { R.busy = false; emit(true); }, 450);
    }
    cv.addEventListener('wheel', function (e) { e.preventDefault(); var d = (Math.abs(e.deltaX) > Math.abs(e.deltaY) ? e.deltaX : e.deltaY); if (d) nudge((d > 0 ? 1 : -1) * (e.shiftKey ? 5 : 1)); }, { passive: false });
    cv.addEventListener('keydown', function (e) {
      var n = { ArrowLeft: -1, ArrowDown: -1, ArrowRight: 1, ArrowUp: 1, PageDown: -10, PageUp: 10 }[e.key];
      if (!n) return; e.preventDefault(); e.stopPropagation(); nudge(n * (e.shiftKey ? 5 : 1));
    });
    R.sync = function () { if (R.busy) return; var v = parseFloat(inp.value); if (isFinite(v) && v !== R.pos) { R.pos = v; draw(); } };
    R.setNow = function (v) { if (R.now !== v) { R.now = v; draw(); } };
    R.setLimits = function (lo, hi) { if (R.lo !== lo || R.hi !== hi) { R.lo = lo; R.hi = hi; draw(); } };
    R.jump = function (v) { animTo(snap(v), 220, false); };
    R.draw = draw;
    inp.addEventListener('input', function () { if (!R.busy) R.sync(); });
    inp._ruler = R;
    setInterval(function () { if (cv.offsetParent) { R.sync(); if (!W) draw(); } }, 300);
    window.addEventListener('resize', function () { W = 0; draw(); });
    return R;
  }
  // riga dei valori rapidi: tasti veri «nome / valore» colorati con la loro zona; pieno con «●» quello del valore scelto
  function presetButtons(box, key, list, onPick) {
    var spec = RULER[key];
    box.className = 'presets';
    box.innerHTML = list.map(function (q) {
      var z = zoneOf(spec, q[0]);
      return '<button type="button" class="pz z' + z + '" data-v="' + q[0] + '"' + (q[2] != null ? ' data-v2="' + q[2] + '"' : '') + '><b>' + q[1] + '</b><span>' + (q[3] || String(q[0]).replace('.', ',')) + '</span></button>';
    }).join('');
    all('button', box).forEach(function (b) { b.onclick = function () { onPick(parseFloat(b.dataset.v), b.dataset.v2 != null ? parseFloat(b.dataset.v2) : null); }; });
  }
  // cursori tecnici (volumi): riempimento della pista fino al pomello (--p), anche quando il valore lo scrive la regia
  function techFill() { all('input[type=range].tech').forEach(function (r) { var p = (r.value - r.min) / ((r.max - r.min) || 1) * 100; r.style.setProperty('--p', p.toFixed(1) + '%'); }); }
  document.addEventListener('input', function (e) { if (e.target && e.target.classList && e.target.classList.contains('tech')) techFill(); });
  setInterval(techFill, 500);
  function markPresets(box, v) {
    if (!box) return;
    all('button', box).forEach(function (b) { var on = Math.abs(parseFloat(b.dataset.v) - v) < 1e-6; b.classList.toggle('on', on); });
  }

  // ---------- controlli a cursore ----------
  function fmt(k, v) { return P[k].dec ? Number(v).toFixed(P[k].dec) : v; }
  // ANDROID (fix analisi 22/11, BUG-01): base dei cursori e di «+/−» = modifica in attesa, altrimenti il valore di ARRIVO della
  // transizione in corso, altrimenti il valore attuale. Prima «+» a metà rampa (94 → 130) mandava 95 e il 130 si perdeva.
  function trTo(s, k) { var t = s && s.transitions && s.transitions[k]; return t && typeof t.to === 'number' ? t.to : undefined; }
  function trTarget(s, k) { var t = trTo(s, k); return t !== undefined ? t : (s ? s[k] : 0); }
  function cur(k) { return pending[k] !== undefined ? pending[k] : trTarget(state, k); }
  function buildCtl(el) {
    var k = el.dataset.p, d = P[k];
    el.className = 'ctl c-' + d.c;
    // ANDROID (affordance 22/11, W-14): durante una transizione «82 → 130» con la barra di avanzamento sotto e una tacca «adesso»
    // sul cursore (il pallino del cursore è l'arrivo)
    // ANDROID (numerici realistici 22/11): FC e SpO₂ con «sul monitor ora: N» (numero a schermo, con il ritardo vero) durante una rampa
    var mn = (k === 'heart_rate_bpm' || k === 'spo2_percent') ? ' <span class="monnow" hidden></span>' : '';
    // ANDROID (righello a zone 22/11): nome e valore, poi i valori rapidi come tasti «nome / valore», poi il righello fra − e +
    // (l'<input type=range> resta, nascosto: è il valore del righello)
    el.innerHTML = '<label>' + d.l + ' <output></output> <small>' + d.u + '</small>' + mn + ' <span class="pend"></span></label>' +
      '<i class="prog" hidden><b></b></i>' +
      ((RULER[k].pre || k === 'systolic_mmhg') ? '<div class="presets"></div>' : '') +
      '<div class="srow"><button type="button" data-d="-1" title="Un passo in meno (tieni premuto per ripetere)">−</button><span class="rwrap"><input type="range" min="' + d.min + '" max="' + d.max + '" step="' + d.step + '" tabindex="-1" aria-hidden="true"></span><button type="button" data-d="1" title="Un passo in più (tieni premuto per ripetere)">+</button></div>';
    var inp = el.querySelector('input');
    var cvar = { hr: '--hr', spo2: '--cyan', art: '--art', co2: '--co2', temp: '--temp' }[d.c] || '--acc';
    var R = makeRuler(inp, k, getComputedStyle(document.documentElement).getPropertyValue(cvar).trim() || '#41c3ea');
    el.querySelector('.rwrap').appendChild(R.cv);
    var pbox = el.querySelector('.presets');
    if (pbox && k === 'systolic_mmhg') presetButtons(pbox, k, BPQ.map(function (q) { return [q[1], q[0], q[2], q[1] + '/' + q[2]]; }), function (sv, dv) {
      if (!tapOnce('pz_bp', 800)) return; inp.value = sv; R.jump(sv); setBp(sv, dv);
    });
    else if (pbox) presetButtons(pbox, k, RULER[k].pre, function (v) {
      if (!tapOnce('pz_' + k, 800)) return; inp.value = v; R.jump(v); commit(k, v);
    });
    inp.addEventListener('input', function () {
      editing[k] = true;
      if (d.now || mode === 0) el.querySelector('output').textContent = fmt(k, inp.value);
      else { var pe = el.querySelector('.pend'); pe.className = 'pend wait'; pe.textContent = '→ ' + inp.value; }
    });
    inp.addEventListener('change', function () { commit(k, parseFloat(inp.value)); });
    all('.srow button', el).forEach(function (b) {
      b.onclick = function () {
        var st = d.step, v = Math.round((parseFloat(inp.value) + (+b.dataset.d) * st) / st) * st;
        inp.value = Math.max(d.min, Math.min(d.max, v)); R.sync(); commit(k, parseFloat(inp.value));
      };
    });
  }
  function commit(k, v) {
    var d = P[k], done = function () { editing[k] = false; if (state) render(state); };
    editing[k] = true;
    var pr = d.now ? command(d.now, v) : change(k, d.dec ? v : Math.round(v));
    return Promise.resolve(pr).then(done, done);
  }
  // PA: la diastolica resta almeno 10 mmHg sotto la sistolica
  function bpPair(k, v) {
    var sys = k === 'systolic_mmhg' ? v : cur('systolic_mmhg'), dia = k === 'diastolic_mmhg' ? v : cur('diastolic_mmhg');
    if (k === 'systolic_mmhg' && dia > sys - 10) { dia = Math.max(20, sys - 10); msg('Diastolica adeguata a ' + dia + ' mmHg (deve restare sotto la sistolica)'); }
    if (k === 'diastolic_mmhg' && sys < dia + 10) { sys = Math.min(260, dia + 10); msg('Sistolica adeguata a ' + sys + ' mmHg (deve restare sopra la diastolica)'); }
    return [sys, dia];
  }
  function change(k, v) {
    if (k === 'systolic_mmhg' || k === 'diastolic_mmhg') {
      var bp = bpPair(k, v);
      if (mode === 0) return command('set_bp', { systolic: bp[0], diastolic: bp[1] });
      pending.systolic_mmhg = bp[0]; pending.diastolic_mmhg = bp[1]; renderPending(); return Promise.resolve();
    }
    if (mode === 0) { var p = {}; p[k] = v; return command('transition', { params: p, duration_s: 0 }); }
    pending[k] = v; renderPending(); return Promise.resolve();
  }
  function setBp(sys, dia) {
    if (mode === 0) return command('set_bp', { systolic: sys, diastolic: dia });
    pending.systolic_mmhg = sys; pending.diastolic_mmhg = dia; renderPending(); if (state) render(state);
  }
  var trSeen = {};
  function renderCtls(s) {
    Object.keys(P).forEach(function (k) {
      var els = all('.ctl[data-p="' + k + '"]'), val = s[k];
      if (val === undefined) return;
      var tr = s.transitions && s.transitions[k];
      els.forEach(function (el) {
        var pe = el.querySelector('.pend');
        if (pending[k] !== undefined) { pe.className = 'pend wait'; pe.textContent = '→ ' + pending[k] + ' (in attesa)'; }
        else if (tr) {
          pe.className = 'pend run';
          pe.innerHTML = '→ ' + tr.to + ' · ancora ' + tr.remaining_s + ' s <button class="stop1" title="Interrompi">■</button>';
          pe.querySelector('button').onclick = function (e) { e.preventDefault(); command('cancel_transitions', [k]); };
        } else if (!editing[k]) { pe.className = 'pend'; pe.textContent = ''; }
        var pg = el.querySelector('.prog'), rr = el.querySelector('input')._ruler;
        if (tr && pending[k] === undefined) {
          var sn = trSeen[k];
          if (!sn || sn.to !== tr.to || tr.remaining_s > sn.tot) sn = trSeen[k] = { to: tr.to, tot: Math.max(1, tr.remaining_s) };
          pg.hidden = false; pg.firstChild.style.width = Math.round(100 * (1 - tr.remaining_s / sn.tot)) + '%';
          if (rr) rr.setNow(val);
        } else { pg.hidden = true; if (rr) rr.setNow(null); }
        var mnEl = el.querySelector('.monnow');
        if (mnEl) {
          var mv = s.monitor_numerics ? s.monitor_numerics[k] : null, show = monNowShow(k, val, mv, tr);
          mnEl.hidden = !show; if (show) mnEl.textContent = 'sul monitor ora: ' + mv;
        }
        if (editing[k]) return;
        el.querySelector('output').textContent = fmt(k, val);           // il numero resta il valore di adesso
        el.querySelector('input').value = pending[k] !== undefined ? pending[k] : trTarget(s, k);   // BUG-01: il cursore, l'arrivo
        // ANDROID (righello a zone 22/11): righello sull'arrivo, limiti di allarme, tasto rapido del valore scelto pieno
        if (rr) {
          rr.sync();
          var L = s.alarm_limits || {}, LK = { heart_rate_bpm: ['hr_low', 'hr_high'], spo2_percent: ['spo2_low'], systolic_mmhg: ['sys_low', 'sys_high'], respiratory_rate_rpm: ['rr_low', 'rr_high'], etco2_mmhg: ['etco2_low', 'etco2_high'] }[k];
          rr.setLimits(LK && typeof L[LK[0]] === 'number' ? L[LK[0]] : null, LK && typeof L[LK[1]] === 'number' ? L[LK[1]] : null);
        }
        markPresets(el.querySelector('.presets'), pending[k] !== undefined ? pending[k] : trTarget(s, k));
      });
    });
  }

  // ---------- modalità di applicazione e modifiche in attesa ----------
  function setMode(t) {
    mode = t;
    all('#modes .mode').forEach(function (b) { b.className = 'mode' + (parseInt(b.dataset.t, 10) === t ? ' active' : ''); });
    $('customT').className = (t > 0 && [10, 20, 30, 60, 120].indexOf(t) < 0) ? 'active' : '';
    if (t === 0 && isPending()) applyNow();
    renderPending();
  }
  function changeRhythm(r) {
    // ANDROID (affordance 22/11, W-01): i ritmi di arresto, e l'uscita da un arresto (ROSC), partono SUBITO anche con una durata
    // scelta: stessa regola del tablet (prima con «30 s» anche la FV restava in attesa di «▶ Avvia», in fondo alla pagina)
    // ANDROID (insorgenza 22/11): il ritmo già in arrivo non riparte da capo (un secondo clic ricominciava il preludio); doppio clic
    // sullo stesso ritmo = uno (0,8 s); ogni cambio porta "onset" (Realistica = auto, Di colpo = immediate)
    var ro = state && state.rhythm_onset;
    if (ro && ro.to === r) { msg((RHYTHMS[r] || r) + ' è già in arrivo (' + Math.ceil(ro.remaining_s || 0) + ' s): «Subito ora» la fa partire adesso'); return Promise.resolve(false); }
    if (mode === 0 || isArrestR(r) || isArrestR(state && state.rhythm)) {
      if (!tapOnce('rhythm_' + r, 800)) return Promise.resolve(false);
      if (mode > 0 && pendingRhythm) { pendingRhythm = null; renderPending(); }
      var om = onsetMode;
      lastRhy = { name: 'set_rhythm', value: r, extra: { onset: om } };
      return command('set_rhythm', r, { onset: om }).then(function (ok) {
        if (ok) msg('Eseguito dal monitor: ritmo ' + (RHYTHMS[r] || r) + (om === 'immediate' ? ' (di colpo)' : ''));
        return ok;
      });
    }
    pendingRhythm = r; renderPending(); renderRhythms();
  }
  function currentStep() {
    var st = { params: JSON.parse(JSON.stringify(pending)), duration_s: mode };
    if (pendingRhythm) { st.rhythm = pendingRhythm; st.onset = onsetMode; }      // ANDROID (insorgenza 22/11)
    if (pendingMorph !== null) st.ecg_morphology = pendingMorph;                  // ANDROID (regia morfologie ECG 22/11)
    return st;
  }
  function clearPending() { pending = {}; pendingRhythm = null; pendingMorph = null; renderPending(); renderRhythms(); if (state) render(state); }
  function applyNow() {
    if (!isPending()) return;
    var st = currentStep(), saved = { p: pending, r: pendingRhythm, m: pendingMorph };
    if (st.rhythm) lastRhy = { name: 'transition', value: st };                     // ANDROID (insorgenza 22/11): per «Subito ora»
    pending = {}; pendingRhythm = null; pendingMorph = null; renderPending(); renderRhythms();
    cmdSeq++;
    api('POST', '/api/v1/command', { device_id: state && state.device_id, command: 'transition', value: st })
      .then(function (j) {
        if (!j.ok) { pending = saved.p; pendingRhythm = saved.r; pendingMorph = saved.m; renderPending(); renderRhythms(); }
        handle(j);
        if (!j.ok && saved.m !== null && j.error) msg('Non avviato: ' + ecgErrIt(j.error), true);       // ANDROID (regia morfologie ECG 22/11)
      }, function () { pending = saved.p; pendingRhythm = saved.r; pendingMorph = saved.m; renderPending(); msg('monitor non raggiungibile'); });
  }
  function describe(st) {
    var parts = Object.keys(st.params || {}).map(function (k) { return PNAME[k] + ' → ' + st.params[k]; });
    if (st.rhythm) parts.unshift(RHYTHMS[st.rhythm]);
    if (st.ecg_morphology !== undefined) parts.push('ECG → ' + morphShort(st.ecg_morphology === 'normal' ? 'normal' : morphEff()));   // ANDROID (regia morfologie ECG 22/11)
    return parts.join(', ') || '—';
  }
  function renderPending() {
    // ANDROID (affordance 22/11, W-01): fascia PREPARAZIONE in cima alla plancia, con «▶ Avvia» e «Scarta» vicino ai controlli,
    // e il bordo giallo tratteggiato della plancia finché è scelta una durata
    var prep = mode > 0, np = Object.keys(pending).length + (pendingRhythm ? 1 : 0) + (pendingMorph !== null ? 1 : 0);   // ANDROID (regia morfologie ECG 22/11)
    document.body.classList.toggle('preparing', prep);
    $('prepBand').hidden = !prep;
    if (prep) {
      $('prepText').textContent = 'PREPARAZIONE (' + mode + ' s) · ' + (np ? (np === 1 ? '1 modifica in attesa: ' : np + ' modifiche in attesa: ') + describe(currentStep())
        : 'ogni modifica aspetta «▶ Avvia»') + ' · i ritmi di arresto partono subito';
      $('prepGo').hidden = $('prepDrop').hidden = !np;
      $('prepGo').textContent = '▶ Avvia (' + mode + ' s)';
    }
    var running = state && state.transitions ? Object.keys(state.transitions) : [];
    $('stopbar').hidden = running.length === 0;
    if (running.length) $('runSummary').textContent = 'In corso: ' + running.map(function (k) {
      return (PNAME[k] || k) + ' → ' + state.transitions[k].to + ' (' + state.transitions[k].remaining_s + ' s)'; }).join(' · ');
    var show = mode > 0 && isPending();
    // ANDROID (affordance 22/11, W-01): «▶ Avvia» e «Scarta» stanno nella fascia PREPARAZIONE sopra la plancia; la vecchia fascia in
    // fondo resta nel codice (stessi tasti) ma non si mostra più, così la plancia non perde altezza a 1280×800
    $('gobar').hidden = true;
    if (show) { $('pendSummary').textContent = describe(currentStep()); $('go').textContent = '▶ Avvia (' + mode + ' s)'; }
  }
  function renderRhythms() {
    all('.rhythms button[data-r]').forEach(function (b) {
      var r = b.dataset.r, act = !!(state && r === state.rhythm);
      // ANDROID (affordance 22/11, CTL-05 / W-01): con una durata scelta i ritmi che partono comunque al clic portano «SUBITO»
      var now = mode > 0 && !act && (isArrestR(r) || isArrestR(state && state.rhythm));
      var arr = !!(state && state.rhythm_onset && state.rhythm_onset.to === r);      // ANDROID (insorgenza 22/11): «IN ARRIVO»
      b.className = (act ? 'active' : '') + (r === pendingRhythm ? ' pending' : '') + (now && !arr ? ' now' : '') + (arr ? ' arriving' : '');
    });
  }

  // ---------- ANDROID (insorgenza e ampiezza FV 22/11) ----------
  // «sul monitor ora: N» solo quando il numero a schermo (monitor_numerics, con il ritardo realistico) è diverso dal valore impostato
  // durante una rampa (transizione in corso, preludio, o valore cambiato da meno di 30 s): fuori dalle rampe non si mostra
  function monNowShow(k, val, mv, tr) {
    var now = Date.now();
    if (monLastVal[k] !== undefined && monLastVal[k] !== val) monChangedAt[k] = now;
    monLastVal[k] = val;
    if (mv === null || mv === undefined || val === null || val === undefined) return false;
    if (Math.abs(mv - val) < (k === 'heart_rate_bpm' ? 2 : 1)) return false;
    return !!(tr || (state && state.rhythm_onset) || (monChangedAt[k] && now - monChangedAt[k] < 30000));
  }
  var ROSHORT = { sinus: 'sinusale', sinus_tachycardia: 'tachicardia', sinus_bradycardia: 'bradicardia', afib: 'FA', vt_pulse: 'TV' };
  var VFCLS = { coarse: 'grossolana', medium: 'media', fine: 'fine' };
  var VFPICK = { coarse: 'grossolana', fine: 'fine', auto: 'automatica' };
  function mvTxt(v) { return Number(v).toFixed(2).replace('.', ','); }
  function vfClassOf(v) { return v >= 0.5 ? 'coarse' : v >= 0.2 ? 'medium' : 'fine'; }
  function renderOnset(s) {
    all('#onsetSeg button').forEach(function (b) { b.className = b.dataset.v === onsetMode ? 'active' : ''; });
    $('onsetHint').textContent = onsetMode === 'auto' ? 'il ritmo nuovo arriva con il suo preludio fisiologico (2–18 s)'
                                                      : 'il ritmo nuovo parte subito, senza preludio';
    // preludio in corso: «INSORGENZA IN CORSO · N s», fase → ritmo di arrivo, barra, «Subito ora»
    var o = s.rhythm_onset, band = $('onsetBand');
    band.hidden = !o;
    if (o) {
      band.classList.toggle('arrest', isArrestR(o.to));
      $('onsetTitle').textContent = 'INSORGENZA IN CORSO · ' + Math.ceil(o.remaining_s || 0) + ' s';
      $('onsetText').textContent = (o.phase_label || o.text || '') + ' → ' + (RHYTHMS[o.to] || o.to);
      band.title = o.text || '';
      band.querySelector('.obar b').style.width = (o.duration_s > 0 ? Math.min(100, Math.round(100 * o.elapsed_s / o.duration_s)) : 0) + '%';
    }
    if (!o || o.to !== 'vf') { if (s.rhythm !== 'vf') vfPick = null; }
    // ampiezza della FV: con la FV attuale (valori del monitor) o in arrivo (scelta che vale all'arrivo)
    var vfNow = s.rhythm === 'vf' && s.vf_amplitude_mv !== null && s.vf_amplitude_mv !== undefined, vfComing = !!(o && o.to === 'vf');
    $('vfBox').hidden = !(vfNow || vfComing);
    if (!(vfNow || vfComing)) return;
    var sel = null, mv = null, txt;
    if (vfNow) {
      mv = s.vf_amplitude_mv;
      if (s.vf_amplitude_mode === 'instructor') sel = Math.abs(mv - 1.0) < 0.005 ? 'coarse' : Math.abs(mv - 0.15) < 0.005 ? 'fine' : null;
      else sel = 'auto';
      txt = mvTxt(mv) + ' mV · ' + (VFCLS[s.vf_amplitude_class] || VFCLS[vfClassOf(mv)]) + ' · ' + (s.vf_amplitude_mode === 'instructor' ? 'impostata' : 'automatica');
    } else {
      sel = typeof vfPick === 'string' ? vfPick : null; mv = typeof vfPick === 'number' ? vfPick : null;
      txt = 'FV in arrivo · all\'arrivo: ' + (vfPick === null ? 'grossolana, poi automatica' : typeof vfPick === 'number' ? mvTxt(vfPick) + ' mV' : VFPICK[vfPick]);
    }
    all('#vfSeg button').forEach(function (b) { b.className = b.dataset.v === sel ? 'active' : ''; });
    if (editing.vf) return;                          // mentre si trascina il cursore il testo è il suo
    $('vfVal').textContent = txt;
    $('vfRange').value = String(mv !== null ? mv : (sel === 'fine' ? 0.15 : 1.0));
    $('vfWarn').hidden = !(mv !== null && mv < 0.1);
  }
  // «Subito ora»: lo stesso comando di ritmo, rimandato di colpo (con la transizione di «▶ Avvia» o di uno stato, se era quella)
  function onsetSkip() {
    var o = state && state.rhythm_onset;
    if (!o || !tapOnce('onsetSkip', 900)) return;
    var name = 'set_rhythm', value = o.to, extra = { onset: 'immediate' };
    if (lastRhy && lastRhy.name === 'transition' && lastRhy.value && lastRhy.value.rhythm === o.to) {
      name = 'transition'; value = JSON.parse(JSON.stringify(lastRhy.value)); value.onset = 'immediate'; extra = undefined;
    }
    var pick = o.to === 'vf' ? vfPick : null;
    command(name, value, extra).then(function (ok) {
      if (!ok) return;
      msg('Eseguito dal monitor: ' + (RHYTHMS[o.to] || o.to) + ' subito (preludio saltato)');
      if (pick !== null) command('set_vf_amplitude', pick);       // il salto del preludio cancella la scelta in attesa: si rimanda
    });
  }
  function vfSet(v) {
    if (!tapOnce('vf', 500)) return;
    var coming = !!(state && state.rhythm !== 'vf' && state.rhythm_onset && state.rhythm_onset.to === 'vf');
    command('set_vf_amplitude', v).then(function (ok) {
      if (!ok) return;
      if (coming) vfPick = v;
      msg('Eseguito dal monitor: ampiezza FV ' + (typeof v === 'number' ? mvTxt(v) + ' mV' : VFPICK[v]) + (coming ? ' (all\'arrivo della FV)' : '') +
          (typeof v === 'number' && v < 0.1 ? ' — il DAE non consiglierà la scarica' : ''));
      if (state) renderOnset(state);
    });
  }

  // ---------- ANDROID (regia morfologie ECG 22/11) ----------
  // Morfologia dell'ECG (API del cuore: set_ecg_morphology, campi parziali; "normal" azzera; "ecg_morphology" anche dentro una
  // transition). Con «Scatto · 0 s» parte subito (set_ecg_morphology); con una durata scelta va nel pacchetto di «▶ Avvia»
  // (transition.ecg_morphology). L'interpretazione automatica del 12D è un'impostazione della carta: parte sempre subito.
  var ECG_ST = [['none', 'Nessuna alterazione'], ['stemi_inferior', 'STEMI inferiore'], ['stemi_inferoposterior', 'STEMI infero-posteriore'],
    ['stemi_anterior', 'STEMI anteriore'], ['stemi_lateral', 'STEMI laterale'], ['stemi_posterior', 'STEMI posteriore isolato'],
    ['nstemi_depression', 'Sottoslivellamento ST (NSTEMI)'], ['left_main', 'Tronco comune (ST ↓ diffuso, ↑ aVR)'], ['wellens', 'Wellens'],
    ['de_winter', 'de Winter'], ['pericarditis', 'Pericardite']];
  var ECG_BBB = [['none', 'Nessuno'], ['rbbb', 'Blocco di branca destro'], ['lbbb', 'Blocco di branca sinistro'], ['lafb', 'Emiblocco anteriore sinistro'],
    ['rbbb_lafb', 'Bifascicolare (BBD + EAS)']];
  var ECG_AV = [['none', 'Conduzione normale'], ['first', 'BAV 1° grado'], ['mobitz1', 'BAV 2° Mobitz I (Wenckebach)'], ['mobitz2', 'BAV 2° Mobitz II'],
    ['2to1', 'BAV 2° 2:1'], ['high_grade', 'BAV avanzato (3:1)'], ['third', 'BAV 3° grado (completo)']];
  var ECG_EXTRA = [['hyperkalemia', 'Iperkaliemia'], ['hyperkalemia_severe', 'Iperkaliemia grave'], ['hypokalemia', 'Ipokaliemia'], ['long_qt', 'QT lungo'],
    ['lvh', 'Ipertrofia ventricolare sinistra'], ['wpw', 'Preeccitazione (WPW)'], ['brugada', 'Brugada tipo 1'], ['pe', 'Embolia polmonare (S1Q3T3)'],
    ['hypothermia', 'Ipotermia (onde J di Osborn)'], ['low_voltage', 'Bassi voltaggi con alternanza']];
  var ECG_SHORT = { stemi_inferior: 'STEMI inf', stemi_inferoposterior: 'STEMI inf-post', stemi_anterior: 'STEMI ant', stemi_lateral: 'STEMI lat',
    stemi_posterior: 'STEMI post', nstemi_depression: 'ST ↓', left_main: 'ST ↓ diffuso', wellens: 'Wellens', de_winter: 'de Winter', pericarditis: 'Pericardite',
    rbbb: 'BBD', lbbb: 'BBS', lafb: 'EAS', rbbb_lafb: 'BBD+EAS',
    first: 'BAV 1°', mobitz1: 'Mobitz I', mobitz2: 'Mobitz II', '2to1': 'BAV 2:1', high_grade: 'BAV 3:1', third: 'BAV 3°',
    hyperkalemia: 'iper-K', hyperkalemia_severe: 'iper-K grave', hypokalemia: 'ipo-K', long_qt: 'QT lungo', lvh: 'IVS', wpw: 'WPW', brugada: 'Brugada',
    pe: 'EP', hypothermia: 'ipotermia', low_voltage: 'bassi voltaggi' };
  var ECG_NORMAL = { st: 'none', st_mm: null, bbb: 'none', av_block: 'none', pr_ms: null, escape: null, extra: [], interpretation: true };
  var pendingMorph = null;            // null | 'normal' | { campi parziali } in attesa di «▶ Avvia»
  var ecg12Seen = null;               // ultimo ecg12_last.seq visto (null = non ancora letto)
  function ecgName(list, v) { for (var i = 0; i < list.length; i++) if (list[i][0] === v) return list[i][1]; return v; }
  function morphOf(s) {
    var m = s && s.ecg_morphology, o = JSON.parse(JSON.stringify(ECG_NORMAL));
    if (m) Object.keys(o).forEach(function (k) { if (m[k] !== undefined) o[k] = m[k]; });
    if (!o.extra) o.extra = [];
    return o;
  }
  function morphMerge(base, p) {
    var o = JSON.parse(JSON.stringify(base));
    if (p === 'normal') { var it = o.interpretation; o = JSON.parse(JSON.stringify(ECG_NORMAL)); o.interpretation = it; return o; }
    if (p) Object.keys(p).forEach(function (k) { o[k] = p[k]; });
    if (p && p.st !== undefined && p.st_mm === undefined && p.st !== base.st) o.st_mm = null;       // come il cuore: territorio nuovo = ampiezza di serie
    if (o.av_block !== 'first') o.pr_ms = null;
    if (o.av_block !== 'third') o.escape = null; else if (!o.escape) o.escape = 'ventricular';
    return o;
  }
  function morphEff() { return morphMerge(morphOf(state), pendingMorph); }
  function morphIsNormal(m) { return !m || m === 'normal' || ((m.st || 'none') === 'none' && (m.bbb || 'none') === 'none' && (m.av_block || 'none') === 'none' && !(m.extra && m.extra.length)); }
  // «STEMI inf · BAV 1°» (al massimo tre voci, poi «+N»)
  function morphShort(m, noMm) {
    if (m === 'normal') return 'ECG normale';
    if (morphIsNormal(m)) return 'normale';
    var p = [];
    if (m.st && m.st !== 'none') p.push(ECG_SHORT[m.st] + (m.st_mm && !noMm ? ' ' + String(m.st_mm).replace('.', ',') + ' mm' : ''));
    if (m.bbb && m.bbb !== 'none') p.push(ECG_SHORT[m.bbb]);
    if (m.av_block && m.av_block !== 'none') p.push(ECG_SHORT[m.av_block]);
    (m.extra || []).forEach(function (x) { p.push(ECG_SHORT[x] || x); });
    return p.length > 3 ? p.slice(0, 3).join(' · ') + ' +' + (p.length - 3) : p.join(' · ');
  }
  // valore da mandare per una fase/uno stato (senza «label», con i soli campi che contano)
  function morphPayload(m) {
    if (morphIsNormal(m)) return 'normal';
    var o = { st: m.st || 'none', bbb: m.bbb || 'none', av_block: m.av_block || 'none', extra: (m.extra || []).slice() };
    if (m.st_mm) o.st_mm = m.st_mm;
    if (o.av_block === 'first' && m.pr_ms) o.pr_ms = m.pr_ms;
    if (o.av_block === 'third') o.escape = m.escape || 'ventricular';
    return o;
  }
  // combinazioni che il cuore rifiuta: il motivo (null = permessa) di una scelta, data la morfologia m
  function morphWhy(m, f, v) {
    var ex = m.extra || [];
    if (f === 'bbb' && v !== 'none' && ex.indexOf('wpw') >= 0) return 'con la preeccitazione (WPW) niente blocchi di branca';
    if (f === 'av_block' && v !== 'none' && ex.indexOf('wpw') >= 0) return 'con la preeccitazione (WPW) niente blocchi AV';
    if (f === 'extra' && ex.indexOf(v) < 0) {
      if (v === 'wpw' && m.bbb !== 'none') return 'la preeccitazione (WPW) non si combina con un blocco di branca';
      if (v === 'wpw' && m.av_block !== 'none') return 'la preeccitazione (WPW) non si combina con un blocco AV';
      if (v === 'hyperkalemia' && ex.indexOf('hyperkalemia_severe') >= 0) return 'c\'è già l\'iperkaliemia grave';
      if (v === 'hyperkalemia_severe' && ex.indexOf('hyperkalemia') >= 0) return 'c\'è già l\'iperkaliemia';
      if (v === 'hypokalemia' && (ex.indexOf('hyperkalemia') >= 0 || ex.indexOf('hyperkalemia_severe') >= 0)) return 'ipo- e iperkaliemia non stanno insieme';
      if ((v === 'hyperkalemia' || v === 'hyperkalemia_severe') && ex.indexOf('hypokalemia') >= 0) return 'ipo- e iperkaliemia non stanno insieme';
    }
    return null;
  }
  function ecgErrIt(e) {
    e = String(e || '');
    if (/wpw cannot be combined with a bundle/.test(e)) return 'la preeccitazione (WPW) non si combina con un blocco di branca';
    if (/wpw cannot be combined with an AV/.test(e)) return 'la preeccitazione (WPW) non si combina con un blocco AV';
    if (/hyperkalemia or hyperkalemia_severe/.test(e)) return 'scegli iperkaliemia oppure iperkaliemia grave';
    if (/mutually exclusive/.test(e)) return 'ipo- e iperkaliemia non stanno insieme';
    return e;
  }
  // una scelta della regia: subito (set_ecg_morphology) o nel pacchetto della preparazione
  function morphPick(part, key, okText) {
    if (!state) return;
    if (!tapOnce('ecgm_' + key, 800)) return;
    if (mode > 0) {
      var p = 'normal';
      if (part !== 'normal') {
        p = pendingMorph === 'normal' ? { st: 'none', st_mm: null, bbb: 'none', av_block: 'none', extra: [] } : JSON.parse(JSON.stringify(pendingMorph || {}));
        Object.keys(part).forEach(function (k) { p[k] = part[k]; });
      }
      pendingMorph = p;
      // la preparazione non cambia niente rispetto a com'è il paziente: niente in attesa
      if (JSON.stringify(morphPayload(morphEff())) === JSON.stringify(morphPayload(morphOf(state)))) pendingMorph = null;
      renderPending(); renderEcgm(state); return;
    }
    command('set_ecg_morphology', part).then(function (ok) {
      if (ok) msg('Eseguito dal monitor: ECG ' + (okText || (state && state.ecg_morphology ? state.ecg_morphology.label : '')));
      else if (lastCmdErr) msg('Morfologia non applicata: ' + ecgErrIt(lastCmdErr), true);
    });
  }
  var ecgmBuilt = false;
  function buildEcgm() {
    if (ecgmBuilt) return; ecgmBuilt = true;
    var mk = function (f, list) {
      var box = document.querySelector('.ecgopts[data-f="' + f + '"]');
      box.innerHTML = list.map(function (o) { return '<button type="button" data-v="' + o[0] + '">' + esc(o[1]) + '</button>'; }).join('');
      all('button', box).forEach(function (b) {
        b.onclick = function () {
          var v = b.dataset.v, m = morphEff(), why = morphWhy(m, f, v);
          if (why) { msg('Non si può: ' + why, true); return; }
          if (f === 'extra') {
            var ex = (m.extra || []).slice(), i = ex.indexOf(v);
            if (i >= 0) ex.splice(i, 1); else ex.push(v);
            morphPick({ extra: ex }, 'extra_' + v);
          } else {
            if (m[f] === v) return;
            var part = {}; part[f] = v; morphPick(part, f + '_' + v);
          }
        };
      });
    };
    mk('st', ECG_ST); mk('bbb', ECG_BBB); mk('av_block', ECG_AV); mk('extra', ECG_EXTRA);
    $('ecgStMm').innerHTML = '<option value="">di serie</option>' + [0.5, 1, 1.5, 2, 2.5, 3, 4, 5, 6, 8].map(function (v) {
      return '<option value="' + v + '">' + String(v).replace('.', ',') + ' mm</option>'; }).join('');
    $('ecgStMm').onchange = function () { var v = $('ecgStMm').value; tapAt.ecgm_stmm = 0; morphPick({ st_mm: v === '' ? null : parseFloat(v) }, 'stmm'); };
    var pr = '<option value="">di serie (280 ms)</option>';
    for (var v = 210; v <= 400; v += 10) pr += '<option value="' + v + '">' + v + ' ms</option>';
    $('ecgPr').innerHTML = pr;
    $('ecgPr').onchange = function () { var v = $('ecgPr').value; tapAt.ecgm_pr = 0; morphPick({ pr_ms: v === '' ? null : parseInt(v, 10) }, 'pr'); };
    all('#ecgEsc button').forEach(function (b) { b.onclick = function () { if (morphEff().escape !== b.dataset.v) morphPick({ escape: b.dataset.v }, 'esc_' + b.dataset.v); }; });
    $('ecgmNormal').onclick = function (e) {
      e.preventDefault(); e.stopPropagation();
      if (morphIsNormal(morphEff())) { msg('L\'ECG è già normale (il ritmo non cambia)'); return; }
      morphPick('normal', 'normal', 'normale');
    };
    $('ecgInterp').querySelector('input').onchange = function (e) {
      var on = e.target.checked;
      command('set_ecg_morphology', { interpretation: on }).then(function (ok) {
        if (ok) msg('Eseguito dal monitor: interpretazione automatica sul 12D ' + (on ? 'attiva' : 'spenta'));
        if (state) renderEcgm(state);
      });
    };
    var box = $('ecgmBox');
    try { box.open = sessionStorage.getItem('sf_ecgm') === '1'; } catch (e) {}
    box.addEventListener('toggle', function () { try { sessionStorage.setItem('sf_ecgm', box.open ? '1' : '0'); } catch (e) {} });
  }
  function renderEcgm(s) {
    buildEcgm();
    var now = morphOf(s), eff = morphEff(), pend = pendingMorph !== null;
    $('ecgmNow').textContent = (s.ecg_morphology && s.ecg_morphology.label) || 'normale';
    $('ecgmNow').title = $('ecgmNow').textContent;
    $('ecgmPend').hidden = !pend;
    if (pend) $('ecgmPend').textContent = 'IN ATTESA → ' + morphShort(pendingMorph === 'normal' ? 'normal' : eff);
    $('ecgmBox').classList.toggle('altered', !morphIsNormal(now));
    var whys = [];
    all('.ecgopts').forEach(function (box) {
      var f = box.dataset.f;
      all('button', box).forEach(function (b) {
        var v = b.dataset.v;
        var cur = f === 'extra' ? (now.extra || []).indexOf(v) >= 0 : now[f] === v;
        var want = f === 'extra' ? (eff.extra || []).indexOf(v) >= 0 : eff[f] === v;
        var why = morphWhy(eff, f, v);
        b.className = (cur ? 'active' : '') + (pend && want !== cur ? ' pending' + (want ? '' : ' off') : '') + (why ? ' blocked' : '');
        b.setAttribute('aria-pressed', want ? 'true' : 'false');
        b.title = why ? 'Non disponibile: ' + why : '';
        if (why && whys.indexOf(why) < 0) whys.push(why);
      });
    });
    $('ecgmWhy').textContent = whys.length ? 'Spente: ' + whys.join('; ') + '.' : '';
    var mmLab = document.querySelector('.ecgmmm');
    mmLab.hidden = eff.st === 'none';
    if (document.activeElement !== $('ecgStMm')) $('ecgStMm').value = eff.st_mm ? String(eff.st_mm) : '';
    document.querySelector('.ecgmpr').hidden = eff.av_block !== 'first';
    if (document.activeElement !== $('ecgPr')) $('ecgPr').value = eff.pr_ms ? String(eff.pr_ms) : '';
    document.querySelector('.ecgmesc').hidden = eff.av_block !== 'third';
    all('#ecgEsc button').forEach(function (b) { b.className = b.dataset.v === (eff.escape || 'ventricular') ? 'active' : ''; });
    var ip = $('ecgInterp').querySelector('input'); if (document.activeElement !== ip) ip.checked = now.interpretation !== false;
    // indicatore nella tessera Ritmo e frequenza degli atri nei blocchi AV (tessera FC)
    var tg = $('v_ecgm'), al = !morphIsNormal(now);
    tg.hidden = !al; if (al) { tg.textContent = morphShort(now, true); tg.title = 'ECG: ' + ((s.ecg_morphology && s.ecg_morphology.label) || ''); }
    // i partecipanti hanno aperto la carta a 12 derivazioni (il monitor scrive già la voce nel registro)
    var l12 = s.ecg12_last, seq = l12 && l12.seq ? l12.seq : 0;
    if (ecg12Seen !== null && seq > ecg12Seen)
      msg('I partecipanti hanno acquisito il 12 derivazioni' + (l12.electrodes === false ? ' (senza elettrodi: carta vuota)' : (l12.heart_rate_bpm !== null && l12.heart_rate_bpm !== undefined ? ' · FC ' + l12.heart_rate_bpm : '')) +
          (l12.interpretation ? ' · referto: ' + String(l12.interpretation).split(';')[0].trim() : ''));
    ecg12Seen = seq;
  }

  // ---------- chip dei sensori ----------
  // ANDROID (affordance 22/11, W-07): a scenario avviato togliere una sonda chiede conferma nella chip («Staccare? Sì · No»);
  // la × ha un'area di 24×24 px
  var chipAsk = null, chipAskT = null;
  function renderChips(s) {
    var chips = $('chips'), sig = JSON.stringify([s.sensors, s.sensor_sites, selSensor, chipAsk]);
    if (chips.dataset.sig === sig) return;
    chips.dataset.sig = sig;
    chips.innerHTML = ORDER.map(function (k) {
      var o = on(k);
      return '<span class="chip' + (o ? ' on' : '') + (selSensor === k ? ' sel' : '') + '" data-k="' + k + '" style="color:' + SENS[k].color + '"' +
        ' title="' + (o ? SENS[k].full + ' · ' + esc(siteLabel(siteOf(k))) : 'Applica ' + SENS[k].full + ' (' + esc(siteLabel(SENS[k].def)) + ')') + '">' +
        '<i></i><b>' + SENS[k].name + '</b><em>' + (o ? esc(siteLabel(siteOf(k))) : 'applica') + '</em>' +
        (o ? (chipAsk === k ? '<span class="ask">Staccare? <button class="yes">Sì</button><button class="no">No</button></span>'
               : '<button class="x" title="Togli ' + SENS[k].full + ' dal paziente">×</button>') : '') + '</span>';
    }).join('');
  }

  // ---------- saturimetro palmare (SimulaFranz_Saturimetro) ----------
  // che cosa mostra il saturimetro (stessa logica del dispositivo: sensore, ritardo, segnale, polso, bassa perfusione)
  function oxiShown(s) {
    var o = s.oximeter; if (!o || !o.power) return '';
    if (!o.sensor_connected) return '---';
    var since = o.sensor_since_s || 0, d = s.spo2_delay_s || 0;
    if (since < d) return since < d / 2 ? 'ricerca' : '---';
    if (!s.spo2_signal_valid || o.cuff_occluded || !s.pulse_present) return '---';
    return s.spo2_percent + (s.spo2_low_perfusion || s.spo2_low_perfusion_flag ? '?' : '') + ' · ' + s.pulse_rate_bpm;
  }
  var OXI_L = ['spo2_low', 'spo2_high', 'pr_low', 'pr_high'], oxiLimitsDirty = false;
  function renderOxi(s) {
    var o = s.oximeter; if (!o) return;
    var badge = $('oxiBadge'); badge.hidden = !o.online; badge.textContent = o.power ? 'on' : 'off';
    $('oxiLink').textContent = o.online ? 'in linea' : 'non in linea (nessuna richiesta da 6 s)';
    $('oxiLink').className = 'hint ' + (o.online ? 'ok' : 'warn');
    var shown = oxiShown(s);
    $('oxiStatus').textContent = (o.power ? 'Acceso' : 'Spento') + ' · sensore ' + (o.sensor_connected ? 'applicato (' + siteLabel(o.site || SENS.oxi.def) + ')' : 'non applicato') +
      (o.power ? ' · mostra: ' + (shown || '—') : '') + (o.muted_remaining_s ? ' · allarmi silenziati ' + fmtMMSS(o.muted_remaining_s) : '');
    var p = $('oxiPower'); p.className = 'toggle ' + (o.power ? 'on' : 'off'); p.textContent = o.power ? 'Acceso' : 'Spento';
    var sb = $('oxiSensor'); sb.textContent = o.sensor_connected ? 'Togli il sensore' : 'Applica il sensore (' + siteLabel(SENS.oxi.def) + ')';
    $('oxiMute').disabled = !o.power;
    if (!oxiLimitsDirty && o.alarm_limits) OXI_L.forEach(function (k) { var i = $('ol_' + k); if (document.activeElement !== i) i.value = o.alarm_limits[k]; });
    if (!editing.oxiVolume) { $('oxiVolume').value = o.volume; $('o_oxiVolume').textContent = o.volume; }
  }

  // ---------- defibrillatore ----------
  function renderDefib(s) {
    var d = s.defib; if (!d) return;
    var phase = { pads: 'applicare le piastre', analyzing: 'analisi', charging: 'carica', ready: 'pronto allo shock', cpr: 'periodo RCP' + (s.cpr_active ? '' : ' (nessuna compressione)') };   // BugFix 23/11: la RCP la decide cpr_active
    var main = DMODE[d.mode] || d.mode;
    var sub = 'shock ' + d.shock_count + (d.shock_count ? ' (ultimo ' + d.last_shock_energy_j + ' J)' : '');
    if (d.mode === 'aed') sub = phase[d.aed_phase] + (d.aed_remaining_s ? ' ' + d.aed_remaining_s + ' s' : '') + ' · ' + sub;
    if (d.mode === 'pacer') sub = (d.pacer.running ? d.pacer.rate_ppm + ' ppm ' + d.pacer.output_ma + ' mA · ' +
      (d.pacer.capture ? (d.pacer.mechanical_capture ? 'CATTURA + polso' : 'cattura elettrica, senza polso') : (d.pacer.active ? 'NESSUNA CATTURA' : 'inibito')) : 'fermo') + ' · ' + sub;
    $('v_defib').textContent = main + (d.pads_connected ? '' : ' · piastre no');
    $('v_defib_sub').textContent = sub;
    $('d_status').innerHTML = esc(d.message || '') + '<small>' + esc('Modalità ' + main + ' · piastre ' + (d.pads_connected ? 'applicate' : 'NON applicate') +
      ' · energia ' + d.energy_j + ' J · ' + ({ none: 'scarico', charging: 'in carica', ready: 'carico' }[d.charge] || d.charge) + (d.sync ? ' · SYNC' : '') + ' · ' + sub) + '</small>';
    if (document.activeElement !== $('d_mode')) $('d_mode').value = d.mode;
    var om = (d.config && d.config.outcome_mode) || 'instructor';
    all('#d_outcome button').forEach(function (b) { b.className = b.dataset.v === om ? 'active' : ''; });
    $('d_rules').className = 'dcfg' + (om === 'instructor' ? ' disabled' : '');
    $('dcfgHint').textContent = om === 'instructor' ? 'Modalità "decido io": le regole di conversione sono ignorate.' : '';
    // ANDROID (fix analisi 22/11, terzo giro): la domanda è legata al numero della scarica. Una scarica nuova chiude la vecchia
    // (il monitor la chiude da sé: lo si dice) o la rifà per la scarica nuova (lo si scrive nella domanda)
    var ocWas = ocShown;
    ocShown = d.outcome_pending ? d.outcome_shock : 0;
    if (ocWas && ocShown !== ocWas) ocRenewedFrom = ocShown ? ocWas : 0;
    if (!ocShown) ocRenewedFrom = 0;
    if (ocWas && !ocShown && (d.shock_count || 0) > ocWas) msg('Scarica n. ' + d.shock_count + ': la domanda sull\'esito della scarica n. ' + ocWas + ' si è chiusa da sola');
    $('d_decide').hidden = !d.outcome_pending;
    renderOutcomeLock(d);
    $('tabDefibBadge').hidden = !d.outcome_pending;
    if (d.outcome_pending) $('d_decide_text').textContent = (ocRenewedFrom ? 'Domanda aggiornata alla scarica n. ' + d.outcome_shock + ' (quella della n. ' + ocRenewedFrom + ' non vale più). ' : '') +
      (d.outcome_sync ? 'Cardioversione sincronizzata' : 'Scarica') + ' n. ' + d.outcome_shock +
      (d.outcome_energy_j ? ' (' + d.outcome_energy_j + ' J)' : '') + ' erogata su ' + (RHYTHMS[d.outcome_from] || d.outcome_from) + ': che cosa succede adesso?';
    $('d_dec_stay').textContent = 'Resta in ' + (RHYTHMS[d.outcome_from] || 'ritmo pre-scarica');
    if (!dcfgDirty && d.config) {
      DCFG_NUM.forEach(function (k) { if (document.activeElement !== $('c_' + k)) $('c_' + k).value = d.config[k]; });
      DCFG_BOOL.forEach(function (k) { $('c_' + k).checked = !!d.config[k]; });
      DCFG_RHY.forEach(function (k) { if (document.activeElement !== $('c_' + k)) $('c_' + k).value = d.config[k]; });
    }
    var log = $('d_log'), ev = d.events || [], last = ev.length ? ev[ev.length - 1].time + ev[ev.length - 1].text : '';
    if (log.dataset.n !== String(ev.length) || log.dataset.last !== last) {
      log.dataset.n = String(ev.length); log.dataset.last = last;
      log.innerHTML = ev.map(function (e) { return '<li class="' + (e.instructor ? 'instr' : '') + '">' + esc(e.time + '  (' + e.elapsed + ')  ' + e.text) + '</li>'; }).join('');
      log.scrollTop = log.scrollHeight;
    }
  }

  // ---------- rendering generale ----------
  function setMon(id, txt) {                        // riga "monitor:" delle tessere
    var el = $(id); if (!el) return;
    el.textContent = 'monitor: ' + (txt || '--');
    el.classList.toggle('none', !txt);
  }
  function render(s) {
    if (!s) return;
    state = s;
    tx12Render();                     // ANDROID (trasmissione 12D 22/11): fase dello scenario e morfologia di adesso nell'avviso
    $('device').textContent = '→ ' + s.device_id + ' · v' + s.version;
    // tessere: in grande i valori VERI del paziente, sotto ciò che il discente vede sul monitor
    $('v_rhythm').textContent = RHYTHMS[s.rhythm] || s.rhythm;
    var vp = $('v_pulse');
    vp.textContent = (s.pulse_present ? 'polso presente' : 'SENZA POLSO') + (s.cpr_active ? ' · RCP' : '') +
      (s.rhythm_onset ? ' · → ' + (RCODE[s.rhythm_onset.to] || ROSHORT[s.rhythm_onset.to] || s.rhythm_onset.to) + ' ' + Math.ceil(s.rhythm_onset.remaining_s || 0) + ' s' : '');   // ANDROID (insorgenza 22/11)
    vp.classList.toggle('none', !!s.pulse_present && !s.cpr_active);
    var codes = { vt: 'TV', vt_pulse: 'TV', vf: 'FV', asystole: 'Asy' };
    // ANDROID (affordance 22/11, X-02): senza polso il numero grande non è la FC («82» verde = polso normale): diventa la sigla del
    // ritmo in rosso; la FC impostata resta scritta come «al ROSC: 82»
    var arrest = s.pulse_present === false;
    $('v_hr').textContent = arrest ? (RCODE[s.rhythm] || '—') : s.heart_rate_bpm;
    $('v_hr').parentElement.classList.toggle('arrest', arrest);
    var monHr = (s.sensors.ecg && codes[s.rhythm]) || (s.displayed_heart_rate_bpm === null ? '' : String(s.displayed_heart_rate_bpm));
    if (arrest) { var mh = $('m_hr'); mh.textContent = 'al ROSC: ' + s.heart_rate_bpm + ' · monitor: ' + (monHr || '--'); mh.classList.remove('none'); }
    else setMon('m_hr', monHr);
    // ANDROID (regia morfologie ECG 22/11): nei blocchi AV la FC è quella dei ventricoli; accanto la frequenza degli atri
    var av = s.ecg_morphology && s.ecg_morphology.av_block;
    if (!arrest && s.av_block_active && av && av !== 'first' && s.atrial_rate_bpm !== null && s.atrial_rate_bpm !== undefined) {
      var mh2 = $('m_hr'); mh2.textContent = 'atri ' + s.atrial_rate_bpm + ' · ' + mh2.textContent; mh2.classList.remove('none');
    }
    // ANDROID (affordance 22/11, W-06): ritmo per intero (a capo, mai troncato) e «SENZA POLSO» come etichetta d'allarme
    $('v_rhythm').title = RHYTHMS[s.rhythm] || s.rhythm;
    $('v_bp').textContent = s.systolic_mmhg + '/' + s.diastolic_mmhg;
    var nibpTxt = s.nibp_last_systolic_mmhg !== null ? s.nibp_last_systolic_mmhg + '/' + s.nibp_last_diastolic_mmhg
      : (s.nibp_status === 'error' && s.nibp_error === 'no_pulse' ? '?/?' : '---/---');
    var bpParts = [];
    if (s.art_displayed_systolic_mmhg !== null) bpParts.push('ART ' + s.art_displayed_systolic_mmhg + '/' + s.art_displayed_diastolic_mmhg);
    if (s.nibp_status === 'measuring') bpParts.push('NIBP in corso…');
    else if (s.nibp_last_systolic_mmhg !== null) bpParts.push('NIBP ' + nibpTxt);
    setMon('m_bp', bpParts.join(' · '));
    $('v_spo2').textContent = s.spo2_percent;
    var oxiTxt = oxiShown(s);
    setMon('m_spo2', (s.spo2_displayed === null ? '' : s.spo2_displayed + (s.spo2_low_perfusion ? '?' : '')) + (oxiTxt ? (s.spo2_displayed === null ? '' : ' · ') + 'sat. ' + oxiTxt : ''));
    renderOxi(s);
    renderMancanti(s);                // ANDROID (funzioni mancanti 22/11)
    $('v_co2').textContent = s.respiratory_rate_rpm + ' · ' + s.etco2_mmhg;
    setMon('m_co2', s.etco2_displayed === null ? '' : 'EtCO₂ ' + s.etco2_displayed);
    $('v_temp').textContent = Number(s.temperature_c).toFixed(1) + '° · ' + (s.crt_s !== undefined ? s.crt_s + ' s' : '--');
    setMon('m_temp', s.temperature_displayed === null || s.temperature_displayed === undefined ? '' : s.temperature_displayed.toFixed(1) + ' °C');
    // NIBP nella scheda pressione
    $('n_last').textContent = nibpTxt;
    var nibpSt = { idle: 'nessuna misura', measuring: 'misura in corso…', complete: 'mmHg',
                   error: s.nibp_error === 'cuff' ? 'bracciale non collegato / perdita' : s.nibp_error === 'motion' ? 'misura fallita: movimenti' : 'nessun polso rilevato' };
    $('n_state').textContent = nibpSt[s.nibp_status] || '';
    // ANDROID (pannello RCP 22/11): in corso il pulsante NON ferma più la RCP (riapre «Come vedi l'RCP?»)
    var cp = $('cpr'); cp.className = 'act danger' + (s.cpr_active ? ' on' : ''); cp.textContent = s.cpr_active ? '● RCP in corso' : '▶ RCP';
    cp.title = s.cpr_active ? 'Apre «Come vedi l\'RCP?» (frequenza, profondità, cambio operatore). La RCP si ferma solo con «Interrompi RCP» nel pannello.'
                            : 'Avvia la RCP e apre «Come vedi l\'RCP?» (di base tutto corretto)';
    rcpRender(s);
    var vb = $('vent'); vb.disabled = !s.ventilation_manual;
    vb.textContent = s.ventilation_manual ? 'Ventila' + (s.breaths_last_min ? ' · ' + s.breaths_last_min + '/min' : '') : 'Ventila (respira da solo)';
    if (document.activeElement !== $('nibpInterval')) $('nibpInterval').value = String(s.nibp_interval_min);
    $('nibpNext').textContent = s.nibp_interval_min > 0 && s.nibp_next_in_s !== null
      ? 'Prossima misura tra ' + Math.floor(s.nibp_next_in_s / 60) + ':' + ('0' + (s.nibp_next_in_s % 60)).slice(-2) : 'Solo misure manuali.';
    $('nibpStart').disabled = $('nibpStart2').disabled = s.nibp_status === 'measuring';
    if (s.alarm_enabled) Object.keys(s.alarm_enabled).forEach(function (k) {
      var c = $('ae_' + k); if (c && document.activeElement !== c) c.checked = !!s.alarm_enabled[k];
    });
    // SpO2
    var g = $('signalSw'); g.querySelector('input').checked = !!s.spo2_signal_valid;
    var lp = $('lowPerfSw'), lpOn = !!s.spo2_low_perfusion_flag;
    lp.querySelector('input').checked = lpOn; lp.className = 'sw flag' + (lpOn ? ' warn' : '');
    if (document.activeElement !== $('spo2Delay') && s.spo2_delay_s !== undefined) $('spo2Delay').value = String(s.spo2_delay_s);
    $('spo2Acq').textContent = s.spo2_acquiring ? 'acquisizione in corso…' : '';
    var sameArm = on('spo2') && on('nibp') && sideOf(siteOf('spo2')) && sideOf(siteOf('spo2')) === sideOf(siteOf('nibp')) && /^finger/.test(siteOf('spo2'));
    $('spo2Note').textContent = s.spo2_cuff_occluded ? 'Bracciale in misura sullo stesso braccio: SpO₂ persa finché non si sgonfia.'
      : sameArm ? 'Sonda e bracciale sullo stesso braccio: durante ogni misura NIBP la SpO₂ si perde.' : '';
    var artSame = on('art') && on('nibp') && /^(radial|ulnar|brachial)/.test(siteOf('art')) && sideOf(siteOf('art')) && sideOf(siteOf('art')) === sideOf(siteOf('nibp'));
    $('artNote').textContent = s.art_cuff_occluded ? 'Bracciale in misura sullo stesso braccio della linea arteriosa: curva smorzata finché non si sgonfia.'
      : artSame ? 'Linea arteriosa e bracciale sullo stesso braccio: durante ogni misura NIBP la curva arteriosa si smorza.' : '';
    // monitor
    // elenco dei layout dal monitor (id + nome): qui non è scritto nessun nome
    // ANDROID (categorie dei layout 22/11): raggruppati per categoria («Default», «Personalizzati») come arrivano dal
    // catalogo del monitor (campo category; un monitor che non lo manda: tutto in «Default»)
    var LAYKIND = { icu: 'Terapia intensiva', ward: 'Reparto', defib: 'Defibrillatore', aed: 'DAE' };
    var lays = s.layouts || [], laySig = lays.map(function (l) { return l.id + '=' + l.name + '=' + (l.category || ''); }).join('|');
    if ($('layoutSeg').dataset.sig !== laySig) {
      $('layoutSeg').dataset.sig = laySig;
      $('layoutSeg').innerHTML = [['default', 'Default'], ['custom', 'Personalizzati']].map(function (c) {
        var g = lays.filter(function (l) { return (l.category === 'custom' ? 'custom' : 'default') === c[0]; });
        if (!g.length && c[0] === 'default') return '';
        return '<div class="laycat" data-cat="' + c[0] + '"><h5>' + c[1] + '</h5>' + (g.length
          // ANDROID (miniature dei layout 23/11): tessera con la miniatura VERA (thumbs/<id>.png dal monitor), nome e tipo sotto;
          // se la miniatura manca (build pubblica, layout nuovo) l'immagine si toglie e resta la tessera di solo testo
          ? '<div class="seg big laythumbs">' + g.map(function (l) {
              return '<button data-v="' + esc(l.id) + '"><img class="laythumb" alt="" src="thumbs/' + encodeURIComponent(l.id) + '.png" onerror="this.remove()">' +
                '<span class="layname">' + esc(l.name) + '</span><small>' + esc(LAYKIND[l.kind] || 'Monitor') + '<b class="layuse">● In uso</b></small></button>';
            }).join('') + '</div>'
          : '<p class="hint laycat-empty">Nessun layout personalizzato: vedi la guida per crearne uno</p>') + '</div>';
      }).join('');
        }
    all('#layoutSeg button').forEach(function (b) { b.className = b.dataset.v === (s.layout || 'generic_icu') ? 'active' : ''; });
    if (!editing.volume) { $('volume').value = s.volume_percent; $('o_volume').textContent = s.volume_percent; }
    var b = $('beep'); b.className = 'toggle ' + (s.qrs_beep_enabled ? 'on' : 'off'); b.textContent = s.qrs_beep_enabled ? 'Bip attivo' : 'Bip disattivato';
    all('.autoPhysio').forEach(function (ap) {
      ap.className = 'toggle wide autoPhysio ' + (s.auto_physiology ? 'on' : 'off');
      ap.textContent = s.auto_physiology ? 'Fisiologia automatica: ON' : 'Fisiologia automatica: OFF';
    });
    // azioni rapide
    all('#motionSeg button').forEach(function (bt) {
      bt.className = bt.dataset.v === (s.patient_motion || 'off') ? 'active' : '';
      bt.disabled = bt.dataset.v !== 'off' && !s.pulse_present;
    });
    $('touch').className = 'act touch' + (s.operator_touch ? ' on' : '');
    $('motionLeft').textContent = s.patient_motion && s.patient_motion !== 'off' && s.patient_motion_remaining_s > 0 ? s.patient_motion_remaining_s + ' s' : '';
    // ANDROID (affordance 22/11, W-09): da silenziato il tasto dice lo STATO (ambra pieno, «SILENZIATI 18 s · riattiva»)
    $('mute').textContent = s.alarms_muted ? '🔕 SILENZIATI ' + (s.alarms_muted_remaining_s || 0) + ' s · riattiva' : 'Silenzia 20 s';
    $('mute').classList.toggle('muted', !!s.alarms_muted);
    document.body.classList.toggle('muted', !!s.alarms_muted);
    // ANDROID (avvio scenario 22/11): PRONTO = «▶ Avvia» (verde pieno), in corso «II Pausa», in pausa «▶ Riprendi» (arancione pieno)
    var ph = scenPhase(s), pb = $('pause');
    pb.textContent = ph === 'ready' ? '▶ Avvia' : (s.simulation_running ? 'II Pausa' : '▶ Riprendi');
    pb.classList.toggle('start', ph === 'ready'); pb.classList.toggle('resume', ph !== 'ready' && !s.simulation_running);
    pb.title = ph === 'ready' ? 'Avvia lo scenario: parte il timer, parte il registro della sessione e la simulazione è in corso. Finché non lo avvii il monitor funziona per familiarizzare, ma niente viene registrato.' : '';
    $('endScen').disabled = ph === 'ready';               // in PRONTO non c'è niente da terminare
    if (!limitsDirty && s.alarm_limits) LIMITS.forEach(function (k) {
      if (document.activeElement !== $('l_' + k)) $('l_' + k).value = s.alarm_limits[k];
    });
    if (hoverSite && !$('tip').hidden) $('tip').innerHTML = hotInfo(hoverSite);
    warnTiles(s); renderClock(s);
    renderCtls(s); renderRhythms(); renderOnset(s); renderEcgm(s); renderPending(); renderDefib(s); renderChips(s); renderFigure(s);   // ANDROID (regia morfologie ECG 22/11): + renderEcgm
  }

  // =====================================================================
  // costruzione e collegamenti
  // =====================================================================
  $('figure').innerHTML = buildFigure();
  wireFigure();
  requestAnimationFrame(breathLoop);
  all('.ctl[data-p]').forEach(buildCtl);
  all('.rhythmbox').forEach(function (box) {
    box.innerHTML = '<div><div class="rlab">Sinusali e sopraventricolari</div><div class="rhythms"></div></div>' +
                    '<div><div class="rlab">Ventricolari e arresto</div><div class="rhythms danger"></div></div>';
    var grp = box.querySelectorAll('.rhythms');
    Object.keys(RHYTHMS).forEach(function (r) {
      var bt = document.createElement('button'); bt.textContent = RHYTHMS[r]; bt.dataset.r = r;
      bt.onclick = function () { changeRhythm(r); };
      grp[ARREST.indexOf(r) >= 0 ? 1 : 0].appendChild(bt);
    });
  });
  // ANDROID (insorgenza e ampiezza FV 22/11)
  all('#onsetSeg button').forEach(function (b) {
    b.onclick = function () {
      onsetMode = b.dataset.v === 'immediate' ? 'immediate' : 'auto';
      try { sessionStorage.setItem('sf_onset', onsetMode); } catch (e) {}
      msg(onsetMode === 'auto' ? 'Insorgenza realistica: i prossimi cambi di ritmo arrivano con il preludio fisiologico'
                               : 'Insorgenza di colpo: i prossimi cambi di ritmo partono subito, senza preludio');
      if (state) renderOnset(state);
    };
  });
  $('onsetSkip').onclick = onsetSkip;
  all('#vfSeg button').forEach(function (b) { b.onclick = function () { vfSet(b.dataset.v); }; });
  $('vfRange').addEventListener('input', function () {
    var v = parseFloat($('vfRange').value); editing.vf = true;
    $('vfVal').textContent = mvTxt(v) + ' mV · ' + VFCLS[vfClassOf(v)] + ' · lascia il cursore per applicare'; $('vfWarn').hidden = v >= 0.1;
  });
  // ANDROID (righello a zone 22/11): ampiezza della FV in mV sul righello a zone (sotto 0,1 mV il DAE non consiglia la scarica)
  (function () { var r = $('vfRange'), R = makeRuler(r, 'vf_mv', '#33ff59'); r.classList.add('rhid'); r.parentNode.insertBefore(R.cv, r.nextSibling); })();
  $('vfRange').addEventListener('change', function () {
    var v = Math.round(parseFloat($('vfRange').value) * 100) / 100;
    tapAt.vf = 0;                                     // il cursore non è un doppio clic
    vfSet(v); setTimeout(function () { editing.vf = false; }, 600);
  });
  // ANDROID (righello a zone 22/11): le coppie della PA (Shock 70/40 …) sono i tasti rapidi della sistolica (buildCtl)
  $('chips').addEventListener('click', function (e) {           // chip: applica, mostra i controlli, o toglie (×)
    var c = e.target.closest('.chip'); if (!c) return;
    var k = c.dataset.k;
    if (e.target.closest('.yes')) { chipAsk = null; detach(k); return; }
    if (e.target.closest('.no')) { chipAsk = null; if (state) renderChips(state); return; }
    if (e.target.closest('.x')) {
      if (scenPhase(state) === 'ready') { detach(k); return; }
      chipAsk = k; clearTimeout(chipAskT); chipAskT = setTimeout(function () { chipAsk = null; if (state) renderChips(state); }, 5000);
      if (state) renderChips(state); return;
    }
    if (e.target.closest('.ask')) return;
    if (on(k)) select(k); else place(k, SENS[k].def);
  });
  all('.tile[data-card]').forEach(function (t) {
    t.onclick = function () { showCard(t.dataset.card); flashCard(t.dataset.card); };
  });
  all('#pnav button').forEach(function (bt) { bt.onclick = function () { showCard(bt.dataset.card); }; });

  $('spo2Delay').innerHTML = [0, 3, 5, 8, 10, 15, 20, 30, 45, 60, 90].map(function (v) {
    return '<option value="' + v + '">' + (v ? v + ' s' : 'nessuno') + '</option>'; }).join('');
  $('spo2Delay').onchange = function () { command('set_spo2_delay', parseInt($('spo2Delay').value, 10)); };
  $('signalSw').querySelector('input').onchange = function (e) { command('set_spo2_signal_valid', e.target.checked); };
  $('lowPerfSw').querySelector('input').onchange = function (e) { command('set_spo2_low_perfusion', e.target.checked); };

  DCFG_RHY.forEach(function (k) {
    $('c_' + k).innerHTML = Object.keys(RHYTHMS).map(function (r) { return '<option value="' + r + '">' + RHYTHMS[r] + '</option>'; }).join('');
  });
  DCFG_NUM.concat(DCFG_BOOL, DCFG_RHY).forEach(function (k) {
    var mark = function () { dcfgDirty = true; $('defibCfgApply').classList.add('dirty'); $('defibCfgApply').textContent = 'Applica impostazioni (modificate)'; };
    $('c_' + k).addEventListener('input', mark); $('c_' + k).addEventListener('change', mark);
  });
  $('defibCfgApply').onclick = function () {
    var v = {};
    DCFG_NUM.forEach(function (k) { v[k] = parseInt($('c_' + k).value, 10); });
    DCFG_BOOL.forEach(function (k) { v[k] = $('c_' + k).checked; });
    DCFG_RHY.forEach(function (k) { v[k] = $('c_' + k).value; });
    command('set_defib_config', v).then(function (ok) {
      if (!ok) return;                                  // ANDROID (fix analisi 22/11, BUG-07): rifiutate, restano «modificate»
      dcfgDirty = false; $('defibCfgApply').classList.remove('dirty'); $('defibCfgApply').textContent = 'Applica impostazioni';
    });
  };
  $('d_mode').onchange = function () { command('defib_mode', $('d_mode').value); };
  // ANDROID (fix analisi 22/11, BUG-08): i pulsanti dei layout si ricostruiscono dall'elenco del monitor (innerHTML) e avevano
  // perso il clic: un solo gestore sul contenitore, che vale anche per i pulsanti ricostruiti
  $('layoutSeg').addEventListener('click', function (e) {
    var b = e.target.closest('button[data-v]'); if (!b) return;
    var v = b.dataset.v, nm = b.textContent;
    if (state && state.layout === v) return;
    command('set_layout', v).then(function (ok) { if (ok) msg('Layout del monitor: ' + nm); });
  });
  all('#d_outcome button').forEach(function (bt) { bt.onclick = function () { command('set_defib_config', { outcome_mode: bt.dataset.v }); }; });
  $('d_dec_other').innerHTML = Object.keys(RHYTHMS).map(function (r) { return '<option value="' + r + '">' + RHYTHMS[r] + '</option>'; }).join('');
  // ANDROID (fix analisi 22/11, BUG-09): UN solo esito per scarica, da qualunque tasto (anche «Altro ritmo… Applica»). Dopo il
  // primo i tasti restano bloccati finché il monitor non toglie la domanda o non chiede l'esito di un'altra scarica; se il
  // monitor rifiuta l'esito si sbloccano per riprovare. Prima «Altro ritmo» e «Resta» con la rete lenta partivano tutti e due.
  var ocSent = null, ocShown = 0, ocRenewedFrom = 0;
  function ocKey(d) { return d && d.outcome_pending ? d.outcome_shock + '|' + d.outcome_from : null; }
  function renderOutcomeLock(d) {
    if (ocSent !== null && ocSent !== ocKey(d)) ocSent = null;
    var lock = ocSent !== null;
    ['d_dec_sinus', 'd_dec_stay', 'd_dec_other', 'd_dec_other_go'].forEach(function (id) { $(id).disabled = lock; });
  }
  function sendOutcome(v) {
    var d = state && state.defib, k = ocKey(d);
    if (k === null || ocSent !== null) return;
    ocSent = k; renderOutcomeLock(d);
    command('defib_outcome', v, { shock: d.outcome_shock }).then(function (ok) { if (!ok && ocSent === k) { ocSent = null; renderOutcomeLock(state && state.defib); } });
  }
  $('d_dec_sinus').onclick = function () { sendOutcome('sinus'); };
  $('d_dec_stay').onclick = function () { sendOutcome('stay'); };
  $('d_dec_other_go').onclick = function () { sendOutcome($('d_dec_other').value); };

  $('touch').onclick = function () { command('patient_touch', 3).then(function (ok) { if (ok) msg('Tocco operatore: 3 s di artefatto sul monitor'); }); };
  all('#motionSeg button').forEach(function (bt) {
    bt.onclick = function () {
      var d = parseInt($('motionDur').value, 10) || 0;
      command('set_patient_motion', bt.dataset.v === 'off' ? 'off' : { level: bt.dataset.v, duration_s: d });
    };
  });
  all('.crtAssess').forEach(function (b) { b.onclick = function () { command('assess_crt').then(function (ok) { if (ok) msg('Refill: valutazione in corso sul monitor'); }); }; });
  all('.autoPhysio').forEach(function (ap) { ap.onclick = function () { command('set_auto_physiology', !state.auto_physiology); }; });
  all('#modes .mode').forEach(function (bt) { bt.onclick = function () { $('customT').value = ''; setMode(parseInt(bt.dataset.t, 10)); }; });
  $('customT').addEventListener('change', function () { var t = parseInt($('customT').value, 10); if (t > 0 && t <= 3600) setMode(t); });
  $('go').onclick = applyNow;
  $('pendCancel').onclick = clearPending;
  $('prepGo').onclick = applyNow; $('prepDrop').onclick = clearPending;          // ANDROID (affordance 22/11, W-01)
  // ANDROID (affordance 22/11, W-15): scorciatoie da tastiera FACOLTATIVE (spente di serie, scelta salvata in questo browser).
  // Mai in sola osservazione, mai mentre si scrive in un campo o con una finestra/pannello che ha il fuoco.
  var kbOn = lsGet('sf_kb', false);
  $('kbOn').checked = !!kbOn;
  $('kbOn').onchange = function () { kbOn = $('kbOn').checked; lsSet('sf_kb', kbOn); msg(kbOn ? 'Scorciatoie da tastiera attive: P R V N S A' : 'Scorciatoie da tastiera spente'); };
  document.addEventListener('keydown', function (e) {
    if (!kbOn || e.ctrlKey || e.metaKey || e.altKey || e.repeat || isObserver() || $('console').hidden) return;
    var t = e.target, tag = t && t.tagName;
    if (tag === 'INPUT' || tag === 'TEXTAREA' || tag === 'SELECT' || (t && t.isContentEditable)) return;
    if (!$('sfDlg').hidden || !$('debrief').hidden || !$('scBuilder').hidden || !$('storia').hidden) return;
    var map = { p: 'pause', r: 'cpr', v: 'vent', n: 'nibpStart', s: 'mute' }, k = (e.key || '').toLowerCase();
    if (k === 'a') { if (isPending()) { e.preventDefault(); applyNow(); } return; }
    var b = map[k] && $(map[k]);
    if (b && !b.disabled) { e.preventDefault(); b.click(); }
  });
  $('prepNow').onclick = function () { $('customT').value = ''; setMode(0); };
  $('trCancel').onclick = function () { command('cancel_transitions'); };
  $('volume').addEventListener('input', function () { editing.volume = true; $('o_volume').textContent = $('volume').value; });
  $('volume').addEventListener('change', function () { command('set_volume', parseInt($('volume').value, 10)).then(function () { editing.volume = false; }); });
  LIMITS.forEach(function (k) { $('l_' + k).addEventListener('input', function () { limitsDirty = true; }); });
  // ANDROID (fix analisi 22/11, BUG-07): un campo vuoto o non numerico si dice subito (prima partiva null → 400 invisibile e il
  // poll riscriveva i campi); i campi tornano quelli del monitor solo se il monitor ha accettato i limiti
  var LIMIT_NAMES = { hr_low: 'FC minima', hr_high: 'FC massima', spo2_low: 'SpO₂ minima', sys_low: 'PA sistolica minima', sys_high: 'PA sistolica massima',
                      rr_low: 'FR minima', rr_high: 'FR massima', etco2_low: 'EtCO₂ minima', etco2_high: 'EtCO₂ massima' };
  $('limitsApply').onclick = function () {
    var v = {}, bad = [];
    LIMITS.forEach(function (k) { v[k] = parseInt($('l_' + k).value, 10); if (isNaN(v[k])) bad.push(LIMIT_NAMES[k] || k); });
    if (bad.length) { msg('Limiti non applicati: ' + bad.join(', ') + (bad.length === 1 ? ' è vuoto o non è un numero' : ' sono vuoti o non numerici'), true); return; }
    command('set_alarm_limits', v).then(function (ok) { if (ok) limitsDirty = false; });
  };
  $('limitsReset').onclick = function () { limitsDirty = false; command('reset_alarm_limits'); };
  ['hr', 'spo2', 'art', 'nibp', 'rr', 'etco2'].forEach(function (k) {
    var c = $('ae_' + k); if (c) c.onchange = function () { command('set_alarm_enabled', { param: k, enabled: c.checked }); };
  });
  $('beep').onclick = function () { command('set_qrs_beep', !state.qrs_beep_enabled); };
  // ANDROID (fix analisi 22/11, BUG-07): le voci del saturimetro nel registro solo a comando eseguito
  $('oxiPower').onclick = function () { if (!state || !state.oximeter) return; var on_ = !state.oximeter.power; command('set_oximeter', { power: on_ }).then(function (ok) { if (ok) logAdd('Saturimetro ' + (on_ ? 'acceso' : 'spento') + ' dal controller', 'monitor'); }); };
  $('oxiSensor').onclick = function () { if (!state) return; if (on('oxi')) detach('oxi'); else place('oxi', SENS.oxi.def); };
  $('oxiMute').onclick = function () { command('set_oximeter', { mute_s: 120 }).then(function (ok) { if (ok) logAdd('Saturimetro: allarmi silenziati 2 min (controller)', 'monitor'); }); };
  OXI_L.forEach(function (k) { $('ol_' + k).addEventListener('input', function () { oxiLimitsDirty = true; }); });
  $('oxiLimitsApply').onclick = function () {
    var lim = {}; OXI_L.forEach(function (k) { var v = parseInt($('ol_' + k).value, 10); if (!isNaN(v)) lim[k] = v; });
    command('set_oximeter', { alarm_limits: lim }).then(function (ok) { if (!ok) return; oxiLimitsDirty = false; logAdd('Saturimetro: limiti di allarme cambiati', 'monitor'); });
  };
  $('oxiLimitsReset').onclick = function () { command('set_oximeter', { alarm_limits: { spo2_low: 85, spo2_high: 100, pr_low: 30, pr_high: 170 } }).then(function (ok) { if (ok) oxiLimitsDirty = false; }); };
  $('oxiVolume').addEventListener('input', function () { editing.oxiVolume = true; $('o_oxiVolume').textContent = $('oxiVolume').value; });
  $('oxiVolume').addEventListener('change', function () { command('set_oximeter', { volume: parseInt($('oxiVolume').value, 10) }).then(function () { editing.oxiVolume = false; }); });
  $('mute').onclick = function () { command('mute_alarms', !state.alarms_muted); };
  $('pause').onclick = function () {
    if (!tapOnce('pause', 900)) return;     // ANDROID (fix analisi 22/11, BUG-02): doppio clic = pausa e subito ripresa
    // ANDROID (debriefing offline web 22/11): con un azzeramento in sospeso «Avvia» lo manda PRIMA (se vale ancora), poi parte
    if (lsGet(RESET_KEY, null)) { resetPendingRun(null, function (ok) { if (ok) pauseGo(); }); return; }
    pauseGo();
  };
  function pauseGo() {
    if (!state) return;
    // ANDROID (avvio scenario 22/11): in PRONTO il tasto avvia lo scenario (start_scenario); un monitor vecchio non lo
    // conosce: lì vale il «riprendi» di prima
    if (scenPhase(state) === 'ready') {
      cmdSeq++;
      var body = { command: 'start_scenario' }; if (state) body.device_id = state.device_id;
      api('POST', '/api/v1/command', body).then(function (j) {
        if (j && !j.ok && /unknown command/i.test(j.error || '')) command('set_simulation_running', true);
        else { handle(j); if (j && j.ok) msg('Scenario avviato: timer e registro partono adesso'); }
      });
      return;
    }
    command('set_simulation_running', !state.simulation_running);
  }
  $('endScen').onclick = endTapped;
  var resetArm = 0, resetT = null;
  function resetScenarioNow() {                      // anche dopo «Salva in Storia Sim» / «Non salvare»
    clearTimeout(resetT); resetDisarm(); clearPending();
    return command('reset_scenario').then(function (ok) {      // ANDROID (fix analisi 22/11, BUG-07): esito ai chiamanti
      if (!ok) return false;
      lsSet(RESET_KEY, null); offRender();       // ANDROID (debriefing offline web 22/11): niente più azzeramento in sospeso
      lastState = null; sessionStorage.removeItem('sf_last_state');
      renderStates();
      return true;
    });
  }
  function resetDisarm() { resetArm = 0; $('reset').textContent = 'Azzera scenario…'; $('reset').classList.remove('armed'); }
  // ANDROID (affordance 22/11, W-03): «Azzera scenario» con la stessa finestra di conferma di FINE SCENARIO (prima bastavano due clic
  // in 4 s: un doppio clic involontario azzerava tutto); «Annulla» è la scelta predefinita
  $('reset').onclick = function () {
    if (!tapOnce('reset', 400)) return;
    var n = Object.keys(pending).length + (pendingRhythm ? 1 : 0);
    dialog('Azzerare lo scenario?', 'Parametri, sonde, contatori e orologio tornano all\'inizio e il monitor si spegne. Gli scenari salvati restano.' +
      (n ? '\n\nAttenzione: ' + (n === 1 ? '1 modifica in attesa verrà scartata.' : n + ' modifiche in attesa verranno scartate.') : ''),
      [{ t: 'Annulla', cls: 'ghost' }, { t: 'Azzera', cls: 'danger strong', fn: function () { resetScenarioNow().then(function (ok) { if (ok) msg('Eseguito dal monitor: scenario azzerato'); }); } }]);
  };
  // ANDROID (affordance 22/11, W-04): la misura NIBP non cambia subito niente sullo schermo: la conferma si scrive sempre
  var nibpGo = function () { api('POST', '/api/v1/nibp/start', {}).then(function (j) { handle(j); if (j && j.ok) msg('Misura NIBP partita sul monitor'); }); };
  $('nibpStart').onclick = nibpGo; $('nibpStart2').onclick = nibpGo;
  // ---------- ANDROID (pannello RCP 22/11): «Come vedi l'RCP?» ----------
  // Richiesta dell'utente (come sul tablet controller): «▶ RCP» fa partire SUBITO la RCP (set_cpr true) e apre il pannello con
  // tutto «corretto» (di base l'RCP è fatta bene). Con la RCP in corso lo stesso pulsante NON la ferma: riapre il pannello; si
  // ferma solo con «Interrompi RCP» in fondo al pannello (mai per sbaglio). Il pannello non è modale (Chiudi / Esc) e mostra
  // sempre i valori VERI dello stato del monitor (cpr_rate_cpm, cpr_*_quality, cpr_fatigue, etco2_cpr_mode), non quelli scelti.
  // Comandi del cuore: set_cpr_quality {rate_cpm 40-200, depth ok/shallow/deep, recoil ok/incomplete, hands ok/wrong} (riporta
  // anche l'EtCO2 al modello se l'istruttore l'aveva impostata a mano), cpr_rescuer_change (finisce la stanchezza).
  // In PRONTO la RCP si può provare come prima (il pulsante RCP non era bloccato prima di «Avvia»). Sola osservazione:
  // il pulsante apre solo il pannello (nessun comando), i controlli del pannello sono spenti (CSS body.observer + controllo qui).
  var RCP = { open: false, tap: [], rateSent: null, rateAt: 0, rateT: null, rateWant: null, slideT: null, slideUntil: 0 };
  var RCP_RATE_GAP_MS = 1200;      // al massimo un set_cpr_quality di frequenza ogni 1,2 s (tap-tempo e cursore): niente raffiche
  function rcpObserver() { return document.body.classList.contains('observer'); }
  function rcpRateCat(r) { return r < 100 ? 'lenta' : r > 120 ? 'veloce' : 'corretta'; }
  function rcpOpen() {
    if (!RCP.open) { RCP.tap = []; $('rcpTapT').textContent = 'Tocca a ogni compressione che vedi (anche barra spaziatrice).'; }
    RCP.open = true; $('rcp').hidden = false; rcpRender(state);
  }
  function rcpClose() { RCP.open = false; $('rcp').hidden = true; RCP.tap = []; }
  // effetto atteso in parole semplici: stessi fattori del modello del cuore (SimulationState co2AutoTarget / artCprPeak /
  // artCprRelax), senza adrenalina e iperventilazione (le fa il monitor): è una stima, i numeri veri sono nelle tessere
  function rcpRateF(r, lo, hi, mn) { var f = r < 100 ? 1 - lo * (100 - r) : r > 120 ? 1 - hi * (r - 120) : 1; return Math.max(mn, f); }
  function rcpEffect(s) {
    if (s.pulse_present) return 'Paziente con polso: le compressioni non cambiano EtCO₂ e arteriosa (il modello vale in arresto).';
    if (!s.cpr_active) return '<b class="low">RCP ferma:</b> l\'EtCO₂ crolla in pochi secondi, l\'arteriosa resta piatta (~15).';
    var r = s.cpr_rate_cpm || 110, dq = s.cpr_depth_quality, sh = dq === 'shallow', dp = dq === 'deep',
        hw = s.cpr_hands_quality === 'wrong', ri = s.cpr_recoil_quality === 'incomplete', fat = 1 - 0.2 * (s.cpr_fatigue || 0);
    var co2 = Math.max(3, 19 * (sh ? 0.55 : dp ? 0.92 : 1) * rcpRateF(r, 0.0233, 0.009, 0.30) * (hw ? 0.75 : 1) * (ri ? 0.85 : 1) * fat);
    var pk = 80 * (sh ? 0.56 : dp ? 1.15 : 1) * rcpRateF(r, 0.004, 0.0075, 0.55) * (hw ? 0.78 : 1) * (ri ? 0.88 : 1) * fat;
    var dia = 28 * (sh ? 0.60 : dp ? 1.04 : 1) * rcpRateF(r, 0.0187, 0.005, 0.40) * (hw ? 0.82 : 1) * fat + (ri ? 4 : 0);
    dia = Math.max(17, dia);
    var man = s.etco2_cpr_mode === 'instructor', auto = s.etco2_cpr_mode === 'auto';
    var co2T = man ? s.etco2_mmhg + ' (a mano)' : auto ? '~' + Math.round(co2) : s.etco2_mmhg + ' (valore impostato: fisiologia automatica spenta)';
    var effCo2 = man || !auto ? 99 : co2;
    var good = effCo2 >= 15 && dia >= 25, poor = (auto && co2 < 12) || dia < 20;
    var lab = good ? '<b>RCP efficace:</b>' : poor ? '<b class="low">RCP poco efficace:</b>' : '<b class="mid">RCP migliorabile:</b>';
    return lab + ' EtCO₂ ' + co2T + ', arteriosa ~' + Math.round(pk) + '/' + Math.round(dia) + (ri && !sh ? ' (diastolica falsamente alta: appoggio)' : '') +
      (dia < 25 ? ' · diastolica sotto 25' : '') + '.';
  }
  function rcpSeg(id, v, okV) {
    all('#' + id + ' button').forEach(function (b) {
      b.className = b.dataset.v === v ? 'active' + (v !== okV ? ' bad' : '') : '';
    });
  }
  function rcpRender(s) {
    if (!RCP.open || !s) return;
    var obs = rcpObserver(), on = !!s.cpr_active;
    $('rcpObs').hidden = !obs;
    var st = $('rcpState'); st.textContent = on ? '● RCP in corso' : 'RCP ferma'; st.className = 'rcpstate' + (on ? ' on' : '');
    var r = s.cpr_rate_cpm || 110, sliding = Date.now() < RCP.slideUntil;
    var shown = sliding ? parseInt($('rcpRate').value, 10) : r;
    if (!sliding) $('rcpRate').value = String(Math.max(60, Math.min(160, r)));
    var rt = $('rcpRateT'); rt.textContent = shown + '/min · ' + rcpRateCat(shown); rt.className = 'rcprate ' + (rcpRateCat(shown) === 'corretta' ? 'ok' : 'bad');
    rcpSeg('rcpDepth', s.cpr_depth_quality || 'ok', 'ok');
    rcpSeg('rcpRecoil', s.cpr_recoil_quality || 'ok', 'ok');
    rcpSeg('rcpHands', s.cpr_hands_quality || 'ok', 'ok');
    var f = Math.max(0, Math.min(1, s.cpr_fatigue || 0)), fb = $('rcpFatBar');
    fb.style.width = Math.round(f * 100) + '%'; fb.className = f > 0.8 ? 'hi' : f > 0.5 ? 'mid' : '';
    var ft = $('rcpFatT'); ft.textContent = Math.round(f * 100) + ' %' + (f >= 0.99 ? ' · Cambio consigliato (2 minuti)' : f > 0.8 ? ' · cambio fra poco' : f > 0.5 ? ' · comincia a stancarsi' : '');
    ft.className = f > 0.8 ? 'hi' : '';
    $('rcpSwap').className = f >= 0.99 ? 'advise' : '';
    $('rcpEff').innerHTML = rcpEffect(s);
    $('rcpMan').hidden = s.etco2_cpr_mode !== 'instructor' || !!s.pulse_present;
    var sb = $('rcpStop'); sb.textContent = on ? 'Interrompi RCP' : '▶ Riprendi la RCP'; sb.className = 'rcpstop' + (on ? '' : ' go');
  }
  // frequenza: un solo invio alla volta, al massimo ogni RCP_RATE_GAP_MS; l'ultimo valore voluto vince (dal cursore o dal tempo)
  function rcpSendRate(v) {
    RCP.rateWant = Math.max(40, Math.min(200, Math.round(v)));
    if (RCP.rateT) return;
    var wait = Math.max(0, RCP.rateAt + RCP_RATE_GAP_MS - Date.now());
    RCP.rateT = setTimeout(function () {
      RCP.rateT = null;
      var w = RCP.rateWant;
      if (w === null || (state && state.cpr_rate_cpm === w)) return;
      RCP.rateAt = Date.now(); RCP.rateWant = null;
      command('set_cpr_quality', { rate_cpm: w });
    }, wait);
  }
  function rcpQuality(key, v) {
    if (rcpObserver()) return;
    if (!tapOnce('rcpq_' + key, 500)) return;
    var o = {}; o[key] = v; command('set_cpr_quality', o);
  }
  // «Batti il tempo»: un tocco per compressione vista; dopo 5 tocchi (4 intervalli) media degli ultimi 4-6 intervalli.
  // Un intervallo > 2 s ricomincia il conteggio. Se si smette di toccare resta l'ultima frequenza impostata.
  function rcpTapHit() {
    if (!RCP.open || rcpObserver()) return;
    var now = (window.performance && performance.now) ? performance.now() : Date.now();
    var t = RCP.tap;
    if (t.length && now - t[t.length - 1] > 2000) t.length = 0;
    t.push(now); if (t.length > 7) t.shift();
    var b = $('rcpTap'); b.classList.add('hit'); setTimeout(function () { b.classList.remove('hit'); }, 90);
    if (t.length < 5) { $('rcpTapT').textContent = 'Tocchi: ' + t.length + ' — continua (' + (5 - t.length) + ' ancora)…'; return; }
    var n = Math.min(6, t.length - 1), sum = t[t.length - 1] - t[t.length - 1 - n];
    var cpm = Math.round(60000 / (sum / n));
    $('rcpTapT').textContent = 'Dal tempo battuto: ' + cpm + '/min · ' + rcpRateCat(cpm) + ' (media degli ultimi ' + n + ' intervalli)';
    RCP.slideUntil = 0;
    rcpSendRate(cpm);
  }
  $('cpr').onclick = function () {
    // ANDROID (fix analisi 22/11, BUG-02): un tocco ogni 900 ms (il doppio clic non fa due cose)
    if (!tapOnce('cpr', 900)) return;
    if (!state) return;
    if (state.cpr_active || rcpObserver()) { rcpOpen(); return; }        // in corso (o solo osservazione): NON ferma, riapre
    rcpStart();
  };
  function rcpStart() {
    // tutto «corretto» se non lo è già (un solo comando in più, solo se serve: non cancella per niente l'EtCO2 a mano)
    var s = state, wrong = s.cpr_rate_quality !== 'ok' || s.cpr_depth_quality !== 'ok' || s.cpr_recoil_quality !== 'ok' || s.cpr_hands_quality !== 'ok';
    rcpOpen();
    command('set_cpr', true).then(function (ok) {
      if (ok && wrong) command('set_cpr_quality', { rate_cpm: 110, depth: 'ok', recoil: 'ok', hands: 'ok' });
    });
  }
  function initRcp() {
    $('rcpClose').onclick = rcpClose;
    $('rcpStop').onclick = function () {
      if (rcpObserver() || !state || !tapOnce('rcpStop', 900)) return;
      if (!state.cpr_active) { rcpStart(); return; }
      command('set_cpr', false).then(function (ok) { if (ok) { msg('RCP interrotta'); rcpClose(); } });
    };
    $('rcpSwap').onclick = function () {
      if (rcpObserver() || !tapOnce('rcpSwap', 900)) return;
      command('cpr_rescuer_change').then(function (ok) { if (ok) msg('Cambio dell\'operatore alle compressioni'); });
    };
    $('rcpModel').onclick = function () {
      // nel cuore si torna al modello con set_cpr_quality: si rimandano le qualità VERE di adesso (non cambia nient'altro)
      if (rcpObserver() || !state || !tapOnce('rcpModel', 900)) return;
      command('set_cpr_quality', { rate_cpm: state.cpr_rate_cpm || 110, depth: state.cpr_depth_quality || 'ok',
                                   recoil: state.cpr_recoil_quality || 'ok', hands: state.cpr_hands_quality || 'ok' })
        .then(function (ok) { if (ok) msg('EtCO₂ di nuovo dal modello della RCP'); });
    };
    [['rcpDepth', 'depth'], ['rcpRecoil', 'recoil'], ['rcpHands', 'hands']].forEach(function (p) {
      all('#' + p[0] + ' button').forEach(function (b) { b.onclick = function () { rcpQuality(p[1], b.dataset.v); }; });
    });
    var sl = $('rcpRate');
    // ANDROID (righello a zone 22/11): righello a zone (Lenta · Corretta 100–120 · Veloce) al posto del cursore con la pista verde
    var rcpR = makeRuler(sl, 'cpr_rate', '#30d158'); sl.classList.add('rhid'); sl.parentNode.insertBefore(rcpR.cv, sl.nextSibling);
    sl.addEventListener('input', function () {
      if (rcpObserver()) return;
      var v = parseInt(sl.value, 10);
      RCP.slideUntil = Date.now() + 2500;           // mentre si trascina (e poco dopo) il numero mostra il cursore, poi lo stato vero
      var rt = $('rcpRateT'); rt.textContent = v + '/min · ' + rcpRateCat(v); rt.className = 'rcprate ' + (rcpRateCat(v) === 'corretta' ? 'ok' : 'bad');
      clearTimeout(RCP.slideT);
      RCP.slideT = setTimeout(function () { rcpSendRate(v); }, 350);         // debounce: si manda quando ci si ferma
    });
    $('rcpTap').addEventListener('pointerdown', function (e) { e.preventDefault(); rcpTapHit(); });
    $('rcpTap').addEventListener('click', function (e) { e.preventDefault(); });
    // tastiera col pannello aperto: Esc chiude il pannello (prima degli altri pannelli: ascolto in cattura), barra spaziatrice
    // = un tocco del tempo (non mentre si scrive in un campo; i tasti ripetuti tenendo premuto non contano)
    document.addEventListener('keydown', function (e) {
      if (!RCP.open || !$('sfDlg').hidden) return;
      var tg = e.target, typing = tg && (/^(INPUT|TEXTAREA|SELECT)$/.test(tg.tagName) && tg.type !== 'range' || tg.isContentEditable);
      if (e.key === 'Escape') {
        if (!$('debrief').hidden || !$('storia').hidden) return;
        e.stopPropagation(); rcpClose(); return;
      }
      if ((e.key === ' ' || e.code === 'Space') && !typing) {
        e.preventDefault(); e.stopPropagation();
        if (!e.repeat) rcpTapHit();
      }
    }, true);
    document.addEventListener('keyup', function (e) {
      if (RCP.open && (e.key === ' ' || e.code === 'Space') && e.target && e.target.tagName === 'BUTTON') e.preventDefault();
    }, true);
  }
  initRcp();
  $('vent').onclick = function () { command('ventilate'); };      // una insufflazione dei partecipanti = un'onda di CO2
  $('nibpInterval').onchange = function () { command('set_nibp_interval', parseInt($('nibpInterval').value, 10)); };
  $('logout').onclick = function () {
    if (ctlMine && token) api('POST', '/api/v1/control', { action: 'release' }).catch(function () {});   // lascia libero il comando
    token = ''; sessionStorage.removeItem('sf_token'); showLogin('');
  };
  $('takeCtl').onclick = takeControl;
  var clockArm = 0;                                   // orologio di scenario: azzeramento a doppio tocco
  $('clockReset').onclick = function () {
    var b = $('clockReset');
    if (clockArm && Date.now() - clockArm < 4000) { clockArm = 0; b.textContent = 'Azzera'; b.classList.remove('armed'); command('reset_clock'); return; }
    clockArm = Date.now(); b.textContent = 'Tocca di nuovo'; b.classList.add('armed');
    setTimeout(function () { if (clockArm) { clockArm = 0; b.textContent = 'Azzera'; b.classList.remove('armed'); } }, 4000);
  };
  $('loginForm').addEventListener('submit', function (e) {
    e.preventDefault();
    token = $('pin').value.trim();
    api('GET', '/api/v1/state').then(function (j) {
      if (j.ok) { sessionStorage.setItem('sf_token', token); $('pin').value = ''; showConsole(); render(j.state); }
      else handle(j);
    }).catch(function () { $('loginErr').textContent = 'monitor non raggiungibile'; });
  });

  // stati, interventi e registro
  renderStates();
  initScen();
  $('stateChips').addEventListener('click', function (e) {
    var b = e.target.closest('.stchip'); if (!b) return;
    var i = parseInt(b.dataset.i, 10);
    savingState = false; selState = selState === i ? null : i;
    renderStates();
    if (selState !== null) $('stateExp').scrollIntoView({ block: 'nearest', behavior: 'smooth' });
  });
  $('saveState').onclick = saveStateUI;
  initLog();
  // figura in miniatura <-> espansa (per piazzare le sonde con precisione)
  function figOpen() { document.body.classList.add('figbig'); $('figBack').hidden = false; $('figClose').hidden = false; }
  function figShut() { document.body.classList.remove('figbig'); $('figBack').hidden = true; $('figClose').hidden = true; }
  $('miniShield').onclick = figOpen;
  $('figExpand').onclick = figOpen;
  $('figClose').onclick = figShut;
  $('figBack').onclick = figShut;
  document.addEventListener('keydown', function (e) { if (e.key === 'Escape') figShut(); });

  // ---------- ANDROID (sala web 22/11): «Sala» in diretta ----------
  // Come il tablet controller (CtlSala.kt): immagine della fotocamera frontale del monitor (MJPEG, GET /api/v1/live/video)
  // e audio del suo microfono (PCM 16 bit mono 16 kHz little-endian, GET /api/v1/live/audio). SOLO DIRETTA: niente viene
  // registrato né salvato (in memoria solo l'ultimo fotogramma e al massimo ~0,4 s di audio).
  // - Le credenziali viaggiano nelle intestazioni come per ogni altra chiamata: i flussi si leggono con fetch in streaming
  //   (ReadableStream), il video si spezza qui in fotogrammi JPEG e si disegna su un <canvas>. API del monitor invariata.
  // - Solo chi comanda: se comanda un altro (423) lo si spiega e si offre «Prendi il controllo» (stesso flusso della barra).
  // - Il video gira solo con il pannello aperto; l'audio resta acceso anche a pannello chiuso (si ascolta la squadra mentre
  //   si regola il paziente), con «Audio sala acceso · Spegni» nella barra in alto. Chiudendo la pagina si chiude tutto.
  // - Ritardo: l'audio tiene al massimo ~0,4 s in coda (oltre si butta, come il controller); il video mostra sempre
  //   l'ultimo fotogramma (se il disegno è indietro i fotogrammi intermedi si saltano).
  // - Flusso che si ferma senza chiudersi (rete caduta): dopo 9 s senza dati si interrompe e si riapre (3 tentativi).
  var SALA = { vOn: false, aOn: false, vGen: 0, aGen: 0, vAbort: null, aAbort: null, vLast: 0, aLast: 0, low: false, vol: 0.8, big: false,
    vErr: null, aErr: null, vWait: false, aWait: false, fps: 0, kbps: 0, delay: -1, aKbps: 0, lvl: 0, qMs: 0,
    take: false, takeAudio: false, tick: null, vBusy: false, vNext: null, srcs: [] };
  var actx = null, again = null, aNext = 0;
  function liveHeaders() {
    return { 'X-SimulaFranz-Token': token, 'X-SimulaFranz-Client': clientId,
      'X-SimulaFranz-Client-Name': encodeURIComponent(clientName), 'X-SimulaFranz-Client-Kind': 'web' };
  }
  function salaErrText(st, j) {
    j = j || {};
    if (st === 423) {
      var n = j.controller && j.controller.name;
      return 'Sala non disponibile: comanda ' + (n ? '«' + n + '»' : 'un altro controller') + '. Immagine e audio della sala arrivano solo a chi ha il comando del monitor.';
    }
    if (st === 401) return 'Sala non disponibile: PIN non valido';
    if (st === 404) return 'Questo monitor non ha ancora la «Sala»: aggiorna similFranz Monitor';
    if (st === 429) return 'Sala non disponibile: l\'immagine del monitor è già aperta in altre due finestre';
    if (j.message) return j.message;                 // 403: permesso della fotocamera o del microfono da dare sul monitor
    return 'Sala non disponibile (errore ' + st + ')';
  }
  function salaAbort(ac) { if (ac) { try { ac.abort(); } catch (e) {} } }
  // un flusso (video o audio): apre, legge finché dura, si riapre da solo; si ferma con un errore chiaro
  function salaLoop(kind, g) {
    var video = kind === 'video', tries = 0, perm = 0;
    function alive() { return video ? (SALA.vOn && g === SALA.vGen) : (SALA.aOn && g === SALA.aGen); }
    function again2(ms) {
      if (!alive()) return;
      if (video) SALA.vWait = true; else SALA.aWait = true;
      salaShow(); setTimeout(step, ms);
    }
    function step() {
      if (!alive()) return;
      var ac = window.AbortController ? new AbortController() : null;
      if (video) { SALA.vAbort = ac; SALA.vLast = Date.now(); } else { SALA.aAbort = ac; SALA.aLast = Date.now(); }
      var opt = { headers: liveHeaders(), cache: 'no-store' };
      if (ac) opt.signal = ac.signal;
      fetch('/api/v1/live/' + kind + (video && SALA.low ? '?size=320' : ''), opt).then(function (r) {
        if (!alive()) { salaAbort(ac); return; }
        if (r.status !== 200) {
          return r.json().catch(function () { return {}; }).then(function (j) {
            if (!alive()) return;
            var e = salaErrText(r.status, j);
            if (r.status === 403 && ++perm <= 10) {          // permesso in attesa sul monitor: si riprova
              if (video) SALA.vErr = e; else SALA.aErr = e;
              again2(2000); return;
            }
            salaFail(video, g, e, r.status === 423);
          });
        }
        if (!r.body || !r.body.getReader) { salaFail(video, g, 'Questo browser non sa leggere i flussi in diretta: usa Chrome, Edge, Firefox o Safari aggiornati'); return; }
        tries = 0; perm = 0;
        if (video) SALA.vErr = null; else { SALA.aErr = null; }
        return (video ? salaReadVideo : salaReadAudio)(r.body.getReader(), alive).then(function () { again2(700); });   // scaduto (10 min) o chiuso dal monitor: si riapre
      }).catch(function () {
        if (!alive()) return;
        if (++tries > 3) { salaFail(video, g, 'Sala chiusa: il monitor non risponde'); return; }
        again2(1000);
      });
    }
    step();
  }
  function salaFail(video, g, why, take) {
    if (video && g === SALA.vGen && SALA.vOn) { salaStopVideo(); SALA.vErr = why; salaBlank(); }
    if (!video && g === SALA.aGen && SALA.aOn) { salaSetAudio(false); SALA.aErr = why; if (take) SALA.takeAudio = true; }
    if (take) SALA.take = true;
    if ($('sala').hidden) msg(why);                 // audio a pannello chiuso: lo si dice nella plancia
    salaShow();
  }
  // ---- video: multipart/x-mixed-replace spezzato a mano (Content-Length, X-SF-T = ora di partenza, X-SF-Rot = rotazione)
  function salaFindHead(b, from) {
    for (var i = from; i + 3 < b.length; i++) if (b[i] === 13 && b[i + 1] === 10 && b[i + 2] === 13 && b[i + 3] === 10) return i;
    return -1;
  }
  function salaParseHead(bytes) {
    var txt = '', h = { len: -1, t: 0, rot: 0 };
    for (var i = 0; i < bytes.length; i++) txt += String.fromCharCode(bytes[i]);
    txt.split(/\r?\n/).forEach(function (line) {
      var k = line.indexOf(':'); if (k < 0) return;
      var key = line.slice(0, k).trim().toLowerCase(), v = line.slice(k + 1).trim();
      if (key === 'content-length') h.len = parseInt(v, 10);
      else if (key === 'x-sf-t') h.t = parseInt(v, 10) || 0;
      else if (key === 'x-sf-rot') h.rot = parseInt(v, 10) || 0;
    });
    return h;
  }
  function salaReadVideo(rd, alive) {
    var buf = new Uint8Array(0), pos = 0, need = -1, hd = null, minD = 1 / 0, win0 = Date.now(), wf = 0, wb = 0;
    function pump() {
      return rd.read().then(function (res) {
        if (!alive()) { try { rd.cancel(); } catch (e) {} return; }
        if (res.done) return;
        SALA.vLast = Date.now();
        var v = res.value;
        if (pos >= buf.length) { buf = v; pos = 0; }
        else { var nb = new Uint8Array(buf.length - pos + v.length); nb.set(buf.subarray(pos), 0); nb.set(v, buf.length - pos); buf = nb; pos = 0; }
        for (;;) {
          if (need < 0) {
            var e = salaFindHead(buf, pos);
            if (e < 0) { if (buf.length - pos > 8192) throw new Error('mjpeg'); break; }
            hd = salaParseHead(buf.subarray(pos, e)); pos = e + 4; need = hd.len;
            if (!(need > 0 && need <= 2000000)) throw new Error('mjpeg');
          }
          if (buf.length - pos < need) break;
          var jpg = buf.slice(pos, pos + need); pos += need; need = -1;
          var now = Date.now();
          // ritardo: il minimo di (arrivo − partenza) vale «quasi zero» (orologi diversi); sopra il minimo = ritardo
          if (hd.t > 0) { var d = now - hd.t; if (d < minD) minD = d; SALA.delay = (d - minD) + 30; }
          wf++; wb += jpg.length;
          if (now - win0 >= 1000) { SALA.fps = wf * 1000 / (now - win0); SALA.kbps = wb / 1024 * 1000 / (now - win0); wf = 0; wb = 0; win0 = now; }
          salaFrame(jpg, hd.rot);
        }
        return pump();
      });
    }
    return pump();
  }
  function salaFrame(jpg, rot) {
    if (SALA.vBusy) { SALA.vNext = [jpg, rot]; return; }       // il disegno è indietro: si tiene solo l'ultimo
    SALA.vBusy = true;
    var blob = new Blob([jpg], { type: 'image/jpeg' });
    var done = function () {
      SALA.vBusy = false;
      if (SALA.vNext) { var n = SALA.vNext; SALA.vNext = null; if (SALA.vOn) salaFrame(n[0], n[1]); }
    };
    if (window.createImageBitmap) {
      createImageBitmap(blob).then(function (bm) { salaDraw(bm, rot); if (bm.close) bm.close(); done(); }, done);
    } else {
      var u = URL.createObjectURL(blob), im = new Image();
      im.onload = function () { salaDraw(im, rot); URL.revokeObjectURL(u); done(); };
      im.onerror = function () { URL.revokeObjectURL(u); done(); };
      im.src = u;
    }
  }
  function salaDraw(img, rot) {
    if (!SALA.vOn) return;
    var cv = $('salaCv'), w = img.width, h = img.height, r = ((rot % 360) + 360) % 360, sw = r === 90 || r === 270;
    var cw = sw ? h : w, ch = sw ? w : h;
    if (cv.width !== cw || cv.height !== ch) { cv.width = cw; cv.height = ch; }
    var c = cv.getContext('2d');
    c.save(); c.translate(cw / 2, ch / 2); c.rotate(r * Math.PI / 180); c.drawImage(img, -w / 2, -h / 2); c.restore();
    if (SALA.vWait) { SALA.vWait = false; salaShow(); }
  }
  // ---- audio: Web Audio, pezzi programmati uno dopo l'altro; coda massima ~0,4 s
  function salaCtx() {                       // va chiamata dentro un tocco dell'utente (regola dei browser sull'audio)
    if (!actx) {
      var C = window.AudioContext || window.webkitAudioContext;
      if (!C) return null;
      try { actx = new C(); } catch (e) { return null; }
      again = actx.createGain(); again.connect(actx.destination);
    }
    again.gain.value = SALA.vol;
    if (actx.state === 'suspended' && actx.resume) { try { actx.resume(); } catch (e) {} }
    return actx;
  }
  function salaPlay(f) {
    var now = actx.currentTime, q = aNext - now;
    if (q < 0) { aNext = now + 0.1; q = 0.1; }               // ripartenza: 100 ms di margine
    if (q > 0.4) { SALA.qMs = Math.round(q * 1000); return; }  // oltre 0,4 s in coda si salta avanti (ritardo basso)
    var b;
    try {
      b = actx.createBuffer(1, f.length, 16000); b.getChannelData(0).set(f);
    } catch (e) {                                          // browser che non accetta 16 kHz: si ricampiona qui
      var sr = actx.sampleRate, m = Math.max(1, Math.round(f.length * sr / 16000));
      b = actx.createBuffer(1, m, sr);
      var o = b.getChannelData(0), k = 16000 / sr;
      for (var i = 0; i < m; i++) { var x = i * k, j = Math.floor(x), u = x - j; o[i] = (f[j] || 0) * (1 - u) + (f[j + 1] !== undefined ? f[j + 1] : (f[j] || 0)) * u; }
    }
    var src = actx.createBufferSource(); src.buffer = b; src.connect(again); src.start(aNext);
    SALA.srcs.push(src);
    src.onended = function () { var i = SALA.srcs.indexOf(src); if (i >= 0) SALA.srcs.splice(i, 1); };
    aNext += b.duration;
    SALA.qMs = Math.round((aNext - now) * 1000);
  }
  function salaReadAudio(rd, alive) {
    var odd = -1, win0 = Date.now(), wb = 0;
    aNext = 0;
    function pump() {
      return rd.read().then(function (res) {
        if (!alive()) { try { rd.cancel(); } catch (e) {} return; }
        if (res.done) return;
        SALA.aLast = Date.now();
        var v = res.value;
        if (odd >= 0) { var nv = new Uint8Array(v.length + 1); nv[0] = odd; nv.set(v, 1); v = nv; odd = -1; }
        var n = v.length >> 1;
        if (v.length & 1) odd = v[v.length - 1];
        wb += v.length;
        if (n > 0) {
          var f = new Float32Array(n), sum = 0;
          for (var i = 0; i < n; i++) { var s = v[2 * i] | (v[2 * i + 1] << 8); if (s & 0x8000) s -= 0x10000; f[i] = s / 32768; sum += s * s; }
          SALA.lvl = Math.min(1, Math.sqrt(sum / n) / 6000);
          if (actx) salaPlay(f);
          if (SALA.aWait) { SALA.aWait = false; }
        }
        var now = Date.now();
        if (now - win0 >= 1000) { SALA.aKbps = wb / 1024 * 1000 / (now - win0); wb = 0; win0 = now; }
        return pump();
      });
    }
    return pump();
  }
  // ---- comandi
  function salaTick() {
    if (SALA.vOn || SALA.aOn) {
      if (!SALA.tick) SALA.tick = setInterval(function () {
        var now = Date.now();
        if (SALA.vOn && now - SALA.vLast > 9000) { SALA.vLast = now; salaAbort(SALA.vAbort); }    // flusso fermo: si riapre
        if (SALA.aOn && now - SALA.aLast > 9000) { SALA.aLast = now; salaAbort(SALA.aAbort); }
        salaShow();
      }, 250);
    } else if (SALA.tick) { clearInterval(SALA.tick); SALA.tick = null; }
  }
  function salaBlank() { var cv = $('salaCv'); cv.getContext('2d').clearRect(0, 0, cv.width, cv.height); }     // niente fotogrammi vecchi
  function salaStartVideo() {
    if (SALA.vOn) return;
    salaBlank();
    SALA.vOn = true; SALA.vErr = null; SALA.vWait = true; SALA.vGen++; SALA.fps = 0; SALA.kbps = 0; SALA.delay = -1;
    salaLoop('video', SALA.vGen); salaTick(); salaShow();
  }
  function salaStopVideo() {
    if (!SALA.vOn) return;
    SALA.vOn = false; SALA.vGen++; salaAbort(SALA.vAbort); SALA.vAbort = null; SALA.vNext = null;
    SALA.fps = 0; SALA.kbps = 0; SALA.delay = -1;
    salaTick(); salaShow();
  }
  function salaSetAudio(on) {
    if (on === SALA.aOn) return;
    SALA.aOn = on; SALA.aGen++;
    if (on) {
      if (!salaCtx()) { SALA.aOn = false; SALA.aErr = 'Questo browser non riproduce l\'audio in diretta (Web Audio assente)'; salaShow(); return; }
      SALA.aErr = null; SALA.aWait = true; SALA.takeAudio = false;
      salaLoop('audio', SALA.aGen);
    } else {
      salaAbort(SALA.aAbort); SALA.aAbort = null; SALA.aKbps = 0; SALA.lvl = 0; SALA.qMs = 0;
      SALA.srcs.forEach(function (s) { try { s.stop(); } catch (e) {} }); SALA.srcs = [];    // ciò che era in coda tace subito
      if (actx && actx.suspend) { try { actx.suspend(); } catch (e) {} }
    }
    salaTick(); salaShow();
  }
  function salaStopAll() { salaStopVideo(); salaSetAudio(false); }
  function salaOpen() {
    $('sala').hidden = false; SALA.take = false; SALA.vErr = null;
    salaStartVideo(); salaShow();
  }
  function salaClose() { $('sala').hidden = true; salaStopVideo(); salaShow(); }    // l'audio, se acceso, resta
  function salaShow() {
    var open = !$('sala').hidden;
    $('salaBtn').classList.toggle('on', open || SALA.aOn);
    $('salaEar').hidden = !(SALA.aOn && !open);
    if (!open) return;
    $('sala').classList.toggle('big', SALA.big);
    $('salaBig').textContent = SALA.big ? 'Rimpicciolisci' : 'Ingrandisci';
    var w = $('salaWait'), ve = SALA.vErr;
    w.hidden = SALA.vOn && !SALA.vWait && !ve;
    w.classList.toggle('err', !!ve);
    w.textContent = ve ? ve + (SALA.vOn ? '' : ' · Tocca qui per riprovare.')
      : (SALA.vOn ? 'Collegamento alla fotocamera del monitor…' : 'Immagine ferma · Tocca qui per riprovare.');
    $('salaStats').hidden = !SALA.vOn || SALA.vWait || !!ve;
    $('salaStats').textContent = Math.round(SALA.fps) + ' fps · ritardo ≈ ' + (SALA.delay >= 0 ? SALA.delay + ' ms' : '--') +
      ' · ' + Math.round(SALA.kbps) + ' kB/s' + (SALA.low ? ' · 320' : '');
    $('salaErr').hidden = !SALA.take;
    $('salaErrT').textContent = 'Per guardare e ascoltare la sala devi avere il comando del monitor.';
    $('salaTake').hidden = !SALA.take;
    var ab = $('salaAudio');
    ab.textContent = SALA.aOn ? 'Audio acceso · Spegni' : 'Ascolta';
    ab.classList.toggle('on', SALA.aOn);
    $('salaLvl').style.width = Math.round((SALA.aOn && !SALA.aWait ? SALA.lvl : 0) * 100) + '%';
    var at = $('salaAudT'), held = SALA.aOn && actx && actx.state === 'suspended';
    at.textContent = SALA.aErr || (!SALA.aOn ? 'Audio spento: tocca «Ascolta» per sentire la sala'
      : SALA.aWait ? 'Collegamento al microfono del monitor…'
      : held ? 'Il browser tiene l\'audio in pausa: spegni e tocca di nuovo «Ascolta»'
      : 'audio ' + Math.round(SALA.aKbps) + ' kB/s · in coda ' + SALA.qMs + ' ms');
    at.classList.toggle('err', !!SALA.aErr || !!held);
    $('salaQ').textContent = SALA.low ? 'Qualità: bassa (rete debole)' : 'Qualità: normale';
    $('salaVolT').textContent = Math.round(SALA.vol * 100) + ' %';
  }
  function initSala() {
    $('salaBtn').onclick = function () { if ($('sala').hidden) salaOpen(); else salaClose(); };
    $('salaEar').onclick = function () { salaSetAudio(false); };
    $('salaClose').onclick = salaClose;
    $('salaStop').onclick = function () { salaStopAll(); salaClose(); };
    $('salaBig').onclick = function () { SALA.big = !SALA.big; salaShow(); };
    $('salaAudio').onclick = function () { salaSetAudio(!SALA.aOn); };
    $('salaVol').oninput = function () {
      SALA.vol = Math.max(0, Math.min(1, parseInt($('salaVol').value, 10) / 100));
      if (again) again.gain.value = SALA.vol;
      salaShow();
    };
    $('salaQ').onclick = function () { SALA.low = !SALA.low; if (SALA.vOn) { salaStopVideo(); salaStartVideo(); } salaShow(); };
    $('salaWait').onclick = function () { if (!SALA.vOn) { SALA.vErr = null; SALA.take = false; salaStartVideo(); } };
    $('salaTake').onclick = function () {       // stesso flusso di «Prendi il controllo» della barra in alto
      if (SALA.takeAudio) salaCtx();            // l'audio si sblocca dentro il tocco (regola dei browser)
      api('POST', '/api/v1/control', { action: 'take' }).then(function (j) {
        handle(j);
        if (!j || !j.ok) return;
        msg('Ora comandi tu'); poll();
        SALA.take = false; SALA.vErr = null;
        if (!$('sala').hidden) { salaStopVideo(); salaStartVideo(); }
        if (SALA.takeAudio) { SALA.takeAudio = false; salaSetAudio(true); }
        salaShow();
      }).catch(function () { msg('Comando non preso: il monitor non risponde'); });
    };
    document.addEventListener('keydown', function (e) {
      if (e.key !== 'Escape' || $('sala').hidden) return;
      if (!$('sfDlg').hidden || !$('debrief').hidden || !$('storia').hidden || document.body.classList.contains('figbig')) return;
      salaClose();
    });
    // chiudendo o lasciando la pagina le connessioni si chiudono
    window.addEventListener('pagehide', function () { salaStopAll(); });
    window.addEventListener('beforeunload', function () { salaStopAll(); });
    salaShow();
  }
  initSala();
  tx12Bind();                         // ANDROID (trasmissione 12D 22/11)

  // =====================================================================
  // ANDROID (funzioni mancanti 22/11): linea arteriosa (IN-03/OP-04), PVC e T2 (IN-13), ventilazione (CC-04/AE-12/OP-05),
  // limiti separati e ritardi (IN-08/IN-09), distrattori (IN-10/IN-12/IN-18/AE-08), esami (IN-13), trend (IN-14), allarmi nel
  // debriefing (IN-05/IN-06), aiuti AE-18 / IN-16. Tutto facoltativo: di serie il monitor si comporta come prima.
  // In sola osservazione i controlli non mandano nulla (body.observer, come gli altri pannelli).
  // =====================================================================
  var X = { dirty: {}, tr: { rows: [], at: 0, busy: false }, trStep: 60, roscAt: 0, wasPulse: null, nibpHintOff: false };
  var XLIM = ['art_sys_low', 'art_sys_high', 'nibp_sys_low', 'nibp_sys_high', 'art_map_low', 'art_map_high', 'nibp_map_low', 'nibp_map_high', 'spo2_high', 'temp_low', 'temp_high'];
  var XDELAY = [['hr', 'FC'], ['spo2', 'SpO₂'], ['rr', 'FR'], ['etco2', 'EtCO₂'], ['art', 'ART'], ['nibp', 'NIBP'], ['temp', 'Temperatura'], ['apnea', 'Apnea']];
  var XDELAY_DEF = { hr: 0, spo2: 5, rr: 5, etco2: 5, art: 0, nibp: 0, temp: 10, apnea: 20 };
  var XLABS = [['ph', 'pH', '', 0.01], ['pao2_mmhg', 'PaO₂', 'mmHg', 1], ['paco2_mmhg', 'PaCO₂', 'mmHg', 1], ['hco3_mmol', 'HCO₃⁻', 'mmol/l', 0.1],
               ['be_mmol', 'BE', 'mmol/l', 0.1], ['lactate_mmol', 'Lattati', 'mmol/l', 0.1], ['glucose_mgdl', 'Glicemia', 'mg/dl', 1],
               ['potassium_mmol', 'K⁺', 'mmol/l', 0.1], ['hb_gdl', 'Hb', 'g/dl', 0.1], ['urine_ml_h', 'Diuresi', 'ml/h', 1], ['balance_ml', 'Bilancio', 'ml', 10]];
  var XTECH = [['ecg_leads_off', 'Elettrodi ECG scollegati'], ['spo2_probe_off', 'Sonda SpO₂ scollegata'], ['nibp_cuff_error', 'Errore del bracciale NIBP'],
               ['nibp_timeout', 'NIBP: tempo di misura superato'], ['etco2_occlusion', 'Linea CO₂ occlusa'], ['low_battery', 'Batteria scarica']];
  function xSeg(id, v) { all('#' + id + ' button').forEach(function (b) { b.className = b.dataset.v === String(v) ? 'active' : ''; }); }
  function xFocus(id) { return document.activeElement === $(id); }
  function xNum(id) { var v = $(id).value.trim(); if (v === '') return null; v = parseFloat(v.replace(',', '.')); return isNaN(v) ? NaN : v; }
  function xSw(id, f) { var i = $(id).querySelector('input'); i.onchange = function () { f(i.checked); }; }
  function xSwSet(id, v) { var i = $(id).querySelector('input'); if (document.activeElement !== i) i.checked = !!v; }

  // ---------- trend (IN-14): grafico SVG e tabella, condivisi da pannello, debriefing e PDF ----------
  var XTS = [['hr', 'FC', '#33ff5a'], ['spo2', 'SpO₂', '#33e6ff'], ['art_s', 'ART S', '#ff3b30'], ['art_d', 'ART D', '#ff3b30'], ['nibp_s', 'NIBP S', '#f0547a'],
             ['rr', 'FR', '#e0c040'], ['etco2', 'EtCO₂', '#f2d84a'], ['cvp', 'PVC', '#7fa8ff']];
  function trendPick(rows, step) {          // la prima riga di ogni finestra di step secondi
    if (!step || step <= 5) return rows.slice();
    var out = [], next = -1;
    rows.forEach(function (r) { if (r.t >= next) { out.push(r); next = Math.floor(r.t / step) * step + step; } });
    return out;
  }
  function trendSvg(rows, pdf) {
    if (!rows || rows.length < 2) return '<p class="hint">Il trend si riempie da solo dopo «Avvia» (un punto ogni 5 s di scenario).</p>';
    var W = 720, H = 220, L = 34, B = 20, T = 8, t0 = rows[0].t, t1 = Math.max(rows[rows.length - 1].t, t0 + 60), ymax = 200;
    function x(t) { return L + (W - L - 6) * (t - t0) / (t1 - t0); }
    function y(v) { return T + (H - T - B) * (1 - Math.min(ymax, Math.max(0, v)) / ymax); }
    var ax = pdf ? '#9aa5b1' : '#4a5a6a', tx = pdf ? '#5b6673' : '#8ea0b2';
    var g = '<svg class="trsvg" viewBox="0 0 ' + W + ' ' + H + '" width="100%" role="img" aria-label="Trend dei parametri">';
    [0, 50, 100, 150, 200].forEach(function (v) { g += '<line x1="' + L + '" x2="' + (W - 6) + '" y1="' + y(v) + '" y2="' + y(v) + '" stroke="' + ax + '" stroke-width=".5"/><text x="' + (L - 4) + '" y="' + (y(v) + 3) + '" fill="' + tx + '" font-size="10" text-anchor="end">' + v + '</text>'; });
    var span = t1 - t0, tick = span > 3600 ? 900 : span > 1200 ? 300 : span > 300 ? 60 : 30;
    for (var tt = Math.ceil(t0 / tick) * tick; tt <= t1; tt += tick) g += '<text x="' + x(tt) + '" y="' + (H - 5) + '" fill="' + tx + '" font-size="10" text-anchor="middle">' + fmtMMSS(tt) + '</text>';
    XTS.forEach(function (s) {
      var d = '', pen = false;
      if (s[0] === 'nibp_s') {                // NIBP: un punto per misura
        rows.forEach(function (r) { if (r.nibp_new && r.nibp_s !== null && r.nibp_s !== undefined) g += '<circle cx="' + x(r.t) + '" cy="' + y(r.nibp_s) + '" r="3" fill="' + s[2] + '"/>'; });
        return;
      }
      rows.forEach(function (r) {
        var v = r[s[0]];
        if (v === null || v === undefined) { pen = false; return; }
        d += (pen ? 'L' : 'M') + x(r.t).toFixed(1) + ' ' + y(v).toFixed(1) + ' '; pen = true;
      });
      if (d) g += '<path d="' + d + '" fill="none" stroke="' + s[2] + '" stroke-width="1.6"' + (s[0] === 'art_d' || s[0] === 'etco2' ? ' stroke-dasharray="4 3"' : '') + '/>';
    });
    g += '</svg><div class="trleg">' + XTS.filter(function (s) { return rows.some(function (r) { return r[s[0]] !== null && r[s[0]] !== undefined; }); })
      .map(function (s) { return '<span><i style="background:' + s[2] + '"></i>' + esc(s[1]) + '</span>'; }).join('') + '</div>';
    return g;
  }
  function trendTable(rows, step) {
    var p = trendPick(rows, step);
    if (!p.length) return '';
    function v(x) { return x === null || x === undefined ? '—' : String(x); }
    var cols = [['hr', 'FC'], ['spo2', 'SpO₂'], ['art', 'ART'], ['nibp', 'NIBP'], ['rr', 'FR'], ['etco2', 'EtCO₂'], ['temp', 'T1'], ['temp2', 'T2'], ['cvp', 'PVC'], ['ppv', 'PPV %']];
    cols = cols.filter(function (c) {
      return p.some(function (r) { return c[0] === 'art' ? r.art_s != null : c[0] === 'nibp' ? r.nibp_s != null : r[c[0]] != null; });
    });
    return '<table class="limits trtab"><thead><tr><th>Tempo</th>' + cols.map(function (c) { return '<th>' + esc(c[1]) + '</th>'; }).join('') + '</tr></thead><tbody>' +
      p.map(function (r) {
        return '<tr><td>' + fmtMMSS(r.t) + '</td>' + cols.map(function (c) {
          var t;
          if (c[0] === 'art') t = r.art_s != null ? r.art_s + '/' + r.art_d + (r.art_m != null ? ' (' + r.art_m + ')' : '') : '—';
          else if (c[0] === 'nibp') t = r.nibp_s != null ? r.nibp_s + '/' + r.nibp_d + ' (' + r.nibp_m + ')' + (r.nibp_new ? '' : ' ·') : '—';
          else if (c[0] === 'temp' || c[0] === 'temp2') t = r[c[0]] != null ? Number(r[c[0]]).toFixed(1) : '—';
          else t = v(r[c[0]]);
          return '<td>' + esc(t) + '</td>';
        }).join('') + '</tr>';
      }).join('') + '</tbody></table><p class="hint">NIBP: «·» = ultima misura, non nuova in quella riga. Valori come li mostrava il monitor («—» = non mostrato).</p>';
  }
  function trendFetch(cb) {
    if (X.tr.busy) return;
    X.tr.busy = true;
    withTimeout(api('GET', '/api/v1/trend?since=0'), 6000).then(function (j) {
      X.tr.busy = false;
      if (j && j.ok && j.samples) { X.tr.rows = j.samples; X.tr.at = Date.now(); }
      if (cb) cb();
    }).catch(function () { X.tr.busy = false; if (cb) cb(); });
  }
  /** trend da salvare con la simulazione (Storia Sim, PDF): passo 60 s (15 s se lo scenario è breve) */
  function trendForRecord(rows) {      // scenari brevi (< 20 min): una riga ogni 15 s, così il grafico non resta vuoto
    rows = rows || X.tr.rows;
    var span = rows.length ? rows[rows.length - 1].t - rows[0].t : 0;
    return trendPick(rows, span > 1200 ? 60 : 15).slice(-480);
  }
  function renderTrendPanel() {
    var box = $('trendBox'); if (!box) return;
    xSeg('trendStep', X.trStep);
    box.innerHTML = trendSvg(X.tr.rows, false) + trendTable(X.tr.rows, X.trStep);
  }
  setInterval(function () {
    if (!state || notStarted() || !monOnline) return;
    var need = card === 'panel_trend' || !$('debrief').hidden;
    if (need || Date.now() - X.tr.at > 30000) trendFetch(function () { if (card === 'panel_trend') renderTrendPanel(); if (!$('debrief').hidden) renderDebriefX(); });
  }, 5000);

  // ---------- allarmi nel debriefing (IN-05/IN-06) ----------
  function alarmBlock(al, pdf) {
    if (!al || !al.total && !al.limits_changed && !al.alarms_disabled && !al.silenced) return pdf ? '<p class="empty">Nessun allarme registrato.</p>' : '';
    var LV = { 3: 'ALTO', 2: 'MEDIO', 1: 'TECNICO' };
    var head = 'Allarmi ' + (al.total || 0) + ' (alti ' + (al.high || 0) + ', medi ' + (al.medium || 0) + ', tecnici ' + (al.technical || 0) + ')' +
      ' · silenziati ' + (al.silenced || 0) + (al.mean_silence_s != null ? ' (in media dopo ' + al.mean_silence_s + ' s, al massimo ' + al.max_silence_s + ' s)' : '') +
      ' · senza risposta ' + (al.unanswered || 0) + ' · limiti cambiati ' + (al.limits_changed || 0) + ' · allarmi spenti ' + (al.alarms_disabled || 0) +
      ' · tecnici risolti ' + (al.tech_resolved || 0) + (al.first_high_at_s != null ? ' · primo allarme alto a ' + fmtMMSS(al.first_high_at_s) : '');
    var it = (al.items || []).slice(0, pdf ? 60 : 25);
    var tab = it.length ? '<table class="' + (pdf ? 'tl' : 'limits altab') + '"><thead><tr><th>Inizio</th><th>Priorità</th><th>Allarme</th><th>Durata</th><th>Silenziato dopo</th></tr></thead><tbody>' +
      it.map(function (a) {
        return '<tr class="al' + (a.lvl || 0) + '"><td>' + fmtMMSS(a.t) + '</td><td>' + (LV[a.lvl] || '—') + '</td><td>' + esc(a.alarm || '') + '</td><td>' +
          (a.active ? 'in corso' : a.dur_s != null ? fmtMMSS(a.dur_s) : '—') + '</td><td>' + (a.mute_s != null ? a.mute_s + ' s' : (a.lvl >= 2 ? 'no' : '—')) + '</td></tr>';
      }).join('') + '</tbody></table>' : '';
    return (pdf ? '<p>' + esc(head) + '</p>' : '<h5>Allarmi</h5><p class="hint">' + esc(head) + '</p>') + tab;
  }
  function renderDebriefX() {
    var box = $('debrief'); if (!box || box.hidden) return;
    var arch = dbArch, past = !arch && dbSess !== null;
    var sn = !arch && !past ? snapShown() : null;
    var sm = (arch ? arch.summary : past ? (dbPast ? dbPast.summary : {}) : sn ? sn.summary : logSum) || {};
    $('dbAlarms').innerHTML = alarmBlock(sm.alarms, false);
    var d = draft || draftLoad();
    var rows = arch ? (arch.trend || []) : past ? [] : (d && d.snap && d.snap.trend && d.fromEnd ? d.snap.trend : trendForRecord());
    $('dbTrendBox').hidden = !rows.length;
    $('dbTrend').innerHTML = rows.length ? trendSvg(rows, false) + trendTable(rows, 60) : '';
  }

  // ---------- pannelli della regia ----------
  function initMancanti() {
    // linea arteriosa
    var lv = []; for (var c = -30; c <= 30; c += 5) lv.push('<option value="' + c + '">' + (c === 0 ? 'all\'asse (0 cm)' : (c > 0 ? c + ' cm sopra (' : -c + ' cm sotto (') + (c > 0 ? '−' : '+') + Math.round(Math.abs(c) * 0.74) + ' mmHg)') + '</option>');
    $('artLevel').innerHTML = lv.join('');
    $('artLevel').onchange = function () { command('set_art_line', { level_cm: parseInt($('artLevel').value, 10) }); };
    all('#artDampSeg button').forEach(function (b) { b.onclick = function () { command('set_art_line', { damping: b.dataset.v }); }; });
    xSw('artOcclSw', function (v) { command('set_art_line', { occluded: v }); });
    xSw('artZeroReqSw', function (v) { command('set_art_line', { zero_required: v }); });
    $('artZero').onclick = function () { if (tapOnce('artZero', 900)) command('art_zero').then(function (ok) { if (!ok) msg('Zero non eseguito: ' + lastCmdErr, true); }); };
    $('artFlush').onclick = function () { if (tapOnce('artFlush', 900)) command('art_flush').then(function (ok) { if (!ok) msg('Lavaggio non eseguito: ' + lastCmdErr, true); }); };
    // PVC e T2
    all('#cvpPre button').forEach(function (b) { b.onclick = function () { command('set_cvp', parseInt(b.dataset.v, 10)); }; });
    all('#cvpWave button').forEach(function (b) { b.onclick = function () { command('set_cvp_waveform', b.dataset.v); }; });
    $('cvpGo').onclick = function () { var v = xNum('cvpVal'); if (v === null || isNaN(v)) { msg('PVC: scrivi un numero (0–40 mmHg)', true); return; } command('set_cvp', Math.round(v)); };
    $('t2Go').onclick = function () { var v = xNum('t2Val'); if (v === null || isNaN(v)) { msg('T2: scrivi una temperatura (25–45 °C)', true); return; } command('set_temperature2', Math.round(v * 10) / 10); };
    $('cvpSensor').onclick = function () { command('set_sensor', { sensor: 'cvp', connected: !on('cvp') }); };
    $('t2Sensor').onclick = function () { command('set_sensor', { sensor: 'temp2', connected: !on('temp2') }); };
    // ventilazione
    all('#ventMode button').forEach(function (b) { b.onclick = function () { command('set_ventilation', { mode: b.dataset.v }); }; });
    ['ventRate', 'ventTidal'].forEach(function (id) { $(id).addEventListener('input', function () { X.dirty.vent = true; }); });
    $('ventGo').onclick = function () {
      var r = xNum('ventRate'), t = xNum('ventTidal'), v = { arrest_rate_rpm: parseInt($('ventArrest').value, 10) };
      if (r !== null) { if (isNaN(r)) { msg('FR del ventilatore: numero 4–40', true); return; } v.rate_rpm = Math.round(r); }
      if (t !== null) { if (isNaN(t)) { msg('Volume: numero 200–1000 ml', true); return; } v.tidal_ml = Math.round(t); }
      if (state && state.ventilation && state.ventilation.mode !== 'ventilator') v.mode = 'ventilator';
      command('set_ventilation', v).then(function (ok) { if (ok) X.dirty.vent = false; else msg('Ventilatore non impostato: ' + lastCmdErr, true); });
    };
    xSw('ventDiscSw', function (v) { command('set_ventilation', { disconnected: v }); });
    xSw('ventAdvSw', function (v) { command('set_ventilation', { advanced_airway: v }); });
    all('#ppvSeg button').forEach(function (b) { b.onclick = function () {
      if (b.dataset.v === 'auto') command('set_ppv', 'auto');
      else { var v = xNum('ppvVal'); command('set_ppv', v === null || isNaN(v) ? 15 : Math.max(0, Math.min(30, Math.round(v)))); }
    }; });
    $('ppvVal').addEventListener('change', function () { var v = xNum('ppvVal'); if (v !== null && !isNaN(v)) command('set_ppv', Math.max(0, Math.min(30, Math.round(v)))); });
    // limiti separati, profili, ritardi
    XLIM.forEach(function (k) { $('x_' + k).addEventListener('input', function () { X.dirty.lim = true; }); });
    $('xlimApply').onclick = function () {
      var v = {}, bad = [];
      XLIM.forEach(function (k) {
        var n = xNum('x_' + k);
        if (n === null) { if (/map|spo2_high|temp/.test(k)) v[k] = 0; return; }
        if (isNaN(n)) { bad.push(k); return; }
        v[k] = /^temp/.test(k) ? (n === 0 ? 0 : Math.round(n * 10)) : Math.round(n);
      });
      if (bad.length) { msg('Limiti non applicati: campi non numerici', true); return; }
      command('set_alarm_limits', v).then(function (ok) { if (ok) X.dirty.lim = false; else msg('Limiti non applicati: ' + lastCmdErr, true); });
    };
    $('profShock').onclick = function () { command('set_alarm_limits', { art_map_low: 65, nibp_map_low: 65 }).then(function (ok) { if (ok) { X.dirty.lim = false; msg('Profilo «ICU shock»: allarme PAM < 65 su ART e NIBP'); } }); };
    $('profRosc').onclick = function () { command('set_alarm_limits', { spo2_low: 94, spo2_high: 98, art_map_low: 65, nibp_map_low: 65, temp_high: 375 }).then(function (ok) { if (ok) { X.dirty.lim = false; msg('Profilo «Post-ROSC»: SpO₂ 94–98, PAM ≥ 65, T ≤ 37,5'); } }); };
    $('delayGrid').innerHTML = XDELAY.map(function (d) { return '<label>' + esc(d[1]) + ' <input type="number" id="dl_' + d[0] + '" min="0" max="60"> <small>s</small></label>'; }).join('');
    XDELAY.forEach(function (d) { $('dl_' + d[0]).addEventListener('input', function () { X.dirty.delay = true; }); });
    function sendDelays(v) { command('set_alarm_delays', v).then(function (ok) { if (ok) X.dirty.delay = false; else msg('Ritardi non applicati: ' + lastCmdErr, true); }); }
    $('delayApply').onclick = function () { var v = {}; XDELAY.forEach(function (d) { var n = xNum('dl_' + d[0]); if (n !== null && !isNaN(n)) v[d[0]] = Math.max(0, Math.min(60, Math.round(n))); }); sendDelays(v); };
    $('delayZero').onclick = function () { var v = {}; XDELAY.forEach(function (d) { v[d[0]] = 0; }); sendDelays(v); };
    $('delayDef').onclick = function () { sendDelays(XDELAY_DEF); };
    // distrattori
    all('#elSeg button').forEach(function (b) { b.onclick = function () { command('set_ecg_electrode', b.dataset.v); }; });
    all('#noiseSeg button').forEach(function (b) { b.onclick = function () { command('set_ecg_noise', parseInt(b.dataset.v, 10)); }; });
    xSw('limbSw', function (v) { command('set_limb_reversal', v); });
    xSw('resolvSw', function (v) { command('set_tech_resolvable', v); });
    $('techGrid').innerHTML = XTECH.map(function (t) { return '<button class="toggle off" data-k="' + t[0] + '">' + esc(t[1]) + '</button>'; }).join('');
    all('#techGrid button').forEach(function (b) { b.onclick = function () {
      var onNow = !!(state && (state.technical_alarms || []).some(function (x) { return x.kind === b.dataset.k; }));
      command('set_technical_alarm', { kind: b.dataset.k, on: !onNow });
    }; });
    all('#padsSeg button').forEach(function (b) { b.onclick = function () { command('set_pads_contact', b.dataset.v).then(function (ok) { if (ok) X.pads = b.dataset.v; }); }; });
    // esami
    $('labGrid').innerHTML = XLABS.map(function (f) { return '<label>' + esc(f[1]) + ' <input type="number" step="' + f[3] + '" id="lab_' + f[0] + '"> <small>' + esc(f[2]) + '</small></label>'; }).join('');
    $('labGo').onclick = function () {
      var v = {}, n = 0, bad = false;
      XLABS.forEach(function (f) { var x = xNum('lab_' + f[0]); if (x === null) return; if (isNaN(x)) { bad = true; return; } v[f[0]] = x; n++; });
      if (bad) { msg('Esami: un campo non è un numero', true); return; }
      if (!n) { msg('Esami: scrivi almeno un valore', true); return; }
      command('set_labs', v).then(function (ok) {
        if (!ok) { msg('Esami non inviati: ' + lastCmdErr, true); return; }
        XLABS.forEach(function (f) { $('lab_' + f[0]).value = ''; });
        msg(notStarted() ? 'Esami impostati (il registro scrive dopo «Avvia»)' : 'Esami nel registro');
      });
    };
    // trend
    all('#trendStep button').forEach(function (b) { b.onclick = function () { X.trStep = parseInt(b.dataset.v, 10); renderTrendPanel(); }; });
    // aiuti AE-18 / IN-16 sotto la striscia dei messaggi
    var hb = document.createElement('div'); hb.id = 'xHelp'; hb.className = 'xhelp'; hb.hidden = true;
    $('msg').parentNode.insertBefore(hb, $('msg').nextSibling);
    hb.addEventListener('click', function (e) {
      var b = e.target.closest('button[data-a]'); if (!b) return;
      if (b.dataset.a === 'mute2') command('mute_alarms', 120);
      else if (b.dataset.a === 'nibp3') command('set_nibp_interval', 3);
      else if (b.dataset.a === 'hide') { X.nibpHintOff = true; renderMancanti(state); }
    });
    all('#pnav button[data-card="panel_trend"]').forEach(function (b) { b.addEventListener('click', function () { renderTrendPanel(); trendFetch(renderTrendPanel); }); });
  }

  function renderMancanti(s) {
    if (!s) return;
    var al = s.art_line || {};
    xSeg('artDampSeg', al.damping || 'optimal');
    if (!xFocus('artLevel')) $('artLevel').value = String(al.level_cm || 0);
    xSwSet('artOcclSw', al.occluded); xSwSet('artZeroReqSw', al.zero_required);
    var st = !on('art') ? 'linea non collegata' : al.zeroing ? 'AZZERAMENTO in corso… ' + (al.zeroing_remaining_s || 0) + ' s' : al.needs_zero ? 'DA AZZERARE: a monitor «---»' :
      (al.occluded ? 'OCCLUSA' : { optimal: 'smorzamento ottimale', over: 'sovrasmorzata', under: 'sottosmorzata' }[al.damping] || '') +
      (al.level_cm ? ' · livello ' + (al.level_cm > 0 ? '+' : '') + al.level_cm + ' cm (' + (al.offset_mmhg > 0 ? '+' : '') + al.offset_mmhg + ' mmHg)' : '') +
      (al.zeroed_at ? ' · azzerata alle ' + al.zeroed_at : '') + (al.flushing ? ' · LAVAGGIO' : '');
    $('artLineState').textContent = st;
    $('artZero').disabled = !on('art') && !on('cvp'); $('artFlush').disabled = !on('art');
    xSeg('cvpPre', s.cvp_mmhg); xSeg('cvpWave', s.cvp_waveform || 'normal');
    if (!xFocus('cvpVal') && s.cvp_mmhg !== undefined) $('cvpVal').value = s.cvp_mmhg;
    var mn = s.monitor_numerics || {};
    $('cvpSensor').textContent = on('cvp') ? 'Stacca il trasduttore PVC' : 'Collega il trasduttore PVC';
    $('cvpNow').textContent = on('cvp') ? 'Monitor: PVC ' + (mn.cvp_mmhg != null ? mn.cvp_mmhg + ' mmHg' : '---') + (al.cvp_needs_zero ? ' (da azzerare)' : '') + ' · si vede nei layout ICU'
      : 'Trasduttore non collegato: la PVC non compare sul monitor.';
    $('t2Sensor').textContent = on('temp2') ? 'Stacca la sonda T2' : 'Collega la sonda T2';
    if (!xFocus('t2Val') && s.temperature2_c !== undefined) $('t2Val').value = Number(s.temperature2_c).toFixed(1);
    $('t2Now').textContent = on('temp2') ? 'Monitor: T2 ' + (mn.temperature2_c != null ? Number(mn.temperature2_c).toFixed(1) + ' °C' : '---') : 'sonda non collegata';
    // ventilazione
    var v = s.ventilation || {};
    xSeg('ventMode', v.mode || 'spontaneous');
    if (!X.dirty.vent) { if (!xFocus('ventRate')) $('ventRate').value = v.rate_rpm || 12; if (!xFocus('ventTidal')) $('ventTidal').value = v.tidal_ml || 450; }
    if (!xFocus('ventArrest') && v.arrest_rate_rpm !== undefined) $('ventArrest').value = String(v.arrest_rate_rpm);
    xSwSet('ventDiscSw', v.disconnected); xSwSet('ventAdvSw', v.advanced_airway);
    $('ventDiscSw').hidden = v.mode !== 'ventilator'; $('ventAdvSw').hidden = v.mode !== 'bag';
    xSeg('ppvSeg', v.ppv_mode === 'instructor' ? 'set' : 'auto');
    if (!xFocus('ppvVal') && v.ppv_percent !== undefined) $('ppvVal').value = v.ppv_percent;
    $('ventState').textContent = v.mode === 'ventilator' ? (v.disconnected ? 'SCOLLEGATO' : 'ventilatore ' + v.rate_rpm + '/min · ' + v.tidal_ml + ' ml' + (mn.ppv_percent != null ? ' · PPV a monitor ' + mn.ppv_percent + ' %' : ' · PPV ' + v.ppv_percent + ' %'))
      : v.mode === 'bag' ? 'pallone' + (v.advanced_airway ? ' su via aerea avanzata' : '') + ' · usa «Ventila» a ogni insufflazione' : 'respiro spontaneo';
    // limiti separati e ritardi
    var L = s.alarm_limits || {};
    if (!X.dirty.lim) XLIM.forEach(function (k) {
      if (xFocus('x_' + k) || L[k] === undefined) return;
      $('x_' + k).value = L[k] === 0 && /map|spo2_high|temp/.test(k) ? '' : (/^temp/.test(k) ? (L[k] / 10).toFixed(1) : L[k]);
    });
    var D = s.alarm_delays || {};
    if (!X.dirty.delay) XDELAY.forEach(function (d) { if (!xFocus('dl_' + d[0]) && D[d[0]] !== undefined) $('dl_' + d[0]).value = D[d[0]]; });
    // distrattori
    xSeg('elSeg', s.ecg_electrode_off || 'none'); xSeg('noiseSeg', s.ecg_noise || 0);
    xSwSet('limbSw', s.limb_reversal); xSwSet('resolvSw', s.tech_resolvable);
    var ta = (s.technical_alarms || []).map(function (x) { return x.kind; });
    all('#techGrid button').forEach(function (b) { var o = ta.indexOf(b.dataset.k) >= 0; b.className = 'toggle ' + (o ? 'on' : 'off'); });
    var df = s.defib || {};
    xSeg('padsSeg', df.pads_poor_contact ? 'poor' : ((s && s.pads_contact) ? s.pads_contact : (X.pads === 'marginal' ? 'marginal' : 'good')));   // stato del monitor (pads_contact), ripiego per monitor vecchi
    // esami: ultimi valori
    var lb = s.labs || {}, lk = Object.keys(lb);
    $('labNow').textContent = lk.length ? 'Ultimi: ' + lk.map(function (k) { var f = XLABS.filter(function (x) { return x[0] === k; })[0]; return (f ? f[1] : k) + ' ' + lb[k]; }).join(' · ') : '';
    // AE-18 / IN-16
    if (X.wasPulse === false && s.pulse_present) X.roscAt = Date.now();
    X.wasPulse = s.pulse_present;
    var h = '';
    if (s.cpr_active && !s.alarms_muted)
      h = '<span>In RCP i monitor veri continuano a suonare per FR, EtCO₂ e SpO₂ con i limiti dell\'adulto: è normale, non è un errore del monitor.</span> <button data-a="mute2" class="act amber small">Silenzia 2 min</button>';
    else if (!X.nibpHintOff && X.roscAt && Date.now() - X.roscAt < 600000 && s.pulse_present && s.nibp_interval_min >= 10 && !on('art'))
      h = '<span>Dopo il ROSC la NIBP automatica è ancora ogni ' + s.nibp_interval_min + ' min: le linee guida chiedono una misura ogni 2–5 min finché non c\'è la linea arteriosa (promemoria per l\'istruttore).</span> <button data-a="nibp3" class="ghost small">Ogni 3 min</button> <button data-a="hide" class="ghost small">Nascondi</button>';
    var hb = $('xHelp'); if (hb) { if (hb.dataset.h !== h) { hb.dataset.h = h; hb.innerHTML = h; } hb.hidden = !h; }
  }
  initMancanti();                     // ANDROID (funzioni mancanti 22/11)

  setMode(0);
  showCard(card);
  api('GET', '/api/v1/health').then(function (h) {
    setConn(true, 'monitor raggiungibile'); $('device').textContent = '→ ' + h.device_id + (h.version && h.version !== '?' ? ' · v' + h.version : '');
  }).catch(function () { setConn(false, 'monitor non raggiungibile'); });
  if (token) showConsole(); else showLogin('');
})();
