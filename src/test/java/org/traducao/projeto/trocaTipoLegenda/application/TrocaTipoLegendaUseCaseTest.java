package org.traducao.projeto.trocaTipoLegenda.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.traducao.projeto.trocaTipoLegenda.domain.ports.ArmazenamentoBackupPort;
import org.traducao.projeto.trocaTipoLegenda.domain.ports.TelemetriaTrocaPort;
import org.traducao.projeto.trocaTipoLegenda.infrastructure.ArmazenamentoBackupKronosAdapter;
import org.traducao.projeto.trocaTipoLegenda.infrastructure.LegendaIoAssAdapter;
import org.traducao.projeto.core.texto.FonteVietnamitaLegada;
import org.traducao.projeto.legenda.infrastructure.EscritorLegendaAss;
import org.traducao.projeto.legenda.infrastructure.LeitorLegendaAss;
import org.traducao.projeto.trocaTipoLegenda.domain.AuditoriaLegendaResultado;
import org.traducao.projeto.trocaTipoLegenda.domain.EntradaAuditoriaTrocaFonte;
import org.traducao.projeto.trocaTipoLegenda.domain.ResultadoGeralAuditoria;
import org.traducao.projeto.trocaTipoLegenda.domain.ResultadoTrocaFonte;
import org.traducao.projeto.trocaTipoLegenda.infrastructure.TrocaTipoLegendaAuditoriaCache;

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
 * PROPÓSITO DE NEGÓCIO: valida auditoria e substituição de fontes sem acessar
 * backups ou relatórios reais do projeto.
 * <p>INVARIANTES DO DOMÍNIO: todos os artefatos ficam sob diretórios temporários.
 * <p>COMPORTAMENTO EM CASO DE FALHA: qualquer acesso à raiz real reprova os testes.
 */
class TrocaTipoLegendaUseCaseTest {

    private TrocaTipoLegendaUseCase useCase;
    private Path tempDirBackups;

    /**
     * Antes este stub ESTENDIA TelemetriaService e precisava sobrescrever {@code init()}
     * junto com o método que interessava — o teste carregava o ciclo de vida de um serviço
     * de outra fatia só para observar uma chamada. Com TelemetriaTrocaPort ele implementa
     * a assinatura que o caso de uso realmente usa, e nada mais.
     */
    private static class TelemetriaStub implements TelemetriaTrocaPort {
        boolean operacaoRegistrada = false;

        @Override
        public synchronized void registrar(String tipo, String detalhe, long duracaoMs,
                                           int arquivosProcessados, int itensDetectados, int itensCorrigidos) {
            this.operacaoRegistrada = true;
        }
    }

    private static class AuditoriaCacheStub extends TrocaTipoLegendaAuditoriaCache {
        int registros = 0;

        public AuditoriaCacheStub() {
            super(new ObjectMapper());
        }

        @Override
        public synchronized void registrar(EntradaAuditoriaTrocaFonte entrada) {
            registros++;
        }
    }

    private final TelemetriaStub telemetriaStub = new TelemetriaStub();
    private final AuditoriaCacheStub cacheStub = new AuditoriaCacheStub();

    /**
     * PROPÓSITO DE NEGÓCIO: compõe cada teste com backup isolado pelo JUnit.
     * <p>INVARIANTES DO DOMÍNIO: nenhuma sessão usa `Path.of("backups")`.
     * <p>COMPORTAMENTO EM CASO DE FALHA: erro de preparação interrompe o teste.
     */
    @BeforeEach
    void setUp(@TempDir Path tempDir) throws IOException {
        AuditoriaFontesService auditoriaService = new AuditoriaFontesService();

        tempDirBackups = tempDir.resolve("backups");
        Files.createDirectories(tempDirBackups);
        // O isolamento agora vem de passar OUTRO ArmazenamentoBackupPort, e não de injetar
        // um Path que o caso de uso usaria para resolver diretório e copiar por conta própria.
        ArmazenamentoBackupPort backup = new ArmazenamentoBackupKronosAdapter(tempDirBackups);
        useCase = TrocaTipoLegendaUseCase.criarParaTeste(
            new LegendaIoAssAdapter(new LeitorLegendaAss(), new EscritorLegendaAss()),
            auditoriaService, telemetriaStub, cacheStub, backup);
    }

