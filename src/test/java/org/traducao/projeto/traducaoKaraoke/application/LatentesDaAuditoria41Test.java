package org.traducao.projeto.traducaoKaraoke.application;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.traducao.projeto.legenda.application.DetectorEfeitoKaraokeService;
import org.traducao.projeto.legenda.domain.DocumentoLegenda;
import org.traducao.projeto.legenda.infrastructure.LeitorLegendaAss;
import org.traducao.projeto.traducaoKaraoke.domain.ClasseLinhaKaraoke;
import org.traducao.projeto.traducaoKaraoke.domain.EstiloAss;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * PROPÓSITO DE NEGÓCIO: os mecanismos latentes da auditoria da 4.1 (28/10/2026) que Paulo mandou
 * fechar junto ("autorizado a fazer tudo"): nenhum tem ocorrência no acervo medido, cada um tem
 * entrada mínima que o reproduz e o contra-caso com o mesmo sinal.
 *
 * <p>INVARIANTES DO DOMÍNIO: o português com acento continua reconhecido; a letra original de
 * verdade continua preservando; a cor hexadecimal do ASS continua igual.
 *
 * <p>COMPORTAMENTO EM CASO DE FALHA: a asserção nomeia o caso.
 */
class LatentesDaAuditoria41Test {

    private final ClassificadorLetraKaraokeService classificador =
        new ClassificadorLetraKaraokeService(new DetectorEfeitoKaraokeService());

    @Test
    @DisplayName("A5: romaji com circunflexo nao vira 'ja portugues'; ingles com acento tambem nao")
    void circunflexoDoRomajiNaoEhPortugues() {
        assertEquals(ClasseLinhaKaraoke.ORIGINAL_JAPONES, classificador.classificar("ED", "kyô mo ashita mo"));
        assertEquals(ClasseLinhaKaraoke.TRADUZIVEL_INGLES,
            classificador.classificar("ED - EN", "Déjà vu, I've just been here before"));
    }

    @Test
    @DisplayName("A5 contra-caso: portugues com acento continua 'ja portugues'")
    void portuguesComAcentoContinua() {
        assertEquals(ClasseLinhaKaraoke.JA_PORTUGUES, classificador.classificar("ED - EN", "Você não está sozinho"));
        assertEquals(ClasseLinhaKaraoke.JA_PORTUGUES, classificador.classificar("ED - EN", "Três avós no avô"));
    }

    @Test
    @DisplayName("A9: linha-marcador 'OP' nao vai ao LLM")
    void marcadorNaoVaiAoLlm() {
        assertEquals(ClasseLinhaKaraoke.EFEITO_KFX, classificador.classificar("OP - English", "\\NOP"));
        assertEquals(ClasseLinhaKaraoke.TRADUZIVEL_INGLES, classificador.classificar("OP - English", "Open your eyes"));
    }

    @Test
    @DisplayName("A8: cor decimal do SSA v4 vira a mesma cor em hexadecimal; hexadecimal do ASS fica igual")
    void corDecimalDoSsa() {
        assertEquals("\\3c&HFFFFFF&\\3a&H00&", EstiloAss.corEmTag("16777215", 3));
        assertEquals("\\3c&H0000FF&\\3a&H00&", EstiloAss.corEmTag("255", 3));
        assertEquals("\\3c&H00FF00&\\3a&H80&", EstiloAss.corEmTag("&H8000FF00", 3));
    }

    private DocumentoLegenda documento(Path pasta, String... dialogos) throws IOException {
        StringBuilder sb = new StringBuilder("[Script Info]\nTitle: t\n\n[Events]\n"
            + "Format: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text\n");
        for (String d : dialogos) {
            sb.append(d).append('\n');
        }
        Path f = pasta.resolve("ep.ass");
        Files.writeString(f, sb.toString(), StandardCharsets.UTF_8);
        return new LeitorLegendaAss().ler(f);
    }

    @Test
    @DisplayName("A7: credito em kanji nao faz a camada unica inglesa trocar no lugar (a letra original some)")
    void creditoNaoEhLetraOriginal(@TempDir Path pasta) throws IOException {
        DocumentoLegenda doc = documento(pasta,
            "Dialogue: 0,0:01:00.00,0:01:30.00,OP Credits,,0,0,0,,作詞・作曲 山田太郎",
            "Dialogue: 0,0:01:05.00,0:01:09.00,OP,,0,0,0,,Behind your mask");
        PlanoDeClassificacao plano = PlanoDeClassificacao.montar(doc, classificador);
        assertFalse(plano.temOriginalPreservadaNoInstante(doc.eventos().get(1)),
            "o credito nao e a letra: sem empilhar, a camada unica some da tela (Unicorn 22/23)");
    }

    @Test
    @DisplayName("A7 contra-caso: romaji de verdade no mesmo instante continua preservando")
    void romajiDeVerdadeContinua(@TempDir Path pasta) throws IOException {
        DocumentoLegenda doc = documento(pasta,
            "Dialogue: 0,0:01:00.00,0:01:05.00,OP - Romaji,,0,0,0,,kimi no koe wo wasure wa shinai",
            "Dialogue: 0,0:01:00.00,0:01:05.00,OP,,0,0,0,,I will never forget your voice");
        assertTrue(PlanoDeClassificacao.montar(doc, classificador).temOriginalPreservadaNoInstante(doc.eventos().get(1)));
    }
}
