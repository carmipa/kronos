# 🩹 Etapa 3.1 — Revisão de Legendas

[← 2.3 Correção de Cache](etapa-2.3-correcao-revisao.md) | [3.2 Revisão de Lore →](etapa-3.2-revisao-lore.md)

---

## O que ela é

A primeira das três telas de **Qualidade**. Ela varre `.ass`/`.ssa` **já traduzidos** e ataca a
**falta de tradução**: falas que ficaram em **inglês** ou idênticas ao original. Concordância só é
corrigida aqui quando a **regra local, gratuita**, resolve sozinha; o resto fica pendente como
`FORA_DO_ESCOPO_DA_TELA` e pertence à [3.3 Revisão de Concordância](etapa-3.3-revisao-concordancia.md).

Diferente da `2.3 Correção de Cache`, que trabalha o **banco de cache**, a 3.1 lê e grava os
**próprios arquivos `.ass`** — é a legenda entregue que muda.

![Painel 3.1 Revisão de Legendas](../src/main/resources/static/img/screenshots/revisao-legendas.webp)

---

## As duas passadas — independentes, e qual você roda

```mermaid
graph TD
    A["📁 Pasta PT-BR (.ass)<br/>é esta que será reescrita"] --> REF{"De onde vem<br/>o inglês?"}
    REF -->|"Ambos (padrão)"| R1["📄 _Track2.ass na mesma pasta<br/>ou pasta EN informada<br/>+ cache do projeto"]
    REF -->|"Cache (sem EN)"| R2["🗃️ cache/**/*_ENG.cache.json"]

    R1 --> P1
    R2 --> P1

    P1["🧠 PASSADA 1 — Traduzir o que faltou<br/><b>é esta que você roda</b>"] --> LLM["LLM local (conhece a lore)"]
    LLM --> OK1{"resolveu?"}
    OK1 -->|"sim"| DIC["📚 dicionários ajustam a proposta"]
    OK1 -->|"não"| PEND["fala fica PENDENTE<br/>o Google não é chamado"]
    DIC --> PORT{"🚦 portão de qualidade<br/>melhora a legenda?"}
    PORT -->|"não"| DESC["descartada — fala fica pendente"]
    PORT -->|"sim"| GRAVA["💾 backup + grava no .ass"]

    P2["🌐 PASSADA 2 — Só o Google<br/><i>escolha explícita do operador</i>"] --> GOO2["Google Tradutor"]
    GOO2 --> DIC

    classDef entrada fill:#1e3a5f,stroke:#3B82F6,color:#F9FAFB
    classDef motor fill:#4c1d95,stroke:#C4B5FD,color:#F9FAFB,stroke-width:2px
    classDef web fill:#134e4a,stroke:#2DD4BF,color:#F9FAFB
    classDef ok fill:#14532d,stroke:#4ADE80,color:#F9FAFB
    classDef ruim fill:#7f1d1d,stroke:#F87171,color:#F9FAFB
    classDef neutro fill:#1e293b,stroke:#6B7280,color:#F9FAFB
    class A,R1,R2 entrada
    class P1,LLM,P2 motor
    class GOO2,DIC web
    class GRAVA ok
    class DESC,PEND ruim
    class REF,OK1,PORT neutro
```

| passada | motor | quando usar | endpoint |
|---|---|---|---|
| **1 — Traduzir o que faltou** | LLM local + dicionários (**sem Google**) | é a padrão | `POST /api/revisar-legendas-concordancia` |
| **2 — Só o Google** | Google Tradutor isolado | LM Studio fora do ar, ou por escolha explícita | `POST /api/revisar-legendas` |

> Os nomes dos endpoints são **históricos** e não descrevem mais o que cada botão faz. O que manda
> é a tabela acima — ela foi lida do `revisao.js`, não da memória.

**Não há cascata para o Google.** O que o LLM da passada 1 não resolve fica **pendente**; o Google
só roda na passada 2, quando o operador escolhe. Os dois botões são isolados de propósito: o Google
retraduz o inglês inteiro e substituiria o que já estava traduzido numa fala parcial. No Google,
fala com tag inline (`{\i1}…{\i0}`) pode voltar como `TAG_CORROMPIDA` e também fica pendente.

Fragmento de frase partida **entre eventos** é preservado sem traduzir (`FRAGMENTO_CROSS_EVENTO`):
traduzido isolado, o modelo completaria a frase com conteúdo inventado.

---

## O cartão do alvo — a cicatriz que ele carrega

A tela mantém à vista **qual lore está ativa** e **qual pasta será reescrita**:

```
Lore ativa: DanMachi (Geral). Pasta: ainda não informada.
```

O rótulo *"pasta que será reescrita"* é deliberado. Em 06/08/2026 uma tradução apontou para
`legenda-simplificada`, que é pasta de **saída**, e sobrescreveu **17 arquivos limpos**. O código
fez o que foi mandado; a interface é que permitiu mandar.

