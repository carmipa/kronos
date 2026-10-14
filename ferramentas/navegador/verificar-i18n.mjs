/**
 * VERIFICAÇÃO DA INTERNACIONALIZAÇÃO NO NAVEGADOR REAL — KRONOS.
 *
 * PROPÓSITO DE NEGÓCIO: a tradução da tela é o caminho de quem não lê português, e ela acontece no
 * navegador (o element.js é do Google). Teste de servidor não prova nada aqui. Este roteiro prova, num
 * Chromium de verdade, que (1) a página TRADUZ e os ícones continuam ícones; (2) quando o Google não
 * entrega o script — rede bloqueada, captcha por limite de uso ou silêncio — a pessoa vê um aviso NO
 * IDIOMA QUE ESCOLHEU, com saídas, e o `<html>` continua declarando português; (3) português não
 * carrega terceiro nenhum; (4) ícone e código ficam fora da tradução, inclusive o que a tela pinta
 * depois do carregamento.
 *
 * INVARIANTES DO DOMÍNIO:
 *  - só UMA carga toca o Google de verdade (item 1); as outras bloqueiam o Google — roteiro em volume
 *    é o que dispara o captcha para o endereço inteiro;
 *  - captcha ou Google fora do ar no item 1 é NÃO VERIFICOU (2), nunca aprovação nem reprovação;
 *  - o detector do item 2 é calibrado contra o `i18n.js` ANTERIOR ao aviso (commit 04134cc5, ou o
 *    arquivo de --calibrar=<caminho>): com ele o item 2 TEM de reprovar, senão o detector não
 *    discrimina e o roteiro sai 2.
 *
 * COMPORTAMENTO EM CASO DE FALHA: sai 0 (passou), 1 (reprovou) ou 2 (não verificou: Chromium não
 * abriu, servidor fora, captcha no item 1, calibração impossível). Cada linha diz o que mediu.
 *
 * USO (na pasta ferramentas/navegador):
 *   npm ci && npx playwright install chromium
 *   node verificar-i18n.mjs [http://127.0.0.1:18099] [--calibrar=<i18n.js antigo>]
 */
import { chromium } from 'playwright';
import { readFileSync } from 'node:fs';
import { execFileSync } from 'node:child_process';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const RAIZ = join(dirname(fileURLToPath(import.meta.url)), '..', '..');
const ARGS = process.argv.slice(2);
const BASE = (ARGS.find((a) => a.startsWith('http')) || 'http://127.0.0.1:18099').replace(/\/$/, '');
const CALIBRAR = (ARGS.find((a) => a.startsWith('--calibrar=')) || '').split('=')[1];
const COMMIT_ANTERIOR = '04134cc5';
const CHAVE_IDIOMA = 'kronos.idioma.interface';
const GOOGLE = /(^|\.)(translate\.google\.com|translate\.googleapis\.com|translate-pa\.googleapis\.com|www\.google\.com|translate\.goog)$/;
const PROTEGIDOS = '.material-symbols-outlined, code, pre, kbd, samp';

const ok = [];
const falhas = [];
const naoVerificado = [];
const confere = (condicao, descricao) => (condicao ? ok : falhas).push(descricao);

let browser;
try {
  browser = await chromium.launch();
} catch (e) {
  console.log('NAO VERIFICOU: o Chromium não abriu —', e.message.split('\n')[0]);
  process.exit(2);
}

/** Contexto isolado, em pt-BR, com a preferência de idioma semeada UMA vez (recarregar não a refaz). */
async function contexto({ idioma = null, bloquearGoogle = true, elementJs = null, scriptAntigo = null } = {}) {
  const ctx = await browser.newContext({ locale: 'pt-BR' });
  await ctx.addInitScript(([chave, valor]) => {
    try {
      if (!sessionStorage.getItem('__semeado')) {
        sessionStorage.setItem('__semeado', '1');
        if (valor) { localStorage.setItem(chave, valor); } else { localStorage.removeItem(chave); }
      }
    } catch { /* armazenamento bloqueado: o roteiro mede o que sobrar */ }
  }, [CHAVE_IDIOMA, idioma]);
  const pedidosGoogle = [];
  await ctx.route('**/*', async (route) => {
    const url = new URL(route.request().url());
    if (scriptAntigo && url.pathname === '/i18n/i18n.js') {
      return route.fulfill({ status: 200, contentType: 'text/javascript', body: scriptAntigo });
    }
    if (GOOGLE.test(url.hostname)) { pedidosGoogle.push(url.href); }
    if (elementJs && /translate_a\/element\.js/.test(url.href)) { return elementJs(route); }
    if (bloquearGoogle && GOOGLE.test(url.hostname)) { return route.abort(); }
    return route.continue();
  });
  const page = await ctx.newPage();
  const erros = [];
  page.on('pageerror', (e) => erros.push(e.message));
  return { ctx, page, erros, pedidosGoogle };
}

