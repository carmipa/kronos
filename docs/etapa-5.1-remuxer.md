# 📦 Módulo: Remuxer

[← 4.2 Correção de Karaokê](etapa-4.2-cura-tags.md) | [5.2 Renomear Arquivos →](etapa-5.2-renomear-arquivos.md)

---

## Para que serve

Última etapa do pipeline: combina o **vídeo original** com a **legenda traduzida** (já revisada/curada) num novo arquivo `.mkv`, via `mkvmerge` (MKVToolNix), preservando vídeo e áudio sem recodificação.

**As legendas originais nunca são removidas.** A PT-BR entra como faixa **padrão** — é a que abre no player — e as que já estavam no arquivo continuam disponíveis como segunda escolha. Não existe modo, opção ou flag que descarte faixa: o caminho que emitia `--no-subtitles` foi **removido** em 2026-07-29, porque perder a legenda de origem é irreversível a partir do MKV remuxado e o material bruto pode não existir mais.

> Esta seção afirmava o contrário até 2026-09-02 — dizia que "por padrão as legendas antigas são removidas". A frase sobreviveu cinco semanas à remoção do caminho destrutivo, em três lugares ao mesmo tempo: aqui, na ficha da tela e no console do CLI, que anunciava "remover legendas originais" enquanto o adaptador preservava tudo.

🚫 **Legenda com fonte vietnamita antiga não vira MKV** (desde 14/10/2026 — o Gundam ZZ foi publicado em 09/10 com o diálogo em `VnBook-Antiqua`, "Nóo, ộ a atraỗóo!" na tela). Na validação de cada legenda ASS, a regra única do kernel (`FonteVietnamitaLegada`) procura a fonte em **qualquer** estilo do cabeçalho (usado ou não) e em `\fn` de fala ou comentário; achou, o console mostra `[REPROVADA — FONTE VIETNAMITA]` com o motivo, o mkvmerge **não** é chamado para aquele episódio, o lote segue os outros e termina `CONCLUIDO_COM_PENDENCIAS` (`fonteVietnamita=N` na telemetria). O remédio é a [1.3 Troca Tipo Legenda](etapa-1.3-troca-tipo-legenda.md) na pasta das legendas e um novo remux. Legenda em ANSI com fonte comum continua passando.

![Painel do Remuxer](../src/main/resources/static/img/screenshots/remuxer.webp)

---

## Antes de tudo: a obra é obrigatória

Desde 2026-09-02 (ordem do Paulo) a 5.1 deixou de ser tela **auxiliar**: os campos de pasta, os botões **Procurar...** e o **Iniciar Remuxing** ficam desabilitados até a obra ser escolhida, exatamente como nas telas de lore. Não existe mais a opção *"Sem obra"* aqui.

O motivo é de operação, não de motor — o remux não consulta lore. É que ele **publica arquivo definitivo, de gigabytes, com nome derivado da legenda**: começar um lote sem ter dito de qual obra se trata é o caminho curto para publicar a temporada errada numa pasta e só descobrir depois, quando o vídeo original talvez já não esteja mais lá.

A trava é a primeira camada; o `remuxer.js` confere de novo antes do POST, porque **botão desabilitado é interface, não autorização** — o painel é reinjetado por `fetch`, e uma reaplicação que falhasse deixaria o botão vivo sem ninguém ver.

---

## Aviso sonoro no fim do lote

O remux é a etapa mais longa do pipeline e era a que terminava em **silêncio**. Desde 2026-09-02 a tela espera a fila reportar `livre` — o instante em que o **último** arquivo do lote foi publicado — e então dá o alerta visual **e** toca três avisos, pelo módulo compartilhado `js/avisoSonoro.js` (o mesmo da Tradução Local, da Revisão de Lore e da 3.3 — nunca uma cópia).

Duas consequências que valem saber:

- **O estado do som é dito no clique, em três valores** (`armado`, `não confirmado`, `indisponível`). O navegador bloqueia áudio que a página inicia sozinha, então o canal nasce no clique que dispara o trabalho. Quem vai sair de perto durante horas precisa saber **antes** se pode confiar no som.
- **O botão fica preso durante todo o lote**, e não mais 3 segundos. O debounce antigo protegia contra a rajada de cliques e deixava o botão vivo durante horas de remux, convidando a um segundo disparo que o servidor recusaria com `409`.

