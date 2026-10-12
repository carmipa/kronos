package org.traducao.projeto.traducaoKaraoke.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.traducao.projeto.cachetraducao.infrastructure.CacheTraducaoService;
import org.traducao.projeto.legenda.application.DetectorEfeitoKaraokeService;
import org.traducao.projeto.legenda.domain.EventoLegenda;
import org.traducao.projeto.legenda.infrastructure.EscritorLegendaAss;
import org.traducao.projeto.legenda.infrastructure.LeitorLegendaAss;
import org.traducao.projeto.llm.domain.Lote;
import org.traducao.projeto.llm.domain.TraducaoLote;
import org.traducao.projeto.lore.infrastructure.GerenciadorContexto;
import org.traducao.projeto.qualidadeTraducao.application.LoreAtivaFake;
import org.traducao.projeto.qualidadeTraducao.application.MascaradorTags;
import org.traducao.projeto.qualidadeTraducao.application.ValidadorTraducaoService;
import org.traducao.projeto.traducaoKaraoke.domain.ResultadoTraducaoKaraoke;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * PROPÓSITO DE NEGÓCIO: a frase de karaokê pintada palavra a palavra sai SÓ em português, e as
 * palavras do efeito em inglês saem da tela — decisão de Paulo em 12/10/2026, depois de ver na TV a
 * abertura do Unicorn com "Do you feel alone" escrito em cima de "Você se sente sozinho?".
 *
 * <h2>O prejuízo que originou</h2>
 * No {@code OPL2} do Unicorn a frase e as palavras "Do", "you", "feel", "a", "lone" são desenhadas
 * na mesma altura ({@code \pos(960,1050)}). A 4.1 empilhava {@code inglês\Nportuguês} na frase: o
 * português ia para a altura 1050, onde as palavras do efeito continuavam acesas, e o inglês saía
 * escrito por cima dele. Reproduzido no libass e no MPC-HC; 336 frases em 21 dos 22 episódios.
 *
 * <h2>Invariantes do domínio congelados aqui</h2>
 * <ul>
 *   <li>A frase inglesa com efeito palavra a palavra vira só português, sem {@code \N} e sem o
 *       inglês; as palavras do efeito viram {@code Comment:} com tempo, posição e texto intactos.</li>
 *   <li><b>Fronteira (A1):</b> a frase em ROMAJI com efeito sílaba a sílaba carrega o mesmo sinal
 *       (frase reconstruída pelos pedaços) e sai intacta, com as sílabas visíveis — a camada
 *       original nunca é tocada.</li>
 *   <li><b>Fronteira do outro lado:</b> a linha inglesa SEM efeito, sozinha na tela, continua
 *       empilhando a original sobre o português — o prejuízo dos 22 de 23 versos do encerramento do
 *       Unicorn que sumiam.</li>
 *   <li>Sem as fontes do vídeo verificadas, a fonte da música não é trocada (A8); o contorno do
 *       diálogo entra porque a frase perdeu o efeito que a contornava.</li>
 * </ul>
 *
 * <h2>Comportamento em caso de falha</h2>
 * Cada asserção mostra o arquivo GRAVADO e relido (A6) — a prova alcança a última transformação.
 */
class KfxSoPortuguesNoLugarTest {

    private static final String NOME = "Unicorn - S01E01.ass";
    private static final String CONTEXTO = "gundam_uc";

    private static final String CABECALHO = String.join("\r\n",
        "[Script Info]",
        "Title: Teste",
        "ScriptType: v4.00+",
        "PlayResX: 1920",
        "PlayResY: 1080",
        "",
        "[V4+ Styles]",
        "Format: Name, Fontname, Fontsize, PrimaryColour, SecondaryColour, OutlineColour, BackColour, "
            + "Bold, Italic, Underline, StrikeOut, ScaleX, ScaleY, Spacing, Angle, BorderStyle, Outline, "
            + "Shadow, Alignment, MarginL, MarginR, MarginV, Encoding",
        "Style: Default,Gandhi Sans,70,&H00FFFFFF,&H000000FF,&H00141414,&H96000000,-1,0,0,0,100,100,0,0,1,3.5,1.5,2,150,150,55,1",
        "Style: OP - English,Dash Horizon,80,&H00FFFFFF,&H000000FF,&H00000000,&H00000000,0,0,0,0,100,100,0,0,1,0,0,2,10,10,10,1",
        "Style: OP - Romaji,Dash Horizon,60,&H00FFFFFF,&H000000FF,&H00000000,&H00000000,0,0,0,0,100,100,0,0,1,0,0,8,10,10,10,1",
        "Style: ED - English,Conformity,60,&H00FFFFFF,&H000000FF,&H00000000,&H00000000,0,0,0,0,100,100,0,0,1,2,0,2,10,10,10,1",
        "",
        "[Events]",
        "Format: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text");

