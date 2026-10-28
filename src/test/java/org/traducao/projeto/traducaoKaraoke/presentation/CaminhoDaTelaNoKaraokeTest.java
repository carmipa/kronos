package org.traducao.projeto.traducaoKaraoke.presentation;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.traducao.projeto.core.io.GuardaCaminhoEntrada;
import org.traducao.projeto.core.presentation.web.LogStreamService;
import org.traducao.projeto.traducaoKaraoke.application.TraduzirKaraokeUseCase;
import org.traducao.projeto.traducaoKaraoke.domain.ResultadoTraducaoKaraoke;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * PROPÓSITO DE NEGÓCIO: a pasta colada com aspas pelo "Copiar como caminho" do Explorer chega à
 * 4.1 sem as aspas. Até 28/10/2026 (auditoria, C4) a guarda aprovava, a tela dizia "iniciada" e a
 * tarefa morria depois, com {@code InvalidPathException}, sem manifesto nem telemetria.
 *
 * <p>INVARIANTES DO DOMÍNIO: o caso de uso recebe exatamente a pasta, sem aspas.
 *
 * <p>COMPORTAMENTO EM CASO DE FALHA: a asserção diz se o caso de uso nem foi chamado.
 */
class CaminhoDaTelaNoKaraokeTest {

    private static final class CasoDeUsoQueGuarda extends TraduzirKaraokeUseCase {
        final CompletableFuture<Path> recebida = new CompletableFuture<>();

        @Override
        public List<ResultadoTraducaoKaraoke> simular(Path pastaOrigem, String contextoId) {
            recebida.complete(pastaOrigem);
            return List.of();
        }
    }

    @Test
    @DisplayName("C4: pasta entre aspas chega ao caso de uso sem as aspas")
    void pastaEntreAspasChegaLimpa(@TempDir Path pasta) throws Exception {
        Path legendas = Files.createDirectories(pasta.resolve("legendas"));
        CasoDeUsoQueGuarda casoDeUso = new CasoDeUsoQueGuarda();
        TraducaoKaraokeController c = new TraducaoKaraokeController();
        c.useCase = casoDeUso;
        c.guardaCaminho = new GuardaCaminhoEntrada();
        c.logStream = new LogStreamService() {
            @Override
            public void publicarLog(String canal, String mensagem) {
            }
        };

        assertEquals(200, c.simular(new TraducaoKaraokeRequest("\"" + legendas + "\"", null)).getStatus());

        Path recebida = null;
        try {
            recebida = casoDeUso.recebida.get(10, TimeUnit.SECONDS);
        } catch (java.util.concurrent.TimeoutException e) {
            // cai na asserção abaixo
        }
        assertTrue(recebida != null, "o caso de uso nem foi chamado: a tarefa morreu no Paths.get com as aspas");
        assertEquals(legendas, recebida);
    }
}
