/* icone-varredura.js v1.3 (2026-10-25) — componente compartilhado: não editar no projeto; mudança só na origem.
   1.3: (a) um elemento anterior que tem TEXTO legível (letra ou número fora de glifo de fonte, svg, img e decorativo
   aria-hidden) não é "ícone imediatamente antes" do rótulo seguinte, mesmo com um ícone declarado ou desenhado: é um
   aviso, uma frase ou outro rótulo, e o ícone é dele. Um controle (botão, link, campo) logo antes também não empresta
   o ícone: é de outra ação. (b) o ícone de um controle aninhado (botão, link, campo, aba, item de menu) não é ícone do
   rótulo que o contém quando o rótulo tem texto próprio fora desse controle: num título "Quem chama" com o botão
   "buscar" dentro, o ícone é do botão. Se o controle carrega todo o texto do rótulo (título que é um link, cabeçalho
   de coluna que é o botão de ordenar), o ícone dele vale. O mesmo para emoji no texto do controle aninhado.
   (c) título de área fora de h1-h6 entra no escopo quando é DECLARADO como título: role=heading, ou o elemento que
   dá nome a uma área (dialog, section, article, aside, nav, form, fieldset, role dialog/alertdialog/region/group/
   radiogroup/tabpanel/complementary/navigation/form) por aria-labelledby com um único id. Elemento com cara de título
   só pela classe (card-title, cabecalho, -h) não é medido: classe não é declaração, e o critério por classe confunde
   título com cabeçalho de dado repetido em laço. data-titulo-editorial vale também para esses títulos declarados.
   1.2: ícone DECLARADO (data-icon, data-icone, data-glifo) só conta quando desenhou alguma coisa — um svg/img com
   tamanho dentro dele, ou um ::before/::after que desenha; nome que o catálogo não conhece não desenha nada e
   reprova. ::before/::after com content vazio conta quando desenha por máscara, imagem de fundo ou cor de fundo com
   tamanho. Rótulo vizinho com ícone declarado não vale como ícone do seguinte. Rótulo que não ocupa espaço na tela
   (0 px, ou dentro de um ancestral com display:none) não é conferido nas FALHAS; se tem texto e nenhum ícone nem
   declarado, vai para os AVISOS como "não renderizado agora". Região de estado vazia (role=status/alert sem texto e
   sem nome) não é rótulo. Sem motor de layout (jsdom), tamanho e desenho não são medidos: valem a presença e a
   declaração, e "não renderizado" é ter um ancestral com display:none.
   1.1: o nome do glifo (Material Symbols/Icons) num invólucro antes do rótulo não conta como texto do invólucro, e
   seta de texto (U+2190 a U+21FF) não vale como ícone.
   Ícone junto de TODO rótulo: label, cabeçalho e legenda de tabela, título de área (página, seção, card, modal, aba),
   botão e link que age como botão, item de menu e navegação, legend, summary, KPI e selo de estado.
   varreduraIcone()  -> FALHAS (reprovam a entrega). Retorna [] quando está tudo certo. Acusa todo rótulo visível do
                        escopo acima que não tem ícone desenhado: nem dentro dele (fora de controle aninhado), nem
                        imediatamente antes dele, nem declarado no próprio elemento (data-icon que desenhou), nem
                        desenhado por CSS (::before/::after).
   avisosIcone()     -> AVISOS (não reprovam): títulos declarados editoriais (data-titulo-editorial), exceções
                        declaradas (data-icone-excecao) e rótulos não renderizados agora, para a contagem da entrega.
   O gatilho "?" de ajuda (classe dica__gatilho, glifo help) e o balão de dica NÃO contam como ícone do rótulo.
   Marcações respeitadas: data-icone-excecao="motivo" (fora da contagem; o motivo tem de estar registrado) e
   data-titulo-editorial em título (h1-h6, role=heading ou nome de área): frase para ler, não nome de área. */
