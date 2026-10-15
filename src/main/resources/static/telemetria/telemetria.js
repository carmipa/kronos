import { mostrarAlerta } from '../js/app.js';
// Mesma URL (com a versão) que o app.js usa: o navegador reaproveita a instância do módulo.
import { rotuloDe } from '../js/dicas.js?v=1.1';

let jvmCpuHistory = [];
let jvmHeapHistory = [];
let jvmLabelsHistory = [];

// Estado da tabela de Historico Operacional (busca + filtro + paginacao)
const ITENS_POR_PAGINA = 12;
let historicoCompleto = [];
let filtroOperacaoAtivo = 'todas';
let termoBuscaTabela = '';
let paginaAtualTabela = 1;

const CORES_ORIGEM = {
    LLM: '#8b5cf6',
    LORE: '#ec4899',
    GOOGLE: '#3b82f6',
    CACHE: '#06b6d4',
    SISTEMA: '#8b97ad'
};

const NOME_OPERACAO_LORE = 'Revisao de Lore (.ass LLM)';

// Configuração de tema dark global do Chart.js
function configurarTemaDarkChart() {
    if (typeof Chart === 'undefined') return;

    Chart.defaults.color = '#8b97ad';
    Chart.defaults.font.family = "'Outfit', 'Inter', sans-serif";
    Chart.defaults.font.size = 10;
    Chart.defaults.plugins.tooltip.backgroundColor = '#0c1422';
    Chart.defaults.plugins.tooltip.borderColor = 'rgba(255, 255, 255, 0.08)';
    Chart.defaults.plugins.tooltip.borderWidth = 1;
    Chart.defaults.plugins.tooltip.cornerRadius = 6;
    Chart.defaults.plugins.tooltip.titleColor = '#eef2f8';
    Chart.defaults.plugins.tooltip.bodyColor = '#8b97ad';
    Chart.defaults.plugins.tooltip.usePointStyle = true;
    Chart.defaults.plugins.legend.labels.boxWidth = 8;
    Chart.defaults.plugins.legend.labels.usePointStyle = true;
    Chart.defaults.plugins.legend.labels.pointStyle = 'circle';
    Chart.defaults.plugins.legend.labels.color = '#8b97ad';
    Chart.defaults.scale.grid.color = 'rgba(255, 255, 255, 0.04)';
    Chart.defaults.scale.border = { display: false };
    Chart.defaults.scale.ticks.color = '#8b97ad';
}

/**
 * PROPÓSITO DE NEGÓCIO: monta as abas de telemetria POR FATIA e mostra as
 * operações da aba escolhida.
 *
 * INVARIANTES DO DOMÍNIO: aba só existe para fatia que tem consolidado — aba
 * vazia não contribui com ninguém. Nenhuma fatia significa "ainda não
 * consolidado", e a tela diz isso em vez de exibir abas fantasma.
 *
 * COMPORTAMENTO EM CASO DE FALHA: falha de rede aparece como aviso no próprio
 * card e não derruba o resto do painel de telemetria.
 */
async function carregarFatias(selecionar) {
    const abas = document.getElementById('fatias-abas');
    const conteudo = document.getElementById('fatias-conteudo');
    const aviso = document.getElementById('fatias-aviso');
    const total = document.getElementById('fatias-total');
    if (!abas || !conteudo) return;

    aviso.classList.add('hidden');
    let dados;
    try {
        const res = await fetch('/api/telemetria/fatias');
        if (!res.ok) throw new Error(`HTTP ${res.status}`);
        dados = await res.json();
    } catch (err) {
        aviso.textContent = `Falha ao listar as fatias: ${err.message}`;
        aviso.classList.remove('hidden');
        return;
    }

    const fatias = dados.fatias || [];
    const soma = fatias.reduce((acc, f) => acc + (f.total || 0), 0);
    if (total) total.textContent = `${soma.toLocaleString('pt-BR')} operações`;

    abas.innerHTML = '';
    if (fatias.length === 0) {
        conteudo.innerHTML = '<p class="fatias-vazio">Ainda não consolidado. Use <strong>Consolidar</strong>'
            + ' para reunir a telemetria das pastas de relatório.</p>';
        return;
    }

    const alvo = selecionar || fatias[0].fatia;
    for (const f of fatias) {
        const b = document.createElement('button');
        b.type = 'button';
        b.className = 'fatia-aba' + (f.fatia === alvo ? ' ativo' : '');
        b.setAttribute('role', 'tab');
        b.setAttribute('aria-selected', String(f.fatia === alvo));
        b.dataset.tooltip = `Mostra as operações da fatia ${f.fatia}; o número é quantas ela tem registradas.`;
        b.innerHTML = '<span></span><span class="fatia-contagem"></span>';
        b.firstElementChild.textContent = f.fatia;
        b.lastElementChild.textContent = (f.total || 0).toLocaleString('pt-BR');
        b.onclick = () => carregarFatias(f.fatia);
        abas.appendChild(b);
    }
    await mostrarFatia(alvo);
}

