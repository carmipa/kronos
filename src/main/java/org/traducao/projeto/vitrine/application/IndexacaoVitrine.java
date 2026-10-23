package org.traducao.projeto.vitrine.application;

import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * PROPÓSITO DE NEGÓCIO: diz aos buscadores o que a vitrine pública do KRONOS é — uma página só,
 * no endereço canônico — e o que não deve ser rastreado: a API e os pedaços de tela que o
 * JavaScript carrega dentro da página. É a FONTE ÚNICA do que é página pública: o
 * {@code robots.txt}, o {@code sitemap.xml} e os testes saem daqui, e os testes conferem que o
 * {@code <link rel="canonical">} e o {@code og:url} do {@code index.html} apontam para o mesmo
 * endereço.
 *
 * <p>INVARIANTES DO DOMÍNIO:
 * <ul>
 *   <li>O endereço canônico é fixo ({@link #URL_PUBLICA}), nunca deduzido do cabeçalho
 *       {@code Host} da requisição — cabeçalho é escolha de quem chama. Ele é constante e não
 *       configuração porque o {@code index.html} é estático e carrega o mesmo endereço no
 *       {@code <head>}: duas fontes (propriedade + arquivo) divergiriam em silêncio.</li>
 *   <li>{@code robots.txt} não soma grupos: cada robô obedece só ao grupo mais específico que casa
 *       com ele, então a lista de bloqueios se repete inteira em cada grupo que libera o site.</li>
 *   <li>Fragmento é bloqueado pelo arquivo exato, nunca pela pasta: a pasta tem o CSS e o JS que o
 *       buscador precisa para desenhar a página.</li>
 *   <li>As páginas de erro ficam FORA do {@code Disallow}: elas levam {@code noindex}, e caminho
 *       bloqueado nunca é buscado, logo o {@code noindex} nunca seria lido.</li>
 *   <li>Nada privado é listado: o arquivo é público.</li>
 * </ul>
 *
 * <p>COMPORTAMENTO EM CASO DE FALHA: não há caminho de falha — os textos são montados de
 * constantes, sem entrada externa.
 */
public final class IndexacaoVitrine {

    /** Endereço canônico da vitrine, sem barra final. */
    public static final String URL_PUBLICA = "https://kronos.carminati.dev.br";

    public static final String CAMINHO_ROBOTS = "/robots.txt";
    public static final String CAMINHO_SITEMAP = "/sitemap.xml";

    /** O documento da página inicial também responde por este nome; a vitrine o manda para {@code /}. */
    public static final String CAMINHO_INDEX = "/index.html";

    /** Páginas públicas indexáveis. As telas do KRONOS são seções desta página (trocadas por {@code #}). */
    public static final List<String> PAGINAS_PUBLICAS = List.of("/");

    /** Pedaços de tela que o JavaScript busca e encaixa na página; sozinhos não são documento. */
    public static final List<String> FRAGMENTOS = List.of(
        "/auditorConteudoLegendas/auditorConteudoLegendas.html",
        "/renomearArquivos/renomearArquivos.html",
        "/revisaoConcordancia/revisaoConcordancia.html",
        "/revisaoLore/revisaoLore.html",
        "/sobre/sobre.html",
        "/traducaoKaraoke/traducaoKaraoke.html",
        "/trocaTipoLegenda/trocaTipoLegenda.html");

    /** O que todo grupo que libera o site fecha: a API (não é página) e os fragmentos. */
    public static final List<String> BLOQUEADOS =
        Stream.concat(Stream.of("/api"), FRAGMENTOS.stream()).toList();

    private static final List<String> BUSCADORES = List.of("Googlebot", "Bingbot", "DuckDuckBot");

    private static final List<String> COLETORES = List.of(
        "GPTBot", "ChatGPT-User", "CCBot", "ClaudeBot", "anthropic-ai", "Google-Extended",
        "Applebot-Extended", "PerplexityBot", "Bytespider", "Amazonbot", "Omgilibot", "FacebookBot",
        "Diffbot", "ImagesiftBot", "AhrefsBot", "SemrushBot", "MJ12bot", "DotBot", "PetalBot",
        "DataForSeoBot");

    private static final String ROBOTS = montarRobots();
    private static final String SITEMAP = montarSitemap();

    private IndexacaoVitrine() {
    }

    /**
     * PROPÓSITO DE NEGÓCIO: o {@code robots.txt} da vitrine.
     *
     * <p>INVARIANTES DO DOMÍNIO: ver o cabeçalho da classe; o mesmo texto em toda chamada.
     *
     * <p>COMPORTAMENTO EM CASO DE FALHA: não falha.
     */
    public static String robotsTxt() {
        return ROBOTS;
    }

    /**
     * PROPÓSITO DE NEGÓCIO: o {@code sitemap.xml} da vitrine, com as páginas de {@link #PAGINAS_PUBLICAS}.
     *
     * <p>INVARIANTES DO DOMÍNIO: só {@code <loc>} absoluto no endereço canônico; sem {@code lastmod}
     * (data inventada a cada pedido seria dado fabricado) e sem {@code priority} (ignorado).
     *
     * <p>COMPORTAMENTO EM CASO DE FALHA: não falha.
     */
    public static String sitemapXml() {
        return SITEMAP;
    }

    /**
     * PROPÓSITO DE NEGÓCIO: endereço absoluto de uma página pública.
     *
     * <p>INVARIANTES DO DOMÍNIO: host sempre o de {@link #URL_PUBLICA}.
     *
     * <p>COMPORTAMENTO EM CASO DE FALHA: caminho que não começa por {@code /} lança
     * {@link IllegalArgumentException}.
     */
    public static String enderecoAbsoluto(String caminho) {
        if (caminho == null || !caminho.startsWith("/")) {
            throw new IllegalArgumentException("caminho de página deve começar por /: " + caminho);
        }
        return URL_PUBLICA + caminho;
    }

    private static String montarRobots() {
        String bloqueios = BLOQUEADOS.stream().map(c -> "Disallow: " + c).collect(Collectors.joining("\n"));
        StringBuilder sb = new StringBuilder();
        sb.append("# Vitrine pública do KRONOS: uma página só. As telas são seções dela.\n")
            .append("#\n")
            .append("# robots.txt NÃO soma grupos: cada robô obedece só ao grupo mais específico que casa com\n")
            .append("# o nome dele. Por isso a lista de caminhos fechados se repete INTEIRA em cada grupo que\n")
            .append("# libera o site.\n")
            .append("#\n")
            .append("# Fechados: a API (não é página) e os pedaços de tela que o JavaScript encaixa na página,\n")
            .append("# pelo arquivo exato — a pasta deles tem o CSS e o JS que o buscador usa para desenhar a\n")
            .append("# página. As páginas de erro ficam abertas de propósito: levam noindex, e caminho fechado\n")
            .append("# aqui nunca é buscado, então o noindex não seria lido.\n");
        for (String robo : BUSCADORES) {
            sb.append("\nUser-agent: ").append(robo).append("\nAllow: /\n").append(bloqueios).append('\n');
        }
        sb.append("\n# Coletores de conteúdo em massa e treinamento de modelos: não autorizados.\n");
        for (String robo : COLETORES) {
            sb.append("User-agent: ").append(robo).append('\n');
        }
        sb.append("Disallow: /\n");
        sb.append("\n# Demais robôs: a página liberada, o resto fechado como acima.\n")
            .append("User-agent: *\nAllow: /\n").append(bloqueios).append("\nCrawl-delay: 10\n");
        sb.append("\nSitemap: ").append(enderecoAbsoluto(CAMINHO_SITEMAP)).append('\n');
        return sb.toString();
    }

    private static String montarSitemap() {
        StringBuilder sb = new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
            .append("<urlset xmlns=\"http://www.sitemaps.org/schemas/sitemap/0.9\">\n");
        for (String pagina : PAGINAS_PUBLICAS) {
            sb.append("  <url><loc>").append(enderecoAbsoluto(pagina)).append("</loc></url>\n");
        }
        return sb.append("</urlset>\n").toString();
    }
}
