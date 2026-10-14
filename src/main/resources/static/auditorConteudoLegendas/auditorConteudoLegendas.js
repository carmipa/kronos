import { logNoConsole, mostrarAlerta, mensagemDoErro } from '../js/app.js';

// v4.0: entraram as visões de tabela comparativa e gráfico. Subir a versão é obrigatório —
// sem isso o navegador serve o HTML antigo em cache e os contêineres novos não existem, o
// que aparece como "a feature não funciona" sem erro nenhum no console.
const PAINEL_HTML = 'auditorConteudoLegendas/auditorConteudoLegendas.html?v=4.1';

const ORDEM_SEVERIDADE = { CRITICAL: 0, ERROR: 1, WARNING: 2 };

// Texto de apoio de cada aba de modo (comparativa vs arquivo único)
const DESCRICAO_MODO = {
    AMBAS: 'Compara o arquivo original com o traduzido e roda as regras de par (quebras, estilos, karaokê, efeitos, metadados).',
    ORIGINAL: 'Audita apenas o arquivo original (EN) com as regras estruturais e de tempo: tags {} não fechadas, timestamps inválidos, eventos vazios, quebras \\N excessivas, sobreposição de tempo e efeitos com texto longo.',
    TRADUZIDO: 'Audita apenas o arquivo traduzido (PT-BR) com as regras estruturais e de tempo: tags {} não fechadas, timestamps inválidos, eventos vazios, quebras \\N excessivas, sobreposição de tempo e efeitos com texto longo.'
};

const ROTULO_MODO = { AMBAS: 'Comparativo', ORIGINAL: 'Só Original (EN)', TRADUZIDO: 'Só Traduzida (PT-BR)' };

// Texto do estado "Arquivo limpo" conforme o alvo auditado em cada modo.
const MENSAGEM_LIMPO = {
    AMBAS: 'Nenhuma anomalia detectada. O traduzido passou em todas as regras de auditoria.',
    ORIGINAL: 'Nenhuma anomalia detectada. O arquivo original passou em todas as regras de auditoria.',
    TRADUZIDO: 'Nenhuma anomalia detectada. O arquivo traduzido passou em todas as regras de auditoria.'
};

// Modo selecionado nas abas; padrão comparativo (comportamento histórico).
let modoAtual = 'AMBAS';

// Configuração visual única por severidade (ícone, rótulo e sufixo de classe CSS)
const CONFIG_SEVERIDADE = {
    CRITICAL: { icone: 'gpp_bad', rotulo: 'Crítico', classe: 'critical' },
    ERROR: { icone: 'error', rotulo: 'Erro', classe: 'error' },
    WARNING: { icone: 'warning', rotulo: 'Aviso', classe: 'warning' }
};

// Cor por severidade, usada no gráfico. Mantida ao lado de CONFIG_SEVERIDADE de propósito:
// são a MESMA decisão visual, e separá-las é como duas paletas divergem.
const COR_SEVERIDADE = {
    CRITICAL: { fundo: 'rgba(239, 68, 68, 0.75)', borda: '#EF4444' },
    ERROR: { fundo: 'rgba(249, 115, 22, 0.75)', borda: '#F97316' },
    WARNING: { fundo: 'rgba(234, 179, 8, 0.75)', borda: '#EAB308' }
};

let ultimoRelatorio = null;
let filtroAtual = 'TODOS';
let visaoAtual = 'cards';
let graficoAnomalias = null;

async function carregarPainelHtml() {
    const painel = document.getElementById('panel-auditor-conteudo');
    if (!painel || painel.dataset.moduloCarregado === 'true') {
        return painel;
    }

    const resposta = await fetch(PAINEL_HTML);
    if (!resposta.ok) {
        throw new Error(`Falha ao carregar ${PAINEL_HTML}`);
    }

    painel.innerHTML = await resposta.text();
    painel.dataset.moduloCarregado = 'true';
    return painel;
}

export async function initAuditorConteudo() {
    try {
        await carregarPainelHtml();
        vincularEventos();
    } catch (err) {
        console.error('[Análise de Conteúdo] Erro ao carregar painel:', err);
        const painel = document.getElementById('panel-auditor-conteudo');
        if (painel) {
            painel.innerHTML = '<div class="glass-card"><p class="card-desc">Não foi possível carregar o painel de Análise de Conteúdo.</p></div>';
        }
    }
}

