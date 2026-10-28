package org.traducao.projeto.traducao.presentation.web;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.traducao.projeto.core.execucao.FilaExecucaoPipeline;
import org.traducao.projeto.core.io.GuardaCaminhoEntrada;
import org.traducao.projeto.core.presentation.web.LogStreamService;
import org.traducao.projeto.core.presentation.web.OperacaoRequest;
import org.traducao.projeto.core.presentation.web.PipelineWebSupport;
import org.traducao.projeto.llm.domain.LlmPort;
import org.traducao.projeto.llm.domain.Lote;
import org.traducao.projeto.llm.domain.StatusLlm;
import org.traducao.projeto.llm.domain.TraducaoLote;
import org.traducao.projeto.lore.domain.ProvedorContexto;
import org.traducao.projeto.lore.domain.SnapshotContexto;
import org.traducao.projeto.lore.infrastructure.GerenciadorContexto;
import org.traducao.projeto.traducao.application.ProcessarArquivoUseCase;
import org.traducao.projeto.traducao.domain.ResultadoTraducaoArquivo;
import org.traducao.projeto.traducao.domain.TelemetriaTraducao;
import org.traducao.projeto.traducao.domain.exceptions.TraducaoParcialException;
import org.traducao.projeto.traducao.domain.ports.TelemetriaTraducaoPort;
import org.traducao.projeto.traducao.infrastructure.AvisoSonoroSistema;
import org.traducao.projeto.traducao.infrastructure.config.TradutorProperties;
import org.traducao.projeto.traducao.presentation.ui.PastasExecucao;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * PROPÓSITO DE NEGÓCIO: garante que parar a Tradução Local de propósito não seja relatado como
 * falha — na tela, no evento de fim de lote e na telemetria.
 *
 * <h2>O prejuízo que originou</h2>
 * Auditoria de 25/09/2026: o "Sair" pedido no meio do Zeta E01 saiu como
 * {@code [FALHA] ... abortado} e {@code [FALHOU] Traducao via LLM finalizada}, com o arquivo
 * registrado como FALHOU na telemetria. O caso de uso converte a interrupção cooperativa em
 * {@link TraducaoParcialException} para salvar o progresso, e o controller tratava toda essa
 * exceção como falha — o ramo {@code [PARADO]} existia e nunca era alcançado por esse caminho.
 * Parada do operador e queda do LLM davam o mesmo sinal, o que a regra 12 proíbe.
 *
 * <h2>Invariantes do domínio</h2>
 * <ul>
 *   <li>Interrupção cooperativa ⇒ lote CANCELADO, nenhuma telemetria de falha.</li>
 *   <li>A MESMA exceção sem interrupção (LLM caiu no meio) continua FALHOU — é o caso-controle
 *       de fronteira (A1): mesmo sinal superficial, desfecho oposto.</li>
 * </ul>
 *
 * <h2>Comportamento em caso de falha</h2>
 * Falha se o evento de fim não chegar em 10s ou se o desfecho divergir do esperado.
 */
class ParadaDoOperadorNaoEhFalhaTest {

    private static final class LogStreamEspiao extends LogStreamService {
        private final List<String[]> publicacoes = new ArrayList<>();
        private final CountDownLatch chegouOFim = new CountDownLatch(1);

        @Override
        public void publicarLog(String canal, String mensagem) {
            synchronized (publicacoes) {
                publicacoes.add(new String[] { canal, mensagem });
            }
            if (canal.endsWith(TraducaoController.SUFIXO_CANAL_FIM)) {
                chegouOFim.countDown();
            }
        }

        String desfecho() {
            synchronized (publicacoes) {
                return publicacoes.stream()
                    .filter(p -> p[0].endsWith(TraducaoController.SUFIXO_CANAL_FIM))
                    .map(p -> p[1].split("\\" + TraducaoController.SEPARADOR_EVENTO)[0])
                    .findFirst().orElse(null);
            }
        }
    }