O alerta visual vem **sempre**, e primeiro: som depende de permissão, de volume e de a aba não estar no mudo.

---

## Pacote e classes principais

| Classe | Papel |
|--------|-------|
| `RemuxarLoteUseCase` (`application`) | Orquestra a fila de tarefas de remux do lote |
| `MapeadorMidiaService` | Pareia cada vídeo com sua legenda correspondente na pasta |
| `MkvmergeAdapter` (`infrastructure/adapters`) | Gera em temporário, inspeciona o container e publica sem sobrescrever |
| `PastaDeLegendaDoRemux` (`domain`) | Dono único da pergunta "onde estão as legendas traduzidas?", consultado pela tela e pelo CLI |
| `PlanoRemux` (`domain`) | Expõe tarefas, ausências, ambiguidades e avisos antes da execução |
| `RelatorioRemux` (`domain`) | Consolida sucessos, pendências, falhas, cancelamento e telemetria |

---

## Pareamento vídeo ↔ legenda

```mermaid
graph TD
    A["Pasta de vídeos + Pasta de legendas"] --> B{"Quantos vídeos<br/>e legendas na pasta?"}
    B -->|"1 vídeo + 1 legenda"| C["Pareamento por arquivo único<br/>(ex.: filme) — casa direto,<br/>mesmo com nomes de release diferentes"]
    B -->|"Vários de cada"| D["Identidade normalizada exata;<br/>depois código de episódio exato"]
    C --> E["Fila de remux"]
    D --> F{"Há empate?"}
    F -->|"Não"| E
    F -->|"Sim"| G["Bloqueia e informa ambiguidade"]
```

As listas são ordenadas antes do pareamento, cada legenda só pode ser usada uma vez e códigos como episódio `01` e `010` não são tratados como iguais. Em empate, o Remuxer não escolhe a primeira legenda por acaso: ele registra uma pendência. Legendas completas PT-BR e `.ass` recebem prioridade sobre faixas `Forced`, `Signs` ou `Songs`, e o `.parcial.` perde para o final do mesmo episódio (−50 na pontuação de desempate).

No fallback por episódio, quando vídeo e legenda trazem temporada (`SxxEyy`), a **temporada também tem de bater**. Legenda **sem** temporada não vai para o especial `S00` quando o lote tem o episódio regular de mesmo número — fica com o regular, e o especial só recebe legenda que diga `S00Eyy`. Sem isso, o especial `S00E01` do Gundam ZZ, que vem antes na ordem dos nomes, levou a legenda do episódio 1 em 09/10/2026.

O caso de filme com exatamente um vídeo e uma legenda continua aceitando nomes de releases diferentes. Confira o sincronismo quando vídeo e legenda vierem de fontes distintas.

O nome final deriva da legenda curada. Tags de tracker, resolução, codec, CRC, `TrackN` e idioma são removidas; títulos editoriais, como `(Narrative)`, são preservados. Exemplo: `Mobile Suit Gundam NT (Narrative).ass` gera `Mobile Suit Gundam NT (Narrative)_PTBR.mkv`.

---

## Sincronismo manual (offset)

O formulário do Remuxer aceita um campo opcional de **sincronismo manual em milissegundos**:

- Positivo → **atrasa** a legenda
- Negativo → **adianta** a legenda

Esse valor é passado como `--sync 0:<ms>` ao `mkvmerge`, que desloca **linearmente todos os timestamps** da faixa de legenda pelo valor informado.

> ⚠️ O offset é aplicado **igualmente a todos os itens da fila do lote** — não é por arquivo individual. Se o valor foi calculado/ajustado para um episódio específico e a mesma execução processa um lote com outros arquivos (ou um filme com timing diferente), todos recebem o mesmo deslocamento. Confira o campo antes de cada execução, especialmente ao misturar um filme com uma leva de episódios na mesma operação.

