package org.traducao.projeto.traducaoKaraoke.application;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.traducao.projeto.legenda.application.DetectorEfeitoKaraokeService;
import org.traducao.projeto.legenda.infrastructure.LeitorLegendaAss;
import org.traducao.projeto.legenda.domain.DocumentoLegenda;
import org.traducao.projeto.traducaoKaraoke.domain.ClasseLinhaKaraoke;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * PROPÓSITO DE NEGÓCIO: guarda as invariantes que passaram a morar no
 * {@link PlanoDeClassificacao} quando ele saiu de dentro do use case — a ORDEM dos dois passes,
 * a leitura do campo {@code Effect}, e a indexação por posição.
 *
 * <h2>Por que esta classe existe separada</h2>
 * A decisão do arquivo vivia num método de 1.107 bytecodes que também gravava cache e escrevia
 * o {@code .ass}. Mexer no critério de música exigia tocar em tudo isso. E o método percorria o
 * documento DUAS vezes chamando o classificador nas duas — no acervo, 3,95 milhões de
 * classificações onde 1,98 milhão basta.
 */
class PlanoDeClassificacaoTest {

    private final ClassificadorLetraKaraokeService classificador =
        new ClassificadorLetraKaraokeService(new DetectorEfeitoKaraokeService());
    private final LeitorLegendaAss leitor = new LeitorLegendaAss();

    private DocumentoLegenda documento(Path pasta, String... linhasDialogo) throws IOException {
        StringBuilder sb = new StringBuilder("[Script Info]\r\nTitle: Teste\r\n\r\n[Events]\r\n")
            .append("Format: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text\r\n");
        for (String l : linhasDialogo) {
            sb.append(l).append("\r\n");
        }
        Path arquivo = pasta.resolve("plano.ass");
        Files.writeString(arquivo, sb.toString(), StandardCharsets.UTF_8);
        return leitor.ler(arquivo);
    }

    /**
     * A INVARIANTE DE ORDEM, e é a razão de o pré-passe existir separado: ele descobre o romaji
     * usando SÓ o campo {@code Effect}. Se usasse também "romaji no mesmo instante", a regra se
     * alimentaria da própria conclusão e uma linha puxaria a vizinha para dentro da música.
     *
     * <p>Aqui as duas linhas estão no MESMO instante: uma é romaji (e o estilo declara), a outra
     * é inglês num estilo que não declara nada. A segunda só é karaokê porque a primeira existe.
     */
    @Test
    @DisplayName("a camada romaji no mesmo instante puxa a camada inglesa para dentro da musica")
    void pareamentoPorInstante(@org.junit.jupiter.api.io.TempDir Path pasta) throws IOException {
        DocumentoLegenda doc = documento(pasta,
            "Dialogue: 0,0:01:00.00,0:01:05.00,OP - Romaji,,0,0,0,,kimi no koe wo wasure wa shinai",
            "Dialogue: 0,0:01:00.00,0:01:05.00,Camada2,,0,0,0,,I will never forget your voice");

        PlanoDeClassificacao plano = PlanoDeClassificacao.montar(doc, classificador);

        assertEquals(ClasseLinhaKaraoke.ORIGINAL_JAPONES, plano.classeNaPosicao(0));
        assertEquals(ClasseLinhaKaraoke.TRADUZIVEL_INGLES, plano.classeNaPosicao(1),
            "o estilo 'Camada2' nao declara nada e o campo Effect esta vazio — a UNICA evidencia "
                + "e a camada romaji no mesmo instante");
    }

    /** Sem a camada romaji, a mesma linha inglesa não tem evidência nenhuma e fica de fora. */
    @Test
    @DisplayName("CONTRA-TESTE: sem a camada romaji, a linha inglesa nao e karaoke")
    void semPareamentoNaoEhKaraoke(@org.junit.jupiter.api.io.TempDir Path pasta) throws IOException {
        DocumentoLegenda doc = documento(pasta,
            "Dialogue: 0,0:01:00.00,0:01:05.00,Camada2,,0,0,0,,I will never forget your voice");

        PlanoDeClassificacao plano = PlanoDeClassificacao.montar(doc, classificador);

        assertEquals(ClasseLinhaKaraoke.FORA_DE_MUSICA, plano.classeNaPosicao(0),
            "sem evidencia nenhuma a linha nao e assunto do karaoke");
    }

