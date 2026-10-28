package org.traducao.projeto.traducaoKaraoke.application;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.traducao.projeto.cachetraducao.domain.EntradaCache;
import org.traducao.projeto.cachetraducao.domain.ProvenienciaCache;
import org.traducao.projeto.cachetraducao.infrastructure.CacheTraducaoService;
import org.traducao.projeto.core.io.DiretorioBaseKronos;
import org.traducao.projeto.core.presentation.web.LogStreamService;
import org.traducao.projeto.legenda.domain.DocumentoLegenda;
import org.traducao.projeto.legenda.domain.EventoLegenda;
import org.traducao.projeto.traducaoKaraoke.domain.VersosDaLetra;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * PROPOSITO DE NEGOCIO: dono do cache JSON por arquivo desta fatia — onde ele mora, o que se
 * reaproveita dele e o que se grava de volta. O cache e EDITAVEL a mao de proposito: o operador
 * corrige uma linha no JSON e a reexecucao respeita a correcao.
 *
 * <h2>As duas invariantes que vieram junto</h2>
 * <ul>
 *   <li><b>Proveniencia divergente descarta TUDO.</b> Contexto ou modelo diferentes significam
 *       que aquelas traducoes nasceram sob outra lore — reaproveita-las e propagar o erro. O
 *       cache antigo e PRESERVADO em arquivo, nunca apagado, e o sinal sobe para o manifesto:
 *       descartar cache multiplica tempo e chamadas ao LLM, e sem aviso o pico aparece no
 *       historico sem explicacao.</li>
 *   <li><b>Grava-se so o que foi APLICADO.</b> E por isso que a limpeza do cache e automatica:
 *       o que o criterio novo tornou inalcancavel simplesmente nao volta para o arquivo. Em
 *       2026-08-19 isso passou a ser CONTADO ({@code entradasCacheDescartadas}), porque cache
 *       que encolhe sem numero e indistinguivel de perda de dado.</li>
 * </ul>
 *
 * <h2>Comportamento em caso de falha</h2>
 * Dry-run nao le nem escreve cache. Mapa vazio de traducoes nao grava arquivo. Nunca lanca.
 */
@ApplicationScoped
public class CacheDoArquivo {

    static final String CANAL_LOG = TraduzirKaraokeUseCase.CANAL_LOG;
    private static final String SUBPASTA = "karaoke";

    @Inject
    CacheTraducaoService cacheService;

    @Inject
    LogStreamService logStream;

    @ConfigProperty(name = "tradutor.idioma-original")
    Optional<String> idiomaOriginal;

    @ConfigProperty(name = "tradutor.idioma-traduzido")
    Optional<String> idiomaTraduzido;

    // E3b/Opcao A: ausencia e vazio colapsam em "cache"; branco de idioma cai no default.
    @ConfigProperty(name = "tradutor.diretorio-cache")
    Optional<String> diretorioCache;

    /**
     * PROPÓSITO DE NEGÓCIO: onde mora o cache de letras de UM arquivo de karaokê.
     *
     * <p>INVARIANTES DO DOMÍNIO: o caminho é ancorado em {@link DiretorioBaseKronos} — um
     * diretório de cache RELATIVO (o padrão {@code cache}) fica sob a raiz operacional efetiva,
     * um ABSOLUTO configurado passa intocado. Em produção a raiz é o diretório corrente e o
     * resultado é o de sempre. Sem a âncora, a suíte e qualquer execução com
     * {@code -Dkronos.dir.base} liam e REGRAVAVAM {@code ./cache/karaoke} real — medido em
     * 08/10/2026: uma tradução de teste isolada regravou 3 caches reais e criou 2.
     *
     * <p>COMPORTAMENTO EM CASO DE FALHA: nome sem extensão ASS/SSA vira base do arquivo como
     * está; caminho sintaticamente inválido propaga {@link java.nio.file.InvalidPathException}.
     */
    public Path arquivoDe(Path arquivo) {
        String nome = arquivo.getFileName().toString();
        String base = nome.replaceFirst("(?i)\\.(ass|ssa)$", "");
        String dirCache = diretorioCache.filter(s -> !s.isBlank()).orElse("cache");
        return DiretorioBaseKronos.resolver(dirCache, SUBPASTA, base + ".cache.json");
    }

    public Map<String, String> carregar(
        Path arquivoCache, boolean gravar, ProvenienciaCache proveniencia,
        java.util.concurrent.atomic.AtomicBoolean cacheIgnorado
    ) {
        return carregar(arquivoCache, gravar, proveniencia, cacheIgnorado, new java.util.HashMap<>());
    }

