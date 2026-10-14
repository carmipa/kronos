package org.traducao.projeto.arquitetura;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * PROPÓSITO DE NEGÓCIO: as páginas de erro de {@code static/errors/} são geradas por
 * {@code ferramentas/paginas-de-erro/gerar.py} a partir de um kit externo (v1.0.0, 14/10/2026) e
 * adaptadas ao KRONOS. Esta catraca congela as adaptações que, se desfeitas, quebram em silêncio.
 *
 * <h2>O prejuízo que originou</h2>
 * Auditoria do kit em 14/10/2026: o retorno automático aceitava {@code ?from=//outro-site}
 * (redirecionamento aberto); o chip de saúde consultava {@code /q/health}, que o KRONOS não tem — a
 * página diria "Backend inacessível" para sempre e o retry nunca voltaria; havia ação para
 * {@code /login}, que não existe; e cada tooltip era de implementação própria, não o componente
 * canônico. Página gerada e editada à mão diverge do gerador, e a próxima geração apaga a edição.
 *
 * <h2>Invariantes do domínio</h2>
 * <ul>
 *   <li>uma página por código do catálogo ({@code kronos-error-catalog.js}), mais a dinâmica;</li>
 *   <li>toda página declara {@code lang="pt-BR"} e o próprio código, carrega o tooltip canônico e o
 *       módulo de idioma do painel na MESMA versão que {@code app.js} e {@code index.html} usam;</li>
 *   <li>todo {@code aria-describedby} aponta para um elemento {@code role="tooltip"} da página, e os
 *       ids são únicos;</li>
 *   <li>nenhuma referência a {@code /q/health} ou {@code /login}; nenhum endereço externo além do site do autor e do
 *       repositório do projeto;</li>
 *   <li>o retorno automático só aceita a mesma origem.</li>
 * </ul>
 *
 * <h2>Comportamento em caso de falha</h2>
 * Nomeia a página e o defeito. Catálogo ou pasta ausentes reprovam como instrumento cego. O
 * comportamento no navegador (varredura da regra 6-A, axe, redirecionamento, idioma, celular) é
 * provado por {@code ferramentas/navegador/verificar-paginas-de-erro.mjs}.
 */
class CatracaPaginasDeErroTest {

    private static final Path ESTATICOS = Path.of("src", "main", "resources", "static");
    private static final Path ERROS = ESTATICOS.resolve("errors");
    private static final Path CATALOGO = ERROS.resolve("assets").resolve("kronos-error-catalog.js");
    private static final Path JS_KIT = ERROS.resolve("assets").resolve("kronos-error.js");
    private static final String REPO = "https://github.com/carmipa/traducao_animes_llm_local_quarkus";
    /** Página do autor, no "Desenvolvido por" (Paulo, 14/10/2026). */
    private static final String SITE = "https://carminati.dev.br/";

    private static final Pattern DESCRITO_POR = Pattern.compile("aria-describedby=\"([^\"]+)\"");
    private static final Pattern ID = Pattern.compile("\\sid=\"([^\"]+)\"");
    private static final Pattern URL_EXTERNA = Pattern.compile("https?://[^\"'\\s<>)]+");

    /** Defeitos de marcação de UMA página; usado nas páginas reais e na calibração. */
    static List<String> defeitosDeMarcacao(String html) {
        List<String> out = new ArrayList<>();
        Set<String> ids = new HashSet<>();
        Matcher mi = ID.matcher(html);
        while (mi.find()) {
            if (!ids.add(mi.group(1))) {
                out.add("id repetido: " + mi.group(1));
            }
        }
        Matcher md = DESCRITO_POR.matcher(html);
        while (md.find()) {
            String ref = md.group(1);
            Matcher alvo = Pattern.compile("<[^>]*\\sid=\"" + Pattern.quote(ref) + "\"[^>]*>").matcher(html);
            if (!alvo.find()) {
                out.add("aria-describedby sem alvo: " + ref);
            } else if (!alvo.group().contains("role=\"tooltip\"")) {
                out.add("aria-describedby aponta para algo que não é tooltip: " + ref);
            }
        }
        if (html.contains("/q/health")) {
            out.add("consulta /q/health, que o KRONOS não tem");
        }
        if (html.contains("'/login'") || html.contains("href=\"/login")) {
            out.add("ação de /login, rota inexistente");
        }
        Matcher mu = URL_EXTERNA.matcher(html);
        while (mu.find()) {
            if (!mu.group().equals(REPO) && !mu.group().equals(SITE)) {
                out.add("endereço externo: " + mu.group());
            }
        }
        return out;
    }

    @Test
    @DisplayName("instrumento: reprova tooltip órfão, id repetido e /q/health; aceita a marcação canônica")
    void instrumentoDiscriminaNaFronteira() {
        String bom = "<span class=\"dica\"><a href=\"/\" aria-describedby=\"d-a\">x</a>"
            + "<span id=\"d-a\" role=\"tooltip\" class=\"dica__texto\">Vai ao painel.</span></span>"
            + "<a href=\"" + REPO + "\">repo</a><script>health: '/api/status'</script>";
        assertEquals(List.of(), defeitosDeMarcacao(bom));
        assertEquals(1, defeitosDeMarcacao(bom.replace("aria-describedby=\"d-a\"", "aria-describedby=\"d-b\"")).size());
        assertEquals(1, defeitosDeMarcacao(bom.replace("role=\"tooltip\"", "role=\"note\"")).size());
        assertEquals(1, defeitosDeMarcacao(bom + "<i id=\"d-a\"></i>").size());
        assertEquals(1, defeitosDeMarcacao(bom.replace("/api/status", "/q/health")).size());
        assertEquals(1, defeitosDeMarcacao(bom + "<link href=\"https://fonts.googleapis.com/x\">").size());
        assertEquals(1, defeitosDeMarcacao(bom + "<a href=\"/login?from=/x\">").size());
    }

    @Test
    @DisplayName("uma página por código do catálogo, cada uma declarando o próprio código e pt-BR")
    void umaPaginaPorCodigo() throws IOException {
        JsonNode erros = catalogo().get("errors");
        assertTrue(erros != null && erros.size() >= 18, "instrumento cego: catálogo sem os erros do kit");
        List<String> faltas = new ArrayList<>();
        Iterator<String> codigos = erros.fieldNames();
        while (codigos.hasNext()) {
            String codigo = codigos.next();
            Path pagina = ERROS.resolve(codigo + ".html");
            if (!Files.isRegularFile(pagina)) {
                faltas.add(codigo + ": página ausente — rode ferramentas/paginas-de-erro/gerar.py");
                continue;
            }
            String html = Files.readString(pagina, StandardCharsets.UTF_8);
            if (!html.contains("<html lang=\"pt-BR\" data-code=\"" + codigo + "\">")) {
                faltas.add(codigo + ": <html> sem lang=\"pt-BR\" e data-code=\"" + codigo + "\"");
            }
            String titulo = erros.get(codigo).get("texto").get("title").asText();
            if (!html.contains(titulo.replace("&", "&amp;"))) {
                faltas.add(codigo + ": título do catálogo ausente na página (editada à mão?): " + titulo);
            }
        }
        assertTrue(Files.isRegularFile(ERROS.resolve("error.html")), "página dinâmica error.html ausente");
        assertEquals(List.of(), faltas);
    }

    @Test
    @DisplayName("toda página carrega o tooltip canônico e o módulo de idioma na versão do painel, sem defeito de marcação")
    void paginasLigadasAoPainel() throws IOException {
        String versaoJs = versao(ESTATICOS.resolve("js").resolve("app.js"), "'\\.\\./i18n/i18n\\.js\\?v=([^']+)'");
        String versaoCss = versao(ESTATICOS.resolve("index.html"), "i18n/i18n.css\\?v=([^\"]+)\"");
        List<Path> paginas;
        try (var s = Files.list(ERROS)) {
            paginas = s.filter(p -> p.toString().endsWith(".html")).sorted().toList();
        }
        assertTrue(paginas.size() >= 19, "instrumento cego: só " + paginas.size() + " páginas em " + ERROS);
        List<String> defeitos = new ArrayList<>();
        for (Path p : paginas) {
            String html = Files.readString(p, StandardCharsets.UTF_8);
            String nome = p.getFileName().toString();
            for (String exigido : List.of("href=\"/dica/dica.css\"", "src=\"/dica/dica.js\"",
                    "href=\"/i18n/i18n.css?v=" + versaoCss + "\"",
                    "from '/i18n/i18n.js?v=" + versaoJs + "'; inicializarI18n();",
                    "id=\"google_translate_element\"", "health: '/api/status'", "data-idioma=\"en-US\"")) {
                if (!html.contains(exigido)) {
                    defeitos.add(nome + ": falta " + exigido);
                }
            }
            for (String d : defeitosDeMarcacao(html)) {
                defeitos.add(nome + ": " + d);
            }
        }
        assertEquals(List.of(), defeitos);
    }

    @Test
    @DisplayName("tooltip canônico copiado sem edição e retorno automático só para a mesma origem")
    void componenteCanonicoEMesmaOrigem() throws IOException {
        String css = Files.readString(ESTATICOS.resolve("dica").resolve("dica.css"), StandardCharsets.UTF_8);
        String js = Files.readString(ESTATICOS.resolve("dica").resolve("dica.js"), StandardCharsets.UTF_8);
        assertTrue(css.startsWith("/* dica.css v1.0 (2026-10-05)") && js.startsWith("/* dica.js v1.0 (2026-10-05)"),
            "o tooltip canônico tem de ser a cópia da fonte, com o cabeçalho de versão intacto");
        String kit = Files.readString(JS_KIT, StandardCharsets.UTF_8);
        assertTrue(kit.contains("u.origin === location.origin"),
            "o retorno automático (retryTarget) tem de recusar outra origem: ?from=//outro-site é redirecionamento aberto");
        assertTrue(!kit.contains("data-tip") && !kit.contains("kc-tip"),
            "o tooltip próprio do kit voltou: o projeto usa o componente canônico (/dica/dica.js)");
    }

    private static JsonNode catalogo() throws IOException {
        assertTrue(Files.isRegularFile(CATALOGO), "catálogo ausente: " + CATALOGO);
        String js = Files.readString(CATALOGO, StandardCharsets.UTF_8);
        int ini = js.indexOf("window.KC_CATALOG = ");
        int fim = js.lastIndexOf("};");
        assertTrue(ini >= 0 && fim > ini, "catálogo em formato inesperado");
        return new ObjectMapper().readTree(js.substring(ini + "window.KC_CATALOG = ".length(), fim + 1));
    }

    private static String versao(Path arquivo, String regex) throws IOException {
        Matcher m = Pattern.compile(regex).matcher(Files.readString(arquivo, StandardCharsets.UTF_8));
        assertTrue(m.find(), "versão não encontrada em " + arquivo + " (" + regex + ")");
        return m.group(1);
    }
}
