/**
 * ==========================================================================
 * KRONOS CORE — PAINEL INICIAL
 * Mantém o card "LLM de Tradução" em sincronia com o modelo realmente
 * carregado no LM Studio (endpoint GET /api/llm/status), fazendo polling.
 * ==========================================================================
 */

const INTERVALO_POLL_MS = 20000;
const PULSES = ['pulse-green', 'pulse-purple', 'pulse-red', 'pulse-yellow', 'pulse-magenta', 'pulse-cyan'];

/**
 * PROPÓSITO DE NEGÓCIO: os cartões do fluxo abrem a tela de cada etapa; até aqui só o mouse
 * conseguia, porque eram `div` com clique. Com `role="button"` e `tabindex="0"` no HTML, isto faz o
 * Enter e o Espaço acionarem o cartão, como em qualquer botão (WCAG 2.1.1).
 *
 * INVARIANTES DO DOMÍNIO: a ação é a mesma do clique (o `onclick` do cartão); Espaço não rola a
 * página enquanto o cartão está com o foco.
 *
 * COMPORTAMENTO EM CASO DE FALHA: sem cartões no painel, não registra nada.
 */
function ligarTecladoNosCartoes() {
    document.querySelectorAll('#panel-inicio .pipeline-card[role="button"]').forEach((cartao) => {
        cartao.addEventListener('keydown', (evento) => {
            if (evento.key !== 'Enter' && evento.key !== ' ') return;
            evento.preventDefault();
            cartao.click();
        });
    });
}

export function initInicio() {
    ligarTecladoNosCartoes();
    const badge = document.getElementById('llm-status-indicator');
    const valor = document.getElementById('llm-modelo-ativo');
    if (!badge && !valor) return;

    const definirBadge = (texto, pulse) => {
        if (!badge) return;
        badge.classList.remove(...PULSES);
        badge.classList.add(pulse);
        badge.textContent = texto;
    };

    // "mistralai/mistral-nemo-instruct-2407" -> "mistral-nemo-instruct-2407"
    const nomeCurto = (modelo) => {
        if (!modelo) return '';
        const barra = modelo.lastIndexOf('/');
        return barra >= 0 ? modelo.slice(barra + 1) : modelo;
    };

    const definirValor = (texto, titulo) => {
        if (!valor) return;
        valor.textContent = texto;
        valor.title = titulo || texto;
    };

    async function atualizar() {
        try {
            const res = await fetch('/api/llm/status');
            if (!res.ok) throw new Error('HTTP ' + res.status);
            const s = await res.json();

            if (s.online && s.modeloCarregado && s.modelo) {
                definirValor(nomeCurto(s.modelo), s.modelo);
                definirBadge('Conectado', 'pulse-green');
            } else if (s.online) {
                definirValor('Nenhum modelo carregado', s.mensagem);
                definirBadge('Sem modelo', 'pulse-yellow');
            } else {
                definirValor('LM Studio desconectado', s.mensagem);
                definirBadge('Desconectado', 'pulse-red');
            }
        } catch (err) {
            definirValor('Status indisponível', String(err && err.message ? err.message : err));
            definirBadge('Desconectado', 'pulse-red');
        }
    }

    definirBadge('Verificando…', 'pulse-purple');
    atualizar();
    setInterval(atualizar, INTERVALO_POLL_MS);
}
