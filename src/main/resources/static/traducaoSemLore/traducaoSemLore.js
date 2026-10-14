import { logNoConsole, mostrarAlerta } from '../js/app.js';

const CONSOLE = 'console-traducao-sem-lore';
/** Id reservado do contexto genérico — espelha ProvedorContexto.ID_SEM_LORE no backend. */
const CONTEXTO_SEM_LORE = 'sem_lore';

/**
 * PROPÓSITO DE NEGÓCIO: dispara a tradução CRUA de um anime que ainda não tem lore
 * declarada — o caso "acabei de baixar e quero ver como fica". É a única tela que traduz
 * sem escolher obra, e por isso é a única que NÃO tem seletor de lore nem trava.
 *
 * INVARIANTES DO DOMÍNIO: envia sempre `contextoId: 'sem_lore'`; a saída fica em
 * `traducao_ptbr_sem_lore` (resolvida no servidor quando o campo vai vazio), separada da
 * tradução definitiva. A caixa "ignorar lore existente" só é enviada quando marcada — sem
 * ela, o servidor BLOQUEIA pasta de obra que já tem lore, que é o comportamento desejado.
 *
 * COMPORTAMENTO EM CASO DE FALHA: erro de rede ou HTTP é registrado no console da tela e
 * mostrado em alerta; nada é reenviado automaticamente.
 */
export function initTraducaoSemLore() {
    const form = document.getElementById('form-traducao-sem-lore');
    if (!form) return;

    form.addEventListener('submit', async (e) => {
        e.preventDefault();

        const entrada = document.getElementById('traducao-sem-lore-entrada').value.trim();
        const saida = document.getElementById('traducao-sem-lore-saida').value.trim();
        const ignorarLoreExistente = document.getElementById('traducao-sem-lore-ignorar')?.checked === true;

        if (!entrada) {
            logNoConsole(CONSOLE, 'Informe a pasta com as legendas originais.', 'erro');
            return;
        }

        logNoConsole(CONSOLE, 'Iniciando tradução SEM LORE (crua)...', 'aviso');
        logNoConsole(CONSOLE, `Pasta Original: ${entrada}`, 'info');
        logNoConsole(CONSOLE, saida
            ? `Pasta de Saída: ${saida}`
            : 'Pasta de Saída: traducao_ptbr_sem_lore (criada ao lado das originais)', 'info');
        logNoConsole(CONSOLE,
            'Sem terminologia de obra: nomes próprios podem ser traduzidos pelo modelo.', 'aviso');
        if (ignorarLoreExistente) {
            logNoConsole(CONSOLE,
                'Portão de obra DISPENSADO: se esta obra tiver lore declarada, ela NÃO será aplicada.', 'aviso');
        }

        try {
            const reqBody = { entrada: entrada, contextoId: CONTEXTO_SEM_LORE };
            if (saida) reqBody.saida = saida;
            if (ignorarLoreExistente) reqBody.ignorarLoreExistente = true;

            const res = await fetch('/api/traduzir', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify(reqBody)
            });

            if (!res.ok) {
                const erroTexto = await res.text();
                let msg = 'Erro interno ao iniciar tradução sem lore';
                try {
                    const parsed = JSON.parse(erroTexto);
                    if (parsed.mensagem) msg = parsed.mensagem;
                } catch (ignorado) {
                    if (erroTexto) msg = erroTexto;
                }
                throw new Error(msg);
            }

            const data = await res.json();
            logNoConsole(CONSOLE, 'Tradução sem lore iniciada em segundo plano.', 'sucesso');
            if (data.mensagem) {
                logNoConsole(CONSOLE, data.mensagem, 'info');
            }
        } catch (err) {
            logNoConsole(CONSOLE, `Erro ao iniciar tradução sem lore: ${err.message}`, 'erro');
            mostrarAlerta(`Falha: ${err.message}`, 'erro');
        }
    });
}
