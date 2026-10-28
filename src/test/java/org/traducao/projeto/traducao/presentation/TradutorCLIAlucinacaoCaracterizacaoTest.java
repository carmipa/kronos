package org.traducao.projeto.traducao.presentation;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.traducao.projeto.lore.domain.SnapshotContexto;
import org.traducao.projeto.lore.infrastructure.GerenciadorContexto;
import org.traducao.projeto.traducao.application.ProcessarArquivoUseCase;
import org.traducao.projeto.traducao.domain.ResultadoTraducaoArquivo;
import org.traducao.projeto.llm.domain.StatusLlm;
import org.traducao.projeto.qualidadeTraducao.domain.AlucinacaoDetectadaException;
import org.traducao.projeto.llm.domain.LlmPort;
import org.traducao.projeto.traducao.infrastructure.config.TradutorProperties;
import org.traducao.projeto.traducao.presentation.ui.ConsoleUILogger;
import org.traducao.projeto.traducao.presentation.ui.PastasExecucao;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * PROPÓSITO DE NEGÓCIO: caracteriza o tratamento que o {@link TradutorCLI} dá a uma
 * {@code AlucinacaoDetectadaException} lançada ao processar um arquivo — hoje herdado
 * do {@code catch (TradutorException)}. Trava o contrato antes da E8b: mensagem de
 * falha, contagem, lista de arquivos com falha e continuidade do lote.
 *
 * <h2>Invariantes do domínio</h2>
 * <ul>
 *   <li>Uma falha por alucinação num arquivo é contabilizada como falha e não aborta
 *       o processamento dos demais (continuidade do lote).</li>
 *   <li>A mensagem exibida usa o formato do ramo crítico ("[ FAIL ] Falha em X: msg"),
 *       preservando a mensagem original da exceção.</li>
 *   <li>O relatório final reflete 0 sucessos, N falhas e lista os arquivos com falha.</li>
 * </ul>
 *
 * <h2>Comportamento em caso de falha</h2>
 * Após o reparenting da E8b, {@code AlucinacaoDetectadaException} deixa de ser
 * {@code TradutorException}; o multi-catch {@code (TradutorException | AlucinacaoDetectadaException)}
 * preserva EXATAMENTE este comportamento e este teste deve continuar verde.
 */
@DisplayName("E8b caracterização: TradutorCLI trata alucinação como falha crítica e continua o lote")
class TradutorCLIAlucinacaoCaracterizacaoTest {

    /** Captura as linhas enviadas ao console para inspeção do relatório/mensagens. */
    private static final class UiLoggerCaptor extends ConsoleUILogger {
        final List<String> linhas = new java.util.ArrayList<>();
        @Override public void log(String mensagem) { linhas.add(mensagem); }
    }

    /** LLM disponível — só precisa passar pela verificação inicial do CLI. */
    private static final class LlmDisponivel implements LlmPort {
        @Override public org.traducao.projeto.llm.domain.TraducaoLote traduzir(
            org.traducao.projeto.llm.domain.Lote lote) { return null; }
        @Override public StatusLlm verificarDisponibilidade() { return new StatusLlm(true, true, "modelo carregado"); }
        @Override public Optional<String> revisarConcordancia(String a, String b, List<String> c) { return Optional.empty(); }
        @Override public Optional<String> corrigirTraducao(String a, String b, String c) { return Optional.empty(); }
    }

    /**
     * Caso de uso que sempre alucina — conta quantos arquivos foram efetivamente processados
     * e registra o contexto que o CLI passou, para provar que ele congela UM snapshot para o
     * lote inteiro em vez de reconsultar o gerenciador por arquivo.
     */
    private static final class ProcessarArquivoAlucina extends ProcessarArquivoUseCase {
        final AtomicInteger chamadas = new AtomicInteger();
        final List<SnapshotContexto> contextosRecebidos = new java.util.ArrayList<>();
        ProcessarArquivoAlucina() {
            // +1 null: CorretorHomografoComOriginal entrou no construtor em 03/09/2026.
            super(null, null, null, null, null, null, null, null, null, null,
                  null, null, null, null, null, null, null, null, null, null, null, null, null, null, null,
                  null, null, null, null, null, null);
        }
        @Override
        public ResultadoTraducaoArquivo processar(
            Path arquivoEntrada, boolean permitirRetraducao, SnapshotContexto contextoDoJob) {
            chamadas.incrementAndGet();
            contextosRecebidos.add(contextoDoJob);
            throw new AlucinacaoDetectadaException("alucinacao em " + arquivoEntrada.getFileName());
        }
    }

    /** Devolve PARCIAL para quem tem "parcial" no nome e CONCLUIDO para o resto, sem lançar. */
    private static final class ProcessarArquivoComDesfecho extends ProcessarArquivoUseCase {
        final List<String> processados = new java.util.ArrayList<>();
        ProcessarArquivoComDesfecho() {
            super(null, null, null, null, null, null, null, null, null, null,
                  null, null, null, null, null, null, null, null, null, null, null, null, null, null, null,
                  null, null, null, null, null, null);
        }
        @Override
        public ResultadoTraducaoArquivo processar(
            Path arquivoEntrada, boolean permitirRetraducao, SnapshotContexto contextoDoJob) {
            String nome = arquivoEntrada.getFileName().toString();
            processados.add(nome);
            int avisos = nome.contains("parcial") ? 1 : 0;
            return ResultadoTraducaoArquivo.concluido(arquivoEntrada, nome, "lore", 3, 0, 3 - avisos, avisos);
        }
    }