    /**
     * O campo {@code Effect} é o NONO da linha, e o {@code split} precisa de limite negativo:
     * campo vazio no meio é o caso normal, e sem isso o índice escorrega.
     */
    @Test
    @DisplayName("campo Effect=fx e lido do nono campo e vale como evidencia")
    void campoEfeitoEhLidoDoNonoCampo(@org.junit.jupiter.api.io.TempDir Path pasta) throws IOException {
        DocumentoLegenda doc = documento(pasta,
            "Dialogue: 0,0:01:37.00,0:01:39.80,OPL2,,0,0,0,fx,{\\fad(200,200)\\pos(960,1050)}Do you feel alone",
            "Dialogue: 0,0:01:37.00,0:01:39.80,OPL2,,0,0,0,,{\\fad(200,200)\\pos(960,1050)}Do you feel alone");

        PlanoDeClassificacao plano = PlanoDeClassificacao.montar(doc, classificador);

        assertEquals(ClasseLinhaKaraoke.TRADUZIVEL_INGLES, plano.classeNaPosicao(0),
            "com Effect=fx o OPL2 e karaoke — sao 258 linhas assim no acervo");
        assertEquals(ClasseLinhaKaraoke.FORA_DE_MUSICA, plano.classeNaPosicao(1),
            "a MESMA linha sem o campo Effect nao tem evidencia — o nono campo e o que decide");
    }

    /**
     * Indexação por POSIÇÃO, não por igualdade de evento. No 86 a mesma frase aparece 650 vezes
     * em instantes diferentes; um mapa por conteúdo colapsaria todas numa decisão só.
     */
    @Test
    @DisplayName("a decisao e por POSICAO: linhas identicas em instantes diferentes sao eventos diferentes")
    void indexadoPorPosicao(@org.junit.jupiter.api.io.TempDir Path pasta) throws IOException {
        DocumentoLegenda doc = documento(pasta,
            "Dialogue: 0,0:01:00.00,0:01:05.00,OP - Romaji,,0,0,0,,kimi no koe wo wasure wa shinai",
            "Dialogue: 0,0:01:00.00,0:01:05.00,Camada2,,0,0,0,,A flower blooms only to be crushed",
            "Dialogue: 0,0:09:00.00,0:09:05.00,Camada2,,0,0,0,,A flower blooms only to be crushed");

        PlanoDeClassificacao plano = PlanoDeClassificacao.montar(doc, classificador);

        assertEquals(3, plano.total());
        assertEquals(ClasseLinhaKaraoke.TRADUZIVEL_INGLES, plano.classeNaPosicao(1),
            "no instante 0:01:00 ha camada romaji — esta e karaoke");
        assertEquals(ClasseLinhaKaraoke.FORA_DE_MUSICA, plano.classeNaPosicao(2),
            "TEXTO IDENTICO, instante diferente e sem camada romaji: decisao diferente. "
                + "Indexar por conteudo colapsaria as duas.");
    }

    /**
     * A pergunta que decide o empilhamento com {@code \N}. Errar aqui apagou a letra da tela em
     * 22 de 23 linhas, em 10 episódios do Unicorn.
     */
    @Test
    @DisplayName("temOriginalPreservadaNoInstante responde pelo instante, nao pelo estilo")
    void originalPreservadaPorInstante(@org.junit.jupiter.api.io.TempDir Path pasta) throws IOException {
        DocumentoLegenda doc = documento(pasta,
            "Dialogue: 0,0:01:00.00,0:01:05.00,OP - Romaji,,0,0,0,,kimi no koe wo wasure wa shinai",
            "Dialogue: 0,0:01:00.00,0:01:05.00,Camada2,,0,0,0,,I will never forget your voice",
            "Dialogue: 0,0:09:00.00,0:09:05.00,ED2,,0,0,0,,Behind your mask");

        PlanoDeClassificacao plano = PlanoDeClassificacao.montar(doc, classificador);

        assertTrue(plano.temOriginalPreservadaNoInstante(doc.eventos().get(1)),
            "ha camada romaji simultanea: a traducao NAO precisa empilhar");
        assertFalse(plano.temOriginalPreservadaNoInstante(doc.eventos().get(2)),
            "camada UNICA: sem empilhar, a letra original desaparece da tela — foi o defeito "
                + "dos episodios 13-22 do Unicorn");
    }

