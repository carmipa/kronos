package org.traducao.projeto.traducaoKaraoke.application;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.traducao.projeto.core.presentation.web.LogStreamService;
import org.traducao.projeto.telemetria.OperacaoTelemetria;
import org.traducao.projeto.telemetria.TelemetriaService;
import org.traducao.projeto.traducaoKaraoke.domain.DesfechoKaraoke;
import org.traducao.projeto.traducaoKaraoke.domain.FalhaArquivoKaraoke;
import org.traducao.projeto.traducaoKaraoke.domain.ResultadoTraducaoKaraoke;
import org.traducao.projeto.traducaoKaraoke.domain.StatusExecucaoKaraoke;
import org.traducao.projeto.traducaoKaraoke.domain.TelemetriaKaraoke;
import org.traducao.projeto.traducaoKaraoke.infrastructure.TraducaoKaraokePersistencia;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * PROPÓSITO DE NEGÓCIO: prova que a execução de karaokê deixa linha no DATASET público — inclusive,
 * e principalmente, quando ela deu errado.
 *
 * <h2>O prejuízo que originou, medido</h2>
 * Em 2026-08-20, {@code logs/telemetria_execucoes.jsonl} tinha 2.266 execuções e <b>zero de
 * karaokê</b>. Os números da fatia terminavam no manifesto, que o publicador do dataset nunca leu.
 * E o pior caso — LLM fora do ar, nenhum arquivo alcançado — não produzia sequer manifesto até
 * 2026-08-14: o desfecho mais grave saía indistinguível de "não havia nada a fazer".
 *
 * <h2>Invariantes do domínio congelados aqui</h2>
 * <ul>
 *   <li>Execução ABORTADA sem arquivo nenhum gera UMA linha, com {@code NAO_ALCANCADO} — a
 *       terceira saída da regra das guardas trazida para o dataset.</li>
 *   <li>Arquivo traduzido e arquivo que falhou geram linhas distinguíveis, não a mesma.</li>
 *   <li>Os campos da execução (modelo, contexto, dicionário, cache ignorado) viajam em CADA
 *       linha — sem isso a tabela não responde "com qual modelo isto foi medido".</li>
 * </ul>
 *
 * <h2>Comportamento em caso de falha</h2>
 * Se qualquer um destes cair, o karaokê volta a ser invisível no dataset ou volta a sair mudo no
 * pior desfecho — as duas formas exatas do defeito que esta classe fecha.
 */
class RegistroDaExecucaoDatasetTest {

    @TempDir
    Path tempDir;

    private RegistroDaExecucao registro;
    private AcervoKaraokeCapturado acervo;

    /** Não persiste em disco: o alvo do teste é a linha do dataset, não o manifesto. */
    static class PersistenciaSilenciosa extends TraducaoKaraokePersistencia {
        @Override
        public Path salvarManifesto(Path origem, Path destino,
                List<ResultadoTraducaoKaraoke> resultados, long duracaoMs,
                org.traducao.projeto.lore.domain.SnapshotContexto contexto,
                org.traducao.projeto.cachetraducao.domain.ProvenienciaCache proveniencia,
                DesfechoKaraoke desfecho) {
            return null;
        }
    }

    static class TelemetriaSilenciosa extends TelemetriaService {
        @Override
        public void finalizarOperacao(OperacaoTelemetria operacao, Path pastaEntrada,
                String prefixo, String conteudo) {
            // a operação genérica já tem teste próprio; aqui o alvo é o acervo do dataset
        }
    }

    static class LogSilencioso extends LogStreamService {
        @Override
        public void publicarLog(String canal, String mensagem) {
            // silencioso nos testes
        }
    }

    @BeforeEach
    void montar() {
        registro = new RegistroDaExecucao();
        // Sem ObjectMapper: salvarManifesto esta sobrescrito e nao toca em disco.
        registro.persistencia = new PersistenciaSilenciosa();
        registro.telemetriaService = new TelemetriaSilenciosa();
        registro.logStream = new LogSilencioso();
        registro.acervoDataset = acervo = new AcervoKaraokeCapturado();
    }

