package org.traducao.projeto.traducaoKaraoke.application;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.traducao.projeto.llm.domain.LlmPort;
import org.traducao.projeto.llm.domain.Lote;
import org.traducao.projeto.llm.domain.StatusLlm;
import org.traducao.projeto.llm.domain.TraducaoLote;
import org.traducao.projeto.qualidadeTraducao.application.LoreAtivaFake;
import org.traducao.projeto.qualidadeTraducao.application.MascaradorTags;
import org.traducao.projeto.qualidadeTraducao.application.ValidadorTraducaoService;
import org.traducao.projeto.traducaoKaraoke.domain.ServidorLlmIndisponivelException;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * PROPÓSITO DE NEGÓCIO: prova os quatro consertos do tradutor da letra apontados pela auditoria da
 * 4.1 de 28/10/2026 — causa da falha preservada e servidor caído parando a execução (B5/C3),
 * marcador inventado pelo modelo ignorado (B1), validação contra o original (B3) e verso perdido
 * conferido já na tradução nova (A3).
 *
 * <p>INVARIANTES DO DOMÍNIO: cada caso doente tem um contra-caso legítimo com o MESMO sinal (A1) —
 * recusa do pedido continua sendo problema só da linha; o "Go!" virando pedido de linhas continua
 * reprovado; a resposta de uma linha que não encurtou continua aceita com UMA chamada.
 *
 * <p>COMPORTAMENTO EM CASO DE FALHA: cada asserção mostra os avisos do manifesto e as entradas que
 * o dublê recebeu.
 */
class TradutorDeLetraDesfechoHonestoTest {

    @AfterEach
    void limparInterrupcao() {
        Thread.interrupted();
    }

    /** Dublê que responde por função da entrada e guarda o que recebeu. */
    private static final class LlmScriptado implements LlmPort {
        private final Function<String, TraducaoLote> resposta;
        final List<String> recebidas = new ArrayList<>();

        LlmScriptado(Function<String, TraducaoLote> resposta) {
            this.resposta = resposta;
        }

        @Override
        public TraducaoLote traduzir(Lote lote) {
            String entrada = lote.linhasOriginais().getFirst();
            recebidas.add(entrada);
            return resposta.apply(entrada);
        }

