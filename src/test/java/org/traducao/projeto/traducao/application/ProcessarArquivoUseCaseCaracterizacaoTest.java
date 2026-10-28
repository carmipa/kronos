package org.traducao.projeto.traducao.application;

import org.traducao.projeto.qualidadeTraducao.application.ReparadorMarcadoresLlm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.traducao.projeto.core.io.DiretorioBaseKronos;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.traducao.projeto.llm.domain.Lote;
import org.traducao.projeto.traducao.domain.ResultadoTraducaoArquivo;
import org.traducao.projeto.traducao.domain.StatusArquivoTraducao;
import org.traducao.projeto.llm.domain.StatusLlm;
import org.traducao.projeto.llm.domain.TraducaoLote;
import org.traducao.projeto.legenda.application.DetectorEfeitoKaraokeService;
import org.traducao.projeto.legenda.application.ProtecaoCamadasMusicaisService;
import org.traducao.projeto.legenda.domain.ArquivoLegendaException;
import org.traducao.projeto.traducao.domain.exceptions.ObraDivergenteDoContextoException;
import org.traducao.projeto.traducao.domain.exceptions.TraducaoParcialException;
import org.traducao.projeto.lore.application.ValidadorCompatibilidadeObraContexto;
import org.traducao.projeto.llm.domain.LlmPort;
import org.traducao.projeto.lore.domain.ProvedorContexto;
import org.traducao.projeto.lore.domain.SnapshotContexto;
import org.traducao.projeto.cachetraducao.infrastructure.CacheTraducaoService;
import org.traducao.projeto.cachetraducao.domain.EntradaCache;
import org.traducao.projeto.cachetraducao.domain.ProvenienciaCache;
import org.traducao.projeto.traducao.infrastructure.config.LlmProperties;
import org.traducao.projeto.traducao.infrastructure.config.TradutorProperties;
import org.traducao.projeto.legenda.domain.PoliticaEstiloMusical;
import org.traducao.projeto.lore.infrastructure.GerenciadorContexto;
import org.traducao.projeto.legenda.infrastructure.EscritorLegendaAss;
import org.traducao.projeto.legenda.infrastructure.EscritorLegendaSrt;
import org.traducao.projeto.legenda.infrastructure.LeitorLegendaAss;
import org.traducao.projeto.legenda.infrastructure.LeitorLegendaSrt;
import org.traducao.projeto.qualidadeTraducao.application.EnforcadorTermosLore;
import org.traducao.projeto.qualidadeTraducao.application.DetectorTraducaoIdenticaService;
import org.traducao.projeto.traducao.infrastructure.adapters.LoreAtivaContextoAdapter;
import org.traducao.projeto.traducao.infrastructure.config.FallbackOnlineProperties;
import org.traducao.projeto.traducao.domain.fallback.ProvedorFallback;
import org.traducao.projeto.traducao.domain.fallback.ResultadoFallback;
import org.traducao.projeto.traducao.domain.fallback.StatusFallback;
import org.traducao.projeto.traducao.domain.ports.FallbackTraducaoMaquinaPort;
import org.traducao.projeto.qualidadeTraducao.application.MascaradorTags;
import org.traducao.projeto.qualidadeTraducao.application.IsoladorQuebraDialogo;
import org.traducao.projeto.qualidadeTraducao.application.NormalizadorAcentosComuns;
import org.traducao.projeto.qualidadeTraducao.application.ProtecaoLegendaAssService;
import org.traducao.projeto.qualidadeTraducao.application.ValidadorTraducaoService;
import org.traducao.projeto.traducao.presentation.ui.ConsoleUILogger;
import org.traducao.projeto.traducao.presentation.ui.PastasExecucao;
import org.traducao.projeto.traducao.domain.TelemetriaTraducao;
import org.traducao.projeto.traducao.domain.ports.TelemetriaTraducaoPort;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.traducao.projeto.qualidadeTraducao.application.LoreAtivaFake;
import org.traducao.projeto.qualidadeTraducao.application.RemovedorItalico;

/**
 * PROPÓSITO DE NEGÓCIO: caracteriza o fluxo ponta-a-ponta de
 * {@link ProcessarArquivoUseCase} (ler → planejar cache → traduzir pendências →
 * validar → reconstruir → publicar → persistir cache → telemetria → resultado)
 * antes da decomposição estrutural da Opção 4, travando o comportamento
 * observável para que a refatoração posterior não possa alterá-lo em silêncio.
 *
 * <p>INVARIANTES DO DOMÍNIO: um episódio ASS/SRT sem pendências publica a saída
 * final {@code _PT-BR} e status {@code CONCLUIDO}; uma segunda execução com a
 * mesma proveniência reaproveita o cache sem chamar o LLM; uma fala que o modelo
 * devolve sem traduzir mantém o texto original, gera status {@code PARCIAL} e
 * publica apenas o artefato {@code .parcial}, preservando a saída final.
 *
 * <p>COMPORTAMENTO EM CASO DE FALHA: qualquer desvio nessas invariantes falha a
 * suíte; o LLM é substituído por um dublê determinístico, de modo que a
 * caracterização não depende de um LM Studio ativo nem de rede.
 */
class ProcessarArquivoUseCaseCaracterizacaoTest {

    @TempDir
    Path raiz;

    private final TelemetriaCaptor telemetriaCaptor = new TelemetriaCaptor();

    // Fallback online controlável pelos testes: por padrão DESLIGADO (pipeline 100%
    // local), preservando o comportamento das caracterizações existentes.
    private boolean fallbackOnlineAtivo = false;

    // Modo "como a produção": agrupamento de frase partida ligado e lote de UMA fala, que é o que o
    // application.yml usa. O padrão do harness (lote de 20, sem agrupamento) é o histórico das
    // caracterizações existentes e continua valendo para elas.
    private int tamanhoLoteTeste = 20;
    private boolean agruparFraseTeste = false;
    private FallbackTraducaoMaquinaPort fallbackPort = portaFallback(original -> Optional.empty());

    /**
     * PROPÓSITO DE NEGÓCIO: adapta a intenção simples destas caracterizações ("traduziu isto" /
     * "não traduziu") ao contrato tipado da porta, que passou a exigir provedor e causa. Mantém
     * os casos legíveis sem enfraquecer o contrato de produção: cada teste continua declarando
     * apenas o desfecho que lhe importa.
     *
     * <p>INVARIANTES DO DOMÍNIO: {@link Optional} presente vira recuperação atribuída ao provedor
     * {@link ProvedorFallback#GOOGLE}; vazio vira recusa por
     * {@link StatusFallback#RESPOSTA_VAZIA} — o mesmo desfecho que o {@code Optional.empty()}
     * representava antes, agora com causa explícita.
     *
     * <p>COMPORTAMENTO EM CASO DE FALHA: repassa o resultado da função sem interpretá-lo.
     */
    private static FallbackTraducaoMaquinaPort portaFallback(Function<String, Optional<String>> traducao) {
        return new FallbackTraducaoMaquinaPort() {
            @Override
            public ResultadoFallback traduzir(String original) {
                return traducao.apply(original)
                    .map(t -> ResultadoFallback.recuperada(t, ProvedorFallback.GOOGLE))
                    .orElseGet(() -> ResultadoFallback.recusada(
                        ProvedorFallback.GOOGLE, StatusFallback.RESPOSTA_VAZIA, "sem resposta do provedor"));
            }

            @Override
            public ProvedorFallback provedor() {
                return ProvedorFallback.GOOGLE;
            }
        };
    }

    /**
     * PROPÓSITO DE NEGÓCIO: captura o último registro de telemetria emitido pelo fluxo, para a
     * caracterização assertar os campos observáveis em vez de descartá-los em um no-op.
     * <p>INVARIANTES DO DOMÍNIO: guarda a referência do último {@link TelemetriaTraducao}; os
     * demais sinais são no-op.
     * <p>COMPORTAMENTO EM CASO DE FALHA: não lança; sem registro, {@code ultima} permanece nula.
     */
    private static final class TelemetriaCaptor implements TelemetriaTraducaoPort {
        TelemetriaTraducao ultima;
        List<org.traducao.projeto.traducao.domain.FalaNaoTraduzida> naoTraduzidas;
        @Override public void registrarTraducao(TelemetriaTraducao t) { ultima = t; }
        @Override public void registrarFalasNaoTraduzidas(Path arquivo, String obra,
                List<org.traducao.projeto.traducao.domain.FalaNaoTraduzida> falas) {
            naoTraduzidas = falas;
        }
        @Override public void registrarAlucinacaoPrevenida() { /* não exercitado */ }
        @Override public void registrarRespostaTraducaoRejeitada() { /* não exercitado */ }
        @Override public void registrarFalhaTraducaoRecuperada() { /* não exercitado */ }
        @Override public void registrarFallbackMantido() { /* não exercitado */ }
    }

    private static final String CABECALHO_ASS = """
        [Script Info]
        ScriptType: v4.00+

        [V4+ Styles]
        Format: Name, Fontname, Fontsize
        Style: Default,Arial,48

        [Events]
        Format: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text
        """;

    /**
     * PROPÓSITO DE NEGÓCIO: dublê determinístico do LLM local para a
     * caracterização — traduz o texto visível para um marcador PT-BR fixo,
     * preservando os marcadores {@code [[TAGn]]} de tags; falas contendo o
     * sentinela {@code KEEPME} voltam sem tradução, simulando a resposta que o
     * pipeline classifica como pendente.
     *
     * <p>INVARIANTES DO DOMÍNIO: preserva a contagem de linhas do lote e conta
     * quantas vezes o modelo foi efetivamente chamado.
     *
     * <p>COMPORTAMENTO EM CASO DE FALHA: nunca falha por rede; as respostas são
     * puramente locais e reprodutíveis.
     */
    private static final class FakeLlmPort implements LlmPort {
        private static final Pattern TOKEN = Pattern.compile("\\[\\[[^\\]]*\\]\\]");
        final AtomicInteger chamadas = new AtomicInteger();
        /** Quantas falas cada chamada levou — é o que mostra se uma corrente foi formada. */
        final List<Integer> tamanhosDeLote = java.util.Collections.synchronizedList(new java.util.ArrayList<>());
        private final boolean interromperNaPrimeira;
        private final boolean comerMarcadores;

        FakeLlmPort() {
            this(false);
        }

        FakeLlmPort(boolean interromperNaPrimeira) {
            this.interromperNaPrimeira = interromperNaPrimeira;
            this.comerMarcadores = false;
        }

        private FakeLlmPort(boolean interromperNaPrimeira, boolean comerMarcadores) {
            this.interromperNaPrimeira = interromperNaPrimeira;
            this.comerMarcadores = comerMarcadores;
        }

        /**
         * PROPÓSITO DE NEGÓCIO: dublê do modo de falha REAL observado em produção — o modelo
         * traduz o texto mas ENGOLE os marcadores {@code [[TAGn]]} das tags ASS. É a causa dos
         * 29 avisos "tags corrompidas pelo LLM" da corrida de 2026-07-22 no 08th MS Team.
         *
         * <p>INVARIANTES DO DOMÍNIO: a tradução do texto visível é a mesma do modo normal; só
         * os marcadores desaparecem. Falas sem tag nenhuma não são afetadas.
         *
         * <p>COMPORTAMENTO EM CASO DE FALHA: determinístico — as três tentativas de
         * {@code ProcessarEpisodioUseCase} recebem a mesma resposta e todas são recusadas.
         */
        static FakeLlmPort queEngoleMarcadores() {
            return new FakeLlmPort(false, true);
        }

        /**
         * PROPÓSITO DE NEGÓCIO: dublê que devolve uma resposta EXATA medida no modelo real, para
         * separar a pergunta da geração ("o modelo acerta?") da pergunta da publicação ("a
         * resposta certa chega ao arquivo, ou o portão a devolve ao inglês?"). A segunda é a
         * fronteira que a A6 cobra, e ela não depende da variância do modelo.
         *
         * <p>INVARIANTES DO DOMÍNIO: a fala cujo original contém {@code gatilho} recebe
         * {@code resposta}; qualquer outra segue o comportamento normal do dublê. Casar por
         * trecho do original é proposital — o texto que chega aqui já vem mascarado com
         * {@code [[TAGn]]} e comparar o texto inteiro tornaria o teste refém do mascarador.
         *
         * <p>COMPORTAMENTO EM CASO DE FALHA: gatilho que não casa com nada faz o dublê agir como
         * o normal, e a asserção do teste é que denuncia — nunca um falso verde silencioso.
         */
        static FakeLlmPort comResposta(String gatilho, String resposta) {
            FakeLlmPort f = new FakeLlmPort(false, false);
            f.respostaFixa = java.util.Map.entry(gatilho, resposta);
            return f;
        }

        private java.util.Map.Entry<String, String> respostaFixa;

        /** Trecho do original cuja fala o dublê devolve PARTIDA em duas linhas. */
        private String gatilhoPartir;
        /** O que o dublê devolve, uma linha por parte, quando parte a fala. */
        private List<String> partesAoPartir = List.of("3, 2, 1,", "vai!");

        /**
         * PROPÓSITO DE NEGÓCIO: dublê do defeito MEDIDO em 25/09/2026 com o aya-expanse-8b: para
         * "3, 2, 1, go!" o modelo devolveu QUATRO linhas em vez de uma. O pipeline descarta a
         * resposta por contagem e mantém o original — e a pergunta do teste é o que o relatório
         * diz sobre isso.
         *
         * <p>INVARIANTES DO DOMÍNIO: só a fala que contém {@code gatilho} sai partida, em todas as
         * tentativas; as demais seguem o comportamento normal.
         *
         * <p>COMPORTAMENTO EM CASO DE FALHA: gatilho que não casa faz o dublê agir como o normal.
         */
        static FakeLlmPort queParteAFala(String gatilho) {
            FakeLlmPort f = new FakeLlmPort(false, false);
            f.gatilhoPartir = gatilho;
            return f;
        }

        /** Como {@link #queParteAFala(String)}, devolvendo estas partes, uma por linha. */
        static FakeLlmPort queParteAFalaEm(String gatilho, String... partes) {
            FakeLlmPort f = queParteAFala(gatilho);
            f.partesAoPartir = List.of(partes);
            return f;
        }

        /** A partir desta chamada o dublê responde como servidor fora do ar. */
        private int caiNaChamada = Integer.MAX_VALUE;

        /**
         * PROPÓSITO DE NEGÓCIO: dublê do LM Studio que CAI no meio do arquivo — responde o
         * primeiro lote e, dali em diante, falha como conexão recusada (não como recusa 4xx).
         *
         * <p>INVARIANTES DO DOMÍNIO: a falha é de indisponibilidade ({@code recusaDaRequisicao ==
         * false}), o caso em que o pipeline aborta o episódio em vez de pular a fala.
         *
         * <p>COMPORTAMENTO EM CASO DE FALHA: determinístico; toda chamada a partir de
         * {@code chamada} falha igual.
         */
        static FakeLlmPort queCaiNaChamada(int chamada) {
            FakeLlmPort f = new FakeLlmPort(false, false);
            f.caiNaChamada = chamada;
            return f;
        }

        @Override
        public TraducaoLote traduzir(Lote lote) {
            return traduzir(lote, null, null);
        }

        @Override
        public TraducaoLote traduzir(Lote lote, Double temperaturaOverride, String promptSistemaCongelado) {
            int chamada = chamadas.incrementAndGet();
            tamanhosDeLote.add(lote.linhasOriginais().size());
            if (chamada >= caiNaChamada) {
                return new TraducaoLote(lote.idLote(), null, false, "Connection refused (dublê: LM Studio caiu)");
            }
            List<String> saida = lote.linhasOriginais().stream()
                .flatMap(l -> gatilhoPartir != null && l.contains(gatilhoPartir)
                    ? partesAoPartir.stream()
                    : java.util.stream.Stream.of(traduzirLinha(l)))
                .toList();
            if (interromperNaPrimeira && chamada == 1) {
                // Reproduz o clique em "Parar" logo após concluir o primeiro lote:
                // marca a interrupção cooperativa, mas devolve a tradução válida do
                // lote — o cancelamento é detectado pelo laço antes do próximo lote.
                Thread.currentThread().interrupt();
            }
            return new TraducaoLote(lote.idLote(), saida, true, null);
        }

        private String traduzirLinha(String mascarada) {
            if (respostaFixa != null && mascarada.contains(respostaFixa.getKey())) {
                return respostaFixa.getValue();
            }
            if (mascarada.contains("KEEPME")) {
                return mascarada; // devolve o original: o pipeline marca como pendente
            }
            Matcher m = TOKEN.matcher(mascarada);
            StringBuilder out = new StringBuilder();
            int ultimo = 0;
            boolean houveTexto = false;
            while (m.find()) {
                if (m.start() > ultimo) {
                    out.append("fala traduzida");
                    houveTexto = true;
                }
                if (!comerMarcadores) {
                    out.append(m.group());
                }
                ultimo = m.end();
            }
            if (ultimo < mascarada.length()) {
                out.append("fala traduzida");
                houveTexto = true;
            }
            // Dublê REALISTA para pergunta (08/10/2026): desde a guarda de pergunta perdida, uma
            // tradução que responde "fala traduzida" a "Where is Captain Bright?" é reprovada como
            // o modelo afirmando em vez de perguntar — que é exatamente o defeito real. O dublê
            // pergunta quando o original pergunta.
            if (houveTexto && TOKEN.matcher(mascarada).replaceAll("").contains("?")
                    && !out.toString().contains("?")) {
                int fim = out.lastIndexOf("fala traduzida") + "fala traduzida".length();
                out.insert(fim, "?");
            }
            return houveTexto ? out.toString() : mascarada;
        }

        @Override
        public StatusLlm verificarDisponibilidade() {
            return null; // não exercitado pelo fluxo de processar()
        }

        @Override
        public Optional<String> revisarConcordancia(String o, String t, List<String> p) {
            return Optional.empty();
        }

        @Override
        public Optional<String> corrigirTraducao(String o, String t, String m) {
            return Optional.empty();
        }
    }

    private static final class ContextoTeste implements ProvedorContexto {
        @Override public String getId() { return "caracterizacao"; }
        @Override public String getNomeExibicao() { return "Caracterizacao"; }
        @Override public String obterPromptSistema() { return "Traduza fielmente para PT-BR."; }
        // Reforço determinístico: a saída fixa do dublê ("fala traduzida") é tratada como
        // forma-ruim de "Legion" — só dispara quando o ORIGINAL contém "Legion", então os
        // demais cenários (originais sem "Legion") permanecem no-op.
        @Override public java.util.Map<String, String> correcoesTerminologia() {
            return java.util.Map.of("fala traduzida", "Legion");
        }
        // Vocabulário de pasta: "AnimeTeste" é a pasta em que todos os cenários escrevem,
        // então a guarda obra×contexto os classifica como CASA e o fluxo caracterizado
        // permanece idêntico.
        @Override public Set<String> apelidosPasta() { return Set.of("AnimeTeste"); }
    }

    /**
     * PROPÓSITO DE NEGÓCIO: segunda obra registrada, para que exista uma pasta que OUTRO
     * contexto reconhece — sem isso não há como provar divergência (que exige prova
     * positiva de reconhecimento por um contexto diferente do ativo).
     * <p>INVARIANTES DO DOMÍNIO: nome de exibição posterior a "Caracterizacao" na ordem
     * case-insensitive, para que o contexto padrão (o primeiro) continue sendo o de teste.
     * <p>COMPORTAMENTO EM CASO DE FALHA: retornos fixos; não lança.
     */
    private static final class ContextoObraAlheia implements ProvedorContexto {
        @Override public String getId() { return "obra_alheia"; }
        @Override public String getNomeExibicao() { return "Obra Alheia"; }
        @Override public String obterPromptSistema() { return "Traduza a obra alheia."; }
        @Override public Set<String> apelidosPasta() { return Set.of("ObraAlheia"); }
    }

    /** Gerenciador da última montagem, para os cenários que precisam trocar o contexto ativo. */
    private GerenciadorContexto gerenciadorMontado;


    /**
     * A6 DO ADITIVO: a conferencia depois da ULTIMA transformacao. Aprovacao intermediaria nao se
     * transfere para um resultado modificado depois dela, e a auditoria de 09/09/2026 nomeou
     * exatamente este buraco: os ensaios "nao comprovaram o fluxo completo ate a gravacao do .ass".
     *
     * <p>Aqui o arquivo em DISCO diz uma coisa e o que a validacao aprovou diz outra. Nenhuma regra
     * de texto veria: as duas frases sao portugues impecavel. So reler o disco ve.
     */
    @Test
    @DisplayName("A6: divergencia entre o disco e o que foi validado e ACUSADA")
    void a6AcusaDivergenciaEntreDiscoEValidado() throws Exception {
        LoggerCapturador logger = new LoggerCapturador();
        ProcessarArquivoUseCase uc = montar(new FakeLlmPort(), logger);

        Path gravado = raiz.resolve("gravado-divergente.ass");
        Files.writeString(gravado, CABECALHO_ASS
            + "Dialogue: 0,0:00:01.00,0:00:02.00,Default,,0,0,0,,Texto que foi para o DISCO.\n",
            StandardCharsets.UTF_8);

        // o cache diz que o validado foi OUTRA coisa -- e alguma etapa depois mexeu
        List<EntradaCache> validado = List.of(new EntradaCache(
            0, "Default", "Text that went to disk.", "Texto que a VALIDACAO aprovou.", "en", "pt-br"));

        uc.conferirArquivoGravado(gravado, false, validado);

        assertTrue(logger.mensagens.stream().anyMatch(l -> l.contains("[ A6 ]") && l.contains("ATENCAO")),
            "a A6 tinha de ACUSAR a divergencia entre disco e validado; console: " + logger.mensagens);
    }

