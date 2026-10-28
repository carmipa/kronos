package org.traducao.projeto.traducao.application;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.traducao.projeto.llm.domain.Lote;
import org.traducao.projeto.llm.domain.TraducaoLote;
import org.traducao.projeto.qualidadeTraducao.application.MascaradorTags;
import org.traducao.projeto.qualidadeTraducao.application.IsoladorQuebraDialogo;
import org.traducao.projeto.qualidadeTraducao.application.ProtecaoLegendaAssService;
import org.traducao.projeto.qualidadeTraducao.domain.AlucinacaoDetectadaException;
import org.traducao.projeto.traducao.domain.SaneadorEnfaseDegenerada;
import org.traducao.projeto.core.texto.TextoSemTags;
import org.traducao.projeto.traducao.domain.exceptions.TraducaoParcialException;
import org.traducao.projeto.traducao.domain.ports.TelemetriaTraducaoPort;
import org.traducao.projeto.traducao.infrastructure.config.TradutorProperties;
import org.traducao.projeto.traducao.presentation.ui.ConsoleUILogger;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import org.traducao.projeto.qualidadeTraducao.application.RemovedorItalico;

/**
 * PROPÓSITO DE NEGÓCIO: traduz as falas pendentes de um episódio — mascara as tags,
 * fatia em lotes, chama o LLM e restaura as tags na resposta —, isolando o coração do
 * fluxo de tradução da orquestração de {@link ProcessarArquivoUseCase} (FASE F, R6).
 *
 * <h2>Invariantes do domínio</h2>
 * <ul>
 *   <li>As tags de formatação são mascaradas antes do LLM e restauradas depois; o
 *       modelo nunca vê a sintaxe de estilo.</li>
 *   <li>Os lotes respeitam {@code tradutorProperties.tamanhoLote()} e preservam a ordem
 *       das falas para o mapeamento original↔traduzido.</li>
 *   <li>Uma alucinação isolada numa fala (tags corrompidas ou linha ASS pesada
 *       contaminada) NÃO derruba o lote/episódio: aquela fala mantém o texto original e é
 *       sinalizada para revisão, sem interromper as demais.</li>
 * </ul>
 *
 * <h2>Comportamento em caso de falha</h2>
 * Um cancelamento cooperativo no meio do episódio propaga {@link TraducaoParcialException}
 * com as traduções já concluídas (desmascaradas), para que o chamador persista o progresso
 * parcial; a barra de progresso é sempre finalizada.
 */
@Service
public class TradutorLotesService {

    private static final Logger log = LoggerFactory.getLogger(TradutorLotesService.class);

    private final MascaradorTags mascarador;
    private final TradutorProperties propriedades;
    private final ConsoleUILogger uiLogger;
    private final ProcessarEpisodioUseCase processarEpisodioUseCase;
    private final ProtecaoLegendaAssService protecaoAss;
    private final TelemetriaTraducaoPort telemetriaTraducao;
    private final IsoladorQuebraDialogo isoladorQuebra;
    private final RemovedorItalico removedorItalico;
    private final SimplificadorItalicoRedundante simplificadorItalico;
    private final DescarteItalicoUltimoRecurso descarteItalico;
    private final DetectorCorrenteFrasePartida detectorCorrente;
    private final GuardaCorrenteTraduzida guardaCorrente;

    /**
     * PROPÓSITO DE NEGÓCIO: injeta as peças do coração do fluxo — mascaramento, tamanho de lote,
     * progresso da UI, execução do episódio, proteção ASS e telemetria.
     *
     * <p>INVARIANTES DO DOMÍNIO: guarda as referências recebidas; não as substitui nem cria
     * implementação própria.
     *
     * <p>COMPORTAMENTO EM CASO DE FALHA: não valida os argumentos; a injeção CDI garante os beans.
     *
     * @param mascarador mascara/desmascara as tags ao redor do texto enviado ao LLM
     * @param propriedades fornece o tamanho de lote da fatia
     * @param uiLogger barra de progresso e mensagens do episódio
     * @param processarEpisodioUseCase executa a tradução dos lotes (sequencial, GPU única)
     * @param protecaoAss detecta resposta suspeita em linha ASS pesada
     * @param telemetriaTraducao contabiliza alucinações prevenidas
     * @param isoladorQuebra isola o {@code \N} mid-sentence do diálogo antes do LLM e o reaplica depois
     * @param detectorCorrente agrupa as falas que formam uma frase partida entre eventos
     * @param guardaCorrente reprova corrente cujo conteúdo o LLM deslocou entre as linhas
     */
    public TradutorLotesService(
        MascaradorTags mascarador,
        TradutorProperties propriedades,
        ConsoleUILogger uiLogger,
        ProcessarEpisodioUseCase processarEpisodioUseCase,
        ProtecaoLegendaAssService protecaoAss,
        TelemetriaTraducaoPort telemetriaTraducao,
        IsoladorQuebraDialogo isoladorQuebra,
        RemovedorItalico removedorItalico,
        SimplificadorItalicoRedundante simplificadorItalico,
        DescarteItalicoUltimoRecurso descarteItalico,
        DetectorCorrenteFrasePartida detectorCorrente,
        GuardaCorrenteTraduzida guardaCorrente
    ) {
        this.mascarador = mascarador;
        this.propriedades = propriedades;
        this.uiLogger = uiLogger;
        this.processarEpisodioUseCase = processarEpisodioUseCase;
        this.protecaoAss = protecaoAss;
        this.telemetriaTraducao = telemetriaTraducao;
        this.isoladorQuebra = isoladorQuebra;
        this.removedorItalico = removedorItalico;
        this.simplificadorItalico = simplificadorItalico;
        this.descarteItalico = descarteItalico;
        this.detectorCorrente = detectorCorrente;
        this.guardaCorrente = guardaCorrente;
    }