O [relatório de Análise de Mídia](etapa-1.1-analise-midia.md#o-que-é-auditado-por-faixa) já sugere o valor de offset em ms quando detecta um "atraso constante" — use esse número como ponto de partida.

---

## Fluxo de execução

```mermaid
sequenceDiagram
    actor Op as Operador
    participant UI as Painel Remuxer
    participant API as RemuxerController
    participant UC as RemuxarLoteUseCase
    participant Map as MapeadorMidiaService
    participant AD as MkvmergeAdapter
    participant MKV as mkvmerge (processo externo)

    Op->>UI: Vídeos + legendas + destino + offset
    UI->>API: POST /api/remuxar
    API->>API: destino existe? não é a pasta de vídeos nem a de legendas?
    API-->>UI: aceite da fila ou erro 400/409
    API->>UC: executar (dentro do job da fila)
    UC->>Map: construirPlano(videos, legendas, destino)
    Map-->>UC: PlanoRemux auditável
    loop Para cada tarefa da fila
        UC->>AD: executarRemux(tarefa, offset)
        AD->>MKV: identificar a ORIGEM com mkvmerge -J
        AD->>MKV: gerar arquivo .part-UUID.mkv
        AD->>MKV: identificar o TEMPORÁRIO com mkvmerge -J
        AD->>AD: exigir vídeo + áudio + a faixa "Português (Brasil)"
        AD->>AD: mover para o nome final sem substituir
    end
```

A validação do temporário exige a faixa **carimbada por este remux**, não "alguma legenda em português". A diferença importa no acervo real: o *Sidonia* já traz faixa PT da Netflix, e o critério antigo daria verde a um MKV publicado sem a tradução.

O destino final nunca é escrito diretamente. Falha, timeout ou cancelamento removem somente o temporário exclusivo daquela execução. Se o destino já existir — inclusive se surgir enquanto o processo roda — ele é preservado e o item aparece como pendência.

---

## Endpoint REST

### `POST /api/remuxar`

```json
{
  "entrada": "C:/animes/Gundam Narrative NT",
  "saida": "C:/animes/Gundam Narrative NT/traducao_ptbr",
  "pastaDestino": "E:/prontos/Gundam NT",
  "syncOffsetMs": 0
}
```

| Campo | Obrigatório | Descrição |
|-------|:-----------:|-----------|
| `entrada` | ✅ | Pasta com os vídeos originais |
| `saida` | ⚪ | Pasta com `.ass`/`.srt` **traduzidos** — nome herdado do contrato, não é a saída do remux. Vazio procura uma subpasta **dentro** da pasta de vídeos, nesta ordem de preferência: `traducao_ptbr`, `legenda_ptbr`, `legendas ptbr`, `legendas pt`, `legendas portugues` (sem diferenciar caixa nem `-`, `_` e espaço) |
| `pastaDestino` | ⚪ | Onde gravar os `.mkv` finais. Vazio ⇒ `mkv_final_ptbr/` dentro da pasta de vídeos. Precisa existir, e **não pode ser** a pasta de vídeos nem a de legendas |
| `syncOffsetMs` | ⚪ | Inteiro entre -86.400.000 e 86.400.000 ms, aplicado a todo o lote |
| `preservarLegendasOriginais` | ⚪ | **Aceito e ignorado.** Continua no contrato para não quebrar cliente antigo; não existe caminho que apague faixa |

**Saída:** novos `.mkv` na pasta de destino — `mkv_final_ptbr/` dentro da pasta de vídeos por padrão, ou a pasta escolhida no campo *"Pasta onde salvar os MKVs finais"* da tela (botão **Procurar...**, seletor nativo do Windows).

> **Por que o destino não pode ser a pasta de vídeos.** Não é medo de sobrescrever — disso o remuxer já cuida, preservando qualquer destino que exista. É que a pasta de vídeos é o diretório varrido em busca de **entrada**: um `X_PTBR.mkv` gravado ali seria lido como vídeo original na execução seguinte, e o acervo passaria a ter remux de remux sem nenhum aviso. A recusa vem em `400` na borda, e o caso de uso confere de novo — o CLI entra pelo mesmo ponto.

O endpoint rejeita pasta inválida antes da fila (`400`) e nova solicitação quando já há operação em execução ou aguardando (`409`). O console mostra progresso por arquivo e termina com `CONCLUIDO`, `CONCLUIDO_COM_PENDENCIAS`, `CONCLUIDO_COM_FALHAS`, `CANCELADO` ou `SEM_ARQUIVOS`. O mesmo resumo é registrado na telemetria para formar dataset de diagnóstico e melhoria do projeto.

---

## Navegação

| Anterior | Próximo |
|----------|---------|
| [← 4.2 Correção de Karaokê](etapa-4.2-cura-tags.md) | [5.2 Renomear Arquivos →](etapa-5.2-renomear-arquivos.md) |
