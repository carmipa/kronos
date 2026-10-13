package org.traducao.projeto.lore.gundam;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.traducao.projeto.qualidadeTraducao.application.EnforcadorTermosLore;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * PROPÓSITO DE NEGÓCIO: congela as formas-ruim REAIS medidas nas 5.643 falas dos 22
 * episódios de Gundam Unicorn RE:0096 (cache de 2026-07-29). Nenhum caso foi deduzido.
 *
 * <p>Terceira obra a receber o tratamento, depois de Char's Counterattack e F91. O
 * padrão se repetiu: {@code termosProtegidos()} é permissivo e não impediu a
 * localização; quem garante é o {@link EnforcadorTermosLore} lendo
 * {@code correcoesTerminologia()}.
 *
 * <p>INVARIANTES DO DOMÍNIO: o enforcer só restaura quando o texto ORIGINAL (EN) contém
 * o canônico — é isso que torna seguras as entradas de ímã, em que a forma-ruim é ela
 * própria um termo legítimo da obra ({@code Side 7} e {@code Londo Bell} existem).
 *
 * <p>COMPORTAMENTO EM CASO DE FALHA: qualquer forma-ruim medida que volte a passar
 * reprova a suíte.
 */
class TerminologiaUnicornFormasMedidasTest {

    private final EnforcadorTermosLore enforcador = new EnforcadorTermosLore();
    private final Map<String, String> correcoes = org.traducao.projeto.lore.LoreDeTeste.obra("gundam_unicorn").correcoesTerminologia();

    private String reforcar(String original, String traduzido) {
        return enforcador.reforcar(original, traduzido, correcoes);
    }

    @Test
    @DisplayName("Unicorn Gundam: a ordem invertida não era coberta pelo mapa antigo")
    void restauraOrdemUnicornGundam() {
        assertEquals("O alvo do Frontal é o Unicorn Gundam, a chave para a Caixa.",
            reforcar("Frontal's target is the Unicorn Gundam, the key to the Box.",
                "O alvo do Frontal é o Gundam Unicorn, a chave para a Caixa."));
        assertEquals("O Unicorn Gundam! - Mas quem é...",
            reforcar("- The Unicorn Gundam! - But who is...",
                "O Unicórnio Gundam! - Mas quem é..."));
    }

    @Test
    @DisplayName("Co-Prosperity Sphere: perdido em 9 de 10, em três formas")
    void restauraCoProsperitySphere() {
        assertEquals("Encontramos a Co-Prosperity Sphere Lado.",
            reforcar("That is to say, we found the Side Co-Prosperity Sphere.",
                "Encontramos a Esfera de Prosperidade Lado."));
        assertEquals("Não avançaremos em direção à Co-Prosperity Sphere.",
            reforcar("we will never move toward the Side Co-Prosperity Sphere.",
                "Não avançaremos em direção à Esfera de Prosperidade Comum."));
    }

    @Test
    @DisplayName("Garencieres: a nave ganhou acento francês em 7 de 30")
    void restauraGarencieres() {
        assertEquals("Garencieres! Inimigos estão chegando!",
            reforcar("Garencieres! Enemies are here!", "Garencières! Inimigos estão chegando!"));
    }

    @Test
    @DisplayName("Red Comet é codinome do Char, não descrição")
    void restauraRedComet() {
        assertEquals("Esse é o Sinanju! O Red Comet!",
            reforcar("That's the Sinanju! The Red Comet!", "Esse é o Sinanju! O Cometa Vermelho!"));
    }

    @Test
    @DisplayName("ÍMÃ entre colônias: Industrial 7 saiu como Side 7")
    void desfazTrocaIndustrial7PorSide7() {
        assertEquals("Em Industrial 7, a colônia industrial",
            reforcar("at the colony Industrial 7,", "Em Side 7, a colônia industrial"));
    }

    @Test
    @DisplayName("Side 7 continua Side 7 quando o inglês falava de Side 7")
    void naoTocaSide7Legitimo() {
        String fala = "A colônia Side 7 foi destruída.";
        assertEquals(fala, reforcar("The Side 7 colony was destroyed.", fala),
            "sem 'Industrial 7' no inglês, a guarda impede a troca");
    }

    @Test
    @DisplayName("ÍMÃ entre unidades: ECOAS saiu como Londo Bell")
    void desfazTrocaEcoasPorLondoBell() {
        assertEquals("ECOAS não pode investigar isso sozinhos.",
            reforcar("ECOAS can't investigate this by ourselves.",
                "Londo Bell não pode investigar isso sozinhos."));
    }

    @Test
    @DisplayName("Londo Bell continua Londo Bell quando o inglês falava da Londo Bell")
    void naoTocaLondoBellLegitimo() {
        String fala = "A Londo Bell está em posição.";
        assertEquals(fala, reforcar("Londo Bell is in position.", fala),
            "sem 'ECOAS' no inglês, a guarda impede a troca");
    }