    /**
     * PROPÓSITO DE NEGÓCIO: traduz o conjunto de falas ainda não resolvidas pelo cache,
     * devolvendo o mapa original↔traduzido pronto para validação e reconstrução.
     *
     * <p>INVARIANTES DO DOMÍNIO: mascara/desmascara as tags; fatia em lotes de
     * {@code tamanhoLote}; preserva a ordem para casar cada tradução com seu original.
     *
     * <p>COMPORTAMENTO EM CASO DE FALHA: conjunto vazio devolve {@code Map.of()}; um
     * cancelamento no meio propaga {@link TraducaoParcialException} com o progresso parcial
     * já desmascarado; a barra de progresso é sempre finalizada.
     */
    public Map<String, String> traduzirPendentes(
            LinkedHashSet<String> textosPendentes, Set<String> textosDeduplicaveis,
            String nomeArquivo, List<String> avisos, String promptCongelado)
            throws InterruptedException, ExecutionException {
        // Compat: sem inventário explícito, vale a regra histórica — isola a quebra
        // apenas do que NÃO é deduplicável, ou seja, só do diálogo.
        return traduzirPendentes(textosPendentes, textosDeduplicaveis, null,
            nomeArquivo, avisos, promptCongelado);
    }

    /**
     * PROPÓSITO DE NEGÓCIO: mesma tradução em lotes, recebendo o inventário de falas cuja
     * quebra {@code \N} pode ser ISOLADA antes do mascaramento. A isolação existe porque um
     * {@code [[TAGn]]} no meio da frase é o que o LLM mais erra: o português reordena as
     * palavras e o marcador se perde, derrubando a fala inteira.
     *
     * <p>Até 2026-07-23 a isolação era exclusiva do diálogo, e música/letreiro seguiam com o
     * {@code \N} mascarado. O custo apareceu medido no Break Blade filme 1: das 34 falas, 6
     * viraram pendência por marcador corrompido — todas verso de música e cartela de título com
     * tag no meio do texto, o caso exato que a isolação resolve.
     *
     * <p>INVARIANTES DO DOMÍNIO: o inventário decide por FALA, não por heurística local. KFX e
     * romaji preservado ficam de fora por decisão do chamador — neles a posição da quebra tem
     * relação com o tempo do efeito, não é quebra visual. Cada fala reaplica a QUANTIDADE de
     * quebras que ela mesma tinha, então camadas que deduplicam entre si não trocam de layout.
     *
     * <p>COMPORTAMENTO EM CASO DE FALHA: inventário {@code null} recai na regra histórica
     * (compatibilidade); fala ausente do inventário simplesmente não tem a quebra isolada.
     */
    public Map<String, String> traduzirPendentes(
            LinkedHashSet<String> textosPendentes, Set<String> textosDeduplicaveis,
            Set<String> textosComQuebraIsolavel,
            String nomeArquivo, List<String> avisos, String promptCongelado)
            throws InterruptedException, ExecutionException {
        return traduzirPendentes(textosPendentes, textosDeduplicaveis, textosComQuebraIsolavel,
            nomeArquivo, avisos, promptCongelado, new DesfechoDasFalas());
    }

    /**
     * PROPÓSITO DE NEGÓCIO: mesma tradução em lotes, devolvendo também, fala por fala, o que o
     * pipeline DECIDIU — causa real de cada original mantido e quais falas vieram da segunda
     * opinião — em {@code desfecho}, que o chamador cria e lê.
     *
     * <p>INVARIANTES DO DOMÍNIO: o mapa de traduções devolvido é o mesmo da sobrecarga sem
     * desfecho; {@code desfecho} só acrescenta procedência, nunca muda tradução.
     *
     * <p>COMPORTAMENTO EM CASO DE FALHA: no caminho parcial o desfecho é preenchido ANTES de a
     * {@link TraducaoParcialException} ser relançada, para o cache parcial também respeitá-lo.
     */
    public Map<String, String> traduzirPendentes(
            LinkedHashSet<String> textosPendentes, Set<String> textosDeduplicaveis,
            Set<String> textosComQuebraIsolavel,
            String nomeArquivo, List<String> avisos, String promptCongelado,
            DesfechoDasFalas desfecho)
            throws InterruptedException, ExecutionException {
        return traduzirPendentes(textosPendentes, textosDeduplicaveis, textosComQuebraIsolavel,
            nomeArquivo, avisos, promptCongelado, desfecho, (anterior, seguinte) -> true);
    }

    /**
     * PROPÓSITO DE NEGÓCIO: mesma tradução, sabendo quais pares de falas são VIZINHOS no documento
     * — a única informação que permite formar corrente de frase partida sem juntar falas distantes.
     *
     * <p>INVARIANTES DO DOMÍNIO: a lista de pendentes NÃO é o documento: dela já saíram as falas
     * vindas do cache, as repetidas e as mantidas por fonte em português. Com a do meio fora, as
     * duas vizinhas dela ficavam coladas na lista e podiam virar corrente (achado da auditoria de
     * 25/09/2026). Com {@code vizinhas}, só liga quem é vizinho de verdade.
     *
     * <p>COMPORTAMENTO EM CASO DE FALHA: predicado que aceita tudo reproduz o comportamento anterior.
     */
    public Map<String, String> traduzirPendentes(
            LinkedHashSet<String> textosPendentes, Set<String> textosDeduplicaveis,
            Set<String> textosComQuebraIsolavel,
            String nomeArquivo, List<String> avisos, String promptCongelado,
            DesfechoDasFalas desfecho, java.util.function.BiPredicate<String, String> vizinhas)
            throws InterruptedException, ExecutionException {
        // FALA DE LOCUTORES ("- A\N- B"): separa o texto de cada locutor ANTES do caminho de
        // sempre e remonta DEPOIS, com o travessão original e a quebra entre eles. Ver
        // FalaDeLocutores. Só diálogo: as camadas deduplicáveis são música e karaokê.
        Map<String, FalaDeLocutores> locutores = new LinkedHashMap<>();
        LinkedHashSet<String> expandidos = new LinkedHashSet<>();
        for (String original : textosPendentes) {
            Optional<FalaDeLocutores> fala = textosDeduplicaveis.contains(original)
                ? Optional.empty() : FalaDeLocutores.decompor(original);
            if (fala.isPresent()) {
                locutores.put(original, fala.get());
                expandidos.addAll(fala.get().textos());
            } else {
                expandidos.add(original);
            }
        }
        if (locutores.isEmpty()) {
            return traduzirSemLocutores(textosPendentes, textosDeduplicaveis, textosComQuebraIsolavel,
                nomeArquivo, avisos, promptCongelado, desfecho, vizinhas);
        }
        // O trecho de um locutor não está no documento: não pode virar corrente de frase partida
        // com fala nenhuma. O que coincide com uma fala de verdade continua sendo ela.
        Set<String> trechos = new java.util.HashSet<>();
        locutores.values().forEach(f -> trechos.addAll(f.textos()));
        trechos.removeAll(textosPendentes);
        java.util.function.BiPredicate<String, String> vizinhasReais =
            (anterior, seguinte) -> !trechos.contains(anterior) && !trechos.contains(seguinte)
                && vizinhas.test(anterior, seguinte);
        DesfechoDasFalas desfechoDosTrechos = new DesfechoDasFalas();
        try {
            Map<String, String> traduzidos = traduzirSemLocutores(expandidos, textosDeduplicaveis,
                textosComQuebraIsolavel, nomeArquivo, avisos, promptCongelado, desfechoDosTrechos, vizinhasReais);
            return remontarLocutores(textosPendentes, locutores, traduzidos, desfechoDosTrechos, desfecho);
        } catch (TraducaoParcialException e) {
            Map<String, String> parciais = remontarLocutores(textosPendentes, locutores,
                e.getDicionarioParcial() != null ? e.getDicionarioParcial() : Map.of(),
                desfechoDosTrechos, desfecho);
            TraducaoParcialException relancada = new TraducaoParcialException(e.getMessage(), parciais, e.getCause());
            throw e.interrompidaPeloUsuario() ? relancada.marcarInterrompidaPeloUsuario() : relancada;
        }
    }

