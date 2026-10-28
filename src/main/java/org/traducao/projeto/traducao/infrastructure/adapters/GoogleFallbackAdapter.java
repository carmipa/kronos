package org.traducao.projeto.traducao.infrastructure.adapters;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.traducao.projeto.traducao.domain.fallback.ProvedorFallback;
import org.traducao.projeto.traducao.domain.fallback.ResultadoFallback;
import org.traducao.projeto.traducao.domain.fallback.StatusFallback;
import org.traducao.projeto.traducao.domain.ports.FallbackTraducaoMaquinaPort;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * PROPÓSITO DE NEGÓCIO: implementação PRÓPRIA da fatia Tradução Local para a recuperação
 * online de último recurso via Google Translate. É deliberadamente independente do módulo
 * manual {@code raspagemCorrecao} (cuja fronteira arquitetural é proibida à Tradução Local):
 * mascara as tags/quebras de forma amigável ao tradutor externo, chama a API pública e
 * restaura a formatação, recusando a resposta se qualquer marcador for corrompido.
 *
 * <h2>Invariantes do domínio</h2>
 * <ul>
 *   <li>Blocos de tag {@code {...}} viram {@code [Tn]} e quebras {@code \N} viram {@code [B]}
 *       antes do envio; ambos são restaurados na volta.</li>
 *   <li>Se o Google perder/mutilar qualquer marcador, ou devolver texto igual/vazio, o
 *       resultado é um resultado de recusa com a causa — nunca uma legenda com formatação quebrada.</li>
 *   <li>Só depende do JDK ({@code java.net.http}) e do Jackson; não conhece cache, LLM,
 *       legenda nem outras fatias.</li>
 * </ul>
 *
 * <h2>Comportamento em caso de falha</h2>
 * Erro de rede, HTTP != 200, corpo inesperado, marcador residual/mutilado ou tradução
 * idêntica devolvem um resultado de recusa com a causa. O transporte HTTP fica isolado em
 * {@link #executarGet(String)} ({@code protected}) para os testes substituírem sem rede.
 */
@Component
public class GoogleFallbackAdapter implements FallbackTraducaoMaquinaPort {

    private static final Logger log = LoggerFactory.getLogger(GoogleFallbackAdapter.class);

    private static final Pattern PADRAO_TAG = Pattern.compile("\\{[^}]+\\}");
    // Marcador residual APENAS nas formas que este adapter emite ([T<n>], [B], [Bn], [Bh]),
    // tolerando mutilação de colchete/espaço — sem confundir '(b)'/'(t)' de conteúdo (alternativas
    // de múltipla escolha) com marcador. A perda/duplicação real é pega pela contagem exata.
    private static final Pattern PADRAO_MARCADOR_RESIDUAL = Pattern.compile(
        "(?i)[\\[(]\\s*t\\s*\\d+\\s*[\\])]"       // [T<n>]: T exige dígito
        + "|[\\[(]\\s*b\\s*[nh]\\s*[\\])]"         // [Bn]/[Bh]: B com sufixo n/h
        + "|\\[\\s*b\\s*\\]");                      // [B] puro: exige colchete reto (não pega "(b)")
    /** Quebras estruturais ASS verificadas por contagem exata na volta do Google. */
    private static final List<String> QUEBRAS = List.of("\\N", "\\n", "\\h");

    /**
     * PROPÓSITO DE NEGÓCIO: guarda a tag ASS JUNTO COM a adjacência que ela tinha no original —
     * havia espaço antes? havia depois? — para que a restauração devolva a formatação em vez de
     * inventá-la.
     *
     * <h2>O defeito que este record existe para impedir</h2>
     * O round-trip era perdedor nas duas pontas. Ao mascarar, o marcador saía sempre com espaço
     * dos dois lados e {@code replaceAll("\\s+", " ")} colapsava o resto: a adjacência original
     * morria ali. Ao restaurar, o padrão {@code \s*\[Tn\]\s*} comia TODO espaço em volta. Uma
     * ponta inventava espaço, a outra apagava todos, e nenhuma sabia o que existia de fato.
     *
     * <p>Medido em 2026-07-28 no acervo: das falas com tag NO MEIO da frase, ~70% saíram com as
     * palavras coladas (110 casos possíveis / 72 defeituosos em Guilty Crown; 20 / 15 em Zeta).
     * <pre>
     *   EN "She let {\i1}you{\i0} use the Void Genome."
     *   PT "Ela deixou{\i1}você{\i0}usar o Void Genome."   renderiza "deixouvocêusar"
     * </pre>
     *
     * <p><b>Por que não bastava "devolver o espaço"</b>: às vezes a tag é MESMO colada, e aí o
     * comportamento antigo acertava. {@code "President Ou{ba}ma"} tem a tag no meio da palavra e
     * precisa continuar sem espaço nenhum. Só a adjacência do original separa os dois casos.
     *
     * <p>INVARIANTES DO DOMÍNIO: a adjacência é do TEXTO ORIGINAL, capturada no instante do
     * mascaramento; o espaço acompanha a tag e não a posição, porque a ordem das palavras muda
     * entre inglês e português. Tag no início ou no fim da fala não tem vizinho e não ganha
     * espaço.
     *
     * <p>COMPORTAMENTO EM CASO DE FALHA: record imutável, sem I/O; índices fora da fala são
     * tratados como "sem vizinho".
     */
    private record TagAncorada(String codigo, boolean espacoAntes, boolean espacoDepois) {

        static TagAncorada de(String original, int inicio, int fim) {
            boolean antes = inicio > 0 && Character.isWhitespace(original.charAt(inicio - 1));
            boolean depois = fim < original.length() && Character.isWhitespace(original.charAt(fim));
            return new TagAncorada(original.substring(inicio, fim), antes, depois);
        }

        /** A tag com exatamente o espaçamento que ela tinha no original — nem mais, nem menos. */
        String comAdjacencia() {
            return (espacoAntes ? " " : "") + codigo + (espacoDepois ? " " : "");
        }
    }

    private final ObjectMapper mapper;
    private final HttpClient httpClient;

    public GoogleFallbackAdapter(ObjectMapper mapper) {
        this.mapper = mapper;
        this.httpClient = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL)
            .connectTimeout(Duration.ofSeconds(10))
            .build();
    }

    /**
     * PROPÓSITO DE NEGÓCIO: traduz uma fala pendente preservando tags e quebras.
     *
     * <p>INVARIANTES DO DOMÍNIO: a saída, quando presente, contém exatamente as mesmas
     * tags/quebras do original; qualquer corrupção de marcador reprova.
     *
     * <p>COMPORTAMENTO EM CASO DE FALHA: um resultado de recusa com a causa em rede, resposta
     * inválida, marcador residual ou tradução igual ao original; não lança.
     */
    /**
     * PROPÓSITO DE NEGÓCIO: identifica este adaptador como o provedor Google na cadeia de
     * recuperação, para que contadores e logs sejam rotulados sem conhecer a classe concreta.
     * <p>INVARIANTES DO DOMÍNIO: valor fixo {@link ProvedorFallback#GOOGLE}.
     * <p>COMPORTAMENTO EM CASO DE FALHA: não lança.
     */
    @Override
    public ProvedorFallback provedor() {
        return ProvedorFallback.GOOGLE;
    }

    @Override
    public ResultadoFallback traduzir(String original) {
        if (original == null || original.isBlank()) {
            return ResultadoFallback.naoTentada("fala original vazia");
        }

        List<TagAncorada> tags = new ArrayList<>();
        Matcher matcher = PADRAO_TAG.matcher(original);
        StringBuilder sb = new StringBuilder();
        int ultimoFim = 0;
        while (matcher.find()) {
            sb.append(original, ultimoFim, matcher.start());
            // A ADJACÊNCIA é capturada AQUI porque é o último instante em que ela existe: o
            // marcador sai sempre com espaço dos dois lados e a normalização logo abaixo colapsa
            // o resto, então depois desta linha não há como saber se o original tinha espaço.
            tags.add(TagAncorada.de(original, matcher.start(), matcher.end()));
            sb.append(" [T").append(tags.size() - 1).append("] ");
            ultimoFim = matcher.end();
        }
        sb.append(original, ultimoFim, original.length());
        String mascarado = sb.toString();

        // Todas as quebras estruturais ASS viram marcadores distintos e restauráveis.
        mascarado = mascarado
            .replace("\\N", " [B] ")
            .replace("\\n", " [Bn] ")
            .replace("\\h", " [Bh] ");
        mascarado = mascarado.replaceAll("\\s+", " ").strip();
        if (mascarado.isEmpty()) {
            return ResultadoFallback.recusada(ProvedorFallback.GOOGLE, StatusFallback.RESPOSTA_VAZIA, "fala vazia apos mascaramento");
        }

        String corpo;
        try {
            String query = URLEncoder.encode(mascarado, StandardCharsets.UTF_8);
            String url = "https://translate.googleapis.com/translate_a/single?client=gtx&sl=en&tl=pt&dt=t&q=" + query;
            RespostaHttp resposta = executarGet(url);
            if (resposta.statusCode() != 200) {
                log.warn("Fallback Google: HTTP {} — fala mantida pendente.", resposta.statusCode());
                return ResultadoFallback.recusada(ProvedorFallback.GOOGLE, StatusFallback.HTTP_ERRO, "HTTP " + resposta.statusCode());
            }
            corpo = resposta.corpo();
        } catch (Exception e) {
            if (e instanceof InterruptedException) {
                // O catch genérico consumia o sinal de parada: o Sair do operador virava "Google
                // indisponível" e o laço seguia para a próxima fala (achado da auditoria da 2.1).
                Thread.currentThread().interrupt();
            }
            log.warn("Fallback Google: falha de comunicação ({}) — fala mantida pendente.", e.getMessage());
            // TIMEOUT tem causa própria no enum e nenhum produtor até 28/10/2026: o diagnóstico por
            // causa — a razão de existir do relatório — misturava rede lenta com servidor fora.
            StatusFallback causa = e instanceof java.net.http.HttpTimeoutException
                ? StatusFallback.TIMEOUT : StatusFallback.PROVEDOR_INDISPONIVEL;
            return ResultadoFallback.recusada(ProvedorFallback.GOOGLE, causa, "falha de comunicacao: " + e.getMessage());
        }

        String traduzido;
        try {
            JsonNode root = mapper.readTree(corpo);
            JsonNode segmentos = root.get(0);
            StringBuilder acc = new StringBuilder();
            if (segmentos != null && segmentos.isArray()) {
                for (JsonNode segmento : segmentos) {
                    JsonNode texto = segmento.get(0);
                    if (texto != null && !texto.isNull()) {
                        acc.append(texto.asText());
                    }
                }
            }
            traduzido = acc.toString();
        } catch (Exception e) {
            log.warn("Fallback Google: resposta em formato inesperado ({}) — fala mantida pendente.", e.getMessage());
            return ResultadoFallback.recusada(ProvedorFallback.GOOGLE, StatusFallback.RESPOSTA_VAZIA, "resposta em formato inesperado: " + e.getMessage());
        }

        if (traduzido.isBlank()) {
            return ResultadoFallback.recusada(ProvedorFallback.GOOGLE, StatusFallback.RESPOSTA_VAZIA, "provedor devolveu texto vazio");
        }

        // Restaura as quebras (marcadores mais específicos primeiro) e depois as tags.
        traduzido = restaurarQuebra(traduzido, "bh", "\\h");
        traduzido = restaurarQuebra(traduzido, "bn", "\\n");
        traduzido = restaurarQuebra(traduzido, "b", "\\N");
        for (int i = 0; i < tags.size(); i++) {
            // O \s* continua comendo todo espaço em volta do marcador — o que o Google devolveu
            // ali não é confiável. A formatação vem de comAdjacencia(), que sabe o que o ORIGINAL
            // tinha. Sem isso, "let {\i1}you{\i0} use" voltava como "deixou{\i1}você{\i0}usar".
            traduzido = traduzido.replaceAll("(?i)\\s*\\[t" + i + "\\]\\s*",
                Matcher.quoteReplacement(tags.get(i).comAdjacencia()));
        }
        traduzido = traduzido.replace("\\ N", "\\N").replace("\\ n", "\\n").replace("\\ h", "\\h");

        if (PADRAO_MARCADOR_RESIDUAL.matcher(traduzido).find()) {
            log.warn("Fallback Google: marcadores de tag/quebra mutilados — fala mantida pendente: {}", traduzido);
            return ResultadoFallback.recusada(ProvedorFallback.GOOGLE, StatusFallback.MARCADOR_CORROMPIDO, "marcadores de tag/quebra mutilados");
        }
        // Contagem EXATA de cada tag e quebra: pega tanto perda quanto DUPLICAÇÃO de marcador
        // pelo Google (um simples contains aceitaria uma tag restaurada em dobro).
        for (TagAncorada tag : tags) {
            String codigo = tag.codigo();
            if (contarOcorrencias(traduzido, codigo) != contarOcorrencias(original, codigo)) {
                log.warn("Fallback Google: contagem da tag ASS {} divergente — fala mantida pendente.", codigo);
                return ResultadoFallback.recusada(ProvedorFallback.GOOGLE, StatusFallback.MARCADOR_CORROMPIDO, "contagem da tag ASS " + codigo + " divergente");
            }
        }
        for (String quebra : QUEBRAS) {
            if (contarOcorrencias(traduzido, quebra) != contarOcorrencias(original, quebra)) {
                log.warn("Fallback Google: contagem da quebra {} divergente — fala mantida pendente.", quebra);
                return ResultadoFallback.recusada(ProvedorFallback.GOOGLE, StatusFallback.MARCADOR_CORROMPIDO, "contagem da quebra " + quebra + " divergente");
            }
        }
        if (traduzido.equals(original)) {
            return ResultadoFallback.recusada(ProvedorFallback.GOOGLE, StatusFallback.ECO, "provedor devolveu o texto original sem traduzir");
        }
        return ResultadoFallback.recuperada(traduzido, ProvedorFallback.GOOGLE);
    }

    /**
     * PROPÓSITO DE NEGÓCIO: devolve uma quebra estrutural ASS ({@code \N}, {@code \n},
     * {@code \h}) ao lugar do seu marcador ({@code [B]}, {@code [Bn]}, {@code [Bh]}).
     *
     * <p>INVARIANTES DO DOMÍNIO: casa o marcador ignorando caixa e espaços ao redor; o
     * código de quebra é inserido literalmente.
     *
     * <p>COMPORTAMENTO EM CASO DE FALHA: marcador ausente é no-op — a divergência de
     * contagem a jusante mantém a fala pendente.
     */
    private static String restaurarQuebra(String texto, String marcador, String codigo) {
        return texto.replaceAll("(?i)\\s*\\[" + marcador + "\\]\\s*", Matcher.quoteReplacement(codigo));
    }

    /**
     * PROPÓSITO DE NEGÓCIO: conta ocorrências literais de {@code alvo} em {@code texto} para
     * a verificação de integridade de tags/quebras.
     *
     * <p>INVARIANTES DO DOMÍNIO: contagem por sobreposição zero (avança pelo comprimento do
     * alvo); alvo vazio devolve 0.
     *
     * <p>COMPORTAMENTO EM CASO DE FALHA: não lança; entradas sem ocorrência devolvem 0.
     */
    private static int contarOcorrencias(String texto, String alvo) {
        if (alvo.isEmpty()) {
            return 0;
        }
        int total = 0;
        int idx = 0;
        while ((idx = texto.indexOf(alvo, idx)) >= 0) {
            total++;
            idx += alvo.length();
        }
        return total;
    }

    /**
     * PROPÓSITO DE NEGÓCIO: transporte HTTP cru (status + corpo), isolado para os testes
     * substituírem sem rede — separando a política de mascaramento/restauração da chamada
     * de rede propriamente dita.
     *
     * <p>INVARIANTES DO DOMÍNIO: apenas executa o GET; não interpreta o corpo.
     *
     * <p>COMPORTAMENTO EM CASO DE FALHA: propaga a exceção de I/O para o chamador tratar
     * como falha transitória (fala mantida pendente).
     */
    protected RespostaHttp executarGet(String url) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64)")
            .timeout(Duration.ofSeconds(15))
            .GET()
            .build();
        HttpResponse<String> resposta = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        return new RespostaHttp(resposta.statusCode(), resposta.body());
    }

    /** Resposta HTTP mínima (status + corpo) usada como seam de transporte nos testes. */
    protected record RespostaHttp(int statusCode, String corpo) {}
}
