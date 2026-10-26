// icone-rotas.spec.mjs v1.1 (2026-10-24) — componente compartilhado: não editar no projeto; mudança só na origem.
// Roda a varredura de ícones (rótulo sem ícone reprova; títulos editoriais, exceções declaradas e rótulos não
// renderizados só são listados) em cada rota do projeto, no HTML que o usuário recebe — inclusive o que o JavaScript
// monta depois do carregamento.
// 1.1: funciona com Content-Security-Policy estrita (a varredura é injetada com bypassCSP: só no navegador de teste,
// a política do servidor não muda) e em telas que nunca ficam sem rede (conexão de eventos, consulta periódica): a
// espera pela rede ociosa tem limite e depois espera o DOM parar de mudar, também com limite.
// Uso:  BASE_URL=http://localhost:8080 ROTAS=/,/clientes,/pedidos npx playwright test icone-rotas.spec.mjs
// Opcional: ESPERA_REDE_MS (padrão 5000) e ESPERA_DOM_MS (padrão 10000).
// Marcações: data-icone-excecao="motivo" (fora da contagem; motivo registrado) e data-titulo-editorial em h1-h6.
import { test, expect } from '@playwright/test';
import fs from 'node:fs'; import path from 'node:path'; import { fileURLToPath } from 'node:url';
const dir = path.dirname(fileURLToPath(import.meta.url));
const varSrc = fs.readFileSync(path.join(dir, 'icone-varredura.js'), 'utf8');
test.use({ bypassCSP: true, launchOptions: process.env.CHROMIUM_PATH ? { executablePath: process.env.CHROMIUM_PATH } : {} });
const BASE = process.env.BASE_URL, ROTAS = (process.env.ROTAS || '/').split(',');
const ESPERA_REDE = Number(process.env.ESPERA_REDE_MS || 5000), ESPERA_DOM = Number(process.env.ESPERA_DOM_MS || 10000);
if (!BASE) throw new Error('Defina BASE_URL (ex.: http://localhost:8080).');

// Espera o DOM ficar 500 ms sem mudar de estrutura nem de texto, até o limite. Tela ao vivo que muda sempre para no limite.
const esperarDomQuieto = (page) => page.evaluate((limite) => new Promise((pronto) => {
  let quieto; const fim = () => { obs.disconnect(); clearTimeout(quieto); clearTimeout(teto); pronto(); };
  const obs = new MutationObserver(() => { clearTimeout(quieto); quieto = setTimeout(fim, 500); });
  obs.observe(document, { subtree: true, childList: true, characterData: true });
  quieto = setTimeout(fim, 500); const teto = setTimeout(fim, limite);
}), ESPERA_DOM);

for (const rota of ROTAS) {
  test(`ícones em ${rota}: nenhum rótulo sem ícone`, async ({ page }) => {
    test.setTimeout(30000 + ESPERA_REDE + ESPERA_DOM);
    await page.goto(BASE.replace(/\/$/, '') + rota, { waitUntil: 'load' });
    await page.waitForLoadState('networkidle', { timeout: ESPERA_REDE }).catch(() => {}); // tela que nunca fica sem rede segue
    await esperarDomQuieto(page);
    await page.addScriptTag({ content: varSrc });
    const falhas = await page.evaluate(() => varreduraIcone());
    const avisos = await page.evaluate(() => avisosIcone());
    for (const a of avisos) { test.info().annotations.push({ type: 'aviso de ícone', description: `${a.elemento}: ${a.problema}` }); console.log(`[aviso de ícone] ${rota} ${a.elemento}: ${a.problema}`); }
    const total = await page.evaluate(() => document.querySelectorAll('label, th, caption, legend, summary, button, h1, h2, h3, h4, h5, h6').length);
    console.log(`[ícones] ${rota}: ${total} rótulos no escopo básico, ${falhas.length} sem ícone, ${avisos.length} declarados ou não renderizados, fora da contagem`);
    expect(falhas.map((f) => `${f.tipo} "${f.elemento}": ${f.problema}`), 'rótulos sem ícone (label, coluna, título de área, botão, menu, legend, summary, KPI)').toEqual([]);
  });
}
