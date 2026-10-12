package org.traducao.projeto.traducaoKaraoke.infrastructure;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.traducao.projeto.cachetraducao.domain.ProvenienciaCache;
import org.traducao.projeto.lore.domain.SnapshotContexto;
import org.traducao.projeto.telemetria.TelemetriaService;
import org.traducao.projeto.traducaoKaraoke.domain.DesfechoKaraoke;
import org.traducao.projeto.traducaoKaraoke.domain.ResultadoTraducaoKaraoke;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Manifesto de auditoria da tradução de karaokê: registra, por execução, o
 * que foi preservado/traduzido em cada arquivo. Fica em
 * {@code logs/traducao-karaoke/manifestos} — junto com os originais intocados
 * na pasta de origem e o cache JSON editável, é a trilha completa para
 * auditar (ou refazer) qualquer tradução de letra.
 */
@ApplicationScoped
public class TraducaoKaraokePersistencia {

    private static final Path PASTA_MANIFESTOS =
        TelemetriaService.resolverPastaArtefatosOperacionais("traducao-karaoke").resolve("manifestos");
    private static final DateTimeFormatter FORMATO_CARIMBO = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss");

    @Inject
    ObjectMapper objectMapper;

    /**
     * PROPÓSITO DE NEGÓCIO: grava o manifesto de auditoria — inclusive, e principalmente, quando a
     * execução deu errado.
     *
     * <h2>INVARIANTES DO DOMÍNIO</h2>
     * <ul>
     *   <li><b>Escreve SEMPRE.</b> Lista de resultados vazia é caso NORMAL aqui, não motivo para
     *       não registrar: execução abortada com zero arquivos é justamente a que mais precisa
     *       deixar rastro. Até 2026-08-14 o chamador pulava o registro nesse caso e o pior
     *       desfecho possível saía indistinguível de "não havia nada a fazer".</li>
     *   <li><b>Contexto e proveniência podem ser nulos.</b> O aborto por LLM fora do ar acontece
     *       ANTES do congelamento do contexto; exigir esses objetos aqui faria o registro falhar
     *       exatamente na hora em que ele importa.</li>
     * </ul>
     *
     * <p>COMPORTAMENTO EM CASO DE FALHA: propaga {@link IOException} — quem chama decide, e a
     * decisão do karaokê é gritar, porque manifesto perdido é auditoria perdida.
     */
    public Path salvarManifesto(
        Path pastaOrigem,
        Path pastaDestino,
        List<ResultadoTraducaoKaraoke> resultados,
        long duracaoMs,
        SnapshotContexto contexto,
        ProvenienciaCache proveniencia,
        DesfechoKaraoke desfecho
    ) throws IOException {
        Files.createDirectories(PASTA_MANIFESTOS);

        Map<String, Object> manifesto = new LinkedHashMap<>();
        manifesto.put("executadoEm", LocalDateTime.now().toString());
        // O DESFECHO vem primeiro de propósito: é a pergunta que se faz ao abrir o arquivo.
        manifesto.put("statusFinal", desfecho.status().name());
        manifesto.put("motivo", desfecho.motivo());
        manifesto.put("cacheIgnorado", desfecho.cacheIgnorado());
        manifesto.put("estadoDicionario", desfecho.estadoDicionario().name());
        manifesto.put("arquivosComFalha", desfecho.falhas().stream()
            .map(f -> {
                Map<String, Object> m = new LinkedHashMap<String, Object>();
                m.put("arquivo", f.arquivo());
                m.put("motivo", f.motivo());
                return m;
            }).toList());
        manifesto.put("pastaOrigem", pastaOrigem.toAbsolutePath().toString());
        manifesto.put("pastaDestino", pastaDestino.toAbsolutePath().toString());
        manifesto.put("duracaoMs", duracaoMs);
        manifesto.put("contextoId", contexto == null ? null : contexto.id());
        manifesto.put("contextoNome", contexto == null ? null : contexto.nomeExibicao());
        manifesto.put("contextoHash", proveniencia == null ? null : proveniencia.contextoHash());
        manifesto.put("modeloLlm", proveniencia == null ? null : proveniencia.modeloLlm());

        List<Map<String, Object>> arquivos = resultados.stream().map(r -> {
            Map<String, Object> item = new LinkedHashMap<String, Object>();
            item.put("arquivo", r.arquivo());
            item.put("arquivoDestino", r.arquivoDestino());
            item.put("eventosTotais", r.eventosTotais());
            item.put("efeitosKfxPreservados", r.efeitosKfxPreservados());
            item.put("preservadasOriginalJapones", r.preservadasOriginalJapones());
            item.put("jaEmPortugues", r.jaEmPortugues());
            item.put("paraTraduzir", r.paraTraduzir());
            item.put("reaproveitadasCache", r.reaproveitadasCache());
            item.put("traduzidas", r.traduzidas());
            item.put("mantidasSemTraducao", r.mantidasSemTraducao());
            // Telemetria PRÓPRIA da fatia: o acento é problema do karaokê e é medido aqui, não no
            // módulo comum de tradução. Sem este campo o defeito só aparece a quem abrir o .ass.
            item.put("acentosRepostos", r.acentosRepostos());
            // Limpeza DECLARADA: cache que encolhe sem numero parece perda de dado.
            item.put("entradasCacheDescartadas", r.entradasCacheDescartadas());
            // Abertura do Unicorn, 12/10/2026: frase de KFX que ficou só em português, as palavras
            // do efeito silenciadas e a fonte do diálogo onde a da música não desenha o português.
            item.put("frasesKfxSoPortugues", r.frasesKfxSoPortugues());
            item.put("silabasKfxSilenciadas", r.silabasKfxSilenciadas());
            item.put("linhasComFonteDoDialogo", r.linhasComFonteDoDialogo());
            item.put("fontesDaMusica", r.fontesDaMusica());
            item.put("avisos", r.avisos());
            return item;
        }).toList();
        manifesto.put("arquivos", arquivos);

        Path destino = PASTA_MANIFESTOS.resolve(
            "kronos_traducao_karaoke_" + LocalDateTime.now().format(FORMATO_CARIMBO) + ".json");
        objectMapper.writerWithDefaultPrettyPrinter().writeValue(destino.toFile(), manifesto);
        return destino;
    }
}
