package org.traducao.projeto.novoKaraoke.application;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.traducao.projeto.core.presentation.web.LogStreamService;
import org.traducao.projeto.core.texto.dicionarioOrtografia.CorretorOrtograficoLegenda;
import org.traducao.projeto.core.texto.dicionarioOrtografia.VeredictoPalavra;
import org.traducao.projeto.legenda.application.DetectorEfeitoKaraokeService;
import org.traducao.projeto.novoKaraoke.domain.ResultadoConversaoKaraoke;
import org.traducao.projeto.novoKaraoke.domain.ports.TelemetriaKaraokePort;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * PROPÓSITO DE NEGÓCIO: os achados do Passo 2 (achatar) da auditoria da 4.1 de 28/10/2026, cada um
 * com a entrada mínima do auditor ou o caso real, e o esperado tirado do CONTRATO da doc da 4.1:
 * camadas de línguas diferentes são conteúdo distinto e ficam; romaji preservado acima; diálogo e
 * placas byte a byte; bloco que não reconstrói fica intacto; nunca se perde trecho de música.
 *
 * <p>INVARIANTES DO DOMÍNIO: cada correção tem o contra-caso com o mesmo sinal (A1): variante de
 * verdade do mesmo verso continua fundida; tipografia empilhada de verdade continua achatada.
 *
 * <p>COMPORTAMENTO EM CASO DE FALHA: a asserção mostra o arquivo gerado.
 */
class AchatadorAuditoria41Test {

    @TempDir
    Path pasta;

    private ConversorKaraokeUseCase conversor() {
        ConversorKaraokeUseCase c = new ConversorKaraokeUseCase();
        c.detectorKaraoke = new DetectorEfeitoKaraokeService();
        c.logStream = new LogStreamService() {
            @Override
            public void publicarLog(String canal, String mensagem) {
            }
        };
        c.telemetriaKaraoke = (TelemetriaKaraokePort) java.lang.reflect.Proxy.newProxyInstance(
            getClass().getClassLoader(), new Class<?>[]{TelemetriaKaraokePort.class}, (p, m, a) -> null);
        // Dicionario mudo: estes achados nao dependem dele (o par cai no diacritico).
        c.corretorOrtografico = new CorretorOrtograficoLegenda() {
            @Override
            public Map<String, VeredictoPalavra> classificarPalavras(Collection<String> palavras) {
                return Map.of();
            }
        };
        return c;
    }

    private String converter(String nome, String... linhas) throws IOException {
        return converterComResultado(nome, linhas).saida;
    }

    private record Conversao(String saida, ResultadoConversaoKaraoke resultado) {
    }

    private Conversao converterComResultado(String nome, String... linhas) throws IOException {
        Path origem = Files.createDirectories(pasta.resolve("in-" + nome)).resolve(nome + ".ass");
        Path destino = Files.createDirectories(pasta.resolve("out-" + nome));
        StringBuilder sb = new StringBuilder("[Script Info]\nPlayResY: 1080\n\n[V4+ Styles]\n"
            + "Format: Name, Fontname, Fontsize, PrimaryColour, SecondaryColour, OutlineColour, BackColour, Bold, Italic, "
            + "Underline, StrikeOut, ScaleX, ScaleY, Spacing, Angle, BorderStyle, Outline, Shadow, Alignment, MarginL, "
            + "MarginR, MarginV, Encoding\n"
            + "Style: Default,Arial,48,&H00FFFFFF,&H000000FF,&H00000000,&H96000000,0,0,0,0,100,100,0,0,1,2,1,2,30,30,30,1\n\n"
            + "[Events]\nFormat: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text\n");
        for (String l : linhas) {
            sb.append(l).append('\n');
        }
        Files.writeString(origem, sb.toString(), StandardCharsets.UTF_8);
        ResultadoConversaoKaraoke r = conversor().converterArquivo(origem, destino, true);
        return new Conversao(Files.readString(destino.resolve(origem.getFileName()), StandardCharsets.UTF_8), r);
    }