    /**
     * CASO-CONTROLE da anterior, e da forma que a A1 exige: MESMO sinal superficial (a A6 rodando
     * sobre um arquivo relido), desfecho oposto. Sem ele, a conferencia poderia acusar tudo e o
     * teste acima passaria do mesmo jeito.
     */
    @Test
    @DisplayName("A6 CASO-CONTROLE: disco igual ao validado passa em silencio")
    void a6PassaQuandoDiscoBateComValidado() throws Exception {
        LoggerCapturador logger = new LoggerCapturador();
        ProcessarArquivoUseCase uc = montar(new FakeLlmPort(), logger);

        Path gravado = raiz.resolve("gravado-igual.ass");
        Files.writeString(gravado, CABECALHO_ASS
            + "Dialogue: 0,0:00:01.00,0:00:02.00,Default,,0,0,0,,Ele esta chegando.\n",
            StandardCharsets.UTF_8);

        List<EntradaCache> validado = List.of(new EntradaCache(
            0, "Default", "He is coming.", "Ele esta chegando.", "en", "pt-br"));

        uc.conferirArquivoGravado(gravado, false, validado);

        assertTrue(logger.mensagens.stream().anyMatch(l -> l.contains("[ A6 ]") && l.contains("nenhuma divergencia")),
            "arquivo igual ao validado nao pode virar alarme; console: " + logger.mensagens);
        assertTrue(logger.mensagens.stream().noneMatch(l -> l.contains("ATENCAO")),
            "nao pode haver ATENCAO no caso sao; console: " + logger.mensagens);
    }

    /**
     * REGRA 12 dentro da A6: "nao consegui conferir" e "conferi e esta limpo" nao podem produzir a
     * mesma saida. Arquivo ausente tem de sair como NAO VERIFICADO, nunca em silencio.
     */
    @Test
    @DisplayName("A6: arquivo que nao da para reler sai como NAO VERIFICADO")
    void a6ArquivoIlegivelSaiComoNaoVerificado() {
        LoggerCapturador logger = new LoggerCapturador();
        ProcessarArquivoUseCase uc = montar(new FakeLlmPort(), logger);

        List<EntradaCache> validado = List.of(new EntradaCache(
            0, "Default", "He is coming.", "Ele esta chegando.", "en", "pt-br"));

        uc.conferirArquivoGravado(raiz.resolve("nem-existe.ass"), false, validado);

        assertTrue(logger.mensagens.stream().anyMatch(l -> l.contains("NAO VERIFICADO")),
            "releitura impossivel tem de sair NAO VERIFICADO; console: " + logger.mensagens);
    }


    /**
     * MEDIDO NA PRIMEIRA CORRIDA EM PRODUCAO desta guarda (DanMachi S01E01, 2026-09-09): as 14
     * "divergencias" que ela acusou eram as 14 PENDENCIAS do episodio. Para fala pendente o cache
     * grava traduzido VAZIO e o arquivo preserva o ORIGINAL -- dois estados legitimos que nao
     * batem entre si por desenho. Confundi-los faz a guarda gritar em TODO arquivo parcial, que e
     * o alarme falso que a regra 23 chama de pior que guarda nenhuma.
     */
    @Test
    @DisplayName("A6: pendencia com o original preservado NAO e divergencia")
    void a6PendenciaNaoEhDivergencia() throws Exception {
        LoggerCapturador logger = new LoggerCapturador();
        ProcessarArquivoUseCase uc = montar(new FakeLlmPort(), logger);

        Path gravado = raiz.resolve("gravado-com-pendencia.ass");
        Files.writeString(gravado, CABECALHO_ASS
            + "Dialogue: 0,0:00:01.00,0:00:02.00,Default,,0,0,0,,Are you even listening?\n",
            StandardCharsets.UTF_8);

        // o cache grava vazio para a pendente; o disco preserva o original
        List<EntradaCache> validado = List.of(new EntradaCache(
            0, "Default", "Are you even listening?", "", "en", "pt-br"));

        uc.conferirArquivoGravado(gravado, false, validado);

        assertTrue(logger.mensagens.stream().anyMatch(l -> l.contains("[ A6 ]") && l.contains("nenhuma divergencia")),
            "pendencia com original preservado nao pode virar ATENCAO; console: " + logger.mensagens);
        assertTrue(logger.mensagens.stream().anyMatch(l -> l.contains("pendente(s) com o original preservado")),
            "o placar tem de DECLARAR a pendencia, nao apenas calar sobre ela; console: " + logger.mensagens);
    }

    /**
     * CONTRA-CASO: cache vazio mas o disco NAO tem o original -- ai a divergencia e real, porque
     * alguma etapa gravou outra coisa.
     */
    @Test
    @DisplayName("A6 CASO-CONTROLE: cache vazio com disco DIFERENTE do original ainda acusa")
    void a6CacheVazioComDiscoDiferenteAindaAcusa() throws Exception {
        LoggerCapturador logger = new LoggerCapturador();
        ProcessarArquivoUseCase uc = montar(new FakeLlmPort(), logger);

        Path gravado = raiz.resolve("gravado-vazio-diferente.ass");
        Files.writeString(gravado, CABECALHO_ASS
            + "Dialogue: 0,0:00:01.00,0:00:02.00,Default,,0,0,0,,Outra coisa qualquer.\n",
            StandardCharsets.UTF_8);

        List<EntradaCache> validado = List.of(new EntradaCache(
            0, "Default", "Are you even listening?", "", "en", "pt-br"));

        uc.conferirArquivoGravado(gravado, false, validado);

        assertTrue(logger.mensagens.stream().anyMatch(l -> l.contains("[ A6 ]") && l.contains("ATENCAO")),
            "cache vazio com disco diferente do original E divergencia; console: " + logger.mensagens);
    }

    /**
     * MEDIDO EM 25/09/2026, na primeira execucao da auditoria da 2.1 (0080 E01+E02, aya): as 4
     * "divergencias" que a A6 acusou eram TODAS fabricadas pelo proprio pipeline. O cache guarda a
     * traducao validada com o italico ("{\i1}Entendido.") e o arquivo recebe a mesma fala depois do
     * RemovedorItalico ("Entendido."), que e a regra de 22/08. A A6 comparava os dois como se
     * fossem a mesma coisa e acendia ATENCAO em toda execucao com italico.
     */
    @Test
    @DisplayName("A6: italico removido na gravacao NAO e divergencia")
    void a6ItalicoRemovidoNaGravacaoNaoEhDivergencia() throws Exception {
        LoggerCapturador logger = new LoggerCapturador();
        ProcessarArquivoUseCase uc = montar(new FakeLlmPort(), logger);

        Path gravado = raiz.resolve("gravado-sem-italico.ass");
        Files.writeString(gravado, CABECALHO_ASS
            + "Dialogue: 0,0:00:01.00,0:00:02.00,Default,,0,0,0,,Entendido.\n",
            StandardCharsets.UTF_8);
        List<EntradaCache> validado = List.of(new EntradaCache(
            0, "Default", "{\\i1}Roger.", "{\\i1}Entendido.", "en", "pt-br"));

        uc.conferirArquivoGravado(gravado, false, validado);

        assertTrue(logger.mensagens.stream().noneMatch(l -> l.contains("ATENCAO")),
            "italico removido pela regra da saida nao e divergencia; console: " + logger.mensagens);
        assertTrue(logger.mensagens.stream().anyMatch(l -> l.contains("nenhuma divergencia")),
            "o placar limpo tem de ser DITO; console: " + logger.mensagens);
    }

    /**
     * CASO-CONTROLE DE FRONTEIRA (A1) da anterior: MESMO sinal superficial -- validado com italico e
     * disco sem italico --, mas o texto visivel mudou. Tem de continuar acusando, senao o conserto
     * teria cegado a guarda para tudo que passa por uma fala italica.
     */
    @Test
    @DisplayName("A6 CASO-CONTROLE: italico removido E texto trocado continua acusado")
    void a6ItalicoRemovidoComTextoTrocadoContinuaAcusado() throws Exception {
        LoggerCapturador logger = new LoggerCapturador();
        ProcessarArquivoUseCase uc = montar(new FakeLlmPort(), logger);

        Path gravado = raiz.resolve("gravado-sem-italico-trocado.ass");
        Files.writeString(gravado, CABECALHO_ASS
            + "Dialogue: 0,0:00:01.00,0:00:02.00,Default,,0,0,0,,Negativo.\n",
            StandardCharsets.UTF_8);
        List<EntradaCache> validado = List.of(new EntradaCache(
            0, "Default", "{\\i1}Roger.", "{\\i1}Entendido.", "en", "pt-br"));

        uc.conferirArquivoGravado(gravado, false, validado);

        assertTrue(logger.mensagens.stream().anyMatch(l -> l.contains("[ A6 ]") && l.contains("ATENCAO")),
            "texto visivel diferente do validado e divergencia real; console: " + logger.mensagens);
    }

    /**
     * A pendencia cujo ORIGINAL tinha italico: o cache grava vazio e o arquivo publica o original
     * sem o italico ("{\i1}Get real!" -> "Get real!"). Medido no 0080 E01 em 25/09/2026 -- era a
     * quarta "divergencia" falsa.
     */
    @Test
    @DisplayName("A6: pendencia de original italico publicada sem italico NAO e divergencia")
    void a6PendenciaDeOriginalItalicoNaoEhDivergencia() throws Exception {
        LoggerCapturador logger = new LoggerCapturador();
        ProcessarArquivoUseCase uc = montar(new FakeLlmPort(), logger);

        Path gravado = raiz.resolve("gravado-pendencia-italico.ass");
        Files.writeString(gravado, CABECALHO_ASS
            + "Dialogue: 0,0:00:01.00,0:00:02.00,Default,,0,0,0,,Get real!\n",
            StandardCharsets.UTF_8);
        List<EntradaCache> validado = List.of(new EntradaCache(
            0, "Default", "{\\i1}Get real!", "", "en", "pt-br"));

        uc.conferirArquivoGravado(gravado, false, validado);

        assertTrue(logger.mensagens.stream().noneMatch(l -> l.contains("ATENCAO")),
            "pendencia com o original preservado nao e divergencia; console: " + logger.mensagens);
        assertTrue(logger.mensagens.stream().anyMatch(l -> l.contains("pendente(s) com o original preservado")),
            "a pendencia tem de ser DECLARADA no placar; console: " + logger.mensagens);
    }

    /**
     * Fala mantida porque a FONTE ja estava em portugues nunca foi ao LLM nem passou pela validacao
     * -- entra no arquivo como veio. A A6 a reprovava como "modelo devolveu o texto original", uma
     * frase falsa duas vezes: nao houve modelo e nao houve devolucao. Medido no controle da
     * auditoria de 25/09/2026.
     */
    @Test
    @DisplayName("A6: fala mantida por fonte ja em portugues NAO reprova como eco do modelo")
    void a6FonteJaEmPortuguesNaoReprovaComoEco() throws Exception {
        LoggerCapturador logger = new LoggerCapturador();
        ProcessarArquivoUseCase uc = montar(new FakeLlmPort(), logger);

        String fonteEmPortugues = "Você não vai sair daqui agora.";
        Path gravado = raiz.resolve("gravado-fonte-pt.ass");
        Files.writeString(gravado, CABECALHO_ASS
            + "Dialogue: 0,0:00:01.00,0:00:02.00,Default,,0,0,0,," + fonteEmPortugues + "\n",
            StandardCharsets.UTF_8);
        List<EntradaCache> validado = List.of(new EntradaCache(
            0, "Default", fonteEmPortugues, fonteEmPortugues, "en", "pt-br"));

        uc.conferirArquivoGravado(gravado, false, validado, Set.of(fonteEmPortugues));

        assertTrue(logger.mensagens.stream().noneMatch(l -> l.contains("ATENCAO")),
            "fala mantida por fonte ja no alvo nao e reprovacao; console: " + logger.mensagens);
        assertTrue(logger.mensagens.stream().anyMatch(l -> l.contains("fonte ja no idioma-alvo")
                && l.contains("NAO VERIFICADA") && l.contains(fonteEmPortugues)),
            "a manutencao tem de ser DECLARADA como nao verificada, com o exemplo; console: "
                + logger.mensagens);
    }

    /**
     * CASO-CONTROLE (A1) da anterior: a MESMA fala identica ao original, sem ter sido mantida por
     * fonte ja em portugues, continua reprovando -- e a traducao que voltou igual, que o portao
     * canonico existe para pegar.
     */
    @Test
    @DisplayName("A6 CASO-CONTROLE: fala identica ao original que NAO veio da fonte-PT continua reprovada")
    void a6FalaIdenticaForaDaFontePtContinuaReprovada() throws Exception {
        LoggerCapturador logger = new LoggerCapturador();
        ProcessarArquivoUseCase uc = montar(new FakeLlmPort(), logger);

        String ingles = "Are you even listening to me?";
        Path gravado = raiz.resolve("gravado-eco.ass");
        Files.writeString(gravado, CABECALHO_ASS
            + "Dialogue: 0,0:00:01.00,0:00:02.00,Default,,0,0,0,," + ingles + "\n",
            StandardCharsets.UTF_8);
        List<EntradaCache> validado = List.of(new EntradaCache(
            0, "Default", ingles, ingles, "en", "pt-br"));

        uc.conferirArquivoGravado(gravado, false, validado, Set.of());

        assertTrue(logger.mensagens.stream().anyMatch(l -> l.contains("[ A6 ]") && l.contains("ATENCAO")),
            "eco do ingles gravado como traducao tem de reprovar; console: " + logger.mensagens);
    }

    /**
     * A7 — CAUSA E AUTORIA PRESERVADAS. Medido na auditoria de 25/09/2026: o aya devolveu quatro
     * linhas para "3, 2, 1, go!", o pipeline descartou a resposta e manteve o original, e o relatório
     * dizia "modelo devolveu o texto original sem tradução", com a pendência no balde ECO. O sistema
     * atribuía ao modelo uma decisão que foi dele mesmo.
     */
    @Test
    @DisplayName("A7: fala mantida por contagem de linhas divergente NAO e relatada como eco do modelo")
    void falaMantidaPorContagemDivergenteNaoEhRelatadaComoEco() throws Exception {
        Path entrada = escreverAss("ep.ass", "Hello there", "3, 2, 1, go!");

        ResultadoTraducaoArquivo r = montar(FakeLlmPort.queParteAFala("go!"))
            .processar(entrada, false, gerenciadorMontado.snapshotAtivo());

        assertEquals(StatusArquivoTraducao.PARCIAL, r.status());
        TelemetriaTraducao tel = telemetriaCaptor.ultima;
        String aviso = tel.errosOcorridos().stream().filter(a -> a.contains("3, 2, 1, go!"))
            .findFirst().orElseThrow(() -> new AssertionError("sem aviso da pendencia: " + tel.errosOcorridos()));
        assertTrue(aviso.contains("linha(s), esperado 1") && aviso.contains("o sistema manteve o original"),
            "o aviso tem de dizer a causa REAL e quem manteve o original; aviso: " + aviso);
        assertFalse(aviso.contains("modelo devolveu o texto original"),
            "o modelo NAO devolveu o original — respondeu duas linhas; aviso: " + aviso);
        assertTrue(tel.pendenciasPorCausa().stream()
                .anyMatch(p -> p.causaRaiz().equals("ESTRUTURA_DIVERGENTE")),
            "o KPI tem de cair em ESTRUTURA_DIVERGENTE; KPI: " + tel.pendenciasPorCausa());
        assertTrue(tel.pendenciasPorCausa().stream().noneMatch(p -> p.causaRaiz().equals("ECO")),
            "nao houve eco; KPI: " + tel.pendenciasPorCausa());
    }

    /**
     * 09/10/2026, filme de Sidonia: o isolador tira o {@code \N} para o modelo ver a frase inteira,
     * o modelo devolve uma linha por parte, e a fala era recusada nas três temperaturas — 304
     * pendências "1 → 2 linhas" no acervo, ~51 recuperáveis. Agora as partes juntam com {@code \N}
     * onde o modelo separou, e cada parte é validada contra a parte do original.
     */
    @Test
    @DisplayName("fala com \\N isolado que volta em duas linhas e JUNTADA onde o modelo separou")
    void falaComQuebraIsoladaEmDuasLinhasEhJuntada() throws Exception {
        Path entrada = escreverAss("ep.ass", "Who are you?\\NWhy are you here?");

        ResultadoTraducaoArquivo r = montar(FakeLlmPort.queParteAFalaEm("Why", "Quem é você?", "Por que está aqui?"))
            .processar(entrada, false, gerenciadorMontado.snapshotAtivo());

        assertEquals(StatusArquivoTraducao.CONCLUIDO, r.status());
        String conteudo = Files.readString(raiz.resolve("saida").resolve("ep_PT-BR.ass"), StandardCharsets.UTF_8);
        assertTrue(conteudo.contains(",Quem é você?\\NPor que está aqui?"),
            "a quebra fica onde o modelo separou, uma so: " + conteudo);
    }

    /** A1: o MESMO sinal (duas linhas onde se esperava uma), mas uma das partes é inglês. */
    @Test
    @DisplayName("CONTROLE: parte em ingles cancela a juncao e a fala continua pendente")
    void parteEmInglesCancelaAJuncao() throws Exception {
        Path entrada = escreverAss("ep.ass", "Hello there", "Who are you?\\NWhy are you here?");

        ResultadoTraducaoArquivo r = montar(FakeLlmPort.queParteAFalaEm("Why", "Quem é você?", "Why are you here?"))
            .processar(entrada, false, gerenciadorMontado.snapshotAtivo());

        assertEquals(StatusArquivoTraducao.PARCIAL, r.status(), "a parte inglesa nao pode ser publicada");
    }

    /**
     * Achado D8 da auditoria da 2.1: a categoria da pendência vinha do estilo do PRIMEIRO evento
     * com aquele texto — e esse evento podia ser um Comment, que nunca é traduzido. O letreiro
     * pendente caía no balde DIALOGO do KPI.
     */
    @Test
    @DisplayName("D8: categoria da pendencia vem do evento TRADUZIVEL, nao do Comment de mesmo texto")
    void categoriaDaPendenciaIgnoraOCommentDeMesmoTexto() throws Exception {
        Path entrada = raiz.resolve("ep.ass");
        Files.writeString(entrada, CABECALHO_ASS
            + "Comment: 0,0:00:00.50,0:00:01.00,Default,,0,0,0,,KEEPME sign\n"
            + "Dialogue: 0,0:00:01.00,0:00:02.00,Sign,,0,0,0,,KEEPME sign\n", StandardCharsets.UTF_8);

        montar(new FakeLlmPort()).processar(entrada, false, gerenciadorMontado.snapshotAtivo());

        TelemetriaTraducao tel = telemetriaCaptor.ultima;
        assertEquals(List.of("LETREIRO"), tel.pendenciasPorCausa().stream().map(p -> p.categoria()).toList(),
            "KPI: " + tel.pendenciasPorCausa());
    }

    /** CASO-CONTROLE (A1) do anterior: o Comment em estilo de letreiro não empresta a categoria. */
    @Test
    @DisplayName("D8 CASO-CONTROLE: Comment em estilo Sign nao faz o dialogo virar letreiro")
    void commentEmEstiloDeLetreiroNaoMudaODialogo() throws Exception {
        Path entrada = raiz.resolve("ep.ass");
        Files.writeString(entrada, CABECALHO_ASS
            + "Comment: 0,0:00:00.50,0:00:01.00,Sign,,0,0,0,,KEEPME talk\n"
            + "Dialogue: 0,0:00:01.00,0:00:02.00,Default,,0,0,0,,KEEPME talk\n", StandardCharsets.UTF_8);

        montar(new FakeLlmPort()).processar(entrada, false, gerenciadorMontado.snapshotAtivo());

        TelemetriaTraducao tel = telemetriaCaptor.ultima;
        assertEquals(List.of("DIALOGO"), tel.pendenciasPorCausa().stream().map(p -> p.categoria()).toList(),
            "KPI: " + tel.pendenciasPorCausa());
    }