    /**
     * Dublê dos três dicionários com os veredictos MEDIDOS no real em 24/09/2026: o pt_BR aceita
     * "anata", "suru", "sou", "ni", "I", "a", "time"; o romaji comum ("wa", "Kagayaku", "Asayake")
     * sai DESCONHECIDA; "yo", "wanna", "pure", "have"... saem inglês.
     */
    private static final class TresDicionarios
            extends org.traducao.projeto.core.texto.dicionarioOrtografia.CorretorOrtograficoLegenda {
        private final boolean inglesNoAr;
        private final java.util.Map<String, org.traducao.projeto.core.texto.dicionarioOrtografia.VeredictoPalavra> tabela =
            new java.util.HashMap<>();

        TresDicionarios(boolean inglesNoAr) {
            this.inglesNoAr = inglesNoAr;
            var pt = org.traducao.projeto.core.texto.dicionarioOrtografia.VeredictoPalavra.PORTUGUES_OK;
            var en = org.traducao.projeto.core.texto.dicionarioOrtografia.VeredictoPalavra.RESIDUO_INGLES;
            for (String p : "I a time ai suru anata ni sou mo anata o sun".split(" ")) tabela.put(p, pt);
            for (String p : "wanna have pure yo was watching you as were the rise my history Dreamer".split(" ")) tabela.put(p, en);
        }

        @Override
        public java.util.Map<String, org.traducao.projeto.core.texto.dicionarioOrtografia.VeredictoPalavra> classificarPalavras(
                java.util.Collection<String> palavras) {
            java.util.Map<String, org.traducao.projeto.core.texto.dicionarioOrtografia.VeredictoPalavra> out = new java.util.HashMap<>();
            for (String p : palavras) {
                out.put(p, inglesNoAr
                    ? tabela.getOrDefault(p, org.traducao.projeto.core.texto.dicionarioOrtografia.VeredictoPalavra.DESCONHECIDA)
                    : org.traducao.projeto.core.texto.dicionarioOrtografia.VeredictoPalavra.NAO_VERIFICADO);
            }
            return out;
        }

        @Override
        public boolean inglesDisponivel() {
            return inglesNoAr;
        }
    }

    private DocumentoLegenda documentoF8(Path pasta) throws IOException {
        return documento(pasta,
            // 0: Zeta — ingles cantado SOZINHO na camada JP (sem romaji irmao no instante)
            "Dialogue: 0,0:00:40.23,0:00:43.27,Song JP,,0,0,0,,I wanna have a pure time!",
            // 1-2: 08th — romaji e a traducao inglesa, os DOIS no estilo JP, no mesmo instante
            "Dialogue: 0,0:21:06.85,0:21:10.88,Song JP,,0,0,0,,Asayake wo mitsumeteru anata o",
            "Dialogue: 0,0:21:06.85,0:21:10.88,Song JP,,0,0,0,,I was watching you as you were watching the sun rise.",
            // 3: MISTURA ingles + japones — linha de cima, intacta
            "Dialogue: 0,0:01:00.00,0:01:04.00,Song JP,,0,0,0,,Kagayaku my history",
            // 4: romaji que o pt_BR aceita quase inteiro (so "yo" e ingles) — intacta
            "Dialogue: 0,0:02:00.00,0:02:04.00,Song JP,,0,0,0,,ai suru anata ni sou yo",
            // 5: verso de UMA palavra so-inglesa (0083) — vira portugues
            "Dialogue: 0,0:27:00.47,0:27:03.73,Song JP,,0,0,0,,Dreamer...",
            // 6: verso de uma palavra ROMAJI (desconhecida para pt/en) — intacto
            "Dialogue: 0,0:22:09.21,0:22:12.58,Song JP,,0,0,0,,itoshii");
    }

