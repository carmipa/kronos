package org.traducao.projeto.vitrine.application;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.traducao.projeto.vitrine.application.PoliticaVitrine.Acao;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * PROPÓSITO DE NEGÓCIO: prova a tabela de decisão da vitrine pública — o que pode seguir, o que
 * vira demonstração e o que é recusado — incluindo os casos de FRONTEIRA (A1): o mesmo caminho
 * com outro método, e caminho de rota perigosa disfarçado por barra dupla, ponto, ponto e
 * vírgula, maiúscula e codificação.
 *
 * <p>INVARIANTES DO DOMÍNIO: o gabarito desta tabela foi escrito a partir do mapa de efeitos de
 * cada rota (levantado no código em 14/10/2026), não lido das constantes da política (A3).
 *
 * <p>COMPORTAMENTO EM CASO DE FALHA: qualquer linha divergente reprova, nomeando método e caminho.
 */
class PoliticaVitrineTest {

    private static Acao decidir(String metodo, String caminho) {
        return PoliticaVitrine.decidir(metodo, caminho, caminho).acao();
    }

    @ParameterizedTest(name = "{0} {1} -> {2}")
    @CsvSource({
        // leitura de memória: segue para a rota real
        "GET,/api/status,PERMITIR",
        "GET,/api/pipeline/status,PERMITIR",
        "GET,/api/contextos,PERMITIR",
        "GET,/api/revisao-lore/contextos,PERMITIR",
        "GET,/api/docs/etapa-1.1-analise-midia,PERMITIR",
        "GET,/api/docs/arquitetura,PERMITIR",
        // lê disco, rede, LLM ou Redis: vira demonstração
        "GET,/api/telemetria,DEMONSTRAR",
        "GET,/api/telemetria/fatias,DEMONSTRAR",
        "GET,/api/telemetria/fatias/revisao,DEMONSTRAR",
        "GET,/api/fluxo/status,DEMONSTRAR",
        "GET,/api/llm/status,DEMONSTRAR",
        "GET,/api/dialogo/capacidade,DEMONSTRAR",
        // fluxos substituídos
        "GET,/api/logs/stream,SSE_LOGS",
        "GET,/api/telemetria/stream,SSE_TELEMETRIA",
        // estático
        "GET,/,PERMITIR",
        "GET,/index.html,PERMITIR",
        "GET,/js/app.js,PERMITIR",
        "HEAD,/css/base.css,PERMITIR",
        "GET,/img/kronos_logo.svg,PERMITIR",
        "GET,/sobre/foto.webp,PERMITIR",
        // GET com efeito: recusado
        "GET,/api/navegador/pastas,BLOQUEAR",
        "GET,/api/metadata,BLOQUEAR",
        "GET,/api/dialogo/selecionar-pasta,BLOQUEAR",
        "GET,/api/dialogo/selecionar-arquivo,BLOQUEAR",
        "GET,/api/telemetria/exportar,BLOQUEAR",
        // escrita: recusada
        "POST,/api/sistema/sair,BLOQUEAR",
        "POST,/api/traduzir,BLOQUEAR",
        "POST,/api/pipeline/parar,BLOQUEAR",
        "POST,/api/renomear-arquivos/aplicar,BLOQUEAR",
        "POST,/api/auditoria-conteudo,BLOQUEAR",
        "POST,/api/telemetria/publicar-dataset,BLOQUEAR",
        // MCP e /q
        "POST,/mcp,BLOQUEAR",
        "GET,/mcp,BLOQUEAR",
        "GET,/mcp/sse,BLOQUEAR",
        "POST,/mcp/messages/abc,BLOQUEAR",
        "GET,/q/dev-ui,BLOQUEAR",
        // sem a regra explícita de /mcp e /q, estes passariam pela regra do estático (têm extensão)
        "GET,/mcp/messages/abc.js,BLOQUEAR",
        "GET,/q/dev-ui/main.css,BLOQUEAR",
        // fonte local: woff2 passa; as outras extensões de fonte, com o mesmo sinal, não
        "GET,/fontes/material-symbols-outlined.woff2,PERMITIR",
        "POST,/fontes/material-symbols-outlined.woff2,BLOQUEAR",
        "GET,/fontes/material-symbols-outlined.woff,NAO_ENCONTRADO",
        "GET,/fontes/outfit.ttf,NAO_ENCONTRADO",
        // estático fora da lista de extensões: nunca segue; para quem navega, é o 404 do KRONOS
        "GET,/prova-vitrine/nao-deve-vazar.txt,NAO_ENCONTRADO",
        "GET,/application-local.yml,NAO_ENCONTRADO",
        "GET,/docs/arquitetura.md,NAO_ENCONTRADO",
        "GET,/js/app,NAO_ENCONTRADO",
        // segmento que começa por ponto é caminho ambíguo: continua recusado, nunca "não encontrado"
        "GET,/.env,BLOQUEAR",
        "GET,/js/.oculto.js,BLOQUEAR",
        // endereço digitado errado (15/10/2026: na VPS caía na página mínima da vitrine, com 403).
        // Gabarito do requisito: todas as rotas do KRONOS moram sob /api (classes @Path medidas
        // em 15/10), além de /mcp e /q; fora disso só há estático, então o local também dá 404.
        "GET,/pagina-que-nao-existe,NAO_ENCONTRADO",
        "HEAD,/pagina-que-nao-existe,NAO_ENCONTRADO",
        "GET,/telemetria,NAO_ENCONTRADO",
        "GET,/apis,NAO_ENCONTRADO",
        // A1: o mesmo sinal ("endereço sem extensão") dentro das famílias de função é recusa, não 404
        "GET,/api,BLOQUEAR",
        "GET,/api/telemetria-inexistente,BLOQUEAR",
        "GET,/q,BLOQUEAR",
        "GET,/Mcp/sse,BLOQUEAR",
        "GET,/Q/dev-ui,BLOQUEAR",
        "POST,/pagina-que-nao-existe,BLOQUEAR",
    })
    @DisplayName("tabela de decisão da vitrine")
    void tabela(String metodo, String caminho, Acao esperado) {
        assertEquals(esperado, decidir(metodo, caminho), metodo + " " + caminho);
    }

