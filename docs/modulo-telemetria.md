# 📊 Módulo: Telemetria

[← Contextos & Lore](modulo-contextos-lore.md) | [Metadados de Anime →](modulo-metadados-anime.md)

---

## Para que serve

Rastreia **todas** as operações do pipeline (análise, extração, tradução, correção, revisão, remux) em um armazenamento único e persistente, alimentando o painel **Telemetria** da UI em tempo real via SSE e permitindo auditoria histórica de tudo que já foi processado.

![Painel de Telemetria](../src/main/resources/static/img/screenshots/telemetria.webp)

---

## Arquitetura

```mermaid
graph TB
    subgraph OPS["Operações do pipeline"]
        AN["Análise"]
        TR["Tradução"]
        CO["Correção/Revisão"]
        RX["Remuxer"]
    end

    OPS -->|registrar*| TS["TelemetriaService<br/>(@Service @ApplicationScoped)"]
    TS --> MEM1["bancoMidia (Map)"]
    TS --> MEM2["bancoLlm (Map)"]
    TS --> MEM3["bancoOperacoes (List)"]
    TS -->|escrita atômica<br/>.tmp + Files.move ATOMIC_MOVE| JSON[("logs/telemetria_compartilhada.json")]
    TS -->|@PostConstruct| JSON
    SSE["TelemetriaStreamResource<br/>/api/telemetria/stream"] -->|"registra o sink"| TS
    TS -->|"scheduler - broadcast a cada 5 s"| UI["Painel Telemetria (SSE)"]
    API["GET /api/telemetria"] --> TS
    EXP["GET /api/telemetria/exportar"] --> JSON
    TS -->|"eventos ao vivo, opcional"| RED[("Redis Streams<br/>kronos:telemetria:&lt;fatia&gt;")]
```

- **`TelemetriaService`** mantém 3 bancos em memória e **persiste imediatamente** a cada `registrar*()` — escrita atômica (grava em `.tmp` e faz `Files.move` com `ATOMIC_MOVE`) para não corromper o arquivo se o processo cair no meio da escrita.
- **Tetos:** o histórico guarda no máximo **500 operações**, e cada episódio guarda no máximo **30 avisos** (o resto vira uma linha `(+N avisos omitidos…)`; a lista integral fica no relatório da operação).
- No `@PostConstruct`, recarrega o JSON existente — a telemetria **sobrevive a restarts** do servidor.
- **"Telemetria compartilhada"** = esse arquivo canônico, fonte de verdade das operações de **análise, extração, remux e correções** (não é por sessão nem por anime).

> **Atenção (FASE E8):** a **Tradução Local** NÃO escreve mais neste arquivo — ela ganhou um **arquivo canônico próprio** (`logs/telemetria_traducao.json`), descrito na próxima seção. O `telemetria_compartilhada.json` segue vivo para as demais operações.

---

## Telemetria própria da Tradução Local (`logs/telemetria_traducao.json`)

Para isolar a Tradução Local do módulo `telemetria` (o fence ArchUnit proíbe a aresta `traducao → telemetria`), a fatia escreve seu **próprio** arquivo canônico via `TelemetriaTraducaoAdapter`, lido pelo painel através de `TelemetriaTraducaoLeitura`.

- **Dedup por episódio (nunca append-only):** o registro mais recente de um episódio **substitui** o anterior (chave = nome normalizado). Escrita atômica (`.tmp` + move seguro); um arquivo ilegível é **preservado** como `.corrompido_<timestamp>` em vez de destruído.
- **Schema 1.1 — `pendenciasPorCausa`:** além dos 4 contadores da fatia (alucinações prevenidas, respostas rejeitadas, falhas recuperadas, fallbacks mantidos), cada episódio carrega um **KPI estruturado** que classifica as falas pendentes por `(categoria × causa-raiz)` — ex.: `DIALOGO × MARCADORES_CORROMPIDOS`, `DIALOGO × ECO` — para o painel mostrar *por que* algo não traduziu, não só *quanto*.

