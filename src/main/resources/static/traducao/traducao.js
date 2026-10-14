import { logNoConsole, mostrarAlerta } from '../js/app.js';
import { montarOpcoesContextos } from '../js/selectContextos.js';
import { travarAteEscolherLore } from '../js/travaLore.js';
// O aviso sonoro mora em js/avisoSonoro.js desde 17/08/2026, quando a Revisao de Lore passou a
// usa-lo tambem: componente repetido vira compartilhado (invariante 10), e duas copias do mesmo
// audio divergiriam no dia em que uma ganhasse ajuste e a outra nao.
import { armarAvisoSonoro, tocarAvisoSonoro, mensagemDoAviso } from '../js/avisoSonoro.js';

let contextosCarregados = false;

/**
 * PROPÓSITO DE NEGÓCIO: prepara o formulário da tradução local e envia a decisão
 * explícita de Paulo sobre manter ou liberar a proteção de arquivos PT-BR existentes.
 *
 * INVARIANTES DO DOMÍNIO: a proteção começa sempre ativada; a liberação só é enviada
 * quando a caixa correspondente estiver marcada no momento da submissão.
 *
 * COMPORTAMENTO EM CASO DE FALHA: valida contexto e resposta HTTP, registra a falha
 * no console da interface e não inicia o acompanhamento de uma operação rejeitada.
 */
export function initTraducao() {
    const form = document.getElementById('form-traducao');
    carregarContextos();

    // Recarregar a página no meio do lote perde o gesto que liberou o áudio, e o operador não
    // tem como saber disso. Este ouvinte devolve o aviso no primeiro clique em qualquer lugar.
    document.addEventListener('click', armarAvisoSonoro, { once: true });

    // Registrado no init, que roda UMA vez no boot (app.js). Registrar por abertura de aba
    // empilharia ouvintes e o aviso tocaria uma vez por visita à tela.
    window.addEventListener('kronos:traducao-finalizada', (evento) => {
        // A rota /api/traduzir serve as telas 2.1 e 2.2; cada uma avisa a sua.
        if (evento.detail?.canal !== 'traducao') return;

        // Corpo: "<desfecho>|<veredito do som>" — ver TraducaoController#SEPARADOR_EVENTO.
        const [desfecho = 'ENCERRADO', somDaMaquina = ''] = String(evento.detail.dados || '').split('|');
        const tipo = desfecho.startsWith('CONCLU') && !desfecho.includes('FALHA') ? 'sucesso' : 'aviso';

        // O alerta VISUAL vem primeiro e sempre: som depende de permissão, volume e de a aba
        // não estar no mudo. Se o aviso sonoro fosse a única rede, o lote terminaria em
        // silêncio absoluto nos casos em que o navegador recusa — e "terminou" ficaria
        // indistinguível de "ainda rodando".
        logNoConsole('console-traducao', `Lote encerrado: ${desfecho}`, tipo);
        mostrarAlerta(`Tradução Local — lote ${desfecho}`, tipo);

        // SEGUNDA VIA, não via paralela: a máquina é quem avisa, e a tela só assume quando ela
        // não conseguiu. Tocar nos dois faria dois avisos sobrepostos no caso normal.
        if (somDaMaquina !== 'TOCOU') {
            tocarAvisoSonoro();
        }
    });

    if (!form) return;

    form.addEventListener('submit', async (e) => {
        e.preventDefault();

        const entrada = document.getElementById('traducao-entrada').value.trim();
        const saida = document.getElementById('traducao-saida').value.trim();
        const contextoSelect = document.getElementById('traducao-contexto');
        const contextoId = contextoSelect ? contextoSelect.value : null;
        const permitirRetraducao = document.getElementById('traducao-liberar-protecao')?.checked === true;

        if (!contextosCarregados) {
            logNoConsole('console-traducao', 'Lista de contextos de tradução ainda não carregou. Aguarde ou recarregue a página antes de iniciar.', 'erro');
            return;
        }
        // Lista carregada e nenhuma obra escolhida é outro estado: mandar recarregar a página
        // aqui faria a pessoa repetir uma ação inútil em vez de só escolher a obra.
        if (!contextoId) {
            logNoConsole('console-traducao', 'Escolha a obra (lore) no seletor antes de iniciar a tradução.', 'erro');
            return;
        }

        logNoConsole('console-traducao', 'Iniciando pipeline de tradução local via LLM...', 'info');
        logNoConsole('console-traducao', `Pasta Original: ${entrada}`, 'info');
        if (saida) logNoConsole('console-traducao', `Pasta de Saída: ${saida}`, 'info');
        if (contextoId) logNoConsole('console-traducao', `Contexto Ativo: ${contextoId}`, 'info');
        logNoConsole('console-traducao', permitirRetraducao
            ? 'Retradução integral: LIBERADA (cache anterior e arquivos PT-BR serão preservados em backup).'
            : 'Proteção de arquivos finais: ATIVADA.', permitirRetraducao ? 'aviso' : 'info');

        // Este submit é o gesto que libera o áudio no navegador. O estado é DITO na hora, em
        // três valores: quem vai sair de perto precisa saber ANTES se pode confiar no som —
        // descobrir que ele não tocaria só depois de perder o fim do lote é o pior desfecho.
        const estadoAviso = armarAvisoSonoro();
        logNoConsole('console-traducao', mensagemDoAviso(estadoAviso),
            estadoAviso === 'armado' ? 'info' : 'aviso');

        try {
            const reqBody = { entrada: entrada, permitirRetraducao: permitirRetraducao };
            if (saida) reqBody.saida = saida;
            if (contextoId) reqBody.contextoId = contextoId;

            const res = await fetch('/api/traduzir', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify(reqBody)
            });

            if (!res.ok) {
                const erroTexto = await res.text();
                let msg = 'Erro interno ao iniciar tradução';
                try {
                    const parsed = JSON.parse(erroTexto);
                    if (parsed.mensagem) msg = parsed.mensagem;
                } catch(e) {
                    if (erroTexto) msg = erroTexto;
                }
                throw new Error(msg);
            }

            const data = await res.json();
            logNoConsole('console-traducao', 'Tradução iniciada com sucesso em segundo plano!', 'sucesso');
            if (data.mensagem) {
                logNoConsole('console-traducao', data.mensagem, 'info');
            }

            iniciarAcompanhamentoTraducao();

        } catch (err) {
            logNoConsole('console-traducao', `Erro ao iniciar tradução: ${err.message}`, 'erro');
            mostrarAlerta(`Falha: ${err.message}`, 'erro');
        }
    });
}

