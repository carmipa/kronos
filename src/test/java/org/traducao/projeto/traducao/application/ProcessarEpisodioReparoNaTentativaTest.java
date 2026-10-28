package org.traducao.projeto.traducao.application;

import org.traducao.projeto.qualidadeTraducao.application.ReparadorMarcadoresLlm;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.traducao.projeto.llm.domain.LlmPort;
import org.traducao.projeto.llm.domain.Lote;
import org.traducao.projeto.llm.domain.StatusLlm;
import org.traducao.projeto.llm.domain.TraducaoLote;
import org.traducao.projeto.qualidadeTraducao.application.LoreAtivaFake;
import org.traducao.projeto.qualidadeTraducao.application.MascaradorTags;
import org.traducao.projeto.qualidadeTraducao.application.ValidadorTraducaoService;
import org.traducao.projeto.traducao.domain.TelemetriaTraducao;
import org.traducao.projeto.traducao.domain.ports.TelemetriaTraducaoPort;
import org.traducao.projeto.traducao.presentation.ui.ConsoleUILogger;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * PROPÓSITO DE NEGÓCIO: prova, pelo caminho REAL da tentativa ({@link ProcessarEpisodioUseCase}),
 * que o defeito de par com conserto conhecido sai CONSERTADO e não em inglês. É a lacuna que a
 * retradução dirigida de 08/10/2026 expôs: o teste de unidade chamava o reparo direto e passava,
 * enquanto no pipeline o modelo repetia "Kou, fique comigo." nas três temperaturas e a fala
 * "Uraki, you're with me." era publicada em inglês (22 falas assim, mais 4 de polaridade).
 *
 * <h2>Invariantes do domínio</h2>
 * <ul>
 *   <li>O LLM falso responde SEMPRE o mesmo candidato, como o aya fez: se o reparo não existir na
 *       tentativa, o desfecho é o original em inglês e o teste reprova pela causa.</li>
 *   <li>Reparo bem-sucedido custa UMA chamada ao modelo e conta uma rejeição e uma recuperação.</li>
 *   <li>A1: defeito sem conserto seguro (pergunta perdida) e reparo que não passa na revalidação
 *       seguem o caminho antigo — três tentativas e o original; o reparo não é porta dos fundos.</li>
 * </ul>
 *
 * <p>COMPORTAMENTO EM CASO DE FALHA: saída em inglês num caso reparável é a regressão de 08/10;
 * saída reparada num caso sem conserto é a porta dos fundos.
 */
@DisplayName("2.1: defeito de par com conserto conhecido sai consertado na tentativa, não em inglês")
class ProcessarEpisodioReparoNaTentativaTest {

    private static final class TelemetriaFake implements TelemetriaTraducaoPort {
        final AtomicInteger rejeitadas = new AtomicInteger();
        final AtomicInteger recuperadas = new AtomicInteger();

        @Override public void registrarTraducao(TelemetriaTraducao telemetria) { }
        @Override public void registrarAlucinacaoPrevenida() { }
        @Override public void registrarRespostaTraducaoRejeitada() { rejeitadas.incrementAndGet(); }
        @Override public void registrarFalhaTraducaoRecuperada() { recuperadas.incrementAndGet(); }
        @Override public void registrarFallbackMantido() { }
    }

    /** Responde sempre o mesmo candidato, em qualquer temperatura — o modelo teimoso medido. */
    private static final class LlmTeimoso implements LlmPort {
        private final String candidato;
        final AtomicInteger chamadas = new AtomicInteger();

        LlmTeimoso(String candidato) {
            this.candidato = candidato;
        }

        @Override public TraducaoLote traduzir(Lote lote) {
            chamadas.incrementAndGet();
            return new TraducaoLote(lote.idLote(), List.of(candidato), true, null);
        }
        @Override public StatusLlm verificarDisponibilidade() { return new StatusLlm(true, true, "ok"); }
        @Override public Optional<String> revisarConcordancia(String a, String b, List<String> c) { return Optional.empty(); }
        @Override public Optional<String> corrigirTraducao(String a, String b, String c) { return Optional.empty(); }
    }

    private static List<String> traduzir(LlmTeimoso llm, TelemetriaFake telemetria, String original) throws Exception {
        ValidadorTraducaoService validador = new ValidadorTraducaoService(LoreAtivaFake.comPares(
            List.of("Uraki", "Kou"), List.of("Keith", "Chuck"), List.of("Handler One", "Lena")));
        ProcessarEpisodioUseCase useCase = new ProcessarEpisodioUseCase(llm, validador, new ConsoleUILogger(),
            telemetria, new MascaradorTags(), new ReparadorMarcadoresLlm(new MascaradorTags()));
        return useCase.processarEpisodio(List.of(new Lote(1, List.of(original))), null).getFirst().linhasTraduzidas();
    }

