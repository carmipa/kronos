package org.traducao.projeto.traducao.application;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * PROPÓSITO DE NEGÓCIO: fixa por regressão o contrato do {@link DetectorCorrenteFrasePartida}
 * — juntar num lote só as falas que formam uma frase partida entre eventos consecutivos, sem
 * juntar o que não é frase partida.
 *
 * <p>INVARIANTES DO DOMÍNIO: cartão (título de episódio) NUNCA abre corrente; corrente de
 * qualquer tamanho fica inteira num grupo; o que não é corrente segue o fatiamento por
 * tamanho de lote; tags ASS não influenciam a heurística.
 *
 * <p>COMPORTAMENTO EM CASO DE FALHA: agrupar um cartão à narração seguinte, partir uma
 * corrente ou perder/duplicar um texto reprova a suíte.
 */
class DetectorCorrenteFrasePartidaTest {

    private final DetectorCorrenteFrasePartida detector = new DetectorCorrenteFrasePartida();

    @Test
    @DisplayName("Caso real do Unicorn: a cerimônia e a residência viram UM lote")
    void agrupaParDeFrasePartida() {
        List<String> textos = List.of(
            "The Earth Federation government is about to host a ceremony",
            "at the Prime Minister's residence, \"Laplace\",",
            "Fear not.");

        List<List<String>> grupos = detector.agrupar(textos, 1);

        assertEquals(2, grupos.size(), "esperado: 1 corrente + 1 fala solta");
        assertEquals(2, grupos.get(0).size(), "a corrente tem que ir inteira num lote");
        assertEquals(textos.get(0), grupos.get(0).get(0));
        assertEquals(textos.get(1), grupos.get(0).get(1));
        assertEquals(List.of("Fear not."), grupos.get(1));
    }

    @Test
    @DisplayName("Corrente de 3 eventos não é partida em par + solto")
    void agrupaCorrenteDeTres() {
        List<String> textos = List.of(
            "Mankind has set foot into a new era,",
            "in which we shall build for ourselves worlds to live in,",
            "beyond Mother Earth.");

        List<List<String>> grupos = detector.agrupar(textos, 1);

        assertEquals(1, grupos.size());
        assertEquals(3, grupos.get(0).size());
    }

    @Test
    @DisplayName("Cartão de título NÃO abre corrente — sem isto, 20 títulos do Unicorn colariam")
    void cartaoDeTituloNaoAbreCorrente() {
        List<String> textos = List.of(
            "THEY CALLED IT GUNDAM,",
            "awaken the Newtype that lies within you.");

        List<List<String>> grupos = detector.agrupar(textos, 1);

        assertEquals(2, grupos.size(), "título de episódio não é a 1ª metade de uma frase");
        assertFalse(detector.ehContinuacao(textos.get(0), textos.get(1)));
    }

    @Test
    @DisplayName("Pontuação final fecha a frase: não há continuação")
    void pontuacaoFinalNaoAbreCorrente() {
        assertFalse(detector.ehContinuacao("Fear not.", "the path will show itself."));
        assertFalse(detector.ehContinuacao("Are you ready?", "yes, I am."));
    }

    @Test
    @DisplayName("Maiúscula na fala seguinte não é continuação")
    void maiusculaNaoContinua() {
        assertFalse(detector.ehContinuacao("But when Zinnerman infiltrates the Garuda",
            "Banagher runs to the hangar"));
    }

    @Test
    @DisplayName("Tags ASS são ignoradas ao medir início e fim")
    void tagsNaoAtrapalhamAHeuristica() {
        assertTrue(detector.ehContinuacao(
            "{\\i1}he is met by Marida's black Unicorn Gundam,{\\i0}",
            "{\\pos(190,230)}the Banshee, standing in his way."));
    }

    @Test
    @DisplayName("Fora das correntes, o fatiamento por tamanho de lote continua valendo")
    void falasSoltasSeguemOTamanhoDeLote() {
        List<String> textos = List.of("Fear not.", "Go now!", "It is time.", "Understood.");

        List<List<String>> grupos = detector.agrupar(textos, 2);

        assertEquals(2, grupos.size());
        assertEquals(2, grupos.get(0).size());
        assertEquals(2, grupos.get(1).size());
    }

    @Test
    @DisplayName("Nenhum texto é perdido nem duplicado no agrupamento")
    void preservaTodosOsTextosNaOrdem() {
        List<String> textos = List.of(
            "Fear not.",
            "The Earth Federation government is about to host a ceremony",
            "at the Prime Minister's residence, \"Laplace\",",
            "to usher in the birth of the new calendar era.",
            "RETRIBUTION,",
            "Understood.");

        List<String> achatado = detector.agrupar(textos, 3).stream().flatMap(List::stream).toList();

        assertEquals(textos, achatado, "ordem e conteúdo têm que sobreviver ao agrupamento");
    }

    @Test
    @DisplayName("Lista vazia ou nula não quebra")
    void entradaVaziaDevolveVazio() {
        assertTrue(detector.agrupar(List.of(), 1).isEmpty());
        assertTrue(detector.agrupar(null, 1).isEmpty());
    }
}
