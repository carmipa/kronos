import { logNoConsole, mostrarAlerta } from '../js/app.js';
// Mesmo aviso da Traducao Local, do MESMO modulo — a revisao de lore tambem leva minutos e quem
// dispara sai de perto. Pedido de Paulo em 17/08/2026.
import { armarAvisoSonoro, tocarAvisoSonoro, mensagemDoAviso } from '../js/avisoSonoro.js';
// O cartao do alvo tem dono unico desde 19/08/2026 — ver o Javadoc de ligarCartaoLoreAtiva.
import { ligarCartaoAlvoAtivo } from '../js/cartaoAlvoAtivo.js?v=1.1';

const PAINEL_HTML = 'revisaoLore/revisaoLore.html?v=3.3';

async function carregarPainelHtml() {
    const painel = document.getElementById('panel-revisao-lore');
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

/**
 * PROPÓSITO DE NEGÓCIO: mantém o botão bloqueado enquanto a revisão de lore
 * ainda está na fila ou em execução no servidor, evitando duplo disparo do job
 * (o POST só ENFILEIRA — o trabalho real roda em segundo plano).
 * INVARIANTES DO DOMÍNIO: só retorna quando a fila do pipeline reporta "livre";
 * o polling nunca interfere no stream SSE do console (canais independentes).
 * COMPORTAMENTO EM CASO DE FALHA: se o status da fila ficar indisponível, loga
 * um aviso e retorna, liberando o botão para nova tentativa.
 */
async function acompanharConclusao() {
    try {
        for (;;) {
            const resposta = await fetch('/api/pipeline/status', { cache: 'no-store' });
            if (!resposta.ok) break;
            const dados = await resposta.json();
            if (dados.mensagem === 'livre') break;
            await new Promise(resolve => setTimeout(resolve, 1000));
        }
    } catch (erro) {
        logNoConsole('console-revisao-lore', `Não foi possível acompanhar o estado da fila: ${erro.message}`, 'aviso');
    }
}

/**
 * PROPÓSITO DE NEGÓCIO: liga o formulário da opção 7 ao job assíncrono e
 * mantém o botão coerente com a fila real.
 * INVARIANTES DO DOMÍNIO: contexto e duas pastas são obrigatórios antes do POST.
 * COMPORTAMENTO EM CASO DE FALHA: exibe o erro no console e reabilita o botão.
 */
function vincularEventos() {
    const btnIniciar = document.getElementById('btn-iniciar-revisao-lore');
    const inputOriginal = document.getElementById('revisao-lore-entrada-original');
    const inputTraduzida = document.getElementById('revisao-lore-entrada-traduzida');
    const selectContexto = document.getElementById('revisao-lore-contexto');

    if (!btnIniciar || !inputOriginal || !inputTraduzida || !selectContexto) return;

    btnIniciar.addEventListener('click', async () => {
        const diretorioOriginal = inputOriginal.value.trim();
        const diretorioTraduzido = inputTraduzida.value.trim();
        const contextoId = selectContexto.value;

        if (!diretorioOriginal || !diretorioTraduzido) {
            mostrarAlerta('Informe as pastas com legendas originais e traduzidas!', 'erro');
            return;
        }
        if (!contextoId) {
            mostrarAlerta('Selecione a obra/contexto para carregar a lore oficial.', 'erro');
            return;
        }

        // Sem checkbox desde 17/08/2026: o sistema determina. O corretor deterministico varre
        // TODA fala no alcance, sempre; o LLM entra so na que a heuristica de lore acusou.
        const revisarTodasFalas = true;
        const nomeObra = selectContexto.options[selectContexto.selectedIndex]?.text || contextoId;

        // Este clique e o gesto que libera o audio no navegador. O estado e DITO na hora, em
        // tres valores: quem vai sair de perto precisa saber ANTES se pode confiar no som.
        const estadoAviso = armarAvisoSonoro();
        logNoConsole('console-revisao-lore', mensagemDoAviso(estadoAviso),
            estadoAviso === 'armado' ? 'info' : 'aviso');

        logNoConsole('console-revisao-lore', `Iniciando revisão de lore — Obra: ${nomeObra}`, 'info');
        logNoConsole('console-revisao-lore', `Original: ${diretorioOriginal} | Traduzida: ${diretorioTraduzido}`, 'info');
        btnIniciar.disabled = true;

        try {
            const res = await fetch('/api/revisar-lore', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({
                    diretorioOriginal,
                    diretorioTraduzido,
                    contextoId,
                    revisarTodasFalas
                })
            });

            const data = await res.json().catch(() => ({}));

            if (!res.ok) {
                throw new Error(data.erro || 'Falha ao iniciar revisão de lore');
            }

            logNoConsole('console-revisao-lore', data.mensagem || 'Revisão de lore iniciada.', 'sucesso');
            mostrarAlerta('Revisão de lore iniciada! Acompanhe os logs.', 'sucesso');

            // Botão permanece bloqueado até o job REAL terminar na fila; só então
            // liberamos e sinalizamos o fim (o status real fica no banner do console).
            await acompanharConclusao();
            mostrarAlerta('Revisão de lore finalizada. Confira o status e o resumo no console.', 'info');
            tocarAvisoSonoro();
            const btnRefresh = document.getElementById('btn-refresh-telemetria');
            if (btnRefresh) btnRefresh.click();
        } catch (err) {
            logNoConsole('console-revisao-lore', `Erro: ${err.message}`, 'erro');
            mostrarAlerta(err.message, 'erro');
        } finally {
            btnIniciar.disabled = false;
        }
    });
}



