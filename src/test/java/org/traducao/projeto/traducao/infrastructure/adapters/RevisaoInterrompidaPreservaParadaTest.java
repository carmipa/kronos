package org.traducao.projeto.traducao.infrastructure.adapters;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.traducao.projeto.lore.infrastructure.GerenciadorContexto;
import org.traducao.projeto.traducao.infrastructure.config.LlmProperties;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * PROPÓSITO DE NEGÓCIO: prova que o "Sair" do operador sobrevive a uma chamada de REVISÃO de
 * uma fala (correção e concordância, usadas pelas telas 3.1/3.2 e pela correção do cache).
 *
 * <h2>O defeito, achado na auditoria de 28/10/2026</h2>
 * {@code postarLinhaUnica} capturava a {@code InterruptedException} do post num
 * {@code catch (Exception)}: a exceção limpa o flag, o laço dormia, repetia a chamada e devolvia
 * "sem linha utilizável" — o chamador seguia para a próxima fala como se nada tivesse sido pedido.
 *
 * <p>INVARIANTES DO DOMÍNIO: thread interrompida desiste já, sem nova chamada ao servidor, e
 * devolve o flag ligado para quem está acima decidir.
 *
 * <p>COMPORTAMENTO EM CASO DE FALHA: o adaptador devolve {@link Optional#empty()}; não lança.
 */
@DisplayName("Revisão interrompida preserva o pedido de parada")
class RevisaoInterrompidaPreservaParadaTest {

    private HttpServer servidor;

    @AfterEach
    void derrubar() {
        Thread.interrupted();
        if (servidor != null) {
            servidor.stop(0);
        }
    }

    private LlmClientAdapter adaptadorContra(AtomicInteger chamadas) throws Exception {
        servidor = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        servidor.createContext("/v1/chat/completions", troca -> {
            chamadas.incrementAndGet();
            byte[] corpo = """
                {"choices":[{"message":{"role":"assistant","content":"Ele é forte."},"finish_reason":"stop"}]}"""
                .getBytes(StandardCharsets.UTF_8);
            troca.getResponseHeaders().add("Content-Type", "application/json");
            troca.sendResponseHeaders(200, corpo.length);
            try (OutputStream saida = troca.getResponseBody()) {
                saida.write(corpo);
            }
        });
        servidor.start();
        LlmProperties propriedades = new LlmProperties(
            "http://127.0.0.1:" + servidor.getAddress().getPort() + "/v1", "modelo-de-teste", 0.3, 2000,
            Duration.ofSeconds(5), Duration.ofSeconds(10));
        return new LlmClientAdapter(propriedades, new GerenciadorContexto(List.of()), new ObjectMapper());
    }

    @Test
    @DisplayName("thread interrompida: a correcao desiste e o flag continua ligado")
    void interrompidaDesisteEPreservaOFlag() throws Exception {
        AtomicInteger chamadas = new AtomicInteger();
        LlmClientAdapter adaptador = adaptadorContra(chamadas);

        Thread.currentThread().interrupt();
        Optional<String> resposta = adaptador.corrigirTraducao("He is strong.", "Ele e forte.", "acento");

        assertTrue(Thread.currentThread().isInterrupted(), "o pedido de parada sumiu no meio da revisao");
        assertTrue(resposta.isEmpty(), "interrompida nao devolve resposta: " + resposta);
        assertTrue(chamadas.get() <= 1, "nao pode repetir a chamada depois do Sair; chamadas: " + chamadas.get());
    }

    @Test
    @DisplayName("CONTROLE: sem interrupcao a correcao chega ao servidor e volta")
    void semInterrupcaoFunciona() throws Exception {
        AtomicInteger chamadas = new AtomicInteger();
        LlmClientAdapter adaptador = adaptadorContra(chamadas);

        Optional<String> resposta = adaptador.corrigirTraducao("He is strong.", "Ele e forte.", "acento");

        assertEquals(Optional.of("Ele é forte."), resposta);
        assertEquals(1, chamadas.get());
    }
}
