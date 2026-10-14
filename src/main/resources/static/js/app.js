/**
 * ==========================================================================
 * KRONOS CORE - ORQUESTRADOR GLOBAL FRONTEND (SPA & SSE STREAM LOGS)
 * ==========================================================================
 */

import { initAnalise } from '../analise/analise.js?v=4.0';
import { initExtracao } from '../extracao/extracao.js?v=3.0';
import { initAuditorConteudo } from '../auditorConteudoLegendas/auditorConteudoLegendas.js?v=4.0';
import { initTraducao } from '../traducao/traducao.js?v=3.2';
import { initTraducaoSemLore } from '../traducaoSemLore/traducaoSemLore.js?v=1.0';
import { initCorrecao } from '../correcao/correcao.js?v=3.1';
import { initRevisao } from '../revisao/revisao.js?v=3.5';
import { initCura } from '../cura/cura.js?v=3.0';
import { initRevisaoLore } from '../revisaoLore/revisaoLore.js?v=3.2';
import { initRevisaoConcordancia } from '../revisaoConcordancia/revisaoConcordancia.js?v=1.0';
import { initTrocaTipoLegenda } from '../trocaTipoLegenda/trocaTipoLegenda.js?v=3.0';
import { initRemuxer } from '../remuxer/remuxer.js?v=3.4';
import { montarOpcoesContextos } from './selectContextos.js';
import { travarAteEscolherLore } from './travaLore.js';
import { initMapa } from '../mapa/mapa.js?v=5.0';
import { initTelemetria } from '../telemetria/telemetria.js?v=3.1';
import { initDocumentacao } from '../documentacao/documentacao.js?v=3.0';
import { initSobre } from '../sobre/sobre.js?v=3.0';
import { initRenomearArquivos } from '../renomearArquivos/renomearArquivos.js?v=3.0';
// 4.3 Karaokê Simples foi ABSORVIDO pela tela única 4.1 Karaokê em 21/09/2026: o achatamento virou
// o Passo 2 da mesma tela (chama /api/novo-karaoke/* direto). O backend do achatador continua vivo;
// só o módulo/tela frontend próprio deixou de existir. Ver traducaoKaraoke.js.
import { initTraducaoKaraoke } from '../traducaoKaraoke/traducaoKaraoke.js?v=1.4';
import { initInicio } from '../inicio/inicio.js?v=1.0';
import { inicializarI18n } from '../i18n/i18n.js?v=2.1';

// Definições de Títulos e Subtítulos por seção do menu
const CONFIG_SECOES = {
    inicio: {
        titulo: "Painel Inicial",
        subtitulo: "Orquestrador automatizado e pipeline industrial de processamento de animes"
    },
    // --- 1. PREPARAÇÃO ---
    analise: {
        titulo: "1.1 Análise de Mídia",
        subtitulo: "Auditoria técnica de codecs, sincronia e taxas de bits de vídeos"
    },
    extracao: {
        titulo: "1.2 Extração de Legendas",
        subtitulo: "Extração industrial de faixas de legendas embutidas in vídeos (MKV, MP4 e outros)"
    },
    // A troca de fonte é 1.3, e não acabamento: fonte legada guarda bytes que só viram
    // letra com aquela fonte. Chegando assim ao LLM, ele traduz mojibake. Era 5.1 até 06/08/2026.
    "troca-tipo-legenda": {
        titulo: "1.3 Troca de Tipo de Legenda",
        subtitulo: "Antes de traduzir: substituição em lote de fontes vietnamitas ou ANSI legadas por fontes Unicode seguras"
    },
    "auditor-conteudo": {
        titulo: "1.4 Análise de Legenda",
        subtitulo: "Auditoria da legenda: roda sobre a fonte sozinha ou comparando original e tradução"
    },

    // --- 2. TRADUÇÃO ---
    traducao: {
        titulo: "2.1 Tradução Local via LLM",
        subtitulo: "Traduzir legendas originais em inglês usando inteligência artificial local"
    },
    "traducao-sem-lore": {
        titulo: "2.2 Tradução sem Lore",
        subtitulo: "Tradução crua de anime ainda sem lore declarada — saída separada, terminologia não aplicada"
    },
    correcao: {
        titulo: "2.3 Correção do Cache de Tradução",
        subtitulo: "Limpeza de inconsistências e preenchimento via raspagem do Google Tradutor"
    },

    // --- 3. QUALIDADE ---
    revisao: {
        titulo: "3.1 Revisão de Legendas",
        subtitulo: "Concordância PT-BR via LLM local e correção de inglês residual via Google"
    },
    "revisao-lore": {
        titulo: "3.2 Revisão de Lore",
        subtitulo: "Padronização de nomes, locais e termos de mundo nas legendas via LLM e lore oficial"
    },
    "revisao-concordancia": {
        titulo: "3.3 Revisão de Concordância",
        subtitulo: "Correção determinística de gênero (o menina → a menina) na legenda PT-BR, sem inglês nem LLM"
    },

    // --- 4. KARAOKÊ --- (tela única desde 21/09/2026: traduzir + achatar em duas passadas)
    "traducao-karaoke": {
        titulo: "4.1 Karaokê",
        subtitulo: "Traduzir as letras (romaji + PT-BR juntos) e depois achatar a animação — duas passadas na mesma tela"
    },
    cura: {
        titulo: "4.2 Correção de Karaokê",
        subtitulo: "Corrige a legenda PT-BR usando a original como referência imutável"
    },

    // --- 5. FINALIZAÇÃO ---
    remuxer: {
        titulo: "5.1 Remuxer Industrial",
        subtitulo: "Junção de vídeos originais e novas legendas traduzidas em novos MKVs"
    },
    "renomear-arquivos": {
        titulo: "5.2 Renomear Arquivos de Vídeo",
        subtitulo: "Limpeza de nomes de arquivo usando regex e metadados S01E01"
    },

    // --- SISTEMA --- (fora da sequência: não são passos do pipeline)
    mapa: {
        titulo: "Mapeamento do Projeto",
        subtitulo: "Auditoria de taxonomia e visualização da árvore de estrutura do código"
    },
    telemetria: {
        titulo: "Telemetria KRONOS",
        subtitulo: "Observabilidade da traducao, cache local e historico operacional"
    },
    documentacao: {
        titulo: "Documentação",
        subtitulo: "Arquitetura, módulos, API REST e diagramas do KRONOS CORE"
    },
    sobre: {
        titulo: "Sobre o Autor",
        subtitulo: "Paulo André Carminati, contatos, formação e repositórios"
    }
};

let logsEventSource = null;
let logsReconnectTimer = null;
let logsWatchdogTimer = null;
let seletorCaminhoEmAndamento = false;
// Identidade da execução do servidor, recebida no batimento (ver LogStreamService).
// Constante enquanto a aplicação for a mesma; mudar de valor significa REINÍCIO, e o
// que está no DOM passa a ser o console de uma execução que já morreu.
let identidadeExecucaoServidor = null;
const logsPendentesPorConsole = new Map();
const LIMITE_LOGS_PENDENTES = 1000;
// Silêncio total (nem heartbeat) por mais que isto ⇒ conexão SSE provavelmente
// zumbi (o servidor bate a cada 15s). Reconecta proativamente para nunca deixar
// o console travar entre operações. Ver LogStreamService#enviarHeartbeat.
const LOGS_WATCHDOG_MS = 40000;

document.addEventListener('traducao-karaoke:painel-carregado', () => {
    descarregarLogsPendentes('console-traducao-karaoke');
});

document.addEventListener('DOMContentLoaded', async () => {
    inicializarI18n();
    inicializarNavegacao();
    inicializarGruposMenu();
    await inicializarModulos();
    atualizarStatusConexao();
    buscarContadoresGlobais();
    atualizarCardFluxoTelemetria();
    conectarFluxoLugsSSE();

    inicializarMetadadosDinamicos();
    inicializarBotoesLimpezaFormularios();
    inicializarControlesConsole();
    inicializarBotoesProcurarCaminho();
    inicializarBotaoSair();
});

/**
 * Controla a troca de abas/painéis na sidebar e atualiza os títulos
 */
function inicializarNavegacao() {
    const botoesMenu = document.querySelectorAll('.nav-item');
    const paineis = document.querySelectorAll('.panel');
    const tituloPagina = document.getElementById('page-title');
    const subtituloPagina = document.getElementById('page-subtitle');

    botoesMenu.forEach(botao => {
        // "Sair" é uma ação, não uma aba — não participa da troca de painéis
        // (ver inicializarBotaoSair()).
        if (botao.id === 'btn-menu-sair') return;
        botao.addEventListener('click', () => {
            const target = botao.getAttribute('data-target');
            
            // 1. Atualizar classe ativa dos botões do menu
            botoesMenu.forEach(b => b.classList.remove('active'));
            botao.classList.add('active');

            // 2. Exibir painel correto
            paineis.forEach(painel => {
                painel.classList.remove('active');
                if (painel.id === `panel-${target}`) {
                    painel.classList.add('active');
                }
            });

            // 3. Atualizar títulos no cabeçalho
            if (CONFIG_SECOES[target]) {
                tituloPagina.textContent = CONFIG_SECOES[target].titulo;
                subtituloPagina.textContent = CONFIG_SECOES[target].subtitulo;
            }

            // Ações extras ao abrir painéis específicos
            if (target === 'telemetria') {
                document.getElementById('btn-refresh-telemetria').click();
            }
        });
    });
}

/**
 * Acordeão dos grupos do menu lateral: alterna abrir/fechar cada grupo e
 * persiste a escolha no localStorage, restaurando na próxima visita.
 */
