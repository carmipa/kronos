package org.traducao.projeto.traducao.application;

import org.traducao.projeto.qualidadeTraducao.application.ReparadorMarcadoresLlm;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;
import org.traducao.projeto.llm.domain.Lote;
import org.traducao.projeto.llm.domain.TraducaoLote;
import org.traducao.projeto.qualidadeTraducao.application.MascaradorTags;
import org.traducao.projeto.qualidadeTraducao.application.ValidadorTraducaoService;
import org.traducao.projeto.qualidadeTraducao.domain.AlucinacaoDetectadaException;
import org.traducao.projeto.traducao.domain.exceptions.DivergenciaLinhasException;
import org.traducao.projeto.traducao.domain.exceptions.MarcadorCorrompidoException;
import org.traducao.projeto.traducao.domain.exceptions.RequisicaoRecusadaPeloLlmException;
import org.traducao.projeto.traducao.domain.exceptions.TradutorException;
import org.traducao.projeto.llm.domain.LlmPort;
import org.traducao.projeto.traducao.presentation.ui.ConsoleUILogger;
import org.traducao.projeto.traducao.domain.exceptions.TraducaoParcialException;
import org.traducao.projeto.traducao.domain.ports.TelemetriaTraducaoPort;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;


@Service
public class ProcessarEpisodioUseCase {

    private static final Logger log = LoggerFactory.getLogger(ProcessarEpisodioUseCase.class);
    private static final String MDC_LOTE_ID = "loteId";

    // Quantas tentativas extras (alem da primeira) sao feitas numa fala isolada
    // (lote de tamanho 1) antes de desistir e manter o texto original sem traducao.
    private static final int MAX_TENTATIVAS_LINHA_UNICA = 2;

    // Temperatura por tentativa numa fala isolada: null = a configurada.
    // Repetir a mesma requisicao com a mesma temperatura tende a reproduzir a
    // mesma alucinacao; subir a temperatura muda a amostragem e da chance real
    // de recuperacao antes de desistir da fala.
    private static final Double[] TEMPERATURA_POR_TENTATIVA = {null, 0.5, 0.7};

    /**
     * Textos ORIGINAIS que a segunda opinião recuperou no lote em curso, para
     * {@code traduzirEValidar} carimbá-los no {@link TraducaoLote} e o gravador de cache
     * saber quais NÃO pode guardar.
     *
     * <p>O PORQUÊ de não ir para o cache: {@code ProvenienciaCache} carimba UM
     * {@code modeloLlm} para o arquivo inteiro. Guardar ali um texto vindo de outro modelo
     * faria a proveniência mentir, e a execução seguinte reutilizaria a tradução achando que
     * é do modelo principal. São poucas falas (3 em 50 episódios, medido em 11/08/2026): sai
     * mais barato recuperá-las de novo a cada execução do que sujar o carimbo.
     *
     * <p>ThreadLocal porque este caso de uso é um bean único que pode atender mais de uma
     * thread ao mesmo tempo (fila de jobs, CLI, testes) — dentro de uma thread os lotes são
     * sequenciais —, e escopado com {@code remove()} no {@code finally} porque a thread é de
     * pool e sobrevive ao job.
     */
    private static final ThreadLocal<List<String>> SEGUNDA_OPINIAO_DO_LOTE =
        ThreadLocal.withInitial(ArrayList::new);

    /**
     * Texto MASCARADO enviado → causa REAL, para cada fala em que este caso de uso desistiu e
     * devolveu o original. Mesmo escopo e mesmo ciclo de vida de {@link #SEGUNDA_OPINIAO_DO_LOTE}.
     *
     * <p>O PORQUÊ: devolver o original é decisão DO PIPELINE, não resposta do modelo. Sem o
     * registro, o portão final só enxergava "saída idêntica à entrada" e relatava "o modelo
     * devolveu o texto original sem tradução", com a pendência contada como ECO. Medido em
     * 25/09/2026: "3, 2, 1, go!" foi descartada porque o aya respondeu QUATRO linhas para uma, e
     * nos logs do acervo havia dezenas de abandonos por contagem de linhas, resíduo, entidade
     * trocada e desproporção — todos publicados como eco. É a A7: a causa e a autoria da
     * alteração não podem ser atribuídas à origem.
     */
    private static final ThreadLocal<java.util.Map<String, String>> CAUSAS_DO_LOTE =
        ThreadLocal.withInitial(java.util.LinkedHashMap::new);

    /**
     * Quantas falas seguidas podem terminar RECUSADAS pelo servidor antes de o episódio ser
     * abortado. Uma fala patológica isolada gasta 1; o servidor recusando tudo (modelo não
     * carregado, requisição inválida por configuração) gasta este número e para.
     *
     * <p>O valor é o menor que ainda distingue os dois casos. Baixá-lo para 1 devolveria o
     * defeito de 2026-08-11; subi-lo faz um episódio inteiro sair vazio antes de alguém notar.
     */
    private static final int MAX_RECUSAS_CONSECUTIVAS = 3;