    private static final class LlmNoAr implements LlmPort {
        @Override public TraducaoLote traduzir(Lote lote) { throw new UnsupportedOperationException(); }
        @Override public StatusLlm verificarDisponibilidade() { return new StatusLlm(true, true, "ok"); }
        @Override public Optional<String> revisarConcordancia(String o, String t, List<String> p) { return Optional.empty(); }
        @Override public Optional<String> corrigirTraducao(String o, String t, String m) { return Optional.empty(); }
    }

    private static final class AvisoSonoroMudo extends AvisoSonoroSistema {
        @Override
        public Resultado tocar(int toques) {
            return Resultado.INDISPONIVEL;
        }
    }

    private static final class ContextoTeste implements ProvedorContexto {
        @Override public String getId() { return "parada_teste"; }
        @Override public String getNomeExibicao() { return "Parada Teste"; }
        @Override public String obterPromptSistema() { return "Traduza."; }
    }

    private static final class TelemetriaEspia implements TelemetriaTraducaoPort {
        final List<String> statusRegistrados = new ArrayList<>();
        @Override public void registrarTraducao(TelemetriaTraducao t) { statusRegistrados.add(t.statusFinal()); }
        @Override public void registrarAlucinacaoPrevenida() { }
        @Override public void registrarRespostaTraducaoRejeitada() { }
        @Override public void registrarFalhaTraducaoRecuperada() { }
        @Override public void registrarFallbackMantido() { }
    }

    /**
     * Caso de uso que só sabe abortar como o real aborta: salva o parcial e lança
     * {@link TraducaoParcialException}. Com {@code interromper}, liga antes o flag de interrupção,
     * exatamente como a fila faz quando o operador aperta "Sair".
     */
    private static final class CasoDeUsoQueAborta extends ProcessarArquivoUseCase {
        private final boolean interromper;

        CasoDeUsoQueAborta(boolean interromper) {
            super(null, null, null, null, null, null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null, null, null, null, null, null,
                null, null, null);
            this.interromper = interromper;
        }

        @Override
        public ResultadoTraducaoArquivo processar(Path arquivoEntrada, boolean permitirRetraducao,
                SnapshotContexto contextoDoJob, boolean ignorarLoreExistente) {
            // O flag da thread NÃO é ligado aqui, de propósito: no caminho real ele chega ao
            // controller já consumido (medido no "Sair" de 25/09/2026). A primeira versão deste
            // dublê ligava o flag, o teste passava, e o conserto falhava na aplicação.
            TraducaoParcialException ex = new TraducaoParcialException("Tradução interrompida pelo usuário.",
                Map.of("Hello there", "Olá"), null);
            throw interromper ? ex.marcarInterrompidaPeloUsuario() : ex;
        }
    }

    private String rodar(Path raiz, boolean interromper, TelemetriaEspia telemetria) throws Exception {
        Path entrada = Files.createDirectories(raiz.resolve("Obra").resolve("legendas_originais"));
        Files.writeString(entrada.resolve("ep01.ass"), "[Events]\n", StandardCharsets.UTF_8);
        Files.writeString(entrada.resolve("ep02.ass"), "[Events]\n", StandardCharsets.UTF_8);
        TradutorProperties props = new TradutorProperties(entrada.toString(), raiz.resolve("saida").toString(),
            raiz.resolve("cache").toString(), 20, List.of(), "en", "pt-BR");
        LogStreamEspiao espiao = new LogStreamEspiao();
        TraducaoController controller = new TraducaoController(
            new PipelineWebSupport(new FilaExecucaoPipeline(), espiao),
            new CasoDeUsoQueAborta(interromper),
            new LlmNoAr(),
            new GerenciadorContexto(List.of(new ContextoTeste())),
            new PastasExecucao(),
            props,
            telemetria,
            new GuardaCaminhoEntrada(),
            espiao,
            new AvisoSonoroMudo());

        controller.traduzir(new OperacaoRequest(
            entrada.toString(), null, "parada_teste", null, false, null, null, false));

        assertTrue(espiao.chegouOFim.await(10, TimeUnit.SECONDS), "o lote nao anunciou o fim");
        return espiao.desfecho();
    }

