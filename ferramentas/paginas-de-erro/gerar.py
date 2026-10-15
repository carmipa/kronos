# -*- coding: utf-8 -*-
"""
GERADOR DAS PÁGINAS DE ERRO DO KRONOS CORE — fonte única do catálogo.

PROPÓSITO DE NEGÓCIO: as páginas de erro (400 a 504, offline e falha de interface) são
renderizações deste catálogo. Texto se muda AQUI e se regera; nunca no HTML gerado, que seria
sobrescrito na próxima geração. Deriva do kit de páginas de erro v1.0.0 de 14/10/2026 (visual,
cores, ícones, textos e anatomia), adaptado ao KRONOS na versão 1.1.0:
  - idioma pelas mesmas bandeiras do painel (`/i18n/i18n.js`, Google Tradutor, sem dicionário);
  - tooltip pelo componente canônico (`/dica/dica.css` e `/dica/dica.js`, copiados sem edição);
  - saúde do backend em `/api/status` (o KRONOS não tem `/q/health`) e sem rota de login;
  - textos corrigidos onde descreviam algo que o KRONOS não faz (lista em docs/arquitetura.md, seção "Páginas de erro").

INVARIANTES DO DOMÍNIO:
  - toda página carrega o mesmo CSS/JS do kit, o tooltip canônico e o módulo de idioma do painel
    com a MESMA versão que o app.js importa (VERSAO_I18N, conferida pela catraca);
  - nenhum atributo de dicionário (a catraca de i18n reprova `data-i18n=`);
  - todo tooltip tem id único na página, `role="tooltip"` e o controle aponta para ele com
    `aria-describedby`; texto não focável ganha o gatilho "?";
  - valores técnicos do diagnóstico, código e marca levam `translate="no"`.

COMPORTAMENTO EM CASO DE FALHA: código sem ícone ou família conhecidos levanta KeyError ANTES de
gravar qualquer arquivo (tudo é montado em memória e só então escrito). Uso:
    python ferramentas/paginas-de-erro/gerar.py
"""
import html
import json
import os

RAIZ = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
SAIDA = os.path.join(RAIZ, "src", "main", "resources", "static", "errors")
VERSION = "1.2.0"
VERSAO_I18N = "2.2"      # tem de ser a mesma do import em static/js/app.js
VERSAO_CSS_I18N = "1.3"  # tem de ser a mesma do <link> em static/index.html
AUTHOR = "Paulo André Carminati"
SITE = "https://carminati.dev.br/"  # página do autor (Paulo, 14/10/2026)
REPO = "https://github.com/carmipa/traducao_animes_llm_local_quarkus"

# ---------------------------------------------------------------------------
# ÍCONES (traço 1.8, viewBox 24) — do kit v1.0.0, sem alteração
# ---------------------------------------------------------------------------
def svg(inner, vb="0 0 24 24"):
    return ('<svg viewBox="%s" fill="none" stroke="currentColor" stroke-width="1.8" '
            'stroke-linecap="round" stroke-linejoin="round" aria-hidden="true" focusable="false">%s</svg>' % (vb, inner))

