package org.traducao.projeto.telemetria;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.traducao.projeto.core.io.DiretorioBaseKronos;
import org.traducao.projeto.core.util.ArquivoAtomicoUtil;

import jakarta.ws.rs.sse.OutboundSseEvent;
import jakarta.ws.rs.sse.Sse;
import jakarta.ws.rs.sse.SseEventSink;
import jakarta.inject.Inject;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.lang.management.ManagementFactory;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.stream.Stream;

@Service
@ApplicationScoped
public class TelemetriaService {

    private static final Logger log = LoggerFactory.getLogger(TelemetriaService.class);
    private static final String NOME_ARQUIVO_TELEMETRIA = "telemetria_compartilhada.json";
    // Arquivo canônico PRÓPRIO da Tradução Local (D-Tel-4), lido em modo read-only
    // por este agregador; escrito exclusivamente pelo adapter da fatia traducao.
    private static final String NOME_ARQUIVO_TRADUCAO = "telemetria_traducao.json";

    // O histórico de operações é append-only e o JSON canônico inteiro é
    // regravado a cada registro; sem um teto, o custo de serialização e o
    // payload do painel cresceriam para sempre. 500 cobre meses de uso.
    private static final int LIMITE_OPERACOES_HISTORICO = 500;

    // A contagem de arquivos .cache.json exige um Files.walk no diretório de
    // cache; com o SSE emitindo resumos periódicos, recontar a cada chamada
    // varreria o disco continuamente segurando o lock do serviço.
    private static final long TTL_CONTAGEM_CACHE_MS = 30_000;

    // Avisos por episódio no JSON canônico: sem teto, os textos de aviso
    // dominavam o arquivo (21,9 mil avisos ≈ 85% dos 3,5MB medidos em
    // 2026-07-09) e eram regravados a cada registro. A íntegra continua nos
    // relatórios por operação em relatorios/ — aqui fica só a amostra.
    private static final int LIMITE_AVISOS_POR_EPISODIO = 30;

    // Local canônico dentro do próprio projeto onde a telemetria é sempre
    // mesclada e persistida a cada registro, para sobreviver a restarts do
    // servidor. As mídias analisadas são um dataset permanente (acumulado e
    // deduplicado por nome de arquivo), nunca zerado entre análises. É o que o
    // painel web lê em gerarResumo().
    // Resolvido via DiretorioBaseKronos: em produção é logs/ na raiz do
    // projeto; sob a suíte de testes é redirecionado para uma árvore
    // descartável, evitando reescrever a telemetria canônica real.
    private static final String SUBPASTA_TELEMETRIA = "logs";

    private static Path pastaTelemetria() {
        return DiretorioBaseKronos.resolver(SUBPASTA_TELEMETRIA);
    }
    private static final String TIPO_REVISAO_LORE = "Revisao de Lore (.ass LLM)";
    private static final DateTimeFormatter TIMESTAMP_RELATORIO =
        DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss");

    private final ObjectMapper objectMapper;
    private final Map<String, MidiaTelemetria> bancoMidia = new LinkedHashMap<>();
    private final Map<String, LlmTelemetria> bancoLlm = new LinkedHashMap<>();
    private final List<OperacaoTelemetria> bancoOperacoes = new ArrayList<>();
    private final AtomicInteger alucinacoesPrevenidas = new AtomicInteger(0);
    private final AtomicInteger respostasTraducaoRejeitadas = new AtomicInteger(0);
    private final AtomicInteger falhasTraducaoRecuperadas = new AtomicInteger(0);
    private final AtomicInteger fallbacksTraducaoMantidos = new AtomicInteger(0);
    private final AtomicInteger arquivosSanitizados = new AtomicInteger(0);

    // Estruturas para Server-Sent Events (SSE)
    private final List<SseEventSink> sinks = new CopyOnWriteArrayList<>();
    private volatile Path ultimoDiretorioCache = DiretorioBaseKronos.resolver("cache");
    private ScheduledExecutorService scheduler;

    // Cache da contagem de arquivos .cache.json (ver TTL_CONTAGEM_CACHE_MS)
    private volatile int contagemCacheUltima = 0;
    private volatile long contagemCacheExpiraEmMs = 0;
    private volatile Path contagemCacheDiretorio;

    @Inject
    Sse sse;

    public void registrarAlucinacaoPrevenida() {
        alucinacoesPrevenidas.incrementAndGet();
        log.info("Resposta suspeita de tradução interceptada. Total legado acumulado: {}", alucinacoesPrevenidas.get());
        persistirCanonico();
        broadcast();
    }

    /**
     * PROPÓSITO DE NEGÓCIO: contabiliza uma resposta do modelo rejeitada pela
     * validação antes de ela contaminar legenda ou cache.
     *
     * <p>INVARIANTES DO DOMÍNIO: rejeição não implica correção nem sucesso.
     *
     * <p>COMPORTAMENTO EM CASO DE FALHA: apenas incrementa contador em memória;
     * a conclusão do episódio persiste o agregado canônico.
     */
    public void registrarRespostaTraducaoRejeitada() {
        respostasTraducaoRejeitadas.incrementAndGet();
    }