    @Test
    @DisplayName("F8: linha toda ingles na camada JP vira traduzivel; mistura e romaji ficam intactos")
    void linhaTodaInglesaViraPortuguesMisturaNao(@org.junit.jupiter.api.io.TempDir Path pasta) throws IOException {
        DocumentoLegenda doc = documentoF8(pasta);

        PlanoDeClassificacao plano = PlanoDeClassificacao.montar(doc, classificador, new TresDicionarios(true));

        assertEquals(ClasseLinhaKaraoke.TRADUZIVEL_INGLES, plano.classeNaPosicao(0),
            "Zeta: 'I wanna have a pure time!' e toda ingles — tem de virar portugues");
        assertFalse(plano.temOriginalPreservadaNoInstante(doc.eventos().get(0)),
            "sozinha, ela e a letra cantada: NAO pode achar a si mesma como irma — tem de EMPILHAR");
        assertEquals(ClasseLinhaKaraoke.TRADUZIVEL_INGLES, plano.classeNaPosicao(2),
            "08th: a traducao inglesa posta no estilo JP tambem vira portugues");
        assertTrue(plano.temOriginalPreservadaNoInstante(doc.eventos().get(2)),
            "com o romaji irmao no instante, a traducao e TROCADA no lugar (romaji em cima)");
        assertEquals(ClasseLinhaKaraoke.ORIGINAL_JAPONES, plano.classeNaPosicao(1), "o romaji do 08th fica intacto");
        assertEquals(ClasseLinhaKaraoke.ORIGINAL_JAPONES, plano.classeNaPosicao(3),
            "A1: linha que MISTURA ingles e japones e a de cima — intacta");
        assertEquals(ClasseLinhaKaraoke.ORIGINAL_JAPONES, plano.classeNaPosicao(4),
            "A1: romaji com 1 palavra que so o ingles reconhece NAO e 'toda ingles'");
        assertEquals(ClasseLinhaKaraoke.TRADUZIVEL_INGLES, plano.classeNaPosicao(5),
            "verso de uma palavra so-inglesa ('Dreamer...') tambem e todo ingles");
        assertEquals(ClasseLinhaKaraoke.ORIGINAL_JAPONES, plano.classeNaPosicao(6),
            "A1: verso de uma palavra ROMAJI ('itoshii') fica intacto");
    }

    @Test
    @DisplayName("F8: sem dicionario ingles (ou sem corretor) o plano fica como era — falha fechada")
    void semDicionarioNadaMuda(@org.junit.jupiter.api.io.TempDir Path pasta) throws IOException {
        DocumentoLegenda doc = documentoF8(pasta);

        assertEquals(ClasseLinhaKaraoke.ORIGINAL_JAPONES,
            PlanoDeClassificacao.montar(doc, classificador, new TresDicionarios(false)).classeNaPosicao(0));
        assertEquals(ClasseLinhaKaraoke.ORIGINAL_JAPONES,
            PlanoDeClassificacao.montar(doc, classificador, null).classeNaPosicao(0));
    }

    /**
     * PROPÓSITO DE NEGÓCIO (F12, 24/09/2026): montar o plano de um arquivo GRANDE não pode prender a
     * fila do pipeline. O arquivo do Char's Counterattack tem 55.983 eventos; a busca de sílabas
     * comparava cada frase com todos os eventos, refazendo parse de tempo e limpeza de tags a cada
     * par, e ficou 45 min de CPU sem terminar. A fila é ÚNICA: travada ali, nenhuma outra operação
     * roda.
     *
     * <p>O documento sintético tem 40.000 falas de diálogo num só estilo, todas com 2+ palavras (o
     * pior caso da busca antiga) e fins espalhados como numa legenda real. O teto de 20 s é folgado
     * para a versão indexada e inalcançável para a quadrática.
     */
    @Test
    @DisplayName("F12: plano de 40 mil falas monta sem prender a fila (versao indexada, nao O(n^2))")
    void planoDeArquivoGrandeNaoPrendeAFila(@org.junit.jupiter.api.io.TempDir Path pasta) throws IOException {
        String[] linhas = new String[40_000];
        for (int i = 0; i < linhas.length; i++) {
            long ini = i * 150L;
            linhas[i] = String.format(java.util.Locale.ROOT,
                "Dialogue: 0,%s,%s,Default,,0,0,0,,{\\clip(601,835,1685,924)}Fala numero %d do filme",
                tempo(ini), tempo(ini + 200), i);
        }
        DocumentoLegenda doc = documento(pasta, linhas);

        PlanoDeClassificacao plano = org.junit.jupiter.api.Assertions.assertTimeoutPreemptively(
            java.time.Duration.ofSeconds(20), () -> PlanoDeClassificacao.montar(doc, classificador),
            "montar o plano de 40 mil falas passou de 20 s — a busca de silabas voltou a ser quadratica");
        assertEquals(40_000, plano.total());
    }

    private static String tempo(long centesimos) {
        long h = centesimos / 360000;
        long m = (centesimos / 6000) % 60;
        long s = (centesimos / 100) % 60;
        long cs = centesimos % 100;
        return String.format(java.util.Locale.ROOT, "%d:%02d:%02d.%02d", h, m, s, cs);
    }

