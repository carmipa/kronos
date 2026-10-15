# 📐 Arquitetura do Sistema

[← Voltar ao README](../README.md) | [Instalação & Configuração →](instalacao.md)

---

## Visão Geral

O **KRONOS CORE** é uma plataforma monolítica modular construída sobre o **Quarkus** (usando as extensões de compatibilidade Spring — `quarkus-spring-di`, `quarkus-spring-web`, `quarkus-spring-boot-properties`), organizada em **fatias verticais** (*vertical slices*) sob `org.traducao.projeto.*` — hoje são **21 fatias funcionais**, cada uma resolvendo uma etapa específica do pipeline de tradução de legendas de anime (a 21ª, `vitrine`, é a [vitrine pública](ref-docker.md#vitrine-pública-vps)).

> **Os números desta página são contados, não estimados.** Medidos em **14/10/2026**:
>
> | o quê | quanto | como foi contado |
> |---|---|---|
> | fatias funcionais | **21** | 28 pacotes de topo sob `org.traducao.projeto`, menos 5 peers, `core` e `config` |
> | peers | **5** | `legenda` · `cachetraducao` · `lore` · `qualidadeTraducao` · `llm` |
> | classes / linhas em `src/main` | **514** / **79.459** | `find … -name '*.java'` |
> | classes / linhas em `src/test` | **485** / **90.765** | idem — o teste já pesa mais que o código |
> | rotas `/api` | **49** | 39 em **21** `@RestController` + 10 em **6** recursos JAX-RS (`@Path`) |
> | lore | **70** arquivos SQL, um por obra: **69** do lado da tradução, **69** do lado da revisão; **68** na lista da UI | `sem_lore` só existe na tradução e é oculta (`aparece_na_lista = 0`); `macross_dyrl` só existe na revisão — ver `lore/esquema.sql` |
> | guardas executáveis | **48** | 38 `Catraca*Test` + 10 `Fronteira*`, somando 189 testes — mais as 7 classes de guarda da vitrine |
> | suíte completa | **3.039** testes | `gradlew test --rerun-tasks`: 0 falhas, 70 pulados (medições condicionadas) |
>
> Divergência entre um número aqui e o código é bug de documentação, não licença poética.
> O número anterior desta página (72 lores) vinha de contagem de classes Java; a lore virou
> **dado** — arquivos SQL (`lore/esquema.sql`, `lore/obras.lst` e um `lore/obras/<id>.sql` por
> obra) que `lore.infrastructure.CatalogoLoreSqlite` carrega num SQLite em memória — e a contagem
> passou a ser feita nos arquivos e perguntada à UI em `/api/contextos`.

A arquitetura passou por uma refatoração longa (FASES A–I) que substituiu o antigo monólito de controllers por **fatias isoladas** cujas fronteiras são **congeladas por testes de fitness ArchUnit**. Duas categorias:

- **Fatias funcionais** — uma etapa do pipeline cada (ex.: `traducao`, `legendasExtracao`, `analisadorMidia`, `remuxer`, `revisaoLore`). Uma fatia funcional **não pode** depender de outra: o teste `FronteiraTraducaoArchTest` prova, a cada build, que a Tradução Local tem **ZERO** arestas de saída para outra fatia.
- **Peers** — bibliotecas internas *importáveis* por qualquer fatia, com a superfície pública **congelada por tipo exato**: `legenda` (modelo + I/O de `.ass`/`.srt`, 15 classes), `cachetraducao` (**dono único** do cache, 5 classes), `lore` (lore em 70 arquivos SQL — 69 obras por lado — + terminologia canônica, 24 classes), `qualidadeTraducao` (máscara de tags, validação anti-alucinação, 17 classes) e `llm` (contrato `LlmPort` neutro, 4 classes). Cada peer tem seu próprio `Fronteira<Peer>ArchTest`.

> **O peer `contexto` virou `lore`.** O guarda dele ainda se chama `FronteiraContextoArchTest` — o
> nome é legado, o alvo é `org.traducao.projeto.lore`. E o peso saiu do Java: as 93 classes de
> contexto viraram **24 classes + a lore como dado**: um `lore.yaml` de 15.101 linhas até
> 09/10/2026 e, desde então, arquivos SQL (`src/main/resources/lore/esquema.sql`, `obras.lst` e um
> `obras/<id>.sql` por obra) que `CatalogoLoreSqlite` carrega num SQLite em memória — o
> `lore.yaml` não existe mais. Ver
> [Contextos & Lore](modulo-contextos-lore.md) e [3.2 Revisão de Lore](etapa-3.2-revisao-lore.md).

Abaixo de tudo, `core` (fila de execução, I/O atômico, kernel web/SSE, mecânica de fronteira do formato ASS) e `config` (bootstrap de modo) são **infra transversal** — e o `core` é proibido, por regra permanente, de depender de qualquer fatia funcional.

### Dois tiers — e a página declara em qual cada fatia está

Estado real vence estado ideal. Hoje há **10 `Fronteira*ArchTest`**, e a leitura honesta da
contagem é esta: **5 congelam os peers**, **1 congela o formato ASS no `core`** (`FronteiraTermoAss`)
e **4 congelam fatias** (`traducao` com duas — saída e INBOUND —, `trocaTipoLegenda` e o corretor de
cache). As demais fatias **não têm guarda própria** — e isso não é dívida escondida, é o nível onde
a refatoração parou:

- **Tier GOLD** — zero aresta funcional de saída, portas próprias para tudo que sai, guarda de fronteira própria congelando cada tipo consumido. Hoje: `traducao` e os cinco peers.
- **Tier SECUNDÁRIO** — consome os peers pela superfície pública e depende direto de serviços comuns (telemetria, por exemplo), sem guarda própria. **Não é bug: é o nível onde parou.**

> Elevar **uma** fatia secundária ao gold a deixa mais desacoplada que as irmãs e não torna o
> sistema mais consistente. Se o objetivo for consistência, o certo é um Plano-Mestre que eleve
> o **tier inteiro** — não uma fatia avulsa porque ela chamou atenção.

### Navegação na SPA

O menu lateral agrupa os painéis em **6 grupos acordeão** que espelham o fluxo de trabalho, com numeração `GRUPO.POSIÇÃO`:

| Grupo | Painéis |
|-------|---------|
| Preparação | `1.1 Análise de Mídia` · `1.2 Extração` · `1.3 Troca de Tipo de Legenda` · `1.4 Análise de Legenda` |
| Tradução | `2.1 Tradução Local` · `2.2 Tradução sem Lore` · `2.3 Correção de Cache` |
| Qualidade | `3.1 Revisão de Legendas` · `3.2 Revisão de Lore` · `3.3 Revisão de Concordância` |
| Karaokê | `4.1 Karaokê` (tela única: traduz a letra e depois achata) · `4.2 Correção de Karaokê` |
| Finalização | `5.1 Remuxer` · `5.2 Renomear Arquivos` |
| Sistema | `Telemetria` · `Mapa do Projeto` · `Documentação` · `Sobre` — sem número, não são passos |

O menu não colore os grupos: o cabeçalho de grupo é só um rótulo. As cores dos diagramas desta
página são do desenho, não da tela. Os grupos são recolhíveis e o estado é lembrado por navegador (`localStorage`). A numeração é
cobrada por teste: no grupo de ordem G, o item de posição N tem de começar com `G.N` — ver
[Catracas e Fronteiras](catracas-e-fronteiras.md).

O desenho segue **Arquitetura Hexagonal (Ports & Adapters)** por módulo: cada pacote tem, tipicamente, `domain/` (modelos e portas), `application/` (casos de uso, orquestração), `infrastructure/` (adapters concretos — ffmpeg, mkvmerge, HTTP client do LM Studio, scraping do Google Translate) e `presentation/` (controllers REST e/ou CLI).

| Camada | Responsabilidade | Exemplos |
|--------|-------------------|----------|
| `presentation/` | Controllers REST (Spring-style) e telas CLI legadas | `TraducaoController`, `RevisaoLoreController`, `AnalisadorMidiaCLI` |
| `application/` | Casos de uso — orquestram domínio e adapters | `ProcessarArquivoUseCase`, `ExtrairLegendaUseCase` |
| `domain/` | Modelos, portas (interfaces), exceções de negócio | `LlmPort`, `AuditoriaResultado`, `LegendaInfo` |
| `infrastructure/` | Implementações concretas das portas | `LlmClientAdapter`, `MkvmergeAdapter`, `FfprobeAdapter` |

A aplicação roda **localmente** (`quarkus.http.host=127.0.0.1`) — não expõe porta na rede. O que sai da máquina é opcional e nomeado:

- **metadados de anime** — TMDB, AniList e Jikan, nessa ordem de preferência (`apiDadosAnime`);
- **Google Translate** — só nas telas de correção/revisão que o usam (scraping da API pública, não a API paga). O fallback online da tradução (`tradutor.fallback-online.ativo`) está **desligado**: a 2.1 é 100% local;
- **Redis** do fluxo ao vivo de telemetria (`quarkus.redis.hosts`) — sem ele o card mostra "desconectado" e nada mais muda;
- **push do dataset público** no GitHub (`telemetria-dataset.repositorio-remoto`), só quando o operador clica em "Publicar Dataset";
- **tradução da tela** — ver [Idioma da interface](#idioma-da-interface-bandeiras), abaixo;
- **foto e números do perfil do autor** na página Sobre, pedidos ao GitHub ao abrir a tela; sem rede ela mostra os valores fixos do HTML.

Fontes e ícones **não** saem mais: desde 14/10/2026 o próprio KRONOS os serve (`static/fontes/`, licenças em `licencas/fontes/`). Antes vinham do Google Fonts, e sem rede cada ícone virava o nome do glifo escrito por extenso.

### Idioma da interface (bandeiras)

As três bandeiras no topo da barra lateral (Brasil, Estados Unidos, Espanha) traduzem a **tela inteira** pelo widget do Google Tradutor — sem dicionário: tela nova já nasce traduzível. A escolha fica no navegador (`localStorage`) e vira o cookie `googtrans` que o widget lê no recarregamento. Na primeira visita, sem escolha salva, vale o idioma do navegador.

- **Português não carrega nada de terceiro.** O `element.js` do Google só é pedido quando o idioma é inglês ou espanhol.
- **O que não se traduz:** consoles, resultados, relatórios, caminhos, tabelas, campos, metadados de anime, código e o nome dos ícones (traduzido, o glifo some) levam `translate="no"`, inclusive o que a tela pinta depois do carregamento.
- **O `<html lang>` fica em `pt-BR`.** Quem o troca, depois de traduzir, é o próprio Google. Até 14/10/2026 o script trocava antes: com o Google bloqueado, a página ficava em português declarando `en-US`, e o leitor de tela lia com a voz inglesa.
- **Quando o Google não responde** (rede, bloqueador, captcha por limite de uso, ou dez segundos sem tradução), um aviso no rodapé diz o que aconteceu **no idioma pedido** e oferece tentar de novo, ficar em português e — só em endereço público `https` — abrir a página pelo Google Tradutor.
- **Guardas:** `CatracaI18nAutomaticaTest` no build; no navegador, `ferramentas/navegador/verificar-i18n.mjs` (tradução real com uma única carga ao Google, Google bloqueado, captcha, Google mudo, ícones intactos e calibração contra o script anterior).

### Tela no celular e dicas

O painel serve do celular de 320 px ao computador. Medido em 14/10/2026: antes, os 19 painéis passavam da largura de 360 px e, até 992 px, o menu empilhava mais de 1.100 px antes do painel.

- **Menu em gaveta até 992 px** (`js/gaveta.js`): uma barra fina no topo traz o botão Menu; o menu abre por cima do conteúdo, fecha ao escolher uma tela (o foco vai para o título do painel), com Esc, com o botão Fechar ou tocando fora, e o conteúdo atrás fica inerte. A gaveta anda por `left`, nunca por `transform` — `transform` num ancestral desloca a dica. Acima de 992 px o menu lateral é o de sempre.
- **Nada passa da borda:** cabeçalho de console que quebra linha, margem lateral em `%`, grades com `minmax(0, 1fr)` e `auto-fit`, textos longos que quebram.
- **Alvo de toque:** com o dedo (`pointer: coarse`), botão, seletor e campo têm 44 px de altura e nada fica abaixo de 24 px; o mouse do computador não muda.
- **Dicas:** todo botão, campo, título e cabeçalho de coluna tem dica pelo componente canônico da regra de tooltip (`/dica/`, sem edição). O HTML continua escrevendo o texto em `data-tooltip`; `js/dicas.js` o converte na marcação acessível — abre no foco, no toque e no hover, fecha com Esc e é lida pelo leitor de tela —, dá a mesma dica aos controles repetidos (`registrarDicaPadrao`) e converte o que aparece depois. O balão antigo, só de CSS, saiu: não abria pelo teclado e, mesmo invisível, ocupava largura (o celular encolhia o zoom da página).
- **Guardas:** `CatracaResponsividadeTest` no build; no navegador, `ferramentas/navegador/verificar-responsividade.mjs` (19 painéis de 320 a 1366 px: estouro, gaveta, alvos de toque, posição e Esc de cada dica, varredura de tooltip e axe WCAG 2.2 AA, cada detector calibrado com um defeito plantado).

### Páginas de erro

Dezoito páginas (400, 401, 403, 404, 405, 408, 409, 410, 413, 415, 422, 429, 500, 502, 503, 504, sem conexão e falha de interface) mais a dinâmica `/errors/error.html?code=…`, em `static/errors/`, no mesmo visual do painel: cor por erro, código grande, o que aconteceu, uma ação concreta e um painel de diagnóstico com Request ID que se copia.

- **Fonte única:** `ferramentas/paginas-de-erro/gerar.py` gera as páginas e o catálogo. Texto se muda no gerador e se regera — editar o HTML gerado é perder a edição na próxima geração.
- **Quem serve:** o arquivo estático abre direto em `/errors/<código>.html`. Além disso, quem **navega** até um endereço que não existe recebe a página 404 com o status 404, e quem abre pela barra do navegador uma ação que só aceita `POST` recebe a 405 — antes, um `<h1>Resource not found</h1>` cru e uma tela em branco. Só navegação (`GET`/`HEAD` com `Accept: text/html`): o `fetch` do painel e qualquer cliente de API recebem a resposta de antes, byte a byte (`sistema.presentation.web.PaginasDeErroNavegador`).
- **Request ID:** o mesmo vai no cabeçalho `X-Request-Id`, no diagnóstico da página e no log (`[PAGINA-ERRO] 404 GET /caminho -> página de erro (rid KC-…)`). Nenhuma mensagem de exceção, caminho de disco ou pilha entra na página.
- **Retry e saúde:** nas páginas 408, 429, 5xx e sem conexão, a contagem consulta `/api/status` e, quando o KRONOS responde, volta à rota de origem — só do próprio site: `?from=//outro-site` é recusado.
- **Idioma e tooltip:** as mesmas três bandeiras do painel (sem dicionário) e o tooltip canônico (`/dica/`), com o "?" ao lado de títulos e rótulos.
- **Não ligado de propósito:** erro de JavaScript e queda de Wi-Fi no painel **não** levam à página de falha ou de sem conexão — isso tiraria a pessoa do console de um trabalho em andamento por qualquer erro de extensão do navegador.
- **Na VPS:** `ferramentas/paginas-de-erro/nginx-kronos-erros.conf` serve 502/503/504 quando o contêiner da vitrine estiver fora do ar (aplicar é passo de deploy).

**De onde veio.** Kit de páginas de erro v1.0.0 de 14/10/2026, adaptado na v1.1.0. Visual, cores, ícones, anatomia e textos foram mantidos; mudou o que o KRONOS não tem ou o que estava errado:

| No kit | No KRONOS | Por quê |
|---|---|---|
| saúde em `/q/health` | `/api/status` | o KRONOS não tem `/q/health`: o chip ficaria "inacessível" para sempre e o retry nunca voltaria |
| botão "Entrar" (`/login`) na 401 | Painel Inicial e Voltar | não há login no KRONOS |
| `?from=` aceito se começar com `/` | só a mesma origem | `//outro-site` também começa com `/` — redirecionamento aberto |
| "Ver telemetria" em `/telemetria` | `/#telemetria`, que abre o painel | a telemetria é um painel da tela, não uma rota |
| PT/EN/ES por dicionário próprio | bandeiras do painel | regra de internacionalização do projeto (decisão de Paulo em 14/10/2026) |
| tooltip próprio | componente canônico | regra de tooltip do projeto (decisão de Paulo em 14/10/2026) |
| `px` e coluna central | `rem`, laterais em `%`, largura cheia, topo que quebra linha | regra de CSS do projeto; no kit, a 390 px, o chip de status saía da tela |
| 401/403 falam de sessão e perfil | proxy com autenticação; pasta fora das raízes | o KRONOS não tem login nem perfil |
| 500: "registrado na telemetria" | "registrado no log do servidor" | é no log que o Request ID está |
| 502/504: o LLM caiu | o proxy não alcançou o KRONOS | quem devolve 502/504 é o proxy da frente; LLM fora do ar o painel mostra no cartão |
| 503: "filas pausadas e retomadas" | "reiniciando ou sobrecarregado" | o KRONOS não pausa fila |
| 415: "use a 1.3" | "a Extração (1.2) já entrega ASS, SRT ou PGS" | a 1.3 troca fonte, não formato |
| falha de interface: "abra a Telemetria" | "abra o console do navegador (F12)" | o erro de JavaScript está no console do navegador |

**Guardas:** `CatracaPaginasDeErroTest` (uma página por código, tooltip ligado, mesma versão do módulo de idioma, nada de `/q/health` ou `/login`, retorno só da mesma origem) e `PaginasDeErroNavegadorHttpTest` (servidor HTTP real: página para navegação, resposta de antes para a API). No navegador, `ferramentas/navegador/verificar-paginas-de-erro.mjs`: as 19 páginas no desktop e no celular sem erro e sem estouro, varredura da regra de tooltip e axe WCAG 2.2 AA limpos, 404 com Request ID, retorno que não vaza, telemetria que abre, `?detail=` que não executa, idioma, teclado e todo tooltip dentro da tela.

A exceção é a [vitrine pública](ref-docker.md#vitrine-pública-vps): outra imagem (`Dockerfile.vitrine`) que mostra as telas na rede e recusa toda função.

![Painel Inicial do KRONOS CORE](../src/main/resources/static/img/screenshots/painel-inicial.webp)

---

## Fatias Verticais, Peers e Fronteiras Congeladas (ArchUnit)

O código é dividido em **fatias funcionais** (uma etapa do pipeline cada) e **peers** (bibliotecas internas importáveis). As setas entre camadas são **provadas a cada build** por testes de fitness ArchUnit — não é convenção de boa vontade, é falha de compilação quando alguém cruza uma fronteira não homologada.

```mermaid
graph TB
    subgraph FUNC["🧩 21 fatias funcionais — uma etapa do pipeline cada (NÃO importam umas às outras)"]
        direction LR
        TRAD["🌐 traducao<br/><b>TIER GOLD</b>"]
        PREP["🔍 analisadorMidia · legendasExtracao<br/>trocaTipoLegenda · auditorConteudoLegendas"]
        QUALF["📖 revisaoLore · revisaoConcordancia<br/>raspagemRevisao · raspagemCorrecao<br/>correcaoLegendas · traducaoCorrige"]
        KAR["🎤 traducaoKaraoke · novoKaraoke"]
        FIM["📦 remuxer · renomearArquivos"]
        SIS["⚙️ telemetria · mapaProjeto · apiDadosAnime<br/>mcp · sistema · vitrine"]
    end

    subgraph PEERS["🧱 5 peers — importáveis por qualquer fatia · superfície congelada por tipo exato"]
        direction LR
        LEG["legenda<br/>modelo + I/O .ass/.srt"]
        CACHE["cachetraducao<br/>DONO ÚNICO do cache"]
        CTX["lore<br/>70 arquivos SQL · SQLite em memória"]
        QUAL["qualidadeTraducao<br/>máscara de tags + validação"]
        LLM["llm<br/>contrato LlmPort neutro"]
    end

    subgraph BASE["⚙️ Infra transversal — core NÃO depende de fatia funcional"]
        direction LR
        CORE["core<br/>fila · I/O atômico · SSE<br/>FronteiraTermoAss · FonteVietnamitaLegada<br/>ModoVitrine"]
        CFG["config<br/>bootstrap de modo"]
    end

    GUARD["🚦 48 guardas executáveis<br/>10 Fronteira* + 38 Catraca*Test<br/>+ guardas da vitrine<br/><i>reprovam o BUILD ao cruzar a fronteira</i>"]

    FUNC -->|"importa — arestas congeladas por tipo exato"| PEERS
    FUNC --> BASE
    PEERS --> BASE
    GUARD -.->|congela| FUNC
    GUARD -.->|congela| PEERS
    GUARD -.->|congela| BASE

    classDef func fill:#312e81,stroke:#818CF8,color:#F9FAFB
    classDef gold fill:#4c1d95,stroke:#C4B5FD,color:#F9FAFB,stroke-width:3px
    classDef peer fill:#14532d,stroke:#4ADE80,color:#F9FAFB
    classDef base fill:#1e293b,stroke:#6B7280,color:#F9FAFB
    classDef guard fill:#7c2d12,stroke:#FB923C,color:#F9FAFB,stroke-width:2px
    class PREP,QUALF,KAR,FIM,SIS func
    class TRAD gold
    class LEG,CACHE,CTX,QUAL,LLM peer
    class CORE,CFG base
    class GUARD guard
```

> A seta pontilhada laranja é o que diferencia este desenho de um diagrama de intenção: as
> guardas **não documentam** a fronteira, elas a **impõem**. Um `CLAUDE.md` pode ser ignorado
> por qualquer IA que trabalhe no repositório; um teste vermelho, não.

**A fronteira da Tradução Local (`traducao`), congelada por `FronteiraTraducaoArchTest`:** ela consome só os cinco peers + `core`, e tem **ZERO** arestas de saída para outra fatia funcional. Cada TIPO consumido de um peer entra numa allowlist exata; um tipo novo cruzando a fronteira **reprova o build** até homologação intencional documentada.

```mermaid
graph LR
    TRAD["🌐 traducao"]:::hub
    LEG["legenda"]:::peer
    CACHE["cachetraducao"]:::peer
    CTX["lore"]:::peer
    QUAL["qualidadeTraducao"]:::peer
    LLM["llm"]:::peer
    CORE["core"]:::base
    OUTRA["❌ qualquer outra<br/>fatia funcional"]:::proib

    TRAD --> LEG
    TRAD --> CACHE
    TRAD --> CTX
    TRAD --> QUAL
    TRAD --> LLM
    TRAD --> CORE
    TRAD -. "proibido (ArchUnit reprova)" .-> OUTRA

    classDef hub fill:#312e81,stroke:#818CF8,color:#F9FAFB
    classDef peer fill:#14532d,stroke:#4ADE80,color:#F9FAFB
    classDef base fill:#1e293b,stroke:#6B7280,color:#F9FAFB
    classDef proib fill:#7f1d1d,stroke:#F87171,color:#F9FAFB
```

> **Por que peers e não uma fatia "compartilhada"?** Porque o fence ArchUnit proíbe fatia→fatia — só um **peer** pode ser importado. E o `cachetraducao` é **dono único do cache**: nenhuma outra fatia escreve cache paralelo. Assim, o núcleo cresce sem virar espaguete: cada dependência cruzada é explícita, congelada e auditável.

---

## Diagrama de Componentes

```mermaid
graph TB
    subgraph UI["🖥️ SPA (HTML/CSS/JS puro, sem framework)"]
        HOME["🏠 Início"]
        P_AN["🔍 Análise de Mídia"]
        P_EX["✂️ Extração de Legendas"]
        P_AC["🔎 Análise de Conteúdo"]
        P_TR["🌐 Tradução Local"]
        P_SL["🌐 Tradução sem Lore"]
        P_CO["🩹 Correção de Cache"]
        P_RE["📝 Revisão de Legendas"]
        P_RL["📖 Revisão de Lore"]
        P_RC["⚖️ Revisão de Concordância"]
        P_TF["🔤 Troca Tipo Legenda"]
        P_TK["🎤 Karaokê (traduz + achata)"]
        P_CU["🧵 Correção de Karaoke"]
        P_RX["📦 Remuxer"]
        P_LN["🧹 Renomear Arquivos"]
        P_MA["🗺️ Mapa do Projeto"]
        P_TE["📊 Telemetria"]
        P_DOC["📖 Documentação"]
        P_SB["ℹ️ Sobre"]
    end

    subgraph API["🎮 49 rotas /api — 21 controllers Spring-style + 6 recursos JAX-RS"]
        EP1["/analisar /extrair"]
        EP2["/traduzir /corrigir-* /revisar-*"]
        EP3["/correcao-legendas /remuxar /mapa"]
        EP4["/telemetria /contextos /metadata"]
        SSE["/logs/stream (SSE)"]
    end

    subgraph UC["⚙️ Use Cases (application/)"]
        UC_AN["AnalisarMidiaUseCase"]
        UC_EX["ExtrairLegendaUseCase"]
        UC_AC["AuditorConteudoUseCase"]
        UC_TR["ProcessarArquivoUseCase"]
        UC_CO["RevisarCacheUseCase / CorrigirComGoogleUseCase"]
        UC_RE["RevisarLegendasUseCase"]
        UC_RL["RevisarLoreUseCase"]
        UC_KS["ConversorKaraokeUseCase"]
        UC_TK["TraduzirKaraokeUseCase"]
        UC_CU["CorrigirLegendasUseCase"]
        UC_RX["RemuxarLoteUseCase"]
        UC_RN["RenomeadorUseCase"]
    end

    subgraph ADAPT["🔌 Adapters (infrastructure/)"]
        AD_FF["FfprobeAdapter"]
        AD_MK["MkvToolNixAdapter<br/>(mkvmerge/mkvextract)"]
        AD_LLM["LlmClientAdapter<br/>(OpenAI-compatible)"]
        AD_GT["GoogleTranslateScraper"]
        AD_MX["MkvmergeAdapter (remux)"]
        AD_JK["JikanApiClientAdapter"]
        AD_TM["TmdbApiClientAdapter"]
        AD_AL["AniListApiClientAdapter"]
    end

    subgraph EXT["🌍 Sistemas Externos"]
        LM[("LM Studio<br/>127.0.0.1:1234")]
        MKVT[("MKVToolNix<br/>mkvmerge / mkvextract")]
        FFM[("ffmpeg / ffprobe")]
        GT[("translate.googleapis.com")]
        JIKAN[("api.jikan.moe (MAL)")]
        TMDB[("api.themoviedb.org")]
        ANILIST[("graphql.anilist.co")]
    end

    subgraph FS["💾 Persistência em Disco"]
        CACHE[("cache/**/*.cache.json")]
        LOGS[("logs/telemetria_traducao.json")]
        REL[("relatorios/*.txt *.json")]
    end

    UI --> API
    API --> UC
    UC_AN --> AD_FF --> FFM
    UC_EX --> AD_MK --> MKVT
    UC_EX --> AD_FF
    UC_TR --> AD_LLM --> LM
    UC_TR --> CACHE
    UC_CO --> AD_GT --> GT
    UC_CO --> AD_LLM
    UC_RE --> AD_GT
    UC_RE --> AD_LLM
    UC_RL --> AD_LLM
    UC_TK --> AD_LLM
    UC_TK --> CACHE
    UC_CU --> AD_LLM
    UC_RX --> AD_MX --> MKVT
    API --> AD_TM --> TMDB
    API --> AD_AL --> ANILIST
    API --> AD_JK --> JIKAN
    UC --> LOGS
    UC_AN --> REL
    API --> SSE --> UI

    classDef ui fill:#1e293b,stroke:#3B82F6,color:#F9FAFB
    classDef uc fill:#1e2937,stroke:#8B5CF6,color:#F9FAFB
    classDef adapt fill:#1e293b,stroke:#F59E0B,color:#F9FAFB
    classDef ext fill:#0f172a,stroke:#10B981,color:#F9FAFB
    classDef fs fill:#0f172a,stroke:#6B7280,color:#F9FAFB
    class HOME,P_AN,P_EX,P_AC,P_TR,P_SL,P_CO,P_RE,P_RL,P_RC,P_TF,P_TK,P_CU,P_RX,P_LN,P_MA,P_TE,P_DOC,P_SB ui
    class UC_AN,UC_EX,UC_AC,UC_TR,UC_CO,UC_RE,UC_RL,UC_KS,UC_TK,UC_CU,UC_RX,UC_RN uc
    class AD_FF,AD_MK,AD_LLM,AD_GT,AD_MX,AD_JK,AD_TM,AD_AL adapt
    class LM,MKVT,FFM,GT,JIKAN,TMDB,ANILIST ext
    class CACHE,LOGS,REL fs
```

---

## Diagrama de Fluxo — Pipeline Completo (visão de negócio)

```mermaid
graph LR
    A["📼 Vídeo Original<br/>.mkv/.mp4"] --> B["🔍 1.1 Análise de Mídia<br/>ffprobe: codecs, drift de sync"]
    B --> C["✂️ 1.2 Extração de Legenda<br/>ASS / SRT / PGS"]
    C --> H3["🔤 1.3 Troca Tipo Legenda<br/>fontes legadas → Unicode"]
    H3 --> QA["🔎 1.4 Análise de Legenda<br/>anomalias de LLM e efeitos"]
    QA --> D["🌐 2.1 Tradução Local<br/>LLM via LM Studio + cache"]
    D --> E{"Resíduo em<br/>inglês?"}
    E -->|Sim| F["🩹 2.3 Correção Cache<br/>(cache LLM / Google scraping)"]
    E -->|Não| G["📝 3.1 Revisão de Legendas<br/>concordância PT-BR"]
    F --> G
    G --> H2["📖 3.2 Revisão de Lore<br/>nomes, locais e termos de mundo"]
    H2 --> K2["🎤 4.1 Karaokê<br/>traduz a letra (romaji preservado)<br/>e depois achata (KFX → linha limpa)"]
    K2 --> H["🧵 4.2 Correção de Karaoke<br/>original como referência imutável"]
    H --> I["📦 5.1 Remuxer<br/>mkvmerge: vídeo + legenda PT-BR"]
    I --> J["🎬 MKV Final<br/>pronto para distribuição"]
    J -.-> K["🧹 5.2 Renomear Arquivos<br/>padroniza nomes de arquivo (S01E01)"]

    classDef prep fill:#0c4a6e,stroke:#38BDF8,color:#F9FAFB
    classDef trad fill:#312e81,stroke:#818CF8,color:#F9FAFB
    classDef qual fill:#14532d,stroke:#4ADE80,color:#F9FAFB
    classDef kara fill:#831843,stroke:#F472B6,color:#F9FAFB
    classDef fin fill:#7c2d12,stroke:#FB923C,color:#F9FAFB
    classDef midia fill:#1e293b,stroke:#3B82F6,color:#F9FAFB
    class B,C,H3,QA prep
    class D,E,F trad
    class G,H2 qual
    class K2,H kara
    class I,K fin
    class A,J midia
```

> 🎨 **Cores do diagrama** (só do desenho — o menu não colore os grupos): azul = Preparação, índigo = Tradução, verde = Qualidade, rosa = Karaokê, laranja = Finalização. Os números são os do menu (`GRUPO.POSIÇÃO`); a 2.2 (tradução sem lore) e a 3.3 (concordância determinística) não aparecem neste fluxo resumido.

> Cada etapa é **independente e re-executável** — o operador pode rodar só a extração de novo, ou só a revisão, sem repetir as etapas anteriores. O elo entre etapas é sempre o sistema de arquivos (pastas de entrada/saída informadas manualmente em cada painel).

---

## Diagrama de Sequência — Tradução com Cache e LLM Local

```mermaid
sequenceDiagram
    actor Op as Operador
    participant UI as Painel Tradução
    participant API as Controller da fatia
    participant UC as ProcessarArquivoUseCase
    participant Cache as CacheTraducaoService
    participant Ctx as GerenciadorContexto
    participant LLM as LlmClientAdapter
    participant LMS as LM Studio (GPU local)

    Op->>UI: Informa pasta + contexto (ex: "gundam_nt")
    UI->>API: POST /api/traduzir {entrada, contextoId}
    API->>Ctx: existeContexto(contextoId)
    API->>API: guarda de caminho (pasta existe e não é saída de tradução)
    API-->>UI: 200 "Tradução via LLM iniciada no servidor." (job na fila)

    Note over API,LMS: daqui em diante, dentro do job assíncrono
    API->>LLM: verificarDisponibilidade()
    LLM->>LMS: GET /api/v0/models (state=loaded)
    LMS-->>LLM: modelo real carregado
    API->>Ctx: snapshotPorId + definirContextoAtivo(contextoId)

    loop Para cada arquivo .ass na pasta
        UC->>Cache: carregar cache existente (.cache.json)
        loop Para cada fala não traduzida
            UC->>LLM: traduzir(1 linha, ou a corrente de frase partida)
            LLM->>LMS: POST /v1/chat/completions
            LMS-->>LLM: tradução
            LLM-->>UC: TraducaoLote
        end
        UC->>UC: reconstrói .ass com EscritorLegendaAss
        UC->>Cache: grava os pares do arquivo (ao fim dele, ou os já validados se o job for interrompido)
    end
    UC-->>API: relatório de lote (SSE: canal "traducao" ou "traducao-sem-lore")
    API-->>UI: stream de progresso e, no fim, o evento "traducao-finalizada" (ou "traducao-sem-lore-finalizada")
```

---

## Pacotes e Responsabilidades

```text
org.traducao.projeto/
│
│  ── Fatias funcionais (uma etapa do pipeline; NÃO importam umas às outras) ──
├── traducao/               ← NÚCLEO da Tradução Local: orquestra ler → cache → traduzir → validar → publicar
├── legendasExtracao/       ← Extração de faixas de legenda (ASS/SRT/PGS) via mkvextract/ffmpeg
├── analisadorMidia/        ← Auditoria técnica (ffprobe): codecs, drift de sincronismo
├── auditorConteudoLegendas/← Análise de Conteúdo: anomalias de LLM, efeitos vazados, karaokê danificado
├── raspagemCorrecao/       ← Correção de cache via Google Translate (scraping)
├── raspagemRevisao/        ← Revisão de .ass finais (Google/LLM) + detector de concordância PT-BR
├── revisaoConcordancia/    ← Revisão de concordância de GÊNERO PT-BR (CorretorConcordanciaGeneroService)
├── revisaoLore/            ← Refinamento de lore pós-tradução: nomes, lugares, termos de universo
├── correcaoLegendas/       ← Correção estrutural da PT-BR usando a original como referência imutável
├── traducaoCorrige/        ← Limpeza de cache (esvazia entradas de fallback)
├── trocaTipoLegenda/       ← Troca em lote de fontes legadas (TCVN3/VNI) por Unicode
├── novoKaraoke/            ← 4.1, passo 2 (achatar): KFX (milhares de eventos) → linha limpa por frase
├── traducaoKaraoke/        ← 4.1, passo 1 (traduzir): romaji preservado + letra EN → PT-BR via LLM
├── remuxer/                ← Combina vídeo original + legenda traduzida em MKV final (mkvmerge)
├── renomearArquivos/       ← Renomeação em lote "Nome - S01E01" com dry-run e undo
├── telemetria/             ← Painel de telemetria + SSE (lê o arquivo próprio da Tradução Local)
├── mapaProjeto/            ← Gera o mapa_projeto.md (varredura estática de docstrings)
├── apiDadosAnime/          ← Metadados externos (TMDB, AniList, Jikan/MAL) — decorativo na UI
├── mcp/                    ← Ferramentas MCP (Model Context Protocol) que expõem funções do KRONOS
├── sistema/                ← Ciclo de vida do processo (menu "Sair" — encerramento gracioso)
├── vitrine/                ← Vitrine pública: GuardaVitrine (filtro na frente de todas as rotas), PoliticaVitrine,
│                             ConferenciaArranqueVitrine, DadosDemonstracao — ver ref-docker.md
│
│  ── Peers (importáveis por qualquer fatia; superfície congelada por tipo exato) ──
├── legenda/                ← Modelo puro (DocumentoLegenda/EventoLegenda) + Leitor/Escritor .ass/.srt
├── cachetraducao/          ← DONO ÚNICO do cache: CacheTraducaoService, EntradaCache, ProvenienciaCache
├── lore/                   ← 70 arquivos SQL (resources/lore/) num SQLite em memória (CatalogoLoreSqlite)
│                             + terminologia canonica (ex-peer "contexto")
├── qualidadeTraducao/      ← MascaradorTags, ValidadorTraducaoService (anti-alucinação), ProtecaoLegendaAssService
├── llm/                    ← Contrato neutro do LLM: LlmPort, Lote, TraducaoLote, StatusLlm
│
│  ── Infra transversal (core NÃO depende de fatia funcional) ──
├── core/                   ← FilaExecucaoPipeline (fila única de jobs LLM), I/O atômico, kernel web/SSE,
│                             texto.FonteVietnamitaLegada (regra única da fonte banida: 1.3, 4.1 e 5.1),
│                             vitrine.ModoVitrine (fila e processo externo recusam na vitrine)
└── config/                 ← Bootstrap (modo WEB vs CLI legado)
```

> O antigo `traducao.presentation.web.ApiController` monolítico foi **decomposto na FASE C2**: cada controller migrou para a fatia dona (ex.: `TelemetriaController` → `telemetria`, `CorrecaoCacheController` → `traducaoCorrige`) e o kernel técnico de apresentação foi para `core.presentation`. O `LlmClientAdapter` (ponte HTTP com o LM Studio) permanece em `traducao.infrastructure` como **ponto de composição** do peer `llm`.

---

## Decisões de Arquitetura

### Por que Quarkus com compatibilidade Spring, e não Quarkus "puro" (JAX-RS/CDI nativo)?

O projeto foi originalmente escrito sobre Spring Boot e migrado para Quarkus preservando as anotações `@RestController`, `@Component`, `@Service`, `@RequestMapping` via `quarkus-spring-web` e `quarkus-spring-di`. Isso permitiu ganhar o **modo dev com live reload** e o tempo de boot menor do Quarkus sem reescrever toda a camada web. Seis classes usam JAX-RS nativo do Quarkus (`@Path`/`@GET`/`@POST`): os dois fluxos SSE (`LogStreamResource`, `TelemetriaStreamResource`), para evitar colisão de roteamento com o dispatcher Spring-style, e `NovoKaraokeController`, `TraducaoKaraokeController`, `RenomearArquivosController` e `AuditorConteudoController`.

### Por que LLM local (LM Studio) em vez de API paga?

Tradução de legendas de fã-sub envolve volumes grandes de texto (temporadas inteiras, filmes) e a lore de cada obra é sensível a nuance (nomes próprios, gênero de personagens, tom). Rodar localmente via LM Studio elimina custo por token, elimina limite de rate, e garante que o app **adapta-se dinamicamente ao modelo que o operador tiver carregado** (ver [`tradutor.llm.model: "current"`](ref-configuracao.md)) — o operador troca de modelo pela UI do LM Studio para comparar qualidade sem precisar recompilar o app.

### Por que cache em JSON por arquivo, e não banco de dados?

O cache (`cache/**/*.cache.json`) espelha a estrutura de pastas de entrada do usuário, é editável manualmente (o operador pode corrigir uma tradução direto no JSON), e não introduz dependência de infraestrutura (sem SGBD para rodar/manter). O trade-off é que buscas cruzadas entre animes não são triviais — mitigado pelo fato de que cada operação já é escopada a uma pasta específica.

### Por que 3 fluxos distintos de correção/revisão em vez de um só?

Cada fluxo ataca uma fonte de erro diferente com o custo/precisão adequado: **correção de cache** (LLM local, grátis, mas pode repetir o mesmo erro do 1º passe), **correção via Google Translate** (scraping gratuito, baseline melhor que "não traduzido", mas sem entender a lore), e **revisão de concordância PT-BR** (heurística regex + LLM, focada especificamente no problema mais comum de tradução EN→PT-BR: calque de gênero). Ver [Correção & Revisão](etapa-2.3-correcao-revisao.md) para o comparativo completo.

### Por que SSE (Server-Sent Events) para logs em vez de WebSocket?

Todo o fluxo de logs é **unidirecional** (servidor → navegador) — o operador só observa o progresso, nunca envia comandos pelo canal de log. SSE é mais simples de implementar (HTTP puro, sem handshake de upgrade), reconecta automaticamente no navegador (`EventSource`), e o `ConsoleRedirector` intercepta `System.out` globalmente, então qualquer `println` de qualquer módulo já aparece no navegador sem instrumentação extra.

---

## Navegação

| Anterior | Próximo |
|----------|---------|
| [← README](../README.md) | [Instalação & Configuração →](instalacao.md) |