/**
 * PROPÓSITO DE NEGÓCIO: mostra, o tempo todo, QUAL lore está ativa e para QUAL pasta ela vai
 * escrever — no mesmo cartão que a 3.1 e a 3.3 usam. Sem isto o operador escolhe a obra num combo
 * lá em cima e perde a confirmação de vista ao preencher as pastas, que é onde o erro custa caro.
 *
 * <p>Em 19/08/2026 deixou de ter montagem própria. A cópia daqui interpolava a pasta direto no
 * `innerHTML` — a única das quatro telas que fazia isso — enquanto as vizinhas usavam
 * `textContent`. Ninguém decidiu a diferença; ela apareceu sozinha, que é como divergência de
 * cópia sempre aparece. O dono do cartão agora é `js/cartaoAlvoAtivo.js`.
 *
 * <p>Sem `caixaId` de propósito: o destaque visual da caixa incompleta é comportamento da 3.1, e
 * unificar código não é licença para inventar comportamento novo em tela que já funciona.
 *
 * INVARIANTES: lê a obra do próprio <select> e a pasta do campo que SERÁ REESCRITO.
 * COMPORTAMENTO EM CASO DE FALHA: elemento ausente devolve sem lançar — a tela perde o cartão,
 * nunca o carregamento.
 */
function ligarCartaoLoreAtiva() {
    ligarCartaoAlvoAtivo({
        alvoTextoId: 'revisao-lore-alvo-texto',
        selectId: 'revisao-lore-contexto',
        pastaId: 'revisao-lore-entrada-traduzida',
        rotuloObra: 'Lore ativa',
        semEscolha: 'Escolha a obra para liberar os campos.'
    });
}


/** Rola ate o formulario e o destaca — o botao da passada aponta para ele. */
function ligarAtalhoDaPassada() {
    document.getElementById('btn-passada-lore-com-ingles')?.addEventListener('click', () => {
        const campo = document.getElementById('revisao-lore-entrada-original');
        campo?.scrollIntoView({ behavior: 'smooth', block: 'center' });
        campo?.focus();
    });
}

export async function initRevisaoLore() {
    try {
        await carregarPainelHtml();
        vincularEventos();
        ligarCartaoLoreAtiva();
        ligarAtalhoDaPassada();
        // A trava cobre o .panel INTEIRO, e e por isso que ela serve aqui: esta tela tem DOIS
        // formularios e UM seletor de obra — travar so o form mais proximo deixaria o outro aberto.
        const { travarAteEscolherLore } = await import('../js/travaLore.js');
        travarAteEscolherLore('revisao-lore-contexto');
        document.dispatchEvent(new CustomEvent('revisao-lore:painel-carregado'));
    } catch (err) {
        console.error('[Revisão de Lore] Erro ao carregar painel:', err);
        const painel = document.getElementById('panel-revisao-lore');
        if (painel) {
            painel.innerHTML = '<div class="glass-card"><p class="card-desc">Não foi possível carregar o painel de Revisão de Lore.</p></div>';
        }
    }
}
