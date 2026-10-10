package org.traducao.projeto.qualidadeTraducao.application;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.traducao.projeto.qualidadeTraducao.domain.AlucinacaoDetectadaException;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * PROPÓSITO DE NEGÓCIO: prova as três reprovações de SENTIDO que o portão de par ganhou em
 * 08/10/2026 — eco (inclusive com aspas que o normalizador tira depois), polaridade invertida e
 * pergunta perdida. Todas têm português limpo na saída; só o original prova o defeito.
 *
 * <p>INVARIANTES DO DOMÍNIO: cada caso RUIM saiu do acervo real (caches de 08/10) ou da tradução
 * do zero de 08/10; cada caso BOM carrega o MESMO sinal do ruim (A1) — a mesma abertura "No", o
 * mesmo "?", a mesma fala idêntica — e tem de passar.
 *
 * <p>COMPORTAMENTO EM CASO DE FALHA: reprovação de um caso BOM é falso positivo (a fala iria para
 * nova tentativa e, esgotada, sairia em inglês); aprovação de um RUIM é a regressão do defeito.
 */
class ValidadorSentidoDaFalaTest {

    private final ValidadorTraducaoService validador = new ValidadorTraducaoService(LoreAtivaFake.vazia());

    @ParameterizedTest(name = "[{index}] RUIM polaridade: {0} -> {1}")
    @DisplayName("polaridade invertida: o original diz não e a legenda diz sim — reprova")
    @CsvSource(delimiter = '|', value = {
        "No.|Sim.",
        "No... it's too late for that. It's selfish.|Sim... é tarde demais para isso. É egoísta.",
        "No... There are still men who even now call it that.|Sim... Ainda há homens que até agora a chamam assim.",
        "No, I'd also like to believe it.|Sim, também gostaria de acreditar nisso.",
        "N-No.|S-Sim."
    })
    void polaridadeInvertidaReprova(String original, String traduzido) {
        AlucinacaoDetectadaException e = assertThrows(AlucinacaoDetectadaException.class,
            () -> validador.validarPar(original, traduzido));
        assertTrue(e.getMessage().startsWith("Polaridade invertida"), e.getMessage());
    }

    @ParameterizedTest(name = "[{index}] BOM polaridade: {0} -> {1}")
    @DisplayName("A1: mesma abertura negativa, resposta certa — passa")
    @CsvSource(delimiter = '|', value = {
        "No.|Não.",
        "No way!|Claro que não!",
        "No problem.|Claro, sem problema.",
        "No, I'd also like to believe it.|Não, eu também gostaria de acreditar nisso.",
        "Nope.|Nem pensar.",
        "Nobody came.|Ninguém veio.",
        "Not yet.|Certo, ainda não."
    })
    void respostaNegativaCertaPassa(String original, String traduzido) {
        assertDoesNotThrow(() -> validador.validarPar(original, traduzido));
    }

    @ParameterizedTest(name = "[{index}] RUIM pergunta: {0} -> {1}")
    @DisplayName("pergunta perdida: o original pergunta e a legenda afirma — reprova")
    // a aspa simples e o caractere de citacao padrao do CsvSource; "'Our\"?" e dado real do 0083
    @CsvSource(delimiter = '|', quoteCharacter = '`', value = {
        "A rat?!|Que porco colorido!",
        "Ensign Reccoa is?|Reccoa é uma tenente.",
        "'Our\"?|Nosso",
        "\"His\"?|Dele.",
        "3:30? Only 30 minutes left!|Ainda faltam apenas 30 minutos!",
        "What good will creating Cyber-Newtypes do anyway?!|Criar Cyber-Newtypes só trará mais sofrimento!",
        "A date? Be glad to!|Que bom, sim!",
        "How about you?|E você."
    })
    void perguntaPerdidaReprova(String original, String traduzido) {
        AlucinacaoDetectadaException e = assertThrows(AlucinacaoDetectadaException.class,
            () -> validador.validarPar(original, traduzido));
        assertTrue(e.getMessage().startsWith("Pergunta perdida"), e.getMessage());
    }