    /**
     * PROPÓSITO DE NEGÓCIO: distingue "uma fala que o servidor não engole" de "o servidor não
     * está engolindo nada", contando recusas DEFINITIVAS consecutivas dentro de um episódio.
     *
     * <h2>Invariantes do domínio</h2>
     * <ul>
     *   <li>Qualquer pedido ACEITO pelo servidor zera a contagem — é a prova direta de que ele
     *       não está recusando tudo.</li>
     *   <li>Vive por episódio e é usado só na thread que percorre os lotes daquele episódio;
     *       não é compartilhado entre execuções.</li>
     * </ul>
     *
     * <h2>Comportamento em caso de falha</h2>
     * Ao estourar o limite lança {@link TradutorException}, que o caminho existente converte em
     * {@code TraducaoParcialException} — o episódio para preservando o que já foi traduzido.
     */
    private static final class DisjuntorRecusas {
        private int consecutivas;

        void registrarPedidoAceito() {
            consecutivas = 0;
        }

        void registrarRecusaDefinitiva(int idLote) {
            consecutivas++;
            if (consecutivas >= MAX_RECUSAS_CONSECUTIVAS) {
                throw new TradutorException("O servidor LLM recusou " + consecutivas
                    + " falas seguidas (a última no lote " + idLote + ") sem aceitar nenhum pedido"
                    + " entre elas. Isso não é fala patológica: é o servidor recusando tudo"
                    + " (modelo não carregado ou requisição inválida). Episódio interrompido.");
            }
        }
    }

    private final LlmPort llmPort;
    private final ValidadorTraducaoService validador;
    private final ConsoleUILogger uiLogger;
    private final TelemetriaTraducaoPort telemetriaTraducao;
    private final MascaradorTags mascarador;
    private final ReparadorMarcadoresLlm reparadorMarcadores;

    /**
     * PROPÓSITO DE NEGÓCIO: compõe tradução, validação, acompanhamento visual e
     * telemetria sem confundir resposta rejeitada com tradução recuperada.
     *
     * <p>INVARIANTES DO DOMÍNIO: toda saída do modelo passa pelo validador antes
     * de ser devolvida ao processamento do arquivo.
     *
     * <p>COMPORTAMENTO EM CASO DE FALHA: dependência ausente impede a criação do
     * caso de uso pelo contêiner.
     */
    public ProcessarEpisodioUseCase(
        LlmPort llmPort,
        ValidadorTraducaoService validador,
        ConsoleUILogger uiLogger,
        TelemetriaTraducaoPort telemetriaTraducao,
        MascaradorTags mascarador,
        ReparadorMarcadoresLlm reparadorMarcadores
    ) {
        this.llmPort = llmPort;
        this.validador = validador;
        this.uiLogger = uiLogger;
        this.telemetriaTraducao = telemetriaTraducao;
        this.mascarador = mascarador;
        this.reparadorMarcadores = reparadorMarcadores;
    }

    public List<TraducaoLote> processarEpisodio(List<Lote> lotes) throws InterruptedException, ExecutionException {
        return processarEpisodio(lotes, null);
    }

    /**
     * @param promptSistemaCongelado prompt de sistema capturado no início do job;
     *        garante que uma troca de contexto (lore) no estado global não vaze
     *        para o meio do episódio. {@code null} usa o prompt do contexto ativo.
     */
    public List<TraducaoLote> processarEpisodio(List<Lote> lotes, String promptSistemaCongelado)
            throws InterruptedException, ExecutionException {
        return processarEpisodio(lotes, promptSistemaCongelado, java.util.Map.of());
    }

    /**
     * PROPÓSITO DE NEGÓCIO: como {@link #processarEpisodio(List, String)}, sabendo em que partes a
     * quebra {@code \N} isolada dividia cada fala — para aceitar a resposta que o modelo devolveu
     * em linhas separadas exatamente onde a quebra estava (ver {@link #juntarPartesDaQuebraIsolada}).
     *
     * <p>INVARIANTES DO DOMÍNIO: o mapa vale só para ESTE episódio (escopado por chamada e removido
     * no {@code finally}); sem entrada para a linha, nada muda.
     *
     * <p>COMPORTAMENTO EM CASO DE FALHA: igual ao da sobrecarga de dois argumentos.
     *
     * @param partesIsoladasPorLinha linha mascarada enviada → partes do original separadas pelo
     *        {@code \N} que foi isolado antes do envio
     */
    public List<TraducaoLote> processarEpisodio(List<Lote> lotes, String promptSistemaCongelado,
            java.util.Map<String, List<String>> partesIsoladasPorLinha)
            throws InterruptedException, ExecutionException {
        PARTES_ISOLADAS.set(partesIsoladasPorLinha == null ? java.util.Map.of() : partesIsoladasPorLinha);
        try {
            return processarEpisodioSequencial(lotes, promptSistemaCongelado);
        } finally {
            PARTES_ISOLADAS.remove();
            REPAROS_NA_TENTATIVA.remove();
        }
    }