    /**
     * PROPÓSITO DE NEGÓCIO: devolve ao chamador o mapa e o desfecho em termos das falas que ELE
     * pediu, remontando cada fala de locutores a partir da tradução dos seus trechos.
     *
     * <p>INVARIANTES DO DOMÍNIO: fala comum passa como veio (tradução, causa e segunda opinião);
     * fala de locutores só entra no mapa com TODOS os trechos traduzidos — senão fica pendente,
     * com a causa do trecho que falhou dentro da sua (o classificador de pendência lê esse texto);
     * se algum trecho veio da segunda opinião, a fala inteira é marcada como tal.
     *
     * <p>COMPORTAMENTO EM CASO DE FALHA: trecho sem tradução e sem causa registrada gera uma causa
     * explícita, nunca uma pendência muda.
     */
    private Map<String, String> remontarLocutores(Set<String> textosPendentes,
            Map<String, FalaDeLocutores> locutores, Map<String, String> traduzidos,
            DesfechoDasFalas desfechoDosTrechos, DesfechoDasFalas desfecho) {
        Map<String, String> resultado = new LinkedHashMap<>();
        Map<String, String> causas = desfechoDosTrechos.causaDoOriginalMantido();
        Set<String> segundaOpiniao = desfechoDosTrechos.traduzidasPorSegundaOpiniao();
        for (String original : textosPendentes) {
            FalaDeLocutores fala = locutores.get(original);
            if (fala == null) {
                String traducao = traduzidos.get(original);
                if (traducao != null) {
                    resultado.put(original, traducao);
                }
                if (causas.containsKey(original)) {
                    desfecho.registrarOriginalMantido(original, causas.get(original));
                }
                if (segundaOpiniao.contains(original)) {
                    desfecho.registrarSegundaOpiniao(original);
                }
                continue;
            }
            // Trecho com causa registrada é trecho em que o pipeline DESISTIU — e, desistindo, ele
            // devolve o próprio inglês como "tradução". Montar com ele publicaria a fala meio em
            // inglês ("- This way.\N- Certo."), que o portão final não vê como eco porque a linha
            // inteira não é idêntica. Conta como sem tradução.
            List<String> partes = new ArrayList<>(fala.textos().size());
            fala.textos().forEach(trecho -> partes.add(causas.containsKey(trecho) ? null : traduzidos.get(trecho)));
            Optional<String> montada = fala.recompor(partes);
            if (montada.isPresent()) {
                resultado.put(original, montada.get());
            } else {
                String faltou = fala.textos().stream()
                    .filter(trecho -> causas.containsKey(trecho)
                        || traduzidos.get(trecho) == null || traduzidos.get(trecho).isBlank())
                    .findFirst().orElse(fala.textos().getFirst());
                String causaDoTrecho = causas.get(faltou);
                desfecho.registrarOriginalMantido(original, "fala de dois ou mais locutores: o trecho \""
                    + faltou + "\" ficou sem traducao"
                    + (causaDoTrecho != null ? " (" + causaDoTrecho + ")" : ""));
            }
            if (fala.textos().stream().anyMatch(segundaOpiniao::contains)) {
                desfecho.registrarSegundaOpiniao(original);
            }
        }
        return resultado;
    }