function inicializarGruposMenu() {
    const CHAVE_ESTADO = 'kronos.menuGruposFechados';
    // Sem estado salvo, "Preparação" inicia colapsada; depois vale a escolha do usuário.
    let fechados;
    try {
        fechados = new Set(JSON.parse(localStorage.getItem(CHAVE_ESTADO) || '["preparacao"]'));
    } catch (e) {
        fechados = new Set(['preparacao']);
    }

    document.querySelectorAll('.nav-group').forEach(grupo => {
        const id = grupo.getAttribute('data-grupo');
        const header = grupo.querySelector('.nav-group-header');
        if (!header) return;

        const aplicar = () => {
            const fechado = fechados.has(id);
            grupo.classList.toggle('fechado', fechado);
            header.setAttribute('aria-expanded', String(!fechado));
        };
        aplicar();

        header.addEventListener('click', () => {
            if (fechados.has(id)) {
                fechados.delete(id);
            } else {
                fechados.add(id);
            }
            try {
                localStorage.setItem(CHAVE_ESTADO, JSON.stringify([...fechados]));
            } catch (e) {
                // armazenamento indisponível: estado vive só nesta sessão
            }
            aplicar();
        });
    });
}

/**
 * Inicializa cada um dos módulos JavaScript específicos das pastas
 */
async function inicializarModulos() {
    initInicio();
    initAnalise();
    initExtracao();
    await initAuditorConteudo();
    await initTraducaoKaraoke();
    initTraducao();
    initTraducaoSemLore();
    initCorrecao();
    initRevisao();
    initCura();
    await initRevisaoLore();
    await initRevisaoConcordancia();
    await initTrocaTipoLegenda();
    initRemuxer();
    await initRenomearArquivos();
    initMapa();
    initTelemetria();
    initDocumentacao();
    await initSobre();
}

/**
 * PROPÓSITO DE NEGÓCIO: conecta a interface ao fluxo SSE e encaminha cada etapa
 * operacional ao console visual do módulo que iniciou o processamento.
 *
 * INVARIANTES DO DOMÍNIO: cada canal deve apontar para um único console; mensagens
 * recebidas antes da montagem do painel devem permanecer disponíveis; só pode haver
 * uma conexão SSE ativa por página.
 *
 * COMPORTAMENTO EM CASO DE FALHA: fecha a conexão defeituosa e agenda uma nova
 * tentativa em cinco segundos, preservando no buffer as linhas já recebidas.
 */
function conectarFluxoLugsSSE() {
    if (logsEventSource && logsEventSource.readyState !== EventSource.CLOSED) {
        return;
    }
    if (logsReconnectTimer) {
        clearTimeout(logsReconnectTimer);
        logsReconnectTimer = null;
    }

    console.log('Iniciando escuta de Server-Sent Events (SSE) para logs...');
    const eventSource = new EventSource('/api/logs/stream');
    logsEventSource = eventSource;
    armarWatchdogLogs();

    // O backend publica cada operação em segundo plano sob um canal SSE com
    // o próprio nome (ver LogStreamService#definirCanalAtual no servidor),
    // então a rota para o console certo é direta — não depende de qual aba
    // está aberta no navegador no momento em que a linha de log chega.
    const consoleMap = {
        'analise': 'console-analise',
        'extracao': 'console-extracao',
        'auditor-conteudo': 'console-auditor-conteudo',
        'traducao': 'console-traducao',
        // Sem esta entrada a tela 2.2 fica MUDA: não é só falta de listener nomeado — o canal
        // genérico de fallback resolve o destino por consoleMap[aba ativa], não acha nada e
        // descarta a linha sem erro. Em 14/08/2026 isso mandou 192 lotes de progresso da
        // tradução sem lore para o console da 2.1. Emparelhada com o canal escolhido em
        // TraducaoController; as duas pontas nascem e mudam juntas.
        'traducao-sem-lore': 'console-traducao-sem-lore',
        'correcao': 'console-correcao',
        'revisao': 'console-revisao',
        'revisao-lore': 'console-revisao-lore',
        'revisao-concordancia': 'console-revisao-concordancia',
        'troca-tipo-legenda': 'console-troca-tipo-legenda',
        'correcao-legendas': 'console-cura',
        'cura': 'console-cura',
        'remuxer': 'console-remuxer',
        'renomear-arquivos': 'console-renomear-arquivos',
        // TELA ÚNICA DE KARAOKÊ (21/09/2026): as duas passadas logam no MESMO console. O backend do
        // achatador publica no canal 'novo-karaoke'; a tradução, em 'traducao-karaoke'. Os dois
        // canais apontam para o console da tela unificada. Faltar uma entrada aqui deixa a passada
        // MUDA sem erro (cicatriz registrada acima).
        'novo-karaoke': 'console-traducao-karaoke',
        'traducao-karaoke': 'console-traducao-karaoke'
    };

    for (const [canal, consoleId] of Object.entries(consoleMap)) {
        eventSource.addEventListener(canal, (event) => {
            armarWatchdogLogs();
            logNoConsoleFormatado(consoleId, event.data);
            verificarAlertaSSE(event.data);
        });
    }

    // FIM DO LOTE DE TRADUÇÃO: canal próprio, emparelhado com TraducaoController#SUFIXO_CANAL_FIM.
    // As duas pontas nascem e mudam juntas, pelo mesmo motivo registrado no consoleMap acima —
    // faltar a entrada de um canal aqui não dá erro, deixa a tela MUDA.
    //
    // Este arquivo só ROTEIA o sinal; o que fazer com ele (som, toast) é decisão da tela, que
    // escuta o evento de janela. Sem isso, o aviso sonoro de uma fatia moraria no arquivo comum.
    ['traducao', 'traducao-sem-lore'].forEach((canal) => {
        eventSource.addEventListener(`${canal}-finalizada`, (event) => {
            // Corpo do evento: "<desfecho>|<veredito do som da máquina>" — emparelhado com
            // TraducaoController#SEPARADOR_EVENTO. Aqui não se interpreta nada: rotear é o
            // trabalho deste arquivo, decidir o que fazer é da tela.
            window.dispatchEvent(new CustomEvent('kronos:traducao-finalizada', {
                detail: { canal: canal, dados: event.data }
            }));
        });
    });

    // Batimento do servidor (na conexão e a cada ~15s): não vira log. Faz DUAS coisas —
    // a chegada rearma o watchdog (conexão viva), e o conteúdo diz QUAL execução está do
    // outro lado. Ver LogStreamService#enviarHeartbeat.
    eventSource.addEventListener('heartbeat', (event) => {
        armarWatchdogLogs();
        aplicarIdentidadeExecucao(event.data);
    });

    // Canal genérico de fallback, para qualquer log que não pertença a uma
    // operação específica das abas acima.
    eventSource.addEventListener('console', (event) => {
        const activeNav = document.querySelector('.nav-item.active');
        if (!activeNav) return;

        const target = activeNav.getAttribute('data-target');
        const consoleId = consoleMap[target];
        if (consoleId) {
            logNoConsoleFormatado(consoleId, event.data);
            verificarAlertaSSE(event.data);
        }
    });

    // Resultado ESTRUTURADO (JSON) da Análise de Mídia: o backend publica o
    // resultado num único evento e aqui o console é substituído por cartões +
    // tabela de legendas (a análise não grava mais relatório em disco).
    eventSource.addEventListener('analise-relatorio', (event) => {
        renderizarAnaliseMidia('console-analise', event.data);
    });

    eventSource.addEventListener('sistema', (event) => {
        console.log('SSE Sistema:', event.data);
    });

    eventSource.onerror = (err) => {
        console.warn('Erro na conexão de stream SSE, tentando reconectar em 5s...', err);
        limparWatchdogLogs();
        eventSource.close();
        if (logsEventSource === eventSource) {
            logsEventSource = null;
        }
        if (!logsReconnectTimer) {
            logsReconnectTimer = setTimeout(() => {
                logsReconnectTimer = null;
                conectarFluxoLugsSSE();
            }, 5000);
        }
    };
}

/**
 * PROPÓSITO DE NEGÓCIO: garante que o console volte a ser dinâmico sozinho — sem o
 * usuário recarregar a página — quando a conexão SSE morre em silêncio (conexão
 * "zumbi": o navegador acha que está conectado, mas o servidor já não a tem).
 *
 * INVARIANTES DO DOMÍNIO: como o servidor bate a cada ~15s, qualquer silêncio maior
 * que LOGS_WATCHDOG_MS indica conexão morta; o timer é sempre rearmado a cada evento
 * recebido (heartbeat ou log), então uma conexão saudável nunca dispara a reconexão.
 *
 * COMPORTAMENTO EM CASO DE FALHA: ao disparar, fecha a conexão atual e reabre na hora;
 * se já não houver EventSource, apenas reabre.
 */
function armarWatchdogLogs() {
    limparWatchdogLogs();
    logsWatchdogTimer = setTimeout(() => {
        logsWatchdogTimer = null;
        console.warn('Silêncio prolongado no stream SSE (sem heartbeat) — reconectando.');
        if (logsEventSource) {
            try { logsEventSource.close(); } catch (e) { /* já fechado */ }
            logsEventSource = null;
        }
        conectarFluxoLugsSSE();
    }, LOGS_WATCHDOG_MS);
}

function limparWatchdogLogs() {
    if (logsWatchdogTimer) {
        clearTimeout(logsWatchdogTimer);
        logsWatchdogTimer = null;
    }
}

/**
 * PROPÓSITO DE NEGÓCIO: descartar da tela o console de uma execução que já morreu.
 * O EventSource reconecta sozinho quando a aplicação reinicia, e sem isto o navegador
 * continua exibindo o histórico do processo anterior como se fosse do atual — o motivo
 * pelo qual só um Ctrl+F5 limpava a tela.
 *
 * INVARIANTES DO DOMÍNIO: a PRIMEIRA identidade recebida nunca limpa nada (é a conexão
 * inicial, não uma troca); só limpa quando o valor MUDA. Identidade vazia é ignorada, para
 * um servidor antigo sem o campo nunca apagar o console de ninguém.
 *
 * COMPORTAMENTO EM CASO DE FALHA: limpar é operação de tela; nada aqui derruba a conexão
 * nem interfere no que o servidor está executando.
 */