const estado = (page) => page.evaluate((chave) => {
  const aviso = document.getElementById('idioma-aviso-falha');
  const r = aviso ? aviso.getBoundingClientRect() : null;
  return {
    htmlLang: document.documentElement.getAttribute('lang'),
    traduzida: document.documentElement.classList.contains('translated-ltr'),
    ativo: document.querySelector('.seletor-idioma-botao[aria-pressed="true"]')?.dataset.idioma || null,
    preferencia: (() => { try { return localStorage.getItem(chave); } catch { return '?'; } })(),
    cookie: document.cookie,
    aviso: aviso ? {
      texto: aviso.querySelector('.idioma-aviso-texto')?.textContent || '',
      lang: aviso.getAttribute('lang'),
      translate: aviso.getAttribute('translate'),
      visivel: r.width > 0 && r.height > 0,
      botoes: [...aviso.querySelectorAll('button, a')].map((b) => b.textContent.trim() || b.getAttribute('aria-label')),
    } : null,
  };
}, CHAVE_IDIOMA);

const rotulosEIcones = (page) => page.evaluate(() => ({
  menu: [...document.querySelectorAll('.nav-item > span:last-child')].map((e) => e.textContent.trim()),
  icones: [...document.querySelectorAll('.material-symbols-outlined')].map((e) => e.textContent.trim()),
}));

/** Item 2 isolado: também serve à calibração, rodando com o script anterior. */
async function itemGoogleBloqueado(scriptAntigo = null) {
  const { ctx, page, erros } = await contexto({ idioma: 'en-US', scriptAntigo });
  await page.goto(BASE + '/', { waitUntil: 'load' });
  await page.waitForTimeout(2500);
  const e = await estado(page);
  await ctx.close();
  return { e, erros };
}

const avaliarItem2 = (e) => [
  [e.htmlLang === 'pt-BR', `<html lang> continua pt-BR com a página em português (${e.htmlLang})`],
  [Boolean(e.aviso && e.aviso.visivel), 'aviso de falha visível'],
  [Boolean(e.aviso && e.aviso.lang === 'en-US' && /did not respond/.test(e.aviso.texto)),
    'o aviso está em inglês, o idioma pedido: ' + (e.aviso ? e.aviso.texto.slice(0, 60) : '—')],
  [Boolean(e.aviso && e.aviso.translate === 'no'), 'o aviso tem translate="no"'],
];

