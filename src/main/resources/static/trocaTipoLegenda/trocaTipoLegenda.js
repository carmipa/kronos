import { logNoConsole, mostrarAlerta } from '../js/app.js';

const PAINEL_HTML = 'trocaTipoLegenda/trocaTipoLegenda.html?v=3.6';

function escapeHtml(texto) {
    return String(texto ?? '')
        .replace(/&/g, '&amp;')
        .replace(/</g, '&lt;')
        .replace(/>/g, '&gt;')
        .replace(/"/g, '&quot;')
        .replace(/'/g, '&#39;');
}

async function carregarPainelHtml() {
    const painel = document.getElementById('panel-troca-tipo-legenda');
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
 * Alterna entre as duas ferramentas do menu, que rodam em momentos OPOSTOS do fluxo:
 * troca de fontes antes de traduzir, achatamento de estilos depois.
 *
 * A auditoria de fontes e o card de substituição pertencem à aba 1 — ficam ocultos
 * enquanto a aba 2 está aberta e voltam ao estado anterior no retorno, para que uma
 * tabela de fontes não fique pendurada embaixo do botão de achatar.
 */
function vincularAbas() {
    const abas = Array.from(document.querySelectorAll('#panel-troca-tipo-legenda .troca-aba'));
    if (abas.length === 0) return;

    const cardsDaAbaFontes = () => [
        document.getElementById('area-auditoria-resultado'),
        document.getElementById('card-correcao-desbloqueado')
    ].filter(Boolean);

    // Guarda o que estava visível na aba 1 para restaurar sem "ressuscitar" card fechado.
    let visiveisNaAbaFontes = null;

    const ativar = (nome) => {
        abas.forEach(aba => {
            const ativa = aba.dataset.aba === nome;
            aba.classList.toggle('ativo', ativa);
            aba.setAttribute('aria-selected', String(ativa));
        });
        document.querySelectorAll('#panel-troca-tipo-legenda .troca-aba-painel').forEach(painel => {
            painel.classList.toggle('hidden', painel.id !== `painel-aba-${nome}`);
        });

        if (nome === 'achatador') {
            if (visiveisNaAbaFontes === null) {
                visiveisNaAbaFontes = cardsDaAbaFontes()
                    .filter(card => !card.classList.contains('hidden'))
                    .map(card => card.id);
            }
            cardsDaAbaFontes().forEach(card => card.classList.add('hidden'));
        } else if (visiveisNaAbaFontes !== null) {
            const restaurar = visiveisNaAbaFontes;
            visiveisNaAbaFontes = null;
            cardsDaAbaFontes()
                .filter(card => restaurar.includes(card.id))
                .forEach(card => card.classList.remove('hidden'));
        }
    };

    abas.forEach(aba => aba.addEventListener('click', () => ativar(aba.dataset.aba)));
}

function vincularEventos() {
    const btnEscanear = document.getElementById('btn-escanear-fontes');
    const btnAplicar = document.getElementById('btn-aplicar-substituicoes');
    const btnForcarArial = document.getElementById('btn-forcar-arial');
    const btnAchatar = document.getElementById('btn-achatar-estilos');
    const btnLimpar = document.querySelector('.btn-clear-form[data-form="form-troca-tipo-legenda"]');
    const inputEntrada = document.getElementById('troca-tipo-legenda-entrada');
    
    const areaResultado = document.getElementById('area-auditoria-resultado');
    const cardCorrecao = document.getElementById('card-correcao-desbloqueado');
    const tabelaFontesCorpo = document.querySelector('#tabela-fontes tbody');
    const tituloCorrecaoFontes = document.getElementById('titulo-correcao-fontes');
    const textoCorrecaoFontes = document.getElementById('texto-correcao-fontes');
    
    const badgeTotal = document.getElementById('badge-total-arquivos');
    const badgeProblemas = document.getElementById('badge-arquivos-problemas');
    const btnToggleOk = document.getElementById('btn-toggle-arquivos-ok');
    const avisoSubpastas = document.getElementById('aviso-subpastas-troca');

    if (!btnEscanear || !inputEntrada || !tabelaFontesCorpo) return;

    /** Texto do que ficou nas subpastas (a 1.3 só lê a pasta escolhida); vazio quando não há nada. */
    const textoSubpastas = (n) => {
        if (n > 0) return `${n} legenda(s) em subpastas não entram nesta ferramenta: escolha a subpasta para tratá-las.`;
        if (n < 0) return 'Não deu para conferir se há legendas nas subpastas.';
        return '';
    };

    let ultimoResultadoAuditoria = null;
    let mostrarArquivosOk = false;

    const renderizarTabelaAuditoria = (data) => {
        tabelaFontesCorpo.innerHTML = '';

        if (!data.arquivos || data.arquivos.length === 0) {
            tabelaFontesCorpo.innerHTML = `<tr><td colspan="6" style="text-align:center; color: var(--text-muted);">Nenhum arquivo de legenda nesta pasta.</td></tr>`;
            if (btnToggleOk) btnToggleOk.classList.add('hidden');
            return;
        }

        // Ilegível é um terceiro grupo, sempre visível: antes caía em "OK", escondido atrás de
        // "Mostrar OK", e a tela dizia que nenhuma legenda precisava de alteração.
        const comErro = (arq) => Boolean(arq.erroLeitura);
        const arquivosComErro = data.arquivos.filter(comErro);
        const arquivosComProblemas = data.arquivos.filter(arq =>
            !comErro(arq) && (arq.fontes || []).some(fonteInfo => fonteInfo.problematica)
        );
        const arquivosOk = data.arquivos.filter(arq =>
            !comErro(arq) && !(arq.fontes || []).some(fonteInfo => fonteInfo.problematica)
        );
        const arquivosVisiveis = mostrarArquivosOk
            ? [...arquivosComErro, ...arquivosComProblemas, ...arquivosOk]
            : [...arquivosComErro, ...arquivosComProblemas];

        if (btnToggleOk) {
            if (arquivosOk.length > 0) {
                btnToggleOk.classList.remove('hidden');
                btnToggleOk.textContent = mostrarArquivosOk
                    ? `Ocultar ${arquivosOk.length} OK`
                    : `Mostrar ${arquivosOk.length} OK`;
            } else {
                btnToggleOk.classList.add('hidden');
            }
        }

        if (arquivosVisiveis.length === 0) {
            tabelaFontesCorpo.innerHTML = `
                <tr>
                    <td colspan="6" style="text-align:center; color: var(--accent-green); font-weight: 700;">
                        Nenhuma legenda precisa de alteração. ${arquivosOk.length} arquivo(s) estão OK.
                    </td>
                </tr>
            `;
            return;
        }

        arquivosVisiveis.forEach(arq => {
            const nomeArq = arq.arquivo;
            const nomeArqSeguro = escapeHtml(nomeArq);
            const tipoLegenda = escapeHtml(arq.tipoLegenda || 'ASS/SSA');
            if (comErro(arq)) {
                const trErro = document.createElement('tr');
                trErro.className = 'linha-auditoria-problema';
                trErro.innerHTML = `
                    <td class="td-arquivo-legenda" title="${nomeArqSeguro}"><strong>${nomeArqSeguro}</strong></td>
                    <td><span class="meta-badge">${tipoLegenda}</span></td>
                    <td><strong style="color: var(--accent-red);">Erro de leitura</strong></td>
                    <td><code>${escapeHtml(arq.erroLeitura)}</code></td>
                    <td><span style="color: var(--accent-yellow);">Conferir o arquivo: não abriu como .ass/.ssa</span></td>
                    <td><span style="color: var(--text-muted);">Fica de fora: nenhum botão altera este arquivo</span></td>
                `;
                tabelaFontesCorpo.appendChild(trErro);
                return;
            }
            const fontesProblematicas = (arq.fontes || []).filter(fonteInfo => fonteInfo.problematica);
            const fontesDoArquivo = (arq.fontes || []);
            const temProblema = fontesProblematicas.length > 0;
            const tr = document.createElement('tr');
            if (temProblema) {
                tr.className = 'linha-auditoria-problema';
            }

            const diagnosticoHtml = temProblema
                ? '<span class="status-badge pulse-red">Problema</span>'
                : '<span class="status-badge pulse-green">OK</span>';

            const fontesHtml = temProblema
                ? fontesProblematicas.map(fonteInfo => {
                    const estiloSeguro = escapeHtml(fonteInfo.estilo);
                    const fonteAtualSegura = escapeHtml(fonteInfo.fonteAtual);
                    const fonteSugeridaSegura = escapeHtml(fonteInfo.fonteSugerida);
                    return `<code>${estiloSeguro}: ${fonteAtualSegura} -> ${fonteSugeridaSegura}</code>`;
                }).join('<br>')
                : fontesDoArquivo.map(fonteInfo => {
                    const estiloSeguro = escapeHtml(fonteInfo.estilo);
                    const fonteAtualSegura = escapeHtml(fonteInfo.fonteAtual);
                    return `<code>${estiloSeguro}: ${fonteAtualSegura}</code>`;
                }).join('<br>') || '<span style="color: var(--text-muted);">Sem estilos declarados</span>';

            const acaoHtml = temProblema
                ? '<strong style="color: var(--accent-green);">Substituir por Arial</strong>'
                : '<span style="color: var(--text-muted);">OK automático; Arial manual disponível</span>';

            const decisaoHtml = temProblema
                ? '<span class="status-badge pulse-red">Alterar se aplicar lote</span>'
                : '<span class="status-badge pulse-green">Operador decide</span>';

            tr.innerHTML = `
                <td class="td-arquivo-legenda" title="${nomeArqSeguro}"><strong>${nomeArqSeguro}</strong></td>
                <td><span class="meta-badge">${tipoLegenda}</span></td>
                <td>${diagnosticoHtml}</td>
                <td>${fontesHtml}</td>
                <td>${acaoHtml}</td>
                <td>${decisaoHtml}</td>
            `;
            tabelaFontesCorpo.appendChild(tr);
        });
    };

    if (btnToggleOk) {
        btnToggleOk.addEventListener('click', () => {
            if (!ultimoResultadoAuditoria) return;
            mostrarArquivosOk = !mostrarArquivosOk;
            renderizarTabelaAuditoria(ultimoResultadoAuditoria);
        });
    }

    // Ação do Botão: Escanear Fontes
    btnEscanear.addEventListener('click', async () => {
        const caminho = inputEntrada.value.trim();
        if (!caminho) {
            mostrarAlerta('Informe a pasta com as legendas a serem auditadas!', 'erro');
            return;
        }

        logNoConsole('console-troca-tipo-legenda', `Iniciando escaneamento de fontes em: ${caminho}`, 'info');
        btnEscanear.disabled = true;

        // Oculta cards antigos
        areaResultado.classList.add('hidden');
        cardCorrecao.classList.add('hidden');
        if (btnAplicar) btnAplicar.disabled = false;
        if (btnForcarArial) btnForcarArial.disabled = false;
        if (btnToggleOk) {
            btnToggleOk.classList.add('hidden');
        }
        if (avisoSubpastas) {
            avisoSubpastas.classList.add('hidden');
            avisoSubpastas.textContent = '';
        }
        ultimoResultadoAuditoria = null;
        mostrarArquivosOk = false;
        tabelaFontesCorpo.innerHTML = '';

        try {
            const res = await fetch('/api/troca-legenda/escanear', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ diretorioLegendas: caminho })
            });

            const data = await res.json().catch(() => ({}));

            if (!res.ok) {
                throw new Error(data.erro || 'Falha ao realizar auditoria de fontes');
            }

            // Exibir a área de resultados
            areaResultado.classList.remove('hidden');
            const totalComErro = data.totalComErroLeitura || 0;
            badgeTotal.textContent = `${data.totalArquivosAnalisados} arquivos`;
            badgeProblemas.textContent = `${data.totalComProblemas} com problemas`
                + (totalComErro > 0 ? ` · ${totalComErro} ilegível(is)` : '');

            if (data.totalComProblemas > 0 || totalComErro > 0) {
                badgeProblemas.className = 'meta-badge score'; // Destaca em vermelho/rosa
            } else {
                badgeProblemas.className = 'meta-badge genre'; // Destaca em verde
            }

            const avisoDasSubpastas = textoSubpastas(data.legendasEmSubpastas ?? 0);
            if (avisoSubpastas && avisoDasSubpastas) {
                avisoSubpastas.textContent = avisoDasSubpastas;
                avisoSubpastas.classList.remove('hidden');
            }

            ultimoResultadoAuditoria = data;
            renderizarTabelaAuditoria(data);

            const temProblemas = data.totalComProblemas > 0;
            const temArquivos = data.totalArquivosAnalisados > 0;

            if (temArquivos) {
                cardCorrecao.classList.remove('hidden');
                if (btnAplicar) btnAplicar.disabled = !temProblemas;
                if (btnForcarArial) btnForcarArial.disabled = false;
                if (tituloCorrecaoFontes) {
                    tituloCorrecaoFontes.textContent = temProblemas
                        ? 'Fontes Legacy/ANSI Detectadas'
                        : 'Normalização Manual Disponível';
                }
                if (textoCorrecaoFontes) {
                    textoCorrecaoFontes.textContent = temProblemas
                        ? 'Alto risco de renderização incorreta de acentos PT-BR. Você pode aplicar a correção automática ou forçar Arial em todos os estilos.'
                        : 'A auditoria automática não encontrou problema obrigatório, mas você pode trocar os Fontname dos estilos para Arial se a legenda estiver ruim na TV.';
                }
            }

            if (totalComErro > 0) {
                logNoConsole('console-troca-tipo-legenda', `${totalComErro} arquivo(s) não abriram como .ass/.ssa e ficam de fora; o motivo está na tabela.`, 'aviso');
            }
            if (avisoDasSubpastas) {
                logNoConsole('console-troca-tipo-legenda', avisoDasSubpastas, 'aviso');
            }
            if (!temArquivos) {
                cardCorrecao.classList.add('hidden');
                logNoConsole('console-troca-tipo-legenda', 'Auditoria concluída: nenhuma legenda .ass/.ssa nesta pasta.', 'aviso');
                mostrarAlerta('Nenhuma legenda nesta pasta.' + (avisoDasSubpastas ? ' ' + avisoDasSubpastas : ''), 'aviso');
            } else if (temProblemas) {
                logNoConsole('console-troca-tipo-legenda', `Auditoria concluída: ${data.totalComProblemas} de ${data.totalArquivosAnalisados} arquivos possuem fontes vietnamitas legadas de alto risco. Área de substituição liberada!`, 'aviso');
                mostrarAlerta('Auditoria concluída! Fontes legadas problemáticas foram detectadas.', 'aviso');
            } else if (totalComErro > 0) {
                logNoConsole('console-troca-tipo-legenda', `Auditoria concluída: nenhuma fonte antiga nos arquivos lidos, mas ${totalComErro} não puderam ser lidos.`, 'aviso');
                mostrarAlerta(`${totalComErro} arquivo(s) não puderam ser lidos — veja a tabela.`, 'aviso');
            } else {
                logNoConsole('console-troca-tipo-legenda', `Auditoria concluída: ${data.totalArquivosAnalisados} arquivo(s) analisado(s). Normalização manual para Arial disponível.`, 'sucesso');
                mostrarAlerta('Auditoria concluída. Se quiser, você pode normalizar as fontes para Arial.', 'sucesso');
            }

        } catch (err) {
            logNoConsole('console-troca-tipo-legenda', `Erro: ${err.message}`, 'erro');
            mostrarAlerta(err.message, 'erro');
        } finally {
            btnEscanear.disabled = false;
        }
    });

    const aplicarTrocaFontes = async (forcarArial) => {
            const caminho = inputEntrada.value.trim();
            if (!caminho) return;
            if (forcarArial) {
                // O texto antigo dizia "somente o cabeçalho" e "não altera as falas": a operação também
                // troca o \fn de fonte antiga dentro das falas e grava no próprio arquivo.
                const ok = window.confirm(
                    'Forçar Arial GRAVA NO PRÓPRIO ARQUIVO cada .ass/.ssa desta pasta (as subpastas não entram).\n\n'
                    + 'O Fontname de todos os estilos vira Arial e, nas falas e comentários, o \\fn de fonte '
                    + 'vietnamita antiga vira \\fnArial. Não achata estilos, não remove efeitos e não muda o '
                    + 'texto visível nem os tempos.\n\n'
                    + 'Antes de gravar, cada arquivo alterado é copiado para backups/troca_tipo_legenda_<data>/.\n\n'
                    + 'Continuar?');
                if (!ok) return;
            }

            logNoConsole('console-troca-tipo-legenda',
                forcarArial
                    ? 'Solicitando normalização manual de fontes para Arial no pipeline...'
                    : 'Solicitando substituição de fontes em lote no pipeline...',
                'info');
            if (forcarArial && btnForcarArial) btnForcarArial.disabled = true;
            if (!forcarArial && btnAplicar) btnAplicar.disabled = true;

            try {
                const res = await fetch('/api/troca-legenda/aplicar', {
                    method: 'POST',
                    headers: { 'Content-Type': 'application/json' },
                    body: JSON.stringify({ diretorioLegendas: caminho, forcarArial })
                });

                const data = await res.json().catch(() => ({}));

                if (!res.ok) {
                    throw new Error(data.erro || 'Falha ao iniciar substituição de fontes');
                }

                logNoConsole('console-troca-tipo-legenda', data.mensagem || 'Substituição iniciada.', 'sucesso');
                mostrarAlerta(
                    forcarArial
                        ? 'Normalização para Arial iniciada! Acompanhe os logs.'
                        : 'Processo de substituição de fontes iniciado! Acompanhe os logs.',
                    'sucesso');

                // Oculta a área de substituição para evitar duplo clique
                cardCorrecao.classList.add('hidden');
            } catch (err) {
                logNoConsole('console-troca-tipo-legenda', `Erro: ${err.message}`, 'erro');
                mostrarAlerta(err.message, 'erro');
                if (forcarArial && btnForcarArial) btnForcarArial.disabled = false;
                if (!forcarArial && btnAplicar) btnAplicar.disabled = false;
            }
    };

    // Ação do Botão: Aplicar Substituições
    if (btnAplicar) {
        btnAplicar.addEventListener('click', () => aplicarTrocaFontes(false));
    }

    if (btnForcarArial) {
        btnForcarArial.addEventListener('click', () => aplicarTrocaFontes(true));
    }

    // Ação do Botão: Achatar Estilos Decorativos (OP/ED/Sign -> Default)
    if (btnAchatar) {
        btnAchatar.addEventListener('click', async () => {
            const caminho = inputEntrada.value.trim();
            if (!caminho) {
                mostrarAlerta('Informe a pasta com as legendas a serem achatadas!', 'erro');
                return;
            }
            const ok = window.confirm(
                'Achatar estilos decorativos grava os .ass NO LOCAL (com backup automático em backups/).\n\n'
                + 'Aberturas/encerramentos e placas de fonte diferente do Default viram legenda branca simples '
                + '(sem posição/fade). Diálogo comum e o Karaokê Simples não são tocados.\n\nContinuar?');
            if (!ok) return;

            logNoConsole('console-troca-tipo-legenda', `Solicitando achatamento de estilos decorativos em: ${caminho}`, 'info');
            btnAchatar.disabled = true;
            try {
                const res = await fetch('/api/troca-legenda/achatar-estilos', {
                    method: 'POST',
                    headers: { 'Content-Type': 'application/json' },
                    body: JSON.stringify({ diretorioLegendas: caminho })
                });
                const data = await res.json().catch(() => ({}));
                if (!res.ok) {
                    throw new Error(data.erro || 'Falha ao iniciar o achatamento de estilos');
                }
                logNoConsole('console-troca-tipo-legenda', data.mensagem || 'Achatamento iniciado.', 'sucesso');
                mostrarAlerta('Achatamento de estilos decorativos iniciado! Acompanhe os logs.', 'sucesso');
            } catch (err) {
                logNoConsole('console-troca-tipo-legenda', `Erro: ${err.message}`, 'erro');
                mostrarAlerta(err.message, 'erro');
            } finally {
                btnAchatar.disabled = false;
            }
        });
    }

    // Ação de Limpeza do Formulário
    if (btnLimpar) {
        btnLimpar.addEventListener('click', () => {
            areaResultado.classList.add('hidden');
            cardCorrecao.classList.add('hidden');
            tabelaFontesCorpo.innerHTML = '';
            ultimoResultadoAuditoria = null;
            mostrarArquivosOk = false;
            if (btnToggleOk) {
                btnToggleOk.classList.add('hidden');
            }
            logNoConsole('console-troca-tipo-legenda', 'Formulário e resultados limpos.', 'info');
        });
    }
}

export async function initTrocaTipoLegenda() {
    try {
        await carregarPainelHtml();
        vincularAbas();
        vincularEventos();
        document.dispatchEvent(new CustomEvent('troca-tipo-legenda:painel-carregado'));
    } catch (err) {
        console.error('[Menu Troca Tipo Legenda] Erro ao inicializar módulo:', err);
        const painel = document.getElementById('panel-troca-tipo-legenda');
        if (painel) {
            painel.innerHTML = '<div class="glass-card"><p class="card-desc">Não foi possível carregar a interface de Troca de Tipo de Legenda.</p></div>';
        }
    }
}