    /**
     * Linhas reparadas pelo portão de par na ÚLTIMA chamada de {@link #traduzirERevalidarBruto};
     * zerado a cada chamada e lido por quem devolve a tradução com sucesso, para contar a fala
     * recuperada uma vez só. Array de um elemento para ser mutável dentro do ThreadLocal.
     */
    private static final ThreadLocal<int[]> REPAROS_NA_TENTATIVA = ThreadLocal.withInitial(() -> new int[1]);

    /** Partes da fala separadas pela quebra isolada, por linha mascarada; escopo de um episódio. */
    private static final ThreadLocal<java.util.Map<String, List<String>>> PARTES_ISOLADAS =
        ThreadLocal.withInitial(java.util.Map::of);

    private List<TraducaoLote> processarEpisodioSequencial(List<Lote> lotes, String promptSistemaCongelado)
            throws InterruptedException, ExecutionException {
        if (lotes.isEmpty()) {
            return List.of();
        }

        log.info("Iniciando processamento de {} lote(s) de forma sequencial (preservando LM Studio/GPU)", lotes.size());

        java.util.List<TraducaoLote> resultado = new java.util.ArrayList<>();
        DisjuntorRecusas disjuntor = new DisjuntorRecusas();
        for (Lote lote : lotes) {
            // Parada cooperativa (botão "Parar" da UI interrompe a thread da
            // fila): sai pelo mesmo caminho de tradução parcial, que salva no
            // cache tudo que já foi traduzido antes de encerrar.
            if (Thread.currentThread().isInterrupted()) {
                uiLogger.log("[ STOP ] Tradução interrompida pelo usuário — salvando progresso parcial.");
                throw new TraducaoParcialException(
                    "Tradução interrompida pelo usuário.", resultado, null)
                    .marcarInterrompidaPeloUsuario();
            }
            try {
                TraducaoLote tl = traduzirEValidar(lote, promptSistemaCongelado, disjuntor);
                resultado.add(tl);
            } catch (Exception e) {
                // Aborta e guarda as traduções parciais que passaram!
                TraducaoParcialException parcial = new TraducaoParcialException(
                    e.getMessage(),
                    resultado,
                    e
                );
                // A interrupção pode chegar DURANTE a requisição ao LLM: o adaptador desiste e
                // restaura o flag. Lido AQUI, antes de qualquer camada consumi-lo.
                throw Thread.currentThread().isInterrupted()
                    ? parcial.marcarInterrompidaPeloUsuario() : parcial;
            }
        }

        log.info("Processamento concluído: {} lote(s) traduzido(s) com sucesso", resultado.size());
        return resultado;
    }

    /**
     * PROPÓSITO DE NEGÓCIO: traduz e valida um lote tolerando alucinações de contagem
     * de linhas e resíduo/preâmbulo — em vez de abortar o episódio por um único lote
     * problemático, a divisão/retry isola o trecho ruim. Só uma falha de comunicação
     * real (HTTP/timeout, esgotadas as tentativas do {@link LlmPort}) aborta.
     *
     * <p>INVARIANTES DO DOMÍNIO: toda saída passa por {@code traduzirComDivisao} antes
     * de ser devolvida como sucesso; o {@code MDC} do lote é sempre limpo no {@code finally}.
     *
     * <p>COMPORTAMENTO EM CASO DE FALHA: uma falha crítica é logada e repropagada. O
     * catch trata tanto {@link TradutorException} quanto
     * {@link org.traducao.projeto.qualidadeTraducao.domain.AlucinacaoDetectadaException}
     * — desde a E8b a alucinação pertence ao peer {@code qualidadeTraducao} e não é mais
     * {@code TradutorException}; o multi-catch preserva a captura defensiva anterior
     * (embora, no fluxo normal, a alucinação seja absorvida antes por divisão/retry/fallback).
     */
    private TraducaoLote traduzirEValidar(Lote lote, String promptSistemaCongelado,
            DisjuntorRecusas disjuntor) {
        MDC.put(MDC_LOTE_ID, String.valueOf(lote.idLote()));
        // Escopo por lote, no MESMO padrão do MDC acima: a recuperação por segunda opinião
        // acontece no fundo da recursão de traduzirComDivisao, e é aqui em cima que o
        // TraducaoLote é montado. ThreadLocal porque o bean é único e pode servir mais de uma
        // thread (os lotes de UMA thread são sequenciais) — uma coleção de instância misturaria a
        // segunda opinião de um episódio com a de outro.
        SEGUNDA_OPINIAO_DO_LOTE.set(new ArrayList<>());
        CAUSAS_DO_LOTE.set(new java.util.LinkedHashMap<>());
        try {
            List<String> traduzidas = traduzirComDivisao(lote, promptSistemaCongelado, disjuntor);

            log.debug("Lote {} validado com sucesso", lote.idLote());
            uiLogger.log("[ OK ] Lote " + lote.idLote() + " traduzido com sucesso.");
            uiLogger.passoConcluido(1);

            return new TraducaoLote(lote.idLote(), traduzidas, true, null,
                SEGUNDA_OPINIAO_DO_LOTE.get(), false, CAUSAS_DO_LOTE.get());
        } catch (TradutorException | AlucinacaoDetectadaException e) {
            log.error("Falha crítica no lote {}: {}", lote.idLote(), e.getMessage());
            uiLogger.log("[ FAIL ] ERRO CRÍTICO no Lote " + lote.idLote() + ": " + e.getMessage());
            throw e;
        } finally {
            MDC.remove(MDC_LOTE_ID);
            // remove(), não set(null): thread de pool sobrevive ao job, e um ThreadLocal
            // deixado para trás vaza para a próxima execução — que herdaria a segunda
            // opinião de um episódio que nem está mais rodando.
            SEGUNDA_OPINIAO_DO_LOTE.remove();
            CAUSAS_DO_LOTE.remove();
        }
    }