    /**
     * PROPÓSITO DE NEGÓCIO: registra quando uma nova tentativa produz tradução
     * válida depois de pelo menos uma resposta rejeitada.
     *
     * <p>INVARIANTES DO DOMÍNIO: só deve ser chamado após validação bem-sucedida.
     *
     * <p>COMPORTAMENTO EM CASO DE FALHA: incremento lock-free; persistência ocorre
     * junto da telemetria final do episódio.
     */
    public void registrarFalhaTraducaoRecuperada() {
        falhasTraducaoRecuperadas.incrementAndGet();
    }

    /**
     * PROPÓSITO DE NEGÓCIO: registra uma fala que esgotou as tentativas e ficou
     * pendente, sem anunciar falsamente que foi corrigida.
     *
     * <p>INVARIANTES DO DOMÍNIO: cada texto distinto pendente é contado uma vez
     * pela consolidação final do arquivo.
     *
     * <p>COMPORTAMENTO EM CASO DE FALHA: incremento lock-free; o cache preserva
     * a pendência como tradução vazia.
     */
    public void registrarFallbackMantido() {
        fallbacksTraducaoMantidos.incrementAndGet();
    }
    
    public void registrarArquivoSanitizado() {
        registrarArquivosSanitizados(1);
    }

    /**
     * Versão em lote de {@link #registrarArquivoSanitizado()}: cada registro
     * reescreve o JSON canônico inteiro e emite broadcast SSE, então quem
     * renomeia dezenas de arquivos de uma vez deve registrar o total uma única
     * vez em vez de disparar uma rajada de escritas em disco.
     */
    public void registrarArquivosSanitizados(int quantidade) {
        if (quantidade <= 0) {
            return;
        }
        int total = arquivosSanitizados.addAndGet(quantidade);
        log.info("Arquivos renomeados com sucesso (Limpa Nome): +{}. Total acumulado: {}", quantidade, total);
        persistirCanonico();
        broadcast();
    }

    /**
     * Fluxo ao vivo. Vem por construtor injetado em produção e fica INERTE quando
     * o serviço é montado à mão — cinco testes fazem isso, e nenhum deles precisa
     * saber que existe fluxo.
     */
    private FluxoTelemetriaPort fluxo = FluxoTelemetriaPort.INERTE;

    /**
     * PROPÓSITO DE NEGÓCIO: construtor usado pelo contêiner, com o fluxo ao vivo
     * ligado.
     *
     * <p>INVARIANTES DO DOMÍNIO: porta nula cai no objeto-nulo, nunca em campo
     * nulo — telemetria não pode ser causa de falha em lugar nenhum.
     */
    @jakarta.inject.Inject
    public TelemetriaService(FluxoTelemetriaPort fluxo) {
        this();
        this.fluxo = fluxo == null ? FluxoTelemetriaPort.INERTE : fluxo;
    }

    public TelemetriaService() {
        // Sem INDENT_OUTPUT: o JSON canônico e os payloads SSE são compactos
        // (o arquivo é regravado a cada registro; indentação dobrava o custo).
        // Relatórios legíveis usam writerWithDefaultPrettyPrinter() no ponto de uso.
        this.objectMapper = new ObjectMapper();
    }

