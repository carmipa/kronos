package org.traducao.projeto.traducaoKaraoke.application;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.traducao.projeto.legenda.application.DetectorEfeitoKaraokeService;
import org.traducao.projeto.legenda.domain.DocumentoLegenda;
import org.traducao.projeto.legenda.domain.EventoLegenda;
import org.traducao.projeto.traducaoKaraoke.domain.ClasseLinhaKaraoke;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * PROPÓSITO DE NEGÓCIO: a SÍLABA de uma frase que também está no arquivo não vai ao LLM — quem
 * traduz é a frase, e a sílaba é efeito.
 *
 * <h2>O prejuízo que originou, medido em 2026-08-20</h2>
 * O {@code OPL2} do Gundam Unicorn tem as duas camadas no mesmo trecho:
 * <pre>
 *   0:01:37.00 -&gt; 0:01:39.80   "Do you feel alone"
 *   0:01:37.00 -&gt; 0:01:39.90   "Do"
 *   0:01:37.61 -&gt; 0:01:39.90   "a"
 *   0:01:37.83 -&gt; 0:01:39.90   "lone"
 * </pre>
 * As duas iam ao LLM. Dos 131 textos distintos traduzidos nos 22 episódios, <b>78 eram
 * fragmento</b>: {@code cant} virou "Cantar.", {@code on} virou "começando", {@code you} virou
 * "Você.". Na tela, a frase certa com as sílabas erradas por cima.
 *
 * <h2>As duas armadilhas que este teste congela</h2>
 * <ul>
 *   <li><b>Substring não é evidência.</b> "o fragmento aparece na frase" aprovava a letra
 *       {@code i} contra qualquer frase que a contivesse — no 86 Part 2, 875 de 875 assim.</li>
 *   <li><b>Cópia de tipografia reconstrói a si mesma.</b> A mesma linha desenhada duas vezes
 *       "reconstruía" a frase sozinha e marcava a LETRA INTEIRA como sílaba — 75,6% do Zeta,
 *       incluindo as frases de {@code Song JP}.</li>
 * </ul>
 *
 * <h2>Comportamento em caso de falha</h2>
 * Se a regra afrouxar, volta a mandar sílaba ao LLM. Se apertar demais, a letra inteira é
 * marcada como efeito e a música deixa de ser traduzida — os dois lados estão cobertos aqui.
 */
class SilabaDeFraseIrmaTest {

    /**
     * O caso real e o {@code OPL2} do Unicorn, mas esse NOME so e reconhecido como musica por
     * entrada nominal no {@code application.yml}, que o teste de unidade nao carrega — a regra
     * {@code (op|ed)} com fronteira de letra nao alcanca "OPL2". Aqui o estilo e um nome musical
     * equivalente, para o teste medir a REGRA DA SILABA e nao o reconhecimento de estilo, que
     * tem dono e teste proprios em {@code PadraoEstiloMusical}.
     */
    private static final String ESTILO = "OP - English";

    private static int proximoIndice = 0;

    private static EventoLegenda evento(String inicio, String fim, String estilo, String texto) {
        String prefixo = "Dialogue: 0," + inicio + "," + fim + "," + estilo + ",,0,0,0,,";
        return new EventoLegenda(proximoIndice++, "Dialogue", estilo, prefixo, texto);
    }

    private static PlanoDeClassificacao planoDe(List<EventoLegenda> eventos) {
        return PlanoDeClassificacao.montar(
            new DocumentoLegenda("[Events]", new ArrayList<>(eventos), "\n", false),
            new ClassificadorLetraKaraokeService(new DetectorEfeitoKaraokeService()));
    }

    /** O caso real do Unicorn, com os tempos do arquivo (as sílabas terminam 0,10s depois). */
    private static List<EventoLegenda> opl2DoUnicorn() {
        return List.of(
            evento("0:01:37.00", "0:01:39.80", ESTILO, "{\\pos(960,1050)}Do you feel alone"),
            evento("0:01:37.00", "0:01:39.90", ESTILO, "{\\pos(753,1050)}Do"),
            evento("0:01:37.15", "0:01:39.90", ESTILO, "{\\pos(800,1050)}you"),
            evento("0:01:37.24", "0:01:39.90", ESTILO, "{\\pos(860,1050)}feel"),
            evento("0:01:37.61", "0:01:39.90", ESTILO, "{\\pos(910,1050)}a"),
            evento("0:01:37.83", "0:01:39.90", ESTILO, "{\\pos(940,1050)}lone"));
    }