---

## O que é rastreado

| Registro | Campos principais |
|----------|---------------------|
| `MidiaTelemetria` (por vídeo) | Formato do contêiner, tamanho MB, duração, codec, resolução, fps e a lista de legendas (`LegendaTelemetria`: índice, idioma, formato, tipo, categoria, traduzível e `diferencaFimSegundos`) |
| `LlmTelemetria` (por episódio traduzido) | Modelo LLM usado, total de linhas, falas traduzidas, falas do cache, tempo total, erros, nome do anime/temporada |
| `OperacaoTelemetria` (genérica) | Tipo, detalhe (pasta), duração, arquivos processados, itens detectados/corrigidos, timestamp ISO — cobre revisão, correção Google, limpeza de cache, remuxer |
| Contador de alucinações prevenidas | Quantas vezes o `MascaradorTags`/validadores impediram uma tag corrompida de ir parar no arquivo final |

As operações que chamam `finalizarOperacao` (revisão de legendas, correções, karaokê) também gravam, em `relatorios/<pasta-da-operação>/`, um relatório `.txt` + `.json` individual timestampado, além da cópia no JSON compartilhado. Quem só chama `registrarOperacao` não gera esse relatório.

---

## Endpoints REST

### `GET /api/telemetria`

Retorna `TelemetriaResumo`:

```json
{
  "cacheCount": 42,
  "totalEpisodios": 12,
  "totalLinhas": 3841,
  "tempoMedioPorLinhaMs": 850,
  "totalCacheHits": 2103,
  "historicoOperacoes": [
    { "nomeOperacao": "...", "detalheOperacao": "...", "duracaoFormatada": "...", "taxaSucesso": 97,
      "origem": "...", "duracaoMs": 1234, "registradoEm": "2026-10-14T12:00:00Z",
      "itensDetectados": 68, "itensCorrigidos": 66 }
  ],
  "traducoesLlm": [ ],
  "operacoes": [ ],
  "revisaoLore": { "totalSessoes": 0, "totalArquivosProcessados": 0, "totalFalasSinalizadas": 0,
                   "totalFalasCorrigidas": 0, "taxaCorrecaoPercent": null },
  "alucinacoesPrevenidas": 17,
  "totalErros": 3,
  "jvmCpuUso": 12.4,
  "jvmThreadsAtivas": 24,
  "jvmHeapUsadoBytes": 536870912,
  "jvmHeapMaxBytes": 4294967296,
  "arquivosSanitizados": 0,
  "respostasTraducaoRejeitadas": 0,
  "falhasTraducaoRecuperadas": 0,
  "fallbacksTraducaoMantidos": 0
}
```

Inclui **métricas de JVM em tempo real**: CPU do processo (em %), threads ativas e heap usado/máximo **em bytes**. `registradoEm` é o instante UTC (ISO-8601); o navegador converte para a hora local.

Na tela, a coluna **Corrigidos** do histórico mostra `itensCorrigidos de itensDetectados` ("66 de 68"); `taxaSucesso`
(divisão inteira, arredondada para baixo) fica só por compatibilidade — ela exibia "0%" para 91 de 24.441. O
**funil** soma a lista `traducoesLlm`: falas traduzíveis (`totalLinhas`, com repetição) e os textos distintos que
vieram do cache (`falasDoCache`) ou do LLM (`falasTraduzidas`); a **eficiência do cache** é cache ÷ (cache + LLM),
na mesma unidade. Nenhuma barra é estimada.

### `GET /api/telemetria/exportar`

Download do arquivo bruto `logs/telemetria_compartilhada.json` como anexo `kronos_telemetria_segura.json`.

### `GET /api/telemetria/stream` (SSE, JAX-RS puro)

