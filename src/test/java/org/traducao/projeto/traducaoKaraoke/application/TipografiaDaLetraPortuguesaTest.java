package org.traducao.projeto.traducaoKaraoke.application;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.traducao.projeto.legenda.application.DetectorEfeitoKaraokeService;
import org.traducao.projeto.legenda.domain.DocumentoLegenda;
import org.traducao.projeto.legenda.domain.EventoLegenda;
import org.traducao.projeto.traducaoKaraoke.domain.CoberturaDeFontes;
import org.traducao.projeto.traducaoKaraoke.domain.FontesDoVideo;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * PROPÓSITO DE NEGÓCIO: a linha portuguesa da música troca para a fonte do diálogo SÓ quando a
 * fonte da música, verificada nas fontes do vídeo, não desenha o português — decisão de Paulo em
 * 12/10/2026 ("Fonte do diálogo"), depois de ver na TV o "ê" de "Você" sair da fonte de reserva.
 *
 * <h2>O que foi medido antes</h2>
 * Nas fontes embutidas do Unicorn e do 86 Parte 2: Dash Horizon sem {@code á à â ã é ê í ó ô õ ú ç
 * ? ! . ,}; Conformity sem os acentos; Gandhi Sans, Althea, Androgyne e Fertigo Pro completas.
 *
 * <h2>Invariantes do domínio congelados aqui</h2>
 * <ul>
 *   <li>Troca quando falta glifo na fonte da música e o diálogo desenha o texto.</li>
 *   <li><b>Fronteira (A1):</b> fonte da música DIFERENTE da do diálogo mas completa — o mesmo sinal
 *       que leva à pergunta — continua com a fonte estilizada.</li>
 *   <li>Fonte que não viaja no vídeo, leitura que falha, ou fonte do diálogo também sem o glifo:
 *       nada muda, e o estado diz por quê (A8).</li>
 *   <li>O vídeo só é lido quando alguma linha precisa perguntar.</li>
 * </ul>
 *
 * <h2>Comportamento em caso de falha</h2>
 * A mensagem mostra a linha vestida e o estado das fontes.
 */
class TipografiaDaLetraPortuguesaTest {

    private static final String CABECALHO = String.join("\n",
        "[Script Info]",
        "ScriptType: v4.00+",
        "",
        "[V4+ Styles]",
        "Format: Name, Fontname, Fontsize, PrimaryColour, SecondaryColour, OutlineColour, BackColour, "
            + "Bold, Italic, Underline, StrikeOut, ScaleX, ScaleY, Spacing, Angle, BorderStyle, Outline, "
            + "Shadow, Alignment, MarginL, MarginR, MarginV, Encoding",
        "Style: Default,Gandhi Sans,70,&H00FFFFFF,&H000000FF,&H00141414,&H96000000,-1,0,0,0,100,100,0,0,1,3.5,1.5,2,150,150,55,1",
        "Style: Logo,Gandhi Sans,40,&H00FFFFFF,&H000000FF,&H00000000,&H00000000,0,0,0,0,100,100,0,0,1,0,0,8,10,10,10,1",
        "Style: OP - English,Dash Horizon,80,&H00FFFFFF,&H000000FF,&H00000000,&H00000000,0,0,0,0,100,100,0,0,1,0,0,2,10,10,10,1",
        "Style: ED - English,Althea,60,&H00FFFFFF,&H000000FF,&H00000000,&H00000000,0,0,0,0,100,100,0,0,1,2,0,2,10,10,10,1",
        "",
        "[Events]",
        "Format: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text");

    private static int proximo = 0;

    private static EventoLegenda evento(String inicio, String fim, String estilo, String texto) {
        return new EventoLegenda(proximo++, "Dialogue", estilo,
            "Dialogue: 0," + inicio + "," + fim + "," + estilo + ",,0,0,0,,", texto);
    }

