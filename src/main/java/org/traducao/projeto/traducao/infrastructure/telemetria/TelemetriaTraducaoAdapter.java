package org.traducao.projeto.traducao.infrastructure.telemetria;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.traducao.projeto.core.io.DiretorioBaseKronos;
import org.traducao.projeto.core.util.ArquivoAtomicoUtil;
import org.traducao.projeto.traducao.domain.FalaNaoTraduzida;
import org.traducao.projeto.traducao.domain.NormalizadorNomeEpisodio;
import org.traducao.projeto.traducao.domain.StatusArquivoTraducao;
import org.traducao.projeto.traducao.domain.TelemetriaTraducao;
import org.traducao.projeto.traducao.domain.TelemetriaTraducaoDocumento;
import org.traducao.projeto.traducao.domain.ports.TelemetriaTraducaoPort;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * PROPÓSITO DE NEGÓCIO: única escritora do arquivo canônico próprio da telemetria
 * da Tradução Local ({@code logs/telemetria_traducao.json}). Projeta, por episódio,
 * o estado final consolidado das traduções e mantém os quatro contadores da fatia,
 * isolando a Tradução Local do módulo de telemetria (o painel apenas lê este arquivo).
 *
 * <h2>Invariantes do domínio</h2>
 * <ul>
 *   <li>Deduplicação por nome de episódio normalizado ({@link NormalizadorNomeEpisodio});
 *       o registro mais recente substitui o anterior — nunca append-only.</li>
 *   <li>Os quatro contadores iniciam em zero e acumulam SOMENTE eventos da Tradução
 *       Local após a adoção deste arquivo; jamais copiam valores do legado.</li>
 *   <li>Cada mutação persiste o documento inteiro (registros + contadores) como uma
 *       ÚNICA alteração lógica, via escrita atômica (temporário no mesmo diretório +
 *       movimentação segura).</li>
 *   <li>Sincronização de escopo JVM: as mutações são {@code synchronized}. Não há
 *       coordenação entre processos — assume-se uma única instância escrevendo o arquivo.</li>
 * </ul>
 *
 * <h2>Comportamento em caso de falha</h2>
 * Um arquivo existente ilegível NÃO é destruído silenciosamente: é preservado com sufixo
 * único {@code .corrompido_<timestamp>_<seq>} (sem sobrescrever evidência anterior) antes de
 * recomeçar o estado. O carregamento tolera elemento {@code null} no array de registros e
 * captura também erros de runtime, para nunca reprovar o {@code @PostConstruct} e impedir o
 * boot. Falha de I/O ao persistir é registrada, mantendo o estado em memória coerente.
 */
@Component
public class TelemetriaTraducaoAdapter implements TelemetriaTraducaoPort {

    private static final Logger log = LoggerFactory.getLogger(TelemetriaTraducaoAdapter.class);

    // 1.1: acrescenta pendenciasPorCausa (KPI estruturado) a cada registro; aditivo e
    // retrocompatível — arquivos 1.0 sao lidos com o campo ausente como null.
    // 1.2 (22/08/2026): entraram falasItalicoRemovido e falasItalicoPreservado. Sao Integer,
    // entao registro gravado sob 1.1 le como null — que e a resposta HONESTA para ele: os
    // episodios daquela epoca nao foram medidos, e nao "foram medidos e deram zero".
    private static final String SCHEMA_VERSION = "1.2";
    private static final String NOME_ARQUIVO = "telemetria_traducao.json";
    /** Histórico append-only: uma linha JSON por EXECUÇÃO (o canônico guarda só a última). */
    static final String NOME_ARQUIVO_HISTORICO = "telemetria_execucoes.jsonl";
    /** Teto LOCAL de execuções guardadas. O acervo completo vive no repositório do dataset. */
    private static final int LIMITE_LOCAL_EXECUCOES = 20_000;
    /** Folga antes de podar, para não reescrever o arquivo a cada episódio traduzido. */
    private static final int FOLGA_PODA = 1_000;
    private static final String SUBPASTA = "logs";
    // Carimbo único (timestamp + sequência JVM) para NÃO sobrescrever evidência forense de
    // corrupções sucessivas; a sequência garante unicidade mesmo dentro do mesmo milissegundo.
    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss_SSS");
    private static final AtomicInteger SEQ_CORROMPIDO = new AtomicInteger(0);

