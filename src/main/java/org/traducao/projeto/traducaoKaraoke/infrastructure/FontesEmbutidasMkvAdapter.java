package org.traducao.projeto.traducaoKaraoke.infrastructure;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.traducao.projeto.core.util.ProcessoExternoUtil;
import org.traducao.projeto.traducaoKaraoke.domain.CoberturaDeFontes;
import org.traducao.projeto.traducaoKaraoke.domain.FontesDoVideo;
import org.traducao.projeto.traducaoKaraoke.domain.ports.FontesEmbutidasPort;

import java.awt.Font;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * PROPÓSITO DE NEGÓCIO: lê as fontes anexadas ao MKV (identifica com {@code mkvmerge -J}, extrai
 * com {@code mkvextract attachments} para uma pasta temporária) e responde o que cada uma desenha
 * com o leitor de fontes do próprio Java.
 *
 * <h2>Medido antes de escrever, 12/10/2026</h2>
 * O leitor do Java deu, nas fontes do Unicorn e do 86 Parte 2, o mesmo resultado que o fontTools:
 * Dash Horizon sem {@code á à â ã é ê í ó ô õ ú ç ? ! . ,} e Conformity sem {@code á à â ã é ê í ó
 * ô õ ú ç}; Gandhi Sans, Althea, Androgyne e Fertigo Pro completas.
 *
 * <p>INVARIANTES DO DOMÍNIO: só lê o vídeo. A pasta temporária é apagada no fim de toda chamada,
 * com sucesso ou não. Cada fonte é indexada pela FAMÍLIA e pelo NOME completo, em minúscula —
 * o estilo do {@code .ass} pode citar qualquer um dos dois.
 *
 * <p>COMPORTAMENTO EM CASO DE FALHA: ferramenta ausente, timeout, JSON ilegível ou falha ao extrair
 * devolvem {@link FontesDoVideo#naoVerificadas} com o motivo; uma fonte corrompida é pulada e as
 * demais continuam valendo (a família dela vira "fora do vídeo", que é não verificado). Nunca lança.
 */
@ApplicationScoped
public class FontesEmbutidasMkvAdapter implements FontesEmbutidasPort {

    private static final Logger log = LoggerFactory.getLogger(FontesEmbutidasMkvAdapter.class);
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Duration TIMEOUT_IDENTIFICAR = Duration.ofSeconds(60);
    private static final Duration TIMEOUT_EXTRAIR = Duration.ofSeconds(180);

    @ConfigProperty(name = "traducao-karaoke.mkvmerge-path", defaultValue = "mkvmerge")
    String mkvmerge;

    @ConfigProperty(name = "traducao-karaoke.mkvextract-path", defaultValue = "mkvextract")
    String mkvextract;

    @Override
    public FontesDoVideo lerFontes(Path video) {
        if (video == null || !Files.isRegularFile(video)) {
            return FontesDoVideo.naoVerificadas("video inexistente: " + video);
        }
        String nome = video.getFileName().toString();
        Path temporaria = null;
        try {
            List<Anexo> anexos = anexosDeFonte(video);
            if (anexos.isEmpty()) {
                return new FontesDoVideo(CoberturaDeFontes.nenhuma(), nome, null);
            }
            temporaria = Files.createTempDirectory("kronos-fontes-");
            List<String> comando = new ArrayList<>(List.of(mkvextract, video.toString(), "attachments"));
            for (Anexo a : anexos) {
                comando.add(a.id() + ":" + temporaria.resolve(a.id() + "_" + seguro(a.nome())));
            }
            ProcessoExternoUtil.Resultado r = ProcessoExternoUtil.executar(comando, TIMEOUT_EXTRAIR, true);
            if (r.codigoSaida() != 0) {
                return FontesDoVideo.naoVerificadas("mkvextract saiu " + r.codigoSaida() + " em " + nome);
            }
            Map<String, Font> porNome = new HashMap<>();
            for (Anexo a : anexos) {
                Path arquivo = temporaria.resolve(a.id() + "_" + seguro(a.nome()));
                for (Font f : carregar(arquivo, a.nome())) {
                    porNome.putIfAbsent(f.getFamily(Locale.ROOT).trim().toLowerCase(Locale.ROOT), f);
                    porNome.putIfAbsent(f.getFontName(Locale.ROOT).trim().toLowerCase(Locale.ROOT), f);
                }
            }
            return new FontesDoVideo(new CoberturaAwt(porNome), nome, null);
        } catch (IOException | java.util.concurrent.TimeoutException e) {
            return FontesDoVideo.naoVerificadas("falha ao ler os anexos de " + nome + ": " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return FontesDoVideo.naoVerificadas("leitura interrompida em " + nome);
        } catch (RuntimeException e) {
            return FontesDoVideo.naoVerificadas("falha inesperada ao ler as fontes de " + nome + ": " + e);
        } finally {
            apagar(temporaria);
        }
    }

    private record Anexo(long id, String nome) {
    }

    private List<Anexo> anexosDeFonte(Path video) throws IOException, InterruptedException,
            java.util.concurrent.TimeoutException {
        ProcessoExternoUtil.Resultado r = ProcessoExternoUtil.executar(
            List.of(mkvmerge, "-J", video.toString()), TIMEOUT_IDENTIFICAR, false);
        if (r.codigoSaida() > 1) {
            throw new IOException("mkvmerge -J saiu " + r.codigoSaida());
        }
        JsonNode raiz = JSON.readTree(new String(r.stdout(), StandardCharsets.UTF_8));
        List<Anexo> anexos = new ArrayList<>();
        for (JsonNode a : raiz.path("attachments")) {
            String nome = a.path("file_name").asText("");
            String tipo = a.path("content_type").asText("").toLowerCase(Locale.ROOT);
            String minusculo = nome.toLowerCase(Locale.ROOT);
            if (tipo.contains("font") || minusculo.endsWith(".ttf") || minusculo.endsWith(".otf")
                || minusculo.endsWith(".ttc")) {
                anexos.add(new Anexo(a.path("id").asLong(), nome));
            }
        }
        return anexos;
    }

    private static List<Font> carregar(Path arquivo, String nome) {
        if (!Files.isRegularFile(arquivo)) {
            return List.of();
        }
        try (InputStream in = Files.newInputStream(arquivo)) {
            if (nome.toLowerCase(Locale.ROOT).endsWith(".ttc")) {
                return List.of(Font.createFonts(in));
            }
            return List.of(Font.createFont(Font.TRUETYPE_FONT, in));
        } catch (Exception e) {
            log.warn("Fonte embutida ilegivel, ignorada: {} ({})", nome, e.getMessage());
            return List.of();
        }
    }

    private static String seguro(String nome) {
        return nome.replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", "_");
    }

    private static void apagar(Path pasta) {
        if (pasta == null) {
            return;
        }
        try (Stream<Path> s = Files.walk(pasta)) {
            s.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException e) {
                    log.warn("Nao apagou temporario {}: {}", p, e.getMessage());
                }
            });
        } catch (IOException e) {
            log.warn("Nao apagou a pasta temporaria de fontes {}: {}", pasta, e.getMessage());
        }
    }

    /** A cobertura respondida pelas fontes que o Java conseguiu abrir. */
    static final class CoberturaAwt implements CoberturaDeFontes {
        private final Map<String, Font> porNome;

        CoberturaAwt(Map<String, Font> porNome) {
            this.porNome = Map.copyOf(porNome);
        }

        @Override
        public Optional<String> caracteresSemDesenho(String familia, String texto) {
            if (familia == null || texto == null) {
                return Optional.empty();
            }
            String chave = familia.trim();
            if (chave.startsWith("@")) {
                chave = chave.substring(1);
            }
            Font fonte = porNome.get(chave.toLowerCase(Locale.ROOT));
            if (fonte == null) {
                return Optional.empty();
            }
            StringBuilder faltam = new StringBuilder();
            texto.codePoints().distinct().forEach(cp -> {
                if (!Character.isWhitespace(cp) && !Character.isISOControl(cp) && !fonte.canDisplay(cp)) {
                    faltam.appendCodePoint(cp);
                }
            });
            return Optional.of(faltam.toString());
        }
    }
}