async function mostrarFatia(fatia) {
    const conteudo = document.getElementById('fatias-conteudo');
    conteudo.innerHTML = '<p class="fatias-vazio">Carregando...</p>';
    let dados;
    try {
        const res = await fetch(`/api/telemetria/fatias/${encodeURIComponent(fatia)}`);
        if (!res.ok) throw new Error(`HTTP ${res.status}`);
        dados = await res.json();
    } catch (err) {
        conteudo.innerHTML = '';
        const p = document.createElement('p');
        p.className = 'fatias-vazio';
        p.textContent = `Não foi possível ler a fatia: ${err.message}`;
        conteudo.appendChild(p);
        return;
    }

    const ops = dados.operacoes || [];
    if (ops.length === 0) {
        conteudo.innerHTML = '<p class="fatias-vazio">Esta fatia não tem operações registradas.</p>';
        return;
    }

    const tabela = document.createElement('table');
    tabela.className = 'tabela-fatia';
    // data-tooltip: js/dicas.js põe o "?" canônico em cada cabeçalho (regra 6-A)
    tabela.innerHTML = '<thead><tr>'
        + '<th data-tooltip="Que tipo de operação a fatia registrou.">Tipo</th>'
        + '<th data-tooltip="Descrição curta da operação, como veio no registro de telemetria.">Detalhe</th>'
        + '<th data-tooltip="Duração total da operação, em milissegundos.">Tempo</th>'
        + '<th data-tooltip="Quantos arquivos a operação processou.">Arquivos</th>'
        + '<th data-tooltip="Quantos itens a operação encontrou para tratar; o que conta como item depende da fatia.">Detectados</th>'
        + '<th data-tooltip="Quantos dos itens encontrados a operação de fato corrigiu.">Corrigidos</th>'
        + '<th data-tooltip="Data e hora em que a operação foi registrada.">Quando</th>'
        + '</tr></thead>';
    const corpo = document.createElement('tbody');
    // Mais recentes primeiro, e teto de 300 linhas: a fatia de auditoria tem
    // 2.930 operações e renderizar tudo trava a aba sem entregar nada a mais.
    const visiveis = [...ops].reverse().slice(0, 300);
    for (const o of visiveis) {
        const tr = document.createElement('tr');
        for (const v of [o.tipo, o.detalhe, `${o.tempoTotalMs ?? 0} ms`,
                         o.arquivosProcessados, o.itensDetectados, o.itensCorrigidos, dataHoraLocal(o.registradoEm)]) {
            const td = document.createElement('td');
            td.textContent = v === null || v === undefined ? '' : String(v);
            tr.appendChild(td);
        }
        corpo.appendChild(tr);
    }
    tabela.appendChild(corpo);

    conteudo.innerHTML = '';
    if (ops.length > visiveis.length) {
        const nota = document.createElement('p');
        nota.className = 'fatias-nota';
        nota.textContent = `Mostrando as ${visiveis.length} mais recentes de ${ops.length}.`;
        conteudo.appendChild(nota);
    }
    const rolagem = document.createElement('div');
    rolagem.className = 'tabela-fatia-wrap';
    rolagem.appendChild(tabela);
    conteudo.appendChild(rolagem);
}

export function initTelemetria() {
    configurarTemaDarkChart();

    const btnConsolidar = document.getElementById('btn-consolidar-fatias');
    if (btnConsolidar) {
        const rotulo = btnConsolidar.querySelector('span:last-child');
        btnConsolidar.addEventListener('click', async () => {
            btnConsolidar.disabled = true;
            const original = rotulo ? rotulo.textContent : '';
            if (rotulo) rotulo.textContent = 'Consolidando...';
            try {
                const res = await fetch('/api/telemetria/consolidar-fatias', { method: 'POST' });
                const d = await res.json().catch(() => ({}));
                if (!res.ok) throw new Error(d.erro || `HTTP ${res.status}`);
                // O número BRUTO e o GRAVADO são reportados juntos de propósito:
                // a diferença é quanto era duplicata, e escondê-la faria o total
                // parecer menor sem explicação.
                mostrarAlerta(`Consolidado: ${d.operacoesGravadas} operações de ${d.arquivosLidos} arquivo(s)`
                    + (d.arquivosPulados ? ` — ${d.arquivosPulados} ilegível(is) pulado(s)` : ''), 'sucesso');
                await carregarFatias();
            } catch (err) {
                mostrarAlerta(`Falha ao consolidar: ${err.message}`, 'erro');
            } finally {
                if (rotulo) rotulo.textContent = original;
                btnConsolidar.disabled = false;
            }
        });
    }

    carregarFatias();

    const btnRefresh = document.getElementById('btn-refresh-telemetria');
    if (btnRefresh) {
        btnRefresh.addEventListener('click', carregarDadosTelemetria);
    }

    const btnExportar = document.getElementById('btn-exportar-telemetria');
    if (btnExportar) {
        btnExportar.addEventListener('click', () => {
            window.open('/api/telemetria/exportar', '_blank');
        });
    }

    // Publica a telemetria sanitizada como dataset público no repositório Git
    // dedicado (kronos-anime-translation-telemetry-dataset): snapshot + commit + push.
    const btnPublicar = document.getElementById('btn-publicar-dataset');
    if (btnPublicar) {
        const rotulo = btnPublicar.querySelector('span:last-child');
        btnPublicar.addEventListener('click', async () => {
            btnPublicar.disabled = true;
            const textoOriginal = rotulo ? rotulo.textContent : '';
            if (rotulo) rotulo.textContent = 'Publicando...';
            try {
                const res = await fetch('/api/telemetria/publicar-dataset', { method: 'POST' });
                const dados = await res.json();
                if (res.ok && dados.pushOk) {
                    mostrarAlerta(dados.mensagem, 'sucesso');
                } else {
                    mostrarAlerta(dados.mensagem || 'Falha ao publicar o dataset.', res.ok ? 'aviso' : 'erro');
                }
            } catch (e) {
                mostrarAlerta('Erro de conexão ao publicar o dataset: ' + e.message, 'erro');
            } finally {
                btnPublicar.disabled = false;
                if (rotulo) rotulo.textContent = textoOriginal;
            }
        });
    }

    const campoBusca = document.getElementById('t-table-search');
    if (campoBusca) {
        campoBusca.addEventListener('input', () => {
            termoBuscaTabela = semAcento(campoBusca.value.trim());
            paginaAtualTabela = 1;
            renderizarTabelaHistorico();
        });
    }

    const btnPaginaAnterior = document.getElementById('t-page-prev');
    if (btnPaginaAnterior) {
        btnPaginaAnterior.addEventListener('click', () => {
            if (paginaAtualTabela > 1) {
                paginaAtualTabela -= 1;
                renderizarTabelaHistorico();
            }
        });
    }

    const btnPaginaProxima = document.getElementById('t-page-next');
    if (btnPaginaProxima) {
        btnPaginaProxima.addEventListener('click', () => {
            const totalPaginas = Math.max(1, Math.ceil(filtrarHistorico().length / ITENS_POR_PAGINA));
            if (paginaAtualTabela < totalPaginas) {
                paginaAtualTabela += 1;
                renderizarTabelaHistorico();
            }
        });
    }

    const btnFiltrarLore = document.getElementById('btn-filtrar-lore-historico');
    if (btnFiltrarLore) {
        btnFiltrarLore.addEventListener('click', () => {
            filtroOperacaoAtivo = NOME_OPERACAO_LORE;
            paginaAtualTabela = 1;
            const campoBusca = document.getElementById('t-table-search');
            if (campoBusca) campoBusca.value = '';
            termoBuscaTabela = '';
            renderizarChipsFiltro();
            renderizarTabelaHistorico();
            document.querySelector('.telemetry-table-card')?.scrollIntoView({ behavior: 'smooth', block: 'nearest' });
        });
    }

    // Atualizações em tempo real via SSE (o servidor emite a cada registro de
    // telemetria e num heartbeat de 5s com CPU/heap da JVM).
    iniciarStreamTelemetria();

    // Fallback: se o stream SSE cair/não conectar, mantém o polling de 10s.
    setInterval(() => {
        const panel = document.getElementById('panel-telemetria');
        if (!sseAtivo && panel && panel.classList.contains('active')) {
            carregarDadosTelemetria();
        }
    }, 10000);

    // O cartão do provedor era texto fixo ("Conectado", "Inferido localmente") e a latência
    // mostrada era a da própria tela. Agora pergunta ao LM Studio, como o card do Início.
    atualizarStatusLlm();
    setInterval(() => {
        const panel = document.getElementById('panel-telemetria');
        if (panel && panel.classList.contains('active')) atualizarStatusLlm();
    }, 15000);
}

