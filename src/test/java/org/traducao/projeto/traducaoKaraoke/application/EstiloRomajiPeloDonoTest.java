package org.traducao.projeto.traducaoKaraoke.application;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.traducao.projeto.legenda.application.DetectorEfeitoKaraokeService;
import org.traducao.projeto.traducaoKaraoke.domain.ClasseLinhaKaraoke;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * PROPÓSITO DE NEGÓCIO: os estilos de romaji que o fansub batiza pela forma curta ({@code OP Roma},
 * {@code ED_S2_roma}, {@code ED_Romaji1}, {@code Romanji}) são camada ORIGINAL — preservada. O
 * classificador tinha regex própria com {@code \b}, mais estreita que a do dono no peer
 * {@code legenda}; com texto que tem inglês ("Sayonara my love"), a linha ia ao LLM e empilhava
 * quatro linhas na tela (auditoria da 4.1, 28/10/2026, A4).
 *
 * <p>INVARIANTES DO DOMÍNIO: a abreviação {@code ED-ROM} (86) continua original; {@code Romance} e
 * a camada inglesa continuam traduzíveis (A1 — o mesmo sinal, o lado legítimo).
 *
 * <p>COMPORTAMENTO EM CASO DE FALHA: a asserção nomeia o estilo.
 */
class EstiloRomajiPeloDonoTest {

    private final ClassificadorLetraKaraokeService classificador =
        new ClassificadorLetraKaraokeService(new DetectorEfeitoKaraokeService());

    @ParameterizedTest
    @ValueSource(strings = {"OP Roma", "ED Roma L1", "ED_S2_roma", "OP_S2_roma", "ED_Romaji1", "Romanji", "ED-ROM", "OP - Romaji"})
    void estiloDeRomajiPreserva(String estilo) {
        assertEquals(ClasseLinhaKaraoke.ORIGINAL_JAPONES, classificador.classificar(estilo, "Sayonara my love"), estilo);
    }

    @ParameterizedTest
    @ValueSource(strings = {"OP Eng", "Romance English", "OP - English"})
    void camadaInglesaContinuaTraduzivel(String estilo) {
        assertEquals(ClasseLinhaKaraoke.TRADUZIVEL_INGLES,
            classificador.classificar(estilo, "Even if the world ends tomorrow"), estilo);
    }
}
