package org.traducao.projeto.qualidadeTraducao.application;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * PROPÓSITO DE NEGÓCIO: prova que o corretor acerta os dois homógrafos que Paulo leu na tela do
 * Gundam ZZ, e — o que importa mais — que ele NÃO estraga o texto que já está certo.
 *
 * <h2>Todo caso aqui saiu do acervo, não da minha cabeça</h2>
 * Os pares EN/PT são falas reais do ZZ S01E01 e das obras onde a medição de 03/09/2026 achou o
 * defeito. Caso inventado prova que o código faz o que eu quis; caso do acervo prova que ele faz
 * o que a legenda precisa.
 *
 * <h2>Comportamento em caso de falha</h2>
 * Reprova apontando a fala. Não há estado entre testes.
 */
@DisplayName("corretor de homografo com o original ingles como prova")
class CorretorHomografoComOriginalTest {

    private final CorretorHomografoComOriginal corretor = new CorretorHomografoComOriginal();

    @Nested
    @DisplayName("acerta o defeito real")
    class AcertaODefeito {

        @Test
        @DisplayName("`tambem e motivo` -- a fala que a lista fechada da 3.3 deixa passar")
        void aFalaQueA33DeixaPassar() {
            // A 3.3 exige que a palavra ANTERIOR esteja numa lista fechada, e `também` não está.
            assertEquals("E o estado de Kamille também é motivo de preocupação.",
                corretor.corrigir("And Kamille's condition is also cause for concern.",
                    "E o estado de Kamille também e motivo de preocupação."));
        }

        @Test
        @DisplayName("a conjuncao que ABRE a fala sobrevive, e so o `e` do meio muda")
        void aberturaSobrevive() {
            // Contar caracteres `é` aqui e armadilha: `também` ja tem um, e a primeira versao
            // deste teste reprovou por isso, apontando para o codigo quando o erro era meu.
            // A pergunta certa e sobre o `e` SOLTO, que e o unico que o corretor toca.
            String saida = corretor.corrigir("And Kamille's condition is also cause for concern.",
                "E o estado de Kamille também e motivo de preocupação.");
            assertEquals('E', saida.charAt(0), "o E de abertura foi acentuado indevidamente");
            assertEquals(0, contarSoltos(saida, "e"), "sobrou um `e` solto sem acento");
            assertEquals(1, contarSoltos(saida, "é"), "devia haver exatamente um `é` solto");
        }

        private long contarSoltos(String texto, String palavra) {
            return java.util.regex.Pattern
                .compile("(?<![\\p{L}\\p{N}])" + palavra + "(?![\\p{L}\\p{N}])")
                .matcher(texto).results().count();
        }

        @Test
        @DisplayName("`voce e candidato` (ZZ ep01, linha 169)")
        void voceECandidato() {
            assertEquals("É verdade que você é candidato a piloto?",
                corretor.corrigir("Is it true you're a candidate to be a pilot?",
                    "É verdade que você e candidato a piloto?"));
        }

        @Test
        @DisplayName("`esta` verbo sem demonstrativo no ingles (ZZ ep01)")
        void estaVerbo() {
            assertEquals("Judau está voando na nossa frente!",
                corretor.corrigir("Judau's flying in front of us!",
                    "Judau esta voando na nossa frente!"));
        }

        @Test
        @DisplayName("preserva a caixa: `Esta` vira `Está`")
        void preservaACaixa() {
            assertEquals("Está tudo bem?",
                corretor.corrigir("Is everything all right?", "Esta tudo bem?"));
        }

        @Test
        @DisplayName("acha o `e` colado na quebra \\N do ASS")
        void coladoNaQuebra() {
            // A quebra ocupa DOIS caracteres e o N e letra: sem FronteiraTermoAss o `e` colado
            // a ela vira sufixo de um "Ne" inexistente e nunca e visto. Medido em 04/08: 11 das
            // 12 formas do dicionario sobreviveram exatamente assim.
            assertEquals("Incrível. A ganancia de energia\\Né mais de cinco vezes!",
                corretor.corrigir("Incredible. The energy gain is more than five times!",
                    "Incrível. A ganancia de energia\\Ne mais de cinco vezes!"));
        }
    }

    @Nested
    @DisplayName("se ABSTEM quando o original nao prova")
    class SeAbstem {