    @Test
    @DisplayName("a sílaba vira EFEITO_KFX e a frase continua traduzível")
    void silabaViraEfeitoEFraseContinuaTraduzivel() {
        List<EventoLegenda> eventos = opl2DoUnicorn();
        PlanoDeClassificacao plano = planoDe(eventos);

        assertEquals(ClasseLinhaKaraoke.TRADUZIVEL_INGLES, plano.classeNaPosicao(0),
            "a LETRA inteira parou de ser traduzida — a musica fica em ingles na tela");
        for (int i = 1; i < eventos.size(); i++) {
            assertEquals(ClasseLinhaKaraoke.EFEITO_KFX, plano.classeNaPosicao(i),
                "a silaba " + i + " continua indo ao LLM: e assim que 'cant' vira 'Cantar.'");
        }
    }

    /**
     * Caso-controle NEGATIVO, o que a versão anterior desta regra reprovava: fragmentos que
     * apenas APARECEM na frase, sem reconstruí-la. É o padrão do 86 Part 2, onde a letra
     * {@code i} "casava" com qualquer frase que a contivesse.
     */
    @Test
    @DisplayName("fragmento que só aparece na frase, sem reconstruí-la, NÃO é sílaba")
    void coincidenciaNaoEhSilaba() {
        List<EventoLegenda> eventos = List.of(
            evento("0:00:10.00", "0:00:14.00", ESTILO, "Sleeping isn't a hard thing to do"),
            evento("0:00:10.50", "0:00:13.00", ESTILO, "i"),
            evento("0:00:11.50", "0:00:13.00", ESTILO, "to"));

        PlanoDeClassificacao plano = planoDe(eventos);

        assertNotEquals(ClasseLinhaKaraoke.EFEITO_KFX, plano.classeNaPosicao(1),
            "'i' foi tratado como silaba por coincidencia — a regra voltou a aprovar por acaso");
        assertNotEquals(ClasseLinhaKaraoke.EFEITO_KFX, plano.classeNaPosicao(2),
            "'to' foi tratado como silaba por coincidencia");
    }

    /**
     * Caso-controle da SEGUNDA armadilha: a mesma linha desenhada duas vezes (halo + preenchimento)
     * não pode reconstruir a si mesma. Foi o que marcou 75,6% do Zeta, incluindo a letra inteira.
     */
    @Test
    @DisplayName("cópia de tipografia não reconstrói a si mesma")
    void copiaDeTipografiaNaoEhSilaba() {
        List<EventoLegenda> eventos = List.of(
            evento("0:00:10.00", "0:00:14.00", "Song ENG", "{\\bord3}Right now I cannot move"),
            evento("0:00:10.00", "0:00:14.00", "Song ENG", "{\\bord0}Right now I cannot move"));

        PlanoDeClassificacao plano = planoDe(eventos);

        assertEquals(ClasseLinhaKaraoke.TRADUZIVEL_INGLES, plano.classeNaPosicao(0),
            "a letra inteira virou efeito: a musica deixaria de ser traduzida");
        assertEquals(ClasseLinhaKaraoke.TRADUZIVEL_INGLES, plano.classeNaPosicao(1),
            "a copia de tipografia virou efeito por reconstruir a si mesma");
    }

    /**
     * A folga de 1 segundo no fim não é enfeite: no arquivo real do Unicorn as sílabas terminam
     * em 0:01:39.90 e a frase em 0:01:39.80. Exigir contenção estrita reprovava 100% delas por
     * dez centésimos — este teste congela a folga.
     */
    @Test
    @DisplayName("a sílaba que termina depois da frase por uma fração ainda é sílaba")
    void folgaNoFimEhRespeitada() {
        PlanoDeClassificacao plano = planoDe(opl2DoUnicorn());

        assertEquals(ClasseLinhaKaraoke.EFEITO_KFX, plano.classeNaPosicao(5),
            "a folga de fim sumiu: as silabas voltam ao LLM por causa de 0,10s");
    }