    /**
     * PROPÓSITO DE NEGÓCIO (F6, 24/09/2026): camadas do MESMO verso com as pontas a centésimos de
     * distância são o mesmo momento. Na abertura do 86 E01 o romaji termina em 22:54.48 e o inglês
     * em 22:54.60; com pareamento EXATO o inglês empilhava {@code inglês\Nportuguês} e a tela
     * mostrava romaji + inglês + português.
     *
     * <p>A1 — o mesmo sinal (camada romaji perto no tempo) a mais de meio segundo nas pontas é OUTRO
     * momento, e a camada única continua empilhando a original.
     */
    @Test
    @DisplayName("F6: camadas com 12 cs de diferenca no fim sao o mesmo verso; a 60 cs nao sao")
    void camadasComPontasPertoSaoOMesmoVerso(@org.junit.jupiter.api.io.TempDir Path pasta) throws IOException {
        DocumentoLegenda doc = documento(pasta,
            "Dialogue: 0,0:22:50.47,0:22:54.48,OP - Romaji,,0,0,0,,fuminijirareru dake no hana",
            "Dialogue: 0,0:22:50.47,0:22:54.60,Camada2,,0,0,0,,A flower blooms only to be crushed",
            "Dialogue: 0,0:23:00.00,0:23:04.00,OP - Romaji,,0,0,0,,boukan shiteiru zouhan shiteiru",
            "Dialogue: 0,0:23:00.00,0:23:04.60,Camada2,,0,0,0,,Bystanding, revolting, willful ignorance");

        PlanoDeClassificacao plano = PlanoDeClassificacao.montar(doc, classificador);

        assertTrue(plano.temOriginalPreservadaNoInstante(doc.eventos().get(1)),
            "12 cs no fim: e o MESMO verso — o ingles nao pode empilhar sobre o romaji");
        assertFalse(plano.temOriginalPreservadaNoInstante(doc.eventos().get(3)),
            "A1: 60 cs no fim ja e outro momento — sem par, a original tem de empilhar");
    }

    /**
     * PROPÓSITO DE NEGÓCIO (09/10/2026): a linha inglesa FATIADA dentro de um romaji só é o mesmo
     * momento. Linhas cruas do OP_S2 do Guilty Crown E13: o romaji vai de 2:01.22 a 2:06.64 e o
     * inglês "that your eyes..." foi partido em cinco eventos dentro dele, para o efeito de pulsar.
     * Pelo pareamento de pontas nenhuma fatia casava, e as cinco empilhariam o inglês sobre o
     * português com o romaji na tela. Medido no acervo pelo plano de produção: as 50 fatias (5 em
     * 10 episódios) e NENHUMA outra das 14.375 linhas traduzíveis mudam de decisão.
     *
     * <p>A1 — o mesmo sinal (inglês sobreposto ao romaji) sem estar CONTIDO: começa dentro da janela
     * e termina 1 s depois dela. A original não cobre o evento inteiro, e trocar no lugar apagaria a
     * letra no trecho descoberto — continua empilhando.
     */
    @Test
    @DisplayName("fatia dentro do romaji e o mesmo momento; o que vaza da janela nao e")
    void fatiaDentroDoRomajiEOMesmoMomento(@org.junit.jupiter.api.io.TempDir Path pasta) throws IOException {
        DocumentoLegenda doc = documento(pasta,
            "Dialogue: 0,0:02:01.22,0:02:06.64,OP_S2_roma,,0,0,0,,{\\fad(0,0)}Sono me wa tagai wo mitomeru tame,",
            "Dialogue: 0,0:02:01.26,0:02:01.89,OP_S2,,0,0,0,,{\\fad(0,0)}that your eyes were given to you to {\\c&HEAEEEB&}acknowledge others,",
            "Dialogue: 0,0:02:03.18,0:02:04.10,OP_S2,,0,0,0,,{\\fad(0,0)}that your eyes were given to you to {\\c&H39383C&}acknowledge others,",
            "Dialogue: 0,0:02:05.97,0:02:06.64,OP_S2,,0,0,0,,{\\fad(0,0)}that your eyes were given to you to {\\c&HEAEEEB&}acknowledge others,",
            "Dialogue: 0,0:02:05.97,0:02:07.64,OP_S2,,0,0,0,,{\\fad(0,0)}that your voice was given to you to tell others");

        PlanoDeClassificacao plano = PlanoDeClassificacao.montar(doc, classificador);

        for (int i = 1; i <= 3; i++) {
            int fatia = i;
            assertTrue(plano.temOriginalPreservadaNoInstante(doc.eventos().get(fatia)),
                () -> "fatia " + fatia + " esta inteira dentro do romaji: o ingles nao pode empilhar");
        }
        assertFalse(plano.temOriginalPreservadaNoInstante(doc.eventos().get(4)),
            "A1: termina 1 s depois do romaji — a original nao cobre o evento, tem de empilhar");
    }

