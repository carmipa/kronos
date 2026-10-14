package org.traducao.projeto.legendasExtracao.application;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.traducao.projeto.legendasExtracao.application.strategy.ExtratorAssStrategy;
import org.traducao.projeto.legendasExtracao.domain.ExtratorException;
import org.traducao.projeto.legendasExtracao.domain.FaixaLegenda;
import org.traducao.projeto.legendasExtracao.domain.FormatoLegenda;
import org.traducao.projeto.legendasExtracao.domain.ItemExtracao;
import org.traducao.projeto.legendasExtracao.domain.RelatorioExtracao;
import org.traducao.projeto.legendasExtracao.domain.StatusExtracao;
import org.traducao.projeto.legendasExtracao.domain.exceptions.ExtracaoTimeoutException;
import org.traducao.projeto.legendasExtracao.domain.ports.ExtratorVideoPort;
import org.traducao.projeto.telemetria.OperacaoTelemetria;
import org.traducao.projeto.telemetria.TelemetriaService;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Cobre a orquestração do extrator sem ferramentas externas: seleção de faixa
 * pela strategy real, extração para arquivo temporário, validação de saída,
 * guarda anti-sobrescrita, cleanup de parcial, classificação de timeout e o
 * mapeamento da telemetria.
 */
class ExtrairLegendaUseCaseTest {

    private enum Modo { SUCESSO, VAZIO, CONTEUDO_SRT, TIMEOUT, FALHA }

    /** Adaptador de vídeo fake: suporta .mkv e simula a extração conforme o modo. */
    private static final class FakeAdapter implements ExtratorVideoPort {
        private final List<FaixaLegenda> faixas;
        private final Modo modo;

        FakeAdapter(List<FaixaLegenda> faixas, Modo modo) {
            this.faixas = faixas;
            this.modo = modo;
        }

        @Override public boolean suporta(Path v) { return v.toString().toLowerCase().endsWith(".mkv"); }
        @Override public void validarInfraestrutura() { }
        @Override public List<FaixaLegenda> identificarFaixas(Path v) { return faixas; }

        @Override
        public void extrairTrilha(Path v, int trackId, Path caminhoSaida) {
            switch (modo) {
                case TIMEOUT -> throw new ExtracaoTimeoutException("timeout simulado");
                case FALHA -> throw new ExtratorException("falha simulada");
                case VAZIO -> escrever(caminhoSaida, "");
                case CONTEUDO_SRT -> escrever(caminhoSaida, "1\n00:00:01,000 --> 00:00:02,000\nOi\n");
                case SUCESSO -> escrever(caminhoSaida,
                    "[Script Info]\n[Events]\nDialogue: 0,0:00:01.00,0:00:02.00,Default,,0,0,0,,Oi");
            }
        }

        private void escrever(Path out, String conteudo) {
            try {
                Files.writeString(out, conteudo, StandardCharsets.UTF_8);
            } catch (IOException e) {
                throw new ExtratorException("io", e);
            }
        }
    }

    /** Captura a operação registrada sem tocar em disco (não chama super). */
    private static final class TelemetriaSpy extends TelemetriaService {
        OperacaoTelemetria ultima;
        @Override public void registrarOperacao(OperacaoTelemetria op) { this.ultima = op; }
    }

    private static FaixaLegenda faixaAss() {
        return new FaixaLegenda(2, "subtitles", "SubStationAlpha", "S_TEXT/ASS", "eng", "Full", true, false);
    }

    private static FaixaLegenda faixaPgs() {
        return new FaixaLegenda(3, "subtitles", "HDMV PGS", "S_HDMV/PGS", "por", "Sem Titulo", false, true);
    }

    private static ExtrairLegendaUseCase useCase(List<FaixaLegenda> faixas, Modo modo, TelemetriaService tel) {
        return new ExtrairLegendaUseCase(
            List.of(new FakeAdapter(faixas, modo)),
            List.of(new ExtratorAssStrategy()),
            tel);
    }

    private static Path prepararVideo(Path base) throws IOException {
        Path videos = Files.createDirectory(base.resolve("videos"));
        Files.writeString(videos.resolve("Ep01.mkv"), "fake");
        return videos;
    }

    /** Adaptador que no 2º vídeo falha como o MkvToolNixAdapter real: com ou sem a parada religando a flag. */
    private static final class AdaptadorQueFalhaNoSegundo implements ExtratorVideoPort {
        private final boolean comParada;
        private int chamadas = 0;