function iconeRegrasDaVarredura(raiz) {
  const GLIFOS_DICA = new Set(['help', 'help_outline', 'question_mark', 'info_outline']);
  const DECLARADO = '[data-icon], [data-icone], [data-glifo]';
  const SEL_DESENHO = 'svg, img, [class*="material-symbols"], [class*="material-icons"], i[class], .icon, .ico, .fic, [class*="icone"], [class*="glifo"], [class*="bi-"], [class*="fa-"], [class*="lucide"], [class*="feather"]';
  const SEL_ICONE = SEL_DESENHO + ', ' + DECLARADO;
  const SEL_GLIFO_FONTE = '[class*="material-symbols"], [class*="material-icons"]';
  // KPI e selo de estado entram pelo papel (role=status/alert) ou pela classe kpi/metrica; um selo só com número não é rótulo.
  const SEL_ESCOPO = 'label, th, caption, legend, summary, button, h1, h2, h3, h4, h5, h6, [role=heading], [role=button], [role=tab], [role=menuitem], a.btn, a[class*="btn-"], a[class*="button"], nav a, [role=navigation] a, [role=menubar] a, [role=tablist] a, .breadcrumb a, .kpi, .metrica, [role=status], [role=alert]';
  // Controle com nome e ação próprios: o ícone dele é dele, não do rótulo que o contém nem do rótulo seguinte.
  const SEL_CONTROLE = 'button, a[href], input, select, textarea, label, summary, [role=button], [role=link], [role=menuitem], [role=tab], [role=checkbox], [role=switch], [role=option]';
  // Área que pode receber nome por aria-labelledby: o elemento apontado é o título dela.
  const SEL_AREA = 'dialog, section, article, aside, nav, form, fieldset, [role=dialog], [role=alertdialog], [role=region], [role=group], [role=radiogroup], [role=tabpanel], [role=complementary], [role=navigation], [role=form]';
  const RE_EMOJI = /[\u{1F300}-\u{1FAFF}\u{2600}-\u{27BF}\u{2B00}-\u{2BFF}]/u;
  const RE_LETRA = /[\p{L}\p{N}]/u;
  // A raiz pode ser o document, um elemento ou um objeto que só tem querySelectorAll (filtro do projeto): o layout
  // se mede no documento da página.
  const doc = raiz.ownerDocument || (raiz.documentElement ? raiz : (typeof document !== 'undefined' ? document : null));
  const cs = (el, pseudo) => { try { return getComputedStyle(el, pseudo || null); } catch (e) { return null; } };
  // Há layout quando a página tem tamanho (navegador real). Em jsdom tudo mede 0: aí tamanho e desenho não são medidos.
  const temLayout = (() => { try { const r = doc.documentElement.getBoundingClientRect(); return r.width > 0 || r.height > 0; } catch (e) { return false; } })();
  const area = (n) => { const r = n.getBoundingClientRect(); return r.width > 0 && r.height > 0; };
  const visivel = (el) => {
    if (el.closest('[hidden], template, [aria-hidden="true"]')) return false;
    const s = cs(el); return !s || (s.display !== 'none' && s.visibility !== 'hidden');
  };
  // Ocupa espaço na tela agora. display:contents não tem caixa própria e segue valendo pelos filhos.
  // Sem layout (jsdom), o que dá para saber é se algum ancestral está com display:none.
  const renderizado = (el) => {
    if (!temLayout) { for (let a = el.parentElement; a; a = a.parentElement) { const s = cs(a); if (s && s.display === 'none') return false; } return true; }
    const s = cs(el); return (s && s.display === 'contents') || area(el);
  };
  const deDica = (el) => !!el.closest('.dica__gatilho, .dica__texto, [role=tooltip]');
  const px = (v) => parseFloat(v) > 0;
  const semCor = (v) => !v || v === 'transparent' || /^rgba\((\s*\d+\s*,){3}\s*0\s*\)$/.test(v);
  // ::before/::after que desenha: content com texto ou glifo; ou content vazio com máscara, imagem ou cor de fundo e tamanho.
  const pseudoDesenha = (el) => ['::before', '::after'].some((p) => {
    const s = cs(el, p); const c = s && s.content;
    if (!c || c === 'none' || c === 'normal' || s.display === 'none') return false;
    if (c !== '""' && c !== "''") return true;
    const imagem = [s.maskImage, s.webkitMaskImage, s.backgroundImage].some((v) => !!v && v !== 'none');
    return (imagem || !semCor(s.backgroundColor)) && px(s.width) && px(s.height);
  });
  // O que um ícone declarado desenhou: svg/img com tamanho dentro dele ou um pseudo-elemento que desenha.
  const desenhou = (el) => !temLayout || [...el.querySelectorAll('svg, img')].some(area) || pseudoDesenha(el);
  const soDeclarado = (c) => c.matches(DECLARADO) && !c.matches(SEL_DESENHO);
  // Texto que uma pessoa lê no elemento: sem glifo de fonte (o nome do glifo é texto do DOM), svg, img, decorativo e dica.
  // semControles=true tira também os controles aninhados (o texto deles é o nome deles).
  const textoLegivel = (n, semControles) => {
    if (n.matches(SEL_GLIFO_FONTE) || n.getAttribute('aria-hidden') === 'true') return '';
    const c = n.cloneNode(true);
    c.querySelectorAll(SEL_GLIFO_FONTE + ', svg, img, [aria-hidden="true"], .dica__texto, .dica__gatilho' + (semControles ? ', ' + SEL_CONTROLE : '')).forEach((x) => x.remove());
    return (c.textContent || '').replace(/\s+/g, ' ').trim();
  };
  const temTextoLegivel = (n) => RE_LETRA.test(textoLegivel(n, false));

  // Título declarado fora de h1-h6: o elemento apontado (um único id) pelo aria-labelledby de uma área.
  const nomesDeArea = new Set();
  try {
    raiz.querySelectorAll('[aria-labelledby]').forEach((a) => {
      if (!a.matches(SEL_AREA)) return;
      const ids = (a.getAttribute('aria-labelledby') || '').trim().split(/\s+/);
      if (ids.length !== 1 || !ids[0]) return;
      const r = a.getRootNode ? a.getRootNode() : doc;
      const t = (r && r.getElementById ? r : doc).getElementById(ids[0]);
      if (t && t !== a && (!raiz.contains || raiz === t || raiz.contains(t))) nomesDeArea.add(t);
    });
  } catch (e) { /* raiz sem aria-labelledby consultável: só o seletor de escopo vale */ }
  const ehRotulo = (el) => el.matches(SEL_ESCOPO) || nomesDeArea.has(el);
  const ehTitulo = (el) => /^h[1-6]$/.test(el.tagName.toLowerCase()) || el.matches('[role=heading]') || nomesDeArea.has(el);
  const dentroDeControle = (c, el) => { for (let a = c; a && a !== el; a = a.parentElement) if (a.matches(SEL_CONTROLE)) return true; return false; };

  // conferir=true: o ícone declarado precisa ter desenhado (FALHAS). conferir=false: basta a presença (AVISOS de rótulo
  // não renderizado, onde nada tem tamanho e o desenho não pode ser medido).
  const criterio = (conferir) => {
    const ehIconeReal = (c) => {
      if (deDica(c)) return false;
      if (/material-(symbols|icons)/.test(c.className || '') && GLIFOS_DICA.has((c.textContent || '').trim())) return false;
      if (conferir && soDeclarado(c) && !desenhou(c)) return false;
      // svg/img posto por um catálogo (dentro de um ícone declarado) só vale com tamanho.
      if (conferir && temLayout && c.matches('svg, img') && c.parentElement && c.parentElement.closest(DECLARADO) && !area(c)) return false;
      return true;
    };
    // Ícone dentro de um controle aninhado é do controle, salvo quando o controle carrega todo o texto do rótulo.
    const temTextoForaDosControles = (el) => RE_LETRA.test(textoLegivel(el, true));
    const temIconeDentro = (el) => {
      const proprio = temTextoForaDosControles(el);
      return [...el.querySelectorAll(SEL_ICONE)].some((c) => ehIconeReal(c) && !(proprio && dentroDeControle(c, el)));
    };
    // Um elemento só é "o ícone" de quem vem depois se não é rótulo, não é controle e não tem texto legível próprio
    // (aviso com ícone, frase com ícone e botão com ícone são deles mesmos).
    const ehElementoIcone = (el) => !!el && el.matches(SEL_ICONE) && !ehRotulo(el) && !el.matches(SEL_CONTROLE) && !temTextoLegivel(el) && ehIconeReal(el);
    // Texto do invólucro sem o nome dos glifos: em Material Symbols o nome do ícone é texto do DOM, não do rótulo.
    const semTexto = (n) => { const c = n.cloneNode(true); c.querySelectorAll(SEL_ICONE).forEach((x) => x.remove()); return !(c.textContent || '').trim(); };
    const involucroDeIcone = (n, limite) => !ehRotulo(n) && !n.matches(SEL_CONTROLE) && (!limite || n.children.length <= 2) && temIconeDentro(n) && semTexto(n);
    const temIconeAntes = (el) => {
      const irmao = el.previousElementSibling;
      if (irmao && (ehElementoIcone(irmao) || involucroDeIcone(irmao, true))) return true;
      const pai = el.parentElement;
      if (pai && pai.firstElementChild === el && !ehRotulo(pai)) {
        const antesDoPai = pai.previousElementSibling;
        if (antesDoPai && (ehElementoIcone(antesDoPai) || involucroDeIcone(antesDoPai, false))) return true;
      }
      return false;
    };
    const declaradoNoProprio = (el) => el.matches(DECLARADO) && (!conferir || desenhou(el));
    const emojiNoTexto = (el) => RE_EMOJI.test(temTextoForaDosControles(el) ? textoLegivel(el, true) : textoProprio(el));
    return (el) => declaradoNoProprio(el) || temIconeDentro(el) || emojiNoTexto(el) || temIconeAntes(el) || pseudoDesenha(el);
  };
  const textoProprio = (el) => {
    const c = el.cloneNode(true);
    c.querySelectorAll('.dica__texto, .dica__gatilho, [aria-hidden="true"], svg, img').forEach((n) => n.remove());
    return (c.textContent || '').replace(/\s+/g, ' ').trim();
  };
  const nome = (el) => (textoProprio(el) || el.getAttribute('aria-label') || el.id || el.tagName.toLowerCase()).slice(0, 40);
  const tipo = (el) => el.tagName.toLowerCase();
  const pular = (el) => !visivel(el) || deDica(el) || el.closest('td') || el.hasAttribute('data-icone-excecao') || el.closest('[data-icone-excecao]') ||
    (ehTitulo(el) && el.hasAttribute('data-titulo-editorial'));
  // rótulo dentro de rótulo (ex.: span kpi dentro de h2): conta o de fora
  const aninhado = (el) => {
    if (/^(th|label|button|summary)$/.test(tipo(el))) return false;
    for (let a = el.parentElement; a; a = a.parentElement) if (ehRotulo(a)) return true;
    return false;
  };
  // Região de estado (role=status/alert) sem texto e sem nome é o lugar onde uma mensagem vai aparecer, não um rótulo.
  const regiaoVazia = (el) => el.matches('[role=status], [role=alert]') && !textoProprio(el) && !el.getAttribute('aria-label');
  const candidatos = () => {
    const lista = [...raiz.querySelectorAll(SEL_ESCOPO)];
    if (nomesDeArea.size) {
      nomesDeArea.forEach((t) => { if (!lista.includes(t)) lista.push(t); });
      lista.sort((a, b) => (a === b ? 0 : (a.compareDocumentPosition(b) & 4 ? -1 : 1)));
    }
    return lista.filter((el) => !pular(el) && !aninhado(el) && !regiaoVazia(el));
  };
  return { candidatos, renderizado, temIconeDesenhado: criterio(true), temIconeDeclarado: criterio(false), textoProprio, nome, tipo, ehTitulo };
}