    @Test
    @DisplayName("Torrington Base: ordem invertida e preposição, 4 de 5")
    void restauraTorringtonBase() {
        assertEquals("Verificando danos na Torrington Base.",
            reforcar("Verifying damage to Torrington Base.", "Verificando danos na Base Torrington."));
    }

    @Test
    @DisplayName("Sieg Zeon: mesma inversão de ordem medida no Char's Counterattack")
    void restauraSiegZeon() {
        assertEquals("Sieg Zeon!", reforcar("Sieg Zeon!", "Zeon Sieg!"));
        assertEquals("\"Sieg Zeon...\"", reforcar("\"Sieg Zeon...\"", "\"Siege Zeon...\""));
    }

    @Test
    @DisplayName("psycommu minúsculo é restaurado")
    void restauraPsycommuMinusculo() {
        assertEquals("Um refluxo de psycommu.",
            reforcar("A psycommu backflow.", "Um refluxo de psicommu."));
    }

    /**
     * PROPÓSITO DE NEGÓCIO: congela um LIMITE deliberado, não um esquecimento.
     *
     * <p>A legenda escreve as duas caixas ({@code "A psycommu backflow."} e
     * {@code "A Psycommu runaway..."}), e o enforcer exige o canônico na grafia exata.
     * Cobrir a maiúscula exigiria declarar {@code "Psycommu"} em
     * {@code termosProtegidos()} — invariante do {@code CorrecoesTerminologiaGundamUcTest}
     * —, o que mudaria o hash do manifesto e invalidaria as 5.643 falas já traduzidas.
     * Caro demais por 2 ocorrências; fica para a próxima mudança de lore desta obra.
     */
    @Test
    @DisplayName("LIMITE: Psycommu maiúsculo fica, para não invalidar 5.643 falas")
    void psycommuMaiusculoNaoEhCoberto() {
        String fala = "Psicommu fugitivo... Está sedentoso por sangue?!";
        assertEquals(fala, reforcar("A Psycommu runaway... Is it thirsty for blood?!", fala));
        assertTrue(!correcoes.containsKey("Psicommu"),
            "declarar o canônico maiúsculo custaria a invalidação do cache inteiro");
    }

    @Test
    @DisplayName("Newtype no feminino: mesma lacuna do núcleo UC encontrada no F91")
    void restauraNewtypeFeminino() {
        assertEquals("Ela é uma Newtype artificial, criada através de clonagem.",
            reforcar("She is an artificial Newtype, created via cloning.",
                "Ela é uma Nova Tipo artificial, criada através de clonagem."));
    }

    @Test
    @DisplayName("Earth Federation NÃO entra — 'Federação Terrestre' é decisão de produto")
    void federacaoTerrestrePermanece() {
        String fala = "O governo da Federação Terrestre alterou o calendário";
        assertEquals(fala,
            reforcar("the Earth Federation government changed the calendar", fala));
        assertTrue(!correcoes.containsKey("Federação Terrestre"),
            "20 de 20 falas usam a forma traduzida, igual a CCA e F91");
    }

    /**
     * Decisão de Paulo, 12/10/2026, vendo o E01: "Anno Domini" fica em LATIM — o modelo escrevia
     * "Ano Domínio", "ano do domínio" e "era do Domínio" (ao pé da letra seria "era de Deus"). As
     * três primeiras formas são as do cache real do E01.
     */
    @Test
    @DisplayName("Anno Domini fica em latim: 'Ano Domínio' e 'ano do domínio' voltam ao latim")
    void annoDominiFicaEmLatim() {
        assertEquals("Adeus, Anno Domini.\\NOlá, Século Universal!",
            reforcar("Goodbye, Anno Domini.\\NHello, Universal Century!",
                "Adeus, Ano Domínio.\\NOlá, Século Universal!"));
        assertEquals("e agradecer ao passado,\\Nà era do Anno Domini.",
            reforcar("and give thanks to the past,\\Nthe Anno Domini era.",
                "e agradecer ao passado,\\Nà era do Ano Domínio."));
        assertEquals("Em poucos momentos, o Anno\\NDomini chegará ao fim.",
            reforcar("In a few moments,\\Nthe Anno Domini era will come to an end.",
                "Em poucos momentos, o ano\\Ndo domínio chegará ao fim."),
            "a quebra \\N de dentro do termo volta no mesmo lugar");
    }

    @Test
    @DisplayName("A1: 'domínio' comum, sem 'Anno Domini' no inglês, não é tocado")
    void dominioComumNaoETocado() {
        String fala = "O ano do domínio da Federação acabou.";
        assertEquals(fala, reforcar("The year of the Federation's dominion is over.", fala),
            "mesmo sinal ('ano do domínio'), mas o inglês não fala do calendário: fica como está");
    }
}