    @jakarta.annotation.PostConstruct
    public void init() {
        carregarBancoPersistido(pastaTelemetria().resolve(NOME_ARQUIVO_TELEMETRIA), bancoMidia, bancoLlm, bancoOperacoes);

        // Agendador daemon em segundo plano que envia atualizações contínuas de
        // CPU/Memória JVM aos clientes SSE. 5s é suficiente para os medidores da
        // interface sem re-serializar o resumo inteiro a cada segundo (mudanças
        // reais de dados já disparam broadcast() nos métodos registrar*).
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "telemetria-sse-scheduler");
            t.setDaemon(true);
            return t;
        });
        this.scheduler.scheduleAtFixedRate(this::broadcast, 5000, 5000, TimeUnit.MILLISECONDS);
    }

    @jakarta.annotation.PreDestroy
    public void destruir() {
        if (scheduler != null) {
            scheduler.shutdownNow();
        }
    }

    public synchronized void registrarMidia(MidiaTelemetria midia) {
        if (midia != null) {
            bancoMidia.put(midia.nomeArquivo(), midia);
            persistirCanonico();
            broadcast();
        }
    }

    public synchronized void registrarTraducao(LlmTelemetria traducao) {
        if (traducao != null) {
            LlmTelemetria compacta = comAvisosLimitados(traducao);
            bancoLlm.put(compacta.nomeEpisodio(), compacta);
            persistirCanonico();
            broadcast();
        }
    }

    // Marcador da compactação. Mantido em ASCII puro: literais com acento são
    // corrompidos pelo compilador do live-reload do quarkusDev (sem -encoding),
    // e este texto precisa ser reconhecível pelo próprio regex depois.
    private static final java.util.regex.Pattern MARCADOR_AVISOS_OMITIDOS =
        java.util.regex.Pattern.compile("\\(\\+(\\d+) avisos omitidos");

    /**
     * Aplica o teto de {@value #LIMITE_AVISOS_POR_EPISODIO} avisos por episódio,
     * trocando o excedente por uma linha-resumo. A íntegra dos avisos vive nos
     * relatórios da operação (pasta {@code relatorios/}), não no JSON canônico.
     * <p>
     * IDEMPOTENTE por contrato: uma lista já compactada (teto + linha-resumo)
     * passa intacta — sem isso, cada boot re-compactava a compactação anterior
     * e o total real do marcador era destruído (bug observado em 2026-07-09:
     * "(+625" virou "(+1" no load seguinte).
     */
    static LlmTelemetria comAvisosLimitados(LlmTelemetria traducao) {
        List<String> avisos = traducao.errosOcorridos();
        if (avisos == null || avisos.size() <= LIMITE_AVISOS_POR_EPISODIO) {
            return traducao;
        }
        if (avisos.size() == LIMITE_AVISOS_POR_EPISODIO + 1
            && MARCADOR_AVISOS_OMITIDOS.matcher(avisos.getLast()).find()) {
            return traducao;
        }
        List<String> amostra = new ArrayList<>(avisos.subList(0, LIMITE_AVISOS_POR_EPISODIO));
        amostra.add("(+" + (avisos.size() - LIMITE_AVISOS_POR_EPISODIO)
            + " avisos omitidos; integra no relatorio da operacao em relatorios/)");
        return new LlmTelemetria(
            traducao.nomeEpisodio(), traducao.modeloLlm(), traducao.totalLinhas(),
            traducao.falasTraduzidas(), traducao.falasDoCache(), traducao.tempoTotalMs(),
            List.copyOf(amostra), traducao.animeNome(), traducao.temporada(), traducao.registradoEm(),
            traducao.loreNome(), traducao.statusFinal(),
            traducao.falasItalicoRemovido(), traducao.falasItalicoPreservado());
    }

    public synchronized void registrarOperacao(OperacaoTelemetria operacao) {
        if (operacao != null) {
            bancoOperacoes.add(operacao);
            while (bancoOperacoes.size() > LIMITE_OPERACOES_HISTORICO) {
                bancoOperacoes.remove(0);
            }
            persistirCanonico();
            log.info("Telemetria de operação registrada: {} — {} ({} arquivos, {} detectados, {} corrigidos)",
                operacao.tipo(), operacao.detalhe(), valorOuZero(operacao.arquivosProcessados()),
                valorOuZero(operacao.itensDetectados()), valorOuZero(operacao.itensCorrigidos()));
            publicarNoFluxo(operacao);
            broadcast();
        }
    }

    /**
     * PROPÓSITO DE NEGÓCIO: leva a operação ao fluxo AO VIVO da fatia a que ela
     * pertence, para o painel acompanhar sem esperar o arquivo fechar e para cada
     * aba publicar o próprio dataset.
     *
     * <h2>Por que aqui, e não em cada fatia</h2>
     * Treze fatias chamam {@code registrarOperacao}. Publicar deste ponto alcança
     * todas de uma vez, sem criar uma única aresta nova entre elas e sem tocar no
     * {@link OperacaoTelemetria}, que é contrato compartilhado. A alternativa
     * — dar a porta a cada fatia — esbarraria nas fronteiras de arquitetura e
     * multiplicaria por treze o ponto de manutenção.
     *
     * <h2>INVARIANTES DO DOMÍNIO</h2>
     * <ul>
     *   <li>Tudo que sai daqui passou pelo {@link SanitizadorTelemetria}. O campo
     *       {@code detalhe} carrega caminho absoluto em 69% dos registros do
     *       acervo, e este é o ponto onde ele deixa de carregar.</li>
     *   <li>A fatia vem de {@link FatiaTelemetria}, por casamento nominal do
     *       tipo — nunca por pacote de origem nem por pedaço de nome.</li>
     *   <li>Publicar é best-effort: a porta engole toda falha, então este ponto
     *       não trata erro nem verifica disponibilidade.</li>
     * </ul>
     *
     * <p>COMPORTAMENTO EM CASO DE FALHA: nenhuma. Sem Redis, a porta está inerte
     * ou desconectada e o registro em arquivo segue igual.
     */
    private void publicarNoFluxo(OperacaoTelemetria operacao) {
        Map<String, String> campos = new LinkedHashMap<>();
        campos.put("detalhe", SanitizadorTelemetria.sanitizar(textoOuVazio(operacao.detalhe())));
        campos.put("tempoTotalMs",
            String.valueOf(operacao.tempoTotalMs() == null ? 0L : operacao.tempoTotalMs()));
        campos.put("arquivosProcessados", String.valueOf(valorOuZero(operacao.arquivosProcessados())));
        campos.put("itensDetectados", String.valueOf(valorOuZero(operacao.itensDetectados())));
        campos.put("itensCorrigidos", String.valueOf(valorOuZero(operacao.itensCorrigidos())));
        campos.put("registradoEm", textoOuVazio(operacao.registradoEm()));

        fluxo.publicar(FatiaTelemetria.de(operacao.tipo()), operacao.tipo(), campos);
    }

    private static String textoOuVazio(String valor) {
        return valor == null ? "" : valor;
    }

    /**
     * Registra telemetria canônica em {@code logs/}, grava relatório .txt/.json em
     * {@code relatorios/} (mesmo padrão da análise de mídia) e copia o JSON unificado
     * para a pasta de relatórios da operação.
     */
    public synchronized void finalizarOperacao(
        OperacaoTelemetria operacao,
        Path pastaEntrada,
        String prefixoRelatorio,
        String conteudoRelatorio
    ) {
        if (operacao == null) {
            return;
        }
        registrarOperacao(operacao);
        Path pastaRelatorios = resolverPastaRelatorios(pastaEntrada);
        try {
            salvarRelatorioOperacao(pastaRelatorios, prefixoRelatorio, operacao, conteudoRelatorio);
            salvar(pastaRelatorios);
            log.info("Relatório e telemetria da operação persistidos em: {}", pastaRelatorios);
        } catch (IOException e) {
            log.warn("Falha ao salvar relatório da operação em {}: {}", pastaRelatorios, e.getMessage());
        }
    }

    public static Path resolverPastaRelatorios(Path entrada) {
        if (entrada == null) {
            return DiretorioBaseKronos.resolver("relatorios", "operacao").toAbsolutePath();
        }
        String nomeDir = entrada.getFileName().toString();
        if (nomeDir.isBlank()) {
            nomeDir = "operacao";
        }
        return DiretorioBaseKronos.resolver("relatorios", nomeDir).toAbsolutePath();
    }

    public static Path resolverPastaTelemetriaProjeto() {
        return pastaTelemetria().toAbsolutePath();
    }

    public static Path resolverArquivoTelemetriaCanonico() {
        return resolverPastaTelemetriaProjeto().resolve(NOME_ARQUIVO_TELEMETRIA);
    }

    public static Path resolverPastaArtefatosOperacionais(String modulo) {
        String nomeModulo = modulo == null || modulo.isBlank()
            ? "operacao"
            : modulo.toLowerCase(java.util.Locale.ROOT).replaceAll("[^a-z0-9._-]", "-").replaceAll("-+", "-");
        return resolverPastaTelemetriaProjeto().resolve(nomeModulo);
    }

    public void salvarRelatorioOperacao(
        Path pastaRelatorios,
        String prefixo,
        OperacaoTelemetria operacao,
        String conteudoRelatorio
    ) throws IOException {
        Files.createDirectories(pastaRelatorios);
        String timestamp = TIMESTAMP_RELATORIO.format(LocalDateTime.now());
        Path arquivoTxt = pastaRelatorios.resolve(prefixo + "_" + timestamp + ".txt");
        Path arquivoJson = pastaRelatorios.resolve(prefixo + "_" + timestamp + ".json");
        Files.writeString(arquivoTxt, conteudoRelatorio, StandardCharsets.UTF_8);
        objectMapper.writerWithDefaultPrettyPrinter().writeValue(arquivoJson.toFile(), operacao);
        log.info("Relatório textual salvo em: {}", arquivoTxt);
        log.info("Relatório JSON salvo em: {}", arquivoJson);
    }

    /**
     * Persiste a telemetria canônica em {@code logs/telemetria_compartilhada.json}.
     * Se {@code pastaRelatorios} for informada e diferente de {@code logs/}, copia
     * o arquivo unificado para lá (padrão usado pela análise de mídia).
     */
    public synchronized Path salvar(Path pastaRelatorios) {
        Path caminhoCanonico = persistirCanonico();
        if (caminhoCanonico == null || pastaRelatorios == null) {
            return caminhoCanonico;
        }
        if (pastaRelatorios.normalize().toAbsolutePath()
            .equals(pastaTelemetria().normalize().toAbsolutePath())) {
            return caminhoCanonico;
        }
        try {
            Files.createDirectories(pastaRelatorios);
            Path destino = pastaRelatorios.resolve(NOME_ARQUIVO_TELEMETRIA);
            Files.copy(caminhoCanonico, destino, StandardCopyOption.REPLACE_EXISTING);
            log.info("Cópia da telemetria unificada salva em: {}", destino);
            return destino;
        } catch (IOException e) {
            log.error("Erro ao copiar telemetria para {}: {}", pastaRelatorios, e.getMessage(), e);
            return caminhoCanonico;
        }
    }

    private synchronized Path persistirCanonico() {
        try {
            Path pastaTelemetria = pastaTelemetria();
            if (!Files.exists(pastaTelemetria)) {
                Files.createDirectories(pastaTelemetria);
            }

            Path caminhoTelemetria = pastaTelemetria.resolve(NOME_ARQUIVO_TELEMETRIA);

            ObjectNode rootNode = objectMapper.createObjectNode();
            rootNode.set("midias", objectMapper.valueToTree(new ArrayList<>(this.bancoMidia.values())));
            rootNode.set("traducoesLlm", objectMapper.valueToTree(new ArrayList<>(this.bancoLlm.values())));
            rootNode.set("operacoes", objectMapper.valueToTree(this.bancoOperacoes));
            rootNode.put("alucinacoesPrevenidas", this.alucinacoesPrevenidas.get());
            rootNode.put("respostasTraducaoRejeitadas", this.respostasTraducaoRejeitadas.get());
            rootNode.put("falhasTraducaoRecuperadas", this.falhasTraducaoRecuperadas.get());
            rootNode.put("fallbacksTraducaoMantidos", this.fallbacksTraducaoMantidos.get());
            rootNode.put("arquivosSanitizados", this.arquivosSanitizados.get());

            // Grava em arquivo temporário e move atomicamente para evitar que uma
            // interrupção no meio da escrita (o arquivo é regravado a cada registro)
            // deixe o JSON truncado e derrube o histórico inteiro no próximo boot.
            // O move usa retry: antivírus/indexador do Windows trava o destino por
            // milissegundos e causava AccessDeniedException sob registros em rajada.
            Path arquivoTemp = pastaTelemetria.resolve(NOME_ARQUIVO_TELEMETRIA + ".tmp");
            objectMapper.writeValue(arquivoTemp.toFile(), rootNode);
            ArquivoAtomicoUtil.substituirAtomico(arquivoTemp, caminhoTelemetria);

            log.debug("Telemetria unificada salva com sucesso: {} mídias, {} traduções, {} operações em: {}",
                this.bancoMidia.size(), this.bancoLlm.size(), this.bancoOperacoes.size(), caminhoTelemetria);

            return caminhoTelemetria;
        } catch (IOException e) {
            log.error("Erro ao salvar a telemetria unificada no disco: {}", e.getMessage(), e);
            return null;
        }
    }

    /**
     * Monta o resumo serializável consumido pelo painel "Telemetria" da
     * interface web. Lê o histórico canônico persistido em
     * {@code logs/telemetria_compartilhada.json} (mesclado a cada chamada de
     * {@link #registrarMidia} / {@link #registrarTraducao} / {@link #registrarOperacao}), por isso reflete
     * o total acumulado do projeto e sobrevive a restarts do servidor — as
     * mídias, traduções e operações são um dataset permanente, nunca zerado
     * entre análises. A contagem de arquivos de cache é sempre lida diretamente
     * do diretório informado.
     */
    public synchronized TelemetriaResumo gerarResumo(Path diretorioCache) {
        this.ultimoDiretorioCache = diretorioCache;
        int cacheCount = contarArquivosCache(diretorioCache);

        // Agregador CQRS read-only: consolida o histórico legado
        // (telemetria_compartilhada.json, em memória) com a telemetria própria da
        // Tradução Local (telemetria_traducao.json), lida por DTO próprio — sem
        // importar classes do pacote traducao. O contrato é apenas o JSON.
        TelemetriaTraducaoLeitura.Documento traducaoLocal = lerTelemetriaTraducao();
        Map<String, LlmTelemetria> traducoes = mesclarTraducoes(traducaoLocal);

        int totalLinhas = traducoes.values().stream().mapToInt(l -> valorOuZero(l.totalLinhas())).sum();
        int totalCacheHits = traducoes.values().stream().mapToInt(l -> valorOuZero(l.falasDoCache())).sum();
        long tempoTotalMs = traducoes.values().stream().mapToLong(l -> l.tempoTotalMs() != null ? l.tempoTotalMs() : 0L).sum();
        long tempoMedioPorLinhaMs = totalLinhas > 0 ? tempoTotalMs / totalLinhas : 0L;
        int totalErros = traducoes.values().stream()
            .mapToInt(l -> l.errosOcorridos() != null ? l.errosOcorridos().size() : 0)
            .sum();

        double jvmCpu = 0.0;
        try {
            com.sun.management.OperatingSystemMXBean osBean = 
                ManagementFactory.getPlatformMXBean(com.sun.management.OperatingSystemMXBean.class);
            if (osBean != null) {
                jvmCpu = osBean.getProcessCpuLoad() * 100.0;
                if (jvmCpu < 0) {
                    jvmCpu = 0.0;
                }
            }
        } catch (Throwable e) {
            // OperatingSystemMXBean proprietário da Sun não disponível (ou em SO não suportado)
        }

        int jvmThreads = 0;
        try {
            jvmThreads = ManagementFactory.getThreadMXBean().getThreadCount();
        } catch (Throwable e) {
            // Thread bean não disponível
        }

        long heapUsed = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory();
        long heapMax = Runtime.getRuntime().maxMemory();

        List<OperacaoHistorico> historico = montarHistorico(bancoOperacoes, traducoes, bancoMidia);
        RevisaoLoreTelemetriaResumo revisaoLore = agregarRevisaoLore(bancoOperacoes);

        return new TelemetriaResumo(
            cacheCount,
            traducoes.size(),
            totalLinhas,
            tempoMedioPorLinhaMs,
            totalCacheHits,
            historico,
            new ArrayList<>(traducoes.values()),
            bancoOperacoes,
            revisaoLore,
            alucinacoesPrevenidas.get() + traducaoLocal.alucinacoesPrevenidas(),
            totalErros,
            jvmCpu,
            jvmThreads,
            heapUsed,
            heapMax,
            arquivosSanitizados.get(),
            respostasTraducaoRejeitadas.get() + traducaoLocal.respostasTraducaoRejeitadas(),
            falhasTraducaoRecuperadas.get() + traducaoLocal.falhasTraducaoRecuperadas(),
            fallbacksTraducaoMantidos.get() + traducaoLocal.fallbacksTraducaoMantidos()
        );
    }

    /**
     * PROPÓSITO DE NEGÓCIO: lê o arquivo canônico próprio da Tradução Local como
     * agregador read-only, tolerando ausência, vazio e corrupção deterministicamente.
     * <p>INVARIANTES DO DOMÍNIO: nunca escreve o arquivo; ilegível é tratado como
     * vazio sem destruir o físico.
     * <p>COMPORTAMENTO EM CASO DE FALHA: arquivo ausente/ilegível devolve
     * {@link TelemetriaTraducaoLeitura.Documento#vazio()}.
     */
    private TelemetriaTraducaoLeitura.Documento lerTelemetriaTraducao() {
        Path arquivo = pastaTelemetria().resolve(NOME_ARQUIVO_TRADUCAO);
        if (!Files.exists(arquivo)) {
            return TelemetriaTraducaoLeitura.Documento.vazio();
        }
        try {
            TelemetriaTraducaoLeitura.Documento doc =
                objectMapper.readValue(arquivo.toFile(), TelemetriaTraducaoLeitura.Documento.class);
            return doc != null ? doc : TelemetriaTraducaoLeitura.Documento.vazio();
        } catch (IOException e) {
            log.warn("Telemetria da Traducao Local ilegivel em {} (tratada como vazia, arquivo preservado): {}",
                arquivo, e.getMessage());
            return TelemetriaTraducaoLeitura.Documento.vazio();
        }
    }

    /**
     * PROPÓSITO DE NEGÓCIO: consolida o histórico legado com a telemetria própria
     * da Tradução Local numa visão determinística por episódio.
     * <p>INVARIANTES DO DOMÍNIO: para a mesma chave normalizada, o registro de
     * {@code telemetria_traducao.json} SEMPRE vence o legado, independentemente da
     * ordem física de leitura; dentro de uma mesma fonte vence o {@code registradoEm}
     * mais recente e, em empate/ausência, a última ocorrência física.
     * <p>COMPORTAMENTO EM CASO DE FALHA: fonte vazia contribui com zero entradas.
     */
    private Map<String, LlmTelemetria> mesclarTraducoes(TelemetriaTraducaoLeitura.Documento traducaoLocal) {
        Map<String, LlmTelemetria> mescla = new LinkedHashMap<>();
        // Fonte legado (compartilhada): dedup interna por mais-recente/última física.
        for (LlmTelemetria l : bancoLlm.values()) {
            acumularMaisRecente(mescla, normalizarEpisodio(l.nomeEpisodio()), l);
        }
        // Fonte própria: dedup interna e, ao final, precedência incondicional sobre o legado.
        Map<String, LlmTelemetria> proprio = new LinkedHashMap<>();
        if (traducaoLocal.registros() != null) {
            for (TelemetriaTraducaoLeitura.Registro r : traducaoLocal.registros()) {
                acumularMaisRecente(proprio, normalizarEpisodio(r.nomeEpisodio()), comAvisosLimitados(converter(r)));
            }
        }
        mescla.putAll(proprio); // telemetria_traducao.json vence o legado por chave
        return mescla;
    }

    private void acumularMaisRecente(Map<String, LlmTelemetria> mapa, String chave, LlmTelemetria atual) {
        LlmTelemetria existente = mapa.get(chave);
        if (existente == null || atualVence(atual.registradoEm(), existente.registradoEm())) {
            mapa.put(chave, atual);
        }
    }

    // Ordena por registradoEm quando ambos existem e diferem; caso contrário
    // (empate ou ausência de timestamp) vence a última ocorrência física (atual).
    private static boolean atualVence(String tsAtual, String tsExistente) {
        if (tsAtual != null && tsExistente != null) {
            int c = tsAtual.compareTo(tsExistente);
            if (c != 0) {
                return c > 0;
            }
        }
        return true;
    }

    private static LlmTelemetria converter(TelemetriaTraducaoLeitura.Registro r) {
        return new LlmTelemetria(
            r.nomeEpisodio(), r.modeloLlm(), r.totalLinhas(), r.falasTraduzidas(),
            r.falasDoCache(), r.tempoTotalMs(), r.errosOcorridos(), r.animeNome(),
            r.temporada(), r.registradoEm(), r.loreNome(), r.statusFinal(),
            r.falasItalicoRemovido(), r.falasItalicoPreservado());
    }

    // Duplicação consciente da normalização proprietária da Tradução Local
    // (org.traducao.projeto.traducao.domain.NormalizadorNomeEpisodio): o módulo de
    // telemetria não pode importar o pacote traducao; ambos concordam pelo contrato.
    static String normalizarEpisodio(String nomeEpisodio) {
        if (nomeEpisodio == null || nomeEpisodio.isBlank()) {
            return "";
        }
        String semDiretorio = nomeEpisodio.replace('\\', '/');
        int barra = semDiretorio.lastIndexOf('/');
        if (barra >= 0) {
            semDiretorio = semDiretorio.substring(barra + 1);
        }
        String nfc = java.text.Normalizer.normalize(semDiretorio, java.text.Normalizer.Form.NFC);
        return nfc.strip().replaceAll("\\s+", " ").toLowerCase(java.util.Locale.ROOT);
    }

    private List<OperacaoHistorico> montarHistorico(
        List<OperacaoTelemetria> operacoes,
        Map<String, LlmTelemetria> traducoes,
        Map<String, MidiaTelemetria> midias
    ) {
        // Operações, traduções e mídias são intercaladas numa única linha do
        // tempo (mais recente primeiro). Entradas antigas persistidas antes de
        // existir o campo registradoEm ficam no fim, em vez de embaralhadas.
        record ItemHistorico(String registradoEm, OperacaoHistorico linha) {}
        List<ItemHistorico> itens = new ArrayList<>();

        for (OperacaoTelemetria op : operacoes) {
            itens.add(new ItemHistorico(op.registradoEm(), new OperacaoHistorico(
                op.tipo(),
                formatarDetalheOperacao(op),
                formatarDuracaoMs(op.tempoTotalMs()),
                calcularTaxaSucesso(op.itensDetectados(), op.itensCorrigidos()),
                inferirOrigem(op.tipo()),
                op.tempoTotalMs(),
                op.registradoEm(),
                op.itensDetectados(),
                op.itensCorrigidos()
            )));
        }

        for (LlmTelemetria l : traducoes.values()) {
            itens.add(new ItemHistorico(l.registradoEm(), new OperacaoHistorico(
                "Tradução LLM", l.nomeEpisodio(), formatarDuracaoMs(l.tempoTotalMs()), null,
                inferirOrigem("Tradução LLM"), l.tempoTotalMs(), l.registradoEm(), null, null
            )));
        }
        for (MidiaTelemetria m : midias.values()) {
            itens.add(new ItemHistorico(m.registradoEm(), new OperacaoHistorico(
                "Análise de Mídia", m.nomeArquivo(), null, null,
                inferirOrigem("Análise de Mídia"), null, m.registradoEm(), null, null
            )));
        }

        itens.sort(Comparator.comparing(
            ItemHistorico::registradoEm,
            Comparator.nullsLast(Comparator.reverseOrder())
        ));
        return itens.stream().map(ItemHistorico::linha).toList();
    }

    /**
     * Classifica a operação numa origem (LLM/GOOGLE/CACHE/SISTEMA) a partir do
     * próprio nome/tipo, já que o histórico não guarda essa dimensão à parte.
     * LLM tem prioridade porque operações como "Revisão Gramatical (cache LLM)"
     * são, na prática, chamadas ao modelo local, mesmo mencionando cache.
     */
    private String inferirOrigem(String tipo) {
        if (tipo == null) {
            return "SISTEMA";
        }
        String normalizado = tipo.toUpperCase(java.util.Locale.ROOT);
        if (ehRevisaoLore(tipo)) {
            return "LORE";
        }
        if (normalizado.contains("LLM")) {
            return "LLM";
        }
        if (normalizado.contains("GOOGLE")) {
            return "GOOGLE";
        }
        if (normalizado.contains("CACHE")) {
            return "CACHE";
        }
        return "SISTEMA";
    }

    private RevisaoLoreTelemetriaResumo agregarRevisaoLore(List<OperacaoTelemetria> operacoes) {
        int sessoes = 0;
        int arquivos = 0;
        int sinalizadas = 0;
        int corrigidas = 0;
        for (OperacaoTelemetria op : operacoes) {
            if (!ehRevisaoLoreComTrabalho(op)) {
                continue;
            }
            sessoes++;
            arquivos += valorOuZero(op.arquivosProcessados());
            sinalizadas += valorOuZero(op.itensDetectados());
            corrigidas += valorOuZero(op.itensCorrigidos());
        }
        return new RevisaoLoreTelemetriaResumo(
            sessoes,
            arquivos,
            sinalizadas,
            corrigidas,
            calcularTaxaSucesso(sinalizadas, corrigidas)
        );
    }

    static boolean ehRevisaoLore(String tipo) {
        if (tipo == null) {
            return false;
        }
        return tipo.contains(TIPO_REVISAO_LORE) || tipo.toUpperCase(java.util.Locale.ROOT).contains("REVISAO DE LORE");
    }

    static boolean ehRevisaoLoreComTrabalho(OperacaoTelemetria operacao) {
        if (operacao == null || !ehRevisaoLore(operacao.tipo())) {
            return false;
        }
        return valorOuZeroEstatico(operacao.arquivosProcessados()) > 0
            || valorOuZeroEstatico(operacao.itensDetectados()) > 0
            || valorOuZeroEstatico(operacao.itensCorrigidos()) > 0;
    }

    private static int valorOuZeroEstatico(Integer valor) {
        return valor != null ? valor : 0;
    }

    private String formatarDetalheOperacao(OperacaoTelemetria op) {
        StringBuilder sb = new StringBuilder(op.detalhe() != null ? op.detalhe() : "-");
        if (valorOuZero(op.arquivosProcessados()) > 0) {
            sb.append(" | arquivos: ").append(op.arquivosProcessados());
        }
        if (valorOuZero(op.itensDetectados()) > 0 || valorOuZero(op.itensCorrigidos()) > 0) {
            sb.append(" | detectados: ").append(valorOuZero(op.itensDetectados()));
            sb.append(", corrigidos: ").append(valorOuZero(op.itensCorrigidos()));
        }
        return sb.toString();
    }

    private Integer calcularTaxaSucesso(Integer detectados, Integer corrigidos) {
        int totalDetectados = valorOuZero(detectados);
        if (totalDetectados <= 0) {
            return null;
        }
        return Math.min(100, (valorOuZero(corrigidos) * 100) / totalDetectados);
    }

    public static OperacaoTelemetria criarOperacao(
        String tipo,
        String detalhe,
        long tempoTotalMs,
        int arquivosProcessados,
        int itensDetectados,
        int itensCorrigidos
    ) {
        return new OperacaoTelemetria(
            tipo,
            detalhe,
            tempoTotalMs,
            arquivosProcessados,
            itensDetectados,
            itensCorrigidos,
            Instant.now().toString()
        );
    }

    /**
     * Carrega o JSON consolidado existente (se houver) em {@code caminho}
     * para dentro dos mapas informados, indexados por nome de arquivo/episódio.
     */
    private void carregarBancoPersistido(
        Path caminho,
        Map<String, MidiaTelemetria> bancoMidia,
        Map<String, LlmTelemetria> bancoLlm,
        List<OperacaoTelemetria> bancoOperacoes
    ) {
        if (!Files.exists(caminho)) {
            return;
        }
        try {
            JsonNode root = objectMapper.readTree(caminho.toFile());

            JsonNode midiasNode = root.get("midias");
            if (midiasNode != null && midiasNode.isArray()) {
                List<MidiaTelemetria> anterioresMidia = objectMapper.convertValue(
                    midiasNode,
                    objectMapper.getTypeFactory().constructCollectionType(List.class, MidiaTelemetria.class)
                );
                for (MidiaTelemetria m : anterioresMidia) {
                    bancoMidia.put(m.nomeArquivo(), m);
                }
            }

            JsonNode llmNode = root.get("traducoesLlm");
            if (llmNode != null && llmNode.isArray()) {
                List<LlmTelemetria> anterioresLlm = objectMapper.convertValue(
                    llmNode,
                    objectMapper.getTypeFactory().constructCollectionType(List.class, LlmTelemetria.class)
                );
                for (LlmTelemetria l : anterioresLlm) {
                    // Compacta também o legado: entradas antigas com milhares de
                    // avisos encolhem na primeira regravação após este load.
                    bancoLlm.put(l.nomeEpisodio(), comAvisosLimitados(l));
                }
            }

            JsonNode operacoesNode = root.get("operacoes");
            if (operacoesNode != null && operacoesNode.isArray()) {
                List<OperacaoTelemetria> anterioresOperacoes = objectMapper.convertValue(
                    operacoesNode,
                    objectMapper.getTypeFactory().constructCollectionType(List.class, OperacaoTelemetria.class)
                );
                if (anterioresOperacoes.size() > LIMITE_OPERACOES_HISTORICO) {
                    anterioresOperacoes = anterioresOperacoes.subList(
                        anterioresOperacoes.size() - LIMITE_OPERACOES_HISTORICO, anterioresOperacoes.size());
                }
                bancoOperacoes.addAll(anterioresOperacoes);
            }

            JsonNode alucinacoesNode = root.get("alucinacoesPrevenidas");
            if (alucinacoesNode != null && alucinacoesNode.isInt()) {
                alucinacoesPrevenidas.set(alucinacoesNode.asInt());
            }

            JsonNode sanitizadosNode = root.get("arquivosSanitizados");
            if (sanitizadosNode != null && sanitizadosNode.isInt()) {
                arquivosSanitizados.set(sanitizadosNode.asInt());
            }

            respostasTraducaoRejeitadas.set(root.path("respostasTraducaoRejeitadas").asInt(0));
            falhasTraducaoRecuperadas.set(root.path("falhasTraducaoRecuperadas").asInt(0));
            fallbacksTraducaoMantidos.set(root.path("fallbacksTraducaoMantidos").asInt(0));

            log.info("Carregadas entradas anteriores: {} mídias, {} traduções, {} operações do arquivo {}.",
                bancoMidia.size(), bancoLlm.size(), bancoOperacoes.size(), caminho);
        } catch (IOException e) {
            log.warn("Não foi possível ler a telemetria consolidada existente em {}. Erro: {}", caminho, e.getMessage());
        }
    }

    private int contarArquivosCache(Path diretorioCache) {
        if (diretorioCache == null || !Files.isDirectory(diretorioCache)) {
            return 0;
        }

        long agora = System.currentTimeMillis();
        if (diretorioCache.equals(contagemCacheDiretorio) && agora < contagemCacheExpiraEmMs) {
            return contagemCacheUltima;
        }

        try (Stream<Path> walk = Files.walk(diretorioCache)) {
            int contagem = (int) walk.filter(Files::isRegularFile)
                .filter(p -> p.getFileName().toString().endsWith(".cache.json"))
                .count();
            contagemCacheUltima = contagem;
            contagemCacheDiretorio = diretorioCache;
            contagemCacheExpiraEmMs = agora + TTL_CONTAGEM_CACHE_MS;
            return contagem;
        } catch (IOException e) {
            log.warn("Não foi possível contar os arquivos de cache em {}: {}", diretorioCache, e.getMessage());
            return 0;
        }
    }

    private int valorOuZero(Integer valor) {
        return valor != null ? valor : 0;
    }

    private String formatarDuracaoMs(Long ms) {
        if (ms == null) {
            return null;
        }
        long segundos = ms / 1000;
        return segundos >= 60 ? (segundos / 60) + "min " + (segundos % 60) + "s" : segundos + "s";
    }

    // Gerenciamento de Sinks SSE
    public void registrarSink(SseEventSink sink) {
        sinks.add(sink);
        enviarInicial(sink);
    }

    private void enviarInicial(SseEventSink sink) {
        if (sse == null) {
            log.warn("Tentativa de enviar telemetria inicial SSE falhou: objeto Sse não injetado.");
            return;
        }
        try {
            TelemetriaResumo resumo = gerarResumo(ultimoDiretorioCache);
            String json = objectMapper.writeValueAsString(resumo);
            OutboundSseEvent event = sse.newEventBuilder()
                .name("telemetria")
                .data(json)
                .build();
            sink.send(event);
        } catch (Exception e) {
            sinks.remove(sink);
        }
    }

    public void broadcast() {
        if (sse == null || sinks.isEmpty()) {
            return;
        }
        try {
            TelemetriaResumo resumo = gerarResumo(ultimoDiretorioCache);
            String json = objectMapper.writeValueAsString(resumo);
            OutboundSseEvent event = sse.newEventBuilder()
                .name("telemetria")
                .data(json)
                .build();
            for (SseEventSink sink : sinks) {
                if (sink.isClosed()) {
                    sinks.remove(sink);
                    continue;
                }
                try {
                    sink.send(event);
                } catch (Exception e) {
                    sinks.remove(sink);
                }
            }
        } catch (Exception e) {
            log.error("Erro ao emitir broadcast de telemetria SSE", e);
        }
    }
}
