package org.traducao.projeto.traducao.presentation;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.traducao.projeto.lore.domain.SnapshotContexto;
import org.traducao.projeto.lore.infrastructure.GerenciadorContexto;
import org.traducao.projeto.traducao.application.ProcessarArquivoUseCase;
import org.traducao.projeto.llm.domain.StatusLlm;
import org.traducao.projeto.traducao.domain.ResultadoTraducaoArquivo;
import org.traducao.projeto.traducao.domain.StatusArquivoTraducao;
import org.traducao.projeto.traducao.domain.exceptions.TradutorException;
import org.traducao.projeto.qualidadeTraducao.domain.AlucinacaoDetectadaException;
import org.traducao.projeto.traducao.domain.exceptions.TraducaoParcialException;
import org.traducao.projeto.legenda.domain.ExcecaoLegenda;
import org.traducao.projeto.llm.domain.LlmPort;
import org.traducao.projeto.traducao.infrastructure.config.TradutorProperties;
import org.traducao.projeto.core.presentation.ui.ConsoleEntrada;
import org.traducao.projeto.traducao.presentation.ui.ConsoleUILogger;
import org.traducao.projeto.traducao.presentation.ui.PastasExecucao;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Entrada de linha de comando da Tradução Local (Opção 4). Varre a pasta de
 * entrada por arquivos {@code .ass}/{@code .ssa}/{@code .srt} e traduz cada um sequencialmente.
 *
 * <h2>Propósito de negócio</h2>
 * Representa a interface CLI da Tradução Local: recebe e valida a configuração
 * necessária para iniciar o processamento e coordena somente a apresentação CLI
 * da Opção 4. Não é o bootstrap global da aplicação — apenas orquestra a leitura
 * da pasta de entrada e a delegação de cada arquivo ao caso de uso de tradução.
 *
 * <h2>Invariantes do domínio</h2>
 * <ul>
 *   <li>A pasta de entrada deve estar configurada e válida antes de iniciar.</li>
 *   <li>A execução respeita as validações e a fila já existentes; nenhum valor é
 *       inventado ou assumido silenciosamente.</li>
 *   <li>Não pressupõe a existência de {@code Application.main()}: o bootstrap da
 *       aplicação é implícito e administrado pelo Quarkus/CDI.</li>
 *   <li>Nenhuma configuração da Tradução Local é promovida para um Composition
 *       Root artificial.</li>
 *   <li>Arquivos são processados um por vez de propósito: todos compartilham o
 *       mesmo LLM local (GPU única). Lotes dentro de cada episódio também são
 *       sequenciais (ver {@code ProcessarEpisodioUseCase}).</li>
 *   <li>O contexto/lore do lote é congelado UMA vez, antes do primeiro arquivo, e
 *       passado por parâmetro a cada tradução. O {@code GerenciadorContexto} é um bean
 *       compartilhado com a UI web: sem esse congelamento, uma troca de obra feita por
 *       outra rota mudaria a lore dos arquivos restantes no meio do lote.</li>
 * </ul>
 *
 * <h2>Comportamento em caso de falha</h2>
 * <ul>
 *   <li>Configuração ausente ou inválida impede o início seguro do processamento
 *       e é sinalizada via {@link ConsoleEntrada} (os caminhos podem ser pedidos
 *       ao operador quando {@code tradutor.diretorio-entrada} não é informado).</li>
 *   <li>Falhas de LLM indisponível, pasta inexistente ou I/O interrompem o fluxo
 *       de forma controlada, sem alterar exceções nem códigos de retorno atuais.</li>
 *   <li>Falha em um arquivo individual ({@code TraducaoParcialException},
 *       {@code TradutorException} ou erro genérico) é registrada pelo mecanismo
 *       já existente e não aborta o processamento dos demais arquivos.</li>
 * </ul>
 */
@Component
@ConditionalOnProperty(name = "app.modo", havingValue = "TRADUZIR", matchIfMissing = true)
public class TradutorCLI {