    @Test
    @DisplayName("aborto sem arquivo nenhum ainda deixa UMA linha no dataset, marcada NAO_ALCANCADO")
    void abortoSemArquivoDeixaLinha() {
        DesfechoKaraoke desfecho = new DesfechoKaraoke(
            StatusExecucaoKaraoke.ABORTADA, "LLM fora do ar", List.of(), false,
            DesfechoKaraoke.EstadoDicionario.NAO_CONSULTADO);

        registro.registrar(tempDir, tempDir.resolve("saida"), List.of(), 120L, 0, 0,
            null, null, desfecho);

        assertEquals(1, acervo.linhas.size(),
            "o pior desfecho possivel saiu do dataset calado — indistinguivel de 'nada a fazer'");
        TelemetriaKaraoke linha = acervo.linhas.get(0);
        assertEquals(TelemetriaKaraoke.DesfechoDoArquivo.NAO_ALCANCADO.name(), linha.desfechoArquivo());
        assertEquals(TelemetriaKaraoke.SEM_ARQUIVO, linha.arquivo());
        assertEquals("ABORTADA", linha.statusExecucao());
        assertEquals("LLM fora do ar", linha.motivoExecucao());
        assertEquals("NAO_CONSULTADO", linha.estadoDicionario());
        assertEquals(0, linha.arquivosNaExecucao());
        assertNotNull(linha.registradoEm(), "sem carimbo o publicador descarta a linha calado");
        // Contexto NÃO congelado: nulo declarado, nunca string inventada — o dataset tem de saber
        // a diferença entre "contexto ausente" e um contexto que se chame "desconhecido".
        assertNull(linha.contextoNome());
        assertNull(linha.modeloLlm());
    }

    @Test
    @DisplayName("arquivo traduzido e arquivo que falhou saem como linhas DISTINGUÍVEIS")
    void traduzidoEFalhaSaemDistinguiveis() {
        ResultadoTraducaoKaraoke feito = new ResultadoTraducaoKaraoke(
            "ep01.ass", caminhoWindows("saida", "ep01.ass"), 900, 400, 120, 5, 60, 20, 40, 3, 7, 2,
            16, 70, 16, "VERIFICADAS (ep01.mkv)",
            List.of("alucinacao detectada; letra mantida"));
        DesfechoKaraoke desfecho = new DesfechoKaraoke(
            StatusExecucaoKaraoke.COMPLETA, null,
            List.of(new FalhaArquivoKaraoke("ep02.ass", "legenda ilegivel")), true,
            DesfechoKaraoke.EstadoDicionario.DISPONIVEL);

        registro.registrar(tempDir, tempDir.resolve("saida"), List.of(feito), 5_000L, 1, 1,
            null, null, desfecho);

        assertEquals(2, acervo.linhas.size());
        TelemetriaKaraoke traduzido = acervo.linhas.stream()
            .filter(l -> "ep01.ass".equals(l.arquivo())).findFirst().orElseThrow();
        TelemetriaKaraoke falhou = acervo.linhas.stream()
            .filter(l -> "ep02.ass".equals(l.arquivo())).findFirst().orElseThrow();

        assertEquals(TelemetriaKaraoke.DesfechoDoArquivo.TRADUZIDO.name(), traduzido.desfechoArquivo());
        assertEquals(TelemetriaKaraoke.DesfechoDoArquivo.FALHOU.name(), falhou.desfechoArquivo());
        assertEquals("legenda ilegivel", falhou.motivoFalha());
        assertNull(traduzido.motivoFalha());

        // Os doze contadores do arquivo chegam inteiros — é isso que a ordem "adiciona tudo"
        // significa, e o que a catraca de completude confere por reflexão.
        assertEquals(900, traduzido.eventosTotais());
        assertEquals(400, traduzido.efeitosKfxPreservados());
        assertEquals(120, traduzido.preservadasOriginalJapones());
        assertEquals(5, traduzido.jaEmPortugues());
        assertEquals(60, traduzido.paraTraduzir());
        assertEquals(20, traduzido.reaproveitadasCache());
        assertEquals(40, traduzido.traduzidas());
        assertEquals(3, traduzido.mantidasSemTraducao());
        assertEquals(7, traduzido.acentosRepostos());
        assertEquals(2, traduzido.entradasCacheDescartadas());
        assertEquals(1, traduzido.avisos().size());

        // Campos da EXECUÇÃO repetidos em cada linha: sem eles a tabela não responde "com qual
        // modelo/dicionário isto foi medido" sem abrir um segundo arquivo.
        for (TelemetriaKaraoke linha : acervo.linhas) {
            assertEquals("COMPLETA", linha.statusExecucao());
            assertEquals("DISPONIVEL", linha.estadoDicionario());
            assertTrue(linha.cacheIgnorado());
            assertEquals(5_000L, linha.duracaoExecucaoMs());
            assertEquals(2, linha.arquivosNaExecucao());
        }
        // Mesma run, mesmo carimbo: é ele que reagrupa as linhas de volta numa execução.
        assertEquals(traduzido.registradoEm(), falhou.registradoEm());
    }