    @Test
    void escanearEIdentificarProblemasNoLote(@TempDir Path tempDir) throws IOException {
        criarLegendaDeTeste(tempDir, "legenda1.ass", ".VnBook-Antiqua");
        criarLegendaDeTeste(tempDir, "legenda2.ass", "Arial");

        ResultadoGeralAuditoria resultado = useCase.escanear(tempDir);

        assertEquals(2, resultado.totalArquivosAnalisados());
        assertEquals(1, resultado.totalComProblemas());
        
        List<AuditoriaLegendaResultado> arquivos = resultado.arquivos();
        assertEquals(2, arquivos.size());

        AuditoriaLegendaResultado arq1 = arquivos.stream()
            .filter(a -> a.arquivo().equals("legenda1.ass"))
            .findFirst().orElseThrow();
        assertTrue(arq1.temProblemas());
        assertEquals("ASS estilizada", arq1.tipoLegenda());
        assertEquals(".VnBook-Antiqua", arq1.fontes().get(1).fonteAtual());

        AuditoriaLegendaResultado arq2 = arquivos.stream()
            .filter(a -> a.arquivo().equals("legenda2.ass"))
            .findFirst().orElseThrow();
        assertFalse(arq2.temProblemas());
    }

    @Test
    void aplicarSubstituicaoComBackupERelatorio(@TempDir Path tempDir) throws IOException {
        criarLegendaDeTeste(tempDir, "legenda1.ass", ".VnBook-Antiqua");
        criarLegendaDeTeste(tempDir, "legenda2.ass", "Arial");

        ResultadoTrocaFonte resultado = useCase.aplicar(tempDir);

        assertEquals(2, resultado.totalAnalisados());
        assertEquals(1, resultado.totalAlterados());
        assertEquals(1, resultado.totalSubstituicoes());
        assertNotNull(resultado.pastaBackup());
        assertNotNull(resultado.caminhoRelatorioJson());

        // Verificar que o arquivo corrigido agora contém a nova fonte (Arial como padrão para animes)
        String conteudoCorrigido = Files.readString(tempDir.resolve("legenda1.ass"), StandardCharsets.UTF_8);
        assertTrue(conteudoCorrigido.contains("Arial"));
        assertFalse(conteudoCorrigido.contains(".VnBook-Antiqua"));

        // Verificar que o backup existe na pasta de backup
        Path pastaBackup = Path.of(resultado.pastaBackup());
        assertTrue(Files.exists(pastaBackup.resolve("legenda1.ass")));
        String conteudoBackup = Files.readString(pastaBackup.resolve("legenda1.ass"), StandardCharsets.UTF_8);
        assertTrue(conteudoBackup.contains(".VnBook-Antiqua"));

        // Verificar telemetria e cache
        assertTrue(telemetriaStub.operacaoRegistrada);
        assertEquals(1, cacheStub.registros);

        Path relatorioMarkdown = Path.of(resultado.caminhoRelatorioJson().replace(".json", ".md"));
        String markdown = Files.readString(relatorioMarkdown, StandardCharsets.UTF_8);
        assertTrue(markdown.contains("# Troca de Fontes ASS"));
        assertTrue(markdown.contains("| Arquivos analisados | 2 |"));
        assertTrue(markdown.contains("Auditoria granular"));
        assertFalse(markdown.contains("Logs de Execução"));
        assertFalse(markdown.contains("[UTC "));
    }

    @Test
    void aplicarSubstituicaoContaCadaEstiloProblematico(@TempDir Path tempDir) throws IOException {
        String cabecalho = "[Script Info]\n" +
            "Title: Test Legend\n" +
            "Script Type: v4.00+\n\n" +
            "[V4+ Styles]\n" +
            "Format: Name, Fontname\n" +
            "Style: Dialogue,.VnBook-Antiqua\n" +
            "Style: Title,.VnBook-Antiqua\n\n" +
            "[Events]\n" +
            "Format: Layer, Start, End, Style, Text\n" +
            "Dialogue: 0,0:00:01.00,0:00:03.00,Dialogue,Olá Mundo\n";
        Files.writeString(tempDir.resolve("legenda-multi.ass"), cabecalho, StandardCharsets.UTF_8);

        ResultadoTrocaFonte resultado = useCase.aplicar(tempDir);

        assertEquals(1, resultado.totalAnalisados());
        assertEquals(1, resultado.totalAlterados());
        assertEquals(2, resultado.totalSubstituicoes());
        assertEquals(2, cacheStub.registros);

        String conteudoCorrigido = Files.readString(tempDir.resolve("legenda-multi.ass"), StandardCharsets.UTF_8);
        assertTrue(conteudoCorrigido.contains("Style: Dialogue,Arial"));
        assertTrue(conteudoCorrigido.contains("Style: Title,Arial"));
        assertFalse(conteudoCorrigido.contains(".VnBook-Antiqua"));
    }

