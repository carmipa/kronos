# ⚙️ Configuração — Referência Completa

[← API REST](ref-api-endpoints.md) | [Solução de Problemas →](ref-solucao-problemas.md)

---

## `src/main/resources/application.yml`

```yaml
app:
  modo: WEB                          # WEB (servidor normal, padrão) ou modos CLI legados
                                      # (RASPAGEM_CORRECAO etc., via ExecucaoCli/ModoExecucaoStartup)

tradutor:
  diretorio-entrada: entrada         # Pasta padrão de entrada (cada operação da UI sobrescreve)
  diretorio-saida: saida             # Pasta padrão de saída
  diretorio-cache: cache             # Pasta padrão do cache de tradução (.cache.json)
  tamanho-lote: 1                    # Falas por requisição ao LLM — 1 = linha a linha (evita alucinação)
  estilos-ignorados:                 # Estilos ASS NUNCA enviados ao LLM (karaokê/romaji original)
    - "Song JP"
    - "Mobile Suit Gundam"
    - "Char's Counterattack"
    - "OP - Romaji"
    - "OP - English"
    - "ED - Romaji"
    - "ED - English"
    - "ED-ROM"
  idioma-original: "en"              # Chave gravada no cache JSON para o texto original
  idioma-traduzido: "pt-br"          # Chave gravada no cache JSON para o texto traduzido
  llm:
    base-url: "http://127.0.0.1:1234/v1"   # Endpoint OpenAI-compatible do LM Studio local
    model: "current"                        # NUNCA fixar um id — ver nota abaixo
    temperature: 0.3
    max-tokens: 2000
    connect-timeout: 5s
    read-timeout: 180s
  fallback-online:
    ativo: true                       # Tradutor de máquina como ÚLTIMO recurso p/ pendências de diálogo — OPT-IN

remuxer:
  mkvmerge-path: mkvmerge            # Caminho/comando do mkvmerge (MKVToolNix)

extrator:
  formato: ASS                       # Formato padrão de extração de legenda
  mkvmerge-path: mkvmerge
  mkvextract-path: mkvextract
  ffmpeg-path: ffmpeg
  ffprobe-path: ffprobe

tmdb:
  api-key: dummy_key                 # Placeholder — chave real fica em application-local.yml

revisao-lore:                        # Stack LLM PRÓPRIA da Revisão de Lore (menu 3.2), isolada de tradutor.llm
  llm:
    base-url: "http://127.0.0.1:1234/v1"
    model: "current"
    max-tokens: 2000
    connect-timeout: 5s
    read-timeout: 180s

api-dados-anime:                     # Timeouts do cliente HTTP dos metadados de anime (AniList/Jikan/TMDB)
  http:
    connect-timeout: 5s
    read-timeout: 180s

telemetria-dataset:                  # Repositório do dataset público de telemetria (botão "Publicar Dataset")
  repositorio-local: ../kronos-anime-translation-telemetry-dataset
  repositorio-remoto: https://github.com/carmipa/kronos-anime-translation-telemetry-dataset.git
  hardware:
    publicar-ambiente-execucao: true
    permitir-deteccao-automatica: true
```