    /**
     * PROPÓSITO DE NEGÓCIO: o caminho de tradução de sempre — mascara, fatia, traduz e desmascara
     * — para falas que já chegam sem locutores misturados.
     *
     * <p>INVARIANTES DO DOMÍNIO: os mesmos de {@link #traduzirPendentes}; as chaves do mapa e do
     * desfecho são os textos recebidos.
     *
     * <p>COMPORTAMENTO EM CASO DE FALHA: conjunto vazio devolve {@code Map.of()}; cancelamento
     * propaga {@link TraducaoParcialException} com o progresso parcial já desmascarado.
     */
    private Map<String, String> traduzirSemLocutores(
            LinkedHashSet<String> textosPendentes, Set<String> textosDeduplicaveis,
            Set<String> textosComQuebraIsolavel,
            String nomeArquivo, List<String> avisos, String promptCongelado,
            DesfechoDasFalas desfecho, java.util.function.BiPredicate<String, String> vizinhas)
            throws InterruptedException, ExecutionException {
        if (textosPendentes.isEmpty()) {
            return Map.of();
        }

        Map<String, List<String>> tagsPorTexto = new LinkedHashMap<>();
        Map<String, String> textoMascaradoPorOriginal = new LinkedHashMap<>();
        Map<String, Integer> quebrasPorOriginal = new LinkedHashMap<>();
        Map<String, List<String>> partesIsoladasPorOriginal = new LinkedHashMap<>();
        // Falas cuja moldura foi SEPARADA em vez de mascarada; a recomposição acontece em
        // expandirParaCamadas, depois de o desmascaramento comum ter feito suas checagens.
        Map<String, TextoSemTags> semTagsPorOriginal = new LinkedHashMap<>();
        for (String original : textosPendentes) {
            // Isola o \N mid-sentence ANTES de mascarar, para o LLM traduzir a frase inteira
            // sem marcador no meio; a quebra é reaplicada na tradução em desmascararComFallback.
            // Quem pode ser isolado vem do CHAMADOR, que conhece a categoria da fala: diálogo,
            // música latina e letreiro (onde \N é quebra visual). KFX e romaji ficam de fora --
            // neles a quebra tem relação com o tempo do efeito. Sem inventário, vale a regra
            // histórica (só o que não é deduplicável, isto é, só diálogo).
            // ORDEM IMPORTA: o itálico redundante é simplificado ANTES da isolação de quebra.
            // O padrão de 140 das 212 corrupções medidas é {\i1}A{\i}\N{\i1}B — o par
            // desliga/religa CERCA a quebra. Se a quebra saísse primeiro, o par deixaria de
            // ser reconhecível e os 3 marcadores extras seguiriam para o LLM.
            // ITALICO ELIMINADO NA ORIGEM (decisao do Paulo, 2026-08-22: "num filme normal nao
            // tem italico, e frescura"). Tirar aqui nao e so estetica: e o que fecha o buraco
            // do par vazio. O mascarador garante que a tag VOLTE, nao que ela continue cercando
            // a palavra que realcava, e o portugues reordena --
            //   vai   : there's no [[TAG0]]love[[TAG1]] in this kind
            //   volta : nao ha amor [[TAG0]][[TAG1]] neste tipo
            // marcadores intactos, na ordem, sem duplicar: o desmascarador aceita e esta CERTO
            // em aceitar. Sem o par, o caso deixa de existir.
            //
            // MUSICA FICA DE FORA: as camadas deduplicaveis sao karaoke/musica, que esta sessao
            // tem ordem de nao tocar. Medido em 22/08/2026 no acervo: das 201.765 linhas
            // musicais, 18 tem italico -- risco proximo de zero, e mesmo assim vetado.
            String textoParaMascarar = textosDeduplicaveis.contains(original)
                ? original
                : removedorItalico.remover(original);
            textoParaMascarar = simplificadorItalico.simplificar(textoParaMascarar);
            boolean quebraIsolavel = textosComQuebraIsolavel == null
                ? !textosDeduplicaveis.contains(original)
                : textosComQuebraIsolavel.contains(original);
            if (quebraIsolavel) {
                IsoladorQuebraDialogo.FalaIsolada isolada = isoladorQuebra.isolar(textoParaMascarar);
                if (isolada.quebras() > 0) {
                    // As partes que a quebra separava, para o episódio aceitar a resposta que o
                    // modelo devolve uma linha por parte (ProcessarEpisodioUseCase).
                    partesIsoladasPorOriginal.put(original, List.of(textoParaMascarar.split("\\\\N", -1)));
                    textoParaMascarar = isolada.textoSemQuebra();
                    quebrasPorOriginal.put(original, isolada.quebras());
                }
            }
            // TAG SÓ NA BORDA: em vez de mascarar, SEPARA. O LLM recebe a frase pura, sem um
            // único [[TAGn]] para perder, e a moldura é recolocada aqui na volta.
            //
            // O prejuízo que motivou (2026-07-22): 393 das 412 falas perdidas — 95% — caíram por
            // marcador ausente, quase todas com tradução aproveitável; a resposta na época foi o
            // remendo ReparadorMarcadoresLlm. O corretor de karaokê atacou a CAUSA em 07/08/2026
            // e a taxa foi de 34% para 100% nas linhas que passaram a viajar sem marcador.
            // Medido no Guilty Crown: 488 falas de diálogo (8,3%) estão nesta forma; as 87,6% sem
            // tag nenhuma NÃO entram aqui e seguem byte a byte pelo caminho de sempre.
            Optional<TextoSemTags> semTags = propriedades.textoPuroAoLlm()
                ? TextoSemTags.decompor(textoParaMascarar)
                : Optional.empty();
            if (semTags.isPresent()) {
                semTagsPorOriginal.put(original, semTags.get());
                tagsPorTexto.put(original, List.of());
                textoMascaradoPorOriginal.put(original, semTags.get().textoLimpo());
                continue;
            }
            MascaradorTags.Mascarado mascarado = mascarador.mascarar(textoParaMascarar);
            tagsPorTexto.put(original, mascarado.tags());
            textoMascaradoPorOriginal.put(original, mascarado.texto());
        }

        // Dedup por texto MASCARADO no subconjunto deduplicável (camadas musicais):
        // cada texto mascarado distinto é traduzido UMA vez (o 1º original é o
        // "representante"); as demais camadas reaproveitam a tradução mascarada,
        // desmascarando com as PRÓPRIAS tags. Diálogo (fora do subconjunto) nunca
        // deduplica — comportamento antigo intacto. Como a chave é o mascarado (mesmo
        // texto E mesma estrutura de tags), nenhuma tradução muda.
        Map<String, String> representantePorMascarado = new LinkedHashMap<>();
        // Espaço de chaves SEPARADO para o caso só-prefixo. Um mapa próprio em vez de prefixar a
        // chave com um sentinela: sentinela ou colide com texto real, ou vira caractere de
        // controle no fonte — a primeira versão desta linha gravou dois bytes NUL no arquivo, que
        // compilaram e funcionaram, e deixaram o .java binário para qualquer ferramenta de texto.
        Map<String, String> representantePorVisivel = new LinkedHashMap<>();
        List<String> representantes = new ArrayList<>();
        Map<String, String> representanteDeOriginal = new LinkedHashMap<>();
        for (String original : textosPendentes) {
            String rep = original;
            if (textosDeduplicaveis.contains(original)) {
                // CHAVE: normalmente o texto MASCARADO — mesmo texto E mesma estrutura de tags.
                // Conservador de propósito, e por isso NÃO junta camadas de composição: as três
                // camadas de um cartão diferem justamente na quantidade de tags de prefixo
                // ({=0}{\an2..} / {\an2..} / {=1}{\an2..}), e cada uma ia ao LLM por conta.
                //
                // Medido no acervo em 05/08/2026: 139 grupos com o MESMO texto visível receberam
                // traduções DIFERENTES na mesma execução — 378 falas. E elas renderizam JUNTAS,
                // então a divergência aparece na tela:
                //   "January 7th, Stellar Year 2149" -> "7 de janeiro, Ano Estelar" | "7 de janeiro do Ano Estelar"
                //   "Sankt Jeder, Federal Republic of Giad" -> correto | "República Federal de Sankt Jeder"
                //
                // Quando TODAS as tags da fala são de PREFIXO, a chave passa a ser o texto
                // VISÍVEL: reaplicar é "prefixo próprio + tradução comum", sem depender de casar
                // marcador. Com tag no MEIO a chave continua a mascarada, porque ali a tradução
                // de uma camada não tem onde reencaixar os marcadores da outra. Dos 139 grupos,
                // 122 (88%) são só-prefixo.
                String masc = textoMascaradoPorOriginal.get(original);
                String existente = soPrefixo(original)
                    ? representantePorVisivel.putIfAbsent(semMarcadores(masc), original)
                    : representantePorMascarado.putIfAbsent(masc, original);
                rep = existente != null ? existente : original;
            }
            representanteDeOriginal.put(original, rep);
            if (rep.equals(original)) {
                representantes.add(original);
            }
        }

        int tamanhoLote = propriedades.tamanhoLote();
        // Com a flag ligada, uma frase partida entre eventos consecutivos vira UM lote, para
        // o LLM enxergar a frase inteira; o resto continua fatiado pelo tamanho configurado.
        List<List<String>> chunksRepresentantes = propriedades.agruparFrasePartida()
            ? detectorCorrente.agrupar(representantes, tamanhoLote, vizinhas)
            : fatiarPorTamanho(representantes, tamanhoLote);
        List<Lote> lotes = new ArrayList<>();
        for (List<String> chunkReps : chunksRepresentantes) {
            lotes.add(new Lote(lotes.size() + 1,
                chunkReps.stream().map(textoMascaradoPorOriginal::get).toList()));
        }

        uiLogger.iniciarLotes(lotes.size(), nomeArquivo);
        ProcedenciaPorRepresentante procedencia = new ProcedenciaPorRepresentante(textoMascaradoPorOriginal);
        Map<String, List<String>> partesPorLinhaEnviada = new HashMap<>();
        partesIsoladasPorOriginal.forEach((original, partes) ->
            partesPorLinhaEnviada.putIfAbsent(textoMascaradoPorOriginal.get(original), partes));
        List<TraducaoLote> resultados;
        try {
            resultados = processarEpisodioUseCase.processarEpisodio(lotes, promptCongelado, partesPorLinhaEnviada);
        } catch (TraducaoParcialException e) {
            Map<String, String> mascaradoPorRepresentante = new HashMap<>();
            if (e.getLotesSalvos() != null) {
                coletarMascaradoPorRepresentante(e.getLotesSalvos(), chunksRepresentantes,
                    mascaradoPorRepresentante, procedencia);
            }
            // A guarda de deslocamento também vale na PARADA: sem ela, uma corrente com o texto
            // trocado de tempo ia para o cache parcial e era reaproveitada na retomada. Aqui não há
            // retradução (o episódio está parando): a corrente reprovada simplesmente não é salva,
            // e a próxima execução a traduz de novo.
            if (propriedades.agruparFrasePartida()) {
                correntesReprovadas(chunksRepresentantes, textoMascaradoPorOriginal, mascaradoPorRepresentante)
                    .forEach(mascaradoPorRepresentante::remove);
            }
            procedencia.transferirPara(desfecho, textosPendentes, representanteDeOriginal);
            Map<String, String> traducoesParciais = expandirParaCamadas(
                textosPendentes, representanteDeOriginal, mascaradoPorRepresentante, tagsPorTexto,
                quebrasPorOriginal, semTagsPorOriginal, avisos, desfecho);
            TraducaoParcialException relancada =
                new TraducaoParcialException(e.getMessage(), traducoesParciais, e.getCause());
            throw e.interrompidaPeloUsuario() ? relancada.marcarInterrompidaPeloUsuario() : relancada;
        } finally {
            uiLogger.finalizar();
        }

        Map<String, String> mascaradoPorRepresentante = new HashMap<>();
        coletarMascaradoPorRepresentante(resultados, chunksRepresentantes, mascaradoPorRepresentante, procedencia);
        boolean paradaPedida = propriedades.agruparFrasePartida()
            && retraduzirCorrentesReprovadas(chunksRepresentantes, textoMascaradoPorOriginal,
                mascaradoPorRepresentante, promptCongelado, procedencia, partesPorLinhaEnviada);
        procedencia.transferirPara(desfecho, textosPendentes, representanteDeOriginal);
        Map<String, String> traduzidas = expandirParaCamadas(
            textosPendentes, representanteDeOriginal, mascaradoPorRepresentante, tagsPorTexto,
            quebrasPorOriginal, semTagsPorOriginal, avisos, desfecho);
        if (paradaPedida) {
            // O mesmo caminho da parada no laço principal: o caso de uso salva o parcial no cache
            // e o lote sai [PARADO]/CANCELADO, em vez de o arquivo seguir para a publicação.
            throw new TraducaoParcialException("Tradução interrompida pelo usuário.", traduzidas, null)
                .marcarInterrompidaPeloUsuario();
        }
        return traduzidas;
    }

