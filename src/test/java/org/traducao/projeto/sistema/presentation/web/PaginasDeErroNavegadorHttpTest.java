package org.traducao.projeto.sistema.presentation.web;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * PROPÓSITO DE NEGÓCIO: prova, no servidor HTTP real, que a pessoa navegando ganha a página de erro
 * do KRONOS e que o cliente de API continua recebendo exatamente o que recebia antes.
 *
 * <h2>O prejuízo que originou</h2>
 * Medido em 14/10/2026, antes da mudança: endereço inexistente devolvia o HTML cru
 * {@code <html><body><h1>Resource not found</h1></body></html>} e uma ação de processamento aberta
 * pela barra do navegador ({@code GET /api/sistema/sair}) devolvia 405 com corpo VAZIO — tela em
 * branco, sem dizer o que fazer.
 *
 * <h2>Invariantes do domínio</h2>
 * <ul>
 *   <li>navegação ({@code GET}/{@code HEAD} + {@code text/html}) recebe a página com o MESMO status,
 *       {@code X-Request-Id} igual ao que vai no HTML, e nenhuma mensagem de exceção;</li>
 *   <li>{@code fetch} do painel ({@code *}{@code /*}) e cliente JSON recebem o corpo de antes,
 *       byte a byte no 404;</li>
 *   <li>o critério de navegação discrimina na fronteira: mesmo método, {@code Accept} diferente.</li>
 * </ul>
 *
 * <h2>Comportamento em caso de falha</h2>
 * Cada asserção nomeia o caso. Se o mapper de 405 não for chamado pelo RESTEasy, o teste do 405
 * reprova mostrando o corpo recebido.
 */
@QuarkusTest
class PaginasDeErroNavegadorHttpTest {

    private static final String NAVEGADOR = "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8";
    private static final Pattern META_RID = Pattern.compile("<meta name=\"kc-rid\" content=\"(KC-[0-9A-F]{8})\">");

    private static String ridDoHtml(String corpo) {
        Matcher m = META_RID.matcher(corpo);
        return m.find() ? m.group(1) : null;
    }

    @Test
    @DisplayName("navegação a endereço inexistente: página 404 do KRONOS, com o mesmo Request ID no cabeçalho e no HTML")
    void navegacaoInexistenteRecebePagina404() {
        Response r = given().accept(NAVEGADOR).when().get("/pagina-que-nao-existe");
        String corpo = r.getBody().asString();
        assertEquals(404, r.statusCode(), "o status original tem de ser mantido");
        assertTrue(r.contentType().startsWith("text/html"), "content-type: " + r.contentType());
        assertTrue(corpo.contains("data-code=\"404\"") && corpo.contains("Página não encontrada"),
            "esperava a página 404 do kit; recebeu: " + corpo.substring(0, Math.min(200, corpo.length())));
        String rid = r.getHeader("X-Request-Id");
        assertNotNull(rid, "sem X-Request-Id");
        assertTrue(rid.matches("KC-[0-9A-F]{8}"), "formato do Request ID: " + rid);
        assertEquals(rid, ridDoHtml(corpo), "o Request ID do HTML tem de ser o do cabeçalho (o mesmo vai ao log)");
        assertEquals("no-store", r.getHeader("Cache-Control"));
        assertFalse(corpo.contains("Exception") || corpo.contains("at org.") || corpo.contains("kc-detail"),
            "nada do servidor (exceção, pilha) pode entrar na página");
    }

    @Test
    @DisplayName("cliente de API recebe o 404 de antes, byte a byte (JSON e */*)")
    void apiRecebe404DeAntes() {
        for (String accept : new String[] {"application/json", "*/*"}) {
            Response r = given().accept(accept).when().get("/api/rota-que-nao-existe");
            assertEquals(404, r.statusCode(), accept);
            assertEquals(PaginasDeErroNavegador.CORPO_404_PADRAO, r.getBody().asString(), "corpo mudou para " + accept);
            assertEquals(null, r.getHeader("X-Request-Id"), "API não ganha cabeçalho novo: " + accept);
        }
    }

    @Test
    @DisplayName("POST com Accept de navegador não é navegação: recebe o 404 de antes")
    void postNaoEhNavegacao() {
        Response r = given().accept(NAVEGADOR).when().post("/pagina-que-nao-existe");
        assertEquals(404, r.statusCode());
        assertEquals(PaginasDeErroNavegador.CORPO_404_PADRAO, r.getBody().asString());
    }

    @Test
    @DisplayName("HEAD de navegação: 404 com cabeçalhos e sem corpo")
    void headDeNavegacao() {
        Response r = given().accept(NAVEGADOR).when().head("/pagina-que-nao-existe");
        assertEquals(404, r.statusCode());
        assertNotNull(r.getHeader("X-Request-Id"));
        assertEquals("", r.getBody().asString());
    }

    @Test
    @DisplayName("ação de processamento aberta pela barra do navegador: página 405 em vez de tela em branco")
    void navegacaoComMetodoErradoRecebePagina405() {
        Response r = given().accept(NAVEGADOR).when().get("/api/sistema/sair");
        String corpo = r.getBody().asString();
        assertEquals(405, r.statusCode());
        assertTrue(corpo.contains("data-code=\"405\"") && corpo.contains("Método não permitido"),
            "esperava a página 405; recebeu (" + corpo.length() + " chars): " + corpo.substring(0, Math.min(200, corpo.length())));
        assertEquals(r.getHeader("X-Request-Id"), ridDoHtml(corpo));
    }

    @Test
    @DisplayName("405 para a API continua como antes: sem corpo e sem cabeçalho novo")
    void apiRecebe405DeAntes() {
        Response r = given().accept("*/*").when().get("/api/sistema/sair");
        assertEquals(405, r.statusCode());
        assertEquals("", r.getBody().asString());
        assertEquals(null, r.getHeader("X-Request-Id"));
    }

    @Test
    @DisplayName("rotas que existem não são tocadas: /api/status responde, e a página estática abre")
    void rotasExistentesIntocadas() {
        given().accept(NAVEGADOR).when().get("/api/status").then().statusCode(200);
        given().accept("*/*").when().get("/errors/404.html").then().statusCode(200);
        given().accept("*/*").when().get("/errors/assets/kronos-error.js").then().statusCode(200);
        given().accept("*/*").when().get("/dica/dica.js").then().statusCode(200);
    }

    @Test
    @DisplayName("critério de navegação discrimina na fronteira: mesmo método, Accept diferente")
    void criterioDeNavegacaoNaFronteira() {
        assertTrue(PaginasDeErroNavegador.ehNavegacao("GET", NAVEGADOR));
        assertTrue(PaginasDeErroNavegador.ehNavegacao("HEAD", "text/html"));
        assertTrue(PaginasDeErroNavegador.ehNavegacao("get", "TEXT/HTML"));
        // mesmo método, legítimo de API:
        assertFalse(PaginasDeErroNavegador.ehNavegacao("GET", "*/*"));
        assertFalse(PaginasDeErroNavegador.ehNavegacao("GET", "application/json"));
        assertFalse(PaginasDeErroNavegador.ehNavegacao("GET", null));
        // mesmo Accept, método que não é leitura:
        assertFalse(PaginasDeErroNavegador.ehNavegacao("POST", NAVEGADOR));
        assertFalse(PaginasDeErroNavegador.ehNavegacao("DELETE", "text/html"));
    }
}