    private final ObjectMapper objectMapper;
    private final Map<String, TelemetriaTraducao> banco = new LinkedHashMap<>();
    private final AtomicInteger alucinacoesPrevenidas = new AtomicInteger(0);
    private final AtomicInteger respostasTraducaoRejeitadas = new AtomicInteger(0);
    private final AtomicInteger falhasTraducaoRecuperadas = new AtomicInteger(0);
    private final AtomicInteger fallbacksTraducaoMantidos = new AtomicInteger(0);

    public TelemetriaTraducaoAdapter(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @PostConstruct
    public synchronized void carregar() {
        Path arquivo = arquivo();
        if (!Files.exists(arquivo)) {
            return;
        }
        try {
            TelemetriaTraducaoDocumento doc = objectMapper.readValue(arquivo.toFile(), TelemetriaTraducaoDocumento.class);
            if (doc == null) {
                return;
            }
            if (doc.registros() != null) {
                for (TelemetriaTraducao t : doc.registros()) {
                    if (t == null) {
                        continue; // elemento null no array (edição manual / escrita parcial): ignora
                    }
                    banco.put(NormalizadorNomeEpisodio.normalizar(t.nomeEpisodio()), t);
                }
            }
            alucinacoesPrevenidas.set(doc.alucinacoesPrevenidas());
            respostasTraducaoRejeitadas.set(doc.respostasTraducaoRejeitadas());
            falhasTraducaoRecuperadas.set(doc.falhasTraducaoRecuperadas());
            fallbacksTraducaoMantidos.set(doc.fallbacksTraducaoMantidos());
            log.info("Telemetria da Traducao Local carregada: {} episodio(s) de {}.", banco.size(), arquivo);
        } catch (IOException | RuntimeException e) {
            // Qualquer falha de leitura/processamento (I/O, JSON, dado inesperado) preserva o
            // arquivo em vez de reprovar o @PostConstruct e impedir o boot da aplicação.
            preservarCorrompido(arquivo, e);
        }
    }

    /**
     * PROPÓSITO DE NEGÓCIO: registra a medição de um episódio no arquivo canônico (a FOTO
     * atual) e no histórico (append puro).
     *
     * <h2>Por que um BLOQUEIO não sobrescreve trabalho real</h2>
     * A FOTO é chaveada pelo nome do episódio, então retraduzir apaga a medição anterior — e
     * isso é DESEJADO: a foto mostra o estado de agora. Mas uma execução {@code BLOQUEADO} não
     * é retradução; é um NÃO-EVENTO. O portão de obra×contexto recusa o arquivo antes de ler a
     * legenda, chamar o LLM ou tocar o cache, e o registro sai com {@code falasTraduzidas = 0},
     * {@code totalLinhas = 0} e tempo desprezível. Deixá-lo vencer zera no painel um episódio
     * que está traduzido em disco.
     *
     * <p>Medido em 2026-08-03 sobre {@code telemetria_execucoes.jsonl} (754 execuções): das 237
     * bloqueadas, <b>48 eram o último registro do seu episódio</b> — 47 do Gundam ZZ e 1 do
     * Char's Counterattack. O painel exibia zero para os 48, apagando <b>17.813 falas
     * traduzidas</b> de trabalho que existe no disco.
     *
     * <p>INVARIANTES DO DOMÍNIO: trabalho real (qualquer status que não {@code BLOQUEADO})
     * SEMPRE vence bloqueio para a mesma chave. Entre dois registros da mesma natureza, o mais
     * recente vence, como antes. O HISTÓRICO continua recebendo TODAS as execuções, inclusive as
     * bloqueadas — é ele que responde "quantas vezes esta obra foi barrada", e essa pergunta
     * continua respondível.
     *
     * <p>COMPORTAMENTO EM CASO DE FALHA: telemetria nula é ignorada sem lançar.
     */
    @Override
    public synchronized void registrarTraducao(TelemetriaTraducao telemetria) {
        if (telemetria == null) {
            return;
        }
        String chave = NormalizadorNomeEpisodio.normalizar(telemetria.nomeEpisodio());
        // Grava quando o registro NOVO representa trabalho, ou quando não há trabalho anterior
        // a proteger (chave ausente ou já sem trabalho). Só o caso "registro sem trabalho por cima
        // de trabalho" é recusado — bloqueio, e desde 28/10/2026 também a FALHA zerada: o arquivo
        // abortado sem gerar saída deixa o .ass anterior intacto, e a foto trocava um episódio
        // CONCLUIDO por FALHOU com zeros (ZZ S01E16 com 382 falas, Stink Bomb com 402, medidos).
        if (!semTrabalho(telemetria) || semTrabalho(banco.get(chave))) {
            banco.put(chave, telemetria);
            persistir();
        }
        // O histórico registra TODA execução, inclusive a bloqueada: a foto responde "como está
        // agora", o histórico responde "o que aconteceu". São perguntas diferentes.
        acrescentarAoHistorico(telemetria);
    }

    /**
     * PROPÓSITO DE NEGÓCIO: um registro representa trabalho de tradução ou apenas a recusa do
     * portão de obra×contexto?
     *
     * <p>INVARIANTES DO DOMÍNIO: {@code null} conta como bloqueado — assim a primeira medição de
     * um episódio, bloqueada ou não, sempre entra (não há trabalho anterior para proteger).
     *
     * <p>COMPORTAMENTO EM CASO DE FALHA: status nulo/desconhecido NÃO é tratado como bloqueio;
     * na dúvida, o registro é considerado trabalho e preservado.
     */
    private static boolean ehBloqueado(TelemetriaTraducao t) {
        return t == null || StatusArquivoTraducao.BLOQUEADO.name().equals(t.statusFinal());
    }

    /**
     * PROPÓSITO DE NEGÓCIO: o registro não traz trabalho que a foto precise mostrar — bloqueio do
     * portão, ou falha que abortou o arquivo antes de qualquer linha (zero linhas e zero falas).
     *
     * <p>INVARIANTES DO DOMÍNIO: falha COM trabalho (alguma linha ou fala) continua sendo trabalho
     * e vence; só a falha zerada se iguala ao bloqueio.
     *
     * <p>COMPORTAMENTO EM CASO DE FALHA: {@code null} conta como sem trabalho, como em
     * {@link #ehBloqueado}.
     */
    private static boolean semTrabalho(TelemetriaTraducao t) {
        return ehBloqueado(t)
            || (StatusArquivoTraducao.FALHOU.name().equals(t.statusFinal())
                && t.totalLinhas() == 0 && t.falasTraduzidas() == 0);
    }

    /**
     * PROPÓSITO DE NEGÓCIO: preserva CADA execução de tradução como uma linha própria, para que o
     * dataset de pesquisa possa comparar o mesmo episódio ao longo do tempo ("esta mudança
     * melhorou?"). O arquivo canônico {@code telemetria_traducao.json} é, por construção, uma
     * FOTO: {@code banco} é um mapa chaveado pelo nome do episódio, então retraduzir apaga a
     * medição anterior. Em 2026-07-23 isso foi confirmado nos dados reais — 155 registros para
     * 155 episódios distintos, zero repetições, e as medições do 08th de 2026-07-22 já haviam
     * sumido ao serem retraduzidas.
     *
     * <h2>Invariantes do domínio</h2>
     * <ul>
     *   <li>APPEND puro: uma linha JSON por execução, jamais reescrita ou editada.</li>
     *   <li>Teto LOCAL de {@value #LIMITE_LOCAL_EXECUCOES} linhas — a máquina do operador não
     *       pode crescer sem fim. Ao estourar, as MAIS ANTIGAS saem daqui; elas continuam no
     *       repositório do dataset, que só cresce.</li>
     *   <li>A poda roda com folga ({@value #FOLGA_PODA} linhas além do teto) para não reescrever
     *       o arquivo a cada episódio.</li>
     * </ul>
     *
     * <h2>Comportamento em caso de falha</h2>
     * NUNCA propaga: telemetria histórica é observabilidade, não pode derrubar uma tradução que
     * já terminou. Falha de I/O é registrada em WARN e a execução segue.
     */
    private void acrescentarAoHistorico(TelemetriaTraducao telemetria) {
        try {
            Path pasta = pasta();
            Files.createDirectories(pasta);
            Path historico = pasta.resolve(NOME_ARQUIVO_HISTORICO);
            String linha = objectMapper.writeValueAsString(telemetria);
            Files.writeString(historico, linha + System.lineSeparator(),
                StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            podarHistoricoSeNecessario(historico);
        } catch (IOException | RuntimeException e) {
            log.warn("Falha ao acrescentar execucao ao historico de telemetria: {}", e.getMessage());
        }
    }

    /**
     * PROPÓSITO DE NEGÓCIO: mantém o histórico local dentro de um teto sem jamais perder dado já
     * publicado — o corte local é de ARMAZENAMENTO, não de acervo.
     *
     * <p>INVARIANTES DO DOMÍNIO: descarta apenas as linhas mais ANTIGAS e preserva a ordem
     * cronológica de escrita; a reescrita é atômica (temporário + substituição), então uma queda
     * no meio da poda não trunca o histórico.
     *
     * <p>COMPORTAMENTO EM CASO DE FALHA: propaga {@link IOException} para o chamador, que apenas
     * loga — o arquivo anterior permanece íntegro porque a troca só ocorre no fim.
     */
    private void podarHistoricoSeNecessario(Path historico) throws IOException {
        List<String> linhas = Files.readAllLines(historico, StandardCharsets.UTF_8);
        if (linhas.size() <= LIMITE_LOCAL_EXECUCOES + FOLGA_PODA) {
            return;
        }
        List<String> mantidas = linhas.subList(linhas.size() - LIMITE_LOCAL_EXECUCOES, linhas.size());
        Path temporario = historico.resolveSibling(NOME_ARQUIVO_HISTORICO + ".tmp");
        Files.write(temporario, mantidas, StandardCharsets.UTF_8);
        ArquivoAtomicoUtil.substituirAtomico(temporario, historico);
        log.info("Historico local de telemetria podado para as {} execucoes mais recentes "
            + "(as anteriores permanecem no repositorio do dataset).", LIMITE_LOCAL_EXECUCOES);
    }

    @Override
    public synchronized void registrarAlucinacaoPrevenida() {
        alucinacoesPrevenidas.incrementAndGet();
        persistir();
    }

    @Override
    public synchronized void registrarRespostaTraducaoRejeitada() {
        respostasTraducaoRejeitadas.incrementAndGet();
        persistir();
    }

    @Override
    public synchronized void registrarFalhaTraducaoRecuperada() {
        falhasTraducaoRecuperadas.incrementAndGet();
        persistir();
    }

    @Override
    public synchronized void registrarFallbackMantido() {
        fallbacksTraducaoMantidos.incrementAndGet();
        persistir();
    }

    private void persistir() {
        try {
            Path pasta = pasta();
            Files.createDirectories(pasta);
            Path arquivo = pasta.resolve(NOME_ARQUIVO);
            TelemetriaTraducaoDocumento doc = new TelemetriaTraducaoDocumento(
                SCHEMA_VERSION,
                new ArrayList<>(banco.values()),
                alucinacoesPrevenidas.get(),
                respostasTraducaoRejeitadas.get(),
                falhasTraducaoRecuperadas.get(),
                fallbacksTraducaoMantidos.get());
            Path temporario = pasta.resolve(NOME_ARQUIVO + ".tmp");
            objectMapper.writeValue(temporario.toFile(), doc);
            ArquivoAtomicoUtil.substituirAtomico(temporario, arquivo);
        } catch (IOException e) {
            log.error("Falha ao persistir a telemetria da Traducao Local: {}", e.getMessage(), e);
        }
    }

    /**
     * PROPÓSITO DE NEGÓCIO: preserva o arquivo de telemetria ilegível como evidência forense
     * antes de recomeçar o estado — sem apagar nem sobrescrever a evidência de corrupções
     * anteriores, para que cada falha possa ser investigada.
     *
     * <p>INVARIANTES DO DOMÍNIO: o nome preservado é único por corrupção (carimbo de tempo +
     * sequência JVM); NUNCA sobrescreve um {@code .corrompido_*} já existente.
     *
     * <p>COMPORTAMENTO EM CASO DE FALHA: se nem mover for possível, o original é mantido
     * intacto (não sobrescrito) e o estado inicia vazio; ambas as causas são logadas.
     */
    private void preservarCorrompido(Path arquivo, Exception causa) {
        String carimbo = LocalDateTime.now().format(TS) + "_" + SEQ_CORROMPIDO.incrementAndGet();
        Path corrompido = arquivo.resolveSibling(NOME_ARQUIVO + ".corrompido_" + carimbo);
        try {
            Files.move(arquivo, corrompido);
            log.error("Telemetria da Traducao Local ilegivel; preservada como {} e reiniciado estado vazio. Causa: {}",
                corrompido, causa.getMessage());
        } catch (IOException e) {
            log.error("Telemetria da Traducao Local ilegivel e NAO foi possivel preserva-la ({}). "
                + "Estado iniciado vazio; o arquivo original NAO sera sobrescrito ate nova escrita bem-sucedida. Causa: {}",
                e.getMessage(), causa.getMessage());
        }
    }

    /**
     * PROPÓSITO DE NEGÓCIO: grava o dataset das falas que saíram iguais à origem, uma linha
     * JSON por fala, para que "por que esta fala está em inglês?" tenha resposta no disco.
     *
     * <p>INVARIANTES DO DOMÍNIO: um arquivo por episódio, sobrescrito a cada execução — a
     * última é a que vale, como o próprio {@code .ass}. O nome espelha o do episódio para que
     * casar dataset e legenda seja olhar dois arquivos de mesmo nome. Escrita ATÔMICA: um
     * dataset truncado por queda no meio mentiria sobre o que o pipeline fez.
     *
     * <p>Lista VAZIA é gravada assim mesmo. É informação: significa "conferi e nenhuma fala
     * ficou para trás", que é diferente de "não conferi". Arquivo ausente e arquivo vazio não
     * podem significar a mesma coisa.
     *
     * <p>COMPORTAMENTO EM CASO DE FALHA: erro de I/O é logado e NÃO propaga — perder o dataset
     * jamais pode custar a legenda que acabou de ser traduzida.
     */
    @Override
    public void registrarFalasNaoTraduzidas(Path arquivoTraduzido, String obra,
                                            List<FalaNaoTraduzida> falas) {
        if (arquivoTraduzido == null || falas == null) {
            return;
        }
        try {
            // A EXTENSAO sai primeiro: o nome do parcial e "Ep_PT-BR.parcial.ass", entao tirar
            // ".parcial$" antes de ".ass$" nunca casa e o parcial ganharia um dataset proprio —
            // a auditoria de um arquivo parcial nao encontraria o registro dele.
            String base = arquivoTraduzido.getFileName().toString()
                .replaceAll("\\.(ass|srt)$", "")
                .replaceAll("\\.parcial$", "");
            Path destino = pasta()
                .resolve("falas-nao-traduzidas")
                .resolve(obra == null || obra.isBlank() ? "Desconhecido" : obra)
                .resolve(base + ".jsonl");
            Files.createDirectories(destino.getParent());

            StringBuilder conteudo = new StringBuilder();
            for (FalaNaoTraduzida fala : falas) {
                conteudo.append(objectMapper.writeValueAsString(fala)).append('\n');
            }

            Path temporario = destino.resolveSibling(destino.getFileName() + ".tmp");
            Files.writeString(temporario, conteudo.toString(), StandardCharsets.UTF_8);
            ArquivoAtomicoUtil.substituirAtomico(temporario, destino);
            log.debug("[TELEMETRIA] {} fala(s) nao traduzida(s) registrada(s) em {}",
                falas.size(), destino);
        } catch (IOException | RuntimeException e) {
            log.warn("[TELEMETRIA] falha ao gravar o dataset de falas nao traduzidas de {}: {}",
                arquivoTraduzido, e.toString());
        }
    }

    private static Path pasta() {
        return DiretorioBaseKronos.resolver(SUBPASTA);
    }

    private static Path arquivo() {
        return pasta().resolve(NOME_ARQUIVO);
    }
}
