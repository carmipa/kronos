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
 *  - varreduras do componente compartilhado (dica-varredura.js e icone-varredura.js, cópias sem
 *    edição) e axe WCAG 2.2 AA limpos: toda dica com "?" à vista e todo rótulo com ícone;
 *  - tabela que vira cartão no celular (thead escondido): cada célula mostra, em texto, o nome da
 *    coluna do seu <th> — senão quem lê vê "22s" e "100%" sem saber de que coluna são;
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
 * --amostra: máquina sem histórico de telemetria (logs/ vazio) não tem linha para conferir a tabela em
 * cartão (item 8 sai NÃO VERIFICOU). Com --amostra, a resposta real de /api/telemetria ganha três
 * operações de exemplo SÓ quando o histórico vier vazio, e o relatório diz que a amostra foi usada.
 */
import { chromium } from 'playwright';
import { readFileSync, existsSync } from 'node:fs';
import { dirname, extname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const AQUI = dirname(fileURLToPath(import.meta.url));
const BASE = (process.argv.slice(2).find((a) => a.startsWith('http')) || 'http://127.0.0.1:18099').replace(/\/$/, '');
const AXE = readFileSync(join(AQUI, 'node_modules', 'axe-core', 'axe.min.js'), 'utf8');
const VARREDURA = readFileSync(join(AQUI, 'dica-varredura.js'), 'utf8');
const VARREDURA_ICONE = readFileSync(join(AQUI, 'icone-varredura.js'), 'utf8');
const EXTERNO = /(^|\.)(google\.com|googleapis\.com|gstatic\.com|translate\.goog|shields\.io|github\.com|githubusercontent\.com)$/;
const LARGURAS = [[320, false], [360, false], [390, false], [768, true], [1024, true], [1366, false]];
const TAGS_AXE = ['wcag2a', 'wcag2aa', 'wcag21a', 'wcag21aa', 'wcag22aa'];
const DISCO = process.argv.includes('--disco');
const AMOSTRA = process.argv.includes('--amostra');
let amostraUsada = false;
const OPERACOES_DE_EXEMPLO = [
  { registradoEm: '2026-10-26T12:00:00Z', nomeOperacao: 'TRADUCAO_LOCAL', origem: 'LLM', detalheOperacao: 'ep01.ass', duracaoMs: 22000, duracaoFormatada: '22s', itensDetectados: 10, itensCorrigidos: 10 },
  { registradoEm: '2026-10-26T12:05:00Z', nomeOperacao: 'REVISAO_LORE', origem: 'LLM', detalheOperacao: 'ep02.ass', duracaoMs: 61000, duracaoFormatada: '1min 1s', itensDetectados: 4, itensCorrigidos: 3 },
  { registradoEm: '2026-10-26T12:10:00Z', nomeOperacao: 'EXTRACAO', origem: 'SISTEMA', detalheOperacao: 'ep03.mkv', duracaoMs: 900, duracaoFormatada: '0,9s', taxaSucesso: 100 },
];
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
  await ctx.route('**/*', async (route) => {
    const url = new URL(route.request().url());
    if (EXTERNO.test(url.hostname)) { return route.abort(); }
    if (AMOSTRA && url.origin === BASE && url.pathname === '/api/telemetria') {
      const real = await route.fetch();
      const dados = await real.json();
      if (!(dados.historicoOperacoes || []).length) { dados.historicoOperacoes = OPERACOES_DE_EXEMPLO; amostraUsada = true; }
      return route.fulfill({ response: real, json: dados });
    }
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
    // a tela que se atualiza sozinha (Telemetria, a cada 5 s) recria os chips no meio da medição: o
    // controle que saiu da página não é medido aqui, e o recriado entra na próxima largura
    if (!d.isConnected) { continue; }
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
  const v = raizes.flatMap((r) => varreduraDica(r)).map((f) => `${f.elemento} — ${f.problema}`)
    // eslint-disable-next-line no-undef
    .concat(raizes.flatMap((r) => varreduraIcone(r)).map((f) => `${f.tipo} "${f.elemento}" — ${f.problema}`));
  const axeR = await window.axe.run(document.querySelector('.panel.active') || document, { runOnly: { type: 'tag', values: tags } });
  return { v, axe: axeR.violations.map((x) => `${x.id} (${x.nodes.length}): ${x.nodes[0].target.join(' ')}`) };
}, TAGS_AXE);

/** Tabelas do painel ativo com o thead escondido: toda célula (sem colspan) tem um filho visível com o nome da coluna. */
const tabelasSemRotulo = (page) => page.evaluate(() => {
  const limpo = (el) => { const c = el.cloneNode(true); c.querySelectorAll('.dica, svg, .material-symbols-outlined').forEach((n) => n.remove()); return c.textContent.replace(/\s+/g, ' ').trim(); };
  const visivel = (el) => el.getClientRects().length > 0 && getComputedStyle(el).visibility !== 'hidden';
  let total = 0;
  const ruins = [];
  for (const t of document.querySelectorAll('.panel.active table')) {
    if (!t.tHead || getComputedStyle(t.tHead).display !== 'none') { continue; }
    const ths = [...t.tHead.querySelectorAll('th')].map(limpo);
    for (const tr of [...t.tBodies].flatMap((b) => [...b.rows])) {
      [...tr.cells].forEach((td, i) => {
        if (td.colSpan > 1 || !ths[i]) { return; }
        total++;
        if (![...td.children].some((c) => visivel(c) && c.textContent.trim() === ths[i])) { ruins.push(`linha ${tr.rowIndex}, coluna "${ths[i]}"`); }
      });
    }
  }
  return { total, ruins };
});
/** Espera as linhas das tabelas em cartão chegarem da API (até 5 s); painel sem essas tabelas passa direto. */
const linhasDasTabelas = (page) => page.waitForFunction(() => [...document.querySelectorAll('.panel.active table')]
  .filter((t) => t.tHead && getComputedStyle(t.tHead).display === 'none')
  .every((t) => t.querySelector('tbody td:not([colspan])')), null, { timeout: 5000 }).catch(() => {});

/* ---------- 1. estouro, alvos, dicas, varredura e axe por largura e painel ---------- */
for (const [largura, toque] of LARGURAS) {
  const { ctx, page, erros } = await abrir(largura, toque);
  await page.addScriptTag({ content: AXE });
  await page.addScriptTag({ content: VARREDURA }); await page.addScriptTag({ content: VARREDURA_ICONE });
  const lista = await paineis(page);
  const est = []; const alv = []; const dic = []; const varr = []; const ax = []; let nDicas = 0;
  const rotSem = []; let nCelulas = 0;
  for (const alvo of lista) {
    await mostrar(page, alvo);
    const e = await estouro(page);
    if (e.sw > e.vw + 1) { est.push(`${alvo} (${e.sw} em ${e.vw})`); }
    if (largura === 390) { for (const x of await alvosPequenos(page)) { alv.push(`${alvo}: ${x}`); } }
    if (largura === 390) {
      await linhasDasTabelas(page);
      const tr = await tabelasSemRotulo(page);
      nCelulas += tr.total;
      for (const x of tr.ruins) { rotSem.push(`${alvo}: ${x}`); }
    }
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
  if (largura === 390) {
    const linha = `8. ${rot}: tabela em cartão diz a coluna em cada célula (${nCelulas} células)` + (rotSem.length ? ` — ${rotSem.length}: ` + rotSem.slice(0, 8).join(' | ') : '');
    if (nCelulas === 0) { naoVerificado.push(linha + ' — nenhuma linha de dados para conferir'); } else { confere(rotSem.length === 0, linha); }
  }
  if (largura === 390 || largura === 1366) {
    confere(nDicas > 0 && dic.length === 0, `3. ${rot}: ${nDicas} dicas abrem no foco, dentro da tela, coladas, e o Esc fecha` + (dic.length ? ' — ' + dic.slice(0, 10).join(' | ') : ''));
    confere(varr.length === 0, `4. ${rot}: varredura das dicas e dos ícones limpa` + (varr.length ? ` — ${varr.length}: ` + varr.slice(0, 10).join(' | ') : ''));
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
  await page.addScriptTag({ content: VARREDURA }); await page.addScriptTag({ content: VARREDURA_ICONE });
  await mostrar(page, 'analise');
  await page.evaluate(() => {
    const p = document.querySelector('.panel.active');
    const largo = document.createElement('div'); largo.style.width = '2000px'; largo.style.height = '4px'; p.append(largo);
    const mini = document.createElement('button'); mini.type = 'button'; mini.textContent = 'x';
    mini.style.cssText = 'min-height:0!important;height:12px;width:12px;padding:0;font-size:8px'; mini.setAttribute('aria-label', 'Plantado'); p.append(mini);
    // dica de botão com texto sem o "?" à vista, e título sem ícone: as duas varreduras têm de acusar
    const d = document.createElement('span'); d.className = 'dica';
    d.innerHTML = '<button type="button" aria-describedby="d-plantada">Plantado</button><span id="d-plantada" role="tooltip" class="dica__texto">Faz algo plantado.</span>';
    p.append(d);
    const h = document.createElement('h3'); h.textContent = 'Título plantado'; p.append(h);
  });
  const e = await estouro(page);
  const a = await alvosPequenos(page);
  const r = await varreduraEaxe(page);
  const cal = [
    [e.sw > e.vw + 1, 'estouro', `div de 2000 px deu largura ${e.sw}`],
    [a.some((x) => x.includes('12x12')), 'alvo de toque', 'botão de 12x12 px'],
    [r.v.some((x) => x.includes('Plantado') && x.includes('sem "?"')), 'varredura das dicas', 'dica de botão sem "?" à vista'],
    [r.v.some((x) => x.includes('Título plantado') && x.includes('sem ícone')), 'varredura dos ícones', 'título sem ícone'],
  ];
  await page.evaluate(() => { const s = document.createElement('style'); s.textContent = '.panel.active { transform: translateX(0); }'; document.head.append(s); });
  const d = await dicasRuins(page);
  cal.push([d.ruins.length > 0, 'posição da dica', `transform no painel (${d.ruins.length} de ${d.total} saem do lugar)`]);
  await page.evaluate(() => { const p = document.querySelector('.panel.active'); const f = document.createElement('p'); f.textContent = 'contraste plantado'; f.style.cssText = 'color:#111;background:#000'; p.append(f); });
  const r2 = await varreduraEaxe(page);
  cal.push([r2.axe.some((x) => x.startsWith('color-contrast')), 'axe', 'texto #111 sobre #000']);
  // tira a faixa de 2000 px plantada acima: com ela o celular alarga a área de layout e a tabela não
  // vira cartão, e a calibração seguinte não teria o que medir
  await page.evaluate(() => { for (const n of document.querySelectorAll('div')) { if (n.style.width === '2000px') { n.remove(); } } });
  await mostrar(page, 'telemetria');
  await linhasDasTabelas(page);
  await page.evaluate(() => document.querySelectorAll('.panel.active td > .td-rotulo').forEach((n) => n.remove()));
  const tr = await tabelasSemRotulo(page);
  cal.push([tr.total > 0 && tr.ruins.length === tr.total, 'rótulo da tabela em cartão', `rótulos removidos (${tr.ruins.length} de ${tr.total} células acusadas)`]);
  for (const [acusou, nome, como] of cal) {
    (acusou ? ok : naoVerificado).push(`0. calibração do detector de ${nome}: ${acusou ? 'acusou' : 'NÃO acusou'} o defeito plantado (${como})`);
  }
  await ctx.close();
}

await browser.close();
for (const l of ok) { console.log('  ok   ', l); }
for (const l of falhas) { console.log('  FALHA', l); }
for (const l of naoVerificado) { console.log('  NAO VERIFICOU', l); }
if (amostraUsada) { console.log('  AVISO o histórico de telemetria veio vazio: a tabela em cartão foi conferida com 3 operações de exemplo (--amostra)'); }
console.log(`\n${ok.length} ok, ${falhas.length} falha(s), ${naoVerificado.length} não verificado(s)`);
process.exit(naoVerificado.length ? 2 : (falhas.length ? 1 : 0));