function vincularEventos() {
    const formAuditor = document.getElementById('form-auditor-conteudo');
    const lista = document.getElementById('auditor-anomalias-lista');
    const statusBadge = document.getElementById('auditor-status-badge');
    const btnExportarMd = document.getElementById('btn-exportar-auditor-md');
    const btnExportarJson = document.getElementById('btn-exportar-auditor-json');
    const btnLimpar = document.getElementById('btn-limpar-auditor');
    const CONSOLE_ID = 'console-auditor-conteudo';

    if (btnExportarMd) {
        btnExportarMd.addEventListener('click', () => exportarRelatorio('md'));
    }
    if (btnExportarJson) {
        btnExportarJson.addEventListener('click', () => exportarRelatorio('json'));
    }
    if (btnLimpar) {
        btnLimpar.addEventListener('click', () => {
            setTimeout(() => resetarRelatorioVisual(lista, statusBadge), 0);
        });
    }

    document.querySelectorAll('.auditor-visao-chip').forEach(chip => {
        chip.addEventListener('click', () => aplicarVisao(chip.dataset.visao));
    });

    vincularAbasModo();

    if (!formAuditor || !lista || !statusBadge) return;

    formAuditor.addEventListener('submit', async (e) => {
        e.preventDefault();

        const original = document.getElementById('auditor-original').value.trim();
        const traduzido = document.getElementById('auditor-traduzido').value.trim();

        if (modoAtual === 'AMBAS' && (!original || !traduzido)) {
            mostrarAlerta('Forneça os caminhos dos arquivos original e traduzido.', 'aviso');
            return;
        }
        if (modoAtual === 'ORIGINAL' && !original) {
            mostrarAlerta('Forneça o caminho do arquivo original (EN).', 'aviso');
            return;
        }
        if (modoAtual === 'TRADUZIDO' && !traduzido) {
            mostrarAlerta('Forneça o caminho do arquivo traduzido (PT-BR).', 'aviso');
            return;
        }

        ultimoRelatorio = null;
        filtroAtual = 'TODOS';
        ocultarResumoFiltrosELimpo();
        statusBadge.textContent = 'Auditando...';
        statusBadge.className = 'status-badge pulse-purple';
        lista.innerHTML = '<div class="auditor-lista-vazia">Analisando arquivos...</div>';
        logNoConsole(CONSOLE_ID, `Iniciando auditoria de conteúdo (${ROTULO_MODO[modoAtual]})...`, 'info');

        // Só envia o(s) caminho(s) relevante(s) ao modo selecionado.
        const corpo = { modo: modoAtual };
        if (modoAtual !== 'TRADUZIDO') corpo.caminhoOriginal = original;
        if (modoAtual !== 'ORIGINAL') corpo.caminhoTraduzido = traduzido;

        try {
            const response = await fetch('/api/auditoria-conteudo', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify(corpo)
            });

            if (!response.ok) {
                const errMsg = await response.text();
                throw new Error(mensagemDoErro(errMsg, 'Falha ao auditar.'));
            }

            const relatorio = await response.json();
            ultimoRelatorio = relatorio;
            renderizarRelatorio(relatorio, lista, statusBadge);
            logNoConsole(CONSOLE_ID, formatosDetectadosLog(relatorio), 'info');

            if (relatorio.limpo) {
                logNoConsole(CONSOLE_ID, 'Auditoria concluída — nenhuma anomalia detectada.', 'sucesso');
            } else {
                logNoConsole(CONSOLE_ID, `Auditoria concluída — ${relatorio.anomalias.length} anomalia(s) encontrada(s).`, 'aviso');
            }
            if (relatorio.caminhoRelatorioJson) {
                logNoConsole(CONSOLE_ID, `Relatório JSON salvo em disco: ${relatorio.caminhoRelatorioJson}`, 'info');
            }
        } catch (err) {
            ultimoRelatorio = null;
            ocultarResumoFiltrosELimpo();
            statusBadge.textContent = 'Erro';
            statusBadge.className = 'status-badge pulse-red';
            lista.innerHTML = `<div class="auditor-lista-vazia auditor-lista-erro">Erro: ${escapeHtml(err.message)}</div>`;
            logNoConsole(CONSOLE_ID, `Erro na auditoria: ${err.message}`, 'erro');
            console.error(err);
        }
    });
}

/**
 * PROPÓSITO DE NEGÓCIO: liga as abas de modo (Ambas / Só Original / Só Traduzida)
 * alternando a análise entre comparativa e de arquivo único.
 * INVARIANTES DO DOMÍNIO: só um modo fica ativo; os campos de arquivo não usados
 * pelo modo ficam ocultos para não confundir o usuário.
 * COMPORTAMENTO EM CASO DE FALHA: ausência das abas no DOM é ignorada sem erro.
 */
function vincularAbasModo() {
    const tabs = document.querySelectorAll('.auditor-modo-tab');
    if (!tabs.length) return;

    tabs.forEach(tab => {
        tab.addEventListener('click', () => {
            modoAtual = tab.dataset.modo || 'AMBAS';
            tabs.forEach(t => {
                const ativo = t === tab;
                t.classList.toggle('ativo', ativo);
                t.setAttribute('aria-selected', String(ativo));
            });
            aplicarVisibilidadeCampos();
        });
    });
    aplicarVisibilidadeCampos();
}

/**
 * PROPÓSITO DE NEGÓCIO: mostra apenas os campos de arquivo pertinentes ao modo e
 * atualiza o texto de apoio.
 * INVARIANTES DO DOMÍNIO: AMBAS mostra os dois campos; ORIGINAL só o original;
 * TRADUZIDO só o traduzido.
 * COMPORTAMENTO EM CASO DE FALHA: campos ausentes são ignorados.
 */
function aplicarVisibilidadeCampos() {
    const campoOriginal = document.querySelector('.auditor-campo-original');
    const campoTraduzido = document.querySelector('.auditor-campo-traduzido');
    const desc = document.getElementById('auditor-modo-desc');

    if (campoOriginal) campoOriginal.classList.toggle('hidden', modoAtual === 'TRADUZIDO');
    if (campoTraduzido) campoTraduzido.classList.toggle('hidden', modoAtual === 'ORIGINAL');
    if (desc) desc.textContent = DESCRICAO_MODO[modoAtual] || DESCRICAO_MODO.AMBAS;
}