    /**
     * CASO-CONTROLE DE FRONTEIRA (A1) da anterior: MESMO desfecho visível (a fala fica em inglês e
     * pendente), mas aqui o modelo REALMENTE devolveu o original. Tem de continuar sendo eco —
     * senão o conserto teria apenas trocado um rótulo errado por outro.
     */
    @Test
    @DisplayName("A7 CASO-CONTROLE: eco de verdade do modelo continua relatado como eco")
    void ecoDeVerdadeContinuaRelatadoComoEco() throws Exception {
        Path entrada = escreverAss("ep.ass", "Hello there", "KEEPME stays");

        montar(new FakeLlmPort()).processar(entrada, false, gerenciadorMontado.snapshotAtivo());

        TelemetriaTraducao tel = telemetriaCaptor.ultima;
        String aviso = tel.errosOcorridos().stream().filter(a -> a.contains("KEEPME stays"))
            .findFirst().orElseThrow(() -> new AssertionError("sem aviso da pendencia: " + tel.errosOcorridos()));
        assertTrue(aviso.contains("modelo devolveu o texto original"),
            "eco verdadeiro tem de continuar dito como eco; aviso: " + aviso);
        assertTrue(tel.pendenciasPorCausa().stream().anyMatch(p -> p.causaRaiz().equals("ECO")),
            "o KPI do eco verdadeiro e ECO; KPI: " + tel.pendenciasPorCausa());
    }

    /**
     * O carimbo do cabeçalho conta FALAS. Medido em 25/09/2026: o 0080 E01 dizia "407 fala(s) na
     * origem" com 400 eventos, e o 86 E01 tinha 100 linhas a mais — linhas vazias, Comment e a
     * seção [Aegisub Extradata] inteira, que o leitor guarda como evento para devolver o arquivo
     * intacto. Todas saíam como "preservadas por regra do pipeline" no carimbo e no dataset.
     */
    @Test
    @DisplayName("carimbo e dataset contam so FALAS, nao linhas vazias, Comment ou Extradata")
    void carimboEDatasetContamSoFalas() throws Exception {
        Path pasta = Files.createDirectories(raiz.resolve("AnimeTeste").resolve("legendas_originais"));
        Path entrada = pasta.resolve("ep.ass");
        Files.writeString(entrada, CABECALHO_ASS
            + "Dialogue: 0,0:00:01.00,0:00:02.00,Default,,0,0,0,,Hello there\n"
            + "\n"
            + "Comment: 0,0:00:03.00,0:00:04.00,Default,,0,0,0,,nota do typesetter\n"
            + "Dialogue: 0,0:00:05.00,0:00:06.00,Default,,0,0,0,,How are you\n"
            + "\n"
            + "[Aegisub Extradata]\n"
            + "Data: 1,_aegi_perspective_ambient_plane,e#56\n"
            + "Data: 2,_aegi_perspective_ambient_plane,e#57\n",
            StandardCharsets.UTF_8);

        montar(new FakeLlmPort()).processar(entrada, false, gerenciadorMontado.snapshotAtivo());

        String gravado = Files.readString(raiz.resolve("saida").resolve("ep_PT-BR.ass"), StandardCharsets.UTF_8);
        assertTrue(gravado.contains("2 fala(s) na origem, 2 traduzivel(is), 0 preservada(s)"),
            "o carimbo tem de contar as 2 falas, nao as linhas do arquivo; cabecalho:\n"
                + gravado.lines().limit(4).collect(java.util.stream.Collectors.joining("\n")));
        assertTrue(gravado.contains("Data: 2,_aegi_perspective_ambient_plane,e#57"),
            "a secao Extradata continua no arquivo, intacta");
        assertNotNull(telemetriaCaptor.naoTraduzidas,
            "o dataset de falas nao traduzidas nem foi entregue a porta — o teste abaixo seria vazio");
        assertTrue(telemetriaCaptor.naoTraduzidas.isEmpty(),
            "nenhuma fala foi preservada; linha vazia/Comment/Extradata nao sao falas: "
                + telemetriaCaptor.naoTraduzidas);
    }

    /**
     * A tela da 2.1 promete: "uma queda do LM Studio no meio deixa no cache o que já foi traduzido
     * — a próxima execução recomeça de onde parou". Até 25/09/2026 a promessa era sustentada só
     * por leitura de código; este teste a sustenta por execução.
     */
    @Test
    @DisplayName("LM Studio que cai no meio do arquivo deixa o cache do que ja foi traduzido, e a retomada so faz o resto")
    void quedaDoLlmNoMeioDeixaCacheERetomadaSoFazOResto() throws Exception {
        String[] falas = new String[21];
        for (int i = 0; i < falas.length; i++) {
            falas[i] = "Line " + (char) ('A' + i / 26) + (char) ('A' + i % 26);
        }
        Path entrada = escreverAss("ep.ass", falas);

        FakeLlmPort caiu = FakeLlmPort.queCaiNaChamada(2);
        assertThrows(TraducaoParcialException.class,
            () -> montar(caiu, new ConsoleUILoggerSilencioso()).processar(entrada, false, gerenciadorMontado.snapshotAtivo()),
            "com o servidor fora do ar no 2o lote o arquivo nao pode ser publicado");
        assertTrue(caiu.chamadas.get() >= 2, "o dublê tem de ter chegado a cair — senao nada foi medido");
        assertFalse(Files.exists(raiz.resolve("saida").resolve("ep_PT-BR.ass")),
            "nenhuma saida final com o episodio pela metade");

        FakeLlmPort retomada = new FakeLlmPort();
        ResultadoTraducaoArquivo r = montar(retomada, new ConsoleUILoggerSilencioso())
            .processar(entrada, false, gerenciadorMontado.snapshotAtivo());

        assertEquals(StatusArquivoTraducao.CONCLUIDO, r.status());
        assertEquals(20, telemetriaCaptor.ultima.falasDoCache(),
            "as 20 falas do lote que o LLM respondeu antes de cair tem de vir do CACHE");
        assertEquals(1, retomada.chamadas.get(), "a retomada so chama o LLM para o que faltava");
    }

    /**
     * A marca "interrompida pelo usuário" tem de chegar ao chamador mesmo depois de o flag da thread
     * ser consumido no caminho. Aqui o logger é o NORMAL, com a barra de progresso que consome a
     * interrupção — o mesmo que fez o "Sair" real de 25/09/2026 chegar ao controller como FALHOU.
     */
    @Test
    @DisplayName("parada pedida chega ao chamador marcada, mesmo com o flag da thread consumido")
    void paradaPedidaChegaMarcadaMesmoComFlagConsumido() throws Exception {
        String[] falas = new String[21];
        for (int i = 0; i < falas.length; i++) {
            falas[i] = "Line " + (char) ('A' + i / 26) + (char) ('A' + i % 26);
        }
        Path entrada = escreverAss("ep.ass", falas);
        try {
            TraducaoParcialException ex = assertThrows(TraducaoParcialException.class,
                () -> montar(new FakeLlmPort(true)).processar(entrada, false, gerenciadorMontado.snapshotAtivo()));
            assertTrue(ex.interrompidaPeloUsuario(),
                "a parada pedida tem de chegar MARCADA — o flag da thread nao e confiavel ate aqui");
        } finally {
            Thread.interrupted();
        }
    }

    private static ConsoleUILogger espiaoDe(List<String> ditas) {
        return new ConsoleUILogger() {
            @Override
            public synchronized void log(String mensagem) {
                ditas.add(mensagem);
            }
        };
    }

    private Path finalPtBr() {
        return raiz.resolve("saida").resolve("ep_PT-BR.ass");
    }

    private void editarNoArquivo(String de, String para) throws IOException {
        String publicado = Files.readString(finalPtBr(), StandardCharsets.UTF_8);
        assertTrue(publicado.contains(de), "o trecho a editar tem de existir no arquivo: " + de);
        Files.writeString(finalPtBr(), publicado.replaceFirst(java.util.regex.Pattern.quote(de),
            java.util.regex.Matcher.quoteReplacement(para)), StandardCharsets.UTF_8);
    }

    /** Corrige no cache a tradução de UM original, como a Correção de Cache (2.3) faz. */
    private void corrigirNoCache(String original, String novaTraducao) throws IOException {
        Path cache;
        try (var s = Files.walk(raiz)) {
            cache = s.filter(p -> p.getFileName().toString().equals("ep.cache.json")).findFirst()
                .orElseThrow(() -> new AssertionError("cache do episodio nao encontrado sob " + raiz));
        }
        ObjectMapper json = new ObjectMapper();
        JsonNode raizJson = json.readTree(cache.toFile());
        int trocadas = 0;
        java.util.Deque<JsonNode> pilha = new java.util.ArrayDeque<>(List.of(raizJson));
        while (!pilha.isEmpty()) {
            JsonNode no = pilha.pop();
            if (no.isObject() && original.equals(no.path("original").asText(null))) {
                ((com.fasterxml.jackson.databind.node.ObjectNode) no).put("traduzido", novaTraducao);
                trocadas++;
            }
            no.forEach(pilha::push);
        }
        assertTrue(trocadas > 0, "nenhuma entrada do cache com original \"" + original + "\"");
        json.writeValue(cache.toFile(), raizJson);
    }

    private void apagarRegistroDaPublicacao() throws IOException {
        try (var s = Files.walk(raiz)) {
            List<Path> bases = s.filter(p -> p.getParent() != null
                && p.getParent().getFileName().toString().equals(".publicado")).toList();
            assertFalse(bases.isEmpty(), "a publicacao anterior tinha de ter deixado o registro");
            for (Path b : bases) {
                Files.delete(b);
            }
        }
    }

    /**
     * MEDIDO NA AUDITORIA DE 25/09/2026: com a proteção LIGADA, reexecutar a 2.1 regravava o
     * _PT-BR a partir do cache e desfazia a correção feita direto no .ass (3.2, 3.3, edição
     * manual — nenhuma escreve no cache). Com a mesclagem de três vias a correção FICA.
     */
    @Test
    @DisplayName("mesclagem: correcao feita direto no arquivo sobrevive a reexecucao")
    void correcaoFeitaNoArquivoSobreviveAReexecucao() throws Exception {
        Path entrada = escreverAss("ep.ass", "Hello there", "How are you");
        montar(new FakeLlmPort()).processar(entrada, false, gerenciadorMontado.snapshotAtivo());
        editarNoArquivo(",,fala traduzida", ",,fala REVISADA A MAO");

        List<String> ditas = new java.util.ArrayList<>();
        montar(new FakeLlmPort(), espiaoDe(ditas)).processar(entrada, false, gerenciadorMontado.snapshotAtivo());

        assertTrue(Files.readString(finalPtBr(), StandardCharsets.UTF_8).contains("fala REVISADA A MAO"),
            "a correcao feita no arquivo tem de continuar no arquivo; console:\n" + String.join("\n", ditas));
        assertTrue(ditas.stream().anyMatch(l -> l.startsWith("[ MESCLA ]") && l.contains("1 fala(s) corrigida(s)")),
            "a preservacao tem de ser DITA; console:\n" + String.join("\n", ditas));
        // Só o arquivo mudou: é preservação, não conflito. Chamar de conflito ensinaria o operador
        // a desconfiar de toda correção feita pela 3.2/3.3.
        assertTrue(ditas.stream().noneMatch(l -> l.contains("CONFLITO")),
            "edicao so no arquivo NAO e conflito; console:\n" + String.join("\n", ditas));
        assertTrue(ditas.stream().noneMatch(l -> l.contains("ATENCAO")),
            "a fala preservada nao e divergencia para a A6; console:\n" + String.join("\n", ditas));
    }

    /**
     * O OUTRO fluxo legítimo, o da 2.3: o cache foi corrigido e a legenda não foi mexida. A
     * reexecução tem de PUBLICAR a correção do cache — a mesclagem não pode confundi-la com edição.
     */
    @Test
    @DisplayName("mesclagem: correcao feita no cache chega ao arquivo (fluxo da 2.3)")
    void correcaoFeitaNoCacheChegaAoArquivo() throws Exception {
        Path entrada = escreverAss("ep.ass", "Hello there", "How are you");
        montar(new FakeLlmPort()).processar(entrada, false, gerenciadorMontado.snapshotAtivo());
        corrigirNoCache("Hello there", "Olá, tudo certo por aí");

        List<String> ditas = new java.util.ArrayList<>();
        montar(new FakeLlmPort(), espiaoDe(ditas)).processar(entrada, false, gerenciadorMontado.snapshotAtivo());

        assertTrue(Files.readString(finalPtBr(), StandardCharsets.UTF_8).contains("Olá, tudo certo por aí"),
            "a correcao do cache tem de chegar ao arquivo; console:\n" + String.join("\n", ditas));
        assertTrue(ditas.stream().anyMatch(l -> l.startsWith("[ MESCLA ]") && l.contains("1 atualizada(s) pelo cache")),
            "a atualizacao tem de ser DITA; console:\n" + String.join("\n", ditas));
        assertTrue(ditas.stream().anyMatch(l -> l.contains("[ BACKUP ]")),
            "substituir o publicado exige backup; console:\n" + String.join("\n", ditas));
    }

    /** Mudou no arquivo E no cache: mantém a do arquivo e diz que houve conflito, com exemplo. */
    @Test
    @DisplayName("mesclagem: conflito nos dois lados mantem o arquivo e avisa")
    void conflitoNosDoisLadosMantemOArquivoEAvisa() throws Exception {
        Path entrada = escreverAss("ep.ass", "Hello there", "How are you");
        montar(new FakeLlmPort()).processar(entrada, false, gerenciadorMontado.snapshotAtivo());
        editarNoArquivo(",,fala traduzida", ",,fala REVISADA A MAO");
        corrigirNoCache("Hello there", "Olá, tudo certo por aí");

        List<String> ditas = new java.util.ArrayList<>();
        montar(new FakeLlmPort(), espiaoDe(ditas)).processar(entrada, false, gerenciadorMontado.snapshotAtivo());

        String gravado = Files.readString(finalPtBr(), StandardCharsets.UTF_8);
        assertTrue(gravado.contains("fala REVISADA A MAO") && !gravado.contains("Olá, tudo certo por aí"),
            "no conflito vale a correcao do arquivo; arquivo:\n" + gravado);
        assertTrue(ditas.stream().anyMatch(l -> l.startsWith("[ MESCLA ]") && l.contains("CONFLITO")
                && l.contains("Olá, tudo certo por aí")),
            "o conflito tem de ser DITO com a versao do cache; console:\n" + String.join("\n", ditas));
    }

    /**
     * SEM o registro da publicação anterior (arquivos publicados antes desta versão) não há como
     * separar os dois casos: substitui pelo cache, com backup obrigatório e o aviso dos dois casos.
     * É o comportamento que a auditoria mediu como a rede mínima.
     */
    @Test
    @DisplayName("sem registro da publicacao anterior: substitui com backup e avisa")
    void semRegistroDaPublicacaoSubstituiComBackupEAvisa() throws Exception {
        Path entrada = escreverAss("ep.ass", "Hello there", "How are you");
        montar(new FakeLlmPort()).processar(entrada, false, gerenciadorMontado.snapshotAtivo());
        editarNoArquivo(",,fala traduzida", ",,fala REVISADA A MAO");
        apagarRegistroDaPublicacao();

        List<String> ditas = new java.util.ArrayList<>();
        montar(new FakeLlmPort(), espiaoDe(ditas)).processar(entrada, false, gerenciadorMontado.snapshotAtivo());

        assertTrue(ditas.stream().anyMatch(l -> l.startsWith("[ ATENÇÃO ]") && l.contains("DIFERIA")),
            "sem base, a substituicao tem de ser DITA; console:\n" + String.join("\n", ditas));
        String linhaBackup = ditas.stream().filter(l -> l.contains("[ BACKUP ]")).findFirst()
            .orElseThrow(() -> new AssertionError("sem backup da versao anterior; console:\n" + String.join("\n", ditas)));
        Path backup = Path.of(linhaBackup.substring(linhaBackup.indexOf("preservada em: ") + "preservada em: ".length()).trim());
        assertTrue(Files.readString(backup, StandardCharsets.UTF_8).contains("fala REVISADA A MAO"),
            "o backup tem de guardar a versao CORRIGIDA: " + backup);
    }

    /**
     * CASO-CONTROLE DE FRONTEIRA (A1) da anterior: MESMO sinal — o _PT-BR final já existe e a 2.1
     * roda de novo —, mas nada foi mexido. Não pode gerar backup nem aviso: backup a cada
     * reexecução enterraria o que importa, e aviso sem causa ensina a ignorar o aviso.
     */
    @Test
    @DisplayName("reexecucao sem mudanca nao gera backup, nao avisa e nao regrava")
    void reexecucaoSemMudancaNaoGeraBackupNemAviso() throws Exception {
        Path entrada = escreverAss("ep.ass", "Hello there", "How are you");
        montar(new FakeLlmPort()).processar(entrada, false, gerenciadorMontado.snapshotAtivo());

        List<String> ditas = new java.util.ArrayList<>();
        montar(new FakeLlmPort(), espiaoDe(ditas)).processar(entrada, false, gerenciadorMontado.snapshotAtivo());

        assertTrue(ditas.stream().noneMatch(l -> l.contains("[ BACKUP ]") || l.startsWith("[ ATENÇÃO ]")),
            "reexecucao identica nao pode gerar backup nem aviso; console:\n" + String.join("\n", ditas));
        assertTrue(ditas.stream().anyMatch(l -> l.contains("conteúdo idêntico ao já publicado")),
            "o 'nao regravei' tem de ser dito, nao presumido; console:\n" + String.join("\n", ditas));
    }

    /**
     * MEDIDO NO ZETA E24 (auditoria de 25/09/2026): a fala "Shree Klime, age 18." tem cópias de
     * 0,08 s com \clip (efeito de apagar). O seletor exclui as cópias como typesetting, e a
     * legenda piscava português → inglês no fim da fala. As cópias herdam a tradução da irmã.
     * O caso-controle no mesmo arquivo: verso de MÚSICA com o mesmo texto não herda.
     */
    @Test
    @DisplayName("quadros de transicao herdam a traducao da fala irma do mesmo estilo; musica nao herda")
    void quadrosDeTransicaoHerdamTraducaoDaIrma() throws Exception {
        // As QUATRO cópias reais do Zeta E24: com a fala são 5 instantes distintos, que é o que faz
        // o seletor tratá-las como letreiro animado. A primeira versão deste teste tinha 2 cópias,
        // ficava abaixo do limiar, as cópias iam ao LLM direto — e o teste passava com a herança
        // DESLIGADA (mutação sobreviveu, 25/09/2026).
        String clip1 = "{\\clip(m 890 1075 l 888 946 260 829 116 992)}Shree Klime, age 18.";
        String clip2 = "{\\clip(m 755.75 1074.625 l 755.75 944 310.5 911.125 169.125 1024)}Shree Klime, age 18.";
        String clip3 = "{\\clip(m 644 1071 l 645 840 167 814 81 988)}Shree Klime, age 18.";
        String clip4 = "{\\clip(m 513 1072 l 516 867 242 857 147 991)}Shree Klime, age 18.";
        Path pasta = Files.createDirectories(raiz.resolve("AnimeTeste").resolve("legendas_originais"));
        Path entrada = pasta.resolve("ep.ass");
        Files.writeString(entrada, CABECALHO_ASS
            + "Dialogue: 0,0:05:43.49,0:05:45.70,Default,,0,0,0,,Shree Klime, age 18.\n"
            + "Dialogue: 0,0:05:45.70,0:05:45.78,Default,,0,0,0,," + clip1 + "\n"
            + "Dialogue: 0,0:05:45.78,0:05:45.87,Default,,0,0,0,," + clip2 + "\n"
            + "Dialogue: 0,0:05:45.87,0:05:45.95,Default,,0,0,0,," + clip3 + "\n"
            + "Dialogue: 0,0:05:45.95,0:05:46.03,Default,,0,0,0,," + clip4 + "\n"
            + "Dialogue: 0,0:07:00.00,0:07:00.08,Song ENG,,0,0,0,," + clip1 + "\n",
            StandardCharsets.UTF_8);

        montar(new FakeLlmPort()).processar(entrada, false, gerenciadorMontado.snapshotAtivo());

        List<String> linhas = Files.readAllLines(raiz.resolve("saida").resolve("ep_PT-BR.ass"), StandardCharsets.UTF_8);
        assertTrue(linhas.stream().anyMatch(l -> l.contains("0:05:45.70") && l.endsWith("}fala traduzida")
                && l.contains("\\clip(m 890")),
            "a copia com clip tem de herdar a traducao mantendo o proprio clip:\n" + String.join("\n", linhas));
        assertTrue(linhas.stream().anyMatch(l -> l.contains("0:05:45.78") && l.endsWith("}fala traduzida")),
            "a segunda copia tambem:\n" + String.join("\n", linhas));
        assertTrue(linhas.stream().anyMatch(l -> l.contains("Song ENG") && l.endsWith("Shree Klime, age 18.")),
            "verso de musica com o mesmo texto NAO herda — outro estilo:\n" + String.join("\n", linhas));
    }

    private static final String METADE_1 = "We are going to wait right here for";
    private static final String METADE_2 = "the others to come back home tonight.";