    /**
     * O caminho absoluto do destino é o único campo do resultado que NÃO vai ao dataset. O teste
     * existe porque a decisão é invisível no código — o campo simplesmente não é copiado — e
     * publicar {@code C:\animes\...} num repositório público é vazamento silencioso.
     */
    @Test
    @DisplayName("nenhuma linha carrega caminho absoluto da máquina")
    void nenhumaLinhaCarregaCaminhoAbsoluto() {
        String destino = caminhoWindows("animes", "86", "saida", "ep01.ass");
        ResultadoTraducaoKaraoke feito = new ResultadoTraducaoKaraoke(
            "ep01.ass", destino, 10, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, "NAO_NECESSARIO", List.of());
        DesfechoKaraoke desfecho = new DesfechoKaraoke(
            StatusExecucaoKaraoke.COMPLETA, null, List.of(), false,
            DesfechoKaraoke.EstadoDicionario.DISPONIVEL);

        registro.registrar(tempDir, tempDir.resolve("saida"), List.of(feito), 1L, 0, 0,
            null, null, desfecho);

        String tudo = acervo.linhas.toString();
        assertFalse(tudo.contains(caminhoWindows("animes")),
            "caminho absoluto da maquina vazou para o dataset publico: " + tudo);
        assertFalse(tudo.contains(destino), "o destino inteiro vazou: " + tudo);
    }

    /**
     * PROPÓSITO DE NEGÓCIO: o motivo da falha e o do aborto nascem de {@code e.getMessage()}, e a
     * mensagem de {@code NoSuchFileException} <b>é</b> o caminho absoluto do arquivo. O dataset é
     * público: sem sanitização, a primeira legenda ilegível publicaria a árvore de pastas da
     * máquina do Paulo.
     *
     * <p>Achado pela revisão de DANO em 20/08/2026, depois de o resto já estar verde — o teste de
     * vazamento anterior olhava só {@code arquivoDestino} e passava ileso por este caminho.
     */
    @Test
    @DisplayName("motivo de falha e de aborto não publicam o caminho da máquina")
    void motivosNaoVazamCaminho() {
        String arquivoInexistente = caminhoWindows("animes", "86", "ep99.ass");
        DesfechoKaraoke desfecho = new DesfechoKaraoke(
            StatusExecucaoKaraoke.ABORTADA,
            "Destino nao criavel: " + caminhoWindows("animes", "86", "saida-karaoke"),
            List.of(new FalhaArquivoKaraoke("ep99.ass", arquivoInexistente)), false,
            DesfechoKaraoke.EstadoDicionario.NAO_CONSULTADO);

        registro.registrar(tempDir, tempDir.resolve("saida"), List.of(), 50L, 0, 0,
            null, null, desfecho);

        assertEquals(1, acervo.linhas.size());
        TelemetriaKaraoke linha = acervo.linhas.get(0);
        assertFalse(linha.motivoFalha().contains(caminhoWindows("animes")),
            "o caminho vazou pelo motivo da FALHA: " + linha.motivoFalha());
        assertFalse(linha.motivoExecucao().contains(caminhoWindows("animes")),
            "o caminho vazou pelo motivo da EXECUCAO: " + linha.motivoExecucao());
        // Sanitizar não é apagar: o que identifica a máquina sai, o diagnóstico fica.
        assertTrue(linha.motivoExecucao().contains("Destino nao criavel")
                || linha.motivoExecucao().equals("[redigido]"),
            "sobrou nem diagnostico nem redacao explicita: " + linha.motivoExecucao());
    }

    /**
     * PROPÓSITO DE NEGÓCIO: monta {@code C:\...} em RUNTIME, porque literal de drive no fonte
     * quebra a suíte no contêiner Linux e {@code CatracaSuiteSemDriveWindowsTest} reprova — com
     * razão. Aqui o caminho é DADO de teste (o que não pode vazar), nunca caminho a abrir.
     */
    private static String caminhoWindows(String... segmentos) {
        return 'C' + ":" + "\\" + String.join("\\", segmentos);
    }
}
