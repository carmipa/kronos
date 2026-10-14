package org.traducao.projeto.core.texto;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A regra única de fonte vietnamita antiga (14/10/2026). Cada caso doente vem do acervo medido; cada
 * controle legítimo carrega o MESMO sinal superficial (começa com V/Vn, estilo existe, \fn existe) e
 * tem de passar — guarda que reprova o legítimo é pior que guarda nenhuma (A1).
 */
class FonteVietnamitaLegadaTest {

    private static final String CABECALHO_ZZ = String.join("\n",
        "[Script Info]",
        "ScriptType: v4.00+",
        "",
        "[V4+ Styles]",
        "Format: Name, Fontname, Fontsize, PrimaryColour, SecondaryColour, OutlineColour, BackColour, Bold, Italic, "
            + "Underline, StrikeOut, ScaleX, ScaleY, Spacing, Angle, BorderStyle, Outline, Shadow, Alignment, MarginL, "
            + "MarginR, MarginV, Encoding",
        "Style: Default,Arial,60,&H00FFFFFF,&H0000FFFF,&H00000000,&H7F404040,-1,0,0,0,100,100,0,0,1,0,0,2,0,0,66,0",
        "Style: Dialogue,VnBook-Antiqua,75,&H00FFFFFF,&H000000FF,&H0007071A,&HDC060618,0,0,0,0,95,100,0,0,1,3.6,1.8,2,56,56,50,1",
        "Style: Song ENG,FrancophilSans,63,&H00FFFFFF,&H00000000,&H00000000,&H4BD3AE6A,-1,0,0,0,100,100,2,0,1,1,0,2,56,56,45,1");

    @Test
    @DisplayName("legada: TCVN3 com e sem ponto (o ZZ era sem), VNI e fonte vertical")
    void reconheceAsLegadasDoAcervo() {
        for (String f : List.of(".VnBook-Antiqua", "VnBook-Antiqua", ".VnTime", "@.VnTime", "VNI-Times", "VnArial",
                "VnTimes", "vnbook-antiqua", ".vntimeh", " @VnBook-Antiqua ")) {
            assertTrue(FonteVietnamitaLegada.ehLegada(f), f);
        }
    }

    @Test
    @DisplayName("fronteira A1: o mesmo 'começa com V/Vn' em fonte comum ou vietnamita Unicode NÃO é legada")
    void naoConfundeFonteComumComLegada() {
        for (String f : List.of("Vinque Sans", "Verdana", "VNF-Futura", "Vivaldi", "Arial", "FrancophilSans", "", "  ")) {
            assertFalse(FonteVietnamitaLegada.ehLegada(f), f);
        }
        assertFalse(FonteVietnamitaLegada.ehLegada(null));
    }

    @Test
    void estiloDoCabecalhoComFonteLegada() {
        assertEquals(Map.of("Dialogue", "VnBook-Antiqua"), FonteVietnamitaLegada.estilosComFonteLegada(CABECALHO_ZZ));
        assertTrue(FonteVietnamitaLegada.estilosComFonteLegada(null).isEmpty());
    }

    @Test
    @DisplayName("o ZZ de 09/10: estilo Dialogue em VnBook-Antiqua usado por fala reprova")
    void estiloLegadoUsadoNaTelaReprova() {
        List<String> achados = FonteVietnamitaLegada.achados(CABECALHO_ZZ, List.of(
            new FonteVietnamitaLegada.Fala("Dialogue", "Ouçam, galera!"),
            new FonteVietnamitaLegada.Fala("Song ENG", "Não é um anime.")));
        assertEquals(List.of("estilo \"Dialogue\" = VnBook-Antiqua"), achados);
    }

    /**
     * Paulo, 14/10/2026: "banir esse tipo de fonte". Estilo declarado e não usado hoje não desenha
     * nada, mas basta alguém (ou a própria 4.1, que escolhe a fonte do diálogo para a letra) passar a
     * usá-lo para a letra trocada voltar à tela sem nenhum aviso. Reprova e a 1.3 troca.
     */
    @Test
    @DisplayName("banida: estilo legado declarado que nenhuma fala usa também reprova, e o motivo diz que não está na tela")
    void estiloLegadoSemFalaTambemReprova() {
        assertEquals(List.of("estilo \"Dialogue\" = VnBook-Antiqua (nenhuma fala na tela usa)"),
            FonteVietnamitaLegada.achados(CABECALHO_ZZ, List.of(
                new FonteVietnamitaLegada.Fala("Song ENG", "Não é um anime."))));
    }