        @Test
        @DisplayName("ha coordenador no ingles: qual `e` e o verbo vira adivinhacao")
        void coordenadorNoIngles() {
            // "The armor is hot and heavy" -> "A armadura e quente e pesada": o primeiro `e` e o
            // verbo e o segundo e a conjuncao, e nada no texto diz qual e qual. Medido: a regra
            // estrita recusa 21 falas das 371, e essas ficam para a rota do LLM.
            String pt = "A armadura e quente e pesada.";
            assertSame(pt, corretor.corrigir("The armor is hot and heavy.", pt));
        }

        @Test
        @DisplayName("o `e` E conjuncao de verdade: nao pode virar verbo")
        void conjuncaoDeVerdade() {
            String pt = "Venha aqui e sente-se.";
            assertSame(pt, corretor.corrigir("Come here and sit down.", pt));
        }

        @Test
        @DisplayName("o `and` colado na quebra \\N do INGLES tambem e coordenador")
        void coordenadorColadoNaQuebraDoIngles() {
            // 14/10/2026, Reconguista I (falas 344 e 1223): "He just hates losing\Nand is quick to
            // act." -> a revisao escreveu "perder\Ne age rapido" e a 2.1 publicou "perder\Né age".
            // O \b de "and" nao existe entre o N da quebra e o "a", entao o ingles parecia nao ter
            // coordenador e todo `e` solto virou verbo.
            String pt = "Ele só odeia perder\\Ne age rápido.";
            assertSame(pt, corretor.corrigir("He just hates losing\\Nand is quick to act.", pt));
            String pt2 = "vamos esmagar os que se aproximarem\\Ne iniciar a operação de distração.";
            assertSame(pt2, corretor.corrigir(
                "let's crush those that approach\\Nand commence the decoy operation.", pt2));
        }

        @Test
        @DisplayName("FRONTEIRA: `is` colado na quebra \\N do ingles continua prova e corrige o `e`")
        void verboColadoNaQuebraDoIngles() {
            assertEquals("Ele\\Né rápido.", corretor.corrigir("He\\Nis fast.", "Ele\\Ne rápido."),
                "o mesmo \\b cegava o verbo: sem enxergar `is`, a correcao certa nao acontecia");
        }

        @Test
        @DisplayName("virgula do ingles tambem faz abster -- e isso CUSTA correcao real")
        void virgulaTambemFazAbster() {
            // "In other words, my job is to collect" tem virgula, entao o corretor se abstem --
            // e aqui a fala PRECISAVA de correcao. E o preco declarado da regra estrita: 21 das
            // 371 falas ficam de fora.
            //
            // Tentei refinar, ignorando clausula introdutoria curta terminada em virgula
            // ("In other words,", "Look,", vocativo). O CASO-CONTROLE derrubou antes de virar
            // codigo: em "He is tall, strong." o refino tratava "He is tall" como introducao,
            // sobrava "strong." sem coordenador, e "Ele é alto e forte." teria virado
            // "Ele é alto é forte.". Corretor que estraga texto certo e pior que corretor
            // nenhum, entao o refino ficou fora e estas 21 vao para a rota que consulta o LLM.
            String pt = "Em outras palavras, meu trabalho e coletar mechas descartados.";
            assertSame(pt, corretor.corrigir(
                "In other words, my job is to collect scrapped mobile suits.", pt));
        }

        @Test
        @DisplayName("o ingles nao tem verbo `to be`: nao ha `e` a acentuar")
        void semVerboSer() {
            String pt = "Judau, saia daí e corra!";
            assertSame(pt, corretor.corrigir("Judau, get out and run!", pt));
        }

        @Test
        @DisplayName("`esta` E demonstrativo, porque o ingles traz `this`")
        void estaDemonstrativo() {
            String pt = "Esta é uma colônia espacial.";
            assertSame(pt, corretor.corrigir("This is a space colony.", pt));
        }

        @Test
        @DisplayName("original ausente NAO autoriza palpite -- falha fechada")
        void semOriginalNaoAdivinha() {
            String pt = "Isso e importante.";
            assertSame(pt, corretor.corrigir(null, pt));
            assertSame(pt, corretor.corrigir("   ", pt));
        }

        @Test
        @DisplayName("texto ja correto volta intacto, e na MESMA instancia")
        void jaCorretoNaoMuda() {
            String pt = "Ele é meu irmão e ela está bem.";
            assertSame(pt, corretor.corrigir("He is my brother and she is fine.", pt));
        }
    }

    @Nested
    @DisplayName("nao invade o que nao e dele")
    class NaoInvade {

        @Test
        @DisplayName("nao toca em tag do ASS")
        void naoTocaEmTag() {
            // O `e` de `\be` dentro de uma tag nao tem fronteira de palavra a esquerda.
            String pt = "{\\be1\\blur2}Isso e verdade.";
            assertEquals("{\\be1\\blur2}Isso é verdade.",
                corretor.corrigir("That's true.", pt));
        }