    private static long ocorrencias(String saida, String trecho) {
        return saida.lines().filter(l -> l.contains(trecho)).count();
    }

    @Test
    @DisplayName("D2: romaji e PT em estilos diferentes com termo de lore em comum ficam OS DOIS")
    void loreEmComumNaoFundeIdiomasDiferentes() throws IOException {
        String s = converter("t7",
            "Dialogue: 0,0:00:10.00,0:00:14.00,OP Romaji,,0,0,0,,{\\pos(960,60)}Mobile Suit no yume wa owaranai",
            "Dialogue: 0,0:00:10.00,0:00:14.00,OP Trans,,0,0,0,,{\\pos(960,1000)}O sonho do Mobile Suit não acaba");
        assertTrue(s.contains("Mobile Suit no yume wa owaranai") && s.contains("O sonho do Mobile Suit não acaba"), s);
    }

    @Test
    @DisplayName("D2: no mesmo estilo (Reconguista V, 1:31:25) a traducao PT nao some")
    void reconguistaVTraducaoNaoSome() throws IOException {
        String s = converter("rgv",
            "Dialogue: 0,1:31:25.92,1:31:30.00,ED Top,,0,0,0,,{\\pos(960,60)}mobile suits ha shinka suru noni",
            "Dialogue: 0,1:31:25.92,1:31:30.00,ED Top,,0,0,0,,{\\pos(960,1000)}Os Mobile Suits evoluem, mas e eu?");
        assertTrue(s.contains("mobile suits ha shinka suru noni") && s.contains("Os Mobile Suits evoluem, mas e eu?"), s);
    }

    @Test
    @DisplayName("D2: mesmo estilo, nome proprio em comum (Unicorn Gundam) — as duas ficam")
    void nomeProprioEmComumNaoFunde() throws IOException {
        String s = converter("t8",
            "Dialogue: 0,0:00:10.00,0:00:14.00,OP,,0,0,0,,{\\pos(960,60)}Unicorn Gundam yo, tobe",
            "Dialogue: 0,0:00:10.00,0:00:14.00,OP,,0,0,0,,{\\pos(960,1000)}Voe, Unicorn Gundam");
        assertTrue(s.contains("Unicorn Gundam yo, tobe") && s.contains("Voe, Unicorn Gundam"), s);
    }

    @Test
    @DisplayName("D2 regressao medida no acervo: romaji capitalizado e traducao com 'to' em comum continuam empilhados (Break Blade)")
    void romajiCapitalizadoNaoFundeComTraducao() throws IOException {
        String s = converter("bb",
            "Dialogue: 0,0:48:52.66,0:48:58.21,Ending,,0,0,0,,{\\be1\\fad(0,200)\\pos(502.5,60)}Kimi ga Yubi Sashita Kanatae to",
            "Dialogue: 0,0:48:52.66,0:48:58.21,Ending,,0,0,0,,{\\be1\\fad(0,200)\\pos(502.5,930)}You pointed me toward\\Nthe horizon I needed to follow");
        assertTrue(s.contains("Kimi ga Yubi Sashita Kanatae to\\NYou pointed me toward the horizon I needed to follow"), s);
    }

    @Test
    @DisplayName("D2 regressao medida no acervo: traducao inglesa com duas palavras de lore do romaji nao e fundida (Reconguista V)")
    void traducaoComDuasPalavrasDeLoreNaoEhVariante() throws IOException {
        String s = converter("rgv-en",
            "Dialogue: 0,1:31:25.92,1:31:30.13,ED Top,,0,0,65,,{\\fad(100,100)\\fs32\\blur1\\bord1.5}mobile suits ha shinka suru noni",
            "Dialogue: 0,1:31:25.92,1:31:30.13,ED Top,,0,0,65,,{\\fad(100,100)\\fs32\\blur1\\bord1.5}Mobile suits are evolving, but what about me?");
        assertTrue(s.contains("mobile suits ha shinka suru noni\\NMobile suits are evolving, but what about me?"), s);
    }