    /**
     * O caso que DISCRIMINA reconstrução de substring — e que faltava aqui.
     *
     * <p>Os pedaços {@code Do|you} concatenam em {@code doyou}, que É substring de
     * {@code doyoufeelalone}. Com o critério antigo isto passaria por sílaba; com reconstrução,
     * não passa, porque a partição está INCOMPLETA — falta {@code feel|a|lone} e portanto estes
     * dois não são a partição daquela frase.
     *
     * <p>Sem este caso o teste ficava CEGO: trocar a igualdade por {@code contains} não fazia
     * nenhuma asserção reprovar, medido por mutação em 2026-08-20.
     */
    @Test
    @DisplayName("partição INCOMPLETA não é sílaba — mesmo sendo substring da frase")
    void particaoIncompletaNaoEhSilaba() {
        List<EventoLegenda> eventos = List.of(
            evento("0:01:37.00", "0:01:39.80", ESTILO, "Do you feel alone"),
            evento("0:01:37.00", "0:01:39.90", ESTILO, "Do"),
            evento("0:01:37.15", "0:01:39.90", ESTILO, "you"));

        PlanoDeClassificacao plano = planoDe(eventos);

        assertNotEquals(ClasseLinhaKaraoke.EFEITO_KFX, plano.classeNaPosicao(1),
            "'Do' virou silaba so por ser substring: a regra voltou ao criterio derrubado");
        assertNotEquals(ClasseLinhaKaraoke.EFEITO_KFX, plano.classeNaPosicao(2),
            "'you' virou silaba so por ser substring");
    }

    /**
     * O caso que DISCRIMINA "pelo menos dois pedaços" — e que também faltava.
     *
     * <p>Um único pedaço que sozinho reconstrói a frase não é sílaba: é a mesma linha escrita de
     * outro jeito. Aceitar um pedaço só foi o que marcou 75,6% do Zeta na segunda versão desta
     * regra, e sem este caso a asserção ficava cega — medido por mutação.
     */
    @Test
    @DisplayName("um pedaço sozinho não é sílaba, mesmo reconstruindo a frase inteira")
    void umPedacoSozinhoNaoEhSilaba() {
        // DUAS copias identicas de proposito: a deduplicacao por (inicio,fim,texto) as reduz a
        // UM pedaco. Com uma copia so, a guarda de "pelo menos dois irmaos" cortaria antes e o
        // teste nao alcancaria o ponto que mede — foi assim que ele ficou cego na primeira versao.
        List<EventoLegenda> eventos = List.of(
            evento("0:00:10.00", "0:00:14.00", ESTILO, "Right now"),
            evento("0:00:10.00", "0:00:13.00", ESTILO, "{\bord3}Rightnow"),
            evento("0:00:10.00", "0:00:13.00", ESTILO, "{\bord0}Rightnow"));

        PlanoDeClassificacao plano = planoDe(eventos);

        assertNotEquals(ClasseLinhaKaraoke.EFEITO_KFX, plano.classeNaPosicao(1),
            "um pedaco sozinho foi aceito como particao: e por ai que a copia de tipografia entra");
        assertNotEquals(ClasseLinhaKaraoke.EFEITO_KFX, plano.classeNaPosicao(2),
            "a segunda copia tambem foi aceita");
    }

