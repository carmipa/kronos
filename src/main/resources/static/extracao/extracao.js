import { logNoConsole, mensagemDoErro } from '../js/app.js';

export function initExtracao() {
    const form = document.getElementById('form-extracao');
    if (!form) return;

    form.addEventListener('submit', async (e) => {
        e.preventDefault();
        
        const entrada = document.getElementById('extracao-entrada').value.trim();
        const saidaElem = document.getElementById('extracao-saida');
        const saida = saidaElem ? saidaElem.value.trim() : '';
        const formato = document.getElementById('extracao-formato').value;
        
        logNoConsole('console-extracao', `Solicitando extração de legendas no formato [${formato}]...`, 'info');
        logNoConsole('console-extracao', `Diretório: ${entrada}`, 'info');
        if (saida) logNoConsole('console-extracao', `Pasta de Saída Customizada: ${saida}`, 'info');

        try {
            const reqBody = { entrada, formato };
            if (saida) reqBody.saida = saida;

            const res = await fetch('/api/extrair', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify(reqBody)
            });

            if (!res.ok) {
                const erroTexto = await res.text();
                throw new Error(mensagemDoErro(erroTexto, 'Erro interno ao iniciar extração'));
            }

            const data = await res.json();
            logNoConsole('console-extracao', 'Extração de legendas iniciada com sucesso em segundo plano!', 'sucesso');
            if (data.mensagem) {
                logNoConsole('console-extracao', data.mensagem, 'info');
            }

        } catch (err) {
            logNoConsole('console-extracao', `Erro ao iniciar extração: ${err.message}`, 'erro');
        }
    });
}