        @Test
        @DisplayName("nao toca em `e` dentro de palavra")
        void naoTocaDentroDePalavra() {
            assertEquals("Ele é de Side 3.",
                corretor.corrigir("He is from Side 3.", "Ele e de Side 3."));
        }

        @Test
        @DisplayName("nao toca em `esta` dentro de `estava` nem de `estas`")
        void naoTocaEmFlexao() {
            String pt = "Ela estava bem e as estatísticas confirmam.";
            assertSame(pt, corretor.corrigir("She was fine and the statistics confirm.", pt));
        }

        @Test
        @DisplayName("nulo e vazio nao lancam")
        void nuloEVazio() {
            assertEquals(null, corretor.corrigir("It is.", null));
            assertEquals("", corretor.corrigir("It is.", ""));
        }
    }

    /**
     * O {@code e} que liga numerais por extenso é conjunção do NÚMERO. O inglês não coordena
     * número ({@code "Eighty-Six"}, {@code "one hundred twenty"}), então a ausência de
     * {@code and} no original não prova verbo ali. Falas do cache de 28/10/2026.
     */
    @Nested
    @DisplayName("numeral por extenso (28/10/2026)")
    class NumeralPorExtenso {

        @Test
        @DisplayName("`Oitenta e Seis` nao vira `Oitenta é Seis` (86 E01)")
        void oitentaESeis() {
            String pt = "Por que temos que\\Nsuportar esses porcos do Setor Oitenta e Seis?";
            assertSame(pt, corretor.corrigir(
                "We're from the First Sector. Why do we have to put up \\Nwith those pigs from "
                    + "the Eighty-Sixth Sector?!", pt));
        }

        @Test
        @DisplayName("`cento e vinte` e `duas e meia` ficam")
        void centoEVinteEDuasEMeia() {
            assertEquals("Cento e vinte Gardes foram destruídos!",
                corretor.corrigir("One hundred twenty Gardes were destroyed!",
                    "Cento e vinte Gardes foram destruídos!"));
            assertEquals("São duas e meia.", corretor.corrigir("It's two thirty.", "São duas e meia."));
        }

        @Test
        @DisplayName("REPARA o que o cache guardou: `Oitenta é Seis`, `Cem é vinte`")
        void reparaOCache() {
            assertEquals("pela\\NLegion do que permitir que os Oitenta e Seis entrem no Gran Mur.",
                corretor.corrigir("The people of the Republic would \\Nrather be destroyed by the Legion",
                    "pela\\NLegion do que permitir que os Oitenta é Seis entrem no Gran Mur."));
            assertEquals("Cento e vinte Gardes foram destruídos!",
                corretor.corrigir("One hundred twenty Gardes were destroyed!",
                    "Cem é vinte Gardes foram destruídos!"));
        }

        @Test
        @DisplayName("FRONTEIRA: `um` depois do `e` nao veta o verbo (`Ele e um piloto`)")
        void umComoArtigoNaoVeta() {
            assertEquals("Ele é um piloto.", corretor.corrigir("He is a pilot.", "Ele e um piloto."));
        }

        @Test
        @DisplayName("`Cem e vinte` vira `Cento e vinte` (Sidonia S00E02)")
        void cemViraCento() {
            assertEquals("Cento e vinte Gardes foram destruídos!",
                corretor.corrigir("One hundred twenty Gardes were destroyed!",
                    "Cem é vinte Gardes foram destruídos!"));
            String sozinho = "Cem soldados chegaram.";
            assertSame(sozinho, corretor.corrigir("A hundred soldiers are here.", sozinho));
        }

        @Test
        @DisplayName("FRONTEIRA: `três é seis` nao tem estrutura de numeral e o `é` fica")
        void verboEntreNumeraisFica() {
            String pt = "Duas vezes três é seis.";
            assertSame(pt, corretor.corrigir("Two times three is six.", pt));
        }
    }

    /**
     * {@code "Esta será uma missão"} traduz {@code "It'll be a mission"}: o {@code esta} faz as
     * vezes do {@code it}, que não é demonstrativo em inglês. {@code "Está será"} não é português.
     */
    @Nested
    @DisplayName("`esta` sujeito de verbo (28/10/2026)")
    class EstaSujeito {

        @Test
        @DisplayName("`Esta será uma missão` fica (Zeta E25)")
        void estaSeraFica() {
            String pt = "Certo. Esta será uma\\Nmissão perigosa para você.";
            assertSame(pt, corretor.corrigir("R-Right.\\NIt'll be a dangerous mission for you.", pt));
        }