    @ParameterizedTest(name = "[{index}] reparada: {0} | modelo: {1}")
    @DisplayName("troca de registro e polaridade: uma chamada, fala consertada")
    @CsvSource(delimiter = '|', value = {
        "Uraki, you're with me.|Kou, fique comigo.|Uraki, fique comigo.",
        "Uraki! Keith! What the hell are you doing?!|Kou! Chuck! O que diabos vocês estão fazendo?!|Uraki! Keith! O que diabos vocês estão fazendo?!",
        "Handler One to Pleiades:|Lena para Plêiades:|Handler One para Plêiades:",
        "No... it's too late for that. It's selfish.|Sim... é tarde demais para isso. É egoísta.|Não... é tarde demais para isso. É egoísta.",
        "No.|Sim.|Não.",
        "\"She\"?|Ela.|Ela?"
    })
    void defeitoComConsertoSaiConsertado(String original, String candidato, String esperado) throws Exception {
        LlmTeimoso llm = new LlmTeimoso(candidato);
        TelemetriaFake telemetria = new TelemetriaFake();

        assertEquals(List.of(esperado), traduzir(llm, telemetria, original),
            "fala reparável não pode sair em inglês nem com o defeito");
        assertEquals(1, llm.chamadas.get(), "o reparo não gasta outra chamada ao modelo");
        assertEquals(1, telemetria.rejeitadas.get(), "a resposta do modelo foi rejeitada pelo portão");
        assertEquals(1, telemetria.recuperadas.get(), "e a fala foi recuperada pelo reparo");
    }

    @ParameterizedTest(name = "[{index}] sem reparo: {0} | modelo: {1}")
    @DisplayName("A1: sem conserto seguro, ou reparo que reprova de novo — três tentativas e o original")
    @CsvSource(delimiter = '|', value = {
        "A rat?!|Que porco colorido!",
        "No... it's too late.|Claro... é tarde demais.",
        "Keith, is it landing?|Chuck, está pousando."
    })
    void semConsertoSeguroSegueOCaminhoAntigo(String original, String candidato) throws Exception {
        LlmTeimoso llm = new LlmTeimoso(candidato);
        TelemetriaFake telemetria = new TelemetriaFake();

        assertEquals(List.of(original), traduzir(llm, telemetria, original),
            "sem conserto seguro a fala fica pendente com o original — nunca publicada com o defeito");
        assertEquals(3, llm.chamadas.get(), "o caminho antigo: três temperaturas");
        assertEquals(0, telemetria.recuperadas.get(), "manter o original não é recuperação");
    }

    /** Responde em sequência, uma resposta por chamada, repetindo a última quando acabar. */
    private static final class LlmEmSequencia implements LlmPort {
        private final List<String> respostas;
        final AtomicInteger chamadas = new AtomicInteger();

        LlmEmSequencia(String... respostas) {
            this.respostas = List.of(respostas);
        }

        @Override public TraducaoLote traduzir(Lote lote) {
            int i = Math.min(chamadas.getAndIncrement(), respostas.size() - 1);
            return new TraducaoLote(lote.idLote(), List.of(respostas.get(i)), true, null);
        }
        @Override public StatusLlm verificarDisponibilidade() { return new StatusLlm(true, true, "ok"); }
        @Override public Optional<String> revisarConcordancia(String a, String b, List<String> c) { return Optional.empty(); }
        @Override public Optional<String> corrigirTraducao(String a, String b, String c) { return Optional.empty(); }
    }

    private static List<String> traduzirCom(LlmPort llm, TelemetriaFake telemetria, String original) throws Exception {
        ProcessarEpisodioUseCase useCase = new ProcessarEpisodioUseCase(llm,
            new ValidadorTraducaoService(LoreAtivaFake.vazia()), new ConsoleUILogger(), telemetria,
            new MascaradorTags(), new ReparadorMarcadoresLlm(new MascaradorTags()));
        return useCase.processarEpisodio(List.of(new Lote(1, List.of(original))), null).getFirst().linhasTraduzidas();
    }

    @org.junit.jupiter.api.Test
    @DisplayName("parêntese inventado: outra tentativa, e a forma única do modelo é a publicada")
    void parenteseInventadoPedeOutraTentativa() throws Exception {
        LlmEmSequencia llm = new LlmEmSequencia("Estou aliviado(a).", "Estou aliviado(a).", "Que alívio.");
        TelemetriaFake telemetria = new TelemetriaFake();

        assertEquals(List.of("Que alívio."), traduzirCom(llm, telemetria, "I'm relieved."));
        assertEquals(3, llm.chamadas.get());
        assertEquals(2, telemetria.rejeitadas.get());
    }

