package org.traducao.projeto.traducao.application;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.traducao.projeto.traducao.domain.fallback.ResultadoRecuperacao;
import org.traducao.projeto.traducao.domain.CategoriaConteudo;
import org.traducao.projeto.traducao.domain.CausaRaizPendencia;
import org.traducao.projeto.traducao.domain.ResultadoTraducaoArquivo;
import org.traducao.projeto.traducao.domain.ResumoPendencia;
import org.traducao.projeto.traducao.domain.FalaNaoTraduzida;
import org.traducao.projeto.traducao.domain.StatusArquivoTraducao;
import org.traducao.projeto.legenda.application.ProtecaoCamadasMusicaisService.ProtecaoCamadas;
import org.traducao.projeto.legenda.domain.ArquivoLegendaException;
import org.traducao.projeto.legenda.domain.CarimboCabecalhoLegenda;
import org.traducao.projeto.traducao.domain.exceptions.EntradaJaTraduzidaException;
import org.traducao.projeto.legenda.domain.DocumentoLegenda;
import org.traducao.projeto.legenda.domain.EventoLegenda;
import org.traducao.projeto.traducao.domain.exceptions.TraducaoParcialException;
import org.traducao.projeto.cachetraducao.infrastructure.CacheTraducaoService;
import org.traducao.projeto.cachetraducao.domain.EntradaCache;
import org.traducao.projeto.cachetraducao.domain.ProvenienciaCache;
import org.traducao.projeto.lore.domain.SnapshotContexto;
import org.traducao.projeto.traducao.infrastructure.config.TradutorProperties;
import org.traducao.projeto.legenda.infrastructure.EscritorLegendaAss;
import org.traducao.projeto.legenda.infrastructure.EscritorLegendaSrt;
import org.traducao.projeto.legenda.infrastructure.LeitorLegendaAss;
import org.traducao.projeto.legenda.infrastructure.LeitorLegendaSrt;
import org.traducao.projeto.qualidadeTraducao.application.CorretorHomografoComOriginal;
import org.traducao.projeto.qualidadeTraducao.application.EnforcadorTermosLore;
import org.traducao.projeto.qualidadeTraducao.application.NormalizadorAcentosComuns;
import org.traducao.projeto.qualidadeTraducao.application.ProtecaoLegendaAssService;
import org.traducao.projeto.traducao.presentation.ui.ConsoleUILogger;
import org.traducao.projeto.traducao.presentation.ui.PastasExecucao;
import org.traducao.projeto.traducao.domain.ports.TelemetriaTraducaoPort;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import org.traducao.projeto.qualidadeTraducao.application.RemovedorItalico;

/**
 * PROPÓSITO DE NEGÓCIO: orquestra a tradução de uma legenda, reaproveitando o
 * cache, traduzindo somente pendências e publicando uma saída PT-BR recuperável.
 *
 * <p>INVARIANTES DO DOMÍNIO: correções válidas do cache não são reenviadas ao
 * LLM; tags e estrutura temporal são preservadas; saída parcial não substitui a
 * final sem liberação explícita e backup obrigatório.
 *
 * <p>COMPORTAMENTO EM CASO DE FALHA: respostas inválidas permanecem pendentes,
 * falhas de IO viram {@link ArquivoLegendaException} e uma substituição liberada
 * é abortada se a versão anterior não puder ser copiada para backup.
 */
@Service
public class ProcessarArquivoUseCase {

    private static final Logger log = LoggerFactory.getLogger(ProcessarArquivoUseCase.class);

    private final LeitorLegendaAss leitor;
    private final EscritorLegendaAss escritor;
    private final LeitorLegendaSrt leitorSrt;
    private final EscritorLegendaSrt escritorSrt;
    private final CacheTraducaoService cacheService;
    private final TradutorProperties propriedades;
    private final ConsoleUILogger uiLogger;
    private final PastasExecucao pastasExecucao;
    private final TelemetriaTraducaoPort telemetriaTraducao;
    private final ProtecaoLegendaAssService protecaoAss;
    private final ResolvedorSaidaLegenda resolvedorSaida;
    private final ResolvedorCacheTraducao resolvedorCache;
    private final PoliticaBackupTraducao politicaBackup;
    private final SeletorEventosTraduziveis seletorEventos;
    private final RemovedorItalico removedorItalico;
    private final AvaliadorTraducaoCache avaliadorCache;
    private final TradutorLotesService tradutorLotes;
    private final MontadorTelemetriaTraducao montadorTelemetria;
    private final ClassificadorPendenciaTelemetria classificadorPendencia;
    private final RecuperarPendenciaFallbackService recuperarPendenciaGoogle;
    private final EnforcadorTermosLore enforcadorTermosLore;
    private final EnforcadorGlossarioFala enforcadorGlossarioFala;
    private final DetectorIdiomaFonteService detectorIdiomaFonte;
    private final NormalizadorAspasService normalizadorAspas;
    private final NormalizadorAcentosComuns normalizadorAcentos;
    private final CorretorHomografoComOriginal corretorHomografo;
    private final org.traducao.projeto.core.texto.dicionarioOrtografia.CorretorOrtograficoLegenda corretorOrtografico;
    private final NormalizadorCartaoDataService normalizadorCartaoData;
    private final GuardaContextoObraTraducao guardaContextoObra;
    private final ContextoCongeladoDaExecucao contextoCongelado;
    private final org.traducao.projeto.qualidadeTraducao.application.nomeProprio
        .DetectorNomeProprioTraduzido detectorNomeProprio;

    // Prefixo EXATO do aviso emitido por TradutorLotesService.desmascararComFallback
    // quando o LLM corrompe os marcadores [[TAGn]]. Usado só para o KPI: identifica
    // que a causa-raiz da pendência é MARCADORES_CORROMPIDOS (e não o eco que ela
    // gera em seguida), sem alterar o caminho quente de tradução.
    private static final String PREFIXO_TAGS_CORROMPIDAS =
        "Fala mantida sem tradução (tags corrompidas pelo LLM): ";

    /**
     * PROPÓSITO DE NEGÓCIO: extrai {@code inicio,fim} do prefixo do evento, que é a chave
     * ESTÁVEL para casar uma fala do dataset com a linha do {@code .ass} publicado.
     *
     * <p>INVARIANTES DO DOMÍNIO: o texto muda ao traduzir, o instante não — casar por índice
     * mente quando o arquivo sai reordenado, e foi assim que uma auditoria acusou corrupção
     * inexistente no Guilty Crown. O prefixo tem a forma
     * {@code "Dialogue: 0,0:00:01.00,0:00:02.00,Estilo,,0,0,0,,"}: os campos 1 e 2 são os tempos.
     *
     * <p>COMPORTAMENTO EM CASO DE FALHA: prefixo nulo ou com menos de três campos devolve
     * string vazia — o registro perde a chave de casamento mas não derruba a tradução.
     */
    /**
     * PROPÓSITO DE NEGÓCIO: condensa os nomes perdidos numa linha legível de log.
     *
     * <p>INVARIANTES DO DOMÍNIO: no máximo oito nomes distintos; o excedente vira contagem. Log de
     * episódio com centenas de nomes não é lido por ninguém, e o detalhe completo já vai para o
     * relatório.
     *
     * <p>COMPORTAMENTO EM CASO DE FALHA: mapa nulo ou vazio devolve string vazia.
     */
    private static String resumirNomesPerdidos(Map<String, List<String>> perdidos) {
        if (perdidos == null || perdidos.isEmpty()) {
            return "";
        }
        java.util.LinkedHashSet<String> distintos = new java.util.LinkedHashSet<>();
        perdidos.values().forEach(distintos::addAll);
        String amostra = distintos.stream().limit(8).collect(java.util.stream.Collectors.joining(", "));
        return distintos.size() > 8 ? amostra + " (+" + (distintos.size() - 8) + ")" : amostra;
    }

    /**
     * O que o {@link CorretorHomografoComOriginal} teria trocado, se o original tivesse provado.
     *
     * <p>A fronteira do ASS é obrigatória: a quebra do formato ocupa dois caracteres e o segundo
     * é uma letra, então a palavra colada a ela some de qualquer busca ingênua.
     */
    private static final java.util.regex.Pattern HOMOGRAFO_SOLTO =
        java.util.regex.Pattern.compile(
            org.traducao.projeto.core.texto.FronteiraTermoAss.INICIO + "(?:e|[Ee]sta)"
                + org.traducao.projeto.core.texto.FronteiraTermoAss.FIM);

    /**
     * PROPÓSITO DE NEGÓCIO: diz se a fala AINDA tem um {@code e} ou {@code esta} solto depois do
     * corretor — é o contador da ABSTENÇÃO, sem o qual "0 corrigidas" é frase ambígua.
     *
     * <p>INVARIANTES DO DOMÍNIO: só OLHA, nunca corrige. A decisão de trocar é do
     * {@link CorretorHomografoComOriginal}, que exige a prova do original inglês.
     *
     * <p>COMPORTAMENTO EM CASO DE FALHA: texto nulo devolve {@code false} — ausência de texto não
     * é ausência de defeito, mas também não é defeito contável.
     */
    private static boolean temHomografoSolto(String texto) {
        return texto != null && HOMOGRAFO_SOLTO.matcher(texto).find();
    }

    /**
     * PROPÓSITO DE NEGÓCIO: põe o portão na fronteira certa — DEPOIS da última transformação e antes
     * de gravar. O portão valida a fala e em seguida ela ainda passa pelo termo obrigatório, pelo
     * reforço de terminologia, pelas aspas, pelo acento, pelo dicionário, pelo homógrafo e pelo
     * glossário. Quando uma dessas etapas deixava a fala reprovável, a releitura A6 só registrava,
     * com o arquivo já gravado. Medido no log desde 17/09/2026, 8 casos:
     * <pre>
     *   "We can grab some\Nnormal suits..."  -> a troca de termo comeu a quebra (2 casos)
     *   "Damn it!", "Uma, wait!"             -> a retirada das aspas deixou o inglês (4 casos)
     *   "Z-G...?", "Catl?"                   -> a retirada das aspas deixou o exemplo do prompt (2 casos)
     * </pre>
     *
     * <h2>Invariantes do domínio</h2>
     * <ul>
     *   <li>Desfazer volta à versão que o PORTÃO aprovou, não ao original em inglês: nos casos da
     *       quebra, o português sem a quebra é melhor que o inglês, e a versão aprovada é melhor que
     *       os dois.</li>
     *   <li>Só desfaz quando a transformada reprova E a aprovada passa no mesmo critério agora.
     *       Fala que já entrou reprovável por outra porta (fonte já no idioma-alvo, original
     *       preservado) não é tocada aqui.</li>
     *   <li>Fala pendente (vazia) e fala que a transformação não mudou não são examinadas.</li>
     * </ul>
     *
     * <p>COMPORTAMENTO EM CASO DE FALHA: nunca lança; mapas nulos devolvem lista vazia. Devolve um
     * aviso por fala desfeita, com a aprovada, a transformada e o motivo (A7).
     *
     * @param validadas o mapa original → tradução DEPOIS das transformações; é corrigido no lugar
     * @param aprovadas o mapa original → tradução como o portão aprovou
     * @param motivoFalha o critério do portão final (motivo da reprovação, ou {@code null})
     */
    static List<String> desfazerTransformacoesQueReprovam(Map<String, String> validadas,
            Map<String, String> aprovadas, java.util.function.BiFunction<String, String, String> motivoFalha) {
        List<String> avisos = new ArrayList<>();
        if (validadas == null || aprovadas == null || motivoFalha == null) {
            return avisos;
        }
        for (Map.Entry<String, String> traducao : validadas.entrySet()) {
            String transformada = traducao.getValue();
            String aprovada = aprovadas.get(traducao.getKey());
            if (transformada == null || transformada.isBlank() || aprovada == null || transformada.equals(aprovada)) {
                continue;
            }
            String motivo = motivoFalha.apply(traducao.getKey(), transformada);
            if (motivo != null && motivoFalha.apply(traducao.getKey(), aprovada) == null) {
                traducao.setValue(aprovada);
                avisos.add("Transformação desfeita: a fala aprovada pelo portão \"" + aprovada + "\" virou \""
                    + transformada + "\", que reprova (" + motivo + "). Publicada a aprovada. Original: "
                    + traducao.getKey());
            }
        }
        return avisos;
    }

    /** Tags só no começo e texto visível sem tag depois: o formato de um quadro de transição. */
    private static final java.util.regex.Pattern PREFIXO_DE_TAGS_E_TEXTO =
        java.util.regex.Pattern.compile("^((?:\\{[^}]*\\})+)([^{]+)$");

    private static String instanteDe(EventoLegenda evento) {
        if (evento == null || evento.prefixo() == null) {
            return "";
        }
        String[] campos = evento.prefixo().split(",");
        return campos.length >= 3 ? campos[1] + "," + campos[2] : "";
    }