        @Test
        @DisplayName("`Esta é a porta` fica (Guilty Crown 21)")
        void estaEFica() {
            String pt = "Aqui está! Esta é a porta dos fundos de Haruka.";
            assertSame(pt, corretor.corrigir("There it is! Haruka's back door.", pt));
        }

        @Test
        @DisplayName("REPARA `Está é` quando o ingles prova o demonstrativo (ZZ E10, Zeta E17)")
        void reparaEstaEComDemonstrativo() {
            assertEquals("Certo. \"Novo modelo\\Nchegando.\" Esta é a Argama!",
                corretor.corrigir("Right. \"New model coming.\"\\NThis is the Argama!",
                    "Certo. \"Novo modelo\\Nchegando.\" Está é a Argama!"));
            assertEquals("Mirai, deixe-me apresentá-la.\\NEsta é Beltorchika Irma.",
                corretor.corrigir("Mirai, let me introduce you.\\NThis is Beltorchika Irma.",
                    "Mirai, deixe-me apresentá-la.\\NEstá é Beltorchika Irma."));
            assertEquals("aposto\\Nque esta é sua primeira batalha na Lua!",
                corretor.corrigir("Even if you are a Newtype, I bet\\Nthis is your first battle on the Moon!",
                    "aposto\\Nque está é sua primeira batalha na Lua!"));
        }

        @Test
        @DisplayName("FRONTEIRA: `você está é parte` tem sujeito antes e fica (ZZ E11)")
        void estaComSujeitoFica() {
            String pt = "O núcleo de combate que você\\Nestá é parte do novo traje!";
            assertSame(pt, corretor.corrigir("The core fighter you're in is part\\Nof the new suit!", pt));
        }

        @Test
        @DisplayName("FRONTEIRA: `Esta tudo bem?` continua virando `Está`")
        void estaSemVerboContinuaVirando() {
            assertEquals("Está tudo bem?", corretor.corrigir("Is everything all right?", "Esta tudo bem?"));
        }

        @Test
        @DisplayName("`esta noite` fica: `tonight` e demonstrativo sem `this` (karaoke)")
        void estaNoiteFica() {
            String pt = "Não venha até mim só porque está sozinho esta noite...";
            assertSame(pt, corretor.corrigir("Don't come to me just 'cause you're lonely tonight...", pt));
        }

        @Test
        @DisplayName("FRONTEIRA: `Esta tarde` vira `Está tarde` (`It's late`)")
        void estaTardeVira() {
            assertEquals("Está tarde.", corretor.corrigir("It's late.", "Esta tarde."));
        }
    }

    /**
     * O que vem depois decide quando o inglês não decide: fala inglesa da 2.1 pode ser só um
     * PEDAÇO da frase, e o {@code and} mora no evento seguinte. Falas do cache de 28/10/2026, que
     * a regra antiga estragava toda vez que a 2.1 republicava.
     */
    @Nested
    @DisplayName("o que vem depois do `e` nao admite verbo (28/10/2026)")
    class NaoAdmiteVerbo {

        @Test
        @DisplayName("`e manipulando` fica (Unicorn)")
        void gerundio() {
            String pt = "a Vist Foundation está conspirando com o Quartel-General\\Ndo Estado-Maior "
                + "e manipulando as forças militares ao seu bel-prazer.";
            assertSame(pt, corretor.corrigir(
                "We don't like how the Vist Foundation\\Nis conspiring with General Staff HQ", pt));
        }

        @Test
        @DisplayName("`e passaram` fica (Unicorn)")
        void preteritoPlural() {
            String pt = "Mas apenas aqueles que esvaziaram a si mesmos\\Ne passaram pela loucura.";
            assertSame(pt, corretor.corrigir(
                "But the only ones who can become vessels\\Nare those who have emptied themselves", pt));
        }

        @Test
        @DisplayName("`Senhoras e senhores` fica (0083)")
        void binomio() {
            String pt = "Senhoras e senhores! Esta revisão\\Nnaval é uma excelente oportunidade...";
            assertSame(pt, corretor.corrigir("Gentlemen! This naval review\\Nis an excellent opportunity...", pt));
        }

        @Test
        @DisplayName("FRONTEIRA: `Ele e alto` continua virando `é` (adjetivo nao veta)")
        void adjetivoNaoVeta() {
            assertEquals("Ele é alto.", corretor.corrigir("He is tall.", "Ele e alto."));
        }
    }
}
