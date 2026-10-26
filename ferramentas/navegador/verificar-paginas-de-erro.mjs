/**
 * VERIFICAÇÃO DAS PÁGINAS DE ERRO NO NAVEGADOR REAL — KRONOS.
 *
 * PROPÓSITO DE NEGÓCIO: as páginas de erro são o que a pessoa vê quando algo deu errado — e é
 * justamente quando ela menos pode ser enganada. Este roteiro prova, num Chromium de verdade, que
 * cada página abre sem erro, cabe no celular, passa na varredura das dicas e dos ícones (todo controle e
 * título com tooltip acessível) e no axe WCAG 2.2 AA; que o servidor entrega a página 404 com o
 * Request ID; que o retorno automático NÃO leva para outro site; que "Ver telemetria" abre a
 * telemetria; que a página dinâmica monta o erro pedido sem executar o `?detail=`; e que o idioma
 * segue o painel (aviso no idioma pedido, `<html lang>` em pt-BR).
 *
 * INVARIANTES DO DOMÍNIO:
 *  - nenhuma carga toca o Google (o Google é bloqueado em todas): a tradução real já é provada por
 *    verificar-i18n.mjs, e roteiro em volume dispara captcha;
 *  - a varredura é a canônica (dica-varredura.js, cópia sem edição) e o axe é o pacote axe-core;
 *  - o detector do redirecionamento é calibrado por injeção controlada: o nosso kronos-error.js
 *    com a condição de retorno ORIGINAL do kit no lugar do filtro de mesma origem — com ela o item 3
 *    TEM de vazar, senão o roteiro sai 2.
 *
 * COMPORTAMENTO EM CASO DE FALHA: sai 0 (passou), 1 (reprovou) ou 2 (não verificou: Chromium não
 * abriu, servidor fora, calibração que não discrimina). Cada linha diz o que mediu.
 *
 * USO (na pasta ferramentas/navegador, com o KRONOS ou a vitrine no ar):
 *   npm ci && npx playwright install chromium
 *   node verificar-paginas-de-erro.mjs [http://127.0.0.1:18099]
 */