    private static final EventoLegenda ABERTURA = evento("0:01:37.00", "0:01:39.80", "OP - English", "Do you feel alone");
    private static final EventoLegenda ENCERRAMENTO = evento("0:22:00.00", "0:22:04.00", "ED - English", "Behind your mask");

    /** Fontes do vídeo com o que cada família NÃO desenha; família fora do mapa = não embutida. */
    private static Supplier<FontesDoVideo> fontes(Map<String, String> faltamPorFamilia, AtomicInteger leituras) {
        CoberturaDeFontes cobertura = (familia, texto) -> {
            String faltam = faltamPorFamilia.get(familia);
            if (faltam == null) {
                return Optional.empty();
            }
            StringBuilder sem = new StringBuilder();
            texto.codePoints().distinct().filter(cp -> faltam.indexOf(cp) >= 0).forEach(sem::appendCodePoint);
            return Optional.of(sem.toString());
        };
        return () -> {
            leituras.incrementAndGet();
            return new FontesDoVideo(cobertura, "ep01.mkv", null);
        };
    }

    private static TipografiaDaLetraPortuguesa tipografia(Supplier<FontesDoVideo> fontes) {
        List<EventoLegenda> eventos = new ArrayList<>();
        eventos.add(evento("0:00:01.00", "0:00:05.00", "Default", "Banagher, voce esta bem?"));
        eventos.add(evento("0:00:06.00", "0:00:10.00", "Default", "Eu estou bem."));
        // O logo animado quadro a quadro: muitos eventos, pouco tempo de tela. Contar eventos o
        // elegeria como diálogo.
        for (int i = 0; i < 12; i++) {
            String ini = String.format("0:00:%02d.00", 20 + i);
            String fim = String.format("0:00:%02d.10", 20 + i);
            eventos.add(evento(ini, fim, "Logo", "UNICORN"));
        }
        eventos.add(ABERTURA);
        eventos.add(ENCERRAMENTO);
        DocumentoLegenda doc = new DocumentoLegenda(CABECALHO, eventos, "\n", false);
        PlanoDeClassificacao plano = PlanoDeClassificacao.montar(doc,
            new ClassificadorLetraKaraokeService(new DetectorEfeitoKaraokeService()));
        return TipografiaDaLetraPortuguesa.doArquivo(doc, plano, fontes);
    }

    @Test
    @DisplayName("a fonte da música sem o 'ê' e o '?' troca para a fonte e o tamanho do diálogo")
    void glifoFaltandoTrocaParaAFonteDoDialogo() {
        AtomicInteger leituras = new AtomicInteger();
        TipografiaDaLetraPortuguesa t = tipografia(fontes(Map.of(
            "Dash Horizon", "áàâãéêíóôõúç?!.,", "Gandhi Sans", ""), leituras));

        String vestida = t.vestir(ABERTURA, "Você se sente sozinho?", false);

        assertEquals("{\\fnGandhi Sans\\fs70}Você se sente sozinho?", vestida);
        assertEquals(1, t.linhasComFonteDoDialogo());
        assertEquals("VERIFICADAS (ep01.mkv)", t.estadoDasFontes());
        assertEquals(1, leituras.get());
    }

    /** Fronteira A1: fonte diferente da do diálogo, mas completa — continua estilizada. */
    @Test
    @DisplayName("fronteira: fonte da música diferente mas completa não é trocada")
    void fonteCompletaContinua() {
        TipografiaDaLetraPortuguesa t = tipografia(fontes(Map.of(
            "Althea", "", "Gandhi Sans", ""), new AtomicInteger()));

        assertEquals("Atrás da sua máscara", t.vestir(ENCERRAMENTO, "Atrás da sua máscara", false));
        assertEquals(0, t.linhasComFonteDoDialogo());
        assertEquals("VERIFICADAS (ep01.mkv)", t.estadoDasFontes());
    }