        AdaptadorQueFalhaNoSegundo(boolean comParada) { this.comParada = comParada; }

        @Override public boolean suporta(Path v) { return v.toString().endsWith(".mkv"); }
        @Override public void validarInfraestrutura() { }
        @Override public List<FaixaLegenda> identificarFaixas(Path v) {
            if (++chamadas == 2) {
                if (comParada) {
                    Thread.currentThread().interrupt();
                }
                throw new ExtratorException("Falha ao invocar mkvmerge para identificar: " + v);
            }
            return List.of(faixaAss());
        }
        @Override public void extrairTrilha(Path v, int trackId, Path caminhoSaida) {
            try {
                Files.writeString(caminhoSaida, "[Script Info]\n[Events]\nDialogue: 0,0:00:01.00,0:00:02.00,Default,,0,0,0,,Oi");
            } catch (IOException e) {
                throw new ExtratorException("io", e);
            }
        }
    }

    private static Path prepararVideos(Path base, int n) throws IOException {
        Path videos = Files.createDirectory(base.resolve("lote"));
        for (int i = 1; i <= n; i++) {
            Files.writeString(videos.resolve(String.format("Ep%02d.mkv", i)), "fake");
        }
        return videos;
    }

    /**
     * E4 da auditoria de 08/10/2026 (reproduzido: 400 MKV, Parar aos 6 s -> 66 OK, 334 "FALHA"
     * e banner SUCESSO): a parada do operador não é falha. O CONTROLE (A1) é a MESMA exceção sem
     * a parada: aí é falha de verdade e o lote segue.
     */
    @Test
    void paradaDoOperadorNaoViraFalhaEmCascata(@TempDir Path base) throws IOException {
        Path videos = prepararVideos(base, 5);
        try {
            RelatorioExtracao rel = new ExtrairLegendaUseCase(
                List.of(new AdaptadorQueFalhaNoSegundo(true)), List.of(new ExtratorAssStrategy()), new TelemetriaSpy())
                .executar(videos, base.resolve("out"), FormatoLegenda.ASS);

            assertEquals(0, rel.getFalhasInesperadas(), "parada contada como falha");
            assertTrue(rel.isInterrompidoPeloOperador(), "a parada tem de ficar registrada");
            assertEquals(1, rel.getLegendasExtraidas());
            assertEquals(4, rel.getNaoProcessadosPorParada(), "o 2o (interrompido) e os 3 seguintes");
        } finally {
            Thread.interrupted();
        }
    }

    /**
     * PROPÓSITO DE NEGÓCIO: vídeo sem faixa do formato pedido (o filme de Macross Delta, só PGS, no
     * teste ponta a ponta de 09/10/2026) não deixa pasta de saída VAZIA para trás. O contra-teste no
     * mesmo método: com faixa, a pasta nasce e recebe a legenda.
     * <p>COMPORTAMENTO EM CASO DE FALHA: pasta vazia criada, ou pasta ausente quando há legenda, reprova.
     */
    @Test
    void semFaixaDoFormatoNaoCriaPastaVazia(@TempDir Path base) throws IOException {
        Path videos = prepararVideo(base);
        Path saida = base.resolve("legendas_extraidas_ass");

        RelatorioExtracao semFaixa = useCase(List.of(faixaPgs()), Modo.SUCESSO, new TelemetriaSpy())
            .executar(videos, saida, FormatoLegenda.ASS);
        assertEquals(0, semFaixa.getLegendasExtraidas());
        assertFalse(Files.exists(saida), "sem legenda para gravar, a pasta de saida nao pode nascer");

        RelatorioExtracao comFaixa = useCase(List.of(faixaAss()), Modo.SUCESSO, new TelemetriaSpy())
            .executar(videos, saida, FormatoLegenda.ASS);
        assertEquals(1, comFaixa.getLegendasExtraidas());
        assertTrue(Files.exists(saida.resolve("Ep01_Track2.ass")), "com faixa, a legenda tem de estar na pasta");
    }

    @Test
    void controleFalhaSemParadaContinuaSendoFalhaEOLoteSegue(@TempDir Path base) throws IOException {
        Path videos = prepararVideos(base, 5);

        RelatorioExtracao rel = new ExtrairLegendaUseCase(
            List.of(new AdaptadorQueFalhaNoSegundo(false)), List.of(new ExtratorAssStrategy()), new TelemetriaSpy())
            .executar(videos, base.resolve("out"), FormatoLegenda.ASS);

        assertEquals(1, rel.getFalhasInesperadas(), "falha real continua sendo falha");
        assertFalse(rel.isInterrompidoPeloOperador());
        assertEquals(4, rel.getLegendasExtraidas(), "o lote segue depois de uma falha real");
    }

