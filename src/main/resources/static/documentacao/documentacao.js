import { mostrarAlerta } from '../js/app.js';

/**
 * Painel "Documentação": busca o markdown bruto de docs/*.md via
 * /api/docs/{pagina} (ver DocumentacaoController no backend) e renderiza no
 * navegador com marked.js (Markdown → HTML) e mermaid.js (diagramas), sem
 * sair da aplicação. As mesmas páginas são a documentação oficial do
 * repositório no GitHub — aqui elas só são exibidas, nunca duplicadas.
 */
export function initDocumentacao() {
    const painel = document.getElementById('panel-documentacao');
    if (!painel) return;

    if (typeof marked === 'undefined' || typeof mermaid === 'undefined') {
        console.warn('[Documentação] marked.js/mermaid.js não carregados — painel de documentação ficará limitado.');
        return;
    }

    marked.setOptions({ gfm: true, breaks: false, pedantic: false });

    mermaid.initialize({
        startOnLoad: false,
        theme: 'dark',
        themeVariables: {
            primaryColor: '#141A29',
            primaryTextColor: '#F9FAFB',
            primaryBorderColor: '#3B82F6',
            lineColor: '#6B7280',
            secondaryColor: '#10141F',
            tertiaryColor: '#0A0D14',
            background: '#0A0D14',
            mainBkg: '#141A29',
            nodeBorder: 'rgba(255, 255, 255, 0.12)',
            clusterBkg: '#10141F',
            titleColor: '#F9FAFB',
            edgeLabelBackground: '#10141F',
            actorBkg: '#141A29',
            actorBorder: '#3B82F6',
            actorTextColor: '#F9FAFB',
            noteBkgColor: '#1e293b',
            noteTextColor: '#F9FAFB',
            activationBorderColor: '#8B5CF6',
            activationBkgColor: '#1e293b',
            sequenceNumberColor: '#F9FAFB'
        }
    });

    const elWelcome    = document.getElementById('doc-welcome');
    const elLoading    = document.getElementById('doc-loading');
    const elError      = document.getElementById('doc-error');
    const elErrorMsg   = document.getElementById('doc-error-msg');
    const elMarkdown   = document.getElementById('doc-markdown-view');
    const elBreadcrumb = document.getElementById('doc-breadcrumb-atual');
    const elBody       = document.getElementById('doc-content-body');

    let paginaAtual = '';
    let mdAtual = '';

    function mostrar(estado) {
        elWelcome.style.display  = estado === 'welcome'  ? '' : 'none';
        elLoading.style.display  = estado === 'loading'  ? '' : 'none';
        elError.style.display    = estado === 'error'    ? '' : 'none';
        elMarkdown.style.display = estado === 'markdown' ? '' : 'none';
    }

    function marcarAtivo(pagina) {
        document.querySelectorAll('.doc-nav-item').forEach(btn => {
            btn.classList.toggle('active', btn.dataset.pagina === pagina);
        });
    }

    // Links markdown do tipo "(02-instalacao.md)" viram navegação interna do
    // painel em vez de tentar abrir um arquivo .md cru no navegador.
    /**
     * PROPÓSITO DE NEGÓCIO: a tela já tem o título principal (h1 do cabeçalho); o "# Título" de
     * cada .md virava um SEGUNDO h1, e o leitor de tela perdia a hierarquia da página.
     *
     * INVARIANTES DO DOMÍNIO: cada título desce um nível (h1 vira h2 ... h6 fica h6) e leva a
     * classe do nível original (md-h1 ... md-h6), que é o que o CSS estiliza: a aparência não muda.
     * O .md continua com "#" — ele também é a documentação do GitHub.
     *
     * COMPORTAMENTO EM CASO DE FALHA: raiz sem títulos não muda; nada é lançado.
     */
    function rebaixarTitulos(raiz) {
        raiz.querySelectorAll('h1, h2, h3, h4, h5, h6').forEach((titulo) => {
            const nivel = Number(titulo.tagName[1]);
            const novo = document.createElement('h' + Math.min(6, nivel + 1));
            for (const atributo of titulo.attributes) novo.setAttribute(atributo.name, atributo.value);
            novo.classList.add('md-h' + nivel);
            novo.innerHTML = titulo.innerHTML;
            titulo.replaceWith(novo);
        });
    }

    function sanitizarLinks(html) {
        return html.replace(
            /href="(?:\.\/|\.\.\/)*(?:[a-zA-Z0-9_\-]+\/)?([a-zA-Z0-9_\-]+)\.md(#[a-zA-Z0-9_\-]*)?"/g,
            (_m, nome) => 'href="javascript:void(0)" data-pagina="' + nome + '" class="doc-md-link"'
        );
    }

    // Imagens em docs/*.md apontam para "../src/main/resources/static/img/..."
    // (caminho relativo correto para o GitHub renderizar o arquivo dentro de
    // docs/). Aqui reescreve para a URL raiz "/img/..." que o Quarkus
    // realmente serve, já que o markdown é injetado na página em "/".
    function sanitizarImagens(html) {
        return html.replace(
            /src="(?:\.\.\/)+src\/main\/resources\/static\/(img\/[^"]+)"/g,
            'src="/$1"'
        );
    }

    function renderizarMermaid() {
        elMarkdown.querySelectorAll('pre code.language-mermaid').forEach((code) => {
            const pre = code.parentElement;
            if (!pre || !pre.parentNode) return;
            const div = document.createElement('div');
            div.className = 'mermaid';
            div.textContent = code.textContent;
            pre.parentNode.replaceChild(div, pre);
        });
        const nos = elMarkdown.querySelectorAll('.mermaid');
        if (nos.length > 0) {
            mermaid.run({ nodes: nos }).catch((e) => console.warn('[Mermaid]', e));
        }
    }

    function carregarPagina(pagina, titulo) {
        if (!pagina) {
            mostrar('welcome');
            marcarAtivo('');
            elBreadcrumb.textContent = 'Visão Geral';
            paginaAtual = '';
            mdAtual = '';
            return;
        }

        if (pagina === paginaAtual && elMarkdown.style.display !== 'none') return;

        mostrar('loading');
        elBody.scrollTop = 0;
        marcarAtivo(pagina);
        elBreadcrumb.textContent = titulo || pagina;
        paginaAtual = pagina;

        fetch('/api/docs/' + encodeURIComponent(pagina))
            .then(res => {
                if (!res.ok) throw new Error('HTTP ' + res.status);
                return res.text();
            })
            .then(md => {
                mdAtual = md;
                // Remove as linhas de navegação anterior/próximo (tabela markdown)
                // — dentro do painel isso é redundante com o menu lateral.
                const mdLimpo = md
                    .replace(/^\s*\|.*?\[←[^\]]+\].*?\|.*?\n?$/gm, '')
                    .replace(/^\s*\|.*?\[[^\]]+→\].*?\|.*?\n?$/gm, '');

                let html = marked.parse(mdLimpo);
                html = sanitizarLinks(html);
                html = sanitizarImagens(html);
                elMarkdown.innerHTML = html;
                rebaixarTitulos(elMarkdown);

                elMarkdown.querySelectorAll('a.doc-md-link').forEach(a => {
                    const pg = a.dataset.pagina;
                    a.addEventListener('click', () => {
                        const btn = document.querySelector('.doc-nav-item[data-pagina="' + pg + '"]');
                        carregarPagina(pg, btn ? btn.dataset.titulo : pg);
                    });
                });

                renderizarMermaid();
                mostrar('markdown');
            })
            .catch(err => {
                console.error('[Documentação]', err);
                elErrorMsg.textContent = 'Não foi possível carregar "' + pagina + '". ' + err.message;
                mostrar('error');
            });
    }

    document.querySelectorAll('.doc-nav-item').forEach(btn => {
        btn.addEventListener('click', () => carregarPagina(btn.dataset.pagina, btn.dataset.titulo));
    });

    document.querySelectorAll('.doc-welcome-card').forEach(card => {
        card.addEventListener('click', () => carregarPagina(card.dataset.pagina, card.dataset.titulo));
    });

    const btnCopiar = document.getElementById('btn-doc-copiar');
    if (btnCopiar) {
        btnCopiar.addEventListener('click', async () => {
            if (!mdAtual) {
                mostrarAlerta('Selecione uma página de documentação primeiro.', 'aviso');
                return;
            }
            try {
                await navigator.clipboard.writeText(mdAtual);
                mostrarAlerta('Markdown copiado para a área de transferência!', 'sucesso');
            } catch (e) {
                mostrarAlerta('Não foi possível copiar automaticamente.', 'erro');
            }
        });
    }

    const btnExportar = document.getElementById('btn-doc-exportar');
    if (btnExportar) {
        btnExportar.addEventListener('click', () => {
            if (!mdAtual) {
                mostrarAlerta('Selecione uma página de documentação primeiro.', 'aviso');
                return;
            }
            const blob = new Blob([mdAtual], { type: 'text/markdown;charset=utf-8' });
            const url = URL.createObjectURL(blob);
            const a = document.createElement('a');
            a.href = url;
            a.download = (paginaAtual || 'documentacao') + '.md';
            document.body.appendChild(a);
            a.click();
            document.body.removeChild(a);
            URL.revokeObjectURL(url);
        });
    }

    const btnImprimir = document.getElementById('btn-doc-imprimir');
    if (btnImprimir) {
        btnImprimir.addEventListener('click', () => window.print());
    }

    mostrar('welcome');
}
