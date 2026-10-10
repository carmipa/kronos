package org.traducao.projeto.traducao.application;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.traducao.projeto.qualidadeTraducao.application.LoreAtivaFake;

/**
 * PROPÓSITO DE NEGÓCIO: trava a invariância numérica (Bug 3) — o valor de um identificador da
 * fonte não pode mudar na tradução. O caso que motivou a regra é real, da corrida de
 * 2026-07-22: {@code "04th Team!"} foi publicado como {@code "Equipe 08!"}, provavelmente por
 * contaminação da lore da obra ({@code 08th MS Team}), sem rastro em log ou telemetria.
 *
 * <h2>Invariantes do domínio</h2>
 * <ul>
 *   <li>Reescrita de FORMATO é aceita: milhar, vírgula decimal e ordinal (EN e PT).</li>
 *   <li>Mudança de VALOR é reprovada, mesmo que a fala pareça bem traduzida.</li>
 *   <li>Números de tags ASS são formatação e não entram na comparação.</li>
 * </ul>
 *
 * <h2>Comportamento em caso de falha</h2>
 * Se um caso de reescrita legítima passar a reprovar, a regra virou falso-positivo; se a troca
 * de valor passar a ser aceita, a blindagem sumiu.
 */
@DisplayName("Bug 3: invariância de identificador numérico entre original e tradução")
class VerificadorIdentificadorNumericoTest {

    private final VerificadorIdentificadorNumerico verificador = new VerificadorIdentificadorNumerico(LoreAtivaFake.vazia());

    @Test
    @DisplayName("caso real: 04th Team publicado como Equipe 08 é reprovado")
    void trocaDeNumeroDeUnidadeEhReprovada() {
        String motivo = verificador.divergencia(
            "{\\i1}04th Team!\\NYou ready to move out yet?!{\\i}",
            "{\\i1}Equipe 08! Vocês já estão\\Nprontos para partir?!{\\i}");

        assertNotNull(motivo, "trocar 04 por 08 corrompe o sentido e não pode ser publicado");
        assertTrue(motivo.contains("identificador numérico"),
            "a mensagem precisa ser reconhecível pelo classificador de causa: " + motivo);
        assertTrue(motivo.contains("04"), "o diagnóstico deve nomear o valor perdido: " + motivo);
    }

    @Test
    @DisplayName("ordinal inglês vira ordinal português: 04th -> 04ª é aceito")
    void ordinalPortuguesEhAceito() {
        assertNull(verificador.divergencia("{\\i1}04th Team!{\\i}", "{\\i1}04ª Equipe!{\\i}"));
        assertNull(verificador.divergencia("May 12th", "12 de maio"));
        assertNull(verificador.divergencia("September 1st", "1º de setembro"));
    }

    @Test
    @DisplayName("separador de milhar e vírgula decimal são reescrita de formato, não perda")
    void reescritaDeFormatoNumericoEhAceita() {
        assertNull(verificador.divergencia(
            "{\\i1}Currently holding at\\Nan altitude of 9500!", "{\\i1}Mantendo uma altitude de 9.500!"));
        assertNull(verificador.divergencia("Wind speed: 0.5 to 0.6.", "Velocidade do vento: 0,5 a 0,6."));
        assertNull(verificador.divergencia(
            "less than 1/30th that of the Earth Federation.", "menos de 1/30 da Federação Terrestre."));
    }

    @Test
    @DisplayName("número TROCADO por outro em algarismos é reprovado")
    void numeroTrocadoEhReprovado() {
        assertNotNull(verificador.divergencia("Range 2300. Two mobile suits.", "Alcance 3200. Dois mobile suits."));
    }

