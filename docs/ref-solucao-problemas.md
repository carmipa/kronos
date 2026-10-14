# 🩺 Solução de Problemas

[← Configuração](ref-configuracao.md) | [Voltar ao README →](../README.md)

---

## Instalação

| Sintoma | Causa provável | Solução |
|---------|------------------|---------|
| `mkvmerge.exe detectado` não aparece no log de boot | MKVToolNix não está no `PATH` nem no caminho padrão do Windows | Configure `remuxer.mkvmerge-path` / `extrator.mkvmerge-path` explicitamente — ver [Configuração](ref-configuracao.md) |
| "Servidor LLM indisponível" ao traduzir | LM Studio não está com o servidor local ligado, ou porta diferente de `1234` | LM Studio → Developer → Start Server; ajuste `tradutor.llm.base-url` se necessário |
| Diálogo de seleção de pasta não abre | SO não é Windows, ou PowerShell bloqueado por política de execução | Sem diálogo nativo (`GET /api/dialogo/capacidade` responde `nativo: false`), o botão "Procurar..." abre o **navegador de pastas do servidor** (`/api/navegador/pastas`), limitado às raízes de `navegador.raizes` — pasta fora delas é recusada com 403. Ou digite o caminho no campo de texto |
| Porta **8099** já em uso ao rodar `quarkusDev` | Outra instância do app já está rodando (ex.: aberta em outra sessão/terminal) | Pergunte à guarda antes: `pwsh -NoProfile -File .\pode-compilar.ps1` (0 pode · 1 não pode · **2 NÃO VERIFICOU, que não é permissão**). Ela existe porque reiniciar durante uma tradução mata o episódio em curso sem erro visível |

---

## Legenda dessincronizada desde o início

**Sintoma:** a legenda traduzida/remuxada aparece adiantada ou atrasada em relação à fala **desde a primeira linha**, não é um desvio que cresce ao longo do vídeo.

**Causa raiz mais comum:** a legenda usada na tradução foi extraída/baixada de um **release diferente** (grupo de fansub/encode distinto) do vídeo usado no remux final. Grupos diferentes frequentemente têm durações de logo/aviso/intro diferentes no início do arquivo, deslocando o "tempo zero" — mesmo que o resto do timing seja consistente internamente.

**Como confirmar:**
1. Rode [Análise de Mídia](etapa-1.1-analise-midia.md) no `.mkv` final — se ele **não tiver faixa de texto** (só PGS/imagem), a legenda `.ass` que você está usando definitivamente não veio desse arquivo.
2. Compare os nomes de release do vídeo e da legenda de origem — grupos diferentes (ex. `[2ndfire]` vs `[U3-Project]`) quase sempre têm timing diferente.

