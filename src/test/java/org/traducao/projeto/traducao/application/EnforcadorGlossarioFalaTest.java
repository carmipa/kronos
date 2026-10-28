package org.traducao.projeto.traducao.application;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * PROPÓSITO DE NEGÓCIO: trava a forma ÚNICA das falas que são inteiramente um termo conhecido.
 * Sem isso a saída fica inconsistente entre episódios da mesma série — o espectador vê
 * "Entendido!" no episódio 3 e "Roger!" no 7.
 *
 * <h2>Medição que originou (acervo completo, 2026-07-23)</h2>
 * {@code Roger} sozinho aparece 220 vezes nos caches versionados, com quatro desfechos:
 * 179x {@code Entendido} (81%, a forma majoritária), 34x mantida em inglês, 5x
 * {@code Shiro: Roger!} (rótulo de locutor INVENTADO pelo modelo) e 2x {@code Rogério.}
 * (o "Roger" de rádio traduzido como nome de pessoa).
 *
 * <p>Os dois últimos são invisíveis para as outras guardas: a régua de identidade só age quando a
 * tradução é IGUAL ao original, e esses textos são diferentes. Passavam limpos.
 *
 * <h2>Invariantes do domínio</h2>
 * <ul>
 *   <li>Casamento de fala COMPLETA: {@code "Roger."} é rádio; {@code "Roger, come here"} pode ser
 *       um personagem e nunca é tocado.</li>
 *   <li>Tags ASS e pontuação do original sobrevivem intactas.</li>
 *   <li>A decisão vem do ORIGINAL, então corrige inclusive tradução alucinada.</li>
 * </ul>
 *
 * <h2>Comportamento em caso de falha</h2>
 * Agir por substring quebraria qualquer fala que contenha o termo no meio de uma frase — é o
 * risco que este teste existe para impedir.
 */
class EnforcadorGlossarioFalaTest {

    private final EnforcadorGlossarioFala enforcador = new EnforcadorGlossarioFala();

    @Test
    void fixaAFormaCanonicaIndependenteDoQueOModeloDevolveu() {
        // Os quatro desfechos reais medidos convergem para a mesma saída.
        assertEquals("Entendido!", enforcador.reforcar("Roger!", "Entendido!"), "já correto: mantém");
        assertEquals("Entendido!", enforcador.reforcar("Roger!", "Roger!"), "mantido em inglês: corrige");
        assertEquals("Entendido!", enforcador.reforcar("Roger!", "Shiro: Roger!"),
            "rótulo de locutor inventado pelo modelo: a fala inteira é reescrita e o prefixo some");
        assertEquals("Entendido.", enforcador.reforcar("Roger.", "Rogério."),
            "o 'Roger' de rádio virou nome de pessoa: corrige");
    }

    @Test
    void preservaTagsAssEPontuacaoDoOriginal() {
        assertEquals("{\\i1}Entendido.", enforcador.reforcar("{\\i1}Roger.", "{\\i1}Roger."));
        assertEquals("{\\i1}Entendido.{\\i0}", enforcador.reforcar("{\\i1}Roger.{\\i0}", "qualquer coisa"));
        assertEquals("Entendido!!", enforcador.reforcar("Roger!!", "Roger!!"), "pontuação repetida sobrevive");
    }

    /**
     * PROPÓSITO DE NEGÓCIO: o termo só é reescrito quando é a fala TODA. Num diálogo, "Roger"
     * pode ser o nome de um personagem — reescrever ali corromperia a fala.
     */
    @Test
    void naoTocaFalaEmQueOTermoEhApenasUmaParte() {
        assertEquals("Roger, venha aqui.",
            enforcador.reforcar("Roger, come here.", "Roger, venha aqui."),
            "aqui Roger pode ser nome de personagem: intocado");
        assertEquals("Diga ao Roger que entendi.",
            enforcador.reforcar("Tell Roger I understood.", "Diga ao Roger que entendi."));
    }

    /**
     * 28/10/2026, medido pela chave de produção: das 165 falas que são só "No", 49 ficaram em
     * inglês, 9 com conteúdo inventado, 4 "Nenhum" e 3 "Número".
     */
    @Test
    void noViraNaoQuandoAFalaInteiraEhNo() {
        assertEquals("Não.", enforcador.reforcar("No.", "Número."));
        assertEquals("Não...", enforcador.reforcar("No...", "Não há escolha..."));
        assertEquals("{\\i1}Não!{\\i0}", enforcador.reforcar("{\\i1}No!{\\i0}", "{\\i1}Nenhum!{\\i0}"));
        assertEquals("Não.", enforcador.traduzirSemModelo("No.").orElseThrow());

        assertEquals("Não há como.", enforcador.reforcar("No way.", "Não há como."),
            "CONTROLE: fala em que 'no' e so uma parte nao e tocada");
        assertEquals("Nº 5.", enforcador.reforcar("No. 5.", "Nº 5."), "CONTROLE: 'No. 5' nao e a fala 'No'");
        assertEquals(java.util.Optional.empty(), enforcador.traduzirSemModelo("Hello there"),
            "CONTROLE: fala fora do glossario vai ao modelo");
    }

    /** Aspas curvas, espaço não separável e o espaço fixo {@code \h} do ASS também são ornamento. */
    @Test
    void ornamentoUnicodeEEspacoFixoNaoEscondemOTermo() {
        assertEquals("“Entendido.”", enforcador.reforcar("“Roger.”", "“Rogério.”"));
        assertEquals("Entendido. ", enforcador.reforcar("Roger. ", "Rogério. "));
        assertEquals("\\hEntendido.", enforcador.reforcar("\\hRoger.", "\\hRogério."));
    }

    @Test
    void falaForaDoGlossarioEDadosAusentesNaoMudamNada() {
        assertEquals("Bom dia.", enforcador.reforcar("Good morning.", "Bom dia."));
        assertEquals("x", enforcador.reforcar(null, "x"));
        assertEquals("x", enforcador.reforcar("  ", "x"));
        assertEquals("y", enforcador.reforcar("{\\pos(1,2)}", "y"), "fala só de tags: nada a fazer");
    }
}