    @ParameterizedTest(name = "[{index}] BOM pergunta: {0} -> {1}")
    @DisplayName("A1: o mesmo \"?\" no original, PT legítimo sem interrogação ou com ela — passa")
    @CsvSource(delimiter = '|', value = {
        "A rat?!|Um rato?!",
        "Better we take it apart than strangers do, right?|É melhor nós desmontarmos do que estranhos.",
        "You can't criticize me for using Mineva Zabi anymore, can you, Judau?|Você não pode mais me criticar, Judau.",
        "Didn't I tell you before that I've nothing to say to you?!|Eu já te disse que não tenho nada a dizer para você!",
        "Would you calm down?!|Acalme-se!",
        "Why are you allied with a Frankish warship?!|Por que você está aliado a um navio franco!",
        "I wonder if she made it to Earth all right?|Eu me pergunto se ela chegou à Terra bem.",
        "I suppose that means I'm not a fit commander?|Suponho que isso signifique que não sou um comandante à altura.",
        "You're an accomplice, remember?!|Você é cúmplice, lembre-se!",
        "How about you GET THE HELL OFF?!|E aí, você vai sair daqui agora mesmo!"
    })
    void perguntaVertidaLegitimamentePassa(String original, String traduzido) {
        assertDoesNotThrow(() -> validador.validarPar(original, traduzido));
    }

    @Test
    @DisplayName("eco com aspas: '\"Damn it!\"' é o inglês que o normalizador publicaria — reprova na tentativa")
    void ecoEnvoltoEmAspasReprova() {
        for (String eco : new String[]{"\"Damn it!\"", "Damn it!", "“Damn it!”"}) {
            AlucinacaoDetectadaException e = assertThrows(AlucinacaoDetectadaException.class,
                () -> validador.validarPar("Damn it!", eco), eco);
            assertTrue(e.getMessage().startsWith("modelo devolveu o texto original sem tradução"), e.getMessage());
        }
    }

    @Test
    @DisplayName("A1 do eco: nome devolvido igual (com ou sem aspas) e fonte que já citava — passam")
    void identicaLegitimaENaoEcoPassam() {
        assertDoesNotThrow(() -> validador.validarPar("Kamille!", "\"Kamille!\""));
        assertDoesNotThrow(() -> validador.validarPar("Kamille!", "Kamille!"));
        assertDoesNotThrow(() -> validador.validarPar("Damn it!", "Droga!"));
        assertDoesNotThrow(() -> validador.validarPar("\"Deliberately\"?", "\"Deliberadamente\"?"));
    }

    /** Os pares de REGISTRO SOCIAL declarados em 08/10 na lore do 0083 e do 86, com os casos medidos. */
    private final ValidadorTraducaoService comRegistro = new ValidadorTraducaoService(LoreAtivaFake.comPares(
        java.util.List.of("Uraki", "Kou"), java.util.List.of("Keith", "Chuck"), java.util.List.of("Handler One", "Lena")));

    @ParameterizedTest(name = "[{index}] registro trocado: {0} -> {1} (reparo: {2})")
    @DisplayName("registro social trocado: reprova e o reparo devolve o termo do original")
    @CsvSource(delimiter = '|', value = {
        "Uraki, you're with me.|Kou, você está comigo.|Uraki, você está comigo.",
        "Keith! Uraki!|Keith! Kou!|Keith! Uraki!",
        "Give it up, Keith.|Desista, Chuck.|Desista, Keith.",
        "Handler One to Pleiades:|Lena para Plêiades:|Handler One para Plêiades:",
        "Handler One.|Lena.|Handler One."
    })
    void registroSocialTrocadoReprovaERepara(String original, String traduzido, String reparadoEsperado) {
        AlucinacaoDetectadaException e = assertThrows(AlucinacaoDetectadaException.class,
            () -> comRegistro.validarPar(original, traduzido));
        assertTrue(e.getMessage().startsWith("Entidade trocada"), e.getMessage());
        org.junit.jupiter.api.Assertions.assertEquals(reparadoEsperado,
            comRegistro.repararTrocaDeEntidade(original, traduzido));
    }