ICONS = {
    "code-x":    svg('<polyline points="8 7 4 12 8 17"/><polyline points="16 7 20 12 16 17"/><line x1="10" y1="16" x2="14" y2="8"/>'),
    "key":       svg('<circle cx="8" cy="15" r="4"/><path d="M10.8 12.2 20 3"/><path d="M17 6l3 3"/><path d="M14 9l2 2"/>'),
    "lock":      svg('<rect x="4" y="11" width="16" height="10" rx="2"/><path d="M8 11V7a4 4 0 0 1 8 0v4"/><circle cx="12" cy="16" r="1" fill="currentColor"/>'),
    "search-x":  svg('<circle cx="11" cy="11" r="7"/><line x1="21" y1="21" x2="16" y2="16"/><line x1="9" y1="9" x2="13" y2="13"/><line x1="13" y1="9" x2="9" y2="13"/>'),
    "ban":       svg('<circle cx="12" cy="12" r="9"/><line x1="5.6" y1="5.6" x2="18.4" y2="18.4"/>'),
    "clock":     svg('<circle cx="12" cy="12" r="9"/><polyline points="12 7 12 12 15.5 14"/>'),
    "merge":     svg('<circle cx="6" cy="5" r="2"/><circle cx="18" cy="5" r="2"/><circle cx="12" cy="19" r="2"/><path d="M6 7v2a4 4 0 0 0 4 4h4a4 4 0 0 0 4-4V7"/><path d="M12 13v4"/>'),
    "archive-x": svg('<rect x="3" y="4" width="18" height="5" rx="1"/><path d="M5 9v10a1 1 0 0 0 1 1h12a1 1 0 0 0 1-1V9"/><line x1="10" y1="13" x2="14" y2="17"/><line x1="14" y1="13" x2="10" y2="17"/>'),
    "box":       svg('<path d="M21 8l-9-5-9 5v8l9 5 9-5z"/><path d="M3 8l9 5 9-5"/><line x1="12" y1="13" x2="12" y2="21"/>'),
    "file-q":    svg('<path d="M14 3H7a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h10a2 2 0 0 0 2-2V8z"/><polyline points="14 3 14 8 19 8"/><path d="M10.3 13.3a1.9 1.9 0 1 1 2.6 1.7c-.6.3-.9.7-.9 1.4"/><circle cx="12" cy="18.7" r=".7" fill="currentColor"/>'),
    "clip-x":    svg('<rect x="6" y="4" width="12" height="17" rx="2"/><path d="M9 4V3a1 1 0 0 1 1-1h4a1 1 0 0 1 1 1v1"/><line x1="10" y1="11" x2="14" y2="15"/><line x1="14" y1="11" x2="10" y2="15"/>'),
    "activity":  svg('<polyline points="3 12 7 12 10 5 14 19 17 12 21 12"/>'),
    "server":    svg('<rect x="3" y="4" width="18" height="6" rx="1.5"/><rect x="3" y="14" width="18" height="6" rx="1.5"/><circle cx="7" cy="7" r=".9" fill="currentColor"/><circle cx="7" cy="17" r=".9" fill="currentColor"/><path d="M17 6v2M17 16v2"/>'),
    "cpu":       svg('<rect x="6" y="6" width="12" height="12" rx="2"/><rect x="10" y="10" width="4" height="4"/><path d="M9 2v4M15 2v4M9 18v4M15 18v4M2 9h4M2 15h4M18 9h4M18 15h4"/>'),
    "wrench":    svg('<path d="M14.7 6.3a1 1 0 0 0 0 1.4l1.6 1.6a1 1 0 0 0 1.4 0l3.77-3.77a6 6 0 0 1-7.94 7.94l-6.91 6.91a2.12 2.12 0 0 1-3-3l6.91-6.91a6 6 0 0 1 7.94-7.94l-3.76 3.76z"/>'),
    "hourglass": svg('<path d="M6 3h12"/><path d="M6 21h12"/><path d="M8 3v3.5a4 4 0 0 0 1.6 3.2L12 12l-2.4 2.3A4 4 0 0 0 8 17.5V21"/><path d="M16 3v3.5a4 4 0 0 1-1.6 3.2L12 12l2.4 2.3a4 4 0 0 1 1.6 3.2V21"/>'),
    "wifi-off":  svg('<line x1="2" y1="2" x2="22" y2="22"/><path d="M8.5 16.5a5 5 0 0 1 7 0"/><path d="M5 12.5a10 10 0 0 1 5.2-2.7"/><path d="M12 6a16 16 0 0 1 10 3.5"/><path d="M2 9.5a16 16 0 0 1 4-2.4"/><circle cx="12" cy="20" r=".9" fill="currentColor"/>'),
    "bug":       svg('<path d="M8 2l1.9 1.9M16 2l-1.9 1.9"/><path d="M9 7.1V6a3 3 0 0 1 6 0v1.1"/><rect x="6" y="7" width="12" height="13" rx="6"/><path d="M12 12v8"/><path d="M6 13H2M22 13h-4"/><path d="M6 17l-3 2M18 17l3 2"/><path d="M6 9L3 7M18 9l3-2"/>'),
    # ícones de interface
    "home":      svg('<path d="M3 11l9-8 9 8"/><path d="M5 10v10a1 1 0 0 0 1 1h4v-6h4v6h4a1 1 0 0 0 1-1V10"/>'),
    "back":      svg('<line x1="19" y1="12" x2="5" y2="12"/><polyline points="12 19 5 12 12 5"/>'),
    "reload":    svg('<path d="M21 12a9 9 0 1 1-2.6-6.4"/><polyline points="21 3 21 9 15 9"/>'),
    "login":     svg('<path d="M15 3h4a2 2 0 0 1 2 2v14a2 2 0 0 1-2 2h-4"/><polyline points="10 17 15 12 10 7"/><line x1="15" y1="12" x2="3" y2="12"/>'),
    "chart":     svg('<path d="M3 3v18h18"/><path d="M7 15l4-5 3 3 5-7"/>'),
    "copy":      svg('<rect x="9" y="9" width="12" height="12" rx="2"/><path d="M5 15H4a2 2 0 0 1-2-2V4a2 2 0 0 1 2-2h9a2 2 0 0 1 2 2v1"/>'),
    "play":      svg('<polygon points="6 4 20 12 6 20 6 4"/>'),
    "pause":     svg('<rect x="6" y="4" width="4" height="16"/><rect x="14" y="4" width="4" height="16"/>'),
    "bulb":      svg('<path d="M9 18h6"/><path d="M10 22h4"/><path d="M12 2a7 7 0 0 0-4 12.7c.6.5 1 1.3 1 2.1V18h6v-1.2c0-.8.4-1.6 1-2.1A7 7 0 0 0 12 2z"/>'),
    "github":    svg('<path d="M9 19c-5 1.5-5-2.5-7-3m14 6v-3.9a3.4 3.4 0 0 0-.9-2.6c3.1-.3 6.4-1.5 6.4-7A5.4 5.4 0 0 0 20 4.8 5 5 0 0 0 19.9 1s-1.2-.3-3.9 1.5a13.4 13.4 0 0 0-7 0C6.3.7 5.1 1 5.1 1A5 5 0 0 0 5 4.8a5.4 5.4 0 0 0-1.5 3.8c0 5.5 3.3 6.7 6.4 7a3.4 3.4 0 0 0-.9 2.6V22"/>'),
}

