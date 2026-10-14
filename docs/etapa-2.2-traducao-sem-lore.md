# ⚡ Etapa 2.2 — Tradução sem Lore

> Traduz o anime que **ainda não tem lore declarada**. É o caminho do "acabei de baixar e quero
> ver como fica" — e a página existe para deixar escrito, antes de você clicar, **exatamente o
> que se perde** por não ter lore.

---

## Para que serve

A [Tradução Local (2.1)](etapa-2.1-traducao-llm.md) exige escolher a obra, porque é a lore dela
que protege nome próprio, patente, mecha e facção. Para um anime recém-baixado essa lore não
existe, e escrevê-la leva tempo.

Esta tela remove a exigência: **não pede obra**. Em troca, não aplica terminologia nenhuma.

![Painel de Tradução sem Lore](../src/main/resources/static/img/screenshots/traducao-sem-lore.webp)

O contexto usado é o `sem_lore`, um id **reservado** do contrato (`ProvedorContexto.ID_SEM_LORE`).
A lore de cada obra vive num arquivo SQL próprio (`src/main/resources/lore/obras/<id>.sql`), e
todos são carregados num SQLite em memória quando a aplicação sobe; o `sem_lore` é um desses
arquivos, `sem_lore.sql`, só com o prompt genérico e nenhum termo.

---

## O que você perde sem lore

| perda | consequência concreta |
|---|---|
| **Nome próprio desprotegido** | sem lista de termos protegidos, nada impede um personagem chamado `Sky` virar `Céu`. O prompt **pede** para manter — pedido não é garantia. |
| **Sem reforço determinístico** | o `EnforcadorTermosLore` não tem mapa para aplicar. É ele, e não o `termosProtegidos`, quem de fato garante a grafia canônica depois da tradução. |
| **Sem grafia canônica** | `Quattro` volta `Quatro`, `Ple` volta `Plee`, e ninguém restaura. |

## O que continua protegido

Sem lore **não** significa sem guarda. Estes seguem valendo:

- tags ASS/SSA — mascaradas antes do LLM e reconferidas na volta;
- detecção de **alucinação** e de **meta-resposta** (`PADRAO_RECUSA_META`), que impede o modelo
  responder em vez de traduzir;
- identificadores numéricos preservados;
- **resíduo em inglês** e **eco do original** acusados como pendência;
- veto de camada musical — karaokê não vai ao LLM.

---

## A saída fica separada, e isso é deliberado

O resultado vai para **`traducao_ptbr_sem_lore`**, e não para `traducao_ptbr`. Duas razões:

1. **A definitiva não é sobrescrita.** Quando a lore de verdade existir, você roda a 2.1 e a saída
   boa nasce na pasta de sempre, sem conflito.
2. **O cache desta execução fica separado.** A 2.2 grava em `cache/<obra>/sem_lore/`; a 2.1 nunca
   lê essa subpasta, e a manutenção de cache ([2.3](etapa-2.3-correcao-revisao.md)) e a
   [Revisão de Legendas (3.1)](etapa-3.1-revisao-legendas.md) a deixam de fora. Quando a obra
   ganhar lore, a 2.1 não reaproveita nenhuma tradução que nunca teve terminologia.

> ⚠️ A pasta `traducao_ptbr_sem_lore` é **saída**, nunca entrada. Apontar uma tradução para uma
> pasta de saída já sobrescreveu 17 arquivos limpos em 06/08/2026 — hoje a
> `GuardaCaminhoEntrada` barra isso, e a cicatriz está em
> [Catracas e Fronteiras](catracas-e-fronteiras.md).

---

## Fluxo

```mermaid
flowchart LR
    A[Pasta com .ass originais] --> B{contextoId}
    B -->|sem_lore| P{"Pasta de obra<br/>que já tem lore?"}
    P -->|"sim, caixa desmarcada"| X["BLOQUEADO"]
    P -->|"não, ou caixa marcada"| C[Tradução sem terminologia]
    C --> D[Guardas que continuam:<br/>tags · alucinação · eco · resíduo]
    D --> E[traducao_ptbr_sem_lore]
    C --> K[("cache/obra/sem_lore/")]
    K -.->|"a 2.1 nunca lê"| F["2.1 com lore escrita depois:<br/>cache próprio, nada herdado da 2.2"]
```

---

## Contrato REST

Esta tela **não tem endpoint próprio** — usa o mesmo da Tradução Local, com o `contextoId`
reservado. É o backend que decide o resto:

| o que muda | valor quando `contextoId = sem_lore` |
|---|---|
| canal SSE | `traducao-sem-lore` (a 2.1 usa `traducao`) |
| canal de fim de lote | `traducao-sem-lore-finalizada` (a 2.1 usa `traducao-finalizada`) |
| nome da operação na telemetria | `Tradução sem Lore via LLM` |
| pasta de saída, quando não informada | `traducao_ptbr_sem_lore`, ao lado da pasta de entrada |
| cache | `cache/<obra>/sem_lore/` |
| pasta de obra que já tem lore | **bloqueada** por padrão; `ignorarLoreExistente: true` (caixa **"Ignorar lore existente desta obra"**) dispensa o portão de obra — só com `sem_lore` |

Canal SSE próprio, e não uma linha no console da 2.1: as duas telas podem estar abertas, e o
progresso de uma não pode aparecer embaixo da outra. Ver
[Ref — API e Endpoints](ref-api-endpoints.md).

---

## Quando NÃO usar

- **Para entregar.** A saída não passou por terminologia; ela serve para ver, não para arquivar.
- **Quando a obra já tem lore.** Use a [2.1](etapa-2.1-traducao-llm.md) — a lore existe justamente
  para isso. Por padrão esta tela **recusa** a pasta de obra que já tem lore: o arquivo sai
  `[BLOQUEADO]` e a mensagem diz qual contexto é o esperado. A caixa **"Ignorar lore existente desta
  obra"** dispensa essa guarda — só nesta tela — e a terminologia da obra não é aplicada.

---

## Navegação

- ⬅️ [Etapa 2.1 — Tradução Local](etapa-2.1-traducao-llm.md)
- ➡️ [Etapa 2.3 — Correção de Cache](etapa-2.3-correcao-revisao.md)
- 📚 [Contextos de Lore](modulo-contextos-lore.md) — como escrever a lore que falta aqui
- 🏠 [README](../README.md)