    @ParameterizedTest(name = "[{index}] registro preservado: {0} -> {1}")
    @DisplayName("A1 do registro: o mesmo nome no original, tratamento preservado — passa")
    @CsvSource(delimiter = '|', value = {
        "Kou!|Kou!",
        "Uraki! Above you!|Uraki! Acima de você!",
        "Kou Uraki, reporting.|Kou Uraki, apresentando-se.",
        "Handler One to Undertaker:|Lena (Handler One) para Shin (Undertaker):",
        "Thank you, Nina.|Obrigado, Nina."
    })
    void registroPreservadoPassa(String original, String traduzido) {
        assertDoesNotThrow(() -> comRegistro.validarPar(original, traduzido));
    }

    @ParameterizedTest(name = "[{index}] reparo de polaridade: {0} | {1} -> {2}")
    @DisplayName("polaridade invertida com 'Sim' na abertura: o reparo devolve 'Não' e o resultado passa no portão")
    // os 5 casos do acervo (75.856 pares distintos), mais caixa, gagueira, tag e marcador
    @CsvSource(delimiter = '|', quoteCharacter = '`', value = {
        "No.|Sim.|Não.",
        "No... it's too late for that. It's selfish.|Sim... é tarde demais para isso. É egoísta.|Não... é tarde demais para isso. É egoísta.",
        "No... It's just that after all these battles, the men are exhausted.|Sim... É que, após todas essas batalhas, os homens estão exaustos.|Não... É que, após todas essas batalhas, os homens estão exaustos.",
        "No, I'd also like to believe it.|Sim, também gostaria de acreditar nisso.|Não, também gostaria de acreditar nisso.",
        "No... There are still men who even now call it that.|Sim... Ainda há homens que até agora a chamam assim.|Não... Ainda há homens que até agora a chamam assim.",
        "N-No.|S-Sim.|N-Não.",
        "NO!|SIM!|NÃO!",
        "{\\i1}No... There are still men{\\i0}|{\\i1}Sim... Ainda há homens{\\i0}|{\\i1}Não... Ainda há homens{\\i0}",
        "[[TAG0]]No.|[[TAG0]]Sim.|[[TAG0]]Não."
    })
    void polaridadeComSimNaAberturaEhReparada(String original, String traduzido, String esperado) {
        org.junit.jupiter.api.Assertions.assertEquals(esperado, validador.repararPar(original, traduzido));
        assertDoesNotThrow(() -> validador.validarPar(original, esperado));
    }

    @ParameterizedTest(name = "[{index}] sem reparo: {0} -> {1}")
    @DisplayName("A1 do reparo: mesmo sinal sem defeito, ou defeito sem conserto seguro — nada muda")
    @CsvSource(delimiter = '|', quoteCharacter = '`', value = {
        "No way!|Claro que não!",
        "No problem.|Sim, sem problema.",
        "Yes.|Sim.",
        "No.|Simples assim.",
        "No... it's too late.|Claro... é tarde demais.",
        "A rat?!|Que porco colorido!",
        "\"Inori is my\" what ?|Inori é a minha voz.",
        "\"She\"?|Ela é a capitã da nave inimiga.",
        "\"She\"?|Ela?",
        "Ensign Reccoa is?|Reccoa é uma tenente."
    })
    void semDefeitoOuSemConsertoSeguroNaoRepara(String original, String traduzido) {
        org.junit.jupiter.api.Assertions.assertNull(validador.repararPar(original, traduzido));
    }

