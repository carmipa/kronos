/**
 * DICAS (tooltips) — transforma o atributo `data-tooltip` na marcação canônica da regra 6-A
 * (instrucoes/tooltip-canonico: dica.css e dica.js copiados SEM edição em static/dica/).
 *
 * PROPÓSITO DE NEGÓCIO: o painel tinha 170 dicas feitas só com CSS (`::after` no hover). Elas não
 * abriam pelo teclado nem pelo toque, o leitor de tela não as lia e o balão invisível ainda ocupava
 * largura, fazendo o celular encolher o zoom da página. Aqui cada `data-tooltip` vira a dica
 * canônica: abre no foco, no toque e no hover, fecha com Esc e é lida como descrição do controle.
 * Os textos continuam onde sempre estiveram (no atributo, no HTML); o Google Tradutor das bandeiras
 * passa a traduzi-los, porque viram texto da página.
 *
 * INVARIANTES DO DOMÍNIO:
 *  - controle (botão, link, campo com foco): envolvido por `span.dica` com a dica como IRMÃ — nunca
 *    filha do controle, senão herdaria `opacity`/`filter`/`transform` do botão desabilitado ou do
 *    hover e abriria translúcida ou fora do lugar;
 *  - o envoltório não pode mexer no layout: controle de nível bloco ganha `dica--bloco`, e o CSS do
 *    projeto faz o envoltório ocupar o lugar dele;
 *  - título e cabeçalho de coluna: o "?" (`dica__gatilho`) vai DENTRO, no fim (padrão 3); se o JS
 *    da tela reescrever o texto do título e o "?" sumir junto, o observador o põe de volta;
 *  - rótulo de campo e o "?" antigo (`.help-icon`): o "?" vai logo DEPOIS do rótulo, fora dele
 *    (padrão 2) — dentro, o nome do campo lido pelo leitor de tela ganharia "Ajuda sobre…" junto;
 *    o campo do rótulo aponta para a mesma dica (`aria-describedby`);
 *  - cada dica tem `id` único e `role="tooltip"`; a conversão é idempotente (o atributo sai);
 *  - conteúdo criado depois (painéis carregados por fetch, linhas de tabela, faixa da vitrine) é
 *    convertido pelo observador; trocar o `data-tooltip` de um controle já convertido atualiza o
 *    texto da dica;
 *  - dica padrão por seletor (`registrarDicaPadrao`) nunca sobrescreve a dica própria do elemento;
 *  - ícone que acompanha texto sai da leitura (`aria-hidden`); ícone que é o único nome de um
 *    controle fica.
 *
 * COMPORTAMENTO EM CASO DE FALHA: elemento com `data-tooltip` vazio só perde o atributo; rótulo sem
 * texto usa "este campo" no nome do "?"; sem `MutationObserver` converte só o que já existe. Se este
 * módulo nem carregar, os atributos ficam e a tela funciona sem dica (nada quebra, nada vaza).
 */

const CONTROLE = 'button, a[href], input:not([type=hidden]), select, textarea, summary, '
    + '[role=button], [role=tab], [role=menuitem], [role=switch], [tabindex]:not([tabindex="-1"])';
const TEXTO = 'th, h1, h2, h3, h4, h5, h6, [role=columnheader]';
const SVG_AJUDA = '<svg viewBox="0 0 24 24" aria-hidden="true" focusable="false"><circle cx="12" cy="12" r="10" '
    + 'fill="none" stroke="currentColor" stroke-width="2"/><path d="M9.5 9a2.5 2.5 0 1 1 3.5 2.3c-.7.4-1 .9-1 '
    + '1.7M12 17h.01" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round"/></svg>';
const BLOCO = /^(block|flex|grid|list-item|table|flow-root)$/;

let sequencia = 0;
const padroes = [];

/**
 * PROPÓSITO DE NEGÓCIO: dá a mesma dica a todo controle repetido (o "Procurar..." de 25 formulários,
 * o "Limpar" de 15 consoles) e aos itens que o JS cria, num lugar só — inclusive aos que aparecem
 * depois (painel carregado por fetch, lista montada de novo).
 *
 * INVARIANTES DO DOMÍNIO: nunca sobrescreve uma dica própria (atributo `data-tooltip` já presente ou
 * controle já convertido); vale para o que já existe e para o que entrar na página.
 *
 * COMPORTAMENTO EM CASO DE FALHA: texto vazio (ou função que devolve vazio) não cria dica.
 *
 * @param {string} seletor CSS dos elementos que recebem a dica
 * @param {string|function(Element): string} texto texto fixo ou calculado a partir do elemento
 */
export function registrarDicaPadrao(seletor, texto) {
    padroes.push({ seletor, texto });
    if (document.body) converterDicas(document);
}