    /**
     * A1 da folga do F6, achado no acervo em 24/09/2026: a folga vale para EMPILHAR, nunca como
     * evidência de que uma linha é música. Uma fala de DIÁLOGO que coincide no tempo com um verso
     * romaji (ZZ: "Como posso pilotar o Zeta Gundam se tenho medo de Newtypes?", 27 cs de diferença
     * no fim) tem de continuar fora da música — senão iria ao LLM como letra.
     */
    @Test
    @DisplayName("F6/A1: dialogo que coincide com verso romaji (27 cs) continua FORA da musica")
    void folgaNaoTransformaDialogoEmMusica(@org.junit.jupiter.api.io.TempDir Path pasta) throws IOException {
        DocumentoLegenda doc = documento(pasta,
            "Dialogue: 0,0:10:00.00,0:10:04.27,OP - Romaji,,0,0,0,,Hontou no koto sa",
            "Dialogue: 0,0:10:00.03,0:10:04.00,Dialogue,,0,0,0,,Como posso pilotar o Zeta Gundam se tenho medo de Newtypes?");

        PlanoDeClassificacao plano = PlanoDeClassificacao.montar(doc, classificador);

        assertEquals(ClasseLinhaKaraoke.FORA_DE_MUSICA, plano.classeNaPosicao(1),
            "fala de dialogo perto de um verso nao pode virar letra de musica");
    }

    /**
     * PROPÓSITO DE NEGÓCIO: uma SÍLABA de fill do KFX que por acaso parece romaji (a letra
     * {@code "I"} → {@code "i"}, que casa o padrão de sílaba japonesa) NÃO pode marcar o instante
     * como "tem original preservada" e roubar o empilhamento do inglês da frase que está no MESMO
     * instante.
     *
     * <h2>O prejuízo que originou (medido 2026-09-21, Unicorn OPL2)</h2>
     * As frases {@code "I know that all the lies..."} e {@code "I wonder how long..."} saíram só em
     * PT na abertura (2 de 16), porque o fragmento {@code "I"} — que compartilha o instante exato da
     * frase — era classificado {@code ORIGINAL_JAPONES} no PRÉ-PASSE (que ainda não tem o sinal de
     * sílaba) e marcava o instante. A original de verdade (romaji do ED) é FRASE, nunca fragmento —
     * ver {@link #originalPreservadaPorInstante} (o caso-controle SÃO que precisa continuar TRUE).
     *
     * <h2>Caso-controle de fronteira (A1)</h2>
     * A sílaba romaji-aparente no mesmo instante NÃO marca (aqui, FALSE); a FRASE romaji real no
     * mesmo instante MARCA (em {@link #originalPreservadaPorInstante}, TRUE). Os dois carregam o
     * mesmo sinal superficial ("parece romaji"); a régua é ser sílaba de fill ou frase.
     */
    @Test
    @DisplayName("silaba que parece romaji NAO marca original no instante (frase inglesa mantem o ingles)")
    void silabaRomajiAparenteNaoMarcaOriginalNoInstante(@org.junit.jupiter.api.io.TempDir Path pasta)
            throws IOException {
        DocumentoLegenda doc = documento(pasta,
            "Dialogue: 0,0:01:00.00,0:01:05.00,OPL2,,0,0,0,fx,{\\pos(640,60)}I know",
            "Dialogue: 0,0:01:00.00,0:01:05.00,OPL2,,0,0,0,fx,{\\pos(560,60)}I",
            "Dialogue: 0,0:01:02.00,0:01:05.00,OPL2,,0,0,0,fx,{\\pos(600,60)}know");

        PlanoDeClassificacao plano = PlanoDeClassificacao.montar(doc, classificador);

        assertEquals(ClasseLinhaKaraoke.TRADUZIVEL_INGLES, plano.classeNaPosicao(0),
            "a frase inglesa e karaoke traduzivel (Effect=fx)");
        assertFalse(plano.temOriginalPreservadaNoInstante(doc.eventos().get(0)),
            "a silaba 'I' que parece romaji NAO pode marcar o instante — senao a frase inglesa perde "
                + "o empilhamento do original e sai so em PT (o defeito das 2 frases da abertura OPL2)");
    }