    @ParameterizedTest(name = "[{index}] pergunta-eco: {0} | {1} -> {2}")
    @DisplayName("pergunta-eco entre aspas que perdeu o '?': o reparo devolve a interrogação e o resultado passa")
    // os 8 casos dos caches do acervo (75.856 pares distintos), mais tag no fim
    @CsvSource(delimiter = '|', quoteCharacter = '`', value = {
        "\"Deliberately\"?|De forma deliberada.|De forma deliberada?",
        "\"She\"?|Ela.|Ela?",
        "\"His\"?|Dele.|Dele?",
        "\"Genomic Resonance Gauge\"?|Medidor de Genomic Resonance.|Medidor de Genomic Resonance?",
        "\"President Ouma\"...?|Presidente Ouma|Presidente Ouma...?",
        "\"This time\"?|Desta vez.|Desta vez?",
        "'Our\"?|Nosso|Nosso?",
        "\"Ronah girl\"?|Menina Ronah|Menina Ronah?",
        "\"She\"?|{\\i1}Ela.{\\i0}|{\\i1}Ela?{\\i0}"
    })
    void perguntaEcoEntreAspasEhReparada(String original, String traduzido, String esperado) {
        org.junit.jupiter.api.Assertions.assertEquals(esperado, validador.repararPar(original, traduzido));
        assertDoesNotThrow(() -> validador.validarPar(original, esperado));
    }

    @Test
    @DisplayName("duas trocas na mesma fala: o reparo desfaz as duas e o resultado passa no portão")
    void duasTrocasNaMesmaFalaSaoDesfeitas() {
        String original = "Uraki! Keith! What the hell are you doing?!";
        String traduzido = "Kou! Chuck! O que diabos vocês estão fazendo?!";
        String esperado = "Uraki! Keith! O que diabos vocês estão fazendo?!";
        org.junit.jupiter.api.Assertions.assertEquals(esperado, comRegistro.repararTrocaDeEntidade(original, traduzido));
        org.junit.jupiter.api.Assertions.assertEquals(esperado, comRegistro.repararPar(original, traduzido));
        assertDoesNotThrow(() -> comRegistro.validarPar(original, esperado));
        // troca e polaridade na mesma fala: os dois reparos compõem
        org.junit.jupiter.api.Assertions.assertEquals("Não, Uraki.", comRegistro.repararPar("No, Uraki.", "Sim, Kou."));
        // termo composto partido pela quebra: o reparo devolve a quebra no mesmo lugar e passa no portao
        ValidadorTraducaoService comZz = new ValidadorTraducaoService(LoreAtivaFake.comPares(
            java.util.List.of("Zeta Gundam", "ZZ Gundam")));
        String reparado = comZz.repararPar("Zeta\\NGundam, launching!", "ZZ\\NGundam, partindo!");
        org.junit.jupiter.api.Assertions.assertEquals("Zeta\\NGundam, partindo!", reparado);
        assertDoesNotThrow(() -> comZz.validarPar("Zeta\\NGundam, launching!", reparado));
    }

    /** O trecho de exemplos do prompt do Zeta (Psyco Gundam e Four) e um exemplo de reescrita de gênero. */
    private static final String PROMPT_ZETA = String.join("\n",
        "- \"Psyco Gundam\" é um mobile armor gigante: \"Psyco Gundam?\" fica \"Psyco Gundam?\", \"As you wish, but"
            + " I'll entrust the Psyco Gundam to you.\" fica \"Como quiser, mas vou confiar o Psyco Gundam a você.\", \"You"
            + " must get out of the Psyco Gundam's cockpit! Hurry!\" fica \"Você precisa sair do cockpit do Psyco Gundam!"
            + " Rápido!\".",
        "- \"Four\" é o NOME dela: \"Four!\" fica \"Four!\", \"Open your eyes, Four!\" fica \"Abra os olhos, Four!\".",
        "  \"I'm tired of this\" -> \"Não aguento mais isso\"");

    private final ValidadorTraducaoService comPromptZeta = new ValidadorTraducaoService(
        LoreAtivaFake.comPrompt(PROMPT_ZETA, "Gundam", "Four", "Kamille", "Psycommu"));