    public ProcessarArquivoUseCase(
        LeitorLegendaAss leitor,
        EscritorLegendaAss escritor,
        LeitorLegendaSrt leitorSrt,
        EscritorLegendaSrt escritorSrt,
        CacheTraducaoService cacheService,
        TradutorProperties propriedades,
        ConsoleUILogger uiLogger,
        PastasExecucao pastasExecucao,
        TelemetriaTraducaoPort telemetriaTraducao,
        ProtecaoLegendaAssService protecaoAss,
        ResolvedorSaidaLegenda resolvedorSaida,
        ResolvedorCacheTraducao resolvedorCache,
        PoliticaBackupTraducao politicaBackup,
        SeletorEventosTraduziveis seletorEventos,
        RemovedorItalico removedorItalico,
        AvaliadorTraducaoCache avaliadorCache,
        TradutorLotesService tradutorLotes,
        MontadorTelemetriaTraducao montadorTelemetria,
        ClassificadorPendenciaTelemetria classificadorPendencia,
        RecuperarPendenciaFallbackService recuperarPendenciaGoogle,
        EnforcadorTermosLore enforcadorTermosLore,
        EnforcadorGlossarioFala enforcadorGlossarioFala,
        DetectorIdiomaFonteService detectorIdiomaFonte,
        NormalizadorAspasService normalizadorAspas,
        NormalizadorAcentosComuns normalizadorAcentos,
        CorretorHomografoComOriginal corretorHomografo,
        org.traducao.projeto.core.texto.dicionarioOrtografia.CorretorOrtograficoLegenda corretorOrtografico,
        NormalizadorCartaoDataService normalizadorCartaoData,
        GuardaContextoObraTraducao guardaContextoObra,
        ContextoCongeladoDaExecucao contextoCongelado,
        org.traducao.projeto.qualidadeTraducao.application.nomeProprio
            .DetectorNomeProprioTraduzido detectorNomeProprio
    ) {
        this.leitor = leitor;
        this.escritor = escritor;
        this.leitorSrt = leitorSrt;
        this.escritorSrt = escritorSrt;
        this.cacheService = cacheService;
        this.propriedades = propriedades;
        this.uiLogger = uiLogger;
        this.pastasExecucao = pastasExecucao;
        this.telemetriaTraducao = telemetriaTraducao;
        this.protecaoAss = protecaoAss;
        this.resolvedorSaida = resolvedorSaida;
        this.resolvedorCache = resolvedorCache;
        this.politicaBackup = politicaBackup;
        this.seletorEventos = seletorEventos;
        this.removedorItalico = removedorItalico;
        this.avaliadorCache = avaliadorCache;
        this.tradutorLotes = tradutorLotes;
        this.montadorTelemetria = montadorTelemetria;
        this.classificadorPendencia = classificadorPendencia;
        this.recuperarPendenciaGoogle = recuperarPendenciaGoogle;
        this.enforcadorTermosLore = enforcadorTermosLore;
        this.enforcadorGlossarioFala = enforcadorGlossarioFala;
        this.detectorIdiomaFonte = detectorIdiomaFonte;
        this.normalizadorAspas = normalizadorAspas;
        this.normalizadorAcentos = normalizadorAcentos;
        this.corretorOrtografico = corretorOrtografico;
        this.corretorHomografo = corretorHomografo;
        this.normalizadorCartaoData = normalizadorCartaoData;
        this.guardaContextoObra = guardaContextoObra;
        this.contextoCongelado = contextoCongelado;
        this.detectorNomeProprio = detectorNomeProprio;
    }

    /**
     * PROPÓSITO DE NEGÓCIO: Traduz um arquivo de legenda sob o contexto que o JOB escolheu.
     * Quando a entrada aparenta já estar em PT-BR, a retradução é BLOQUEADA por padrão —
     * evita traduzir de novo uma legenda já traduzida e sobrescrever trabalho bom.
     *
     * <p><b>Invariantes do domínio</b>
     * <ul>
     *   <li>O contexto chega por PARÂMETRO e é o mesmo objeto para todos os arquivos do lote:
     *       quem resolve é o chamador, UMA vez, a partir do id explicitamente pedido. Este
     *       caso de uso não conhece mais o {@code GerenciadorContexto} — antes ele consultava
     *       o contexto ATIVO GLOBAL a cada arquivo, e uma troca de obra no meio do lote fazia
     *       os arquivos seguintes saírem com outra lore, prompt e proveniência, sem sinal.</li>
     *   <li>A guarda obra×contexto roda POR ARQUIVO (pastas mistas: obras diferentes sob a
     *       mesma entrada), sempre contra ESTE mesmo snapshot — é a revalidação que bloqueia
     *       o arquivo alheio sem reabrir a porta para outra lore entrar no lugar.</li>
     *   <li>Só reprocessa uma entrada que parece traduzida se {@code permitirRetraducao} for
     *       explicitamente verdadeiro (confirmação do usuário); com essa liberação, uma saída
     *       final existente só é substituída após backup obrigatório, inclusive quando a nova
     *       execução ainda ficar parcial.</li>
     * </ul>
     *
     * <p><b>Comportamento em caso de falha</b>
     * Entrada aparentemente já traduzida sem confirmação → lança
     * {@link EntradaJaTraduzidaException} (o lote registra o arquivo como falha e segue para
     * o próximo); obra do caminho divergente do contexto do job → lança
     * {@code ObraDivergenteDoContextoException} antes de qualquer LLM ou escrita; falha ao
     * criar backup aborta a substituição e preserva o arquivo final anterior. Contexto nulo é
     * um erro de programação do chamador e falha rápido, em vez de degradar silenciosamente
     * para o contexto global — degradar aqui reintroduziria o bug que este parâmetro elimina.
     *
     * @param arquivoEntrada legenda a traduzir
     * @param permitirRetraducao liberação explícita para reprocessar entrada já traduzida
     * @param contextoDoJob fotografia imutável do contexto escolhido para o job inteiro
     */
    public ResultadoTraducaoArquivo processar(
        Path arquivoEntrada, boolean permitirRetraducao, SnapshotContexto contextoDoJob
    ) throws InterruptedException, ExecutionException {
        return processar(arquivoEntrada, permitirRetraducao, contextoDoJob, false);
    }

    /**
     * PROPÓSITO DE NEGÓCIO: mesma tradução, com a possibilidade de DISPENSAR o portão de obra.
     * Existe só para a tradução sem lore: quando o operador escolhe traduzir cru uma pasta cuja
     * obra JÁ tem lore declarada, a guarda bloquearia — e bloquear é o certo por padrão. A
     * dispensa é a caixa de escape marcada conscientemente na tela.
     *
     * <p>INVARIANTES DO DOMÍNIO: a dispensa não é silenciosa — registra {@code [ AVISO ]} em log
     * e console com a obra e o contexto ativo, para que a decisão apareça no relatório da
     * execução. Nenhuma outra blindagem é afetada: tags, alucinação, numérico e eco continuam.
     * A dispensa só é aceita com o contexto {@code sem_lore}; com qualquer outro ela é recusada
     * (com aviso) e o portão confere normalmente.
     *
     * <p>COMPORTAMENTO EM CASO DE FALHA: idêntico ao da sobrecarga de três argumentos.
     *
     * @param ignorarLoreExistente dispensa o portão de compatibilidade obra × contexto
     */
    public ResultadoTraducaoArquivo processar(
        Path arquivoEntrada, boolean permitirRetraducao, SnapshotContexto contextoDoJob,
        boolean ignorarLoreExistente
    ) throws InterruptedException, ExecutionException {
        long inicioMs = System.currentTimeMillis();
        if (contextoDoJob == null) {
            throw new IllegalArgumentException(
                "Contexto do job obrigatório: a tradução de " + arquivoEntrada
                    + " não pode escolher lore por conta própria.");
        }
        // Portão determinístico ANTES de ler a legenda, de chamar o LLM e de escrever cache:
        // arquivo cuja obra é reconhecida por outro contexto é BLOQUEADO aqui. Roda por
        // arquivo (a pasta de entrada pode misturar obras) contra o MESMO snapshot do job.
        // A dispensa vale SÓ para o contexto sem lore. Antes ela valia para qualquer contexto: um
        // POST com a lore de OUTRA obra e a caixa marcada passava, gravava o cache com a lore
        // errada e regravava a saída definitiva, sob um aviso que dizia "tradução sem lore"
        // (auditoria de 08/10/2026, achado L3; o mesmo pedido sem a caixa era BLOQUEADO).
        boolean dispensaValida = ignorarLoreExistente
            && org.traducao.projeto.lore.domain.ProvedorContexto.ID_SEM_LORE.equals(contextoDoJob.id());
        if (dispensaValida) {
            String aviso = "Portão de obra DISPENSADO por escolha explícita (tradução sem lore): "
                + arquivoEntrada + " — contexto ativo \"" + contextoDoJob.id()
                + "\". A terminologia da obra, se existir, NÃO será aplicada.";
            log.warn(aviso);
            uiLogger.log("[ AVISO ] " + aviso);
        } else {
            if (ignorarLoreExistente) {
                String aviso = "Dispensa do portão de obra RECUSADA: ela só vale para a tradução sem lore, "
                    + "e o contexto pedido é \"" + contextoDoJob.id() + "\". O portão confere normalmente.";
                log.warn(aviso);
                uiLogger.log("[ AVISO ] " + aviso);
            }
            guardaContextoObra.verificar(arquivoEntrada, contextoDoJob);
        }
        // Ponte para o consumidor legado que ainda lê a lore por ThreadLocal (ver
        // ContextoCongeladoDaExecucao): a validação de cada fala atravessa a porta
        // LoreAtivaPort, que não recebe o snapshot por parâmetro.
        contextoCongelado.definir(contextoDoJob);
        try {
            return traduzirArquivo(arquivoEntrada, permitirRetraducao, inicioMs, contextoDoJob);
        } finally {
            // Sempre: um snapshot vazado sobreviveria à thread da fila única e contaminaria
            // o próximo job com a lore do arquivo anterior.
            contextoCongelado.limpar();
        }
    }

