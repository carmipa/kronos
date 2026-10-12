package org.traducao.projeto.traducaoKaraoke.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * PROPÓSITO DE NEGÓCIO: a tipografia dos estilos é lida pela linha {@code Format:} do próprio
 * arquivo — a linha portuguesa da música se veste com o que o arquivo diz, não com uma ordem de
 * campos suposta.
 *
 * <h2>Invariantes do domínio congelados aqui</h2>
 * <ul>
 *   <li>A ordem dos campos vem do {@code Format:}; o SSA antigo ({@code [V4 Styles]},
 *       {@code TertiaryColour}) também é lido.</li>
 *   <li>Os valores voltam como TEXTO, exatamente como escritos ({@code 3.5}, não {@code 3.50}).</li>
 *   <li>A cor do estilo ({@code &HAABBGGRR}) vira o par de tags com a transparência.</li>
 * </ul>
 *
 * <h2>Comportamento em caso de falha</h2>
 * Cabeçalho sem estilos devolve mapa vazio; cor em formato desconhecido não vira tag.
 */
class EstiloAssTest {

    @Test
    @DisplayName("lê os estilos pela ordem do Format:, mesmo com os campos fora da ordem habitual")
    void lePelaOrdemDoFormat() {
        String cabecalho = String.join("\r\n",
            "[Script Info]",
            "Title: x",
            "",
            "[V4+ Styles]",
            "Format: Fontsize, Name, Outline, Fontname, Shadow, OutlineColour, BackColour",
            "Style: 70,Default,3.5,Gandhi Sans,1.5,&H00141414,&H96000000",
            "Style: 80,OPL2,0,Dash Horizon,0,&H00000000,&H00000000",
            "",
            "[Events]",
            "Format: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text");

        Map<String, EstiloAss> estilos = EstiloAss.doCabecalho(cabecalho);

        assertEquals(new EstiloAss("Default", "Gandhi Sans", "70", "&H00141414", "&H96000000", "3.5", "1.5"),
            estilos.get("Default"));
        assertEquals("Dash Horizon", estilos.get("OPL2").fonte());
        assertEquals(2, estilos.size());
    }

    @Test
    @DisplayName("SSA antigo: [V4 Styles] e o contorno em TertiaryColour")
    void ssaAntigo() {
        String cabecalho = String.join("\n",
            "[V4 Styles]",
            "Format: Name, Fontname, Fontsize, PrimaryColour, SecondaryColour, TertiaryColour, BackColour, Outline, Shadow",
            "Style: Default,Arial,24,&HFFFFFF,&H00FFFF,&H000000,&H000000,2,1");

        EstiloAss d = EstiloAss.doCabecalho(cabecalho).get("Default");

        assertEquals("Arial", d.fonte());
        assertEquals("&H000000", d.corContorno());
    }

    @Test
    @DisplayName("procura como o player: nome exato primeiro, depois sem diferenciar maiúsculas")
    void procuraComoOPlayer() {
        EstiloAss exato = new EstiloAss("OP", "A", null, null, null, null, null);
        EstiloAss outro = new EstiloAss("op", "B", null, null, null, null, null);
        Map<String, EstiloAss> estilos = new java.util.LinkedHashMap<>();
        estilos.put("op", outro);
        estilos.put("OP", exato);

        assertSame(exato, EstiloAss.procurar(estilos, "OP"));
        assertSame(outro, EstiloAss.procurar(estilos, "op"));
        assertEquals("A", EstiloAss.procurar(Map.of("OP", exato), "Op").fonte());
        assertNull(EstiloAss.procurar(estilos, "ED"));
        assertNull(EstiloAss.procurar(null, "OP"));
    }

    @Test
    @DisplayName("cor do estilo vira cor e transparência em tag; formato estranho não vira nada")
    void corEmTag() {
        assertEquals("\\3c&H141414&\\3a&H00&", EstiloAss.corEmTag("&H00141414", 3));
        assertEquals("\\4c&H000000&\\4a&H96&", EstiloAss.corEmTag("&H96000000", 4));
        assertEquals("\\3c&H0000FF&\\3a&H00&", EstiloAss.corEmTag("&H0000FF", 3));
        assertEquals("", EstiloAss.corEmTag("vermelho", 3));
        assertEquals("", EstiloAss.corEmTag(null, 3));
    }

    @Test
    @DisplayName("cabeçalho sem estilos ou nulo devolve mapa vazio")
    void semEstilos() {
        assertTrue(EstiloAss.doCabecalho(null).isEmpty());
        assertTrue(EstiloAss.doCabecalho("[Script Info]\nTitle: x\n[Events]").isEmpty());
    }
}
