package org.traducao.projeto.vitrine.arquitetura;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.traducao.projeto.vitrine.application.IndexacaoVitrine;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * PROPÓSITO DE NEGÓCIO: impede que a lista do que o buscador pode e não pode buscar fique para trás
 * do que existe na pasta {@code static}: pedaço de tela novo nasceria aberto ao rastreio, e página
 * completa nova nasceria fora do sitemap, sem ninguém perceber.
 *
 * <p>INVARIANTES DO DOMÍNIO:
 * <ul>
 *   <li>Todo {@code .html} fora de {@code errors/} sem {@code <!DOCTYPE} é fragmento e está em
 *       {@link IndexacaoVitrine#FRAGMENTOS}; e todo item da lista existe no disco.</li>
 *   <li>Todo {@code .html} fora de {@code errors/} com {@code <!DOCTYPE} é página e está em
 *       {@link IndexacaoVitrine#PAGINAS_PUBLICAS} ({@code index.html} responde por {@code /}).</li>
 *   <li>O detector é calibrado na fronteira (A1): os dois casos de controle são {@code .html}, e só
 *       o conteúdo os separa.</li>
 * </ul>
 *
 * <p>COMPORTAMENTO EM CASO DE FALHA: pasta ausente ou sem nenhum {@code .html} reprova com NÃO
 * VERIFICOU — lista vazia nunca vale como "nada fora da lista".
 */
class VitrineIndexacaoArquivosTest {

    private static final Path ESTATICO = Path.of("src", "main", "resources", "static");

    private static boolean ehDocumentoCompleto(Path arquivo) throws IOException {
        String inicio = Files.readString(arquivo, StandardCharsets.UTF_8).stripLeading();
        return inicio.toLowerCase(Locale.ROOT).startsWith("<!doctype html");
    }

    private static String rota(Path arquivo) {
        String relativo = ESTATICO.relativize(arquivo).toString().replace('\\', '/');
        return relativo.equals("index.html") ? "/" : "/" + relativo;
    }

    private static Set<Path> htmlForaDosErros() throws IOException {
        assertTrue(Files.isDirectory(ESTATICO), "NÃO VERIFICOU — pasta ausente: " + ESTATICO.toAbsolutePath());
        Set<Path> r = new TreeSet<>();
        try (Stream<Path> s = Files.walk(ESTATICO)) {
            s.filter(Files::isRegularFile)
                .filter(p -> p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".html"))
                .filter(p -> !ESTATICO.relativize(p).toString().replace('\\', '/').startsWith("errors/"))
                .forEach(r::add);
        }
        assertFalse(r.isEmpty(), "NÃO VERIFICOU — nenhum .html encontrado em " + ESTATICO.toAbsolutePath());
        return r;
    }

    @Test
    @DisplayName("A1: o detector separa fragmento de página pelo conteúdo, não pela extensão")
    void detectorCalibrado() throws IOException {
        assertFalse(ehDocumentoCompleto(ESTATICO.resolve("sobre").resolve("sobre.html")),
            "o fragmento conhecido sobre/sobre.html foi lido como página completa");
        assertTrue(ehDocumentoCompleto(ESTATICO.resolve("index.html")),
            "a página conhecida index.html foi lida como fragmento");
    }

    @Test
    @DisplayName("todo fragmento da pasta está fechado ao rastreio, e a lista não tem entrada morta")
    void fragmentosBatemComALista() throws IOException {
        Set<String> noDisco = new TreeSet<>();
        for (Path p : htmlForaDosErros()) {
            if (!ehDocumentoCompleto(p)) {
                noDisco.add(rota(p));
            }
        }
        assertEquals(new TreeSet<>(IndexacaoVitrine.FRAGMENTOS), noDisco,
            "pedaço de tela fora da lista de bloqueio do robots.txt (ou item da lista sem arquivo)");
    }

    @Test
    @DisplayName("toda página completa da pasta está na lista de páginas públicas (sitemap e canonical)")
    void paginasBatemComALista() throws IOException {
        Set<String> noDisco = new TreeSet<>();
        for (Path p : htmlForaDosErros()) {
            if (ehDocumentoCompleto(p)) {
                noDisco.add(rota(p));
            }
        }
        assertEquals(new TreeSet<>(IndexacaoVitrine.PAGINAS_PUBLICAS), noDisco,
            "página pública fora do sitemap (ou item da lista sem arquivo)");
    }
}