    /**
     * PROPÓSITO DE NEGÓCIO: carrega o cache reaproveitável do arquivo e devolve, à parte, as
     * entradas que deixaram de ser reaproveitadas por suspeita de verso perdido — para que a falha
     * da retradução use a tradução antiga em vez de deixar a letra em inglês.
     *
     * <p>INVARIANTES DO DOMÍNIO: proveniência divergente (lore, prompt ou modelo mudaram) liga
     * {@code cacheIgnorado} e avisa no console, como o cache legado — até 28/10/2026 só o legado
     * ligava, e o Guilty Crown de 09/10 teve 20 caches arquivados e 560 letras retraduzidas com o
     * manifesto dizendo {@code cacheIgnorado=false}.
     *
     * <p>COMPORTAMENTO EM CASO DE FALHA: simulação devolve mapa vazio sem ler nada.
     */
    public Map<String, String> carregar(
        Path arquivoCache, boolean gravar, ProvenienciaCache proveniencia,
        java.util.concurrent.atomic.AtomicBoolean cacheIgnorado, Map<String, String> suspeitas
    ) {
        if (!gravar) {
            return Map.of();
        }
        CacheTraducaoService.ResultadoCarga carga = cacheService.carregar(arquivoCache, proveniencia);
        if (!carga.migrado() && carga.invalidadas() > 0) {
            cacheIgnorado.set(true);
            logStream.publicarLog(CANAL_LOG, "   [CACHE IGNORADO] a lore, o prompt ou o modelo mudaram: "
                + carga.invalidadas() + " entrada(s) da geração anterior foram arquivadas e as letras serão "
                + "retraduzidas (uma correção feita à mão no cache está na geração arquivada).");
        }
        if (carga.migrado()) {
            cacheService.arquivarGeracaoSemProveniencia(arquivoCache);
            // Sobe para o manifesto: descartar o cache multiplica tempo e chamadas ao LLM, e sem
            // esse sinal o pico aparece no histórico sem explicação nenhuma.
            cacheIgnorado.set(true);
            logStream.publicarLog(CANAL_LOG,
                "   [CACHE IGNORADO] cache antigo sem contexto/lore foi preservado; linhas serão retraduzidas e carimbadas.");
            return Map.of();
        }
        return semVersoPerdido(carga.mapa(), suspeitas);
    }

    /**
     * PROPÓSITO DE NEGÓCIO: deixa de REAPROVEITAR a tradução que ficou gravada com o defeito do
     * verso perdido (24/09/2026) — sem isto a correção do tradutor nunca chegaria às letras já
     * cacheadas, e "Faça uma pequena pausa" continuaria no lugar dos dois versos para sempre.
     *
     * <p>INVARIANTES DO DOMÍNIO: a decisão é de {@link VersosDaLetra#suspeitaDeVersoPerdido}. A
     * suspeita NÃO apaga nada: a letra só volta ao LLM (A8). Cada descarte é publicado com o texto
     * antigo, porque o cache é editável à mão e o operador precisa ver o que deixou de valer (A7).
     *
     * <p>COMPORTAMENTO EM CASO DE FALHA: mapa nulo ou vazio volta como veio.
     */
    Map<String, String> semVersoPerdido(Map<String, String> mapa) {
        return semVersoPerdido(mapa, new java.util.HashMap<>());
    }

    Map<String, String> semVersoPerdido(Map<String, String> mapa, Map<String, String> suspeitas) {
        if (mapa == null || mapa.isEmpty()) {
            return mapa;
        }
        Map<String, String> filtrado = new LinkedHashMap<>(mapa);
        for (Map.Entry<String, String> entrada : mapa.entrySet()) {
            if (VersosDaLetra.suspeitaDeVersoPerdido(entrada.getKey(), entrada.getValue())) {
                filtrado.remove(entrada.getKey());
                suspeitas.put(entrada.getKey(), entrada.getValue());
                logStream.publicarLog(CANAL_LOG, "   [CACHE] retraduzindo letra com verso perdido: \""
                    + entrada.getValue() + "\" (original: \"" + entrada.getKey() + "\")");
            }
        }
        return filtrado;
    }

    /**
     * Persiste TODAS as traduções aplicadas (novas e reaproveitadas) no cache
     * do arquivo, preservando o fluxo de correção manual: o usuário edita o
     * JSON e a reexecução respeita a edição.
     */
    public void salvar(Path arquivoCache, DocumentoLegenda documento, Map<String, String> traducoes,
                             ProvenienciaCache proveniencia) {
        if (traducoes.isEmpty()) {
            return;
        }
        Map<String, EntradaCache> porOriginal = new LinkedHashMap<>();
        for (EventoLegenda evento : documento.eventos()) {
            String traduzido = evento.temTexto() ? traducoes.get(evento.texto()) : null;
            if (traduzido != null) {
                porOriginal.putIfAbsent(evento.texto(), new EntradaCache(
                    evento.indice(), evento.estilo(), evento.texto(), traduzido,
                    idiomaOriginal.filter(s -> !s.isBlank()).orElse("en"),
                    idiomaTraduzido.filter(s -> !s.isBlank()).orElse("pt-br")));
            }
        }
        cacheService.salvar(arquivoCache, proveniencia, new ArrayList<>(porOriginal.values()));
    }

    /**
     * PROPÓSITO DE NEGÓCIO: guarda o que já foi traduzido quando o arquivo NÃO chega ao fim —
     * "Parar" ou servidor fora do ar no meio. A gravação normal só guarda o que foi aplicado; aqui
     * as entradas do cache que a execução ainda não alcançou ficam, senão parar no meio apagaria
     * a tradução do resto do episódio.
     *
     * <p>INVARIANTES DO DOMÍNIO: a tradução desta execução vence a do cache para o mesmo original;
     * nada que estava no cache é perdido.
     *
     * <p>COMPORTAMENTO EM CASO DE FALHA: sem nada a guardar, não toca no arquivo; erro de gravação
     * propaga como na gravação normal.
     */
    public void salvarParcial(Path arquivoCache, DocumentoLegenda documento, Map<String, String> traducoes,
                              Map<String, String> cacheExistente, ProvenienciaCache proveniencia) {
        Map<String, String> unido = new LinkedHashMap<>(cacheExistente);
        unido.putAll(traducoes);
        salvar(arquivoCache, documento, unido, proveniencia);
    }
}
