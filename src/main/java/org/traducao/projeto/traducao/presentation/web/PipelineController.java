package org.traducao.projeto.traducao.presentation.web;
import org.traducao.projeto.core.presentation.web.RespostaPadrao;
import org.traducao.projeto.core.presentation.ui.AnsiCores;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.traducao.projeto.core.execucao.FilaExecucaoPipeline;
import org.traducao.projeto.llm.domain.StatusLlm;
import org.traducao.projeto.llm.domain.LlmPort;
import org.traducao.projeto.traducao.infrastructure.config.LlmProperties;
import org.traducao.projeto.lore.domain.ProvedorContexto;
import org.traducao.projeto.lore.infrastructure.GerenciadorContexto;
import org.traducao.projeto.core.presentation.ui.AnsiCores;

import java.util.List;

/**
 * PROPÓSITO DE NEGÓCIO: expõe os endpoints de estado e controle do pipeline
 * local à interface web — heartbeat, parada cooperativa da fila, estado da fila,
 * status ao vivo do servidor LLM e a lista de contextos de tradução disponíveis.
 *
 * <p>INVARIANTES DO DOMÍNIO: compartilha a MESMA {@link FilaExecucaoPipeline}
 * (bean CDI) dos demais controllers; a parada é cooperativa e preserva o
 * progresso já salvo; nenhuma URL, código HTTP ou nome de campo de DTO é alterado
 * em relação ao controller monolítico original.
 *
 * <p>COMPORTAMENTO EM CASO DE FALHA: a consulta de status do LLM nunca propaga
 * exceção — falhas viram uma resposta {@code online=false} com a mensagem do
 * erro; os demais endpoints são consultas simples sem caminho de falha explícito.
 */
@RestController
@RequestMapping("/api")
public class PipelineController {

    private static final Logger log = LoggerFactory.getLogger(PipelineController.class);

    // Fila única compartilhada por todos os módulos (ver FilaExecucaoPipeline):
    // garante execução sequencial em segundo plano e impede que outro endpoint
    // troque o contexto/modelo LLM no meio de um job em andamento.
    private final FilaExecucaoPipeline filaExecucao;
    private final LlmPort llmPort;
    private final GerenciadorContexto gerenciadorContexto;
    private final LlmProperties llmProperties;
    private final CatalogoObras catalogoObras;

    public PipelineController(
            FilaExecucaoPipeline filaExecucao,
            LlmPort llmPort,
            GerenciadorContexto gerenciadorContexto,
            LlmProperties llmProperties,
            CatalogoObras catalogoObras) {
        this.filaExecucao = filaExecucao;
        this.llmPort = llmPort;
        this.gerenciadorContexto = gerenciadorContexto;
        this.llmProperties = llmProperties;
        this.catalogoObras = catalogoObras;
    }

    /**
     * Endpoint para consulta de status geral (heartbeat)
     */
    @GetMapping("/status")
    public ResponseEntity<RespostaPadrao> status() {
        return ResponseEntity.ok(new RespostaPadrao("online"));
    }

    /**
     * Para o trabalho em execução na fila do pipeline e descarta os
     * enfileirados. A parada é cooperativa: o job interrompido encerra no
     * próximo ponto seguro (entre falas/arquivos), preservando o progresso já
     * salvo — cache de tradução e arquivos concluídos não se perdem.
     */
    @PostMapping("/pipeline/parar")
    public ResponseEntity<RespostaPadrao> pararPipeline() {
        if (!filaExecucao.ocupada()) {
            return ResponseEntity.ok(new RespostaPadrao("Nenhum trabalho em execução no pipeline."));
        }
        int canceladas = filaExecucao.parar();
        log.info("Pipeline interrompido pelo usuário ({} tarefa(s) cancelada(s)).", canceladas);
        System.out.println(AnsiCores.YELLOW
            + "[STOP] Interrupção solicitada pelo usuário — o trabalho atual encerra no próximo ponto seguro."
            + AnsiCores.RESET);
        return ResponseEntity.ok(new RespostaPadrao(
            "Parada solicitada (" + canceladas + " tarefa(s) cancelada(s)). "
                + "O trabalho atual encerra no próximo ponto seguro, preservando o progresso já salvo."));
    }