    @ParameterizedTest(name = "[{index}] RUIM exemplo copiado: {0} -> {1}")
    @DisplayName("exemplo do prompt devolvido para outra fala, com termo que o original não tem — reprova")
    // os casos medidos nos caches do Zeta e do ZZ (08/10/2026)
    @CsvSource(delimiter = '|', quoteCharacter = '`', value = {
        "All right, do as you wish.|Como quiser, mas vou confiar o Psyco Gundam a você.",
        "As it turns out, I should have\\Nvisited them instead!|Como quiser, mas vou confiar\\No Psyco Gundam a você!",
        "...through the armor of a mobile suit,\\Nlike a Newtype?|Você precisa sair do cockpit\\Ndo Psyco Gundam! Rápido!",
        "G3?!|Psyco Gundam?",
        "Catl?|Psyco Gundam?",
        "Z-G...?|Psyco Gundam?",
        "{\\i1}...sami...|Four...",
        // o modelo devolve entre aspas e o normalizador as tira DEPOIS do portão (jar, 08/10/2026)
        "Z-G...?|\"Psyco Gundam?\"",
        "Catl?|“Psyco Gundam?”"
    })
    void exemploDoPromptCopiadoReprova(String original, String traduzido) {
        AlucinacaoDetectadaException e = assertThrows(AlucinacaoDetectadaException.class,
            () -> comPromptZeta.validarPar(original, traduzido));
        assertTrue(e.getMessage().startsWith("Exemplo do prompt copiado"), e.getMessage());
    }

    @ParameterizedTest(name = "[{index}] BOM igual a exemplo: {0} -> {1}")
    @DisplayName("A1: a mesma fala de exemplo, legítima — o próprio exemplo, termo já no original, ou sem termo da lore")
    @CsvSource(delimiter = '|', quoteCharacter = '`', value = {
        "Psyco Gundam?|Psyco Gundam?",
        "PSYCO GUNDAM|Psyco Gundam?",
        "The Psyco Gundam!|Psyco Gundam!",
        "It's Four!|Four!",
        "Number Four.|Four.",
        "I can't take this anymore!|Não aguento mais isso!",
        "A Gundam?!|Psyco Gundam?"
    })
    void falaIgualAExemploLegitimaPassa(String original, String traduzido) {
        // a última linha é o falso negativo declarado: o original já diz "Gundam"
        assertDoesNotThrow(() -> comPromptZeta.validarPar(original, traduzido));
    }

    @ParameterizedTest(name = "[{index}] parêntese inventado: {0} -> {1}")
    @DisplayName("parêntese que o original não tem é acusado (casos medidos nos caches em 08/10)")
    @CsvSource(delimiter = '|', quoteCharacter = '`', value = {
        "I'm relieved.|Estou aliviado(a).",
        "Welcome!|Bem-vindo(a)!",
        "Bombs?!|Bomba(s)?!",
        "\"You need something,\" my ass!|\"Você precisa de algo?!\" (com tom sarcástico)",
        "A pursuer?!|A pursuer?! (Perseguidor?!)",
        "G3?!|G3?! (G3?!)",
        "Mobile Suit|Móbil Space (ou Mecha)",
        "That means you're mine.|{\\i1}Isso significa que você é minha(o).{\\i0}"
    })
    void parenteseInventadoEhAcusado(String original, String traduzido) {
        String motivo = validador.alternativaInventada(original, traduzido);
        assertTrue(motivo != null && motivo.startsWith("Parêntese que o original não tem"), String.valueOf(motivo));
    }

    @ParameterizedTest(name = "[{index}] parêntese legítimo ou ausente: {0} -> {1}")
    @DisplayName("A1: o mesmo parêntese, quando o original já tinha, ou tradução sem parêntese — nada acusado")
    @CsvSource(delimiter = '|', quoteCharacter = '`', value = {
        "The NT-D (Newtype Destroyer).|O NT-D (Destruidor de Newtypes).",
        "I'm relieved.|Que alívio.",
        "{\\pos(10,20)}Welcome!|{\\pos(10,20)}Seja bem-vinda!"
    })
    void parenteseLegitimoOuAusenteNaoEhAcusado(String original, String traduzido) {
        org.junit.jupiter.api.Assertions.assertNull(validador.alternativaInventada(original, traduzido));
    }

