package org.traducao.projeto.novoKaraoke.presentation;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.traducao.projeto.core.execucao.FilaExecucaoPipeline;
import org.traducao.projeto.core.io.GuardaCaminhoEntrada;
import org.traducao.projeto.core.presentation.web.LogStreamService;
import org.traducao.projeto.novoKaraoke.application.ConversorKaraokeUseCase;
import org.traducao.projeto.novoKaraoke.domain.ResultadoConversaoKaraoke;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * PROPÓSITO DE NEGÓCIO: o "Copiar como caminho" do Explorer põe aspas. A guarda as tirava e
 * aprovava; o controller do Passo 2 fazia {@code Paths.get} com as aspas e a rota respondia 500
 * (auditoria da 4.1, 28/10/2026, D7). O conserto de 09/10 ({@code caminhoDaInterface},
 * 0e18ed57) chegou a três controllers e não a este.
 *
 * <p>INVARIANTES DO DOMÍNIO: origem e destino informados chegam ao caso de uso sem as aspas.
 *
 * <p>COMPORTAMENTO EM CASO DE FALHA: a asserção mostra o caminho que chegou.
 */
class CaminhoDaTelaNoAchatarTest {

    private static final class FilaQueRoda extends FilaExecucaoPipeline {
        @Override
        public Future<?> submeter(Runnable tarefa) {
            tarefa.run();
            return CompletableFuture.completedFuture(null);
        }
    }

    private static final class ConversorQueGuarda extends ConversorKaraokeUseCase {
        final List<Path> recebidos = new ArrayList<>();

        @Override
        public List<ResultadoConversaoKaraoke> aplicar(Path origem, Path destino) {
            recebidos.add(origem);
            recebidos.add(destino);
            return List.of();
        }
    }

    @Test
    @DisplayName("D7: origem e destino colados entre aspas chegam ao Passo 2 sem as aspas")
    void caminhoEntreAspasChegaLimpo(@TempDir Path pasta) throws Exception {
        Path origem = Files.createDirectories(pasta.resolve("traducao_ptbr-karaoke-ptbr"));
        Path destino = pasta.resolve("legenda-simplificada");
        ConversorQueGuarda conversor = new ConversorQueGuarda();
        NovoKaraokeController c = new NovoKaraokeController();
        c.filaExecucao = new FilaQueRoda();
        c.conversor = conversor;
        c.guardaCaminho = new GuardaCaminhoEntrada();
        c.logStream = new LogStreamService() {
            @Override
            public void publicarLog(String canal, String mensagem) {
            }
        };

        c.aplicar(new NovoKaraokeRequest("\"" + origem + "\"", "\"" + destino + "\""));

        assertEquals(List.of(origem, destino), conversor.recebidos);
    }
}