    @Test
    @DisplayName("D3 regressao medida no acervo: palavra solta que nao e romaji ('Claro!') nao empilha na abertura (0083 E02)")
    void palavraSoltaQueNaoEhRomajiNaoEmpilha() throws IOException {
        String s = converter("0083",
            "Dialogue: 0,0:01:34.61,0:01:39.91,OP,,0,0,0,,{\\pos(960,60)}kogoeta kokoro sae atsuku saseru",
            "Dialogue: 0,0:01:34.61,0:01:39.91,OP,,0,0,0,,{\\pos(960,1000)}Claro!");
        assertFalse(s.lines().anyMatch(l -> l.contains("Karaoke Simples") && l.contains("Claro!")), s);
    }

    @Test
    @DisplayName("D6 regressao medida no acervo: a variante que perdeu o voto sai com o verso, nao fica crua (0083)")
    void variantePerdedoraNaoFicaCrua() throws IOException {
        String s = converter("var0083",
            "Dialogue: 0,0:01:21.52,0:01:27.07,Song ENG,,0,0,0,,{\\blur2}Não preciso de sonhos que se desfizeram.",
            "Dialogue: 0,0:01:21.52,0:01:27.07,Song ENG,,0,0,0,,{\\blur2}Não preciso de sonhos que se desfizeram.",
            "Dialogue: 0,0:01:21.52,0:01:27.07,Song ENG,,0,0,0,,{\\be0\\1a&HFF&\\blur2}Não preciso de sonhos que desmoronaram.");
        assertFalse(s.lines().anyMatch(l -> l.contains(",Song ENG,")), s);
        assertEquals(1, s.lines().filter(l -> l.startsWith("Dialogue:") && l.contains("Karaoke Simples")).count(), s);
    }

    @Test
    @DisplayName("D2 contra-caso: duas camadas do MESMO verso continuam virando uma linha so")
    void varianteDeVerdadeContinuaFundida() throws IOException {
        String s = converter("var",
            "Dialogue: 0,0:00:10.00,0:00:14.00,OP Eng,,0,0,0,,{\\pos(960,1000)}Do you feel alone",
            "Dialogue: 1,0:00:10.00,0:00:14.00,OP Eng Glow,,0,0,0,,{\\pos(960,1000)\\blur3}Do you feel alone?");
        assertEquals(1, s.lines().filter(l -> l.startsWith("Dialogue:") && l.contains("Karaoke Simples")).count(), s);
    }

    @Test
    @DisplayName("D3: romaji de UMA palavra no mesmo estilo da traducao nao some")
    void romajiDeUmaPalavraNoEstiloDaTraducaoFica() throws IOException {
        String s = converter("d3",
            "Dialogue: 0,0:00:37.62,0:00:41.00,OP Top,,0,0,0,,{\\pos(960,60)}wasurerarenai",
            "Dialogue: 0,0:00:37.62,0:00:41.00,OP Top,,0,0,0,,{\\pos(960,1000)}Isso nunca sairá da minha mente...");
        assertTrue(s.contains("wasurerarenai") && s.contains("Isso nunca sairá da minha mente..."), s);
    }

    @Test
    @DisplayName("D4: placa dividida por \\clip e placas em \\pos diferentes nao perdem camada")
    void colapsoRespeitaPosicaoERecorte() throws IOException {
        String s = converter("t9",
            "Dialogue: 0,0:01:00.00,0:01:04.00,OP,,0,0,0,,{\\pos(960,60)}kimi no koe ga kikoeru",
            "Dialogue: 0,0:00:05.00,0:00:08.00,Signs,,0,0,0,,{\\pos(300,500)\\clip(0,0,960,1080)\\c&H0000FF&}SAIDA",
            "Dialogue: 1,0:00:05.00,0:00:08.00,Signs,,0,0,0,,{\\pos(300,500)\\clip(960,0,1920,1080)\\c&H00FF00&}SAIDA",
            "Dialogue: 0,0:00:10.00,0:00:13.00,Signs,,0,0,0,,{\\pos(200,300)}PERIGO",
            "Dialogue: 1,0:00:10.00,0:00:13.00,Signs,,0,0,0,,{\\pos(1700,300)}PERIGO");
        assertEquals(2, ocorrencias(s, "SAIDA"), s);
        assertEquals(2, ocorrencias(s, "PERIGO"), s);
    }