    /**
     * A1 (caso-controle de FRONTEIRA, 2026-09-21): o fansub divide a letra em pedaços que às
     * vezes têm DUAS palavras ({@code "you are"}), e o corte antigo "MENOS de duas palavras"
     * abortava o grupo inteiro ao topá-los — os 9 fragmentos de
     * <i>"If you are holding holding onto fear"</i> iam ao LLM e viravam lixo. Medido no cache
     * real do Unicorn ep01 (aya, 20/08): {@code hol}→"Olá", {@code on}→"começando",
     * {@code dnt}→meta-resposta, {@code all}→"Eu sou a Audrey" (alucinação de lore).
     *
     * <p>Este é o par de fronteira: um pedaço de duas palavras que É pedaço legítimo da letra
     * (aprovar como sílaba) convive com a cópia da frase inteira, que NÃO é
     * ({@code copiaDeTipografiaNaoEhSilaba} acima cobre esse lado). O corte que separa os dois é
     * "MENOS palavras que a frase", não "menos de duas".
     *
     * <p>A2: sob o corte antigo esta asserção REPROVA — a presença de {@code "you are"} disparava
     * {@code algumEhFrase} e todos os pedaços saíam como {@code TRADUZIVEL_INGLES}.
     */
    @Test
    @DisplayName("pedaço de DUAS palavras (you are) não aborta o grupo — os 9 fragmentos viram sílaba")
    void pedacoDeDuasPalavrasAindaEhSilaba() {
        List<EventoLegenda> eventos = List.of(
            evento("0:02:10.46", "0:02:14.47", ESTILO, "{\\bord0}If you are holding holding onto fear"),
            evento("0:02:10.46", "0:02:14.47", ESTILO, "{\\pos(469,1050)}If"),
            evento("0:02:10.65", "0:02:14.47", ESTILO, "{\\pos(614,1050)}you are"),
            evento("0:02:10.99", "0:02:14.47", ESTILO, "{\\pos(778,1050)}hol"),
            evento("0:02:11.67", "0:02:14.47", ESTILO, "{\\pos(888,1050)}ding"),
            evento("0:02:12.28", "0:02:14.47", ESTILO, "{\\pos(1012,1050)}hol"),
            evento("0:02:13.06", "0:02:14.47", ESTILO, "{\\pos(1122,1050)}ding"),
            evento("0:02:13.75", "0:02:14.47", ESTILO, "{\\pos(1237,1050)}on"),
            evento("0:02:14.11", "0:02:14.47", ESTILO, "{\\pos(1305,1050)}to"),
            evento("0:02:14.20", "0:02:14.47", ESTILO, "{\\pos(1415,1050)}fear"));

        PlanoDeClassificacao plano = planoDe(eventos);

        assertEquals(ClasseLinhaKaraoke.TRADUZIVEL_INGLES, plano.classeNaPosicao(0),
            "a LETRA inteira parou de ser traduzida — a musica fica em ingles na tela");
        for (int i = 1; i < eventos.size(); i++) {
            assertEquals(ClasseLinhaKaraoke.EFEITO_KFX, plano.classeNaPosicao(i),
                "o fragmento " + i + " ainda vai ao LLM: e assim que 'hol' vira 'Olá'");
        }
    }

