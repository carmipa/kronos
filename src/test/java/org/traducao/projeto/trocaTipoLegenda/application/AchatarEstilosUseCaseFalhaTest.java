package org.traducao.projeto.trocaTipoLegenda.application;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.traducao.projeto.legenda.application.DetectorEfeitoKaraokeService;
import org.traducao.projeto.legenda.application.ProtecaoCamadasMusicaisService;
import org.traducao.projeto.legenda.infrastructure.EscritorLegendaAss;
import org.traducao.projeto.legenda.infrastructure.LeitorLegendaAss;
import org.traducao.projeto.trocaTipoLegenda.domain.ResultadoTrocaFonte;
import org.traducao.projeto.trocaTipoLegenda.domain.ports.ConsoleTrocaPort;
import org.traducao.projeto.trocaTipoLegenda.infrastructure.ArmazenamentoBackupKronosAdapter;
import org.traducao.projeto.trocaTipoLegenda.infrastructure.ClassificadorCamadaMusicalLegendaAdapter;
import org.traducao.projeto.trocaTipoLegenda.infrastructure.LegendaIoAssAdapter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * PROPÓSITO DE NEGÓCIO: o achatamento da 1.3 grava no acervo; arquivo que não gravou tem de
 * aparecer como falha no fim, e não sob o banner de sucesso.
 *
 * <p>INVARIANTES DO DOMÍNIO: tudo em diretório temporário; backups sob o {@code @TempDir}.
 *
 * <p>COMPORTAMENTO EM CASO DE FALHA: asserção reprovada diz o que o console mostrou.
 */
class AchatarEstilosUseCaseFalhaTest {

    /** Guarda o que o caso de uso declarou, por tipo, sem cor nem System.out. */
    private static final class ConsoleGravado implements ConsoleTrocaPort {
        final List<String> sucessos = new ArrayList<>();
        final List<String> avisos = new ArrayList<>();

        @Override public void titulo(String m) { }
        @Override public void info(String m) { }
        @Override public void sucesso(String m) { sucessos.add(m); }
        @Override public void aviso(String m) { avisos.add(m); }
        @Override public void erro(String m) { }
    }

    private AchatarEstilosUseCase montar(Path backups, ConsoleGravado console) {
        DetectorEfeitoKaraokeService detector = new DetectorEfeitoKaraokeService();
        AchatadorEstilosDecorativosService achatador = new AchatadorEstilosDecorativosService(
            new AuditoriaFontesService(),
            new ClassificadorCamadaMusicalLegendaAdapter(detector, new ProtecaoCamadasMusicaisService(detector)));
        return new AchatarEstilosUseCase(
            new LegendaIoAssAdapter(new LeitorLegendaAss(), new EscritorLegendaAss()),
            achatador,
            (tipo, detalhe, duracaoMs, arquivos, detectados, corrigidos) -> { },
            new ArmazenamentoBackupKronosAdapter(backups),
            console);
    }

    @Test
    void arquivoQueNaoGravaContaComoFalhaEOFimSaiComoAviso(@TempDir Path dir) throws IOException {
        Path pasta = Files.createDirectories(dir.resolve("legendas"));
        Files.writeString(pasta.resolve("quebrada.ass"), "[Script Info]\nTitle: sem eventos\n", StandardCharsets.UTF_8);
        ConsoleGravado console = new ConsoleGravado();

        ResultadoTrocaFonte r = montar(dir.resolve("backups"), console).aplicar(pasta);

        assertEquals(1, r.totalFalhas());
        assertTrue(console.avisos.stream().anyMatch(m -> m.contains("1 ARQUIVO(S) SEM GRAVAR")), console.avisos.toString());
        assertFalse(console.sucessos.stream().anyMatch(m -> m.contains("[SUCESSO]")), console.sucessos.toString());
    }

    @Test
    void semFalhaOFimContinuaSucesso(@TempDir Path dir) throws IOException {
        Path pasta = Files.createDirectories(dir.resolve("legendas"));
        Files.writeString(pasta.resolve("limpa.ass"), String.join("\n",
            "[Script Info]", "ScriptType: v4.00+", "",
            "[V4+ Styles]", "Format: Name, Fontname", "Style: Default,Arial", "",
            "[Events]", "Format: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text",
            "Dialogue: 0,0:00:01.00,0:00:02.00,Default,,0,0,0,,Ola", ""), StandardCharsets.UTF_8);
        ConsoleGravado console = new ConsoleGravado();

        ResultadoTrocaFonte r = montar(dir.resolve("backups"), console).aplicar(pasta);

        assertEquals(0, r.totalFalhas());
        assertTrue(console.sucessos.stream().anyMatch(m -> m.contains("[SUCESSO]")), console.sucessos.toString());
    }
}
