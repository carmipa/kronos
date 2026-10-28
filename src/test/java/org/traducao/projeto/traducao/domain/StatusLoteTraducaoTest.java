package org.traducao.projeto.traducao.domain;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * Cobre a consolidação do status do lote a partir dos status por arquivo —
 * o núcleo do fix "não mostrar sucesso quando houve falhas".
 */
class StatusLoteTraducaoTest {

    private static ResultadoTraducaoArquivo comStatus(StatusArquivoTraducao s) {
        return new ResultadoTraducaoArquivo(null, "ep.ass", "DanMachi", 10, 2, 8, 0, s, 0L, 0, List.of());
    }

    @Test
    void todosConcluidosOuParciaisViramConcluido() {
        assertEquals(StatusLoteTraducao.CONCLUIDO, StatusLoteTraducao.consolidar(List.of(
            comStatus(StatusArquivoTraducao.CONCLUIDO), comStatus(StatusArquivoTraducao.PARCIAL))));
    }

    @Test
    void misturaViraConcluidoComFalhas() {
        assertEquals(StatusLoteTraducao.CONCLUIDO_COM_FALHAS, StatusLoteTraducao.consolidar(List.of(
            comStatus(StatusArquivoTraducao.CONCLUIDO),
            comStatus(StatusArquivoTraducao.FALHOU),
            comStatus(StatusArquivoTraducao.BLOQUEADO))));
    }

    @Test
    void todasFalhasViramFalhou() {
        assertEquals(StatusLoteTraducao.FALHOU, StatusLoteTraducao.consolidar(List.of(
            comStatus(StatusArquivoTraducao.FALHOU), comStatus(StatusArquivoTraducao.BLOQUEADO))));
    }

    @Test
    void loteVazioViraFalhou() {
        assertEquals(StatusLoteTraducao.FALHOU, StatusLoteTraducao.consolidar(List.of()));
        assertEquals(StatusLoteTraducao.FALHOU, StatusLoteTraducao.consolidar(null));
    }

    @Test
    void loteInteiramenteBloqueadoViraConcluido() {
        // Rerun de um lote 100% já traduzido: todos BLOQUEADO. Nada falhou -> CONCLUIDO,
        // não FALHOU (BLOQUEADO é "já pronto", não é falha).
        assertEquals(StatusLoteTraducao.CONCLUIDO, StatusLoteTraducao.consolidar(List.of(
            comStatus(StatusArquivoTraducao.BLOQUEADO), comStatus(StatusArquivoTraducao.BLOQUEADO))));
    }

    /**
     * 28/10/2026: BLOQUEADO também vem da guarda obra×contexto (lore errada no combo). Nada foi
     * traduzido, e o lote saía CONCLUIDO com banner verde e som de sucesso.
     */
    @Test
    void loteBloqueadoPorObraNaoEhConcluido() {
        List<ResultadoTraducaoArquivo> dois = List.of(
            comStatus(StatusArquivoTraducao.BLOQUEADO), comStatus(StatusArquivoTraducao.BLOQUEADO));
        assertEquals(StatusLoteTraducao.BLOQUEADO, StatusLoteTraducao.consolidar(dois, 2));
        assertEquals(StatusLoteTraducao.CONCLUIDO_COM_FALHAS, StatusLoteTraducao.consolidar(List.of(
            comStatus(StatusArquivoTraducao.CONCLUIDO), comStatus(StatusArquivoTraducao.BLOQUEADO)), 1),
            "um traduzido e um bloqueado por obra: houve falha");
        assertEquals(StatusLoteTraducao.CONCLUIDO, StatusLoteTraducao.consolidar(dois, 0),
            "CONTROLE: bloqueio por arquivo ja traduzido continua neutro");
        assertEquals(StatusLoteTraducao.BLOQUEADO, StatusLoteTraducao.consolidar(List.of(
            comStatus(StatusArquivoTraducao.BLOQUEADO), comStatus(StatusArquivoTraducao.BLOQUEADO)), 1),
            "um ja traduzido (neutro) e um bloqueado por obra: o que havia a fazer foi recusado");
        assertFalse(StatusLoteTraducao.BLOQUEADO.getRotulo().toUpperCase().startsWith("CONCLU"),
            "a tela trata como sucesso todo rotulo que comeca por CONCLU");
    }

    @Test
    void concluidoComBloqueadoViraConcluido() {
        // Um arquivo novo traduzido + um já pronto (bloqueado): não há falha -> CONCLUIDO,
        // não CONCLUIDO_COM_FALHAS.
        assertEquals(StatusLoteTraducao.CONCLUIDO, StatusLoteTraducao.consolidar(List.of(
            comStatus(StatusArquivoTraducao.CONCLUIDO), comStatus(StatusArquivoTraducao.BLOQUEADO))));
    }
}