LOGO = ('<svg viewBox="0 0 48 48" aria-hidden="true" focusable="false">'
        '<circle cx="24" cy="24" r="21" fill="#0f1520" stroke="#263040" stroke-width="2"/>'
        '<circle cx="24" cy="24" r="16" fill="#101a2a" stroke="#2dd4bf" stroke-opacity=".55" stroke-width="1.5"/>'
        '<path d="M24 13.5 33.5 31.5h-19z" fill="none" stroke="#e6edf5" stroke-width="2.4" stroke-linejoin="round"/>'
        '<path d="M24 21 28.6 29.5h-9.2z" fill="#2dd4bf"/></svg>')

# O "?" do componente canônico de tooltip (tags/dicaAjuda.html), sem alteração.
INTERROGACAO = ('<svg viewBox="0 0 24 24" aria-hidden="true" focusable="false"><circle cx="12" cy="12" r="10" '
                'fill="none" stroke="currentColor" stroke-width="2"/><path d="M9.5 9a2.5 2.5 0 1 1 3.5 2.3c-.7.4-1 '
                '.9-1 1.7M12 17h.01" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round"/></svg>')

# ---------------------------------------------------------------------------
# TEXTOS COMUNS DA INTERFACE (pt-BR; os outros idiomas vêm do Google Tradutor)
# ---------------------------------------------------------------------------
UI = {
    "brand.sub": "Pipeline Industrial",
    "aria.lang": "Idioma da página", "aria.actions": "Ações",
    "tip.brand": "Voltar ao Painel Inicial do KRONOS CORE",
    "tip.clock": "Data e hora locais do seu navegador",
    "status.wait": "Verificando backend…", "status.ok": "Backend online", "status.bad": "Backend inacessível",
    "tip.status.wait": "Consultando o estado do orquestrador Quarkus (/api/status)…",
    "tip.status.ok": "O orquestrador Java Quarkus respondeu à consulta de estado",
    "tip.status.bad": "O orquestrador não respondeu à consulta de estado (/api/status)",
    "btn.home": "Painel Inicial", "btn.back": "Voltar", "btn.reload": "Recarregar", "btn.retry": "Tentar novamente",
    "btn.telemetry": "Ver telemetria", "btn.copy": "Copiar diagnóstico", "btn.copied": "Copiado!",
    "btn.now": "Tentar agora", "btn.pause": "Pausar", "btn.resume": "Retomar",
    "tip.home": "Ir para o Painel Inicial do pipeline",
    "tip.back": "Retornar à tela anterior do navegador",
    "tip.reload": "Recarregar esta página e tentar de novo",
    "tip.retry": "Verificar o backend e voltar à rota de origem se ele responder",
    "tip.telemetry": "Abrir o painel de telemetria (operações, cache e tokens)",
    "tip.copy": "Copiar todos os campos do diagnóstico para a área de transferência",
    "tip.now": "Pular a contagem e verificar o backend imediatamente",
    "tip.pause": "Interromper as tentativas automáticas de reconexão",
    "tip.resume": "Retomar as tentativas automáticas de reconexão",
    "diag.title": "Diagnóstico", "tip.diag.title": "Informações técnicas desta ocorrência — úteis para suporte e logs",
    "diag.status": "Status HTTP", "tip.diag.status": "Código e descrição do status HTTP devolvido pelo servidor",
    "diag.ref": "Referência", "tip.diag.ref": "Código interno desta página de erro no KRONOS CORE",
    "diag.rid": "Request ID", "tip.diag.rid": "Identificador desta ocorrência — o mesmo aparece no log do servidor",
    "diag.time": "Horário (UTC)", "tip.diag.time": "Momento em que a página de erro foi exibida, em UTC (ISO 8601)",
    "diag.route": "Rota", "tip.diag.route": "Endereço que você tentou acessar quando o erro ocorreu",
    "diag.origin": "Origem", "tip.diag.origin": "Página que levou você até esta rota (referrer)",
    "diag.ua": "Navegador", "tip.diag.ua": "Navegador e sistema operacional detectados",
    "diag.lang": "Idioma", "tip.diag.lang": "Idioma em que a página está sendo exibida",
    "diag.detail": "Detalhe", "tip.diag.detail": "Detalhe técnico adicional recebido no endereço desta página",
    "retry.in": "Nova tentativa automática em", "retry.unit": "s",
    "retry.paused": "Tentativas automáticas pausadas",
    "retry.checking": "Verificando o backend…",
    "retry.online": "Backend respondeu — redirecionando…",
    "retry.exhausted": "Tentativas automáticas esgotadas. Use “Tentar agora” ou volte ao painel.",
    "foot.by": "Desenvolvido por", "foot.repo": "Quarkus Repo", "foot.page": "Página de erro v" + VERSION,
    "tip.foot.repo": "Abrir o repositório do projeto no GitHub",
    "tip.foot.autor": "Abre carminati.dev.br, a página do autor, em uma nova aba",
    "tip.foot.ref": "Versão das páginas de erro e código de referência desta tela",
    "tip.hint": "Sugestão de ação para resolver ou contornar o problema",
    "ajuda": "Ajuda sobre",
}

# As bandeiras ficam fora da tradução (translate="no"): cada uma fala no próprio idioma, como no painel.
BANDEIRAS = [
    ("pt-BR", "br", "Português (Brasil)", "Exibir esta página em português (Brasil)"),
    ("en-US", "us", "English (United States)", "Show this page in English (United States)"),
    ("es-ES", "es", "Español (España)", "Mostrar esta página en español (España)"),
]

