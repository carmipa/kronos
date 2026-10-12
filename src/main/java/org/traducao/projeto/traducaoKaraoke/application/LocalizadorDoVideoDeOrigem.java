package org.traducao.projeto.traducaoKaraoke.application;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * PROPÓSITO DE NEGÓCIO: acha o vídeo de onde a legenda saiu, para que a tradução de karaokê saiba
 * quais fontes viajam no MKV — sem pedir ao operador mais um campo de pasta na tela.
 *
 * <h2>O critério, e por que ele é estreito de propósito</h2>
 * O vídeo é o MKV cujo nome, sem extensão nem os sufixos que a esteira acrescenta à legenda
 * ({@code _Track4}, {@code _PT-BR}, {@code .parcial}) e ao vídeo publicado ({@code _PTBR}), é o
 * MESMO nome da legenda. No acervo, a legenda {@code X_Track4_PT-BR.ass} sai do vídeo {@code X.mkv},
 * e o remux publica {@code X_PTBR.mkv} com os mesmos anexos. Procura-se na pasta acima da pasta das
 * legendas e nas pastas dentro dela; não achando, um nível acima — o Unicorn do acervo guarda as
 * legendas em {@code obra/texte/traducao_ptbr} e os vídeos em {@code obra/Gundam Unicorn Season 1}.
 *
 * <p>Não há pareamento por número de episódio: quando o nome não casa, a resposta é "não achei", e
 * a fonte fica NÃO VERIFICADA (A8) — trocar a fonte com base no vídeo de OUTRO episódio seria
 * decisão tomada sobre dado errado.
 *
 * <p>INVARIANTES DO DOMÍNIO: só lê nomes de arquivo; nunca abre vídeo. Entre os casados, o vídeo de
 * origem vence o publicado ({@code _PTBR}); dois candidatos do mesmo tipo são ambiguidade e não
 * escolhem nenhum.
 *
 * <p>COMPORTAMENTO EM CASO DE FALHA: pasta ilegível conta como pasta sem vídeo; nunca lança. O
 * motivo de não ter achado vem sempre preenchido.
 */
public final class LocalizadorDoVideoDeOrigem {

    /** Sufixos que a esteira acrescenta ao nome — legenda extraída, traduzida, parcial, publicada. */
    private static final Pattern SUFIXOS_DA_ESTEIRA =
        Pattern.compile("(?i)(?:[ ._-]+(?:track\\d+|pt[-_ ]?br|parcial))+$");

    private LocalizadorDoVideoDeOrigem() {
    }

    /** O vídeo achado, ou o motivo de não ter achado. Exatamente um dos dois vem preenchido. */
    public record Localizacao(Path video, String motivo) {
    }

    /**
     * PROPÓSITO DE NEGÓCIO: procura o vídeo da legenda nas duas alturas descritas na classe.
     *
     * <p>INVARIANTES DO DOMÍNIO: a primeira altura que achar exatamente um candidato decide.
     *
     * <p>COMPORTAMENTO EM CASO DE FALHA: legenda nula ou sem pasta devolve motivo.
     */
    public static Localizacao localizar(Path legenda) {
        if (legenda == null || legenda.toAbsolutePath().getParent() == null) {
            return new Localizacao(null, "legenda sem pasta");
        }
        Path pastaLegendas = legenda.toAbsolutePath().getParent();
        String identidade = identidade(legenda.getFileName().toString());
        Path raiz = pastaLegendas.getParent();
        for (int altura = 0; altura < 2 && raiz != null; altura++, raiz = raiz.getParent()) {
            List<Path> casados = new ArrayList<>();
            for (Path mkv : mkvsAteDoisNiveis(raiz, pastaLegendas)) {
                if (identidade.equals(identidade(mkv.getFileName().toString()))) {
                    casados.add(mkv);
                }
            }
            List<Path> deOrigem = casados.stream().filter(p -> !ehPublicado(p)).toList();
            List<Path> escolhidos = deOrigem.isEmpty() ? casados : deOrigem;
            if (escolhidos.size() == 1) {
                return new Localizacao(escolhidos.getFirst(), null);
            }
            if (escolhidos.size() > 1) {
                return new Localizacao(null, "mais de um vídeo com o nome da legenda: "
                    + escolhidos.stream().map(p -> p.getFileName().toString()).toList());
            }
        }
        return new Localizacao(null, "nenhum vídeo .mkv com o nome da legenda perto da pasta "
            + pastaLegendas.getFileName());
    }

    /**
     * PROPÓSITO DE NEGÓCIO: o nome reduzido ao que identifica o episódio, para casar legenda e vídeo.
     *
     * <p>INVARIANTES DO DOMÍNIO: sem extensão, sem os sufixos da esteira, só letras e dígitos em
     * minúscula — a diferença entre {@code _} e espaço não separa dois episódios.
     *
     * <p>COMPORTAMENTO EM CASO DE FALHA: nome nulo vira texto vazio, que não casa com nada não vazio.
     */
    static String identidade(String nome) {
        if (nome == null) {
            return "";
        }
        String base = nome.replaceFirst("(?i)\\.(mkv|ass|ssa|srt)$", "");
        base = base.replaceFirst("(?i)\\.parcial$", "");
        base = SUFIXOS_DA_ESTEIRA.matcher(base).replaceAll("");
        return base.toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]+", "");
    }

    private static boolean ehPublicado(Path mkv) {
        return mkv.getFileName().toString().toLowerCase(Locale.ROOT).endsWith("_ptbr.mkv");
    }

    private static List<Path> mkvsAteDoisNiveis(Path raiz, Path ignorar) {
        List<Path> achados = new ArrayList<>();
        for (Path filho : listar(raiz)) {
            if (Files.isRegularFile(filho) && ehMkv(filho)) {
                achados.add(filho);
            } else if (Files.isDirectory(filho) && !filho.equals(ignorar)) {
                for (Path neto : listar(filho)) {
                    if (Files.isRegularFile(neto) && ehMkv(neto)) {
                        achados.add(neto);
                    }
                }
            }
        }
        return achados;
    }

    private static boolean ehMkv(Path p) {
        return p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".mkv");
    }

    private static List<Path> listar(Path pasta) {
        try (Stream<Path> s = Files.list(pasta)) {
            return s.sorted().toList();
        } catch (IOException | RuntimeException e) {
            return List.of();
        }
    }
}
