package org.traducao.projeto.traducao.infrastructure.adapters;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.traducao.projeto.llm.domain.Lote;
import org.traducao.projeto.llm.domain.TraducaoLote;
import org.traducao.projeto.lore.infrastructure.GerenciadorContexto;
import org.traducao.projeto.traducao.infrastructure.config.LlmProperties;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * PROPÓSITO DE NEGÓCIO: prova que o adaptador deixou de chamar de SUCESSO uma resposta que o
 * servidor cortou no teto de tokens. Antes desta correção, o contrato só transportava a
 * mensagem: {@code finish_reason} nem sequer era desserializado, então uma fala pela metade —
 * "I promise to protect everyone here." devolvido como "Eu prometo" — chegava à legenda com
 * {@code sucesso=true} e cara de tradução completa.
 *
 * <h2>Por que servidor de verdade e não dublê</h2>
 * O defeito vive no TRANSPORTE, entre o JSON do servidor e o objeto Java. Um dublê da porta
 * pularia justamente o trecho que estava quebrado, e o teste ficaria verde sem tocar no
 * defeito — regra 10: o instrumento tem de alcançar a classe de falha. O servidor é local, de
 * porta efêmera, e responde exatamente o JSON que a auditoria de 2026-09-09 usou.
 *
 * <p>INVARIANTES DO DOMÍNIO: {@code finish_reason} ausente NÃO reprova. Servidor que não informa
 * o campo continua funcionando como antes — é o que o segundo teste fixa.
 *
 * <p>COMPORTAMENTO EM CASO DE FALHA: o adaptador devolve {@link TraducaoLote} com
 * {@code sucesso=false} e mensagem de diagnóstico; não lança para o chamador.
 */
@DisplayName("Resposta truncada pelo teto de tokens não é sucesso")
class RespostaTruncadaNaoEhSucessoTest {

    private HttpServer servidor;
    private String baseUrl;

    private void subirServidorQueResponde(String corpoJson) throws Exception {
        servidor = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        servidor.createContext("/v1/chat/completions", troca -> {
            byte[] corpo = corpoJson.getBytes(StandardCharsets.UTF_8);
            troca.getResponseHeaders().add("Content-Type", "application/json");
            troca.sendResponseHeaders(200, corpo.length);
            try (OutputStream saida = troca.getResponseBody()) {
                saida.write(corpo);
            }
        });
        servidor.start();
        baseUrl = "http://127.0.0.1:" + servidor.getAddress().getPort() + "/v1";
    }

    @AfterEach
    void derrubarServidor() {
        if (servidor != null) {
            servidor.stop(0);
        }
    }

    @BeforeEach
    void semServidor() {
        servidor = null;
    }

    private TraducaoLote traduzirUmaFala() {
        LlmProperties propriedades = new LlmProperties(
            baseUrl, "modelo-de-teste", 0.3, 2000, Duration.ofSeconds(5), Duration.ofSeconds(10));
        LlmClientAdapter adaptador = new LlmClientAdapter(
            propriedades, new GerenciadorContexto(List.of()), new ObjectMapper());
        return adaptador.traduzir(new Lote(1, List.of("I promise to protect everyone here.")));
    }

    @Test
    @DisplayName("finish_reason=length reprova, e a fala cortada não vira tradução publicável")
    void truncamentoNaoEhSucesso() throws Exception {
        subirServidorQueResponde("""
            {"choices":[{"message":{"role":"assistant","content":"Eu prometo"},
             "finish_reason":"length"}]}""");

        TraducaoLote resultado = traduzirUmaFala();

        assertFalse(resultado.sucesso(),
            "resposta cortada no teto de tokens foi declarada sucesso — o defeito segue aberto");
        assertNotNull(resultado.mensagemErro(), "falha sem diagnóstico é saída ambígua");
        assertTrue(resultado.mensagemErro().toLowerCase().contains("trunc"),
            "o diagnóstico tem de dizer que foi truncamento, e disse: " + resultado.mensagemErro());
        // 28/10/2026: o servidor RESPONDEU — é problema desta fala, não de todas. Sem a marca, o
        // caso de uso tratava como servidor fora do ar e abortava o episódio inteiro.
        assertTrue(resultado.recusaDaRequisicao(),
            "truncamento tem de voltar como recusa DESTA requisicao, para a fala ficar pendente e o episodio seguir");
    }

    @Test
    @DisplayName("resposta vazia nas tres tentativas tambem e problema desta fala, nao do servidor")
    void respostaVaziaEhRecusaDaRequisicao() throws Exception {
        subirServidorQueResponde("""
            {"choices":[{"message":{"role":"assistant","content":"   "},"finish_reason":"stop"}]}""");

        TraducaoLote resultado = traduzirUmaFala();

        assertFalse(resultado.sucesso());
        assertTrue(resultado.recusaDaRequisicao(), "o servidor respondeu: " + resultado.mensagemErro());
    }

    @Test
    @DisplayName("CASO-CONTROLE: servidor fora do ar continua sendo indisponibilidade, nao recusa")
    void servidorForaDoArNaoEhRecusa() {
        baseUrl = "http://127.0.0.1:9/v1";

        TraducaoLote resultado = traduzirUmaFala();

        assertFalse(resultado.sucesso());
        assertFalse(resultado.recusaDaRequisicao(),
            "conexao recusada e problema de TODAS as falas: o episodio deve abortar, como sempre");
    }

    @Test
    @DisplayName("CASO-CONTROLE: finish_reason=stop continua sendo sucesso")
    void terminoNormalContinuaSucesso() throws Exception {
        subirServidorQueResponde("""
            {"choices":[{"message":{"role":"assistant","content":"Eu prometo proteger todos aqui."},
             "finish_reason":"stop"}]}""");

        TraducaoLote resultado = traduzirUmaFala();

        assertTrue(resultado.sucesso(),
            "tradução completa foi reprovada: a correção passou a barrar o que funcionava");
    }

    @Test
    @DisplayName("CASO-CONTROLE: servidor que não informa finish_reason continua funcionando")
    void semFinishReasonNadaMuda() throws Exception {
        subirServidorQueResponde("""
            {"choices":[{"message":{"role":"assistant","content":"Eu prometo proteger todos aqui."}}]}""");

        TraducaoLote resultado = traduzirUmaFala();

        assertTrue(resultado.sucesso(),
            "campo ausente é 'não sei' e não pode virar reprovação — quebraria servidores que não o enviam");
    }
}
