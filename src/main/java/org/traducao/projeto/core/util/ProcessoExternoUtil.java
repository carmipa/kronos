package org.traducao.projeto.core.util;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.traducao.projeto.core.vitrine.ModoVitrine;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * PROPÓSITO DE NEGÓCIO: executa com segurança as ferramentas externas de que o KRONOS
 * depende (ffmpeg, ffprobe, mkvmerge, mkvextract, git), servindo de único ponto de spawn de
 * processos. Drena stdout e stderr em paralelo para nunca travar o pipeline por um pipe cheio.
 *
 * <h2>Invariantes do domínio</h2>
 * <ul>
 *   <li>stdout e stderr são drenados concorrentemente — evita o deadlock clássico do
 *       {@link ProcessBuilder}, em que o filho bloqueia escrevendo num pipe cujo buffer do
 *       SO enche enquanto o pai ainda lê o outro fluxo.</li>
 *   <li>O {@code timeout} recebido limita a espera pelo término normal; ao estourá-lo, o
 *       encerramento forçado é SOLICITADO ({@code destroyForcibly}) e o método AGUARDA a
 *       CONFIRMAÇÃO do término por um limite curto e explícito ({@code ESPERA_ENCERRAMENTO} =
 *       2s), em vez de só disparar o kill e retornar. Kill solicitado não é término
 *       confirmado: se o SO não confirmar dentro do limite, o método retorna assim mesmo
 *       (best-effort), sem garantir de forma absoluta que nenhum filho sobreviveu.</li>
 *   <li>A drenagem é I/O puro: roda em Virtual Threads (Java 21+), sem manter um pool de
 *       threads de plataforma por invocação nem competir com a GPU/LLM sequencial.</li>
 * </ul>
 *
 * <h2>Comportamento em caso de falha</h2>
 * Falha de spawn ou de leitura vira {@link IOException}. Estourar o {@code timeout} lança
 * {@link TimeoutException} depois de SOLICITAR o encerramento forçado e aguardar (best-effort)
 * a confirmação do término; se o SO não confirmar dentro do limite curto de espera, o método
 * registra um aviso e retorna assim mesmo, sem prender o pipeline (kill solicitado, término
 * não confirmado). Uma interrupção da thread chamadora propaga {@link InterruptedException};
 * se ocorrer durante a espera pelo encerramento, o cancelamento tem PRECEDÊNCIA sobre um
 * {@link TimeoutException} ainda não entregue. Só na limpeza final a interrupção é absorvida
 * restaurando o flag, para não mascarar a exceção ou o resultado já em curso.
 */
public final class ProcessoExternoUtil {

    private static final Logger log = LoggerFactory.getLogger(ProcessoExternoUtil.class);

    // Virtual threads: a drenagem de stdout/stderr é I/O puro e efêmera. Nomeadas para
    // diagnóstico; sempre daemon por natureza (não seguram a JVM viva).
    private static final ThreadFactory FABRICA_DRENAGEM =
        Thread.ofVirtual().name("processo-externo-drain-", 0).factory();

    // Espera curta e explícita pela confirmação do término após destroyForcibly. Curta
    // porque um kill do SO é quase imediato; limitada para nunca travar o pipeline caso
    // o SO demore a recolher o processo.
    private static final Duration ESPERA_ENCERRAMENTO = Duration.ofSeconds(2);

    private ProcessoExternoUtil() {}

    public record Resultado(int codigoSaida, byte[] stdout, byte[] stderr) {}

    /**
     * PROPÓSITO DE NEGÓCIO: executa o comando externo capturando stdout e stderr separados,
     * para o chamador inspecionar diagnósticos sem misturar os fluxos.
     *
     * <p>INVARIANTES DO DOMÍNIO: delega ao fluxo completo sem mesclar erro.
     *
     * <p>COMPORTAMENTO EM CASO DE FALHA: propaga {@link IOException}, {@link InterruptedException}
     * e {@link TimeoutException} do fluxo completo.
     */
    public static Resultado executar(List<String> comando, Duration timeout) throws IOException, InterruptedException, TimeoutException {
        return executar(comando, timeout, false);
    }