    @Test
    void fnLegadoDentroDaFalaReprovaEFnComumNao() {
        List<String> achados = FonteVietnamitaLegada.achados(CABECALHO_ZZ, List.of(
            new FonteVietnamitaLegada.Fala("Dialogue", "{\\fn.VnTime\\fs40}Placa"),
            new FonteVietnamitaLegada.Fala("Default", "{\\b1\\fn.VnTime}Outra"),
            new FonteVietnamitaLegada.Fala("Default", "{\\fnArial}Comum")));
        assertEquals(List.of("estilo \"Dialogue\" = VnBook-Antiqua", "\\fn.VnTime em 2 linha(s)"), achados);
    }

    @Test
    @DisplayName("banida: Comment com \\fn legado reprova no arquivo inteiro, como o estilo legado sem fala")
    void achadosNoArquivoInteiro() {
        String ass = "﻿" + CABECALHO_ZZ + "\r\n\r\n[Events]\r\n"
            + "Format: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text\r\n"
            + "Comment: 0,0:00:01.00,0:00:02.00,Default,,0,0,0,,{\\fnVNI-Times}nota\r\n"
            + "Dialogue: 0,0:00:03.00,0:00:04.00,Song ENG,,0,0,0,,Não é um anime, não é.\r\n";
        assertEquals(List.of("estilo \"Dialogue\" = VnBook-Antiqua (nenhuma fala na tela usa)",
            "\\fnVNI-Times em 1 linha(s)"), FonteVietnamitaLegada.achadosNoAss(ass));

        String comFala = ass + "Dialogue: 0,0:00:05.00,0:00:06.00,Dialogue,,0,0,0,,Irmão, é a atração!\r\n";
        assertEquals(List.of("estilo \"Dialogue\" = VnBook-Antiqua", "\\fnVNI-Times em 1 linha(s)"),
            FonteVietnamitaLegada.achadosNoAss(comFala));
        assertTrue(FonteVietnamitaLegada.achadosNoAss("sem eventos").isEmpty());
    }

    /**
     * A1 do banimento: o sinal que agora reprova (estilo que nenhuma fala usa, {@code \fn} dentro de
     * {@code Comment:}) aparece igualzinho com fonte legítima — vietnamita moderna em Unicode
     * ({@code VNF-}), {@code Vinque Sans}, {@code Verdana} — e isso tem de passar.
     */
    @Test
    @DisplayName("fronteira A1: estilo sem fala e \\fn em Comment com VNF-, Vinque Sans e Verdana não reprovam")
    void mesmoSinalComFonteLegitimaNaoReprova() {
        String linhaEstilo = "Style: Song ENG,FrancophilSans,63,&H00FFFFFF,&H00000000,&H00000000,&H4BD3AE6A,-1,0,0,0,"
            + "100,100,2,0,1,1,0,2,56,56,45,1";
        String cabecalho = CABECALHO_ZZ.replace("VnBook-Antiqua", "VNF-Futura")
            + "\n" + linhaEstilo.replace("Song ENG,FrancophilSans", "Placa,Vinque Sans")
            + "\n" + linhaEstilo.replace("Song ENG,FrancophilSans", "Nota,Verdana");
        String ass = cabecalho + "\n\n[Events]\n"
            + "Format: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text\n"
            + "Comment: 0,0:00:01.00,0:00:02.00,Nota,,0,0,0,,{\\fnVerdana}nota {\\fnVNF-Futura}outra\n"
            + "Comment: 0,0:00:02.00,0:00:03.00,Default,,0,0,0,,{\\fnVinque Sans}sinal\n"
            + "Dialogue: 0,0:00:03.00,0:00:04.00,Song ENG,,0,0,0,,Não é um anime, não é.\n";
        assertTrue(FonteVietnamitaLegada.achadosNoAss(ass).isEmpty(), FonteVietnamitaLegada.achadosNoAss(ass).toString());
    }

    @Test
    void trocaSoOFnLegadoEPreservaOResto() {
        assertEquals("{\\b1\\fnArial\\fs40}Placa {\\fnVerdana}comum",
            FonteVietnamitaLegada.trocarFontesInlineLegadas("{\\b1\\fn.VnTime\\fs40}Placa {\\fnVerdana}comum", "Arial"));
        assertEquals("{\\fnArial}x", FonteVietnamitaLegada.trocarFontesInlineLegadas("{\\fnVNI-Times}x", "Arial"));
        assertEquals("sem tag", FonteVietnamitaLegada.trocarFontesInlineLegadas("sem tag", "Arial"));
        assertEquals("{\\fnVinque Sans}x", FonteVietnamitaLegada.trocarFontesInlineLegadas("{\\fnVinque Sans}x", "Arial"));
    }
}
