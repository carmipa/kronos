package org.traducao.projeto.traducao.presentation.web;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.traducao.projeto.core.execucao.FilaExecucaoPipeline;
import org.traducao.projeto.llm.domain.LlmPort;
import org.traducao.projeto.llm.domain.Lote;
import org.traducao.projeto.llm.domain.StatusLlm;
import org.traducao.projeto.llm.domain.TraducaoLote;
import org.traducao.projeto.traducao.infrastructure.config.LlmProperties;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * PROPÓSITO DE NEGÓCIO: prova que o card do painel inicial mostra o modelo que o servidor tem
 * CARREGADO, e não o texto da configuração.
 *
 * <h2>O defeito, achado na auditoria de 28/10/2026</h2>
 * {@code /api/llm/status} devolvia {@code llmProperties.model()}. A cópia de {@link LlmProperties}
 * fora do adaptador não recebe o modelo detectado, e a configuração de produção é
 * {@code model: "current"} — o card dizia "current".
 *
 * <p>INVARIANTES DO DOMÍNIO: sem modelo carregado o nome é nulo; a configuração só aparece quando
 * a porta não sabe informar o modelo ativo.
 *
 * <p>COMPORTAMENTO EM CASO DE FALHA: dublês locais, sem rede.
 */
@DisplayName("/api/llm/status mostra o modelo ativo")
class StatusLlmMostraModeloAtivoTest {

    private static final class Llm implements LlmPort {
        private final boolean carregado;
        private final String ativo;

        Llm(boolean carregado, String ativo) {
            this.carregado = carregado;
            this.ativo = ativo;
        }

        @Override public TraducaoLote traduzir(Lote lote) { throw new UnsupportedOperationException(); }
        @Override public StatusLlm verificarDisponibilidade() { return new StatusLlm(true, carregado, "ok"); }
        @Override public Optional<String> revisarConcordancia(String a, String b, List<String> c) { return Optional.empty(); }
        @Override public Optional<String> corrigirTraducao(String a, String b, String c) { return Optional.empty(); }
        @Override public String modeloAtivo() { return ativo; }
    }

    private static PipelineController controller(LlmPort llm) {
        LlmProperties props = new LlmProperties("http://127.0.0.1:1/v1", "current", 0.3, 2000,
            Duration.ofSeconds(1), Duration.ofSeconds(1));
        return new PipelineController(new FilaExecucaoPipeline(), llm, null, props, null);
    }

    @Test
    @DisplayName("modelo carregado: o nome vem da porta, nao da configuracao")
    void nomeVemDaPorta() {
        assertEquals("aya-expanse-32b",
            controller(new Llm(true, "aya-expanse-32b")).statusLlm().getBody().modelo());
    }

    @Test
    @DisplayName("CONTROLE: porta sem nome cai na configuracao; sem modelo carregado, nulo")
    void semNomeOuSemModelo() {
        assertEquals("current", controller(new Llm(true, null)).statusLlm().getBody().modelo());
        assertNull(controller(new Llm(false, "aya-expanse-32b")).statusLlm().getBody().modelo());
    }
}