    /**
     * PROPÓSITO DE NEGÓCIO: executa o comando externo com controle explícito de mescla de
     * erro e timeout, isolando o KRONOS de travas e vazamentos de processos-filho.
     *
     * <p>INVARIANTES DO DOMÍNIO: stdout e stderr são drenados concorrentemente em Virtual
     * Threads; com {@code mesclarErro=true}, stderr vai para stdout e o stderr devolvido é
     * vazio; se o processo ainda estiver vivo ao final, o encerramento forçado é solicitado e a
     * confirmação do término aguardada (best-effort).
     *
     * <p>COMPORTAMENTO EM CASO DE FALHA: falha ao ler um fluxo vira {@link IOException}; exceder
     * o {@code timeout} lança {@link TimeoutException} depois de solicitar o encerramento
     * forçado e aguardar (best-effort) a confirmação; uma interrupção durante a espera propaga
     * {@link InterruptedException}, com precedência sobre o timeout ainda não entregue. Na
     * vitrine pública, nenhum processo é criado: lança
     * {@link org.traducao.projeto.core.vitrine.OperacaoBloqueadaNaVitrineException} antes do spawn.
     */
    public static Resultado executar(List<String> comando, Duration timeout, boolean mesclarErro)
            throws IOException, InterruptedException, TimeoutException {
        ModoVitrine.exigirForaDaVitrine("processo externo");
        Process processo = new ProcessBuilder(comando).redirectErrorStream(mesclarErro).start();

        ExecutorService pool = Executors.newThreadPerTaskExecutor(FABRICA_DRENAGEM);
        try {
            Future<byte[]> stdoutFuture = pool.submit(() -> processo.getInputStream().readAllBytes());
            Future<byte[]> stderrFuture = mesclarErro ? null : pool.submit(() -> processo.getErrorStream().readAllBytes());

            boolean terminou = processo.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
            if (!terminou) {
                // Se a interrupção chegar aqui, encerrarForcadamenteEAguardar propaga
                // InterruptedException e o TimeoutException abaixo NÃO é lançado — o
                // cancelamento tem precedência sobre um timeout ainda não entregue.
                encerrarForcadamenteEAguardar(processo);
                throw new TimeoutException("Processo excedeu o tempo limite de " + timeout.toSeconds()
                        + "s; encerramento forçado solicitado: " + String.join(" ", comando));
            }

            byte[] stdout = lerResultado(stdoutFuture);
            byte[] stderr = stderrFuture != null ? lerResultado(stderrFuture) : new byte[0];
            return new Resultado(processo.exitValue(), stdout, stderr);
        } finally {
            pool.shutdownNow();
            // Limpeza: solicita e aguarda o encerramento sem MASCARAR a exceção/retorno já em
            // curso. Aqui a interrupção só restaura o flag (não pode sobrepor o que propaga).
            try {
                encerrarForcadamenteEAguardar(processo);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /**
     * PROPÓSITO DE NEGÓCIO: solicita o encerramento forçado de um processo externo estourado
     * ou abandonado e AGUARDA a confirmação do término antes de retornar, para não deixar um
     * ffmpeg/mkvmerge consumindo recursos após o kill.
     *
     * <p>INVARIANTES DO DOMÍNIO: se ainda vivo, chama {@code destroyForcibly()} (kill
     * SOLICITADO) e aguarda a CONFIRMAÇÃO do término por um limite curto e explícito
     * ({@link #ESPERA_ENCERRAMENTO}), nunca indefinidamente; não altera stdout/stderr nem o
     * código de saída; não introduz concorrência no LLM.
     *
     * <p>COMPORTAMENTO EM CASO DE FALHA: se o SO não confirmar o término dentro do limite, o
     * kill permanece solicitado, porém não confirmado — registra um aviso e retorna assim
     * mesmo, sem prender o pipeline. Uma interrupção durante a espera PROPAGA
     * {@link InterruptedException} ao chamador, dando ao cancelamento precedência sobre um
     * {@link TimeoutException} ainda não entregue.
     */
    private static void encerrarForcadamenteEAguardar(Process processo) throws InterruptedException {
        if (!processo.isAlive()) {
            return;
        }
        processo.destroyForcibly();
        boolean confirmado = processo.waitFor(ESPERA_ENCERRAMENTO.toMillis(), TimeUnit.MILLISECONDS);
        if (!confirmado) {
            log.warn("Kill solicitado, mas o processo externo não confirmou o término {} ms depois; "
                + "o SO pode concluir o encerramento de forma assíncrona.", ESPERA_ENCERRAMENTO.toMillis());
        }
    }

    private static byte[] lerResultado(Future<byte[]> future) throws IOException, InterruptedException {
        try {
            return future.get(5, TimeUnit.SECONDS);
        } catch (ExecutionException e) {
            throw new IOException("Falha ao ler saída do processo externo", e.getCause());
        } catch (TimeoutException e) {
            future.cancel(true);
            // LANÇA em vez de devolver vazio. Array vazio é indistinguível de "o processo não
            // escreveu nada", e quem chama isto interpreta a saída: FfprobeAdapter e
            // MkvToolNixAdapter concluiriam "este MKV não tem faixa de legenda" quando o fato
            // é "não consegui ler a resposta". Num lote, isso vira episódio pulado em silêncio.
            // Mesma classe de defeito que o build.gradle já registra: "'0 testes rodaram' tem a
            // mesma aparência de 'tudo passou'".
            throw new IOException(
                "Timeout de 5s ao drenar a saída do processo externo — saída INCOMPLETA, "
                + "não confundir com saída vazia", e);
        }
    }
}
