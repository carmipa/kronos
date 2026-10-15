package org.traducao.projeto.arquitetura;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * PROPÓSITO DE NEGÓCIO: congela, no código, o que a responsividade do painel conquistou em 14/10/2026
 * e que um commit distraído desfaria sem nenhum teste reclamar: fonte e ícone servidos pelo próprio
 * KRONOS, layout que não estoura a tela do celular, menu em gaveta que não desloca as dicas, e dicas
 * no componente canônico da regra 6-A.
 *
 * <h2>O prejuízo que originou</h2>
 * Medido em 14/10/2026: com o Google bloqueado, cada ícone virava o nome do glifo por extenso e
 * empurrava o layout; os 19 painéis passavam da largura de 360 px; o balão antigo das dicas
 * ({@code [data-tooltip]::after} com {@code position:absolute}) ocupava largura mesmo invisível e o
 * celular encolhia o zoom da página (390 → 418 px); e {@code transform} num ancestral abriria a dica
 * canônica fora da tela.
 *
 * <h2>Invariantes do domínio</h2>
 * <ul>
 *   <li>nenhuma folha de estilo, script, fonte ou {@code @import} vem de fora (link de navegação para
 *       um site é legítimo; comentário que cita um domínio também);</li>
 *   <li>nenhum {@code width}/{@code min-width}/{@code inline-size} de {@code 100vw} (inclui a barra de
 *       rolagem e cria barra horizontal); {@code calc(100vw - …)} como teto é legítimo;</li>
 *   <li>grade que preenche usa {@code auto-fit}, nunca {@code auto-fill} (regra de CSS do Paulo);</li>
 *   <li>o balão antigo {@code [data-tooltip]::after/::before} não volta;</li>
 *   <li>a gaveta ({@code .sidebar}) não se move por {@code transform};</li>
 *   <li>toda dica escrita no HTML tem texto e no máximo 160 caracteres (regra 6-A);</li>
 *   <li>o {@code index.html} carrega {@code dica.css} antes do CSS do projeto e o {@code dica.js}; o
 *       {@code app.js} liga o conversor.</li>
 * </ul>
 *
 * <h2>Comportamento em caso de falha</h2>
 * Nomeia o arquivo e o trecho. Alvo vazio (nenhum CSS ou HTML lido) reprova como instrumento cego.
 * O comportamento no navegador — estouro, gaveta, alvo de toque, posição da dica, varredura e axe — é
 * provado por {@code ferramentas/navegador/verificar-responsividade.mjs}, que esta catraca não
 * substitui.
 */
class CatracaResponsividadeTest {

    private static final Path ESTATICOS = Path.of("src", "main", "resources", "static");
    private static final Path INDEX = ESTATICOS.resolve("index.html");
    private static final Path APP_JS = ESTATICOS.resolve("js").resolve("app.js");