    /**
     * Os 9 casos de BARRA medidos nos caches em 08/10/2026 (texto visível), todos alternativa de gênero
     * que o prompt proíbe. A barra entrou porque, com o parêntese acusado, o modelo trocava "estranho(a)"
     * por "estranho/a" na retentativa — o mesmo defeito escapando por outra grafia.
     */
    @ParameterizedTest(name = "[{index}] barra inventada: {0} -> {1}")
    @DisplayName("alternativa de gênero com barra que o original não tem é acusada (casos medidos)")
    @CsvSource(delimiter = '|', quoteCharacter = '`', value = {
        "I'm telling you that I'm tired.|Estou dizendo a vocês que estou cansada/o.",
        "I'm just tired.|Estou só cansada/o.",
        "I was so stupid.|Eu fui tão estúpido/estupida.",
        "The captain of the first unit I was attached to.|O capitão da primeira unidade a que fui designado/a.",
        "Nice to meet you.|Prazer em conhecê-lo/a.",
        "...who this intense pain is for.|Quem é que essa dor intensa é para ele/ela.",
        "Dear, I can't find a towel anywhere.|Querido/a, não consigo encontrar toalha em lugar nenhum.",
        "You little—|Você pequenino/a—",
        "You really are weird.|Você realmente é estranho/a."
    })
    void barraDeGeneroInventadaEhAcusada(String original, String traduzido) {
        String motivo = validador.alternativaInventada(original, traduzido);
        assertTrue(motivo != null && motivo.startsWith("Alternativa de gênero com barra"), String.valueOf(motivo));
    }

    /**
     * A1 da barra: o MESMO sinal (barra entre letras que o original não tem) em tradução certa. O
     * primeiro é o caso legítimo medido nos caches; os outros são as barras que o português usa de
     * verdade. Original COM barra também não acusa.
     */
    @ParameterizedTest(name = "[{index}] barra legítima: {0} -> {1}")
    @DisplayName("A1: barra que não é alternativa de gênero, ou que o original já tinha — nada acusado")
    @CsvSource(delimiter = '|', quoteCharacter = '`', value = {
        "There's one unit! Can't confirm IFF!|Há uma unidade! Não consigo confirmar a identificação amigo/inimigo!",
        "We're going 300 kph!|Estamos a 300 km/h!",
        "Bring the map, the radio, or both.|Traga o mapa e/ou o rádio.",
        "Yes/No?|Sim/Não?",
        "He/She will come.|Ele/Ela virá."
    })
    void barraLegitimaNaoEhAcusada(String original, String traduzido) {
        org.junit.jupiter.api.Assertions.assertNull(validador.alternativaInventada(original, traduzido));
    }

    @Test
    @DisplayName("detector de eco: o envelope só sai quando a FONTE não citava")
    void envelopeDeAspasSoSaiQuandoAFonteNaoCitava() {
        DetectorTraducaoIdenticaService detector = new DetectorTraducaoIdenticaService(LoreAtivaFake.vazia());
        assertTrue(detector.pareceNaoTraduzida("Damn it!", "\"Damn it!\""));
        assertTrue(detector.pareceNaoTraduzida("Damn it!", "“Damn it!”"));
        // fonte já entre aspas: a comparação é das falas com aspas, como sempre foi
        assertTrue(detector.pareceNaoTraduzida("\"Damn it!\"", "\"Damn it!\""));
        // duas citações não são envelope: não se mexe
        assertFalse(detector.pareceNaoTraduzida("Damn it! Damn it!", "\"Damn\" it! \"Damn\" it!"));
    }
}
