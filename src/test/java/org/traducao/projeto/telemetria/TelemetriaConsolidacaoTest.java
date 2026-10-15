package org.traducao.projeto.telemetria;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.traducao.projeto.core.io.DiretorioBaseKronos;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * PROPÓSITO DE NEGÓCIO: caracteriza o agregador CQRS read-only do Painel Unificado,
 * consolidando o histórico legado ({@code telemetria_compartilhada.json}) com a
 * telemetria própria da Tradução Local ({@code telemetria_traducao.json}) de forma
 * determinística e sem sobrepor contadores.
 * <p>INVARIANTES DO DOMÍNIO: raiz isolada em {@code @TempDir}; o novo arquivo vence
 * o legado por chave; contadores somados sem overlap; sem importar o pacote traducao.
 * <p>COMPORTAMENTO EM CASO DE FALHA: divergência ou exceção reprova a suíte.
 */
class TelemetriaConsolidacaoTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private String baseAnterior;

    @TempDir
    Path raiz;

    @BeforeEach
    void isolarRaiz() {
        baseAnterior = System.getProperty(DiretorioBaseKronos.PROPRIEDADE_BASE);
        System.setProperty(DiretorioBaseKronos.PROPRIEDADE_BASE, raiz.toString());
    }

    @AfterEach
    void restaurarRaiz() {
        if (baseAnterior == null) {
            System.clearProperty(DiretorioBaseKronos.PROPRIEDADE_BASE);
        } else {
            System.setProperty(DiretorioBaseKronos.PROPRIEDADE_BASE, baseAnterior);
        }
    }

    private Map<String, Object> reg(String nome, String modelo, int linhas, String ts) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("nomeEpisodio", nome);
        m.put("modeloLlm", modelo);
        m.put("totalLinhas", linhas);
        m.put("falasTraduzidas", linhas);
        m.put("falasDoCache", 0);
        m.put("tempoTotalMs", 100);
        m.put("errosOcorridos", List.of());
        m.put("animeNome", "Anime");
        m.put("temporada", "Temporada Única");
        m.put("registradoEm", ts);
        m.put("loreNome", "lore");
        m.put("statusFinal", "CONCLUIDO");
        return m;
    }

    private void escreverTraducao(int aluc, int rej, int rec, int fb, List<Map<String, Object>> registros) throws IOException {
        Path logs = Files.createDirectories(raiz.resolve("logs"));
        Map<String, Object> doc = new LinkedHashMap<>();
        doc.put("schemaVersion", "1.0");
        doc.put("registros", registros);
        doc.put("alucinacoesPrevenidas", aluc);
        doc.put("respostasTraducaoRejeitadas", rej);
        doc.put("falhasTraducaoRecuperadas", rec);
        doc.put("fallbacksTraducaoMantidos", fb);
        mapper.writeValue(logs.resolve("telemetria_traducao.json").toFile(), doc);
    }

    private void escreverBruto(String conteudo) throws IOException {
        Path logs = Files.createDirectories(raiz.resolve("logs"));
        Files.writeString(logs.resolve("telemetria_traducao.json"), conteudo);
    }

    private LlmTelemetria legado(String nome, String modelo, int linhas, String ts) {
        return new LlmTelemetria(nome, modelo, linhas, linhas, 0, 100L, List.of(),
            "Anime", "Temporada Única", ts, "lore", "CONCLUIDO", null, null);
    }

    /**
     * A tela mostrava a coluna "Confianca" com a taxa inteira (91 de 24.441 virava "0%"); agora
     * ela lê os números crus. Fronteira: tradução de episódio não detecta nada e vem com nulo.
     */
    @Test
    @DisplayName("Histórico leva detectados e corrigidos crus da operação")
    void historicoLevaDetectadosECorrigidos() {
        TelemetriaService t = new TelemetriaService();
        t.registrarOperacao(new OperacaoTelemetria("Revisao de Lore (.ass LLM)", "pasta", 1000L, 3, 24441, 91,
            "2026-10-14T10:00:00Z"));
        t.registrarTraducao(legado("ep.ass", "aya", 10, "2026-10-14T09:00:00Z"));

        List<OperacaoHistorico> historico = t.gerarResumo(Path.of("cache")).historicoOperacoes();

        OperacaoHistorico lore = historico.stream()
            .filter(h -> h.nomeOperacao().startsWith("Revisao de Lore")).findFirst().orElseThrow();
        assertEquals(24441, lore.itensDetectados());
        assertEquals(91, lore.itensCorrigidos());
        assertEquals(0, lore.taxaSucesso(), "a taxa inteira continua igual, só por compatibilidade");
        OperacaoHistorico traducao = historico.stream()
            .filter(h -> "Tradução LLM".equals(h.nomeOperacao())).findFirst().orElseThrow();
        assertEquals(null, traducao.itensDetectados());
        assertEquals(null, traducao.itensCorrigidos());
    }

    @Test
    @DisplayName("Somente legado existente")
    void somenteLegado() {
        TelemetriaService t = new TelemetriaService();
        t.registrarTraducao(legado("epLeg.ass", "modeloLeg", 10, "2026-01-01T00:00:00Z"));
        t.registrarAlucinacaoPrevenida();
        TelemetriaResumo r = t.gerarResumo(Path.of("cache"));
        assertEquals(1, r.totalEpisodios());
        assertEquals(1, r.alucinacoesPrevenidas());
    }

    @Test
    @DisplayName("Somente arquivo novo existente")
    void somenteNovo() throws IOException {
        escreverTraducao(3, 2, 1, 4, List.of(reg("epNovo.ass", "modeloNovo", 20, "2026-02-01T00:00:00Z")));
        TelemetriaService t = new TelemetriaService();
        TelemetriaResumo r = t.gerarResumo(Path.of("cache"));
        assertEquals(1, r.totalEpisodios());
        assertEquals(3, r.alucinacoesPrevenidas());
        assertEquals(2, r.respostasTraducaoRejeitadas());
        assertEquals(1, r.falhasTraducaoRecuperadas());
        assertEquals(4, r.fallbacksTraducaoMantidos());
    }

    @Test
    @DisplayName("Ambos existentes: episódios somados e contadores somados sem overlap")
    void ambos() throws IOException {
        escreverTraducao(3, 0, 0, 0, List.of(reg("epNovo.ass", "modeloNovo", 20, "2026-02-01T00:00:00Z")));
        TelemetriaService t = new TelemetriaService();
        t.registrarTraducao(legado("epLeg.ass", "modeloLeg", 10, "2026-01-01T00:00:00Z"));
        t.registrarAlucinacaoPrevenida();
        t.registrarAlucinacaoPrevenida();
        TelemetriaResumo r = t.gerarResumo(Path.of("cache"));
        assertEquals(2, r.totalEpisodios(), "epLeg + epNovo");
        assertEquals(2 + 3, r.alucinacoesPrevenidas(), "legado(2) + novo(3), sem sobreposição");
    }

    @Test
    @DisplayName("Arquivo novo ausente não quebra a agregação")
    void novoAusente() {
        TelemetriaService t = new TelemetriaService();
        t.registrarTraducao(legado("epLeg.ass", "modeloLeg", 10, "2026-01-01T00:00:00Z"));
        assertDoesNotThrow(() -> t.gerarResumo(Path.of("cache")));
        assertEquals(1, t.gerarResumo(Path.of("cache")).totalEpisodios());
    }

    @Test
    @DisplayName("Arquivo novo vazio: só o legado é considerado")
    void novoVazio() throws IOException {
        escreverTraducao(0, 0, 0, 0, List.of());
        TelemetriaService t = new TelemetriaService();
        t.registrarTraducao(legado("epLeg.ass", "modeloLeg", 10, "2026-01-01T00:00:00Z"));
        assertEquals(1, t.gerarResumo(Path.of("cache")).totalEpisodios());
    }

    @Test
    @DisplayName("Arquivo novo corrompido é tratado como vazio (SSE degrada para o legado, sem exceção)")
    void novoCorrompido() throws IOException {
        escreverBruto("isto nao e json {{{");
        TelemetriaService t = new TelemetriaService();
        t.registrarTraducao(legado("epLeg.ass", "modeloLeg", 10, "2026-01-01T00:00:00Z"));
        TelemetriaResumo r = assertDoesNotThrow(() -> t.gerarResumo(Path.of("cache")));
        assertEquals(1, r.totalEpisodios());
    }

    @Test
    @DisplayName("Duplicata entre fontes: o arquivo novo vence o legado, independentemente da leitura")
    void duplicataCrossSourceNovoVence() throws IOException {
        escreverTraducao(0, 0, 0, 0, List.of(reg("ep01.ass", "modeloNovo", 99, "2026-02-01T00:00:00Z")));
        TelemetriaService t = new TelemetriaService();
        t.registrarTraducao(legado("ep01.ass", "modeloLeg", 10, "2026-01-01T00:00:00Z"));
        TelemetriaResumo r = t.gerarResumo(Path.of("cache"));
        assertEquals(1, r.totalEpisodios(), "mesma chave normalizada consolida em um episódio");
        assertEquals(1, r.traducoesLlm().size());
        assertEquals("modeloNovo", r.traducoesLlm().get(0).modeloLlm(), "novo vence o legado");
        assertEquals(99, r.totalLinhas());
    }

    @Test
    @DisplayName("Duplicata por caixa/diretório também consolida com precedência do novo")
    void duplicataNormalizadaNovoVence() throws IOException {
        escreverTraducao(0, 0, 0, 0, List.of(reg("EP01.ass", "modeloNovo", 99, "2026-02-01T00:00:00Z")));
        TelemetriaService t = new TelemetriaService();
        t.registrarTraducao(legado("pasta/ep01.ass", "modeloLeg", 10, "2026-01-01T00:00:00Z"));
        TelemetriaResumo r = t.gerarResumo(Path.of("cache"));
        assertEquals(1, r.totalEpisodios());
        assertEquals("modeloNovo", r.traducoesLlm().get(0).modeloLlm());
    }
}
