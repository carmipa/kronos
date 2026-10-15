import { mostrarAlerta, logNoConsole } from '../js/app.js';

const PAINEL_HTML = 'renomearArquivos/renomearArquivos.html?v=3.3';

async function carregarPainelHtml() {
    const painel = document.getElementById('panel-renomear-arquivos');
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

export async function initRenomearArquivos() {
    try {
        await carregarPainelHtml();
        vincularEventos();
        document.dispatchEvent(new CustomEvent('renomear-arquivos:painel-carregado'));
    } catch (err) {
        console.error('[Renomear Arquivos] Erro ao carregar painel:', err);
        const painel = document.getElementById('panel-renomear-arquivos');
        if (painel) {
            painel.innerHTML = '<div class="glass-card"><p class="card-desc">Não foi possível carregar o painel de Renomear Arquivos.</p></div>';
        }
    }
}

/**
 * PROPÓSITO DE NEGÓCIO: conecta os controles da opção 13 ao backend e mantém
 * nome/temporada coerentes com a obra selecionada.
 * INVARIANTES DO DOMÍNIO: somente um envio iniciado pela tela permanece ativo;
 * pasta, padrão e temporada são validados antes da requisição.
 * COMPORTAMENTO EM CASO DE FALHA: ausência do formulário encerra sem efeito e
 * erros HTTP são exibidos no console e no alerta.
 */
function vincularEventos() {
    const form = document.getElementById('form-renomear-arquivos');
    if (!form) return;

    const btnSimular = document.getElementById('btn-limpanome-simular');
    const btnAplicar = document.getElementById('btn-limpanome-aplicar');
    const btnReverter = document.getElementById('btn-limpanome-reverter');
    const selectContexto = document.getElementById('renomear-arquivos-contexto');
    const inputPadrao = document.getElementById('limpanome-padrao');
    const inputTemporada = document.getElementById('limpanome-temporada');
    const consoleId = 'console-renomear-arquivos';

    // Ao mudar o select, preencher automaticamente o nome padrão
    if (selectContexto && inputPadrao) {
        selectContexto.addEventListener('change', () => {
            const optText = selectContexto.options[selectContexto.selectedIndex]?.text;
            if (optText && !optText.includes('Carregando') && !optText.includes('Selecione')) {
                const limpo = optText.replace(/\s*-\s*Revis[aã]o\s+de\s+Lore\s*$/i, '')
                                     .replace(/\s+Revis[aã]o\s+de\s+Lore\s*$/i, '')
                                     .trim();
                inputPadrao.value = limpo;
                if (inputTemporada) {
                    inputTemporada.value = String(inferirTemporada(limpo));
                }
            }
        });
    }

    if (btnSimular) {
        btnSimular.addEventListener('click', async () => {
            if (!validarForm()) return;
            await executarOperacao('/api/renomear-arquivos/simular', 'Simulação de Renomeação (Dry-Run)');
        });
    }

    if (btnAplicar) {
        btnAplicar.addEventListener('click', async (e) => {
            e.preventDefault();
            if (!validarForm()) return;
            await executarOperacao('/api/renomear-arquivos/aplicar', 'Aplicar Renomeação');
        });
    }

    if (btnReverter) {
        btnReverter.addEventListener('click', async () => {
            const entrada = document.getElementById('limpanome-entrada').value.trim();
            if (!entrada) {
                mostrarAlerta('Preencha a pasta dos arquivos para buscar o backup de reversão.', 'aviso');
                return;
            }
            if (confirm('Tem certeza que deseja reverter a última renomeação nesta pasta?')) {
                await executarOperacao('/api/renomear-arquivos/reverter', 'Reverter Renomeação');
            }
        });
    }

    function validarForm() {
        const entrada = document.getElementById('limpanome-entrada').value.trim();
        const padrao = document.getElementById('limpanome-padrao').value.trim();
        const temporada = Number.parseInt(document.getElementById('limpanome-temporada')?.value, 10);
        
        if (!entrada || !padrao || !Number.isInteger(temporada) || temporada < 1 || temporada > 99) {
            mostrarAlerta('Preencha Pasta, Nome Padrão e uma Temporada entre 1 e 99.', 'aviso');
            return false;
        }
        return true;
    }

    /**
     * PROPÓSITO DE NEGÓCIO: envia uma operação e mantém os botões bloqueados até
     * o backend devolver a conclusão real do lote.
     * INVARIANTES DO DOMÍNIO: a temporada enviada é inteira e a resposta HTTP é
     * consumida uma única vez.
     * COMPORTAMENTO EM CASO DE FALHA: erro estruturado ou texto simples é exibido
     * sem ser confundido com falha de rede.
     */
    async function executarOperacao(url, descricao) {
        const entrada = document.getElementById('limpanome-entrada').value.trim();
        const padrao = document.getElementById('limpanome-padrao').value.trim();
        const temporada = Number.parseInt(document.getElementById('limpanome-temporada').value, 10);

        logNoConsole(consoleId, `Iniciando ${descricao} em: ${entrada}`, 'info');

        // Desabilita botões
        const botoes = form.querySelectorAll('button');
        botoes.forEach(b => b.disabled = true);

        try {
            const resp = await fetch(url, {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({
                    caminhoOrigem: entrada,
                    nomePadrao: padrao,
                    temporada
                })
            });

            // SSE capturará os logs visuais caso a api lance eventos no backend,
            // mas nós podemos imprimir a resposta também caso retorne um body curto
            if (resp.ok) {
                const contentType = resp.headers.get("content-type");
                if (contentType && contentType.includes("application/json")) {
                    const dados = await resp.json();
                    if (dados.mensagem) {
                        const tipo = dados.status === 'FALHOU' || dados.status === 'CONCLUIDO_COM_FALHAS'
                            ? 'erro'
                            : dados.status === 'CONCLUIDO_COM_PENDENCIAS' ? 'aviso' : 'sucesso';
                        logNoConsole(consoleId, dados.mensagem, tipo);
                        mostrarAlerta(dados.mensagem, tipo);
                    }
                }
            } else {
                let msgErro = `Erro HTTP ${resp.status}`;
                const corpo = await resp.text();
                if (corpo) {
                    try {
                        const errorObj = JSON.parse(corpo);
                        msgErro = errorObj.error || msgErro;
                    } catch (e) {
                        msgErro = corpo;
                    }
                }
                logNoConsole(consoleId, `Falha na operação: ${msgErro}`, 'erro');
                mostrarAlerta(`Erro: ${msgErro}`, 'erro');
            }
        } catch (e) {
            logNoConsole(consoleId, `Erro de rede: ${e.message}`, 'erro');
            mostrarAlerta('Erro de conexão ao servidor.', 'erro');
        } finally {
            botoes.forEach(b => b.disabled = false);
        }
    }
}

/**
 * PROPÓSITO DE NEGÓCIO: extrai a temporada do título/contexto para evitar que
 * Season 4 seja renomeada como S01.
 * INVARIANTES DO DOMÍNIO: o retorno fica entre 1 e 99.
 * COMPORTAMENTO EM CASO DE FALHA: título sem marcador ou número inválido usa 1,
 * que pode ser alterado manualmente antes da operação.
 */
function inferirTemporada(nome) {
    const correspondencia = String(nome || '').match(/\b(?:season|temporada|temp(?:orada)?|s)\s*[-_. ]?(\d{1,2})\b/i);
    const numero = correspondencia ? Number.parseInt(correspondencia[1], 10) : 1;
    return Number.isInteger(numero) && numero >= 1 && numero <= 99 ? numero : 1;
}