    @Test
    void paradaDoOperadorSaiComoCanceladoESemTelemetriaDeFalha(@TempDir Path raiz) throws Exception {
        TelemetriaEspia telemetria = new TelemetriaEspia();

        String desfecho = rodar(raiz, true, telemetria);

        assertEquals("CANCELADO", desfecho,
            "parada pedida pelo operador tem de sair CANCELADO, nao FALHOU");
        assertTrue(telemetria.statusRegistrados.isEmpty(),
            "parada nao e falha do arquivo e nao pode ir a telemetria como FALHOU: " + telemetria.statusRegistrados);
    }

    /** Caso de uso que conclui todo arquivo, para exercitar a borda sem o pipeline. */
    private static final class CasoDeUsoQueConclui extends ProcessarArquivoUseCase {
        CasoDeUsoQueConclui() {
            super(null, null, null, null, null, null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null, null, null, null, null, null,
                null, null, null);
        }

        @Override
        public ResultadoTraducaoArquivo processar(Path arquivoEntrada, boolean permitirRetraducao,
                SnapshotContexto contextoDoJob, boolean ignorarLoreExistente) {
            return ResultadoTraducaoArquivo.concluido(null, arquivoEntrada.getFileName().toString(), "teste", 1, 0, 1, 0);
        }
    }

    /**
     * 28/10/2026: o caminho colado com aspas ("Copiar como caminho" do Explorer) passava na guarda,
     * a rota respondia 200 "iniciada", e o job morria com InvalidPathException ao configurar as
     * pastas com o texto cru — o lote saía FALHA GERAL.
     */
    @Test
    void caminhoEntreAspasNaoDerrubaOLote(@TempDir Path raiz) throws Exception {
        Path entrada = Files.createDirectories(raiz.resolve("Obra").resolve("legendas_originais"));
        Files.writeString(entrada.resolve("ep01.ass"), "[Events]\n", StandardCharsets.UTF_8);
        Path saida = raiz.resolve("saida-escolhida");
        TradutorProperties props = new TradutorProperties(entrada.toString(), raiz.resolve("saida").toString(),
            raiz.resolve("cache").toString(), 20, List.of(), "en", "pt-BR");
        LogStreamEspiao espiao = new LogStreamEspiao();
        PastasExecucao pastas = new PastasExecucao();
        TraducaoController controller = new TraducaoController(
            new PipelineWebSupport(new FilaExecucaoPipeline(), espiao), new CasoDeUsoQueConclui(), new LlmNoAr(),
            new GerenciadorContexto(List.of(new ContextoTeste())), pastas, props, new TelemetriaEspia(),
            new GuardaCaminhoEntrada(), espiao, new AvisoSonoroMudo());

        controller.traduzir(new OperacaoRequest(
            "\"" + entrada + "\"", "\"" + saida + "\"", "parada_teste", null, false, null, null, false));

        assertTrue(espiao.chegouOFim.await(10, TimeUnit.SECONDS), "o lote nao anunciou o fim");
        assertEquals("CONCLUÍDO", espiao.desfecho(), "o lote tem de rodar, nao cair em FALHA GERAL");
        assertEquals(entrada, pastas.diretorioEntrada(), "a pasta de entrada vai sem as aspas");
        assertEquals(saida, pastas.diretorioSaida(), "a pasta de saida vai sem as aspas");
    }

    @Test
    void mesmaExcecaoSemInterrupcaoContinuaSendoFalha(@TempDir Path raiz) throws Exception {
        TelemetriaEspia telemetria = new TelemetriaEspia();

        String desfecho = rodar(raiz, false, telemetria);

        assertEquals("FALHOU", desfecho,
            "o LLM caindo no meio NAO e parada do operador: continua FALHOU");
        assertEquals(List.of("FALHOU", "FALHOU"), telemetria.statusRegistrados,
            "cada arquivo abortado sem interrupcao continua registrado como FALHOU");
    }
}
