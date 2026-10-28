package org.traducao.projeto.novoKaraoke.infrastructure;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.traducao.projeto.novoKaraoke.domain.LinhaSimplesKaraoke;
import org.traducao.projeto.novoKaraoke.domain.ResultadoConversaoKaraoke;
import org.traducao.projeto.telemetria.TelemetriaService;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Manifesto de auditoria da conversão de karaokê: registra, por execução, o
 * que foi removido/criado em cada arquivo. Fica em
 * {@code logs/novo-karaoke/} dentro do projeto — junto com os originais
 * intocados na pasta de origem, é a trilha completa para auditar (ou refazer)
 * qualquer conversão.
 */
@ApplicationScoped
public class NovoKaraokePersistencia {

    private static final Path PASTA_MANIFESTOS =
        TelemetriaService.resolverPastaArtefatosOperacionais("novo-karaoke").resolve("manifestos");
    private static final DateTimeFormatter FORMATO_CARIMBO = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss_SSS");

    @Inject
    ObjectMapper objectMapper;

    public Path salvarManifesto(
        Path pastaOrigem,
        Path pastaDestino,
        List<ResultadoConversaoKaraoke> resultados,
        long duracaoMs
    ) throws IOException {
        Files.createDirectories(PASTA_MANIFESTOS);

        Map<String, Object> manifesto = new LinkedHashMap<>();
        manifesto.put("executadoEm", LocalDateTime.now().toString());
        manifesto.put("pastaOrigem", pastaOrigem.toAbsolutePath().toString());
        manifesto.put("pastaDestino", pastaDestino.toAbsolutePath().toString());
        manifesto.put("duracaoMs", duracaoMs);

        List<Map<String, Object>> arquivos = resultados.stream().map(r -> {
            Map<String, Object> item = new LinkedHashMap<String, Object>();
            item.put("arquivoOrigem", r.getArquivoOrigem());
            item.put("arquivoDestino", r.getArquivoDestino());
            item.put("eventosTotais", r.getEventosTotais());
            item.put("eventosDialogoPreservados", r.getEventosDialogoPreservados());
            item.put("eventosKaraokeRemovidos", r.getEventosKaraokeRemovidos());
            item.put("eventosPreservadosPorSeguranca", r.getEventosPreservadosPorSeguranca());
            item.put("eventosTipografiaColapsados", r.getEventosTipografiaColapsados());
            item.put("tamanhoOriginalBytes", r.getTamanhoOriginalBytes());
            item.put("tamanhoNovoBytes", r.getTamanhoNovoBytes());
            item.put("percentualReducao", r.getPercentualReducao());
            item.put("avisos", r.getAvisos());
            item.put("linhasCriadas", r.getLinhasCriadas().stream().map(NovoKaraokePersistencia::linhaParaMapa).toList());
            return item;
        }).toList();
        manifesto.put("arquivos", arquivos);

        Path destino = PASTA_MANIFESTOS.resolve(
            "kronos_novo_karaoke_" + LocalDateTime.now().format(FORMATO_CARIMBO) + ".json");
        objectMapper.writerWithDefaultPrettyPrinter().writeValue(destino.toFile(), manifesto);
        return destino;
    }

    private static Map<String, Object> linhaParaMapa(LinhaSimplesKaraoke linha) {
        Map<String, Object> mapa = new LinkedHashMap<>();
        mapa.put("inicio", linha.inicioAss());
        mapa.put("fim", linha.fimAss());
        mapa.put("texto", linha.texto());
        mapa.put("eventosOrigem", linha.eventosOrigem());
        mapa.put("variantesTexto", linha.variantesTexto());
        return mapa;
    }
}