    @Test
    void aplicarForcadoTrocaFonteSemProblemaAutomaticoParaArial(@TempDir Path tempDir) throws IOException {
        criarLegendaDeTeste(tempDir, "legenda-ok.ass", "FrancophilSans");

        ResultadoGeralAuditoria auditoria = useCase.escanear(tempDir);
        assertEquals(0, auditoria.totalComProblemas());
        assertFalse(auditoria.arquivos().get(0).temProblemas());
        assertEquals("ASS estilizada", auditoria.arquivos().get(0).tipoLegenda());

        ResultadoTrocaFonte resultado = useCase.aplicar(tempDir, true);

        assertEquals(1, resultado.totalAnalisados());
        assertEquals(1, resultado.totalAlterados());
        assertEquals(1, resultado.totalSubstituicoes());
        assertEquals(1, cacheStub.registros);

        String conteudoCorrigido = Files.readString(tempDir.resolve("legenda-ok.ass"), StandardCharsets.UTF_8);
        assertTrue(conteudoCorrigido.contains("Style: Dialogue,Arial"));
        assertFalse(conteudoCorrigido.contains("FrancophilSans"));

        Path pastaBackup = Path.of(resultado.pastaBackup());
        String conteudoBackup = Files.readString(pastaBackup.resolve("legenda-ok.ass"), StandardCharsets.UTF_8);
        assertTrue(conteudoBackup.contains("Style: Dialogue,FrancophilSans"));
    }

    /**
     * A lista de nomes exatos da 1.3 deixava passar {@code .VnTime} e {@code VNI-Times} (registrado em
     * 12/10/2026). Com a regra única do kernel, toda variante vietnamita antiga é achada e trocada; a
     * vietnamita moderna em Unicode ({@code VNF-}), com o mesmo sinal "começa com VN", fica (A1).
     */
    @Test
    void variantesVietnamitasForaDaListaAntigaTambemSaoTrocadas(@TempDir Path tempDir) throws IOException {
        criarLegendaDeTeste(tempDir, "vntime.ass", ".VnTime");
        criarLegendaDeTeste(tempDir, "vni.ass", "VNI-Times");
        criarLegendaDeTeste(tempDir, "vnf.ass", "VNF-Futura");

        assertEquals(2, useCase.escanear(tempDir).totalComProblemas());
        ResultadoTrocaFonte resultado = useCase.aplicar(tempDir);

        assertEquals(2, resultado.totalAlterados());
        assertTrue(Files.readString(tempDir.resolve("vntime.ass"), StandardCharsets.UTF_8).contains("Style: Dialogue,Arial"));
        assertTrue(Files.readString(tempDir.resolve("vni.ass"), StandardCharsets.UTF_8).contains("Style: Dialogue,Arial"));
        assertTrue(Files.readString(tempDir.resolve("vnf.ass"), StandardCharsets.UTF_8).contains("Style: Dialogue,VNF-Futura"));
    }

    /**
     * {@code \fn} de fonte vietnamita antiga dentro da FALA não está no cabeçalho, e a 1.3 só trocava o
     * cabeçalho — a reprovação da 4.1 e do remux pediria uma troca que a ferramenta não fazia. Agora a
     * fala ganha {@code \fnArial}; o {@code \fn} de fonte comum fica. Depois da troca, a regra que
     * reprova não acha mais nada no arquivo.
     */
    @Test
    void fnVietnamitaDentroDaFalaTambemETrocado(@TempDir Path tempDir) throws IOException {
        String conteudo = "[Script Info]\nTitle: Test Legend\n\n[V4+ Styles]\nFormat: Name, Fontname\n"
            + "Style: Dialogue,Arial\n\n[Events]\nFormat: Layer, Start, End, Style, Text\n"
            + "Dialogue: 0,0:00:01.00,0:00:03.00,Dialogue,{\\fn.VnTime\\fs40}Atração\n"
            + "Dialogue: 0,0:00:04.00,0:00:05.00,Dialogue,{\\fnVerdana}Comum\n";
        Files.writeString(tempDir.resolve("inline.ass"), conteudo, StandardCharsets.UTF_8);

        assertEquals(1, useCase.escanear(tempDir).totalComProblemas());
        ResultadoTrocaFonte resultado = useCase.aplicar(tempDir);

        assertEquals(1, resultado.totalAlterados());
        String corrigido = Files.readString(tempDir.resolve("inline.ass"), StandardCharsets.UTF_8);
        assertTrue(corrigido.contains("{\\fnArial\\fs40}Atração"), corrigido);
        assertTrue(corrigido.contains("{\\fnVerdana}Comum"), corrigido);
        assertTrue(FonteVietnamitaLegada.achadosNoAss(corrigido).isEmpty(), "a 1.3 resolve o que a reprovacao cobra");
    }

    private void criarLegendaDeTeste(Path pasta, String nome, String fonte) throws IOException {
        String cabecalho = "[Script Info]\n" +
            "Title: Test Legend\n" +
            "Script Type: v4.00+\n\n" +
            "[V4+ Styles]\n" +
            "Format: Name, Fontname\n" +
            "Style: Default,Arial\n" +
            "Style: Dialogue," + fonte + "\n\n" +
            "[Events]\n" +
            "Format: Layer, Start, End, Style, Text\n" +
            "Dialogue: 0,0:00:01.00,0:00:03.00,Dialogue,Olá Mundo\n";

        Files.writeString(pasta.resolve(nome), cabecalho, StandardCharsets.UTF_8);
    }
}
