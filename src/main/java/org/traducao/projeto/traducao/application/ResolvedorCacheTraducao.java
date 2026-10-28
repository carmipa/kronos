package org.traducao.projeto.traducao.application;

import org.springframework.stereotype.Component;
import org.traducao.projeto.cachetraducao.domain.ProvenienciaCache;
import org.traducao.projeto.lore.domain.SnapshotContexto;
import org.traducao.projeto.llm.domain.LlmPort;
import org.traducao.projeto.traducao.infrastructure.config.LlmProperties;
import org.traducao.projeto.traducao.infrastructure.config.TradutorProperties;
import org.traducao.projeto.traducao.presentation.ui.PastasExecucao;

import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * PROPÓSITO DE NEGÓCIO: resolve a IDENTIDADE de cache de um episódio — o arquivo de
 * cache no disco, o carimbo de proveniência que decide se traduções anteriores podem
 * ser reusadas e os rótulos de agrupamento (anime, temporada) derivados do caminho —,
 * isolando essa derivação da orquestração de {@link ProcessarArquivoUseCase}
 * (FASE F, R2).
 *
 * <h2>Invariantes do domínio</h2>
 * <ul>
 *   <li>O arquivo de cache mora em {@code <diretorioCache>/<anime>/<base>.cache.json}, com o
 *       nome-base do episódio SEM a extensão (via {@link ResolvedorSaidaLegenda}). Consequência
 *       declarada: {@code Ep01.ass} e {@code Ep01.srt} da mesma obra dividiriam o cache, e o
 *       segundo a traduzir substituiria o do primeiro. O endereço não muda por isso — mudar
 *       deixaria órfãos todos os caches existentes —, e a colisão foi medida em 28/10/2026:
 *       0 nomes-base com os dois formatos entre 428 no acervo.</li>
 *   <li>A proveniência carimba os seis campos canônicos (schema, contexto congelado do job,
 *       hash do prompt desse contexto, modelo, idiomas) — qualquer troca de lore/modelo/
 *       idioma muda o carimbo e invalida o cache antigo. O contexto chega por PARÂMETRO;
 *       este resolvedor não consulta o contexto ativo global.</li>
 *   <li>O nome do anime vem da pasta-avó do arquivo ({@code <Anime>/legendas_originais/
 *       arquivo.ass}); a temporada é extraída desse nome quando presente.</li>
 * </ul>
 *
 * <h2>Comportamento em caso de falha</h2>
 * Não lança: um snapshot neutro carimba {@code contextoId} nulo e o hash do prompt genérico
 * ainda é calculado (a comparação de proveniência trata nulos como divergência); um
 * caminho sem pasta-avó reconhecível resolve o anime como {@code "Desconhecido"} e a
 * temporada como {@code "Temporada Única"}.
 */
@Component
public class ResolvedorCacheTraducao {

    private static final Pattern PADRAO_TEMPORADA =
        Pattern.compile("(?i)(?:season|temporada|\\bs)\\s*0*(\\d{1,2})\\b");

    private final PastasExecucao pastasExecucao;
    private final ResolvedorSaidaLegenda resolvedorSaida;
    private final LlmPort llmPort;
    private final LlmProperties llmPropriedades;
    private final TradutorProperties propriedades;

    /**
     * PROPÓSITO DE NEGÓCIO: injeta as fontes que compõem a identidade de cache do episódio —
     * diretórios, extensão do formato, modelo e idiomas.
     *
     * <p>INVARIANTES DO DOMÍNIO: guarda as referências recebidas; não as substitui nem cria
     * implementação própria. O contexto/lore NÃO é colaborador injetado: ele chega por
     * parâmetro em {@link #provenienciaDe(SnapshotContexto)}, já congelado pelo job. Injetar
     * o {@code GerenciadorContexto} aqui era uma segunda porta para o estado global mutável,
     * capaz de carimbar o cache com uma obra diferente da que produziu o prompt.
     *
     * <p>COMPORTAMENTO EM CASO DE FALHA: não valida os argumentos; a injeção CDI garante os beans.
     *
     * @param pastasExecucao raiz de cache/saída resolvidas para a execução
     * @param resolvedorSaida provê a extensão canônica do formato de legenda
     * @param llmPort porta do LLM — fonte do modelo que REALMENTE respondeu
     * @param llmPropriedades configuração; só o fallback do modelo, quando a porta não sabe
     * @param propriedades idiomas de origem/destino do carimbo de proveniência
     */
    public ResolvedorCacheTraducao(
        PastasExecucao pastasExecucao,
        ResolvedorSaidaLegenda resolvedorSaida,
        LlmPort llmPort,
        LlmProperties llmPropriedades,
        TradutorProperties propriedades
    ) {
        this.pastasExecucao = pastasExecucao;
        this.resolvedorSaida = resolvedorSaida;
        this.llmPort = llmPort;
        this.llmPropriedades = llmPropriedades;
        this.propriedades = propriedades;
    }