        @Override
        public StatusLlm verificarDisponibilidade() {
            return new StatusLlm(true, true, "teste");
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

    private static TraducaoLote ok(String... linhas) {
        return new TraducaoLote(1, List.of(linhas), true, null);
    }

    private static TradutorDeLetraKaraoke tradutor(LlmPort llm) {
        TradutorDeLetraKaraoke t = new TradutorDeLetraKaraoke();
        t.llmPort = llm;
        t.mascarador = new MascaradorTags();
        t.validador = new ValidadorTraducaoService(LoreAtivaFake.vazia());
        t.telemetriaService = new TraduzirKaraokeUseCaseTest.MockTelemetria();
        t.logStream = new TraduzirKaraokeUseCaseTest.MockLogStream();
        return t;
    }

    private static final String MOLDURA = "{\\fad(100,100)\\an1\\blur4}";

    @Test
    @DisplayName("B5: recusa do pedido fica na linha COM a causa do adaptador")
    void recusaDoPedidoGuardaACausa() {
        LlmScriptado llm = new LlmScriptado(e -> TraducaoLote.recusadaPeloServidor(1, "resposta truncada pelo servidor"));
        List<String> avisos = new ArrayList<>();

        String saida = tradutor(llm).traduzirViaLlm(MOLDURA + "Fly away", avisos, new AtomicInteger(), "p");

        assertNull(saida);
        assertTrue(avisos.stream().anyMatch(a -> a.contains("resposta truncada pelo servidor")),
            () -> "A7: a causa do adaptador tinha de chegar ao manifesto: " + avisos);
    }

    @Test
    @DisplayName("B5: falha sem marca de recusa é servidor fora do ar e PARA a execução")
    void servidorForaDoArPara() {
        LlmScriptado llm = new LlmScriptado(e -> new TraducaoLote(1, null, false, "timeout de 180 s"));

        ServidorLlmIndisponivelException erro = assertThrows(ServidorLlmIndisponivelException.class,
            () -> tradutor(llm).traduzirViaLlm(MOLDURA + "Fly away", new ArrayList<>(), new AtomicInteger(), "p"));
        assertTrue(erro.getMessage().contains("timeout de 180 s"), erro::getMessage);
    }

    @Test
    @DisplayName("B5: com a thread interrompida a mesma falha NÃO é servidor — é o Parar do operador")
    void interrupcaoNaoViraServidorForaDoAr() {
        LlmScriptado llm = new LlmScriptado(e -> new TraducaoLote(1, null, false, "interrompido"));
        Thread.currentThread().interrupt();
        List<String> avisos = new ArrayList<>();

        String saida = tradutor(llm).traduzirViaLlm(MOLDURA + "Fly away", avisos, new AtomicInteger(), "p");

        assertNull(saida);
        assertTrue(avisos.stream().anyMatch(a -> a.contains("interrompida pelo operador")), avisos::toString);
    }

    @Test
    @DisplayName("B1: [[TAG0]] inventado numa linha própria não derruba a tradução certa")
    void marcadorInventadoEmLinhaPropria() {
        String limpa = "I will find my strength within your charms";
        LlmScriptado llm = new LlmScriptado(e -> ok("Encontrarei minha força dentro do seu encanto.", "[[TAG0]]"));
        List<String> avisos = new ArrayList<>();

        String saida = tradutor(llm).traduzirViaLlm(MOLDURA + limpa, avisos, new AtomicInteger(), "p");

        assertEquals(MOLDURA + "Encontrarei minha força dentro do seu encanto.", saida,
            () -> "Reconguista IV, 14/10: a tradução certa foi recusada por '[[TAG0]]'. Avisos: " + avisos);
    }

    @Test
    @DisplayName("B1: [[TAG0]] colado no texto sai do texto em vez de ir para a tela")
    void marcadorInventadoColado() {
        LlmScriptado llm = new LlmScriptado(e -> ok("Encontrarei [[TAG0]]minha força"));

        String saida = tradutor(llm).traduzirViaLlm(MOLDURA + "I will find my strength", new ArrayList<>(),
            new AtomicInteger(), "p");

        assertEquals(MOLDURA + "Encontrarei minha força", saida);
    }

    @Test
    @DisplayName("B3: 'Não recebi a ordem.' para 'I didn't receive the order.' é tradução, não recusa")
    void recusaAmbiguaAbsolvidaPeloOriginal() {
        LlmScriptado llm = new LlmScriptado(e -> ok("Não recebi a ordem."));
        List<String> avisos = new ArrayList<>();

        String saida = tradutor(llm).traduzirViaLlm(MOLDURA + "I didn't receive the order.", avisos,
            new AtomicInteger(), "p");

        assertEquals(MOLDURA + "Não recebi a ordem.", saida, avisos::toString);
    }

    @Test
    @DisplayName("B3 contra-caso: 'Go!' virando pedido de linhas continua reprovado")
    void recusaSemApoioContinuaReprovada() {
        LlmScriptado llm = new LlmScriptado(e -> ok("Não recebi nenhuma linha para traduzir."));

        String saida = tradutor(llm).traduzirViaLlm(MOLDURA + "Go!", new ArrayList<>(), new AtomicInteger(), "p");

        assertNull(saida);
    }

    @Test
    @DisplayName("A3: resposta que juntou os versos e encurtou é refeita verso a verso")
    void versoPerdidoNaTraducaoNova() {
        Map<String, String> respostas = Map.of(
            "I seek out the ones who\\Ntry to stomp on them", "Busco aqueles que tentam pisar neles.",
            "I seek out the ones who", "Eu procuro aqueles que",
            "try to stomp on them", "tentam pisar neles");
        LlmScriptado llm = new LlmScriptado(e -> ok(respostas.getOrDefault(e, "(sem resposta)")));
        List<String> avisos = new ArrayList<>();

        String saida = tradutor(llm).traduzirViaLlm(MOLDURA + "I seek out the ones who\\Ntry to stomp on them",
            avisos, new AtomicInteger(), "p");

        assertEquals(MOLDURA + "Eu procuro aqueles que\\Ntentam pisar neles", saida,
            () -> "Break Blade 05: o falso positivo voltava ao LLM em toda execução. Avisos: " + avisos
                + " | recebidas: " + llm.recebidas);
    }

    @Test
    @DisplayName("A3 contra-caso: uma linha que NÃO encurtou é aceita com uma chamada só")
    void umaLinhaSemEncurtarPassa() {
        LlmScriptado llm = new LlmScriptado(e -> ok("Faça uma pausa curta, estamos correndo pelas luzes da cidade"));

        String saida = tradutor(llm).traduzirViaLlm(MOLDURA + "Have a little break\\NWe're running through the lights",
            new ArrayList<>(), new AtomicInteger(), "p");

        assertEquals(MOLDURA + "Faça uma pausa curta, estamos correndo pelas luzes da cidade", saida);
        assertEquals(1, llm.recebidas.size(), () -> "nenhuma chamada extra: " + llm.recebidas);
    }
}
