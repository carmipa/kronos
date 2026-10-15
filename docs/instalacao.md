# 🚀 Instalação & Configuração

[← Arquitetura](arquitetura.md) | [Análise de Mídia →](etapa-1.1-analise-midia.md)

---

## Pré-requisitos

| Ferramenta | Versão mínima | Obrigatório? | Para quê |
|------------|----------------|--------------|----------|
| **Java (JDK)** | 25 | ✅ Sim | Runtime da aplicação |
| **Gradle** | Incluído via Wrapper | ✅ Sim | Build (`./gradlew`) |
| **FFmpeg / FFprobe** | Qualquer build recente | ✅ Sim | Análise de mídia e extração (containers não-MKV) |
| **MKVToolNix** (`mkvmerge` / `mkvextract`) | Qualquer build recente | ✅ Sim | Extração de MKV e remuxagem final |
| **LM Studio** | Qualquer versão com API OpenAI-compatible + `/api/v0/models` | ✅ Sim (para tradução) | Servir o LLM local |
| **hunspell** + dicionários (`pt_BR`, `en_US`, `de_DE`, `es_ES`…) | — | ⚪ Recomendado | Verificador ortográfico da legenda (`core.texto.dicionarioOrtografia.CorretorOrtograficoLegenda`): repõe acento faltando e separa resíduo de inglês de erro real. Sem dicionário o texto sai **intacto** e a conferência conta como "não verificada". O script `instalar-pre-requisitos.ps1` (raiz) instala e confere ambos |
| **Redis** | — | ⚪ Opcional | Fluxo ao vivo de telemetria (`quarkus.redis.hosts`). Sem ele o card mostra "desconectado" e a tradução segue |
| **TMDB API Key** | — | ⚪ Opcional | Metadados de anime com pôster/sinopse em PT-BR (sem chave, cai para AniList e depois Jikan/MAL) |

> O app é **Windows-first**: o seletor nativo de pasta/arquivo (`DialogoArquivoController`) usa PowerShell + `System.Windows.Forms`, e o `BrowserLauncher` abre o navegador padrão automaticamente no boot. Fora do Windows (ou sem PowerShell), o botão "Procurar..." abre o **navegador de pastas do servidor** (`/api/navegador/pastas`), limitado às raízes de `navegador.raizes`; o caminho também pode ser digitado no formulário.

---

## Passo a passo

### 1. Clonar o repositório

```bash
git clone <url-do-repositorio>
cd traducao_animes_llm_local_quarkus
```

### 2. Instalar as ferramentas externas

