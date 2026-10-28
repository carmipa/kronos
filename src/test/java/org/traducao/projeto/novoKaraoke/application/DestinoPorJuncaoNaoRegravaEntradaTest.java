package org.traducao.projeto.novoKaraoke.application;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.traducao.projeto.core.presentation.web.LogStreamService;
import org.traducao.projeto.legenda.application.DetectorEfeitoKaraokeService;
import org.traducao.projeto.novoKaraoke.domain.NovoKaraokeException;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * PROPÓSITO DE NEGÓCIO: a garantia "a entrada NUNCA é alterada" do Passo 2 comparava os caminhos
 * normalizados como texto; um destino que é JUNÇÃO (ou {@code subst}) apontando para a origem
 * passava e o achatamento regravava a entrada (auditoria da 4.1, 28/10/2026, D8, reproduzido com
 * SHA antes e depois).
 *
 * <p>INVARIANTES DO DOMÍNIO: destino que resolve para a mesma pasta da origem é recusado antes de
 * qualquer leitura ou escrita.
 *
 * <p>COMPORTAMENTO EM CASO DE FALHA: sem permissão para criar junção nesta máquina o teste é
 * ABORTADO (não verificado), nunca aprovado.
 */
class DestinoPorJuncaoNaoRegravaEntradaTest {

    @Test
    @DisplayName("D8: destino que é junção para a origem é recusado e a entrada fica byte a byte")
    void juncaoParaAOrigemEhRecusada(@TempDir Path pasta) throws Exception {
        Assumptions.assumeTrue(System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("win"),
            "junção é recurso do Windows");
        Path origem = Files.createDirectories(pasta.resolve("orig"));
        Path legenda = origem.resolve("ep.ass");
        Files.writeString(legenda, "[Script Info]\nTitle: x\n\n[Events]\nFormat: Layer, Start, End, Style, Name, "
            + "MarginL, MarginR, MarginV, Effect, Text\nDialogue: 0,0:00:01.00,0:00:02.00,Default,,0,0,0,,Oi\n",
            StandardCharsets.UTF_8);
        Path juncao = pasta.resolve("link");
        Process p = new ProcessBuilder("cmd", "/c", "mklink", "/J", juncao.toString(), origem.toString())
            .redirectErrorStream(true).start();
        p.getInputStream().readAllBytes();
        Assumptions.assumeTrue(p.waitFor(30, TimeUnit.SECONDS) && p.exitValue() == 0 && Files.isDirectory(juncao),
            "não foi possível criar a junção nesta máquina");
        byte[] antes = Files.readAllBytes(legenda);

        ConversorKaraokeUseCase conversor = new ConversorKaraokeUseCase();
        conversor.detectorKaraoke = new DetectorEfeitoKaraokeService();
        conversor.logStream = new LogStreamService() {
            @Override
            public void publicarLog(String canal, String mensagem) {
            }
        };
        // Dublês inertes: sem eles a execução que NÃO deveria acontecer quebrava no fim com NPE e o
        // vermelho viria de outra causa (A2).
        conversor.telemetriaService = new org.traducao.projeto.telemetria.TelemetriaService() {
            @Override
            public void registrarOperacao(org.traducao.projeto.telemetria.OperacaoTelemetria operacao) {
            }
        };
        conversor.telemetriaKaraoke = (org.traducao.projeto.novoKaraoke.domain.ports.TelemetriaKaraokePort)
            java.lang.reflect.Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[]{org.traducao.projeto.novoKaraoke.domain.ports.TelemetriaKaraokePort.class},
                (proxy, metodo, args) -> null);
        conversor.persistencia = new org.traducao.projeto.novoKaraoke.infrastructure.NovoKaraokePersistencia() {
            @Override
            public Path salvarManifesto(Path o, Path d, java.util.List<org.traducao.projeto.novoKaraoke.domain.ResultadoConversaoKaraoke> r,
                    long ms) {
                return null;
            }
        };

        try {
            assertThrows(NovoKaraokeException.class, () -> conversor.aplicar(origem, juncao));
            assertArrayEquals(antes, Files.readAllBytes(legenda), "a entrada mudou");
        } finally {
            Files.deleteIfExists(juncao);
        }
    }
}