function varreduraIcone(raiz = document) {
  const r = iconeRegrasDaVarredura(raiz);
  return r.candidatos().filter((el) => r.renderizado(el) && !r.temIconeDesenhado(el))
    .map((el) => ({ elemento: r.nome(el), tipo: r.tipo(el), problema: 'rótulo sem ícone' }));
}

function avisosIcone(raiz = document) {
  const avisos = [];
  const r = iconeRegrasDaVarredura(raiz);
  [...raiz.querySelectorAll('[data-titulo-editorial]')].filter(r.ehTitulo)
    .forEach((el) => avisos.push({ elemento: (el.textContent || '').trim().slice(0, 40), problema: 'título declarado editorial (sem ícone por decisão)' }));
  raiz.querySelectorAll('[data-icone-excecao]')
    .forEach((el) => avisos.push({ elemento: (el.textContent || '').trim().slice(0, 40) || el.tagName.toLowerCase(), problema: 'exceção declarada: ' + el.getAttribute('data-icone-excecao') }));
  r.candidatos().filter((el) => !r.renderizado(el) && (r.textoProprio(el) || el.getAttribute('aria-label')) && !r.temIconeDeclarado(el))
    .forEach((el) => avisos.push({ elemento: r.nome(el), problema: 'não renderizado agora: ' + r.tipo(el) + ' sem ícone; confira no estado em que aparece' }));
  return avisos;
}

if (typeof module !== 'undefined') module.exports = { varreduraIcone, avisosIcone };