    /**
     * Achado da auditoria de 25/09/2026: a corrente de frase partida deduzia vizinhança da ORDEM
     * DA LISTA DE PENDENTES. Aqui o "Yes." do meio é repetição de um anterior (sai da lista), e as
     * duas metades ficavam coladas — viravam corrente sem serem vizinhas no documento.
     */
    @Test
    @DisplayName("corrente: falas separadas por outra no documento NAO viram corrente")
    void falasNaoVizinhasNaoViramCorrente() throws Exception {
        tamanhoLoteTeste = 1;
        agruparFraseTeste = true;
        Path entrada = escreverAss("ep.ass", "Yes.", METADE_1, "Yes.", METADE_2);
        FakeLlmPort llm = new FakeLlmPort();

        montar(llm, new ConsoleUILoggerSilencioso()).processar(entrada, false, gerenciadorMontado.snapshotAtivo());

        assertTrue(llm.tamanhosDeLote.stream().allMatch(n -> n == 1),
            "nenhum lote pode juntar falas que nao sao vizinhas no documento: " + llm.tamanhosDeLote);
    }

    /** CASO-CONTROLE (A1): as mesmas metades, agora vizinhas de verdade, formam a corrente. */
    @Test
    @DisplayName("corrente: metades vizinhas de verdade continuam formando corrente")
    void metadesVizinhasFormamCorrente() throws Exception {
        tamanhoLoteTeste = 1;
        agruparFraseTeste = true;
        Path entrada = escreverAss("ep.ass", "Yes.", METADE_1, METADE_2);
        FakeLlmPort llm = new FakeLlmPort();

        montar(llm, new ConsoleUILoggerSilencioso()).processar(entrada, false, gerenciadorMontado.snapshotAtivo());

        assertTrue(llm.tamanhosDeLote.contains(2),
            "a frase partida vizinha tem de ir junta ao LLM: " + llm.tamanhosDeLote);
    }

    /**
     * A corrente reprovada pela guarda de deslocamento é retraduzida fala a fala. Se ESSA passada
     * falhar, a tradução agrupada — a que a guarda acabou de reprovar — era publicada, e o arquivo
     * saía CONCLUIDO com texto no tempo de outra fala. Agora as falas ficam pendentes, com a causa.
     */
    @Test
    @DisplayName("corrente reprovada cuja retraducao falha vira pendencia com causa, nao e publicada")
    void correnteReprovadaSemRetraducaoViraPendencia() throws Exception {
        tamanhoLoteTeste = 1;
        agruparFraseTeste = true;
        Path entrada = escreverAss("ep.ass", METADE_1, METADE_2);
        // 1a chamada: a corrente (o dublê devolve 2 palavras por linha — a guarda reprova por escala);
        // a partir da 2a, o servidor cai: a retradução individual falha.
        FakeLlmPort llm = FakeLlmPort.queCaiNaChamada(2);

        ResultadoTraducaoArquivo r = montar(llm, new ConsoleUILoggerSilencioso())
            .processar(entrada, false, gerenciadorMontado.snapshotAtivo());

        assertEquals(List.of(2), llm.tamanhosDeLote.subList(0, 1), "a corrente tem de ter ido junta");
        assertEquals(StatusArquivoTraducao.PARCIAL, r.status(),
            "a corrente reprovada nao pode sair como traducao concluida");
        String gravado = Files.readString(raiz.resolve("saida").resolve("ep_PT-BR.parcial.ass"), StandardCharsets.UTF_8);
        assertFalse(gravado.contains("fala traduzida"),
            "a traducao que a guarda reprovou nao pode ser publicada:\n" + gravado);
        assertTrue(telemetriaCaptor.ultima.errosOcorridos().stream()
                .anyMatch(a -> a.contains("corrente de frase partida reprovada")),
            "a causa tem de ser dita: " + telemetriaCaptor.ultima.errosOcorridos());
    }

    /**
     * No caminho da PARADA a guarda de deslocamento não rodava: a corrente reprovada ia para o
     * cache parcial e era reaproveitada na retomada. Agora ela não é salva.
     */
    @Test
    @DisplayName("parada: corrente reprovada nao vai para o cache parcial")
    void paradaNaoSalvaCorrenteReprovadaNoCache() throws Exception {
        tamanhoLoteTeste = 1;
        agruparFraseTeste = true;
        Path entrada = escreverAss("ep.ass", METADE_1, METADE_2, "Hello there.");
        FakeLlmPort llm = new FakeLlmPort(true);
        try {
            assertThrows(TraducaoParcialException.class,
                () -> montar(llm, new ConsoleUILoggerSilencioso()).processar(entrada, false, gerenciadorMontado.snapshotAtivo()));
        } finally {
            Thread.interrupted();
        }
        Path cache;
        try (var s = Files.walk(raiz)) {
            cache = s.filter(p -> p.getFileName().toString().equals("ep.cache.json")).findFirst().orElse(null);
        }
        String conteudo = cache == null ? "" : Files.readString(cache, StandardCharsets.UTF_8);
        assertFalse(conteudo.contains(METADE_1) && conteudo.contains("fala traduzida"),
            "a corrente reprovada nao pode ir para o cache parcial:\n" + conteudo);
    }

    /**
     * L1 e L2 da auditoria de 08/10/2026 (reproduzidos em execução com LLM falso): a 2.2 gravava o
     * cache sem lore no MESMO arquivo da 2.1. O cache com lore (e a correção feita nele pela 2.3)
     * saía do caminho ativo, a 2.1 seguinte retraduzia tudo e a correção não chegava ao _PT-BR; a
     * base de mescla {@code .publicado}, derivada da pasta do cache, também era sobrescrita.
     */
    @Test
    @DisplayName("L1/L2: traducao sem lore nao toca o cache nem a base de mescla da traducao com lore")
    void traducaoSemLoreNaoDespejaCacheNemBaseDaTraducaoComLore() throws Exception {
        Path entrada = escreverAss("ep.ass", "Hello there", "Where is Captain Bright?");
        Path cacheComLore = raiz.resolve("cache").resolve("AnimeTeste").resolve("ep.cache.json");
        Path baseComLore = raiz.resolve("cache").resolve("AnimeTeste").resolve(".publicado").resolve("ep_PT-BR.ass");

        montar(new FakeLlmPort()).processar(entrada, false, gerenciadorMontado.snapshotAtivo());
        String cacheEditado = Files.readString(cacheComLore, StandardCharsets.UTF_8)
            .replace("\"traduzido\" : \"fala traduzida\"", "\"traduzido\" : \"CORRECAO DA 2.3\"");
        assertTrue(cacheEditado.contains("CORRECAO DA 2.3"), "o ensaio precisa conseguir editar o cache");
        Files.writeString(cacheComLore, cacheEditado, StandardCharsets.UTF_8);
        byte[] baseAntes = Files.readAllBytes(baseComLore);

        org.traducao.projeto.lore.domain.SnapshotContexto semLore = new org.traducao.projeto.lore.domain.SnapshotContexto(
            "sem_lore", "Sem lore", "Traduza sem lore.", "", Set.of(), java.util.Map.of(), Set.of(), java.util.Map.of());
        montar(new FakeLlmPort()).processar(entrada, false, semLore, true);

        String cacheDepois = Files.readString(cacheComLore, StandardCharsets.UTF_8);
        assertTrue(cacheDepois.contains("\"caracterizacao\"") && cacheDepois.contains("CORRECAO DA 2.3"),
            "o cache COM lore foi despejado pela 2.2:\n" + cacheDepois);
        org.junit.jupiter.api.Assertions.assertArrayEquals(baseAntes, Files.readAllBytes(baseComLore),
            "a base de mescla da 2.1 foi sobrescrita pela 2.2");
        Path cacheSemLore = raiz.resolve("cache").resolve("AnimeTeste").resolve("sem_lore").resolve("ep.cache.json");
        assertTrue(Files.exists(cacheSemLore), "o cache sem lore tem de existir no endereco proprio");
        assertTrue(Files.readString(cacheSemLore, StandardCharsets.UTF_8).contains("\"sem_lore\""));
    }

    private ProcessarArquivoUseCase montar(FakeLlmPort llm) {
        return montar(llm, new ConsoleUILogger());
    }

    private ProcessarArquivoUseCase montar(FakeLlmPort llm, ConsoleUILogger uiLogger) {
        LeitorLegendaAss leitorAss = new LeitorLegendaAss();
        EscritorLegendaAss escritorAss = new EscritorLegendaAss();
        LeitorLegendaSrt leitorSrt = new LeitorLegendaSrt();
        EscritorLegendaSrt escritorSrt = new EscritorLegendaSrt();
        MascaradorTags mascarador = new MascaradorTags();
        CacheTraducaoService cache = new CacheTraducaoService(new ObjectMapper());
        ValidadorTraducaoService validador = new ValidadorTraducaoService(LoreAtivaFake.vazia());
        GerenciadorContexto gerenciador =
            new GerenciadorContexto(List.of(new ContextoTeste(), new ContextoObraAlheia()));
        this.gerenciadorMontado = gerenciador;
        ContextoCongeladoDaExecucao contextoCongelado = new ContextoCongeladoDaExecucao();
        // Mesma instância para o detector e para a guarda do fallback: ambos julgam contra a
        // terminologia da obra ATIVA, e usar adapters distintos permitiria divergência silenciosa.
        LoreAtivaContextoAdapter loreAtivaAdapter =
            new LoreAtivaContextoAdapter(gerenciador, contextoCongelado);
        DetectorTraducaoIdenticaService detectorIdentica =
            new DetectorTraducaoIdenticaService(loreAtivaAdapter);
        ProtecaoLegendaAssService protecao = new ProtecaoLegendaAssService();
        DetectorEfeitoKaraokeService detectorKaraoke = new DetectorEfeitoKaraokeService();
        TelemetriaTraducaoPort telemetria = telemetriaCaptor;
        // uiLogger chega por parâmetro: o cenário de cancelamento usa um logger
        // que desliga a barra de progresso (que, ativa, consumiria a interrupção).

        TradutorProperties props = new TradutorProperties(
            raiz.resolve("entrada").toString(),
            raiz.resolve("saida").toString(),
            raiz.resolve("cache").toString(),
            tamanhoLoteTeste, List.of(), "en", "pt-BR");
        props.setAgruparFrasePartida(agruparFraseTeste);
        LlmProperties llmProps = new LlmProperties(
            "http://127.0.0.1:1234/v1", "modelo-teste", 0.3, 2048,
            Duration.ofSeconds(5), Duration.ofSeconds(30));
        PastasExecucao pastas = new PastasExecucao();
        pastas.configurar(props.diretorioEntrada(), props.diretorioSaida(), props.diretorioCache(), props);

        ProcessarEpisodioUseCase episodio =
            new ProcessarEpisodioUseCase(llm, validador, uiLogger, telemetria, mascarador,
                new ReparadorMarcadoresLlm(mascarador));

        ResolvedorSaidaLegenda resolvedorSaida = new ResolvedorSaidaLegenda();
        ResolvedorCacheTraducao resolvedorCache =
            new ResolvedorCacheTraducao(pastas, resolvedorSaida, null, llmProps, props);
        PoliticaBackupTraducao politicaBackup = new PoliticaBackupTraducao(cache, uiLogger);
        SeletorEventosTraduziveis seletorEventos =
            new SeletorEventosTraduziveis(new PoliticaEstiloMusical(List.of()), detectorKaraoke, protecao, mascarador,
                new ProtecaoCamadasMusicaisService(detectorKaraoke));
        AvaliadorTraducaoCache avaliadorCache =
            new AvaliadorTraducaoCache(mascarador, detectorIdentica, validador,
                new VerificadorIdentificadorNumerico(LoreAtivaFake.vazia()),
                new RemovedorItalico(),
                new RestauradorFalaIdenticaSemItalico(mascarador, detectorIdentica,
                    new DescarteItalicoUltimoRecurso(), LoreAtivaFake.vazia()));
        TradutorLotesService tradutorLotes =
            new TradutorLotesService(mascarador, props, uiLogger, episodio, protecao, telemetria,
                new IsoladorQuebraDialogo(), new RemovedorItalico(), new SimplificadorItalicoRedundante(),
                new DescarteItalicoUltimoRecurso(), new DetectorCorrenteFrasePartida(),
                new GuardaCorrenteTraduzida());
        EnforcadorTermosLore enforcadorTermos = new EnforcadorTermosLore();
        MontadorTelemetriaTraducao montadorTelemetria =
            new MontadorTelemetriaTraducao(llmProps, resolvedorCache, llm);
        ClassificadorPendenciaTelemetria classificadorPendencia =
            new ClassificadorPendenciaTelemetria(detectorKaraoke);
        RecuperarPendenciaFallbackService recuperarPendenciaGoogle =
            new RecuperarPendenciaFallbackService(
                new FallbackOnlineProperties(fallbackOnlineAtivo), fallbackPort, loreAtivaAdapter,
                new VerificadorIdentificadorNumerico(LoreAtivaFake.vazia()));

        GuardaContextoObraTraducao guardaContextoObra = new GuardaContextoObraTraducao(
            gerenciador, new ValidadorCompatibilidadeObraContexto(), resolvedorCache, uiLogger);

        return new ProcessarArquivoUseCase(
            leitorAss, escritorAss, leitorSrt, escritorSrt, cache,
            props, uiLogger,
            pastas, telemetria, protecao, resolvedorSaida, resolvedorCache, politicaBackup, seletorEventos, new RemovedorItalico(), avaliadorCache, tradutorLotes, montadorTelemetria, classificadorPendencia, recuperarPendenciaGoogle,
            enforcadorTermos, new EnforcadorGlossarioFala(), new DetectorIdiomaFonteService(), new NormalizadorAspasService(),
            new NormalizadorAcentosComuns(),
            new org.traducao.projeto.qualidadeTraducao.application.CorretorHomografoComOriginal(),
            ortografiaTeste != null ? ortografiaTeste
                : new org.traducao.projeto.core.texto.dicionarioOrtografia.CorretorOrtograficoLegenda(),
            new NormalizadorCartaoDataService(), guardaContextoObra, contextoCongelado,
            new org.traducao.projeto.qualidadeTraducao.application.nomeProprio.DetectorNomeProprioTraduzido(
                new org.traducao.projeto.core.texto.dicionarioOrtografia.CorretorOrtograficoLegenda()));
    }

    /**
     * PROPÓSITO DE NEGÓCIO: escreve um episódio dentro de uma pasta de obra ARBITRÁRIA, para
     * exercitar a guarda obra×contexto — que decide a partir do nome da pasta-avó.
     * <p>INVARIANTES DO DOMÍNIO: mesma estrutura {@code <Obra>/legendas_originais/arquivo} dos
     * demais cenários; só o nome da obra muda.
     * <p>COMPORTAMENTO EM CASO DE FALHA: propaga a {@link IOException} da escrita.
     */
    private Path escreverAssEmObra(String obra, String nomeArquivo, String... falas) throws IOException {
        Path pastaAnime = Files.createDirectories(raiz.resolve(obra).resolve("legendas_originais"));
        StringBuilder sb = new StringBuilder(CABECALHO_ASS);
        int t = 1;
        for (String fala : falas) {
            sb.append(String.format("Dialogue: 0,0:00:%02d.00,0:00:%02d.00,Default,,0,0,0,,%s%n", t, t + 1, fala));
            t += 2;
        }
        Path arquivo = pastaAnime.resolve(nomeArquivo);
        Files.writeString(arquivo, sb.toString(), StandardCharsets.UTF_8);
        return arquivo;
    }

    private Path escreverAss(String nomeArquivo, String... falas) throws IOException {
        Path pastaAnime = Files.createDirectories(raiz.resolve("AnimeTeste").resolve("legendas_originais"));
        StringBuilder sb = new StringBuilder(CABECALHO_ASS);
        int t = 1;
        for (String fala : falas) {
            sb.append(String.format("Dialogue: 0,0:00:%02d.00,0:00:%02d.00,Default,,0,0,0,,%s%n", t, t + 1, fala));
            t += 2;
        }
        Path arquivo = pastaAnime.resolve(nomeArquivo);
        Files.writeString(arquivo, sb.toString(), StandardCharsets.UTF_8);
        return arquivo;
    }

    private Path escreverSrt(String nomeArquivo, String... falas) throws IOException {
        Path pastaAnime = Files.createDirectories(raiz.resolve("AnimeTeste").resolve("legendas_originais"));
        StringBuilder sb = new StringBuilder();
        int i = 1;
        int t = 1;
        for (String fala : falas) {
            sb.append(i).append('\n')
              .append(String.format("00:00:%02d,000 --> 00:00:%02d,000%n", t, t + 1))
              .append(fala).append("\n\n");
            i++;
            t += 2;
        }
        Path arquivo = pastaAnime.resolve(nomeArquivo);
        Files.writeString(arquivo, sb.toString(), StandardCharsets.UTF_8);
        return arquivo;
    }

    /**
     * PROPÓSITO DE NEGÓCIO: um episódio ASS sem pendências é traduzido, publicado
     * como {@code _PT-BR.ass} e marcado como {@code CONCLUIDO}, com o cache gravado.
     */
    /**
     * PROPÓSITO DE NEGÓCIO: o console SEMPRE diz o que a lore e o dicionário fizeram — mesmo
     * (e principalmente) quando não fizeram nada.
     *
     * <h2>O prejuízo que originou</h2>
     * Pergunta de Paulo em 2026-08-15: <i>"mas dicionário e lores estão todos logados agora,
     * testados e funcionando?"</i>. Estavam plugados e testados; <b>logados não</b>. A tradução
     * chamava {@code reforcar()}, que joga a contagem fora, enquanto {@code reforcarContando()}
     * já existia e era usado por outra fatia. O efeito: uma run em que a lore restaurou 40
     * termos e uma em que ela não fez nada imprimiam a MESMA coisa — nada. E o dicionário
     * ausente era indistinguível de dicionário sem trabalho a fazer.
     *
     * <h2>Invariantes do domínio</h2>
     * <ul>
     *   <li>Toda execução emite uma linha {@code [ LORE ]} e uma {@code [ ORTOGRAFIA ]}.
     *       Silêncio é proibido: é o sinal ambíguo que a regra da saída vazia veta.</li>
     *   <li>O dicionário fala em TRÊS estados — indisponível, ativo sem correção, ativo com
     *       N correções —, nunca em dois.</li>
     * </ul>
     *
     * <h2>Comportamento em caso de falha</h2>
     * Reprovar aqui significa que o operador voltou a não ter como saber, olhando o console, se
     * a lore e a ortografia agiram na legenda que acabou de ser gravada.
     */
    @Test
    void oConsoleDizSempreOQueALoreEODicionarioFizeram() throws Exception {
        List<String> ditas = new java.util.ArrayList<>();
        ConsoleUILogger espiao = new ConsoleUILogger() {
            @Override
            public synchronized void log(String mensagem) {
                ditas.add(mensagem);
            }
        };
        FakeLlmPort llm = new FakeLlmPort();
        Path entrada = escreverAss("ep.ass", "Hello there", "How are you");

        montar(llm, espiao).processar(entrada, false, gerenciadorMontado.snapshotAtivo());

        assertTrue(ditas.stream().anyMatch(l -> l.contains("[ LORE ]")),
            () -> "nenhuma linha [ LORE ]: a run nao diz se a lore agiu. Ditas:\n"
                + String.join("\n", ditas));
        assertTrue(ditas.stream().anyMatch(l -> l.contains("[ ORTOGRAFIA ]")),
            () -> "nenhuma linha [ ORTOGRAFIA ]: dicionario ausente ficaria indistinguivel de "
                + "dicionario sem trabalho. Ditas:\n" + String.join("\n", ditas));

        // O terceiro estado precisa estar DITO, não subentendido: ou o dicionário se declara
        // indisponível, ou declara quantas falas corrigiu.
        String ortografia = ditas.stream().filter(l -> l.contains("[ ORTOGRAFIA ]")).findFirst().orElseThrow();
        assertTrue(ortografia.contains("INDISPONÍVEL") || ortografia.contains("corrigida")
                || ortografia.contains("0 fala corrigida"),
            "a linha de ortografia precisa dizer o estado, nao so existir: " + ortografia);
    }

    /** Corretor ortográfico do caso de uso; {@code null} usa o real. */
    private org.traducao.projeto.core.texto.dicionarioOrtografia.CorretorOrtograficoLegenda ortografiaTeste;

    /**
     * 28/10/2026: a 2.1 chamava o dicionário fala a fala, sem o aquecimento em lote que a 3.3 usa
     * — 258,5 s frio contra 30,8 s aquecido no filme de Sidonia (09/10), mesma saída. O espião
     * registra que o lote chega ANTES de qualquer correção por fala, com todas as traduções.
     */
    @Test
    @DisplayName("a 2.1 aquece o dicionario com todas as traducoes antes de corrigir fala a fala")
    void aqueceODicionarioEmLote() throws Exception {
        List<String> eventos = java.util.Collections.synchronizedList(new java.util.ArrayList<>());
        ortografiaTeste = new org.traducao.projeto.core.texto.dicionarioOrtografia.CorretorOrtograficoLegenda() {
            @Override
            public boolean aquecerComTextos(java.util.Collection<String> textos) {
                eventos.add("aquecer:" + textos.size());
                return super.aquecerComTextos(textos);
            }

            @Override
            public String corrigir(String texto, java.util.Set<String> intocaveis) {
                eventos.add("corrigir");
                return super.corrigir(texto, intocaveis);
            }
        };
        ProcessarArquivoUseCase uc = montar(new FakeLlmPort());
        Path entrada = escreverAss("ep.ass", "Hello there", "How are you", "See you later");

        uc.processar(entrada, false, gerenciadorMontado.snapshotAtivo());

        assertEquals("aquecer:3", eventos.get(0), "o lote vem primeiro, com as 3 traducoes: " + eventos);
        assertEquals(1, eventos.stream().filter(e -> e.startsWith("aquecer")).count(), "um lote so: " + eventos);
        assertEquals(3, eventos.stream().filter("corrigir"::equals).count(), "CONTROLE: a correcao por fala continua");
    }