- **FFmpeg/FFprobe**: baixe em [ffmpeg.org](https://ffmpeg.org/download.html) e garanta que `ffmpeg`/`ffprobe` estão no `PATH`, ou configure o caminho completo em `application.yml` (`extrator.ffmpeg-path` / `extrator.ffprobe-path`).
- **MKVToolNix**: baixe em [mkvtoolnix.download](https://mkvtoolnix.download/) — no Windows, o app já procura automaticamente em `C:\Program Files\MKVToolNix\`.
- **LM Studio**: baixe em [lmstudio.ai](https://lmstudio.ai/), carregue o modelo **aya-expanse-8b** (o titular do projeto, escolhido por medição contra o mistral-nemo; outro modelo instruct multilíngue de 7B+ também roda) e **inicie o servidor local** (padrão: `http://127.0.0.1:1234`).

### 3. Configurar (opcional)

Copie/edite `src/main/resources/application-local.yml` para chaves privadas (git-ignored):

```yaml
tmdb:
  api-key: "sua-chave-tmdb-aqui"
```

Ajuste `src/main/resources/application.yml` se as ferramentas externas não estiverem no `PATH`:

```yaml
remuxer:
  mkvmerge-path: "C:/Program Files/MKVToolNix/mkvmerge.exe"

extrator:
  mkvmerge-path: "C:/Program Files/MKVToolNix/mkvmerge.exe"
  mkvextract-path: "C:/Program Files/MKVToolNix/mkvextract.exe"
  ffmpeg-path: "C:/ffmpeg/bin/ffmpeg.exe"
  ffprobe-path: "C:/ffmpeg/bin/ffprobe.exe"
```

### 4. Executar em modo desenvolvimento

```shell
./gradlew quarkusDev
```

> O servidor sobe em **`http://127.0.0.1:8099`** (`quarkus.http.port=8099` em
> `application.properties` — porta dedicada, para coexistir com outros apps Quarkus) e o navegador
> abre automaticamente.

> ⚠️ **O live reload NÃO vale para os arquivos estáticos.** Alteração em `.java` é recompilada na
> próxima requisição; alteração em `index.html`, `.js` ou `.css` de
> `src/main/resources/static/` **não** — o `quarkusDev` serve o estático que existia no **boot**.
> Sem reiniciar, você edita o arquivo, recarrega a página e vê a versão velha, sem nenhum aviso.
>
> Medido em 18/08/2026: o `build/` já tinha o seletor novo (2 ocorrências) e o servidor devolvia
> **0**. Depois do reinício, 2. Antes de concluir que "a mudança de tela não funcionou", **reinicie
> e meça de novo** — e antes de reiniciar, pergunte à guarda:
>
> ```shell
> pwsh -NoProfile -File .\pode-compilar.ps1     # 0 pode · 1 nao pode · 2 NAO VERIFICOU
> ```
>
> Ela existe porque reiniciar durante uma tradução **mata o episódio em curso sem erro visível**.
> O estado `2` não é permissão: significa que ela não conseguiu decidir.

### 5. Empacotar para produção

```shell
./gradlew build
java -jar build/quarkus-app/quarkus-run.jar
```

Über-jar (single-file):

```shell
./gradlew build -Dquarkus.package.jar.type=uber-jar
java -jar build/*-runner.jar
```

---

## Referência completa de configuração

Ver [Configuração — Referência Completa](ref-configuracao.md) para todas as chaves de `application.yml`/`application.properties` com valores padrão e explicação.

---

## Verificando a instalação

1. Abra `http://127.0.0.1:8099` — o painel **Início** deve mostrar os quatro widgets de status:
   - **Orquestrador**: `Java Quarkus` — badge verde "Online"
   - **LLM de Tradução**: badge indicando se o LM Studio respondeu
   - **Cache de Legendas**: contagem de arquivos `.cache.json` já existentes
   - **Fluxo de Telemetria**: conectado ou desconectado do Redis (`GET /api/fluxo/status`) — desconectado é estado legítimo

   ![Painel Inicial com os widgets de status](../src/main/resources/static/img/screenshots/painel-inicial.webp)
2. Vá ao painel **Análise de Mídia**, aponte para uma pasta com um `.mkv` de teste e clique em auditar — se o relatório aparecer no console com as faixas de vídeo/áudio/legenda, `ffprobe` está configurado corretamente.
3. Vá ao painel **Tradução Local**, escolha um contexto (ex.: "DanMachi") e uma pasta com uma legenda `.ass` de teste — se a tradução iniciar sem erro de "Servidor LLM indisponível", o LM Studio está acessível.

---

## Problemas comuns na instalação

| Sintoma | Causa provável | Solução |
|---------|------------------|---------|
| `mkvmerge.exe detectado` não aparece no log | MKVToolNix não está no `PATH` nem no caminho padrão do Windows | Configure `remuxer.mkvmerge-path` / `extrator.mkvmerge-path` explicitamente |
| "Servidor LLM indisponível" | LM Studio não está com o servidor local ligado, ou porta diferente de `1234` | Verifique em LM Studio → Developer → Start Server; ajuste `tradutor.llm.base-url` se necessário |
| Diálogo de seleção de pasta não abre | SO não é Windows, ou PowerShell bloqueado por política de execução | Sem diálogo nativo, o "Procurar..." usa o navegador de pastas do servidor, limitado a `navegador.raizes` (fora delas: 403). Ou digite o caminho no campo de texto |
| Porta **8099** já em uso | Outra instância do app já está rodando — e ela pode estar **traduzindo** | Confira o dono da porta ANTES de matar: `Get-NetTCPConnection -LocalPort 8099`. Para mudar, `quarkus.http.port` em `application.properties` |

Mais detalhes em [Solução de Problemas](ref-solucao-problemas.md).

---

## Navegação

| Anterior | Próximo |
|----------|---------|
| [← Arquitetura](arquitetura.md) | [Análise de Mídia →](etapa-1.1-analise-midia.md) |
