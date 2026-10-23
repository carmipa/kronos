package org.traducao.projeto.vitrine.presentation.web;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.response.Response;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.traducao.projeto.vitrine.application.IndexacaoVitrine;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * PROPÓSITO DE NEGÓCIO: prova, na resposta HTTP que a vitrine entrega (nunca lendo o arquivo), que
 * um buscador encontra a página pública, entende qual é o endereço oficial dela e não rastreia a
 * API nem os pedaços de tela.
 *
 * <p>INVARIANTES DO DOMÍNIO:
 * <ul>
 *   <li>O {@code robots.txt} é lido pelo {@link LeitorRobotsTxt}, como um buscador lê; nenhuma
 *       asserção procura texto solto no arquivo.</li>
 *   <li>As asserções sobre o {@code <head>} só valem numa resposta que comprovadamente é a página
 *       inteira (controle positivo no próprio teste): página de erro curta passaria com o defeito.</li>
 *   <li>O endereço oficial não segue o cabeçalho {@code Host}.</li>
 * </ul>
 *
 * <p>COMPORTAMENTO EM CASO DE FALHA: cada asserção nomeia o defeito e o valor recebido.
 */
@QuarkusTest
@TestProfile(GuardaVitrineHttpTest.PerfilVitrine.class)
class IndexacaoVitrineHttpTest {

    private static final String NAVEGADOR = "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8";
    private static final List<String> BUSCADORES = List.of("Googlebot", "Bingbot", "DuckDuckBot", "robo-qualquer");
    private static final String CANONICO = IndexacaoVitrine.enderecoAbsoluto("/");

    private static LeitorRobotsTxt robots() {
        Response r = given().get(IndexacaoVitrine.CAMINHO_ROBOTS);
        assertEquals(200, r.statusCode(), "robots.txt não responde 200");
        assertTrue(r.contentType().startsWith("text/plain"), "robots.txt com tipo " + r.contentType());
        return new LeitorRobotsTxt(r.asString());
    }

    private static String paginaInicial(String host) {
        var pedido = given().redirects().follow(false).header("Accept", NAVEGADOR);
        if (host != null) {
            pedido = pedido.header("Host", host);
        }
        Response r = pedido.get("/");
        assertEquals(200, r.statusCode(), "a página pública / não responde 200");
        String html = r.asString();
        assertTrue(html.contains("id=\"page-title\"") && html.contains("<main"),
            "controle: a resposta de / não é a página inteira, as asserções do <head> não provariam nada");
        return html;
    }

    private static List<String> tags(String html, String nome) {
        List<String> r = new ArrayList<>();
        Matcher m = Pattern.compile("<" + nome + "\\b[^>]*>", Pattern.CASE_INSENSITIVE | Pattern.DOTALL).matcher(html);
        while (m.find()) {
            r.add(m.group());
        }
        return r;
    }

    private static String atributo(String tag, String nome) {
        Matcher m = Pattern.compile("\\s" + nome + "\\s*=\\s*\"([^\"]*)\"", Pattern.CASE_INSENSITIVE).matcher(tag);
        return m.find() ? m.group(1) : null;
    }

    private static List<String> metas(String html, String chave, String valor) {
        return tags(html, "meta").stream().filter(t -> valor.equals(atributo(t, chave))).toList();
    }

    private static List<String> conteudos(String html, String chave, String valor) {
        return metas(html, chave, valor).stream().map(t -> atributo(t, "content")).toList();
    }