    /**
     * PROPÓSITO DE NEGÓCIO: resolve o caminho do arquivo de cache do episódio, dentro
     * da pasta do anime, para que cada episódio tenha seu banco bilíngue próprio.
     *
     * <p>INVARIANTES DO DOMÍNIO: o cache mora em {@code <diretorioCache>/<anime>/
     * <base>.cache.json}, com o nome-base do episódio sem a extensão (ver a colisão declarada
     * no Javadoc da classe).
     *
     * <p>COMPORTAMENTO EM CASO DE FALHA: função pura, sem I/O; caminho sem pasta-avó
     * reconhecível usa {@code "Desconhecido"} como anime.
     */
    public Path resolverArquivoCache(Path entrada) {
        return resolverArquivoCache(entrada, null);
    }

    /**
     * PROPÓSITO DE NEGÓCIO: mesmo endereço do cache do episódio, separando a tradução SEM lore
     * (2.2) da tradução com lore (2.1), para que rodar uma nunca substitua o banco bilíngue da
     * outra.
     *
     * <p>INVARIANTES DO DOMÍNIO: contexto {@code sem_lore} grava em
     * {@code <diretorioCache>/<anime>/sem_lore/<base>.cache.json}; qualquer outro contexto (ou
     * nenhum) mantém o endereço de sempre — o acervo já traduzido continua sendo encontrado.
     * A base de mescla {@code .publicado} e as gerações arquivadas ficam ao lado do cache, então
     * se separam junto.
     *
     * <p>COMPORTAMENTO EM CASO DE FALHA: função pura, sem I/O; contexto nulo é o caminho comum.
     */
    public Path resolverArquivoCache(Path entrada, String contextoId) {
        String nome = entrada.getFileName().toString();
        String extensao = resolvedorSaida.extensaoLegenda(nome);
        String base = nome.substring(0, nome.length() - extensao.length());
        String animeNome = animeAPartirDoArquivo(entrada);
        Path pastaObra = pastasExecucao.diretorioCache().resolve(animeNome);
        if (org.traducao.projeto.lore.domain.ProvedorContexto.ID_SEM_LORE.equals(contextoId)) {
            pastaObra = pastaObra.resolve(ProvenienciaCache.SUBPASTA_SEM_LORE);
        }
        return pastaObra.resolve(base + ".cache.json");
    }

    /**
     * PROPÓSITO DE NEGÓCIO: carimba a proveniência a partir do contexto CONGELADO do job, e
     * não do contexto ativo global. É o que garante que o cache gravado no fim do arquivo
     * declare a mesma lore que produziu o prompt enviado ao LLM no começo dele — mesmo que o
     * operador troque a obra no combo enquanto o episódio traduz.
     *
     * <p>INVARIANTES DO DOMÍNIO: este método é a FRONTEIRA DE INTEGRAÇÃO entre o peer
     * {@code contexto} (que só entrega o prompt congelado) e o peer {@code cachetraducao}
     * (dono do formato do carimbo). O hash é derivado AQUI, com
     * {@link ProvenienciaCache#hashDe(String)} — a única fonte do algoritmo. O snapshot não
     * calcula hash algum: se ele mantivesse uma cópia do algoritmo e as duas divergissem,
     * todo o cache já gravado passaria a ser lido como de outra origem e seria descartado.
     * O {@code contextoId} e o {@code contextoHash} saem do MESMO snapshot, então nunca
     * podem pertencer a obras diferentes.
     *
     * <p>COMPORTAMENTO EM CASO DE FALHA: não lança; um snapshot neutro (job sem contexto)
     * carimba {@code contextoId} nulo, e a comparação de proveniência trata nulo como
     * divergência — o cache antigo não é reusado por engano.
     *
     * @param contexto fotografia imutável do contexto em vigor neste job
     * @return carimbo de proveniência desta execução
     */
    public ProvenienciaCache provenienciaDe(SnapshotContexto contexto) {
        return new ProvenienciaCache(
            ProvenienciaCache.SCHEMA_ATUAL,
            contexto.id(),
            ProvenienciaCache.hashDe(contexto.promptSistema()),
            modeloEfetivo(),
            propriedades.idiomaOriginal(),
            propriedades.idiomaTraduzido()
        );
    }

