import { logNoConsole, montarTxtAnaliseMidia, mensagemDoErro } from '../js/app.js';

export function initAnalise() {
    const btnExportar = document.getElementById('btn-exportar-analise');
    if (btnExportar) {
        btnExportar.addEventListener('click', () => {
            const texto = montarTxtAnaliseMidia(window.__ultimaAnaliseMidia);
            if (!texto || !texto.trim()) {
                logNoConsole('console-analise', 'Nenhuma análise para exportar. Rode a análise primeiro.', 'aviso');
                return;
            }
            const blob = new Blob([texto], { type: 'text/plain;charset=utf-8' });
            const url = URL.createObjectURL(blob);
            const a = document.createElement('a');
            a.href = url;
            a.download = 'analise_midia.txt';
            document.body.appendChild(a);
            a.click();
            document.body.removeChild(a);
            URL.revokeObjectURL(url);
        });
    }

    const form = document.getElementById('form-analise');
    if (!form) return;

    form.addEventListener('submit', async (e) => {
        e.preventDefault();
        
        const entrada = document.getElementById('analise-entrada').value.trim();
        const saida = document.getElementById('analise-saida').value.trim();
        
        logNoConsole('console-analise', 'Solicitando auditoria de mídia...', 'info');
        logNoConsole('console-analise', `Diretório: ${entrada}`, 'info');
        if (saida) logNoConsole('console-analise', 'Pasta de saída informada, mas a análise não grava em disco: o resultado fica na tela (exportável em TXT).', 'info');

        try {
            const res = await fetch('/api/analisar', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ entrada, saida })
            });

            if (!res.ok) {
                const erroTexto = await res.text();
                throw new Error(mensagemDoErro(erroTexto, 'Erro interno no servidor'));
            }

            const data = await res.json();
            logNoConsole('console-analise', 'Auditoria de mídia disparada com sucesso em segundo plano!', 'sucesso');
            
            if (data.mensagem) {
                logNoConsole('console-analise', data.mensagem, 'info');
            }

            // Inicia o monitoramento de progresso se necessário
            iniciarMonitoramentoProgresso();

        } catch (err) {
            logNoConsole('console-analise', `Erro ao iniciar auditoria: ${err.message}`, 'erro');
        }
    });
}

function iniciarMonitoramentoProgresso() {
    // Implementação futura do polling de status do analisador
    logNoConsole('console-analise', 'Acompanhando execução do analisador via SSE/Polling...', 'info');
}