    /**
     * Tenta traduzir o lote de uma vez; se o LLM devolver a contagem errada de
     * linhas, uma fala com resíduo/preâmbulo, ou se o servidor RECUSAR o pedido
     * (HTTP 4xx permanente), divide o lote pela metade e tenta cada metade
     * recursivamente, isolando o trecho problemático em vez de descartar o lote
     * inteiro (que pode ter 20+ falas, das quais só 1 costuma ser a culpada).
     *
     * <p>A recusa entrou nesta lista em 2026-08-12: ela nasce de UMA fala patológica
     * (verso de karaokê com um marcador por letra), e tratá-la como "servidor caiu"
     * custou 373 falas de diálogo no DanMachi E03. Ver
     * {@link org.traducao.projeto.traducao.domain.exceptions.RequisicaoRecusadaPeloLlmException}.
     */
    private List<String> traduzirComDivisao(Lote lote, String promptSistemaCongelado,
            DisjuntorRecusas disjuntor) {
        if (lote.linhasOriginais().size() <= 1) {
            return traduzirLinhaUnicaComFallback(lote, promptSistemaCongelado, disjuntor);
        }

        try {
            List<String> traduzidas = traduzirERevalidarBruto(lote, null, promptSistemaCongelado, null, disjuntor, false);
            for (int i = 0; i < REPAROS_NA_TENTATIVA.get()[0]; i++) {
                telemetriaTraducao.registrarFalhaTraducaoRecuperada();
            }
            return traduzidas;
        } catch (DivergenciaLinhasException | AlucinacaoDetectadaException
                | RequisicaoRecusadaPeloLlmException e) {
            int total = lote.linhasOriginais().size();
            int meio = total / 2;
            log.warn("Lote {} (tamanho {}) falhou na validação ({}). Dividindo em 2 partes e tentando novamente...",
                lote.idLote(), total, e.getMessage());
            uiLogger.log("[ WARN ] Lote " + lote.idLote() + " dividido após falha de validação: " + e.getMessage());

            Lote primeiraMetade = new Lote(lote.idLote(), lote.linhasOriginais().subList(0, meio));
            Lote segundaMetade = new Lote(lote.idLote(), lote.linhasOriginais().subList(meio, total));

            List<String> traduzidas =
                new ArrayList<>(traduzirComDivisao(primeiraMetade, promptSistemaCongelado, disjuntor));
            traduzidas.addAll(traduzirComDivisao(segundaMetade, promptSistemaCongelado, disjuntor));
            return traduzidas;
        }
    }