/**
 * PROPÓSITO DE NEGÓCIO: monta a linha de log de formatos conforme o modo,
 * omitindo o lado que não foi auditado.
 * INVARIANTES DO DOMÍNIO: no arquivo único apenas um formato é relevante.
 * COMPORTAMENTO EM CASO DE FALHA: formato ausente vira "desconhecido".
 */
function formatosDetectadosLog(relatorio) {
    const modo = relatorio.modo || 'AMBAS';
    if (modo === 'ORIGINAL') {
        return `Formato detectado — original: ${relatorio.formatoOriginal || 'desconhecido'}`;
    }
    if (modo === 'TRADUZIDO') {
        return `Formato detectado — traduzido: ${relatorio.formatoTraduzido || 'desconhecido'}`;
    }
    return `Formatos detectados — original: ${relatorio.formatoOriginal || 'desconhecido'} · traduzido: ${relatorio.formatoTraduzido || 'desconhecido'}`;
}

function resetarRelatorioVisual(lista, statusBadge) {
    ultimoRelatorio = null;
    filtroAtual = 'TODOS';
    ocultarResumoFiltrosELimpo();
    if (statusBadge) {
        statusBadge.textContent = 'Aguardando...';
        statusBadge.className = 'status-badge';
    }
    if (lista) {
        lista.innerHTML = '<div class="auditor-lista-vazia">Nenhuma auditoria realizada ainda. Execute a auditoria para ver o relatório aqui.</div>';
    }
}

function ocultarResumoFiltrosELimpo() {
    const resumo = document.getElementById('auditor-resumo');
    const filtros = document.getElementById('auditor-filtros');
    const limpo = document.getElementById('auditor-alerta-limpo');
    if (resumo) {
        resumo.innerHTML = '';
        resumo.classList.add('hidden');
    }
    if (filtros) {
        filtros.innerHTML = '';
        filtros.classList.add('hidden');
    }
    if (limpo) limpo.classList.add('hidden');

    // Tabela e gráfico voltam ao estado inicial junto com o resto: deixar o gráfico da
    // auditoria anterior na tela depois de limpar o formulário é dado velho passando por novo.
    const visoes = document.getElementById('auditor-visoes');
    if (visoes) visoes.classList.add('hidden');
    const corpo = document.getElementById('auditor-tabela-corpo');
    if (corpo) corpo.innerHTML = '';
    ['auditor-tabela-wrap', 'auditor-grafico-wrap'].forEach(id => {
        const el = document.getElementById(id);
        if (el) el.classList.add('hidden');
    });
    if (graficoAnomalias) {
        graficoAnomalias.destroy();
        graficoAnomalias = null;
    }
    visaoAtual = 'cards';
    const lista = document.getElementById('auditor-anomalias-lista');
    if (lista) lista.classList.remove('hidden');
}

/**
 * PROPÓSITO DE NEGÓCIO: apresenta o(s) arquivo(s) auditado(s), o modo e as
 * métricas no topo do relatório.
 * INVARIANTES DO DOMÍNIO: no modo de arquivo único só o lado auditado aparece;
 * cada formato é exibido junto do arquivo correspondente.
 * COMPORTAMENTO EM CASO DE FALHA: usa rótulo explícito de formato desconhecido.
 */
function renderizarResumo(relatorio) {
    const resumo = document.getElementById('auditor-resumo');
    if (!resumo) return;

    const modo = relatorio.modo || 'AMBAS';
    const itens = [];

    itens.push(`
        <div class="auditor-resumo-item">
            <span class="auditor-resumo-label">Modo</span>
            <strong class="auditor-resumo-valor">${escapeHtml(ROTULO_MODO[modo] || modo)}</strong>
        </div>
    `);

    if (modo !== 'TRADUZIDO') {
        itens.push(`
            <div class="auditor-resumo-item">
                <span class="auditor-resumo-label">Original · ${escapeHtml(relatorio.formatoOriginal || 'FORMATO DESCONHECIDO')}</span>
                <strong class="auditor-resumo-valor" title="${escapeHtml(relatorio.arquivoOriginal || '')}">${escapeHtml(relatorio.arquivoOriginal || '—')}</strong>
            </div>
        `);
    }
    if (modo !== 'ORIGINAL') {
        itens.push(`
            <div class="auditor-resumo-item">
                <span class="auditor-resumo-label">Traduzido · ${escapeHtml(relatorio.formatoTraduzido || 'FORMATO DESCONHECIDO')}</span>
                <strong class="auditor-resumo-valor" title="${escapeHtml(relatorio.arquivoTraduzido || '')}">${escapeHtml(relatorio.arquivoTraduzido || '—')}</strong>
            </div>
        `);
    }

    itens.push(`
        <div class="auditor-resumo-item">
            <span class="auditor-resumo-label">Regras</span>
            <strong class="auditor-resumo-valor">${relatorio.regrasExecutadas ?? '—'}</strong>
        </div>
        <div class="auditor-resumo-item">
            <span class="auditor-resumo-label">Duração</span>
            <strong class="auditor-resumo-valor">${relatorio.duracaoMs ?? 0} ms</strong>
        </div>
    `);

    resumo.innerHTML = `<div class="auditor-resumo-grid">${itens.join('')}</div>`;
    resumo.classList.remove('hidden');
}