let sseAtivo = false;

/** O selo do stream mostra o estado real da conexão SSE (antes dizia "Stream ativo" sempre). */
function definirSeloStream(ativo) {
    const selo = document.getElementById('t-stream-status');
    if (!selo) return;
    selo.textContent = ativo ? 'Stream ativo' : 'Reconectando — atualiza a cada 10 s';
    selo.classList.toggle('stream', ativo);
}

function iniciarStreamTelemetria() {
    if (typeof EventSource === 'undefined') {
        definirSeloStream(false);
        return;
    }
    try {
        const stream = new EventSource('/api/telemetria/stream');
        stream.addEventListener('telemetria', (evento) => {
            sseAtivo = true;
            definirSeloStream(true);
            const panel = document.getElementById('panel-telemetria');
            if (!panel || !panel.classList.contains('active')) return;
            try {
                aplicarDadosTelemetria(JSON.parse(evento.data), null);
            } catch (err) {
                console.error('Erro ao aplicar telemetria SSE:', err);
            }
        });
        stream.onerror = () => {
            // O navegador reconecta sozinho; enquanto isso o polling assume.
            sseAtivo = false;
            definirSeloStream(false);
        };
    } catch (err) {
        console.error('Não foi possível abrir o stream SSE de telemetria:', err);
        sseAtivo = false;
        definirSeloStream(false);
    }
}

/**
 * PROPÓSITO DE NEGÓCIO: o cartão "Provedor" diz se o LM Studio está no ar, qual modelo está
 * carregado e quanto a verificação levou — o mesmo /api/llm/status do card do Início.
 *
 * INVARIANTES DO DOMÍNIO: nada é afirmado sem a resposta: "Conectado" só com servidor no ar
 * E modelo carregado; o tempo mostrado é o desta verificação, não o da tela.
 *
 * COMPORTAMENTO EM CASO DE FALHA: erro de rede vira "Status indisponível" e o selo
 * "Desconectado"; nada é lançado.
 */
async function atualizarStatusLlm() {
    const modeloEl = document.getElementById('t-modelo-ativo');
    const statusEl = document.getElementById('t-llm-status');
    const tempoEl = document.getElementById('t-latencia');
    const definir = (modelo, status, cor, titulo) => {
        if (modeloEl) { modeloEl.textContent = modelo; modeloEl.title = titulo || modelo; }
        if (statusEl) {
            statusEl.textContent = status;
            statusEl.classList.remove('accent-green', 'accent-yellow', 'accent-red');
            statusEl.classList.add(cor);
        }
    };
    const inicio = performance.now();
    try {
        const res = await fetch('/api/llm/status');
        if (!res.ok) throw new Error('HTTP ' + res.status);
        const s = await res.json();
        if (tempoEl) tempoEl.textContent = `${Math.round(performance.now() - inicio)} ms`;
        if (s.online && s.modeloCarregado && s.modelo) {
            definir(s.modelo, 'Conectado', 'accent-green');
        } else if (s.online) {
            definir('Nenhum modelo carregado', 'Sem modelo', 'accent-yellow', s.mensagem);
        } else {
            definir('LM Studio desconectado', 'Desconectado', 'accent-red', s.mensagem);
        }
    } catch (err) {
        if (tempoEl) tempoEl.textContent = '-- ms';
        definir('Status indisponível', 'Desconectado', 'accent-red', String(err && err.message ? err.message : err));
    }
}

