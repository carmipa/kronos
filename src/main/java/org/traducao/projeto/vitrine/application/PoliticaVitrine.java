package org.traducao.projeto.vitrine.application;

import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * PROPÓSITO DE NEGÓCIO: decide, para cada requisição que chega à VITRINE pública do KRONOS,
 * se ela pode seguir, se recebe um dado de demonstração ou se é recusada. A vitrine mostra as
 * telas na VPS sem que nenhuma função rode fora da máquina do Paulo (decisão de 14/10/2026).
 * É a FONTE ÚNICA do que é público — o filtro HTTP e os testes leem estas listas, nunca uma
 * cópia.
 *
 * <p>INVARIANTES DO DOMÍNIO:
 * <ul>
 *   <li><b>Lista de permitidos, nunca de bloqueados</b>: o que não está aqui é recusado. Rota
 *       nova nasce bloqueada, sem ninguém precisar lembrar dela.</li>
 *   <li>Só leitura passa: qualquer método diferente de GET (e HEAD para estático) é recusado,
 *       inclusive OPTIONS.</li>
 *   <li>Caminho ambíguo é recusado antes de qualquer comparação: bruto diferente do normalizado,
 *       caractere fora de {@code [A-Za-z0-9._~/-]}, barra dupla, segmento que começa por ponto.
 *       Comparação por igualdade exata — nada de prefixo para rota de API, exceto as duas
 *       famílias com nome validado (página de documentação e fatia de telemetria).</li>
 *   <li>Rota real só para leitura de memória ({@link #GET_REAIS}); o que lê disco, chama rede,
 *       LLM ou Redis vira dado de demonstração ({@link #GET_DEMONSTRACAO}).</li>
 *   <li>MCP e {@code /q/*} são sempre recusados.</li>
 * </ul>
 *
 * <p>COMPORTAMENTO EM CASO DE FALHA: entrada nula ou malformada devolve
 * {@link Acao#BLOQUEAR} — a dúvida nunca abre passagem.
 */
public final class PoliticaVitrine {

    /** O que a vitrine faz com a requisição. */
    public enum Acao {
        /** Segue para a rota real (estático ou leitura de memória). */
        PERMITIR,
        /** Responde com o dado de demonstração nomeado em {@link Decisao#demonstracao()}. */
        DEMONSTRAR,
        /** Abre o substituto do fluxo de logs: só batimento, nada do console real. */
        SSE_LOGS,
        /** Abre o substituto do fluxo de telemetria: o retrato de demonstração e batimento. */
        SSE_TELEMETRIA,
        /** Recusa com 403. */
        BLOQUEAR
    }

    /**
     * PROPÓSITO DE NEGÓCIO: resultado da decisão.
     *
     * <p>INVARIANTES DO DOMÍNIO: {@code demonstracao} só é preenchido com {@link Acao#DEMONSTRAR}
     * e só contém nome validado ({@code [a-z0-9-]}), nunca texto vindo do cliente sem filtro.
     *
     * <p>COMPORTAMENTO EM CASO DE FALHA: não se aplica — valor imutável.
     */
    public record Decisao(Acao acao, String demonstracao) {
        static final Decisao PERMITIR = new Decisao(Acao.PERMITIR, null);
        static final Decisao BLOQUEAR = new Decisao(Acao.BLOQUEAR, null);
        static final Decisao SSE_LOGS = new Decisao(Acao.SSE_LOGS, null);
        static final Decisao SSE_TELEMETRIA = new Decisao(Acao.SSE_TELEMETRIA, null);

        static Decisao demonstrar(String nome) {
            return new Decisao(Acao.DEMONSTRAR, nome);
        }
    }

    /**
     * Extensões de arquivo estático que a tela usa. Medido em 14/10: js, css, webp, png, html, svg; e
     * woff2 desde que as fontes e os ícones passaram a ser servidos pelo próprio KRONOS (antes vinham
     * do Google Fonts). Só woff2: woff, ttf e otf não existem no projeto e continuam recusados.
     */
    public static final Set<String> EXTENSOES_ESTATICAS = Set.of("html", "js", "css", "webp", "png", "svg", "ico", "woff2");

    /** GETs que seguem para o controller real: todos só leem memória. */
    public static final Set<String> GET_REAIS = Set.of(
        "/api/status",
        "/api/pipeline/status",
        "/api/contextos",
        "/api/revisao-lore/contextos");

    /** GETs respondidos com dado de demonstração, porque o real lê disco, rede, LLM ou Redis. */
    public static final Map<String, String> GET_DEMONSTRACAO = Map.of(
        "/api/telemetria", "telemetria",
        "/api/telemetria/fatias", "telemetria-fatias",
        "/api/fluxo/status", "fluxo-status",
        "/api/llm/status", "llm-status",
        "/api/dialogo/capacidade", "dialogo-capacidade");

    /** Fluxo de logs do servidor: substituído, porque o real transmite o console inteiro. */
    public static final String SSE_LOGS = "/api/logs/stream";

    /** Fluxo de telemetria: substituído, porque o real percorre o cache a cada 5 s. */
    public static final String SSE_TELEMETRIA = "/api/telemetria/stream";

    /** Página da documentação: segue para o controller real, que só lê {@code docs/*.md}. */
    public static final String PREFIXO_DOCS = "/api/docs/";

    /** Detalhe de uma fatia de telemetria: dado de demonstração {@code fatia-<nome>}. */
    public static final String PREFIXO_FATIA = "/api/telemetria/fatias/";

    private static final Pattern CAMINHO_SEGURO = Pattern.compile("^/[A-Za-z0-9._~/-]*$");
    private static final Pattern NOME_DOC = Pattern.compile("^[a-z0-9][a-z0-9.-]{0,79}$");
    private static final Pattern NOME_FATIA = Pattern.compile("^[a-z0-9][a-z0-9-]{0,63}$");

    private PoliticaVitrine() {
    }

    /**
     * PROPÓSITO DE NEGÓCIO: decide o destino de uma requisição na vitrine.
     *
     * <p>INVARIANTES DO DOMÍNIO: ver o cabeçalho da classe — lista de permitidos, só leitura,
     * caminho ambíguo recusado, comparação exata.
     *
     * <p>COMPORTAMENTO EM CASO DE FALHA: qualquer argumento nulo, método desconhecido ou caminho
     * malformado devolve {@link Acao#BLOQUEAR}.
     *
     * @param metodo             método HTTP como chegou (GET, POST...)
     * @param caminhoBruto       caminho da requisição, sem a query, como chegou
     * @param caminhoNormalizado o mesmo caminho depois da normalização do roteador
     */
    public static Decisao decidir(String metodo, String caminhoBruto, String caminhoNormalizado) {
        if (metodo == null || caminhoBruto == null || caminhoNormalizado == null) {
            return Decisao.BLOQUEAR;
        }
        boolean get = "GET".equals(metodo);
        if (!get && !"HEAD".equals(metodo)) {
            return Decisao.BLOQUEAR;
        }
        if (!caminhoAmbiguoAusente(caminhoBruto, caminhoNormalizado)) {
            return Decisao.BLOQUEAR;
        }

        String c = caminhoBruto;
        if (c.equals("/api") || c.startsWith("/api/")) {
            return get ? decidirApi(c) : Decisao.BLOQUEAR;
        }
        if (c.equals("/mcp") || c.startsWith("/mcp/") || c.equals("/q") || c.startsWith("/q/")) {
            return Decisao.BLOQUEAR;
        }
        if (c.equals("/")) {
            return Decisao.PERMITIR;
        }
        return extensaoEstaticaPermitida(c) ? Decisao.PERMITIR : Decisao.BLOQUEAR;
    }

    private static Decisao decidirApi(String c) {
        if (GET_REAIS.contains(c)) {
            return Decisao.PERMITIR;
        }
        String demonstracao = GET_DEMONSTRACAO.get(c);
        if (demonstracao != null) {
            return Decisao.demonstrar(demonstracao);
        }
        if (c.equals(SSE_LOGS)) {
            return Decisao.SSE_LOGS;
        }
        if (c.equals(SSE_TELEMETRIA)) {
            return Decisao.SSE_TELEMETRIA;
        }
        if (c.startsWith(PREFIXO_DOCS) && NOME_DOC.matcher(c.substring(PREFIXO_DOCS.length())).matches()
                && !c.contains("..")) {
            return Decisao.PERMITIR;
        }
        if (c.startsWith(PREFIXO_FATIA)) {
            String fatia = c.substring(PREFIXO_FATIA.length());
            if (NOME_FATIA.matcher(fatia).matches()) {
                return Decisao.demonstrar("fatia-" + fatia);
            }
        }
        return Decisao.BLOQUEAR;
    }

    private static boolean caminhoAmbiguoAusente(String bruto, String normalizado) {
        return bruto.equals(normalizado)
            && CAMINHO_SEGURO.matcher(bruto).matches()
            && !bruto.contains("//")
            && !bruto.contains("/.");
    }

    private static boolean extensaoEstaticaPermitida(String c) {
        String ultimo = c.substring(c.lastIndexOf('/') + 1);
        int ponto = ultimo.lastIndexOf('.');
        if (ponto <= 0 || ponto == ultimo.length() - 1) {
            return false;
        }
        return EXTENSOES_ESTATICAS.contains(ultimo.substring(ponto + 1).toLowerCase(Locale.ROOT));
    }
}