    private static final String FALA = "Dialogue: 0,0:00:01.00,0:00:05.00,Default,,0,0,0,,Banagher, voce esta bem?";

    /** O {@code OPL2} do E01, com os tempos e as posições do arquivo real. */
    private static final List<String> FRASE_INGLESA_COM_EFEITO = List.of(
        "Dialogue: 0,0:01:37.00,0:01:39.80,OP - English,,0,0,0,,{\\pos(960,1050)}Do you feel alone",
        "Dialogue: 0,0:01:37.00,0:01:39.90,OP - English,,0,0,0,,{\\pos(753,1050)}Do",
        "Dialogue: 0,0:01:37.15,0:01:39.90,OP - English,,0,0,0,,{\\pos(800,1050)}you",
        "Dialogue: 0,0:01:37.24,0:01:39.90,OP - English,,0,0,0,,{\\pos(860,1050)}feel",
        "Dialogue: 0,0:01:37.61,0:01:39.90,OP - English,,0,0,0,,{\\pos(910,1050)}a",
        "Dialogue: 0,0:01:37.83,0:01:39.90,OP - English,,0,0,0,,{\\pos(940,1050)}lone");

    /** A camada ORIGINAL do mesmo verso, com o mesmo tipo de efeito: frase + sílabas. */
    private static final List<String> FRASE_ROMAJI_COM_EFEITO = List.of(
        "Dialogue: 0,0:01:37.00,0:01:39.80,OP - Romaji,,0,0,0,,{\\pos(960,60)}Kimi no koe ga kikoeru",
        "Dialogue: 0,0:01:37.00,0:01:39.90,OP - Romaji,,0,0,0,,{\\pos(700,60)}Ki",
        "Dialogue: 0,0:01:37.10,0:01:39.90,OP - Romaji,,0,0,0,,{\\pos(730,60)}mi",
        "Dialogue: 0,0:01:37.30,0:01:39.90,OP - Romaji,,0,0,0,,{\\pos(780,60)}no",
        "Dialogue: 0,0:01:37.50,0:01:39.90,OP - Romaji,,0,0,0,,{\\pos(830,60)}ko",
        "Dialogue: 0,0:01:37.60,0:01:39.90,OP - Romaji,,0,0,0,,{\\pos(860,60)}e",
        "Dialogue: 0,0:01:37.80,0:01:39.90,OP - Romaji,,0,0,0,,{\\pos(900,60)}ga",
        "Dialogue: 0,0:01:38.00,0:01:39.90,OP - Romaji,,0,0,0,,{\\pos(950,60)}ki",
        "Dialogue: 0,0:01:38.20,0:01:39.90,OP - Romaji,,0,0,0,,{\\pos(990,60)}ko",
        "Dialogue: 0,0:01:38.40,0:01:39.90,OP - Romaji,,0,0,0,,{\\pos(1030,60)}e",
        "Dialogue: 0,0:01:38.60,0:01:39.90,OP - Romaji,,0,0,0,,{\\pos(1070,60)}ru");

    /** Encerramento de camada única, sem efeito: a original tem de continuar na tela. */
    private static final String VERSO_SEM_EFEITO =
        "Dialogue: 0,0:22:00.00,0:22:04.00,ED - English,,0,0,0,,Behind your mask";

    private Path tempDir;
    private Path pastaEntrada;
    private TraduzirKaraokeUseCase useCase;

