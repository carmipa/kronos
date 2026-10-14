# 🔍 Módulo: Análise de Mídia

[← Instalação](instalacao.md) | [Extração de Legendas →](etapa-1.2-extracao-legendas.md)

---

## Para que serve

Primeira etapa do pipeline: **auditoria técnica** de arquivos de vídeo antes de qualquer processamento. Usa `ffprobe` para extrair contêiner, codecs de vídeo/áudio e **cada faixa de legenda embutida**, e classifica cada legenda por **traduzibilidade** — se é de **texto** (ASS, SSA, SRT, WebVTT, MOV_TEXT), extraível e traduzível, ou **bitmap** (PGS, VobSub, DVB), que exige OCR. Esse é o dado vital do módulo: decidir, antes de gastar tempo, se dá para extrair e traduzir a legenda de um arquivo.

> ⚠️ Este módulo **não** emite um veredito confiável de sincronismo. O `ffprobe` fornece apenas **metadados técnicos** (durações, codecs, flags). A duração de uma faixa de legenda quando comparada à do vídeo é reportada como **informação**, não como diagnóstico — e frequentemente é um placeholder (igual à do vídeo), não um valor real. Um atraso constante ou uma incompatibilidade entre releases (legenda de um fansub/encode diferente do vídeo) só pode ser confirmado analisando os **eventos temporais da própria legenda**, o que está fora do escopo atual. Qualquer diagnóstico futuro de sincronismo deve ser tratado como **heurística, não como certeza**.

