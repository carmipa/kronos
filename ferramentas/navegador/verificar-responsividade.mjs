/**
 * VERIFICAÇÃO DA RESPONSIVIDADE E DAS DICAS NO NAVEGADOR REAL — KRONOS.
 *
 * PROPÓSITO DE NEGÓCIO: o painel tem de servir do celular de 320 px ao computador, com o menu em
 * gaveta no celular, nada passando da borda da tela, alvos de toque do tamanho do dedo e toda dica
 * (tooltip) acessível pelo teclado, pelo toque e pelo leitor de tela. Este roteiro prova isso num
 * Chromium de verdade, contra o que o servidor entrega (não contra o disco), painel por painel.
 *
 * INVARIANTES DO DOMÍNIO:
 *  - mede os 19 painéis do menu (menos "Sair") em 320, 360, 390, 768 (toque), 1024 (toque) e 1366;
 *  - estouro: largura rolável do documento nunca passa da janela;
 *  - gaveta (até 992 px): abre por cima, foco na própria gaveta, item fecha e mostra o painel com o
 *    foco no título, Esc em dois tempos quando há dica aberta, conteúdo atrás inerte, alargar a
 *    janela devolve o menu lateral;
 *  - toque (pointer: coarse): nenhum controle abaixo de 24 px; botão e campo com 44 px de altura;
 *  - dica: no foco ela aparece dentro da tela, colada ao controle, e o Esc a esconde sem tirar o foco;
 *  - varredura canônica da regra 6-A (dica-varredura.js, cópia sem edição) e axe WCAG 2.2 AA limpos;
 *  - cada detector é calibrado por injeção de um defeito conhecido no mesmo navegador: se não acusar,
 *    o roteiro não aprova nada (sai 2).
 *
 * COMPORTAMENTO EM CASO DE FALHA: sai 0 (passou), 1 (reprovou) ou 2 (não verificou: Chromium não
 * abriu, servidor fora, calibração que não discrimina). Cada linha diz o que mediu.
 *
 * USO (na pasta ferramentas/navegador, com o KRONOS ou a vitrine no ar):
 *   npm ci && npx playwright install chromium
 *   node verificar-responsividade.mjs [http://127.0.0.1:18099] [--disco]
 * --disco: serve o HTML/CSS/JS da árvore de trabalho (src/main/resources/static) e usa o servidor só
 * para a API — para conferir antes de empacotar. A prova de entrega é SEM --disco (o que o servidor
 * entrega de fato).
 */