    private static final Logger log = LoggerFactory.getLogger(TradutorCLI.class);
    /** As mesmas da rota web (TraducaoController): o caso de uso traduz SRT, e so o filtro da CLI o deixava de fora. */
    private static final Set<String> EXTENSOES_SUPORTADAS = Set.of(".ass", ".ssa", ".srt");

    private final ProcessarArquivoUseCase processarArquivoUseCase;
    private final ConsoleUILogger uiLogger;
    private final TradutorProperties propriedades;
    private final PastasExecucao pastasExecucao;
    private final LlmPort llmPort;
    private final GerenciadorContexto gerenciadorContexto;

    /**
     * PROPÓSITO DE NEGÓCIO: injeta os colaboradores da execução CLI, incluindo o catálogo de
     * contextos — a CLI não recebe id de contexto por requisição, então é ela quem congela o
     * contexto ativo UMA vez, no início do lote, para passá-lo a cada arquivo.
     *
     * <p>INVARIANTES DO DOMÍNIO: guarda as referências recebidas; não as substitui nem cria
     * implementação própria.
     *
     * <p>COMPORTAMENTO EM CASO DE FALHA: não valida os argumentos; a injeção CDI garante os beans.
     */
    public TradutorCLI(
        ProcessarArquivoUseCase processarArquivoUseCase,
        ConsoleUILogger uiLogger,
        TradutorProperties propriedades,
        PastasExecucao pastasExecucao,
        LlmPort llmPort,
        GerenciadorContexto gerenciadorContexto
    ) {
        this.processarArquivoUseCase = processarArquivoUseCase;
        this.uiLogger = uiLogger;
        this.propriedades = propriedades;
        this.pastasExecucao = pastasExecucao;
        this.llmPort = llmPort;
        this.gerenciadorContexto = gerenciadorContexto;
    }