// Chips de filtro por severidade com contagem (Crítico · Erro · Aviso · Todos)
function renderizarFiltros(relatorio, lista) {
    const filtros = document.getElementById('auditor-filtros');
    if (!filtros) return;

    const contagem = contarPorSeveridade(relatorio.anomalias || []);
    const total = (relatorio.anomalias || []).length;
    filtros.innerHTML = '';

    const definicoes = [
        { chave: 'CRITICAL', icone: CONFIG_SEVERIDADE.CRITICAL.icone, rotulo: 'Crítico', quantidade: contagem.CRITICAL, classe: 'critical' },
        { chave: 'ERROR', icone: CONFIG_SEVERIDADE.ERROR.icone, rotulo: 'Erro', quantidade: contagem.ERROR, classe: 'error' },
        { chave: 'WARNING', icone: CONFIG_SEVERIDADE.WARNING.icone, rotulo: 'Aviso', quantidade: contagem.WARNING, classe: 'warning' },
        { chave: 'TODOS', icone: 'filter_list', rotulo: 'Todos', quantidade: total, classe: 'todos' }
    ];

    definicoes.forEach(def => {
        const chip = document.createElement('button');
        chip.type = 'button';
        chip.className = `auditor-filtro-chip auditor-filtro-${def.classe}`;
        chip.dataset.severidade = def.chave;
        chip.setAttribute('aria-pressed', String(filtroAtual === def.chave));
        if (filtroAtual === def.chave) chip.classList.add('ativo');
        chip.innerHTML = `<span class="material-symbols-outlined">${def.icone}</span>${def.rotulo}<span class="auditor-filtro-contagem">${def.quantidade}</span>`;

        chip.addEventListener('click', () => {
            filtroAtual = def.chave;
            filtros.querySelectorAll('.auditor-filtro-chip').forEach(c => {
                const ativo = c.dataset.severidade === filtroAtual;
                c.classList.toggle('ativo', ativo);
                c.setAttribute('aria-pressed', String(ativo));
            });
            renderizarLista(relatorio, lista);
            // A tabela obedece ao MESMO filtro: duas visões do mesmo relatório mostrando
            // populações diferentes seria a origem exata da confusão que elas vêm resolver.
            renderizarTabela(relatorio);
        });

        filtros.appendChild(chip);
    });

    filtros.classList.remove('hidden');
}

function renderizarRelatorio(relatorio, lista, statusBadge) {
    renderizarResumo(relatorio);

    const alertaLimpo = document.getElementById('auditor-alerta-limpo');

    // Estado "limpo" só quando realmente não há nenhuma anomalia
    if (relatorio.limpo) {
        statusBadge.textContent = 'Limpo';
        statusBadge.className = 'status-badge pulse-green';
        const msgLimpo = document.getElementById('auditor-alerta-limpo-msg');
        if (msgLimpo) {
            msgLimpo.textContent = MENSAGEM_LIMPO[relatorio.modo] || MENSAGEM_LIMPO.AMBAS;
        }
        if (alertaLimpo) alertaLimpo.classList.remove('hidden');
        lista.innerHTML = '<div class="auditor-lista-vazia">Nenhuma anomalia para listar.</div>';
        return;
    }

    if (alertaLimpo) alertaLimpo.classList.add('hidden');
    const total = relatorio.anomalias.length;
    statusBadge.textContent = total === 1 ? '1 ANOMALIA' : `${total} ANOMALIAS`;
    statusBadge.className = 'status-badge pulse-red';

    renderizarFiltros(relatorio, lista);
    renderizarLista(relatorio, lista);
    renderizarTabela(relatorio);
    renderizarGrafico(relatorio);

    const visoes = document.getElementById('auditor-visoes');
    if (visoes) visoes.classList.remove('hidden');
    aplicarVisao(visaoAtual);
}

/**
 * PROPÓSITO DE NEGÓCIO: dizer se a anomalia JÁ VINHA do arquivo original ou apareceu na
 * tradução. É a pergunta que o relatório não respondia e que gerou leitura errada: em
 * 06/08/2026 uma auditoria do Gundam 0080 acusou 25 anomalias, e a tradução respondia por
 * ZERO — os 12 eventos vazios e os 8 timestamps inválidos eram os mesmos 6 e 4 do Blu-ray,
 * contados nos dois arquivos.
 *
 * INVARIANTES DO DOMÍNIO: a régua é o TEXTO. Se os dois lados têm o mesmo texto, a tradução
 * não tocou naquela linha e a anomalia é herdada. Sem os dois lados (modo ORIGINAL ou
 * TRADUZIDO), devolve indefinido em vez de chutar — meia informação apresentada como certeza
 * é pior que ausência.
 *
 * COMPORTAMENTO EM CASO DE FALHA: campo ausente cai no ramo indefinido; não lança.
 */