    /**
     * E6 da auditoria de 08/10/2026 (reproduzido): S01/Ep01.mkv e S02/Ep01.mkv dão o mesmo destino
     * na saída plana; o segundo aparecia como "Já existe (preservado)" e o lote dizia SUCESSO 1 de 2.
     * A colisão desta execução é falha nomeada. (O arquivo que já existia ANTES continua sendo
     * "Já existe" — controle em naoSobrescreveArquivoExistente.)
     */
    @Test
    void colisaoDeNomeNaMesmaExecucaoEFalhaNomeada(@TempDir Path base) throws IOException {
        Path videos = Files.createDirectory(base.resolve("serie"));
        Files.createDirectories(videos.resolve("S01"));
        Files.createDirectories(videos.resolve("S02"));
        Files.writeString(videos.resolve("S01").resolve("Ep01.mkv"), "fake");
        Files.writeString(videos.resolve("S02").resolve("Ep01.mkv"), "fake");

        RelatorioExtracao rel = useCase(List.of(faixaAss()), Modo.SUCESSO, new TelemetriaSpy())
            .executar(videos, base.resolve("out"), FormatoLegenda.ASS);

        assertEquals(1, rel.getLegendasExtraidas());
        assertEquals(0, rel.getArquivosJaExistentes(), "a colisao nao pode aparecer como 'ja existe (preservado)'");
        assertEquals(1, rel.getFalhasInesperadas(), "a legenda do 2o video NAO foi extraida: e falha");
        assertTrue(rel.getItens().stream().anyMatch(i -> i.detalhe() != null && i.detalhe().startsWith("Colisão")),
            "o item tem de nomear a colisao: " + rel.getItens());
    }

    /**
     * Gundam ZZ, 14/10/2026: a pasta da obra tinha os originais em {@code en/} e os MKVs publicados
     * ({@code _PTBR.mkv}, saída do remux 5.1) na raiz. A extração percorreu os dois: 95 vídeos para
     * 48 originais, 47 legendas duplicadas e, nos dois PTBR sem faixa inglesa, a faixa PORTUGUESA
     * extraída como se fosse origem. Com os originais na mesma pasta, a saída do remux não é origem.
     */
    @Test
    void pastaComOriginaisESaidasDoRemuxNaoExtraiAsSaidas(@TempDir Path base) throws IOException {
        Path obra = Files.createDirectory(base.resolve("obra"));
        Files.createDirectory(obra.resolve("en"));
        Files.writeString(obra.resolve("en").resolve("Obra - S01E01.mkv"), "fake");
        Files.writeString(obra.resolve("en").resolve("Obra - S01E02.mkv"), "fake");
        Files.writeString(obra.resolve("Obra.S01E01_PTBR.mkv"), "fake");
        Files.writeString(obra.resolve("Obra.S01E02_PTBR.mkv"), "fake");
        TelemetriaSpy tel = new TelemetriaSpy();

        RelatorioExtracao rel = useCase(List.of(faixaAss()), Modo.SUCESSO, tel)
            .executar(obra, base.resolve("out"), FormatoLegenda.ASS);

        assertEquals(2, rel.getArquivosDetectados(), "so os originais entram: " + rel.getItens());
        assertEquals(2, rel.getLegendasExtraidas());
        assertFalse(Files.exists(base.resolve("out").resolve("Obra.S01E01_PTBR_Track2.ass")),
            "a saida do remux nao pode virar legenda de origem");
        assertEquals(2, rel.getSaidasDoRemuxIgnoradas());
        assertTrue(tel.ultima.detalhe().contains("saídas do remux ignoradas: 2"), tel.ultima.detalhe());
    }

    /**
     * CASO-CONTROLE (A1): pasta SÓ com MKVs publicados continua extraindo — é como o acervo do
     * notebook foi regenerado em 12/10, a partir do publicado, quando a pasta de origem não existia.
     */
    @Test
    void pastaSoComSaidasDoRemuxContinuaExtraindo(@TempDir Path base) throws IOException {
        Path obra = Files.createDirectory(base.resolve("obra"));
        Files.writeString(obra.resolve("Obra.S01E01_PTBR.mkv"), "fake");

        RelatorioExtracao rel = useCase(List.of(faixaAss()), Modo.SUCESSO, new TelemetriaSpy())
            .executar(obra, base.resolve("out"), FormatoLegenda.ASS);

        assertEquals(1, rel.getLegendasExtraidas());
        assertEquals(0, rel.getSaidasDoRemuxIgnoradas());
    }