    /**
     * PROPÓSITO DE NEGÓCIO: executa a tradução do arquivo já autorizada pela guarda e sob um
     * contexto congelado, do cache à publicação da legenda PT-BR.
     *
     * <p>INVARIANTES DO DOMÍNIO: toda leitura de lore desta execução vem do parâmetro
     * {@code contexto} — prompt do LLM, proveniência do cache, mapa de terminologia e nome
     * da lore na telemetria. Nenhuma consulta ao contexto global acontece daqui para baixo.
     *
     * <p>COMPORTAMENTO EM CASO DE FALHA: mantém o contrato de erro do fluxo original —
     * entrada aparentemente já traduzida sem confirmação lança
     * {@link EntradaJaTraduzidaException}; cancelamento no meio propaga
     * {@link TraducaoParcialException} com o progresso persistido; falha de I/O vira
     * {@link ArquivoLegendaException}.
     */
    private ResultadoTraducaoArquivo traduzirArquivo(
        Path arquivoEntrada, boolean permitirRetraducao, long inicioMs, SnapshotContexto contexto
    ) throws InterruptedException, ExecutionException {
        boolean ehSrt = ehSrt(arquivoEntrada);
        log.info("Lendo arquivo de legenda: {}", arquivoEntrada);
        DocumentoLegenda documento = ehSrt ? leitorSrt.ler(arquivoEntrada) : leitor.ler(arquivoEntrada);

        Path arquivoCache = resolvedorCache.resolverArquivoCache(arquivoEntrada, contexto.id());
        ProvenienciaCache proveniencia = resolvedorCache.provenienciaDe(contexto);
        boolean cacheAnteriorJaPreservado = false;
        if (permitirRetraducao && Files.exists(arquivoCache)) {
            politicaBackup.arquivarCacheAntesDaRetraducao(arquivoCache);
            cacheAnteriorJaPreservado = true;
        }
        // Prompt do snapshot congelado no início do arquivo: se o contexto global mudar
        // (troca de lore) enquanto este episódio traduz, o prompt já capturado continua
        // valendo até o fim — a mesma origem carimbada na proveniência acima.
        String promptCongelado = contexto.promptSistema();
        // Retradução liberada: a geração anterior é descartada EM MEMÓRIA. Antes, quem
        // produzia o mapa vazio era o arquivo ter sido apagado do disco no passo acima —
        // e era isso que deixava o episódio sem cache nenhum se a execução caísse no meio.
        CacheTraducaoService.ResultadoCarga carga = permitirRetraducao
            ? CacheTraducaoService.ResultadoCarga.vazio()
            : cacheService.carregar(arquivoCache, proveniencia, propriedades.reusoEntreModelos());
        Map<String, String> cacheExistente = carga.mapa();

        // Herdou de outro modelo? Então o carimbo tem de dizer isso. Gravar a proveniência SEM a
        // herança faria o arquivo afirmar que o modelo atual produziu falas que ele apenas
        // reaproveitou — e a proveniência é o que sustenta comparar modelos. O reuso é autorizado;
        // a mentira sobre ele, não.
        if (carga.herdouDeOutroModelo()) {
            proveniencia = proveniencia.herdandoDe(carga.modeloHerdado());
        }

        // Avisos de falas que ficaram sem tradução confiável (tags corrompidas,
        // resíduo detectado na revalidação final). Alimenta o campo
        // errosOcorridos da telemetria para o painel refletir o que exige
        // revisão manual.
        List<String> avisos = new ArrayList<>();
        if (carga.invalidadas() > 0) {
            String aviso = carga.invalidadas()
                + " entrada(s) do cache anterior invalidadas por mudança de lore/modelo (serão retraduzidas com o lore atual).";
            log.warn(aviso);
            uiLogger.log("[ INFO ] " + aviso);
            avisos.add(aviso);
        }
        if (protecaoAss.caminhoPareceTraduzido(arquivoEntrada)) {
            if (!permitirRetraducao) {
                String msg = "Entrada parece já traduzida (PT-BR): " + arquivoEntrada
                    + ". Retradução BLOQUEADA por padrão — confirme explicitamente para reprocessar.";
                log.warn(msg);
                uiLogger.log("[ BLOQUEADO ] " + msg);
                throw new EntradaJaTraduzidaException(msg);
            }
            String aviso = "Entrada parece já traduzida; reprocessando por confirmação explícita: " + arquivoEntrada;
            log.warn(aviso);
            uiLogger.log("[ WARN ] " + aviso);
            avisos.add(aviso);
        }

        Map<String, Long> frequenciaTextoLimpo = seletorEventos.calcularFrequenciaTextoLimpo(documento);
        Map<String, Long> instantesPorTexto = seletorEventos.calcularInstantesDistintosPorTexto(documento);
        // Pre-passe do peer legenda: quais linhas sao a camada ORIGINAL do karaoke (romaji). E uma
        // propriedade do DOCUMENTO — duas linhas no mesmo tempo —, por isso calculada uma vez aqui,
        // no mesmo molde da frequencia de texto. Com a flag desligada devolve protecao vazia e a
        // decisao do que traduzir fica identica a de antes.
        ProtecaoCamadas protecaoCamadas = propriedades.protecaoRomajiPareamento()
            ? seletorEventos.calcularProtecaoCamadas(documento)
            : ProtecaoCamadas.VAZIA;
        log.info("[ PROTECAO-ROMAJI ] pareamento de camadas {} — {} par(es), {} indeciso(s), {} linha(s) preservada(s)",
            propriedades.protecaoRomajiPareamento() ? "LIGADO" : "DESLIGADO",
            protecaoCamadas.paresEncontrados(), protecaoCamadas.paresIndecisos(),
            protecaoCamadas.indicesPreservados().size());
        List<EventoLegenda> eventosTraduziveis = documento.eventos().stream()
            .filter(evento -> seletorEventos.isTraduzivel(evento, frequenciaTextoLimpo, instantesPorTexto, protecaoCamadas))
            .toList();
        log.info("{} fala(s) traduzível(eis) encontrada(s) em {}", eventosTraduziveis.size(), arquivoEntrada.getFileName());

        LinkedHashSet<String> textosTraduziveisDistintos = new LinkedHashSet<>();
        eventosTraduziveis.forEach(evento -> textosTraduziveisDistintos.add(evento.texto()));

        Map<String, String> cacheReaproveitavel = new HashMap<>();
        // Falas cuja FONTE já está no idioma-alvo (fonte contaminada/meio-traduzida): não vão
        // ao LLM (evita eco/recusa) e são mantidas como estão. Mapa original->original.
        Map<String, String> jaNoIdiomaAlvo = new HashMap<>();
        LinkedHashSet<String> textosPendentes = new LinkedHashSet<>();
        // RECUO (09/10/2026): a entrada recusada SO por ter perdido a separacao dos locutores vai ao
        // modelo por locutor; se essa refacao terminar pendente, publica-se a traducao antiga (em
        // portugues, torta) e nao o ingles. Medido no Reconguista I: 63 entradas recusadas, 1 com o
        // trecho "G..." alucinado ("Bellri Zenam: Vamos la, G-Self!") ia virar ingles na tela.
        Map<String, String> recuoDoCache = new HashMap<>();
        Map<String, String> resolvidasSemModelo = new HashMap<>();
        int cacheSuspeito = 0;
        int cacheReparado = 0;
        for (String textoOriginal : textosTraduziveisDistintos) {
            String cacheado = cacheExistente.get(textoOriginal);
            String reparadoDoCache;
            if (cacheado != null && avaliadorCache.isCacheReaproveitavel(textoOriginal, cacheado)) {
                cacheReaproveitavel.put(textoOriginal, cacheado);
            } else if (cacheado != null
                    && (reparadoDoCache = avaliadorCache.repararCacheSePossivel(textoOriginal, cacheado)) != null) {
                // Defeito de par com conserto conhecido: conserta a entrada em vez de retraduzir a fala
                // inteira (o modelo insistia no defeito e piorava o resto). A7: candidato, reparo e
                // original ficam no log e no console.
                cacheReaproveitavel.put(textoOriginal, reparadoDoCache);
                cacheReparado++;
                String aviso = "entrada de cache reparada sem chamar o modelo: \"" + cacheado + "\" -> \""
                    + reparadoDoCache + "\". Original: " + textoOriginal;
                log.info(aviso);
                uiLogger.log("[REPARADA] " + aviso);
            } else if (detectorIdiomaFonte.jaNoIdiomaAlvo(textoOriginal, propriedades.idiomaTraduzido())) {
                jaNoIdiomaAlvo.put(textoOriginal, textoOriginal);
            } else {
                if (cacheado != null) {
                    cacheSuspeito++;
                    if (FalaDeLocutores.locutoresPerdidos(textoOriginal, cacheado) != null
                            && avaliadorCache.motivoFalhaFinal(textoOriginal, cacheado) == null) {
                        recuoDoCache.put(textoOriginal, cacheado);
                    }
                }
                // A fala que É um termo do glossário tem resposta sabida: não vai ao modelo. Antes,
                // "No." ia, o modelo era recusado nas três temperaturas e a fala saía em INGLÊS —
                // 49 de 165 no acervo de 28/10/2026, com a tradução certa escrita no glossário.
                java.util.Optional<String> doGlossario = enforcadorGlossarioFala.traduzirSemModelo(textoOriginal);
                if (doGlossario.isPresent()) {
                    resolvidasSemModelo.put(textoOriginal, doGlossario.get());
                    continue;
                }
                textosPendentes.add(textoOriginal);
            }
        }
        if (!resolvidasSemModelo.isEmpty()) {
            uiLogger.log("[ GLOSSARIO ] " + resolvidasSemModelo.size()
                + " fala(s) traduzida(s) pelo glossário de fala completa, sem chamar o modelo.");
        }
        log.info("{} fala(s) distinta(s) reaproveitada(s) do cache ({} reparada(s)), {} suspeita(s), {} pendente(s) de tradução",
            cacheReaproveitavel.size(), cacheReparado, cacheSuspeito, textosPendentes.size());
        uiLogger.registrarFalasCache(cacheReaproveitavel.size());
        if (!jaNoIdiomaAlvo.isEmpty()) {
            // Mensagem INFORMATIVA (não entra em 'avisos', que sinaliza pendência e marcaria o
            // episódio como PARCIAL): fala já no alvo é sucesso, não problema.
            String info = jaNoIdiomaAlvo.size() + " fala(s) já no idioma-alvo ("
                + propriedades.idiomaTraduzido() + ") na fonte — mantida(s) sem retradução (não enviadas ao LLM).";
            log.info(info);
            uiLogger.log("[ INFO ] " + info);
        }

        // Estilo por texto (reusado no dedup e no KPI de pendência). Só evento TRADUZÍVEL: um
        // Comment com o mesmo texto emprestava o estilo dele à fala (achado D8, 25/09/2026).
        Map<String, String> estiloPorTexto = new HashMap<>();
        for (EventoLegenda evento : eventosTraduziveis) {
            if (evento.temTexto()) {
                estiloPorTexto.putIfAbsent(evento.texto(), evento.estilo());
            }
        }
        // Dedup de camadas musicais (Incremento 2): o mesmo verso de OP/ED aparece
        // em muitas camadas KFX/clip com tags diferentes mas o MESMO texto mascarado.
        // Marca as falas NÃO-diálogo para o LLM traduzir cada mascarado uma vez e a
        // tradução ser reaplicada a cada camada com suas próprias tags. Diálogo fica
        // de fora (comportamento intacto). Romaji já nem chega aqui (é preservado).
        // Inventário de quebra isolável: onde o \N é quebra VISUAL, tirá-lo antes do
        // mascaramento evita o marcador no meio da frase — que é o que o LLM mais erra, porque
        // o português reordena as palavras e o marcador se perde. Diálogo já fazia isso; música
        // latina e letreiro entram agora. Medição que motivou (Break Blade filme 1, 2026-07-23):
        // 6 das 34 falas viraram pendência por marcador corrompido, TODAS verso de música e
        // cartela com tag no meio do texto. KFX e romaji preservado ficam FORA de propósito: ali
        // a quebra acompanha o tempo do efeito, não é decoração visual.
        Set<String> textosDeduplicaveis = new HashSet<>();
        Set<String> textosComQuebraIsolavel = new HashSet<>();
        for (String pendente : textosPendentes) {
            CategoriaConteudo categoria = classificadorPendencia.categoria(estiloPorTexto.get(pendente), pendente);
            if (categoria != CategoriaConteudo.DIALOGO) {
                textosDeduplicaveis.add(pendente);
            }
            if (categoria == CategoriaConteudo.DIALOGO
                || categoria == CategoriaConteudo.MUSICA_LATINA
                || categoria == CategoriaConteudo.LETREIRO) {
                textosComQuebraIsolavel.add(pendente);
            }
        }

        Map<String, String> traducoesNovas;
        DesfechoDasFalas desfecho = new DesfechoDasFalas();
        // Quem é VIZINHO no documento: pares de falas traduzíveis consecutivas. A corrente de frase
        // partida só pode ligar esses — a lista de pendentes perdeu as falas vindas do cache, as
        // repetidas e as mantidas por fonte em português, e nela vizinhas falsas ficam coladas.
        Set<String> paresVizinhos = new HashSet<>();
        for (int i = 1; i < eventosTraduziveis.size(); i++) {
            paresVizinhos.add(eventosTraduziveis.get(i - 1).texto() + '\u0000' + eventosTraduziveis.get(i).texto());
        }
        try {
            traducoesNovas = tradutorLotes.traduzirPendentes(textosPendentes, textosDeduplicaveis,
                textosComQuebraIsolavel, arquivoEntrada.getFileName().toString(), avisos, promptCongelado,
                desfecho, (anterior, seguinte) -> paresVizinhos.contains(anterior + '\u0000' + seguinte));
        } catch (TraducaoParcialException e) {
            Map<String, String> traducoesParciais = e.getDicionarioParcial();
            if (traducoesParciais != null && !traducoesParciais.isEmpty()) {
                log.info("Salvando {} traducoes parciais no cache antes de abortar o episodio", traducoesParciais.size());
                Map<String, String> combinadasParciais = new HashMap<>(cacheReaproveitavel);
                combinadasParciais.putAll(resolvidasSemModelo);
                combinadasParciais.putAll(traducoesParciais);
                // O recuo vale tambem aqui: a fala de locutores sem refacao fica com a do cache.
                recuoDoCache.forEach((original, antiga) -> {
                    String atual = combinadasParciais.get(original);
                    if (atual == null || atual.isBlank()) {
                        combinadasParciais.put(original, antiga);
                    }
                });
                Map<String, String> parciaisValidadas = new HashMap<>();
                for (Map.Entry<String, String> parcial : combinadasParciais.entrySet()) {
                    String motivo = avaliadorCache.motivoFalhaFinal(parcial.getKey(), parcial.getValue());
                    parciaisValidadas.put(parcial.getKey(), motivo == null ? parcial.getValue() : "");
                    if (motivo != null) {
                        telemetriaTraducao.registrarFallbackMantido();
                    }
                }

                List<EntradaCache> entradasCacheParcial = new ArrayList<>();
                for (EventoLegenda evento : documento.eventos()) {
                    if (seletorEventos.isTraduzivel(evento, frequenciaTextoLimpo, instantesPorTexto, protecaoCamadas)) {
                        String txtFinal = parciaisValidadas.get(evento.texto());
                        if (txtFinal != null
                                && !desfecho.traduzidasPorSegundaOpiniao().contains(evento.texto())) {
                            entradasCacheParcial.add(new EntradaCache(
                                evento.indice(), evento.estilo(), evento.texto(), txtFinal,
                                propriedades.idiomaOriginal(), propriedades.idiomaTraduzido()));
                        }
                    }
                }
                if (!entradasCacheParcial.isEmpty()) {
                    // Sem backup duplicado: a retradução já preservou esta mesma geração
                    // ANTES de começar (o arquivo ativo permanece intacto até aqui).
                    politicaBackup.salvarCacheDaExecucao(
                        arquivoCache, proveniencia, entradasCacheParcial,
                        permitirRetraducao && !cacheAnteriorJaPreservado);
                }
            }
            throw e;
        }

        Map<String, String> traducoesCombinadas = new HashMap<>(cacheReaproveitavel);
        traducoesCombinadas.putAll(resolvidasSemModelo);
        traducoesCombinadas.putAll(traducoesNovas);

        // KPI de pendência (schemaVersion 1.1): usa o estiloPorTexto já construído
        // acima para o balde de conteúdo, e o conjunto de falas cuja causa-raiz é
        // marcador corrompido (extraído dos avisos já emitidos pela via de
        // desmascaramento). Precedência: MARCADORES_CORROMPIDOS vence o ECO.
        Set<String> falasComTagCorrompida = new HashSet<>();
        for (String aviso : avisos) {
            if (aviso.startsWith(PREFIXO_TAGS_CORROMPIDAS)) {
                falasComTagCorrompida.add(aviso.substring(PREFIXO_TAGS_CORROMPIDAS.length()));
            }
        }
        // 1ª passada: separa o que passou na validação canônica do que ficou pendente.
        // Ainda NÃO emite aviso/KPI: o fallback online (opt-in) pode recuperar algumas
        // pendências antes de a lista final de pendentes ser consolidada.
        Map<String, String> traducoesValidadas = new HashMap<>();
        // Falas cujo texto publicado NAO veio do LLM desta execucao (recuo para a traducao antiga
        // do cache, fallback online): ficam fora da contagem "pelo LLM" (auditoria de 28/10/2026).
        Set<String> publicadasSemOModelo = new HashSet<>();
        LinkedHashSet<String> falhasDistintas = new LinkedHashSet<>();
        Map<String, String> motivoPorFalha = new HashMap<>();
        // Itera na ordem ESTÁVEL do documento (textosTraduziveisDistintos é LinkedHashSet), não
        // na ordem de um HashMap — assim os avisos, a fila do fallback Google e o KPI de
        // pendência ficam determinísticos entre execuções.
        for (String original : textosTraduziveisDistintos) {
            if (jaNoIdiomaAlvo.containsKey(original)) {
                continue; // fonte já no idioma-alvo: passthrough, fora da validação/pendência.
            }
            String traduzido = traducoesCombinadas.get(original);
            String motivoFalha = avaliadorCache.motivoFalhaFinal(original, traduzido);
            if (motivoFalha == null) {
                traducoesValidadas.put(original, traduzido);
                continue;
            }
            // ANTES de descartar: troca de entidade é a única falha que o sistema sabe CONSERTAR,
            // porque o portão já identificou o termo certo e o errado. A tradução costuma estar
            // impecável no resto — medido no Zeta em 2026-07-29, 22 das 59 falas descartadas eram
            // isto, incluindo a cena da morte da Four Murasame saindo em branco. O reparo passa
            // pelo MESMO portão; se não passar, a fala segue pendente como antes.
            String reparado = avaliadorCache.repararSePossivel(original, traduzido);
            if (reparado != null) {
                traducoesValidadas.put(original, reparado);
                String aviso = "Troca de entidade DESFEITA: \"" + traduzido + "\" -> \"" + reparado
                    + "\". Original: " + original;
                log.info(aviso);
                uiLogger.log("[REPARADA] " + aviso);
                continue;
            }
            // Segundo resgate: a fala é um NOME e o modelo a devolveu certa, só sem o itálico
            // que perdeu junto com o marcador. Reprovar aqui republica a fala EM INGLÊS — medido
            // no 08th em 2026-07-31: 8 das 16 falas com itálico descartado terminaram assim na
            // tela ("{\i1}Eledore!", "{\i1}Karen!", "{\i1}Sanders...").  Restaurar devolve o
            // ORIGINAL intacto: texto certo E itálico preservado.
            String identicaRestaurada = avaliadorCache.restaurarIdenticaSemItalico(original, traduzido);
            if (identicaRestaurada != null) {
                traducoesValidadas.put(original, identicaRestaurada);
                String aviso = "Itálico restaurado em fala-nome: o modelo perdeu a tag mas não mudou"
                    + " o texto. Publicado o original. Original: " + original;
                log.info(aviso);
                uiLogger.log("[RESTAURADA] " + aviso);
                continue;
            }
            // Terceiro resgate: a fala de locutores cuja entrada de cache foi recusada so pela
            // separacao torta, e cuja refacao por locutor nao fechou. A antiga e portugues e passou
            // no portao final; o ingles seria pior. A7: o motivo da refacao e a antiga vao ao log.
            String antigaDoCache = recuoDoCache.get(original);
            if (antigaDoCache != null) {
                traducoesValidadas.put(original, antigaDoCache);
                publicadasSemOModelo.add(original);
                String causaDaRefacao = desfecho.causaDoOriginalMantido().getOrDefault(original, motivoFalha);
                String aviso = "Refacao por locutor nao fechou (" + causaDaRefacao + "); mantida a traducao"
                    + " anterior do cache, com a separacao dos locutores torta: \"" + antigaDoCache
                    + "\". Original: " + original;
                log.warn(aviso);
                uiLogger.log("[RECUO] " + aviso);
                continue;
            }
            // Uma falha conhecida nunca volta ao banco como se fosse tradução.
            traducoesValidadas.put(original, "");
            falhasDistintas.add(original);
            motivoPorFalha.put(original, motivoFalha);
        }
        // Falas já no idioma-alvo entram direto como estão; passam pela mesma consolidação de
        // saída/cache adiante e ainda recebem o reforço determinístico de termos de lore.
        traducoesValidadas.putAll(jaNoIdiomaAlvo);

        // Fallback online de último recurso (opt-in, desligado por padrão): só as falas
        // de DIÁLOGO pendentes desta execução vão ao provedor externo; a resposta só é
        // aceita se passar pela MESMA validação canônica do LLM. Rede falha/recusa =
        // fala continua pendente. Quando desligado, recuperar() devolve mapa vazio.
        if (!falhasDistintas.isEmpty()) {
            LinkedHashSet<String> dialogosPendentes = new LinkedHashSet<>();
            for (String pendente : falhasDistintas) {
                if (classificadorPendencia.categoria(estiloPorTexto.get(pendente), pendente) == CategoriaConteudo.DIALOGO) {
                    dialogosPendentes.add(pendente);
                }
            }
            if (recuperarPendenciaGoogle.ativo() && !dialogosPendentes.isEmpty()) {
                uiLogger.log("[FALLBACK] LLM não resolveu " + dialogosPendentes.size()
                    + " fala(s) de diálogo — enviando ao tradutor de máquina ("
                    + recuperarPendenciaGoogle.provedorAtivo() + ")...");
            }
            ResultadoRecuperacao recuperacao = recuperarPendenciaGoogle.recuperar(dialogosPendentes);
            int reprovadasNaValidacao = 0;
            for (Map.Entry<String, String> candidata : recuperacao.recuperadas().entrySet()) {
                String original = candidata.getKey();
                String traducaoMaquina = candidata.getValue();
                if (avaliadorCache.motivoFalhaFinal(original, traducaoMaquina) != null) {
                    // Passou na guarda, mas a validação canônica (a mesma do LLM) reprovou:
                    // continua pendente. Contabilizado à parte para não inflar "recuperadas".
                    reprovadasNaValidacao++;
                    continue;
                }
                traducoesValidadas.put(original, traducaoMaquina);
                publicadasSemOModelo.add(original);
                falhasDistintas.remove(original);
                motivoPorFalha.remove(original);
                telemetriaTraducao.registrarFalhaTraducaoRecuperada();
                String recuperada = "Fala recuperada via fallback (último recurso): " + original;
                log.info(recuperada);
                uiLogger.log("[ OK ] " + recuperada);
            }
            // Relatório por CAUSA: sem ele, "recuperou N de M" não diz se o resto caiu por
            // provedor fora do ar, marcador corrompido ou guarda de lore — e não há como
            // decidir se vale trocar de provedor, ajustar a guarda ou subir um container.
            if (!dialogosPendentes.isEmpty() && recuperarPendenciaGoogle.ativo()) {
                String resumo = recuperacao.comReprovadasNaValidacao(reprovadasNaValidacao).resumoPorCausa();
                log.info("Fallback por causa: {}", resumo);
                uiLogger.log("[FALLBACK] Desfecho por causa: " + resumo);
            }
        }

        // 2ª passada: consolida avisos e KPI só das falas que SOBRARAM pendentes.
        List<ResumoPendencia> pendenciasUnitarias = new ArrayList<>();
        for (String original : falhasDistintas) {
            telemetriaTraducao.registrarFallbackMantido();
            String motivoFalha = motivoPorFalha.get(original);
            // A7: quando foi o PIPELINE que desistiu e manteve o original, a causa é a dele, não
            // o "devolveu o texto original" que o portão final deduz da saída idêntica. A
            // corrupção de marcador já tem registro próprio e precedência, por isso fica fora.
            String causaRegistrada = desfecho.causaDoOriginalMantido().get(original);
            if (causaRegistrada != null && !falasComTagCorrompida.contains(original)) {
                motivoFalha = "o LLM não produziu tradução aceitável nas tentativas ("
                    + causaRegistrada + ") e o sistema manteve o original";
            }
            String aviso = "Fala pendente após tentativas do LLM: " + motivoFalha + ". Original: " + original;
            log.warn(aviso);
            uiLogger.log("[ WARN ] " + aviso);
            avisos.add(aviso);

            CausaRaizPendencia causa = falasComTagCorrompida.contains(original)
                ? CausaRaizPendencia.MARCADORES_CORROMPIDOS
                : classificadorPendencia.causaDeMotivoFinal(motivoFalha);
            CategoriaConteudo categoria = classificadorPendencia.categoria(estiloPorTexto.get(original), original);
            pendenciasUnitarias.add(new ResumoPendencia(categoria.name(), causa.name(), 1));
        }
        List<ResumoPendencia> pendenciasPorCausa = classificadorPendencia.consolidar(pendenciasUnitarias);

        // Reforço determinístico de terminologia da lore ativa (ex.: "Legião" -> "Legion",
        // "Coveiro" -> "Undertaker"): restaura a grafia oficial nas traduções válidas SEM
        // depender do modelo. Só age quando o original contém o termo canônico; mapa vazio
        // (outra obra) é no-op. Não altera pendências (falas em branco são ignoradas).
        //
        // O REFORÇO É CONTADO E DITO EM VOZ ALTA, e isto não é enfeite de log.
        //
        // Até 2026-08-15 esta chamada usava `reforcar`, que joga a contagem fora — enquanto
        // `reforcarContando` já existia e era usada por outra fatia. O efeito prático: uma run
        // em que a lore restaurou 40 termos e uma em que ela não fez NADA imprimiam a mesma
        // coisa, que é nada. "Ligado e sem efeito" ficava indistinguível de "desligado",
        // exatamente o que a regra da saída ambígua proíbe — e foi assim que `Spearhead` chegou
        // à legenda final do 86 como "Esquadroe de Ponta" sem ninguém notar no console.
        //
        // Três estados aqui também: mapa VAZIO é "esta obra não declara terminologia", que é
        // diferente de "declarou e nada casou". Os dois saem escritos.
        // TRADUCOES OBRIGATORIAS antes do reforco: a lore manda TRADUZIR estes termos, e a
        // ordem importa porque os dois mexem no mesmo texto. Este conserta o que ficou em
        // ingles; o reforco logo abaixo restaura o que o modelo traduziu e nao devia.
        // Decisao do Paulo em 22/08/2026, sobre as 7 falas do Unicorn publicadas como
        // "Universal Century 0096.".
        // Fotografia do que o PORTÃO aprovou, antes de qualquer transformação: é a versão que volta
        // se uma transformação abaixo deixar a fala reprovável (ver desfazerTransformacoesQueReprovam).
        Map<String, String> aprovadasPeloPortao = new HashMap<>(traducoesValidadas);
        Map<String, String> obrigatorias = contexto.traducoesObrigatorias();
        if (!obrigatorias.isEmpty()) {
            int trocadas = 0;
            for (Map.Entry<String, String> traducao : traducoesValidadas.entrySet()) {
                String traduzido = traducao.getValue();
                if (traduzido == null || traduzido.isBlank()) {
                    continue;
                }
                String comTermoPt = enforcadorTermosLore.traduzirObrigatorios(
                    traducao.getKey(), traduzido, obrigatorias);
                if (!comTermoPt.equals(traduzido)) {
                    traducao.setValue(comTermoPt);
                    trocadas++;
                }
            }
            // Zero e IMPRESSO: a obra declarou termos, entao "nenhum sobrou em ingles" e
            // resultado, nao ausencia de medicao.
            uiLogger.log("[ LORE ] " + contexto.nomeExibicao() + ": " + trocadas
                + " fala(s) com termo traduzido por obrigacao da lore, de "
                + obrigatorias.size() + " termo(s) declarado(s).");
        }

        Map<String, String> correcoesLore = contexto.correcoesTerminologia();
        if (correcoesLore.isEmpty()) {
            uiLogger.log("[ LORE ] " + contexto.nomeExibicao()
                + ": nenhum termo de terminologia declarado — nada a reforçar nesta obra.");
        } else {
            Map<String, Integer> restauradosPorTermo = new java.util.TreeMap<>();
            for (Map.Entry<String, String> traducao : traducoesValidadas.entrySet()) {
                String traduzido = traducao.getValue();
                if (traduzido != null && !traduzido.isBlank()) {
                    var reforco = enforcadorTermosLore.reforcarContando(
                        traducao.getKey(), traduzido, correcoesLore);
                    traducao.setValue(reforco.texto());
                    reforco.restauracoesPorTermo()
                        .forEach((termo, n) -> restauradosPorTermo.merge(termo, n, Integer::sum));
                }
            }
            int total = restauradosPorTermo.values().stream().mapToInt(Integer::intValue).sum();
            if (total == 0) {
                uiLogger.log("[ LORE ] " + contexto.nomeExibicao() + ": " + correcoesLore.size()
                    + " termo(s) no glossário, 0 restauração — o modelo não errou nenhum aqui.");
            } else {
                String detalhe = restauradosPorTermo.entrySet().stream()
                    .sorted(java.util.Map.Entry.<String, Integer>comparingByValue().reversed())
                    .limit(8)
                    .map(e -> e.getKey() + "×" + e.getValue())
                    .collect(java.util.stream.Collectors.joining(", "));
                uiLogger.log("[ LORE ] " + contexto.nomeExibicao() + ": " + total
                    + " termo(s) restaurado(s) de " + correcoesLore.size()
                    + " no glossário — " + detalhe
                    + (restauradosPorTermo.size() > 8 ? ", …" : ""));
            }
        }

        // Normalização determinística final das traduções válidas (roda SEMPRE, independe de
        // lore; falas em branco/pendentes são ignoradas):
        //  (1) aspas: remove as que ENVOLVEM a fala inteira quando o EN não as tinha (o LLM às
        //      vezes cita um diálogo que a fonte não citava);
        //  (2) acentos: repõe acento nas formas que sem ele nunca são palavra válida (infancia,
        //      nao, ...), sem tocar homógrafos;
        //  (3) glossário de fala completa: fixa a forma canônica das falas que são INTEIRAMENTE
        //      um termo conhecido ("Roger." -> "Entendido."). Roda por ÚLTIMO porque decide a
        //      partir do ORIGINAL e reescreve a fala inteira — é o único ponto capaz de corrigir
        //      tradução alucinada ("Shiro: Roger!") e erro semântico ("Rogério."), que passam
        //      limpos por todas as outras guardas justamente por não serem iguais ao original.
        int corrigidasPeloDicionario = 0;
        // Telemetria em cada canto: um contador do que AGIU e um do que se ABSTEVE. Sem o
        // segundo, "0 corrigidas" não distingue "não havia defeito nesta legenda" de "o corretor
        // ficou cego" — e foi exatamente essa ambiguidade que deixou 566 falas de acento
        // atravessarem o acervo sem ninguém notar.
        int falasComHomografoCorrigido = 0;
        int falasComHomografoIntocado = 0;
        // AQUECIMENTO EM LOTE, como a 3.3 faz desde b8e4014c. O corretor consulta o dicionário
        // por palavra, e sem o lote cada palavra nova paga um processo externo. Medido em
        // 09/10/2026 no filme de Sidonia: 258,5 s frio contra 30,8 s aquecido, 0 de 1405 saídas
        // diferentes — aquecer só antecipa as consultas, não muda nenhuma resposta.
        corretorOrtografico.aquecerComTextos(traducoesValidadas.values().stream()
            .filter(t -> t != null && !t.isBlank())
            .toList());
        for (Map.Entry<String, String> traducao : traducoesValidadas.entrySet()) {
            String traduzido = traducao.getValue();
            if (traduzido != null && !traduzido.isBlank()) {
                String normalizado = normalizadorAspas.normalizar(traducao.getKey(), traduzido);
                // Os termos da lore também ficam fora do normalizador: o reforço de lore acabou de
                // escrevê-los, e "Hestia Familia" virava "Família" logo depois (28/10/2026).
                normalizado = normalizadorAcentos.normalizar(normalizado, contexto.termosProtegidos());
                // DEPOIS do normalizador determinístico, e não antes: o que a lista e a regra de
                // terminação já resolvem não chega a custar consulta ao dicionário. Sobra o que
                // nenhuma regra alcança — fatidico, minimo, aereo, psicicas, medidos no Unicorn em
                // 13/08/2026, quando o dicionário manual resolvia ZERO das 119 faltas do Zeta.
                String antesDoDicionario = normalizado;
                // Os termos de lore da obra sao INTOCAVEIS para o dicionario. Nome proprio de
                // ficcao nao leva acento do portugues, e o dicionario nao tem como saber que
                // aquilo e um nome. Medido no acervo em 18/08/2026: tres termos chegaram
                // acentuados a legenda entregue, em 32 falas, e os TRES sao defeito — inclusive
                // o que parecia excecao legitima. No Zeta, "Bosnia" e uma NAVE ("Send a signal
                // flare to the Bosnia"), e "Bósnia" a transformou num pais. O resultado e
                // portugues impecavel, o que torna o defeito invisivel para quem nao confere
                // contra o ingles.
                normalizado = corretorOrtografico.corrigir(
                    normalizado, contexto.termosProtegidos());
                if (!normalizado.equals(antesDoDicionario)) {
                    corrigidasPeloDicionario++;
                }
                // HOMÓGRAFO, que nenhum dos de cima enxerga. `e` e `esta` são palavras válidas
                // em português, então dicionário nenhum as acusa — está errado o SENTIDO, e o
                // NormalizadorAcentosComuns as exclui de propósito por isso. Quem responde é o
                // ORIGINAL, que aqui está em mãos: sem coordenador no inglês não há o que
                // coordenar, e todo `e` solto é o verbo. Medido no acervo em 03/09/2026, depois
                // de a 3.3 já ter rodado em agosto: 371 falas com `e` por `é` e 195 com `esta`
                // por `está`, o ZZ com 166. Roda DEPOIS do dicionário porque decide por contexto,
                // não por grafia, e antes do glossário para o reforço ver a fala já acentuada.
                String antesDoHomografo = normalizado;
                normalizado = corretorHomografo.corrigir(traducao.getKey(), normalizado);
                if (!normalizado.equals(antesDoHomografo)) {
                    falasComHomografoCorrigido++;
                } else if (temHomografoSolto(normalizado)) {
                    falasComHomografoIntocado++;
                }
                normalizado = normalizadorCartaoData.normalizar(traducao.getKey(), normalizado);
                normalizado = enforcadorGlossarioFala.reforcar(traducao.getKey(), normalizado);
                traducao.setValue(normalizado);
            }
        }

        // O PORTÃO NA FRONTEIRA CERTA: depois da última transformação e antes de gravar. A A6
        // (conferirArquivoGravado) acusava fala que passou no portão e reprova no arquivo, mas só
        // registrava — o arquivo já estava gravado. Medido no log: 8 casos desde 17/09/2026.
        List<String> desfeitas = desfazerTransformacoesQueReprovam(
            traducoesValidadas, aprovadasPeloPortao, avaliadorCache::motivoFalhaFinal);
        desfeitas.forEach(aviso -> {
            log.warn(aviso);
            uiLogger.log("[ DESFEITA ] " + aviso);
        });
        uiLogger.log("[ PORTAO POS-TRANSFORMACAO ] " + desfeitas.size()
            + " fala(s) devolvida(s) à versão aprovada, de " + aprovadasPeloPortao.size() + " conferida(s).");

        // O DICIONÁRIO DIZ O QUE FEZ, em TRÊS estados — e o terceiro é o que faltava.
        //
        // Sem esta linha, "o hunspell corrigiu 0 palavras" e "o hunspell não está instalado"
        // produziam o mesmo silêncio no console da Tradução Local. Quem rodasse numa máquina
        // sem o dicionário veria a run inteira passar sem nenhum aviso e concluiria que a
        // ortografia foi conferida. A fatia de karaokê já dizia isso desde 14/08; a tradução,
        // que é quem escreve a legenda final, não.
        if (!corretorOrtografico.disponivel()) {
            uiLogger.log("[ ORTOGRAFIA ] Dicionário do sistema INDISPONÍVEL — a ortografia NÃO foi "
                + "verificada nesta execução. Ausência de correção aqui não significa texto correto.");
        } else if (corrigidasPeloDicionario == 0) {
            uiLogger.log("[ ORTOGRAFIA ] Dicionário ativo, 0 fala corrigida — nada a repor neste arquivo.");
        } else {
            uiLogger.log("[ ORTOGRAFIA ] Dicionário ativo: " + corrigidasPeloDicionario
                + " fala(s) com ortografia corrigida.");
        }

        // HOMÓGRAFO: o par que dicionário nenhum vê, porque `e` e `esta` são palavras válidas em
        // português — está errado o sentido, não a grafia. As DUAS linhas existem porque uma só
        // mentiria: "0 corrigidas" com falas intocadas significa que o original não provou nada e
        // o corretor se absteve, que é muito diferente de não haver defeito.
        if (falasComHomografoCorrigido > 0 || falasComHomografoIntocado > 0) {
            uiLogger.log("[ HOMÓGRAFO  ] " + falasComHomografoCorrigido
                + " fala(s) com é/está repostos pela prova do original; "
                + falasComHomografoIntocado
                + " com e/esta solto que o original NÃO provou ser verbo (abstenção).");
        }

        // DIAGNÓSTICO de nome próprio traduzido. Só mede: não reescreve nenhuma fala, porque a
        // versão anterior desta ideia — "toda palavra capitalizada tem de sobreviver" — foi
        // removida do projeto por gerar 323 falso positivo em 560 pendências (57,7%). O que mudou
        // foi passar a existir dicionário de quatro idiomas para separar Sky, que não é palavra de
        // idioma nenhum, de Never, que é inglês comum capitalizado.
        //
        // Vale sobretudo SEM LORE: ali correcoesTerminologia() é vazio por definição e nada além
        // da instrução em prosa impede o modelo de traduzir nome de personagem. Roda também com
        // lore para os dois números serem comparáveis. Uma consulta ao dicionário por EXECUÇÃO.
        //
        // O número é PISO, não teto: nome próprio que também é palavra comum escapa. Medido em
        // 13/08/2026 no Memories — "Heinz", 17 falas, passa ileso porque en_US e de_DE conhecem a
        // palavra. Pegá-lo exigiria acusar toda capitalizada, que é o falso positivo de 57,7%.
        var nomesProprios = detectorNomeProprio.verificarLote(traducoesValidadas);
        if (!nomesProprios.verificado()) {
            uiLogger.log("[ INFO ] Nomes próprios: NÃO VERIFICADO (dicionário indisponível) — "
                + "ausência de aviso aqui não significa que nenhum nome foi traduzido.");
        } else if (nomesProprios.temPerda()) {
            String aviso = nomesProprios.totalPerdidos() + " nome(s) próprio(s) do original ausente(s) "
                + "da tradução em " + nomesProprios.falasAfetadas() + " fala(s), de "
                + nomesProprios.candidatasExaminadas() + " candidata(s) examinada(s): "
                + resumirNomesPerdidos(nomesProprios.perdidos());
            log.info(aviso);
            uiLogger.log("[ INFO ] " + aviso);
        }

        List<EventoLegenda> eventosFinais = new ArrayList<>(documento.eventos().size());
        List<EntradaCache> entradasCache = new ArrayList<>();
        // Dataset das falas que saem IGUAIS a origem, com o motivo de cada uma. Sem ele, "por
        // que esta fala esta em ingles?" so tem resposta recalculando por fora as regras que o
        // pipeline aplicou por dentro — e foi assim que a auditoria de 07/08/2026 classificou
        // 18.431 falas de letra de musica como residuo de traducao.
        List<FalaNaoTraduzida> naoTraduzidas = new ArrayList<>();
        int falasItalicoRemovido = 0;
        int falasItalicoPreservado = 0;
        // QUADROS DE TRANSIÇÃO herdam a tradução da fala irmã. Um "wipe" de legenda é a mesma fala
        // repetida em cópias de 0,08 s com \clip; o seletor as exclui como typesetting de alto
        // risco, e elas saíam em inglês logo depois da fala em português — a legenda PISCAVA
        // (medido em 25/09/2026: 71 quadros em 31 episódios do acervo, 13 de diálogo e 27 de
        // título do Zeta). A cópia recebe a tradução da irmã SEM tags do MESMO estilo e mesmo
        // texto, com as próprias tags de prefixo. Mesmo estilo de propósito: verso de música com o
        // mesmo texto de um diálogo não pode herdar tradução — música não é trabalho desta tela.
        Map<String, String> traducaoDaIrma = new HashMap<>();
        for (EventoLegenda irma : documento.eventos()) {
            if (!irma.isDialogo() || !irma.temTexto() || irma.texto().indexOf('{') >= 0
                    || !seletorEventos.isTraduzivel(irma, frequenciaTextoLimpo, instantesPorTexto, protecaoCamadas)) {
                continue;
            }
            String validada = traducoesValidadas.get(irma.texto());
            String semItalicoIrma = validada == null ? null : removedorItalico.remover(validada);
            if (semItalicoIrma != null && !semItalicoIrma.isBlank() && semItalicoIrma.indexOf('{') < 0) {
                traducaoDaIrma.putIfAbsent(irma.estilo() + '\u0000' + irma.texto(), semItalicoIrma);
            }
        }
        int quadrosHerdados = 0;
        for (EventoLegenda evento : documento.eventos()) {
            String instante = instanteDe(evento);
            if (!seletorEventos.isTraduzivel(evento, frequenciaTextoLimpo, instantesPorTexto, protecaoCamadas)) {
                java.util.regex.Matcher soPrefixo = evento.isDialogo() && evento.temTexto()
                    ? PREFIXO_DE_TAGS_E_TEXTO.matcher(evento.texto()) : null;
                String herdada = soPrefixo != null && soPrefixo.matches()
                    ? traducaoDaIrma.get(evento.estilo() + '\u0000' + soPrefixo.group(2)) : null;
                if (herdada != null) {
                    eventosFinais.add(evento.comTexto(soPrefixo.group(1) + herdada));
                    quadrosHerdados++;
                    continue;
                }
                eventosFinais.add(evento);
                // Só FALA entra no dataset de falas não traduzidas. O leitor devolve como evento
                // também a linha vazia, o Comment e a seção [Aegisub Extradata] inteira — no 86
                // E01 eram 100 linhas assim, registradas como "preservada por regra: música,
                // karaokê..." (medido em 25/09/2026). Não eram falas nem foram preservadas por
                // regra nenhuma de tradução.
                if (evento.isDialogo()) {
                    naoTraduzidas.add(new FalaNaoTraduzida(evento.indice(), instante, evento.estilo(),
                        FalaNaoTraduzida.Motivo.PRESERVADA_POR_REGRA,
                        "seletor: musica, karaoke, romaji protegido ou estilo em estilos-ignorados"));
                }
                continue;
            }
            if (!traducoesValidadas.containsKey(evento.texto())) {
                throw new ArquivoLegendaException(
                    "Falha interna: nenhuma tradução encontrada para a fala do evento " + evento.indice()
                        + " em " + arquivoEntrada);
            }
            String textoValidado = traducoesValidadas.get(evento.texto());
            boolean semTraducaoUtil = textoValidado == null || textoValidado.isBlank();
            String textoFinal = semTraducaoUtil ? evento.texto() : textoValidado;
            if (semTraducaoUtil) {
                naoTraduzidas.add(new FalaNaoTraduzida(evento.indice(), instante, evento.estilo(),
                    FalaNaoTraduzida.Motivo.PENDENTE,
                    "sem traducao aproveitavel — marcador corrompido, eco ou estrutura divergente; "
                        + "a causa-raiz por fala esta na telemetria do episodio"));
            } else if (textoFinal != null && textoFinal.equals(evento.texto())) {
                naoTraduzidas.add(new FalaNaoTraduzida(evento.indice(), instante, evento.estilo(),
                    FalaNaoTraduzida.Motivo.TRADUCAO_IGUAL_AO_ORIGINAL,
                    "o LLM devolveu texto identico — legitimo em nome proprio e fala de uma palavra"));
            }
            // ITALICO ELIMINADO NA SAIDA — o segundo ponto da regra de 22/08/2026, e o que a
            // torna completa. O RemovedorItalico do TradutorLotesService age no que VAI ao LLM;
            // aqui age no que VAI PARA O ARQUIVO, e por isso alcanca tambem o que nunca passou
            // pelo modelo: fala vinda do CACHE (chave e valor gravados antes da regra), fala
            // recuperada pelo fallback Google e fala pendente publicada em ingles.
            //
            // MUSICA NAO CHEGA AQUI: o seletorEventos.isTraduzivel logo acima ja mandou musica,
            // karaoke, romaji protegido e estilo-ignorado embora com continue. O veto e aplicado
            // por quem sabe aplica-lo, e nao reimplementado neste laco.
            String semItalico = removedorItalico.remover(textoFinal);
            if (!java.util.Objects.equals(semItalico, textoFinal)) {
                falasItalicoRemovido++;
            } else if (removedorItalico.temItalico(textoFinal)) {
                // A REGRA SE ABSTEVE. Nao e "nao havia italico": havia, e o removedor recuou
                // porque a fala herda o italico do Style: do cabecalho. Sem este contador o
                // recuo e invisivel, e uma obra inteira poderia sair em italico sem sinal.
                falasItalicoPreservado++;
            }
            eventosFinais.add(evento.comTexto(semItalico));
            entradasCache.add(new EntradaCache(
                evento.indice(), evento.estilo(), evento.texto(), textoValidado,
                propriedades.idiomaOriginal(), propriedades.idiomaTraduzido()));
        }

        // Carimbo de proveniencia NO PROPRIO ARQUIVO. Sem ele, "por que esta fala ficou em
        // ingles?" e "cade as 138 falas que sumiram?" so tem resposta recalculando por fora as
        // regras que o pipeline ja aplicou por dentro — e recalcular por fora foi a causa das
        // CINCO conclusoes erradas da auditoria de 07/08/2026, entre elas 18.431 falas
        // classificadas como residuo de traducao que eram letra de musica.
        //
        // So ASS: o SRT nao tem cabecalho para comentario, e inventar um quebraria o formato.
        // O DESTINO E RESOLVIDO ANTES DO CARIMBO porque o carimbo FALA SOBRE ELE. Enquanto a
        // ordem era a inversa, a frase "arquivo publicado como parcial" era escrita sempre que
        // houvesse pendencia — inclusive nas retraducoes, que publicam o arquivo FINAL mesmo
        // com pendencia (ResolvedorSaidaLegenda.selecionar, protecaoLiberada). Medido em
        // 2026-09-09 no DanMachi S01E01: o arquivo em disco era "..._PT-BR.ass", final, e o
        // cabecalho dele afirmava ser parcial. Como a regra de auditoria do projeto diz que
        // ".parcial nao e entrega", o carimbo mentiroso ensina a DESCARTAR uma entrega valida.
        // A condicao nao e recalculada aqui: ela e CONSULTADA no caminho que o resolvedor
        // escolheu, para nao existir uma segunda implementacao da mesma decisao.
        Path arquivoSaidaFinal = resolvedorSaida.resolverSaidaFinal(arquivoEntrada, pastasExecucao.diretorioSaida());
        Path arquivoSaida = resolvedorSaida.selecionar(
            arquivoSaidaFinal, !falhasDistintas.isEmpty(), permitirRetraducao);
        boolean publicadoComoParcial = !arquivoSaida.equals(arquivoSaidaFinal);

        String cabecalhoFinal = documento.cabecalho();
        if (!ehSrt) {
            // FALAS, não linhas: o documento também guarda como evento a linha vazia, o Comment e
            // a seção [Aegisub Extradata]. Contá-los inflava "preservadas por regra" — 7 no 0080
            // E01, 100 no 86 E01, medido em 25/09/2026 —, e o carimbo existe justamente para
            // ninguém ter de recalcular isso por fora.
            int naOrigem = (int) documento.eventos().stream().filter(EventoLegenda::isDialogo).count();
            int traduziveis = entradasCache.size();
            List<String> carimbo = new ArrayList<>();
            carimbo.add("traduziu: " + naOrigem + " fala(s) na origem, " + traduziveis
                + " traduzivel(is), " + (naOrigem - traduziveis)
                + " preservada(s) por regra do pipeline (musica, karaoke, estilo ignorado)");
            if (quadrosHerdados > 0) {
                carimbo.add("quadros de transicao: " + quadrosHerdados
                    + " copia(s) com \\clip receberam a traducao da fala irma (contadas entre as preservadas)");
            }
            carimbo.add("lore: " + contexto.nomeExibicao() + " (" + contexto.id() + ")");
            if (!falhasDistintas.isEmpty()) {
                carimbo.add("pendentes: " + falhasDistintas.size()
                    + " fala(s) mantida(s) no original — "
                    + (publicadoComoParcial
                        ? "arquivo publicado como parcial"
                        : "arquivo publicado como FINAL assim mesmo, por retraducao autorizada"));
            }
            cabecalhoFinal = CarimboCabecalhoLegenda.aplicar(cabecalhoFinal, carimbo);
        }

        DocumentoLegenda documentoFinal = new DocumentoLegenda(
            cabecalhoFinal, eventosFinais, documento.quebraDeLinha(), documento.comBom());

        // O CACHE ANTES DA LEGENDA (auditoria de 28/10/2026). O cache era gravado so depois de
        // publicar, e o unico salvamento de emergencia fica em volta da traducao: qualquer falha
        // daqui para baixo (backup obrigatorio, AccessDenied depois das retentativas, disco que
        // saiu) descartava o trabalho do LLM no episodio inteiro, e a proxima execucao retraduzia
        // tudo. O conteudo do cache nao depende da publicacao (a mescla decide so o que vai ao
        // arquivo). Se a publicacao falhar agora, cache novo + arquivo e base antigos fazem a
        // proxima execucao levar a mudanca ao arquivo pela regra "mudou so no cache".
        // SEGUNDA OPINIÃO NÃO VAI PARA O CACHE: o carimbo de proveniência declara UM modelo para o
        // arquivo inteiro, e guardar ali a tradução do modelo de recuperação faria a execução
        // seguinte reusá-la como se o principal a tivesse produzido. A promessa existia desde
        // 11/08/2026 em ProcessarEpisodioUseCase e não tinha consumidor — medido em 25/09/2026.
        List<EntradaCache> entradasParaCache = desfecho.traduzidasPorSegundaOpiniao().isEmpty()
            ? entradasCache
            : entradasCache.stream()
                .filter(e -> !desfecho.traduzidasPorSegundaOpiniao().contains(e.original()))
                .toList();
        // Substituição atômica da geração: só AQUI o cache ativo deixa de ser o anterior.
        // preservarAnterior fica falso quando a retradução já copiou esta mesma geração no
        // início — do contrário o mesmo arquivo iria duas vezes para backups/traducao-cache.
        politicaBackup.salvarCacheDaExecucao(arquivoCache, proveniencia, entradasParaCache,
            permitirRetraducao && !cacheAnteriorJaPreservado);

        // Registro do que o cache gerou nesta publicação: é a BASE da mesclagem de três vias da
        // próxima reexecução. Mora ao lado do cache da obra, fora da pasta de saída — nada ali pode
        // ser confundido com legenda pelo remux.
        Path basePublicada = basePorPastaDeSaida(arquivoCache, arquivoSaida);
        ResultadoPublicacao publicacao = publicar(arquivoSaida, documentoFinal, ehSrt,
            permitirRetraducao && arquivoSaida.equals(arquivoSaidaFinal), avisos, basePublicada,
            baseLegadaPorNome(arquivoCache, arquivoSaida));
        Path backupSobrescrita = publicacao.backup();
        // A6 DO ADITIVO: a aprovação tem de alcançar a ÚLTIMA transformação, e a última aqui é a
        // gravação. Tudo o que este método valida acontece ANTES da terminologia, da normalização
        // de aspas, do carimbo de cabeçalho e da serialização — e a auditoria de 09/09 apontou
        // exatamente isso: "os ensaios não comprovaram o fluxo completo até a gravação do .ass".
        conferirArquivoGravado(arquivoSaida, ehSrt, entradasCache, jaNoIdiomaAlvo.keySet(),
            publicacao.preservadasDoArquivo());

        // Depois de a legenda estar em disco: o dataset e ACESSORIO e a porta engole a propria
        // falha, entao perder o registro nunca custa o arquivo que acabou de ser publicado.
        telemetriaTraducao.registrarFalasNaoTraduzidas(
            arquivoSaida, resolvedorCache.animeAPartirDoArquivo(arquivoEntrada), naoTraduzidas);

        long tempoTotalMs = System.currentTimeMillis() - inicioMs;
        String animeNome = resolvedorCache.animeAPartirDoArquivo(arquivoEntrada);
        String loreNome = contexto.nomeExibicao();
        int traducoesNovasValidas = (int) traducoesNovas.entrySet().stream()
            .filter(e -> !publicadasSemOModelo.contains(e.getKey()))
            .filter(e -> {
                String validada = traducoesValidadas.get(e.getKey());
                return validada != null && !validada.isBlank();
            })
            .count();
        uiLogger.registrarFalasNovas(traducoesNovasValidas);
        // O status reflete PENDÊNCIA FINAL, não aviso. `avisos` também carrega mensagens
        // meramente informativas — cache invalidado por mudança de lore e reprocessamento
        // confirmado pelo operador — que não deixam nenhuma fala por traduzir. Enquanto o
        // status dependia delas, um episódio 100% traduzido saía PARCIAL: no run de Guilty
        // Crown foram SETE episódios marcados assim sem ter uma única pendência, o que torna
        // o indicador inútil justamente para quem precisa achar o que ainda falta.
        // `falhasDistintas` já é a lista consolidada DEPOIS da recuperação pelo fallback.
        StatusArquivoTraducao status = falhasDistintas.isEmpty()
            ? StatusArquivoTraducao.CONCLUIDO : StatusArquivoTraducao.PARCIAL;
        telemetriaTraducao.registrarTraducao(montadorTelemetria.montar(
            arquivoEntrada, eventosTraduziveis.size(), traducoesNovasValidas,
            cacheReaproveitavel.size(), tempoTotalMs, avisos, animeNome, loreNome, status,
            pendenciasPorCausa, falasItalicoRemovido, falasItalicoPreservado));

        if (status == StatusArquivoTraducao.PARCIAL) {
            if (permitirRetraducao) {
                log.warn("Tradução parcial publicada em {} por liberação explícita: {} fala(s) distinta(s) continuam pendentes; backup anterior em {}.",
                    arquivoSaida, falhasDistintas.size(), backupSobrescrita);
            } else {
                log.warn("Tradução parcial salva em {}: {} fala(s) distinta(s) continuam pendentes; saída final {} não foi sobrescrita.",
                    arquivoSaida, falhasDistintas.size(), arquivoSaidaFinal);
            }
        } else {
            log.info("Arquivo traduzido salvo em {} (cache em {})", arquivoSaida, arquivoCache);
        }
        // Mesmos valores que já iam para a telemetria (linha do registrarTraducao acima):
        // o relatório de console passa a receber tempo e causa das pendências pelo retorno,
        // em vez de reler logs/telemetria_traducao.json.
        return new ResultadoTraducaoArquivo(
            arquivoSaida, arquivoEntrada.getFileName().toString(), loreNome,
            eventosTraduziveis.size(), cacheReaproveitavel.size(), traducoesNovasValidas,
            avisos.size(), status, tempoTotalMs, falhasDistintas.size(), pendenciasPorCausa);
    }



