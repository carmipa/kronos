package org.traducao.projeto.traducao.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.traducao.projeto.cachetraducao.infrastructure.CacheTraducaoService;
import org.traducao.projeto.core.io.DiretorioBaseKronos;
import org.traducao.projeto.legenda.domain.DocumentoLegenda;
import org.traducao.projeto.legenda.infrastructure.EscritorLegendaAss;
import org.traducao.projeto.legenda.infrastructure.EscritorLegendaSrt;
import org.traducao.projeto.legenda.infrastructure.LeitorLegendaAss;
import org.traducao.projeto.legenda.infrastructure.LeitorLegendaSrt;
import org.traducao.projeto.traducao.presentation.ui.ConsoleUILogger;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * PROPÓSITO DE NEGÓCIO: prova a publicação com mescla de três vias DIRETO na classe, sem montar o
 * caso de uso inteiro — que era o motivo de ela ter saído do {@link ProcessarArquivoUseCase}
 * (auditoria de 28/10/2026).
 *
 * <p>INVARIANTES DO DOMÍNIO: base por pasta de saída; reexecução idêntica não regrava; correção
 * feita no arquivo fica e correção feita no cache chega.
 *
 * <p>COMPORTAMENTO EM CASO DE FALHA: tudo em {@code @TempDir}; a raiz operacional (backups) é
 * redirecionada para ela e restaurada no fim.
 */
@DisplayName("publicador com mescla de tres vias")
class PublicadorLegendaMesclaTest {

    private static final String CABECALHO = """
        [Script Info]
        ScriptType: v4.00+

        [V4+ Styles]
        Format: Name, Fontname, Fontsize
        Style: Default,Arial,48

        [Events]
        Format: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text
        """;

    @TempDir
    Path raiz;
    private String baseOriginal;
    private PublicadorLegendaMescla publicador;
    private final LeitorLegendaAss leitor = new LeitorLegendaAss();

    @BeforeEach
    void montar() {
        baseOriginal = System.getProperty(DiretorioBaseKronos.PROPRIEDADE_BASE);
        System.setProperty(DiretorioBaseKronos.PROPRIEDADE_BASE, raiz.toString());
        ConsoleUILogger ui = new ConsoleUILogger();
        publicador = new PublicadorLegendaMescla(leitor, new LeitorLegendaSrt(), new EscritorLegendaAss(),
            new EscritorLegendaSrt(), ui, new PoliticaBackupTraducao(new CacheTraducaoService(new ObjectMapper()), ui));
    }

    @AfterEach
    void restaurar() {
        if (baseOriginal == null) {
            System.clearProperty(DiretorioBaseKronos.PROPRIEDADE_BASE);
        } else {
            System.setProperty(DiretorioBaseKronos.PROPRIEDADE_BASE, baseOriginal);
        }
    }

    private DocumentoLegenda documento(String... falas) throws Exception {
        StringBuilder sb = new StringBuilder(CABECALHO);
        int t = 1;
        for (String fala : falas) {
            sb.append(String.format("Dialogue: 0,0:00:%02d.00,0:00:%02d.00,Default,,0,0,0,,%s%n", t, t + 1, fala));
            t += 2;
        }
        Path tmp = Files.createTempFile(raiz, "gerado", ".ass");
        Files.writeString(tmp, sb.toString(), StandardCharsets.UTF_8);
        return leitor.ler(tmp);
    }

    @Test
    @DisplayName("primeira publicacao grava o arquivo e a base DESTA pasta de saida")
    void primeiraPublicacaoGravaABaseDaPasta() throws Exception {
        Path cache = raiz.resolve("cache").resolve("Obra").resolve("ep.cache.json");
        Path destino = Files.createDirectories(raiz.resolve("saida")).resolve("ep_PT-BR.ass");

        var r = publicador.publicar(destino, documento("Oi.", "Tudo bem?"), false, false, new ArrayList<>(), cache);

        assertNull(r.backup(), "nada a substituir na primeira vez");
        assertTrue(Files.exists(destino));
        Path base = PublicadorLegendaMescla.basePorPastaDeSaida(cache, destino);
        assertTrue(Files.isRegularFile(base), "a base mora em .publicado/<pasta>/: " + base);
        assertTrue(Files.readString(base.getParent().resolve("ORIGEM.txt"), StandardCharsets.UTF_8)
            .contains(destino.getParent().toString()), "ORIGEM.txt diz qual pasta e");
    }

    @Test
    @DisplayName("reexecucao identica nao regrava nem gera backup")
    void reexecucaoIdenticaNaoRegrava() throws Exception {
        Path cache = raiz.resolve("cache").resolve("Obra").resolve("ep.cache.json");
        Path destino = Files.createDirectories(raiz.resolve("saida")).resolve("ep_PT-BR.ass");
        publicador.publicar(destino, documento("Oi.", "Tudo bem?"), false, false, new ArrayList<>(), cache);
        byte[] antes = Files.readAllBytes(destino);

        var r = publicador.publicar(destino, documento("Oi.", "Tudo bem?"), false, false, new ArrayList<>(), cache);

        assertNull(r.backup(), "conteudo identico nao gera backup");
        assertEquals(new String(antes, StandardCharsets.UTF_8), Files.readString(destino, StandardCharsets.UTF_8));
    }

    @Test
    @DisplayName("correcao no arquivo fica, correcao no cache chega, e ha backup")
    void mesclaDeTresVias() throws Exception {
        Path cache = raiz.resolve("cache").resolve("Obra").resolve("ep.cache.json");
        Path destino = Files.createDirectories(raiz.resolve("saida")).resolve("ep_PT-BR.ass");
        publicador.publicar(destino, documento("Oi.", "Tudo bem?"), false, false, new ArrayList<>(), cache);
        Files.writeString(destino, Files.readString(destino, StandardCharsets.UTF_8)
            .replace(",Oi.", ",Olá, corrigido a mão."), StandardCharsets.UTF_8);

        var r = publicador.publicar(destino, documento("Oi.", "Tudo certo?"), false, false, new ArrayList<>(), cache);

        String publicado = Files.readString(destino, StandardCharsets.UTF_8);
        assertTrue(publicado.contains(",Olá, corrigido a mão."), "a correcao feita no arquivo fica:\n" + publicado);
        assertTrue(publicado.contains(",Tudo certo?"), "a correcao vinda do cache chega:\n" + publicado);
        assertEquals(java.util.Set.of(0), r.preservadasDoArquivo(), "a linha mantida e informada");
        assertTrue(r.backup() != null && Files.exists(r.backup()), "toda substituicao tem backup");
    }
}
