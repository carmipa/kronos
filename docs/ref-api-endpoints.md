# 📋 API REST — Referência Completa

[← Mapa do Projeto](modulo-mapa-projeto.md) | [Configuração →](ref-configuracao.md)

---

## Convenções

- **Base URL:** `http://127.0.0.1:8099`
- **Content-Type:** `application/json` nas requisições e respostas — exceto `GET /api/docs/{pagina}` (`text/plain`), os dois fluxos SSE (`text/event-stream`) e os erros da auditoria de conteúdo (texto puro)
- A maioria das operações do pipeline (`/analisar`, `/extrair`, `/traduzir`, `/corrigir-*`, `/revisar-*`, `/remuxar`) é **assíncrona**: o endpoint responde `200 OK` imediatamente com uma mensagem de confirmação, e o progresso/relatório real chega via **[SSE](#sse--logsstream)** no canal correspondente. `/correcao-legendas` responde `202 Accepted`; ela e `/remuxar` respondem `409 Conflict` quando a fila do pipeline já está ocupada.
- Erros de validação de entrada retornam `400 Bad Request` com `{"mensagem": "..."}` ou `{"erro": "..."}`, conforme a rota; as rotas JAX-RS de karaokê e de renomeação usam `{"error": "..."}`, e a auditoria de conteúdo responde o erro em texto puro.

---

## Pipeline — Operações Principais

### `GET /api/status`
Health check simples.

**Resposta:** `200 OK` `{"mensagem": "online"}`

---

### `POST /api/analisar`
Auditoria técnica de mídia. Ver [Análise de Mídia](etapa-1.1-analise-midia.md).

```json
{ "entrada": "C:/animes/DanMachi/Season 04", "saida": null }
```
**Canal SSE:** `analise` (progresso) + evento dedicado `analise-relatorio` com o **JSON do `ResultadoAnaliseLote`** (resultado estruturado, **não** gravado em disco). A exportação TXT é manual no navegador; só a telemetria técnica é persistida (em `logs/`).

---

### `POST /api/extrair`
Extração de faixas de legenda. Ver [Extração de Legendas](etapa-1.2-extracao-legendas.md).

```json
{ "entrada": "C:/animes/DanMachi/Season 04", "saida": "C:/.../legendas_extraidas", "formato": "ASS" }
```
`formato`: `ASS` | `SRT` | `PGS` (obrigatório — `400` se ausente/inválido). **Canal SSE:** `extracao`

Numa pasta que tem os vídeos originais, os MKVs publicados pelo remux (`*_PTBR.mkv`) ficam de fora e o console avisa `[IGNORADOS]`; subpastas `_kronos_*` (cópia de trabalho e backup) nunca são percorridas. A rota só aceita **pasta** (arquivo avulso → 400); para extrair de um `_PTBR.mkv`, ele precisa estar numa pasta só com MKVs publicados, que continua sendo extraída.

---

### `POST /api/traduzir`
Tradução via LLM local com cache. Ver [Tradução Local](etapa-2.1-traducao-llm.md).

```json
{ "entrada": "C:/.../legendas_extraidas", "saida": "C:/.../legendas-ptbr", "contextoId": "danmachi_s4" }
```
`contextoId` é **obrigatório** e tem de existir (`400` se ausente ou desconhecido — não há fallback para a obra da execução anterior). A pasta de entrada é conferida antes de a fila aceitar o job.

**Canal SSE:** `traducao` — ou `traducao-sem-lore` quando `contextoId` é `sem_lore` (tela 2.2). O fim do lote sai num evento próprio: `traducao-finalizada` / `traducao-sem-lore-finalizada`.

---

### `POST /api/corrigir-cache`
Limpa entradas de fallback do cache (força retradução). Ver [2.3 Correção de Cache](etapa-2.3-correcao-revisao.md).

```json
{ "entrada": "cache", "contextoId": "danmachi_s4" }
```
**Canal SSE:** `correcao`

---

### `POST /api/reforcar-terminologia-ensaio`
### `POST /api/reforcar-terminologia-aplicar`
Reforço de terminologia sobre o cache já gravado (classe `CorrecaoCacheController`). O **ensaio** mede o que a lore mudaria no acervo sem escrever nada; o **aplicar** grava, com backup e escrita atômica por arquivo. São rotas separadas de propósito: a ação destrutiva tem endereço próprio, não um booleano no corpo. Ver [2.3 Correção de Cache](etapa-2.3-correcao-revisao.md).

```json
{ "entrada": "cache", "contextoId": "danmachi_s4" }
```
`400` para caminho ou contexto inválido. O **aplicar** responde `409` enquanto `correcao-cache.reforco-terminologia-aplicar-habilitado` for `false` (o padrão) — nada é lido nem escrito, e o ensaio continua disponível. **Canal SSE:** `correcao`

---

### `POST /api/corrigir-scraping`
Correção de cache via Google Translate. Ver [2.3 Correção de Cache](etapa-2.3-correcao-revisao.md).

```json
{ "entrada": "cache" }
```
**Canal SSE:** `correcao`

---

### `POST /api/revisar-cache`
Revisão de concordância PT-BR do cache via LLM. Ver [2.3 Correção de Cache](etapa-2.3-correcao-revisao.md).

```json
{ "entrada": "cache", "contextoId": "danmachi_s4" }
```
**Canal SSE:** `correcao`

---

### `POST /api/revisar-legendas`
Revisão de legendas `.ass` finais via Google Translate (modo `GOOGLE`).

```json
{ "entrada": "C:/.../legendas-ptbr", "saida": "C:/.../legendas_extraidas", "contextoId": "danmachi_s4", "modoReferencia": "AMBOS", "caminhoCache": "" }
```
`entrada` é a pasta **PT-BR** (obrigatória) e `saida` a pasta do **inglês** (nome herdado do contrato). `modoReferencia`: `AMBOS` (padrão — o `.ass` inglês manda e o cache preenche lacunas) ou `CACHE` (só o cache, entrada a entrada, com vínculo confirmado; usa `caminhoCache` — vazio cai em `cache` —, e a pasta precisa existir e ter ao menos um `.cache.json`, senão `400`). **Canal SSE:** `revisao`

---

### `POST /api/revisar-legendas-concordancia`
Revisão de legendas `.ass` finais via LLM local (modo `LLM_CONCORDANCIA`). Mesmo corpo e mesmas regras de `/api/revisar-legendas`.

```json
{ "entrada": "C:/.../legendas-ptbr", "saida": "C:/.../legendas_extraidas", "contextoId": "danmachi_s4", "modoReferencia": "AMBOS" }
```
**Canal SSE:** `revisao`

---

### `POST /api/revisar-concordancia`
Correção **determinística** de concordância de gênero (painel **3.3**), direto na pasta PT-BR — sem inglês, sem cache e sem LLM. `aplicar: false` = dry-run, e é o padrão da tela. Ver [3.3 Revisão de Concordância](etapa-3.3-revisao-concordancia.md).

```json
{ "diretorioTraduzido": "C:/.../legendas-ptbr", "aplicar": false }
```
**Canal SSE:** `revisao-concordancia`

---

### `POST /api/correcao-legendas`
Correção estrutural de legendas PT-BR usando a original como referência. Ver [Correção de Legendas](etapa-4.2-cura-tags.md).

### `POST /api/cura-tags`
Alias legado de compatibilidade para `/api/correcao-legendas`.

```json
{ "diretorioOriginal": "C:/.../legendas_extraidas", "diretorioTraduzido": "C:/.../legendas-ptbr", "contextoId": "danmachi_s4" }
```
Responde `202 Accepted`; `409` se a fila do pipeline estiver ocupada. **Canal SSE:** `correcao-legendas`

---

### `POST /api/revisar-lore`
Corrige nomes, locais e termos de lore em legendas `.ass` já traduzidas, comparando com o original em inglês. Ver [Revisão de Lore](etapa-3.2-revisao-lore.md).

```json
{ "diretorioOriginal": "E:/.../legendas_eng", "diretorioTraduzido": "E:/.../traducao_ptbr", "contextoId": "gundam_0083", "revisarTodasFalas": false }
```
`contextoId` é **obrigatório** (`400` se ausente/desconhecido — usa o sistema de contextos próprio deste módulo, não o `/api/contextos` geral). **Canal SSE:** `revisao-lore`

---

### `POST /api/troca-legenda/escanear`
Audita os `.ass`/`.ssa` de uma pasta em busca de fontes legadas (TCVN3/VNI etc.) nos estilos. **Síncrono** (roda dentro da fila do pipeline e devolve o relatório na resposta). Ver [Troca Tipo Legenda](etapa-1.3-troca-tipo-legenda.md).

```json
{ "diretorioLegendas": "C:/animes/Serie/traducao-ptbr", "forcarArial": false }
```

### `POST /api/troca-legenda/aplicar`
Aplica em lote as substituições de fontes sugeridas, com backup automático. Assíncrono. Mesmo corpo: com `forcarArial: true`, normaliza manualmente para Arial. **Canal SSE:** `troca-tipo-legenda`

### `POST /api/troca-legenda/achatar-estilos`
Achata, no próprio arquivo e com backup, os estilos decorativos das falas da pasta (classe `TrocaTipoLegendaController`). Corpo `{ "diretorioLegendas": "..." }`; a pasta é conferida antes da fila (`400` se não existir). Assíncrono. **Canal SSE:** `troca-tipo-legenda`

---

### `POST /api/auditoria-conteudo`
Audita legendas com **12 regras**: 6 de par original ↔ traduzido (karaokê danificado, efeito vazado, quebra de linha alucinada, metadados ASS, sincronia de estilos, integridade do pareamento) e 6 de arquivo único (efeito com texto longo, evento de diálogo vazio, quebras de linha excessivas, sobreposição de tempo, tag de override não fechada, timestamp inválido). **Síncrono** — devolve o `RelatorioAuditoriaConteudo` na resposta. Ver [Análise de Conteúdo](etapa-1.4-analise-conteudo.md).

```json
{ "modo": "AMBAS", "caminhoOriginal": "C:/animes/Serie/legendas_originais", "caminhoTraduzido": "C:/animes/Serie/legendas-ptbr" }
```
`modo`: `AMBAS` (padrão; exige os dois caminhos), `ORIGINAL` ou `TRADUZIDO` (exige só o caminho do lado escolhido). Modo desconhecido ou caminho exigido em branco: `400` em texto puro.
**Canal SSE:** `auditor-conteudo`

---

### `POST /api/novo-karaoke/simular`
### `POST /api/novo-karaoke/aplicar`
Converte karaokê KFX (milhares de eventos por sílaba/frame) em legendas simples — uma linha limpa por frase, no tempo original. Simulação não grava nada. É o **Passo 2 (Achatar)** da tela única de Karaokê. Ver [Karaokê](etapa-4.1-traducao-karaoke.md).

```json
{ "caminhoOrigem": "C:/animes/86/legendas-ptbr", "caminhoDestino": "C:/animes/86/legendas-karaoke-simples" }
```
**Canal SSE:** `novo-karaoke`

---

### `POST /api/traducao-karaoke/simular`
### `POST /api/traducao-karaoke/aplicar`
Traduz as letras de música preservando o romaji/japonês original: a simulação classifica linha a linha **sem LLM**; a aplicação entra na fila do pipeline e traduz via LLM apenas a camada em inglês, com cache editável em `cache/karaoke/`. Ver [Tradução de Karaokê](etapa-4.1-traducao-karaoke.md).

```json
{ "caminhoOrigem": "C:/animes/86/legendas-karaoke-simples", "contextoId": "eight_six" }
```
**Canal SSE:** `traducao-karaoke` — saída gravada na pasta irmã `<entrada>-karaoke-ptbr`

---

### `POST /api/renomear-arquivos/simular`
### `POST /api/renomear-arquivos/aplicar`
### `POST /api/renomear-arquivos/reverter`
Renomeação em lote de arquivos para o padrão `Nome - S01E01` (dry-run, aplicação com manifesto de undo e reversão). Ver [Renomear Arquivos](etapa-5.2-renomear-arquivos.md).

```json
{ "caminhoOrigem": "C:/animes/[SubsPlease] Nome Anime", "nomePadrao": "Nome Anime", "temporada": 1 }
```
`temporada` é opcional: nula, a temporada é inferida pelo nome. **Canal SSE:** `renomear-arquivos` (`reverter` dispensa `nomePadrao` e `temporada`)

---

### `POST /api/remuxar`
Combina vídeo + legenda em MKV final. Ver [Remuxer](etapa-5.1-remuxer.md).

```json
{ "entrada": "C:/animes/Gundam-Narrative-NT", "saida": "C:/animes/Gundam-Narrative-NT/traducao_ptbr", "pastaDestino": "E:/prontos", "syncOffsetMs": 0 }
```
`saida` é a pasta das **legendas** (nome herdado do contrato); vazio procura `traducao_ptbr` ao lado dos vídeos. `pastaDestino` é onde os `.mkv` finais são gravados — vazio ⇒ `mkv_final_ptbr/` dentro da pasta de vídeos; precisa existir e não pode ser a pasta de vídeos nem a de legendas. `preservarLegendasOriginais` é aceito e **ignorado**: as originais nunca são apagadas.

O pareamento vídeo ↔ legenda respeita a temporada: com `SxxEyy` nos dois nomes, a temporada tem de ser igual, e legenda **sem** temporada não vai para o especial `S00` quando o lote tem o episódio regular de mesmo número. Legenda com fonte vietnamita legada (TCVN3/VNI) é recusada e o MKV daquele episódio não é gerado — rode antes a [1.3 Troca de Tipo de Legenda](etapa-1.3-troca-tipo-legenda.md). `409` se a fila estiver ocupada.

**Canal SSE:** `remuxer`

---

### `POST /api/pipeline/parar`
Solicita a **parada cooperativa** do trabalho em execução na fila única do pipeline: o job encerra no próximo ponto seguro e o progresso já salvo (cache, arquivos concluídos) é preservado. Os botões "Parar Execução" foram **removidos da UI em 2026-07-09** (a parada cooperativa não interrompia o job de forma perceptível); o endpoint permanece disponível via API e é usado internamente pelo fluxo de encerramento (`/api/sistema/sair`).

Sem payload. **Resposta:** `{"mensagem": "..."}`

---

### `GET /api/pipeline/status`
Estado da fila única do pipeline (classe `PipelineController`) — usado pelo modal do "Sair" para avisar que ainda há trabalho em execução.

**Resposta:** `200 OK` `{"mensagem": "ocupada"}` ou `{"mensagem": "livre"}`

---

### `GET /api/llm/status`
Status ao vivo do LM Studio para o card do painel inicial (classe `PipelineController`): servidor online, se há modelo carregado e qual.

**Resposta:** `200 OK` `{ "online", "modeloCarregado", "modelo", "mensagem" }` — falha na consulta também volta `200`, com `online: false` e o motivo em `mensagem`.

---

### `POST /api/sistema/sair`
Encerra a aplicação de forma graciosa (menu "Sair" da UI): o trabalho em execução para no próximo ponto seguro e o servidor é desligado.

Sem payload. **Resposta:** `{"mensagem": "Encerrando a aplicação. ..."}`

---

### `POST /api/mapa`
Regenera `mapa_projeto.md`. Ver [Mapa do Projeto](modulo-mapa-projeto.md).

Sem payload.

---

### `GET /api/docs/{pagina}`
Conteúdo bruto de uma página de `docs/` para o painel "Documentação" (classe `DocumentacaoController`), em **`text/plain`**. `pagina` é o nome sem `.md` (ex.: `etapa-1.1-analise-midia`); nome inválido ou com `..` dá `400`, página inexistente `404`.

---

## Dados de Apoio

### `GET /api/contextos`
Lista as obras com lore para os seletores das telas, ordenadas por franquia (`grupo`) e cronologia. Ver [Contextos & Lore](modulo-contextos-lore.md).

```json
[{ "id": "danmachi", "nome": "DanMachi (Geral)", "grupo": "DanMachi", "padrao": true }]
```
São **68** obras: `sem_lore` fica fora da lista (tem tela própria, a 2.2). `grupo` vazio = obra sem franquia conhecida.

### `GET /api/revisao-lore/contextos`
Lista as obras da [Revisão de Lore](etapa-3.2-revisao-lore.md). É a **mesma lore SQL** do `/api/contextos`, lado da revisão (tabela `lore_revisao`, **69** obras: `macross_dyrl` só existe aqui, e `sem_lore` só na tradução). `termoMetadata` é o nome sem o sufixo "Revisao de Lore", usado na busca de metadados.

```json
[{ "id": "gundam_0083", "nome": "Mobile Suit Gundam 0083: Stardust Memory - Revisao de Lore", "termoMetadata": "Mobile Suit Gundam 0083: Stardust Memory" }]
```

### `GET /api/metadata?caminho=<pasta_ou_nome>`
Metadados de anime (TMDB, AniList e Jikan, nessa ordem). Ver [Metadados de Anime](modulo-metadados-anime.md).

### `GET /api/telemetria`
Resumo consolidado de telemetria + métricas de JVM. Ver [Telemetria](modulo-telemetria.md).

### `GET /api/telemetria/exportar`
Download do `logs/telemetria_compartilhada.json` bruto como `kronos_telemetria_segura.json`.

### `POST /api/telemetria/publicar-dataset`
Publica a telemetria **sanitizada** (só métricas — sem textos de legenda nem caminhos) como dataset público no repositório dedicado `kronos-anime-translation-telemetry-dataset`: snapshot em `metrics/`, commit e push. Ver [Telemetria](modulo-telemetria.md).

Sem payload. **Resposta:** `{ "repositorio", "commit", "pushOk", "mensagem" }`

### `POST /api/telemetria/consolidar-fatias`
Reúne a telemetria espalhada em `relatorios/` num arquivo sanitizado por fatia (`logs/telemetria_fatia_<fatia>.json`) — é o que enche as abas do painel (classe `TelemetriaController`). Idempotente; não toca nos arquivos de origem.

Sem payload. **Resposta:** `{ "arquivosLidos", "arquivosPulados", "operacoesLidas", "operacoesDeTeste", "operacoesGravadas", "porFatia" }` — `500` só em falha de escrita do destino.

### `GET /api/telemetria/fatias`
Lista as fatias já consolidadas com o total de cada uma: `{ "fatias": [{ "fatia", "total", "operacoes": [] }] }`. Lista vazia significa "ainda não consolidou".

### `GET /api/telemetria/fatias/{fatia}`
Operações de uma fatia (o conteúdo de uma aba): `{ "fatia", "total", "operacoes" }`. Fatia sem consolidado dá `404` com `"motivo": "NAO_CONSOLIDADA"`.

### `GET /api/fluxo/status`
Estado do fluxo ao vivo de telemetria no Redis, para o card do painel inicial (classe `FluxoTelemetriaController`).

**Resposta:** `200 OK` `{ "conectado", "detalhe", "eventos" }` — desconectado também é `200` (estado legítimo); `eventos` vem `-1` quando desconhecido.

---

## Seleção de pasta e arquivo

### `GET /api/dialogo/capacidade`
Diz se este servidor tem diálogo nativo (Windows com `powershell.exe` presente), sem abrir nada (classe `DialogoArquivoController`). **Resposta:** `{ "nativo": true | false }` — na dúvida, `false`.

### `GET /api/dialogo/selecionar-pasta?dirInicial=...`
Abre o seletor nativo de pasta do Windows (PowerShell + `System.Windows.Forms.OpenFileDialog` simulando `FolderBrowserDialog`). Timeout de 3 minutos. `dirInicial` (opcional) é a pasta em que o diálogo abre, se existir.

```json
{ "caminho": "C:\\animes\\DanMachi\\Season 04" }
```

### `GET /api/dialogo/selecionar-arquivo?filtro=...&dirInicial=...`
Idem, para seleção de arquivo único.

### `GET /api/navegador/pastas?caminho=...&tipo=pasta|arquivo`
Navegador de pastas **servido pelo servidor** (classe `NavegadorPastasController`) — é o que a tela usa quando `/api/dialogo/capacidade` responde `nativo: false` (fora do Windows, contêiner). `caminho` vazio devolve as raízes de `navegador.raizes`.

**Resposta:** `{ "atual", "pai", "raizes", "pastas", "arquivos" }` (`arquivos` só com `tipo=arquivo`). Recusas com `{ "erro", "motivo" }`: `503` sem raiz configurada, `403` fora da raiz, `404` pasta inexistente, `400` caminho inválido.

---

## SSE — `/api/logs/stream`

Conexão `EventSource` única para **todos** os logs em tempo real. Cada operação em background publica sob um **canal nomeado** (ver `LogStreamService#definirCanalAtual`, escopado por `ThreadLocal` — operações concorrentes não se misturam):

| Canal | Origem |
|-------|--------|
| `analise` | `/api/analisar` |
| `extracao` | `/api/extrair` |
| `traducao` | `/api/traduzir` (com lore) |
| `traducao-sem-lore` | `/api/traduzir` com `contextoId: "sem_lore"` |
| `traducao-finalizada` / `traducao-sem-lore-finalizada` | Evento único de fim do lote de tradução (desfecho + resultado do aviso sonoro) |
| `correcao` | `/api/corrigir-*`, `/api/revisar-cache`, `/api/reforcar-terminologia-*` |
| `revisao` | `/api/revisar-legendas*` |
| `revisao-concordancia` | `/api/revisar-concordancia` |
| `correcao-legendas` | `/api/correcao-legendas` e o alias `/api/cura-tags` |
| `revisao-lore` | `/api/revisar-lore` |
| `troca-tipo-legenda` | `/api/troca-legenda/aplicar`, `/api/troca-legenda/achatar-estilos` |
| `auditor-conteudo` | `/api/auditoria-conteudo` |
| `novo-karaoke` | `/api/novo-karaoke/*` |
| `traducao-karaoke` | `/api/traducao-karaoke/*` |
| `renomear-arquivos` | `/api/renomear-arquivos/*` |
| `remuxer` | `/api/remuxar` |
| `console` | Fallback genérico — roteado para a aba ativa no navegador |
| `sistema` | Mensagens de conexão/sistema |
| `heartbeat` | Batimento periódico (15 s, `kronos.sse.heartbeat-intervalo-segundos`) com a identidade da execução — distingue "servidor reiniciou" de "continuo conectado" |
| `analise-relatorio` | Evento único com o JSON do `ResultadoAnaliseLote` (resultado estruturado; não salvo em disco — export TXT é manual) |

```javascript
const es = new EventSource('/api/logs/stream');
es.addEventListener('traducao', (e) => console.log(e.data));
```

> 📋 **Relatório final padronizado**: toda operação, em qualquer canal, encerra com a linha
> `[RELATÓRIO FINAL] Operação: <nome> | Tempo total: <duração> | Término: <hh:mm:ss>` (hora local) —
> tanto em caso de sucesso quanto de falha.

Cada painel da SPA renderiza seu canal nesta mesma caixa "Logs do..." (exemplo abaixo: canal `analise`, painel Análise de Mídia):

![Console de logs em tempo real (SSE) — mesmo componente em todos os painéis](../src/main/resources/static/img/screenshots/analise-midia.webp)

---

## SSE — `/api/telemetria/stream`

JAX-RS puro (não Spring — evita colisão de rota). Publica o `TelemetriaResumo` serializado a cada 5 segundos.

---

## MCP — `/mcp`

Na mesma porta, a extensão `quarkus-mcp-server-sse` expõe um servidor MCP (Model Context Protocol) em `/mcp` (e `/mcp/sse`), com as ferramentas de `mcp/KronosMcpTools`:

| Ferramenta | O que faz |
|------------|-----------|
| `ping` | Confirma que o servidor MCP está no ar |
| `analisar_midia` | Auditoria técnica (ffprobe) de um vídeo ou pasta — entra na mesma fila do pipeline da UI |

Ligado por `quarkus.mcp.server.http.enabled` (padrão `true`, fixada no build) — ver [Configuração](ref-configuracao.md).

---

## Na vitrine pública

Na [vitrine pública](ref-docker.md#vitrine-pública-vps) só alguns `GET` são reais (`/api/status`, `/api/pipeline/status`, `/api/contextos`, `/api/revisao-lore/contextos`, `/api/docs/*`); telemetria, fatias, fluxo, status do LLM e capacidade do diálogo viram **dados de demonstração**; os dois SSE são substitutos só com batimento ou retrato. Todo o resto — incluindo `/mcp` — responde **`403`**.

---

## Exemplo com cURL

```bash
# Análise de mídia
curl -X POST http://127.0.0.1:8099/api/analisar \
  -H "Content-Type: application/json" \
  -d '{"entrada": "C:/animes/DanMachi/Season 04"}'

# Tradução
curl -X POST http://127.0.0.1:8099/api/traduzir \
  -H "Content-Type: application/json" \
  -d '{"entrada": "C:/.../legendas_extraidas", "contextoId": "danmachi"}'

# Acompanhar logs em tempo real
curl -N http://127.0.0.1:8099/api/logs/stream

# Telemetria
curl http://127.0.0.1:8099/api/telemetria
```

---

## Navegação

| Anterior | Próximo |
|----------|---------|
| [← Mapa do Projeto](modulo-mapa-projeto.md) | [Configuração →](ref-configuracao.md) |
