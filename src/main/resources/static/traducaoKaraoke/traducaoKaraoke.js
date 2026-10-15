import { mostrarAlerta, logNoConsole } from '../js/app.js';
import { ligarCartaoAlvoAtivo } from '../js/cartaoAlvoAtivo.js?v=1.1';
// Karaoke e trabalho de fila: o POST so ENFILEIRA, o LLM/achatamento roda em segundo plano. Sem
// acompanhar a fila a tela dizia "iniciado" e calava — quem dispara 22 episodios sai de perto.
import { armarAvisoSonoro, tocarAvisoSonoro, mensagemDoAviso } from '../js/avisoSonoro.js';

const PAINEL_HTML = 'traducaoKaraoke/traducaoKaraoke.html?v=1.4';
const CONSOLE_ID = 'console-traducao-karaoke';
const SUFIXO_SAIDA_TRADUCAO = '-karaoke-ptbr';

/**
 * PROPÓSITO DE NEGÓCIO: monta a tela ÚNICA de Karaokê — as duas passadas (traduzir letras e
 * achatar/limpar a animação) na mesma tela, na ordem em que se aplicam.
 *
 * INVARIANTES DO DOMÍNIO: o fragmento é carregado uma única vez e sempre a versão atual (o
 * cache-buster ?v= sobe junto com o HTML); o console é compartilhado pelas duas passadas.
 *
 * COMPORTAMENTO EM CASO DE FALHA: falha ao buscar o fragmento vira mensagem visível no painel.
 */
async function carregarPainelHtml() {
    const painel = document.getElementById('panel-traducao-karaoke');
    if (!painel || painel.dataset.moduloCarregado === 'true') {
        return painel;
    }

    const resposta = await fetch(PAINEL_HTML, { cache: 'no-store' });
    if (!resposta.ok) {
        throw new Error(`Falha ao carregar ${PAINEL_HTML}`);
    }

    painel.innerHTML = await resposta.text();
    painel.dataset.moduloCarregado = 'true';
    return painel;
}

export async function initTraducaoKaraoke() {
    try {
        await carregarPainelHtml();
        vincularEventos();
        document.dispatchEvent(new CustomEvent('traducao-karaoke:painel-carregado'));
    } catch (err) {
        console.error('[Karaokê] Erro ao carregar painel:', err);
        const painel = document.getElementById('panel-traducao-karaoke');
        if (painel) {
            painel.innerHTML = '<div class="glass-card"><p class="card-desc">Não foi possível carregar o painel do Karaokê.</p></div>';
        }
    }
}

/**
 * PROPÓSITO DE NEGÓCIO: espera a fila do pipeline esvaziar, para a tela saber que a passada
 * REALMENTE terminou — o POST apenas ENFILEIRA, e o trabalho roda em segundo plano.
 *
 * INVARIANTES DO DOMÍNIO: devolve true SÓ quando a fila reporta "livre"; nunca lança.
 * COMPORTAMENTO EM CASO DE FALHA: status HTTP de erro ou falha de rede devolvem false — a tela
 * NÃO pode dizer "concluída" sem ter visto a fila livre (24/09/2026: o break no erro fazia isso).
 */
async function acompanharConclusao() {
    try {
        for (;;) {
            const resposta = await fetch('/api/pipeline/status', { cache: 'no-store' });
            if (!resposta.ok) {
                logNoConsole(CONSOLE_ID, `Não foi possível acompanhar a fila (HTTP ${resposta.status}).`, 'aviso');
                return false;
            }
            const dados = await resposta.json();
            if (dados.mensagem === 'livre') return true;
            await new Promise(resolve => setTimeout(resolve, 1000));
        }
    } catch (erro) {
        logNoConsole(CONSOLE_ID, `Não foi possível acompanhar o estado da fila: ${erro.message}`, 'aviso');
        return false;
    }
}