FAMILY = {
    "nav": "Navegação", "val": "Validação", "acc": "Acesso",
    "lim": "Limites & Tempo", "srv": "Servidor", "cli": "Cliente",
}

# ---------------------------------------------------------------------------
# CATÁLOGO DE ERROS
#   display: texto grande | mark: marca d'água | http: linha de status
#   color: destaque (espelha o CSS) | icon | family | actions | retry
# ---------------------------------------------------------------------------
def E(code, display, mark, http, color, icon, family, actions, retry, texto):
    return dict(code=code, display=display, mark=mark, http=http, color=color, icon=icon,
                family=family, actions=actions, retry=retry, texto=texto)


def T(title, desc, hint, codeTip, titleTip):
    return dict(title=title, desc=desc, hint=hint, codeTip=codeTip, titleTip=titleTip)


CATALOG = [
    E("400", "400", "400", "400 Bad Request", "#a3e635", "code-x", "val", ["back", "home", "telemetry"], False,
      T("Requisição inválida",
        "O orquestrador não conseguiu interpretar esta requisição. Algum parâmetro, cabeçalho ou corpo chegou malformado antes mesmo de ser processado pelo pipeline.",
        "Volte ao painel de origem e refaça a operação. Se o erro persistir, copie o diagnóstico e verifique o formato dos dados enviados.",
        "Código HTTP 400 — o servidor rejeitou a sintaxe da requisição", "O que aconteceu nesta requisição")),

    E("401", "401", "401", "401 Unauthorized", "#f43f5e", "key", "acc", ["home", "back"], False,
      T("Autenticação necessária",
        "Esta requisição chegou sem credenciais aceitas. O KRONOS CORE local não tem login: isso só acontece quando há um proxy com autenticação na frente dele.",
        "Se você acessa por um proxy com autenticação, entre por ele e abra o painel de novo. Na instalação local, volte ao Painel Inicial.",
        "Código HTTP 401 — a requisição não trouxe credenciais válidas", "Por que o acesso foi interrompido")),

    E("403", "403", "403", "403 Forbidden", "#ec4899", "lock", "acc", ["home", "back", "telemetry"], False,
      T("Acesso negado",
        "O servidor entendeu a requisição, mas recusou atendê-la — por exemplo, uma pasta fora das raízes que o navegador de pastas pode listar. Nenhuma etapa do pipeline foi executada.",
        "Escolha a pasta pelo botão Procurar do painel, dentro das raízes configuradas em navegador.raizes, e repita a operação.",
        "Código HTTP 403 — o servidor entendeu a requisição, mas recusou atendê-la", "Por que o acesso foi recusado")),

    # Só a vitrine pública serve esta página: é a recusa do filtro da vitrine para quem NAVEGA até
    # uma função (rota de /api, /mcp, /q) ou um caminho ambíguo. Endereço que não existe recebe o 404.
    E("vitrine", "403", "403", "403 Forbidden", "#38bdf8", "lock", "acc", ["home", "back"], False,
      T("Só na instalação local",
        "Este endereço não está aberto na vitrine pública do KRONOS CORE. A vitrine mostra as telas para você conhecer o sistema, mas nenhuma função roda aqui: tradução, revisão, gravação e leitura de pastas só acontecem na instalação local. Nada foi executado.",
        "Volte às telas para continuar conhecendo o KRONOS CORE. Para usar as funções, instale o KRONOS CORE na sua máquina — o passo a passo está no repositório do projeto.",
        "Código HTTP 403 — a vitrine recusa toda função; só as telas são públicas", "Por que a vitrine recusou")),

    E("404", "404", "404", "404 Not Found", "#2dd4bf", "search-x", "nav", ["home", "back", "telemetry"], False,
      T("Página não encontrada",
        "O painel, rota ou arquivo que você procura não existe nesta versão do pipeline. Ele pode ter sido movido, renomeado durante uma etapa de finalização ou nunca ter existido.",
        "Confira o endereço digitado ou navegue pelo Painel Inicial — todas as etapas do fluxo de trabalho estão listadas lá.",
        "Código HTTP 404 — o servidor não encontrou nada neste endereço", "O que não foi encontrado")),

    E("405", "405", "405", "405 Method Not Allowed", "#10b981", "ban", "val", ["back", "home"], False,
      T("Método não permitido",
        "A rota existe, mas não aceita o método HTTP utilizado — por exemplo, um POST em um endpoint que só responde a GET, ou um GET em uma ação de processamento.",
        "Use os botões e formulários do painel em vez de chamar a rota diretamente. Para integrações, consulte a documentação da API.",
        "Código HTTP 405 — o verbo HTTP não é aceito por esta rota", "Qual foi o problema com o método")),

    E("408", "408", "408", "408 Request Timeout", "#f59e0b", "clock", "lim", ["retry", "home"], True,
      T("Tempo de requisição esgotado",
        "O servidor esperou demais pela requisição completa e encerrou a conexão. Redes lentas, uploads interrompidos ou abas em segundo plano costumam causar isso.",
        "Verifique a conexão com o servidor local e tente novamente. A página fará uma nova tentativa automática quando o backend responder.",
        "Código HTTP 408 — o servidor desistiu de esperar a requisição chegar", "O que expirou")),

    E("409", "409", "409", "409 Conflict", "#fb923c", "merge", "val", ["reload", "back", "home"], False,
      T("Conflito de estado",
        "A operação colide com o estado atual do recurso — por exemplo, uma legenda que já está em processamento, um lote já remuxado ou um arquivo com o mesmo nome no cache local.",
        "Recarregue o painel para ver o estado mais recente antes de repetir a ação. Se o conflito for de cache, use a Correção de Cache (2.3).",
        "Código HTTP 409 — a requisição conflita com o estado atual do recurso", "Onde houve o conflito")),

    E("410", "410", "410", "410 Gone", "#67e8f9", "archive-x", "nav", ["home", "back"], False,
      T("Recurso removido",
        "Este recurso existiu, mas foi removido de forma permanente: um arquivo arquivado após a remuxagem, um cache purgado ou uma rota aposentada em versão anterior do pipeline.",
        "Não adianta tentar de novo — o endereço não voltará. Localize a versão atual do recurso pelo Painel Inicial ou pela telemetria.",
        "Código HTTP 410 — o recurso foi removido intencionalmente e em definitivo", "O que foi removido")),

    E("413", "413", "413", "413 Content Too Large", "#fde047", "box", "val", ["back", "home"], False,
      T("Arquivo muito grande",
        "O conteúdo enviado excede o limite configurado no servidor. Arquivos MKV completos não devem ser enviados por upload — eles são lidos diretamente pela Extração (1.2) a partir da pasta local.",
        "Envie apenas a legenda (ASS/SRT/PGS) ou aponte o pipeline para a pasta de origem. Para aumentar o limite, ajuste quarkus.http.limits.max-body-size.",
        "Código HTTP 413 — o corpo da requisição ultrapassa o tamanho máximo aceito", "O que ultrapassou o limite")),

    E("415", "415", "415", "415 Unsupported Media Type", "#86efac", "file-q", "val", ["back", "home"], False,
      T("Formato não suportado",
        "O tipo de arquivo ou de conteúdo enviado não é aceito por este endpoint. O pipeline trabalha com legendas ASS, SRT e PGS extraídas de contêineres MKV.",
        "Converta o arquivo para um formato suportado antes de enviar de novo; a Extração (1.2) já entrega ASS, SRT ou PGS a partir do MKV.",
        "Código HTTP 415 — o Content-Type da requisição não é suportado", "Qual formato foi recusado")),

    E("422", "422", "422", "422 Unprocessable Content", "#e879f9", "clip-x", "val", ["back", "home"], False,
      T("Dados não processáveis",
        "A requisição está bem formada, mas os dados não passaram na validação: campos obrigatórios ausentes, faixas de legenda inválidas ou metadados inconsistentes com o arquivo de mídia.",
        "Revise os campos destacados no formulário de origem. A Análise de Mídia (1.1) mostra as faixas e metadados reais do arquivo.",
        "Código HTTP 422 — sintaxe correta, mas conteúdo semanticamente inválido", "Quais dados falharam na validação")),

    E("429", "429", "429", "429 Too Many Requests", "#ea580c", "activity", "lim", ["retry", "home"], True,
      T("Muitas requisições",
        "Você ultrapassou o limite de requisições em um curto período. Quem está na frente do KRONOS CORE está protegendo o serviço contra sobrecarga.",
        "Aguarde a contagem abaixo — ela respeita o intervalo informado pelo servidor — e evite disparar vários lotes em paralelo.",
        "Código HTTP 429 — limite de taxa (rate limit) atingido", "Por que o ritmo foi limitado")),

    E("500", "500", "500", "500 Internal Server Error", "#ef4444", "server", "srv", ["retry", "home", "telemetry"], True,
      T("Erro interno do servidor",
        "O orquestrador Java Quarkus encontrou uma falha inesperada ao processar esta requisição. O erro foi registrado no log do servidor com o Request ID abaixo.",
        "Tente novamente em instantes. Se o problema continuar, procure o Request ID no log da execução (logs/execucoes/) e informe-o.",
        "Código HTTP 500 — exceção não tratada no servidor", "O que falhou no servidor")),

    E("502", "502", "502", "502 Bad Gateway", "#8b5cf6", "cpu", "srv", ["retry", "home", "telemetry"], True,
      T("KRONOS CORE indisponível",
        "O proxy na frente do KRONOS CORE não obteve uma resposta válida dele. O serviço pode estar reiniciando, subindo ou fora do ar.",
        "Confirme que o KRONOS CORE está em execução e que a porta configurada responde. A página tentará reconectar automaticamente.",
        "Código HTTP 502 — resposta inválida do serviço por trás do proxy", "Qual serviço não respondeu")),

    E("503", "503", "503", "503 Service Unavailable", "#3b82f6", "wrench", "srv", ["retry", "home"], True,
      T("Serviço indisponível",
        "O KRONOS CORE está reiniciando ou temporariamente sobrecarregado e não atendeu esta requisição.",
        "Nenhuma ação é necessária: a página verificará o backend periodicamente e voltará ao painel assim que ele responder.",
        "Código HTTP 503 — o servidor está indisponível de forma temporária", "Por que o serviço está parado")),

    E("504", "504", "504", "504 Gateway Timeout", "#a5b4fc", "hourglass", "srv", ["retry", "home", "telemetry"], True,
      T("Tempo limite do gateway",
        "O KRONOS CORE demorou demais para responder e o proxy na frente dele encerrou a espera.",
        "Tente de novo em instantes. Um trabalho já enfileirado no pipeline continua no servidor — confira o andamento no painel.",
        "Código HTTP 504 — o serviço por trás do proxy não respondeu a tempo", "O que demorou demais")),

    E("offline", "OFFLINE", "OFF", "— (sem conexão)", "#0ea5e9", "wifi-off", "cli", ["retry", "home"], True,
      T("Sem conexão com o servidor",
        "Seu navegador perdeu a conexão com o KRONOS CORE. Isso costuma acontecer quando o backend Quarkus foi encerrado, a rede local caiu ou o computador entrou em suspensão.",
        "Verifique se o backend ainda está em execução e se a rede está ativa. Assim que a conexão voltar, esta página retomará o painel sozinha.",
        "Código interno do KRONOS CORE — não é um status HTTP", "O que foi perdido")),

    E("crash", "FALHA", "ERR", "— (erro de interface)", "#e2e8f0", "bug", "cli", ["reload", "home", "telemetry"], False,
      T("Falha na interface",
        "A interface encontrou um erro inesperado de JavaScript e não conseguiu continuar. O estado do pipeline no servidor não foi afetado — apenas esta tela.",
        "Recarregue a página. Se a falha se repetir, copie o diagnóstico e abra o console do navegador (F12) para localizar o erro.",
        "Código interno do KRONOS CORE — erro não tratado no navegador", "O que quebrou na interface")),
]

