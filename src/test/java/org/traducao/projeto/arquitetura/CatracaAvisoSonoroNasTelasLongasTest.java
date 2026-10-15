package org.traducao.projeto.arquitetura;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;
import java.util.regex.Matcher;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * PROPÓSITO DE NEGÓCIO: toda tela que ESPERA a fila do pipeline terminar precisa avisar quando
 * o trabalho acaba — e pelo módulo COMPARTILHADO, nunca por uma segunda cópia do áudio.
 *
 * <h2>O prejuízo que originou (18/08/2026)</h2>
 * A tela 3.3 (Revisão de Concordância) nasceu <b>muda</b>. As irmãs da mesma etapa avisavam: a
 * Tradução Local desde 14/08 e a Revisão de Lore desde 17/08, ambas pelo
 * {@code js/avisoSonoro.js}. A 3.3 chegou depois, bloqueava o botão esperando a fila igual às
 * outras — e terminava em silêncio. Ninguém percebeu até Paulo pedir o som, o que é a definição
 * de falha silenciosa: o defeito não aparece na tela, aparece na ausência dela.
 *
 * <p>Nenhum compilador liga um arquivo {@code .js} ao outro, e nenhum teste de Java olhava para
 * lá. Por isso a regra vira catraca: {@code CLAUDE.md} e combinado verbal não impedem a quarta
 * tela de nascer muda; um teste vermelho impede.
 *
 * <h2>O critério, e por que é este</h2>
 * O alvo é quem consulta {@code /api/pipeline/status} — o polling que existe justamente para
 * segurar o botão até o job REAL terminar. Quem faz isso admite, no próprio código, que o
 * trabalho demora o bastante para o operador sair de perto. Filtrar por nome de arquivo ou por
 * número de menu seria adivinhação; a espera pela fila é o fato.
 *
 * <h2>Invariantes do domínio</h2>
 * <ul>
 *   <li>Tela que espera a fila importa {@code avisoSonoro.js} e chama {@code tocarAvisoSonoro},
 *       ou está na LINHA DE BASE nominal abaixo, com o motivo escrito.</li>
 *   <li>Ninguém, fora do módulo compartilhado, cria {@code AudioContext} — cópia do áudio é o
 *       que a invariante 10 do projeto proíbe, e duas cópias divergem em silêncio.</li>
 *   <li><b>Alvo vazio é NÃO VERIFICADO, nunca aprovação:</b> se a varredura não encontrar
 *       nenhuma tela que espera a fila, o instrumento reprova em vez de devolver verde.</li>
 *   <li>A linha de base é CATRACA: só desce. Tela nova entra avisando, não entra na lista.</li>
 * </ul>
 *
 * <h2>Comportamento em caso de falha</h2>
 * Reprova nomeando o arquivo exato. Se a pasta {@code static} não existir, reprova como não
 * verificado — "não achei nada" e "não tinha como achar" não podem dar o mesmo sinal.
 */
class CatracaAvisoSonoroNasTelasLongasTest {

    private static final Path RAIZ_ESTATICOS = Path.of("src", "main", "resources", "static");

    /** A espera pela fila: quem faz isto admite que o trabalho demora. */
    private static final String ESPERA_A_FILA = "/api/pipeline/status";

    private static final String MODULO_COMPARTILHADO = "avisoSonoro.js";
    private static final String CHAMADA_DO_AVISO = "tocarAvisoSonoro(";

    /**
     * LINHA DE BASE — dívida herdada, declarada e congelada. Só desce.
     *
     * <p>{@code correcao/correcao.js} espera a fila e não avisa. Ficou de fora do escopo do dia
     * (Paulo pediu o som na 3.3, e ampliar por conta própria é o que a regra do Plano-Mestre
     * proíbe), mas fica VISÍVEL aqui em vez de virar lacuna silenciosa. Quando alguém tocar
     * naquela tela, o som entra junto e esta linha sai.
     */
    private static final Set<String> SEM_AVISO_LINHA_DE_BASE = Set.of(
        "correcao/correcao.js");