    /**
     * PROPÓSITO DE NEGÓCIO: recupera uma fala isolada com novas amostragens antes
     * de declará-la pendente, preservando o restante do episódio.
     *
     * <p>INVARIANTES DO DOMÍNIO: cada resposta rejeitada e cada recuperação são
     * contabilizadas separadamente; o fallback original não representa sucesso.
     *
     * <p>COMPORTAMENTO EM CASO DE FALHA: após esgotar as tentativas, devolve um
     * marcador transitório que {@link ProcessarArquivoUseCase} converte em tradução
     * vazia no cache e saída explicitamente parcial. Se a causa foi marcador
     * {@code [[TAGn]]} corrompido, o marcador transitório é a TENTATIVA recusada (e não
     * o original), para que o desmascaramento registre a causa-raiz correta em vez de
     * a pendência ser contabilizada como eco — ver {@link MarcadorCorrompidoException}.
     */
    private List<String> traduzirLinhaUnicaComFallback(Lote lote, String promptSistemaCongelado,
            DisjuntorRecusas disjuntor) {
        if (lote.linhasOriginais().isEmpty()) {
            return List.of();
        }

        RuntimeException ultimaFalha = null;
        boolean houveRespostaRejeitada = false;
        for (int tentativa = 1; tentativa <= 1 + MAX_TENTATIVAS_LINHA_UNICA; tentativa++) {
            try {
                Double temperatura = TEMPERATURA_POR_TENTATIVA[
                    Math.min(tentativa - 1, TEMPERATURA_POR_TENTATIVA.length - 1)];
                List<String> traducao = traduzirERevalidarBruto(lote, temperatura, promptSistemaCongelado, null,
                    disjuntor, tentativa == 1 + MAX_TENTATIVAS_LINHA_UNICA);
                // Uma fala, um desfecho: recuperada se houve resposta rejeitada antes OU reparo nesta.
                if (houveRespostaRejeitada || REPAROS_NA_TENTATIVA.get()[0] > 0) {
                    telemetriaTraducao.registrarFalhaTraducaoRecuperada();
                }
                return traducao;
            } catch (DivergenciaLinhasException | AlucinacaoDetectadaException
                    | RequisicaoRecusadaPeloLlmException e) {
                ultimaFalha = e;
                houveRespostaRejeitada = true;
                telemetriaTraducao.registrarRespostaTraducaoRejeitada();
            }
        }

        // SEGUNDA OPINIÃO, antes de desistir. O laço acima repete com o MESMO modelo variando
        // só a temperatura, e há uma classe de fala em que isso nunca vence: medido em
        // 11/08/2026 no Zeta, das 6 pendências que sobraram em 50 episódios com mistral-nemo,
        // CINCO eram discurso citado com aspas internas — e o towerinstruct traduziu 3 delas
        // de primeira. Desligado por padrão (tradutor.llm.modelo-recuperacao vazio).
        String modeloRecuperacao = llmPort.modeloRecuperacao();
        if (modeloRecuperacao != null && !modeloRecuperacao.isBlank()) {
            try {
                List<String> recuperada = traduzirERevalidarBruto(
                    lote, null, promptSistemaCongelado, modeloRecuperacao, disjuntor, true);
                telemetriaTraducao.registrarFalhaTraducaoRecuperada();
                // Marca o ORIGINAL para o cache não guardar tradução de outro modelo sob o
                // carimbo do principal. Ver SEGUNDA_OPINIAO_DO_LOTE.
                SEGUNDA_OPINIAO_DO_LOTE.get().addAll(lote.linhasOriginais());
                log.info("Lote {}: recuperado pela segunda opiniao do modelo \"{}\".",
                    lote.idLote(), modeloRecuperacao);
                uiLogger.log("[ SEGUNDA-OPINIAO ] Lote " + lote.idLote()
                    + " recuperado por \"" + modeloRecuperacao + "\": " + lote.linhasOriginais().getFirst());
                return recuperada;
            } catch (RuntimeException e) {
                // Reprovou na MESMA régua do principal: segue pendente, como sem a segunda
                // opinião. Nunca silenciosa — sem esta linha, um modelo de recuperação mal
                // configurado ficaria eternamente "ligado e sem efeito", indistinguível de
                // desligado.
                log.warn("Lote {}: segunda opiniao de \"{}\" tambem reprovada ({}). Fala segue pendente.",
                    lote.idLote(), modeloRecuperacao, e.getMessage());
                uiLogger.log("[ SEGUNDA-OPINIAO ] Lote " + lote.idLote() + " tambem reprovado por \""
                    + modeloRecuperacao + "\": " + e.getMessage());
            }
        }

        // Recusa em SÉRIE não é fala patológica: é o servidor recusando tudo (modelo não
        // carregado, requisição inválida por configuração). Deixar cada fala virar pendência
        // produziria um arquivo inteiro sem tradução, em silêncio e devagar — trocaria o dano
        // do aborto pelo dano pior de um resultado vazio que parece normal. O disjuntor devolve
        // o aborto quando o padrão deixa de ser pontual, e com diagnóstico próprio.
        if (ultimaFalha instanceof RequisicaoRecusadaPeloLlmException) {
            disjuntor.registrarRecusaDefinitiva(lote.idLote());
        }

        String original = lote.linhasOriginais().getFirst();
        log.warn("Lote {}: fala não pôde ser traduzida com confiança após tentativas extras ({}). " +
                "Mantendo o texto original sem tradução: \"{}\"",
            lote.idLote(), ultimaFalha != null ? ultimaFalha.getMessage() : "motivo desconhecido", original);
        uiLogger.log("[ WARN ] Fala mantida sem tradução no Lote " + lote.idLote()
            + " (revise manualmente no cache): " + original);

        // Quando a causa foi marcador corrompido, devolve a TENTATIVA recusada em vez do
        // original: o desmascaramento em TradutorLotesService a reprova pela mesma régua
        // (marcadoresPreservados e desmascarar são equivalentes — se um falha, o outro
        // também) e emite o aviso que classifica a pendência como MARCADORES_CORROMPIDOS.
        // Devolvendo o original, o desmascaramento passava e a fala era contabilizada como
        // ECO, escondendo a causa real. O texto publicado é o mesmo nos dois caminhos: o
        // fallback do desmascaramento mantém o original.
        if (ultimaFalha instanceof MarcadorCorrompidoException marcador && marcador.tentativa() != null) {
            return List.of(marcador.tentativa());
        }
        // A causa vai JUNTO com o original devolvido: sem ela, o portão final só vê "saída igual
        // à entrada" e relata um eco que o modelo não produziu. Ver CAUSAS_DO_LOTE.
        CAUSAS_DO_LOTE.get().put(original, ultimaFalha != null && ultimaFalha.getMessage() != null
            ? ultimaFalha.getMessage() : "motivo desconhecido");
        return List.of(original);
    }