    @Test
    @DisplayName("D4 contra-caso: tipografia empilhada no MESMO lugar continua achatada na camada de cima")
    void empilhadoDeVerdadeContinuaAchatado() throws IOException {
        String s = converter("emp",
            "Dialogue: 0,0:01:00.00,0:01:04.00,OP,,0,0,0,,{\\pos(960,60)}kimi no koe ga kikoeru",
            "Dialogue: 0,0:00:05.00,0:00:08.00,Signs,,0,0,0,,{\\pos(300,500)\\1a&HFF&\\blur5}DATA",
            "Dialogue: 1,0:00:05.00,0:00:08.00,Signs,,0,0,0,,{\\pos(300,500)\\c&H000000&}DATA",
            "Dialogue: 2,0:00:05.00,0:00:08.00,Signs,,0,0,0,,{\\pos(300,500)}DATA");
        assertEquals(1, ocorrencias(s, "DATA"), s);
        assertTrue(s.contains("Dialogue: 2,0:00:05.00,0:00:08.00,Signs,,0,0,0,,{\\pos(300,500)}DATA"), s);
    }

    @Test
    @DisplayName("D1: desenho vetorial \\p1 nao vira letra nem estica o verso")
    void desenhoNaoViraLetra() throws IOException {
        String[] linhas = new String[13];
        for (int i = 0; i < 10; i++) {
            int s = 10 + 3 * i;
            linhas[i] = String.format("Dialogue: 2,0:00:%02d.00,0:00:%02d.80,ED,,0,0,0,fx,{\\an7\\pos(800,60)\\p1\\alpha&HFF&}m 0 0 l 0 -10 b 0 0 0 0 10 0", s, s);
        }
        linhas[10] = "Dialogue: 0,0:00:10.00,0:00:14.00,ED,,0,0,0,,{\\pos(960,60)}kimi no koe ga kikoeru";
        linhas[11] = "Dialogue: 0,0:00:15.00,0:00:19.00,ED,,0,0,0,,{\\pos(960,60)}sora wa aoku hirogaru";
        linhas[12] = "Dialogue: 0,0:00:20.00,0:00:24.00,ED,,0,0,0,,{\\pos(960,60)}ashita mo mata aeru";
        String s = converter("t4", linhas);
        assertFalse(s.lines().anyMatch(l -> l.contains("Karaoke Simples") && l.contains("m 0 0 l")), s);
        assertTrue(s.contains("0:00:10.00,0:00:14.00,Karaoke Simples"), "o verso nao pode esticar: " + s);
    }

    @Test
    @DisplayName("D5: romaji original vence a variante pulverizada (k i m i n o ...), como a doc promete")
    void romajiVencePulverizado() throws IOException {
        String s = converter("t10",
            "Dialogue: 0,0:00:10.00,0:00:14.00,OP Romaji,,0,0,0,,{\\pos(960,60)}kimi no koe ga kikoeru",
            "Dialogue: 0,0:00:10.00,0:00:14.00,OP FX,,0,0,0,,{\\pos(960,60)}k i m i n o k o e g a k i k o e r u",
            "Dialogue: 0,0:00:10.00,0:00:14.00,OP Trans,,0,0,0,,{\\pos(960,1000)}Eu ouço a sua voz");
        assertFalse(s.contains("k i m i n o"), s);
        assertTrue(s.contains("kimi no koe ga kikoeru\\NEu ouço a sua voz"), s);
    }