/** Instante gravado em UTC (ISO-8601) mostrado na hora do computador de quem usa (decisão de 05/07). */
function dataHoraLocal(iso) {
    if (!iso) return '-';
    const d = new Date(iso);
    return Number.isNaN(d.getTime()) ? String(iso) : d.toLocaleString('pt-BR');
}

/** Minúsculas e sem acento: a busca acha "Tradução" digitando "traducao" e vice-versa. */
function semAcento(texto) {
    return String(texto ?? '').normalize('NFD').replace(/\p{M}/gu, '').toLowerCase();
}

async function carregarDadosTelemetria() {
    const tableBody = document.getElementById('telemetria-table-body');
    try {
        const inicio = performance.now();
        const res = await fetch('/api/telemetria');
        if (!res.ok) throw new Error('Não foi possível obter dados de telemetria');

        const data = await res.json();
        const latenciaMs = Math.round(performance.now() - inicio);
        aplicarDadosTelemetria(data, latenciaMs);
    } catch (err) {
        console.error('Erro ao atualizar telemetria:', err);
        if (tableBody && tableBody.children.length === 0) {
            renderizarLinhaVazia(tableBody, 'Não foi possível carregar a telemetria agora.');
        }
    }
}

function aplicarDadosTelemetria(data, latenciaMs) {
    const episodiosVal = document.getElementById('t-episodios');
    const linhasVal = document.getElementById('t-linhas');
    const tempoLinhaVal = document.getElementById('t-tempo-linha');
    const cacheHitsVal = document.getElementById('t-cache-hits');
    const cacheFilesVal = document.getElementById('t-cache-files');
    const cacheRateVal = document.getElementById('t-cache-rate');
    const cacheStatusVal = document.getElementById('t-cache-status');
    const cacheMeterVal = document.getElementById('t-cache-meter');
    const cacheSavedVal = document.getElementById('t-cache-saved');
    const operacoesVal = document.getElementById('t-operacoes');
    const auditLineVal = document.getElementById('t-audit-line');
    const errosVal = document.getElementById('t-erros');
    const chamadasLlmVal = document.getElementById('t-chamadas-llm');
    const arquivosSanitizadosVal = document.getElementById('t-arquivos-sanitizados');

    const totalLinhas = Number(data.totalLinhas ?? 0);
        const cacheHits = Number(data.totalCacheHits ?? 0);
        // Mesma unidade dos dois lados: textos distintos vindos do cache sobre textos distintos
        // (cache + LLM). Antes dividia por falas traduzíveis COM repetição e subestimava o cache.
        const funil = somarFunil(data.traducoesLlm);
        const textosDistintos = funil.cache + funil.llm;
        const cacheRate = textosDistintos > 0 ? Math.min(100, (funil.cache / textosDistintos) * 100) : 0;
        const historico = Array.isArray(data.historicoOperacoes) ? data.historicoOperacoes : [];
        const statusCache = cacheRate >= 50 ? 'ótimo' : cacheRate > 0 ? 'aquecendo' : 'estável';

        // Atualiza os cards estatísticos
        if (episodiosVal) episodiosVal.textContent = formatarNumero(data.totalEpisodios ?? 0);
        if (linhasVal) linhasVal.textContent = formatarNumero(totalLinhas);
        if (tempoLinhaVal) tempoLinhaVal.textContent = `${data.tempoMedioPorLinhaMs ?? 0} ms`;
        if (cacheHitsVal) cacheHitsVal.textContent = formatarNumero(cacheHits);
        if (cacheFilesVal) cacheFilesVal.textContent = formatarNumero(data.cacheCount ?? 0);
        if (cacheRateVal) cacheRateVal.textContent = `${cacheRate.toFixed(1)}%`;
        if (cacheStatusVal) cacheStatusVal.textContent = statusCache;
        if (cacheMeterVal) cacheMeterVal.style.width = `${cacheRate}%`;
        // A "Latência" do cartão do provedor é a do LM Studio (atualizarStatusLlm), não a desta tela.
        // Mesma fonte da barra "Do cache" ao lado: no retrato cortado da vitrine o agregado diverge da lista.
        if (cacheSavedVal) cacheSavedVal.textContent = `${formatarNumero(funil.cache)} chamadas`;
        if (operacoesVal) operacoesVal.textContent = formatarNumero(historico.length);
        if (errosVal) errosVal.textContent = formatarNumero(data.totalErros ?? 0);
        if (arquivosSanitizadosVal) arquivosSanitizadosVal.textContent = formatarNumero(data.arquivosSanitizados ?? 0);
        if (chamadasLlmVal) {
            const totalChamadasLlm = historico.filter(op => op.origem === 'LLM').length;
            chamadasLlmVal.textContent = formatarNumero(totalChamadasLlm);
        }
        if (auditLineVal) {
            const hora = new Date().toLocaleTimeString('pt-BR');
            auditLineVal.textContent = `[${hora}] ${formatarNumero(historico.length)} operações, ${formatarNumero(totalLinhas)} falas traduzíveis e ${cacheRate.toFixed(1)}% de cache registrados.`;
        }

        // Atualiza estatísticas detalhadas do hardware JVM
        const cpuVal = numeroSeguro(data.jvmCpuUso ?? data.jvmCpu, 0);
        const thrVal = numeroSeguro(data.jvmThreadsAtivas ?? data.jvmThreads, 0);
        const heapUsadoBytes = numeroSeguro(data.jvmHeapUsadoBytes ?? data.heapUsed, 0);
        const heapMaxBytes = Math.max(1, numeroSeguro(data.jvmHeapMaxBytes ?? data.heapMax, 1));
        const heapUsedMb = heapUsadoBytes / 1024 / 1024;
        const heapMaxMb = heapMaxBytes / 1024 / 1024;
        const heapPercent = Math.min(100, Math.max(0, (heapUsadoBytes / heapMaxBytes) * 100));

        const cpuEl = document.getElementById('t-jvm-cpu');
        const thrEl = document.getElementById('t-jvm-threads');
        const heapEl = document.getElementById('t-jvm-heap');

        if (cpuEl) cpuEl.textContent = `${cpuVal.toFixed(1)}%`;
        if (thrEl) thrEl.textContent = formatarNumero(thrVal);
        if (heapEl) heapEl.textContent = `${Math.round(heapUsedMb)} MB / ${Math.round(heapMaxMb)} MB`;

        renderizarGraficosTelemetria(data, totalLinhas, cacheHits, cpuVal, heapPercent);
        renderizarRevisaoLore(data.revisaoLore);

        // Atualiza o Historico Operacional (busca + filtros + paginacao)
        historicoCompleto = historico;
        renderizarChipsFiltro();
        paginaAtualTabela = Math.min(paginaAtualTabela, Math.max(1, Math.ceil(filtrarHistorico().length / ITENS_POR_PAGINA)));
        renderizarTabelaHistorico();
}

