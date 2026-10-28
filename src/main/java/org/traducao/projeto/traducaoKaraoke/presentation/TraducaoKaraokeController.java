package org.traducao.projeto.traducaoKaraoke.presentation;

import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.traducao.projeto.core.execucao.FilaExecucaoPipeline;
import org.traducao.projeto.lore.infrastructure.GerenciadorContexto;
import org.traducao.projeto.core.presentation.web.LogStreamService;
import org.traducao.projeto.traducaoKaraoke.application.TraduzirKaraokeUseCase;
import org.traducao.projeto.traducaoKaraoke.domain.TraducaoKaraokeException;

import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Endpoints do módulo Tradução de Karaokê. A simulação só lê arquivos e roda
 * async fora da fila (mesmo padrão do Karaokê Simples); a APLICAÇÃO chama o
 * LLM e congela o contexto escolhido para o job inteiro. O trabalho pesado
 * obrigatoriamente entra na {@link FilaExecucaoPipeline}.
 */
@Path("/api/traducao-karaoke")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class TraducaoKaraokeController {

    private static final Logger log = LoggerFactory.getLogger(TraducaoKaraokeController.class);

    @Inject
    TraduzirKaraokeUseCase useCase;

    @Inject
    LogStreamService logStream;

    @Inject
    FilaExecucaoPipeline filaExecucao;

    @Inject
    GerenciadorContexto gerenciadorContexto;

    @Inject
    org.traducao.projeto.core.io.GuardaCaminhoEntrada guardaCaminho;

    @POST
    @Path("/simular")
    public Response simular(TraducaoKaraokeRequest request) {
        String erro = validar(request, false);
        if (erro != null) {
            return Response.status(Response.Status.BAD_REQUEST).entity(Map.of("error", erro)).build();
        }
        // Convertido AQUI, na thread da requisição, pelo dono único do caminho da tela (aspas do
        // "Copiar como caminho", relativo ancorado). Até 28/10/2026 era Paths.get cru dentro da
        // tarefa: a guarda aprovava, a tela dizia "iniciada" e a tarefa morria sem deixar rastro.
        java.nio.file.Path pasta = org.traducao.projeto.core.io.GuardaCaminhoEntrada.caminhoDaInterface(
            request.caminhoOrigem());
        CompletableFuture.runAsync(() -> {
            try {
                useCase.simular(pasta, request.contextoId());
            } catch (TraducaoKaraokeException e) {
                logStream.publicarLog(TraduzirKaraokeUseCase.CANAL_LOG, "[ERRO] " + e.getMessage());
            } catch (Exception e) {
                log.error("Erro na simulação da tradução de karaokê", e);
                logStream.publicarLog(TraduzirKaraokeUseCase.CANAL_LOG,
                    "[ERRO FATAL] Falha durante a simulação: " + e.getMessage());
            }
        });
        return Response.ok(Map.of("mensagem",
            "Simulação da tradução de karaokê iniciada. Acompanhe a classificação linha a linha no console abaixo.")).build();
    }

    @POST
    @Path("/aplicar")
    public Response aplicar(TraducaoKaraokeRequest request) {
        String erro = validar(request, true);
        if (erro != null) {
            return Response.status(Response.Status.BAD_REQUEST).entity(Map.of("error", erro)).build();
        }
        java.nio.file.Path pasta = org.traducao.projeto.core.io.GuardaCaminhoEntrada.caminhoDaInterface(
            request.caminhoOrigem());
        filaExecucao.submeter(() -> {
            logStream.definirCanalAtual(TraduzirKaraokeUseCase.CANAL_LOG);
            try {
                useCase.aplicar(pasta, request.contextoId());
            } catch (TraducaoKaraokeException e) {
                logStream.publicarLog(TraduzirKaraokeUseCase.CANAL_LOG, "[ERRO] " + e.getMessage());
            } catch (Exception e) {
                log.error("Erro na tradução de karaokê", e);
                logStream.publicarLog(TraduzirKaraokeUseCase.CANAL_LOG,
                    "[ERRO FATAL] Falha durante a tradução: " + e.getMessage());
            }
        });
        return Response.ok(Map.of("mensagem",
            "Tradução de karaokê enviada para a fila do pipeline. Acompanhe o progresso no console abaixo.")).build();
    }

    private String validar(TraducaoKaraokeRequest request, boolean exigeContextoValido) {
        if (request == null || request.caminhoOrigem() == null || request.caminhoOrigem().trim().isEmpty()) {
            return "Informe a pasta com as legendas que deseja traduzir.";
        }
        // ANTES do CompletableFuture. Depois de enfileirar, a resposta HTTP ja saiu
        // como "simulacao iniciada" e o unico canal que resta e o log — que ninguem
        // esta lendo no instante do clique. Medido em 11/08/2026: pasta inexistente
        // devolvia 200 "iniciada".
        var recusa = guardaCaminho.conferirDiretorio("A pasta com as legendas", request.caminhoOrigem());
        if (recusa.isPresent()) {
            return recusa.get().mensagem();
        }
        if (exigeContextoValido && (request.contextoId() == null || request.contextoId().isBlank())) {
            return "Selecione a obra/contexto que deve ser usado em toda a tradução de karaokê.";
        }
        if (exigeContextoValido && !gerenciadorContexto.existeContexto(request.contextoId())) {
            return "Contexto de tradução desconhecido: \"" + request.contextoId()
                + "\". Recarregue a página e selecione uma obra válida.";
        }
        return null;
    }
}
