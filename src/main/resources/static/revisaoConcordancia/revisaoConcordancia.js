import { logNoConsole, mostrarAlerta } from '../js/app.js';
// O MESMO modulo da Traducao Local e da Revisao de Lore, nao uma copia: invariante 10 do
// projeto. Duas copias do mesmo audio divergiriam no dia em que uma ganhasse ajuste (volume,
// numero de toques) e a outra nao, e o lote errado avisaria diferente. Pedido de Paulo em
// 18/08/2026, quando a 3.3 foi a unica das tres telas da etapa a nascer muda.
import { armarAvisoSonoro, tocarAvisoSonoro, mensagemDoAviso } from '../js/avisoSonoro.js';
// O cartão que mantém à vista PARA ONDE a tela escreve. Módulo compartilhado, na versão que
// escapa o caminho — as três cópias que existiam nas telas irmãs já tinham divergido nesse ponto.
import { ligarCartaoAlvoAtivo } from '../js/cartaoAlvoAtivo.js?v=1.1';

// A versão sobe a cada mudança no HTML do painel (1.1 = seletor de obra; 1.2 = cartão do alvo
// ativo e a caixa "o que acontece"): o painel é buscado por fetch, e sem trocar a query o
// navegador serve o HTML antigo do cache — as partes novas simplesmente não apareceriam.
const PAINEL_HTML = 'revisaoConcordancia/revisaoConcordancia.html?v=1.5';

async function carregarPainelHtml() {
    const painel = document.getElementById('panel-revisao-concordancia');
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
 * PROPÓSITO DE NEGÓCIO: mantém o botão bloqueado enquanto o job está na fila/execução,
 * evitando duplo disparo (o POST só ENFILEIRA; o trabalho roda em segundo plano).
 * INVARIANTES DO DOMÍNIO: só retorna quando a fila reporta "livre".
 * COMPORTAMENTO EM CASO DE FALHA: loga aviso e retorna, liberando o botão.
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
        logNoConsole('console-revisao-concordancia', `Não foi possível acompanhar o estado da fila: ${erro.message}`, 'aviso');
    }
}

/**
 * PROPÓSITO DE NEGÓCIO: liga o formulário ao endpoint que corrige concordância de gênero na
 * pasta PT-BR, respeitando o dry-run.
 * INVARIANTES DO DOMÍNIO: a pasta PT-BR é obrigatória; aplicar = !simular.
 * COMPORTAMENTO EM CASO DE FALHA: exibe o erro no console e reabilita o botão.
 */
function vincularEventos() {
    const btn = document.getElementById('btn-iniciar-revisao-concordancia');
    const input = document.getElementById('revisao-concordancia-entrada');
    const chkSimular = document.getElementById('revisao-concordancia-simular');
    // Seletor AUXILIAR: identifica a obra, traz capa e sinopse e entra no registro do console.
    // Não vai para o servidor e não muda a correção — esta tela é determinística e não usa lore
    // (escopo fechado por Paulo em 18/08/2026). Prometer efeito que o motor não tem seria pior
    // que não ter o combo.
    const selectContexto = document.getElementById('revisao-concordancia-contexto');
    if (!btn || !input) return;

    btn.addEventListener('click', async () => {
        const diretorioTraduzido = input.value.trim();
        if (!diretorioTraduzido) {
            mostrarAlerta('Informe a pasta com as legendas traduzidas (PT-BR)!', 'erro');
            return;
        }
        const aplicar = chkSimular ? !chkSimular.checked : true;

        // Este clique É o gesto que libera o áudio no navegador — um AudioContext criado fora de
        // um gesto do usuário nasce 'suspended' e não emite som, sem erro e sem log. O estado é
        // DITO na hora, em três valores: quem vai sair de perto precisa saber ANTES se pode
        // confiar no som, e não depois de perder o fim do trabalho.
        const estadoAviso = armarAvisoSonoro();
        logNoConsole('console-revisao-concordancia', mensagemDoAviso(estadoAviso),
            estadoAviso === 'armado' ? 'info' : 'aviso');

        const obra = selectContexto?.options[selectContexto.selectedIndex]?.text?.trim();
        const obraNoRegistro = obra && !obra.startsWith('--') && !obra.startsWith('—')
            ? obra : 'não informada';

        logNoConsole('console-revisao-concordancia',
            `Iniciando revisão de concordância — Obra: ${obraNoRegistro}`, 'info');
        logNoConsole('console-revisao-concordancia',
            `${diretorioTraduzido} | ${aplicar ? 'APLICAR' : 'simular (dry-run)'}`, 'info');
        btn.disabled = true;

        try {
            const res = await fetch('/api/revisar-concordancia', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ diretorioTraduzido, aplicar })
            });
            const data = await res.json().catch(() => ({}));
            if (!res.ok) {
                throw new Error(data.erro || 'Falha ao iniciar revisão de concordância');
            }
            logNoConsole('console-revisao-concordancia', data.mensagem || 'Revisão de concordância iniciada.', 'sucesso');
            mostrarAlerta('Revisão de concordância iniciada! Acompanhe os logs.', 'sucesso');

            await acompanharConclusao();
            // O alerta VISUAL vem primeiro e sempre: som depende de permissão, volume e de a aba
            // não estar no mudo. Se o som fosse a única rede, "terminou" ficaria indistinguível
            // de "ainda rodando" justamente quando o navegador recusa.
            mostrarAlerta('Revisão de concordância finalizada. Confira o status no console.', 'info');
            tocarAvisoSonoro();
        } catch (err) {
            logNoConsole('console-revisao-concordancia', `Erro: ${err.message}`, 'erro');
            mostrarAlerta(err.message, 'erro');
        } finally {
            btn.disabled = false;
        }
    });
}

export async function initRevisaoConcordancia() {
    try {
        await carregarPainelHtml();
        vincularEventos();
        // "Pasta que será reescrita" e não "Pasta": esta tela grava por cima do arquivo do
        // Paulo, e o rótulo é o que separa uma escolha consciente de um clique distraído.
        const repintarAlvo = ligarCartaoAlvoAtivo({
            alvoTextoId: 'revisao-concordancia-alvo-texto',
            selectId: 'revisao-concordancia-contexto',
            pastaId: 'revisao-concordancia-entrada',
            rotuloObra: 'Obra',
            rotuloPasta: 'Pasta que será reescrita',
            semEscolha: 'Escolha a obra acima — ou "Sem obra" — para liberar os campos.'
        });
        // O "Limpar Campos" compartilhado grava o valor direto, sem disparar evento: sem este
        // ouvinte o cartão continuaria mostrando a pasta que o operador acabou de apagar.
        document.querySelector('#panel-revisao-concordancia .btn-clear-form')
            ?.addEventListener('click', () => setTimeout(() => repintarAlvo?.(), 0));
        // O painel é injetado DEPOIS do boot do app.js, então o seletor de obra nasce vazio se
        // ninguém avisar. É o mesmo defeito que a Tradução de Karaokê teve: o combo ficava sem
        // opção nenhuma e o operador achava que a tela estava quebrada.
        document.dispatchEvent(new CustomEvent('revisao-concordancia:painel-carregado'));
    } catch (err) {
        console.error('[Revisão de Concordância] Erro ao carregar painel:', err);
        const painel = document.getElementById('panel-revisao-concordancia');
        if (painel) {
            painel.innerHTML = '<div class="glass-card"><p class="card-desc">Não foi possível carregar o painel de Revisão de Concordância.</p></div>';
        }
    }
}