// Nomes gravados sem acento pelo backend: o histórico guarda o nome cru (filtro e chave não
// mudam), a tela mostra com acento. "Analise de Midia" é a operação do LOTE; a linha por
// arquivo já vem como "Análise de Mídia".
const ROTULOS_SEM_ACENTO = [
    [/^Analise de Midia$/, 'Análise de Mídia (lote)'],
    [/\bExtracao\b/, 'Extração'],
    [/\bRevisao\b/, 'Revisão'],
    [/\bTraducao\b/, 'Tradução'],
];

function rotuloExibicaoOperacao(nome) {
    if (!nome) return 'Desconhecida';
    if (nome === NOME_OPERACAO_LORE || nome.includes('Revisao de Lore')) {
        return 'Revisão de Lore';
    }
    return ROTULOS_SEM_ACENTO.reduce((rotulo, [padrao, novo]) => rotulo.replace(padrao, novo), nome);
}

function renderizarRevisaoLore(resumo) {
    const sessoesEl = document.getElementById('t-lore-sessoes');
    const arquivosEl = document.getElementById('t-lore-arquivos');
    const sinalizadasEl = document.getElementById('t-lore-sinalizadas');
    const corrigidasEl = document.getElementById('t-lore-corrigidas');
    const taxaEl = document.getElementById('t-lore-taxa');
    const hintEl = document.getElementById('t-lore-hint');
    const btnFiltrar = document.getElementById('btn-filtrar-lore-historico');

    const sessoes = Number(resumo?.totalSessoes ?? 0);
    const arquivos = Number(resumo?.totalArquivosProcessados ?? 0);
    const sinalizadas = Number(resumo?.totalFalasSinalizadas ?? 0);
    const corrigidas = Number(resumo?.totalFalasCorrigidas ?? 0);
    const taxa = resumo?.taxaCorrecaoPercent;

    if (sessoesEl) sessoesEl.textContent = formatarNumero(sessoes);
    if (arquivosEl) arquivosEl.textContent = formatarNumero(arquivos);
    if (sinalizadasEl) sinalizadasEl.textContent = formatarNumero(sinalizadas);
    if (corrigidasEl) corrigidasEl.textContent = formatarNumero(corrigidas);
    if (taxaEl) {
        // FRACAO, nao porcentagem. Medido em 17/08/2026: 91 correcoes em 24.441 falas
        // sinalizadas saiam como "0%", porque o backend divide inteiro por inteiro
        // (91*100/24441 = 0). O operador lia zero e concluia que a tela nao corrigiu nada —
        // e ela tinha acabado de consertar 13 falas do 86.
        //
        // Percentual e um numero ruim NESTA tela por natureza: o denominador inclui tudo o que
        // foi sinalizado, e a maioria e falso positivo estrutural (o ingles nao marca genero, o
        // portugues marca) ou trabalho de outra tela. "91 de 24.441" nao engana ninguem.
        taxaEl.textContent = sinalizadas > 0
            ? `${formatarNumero(corrigidas)} de ${formatarNumero(sinalizadas)}`
            : '—';
    }
    if (hintEl) {
        hintEl.textContent = sessoes > 0
            ? `${formatarNumero(sessoes)} sessão(ões) registrada(s) no histórico do pipeline.`
            : 'Nenhuma sessão de revisão de lore registrada ainda.';
    }
    if (btnFiltrar) {
        btnFiltrar.hidden = sessoes <= 0;
    }
}

function formatarNumero(valor) {
    return Number(valor ?? 0).toLocaleString('pt-BR');
}

function numeroSeguro(valor, padrao = 0) {
    const numero = Number(valor);
    return Number.isFinite(numero) ? numero : padrao;
}

// ---- Historico Operacional: filtros, busca, chips e paginacao ----

function filtrarHistorico() {
    return historicoCompleto.filter(op => {
        if (filtroOperacaoAtivo !== 'todas' && (op.nomeOperacao ?? '') !== filtroOperacaoAtivo) {
            return false;
        }
        if (!termoBuscaTabela) return true;
        // O nome exibido (com acento) entra junto do cru: quem digita o que lê no chip acha.
        const alvo = semAcento(`${rotuloExibicaoOperacao(op.nomeOperacao)} ${op.nomeOperacao ?? ''} ${op.detalheOperacao ?? ''}`);
        return alvo.includes(termoBuscaTabela);
    });
}

