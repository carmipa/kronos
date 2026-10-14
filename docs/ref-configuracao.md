# ⚙️ Configuração — Referência Completa

[← API REST](ref-api-endpoints.md) | [Solução de Problemas →](ref-solucao-problemas.md)

---

## `src/main/resources/application.yml`

```yaml
app:
  modo: WEB                          # WEB (servidor normal, padrão) ou modos CLI legados
                                      # (RASPAGEM_CORRECAO etc., via ExecucaoCli/ModoExecucaoStartup)

quarkus:
  log:
    file:                            # Log em arquivo: UM POR EXECUÇÃO
      enable: true
      path: "logs/execucoes/kronos-${kronos.log.execucao:manual}.log"   # carimbo gerado no lançamento
      level: DEBUG
      rotation:
        max-file-size: 50M
        max-backup-index: 3
        rotate-on-boot: false        # quem separa execução é o carimbo no nome

kronos:
  log:
    retencao-dias: 7                 # Faxina dos logs de execução no boot; zero ou negativo DESLIGA

navegador:
  raizes:                            # Raízes do navegador de pastas (botões "Procurar..." sem diálogo nativo)
    - "C:/animes"
    - "/acervo"
    - "."                            # a própria pasta do KRONOS (Correção de Cache navega cache/)

tradutor:
  diretorio-entrada: entrada         # Pasta padrão de entrada (cada operação da UI sobrescreve)
  diretorio-saida: saida             # Pasta padrão de saída
  diretorio-cache: cache             # Pasta padrão do cache de tradução (.cache.json)
  tamanho-lote: 1                    # Falas por requisição ao LLM — 1 = linha a linha (evita alucinação)
  agrupar-frase-partida: true        # Frase partida entre eventos consecutivos vai numa MESMA chamada
  estilos-ignorados:                 # Estilos ASS NUNCA enviados ao LLM (karaokê/romaji original)
    - "Song JP"
    - "Mobile Suit Gundam"
    - "Char's Counterattack"
    - "OP - Romaji"
    - "OP - English"
    - "ED - Romaji"
    - "ED - English"
    - "ED-ROM"
    - "OPL2"                         # camada de karaokê do Unicorn que escapa do veto de música
  protecao-romaji-pareamento: false  # Proteção de romaji pelo pareamento das duas camadas de karaokê
  reuso-entre-modelos: false         # Um modelo NÃO reaproveita cache de outro (preserva a proveniência)
  idioma-original: "en"              # Chave gravada no cache JSON para o texto original
  idioma-traduzido: "pt-br"          # Chave gravada no cache JSON para o texto traduzido
  fallback-online:
    ativo: false                     # Google Translate como último recurso — DESLIGADO: pipeline 100% local
  llm:
    base-url: "${KRONOS_LLM_BASE_URL:http://127.0.0.1:1234/v1}"   # LM Studio (OpenAI-compatible)
    model: "current"                        # NUNCA fixar um id — ver nota abaixo
    modelo-recuperacao: ""                  # Segunda opinião só na fala que o principal não traduziu; vazio = desligado
    temperature: 0.3
    max-tokens: 2000
    connect-timeout: 5s
    read-timeout: 180s

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
    base-url: "${KRONOS_LLM_BASE_URL:http://127.0.0.1:1234/v1}"   # mesma variável: é o mesmo LM Studio
    model: "current"
    max-tokens: 2000
    connect-timeout: 5s
    read-timeout: 30s                # uma fala curta por chamada, não um lote

api-dados-anime:                     # Timeouts do cliente HTTP dos metadados de anime (AniList/Jikan/TMDB)
  http:
    connect-timeout: 5s
    read-timeout: 180s

telemetria-dataset:                  # Repositório do dataset público de telemetria (botão "Publicar Dataset")
  repositorio-local: "${KRONOS_DATASET_REPO:../kronos-anime-translation-telemetry-dataset}"
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
# Só localhost — fora do contêiner o app não expõe rede (no contêiner, QUARKUS_HTTP_HOST=0.0.0.0)
quarkus.http.host=127.0.0.1
quarkus.arc.ignored-split-packages=org.traducao.projeto

# Redis OPCIONAL: fluxo ao vivo de telemetria. Fora do compose o nome "redis" não resolve
# e o fluxo fica "desconectado" — a aplicação segue inteira.
quarkus.redis.hosts=${KRONOS_REDIS_URL:redis://redis:6379}
quarkus.redis.timeout=1s
quarkus.redis.health.enabled=false

# Estático sem cache no navegador, em qualquer perfil (não só em dev)
quarkus.http.static-resources.caching-enabled=false
# Mescla a config local só em dev
%dev.quarkus.config.locations=application-local.yml

%test.app.modo=WEB
%test.app.browser.auto-open=false
# Porta aleatória em testes
%test.quarkus.http.test-port=0
# Batimento SSE de 1 s na suíte (produção: 15 s)
%test.kronos.sse.heartbeat-intervalo-segundos=1
# Auditoria de conteúdo não grava em relatorios/ nem na telemetria canônica durante os testes
%test.kronos.auditoria.persistencia-canonica=false
```