    /**
     * 28/10/2026: o PARCIAL volta sem lançar, e a CLI descartava o retorno — o arquivo com falas
     * em inglês contava como "traduzido com sucesso". E o filtro da CLI ignorava {@code .srt}.
     */
    @Test
    @DisplayName("PARCIAL nao conta como sucesso, e .srt entra no lote")
    void parcialNaoEhSucessoESrtEntra(@TempDir Path entrada) throws Exception {
        Files.writeString(entrada.resolve("ep1-parcial.ass"), "conteudo");
        Files.writeString(entrada.resolve("ep2.ass"), "conteudo");
        Files.writeString(entrada.resolve("ep3.srt"), "conteudo");

        UiLoggerCaptor uiLogger = new UiLoggerCaptor();
        ProcessarArquivoComDesfecho processar = new ProcessarArquivoComDesfecho();
        TradutorProperties props = new TradutorProperties(
            entrada.toString(), entrada.toString(), entrada.toString(), 20, List.of(), "en", "pt-BR");
        new TradutorCLI(processar, uiLogger, props, new PastasExecucao(), new LlmDisponivel(),
            new GerenciadorContexto(List.of())).executar();

        assertEquals(3, processar.processados.size(), "o .srt tem de entrar: " + processar.processados);
        assertTrue(uiLogger.linhas.stream().anyMatch(l -> l.contains("2 sucesso(s), 1 falha(s) de 3 arquivo(s)")),
            "o parcial nao e sucesso: " + uiLogger.linhas);
        assertTrue(uiLogger.linhas.stream().anyMatch(l -> l.startsWith("[ PARCIAL ] ep1-parcial.ass")),
            "o parcial e dito na linha do arquivo");
        assertTrue(uiLogger.linhas.stream().noneMatch(l -> l.contains("ep1-parcial.ass traduzido com sucesso")),
            "o arquivo com pendencia nao pode ser anunciado como sucesso");
    }

    @Test
    @DisplayName("dois arquivos que alucinam: ambos contam como falha, mensagem preservada, lote continua")
    void alucinacaoEmArquivoContaComoFalhaESegueOLote(@TempDir Path entrada) throws Exception {
        Files.writeString(entrada.resolve("ep1.ass"), "conteudo");
        Files.writeString(entrada.resolve("ep2.ass"), "conteudo");

        UiLoggerCaptor uiLogger = new UiLoggerCaptor();
        ProcessarArquivoAlucina processar = new ProcessarArquivoAlucina();
        TradutorProperties props = new TradutorProperties(
            entrada.toString(), entrada.toString(), entrada.toString(), 20, List.of(), "en", "pt-BR");
        PastasExecucao pastas = new PastasExecucao();

        GerenciadorContexto gerenciador = new GerenciadorContexto(List.of());
        TradutorCLI cli = new TradutorCLI(
            processar, uiLogger, props, pastas, new LlmDisponivel(), gerenciador);
        cli.executar();

        // Continuidade do lote: os DOIS arquivos foram processados apesar da falha no primeiro.
        assertEquals(2, processar.chamadas.get(), "o lote deve continuar após a falha do primeiro arquivo");

        // Snapshot ÚNICO por lote: o CLI congela o contexto antes do laço e passa o MESMO
        // objeto a cada arquivo, em vez de reconsultar o gerenciador (global e mutável).
        assertEquals(2, processar.contextosRecebidos.size());
        assertSame(processar.contextosRecebidos.get(0), processar.contextosRecebidos.get(1),
            "todos os arquivos do lote devem receber a MESMA fotografia de contexto");

        // Mensagem preservada no formato do ramo crítico "[ FAIL ] Falha em X: msg".
        long falhasFormatoCritico = uiLogger.linhas.stream()
            .filter(l -> l.startsWith("[ FAIL ] Falha em ") && l.contains("alucinacao em "))
            .count();
        assertEquals(2, falhasFormatoCritico, "cada arquivo alucinado deve exibir a falha crítica com a mensagem original");

        // Relatório final: 0 sucessos, 2 falhas, e a lista de arquivos com falha.
        assertTrue(uiLogger.linhas.stream().anyMatch(l -> l.contains("0 sucesso(s), 2 falha(s) de 2 arquivo(s)")),
            "relatório deve contabilizar 2 falhas e 0 sucessos");
        assertTrue(uiLogger.linhas.stream().anyMatch(l ->
                l.startsWith("[ WARN ] Arquivos com falha") && l.contains("ep1.ass") && l.contains("ep2.ass")),
            "relatório deve listar os dois arquivos com falha");
    }
}