function renderizarChipsFiltro() {
    const container = document.getElementById('t-filter-chips');
    if (!container) return;

    const contagemPorTipo = new Map();
    historicoCompleto.forEach(op => {
        const nome = op.nomeOperacao ?? 'Desconhecida';
        if (!contagemPorTipo.has(nome)) {
            contagemPorTipo.set(nome, { total: 0, origem: op.origem ?? 'SISTEMA' });
        }
        contagemPorTipo.get(nome).total += 1;
    });

    // Se o filtro ativo deixou de existir no dataset atual, volta para "Todas"
    if (filtroOperacaoAtivo !== 'todas' && !contagemPorTipo.has(filtroOperacaoAtivo)) {
        filtroOperacaoAtivo = 'todas';
    }

    const chips = [criarChip('todas', 'Todas', historicoCompleto.length, CORES_ORIGEM.SISTEMA)];
    contagemPorTipo.forEach((info, nome) => {
        chips.push(criarChip(nome, rotuloExibicaoOperacao(nome), info.total, CORES_ORIGEM[info.origem] ?? CORES_ORIGEM.SISTEMA));
    });

    container.innerHTML = '';
    chips.forEach(chip => container.appendChild(chip));
}

function criarChip(valor, label, contagem, cor) {
    const btn = document.createElement('button');
    btn.type = 'button';
    btn.className = 'filter-chip' + (filtroOperacaoAtivo === valor ? ' active' : '');
    btn.dataset.filtro = valor;
    btn.dataset.tooltip = valor === 'todas'
        ? 'Mostra todas as operações do histórico; o número é o total.'
        : `Mostra só as operações "${label}" no histórico; o número é quantas há.`;
    btn.innerHTML = `
        <span class="chip-dot" style="background:${cor}"></span>
        <span class="chip-label">${label}</span>
        <span class="chip-count">${formatarNumero(contagem)}</span>
    `;
    btn.addEventListener('click', () => {
        filtroOperacaoAtivo = valor;
        paginaAtualTabela = 1;
        renderizarChipsFiltro();
        renderizarTabelaHistorico();
    });
    return btn;
}

function classeDuracao(duracaoMs) {
    if (duracaoMs === null || duracaoMs === undefined) return '';
    if (duracaoMs < 5000) return 'duration-fast';
    if (duracaoMs < 60000) return 'duration-mid';
    return 'duration-slow';
}

function origemBadgeClass(origem) {
    switch (origem) {
        case 'LLM': return 'origin-llm';
        case 'LORE': return 'origin-lore';
        case 'GOOGLE': return 'origin-google';
        case 'CACHE': return 'origin-cache';
        default: return 'origin-sistema';
    }
}

function renderizarTabelaHistorico() {
    const tableBody = document.getElementById('telemetria-table-body');
    const contadorTexto = document.getElementById('t-table-count');
    const paginacaoInfo = document.getElementById('t-pagination-info');
    const btnAnterior = document.getElementById('t-page-prev');
    const btnProxima = document.getElementById('t-page-next');
    if (!tableBody) return;

    const filtrados = filtrarHistorico();
    const totalPaginas = Math.max(1, Math.ceil(filtrados.length / ITENS_POR_PAGINA));
    paginaAtualTabela = Math.min(Math.max(1, paginaAtualTabela), totalPaginas);

    const inicio = (paginaAtualTabela - 1) * ITENS_POR_PAGINA;
    const pagina = filtrados.slice(inicio, inicio + ITENS_POR_PAGINA);

    const rotulos = Array.from(tableBody.closest('table')?.querySelectorAll('thead th') ?? [], rotuloDe);

    tableBody.innerHTML = '';
    if (pagina.length === 0) {
        renderizarLinhaVazia(tableBody, historicoCompleto.length === 0
            ? 'Aguardando dados de processamento...'
            : 'Nenhuma operação corresponde à busca ou ao filtro atual.');
    } else {
        pagina.forEach(op => {
            const row = document.createElement('tr');
            const origem = op.origem ?? 'SISTEMA';
            const cor = CORES_ORIGEM[origem] ?? CORES_ORIGEM.SISTEMA;

            // registradoEm chega em UTC (ISO-8601) e é mostrado na hora local do navegador:
            // guardar em UTC, exibir local (decisão de 05/07; antes saía forçado em UTC).
            const tdData = document.createElement('td');
            tdData.className = 'op-datetime';
            tdData.textContent = dataHoraLocal(op.registradoEm);

            const tdOperacao = document.createElement('td');
            tdOperacao.innerHTML = `<span class="op-name"><span class="chip-dot" style="background:${cor}"></span>${rotuloExibicaoOperacao(op.nomeOperacao)}</span>`;

            const tdOrigem = document.createElement('td');
            tdOrigem.innerHTML = `<span class="origin-badge ${origemBadgeClass(origem)}">${origem}</span>`;

            const tdDetalhe = document.createElement('td');
            tdDetalhe.className = 'op-key';
            tdDetalhe.title = op.detalheOperacao || '-';
            tdDetalhe.textContent = op.detalheOperacao || '-';

            const tdDuracao = document.createElement('td');
            tdDuracao.innerHTML = `<span class="op-duration ${classeDuracao(op.duracaoMs)}">${op.duracaoFormatada || '-'}</span>`;

            // "x de y", não percentual: a divisão inteira do backend mostrava "0%" para 91 de 24.441.
            const tdTaxa = document.createElement('td');
            const detectados = Number(op.itensDetectados ?? 0);
            tdTaxa.textContent = detectados > 0
                ? `${formatarNumero(op.itensCorrigidos ?? 0)} de ${formatarNumero(detectados)}`
                : (op.taxaSucesso === null || op.taxaSucesso === undefined ? '-' : `${op.taxaSucesso}%`);

            row.append(tdData, tdOperacao, tdOrigem, tdDetalhe, tdDuracao, tdTaxa);
            rotularCelulas(row, rotulos);
            tableBody.appendChild(row);
        });
    }

    if (contadorTexto) {
        const total = historicoCompleto.length;
        const totalFiltrado = filtrados.length;
        const fimIntervalo = Math.min(inicio + ITENS_POR_PAGINA, totalFiltrado);
        const intervaloTexto = totalFiltrado === 0 ? '0-0' : `${inicio + 1}-${fimIntervalo}`;
        contadorTexto.textContent = totalFiltrado === total
            ? `${formatarNumero(total)} operações registradas · exibindo ${intervaloTexto}`
            : `${formatarNumero(totalFiltrado)} de ${formatarNumero(total)} operações · exibindo ${intervaloTexto}`;
    }
    if (paginacaoInfo) paginacaoInfo.textContent = `Página ${paginaAtualTabela} de ${totalPaginas}`;
    if (btnAnterior) btnAnterior.disabled = paginaAtualTabela <= 1;
    if (btnProxima) btnProxima.disabled = paginaAtualTabela >= totalPaginas;
}

