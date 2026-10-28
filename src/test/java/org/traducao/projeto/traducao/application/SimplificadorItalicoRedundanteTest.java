package org.traducao.projeto.traducao.application;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * PROPÓSITO DE NEGÓCIO: congela o contrato do {@link SimplificadorItalicoRedundante} com
 * as falas REAIS que aparecem nos logs de corrupção de {@code [[TAGn]]} — medidas em
 * 2026-07-31 sobre 208 ocorrências, das quais 141 (67,8%) têm exatamente este formato.
 *
 * <p>INVARIANTES DO DOMÍNIO: a renderização não pode mudar. {@code {\i1}A{\i}\N{\i1}B} e
 * {@code {\i1}A\NB} produzem a mesma tela — o par desliga/religa cerca a quebra, onde não
 * há texto. Itálico de ÊNFASE, que delimita palavra, é intocável.
 *
 * <p>COMPORTAMENTO EM CASO DE FALHA: qualquer fala de ênfase alterada, ou qualquer texto
 * visível perdido, reprova a suíte.
 */
class SimplificadorItalicoRedundanteTest {

    private final SimplificadorItalicoRedundante simplificador = new SimplificadorItalicoRedundante();

    @Test
    @DisplayName("Caso de 141 ocorrências: narração em duas linhas vai de 4 marcadores para 1")
    void simplificaNarracaoEmDuasLinhas() {
        String original = "{\\an8\\i1}Zeon's national strength is less than"
            + "{\\i}\\N{\\i1}1/30th that of the Earth Federation.";

        String saida = simplificador.simplificar(original);

        assertEquals("{\\an8\\i1}Zeon's national strength is less than"
            + "\\N1/30th that of the Earth Federation.", saida);
        assertEquals(1, contarBlocos(saida), "deve sobrar só o envoltório de abertura");
        assertEquals(4, contarBlocos(original) + 1, "o original tinha 3 blocos + a quebra");
    }

    @Test
    @DisplayName("O texto visível sobrevive inteiro — nada além das tags é removido")
    void preservaTodoOTextoVisivel() {
        String original = "{\\an8\\i1}In spite of that, how is it that"
            + "{\\i}\\N{\\i1}we've been able to fight for so long?";

        String saida = simplificador.simplificar(original);

        assertEquals(semTags(original), semTags(saida),
            "o texto sem tags tem que ser idêntico antes e depois");
        assertTrue(saida.contains("\\N"), "a quebra visual continua lá");
    }

    @Test
    @DisplayName("ÊNFASE não é tocada: o itálico delimita palavra, não quebra")
    void naoTocaItalicoDeEnfase() {
        for (String fala : new String[]{
            "I {\\i1}will{\\i0} fight for my friends!",
            "The Apsaras {\\i1}will{\\i0} be completed.",
            "Y-You've been giving them {\\i1}this{\\i0}...?!"}) {
            assertSame(fala, simplificador.simplificar(fala),
                "ênfase sem quebra não pode ser alterada: " + fala);
        }
    }

    @Test
    @DisplayName("Itálico que envolve a fala inteira, sem quebra, fica como está")
    void naoTocaItalicoSimples() {
        assertSame("{\\i1}Eledore!", simplificador.simplificar("{\\i1}Eledore!"));
        assertSame("{\\i1}Roger!{\\i0}", simplificador.simplificar("{\\i1}Roger!{\\i0}"));
    }

    @Test
    @DisplayName("Se houver TEXTO entre o desliga e o religa, nada é removido")
    void naoRemoveQuandoHaTextoNoMeio() {
        String fala = "{\\i1}narrando{\\i0} ele disse \\N{\\i1}e continuou";
        assertSame(fala, simplificador.simplificar(fala),
            "o trecho fora do itálico é intencional");
    }

    @Test
    @DisplayName("Bloco de religamento com override próprio NÃO é descartado")
    void naoDescartaOverrideComEfeito() {
        String fala = "{\\i1}primeira{\\i}\\N{\\i1\\pos(10,20)}segunda";
        assertSame(fala, simplificador.simplificar(fala),
            "o \\pos no religamento tem efeito visual próprio");
    }

    @Test
    @DisplayName("Fala sem quebra ou sem tag volta pela mesma instância")
    void entradaSemPadraoVoltaIntacta() {
        assertSame("Decryption code 33.", simplificador.simplificar("Decryption code 33."));
        assertEquals(null, simplificador.simplificar(null));
    }

    @Test
    @DisplayName("Operação idempotente: aplicar duas vezes dá o mesmo resultado")
    void idempotente() {
        String original = "{\\an8\\i1}A{\\i}\\N{\\i1}B";
        String uma = simplificador.simplificar(original);
        assertEquals(uma, simplificador.simplificar(uma));
    }

    @Test
    @DisplayName("temRedundancia concorda com simplificar")
    void deteccaoBateComAAcao() {
        assertTrue(simplificador.temRedundancia("{\\i1}A{\\i}\\N{\\i1}B"));
        assertFalse(simplificador.temRedundancia("I {\\i1}will{\\i0} fight!"));
        assertFalse(simplificador.temRedundancia(null));
    }

    /** 28/10/2026: o bloco que desliga com outros overrides sumia inteiro, levando a cor junto. */
    @Test
    @DisplayName("caminho composto tira so o \\i0 do bloco que desliga e mantem a cor")
    void compostoMantemOsOutrosOverrides() {
        assertEquals("{\\i1}linha um{\\c&H00FFFF&}\\Nlinha dois",
            simplificador.simplificar("{\\i1}linha um{\\i0\\c&H00FFFF&}\\N{\\i1}linha dois"));
        assertEquals("{\\i1}A{\\a8}\\NB", simplificador.simplificar("{\\i1}A{\\i0\\a8}\\N{\\i1}B"));
        assertEquals("{\\i1}A\\NB", simplificador.simplificar("{\\i1}A{\\i\\i0}\\N{\\i1}B"),
            "CONTROLE: bloco so de italico continua saindo inteiro");
    }

    private static int contarBlocos(String t) {
        return (int) t.chars().filter(c -> c == '{').count();
    }

    private static String semTags(String t) {
        return t.replaceAll("\\{[^}]*\\}", "").replace("\\N", " ").replaceAll("\\s+", " ").trim();
    }
}