for e in CATALOG:
    prefixo = "HTTP " + e["display"] if e["code"] not in ("offline", "crash") else "KRONOS"
    e["texto"]["eyebrow"] = prefixo + " · " + FAMILY[e["family"]]


def esc(s):
    return html.escape(s, quote=True)


# ---------------------------------------------------------------------------
# PEÇAS DO TOOLTIP CANÔNICO
# ---------------------------------------------------------------------------
def dica_controle(controle, ref, texto):
    """Padrão 1 (elemento focável): o controle já traz aria-describedby="ref"."""
    return '<span class="dica">%s<span id="%s" role="tooltip" class="dica__texto">%s</span></span>' % (
        controle, ref, esc(texto))


def dica_ajuda(ref, rotulo, texto):
    """Padrão 3 (texto não focável): gatilho "?" com aria-label "Ajuda sobre <rótulo>"."""
    return ('<span class="dica"><button type="button" class="dica__gatilho" aria-label="%s %s" '
            'aria-describedby="%s">%s</button><span id="%s" role="tooltip" class="dica__texto">%s</span></span>'
            % (esc(UI["ajuda"]), esc(rotulo), ref, INTERROGACAO, ref, esc(texto)))


BTN_ICON = {"home": "home", "back": "back", "reload": "reload", "retry": "reload", "telemetry": "chart"}
BTN_HREF = {"home": "/", "back": "#", "reload": "#", "retry": "#", "telemetry": "/#telemetria"}
TODAS_ACOES = ("home", "back", "reload", "retry", "telemetry")