> 📖 Contexto real de uso: numa sessão de suporte, a legenda de um filme (*Gundam Narrative*) chegava "adiantada" desde a primeira fala — a causa raiz era a legenda ter sido extraída de um release/encode diferente (grupo de fansub distinto) do vídeo usado no remux. A análise técnica ajuda a levantar os metadados, mas a confirmação do desalinhamento exigiu inspecionar os tempos dos eventos da legenda. Ver [Solução de Problemas](ref-solucao-problemas.md#legenda-dessincronizada-desde-o-inicio).

![Painel de Análise de Mídia](../src/main/resources/static/img/screenshots/analise-midia.webp)

---

## Pacote e classes principais

| Classe | Papel |
|--------|-------|
| `AnalisarMidiaUseCase` (`application`) | Só coordena o lote: pede os vídeos ao `LocalizadorVideosService`, roda o `ffprobe` em **paralelo** (de 2 a 8 por vez, em virtual threads) e coleta os resultados **na ordem dos arquivos**, delegando a classificação ao `ClassificadorLegendaService`, a telemetria ao `TelemetriaMidiaMapper` e o resumo textual ao `RelatorioMidiaTextoFormatter`. **Não grava relatório em disco.** |
| `LocalizadorVideosService` (`application`) | Acha os vídeos: arquivo avulso ou pasta varrida recursivamente, pelas extensões `.mkv`, `.mp4`, `.avi`, `.mov`, `.flv`, `.wmv`, `.webm`, `.m4v`, `.ts` e `.m2ts` |
| `ClassificadorLegendaService`, `TelemetriaMidiaMapper`, `RelatorioMidiaTextoFormatter` (`application`) | Classificação texto/bitmap de cada legenda, registro na telemetria e resumo textual por arquivo |
| `FfprobeAdapter` (`infrastructure/adapters`) | Executa `ffprobe -v error -print_format json -show_format -show_streams -show_chapters` e mapeia o JSON (faixas, capítulos e anexos) para os records de domínio |
| `AuditoriaResultado`, `ContainerInfo`, `VideoInfo`, `AudioInfo`, `LegendaInfo`, `CapituloInfo`, `AnexoInfo`, `FalhaAnalise` (`domain`) | Records imutáveis do resultado da auditoria |
| `ResultadoAnaliseLote` (`domain`) | Retorno do use case: **`resultados`** (análises bem-sucedidas) + **`falhas`** (arquivos que erraram). Não contém `Path` de relatório salvo. |
| `ConsoleAnalisadorLogger` (`presentation/ui`) | Formatação colorida para a CLI legada |

---

## Fluxo de execução

```mermaid
sequenceDiagram
    actor Op as Operador
    participant UI as Painel Análise
    participant API as AnaliseMidiaController
    participant UC as AnalisarMidiaUseCase
    participant FF as FfprobeAdapter
    participant TEL as Telemetria (logs/)

    Op->>UI: Informa pasta/arquivo de vídeo
    UI->>API: POST /api/analisar {entrada, saida?}
    API->>API: entrada vazia ou inexistente → 400, antes da fila
    API-->>UI: 200 "Análise de mídia iniciada" (job assíncrono)
    API->>UC: executar(entrada) — saida é ignorada
    UC->>UC: LocalizadorVideosService.localizar() — arquivo ou pasta recursiva, por extensão
    par ffprobe em paralelo (2 a 8 por vez, virtual threads)
        UC->>FF: analisarMidia(video)
        FF->>FF: ffprobe -show_format -show_streams -show_chapters
        FF-->>UC: AuditoriaResultado (contêiner, faixas, capítulos, anexos)
        UC->>UC: classifica cada legenda (texto/bitmap, traduzível)
    end
    loop Coleta na ordem dos arquivos
        UC->>TEL: registrarMidia() — telemetria técnica anonimizada
    end
    UC-->>API: ResultadoAnaliseLote {resultados, falhas}
    API->>UI: SSE "analise-relatorio" com o JSON serializado do resultado
    API->>UI: banner final no console: [SUCESSO] / [ATENÇÃO] / [FALHA]
    Op->>UI: (opcional) Exportar TXT — gerado no navegador sob demanda
```

O banner final tem três estados: `[SUCESSO]` só sem nenhuma falha; `[ATENÇÃO]` quando houve falha mas algum arquivo foi analisado; `[FALHA]` quando nenhum arquivo foi analisado. A contagem de falhas sempre aparece.

> O que a tela recebe é o **`ResultadoAnaliseLote` serializado diretamente em JSON** e transmitido via SSE no canal `analise-relatorio`. **Não** há gravação de `.txt`/`.json` da análise em disco, nem releitura de arquivo: a fonte da interface é o **domínio estruturado**. A exportação para TXT é **manual**, disparada pelo operador no navegador e produzida a partir dos mesmos dados estruturados. Apenas a **telemetria técnica** é persistida — separadamente, no destino canônico interno (`logs/telemetria_compartilhada.json`).

---

## O que é auditado por faixa

### Vídeo
Codec, resolução, profundidade de cor, FPS, aspect ratio, bitrate.

### Áudio
Idioma, codec, canais, taxa de amostragem, bitrate, título da faixa.

### Capítulos e anexos (fontes embutidas)
Capítulos: número, título, início e fim. Anexos do contêiner — em MKV, normalmente as **fontes embutidas**: nome do arquivo, tipo MIME e tamanho.

### Legenda — classificação por traduzibilidade

Para cada faixa, o módulo classifica o formato e deriva a traduzibilidade:

| Categoria | Formatos | Traduzível | Observação |
|-----------|----------|------------|------------|
| **Texto** | ASS, SSA, SRT/SubRip, WebVTT, MOV_TEXT | Sim | Extraível para texto e apto ao pipeline de tradução |
| **Bitmap** | PGS, VobSub, DVB | Não (exige OCR) | Legenda em imagem; **não** é hardsub — é uma faixa embutida, apenas não textual |
| **Sem faixa de legenda** | — | — | Pode ser RAW **ou** hardsub; a **ausência de faixa softsub não prova** que há hardsub |

**Indicadores temporais** (duração da legenda e diferença para a duração do vídeo) são exibidos apenas como **informação técnica**, sem veredito de sincronia.

---

## O que a tela mostra

Para cada arquivo, a tela monta **cartões** — Contêiner, Vídeo, Áudio, Capítulos e Anexos — e uma **tabela de Legendas** (número da faixa, idioma, formato, tipo, flags `default`/`forced`/acessibilidade e traduzibilidade: *Traduzível*, *OCR* ou *—*). Arquivo sem nenhuma faixa mostra "pode ser RAW; hardsub não confirmado por esta análise". Os arquivos que falharam aparecem numa lista à parte. O **TXT exportado** sai dos mesmos dados e começa com `RELATORIO DE ANALISE DE MIDIA`. Atenção à terminologia:

- **PGS** e **VobSub** são legendas **bitmap** (imagem), não extraíveis para texto sem OCR — **não** são hardsub.
- **Hardsub** é conteúdo **queimado na imagem do vídeo**; não aparece como faixa de legenda.
- **Nenhuma faixa de legenda** encontrada **não** confirma hardsub — o arquivo pode ser apenas RAW.

---

## Endpoint REST

### `POST /api/analisar`

```json
{
  "entrada": "C:/animes/[Sokudo] DanMachi/Season 04",
  "saida": null
}
```

`entrada` é obrigatório (pasta ou arquivo de vídeo) e precisa existir: vazio ou inexistente responde `400` **antes** de entrar na fila. `saida` é aceito, mas **ignorado** — a análise não grava em disco.

O seletor de **Obra / Contexto** do painel é opcional e serve só para exibir capa e metadados do anime; ele não é enviado à análise.

**Resposta imediata:** `200 OK` com `{"mensagem": "Análise de mídia iniciada no servidor."}`. O job roda em segundo plano; o resultado chega via **SSE** no canal `analise-relatorio` como o **JSON do `ResultadoAnaliseLote`** (ver [API REST — Referência](ref-api-endpoints.md)).

**Saída em disco:**
- **Nenhum relatório de análise é gravado automaticamente.** O resultado completo aparece no HTML a partir do JSON estruturado.
- A **exportação para TXT é manual**, feita no navegador sob demanda, a partir dos mesmos dados estruturados.
- A **telemetria técnica** (metadados anonimizados por mídia) é persistida separadamente no destino canônico interno (`logs/telemetria_compartilhada.json`), servindo tanto para diagnóstico quanto como dataset de melhoria futura.

---

## Navegação

| Anterior | Próximo |
|----------|---------|
| [← Instalação](instalacao.md) | [Extração de Legendas →](etapa-1.2-extracao-legendas.md) |