    private static boolean ehSrt(Path arquivo) {
        return arquivo.getFileName().toString().toLowerCase().endsWith(".srt");
    }

    private void escreverLegenda(Path destino, DocumentoLegenda documento, boolean ehSrt) {
        if (ehSrt) {
            escritorSrt.escrever(destino, documento);
        } else {
            escritor.escrever(destino, documento);
        }
    }

    /**
     * O que a publicação fez: o backup da versão anterior (quando houve substituição) e os índices
     * das falas mantidas do arquivo — corrigidas nele por outra tela ou à mão —, que a conferência
     * pós-gravação não pode tratar como divergência.
     */
    record ResultadoPublicacao(Path backup, Set<Integer> preservadasDoArquivo) {
        static final ResultadoPublicacao NADA = new ResultadoPublicacao(null, Set.of());
    }

    /** Resultado da mesclagem de três vias. */
    private record Mescla(DocumentoLegenda documento, Set<Integer> preservadas, int atualizadas,
            int conflitos, boolean cabecalhoPreservado, String exemploConflito) {}

    /**
     * PROPÓSITO DE NEGÓCIO: publica a legenda PT-BR sem destruir o que foi corrigido nela depois da
     * última publicação, e sem deixar de entregar o que foi corrigido no cache.
     *
     * <h2>O prejuízo que originou (auditoria de 25/09/2026)</h2>
     * A Tradução Local regenera a legenda a partir do CACHE. As telas que corrigem a legenda depois
     * dela — Revisão de Lore, Concordância, a correção de tags, a troca de fonte e o Aegisub —
     * escrevem direto no {@code .ass} e não tocam no cache. Reexecutar a 2.1 com a proteção LIGADA
     * regravava o {@code _PT-BR} e desfazia essas correções sem backup (medido no 0080 E02). E
     * reexecutar é justamente o que a tela da 2.3 manda fazer para publicar o cache corrigido — os
     * dois fluxos legítimos colidiam no mesmo arquivo.
     *
     * <h2>Invariantes do domínio</h2>
     * <ul>
     *   <li>MESCLAGEM DE TRÊS VIAS por linha, com a BASE = o que o cache gerou na publicação
     *       anterior (gravada a cada publicação em {@code cache/<obra>/.publicado/}): linha que o
     *       arquivo não mudou recebe a do cache (fluxo da 2.3); linha que o arquivo mudou e o cache
     *       não é MANTIDA (correção humana ou de outra tela); linha mudada nos dois lados é conflito,
     *       mantida a do arquivo e avisada.</li>
     *   <li>Sem base, ou com o arquivo estruturalmente diferente (linhas incluídas ou removidas), não
     *       há como separar os dois casos: substitui pelo cache COM backup obrigatório e aviso.</li>
     *   <li>Conteúdo final idêntico ao publicado não é regravado — uma reexecução sem mudança
     *       produz o arquivo byte a byte igual, então não há backup por ruído.</li>
     *   <li>Toda substituição é precedida de backup; falha no backup aborta a publicação.</li>
     *   <li>Retradução liberada é substituição PEDIDA: não mescla, faz backup e publica o cache.</li>
     * </ul>
     *
     * <h2>Comportamento em caso de falha</h2>
     * Falha ao gerar, comparar ou fazer backup lança {@link ArquivoLegendaException} antes de o
     * destino ser tocado. Falha ao ler a base ou o arquivo para mesclar degrada para a substituição
     * com backup (declarada no console). Falha ao gravar a base só é avisada — a legenda já foi
     * publicada, e a próxima reexecução cai no caminho sem base.
     */
    private ResultadoPublicacao publicar(Path destino, DocumentoLegenda gerado, boolean ehSrt,
            boolean substituicaoPedida, List<String> avisos, Path base, Path baseLegada) {
        if (!Files.exists(destino)) {
            escreverLegenda(destino, gerado, ehSrt);
            registrarBase(base, gerado, ehSrt, destino);
            return ResultadoPublicacao.NADA;
        }
        // MIGRAÇÃO: antes de 28/10/2026 a base era só pelo nome do arquivo. Ela vale UMA vez, até a
        // primeira publicação gravar a base desta pasta de saída.
        Path baseParaMesclar = base != null && Files.isRegularFile(base) ? base : baseLegada;
        Mescla mescla = substituicaoPedida ? null : mesclar(destino, gerado, ehSrt, baseParaMesclar);
        DocumentoLegenda aPublicar = mescla != null ? mescla.documento() : gerado;
        Path backup = publicarPreservandoAnterior(destino, aPublicar, ehSrt, substituicaoPedida, avisos,
            mescla != null);
        registrarBase(base, gerado, ehSrt, destino);
        return new ResultadoPublicacao(backup, mescla != null ? mescla.preservadas() : Set.of());
    }

