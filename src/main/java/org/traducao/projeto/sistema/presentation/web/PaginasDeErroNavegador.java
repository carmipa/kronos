package org.traducao.projeto.sistema.presentation.web;

import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.HttpServerResponse;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.ws.rs.NotAllowedException;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.Request;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriInfo;
import org.jboss.resteasy.reactive.server.ServerExceptionMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * PROPÓSITO DE NEGÓCIO: quem NAVEGA até um endereço que não existe (404) ou chama pela barra do
 * navegador uma rota que só aceita outro método (405) passa a ver a página de erro do KRONOS —
 * com o que aconteceu, o que fazer e um Request ID que também vai para o log — em vez do HTML cru
 * {@code <h1>Resource not found</h1>} ou de uma tela em branco. As páginas são as de
 * {@code static/errors/}, geradas por {@code ferramentas/paginas-de-erro/gerar.py}.
 *
 * <h2>Invariantes do domínio</h2>
 * <ul>
 *   <li><b>Só navegação.</b> Age apenas em {@code GET}/{@code HEAD} cujo {@code Accept} contém
 *       {@code text/html}. O painel chama a API com {@code fetch}, que manda {@code *}{@code /*}: a
 *       resposta dele continua byte a byte a de antes (404 com o HTML padrão do Vert.x; 405 do
 *       RESTEasy com o corpo e os cabeçalhos originais). Medido em 14/10/2026 antes da mudança.</li>
 *   <li><b>Status original, sem redirect.</b> A página sai com o mesmo 404/405.</li>
 *   <li><b>Nada do servidor na página.</b> Nenhuma mensagem de exceção, caminho de disco ou pilha é
 *       escrita no HTML — só o Request ID gerado aqui. A vitrine é pública, e o kit original punha
 *       a mensagem da exceção na página. A causa fica no log, com o mesmo Request ID.</li>
 *   <li>A vitrine continua decidindo antes: o filtro dela roda na frente de qualquer rota, e o que
 *       ele recusa nunca chega aqui.</li>
 * </ul>
 *
 * <h2>Comportamento em caso de falha</h2>
 * Resposta já escrita por outro handler: não interfere. Página ausente no classpath: responde o
 * mesmo status com uma página mínima e registra o defeito no log em vez de lançar. Erro ao ler o
 * arquivo vira {@link IllegalStateException} na primeira leitura daquele código.
 */
@ApplicationScoped
public class PaginasDeErroNavegador {

    private static final Logger log = LoggerFactory.getLogger(PaginasDeErroNavegador.class);

    /** Corpo do 404 padrão do roteador, medido em 14/10/2026: é o que cliente de API continua vendo. */
    static final String CORPO_404_PADRAO = "<html><body><h1>Resource not found</h1></body></html>";

    /**
     * Marcador onde entra o Request ID. Cópia declarada em {@code vitrine.presentation.web.GuardaVitrine}:
     * na vitrine o filtro dela responde antes deste handler, e a vitrine não depende de outra fatia.
     */
    private static final String META_RID_VAZIA = "<meta name=\"kc-rid\" content=\"\">";

    private final Map<Integer, String> paginas = new ConcurrentHashMap<>();

    /**
     * PROPÓSITO DE NEGÓCIO: liga a página 404 ao roteador, que é quem responde quando nenhuma rota,
     * nem o estático, atende o endereço.
     * INVARIANTES DO DOMÍNIO: um handler só, para o 404 do roteador.
     * COMPORTAMENTO EM CASO DE FALHA: sem roteador (contexto sem HTTP), o evento não chega e nada muda.
     */
    void registrar(@Observes Router router) {
        router.errorHandler(404, ctx -> responder404(ctx));
    }

    private void responder404(RoutingContext ctx) {
        HttpServerResponse res = ctx.response();
        if (res.headWritten() || res.ended()) {
            return;
        }
        String metodo = ctx.request().method().name();
        if (!ehNavegacao(metodo, ctx.request().getHeader("Accept"))) {
            res.setStatusCode(404).putHeader("Content-Type", "text/html; charset=utf-8").end(CORPO_404_PADRAO);
            return;
        }
        String rid = novoRequestId();
        registrarNoLog(404, metodo, ctx.request().path(), rid);
        res.setStatusCode(404)
            .putHeader("Content-Type", "text/html; charset=utf-8")
            .putHeader("X-Request-Id", rid)
            .putHeader("Cache-Control", "no-store");
        if (HttpMethod.HEAD.name().equals(metodo)) {
            res.end();
        } else {
            res.end(pagina(404, rid));
        }
    }

    /**
     * PROPÓSITO DE NEGÓCIO: a rota existe mas não aceita o método — na barra do navegador isso
     * acontece ao abrir uma ação de processamento (que é {@code POST}). Mostra a página 405.
     * INVARIANTES DO DOMÍNIO: fora da navegação devolve a resposta ORIGINAL da exceção, com o
     * cabeçalho {@code Allow} que o RESTEasy montou.
     * COMPORTAMENTO EM CASO DE FALHA: exceção sem resposta associada vira 405 sem corpo.
     */
    @ServerExceptionMapper
    public Response naoPermitido(NotAllowedException e, HttpHeaders headers, Request request, UriInfo uri) {
        String metodo = request.getMethod();
        if (!ehNavegacao(metodo, headers.getHeaderString(HttpHeaders.ACCEPT))) {
            return e.getResponse() != null ? e.getResponse() : Response.status(405).build();
        }
        String rid = novoRequestId();
        registrarNoLog(405, metodo, "/" + uri.getPath().replaceFirst("^/", ""), rid);
        Response.ResponseBuilder resposta = Response.status(405)
            .type("text/html; charset=utf-8")
            .header("X-Request-Id", rid)
            .header("Cache-Control", "no-store");
        if (e.getResponse() != null && e.getResponse().getHeaderString(HttpHeaders.ALLOW) != null) {
            resposta.header(HttpHeaders.ALLOW, e.getResponse().getHeaderString(HttpHeaders.ALLOW));
        }
        if (!HttpMethod.HEAD.name().equals(metodo)) {
            resposta.entity(pagina(405, rid));
        }
        return resposta.build();
    }

    /**
     * PROPÓSITO DE NEGÓCIO: separa a pessoa navegando (que precisa de uma página) do script que
     * chama a API (que precisa da resposta de sempre).
     * INVARIANTES DO DOMÍNIO: só {@code GET}/{@code HEAD} com {@code text/html} no {@code Accept}.
     * COMPORTAMENTO EM CASO DE FALHA: cabeçalho ausente conta como não-navegação.
     */
    static boolean ehNavegacao(String metodo, String accept) {
        boolean leitura = HttpMethod.GET.name().equalsIgnoreCase(metodo) || HttpMethod.HEAD.name().equalsIgnoreCase(metodo);
        return leitura && accept != null && accept.toLowerCase(Locale.ROOT).contains("text/html");
    }

    /**
     * PROPÓSITO DE NEGÓCIO: lê a página gerada (uma vez por código) e injeta o Request ID.
     * INVARIANTES DO DOMÍNIO: só o Request ID entra no HTML, e ele é gerado aqui (sem entrada do
     * cliente); nenhum outro dado do servidor.
     * COMPORTAMENTO EM CASO DE FALHA: página ausente vira uma página mínima com o código.
     */
    String pagina(int codigo, String rid) {
        String html = paginas.computeIfAbsent(codigo, this::carregar);
        return html.replace(META_RID_VAZIA, "<meta name=\"kc-rid\" content=\"" + rid + "\">");
    }

    private String carregar(int codigo) {
        String caminho = "META-INF/resources/errors/" + codigo + ".html";
        try (InputStream in = Thread.currentThread().getContextClassLoader().getResourceAsStream(caminho)) {
            if (in == null) {
                log.error("[PAGINA-ERRO] {} ausente no classpath: rode ferramentas/paginas-de-erro/gerar.py", caminho);
                return "<!DOCTYPE html><html lang=\"pt-BR\"><meta charset=\"utf-8\"><title>" + codigo
                    + "</title><h1>" + codigo + "</h1>" + META_RID_VAZIA + "</html>";
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("Falha ao ler a página de erro " + caminho, e);
        }
    }

    private static String novoRequestId() {
        return "KC-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase(Locale.ROOT);
    }

    private static void registrarNoLog(int codigo, String metodo, String caminho, String rid) {
        String limpo = caminho == null ? "" : caminho.replaceAll("\\p{Cntrl}", "?");
        if (limpo.length() > 300) {
            limpo = limpo.substring(0, 300) + "…";
        }
        log.info("[PAGINA-ERRO] {} {} {} -> página de erro (rid {})", codigo, metodo, limpo, rid);
    }
}