> ⚠️ **`tradutor.llm.model` deve permanecer sempre `"current"`.** Fixar o id exato de um modelo (ex.: `"mistralai/mistral-nemo-instruct-2407"`) faz o app enviar requisições para esse id específico mesmo quando outro modelo está carregado no LM Studio — e o LM Studio, ao receber uma chamada para um modelo que não está em memória, faz **auto-load (JIT)** dele, resultando em **duas instâncias de modelo carregadas simultaneamente** (consumindo VRAM em dobro). O app já resolve dinamicamente qual modelo está de fato carregado a cada operação — ver [Tradução Local — modelo "coringa"](etapa-2.1-traducao-llm.md#modelo-coringa-tradutorllmmodel-current) e [Solução de Problemas](ref-solucao-problemas.md#lm-studio-carregando-dois-modelos-simultaneamente).

O badge **"LLM de Tradução"** no Painel Inicial reflete `tradutor.llm.base-url` — mostra o nome do modelo atualmente carregado no LM Studio (não o valor `"current"` da config):

![Widget de status do LLM no Painel Inicial](../src/main/resources/static/img/screenshots/painel-inicial.webp)

---

## `src/main/resources/application-local.yml` (git-ignored)

Config local privada — chaves reais que não devem ir para o controle de versão:

```yaml
tmdb:
  api-key: "sua-chave-tmdb-real-aqui"
```

---

## `src/main/resources/application.properties`

```properties
app.modo=WEB
quarkus.http.port=8099
quarkus.http.host=127.0.0.1             # Só localhost — app não expõe rede
quarkus.arc.ignored-split-packages=org.traducao.projeto
quarkus.log.file.path=logs/tradutor.log
quarkus.log.file.rotation.max-file-size=10M
quarkus.log.file.rotation.max-backup-index=5

%dev.quarkus.config.locations=application-local.yml       # Mescla config local só em dev
%dev.quarkus.http.static-resources.caching-enabled=false  # Sem cache agressivo de estáticos em dev

%test.app.modo=WEB
%test.app.browser.auto-open=false
%test.quarkus.http.test-port=0          # Porta aleatória em testes
```

---

## Tabela de referência rápida

| Chave | Padrão | Descrição |
|-------|--------|-----------|
| `app.modo` | `WEB` | `WEB` sobe o servidor; outros valores disparam modos CLI legados |
| `tradutor.diretorio-entrada/saida/cache` | `entrada`/`saida`/`cache` | Pastas padrão (cada painel da UI normalmente sobrescreve) |
| `tradutor.tamanho-lote` | `1` | Falas por chamada ao LLM — não aumente sem necessidade real (risco de desalinhamento) |
| `tradutor.estilos-ignorados` | lista de karaokê/romaji | Estilos ASS nunca traduzidos |
| `tradutor.llm.base-url` | `http://127.0.0.1:1234/v1` | Endpoint do LM Studio |
| `tradutor.llm.model` | `"current"` | **Nunca alterar para um id fixo** |
| `tradutor.llm.temperature` | `0.3` | Baixa — prioriza consistência sobre criatividade |
| `tradutor.llm.max-tokens` | `2000` | Limite de tokens de saída por chamada |
| `tradutor.llm.read-timeout` | `180s` | Timeout generoso — modelos locais em GPU modesta podem ser lentos |
| `tradutor.fallback-online.ativo` | `true` | Tradutor de máquina como último recurso para as pendências de diálogo desta execução (ver doc 05) |
| `remuxer.mkvmerge-path` | `mkvmerge` | Ajuste se não estiver no `PATH` |
| `extrator.formato` | `ASS` | Formato padrão quando não especificado na requisição |
| `extrator.mkvmerge-path` / `mkvextract-path` | `mkvmerge` / `mkvextract` | Ajuste se não estiver no `PATH` |
| `extrator.ffmpeg-path` / `ffprobe-path` | `ffmpeg` / `ffprobe` | Ajuste se não estiver no `PATH` |
| `tmdb.api-key` | `dummy_key` | Configure em `application-local.yml` para metadados via TMDB |
| `quarkus.http.port` | **`8099`** | Porta do servidor. O padrão do Quarkus é 8080; o KRONOS usa 8099 para coexistir com outros apps Quarkus na mesma máquina |
| `quarkus.http.host` | `127.0.0.1` | Bind apenas local |
| `kronos.vitrine.ativa` (env `KRONOS_VITRINE_ATIVA`) | `false` | Liga a **vitrine pública**: telas abertas, toda função recusada com 403, dados de demonstração no lugar do que leria disco/rede/LLM. Só a imagem `Dockerfile.vitrine` a liga — ver [Contêiner](ref-docker.md#vitrine-pública-vps) |

---

## Navegação

| Anterior | Próximo |
|----------|---------|
| [← API REST](ref-api-endpoints.md) | [Solução de Problemas →](ref-solucao-problemas.md) |