    /**
     * PROPÓSITO DE NEGÓCIO: o outro lado da fronteira do fix #3 — a original ROMAJI de verdade,
     * mesmo quando o karaokê a pinta em SÍLABAS, tem de continuar marcando o instante. O fix pula
     * a SÍLABA (fragmento) do {@code comRomaji}, não a FRASE: a frase romaji inteira não é sílaba de
     * ninguém, então preserva. Sem esta garantia, "consertar" o #3 excluindo demais reabriria o
     * scar das 22/23 linhas do ED.
     *
     * <p>Aqui a frase romaji {@code "kimi no koe"} convive com suas sílabas ({@code ki},{@code mi},
     * {@code no},{@code koe}, que reconstroem {@code kiminokoe}) e com uma camada inglesa no MESMO
     * instante. A inglesa TEM de ver original preservada (a frase romaji), apesar de as sílabas
     * serem puladas.
     */
    @Test
    @DisplayName("fronteira #3: frase romaji com silabas KFX ainda marca o instante (nao sobre-exclui)")
    void fraseRomajiComSilabasAindaMarcaOInstante(@org.junit.jupiter.api.io.TempDir Path pasta)
            throws IOException {
        DocumentoLegenda doc = documento(pasta,
            "Dialogue: 0,0:01:00.00,0:01:05.00,OP - Romaji,,0,0,0,,kimi no koe",
            "Dialogue: 0,0:01:00.00,0:01:05.00,OP - Romaji,,0,0,0,,ki",
            "Dialogue: 0,0:01:01.00,0:01:05.00,OP - Romaji,,0,0,0,,mi",
            "Dialogue: 0,0:01:02.00,0:01:05.00,OP - Romaji,,0,0,0,,no",
            "Dialogue: 0,0:01:03.00,0:01:05.00,OP - Romaji,,0,0,0,,koe",
            "Dialogue: 0,0:01:00.00,0:01:05.00,Camada2,,0,0,0,,your voice");

        PlanoDeClassificacao plano = PlanoDeClassificacao.montar(doc, classificador);

        assertEquals(ClasseLinhaKaraoke.ORIGINAL_JAPONES, plano.classeNaPosicao(0),
            "a FRASE romaji e a original — nao e silaba de ninguem");
        assertTrue(plano.temOriginalPreservadaNoInstante(doc.eventos().get(5)),
            "a camada inglesa no mesmo instante TEM de ver a original romaji preservada, mesmo com "
                + "as silabas puladas — senao o fix #3 reabriria o scar 22/23 do ED");
    }

    /**
     * PROPÓSITO DE NEGÓCIO (decisão de Paulo, 12/10/2026, Break Blade 1-5): o último verso do
     * encerramento é cantado em inglês ("ALIVE SERIOUS AGE", na altura do romaji) e o fansub pôs a
     * tradução dele embaixo ("Alive in this serious age"). A tela mostrava QUATRO linhas — os dois
     * ingleses, cada um com um português empilhado. Paulo: o original fica INTACTO em cima e embaixo
     * fica SÓ o português.
     *
     * <p>A1, mesmo sinal dos dois lados: as duas linhas candidatas são "toda inglês na camada
     * original". Só a que tem a tradução do fansub no MESMO momento fica intacta; a que canta
     * sozinha (Zeta, 0:00:40) continua virando português empilhado (F8 de 24/09).
     */
    @Test
    @DisplayName("F8 + fansub: ingles cantado com a traducao do fansub ao lado fica intacto e o fansub vira so PT")
    void inglesCantadoComTraducaoDoFansubFicaIntacto(@org.junit.jupiter.api.io.TempDir Path pasta) throws IOException {
        String cima = "Dialogue: 0,%s,Ending,,0,0,0,,{\\be1\\fad(0,200)\\pos(502.5,165)}%s";
        String baixo = "Dialogue: 0,%s,Ending,,0,0,0,,{\\be1\\fad(0,200)\\pos(502.5,930)}%s";
        DocumentoLegenda doc = documento(pasta,
            String.format(cima, "0:49:57.89,0:50:02.00", "Hashiridashite Kigatsuitanda"),
            String.format(cima, "0:50:02.31,0:50:07.57", "Omoi wa Tsunagaru Tabi Kieru"),
            String.format(cima, "0:50:07.86,0:50:20.00", "Futatabi Aeru Hi no\\NTame ni Ima o Ikiru yo"),
            String.format(cima, "0:50:20.50,0:50:26.09", "ALIVE SERIOUS AGE"),
            String.format(baixo, "0:49:57.89,0:50:02.00", "I began to run and noticed too late"),
            String.format(baixo, "0:50:02.31,0:50:07.57", "that feelings die when they connect"),
            String.format(baixo, "0:50:07.86,0:50:20.00", "I will live in the present for\\Nwe may meet in the future"),
            String.format(baixo, "0:50:20.50,0:50:26.09", "Alive in this serious age"),
            // 8: ingles na altura do romaji SEM a traducao do fansub ao lado — continua traducao
            String.format(cima, "0:51:00.00,0:51:04.00", "ALIVE SERIOUS AGE"));

        PlanoDeClassificacao plano = PlanoDeClassificacao.montar(doc, classificador);

        assertEquals(ClasseLinhaKaraoke.ORIGINAL_JAPONES, plano.classeNaPosicao(0), "o romaji e o original");
        assertEquals(ClasseLinhaKaraoke.ORIGINAL_JAPONES, plano.classeNaPosicao(3),
            "'ALIVE SERIOUS AGE' esta na camada do romaji e tem a traducao do fansub ao lado: original, intacto");
        assertTrue(plano.temOriginalPreservadaNoInstante(doc.eventos().get(7)),
            "com o original intacto em cima, 'Alive in this serious age' vira SO portugues");
        assertEquals(ClasseLinhaKaraoke.TRADUZIVEL_INGLES, plano.classeNaPosicao(7), "o fansub continua traduzivel");
        assertEquals(ClasseLinhaKaraoke.TRADUZIVEL_INGLES, plano.classeNaPosicao(8),
            "A1: o mesmo ingles na mesma camada, SEM traducao ao lado, continua traducao (empilha)");
        assertFalse(plano.temOriginalPreservadaNoInstante(doc.eventos().get(8)),
            "A1: sozinho ele e a letra — nao pode achar a si mesmo como original");
    }