    private Mescla mesclar(Path destino, DocumentoLegenda gerado, boolean ehSrt, Path base) {
        if (base == null || !Files.isRegularFile(base)) {
            uiLogger.log("[ MESCLA ] " + destino.getFileName() + ": sem registro da publicação anterior"
                + " — não dá para separar correção feita no arquivo de correção feita no cache.");
            return null;
        }
        DocumentoLegenda noArquivo;
        DocumentoLegenda publicadoAntes;
        try {
            noArquivo = ehSrt ? leitorSrt.ler(destino) : leitor.ler(destino);
            publicadoAntes = ehSrt ? leitorSrt.ler(base) : leitor.ler(base);
        } catch (RuntimeException naoDeu) {
            uiLogger.log("[ MESCLA ] " + destino.getFileName() + ": não consegui ler o arquivo ou a base ("
                + naoDeu.getMessage() + ") — mesclagem NÃO feita.");
            return null;
        }
        List<EventoLegenda> d = noArquivo.eventos();
        List<EventoLegenda> b = publicadoAntes.eventos();
        List<EventoLegenda> n = gerado.eventos();
        if (d.size() != b.size() || b.size() != n.size()) {
            uiLogger.log("[ MESCLA ] " + destino.getFileName() + ": o arquivo tem " + d.size()
                + " linha(s) de evento e a publicação anterior tinha " + b.size()
                + " — estrutura mudou, mesclagem linha a linha NÃO é segura.");
            return null;
        }
        List<EventoLegenda> saida = new ArrayList<>(n.size());
        Set<Integer> preservadas = new java.util.LinkedHashSet<>();
        int atualizadas = 0;
        int conflitos = 0;
        String exemplo = null;
        for (int i = 0; i < n.size(); i++) {
            String kd = chaveDaLinha(d.get(i));
            String kb = chaveDaLinha(b.get(i));
            String kn = chaveDaLinha(n.get(i));
            if (kd.equals(kb)) {
                saida.add(n.get(i));
                if (!kn.equals(kb)) {
                    atualizadas++;
                }
            } else if (kn.equals(kb) || kn.equals(kd)) {
                saida.add(d.get(i));
                if (!kn.equals(kd)) {
                    preservadas.add(d.get(i).indice());
                }
            } else {
                saida.add(d.get(i));
                preservadas.add(d.get(i).indice());
                conflitos++;
                if (exemplo == null && d.get(i).temTexto() && n.get(i).temTexto()) {
                    exemplo = "no arquivo: \"" + recortar(d.get(i).texto())
                        + "\" | o cache gera agora: \"" + recortar(n.get(i).texto()) + "\"";
                }
            }
        }
        // O CARIMBO NÃO ENTRA NA COMPARAÇÃO (auditoria de 28/10/2026). Ele muda a cada execução, e
        // comparado junto fazia o cabeçalho editado no arquivo (1.3 trocando fonte) valer INTEIRO,
        // com o carimbo antigo — "pendentes: 5" para sempre, depois de a 2.3 resolver as cinco.
        // É a classe do "carimbo mentiroso" de 09/09: o artefato tem de declarar o que ESTA
        // execução fez (decisão de 07/08).
        String hn = gerado.cabecalho();
        String hd = CarimboCabecalhoLegenda.remover(noArquivo.cabecalho());
        boolean cabecalhoPreservado = !hd.equals(CarimboCabecalhoLegenda.remover(publicadoAntes.cabecalho()))
            && !hd.equals(CarimboCabecalhoLegenda.remover(hn));
        String cabecalho = cabecalhoPreservado ? CarimboCabecalhoLegenda.aplicar(hd, linhasDoCarimbo(hn)) : hn;

        if (!preservadas.isEmpty() || atualizadas > 0 || cabecalhoPreservado) {
            String resumo = destino.getFileName() + ": " + preservadas.size()
                + " fala(s) corrigida(s) direto no arquivo mantida(s) (o cache não as conhece), "
                + atualizadas + " atualizada(s) pelo cache"
                + (conflitos > 0 ? ", " + conflitos + " CONFLITO(s) — mudou no arquivo E no cache;"
                    + " mantida a do arquivo" + (exemplo != null ? ". Ex.: " + exemplo : "") : "")
                + (cabecalhoPreservado ? "; cabeçalho editado no arquivo (estilos/fonte) mantido" : "")
                + ".";
            log.info("[ MESCLA ] {}", resumo);
            uiLogger.log("[ MESCLA ] " + resumo);
        }
        return new Mescla(new DocumentoLegenda(cabecalho, saida, gerado.quebraDeLinha(), gerado.comBom()),
            preservadas, atualizadas, conflitos, cabecalhoPreservado, exemplo);
    }