    @org.junit.jupiter.api.Test
    @DisplayName("parêntese trocado por barra na retentativa é o mesmo defeito: reprovado de novo")
    void parenteseTrocadoPorBarraNaoEscapa() throws Exception {
        // Medido no experimento de 08/10: recusado o "estranho(a)", o aya devolveu "estranho/a".
        LlmEmSequencia llm = new LlmEmSequencia("Você é estranho(a).", "Você é estranho/a.", "Você é muito esquisito.");
        TelemetriaFake telemetria = new TelemetriaFake();

        assertEquals(List.of("Você é muito esquisito."), traduzirCom(llm, telemetria, "You really are weird."));
        assertEquals(3, llm.chamadas.get());
        assertEquals(2, telemetria.rejeitadas.get());
    }

    @org.junit.jupiter.api.Test
    @DisplayName("parêntese inventado até a última tentativa: publica com o parêntese, NUNCA o inglês")
    void parenteseNaUltimaTentativaEhAceito() throws Exception {
        LlmEmSequencia llm = new LlmEmSequencia("Estou aliviado(a).");
        TelemetriaFake telemetria = new TelemetriaFake();

        assertEquals(List.of("Estou aliviado(a)."), traduzirCom(llm, telemetria, "I'm relieved."),
            "o inglês seria o desfecho pior para quem assiste");
        assertEquals(3, llm.chamadas.get());
    }

    /**
     * 28/10/2026: "recuperada" é desfecho, não tentativa. A 1ª temperatura reprovada e o reparo na
     * 2ª contavam a MESMA fala duas vezes; e um reparo seguido de alternativa inventada contava
     * recuperação de uma fala que terminava pendente.
     */
    @org.junit.jupiter.api.Test
    @DisplayName("fala recuperada conta uma vez, por mais tentativas e reparos que custe")
    void recuperadaContaUmaVezPorFala() throws Exception {
        ValidadorTraducaoService validador = new ValidadorTraducaoService(LoreAtivaFake.comPares(
            List.of("Uraki", "Kou"), List.of("Keith", "Chuck"), List.of("Handler One", "Lena")));

        // 1a temperatura: eco; 2a: troca de registro, reparada. Antes: 2 recuperadas.
        LlmEmSequencia ecoDepoisPar = new LlmEmSequencia("Uraki, you're with me.", "Kou, fique comigo.");
        TelemetriaFake t1 = new TelemetriaFake();
        List<String> saida = new ProcessarEpisodioUseCase(ecoDepoisPar, validador, new ConsoleUILogger(), t1,
                new MascaradorTags(), new ReparadorMarcadoresLlm(new MascaradorTags()))
            .processarEpisodio(List.of(new Lote(1, List.of("Uraki, you're with me."))), null)
            .getFirst().linhasTraduzidas();
        assertEquals(List.of("Uraki, fique comigo."), saida);
        assertEquals(1, t1.recuperadas.get(), "uma fala, uma recuperacao; rejeitadas=" + t1.rejeitadas.get());

        // Reparo a cada temperatura, reprovado pela alternativa inventada ate a ultima aceitar.
        // Antes: 4 recuperadas (3 reparos + o laco).
        LlmTeimoso teimoso = new LlmTeimoso("Kou(a), fique comigo.");
        TelemetriaFake t2 = new TelemetriaFake();
        traduzir(teimoso, t2, "Uraki, you're with me.");
        assertEquals(3, teimoso.chamadas.get(), "as tres temperaturas foram gastas");
        assertEquals(1, t2.recuperadas.get(), "ainda e uma fala so: " + t2.recuperadas.get());
    }

    @ParameterizedTest(name = "[{index}] legítima: {0} -> {1}")
    @DisplayName("A1: tradução certa com o mesmo sinal passa intacta, sem reparo nem rejeição")
    @CsvSource(delimiter = '|', value = {
        "Uraki! Above you!|Uraki! Acima de você!",
        "No way!|Claro que não!",
        "Kou Uraki, reporting.|Kou Uraki, apresentando-se."
    })
    void traducaoCertaPassaIntacta(String original, String candidato) throws Exception {
        LlmTeimoso llm = new LlmTeimoso(candidato);
        TelemetriaFake telemetria = new TelemetriaFake();

        assertEquals(List.of(candidato), traduzir(llm, telemetria, original));
        assertEquals(1, llm.chamadas.get());
        assertEquals(0, telemetria.rejeitadas.get());
    }
}