function aplicarIdentidadeExecucao(identidade) {
    if (!identidade) return;

    if (identidadeExecucaoServidor === null) {
        identidadeExecucaoServidor = identidade;
        return;
    }
    if (identidadeExecucaoServidor === identidade) return;

    identidadeExecucaoServidor = identidade;
    logsPendentesPorConsole.clear();
    document.querySelectorAll('.console-body').forEach((consoleDiv) => {
        consoleDiv.innerHTML =
            '<div class="system-message">Aplicação reiniciada. Console limpo, aguardando novos logs...</div>';
    });
    console.log('SSE: execução nova do servidor detectada — consoles limpos.');
}

/**
 * Verifica se o servidor Spring Boot está respondendo
 */
async function atualizarStatusConexao() {
    const indicador = document.querySelector('.status-indicator');
    const statusText = document.querySelector('.status-text');
    
    try {
        const res = await fetch('/api/status', { method: 'GET' });
        if (res.ok) {
            indicador.className = 'status-indicator online';
            statusText.textContent = 'Backend Online';
            if (res.headers.get('X-Kronos-Vitrine') === '1') {
                ativarFaixaVitrine();
            }
        } else {
            throw new Error();
        }
    } catch (e) {
        indicador.className = 'status-indicator offline';
        statusText.textContent = 'Backend Offline';
    }

    // Repete a verificação a cada 10 segundos
    setTimeout(atualizarStatusConexao, 10000);
}

/**
 * PROPÓSITO DE NEGÓCIO: avisa quem visita a vitrine pública do KRONOS (VPS) que as telas estão
 * abertas para conhecer o sistema, mas as funções só rodam na instalação local — para ninguém
 * concluir que o site quebrou quando uma ação é recusada.
 *
 * INVARIANTES DO DOMÍNIO: só aparece quando o servidor manda o cabeçalho X-Kronos-Vitrine (a
 * instalação local nunca o manda); é criada uma única vez; o texto visível já explica, e o
 * tooltip (também pelo teclado, via tabindex) só acrescenta detalhe — ícone e tooltip reforçam,
 * nunca substituem o texto.
 *
 * COMPORTAMENTO EM CASO DE FALHA: sem o contêiner principal na página, não faz nada.
 */
function ativarFaixaVitrine() {
    if (document.querySelector('.faixa-vitrine')) return;
    const principal = document.querySelector('.main-content');
    if (!principal) return;

    const faixa = document.createElement('div');
    faixa.className = 'faixa-vitrine';
    faixa.setAttribute('role', 'note');
    faixa.tabIndex = 0;
    faixa.setAttribute('data-tooltip',
        'Vitrine: cada tela mostra como o KRONOS funciona. Executar, gravar e ler o acervo só acontece '
        + 'na instalação local, na máquina de quem usa. Os números da Telemetria são um retrato '
        + 'de 14/10/2026, não atualizam ao vivo.');

    const icone = document.createElement('span');
    icone.className = 'material-symbols-outlined';
    icone.setAttribute('aria-hidden', 'true');
    icone.setAttribute('translate', 'no');
    icone.textContent = 'storefront';

    const texto = document.createElement('span');
    texto.textContent = 'Vitrine do KRONOS — conheça as telas à vontade; as funções só rodam na instalação local.';

    faixa.append(icone, texto);
    principal.insertBefore(faixa, principal.firstChild);
    document.body.classList.add('modo-vitrine');
}

/**
 * PROPÓSITO DE NEGÓCIO: transforma o corpo de uma resposta de erro do servidor na frase que a
 * pessoa deve ler — o servidor responde JSON ({mensagem} ou {erro}), e mostrar o JSON cru no
 * console da tela esconde a mensagem dentro de chaves e aspas.
 *
 * INVARIANTES DO DOMÍNIO: preferência mensagem > erro > error; corpo que não é JSON volta como
 * veio (proxy, 500 cru), sem esconder nada.
 *
 * COMPORTAMENTO EM CASO DE FALHA: corpo vazio devolve o texto padrão recebido.
 */
export function mensagemDoErro(texto, padrao) {
    if (!texto) return padrao;
    try {
        const json = JSON.parse(texto);
        if (json && typeof json === 'object') {
            return json.mensagem || json.erro || json.error || padrao;
        }
    } catch (_) {
        // não é JSON: devolve como veio
    }
    return texto;
}

/**
 * Carrega estatísticas rápidas no cabeçalho
 */
async function buscarContadoresGlobais() {
    try {
        const res = await fetch('/api/telemetria');
        if (res.ok) {
            const dados = await res.json();
            
            // Atualiza cabeçalho global
            const cacheCount = document.getElementById('stat-cache-count');
            if (cacheCount && dados.cacheCount !== undefined) {
                cacheCount.textContent = `${dados.cacheCount} Arquivos`;
            }
            
            // Atualiza widget da home (Painel Inicial)
            const dashCacheCount = document.getElementById('dashboard-cache-count');
            if (dashCacheCount && dados.cacheCount !== undefined) {
                dashCacheCount.textContent = `${dados.cacheCount} Arquivos`;
            }
        }
    } catch (e) {
        console.warn('Não foi possível obter os contadores globais da telemetria.');
        const cacheCount = document.getElementById('stat-cache-count');
        if (cacheCount) cacheCount.textContent = 'Indisponível';
        
        const dashCacheCount = document.getElementById('dashboard-cache-count');
        if (dashCacheCount) dashCacheCount.textContent = 'Indisponível';
    }
}

/**
 * PROPÓSITO DE NEGÓCIO: mostra no painel inicial o estado do fluxo ao vivo de
 * telemetria, ao lado do orquestrador, do LLM e do cache.
 *
 * INVARIANTES DO DOMÍNIO: desconectado é estado LEGÍTIMO, não erro — o KRONOS
 * funciona inteiro sem o fluxo. O card informa, e não alarma. E `eventos = -1`
 * significa "desconhecido", que vira "—" na tela: zero evento e estado
 * desconhecido são coisas diferentes e não podem exibir o mesmo número.
 *
 * COMPORTAMENTO EM CASO DE FALHA: falha de rede pinta "Indisponível" no card e
 * não propaga — a home não deixa de carregar por causa de um card.
 */
async function atualizarCardFluxoTelemetria() {
    const valor = document.getElementById('fluxo-telemetria-valor');
    const badge = document.getElementById('fluxo-telemetria-status');
    if (!valor || !badge) return;

    try {
        const res = await fetch('/api/fluxo/status');
        if (!res.ok) throw new Error(`HTTP ${res.status}`);
        const d = await res.json();

        if (d.conectado) {
            const n = Number(d.eventos);
            valor.textContent = n < 0 ? 'Redis conectado' : `${n.toLocaleString('pt-BR')} eventos`;
            badge.textContent = 'Conectado';
            badge.className = 'status-badge pulse-green';
        } else {
            valor.textContent = 'Redis desconectado';
            badge.textContent = 'Desconectado';
            badge.className = 'status-badge pulse-red';
            badge.title = d.detalhe || '';
        }
    } catch (e) {
        valor.textContent = 'Indisponível';
        badge.textContent = 'Indisponível';
        badge.className = 'status-badge pulse-red';
    }
}

/**
 * Escapa caracteres especiais de HTML para impedir que conteúdo vindo do
 * backend (nomes de arquivo, mensagens de erro, texto raspado do Google
 * Translate) seja interpretado como markup/script ao cair no innerHTML.
 */