    @BeforeEach
    void setUp() throws IOException {
        tempDir = Files.createTempDirectory("test_kfx_so_pt");
        pastaEntrada = Files.createDirectories(tempDir.resolve("legendas"));

        TraduzirKaraokeUseCaseTest.LlmPortFake llm = new TraduzirKaraokeUseCaseTest.LlmPortFake() {
            @Override
            public TraducaoLote traduzir(Lote lote) {
                chamadasTraduzir++;
                String entrada = lote.linhasOriginais().getFirst();
                String saida = entrada.contains("feel alone") ? "Você se sente sozinho"
                    : entrada.contains("mask") ? "Atrás da sua máscara" : "Tradução simulada";
                return new TraducaoLote(lote.idLote(), List.of(saida), true, null);
            }
        };

        useCase = new TraduzirKaraokeUseCase();
        useCase.leitor = new LeitorLegendaAss();
        useCase.escritor = new EscritorLegendaAss();
        useCase.llmPort = llm;
        useCase.gerenciadorContexto = new GerenciadorContexto(List.of(
            new org.traducao.projeto.lore.domain.ProvedorContexto() {
                @Override public String getId() { return CONTEXTO; }
                @Override public String getNomeExibicao() { return "Mobile Suit Gundam Unicorn"; }
                @Override public String obterPromptSistema() { return "PROMPT DE TESTE DO UNICORN"; }
            }));
        useCase.classificador = new ClassificadorLetraKaraokeService(new DetectorEfeitoKaraokeService());

        TradutorDeLetraKaraoke tradutorDeLetra = new TradutorDeLetraKaraoke();
        tradutorDeLetra.llmPort = llm;
        tradutorDeLetra.mascarador = new MascaradorTags();
        tradutorDeLetra.validador = new ValidadorTraducaoService(LoreAtivaFake.vazia());
        tradutorDeLetra.telemetriaService = new TraduzirKaraokeUseCaseTest.MockTelemetria();
        tradutorDeLetra.logStream = new TraduzirKaraokeUseCaseTest.MockLogStream();
        useCase.tradutorDeLetra = tradutorDeLetra;

        RegistroDaExecucao registro = new RegistroDaExecucao();
        registro.persistencia = new TraduzirKaraokeUseCaseTest.MockPersistencia();
        registro.telemetriaService = new TraduzirKaraokeUseCaseTest.MockTelemetria();
        registro.logStream = new TraduzirKaraokeUseCaseTest.MockLogStream();
        registro.acervoDataset = new AcervoKaraokeCapturado();
        useCase.registro = registro;

        // O cache vai para a pasta temporaria: o cache do karaoke e por NOME de arquivo, e um nome
        // de episodio real gravaria por cima do cache real.
        CacheDoArquivo cacheDoArquivo = new CacheDoArquivo();
        cacheDoArquivo.cacheService = new CacheTraducaoService(new ObjectMapper());
        cacheDoArquivo.logStream = new TraduzirKaraokeUseCaseTest.MockLogStream();
        cacheDoArquivo.idiomaOriginal = Optional.empty();
        cacheDoArquivo.idiomaTraduzido = Optional.empty();
        cacheDoArquivo.diretorioCache = Optional.of(tempDir.resolve("cache").toString());
        useCase.cacheDoArquivo = cacheDoArquivo;

        useCase.montador = new MontadorEventoFinal();
        useCase.logStream = new TraduzirKaraokeUseCaseTest.MockLogStream();
        useCase.telemetriaService = new TraduzirKaraokeUseCaseTest.MockTelemetria();
        useCase.idiomaOriginal = Optional.empty();
        useCase.idiomaTraduzido = Optional.empty();
        // Sem leitor de fontes: o caminho em que a fonte NÃO pode ser trocada (A8).
        useCase.fontesEmbutidas = null;
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

    private void escrever(List<String> eventos) throws IOException {
        List<String> linhas = new ArrayList<>();
        linhas.add(CABECALHO);
        linhas.add(FALA);
        linhas.addAll(eventos);
        linhas.add("");
        Files.writeString(pastaEntrada.resolve(NOME), String.join("\r\n", linhas), StandardCharsets.UTF_8);
    }

    private String gravado() throws IOException {
        return Files.readString(TraduzirKaraokeUseCase.resolverPastaSaida(pastaEntrada).resolve(NOME),
            StandardCharsets.UTF_8);
    }

    private static List<String> linhasDoEstilo(String saida, String tipo, String estilo) {
        return saida.lines().filter(l -> l.startsWith(tipo + ":") && l.contains("," + estilo + ",")).toList();
    }

    @Test
    @DisplayName("Unicorn OPL2: a frase sai só em português e as palavras do efeito saem da tela")
    void fraseInglesaComEfeitoViraSoPortugues() throws IOException {
        escrever(FRASE_INGLESA_COM_EFEITO);

        ResultadoTraducaoKaraoke r = useCase.aplicar(pastaEntrada, CONTEXTO).getFirst();
        String saida = gravado();

        List<String> dialogos = linhasDoEstilo(saida, "Dialogue", "OP - English");
        assertEquals(1, dialogos.size(), () -> "so a frase em portugues pode ficar na tela:\n" + saida);
        String frase = dialogos.getFirst();
        assertTrue(frase.startsWith("Dialogue: 0,0:01:37.00,0:01:39.80,OP - English,,0,0,0,,{\\pos(960,1050)"),
            () -> "a frase tem de manter tempo e posicao:\n" + frase);
        assertTrue(frase.endsWith("}Você se sente sozinho"), () -> "a frase tem de sair so em portugues:\n" + frase);
        assertFalse(frase.contains("\\N"), () -> "EMPILHOU de novo — o ingles volta a cair em cima do portugues:\n" + frase);
        assertFalse(frase.contains("Do you feel alone"), () -> "o ingles ficou na frase:\n" + frase);

        List<String> comentadas = linhasDoEstilo(saida, "Comment", "OP - English");
        assertEquals(List.of(
                "Comment: 0,0:01:37.00,0:01:39.90,OP - English,,0,0,0,,{\\pos(753,1050)}Do",
                "Comment: 0,0:01:37.15,0:01:39.90,OP - English,,0,0,0,,{\\pos(800,1050)}you",
                "Comment: 0,0:01:37.24,0:01:39.90,OP - English,,0,0,0,,{\\pos(860,1050)}feel",
                "Comment: 0,0:01:37.61,0:01:39.90,OP - English,,0,0,0,,{\\pos(910,1050)}a",
                "Comment: 0,0:01:37.83,0:01:39.90,OP - English,,0,0,0,,{\\pos(940,1050)}lone"),
            comentadas, () -> "as palavras do efeito tem de sair da tela intactas, como Comment:\n" + saida);

        assertEquals(1, r.frasesKfxSoPortugues(), () -> "A7: o manifesto tem de contar a frase. " + r);
        assertEquals(5, r.silabasKfxSilenciadas(), () -> "A7: o manifesto tem de contar as palavras. " + r);
        assertTrue(saida.contains(FALA), "o dialogo nao pode mudar");
        assertTrue(Files.readString(pastaEntrada.resolve(NOME), StandardCharsets.UTF_8)
            .contains(FRASE_INGLESA_COM_EFEITO.get(1)), "a entrada nao pode mudar");
    }

    /**
     * Fronteira A1: a frase em romaji traz o MESMO sinal que disparou a mudança — pedaços que a
     * reconstroem, na mesma janela — e tem de sair intacta. "A camada da legenda original romaji
     * sempre deve ser preservada" (Paulo, 12/10/2026).
     */
    @Test
    @DisplayName("fronteira: o romaji com efeito sílaba a sílaba sai intacto ao lado do inglês que vira PT")
    void romajiComEfeitoSaiIntacto() throws IOException {
        List<String> eventos = new ArrayList<>(FRASE_ROMAJI_COM_EFEITO);
        eventos.addAll(FRASE_INGLESA_COM_EFEITO);
        escrever(eventos);

        ResultadoTraducaoKaraoke r = useCase.aplicar(pastaEntrada, CONTEXTO).getFirst();
        String saida = gravado();

        assertEquals(FRASE_ROMAJI_COM_EFEITO, linhasDoEstilo(saida, "Dialogue", "OP - Romaji"),
            () -> "A CAMADA ORIGINAL FOI TOCADA — frase ou silaba do romaji mudou ou saiu da tela:\n" + saida);
        assertTrue(linhasDoEstilo(saida, "Comment", "OP - Romaji").isEmpty(),
            () -> "silaba do romaji virou Comment:\n" + saida);
        assertEquals(5, linhasDoEstilo(saida, "Comment", "OP - English").size(),
            () -> "as palavras do efeito ingles tinham de sair da tela:\n" + saida);
        assertEquals(1, linhasDoEstilo(saida, "Dialogue", "OP - English").size(), () -> saida);
        assertEquals(1, r.frasesKfxSoPortugues(), () -> String.valueOf(r));
        assertEquals(5, r.silabasKfxSilenciadas(), () -> String.valueOf(r));
    }

    /** O outro lado da fronteira: verso inglês sozinho, sem efeito, continua empilhando a original. */
    @Test
    @DisplayName("fronteira: verso inglês sem efeito continua com a original em cima do português")
    void versoSemEfeitoContinuaEmpilhando() throws IOException {
        escrever(List.of(VERSO_SEM_EFEITO));

        ResultadoTraducaoKaraoke r = useCase.aplicar(pastaEntrada, CONTEXTO).getFirst();
        String saida = gravado();

        assertTrue(saida.contains("ED - English,,0,0,0,,Behind your mask\\NAtrás da sua máscara"),
            () -> "o verso sem irma perdeu a original — o defeito dos 22 de 23 versos do Unicorn:\n" + saida);
        assertEquals(0, r.frasesKfxSoPortugues());
        assertEquals(0, r.silabasKfxSilenciadas());
        assertTrue(linhasDoEstilo(saida, "Comment", "ED - English").isEmpty(), saida);
    }

    /**
     * A8: sem as fontes do vídeo lidas, a fonte da música NÃO é trocada — e o resumo diz o motivo.
     * O contorno do diálogo entra mesmo assim: a frase perdeu as palavras que a contornavam.
     */
    @Test
    @DisplayName("sem fonte verificada não troca a fonte; o contorno do diálogo entra na frase que ficou só")
    void semFonteVerificadaNaoTrocaAFonte() throws IOException {
        escrever(FRASE_INGLESA_COM_EFEITO);

        ResultadoTraducaoKaraoke r = useCase.aplicar(pastaEntrada, CONTEXTO).getFirst();
        String frase = linhasDoEstilo(gravado(), "Dialogue", "OP - English").getFirst();

        assertFalse(frase.contains("\\fn"), () -> "trocou a fonte sem ter verificado o video:\n" + frase);
        assertTrue(frase.contains("{\\pos(960,1050)\\bord3.5\\shad1.5\\3c&H141414&\\3a&H00&\\4c&H000000&\\4a&H96&}"),
            () -> "a frase sem o efeito tinha de ganhar o contorno do dialogo:\n" + frase);
        assertEquals(0, r.linhasComFonteDoDialogo());
        assertTrue(r.fontesDaMusica().startsWith("NAO_VERIFICADAS"), r.fontesDaMusica());
    }

    @Test
    @DisplayName("Comment: preserva índice, estilo, tempo e texto; o que não é Dialogue volta intacto")
    void comoComentarioSoTrocaOTipo() {
        EventoLegenda silaba = new EventoLegenda(7, "Dialogue", "OP - English",
            "Dialogue: 0,0:01:37.00,0:01:39.90,OP - English,,0,0,0,,", "{\\pos(753,1050)}Do");

        EventoLegenda comentada = MontadorEventoFinal.comoComentario(silaba);

        assertEquals("Comment: 0,0:01:37.00,0:01:39.90,OP - English,,0,0,0,,", comentada.prefixo());
        assertEquals(silaba.texto(), comentada.texto());
        assertEquals(silaba.indice(), comentada.indice());
        assertEquals(silaba.estilo(), comentada.estilo());
        assertFalse(comentada.isDialogo(), "o evento comentado nao pode continuar sendo dialogo");

        assertSame(comentada, MontadorEventoFinal.comoComentario(comentada), "Comment: volta intacto");
        assertNull(MontadorEventoFinal.comoComentario(null));
    }
}