    /**
     * 28/10/2026: "No." ia ao modelo, era recusado nas três temperaturas e saía EM INGLÊS — 49 de
     * 165 falas assim no acervo, embora a resposta estivesse escrita no glossário. A fala que é
     * inteiramente um termo do glossário agora é traduzida sem o modelo; as outras continuam indo.
     */
    @Test
    @DisplayName("fala que e termo do glossario sai traduzida SEM chamar o modelo")
    void falaDoGlossarioNaoVaiAoModelo() throws Exception {
        FakeLlmPort llm = new FakeLlmPort();
        ProcessarArquivoUseCase uc = montar(llm);
        Path entrada = escreverAss("ep.ass", "No...", "Hello there", "{\\i1}No!{\\i0}");

        ResultadoTraducaoArquivo r = uc.processar(entrada, false, gerenciadorMontado.snapshotAtivo());

        assertEquals(StatusArquivoTraducao.CONCLUIDO, r.status());
        String conteudo = Files.readString(raiz.resolve("saida").resolve("ep_PT-BR.ass"), StandardCharsets.UTF_8);
        assertTrue(conteudo.contains(",Não..."), "o glossario traduz a fala inteira: " + conteudo);
        assertTrue(conteudo.contains(",Não!"),
            "pontuacao do original sobrevive (o italico do dialogo sai por politica da 2.1): " + conteudo);
        assertTrue(conteudo.contains("fala traduzida"), "CONTROLE: a fala comum continua indo ao modelo");
        assertEquals(1, llm.tamanhosDeLote.stream().mapToInt(Integer::intValue).sum(),
            "so 'Hello there' chega ao modelo; lotes: " + llm.tamanhosDeLote);
    }

    @Test
    void assFluxoCompletoCacheMissConcluido() throws Exception {
        FakeLlmPort llm = new FakeLlmPort();
        ProcessarArquivoUseCase uc = montar(llm);
        Path entrada = escreverAss("ep.ass", "Hello there", "How are you");

        ResultadoTraducaoArquivo r = uc.processar(entrada, false, gerenciadorMontado.snapshotAtivo());

        assertEquals(StatusArquivoTraducao.CONCLUIDO, r.status());
        Path saida = raiz.resolve("saida").resolve("ep_PT-BR.ass");
        assertTrue(Files.exists(saida), "saida final _PT-BR.ass deve existir");
        String conteudo = Files.readString(saida, StandardCharsets.UTF_8);
        assertTrue(conteudo.contains("fala traduzida"), "texto traduzido deve aparecer");
        assertFalse(conteudo.contains("Hello there"), "texto original nao deve permanecer");
        assertEquals(1, llm.chamadas.get(), "um lote deve ter sido enviado ao LLM");
        assertTrue(Files.exists(saida.getParent()));

        TelemetriaTraducao tel = telemetriaCaptor.ultima;
        assertNotNull(tel, "a telemetria do episódio deve ter sido registrada");
        assertEquals("CONCLUIDO", tel.statusFinal());
        assertEquals("ep.ass", tel.nomeEpisodio());
        assertEquals("modelo-teste", tel.modeloLlm());
        assertEquals(2, tel.totalLinhas(), "duas falas traduzíveis");
        assertEquals(2, tel.falasTraduzidas(), "ambas traduzidas de novo (cache-miss)");
        assertEquals(0, tel.falasDoCache(), "nenhuma fala veio do cache");
        assertEquals("AnimeTeste", tel.animeNome());
        assertTrue(tel.errosOcorridos().isEmpty(), "cenário concluído não tem avisos");
    }

    /**
     * PROPÓSITO DE NEGÓCIO: reexecutar o mesmo episódio, com a mesma proveniência,
     * reaproveita integralmente o cache e não chama o LLM (cache incremental).
     */
    @Test
    void assSegundaExecucaoReutilizaCacheSemChamarLlm() throws Exception {
        Path entrada = escreverAss("ep.ass", "Hello there", "How are you");

        FakeLlmPort primeira = new FakeLlmPort();
        montar(primeira).processar(entrada, false, gerenciadorMontado.snapshotAtivo());
        assertEquals(1, primeira.chamadas.get());

        FakeLlmPort segunda = new FakeLlmPort();
        ResultadoTraducaoArquivo r = montar(segunda).processar(entrada, false, gerenciadorMontado.snapshotAtivo());

        assertEquals(StatusArquivoTraducao.CONCLUIDO, r.status());
        assertEquals(0, segunda.chamadas.get(), "segunda execucao nao pode chamar o LLM");
    }

    /**
     * PROPÓSITO DE NEGÓCIO: uma execução interrompida no meio não pode custar o trabalho já
     * feito — a retomada continua de onde parou, sem repetir o que o LLM já traduziu.
     *
     * <h2>O prejuízo que originou este teste</h2>
     * 12/08/2026, 09:22: a aplicação em modo dev recarregou por baixo de uma rodada em
     * andamento (alguém editou {@code src/main} enquanto o Unicorn traduzia). O episódio E01
     * morreu com {@code "após 3 tentativa(s): null"} e a mensagem foi
     * {@code "abortado sem gerar saída (57 linha(s) salvas no cache para retomar)"}.
     *
     * <p>Salvar para retomar é o comportamento CERTO, e ele existia — mas
     * {@code FakeLlmPort(interromperNaPrimeira)} estava construído no harness e <b>nenhum teste
     * o usava</b>. O mecanismo de recuperação nunca tinha sido exercitado: "salvas para
     * retomar" era uma promessa da mensagem de log, não um fato provado.
     *
     * <h2>Invariantes do domínio</h2>
     * <ul>
     *   <li>O que já foi traduzido antes da interrupção fica no cache e NÃO volta ao LLM.</li>
     *   <li>A retomada completa o restante e conclui.</li>
     *   <li>O resultado final é o mesmo que o de uma execução sem interrupção — retomar não
     *       pode produzir arquivo diferente de nunca ter parado.</li>
     * </ul>
     */
    @Test
    void interrupcaoNoMeioRetomaSemRefazerOQueJaFoiTraduzido() throws Exception {
        // 21 falas, como em cancelamentoNoMeioPreservaProgressoParcialDoPrimeiroLote: com
        // tamanhoLote=20 elas geram 2 lotes, e a interrupção cooperativa só é percebida ENTRE
        // lotes. Com 3 falas — a primeira versão deste teste — cabe tudo num lote, o episódio
        // CONCLUI e o cenário vira um segundo teste de cache, verde e medindo outra coisa.
        // Foi a asserção `parouDeVerdade` que pegou isso.
        String[] falas = new String[21];
        for (int i = 0; i < falas.length; i++) {
            falas[i] = "Line " + (char) ('A' + i / 26) + (char) ('A' + i % 26);
        }
        Path entrada = escreverAss("ep.ass", falas);

        // 1ª execução: o dublê marca a interrupção cooperativa logo após o primeiro lote.
        FakeLlmPort interrompida = new FakeLlmPort(true);
        boolean parouDeVerdade = false;
        try {
            ResultadoTraducaoArquivo parcial = montar(interrompida)
                .processar(entrada, false, gerenciadorMontado.snapshotAtivo());
            parouDeVerdade = parcial.status() != StatusArquivoTraducao.CONCLUIDO;
        } catch (TraducaoParcialException esperada) {
            parouDeVerdade = true;
        } finally {
            // A interrupção cooperativa marca a thread; sem limpar, a próxima execução herdaria
            // a marca e "seria interrompida" antes de começar — o teste mediria o próprio lixo.
            Thread.interrupted();
        }

        // CALIBRAGEM do próprio teste: sem esta asserção, um dublê que parasse de interromper
        // faria o cenário virar silenciosamente um SEGUNDO teste de cache — verde, e medindo
        // outra coisa. É o "0 não é prova" aplicado ao caso-controle.
        assertTrue(parouDeVerdade,
            "a primeira execução CONCLUIU: a interrupção não aconteceu e este teste não está "
                + "medindo retomada nenhuma");
        assertTrue(interrompida.chamadas.get() >= 1,
            "a primeira execução precisa ter traduzido algo antes de parar");

        // 2ª execução: mesmo episódio, mesma proveniência. Só o que faltava pode ir ao LLM.
        FakeLlmPort retomada = new FakeLlmPort();
        ResultadoTraducaoArquivo r = montar(retomada, new ConsoleUILoggerSilencioso())
            .processar(entrada, false, gerenciadorMontado.snapshotAtivo());

        assertEquals(StatusArquivoTraducao.CONCLUIDO, r.status(),
            "a retomada tem de concluir o episódio que a interrupção deixou pela metade");
        Path saida = raiz.resolve("saida").resolve("ep_PT-BR.ass");
        assertTrue(Files.exists(saida), "a retomada tem de publicar a saída final");
        String conteudo = Files.readString(saida, StandardCharsets.UTF_8);
        assertFalse(conteudo.contains("Line AA"), "nenhuma fala pode ficar no idioma original");
        assertFalse(conteudo.contains("Line AU"), "a fala do 2º lote também tem de ser traduzida");

        TelemetriaTraducao tel = telemetriaCaptor.ultima;
        assertNotNull(tel);
        assertEquals(20, tel.falasDoCache(),
            "as 20 falas salvas antes da interrupção têm de vir do CACHE — se vier zero, "
                + "'salvas para retomar' é só uma frase no log");
        assertEquals(1, retomada.chamadas.get(),
            "a retomada só pode chamar o LLM para o que FALTAVA: um lote com a 21ª fala");
    }

    /**
     * PROPÓSITO DE NEGÓCIO: SRT nativo percorre o mesmo pipeline e publica
     * {@code _PT-BR.srt} sem conversão para ASS.
     */
    @Test
    void srtFluxoCompletoConcluido() throws Exception {
        FakeLlmPort llm = new FakeLlmPort();
        ProcessarArquivoUseCase uc = montar(llm);
        Path entrada = escreverSrt("ep.srt", "Hello there", "How are you");

        ResultadoTraducaoArquivo r = uc.processar(entrada, false, gerenciadorMontado.snapshotAtivo());

        assertEquals(StatusArquivoTraducao.CONCLUIDO, r.status());
        Path saida = raiz.resolve("saida").resolve("ep_PT-BR.srt");
        assertTrue(Files.exists(saida), "saida final _PT-BR.srt deve existir");
        assertTrue(Files.readString(saida, StandardCharsets.UTF_8).contains("fala traduzida"));
    }

    /**
     * PROPÓSITO DE NEGÓCIO: uma fala que o LLM devolve sem traduzir permanece
     * pendente — status {@code PARCIAL}, publicação apenas em {@code .parcial.ass}
     * e preservação do texto original naquela fala, sem sobrescrever a saída final.
     */
    @Test
    void assLinhaNaoTraduzidaGeraParcial() throws Exception {
        FakeLlmPort llm = new FakeLlmPort();
        ProcessarArquivoUseCase uc = montar(llm);
        Path entrada = escreverAss("ep.ass", "Hello there", "KEEPME stays");

        ResultadoTraducaoArquivo r = uc.processar(entrada, false, gerenciadorMontado.snapshotAtivo());

        assertEquals(StatusArquivoTraducao.PARCIAL, r.status());
        Path parcial = raiz.resolve("saida").resolve("ep_PT-BR.parcial.ass");
        Path finalPtBr = raiz.resolve("saida").resolve("ep_PT-BR.ass");
        assertTrue(Files.exists(parcial), "resultado incompleto deve ir para .parcial.ass");
        assertFalse(Files.exists(finalPtBr), "saida final nao pode ser publicada com pendencia");
        String conteudo = Files.readString(parcial, StandardCharsets.UTF_8);
        assertTrue(conteudo.contains("KEEPME stays"), "fala pendente mantem o texto original");

        TelemetriaTraducao tel = telemetriaCaptor.ultima;
        assertNotNull(tel, "a telemetria do episódio parcial deve ter sido registrada");
        assertEquals("PARCIAL", tel.statusFinal());
        assertEquals(2, tel.totalLinhas(), "duas falas traduzíveis");
        assertEquals(1, tel.falasTraduzidas(), "só 'Hello there' foi traduzida; KEEPME ficou pendente");
        assertFalse(tel.errosOcorridos().isEmpty(), "cenário parcial deve registrar avisos");

        // KPI estruturado (schema 1.1): a fala 'KEEPME stays' voltou como o original
        // (eco), sob estilo comum -> DIALOGO/ECO, quantidade 1.
        assertEquals(1, tel.pendenciasPorCausa().size(), "uma combinação (categoria,causa) pendente");
        var p = tel.pendenciasPorCausa().get(0);
        assertEquals("DIALOGO", p.categoria());
        assertEquals("ECO", p.causaRaiz());
        assertEquals(1, p.quantidade());

        // O CARIMBO DESCREVE O ARQUIVO EM QUE ELE ESTA. Aqui o arquivo E parcial, e a frase
        // pode dizer isso. O par de fronteira esta no teste seguinte: MESMA pendencia, arquivo
        // FINAL. Sem os dois lados, a guarda prova que enxerga e nao que discrimina (A1).
        assertTrue(conteudo.contains("arquivo publicado como parcial"),
            "carimbo do .parcial deve dizer que e parcial, e disse: " + primeiraLinhaDePendencia(conteudo));
    }

    /**
     * PROPÓSITO DE NEGÓCIO: prova que o carimbo de proveniência não afirma mais uma falsidade
     * sobre o arquivo que o carrega. Com retradução autorizada, o pipeline publica a saída
     * <b>final</b> mesmo havendo pendência ({@code ResolvedorSaidaLegenda.selecionar}, parâmetro
     * {@code protecaoLiberada}) — e o carimbo escrevia "arquivo publicado como parcial" de
     * qualquer maneira, porque era montado ANTES de o destino ser resolvido.
     *
     * <h2>O prejuízo MEDIDO — 2026-09-09, DanMachi S01E01</h2>
     * O arquivo em disco era {@code ..._PT-BR.ass}, final, sem irmão {@code .parcial}, e o
     * cabeçalho dele dizia:
     * <pre>
     * ; KRONOS pendentes: 14 fala(s) mantida(s) no original — arquivo publicado como parcial
     * </pre>
     * A régua de auditoria do projeto diz que <b>{@code .parcial} não é entrega</b>. Um operador
     * que aplique essa régua ao carimbo <b>descarta uma entrega válida</b> — é a lente de boa-fé:
     * ninguém precisa errar para o dano acontecer, basta acreditar no que o arquivo diz de si.
     *
     * <h2>Caso-controle de fronteira (A1)</h2>
     * O sinal superficial é o mesmo nos dois testes — {@code falhasDistintas} não vazio. O que
     * muda é só o destino escolhido pelo resolvedor. Uma guarda que olhasse apenas a pendência
     * daria a mesma frase nos dois, que é exatamente o defeito corrigido.
     *
     * <h2>Comportamento em caso de falha</h2>
     * Falha de asserção nomeando a frase encontrada no cabeçalho.
     */
    @Test
    void carimboNaoChamaDeParcialUmArquivoPublicadoComoFinal() throws Exception {
        FakeLlmPort llm = new FakeLlmPort();
        ProcessarArquivoUseCase uc = montar(llm);
        Path entrada = escreverAss("ep.ass", "Hello there", "KEEPME stays");

        // true = retradução autorizada: publica a saída FINAL ainda que sobre pendência.
        ResultadoTraducaoArquivo r = uc.processar(entrada, true, gerenciadorMontado.snapshotAtivo());

        Path finalPtBr = raiz.resolve("saida").resolve("ep_PT-BR.ass");
        Path parcial = raiz.resolve("saida").resolve("ep_PT-BR.parcial.ass");
        assertTrue(Files.exists(finalPtBr),
            "com retraducao autorizada a saida final e publicada mesmo com pendencia");
        assertFalse(Files.exists(parcial), "nao existe .parcial neste cenario");
        assertEquals(StatusArquivoTraducao.PARCIAL, r.status(),
            "o STATUS continua parcial: o que mudou foi o NOME do arquivo, nao a pendencia");

        String conteudo = Files.readString(finalPtBr, StandardCharsets.UTF_8);
        assertTrue(conteudo.contains("pendentes: 1 fala(s)"),
            "a pendencia continua declarada no carimbo: " + primeiraLinhaDePendencia(conteudo));
        assertFalse(conteudo.contains("arquivo publicado como parcial"),
            "arquivo FINAL nao pode se declarar parcial: " + primeiraLinhaDePendencia(conteudo));
        assertTrue(conteudo.contains("publicado como FINAL"),
            "o carimbo tem de dizer o que o arquivo E: " + primeiraLinhaDePendencia(conteudo));
    }

    /**
     * PROPÓSITO DE NEGÓCIO: fecha a fronteira A6 da correção de rumo de relógio. A medição no
     * modelo real respondeu "o modelo acerta com a instrução nova"; ela <b>não</b> respondeu se a
     * resposta certa chega ao arquivo. Guarda que reprova a tradução devolve a fala ao INGLÊS na
     * legenda, e nesse caso a correção pioraria a entrega em vez de melhorá-la.
     *
     * <h2>Por que este teste não chama o modelo</h2>
     * De propósito, e é a separação que o auditor externo cobrou: geração e publicação são
     * perguntas diferentes e medidas separadamente. A geração foi medida no aya-expanse-8b com o
     * prompt de produção (3 repetições, temperatura 0.3): {@code "Two o'clock!"} saiu como
     * {@code "2 horas!"} em 3 de 3 com a instrução, contra {@code "Meia-noite!"} em 3 de 3 sem
     * ela. Este teste pega essa resposta EXATA e pergunta se o pipeline a publica.
     *
     * <h2>O risco concreto que ele fecha</h2>
     * O original escreve o número por extenso ({@code Two}) e a tradução usa algarismo
     * ({@code 2}). Essa é exatamente a assimetria que o {@code VerificadorIdentificadorNumerico}
     * declara tolerar — mas "declara tolerar" é afirmação de Javadoc, e o que vale é o arquivo
     * gravado. Se qualquer guarda da cadeia reprovasse, a fala publicada voltaria a
     * {@code "Two o'clock!"} em inglês.
     *
     * <h2>Comportamento em caso de falha</h2>
     * Falha de asserção mostrando o que foi realmente gravado no {@code .ass}.
     */
    @Test
    void respostaDeRumoDeRelogioChegaAoArquivoSemVoltarAoIngles() throws Exception {
        String original = "11 o'clock! Enemy has opened fire!";
        String medida = "11 horas! O inimigo abriu fogo!";
        FakeLlmPort llm = FakeLlmPort.comResposta("11 o'clock", medida);
        ProcessarArquivoUseCase uc = montar(llm);
        Path entrada = escreverAss("ep.ass", original);

        ResultadoTraducaoArquivo r = uc.processar(entrada, false, gerenciadorMontado.snapshotAtivo());

        Path saida = raiz.resolve("saida").resolve("ep_PT-BR.ass");
        assertTrue(Files.exists(saida),
            "sem pendencia a saida final tem de ser publicada; status=" + r.status());
        String conteudo = Files.readString(saida, StandardCharsets.UTF_8);
        String gravada = conteudo.lines()
            .filter(l -> l.startsWith("Dialogue:"))
            .findFirst().orElse("<<nenhuma linha Dialogue no arquivo>>");
        assertTrue(conteudo.contains("11 horas!"),
            "o valor do relogio tem de sobreviver ate o disco, e foi gravado: " + gravada);
        assertFalse(conteudo.contains("11 o'clock"),
            "o portao devolveu a fala ao ingles, e a correcao piorou a entrega: " + gravada);
        assertEquals(StatusArquivoTraducao.CONCLUIDO, r.status(),
            "a resposta medida no modelo real nao pode virar pendencia");
    }

    /** {@code \N} do ASS montado sem literal de escape, para o teste não depender de transporte. */