> O log em arquivo **não** mora mais aqui: está no `application.yml` (um arquivo por execução em
> `logs/execucoes/`). Duas fontes para a mesma chave fariam a vencedora depender do ordinal.

---

## Tabela de referência rápida

| Chave | Padrão | Descrição |
|-------|--------|-----------|
| `app.modo` | `WEB` | `WEB` sobe o servidor; outros valores disparam modos CLI legados |
| `tradutor.diretorio-entrada/saida/cache` | `entrada`/`saida`/`cache` | Pastas padrão (cada painel da UI normalmente sobrescreve) |
| `tradutor.tamanho-lote` | `1` | Falas por chamada ao LLM — não aumente sem necessidade real (risco de desalinhamento) |
| `tradutor.agrupar-frase-partida` | `true` no yml (sem a chave, o código usa `false`) | Junta numa mesma chamada as falas que formam uma frase partida entre eventos consecutivos; a corrente que não passa na conferência volta ao fluxo fala a fala |
| `tradutor.estilos-ignorados` | lista de karaokê/romaji (inclui `OPL2`) | Estilos ASS nunca traduzidos |
| `tradutor.protecao-romaji-pareamento` | `false` | Protege o romaji pelo pareamento das duas camadas de karaokê; desligada, a decisão do que traduzir não muda |
| `tradutor.reuso-entre-modelos` | `false` | Ligada, um modelo reaproveita o cache de outro (mesma lore, prompt, idiomas e schema) e o cache é carimbado com `modeloHerdado` |
| `tradutor.llm.base-url` (env `KRONOS_LLM_BASE_URL`) | `http://127.0.0.1:1234/v1` | Endpoint do LM Studio. O compose injeta `host.docker.internal` — ver [Contêiner](ref-docker.md) |
| `tradutor.llm.model` | `"current"` | **Nunca alterar para um id fixo** |
| `tradutor.llm.modelo-recuperacao` | `""` (desligado) | Segunda opinião: id EXPLÍCITO de outro modelo, consultado só na fala que o principal não traduziu depois das retentativas |
| `tradutor.llm.temperature` | `0.3` | Baixa — prioriza consistência sobre criatividade |
| `tradutor.llm.max-tokens` | `2000` | Limite de tokens de saída por chamada |
| `tradutor.llm.read-timeout` | `180s` | Timeout generoso — modelos locais em GPU modesta podem ser lentos |
| `tradutor.fallback-online.ativo` | `false` | Google Translate como último recurso para as pendências de diálogo desta execução. Desligado: a fala que o LLM não traduzir fica com o original e continua contada como pendência (ver [2.1 Tradução Local](etapa-2.1-traducao-llm.md)) |
| `revisao-lore.llm.base-url` (env `KRONOS_LLM_BASE_URL`) | `http://127.0.0.1:1234/v1` | Mesmo LM Studio da tradução, de propósito |
| `revisao-lore.llm.read-timeout` | `30s` | A revisão chama o LLM por uma fala curta, não por um lote |
| `correcao-cache.reforco-terminologia-aplicar-habilitado` | `false` | Autoriza `POST /api/reforcar-terminologia-aplicar` a escrever no acervo. Desligada, a rota responde `409` e só o ensaio roda |
| `navegador.raizes` | `C:/animes`, `/acervo`, `.` | Raízes que o navegador de pastas pode listar. Caminho fora delas é recusado com `403`; raiz inexistente é ignorada; lista vazia recusa tudo |
| `remuxer.mkvmerge-path` | `mkvmerge` | Ajuste se não estiver no `PATH` |
| `extrator.formato` | `ASS` | Formato padrão quando não especificado na requisição |
| `extrator.mkvmerge-path` / `mkvextract-path` | `mkvmerge` / `mkvextract` | Ajuste se não estiver no `PATH` |
| `extrator.ffmpeg-path` / `ffprobe-path` | `ffmpeg` / `ffprobe` | Ajuste se não estiver no `PATH` |
| `traducao-karaoke.mkvmerge-path` / `mkvextract-path` | `mkvmerge` / `mkvextract` | Usados pela 4.1 para ler as fontes anexadas ao MKV. Sem a chave no yml, vale o padrão do código |
| `tmdb.api-key` | `dummy_key` | Configure em `application-local.yml` para metadados via TMDB |
| `telemetria-dataset.repositorio-local` (env `KRONOS_DATASET_REPO`) | `../kronos-anime-translation-telemetry-dataset` | Repositório local do dataset público; no contêiner o compose aponta para `/dataset` |
| `kronos.log.retencao-dias` | `7` | Faxina dos logs de execução no boot; zero ou negativo desliga (nada é apagado) |
| `quarkus.log.file.path` | `logs/execucoes/kronos-<carimbo>.log` | Um arquivo por execução, até 50M com 3 backups |
| `app.browser.auto-open` | `true` | Abre o navegador padrão no boot. Os testes e a vitrine desligam |
| `quarkus.mcp.server.http.enabled` | `true` | Endpoint MCP (`/mcp`). **Fixada no build**: vale o valor do momento da compilação; a imagem da vitrine compila com `false` |
| `quarkus.redis.hosts` (env `KRONOS_REDIS_URL`) | `redis://redis:6379` | Redis opcional do fluxo de telemetria; o nome `redis` só resolve dentro do compose |
| `kronos.dir.base` (propriedade de sistema `-D`) | vazio = diretório de trabalho | Raiz de `cache/`, `logs/`, `relatorios/` e `backups/`. A suíte de testes aponta para `build/tmp/kronos-tests` |
| `quarkus.http.port` | **`8099`** | Porta do servidor. O padrão do Quarkus é 8080; o KRONOS usa 8099 para coexistir com outros apps Quarkus na mesma máquina |
| `quarkus.http.host` (env `QUARKUS_HTTP_HOST`) | `127.0.0.1` | Bind apenas local. No contêiner o compose define `QUARKUS_HTTP_HOST=0.0.0.0` |
| `kronos.vitrine.ativa` (env `KRONOS_VITRINE_ATIVA`) | `false` | Liga a **vitrine pública**: telas abertas, toda função recusada com 403, dados de demonstração no lugar do que leria disco/rede/LLM. Só a imagem `Dockerfile.vitrine` a liga — ver [Contêiner](ref-docker.md#vitrine-pública-vps) |

---

## Navegação

| Anterior | Próximo |
|----------|---------|
| [← API REST](ref-api-endpoints.md) | [Solução de Problemas →](ref-solucao-problemas.md) |
