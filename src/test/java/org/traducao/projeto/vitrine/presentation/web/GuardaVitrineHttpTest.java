package org.traducao.projeto.vitrine.presentation.web;

import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.traducao.projeto.core.execucao.FilaExecucaoPipeline;
import org.traducao.projeto.core.vitrine.ModoVitrine;
import org.traducao.projeto.core.vitrine.OperacaoBloqueadaNaVitrineException;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * PROPÓSITO DE NEGÓCIO: prova, com o servidor HTTP real, que a vitrine pública do KRONOS mostra
 * as telas e recusa toda função — o filtro está mesmo na frente do estático, do JAX-RS, das
 * rotas Spring e do MCP, e a fila do pipeline recusa trabalho por conta própria.
 *
 * <p>INVARIANTES DO DOMÍNIO: caminhos disfarçados são enviados por socket cru, byte a byte,
 * porque um cliente HTTP comum normaliza o caminho antes de enviar e o teste provaria nada. O
 * controle do outro lado (vitrine desligada) mora em {@code GuardaVitrineDesligadaTest}: sem
 * ele, um 403 aqui não distinguiria "a guarda recusou" de "a rota não existe".
 *
 * <p>COMPORTAMENTO EM CASO DE FALHA: cada asserção nomeia o caminho e o status recebido.
 */
@QuarkusTest
@TestProfile(GuardaVitrineHttpTest.PerfilVitrine.class)
class GuardaVitrineHttpTest {