    /**
     * PROPÓSITO DE NEGÓCIO: fecha a fronteira A6 da instrução de proa. A resposta que o modelo dá
     * com a instrução nova carrega duas formas que costumam derrubar guarda numérica ao mesmo
     * tempo: o rumo escrito com hífens ({@code 2-8-0}) e a distância com separador de milhar
     * ({@code 5.000} para {@code 5,000}). Se qualquer camada reprovasse, a fala publicada voltaria
     * ao inglês e a correção pioraria a entrega.
     *
     * <h2>Por que este caso, e não outro</h2>
     * É o caso em que o arquivo publicado <b>perdeu o rumo inteiro</b> — saiu como
     * {@code "Distancia 5.000."}, sem nenhum rumo. Provar que a resposta completa chega ao disco é
     * exatamente o que o defeito original desmentia.
     *
     * <h2>Comportamento em caso de falha</h2>
     * Falha de asserção mostrando a linha realmente gravada no {@code .ass}.
     */
    @Test
    void respostaDeProaChegaAoArquivoComHifenESeparadorDeMilhar() throws Exception {
        String original = "Heading 2-8-0. Distance 5,000.";
        String medida = "Direção: 2-8-0. Distância: 5.000.";
        FakeLlmPort llm = FakeLlmPort.comResposta("Heading 2-8-0", medida);
        ProcessarArquivoUseCase uc = montar(llm);
        Path entrada = escreverAss("ep.ass", original);

        ResultadoTraducaoArquivo r = uc.processar(entrada, false, gerenciadorMontado.snapshotAtivo());

        Path saida = raiz.resolve("saida").resolve("ep_PT-BR.ass");
        assertTrue(Files.exists(saida),
            "sem pendencia a saida final tem de ser publicada; status=" + r.status());
        String conteudo = Files.readString(saida, StandardCharsets.UTF_8);
        String gravada = conteudo.lines()
            .filter(l -> l.startsWith("Dialogue:"))
            .findFirst().orElse("<<nenhuma linha Dialogue no arquivo>>");
        assertTrue(conteudo.contains("2-8-0"),
            "o rumo com hifen tem de sobreviver ate o disco, e foi gravado: " + gravada);
        assertTrue(conteudo.contains("5.000"),
            "o separador de milhar e reescrita legitima do portugues: " + gravada);
        assertFalse(conteudo.contains("Heading 2-8-0"),
            "o portao devolveu a fala ao ingles: " + gravada);
        assertEquals(StatusArquivoTraducao.CONCLUIDO, r.status(),
            "a resposta medida no modelo real nao pode virar pendencia");
    }

    /**
     * PROPÓSITO DE NEGÓCIO: fecha a fronteira A6 da correção de acento do futuro. O
     * {@code CorretorHomografoComOriginal} repõe o acento usando o original inglês como prova, mas
     * "a função devolve certo" não é "o arquivo saiu certo": entre a correção e a gravação ainda
     * correm terminologia, normalização de aspas, carimbo de cabeçalho e serialização.
     *
     * <h2>O que este teste responde, e o outro não</h2>
     * {@code AcentoDoFuturoComOriginalTest} prova o critério na função, com 16 casos e mutação. Só
     * este prova que o {@code á} chega ao {@code .ass} publicado — e que nenhuma guarda da cadeia
     * reprova a fala corrigida, devolvendo-a ao inglês.
     *
     * <h2>Comportamento em caso de falha</h2>
     * Falha de asserção mostrando a linha realmente gravada.
     */
    @Test
    void acentoDoFuturoChegaAoArquivoGravado() throws Exception {
        String original = "This war will end someday.";
        FakeLlmPort llm = FakeLlmPort.comResposta("This war will end", "Um dia, a guerra acabara.");
        ProcessarArquivoUseCase uc = montar(llm);
        Path entrada = escreverAss("ep.ass", original);

        ResultadoTraducaoArquivo r = uc.processar(entrada, false, gerenciadorMontado.snapshotAtivo());

        Path saida = raiz.resolve("saida").resolve("ep_PT-BR.ass");
        assertTrue(Files.exists(saida),
            "sem pendencia a saida final tem de ser publicada; status=" + r.status());
        String conteudo = Files.readString(saida, StandardCharsets.UTF_8);
        String gravada = conteudo.lines()
            .filter(l -> l.startsWith("Dialogue:"))
            .findFirst().orElse("<<nenhuma linha Dialogue no arquivo>>");
        assertTrue(conteudo.contains("acabará"),
            "o acento do futuro tem de sobreviver ate o disco, e foi gravado: " + gravada);
        assertFalse(conteudo.contains("acabara "),
            "a forma sem acento nao pode continuar no arquivo: " + gravada);
        assertEquals(StatusArquivoTraducao.CONCLUIDO, r.status(),
            "a fala corrigida nao pode virar pendencia");
    }

    /**
     * PROPÓSITO DE NEGÓCIO: extrai do cabeçalho a linha de pendência para a mensagem de falha
     * mostrar o texto real gravado, e não apenas "esperava true".
     *
     * <p>INVARIANTES DO DOMÍNIO: não altera o conteúdo; devolve a primeira linha que contém o
     * rótulo de pendência do carimbo.
     *
     * <p>COMPORTAMENTO EM CASO DE FALHA: sem linha de pendência, devolve aviso explícito em vez
     * de {@code null} — asserção que falha sem mostrar o observado não ensina nada.
     */
    private static String primeiraLinhaDePendencia(String cabecalho) {
        return cabecalho.lines()
            .filter(l -> l.contains("pendentes:"))
            .findFirst()
            .orElse("<<nenhuma linha de pendencia no cabecalho>>");
    }

    /**
     * PROPÓSITO DE NEGÓCIO: uma fala que o LLM nunca conseguiu traduzir (engoliu os
     * marcadores {@code [[TAGn]]} nas três tentativas) NÃO pode ser publicada em inglês como
     * se fosse tradução. Ela vira pendência declarada, o episódio fica contido no
     * {@code .parcial.ass} e o cache grava vazio para a fala ser retentada na próxima
     * execução.
     *
     * <p>HISTÓRICO — este teste nasceu ao contrário. Escrito em 2026-07-22, ele CARACTERIZAVA
     * o defeito: exigia {@code pendenciasPorCausa} VAZIO, saída FINAL publicada e o inglês
     * gravado no cache como tradução boa. Era a assinatura real de 7 episódios do 08th MS
     * Team, todos {@code PARCIAL} sem dizer o que estava errado. A causa era a régua de
     * identidade aceitar qualquer palavra capitalizada com 3+ letras como nome próprio, o que
     * deixava passar {@code Heavy!}, {@code Enter.}, {@code Gotcha!} e {@code Next Episode}.
     *
     * <p>Quando a régua por evidência entrou em
     * {@code DetectorTraducaoIdenticaService.temEvidenciaDeInglesTraduzivel}, este teste
     * falhou — como previsto no próprio Javadoc que ele tinha na época — e foi invertido para
     * fixar o comportamento correto. As asserções abaixo são o oposto ponto a ponto das
     * originais.
     *
     * <h2>Prova real (corrida de 2026-07-22, Gundam 08th MS Team, 13 episódios)</h2>
     * 29 avisos "tags corrompidas pelo LLM" em 7 episódios PARCIAL-com-zero-pendência.
     * Cruzando cada aviso com o cache: 19 falas foram recuperadas pelo fallback (o aviso
     * ficou órfão) e 10 foram publicadas idênticas ao original. Dessas 10, sete são nomes
     * próprios legítimos (Eledore, Michel, Karen, Dell) e três são inglês de verdade
     * entregue ao espectador: {@code "Heavy!"} (E02), {@code "Enter."} (E07) e
     * {@code "\"Doctor Flanagan\"...?"} (E12).
     *
     * <h2>Invariantes do domínio</h2>
     * <ul>
     *   <li>Fala descartada pelo desmascaramento NUNCA entra no cache como tradução válida.</li>
     *   <li>PARCIAL vem sempre acompanhado da pendência que o justifica no KPI causal.</li>
     *   <li>Com pendência, a saída final não é publicada — o inglês fica no {@code .parcial.ass}.</li>
     * </ul>
     *
     * <h2>Comportamento em caso de falha</h2>
     * Voltar a gravar o inglês no cache é o pior desfecho: {@code isCacheReaproveitavel} o
     * reaproveita na execução seguinte e a fala congela em inglês, agora sem nem o PARCIAL
     * para chamar atenção.
     */
    @Test
    void falaComMarcadorDescartadoEhPublicadaEmInglesSemVirarPendencia() throws Exception {
        FakeLlmPort llm = FakeLlmPort.queEngoleMarcadores();
        ProcessarArquivoUseCase uc = montar(llm);
        // "Heavy{\b1}!" reproduz a fala real do E02: a tag fica NO MEIO do texto visível,
        // então o reparador de marcadores não pode reconstruí-la pelas bordas e recusa.
        Path entrada = escreverAss("ep.ass", "Heavy{\\b1}!", "How are you");

        ResultadoTraducaoArquivo r = uc.processar(entrada, false, gerenciadorMontado.snapshotAtivo());

        assertEquals(StatusArquivoTraducao.PARCIAL, r.status(),
            "a fala não resolvida mantém o episódio PARCIAL");

        TelemetriaTraducao tel = telemetriaCaptor.ultima;
        assertNotNull(tel, "a telemetria do episódio deve ter sido registrada");
        assertFalse(tel.pendenciasPorCausa().isEmpty(),
            "o PARCIAL agora vem ACOMPANHADO da pendência que o justifica — era exatamente isto "
                + "que faltava: status PARCIAL com KPI causal vazio, sem dizer o que estava errado");
        assertTrue(tel.errosOcorridos().stream()
                .anyMatch(a -> a.startsWith("Fala mantida sem tradução (tags corrompidas pelo LLM): ")),
            "o descarte do marcador precisa deixar rastro em errosOcorridos: " + tel.errosOcorridos());

        // Com pendência declarada, o resolvedor NÃO publica a saída final: o inglês fica contido
        // no .parcial.ass e a versão final anterior permanece intacta.
        Path finalPtBr = raiz.resolve("saida").resolve("ep_PT-BR.ass");
        Path parcial = raiz.resolve("saida").resolve("ep_PT-BR.parcial.ass");
        assertFalse(Files.exists(finalPtBr),
            "com fala não resolvida, a saída FINAL não pode ser publicada");
        assertTrue(Files.exists(parcial), "o resultado incompleto vai para .parcial.ass");

        // E o cache grava VAZIO na fala pendente — é isso que a faz ser reenviada ao LLM na
        // próxima execução, em vez de congelar em inglês para sempre.
        JsonNode cacheGravado = new ObjectMapper()
            .readTree(raiz.resolve("cache").resolve("AnimeTeste").resolve("ep.cache.json").toFile());
        JsonNode entradaDaFala = null;
        for (JsonNode entradaCache : cacheGravado.get("entradas")) {
            if ("Heavy{\\b1}!".equals(entradaCache.get("original").asText())) {
                entradaDaFala = entradaCache;
                break;
            }
        }
        assertNotNull(entradaDaFala, "a fala descartada deveria estar no cache: " + cacheGravado);
        assertEquals("", entradaDaFala.get("traduzido").asText(),
            "pendência grava vazio: o inglês NÃO é aceito como tradução e a fala será retentada");
    }

    /**
     * PROPÓSITO DE NEGÓCIO: a retradução liberada refaz o episódio do zero SEM tirar o cache
     * anterior do caminho ativo. O operador que aborta a execução no meio (ou perde energia)
     * continua com o cache que tinha — a geração anterior só é substituída quando a nova
     * estiver inteira e gravada atomicamente.
     *
     * <h2>Invariantes do domínio</h2>
     * <ul>
     *   <li>A geração anterior é descartada EM MEMÓRIA: o LLM é chamado de novo mesmo com o
     *       cache presente e válido no disco.</li>
     *   <li>O arquivo de cache ativo existe antes, durante e depois — nunca some.</li>
     *   <li>UMA única cópia vai para {@code backups/traducao-cache} por execução: a do
     *       início. Sem a guarda, a gravação final copiaria o mesmo arquivo outra vez.</li>
     * </ul>
     *
     * <h2>Comportamento em caso de falha</h2>
     * LLM não rechamado ⇒ a retradução virou no-op e o cache velho foi reaproveitado.
     * Cache ausente ⇒ voltou a janela que apagou o S00E02 do 08th MS Team em 2026-07-22.
     * Dois backups ⇒ o mesmo arquivo está sendo duplicado em disco a cada retradução.
     */
    /**
     * PROPÓSITO DE NEGÓCIO: a regra do itálico (22/08/2026) tem de valer também para a fala que
     * NUNCA passa pelo LLM. O cache de Paulo foi gravado ANTES da regra e guarda milhares de
     * traduções com {@code \i1}; sem esta etapa a legenda entregue continuaria em itálico e a
     * regra só valeria para episódio traduzido do zero.
     *
     * <p>INVARIANTES DO DOMÍNIO: o cache NÃO é reescrito — ele guarda o que o modelo produziu.
     * Quem aplica a regra é a saída. E o LLM continua não sendo chamado: a economia do cache
     * não pode ser perdida por causa da formatação.
     *
     * <p>COMPORTAMENTO EM CASO DE FALHA: itálico no arquivo entregue, ou chamada ao LLM que o
     * cache deveria ter evitado, reprova.
     */
    @Test
    @DisplayName("Itálico gravado no cache ANTES da regra não chega ao arquivo entregue")
    void italicoVindoDoCacheAntigoNaoChegaAoArquivoEntregue() throws Exception {
        Path entrada = escreverAss("ep.ass", "Hello there", "How are you");
        montar(new FakeLlmPort()).processar(entrada, false, gerenciadorMontado.snapshotAtivo());

        // Simula o cache ANTERIOR à regra: valor traduzido gravado com itálico.
        Path arquivoCache = raiz.resolve("cache").resolve("AnimeTeste").resolve("ep.cache.json");
        String json = Files.readString(arquivoCache, StandardCharsets.UTF_8);
        String comItalico = json.replace("fala traduzida", "{\\\\i1}fala traduzida{\\\\i0}");
        assertNotEquals(json, comItalico, "o cache tem de conter a fala traduzida para o teste valer");
        Files.writeString(arquivoCache, comItalico, StandardCharsets.UTF_8);

        FakeLlmPort segunda = new FakeLlmPort();
        montar(segunda).processar(entrada, false, gerenciadorMontado.snapshotAtivo());

        assertEquals(0, segunda.chamadas.get(), "a fala veio do cache: o LLM não pode ser chamado");
        Path saida = raiz.resolve("saida").resolve("ep_PT-BR.ass");
        String conteudo = Files.readString(saida, StandardCharsets.UTF_8);
        assertTrue(conteudo.contains("fala traduzida"), "o texto traduzido tem de continuar lá");
        assertFalse(conteudo.contains("{\\i1}"), "itálico do cache não pode chegar ao arquivo");
        assertFalse(conteudo.contains("{\\i0}"), "nem o desliga do par");

        // TELEMETRIA: sem o contador, "o italico sumiu" e afirmacao, nao evidencia. E o
        // preservado tem de ser 0 MEDIDO (nao null): aqui nenhuma fala herda italico do
        // Style:, e "medi e deu zero" nao pode sair com a cara de "nao medi".
        TelemetriaTraducao tel = telemetriaCaptor.ultima;
        assertNotNull(tel, "a telemetria do episodio deve ter sido registrada");
        assertEquals(2, tel.falasItalicoRemovido(),
            "as duas falas do cache tinham italico e as duas foram limpas");
        assertEquals(0, tel.falasItalicoPreservado(),
            "nenhuma abstencao neste episodio — e 0 MEDIDO, nao null");
    }

    @Test
    void retraducaoLiberadaIgnoraOCacheSemApagarOArquivoAtivo() throws Exception {
        Path entrada = escreverAss("ep.ass", "Hello there", "How are you");
        montar(new FakeLlmPort()).processar(entrada, false, gerenciadorMontado.snapshotAtivo());

        Path arquivoCache = raiz.resolve("cache").resolve("AnimeTeste").resolve("ep.cache.json");
        assertTrue(Files.exists(arquivoCache), "a primeira execução deve ter gravado o cache");
        Path raizBackup = DiretorioBaseKronos.resolver("backups", "traducao-cache");
        long backupsAntes = contarPastas(raizBackup);

        FakeLlmPort segunda = new FakeLlmPort();
        montar(segunda).processar(entrada, true, gerenciadorMontado.snapshotAtivo()); // retradução explicitamente liberada

        assertEquals(1, segunda.chamadas.get(),
            "a retradução ignora o cache em MEMÓRIA e reenvia as falas ao LLM");
        assertTrue(Files.exists(arquivoCache),
            "o cache ativo não pode ser removido: uma interrupção deixaria o episódio sem nada");
        assertEquals(backupsAntes + 1, contarPastas(raizBackup),
            "exatamente UMA cópia de segurança por retradução — a do início, sem duplicar na gravação");
    }

    private static long contarPastas(Path raizBackup) throws IOException {
        if (!Files.exists(raizBackup)) {
            return 0;
        }
        try (Stream<Path> s = Files.list(raizBackup)) {
            return s.filter(Files::isDirectory).count();
        }
    }

    /**
     * PROPÓSITO DE NEGÓCIO (reforço de terminologia): quando o original contém um termo
     * canônico da lore ("Legion") e o modelo o traduziu (forma-ruim), o reforço
     * determinístico restaura a grafia oficial na saída publicada — sem depender do LLM.
     *
     * <p>INVARIANTES DO DOMÍNIO: o mapa da lore ativa só age quando o original tem o termo
     * canônico; a saída final traz "Legion", não a forma-ruim.
     *
     * <p>COMPORTAMENTO EM CASO DE FALHA (antes do wire): a saída manteria a forma-ruim.
     */
    @Test
    void reforcoDeterministicoRestauraTermoCanonicoNaSaida() throws Exception {
        FakeLlmPort llm = new FakeLlmPort();
        ProcessarArquivoUseCase uc = montar(llm);
        // Original com o termo canônico "Legion"; o dublê traduz para "fala traduzida",
        // que o mapa do ContextoTeste restaura para "Legion".
        Path entrada = escreverAss("ep.ass", "The Legion attacks at dawn");

        uc.processar(entrada, false, gerenciadorMontado.snapshotAtivo());

        Path saida = raiz.resolve("saida").resolve("ep_PT-BR.ass");
        assertTrue(Files.exists(saida), "saída final deve existir");
        String conteudo = Files.readString(saida, StandardCharsets.UTF_8);
        assertTrue(conteudo.contains("Legion"), "o termo canônico deve ser restaurado na saída");
        assertFalse(conteudo.contains("fala traduzida"), "a forma-ruim não pode permanecer");
    }

    /**
     * PROPÓSITO DE NEGÓCIO (fallback online — rede de segurança): com o modo online LIGADO,
     * uma fala de diálogo que o LLM deixou pendente é recuperada pelo provedor externo,
     * validada canonicamente e publicada — o episódio conclui em vez de ficar PARCIAL.
     *
     * <p>INVARIANTES DO DOMÍNIO: só a fala pendente vai ao provedor; a resposta entra no
     * ASS/cache final; status {@code CONCLUIDO}; sem pendências no KPI.
     *
     * <p>COMPORTAMENTO EM CASO DE FALHA (antes do wire): a fala continuaria pendente e o
     * episódio seria PARCIAL — este teste falharia.
     */
    @Test
    void fallbackLigadoRecuperaDialogoPendenteEConclui() throws Exception {
        fallbackOnlineAtivo = true;
        fallbackPort = portaFallback(original -> original.contains("KEEPME")
            ? Optional.of("KEEPME permanece aqui") : Optional.empty());
        FakeLlmPort llm = new FakeLlmPort();
        ProcessarArquivoUseCase uc = montar(llm);
        Path entrada = escreverAss("ep.ass", "Hello there", "KEEPME stays");

        ResultadoTraducaoArquivo r = uc.processar(entrada, false, gerenciadorMontado.snapshotAtivo());

        assertEquals(StatusArquivoTraducao.CONCLUIDO, r.status(),
            "com a fala recuperada pelo fallback, o episódio conclui");
        Path finalPtBr = raiz.resolve("saida").resolve("ep_PT-BR.ass");
        assertTrue(Files.exists(finalPtBr), "saída final deve ser publicada após recuperação");
        String conteudo = Files.readString(finalPtBr, StandardCharsets.UTF_8);
        assertTrue(conteudo.contains("permanece aqui"), "a tradução do fallback deve entrar na legenda");
        assertFalse(conteudo.contains("KEEPME stays"), "a fala original não pode permanecer");

        TelemetriaTraducao tel = telemetriaCaptor.ultima;
        assertEquals("CONCLUIDO", tel.statusFinal());
        assertTrue(tel.pendenciasPorCausa().isEmpty(), "não deve sobrar pendência após recuperação");
    }

    /**
     * PROPÓSITO DE NEGÓCIO (fallback online — falha externa é segura): com o modo LIGADO
     * mas o provedor indisponível (resposta vazia), a fala continua pendente exatamente
     * como sem o fallback — nada é publicado à força.
     *
     * <p>INVARIANTES DO DOMÍNIO: porta vazia ⇒ status {@code PARCIAL}; original preservado
     * no artefato parcial.
     *
     * <p>COMPORTAMENTO EM CASO DE FALHA: publicar tradução inexistente reprova.
     */
    @Test
    void fallbackLigadoComRedeVaziaMantemPendente() throws Exception {
        fallbackOnlineAtivo = true;
        fallbackPort = portaFallback(original -> Optional.empty()); // simula rede fora/recusa
        FakeLlmPort llm = new FakeLlmPort();
        ProcessarArquivoUseCase uc = montar(llm);
        Path entrada = escreverAss("ep.ass", "Hello there", "KEEPME stays");

        ResultadoTraducaoArquivo r = uc.processar(entrada, false, gerenciadorMontado.snapshotAtivo());

        assertEquals(StatusArquivoTraducao.PARCIAL, r.status(),
            "sem resposta do provedor, a fala continua pendente");
        assertFalse(Files.exists(raiz.resolve("saida").resolve("ep_PT-BR.ass")),
            "saída final não pode ser publicada com pendência");
    }

