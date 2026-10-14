package org.traducao.projeto.vitrine.presentation.bootstrap;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * PROPÓSITO DE NEGÓCIO: prova que a vitrine pública só sobe com configuração coerente — modo
 * WEB, navegador automático desligado, MCP desligado e filtro HTTP ligado — e recusa cada
 * incoerência sozinha (a fronteira de cada condição).
 *
 * <p>INVARIANTES DO DOMÍNIO: a configuração coerente devolve lista vazia; cada condição
 * quebrada, isoladamente, devolve exatamente um problema.
 *
 * <p>COMPORTAMENTO EM CASO DE FALHA: a asserção mostra a lista recebida.
 */
class ConferenciaArranqueVitrineTest {

    @Test
    @DisplayName("configuração coerente: nenhum problema")
    void coerente() {
        assertEquals(List.of(), ConferenciaArranqueVitrine.conferir(true, "WEB", false, false));
        assertEquals(List.of(), ConferenciaArranqueVitrine.conferir(true, " web ", false, false));
    }

    @Test
    @DisplayName("cada incoerência sozinha vira exatamente um problema")
    void cadaIncoerencia() {
        assertUm(ConferenciaArranqueVitrine.conferir(false, "WEB", false, false), "filtro HTTP");
        assertUm(ConferenciaArranqueVitrine.conferir(true, "TRADUZIR", false, false), "app.modo=TRADUZIR");
        assertUm(ConferenciaArranqueVitrine.conferir(true, "EXTRAIR", false, false), "app.modo=EXTRAIR");
        assertUm(ConferenciaArranqueVitrine.conferir(true, null, false, false), "app.modo=null");
        assertUm(ConferenciaArranqueVitrine.conferir(true, "WEB", true, false), "auto-open");
        assertUm(ConferenciaArranqueVitrine.conferir(true, "WEB", false, true), "mcp");
    }

    @Test
    @DisplayName("tudo errado: todos os problemas listados de uma vez")
    void tudoErrado() {
        assertEquals(4, ConferenciaArranqueVitrine.conferir(false, "REMUXAR", true, true).size());
    }

    private static void assertUm(List<String> problemas, String trecho) {
        assertEquals(1, problemas.size(), problemas.toString());
        assertTrue(problemas.get(0).contains(trecho), problemas.toString());
    }
}
