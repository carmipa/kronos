package org.traducao.projeto.traducao.infrastructure.telemetria;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.traducao.projeto.core.io.DiretorioBaseKronos;
import org.traducao.projeto.traducao.domain.TelemetriaTraducao;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * PROPÓSITO DE NEGÓCIO: uma execução {@code BLOQUEADO} não pode zerar no painel um episódio que
 * está traduzido em disco. O portão de obra×contexto recusa o arquivo ANTES de ler a legenda,
 * chamar o LLM ou tocar o cache — o registro sai com zero falas e tempo desprezível. Como a foto
 * canônica é chaveada pelo nome do episódio, deixá-lo vencer destrói a medição do trabalho real.
 *
 * <p>Medido em 2026-08-03 sobre {@code telemetria_execucoes.jsonl} (754 execuções): das 237
 * bloqueadas, <b>48 eram o último registro do seu episódio</b> — 47 do Gundam ZZ e 1 do Char's
 * Counterattack —, e o painel exibia zero para todas, apagando <b>17.813 falas traduzidas</b> de
 * trabalho existente em disco.
 *
 * <h2>Invariantes do domínio</h2>
 * <ul>
 *   <li>Trabalho real (qualquer status ≠ {@code BLOQUEADO}) SEMPRE vence bloqueio na foto.</li>
 *   <li>Entre registros da mesma natureza, o mais recente continua vencendo — retraduzir segue
 *       atualizando a foto, que é o comportamento desejado.</li>
 *   <li>O HISTÓRICO recebe TODA execução, inclusive a bloqueada: a foto responde "como está
 *       agora", o histórico responde "o que aconteceu".</li>
 * </ul>
 *
 * <h2>Comportamento em caso de falha</h2>
 * Se a foto voltar a aceitar bloqueio por cima de trabalho, o painel volta a mostrar zero para
 * episódio traduzido e qualquer média calculada sobre ele fica deprimida.
 */
class BloqueioNaoApagaTrabalhoTelemetriaTest {

    @TempDir
    Path raiz;

    private String baseAnterior;

    @BeforeEach
    void redirecionarRaizOperacional() {
        baseAnterior = System.getProperty(DiretorioBaseKronos.PROPRIEDADE_BASE);
        System.setProperty(DiretorioBaseKronos.PROPRIEDADE_BASE, raiz.toString());
    }

    @AfterEach
    void restaurarRaizOperacional() {
        if (baseAnterior == null) {
            System.clearProperty(DiretorioBaseKronos.PROPRIEDADE_BASE);
        } else {
            System.setProperty(DiretorioBaseKronos.PROPRIEDADE_BASE, baseAnterior);
        }
    }

    private static TelemetriaTraducao trabalho(String episodio, String quando, String status) {
        return new TelemetriaTraducao(
            episodio, "modelo-teste", 300, 285, 0, 60000L,
            List.of(), "AnimeTeste", "S01", quando, "Lore Teste", status, List.of(), null, null);
    }

    /** Bloqueio real: o portão recusa antes de qualquer trabalho, então tudo vem zerado. */
    private static TelemetriaTraducao bloqueio(String episodio, String quando) {
        return new TelemetriaTraducao(
            episodio, "modelo-teste", 0, 0, 0, 0L,
            List.of(), "AnimeTeste", "S01", quando, "Lore Teste", "BLOQUEADO", List.of(), null, null);
    }

    private String foto() throws Exception {
        return Files.readString(raiz.resolve("logs").resolve("telemetria_traducao.json"),
            StandardCharsets.UTF_8);
    }

    @Test
    @DisplayName("bloqueio POSTERIOR nao apaga a traducao que existe em disco")
    void bloqueioPosteriorNaoSobrescreveTrabalho() throws Exception {
        TelemetriaTraducaoAdapter adapter = new TelemetriaTraducaoAdapter(new ObjectMapper());

        adapter.registrarTraducao(trabalho("ZZ_S01E01.ass", "2026-07-26T12:00:00Z", "CONCLUIDO"));
        adapter.registrarTraducao(bloqueio("ZZ_S01E01.ass", "2026-07-31T09:00:00Z"));

        String foto = foto();
        assertTrue(foto.contains("CONCLUIDO"),
            "a foto tem de manter o trabalho real; o bloqueio nao traduziu nada: " + foto);
        assertFalse(foto.contains("BLOQUEADO"),
            "bloqueio POSTERIOR nao pode virar o estado do episodio na foto: " + foto);
        assertTrue(foto.contains("\"falasTraduzidas\":285"),
            "as falas traduzidas nao podem ser zeradas pelo bloqueio: " + foto);
    }

