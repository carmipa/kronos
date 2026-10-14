package org.traducao.projeto.arquitetura;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * PROPÓSITO DE NEGÓCIO: a página {@code docs/ref-esqueleto-projeto.md} desenha a árvore completa
 * do código — todo pacote, toda pasta e o nome de todas as classes. Esta catraca é ao mesmo tempo
 * o <b>gerador</b> dessa página e a guarda que reprova o build quando o desenho deixa de bater com
 * o disco.
 *
 * <h2>Por que gerador e guarda na mesma classe</h2>
 * Documento que descreve estrutura envelhece <b>em silêncio</b>: ninguém percebe que ele mente,
 * porque nada quebra. A alternativa — escrever a árvore à mão e confiar — já falhou neste
 * repositório em coisa menor: em 19/08/2026 a documentação afirmava 577 classes onde havia 471, e
 * 72 lores onde havia 69. Um gerador que ninguém roda tem o mesmo destino de uma guarda que
 * ninguém invoca; aqui rodar a suíte já confere, e regravar é uma flag.
 *
 * <h2>Como regravar depois de mexer no código</h2>
 * <pre>
 *   gradlew test --tests "*CatracaEsqueletoDoProjetoAtualizadoTest*" -Dkronos.esqueleto.regravar=true
 * </pre>
 * Com a flag, a página é reescrita a partir do disco e o teste passa. Sem a flag, ele compara.
 *
 * <h2>Invariantes do domínio</h2>
 * <ul>
 *   <li>A página é <b>inteiramente</b> gerada: comparar conteúdo completo, não amostra. Comparação
 *       por amostra deixaria classe nova entrar sem aparecer.</li>
 *   <li>A saída é <b>determinística</b> — ordenação alfabética estável e nenhum identificador
 *       derivado de {@code hashCode}. Saída instável faria a página mudar sozinha e transformaria
 *       o diff em ruído (a primeira versão deste gerador, em Python, errou exatamente nisso).</li>
 *   <li>Alvo vazio <b>reprova</b>: menos de 100 classes em {@code src/main} significa que a
 *       varredura não achou o código, e isso não é aprovação.</li>
 * </ul>
 *
 * <h2>Comportamento em caso de falha</h2>
 * Reprova nomeando a primeira linha divergente, com o que está na página e o que está no disco,
 * mais o comando de regravação. Nunca reescreve o arquivo sem a flag.
 */
class CatracaEsqueletoDoProjetoAtualizadoTest {

    private static final Path PAGINA = Path.of("docs", "ref-esqueleto-projeto.md");
    private static final Path RAIZ_MAIN = Path.of("src", "main", "java", "org", "traducao", "projeto");
    private static final Path RAIZ_TEST = Path.of("src", "test", "java", "org", "traducao", "projeto");
    private static final String FLAG_REGRAVAR = "kronos.esqueleto.regravar";

    /** Nó das classes que ficam direto em {@code org.traducao.projeto}, sem subpacote. */
    private static final String RAIZ_DO_PACOTE = "(raiz do pacote)";

    private static final Set<String> PEERS =
        Set.of("legenda", "cachetraducao", "lore", "qualidadeTraducao", "llm");
    private static final Set<String> INFRA = Set.of("core", "config");

    /** Os grupos do menu, na ordem do pipeline. Pacote fora deles cai em "sem grupo declarado". */
    private static final List<Map.Entry<String, List<String>>> GRUPOS = List.of(
        Map.entry("Preparação",
            List.of("analisadorMidia", "legendasExtracao", "trocaTipoLegenda", "auditorConteudoLegendas")),
        Map.entry("Tradução",
            List.of("traducao", "traducaoCorrige", "raspagemCorrecao", "correcaoLegendas")),
        Map.entry("Qualidade",
            List.of("raspagemRevisao", "revisaoLore", "revisaoConcordancia")),
        Map.entry("Karaokê",
            List.of("traducaoKaraoke", "novoKaraoke")),
        Map.entry("Finalização",
            List.of("remuxer", "renomearArquivos")),
        Map.entry("Sistema",
            List.of("telemetria", "mapaProjeto", "apiDadosAnime", "mcp", "sistema", "vitrine")));