def acao_html(a, classe, oculta=False):
    controle = '<a class="kc-btn %s" href="%s" data-action="%s" aria-describedby="d-act-%s">%s<span>%s</span></a>' % (
        classe, BTN_HREF[a], a, a, ICONS[BTN_ICON[a]], esc(UI["btn." + a]))
    bloco = dica_controle(controle, "d-act-" + a, UI["tip." + a])
    if oculta:
        bloco = bloco.replace('<span class="dica">', '<span class="dica" hidden>', 1)
    return "      " + bloco


def acoes_html(acoes):
    saida = []
    for i, a in enumerate(acoes):
        classe = "kc-btn--primary" if i == 0 else ("kc-btn--link" if a == "telemetry" else "kc-btn--ghost")
        saida.append(acao_html(a, classe))
    return "\n".join(saida)


def todas_acoes_html():
    # página dinâmica: todos os botões presentes; o JS mostra os do erro pedido, na ordem do catálogo
    return "\n".join(acao_html(a, "kc-btn--link" if a == "telemetry" else "kc-btn--ghost", oculta=True)
                     for a in TODAS_ACOES)


def retry_html():
    agora = '<button type="button" class="kc-icon-btn" data-action="retry-now" aria-describedby="d-retry-now">%s<span>%s</span></button>' % (
        ICONS["play"], esc(UI["btn.now"]))
    pausa = '<button type="button" class="kc-icon-btn" data-action="retry-toggle" aria-describedby="d-retry-toggle">%s<span data-kc-rotulo>%s</span></button>' % (
        ICONS["pause"], esc(UI["btn.pause"]))
    return ('    <div class="kc-retry" hidden role="status" aria-live="polite">\n'
            '      <p data-retry="msg">—</p>\n'
            '      <div class="kc-bar" aria-hidden="true"><i></i></div>\n'
            '      <div class="kc-retry-actions">\n'
            '        %s\n        %s\n'
            '      </div>\n'
            '    </div>\n') % (dica_controle(agora, "d-retry-now", UI["tip.now"]),
                              dica_controle(pausa, "d-retry-toggle", UI["tip.pause"]))


def diag_row(key, dd_inner=None):
    dd = dd_inner if dd_inner is not None else '<dd data-diag="%s" translate="no">—</dd>' % key
    return '      <dt>%s %s</dt>\n      %s' % (esc(UI["diag." + key]),
                                             dica_ajuda("d-diag-" + key, UI["diag." + key], UI["tip.diag." + key]), dd)