Desde 19/08/2026 o cartão tem **dono único** (`js/cartaoAlvoAtivo.js`), importado pelas telas 3.1,
3.2, 3.3 e 4.1. Antes, o mesmo cartão existia copiado em três lugares e as cópias **já tinham
divergido**: a da 3.2 interpolava a pasta no `innerHTML`, a da 3.1 usava `textContent`. A versão
compartilhada é a segura — provada com caso-controle no navegador: a pasta
`C:\animes\<b>injecao</b>\pt` sai como **texto**, não como HTML.

A guarda `CatracaCartaoDoAlvoTemDonoUnicoTest` impede a divergência de voltar.

---

## Backup — a primeira cópia da sessão nunca é sobrescrita

Cada execução cria a sua pasta `backups/revisao-legendas/revisao_<yyyyMMdd_HHmmss_SSS>/`, e cada
arquivo tocado ganha cópia ali **antes** da escrita — só quando origem e destino são o **mesmo
arquivo** (gravar em outra pasta não precisa de backup). Dentro da execução, um arquivo escrito de
novo **não** substitui a cópia já feita: a primeira é a que interessa quando algo dá errado.

---

## Contrato de tags — o que impede a legenda de quebrar

A revisão herda o mesmo contrato da tradução: toda proposta que **perca, duplique, reordene ou
invente** um marcador `[[TAGn]]` é descartada e provoca nova tentativa. Nenhuma versão
estruturalmente divergente é publicada.

As quebras internas `\N` seguem caminho separado: são retiradas antes da chamada ao LLM e
recolocadas **deterministicamente** no limite textual mais equilibrado depois da resposta. Os
termos canônicos são mascarados pela proteção de lore entre as duas etapas e restaurados antes da
validação final.

O detector de idioma analisa o texto **sem os termos já reconhecidos pela lore**. Assim,
`Mont Blanc` (nave do Zeta) não vira "vazamento de francês" por causa de `blanc`, enquanto francês
real (`aura bleu`) continua sendo rejeitado.

---

## Concordância na 3.1 × concordância na 3.3

O detector da 3.1 **acha** suspeita de gênero, mas a tela só a **corrige** pela regra local
(contradição objetiva com o inglês, sem chamar LLM nem Google). O que a regra local não resolve fica
pendente como `FORA_DO_ESCOPO_DA_TELA` — a fala é preservada e o caso é da 3.3:

| | 3.1 Revisão de Legendas | 3.3 Revisão de Concordância |
|---|---|---|
| precisa do inglês | **sim** (ou do cache) | não |
| usa LLM | sim, só para **falta de tradução** | **não** |
| usa lore | sim | não (a obra é só rótulo) |
| concordância | só a regra local gratuita; o resto fica pendente | cadeia de corretores sem modelo |
| custo | minutos por lote | segundos |

Do corretor da 3.1, apenas **dois** tratamentos são PT-only e puderam ser copiados para a 3.3
(`graças ao deus → graças a Deus` e o possessivo de parentesco `minha pai → meu pai`); todo o
resto exige o original em inglês. Essa cópia foi consciente e medida: ganho **zero** nas 332.545
falas do acervo de hoje — estão lá para a tradução de amanhã, e isso fica escrito em vez de virar
promessa.

> **A exceção do `a`**, que a 3.1 já documentava e a 3.3 teve de reaprender: antes de substantivo
> masculino, `a` é **preposição** e está certo (`"Graças a Deus"`). O detector da 3.1 sempre deixou
> o `a` de fora; o corretor da 3.3 nasceu de uma segunda escrita da mesma ideia e **perdeu a
> exceção** — 14 estragos para 1 acerto no acervo, até ser medido. É a divergência que a regra da
> medição prevê: critério que a produção já implementa é **consultado**, nunca reescrito.

---

## Música e karaokê: **veto absoluto**

Música e karaokê, inclusive em inglês (estilo `Song ENG`), pertencem à
[4.1 Karaokê](etapa-4.1-traducao-karaoke.md), que sabe lidar com KFX, camadas e tempo por sílaba.
A 3.1 **não** audita nem reescreve linha de estilo musical ou efeito de karaokê: a letra fica como
está até a 4.1 tratá-la.

Se mesmo assim uma linha de estilo musical sair alterada, o resumo por estilo no console a marca em
vermelho (`esta tela veta música`) e pede para conferir o backup. A guarda
`CatracaEscritaDeFalaVetaMusicaTest` impede nova porta de escrita sem o veto.

---

## Quanto defeito existe hoje

Medido em 19/08/2026 sobre o acervo entregue:

```
detector 3.1 (com inglês de referência) ..... 60 suspeitas em 122.640 falas   (0,05%)
```

Instrumento saturado perto de zero é sinal de que o alvo está pequeno — e é motivo para **medir
antes de ampliar**, não para ampliar as famílias de correção.

---

## Por dentro — como a suspeita nasce antes de gastar o modelo

