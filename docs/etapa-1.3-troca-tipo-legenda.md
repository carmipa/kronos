# 🔤 Módulo: Troca Tipo Legenda (Fontes Legadas)

[← Revisão de Lore](etapa-3.2-revisao-lore.md) | [Remuxer →](etapa-5.1-remuxer.md)

---

## Para que serve

Painel **"9. Troca Tipo Legenda"** da SPA (grupo **Qualidade**). Audita arquivos `.ass`/`.ssa` em busca de **fontes legadas de 8 bits** nos estilos (`Fontname` em `[V4+ Styles]`) e as substitui em lote por fontes Unicode seguras — com backup automático antes de gravar. Mesmo quando a auditoria automática não encontra defeito obrigatório, o operador pode escolher **normalizar manualmente os `Fontname` para Arial** por legibilidade em TV; essa ação não achata estilos, não remove efeitos e não altera textos/tempos.

![Painel de Troca Tipo Legenda](../src/main/resources/static/img/screenshots/troca-tipo-legenda.png)

### O problema que motivou o módulo

Fansubs antigos usam fontes com codificações pré-Unicode — em especial as vietnamitas **TCVN3/VNI** (prefixo `.Vn`, ex.: `.VnBook-Antiqua`) — que colocam **glifos vietnamitas nas posições dos caracteres acentuados do Latin-1**. O texto no arquivo fica correto, mas qualquer player que honre fontes embutidas (VLC, mpv/libass) renderiza `"Não, não é"` como `"Nóo, nóo ộ"`. Legendas em inglês (ASCII puro) nunca revelam o defeito — ele só aparece quando o pipeline traduz para PT-BR e os acentos entram em cena.

> 💡 Caso real: a temporada de *Gundam 08th MS Team* (release [Joseki]) usava `.VnBook-Antiqua` no estilo `Dialogue`, com o `Vnantiqb.ttf` embutido nos MKVs. O defeito é 100% de **renderização** — o `.ass` está íntegro — e por isso não aparece em nenhum grep/diff do texto.

### 🚫 Fonte vietnamita antiga é BANIDA — a 4.1 e o 5.1 reprovam e pedem esta tela

Ordens de Paulo em 14/10/2026, depois do Gundam ZZ publicado com 10.514 de 16.467 falas em
`VnBook-Antiqua` (sem o ponto — a lista antiga desta tela não a conhecia):

- **Regra única** em `core.texto.FonteVietnamitaLegada`, a mesma nas três ferramentas. É antiga:
  `.vn…` e `vni-…` (sem caixa), `Vn` + maiúscula (`VnBook-Antiqua`, `VnTimes`) e os nomes antigos em
  minúsculas. **Não** é: `VNF-…` (vietnamita moderna, em Unicode), `Vinque Sans`, `Verdana`.
- **Em qualquer lugar da legenda:** todo estilo do cabeçalho, **usado ou não** por fala, e todo `\fn`
  dentro de linha `Dialogue:` **ou** `Comment:`. Estilo sem fala e comentário não desenham hoje, mas
  basta reativar a linha ou apontar uma fala para o estilo para a letra trocada voltar sem aviso. O
  motivo da reprovação diz quando o estilo não está na tela (`(nenhuma fala na tela usa)`).
- **4.1 Karaokê** reprova o arquivo antes de cache e LLM, sem gravar; **5.1 Remuxer** não gera o MKV
  daquele episódio (pendência `fonteVietnamita=N`) e segue os outros. As duas mandam rodar esta tela.
- **Esta tela troca tudo o que a reprovação cobra:** `Fontname` de todo estilo (inclusive linha de
  estilo com menos colunas que o `Format:`) e o `\fn` de falas e comentários. Depois de aplicar, a
  regra não acha mais nada — o teste
  `estiloSemFalaComentarioELinhaCurtaComFonteLegadaTambemSaoTrocados` prova isso relendo o arquivo.
- Legenda salva em ANSI continua passando quando a fonte é comum (nome de fonte é ASCII). ASS em
  UTF-16 é recusado antes pelo validador de entrada do 5.1.

---

## Pacote e classes principais