    /**
     * PROPÓSITO DE NEGÓCIO: mesma tradução e a MESMA revalidação, podendo apontar para outro
     * modelo. É de propósito que a régua seja uma só — segunda opinião não é passe livre, e o
     * texto que vier do modelo de recuperação passa pelas mesmas checagens de linhas,
     * marcadores e alucinação que o do principal.
     *
     * <p>COMPORTAMENTO EM CASO DE FALHA: distingue os dois desfechos de {@code sucesso == false}.
     * Recusa DESTA requisição (HTTP 4xx permanente) vira
     * {@link RequisicaoRecusadaPeloLlmException}, que os laços de divisão e retentativa
     * absorvem — a fala fica pendente e o episódio continua. Qualquer outra falha continua
     * sendo {@link TradutorException}, que aborta: com o servidor fora do ar, insistir só gasta
     * tempo e a saída parcial é o desfecho correto.
     *
     * @param ultimaTentativa {@code true} quando não haverá outra chamada para esta fala — aí o
     *        alternativa inventada é aceita em vez de reprovada (ver {@link #conferirAlternativaInventada})
     */
    private List<String> traduzirERevalidarBruto(Lote lote, Double temperaturaOverride,
            String promptSistemaCongelado, String modeloOverride, DisjuntorRecusas disjuntor,
            boolean ultimaTentativa) {
        REPAROS_NA_TENTATIVA.get()[0] = 0;
        TraducaoLote resultado = llmPort.traduzir(lote, temperaturaOverride, promptSistemaCongelado, modeloOverride);

        if (!resultado.sucesso() || resultado.linhasTraduzidas() == null) {
            if (resultado.recusaDaRequisicao()) {
                throw new RequisicaoRecusadaPeloLlmException("Lote " + lote.idLote()
                    + " foi recusado pelo servidor LLM: " + resultado.mensagemErro());
            }
            throw new TradutorException("Lote " + lote.idLote() + " falhou na comunicação: " + resultado.mensagemErro());
        }

        // O servidor ACEITOU um pedido: seja qual for o veredito da validação adiante, ele não
        // está recusando tudo. Zerar aqui — e não só no sucesso do lote — é o que impede o
        // disjuntor de somar recusas de falas distantes entre si ao longo do episódio.
        disjuntor.registrarPedidoAceito();

        List<String> traduzido = juntarPartesDaQuebraIsolada(lote, resultado.linhasTraduzidas());
        if (traduzido.size() != lote.linhasOriginais().size()) {
            throw new DivergenciaLinhasException(
                "Lote " + lote.idLote() + " retornou " + traduzido.size()
                    + " linha(s), esperado " + lote.linhasOriginais().size()
                    + ". Provável alucinação do LLM fundindo ou quebrando falas, o que desalinharia a legenda.");
        }

        // Integridade dos marcadores DENTRO da tentativa: se o LLM perdeu/duplicou/
        // inventou um [[TAGn]], rejeita AQUI (AlucinacaoDetectadaException) para que o
        // retry tente de novo com outra temperatura, em vez de deixar a corrupção só
        // ser detectada no desmascaramento (fora do retry) e a fala virar pendente.
        List<String> mascaradoOriginal = lote.linhasOriginais();
        List<String> saneadas = new ArrayList<>(traduzido.size());
        for (int i = 0; i < traduzido.size(); i++) {
            String linha = traduzido.get(i);
            if (!mascarador.marcadoresPreservados(mascaradoOriginal.get(i), linha)) {
                // O modelo costuma traduzir BEM e apenas não repetir o marcador de controle.
                // Antes de descartar a fala, tenta o reparo determinístico (variante
                // sintática ou marcador só de borda); o reparo é revalidado pela MESMA
                // regra estrita, então nada frouxo passa por aqui.
                String recusada = linha;
                linha = reparadorMarcadores.reparar(mascaradoOriginal.get(i), recusada)
                    .orElseThrow(() -> new MarcadorCorrompidoException(
                        "Marcadores [[TAGn]] corrompidos pelo LLM na tentativa: " + recusada, recusada));
                log.info("Marcador [[TAGn]] reparado no lote {}: \"{}\" -> \"{}\"",
                    lote.idLote(), recusada, linha);
            }
            // O ORIGINAL ENTRA AQUI porque ele existe aqui. Até 2026-09-09 esta chamada era
            // cega, e a auditoria mostrou o custo: "I don't have access to the hangar." era
            // traduzido certo, reprovado como recusa do modelo e reenviado nas TRÊS
            // temperaturas, gastando três chamadas para chegar ao mesmo descarte. Com o texto
            // de partida em mãos, a construção ambígua é absolvida na primeira tentativa.
            // Vai MASCARADO, e isso é indiferente: a âncora procurada é palavra inglesa, e o
            // que a máscara troca são tags ASS, não vocabulário.
            validador.validarFala(linha, mascaradoOriginal.get(i));

            // VALIDAÇÃO DE PAR TAMBÉM DENTRO DA TENTATIVA. Ela existia só no portão final, então
            // uma troca de entidade ou um locutor inventado só era descoberta DEPOIS do laço de
            // temperaturas, quando não havia mais retentativa possível e a fala virava pendente
            // sem nunca ter sido reenviada. Aqui a mesma falha vira outra tentativa.
            //
            // SEGURO COM O TEXTO MASCARADO, e isso foi MEDIDO antes de escrever a linha, não
            // suposto: 114.329 pares do acervo julgados nas duas formas, com e sem máscara,
            // deram 84 reprovações em cada uma e ZERO vereditos divergentes.
            linha = validarParOuReparar(lote, mascaradoOriginal.get(i), linha);
            linha = conferirAlternativaInventada(lote, mascaradoOriginal.get(i), linha, ultimaTentativa);
            saneadas.add(linha);
        }

        return saneadas;
    }