    @Test
    @DisplayName("robots.txt: buscadores abrem a página e fecham a API e cada pedaço de tela")
    void robotsFechaOQueNaoEPagina() {
        LeitorRobotsTxt leitor = robots();
        for (String robo : BUSCADORES) {
            assertTrue(leitor.permitido(robo, "/"), robo + " não pode buscar a página pública");
            for (String fechado : IndexacaoVitrine.BLOQUEADOS) {
                assertFalse(leitor.permitido(robo, fechado), robo + " pode buscar " + fechado);
            }
            assertFalse(leitor.permitido(robo, "/sobre/sobre.html?v=3.2"), robo + " busca o fragmento com versão na URL");
            assertFalse(leitor.permitido(robo, "/api/status"), robo + " busca a API");
            // o que a página precisa para ser desenhada continua aberto
            for (String aberto : List.of("/sobre/sobre.js", "/css/base.css", "/js/app.js", "/img/og-kronos.png")) {
                assertTrue(leitor.permitido(robo, aberto), robo + " não pode buscar " + aberto);
            }
            // páginas de erro levam noindex: fechá-las aqui impediria a leitura do noindex
            assertTrue(leitor.permitido(robo, "/errors/404.html"), robo + " não pode ler o noindex das páginas de erro");
        }
        assertFalse(leitor.permitido("GPTBot", "/"), "controle: coletor de IA deveria estar fechado");
        assertEquals(List.of(IndexacaoVitrine.enderecoAbsoluto(IndexacaoVitrine.CAMINHO_SITEMAP)), leitor.sitemaps(),
            "linha Sitemap do robots.txt fora do endereço canônico");
    }

    @Test
    @DisplayName("A1: só /robots.txt exato é o robots; o mesmo nome em outro lugar é endereço inexistente")
    void robotsSoNoEnderecoExato() {
        given().header("Accept", NAVEGADOR).get("/sobre/robots.txt").then().statusCode(404);
        given().header("Accept", NAVEGADOR).get("/robots.txt.bak").then().statusCode(404);
        Response head = given().head(IndexacaoVitrine.CAMINHO_ROBOTS);
        assertEquals(200, head.statusCode(), "HEAD do robots.txt");
        given().post(IndexacaoVitrine.CAMINHO_ROBOTS).then().statusCode(403);
    }

    @Test
    @DisplayName("sitemap: toda URL é da lista de páginas públicas, responde 200 sem redirecionar e está aberta no robots")
    void sitemapSoComPaginaQueResponde() {
        Response r = given().get(IndexacaoVitrine.CAMINHO_SITEMAP);
        assertEquals(200, r.statusCode(), "sitemap.xml não responde 200");
        assertTrue(r.contentType().startsWith("application/xml"), "sitemap.xml com tipo " + r.contentType());
        String xml = r.asString();
        assertFalse(xml.contains("lastmod") || xml.contains("priority"), "sitemap com lastmod/priority inventado");
        List<String> locs = new ArrayList<>();
        Matcher m = Pattern.compile("<loc>([^<]+)</loc>").matcher(xml);
        while (m.find()) {
            locs.add(m.group(1));
        }
        assertEquals(IndexacaoVitrine.PAGINAS_PUBLICAS.stream().map(IndexacaoVitrine::enderecoAbsoluto).toList(), locs,
            "sitemap diferente da lista de páginas públicas");
        LeitorRobotsTxt leitor = robots();
        for (String loc : locs) {
            assertTrue(loc.startsWith(IndexacaoVitrine.URL_PUBLICA + "/"), "URL do sitemap fora do host canônico: " + loc);
            String caminho = loc.substring(IndexacaoVitrine.URL_PUBLICA.length());
            Response pagina = given().redirects().follow(false).header("Accept", NAVEGADOR).get(caminho);
            assertEquals(200, pagina.statusCode(), "URL do sitemap não responde 200 direto: " + loc);
            assertTrue(leitor.permitido("Googlebot", caminho), "URL do sitemap fechada no robots: " + loc);
        }
    }