**Como resolver:**
- Ideal: extrair/achar uma legenda timada especificamente para o release do vídeo que você vai usar.
- Alternativa: usar `ffsubsync` ou o "Point Sync" do Subtitle Edit para realinhar a legenda contra o áudio real do vídeo.
- Se o desvio for **constante** do início ao fim, o [relatório de Análise de Mídia](etapa-1.1-analise-midia.md#o-que-é-auditado-por-faixa) já sugere um offset em ms para usar no campo de sincronismo manual do [Remuxer](etapa-5.1-remuxer.md#sincronismo-manual-offset).

> O pipeline de tradução **nunca** é a causa desse tipo de desalinhamento — o parser de `.ass` preserva timestamps byte a byte (ver [Tradução Local](etapa-2.1-traducao-llm.md#por-que-o-parser-de-ass-nunca-interpreta-timestamps)). Se a legenda já estava dessincronizada na entrada, ela sai dessincronizada na saída.

---

## LM Studio carregando dois modelos simultaneamente

**Sintoma:** ao rodar uma operação que usa o LLM, o LM Studio sobe uma **segunda instância** de modelo em memória (visível em `lms ps`), mesmo com apenas um modelo configurado como ativo.

**Causa raiz (histórica, já corrigida):** `/v1/models` (endpoint OpenAI-compatible) lista **todo o catálogo de modelos baixados**, não só o que está carregado em memória — não há garantia de ordenação por estado de carregamento. Uma versão anterior do adapter assumia que o primeiro item da lista era o modelo ativo; se essa suposição falhasse, o app enviava uma requisição de tradução para um id diferente do carregado, e o LM Studio fazia **auto-load (JIT)** desse outro modelo para atender o pedido.

**Correção aplicada:** `LlmClientAdapter` agora consulta a API estendida da LM Studio (`GET /api/v0/models`, fora do prefixo `/v1`), que expõe explicitamente o campo `state: "loaded"`/`"not-loaded"` por modelo — a fonte de verdade real. O catálogo `/v1/models` só é usado como fallback de melhor esforço se essa API não estiver disponível (ex.: servidor não é LM Studio).

**Se ainda acontecer:**
1. Confirme que `tradutor.llm.model` em `application.yml` está como `"current"` (nunca um id fixo — ver [Configuração](ref-configuracao.md)).
2. Confira também `tradutor.llm.modelo-recuperacao`: vazio (o padrão) desliga a segunda opinião; preenchido com um id, o LM Studio mantém **dois modelos residentes de propósito** — o de tradução e o de recuperação, consultado só na fala que o principal não conseguiu traduzir.
3. Rode `lms ps` no terminal para ver quantas instâncias estão de fato carregadas.
4. Verifique se duas operações do pipeline que usam o LLM não estão rodando **em paralelo** contra modelos diferentes carregados manualmente entre uma chamada e outra.

---

## Caixa de log mostrando ruído em vez do relatório esperado

**Sintoma:** o painel de [Análise de Mídia](etapa-1.1-analise-midia.md) (ou qualquer outro console da UI) mostra linhas de log de **outra operação** rodando em paralelo, misturadas com o que era esperado.

**Causa raiz (histórica, já corrigida):** o canal SSE ativo (`LogStreamService`) era um campo único compartilhado — quando duas operações em background rodavam ao mesmo tempo (ex.: uma tradução em andamento + uma análise disparada em paralelo), a troca de canal de uma "roubava" o console da outra.

**Correção aplicada:** o canal agora é armazenado em `ThreadLocal`, escopado por thread do executor — cada operação em background mantém seu próprio canal, mesmo com várias rodando simultaneamente.

![Console "Logs do Analisador" — mesma caixa de log de todos os painéis](../src/main/resources/static/img/screenshots/analise-midia.webp)

---

## Extração de legenda com formato inesperado

**Sintoma:** suspeita de que pedir um formato (ex.: ASS) resultou em extração de outro formato (ex.: PGS).

**Diagnóstico:** cada estratégia de extração (`ExtratorAssStrategy`, `ExtratorPgsStrategy`, `ExtratorSrtStrategy`) filtra as faixas **estritamente pelo codec do próprio formato** — não existe fallback cruzado entre formatos no motor de extração. Se nenhuma faixa do formato pedido existir, o vídeo é marcado como "sem legenda" e pulado, nunca substituído por outro formato. Ver [Extração de Legendas](etapa-1.2-extracao-legendas.md#como-o-motor-escolhe-a-faixa-certa).

O que **de fato** podia acontecer (e foi corrigido): se o campo `formato` da requisição chegasse vazio ou com um valor não reconhecido, o app caía silenciosamente para `ASS` como padrão. Hoje isso lança um erro `400` explícito em vez de mascarar o problema — ver `FormatoLegenda.fromString()`.

---

## Arquivo pulado de propósito na extração ou no remux

| Sintoma | Causa | O que fazer |
|---------|-------|-------------|
| A extração avisa `[IGNORADOS] N MKV(s) publicado(s) pelo remux (_PTBR.mkv)` | A pasta tem os vídeos originais **e** os MKVs que o 5.1 publicou; a saída do remux não é legenda de origem. Subpastas `_kronos_*` (cópia de trabalho e backup) também não são percorridas | Nada, se os originais estão lá. Para extrair de um `_PTBR.mkv`, copie-o para uma pasta só com MKVs publicados e extraia dessa pasta (a tela recusa arquivo avulso) |
| O remux deixa o especial `S00Exx` sem legenda | A legenda não traz temporada no nome e o lote tem o episódio regular de mesmo número: ela fica com o regular, nunca com o especial. Com temporada no vídeo e na legenda, as duas têm de ser iguais | Dê à legenda do especial o nome com `S00Exx`; o motivo aparece como aviso no console do remux, e o vídeo conta como "sem legenda" |

---

## Fonte vietnamita legada

**Sintoma:** a **4.1 Karaokê** (na simulação e na aplicação) reprova a legenda com `REPROVADO: fonte vietnamita antiga na legenda (...)`, ou o **5.1 Remuxer** pula o episódio com `fonte vietnamita antiga na legenda (...)` e não gera o MKV dele.

**Causa:** a legenda usa uma fonte de codificação antiga (TCVN3 — `.VnBook-Antiqua`, `.VnTime`, também sem o ponto — ou VNI, como `VNI-Times`), que desenha **outra letra** no lugar de "ç", "ã" e das maiúsculas acentuadas. A regra é única (`core.texto.FonteVietnamitaLegada`) e vale para todo estilo do cabeçalho e todo `\fn` dentro de `Dialogue:` ou `Comment:`.

**Como resolver:** rode a [1.3 Troca de Tipo de Legenda](etapa-1.3-troca-tipo-legenda.md) (Arial) na pasta das legendas e repita a 4.1 ou o remux.

---

## Ainda com problemas?

1. Confira o [relatório de Análise de Mídia](etapa-1.1-analise-midia.md) do arquivo envolvido — ele geralmente aponta a causa raiz antes de qualquer outra etapa.
2. Verifique o log da execução em `logs/execucoes/kronos-<carimbo>.log` — um arquivo por execução, mantido por `kronos.log.retencao-dias` (7 dias) — e `logs/console-web.log`, que guarda o que passou pelo console da tela.
3. Verifique `logs/telemetria_compartilhada.json` (ou o painel [Telemetria](modulo-telemetria.md)) para o histórico de operações já executadas.

![Painel de Telemetria — histórico de operações, hits de cache e tokens](../src/main/resources/static/img/screenshots/telemetria.webp)

---

## Navegação

| Anterior | Próximo |
|----------|---------|
| [← Configuração](ref-configuracao.md) | [Voltar ao README →](../README.md) |