    /** Tags ASS, para validar cada parte pelo texto visível. */
    private static final java.util.regex.Pattern TAG_ASS = java.util.regex.Pattern.compile("\\{[^{}]*}");

    /**
     * PROPÓSITO DE NEGÓCIO: aceita a resposta que o modelo devolveu em linhas separadas EXATAMENTE
     * onde estava a quebra {@code \N} que foi isolada antes do envio. O isolador tira o {@code \N}
     * para o modelo ver a frase inteira; o modelo às vezes devolve uma linha por parte, e a
     * contagem 1 → 2 era recusada como alucinação nas três temperaturas.
     *
     * <h2>O prejuízo medido — 09/10/2026, filme de Sidonia e acervo</h2>
     * Reproduzidas as 304 pendências "1 → 2 linhas" do acervo: juntando só quando
     * {@code linhas == quebras + 1}, ~51 seriam aceitas, e 4 delas seriam português ERRADO numa das
     * partes. Por isso cada parte é validada contra a parte correspondente do original.
     *
     * <h2>Invariantes do domínio</h2>
     * <ul>
     *   <li>Só lote de UMA linha, só com o mapa de partes deste episódio, só quando o número de
     *       linhas devolvidas é exatamente o de partes, e nenhuma parte veio vazia.</li>
     *   <li>Cada parte passa por {@code validarFala} contra a parte do original; uma reprovação
     *       cancela a junção e a divergência de linhas volta a valer, como antes.</li>
     *   <li>As partes juntam por {@code \N}, ONDE O MODELO SEPAROU — que é onde a quebra do original
     *       estava. Juntar por espaço deixaria o {@code reaplicar} pôr a quebra no ponto de
     *       equilíbrio ({@code "Quem é você? Por\Nque está aqui?"}); o {@code reaplicar} só insere
     *       as quebras que ainda faltam.</li>
     *   <li>A7: a junção vai ao log e ao console ({@code [ JUNTADA ]}), com as partes.</li>
     * </ul>
     *
     * <p>COMPORTAMENTO EM CASO DE FALHA: qualquer condição não satisfeita devolve a lista recebida,
     * e a divergência é tratada como sempre foi. Não lança.
     */
    private List<String> juntarPartesDaQuebraIsolada(Lote lote, List<String> linhas) {
        if (lote.linhasOriginais().size() != 1 || linhas.size() < 2) {
            return linhas;
        }
        List<String> partesOriginais = PARTES_ISOLADAS.get().get(lote.linhasOriginais().get(0));
        if (partesOriginais == null || partesOriginais.size() != linhas.size()
            || linhas.stream().anyMatch(l -> l == null || l.isBlank())) {
            return linhas;
        }
        for (int i = 0; i < linhas.size(); i++) {
            try {
                validador.validarFala(linhas.get(i).strip(),
                    TAG_ASS.matcher(partesOriginais.get(i)).replaceAll("").strip());
            } catch (RuntimeException parteReprovada) {
                log.info("Lote {}: resposta em {} linhas NAO juntada, a parte {} reprovou: {}",
                    lote.idLote(), linhas.size(), i + 1, parteReprovada.getMessage());
                return linhas;
            }
        }
        String juntada = String.join("\\N", linhas.stream().map(String::strip).toList());
        String aviso = "lote " + lote.idLote() + ": o modelo separou a fala onde estava a quebra; "
            + linhas.size() + " partes juntadas: \"" + juntada + "\"";
        log.info(aviso);
        uiLogger.log("[ JUNTADA ] " + aviso);
        return List.of(juntada);
    }

