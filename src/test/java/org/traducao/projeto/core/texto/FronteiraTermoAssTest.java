package org.traducao.projeto.core.texto;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * PROPÓSITO DE NEGÓCIO: congela a mecânica de casamento de termo em legenda ASS, agora que ela
 * tem UM dono. Antes de 2026-08-04 a regra vivia em 12 arquivos de 7 fatias e as duas metades —
 * fronteira e separador interno — estavam em lugares diferentes, o que corrompeu tradução correta.
 *
 * <h2>Invariantes do domínio</h2>
 * <ul>
 *   <li>A quebra é separador nas DUAS posições: colada à fronteira e dentro do termo composto.</li>
 *   <li>Termo de uma palavra se comporta exatamente como antes — nada muda para nome simples.</li>
 *   <li>Metacaractere de regex no termo continua literal ({@code "Gaza-C"}, {@code "A.E.U.G."}).</li>
 *   <li>A fronteira não vira "casa em qualquer lugar": sufixo e prefixo continuam recusados.</li>
 * </ul>
 *
 * <h2>Comportamento em caso de falha</h2>
 * Qualquer uma destas quebrando significa que termo de lore some da vista de todo o pipeline —
 * detecção, proteção, reparo e enforcement usam esta mesma mecânica.
 */
class FronteiraTermoAssTest {

    /** Quebra do ASS montada em runtime para o literal não se confundir com escape do fonte. */
    private static final String QUEBRA = "\\" + "N";

    @Test
    @DisplayName("termo colado na quebra e encontrado")
    void quebraNaFronteira() {
        assertTrue(FronteiraTermoAss.padrao("Argama")
            .matcher("no espaço aéreo da" + QUEBRA + "Argama!").find(),
            "o N da quebra e letra para \\p{L}; sem a alternativa o termo vira sufixo de NArgama");
    }

    @Test
    @DisplayName("nome composto partido pela quebra e encontrado")
    void quebraDentroDoTermo() {
        assertTrue(FronteiraTermoAss.padrao("Nahel Argama")
            .matcher("perto da Nahel" + QUEBRA + "Argama!").find(),
            "Pattern.quote procura um ESPACO literal que nao esta la quando o fansub parte o nome");
    }

    @Test
    @DisplayName("as duas metades juntas: quebra na fronteira E dentro do termo")
    void quebraNasDuasPosicoes() {
        assertTrue(FronteiraTermoAss.padrao("Nahel Argama")
            .matcher("perto da" + QUEBRA + "Nahel" + QUEBRA + "Argama!").find(),
            "ter so uma das metades foi o que corrompeu traducao correta em 04/08/2026");
    }

    @Test
    @DisplayName("fronteira continua recusando sufixo e prefixo")
    void naoCasaDentroDeOutraPalavra() {
        assertFalse(FronteiraTermoAss.padrao("Zeon").matcher("a tecnologia Zeonic").find(),
            "aceitar a quebra nao pode virar 'casa em qualquer lugar'");
        assertFalse(FronteiraTermoAss.padrao("Argama").matcher("NeoArgama").find());
    }

    @Test
    @DisplayName("o termo nao comeca no N da propria quebra (\\Nao nao e 'Nao')")
    void naoComecaNaLetraDaQuebra() {
        assertFalse(FronteiraTermoAss.padrao("Nao")
                .matcher("essa informação" + QUEBRA + "ao controle").find(),
            "14/10, Reconguista I: o N da quebra mais o 'ao' seguinte viraram 'Nao', e o normalizador "
                + "escreveu \\Não — a tela mostrava 'ão controle'");
        assertTrue(FronteiraTermoAss.padrao("Nao")
                .matcher("eu" + QUEBRA + "Nao sei").find(),
            "FRONTEIRA: o termo de verdade logo depois da quebra continua encontrado");
    }

    @Test
    @DisplayName("termo de UMA palavra produz o padrao de sempre")
    void termoSimplesNaoMuda() {
        assertEquals("\\QArgama\\E", FronteiraTermoAss.corpo("Argama"),
            "sem separador interno para inserir, o corpo e o quote puro");
    }

    @Test
    @DisplayName("metacaractere de regex no termo continua literal")
    void metacaractereEscapado() {
        assertTrue(FronteiraTermoAss.padrao("Gaza-C").matcher("um Gaza-C apareceu").find());
        assertFalse(FronteiraTermoAss.padrao("Gaza-C").matcher("um GazaXC apareceu").find(),
            "o hifen tem de ser literal, nao classe de caractere");
        assertTrue(FronteiraTermoAss.padrao("A.E.U.G.").matcher("da A.E.U.G. veio").find());
        assertFalse(FronteiraTermoAss.padrao("A.E.U.G.").matcher("da AXEXUXGX veio").find(),
            "o ponto tem de ser literal, nao coringa");
    }

    @Test
    @DisplayName("ignorar caixa e opcional, nao padrao")
    void caixaSoQuandoPedida() {
        assertFalse(FronteiraTermoAss.padrao("Four").matcher("o four ali").find(),
            "sensivel a caixa separa o nome proprio Four do numeral four");
        assertTrue(FronteiraTermoAss.padraoIgnorandoCaixa("Four").matcher("o four ali").find());
    }

    @Test
    @DisplayName("troca de termo partido pela quebra devolve a quebra no mesmo lugar")
    void trocaPreservaAQuebraDentroDoTermo() {
        assertEquals("Normal\\NSuits", FronteiraTermoAss.substituirPreservandoQuebra("trajes\\Nnormais", "Normal Suits"));
        assertEquals("Mobile\\NSuit", FronteiraTermoAss.substituirPreservandoQuebra("Mobile\\NSuit", "Mobile Suit"));
        assertEquals("Século\\NUniversal", FronteiraTermoAss.substituirPreservandoQuebra("Universal \\N Century", "Século Universal"));
        assertEquals("Zeta Gundam\\NMk II", FronteiraTermoAss.substituirPreservandoQuebra("ZZ Gundam\\NMk II", "Zeta Gundam Mk II"));
        // substituto com MENOS palavras: a quebra nao some, vai para o fim do termo
        assertEquals("Axis\\N", FronteiraTermoAss.substituirPreservandoQuebra("o\\NEixo", "Axis"));
        // sem quebra no casado: o substituto passa como veio
        assertEquals("Mobile Suit", FronteiraTermoAss.substituirPreservandoQuebra("traje movel", "Mobile Suit"));
        assertEquals("Mobile Suit", FronteiraTermoAss.substituirPreservandoQuebra(null, "Mobile Suit"));
    }

    @Test
    @DisplayName("termo nulo ou em branco nao casa com nada")
    void degradaSemLancar() {
        assertEquals("", FronteiraTermoAss.corpo(null));
        assertEquals("", FronteiraTermoAss.corpo("   "));
        assertFalse(FronteiraTermoAss.padrao(null).matcher("qualquer texto").find(),
            "corpo vazio casaria em TODA posicao se a fronteira nao segurasse");
    }
}