    /** Recurso carregado de fora: folha de estilo, script, url() e @import com http(s). */
    private static final Pattern RECURSO_EXTERNO = Pattern.compile(
        "<link\\b[^>]*\\bhref\\s*=\\s*[\"']https?://[^\"']+[\"']"
            + "|<script\\b[^>]*\\bsrc\\s*=\\s*[\"']https?://[^\"']+[\"']"
            + "|url\\(\\s*[\"']?https?://[^)]+\\)"
            + "|@import\\s+(?:url\\()?\\s*[\"']https?://[^\"']+[\"']",
        Pattern.CASE_INSENSITIVE);
    private static final Pattern LARGURA_100VW = Pattern.compile(
        "(?<![-\\w])(?:min-)?(?:width|inline-size)\\s*:\\s*100vw\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern AUTO_FILL = Pattern.compile("repeat\\(\\s*auto-fill\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern BALAO_ANTIGO = Pattern.compile("\\[data-tooltip\\]\\s*::?(?:after|before)\\b");
    private static final Pattern GAVETA_COM_TRANSFORM = Pattern.compile("\\.sidebar\\b[^{}]*\\{[^}]*\\btransform\\s*:");
    private static final Pattern COMENTARIO_CSS = Pattern.compile("/\\*.*?\\*/", Pattern.DOTALL);
    private static final Pattern COMENTARIO_HTML = Pattern.compile("<!--.*?-->", Pattern.DOTALL);
    private static final Pattern DICA_NO_HTML = Pattern.compile("data-tooltip\\s*=\\s*\"([^\"]*)\"");

    static List<String> violacoesCss(String css) {
        String limpo = COMENTARIO_CSS.matcher(css).replaceAll(" ");
        List<String> out = new ArrayList<>();
        juntar(out, RECURSO_EXTERNO.matcher(limpo), "recurso de fora");
        juntar(out, LARGURA_100VW.matcher(limpo), "largura de 100vw");
        juntar(out, AUTO_FILL.matcher(limpo), "auto-fill (use auto-fit)");
        juntar(out, BALAO_ANTIGO.matcher(limpo), "balao antigo das dicas");
        juntar(out, GAVETA_COM_TRANSFORM.matcher(limpo), "gaveta com transform");
        return out;
    }

    static List<String> violacoesHtml(String html) {
        String limpo = COMENTARIO_HTML.matcher(html).replaceAll(" ");
        List<String> out = new ArrayList<>();
        juntar(out, RECURSO_EXTERNO.matcher(limpo), "recurso de fora");
        Matcher dica = DICA_NO_HTML.matcher(limpo);
        while (dica.find()) {
            String texto = desescapar(dica.group(1)).strip();
            if (texto.isEmpty()) {
                out.add("dica vazia");
            } else if (texto.codePointCount(0, texto.length()) > 160) {
                out.add("dica com " + texto.codePointCount(0, texto.length()) + " caracteres: "
                    + texto.substring(0, Math.min(60, texto.length())));
            }
        }
        return out;
    }

    private static void juntar(List<String> out, Matcher m, String rotulo) {
        while (m.find()) {
            out.add(rotulo + ": " + m.group().replaceAll("\\s+", " "));
        }
    }

    private static String desescapar(String s) {
        return s.replace("&quot;", "\"").replace("&#x27;", "'").replace("&#39;", "'")
            .replace("&lt;", "<").replace("&gt;", ">").replace("&amp;", "&");
    }

    @Test
    @DisplayName("instrumento: reprova o defeito e aceita o legitimo que tem o mesmo sinal")
    void instrumentoDiscriminaNaFronteira() {
        // recurso de fora x link de navegação e comentário com o mesmo domínio
        assertEquals(1, violacoesHtml("<link rel=\"stylesheet\" href=\"https://fonts.googleapis.com/css2?family=X\">").size());
        assertEquals(1, violacoesHtml("<script src=\"https://cdn.jsdelivr.net/npm/x.js\"></script>").size());
        assertEquals(0, violacoesHtml("<a href=\"https://github.com/carmipa\" target=\"_blank\">GitHub</a>").size());
        assertEquals(0, violacoesHtml("<!-- antes vinha de <link href=\"https://fonts.googleapis.com/x\"> -->").size());
        assertEquals(1, violacoesCss("@font-face { src: url(https://fonts.gstatic.com/x.woff2); }").size());
        assertEquals(1, violacoesCss("@import url(\"https://fonts.googleapis.com/css2?family=X\");").size());
        assertEquals(0, violacoesCss("/* o selo do img.shields.io quebrava; url(https://img.shields.io/x) */").size());
        assertEquals(0, violacoesCss("@font-face { src: url(material-symbols-outlined.woff2); }").size());
        // 100vw: largura cheia reprova; teto com calc passa
        assertEquals(1, violacoesCss(".x { width: 100vw; }").size());
        assertEquals(1, violacoesCss(".x { min-width:100vw }").size());
        assertEquals(0, violacoesCss(".x { max-width: calc(100vw - 3rem); }").size());
        assertEquals(0, violacoesCss(".x { max-inline-size: min(20rem, calc(100vw - 1rem)); }").size());
        // grade
        assertEquals(1, violacoesCss(".g { grid-template-columns: repeat(auto-fill, minmax(10rem, 1fr)); }").size());
        assertEquals(0, violacoesCss(".g { grid-template-columns: repeat(auto-fit, minmax(10rem, 1fr)); }").size());
        // balão antigo x atributo de dica sem pseudo-elemento
        assertEquals(1, violacoesCss("[data-tooltip]::after { content: attr(data-tooltip); }").size());
        assertEquals(0, violacoesCss("[data-dica] { position: relative; }").size());
        // gaveta: transform reprova; left passa; transform em outro seletor não é da gaveta
        assertEquals(1, violacoesCss(".sidebar.aberta { transform: translateX(0); }").size());
        assertEquals(0, violacoesCss(".sidebar.aberta { left: 0; visibility: visible; }").size());
        assertEquals(0, violacoesCss(".pipeline-card:hover { transform: translateY(-2px); }").size());
        // dica: 160 passa, 161 reprova, vazia reprova, entidade conta como 1 caractere
        assertEquals(0, violacoesHtml("<b data-tooltip=\"" + "a".repeat(160) + "\">x</b>").size());
        assertEquals(1, violacoesHtml("<b data-tooltip=\"" + "a".repeat(161) + "\">x</b>").size());
        assertEquals(1, violacoesHtml("<b data-tooltip=\"  \">x</b>").size());
        assertEquals(0, violacoesHtml("<b data-tooltip=\"" + "a".repeat(155) + "&quot;b&quot;\">x</b>").size());
    }

    @Test
    @DisplayName("CSS do painel: sem recurso de fora, sem 100vw, sem auto-fill, sem balao antigo, gaveta sem transform")
    void cssDoPainel() throws IOException {
        List<String> violacoes = new ArrayList<>();
        int lidos = 0;
        for (Path css : arquivos(".css")) {
            lidos++;
            for (String v : violacoesCss(Files.readString(css, StandardCharsets.UTF_8))) {
                violacoes.add(ESTATICOS.relativize(css) + " -> " + v);
            }
        }
        assertTrue(lidos >= 15, "NÃO VERIFICOU — só " + lidos + " CSS lidos em " + ESTATICOS.toAbsolutePath());
        assertTrue(violacoes.isEmpty(), "CSS que desfaz a responsividade:\n" + String.join("\n", violacoes));
    }

    @Test
    @DisplayName("HTML do painel: sem recurso de fora e toda dica com texto de ate 160 caracteres")
    void htmlDoPainel() throws IOException {
        List<String> violacoes = new ArrayList<>();
        int lidos = 0;
        int dicas = 0;
        for (Path html : arquivos(".html")) {
            lidos++;
            String texto = Files.readString(html, StandardCharsets.UTF_8);
            dicas += (int) DICA_NO_HTML.matcher(texto).results().count();
            for (String v : violacoesHtml(texto)) {
                violacoes.add(ESTATICOS.relativize(html) + " -> " + v);
            }
        }
        assertTrue(lidos >= 8 && dicas >= 100, "NÃO VERIFICOU — " + lidos + " HTML e " + dicas + " dicas lidos");
        assertTrue(violacoes.isEmpty(), "HTML que desfaz a responsividade ou a regra 6-A:\n" + String.join("\n", violacoes));
    }

    @Test
    @DisplayName("o painel carrega a dica canonica antes do CSS do projeto e liga o conversor")
    void componenteCanonicoLigado() throws IOException {
        String index = Files.readString(INDEX, StandardCharsets.UTF_8);
        int dicaCss = index.indexOf("href=\"dica/dica.css");
        int baseCss = index.indexOf("href=\"css/base.css");
        assertTrue(dicaCss > 0 && baseCss > 0, "index.html sem dica/dica.css ou sem css/base.css");
        assertTrue(dicaCss < baseCss, "dica.css tem de vir antes do base.css (as regras do projeto vencem os empates)");
        assertTrue(index.contains("src=\"dica/dica.js"), "index.html não carrega dica/dica.js");
        String app = Files.readString(APP_JS, StandardCharsets.UTF_8);
        assertTrue(app.contains("from './dicas.js") && app.contains("inicializarDicas()"),
            "app.js não importa o conversor de dicas (js/dicas.js) ou não o liga");
        assertTrue(app.contains("from './gaveta.js") && app.contains("inicializarGaveta()"),
            "app.js não liga a gaveta do menu (js/gaveta.js)");
    }

    private static List<Path> arquivos(String extensao) throws IOException {
        try (Stream<Path> s = Files.walk(ESTATICOS)) {
            return s.filter(Files::isRegularFile)
                .filter(p -> p.getFileName().toString().endsWith(extensao))
                .sorted()
                .toList();
        }
    }
}