    /**
     * PROPÓSITO DE NEGÓCIO: o portão de par dentro da tentativa, com o conserto determinístico ANTES
     * de gastar outra chamada ao modelo. Desde 09/09/2026 a validação de par roda aqui, e com isso o
     * reparo da consolidação final (troca de entidade, 29/07) deixou de alcançar a tradução nova:
     * o modelo que insiste no mesmo defeito esgota as três temperaturas e a fala sai em INGLÊS.
     * Medido na retradução dirigida de 08/10/2026: 26 falas assim ("Kou, fique comigo." para
     * "Uraki, you're with me."; "Sim... é tarde demais" para "No... it's too late"), todas certas
     * no resto.
     *
     * <h2>Invariantes do domínio</h2>
     * <ul>
     *   <li>O reparo só vem de {@link ValidadorTraducaoService#repararPar}, que conserta apenas o
     *       que o portão sabe consertar; o resultado passa de novo pelas DUAS validações
     *       ({@code validarFala} e {@code validarPar}). Se ainda reprovar, a reprovação nova sobe e
     *       vira outra tentativa — o reparo nunca é porta dos fundos.</li>
     *   <li>A7: o candidato do modelo, o texto reparado e o motivo vão para o log e para o console
     *       ({@code [REPARADA]}), e a telemetria conta a resposta rejeitada E a fala recuperada —
     *       o mesmo par de contadores da retentativa.</li>
     * </ul>
     *
     * <p>COMPORTAMENTO EM CASO DE FALHA: sem reparo possível, relança a
     * {@link AlucinacaoDetectadaException} original; reparo que não passa lança a da revalidação.
     */
    private String validarParOuReparar(Lote lote, String original, String linha) {
        try {
            validador.validarPar(original, linha);
            return linha;
        } catch (AlucinacaoDetectadaException reprovada) {
            String reparada = validador.repararPar(original, linha);
            if (reparada == null) {
                throw reprovada;
            }
            validador.validarFala(reparada, original);
            validador.validarPar(original, reparada);
            telemetriaTraducao.registrarRespostaTraducaoRejeitada();
            // "Recuperada" é DESFECHO, não tentativa: a fala reparada aqui ainda pode ser reprovada
            // pela alternativa inventada logo a seguir, e o laço de temperaturas contava de novo
            // (auditoria de 28/10/2026). Quem devolve a tradução com sucesso é quem conta.
            REPAROS_NA_TENTATIVA.get()[0]++;
            String aviso = "Lote " + lote.idLote() + ": fala reparada na tentativa (" + reprovada.getMessage()
                + "): \"" + linha + "\" -> \"" + reparada + "\". Original: " + original;
            log.info(aviso);
            uiLogger.log("[REPARADA] " + aviso);
            return reparada;
        }
    }

    /**
     * PROPÓSITO DE NEGÓCIO: pede outra tentativa ao modelo quando a tradução traz alternativa ou nota
     * que o original não tem — "cansado(a)", "cansada/o", "(com tom sarcástico)", "A pursuer?!
     * (Perseguidor?!)": 95 casos de parêntese e 9 de barra nos caches em 08/10/2026 (ver
     * {@link ValidadorTraducaoService#alternativaInventada}).
     *
     * <h2>Invariantes do domínio</h2>
     * <ul>
     *   <li>Fora da última tentativa, reprova: a próxima temperatura às vezes devolve a forma única.
     *       Medido na 2.1 de 08/10 sobre o acervo, o aya mantém o "(a)" em cerca de dois terços das
     *       falas; e dizer a ele o motivo da recusa não ajudou (40 de 91 limpas com a dica, 46 sem).</li>
     *   <li>Na última, ACEITA e registra: a fala sai com a alternativa, nunca em inglês. Não há
     *       conserto determinístico seguro, e o inglês seria o desfecho pior para quem assiste.</li>
     * </ul>
     *
     * <p>COMPORTAMENTO EM CASO DE FALHA: fora da última tentativa lança
     * {@link AlucinacaoDetectadaException} com a descrição; na última devolve a linha como veio, com
     * aviso {@code [ ALTERNATIVA ]} no log e no console.
     */
    private String conferirAlternativaInventada(Lote lote, String original, String linha, boolean ultimaTentativa) {
        String alternativa = validador.alternativaInventada(original, linha);
        if (alternativa == null) {
            return linha;
        }
        if (!ultimaTentativa) {
            throw new AlucinacaoDetectadaException(alternativa);
        }
        String aviso = "Lote " + lote.idLote() + ": alternativa mantida na última tentativa (" + alternativa + ")";
        log.warn(aviso);
        uiLogger.log("[ ALTERNATIVA ] " + aviso);
        return linha;
    }
}