    /**
     * PROPÓSITO DE NEGÓCIO: acumula, por REPRESENTANTE, o que o episódio decidiu sobre cada fala
     * enviada — causa de ter mantido o original e tradução vinda da segunda opinião — e no fim
     * repassa isso a cada fala original que o representante cobre (camadas deduplicadas incluídas).
     *
     * <p>INVARIANTES DO DOMÍNIO: a última coleta vence — a retradução individual de uma corrente
     * reprovada SUBSTITUI o desfecho da passada agrupada, inclusive apagando uma causa que a nova
     * passada não confirmou. O casamento é pelo texto MASCARADO, que é exatamente o que o episódio
     * recebeu no lote.
     *
     * <p>COMPORTAMENTO EM CASO DE FALHA: representante sem registro fica sem causa e fora da
     * segunda opinião — ausência nunca é inventada.
     */
    private static final class ProcedenciaPorRepresentante {
        private final Map<String, String> textoMascaradoPorOriginal;
        private final Map<String, String> causaPorRepresentante = new HashMap<>();
        private final Set<String> segundaOpiniaoPorRepresentante = new java.util.HashSet<>();

        ProcedenciaPorRepresentante(Map<String, String> textoMascaradoPorOriginal) {
            this.textoMascaradoPorOriginal = textoMascaradoPorOriginal;
        }