    /**
     * PROPÓSITO DE NEGÓCIO (visibilidade do fallback): com o modo online LIGADO e falas de
     * diálogo pendentes, o pipeline ANUNCIA na saída dinâmica que está enviando ao scraping
     * do Google — para o operador não ficar no escuro durante a recuperação de último recurso.
     *
     * <p>INVARIANTES DO DOMÍNIO: a linha de aviso cita "tradutor de máquina" e é emitida antes
     * da tentativa externa, apenas quando há diálogo pendente e o fallback está ligado.
     *
     * <p>COMPORTAMENTO EM CASO DE FALHA (antes do wire): nenhuma narração — este teste falharia.
     */
    @Test
    void fallbackLigadoAnunciaEnvioAoScrapingDoGoogle() throws Exception {
        fallbackOnlineAtivo = true;
        fallbackPort = portaFallback(original -> original.contains("KEEPME")
            ? Optional.of("KEEPME permanece aqui") : Optional.empty());
        FakeLlmPort llm = new FakeLlmPort();
        LoggerCapturador logger = new LoggerCapturador();
        ProcessarArquivoUseCase uc = montar(llm, logger);
        Path entrada = escreverAss("ep.ass", "Hello there", "KEEPME stays");

        uc.processar(entrada, false, gerenciadorMontado.snapshotAtivo());

        assertTrue(logger.mensagens.stream().anyMatch(m -> m.contains("tradutor de máquina")),
            "deve anunciar o envio ao tradutor de máquina quando há diálogo pendente e o fallback está ligado");
    }

    /**
     * PROPÓSITO DE NEGÓCIO (fallback desligado é 100% local e silencioso): com o modo online
     * DESLIGADO, mesmo havendo fala pendente, o pipeline NÃO pode anunciar envio ao Google —
     * evita mentir que houve chamada externa quando nenhuma ocorre.
     *
     * <p>INVARIANTES DO DOMÍNIO: nenhuma linha citando "tradutor de máquina" é emitida.
     *
     * <p>COMPORTAMENTO EM CASO DE FALHA: um anúncio incondicional (sem checar {@code ativo()})
     * reprova este teste.
     */
    @Test
    void fallbackDesligadoNaoAnunciaEnvioAoGoogle() throws Exception {
        fallbackOnlineAtivo = false;
        fallbackPort = portaFallback(original -> Optional.empty());
        FakeLlmPort llm = new FakeLlmPort();
        LoggerCapturador logger = new LoggerCapturador();
        ProcessarArquivoUseCase uc = montar(llm, logger);
        Path entrada = escreverAss("ep.ass", "Hello there", "KEEPME stays");

        uc.processar(entrada, false, gerenciadorMontado.snapshotAtivo());

        assertTrue(logger.mensagens.stream().noneMatch(m -> m.contains("tradutor de máquina")),
            "com o fallback desligado, nada de fallback deve ser anunciado");
    }

    /**
     * PROPÓSITO DE NEGÓCIO (fonte contaminada — guarda de idioma): uma fala cuja FONTE já está
     * em PT é mantida como está, SEM ir ao LLM, e o episódio conclui — em vez de virar eco/recusa
     * pendente. É a defesa direta contra os arquivos "inglês" meio-traduzidos.
     *
     * <p>INVARIANTES DO DOMÍNIO: a fala já-PT sai verbatim; status {@code CONCLUIDO}; um aviso
     * informa que ela foi mantida sem retradução.
     *
     * <p>COMPORTAMENTO EM CASO DE FALHA (antes do wire): a fala já-PT iria ao LLM, voltaria como
     * eco/recusa e o episódio ficaria PARCIAL — este teste falharia.
     */
    @Test
    void fonteJaEmPortuguesEhMantidaSemRetraducao() throws Exception {
        FakeLlmPort llm = new FakeLlmPort();
        LoggerCapturador logger = new LoggerCapturador();
        ProcessarArquivoUseCase uc = montar(llm, logger);
        Path entrada = escreverAss("ep.ass", "Não é que ele fosse desagradável.");

        ResultadoTraducaoArquivo r = uc.processar(entrada, false, gerenciadorMontado.snapshotAtivo());

        assertEquals(StatusArquivoTraducao.CONCLUIDO, r.status(),
            "a linha já-PT não vira pendência; o episódio conclui sem chamar o LLM");
        Path finalPtBr = raiz.resolve("saida").resolve("ep_PT-BR.ass");
        String conteudo = Files.readString(finalPtBr, StandardCharsets.UTF_8);
        assertTrue(conteudo.contains("Não é que ele fosse desagradável."),
            "a fala já em PT deve ser mantida verbatim na saída");
        assertTrue(logger.mensagens.stream().anyMatch(m -> m.contains("já no idioma-alvo")),
            "deve anunciar que manteve fala(s) já no idioma-alvo");
    }

    /**
     * PROPÓSITO DE NEGÓCIO (segurança da guarda): uma fala em INGLÊS nunca pode ser classificada
     * como já-no-alvo — deixar inglês sem traduzir é o erro que a guarda precisa evitar.
     *
     * <p>INVARIANTES DO DOMÍNIO: nenhuma mensagem "já no idioma-alvo" é emitida para inglês; a
     * fala segue o caminho normal de tradução.
     *
     * <p>COMPORTAMENTO EM CASO DE FALHA: um detector agressivo demais (que pulasse inglês)
     * reprova este teste.
     */
    @Test
    void fonteInglesNaoEhTratadaComoJaTraduzida() throws Exception {
        FakeLlmPort llm = new FakeLlmPort();
        LoggerCapturador logger = new LoggerCapturador();
        ProcessarArquivoUseCase uc = montar(llm, logger);
        Path entrada = escreverAss("ep.ass", "It is because the war changed everything.");

        uc.processar(entrada, false, gerenciadorMontado.snapshotAtivo());

        assertTrue(logger.mensagens.stream().noneMatch(m -> m.contains("já no idioma-alvo")),
            "linha inglesa não pode ser classificada como já no idioma-alvo");
    }

    /**
     * PROPÓSITO DE NEGÓCIO: uma falha real na SUBSTITUIÇÃO ATÔMICA final (o caminho
     * de destino já está ocupado por um diretório) não pode publicar uma legenda
     * inválida, não pode destruir o que ocupa o destino e não pode deixar
     * temporários órfãos; caracteriza o contrato de erro da publicação atômica.
     *
     * <p>INVARIANTES DO DOMÍNIO: a falha vira {@link ArquivoLegendaException}; o
     * temporário criado pelo escritor é sempre removido; o diretório que ocupa o
     * destino permanece; a entrada permanece byte a byte intacta; o cache não é
     * persistido (a gravação ocorre depois da publicação e nunca é alcançada).
     *
     * <p>COMPORTAMENTO EM CASO DE FALHA: qualquer regressão que publique saída
     * final, deixe {@code .tmp} órfão, altere a entrada ou troque o tipo da
     * exceção falha a suíte.
     */
    @Test
    void falhaNaPublicacaoAtomicaNaoPublicaNemDeixaTemporario() throws Exception {
        FakeLlmPort llm = new FakeLlmPort();
        ProcessarArquivoUseCase uc = montar(llm);
        Path entrada = escreverAss("ep.ass", "Hello there", "How are you");
        String origemAntes = Files.readString(entrada, StandardCharsets.UTF_8);
        // raiz/saida é um diretório NORMAL; o ponto de falha é o move atômico final:
        // o caminho de destino esperado (ep_PT-BR.ass) já está ocupado por um
        // diretório, então o escritor cria/escreve o temporário e falha ao
        // substituir o destino. Sem ACL, lock, antivírus, sleep ou privilégio.
        Path pastaSaida = Files.createDirectories(raiz.resolve("saida"));
        Path destinoOcupado = Files.createDirectory(pastaSaida.resolve("ep_PT-BR.ass"));

        assertThrows(ArquivoLegendaException.class, () -> uc.processar(entrada, false, gerenciadorMontado.snapshotAtivo()));

        assertTrue(Files.isDirectory(destinoOcupado), "o diretorio que ocupava o destino permanece intacto");
        try (Stream<Path> itens = Files.list(pastaSaida)) {
            boolean sobrouTemporario = itens.anyMatch(p -> {
                String nome = p.getFileName().toString();
                return Files.isRegularFile(p) && nome.startsWith("ep_PT-BR.ass") && nome.endsWith(".tmp");
            });
            assertFalse(sobrouTemporario, "nenhum temporario de publicacao pode permanecer em raiz/saida");
        }
        assertEquals(origemAntes, Files.readString(entrada, StandardCharsets.UTF_8), "entrada byte a byte intacta");
        assertFalse(Files.exists(raiz.resolve("cache").resolve("AnimeTeste").resolve("ep.cache.json")),
            "cache nao e persistido quando a publicacao falha antes da gravacao do cache");
    }

    /**
     * PROPÓSITO DE NEGÓCIO: o cancelamento cooperativo NO MEIO do processamento
     * (após o primeiro lote concluir e antes do segundo) encerra o episódio como
     * parcial, preservando no cache exatamente o progresso já traduzido e sem
     * publicar a saída final — caracteriza a semântica de "Parar" da UI.
     *
     * <p>INVARIANTES DO DOMÍNIO: o próprio dublê da LLM dispara a interrupção após
     * a primeira chamada; o laço detecta antes do segundo lote e lança
     * {@link TraducaoParcialException}; exatamente uma chamada ao LLM ocorre; o
     * segundo lote nunca é enviado; o cache parcial contém e só contém as falas do
     * primeiro lote; nenhuma saída final {@code _PT-BR} é publicada; a entrada
     * permanece intacta.
     *
     * <p>COMPORTAMENTO EM CASO DE FALHA: qualquer chamada extra ao LLM, publicação
     * final, divergência de quantidade/conteúdo do cache parcial ou alteração da
     * entrada falha a suíte. Determinístico: a interrupção é marcada pelo dublê
     * (sem sleep nem corrida) e a barra de progresso é desligada para não consumir
     * o flag antes da verificação do laço.
     */
    @Test
    void cancelamentoNoMeioPreservaProgressoParcialDoPrimeiroLote() throws Exception {
        FakeLlmPort llm = new FakeLlmPort(true);
        ProcessarArquivoUseCase uc = montar(llm, new ConsoleUILoggerSilencioso());
        // 21 falas distintas e traduzíveis: com tamanhoLote=20, geram 2 lotes
        // (20 + 1); o primeiro conclui, o segundo nunca é enviado.
        // Rótulos ALFABÉTICOS de propósito: o dublê troca todo o texto por "fala traduzida",
        // apagando qualquer número. Desde a invariância numérica (Bug 3), uma fala numerada
        // traduzida assim seria — corretamente — recusada, e este teste não é sobre isso.
        String[] falas = new String[21];
        for (int i = 0; i < falas.length; i++) {
            falas[i] = "Line " + (char) ('A' + i / 26) + (char) ('A' + i % 26);
        }
        Path entrada = escreverAss("ep.ass", falas);
        String origemAntes = Files.readString(entrada, StandardCharsets.UTF_8);

        try {
            assertThrows(TraducaoParcialException.class, () -> uc.processar(entrada, false, gerenciadorMontado.snapshotAtivo()));
        } finally {
            Thread.interrupted();
        }

        assertEquals(1, llm.chamadas.get(), "apenas o primeiro lote pode ser enviado ao LLM");
        // Cache parcial: exatamente as 20 falas do primeiro lote (Line AA..Line AT),
        // sem a fala do segundo lote (Line AU). Carregado com a mesma proveniência.
        Path arquivoCache = raiz.resolve("cache").resolve("AnimeTeste").resolve("ep.cache.json");
        assertTrue(Files.exists(arquivoCache), "o progresso parcial deve ter sido persistido no cache");
        ProvenienciaCache prov = new ProvenienciaCache(
            ProvenienciaCache.SCHEMA_ATUAL, "caracterizacao",
            ProvenienciaCache.hashDe("Traduza fielmente para PT-BR."),
            "modelo-teste", "en", "pt-BR");
        var mapa = new CacheTraducaoService(new ObjectMapper()).carregar(arquivoCache, prov).mapa();
        assertEquals(20, mapa.size(), "o cache parcial deve conter as 20 falas do primeiro lote");
        for (int i = 0; i < 20; i++) {
            String chave = "Line " + (char) ('A' + i / 26) + (char) ('A' + i % 26);
            assertTrue(mapa.containsKey(chave), "cache parcial deve conter " + chave);
            assertEquals("fala traduzida", mapa.get(chave), "conteudo traduzido do primeiro lote");
        }
        assertFalse(mapa.containsKey("Line AU"), "a fala do segundo lote nao pode estar no cache");

        assertFalse(Files.exists(raiz.resolve("saida").resolve("ep_PT-BR.ass")),
            "nenhuma saida final pode ser publicada apos o cancelamento");
        assertEquals(origemAntes, Files.readString(entrada, StandardCharsets.UTF_8), "entrada intacta");
    }

    /**
     * PROPÓSITO DE NEGÓCIO (F0/R4 — elegibilidade): um evento que é puro desenho
     * vetorial ({@code \p1} do Aegisub) nunca é considerado traduzível — não é
     * enviado ao LLM e permanece idêntico na saída. Fixa a blindagem de
     * elegibilidade antes de extrair {@code isTraduzivel} para um colaborador.
     *
     * <p>INVARIANTES DO DOMÍNIO: com o desenho como único evento, nenhum lote é
     * enviado (LLM chamado zero vezes), o status é {@code CONCLUIDO} e o comando de
     * desenho continua byte a byte na saída publicada.
     *
     * <p>COMPORTAMENTO EM CASO DE FALHA: qualquer regressão que envie o desenho ao
     * LLM ou o altere na saída falha a suíte.
     */
    @Test
    void desenhoVetorialNaoEhTraduzidoNemEnviadoAoLlm() throws Exception {
        FakeLlmPort llm = new FakeLlmPort();
        ProcessarArquivoUseCase uc = montar(llm);
        String desenho = "{\\p1}m 5 5 l 40 5 l 40 40 l 5 40{\\p0}";
        Path entrada = escreverAss("ep.ass", desenho);

        ResultadoTraducaoArquivo r = uc.processar(entrada, false, gerenciadorMontado.snapshotAtivo());

        assertEquals(StatusArquivoTraducao.CONCLUIDO, r.status());
        assertEquals(0, llm.chamadas.get(), "desenho vetorial nunca deve ir ao LLM");
        Path saida = raiz.resolve("saida").resolve("ep_PT-BR.ass");
        assertTrue(Files.readString(saida, StandardCharsets.UTF_8).contains("m 5 5 l 40 5"),
            "o desenho vetorial deve permanecer intacto na saida");
    }

    /**
     * PROPÓSITO DE NEGÓCIO (F0/R4 — elegibilidade): um letreiro/título animado
     * quadro a quadro (tag de efeito pesada + pouco texto visível + o mesmo texto
     * repetido muitas vezes) é blindado antes do LLM. Fixa a heurística de
     * repetição antes de extrair {@code isTraduzivel}.
     *
     * <p>INVARIANTES DO DOMÍNIO: cinco ocorrências idênticas (>= limiar de
     * repetição) do letreiro não geram nenhuma chamada ao LLM e o texto visível
     * original permanece na saída, sem marcador de tradução.
     *
     * <p>COMPORTAMENTO EM CASO DE FALHA: se a heurística deixar de bloquear, o LLM
     * é chamado e "fala traduzida" aparece na saída — a suíte falha.
     */
    private static final String CARTAO_86 = "{\\an2\\pos(960,930)\\fnNewCinemaB Std D\\fs80\\fsp2\\bord2"
        + "\\1a&HFF&\\3a&H30&\\blur5\\fad(1500,0)\\b0\\fscx90}May 22nd, Stellar Year 2148";

    private Path escreverCartaoEmInstantes(int... segundosDeInicio) throws IOException {
        Path pasta = Files.createDirectories(raiz.resolve("AnimeTeste").resolve("legendas_originais"));
        StringBuilder sb = new StringBuilder(CABECALHO_ASS);
        for (int s : segundosDeInicio) {
            for (int camada = 0; camada < 3; camada++) {
                sb.append(String.format("Dialogue: %d,0:00:%02d.00,0:00:%02d.00,Signs,,0,0,0,,%s%n",
                    camada, s, s + 3, CARTAO_86));
            }
        }
        Path arquivo = pasta.resolve("ep.ass");
        Files.writeString(arquivo, sb.toString(), StandardCharsets.UTF_8);
        return arquivo;
    }

    /**
     * MEDIDO NA AUDITORIA DE 25/09/2026: no 86 E01, "May 22nd, Stellar Year 2148" aparece em 2
     * cenas × 3 camadas = 6 linhas, batia no limiar 5 de "letreiro animado" e ficava em inglês,
     * enquanto "May 20th" (1 cena × 3 camadas) era traduzido. Camadas de um mesmo cartão começam
     * juntas: não são animação quadro a quadro.
     */
    @Test
    @DisplayName("cartao estatico em 2 cenas x 3 camadas E traduzido")
    void cartaoEstaticoEmDuasCenasEhTraduzido() throws Exception {
        FakeLlmPort llm = new FakeLlmPort();
        Path entrada = escreverCartaoEmInstantes(1, 20);

        montar(llm).processar(entrada, false, gerenciadorMontado.snapshotAtivo());

        String conteudo = Files.readString(raiz.resolve("saida").resolve("ep_PT-BR.ass"), StandardCharsets.UTF_8);
        assertTrue(llm.chamadas.get() > 0, "o cartao estatico tem de ir ao LLM");
        assertFalse(conteudo.contains("May 22nd"), "nenhuma camada do cartao pode ficar em ingles:\n" + conteudo);
    }

    /**
     * CASO-CONTROLE DE FRONTEIRA (A1) da anterior: o MESMO texto e as mesmas tags, mas começando em
     * 6 instantes distintos — animação quadro a quadro. Continua preservado.
     */
    @Test
    @DisplayName("o mesmo cartao em 6 instantes distintos continua preservado como animacao")
    void cartaoEmSeisInstantesContinuaPreservado() throws Exception {
        FakeLlmPort llm = new FakeLlmPort();
        Path entrada = escreverCartaoEmInstantes(1, 5, 9, 13, 17, 21);

        montar(llm).processar(entrada, false, gerenciadorMontado.snapshotAtivo());

        assertEquals(0, llm.chamadas.get(), "animacao quadro a quadro nao vai ao LLM");
    }

    @Test
    void letreiroAnimadoRepetidoNaoEhTraduzido() throws Exception {
        FakeLlmPort llm = new FakeLlmPort();
        ProcessarArquivoUseCase uc = montar(llm);
        String letreiro = "{\\clip(0,0,300,300)\\t(0,1000,\\frx360)\\pos(20,20)}Hi";
        Path entrada = escreverAss("ep.ass", letreiro, letreiro, letreiro, letreiro, letreiro);

        uc.processar(entrada, false, gerenciadorMontado.snapshotAtivo());

        assertEquals(0, llm.chamadas.get(), "letreiro animado repetido nao deve ir ao LLM");
        Path saida = raiz.resolve("saida").resolve("ep_PT-BR.ass");
        String conteudo = Files.readString(saida, StandardCharsets.UTF_8);
        assertFalse(conteudo.contains("fala traduzida"), "nenhuma fala traduzida deve aparecer");
        assertTrue(conteudo.contains("Hi"), "o texto original do letreiro deve permanecer");
    }

    /**
     * PROPÓSITO DE NEGÓCIO (F0/R5 — reuso de cache): uma entrada de cache cujo
     * "traduzido" é o próprio original em inglês (aparência de fala não traduzida)
     * NÃO pode ser reaproveitada — deve ser reenviada ao LLM. Fixa a política de
     * reuso antes de extrair {@code isCacheReaproveitavel}.
     *
     * <p>INVARIANTES DO DOMÍNIO: com o cache semeado apontando "Hello there" para
     * ele mesmo, a execução ainda assim chama o LLM uma vez e publica a tradução
     * ("fala traduzida"), em vez de reusar o conteúdo suspeito.
     *
     * <p>COMPORTAMENTO EM CASO DE FALHA: se o cache suspeito for reaproveitado, o
     * LLM não é chamado e a saída mantém o inglês — a suíte falha.
     */
    @Test
    void cacheComFalaAparentandoNaoTraduzidaNaoEhReaproveitado() throws Exception {
        Path entrada = escreverAss("ep.ass", "Hello there");
        Path cachePath = raiz.resolve("cache").resolve("AnimeTeste").resolve("ep.cache.json");
        Files.createDirectories(cachePath.getParent());
        ProvenienciaCache prov = new ProvenienciaCache(
            ProvenienciaCache.SCHEMA_ATUAL, "caracterizacao",
            ProvenienciaCache.hashDe("Traduza fielmente para PT-BR."),
            "modelo-teste", "en", "pt-BR");
        new CacheTraducaoService(new ObjectMapper()).salvar(cachePath, prov,
            List.of(new EntradaCache(0, "Default", "Hello there", "Hello there", "en", "pt-BR")));

        FakeLlmPort llm = new FakeLlmPort();
        montar(llm).processar(entrada, false, gerenciadorMontado.snapshotAtivo());

        assertEquals(1, llm.chamadas.get(),
            "fala em cache que aparenta nao-traduzida deve ser reenviada ao LLM");
        Path saida = raiz.resolve("saida").resolve("ep_PT-BR.ass");
        assertTrue(Files.readString(saida, StandardCharsets.UTF_8).contains("fala traduzida"),
            "a saida deve conter a retraducao, nao o cache suspeito");
    }