function classificarOrigem(anom, todas) {
    const o = anom.eventoOriginal;
    const t = anom.eventoTraduzido;

    // Regra COMPARATIVA: só dispara olhando o par, então nenhum lado sozinho é "o" defeito.
    // Ex.: "Efeito Visual Vazado" acusa que a linha de efeito pesado mudou — e ela mudou
    // porque foi traduzida. Marcar isso como "introduzido na tradução" seria alarme falso.
    if (o && t) {
        return {
            chave: 'comparativa',
            rotulo: 'comparativa',
            dica: 'Regra de par: avalia original e traduzido juntos. Mudança de texto aqui é esperada — o que importa é a estrutura.'
        };
    }

    if (o && !t) {
        return {
            chave: 'herdado',
            rotulo: 'da fonte',
            dica: 'A anomalia está no arquivo ORIGINAL. A tradução não a causou.'
        };
    }

    if (t && !o) {
        // Gêmea: a MESMA regra no MESMO índice também acusada no original significa que a
        // linha já vinha assim — o auditor apenas a reporta duas vezes, uma por arquivo.
        const temGemeaNoOriginal = (todas || []).some(outra =>
            outra !== anom
            && outra.regra === anom.regra
            && outra.eventoOriginal
            && !outra.eventoTraduzido
            && outra.eventoOriginal.indice === t.indice);

        if (temGemeaNoOriginal) {
            return {
                chave: 'herdado',
                rotulo: 'da fonte',
                dica: 'A mesma regra acusa o mesmo evento no arquivo original: a linha já vinha assim e está contada nos dois lados.'
            };
        }
        return {
            chave: 'traducao',
            rotulo: 'na tradução',
            dica: 'Só o arquivo traduzido é acusado nesta regra e neste evento — apareceu aqui.'
        };
    }

    return { chave: 'indef', rotulo: '—', dica: 'Anomalia sem evento associado; não dá para localizar.' };
}

/** Extrai `início → fim` do prefixo ASS (`Dialogue: 0,0:05:42.03,0:05:42.07,OP,...`). */
function tempoDoEvento(evento) {
    const partes = String(evento?.prefixo ?? '').split(',');
    return partes.length >= 3 ? `${partes[1]} → ${partes[2]}` : '—';
}

/** Texto visível: sem tags `{...}`, com a quebra `\N` como espaço. */
function textoVisivel(evento) {
    return String(evento?.texto ?? '')
        .replace(/\{[^}]*\}/g, '')
        .replace(/\\N/g, ' ')
        .trim();
}

/**
 * PROPÓSITO DE NEGÓCIO: mostrar as anomalias como comparação linha a linha, com original e
 * traduzido lado a lado — a leitura que permite julgar um caso sem abrir o `.ass`.
 *
 * INVARIANTES DO DOMÍNIO: respeita o filtro de severidade ativo, para os dois modos de
 * exibição concordarem. Ordena por severidade e depois pelo índice do evento, que é a ordem
 * em que as falas aparecem no arquivo.
 *
 * COMPORTAMENTO EM CASO DE FALHA: sem tabela no DOM, não faz nada.
 */
function renderizarTabela(relatorio) {
    const corpo = document.getElementById('auditor-tabela-corpo');
    if (!corpo) return;

    const visiveis = (relatorio.anomalias || [])
        .filter(a => filtroAtual === 'TODOS' || a.severidade === filtroAtual)
        .sort((a, b) => {
            const ps = (ORDEM_SEVERIDADE[a.severidade] ?? 99) - (ORDEM_SEVERIDADE[b.severidade] ?? 99);
            if (ps !== 0) return ps;
            return (a.eventoOriginal?.indice ?? 0) - (b.eventoOriginal?.indice ?? 0);
        });

    if (!visiveis.length) {
        corpo.innerHTML = '<tr><td colspan="8" class="auditor-tabela-vazia">Nenhuma anomalia nesta severidade.</td></tr>';
        return;
    }

    corpo.innerHTML = visiveis.map(anom => {
        const origem = classificarOrigem(anom, relatorio.anomalias || []);
        const cfg = CONFIG_SEVERIDADE[anom.severidade] || { rotulo: anom.severidade, classe: 'warning', icone: 'help' };
        const ref = anom.eventoOriginal || anom.eventoTraduzido;
        const original = textoVisivel(anom.eventoOriginal);
        const traduzido = textoVisivel(anom.eventoTraduzido);
        const iguais = origem.chave === 'herdado';
        return `
            <tr class="auditor-tabela-linha auditor-linha-${cfg.classe}">
                <td><span class="auditor-origem auditor-origem-${origem.chave}" title="${escapeHtml(origem.dica)}">${escapeHtml(origem.rotulo)}</span></td>
                <td><span class="auditor-sev-chip auditor-sev-${cfg.classe}" title="${escapeHtml(cfg.rotulo)}"><span class="material-symbols-outlined">${cfg.icone}</span></span></td>
                <td class="auditor-td-regra" title="${escapeHtml(anom.descricao || '')}">${escapeHtml(anom.regra || '—')}</td>
                <td class="auditor-td-num">${ref?.indice ?? '—'}</td>
                <td class="auditor-td-tempo">${escapeHtml(tempoDoEvento(ref))}</td>
                <td class="auditor-td-estilo">${escapeHtml(ref?.estilo || '—')}</td>
                <td class="auditor-td-texto">${original ? escapeHtml(original) : '<i class="auditor-td-vazio">(vazio)</i>'}</td>
                <td class="auditor-td-texto ${iguais ? 'auditor-td-igual' : ''}">${traduzido ? escapeHtml(traduzido) : '<i class="auditor-td-vazio">(vazio)</i>'}</td>
            </tr>`;
    }).join('');
}