    /** Perfil da vitrine: filtro ligado e MCP desligado no build, como na imagem da VPS. */
    public static class PerfilVitrine implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of(
                ModoVitrine.CHAVE_CONFIGURACAO, "true",
                "quarkus.mcp.server.http.enabled", "false",
                "app.browser.auto-open", "false",
                "app.modo", "WEB");
        }
    }

    @TestHTTPResource("/")
    URL raiz;

    @Inject
    FilaExecucaoPipeline fila;

    @Inject
    GuardaVitrine guarda;

    @Test
    @DisplayName("leitura de memória segue e leva o cabeçalho da vitrine")
    void leituraSegue() {
        given().get("/api/status").then().statusCode(200).header(GuardaVitrine.CABECALHO_VITRINE, "1");
        given().get("/api/pipeline/status").then().statusCode(200);
        given().get("/api/contextos").then().statusCode(200);
    }

    @Test
    @DisplayName("as telas abrem: índice e estáticos")
    void telasAbrem() {
        given().get("/").then().statusCode(200).body(containsString("KRONOS"));
        given().get("/js/app.js").then().statusCode(200);
        given().get("/css/base.css").then().statusCode(200);
    }

    @ParameterizedTest(name = "{0} {1}")
    @CsvSource({
        "POST,/api/pipeline/parar",
        "POST,/api/traduzir",
        "POST,/api/mapa",
        "POST,/api/telemetria/consolidar-fatias",
        "GET,/api/navegador/pastas",
        "GET,/api/metadata",
        "GET,/api/telemetria/exportar",
        "GET,/api/dialogo/selecionar-pasta",
        "POST,/api/renomear-arquivos/simular",
        "POST,/api/auditoria-conteudo",
        "POST,/api/novo-karaoke/simular",
        "POST,/mcp",
        "GET,/mcp",
        "GET,/mcp/sse",
        "GET,/q/dev-ui",
        "GET,/prova-vitrine/nao-deve-vazar.txt",
    })
    @DisplayName("função, MCP, /q e estático fora da lista: 403 com a mensagem da vitrine")
    void recusa(String metodo, String caminho) {
        given().contentType("application/json").body("{}")
            .request(metodo, caminho)
            .then().statusCode(403)
            .header(GuardaVitrine.CABECALHO_VITRINE, "1")
            .body("vitrine", equalTo(true));
    }

    @Test
    @DisplayName("o filtro roda ANTES do estático: arquivo plantado fora da lista é recusado")
    void estaticoForaDaListaRecusado() {
        given().get("/prova-vitrine/nao-deve-vazar.txt").then().statusCode(403).body("vitrine", equalTo(true));
    }

    @Test
    @DisplayName("navegação direta (botão que abre aba) recebe página legível, não JSON cru")
    void navegacaoRecebePagina() {
        given().header("Accept", "text/html,application/xhtml+xml").get("/api/telemetria/exportar")
            .then().statusCode(403)
            .contentType(containsString("text/html"))
            .body(containsString("Vitrine do KRONOS"))
            .body(containsString("href=\"/\""));
        given().header("Accept", "application/json").get("/api/telemetria/exportar")
            .then().statusCode(403).body("vitrine", equalTo(true));
    }

    @Test
    @DisplayName("MCP recusado pela vitrine (403), não apenas ausente (404)")
    void mcpRecusado() {
        given().contentType("application/json").body("{}").post("/mcp").then().statusCode(403);
        given().get("/mcp/sse").then().statusCode(403);
    }

    @Test
    @DisplayName("GET com efeito (lista o disco do servidor) é recusado")
    void navegadorDePastasRecusado() {
        given().get("/api/navegador/pastas").then().statusCode(403).body("vitrine", equalTo(true));
    }

    @Test
    @DisplayName("dados de demonstração no lugar do que leria disco, rede, LLM ou Redis")
    void demonstracao() {
        given().get("/api/telemetria").then().statusCode(200).body("vitrineRetrato.data", equalTo("2026-10-14"));
        given().get("/api/telemetria/fatias").then().statusCode(200);
        given().get("/api/telemetria/fatias/revisao").then().statusCode(200);
        given().get("/api/telemetria/fatias/nao-existe").then().statusCode(404);
        given().get("/api/llm/status").then().statusCode(200).body("online", equalTo(false));
        given().get("/api/fluxo/status").then().statusCode(200).body("conectado", equalTo(false));
        given().get("/api/dialogo/capacidade").then().statusCode(200).body("nativo", equalTo(false));
    }

    @ParameterizedTest(name = "{0} {1}")
    @CsvSource({
        "POST,/api/pipeline/parar",
        "POST,/api//pipeline/parar",
        "POST,/api/./pipeline/parar",
        "POST,/api/x/../pipeline/parar",
        "POST,/API/PIPELINE/PARAR",
        "POST,/api/pipeline/parar;x=1",
        "POST,/api%2Fpipeline%2Fparar",
        "GET,/api//navegador/pastas",
        "GET,/api/status/../navegador/pastas",
        "GET,/%61pi/navegador/pastas",
        "GET,/api/docs/..%2F..%2Fbuild.gradle",
        "GET,/api/telemetria/fatias/../exportar",
        "OPTIONS,/api/status",
        "HEAD,/api/status",
        "TRACE,/",
    })
    @DisplayName("A1 por socket cru: caminho disfarçado e método não-leitura não passam")
    void caminhoDisfarcado(String metodo, String caminho) throws IOException {
        String status = linhaDeStatus(metodo, caminho);
        assertTrue(status.startsWith("HTTP/1.1 4"), metodo + " " + caminho + " -> " + status);
        assertFalse(status.startsWith("HTTP/1.1 404") && caminho.contains("pipeline"),
            "rota perigosa respondeu 404 em vez de 403 — prova fraca: " + caminho);
    }

    @Test
    @DisplayName("controle do socket cru: o mesmo instrumento recebe 200 num caminho permitido")
    void controleDoSocket() throws IOException {
        assertEquals("HTTP/1.1 200 OK", linhaDeStatus("GET", "/api/status"));
    }

    @Test
    @DisplayName("SSE de logs: só batimento e o aviso da vitrine; o contador fecha ao sair")
    void sseLogs() throws Exception {
        String lido = lerSse("/api/logs/stream", "data: vitrine-");
        assertTrue(lido.contains("event: heartbeat"), lido);
        assertTrue(lido.contains("data: vitrine-"), lido);
        aguardarSseFechar();
    }

    @Test
    @DisplayName("SSE de telemetria: entrega o retrato de demonstração")
    void sseTelemetria() throws Exception {
        String lido = lerSse("/api/telemetria/stream", "vitrineRetrato");
        assertTrue(lido.contains("event: telemetria"), lido);
        aguardarSseFechar();
    }

    @Test
    @DisplayName("segunda camada: dentro da aplicação a fila recusa e a tarefa nunca roda")
    void filaRecusa() {
        assertTrue(ModoVitrine.ativa(), "ModoVitrine deveria estar ligado no perfil da vitrine");
        AtomicBoolean rodou = new AtomicBoolean(false);
        assertThrows(OperacaoBloqueadaNaVitrineException.class, () -> fila.submeter(() -> rodou.set(true)));
        assertThrows(OperacaoBloqueadaNaVitrineException.class, () -> fila.executarEAguardar(() -> {
            rodou.set(true);
            return null;
        }));
        assertFalse(rodou.get(), "a tarefa rodou apesar da recusa");
    }

    private String linhaDeStatus(String metodo, String caminho) throws IOException {
        try (Socket s = new Socket(raiz.getHost(), raiz.getPort())) {
            s.setSoTimeout(5000);
            OutputStream out = s.getOutputStream();
            out.write((metodo + " " + caminho + " HTTP/1.1\r\nHost: localhost\r\nContent-Length: 0\r\n"
                + "Connection: close\r\n\r\n").getBytes(StandardCharsets.ISO_8859_1));
            out.flush();
            BufferedReader in = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.ISO_8859_1));
            String linha = in.readLine();
            return linha == null ? "(sem resposta)" : linha;
        }
    }

    private String lerSse(String caminho, String ate) throws IOException {
        StringBuilder sb = new StringBuilder();
        try (Socket s = new Socket(raiz.getHost(), raiz.getPort())) {
            s.setSoTimeout(5000);
            OutputStream out = s.getOutputStream();
            out.write(("GET " + caminho + " HTTP/1.1\r\nHost: localhost\r\nAccept: text/event-stream\r\n\r\n")
                .getBytes(StandardCharsets.ISO_8859_1));
            out.flush();
            BufferedReader in = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8));
            long limite = System.currentTimeMillis() + 5000;
            String linha;
            try {
                while (System.currentTimeMillis() < limite && (linha = in.readLine()) != null) {
                    sb.append(linha).append('\n');
                    if (linha.contains(ate)) {
                        break;
                    }
                }
            } catch (SocketTimeoutException e) {
                sb.append("(tempo esgotado)\n");
            }
            assertTrue(guarda.sseAbertasAgora() >= 1, "o fluxo deveria estar contado como aberto");
        }
        return sb.toString();
    }

    private void aguardarSseFechar() throws InterruptedException {
        long limite = System.currentTimeMillis() + 5000;
        while (guarda.sseAbertasAgora() != 0 && System.currentTimeMillis() < limite) {
            Thread.sleep(50);
        }
        assertEquals(0, guarda.sseAbertasAgora(), "conexão SSE fechada continuou contada");
    }
}
