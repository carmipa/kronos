package org.traducao.projeto.vitrine.arquitetura;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.traducao.projeto.vitrine.application.PoliticaVitrine;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeSet;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * PROPÓSITO DE NEGÓCIO: garante que o que a vitrine pública entrega a qualquer visitante não
 * carrega o que não pode sair da máquina do Paulo — arquivo estático de tipo inesperado (um
 * dataset, uma configuração, um markdown largado na pasta) e, nos dados de demonstração, caminho
 * do disco, IP, nome de máquina, dono ou chave.
 *
 * <p>INVARIANTES DO DOMÍNIO:
 * <ul>
 *   <li>A pasta {@code static} só tem extensões da lista pública da {@link PoliticaVitrine};
 *       arquivo fora dela reprova o BUILD, além de ser recusado pelo filtro em execução.</li>
 *   <li>O detector de segredo é calibrado na fronteira (A1): reprova o caso doente e aprova o
 *       legítimo que carrega o mesmo formato ("S01E01", "1.5", caminho já encurtado).</li>
 *   <li>Todo dado de demonstração que a política referencia existe e é JSON válido, e há uma
 *       fatia de demonstração para cada fatia listada — senão a tela abre vazia ou quebrada.</li>
 * </ul>
 *
 * <p>COMPORTAMENTO EM CASO DE FALHA: pasta ausente ou vazia reprova com NÃO VERIFICOU — "não
 * achei nada" nunca vale como aprovação quando não havia o que olhar.
 */
class VitrineConteudoPublicoTest {

    private static final Path ESTATICO = Path.of("src", "main", "resources", "static");
    private static final Path DEMONSTRACAO = Path.of("src", "main", "resources", "vitrine", "demonstracao");

    private static final Map<String, Pattern> PROIBIDOS = new LinkedHashMap<>();

    static {
        PROIBIDOS.put("caminho com unidade", Pattern.compile("[A-Za-z]:[\\\\/]"));
        PROIBIDOS.put("pasta de usuário ou acervo", Pattern.compile("(?i)(/home/|/users/|\\\\users\\\\|/acervo\\b|/root/)"));
        PROIBIDOS.put("dono", Pattern.compile("(?i)\\b(paulo|carmipa)\\b"));
        PROIBIDOS.put("IPv4", Pattern.compile("\\b(?:\\d{1,3}\\.){3}\\d{1,3}\\b"));
        PROIBIDOS.put("máquina", Pattern.compile("(?i)desktop-[a-z0-9]{5,}|srv1522378"));
        PROIBIDOS.put("chave", Pattern.compile("(?i)(api[_-]?key|tmdb[_-]?key|bearer\\s+[a-z0-9])"));
    }

    private static List<String> achados(String texto) {
        List<String> r = new ArrayList<>();
        PROIBIDOS.forEach((nome, rx) -> {
            if (rx.matcher(texto).find()) {
                r.add(nome);
            }
        });
        return r;
    }

    @Test
    @DisplayName("A1: o detector reprova o doente e aprova o legítimo do mesmo formato")
    void detectorCalibrado() {
        assertEquals(List.of("caminho com unidade"), achados("Pasta: C:\\animes\\86 | arquivos: 3"));
        assertTrue(achados("Legendas: /acervo/86/traducao_ptbr").contains("pasta de usuário ou acervo"));
        assertTrue(achados("dono: Paulo").contains("dono"));
        assertTrue(achados("host 192.168.0.4").contains("IPv4"));
        assertTrue(achados("DESKTOP-GR98NAM").contains("máquina"));
        assertTrue(achados("tmdb_key=abc").contains("chave"));

        assertEquals(List.of(), achados("Pasta: …\\86 Part 2\\traducao_ptbr | arquivos: 3"));
        assertEquals(List.of(), achados("Mobile.Suit.Gundam.S01E01.ass 1.5x versão 2.1"));
        assertEquals(List.of(), achados("Revisão de Lore: 12 falas, Shin Nouzen, Paulownia"));
        assertEquals(List.of(), achados("tempo 0:00:12.34 | 10.5 s"));
    }

    @Test
    @DisplayName("dados de demonstração: sem caminho, IP, máquina, dono ou chave")
    void demonstracaoSemSegredo() throws IOException {
        List<Path> arquivos = listar(DEMONSTRACAO, ".json");
        Map<String, List<String>> sujos = new LinkedHashMap<>();
        for (Path p : arquivos) {
            List<String> a = achados(Files.readString(p, StandardCharsets.UTF_8));
            if (!a.isEmpty()) {
                sujos.put(p.getFileName().toString(), a);
            }
        }
        assertTrue(sujos.isEmpty(), "dado de demonstração com conteúdo proibido: " + sujos);
    }

    @Test
    @DisplayName("todo dado referenciado existe, é JSON válido, e cada fatia listada tem demonstração")
    void demonstracaoCompleta() throws IOException {
        ObjectMapper om = new ObjectMapper();
        for (String nome : PoliticaVitrine.GET_DEMONSTRACAO.values()) {
            Path p = DEMONSTRACAO.resolve(nome + ".json");
            assertTrue(Files.isRegularFile(p), "falta o dado de demonstração " + p);
            om.readTree(p.toFile());
        }
        JsonNode fatias = om.readTree(DEMONSTRACAO.resolve("telemetria-fatias.json").toFile()).path("fatias");
        assertTrue(fatias.isArray() && fatias.size() > 0, "NÃO VERIFICOU — telemetria-fatias.json sem fatias");
        for (JsonNode f : fatias) {
            String nome = f.path("fatia").asText();
            Path p = DEMONSTRACAO.resolve("fatia-" + nome + ".json");
            assertTrue(Files.isRegularFile(p), "a tela listaria a fatia '" + nome + "' sem dado: " + p);
            om.readTree(p.toFile());
        }
    }

    @Test
    @DisplayName("a pasta static só tem extensões da lista pública")
    void estaticoSoComExtensoesPublicas() throws IOException {
        List<Path> arquivos = listar(ESTATICO, "");
        TreeSet<String> fora = new TreeSet<>();
        for (Path p : arquivos) {
            String nome = p.getFileName().toString();
            int ponto = nome.lastIndexOf('.');
            String ext = ponto <= 0 ? "(sem extensão)" : nome.substring(ponto + 1).toLowerCase(Locale.ROOT);
            if (!PoliticaVitrine.EXTENSOES_ESTATICAS.contains(ext)) {
                fora.add(ESTATICO.relativize(p).toString());
            }
        }
        assertTrue(fora.isEmpty(), "arquivo(s) fora da lista pública da vitrine em static/: " + fora);
    }

    private static List<Path> listar(Path pasta, String sufixo) throws IOException {
        assertTrue(Files.isDirectory(pasta), "NÃO VERIFICOU — pasta ausente: " + pasta.toAbsolutePath());
        List<Path> lista;
        try (Stream<Path> s = Files.walk(pasta)) {
            lista = s.filter(Files::isRegularFile).filter(p -> p.toString().endsWith(sufixo)).toList();
        }
        assertTrue(!lista.isEmpty(), "NÃO VERIFICOU — nenhum arquivo em " + pasta.toAbsolutePath());
        return lista;
    }
}
