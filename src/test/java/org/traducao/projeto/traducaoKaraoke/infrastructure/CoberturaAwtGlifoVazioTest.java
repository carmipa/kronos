package org.traducao.projeto.traducaoKaraoke.infrastructure;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.awt.Font;
import java.io.InputStream;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * PROPÓSITO DE NEGÓCIO: a fonte que MAPEIA a letra acentuada para um glifo SEM CONTORNO não desenha
 * a letra — o player mostra um buraco. Medido em 12/10/2026 no encerramento do Guilty Crown: a
 * "Complete in Him" aponta á, ã, ê, ç... para glifos vazios, o {@code Font.canDisplay} respondia
 * "desenha", a 4.1 não trocava a fonte e a tela mostrava "prezado por voc ." no lugar de "você".
 *
 * <h2>Os controles (A1: o mesmo sinal dos dois lados)</h2>
 * A fonte de teste {@code karaoke/fontes/acento-vazio.ttf} é desenho nosso (um quadrado), gerada por
 * {@code ferramentas/gerar-fonte-teste-acento-vazio.py}: "ã" e "ê" são AMBAS letras acentuadas e
 * AMBAS estão no cmap — só o "ê" aponta para o glifo vazio. O espaço comum e o espaço de largura zero
 * (U+200B) também são vazios e não podem contar como falta: são invisíveis por natureza.
 *
 * <p>INVARIANTES DO DOMÍNIO: glifo mapeado e sem contorno é "sem desenho"; glifo com contorno não é;
 * caractere invisível por natureza nunca é falta.
 *
 * <p>COMPORTAMENTO EM CASO DE FALHA: recurso ausente reprova (não pula): a fonte é do próprio
 * repositório, e sem ela o teste não mede nada.
 */
class CoberturaAwtGlifoVazioTest {

    private static final String FAMILIA = "Kronos Acento Vazio";

    private static FontesEmbutidasMkvAdapter.CoberturaAwt cobertura() throws Exception {
        try (InputStream in = CoberturaAwtGlifoVazioTest.class.getResourceAsStream("/karaoke/fontes/acento-vazio.ttf")) {
            assertNotNull(in, "fonte de teste ausente do classpath");
            Font fonte = Font.createFont(Font.TRUETYPE_FONT, in);
            assertEquals(FAMILIA, fonte.getFamily(java.util.Locale.ROOT), "a fonte de teste mudou de familia");
            return new FontesEmbutidasMkvAdapter.CoberturaAwt(Map.of(FAMILIA.toLowerCase(java.util.Locale.ROOT), fonte));
        }
    }

    @Test
    @DisplayName("ê mapeado para glifo vazio falta; ã desenhado não falta (mesmo sinal: acento no cmap)")
    void glifoMapeadoSemContornoContaComoFalta() throws Exception {
        assertEquals(Optional.of("ê"), cobertura().caracteresSemDesenho(FAMILIA, "e ê a ã"),
            "o ê aponta para um glifo sem contorno: o player desenha um buraco");
    }

    @Test
    @DisplayName("só letras desenhadas, inclusive acentuada: nada falta")
    void acentuadaDesenhadaNaoEFalta() throws Exception {
        assertEquals(Optional.of(""), cobertura().caracteresSemDesenho(FAMILIA, "a ã e"),
            "o ã tem contorno — trocar a fonte por causa dele seria alarme falso");
    }

    @Test
    @DisplayName("espaço e espaço de largura zero vazios não são falta")
    void invisivelPorNaturezaNaoEFalta() throws Exception {
        assertEquals(Optional.of(""), cobertura().caracteresSemDesenho(FAMILIA, "a​e a"),
            "U+200B e o espaco sao invisiveis por natureza; glifo vazio neles e o certo");
    }

    @Test
    @DisplayName("letra fora do cmap continua faltando (comportamento anterior preservado)")
    void foraDoCmapContinuaFaltando() throws Exception {
        Optional<String> faltam = cobertura().caracteresSemDesenho(FAMILIA, "aço");
        assertTrue(faltam.isPresent() && faltam.get().contains("ç") && faltam.get().contains("o"),
            () -> "ç e o nao estao na fonte: " + faltam);
    }
}