function escapeHtml(texto) {
    return texto
        .replace(/&/g, '&amp;')
        .replace(/</g, '&lt;')
        .replace(/>/g, '&gt;')
        .replace(/"/g, '&quot;')
        .replace(/'/g, '&#39;');
}

/**
 * Realiza o parse de códigos ANSI para tags HTML estilizadas
 */
function ansiParaHtml(texto) {
    let html = escapeHtml(texto);

    // Sanitização contra caracteres de controle de cursor
    html = html.replace(/\r?\033\[K/g, '');
    html = html.replace(/\r/g, '');
    
    // Substitui quebras de linha literais se houver
    html = html.replace(/\\n/g, '<br>');

    // Negritos e Resets
    html = html.replace(/\033\[1m/g, '<span style="font-weight: 700;">');
    html = html.replace(/\u001b\[1m/g, '<span style="font-weight: 700;">');
    html = html.replace(/\033\[2m/g, '<span style="opacity: 0.72;">');
    html = html.replace(/\u001b\[2m/g, '<span style="opacity: 0.72;">');
    html = html.replace(/\033\[0m/g, '</span>');
    html = html.replace(/\u001b\[0m/g, '</span>');

    // Mapeamento de Cores ANSI
    const cores = {
        '30': 'var(--text-muted)',
        '31': 'rgba(239, 68, 68, 0.95)', // Vermelho elegante
        '32': 'var(--accent-green)',
        '33': 'var(--accent-yellow)',
        '34': 'var(--accent-blue)',
        '35': 'var(--accent-purple)',
        '36': 'var(--accent-cyan)',
        '37': 'var(--text-primary)',
        '90': 'var(--text-muted)'
    };

    for (let code in cores) {
        const regex1 = new RegExp('\\033\\[' + code + 'm', 'g');
        const regex2 = new RegExp('\\u001b\\[' + code + 'm', 'g');
        const replacement = `<span style="color: ${cores[code]};">`;
        html = html.replace(regex1, replacement);
        html = html.replace(regex2, replacement);
    }

    return html;
}

/**
 * PROPÓSITO DE NEGÓCIO: apresenta no navegador uma linha produzida pelo CLI/backend,
 * conservando horário, cores ANSI e a sequência original do processamento.
 *
 * INVARIANTES DO DOMÍNIO: conteúdo externo nunca é interpretado como HTML; cada
 * console mantém no máximo mil linhas visíveis; uma linha não pode ser descartada
 * apenas porque o painel dinâmico ainda não terminou de carregar.
 *
 * COMPORTAMENTO EM CASO DE FALHA: se o elemento visual ainda não existir, guarda a
 * mensagem em memória para exibição posterior; mensagens excedentes mais antigas
 * são removidas para impedir crescimento ilimitado.
 */
function logNoConsoleFormatado(consoleId, rawMessage) {
    const consoleDiv = document.getElementById(consoleId);
    if (!consoleDiv) {
        const pendentes = logsPendentesPorConsole.get(consoleId) || [];
        pendentes.push(rawMessage);
        if (pendentes.length > LIMITE_LOGS_PENDENTES) {
            pendentes.splice(0, pendentes.length - LIMITE_LOGS_PENDENTES);
        }
        logsPendentesPorConsole.set(consoleId, pendentes);
        return;
    }

    // Remove mensagem "Aguardando..." se existir
    const sysMsg = consoleDiv.querySelector('.system-message');
    if (sysMsg) {
        consoleDiv.removeChild(sysMsg);
    }

    const timestamp = new Date().toLocaleTimeString();
    const htmlMensagem = ansiParaHtml(rawMessage);
    
    const linhaLog = document.createElement('div');
    linhaLog.className = 'log-line';
    linhaLog.innerHTML = `<span style="color: var(--text-muted); font-size: 0.75rem;">[${timestamp}]</span> ${htmlMensagem}`;
    
    // Se o operador SUBIU para ler alguma coisa, a decisão de rolar é dele. Medido antes de
    // inserir a linha, senão a altura nova já teria mudado a conta.
    const estavaNoFim = seguindoOFim(consoleDiv);

    consoleDiv.appendChild(linhaLog);

    // Proteção extrema contra travamentos: Limita a 1000 linhas visíveis no console HTML
    while (consoleDiv.childElementCount > 1000) {
        consoleDiv.removeChild(consoleDiv.firstChild);
    }

    if (estavaNoFim) {
        consoleDiv.scrollTop = consoleDiv.scrollHeight;
        esconderAvisoDeNovasLinhas(consoleDiv);
    } else {
        mostrarAvisoDeNovasLinhas(consoleDiv);
    }
}

/**
 * PROPÓSITO DE NEGÓCIO: o console estava PRESO no fim. Cada linha nova executava
 * `scrollTop = scrollHeight` sem condição, então subir para reler era impossível: a linha
 * seguinte jogava o operador de volta para baixo.
 *
 * <p>Prejuízo relatado pelo Paulo em 17/08/2026: "o cli no navegador sobe sem parar e não
 * permite voltar a ele, por isso que víamos errado também". Combinado com o `EN:`/`PT:` que
 * saíam da mesma cor, o resultado foi um dia inteiro investigando um problema inexistente —
 * ele só tinha o relance de linhas em inglês passando, sem poder parar e conferir.
 *
 * INVARIANTES DO DOMÍNIO: a folga de 40px absorve arredondamento de zoom e meia-linha; quem
 * está no fim continua sendo levado junto, que é o comportamento útil durante a corrida.
 *
 * COMPORTAMENTO EM CASO DE FALHA: elemento sem rolagem (conteúdo menor que a caixa) conta como
 * "no fim", então console curto segue rolando normalmente.
 */
function seguindoOFim(el) {
    const FOLGA_PX = 40;
    return el.scrollHeight - el.scrollTop - el.clientHeight <= FOLGA_PX;
}

/** O aviso só existe quando o operador está lendo lá em cima; some sozinho ao voltar ao fim. */
function mostrarAvisoDeNovasLinhas(consoleDiv) {
    const painel = consoleDiv.parentElement;
    if (!painel) return;
    let aviso = painel.querySelector('.console-novas-linhas');
    if (!aviso) {
        painel.style.position = painel.style.position || 'relative';
        aviso = document.createElement('button');
        aviso.type = 'button';
        aviso.className = 'console-novas-linhas';
        aviso.textContent = '↓ novas linhas — clique para voltar ao fim';
        aviso.addEventListener('click', () => {
            consoleDiv.scrollTop = consoleDiv.scrollHeight;
            esconderAvisoDeNovasLinhas(consoleDiv);
        });
        painel.appendChild(aviso);
        // Voltar ao fim rolando com o mouse também tira o aviso, sem precisar do clique.
        consoleDiv.addEventListener('scroll', () => {
            if (seguindoOFim(consoleDiv)) esconderAvisoDeNovasLinhas(consoleDiv);
        });
    }
    aviso.style.display = 'block';
}

function esconderAvisoDeNovasLinhas(consoleDiv) {
    const aviso = consoleDiv.parentElement?.querySelector('.console-novas-linhas');
    if (aviso) aviso.style.display = 'none';
}

/**
 * PROPÓSITO DE NEGÓCIO: entrega ao terminal visual as linhas produzidas enquanto o
 * fragmento HTML do módulo ainda estava sendo carregado pelo navegador.
 *
 * INVARIANTES DO DOMÍNIO: respeita a ordem de chegada e remove o lote do buffer antes
 * de renderizar para impedir duplicação caso outro evento de montagem seja emitido.
 *
 * COMPORTAMENTO EM CASO DE FALHA: se o console ainda não existir ou não houver linhas
 * pendentes, não altera o estado; uma nova montagem poderá tentar novamente.
 */
function descarregarLogsPendentes(consoleId) {
    if (!document.getElementById(consoleId)) return;

    const pendentes = logsPendentesPorConsole.get(consoleId);
    if (!pendentes || pendentes.length === 0) return;

    logsPendentesPorConsole.delete(consoleId);
    pendentes.forEach(mensagem => logNoConsoleFormatado(consoleId, mensagem));
}

/**
 * Renderiza o resultado ESTRUTURADO da Análise de Mídia (JSON via SSE) em
 * cartões + tabela de legendas em destaque. Guarda o resultado em
 * window.__ultimaAnaliseMidia para a exportação TXT manual.
 */
function renderizarAnaliseMidia(consoleId, jsonText) {
    const consoleDiv = document.getElementById(consoleId);
    if (!consoleDiv) return;

    let dados;
    try {
        dados = JSON.parse(jsonText);
    } catch (e) {
        consoleDiv.innerHTML = '<div class="system-message">Falha ao interpretar o resultado da análise.</div>';
        return;
    }
    window.__ultimaAnaliseMidia = dados;

    const resultados = Array.isArray(dados.resultados) ? dados.resultados : [];
    const falhas = Array.isArray(dados.falhas) ? dados.falhas : [];

    const esc = (v) => String(v == null ? '' : v).replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;');
    const kbps = (b) => (b > 0 ? Math.round(b / 1000) + ' kbps' : 'N/A');
    const gib = (bytes) => (bytes > 0 ? (bytes / (1024 ** 3)).toFixed(2) + ' GiB' : 'N/A');
    const dur = (s) => {
        if (!s || s <= 0) return 'N/A';
        const h = Math.floor(s / 3600), m = Math.floor((s % 3600) / 60), sec = Math.floor(s % 60);
        return `${String(h).padStart(2, '0')}:${String(m).padStart(2, '0')}:${String(sec).padStart(2, '0')}`;
    };
    const badgeTrad = (leg) => leg.traduzivel
        ? '<span class="badge-trad sim">Traduzível</span>'
        : (leg.exigeOcr ? '<span class="badge-trad ocr">OCR</span>' : '<span class="badge-trad nao">—</span>');
    const flags = (leg) => {
        const f = [];
        if (leg.isDefault) f.push('default');
        if (leg.isForced) f.push('forced');
        if (leg.acessibilidade) f.push('acess.');
        return f.length ? f.join(', ') : '—';
    };
    const lista = (arr, render, limite = 12) => {
        const a = arr || [];
        if (!a.length) return '<li>—</li>';
        return a.slice(0, limite).map(render).join('') + (a.length > limite ? `<li class="ac-dim">+${a.length - limite}…</li>` : '');
    };

    const comTexto = resultados.filter(r => (r.legendas || []).some(l => l.traduzivel)).length;

    let html = '<div class="analise-wrapper">';
    html += '<div class="analise-cabecalho"><span class="material-symbols-outlined">fact_check</span>'
        + '<span>Análise de Mídia</span>'
        + `<span class="analise-resumo">${resultados.length} arquivo(s) · ${comTexto} com legenda de texto · ${falhas.length} falha(s)</span></div>`;

    if (!resultados.length && !falhas.length) {
        html += '<div class="system-message">Nenhum resultado.</div>';
    }

    for (const r of resultados) {
        const c = r.container || {};
        html += '<div class="analise-card">';
        html += `<div class="ac-titulo"><span class="material-symbols-outlined">movie</span>${esc(r.nomeArquivo)}</div>`;
        html += '<div class="ac-grid">';
        html += '<div class="ac-bloco"><h5>Contêiner</h5><ul>'
            + `<li>Formato: ${esc(c.formato)}</li><li>Tamanho: ${gib(c.tamanhoBytes)}</li>`
            + `<li>Duração: ${dur(c.duracaoSegundos)}</li><li>Bitrate: ${kbps(c.bitrateGeral)}</li>`
            + `<li>Escrito por: ${esc(c.aplicacaoEscrita)}</li></ul></div>`;
        html += `<div class="ac-bloco"><h5>Vídeo (${(r.videos || []).length})</h5><ul>`
            + lista(r.videos, v => `<li>${esc(v.codecId)} · ${v.width}x${v.height} · ${v.bitDepth}bit · ${(v.fps || 0).toFixed(3)}fps · ${kbps(v.bitrate)}</li>`) + '</ul></div>';
        html += `<div class="ac-bloco"><h5>Áudio (${(r.audios || []).length})</h5><ul>`
            + lista(r.audios, a => `<li>${esc(a.idioma)} · ${esc(a.format)} · ${a.channels}ch · ${(a.sampleRateKHz || 0).toFixed(1)}kHz · ${kbps(a.bitrate)}</li>`) + '</ul></div>';
        html += `<div class="ac-bloco"><h5>Capítulos (${(r.capitulos || []).length})</h5><ul>`
            + lista(r.capitulos, cap => `<li>${cap.numero}. ${esc(cap.titulo)} <span class="ac-dim">(${dur(cap.inicioSegundos)})</span></li>`) + '</ul></div>';
        html += `<div class="ac-bloco"><h5>Anexos (${(r.anexos || []).length})</h5><ul>`
            + lista(r.anexos, an => `<li>${esc(an.nomeArquivo)} <span class="ac-dim">${esc(an.mimeType)}</span></li>`) + '</ul></div>';
        html += '</div>';

        html += `<div class="ac-legendas"><h5>Legendas (${(r.legendas || []).length})</h5>`;
        if (!(r.legendas || []).length) {
            html += '<p class="ac-sem-legenda">Nenhuma faixa de legenda encontrada — pode ser RAW; hardsub não confirmado por esta análise.</p>';
        } else {
            html += '<table class="ac-tabela"><thead><tr><th>#</th><th>Idioma</th><th>Formato</th><th>Tipo</th><th>Flags</th><th>Traduzível</th></tr></thead><tbody>';
            for (const leg of r.legendas) {
                html += `<tr><td>${(leg.indexRelativo ?? 0) + 1}</td><td>${esc(leg.idioma)}</td><td>${esc(leg.formato)}</td>`
                    + `<td>${esc(leg.tipoCurto)} <span class="ac-dim">(${esc(leg.categoria)})</span></td>`
                    + `<td>${esc(flags(leg))}</td><td>${badgeTrad(leg)}</td></tr>`;
            }
            html += '</tbody></table>';
        }
        html += '</div></div>';
    }

    if (falhas.length) {
        html += '<div class="analise-falhas"><h5>Falhas na análise (' + falhas.length + ')</h5><ul>'
            + falhas.map(f => `<li><strong>${esc(f.arquivo)}</strong>: ${esc(f.erro)}</li>`).join('') + '</ul></div>';
    }

    html += '</div>';
    consoleDiv.innerHTML = html;
    consoleDiv.scrollTop = 0;
}

/**
 * Constrói o TXT (exportação manual) a partir do resultado estruturado da análise.
 */
export function montarTxtAnaliseMidia(dados) {
    if (!dados) return '';
    const resultados = dados.resultados || [];
    const falhas = dados.falhas || [];
    const L = [];
    L.push('RELATORIO DE ANALISE DE MIDIA');
    L.push('Arquivos: ' + resultados.length + ' | Falhas: ' + falhas.length);
    L.push('');
    for (const r of resultados) {
        const c = r.container || {};
        L.push('='.repeat(70));
        L.push('ARQUIVO: ' + (r.nomeArquivo || ''));
        L.push('='.repeat(70));
        L.push('Conteiner: ' + (c.formato || '') + ' | Duracao: ' + (c.duracaoSegundos || 0).toFixed(2) + 's | ' + ((c.tamanhoBytes || 0) / (1024 ** 3)).toFixed(2) + ' GiB');
        L.push('Faixas: video=' + (r.videos || []).length + ' audio=' + (r.audios || []).length
            + ' legenda=' + (r.legendas || []).length + ' capitulos=' + (r.capitulos || []).length + ' anexos=' + (r.anexos || []).length);
        L.push('Legendas:');
        if (!(r.legendas || []).length) {
            L.push('  (nenhuma faixa; hardsub nao confirmado por esta analise)');
        } else {
            for (const leg of r.legendas) {
                L.push('  [' + ((leg.indexRelativo || 0) + 1) + '] ' + (leg.idioma || '') + ' / ' + (leg.tipoCurto || '')
                    + ' (' + (leg.categoria || '') + ') -> ' + (leg.traduzivel ? 'traduzivel' : (leg.exigeOcr ? 'OCR' : '-'))
                    + ' | default=' + !!leg.isDefault + ' forced=' + !!leg.isForced + ' acess=' + !!leg.acessibilidade);
            }
        }
        L.push('');
    }
    if (falhas.length) {
        L.push('FALHAS:');
        for (const f of falhas) L.push('  ' + (f.arquivo || '') + ': ' + (f.erro || ''));
    }
    return L.join('\r\n');
}

/**
 * Método genérico clássico para logs manuais do frontend
 */
export function logNoConsole(consoleId, mensagem, tipo = 'info') {
    let corAnsi = '\u001b[37m'; // Branco padrão
    if (tipo === 'erro') corAnsi = '\u001b[31m';
    if (tipo === 'aviso') corAnsi = '\u001b[33m';
    if (tipo === 'sucesso') corAnsi = '\u001b[32m';
    if (tipo === 'info') corAnsi = '\u001b[36m'; // Ciano para comandos do sistema

    logNoConsoleFormatado(consoleId, `${corAnsi}${mensagem}\u001b[0m`);
}

// Configura funcionalidade de limpar os consoles
document.addEventListener('click', (e) => {
    const btn = e.target.closest('.btn-clear-console');
    if (!btn) return;

    const consoleId = btn.getAttribute('data-target');
    const consoleDiv = document.getElementById(consoleId);
    if (consoleDiv) {
        consoleDiv.innerHTML = '<div class="system-message">Console limpo. Aguardando novos logs...</div>';
    }
});

// Os botões "Parar Execução" foram removidos da UI (decisão 2026-07-09): a
// parada cooperativa nunca interrompia o job de forma perceptível para o
// usuário. O endpoint POST /api/pipeline/parar continua existindo e é usado
// internamente pelo fluxo de encerramento ("Sair").

// Configura funcionalidade de copiar o conteúdo de um console/relatório
document.addEventListener('click', async (e) => {
    const btn = e.target.closest('.btn-copy-console');
    if (!btn) return;

    const consoleId = btn.getAttribute('data-target');
    const consoleDiv = document.getElementById(consoleId);
    if (!consoleDiv) return;

    const texto = consoleDiv.innerText.trim();
    if (!texto) {
        mostrarAlerta('Não há conteúdo para copiar.', 'aviso');
        return;
    }

    try {
        await navigator.clipboard.writeText(texto);
        mostrarAlerta('Conteúdo copiado para a área de transferência!', 'sucesso');
    } catch (err) {
        console.warn('Falha ao copiar via Clipboard API, usando fallback.', err);
        const textarea = document.createElement('textarea');
        textarea.value = texto;
        textarea.style.position = 'fixed';
        textarea.style.opacity = '0';
        document.body.appendChild(textarea);
        textarea.select();
        try {
            document.execCommand('copy');
            mostrarAlerta('Conteúdo copiado para a área de transferência!', 'sucesso');
        } catch (fallbackErr) {
            mostrarAlerta('Não foi possível copiar automaticamente. Selecione o texto manualmente.', 'erro');
        } finally {
            document.body.removeChild(textarea);
        }
    }
});

/**
 * Exibe um alerta flutuante (Toast) na tela
 */
export function mostrarAlerta(mensagem, tipo = 'info') {
    const container = document.getElementById('toast-container');
    if (!container) return;

    const toast = document.createElement('div');
    toast.className = `toast toast-${tipo}`;

    // Ícone do toast em Material Symbols (regra do design: nunca emojis)
    let icon = 'info';
    if (tipo === 'erro') icon = 'error';
    if (tipo === 'sucesso') icon = 'check_circle';
    if (tipo === 'aviso') icon = 'warning';

    toast.innerHTML = `
        <div class="toast-content">
            <span class="material-symbols-outlined toast-icon">${icon}</span> &nbsp; ${escapeHtml(mensagem)}
        </div>
        <button class="toast-close" title="Fechar">&times;</button>
    `;

    container.appendChild(toast);
    
    // Força reflow para animação
    toast.offsetHeight;
    toast.classList.add('show');

    // Auto-destruir após 7 segundos
    const timeout = setTimeout(() => fecharToast(toast), 7000);

    toast.querySelector('.toast-close').addEventListener('click', () => {
        clearTimeout(timeout);
        fecharToast(toast);
    });
}

function fecharToast(toast) {
    toast.classList.remove('show');
    toast.classList.add('hiding');
    toast.addEventListener('transitionend', () => {
        if (toast.parentNode) {
            toast.parentNode.removeChild(toast);
        }
    });
}

/**
 * Analisa as mensagens recebidas via SSE e dispara alertas caso sejam de erro fatal ou sucesso
 */
export function verificarAlertaSSE(mensagem) {
    // Remove códigos ANSI limpos para exibir no Toast
    const msgLimpa = mensagem.replace(/\u001B\[[0-9;]*m/g, '').replace(/\033\[[0-9;]*m/g, '').trim();
    
    if (msgLimpa.includes('[ERRO]') || msgLimpa.includes('[FAIL]')) {
        const erroMsg = msgLimpa.replace(/\[ERRO\]|\[FAIL\]/, '').trim();
        mostrarAlerta(erroMsg, 'erro');
    } 
    else if (msgLimpa.includes('PROCESSAMENTO FINALIZADO') || msgLimpa.includes('[SUCESSO]')) {
        mostrarAlerta(msgLimpa, 'sucesso');
        const btnRefresh = document.getElementById('btn-refresh-telemetria');
        if (btnRefresh) btnRefresh.click();
    }
}

/**
 * Monitora inputs de caminho/pasta e selects de contexto para carregar dinamicamente os metadados e capas de animes
 */
function limparTermoMetadata(texto) {
    if (!texto) return '';

    return texto
        .replace(/\s*-\s*Revis[aã]o\s+de\s+Lore\s*$/i, '')
        .replace(/\s+Revis[aã]o\s+de\s+Lore\s*$/i, '')
        .replace(/\s+/g, ' ')
        .trim();
}

function inicializarMetadadosDinamicos() {
    const mapeamentoFormularios = [
        { inputId: 'analise-entrada', selectId: 'analise-contexto', bannerId: 'meta-banner-analise' },
        { inputId: 'extracao-entrada', selectId: 'extracao-contexto', bannerId: 'meta-banner-extracao' },
        { inputId: 'auditor-original', selectId: 'auditor-conteudo-contexto', bannerId: 'meta-banner-auditor-conteudo' },
        { inputId: 'remuxer-videos', selectId: 'remuxer-contexto', bannerId: 'meta-banner-remuxer' },
        { inputId: 'traducao-entrada', selectId: 'traducao-contexto', bannerId: 'meta-banner-traducao' },
        { inputId: 'correcao-entrada', selectId: 'correcao-contexto', bannerId: 'meta-banner-correcao' },
        { inputId: 'revisao-entrada', selectId: 'revisao-contexto', bannerId: 'meta-banner-revisao' },
        { inputId: 'cura-entrada-original', selectId: 'cura-contexto', bannerId: 'meta-banner-cura' },
        { inputId: 'revisao-lore-entrada-original', selectId: 'revisao-lore-contexto', bannerId: 'meta-banner-revisao-lore' },
        { inputId: 'troca-tipo-legenda-entrada', selectId: 'troca-tipo-legenda-contexto', bannerId: 'meta-banner-troca-tipo-legenda' },
        { inputId: 'limpanome-entrada', selectId: 'renomear-arquivos-contexto', bannerId: 'meta-banner-limpanome' },
        { inputId: 'traducao-karaoke-entrada', selectId: 'traducao-karaoke-contexto', bannerId: 'meta-banner-traducao-karaoke' },
        { inputId: 'revisao-concordancia-entrada', selectId: 'revisao-concordancia-contexto', bannerId: 'meta-banner-revisao-concordancia' }
    ];

    const atualizarItem = (item) => {
        const input = document.getElementById(item.inputId);
        const select = item.selectId ? document.getElementById(item.selectId) : null;
        let termo = '';

        // Se houver select preenchido com obra válida, usa o contexto como fonte principal de lore
        if (select && select.value && select.selectedIndex >= 0) {
            const option = select.options[select.selectedIndex];
            const optText = option.text;
            if (optText && !optText.includes('Carregando') && !optText.includes('Selecione')) {
                termo = option.dataset.metadataQuery || limparTermoMetadata(optText);
            }
        }

        // Se não houver contexto no select, ou se o usuário digitou um caminho completo de mídia (que não seja termos genéricos de pasta)
        if (!termo && input && input.value.trim().length > 3) {
            const val = input.value.trim();
            if (!['cache', 'logs', 'relatorios'].includes(val.toLowerCase())) {
                termo = val;
            }
        }

        if (termo && termo.length > 2) {
            carregarMetadataAnime(termo, item.bannerId);
        } else {
            const banner = document.getElementById(item.bannerId);
            if (banner) banner.classList.add('hidden');
        }
    };

    // Popula automaticamente todos os selects de contexto dos módulos auxiliares.
    const popularContextos = () => {
        carregarContextosAuxiliares(['extracao-contexto', 'auditor-conteudo-contexto', 'remuxer-contexto', 'analise-contexto', 'traducao-contexto', 'correcao-contexto', 'revisao-contexto', 'cura-contexto', 'revisao-lore-contexto', 'troca-tipo-legenda-contexto', 'renomear-arquivos-contexto', 'traducao-karaoke-contexto', 'revisao-concordancia-contexto'], () => {
            mapeamentoFormularios.forEach(atualizarItem);
        });
    };

    popularContextos();
    document.addEventListener('revisao-lore:painel-carregado', popularContextos);
    document.addEventListener('troca-tipo-legenda:painel-carregado', popularContextos);
    document.addEventListener('renomear-arquivos:painel-carregado', popularContextos);
    // A Tradução de Karaokê disparava o evento desde sempre, mas ninguém repopulava o
    // seletor dela: o painel é injetado depois do carregamento inicial, então o combo de
    // lore ficava vazio. Achado ao pendurar a trava de lore, que depende desse seletor.
    document.addEventListener('traducao-karaoke:painel-carregado', popularContextos);
    // A 3.3 tem o mesmo desenho: painel injetado por fetch depois do boot. Sem esta linha o
    // combo dela abriria vazio, que foi exatamente o defeito da Tradução de Karaokê acima.
    document.addEventListener('revisao-concordancia:painel-carregado', popularContextos);

    mapeamentoFormularios.forEach(item => {
        const input = document.getElementById(item.inputId);
        const select = item.selectId ? document.getElementById(item.selectId) : null;

        if (input) {
            input.addEventListener('input', () => atualizarItem(item));
            input.addEventListener('change', () => atualizarItem(item));
            input.addEventListener('blur', () => atualizarItem(item));
        }

        if (select) {
            select.addEventListener('change', () => atualizarItem(item));
            const observer = new MutationObserver(() => {
                setTimeout(() => atualizarItem(item), 100);
            });
            observer.observe(select, { childList: true });
        }

        // Dispara verificação inicial
        setTimeout(() => atualizarItem(item), 500);
    });

    // Emite carregado ao trocar de aba no menu lateral
    document.querySelectorAll('.nav-menu .nav-item, .sidebar-nav .nav-item').forEach(nav => {
        nav.addEventListener('click', () => {
            setTimeout(() => mapeamentoFormularios.forEach(atualizarItem), 150);
        });
    });
}

/**
 * PROPÓSITO DE NEGÓCIO: faz o select da Revisão de Lore ficar IDÊNTICO ao da Tradução —
 * mesmo nome (com ano UC/CE/RC), mesmo grupo (<optgroup>) e mesma ordem cronológica.
 * Como a fatia revisaoLore não pode importar o CatalogoObras da Tradução (a arquitetura
 * proíbe dependência entre fatias / INBOUND→traducao = 0), o alinhamento é feito no
 * cliente por JOIN de id: o contexto de revisão herda o nome/grupo/ordem já corrigidos
 * do /api/contextos. O mapa de palavras-chave FRANQUIAS_REVISAO_LORE é o FALLBACK para
 * obras de revisão sem par na lista principal.
 *
 * INVARIANTES DO DOMÍNIO: id de revisão espelha id de tradução; a lista sai ordenada por
 * grupo→ordem-canônica para os <optgroup> saírem contíguos e cronológicos.
 *
 * COMPORTAMENTO EM CASO DE FALHA: obra sem par usa o grupo por palavra-chave e vai para o
 * fim do seu grupo (ordem máxima), ordenada por nome.
 */
const FRANQUIAS_REVISAO_LORE = [
    ['Gundam SEED', 'Gundam SEED'],
    ['Reconguista', 'Gundam Reconguista in G'],
    ['Gundam', 'Gundam'],
    ['Macross', 'Macross'],
    ['Evangelion', 'Evangelion'],
    ['DanMachi', 'DanMachi'],
    ['Sidonia', 'Knights of Sidonia'],
    ['Break Blade', 'Break Blade'],
    ['Broken Blade', 'Break Blade']
];

function grupoFranquiaRevisaoLore(nome) {
    if (!nome) return '';
    for (const [chave, rotulo] of FRANQUIAS_REVISAO_LORE) {
        if (nome.includes(chave)) return rotulo;
    }
    return '';
}

function agruparContextosRevisaoLore(lista, contextosPrincipais) {
    // Join por id com a lista principal (/api/contextos, já corrigida pelo CatalogoObras:
    // nome com ano UC/CE/RC, grupo e ordem cronológica). O id do contexto de REVISÃO
    // espelha o id de TRADUÇÃO (ex.: ambos "gundam_0080"), então a Revisão de Lore herda
    // o MESMO nome/grupo/ordem do select da Tradução — os selects ficam idênticos.
    // Obra sem par na lista principal cai no fallback: grupo por palavra-chave no cliente.
    const principaisPorId = new Map();
    (Array.isArray(contextosPrincipais) ? contextosPrincipais : []).forEach((ctx, i) => {
        principaisPorId.set(ctx.id, { nome: ctx.nome, grupo: ctx.grupo || '', ordem: i });
    });
    return lista
        .map(ctx => {
            const principal = principaisPorId.get(ctx.id);
            if (principal) {
                // Mantém id e termoMetadata da revisão; adota nome/grupo/ordem canônicos.
                return { ...ctx, nome: principal.nome, grupo: principal.grupo, ordem: principal.ordem };
            }
            return { ...ctx, grupo: ctx.grupo || grupoFranquiaRevisaoLore(ctx.nome), ordem: Number.MAX_SAFE_INTEGER };
        })
        .sort((a, b) => {
            const chaveA = (a.grupo || a.nome || '').toLowerCase();
            const chaveB = (b.grupo || b.nome || '').toLowerCase();
            if (chaveA !== chaveB) return chaveA < chaveB ? -1 : 1;
            if (a.ordem !== b.ordem) return a.ordem - b.ordem;
            return (a.nome || '').localeCompare(b.nome || '');
        });
}

async function carregarContextosAuxiliares(idsSelects, onComplete) {
    try {
        const [response, responseRevisaoLore] = await Promise.all([
            fetch('/api/contextos', { cache: 'no-store' }),
            fetch('/api/revisao-lore/contextos', { cache: 'no-store' }).catch(() => null)
        ]);
        if (!response.ok) return;
        const contextos = await response.json();
        if (!Array.isArray(contextos) || contextos.length === 0) return;
        const contextosRevisaoLore = responseRevisaoLore?.ok
            ? await responseRevisaoLore.json()
            : contextos;

        // traducao-karaoke-contexto NÃO é auxiliar: o contexto alimenta o prompt
        // do LLM (lore), então recebe a obra padrão pré-selecionada, como o
        // select da Tradução Local.
        const todosSelects = ['extracao-contexto', 'auditor-conteudo-contexto', 'remuxer-contexto', 'analise-contexto', 'traducao-contexto', 'correcao-contexto', 'revisao-contexto', 'cura-contexto', 'revisao-lore-contexto', 'troca-tipo-legenda-contexto', 'renomear-arquivos-contexto', 'traducao-karaoke-contexto', 'revisao-concordancia-contexto'];
        todosSelects.forEach(id => {
            const select = document.getElementById(id);
            if (!select) return;

            // 'revisao-concordancia-contexto' entra como AUXILIAR por decisão de escopo de Paulo
            // (18/08/2026): "lore não entra aqui". Naquela tela a correção é determinística e a
            // obra escolhida não muda uma vírgula do resultado — ela identifica, exibe capa e
            // fica no registro. Tratá-la como seletor de lore seria prometer ao operador um
            // efeito que o motor não tem.
            // 'remuxer-contexto' SAIU dos auxiliares em 2026-09-02, por ordem de Paulo: na 5.1
            // a escolha da obra passa a ser OBRIGATÓRIA, como nas telas de lore. O motivo é de
            // operação, não de motor — o remux publica um arquivo definitivo, de gigabytes, com
            // nome derivado da legenda; começar o lote sem ter dito de qual obra se trata é o
            // caminho curto para publicar a temporada errada numa pasta e só descobrir depois.
            const ehAuxiliar = (id === 'extracao-contexto' || id === 'auditor-conteudo-contexto' || id === 'analise-contexto' || id === 'correcao-contexto' || id === 'cura-contexto' || id === 'troca-tipo-legenda-contexto' || id === 'renomear-arquivos-contexto' || id === 'revisao-concordancia-contexto');
            const ehRevisaoLore = (id === 'revisao-lore-contexto');
            select.innerHTML = '';

            // TRAVA DE LORE: TODO seletor abre num marcador desabilitado, inclusive os
            // auxiliares. Antes, os seletores de lore vinham com a obra `padrao`
            // pré-selecionada — o operador que não olhasse o combo traduzia com a lore de
            // OUTRA obra sem perceber, que é justamente o defeito que a trava impede.
            // Escolher deixou de ser conveniência e passou a ser exigência.
            const optObrigatorio = document.createElement('option');
            optObrigatorio.value = '';
            optObrigatorio.textContent = ehAuxiliar
                ? '-- Selecione uma obra para visualizar --'
                : '-- Selecione a obra (obrigatório) --';
            optObrigatorio.disabled = true;
            optObrigatorio.selected = true;
            select.appendChild(optObrigatorio);

            // Nas telas AUXILIARES a lore não muda o resultado — só carrega capa e metadados.
            // Travá-las sem saída impediria o caso legítimo de analisar uma mídia que não tem
            // lore nenhuma (foi o que aconteceu na Análise de Mídia). A escolha continua
            // DELIBERADA: o operador tem de dizer "sem obra", em vez de a tela abrir liberada.
            if (ehAuxiliar) {
                const optSemObra = document.createElement('option');
                optSemObra.value = '';
                optSemObra.textContent = '— Sem obra (seguir sem capa/lore) —';
                optSemObra.dataset.liberaTrava = 'true';
                select.appendChild(optSemObra);
            }

            // TELA ÚNICA DE KARAOKÊ (4.1): a fuga "sem lore" existe para o Passo 2 (achatar), que
            // NÃO usa lore — inclusive para voltar depois e só limpar uma pasta já traduzida, sem
            // reescolher a obra. É liberaTrava com value VAZIO (não 'sem_lore'): o endpoint de
            // tradução do karaokê exige um contexto que exista no backend, e 'sem_lore' não é um
            // ProvedorContexto registrado (só o endpoint da 2.2 o trata). O Passo 1 (traduzir) exige
            // obra real — a validação no traducaoKaraoke.js recusa traduzir sem lore com mensagem clara.
            if (id === 'traducao-karaoke-contexto') {
                const optSemLore = document.createElement('option');
                optSemLore.value = '';
                optSemLore.textContent = '— Sem lore (apenas achatar) —';
                optSemLore.dataset.liberaTrava = 'true';
                select.appendChild(optSemLore);
            }

            const fonteContextos = ehRevisaoLore && Array.isArray(contextosRevisaoLore) && contextosRevisaoLore.length > 0
                ? agruparContextosRevisaoLore(contextosRevisaoLore, contextos)
                : contextos;

            // Agrupa por franquia (<optgroup>) via helper compartilhado. Para a Revisão de
            // Lore o `grupo` é derivado do nome no cliente (agruparContextosRevisaoLore),
            // já que o backend não envia franquia nesses contextos.
            montarOpcoesContextos(select, fonteContextos, (opt, ctx) => {
                if (ctx.termoMetadata) {
                    opt.dataset.metadataQuery = ctx.termoMetadata;
                }
                // `ctx.padrao` deixou de pré-selecionar: ver comentário da trava acima.
            });

            // Campos de pasta, "Procurar..." e botão de ação ficam inertes até a escolha.
            travarAteEscolherLore(select, {
                idsLiberadores: ['sem_lore'],
                textoAviso: ehAuxiliar
                    ? 'Escolha a obra acima — ou "Sem obra" — para liberar os campos de pasta.'
                    : 'Escolha a obra acima para liberar os campos de pasta.'
            });
        });

        if (onComplete && typeof onComplete === 'function') {
            onComplete();
        }
    } catch (e) {
        console.warn('Falha ao carregar contextos auxiliares:', e);
    }
}

async function carregarMetadataAnime(caminho, bannerId) {
    const banner = document.getElementById(bannerId);
    if (!banner) return;

    try {
        const resp = await fetch(`/api/metadata?caminho=${encodeURIComponent(caminho)}`, { cache: 'no-store' });
        if (!resp.ok) {
            banner.classList.add('hidden');
            return;
        }

        const meta = await resp.json();
        renderizarBannerMetadata(banner, meta);
    } catch (e) {
        console.warn('Erro ao carregar metadata:', e);
        banner.classList.add('hidden');
    }
}

function renderizarBannerMetadata(banner, meta) {
    if (!meta || !meta.titulo) {
        banner.classList.add('hidden');
        return;
    }

    const posterHtml = meta.posterUrl 
        ? `<div class="meta-poster-container"><img src="${escapeHtml(meta.posterUrl)}" alt="${escapeHtml(meta.titulo)}" class="meta-poster-img" onerror="this.src='img/kronos_logo.svg'"></div>`
        : '';

    const scoreHtml = meta.score ? `<span class="meta-badge score"><span class="material-symbols-outlined">star</span> ${meta.score}</span>` : '';
    const anoHtml = meta.ano ? `<span class="meta-badge">${meta.ano}</span>` : '';
    const epsHtml = meta.episodios ? `<span class="meta-badge">${meta.episodios} eps</span>` : '';
    const subTitle = meta.tituloJapones || meta.tituloIngles || '';

    let generosHtml = '';
    if (meta.generos && meta.generos.length > 0) {
        generosHtml = meta.generos.slice(0, 3).map(g => `<span class="meta-badge genre">${escapeHtml(g)}</span>`).join('');
    }

    banner.innerHTML = `
        ${posterHtml}
        <div class="meta-info-container">
            <div class="meta-header-titles">
                <div class="meta-title-main">${escapeHtml(meta.titulo)}</div>
                ${subTitle ? `<div class="meta-title-sub">${escapeHtml(subTitle)}</div>` : ''}
            </div>
            <div class="meta-badges-row">
                ${scoreHtml}
                ${anoHtml}
                ${epsHtml}
                ${generosHtml}
            </div>
            ${meta.sinopse ? `<div class="meta-synopsis">${escapeHtml(meta.sinopse)}</div>` : ''}
        </div>
    `;

    banner.classList.remove('hidden');
}

function inicializarBotoesLimpezaFormularios() {
    document.querySelectorAll('.btn-clear-form').forEach(btn => {
        btn.addEventListener('click', () => {
            const formId = btn.getAttribute('data-form');
            const form = formId ? document.getElementById(formId) : btn.closest('form');
            if (!form) return;

            // Reseta todos os inputs de texto e selects do formulário
            form.querySelectorAll('input[type="text"]').forEach(input => input.value = '');
            form.querySelectorAll('select').forEach(select => {
                select.selectedIndex = 0;
            });

            // Oculta o banner de metadados associado se houver
            const cardParent = form.closest('.glass-card');
            if (cardParent) {
                const banner = cardParent.querySelector('.anime-meta-banner');
                if (banner) banner.classList.add('hidden');
            }
        });
    });
}

function inicializarControlesConsole() {
    document.addEventListener('click', (e) => {
        const btn = e.target.closest('.btn-toggle-console');
        if (!btn) return;

        const targetId = btn.getAttribute('data-target');
        const consoleBody = targetId ? document.getElementById(targetId) : null;
        if (!consoleBody) return;

        consoleBody.classList.toggle('expanded');
        if (consoleBody.classList.contains('expanded')) {
            btn.innerHTML = '<span class="material-symbols-outlined console-action-icon">unfold_less</span>Encolher';
        } else {
            btn.innerHTML = '<span class="material-symbols-outlined console-action-icon">unfold_more</span>Expandir';
        }
    });
}

/**
 * Capacidade de diálogo nativo, perguntada UMA vez e memorizada. A pergunta é
 * barata; a alternativa — descobrir tentando — custa até 3 minutos de timeout
 * por clique quando o diálogo não existe (contêiner Linux).
 */
let dialogoNativoDisponivel = null;

async function temDialogoNativo() {
    if (dialogoNativoDisponivel !== null) return dialogoNativoDisponivel;
    try {
        const res = await fetch('/api/dialogo/capacidade');
        if (!res.ok) {
            // NAO memoriza: 404/503 durante o recompile do quarkusDev e transitorio.
            return false;
        }
        dialogoNativoDisponivel = Boolean((await res.json()).nativo);
    } catch (err) {
        // Falha fechada NESTE clique, mas NAO memorizada — a resposta continua
        // sendo o navegador do servidor agora, e a proxima tentativa pergunta de novo.
        //
        // O BUG que isto corrige (Paulo, 07/08/2026: "esse modelo atual e horrivel"):
        // antes, uma unica falha de rede gravava `false` na variavel e TODO clique da
        // aba passava a abrir o modal, mesmo com o servidor respondendo {"nativo":true}
        // — so um F5 desfazia. Em quarkusDev isso acontece o tempo todo, porque cada
        // recompilacao derruba o endpoint por instantes. Memorizar indisponibilidade
        // transitoria como permanente e o mesmo defeito da regra 13: estado anotado na
        // execucao em vez de perguntado quando importa.
        return false;
    }
    return dialogoNativoDisponivel;
}

/**
 * PROPÓSITO DE NEGÓCIO: abre o navegador de pastas SERVIDO PELO SERVIDOR e
 * devolve o caminho escolhido — o caminho que funciona onde o diálogo nativo do
 * Windows não existe, inclusive dentro de um contêiner.
 *
 * INVARIANTES DO DOMÍNIO: o que aparece é o que o SERVIDOR enxerga, então o
 * caminho escolhido já nasce válido para o processo que vai usá-lo. Recusa do
 * servidor é exibida com o motivo, nunca como lista vazia — pasta sem subpastas
 * e servidor sem raiz configurada são situações diferentes.
 *
 * COMPORTAMENTO EM CASO DE FALHA: resolve com `null` no cancelamento; erro de
 * rede aparece como aviso dentro do modal e mantém a janela aberta para nova
 * tentativa.
 */
function abrirNavegadorPastas(dirInicial, tipo) {
    const modal = document.getElementById('modal-navegador');
    const lista = document.getElementById('navegador-lista');
    const atual = document.getElementById('navegador-atual');
    const aviso = document.getElementById('navegador-aviso');
    const btnSubir = document.getElementById('btn-navegador-subir');
    const btnUsar = document.getElementById('btn-navegador-confirmar');
    const btnCancelar = document.getElementById('btn-navegador-cancelar');
    if (!modal || !lista) return Promise.resolve(null);

    const pedeArquivo = tipo === 'arquivo';
    let caminhoAtual = dirInicial || '';
    let caminhoPai = '';

    return new Promise((resolve) => {
        const fechar = (valor) => {
            modal.classList.add('hidden');
            btnSubir.onclick = null;
            btnUsar.onclick = null;
            btnCancelar.onclick = null;
            document.removeEventListener('keydown', aoTeclar);
            resolve(valor);
        };
        const aoTeclar = (ev) => { if (ev.key === 'Escape') fechar(null); };

        const mostrarAviso = (texto) => {
            aviso.textContent = texto;
            aviso.classList.remove('hidden');
        };

        const carregar = async (caminho) => {
            lista.innerHTML = '<div class="navegador-vazio">Carregando...</div>';
            aviso.classList.add('hidden');
            const url = `/api/navegador/pastas?tipo=${encodeURIComponent(tipo || 'pasta')}`
                + (caminho ? `&caminho=${encodeURIComponent(caminho)}` : '');
            let res;
            try {
                res = await fetch(url);
            } catch (err) {
                lista.innerHTML = '';
                mostrarAviso(`Falha de rede ao listar: ${err.message}`);
                return;
            }
            const dados = await res.json().catch(() => ({}));
            if (!res.ok) {
                lista.innerHTML = '';
                mostrarAviso(dados.erro || `O servidor recusou a listagem (HTTP ${res.status}).`);
                return;
            }

            caminhoAtual = dados.atual || '';
            caminhoPai = dados.pai || '';
            atual.textContent = caminhoAtual || 'Raízes permitidas';
            btnSubir.disabled = !caminhoPai;
            // Sem caminho ainda escolhido não há "esta pasta" para usar.
            btnUsar.disabled = !caminhoAtual;

            const pastas = dados.pastas || [];
            const arquivos = pedeArquivo ? (dados.arquivos || []) : [];
            lista.innerHTML = '';

            if (pastas.length === 0 && arquivos.length === 0) {
                lista.innerHTML = '<div class="navegador-vazio">Esta pasta não tem subpastas.</div>';
                return;
            }

            for (const p of pastas) {
                const item = document.createElement('button');
                item.type = 'button';
                item.className = 'navegador-item';
                item.innerHTML = `<span class="material-symbols-outlined">folder</span><span></span>`;
                item.lastElementChild.textContent = p.nome;
                item.onclick = () => carregar(p.caminho);
                lista.appendChild(item);
            }
            for (const a of arquivos) {
                const item = document.createElement('button');
                item.type = 'button';
                item.className = 'navegador-item';
                item.innerHTML = `<span class="material-symbols-outlined">description</span><span></span>`;
                item.lastElementChild.textContent = a.nome;
                item.onclick = () => fechar(a.caminho);
                lista.appendChild(item);
            }
        };

        btnSubir.onclick = () => { if (caminhoPai) carregar(caminhoPai); };
        btnUsar.onclick = () => fechar(caminhoAtual || null);
        btnCancelar.onclick = () => fechar(null);
        document.addEventListener('keydown', aoTeclar);

        // Em modo arquivo não existe "usar esta pasta": a escolha só se completa
        // clicando num arquivo. Deixar o botão visível ofereceria uma saída que
        // devolveria uma pasta para um campo que espera um arquivo.
        btnUsar.classList.toggle('hidden', pedeArquivo);
        modal.classList.remove('hidden');
        carregar(caminhoAtual);
    });
}

/**
 * PROPÓSITO DE NEGÓCIO: escolhe UM caminho para um campo de formulário, pelo
 * meio que existir nesta instalação.
 *
 * INVARIANTES DO DOMÍNIO: o diálogo nativo é atalho de conveniência, não
 * requisito. Quando ele não existe, o navegador do servidor assume — e nenhum
 * dos 27 botões fica inerte.
 *
 * COMPORTAMENTO EM CASO DE FALHA: devolve `null`, que o chamador trata como
 * "usuário não escolheu nada" e deixa o campo intocado.
 */
async function escolherCaminho(tipo, dirInicial) {
    if (await temDialogoNativo()) {
        const base = tipo === 'arquivo' ? '/api/dialogo/selecionar-arquivo' : '/api/dialogo/selecionar-pasta';
        const url = dirInicial ? `${base}?dirInicial=${encodeURIComponent(dirInicial)}` : base;
        const res = await fetch(url);
        if (res.ok) {
            const data = await res.json();
            return data.caminho || null;
        }
    }
    return abrirNavegadorPastas(dirInicial, tipo);
}

/**
 * PROPÓSITO DE NEGÓCIO: conecta todos os botões "Procurar..." dos formulários
 * ao seletor centralizado e transfere a escolha para o campo correto.
 *
 * INVARIANTES DO DOMÍNIO: apenas um seletor pode ficar aberto em toda a SPA;
 * cliques concorrentes não podem enfileirar janelas que apareceriam depois.
 *
 * COMPORTAMENTO EM CASO DE FALHA: registra o erro no console, restaura o botão
 * e libera o bloqueio global para que uma nova tentativa possa ser feita.
 */
function inicializarBotoesProcurarCaminho() {
    document.body.addEventListener('click', async (e) => {
        const btn = e.target.closest('.btn-procurar');
        if (!btn) return;
        if (btn.disabled || seletorCaminhoEmAndamento) return;

        const targetId = btn.getAttribute('data-target');
        const tipo = btn.getAttribute('data-type') || 'pasta';
        // Diretório inicial opcional (ex.: pasta "cache" do projeto na Correção de Cache).
        const dirInicial = btn.getAttribute('data-dir-inicial') || '';
        const inputTarget = document.getElementById(targetId);
        if (!inputTarget) return;

        const textoOriginal = btn.innerHTML;
        btn.innerHTML = '⏳ Abrindo...';
        btn.disabled = true;
        seletorCaminhoEmAndamento = true;

        try {
            const caminho = await escolherCaminho(tipo, dirInicial);
            if (caminho) {
                inputTarget.value = caminho;
                inputTarget.dispatchEvent(new Event('input', { bubbles: true }));
                inputTarget.dispatchEvent(new Event('change', { bubbles: true }));
            }
        } catch (err) {
            console.error('Erro ao abrir seletor nativo:', err);
        } finally {
            btn.innerHTML = textoOriginal;
            btn.disabled = false;
            seletorCaminhoEmAndamento = false;
        }
    });
}

/**
 * Menu "Sair": encerra a aplicação inteira (servidor + trabalho em execução).
 * Fluxo: abre o modal de confirmação (avisando se a fila do pipeline está
 * ocupada), chama POST /api/sistema/sair e cobre a tela com o aviso final.
 * A parada do job em execução é cooperativa (próximo ponto seguro).
 */
function inicializarBotaoSair() {
    const btnMenu = document.getElementById('btn-menu-sair');
    const modal = document.getElementById('modal-sair');
    const avisoPipeline = document.getElementById('modal-sair-aviso-pipeline');
    const btnCancelar = document.getElementById('btn-sair-cancelar');
    const btnConfirmar = document.getElementById('btn-sair-confirmar');
    const overlayEncerrado = document.getElementById('overlay-encerrado');
    if (!btnMenu || !modal || !btnCancelar || !btnConfirmar || !overlayEncerrado) return;

    btnMenu.addEventListener('click', async () => {
        modal.classList.remove('hidden');
        if (avisoPipeline) {
            avisoPipeline.classList.add('hidden');
            try {
                const res = await fetch('/api/pipeline/status');
                const data = await res.json();
                if (data.mensagem === 'ocupada') {
                    avisoPipeline.classList.remove('hidden');
                }
            } catch (e) {
                // Status indisponível não impede a confirmação de saída.
            }
        }
    });

    btnCancelar.addEventListener('click', () => modal.classList.add('hidden'));
    modal.addEventListener('click', (e) => {
        if (e.target === modal) modal.classList.add('hidden');
    });

    btnConfirmar.addEventListener('click', async () => {
        btnConfirmar.disabled = true;
        btnCancelar.disabled = true;
        try {
            const res = await fetch('/api/sistema/sair', { method: 'POST' });
            const data = await res.json().catch(() => ({}));
            if (!res.ok) {
                // Recusa (vitrine pública ou falha do servidor): a aplicação NÃO encerrou,
                // então a tela de "encerrada" seria mentira.
                mostrarAlerta(data.mensagem || data.erro || `Não foi possível encerrar (HTTP ${res.status}).`, 'aviso');
                modal.classList.add('hidden');
                btnConfirmar.disabled = false;
                btnCancelar.disabled = false;
                return;
            }
            mostrarAlerta(data.mensagem || 'Encerrando a aplicação...', 'aviso');
            modal.classList.add('hidden');
            overlayEncerrado.classList.remove('hidden');
            // Navegadores só permitem window.close() em janelas abertas por
            // script; se não fechar, o overlay orienta a fechar manualmente.
            setTimeout(() => window.close(), 1500);
        } catch (err) {
            mostrarAlerta(`Erro ao encerrar a aplicação: ${err.message}`, 'erro');
            btnConfirmar.disabled = false;
            btnCancelar.disabled = false;
        }
    });
}

// Metadados e utilitários de formulário são inicializados no DOMContentLoaded principal acima.