        void registrar(String representante, TraducaoLote lote) {
            String mascarado = textoMascaradoPorOriginal.get(representante);
            String causa = mascarado != null ? lote.causasDoOriginalMantido().get(mascarado) : null;
            if (causa != null) {
                causaPorRepresentante.put(representante, causa);
            } else {
                causaPorRepresentante.remove(representante);
            }
            if (mascarado != null && lote.mascaradosSegundaOpiniao().contains(mascarado)) {
                segundaOpiniaoPorRepresentante.add(representante);
            } else {
                segundaOpiniaoPorRepresentante.remove(representante);
            }
        }

        void registrarCausa(String representante, String causa) {
            causaPorRepresentante.put(representante, causa);
        }

        void transferirPara(DesfechoDasFalas desfecho, Set<String> originais,
                Map<String, String> representanteDeOriginal) {
            for (String original : originais) {
                String rep = representanteDeOriginal.get(original);
                if (rep == null) {
                    continue;
                }
                String causa = causaPorRepresentante.get(rep);
                if (causa != null) {
                    desfecho.registrarOriginalMantido(original, causa);
                }
                if (segundaOpiniaoPorRepresentante.contains(rep)) {
                    desfecho.registrarSegundaOpiniao(original);
                }
            }
        }
    }

    /**
     * PROPÓSITO DE NEGÓCIO: fatiamento histórico em blocos de tamanho fixo — o caminho de
     * quando o agrupamento por frase partida está desligado.
     *
     * <p>INVARIANTES DO DOMÍNIO: preserva a ordem; o último bloco pode ser menor.
     *
     * <p>COMPORTAMENTO EM CASO DE FALHA: lista vazia devolve lista vazia.
     */
    private List<List<String>> fatiarPorTamanho(List<String> textos, int tamanho) {
        List<List<String>> blocos = new ArrayList<>();
        int passo = Math.max(1, tamanho);
        for (int i = 0; i < textos.size(); i += passo) {
            blocos.add(textos.subList(i, Math.min(i + passo, textos.size())));
        }
        return blocos;
    }

    /**
     * PROPÓSITO DE NEGÓCIO: desfaz o agrupamento das correntes em que o LLM deslocou conteúdo
     * entre as linhas, retraduzindo aquelas falas UMA A UMA — o comportamento de sempre. O
     * agrupamento é uma aposta por corrente: quando ela não paga, a corrente volta ao caminho
     * conhecido em vez de entregar uma legenda com o texto trocado de tempo.
     *
     * <p>INVARIANTES DO DOMÍNIO: só toca correntes (grupos com 2+ falas) que a
     * {@link GuardaCorrenteTraduzida} reprovou; grupo aprovado permanece byte-idêntico. A
     * segunda passada usa lotes de UMA linha, onde a divergência de contagem é estruturalmente
     * improvável.
     *
     * <p>COMPORTAMENTO EM CASO DE FALHA: se a segunda passada falhar (cancelamento, erro do
     * LLM), a tradução agrupada é MANTIDA — pior que a individual, melhor que fala sem
     * tradução. A barra de progresso não contabiliza esta passada extra; o total exibido pode
     * ficar aquém do executado quando há correntes reprovadas.
     */
    /**
     * @return {@code true} quando o operador pediu a parada durante a retradução individual
     */
    private boolean retraduzirCorrentesReprovadas(
            List<List<String>> chunksRepresentantes, Map<String, String> textoMascaradoPorOriginal,
            Map<String, String> mascaradoPorRepresentante, String promptCongelado,
            ProcedenciaPorRepresentante procedencia, Map<String, List<String>> partesPorLinhaEnviada) {
        List<String> aRefazer = correntesReprovadas(chunksRepresentantes, textoMascaradoPorOriginal,
            mascaradoPorRepresentante);
        if (aRefazer.isEmpty()) {
            return false;
        }
        uiLogger.log("[ WARN ] " + aRefazer.size()
            + " fala(s) de frase partida voltaram ao fluxo individual (guarda de deslocamento).");
        List<List<String>> chunksIndividuais = new ArrayList<>();
        List<Lote> lotesIndividuais = new ArrayList<>();
        for (String representante : aRefazer) {
            chunksIndividuais.add(List.of(representante));
            lotesIndividuais.add(new Lote(lotesIndividuais.size() + 1,
                List.of(textoMascaradoPorOriginal.get(representante))));
        }
        try {
            List<TraducaoLote> refeitos =
                processarEpisodioUseCase.processarEpisodio(lotesIndividuais, promptCongelado, partesPorLinhaEnviada);
            coletarMascaradoPorRepresentante(refeitos, chunksIndividuais, mascaradoPorRepresentante, procedencia);
        } catch (Exception e) {
            // A corrente foi REPROVADA por deslocar texto entre as linhas: publicá-la seria pôr na
            // tela uma fala no tempo de outra. Até 25/09/2026 esta falha mantinha a tradução
            // agrupada e o arquivo saía CONCLUIDO com o deslocamento dentro. Agora as falas viram
            // pendência com a causa dita — o original fica, e a próxima execução tenta de novo.
            log.warn("Segunda passada individual falhou ({}); {} fala(s) da corrente reprovada ficam pendentes.",
                e.getMessage(), aRefazer.size());
            for (String representante : aRefazer) {
                mascaradoPorRepresentante.remove(representante);
                procedencia.registrarCausa(representante, "corrente de frase partida reprovada pela guarda de"
                    + " deslocamento e a retradução individual falhou (" + e.getMessage() + ")");
            }
            if (e instanceof InterruptedException
                    || (e instanceof TraducaoParcialException p && p.interrompidaPeloUsuario())) {
                Thread.currentThread().interrupt();
                // PARADA PEDIDA NÃO É PENDÊNCIA (auditoria de 28/10/2026): devolvida ao chamador,
                // que encerra o arquivo pelo caminho da parada em vez de seguir para a publicação.
                return true;
            }
        }
        return false;
    }