try {
  try {
    const r = await fetch(BASE + '/');
    if (!r.ok) { throw new Error('HTTP ' + r.status); }
  } catch (e) {
    console.log('NAO VERIFICOU: servidor fora em', BASE, '—', e.message);
    process.exit(2);
  }

  // 4. Português (sem preferência, navegador pt-BR): nada de terceiro, nenhum aviso, bandeiras locais
  let original = null;
  {
    const { ctx, page, erros, pedidosGoogle } = await contexto({ idioma: null });
    await page.goto(BASE + '/', { waitUntil: 'load' });
    await page.waitForTimeout(2500);
    const e = await estado(page);
    original = await rotulosEIcones(page);
    confere(e.ativo === 'pt-BR' && !e.aviso, `4. português: bandeira pt-BR ativa e nenhum aviso (ativo=${e.ativo})`);
    confere(pedidosGoogle.length === 0, `4. português não pede nada ao tradutor (${pedidosGoogle.length} pedido(s))`);
    const bandeiras = await page.$$eval('.seletor-idioma-botao img', (imgs) =>
      imgs.map((i) => ({ src: i.getAttribute('src'), w: i.naturalWidth })));
    confere(bandeiras.length === 3 && bandeiras.every((b) => b.src.startsWith('i18n/flags/') && b.w > 0),
      '4. as 3 bandeiras vêm do próprio site e carregam: ' + bandeiras.map((b) => b.src).join(', '));
    confere(erros.length === 0, '4. nenhum erro de página' + (erros.length ? ': ' + erros[0] : ''));

    // 6. Ilhas técnicas fora da tradução, inclusive o módulo injetado depois (painel "Sobre")
    await page.evaluate(() => document.getElementById('btn-menu-sobre')?.click());
    await page.waitForTimeout(2000);
    const prot = await page.evaluate((sel) => {
      const todos = [...document.querySelectorAll(sel)];
      return { total: todos.length, sem: todos.filter((el) => el.getAttribute('translate') !== 'no').map((el) => el.outerHTML.slice(0, 90)),
        sobre: document.querySelectorAll('#panel-sobre .material-symbols-outlined, #panel-sobre code').length };
    }, PROTEGIDOS);
    confere(prot.total > 100, `6. instrumento enxerga as ilhas técnicas (${prot.total} ícones/códigos)`);
    confere(prot.sobre > 0, `6. o módulo injetado depois (Sobre) tem ilhas técnicas para medir (${prot.sobre})`);
    confere(prot.sem.length === 0, `6. 100% das ilhas técnicas com translate="no"` + (prot.sem.length ? ' — falta em: ' + prot.sem[0] : ''));
    await ctx.close();
  }

  // 1. Tradução de verdade (única carga que toca o Google)
  {
    const { ctx, page, erros } = await contexto({ idioma: 'en-US', bloquearGoogle: false });
    let captcha = false;
    page.on('response', (r) => {
      if (/google\.com\/sorry/.test(r.url()) || (r.status() >= 300 && /sorry/.test(r.headers().location || ''))) { captcha = true; }
    });
    const inicio = Date.now();
    await page.goto(BASE + '/', { waitUntil: 'load' });
    const traduziu = await page.waitForFunction(() => document.documentElement.classList.contains('translated-ltr'),
      null, { timeout: 25000 }).then(() => true).catch(() => false);
    const ms = Date.now() - inicio;
    if (!traduziu && captcha) {
      naoVerificado.push('1. tradução real: o Google respondeu com captcha (limite de uso do endereço) — sem veredito');
    } else {
      confere(traduziu, `1. a página traduz para inglês (html.translated-ltr em ${ms} ms)`);
      await page.waitForTimeout(2000);
      const depois = await rotulosEIcones(page);
      const mudou = depois.menu.filter((t, i) => t !== original.menu[i]);
      confere(mudou.length >= 3, `1. o menu mudou de idioma (${mudou.slice(0, 4).join(' / ')})`);
      confere(JSON.stringify(depois.icones) === JSON.stringify(original.icones),
        `1. os ${original.icones.length} ícones continuam com o nome do glifo`);
      const e = await estado(page);
      confere(!e.aviso, '1. sem aviso de falha quando a tradução funciona');
      confere(erros.length === 0, '1. nenhum erro de página' + (erros.length ? ': ' + erros[0] : ''));
    }
    await ctx.close();
  }

  // 2. Google bloqueado: aviso imediato no idioma pedido, e o documento continua declarando português
  {
    const { e, erros } = await itemGoogleBloqueado();
    for (const [c, d] of avaliarItem2(e)) { confere(c, '2. ' + d); }
    confere(e.ativo === 'pt-BR', `2. a bandeira volta para pt-BR, que é o que a página mostra (${e.ativo})`);
    confere(Boolean(e.aviso && e.aviso.botoes.includes('Try again') && e.aviso.botoes.includes('Stay in Portuguese')),
      '2. saídas: tentar de novo e ficar em português — ' + (e.aviso ? e.aviso.botoes.join(' | ') : '—'));
    confere(Boolean(e.aviso) && !e.aviso.botoes.includes('Open in Google Translate') === BASE.startsWith('http:'),
      '2. "abrir no Google Tradutor" só em endereço público https (aqui: '
        + (e.aviso && e.aviso.botoes.includes('Open in Google Translate') ? 'presente' : 'ausente') + ')');
    confere(erros.length === 0, '2. nenhum erro de página' + (erros.length ? ': ' + erros[0] : ''));
  }

  // 2b. "Ficar em português" grava a escolha: depois de recarregar, nada de aviso nem de Google
  {
    const { ctx, page, pedidosGoogle } = await contexto({ idioma: 'en-US' });
    await page.goto(BASE + '/', { waitUntil: 'load' });
    await page.waitForTimeout(2500);
    await page.click('#idioma-aviso-falha button:has-text("Stay in Portuguese")');
    await page.waitForLoadState('load');
    await page.waitForTimeout(2500);
    const antes = pedidosGoogle.length;
    const e = await estado(page);
    confere(!e.aviso && e.preferencia === 'pt-BR' && !/googtrans=\//.test(e.cookie) && e.ativo === 'pt-BR',
      `2b. "ficar em português" grava pt-BR e o aviso some (preferência=${e.preferencia}, ativo=${e.ativo})`);
    await page.reload({ waitUntil: 'load' });
    await page.waitForTimeout(1500);
    confere(pedidosGoogle.length === antes, '2b. depois da escolha, recarregar não pede nada ao Google');
    await ctx.close();
  }

  // 3. Captcha (302 para google.com/sorry): aviso em espanhol; a tradução que chega depois o tira
  {
    const captcha = (route) => route.fulfill({ status: 302, headers: { location: 'https://www.google.com/sorry/index?continue=x' } });
    const { ctx, page } = await contexto({ idioma: 'es-ES', elementJs: captcha });
    await page.goto(BASE + '/', { waitUntil: 'load' });
    await page.waitForTimeout(2500);
    const antes = await estado(page);
    confere(Boolean(antes.aviso && antes.aviso.lang === 'es-ES' && /no respondió/.test(antes.aviso.texto)),
      '3. captcha: aviso em espanhol — ' + (antes.aviso ? antes.aviso.texto.slice(0, 60) : '—'));
    await page.evaluate(() => document.documentElement.classList.add('translated-ltr'));
    await page.waitForTimeout(400);
    const depois = await estado(page);
    confere(!depois.aviso, '3. o aviso sai quando a tradução chega atrasada');
    await ctx.close();
  }

  // 5. Google mudo (pedido pendurado): a tela funciona e o aviso vem pelo prazo de 10 s
  {
    const { ctx, page } = await contexto({ idioma: 'en-US', elementJs: () => new Promise(() => {}) });
    const montou = await page.goto(BASE + '/', { waitUntil: 'domcontentloaded', timeout: 8000 }).then(() => true).catch(() => false);
    confere(montou, '5. Google mudo: o DOMContentLoaded acontece (o element.js não segura a página)');
    if (montou) {
      await page.waitForTimeout(1500);
      await page.evaluate(() => document.getElementById('btn-menu-documentacao')?.click());
      await page.waitForTimeout(500);
      const ativo = await page.evaluate(() => document.getElementById('panel-documentacao')?.classList.contains('active'));
      confere(Boolean(ativo), '5. Google mudo: o menu troca de painel (Documentação)');
      const cedo = await estado(page);
      confere(!cedo.aviso, '5. antes do prazo, nenhum aviso (o Google pode estar só lento)');
      await page.waitForTimeout(10000);
      const e = await estado(page);
      confere(Boolean(e.aviso && e.aviso.visivel && e.aviso.lang === 'en-US'), '5. Google mudo: aviso em inglês pelo prazo de 10 s');
      confere(e.htmlLang === 'pt-BR', `5. Google mudo: <html lang> continua pt-BR (${e.htmlLang})`);
    }
    await ctx.close();
  }

  // 0. Calibração: com o i18n.js ANTERIOR, o item 2 tem de reprovar
  {
    let antigo = null;
    try {
      antigo = CALIBRAR ? readFileSync(CALIBRAR, 'utf8')
        : execFileSync('git', ['show', `${COMMIT_ANTERIOR}:src/main/resources/static/i18n/i18n.js`], { cwd: RAIZ, encoding: 'utf8' });
    } catch { antigo = null; }
    if (!antigo || /idioma-aviso-falha/.test(antigo)) {
      naoVerificado.push('0. calibração: não há versão anterior sem o aviso (passe --calibrar=<arquivo>)');
    } else {
      const { e } = await itemGoogleBloqueado(antigo);
      const reprovados = avaliarItem2(e).filter(([c]) => !c).map(([, d]) => d);
      if (reprovados.length === 0) {
        naoVerificado.push('0. calibração: o script anterior PASSOU no item 2 — o detector não discrimina');
      } else {
        ok.push('0. calibração: com o script anterior o item 2 reprova (' + reprovados.length + ' de 4: ' + reprovados[0] + ')');
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
falhas.forEach((d) => console.log('FALHA ' + d));
console.log(`\n${ok.length} ok, ${falhas.length} falhas, ${naoVerificado.length} não verificados — ${BASE}`);
process.exit(falhas.length ? 1 : (naoVerificado.length || ok.length === 0 ? 2 : 0));
