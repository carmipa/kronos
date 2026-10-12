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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * PROPÓSITO DE NEGÓCIO: a letra ORIGINAL desenhada sílaba a sílaba, sem linha inteira, conta como
 * "original na tela" — e a tradução inglesa do fansub vira só português no lugar, em vez de empilhar.
 *
 * <h2>O prejuízo que originou, medido em 12/10/2026</h2>
 * Encerramento do 86 (Partes 1 e 2): o romaji "Silent haze kasumi gachi ni toraeru kage" sobe em
 * sílabas, três camadas cada, no alto da tela; a 4.1 não o reconhecia e empilhava
 * {@code inglês\Nportuguês}. Na tela: romaji + "To shadows stalled in a silent haze" + português.
 * Regra de Paulo: o inglês do fansub sai, fica só o português.
 *
 * <h2>Invariantes do domínio congelados aqui (linhas reais do 86 Parte 1 E02)</h2>
 * <ul>
 *   <li>Romaji em sílabas aceso no meio da linha inglesa: o original está na tela.</li>
 *   <li><b>Fronteira (A1):</b> o mesmo sinal — sílabas acesas no meio — com sílabas INGLESAS não
 *       conta: a letra cantada em inglês continua empilhando.</li>
 *   <li>Menos de {@value PlanoDeClassificacao#MINIMO_SILABAS_DO_ORIGINAL} sílabas distintas não
 *       decidem; as três camadas da mesma sílaba contam uma vez.</li>
 * </ul>
 *
 * <h2>Comportamento em caso de falha</h2>
 * A mensagem diz qual lado da fronteira cedeu.
 */
class RomajiEmSilabasTest {

    private static final String LINHA_INGLESA =
        "{=182}{\\fad(80,80)\\pos(1860,125)\\blur1\\bord3\\3c&H00FFFFFF&}To shadows stalled in a silent haze";

    private static int proximo = 0;

    private static EventoLegenda evento(int camada, String inicio, String fim, String texto) {
        return new EventoLegenda(proximo++, "Dialogue", "Ending",
            "Dialogue: " + camada + "," + inicio + "," + fim + ",Ending,,0,0,0,,", texto);
    }

    /**
     * Os tempos REAIS das sílabas do verso no 86 Parte 1 E02 (início, fim): cada sílaba apaga num
     * instante diferente. Com todas terminando junto com a linha, alguma sílaba passaria por "linha
     * original do mesmo momento" e a regra antiga bastaria — o teste ficaria verde nos dois mundos.
     */
    static final String[][] TEMPOS_REAIS = {
        {"0:18:08.63", "0:18:14.32"}, {"0:18:08.79", "0:18:14.32"}, {"0:18:09.21", "0:18:14.32"},
        {"0:18:08.34", "0:18:09.59"}, {"0:18:08.34", "0:18:09.92"}, {"0:18:08.34", "0:18:10.05"},
        {"0:18:08.34", "0:18:10.38"}, {"0:18:08.34", "0:18:10.55"}, {"0:18:08.34", "0:18:10.80"},
        {"0:18:08.34", "0:18:11.13"}, {"0:18:08.34", "0:18:11.55"}, {"0:18:08.34", "0:18:12.34"},
        {"0:18:08.34", "0:18:12.46"}, {"0:18:08.34", "0:18:12.88"}, {"0:18:08.34", "0:18:13.05"},
    };

    /** As sílabas do verso, cada uma nas camadas de contorno e de cor, como no arquivo real. */
    private static List<EventoLegenda> silabas(String... textos) {
        List<EventoLegenda> lista = new ArrayList<>();
        int x = 1131;
        for (int k = 0; k < textos.length; k++) {
            String[] t = TEMPOS_REAIS[k % TEMPOS_REAIS.length];
            lista.add(evento(1, t[0], t[1], "{\\fad(80,0)\\pos(" + x + ",40)\\3c&H00FFFFFF&\\blur0.6\\bord3}" + textos[k]));
            lista.add(evento(2, t[0], t[1], "{\\fad(80,0)\\pos(" + x + ",40)\\c&H009C100E&\\blur0.6\\c&H009C100E&}" + textos[k]));
            x += 50;
        }
        return lista;
    }

    private static PlanoDeClassificacao planoCom(List<EventoLegenda> silabas) {
        List<EventoLegenda> eventos = new ArrayList<>();
        eventos.add(evento(1, "0:18:08.36", "0:18:14.32", LINHA_INGLESA));
        eventos.addAll(silabas);
        return PlanoDeClassificacao.montar(new DocumentoLegenda("[Events]", eventos, "\n", false),
            new ClassificadorLetraKaraokeService(new DetectorEfeitoKaraokeService()));
    }

    @Test
    @DisplayName("86: romaji em sílabas aceso no meio da linha inglesa é o original na tela")
    void romajiEmSilabasEhOriginalNaTela() {
        PlanoDeClassificacao plano = planoCom(silabas(
            "Si", "lent", "haze", "ka", "su", "mi", "ga", "chi", "ni", "to", "ra", "e", "ru", "ka", "ge"));

        assertEquals(ClasseLinhaKaraoke.TRADUZIVEL_INGLES, plano.classeNaPosicao(0),
            "a traducao do fansub tem de continuar indo ao portugues");
        assertEquals(ClasseLinhaKaraoke.EFEITO_KFX, plano.classeNaPosicao(1), "a silaba e efeito");
        assertTrue(plano.temOriginalEmSilabasNaPosicao(0),
            "o romaji esta na tela em silabas e o ingles voltaria a empilhar: romaji + ingles + portugues");
    }

    /** Fronteira A1: o mesmo sinal (sílabas acesas no meio), só que inglesas. */
    @Test
    @DisplayName("fronteira: sílabas INGLESAS acesas não contam — a letra cantada em inglês continua empilhando")
    void silabasInglesasNaoContam() {
        // Quinze, como o verso romaji: com os tempos reais ficam OITO acesas no meio da linha — o
        // mínimo de sílabas passa, e quem decide é o critério de romaji (a fronteira de verdade).
        PlanoDeClassificacao plano = planoCom(silabas("Do", "you", "feel", "a", "lone", "now", "on", "earth",
            "in", "the", "dark", "where", "no", "one", "hears"));

        assertFalse(plano.temOriginalEmSilabasNaPosicao(0),
            "silaba inglesa contou como original: o verso cantado em ingles perderia a letra da tela");
    }

    @Test
    @DisplayName("menos de quatro sílabas distintas não decidem, e as camadas da mesma sílaba contam uma vez")
    void poucasSilabasNaoDecidem() {
        assertFalse(planoCom(silabas("ka", "su", "mi")).temOriginalEmSilabasNaPosicao(0),
            "tres silabas (seis eventos com as camadas) decidiram sozinhas");
        assertFalse(planoCom(List.of()).temOriginalEmSilabasNaPosicao(0), "sem silaba nenhuma");
        assertFalse(planoCom(silabas("ka", "su", "mi", "ga")).temOriginalEmSilabasNaPosicao(99),
            "posicao fora do documento");
    }

    @Test
    @DisplayName("sílaba que já apagou antes do meio da linha não conta")
    void silabaApagadaNaoConta() {
        List<EventoLegenda> apagadas = new ArrayList<>();
        for (String s : new String[] {"ka", "su", "mi", "ga", "chi", "ni"}) {
            apagadas.add(evento(1, "0:18:08.34", "0:18:09.00", "{\\pos(1365,40)\\bord3}" + s));
        }
        assertFalse(planoCom(apagadas).temOriginalEmSilabasNaPosicao(0),
            "silabas do verso ANTERIOR, ja apagadas, decidiram por este");
    }
}