/**
 * PROPÓSITO DE NEGÓCIO: mostrar de onde vem o VOLUME antes de o operador ler qualquer linha.
 * Doze linhas idênticas de "Evento de Diálogo Vazio" ocupam a tela sem informar mais do que
 * uma barra com o número 12.
 *
 * INVARIANTES DO DOMÍNIO: usa o total do relatório, não o filtrado — o gráfico é a visão
 * geral, e filtrar por severidade nele esconderia justamente a comparação entre severidades.
 * As cores são as mesmas dos chips de severidade.
 *
 * COMPORTAMENTO EM CASO DE FALHA: sem Chart.js carregado ou sem canvas, escreve o motivo no
 * lugar do gráfico em vez de ficar em branco — quadro vazio e "sem dados" não podem parecer
 * a mesma coisa.
 */
function renderizarGrafico(relatorio) {
    const wrap = document.getElementById('auditor-grafico-wrap');
    const canvas = document.getElementById('auditor-grafico-canvas');
    const resumo = document.getElementById('auditor-grafico-resumo');
    if (!wrap || !canvas) return;

    const anomalias = relatorio.anomalias || [];

    if (resumo) {
        const porOrigem = { herdado: 0, traducao: 0, comparativa: 0, indef: 0 };
        anomalias.forEach(a => { porOrigem[classificarOrigem(a, anomalias).chave]++; });
        resumo.innerHTML = `
            <div class="auditor-grafico-tile"><span>Total</span><strong>${anomalias.length}</strong></div>
            <div class="auditor-grafico-tile auditor-tile-herdado"><span>Já vinham da fonte</span><strong>${porOrigem.herdado}</strong></div>
            <div class="auditor-grafico-tile auditor-tile-traducao"><span>Na tradução</span><strong>${porOrigem.traducao}</strong></div>
            <div class="auditor-grafico-tile auditor-tile-comparativa"><span>Regra de par</span><strong>${porOrigem.comparativa}</strong></div>`;
    }

    if (typeof Chart === 'undefined') {
        canvas.insertAdjacentHTML('afterend',
            '<p class="auditor-grafico-erro">Chart.js não carregou — gráfico indisponível. Os números acima continuam válidos.</p>');
        return;
    }

    const regras = [...new Set(anomalias.map(a => a.regra || '—'))];
    const severidades = ['CRITICAL', 'ERROR', 'WARNING'];
    const conjuntos = severidades
        .filter(sev => anomalias.some(a => a.severidade === sev))
        .map(sev => ({
            label: CONFIG_SEVERIDADE[sev]?.rotulo || sev,
            data: regras.map(r => anomalias.filter(a => (a.regra || '—') === r && a.severidade === sev).length),
            backgroundColor: COR_SEVERIDADE[sev].fundo,
            borderColor: COR_SEVERIDADE[sev].borda,
            borderWidth: 1
        }));

    if (graficoAnomalias) graficoAnomalias.destroy();
    graficoAnomalias = new Chart(canvas.getContext('2d'), {
        type: 'bar',
        data: { labels: regras, datasets: conjuntos },
        options: {
            indexAxis: 'y',
            responsive: true,
            maintainAspectRatio: false,
            scales: {
                x: { stacked: true, ticks: { precision: 0, color: '#9CA3AF' }, grid: { color: 'rgba(255,255,255,0.06)' } },
                y: { stacked: true, ticks: { color: '#D1D5DB' }, grid: { display: false } }
            },
            plugins: {
                legend: { labels: { color: '#D1D5DB' } },
                tooltip: { callbacks: { footer: itens => `total na regra: ${itens.reduce((s, i) => s + i.parsed.x, 0)}` } }
            }
        }
    });
}

/** Alterna entre cards, tabela e gráfico. Os três leem o mesmo relatório. */
function aplicarVisao(visao) {
    visaoAtual = visao;
    const alvos = {
        cards: document.getElementById('auditor-anomalias-lista'),
        tabela: document.getElementById('auditor-tabela-wrap'),
        grafico: document.getElementById('auditor-grafico-wrap')
    };
    Object.entries(alvos).forEach(([chave, el]) => {
        if (el) el.classList.toggle('hidden', chave !== visao);
    });
    document.querySelectorAll('.auditor-visao-chip').forEach(chip => {
        const ativo = chip.dataset.visao === visao;
        chip.classList.toggle('ativo', ativo);
        chip.setAttribute('aria-pressed', String(ativo));
    });
}

function renderizarLista(relatorio, lista) {
    const ordenadas = [...relatorio.anomalias].sort((a, b) => {
        const pa = ORDEM_SEVERIDADE[a.severidade] ?? 99;
        const pb = ORDEM_SEVERIDADE[b.severidade] ?? 99;
        return pa - pb;
    });

    const visiveis = filtroAtual === 'TODOS'
        ? ordenadas
        : ordenadas.filter(anom => anom.severidade === filtroAtual);

    lista.innerHTML = '';

    if (!visiveis.length) {
        lista.innerHTML = '<div class="auditor-lista-vazia">Nenhuma anomalia nesta severidade.</div>';
        return;
    }

    visiveis.forEach(anom => lista.appendChild(criarCardAnomalia(anom)));
}

