/**
 * MEDIÇÃO DO LAYOUT DE TODOS OS PAINÉIS — KRONOS.
 *
 * PROPÓSITO DE NEGÓCIO: provar que uma refatoração de CSS (ex.: px→rem) não moveu nada na tela, e
 * dizer O QUE mudou quando uma correção de layout era a intenção. Captura de tela varia com relógio e
 * animação; posição e tamanho de cada elemento, não.
 *
 * INVARIANTES DO DOMÍNIO:
 *  - mede os mesmos painéis (todo item do menu com data-target, menos "Sair") nas mesmas larguras;
 *  - Google bloqueado e idioma pt-BR: a tradução não pode mexer no texto medido;
 *  - a chave de cada elemento é o caminho no DOM (tag, id, classes e posição entre irmãos), estável
 *    entre duas medições da mesma marcação.
 *
 * COMPORTAMENTO EM CASO DE FALHA: sai 2 se o Chromium não abre ou o servidor está fora. No modo
 * comparação, sai 0 quando nada mudou além da tolerância (0,5 px), 1 quando mudou — e lista o quê.
 *
 * USO (na pasta ferramentas/navegador):
 *   node medir-layout.mjs <http://127.0.0.1:18099> <saida.json>
 *   node medir-layout.mjs --comparar <antes.json> <depois.json>
 */
import { chromium } from 'playwright';
import { readFileSync, writeFileSync } from 'node:fs';

const ARGS = process.argv.slice(2);
const LARGURAS = [1366, 390];

if (ARGS[0] === '--comparar') {
  const antes = JSON.parse(readFileSync(ARGS[1], 'utf8'));
  const depois = JSON.parse(readFileSync(ARGS[2], 'utf8'));
  const diferencas = [];
  for (const chave of Object.keys(antes)) {
    const a = antes[chave];
    const d = depois[chave] || {};
    for (const el of Object.keys(a)) {
      const ra = a[el];
      const rd = d[el];
      if (!rd) { diferencas.push(`${chave} SUMIU ${el}`); continue; }
      if (ra.some((v, i) => Math.abs(v - rd[i]) > 0.5)) { diferencas.push(`${chave} ${el}: ${ra.join(',')} → ${rd.join(',')}`); }
    }
    for (const el of Object.keys(d)) { if (!a[el]) { diferencas.push(`${chave} NOVO ${el}`); } }
  }
  const total = Object.values(antes).reduce((s, p) => s + Object.keys(p).length, 0);
  console.log(diferencas.slice(0, 40).join('\n'));
  console.log(`\n${diferencas.length} diferença(s) em ${total} elementos medidos`);
  process.exit(total === 0 ? 2 : (diferencas.length ? 1 : 0));
}

const BASE = (ARGS[0] || 'http://127.0.0.1:18099').replace(/\/$/, '');
const SAIDA = ARGS[1] || 'layout.json';
let browser;
try { browser = await chromium.launch(); } catch (e) { console.log('NAO VERIFICOU: Chromium —', e.message); process.exit(2); }
try { const r = await fetch(BASE + '/api/status'); if (!r.ok) { throw new Error('HTTP ' + r.status); } } catch (e) {
  console.log('NAO VERIFICOU: servidor fora —', e.message); await browser.close(); process.exit(2);
}
const resultado = {};
for (const largura of LARGURAS) {
  const ctx = await browser.newContext({ locale: 'pt-BR', viewport: { width: largura, height: 900 }, reducedMotion: 'reduce' });
  await ctx.addInitScript(() => { try { localStorage.setItem('kronos.idioma.interface', 'pt-BR'); } catch { /* sem armazenamento */ } });
  await ctx.route(/google|gstatic/, (r) => r.abort());
  const page = await ctx.newPage();
  await page.goto(BASE + '/', { waitUntil: 'load' });
  await page.waitForTimeout(1500);
  const alvos = await page.evaluate(() => [...document.querySelectorAll('.nav-item[data-target]')]
    .filter((b) => b.id !== 'btn-menu-sair').map((b) => b.getAttribute('data-target')));
  for (const alvo of alvos) {
    await page.evaluate((a) => document.querySelector(`.nav-item[data-target="${a}"]`).click(), alvo);
    await page.waitForTimeout(500);
    resultado[`${alvo}@${largura}`] = await page.evaluate(() => {
      const caminho = (el) => {
        const partes = [];
        for (let n = el; n && n !== document.body; n = n.parentElement) {
          const irmaos = n.parentElement ? [...n.parentElement.children].filter((c) => c.tagName === n.tagName) : [];
          partes.unshift(n.tagName.toLowerCase() + (n.id ? '#' + n.id : '') + (irmaos.length > 1 ? `[${irmaos.indexOf(n)}]` : ''));
        }
        return partes.join('>');
      };
      const out = {};
      const raiz = [document.querySelector('.sidebar'), document.querySelector('.panel.active'), document.querySelector('.main-content header, .top-header, .page-header')].filter(Boolean);
      for (const r of raiz) {
        for (const el of [r, ...r.querySelectorAll('*')]) {
          if (!el.getClientRects().length) { continue; }
          const b = el.getBoundingClientRect();
          const cs = getComputedStyle(el);
          out[caminho(el)] = [b.x, b.y + window.scrollY, b.width, b.height, parseFloat(cs.fontSize)].map((v) => Math.round(v * 2) / 2);
        }
      }
      return out;
    });
  }
  await ctx.close();
}
await browser.close();
writeFileSync(SAIDA, JSON.stringify(resultado));
const total = Object.values(resultado).reduce((s, p) => s + Object.keys(p).length, 0);
console.log(`${Object.keys(resultado).length} painéis×larguras, ${total} elementos medidos → ${SAIDA}`);
process.exit(total ? 0 : 2);
