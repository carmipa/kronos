/* dica.js v1.0 (2026-10-05) — comportamento do tooltip (regra 6-A). Fonte canônica: instrucoes/tooltip-canonico/. Não editar no projeto.
   Sem dependências. Delegação de eventos: vale para conteúdo dinâmico. */
(() => {
  const W = '.dica, .campo';
  const tip = (w) => w.querySelector('.dica__texto');

  function posicionar(w) {
    const t = tip(w); if (!t) return;
    const ancora = (t.closest('.dica') || w).getBoundingClientRect();
    const m = 8, g = 6, vw = document.documentElement.clientWidth, vh = innerHeight;
    t.style.left = '0px'; t.style.top = '0px';
    const r = t.getBoundingClientRect();
    let x = ancora.left; if (x + r.width > vw - m) x = vw - m - r.width; if (x < m) x = m;
    let y = ancora.bottom + g, cima = false;
    if (y + r.height > vh - m && ancora.top - g - r.height >= m) { y = ancora.top - g - r.height; cima = true; }
    t.style.left = x + 'px'; t.style.top = y + 'px';
    const d = t.closest('.dica') || w; if (cima) d.setAttribute('data-lado', 'cima'); else d.removeAttribute('data-lado');
  }
  const abrir  = (w) => { w.removeAttribute('data-fechada'); posicionar(w); };
  const fechar = (w) => { w.removeAttribute('data-aberta'); w.setAttribute('data-fechada', ''); };

  document.addEventListener('pointerover', (e) => { const w = e.target.closest?.(W); if (w) abrir(w); });
  document.addEventListener('focusin',     (e) => { const w = e.target.closest?.(W); if (w) abrir(w); });
  document.addEventListener('pointerout',  (e) => { const w = e.target.closest?.(W); if (w && !w.contains(e.relatedTarget)) w.removeAttribute('data-fechada'); });
  document.addEventListener('focusout',    (e) => { const w = e.target.closest?.(W); if (w && !w.contains(e.relatedTarget)) { w.removeAttribute('data-fechada'); w.removeAttribute('data-aberta'); } });

  // Esc dispensa sem mover o foco (WCAG 1.4.13)
  document.addEventListener('keydown', (e) => {
    if (e.key !== 'Escape') return;
    document.querySelectorAll(W).forEach((w) => { if (w.matches(':hover, :focus-within, [data-aberta]')) fechar(w); });
  });

  // Rolagem/redimensionamento: reposiciona as dicas visíveis
  const reposicionar = () => document.querySelectorAll('.dica:hover, .dica:focus-within, .campo:focus-within, [data-aberta]').forEach(posicionar);
  addEventListener('scroll', reposicionar, true); addEventListener('resize', reposicionar);

  // Toque/caneta e teclado no gatilho "?": alterna. Mouse não alterna (o hover já cuida).
  let tipoPonteiro = 'mouse';
  document.addEventListener('pointerdown', (e) => {
    tipoPonteiro = e.pointerType || 'mouse';
    document.querySelectorAll('[data-aberta]').forEach((w) => { if (!w.contains(e.target)) fechar(w); });
  }, true);
  document.addEventListener('click', (e) => {
    const g = e.target.closest?.('.dica__gatilho'); if (!g) return;
    if (e.detail !== 0 && tipoPonteiro === 'mouse') return;
    const w = g.closest(W);
    if (w.hasAttribute('data-aberta')) fechar(w); else { w.setAttribute('data-aberta', ''); abrir(w); }
  });
})();