function aplicarPadroes(raiz) {
    for (const { seletor, texto } of padroes) {
        const alvos = [];
        if (raiz.nodeType === 1 && raiz.matches(seletor)) alvos.push(raiz);
        alvos.push(...raiz.querySelectorAll(seletor));
        for (const el of alvos) {
            if (el.hasAttribute('data-tooltip') || el.hasAttribute('data-dica') || el.querySelector(':scope > .dica')) continue;
            const t = typeof texto === 'function' ? texto(el) : texto;
            if (t) el.setAttribute('data-tooltip', t);
        }
    }
}

/**
 * PROPÓSITO DE NEGÓCIO: converte todas as dicas de um trecho da página e passa a vigiar o que
 * aparecer depois.
 *
 * INVARIANTES DO DOMÍNIO: chamar de novo não duplica nada (o atributo de origem é consumido).
 *
 * COMPORTAMENTO EM CASO DE FALHA: sem `document.body`, não faz nada.
 *
 * @param {ParentNode} [raiz=document] onde procurar
 */
export function inicializarDicas(raiz = document) {
    if (!document.body) return;
    converterDicas(raiz);
    if (typeof MutationObserver !== 'function') return;
    new MutationObserver((mudancas) => {
        for (const m of mudancas) {
            if (m.type === 'attributes') {
                atualizarOuConverter(m.target);
                continue;
            }
            for (const no of m.addedNodes) {
                if (no.nodeType === 1 && !no.closest('.dica__texto')) converterDicas(no);
            }
            recolocarAjuda(m.target);
        }
    }).observe(document.body, { childList: true, subtree: true, attributes: true, attributeFilter: ['data-tooltip'] });
}

/**
 * PROPÓSITO DE NEGÓCIO: converte os `data-tooltip` dentro de `raiz` (inclusive o da própria raiz).
 *
 * INVARIANTES DO DOMÍNIO: nada dentro de uma dica já pronta é tocado.
 *
 * COMPORTAMENTO EM CASO DE FALHA: elemento que não se encaixa em nenhum padrão perde o atributo e
 * fica sem dica — nunca recebe marcação pela metade.
 *
 * @param {Element|Document} raiz
 */
export function converterDicas(raiz) {
    aplicarPadroes(raiz);
    marcarIconesDecorativos(raiz);
    const lista = [];
    if (raiz.nodeType === 1 && raiz.hasAttribute('data-tooltip')) lista.push(raiz);
    lista.push(...raiz.querySelectorAll('[data-tooltip]'));
    for (const el of lista) {
        if (!el.isConnected || el.closest('.dica__texto')) continue;
        converter(el);
    }
}

/**
 * Regra 6, blindagem 3: ícone que acompanha um texto é decorativo e sai da leitura do leitor de tela
 * (sem isso ele lê o nome do glifo: "content_copy Copiar"). Ícone que é o ÚNICO nome de um controle
 * fica — tirá-lo deixaria o botão sem nome.
 */
function marcarIconesDecorativos(raiz) {
    const icones = [];
    if (raiz.nodeType === 1 && raiz.matches('.material-symbols-outlined')) icones.push(raiz);
    icones.push(...raiz.querySelectorAll('.material-symbols-outlined'));
    for (const icone of icones) {
        if (icone.hasAttribute('aria-hidden')) continue;
        const controle = icone.closest(CONTROLE);
        if (controle && !rotuloDe(controle) && !controle.getAttribute('aria-label')) continue;
        icone.setAttribute('aria-hidden', 'true');
    }
}

/** Título cujo texto o JS reescreveu (o "?" de dentro foi junto): põe o "?" de volta. */
function recolocarAjuda(el) {
    if (el.nodeType !== 1 || !el.hasAttribute('data-dica-texto') || el.querySelector(':scope > .dica')) return;
    el.append(criarAjuda(novoId(el), rotuloDe(el), el.getAttribute('data-dica-texto')));
}

function atualizarOuConverter(el) {
    if (!el.hasAttribute('data-tooltip')) return;
    const id = el.getAttribute('data-dica');
    const existente = id && document.getElementById(id);
    if (existente) {
        const texto = el.getAttribute('data-tooltip').trim();
        el.removeAttribute('data-tooltip');
        if (texto) existente.textContent = texto;
        return;
    }
    converter(el);
}

function converter(el) {
    const texto = (el.getAttribute('data-tooltip') || '').trim();
    el.removeAttribute('data-tooltip');
    if (!texto) return;
    if (el.classList.contains('help-icon')) { converterAjudaAntiga(el, texto); return; }
    if (el.matches('label')) { colocarAposRotulo(el, texto, rotuloDe(el)); return; }
    if (el.matches(TEXTO)) {
        // guarda o texto: se o JS da tela reescrever o título (textContent/innerHTML), o observador
        // recoloca o "?" (ver recolocarAjuda)
        el.setAttribute('data-dica-texto', texto);
        el.append(criarAjuda(novoId(el), rotuloDe(el), texto));
        return;
    }
    if (el.matches(CONTROLE)) { envolverControle(el, texto); }
}