function vincularEventos() {
    const form = document.getElementById('form-traducao-karaoke');
    if (!form) return;

    const entrada = document.getElementById('traducao-karaoke-entrada');
    const achatarOrigem = document.getElementById('traducao-karaoke-achatar-origem');

    // ENCADEAMENTO DE PASTA: o Passo 2 achata a SAÍDA do Passo 1. Enquanto o operador não editar
    // à mão o campo do Passo 2, ele espelha "<entrada>-karaoke-ptbr". Ao editar, para de espelhar —
    // o campo passa a ser dele (permite achatar uma pasta já traduzida de outra origem).
    if (entrada && achatarOrigem) {
        const sincronizar = () => {
            if (achatarOrigem.dataset.editadoManual === 'true') return;
            const base = entrada.value.trim().replace(/[\\/]+$/, '');
            achatarOrigem.value = base ? base + SUFIXO_SAIDA_TRADUCAO : '';
        };
        entrada.addEventListener('input', sincronizar);
        entrada.addEventListener('change', sincronizar);
        achatarOrigem.addEventListener('input', () => {
            // Vazio volta a espelhar; qualquer texto do operador congela o campo como dele.
            achatarOrigem.dataset.editadoManual = achatarOrigem.value.trim() ? 'true' : 'false';
        });
        sincronizar();
    }

    // CARTÃO DE ALVO ATIVO — igual às telas 3.x: mantém lore + pasta à vista o tempo todo.
    const atualizarAlvo = ligarCartaoAlvoAtivo({
        alvoTextoId: 'traducao-karaoke-alvo-texto',
        selectId: 'traducao-karaoke-contexto',
        pastaId: 'traducao-karaoke-entrada',
        caixaId: 'traducao-karaoke-alvo',
        rotuloObra: 'Lore ativa',
        rotuloPasta: 'Pasta a traduzir',
        semEscolha: 'Escolha a obra (ou "sem lore") acima para exibir a capa e liberar as passadas.'
    }) || (() => {});
    // "Limpar Campos" grava o valor direto, sem disparar evento — repinta o cartão depois dele.
    document.querySelector('#panel-traducao-karaoke .btn-clear-form')
        ?.addEventListener('click', () => setTimeout(() => {
            if (achatarOrigem) achatarOrigem.dataset.editadoManual = 'false';
            atualizarAlvo();
        }, 0));
    // Os contextos chegam por fetch depois do boot; sem isto o cartão ficaria preso em "nenhuma".
    document.getElementById('traducao-karaoke-contexto')
        ?.addEventListener('kronos:contextos-carregados', atualizarAlvo);

    // PASSO 1 — TRADUZIR (LLM). Simular é read-only; aplicar entra na fila.
    document.getElementById('btn-traducao-karaoke-simular')?.addEventListener('click', async () => {
        if (!validarTraducao()) return;
        await executarTraducao('/api/traducao-karaoke/simular', 'Simulação da Tradução de Karaokê (Dry-Run)', false);
    });
    form.addEventListener('submit', async (e) => {
        e.preventDefault();
        if (!validarTraducao()) return;
        await executarTraducao('/api/traducao-karaoke/aplicar', 'Tradução de Karaokê (LLM)', true);
    });

    // PASSO 2 — ACHATAR (sem LLM). Simular é read-only; aplicar entra na fila.
    document.getElementById('btn-traducao-karaoke-achatar-simular')?.addEventListener('click', async () => {
        if (!validarAchatar()) return;
        await executarAchatar('/api/novo-karaoke/simular', 'Simulação do Achatamento de Karaokê (Dry-Run)', false);
    });
    document.getElementById('btn-traducao-karaoke-achatar-aplicar')?.addEventListener('click', async () => {
        if (!validarAchatar()) return;
        await executarAchatar('/api/novo-karaoke/aplicar', 'Achatamento de Karaokê', true);
    });

    function validarTraducao() {
        if (!entrada || !entrada.value.trim()) {
            mostrarAlerta('Preencha a pasta com as legendas que deseja traduzir.', 'aviso');
            return false;
        }
        // O Passo 1 exige OBRA real: a lore alimenta o prompt do LLM e o endpoint de tradução do
        // karaokê recusa contexto vazio/inexistente. "Sem lore" libera só o Passo 2 (Achatar).
        const contexto = document.getElementById('traducao-karaoke-contexto');
        if (!contexto || !contexto.value) {
            mostrarAlerta('Para TRADUZIR letras, escolha uma obra — a lore alimenta o LLM. Em "sem lore" só o Passo 2 (Achatar) funciona.', 'aviso');
            return false;
        }
        return true;
    }

    function validarAchatar() {
        const origem = achatarOrigem ? achatarOrigem.value.trim() : '';
        if (!origem) {
            mostrarAlerta('Informe a pasta a achatar (normalmente a saída da tradução, -karaoke-ptbr). Rode o Passo 1 primeiro.', 'aviso');
            return false;
        }
        return true;
    }

    async function executarTraducao(url, descricao, aguardarFila) {
        const contexto = document.getElementById('traducao-karaoke-contexto');
        const contextoId = contexto && contexto.value ? contexto.value : null;
        await postarEAcompanhar(url, descricao, aguardarFila, {
            caminhoOrigem: entrada.value.trim(),
            contextoId: contextoId
        });
    }

    async function executarAchatar(url, descricao, aguardarFila) {
        await postarEAcompanhar(url, descricao, aguardarFila, {
            caminhoOrigem: achatarOrigem.value.trim(),
            caminhoDestino: ''
        });
    }

    /**
     * PROPÓSITO DE NEGÓCIO: dispara a passada, loga no console compartilhado e, quando ela entra
     * na fila, arma o aviso sonoro NO GESTO do clique e espera a fila esvaziar.
     *
     * INVARIANTES DO DOMÍNIO: o aviso é armado antes do await — o navegador só libera áudio após um
     * gesto, e o clique é esse gesto; armar depois não tocaria. Desabilita os botões durante a
     * passada e reabilita no finally.
     * COMPORTAMENTO EM CASO DE FALHA: erro de rede/HTTP loga no console e alerta; nunca deixa botão travado.
     */
    async function postarEAcompanhar(url, descricao, aguardarFila, payload) {
        logNoConsole(CONSOLE_ID, `Iniciando ${descricao}...`, 'info');
        logNoConsole(CONSOLE_ID, `Pasta: ${payload.caminhoOrigem}`, 'info');

        const botoes = document.querySelectorAll('#panel-traducao-karaoke button');
        botoes.forEach(b => { b.disabled = true; });

        try {
            const resp = await fetch(url, {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify(payload)
            });

            if (!resp.ok) {
                let msgErro = `Erro HTTP ${resp.status}`;
                try {
                    const errorObj = await resp.json();
                    msgErro = errorObj.error || errorObj.mensagem || msgErro;
                } catch (e) {
                    const txt = await resp.text();
                    if (txt) msgErro = txt;
                }
                logNoConsole(CONSOLE_ID, `Falha na operação: ${msgErro}`, 'erro');
                mostrarAlerta(`Erro: ${msgErro}`, 'erro');
                return;
            }

            const contentType = resp.headers.get('content-type');
            if (contentType && contentType.includes('application/json')) {
                const dados = await resp.json();
                if (dados.mensagem) {
                    logNoConsole(CONSOLE_ID, dados.mensagem, 'sucesso');
                }
            }

            if (aguardarFila) {
                const estadoAviso = armarAvisoSonoro();
                logNoConsole(CONSOLE_ID, mensagemDoAviso(estadoAviso),
                    estadoAviso === 'ARMADO' ? 'info' : 'aviso');
                if (await acompanharConclusao()) {
                    logNoConsole(CONSOLE_ID, 'Passada concluída.', 'sucesso');
                    tocarAvisoSonoro();
                } else {
                    logNoConsole(CONSOLE_ID, 'Fim da passada NÃO confirmado — confira o console acima e a pasta de saída antes de seguir.', 'aviso');
                }
            }
        } catch (e) {
            logNoConsole(CONSOLE_ID, `Erro de rede: ${e.message}`, 'erro');
            mostrarAlerta('Erro de conexão ao servidor.', 'erro');
        } finally {
            botoes.forEach(b => { b.disabled = false; });
        }
    }
}
