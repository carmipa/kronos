package org.traducao.projeto.core.execucao;

import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Component;
import org.traducao.projeto.core.vitrine.ModoVitrine;

import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * Fila única (single-thread) para todos os jobs pesados do pipeline —
 * tradução, correção, revisões (concordância/lore), análise, extração, remux.
 * <p>
 * Ter UMA fila compartilhada é requisito de corretude, não só de desempenho:
 * o contexto de tradução ativo ({@code GerenciadorContexto}) e o modelo LLM
 * configurado são estado global mutado no início de cada job. Quando cada
 * controller tinha seu próprio executor (ou rodava na thread HTTP), dois jobs
 * podiam rodar em paralelo e um trocava a lore/modelo no meio do outro — além
 * de disputarem a GPU do LM Studio, que atende uma inferência por vez.
 */
@Component
public class FilaExecucaoPipeline {

    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "pipeline-fila-execucao");
        t.setDaemon(true);
        return t;
    });

    // Tarefas na fila + em execução. Serve para (1) recusar operações
    // síncronas em vez de deixá-las penduradas atrás de uma tradução de horas
    // e (2) o botão "Parar" da UI cancelar tudo via parar().
    private final Set<Future<?>> tarefas = ConcurrentHashMap.newKeySet();

    /**
     * PROPÓSITO DE NEGÓCIO: enfileira um job pesado do pipeline na thread única.
     *
     * <p>INVARIANTES DO DOMÍNIO: na vitrine pública nenhum job entra na fila — a recusa vem
     * antes do {@code submit}, então a tarefa nunca executa.
     *
     * <p>COMPORTAMENTO EM CASO DE FALHA: na vitrine lança
     * {@link org.traducao.projeto.core.vitrine.OperacaoBloqueadaNaVitrineException}.
     */
    public Future<?> submeter(Runnable tarefa) {
        ModoVitrine.exigirForaDaVitrine("fila do pipeline");
        Future<?> future = executor.submit(tarefa);
        registrar(future);
        return future;
    }

    /**
     * Executa a tarefa na fila e bloqueia até o resultado — para endpoints que
     * respondem o resultado no próprio request HTTP. Verifique
     * {@link #ocupada()} antes, para não bloquear atrás de um job longo.
     * <p>
     * NUNCA chame este método de dentro de uma tarefa que já roda NA fila:
     * o executor tem uma única thread, então esperar por outra tarefa da
     * mesma fila a partir dela é deadlock garantido.
     * <p>
     * Na vitrine pública recusa antes do {@code submit}, como {@link #submeter(Runnable)}.
     */
    public <T> T executarEAguardar(Callable<T> tarefa) throws Exception {
        ModoVitrine.exigirForaDaVitrine("fila do pipeline");
        Future<T> future = executor.submit(tarefa);
        registrar(future);
        try {
            return future.get();
        } catch (ExecutionException e) {
            if (e.getCause() instanceof Exception causa) {
                throw causa;
            }
            throw e;
        }
    }

    public boolean ocupada() {
        return tarefas.stream().anyMatch(f -> !f.isDone());
    }

    /**
     * Para o trabalho do pipeline: interrompe a tarefa em execução (interrupt
     * na thread única) e descarta as enfileiradas. A parada é COOPERATIVA —
     * os use cases longos verificam a flag de interrupção entre falas/arquivos
     * e encerram no próximo ponto seguro, preservando o progresso já salvo
     * (cache de tradução, arquivos concluídos). Retorna quantas tarefas foram
     * canceladas.
     */
    public int parar() {
        int canceladas = 0;
        for (Future<?> f : tarefas) {
            if (!f.isDone() && f.cancel(true)) {
                canceladas++;
            }
        }
        tarefas.removeIf(Future::isDone);
        return canceladas;
    }

    private void registrar(Future<?> future) {
        tarefas.add(future);
        // Higiene: concluídas (ou canceladas) não precisam ficar no conjunto.
        tarefas.removeIf(Future::isDone);
    }

    @PreDestroy
    void encerrar() {
        executor.shutdownNow();
    }
}