    /** CASO-CONTROLE (A1): o MKV publicado escolhido DIRETAMENTE é extraído — o operador pediu aquele. */
    @Test
    void saidaDoRemuxEscolhidaDiretamenteContinuaExtraindo(@TempDir Path base) throws IOException {
        Path obra = Files.createDirectory(base.resolve("obra"));
        Files.writeString(obra.resolve("Obra - S01E01.mkv"), "fake");
        Path publicado = obra.resolve("Obra.S01E01_PTBR.mkv");
        Files.writeString(publicado, "fake");

        RelatorioExtracao rel = useCase(List.of(faixaAss()), Modo.SUCESSO, new TelemetriaSpy())
            .executar(publicado, base.resolve("out"), FormatoLegenda.ASS);

        assertEquals(1, rel.getLegendasExtraidas());
    }

    /**
     * Cópia de trabalho e backup do KRONOS ({@code _kronos_regen_*}, {@code _kronos_backup_*}) dentro
     * da pasta da obra não são origem: com o mesmo nome do original, viravam colisão no lote. O
     * controle (A1) é a pasta {@code kronos} sem o sublinhado, que é do operador e é percorrida.
     */
    @Test
    void pastaDeTrabalhoDoKronosNaoEhOrigem(@TempDir Path base) throws IOException {
        Path obra = Files.createDirectory(base.resolve("obra"));
        Files.writeString(obra.resolve("Ep01.mkv"), "fake");
        Files.createDirectory(obra.resolve("_kronos_backup_20261014"));
        Files.writeString(obra.resolve("_kronos_backup_20261014").resolve("Ep01.mkv"), "fake");
        Files.createDirectory(obra.resolve("kronos"));
        Files.writeString(obra.resolve("kronos").resolve("Ep02.mkv"), "fake");

        RelatorioExtracao rel = useCase(List.of(faixaAss()), Modo.SUCESSO, new TelemetriaSpy())
            .executar(obra, base.resolve("out"), FormatoLegenda.ASS);

        assertEquals(2, rel.getArquivosDetectados(), "Ep01 da obra e Ep02 da pasta kronos: " + rel.getItens());
        assertEquals(2, rel.getLegendasExtraidas());
        assertEquals(0, rel.getFalhasInesperadas(), "a copia de backup nao pode colidir com o original");
    }

    /** E5: vídeo com legenda de TEXTO em mov_text não é "sem legenda/hardsub". Controle: só PGS (imagem). */
    @Test
    void legendaDeTextoNaoConvertidaEContadaAParte(@TempDir Path base) throws IOException {
        FaixaLegenda movText = new FaixaLegenda(2, "subtitle", "mov_text", "mov_text", "eng", "", true, false);
        RelatorioExtracao rel = useCase(List.of(movText), Modo.SUCESSO, new TelemetriaSpy())
            .executar(prepararVideo(base), base.resolve("out"), FormatoLegenda.ASS);
        assertEquals(1, rel.getArquivosSemLegenda());
        assertEquals(1, rel.getSemFaixaComTextoNaoSuportado(), "mov_text e legenda de texto, nao hardsub");

        RelatorioExtracao controle = useCase(List.of(faixaPgs()), Modo.SUCESSO, new TelemetriaSpy())
            .executar(prepararVideos(base, 1), base.resolve("out2"), FormatoLegenda.ASS);
        assertEquals(0, controle.getSemFaixaComTextoNaoSuportado(), "CONTROLE: PGS e imagem, nao conta como texto");
    }