    @Test
    @DisplayName("<head>: um título, uma descrição, canonical e Open Graph no endereço oficial")
    void headDaPaginaPublica() {
        String html = paginaInicial(null);
        assertEquals(1, tags(html, "title").size(), "a página tem " + tags(html, "title").size() + " <title>");
        List<String> descricao = conteudos(html, "name", "description");
        assertEquals(1, descricao.size(), "a página tem " + descricao.size() + " meta description");
        assertFalse(descricao.get(0).isBlank(), "meta description vazia");

        List<String> canonicos = tags(html, "link").stream()
            .filter(t -> "canonical".equals(atributo(t, "rel"))).map(t -> atributo(t, "href")).toList();
        assertEquals(List.of(CANONICO), canonicos, "canonical ausente, duplicado ou fora do endereço oficial");
        assertEquals(List.of(CANONICO), conteudos(html, "property", "og:url"), "og:url fora do endereço oficial");
        for (String og : List.of("og:title", "og:description", "og:type", "og:image")) {
            List<String> valor = conteudos(html, "property", og);
            assertEquals(1, valor.size(), "página com " + valor.size() + " " + og);
            assertFalse(valor.get(0).isBlank(), og + " vazio");
        }
        String imagem = conteudos(html, "property", "og:image").get(0);
        assertTrue(imagem.startsWith(IndexacaoVitrine.URL_PUBLICA + "/"), "og:image não é absoluta no host oficial: " + imagem);
        Response arquivo = given().get(imagem.substring(IndexacaoVitrine.URL_PUBLICA.length()));
        assertEquals(200, arquivo.statusCode(), "a imagem do og:image não responde 200");
        assertTrue(arquivo.contentType().startsWith("image/"), "og:image com tipo " + arquivo.contentType());
    }

    @Test
    @DisplayName("o endereço oficial não muda com Host forjado")
    void canonicalNaoSegueOHost() {
        String html = paginaInicial("atacante.example");
        List<String> canonicos = tags(html, "link").stream()
            .filter(t -> "canonical".equals(atributo(t, "rel"))).map(t -> atributo(t, "href")).toList();
        assertEquals(List.of(CANONICO), canonicos, "com Host forjado, o canonical sumiu ou mudou de endereço");
        assertEquals(List.of(CANONICO), conteudos(html, "property", "og:url"), "com Host forjado, o og:url sumiu ou mudou de endereço");
    }

    @Test
    @DisplayName("semântica: um <h1>, um <main> e títulos sem salto de nível")
    void semantica() {
        String html = paginaInicial(null);
        assertEquals(1, tags(html, "h1").size(), "a página tem " + tags(html, "h1").size() + " <h1>");
        assertEquals(1, tags(html, "main").size(), "a página tem " + tags(html, "main").size() + " <main>");
        Matcher m = Pattern.compile("<h([1-6])\\b", Pattern.CASE_INSENSITIVE).matcher(html);
        int anterior = 0;
        while (m.find()) {
            int nivel = Integer.parseInt(m.group(1));
            assertTrue(anterior == 0 || nivel <= anterior + 1, "salto de título: h" + anterior + " para h" + nivel);
            anterior = nivel;
        }
        assertTrue(anterior > 0, "controle: nenhum título lido");
    }

    @Test
    @DisplayName("imagens: toda <img> da página tem alt, width e height")
    void imagensComAltEDimensao() {
        List<String> imgs = tags(paginaInicial(null), "img");
        assertFalse(imgs.isEmpty(), "controle: nenhuma <img> lida na página");
        for (String img : imgs) {
            for (String obrigatorio : List.of("alt", "width", "height")) {
                assertTrue(atributo(img, obrigatorio) != null, "<img> sem " + obrigatorio + ": " + img.replaceAll("\\s+", " "));
            }
        }
    }

    @Test
    @DisplayName("/index.html redireciona com 301 para /, um endereço só para o mesmo documento")
    void indexRedirecionaParaRaiz() {
        for (Response r : List.of(
                given().redirects().follow(false).get(IndexacaoVitrine.CAMINHO_INDEX),
                given().redirects().follow(false).head(IndexacaoVitrine.CAMINHO_INDEX))) {
            assertEquals(301, r.statusCode(), "/index.html não redireciona com 301");
            assertEquals("/", r.header("Location"), "/index.html redireciona para outro lugar");
        }
    }

    @Test
    @DisplayName("fragmentos continuam servidos à página, e as páginas de erro levam noindex")
    void fragmentosServidosEErrosComNoindex() {
        for (String fragmento : IndexacaoVitrine.FRAGMENTOS) {
            given().get(fragmento).then().statusCode(200);
        }
        String erro = given().get("/errors/404.html").asString();
        assertTrue(erro.contains("noindex"), "a página de erro servida não leva noindex");
    }
}