// Card fechado de anomalia: faixa de severidade, cabeçalho, mensagem, diff empilhado e dica
function criarCardAnomalia(anom) {
    const cfg = CONFIG_SEVERIDADE[anom.severidade] || CONFIG_SEVERIDADE.WARNING;

    const card = document.createElement('article');
    card.className = `auditor-anomalia-card auditor-anomalia-${cfg.classe}`;

    const head = document.createElement('header');
    head.className = 'auditor-anomalia-head';

    const pill = document.createElement('span');
    pill.className = `auditor-sev-pill auditor-sev-${cfg.classe}`;
    pill.innerHTML = `<span class="material-symbols-outlined">${cfg.icone}</span>${cfg.rotulo}`;

    const regra = document.createElement('span');
    regra.className = 'auditor-anomalia-regra';
    regra.innerHTML = `<span class="material-symbols-outlined">bug_report</span>${escapeHtml(anom.regra || '—')}`;

    head.append(pill, regra);

    const numLinha = anom.eventoTraduzido?.indice ?? anom.eventoOriginal?.indice;
    if (numLinha != null) {
        const badgeLinha = document.createElement('span');
        badgeLinha.className = 'auditor-anomalia-linha';
        badgeLinha.textContent = `LINHA #${numLinha}`;
        head.appendChild(badgeLinha);
    }

    card.appendChild(head);

    if (anom.descricao) {
        const msg = document.createElement('p');
        msg.className = 'auditor-anomalia-msg';
        msg.textContent = anom.descricao;
        card.appendChild(msg);
    }

    // Diff empilhado: bloco ORIGINAL (azul) sobre bloco TRADUZIDO (vermelho)
    if (anom.eventoOriginal?.texto || anom.eventoTraduzido?.texto) {
        const diff = document.createElement('div');
        diff.className = 'auditor-anomalia-diff';
        if (anom.eventoOriginal?.texto) {
            diff.appendChild(criarTrechoEvento('Original', anom.eventoOriginal, 'auditor-evento-orig'));
        }
        if (anom.eventoTraduzido?.texto) {
            diff.appendChild(criarTrechoEvento('Traduzido', anom.eventoTraduzido, 'auditor-evento-trad'));
        }
        card.appendChild(diff);
    }

    if (anom.sugestaoCorrecao) {
        const dica = document.createElement('footer');
        dica.className = 'auditor-anomalia-dica';
        dica.innerHTML = `<span class="material-symbols-outlined">lightbulb</span><span>${escapeHtml(anom.sugestaoCorrecao)}</span>`;
        card.appendChild(dica);
    }

    return card;
}

function criarTrechoEvento(rotulo, evento, classeExtra) {
    const bloco = document.createElement('div');
    bloco.className = `auditor-evento ${classeExtra}`;

    const titulo = document.createElement('div');
    titulo.className = 'auditor-evento-titulo';
    const icone = rotulo === 'Original' ? 'description' : 'translate';
    titulo.innerHTML = `<span class="material-symbols-outlined">${icone}</span><span>${rotulo} · linha #${evento.indice}</span>`;

    const pre = document.createElement('div');
    pre.className = 'auditor-evento-texto';
    pre.innerHTML = formatarTextoAssHtml(evento.texto);

    bloco.append(titulo, pre);
    return bloco;
}

function formatarTextoAss(texto) {
    if (!texto) return '';
    return texto.replace(/\\N/g, '\n');
}

function formatarTextoAssHtml(texto) {
    const escapado = escapeHtml(formatarTextoAss(texto));
    return escapado.replace(/\{[^}]*\}/g, '<span class="ass-tag-code">$&</span>');
}

function rotuloSeveridade(sev) {
    if (sev === 'CRITICAL') return 'Crítico';
    if (sev === 'ERROR') return 'Erro';
    if (sev === 'WARNING') return 'Aviso';
    return sev || '—';
}

