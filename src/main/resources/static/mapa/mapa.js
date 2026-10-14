import { mensagemDoErro } from '../js/app.js';

export function initMapa() {
    const btnGerar = document.getElementById('btn-gerar-mapa');
    const btnExportar = document.getElementById('btn-exportar-mapa');
    const btnArvoreGithub = document.getElementById('btn-exportar-arvore-github');
    const viewer = document.getElementById('viewer-mapa');

    // Guarda o que a API retornou para exportar exatamente igual.
    let ultimoMapa = '';
    let ultimaArvoreGithub = '';
    let nomeProjeto = 'projeto';

    const habilitarExportacoes = (habilitar) => {
        if (btnExportar) btnExportar.disabled = !habilitar;
        if (btnArvoreGithub) btnArvoreGithub.disabled = !habilitar;
    };

    // Sanitiza o nome do projeto para uso seguro em nome de arquivo.
    const nomeArquivoSeguro = (base) => (base || 'projeto').replace(/[^a-zA-Z0-9._-]+/g, '_');

    const baixar = (conteudo, nomeArquivo, mime) => {
        if (!conteudo || !conteudo.trim()) return;
        const blob = new Blob([conteudo], { type: mime });
        const url = URL.createObjectURL(blob);
        const a = document.createElement('a');
        a.href = url;
        a.download = nomeArquivo;
        document.body.appendChild(a);
        a.click();
        document.body.removeChild(a);
        URL.revokeObjectURL(url);
    };

    habilitarExportacoes(false);

    if (btnGerar) {
        btnGerar.addEventListener('click', async () => {
            btnGerar.disabled = true;
            if (viewer) viewer.textContent = 'Mapeando a estrutura completa do projeto. Por favor, aguarde...';

            try {
                const res = await fetch('/api/mapa', {
                    method: 'POST',
                    headers: { 'Content-Type': 'application/json' }
                });

                if (!res.ok) {
                    const erro = await res.text();
                    throw new Error(mensagemDoErro(erro, 'Erro ao gerar o mapa'));
                }

                const data = await res.json();
                ultimoMapa = data.conteudo || '';
                ultimaArvoreGithub = data.arvoreGithub || '';
                nomeProjeto = data.nomeProjeto || 'projeto';
                if (viewer) viewer.textContent = ultimoMapa || 'Nenhum mapa retornado.';
                habilitarExportacoes(ultimoMapa.trim().length > 0);
            } catch (err) {
                ultimoMapa = '';
                ultimaArvoreGithub = '';
                if (viewer) viewer.textContent = `Erro ao gerar mapa: ${err.message}`;
                habilitarExportacoes(false);
            } finally {
                btnGerar.disabled = false;
            }
        });
    }

    if (btnExportar) {
        btnExportar.addEventListener('click', () => {
            const conteudo = ultimoMapa || (viewer ? viewer.textContent : '');
            baixar(conteudo, `${nomeArquivoSeguro(nomeProjeto)}_mapa_projeto.txt`, 'text/plain;charset=utf-8');
        });
    }

    if (btnArvoreGithub) {
        btnArvoreGithub.addEventListener('click', () => {
            baixar(ultimaArvoreGithub, `${nomeArquivoSeguro(nomeProjeto)}_estrutura_github.md`, 'text/markdown;charset=utf-8');
        });
    }
}