/**
 * PROPÓSITO DE NEGÓCIO: os números do funil — falas traduzíveis e quantos textos vieram do cache
 * ou foram traduzidos pelo LLM —, todos da MESMA lista de traduções por episódio.
 *
 * INVARIANTES DO DOMÍNIO: nada estimado (a antiga barra "Brutas" era o total + 15%); as três
 * barras saem da mesma população, então continuam coerentes quando a lista vem cortada (o retrato
 * da vitrine). "Traduzíveis" conta falas com repetição; cache e LLM contam textos distintos.
 *
 * COMPORTAMENTO EM CASO DE FALHA: lista ausente ou campo nulo soma zero; nada é lançado.
 */
function somarFunil(traducoes) {
    const lista = Array.isArray(traducoes) ? traducoes : [];
    return lista.reduce((acc, t) => ({
        traduziveis: acc.traduziveis + numeroSeguro(t.totalLinhas, 0),
        cache: acc.cache + numeroSeguro(t.falasDoCache, 0),
        llm: acc.llm + numeroSeguro(t.falasTraduzidas, 0),
    }), { traduziveis: 0, cache: 0, llm: 0 });
}

function renderizarGraficosTelemetria(data, totalLinhas, cacheHits, cpuVal, heapPercent) {
    const funil = somarFunil(data.traducoesLlm);
    renderizarFunil([
        { label: 'Traduzíveis', valor: funil.traduziveis, cor: '#818cf8' },
        { label: 'Do cache', valor: funil.cache, cor: '#2dd4bf' },
        { label: 'Pelo LLM', valor: funil.llm, cor: '#6366f1' }
    ]);

    const contagemModelos = {};
    const listaTraducoes = Array.isArray(data.traducoesLlm) ? data.traducoesLlm : [];
    listaTraducoes.forEach(t => {
        const modelo = t.modeloLlm || 'Desconhecido';
        contagemModelos[modelo] = (contagemModelos[modelo] || 0) + 1;
    });
    renderizarDonut(contagemModelos);

    const now = new Date();
    const timeLabel = now.getHours().toString().padStart(2, '0') + ':' +
        now.getMinutes().toString().padStart(2, '0') + ':' +
        now.getSeconds().toString().padStart(2, '0');

    jvmCpuHistory.push(cpuVal);
    jvmHeapHistory.push(heapPercent);
    jvmLabelsHistory.push(timeLabel);

    if (jvmCpuHistory.length > 15) {
        jvmCpuHistory.shift();
        jvmHeapHistory.shift();
        jvmLabelsHistory.shift();
    }
    renderizarLinhasJvm(jvmCpuHistory, jvmHeapHistory);
}

function renderizarFunil(itens) {
    const alvo = document.getElementById('chart-funil-processamento');
    if (!alvo) return;

    const maior = Math.max(1, ...itens.map(item => item.valor));
    alvo.innerHTML = itens.map(item => {
        const altura = Math.max(6, Math.round((item.valor / maior) * 76));
        return `
            <div class="telemetry-bar-item" style="min-width:0;height:100%;display:grid;grid-template-rows:16px 1fr 18px;justify-items:center;align-items:end;">
                <span class="telemetry-bar-value" style="color:var(--text-muted);font-size:.64rem;line-height:1;">${formatarNumero(item.valor)}</span>
                <span class="telemetry-bar" style="display:block;width:min(28px,58%);min-height:6px;height:${altura}px;background:${item.cor};border-radius:5px 5px 2px 2px;box-shadow:0 0 16px ${hexParaRgba(item.cor, 0.25)};"></span>
                <span class="telemetry-bar-label" style="width:100%;color:var(--text-muted);font-size:.62rem;text-align:center;white-space:nowrap;overflow:hidden;text-overflow:ellipsis;">${item.label}</span>
            </div>
        `;
    }).join('');
}