    /**
     * A1 (caso-controle de FRONTEIRA no INSTANTE, 2026-09-21): o fill de karaokê ADIANTA a linha —
     * o primeiro pedaço começa uma fração antes da frase. Medido no OPL2 do Unicorn ep01:
     * <i>"We didnt see all its meaning"</i> começa em 0:02:39.53, mas o pedaço {@code We} começa
     * em 0:02:39.38 (0,15s antes). O corte antigo só tinha folga no FIM, excluía o {@code We}, a
     * reconstrução ficava incompleta e os 8 fragmentos vazavam ({@code dnt}→"Não há contexto",
     * {@code all}→"Eu sou a Audrey", {@code ning}→"Nenhum.").
     *
     * <p>O lado NEGATIVO da fronteira: o último pedaço da frase ANTERIOR ({@code vive}, que termina
     * em 0:02:39.23, antes desta frase começar) NÃO pode entrar como irmão — a sobreposição
     * ({@code fim >= iniFrase}) o barra. Se entrasse, poluiria a reconstrução e o grupo inteiro
     * voltaria a vazar.
     */
    @Test
    @DisplayName("fill que ADIANTA a linha ainda é sílaba; o pedaço da frase anterior não entra")
    void folgaNoInicioEhRespeitadaESemVizinho() {
        List<EventoLegenda> eventos = List.of(
            // Fim da frase ANTERIOR: 'vive' termina antes desta frase comecar — nao pode entrar.
            evento("0:02:38.53", "0:02:39.23", ESTILO, "{\\pos(900,1050)}vive"),
            // A frase e seus 8 pedacos; 'We' comeca 0,15s ANTES da frase (39.38 < 39.53).
            evento("0:02:39.53", "0:02:44.69", ESTILO, "{\\bord0}We didnt see all its meaning"),
            evento("0:02:39.38", "0:02:44.79", ESTILO, "{\\pos(591,1050)}We"),
            evento("0:02:40.40", "0:02:44.79", ESTILO, "{\\pos(650,1050)}di"),
            evento("0:02:40.96", "0:02:44.79", ESTILO, "{\\pos(758,1050)}dnt"),
            evento("0:02:41.31", "0:02:44.79", ESTILO, "{\\pos(860,1050)}see"),
            evento("0:02:41.99", "0:02:44.79", ESTILO, "{\\pos(975,1050)}all"),
            evento("0:02:42.58", "0:02:44.79", ESTILO, "{\\pos(1068,1050)}its"),
            evento("0:02:42.85", "0:02:44.79", ESTILO, "{\\pos(1180,1050)}mea"),
            evento("0:02:43.40", "0:02:44.79", ESTILO, "{\\pos(1309,1050)}ning"));

        PlanoDeClassificacao plano = planoDe(eventos);

        // A prova de que 'vive' (frase anterior) NAO poluiu a reconstrucao de "We didnt" e que os
        // 8 fragmentos abaixo saem EFEITO_KFX. Se 'vive' tivesse entrado como irmao, a concatenacao
        // nao casaria e todos voltariam a vazar como TRADUZIVEL. A classe do proprio 'vive' (pos 0)
        // nao e o ponto: isolado, sem a frase dele, ele cai pelo estilo, e isso e outro caso.
        assertEquals(ClasseLinhaKaraoke.TRADUZIVEL_INGLES, plano.classeNaPosicao(1),
            "a frase inteira precisa continuar traduzivel");
        for (int i = 2; i < eventos.size(); i++) {
            assertEquals(ClasseLinhaKaraoke.EFEITO_KFX, plano.classeNaPosicao(i),
                "o fragmento " + i + " voltou a vazar por causa do 'We' adiantado ou do 'vive' vizinho");
        }
        assertEquals(List.of(2, 3, 4, 5, 6, 7, 8, 9), plano.silabasDaFrase(1),
            "a frase tem de saber EXATAMENTE quais pedacos sao os dela — o 'vive' da frase anterior "
                + "nao pode ser silenciado junto quando ela virar portugues");
    }

    /**
     * PROPÓSITO DE NEGÓCIO: a frase sabe QUAIS pedaços são os dela — é o que permite tirar da tela
     * exatamente essas palavras quando a frase vira só português (Unicorn, 12/10/2026).
     *
     * <p>INVARIANTES DO DOMÍNIO: só a frase reconstruída tem lista; o pedaço, a coincidência e a
     * partição incompleta respondem lista vazia — o lado que não silencia nada.
     */
    @Test
    @DisplayName("a frase sabe quais pedaços são os dela; o resto responde lista vazia")
    void fraseSabeQuaisSaoAsSilabasDela() {
        PlanoDeClassificacao unicorn = planoDe(opl2DoUnicorn());
        assertEquals(List.of(1, 2, 3, 4, 5), unicorn.silabasDaFrase(0));
        assertEquals(List.of(), unicorn.silabasDaFrase(1), "o pedaco nao tem pedacos");
        assertEquals(List.of(), unicorn.silabasDaFrase(99), "posicao fora do documento");

        PlanoDeClassificacao incompleta = planoDe(List.of(
            evento("0:01:37.00", "0:01:39.80", ESTILO, "Do you feel alone"),
            evento("0:01:37.00", "0:01:39.90", ESTILO, "Do"),
            evento("0:01:37.15", "0:01:39.90", ESTILO, "you")));
        assertEquals(List.of(), incompleta.silabasDaFrase(0),
            "particao incompleta nao e efeito da frase — silenciar 'Do you' apagaria letra de verdade");
    }
}