/** Controle com foco: `span.dica` em volta, dica como irmã do controle. */
function envolverControle(el, texto) {
    const id = novoId(el);
    const envolve = document.createElement('span');
    envolve.className = ehBloco(el) ? 'dica dica--bloco' : 'dica';
    el.replaceWith(envolve);
    envolve.append(el, criarTexto(id, texto));
    descrever(el, id);
    el.setAttribute('data-dica', id);
}

/** O "?" antigo (`span.help-icon`): vira o gatilho canônico logo depois do rótulo que o continha. */
function converterAjudaAntiga(icone, texto) {
    const rotulo = icone.closest('label');
    icone.remove();
    if (rotulo) {
        colocarAposRotulo(rotulo, texto, rotuloDe(rotulo));
        return;
    }
    const pai = icone.parentElement || null;
    const ajuda = criarAjuda(novoId(pai || document.body), rotuloDe(pai) || 'este item', texto);
    if (pai) pai.append(ajuda);
}

/**
 * Padrão 2: rótulo + "?" + campo. O campo do rótulo aponta para a mesma dica. Rótulo e "?" dividem
 * uma linha (`div.rotulo-dica`): solto depois do rótulo, o "?" virava um item a mais no formulário
 * em coluna (+29 px por campo, medido).
 */
function colocarAposRotulo(rotulo, texto, nome) {
    const controle = rotulo.htmlFor ? document.getElementById(rotulo.htmlFor) : rotulo.querySelector('input, select, textarea');
    const id = novoId(controle || rotulo);
    let linha = rotulo.parentElement;
    if (!linha || !linha.classList.contains('rotulo-dica')) {
        linha = document.createElement('div');
        linha.className = 'rotulo-dica';
        rotulo.replaceWith(linha);
        linha.append(rotulo);
    }
    linha.append(criarAjuda(id, nome || 'este campo', texto));
    if (controle) descrever(controle, id);
}

function criarAjuda(id, nome, texto) {
    const envolve = document.createElement('span');
    envolve.className = 'dica';
    const gatilho = document.createElement('button');
    gatilho.type = 'button';
    gatilho.className = 'dica__gatilho';
    gatilho.setAttribute('aria-label', `Ajuda sobre ${nome}`);
    gatilho.setAttribute('aria-describedby', id);
    gatilho.innerHTML = SVG_AJUDA;
    envolve.append(gatilho, criarTexto(id, texto));
    return envolve;
}

function criarTexto(id, texto) {
    const t = document.createElement('span');
    t.id = id;
    t.setAttribute('role', 'tooltip');
    t.className = 'dica__texto';
    t.textContent = texto;
    return t;
}

function descrever(el, id) {
    const atuais = (el.getAttribute('aria-describedby') || '').split(/\s+/).filter(Boolean);
    if (!atuais.includes(id)) atuais.push(id);
    el.setAttribute('aria-describedby', atuais.join(' '));
}

/**
 * PROPÓSITO DE NEGÓCIO: nome que a pessoa lê no elemento, para compor o "Ajuda sobre …" do "?" e as
 * dicas padrão (ex.: "Abre a página X da documentação").
 *
 * INVARIANTES DO DOMÍNIO: tira o glifo do ícone (que é palavra técnica, ex. "content_copy"), o "?"
 * antigo, dicas já postas e os dois-pontos finais do rótulo; até 60 caracteres.
 *
 * COMPORTAMENTO EM CASO DE FALHA: elemento ausente devolve string vazia.
 *
 * @param {Element|null} el
 * @returns {string}
 */
export function rotuloDe(el) {
    if (!el) return '';
    const copia = el.cloneNode(true);
    copia.querySelectorAll('.material-symbols-outlined, .help-icon, .dica, svg').forEach((n) => n.remove());
    return copia.textContent.replace(/\s+/g, ' ').trim().replace(/[\s:?]+$/, '').slice(0, 60);
}

function novoId(el) {
    const base = (el && el.id) ? el.id : 'auto';
    let id = `d-${base}`;
    while (document.getElementById(id)) id = `d-${base}-${++sequencia}`;
    return id;
}

/** Nível bloco pelo display calculado; escondido por `hidden`/`.hidden`, mede sem o esconderijo. */
function ehBloco(el) {
    let display = getComputedStyle(el).display;
    if (display === 'none' && (el.hidden || el.classList.contains('hidden'))) {
        const tinhaHidden = el.hidden;
        const tinhaClasse = el.classList.contains('hidden');
        el.hidden = false;
        el.classList.remove('hidden');
        display = getComputedStyle(el).display;
        el.hidden = tinhaHidden;
        if (tinhaClasse) el.classList.add('hidden');
    }
    return BLOCO.test(display);
}