    /**
     * PROPÓSITO DE NEGÓCIO: carimba o cache com o modelo que REALMENTE respondeu, e não com
     * o que a configuração diz — para que trocar de LLM invalide a geração anterior. É o
     * invariante que a {@link ProvenienciaCache} existe para garantir e que, até 2026-07-29,
     * valia para lore e idioma mas NÃO para modelo.
     *
     * <p>INVARIANTES DO DOMÍNIO: a PORTA vence a configuração. Mesma regra de
     * {@code MontadorTelemetriaTraducao#modeloEfetivo} — as duas leituras precisam concordar,
     * senão telemetria e cache atribuem a mesma tradução a modelos diferentes.
     *
     * <p>Por que a configuração não serve: {@code LlmProperties} é {@code @ConfigurationProperties}
     * e NÃO é instância compartilhada entre beans. O {@code setModel} que o adaptador chama ao
     * detectar o modelo carregado muda a cópia DELE; este resolvedor enxerga outra, que continua
     * com o {@code "current"} do {@code application.yml}. Foi o que deixou 218 dos 279 caches do
     * acervo carimbados {@code "current"} — corrigidos por migração antes desta mudança entrar,
     * justamente porque corrigir o código sem migrar invalidaria o acervo inteiro de uma vez.
     *
     * <p>COMPORTAMENTO EM CASO DE FALHA: porta ausente ou sem modelo resolvido cai na
     * configuração. Preferível a gravar vazio, que perderia até o rastro da execução.
     */
    private String modeloEfetivo() {
        String daPorta = llmPort != null ? llmPort.modeloAtivo() : null;
        if (daPorta != null && !daPorta.isBlank()) {
            return daPorta;
        }
        return llmPropriedades.model();
    }

    /**
     * PROPÓSITO DE NEGÓCIO: deriva o nome do anime para agrupar cache e telemetria por
     * obra, a partir da pasta-avó do arquivo de legenda ({@code <Anime>/
     * legendas_originais/arquivo.ass}) — mesma convenção de duas pastas acima usada por
     * {@code TradutorProperties.resolverDiretorioCache()}.
     *
     * <p>INVARIANTES DO DOMÍNIO: prefere a pasta-avó; sem ela, cai para a pasta-mãe.
     *
     * <p>COMPORTAMENTO EM CASO DE FALHA: sem pasta reconhecível, devolve
     * {@code "Desconhecido"} em vez de lançar.
     */
    public String animeAPartirDoArquivo(Path arquivoEntrada) {
        Path pastaEntrada = arquivoEntrada.getParent();
        Path pastaAnime = pastaEntrada != null ? pastaEntrada.getParent() : null;
        if (pastaAnime != null && pastaAnime.getFileName() != null) {
            return pastaAnime.getFileName().toString();
        }
        if (pastaEntrada != null && pastaEntrada.getFileName() != null) {
            return pastaEntrada.getFileName().toString();
        }
        return "Desconhecido";
    }

    /**
     * PROPÓSITO DE NEGÓCIO: extrai o rótulo de temporada (ex.: "Season 04", "S04") do
     * nome da pasta do anime para agrupar a telemetria por temporada.
     *
     * <p>INVARIANTES DO DOMÍNIO: reconhece {@code season}/{@code temporada}/{@code s}
     * seguidos do número; sem marcador, agrupa como {@code "Temporada Única"}.
     *
     * <p>COMPORTAMENTO EM CASO DE FALHA: nome nulo ou sem marcador devolve
     * {@code "Temporada Única"} em vez de lançar.
     */
    public String temporadaAPartirDoNome(String animeNome) {
        if (animeNome == null) {
            return "Temporada Única";
        }
        Matcher matcher = PADRAO_TEMPORADA.matcher(animeNome);
        if (matcher.find()) {
            return "Temporada " + Integer.parseInt(matcher.group(1));
        }
        return "Temporada Única";
    }
}