    @Test
    @DisplayName("episodio que SO foi bloqueado aparece como bloqueado")
    void primeiroRegistroBloqueadoEntraNormalmente() throws Exception {
        TelemetriaTraducaoAdapter adapter = new TelemetriaTraducaoAdapter(new ObjectMapper());

        adapter.registrarTraducao(bloqueio("ZZ_S01E02.ass", "2026-07-31T09:00:00Z"));

        assertTrue(foto().contains("BLOQUEADO"),
            "sem trabalho anterior nao ha o que proteger: o bloqueio e a verdade do episodio");
    }

    @Test
    @DisplayName("traducao POSTERIOR a um bloqueio vence normalmente")
    void trabalhoDepoisDeBloqueioAtualizaAFoto() throws Exception {
        TelemetriaTraducaoAdapter adapter = new TelemetriaTraducaoAdapter(new ObjectMapper());

        adapter.registrarTraducao(bloqueio("ZZ_S01E03.ass", "2026-07-26T09:00:00Z"));
        adapter.registrarTraducao(trabalho("ZZ_S01E03.ass", "2026-07-26T10:00:00Z", "PARCIAL"));

        String foto = foto();
        assertTrue(foto.contains("PARCIAL"), "o trabalho posterior tem de virar o estado: " + foto);
        assertFalse(foto.contains("BLOQUEADO"), "o bloqueio anterior nao pode sobreviver: " + foto);
    }

    @Test
    @DisplayName("retraducao continua atualizando a foto — a correcao nao congela o estado")
    void retraducaoRealContinuaVencendo() throws Exception {
        TelemetriaTraducaoAdapter adapter = new TelemetriaTraducaoAdapter(new ObjectMapper());

        adapter.registrarTraducao(trabalho("ZZ_S01E04.ass", "2026-07-26T12:00:00Z", "PARCIAL"));
        adapter.registrarTraducao(trabalho("ZZ_S01E04.ass", "2026-07-31T12:00:00Z", "CONCLUIDO"));

        assertTrue(foto().contains("CONCLUIDO"),
            "proteger contra bloqueio NAO pode impedir a foto de refletir uma retraducao real");
    }

    /**
     * 28/10/2026: a falha que aborta o arquivo antes de gerar saída sai com zeros, e trocava na
     * foto um episódio CONCLUIDO — medido no ZZ S01E16 (382 falas) e no Stink Bomb (402).
     */
    @Test
    @DisplayName("FALHOU zerado nao apaga da foto o trabalho anterior; FALHOU com trabalho, sim")
    void falhaZeradaNaoApagaTrabalho() throws Exception {
        TelemetriaTraducaoAdapter adapter = new TelemetriaTraducaoAdapter(new ObjectMapper());
        adapter.registrarTraducao(trabalho("ZZ_S01E16.ass", "2026-08-07T12:00:00Z", "CONCLUIDO"));

        adapter.registrarTraducao(new TelemetriaTraducao(
            "ZZ_S01E16.ass", "modelo-teste", 0, 0, 0, 0L,
            List.of("LLM caiu"), "AnimeTeste", "S01", "2026-08-08T12:00:00Z", "Lore Teste", "FALHOU",
            List.of(), null, null));
        assertTrue(foto().contains("CONCLUIDO") && !foto().contains("FALHOU"),
            "a falha zerada nao pode zerar o episodio no painel: " + foto());

        adapter.registrarTraducao(trabalho("ZZ_S01E16.ass", "2026-08-09T12:00:00Z", "FALHOU"));
        assertTrue(foto().contains("FALHOU"),
            "CONTROLE: falha que traz linhas e falas e trabalho, e vence como antes");
    }

    @Test
    @DisplayName("o historico registra TODAS as execucoes, inclusive as bloqueadas")
    void historicoNaoPerdeOBloqueio() throws Exception {
        TelemetriaTraducaoAdapter adapter = new TelemetriaTraducaoAdapter(new ObjectMapper());

        adapter.registrarTraducao(trabalho("ZZ_S01E05.ass", "2026-07-26T12:00:00Z", "CONCLUIDO"));
        adapter.registrarTraducao(bloqueio("ZZ_S01E05.ass", "2026-07-31T09:00:00Z"));

        List<String> linhas = Files.readAllLines(
            raiz.resolve("logs").resolve(TelemetriaTraducaoAdapter.NOME_ARQUIVO_HISTORICO),
            StandardCharsets.UTF_8);

        assertEquals(2, linhas.size(),
            "a foto protege o trabalho, mas o historico nao pode perder o bloqueio: "
                + "e ele que responde 'quantas vezes esta obra foi barrada'");
        assertTrue(linhas.get(1).contains("BLOQUEADO"),
            "o bloqueio precisa estar no historico: " + linhas.get(1));
    }
}