def bandeiras_html():
    botoes = []
    for idioma, pais, nome, dica in BANDEIRAS:
        controle = ('<button type="button" class="seletor-idioma-botao" data-idioma="%s" aria-label="%s" '
                    'aria-pressed="false" aria-describedby="d-lang-%s"><img src="/i18n/flags/%s.svg" width="26" '
                    'height="18" alt="" aria-hidden="true" draggable="false"></button>') % (idioma, esc(nome), pais, pais)
        botoes.append("      " + dica_controle(controle, "d-lang-" + pais, dica))
    return "\n".join(botoes)


def pagina(e, dinamica=False):
    t = e["texto"]
    code = e["code"]
    data_attr = 'data-dynamic="true" data-code="500"' if dinamica else 'data-code="%s"' % code
    display = "ERRO" if dinamica else e["display"]
    mark = "ERR" if dinamica else e["mark"]
    titulo_aba = "Erro · KRONOS CORE" if dinamica else "%s · %s · KRONOS CORE" % (e["display"], t["title"])
    icone = ICONS["server"] if dinamica else ICONS[e["icon"]]
    acoes = todas_acoes_html() if dinamica else acoes_html(e["actions"])
    retry = retry_html() if (dinamica or e["retry"]) else ""
    comentario = ("Página dinâmica: /errors/error.html?code=404&rid=...&from=/rota&retry=30&detail=..."
                  if dinamica else "Página do erro %s" % e["display"])
    h_title = "Ocorreu um erro" if dinamica else t["title"]
    desc = "Carregando os detalhes do erro…" if dinamica else t["desc"]
    hint = "" if dinamica else t["hint"]
    code_tip = "Código do erro desta página" if dinamica else t["codeTip"]
    title_tip = "O que aconteceu" if dinamica else t["titleTip"]
    eyebrow = "KRONOS CORE · Erro" if dinamica else t["eyebrow"]

    linhas = "\n".join([
        diag_row("status", '<dd translate="no"><span class="kc-pill" data-diag="status">%s</span></dd>'
                 % esc("—" if dinamica else e["http"])),
        diag_row("ref", '<dd data-diag="ref" translate="no">KC-%s</dd>' % ("—" if dinamica else e["display"])),
        diag_row("rid"), diag_row("time"), diag_row("route"), diag_row("origin"), diag_row("ua"),
        diag_row("lang", '<dd data-diag="lang" translate="no">pt-BR</dd>'),
    ])
    detalhe = '      <div data-diag-row hidden>\n' + diag_row("detail") + '\n      </div>'

    marca = ('<a class="kc-brand" href="/" data-action="home" aria-describedby="d-brand">%s'
             '<span><b translate="no">KRONOS CORE</b><small>%s</small></span></a>') % (LOGO, esc(UI["brand.sub"]))
    copiar = ('<button type="button" class="kc-icon-btn" data-action="copy" aria-describedby="d-copy">%s'
              '<span data-kc-rotulo>%s</span></button>') % (ICONS["copy"], esc(UI["btn.copy"]))
    repo = ('<a class="kc-gh" href="%s" data-action="repo" target="_blank" rel="noopener" aria-describedby="d-repo">%s'
            '<span translate="no">%s</span></a>') % (REPO, ICONS["github"], esc(UI["foot.repo"]))

    return '''<!DOCTYPE html>
<html lang="pt-BR" %(data_attr)s>
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<meta name="robots" content="noindex, nofollow">
<meta name="kc-rid" content="">
<title>%(titulo_aba)s</title>
<link rel="stylesheet" href="/dica/dica.css">
<link rel="stylesheet" href="/i18n/i18n.css?v=%(v_css_i18n)s">
<link rel="stylesheet" href="/errors/assets/kronos-error.css?v=%(v)s">
<!-- %(comentario)s. Gerada por ferramentas/paginas-de-erro/gerar.py: não edite à mão. -->
<script>window.KC_CONFIG = { home: '/', telemetry: '/#telemetria', health: '/api/status', repo: '%(repo_url)s' };</script>
</head>
<body class="kc-error">
<div class="kc-bg" aria-hidden="true"><p class="kc-watermark" translate="no">%(mark)s</p></div>

<header class="kc-top">
  %(marca)s
  <div class="kc-tools">
    <div class="seletor-idioma kc-lang" role="group" aria-label="%(aria_lang)s" translate="no">
%(bandeiras)s
    </div>
    <div class="kc-status" data-state="wait" role="status" aria-live="polite"><i></i><span>%(status_wait)s</span></div>
    %(dica_status)s
    <time class="kc-clock" translate="no">—</time>
    %(dica_relogio)s
  </div>
</header>

<main class="kc-main">
  <section class="kc-hero">
    <div class="kc-eyebrow">
      <div class="kc-tile" aria-hidden="true">%(icone)s</div>
      <span data-kc-campo="eyebrow">%(eyebrow)s</span>
    </div>
    <h1 class="kc-code"><span data-kc-campo="display" translate="no">%(display)s</span> %(dica_codigo)s</h1>
    <h2 class="kc-title"><span data-kc-campo="title">%(h_title)s</span> %(dica_titulo)s</h2>
    <p class="kc-desc" data-kc-campo="desc">%(desc)s</p>
    <p class="kc-hint">%(bulb)s<span data-kc-campo="hint">%(hint)s</span> %(dica_sugestao)s</p>
    <nav class="kc-actions" aria-label="%(aria_actions)s">
%(acoes)s
    </nav>
  </section>

  <aside class="kc-diag" aria-labelledby="kc-diag-title">
    <header>
      <h2><span id="kc-diag-title">%(diag_title)s</span> %(dica_diag)s</h2>
      %(copiar)s
    </header>
    <dl>
%(linhas)s
%(detalhe)s
    </dl>
%(retry)s  </aside>
</main>

<footer class="kc-foot">
  <div class="kc-by"><span>%(foot_by)s</span> %(autor)s
    %(repo)s
  </div>
  <div class="kc-ref"><span translate="no">KRONOS CORE ·</span> <span>%(foot_page)s</span> <span translate="no">· <span data-diag="ref">KC-%(ref)s</span></span> %(dica_ref)s</div>
</footer>

<div id="google_translate_element" class="notranslate" translate="no" hidden></div>
<script src="/errors/assets/kronos-error-catalog.js?v=%(v)s"></script>
<script src="/errors/assets/kronos-error.js?v=%(v)s"></script>
<script src="/dica/dica.js"></script>
<script type="module">import { inicializarI18n } from '/i18n/i18n.js?v=%(v_i18n)s'; inicializarI18n();</script>
</body>
</html>
''' % dict(
        data_attr=data_attr, titulo_aba=esc(titulo_aba), v=VERSION, v_i18n=VERSAO_I18N, v_css_i18n=VERSAO_CSS_I18N,
        comentario=comentario, repo_url=REPO, mark=mark, marca=dica_controle(marca, "d-brand", UI["tip.brand"]),
        aria_lang=esc(UI["aria.lang"]), bandeiras=bandeiras_html(), status_wait=esc(UI["status.wait"]),
        dica_status=dica_ajuda("d-status", "o estado do backend", UI["tip.status.wait"]),
        dica_relogio=dica_ajuda("d-clock", "o relógio", UI["tip.clock"]),
        icone=icone, eyebrow=esc(eyebrow), display=display,
        dica_codigo=dica_ajuda("d-code", "o código do erro", code_tip),
        h_title=esc(h_title), dica_titulo=dica_ajuda("d-title", "o título", title_tip),
        desc=esc(desc), bulb=ICONS["bulb"], hint=esc(hint),
        dica_sugestao=dica_ajuda("d-hint", "a sugestão", UI["tip.hint"]),
        aria_actions=esc(UI["aria.actions"]), acoes=acoes,
        diag_title=esc(UI["diag.title"]), dica_diag=dica_ajuda("d-diag-title", UI["diag.title"], UI["tip.diag.title"]),
        copiar=dica_controle(copiar, "d-copy", UI["tip.copy"]), linhas=linhas, detalhe=detalhe, retry=retry,
        foot_by=esc(UI["foot.by"]), repo=dica_controle(repo, "d-repo", UI["tip.foot.repo"]),
        autor=dica_controle('<a class="kc-autor" href="%s" target="_blank" rel="noopener" aria-describedby="d-autor">'
                            '<b translate="no">%s</b></a>' % (SITE, esc(AUTHOR)), "d-autor", UI["tip.foot.autor"]),
        foot_page=esc(UI["foot.page"]), ref=("—" if dinamica else e["display"]),
        dica_ref=dica_ajuda("d-ref", "a referência da página", UI["tip.foot.ref"]),
    )


