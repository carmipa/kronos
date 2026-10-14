package org.traducao.projeto.vitrine.presentation.bootstrap;

import io.quarkus.runtime.StartupEvent;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.traducao.projeto.core.vitrine.ModoVitrine;

import java.util.ArrayList;
import java.util.List;

/**
 * PROPÓSITO DE NEGÓCIO: impede a VITRINE pública de subir com uma configuração que faria o
 * KRONOS executar algo sozinho — um modo de linha de comando que roda extração ou tradução no
 * arranque, o navegador aberto pelo servidor, ou o MCP aceitando ferramentas.
 *
 * <p>INVARIANTES DO DOMÍNIO:
 * <ul>
 *   <li>Roda ANTES de qualquer outro observador de arranque ({@code @Priority(1)}), em
 *       particular antes do despachante de modo CLI e do lançador de navegador.</li>
 *   <li>Fora da vitrine não confere nada: o KRONOS local continua igual.</li>
 *   <li>Coerência obrigatória na vitrine: {@code app.modo=WEB},
 *       {@code app.browser.auto-open=false}, {@code quarkus.mcp.server.http.enabled=false}
 *       (fixada no build) e o filtro HTTP ligado.</li>
 * </ul>
 *
 * <p>COMPORTAMENTO EM CASO DE FALHA: configuração incoerente lança
 * {@link IllegalStateException} listando todos os problemas — o Quarkus aborta a partida, e a
 * vitrine não fica no ar pela metade (falha FECHADA).
 */
@ApplicationScoped
public class ConferenciaArranqueVitrine {

    private static final Logger log = LoggerFactory.getLogger(ConferenciaArranqueVitrine.class);

    @ConfigProperty(name = ModoVitrine.CHAVE_CONFIGURACAO, defaultValue = "false")
    boolean filtroLigado;

    @ConfigProperty(name = "app.modo", defaultValue = "WEB")
    String modo;

    @ConfigProperty(name = "app.browser.auto-open", defaultValue = "true")
    boolean abrirNavegador;

    @ConfigProperty(name = "quarkus.mcp.server.http.enabled", defaultValue = "true")
    boolean mcpHttpLigado;

    /**
     * PROPÓSITO DE NEGÓCIO: confere a configuração da vitrine no início do arranque.
     *
     * <p>INVARIANTES DO DOMÍNIO: grava o estado da vitrine no {@link ModoVitrine} antes de
     * conferir, para que nem uma partida abortada deixe a fila aceitando trabalho; a variável
     * de ambiente {@value ModoVitrine#VARIAVEL_AMBIENTE} sozinha também ativa a conferência.
     *
     * <p>COMPORTAMENTO EM CASO DE FALHA: lança {@link IllegalStateException} com a lista de
     * problemas.
     */
    void aoIniciar(@Observes @Priority(1) StartupEvent evento) {
        ModoVitrine.registrarArranque(filtroLigado);
        if (!ModoVitrine.ativa()) {
            return;
        }
        List<String> problemas = conferir(filtroLigado, modo, abrirNavegador, mcpHttpLigado);
        if (!problemas.isEmpty()) {
            throw new IllegalStateException("Vitrine recusou subir com configuração incoerente: " + problemas);
        }
        log.info("[VITRINE] MODO VITRINE ATIVO: telas abertas, funções recusadas, MCP desligado.");
    }

    /**
     * PROPÓSITO DE NEGÓCIO: lista o que impede a vitrine de subir com segurança.
     *
     * <p>INVARIANTES DO DOMÍNIO: função pura; lista vazia significa configuração coerente.
     *
     * <p>COMPORTAMENTO EM CASO DE FALHA: {@code modo} nulo conta como problema.
     */
    static List<String> conferir(boolean filtroLigado, String modo, boolean abrirNavegador, boolean mcpHttpLigado) {
        List<String> problemas = new ArrayList<>();
        if (!filtroLigado) {
            problemas.add(ModoVitrine.VARIAVEL_AMBIENTE + " ligada mas " + ModoVitrine.CHAVE_CONFIGURACAO
                + "=false: o filtro HTTP não seria registrado");
        }
        if (modo == null || !"WEB".equalsIgnoreCase(modo.trim())) {
            problemas.add("app.modo=" + modo + " (a vitrine só aceita WEB)");
        }
        if (abrirNavegador) {
            problemas.add("app.browser.auto-open=true (o servidor abriria um navegador)");
        }
        if (mcpHttpLigado) {
            problemas.add("quarkus.mcp.server.http.enabled=true (construa a imagem com o MCP desligado)");
        }
        return problemas;
    }
}