    /**
     * Estado da fila do pipeline — usado pela UI no modal do "Sair" para
     * avisar quando ainda há trabalho em execução.
     */
    @GetMapping("/pipeline/status")
    public ResponseEntity<RespostaPadrao> statusPipeline() {
        return ResponseEntity.ok(new RespostaPadrao(filaExecucao.ocupada() ? "ocupada" : "livre"));
    }

    /**
     * PROPÓSITO DE NEGÓCIO: status ao vivo do servidor LLM local (LM Studio) para o card do
     * painel inicial — se está online, se há modelo carregado e QUAL.
     *
     * <p>INVARIANTES DO DOMÍNIO: o nome vem de {@link LlmPort#modeloAtivo()}, que é o modelo que
     * a verificação resolveu. A cópia de {@link LlmProperties} injetada aqui NÃO recebe o modelo
     * detectado (o adaptador documenta isso desde 23/07/2026, quando 155 registros de telemetria
     * gravaram {@code "current"}), e o card mostrava a configuração crua. Ela fica só como último
     * recurso, quando a porta não sabe informar (auditoria de 28/10/2026).
     *
     * <p>COMPORTAMENTO EM CASO DE FALHA: erro na consulta devolve offline com a mensagem; nunca
     * lança.
     */
    @GetMapping("/llm/status")
    public ResponseEntity<LlmStatusResponse> statusLlm() {
        try {
            StatusLlm status = llmPort.verificarDisponibilidade();
            String ativo = llmPort.modeloAtivo();
            String modelo = !status.modeloCarregado() ? null
                : ativo != null && !ativo.isBlank() ? ativo : llmProperties.model();
            return ResponseEntity.ok(new LlmStatusResponse(
                status.servidorOnline(), status.modeloCarregado(), modelo, status.mensagem()));
        } catch (Exception e) {
            log.warn("Falha ao consultar o status do LLM: {}", e.getMessage());
            return ResponseEntity.ok(new LlmStatusResponse(false, false, null,
                "Falha ao consultar o servidor LLM: " + e.getMessage()));
        }
    }

    /**
     * Lista os contextos de tradução disponíveis (animes).
     */
    @GetMapping("/contextos")
    public ResponseEntity<List<ContextoResponse>> listarContextos() {
        String idPadrao = gerenciadorContexto.getIdContextoPadrao();
        // Enriquece com franquia (grupo) + nome padronizado e ordena por SEÇÃO (rótulo
        // do grupo, ou o próprio nome quando título solto) e depois pela ordem cronológica
        // dentro do grupo — a SPA só precisa criar os <optgroup> na troca de grupo.
        List<ContextoResponse> lista = gerenciadorContexto.getProvedores().stream()
                // O contexto genérico "sem lore" tem tela própria e fica FORA dos combos de
                // obra: oferecê-lo ao lado das 68 obras convidaria a descartar a terminologia
                // de uma obra que tem lore declarada.
                .filter(ProvedorContexto::apareceNaListaDeObras)
                .map(p -> new ContextoResponse(
                        p.getId(),
                        catalogoObras.nomePadronizado(p.getId(), p.getNomeExibicao()),
                        catalogoObras.grupo(p.getNomeExibicao()),
                        p.getId().equals(idPadrao)))
                .sorted(java.util.Comparator
                        .comparing((ContextoResponse c) -> c.grupo().isEmpty() ? c.nome() : c.grupo(),
                                String.CASE_INSENSITIVE_ORDER)
                        .thenComparingInt(c -> catalogoObras.ordem(c.id())))
                .toList();
        return ResponseEntity.ok(lista);
    }
}