    /**
     * PROPÓSITO DE NEGÓCIO: diz quais representantes pertencem a correntes (2+ falas) que a
     * {@link GuardaCorrenteTraduzida} reprovou — o LLM deslocou conteúdo entre as linhas.
     *
     * <p>INVARIANTES DO DOMÍNIO: corrente com alguma fala sem tradução não é avaliada (não há o
     * que comparar); grupo de uma fala nunca é corrente.
     *
     * <p>COMPORTAMENTO EM CASO DE FALHA: sem correntes reprovadas devolve lista vazia.
     */
    private List<String> correntesReprovadas(List<List<String>> chunksRepresentantes,
            Map<String, String> textoMascaradoPorOriginal, Map<String, String> mascaradoPorRepresentante) {
        List<String> reprovados = new ArrayList<>();
        for (List<String> chunk : chunksRepresentantes) {
            if (chunk.size() < 2) {
                continue;
            }
            List<String> enviados = chunk.stream().map(textoMascaradoPorOriginal::get).toList();
            List<String> recebidos = chunk.stream().map(mascaradoPorRepresentante::get).toList();
            if (recebidos.stream().anyMatch(java.util.Objects::isNull)) {
                continue;
            }
            GuardaCorrenteTraduzida.Veredito veredito = guardaCorrente.avaliar(enviados, recebidos);
            if (!veredito.aceita()) {
                log.warn("Corrente de {} falas reprovada pela guarda ({}).", chunk.size(), veredito.motivo());
                reprovados.addAll(chunk);
            }
        }
        return reprovados;
    }

    /**
     * PROPÓSITO DE NEGÓCIO: coleta a tradução mascarada de cada representante a partir
     * dos lotes devolvidos pelo episódio (completos ou salvos numa parcial).
     *
     * <p>INVARIANTES DO DOMÍNIO: casa cada lote ao seu chunk de representantes pelo id;
     * ignora lote fora de faixa ou com contagem de linhas divergente (defensivo).
     *
     * <p>COMPORTAMENTO EM CASO DE FALHA: um lote inconsistente é apenas ignorado — seus
     * representantes ficam sem tradução e as camadas correspondentes serão puladas.
     */
    private void coletarMascaradoPorRepresentante(
            List<TraducaoLote> lotes, List<List<String>> chunksRepresentantes, Map<String, String> destino,
            ProcedenciaPorRepresentante procedencia) {
        for (TraducaoLote tl : lotes) {
            int k = tl.idLote() - 1;
            if (k < 0 || k >= chunksRepresentantes.size()) {
                continue;
            }
            List<String> chunkReps = chunksRepresentantes.get(k);
            List<String> linhas = tl.linhasTraduzidas();
            if (linhas == null || linhas.size() != chunkReps.size()) {
                continue;
            }
            for (int j = 0; j < chunkReps.size(); j++) {
                destino.put(chunkReps.get(j), linhas.get(j));
                procedencia.registrar(chunkReps.get(j), tl);
            }
        }
    }

    /**
     * PROPÓSITO DE NEGÓCIO: reaplica a tradução mascarada do representante a TODAS as
     * camadas que compartilham aquele texto mascarado, desmascarando com as tags de cada
     * uma — de forma que um verso musical traduzido uma vez chegue a todas as suas camadas.
     *
     * <p>INVARIANTES DO DOMÍNIO: cada camada é desmascarada com as suas próprias tags
     * ({@code tagsPorTexto}); marcador corrompido cai no fallback por camada (mantém o
     * original só naquela). Camada cujo representante não foi traduzido (parcial) é omitida.
     *
     * <p>COMPORTAMENTO EM CASO DE FALHA: {@link #desmascararComFallback} absorve alucinação
     * de tags e resposta suspeita mantendo o original; nada escapa para o laço.
     */
    private Map<String, String> expandirParaCamadas(
            LinkedHashSet<String> textosPendentes, Map<String, String> representanteDeOriginal,
            Map<String, String> mascaradoPorRepresentante, Map<String, List<String>> tagsPorTexto,
            Map<String, Integer> quebrasPorOriginal, Map<String, TextoSemTags> semTagsPorOriginal,
            List<String> avisos, DesfechoDasFalas desfecho) {
        Map<String, String> traducoes = new HashMap<>();
        for (String original : textosPendentes) {
            String rep = representanteDeOriginal.get(original);
            String traduzidoMascarado = mascaradoPorRepresentante.get(rep);
            if (traduzidoMascarado == null) {
                continue;
            }
            // SANEAMENTO DE SAÍDA, aplicado aos TRÊS caminhos abaixo. Fica aqui, e não dentro de
            // um deles, porque o defeito nasce onde ninguém olhava: a tag {\i1}…{\i0} marca UMA
            // palavra do inglês, em português ela muda de lugar, e o modelo devolve os marcadores
            // fora de posição. Nenhuma guarda pegava — os marcadores ESTÃO todos lá, o
            // desmascaramento é bem-sucedido, e a fala passa por íntegra com a ênfase em volta do
            // vazio. Achado por revisão adversarial em 08/08/2026; medido no acervo (305
            // arquivos): 16 ênfases vazias e 73 espaços órfãos, e o defeito é ANTERIOR a qualquer
            // mudança desta data — sai idêntico na tradução antiga e na nova.
            // MOLDURA SEPARADA: a fala viajou sem tag alguma, então não há marcador a casar —
            // veste-se a tradução com o prefixo/sufixo da PRÓPRIA fala. Falha fechada mora no
            // recompor: resposta inútil devolve o original intacto.
            TextoSemTags semTags = semTagsPorOriginal.get(original);
            if (semTags != null) {
                traducoes.put(original,
                    SaneadorEnfaseDegenerada.sanear(semTags.recompor(traduzidoMascarado)));
                continue;
            }
            // CAMADA SÓ-PREFIXO com representante de OUTRA estrutura de tags: aqui desmascarar
            // por marcador não serve — o representante tem a contagem DELE, e a camada tem a sua.
            // A reaplicação correta é literal: prefixo próprio + tradução visível.
            String reaplicado = reaplicarPorPrefixo(original, rep, traduzidoMascarado, tagsPorTexto);
            if (reaplicado != null) {
                traducoes.put(original, SaneadorEnfaseDegenerada.sanear(reaplicado));
                continue;
            }
            traducoes.put(original, SaneadorEnfaseDegenerada.sanear(desmascararComFallback(
                original, traduzidoMascarado, tagsPorTexto.get(original),
                quebrasPorOriginal.getOrDefault(original, 0), avisos, desfecho)));
        }
        return traducoes;
    }

