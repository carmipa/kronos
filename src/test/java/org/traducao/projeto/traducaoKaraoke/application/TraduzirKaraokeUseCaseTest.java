package org.traducao.projeto.traducaoKaraoke.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.traducao.projeto.telemetria.OperacaoTelemetria;
import org.traducao.projeto.telemetria.TelemetriaService;
import org.traducao.projeto.legenda.application.DetectorEfeitoKaraokeService;
import org.traducao.projeto.qualidadeTraducao.application.ValidadorTraducaoService;
import org.traducao.projeto.llm.domain.Lote;
import org.traducao.projeto.llm.domain.StatusLlm;
import org.traducao.projeto.llm.domain.TraducaoLote;
import org.traducao.projeto.llm.domain.LlmPort;
import org.traducao.projeto.cachetraducao.infrastructure.CacheTraducaoService;
import org.traducao.projeto.cachetraducao.domain.ProvenienciaCache;
import org.traducao.projeto.lore.domain.ProvedorContexto;
import org.traducao.projeto.lore.domain.SnapshotContexto;
import org.traducao.projeto.lore.infrastructure.GerenciadorContexto;
import org.traducao.projeto.legenda.infrastructure.EscritorLegendaAss;
import org.traducao.projeto.legenda.infrastructure.LeitorLegendaAss;
import org.traducao.projeto.qualidadeTraducao.application.MascaradorTags;
import org.traducao.projeto.core.presentation.web.LogStreamService;
import org.traducao.projeto.traducaoKaraoke.domain.DesfechoKaraoke;
import org.traducao.projeto.traducaoKaraoke.domain.ResultadoTraducaoKaraoke;
import org.traducao.projeto.traducaoKaraoke.domain.StatusExecucaoKaraoke;
import org.traducao.projeto.traducaoKaraoke.domain.TraducaoKaraokeException;
import org.traducao.projeto.traducaoKaraoke.infrastructure.TraducaoKaraokePersistencia;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import org.traducao.projeto.qualidadeTraducao.application.LoreAtivaFake;

class TraduzirKaraokeUseCaseTest {

    private static final String NOME_ARQUIVO = "Anime Teste - S01E01.ass";
    private static final String CONTEXTO_08TH = "gundam_08ms";
    private static final String PROMPT_08TH = "PROMPT EXCLUSIVO DO 08TH MS TEAM";
    private static final String PROMPT_ZZ = "PROMPT EXCLUSIVO DO GUNDAM ZZ";

    private Path tempDir;
    private Path pastaEntrada;
    private TraduzirKaraokeUseCase useCase;
    private LlmPortFake llmFake;
    private MockPersistencia persistenciaMock;
    private RegistroDaExecucao registroMock;

    private record ContextoTeste(String id, String nome, String prompt) implements ProvedorContexto {
        @Override public String getId() { return id; }
        @Override public String getNomeExibicao() { return nome; }
        @Override public String obterPromptSistema() { return prompt; }
    }

    /** Traduções fixas com contador de chamadas para provar reuso de cache e dry-run sem LLM. */
    static class LlmPortFake implements LlmPort {
        int chamadasTraduzir = 0;
        private final Map<String, String> respostas = Map.of(
            "Even if the world ends tomorrow", "Mesmo que o mundo acabe amanhã"
        );

        @Override
        public TraducaoLote traduzir(Lote lote) {
            chamadasTraduzir++;
            String original = lote.linhasOriginais().getFirst();
            String traduzido = respostas.getOrDefault(original, "Tradução simulada");
            return new TraducaoLote(lote.idLote(), List.of(traduzido), true, null);
        }

        @Override
        public StatusLlm verificarDisponibilidade() {
            return new StatusLlm(true, true, "modelo de teste carregado");
        }

        @Override
        public Optional<String> revisarConcordancia(String original, String traducao, List<String> problemas) {
            return Optional.empty();
        }

        @Override
        public Optional<String> corrigirTraducao(String original, String traducao, String motivo) {
            return Optional.empty();
        }
    }

    static class MockLogStream extends LogStreamService {
        @Override
        public void publicarLog(String canal, String mensagem) {
            // silencioso nos testes
        }
    }

    static class MockTelemetria extends TelemetriaService {
        @Override
        public void finalizarOperacao(OperacaoTelemetria operacao, Path pastaEntrada, String prefixo, String conteudo) {
            // não persiste telemetria em disco nos testes
        }

        @Override
        public void registrarAlucinacaoPrevenida() {
            // silencioso
        }
    }

    static class MockPersistencia extends TraducaoKaraokePersistencia {
        /** Guarda o desfecho para os testes de telemetria de falha. */
        final AtomicReference<DesfechoKaraoke> desfechoCapturado = new AtomicReference<>();

        @Override
        public Path salvarManifesto(
            Path origem,
            Path destino,
            List<ResultadoTraducaoKaraoke> resultados,
            long duracaoMs,
            SnapshotContexto contexto,
            ProvenienciaCache proveniencia,
            DesfechoKaraoke desfecho
        ) {
            desfechoCapturado.set(desfecho);
            return null; // não grava manifesto em logs/ nos testes
        }
    }

    /** Servidor de LLM fora do ar: {@code null} é o que o pipeline trata como indisponível. */
    static class LlmForaDoAr extends LlmPortFake {
        @Override
        public StatusLlm verificarDisponibilidade() {
            return null;
        }
    }

    /**
     * Aponta os DOIS consumidores do LLM para o mesmo dublê.
     *
     * <p>Desde 2026-08-19 o LLM tem dois papéis nesta fatia: o use case o consulta para saber se
     * o servidor está no ar e qual o modelo ativo, e {@code TradutorDeLetraKaraoke} o usa para
     * traduzir de fato. Trocar só um dos dois faria o teste medir um mundo e o codigo rodar
     * outro — foi exatamente o que quebrou estes dois testes quando o colaborador foi extraido.
     */
    private void usarLlm(LlmPort llm) {
        useCase.llmPort = llm;
        useCase.tradutorDeLetra.llmPort = llm;
    }