    /**
     * LINHA DE BASE do critério novo — as telas que DISPARAM trabalho de fila e ainda não avisam.
     *
     * <p>Ela é maior que a de cima de propósito: o critério antigo ("quem consulta a fila") só
     * enxergava três telas, e por isso a 3.1 ficou muda sem ninguém ver. Trocado o critério, o
     * tamanho real da dívida apareceu — e aparecer é o ponto. Cada uma sai daqui quando alguém
     * tocar naquela tela, e a catraca garante que nenhuma NOVA entre.
     *
     * <p>Paulo pediu o aviso na 3.1 em 24/08/2026; ampliar por conta própria para as outras seis
     * é o que a regra do Plano-Mestre proíbe. Lacuna conhecida é permitida; silenciosa não.
     */
    private static final Set<String> TELAS_DE_FILA_AINDA_MUDAS = new TreeSet<>(Set.of(
        "analise/analise.js",
        "correcao/correcao.js",
        "cura/cura.js",
        "extracao/extracao.js",
        // "remuxer/remuxer.js" SAIU em 2026-09-02 — a catraca so desce. Paulo pediu o som na
        // 5.1, e ela era a tela onde a ausencia doia mais: o lote e de horas, com um MKV de
        // gigabytes por episodio, e terminava em silencio absoluto.
        // "traducaoSemLore/traducaoSemLore.js" SAIU em 2026-10-15: a 2.2 nao escutava o fim do
        // lote que o servidor ja anunciava no canal "traducao-sem-lore" (so a 2.1 escutava). Paulo
        // autorizou corrigir os achados da revisao das telas; ganhou toast, linha e a segunda via do som.
        "trocaTipoLegenda/trocaTipoLegenda.js"));

    @Test
    @DisplayName("toda tela que espera a fila avisa pelo modulo compartilhado (ou esta na linha de base)")
    void telaQueEsperaAFilaTemDeAvisar() throws IOException {
        assertTrue(Files.isDirectory(RAIZ_ESTATICOS),
            "NAO VERIFICADO: " + RAIZ_ESTATICOS.toAbsolutePath() + " nao existe — a catraca nao pode aprovar por cegueira");

        List<Path> telasQueEsperam = telasQueEsperamAFila();
        assertFalse(telasQueEsperam.isEmpty(),
            "NAO VERIFICADO: nenhuma tela consultando " + ESPERA_A_FILA + " foi encontrada. "
                + "Ou o polling mudou de forma, ou a varredura esta cega — os dois exigem olhar, nao verde.");

        Set<String> mudas = new TreeSet<>();
        for (Path tela : telasQueEsperam) {
            String conteudo = Files.readString(tela, StandardCharsets.UTF_8);
            boolean avisa = conteudo.contains(MODULO_COMPARTILHADO) && conteudo.contains(CHAMADA_DO_AVISO);
            String relativo = relativo(tela);
            if (!avisa && !SEM_AVISO_LINHA_DE_BASE.contains(relativo)) {
                mudas.add(relativo);
            }
        }

        assertTrue(mudas.isEmpty(),
            "tela(s) que esperam a fila e terminam em SILENCIO: " + mudas
                + ". Importe " + MODULO_COMPARTILHADO + " e chame " + CHAMADA_DO_AVISO
                + " depois da conclusao — ou justifique na linha de base desta catraca.");
    }

    /**
     * O FURO QUE ESTA CATRACA TINHA, e ele so apareceu quando Paulo reclamou (24/08/2026).
     *
     * <p>O critério acima é "quem CONSULTA a fila". Ele nasceu de uma observação verdadeira —
     * quem espera a fila admite que o trabalho demora — mas deixa passar o caso oposto e pior:
     * a tela que <b>dispara e esquece</b>. A 3.1 Revisão de Legendas fazia o POST, escrevia
     * "Processamento iniciado no servidor!" e calava para sempre. Numa revisão de 50 episódios
     * o operador sai de perto e volta sem saber se acabou, se falhou ou se ainda roda — os três
     * estados com a mesma cara. E como ela não consultava a fila, a catraca dizia que estava tudo
     * bem.
     *
     * <p>Este teste troca o critério por um que teria pego: <b>quem posta para um endpoint que
     * ENFILEIRA</b> tem de esperar e avisar. A pergunta deixa de ser "esta tela espera?" e passa a
     * ser "o trabalho desta tela roda em segundo plano?", que é o fato de verdade.
     *
     * <p>O lado do servidor é a fonte: um controller enfileira quando usa
     * {@code filaExecucao.submeter} ou {@code pipelineWebSupport.submeterJob}. A 3.1 escapava
     * também por aqui — ela usa o segundo, e a busca ingênua pelo primeiro não a via.
     */
    @Test
    @DisplayName("toda tela cujo endpoint ENFILEIRA espera e avisa — o furo que deixou a 3.1 muda")
    void telaComEndpointQueEnfileiraEsperaEAvisa() throws IOException {
        Set<String> rotas = rotasQueEnfileiram();
        assertFalse(rotas.isEmpty(),
            "NAO VERIFICADO: nenhuma rota que enfileira foi encontrada. Ou a forma de enfileirar "
                + "mudou, ou a varredura esta cega — os dois exigem olhar, nao verde.");

        Set<String> faltando = new TreeSet<>();
        try (Stream<Path> caminhos = Files.walk(RAIZ_ESTATICOS)) {
            for (Path tela : caminhos.filter(p -> p.toString().endsWith(".js")).toList()) {
                String js = Files.readString(tela, StandardCharsets.UTF_8);
                boolean postaParaFila = rotas.stream().anyMatch(js::contains);
                if (!postaParaFila) {
                    continue;
                }
                String relativo = relativo(tela);
                // NAO reutiliza a linha de base do criterio antigo: este teste tem a propria, e
                // duas listas para a mesma divida divergem em silencio — foi o que aconteceu com
                // o cartao do alvo em 19/08. Uma lista so, e ela e a de baixo.
                // Exige o AVISO, nao o polling. Como a tela descobre que terminou e problema
                // dela — a Traducao, por exemplo, sabe pelo fluxo de log e nunca consultou a
                // fila. O que o operador precisa e o mesmo nos dois casos: um som quando acaba.
                if (!(js.contains(MODULO_COMPARTILHADO) && js.contains(CHAMADA_DO_AVISO))) {
                    faltando.add(relativo);
                }
            }
        }
        assertEquals(TELAS_DE_FILA_AINDA_MUDAS, faltando,
            "a linha de base das telas de fila nao bate com a realidade. Se uma ganhou o aviso, "
                + "RETIRE-A da lista — a catraca so desce. Se uma nova nasceu muda, ela e defeito: "
                + "o POST apenas ENFILEIRA, e sem " + CHAMADA_DO_AVISO + " 'terminou', 'falhou' e "
                + "'ainda rodando' saem iguais para quem saiu de perto.");
    }