function renderizarDonut(contagemModelos) {
    const alvo = document.getElementById('chart-modelos-llm');
    if (!alvo) return;

    const entradas = Object.entries(contagemModelos);
    if (entradas.length === 0) {
        alvo.innerHTML = `
            <div class="telemetry-donut empty" style="width:92px;aspect-ratio:1;border-radius:50%;display:grid;place-items:center;position:relative;background:conic-gradient(rgba(139,151,173,.28) 0% 100%);">
                <span style="position:relative;z-index:1;color:var(--text-primary);font-size:.9rem;font-weight:800;">0</span>
            </div>
            <div class="telemetry-donut-label">Sem chamadas LLM</div>
        `;
        return;
    }

    // Do mais usado para o menos usado: antes o rótulo era o PRIMEIRO da ordem de inserção,
    // não o modelo mais frequente, e as outras cores não tinham legenda.
    entradas.sort((a, b) => b[1] - a[1]);
    const cores = ['#2dd4bf', '#818cf8', '#f59e0b', '#f43f5e', '#3b82f6'];
    const total = entradas.reduce((acc, [, valor]) => acc + valor, 0);
    let inicio = 0;
    const fatias = entradas.map(([, valor], idx) => {
        const fim = inicio + (valor / total) * 100;
        const trecho = `${cores[idx % cores.length]} ${inicio}% ${fim}%`;
        inicio = fim;
        return trecho;
    }).join(', ');
    const legenda = entradas.slice(0, 5).map(([modelo, valor], idx) => `
            <li style="display:flex;gap:.375rem;align-items:center;min-width:0;">
                <span aria-hidden="true" style="flex:none;width:.5rem;height:.5rem;border-radius:50%;background:${cores[idx % cores.length]};"></span>
                <span style="overflow:hidden;text-overflow:ellipsis;white-space:nowrap;" title="${escaparHtml(modelo)}">${escaparHtml(modelo)}</span>
                <strong style="margin-left:auto;">${formatarNumero(valor)}</strong>
            </li>`).join('');
    const resto = entradas.length > 5 ? `<li style="color:var(--text-muted);">+${entradas.length - 5} modelo(s)</li>` : '';

    alvo.innerHTML = `
        <div class="telemetry-donut" style="width:92px;aspect-ratio:1;border-radius:50%;display:grid;place-items:center;position:relative;background:conic-gradient(${fatias});box-shadow:0 0 22px rgba(45,212,191,.12);">
            <span style="position:relative;z-index:1;color:var(--text-primary);font-size:.9rem;font-weight:800;">${formatarNumero(total)}</span>
        </div>
        <ul class="telemetry-donut-legenda" aria-label="Episódios por modelo">${legenda}${resto}</ul>
    `;
}

function escaparHtml(texto) {
    return String(texto ?? '').replace(/[&<>"']/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
}

function renderizarLinhasJvm(cpuHistory, heapHistory) {
    const alvo = document.getElementById('chart-jvm-performance');
    if (!alvo) return;

    const cpuSeries = cpuHistory.length > 1 ? cpuHistory : [cpuHistory[0] ?? 0, cpuHistory[0] ?? 0];
    const heapSeries = heapHistory.length > 1 ? heapHistory : [heapHistory[0] ?? 0, heapHistory[0] ?? 0];
    const cpuPoints = montarPolyline(cpuSeries);
    const heapPoints = montarPolyline(heapSeries);
    alvo.innerHTML = `
        <svg class="telemetry-line-chart" viewBox="0 0 240 90" preserveAspectRatio="none" aria-hidden="true" style="width:100%;height:100%;display:block;">
            <line x1="0" y1="15" x2="240" y2="15" stroke="rgba(255,255,255,.045)" stroke-width="1"></line>
            <line x1="0" y1="45" x2="240" y2="45" stroke="rgba(255,255,255,.045)" stroke-width="1"></line>
            <line x1="0" y1="75" x2="240" y2="75" stroke="rgba(255,255,255,.045)" stroke-width="1"></line>
            <polyline class="line-heap" points="${heapPoints}" fill="none" stroke="#06b6d4" stroke-width="3" stroke-linecap="round" stroke-linejoin="round"></polyline>
            <polyline class="line-cpu" points="${cpuPoints}" fill="none" stroke="#8b5cf6" stroke-width="3" stroke-linecap="round" stroke-linejoin="round"></polyline>
        </svg>
    `;
}

function montarPolyline(valores) {
    const largura = 240;
    const altura = 82;
    const baseY = 86;
    const divisor = Math.max(1, valores.length - 1);
    return valores.map((valor, idx) => {
        const x = (idx / divisor) * largura;
        const y = baseY - (Math.min(100, Math.max(0, valor)) / 100) * altura;
        return `${x.toFixed(1)},${y.toFixed(1)}`;
    }).join(' ');
}

function hexParaRgba(hex, alpha) {
    const normalizado = hex.replace('#', '');
    const numero = Number.parseInt(normalizado, 16);
    if (!Number.isFinite(numero)) return `rgba(255,255,255,${alpha})`;
    const r = (numero >> 16) & 255;
    const g = (numero >> 8) & 255;
    const b = numero & 255;
    return `rgba(${r},${g},${b},${alpha})`;
}

/**
 * PROPÓSITO DE NEGÓCIO: no celular (até 640 px) o histórico vira um cartão por
 * operação e o cabeçalho da tabela some; sem rótulo, quem lê vê "12 s" e "83%"
 * sem saber de que coluna são. Cada célula ganha o nome da coluna em texto real.
 *
 * INVARIANTES DO DOMÍNIO: o rótulo sai do próprio `<th>` (fonte única, a mesma
 * que o Google traduz) e é texto, não `attr()` em CSS, para o tradutor e o
 * leitor de tela alcançarem. No computador ele fica `display: none` e a tabela
 * não muda.
 *
 * COMPORTAMENTO EM CASO DE FALHA: sem cabeçalho, ou com menos títulos que
 * células, a célula sem título correspondente fica sem rótulo; nada é lançado.
 */
function rotularCelulas(row, rotulos) {
    Array.from(row.cells).forEach((td, i) => {
        const rotulo = rotulos[i];
        if (!rotulo) return;
        const span = document.createElement('span');
        span.className = 'td-rotulo';
        span.textContent = rotulo;
        td.prepend(span);
    });
}

function renderizarLinhaVazia(tableBody, mensagem) {
    tableBody.innerHTML = '';
    const row = document.createElement('tr');
    const cell = document.createElement('td');
    cell.colSpan = 6;
    cell.className = 'table-empty';
    cell.textContent = mensagem;
    row.appendChild(cell);
    tableBody.appendChild(row);
}
