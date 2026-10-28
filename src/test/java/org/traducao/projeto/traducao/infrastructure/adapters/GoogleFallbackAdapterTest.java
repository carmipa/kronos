package org.traducao.projeto.traducao.infrastructure.adapters;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * PROPÓSITO DE NEGÓCIO: fixa o mascaramento/restauração e a recusa por corrupção do
 * {@link GoogleFallbackAdapter} sem tocar a rede — o transporte HTTP é substituído pelo
 * seam {@code executarGet}.
 *
 * <p>INVARIANTES DO DOMÍNIO: tags {@code {...}} e quebras {@code \N} voltam intactas;
 * marcador perdido/mutilado resulta em {@link Optional#empty()}.
 *
 * <p>COMPORTAMENTO EM CASO DE FALHA: HTTP != 200 ou corpo inválido devolvem vazio.
 */
class GoogleFallbackAdapterTest {

    /** Adapter com transporte HTTP fixo — devolve o corpo canado, sem rede. */
    private static final class AdapterFake extends GoogleFallbackAdapter {
        private final int status;
        private final String corpo;

        AdapterFake(int status, String corpo) {
            super(new ObjectMapper());
            this.status = status;
            this.corpo = corpo;
        }

        @Override
        protected RespostaHttp executarGet(String url) {
            return new RespostaHttp(status, corpo);
        }
    }

    /** Achado da auditoria da 2.1: o catch genérico engolia o sinal de parada do operador. */
    @Test
    @DisplayName("interrupcao durante a chamada ao Google devolve o sinal de parada a thread")
    void interrupcaoNaChamadaRestauraOSinalDeParada() {
        GoogleFallbackAdapter adapter = new GoogleFallbackAdapter(new ObjectMapper()) {
            @Override
            protected RespostaHttp executarGet(String url) throws Exception {
                throw new InterruptedException("Sair do operador");
            }
        };
        try {
            assertTrue(adapter.traduzir("Hello there").traducaoOpcional().isEmpty());
            assertTrue(Thread.currentThread().isInterrupted(), "o sinal de parada nao pode ser consumido");
        } finally {
            Thread.interrupted();
        }
    }

    /** CASO-CONTROLE (A1): falha de rede comum não inventa sinal de parada. */
    @Test
    @DisplayName("falha de rede comum nao marca a thread como interrompida")
    void falhaDeRedeNaoInterrompe() {
        GoogleFallbackAdapter adapter = new GoogleFallbackAdapter(new ObjectMapper()) {
            @Override
            protected RespostaHttp executarGet(String url) throws Exception {
                throw new java.io.IOException("timeout");
            }
        };
        assertTrue(adapter.traduzir("Hello there").traducaoOpcional().isEmpty());
        assertTrue(!Thread.interrupted(), "IOException nao e parada");
    }

    /** 28/10/2026: TIMEOUT existia no enum e nada o produzia; o timeout virava "indisponível". */
    @Test
    @DisplayName("timeout do HTTP vira TIMEOUT, e falha de rede comum continua PROVEDOR_INDISPONIVEL")
    void timeoutTemCausaPropria() {
        GoogleFallbackAdapter lento = new GoogleFallbackAdapter(new ObjectMapper()) {
            @Override
            protected RespostaHttp executarGet(String url) throws Exception {
                throw new java.net.http.HttpTimeoutException("request timed out");
            }
        };
        GoogleFallbackAdapter fora = new GoogleFallbackAdapter(new ObjectMapper()) {
            @Override
            protected RespostaHttp executarGet(String url) throws Exception {
                throw new java.net.ConnectException("Connection refused");
            }
        };
        assertEquals(org.traducao.projeto.traducao.domain.fallback.StatusFallback.TIMEOUT,
            lento.traduzir("Hello there").status());
        assertEquals(org.traducao.projeto.traducao.domain.fallback.StatusFallback.PROVEDOR_INDISPONIVEL,
            fora.traduzir("Hello there").status(), "CONTROLE");
    }

    /** Monta o JSON no formato do endpoint translate_a/single para um único segmento. */
    private static String jsonGoogle(String traducao) {
        return "[[[\"" + traducao + "\",\"orig\",null,null,10]],null,\"en\"]";
    }

    @Test
    @DisplayName("restaura tag {..} e quebra \\N a partir dos marcadores [T0]/[B]")
    void restauraTagsEQuebra() {
        GoogleFallbackAdapter adapter = new AdapterFake(200, jsonGoogle("[T0] Ola [B] mundo"));

        Optional<String> r = adapter.traduzir("{\\i1}Hello \\Nworld").traducaoOpcional();

        assertTrue(r.isPresent(), "tradução válida deve estar presente");
        assertEquals("{\\i1}Ola\\Nmundo", r.get());
    }

    @Test
    @DisplayName("tag ASS perdida pelo Google → recusa (Optional vazio)")
    void tagPerdidaRecusa() {
        // A resposta não traz [T0]: a tag {\i1} não pode ser restaurada.
        GoogleFallbackAdapter adapter = new AdapterFake(200, jsonGoogle("Ola mundo"));

        Optional<String> r = adapter.traduzir("{\\i1}Hello world").traducaoOpcional();

        assertTrue(r.isEmpty(), "tag perdida deve manter a fala pendente");
    }

    @Test
    @DisplayName("HTTP != 200 → Optional vazio")
    void httpErroRecusa() {
        GoogleFallbackAdapter adapter = new AdapterFake(429, "");

        assertTrue(adapter.traduzir("Hello there").traducaoOpcional().isEmpty());
    }

    @Test
    @DisplayName("texto sem tag é traduzido e devolvido")
    void semTagTraduz() {
        GoogleFallbackAdapter adapter = new AdapterFake(200, jsonGoogle("Ola pessoal"));

        Optional<String> r = adapter.traduzir("Hi everyone").traducaoOpcional();

        assertEquals("Ola pessoal", r.get());
    }

    @Test
    @DisplayName("preserva hard-space \\h e soft-break \\n (não só \\N)")
    void preservaQuebrasMinusculas() {
        GoogleFallbackAdapter adapter = new AdapterFake(200, jsonGoogle("Vem [Bh] ca [Bn] agora"));

        Optional<String> r = adapter.traduzir("Come\\hhere\\nnow").traducaoOpcional();

        assertTrue(r.isPresent(), "quebras \\h/\\n devem ser preservadas");
        assertEquals("Vem\\hca\\nagora", r.get());
    }

    @Test
    @DisplayName("marcador de tag duplicado pelo Google → recusa (contagem)")
    void tagDuplicadaRecusa() {
        // Google ecoa [T0] duas vezes: restaurar produziria {\i1} duplicado.
        GoogleFallbackAdapter adapter = new AdapterFake(200, jsonGoogle("[T0] Ola [T0] mundo"));

        Optional<String> r = adapter.traduzir("{\\i1}Hello world").traducaoOpcional();

        assertTrue(r.isEmpty(), "duplicacao de tag deve manter a fala pendente");
    }

    @Test
    @DisplayName("hard-space \\h descartado pelo Google → recusa (contagem)")
    void quebraPerdidaRecusa() {
        GoogleFallbackAdapter adapter = new AdapterFake(200, jsonGoogle("Vem ca agora"));

        Optional<String> r = adapter.traduzir("Come\\hhere now").traducaoOpcional();

        assertTrue(r.isEmpty(), "perda de \\h deve manter a fala pendente");
    }

    @Test
    @DisplayName("#12: '(b)'/'(t)' como conteúdo (alternativas) não é confundido com marcador residual")
    void conteudoComParentesesNaoEhMarcadorResidual() {
        GoogleFallbackAdapter adapter = new AdapterFake(200, jsonGoogle("Escolha (b), nao (a)."));

        Optional<String> r = adapter.traduzir("Choose (b), not (a).").traducaoOpcional();

        assertTrue(r.isPresent(), "conteúdo entre parênteses não pode ser tratado como marcador mutilado");
        assertEquals("Escolha (b), nao (a).", r.get());
    }
}
