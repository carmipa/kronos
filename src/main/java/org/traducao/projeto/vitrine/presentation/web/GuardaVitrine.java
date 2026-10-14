package org.traducao.projeto.vitrine.presentation.web;

import io.quarkus.vertx.http.runtime.filters.Filters;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.http.HttpServerResponse;
import io.vertx.ext.web.RoutingContext;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.traducao.projeto.core.vitrine.ModoVitrine;
import org.traducao.projeto.vitrine.application.PoliticaVitrine;
import org.traducao.projeto.vitrine.application.PoliticaVitrine.Decisao;
import org.traducao.projeto.vitrine.infrastructure.DadosDemonstracao;

import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * PROPÓSITO DE NEGÓCIO: a porta da VITRINE pública do KRONOS. Na VPS as telas abrem e se
 * mostram, mas nenhuma função roda fora da máquina do Paulo (decisão de 14/10/2026). Este
 * filtro fica na frente de TODAS as rotas do roteador HTTP — arquivos estáticos, JAX-RS, rotas
 * no estilo Spring e o MCP — e aplica a {@link PoliticaVitrine}.
 *
 * <p>INVARIANTES DO DOMÍNIO:
 * <ul>
 *   <li>Desligado ({@code kronos.vitrine.ativa=false}, o padrão), o filtro NEM é registrado: o
 *       KRONOS local se comporta exatamente como antes.</li>
 *   <li>Ligado, é registrado no {@link Filters} do Quarkus, que roda antes do handler de
 *       estáticos e de qualquer rota — um filtro JAX-RS não alcançaria nem o estático nem o MCP
 *       (cicatriz do binmapper, 02/09/2026).</li>
 *   <li>Toda resposta leva o cabeçalho {@value #CABECALHO_VITRINE}, que a tela usa para mostrar
 *       a faixa da vitrine sem nenhuma rota extra.</li>
 *   <li>Os dois fluxos SSE são substituídos por versões que nunca leem o servidor real: logs só
 *       com batimento, telemetria com o retrato de demonstração. Ficam abertos com batimento de
 *       {@value #BATIMENTO_MS} ms (a tela reconecta a cada 5 s se o fluxo cair, e o watchdog
 *       dela espera 40 s), com teto de {@value #TETO_SSE} conexões e vida máxima de
 *       {@value #VIDA_MAXIMA_SSE_MS} ms.</li>
 * </ul>
 *
 * <p>COMPORTAMENTO EM CASO DE FALHA: recusa com 403 e JSON {@code {erro, mensagem, vitrine}};
 * dado de demonstração ausente responde 404; teto de SSE atingido responde 503. Nenhum desses
 * caminhos chama a rota real.
 */
@ApplicationScoped
public class GuardaVitrine {

    private static final Logger log = LoggerFactory.getLogger(GuardaVitrine.class);

    /** Cabeçalho que diz à tela que esta é a vitrine. */
    public static final String CABECALHO_VITRINE = "X-Kronos-Vitrine";

    /** Prioridade no {@link Filters}: quanto maior, mais cedo roda. */
    static final int PRIORIDADE = 50_000;

    static final long BATIMENTO_MS = 15_000L;
    static final long VIDA_MAXIMA_SSE_MS = 30L * 60L * 1000L;
    static final int TETO_SSE = 200;
    private static final int RESUMO_A_CADA = 500;

    static final String MENSAGEM_RECUSA =
        "Esta é a vitrine pública do KRONOS: as telas estão abertas para conhecer o sistema, "
            + "mas as funções só rodam na instalação local.";

    private static final String CORPO_RECUSA =
        "{\"erro\":\"" + MENSAGEM_RECUSA + "\",\"mensagem\":\"" + MENSAGEM_RECUSA + "\",\"vitrine\":true}";

    private static final String PAGINA_RECUSA = "<!DOCTYPE html><html lang=\"pt-BR\"><head><meta charset=\"UTF-8\">"
        + "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0\">"
        + "<title>KRONOS — vitrine</title><link rel=\"icon\" type=\"image/png\" href=\"/img/kronos_logo.png\"><style>body{margin:0;padding:8% 6%;background:#05070e;color:#eaf0f8;"
        + "font-family:system-ui,-apple-system,'Segoe UI',sans-serif;line-height:1.5}"
        + "a{color:#38bdf8}</style></head><body><h1>Vitrine do KRONOS</h1><p>" + MENSAGEM_RECUSA
        + "</p><p><a href=\"/\">Voltar às telas</a></p></body></html>";

    private static final String IDENTIDADE = "vitrine-" + UUID.randomUUID();

    @ConfigProperty(name = ModoVitrine.CHAVE_CONFIGURACAO, defaultValue = "false")
    boolean ativa;

    private final AtomicInteger sseAbertas = new AtomicInteger();
    private final AtomicLong recusadas = new AtomicLong();
    private final AtomicLong demonstradas = new AtomicLong();

    /**
     * PROPÓSITO DE NEGÓCIO: liga a vitrine no roteador HTTP quando a configuração manda.
     *
     * <p>INVARIANTES DO DOMÍNIO: roda na montagem do roteador, antes de o servidor aceitar
     * conexão; liga também o {@link ModoVitrine} do core, para a fila e o processo externo
     * recusarem desde o primeiro instante.
     *
     * <p>COMPORTAMENTO EM CASO DE FALHA: desligada, não registra nada e registra o estado no log.
     */
    void registrar(@Observes Filters filtros) {
        if (!ativa) {
            log.debug("[VITRINE] desligada: nenhum filtro registrado.");
            return;
        }
        ModoVitrine.ligar();
        filtros.register(this::filtrar, PRIORIDADE);
        log.info("[VITRINE] filtro ligado na frente de todas as rotas (prioridade {}).", PRIORIDADE);
    }

    /**
     * PROPÓSITO DE NEGÓCIO: aplica a política a uma requisição.
     *
     * <p>INVARIANTES DO DOMÍNIO: decide pelo método e pelo caminho bruto e normalizado; só
     * {@code PERMITIR} chama a próxima etapa do roteador.
     *
     * <p>COMPORTAMENTO EM CASO DE FALHA: qualquer exceção ao decidir vira recusa 403.
     */
    void filtrar(RoutingContext rc) {
        HttpServerResponse resposta = rc.response();
        resposta.putHeader(CABECALHO_VITRINE, "1");
        resposta.putHeader("X-Content-Type-Options", "nosniff");
        resposta.putHeader("Referrer-Policy", "no-referrer");
        resposta.putHeader("X-Frame-Options", "DENY");

        Decisao decisao;
        try {
            HttpServerRequest req = rc.request();
            decisao = PoliticaVitrine.decidir(req.method().name(), req.path(), rc.normalizedPath());
        } catch (RuntimeException e) {
            log.warn("[VITRINE] decisão falhou; recusando por segurança: {}", e.toString());
            recusar(rc);
            return;
        }

        switch (decisao.acao()) {
            case PERMITIR -> rc.next();
            case DEMONSTRAR -> demonstrar(rc, decisao.demonstracao());
            case SSE_LOGS -> abrirSse(rc, false);
            case SSE_TELEMETRIA -> abrirSse(rc, true);
            default -> recusar(rc);
        }
    }

    private void recusar(RoutingContext rc) {
        long total = recusadas.incrementAndGet();
        log.debug("[VITRINE] recusada: {} {}", rc.request().method(), rc.request().path());
        if (total % RESUMO_A_CADA == 0) {
            log.info("[VITRINE] {} requisições recusadas até agora; {} respondidas com demonstração.",
                total, demonstradas.get());
        }
        // Navegação direta do navegador (botão que abre aba, URL digitada) recebe página legível;
        // chamada de script (fetch) recebe o JSON que as telas já sabem mostrar.
        String aceita = rc.request().getHeader("Accept");
        boolean navegacao = aceita != null && aceita.contains("text/html");
        rc.response()
            .setStatusCode(403)
            .putHeader("Content-Type", navegacao ? "text/html; charset=utf-8" : "application/json; charset=utf-8")
            .putHeader("Cache-Control", "no-store")
            .end(navegacao ? PAGINA_RECUSA : CORPO_RECUSA);
    }

    private void demonstrar(RoutingContext rc, String nome) {
        Optional<byte[]> dado = DadosDemonstracao.obter(nome);
        if (dado.isEmpty()) {
            rc.response()
                .setStatusCode(404)
                .putHeader("Content-Type", "application/json; charset=utf-8")
                .putHeader("Cache-Control", "no-store")
                .end("{\"erro\":\"Sem dado de demonstração para esta tela.\",\"vitrine\":true}");
            return;
        }
        demonstradas.incrementAndGet();
        rc.response()
            .setStatusCode(200)
            .putHeader("Content-Type", "application/json; charset=utf-8")
            .putHeader("Cache-Control", "no-store")
            .end(io.vertx.core.buffer.Buffer.buffer(dado.get()));
    }

    private void abrirSse(RoutingContext rc, boolean telemetria) {
        if (sseAbertas.incrementAndGet() > TETO_SSE) {
            sseAbertas.decrementAndGet();
            rc.response()
                .setStatusCode(503)
                .putHeader("Content-Type", "application/json; charset=utf-8")
                .putHeader("Cache-Control", "no-store")
                .end("{\"erro\":\"Muitas conexões abertas na vitrine.\",\"vitrine\":true}");
            return;
        }
        HttpServerResponse resposta = rc.response();
        resposta.setStatusCode(200)
            .setChunked(true)
            .putHeader("Content-Type", "text/event-stream; charset=utf-8")
            .putHeader("Cache-Control", "no-store")
            .putHeader("X-Accel-Buffering", "no");

        AtomicBoolean encerrada = new AtomicBoolean(false);
        long[] temporizadores = new long[2];
        Runnable encerrar = () -> {
            if (encerrada.compareAndSet(false, true)) {
                rc.vertx().cancelTimer(temporizadores[0]);
                rc.vertx().cancelTimer(temporizadores[1]);
                sseAbertas.decrementAndGet();
            }
        };
        resposta.closeHandler(v -> encerrar.run());
        resposta.exceptionHandler(e -> encerrar.run());

        resposta.write(eventoInicial(telemetria));
        temporizadores[0] = rc.vertx().setPeriodic(BATIMENTO_MS, id -> {
            if (!encerrada.get()) {
                resposta.write(telemetria ? ": batimento\n\n" : batimentoLogs());
            }
        });
        temporizadores[1] = rc.vertx().setTimer(VIDA_MAXIMA_SSE_MS, id -> {
            if (!encerrada.get()) {
                encerrar.run();
                resposta.end();
            }
        });
    }

    private String eventoInicial(boolean telemetria) {
        if (!telemetria) {
            return "retry: 15000\n\n"
                + batimentoLogs()
                + "event: console\ndata: [VITRINE] Os consoles ficam em silêncio aqui: nenhuma função é "
                + "executada na vitrine.\n\n";
        }
        StringBuilder sb = new StringBuilder("retry: 60000\n\n");
        Optional<byte[]> retrato = DadosDemonstracao.obter("telemetria");
        if (retrato.isPresent()) {
            sb.append("event: telemetria\n");
            for (String linha : new String(retrato.get(), StandardCharsets.UTF_8).split("\\R")) {
                sb.append("data: ").append(linha).append('\n');
            }
            sb.append('\n');
        }
        return sb.toString();
    }

    private static String batimentoLogs() {
        return "event: heartbeat\ndata: " + IDENTIDADE + "\n\n";
    }

    /**
     * PROPÓSITO DE NEGÓCIO: contagem de fluxos SSE abertos agora, para teste e diagnóstico.
     *
     * <p>INVARIANTES DO DOMÍNIO: nunca negativa; volta a zero quando todos os clientes saem.
     *
     * <p>COMPORTAMENTO EM CASO DE FALHA: não lança.
     */
    int sseAbertasAgora() {
        return sseAbertas.get();
    }
}