# ---------------------------------------------------------------------------
# SAÍDAS — montadas em memória; só se grava depois que todas existem
# ---------------------------------------------------------------------------
erros_js = {e["code"]: {k: e[k] for k in ("display", "mark", "http", "color", "icon", "family", "actions", "retry", "texto")}
            for e in CATALOG}
catalogo_js = (
    "/* KRONOS CORE — kronos-error-catalog.js v%s — DADOS das páginas de erro\n"
    "   Gerado por ferramentas/paginas-de-erro/gerar.py. Mude os textos no gerador e regere;\n"
    "   nunca aqui nem nos HTMLs (eles são renderizações deste catálogo). */\n"
    "window.KC_CATALOG = %s;\n"
) % (VERSION, json.dumps({"version": VERSION, "ui": UI, "family": FAMILY, "icons": ICONS, "errors": erros_js},
                         ensure_ascii=False, indent=1))

saidas = {os.path.join("assets", "kronos-error-catalog.js"): catalogo_js}
for e in CATALOG:
    saidas["%s.html" % e["code"]] = pagina(e)
saidas["error.html"] = pagina(next(e for e in CATALOG if e["code"] == "500"), dinamica=True)

for relativo, conteudo in saidas.items():
    destino = os.path.join(SAIDA, relativo)
    os.makedirs(os.path.dirname(destino), exist_ok=True)
    with open(destino, "w", encoding="utf-8", newline="\n") as f:
        f.write(conteudo)

print("ok:", len(CATALOG), "páginas + error.html + catalog.js em", os.path.relpath(SAIDA, RAIZ))
for e in CATALOG:
    print("  %-8s %-7s %s  %s" % (e["code"], e["color"], FAMILY[e["family"]].ljust(16), e["texto"]["title"]))