async function carregarContextos() {
    const select = document.getElementById('traducao-contexto');
    if (!select) return;

    try {
        const response = await fetch('/api/contextos', { cache: 'no-store' });
        if (!response.ok) {
            throw new Error('Resposta HTTP ' + response.status);
        }

        const contextos = await response.json();
        if (!Array.isArray(contextos) || contextos.length === 0) {
            throw new Error('Nenhum contexto de tradução cadastrado no servidor.');
        }

        select.innerHTML = '';
        // Agrupa por franquia (<optgroup>) na ordem já vinda do backend.
        //
        // NÃO pré-selecciona mais o contexto padrão do servidor. O comentário antigo dizia que
        // pré-selecionar evitava "usar a lore errada sem o usuário perceber" — mas trocava um
        // erro por outro: quem não olhasse o combo traduzia TUDO com a lore da obra padrão.
        // Agora o seletor abre num marcador desabilitado e a trava de lore mantém os campos de
        // pasta inertes até a escolha ser feita de propósito.
        const marcador = document.createElement('option');
        marcador.value = '';
        marcador.textContent = '-- Selecione a obra (obrigatório) --';
        marcador.disabled = true;
        marcador.selected = true;
        select.appendChild(marcador);
        montarOpcoesContextos(select, contextos);
        travarAteEscolherLore(select);

        contextosCarregados = true;
    } catch (err) {
        console.error('Erro ao carregar contextos:', err);
        select.innerHTML = '<option value="">Erro ao carregar — recarregue a página</option>';
        contextosCarregados = false;
    }
}

function iniciarAcompanhamentoTraducao() {
    logNoConsole('console-traducao', 'Acompanhando execução do tradutor local...', 'info');
}