    @BeforeEach
    void setUp() throws IOException {
        tempDir = Files.createTempDirectory("test_traducao_karaoke");
        pastaEntrada = Files.createDirectories(tempDir.resolve("legendas"));
        escreverLegenda(pastaEntrada.resolve(NOME_ARQUIVO));

        llmFake = new LlmPortFake();

        useCase = new TraduzirKaraokeUseCase();
        useCase.leitor = new LeitorLegendaAss();
        useCase.escritor = new EscritorLegendaAss();
        useCase.llmPort = llmFake;
        useCase.gerenciadorContexto = new GerenciadorContexto(List.of(
            new ContextoTeste(CONTEXTO_08TH, "Mobile Suit Gundam: The 08th MS Team", PROMPT_08TH),
            new ContextoTeste("gundam_zz", "Mobile Suit Gundam ZZ", PROMPT_ZZ)));
        useCase.classificador = new ClassificadorLetraKaraokeService(new DetectorEfeitoKaraokeService());

        // O colaborador que leva a linha ao LLM saiu do use case em 2026-08-19. Aqui ele recebe
        // os MESMOS dubles — se recebesse outros, o teste passaria a medir dois mundos.
        TradutorDeLetraKaraoke tradutorDeLetra = new TradutorDeLetraKaraoke();
        tradutorDeLetra.llmPort = llmFake;
        tradutorDeLetra.mascarador = new MascaradorTags();
        tradutorDeLetra.validador = new ValidadorTraducaoService(LoreAtivaFake.vazia());
        tradutorDeLetra.telemetriaService = new MockTelemetria();
        tradutorDeLetra.logStream = new MockLogStream();
        useCase.tradutorDeLetra = tradutorDeLetra;
        // Os colaboradores extraidos em 2026-08-19. Todos recebem os MESMOS dubles do use case —
        // dois mundos de duble fariam o teste medir uma coisa e o codigo rodar outra.
        registroMock = new RegistroDaExecucao();
        registroMock.persistencia = persistenciaMock = new MockPersistencia();
        registroMock.telemetriaService = new MockTelemetria();
        registroMock.logStream = new MockLogStream();
        // O acervo do dataset entra como duble de MEMORIA, nunca nulo: acrescentarAoDataset
        // engole RuntimeException de proposito, entao colaborador ausente faria o caminho do
        // dataset passar inteiro pelo catch e o teste ficaria verde sem exercitar uma linha.
        registroMock.acervoDataset = new AcervoKaraokeCapturado();
        useCase.registro = registroMock;

        CacheDoArquivo cacheDoArquivo = new CacheDoArquivo();
        cacheDoArquivo.cacheService = new CacheTraducaoService(new ObjectMapper());
        cacheDoArquivo.logStream = new MockLogStream();
        cacheDoArquivo.idiomaOriginal = Optional.empty();
        cacheDoArquivo.idiomaTraduzido = Optional.empty();
        cacheDoArquivo.diretorioCache = Optional.of(tempDir.resolve("cache").toString());
        useCase.cacheDoArquivo = cacheDoArquivo;

        // Sem corretor ortografico: o dicionario do sistema nao entra em teste de unidade, e a
        // lista NOMINAL da fatia continua valendo — e o que MontadorEventoFinal garante.
        useCase.montador = new MontadorEventoFinal();

        useCase.logStream = new MockLogStream();
        useCase.telemetriaService = new MockTelemetria();
        useCase.idiomaOriginal = Optional.empty();
        useCase.idiomaTraduzido = Optional.empty();
    }