    @Test
    void extraiValidaEMoveParaFinal(@TempDir Path base) throws IOException {
        Path videos = prepararVideo(base);
        Path saida = base.resolve("out");
        TelemetriaSpy tel = new TelemetriaSpy();

        RelatorioExtracao rel = useCase(List.of(faixaAss()), Modo.SUCESSO, tel)
            .executar(videos, saida, FormatoLegenda.ASS);

        assertEquals(1, rel.getLegendasExtraidas());
        assertEquals(1, rel.getFaixasEncontradas());
        assertTrue(Files.exists(saida.resolve("Ep01_Track2.ass")));
        assertFalse(Files.exists(saida.resolve("Ep01_Track2.ass.part")), "parcial não deve sobrar");

        ItemExtracao item = rel.getItens().get(0);
        assertEquals(StatusExtracao.SUCESSO, item.status());
        assertEquals(2, item.trackId());
        assertEquals("Ep01_Track2.ass", item.arquivoGerado());

        assertNotNull(tel.ultima);
        assertEquals(1, tel.ultima.arquivosProcessados());
        assertEquals(1, tel.ultima.itensDetectados()); // faixas encontradas
        assertEquals(1, tel.ultima.itensCorrigidos()); // extraídas
    }

    @Test
    void semFaixaDoFormatoNaoCaiEmOutroFormato(@TempDir Path base) throws IOException {
        Path videos = prepararVideo(base);

        RelatorioExtracao rel = useCase(List.of(faixaPgs()), Modo.SUCESSO, new TelemetriaSpy())
            .executar(videos, base.resolve("out"), FormatoLegenda.ASS);

        assertEquals(0, rel.getLegendasExtraidas());
        assertEquals(1, rel.getArquivosSemLegenda());
        assertEquals(1, rel.getFaixasEncontradas()); // achou a faixa PGS, só não do formato pedido
        assertEquals(StatusExtracao.FAIXA_NAO_ENCONTRADA, rel.getItens().get(0).status());
    }

    @Test
    void naoSobrescreveArquivoExistente(@TempDir Path base) throws IOException {
        Path videos = prepararVideo(base);
        Path saida = Files.createDirectory(base.resolve("out"));
        Path existente = saida.resolve("Ep01_Track2.ass");
        Files.writeString(existente, "CONTEUDO ORIGINAL");

        RelatorioExtracao rel = useCase(List.of(faixaAss()), Modo.SUCESSO, new TelemetriaSpy())
            .executar(videos, saida, FormatoLegenda.ASS);

        assertEquals(0, rel.getLegendasExtraidas());
        assertEquals(1, rel.getArquivosJaExistentes());
        assertEquals("CONTEUDO ORIGINAL", Files.readString(existente), "arquivo deve ser preservado");
        assertEquals(StatusExtracao.JA_EXISTE, rel.getItens().get(0).status());
    }

    @Test
    void conteudoVazioViraFalhaERemoveParcial(@TempDir Path base) throws IOException {
        Path videos = prepararVideo(base);
        Path saida = base.resolve("out");

        RelatorioExtracao rel = useCase(List.of(faixaAss()), Modo.VAZIO, new TelemetriaSpy())
            .executar(videos, saida, FormatoLegenda.ASS);

        assertEquals(0, rel.getLegendasExtraidas());
        assertEquals(1, rel.getFalhasInesperadas());
        assertFalse(Files.exists(saida.resolve("Ep01_Track2.ass")));
        assertFalse(Files.exists(saida.resolve("Ep01_Track2.ass.part")));
        assertEquals(StatusExtracao.FALHA, rel.getItens().get(0).status());
    }

    @Test
    void conteudoDeOutroFormatoViraFalha(@TempDir Path base) throws IOException {
        Path videos = prepararVideo(base);
        Path saida = base.resolve("out");

        RelatorioExtracao rel = useCase(List.of(faixaAss()), Modo.CONTEUDO_SRT, new TelemetriaSpy())
            .executar(videos, saida, FormatoLegenda.ASS);

        assertEquals(0, rel.getLegendasExtraidas());
        assertEquals(1, rel.getFalhasInesperadas());
        assertFalse(Files.exists(saida.resolve("Ep01_Track2.ass.part")));
        assertEquals(StatusExtracao.FALHA, rel.getItens().get(0).status());
    }

    @Test
    void timeoutClassificadoSeparadoDeFalha(@TempDir Path base) throws IOException {
        Path videos = prepararVideo(base);
        Path saida = base.resolve("out");

        RelatorioExtracao rel = useCase(List.of(faixaAss()), Modo.TIMEOUT, new TelemetriaSpy())
            .executar(videos, saida, FormatoLegenda.ASS);

        assertEquals(0, rel.getLegendasExtraidas());
        assertEquals(1, rel.getTimeouts());
        assertEquals(0, rel.getFalhasInesperadas());
        assertFalse(Files.exists(saida.resolve("Ep01_Track2.ass.part")));
        assertEquals(StatusExtracao.TIMEOUT, rel.getItens().get(0).status());
    }
}