    /** As rotas cujos controllers colocam o trabalho na fila do pipeline. */
    private static Set<String> rotasQueEnfileiram() throws IOException {
        Set<String> rotas = new TreeSet<>();
        Path raizJava = Path.of("src", "main", "java");
        if (!Files.isDirectory(raizJava)) {
            return rotas;
        }
        Pattern post = Pattern.compile("@PostMapping\\(\"([^\"]+)\"\\)");
        try (Stream<Path> caminhos = Files.walk(raizJava)) {
            for (Path arquivo : caminhos.filter(p -> p.toString().endsWith("Controller.java")).toList()) {
                String fonte = Files.readString(arquivo, StandardCharsets.UTF_8);
                if (!fonte.contains("filaExecucao.submeter") && !fonte.contains("submeterJob")) {
                    continue;
                }
                Matcher m = post.matcher(fonte);
                while (m.find()) {
                    rotas.add("/api" + m.group(1));
                }
            }
        }
        return rotas;
    }

    @Test
    @DisplayName("a linha de base e catraca: so desce")
    void linhaDeBaseSoDesce() throws IOException {
        Set<String> aindaMudas = new TreeSet<>();
        for (Path tela : telasQueEsperamAFila()) {
            String conteudo = Files.readString(tela, StandardCharsets.UTF_8);
            if (!(conteudo.contains(MODULO_COMPARTILHADO) && conteudo.contains(CHAMADA_DO_AVISO))) {
                aindaMudas.add(relativo(tela));
            }
        }
        assertEquals(SEM_AVISO_LINHA_DE_BASE, aindaMudas,
            "a linha de base nao bate com a realidade: se uma tela ganhou o aviso, RETIRE-A da lista "
                + "(a catraca so desce); se uma nova nasceu muda, ela e defeito, nao linha de base.");
    }

    /**
     * A outra metade da invariante 10: o áudio tem UM dono. Uma tela que criasse o próprio
     * {@code AudioContext} passaria no primeiro teste (faria barulho) e mesmo assim seria a
     * cópia que diverge no dia em que o volume ou o número de toques mudar num lado só.
     *
     * <h2>Por que o critério é a CONSTRUÇÃO e não a palavra</h2>
     * A primeira versão procurava o texto {@code AudioContext} e reprovou de imediato — num
     * COMENTÁRIO que explicava por que o áudio precisa nascer no clique. Guarda que reprova
     * código correto é pior que guarda nenhuma: alarme falso ensina a desligar o alarme. O que
     * caracteriza a cópia é obter o construtor ({@code new AudioContext}, {@code window.AudioContext}),
     * não citar o nome.
     */
    private static final Pattern CONSTRUCAO_DE_AUDIO = Pattern.compile(
        "new\\s+(?:window\\.)?(?:webkit)?AudioContext|window\\.(?:webkit)?AudioContext");

    @Test
    @DisplayName("ninguem cria AudioContext fora do modulo compartilhado")
    void audioTemDonoUnico() throws IOException {
        Set<String> copias = new TreeSet<>();
        for (Path js : todosOsJs()) {
            if (js.getFileName().toString().equals(MODULO_COMPARTILHADO)) {
                continue;
            }
            String conteudo = Files.readString(js, StandardCharsets.UTF_8);
            if (CONSTRUCAO_DE_AUDIO.matcher(conteudo).find()) {
                copias.add(relativo(js));
            }
        }
        assertTrue(copias.isEmpty(),
            "AudioContext fora de " + MODULO_COMPARTILHADO + ": " + copias
                + ". Componente repetido vira compartilhado — duas copias divergem em silencio.");
    }