    /**
     * O mesmo caso pelo caminho do F8: o estilo da camada é "Song JP" (o classificador já chama a
     * linha de ORIGINAL) e ela é toda inglesa. Com a tradução do fansub no mesmo momento ela fica
     * intacta; sozinha (Zeta), continua virando português empilhado.
     */
    @Test
    @DisplayName("F8 + fansub: linha toda inglesa no estilo JP com a traducao do fansub ao lado fica intacta")
    void f8ComTraducaoDoFansubFicaIntacto(@org.junit.jupiter.api.io.TempDir Path pasta) throws IOException {
        DocumentoLegenda doc = documento(pasta,
            "Dialogue: 0,0:03:00.00,0:03:04.00,Song JP,,0,0,0,,I wanna have a pure time!",
            "Dialogue: 0,0:03:00.00,0:03:04.00,OP - English,,0,0,0,,I wanna have a pure time!",
            "Dialogue: 0,0:00:40.23,0:00:43.27,Song JP,,0,0,0,,I wanna have a pure time!");

        PlanoDeClassificacao plano = PlanoDeClassificacao.montar(doc, classificador, new TresDicionarios(true));

        assertEquals(ClasseLinhaKaraoke.TRADUZIVEL_INGLES, plano.classeNaPosicao(1),
            "pre-condicao: a camada do fansub e traducao");
        assertEquals(ClasseLinhaKaraoke.ORIGINAL_JAPONES, plano.classeNaPosicao(0),
            "com a traducao do fansub no mesmo momento, a linha cantada fica intacta");
        assertTrue(plano.temOriginalPreservadaNoInstante(doc.eventos().get(1)),
            "e o fansub vira so portugues");
        assertEquals(ClasseLinhaKaraoke.TRADUZIVEL_INGLES, plano.classeNaPosicao(2),
            "A1: sozinha (Zeta) ela continua virando portugues empilhado");
    }

    /** FALHA FECHADA: documento nulo e posição fora da faixa não podem virar exceção. */
    @Test
    @DisplayName("FALHA FECHADA: nulo e posicao invalida devolvem FORA_DE_MUSICA")
    void falhaFechada() {
        PlanoDeClassificacao vazio = PlanoDeClassificacao.montar(null, classificador);
        assertEquals(0, vazio.total());
        assertEquals(ClasseLinhaKaraoke.FORA_DE_MUSICA, vazio.classeNaPosicao(0));
        assertEquals(ClasseLinhaKaraoke.FORA_DE_MUSICA, vazio.classeNaPosicao(-1));
        assertEquals(ClasseLinhaKaraoke.FORA_DE_MUSICA, vazio.classeNaPosicao(999));
        assertFalse(vazio.temOriginalPreservadaNoInstante(null));
    }
}