| Classe | Papel |
|--------|-------|
| `TrocaTipoLegendaUseCase` (`application`) | Orquestra o lote: escaneia, cria backup, grava substituições, persiste relatório e telemetria |
| `AuditoriaFontesService` (`application`) | Detecta fontes legadas/problemáticas nos estilos e sugere a substituta Unicode |
| `TrocaTipoLegendaAuditoriaCache` (`infrastructure`) | Cache/auditoria append-only de cada substituição realizada |
| `AuditoriaFonteInfo`, `AuditoriaLegendaResultado`, `ResultadoGeralAuditoria`, `ResultadoTrocaFonte` (`domain`) | Records imutáveis dos resultados de auditoria e da aplicação |
| `TrocaTipoLegendaController` (`presentation`) | Endpoints REST — escanear (síncrono via fila) e aplicar (assíncrono via fila) |

---

## Fluxo de execução

```mermaid
sequenceDiagram
    actor Op as Operador
    participant UI as Painel Troca Tipo Legenda
    participant API as TrocaTipoLegendaController
    participant Fila as FilaExecucaoPipeline
    participant UC as TrocaTipoLegendaUseCase
    participant Aud as AuditoriaFontesService

    Op->>UI: Pasta com as legendas .ass/.ssa
    UI->>API: POST /api/troca-legenda/escanear
    API->>Fila: executarEAguardar(escanear)
    Fila->>UC: escanear(diretorio)
    UC->>Aud: auditar estilos de cada arquivo
    Aud-->>UC: fontes legadas + sugestões Unicode
    UC-->>UI: relatório de auditoria (JSON síncrono)

    Op->>UI: Confirma "Aplicar Substituições"
    UI->>API: POST /api/troca-legenda/aplicar
    API->>Fila: submeter(aplicar) — assíncrono
    Fila->>UC: aplicar(diretorio)
    UC->>UC: backup da pasta → substitui Fontname → grava
    UC-->>UI: progresso via SSE (canal troca-tipo-legenda)
```

- **Escanear** roda **síncrono dentro da fila** (`executarEAguardar`): garante que nenhum job pesado roda em paralelo e devolve o relatório na própria resposta HTTP. Se a fila estiver ocupada com um job longo, a requisição espera.
- **Aplicar** roda **assíncrono na fila** (`submeter`): cria o backup, grava os arquivos, loga cada substituição no console via SSE e registra cada troca no cache de auditoria.
- A execução respeita **parada cooperativa** (interrupção via encerramento/`/api/pipeline/parar`) — arquivos já gravados são preservados.

---

## O estilo base é o de MAIOR TEMPO DE TELA — nunca o mais numeroso

O achatamento joga os estilos decorativos no estilo de diálogo principal. Escolher **qual** é o
principal decidia tudo — e o critério antigo (*"o estilo mais frequente"*) elegia a **decoração**.

```
Zeta Gundam — o logo de abertura e animado quadro a quadro:
  297 eventos do estilo "Zeta Episode Title", de 0,04 s cada  =  6 s de tela

episodios 1, 8 e 14:  297 quadros  >  205 / 291 / 275 falas de dialogo
  -> a decoracao ganhava a votacao e o achatamento rodava AO CONTRARIO:
     o dialogo inteiro ia para o estilo do letreiro (corpo 100, contorno 0,
     sombra 0, cinza claro, margem 10) e ficava ilegivel sobre cena clara

episodio 2:  298 falas contra 297 quadros  ->  UMA linha de diferenca
  -> nos outros 47 episodios o defeito nao apareceu POR UM FIO
```

Hoje a eleição prefere `Default` quando ele existe no cabeçalho **e alguma fala o usa** (declarado
e vazio é resto de template — no DanMachi Sword Oratoria é Arial 30 no canto superior direito); na
ausência dele, vence o estilo com **maior tempo de tela** entre as falas `Dialogue` com fonte
declarada. Por tempo, a separação é de **duas ordens de grandeza** (6 s de logo contra ~10 min de
diálogo) e não depende de sorte.