    private static String chaveDaLinha(EventoLegenda evento) {
        return evento.prefixo() + '\u0000' + (evento.texto() == null ? "" : evento.texto());
    }

    private static String recortar(String texto) {
        return texto.length() > 60 ? texto.substring(0, 60) + "…" : texto;
    }

    /**
     * PROPÓSITO DE NEGÓCIO: endereço da base da mescla de três vias PARA ESTA PASTA DE SAÍDA
     * (INV-MESCLA-001). A base era só {@code .publicado/<nome>}: duas pastas de saída para a
     * mesma entrada (a padrão e uma escolhida na tela, ou o reuso entre modelos) dividiam a base, e
     * a correção da 2.3 publicada numa virava "correção feita no arquivo" na outra — nunca chegava
     * lá, e o console dizia "mantida" (auditoria de 28/10/2026).
     *
     * <p>INVARIANTES DO DOMÍNIO: a pasta vira um identificador estável (SHA-256 do caminho absoluto
     * normalizado, em minúsculas porque o Windows não distingue caixa); o caminho legível fica em
     * {@code ORIGEM.txt} ao lado, para quem abrir a pasta.
     *
     * <p>COMPORTAMENTO EM CASO DE FALHA: não lança; sem pasta-mãe, usa a raiz {@code .publicado}.
     */
    static Path basePorPastaDeSaida(Path arquivoCache, Path arquivoSaida) {
        Path pasta = arquivoSaida.toAbsolutePath().normalize().getParent();
        Path publicado = arquivoCache.getParent().resolve(".publicado");
        if (pasta == null) {
            return publicado.resolve(arquivoSaida.getFileName().toString());
        }
        return publicado.resolve(ProvenienciaCache.hashDe(pasta.toString().toLowerCase(java.util.Locale.ROOT)).substring(0, 12))
            .resolve(arquivoSaida.getFileName().toString());
    }