    /**
     * PROPÓSITO DE NEGÓCIO: a entrada de cache recusada por um defeito de par com conserto conhecido
     * (aqui a polaridade: "Sim..." para "No...") é CONSERTADA no reaproveitamento, sem chamar o
     * modelo. Medido na 2.1 do 0083 em 09/10/2026: mandada ao modelo, a fala voltava com o mesmo
     * defeito (consertado na tentativa) e o RESTO piorado ("Core Fighter" virou "Lutador Central").
     *
     * <p>INVARIANTES DO DOMÍNIO: zero chamadas ao modelo; o arquivo gravado traz a fala consertada
     * e o cache regravado também — a próxima execução reaproveita sem consertar de novo (A6: a
     * prova lê os dois arquivos do disco).
     *
     * <p>COMPORTAMENTO EM CASO DE FALHA: sem o conserto, o modelo é chamado (1 chamada) e a saída
     * traz a fala do modelo falso, não "Não... é tarde demais.".
     */
    @Test
    void cacheComPolaridadeInvertidaEhConsertadoSemChamarOModelo() throws Exception {
        Path entrada = escreverAss("ep.ass", "No... it's too late.");
        Path cachePath = raiz.resolve("cache").resolve("AnimeTeste").resolve("ep.cache.json");
        Files.createDirectories(cachePath.getParent());
        ProvenienciaCache prov = new ProvenienciaCache(
            ProvenienciaCache.SCHEMA_ATUAL, "caracterizacao",
            ProvenienciaCache.hashDe("Traduza fielmente para PT-BR."),
            "modelo-teste", "en", "pt-BR");
        new CacheTraducaoService(new ObjectMapper()).salvar(cachePath, prov,
            List.of(new EntradaCache(0, "Default", "No... it's too late.", "Sim... é tarde demais.", "en", "pt-BR")));

        FakeLlmPort llm = new FakeLlmPort();
        montar(llm).processar(entrada, false, gerenciadorMontado.snapshotAtivo());

        assertEquals(0, llm.chamadas.get(), "o conserto da entrada de cache nao chama o modelo");
        String gravado = Files.readString(raiz.resolve("saida").resolve("ep_PT-BR.ass"), StandardCharsets.UTF_8);
        assertTrue(gravado.contains("Não... é tarde demais."), gravado);
        assertFalse(gravado.contains("Sim... é tarde demais."), "o defeito nao pode chegar ao arquivo");
        assertTrue(Files.readString(cachePath, StandardCharsets.UTF_8).contains("Não... é tarde demais."),
            "o cache regravado guarda a fala consertada");
    }

    /**
     * PROPÓSITO DE NEGÓCIO: a entrada de cache da fala de dois locutores que perdeu a separação
     * ("Por que você\Ndesviou? - Sim.", Reconguista I, 09/10/2026) volta ao modelo POR LOCUTOR e o
     * arquivo sai com um travessão e uma linha por locutor.
     * <p>INVARIANTES DO DOMÍNIO: a prova lê o arquivo gravado (A6); a antiga torta não chega a ele.
     * <p>COMPORTAMENTO EM CASO DE FALHA: reaproveitar a torta reprova.
     */
    @Test
    void cacheDeLocutoresTortoEhRefeitoPorLocutor() throws Exception {
        Path entrada = escreverAss("ep.ass", "- Why did you dodge?\\N- Yes.");
        semearCacheDeUmaFala("- Why did you dodge?\\N- Yes.", "Por que você\\Ndesviou? - Sim.");

        FakeLlmPort llm = new FakeLlmPort();
        montar(llm).processar(entrada, false, gerenciadorMontado.snapshotAtivo());

        String gravado = Files.readString(raiz.resolve("saida").resolve("ep_PT-BR.ass"), StandardCharsets.UTF_8);
        assertTrue(gravado.contains("- fala traduzida?\\N- fala traduzida"), gravado);
        assertFalse(gravado.contains("desviou? - Sim."), "a entrada torta do cache chegou ao arquivo");
    }

    /**
     * PROPÓSITO DE NEGÓCIO: o lado de lá da mesma fronteira — a refação por locutor NÃO fecha (o
     * modelo parte a fala de um locutor em duas linhas, todas as tentativas). Sem o recuo, a fala
     * iria ao inglês; com ele, sai a tradução antiga do cache, que é português.
     * <p>INVARIANTES DO DOMÍNIO: nunca inglês quando havia português aprovado pelo portão final.
     * <p>COMPORTAMENTO EM CASO DE FALHA: o arquivo gravado com o original em inglês reprova.
     */
    @Test
    void refacaoPorLocutorQueNaoFechaRecuaParaOCacheEnaoParaOIngles() throws Exception {
        Path entrada = escreverAss("ep.ass", "- Why did you dodge?\\N- Yes.");
        semearCacheDeUmaFala("- Why did you dodge?\\N- Yes.", "Por que você\\Ndesviou? - Sim.");

        FakeLlmPort llm = FakeLlmPort.queParteAFala("Yes");
        montar(llm).processar(entrada, false, gerenciadorMontado.snapshotAtivo());

        Path saida = raiz.resolve("saida");
        String gravado;
        try (var arquivos = Files.list(saida)) {
            Path arquivo = arquivos.filter(p -> p.getFileName().toString().startsWith("ep_PT-BR")).findFirst().orElseThrow();
            gravado = Files.readString(arquivo, StandardCharsets.UTF_8);
        }
        assertTrue(gravado.contains("Por que você\\Ndesviou? - Sim."), gravado);
        assertFalse(gravado.contains("Why did you dodge?"), "a fala voltou ao ingles");
    }

    private void semearCacheDeUmaFala(String original, String traduzido) throws IOException {
        Path cachePath = raiz.resolve("cache").resolve("AnimeTeste").resolve("ep.cache.json");
        Files.createDirectories(cachePath.getParent());
        ProvenienciaCache prov = new ProvenienciaCache(
            ProvenienciaCache.SCHEMA_ATUAL, "caracterizacao",
            ProvenienciaCache.hashDe("Traduza fielmente para PT-BR."),
            "modelo-teste", "en", "pt-BR");
        new CacheTraducaoService(new ObjectMapper()).salvar(cachePath, prov,
            List.of(new EntradaCache(0, "Default", original, traduzido, "en", "pt-BR")));
    }

    /**
     * PROPÓSITO DE NEGÓCIO (guarda obra×contexto — DIVERGÊNCIA BLOQUEIA): um arquivo que mora
     * numa pasta reconhecida por OUTRO contexto não pode ser traduzido com a lore selecionada.
     * É a reprodução direta do incidente medido nesta árvore: 15 caches de Gundam 0083 gravados
     * com {@code contextoId = "guilty_crown"} e ~4.442 entradas produzidas sob a lore errada,
     * porque nada comparava a obra do ARQUIVO com o contexto ATIVO.
     *
     * <h2>Invariantes do domínio</h2>
     * <ul>
     *   <li>O bloqueio acontece ANTES do LLM: zero chamadas ao modelo.</li>
     *   <li>Nada é escrito: nenhum arquivo de cache e nenhuma legenda de saída.</li>
     *   <li>A mensagem nomeia a obra do caminho, o contexto esperado e o contexto ativo — o
     *       operador precisa saber qual clique corrigir.</li>
     * </ul>
     *
     * <h2>Comportamento em caso de falha</h2>
     * Se a execução prosseguir, o cache nasce carimbado com a lore errada e o dano só aparece
     * depois, na legenda publicada — que foi exatamente como o incidente passou despercebido.
     */
    /**
     * L3 da auditoria de 08/10/2026 (reproduzido em execução): com a lore de OUTRA obra e a caixa
     * "ignorar lore existente", o portão era dispensado, a obra era traduzida com a terminologia
     * errada e a saída definitiva regravada. A dispensa só vale para a tradução sem lore. O
     * CONTROLE (A1) é o mesmo arquivo com o contexto {@code sem_lore} e a caixa: esse passa.
     */
    @Test
    void dispensaDoPortaoSoValeParaTraducaoSemLore() throws Exception {
        FakeLlmPort llm = new FakeLlmPort();
        ProcessarArquivoUseCase uc = montar(llm);
        gerenciadorMontado.definirContextoAtivo("caracterizacao");
        Path entrada = escreverAssEmObra("ObraAlheia", "ep.ass", "Hello there", "How are you");

        assertThrows(ObraDivergenteDoContextoException.class,
            () -> uc.processar(entrada, false, gerenciadorMontado.snapshotAtivo(), true),
            "lore de outra obra com a caixa marcada tem de ser BLOQUEADA");
        assertEquals(0, llm.chamadas.get(), "nenhuma fala pode chegar ao LLM sob a lore errada");

        org.traducao.projeto.lore.domain.SnapshotContexto semLore = new org.traducao.projeto.lore.domain.SnapshotContexto(
            "sem_lore", "Sem lore", "Traduza sem lore.", "", Set.of(), java.util.Map.of(), Set.of(), java.util.Map.of());
        uc.processar(entrada, false, semLore, true);
        assertTrue(llm.chamadas.get() > 0, "CONTROLE: sem lore com a caixa marcada traduz normalmente");
    }

    @Test
    void obraDivergenteDoContextoAtivoBloqueiaAntesDoLlmESemEscreverNada() throws Exception {
        FakeLlmPort llm = new FakeLlmPort();
        ProcessarArquivoUseCase uc = montar(llm);
        gerenciadorMontado.definirContextoAtivo("caracterizacao");
        // Pasta reconhecida por ContextoObraAlheia; contexto ativo é o de caracterização.
        Path entrada = escreverAssEmObra("ObraAlheia", "ep.ass", "Hello there", "How are you");

        ObraDivergenteDoContextoException erro = assertThrows(
            ObraDivergenteDoContextoException.class, () -> uc.processar(entrada, false, gerenciadorMontado.snapshotAtivo()));

        assertEquals(0, llm.chamadas.get(), "nenhuma fala pode chegar ao LLM sob a lore errada");
        assertFalse(Files.exists(raiz.resolve("cache").resolve("ObraAlheia").resolve("ep.cache.json")),
            "nenhum cache pode ser gravado com a proveniência da obra errada");
        assertFalse(Files.exists(raiz.resolve("saida").resolve("ep_PT-BR.ass")),
            "nenhuma legenda pode ser publicada");
        assertTrue(erro.getMessage().contains("ObraAlheia"), "a mensagem cita a obra do caminho: " + erro.getMessage());
        assertTrue(erro.getMessage().contains("obra_alheia"), "a mensagem cita o contexto esperado: " + erro.getMessage());
        assertTrue(erro.getMessage().contains("caracterizacao"), "a mensagem cita o contexto ativo: " + erro.getMessage());
    }

    /**
     * PROPÓSITO DE NEGÓCIO (guarda obra×contexto — CAMINHO IGUAL PASSA): quando a pasta é
     * reconhecida pelo contexto ativo, a guarda é transparente: o episódio traduz normalmente.
     *
     * <p>INVARIANTES DO DOMÍNIO: status {@code CONCLUIDO}, saída final publicada e nenhum aviso
     * de bloqueio — a guarda não pode custar nada ao caminho feliz.
     *
     * <p>COMPORTAMENTO EM CASO DE FALHA: uma guarda que bloqueie o caso correto para o pipeline
     * inteiro; por isso o caso feliz é testado explicitamente, e não só por tabela.
     */
    @Test
    void obraQueConfereComOContextoAtivoTraduzNormalmente() throws Exception {
        FakeLlmPort llm = new FakeLlmPort();
        LoggerCapturador logger = new LoggerCapturador();
        ProcessarArquivoUseCase uc = montar(llm, logger);
        gerenciadorMontado.definirContextoAtivo("caracterizacao");
        // "AnimeTeste" é justamente o apelido de pasta declarado por ContextoTeste.
        Path entrada = escreverAss("ep.ass", "Hello there", "How are you");

        ResultadoTraducaoArquivo r = uc.processar(entrada, false, gerenciadorMontado.snapshotAtivo());

        assertEquals(StatusArquivoTraducao.CONCLUIDO, r.status());
        assertTrue(Files.exists(raiz.resolve("saida").resolve("ep_PT-BR.ass")));
        assertTrue(logger.mensagens.stream().noneMatch(m -> m.contains("[ BLOQUEADO ]")),
            "obra que confere não pode gerar bloqueio: " + logger.mensagens);
        assertTrue(logger.mensagens.stream().noneMatch(m -> m.contains("[ AVISO ]")),
            "obra que confere não pode gerar aviso de checagem pulada: " + logger.mensagens);
    }

    /**
     * PROPÓSITO DE NEGÓCIO (guarda obra×contexto — CAMINHO DESCONHECIDO AVISA E SEGUE): uma
     * pasta que nenhum contexto reconhece não é prova de nada. A guarda AVISA que pulou a
     * checagem e deixa passar — adivinhar a obra a partir de um caminho desconhecido seria
     * repetir, automatizado, o erro que ela existe para impedir.
     *
     * <h2>Invariantes do domínio</h2>
     * <ul>
     *   <li>A tradução conclui normalmente: nada é bloqueado.</li>
     *   <li>O aviso aparece na saída dinâmica, para o operador saber que passou sem conferência.</li>
     *   <li>O aviso NÃO entra na lista de avisos do episódio — se entrasse, todo arquivo de obra
     *       ainda sem vocabulário declarado viraria {@code PARCIAL}, e "não sei julgar" não é
     *       tradução incompleta.</li>
     * </ul>
     *
     * <h2>Comportamento em caso de falha</h2>
     * Bloquear aqui pararia toda obra que ainda não declarou apelidos de pasta; silenciar aqui
     * daria a falsa impressão de que o arquivo foi conferido.
     */
    @Test
    void obraNaoReconhecidaAvisaESegueSemMarcarParcial() throws Exception {
        FakeLlmPort llm = new FakeLlmPort();
        LoggerCapturador logger = new LoggerCapturador();
        ProcessarArquivoUseCase uc = montar(llm, logger);
        gerenciadorMontado.definirContextoAtivo("caracterizacao");
        Path entrada = escreverAssEmObra("PastaSemVocabulario", "ep.ass", "Hello there", "How are you");

        ResultadoTraducaoArquivo r = uc.processar(entrada, false, gerenciadorMontado.snapshotAtivo());

        assertEquals(StatusArquivoTraducao.CONCLUIDO, r.status(),
            "caminho desconhecido não pode bloquear nem marcar o episódio como parcial");
        assertTrue(Files.exists(raiz.resolve("saida").resolve("ep_PT-BR.ass")),
            "a saída final deve ser publicada normalmente");
        assertTrue(logger.mensagens.stream()
                .anyMatch(m -> m.startsWith("[ AVISO ]") && m.contains("PastaSemVocabulario")),
            "a checagem pulada precisa ficar visível para o operador: " + logger.mensagens);

        TelemetriaTraducao tel = telemetriaCaptor.ultima;
        assertTrue(tel.errosOcorridos().isEmpty(),
            "o aviso da guarda não pode entrar em errosOcorridos (marcaria o episódio PARCIAL): "
                + tel.errosOcorridos());
    }

    /**
     * PROPÓSITO DE NEGÓCIO (snapshot ÚNICO POR JOB — ponta a ponta): o contexto é resolvido
     * UMA vez, no início do lote, e vale até o último arquivo. Trocar o contexto ativo GLOBAL
     * no MEIO do lote — outro clique do operador, ou outra rota (correção, revisão, karaokê)
     * que compartilha o mesmo gerenciador — não pode mudar a lore, o prompt nem a proveniência
     * dos arquivos que ainda faltam. É o modo de falha exato do incidente medido nesta árvore.
     *
     * <h2>Invariantes do domínio</h2>
     * <ul>
     *   <li>O caso de uso NÃO consulta o gerenciador: recebe o snapshot por parâmetro, o mesmo
     *       objeto para os dois arquivos do lote.</li>
     *   <li>Os DOIS caches continuam legíveis sob a proveniência do contexto do início —
     *       inclusive o do arquivo traduzido DEPOIS da troca global.</li>
     * </ul>
     *
     * <h2>Comportamento em caso de falha</h2>
     * Se o contexto voltar a ser lido do gerenciador dentro do laço, o segundo arquivo passa a
     * declarar a obra nova e a carga com a proveniência do início devolve mapa vazio.
     */
    @Test
    void snapshotDoJobNaoMudaQuandoOContextoGlobalTrocaNoMeioDoLote() throws Exception {
        FakeLlmPort llm = new FakeLlmPort();
        ProcessarArquivoUseCase uc = montar(llm);
        gerenciadorMontado.definirContextoAtivo("caracterizacao");
        // Exatamente o que o TraducaoController faz ANTES do laço de arquivos: congela o
        // contexto a partir do id EXPLÍCITO pedido, sem passar pelo estado global.
        SnapshotContexto contextoDoJob = gerenciadorMontado.snapshotPorId("caracterizacao");
        Path primeiro = escreverAss("ep1.ass", "Hello there", "How are you");
        Path segundo = escreverAss("ep2.ass", "Hello there", "How are you");

        uc.processar(primeiro, false, contextoDoJob);
        // Troca da obra ativa NO MEIO do lote, entre um arquivo e o próximo.
        gerenciadorMontado.definirContextoAtivo("obra_alheia");
        uc.processar(segundo, false, contextoDoJob);

        ProvenienciaCache doInicio = new ProvenienciaCache(
            ProvenienciaCache.SCHEMA_ATUAL, "caracterizacao",
            ProvenienciaCache.hashDe("Traduza fielmente para PT-BR."),
            "modelo-teste", "en", "pt-BR");
        CacheTraducaoService cacheService = new CacheTraducaoService(new ObjectMapper());
        Path pastaCache = raiz.resolve("cache").resolve("AnimeTeste");

        assertEquals(2, cacheService.carregar(pastaCache.resolve("ep1.cache.json"), doInicio).mapa().size(),
            "o primeiro arquivo do lote carimba a proveniência do contexto congelado no início");
        assertEquals(2, cacheService.carregar(pastaCache.resolve("ep2.cache.json"), doInicio).mapa().size(),
            "o arquivo traduzido DEPOIS da troca global carimba a MESMA proveniência do início "
                + "do lote — o snapshot do job não observa o contexto ativo");
        assertEquals("obra_alheia", gerenciadorMontado.obterIdContextoAtivo(),
            "o contexto global realmente mudou no meio do lote (senão o teste não prova nada)");
    }

    /**
     * PROPÓSITO DE NEGÓCIO: logger de progresso silencioso para os testes —
     * desliga a barra de progresso de terceiros ({@code me.tongfei:progressbar}),
     * que, quando ativa, consome o flag de interrupção da thread e impediria
     * caracterizar o cancelamento cooperativo disparado pelo dublê da LLM.
     *
     * <p>INVARIANTES DO DOMÍNIO: nunca constrói a barra; os demais métodos do
     * {@link ConsoleUILogger} passam a operar no ramo {@code pb == null}, sem
     * qualquer interação capaz de consumir a interrupção.
     *
     * <p>COMPORTAMENTO EM CASO DE FALHA: não lança; a inicialização de lotes é um
     * no-op deliberado.
     */
    private static final class ConsoleUILoggerSilencioso extends ConsoleUILogger {
        @Override
        public synchronized void iniciarLotes(int totalLotes, String nomeEpisodio) {
            // no-op: não constrói a barra de progresso (evita consumir a interrupção).
        }
    }

    /**
     * PROPÓSITO DE NEGÓCIO: captura as mensagens emitidas por {@code uiLogger.log(...)}
     * para que os testes possam asserir a narração da saída dinâmica (ex.: o anúncio do
     * fallback Google) sem depender de capturar {@code System.out}.
     *
     * <p>INVARIANTES DO DOMÍNIO: não constrói a barra de progresso e registra cada mensagem
     * exatamente uma vez, na ordem recebida.
     *
     * <p>COMPORTAMENTO EM CASO DE FALHA: não lança; lista thread-safe para tolerar emissões
     * de qualquer thread do pipeline.
     */
    private static final class LoggerCapturador extends ConsoleUILogger {
        final java.util.List<String> mensagens = new java.util.concurrent.CopyOnWriteArrayList<>();

        @Override
        public synchronized void iniciarLotes(int totalLotes, String nomeEpisodio) {
            // no-op: sem barra de progresso nos testes.
        }

        @Override
        public synchronized void log(String mensagem) {
            mensagens.add(mensagem);
        }
    }
}