    @ParameterizedTest(name = "{0} {1}")
    @CsvSource({
        // A1: mesmo caminho legítimo, outro método
        "POST,/api/status",
        "PUT,/api/contextos",
        "DELETE,/api/telemetria",
        "OPTIONS,/api/status",
        "HEAD,/api/status",
        "POST,/js/app.js",
        "POST,/",
        "PATCH,/index.html",
        "TRACE,/",
    })
    @DisplayName("A1: o mesmo caminho permitido, com método que não é leitura, é recusado")
    void mesmoCaminhoOutroMetodo(String metodo, String caminho) {
        assertEquals(Acao.BLOQUEAR, decidir(metodo, caminho), metodo + " " + caminho);
    }

    @ParameterizedTest(name = "[{0}] normalizado=[{1}]")
    @CsvSource({
        "/api//status,/api/status",
        "/api/./status,/api/status",
        "/api/x/../status,/api/status",
        "/api/status/,/api/status/",
        "/API/STATUS,/API/STATUS",
        "/api/status;x=1,/api/status;x=1",
        "/api%2Fstatus,/api/status",
        "/%61pi/status,/api/status",
        "/api/docs/../../application-local,/application-local",
        "/api/docs/..%2F..%2Fsegredo,/api/docs/..%2F..%2Fsegredo",
        "/api/telemetria/fatias/../exportar,/api/telemetria/exportar",
        "/api/telemetria/fatias/REVISAO,/api/telemetria/fatias/REVISAO",
        "/js/app.js%00.png,/js/app.js%00.png",
        "/js\\app.js,/js\\app.js",
        "//js/app.js,/js/app.js",
    })
    @DisplayName("A1: caminho ambíguo é recusado mesmo quando o normalizado seria permitido")
    void caminhoAmbiguo(String bruto, String normalizado) {
        assertEquals(Acao.BLOQUEAR, PoliticaVitrine.decidir("GET", bruto, normalizado).acao(),
            "bruto=" + bruto + " normalizado=" + normalizado);
    }

    @Test
    @DisplayName("controle: o caminho limpo equivalente aos ambíguos acima É permitido (prova de fronteira)")
    void controleDoCaminhoLimpo() {
        assertEquals(Acao.PERMITIR, decidir("GET", "/api/status"));
        assertEquals(Acao.PERMITIR, decidir("GET", "/js/app.js"));
        assertEquals(Acao.DEMONSTRAR, decidir("GET", "/api/telemetria/fatias/revisao"));
    }

    @Test
    @DisplayName("entrada nula nunca abre passagem")
    void nuloRecusa() {
        assertEquals(Acao.BLOQUEAR, PoliticaVitrine.decidir(null, "/", "/").acao());
        assertEquals(Acao.BLOQUEAR, PoliticaVitrine.decidir("GET", null, "/").acao());
        assertEquals(Acao.BLOQUEAR, PoliticaVitrine.decidir("GET", "/", null).acao());
    }

    @Test
    @DisplayName("a demonstração da fatia leva o nome validado, nunca texto livre")
    void nomeDaFatia() {
        assertEquals("fatia-revisao",
            PoliticaVitrine.decidir("GET", "/api/telemetria/fatias/revisao", "/api/telemetria/fatias/revisao")
                .demonstracao());
    }
}