    @Test
    @DisplayName("o esqueleto desenhado na documentacao bate com o codigo em disco")
    void esqueletoBateComODisco() throws IOException {
        Map<String, Map<String, List<String>>> principal = varrer(RAIZ_MAIN);
        Map<String, Map<String, List<String>>> testes = varrer(RAIZ_TEST);

        int classesMain = principal.values().stream().mapToInt(CatracaEsqueletoDoProjetoAtualizadoTest::conta).sum();
        assertTrue(classesMain >= 100,
            "varredura achou " + classesMain + " classe(s) em src/main — alvo vazio ou quase vazio "
                + "NAO e aprovacao: a catraca estaria cega. Confira a raiz " + RAIZ_MAIN);

        String esperado = gerar(principal, testes);

        if (Boolean.getBoolean(FLAG_REGRAVAR)) {
            Files.writeString(PAGINA, esperado, StandardCharsets.UTF_8);
            System.out.println("[esqueleto] pagina regravada a partir do disco: " + PAGINA);
            return;
        }

        assertTrue(Files.exists(PAGINA), () -> "a pagina " + PAGINA + " nao existe. Gere com:"
            + System.lineSeparator() + comandoDeRegravacao());

        String atual = Files.readString(PAGINA, StandardCharsets.UTF_8);
        if (atual.equals(esperado)) {
            return;
        }

        List<String> linhasAtuais = atual.lines().toList();
        List<String> linhasEsperadas = esperado.lines().toList();
        int i = 0;
        while (i < linhasAtuais.size() && i < linhasEsperadas.size()
            && linhasAtuais.get(i).equals(linhasEsperadas.get(i))) {
            i++;
        }
        String naPagina = i < linhasAtuais.size() ? linhasAtuais.get(i) : "(a pagina acabou aqui)";
        String noDisco = i < linhasEsperadas.size() ? linhasEsperadas.get(i) : "(o codigo acabou aqui)";
        int linha = i + 1;

        // fail() e nao assertEquals(): comparar dois textos de 1.500 linhas faz o JUnit despejar
        // os DOIS inteiros no relatorio — medido: 114 KB de ruido em volta da unica linha que
        // interessa. Guarda com saida ilegivel e guarda que se aprende a ignorar.
        fail("""
            O ESQUELETO DA DOCUMENTACAO NAO BATE MAIS COM O CODIGO.

            Alguem criou, apagou, moveu ou renomeou classe e a pagina ficou para tras. Ela e
            gerada do disco de proposito — nao conserte editando o markdown a mao.

            Primeira divergencia na linha %d:
              na pagina : %s
              no disco  : %s

            Regrave com:
            %s""".formatted(linha, naPagina, noDisco, comandoDeRegravacao()));
    }