    /**
     * PROPÓSITO DE NEGÓCIO: executa a varredura da pasta de entrada e traduz cada
     * legenda {@code .ass}/{@code .ssa}/{@code .srt} sequencialmente. É o ponto de entrada da CLI
     * da Tradução Local, agora disparado pelo bootstrap próprio do slice
     * ({@code TraducaoStartup}) — não mais por um contrato compartilhado da fatia
     * {@code config}.
     *
     * <p>INVARIANTES DO DOMÍNIO: pasta de entrada configurada e válida antes de
     * iniciar; LLM local disponível; processamento estritamente sequencial (GPU única).
     *
     * <p>COMPORTAMENTO EM CASO DE FALHA: configuração ausente/inválida, pasta
     * inexistente ou LLM indisponível interrompem o fluxo de forma controlada
     * (retorno sem processar); falha em um arquivo individual é registrada e não
     * aborta os demais. Desde a E8b, o ramo crítico captura
     * {@code TradutorException | AlucinacaoDetectadaException} — a alucinação passou
     * ao peer {@code qualidadeTraducao} e o multi-catch preserva o mesmo tratamento.
     *
     * @throws Exception em falha não recuperável do fluxo de tradução
     */
    public void executar() throws Exception {
        if (!resolverPastas()) {
            return;
        }

        uiLogger.log("Iniciando Tradutor Local...");

        Path diretorioEntrada = pastasExecucao.diretorioEntrada();
        if (!Files.isDirectory(diretorioEntrada)) {
            log.error("Pasta de entrada não existe ou não é um diretório: {}", diretorioEntrada);
            uiLogger.log("❌ Pasta de entrada não existe ou não é um diretório: " + diretorioEntrada);
            return;
        }

        if (!verificarLlmDisponivel()) {
            return;
        }

        List<Path> arquivos;
        try (Stream<Path> stream = Files.list(diretorioEntrada)) {
            arquivos = stream
                .filter(Files::isRegularFile)
                .filter(this::temExtensaoSuportada)
                .sorted(Comparator.comparing(p -> p.getFileName().toString()))
                .toList();
        } catch (IOException e) {
            log.error("Falha ao listar arquivos em {}", diretorioEntrada, e);
            uiLogger.log("❌ Falha ao listar arquivos em " + diretorioEntrada + ": " + e.getMessage());
            return;
        }

        if (arquivos.isEmpty()) {
            log.warn("Nenhum arquivo .ass/.ssa/.srt encontrado em {}", diretorioEntrada);
            uiLogger.log("Nenhum arquivo .ass/.ssa/.srt encontrado em " + diretorioEntrada);
            return;
        }

        log.info("{} arquivo(s) de legenda encontrado(s) em {}", arquivos.size(), diretorioEntrada);
        uiLogger.log(arquivos.size() + " arquivo(s) encontrado(s). Iniciando tradução...");

        // Fotografia ÚNICA do lote. A CLI não recebe id por requisição, então o contexto do
        // job é o ativo no INSTANTE em que o lote começa — congelado aqui e repassado a cada
        // arquivo. Reconsultar o gerenciador dentro do laço deixaria uma troca de obra vinda
        // de outra rota (a UI web compartilha o mesmo bean) mudar a lore no meio do lote.
        SnapshotContexto contextoDoJob = gerenciadorContexto.snapshotAtivo();
        log.info("Contexto congelado para o lote: {} ({})",
            contextoDoJob.nomeExibicao(), contextoDoJob.id());

        int sucesso = 0;
        int falha = 0;
        List<String> arquivosComFalha = new ArrayList<>();
        for (int i = 0; i < arquivos.size(); i++) {
            Path arquivo = arquivos.get(i);
            uiLogger.tituloEpisodio(arquivo.getFileName().toString(), i + 1, arquivos.size());
            try {
                // O DESFECHO vem no retorno, não só nas exceções: PARCIAL (falas pendentes, saída
                // em .parcial) volta sem lançar. A CLI descartava o resultado e contava o arquivo
                // como "traduzido com sucesso" (auditoria de 28/10/2026); a web já o separava.
                ResultadoTraducaoArquivo resultado = processarArquivoUseCase.processar(arquivo, false, contextoDoJob);
                if (resultado.status() == StatusArquivoTraducao.CONCLUIDO) {
                    uiLogger.log("[ OK ] " + arquivo.getFileName() + " traduzido com sucesso.");
                    sucesso++;
                } else {
                    uiLogger.log("[ PARCIAL ] " + arquivo.getFileName() + ": " + resultado.falasPendentes()
                        + " fala(s) pendente(s); saída em " + resultado.arquivoSaida());
                    falha++;
                    arquivosComFalha.add(arquivo.getFileName() + " (parcial: "
                        + resultado.falasPendentes() + " pendente(s))");
                }
            } catch (TraducaoParcialException e) {
                int salvas = e.getDicionarioParcial() != null ? e.getDicionarioParcial().size() : 0;
                log.warn("Processamento parcial em {}: {} traduções salvas. {}", arquivo.getFileName(), salvas, e.getMessage());
                uiLogger.log("[ PARCIAL ] " + arquivo.getFileName() + " (Salvas: " + salvas + " antes de abortar)");
                falha++;
                arquivosComFalha.add(arquivo.getFileName().toString() + " (parcial: " + salvas + " salvas)");
            } catch (TradutorException | AlucinacaoDetectadaException e) {
                // AlucinacaoDetectadaException migrou para o peer qualidadeTraducao na E8b e
                // deixou de ser TradutorException; o multi-catch preserva exatamente o
                // tratamento crítico que ela recebia por herança antes do reparenting.
                log.error("Falha crítica ao processar {}: {}", arquivo.getFileName(), e.getMessage());
                uiLogger.log("[ FAIL ] Falha em " + arquivo.getFileName() + ": " + e.getMessage());
                falha++;
                arquivosComFalha.add(arquivo.getFileName().toString());
            } catch (ExcecaoLegenda e) {
                // ArquivoLegendaException/EntradaJaTraduzidaException migraram para a raiz
                // ExcecaoLegenda (modulo legenda) e nao sao mais TradutorException; este ramo
                // preserva exatamente o mesmo tratamento do ramo TradutorException acima.
                log.error("Falha crítica ao processar {}: {}", arquivo.getFileName(), e.getMessage());
                uiLogger.log("[ FAIL ] Falha em " + arquivo.getFileName() + ": " + e.getMessage());
                falha++;
                arquivosComFalha.add(arquivo.getFileName().toString());
            } catch (Exception e) {
                String errorMsg = e.getMessage();
                if (e.getCause() instanceof TradutorException te) {
                    errorMsg = te.getMessage();
                }
                log.error("Erro ao processar {}: {}", arquivo.getFileName(), errorMsg);
                uiLogger.log("[ FAIL ] Erro em " + arquivo.getFileName() + " (" + errorMsg + ")");
                falha++;
                arquivosComFalha.add(arquivo.getFileName().toString());
            }
        }

        log.info("Processamento finalizado: {} sucesso(s), {} falha(s) de {} arquivo(s)", sucesso, falha, arquivos.size());
        imprimirRelatorioFinal(arquivos.size(), sucesso, falha, arquivosComFalha);
    }

