/* ============================================================================
   KRONOS CORE — Páginas de erro — kronos-error.js
   Versão: 1.1.0 (adaptação ao KRONOS do kit v1.0.0) | Autor: Paulo André Carminati
   ----------------------------------------------------------------------------
   Responsabilidades (apenas lógica; os dados ficam em kronos-error-catalog.js):
     1. Resolver o código do erro (estático via <html data-code> ou dinâmico
        via error.html?code=404).
     2. Painel de diagnóstico: request-id, horário, rota, origem, navegador,
        idioma, com botão "Copiar diagnóstico".
     3. Relógio e verificação do backend (GET em /api/status).
     4. Auto-retry com contagem regressiva e backoff para 5xx / 408 / 429 /
        offline; retoma automaticamente quando a conexão volta.
     5. Botões de ação.

   O que NÃO mora aqui, por regra do projeto:
     - idioma: as bandeiras são as do painel (/i18n/i18n.js, Google Tradutor),
       sem dicionário — este script só lê o idioma em que a página está;
     - tooltip: componente canônico (/dica/dica.js) — o JS de tooltip do kit saiu.

   Configuração (antes deste script):
     <script>window.KC_CONFIG = { home:'/', telemetry:'/#telemetria',
                                  health:'/api/status', repo:'…' }</script>
   Parâmetros de URL aceitos:
     ?code=404      (apenas error.html)
     ?rid=<id>      request-id vindo do servidor
     ?from=<rota>   rota de origem NESTE site (volta para ela ao recuperar)
     ?retry=<seg>   segundos para a primeira nova tentativa (ex.: Retry-After)
     ?detail=<txt>  detalhe técnico extra exibido no diagnóstico
   ========================================================================== */
