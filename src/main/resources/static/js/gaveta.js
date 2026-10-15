/**
 * GAVETA DO MENU — o menu lateral em tela estreita (celular e tablet em pé).
 *
 * PROPÓSITO DE NEGÓCIO: até 992 px o menu deixava de ser lateral e virava um bloco de mais de
 * 1.100 px empilhado ANTES do painel — quem tocava num item continuava vendo o menu e não o
 * painel que acabara de abrir. Aqui o menu vira uma gaveta: o botão "Menu" da barra do topo a
 * abre por cima do conteúdo, e escolher uma tela a fecha mostrando o painel. Acima de 992 px nada
 * disto age: o menu lateral do computador fica como sempre foi.
 *
 * INVARIANTES DO DOMÍNIO:
 *  - a gaveta só existe em tela estreita; ao alargar a janela com ela aberta, ela fecha e o
 *    conteúdo volta a responder (nada fica `inert` para sempre);
 *  - aberta, só ela recebe foco e toque: o conteúdo atrás fica `inert`;
 *  - Esc com uma dica (tooltip) aberta dentro da gaveta fecha só a dica; o Esc seguinte fecha a
 *    gaveta (a dica tem de poder ser dispensada sem mover o foco);
 *  - a gaveta se move por `left`, nunca por `transform`: `transform` num ancestral muda a
 *    referência do `position: fixed` e o tooltip canônico abriria fora da tela;
 *  - abrir o painel pelo endereço (`/#telemetria`) com a gaveta fechada não mexe no foco.
 *
 * COMPORTAMENTO EM CASO DE FALHA: sem o botão, a gaveta ou a sombra na página, não liga nada e
 * devolve `false` — o menu continua acessível pelo CSS de tela estreita, só sem a gaveta.
 */

const LARGURA_GAVETA = '(max-width: 992px)';

/**
 * PROPÓSITO DE NEGÓCIO: liga o botão "Menu", a sombra, o botão "Fechar", o Esc e a troca de
 * largura da janela à gaveta do menu.
 *
 * INVARIANTES DO DOMÍNIO: ver o cabeçalho do arquivo.
 *
 * COMPORTAMENTO EM CASO DE FALHA: devolve `false` sem registrar nada quando falta algum dos
 * elementos (`#btn-menu-movel`, `.sidebar`, `.gaveta-sombra`, `.main-content`).
 *
 * @returns {boolean} `true` quando a gaveta ficou ligada
 */
export function inicializarGaveta() {
    const botao = document.getElementById('btn-menu-movel');
    const gaveta = document.querySelector('.sidebar');
    const sombra = document.querySelector('.gaveta-sombra');
    const conteudo = document.querySelector('.main-content');
    const fecharDentro = document.getElementById('btn-gaveta-fechar');
    if (!botao || !gaveta || !sombra || !conteudo) return false;

    const telaEstreita = window.matchMedia(LARGURA_GAVETA);
    const estaAberta = () => gaveta.classList.contains('aberta');

    const abrir = () => {
        if (!telaEstreita.matches || estaAberta()) return;
        gaveta.classList.add('aberta');
        sombra.hidden = false;
        document.body.classList.add('gaveta-aberta');
        botao.setAttribute('aria-expanded', 'true');
        conteudo.inert = true;
        const ativo = gaveta.querySelector('.nav-item.active');
        const alvo = ativo && ativo.offsetParent !== null ? ativo : (fecharDentro || gaveta.querySelector('.nav-item'));
        // Medido no Chromium: logo depois de a gaveta deixar de estar escondida o focus() falha
        // calado, e o número de quadros até ele pegar variou (2 bastaram 3 de 3 num ensaio e
        // faltaram noutro). Então tenta, confere se pegou e repete no quadro seguinte.
        const focarQuandoPuder = (restam) => {
            if (!estaAberta() || !alvo) return;
            alvo.focus();
            if (document.activeElement !== alvo && restam > 0) {
                requestAnimationFrame(() => focarQuandoPuder(restam - 1));
            }
        };
        requestAnimationFrame(() => focarQuandoPuder(10));
    };

    /** @param {'botao'|'titulo'|'nenhum'} destinoDoFoco para onde o foco vai depois de fechar */
    const fechar = (destinoDoFoco) => {
        if (!estaAberta()) return;
        gaveta.classList.remove('aberta');
        sombra.hidden = true;
        document.body.classList.remove('gaveta-aberta');
        botao.setAttribute('aria-expanded', 'false');
        conteudo.inert = false;
        if (destinoDoFoco === 'botao') botao.focus();
        if (destinoDoFoco === 'titulo') document.getElementById('page-title')?.focus();
    };

    botao.addEventListener('click', () => (estaAberta() ? fechar('botao') : abrir()));
    sombra.addEventListener('click', () => fechar('botao'));
    fecharDentro?.addEventListener('click', () => fechar('botao'));

    // Escolher uma tela fecha a gaveta e leva o foco ao título do painel aberto; "Sair" abre o
    // próprio diálogo, que cuida do foco. O cabeçalho de grupo só abre/fecha o grupo.
    gaveta.addEventListener('click', (evento) => {
        const item = evento.target.closest('.nav-item');
        if (!item || !estaAberta()) return;
        fechar(item.id === 'btn-menu-sair' ? 'nenhum' : 'titulo');
    });

    // Captura na janela: roda ANTES do Esc do dica.js (que escuta no document), e assim ainda vê
    // se havia uma dica aberta neste Esc.
    window.addEventListener('keydown', (evento) => {
        if (evento.key !== 'Escape' || !estaAberta()) return;
        const dicaAberta = [...gaveta.querySelectorAll('.dica__texto')]
            .some((t) => getComputedStyle(t).visibility === 'visible');
        if (dicaAberta) return;
        fechar('botao');
    }, true);

    telaEstreita.addEventListener('change', (evento) => {
        if (!evento.matches) fechar('nenhum');
    });
    return true;
}