    /**
     * PROPÓSITO DE NEGÓCIO: reaplica a tradução comum a uma camada cujas tags são todas de
     * PREFIXO, quando ela deduplicou com um representante de estrutura de tags DIFERENTE.
     *
     * <p>Existe porque a chave de dedup passou a ser o texto VISÍVEL nesse caso: o representante
     * pode ter duas tags de prefixo e a camada apenas uma. {@code desmascarar} casaria marcador a
     * marcador e falharia; aqui a reaplicação é literal — o prefixo da PRÓPRIA camada, seguido da
     * tradução sem marcadores.
     *
     * <p>INVARIANTES DO DOMÍNIO: só age quando (a) há representante distinto, (b) as duas falas
     * são só-prefixo e (c) a tradução, tirados os marcadores de prefixo, NÃO tem marcador
     * sobrando. Faltando qualquer uma, devolve {@code null} e o chamador segue pelo caminho
     * histórico — o guarda (c) é o que protege de o LLM ter movido um marcador para o meio.
     *
     * <p>COMPORTAMENTO EM CASO DE FALHA: {@code null}, nunca exceção.
     */
    private String reaplicarPorPrefixo(String original, String rep, String traduzidoMascarado,
            Map<String, List<String>> tagsPorTexto) {
        if (rep == null || rep.equals(original) || !soPrefixo(original) || !soPrefixo(rep)) {
            return null;
        }
        String visivel = semMarcadores(traduzidoMascarado);
        if (visivel.contains("[[TAG")) {
            return null;   // marcador no meio: não é reaplicação literal, cai no caminho antigo
        }
        List<String> tags = tagsPorTexto.get(original);
        return (tags == null ? "" : String.join("", tags)) + visivel;
    }

    /**
     * Todas as tags {@code {...}} da fala estão ANTES do primeiro caractere de texto?
     *
     * <p>É a condição que torna a reaplicação literal segura: prefixo próprio + tradução comum.
     * Tag no MEIO exige casar marcador, e aí a estrutura das duas camadas tem de ser idêntica.
     */
    /**
     * PROPÓSITO DE NEGÓCIO: delega ao DONO ÚNICO da pergunta "todas as tags estão na borda?".
     *
     * <p>Até 07/08/2026 esta era a implementação em si — um {@code replaceFirst} local. Quando o
     * mesmo critério passou a ser necessário para decidir o que vai ao LLM como texto puro, manter
     * a cópia aqui criaria duas regras capazes de divergir, que é a classe de defeito que já custou
     * a este projeto uma medição errada por 8×. A regra mora em
     * {@link TextoSemTags}; aqui só se consulta.
     *
     * <p>COMPORTAMENTO EM CASO DE FALHA: {@code null} devolve {@code false}; nunca lança.
     */
    private static boolean soPrefixo(String original) {
        return TextoSemTags.tagsSoNaBorda(original);
    }

    /** Remove os marcadores {@code [[TAGn]]} do INÍCIO do texto mascarado. */
    private static String semMarcadores(String mascarado) {
        return mascarado == null ? "" : mascarado.replaceFirst("^(\\[\\[TAG\\d+]])+", "");
    }

    /**
     * PROPÓSITO DE NEGÓCIO: restaura as tags numa fala traduzida; se o LLM corrompeu/perdeu
     * marcadores {@code [[TAGn]]} (alucinação isolada), não derruba o lote/episódio: mantém
     * o texto original (sem tradução) só para essa fala e sinaliza para revisão manual.
     *
     * <p>INVARIANTES DO DOMÍNIO: tags corrompidas ou linha ASS pesada contaminada mantêm o
     * original e registram a alucinação prevenida na telemetria; nenhuma exceção escapa
     * para o laço de lotes.
     *
     * <p>COMPORTAMENTO EM CASO DE FALHA: em {@link AlucinacaoDetectadaException} ou resposta
     * suspeita devolve o texto original (que já contém suas quebras) e acrescenta um aviso;
     * caso contrário devolve a fala traduzida com o {@code \N} isolado reaplicado.
     */
    private String desmascararComFallback(String original, String traduzidoMascarado, List<String> tags,
            int quebrasIsoladas, List<String> avisos, DesfechoDasFalas desfecho) {
        try {
            String traduzido = mascarador.desmascarar(traduzidoMascarado, tags);
            if (protecaoAss.respostaSuspeita(original, traduzido)) {
                telemetriaTraducao.registrarAlucinacaoPrevenida();
                log.warn("LLM contaminou linha ASS pesada — mantendo original. Original: \"{}\" Traduzido: \"{}\"",
                    original, traduzido);
                uiLogger.log("[ WARN ] Linha ASS pesada contaminada pelo LLM — mantida sem tradução (revise manualmente): " + original);
                avisos.add("Linha ASS pesada mantida sem tradução por resposta suspeita do LLM: " + original);
                // A7: o PIPELINE desistiu e devolveu o original. Sem a causa no desfecho, a fala de
                // locutores remontava com este trecho em inglês ("- Vá até lá.\N- Sorry.") e nada
                // a marcava pendente (auditoria de 28/10/2026).
                desfecho.registrarOriginalMantido(original, "resposta suspeita do LLM em linha ASS pesada");
                return original;
            }
            return isoladorQuebra.reaplicar(traduzido, quebrasIsoladas);
        } catch (AlucinacaoDetectadaException e) {
            // ÚLTIMO RECURSO antes de publicar o inglês: se as únicas tags perdidas eram
            // itálico, entregar a TRADUÇÃO sem ênfase é melhor que entregar o original com
            // ênfase — o espectador entende a fala. Decisão do Paulo em 2026-07-31; a
            // medição dos logs mostra 47 de 212 corrupções nessa condição.
            String semItalico = descarteItalico.salvarSemItalico(tags, traduzidoMascarado);
            if (semItalico != null) {
                log.info("Itálico descartado para salvar a tradução (marcador perdido pelo LLM). "
                    + "Original: \"{}\" -> \"{}\"", original, semItalico);
                uiLogger.log("[ INFO ] Itálico descartado para preservar a tradução: " + original);
                avisos.add("Itálico descartado (marcador perdido pelo LLM): " + original);
                return isoladorQuebra.reaplicar(semItalico, quebrasIsoladas);
            }
            telemetriaTraducao.registrarAlucinacaoPrevenida();
            log.warn("Tags corrompidas pelo LLM nesta fala — mantendo o texto original sem tradução. Motivo: {}. Original: \"{}\"",
                e.getMessage(), original);
            uiLogger.log("[ WARN ] Tags corrompidas pelo LLM — fala mantida sem tradução (revise manualmente): " + original);
            avisos.add("Fala mantida sem tradução (tags corrompidas pelo LLM): " + original);
            desfecho.registrarOriginalMantido(original, "marcadores de formatação corrompidos pelo LLM");
            return original;
        }
    }
}
