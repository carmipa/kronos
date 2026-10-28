package org.traducao.projeto.traducaoKaraoke.application;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.traducao.projeto.core.texto.dicionarioOrtografia.CorretorOrtograficoLegenda;
import org.traducao.projeto.core.texto.dicionarioOrtografia.VeredictoPalavra;
import org.traducao.projeto.legenda.application.DetectorEfeitoKaraokeService;
import org.traducao.projeto.legenda.domain.DocumentoLegenda;
import org.traducao.projeto.legenda.infrastructure.LeitorLegendaAss;
import org.traducao.projeto.traducaoKaraoke.domain.ClasseLinhaKaraoke;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * PROPÓSITO DE NEGÓCIO: letra que JÁ está em português num estilo que se declara inglês ("Eng")
 * não vai ao LLM como se fosse inglês. F91, auditoria da 4.1 de 28/10/2026: 37 entradas PT
 * reescritas pelo modelo e gravadas como {@code en} ("aos recuerdos" => "aos lembranças"), porque
 * o reconhecimento de PT era uma lista de 26 palavras ou acento.
 *
 * <p>INVARIANTES DO DOMÍNIO (A1 — o legítimo com o mesmo sinal fica): inglês com palavras que o
 * pt_BR aceita ("Do you feel alone", "I see the sun") continua traduzível; romaji que o pt_BR
 * aceita inteiro ("ai suru anata ni sou", medido em 24/09) continua traduzível; sem dicionário,
 * nada muda.
 *
 * <p>COMPORTAMENTO EM CASO DE FALHA: a asserção diz qual linha saiu na classe errada.
 */
class LetraJaEmPortuguesNaoVaiAoLlmTest {

    private final ClassificadorLetraKaraokeService classificador =
        new ClassificadorLetraKaraokeService(new DetectorEfeitoKaraokeService());

    /** Veredictos do tipo que o hunspell real dá: o pt_BR aceita "do", "a", "sun", "ai", "anata"... */
    private static final class Dicionarios extends CorretorOrtograficoLegenda {
        private final Map<String, VeredictoPalavra> tabela = new HashMap<>();

        Dicionarios() {
            for (String p : ("Quase como fragmentos de tristeza Eu consigo ouvir sua voz E olhe nos meus olhos "
                + "Do a sun ai suru anata ni sou I").split(" ")) {
                tabela.put(p, VeredictoPalavra.PORTUGUES_OK);
            }
            tabela.put("tolas", VeredictoPalavra.PORTUGUES_OK);
            tabela.put("criaturas", VeredictoPalavra.PORTUGUES_OK);
            tabela.put("tornem-se", VeredictoPalavra.PORTUGUES_OK);
            for (String p : "you feel alone see the".split(" ")) {
                tabela.put(p, VeredictoPalavra.RESIDUO_INGLES);
            }
        }

        @Override
        public Map<String, VeredictoPalavra> classificarPalavras(Collection<String> palavras) {
            Map<String, VeredictoPalavra> out = new HashMap<>();
            for (String p : palavras) {
                out.put(p, tabela.getOrDefault(p, VeredictoPalavra.DESCONHECIDA));
            }
            return out;
        }

        @Override
        public boolean inglesDisponivel() {
            return true;
        }
    }

    private DocumentoLegenda documento(Path pasta, List<String> textos) throws IOException {
        StringBuilder sb = new StringBuilder("[Script Info]\nTitle: t\n\n[Events]\n"
            + "Format: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text\n");
        int s = 1;
        for (String t : textos) {
            sb.append(String.format("Dialogue: 0,0:01:%02d.00,0:01:%02d.50,Eng,,0,0,0,,%s%n", s, s, t));
            s += 2;
        }
        Path f = pasta.resolve("ep.ass");
        Files.writeString(f, sb.toString(), StandardCharsets.UTF_8);
        return new LeitorLegendaAss().ler(f);
    }

    @Test
    @DisplayName("A1: PT sem acento em estilo Eng sai JA_PORTUGUES; ingles e romaji que o pt_BR aceita continuam traduziveis")
    void ptSemAcentoNaoVaiAoLlm(@TempDir Path pasta) throws IOException {
        List<String> textos = List.of(
            "Quase como fragmentos de tristeza...",
            "Eu consigo ouvir sua voz.",
            "...tornem-se criaturas tolas.",
            "Do you feel alone",
            "I see the sun",
            "ai suru anata ni sou");
        DocumentoLegenda doc = documento(pasta, textos);

        PlanoDeClassificacao plano = PlanoDeClassificacao.montar(doc, classificador, new Dicionarios());

        List<ClasseLinhaKaraoke> esperado = List.of(
            ClasseLinhaKaraoke.JA_PORTUGUES, ClasseLinhaKaraoke.JA_PORTUGUES, ClasseLinhaKaraoke.JA_PORTUGUES,
            ClasseLinhaKaraoke.TRADUZIVEL_INGLES, ClasseLinhaKaraoke.TRADUZIVEL_INGLES, ClasseLinhaKaraoke.TRADUZIVEL_INGLES);
        for (int i = 0; i < textos.size(); i++) {
            assertEquals(esperado.get(i), plano.classeNaPosicao(i), "linha: " + textos.get(i));
        }
    }

    @Test
    @DisplayName("A1: sem dicionario o plano fica como era (falha fechada)")
    void semDicionarioNadaMuda(@TempDir Path pasta) throws IOException {
        DocumentoLegenda doc = documento(pasta, List.of("Eu consigo ouvir sua voz."));

        assertEquals(ClasseLinhaKaraoke.TRADUZIVEL_INGLES,
            PlanoDeClassificacao.montar(doc, classificador, null).classeNaPosicao(0));
    }
}
