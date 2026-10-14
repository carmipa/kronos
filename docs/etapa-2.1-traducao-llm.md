# 🌐 Módulo: Tradução Local (LLM)

[← Análise de Legenda](etapa-1.4-analise-conteudo.md) | [Tradução sem Lore →](etapa-2.2-traducao-sem-lore.md)

---

## Para que serve

O núcleo do pipeline: traduz cada fala de uma legenda `.ass`/`.ssa`/`.srt` do inglês para PT-BR usando um **LLM rodando 100% localmente** via [LM Studio](https://lmstudio.ai/), com **cache persistente** (evita retraduzir falas já processadas) e **contexto/lore por anime** (nomes próprios, gênero de personagens, terminologia).

![Painel de Tradução Local](../src/main/resources/static/img/screenshots/traducao-local.webp)

---

## Pacote e classes principais

| Classe | Papel |
|--------|-------|
| `ProcessarArquivoUseCase` (fatia `traducao`, `application`) | Orquestra um arquivo: guarda obra×contexto, lê a legenda, consulta o cache, manda as pendências ao `TradutorLotesService`, aplica as pós-transformações e o portão, publica (mescla de três vias) e grava o cache |
| `GuardaContextoObraTraducao` (`traducao/application`) | Portão de entrada: bloqueia o arquivo cuja pasta é reconhecida como de **outra** obra (ou de várias) — antes de ler a legenda, chamar o LLM ou gravar qualquer coisa |
| `ResolvedorCacheTraducao` (`traducao/application`) | Endereço do cache do episódio e carimbo de proveniência, a partir do contexto congelado do job |
| `AvaliadorTraducaoCache` (`traducao/application`) | Decide se uma entrada de cache pode ser reaproveitada (ou reparada sem chamar o modelo) e se o resultado final é tradução válida |
| `TradutorLotesService` (`traducao/application`) | Mascara as tags, monta os lotes (inclusive o agrupamento de frase partida) e restaura as tags na resposta |
| `ProcessarEpisodioUseCase` (`traducao/application`) | Envia os lotes ao LLM, com novas tentativas em temperatura maior e segunda opinião opcional, e registra a causa de cada fala em que desiste |
| `LlmClientAdapter` (`traducao/infrastructure/adapters`) | Implementa `LlmPort` (peer **`llm`**) — cliente HTTP OpenAI-compatible para o LM Studio; é o **ponto de composição** do peer `llm` |
| `CacheTraducaoService` / `EntradaCache` / `ProvenienciaCache` (peer **`cachetraducao`**) | **Dono único** do cache: persiste o par original↔traduzido por arquivo e carimba a **proveniência** (ver abaixo) |
| `GerenciadorContexto` / `ProvedorContexto` / `CatalogoLoreSqlite` (peer **`lore`**) | Lore por anime: um arquivo `src/main/resources/lore/obras/<id>.sql` por obra, lido pelo `CatalogoLoreSqlite` — ver [Contextos & Lore](modulo-contextos-lore.md) |
| `LeitorLegendaAss` / `EscritorLegendaAss` / `LeitorLegendaSrt` / `EscritorLegendaSrt` (peer **`legenda`**) | Parser/escritor da legenda — preserva timestamps e formatação **byte a byte**, só troca o texto |
| `MascaradorTags` / `ValidadorTraducaoService` / `DetectorTraducaoIdenticaService` / `CorretorHomografoComOriginal` / `EnforcadorTermosLore` (peer **`qualidadeTraducao`**) | Máscara de tags ASS; detecção de resíduo, alucinação, **recusa ou meta-resposta do modelo** e tradução idêntica ao original (falha silenciosa); correção de homógrafo (`e`/`é`, `esta`/`está`) com prova do original; reforço da terminologia da lore |
| `FronteiraTermoAss`, `dicionarioOrtografia.CorretorOrtograficoLegenda` (`core.texto`) | Fronteira de termo dentro de legenda ASS (respeita a quebra `\N`) e correção de acento pelo dicionário do sistema |

> As classes de cache, legenda, lore, qualidade e LLM vivem em **fatias peer próprias** (`cachetraducao`, `legenda`, `lore`, `qualidadeTraducao`, `llm`), importadas pela Tradução Local por uma fronteira **congelada por ArchUnit** — ver [Arquitetura](arquitetura.md#fatias-verticais-peers-e-fronteiras-congeladas-archunit).

---

## Por que o parser de `.ass` nunca interpreta timestamps

Uma decisão de design deliberada: `LeitorLegendaAss` separa cada linha `Dialogue:`/`Comment:` em **`prefixo`** (tudo até o penúltimo campo do `Format:` — inclui `Start`, `End`, `Style`, posicionamento) e **`texto`** (só o último campo, que vai para tradução). O parser **nunca decompõe `H:MM:SS.cc`** — o prefixo é tratado como string opaca e devolvido bit a bit idêntico na escrita. Isso garante que **a tradução nunca pode introduzir dessincronização de tempo** — se uma legenda sai dessincronizada depois de traduzida, a causa está em outro lugar (ex.: legenda de origem que já veio de um release diferente do vídeo — ver [Solução de Problemas](ref-solucao-problemas.md)).

---

## Fluxo de execução (cache-aware)

```mermaid
sequenceDiagram
    actor Op as Operador
    participant API as TraducaoController
    participant UC as ProcessarArquivoUseCase
    participant Guarda as GuardaContextoObraTraducao
    participant Leitor as LeitorLegendaAss
    participant Cache as CacheTraducaoService
    participant Aval as AvaliadorTraducaoCache
    participant Lotes as TradutorLotesService
    participant LLM as LlmClientAdapter
    participant Escritor as EscritorLegendaAss

    Op->>API: POST /api/traduzir {entrada, contextoId}
    API->>UC: processar(arquivo, contexto congelado do job)
    UC->>Guarda: verificar(arquivo, contexto)
    Guarda-->>UC: obra divergente ou ambígua → [BLOQUEADO], nada lido nem gravado
    UC->>Leitor: ler(arquivo)
    Leitor-->>UC: lista de EventoLegenda (prefixo + texto, timestamps intactos)
    UC->>Cache: carregar(arquivo.cache.json, proveniência)
    Cache-->>UC: mapa original → traduzido
    UC->>Aval: cada entrada — reaproveita, repara ([REPARADA]) ou volta ao modelo
    UC->>Lotes: traduzirPendentes(falas sem tradução aproveitável)
    Lotes->>Lotes: mascara tags — {\pos(...)} → [[TAG0]] — e monta os lotes
    Lotes->>LLM: traduzir(lote, prompt da lore congelada)
    LLM-->>Lotes: texto traduzido (ainda mascarado)
    Lotes-->>UC: traduções com as tags restauradas
    UC->>UC: pós-transformações (terminologia, aspas, acentos, dicionário, homógrafo, cartão de data, glossário)
    UC->>UC: portão pós-transformação — reprovou, volta à versão aprovada ([ DESFEITA ])
    UC->>Escritor: publica com mescla de três vias contra cache/obra/.publicado/
    Escritor-->>UC: arquivo_PT-BR.ass
    UC->>Cache: salva o cache do arquivo (uma vez, no fim — ou no parcial, se parar)
    UC-->>API: resultado do arquivo (SSE canal "traducao")
```

---

## Cache de tradução

- **Formato:** JSON, lista de `EntradaCache(indice, estilo, original, traduzido, idiomaOriginal, idiomaTraduzido)`.
- **Localização:** `cache/<pasta da obra>/<nomeLegenda>.cache.json` — a pasta da obra é a **avó** do arquivo (`<Obra>/legendas_extraidas_ass/ep.ass` → `cache/<Obra>/ep.cache.json`). Consequência medida em 25/09/2026: duas pastas com o mesmo nome de obra e o mesmo nome de arquivo **compartilham o cache** — uma cópia de teste precisa de nome de obra próprio para não ler nem gravar o cache real. Exceção: a [Tradução sem Lore (2.2)](etapa-2.2-traducao-sem-lore.md) grava em `cache/<obra>/sem_lore/`, que a 2.1 nunca lê.
- **Gravação:** o cache é gravado **uma vez por arquivo**, no fim — e também quando a execução para no meio, com o que já foi traduzido —, não a cada fala.
- **Chave de lookup:** o **texto original**, não o índice — se a mesma frase aparecer em falas diferentes, a mesma tradução é reaproveitada (cada evento mantém seu próprio timestamp, então isso nunca afeta sincronismo).
- **Editável manualmente:** o operador pode abrir o `.cache.json` e corrigir uma tradução na mão. Na próxima execução a correção só é reaproveitada se passar no `AvaliadorTraducaoCache` (a mesma régua aplicada à saída do LLM); se não passar, é reparada sem chamar o modelo (`[REPARADA]`) quando o defeito tem conserto conhecido, ou volta ao modelo.
- **Entradas de falha:** fala que termina pendente é salva com `traduzido` **vazio** (a legenda publica o original). Na execução seguinte ela volta ao LLM. Caches antigos podem ainda ter `original == traduzido`, que é o "fallback de falha" que os fluxos de [Correção & Revisão](etapa-2.3-correcao-revisao.md) tratam.
- **Segunda opinião não entra no cache:** fala traduzida pelo `modelo-recuperacao` é publicada, mas não gravada — o carimbo de proveniência declara um modelo só.
- **Entrada com defeito de par consertável é consertada, não retraduzida (09/10/2026):** nome trocado ("Desista, Chuck." para "Give it up, Keith.") ou "Sim..." no lugar de "No..." é desfeito na própria entrada, que passa de novo pelo portão de reaproveitamento antes de ser usada (`[REPARADA] entrada de cache reparada sem chamar o modelo`). Mandar a fala inteira ao modelo trazia o mesmo nome errado e piorava o resto ("Core Fighter" virou "Lutador Central"). Medido no 0083: 64 das 70 entradas recusadas se consertaram assim, e as trocas de nome publicadas caíram de 65 para 0.
- **Proveniência (`ProvenienciaCache`):** cada arquivo de cache carrega um hash de proveniência (`contextoHash` = SHA-256 do prompt de sistema/lore, mais modelo e idiomas). Se a **lore ou o modelo mudam**, o cache anterior é **arquivado e não reusado** — a fala é retraduzida sob o contexto atual, em vez de servir uma tradução feita sob outra lore. Exceção opcional: `tradutor.reuso-entre-modelos` (padrão `false`) permite reaproveitar o cache de **outro modelo** com a mesma lore; o cache passa a carimbar `modeloHerdado` para dizer de quem herdou. Lore diferente continua invalidando.

---

## Reexecução, parada e causa das pendências (auditoria de 25/09/2026)

- **Reexecutar sobre uma pasta já traduzida faz mesclagem de três vias**, linha a linha, contra o
  registro do que o cache gerou na publicação anterior (`cache/<obra>/.publicado/`):
  linha que o arquivo não mudou recebe a do cache (é assim que a correção da
  [2.3](etapa-2.3-correcao-revisao.md) chega); linha **corrigida direto no `.ass`** (Revisão de
  Lore, Concordância, edição manual) e não mudada no cache é **mantida**; mudou dos dois lados é
  conflito — mantém a do arquivo e avisa com exemplo (`[ MESCLA ]`). Conteúdo final idêntico ao
  publicado não é regravado; qualquer substituição tem backup em `backups/traducao/`.
- **Sem o registro** (legendas publicadas antes de 25/09/2026, ou linhas incluídas/removidas no
  arquivo), não dá para separar os dois casos: a reexecução publica a versão do cache, com backup
  obrigatório e `[ ATENÇÃO ]` explicando os dois cenários.
- **Parar:** "Sair" e queda do LM Studio no meio salvam no cache o que já foi traduzido; a próxima
  execução recomeça de onde parou. Só processo morto à força perde o episódio em curso. A parada
  pedida sai como `[PARADO]` / lote `CANCELADO`, não como falha.
- **Causa da pendência:** quando o pipeline desiste de uma fala e mantém o original, o relatório diz
  a causa real (`ESTRUTURA_DIVERGENTE` para contagem de linhas errada, `CONTEUDO_NAO_ANCORADO` para
  entidade trocada, locutor inventado ou tradução desproporcional), e não "o modelo devolveu o
  original". `ECO` fica só para eco de verdade.
- **Quadros de transição:** as cópias de 0,08 s com `\clip` de uma fala (efeito de apagar) são
  excluídas do LLM como letreiro animado; elas recebem a tradução da fala irmã **do mesmo estilo**,
  com as próprias tags — a legenda não pisca mais português → inglês. Verso de música com o mesmo
  texto não herda (outro estilo).
- **Conferência pós-gravação (A6):** relê o arquivo gravado. O itálico removido na saída não é
  divergência; fala mantida porque a fonte já estava em português aparece como NÃO VERIFICADA, com
  exemplos, porque não passou pelo LLM nem pela validação.
- **Portão depois da última transformação (08/10/2026):** a terminologia, as aspas, os acentos, o
  dicionário, o homógrafo, o cartão de data e o glossário mexem na fala DEPOIS do portão. Antes de gravar, cada fala transformada é
  conferida de novo; se a transformação a deixou reprovável e a versão que o portão aprovou passa,
  volta a aprovada (`[ DESFEITA ]`, e o placar `[ PORTAO POS-TRANSFORMACAO ]` sai sempre). Era o que
  a A6 acusava sem poder agir: em 17/09, a troca de termo composto apagava o `\N` de dentro do termo
  ("Quin\NMantha") e duas falas iam ao ar numa linha só. A troca agora devolve a quebra no lugar.
- **Dicionário, termos de lore e a quebra `\N`:** o dicionário nunca acentua termo de lore, nem
  palavra de dentro de termo de lore composto — o "Aida" de "Aida Rayhunton" fica sem acento, e
  "o virus" comum continua virando "vírus". Um termo não casa a partir do `N` da quebra `\N`
  ("informação\Nao controle" não vira "\Não controle"), e o homógrafo lê como coordenador o "and"
  colado na quebra `\N` do inglês. A linha `[ ORTOGRAFIA ]` sai sempre, com um de três estados:
  dicionário indisponível, 0 fala corrigida ou N falas corrigidas.
- **Alternativa inventada (08/10/2026):** "cansado(a)", "cansada/o", "Bomba(s)" ou nota do modelo
  ("(com tom sarcástico)") que o original não tem fazem a tentativa pedir outra temperatura. Na
  última tentativa a fala é aceita como veio (`[ ALTERNATIVA ]`) — nunca vira inglês. No cache, a
  entrada assim volta ao modelo a cada execução. Medido: o aya mantém o "(a)" em boa parte das
  falas, e dizer a ele o motivo da recusa não ajudou.

---

## Proteção de tags ASS (`MascaradorTags`)

Falas de karaokê/efeitos têm prefixos de formatação complexos:

```
{\fad(100,100)\blur2\c&HE8E8E8&\1a&HFF&}Kitto Soba de Hohoendeitai
```

Antes de enviar ao LLM, o `MascaradorTags` substitui blocos `{...}` por marcadores neutros (`[[TAG0]]`, `[[TAG1]]`...), pedindo ao LLM para preservá-los literalmente. Isso evita dois problemas comuns de LLMs com texto estruturado: (1) tentar "traduzir" o conteúdo da tag, e (2) alucinar/corromper a sintaxe da tag. Falas de estilos listados em `tradutor.estilos-ignorados` (karaokê romaji, títulos de abertura/encerramento) **nem chegam a ser enviadas ao LLM** — são copiadas como estão.

---

## Prevenção de alucinação: lote de 1 linha

`tradutor.tamanho-lote: 1` — cada requisição ao LLM traduz **uma única fala por vez**, não um lote de N falas. Isso é mais lento (mais chamadas HTTP), mas elimina uma classe inteira de erro: LLMs locais menores frequentemente perdem a contagem de linhas em lotes maiores (retornam menos ou mais linhas que o pedido), desalinhando toda a tradução subsequente do arquivo.

**Exceção — frase partida:** com `tradutor.agrupar-frase-partida: true` (ligado no `application.yml`), as falas vizinhas que formam uma frase partida entre eventos vão juntas numa mesma chamada, para o modelo ver a frase inteira. Se a guarda de deslocamento (`GuardaCorrenteTraduzida`) reprovar o resultado do grupo, cada fala dele é retraduzida individualmente.

---

## Modelo "coringa": `tradutor.llm.model: "current"`

O valor de `tradutor.llm.model` em `application.yml` é **sempre** o literal `"current"`, nunca o id fixo de um modelo (ex. `"mistralai/mistral-nemo-instruct-2407"`). Ao iniciar cada operação, `LlmClientAdapter.verificarDisponibilidade()` consulta o LM Studio para descobrir **qual modelo está de fato carregado em memória** (via a API estendida `/api/v0/models`, que expõe o campo `state: "loaded"`) e adapta o valor em runtime. Isso permite trocar o modelo ativo direto na UI/CLI do LM Studio (`lms load`) sem tocar no `application.yml` nem recompilar — e evita que o app dispare um **auto-load de uma segunda instância de modelo** ao mandar uma requisição para um id que não bate com o que está carregado (ver detalhes técnicos em [Solução de Problemas](ref-solucao-problemas.md#lm-studio-carregando-dois-modelos-simultaneamente)).

---

## Concordância de gênero: limitação conhecida

Em PT-BR, adjetivos e particípios concordam em gênero com quem fala ("Estou cert**o**" vs "Estou cert**a**"). Só que a fonte `.ass` de fansub **não diz quem fala** — a coluna `Name`/Actor vem vazia (no Gundam 08th MS Team, em 325/325 falas). Como o pipeline traduz **linha a linha** (`lote=1`) e dedup/cacheia por texto original puro, o modelo não tem como inferir o gênero e erra: `Thank you, Norris.` — dito pela **Aina** — vira "Obrigad**o**".

A Tradução Local **não** tenta resolver isso: é responsabilidade da [Revisão de Concordância (3.3)](etapa-3.3-revisao-concordancia.md), que atua depois, sobre a legenda já traduzida.

> Um motor experimental de correção por contexto de cena (piloto D) chegou a viver dentro desta fatia e foi **removido**: era tiro único, sem retry, e descartava o motivo da rejeição — esvaziou 22,6% das falas de diálogo em silêncio.

---

## Recuperação de pendências (tradutor de máquina)

Quando o LLM local esgota as tentativas e uma fala de **diálogo** continua pendente, a fatia pode acionar um tradutor de máquina como **último recurso** — opt-in por `tradutor.fallback-online.ativo`. O escopo é estrito: **só as falhas desta execução**, nunca uma varredura de cache antigo.

O contrato é a porta própria `FallbackTraducaoMaquinaPort`, que devolve um `ResultadoFallback` **tipado** — tradução, provedor, status e motivo. O retorno anterior (`Optional<String>`) colapsava nove desfechos num único "vazio" e apagava a razão da recusa; hoje toda tentativa que não recupera é registrada com a causa e contabilizada em `ResultadoRecuperacao`, que o console imprime como `Desfecho por causa: ...`.

**Guarda de terminologia.** Uma candidata só é aceita se preservar o que a obra exige. São exigidos apenas três tipos de token:

| Categoria | Exemplo | Origem |
|---|---|---|
| Termo da obra ativa | `Zaku`, `Rygart Arrow` | `LoreAtivaPort.termosProtegidosAtivos()` |
| Sigla / acrônimo | `MS`, `EFF` | CAIXA ALTA com ≥2 caracteres — exceto quando a linha inteira está em caixa alta (cartela de título), onde caixa alta é estilo |
| Identificador | `RX-78`, `2148` | contém dígito (ordinal inglês exige só os dígitos: `12th` → `12`) |

Palavra capitalizada comum **não** obriga. A regra anterior — qualquer maiúscula no meio da frase era "nome próprio" — tornava impossível traduzir um título em Title Case (`The Battle in Three Dimensions` exigia `Battle` em português) e recusava a resposta de **qualquer** provedor, já que a verificação ocorre depois dela. Medição sobre as 560 pendências reais dos caches versionados: **323 (57,7%)** caíam só por esse falso-positivo; 35 (6,2%) dependem de termo legítimo e seguem protegidas.

*Trade-off declarado:* um nome próprio que a obra não declara deixa de ser protegido por esta guarda — a rede de segurança passa a ser a validação canônica a jusante, a mesma aplicada à saída do LLM.

---

## Endpoint REST

### `POST /api/traduzir`

```json
{
  "entrada": "C:/animes/[Sokudo] DanMachi/Season 04/legendas_extraidas",
  "saida": "C:/animes/[Sokudo] DanMachi/Season 04/legendas-ptbr",
  "contextoId": "danmachi_s4",
  "permitirRetraducao": false
}
```

| Campo | Obrigatório | Descrição |
|-------|:-----------:|-----------|
| `entrada` | ✅ | Pasta **existente** com legendas `.ass`/`.ssa`/`.srt` em inglês. Só o nível dela é lido (sem subpastas), e pasta de saída de tradução (ex.: `traducao_ptbr`) é recusada com `400` |
| `saida` | ⚪ | Pasta de saída. Vazia: `traducao_ptbr` ao lado da pasta de entrada. Cada arquivo sai como `<nome sem _ENG>_PT-BR.<extensão>` |
| `contextoId` | ✅ | Id de um dos contextos/lore cadastrados (ver [Contextos & Lore](modulo-contextos-lore.md)). **Não há obra padrão**: vazio ou desconhecido responde `400` |
| `permitirRetraducao` | ⚪ | Retradução integral: o cache anterior é arquivado e não é reaproveitado, a saída é substituída (com backup) sem mescla, e entrada que parece já traduzida deixa de ser bloqueada |
| `ignorarLoreExistente` | ⚪ | Só vale com `contextoId = sem_lore` ([2.2](etapa-2.2-traducao-sem-lore.md)): dispensa o portão de obra. Com outro contexto é recusado e o portão confere normalmente |

A resposta imediata é `200 OK` (job assíncrono); o progresso e o relatório de cada lote chegam via **SSE** no canal `traducao`. Arquivo cuja pasta é reconhecida como de outra obra sai `[BLOQUEADO]` e o lote segue. No fim do lote, o canal `traducao-finalizada` recebe `<desfecho>|<som>`: a máquina toca 3 bipes, e a tela só toca o próprio aviso se a máquina não conseguiu.

---

## Mensagens da tela

| Mensagem | Quando aparece |
|----------|----------------|
| "Lista de contextos de tradução ainda não carregou. Aguarde ou recarregue a página antes de iniciar." | A lista de obras ainda não chegou do servidor (ou falhou ao carregar) |
| "Escolha a obra (lore) no seletor antes de iniciar a tradução." | A lista carregou, mas nenhuma obra foi escolhida |

---

## Navegação

| Anterior | Próximo |
|----------|---------|
| [← Análise de Legenda](etapa-1.4-analise-conteudo.md) | [Tradução sem Lore →](etapa-2.2-traducao-sem-lore.md) |