    /**
     * Casos REAIS da corrida de 2026-07-22 que a primeira versão da regra reprovou por engano:
     * eram 22 falas boas, quase todas verso de música e contagem regressiva, em que a tradução
     * escreveu o número por extenso. Escrever "dez" para "10" é português legítimo, não
     * corrupção — e é o espelho exato do caso que já se tolerava ("eighteen" → "18").
     */
    @Test
    @DisplayName("número escrito por extenso na tradução é aceito (regressão real)")
    void numeroPorExtensoEhAceito() {
        assertNull(verificador.divergencia(
            "{\\fad(100,100)\\blur1\\bord0}10 years after, a decade from now.",
            "{\\fad(100,100)\\blur1\\bord0}Dez anos depois, uma década a partir de agora."));
        assertNull(verificador.divergencia("{\\i1}2... 1... 0!{\\i0}", "{\\i1}Dois... um... zero!{\\i0}"));
        assertNull(verificador.divergencia("{\\i1}98! 97!", "{\\i1}Noventa e oito! Noventa e sete!"));
        assertNull(verificador.divergencia("120...", "Cento e vinte..."));
    }

    @Test
    @DisplayName("representação mista não reprova quando nenhum valor estranho aparece")
    void representacaoMistaNaoReprova() {
        assertNull(verificador.divergencia("10 years, 20 dead.", "Dez anos, 20 mortos."),
            "o 10 virou palavra e o 20 seguiu algarismo: nenhum valor foi substituído");
    }

    @Test
    @DisplayName("números de tags ASS são formatação e não contam")
    void numerosDeTagsAssNaoContam() {
        assertNull(verificador.divergencia(
            "{\\blur1\\pos(720,650)\\c&HDEDEE3&}GUNDAMS IN THE JUNGLE",
            "{\\blur1\\pos(720,650)\\c&HDEDEE3&}GUNDAMS NA SELVA"));
    }

    @Test
    @DisplayName("fala sem número nunca reprova")
    void falaSemNumeroNuncaReprova() {
        assertNull(verificador.divergencia("A bridge, huh?", "Uma ponte, é?"));
        assertNull(verificador.divergencia(null, "x"));
        assertNull(verificador.divergencia("x", null));
    }

    @Test
    @DisplayName("invenção de número é tolerada (limite declarado da regra)")
    void invencaoDeNumeroEhTolerada() {
        assertNull(verificador.divergencia("The eighteen survivors.", "Os 18 sobreviventes."),
            "numeralizar quantidade escrita por extenso é tradução legítima, não corrupção");
    }

    /**
     * Break Blade, filme 2 (10/10/2026): {@code "2,000 soldiers"} vertido como {@code "2 mil
     * soldados"} ficava pendente e a fala saía em INGLÊS. Os dois lados da fronteira têm o mesmo
     * sinal (algarismo + "mil"); só a conta separa.
     */
    @Test
    @DisplayName("multiplicador por extenso: 2,000 -> 2 mil aceito; 3 mil e '2 soldados' reprovados")
    void multiplicadorPorExtensoConfereAConta() {
        assertNull(verificador.divergencia("We've already lost 2,000 soldiers.", "Já perdemos 2 mil soldados."));
        assertNull(verificador.divergencia("1,500 Golems.", "1,5 mil Golems."));
        assertNull(verificador.divergencia("2,000,000 people.", "2 milhões de pessoas."));
        assertNotNull(verificador.divergencia("We've already lost 2,000 soldiers.", "Já perdemos 3 mil soldados."),
            "CONTROLE: a conta nao fecha (3 x 1000 != 2000)");
        assertNotNull(verificador.divergencia("We've already lost 2,000 soldiers.", "Já perdemos 2 soldados."),
            "CONTROLE: sem o multiplicador, 2 continua sendo outro valor");
    }

    @Test
    @DisplayName("sobrevive(): 500 não pode ser dado como presente dentro de 12500")
    void fronteiraDeDigitoValeNasDuasPontas() {
        assertTrue(verificador.sobrevive("500", "Alcance 500 metros."));
        assertTrue(!verificador.sobrevive("500", "Alcance 12500 metros."));
    }
}
