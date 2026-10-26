const PAINEL_HTML = 'sobre/sobre.html?v=3.3';
const GITHUB_USER = 'carmipa';
const GITHUB_REPO = 'carmipa/kronos';

async function carregarPainelHtml() {
    const painel = document.getElementById('panel-sobre');
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

async function atualizarStatsGithub() {
    try {
        const resposta = await fetch(`https://api.github.com/users/${GITHUB_USER}`);
        if (!resposta.ok) return;

        const dados = await resposta.json();
        const repos = document.getElementById('sobre-stat-repos');
        const seguidores = document.getElementById('sobre-stat-followers');
        const seguindo = document.getElementById('sobre-stat-following');
        const avatar = document.getElementById('sobre-avatar');
        const localizacao = document.getElementById('sobre-location');
        const disponibilidade = document.getElementById('sobre-hireable');
        const blog = document.getElementById('sobre-blog');

        if (repos && dados.public_repos != null) repos.textContent = dados.public_repos;
        if (seguidores && dados.followers != null) seguidores.textContent = dados.followers;
        if (seguindo && dados.following != null) seguindo.textContent = dados.following;
        if (avatar && dados.avatar_url) avatar.src = dados.avatar_url;
        if (localizacao && dados.location) localizacao.textContent = dados.location;
        if (disponibilidade) disponibilidade.textContent = dados.hireable ? 'Aberto a oportunidades' : 'Perfil profissional ativo';
        if (blog && dados.blog) {
            const url = dados.blog.startsWith('http') ? dados.blog : `https://${dados.blog}`;
            blog.textContent = dados.blog.replace(/^https?:\/\//, '');
            blog.href = url;
        }
    } catch {
        // Mantém valores estáticos do HTML se a API não responder
    }
}

async function atualizarProjetoGithub() {
    try {
        const resposta = await fetch(`https://api.github.com/repos/${GITHUB_REPO}`);
        if (!resposta.ok) return;

        const dados = await resposta.json();
        const branch = document.getElementById('sobre-repo-branch');
        const linguagem = document.getElementById('sobre-repo-language');

        if (branch && dados.default_branch) branch.textContent = dados.default_branch;
        if (linguagem && dados.language) linguagem.textContent = dados.language;
    } catch {
        // Mantém valores estáticos do HTML se a API não responder
    }
}

export async function initSobre() {
    try {
        await carregarPainelHtml();
        atualizarStatsGithub();
        atualizarProjetoGithub();
    } catch (err) {
        console.error('[Sobre] Erro ao carregar painel:', err);
        const painel = document.getElementById('panel-sobre');
        if (painel) {
            painel.innerHTML = '<div class="glass-card"><p class="card-desc">Não foi possível carregar a página Sobre.</p></div>';
        }
    }
}
