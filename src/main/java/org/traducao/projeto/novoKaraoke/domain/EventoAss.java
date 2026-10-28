package org.traducao.projeto.novoKaraoke.domain;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Um evento {@code Dialogue:} de um arquivo .ass, com a linha crua preservada
 * byte a byte. A conversão de karaokê NUNCA reescreve eventos que decide
 * manter — ela reemite {@link #linhaCrua()} — para garantir que diálogo,
 * placas e blocos preservados saiam idênticos ao arquivo de origem.
 *
 * @param linhaCrua  linha original completa, exatamente como lida do arquivo
 * @param camada     campo Layer
 * @param inicio     campo Start (mantido como texto para não perder precisão)
 * @param fim        campo End
 * @param estilo     campo Style
 * @param efeito     campo Effect (9º campo): carimbo do Kara Templater do Aegisub
 *                   ({@code fx}, {@code Effector [fx]}) quando presente — evidência
 *                   direta de karaokê que o nome do estilo e a tag {@code \k} podem não trazer
 * @param texto      campo Text (último campo, pode conter vírgulas)
 */
public record EventoAss(
    String linhaCrua,
    int camada,
    String inicio,
    String fim,
    String estilo,
    String efeito,
    String texto
) {

    private static final Pattern PADRAO_REMOVE_TAGS = Pattern.compile("\\{[^}]*\\}");
    /**
     * Centésimos (2 casas, o ASS) ou milésimos (3 casas, que alguns editores gravam): até 28/10/2026
     * só 2 casas casavam, o tempo virava -1 e a saída ganhava {@code 0:00:00.-1}.
     */
    private static final Pattern PADRAO_TEMPO = Pattern.compile("(\\d+):(\\d{2}):(\\d{2})[.,](\\d{2,3})");

    /** Interpreta uma linha "Dialogue: ..." ou retorna {@code null} se não for evento. */
    public static EventoAss interpretar(String linha) {
        if (linha == null || !linha.startsWith("Dialogue:")) {
            return null;
        }
        String corpo = linha.substring("Dialogue:".length()).stripLeading();
        // Formato: Layer,Start,End,Style,Name,MarginL,MarginR,MarginV,Effect,Text
        String[] campos = corpo.split(",", 10);
        if (campos.length < 10) {
            return null;
        }
        int camada;
        try {
            camada = Integer.parseInt(campos[0].strip());
        } catch (NumberFormatException e) {
            camada = 0;
        }
        return new EventoAss(linha, camada, campos[1].strip(), campos[2].strip(), campos[3].strip(),
            campos[8].strip(), campos[9]);
    }

    /** Texto visível na tela: sem blocos {@code {...}} e com quebras viradas espaço. */
    public String textoVisivel() {
        return PADRAO_REMOVE_TAGS.matcher(texto)
            .replaceAll("")
            .replace("\\N", " ")
            .replace("\\n", " ")
            .replace("\\h", " ")
            .strip();
    }

    /**
     * PROPÓSITO DE NEGÓCIO: o texto sem as tags de animação, mas com a quebra {@code \N}
     * DELIBERADA preservada. Existe porque a tradução de karaokê (Passo 1) entrega o par
     * bilíngue — {@code original\Ntradução} — num evento ÚNICO, e o achatamento não pode
     * fundir essas duas linhas numa só: {@link #textoVisivel()} troca {@code \N} por espaço
     * (certo para AGRUPAR sílabas), e usá-lo na saída colava "inglês PT" numa linha só.
     *
     * <p>INVARIANTES DO DOMÍNIO: só remove o que é decoração ({@code {...}}, {@code \h}); a
     * quebra {@code \N}/{@code \n} de linha é conteúdo e fica. NÃO inventa quebra onde não há.
     *
     * <p>COMPORTAMENTO EM CASO DE FALHA: texto sem {@code \N} devolve o mesmo que
     * {@link #textoVisivel()}; nunca lança.
     */
    public String textoComQuebra() {
        return PADRAO_REMOVE_TAGS.matcher(texto)
            .replaceAll("")
            .replace("\\h", " ")
            .strip();
    }

    /** Início em centésimos de segundo, para ordenação/agrupamento (-1 se ilegível). */
    public long inicioCs() {
        return tempoParaCs(inicio);
    }

    /** Fim em centésimos de segundo (-1 se ilegível). */
    public long fimCs() {
        return tempoParaCs(fim);
    }

    static long tempoParaCs(String tempo) {
        if (tempo == null) {
            return -1;
        }
        Matcher m = PADRAO_TEMPO.matcher(tempo.strip());
        if (!m.matches()) {
            return -1;
        }
        long horas = Long.parseLong(m.group(1));
        long minutos = Long.parseLong(m.group(2));
        long segundos = Long.parseLong(m.group(3));
        String fracao = m.group(4);
        long centesimos = fracao.length() == 3 ? Long.parseLong(fracao) / 10 : Long.parseLong(fracao);
        return ((horas * 60 + minutos) * 60 + segundos) * 100 + centesimos;
    }

    /** Formata centésimos de segundo de volta para o formato de tempo ASS (H:MM:SS.CC). */
    public static String csParaTempo(long cs) {
        long horas = cs / 360000;
        long minutos = (cs % 360000) / 6000;
        long segundos = (cs % 6000) / 100;
        long centesimos = cs % 100;
        return String.format("%d:%02d:%02d.%02d", horas, minutos, segundos, centesimos);
    }
}