    /**
     * CASO-CONTROLE do detector de cópia, montado depois do alarme falso: ele tem de pegar a
     * construção de verdade e deixar em paz o texto que só MENCIONA o nome.
     */
    @Test
    @DisplayName("caso-controle: pega a construcao do audio e ignora a mencao em comentario")
    void detectorDeCopiaDiscrimina() {
        assertTrue(CONSTRUCAO_DE_AUDIO.matcher("const ctx = new AudioContext();").find(),
            "nao pegou a construcao direta");
        assertTrue(CONSTRUCAO_DE_AUDIO.matcher("const C = window.webkitAudioContext;").find(),
            "nao pegou o construtor obtido do window");
        assertFalse(CONSTRUCAO_DE_AUDIO.matcher(
                "// um AudioContext criado fora de um gesto nasce 'suspended'").find(),
            "acusou uma MENCAO em comentario — e foi exatamente assim que a 1a versao reprovou "
                + "codigo correto");
    }

    /**
     * CASO-CONTROLE: a varredura precisa ter sido vista REPROVANDO um caso doente montado à mão,
     * senão o verde acima pode ser cegueira. Monta duas telas de mentira — uma que espera e
     * avisa, outra que espera e cala — e exige que ela separe as duas.
     */
    @Test
    @DisplayName("caso-controle: a varredura pega a tela muda e deixa a que avisa em paz")
    void varreduraDiscrimina(@TempDir Path raiz) throws IOException {
        Path telaMuda = raiz.resolve("telaMuda/telaMuda.js");
        Files.createDirectories(telaMuda.getParent());
        Files.writeString(telaMuda, "fetch('" + ESPERA_A_FILA + "');\n", StandardCharsets.UTF_8);

        Path telaQueAvisa = raiz.resolve("telaBoa/telaBoa.js");
        Files.createDirectories(telaQueAvisa.getParent());
        Files.writeString(telaQueAvisa,
            "import { tocarAvisoSonoro } from '../js/" + MODULO_COMPARTILHADO + "';\n"
                + "fetch('" + ESPERA_A_FILA + "');\n"
                + CHAMADA_DO_AVISO + ");\n", StandardCharsets.UTF_8);

        Set<String> mudas = new TreeSet<>();
        for (Path tela : telasQueEsperamAFila(raiz)) {
            String conteudo = Files.readString(tela, StandardCharsets.UTF_8);
            if (!(conteudo.contains(MODULO_COMPARTILHADO) && conteudo.contains(CHAMADA_DO_AVISO))) {
                mudas.add(tela.getFileName().toString());
            }
        }

        assertEquals(Set.of("telaMuda.js"), mudas,
            "o instrumento nao discrimina: ou deixou a tela muda passar, ou acusou a que avisa");
    }

    private List<Path> telasQueEsperamAFila() throws IOException {
        return telasQueEsperamAFila(RAIZ_ESTATICOS);
    }

    /**
     * Telas — arquivos {@code .js} que consultam a fila. O {@code js/} comum fica de fora: ali
     * vive a infraestrutura ({@code app.js}), que serve as telas e não é uma delas.
     */
    private List<Path> telasQueEsperamAFila(Path raiz) throws IOException {
        try (Stream<Path> s = Files.walk(raiz)) {
            return s.filter(Files::isRegularFile)
                .filter(p -> p.getFileName().toString().endsWith(".js"))
                .filter(p -> !p.getParent().getFileName().toString().equals("js"))
                .filter(p -> {
                    try {
                        return Files.readString(p, StandardCharsets.UTF_8).contains(ESPERA_A_FILA);
                    } catch (IOException e) {
                        return false;
                    }
                })
                .sorted()
                .toList();
        }
    }

    private List<Path> todosOsJs() throws IOException {
        try (Stream<Path> s = Files.walk(RAIZ_ESTATICOS)) {
            return s.filter(Files::isRegularFile)
                .filter(p -> p.getFileName().toString().endsWith(".js"))
                // Bibliotecas de terceiros minificadas não são código do projeto e não seguem
                // as invariantes dele — acusá-las seria alarme falso, que ensina a desligar o
                // alarme.
                .filter(p -> !p.getFileName().toString().contains(".min."))
                .sorted()
                .toList();
        }
    }

    private String relativo(Path arquivo) {
        return RAIZ_ESTATICOS.toAbsolutePath().relativize(arquivo.toAbsolutePath())
            .toString().replace('\\', '/');
    }
}