    /** As linhas do carimbo {@code ; KRONOS} de um cabeçalho, sem o fim de linha. */
    private static List<String> linhasDoCarimbo(String cabecalho) {
        return cabecalho.lines()
            .filter(l -> l.startsWith(CarimboCabecalhoLegenda.PREFIXO))
            .map(String::strip)
            .toList();
    }

    /** O endereço antigo da base, só pelo nome — lido uma vez, como migração. */
    private static Path baseLegadaPorNome(Path arquivoCache, Path arquivoSaida) {
        return arquivoCache.getParent().resolve(".publicado").resolve(arquivoSaida.getFileName().toString());
    }

    private void registrarBase(Path base, DocumentoLegenda gerado, boolean ehSrt, Path destino) {
        if (base == null) {
            return;
        }
        try {
            Files.createDirectories(base.getParent());
            escreverLegenda(base, gerado, ehSrt);
            Path origem = base.getParent().resolve("ORIGEM.txt");
            if (!base.getParent().getFileName().toString().equals(".publicado") && !Files.exists(origem)) {
                Files.writeString(origem, "pasta de saida desta base: "
                    + destino.toAbsolutePath().normalize().getParent() + System.lineSeparator(),
                    java.nio.charset.StandardCharsets.UTF_8);
            }
        } catch (RuntimeException | java.io.IOException naoDeu) {
            log.warn("Registro da publicação {} não gravado: {}", base, naoDeu.getMessage());
            uiLogger.log("[ MESCLA ] registro da publicação NÃO gravado (" + naoDeu.getMessage()
                + ") — a próxima reexecução não conseguirá preservar correções feitas no arquivo.");
        }
    }