**Tempo de tela é a UNIÃO dos intervalos**, não a soma das linhas (08/10/2026). Somar contava cada
camada sobreposta de karaokê como tempo próprio: no DanMachi Sword Oratoria as 15.482 linhas do
`ED - Romaji` (90 s de encerramento) somavam mais que as 298 falas do diálogo, e o diálogo era
achatado **para o estilo do karaokê**. Com a união, o mesmo segundo conta uma vez; para quadros
disjuntos (o logo do Zeta) a união é igual à soma. O console diz para qual estilo as falas foram
(`[ACHATADO] ... -> Dungeons`) — até 08/10 imprimia `-> Default` fixo, qualquer que fosse a base.
Faixa só de letreiros/músicas, sem estilo de diálogo, continua com base arbitrária entre os
estilos que tiver — limite declarado.

> Contagem de eventos ainda decide, mas só como **último recurso** — quando nenhuma duração pôde
> ser lida (legenda sem colunas `Start`/`End`, ou com tempos ilegíveis). Critério que só funciona
> por margem de uma linha não é critério: é sorte com aparência de regra.

**O achatamento em si é feature, não defeito** — quem confundiu os dois fui eu, ao ler o resultado
antes do código. O bug era só o voto por contagem.

## O que o achatamento NÃO toca: composição tipográfica

Linha cujo "texto" não é para ser lido fica no estilo dela, com o bloco de override intacto — tirar o
bloco expõe o desenho ou a textura como texto comum no estilo base:

| Composição | Como é reconhecida (no bloco líder) | O que aparecia na tela quando era achatada |
|---|---|---|
| Desenho vetorial | último `\p<n>` com `n > 0` | `m -720 -540 l 720 -540 ...` (até uma máscara preta de tela cheia) |
| Textura com recorte | `\clip`/`\iclip` **e** `\fn` | `ABCDEF`, `ccccc` |
| Textura sem recorte | `\fn` **e** texto que é só a repetição de 1–4 letras | `ccccc`, `abcdabcd` |

`\clip` sozinho ou `\fn` sozinho com texto real (letra de música, placa) continuam sendo achatados. O
dano era real: o Patlabor sem lore, achatado em 06/08 pelo código antigo, tinha 14.357 linhas de lixo
no `Default` em 6 episódios — reparado em 08/10/2026 linha a linha a partir do backup de antes do
achatamento (`backups/achatar_estilos_20260806_170047`), sem tocar nas demais linhas.

---

## Endpoints REST

| Endpoint | Payload | Canal SSE |
|----------|---------|-----------|
| `POST /api/troca-legenda/escanear` | `{diretorioLegendas}` | — (resposta síncrona) |
| `POST /api/troca-legenda/aplicar` | `{diretorioLegendas, forcarArial?}` | `troca-tipo-legenda` |

```json
{ "diretorioLegendas": "C:/animes/[Joseki] Gundam 08th MS Team/traducao-ptbr" }
```

Para decisão manual de legibilidade:

```json
{ "diretorioLegendas": "C:/animes/Mobile Suit Zeta Gundam/traducao_ptbr", "forcarArial": true }
```

`diretorioLegendas` é **obrigatório** (`400` se ausente).

---

## Pontos de atenção

- A troca altera o `Fontname` em `[V4+ Styles]` e o `\fn` de fonte vietnamita antiga dentro das linhas `Dialogue:` e `Comment:` (desde 14/10/2026; até então só o cabeçalho). O relatório de auditoria lista o `\fn` como `\fn nas falas`; `\fn` de fonte comum não é tocado.
- Depois da troca, os MKVs finais precisam ser **re-remuxados** ([Remuxer](etapa-5.1-remuxer.md)) para embutir a legenda corrigida.
- Fontes anexadas no MKV original que nenhum estilo referencia mais (ex.: o `Vnantiqb.ttf` órfão) são inofensivas, mas continuam dentro do vídeo até um remux que as descarte.
- Regra prática ao iniciar **qualquer série nova**: rodar o escaneamento na pasta das legendas extraídas antes de traduzir — fontes `.Vn*`, `VNI-*` e similares quebram a acentuação PT-BR silenciosamente.
- A normalização manual para Arial mexe somente no campo `Fontname` dos estilos. O **Achatador de Estilos Decorativos** continua sendo uma aba independente na mesma página e não é acionado por essa troca de fonte.

---

## Navegação

| Anterior | Próximo |
|----------|---------|
| [← Revisão de Lore](etapa-3.2-revisao-lore.md) | [Remuxer →](etapa-5.1-remuxer.md) |