import { chromium } from 'playwright';
import { readFileSync, existsSync } from 'node:fs';
import { dirname, extname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const AQUI = dirname(fileURLToPath(import.meta.url));
const BASE = (process.argv.slice(2).find((a) => a.startsWith('http')) || 'http://127.0.0.1:18099').replace(/\/$/, '');
const AXE = readFileSync(join(AQUI, 'node_modules', 'axe-core', 'axe.min.js'), 'utf8');
const VARREDURA = readFileSync(join(AQUI, 'dica-varredura.js'), 'utf8');
const EXTERNO = /(^|\.)(google\.com|googleapis\.com|gstatic\.com|translate\.goog|shields\.io|github\.com|githubusercontent\.com)$/;
const LARGURAS = [[320, false], [360, false], [390, false], [768, true], [1024, true], [1366, false]];
const TAGS_AXE = ['wcag2a', 'wcag2aa', 'wcag21a', 'wcag21aa', 'wcag22aa'];
const DISCO = process.argv.includes('--disco');
const ESTATICO = join(AQUI, '..', '..', 'src', 'main', 'resources', 'static');
const TIPOS = { '.html': 'text/html; charset=utf-8', '.js': 'text/javascript', '.css': 'text/css', '.svg': 'image/svg+xml',
  '.woff2': 'font/woff2', '.png': 'image/png', '.webp': 'image/webp', '.ico': 'image/x-icon' };

const ok = [];
const falhas = [];
const naoVerificado = [];
const confere = (c, d) => (c ? ok : falhas).push(d);

let browser;
try {
  browser = await chromium.launch();
} catch (e) {
  console.log('NAO VERIFICOU: o Chromium não abriu —', e.message.split('\n')[0]);
  process.exit(2);
}
try {
  const r = await fetch(BASE + '/api/status');
  if (!r.ok) { throw new Error('HTTP ' + r.status); }
} catch (e) {
  console.log('NAO VERIFICOU: servidor fora —', e.message);
  await browser.close();
  process.exit(2);
}

/** Página em pt-BR com tudo de fora bloqueado (o painel não pode depender de rede). */
async function abrir(largura, toque) {
  const ctx = await browser.newContext({ locale: 'pt-BR', viewport: { width: largura, height: 800 }, hasTouch: toque || largura < 900, isMobile: toque || largura < 900, reducedMotion: 'reduce' });
  await ctx.addInitScript(() => { try { localStorage.setItem('kronos.idioma.interface', 'pt-BR'); } catch { /* sem armazenamento */ } });
  await ctx.route('**/*', (route) => {
    const url = new URL(route.request().url());
    if (EXTERNO.test(url.hostname)) { return route.abort(); }
    if (DISCO && url.origin === BASE && !url.pathname.startsWith('/api/')) {
      const arq = join(ESTATICO, decodeURIComponent(url.pathname === '/' ? '/index.html' : url.pathname));
      if (existsSync(arq)) { return route.fulfill({ status: 200, contentType: TIPOS[extname(arq)] || 'application/octet-stream', body: readFileSync(arq) }); }
    }
    return route.continue();
  });
  const page = await ctx.newPage();
  const erros = [];
  page.on('pageerror', (e) => erros.push(e.message));
  await page.goto(BASE + '/', { waitUntil: 'load' });
  await page.waitForTimeout(1500);
  return { ctx, page, erros };
}
const paineis = (page) => page.evaluate(() => [...document.querySelectorAll('.nav-item[data-target]')]
  .filter((b) => b.id !== 'btn-menu-sair').map((b) => b.getAttribute('data-target')));
const mostrar = async (page, alvo) => {
  await page.evaluate((a) => document.querySelector(`.nav-item[data-target="${a}"]`).click(), alvo);
  await page.waitForTimeout(450);
};

/* ---------- detectores (os mesmos rodam na calibração) ---------- */
const estouro = (page) => page.evaluate(() => ({ sw: document.documentElement.scrollWidth, vw: document.documentElement.clientWidth }));
const alvosPequenos = (page) => page.evaluate(() => {
  const SEL = 'button, a[href], input:not([type=hidden]), select, textarea, [role=button], [role=tab]';
  const raizes = [document.querySelector('.barra-movel'), document.querySelector('.main-header'), document.querySelector('.panel.active'), document.querySelector('.app-footer')].filter(Boolean);
  const out = [];
  for (const raiz of raizes) {
    for (const el of raiz.querySelectorAll(SEL)) {
      const cs = getComputedStyle(el);
      if (!el.getClientRects().length || cs.visibility === 'hidden' || cs.display === 'none') { continue; }
      const b = el.getBoundingClientRect();
      if (!b.width || !b.height) { continue; }
      if (el.tagName === 'A' && cs.display === 'inline') {
        let bloco = el.parentElement;
        while (bloco && getComputedStyle(bloco).display === 'inline') { bloco = bloco.parentElement; }
        if (((bloco ? bloco.textContent : '').replace(el.textContent, '').trim()).length > 15) { continue; }
      }
      const caixa = el.matches('input[type=checkbox], input[type=radio]');
      const piso = caixa ? 24 : (el.matches('button, select, input') ? 44 : 24);
      const medida = el.matches('button, select, input') && !caixa ? b.height : Math.min(b.width, b.height);
      if (medida + 0.5 < piso || Math.min(b.width, b.height) + 0.5 < 24) {
        out.push(`${el.tagName.toLowerCase()}${el.id ? '#' + el.id : ''}.${[...el.classList].slice(0, 2).join('.')} ${Math.round(b.width)}x${Math.round(b.height)}`);
      }
    }
  }
  return out;
});
const dicasRuins = (page) => page.evaluate(async () => {
  const raizes = [document.querySelector('.panel.active'), document.querySelector('.main-header')].filter(Boolean);
  const lista = raizes.flatMap((r) => [...r.querySelectorAll('.dica')]).filter((d) => {
    const g = d.querySelector(':scope > [data-dica], :scope > .dica__gatilho');
    return g && g.getClientRects().length && getComputedStyle(g).visibility !== 'hidden' && !g.disabled;
  });
  const ruins = [];
  for (const d of lista) {
    const g = d.querySelector(':scope > [data-dica], :scope > .dica__gatilho');
    const t = d.querySelector(':scope > .dica__texto');
    g.focus();
    await new Promise((ok) => setTimeout(ok, 40));
    const vw = document.documentElement.clientWidth;
    const a = d.getBoundingClientRect();
    const q = t.getBoundingClientRect();
    const cs = getComputedStyle(t);
    const visivel = cs.visibility === 'visible' && cs.opacity !== '0';
    const dentro = q.left >= 0 && q.right <= vw + 0.5 && q.top >= 0 && q.bottom <= innerHeight + 0.5;
    const colado = Math.min(Math.abs(q.top - a.bottom), Math.abs(a.top - q.bottom)) <= 12;
    document.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', bubbles: true }));
    await new Promise((ok) => setTimeout(ok, 20));
    const esc = getComputedStyle(t).visibility === 'hidden' && document.activeElement === g;
    g.blur();
    if (!(visivel && dentro && colado && esc)) {
      ruins.push(`"${(g.getAttribute('aria-label') || g.textContent || '').trim().slice(0, 30)}" visivel=${visivel} dentro=${dentro} colado=${colado} esc=${esc}`);
    }
  }
  return { total: lista.length, ruins };
});
const varreduraEaxe = (page) => page.evaluate(async (tags) => {
  const raizes = [document.querySelector('.panel.active'), document.querySelector('.main-header')].filter(Boolean);
  // eslint-disable-next-line no-undef
  const v = raizes.flatMap((r) => varreduraDica(r)).map((f) => `${f.elemento} — ${f.problema}`);
  const axeR = await window.axe.run(document.querySelector('.panel.active') || document, { runOnly: { type: 'tag', values: tags } });
  return { v, axe: axeR.violations.map((x) => `${x.id} (${x.nodes.length}): ${x.nodes[0].target.join(' ')}`) };
}, TAGS_AXE);

/* ---------- 1. estouro, alvos, dicas, varredura e axe por largura e painel ---------- */
for (const [largura, toque] of LARGURAS) {
  const { ctx, page, erros } = await abrir(largura, toque);
  await page.addScriptTag({ content: AXE });
  await page.addScriptTag({ content: VARREDURA });
  const lista = await paineis(page);
  const est = []; const alv = []; const dic = []; const varr = []; const ax = []; let nDicas = 0;
  for (const alvo of lista) {
    await mostrar(page, alvo);
    const e = await estouro(page);
    if (e.sw > e.vw + 1) { est.push(`${alvo} (${e.sw} em ${e.vw})`); }
    if (largura === 390) { for (const x of await alvosPequenos(page)) { alv.push(`${alvo}: ${x}`); } }
    if (largura === 390 || largura === 1366) {
      const d = await dicasRuins(page);
      nDicas += d.total;
      for (const x of d.ruins) { dic.push(`${alvo}: ${x}`); }
      const r = await varreduraEaxe(page);
      for (const x of r.v) { varr.push(`${alvo}: ${x}`); }
      for (const x of r.axe) { ax.push(`${alvo}: ${x}`); }
    }
  }
  const rot = `${largura}px${toque ? ' com toque' : ''}`;
  confere(est.length === 0, `1. ${rot}: nada passa da borda nos ${lista.length} painéis` + (est.length ? ' — ' + est.join(' | ') : ''));
  if (largura === 390) { confere(alv.length === 0, `2. ${rot}: alvos de toque (botão/campo 44 px, nada abaixo de 24)` + (alv.length ? ' — ' + alv.slice(0, 12).join(' | ') : '')); }
  if (largura === 390 || largura === 1366) {
    confere(nDicas > 0 && dic.length === 0, `3. ${rot}: ${nDicas} dicas abrem no foco, dentro da tela, coladas, e o Esc fecha` + (dic.length ? ' — ' + dic.slice(0, 10).join(' | ') : ''));
    confere(varr.length === 0, `4. ${rot}: varredura da regra 6-A limpa` + (varr.length ? ` — ${varr.length}: ` + varr.slice(0, 10).join(' | ') : ''));
    confere(ax.length === 0, `5. ${rot}: axe WCAG 2.2 AA sem violação` + (ax.length ? ' — ' + ax.slice(0, 8).join(' | ') : ''));
  }
  confere(erros.length === 0, `6. ${rot}: nenhum erro de página` + (erros.length ? ' — ' + erros.slice(0, 3).join(' | ') : ''));
  await ctx.close();
}

/* ---------- 7. gaveta do menu (390 px, toque) ---------- */
{
  const { ctx, page, erros } = await abrir(390, true);
  const estado = () => page.evaluate(() => {
    const s = document.querySelector('.sidebar');
    return { aberta: s.classList.contains('aberta'), left: Math.round(s.getBoundingClientRect().left), vis: getComputedStyle(s).visibility,
      exp: document.getElementById('btn-menu-movel').getAttribute('aria-expanded'), inert: document.querySelector('.main-content').inert,
      foco: document.activeElement && document.activeElement.id, painel: document.querySelector('.panel.active')?.id };
  });
  const dicaAberta = () => page.evaluate(() => [...document.querySelectorAll('.sidebar .dica__texto')].some((t) => getComputedStyle(t).visibility === 'visible'));
  let e = await estado();
  confere(!e.aberta && e.vis === 'hidden' && e.left < 0, '7. gaveta: começa fechada e fora da tela');
  await page.click('#btn-menu-movel'); await page.waitForTimeout(450);
  e = await estado();
  confere(e.aberta && e.left === 0 && e.exp === 'true' && e.inert && e.foco === 'menu-lateral', '7. gaveta: o botão Menu abre por cima, conteúdo inerte, foco na gaveta' + (e.foco === 'menu-lateral' ? '' : ' — foco em ' + e.foco));
  await page.keyboard.press('Tab'); await page.waitForTimeout(250);
  const comDica = await dicaAberta();
  await page.keyboard.press('Escape'); await page.waitForTimeout(250);
  const umEsc = await estado();
  await page.keyboard.press('Escape'); await page.waitForTimeout(450);
  e = await estado();
  confere(comDica && umEsc.aberta && !e.aberta && e.foco === 'btn-menu-movel', '7. gaveta: Esc em dois tempos (1º fecha a dica, 2º a gaveta) e o foco volta ao Menu');
  await page.click('#btn-menu-movel'); await page.waitForTimeout(450);
  await page.evaluate(() => { const g = document.querySelector('[data-grupo="sistema"]'); g.classList.remove('fechado'); });
  await page.click('#btn-menu-telemetria'); await page.waitForTimeout(450);
  e = await estado();
  confere(!e.aberta && e.painel === 'panel-telemetria' && e.foco === 'page-title' && !e.inert, '7. gaveta: escolher a tela fecha a gaveta, mostra o painel e leva o foco ao título');
  await page.click('#btn-menu-movel'); await page.waitForTimeout(450);
  await page.mouse.click(385, 400); await page.waitForTimeout(450);
  confere(!(await estado()).aberta, '7. gaveta: tocar fora fecha');
  await page.click('#btn-menu-movel'); await page.waitForTimeout(450);
  await page.setViewportSize({ width: 1366, height: 800 }); await page.waitForTimeout(450);
  e = await estado();
  confere(!e.aberta && !e.inert && e.vis === 'visible' && e.left === 0, '7. gaveta: alargar a janela com ela aberta volta ao menu lateral e libera o conteúdo');
  confere(erros.length === 0, '7. gaveta: nenhum erro de página' + (erros.length ? ' — ' + erros.join(' | ') : ''));
  await ctx.close();
}

/* ---------- 0. calibração: cada detector tem de acusar um defeito plantado ---------- */
{
  const { ctx, page } = await abrir(390, true);
  await page.addScriptTag({ content: AXE });
  await page.addScriptTag({ content: VARREDURA });
  await mostrar(page, 'analise');
  await page.evaluate(() => {
    const p = document.querySelector('.panel.active');
    const largo = document.createElement('div'); largo.style.width = '2000px'; largo.style.height = '4px'; p.append(largo);
    const mini = document.createElement('button'); mini.type = 'button'; mini.textContent = 'x';
    mini.style.cssText = 'min-height:0!important;height:12px;width:12px;padding:0;font-size:8px'; mini.setAttribute('aria-label', 'Plantado'); p.append(mini);
  });
  const e = await estouro(page);
  const a = await alvosPequenos(page);
  const r = await varreduraEaxe(page);
  const cal = [
    [e.sw > e.vw + 1, 'estouro', `div de 2000 px deu largura ${e.sw}`],
    [a.some((x) => x.includes('12x12')), 'alvo de toque', 'botão de 12x12 px'],
    [r.v.some((x) => x.includes('Plantado') || x.includes('sem tooltip')), 'varredura 6-A', 'botão sem dica'],
  ];
  await page.evaluate(() => { const s = document.createElement('style'); s.textContent = '.panel.active { transform: translateX(0); }'; document.head.append(s); });
  const d = await dicasRuins(page);
  cal.push([d.ruins.length > 0, 'posição da dica', `transform no painel (${d.ruins.length} de ${d.total} saem do lugar)`]);
  await page.evaluate(() => { const p = document.querySelector('.panel.active'); const f = document.createElement('p'); f.textContent = 'contraste plantado'; f.style.cssText = 'color:#111;background:#000'; p.append(f); });
  const r2 = await varreduraEaxe(page);
  cal.push([r2.axe.some((x) => x.startsWith('color-contrast')), 'axe', 'texto #111 sobre #000']);
  for (const [acusou, nome, como] of cal) {
    (acusou ? ok : naoVerificado).push(`0. calibração do detector de ${nome}: ${acusou ? 'acusou' : 'NÃO acusou'} o defeito plantado (${como})`);
  }
  await ctx.close();
}

await browser.close();
for (const l of ok) { console.log('  ok   ', l); }
for (const l of falhas) { console.log('  FALHA', l); }
for (const l of naoVerificado) { console.log('  NAO VERIFICOU', l); }
console.log(`\n${ok.length} ok, ${falhas.length} falha(s), ${naoVerificado.length} não verificado(s)`);
process.exit(naoVerificado.length ? 2 : (falhas.length ? 1 : 0));