import { chromium } from 'playwright';
import { readFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const AQUI = dirname(fileURLToPath(import.meta.url));
const ARGS = process.argv.slice(2);
const BASE = (ARGS.find((a) => a.startsWith('http')) || 'http://127.0.0.1:18099').replace(/\/$/, '');
const JS_NOSSO = join(AQUI, '..', '..', 'src', 'main', 'resources', 'static', 'errors', 'assets', 'kronos-error.js');
const GOOGLE = /(^|\.)(google\.com|googleapis\.com|gstatic\.com|translate\.goog)$/;
const ALHEIO = 'example.com';
const PAGINAS = ['400', '401', '403', 'vitrine', '404', '405', '408', '409', '410', '413', '415', '422', '429',
  '500', '502', '503', '504', 'offline', 'crash', 'error'];
const LARGURAS = [[1366, 900], [390, 844]];
const AXE = readFileSync(join(AQUI, 'node_modules', 'axe-core', 'axe.min.js'), 'utf8');
const VARREDURA = readFileSync(join(AQUI, 'dica-varredura.js'), 'utf8');
const VARREDURA_ICONE = readFileSync(join(AQUI, 'icone-varredura.js'), 'utf8');

const ok = [];
const falhas = [];
const naoVerificado = [];
const naoSeAplica = [];
const confere = (c, d) => (c ? ok : falhas).push(d);

let browser;
try {
  browser = await chromium.launch();
} catch (e) {
  console.log('NAO VERIFICOU: o Chromium não abriu —', e.message.split('\n')[0]);
  process.exit(2);
}

/** Contexto isolado em pt-BR, Google bloqueado, site alheio respondido localmente (para detectar a fuga). */
async function contexto({ idioma = null, largura = 1366, altura = 900, jsDoKit = null } = {}) {
  const ctx = await browser.newContext({ locale: 'pt-BR', viewport: { width: largura, height: altura } });
  await ctx.addInitScript(([valor]) => {
    try {
      if (!sessionStorage.getItem('__semeado')) {
        sessionStorage.setItem('__semeado', '1');
        if (valor) { localStorage.setItem('kronos.idioma.interface', valor); } else { localStorage.removeItem('kronos.idioma.interface'); }
      }
    } catch { /* sem armazenamento: mede o que sobrar */ }
  }, [idioma]);
  await ctx.route('**/*', (route) => {
    const url = new URL(route.request().url());
    if (url.hostname === ALHEIO || url.hostname.endsWith('.' + ALHEIO)) {
      return route.fulfill({ status: 200, contentType: 'text/html', body: '<title>SITE ALHEIO</title>fora do KRONOS' });
    }
    if (GOOGLE.test(url.hostname)) { return route.abort(); }
    if (jsDoKit && url.pathname === '/errors/assets/kronos-error.js') {
      return route.fulfill({ status: 200, contentType: 'text/javascript', body: jsDoKit });
    }
    return route.continue();
  });
  const page = await ctx.newPage();
  const erros = [];
  page.on('pageerror', (e) => erros.push('pageerror: ' + e.message));
  page.on('console', (m) => { if (m.type() === 'error' && !/ERR_FAILED|ERR_ABORTED|net::/.test(m.text())) { erros.push(m.text()); } });
  return { ctx, page, erros };
}

/** Item 3 isolado: também serve à calibração com o JS do kit. */
async function fugaDoRetorno(jsDoKit = null) {
  const { ctx, page } = await contexto({ jsDoKit });
  await page.goto(`${BASE}/errors/503.html?from=//${ALHEIO}/roubo&retry=1`, { waitUntil: 'load' });
  await page.waitForTimeout(4500);
  const destino = page.url();
  await ctx.close();
  return destino;
}

try {
  try {
    const r = await fetch(BASE + '/api/status');
    if (!r.ok) { throw new Error('HTTP ' + r.status); }
  } catch (e) {
    console.log('NAO VERIFICOU: servidor fora em', BASE, '—', e.message);
    process.exit(2);
  }

  // 1. Cada página, no desktop e no celular: sem erro, sem estouro, varreduras de dicas e ícones e axe limpos
  for (const [largura, altura] of LARGURAS) {
    const { ctx, page, erros } = await contexto({ largura, altura });
    for (const codigo of PAGINAS) {
      const antes = erros.length;
      const sufixo = codigo === 'error' ? '?code=502' : '';
      await page.goto(`${BASE}/errors/${codigo}.html${sufixo}`, { waitUntil: 'load' });
      await page.waitForTimeout(600);
      await page.addScriptTag({ content: VARREDURA }); await page.addScriptTag({ content: VARREDURA_ICONE });
      await page.addScriptTag({ content: AXE });
      const r = await page.evaluate(async () => {
        const doc = document.documentElement;
        const axeR = await window.axe.run(document, { runOnly: { type: 'tag', values: ['wcag2a', 'wcag2aa', 'wcag21a', 'wcag21aa', 'wcag22aa'] } });
        return {
          estouro: doc.scrollWidth - doc.clientWidth,
          lang: doc.getAttribute('lang'),
          varredura: window.varreduraDica().concat(window.varreduraIcone()),
          axe: axeR.violations.map((v) => v.id + ' (' + v.nodes.length + '): ' + v.nodes[0].target.join(' ')),
        };
      });
      const rotulo = `${codigo} @${largura}px`;
      confere(erros.length === antes, `1. ${rotulo}: sem erro de página` + (erros.length > antes ? ' — ' + erros[antes] : ''));
      confere(r.estouro <= 0, `1. ${rotulo}: cabe na largura (estouro ${r.estouro}px)`);
      confere(r.varredura.length === 0, `1. ${rotulo}: varredura de dicas e ícones limpa` + (r.varredura.length ? ' — ' + JSON.stringify(r.varredura[0]) : ''));
      confere(r.axe.length === 0, `1. ${rotulo}: axe WCAG 2.2 AA sem violação` + (r.axe.length ? ' — ' + r.axe.join(' | ') : ''));
      confere(r.lang === 'pt-BR', `1. ${rotulo}: <html lang> pt-BR (${r.lang})`);
    }
    await ctx.close();
  }

  // 1b. Chip de saúde lê /api/status (o KRONOS não tem /q/health)
  {
    const { ctx, page } = await contexto();
    await page.goto(`${BASE}/errors/404.html`, { waitUntil: 'load' });
    const estado = await page.waitForFunction(() => document.querySelector('.kc-status')?.getAttribute('data-state') !== 'wait',
      null, { timeout: 8000 }).then(() => page.$eval('.kc-status', (e) => e.getAttribute('data-state'))).catch(() => 'wait');
    confere(estado === 'ok', `1b. chip de saúde: "${estado}" com o backend no ar (consulta /api/status)`);
    await ctx.close();
  }

  // 2. O servidor entrega a página 404 com o Request ID (navegação a endereço inexistente). Com e
  //    sem extensão: em 15/10/2026 só o ".html" chegava ao 404 na vitrine — o endereço digitado sem
  //    extensão caía na página mínima da vitrine, e este item, que só testava o ".html", passava.
  for (const caminho of ['/pagina-que-nao-existe.html', '/pagina-que-nao-existe']) {
    const { ctx, page } = await contexto();
    const resp = await page.goto(`${BASE}${caminho}`, { waitUntil: 'load' });
    const rid = resp.headers()['x-request-id'];
    const naTela = await page.evaluate(() => ({
      code: document.documentElement.getAttribute('data-code'),
      rid: document.querySelector('[data-diag="rid"]')?.textContent,
    }));
    confere(resp.status() === 404 && naTela.code === '404', `2. ${caminho}: status ${resp.status()} e página ${naTela.code}`);
    confere(Boolean(rid) && naTela.rid === rid, `2. ${caminho}: o Request ID do cabeçalho aparece no diagnóstico (${rid} / ${naTela.rid})`);
    await ctx.close();
  }

  // 2b. Só na vitrine: navegar até uma função mostra a página "vitrine" do kit (403), com Request ID.
  //     Fora da vitrine o mesmo endereço é uma rota real, e o roteiro não a chama.
  {
    const { ctx, page } = await contexto();
    const sonda = await page.request.get(`${BASE}/api/status`);
    if (sonda.headers()['x-kronos-vitrine'] !== '1') {
      naoSeAplica.push('2b. função recusada na vitrine: este servidor não é a vitrine (sem o cabeçalho X-Kronos-Vitrine)');
    } else {
      const resp = await page.goto(`${BASE}/api/telemetria/exportar`, { waitUntil: 'load' });
      const rid = resp.headers()['x-request-id'];
      const naTela = await page.evaluate(() => ({
        code: document.documentElement.getAttribute('data-code'),
        rid: document.querySelector('[data-diag="rid"]')?.textContent,
        titulo: document.querySelector('h2, .kc-title')?.textContent?.trim(),
      }));
      confere(resp.status() === 403 && naTela.code === 'vitrine', `2b. função na vitrine: status ${resp.status()} e página ${naTela.code} ("${naTela.titulo}")`);
      confere(Boolean(rid) && naTela.rid === rid, `2b. o Request ID do cabeçalho aparece no diagnóstico (${rid} / ${naTela.rid})`);
    }
    await ctx.close();
  }

  // 3. Retorno automático com ?from=//outro-site NÃO sai do KRONOS; com rota legítima, volta para ela
  {
    const destino = await fugaDoRetorno();
    confere(new URL(destino).origin === new URL(BASE).origin, `3. ?from=//${ALHEIO}: o retorno fica no KRONOS (${destino})`);
    const { ctx, page } = await contexto();
    await page.goto(`${BASE}/errors/503.html?from=%2F%23telemetria&retry=1`, { waitUntil: 'load' });
    await page.waitForTimeout(4500);
    const tela = await page.evaluate(() => document.getElementById('panel-telemetria')?.classList.contains('active'));
    confere(page.url().endsWith('/#telemetria') && Boolean(tela), `3. ?from=/#telemetria (mesma origem): volta para a telemetria (${page.url()})`);
    await ctx.close();
  }

  // 4. "Ver telemetria" abre o painel da telemetria, não o Painel Inicial
  {
    const { ctx, page } = await contexto();
    await page.goto(`${BASE}/errors/404.html`, { waitUntil: 'load' });
    await page.click('a[data-action="telemetry"]');
    await page.waitForLoadState('load');
    await page.waitForTimeout(1500);
    const ativo = await page.evaluate(() => document.getElementById('panel-telemetria')?.classList.contains('active'));
    confere(Boolean(ativo), `4. "Ver telemetria" abre o painel da telemetria (${page.url()})`);
    await ctx.close();
  }

  // 5. Página dinâmica: monta o erro pedido, ações na ordem do catálogo, ?detail= não executa
  {
    const { ctx, page, erros } = await contexto();
    await page.goto(`${BASE}/errors/error.html?code=502&detail=${encodeURIComponent('<img src=x onerror="window.__xss=1">')}`, { waitUntil: 'load' });
    await page.waitForTimeout(800);
    const r = await page.evaluate(() => ({
      code: document.documentElement.getAttribute('data-code'),
      titulo: document.querySelector('[data-kc-campo="title"]')?.textContent,
      acoes: [...document.querySelectorAll('.kc-actions [data-action]')].filter((a) => !a.closest('[hidden]')).map((a) => a.getAttribute('data-action')),
      primaria: document.querySelector('.kc-actions .kc-btn--primary')?.getAttribute('data-action'),
      xss: window.__xss === 1,
      detalhe: document.querySelector('[data-diag="detail"]')?.textContent,
    }));
    confere(r.code === '502' && r.titulo === 'KRONOS CORE indisponível', `5. error.html?code=502 monta o 502 (${r.code}, "${r.titulo}")`);
    confere(JSON.stringify(r.acoes) === '["retry","home","telemetry"]' && r.primaria === 'retry', `5. ações do 502 na ordem do catálogo: ${r.acoes.join(', ')} (primária ${r.primaria})`);
    confere(!r.xss && (r.detalhe || '').includes('<img'), '5. ?detail= aparece como texto e não executa');
    confere(erros.length === 0, '5. sem erro de página' + (erros.length ? ' — ' + erros[0] : ''));
    await ctx.close();
  }

  // 6. Idioma: preferência inglesa com o Google bloqueado = aviso em inglês, <html lang> em pt-BR
  {
    const { ctx, page } = await contexto({ idioma: 'en-US' });
    await page.goto(`${BASE}/errors/404.html`, { waitUntil: 'load' });
    await page.waitForTimeout(2500);
    const r = await page.evaluate(() => {
      const a = document.getElementById('idioma-aviso-falha');
      return { lang: document.documentElement.getAttribute('lang'), aviso: a ? a.getAttribute('lang') + ' ' + a.textContent.slice(0, 50) : null,
        bandeiras: document.querySelectorAll('.seletor-idioma-botao[data-idioma]').length };
    });
    confere(r.bandeiras === 3, `6. as 3 bandeiras do painel estão na página (${r.bandeiras})`);
    confere(Boolean(r.aviso) && r.aviso.startsWith('en-US') && /did not respond/.test(r.aviso), `6. aviso no idioma pedido: ${r.aviso}`);
    confere(r.lang === 'pt-BR', `6. <html lang> continua pt-BR (${r.lang})`);
    await ctx.close();
  }

  // 7. Tooltip pelo teclado: foco no "?" mostra, Esc dispensa sem tirar o foco
  {
    const { ctx, page } = await contexto();
    await page.goto(`${BASE}/errors/404.html`, { waitUntil: 'load' });
    await page.focus('h2.kc-title .dica__gatilho');
    const visivel = () => page.$eval('#d-title', (t) => getComputedStyle(t).visibility === 'visible');
    const aberto = await visivel();
    await page.keyboard.press('Escape');
    const fechado = !(await visivel());
    const focoFicou = await page.evaluate(() => document.activeElement?.matches('h2.kc-title .dica__gatilho'));
    confere(aberto && fechado && focoFicou, `7. tooltip do título: abre no foco (${aberto}), Esc fecha (${fechado}) e o foco fica (${focoFicou})`);
    await ctx.close();
  }

  // 8. Todo tooltip abre DENTRO da tela e colado ao gatilho, no desktop e no celular. Nasceu de
  //    defeito medido: ancestral com transform/backdrop-filter muda a referência do position:fixed
  //    e o tooltip do "Copiar diagnóstico" abria em x=2051. A calibração reinjeta aquele CSS.
  const tooltipsForaDoLugar = async (page) => page.evaluate(async () => {
    const espera = () => new Promise((r) => setTimeout(r, 60));
    const vw = document.documentElement.clientWidth;
    const vh = window.innerHeight;
    const ruins = [];
    let medidos = 0;
    for (const alvo of document.querySelectorAll('[aria-describedby]')) {
      const dica = document.getElementById(alvo.getAttribute('aria-describedby'));
      if (!dica || dica.getAttribute('role') !== 'tooltip' || !alvo.getClientRects().length) { continue; }
      alvo.scrollIntoView({ block: 'center' });
      alvo.focus();
      await espera();
      const g = alvo.getBoundingClientRect();
      const d = dica.getBoundingClientRect();
      const visivel = getComputedStyle(dica).visibility === 'visible';
      const dentro = d.left >= 0 && d.right <= vw + 1 && d.top >= 0 && d.bottom <= vh + 1;
      const colado = Math.min(Math.abs(d.top - g.bottom), Math.abs(g.top - d.bottom)) <= 24
        && d.right >= g.left - 24 && d.left <= g.right + 24;
      medidos += 1;
      if (!visivel || !dentro || !colado) {
        ruins.push(alvo.getAttribute('aria-describedby') + ` (gatilho ${Math.round(g.left)},${Math.round(g.bottom)} → dica ${Math.round(d.left)},${Math.round(d.top)})`);
      }
      alvo.blur();
    }
    return { medidos, ruins };
  });
  for (const [largura, altura] of LARGURAS) {
    const { ctx, page } = await contexto({ largura, altura });
    await page.goto(`${BASE}/errors/503.html`, { waitUntil: 'load' });
    await page.waitForTimeout(900);
    const r = await tooltipsForaDoLugar(page);
    confere(r.medidos >= 20, `8. @${largura}px: instrumento mediu ${r.medidos} tooltips`);
    confere(r.ruins.length === 0, `8. @${largura}px: todo tooltip abre dentro da tela e colado ao gatilho` + (r.ruins.length ? ' — ' + r.ruins.slice(0, 3).join(' | ') : ''));
    await ctx.close();
  }
  {
    const { ctx, page } = await contexto({ largura: 1440, altura: 900 });
    await page.goto(`${BASE}/errors/503.html`, { waitUntil: 'load' });
    await page.addStyleTag({ content: '.kc-hero,.kc-diag{animation:kc-rise .45s ease-out both !important}.kc-top{backdrop-filter:blur(10px)}' });
    await page.waitForTimeout(900);
    const r = await tooltipsForaDoLugar(page);
    if (r.ruins.length > 0) {
      ok.push(`8. calibração: com o CSS antigo ${r.ruins.length} tooltips saem do lugar (o detector discrimina)`);
    } else {
      naoVerificado.push('8. calibração: com o CSS antigo nenhum tooltip saiu do lugar — o detector não discrimina');
    }
    await ctx.close();
  }

  // 0. Calibração por injeção controlada: o nosso JS com a condição de retorno ORIGINAL do kit
  //    (`if (from && from.charAt(0) === '/') { return from; }`) no lugar do filtro de mesma origem.
  //    Com ela o item 3 TEM de vazar; se não vazar, o detector não discrimina.
  {
    const nosso = readFileSync(JS_NOSSO, 'utf8');
    // \r?\n: no Windows o checkout pode trazer o arquivo em CRLF, e o trecho tem de casar do mesmo jeito
    const doKit = nosso.replace(/if \(from && from\.charAt\(0\) === '\/'\) \{\r?\n\s*var destino = mesmaOrigem\(from\);\r?\n\s*if \(destino\) \{ return destino; \}\r?\n\s*\}/,
      "if (from && from.charAt(0) === '/') { return from; }");
    if (doKit === nosso) {
      naoVerificado.push('0. calibração: o trecho do filtro de retorno mudou — atualize a injeção deste roteiro');
    } else {
      const destino = await fugaDoRetorno(doKit);
      if (new URL(destino).hostname === ALHEIO) {
        ok.push(`0. calibração: com a condição do kit o retorno vaza para ${destino} (o detector discrimina)`);
      } else {
        naoVerificado.push(`0. calibração: com a condição do kit o retorno NÃO vazou (${destino}) — o detector não discrimina`);
      }
    }
  }
} catch (e) {
  falhas.push('exceção: ' + String(e.message || e).split(/\r?\n/)[0]);
} finally {
  await browser.close();
}

ok.forEach((d) => console.log('ok    ' + d));
naoVerificado.forEach((d) => console.log('NAO VERIFICOU ' + d));
naoSeAplica.forEach((d) => console.log('nao se aplica ' + d));
falhas.forEach((d) => console.log('FALHA ' + d));
console.log(`\n${ok.length} ok, ${falhas.length} falhas, ${naoVerificado.length} não verificados — ${BASE}`);
process.exit(falhas.length ? 1 : (naoVerificado.length || ok.length === 0 ? 2 : 0));
