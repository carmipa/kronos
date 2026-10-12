package org.traducao.projeto.traducaoKaraoke.infrastructure;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.traducao.projeto.core.util.ProcessoExternoUtil;
import org.traducao.projeto.traducaoKaraoke.domain.FontesDoVideo;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * PROPÓSITO DE NEGÓCIO: o leitor de fontes responde, a partir de um MKV DE VERDADE, o que cada
 * fonte anexada desenha — é a resposta que autoriza trocar a fonte da letra portuguesa.
 *
 * <h2>Os controles</h2>
 * O MKV é montado aqui com o {@code mkvmerge} e duas fontes do Windows: a Arial, que desenha todo o
 * português (controle negativo: nada falta), e a Segoe MDL2 Assets, que só tem ícones (controle
 * positivo: faltam as letras). Sem os dois o instrumento poderia estar respondendo "nada falta"
 * por não enxergar nada.
 *
 * <p>INVARIANTES DO DOMÍNIO: família fora do vídeo responde "não verificado" (vazio), nunca "nada
 * falta"; a pasta temporária some depois da leitura; ferramenta ausente ou vídeo inexistente viram
 * NÃO VERIFICADAS com o motivo.
 *
 * <p>COMPORTAMENTO EM CASO DE FALHA: sem MKVToolNix ou sem as fontes do Windows o teste PULA — é o
 * estado 2, não verificado, e não aprovação.
 */
class FontesEmbutidasMkvAdapterTest {

    /**
     * A pasta de fontes vem do sistema, nunca de letra de drive cravada (CatracaSuiteSemDriveWindows):
     * no contêiner Linux ela não existe e o teste pula como NÃO VERIFICADO.
     */
    private static final Path FONTES_DO_SISTEMA = System.getenv("SystemRoot") == null
        ? Path.of("fontes-do-windows-ausentes") : Path.of(System.getenv("SystemRoot"), "Fonts");
    private static final Path ARIAL = FONTES_DO_SISTEMA.resolve("arial.ttf");
    private static final Path SIMBOLOS = FONTES_DO_SISTEMA.resolve("segmdl2.ttf");

    @TempDir
    Path pasta;

    private FontesEmbutidasMkvAdapter adapter;

    @BeforeEach
    void setUp() {
        adapter = new FontesEmbutidasMkvAdapter();
        adapter.mkvmerge = "mkvmerge";
        adapter.mkvextract = "mkvextract";
    }

    private Path mkvComFontes(Path... fontes) throws Exception {
        Assumptions.assumeTrue(Files.isRegularFile(ARIAL) && Files.isRegularFile(SIMBOLOS),
            "fontes do Windows ausentes — NAO VERIFICADO");
        Path legenda = Files.writeString(pasta.resolve("letra.ass"), String.join("\r\n",
            "[Script Info]", "ScriptType: v4.00+", "", "[Events]",
            "Format: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text",
            "Dialogue: 0,0:00:01.00,0:00:02.00,Default,,0,0,0,,Você", ""), StandardCharsets.UTF_8);
        Path mkv = pasta.resolve("Obra - S01E01.mkv");
        List<String> comando = new java.util.ArrayList<>(List.of("mkvmerge", "-o", mkv.toString()));
        for (Path f : fontes) {
            comando.addAll(List.of("--attachment-mime-type", "font/ttf", "--attach-file", f.toString()));
        }
        comando.add(legenda.toString());
        ProcessoExternoUtil.Resultado r;
        try {
            r = ProcessoExternoUtil.executar(comando, Duration.ofSeconds(60), true);
        } catch (IOException e) {
            Assumptions.abort("mkvmerge ausente — NAO VERIFICADO: " + e.getMessage());
            return null;
        }
        Assumptions.assumeTrue(r.codigoSaida() <= 1 && Files.isRegularFile(mkv),
            "mkvmerge nao montou o MKV de teste — NAO VERIFICADO");
        return mkv;
    }

    @Test
    @DisplayName("Arial anexada: nada falta no português; Segoe MDL2: faltam as letras")
    void respondeOQueCadaFonteDesenha() throws Exception {
        Path mkv = mkvComFontes(ARIAL, SIMBOLOS);

        FontesDoVideo fontes = adapter.lerFontes(mkv);

        assertTrue(fontes.verificadas(), () -> "motivo: " + fontes.motivoNaoVerificado());
        assertEquals("Obra - S01E01.mkv", fontes.origem());
        assertEquals(Optional.of(""), fontes.cobertura().caracteresSemDesenho("Arial", "Você se sente sozinho? Coração, ação!"),
            "a Arial desenha todo o portugues — se faltou algo, o instrumento esta errado");
        Optional<String> simbolos = fontes.cobertura().caracteresSemDesenho("Segoe MDL2 Assets", "Você");
        assertTrue(simbolos.isPresent() && simbolos.get().contains("ê"),
            () -> "controle positivo: a fonte de icones nao desenha letras, e o leitor disse " + simbolos);
        assertEquals(Optional.empty(), fontes.cobertura().caracteresSemDesenho("Gandhi Sans", "Você"),
            "familia fora do video tem de responder NAO VERIFICADO, nunca 'nada falta'");
    }

    @Test
    @DisplayName("MKV sem fonte anexada: lido, e toda família responde não verificado")
    void semFonteAnexada() throws Exception {
        Path mkv = mkvComFontes();

        FontesDoVideo fontes = adapter.lerFontes(mkv);

        assertTrue(fontes.verificadas());
        assertEquals(Optional.empty(), fontes.cobertura().caracteresSemDesenho("Arial", "Você"));
    }

    @Test
    @DisplayName("a pasta temporária das fontes some depois da leitura")
    void pastaTemporariaSome() throws Exception {
        Path mkv = mkvComFontes(ARIAL);
        Path tmp = Path.of(System.getProperty("java.io.tmpdir"));
        List<Path> antes = temporarias(tmp);

        assertTrue(adapter.lerFontes(mkv).verificadas());

        List<Path> depois = temporarias(tmp);
        assertTrue(antes.containsAll(depois), () -> "sobrou pasta temporaria: " + depois);
    }

    @Test
    @DisplayName("vídeo inexistente e ferramenta ausente viram NÃO VERIFICADAS com o motivo")
    void falhasViramNaoVerificadas() {
        FontesDoVideo inexistente = adapter.lerFontes(pasta.resolve("nao-existe.mkv"));
        assertFalse(inexistente.verificadas());
        assertTrue(inexistente.motivoNaoVerificado().startsWith("video inexistente"), inexistente.motivoNaoVerificado());

        adapter.mkvmerge = "mkvmerge-que-nao-existe-kronos";
        FontesDoVideo semFerramenta = adapter.lerFontes(ARIAL);
        assertFalse(semFerramenta.verificadas(), "ferramenta ausente nao pode ser lida como fontes conferidas");
        assertFalse(semFerramenta.motivoNaoVerificado().isBlank());
    }

    private static List<Path> temporarias(Path tmp) throws IOException {
        try (Stream<Path> s = Files.list(tmp)) {
            return s.filter(p -> p.getFileName().toString().startsWith("kronos-fontes-")).toList();
        }
    }
}