    @Test
    @DisplayName("fonte da música que não viaja no vídeo: não troca, e o estado diz NÃO VERIFICADA")
    void fonteForaDoVideoNaoTroca() {
        TipografiaDaLetraPortuguesa t = tipografia(fontes(Map.of("Gandhi Sans", ""), new AtomicInteger()));

        assertEquals("Você se sente sozinho?", t.vestir(ABERTURA, "Você se sente sozinho?", false));
        assertEquals("NAO_VERIFICADAS: fonte(s) da musica fora do video [Dash Horizon]", t.estadoDasFontes());
    }

    @Test
    @DisplayName("a fonte do diálogo também sem o glifo: troca nenhuma — seria um buraco por outro")
    void dialogoTambemSemGlifoNaoTroca() {
        TipografiaDaLetraPortuguesa t = tipografia(fontes(Map.of(
            "Dash Horizon", "ê", "Gandhi Sans", "ê"), new AtomicInteger()));

        assertEquals("Você", t.vestir(ABERTURA, "Você", false));
        assertEquals(0, t.linhasComFonteDoDialogo());
    }

    @Test
    @DisplayName("leitura das fontes que falha: nada muda e o motivo vai ao estado")
    void leituraQueFalhaNaoTroca() {
        TipografiaDaLetraPortuguesa t = tipografia(() -> {
            throw new IllegalStateException("mkvextract travou");
        });

        assertEquals("Você", t.vestir(ABERTURA, "Você", false));
        assertEquals("NAO_VERIFICADAS: falha ao ler as fontes: mkvextract travou", t.estadoDasFontes());
    }

    @Test
    @DisplayName("o \\fn do bloco de abertura é a fonte que vale; a tag nova entra no MESMO bloco")
    void fonteInlineDecideETagEntraNoPrimeiroBloco() {
        TipografiaDaLetraPortuguesa t = tipografia(fontes(Map.of(
            "Althea", "", "Conformity", "ãç", "Gandhi Sans", ""), new AtomicInteger()));

        assertEquals("{\\fnConformity\\pos(960,1000)\\fnGandhi Sans\\fs70}Coração",
            t.vestir(ENCERRAMENTO, "{\\fnConformity\\pos(960,1000)}Coração", false));
    }

    @Test
    @DisplayName("frase de KFX que ficou só em português ganha contorno, sombra e cores do diálogo")
    void kfxSoPortuguesGanhaContornoDoDialogo() {
        TipografiaDaLetraPortuguesa t = tipografia(fontes(Map.of(
            "Dash Horizon", "", "Gandhi Sans", ""), new AtomicInteger()));

        assertEquals("{\\pos(960,1050)\\bord3.5\\shad1.5\\3c&H141414&\\3a&H00&\\4c&H000000&\\4a&H96&}Você",
            t.vestir(ABERTURA, "{\\pos(960,1050)}Você", true));
    }

