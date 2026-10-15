/* KRONOS CORE — kronos-error-catalog.js v1.2.0 — DADOS das páginas de erro
   Gerado por ferramentas/paginas-de-erro/gerar.py. Mude os textos no gerador e regere;
   nunca aqui nem nos HTMLs (eles são renderizações deste catálogo). */
window.KC_CATALOG = {
 "version": "1.2.0",
 "ui": {
  "brand.sub": "Pipeline Industrial",
  "aria.lang": "Idioma da página",
  "aria.actions": "Ações",
  "tip.brand": "Voltar ao Painel Inicial do KRONOS CORE",
  "tip.clock": "Data e hora locais do seu navegador",
  "status.wait": "Verificando backend…",
  "status.ok": "Backend online",
  "status.bad": "Backend inacessível",
  "tip.status.wait": "Consultando o estado do orquestrador Quarkus (/api/status)…",
  "tip.status.ok": "O orquestrador Java Quarkus respondeu à consulta de estado",
  "tip.status.bad": "O orquestrador não respondeu à consulta de estado (/api/status)",
  "btn.home": "Painel Inicial",
  "btn.back": "Voltar",
  "btn.reload": "Recarregar",
  "btn.retry": "Tentar novamente",
  "btn.telemetry": "Ver telemetria",
  "btn.copy": "Copiar diagnóstico",
  "btn.copied": "Copiado!",
  "btn.now": "Tentar agora",
  "btn.pause": "Pausar",
  "btn.resume": "Retomar",
  "tip.home": "Ir para o Painel Inicial do pipeline",
  "tip.back": "Retornar à tela anterior do navegador",
  "tip.reload": "Recarregar esta página e tentar de novo",
  "tip.retry": "Verificar o backend e voltar à rota de origem se ele responder",
  "tip.telemetry": "Abrir o painel de telemetria (operações, cache e tokens)",
  "tip.copy": "Copiar todos os campos do diagnóstico para a área de transferência",
  "tip.now": "Pular a contagem e verificar o backend imediatamente",
  "tip.pause": "Interromper as tentativas automáticas de reconexão",
  "tip.resume": "Retomar as tentativas automáticas de reconexão",
  "diag.title": "Diagnóstico",
  "tip.diag.title": "Informações técnicas desta ocorrência — úteis para suporte e logs",
  "diag.status": "Status HTTP",
  "tip.diag.status": "Código e descrição do status HTTP devolvido pelo servidor",
  "diag.ref": "Referência",
  "tip.diag.ref": "Código interno desta página de erro no KRONOS CORE",
  "diag.rid": "Request ID",
  "tip.diag.rid": "Identificador desta ocorrência — o mesmo aparece no log do servidor",
  "diag.time": "Horário (UTC)",
  "tip.diag.time": "Momento em que a página de erro foi exibida, em UTC (ISO 8601)",
  "diag.route": "Rota",
  "tip.diag.route": "Endereço que você tentou acessar quando o erro ocorreu",
  "diag.origin": "Origem",
  "tip.diag.origin": "Página que levou você até esta rota (referrer)",
  "diag.ua": "Navegador",
  "tip.diag.ua": "Navegador e sistema operacional detectados",
  "diag.lang": "Idioma",
  "tip.diag.lang": "Idioma em que a página está sendo exibida",
  "diag.detail": "Detalhe",
  "tip.diag.detail": "Detalhe técnico adicional recebido no endereço desta página",
  "retry.in": "Nova tentativa automática em",
  "retry.unit": "s",
  "retry.paused": "Tentativas automáticas pausadas",
  "retry.checking": "Verificando o backend…",
  "retry.online": "Backend respondeu — redirecionando…",
  "retry.exhausted": "Tentativas automáticas esgotadas. Use “Tentar agora” ou volte ao painel.",
  "foot.by": "Desenvolvido por",
  "foot.repo": "Quarkus Repo",
  "foot.page": "Página de erro v1.2.0",
  "tip.foot.repo": "Abrir o repositório do projeto no GitHub",
  "tip.foot.autor": "Abre carminati.dev.br, a página do autor, em uma nova aba",
  "tip.foot.ref": "Versão das páginas de erro e código de referência desta tela",
  "tip.hint": "Sugestão de ação para resolver ou contornar o problema",
  "ajuda": "Ajuda sobre"
 },
 "family": {
  "nav": "Navegação",
  "val": "Validação",
  "acc": "Acesso",
  "lim": "Limites & Tempo",
  "srv": "Servidor",
  "cli": "Cliente"
 },
 "icons": {
  "code-x": "<svg viewBox=\"0 0 24 24\" fill=\"none\" stroke=\"currentColor\" stroke-width=\"1.8\" stroke-linecap=\"round\" stroke-linejoin=\"round\" aria-hidden=\"true\" focusable=\"false\"><polyline points=\"8 7 4 12 8 17\"/><polyline points=\"16 7 20 12 16 17\"/><line x1=\"10\" y1=\"16\" x2=\"14\" y2=\"8\"/></svg>",
  "key": "<svg viewBox=\"0 0 24 24\" fill=\"none\" stroke=\"currentColor\" stroke-width=\"1.8\" stroke-linecap=\"round\" stroke-linejoin=\"round\" aria-hidden=\"true\" focusable=\"false\"><circle cx=\"8\" cy=\"15\" r=\"4\"/><path d=\"M10.8 12.2 20 3\"/><path d=\"M17 6l3 3\"/><path d=\"M14 9l2 2\"/></svg>",
  "lock": "<svg viewBox=\"0 0 24 24\" fill=\"none\" stroke=\"currentColor\" stroke-width=\"1.8\" stroke-linecap=\"round\" stroke-linejoin=\"round\" aria-hidden=\"true\" focusable=\"false\"><rect x=\"4\" y=\"11\" width=\"16\" height=\"10\" rx=\"2\"/><path d=\"M8 11V7a4 4 0 0 1 8 0v4\"/><circle cx=\"12\" cy=\"16\" r=\"1\" fill=\"currentColor\"/></svg>",
  "search-x": "<svg viewBox=\"0 0 24 24\" fill=\"none\" stroke=\"currentColor\" stroke-width=\"1.8\" stroke-linecap=\"round\" stroke-linejoin=\"round\" aria-hidden=\"true\" focusable=\"false\"><circle cx=\"11\" cy=\"11\" r=\"7\"/><line x1=\"21\" y1=\"21\" x2=\"16\" y2=\"16\"/><line x1=\"9\" y1=\"9\" x2=\"13\" y2=\"13\"/><line x1=\"13\" y1=\"9\" x2=\"9\" y2=\"13\"/></svg>",
  "ban": "<svg viewBox=\"0 0 24 24\" fill=\"none\" stroke=\"currentColor\" stroke-width=\"1.8\" stroke-linecap=\"round\" stroke-linejoin=\"round\" aria-hidden=\"true\" focusable=\"false\"><circle cx=\"12\" cy=\"12\" r=\"9\"/><line x1=\"5.6\" y1=\"5.6\" x2=\"18.4\" y2=\"18.4\"/></svg>",
  "clock": "<svg viewBox=\"0 0 24 24\" fill=\"none\" stroke=\"currentColor\" stroke-width=\"1.8\" stroke-linecap=\"round\" stroke-linejoin=\"round\" aria-hidden=\"true\" focusable=\"false\"><circle cx=\"12\" cy=\"12\" r=\"9\"/><polyline points=\"12 7 12 12 15.5 14\"/></svg>",
  "merge": "<svg viewBox=\"0 0 24 24\" fill=\"none\" stroke=\"currentColor\" stroke-width=\"1.8\" stroke-linecap=\"round\" stroke-linejoin=\"round\" aria-hidden=\"true\" focusable=\"false\"><circle cx=\"6\" cy=\"5\" r=\"2\"/><circle cx=\"18\" cy=\"5\" r=\"2\"/><circle cx=\"12\" cy=\"19\" r=\"2\"/><path d=\"M6 7v2a4 4 0 0 0 4 4h4a4 4 0 0 0 4-4V7\"/><path d=\"M12 13v4\"/></svg>",
  "archive-x": "<svg viewBox=\"0 0 24 24\" fill=\"none\" stroke=\"currentColor\" stroke-width=\"1.8\" stroke-linecap=\"round\" stroke-linejoin=\"round\" aria-hidden=\"true\" focusable=\"false\"><rect x=\"3\" y=\"4\" width=\"18\" height=\"5\" rx=\"1\"/><path d=\"M5 9v10a1 1 0 0 0 1 1h12a1 1 0 0 0 1-1V9\"/><line x1=\"10\" y1=\"13\" x2=\"14\" y2=\"17\"/><line x1=\"14\" y1=\"13\" x2=\"10\" y2=\"17\"/></svg>",
  "box": "<svg viewBox=\"0 0 24 24\" fill=\"none\" stroke=\"currentColor\" stroke-width=\"1.8\" stroke-linecap=\"round\" stroke-linejoin=\"round\" aria-hidden=\"true\" focusable=\"false\"><path d=\"M21 8l-9-5-9 5v8l9 5 9-5z\"/><path d=\"M3 8l9 5 9-5\"/><line x1=\"12\" y1=\"13\" x2=\"12\" y2=\"21\"/></svg>",
  "file-q": "<svg viewBox=\"0 0 24 24\" fill=\"none\" stroke=\"currentColor\" stroke-width=\"1.8\" stroke-linecap=\"round\" stroke-linejoin=\"round\" aria-hidden=\"true\" focusable=\"false\"><path d=\"M14 3H7a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h10a2 2 0 0 0 2-2V8z\"/><polyline points=\"14 3 14 8 19 8\"/><path d=\"M10.3 13.3a1.9 1.9 0 1 1 2.6 1.7c-.6.3-.9.7-.9 1.4\"/><circle cx=\"12\" cy=\"18.7\" r=\".7\" fill=\"currentColor\"/></svg>",
  "clip-x": "<svg viewBox=\"0 0 24 24\" fill=\"none\" stroke=\"currentColor\" stroke-width=\"1.8\" stroke-linecap=\"round\" stroke-linejoin=\"round\" aria-hidden=\"true\" focusable=\"false\"><rect x=\"6\" y=\"4\" width=\"12\" height=\"17\" rx=\"2\"/><path d=\"M9 4V3a1 1 0 0 1 1-1h4a1 1 0 0 1 1 1v1\"/><line x1=\"10\" y1=\"11\" x2=\"14\" y2=\"15\"/><line x1=\"14\" y1=\"11\" x2=\"10\" y2=\"15\"/></svg>",
  "activity": "<svg viewBox=\"0 0 24 24\" fill=\"none\" stroke=\"currentColor\" stroke-width=\"1.8\" stroke-linecap=\"round\" stroke-linejoin=\"round\" aria-hidden=\"true\" focusable=\"false\"><polyline points=\"3 12 7 12 10 5 14 19 17 12 21 12\"/></svg>",
  "server": "<svg viewBox=\"0 0 24 24\" fill=\"none\" stroke=\"currentColor\" stroke-width=\"1.8\" stroke-linecap=\"round\" stroke-linejoin=\"round\" aria-hidden=\"true\" focusable=\"false\"><rect x=\"3\" y=\"4\" width=\"18\" height=\"6\" rx=\"1.5\"/><rect x=\"3\" y=\"14\" width=\"18\" height=\"6\" rx=\"1.5\"/><circle cx=\"7\" cy=\"7\" r=\".9\" fill=\"currentColor\"/><circle cx=\"7\" cy=\"17\" r=\".9\" fill=\"currentColor\"/><path d=\"M17 6v2M17 16v2\"/></svg>",
  "cpu": "<svg viewBox=\"0 0 24 24\" fill=\"none\" stroke=\"currentColor\" stroke-width=\"1.8\" stroke-linecap=\"round\" stroke-linejoin=\"round\" aria-hidden=\"true\" focusable=\"false\"><rect x=\"6\" y=\"6\" width=\"12\" height=\"12\" rx=\"2\"/><rect x=\"10\" y=\"10\" width=\"4\" height=\"4\"/><path d=\"M9 2v4M15 2v4M9 18v4M15 18v4M2 9h4M2 15h4M18 9h4M18 15h4\"/></svg>",
  "wrench": "<svg viewBox=\"0 0 24 24\" fill=\"none\" stroke=\"currentColor\" stroke-width=\"1.8\" stroke-linecap=\"round\" stroke-linejoin=\"round\" aria-hidden=\"true\" focusable=\"false\"><path d=\"M14.7 6.3a1 1 0 0 0 0 1.4l1.6 1.6a1 1 0 0 0 1.4 0l3.77-3.77a6 6 0 0 1-7.94 7.94l-6.91 6.91a2.12 2.12 0 0 1-3-3l6.91-6.91a6 6 0 0 1 7.94-7.94l-3.76 3.76z\"/></svg>",
  "hourglass": "<svg viewBox=\"0 0 24 24\" fill=\"none\" stroke=\"currentColor\" stroke-width=\"1.8\" stroke-linecap=\"round\" stroke-linejoin=\"round\" aria-hidden=\"true\" focusable=\"false\"><path d=\"M6 3h12\"/><path d=\"M6 21h12\"/><path d=\"M8 3v3.5a4 4 0 0 0 1.6 3.2L12 12l-2.4 2.3A4 4 0 0 0 8 17.5V21\"/><path d=\"M16 3v3.5a4 4 0 0 1-1.6 3.2L12 12l2.4 2.3a4 4 0 0 1 1.6 3.2V21\"/></svg>",
  "wifi-off": "<svg viewBox=\"0 0 24 24\" fill=\"none\" stroke=\"currentColor\" stroke-width=\"1.8\" stroke-linecap=\"round\" stroke-linejoin=\"round\" aria-hidden=\"true\" focusable=\"false\"><line x1=\"2\" y1=\"2\" x2=\"22\" y2=\"22\"/><path d=\"M8.5 16.5a5 5 0 0 1 7 0\"/><path d=\"M5 12.5a10 10 0 0 1 5.2-2.7\"/><path d=\"M12 6a16 16 0 0 1 10 3.5\"/><path d=\"M2 9.5a16 16 0 0 1 4-2.4\"/><circle cx=\"12\" cy=\"20\" r=\".9\" fill=\"currentColor\"/></svg>",
  "bug": "<svg viewBox=\"0 0 24 24\" fill=\"none\" stroke=\"currentColor\" stroke-width=\"1.8\" stroke-linecap=\"round\" stroke-linejoin=\"round\" aria-hidden=\"true\" focusable=\"false\"><path d=\"M8 2l1.9 1.9M16 2l-1.9 1.9\"/><path d=\"M9 7.1V6a3 3 0 0 1 6 0v1.1\"/><rect x=\"6\" y=\"7\" width=\"12\" height=\"13\" rx=\"6\"/><path d=\"M12 12v8\"/><path d=\"M6 13H2M22 13h-4\"/><path d=\"M6 17l-3 2M18 17l3 2\"/><path d=\"M6 9L3 7M18 9l3-2\"/></svg>",
  "home": "<svg viewBox=\"0 0 24 24\" fill=\"none\" stroke=\"currentColor\" stroke-width=\"1.8\" stroke-linecap=\"round\" stroke-linejoin=\"round\" aria-hidden=\"true\" focusable=\"false\"><path d=\"M3 11l9-8 9 8\"/><path d=\"M5 10v10a1 1 0 0 0 1 1h4v-6h4v6h4a1 1 0 0 0 1-1V10\"/></svg>",
  "back": "<svg viewBox=\"0 0 24 24\" fill=\"none\" stroke=\"currentColor\" stroke-width=\"1.8\" stroke-linecap=\"round\" stroke-linejoin=\"round\" aria-hidden=\"true\" focusable=\"false\"><line x1=\"19\" y1=\"12\" x2=\"5\" y2=\"12\"/><polyline points=\"12 19 5 12 12 5\"/></svg>",
  "reload": "<svg viewBox=\"0 0 24 24\" fill=\"none\" stroke=\"currentColor\" stroke-width=\"1.8\" stroke-linecap=\"round\" stroke-linejoin=\"round\" aria-hidden=\"true\" focusable=\"false\"><path d=\"M21 12a9 9 0 1 1-2.6-6.4\"/><polyline points=\"21 3 21 9 15 9\"/></svg>",
  "login": "<svg viewBox=\"0 0 24 24\" fill=\"none\" stroke=\"currentColor\" stroke-width=\"1.8\" stroke-linecap=\"round\" stroke-linejoin=\"round\" aria-hidden=\"true\" focusable=\"false\"><path d=\"M15 3h4a2 2 0 0 1 2 2v14a2 2 0 0 1-2 2h-4\"/><polyline points=\"10 17 15 12 10 7\"/><line x1=\"15\" y1=\"12\" x2=\"3\" y2=\"12\"/></svg>",
  "chart": "<svg viewBox=\"0 0 24 24\" fill=\"none\" stroke=\"currentColor\" stroke-width=\"1.8\" stroke-linecap=\"round\" stroke-linejoin=\"round\" aria-hidden=\"true\" focusable=\"false\"><path d=\"M3 3v18h18\"/><path d=\"M7 15l4-5 3 3 5-7\"/></svg>",
  "copy": "<svg viewBox=\"0 0 24 24\" fill=\"none\" stroke=\"currentColor\" stroke-width=\"1.8\" stroke-linecap=\"round\" stroke-linejoin=\"round\" aria-hidden=\"true\" focusable=\"false\"><rect x=\"9\" y=\"9\" width=\"12\" height=\"12\" rx=\"2\"/><path d=\"M5 15H4a2 2 0 0 1-2-2V4a2 2 0 0 1 2-2h9a2 2 0 0 1 2 2v1\"/></svg>",
  "play": "<svg viewBox=\"0 0 24 24\" fill=\"none\" stroke=\"currentColor\" stroke-width=\"1.8\" stroke-linecap=\"round\" stroke-linejoin=\"round\" aria-hidden=\"true\" focusable=\"false\"><polygon points=\"6 4 20 12 6 20 6 4\"/></svg>",
  "pause": "<svg viewBox=\"0 0 24 24\" fill=\"none\" stroke=\"currentColor\" stroke-width=\"1.8\" stroke-linecap=\"round\" stroke-linejoin=\"round\" aria-hidden=\"true\" focusable=\"false\"><rect x=\"6\" y=\"4\" width=\"4\" height=\"16\"/><rect x=\"14\" y=\"4\" width=\"4\" height=\"16\"/></svg>",
  "bulb": "<svg viewBox=\"0 0 24 24\" fill=\"none\" stroke=\"currentColor\" stroke-width=\"1.8\" stroke-linecap=\"round\" stroke-linejoin=\"round\" aria-hidden=\"true\" focusable=\"false\"><path d=\"M9 18h6\"/><path d=\"M10 22h4\"/><path d=\"M12 2a7 7 0 0 0-4 12.7c.6.5 1 1.3 1 2.1V18h6v-1.2c0-.8.4-1.6 1-2.1A7 7 0 0 0 12 2z\"/></svg>",
  "github": "<svg viewBox=\"0 0 24 24\" fill=\"none\" stroke=\"currentColor\" stroke-width=\"1.8\" stroke-linecap=\"round\" stroke-linejoin=\"round\" aria-hidden=\"true\" focusable=\"false\"><path d=\"M9 19c-5 1.5-5-2.5-7-3m14 6v-3.9a3.4 3.4 0 0 0-.9-2.6c3.1-.3 6.4-1.5 6.4-7A5.4 5.4 0 0 0 20 4.8 5 5 0 0 0 19.9 1s-1.2-.3-3.9 1.5a13.4 13.4 0 0 0-7 0C6.3.7 5.1 1 5.1 1A5 5 0 0 0 5 4.8a5.4 5.4 0 0 0-1.5 3.8c0 5.5 3.3 6.7 6.4 7a3.4 3.4 0 0 0-.9 2.6V22\"/></svg>"
 },
 "errors": {
  "400": {
   "display": "400",
   "mark": "400",
   "http": "400 Bad Request",
   "color": "#a3e635",
   "icon": "code-x",
   "family": "val",
   "actions": [
    "back",
    "home",
    "telemetry"
   ],
   "retry": false,
   "texto": {
    "title": "Requisição inválida",
    "desc": "O orquestrador não conseguiu interpretar esta requisição. Algum parâmetro, cabeçalho ou corpo chegou malformado antes mesmo de ser processado pelo pipeline.",
    "hint": "Volte ao painel de origem e refaça a operação. Se o erro persistir, copie o diagnóstico e verifique o formato dos dados enviados.",
    "codeTip": "Código HTTP 400 — o servidor rejeitou a sintaxe da requisição",
    "titleTip": "O que aconteceu nesta requisição",
    "eyebrow": "HTTP 400 · Validação"
   }
  },
  "401": {
   "display": "401",
   "mark": "401",
   "http": "401 Unauthorized",
   "color": "#f43f5e",
   "icon": "key",
   "family": "acc",
   "actions": [
    "home",
    "back"
   ],
   "retry": false,
   "texto": {
    "title": "Autenticação necessária",
    "desc": "Esta requisição chegou sem credenciais aceitas. O KRONOS CORE local não tem login: isso só acontece quando há um proxy com autenticação na frente dele.",
    "hint": "Se você acessa por um proxy com autenticação, entre por ele e abra o painel de novo. Na instalação local, volte ao Painel Inicial.",
    "codeTip": "Código HTTP 401 — a requisição não trouxe credenciais válidas",
    "titleTip": "Por que o acesso foi interrompido",
    "eyebrow": "HTTP 401 · Acesso"
   }
  },
  "403": {
   "display": "403",
   "mark": "403",
   "http": "403 Forbidden",
   "color": "#ec4899",
   "icon": "lock",
   "family": "acc",
   "actions": [
    "home",
    "back",
    "telemetry"
   ],
   "retry": false,
   "texto": {
    "title": "Acesso negado",
    "desc": "O servidor entendeu a requisição, mas recusou atendê-la — por exemplo, uma pasta fora das raízes que o navegador de pastas pode listar. Nenhuma etapa do pipeline foi executada.",
    "hint": "Escolha a pasta pelo botão Procurar do painel, dentro das raízes configuradas em navegador.raizes, e repita a operação.",
    "codeTip": "Código HTTP 403 — o servidor entendeu a requisição, mas recusou atendê-la",
    "titleTip": "Por que o acesso foi recusado",
    "eyebrow": "HTTP 403 · Acesso"
   }
  },
  "vitrine": {
   "display": "403",
   "mark": "403",
   "http": "403 Forbidden",
   "color": "#38bdf8",
   "icon": "lock",
   "family": "acc",
   "actions": [
    "home",
    "back"
   ],
   "retry": false,
   "texto": {
    "title": "Só na instalação local",
    "desc": "Este endereço não está aberto na vitrine pública do KRONOS CORE. A vitrine mostra as telas para você conhecer o sistema, mas nenhuma função roda aqui: tradução, revisão, gravação e leitura de pastas só acontecem na instalação local. Nada foi executado.",
    "hint": "Volte às telas para continuar conhecendo o KRONOS CORE. Para usar as funções, instale o KRONOS CORE na sua máquina — o passo a passo está no repositório do projeto.",
    "codeTip": "Código HTTP 403 — a vitrine recusa toda função; só as telas são públicas",
    "titleTip": "Por que a vitrine recusou",
    "eyebrow": "HTTP 403 · Acesso"
   }
  },
  "404": {
   "display": "404",
   "mark": "404",
   "http": "404 Not Found",
   "color": "#2dd4bf",
   "icon": "search-x",
   "family": "nav",
   "actions": [
    "home",
    "back",
    "telemetry"
   ],
   "retry": false,
   "texto": {
    "title": "Página não encontrada",
    "desc": "O painel, rota ou arquivo que você procura não existe nesta versão do pipeline. Ele pode ter sido movido, renomeado durante uma etapa de finalização ou nunca ter existido.",
    "hint": "Confira o endereço digitado ou navegue pelo Painel Inicial — todas as etapas do fluxo de trabalho estão listadas lá.",
    "codeTip": "Código HTTP 404 — o servidor não encontrou nada neste endereço",
    "titleTip": "O que não foi encontrado",
    "eyebrow": "HTTP 404 · Navegação"
   }
  },
  "405": {
   "display": "405",
   "mark": "405",
   "http": "405 Method Not Allowed",
   "color": "#10b981",
   "icon": "ban",
   "family": "val",
   "actions": [
    "back",
    "home"
   ],
   "retry": false,
   "texto": {
    "title": "Método não permitido",
    "desc": "A rota existe, mas não aceita o método HTTP utilizado — por exemplo, um POST em um endpoint que só responde a GET, ou um GET em uma ação de processamento.",
    "hint": "Use os botões e formulários do painel em vez de chamar a rota diretamente. Para integrações, consulte a documentação da API.",
    "codeTip": "Código HTTP 405 — o verbo HTTP não é aceito por esta rota",
    "titleTip": "Qual foi o problema com o método",
    "eyebrow": "HTTP 405 · Validação"
   }
  },
  "408": {
   "display": "408",
   "mark": "408",
   "http": "408 Request Timeout",
   "color": "#f59e0b",
   "icon": "clock",
   "family": "lim",
   "actions": [
    "retry",
    "home"
   ],
   "retry": true,
   "texto": {
    "title": "Tempo de requisição esgotado",
    "desc": "O servidor esperou demais pela requisição completa e encerrou a conexão. Redes lentas, uploads interrompidos ou abas em segundo plano costumam causar isso.",
    "hint": "Verifique a conexão com o servidor local e tente novamente. A página fará uma nova tentativa automática quando o backend responder.",
    "codeTip": "Código HTTP 408 — o servidor desistiu de esperar a requisição chegar",
    "titleTip": "O que expirou",
    "eyebrow": "HTTP 408 · Limites & Tempo"
   }
  },
  "409": {
   "display": "409",
   "mark": "409",
   "http": "409 Conflict",
   "color": "#fb923c",
   "icon": "merge",
   "family": "val",
   "actions": [
    "reload",
    "back",
    "home"
   ],
   "retry": false,
   "texto": {
    "title": "Conflito de estado",
    "desc": "A operação colide com o estado atual do recurso — por exemplo, uma legenda que já está em processamento, um lote já remuxado ou um arquivo com o mesmo nome no cache local.",
    "hint": "Recarregue o painel para ver o estado mais recente antes de repetir a ação. Se o conflito for de cache, use a Correção de Cache (2.3).",
    "codeTip": "Código HTTP 409 — a requisição conflita com o estado atual do recurso",
    "titleTip": "Onde houve o conflito",
    "eyebrow": "HTTP 409 · Validação"
   }
  },
  "410": {
   "display": "410",
   "mark": "410",
   "http": "410 Gone",
   "color": "#67e8f9",
   "icon": "archive-x",
   "family": "nav",
   "actions": [
    "home",
    "back"
   ],
   "retry": false,
   "texto": {
    "title": "Recurso removido",
    "desc": "Este recurso existiu, mas foi removido de forma permanente: um arquivo arquivado após a remuxagem, um cache purgado ou uma rota aposentada em versão anterior do pipeline.",
    "hint": "Não adianta tentar de novo — o endereço não voltará. Localize a versão atual do recurso pelo Painel Inicial ou pela telemetria.",
    "codeTip": "Código HTTP 410 — o recurso foi removido intencionalmente e em definitivo",
    "titleTip": "O que foi removido",
    "eyebrow": "HTTP 410 · Navegação"
   }
  },
  "413": {
   "display": "413",
   "mark": "413",
   "http": "413 Content Too Large",
   "color": "#fde047",
   "icon": "box",
   "family": "val",
   "actions": [
    "back",
    "home"
   ],
   "retry": false,
   "texto": {
    "title": "Arquivo muito grande",
    "desc": "O conteúdo enviado excede o limite configurado no servidor. Arquivos MKV completos não devem ser enviados por upload — eles são lidos diretamente pela Extração (1.2) a partir da pasta local.",
    "hint": "Envie apenas a legenda (ASS/SRT/PGS) ou aponte o pipeline para a pasta de origem. Para aumentar o limite, ajuste quarkus.http.limits.max-body-size.",
    "codeTip": "Código HTTP 413 — o corpo da requisição ultrapassa o tamanho máximo aceito",
    "titleTip": "O que ultrapassou o limite",
    "eyebrow": "HTTP 413 · Validação"
   }
  },
  "415": {
   "display": "415",
   "mark": "415",
   "http": "415 Unsupported Media Type",
   "color": "#86efac",
   "icon": "file-q",
   "family": "val",
   "actions": [
    "back",
    "home"
   ],
   "retry": false,
   "texto": {
    "title": "Formato não suportado",
    "desc": "O tipo de arquivo ou de conteúdo enviado não é aceito por este endpoint. O pipeline trabalha com legendas ASS, SRT e PGS extraídas de contêineres MKV.",
    "hint": "Converta o arquivo para um formato suportado antes de enviar de novo; a Extração (1.2) já entrega ASS, SRT ou PGS a partir do MKV.",
    "codeTip": "Código HTTP 415 — o Content-Type da requisição não é suportado",
    "titleTip": "Qual formato foi recusado",
    "eyebrow": "HTTP 415 · Validação"
   }
  },
  "422": {
   "display": "422",
   "mark": "422",
   "http": "422 Unprocessable Content",
   "color": "#e879f9",
   "icon": "clip-x",
   "family": "val",
   "actions": [
    "back",
    "home"
   ],
   "retry": false,
   "texto": {
    "title": "Dados não processáveis",
    "desc": "A requisição está bem formada, mas os dados não passaram na validação: campos obrigatórios ausentes, faixas de legenda inválidas ou metadados inconsistentes com o arquivo de mídia.",
    "hint": "Revise os campos destacados no formulário de origem. A Análise de Mídia (1.1) mostra as faixas e metadados reais do arquivo.",
    "codeTip": "Código HTTP 422 — sintaxe correta, mas conteúdo semanticamente inválido",
    "titleTip": "Quais dados falharam na validação",
    "eyebrow": "HTTP 422 · Validação"
   }
  },
  "429": {
   "display": "429",
   "mark": "429",
   "http": "429 Too Many Requests",
   "color": "#ea580c",
   "icon": "activity",
   "family": "lim",
   "actions": [
    "retry",
    "home"
   ],
   "retry": true,
   "texto": {
    "title": "Muitas requisições",
    "desc": "Você ultrapassou o limite de requisições em um curto período. Quem está na frente do KRONOS CORE está protegendo o serviço contra sobrecarga.",
    "hint": "Aguarde a contagem abaixo — ela respeita o intervalo informado pelo servidor — e evite disparar vários lotes em paralelo.",
    "codeTip": "Código HTTP 429 — limite de taxa (rate limit) atingido",
    "titleTip": "Por que o ritmo foi limitado",
    "eyebrow": "HTTP 429 · Limites & Tempo"
   }
  },
  "500": {
   "display": "500",
   "mark": "500",
   "http": "500 Internal Server Error",
   "color": "#ef4444",
   "icon": "server",
   "family": "srv",
   "actions": [
    "retry",
    "home",
    "telemetry"
   ],
   "retry": true,
   "texto": {
    "title": "Erro interno do servidor",
    "desc": "O orquestrador Java Quarkus encontrou uma falha inesperada ao processar esta requisição. O erro foi registrado no log do servidor com o Request ID abaixo.",
    "hint": "Tente novamente em instantes. Se o problema continuar, procure o Request ID no log da execução (logs/execucoes/) e informe-o.",
    "codeTip": "Código HTTP 500 — exceção não tratada no servidor",
    "titleTip": "O que falhou no servidor",
    "eyebrow": "HTTP 500 · Servidor"
   }
  },
  "502": {
   "display": "502",
   "mark": "502",
   "http": "502 Bad Gateway",
   "color": "#8b5cf6",
   "icon": "cpu",
   "family": "srv",
   "actions": [
    "retry",
    "home",
    "telemetry"
   ],
   "retry": true,
   "texto": {
    "title": "KRONOS CORE indisponível",
    "desc": "O proxy na frente do KRONOS CORE não obteve uma resposta válida dele. O serviço pode estar reiniciando, subindo ou fora do ar.",
    "hint": "Confirme que o KRONOS CORE está em execução e que a porta configurada responde. A página tentará reconectar automaticamente.",
    "codeTip": "Código HTTP 502 — resposta inválida do serviço por trás do proxy",
    "titleTip": "Qual serviço não respondeu",
    "eyebrow": "HTTP 502 · Servidor"
   }
  },
  "503": {
   "display": "503",
   "mark": "503",
   "http": "503 Service Unavailable",
   "color": "#3b82f6",
   "icon": "wrench",
   "family": "srv",
   "actions": [
    "retry",
    "home"
   ],
   "retry": true,
   "texto": {
    "title": "Serviço indisponível",
    "desc": "O KRONOS CORE está reiniciando ou temporariamente sobrecarregado e não atendeu esta requisição.",
    "hint": "Nenhuma ação é necessária: a página verificará o backend periodicamente e voltará ao painel assim que ele responder.",
    "codeTip": "Código HTTP 503 — o servidor está indisponível de forma temporária",
    "titleTip": "Por que o serviço está parado",
    "eyebrow": "HTTP 503 · Servidor"
   }
  },
  "504": {
   "display": "504",
   "mark": "504",
   "http": "504 Gateway Timeout",
   "color": "#a5b4fc",
   "icon": "hourglass",
   "family": "srv",
   "actions": [
    "retry",
    "home",
    "telemetry"
   ],
   "retry": true,
   "texto": {
    "title": "Tempo limite do gateway",
    "desc": "O KRONOS CORE demorou demais para responder e o proxy na frente dele encerrou a espera.",
    "hint": "Tente de novo em instantes. Um trabalho já enfileirado no pipeline continua no servidor — confira o andamento no painel.",
    "codeTip": "Código HTTP 504 — o serviço por trás do proxy não respondeu a tempo",
    "titleTip": "O que demorou demais",
    "eyebrow": "HTTP 504 · Servidor"
   }
  },
  "offline": {
   "display": "OFFLINE",
   "mark": "OFF",
   "http": "— (sem conexão)",
   "color": "#0ea5e9",
   "icon": "wifi-off",
   "family": "cli",
   "actions": [
    "retry",
    "home"
   ],
   "retry": true,
   "texto": {
    "title": "Sem conexão com o servidor",
    "desc": "Seu navegador perdeu a conexão com o KRONOS CORE. Isso costuma acontecer quando o backend Quarkus foi encerrado, a rede local caiu ou o computador entrou em suspensão.",
    "hint": "Verifique se o backend ainda está em execução e se a rede está ativa. Assim que a conexão voltar, esta página retomará o painel sozinha.",
    "codeTip": "Código interno do KRONOS CORE — não é um status HTTP",
    "titleTip": "O que foi perdido",
    "eyebrow": "KRONOS · Cliente"
   }
  },
  "crash": {
   "display": "FALHA",
   "mark": "ERR",
   "http": "— (erro de interface)",
   "color": "#e2e8f0",
   "icon": "bug",
   "family": "cli",
   "actions": [
    "reload",
    "home",
    "telemetry"
   ],
   "retry": false,
   "texto": {
    "title": "Falha na interface",
    "desc": "A interface encontrou um erro inesperado de JavaScript e não conseguiu continuar. O estado do pipeline no servidor não foi afetado — apenas esta tela.",
    "hint": "Recarregue a página. Se a falha se repetir, copie o diagnóstico e abra o console do navegador (F12) para localizar o erro.",
    "codeTip": "Código interno do KRONOS CORE — erro não tratado no navegador",
    "titleTip": "O que quebrou na interface",
    "eyebrow": "KRONOS · Cliente"
   }
  }
 }
};
