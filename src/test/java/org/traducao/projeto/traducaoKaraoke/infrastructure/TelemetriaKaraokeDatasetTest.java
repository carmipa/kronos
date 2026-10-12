package org.traducao.projeto.traducaoKaraoke.infrastructure;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.traducao.projeto.core.io.DiretorioBaseKronos;
import org.traducao.projeto.traducaoKaraoke.domain.TelemetriaKaraoke;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * PROPÓSITO DE NEGÓCIO: trava o acervo APPEND-ONLY do karaokê — o arquivo que o publicador lê para
 * a fatia existir no dataset público.
 *
 * <h2>Invariantes do domínio congelados aqui</h2>
 * <ul>
 *   <li>Duas execuções ACRESCENTAM; a segunda não substitui a primeira. Sem isso não existe a
 *       pergunta "esta mudança melhorou?", porque o segundo ponto destrói o primeiro.</li>
 *   <li>Uma linha física é um registro — o JSON de cada linha é lido de volta inteiro.</li>
 *   <li>Elemento nulo na lista NÃO vira a linha {@code "null"}: linha malformada num acervo é
 *       defeito, e o publicador que a descartasse estaria aprovando por cegueira.</li>
 *   <li>Escreve sob a raiz operacional redirecionável — nunca no {@code logs/} real durante a
 *       suíte.</li>
 * </ul>
 *
 * <h2>Comportamento em caso de falha</h2>
 * Se o acervo passar a sobrescrever, a série temporal do karaokê some e o dataset volta a
 * responder apenas "quantas execuções houve".
 */
class TelemetriaKaraokeDatasetTest {

    @TempDir
    Path raiz;

    private String baseAnterior;
    private TelemetriaKaraokeDataset acervo;

    @BeforeEach
    void redirecionarRaizOperacional() {
        baseAnterior = System.getProperty(DiretorioBaseKronos.PROPRIEDADE_BASE);
        System.setProperty(DiretorioBaseKronos.PROPRIEDADE_BASE, raiz.toString());
        acervo = new TelemetriaKaraokeDataset();
        acervo.objectMapper = new ObjectMapper();
    }

    @AfterEach
    void restaurarRaizOperacional() {
        if (baseAnterior == null) {
            System.clearProperty(DiretorioBaseKronos.PROPRIEDADE_BASE);
        } else {
            System.setProperty(DiretorioBaseKronos.PROPRIEDADE_BASE, baseAnterior);
        }
    }

    @Test
    @DisplayName("duas execuções do mesmo arquivo ACRESCENTAM — o acervo não é foto")
    void duasExecucoesAcrescentam() throws IOException {
        assertEquals(1, acervo.registrar(List.of(linha("2026-08-20T10:00:00Z", "ep01.ass", 10))));
        assertEquals(1, acervo.registrar(List.of(linha("2026-08-20T11:00:00Z", "ep01.ass", 40))));

        List<String> linhas = lerAcervo();
        assertEquals(2, linhas.size(), "a segunda execucao sobrescreveu a primeira: serie perdida");
        assertTrue(linhas.get(0).contains("\"traduzidas\":10"));
        assertTrue(linhas.get(1).contains("\"traduzidas\":40"));
    }

    @Test
    @DisplayName("cada linha física é um JSON completo, relido com os campos intactos")
    void cadaLinhaEUmRegistroRelegivel() throws IOException {
        acervo.registrar(List.of(linha("2026-08-20T10:00:00Z", "ep01.ass", 33)));

        ObjectMapper mapper = new ObjectMapper();
        TelemetriaKaraoke lida = mapper.readValue(lerAcervo().get(0), TelemetriaKaraoke.class);
        assertEquals("ep01.ass", lida.arquivo());
        assertEquals(33, lida.traduzidas());
        assertEquals("TRADUZIDO", lida.desfechoArquivo());
        assertEquals(List.of("aviso de exemplo"), lida.avisos());
    }

    /**
     * Caso-controle da regra "guarda que descarta o que não entende aprova por cegueira": a lista
     * chega com um nulo no meio e o acervo tem de gravar SÓ as duas linhas válidas, sem produzir a
     * linha literal {@code null} — que o publicador leria como registro e descartaria calado.
     */
    @Test
    @DisplayName("elemento nulo na lista é pulado, e nunca vira a linha literal null")
    void elementoNuloNaoViraLinha() throws IOException {
        List<TelemetriaKaraoke> comNulo = new ArrayList<>();
        comNulo.add(linha("2026-08-20T10:00:00Z", "ep01.ass", 1));
        comNulo.add(null);
        comNulo.add(linha("2026-08-20T10:00:00Z", "ep02.ass", 2));

        assertEquals(2, acervo.registrar(comNulo));
        List<String> linhas = lerAcervo();
        assertEquals(2, linhas.size());
        assertFalse(linhas.contains("null"), "linha literal 'null' gravada no acervo");
    }

    @Test
    @DisplayName("lista vazia não cria arquivo — e devolve zero, não um número inventado")
    void listaVaziaNaoCriaArquivo() {
        assertEquals(0, acervo.registrar(List.of()));
        assertEquals(0, acervo.registrar(null));
        assertFalse(Files.exists(arquivo()), "acervo criado sem nenhuma linha a gravar");
    }

    private List<String> lerAcervo() throws IOException {
        return Arrays.stream(Files.readString(arquivo(), StandardCharsets.UTF_8).split("\\R"))
            .filter(l -> !l.isBlank()).toList();
    }

    private Path arquivo() {
        return raiz.resolve("logs").resolve(TelemetriaKaraokeDataset.NOME_ARQUIVO);
    }

    private static TelemetriaKaraoke linha(String quando, String arquivo, int traduzidas) {
        return new TelemetriaKaraoke(
            quando, TelemetriaKaraoke.Origem.EXECUCAO.name(), arquivo,
            TelemetriaKaraoke.DesfechoDoArquivo.TRADUZIDO.name(), null,
            "COMPLETA", null, "eight_six", "86 (Eighty-Six)", "abc123", "aya-expanse-8b",
            false, "DISPONIVEL", 1_000L, 1,
            100, 10, 5, 0, 50, 5, traduzidas, 0, 3, 0, 0, 0, 0, "NAO_NECESSARIO",
            List.of("aviso de exemplo"));
    }
}