Implementado em `TelemetriaStreamResource` (JAX-RS puro, não Spring-style, para evitar colisão de rota com o dispatcher), que **só registra** o `SseEventSink` no `TelemetriaService`. O agendador fica no `TelemetriaService`: um `ScheduledExecutorService` faz `broadcast()` do `TelemetriaResumo` serializado a cada **5 segundos** para todos os clientes conectados.

### Telemetria por fatia

| Endpoint | O que faz |
|----------|-----------|
| `POST /api/telemetria/consolidar-fatias` | Reúne a telemetria espalhada em `relatorios/` num arquivo por fatia, `logs/telemetria_fatia_<fatia>.json`, com o `detalhe` **sanitizado** (sem caminho absoluto). Não toca nos arquivos de origem e é **idempotente**: rodar duas vezes não duplica. |
| `GET /api/telemetria/fatias` | Lista as fatias consolidadas com a contagem de cada uma; lista vazia = ainda não consolidou. |
| `GET /api/telemetria/fatias/{fatia}` | As operações de uma fatia. Fatia sem consolidado responde `404` com `"motivo": "NAO_CONSOLIDADA"`. |

### Fluxo ao vivo (Redis Streams, opcional)

`RedisFluxoTelemetriaAdapter` publica os eventos num Redis Stream **por fatia**, para acompanhar a execução ao vivo e reler depois de um F5. É opcional: sem Redis o KRONOS funciona por inteiro, nenhuma falha de publicação sobe ao pipeline, e o arquivo em disco continua sendo a fonte durável. `GET /api/fluxo/status` informa se está conectado e quantos eventos há, e alimenta o card de fluxo de telemetria no painel inicial.

### `POST /api/telemetria/publicar-dataset`

Botão **"Publicar Dataset"** do painel: publica a telemetria como **dataset público** no repositório Git dedicado **[`kronos-anime-translation-telemetry-dataset`](https://github.com/carmipa/kronos-anime-translation-telemetry-dataset)** (convenção da comunidade `[NomeDoSistema]-telemetry-dataset`, voltada a pesquisa/ML/AIOps).

Fluxo do `TelemetriaDatasetService`:

1. **Consolida por fatia antes** (o mesmo `consolidar-fatias` acima), para o snapshot não sair com o retrato da publicação anterior;
2. **Sanitiza** — nas métricas agregadas os avisos viram `quantidadeAvisos` (nenhum texto de legenda) e nenhum caminho de máquina sai; só as **tabelas de aviso** citam a linha que provocou a falha, porque sem ela a falha não pode ser estudada (declaração no README do dataset);
3. **Gera**, em `metrics/` no repositório local (clonado/bootstrapado automaticamente, com README e LICENSE na primeira publicação):
   - `kronos-telemetria-dataset.json` (resumo agregado + ambiente de execução);
   - os acervos acumulados `kronos-telemetria-execucoes.jsonl` (tradução) e `kronos-karaoke-execucoes.jsonl` (karaokê);
   - `fatias/kronos-fatia-*.json`, uma por fatia consolidada;
   - `csv/kronos-*.csv` — resumo, ambiente de execução, traduções LLM, operações, execuções, avisos, karaokê e avisos de karaokê;
4. **Commita e faz push** — cada publicação é um commit; o histórico Git é a linha do tempo dos snapshots.

Configuração em `application.yml`: `telemetria-dataset.repositorio-local` (padrão `${KRONOS_DATASET_REPO:../kronos-anime-translation-telemetry-dataset}`), `repositorio-remoto` e `telemetria-dataset.hardware.*` (se publica o ambiente de execução e se detecta o hardware automaticamente). Único passo manual, uma vez: criar o repositório vazio no GitHub.

---

## Navegação

| Anterior | Próximo |
|----------|---------|
| [← Contextos & Lore](modulo-contextos-lore.md) | [Metadados de Anime →](modulo-metadados-anime.md) |