    @AfterEach
    void tearDown() throws IOException {
        try (var walk = Files.walk(tempDir)) {
            walk.sorted((a, b) -> b.compareTo(a)).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException e) {
                    // melhor esforço na limpeza do temp
                }
            });
        }
    }

    private void escreverLegenda(Path destino) throws IOException {
        String conteudo = String.join("\r\n",
            "[Script Info]",
            "Title: Teste",
            "",
            "[Events]",
            "Format: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text",
            "Dialogue: 0,0:00:01.00,0:00:03.00,Default,,0,0,0,,Fala de diálogo intocável.",
            "Dialogue: 0,0:01:00.00,0:01:05.00,OP - Romaji,,0,0,0,,kimi no heart ni fly away",
            "Dialogue: 0,0:01:00.00,0:01:05.00,OP - English,,0,0,0,,Even if the world ends tomorrow",
            "");
        Files.writeString(destino, conteudo, StandardCharsets.UTF_8);
    }

    /**
     * CASO DOENTE da telemetria de falha, 2026-08-14.
     *
     * <p>O registro do manifesto era condicionado a {@code !resultados.isEmpty()} e vinha DEPOIS
     * do laço. Consequência medida na auditoria: LLM fora do ar, destino não criável ou todos os
     * arquivos falhando produziam <b>zero artefato</b> — o pior desfecho possível saía
     * indistinguível de "não havia nada a fazer", e o motivo vivia só numa linha de texto dentro
     * de um log de 78 MB. Se alguém devolver aquela condição ou tirar o {@code finally}, este
     * teste cai.
     */
    @Test
    void abortoPorLlmForaDoArAindaRegistraOManifesto() {
        useCase.llmPort = new LlmForaDoAr();

        assertThrows(TraducaoKaraokeException.class, () -> useCase.aplicar(pastaEntrada, CONTEXTO_08TH),
            "o chamador continua sabendo que deu errado — o rastro é ADICIONAL, não substituto");

        DesfechoKaraoke desfecho = persistenciaMock.desfechoCapturado.get();
        assertNotNull(desfecho, "NENHUM manifesto foi registrado numa execucao abortada, "
            + "e essa e justamente a execucao que mais precisa de rastro");
        assertEquals(StatusExecucaoKaraoke.ABORTADA, desfecho.status());
        assertTrue(desfecho.motivo() != null && desfecho.motivo().contains("LLM"),
            "o motivo tem de dizer o que houve, nao 'erro desconhecido': " + desfecho.motivo());
        assertEquals(DesfechoKaraoke.EstadoDicionario.NAO_CONSULTADO, desfecho.estadoDicionario(),
            "abortou antes de traduzir qualquer linha: o dicionario NAO foi consultado, "
                + "que e diferente de estar ausente");
        assertTrue(desfecho.falhas().isEmpty(),
            "nenhum arquivo chegou a ser processado — lista vazia aqui e informacao, nao omissao");
    }

    /**
     * CONTRA-TESTE do anterior. Sem ele, um {@code status} preso em {@code ABORTADA} passaria
     * despercebido — a guarda tem de saber dizer NÃO e também SIM.
     */
    @Test
    void execucaoNormalRegistraManifestoComoCompleta() {
        useCase.aplicar(pastaEntrada, CONTEXTO_08TH);

        DesfechoKaraoke desfecho = persistenciaMock.desfechoCapturado.get();
        assertNotNull(desfecho, "execucao normal tambem tem de registrar desfecho");
        assertEquals(StatusExecucaoKaraoke.COMPLETA, desfecho.status());
        assertNull(desfecho.motivo(), "execucao completa nao tem motivo de aborto");
        assertTrue(desfecho.falhas().isEmpty(), "nenhum arquivo falhou nesta legenda de teste");
    }

    /**
     * PROPÓSITO DE NEGÓCIO: pasta só com SRT (o Reconguista do teste ponta a ponta de 09/10/2026)
     * não tem camada de karaokê. Até então a 4.1 criava a pasta de destino VAZIA e fechava com
     * "[SUCESSO]", que lê como "fiz o karaokê".
     * <p>INVARIANTES DO DOMÍNIO: nenhuma pasta criada; o console explica o SRT; o banner diz NADA A
     * FAZER; com legenda .ass o banner continua SUCESSO (contra-teste no mesmo método).
     * <p>COMPORTAMENTO EM CASO DE FALHA: pasta vazia criada, ou SUCESSO sem arquivo, reprova.
     */
    @Test
    void pastaSoComSrtNaoCriaDestinoNemDizSucesso() throws IOException {
        Path soSrt = Files.createDirectories(tempDir.resolve("traducao_srt"));
        Files.writeString(soSrt.resolve("filme_PT-BR.srt"), "1\r\n00:00:01,000 --> 00:00:02,000\r\nOla\r\n");
        List<String> console = new java.util.ArrayList<>();
        useCase.logStream = new LogStreamService() {
            @Override
            public void publicarLog(String canal, String mensagem) {
                console.add(mensagem);
            }
        };

        assertTrue(useCase.aplicar(soSrt, CONTEXTO_08TH).isEmpty());

        assertFalse(Files.exists(TraduzirKaraokeUseCase.resolverPastaSaida(soSrt)),
            "a pasta de destino nao pode nascer vazia");
        assertTrue(console.stream().anyMatch(l -> l.contains("1 legenda(s) .srt")), () -> "sem o aviso do SRT: " + console);
        assertTrue(console.stream().anyMatch(l -> l.startsWith("[NADA A FAZER]")), () -> "banner errado: " + console);
        assertTrue(console.stream().noneMatch(l -> l.startsWith("[SUCESSO]")), () -> "SUCESSO sem arquivo: " + console);

        assertEquals("SUCESSO", TraduzirKaraokeUseCase.rotuloDoBanner(StatusExecucaoKaraoke.COMPLETA, 1, 0, 0));
        assertEquals("ATENÇÃO", TraduzirKaraokeUseCase.rotuloDoBanner(StatusExecucaoKaraoke.COMPLETA, 0, 1, 0));
    }

    /**
     * PROPÓSITO DE NEGÓCIO: a palavra inventada pelo modelo ({@code Açãoaria}, Break Blade 1, teste
     * ponta a ponta de 09/10/2026) vai ao manifesto e ao resumo do arquivo, e a letra continua
     * TRADUZIDA no arquivo gravado — recusar a devolveria ao inglês.
     * <p>INVARIANTES DO DOMÍNIO: verificação depois da última transformação (o arquivo é relido);
     * sem dicionário, o resumo diz NÃO VERIFICADO (contra-teste no mesmo método).
     * <p>COMPORTAMENTO EM CASO DE FALHA: sem hunspell instalado, o caso real PULA.
     */
    @Test
    void palavraInventadaVaiAoManifestoEaTraducaoSegue() throws IOException {
        List<String> console = new java.util.ArrayList<>();
        useCase.logStream = new LogStreamService() {
            @Override
            public void publicarLog(String canal, String mensagem) {
                console.add(mensagem);
            }
        };
        usarLlm(new LlmPortFake() {
            @Override
            public TraducaoLote traduzir(Lote lote) {
                chamadasTraduzir++;
                return new TraducaoLote(lote.idLote(), List.of("Açãoaria em todos os meus sentimentos."), true, null);
            }
        });

        useCase.aplicar(pastaEntrada, CONTEXTO_08TH);
        assertTrue(console.stream().anyMatch(l -> l.contains("fora do dicionário: NÃO VERIFICADO")),
            () -> "sem dicionario o resumo tem de dizer NAO VERIFICADO: " + console);

        // A segunda execucao reaproveita a letra do CACHE: a conferencia vale para ela tambem.
        console.clear();
        useCase.corretorOrtografico = new org.traducao.projeto.core.texto.dicionarioOrtografia.CorretorOrtograficoLegenda();
        List<ResultadoTraducaoKaraoke> resultados = useCase.aplicar(pastaEntrada, CONTEXTO_08TH);
        org.junit.jupiter.api.Assumptions.assumeFalse(
            console.stream().anyMatch(l -> l.contains("fora do dicionário: NÃO VERIFICADO")),
            "hunspell ausente — NÃO VERIFICADO");

        assertTrue(resultados.getFirst().avisos().stream()
                .anyMatch(a -> a.startsWith("Palavra fora do dicionario [Açãoaria]")),
            () -> "a palavra inventada nao chegou ao manifesto: " + resultados.getFirst().avisos());
        assertTrue(console.stream().anyMatch(l -> l.contains("fora do dicionário: 1")),
            () -> "o resumo do arquivo nao contou a letra: " + console);
        String saida = Files.readString(TraduzirKaraokeUseCase.resolverPastaSaida(pastaEntrada)
            .resolve(NOME_ARQUIVO), StandardCharsets.UTF_8);
        assertTrue(saida.contains("Açãoaria em todos os meus sentimentos."),
            "a traducao foi recusada — a letra voltaria ao ingles");
        assertFalse(saida.contains("OP - English,,0,0,0,,Even if the world ends tomorrow"),
            "a letra ficou em ingles");
    }

    /**
     * Gundam ZZ, 14/10/2026: o diálogo publicado em {@code VnBook-Antiqua} (fonte vietnamita antiga)
     * mostrava "Nóo, ộ a atraỗóo!" no lugar de "Não, é a atração!". Ordem de Paulo: fonte assim
     * REPROVA e pede a 1.3 Troca de Tipo de Legenda — antes de gastar LLM e sem gravar saída.
     */
    @Test
    void fonteVietnamitaNoDialogoReprovaOArquivoEPedeA13() throws IOException {
        Files.writeString(pastaEntrada.resolve(NOME_ARQUIVO), legendaComFonteDoDialogo("VnBook-Antiqua"),
            StandardCharsets.UTF_8);

        List<ResultadoTraducaoKaraoke> resultados = useCase.aplicar(pastaEntrada, CONTEXTO_08TH);

        assertTrue(resultados.isEmpty(), "o arquivo reprovado nao entra como processado");
        DesfechoKaraoke desfecho = persistenciaMock.desfechoCapturado.get();
        assertEquals(1, desfecho.falhas().size(), "a reprovacao fica no manifesto: " + desfecho);
        String motivo = desfecho.falhas().getFirst().motivo();
        assertTrue(motivo.contains("VnBook-Antiqua") && motivo.contains("1.3"), motivo);
        assertEquals(0, llmFake.chamadasTraduzir, "reprovou antes de gastar LLM");
        assertFalse(Files.exists(TraduzirKaraokeUseCase.resolverPastaSaida(pastaEntrada).resolve(NOME_ARQUIVO)),
            "nada gravado para o arquivo reprovado");
    }

    /** CASO-CONTROLE (A1): a MESMA legenda com o diálogo em Arial é processada e gravada. */
    @Test
    void mesmaLegendaComDialogoEmArialSegueNormal() throws IOException {
        Files.writeString(pastaEntrada.resolve(NOME_ARQUIVO), legendaComFonteDoDialogo("Arial"), StandardCharsets.UTF_8);

        List<ResultadoTraducaoKaraoke> resultados = useCase.aplicar(pastaEntrada, CONTEXTO_08TH);

        assertEquals(1, resultados.size());
        assertTrue(persistenciaMock.desfechoCapturado.get().falhas().isEmpty());
        assertTrue(Files.exists(TraduzirKaraokeUseCase.resolverPastaSaida(pastaEntrada).resolve(NOME_ARQUIVO)));
    }

    /**
     * Banimento de 14/10/2026 (Paulo: "banir esse tipo de fonte"): o diálogo em Arial, mas um
     * {@code Comment:} com {@code \fn} vietnamita antigo e um estilo antigo que nenhuma fala usa. A
     * 4.1 reprova do mesmo jeito, antes do LLM e sem gravar, pedindo a 1.3 — que troca os dois.
     */
    @Test
    void fonteVietnamitaEmComentarioOuEstiloSemFalaTambemReprova() throws IOException {
        String legenda = legendaComFonteDoDialogo("Arial")
            .replace("Style: OP - English,FrancophilSans,63", "Style: OP - English,FrancophilSans,63\r\nStyle: Velho,.VnTime,40")
            .replace("Dialogue: 0,0:00:01.00", "Comment: 0,0:00:00.50,0:00:01.00,Default,,0,0,0,,{\\fnVNI-Times}nota\r\nDialogue: 0,0:00:01.00");
        Files.writeString(pastaEntrada.resolve(NOME_ARQUIVO), legenda, StandardCharsets.UTF_8);

        List<ResultadoTraducaoKaraoke> resultados = useCase.aplicar(pastaEntrada, CONTEXTO_08TH);

        assertTrue(resultados.isEmpty(), "o arquivo reprovado nao entra como processado");
        String motivo = persistenciaMock.desfechoCapturado.get().falhas().getFirst().motivo();
        assertTrue(motivo.contains(".VnTime") && motivo.contains("VNI-Times") && motivo.contains("1.3"), motivo);
        assertEquals(0, llmFake.chamadasTraduzir, "reprovou antes de gastar LLM");
        assertFalse(Files.exists(TraduzirKaraokeUseCase.resolverPastaSaida(pastaEntrada).resolve(NOME_ARQUIVO)),
            "nada gravado para o arquivo reprovado");
    }

    private static String legendaComFonteDoDialogo(String fonte) {
        return String.join("\r\n",
            "[Script Info]",
            "Title: Teste",
            "",
            "[V4+ Styles]",
            "Format: Name, Fontname, Fontsize",
            "Style: Default," + fonte + ",75",
            "Style: OP - Romaji,FrancophilSans,63",
            "Style: OP - English,FrancophilSans,63",
            "",
            "[Events]",
            "Format: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text",
            "Dialogue: 0,0:00:01.00,0:00:03.00,Default,,0,0,0,,Fala de diálogo intocável.",
            "Dialogue: 0,0:01:00.00,0:01:05.00,OP - Romaji,,0,0,0,,kimi no heart ni fly away",
            "Dialogue: 0,0:01:00.00,0:01:05.00,OP - English,,0,0,0,,Even if the world ends tomorrow",
            "");
    }

    @Test
    void aplicarTraduzCamadaInglesaEPreservaRomajiEDialogo() throws IOException {
        List<ResultadoTraducaoKaraoke> resultados = useCase.aplicar(pastaEntrada, CONTEXTO_08TH);

        assertEquals(1, resultados.size());
        ResultadoTraducaoKaraoke r = resultados.getFirst();
        assertEquals(1, r.preservadasOriginalJapones(), "romaji com inglês misturado deve ser preservado");
        assertEquals(1, r.paraTraduzir());
        assertEquals(1, r.traduzidas());
        assertEquals(0, r.mantidasSemTraducao());

        Path destino = TraduzirKaraokeUseCase.resolverPastaSaida(pastaEntrada).resolve(NOME_ARQUIVO);
        assertTrue(Files.exists(destino), "legenda traduzida deve ser gravada na pasta irmã");
        String saida = Files.readString(destino, StandardCharsets.UTF_8);
        assertTrue(saida.contains("kimi no heart ni fly away"), "letra original não pode ser alterada");
        assertTrue(saida.contains("Mesmo que o mundo acabe amanhã"), "camada inglesa deve virar PT-BR");
        assertFalse(saida.contains("Even if the world ends tomorrow"), "inglês da camada de tradução não pode sobrar");
        assertTrue(saida.contains("Fala de diálogo intocável."), "diálogo não pode ser alterado");

        String original = Files.readString(pastaEntrada.resolve(NOME_ARQUIVO), StandardCharsets.UTF_8);
        assertTrue(original.contains("Even if the world ends tomorrow"), "arquivo de entrada deve permanecer intacto");
    }

    @Test
    void contextoSelecionadoFicaCongeladoMesmoSeGlobalMudarDuranteExecucao() throws IOException {
        escreverLegendaDuasLinhasInglesas(pastaEntrada.resolve(NOME_ARQUIVO));
        List<String> promptsRecebidos = new ArrayList<>();

        usarLlm(new LlmPortFake() {
            @Override
            public TraducaoLote traduzir(Lote lote, Double temperaturaOverride, String promptSistemaCongelado) {
                promptsRecebidos.add(promptSistemaCongelado);
                useCase.gerenciadorContexto.definirContextoAtivo("gundam_zz");
                return super.traduzir(lote);
            }
        });
        useCase.gerenciadorContexto.definirContextoAtivo("gundam_zz");

        useCase.aplicar(pastaEntrada, CONTEXTO_08TH);

        assertEquals(List.of(PROMPT_08TH, PROMPT_08TH), promptsRecebidos,
            "todas as chamadas devem usar a lore escolhida no início, nunca o contexto global");
        assertEquals("gundam_zz", useCase.gerenciadorContexto.obterIdContextoAtivo(),
            "o teste precisa provar que o global realmente estava em outra obra");

        Path cache = tempDir.resolve("cache").resolve("karaoke")
            .resolve("Anime Teste - S01E01.cache.json");
        String json = Files.readString(cache, StandardCharsets.UTF_8);
        assertTrue(json.contains("\"contextoId\" : \"gundam_08ms\""));
        assertTrue(json.contains(ProvenienciaCache.hashDe(PROMPT_08TH)));
        assertFalse(json.contains(ProvenienciaCache.hashDe(PROMPT_ZZ)));
    }

    @Test
    void reexecucaoReaproveitaCacheSemChamarLlmDeNovo() {
        useCase.aplicar(pastaEntrada, CONTEXTO_08TH);
        assertEquals(1, llmFake.chamadasTraduzir);

        List<ResultadoTraducaoKaraoke> segunda = useCase.aplicar(pastaEntrada, CONTEXTO_08TH);
        assertEquals(1, llmFake.chamadasTraduzir, "segunda execução deve vir 100% do cache");
        assertEquals(1, segunda.getFirst().reaproveitadasCache());
        assertEquals(0, segunda.getFirst().traduzidas());
    }

    @Test
    void cacheLegadoSemLoreEhPreservadoMasNaoReaproveitado() throws IOException {
        Path pastaCache = Files.createDirectories(tempDir.resolve("cache").resolve("karaoke"));
        Path cache = pastaCache.resolve("Anime Teste - S01E01.cache.json");
        Files.writeString(cache, """
            [ {
              "indice" : 2,
              "estilo" : "OP - English",
              "original" : "Even if the world ends tomorrow",
              "traduzido" : "TRADUCAO DE LORE DESCONHECIDA",
              "idiomaOriginal" : "en",
              "idiomaTraduzido" : "pt-br"
            } ]
            """, StandardCharsets.UTF_8);

        useCase.aplicar(pastaEntrada, CONTEXTO_08TH);

        assertEquals(1, llmFake.chamadasTraduzir,
            "cache sem contexto não pode evitar a chamada ao LLM da lore selecionada");
        String novoCache = Files.readString(cache, StandardCharsets.UTF_8);
        assertTrue(novoCache.contains("\"contextoId\" : \"gundam_08ms\""));
        assertFalse(novoCache.contains("TRADUCAO DE LORE DESCONHECIDA"));
        try (var backups = Files.list(pastaCache)) {
            assertEquals(1, backups.filter(p -> p.getFileName().toString()
                .contains(".geracao_anterior_")).count(),
                "o cache sem proveniência deve continuar recuperável como geração anterior");
        }
    }

    @Test
    void simularClassificaSemChamarLlmESemGravar() {
        List<ResultadoTraducaoKaraoke> resultados = useCase.simular(pastaEntrada, null);

        assertEquals(0, llmFake.chamadasTraduzir, "dry-run nunca chama o LLM");
        Path pastaDestino = TraduzirKaraokeUseCase.resolverPastaSaida(pastaEntrada);
        assertFalse(Files.exists(pastaDestino), "dry-run não pode criar a pasta de destino");

        ResultadoTraducaoKaraoke r = resultados.getFirst();
        assertEquals(1, r.preservadasOriginalJapones());
        assertEquals(1, r.paraTraduzir());
        assertEquals(0, r.traduzidas());
    }

    /**
     * PROPÓSITO DE NEGÓCIO: dublê de LLM que bloqueia na PRIMEIRA chamada até ser liberado,
     * registrando os ids de lote observados — permite prender o {@code aplicar} num ponto
     * conhecido para provar o isolamento do contador contra um {@code simular} concorrente.
     *
     * <p>INVARIANTES DO DOMÍNIO: registra todo id de lote recebido (lista sincronizada);
     * sinaliza {@code primeiraChamadaIniciou} e aguarda {@code liberar} apenas na 1ª chamada;
     * as demais respondem imediatamente.
     *
     * <p>COMPORTAMENTO EM CASO DE FALHA: interrompido durante a espera, restaura o flag de
     * interrupção e devolve a tradução simulada, sem travar a thread indefinidamente.
     */
    static class LlmPortBloqueante implements LlmPort {
        final List<Integer> idsObservados = Collections.synchronizedList(new ArrayList<>());
        final CountDownLatch primeiraChamadaIniciou = new CountDownLatch(1);
        final CountDownLatch liberar = new CountDownLatch(1);
        private volatile boolean primeira = true;

        @Override
        public TraducaoLote traduzir(Lote lote) {
            idsObservados.add(lote.idLote());
            if (primeira) {
                primeira = false;
                primeiraChamadaIniciou.countDown();
                try {
                    liberar.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            return new TraducaoLote(lote.idLote(), List.of("Tradução simulada"), true, null);
        }

        @Override
        public StatusLlm verificarDisponibilidade() {
            return new StatusLlm(true, true, "modelo de teste carregado");
        }

        @Override
        public Optional<String> revisarConcordancia(String original, String traducao, List<String> problemas) {
            return Optional.empty();
        }

        @Override
        public Optional<String> corrigirTraducao(String original, String traducao, String motivo) {
            return Optional.empty();
        }
    }

    /**
     * PROPÓSITO DE NEGÓCIO: prova que o contador de lotes é isolado por execução — o defeito
     * corrigido na FASE I.3 permitia que um {@code simular} concorrente (fora da fila)
     * resetasse o contador de um {@code aplicar} em curso (na fila), corrompendo os ids de lote.
     *
     * <p>INVARIANTES DO DOMÍNIO: usa a MESMA instância singleton; o LLM bloqueia a 1ª chamada
     * do {@code aplicar} via {@link CountDownLatch} enquanto {@code simular} roda na mesma
     * instância; os ids observados pelo LLM devem ser exatamente {@code [1, 2]} — {@code simular}
     * não perturba o contador nem chama o LLM nem grava saída. Determinístico, sem {@code sleep}.
     *
     * <p>COMPORTAMENTO EM CASO DE FALHA: na implementação antiga (campo de instância
     * {@code sequencialLote}) o {@code simular} zera o contador e o 2º lote reaparece como id 1,
     * reprovando a asserção {@code [1, 2]}; com o contador local atual, passa.
     */
    @Test
    void contadorDeLotesIsoladoEntreSimularEAplicarConcorrentes() throws Exception {
        escreverLegendaDuasLinhasInglesas(pastaEntrada.resolve(NOME_ARQUIVO));
        LlmPortBloqueante fake = new LlmPortBloqueante();
        usarLlm(fake);

        AtomicReference<Throwable> falhaAplicador = new AtomicReference<>();
        Thread aplicador = new Thread(() -> {
            try {
                useCase.aplicar(pastaEntrada, CONTEXTO_08TH);
            } catch (Throwable t) {
                falhaAplicador.set(t);
            }
        }, "aplicar-karaoke-teste");
        aplicador.setDaemon(true);
        aplicador.start();

        // O finally SEMPRE libera o aplicador — se qualquer assertiva falhar antes, a thread
        // presa no LLM não pode ficar bloqueada indefinidamente.
        try {
            assertTrue(fake.primeiraChamadaIniciou.await(10, TimeUnit.SECONDS),
                "a primeira chamada ao LLM do aplicar deveria ter iniciado");

            int chamadasAntes = fake.idsObservados.size();
            List<ResultadoTraducaoKaraoke> simulacao = useCase.simular(pastaEntrada, null);
            int chamadasDepois = fake.idsObservados.size();

            assertEquals(1, chamadasAntes, "apenas a 1ª chamada do aplicar deve estar em curso");
            assertEquals(chamadasAntes, chamadasDepois, "simular não pode chamar o LLM");
            assertNull(simulacao.getFirst().arquivoDestino(), "simular não pode gravar saída");
        } finally {
            fake.liberar.countDown();
        }

        aplicador.join(10_000);
        if (aplicador.isAlive()) {
            aplicador.interrupt();
            aplicador.join(5_000);
        }
        assertFalse(aplicador.isAlive(), "aplicar deveria ter concluído (nenhuma thread sobrevive ao teste)");
        assertNull(falhaAplicador.get(), "aplicar não deveria lançar exceção");

        assertEquals(List.of(1, 2), List.copyOf(fake.idsObservados),
            "os ids de lote observados pelo LLM devem ser exatamente [1, 2]; "
            + "simular concorrente não pode reiniciar o contador do aplicar");
    }

    /**
     * PROPÓSITO DE NEGÓCIO: escreve uma legenda de teste com DUAS linhas inglesas traduzíveis e
     * distintas, para que o {@code aplicar} gere exatamente dois lotes de LLM (ids 1 e 2) e
     * exponha a corrida do contador.
     *
     * <p>INVARIANTES DO DOMÍNIO: as duas falas usam estilo de camada inglesa e textos
     * diferentes, garantindo duas chamadas distintas ao LLM (sem dedup) na ordem do arquivo.
     *
     * <p>COMPORTAMENTO EM CASO DE FALHA: falha de I/O ao escrever propaga {@link IOException} e
     * interrompe o teste.
     */
    private void escreverLegendaDuasLinhasInglesas(Path destino) throws IOException {
        String conteudo = String.join("\r\n",
            "[Script Info]",
            "Title: Teste",
            "",
            "[Events]",
            "Format: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text",
            "Dialogue: 0,0:01:00.00,0:01:05.00,OP - English,,0,0,0,,The night sky is calling me",
            "Dialogue: 0,0:01:06.00,0:01:10.00,OP - English,,0,0,0,,Hold my hand and never let go",
            "");
        Files.writeString(destino, conteudo, StandardCharsets.UTF_8);
    }

    /**
     * PROPÓSITO DE NEGÓCIO: A6 do segundo tiro sem marcador — a linha do OP_S2 do Guilty Crown que
     * a 4.1 de 09/10/2026 deixou em inglês (tag de cor no meio da frase, marcador perdido) tem de
     * chegar ao ARQUIVO GRAVADO traduzida e com a moldura do original, e ao CACHE do jeito que foi
     * para o arquivo. A prova unitária do tradutor não alcança a gravação; esta alcança.
     *
     * <p>INVARIANTES DO DOMÍNIO: o romaji da mesma música sai intacto; a entrada não muda.
     *
     * <p>COMPORTAMENTO EM CASO DE FALHA: a asserção mostra o arquivo gravado.
     */
    @Test
    void linhaComTagNoMeioChegaTraduzidaAoArquivoGravadoEAoCache() throws IOException {
        String prefixo = "{\\fad(0,0)\\blur4.5\\3c&H4331EA&}";
        String original = prefixo + "that your eyes were given to you to {\\c&HEAEEEB&}acknowledge others,";
        String romaji = "{\\fad(0,0)\\blur4.5\\3c&H4331EA&\\c&HEAEEEB&}Sono me wa tagai wo mitomeru tame,";
        String esperado = prefixo + "que os seus olhos foram dados a você para {\\c&HEAEEEB&}reconhecer os outros,";
        // Linhas cruas do OP_S2 do episodio 13, com a forma do arquivo real: a linha do meio e uma
        // FATIA (2:01.26-2:01.89) dentro do romaji (2:01.22-2:06.64). Foi esta fixture que mostrou
        // que o pareamento por pontas empilhava "ingles\Nportugues" sobre o romaji — ver
        // PlanoDeClassificacaoTest.fatiaDentroDoRomajiEOMesmoMomento. As vizinhas provam que as
        // linhas que ja funcionavam continuam trocando no lugar.
        Files.writeString(pastaEntrada.resolve(NOME_ARQUIVO), String.join("\r\n",
            "[Script Info]",
            "Title: Teste",
            "",
            "[Events]",
            "Format: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text",
            "Dialogue: 0,0:01:39.03,0:01:47.20,OP_S2_roma,,0,0,0,,{\\c&H5846A6&\\blur4.5\\fad(100,75)}Houkai no shinfonii ga narihibiite",
            "Dialogue: 0,0:01:50.12,0:01:58.26,OP_S2_roma,,0,0,0,,{\\c&H4E3CC8&\\blur4.5\\fad(100,300)}Furu ame wa maru de namida no neiro",
            "Dialogue: 0,0:02:01.22,0:02:06.64,OP_S2_roma,,0,0,0,," + romaji,
            "Dialogue: 0,0:02:06.64,0:02:11.98,OP_S2_roma,,0,0,0,,{\\blur4.5\\fad(100,150)}Sono koe wa omoi wo tsutaeru tame",
            "Dialogue: 0,0:01:39.03,0:01:47.20,OP_S2,,0,0,0,,{\\c&H5846A6&\\blur4.5\\fad(100,75)}The reality of the destruction around us fills our mind",
            "Dialogue: 0,0:01:50.12,0:01:58.26,OP_S2,,0,0,0,,{\\c&H4E3CC8&\\blur4.5\\fad(100,300)}The raging rain sounds like a storm of tears",
            "Dialogue: 0,0:02:01.26,0:02:01.89,OP_S2,,0,0,0,," + original,
            "Dialogue: 0,0:02:06.64,0:02:11.98,OP_S2,,0,0,0,,{\\blur4.5\\fad(100,150)}that your voice was given to you to tell others how you feel",
            ""), StandardCharsets.UTF_8);
        // O aya, medido: devolve a traducao certa e esquece o marcador; com a frase limpa, a mesma.
        usarLlm(new LlmPortFake() {
            @Override
            public TraducaoLote traduzir(Lote lote) {
                chamadasTraduzir++;
                String entrada = lote.linhasOriginais().getFirst();
                return new TraducaoLote(lote.idLote(), List.of(entrada.contains("eyes")
                    ? "que os seus olhos foram dados a você para reconhecer os outros,"
                    : "Tradução simulada"), true, null);
            }
        });

        ResultadoTraducaoKaraoke r = useCase.aplicar(pastaEntrada, CONTEXTO_08TH).getFirst();

        String saida = Files.readString(
            TraduzirKaraokeUseCase.resolverPastaSaida(pastaEntrada).resolve(NOME_ARQUIVO), StandardCharsets.UTF_8);
        assertTrue(saida.contains("OP_S2,,0,0,0,," + esperado), () -> "a linha tinha de chegar ao arquivo GRAVADO traduzida "
            + "e com a moldura do original. Avisos: " + r.avisos() + "\n" + saida);
        assertTrue(saida.contains(romaji), "o romaji da mesma musica nao pode mudar");
        assertEquals(4, r.traduzidas(), () -> "as quatro linhas inglesas, a do meio inclusive. Avisos: " + r.avisos());
        assertEquals(0, r.mantidasSemTraducao(), () -> "Avisos: " + r.avisos());
        assertTrue(r.avisos().stream().anyMatch(a -> a.startsWith("Refeita sem marcador")),
            () -> "A7: o manifesto tem de registrar o segundo tiro: " + r.avisos());
        String cache = Files.readString(tempDir.resolve("cache").resolve("karaoke")
            .resolve("Anime Teste - S01E01.cache.json"), StandardCharsets.UTF_8);
        assertTrue(cache.contains("reconhecer os outros"), () -> "o cache tem de guardar o que foi para o arquivo: " + cache);
        assertTrue(Files.readString(pastaEntrada.resolve(NOME_ARQUIVO), StandardCharsets.UTF_8).contains(original),
            "a entrada nao pode mudar");
    }

    // ---- auditoria da 4.1, 28/10/2026: desfecho honesto da execucao ----

    private void escreverLegendaCom(String... dialogos) throws IOException {
        List<String> linhas = new ArrayList<>(List.of("[Script Info]", "Title: Teste", "", "[Events]",
            "Format: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text"));
        linhas.addAll(List.of(dialogos));
        linhas.add("");
        Files.writeString(pastaEntrada.resolve(NOME_ARQUIVO), String.join("\r\n", linhas), StandardCharsets.UTF_8);
    }

    private Path arquivoCacheDoTeste() {
        return tempDir.resolve("cache").resolve("karaoke").resolve("Anime Teste - S01E01.cache.json");
    }

    @AfterEach
    void limparInterrupcao() {
        Thread.interrupted();
    }

    /** C8: com o LLM fora do ar, a pasta de destino nao nasce vazia ao lado da obra. */
    @Test
    void llmForaDoArNaoCriaPastaDeDestinoVazia() {
        usarLlm(new LlmForaDoAr());
        assertThrows(TraducaoKaraokeException.class, () -> useCase.aplicar(pastaEntrada, CONTEXTO_08TH));
        assertFalse(Files.exists(TraduzirKaraokeUseCase.resolverPastaSaida(pastaEntrada)),
            "22/08/2026: cinco execucoes abortadas deixaram -karaoke-ptbr vazia");
    }

    /** C2: Parar durante o unico arquivo fecha INTERROMPIDA, sem gravar o arquivo meio ingles; o que ja veio do LLM fica no cache. */
    @Test
    void pararDentroDoArquivoNaoFechaComoCompleta() throws IOException {
        escreverLegendaCom(
            "Dialogue: 0,0:01:00.00,0:01:05.00,OP - English,,0,0,0,,First line of the song",
            "Dialogue: 0,0:01:05.00,0:01:10.00,OP - English,,0,0,0,,Second line of the song");
        usarLlm(new LlmPortFake() {
            @Override
            public TraducaoLote traduzir(Lote lote) {
                chamadasTraduzir++;
                Thread.currentThread().interrupt();
                return new TraducaoLote(lote.idLote(), List.of("Primeira linha da música"), true, null);
            }
        });

        useCase.aplicar(pastaEntrada, CONTEXTO_08TH);
        Thread.interrupted();

        DesfechoKaraoke desfecho = persistenciaMock.desfechoCapturado.get();
        assertEquals(StatusExecucaoKaraoke.INTERROMPIDA, desfecho.status(),
            "o Parar dentro do ultimo arquivo saia COMPLETA com [SUCESSO]");
        assertFalse(Files.exists(TraduzirKaraokeUseCase.resolverPastaSaida(pastaEntrada).resolve(NOME_ARQUIVO)),
            "arquivo meio em ingles nao pode ser gravado como se estivesse pronto");
        assertTrue(Files.readString(arquivoCacheDoTeste(), StandardCharsets.UTF_8).contains("Primeira linha da música"),
            "o trabalho do LLM antes do Parar fica no cache para a proxima execucao");
    }

    /** B5/C3: servidor caindo no meio para a execucao (ABORTADA), guarda o que ja veio e nao grava o arquivo em ingles. */
    @Test
    void servidorCaindoNoMeioParaAExecucao() throws IOException {
        escreverLegendaCom(
            "Dialogue: 0,0:01:00.00,0:01:05.00,OP - English,,0,0,0,,First line of the song",
            "Dialogue: 0,0:01:05.00,0:01:10.00,OP - English,,0,0,0,,Second line of the song");
        usarLlm(new LlmPortFake() {
            @Override
            public TraducaoLote traduzir(Lote lote) {
                chamadasTraduzir++;
                return chamadasTraduzir == 1
                    ? new TraducaoLote(lote.idLote(), List.of("Primeira linha da música"), true, null)
                    : new TraducaoLote(lote.idLote(), null, false, "connection refused");
            }
        });

        assertThrows(TraducaoKaraokeException.class, () -> useCase.aplicar(pastaEntrada, CONTEXTO_08TH));

        DesfechoKaraoke desfecho = persistenciaMock.desfechoCapturado.get();
        assertEquals(StatusExecucaoKaraoke.ABORTADA, desfecho.status());
        assertTrue(desfecho.motivo().contains("connection refused"), desfecho::motivo);
        assertFalse(Files.exists(TraduzirKaraokeUseCase.resolverPastaSaida(pastaEntrada).resolve(NOME_ARQUIVO)));
        assertTrue(Files.readString(arquivoCacheDoTeste(), StandardCharsets.UTF_8).contains("Primeira linha da música"));
    }

    /** B5/C3: linha sem traducao numa execucao completa nao e [SUCESSO]. */
    @Test
    void bannerComLinhaSemTraducaoNaoEhSucesso() {
        assertEquals("ATENÇÃO", TraduzirKaraokeUseCase.rotuloDoBanner(StatusExecucaoKaraoke.COMPLETA, 1, 0, 2));
        assertEquals("SUCESSO", TraduzirKaraokeUseCase.rotuloDoBanner(StatusExecucaoKaraoke.COMPLETA, 1, 0, 0));
        assertEquals("NADA A FAZER", TraduzirKaraokeUseCase.rotuloDoBanner(StatusExecucaoKaraoke.COMPLETA, 0, 0, 0));
    }

    /** C6: falha ao gravar o .ass nao perde o trabalho do LLM — o cache foi gravado antes. */
    @Test
    void falhaAoGravarOAssNaoPerdeOCache() {
        useCase.escritor = new EscritorLegendaAss() {
            @Override
            public void escrever(Path destino, org.traducao.projeto.legenda.domain.DocumentoLegenda documento) {
                throw new org.traducao.projeto.legenda.domain.ArquivoLegendaException("arquivo travado pelo player");
            }
        };

        useCase.aplicar(pastaEntrada, CONTEXTO_08TH);

        assertEquals(1, persistenciaMock.desfechoCapturado.get().falhas().size());
        assertTrue(Files.exists(arquivoCacheDoTeste()), "a 2.1 grava o cache antes de publicar (ea4c57ff); a 4.1 tambem");
    }

    /** C1: lore trocada retraduz tudo E diz isso no manifesto. */
    @Test
    void loreTrocadaMarcaCacheIgnorado() {
        useCase.aplicar(pastaEntrada, CONTEXTO_08TH);
        assertFalse(persistenciaMock.desfechoCapturado.get().cacheIgnorado(), "contra-caso: primeira execucao");

        useCase.aplicar(pastaEntrada, "gundam_zz");

        assertTrue(persistenciaMock.desfechoCapturado.get().cacheIgnorado(),
            "Guilty Crown 09/10/2026: 20 caches arquivados e o manifesto dizia cacheIgnorado=false");
    }

    /** C5: entrada suspeita de verso perdido cuja retraducao falha continua valendo (a suspeita nao apaga nada). */
    @Test
    void suspeitaDeVersoPerdidoNaoApagaQuandoARetraducaoFalha() throws IOException {
        escreverLegendaCom("Dialogue: 0,0:01:00.00,0:01:05.00,OP - English,,0,0,0,,{\\fad(1,1)}Have a little break\\NWe're running through the lights");
        usarLlm(new LlmPortFake() {
            @Override
            public TraducaoLote traduzir(Lote lote) {
                chamadasTraduzir++;
                String e = lote.linhasOriginais().getFirst();
                return e.contains("\\N") ? new TraducaoLote(lote.idLote(), List.of("Pausa"), true, null)
                    : TraducaoLote.recusadaPeloServidor(lote.idLote(), "recusado");
            }
        });
        useCase.aplicar(pastaEntrada, CONTEXTO_08TH);
        assertTrue(Files.readString(arquivoCacheDoTeste(), StandardCharsets.UTF_8).contains("Pausa"), "preparo: suspeita gravada");

        usarLlm(new LlmPortFake() {
            @Override
            public TraducaoLote traduzir(Lote lote) {
                chamadasTraduzir++;
                return TraducaoLote.recusadaPeloServidor(lote.idLote(), "recusado");
            }
        });
        useCase.aplicar(pastaEntrada, CONTEXTO_08TH);

        assertTrue(Files.readString(arquivoCacheDoTeste(), StandardCharsets.UTF_8).contains("Pausa"),
            "o Javadoc diz que a suspeita nao apaga nada; a entrada sumia na regravacao");
        String saida = Files.readString(TraduzirKaraokeUseCase.resolverPastaSaida(pastaEntrada).resolve(NOME_ARQUIVO),
            StandardCharsets.UTF_8);
        assertTrue(saida.contains("Pausa"), "sem retraducao, vale a traducao do cache, nao o ingles: " + saida);
    }

    /**
     * B2, decisão de Paulo (28/10/2026, "Música inteira"): quando UMA linha portuguesa da música
     * precisa da fonte do diálogo (a fonte estilizada não desenha o acento), a música INTEIRA troca —
     * inclusive a linha SEM acento que veio antes. Antes, linha a linha, a mesma música saía metade
     * em Arial e metade na fonte estilizada (ED do Guilty Crown 02-22: 199 linhas).
     */
    @Test
    void fonteDoDialogoValeParaAMusicaInteira() throws IOException {
        Files.writeString(pastaEntrada.resolve(NOME_ARQUIVO), String.join("\r\n",
            "[Script Info]", "Title: Teste", "",
            "[V4+ Styles]",
            "Format: Name, Fontname, Fontsize, PrimaryColour, SecondaryColour, OutlineColour, BackColour, Bold, Italic, "
                + "Underline, StrikeOut, ScaleX, ScaleY, Spacing, Angle, BorderStyle, Outline, Shadow, Alignment, MarginL, "
                + "MarginR, MarginV, Encoding",
            "Style: Default,Gandhi Sans,70,&H00FFFFFF,&H000000FF,&H00141414,&H96000000,0,0,0,0,100,100,0,0,1,3,1,2,150,150,55,1",
            "Style: OP - English,Dash Horizon,80,&H00FFFFFF,&H000000FF,&H00000000,&H00000000,0,0,0,0,100,100,0,0,1,0,0,2,10,10,10,1",
            "",
            "[Events]",
            "Format: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text",
            "Dialogue: 0,0:00:01.00,0:00:30.00,Default,,0,0,0,,Fala de diálogo longa o bastante.",
            "Dialogue: 0,0:01:00.00,0:01:04.00,OP - English,,0,0,0,,Fly away now",
            "Dialogue: 0,0:01:05.00,0:01:09.00,OP - English,,0,0,0,,Even if the world ends tomorrow",
            ""), StandardCharsets.UTF_8);
        Files.writeString(tempDir.resolve("Anime Teste - S01E01.mkv"), "", StandardCharsets.UTF_8);
        org.traducao.projeto.traducaoKaraoke.domain.CoberturaDeFontes cobertura = (familia, texto) -> {
            String faltam = "Dash Horizon".equals(familia) ? "ãáéêíóôõúç" : "Gandhi Sans".equals(familia) ? "" : null;
            if (faltam == null) {
                return Optional.empty();
            }
            StringBuilder sem = new StringBuilder();
            texto.codePoints().distinct().filter(cp -> faltam.indexOf(cp) >= 0).forEach(sem::appendCodePoint);
            return Optional.of(sem.toString());
        };
        useCase.fontesEmbutidas = video -> new org.traducao.projeto.traducaoKaraoke.domain.FontesDoVideo(cobertura, "x.mkv", null);
        usarLlm(new LlmPortFake() {
            @Override
            public TraducaoLote traduzir(Lote lote) {
                chamadasTraduzir++;
                String e = lote.linhasOriginais().getFirst();
                return new TraducaoLote(lote.idLote(), List.of(e.startsWith("Fly") ? "Voe longe agora" : "Mesmo que o mundo acabe amanhã"), true, null);
            }
        });

        useCase.aplicar(pastaEntrada, CONTEXTO_08TH);

        String saida = Files.readString(TraduzirKaraokeUseCase.resolverPastaSaida(pastaEntrada).resolve(NOME_ARQUIVO), StandardCharsets.UTF_8);
        assertTrue(saida.lines().anyMatch(l -> l.contains("Mesmo que o mundo acabe amanhã") && l.contains("\\fnGandhi Sans")),
            "contra-caso: a linha com acento troca, como ja trocava:\n" + saida);
        assertTrue(saida.lines().anyMatch(l -> l.contains("Voe longe agora") && l.contains("\\fnGandhi Sans")),
            "a linha SEM acento da mesma musica tambem troca (musica inteira):\n" + saida);
    }

    /**
     * Camada única, decisão de Paulo (28/10/2026, "Lista por obra fora da lore"): na obra e estilo em
     * que a camada única inglesa é a TRADUÇÃO do fansub de música japonesa (ZZ OP2), o inglês não
     * empilha — sai só o português. Na mesma legenda com outra obra (contra-caso: o Unicorn E13-22,
     * letra cantada em inglês), continua empilhando "inglês\Nportuguês".
     */
    @Test
    void camadaUnicaDaListaDeObrasSaiSoEmPortugues() throws IOException {
        escreverLegendaCom("Dialogue: 0,0:01:00.00,0:01:05.00,OP2,,0,0,0,,Even if the world ends tomorrow");
        useCase.camadaUnicaTraducaoDoFansub = Optional.of(List.of("gundam_zz:OP2"));

        useCase.aplicar(pastaEntrada, "gundam_zz");
        String zz = Files.readString(TraduzirKaraokeUseCase.resolverPastaSaida(pastaEntrada).resolve(NOME_ARQUIVO),
            StandardCharsets.UTF_8);
        assertTrue(zz.contains(",OP2,,0,0,0,,Mesmo que o mundo acabe amanhã") && !zz.contains("tomorrow\\N"),
            "na obra da lista, so o portugues:\n" + zz);

        useCase.aplicar(pastaEntrada, CONTEXTO_08TH);
        String outra = Files.readString(TraduzirKaraokeUseCase.resolverPastaSaida(pastaEntrada).resolve(NOME_ARQUIVO),
            StandardCharsets.UTF_8);
        assertTrue(outra.contains("Even if the world ends tomorrow\\N"),
            "contra-caso: obra fora da lista continua empilhando o ingles:\n" + outra);
    }
}