    private void imprimirRelatorioFinal(int totalArquivos, int sucesso, int falha, List<String> arquivosComFalha) {
        uiLogger.log("");
        uiLogger.log("==================== RELATÓRIO FINAL ====================");
        uiLogger.log(String.format("Concluido: %d sucesso(s), %d falha(s) de %d arquivo(s).", sucesso, falha, totalArquivos));
        uiLogger.log(String.format("Falas traduzidas agora pelo LLM: %d", uiLogger.totalFalasNovas()));
        uiLogger.log(String.format("Falas reaproveitadas do cache: %d", uiLogger.totalFalasCache()));
        uiLogger.log(String.format("Avisos/revisões manuais sinalizadas: %d", uiLogger.totalAvisos()));
        if (!arquivosComFalha.isEmpty()) {
            uiLogger.log("[ WARN ] Arquivos com falha ou tradução parcial: " + String.join(", ", arquivosComFalha));
        }
        uiLogger.log("===========================================================");
    }

    private boolean verificarLlmDisponivel() {
        uiLogger.log("Verificando se o servidor LLM local esta online e com o modelo carregado em memoria...");
        StatusLlm status = llmPort.verificarDisponibilidade();
        if (!status.modeloCarregado()) {
            uiLogger.log("[ FAIL ] " + status.mensagem());
            uiLogger.log("[ FAIL ] Abortando: inicie o servidor LLM local (ex: LM Studio), carregue o modelo "
                + "configurado em tradutor.llm.model e tente novamente.");
            return false;
        }
        uiLogger.log("[ OK ] " + status.mensagem());
        return true;
    }

    private boolean resolverPastas() {
        String entrada = propriedades.diretorioEntrada();
        String saida = propriedades.diretorioSaida();
        String cache = propriedades.diretorioCache();

        if (entrada == null || entrada.isBlank()) {
            log.error("Pasta de entrada nao configurada (informe tradutor.diretorio-entrada por configuracao ou os caminhos no console)");
            ConsoleEntrada.imprimirErroSaida();
            return false;
        }

        pastasExecucao.configurar(entrada, saida, cache, propriedades);
        log.info("Pastas: entrada={}, saída={}, cache={}",
            pastasExecucao.diretorioEntrada(),
            pastasExecucao.diretorioSaida(),
            pastasExecucao.diretorioCache());
        uiLogger.log("Entrada: " + pastasExecucao.diretorioEntrada());
        uiLogger.log("Saída: " + pastasExecucao.diretorioSaida());
        return true;
    }

    private boolean temExtensaoSuportada(Path arquivo) {
        String nome = arquivo.getFileName().toString().toLowerCase();
        return EXTENSOES_SUPORTADAS.stream().anyMatch(nome::endsWith);
    }
}
