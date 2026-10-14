package org.traducao.projeto.core.vitrine;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.traducao.projeto.core.execucao.FilaExecucaoPipeline;
import org.traducao.projeto.core.util.ProcessoExternoUtil;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * PROPÓSITO DE NEGÓCIO: prova a SEGUNDA camada da vitrine pública — mesmo que uma rota escapasse
 * do filtro HTTP, a fila do pipeline e o executor de processo externo recusam antes de qualquer
 * efeito.
 *
 * <p>INVARIANTES DO DOMÍNIO: o efeito é medido no mundo (tarefa rodou? arquivo criado pelo
 * processo?), não pela exceção; e o mesmo instrumento é visto produzindo o efeito com a vitrine
 * desligada (controle positivo), senão "não criou o arquivo" poderia ser cegueira.
 *
 * <p>COMPORTAMENTO EM CASO DE FALHA: cada teste devolve o estado da vitrine a desligado, para não
 * contaminar os outros testes da mesma JVM.
 */
class ModoVitrineCamada2Test {

    @TempDir
    Path tmp;

    @AfterEach
    void desligar() {
        ModoVitrine.registrarArranque(false);
    }

    @Test
    @DisplayName("vitrine ligada: a fila recusa e a tarefa nunca roda")
    void filaRecusa() throws Exception {
        ModoVitrine.registrarArranque(true);
        FilaExecucaoPipeline fila = new FilaExecucaoPipeline();
        AtomicBoolean rodou = new AtomicBoolean(false);
        assertThrows(OperacaoBloqueadaNaVitrineException.class, () -> fila.submeter(() -> rodou.set(true)));
        assertThrows(OperacaoBloqueadaNaVitrineException.class, () -> fila.executarEAguardar(() -> {
            rodou.set(true);
            return 1;
        }));
        Thread.sleep(100);
        assertFalse(rodou.get(), "a tarefa rodou apesar da recusa");
    }

    @Test
    @DisplayName("controle: vitrine desligada, a mesma fila roda a tarefa")
    void controleFilaRoda() throws Exception {
        FilaExecucaoPipeline fila = new FilaExecucaoPipeline();
        AtomicBoolean rodou = new AtomicBoolean(false);
        fila.submeter(() -> rodou.set(true)).get(5, TimeUnit.SECONDS);
        assertTrue(rodou.get());
        assertEquals(7, fila.executarEAguardar(() -> 7));
    }

    @Test
    @DisplayName("vitrine ligada: nenhum processo externo é criado")
    void processoRecusado() throws Exception {
        ModoVitrine.registrarArranque(true);
        Path marca = tmp.resolve("marca-vitrine.txt");
        assertThrows(OperacaoBloqueadaNaVitrineException.class,
            () -> ProcessoExternoUtil.executar(comandoQueCria(marca), Duration.ofSeconds(10)));
        Thread.sleep(300);
        assertFalse(Files.exists(marca), "o processo rodou apesar da recusa");
    }

    @Test
    @DisplayName("controle: vitrine desligada, o mesmo comando cria o arquivo (o instrumento enxerga)")
    void controleProcessoRoda() throws Exception {
        Path marca = tmp.resolve("marca-controle.txt");
        ProcessoExternoUtil.Resultado r = ProcessoExternoUtil.executar(comandoQueCria(marca), Duration.ofSeconds(10));
        assertEquals(0, r.codigoSaida());
        assertTrue(Files.exists(marca), "controle falhou: o comando não criou o arquivo");
    }

    private static List<String> comandoQueCria(Path arquivo) {
        if (System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win")) {
            return List.of("cmd", "/c", "type nul > \"" + arquivo + "\"");
        }
        return List.of("sh", "-c", "touch '" + arquivo + "'");
    }
}