    /**
     * PROPÓSITO DE NEGÓCIO: substitui a legenda existente pelo conteúdo já decidido, com backup
     * obrigatório quando ela muda e sem tocar no arquivo quando é idêntica.
     *
     * <p>INVARIANTES DO DOMÍNIO: backup antes de qualquer substituição; troca atômica; conteúdo
     * idêntico não é regravado; sem mesclagem ({@code mesclado == false}) a diferença é avisada com
     * os dois casos possíveis, porque aí o sistema não sabe separar correção do cache de correção
     * feita no arquivo.
     *
     * <p>COMPORTAMENTO EM CASO DE FALHA: falha ao gerar, comparar ou fazer backup lança
     * {@link ArquivoLegendaException} antes de o destino ser tocado; o temporário é sempre apagado.
     *
     * @return o caminho do backup criado, ou {@code null} quando nada precisou ser substituído
     */
    private Path publicarPreservandoAnterior(Path destino, DocumentoLegenda documento, boolean ehSrt,
            boolean substituicaoPedida, List<String> avisos, boolean mesclado) {
        Path gerado;
        try {
            gerado = Files.createTempFile(destino.toAbsolutePath().getParent(),
                destino.getFileName().toString(), ".gerando.tmp");
        } catch (java.io.IOException e) {
            throw new ArquivoLegendaException("Não consegui preparar a nova versão de " + destino, e);
        }
        try {
            escreverLegenda(gerado, documento, ehSrt);
            boolean identico = Files.mismatch(gerado, destino) == -1L;
            if (identico && !substituicaoPedida) {
                uiLogger.log("[ SAIDA ] " + destino.getFileName()
                    + ": conteúdo idêntico ao já publicado — mantido sem regravar.");
                return null;
            }
            Path backup = politicaBackup.criarBackupAntesSobrescrita(destino);
            if (!identico && !substituicaoPedida && !mesclado) {
                // Decodificação TOLERANTE: o arquivo anterior pode ter sido salvo por outro programa
                // com byte inválido, e o aviso nunca pode ser o motivo de a publicação falhar.
                List<String> antes = new String(Files.readAllBytes(destino),
                    java.nio.charset.StandardCharsets.UTF_8).lines().toList();
                List<String> depois = new String(Files.readAllBytes(gerado),
                    java.nio.charset.StandardCharsets.UTF_8).lines().toList();
                int diferentes = Math.abs(antes.size() - depois.size());
                String exemplo = null;
                for (int i = 0; i < Math.min(antes.size(), depois.size()); i++) {
                    if (!antes.get(i).equals(depois.get(i))) {
                        diferentes++;
                        if (exemplo == null && antes.get(i).startsWith("Dialogue:")) {
                            exemplo = "no arquivo: \"" + textoDaLinha(antes.get(i))
                                + "\" → agora: \"" + textoDaLinha(depois.get(i)) + "\"";
                        }
                    }
                }
                // Os DOIS casos legítimos que produzem esta diferença pedem reações opostas, e o
                // aviso tem de dizer os dois: se foi o cache que mudou (Correção de Cache, 2.3), a
                // diferença É a correção chegando; se foi a legenda corrigida direto no .ass, a
                // correção acabou de ser substituída. Sem o registro da publicação anterior, o
                // sistema não sabe qual dos dois aconteceu.
                String aviso = "A legenda já publicada " + destino.getFileName() + " DIFERIA do que o cache"
                    + " gera em " + diferentes + " linha(s) e foi substituída pela versão do cache; a anterior"
                    + " está no backup. Se você corrigiu o cache (Correção de Cache), é a correção chegando."
                    + " Se a legenda tinha sido corrigida direto no .ass (Revisão de Lore, Concordância,"
                    + " edição manual), essa correção está no backup e não no arquivo."
                    + (exemplo != null ? " Exemplo: " + exemplo : "");
                log.warn("{} Backup: {}", aviso, backup);
                uiLogger.log("[ ATENÇÃO ] " + aviso + " Backup: " + backup);
                avisos.add(aviso);
            }
            org.traducao.projeto.core.util.ArquivoAtomicoUtil.substituirAtomico(gerado, destino);
            return backup;
        } catch (java.io.IOException e) {
            throw new ArquivoLegendaException("Falha ao publicar " + destino + " preservando a versão anterior", e);
        } finally {
            try {
                Files.deleteIfExists(gerado);
            } catch (java.io.IOException ignorada) {
                log.warn("Temporário {} não pôde ser apagado", gerado);
            }
        }
    }

    private static String textoDaLinha(String linhaAss) {
        String[] campos = linhaAss.split(",", 10);
        String texto = campos.length == 10 ? campos[9] : linhaAss;
        return texto.length() > 60 ? texto.substring(0, 60) + "…" : texto;
    }

    /**
     * PROPÓSITO DE NEGÓCIO: relê a legenda que acabou de ser GRAVADA e confere que o que está no
     * disco é o que a validação aprovou. É a única verificação deste fluxo que acontece depois de
     * TODAS as transformações — terminologia, normalização de aspas, carimbo de cabeçalho e
     * serialização.
     *
     * <h2>Por que existe (A6 do aditivo, 2026-09-09)</h2>
     * Aprovação intermediária não se transfere para um resultado modificado depois dela. Este
     * método validava a tradução numa etapa e continuava alterando o texto por centenas de linhas
     * antes de escrever — e a auditoria de terceiro nomeou o buraco com todas as letras: os ensaios
     * "não comprovaram o fluxo completo até a gravação do {@code .ass}". A tabela da A6 é explícita:
     * <b>"o arquivo correto foi produzido" exige arquivo GRAVADO E RELIDO</b>, não o retorno de uma
     * função.
     *
     * <h2>Invariantes do domínio</h2>
     * <ul>
     *   <li>Compara o que está no arquivo com o que foi gravado no cache para o MESMO índice. Se
     *       divergirem, alguma etapa depois da validação mexeu no texto sem passar por portão.</li>
     *   <li>Roda o MESMO portão canônico ({@code motivoFalhaFinal}) sobre o texto do disco. Fala
     *       que passou antes e reprova agora é regressão que o próprio pipeline introduziu.</li>
     *   <li><b>Não apaga e não reescreve nada.</b> A8: suspeita não autoriza alteração automática,
     *       e o arquivo já está publicado — desfazer aqui seria destruir a única saída existente.
     *       A ação é DENUNCIAR: log de aviso por fala e uma linha no console do operador.</li>
     *   <li>Falha ao reler é {@code NÃO VERIFICADO} declarado, nunca silêncio: "não consegui
     *       conferir" e "conferi e está certo" não podem produzir a mesma saída (regra 12).</li>
     * </ul>
     *
     * <h2>Comportamento em caso de falha</h2>
     * Não lança e não interrompe: a legenda já está em disco e derrubar o job aqui não a desfaz.
     * Divergência vira aviso no log e linha no console; exceção ao reler vira "não verificado".
     *
     * @param arquivoSaida   o arquivo que acabou de ser escrito
     * @param ehSrt          qual leitor usar
     * @param entradasCache  o que a validação aprovou, por índice de evento
     */
    // COSTURA DE TESTE, e nao acidente de visibilidade: o pacote consegue chamar esta
    // conferencia com um arquivo montado a mao e um cache montado a mao, que e o unico jeito
    // de VER a A6 acusando divergencia sem depender de um escritor defeituoso de verdade.
    void conferirArquivoGravado(Path arquivoSaida, boolean ehSrt, List<EntradaCache> entradasCache) {
        conferirArquivoGravado(arquivoSaida, ehSrt, entradasCache, Set.of());
    }

    /**
     * PROPÓSITO DE NEGÓCIO: a mesma conferência pós-gravação, sabendo quais falas foram MANTIDAS
     * porque a fonte já estava no idioma-alvo — as únicas que entram no arquivo sem terem passado
     * nem pelo LLM nem pelo portão canônico.
     *
     * <h2>Invariantes do domínio</h2>
     * <ul>
     *   <li>O esperado no disco é o validado DEPOIS da regra de saída do itálico: o cache guarda o
     *       que foi validado e o arquivo recebe {@link RemovedorItalico#remover}. Comparar os dois
     *       sem a mesma regra fabricava divergência em toda fala itálica — medido em 25/09/2026, 4
     *       de 4 alarmes do 0080 eram isso. A regra é CONSULTADA, não reimplementada.</li>
     *   <li>Fala mantida por fonte já no alvo é declarada NÃO VERIFICADA, com exemplos, e não
     *       reprovada: o portão canônico só sabe dizer que ela é idêntica ao original, o que é
     *       verdade por construção e não prova nada sobre o idioma. Reprová-la com "o modelo
     *       devolveu o texto original" era falso duas vezes — não houve modelo nem devolução.
     *       Calá-la seria pior: foi a A6 que mostrou "Uma, wait!" saindo em inglês.</li>
     * </ul>
     *
     * <p>COMPORTAMENTO EM CASO DE FALHA: igual à sobrecarga de três argumentos.
     *
     * @param mantidasPorFonteNoAlvo originais que o detector de idioma da fonte deu por já
     *        traduzidos e que por isso não foram ao LLM
     */
    void conferirArquivoGravado(Path arquivoSaida, boolean ehSrt, List<EntradaCache> entradasCache,
            Set<String> mantidasPorFonteNoAlvo) {
        conferirArquivoGravado(arquivoSaida, ehSrt, entradasCache, mantidasPorFonteNoAlvo, Set.of());
    }

    /**
     * PROPÓSITO DE NEGÓCIO: a mesma conferência, sabendo quais falas a mesclagem de três vias
     * MANTEVE do arquivo (corrigidas nele por outra tela ou à mão). Elas diferem do validado por
     * decisão, não por defeito: são declaradas no placar e não viram divergência.
     *
     * <p>COMPORTAMENTO EM CASO DE FALHA: igual à sobrecarga de quatro argumentos.
     */
    void conferirArquivoGravado(Path arquivoSaida, boolean ehSrt, List<EntradaCache> entradasCache,
            Set<String> mantidasPorFonteNoAlvo, Set<Integer> preservadasDoArquivo) {
        if (entradasCache.isEmpty()) {
            uiLogger.log("   [ A6 ] nada a conferir no arquivo gravado: nenhuma fala traduzida nesta execucao");
            return;
        }
        DocumentoLegenda relido;
        try {
            relido = ehSrt ? leitorSrt.ler(arquivoSaida) : leitor.ler(arquivoSaida);
        } catch (RuntimeException naoDeu) {
            uiLogger.log("   [ A6 ] NAO VERIFICADO: nao consegui reler " + arquivoSaida.getFileName()
                + " para conferir a gravacao (" + naoDeu.getMessage() + ")");
            log.warn("A6: releitura de {} falhou; a conferencia pos-gravacao NAO rodou", arquivoSaida, naoDeu);
            return;
        }

        Map<Integer, String> noDisco = new HashMap<>();
        for (EventoLegenda evento : relido.eventos()) {
            noDisco.put(evento.indice(), evento.texto());
        }

        int divergentes = 0;
        int reprovadosAgora = 0;
        int pendentesPreservadas = 0;
        List<String> naoVerificadasFonteNoAlvo = new ArrayList<>();
        int preservadasDaEdicao = 0;
        for (EntradaCache entrada : entradasCache) {
            if (preservadasDoArquivo.contains(entrada.indice())) {
                preservadasDaEdicao++;
                continue;
            }
            String gravado = noDisco.get(entrada.indice());
            if (gravado == null) {
                divergentes++;
                log.warn("A6: fala {} foi traduzida e NAO esta no arquivo gravado", entrada.indice());
                continue;
            }
            if (mantidasPorFonteNoAlvo.contains(entrada.original())
                    && gravado.equals(removedorItalico.remover(entrada.original()))) {
                naoVerificadasFonteNoAlvo.add(gravado);
                continue;
            }
            // PENDENTE NAO E DIVERGENCIA, e confundir os dois faz esta guarda gritar em todo
            // arquivo parcial — o alarme falso que a regra 23 diz ser pior que guarda nenhuma.
            //
            // MEDIDO na primeira corrida em producao (DanMachi S01E01, 2026-09-09): as 14
            // "divergencias" que esta guarda acusou eram as 14 PENDENCIAS. Para elas o cache
            // grava traduzido VAZIO e o arquivo preserva o ORIGINAL — dois estados legitimos que
            // nao batem entre si por desenho.
            //
            // FICA DECLARADO O QUE ISTO REVELOU, e nao esta consertado aqui: gravar vazio no
            // cache PERDE a informacao de que a fala esta pendente e por que (A7 do aditivo —
            // causa e autoria preservadas). O certo seria o cache registrar a pendencia com o
            // motivo, em vez de uma traducao que nao existe.
            boolean pendenteComOriginalPreservado =
                (entrada.traduzido() == null || entrada.traduzido().isBlank())
                    && gravado.equals(removedorItalico.remover(entrada.original()));
            if (pendenteComOriginalPreservado) {
                pendentesPreservadas++;
                continue;
            }
            if (!gravado.equals(removedorItalico.remover(entrada.traduzido()))) {
                divergentes++;
                log.warn("A6: o disco divergiu do que foi validado na fala {}.\n  validado: \"{}\"\n  no disco: \"{}\"",
                    entrada.indice(), entrada.traduzido(), gravado);
                continue;
            }
            String motivo = avaliadorCache.motivoFalhaFinal(entrada.original(), gravado);
            if (motivo != null) {
                reprovadosAgora++;
                log.warn("A6: fala {} PASSOU antes das transformacoes e REPROVA no arquivo gravado: {}\n  original: \"{}\"\n  gravado : \"{}\"",
                    entrada.indice(), motivo, entrada.original(), gravado);
            }
        }

        // O placar sai SEMPRE, inclusive em zero (regra 12): "conferi e esta limpo" e "a
        // conferencia nao rodou" nao podem ter a mesma cara no console.
        // O placar sai SEMPRE e mostra os TRES estados, inclusive em zero (regra 12 e A4):
        // conferidas, pendentes-com-original-preservado, e divergencia de verdade.
        String pendentes = pendentesPreservadas > 0
            ? ", " + pendentesPreservadas + " pendente(s) com o original preservado (estado esperado)"
            : "";
        if (preservadasDaEdicao > 0) {
            pendentes += ", " + preservadasDaEdicao + " mantida(s) da correção feita no arquivo (mesclagem)";
        }
        if (!naoVerificadasFonteNoAlvo.isEmpty()) {
            pendentes += ", " + naoVerificadasFonteNoAlvo.size()
                + " mantida(s) por fonte ja no idioma-alvo, NAO VERIFICADA(s) — nao passaram pelo LLM"
                + " nem pela validacao; confira se sao mesmo portugues: "
                + naoVerificadasFonteNoAlvo.stream().limit(3)
                    .map(t -> "\"" + (t.length() > 60 ? t.substring(0, 60) + "…" : t) + "\"")
                    .collect(java.util.stream.Collectors.joining(", "));
        }
        if (divergentes == 0 && reprovadosAgora == 0) {
            uiLogger.log("   [ A6 ] arquivo gravado conferido: " + entradasCache.size()
                + " fala(s) relida(s) do disco, nenhuma divergencia" + pendentes);
            return;
        }
        uiLogger.log("   [ A6 ] ATENCAO no arquivo gravado: " + divergentes
            + " divergencia(s) entre disco e validado, " + reprovadosAgora
            + " fala(s) que passaram antes e reprovam agora (de " + entradasCache.size()
            + " relidas" + pendentes + "). Detalhe no log; o arquivo NAO foi alterado.");
    }

}