function escapeHtml(unsafe) {
    if (unsafe == null) return '';
    return String(unsafe)
        .replace(/&/g, '&amp;')
        .replace(/</g, '&lt;')
        .replace(/>/g, '&gt;')
        .replace(/"/g, '&quot;')
        .replace(/'/g, '&#039;');
}

function exportarRelatorio(formato) {
    if (!ultimoRelatorio) {
        mostrarAlerta('Execute uma auditoria antes de exportar o relatório.', 'aviso');
        return;
    }

    const nomeBase = extrairNomeArquivo(ultimoRelatorio.arquivoTraduzido || ultimoRelatorio.arquivoOriginal || 'auditoria');
    const timestamp = new Date().toISOString().replace(/[:.]/g, '-').slice(0, 19);

    if (formato === 'json') {
        baixarArquivo(JSON.stringify(ultimoRelatorio, null, 2), `auditoria_conteudo_${nomeBase}_${timestamp}.json`, 'application/json;charset=utf-8');
        mostrarAlerta('Relatório JSON exportado.', 'sucesso');
        return;
    }

    baixarArquivo(gerarRelatorioMarkdown(ultimoRelatorio), `auditoria_conteudo_${nomeBase}_${timestamp}.md`, 'text/markdown;charset=utf-8');
    mostrarAlerta('Relatório Markdown (.md) exportado.', 'sucesso');
}

/**
 * PROPÓSITO DE NEGÓCIO: gera a versão portátil do relatório com identificação de formato.
 * INVARIANTES DO DOMÍNIO: os metadados exportados refletem o mesmo objeto mostrado na tela.
 * COMPORTAMENTO EM CASO DE FALHA: campos ausentes são representados por travessão.
 */
function gerarRelatorioMarkdown(relatorio) {
    const agora = new Date().toLocaleString('pt-BR');
    const modo = relatorio.modo || 'AMBAS';
    const linhas = [
        '# Relatório de Auditoria de Conteúdo — KRONOS CORE',
        '',
        'Relatório didático para revisão humana de legendas.',
        '',
        '## Contexto da auditoria',
        '',
        '| Campo | Valor |',
        '| --- | --- |',
        `| **Data** | ${agora} |`,
        `| **Modo** | ${ROTULO_MODO[modo] || modo} |`
    ];

    if (modo !== 'TRADUZIDO') {
        linhas.push(`| **Arquivo original** | \`${relatorio.arquivoOriginal || '—'}\` |`);
        linhas.push(`| **Formato original** | ${relatorio.formatoOriginal || '—'} |`);
    }
    if (modo !== 'ORIGINAL') {
        linhas.push(`| **Arquivo traduzido** | \`${relatorio.arquivoTraduzido || '—'}\` |`);
        linhas.push(`| **Formato traduzido** | ${relatorio.formatoTraduzido || '—'} |`);
    }

    linhas.push(
        `| **Regras executadas** | ${relatorio.regrasExecutadas ?? '—'} |`,
        `| **Duração** | ${relatorio.duracaoMs ?? 0} ms |`,
        `| **Resultado** | ${relatorio.limpo ? 'Limpo (sem anomalias)' : `${relatorio.anomalias.length} anomalia(s)`} |`
    );

    if (relatorio.caminhoRelatorioJson) {
        linhas.push(`| **JSON em disco** | \`${relatorio.caminhoRelatorioJson}\` |`);
    }
    linhas.push('');

    if (relatorio.limpo) {
        linhas.push('## Conclusão', '', 'Nenhuma anomalia foi detectada.', '');
        return linhas.join('\n');
    }

    const resumo = contarPorSeveridade(relatorio.anomalias);
    linhas.push(
        '## Resumo por severidade', '',
        '| Severidade | Quantidade |',
        '| --- | ---: |',
        `| Crítico | ${resumo.CRITICAL} |`,
        `| Erro | ${resumo.ERROR} |`,
        `| Aviso | ${resumo.WARNING} |`,
        '', '## Anomalias detalhadas', ''
    );

    relatorio.anomalias.forEach((anom, indice) => {
        linhas.push(`### ${indice + 1}. [${rotuloSeveridade(anom.severidade)}] ${anom.regra}`, '');
        linhas.push(`**Descrição:** ${anom.descricao}`, '');
        if (anom.eventoOriginal) {
            linhas.push(`**Original (#${anom.eventoOriginal.indice}):**`, '```', formatarTextoAss(anom.eventoOriginal.texto), '```', '');
        }
        if (anom.eventoTraduzido) {
            linhas.push(`**Traduzido (#${anom.eventoTraduzido.indice}):**`, '```', formatarTextoAss(anom.eventoTraduzido.texto), '```', '');
        }
        if (anom.sugestaoCorrecao) {
            linhas.push(`**Recomendação:** ${anom.sugestaoCorrecao}`, '');
        }
        linhas.push('---', '');
    });

    return linhas.join('\n');
}

function contarPorSeveridade(anomalias) {
    const contagem = { CRITICAL: 0, ERROR: 0, WARNING: 0 };
    (anomalias || []).forEach(anom => {
        if (contagem[anom.severidade] !== undefined) {
            contagem[anom.severidade]++;
        }
    });
    return contagem;
}

/**
 * PROPÓSITO DE NEGÓCIO: cria um nome seguro de exportação para ASS, SSA ou SRT.
 * INVARIANTES DO DOMÍNIO: remove apenas a extensão de legenda conhecida.
 * COMPORTAMENTO EM CASO DE FALHA: caminho vazio resulta no nome seguro "auditoria".
 */
function extrairNomeArquivo(caminho) {
    const normalizado = String(caminho).replace(/\\/g, '/');
    const partes = normalizado.split('/');
    const nome = partes[partes.length - 1] || 'auditoria';
    return nome.replace(/\.(ass|ssa|srt)$/i, '').replace(/[^\w.-]+/g, '_');
}

function baixarArquivo(conteudo, nomeArquivo, mimeType) {
    const blob = new Blob([conteudo], { type: mimeType });
    const url = URL.createObjectURL(blob);
    const link = document.createElement('a');
    link.href = url;
    link.download = nomeArquivo;
    document.body.appendChild(link);
    link.click();
    document.body.removeChild(link);
    URL.revokeObjectURL(url);
}