    @Test
    @DisplayName("D6: evento do estilo que nao pertence ao verso ((Hey!)) fica intacto, nao some pela janela")
    void foraDoVersoNaoSomePelaJanela() throws IOException {
        String s = converter("t1",
            "Dialogue: 0,0:00:10.00,0:00:14.00,OP,,0,0,0,,{\\pos(640,50)}kimi no koe ga kikoeru",
            "Dialogue: 0,0:00:11.00,0:00:13.00,OP,,0,0,0,,{\\pos(640,1000)}(Hey!)",
            "Dialogue: 0,0:00:01.00,0:00:03.00,Default,,0,0,0,,Fala.");
        assertTrue(s.contains("(Hey!)"), s);
    }

    @Test
    @DisplayName("D9: os eventos ficam na secao [Events], antes de [Aegisub Extradata]")
    void eventosNaSecaoDeEventos() throws IOException {
        String s = converter("extra",
            "Dialogue: 0,0:00:10.00,0:00:14.00,OP,,0,0,0,,{\\pos(640,50)}kimi no koe ga kikoeru",
            "Dialogue: 0,0:00:01.00,0:00:03.00,Default,,0,0,0,,Fala.",
            "",
            "[Aegisub Extradata]",
            "Data: 1,abc,e123");
        int extradata = s.indexOf("[Aegisub Extradata]");
        int ultimaFala = s.lastIndexOf("Dialogue:");
        assertTrue(extradata > ultimaFala, s);
    }

    @Test
    @DisplayName("D10: o contador de dialogo preservado e o que saiu, depois do colapso")
    void contadorDeDialogoDepoisDoColapso() throws IOException {
        Conversao c = converterComResultado("cnt",
            "Dialogue: 0,0:01:00.00,0:01:04.00,OP,,0,0,0,,{\\pos(960,60)}kimi no koe ga kikoeru",
            "Dialogue: 0,0:00:05.00,0:00:08.00,Signs,,0,0,0,,{\\pos(300,500)\\1a&HFF&}DATA",
            "Dialogue: 1,0:00:05.00,0:00:08.00,Signs,,0,0,0,,{\\pos(300,500)}DATA",
            "Dialogue: 0,0:00:01.00,0:00:03.00,Default,,0,0,0,,Fala.");
        long emitidos = c.saida().lines().filter(l -> l.startsWith("Dialogue:") && !l.contains("Karaoke Simples")).count();
        assertEquals(emitidos, c.resultado().getEventosDialogoPreservados(), c.saida());
    }

    @Test
    @DisplayName("D11: Format de eventos fora da ordem padrao nao e convertido (as colunas seriam lidas erradas)")
    void formatForaDaOrdemNaoConverte() throws IOException {
        Path origem = Files.createDirectories(pasta.resolve("in-fmt")).resolve("fmt.ass");
        Path destino = Files.createDirectories(pasta.resolve("out-fmt"));
        String conteudo = "[Script Info]\nPlayResY: 1080\n\n[Events]\n"
            + "Format: Layer, Style, Start, End, Name, MarginL, MarginR, MarginV, Effect, Text\n"
            // \k faz a linha ser musica pelo TEXTO, qualquer que seja a coluna lida como estilo — sem
            // isso o arquivo sairia intacto por outro caminho e o teste nao discriminaria.
            + "Dialogue: 0,OP,0:00:10.00,0:00:14.00,,0,0,0,,{\\k50}kimi {\\k50}no {\\k50}koe {\\k50}ga {\\k50}kikoeru\n";
        Files.writeString(origem, conteudo, StandardCharsets.UTF_8);
        conversor().converterArquivo(origem, destino, true);
        assertEquals(conteudo, Files.readString(destino.resolve("fmt.ass"), StandardCharsets.UTF_8),
            "com colunas em outra ordem, a saida tem de ser a entrada intacta");
    }

    @Test
    @DisplayName("D11: tempo com tres casas decimais nao sai como 0:00:00.-1")
    void tempoComTresCasas() throws IOException {
        String s = converter("ms",
            "Dialogue: 0,0:00:10.000,0:00:14.000,OP,,0,0,0,,{\\pos(640,50)}kimi no koe ga kikoeru");
        assertFalse(s.contains(".-1"), s);
    }
}