`AuditorProblemasLegendaService` agrega **três fontes de suspeita**, todas regex, **antes** de
decidir se vale acionar o LLM:

1. `ValidadorTraducaoService` — resíduo em inglês / alucinação
2. `DetectorTraducaoIdenticaService` — fala idêntica ao original (não traduzida)
3. `DetectorConcordanciaService` — erro de gênero PT-BR: cruza pronomes do original
   (`he/she/him/her`) com artigos, substantivos, particípios e pronomes da tradução, procurando
   incompatibilidade (o inglês usa `"her"`, a tradução diz `"dele"`)

```mermaid
sequenceDiagram
    participant UC as RevisarLegendasUseCase
    participant Aud as AuditorProblemasLegendaService
    participant Det as DetectorConcordanciaService
    participant LLM as LlmPort

    UC->>Aud: auditar(original, traducao)
    Aud->>Aud: ValidadorTraducaoService (resíduo EN?)
    Aud->>Aud: DetectorTraducaoIdenticaService (não traduzida?)
    Aud->>Det: analisar(original, traducao)
    Aud-->>UC: lista de problemas detectados

    alt há suspeita
        UC->>UC: regra local (sem custo) — se resolver, grava
        alt falta de tradução
            UC->>LLM: corrigirTraducao / revisarConcordancia (texto mascarado)
            LLM-->>UC: proposta
            UC->>UC: dicionários + portão de qualidade
        else concordância ou estilo
            UC->>UC: pendente FORA_DO_ESCOPO_DA_TELA (3.3)
        end
    else sem suspeita
        UC->>UC: mantém a fala como está
    end
```

Só falta de tradução com suspeita real vira chamada ao modelo — é o que mantém o custo baixo.

O mesmo caso de uso conserta **"karaokê quebrado"** (chaves `{texto}` sem `\` ou `=` na frente) por
regex, antes de qualquer chamada externa.

### O catálogo de lore que a revisão usa

Uma auditoria dos 50 `.ass` ingleses do Zeta ampliou o catálogo operacional com nomes
**efetivamente usados nos diálogos**: `Dogosse Gier`, `Rosammy`, `Haro`, `Shinta`, `Qum`,
`Green Noa`, `Green Oasis`, `Von Braun City`, `Bosnia`, `Sudori`, `Baund Doc` e o elenco secundário
recorrente.

Dos aliases curtos, só `Four` é protegido sozinho (são 188 usos do nome no Zeta, contra 3 de
`Side Four`); `Fa` e `Bright` não são protegidos isoladamente, porque o casamento ignora caixa — as
formas completas (`Four Murasame`, `Fa Yuiry`, `Bright Noa`) continuam protegidas, e `Fala`/`Fá`
→ `Fa` e `Brilhante` → `Bright` são restaurados pelo mapa de terminologia. Esse mapa é o mesmo
(uma tabela por obra) que a [3.2](etapa-3.2-revisao-lore.md) também lê, e restaura variantes
realmente observadas — `Dogosse Giar → Dogosse Gier`, `Quem → Qum`, `Mancack → Manack`,
`Ramus → Ramsus` — sempre **só quando o inglês original contém o canônico correspondente**.

---

## Contrato REST

| Endpoint | Payload | Canal SSE |
|----------|---------|-----------|
| `POST /api/revisar-legendas-concordancia` | `{entrada, saida?, contextoId, modoReferencia, caminhoCache?}` | `revisao` |
| `POST /api/revisar-legendas` | `{entrada, saida?, contextoId, modoReferencia, caminhoCache?}` | `revisao` |

`entrada` é a pasta PT-BR (**obrigatória**, é a que será reescrita). `saida` é a pasta dos `.ass`
em **inglês** (nome histórico do campo). `modoReferencia` é `AMBOS` (padrão) ou `CACHE`; no modo
`CACHE`, `caminhoCache` aponta a pasta de cache, e pasta inexistente ou sem nenhum `.cache.json`
devolve **HTTP 400**. `contextoId` desconhecido também devolve **HTTP 400**. Indisponibilidade do
LLM aparece no console SSE **sem derrubar a fila**.

---

## Guardas que protegem esta tela

| guarda | o que impede |
|---|---|
| `CatracaCartaoDoAlvoTemDonoUnicoTest` | o cartão do alvo divergir entre as telas |
| `CatracaEscritaDeFalaVetaMusicaTest` | escrita em fala de estilo musical (música é da 4.1) |
| `CatracaTokenDeControleEmTodaPortaLlmTest` | token de template do modelo vazar para a legenda |
| `CatracaBordaAssincronaConfereCaminhoTest` | a borda dizer "iniciada" para pasta inexistente |
| `CatracaAvisoSonoroNasTelasLongasTest` | o lote terminar em silêncio |

---

## Navegação

| Anterior | Próximo |
|----------|---------|
| [← 2.3 Correção de Cache](etapa-2.3-correcao-revisao.md) | [3.2 Revisão de Lore →](etapa-3.2-revisao-lore.md) |