    @Test
    @DisplayName("a varredura nao perde classe pelo caminho — duas contagens independentes batem")
    void varreduraNaoPerdeClasse() throws IOException {
        for (Path raiz : List.of(RAIZ_MAIN, RAIZ_TEST)) {
            long noDisco;
            try (Stream<Path> caminhos = Files.walk(raiz)) {
                noDisco = caminhos
                    .filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().endsWith(".java"))
                    .count();
            }
            int naArvore = varrer(raiz).values().stream()
                .mapToInt(CatracaEsqueletoDoProjetoAtualizadoTest::conta).sum();

            assertEquals(noDisco, naArvore, () -> """
                A VARREDURA DO ESQUELETO PERDEU CLASSE EM %s.

                Duas contagens independentes do MESMO alvo divergiram: %d arquivos .java no disco
                contra %d desenhados na arvore. Foi assim que a primeira versao desta catraca
                sumiu, sem dizer nada, com as 4 classes que moram na raiz do pacote — entre elas a
                WebInterfaceTest, que a documentacao cita pelo nome.

                Guarda que descarta o que nao entende aprova por cegueira.""".formatted(
                raiz, noDisco, naArvore));
        }
    }

    private static String comandoDeRegravacao() {
        return "  gradlew test --tests \"*CatracaEsqueletoDoProjetoAtualizadoTest*\" -D"
            + FLAG_REGRAVAR + "=true";
    }

    // ------------------------------------------------------------------ varredura

    /** {pacote de topo: {caminho relativo da pasta: classes ordenadas}}. */
    private static Map<String, Map<String, List<String>>> varrer(Path raiz) throws IOException {
        Map<String, Map<String, List<String>>> arvore = new TreeMap<>();
        if (!Files.isDirectory(raiz)) {
            return arvore;
        }
        try (Stream<Path> caminhos = Files.walk(raiz)) {
            for (Path dir : caminhos.filter(Files::isDirectory).toList()) {
                List<String> classes;
                try (Stream<Path> arquivos = Files.list(dir)) {
                    classes = arquivos
                        .filter(Files::isRegularFile)
                        .map(p -> p.getFileName().toString())
                        .filter(n -> n.endsWith(".java"))
                        .map(n -> n.substring(0, n.length() - 5))
                        .sorted()
                        .toList();
                }
                if (classes.isEmpty()) {
                    continue;
                }
                String rel = raiz.relativize(dir).toString().replace('\\', '/');
                // Classe na RAIZ do pacote (sem subpacote) entra num nó próprio em vez de ser
                // descartada. A primeira versão pulava este caso e sumia com 4 classes de teste
                // — entre elas a WebInterfaceTest, que a documentação cita pelo nome. Guarda que
                // descarta o que não entende aprova por cegueira.
                String topo = rel.isEmpty() ? RAIZ_DO_PACOTE : rel.split("/")[0];
                String dentro = rel.contains("/") ? rel.substring(rel.indexOf('/') + 1) : "";
                arvore.computeIfAbsent(topo, k -> new TreeMap<>()).put(dentro, classes);
            }
        }
        return arvore;
    }

    private static int conta(Map<String, List<String>> pastas) {
        return pastas.values().stream().mapToInt(List::size).sum();
    }

    private static String categoria(String pacote) {
        if (PEERS.contains(pacote)) {
            return "peer";
        }
        return INFRA.contains(pacote) ? "infra" : "fatia";
    }

    // ------------------------------------------------------------------ desenho

    /** Nó da árvore aninhada: subpastas em ordem e classes daquele nível. */
    private record No(Map<String, No> pastas, List<String> classes) {
        No() {
            this(new TreeMap<>(), new ArrayList<>());
        }
    }

    /**
     * Aninha os caminhos planos ({@code infrastructure/http}) numa árvore de verdade. Sem isto,
     * {@code exception/} e {@code exception/web/} sairiam no MESMO nível e o desenho deixaria de
     * mostrar a hierarquia — que é a única coisa que esta página existe para mostrar.
     */
    private static No aninhar(Map<String, List<String>> pastas) {
        No raiz = new No();
        for (Map.Entry<String, List<String>> e : pastas.entrySet()) {
            No no = raiz;
            if (!e.getKey().isEmpty()) {
                for (String parte : e.getKey().split("/")) {
                    no = no.pastas().computeIfAbsent(parte, k -> new No());
                }
            }
            no.classes().addAll(e.getValue());
            no.classes().sort(Comparator.naturalOrder());
        }
        return raiz;
    }

    private static void desenharNo(No no, String prefixo, List<String> linhas) {
        List<String> nomesPastas = new ArrayList<>(no.pastas().keySet());
        int total = nomesPastas.size() + no.classes().size();
        int i = 0;
        for (String pasta : nomesPastas) {
            boolean ultimo = (++i == total);
            linhas.add(prefixo + (ultimo ? "└── " : "├── ") + pasta + "/");
            desenharNo(no.pastas().get(pasta), prefixo + (ultimo ? "    " : "│   "), linhas);
        }
        for (String classe : no.classes()) {
            boolean ultimo = (++i == total);
            linhas.add(prefixo + (ultimo ? "└── " : "├── ") + classe + ".java");
        }
    }

    private static List<String> desenhar(String pacote, Map<String, List<String>> pastas) {
        List<String> linhas = new ArrayList<>();
        linhas.add(pacote + "/");
        desenharNo(aninhar(pastas), "", linhas);
        return linhas;
    }

    private static void bloco(List<String> saida, String pacote, Map<String, List<String>> pastas) {
        saida.add("#### `" + pacote + "` — " + conta(pastas) + " classes");
        saida.add("");
        saida.add("```text");
        saida.addAll(desenhar(pacote, pastas));
        saida.add("```");
        saida.add("");
    }

    // ------------------------------------------------------------------ pagina

    private static String gerar(Map<String, Map<String, List<String>>> principal,
                                Map<String, Map<String, List<String>>> testes) {
        int totalMain = principal.values().stream().mapToInt(CatracaEsqueletoDoProjetoAtualizadoTest::conta).sum();
        int totalTest = testes.values().stream().mapToInt(CatracaEsqueletoDoProjetoAtualizadoTest::conta).sum();
        List<String> fatias = principal.keySet().stream().filter(p -> categoria(p).equals("fatia")).toList();
        List<String> peers = principal.keySet().stream().filter(p -> categoria(p).equals("peer")).toList();
        List<String> infra = principal.keySet().stream().filter(p -> categoria(p).equals("infra")).toList();

        List<String> out = new ArrayList<>();
        out.add("# 🧬 Esqueleto do Projeto");
        out.add("");
        out.add("[← Catracas & Fronteiras](catracas-e-fronteiras.md) | [Arquitetura →](arquitetura.md)");
        out.add("");
        out.add("---");
        out.add("");
        out.add("## O que esta página é");
        out.add("");
        out.add("A árvore **completa** do código: todo pacote, toda pasta e o nome de **todas as classes**.");
        out.add("");
        out.add("> **Ela é gerada do disco, nunca digitada.** A catraca");
        out.add("> `CatracaEsqueletoDoProjetoAtualizadoTest` é o gerador **e** a guarda: se alguém criar,");
        out.add("> apagar, mover ou renomear uma classe sem regravar esta página, o build reprova apontando");
        out.add("> a primeira linha divergente. Documento que descreve estrutura envelhece em silêncio —");
        out.add("> nada quebra quando ele mente, e por isso ele precisa de teste, não de confiança.");
        out.add("");
        out.add("Para regravar depois de mexer no código:");
        out.add("");
        out.add("```shell");
        out.add("gradlew test --tests \"*CatracaEsqueletoDoProjetoAtualizadoTest*\" -Dkronos.esqueleto.regravar=true");
        out.add("```");
        out.add("");
        out.add("| | quantidade |");
        out.add("|---|---:|");
        out.add("| pacotes de topo | **" + principal.size() + "** |");
        out.add("| fatias funcionais | **" + fatias.size() + "** |");
        out.add("| peers | **" + peers.size() + "** |");
        out.add("| infra transversal | **" + infra.size() + "** |");
        out.add("| classes em `src/main` | **" + totalMain + "** |");
        out.add("| classes em `src/test` | **" + totalTest + "** |");
        out.add("");
        out.add("---");
        out.add("");
        out.add("## Mapa de um olhar");
        out.add("");
        out.add("```mermaid");
        out.add("graph TB");
        for (int g = 0; g < GRUPOS.size(); g++) {
            Map.Entry<String, List<String>> grupo = GRUPOS.get(g);
            List<String> presentes = grupo.getValue().stream().filter(principal::containsKey).toList();
            if (presentes.isEmpty()) {
                continue;
            }
            // Um pacote por LINHA, nao separados por " · ": com os oito rotulos numa linha so, o
            // mermaid espreme o desenho inteiro na largura do painel — medido em 19/08/2026, o SVG
            // saiu com 796x67 px e caixas de 13 px de altura, ilegivel. Rotulo alto e estreito
            // deixa o layout respirar.
            String rotulo = String.join("<br/>", presentes.stream()
                .map(p -> p + " (" + conta(principal.get(p)) + ")").toList());
            out.add("    G" + g + "[\"<b>" + grupo.getKey() + "</b><br/>" + rotulo + "\"]:::fatia");
        }
        out.add("    PEERS[\"<b>🧱 peers</b><br/><i>importáveis por qualquer fatia</i><br/>"
            + String.join("<br/>", peers.stream().map(p -> p + " (" + conta(principal.get(p)) + ")").toList())
            + "\"]:::peer");
        out.add("    BASE[\"<b>⚙️ infra transversal</b><br/>"
            + String.join("<br/>", infra.stream().map(p -> p + " (" + conta(principal.get(p)) + ")").toList())
            + "\"]:::base");
        for (int g = 0; g < GRUPOS.size(); g++) {
            if (GRUPOS.get(g).getValue().stream().anyMatch(principal::containsKey)) {
                out.add("    G" + g + " --> PEERS");
            }
        }
        out.add("    PEERS --> BASE");
        out.add("    classDef fatia fill:#312e81,stroke:#818CF8,color:#F9FAFB");
        out.add("    classDef peer fill:#14532d,stroke:#4ADE80,color:#F9FAFB");
        out.add("    classDef base fill:#1e293b,stroke:#6B7280,color:#F9FAFB");
        out.add("```");
        out.add("");
        out.add("---");
        out.add("");
        out.add("## Fatias funcionais");
        out.add("");
        out.add("Uma etapa do pipeline cada. **Fatia não importa fatia** — quem prova são os");
        out.add("`Fronteira*ArchTest` ([Catracas & Fronteiras](catracas-e-fronteiras.md)).");
        out.add("");
        List<String> jaDesenhadas = new ArrayList<>();
        for (Map.Entry<String, List<String>> grupo : GRUPOS) {
            List<String> presentes = grupo.getValue().stream()
                .filter(principal::containsKey)
                .filter(p -> categoria(p).equals("fatia"))
                .toList();
            if (presentes.isEmpty()) {
                continue;
            }
            out.add("### " + grupo.getKey());
            out.add("");
            for (String p : presentes) {
                bloco(out, p, principal.get(p));
                jaDesenhadas.add(p);
            }
        }
        List<String> semGrupo = fatias.stream().filter(p -> !jaDesenhadas.contains(p)).toList();
        if (!semGrupo.isEmpty()) {
            out.add("### Sem grupo declarado no menu");
            out.add("");
            for (String p : semGrupo) {
                bloco(out, p, principal.get(p));
            }
        }

        out.add("---");
        out.add("");
        out.add("## Peers — importáveis por qualquer fatia");
        out.add("");
        out.add("Superfície pública **congelada por tipo exato**: cada peer tem o próprio");
        out.add("`Fronteira<Peer>ArchTest`, e um tipo novo cruzando a fronteira reprova o build.");
        out.add("");
        for (String p : peers) {
            bloco(out, p, principal.get(p));
        }

        out.add("---");
        out.add("");
        out.add("## Infra transversal");
        out.add("");
        out.add("`core` é proibido, por regra permanente, de depender de qualquer fatia funcional.");
        out.add("");
        for (String p : infra) {
            bloco(out, p, principal.get(p));
        }

        out.add("---");
        out.add("");
        out.add("## Testes — " + totalTest + " classes");
        out.add("");
        out.add("O teste pesa quase tanto quanto o código. As `Catraca*` e `Fronteira*` moram aqui.");
        out.add("");
        for (String p : testes.keySet()) {
            bloco(out, p, testes.get(p));
        }

        out.add("---");
        out.add("");
        out.add("## Navegação");
        out.add("");
        out.add("| Anterior | Próximo |");
        out.add("|----------|---------|");
        out.add("| [← Catracas & Fronteiras](catracas-e-fronteiras.md) | [Arquitetura →](arquitetura.md) |");
        out.add("");
        return String.join("\n", out);
    }
}