(function () {
  'use strict';

  var CFG = Object.assign({
    home: '/',
    telemetry: '/#telemetria',
    health: '/api/status',
    repo: '',
    retryDefault: 30,
    retryMax: 120,
    retryAttempts: 10,
    probeTimeout: 4000
  }, window.KC_CONFIG || {});

  var CAT = window.KC_CATALOG;
  if (!CAT) { return; } /* catálogo ausente: a página estática continua legível */

  var root = document.documentElement;
  var q = new URLSearchParams(location.search);
  var $ = function (sel, ctx) { return (ctx || document).querySelector(sel); };
  var $$ = function (sel, ctx) { return Array.prototype.slice.call((ctx || document).querySelectorAll(sel)); };
  var UI = CAT.ui;

  /* ------------------------------------------------------------------------
     1. CÓDIGO DO ERRO
     ---------------------------------------------------------------------- */
  var code = (root.getAttribute('data-code') || '').toLowerCase();
  if (root.getAttribute('data-dynamic') === 'true') {
    code = (q.get('code') || '500').toLowerCase();
    if (!CAT.errors[code]) { code = '500'; }
    root.setAttribute('data-code', code);
    renderDynamic(code);
  }
  var E = CAT.errors[code] || CAT.errors['500'];

  /**
   * PROPÓSITO DE NEGÓCIO: monta a página dinâmica (error.html?code=) a partir do catálogo, para o
   * front poder mostrar qualquer erro sem uma página por código.
   * INVARIANTES DO DOMÍNIO: texto só por textContent (o ícone vem do catálogo, que é estático e
   * versionado); ficam visíveis só as ações do erro, na ordem do catálogo, e a primeira é a primária.
   * COMPORTAMENTO EM CASO DE FALHA: elemento ausente na página é ignorado, campo a campo.
   */
  function renderDynamic(c) {
    var e = CAT.errors[c];
    var tile = $('.kc-tile');
    if (tile && CAT.icons[e.icon]) { tile.innerHTML = CAT.icons[e.icon]; }
    $$('[data-kc-campo]').forEach(function (el) {
      var campo = el.getAttribute('data-kc-campo');
      el.textContent = campo === 'display' ? e.display : (e.texto[campo] || '');
    });
    var wm = $('.kc-watermark');
    if (wm) { wm.textContent = e.mark; }
    var dCode = $('#d-code');
    if (dCode) { dCode.textContent = e.texto.codeTip; }
    var dTitle = $('#d-title');
    if (dTitle) { dTitle.textContent = e.texto.titleTip; }
    var nav = $('.kc-actions');
    $$('.kc-actions [data-action]').forEach(function (b) {
      var a = b.getAttribute('data-action');
      var idx = e.actions.indexOf(a);
      var envoltorio = b.closest('.dica') || b;
      envoltorio.hidden = idx === -1;
      b.classList.remove('kc-btn--primary', 'kc-btn--ghost', 'kc-btn--link');
      b.classList.add(idx === 0 ? 'kc-btn--primary' : (a === 'telemetry' ? 'kc-btn--link' : 'kc-btn--ghost'));
    });
    e.actions.forEach(function (a) {
      var b = $('.kc-actions [data-action="' + a + '"]');
      if (b && nav) { nav.appendChild(b.closest('.dica') || b); }
    });
    var retryBox = $('.kc-retry');
    if (retryBox) { retryBox.hidden = !e.retry; }
    var pill = $('[data-diag="status"]');
    if (pill) { pill.textContent = e.http; }
    document.title = e.display + ' · ' + e.texto.title + ' · KRONOS CORE';
  }

  /**
   * PROPÓSITO DE NEGÓCIO: diz em que idioma a página está sendo mostrada, para o relógio e o
   * diagnóstico. Quem traduz é o Google, e ele mesmo troca o lang do documento ao terminar.
   * INVARIANTES DO DOMÍNIO: nunca escreve no lang; só o lê.
   * COMPORTAMENTO EM CASO DE FALHA: sem atributo, responde pt-BR, que é o idioma do HTML servido.
   */
  function idiomaPagina() {
    return root.getAttribute('lang') || 'pt-BR';
  }

  /* ------------------------------------------------------------------------
     2. DIAGNÓSTICO
     ---------------------------------------------------------------------- */
  function meta(name) {
    var m = $('meta[name="' + name + '"]');
    return m ? (m.getAttribute('content') || '') : '';
  }

  function rid() {
    /* prioridade: injetado pelo servidor (<meta name="kc-rid">) > ?rid= > gerado */
    var given = meta('kc-rid') || q.get('rid');
    if (given) { return given.slice(0, 64); }
    var a = Math.random().toString(16).slice(2, 6).toUpperCase();
    var b = Date.now().toString(36).toUpperCase().slice(-6);
    return 'KC-' + a + '-' + b;
  }

  function shortUA() {
    var ua = navigator.userAgent || '';
    var m = ua.match(/(Edg|OPR|Chrome|Firefox|Safari)\/([\d.]+)/);
    var os = /Windows/.test(ua) ? 'Windows' : /Mac OS/.test(ua) ? 'macOS' : /Android/.test(ua) ? 'Android' : /Linux/.test(ua) ? 'Linux' : /iPhone|iPad/.test(ua) ? 'iOS' : '—';
    if (!m) { return os; }
    var name = { Edg: 'Edge', OPR: 'Opera' }[m[1]] || m[1];
    return name + ' ' + m[2].split('.')[0] + ' · ' + os;
  }

  var DIAG = {
    status: E.http || '—',
    ref: 'KC-' + E.display.replace(/\s+/g, ''),
    rid: rid(),
    time: new Date().toISOString(),
    route: q.get('from') || (location.pathname + location.search),
    origin: document.referrer || '—',
    ua: shortUA(),
    lang: idiomaPagina(),
    detail: (q.get('detail') || '').slice(0, 500)
  };

  Object.keys(DIAG).forEach(function (k) {
    $$('[data-diag="' + k + '"]').forEach(function (el) {
      if (k === 'detail') {
        var row = el.closest('[data-diag-row]');
        if (!DIAG.detail) { if (row) { row.hidden = true; } return; }
        if (row) { row.hidden = false; }
      }
      el.textContent = DIAG[k];
    });
  });

  /* O Google troca o lang do documento quando termina de traduzir: o campo Idioma acompanha. */
  if (window.MutationObserver) {
    new MutationObserver(function () {
      DIAG.lang = idiomaPagina();
      $$('[data-diag="lang"]').forEach(function (el) { el.textContent = DIAG.lang; });
      tickClock();
    }).observe(root, { attributes: true, attributeFilter: ['lang'] });
  }

  var copyBtn = $('[data-action="copy"]');
  if (copyBtn) {
    copyBtn.addEventListener('click', function () {
      var lines = [
        'KRONOS CORE — ' + UI['diag.title'],
        UI['diag.status'] + ': ' + DIAG.status,
        UI['diag.ref'] + ': ' + DIAG.ref,
        UI['diag.rid'] + ': ' + DIAG.rid,
        UI['diag.time'] + ': ' + DIAG.time,
        UI['diag.route'] + ': ' + DIAG.route,
        UI['diag.origin'] + ': ' + DIAG.origin,
        UI['diag.ua'] + ': ' + DIAG.ua,
        UI['diag.lang'] + ': ' + DIAG.lang
      ];
      if (DIAG.detail) { lines.push(UI['diag.detail'] + ': ' + DIAG.detail); }
      var text = lines.join('\n');
      var done = function () {
        copyBtn.setAttribute('data-done', 'true');
        var label = $('[data-kc-rotulo]', copyBtn);
        if (label) { label.textContent = UI['btn.copied']; }
        setTimeout(function () {
          copyBtn.removeAttribute('data-done');
          if (label) { label.textContent = UI['btn.copy']; }
        }, 2200);
      };
      if (navigator.clipboard && navigator.clipboard.writeText) {
        navigator.clipboard.writeText(text).then(done, function () { fallbackCopy(text); done(); });
      } else { fallbackCopy(text); done(); }
    });
  }

  function fallbackCopy(text) {
    var ta = document.createElement('textarea');
    ta.value = text; ta.setAttribute('readonly', '');
    ta.style.position = 'fixed'; ta.style.opacity = '0';
    document.body.appendChild(ta); ta.select();
    try { document.execCommand('copy'); } catch (_) {}
    document.body.removeChild(ta);
  }

  /* ------------------------------------------------------------------------
     3. RELÓGIO E ESTADO DO BACKEND
     ---------------------------------------------------------------------- */
  var clock = $('.kc-clock');

  /**
   * PROPÓSITO DE NEGÓCIO: mostra data e hora locais no idioma da página.
   * INVARIANTES DO DOMÍNIO: o relógio é translate="no" — formatado aqui, para o tradutor não ser
   * chamado a cada segundo.
   * COMPORTAMENTO EM CASO DE FALHA: idioma que o navegador não formata cai em pt-BR.
   */
  function tickClock() {
    if (!clock) { return; }
    var d = new Date();
    var idioma = idiomaPagina();
    try {
      clock.textContent = d.toLocaleDateString(idioma, { day: '2-digit', month: 'short' }) + ' · ' +
        d.toLocaleTimeString(idioma, { hour: '2-digit', minute: '2-digit', second: '2-digit' });
    } catch (_) {
      clock.textContent = d.toLocaleString('pt-BR');
    }
  }
  setInterval(tickClock, 1000);

  var statusEl = $('.kc-status');
  var statusDica = $('#d-status');
  var statusState = 'wait';

  /**
   * PROPÓSITO DE NEGÓCIO: diz se o orquestrador responde, para a pessoa saber se adianta tentar.
   * INVARIANTES DO DOMÍNIO: três estados (verificando, online, inacessível); o texto do tooltip
   * acompanha o estado.
   * COMPORTAMENTO EM CASO DE FALHA: sem o chip na página, só guarda o estado.
   */
  function renderStatus(state) {
    statusState = state;
    if (!statusEl) { return; }
    statusEl.setAttribute('data-state', state);
    var label = $('span', statusEl);
    if (label) { label.textContent = UI['status.' + state]; }
    if (statusDica) { statusDica.textContent = UI['tip.status.' + state]; }
  }

  /**
   * PROPÓSITO DE NEGÓCIO: pergunta ao KRONOS se ele está no ar.
   * INVARIANTES DO DOMÍNIO: GET em CFG.health (/api/status — o KRONOS não tem /q/health), sem
   * cache e com prazo; só HTTP 2xx conta como online.
   * COMPORTAMENTO EM CASO DE FALHA: rede, prazo ou status de erro respondem false; nunca lança.
   */
  function probe() {
    if (!CFG.health || !window.fetch) { return Promise.resolve(false); }
    var ctrl = window.AbortController ? new AbortController() : null;
    var timer = setTimeout(function () { if (ctrl) { ctrl.abort(); } }, CFG.probeTimeout);
    return fetch(CFG.health, { cache: 'no-store', headers: { Accept: 'application/json' }, signal: ctrl ? ctrl.signal : undefined })
      .then(function (r) { clearTimeout(timer); return r.ok; })
      .catch(function () { clearTimeout(timer); return false; });
  }

  function checkBackend() {
    renderStatus('wait');
    return probe().then(function (ok) { renderStatus(ok ? 'ok' : 'bad'); return ok; });
  }

  /* ------------------------------------------------------------------------
     4. AUTO-RETRY
     ---------------------------------------------------------------------- */
  var retryBox = $('.kc-retry');
  var retryMsg = retryBox && $('[data-retry="msg"]', retryBox);
  var retryBar = retryBox && $('.kc-bar i', retryBox);
  var retryNow = retryBox && $('[data-action="retry-now"]', retryBox);
  var retryToggle = retryBox && $('[data-action="retry-toggle"]', retryBox);
  var retryToggleDica = $('#d-retry-toggle');

  var R = { total: 0, left: 0, attempts: 0, paused: false, timer: null, exhausted: false, busy: false };

  /**
   * PROPÓSITO DE NEGÓCIO: decide para onde voltar quando o backend responde de novo.
   * INVARIANTES DO DOMÍNIO: só rota DESTE site. `?from=//outro-site` e `?from=/\outro-site` começam
   * com "/" mas o navegador os resolve para outro domínio — redirecionamento aberto no kit v1.0.0.
   * A rota é resolvida contra a origem atual e recusada se a origem mudar.
   * COMPORTAMENTO EM CASO DE FALHA: valor inválido ou de outra origem cai no referrer da mesma
   * origem e, por fim, na home.
   */
  function retryTarget() {
    var mesmaOrigem = function (valor) {
      try {
        var u = new URL(valor, location.origin);
        return u.origin === location.origin ? u.pathname + u.search + u.hash : null;
      } catch (_) { return null; }
    };
    var from = q.get('from');
    if (from && from.charAt(0) === '/') {
      var destino = mesmaOrigem(from);
      if (destino) { return destino; }
    }
    if (document.referrer) {
      var volta = mesmaOrigem(document.referrer);
      if (volta) { return volta; }
    }
    return CFG.home;
  }

  /**
   * PROPÓSITO DE NEGÓCIO: mostra a contagem e o estado das tentativas automáticas.
   * INVARIANTES DO DOMÍNIO: a frase fica fixa e só o número muda, num span translate="no" — com a
   * página traduzida, trocar a frase inteira a cada segundo pediria uma tradução nova ao Google
   * por segundo.
   * COMPORTAMENTO EM CASO DE FALHA: erro sem retry ou caixa ausente: não faz nada.
   */
  function renderRetry() {
    if (!retryBox || !E.retry) { return; }
    if (retryMsg) {
      if (R.exhausted || R.busy || R.paused) {
        retryMsg.textContent = UI[R.exhausted ? 'retry.exhausted' : (R.busy ? 'retry.checking' : 'retry.paused')];
      } else {
        var numero = $('[data-retry="n"]', retryMsg);
        if (!numero) {
          retryMsg.textContent = '';
          retryMsg.appendChild(document.createTextNode(UI['retry.in'] + ' '));
          numero = document.createElement('span');
          numero.setAttribute('data-retry', 'n');
          numero.setAttribute('translate', 'no');
          numero.className = 'notranslate';
          retryMsg.appendChild(numero);
        }
        numero.textContent = R.left + UI['retry.unit'];
      }
    }
    if (retryBar) { retryBar.style.width = (R.total ? Math.round(100 * (R.total - R.left) / R.total) : 0) + '%'; }
    if (retryToggle) {
      var lbl = $('[data-kc-rotulo]', retryToggle);
      if (lbl) { lbl.textContent = UI[R.paused ? 'btn.resume' : 'btn.pause']; }
      if (retryToggleDica) { retryToggleDica.textContent = UI[R.paused ? 'tip.resume' : 'tip.pause']; }
      var envoltorio = retryToggle.closest('.dica') || retryToggle;
      envoltorio.hidden = R.exhausted;
    }
  }

  function startCountdown(seconds) {
    clearInterval(R.timer);
    R.total = seconds; R.left = seconds; R.busy = false;
    renderRetry();
    R.timer = setInterval(function () {
      if (R.paused) { return; }
      R.left -= 1;
      if (R.left <= 0) { clearInterval(R.timer); attempt(); return; }
      renderRetry();
    }, 1000);
  }

  function attempt() {
    if (R.busy) { return; }
    R.busy = true; renderRetry();
    R.attempts += 1;
    checkBackend().then(function (ok) {
      if (ok) {
        if (retryMsg) { retryMsg.textContent = UI['retry.online']; }
        if (retryBar) { retryBar.style.width = '100%'; }
        setTimeout(function () { location.replace(retryTarget()); }, 900);
        return;
      }
      R.busy = false;
      if (R.attempts >= CFG.retryAttempts) { R.exhausted = true; renderRetry(); return; }
      startCountdown(Math.min(Math.round(R.total * 1.5), CFG.retryMax));
    });
  }

  if (retryBox && E.retry) {
    retryBox.hidden = false;
    var first = parseInt(q.get('retry'), 10);
    if (!(first > 0)) { first = CFG.retryDefault; }
    startCountdown(first);
    if (retryNow) { retryNow.addEventListener('click', function () { clearInterval(R.timer); R.paused = false; attempt(); }); }
    if (retryToggle) { retryToggle.addEventListener('click', function () { R.paused = !R.paused; renderRetry(); }); }
    window.addEventListener('online', function () { clearInterval(R.timer); attempt(); });
  } else if (retryBox) {
    retryBox.hidden = true;
  }

  /* ------------------------------------------------------------------------
     5. BOTÕES DE AÇÃO
     ---------------------------------------------------------------------- */
  $$('[data-action]').forEach(function (b) {
    var a = b.getAttribute('data-action');
    if (a === 'home') { b.setAttribute('href', CFG.home); }
    if (a === 'telemetry') { b.setAttribute('href', CFG.telemetry); }
    if (a === 'repo' && CFG.repo) { b.setAttribute('href', CFG.repo); }
    if (a === 'back') {
      b.addEventListener('click', function (ev) {
        ev.preventDefault();
        if (history.length > 1 && document.referrer) { history.back(); } else { location.href = CFG.home; }
      });
    }
    if (a === 'reload') {
      b.addEventListener('click', function (ev) { ev.preventDefault(); location.reload(); });
    }
    if (a === 'retry') {
      b.addEventListener('click', function (ev) {
        ev.preventDefault();
        if (E.retry) { clearInterval(R.timer); R.paused = false; attempt(); } else { location.href = retryTarget(); }
      });
    }
  });

  /* ------------------------------------------------------------------------
     INICIALIZAÇÃO
     ---------------------------------------------------------------------- */
  tickClock();
  checkBackend();
  if (code === 'offline') {
    renderStatus(navigator.onLine ? 'wait' : 'bad');
    window.addEventListener('offline', function () { renderStatus('bad'); });
  }
})();