    /**
     * F91, 12/10/2026: o estilo do diálogo usa {@code .VnBook-Antiqua}, fonte vietnamita TCVN3. O
     * leitor de fontes respondeu "desenha tudo" — ela TEM glifo no lugar do "ç" — e na tela a letra
     * saiu "pedaÕos". A fronteira: uma fonte comum cujo nome também começa por "V" continua
     * recebendo a troca.
     */
    @Test
    @DisplayName("fronteira: fonte do diálogo vietnamita antiga (.Vn/VNI-) nunca recebe a letra; a comum recebe")
    void dialogoDeCodificacaoAntigaNaoRecebeALetra() {
        String cabecalho = CABECALHO.replace("Style: Default,Gandhi Sans,", "Style: Default,.VnBook-Antiqua,");
        List<EventoLegenda> eventos = List.of(
            evento("0:00:01.00", "0:00:05.00", "Default", "Fala."), ABERTURA);
        DocumentoLegenda doc = new DocumentoLegenda(cabecalho, new ArrayList<>(eventos), "\n", false);
        PlanoDeClassificacao plano = PlanoDeClassificacao.montar(doc,
            new ClassificadorLetraKaraokeService(new DetectorEfeitoKaraokeService()));
        TipografiaDaLetraPortuguesa antiga = TipografiaDaLetraPortuguesa.doArquivo(doc, plano,
            fontes(Map.of("Dash Horizon", "ç", ".VnBook-Antiqua", ""), new AtomicInteger()));

        assertEquals("Quase como pedaços", antiga.vestir(ABERTURA, "Quase como pedaços", false),
            "trocou para a fonte vietnamita antiga — o 'ç' sai como outra letra na tela");
        assertEquals(0, antiga.linhasComFonteDoDialogo());
        assertTrue(antiga.estadoDasFontes().startsWith("NAO_VERIFICADAS: fonte do dialogo de codificacao antiga"),
            antiga::estadoDasFontes);

        String comum = CABECALHO.replace("Style: Default,Gandhi Sans,", "Style: Default,Vinque Sans,");
        DocumentoLegenda docComum = new DocumentoLegenda(comum, new ArrayList<>(eventos), "\n", false);
        TipografiaDaLetraPortuguesa normal = TipografiaDaLetraPortuguesa.doArquivo(docComum, plano,
            fontes(Map.of("Dash Horizon", "ç", "Vinque Sans", ""), new AtomicInteger()));
        assertEquals("{\\fnVinque Sans\\fs70}Quase como pedaços", normal.vestir(ABERTURA, "Quase como pedaços", false));

        assertTrue(TipografiaDaLetraPortuguesa.codificacaoAntiga(".VnTime"));
        assertTrue(TipografiaDaLetraPortuguesa.codificacaoAntiga("@.VnBook-Antiqua"));
        assertTrue(TipografiaDaLetraPortuguesa.codificacaoAntiga("VNI-Times"));
        assertTrue(!TipografiaDaLetraPortuguesa.codificacaoAntiga("Vinque Sans")
            && !TipografiaDaLetraPortuguesa.codificacaoAntiga("Arial") && !TipografiaDaLetraPortuguesa.codificacaoAntiga(null));
    }

    @Test
    @DisplayName("ninguém precisa perguntar: o vídeo não é lido e o estado é NAO_NECESSARIO")
    void semPerguntaNaoLeOVideo() {
        AtomicInteger leituras = new AtomicInteger();
        TipografiaDaLetraPortuguesa t = tipografia(fontes(Map.of(), leituras));
        EventoLegenda naFonteDoDialogo = evento("0:00:01.00", "0:00:05.00", "Default", "Uma fala");

        assertEquals("Uma fala", t.vestir(naFonteDoDialogo, "Uma fala", false));
        assertEquals(0, leituras.get(), "leu o video sem nenhuma linha precisar");
        assertEquals("NAO_NECESSARIO", t.estadoDasFontes());
    }

    @Test
    @DisplayName("o estilo do diálogo é o de maior tempo de tela, não o de mais eventos")
    void estiloDoDialogoPeloTempoDeTela() {
        List<EventoLegenda> eventos = List.of(
            evento("0:00:01.00", "0:00:05.00", "Default", "Fala longa."),
            evento("0:00:06.00", "0:00:09.00", "Default", "Outra fala."),
            evento("0:00:20.00", "0:00:20.10", "Logo", "U"),
            evento("0:00:21.00", "0:00:21.10", "Logo", "N"),
            evento("0:00:22.00", "0:00:22.10", "Logo", "I"));
        DocumentoLegenda doc = new DocumentoLegenda(CABECALHO, new ArrayList<>(eventos), "\n", false);
        PlanoDeClassificacao plano = PlanoDeClassificacao.montar(doc,
            new ClassificadorLetraKaraokeService(new DetectorEfeitoKaraokeService()));

        assertEquals("Default", TipografiaDaLetraPortuguesa.estiloDoDialogo(doc, plano));
        assertTrue(TipografiaDaLetraPortuguesa.doArquivo(
                new DocumentoLegenda("[Events]", new ArrayList<>(), "\n", false), plano, null)
            .estadoDasFontes().startsWith("NAO_VERIFICADAS"), "sem estilo do dialogo o estado tem de dizer por que");
    }
}
