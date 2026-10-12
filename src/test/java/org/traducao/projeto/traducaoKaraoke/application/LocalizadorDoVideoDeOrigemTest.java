package org.traducao.projeto.traducaoKaraoke.application;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * PROPÓSITO DE NEGÓCIO: a 4.1 acha sozinha o vídeo de onde a legenda saiu, para ler as fontes dele
 * — sem campo novo na tela.
 *
 * <h2>Invariantes do domínio congelados aqui</h2>
 * <ul>
 *   <li>Os dois arranjos do acervo: legendas numa pasta irmã dos vídeos, e o do Unicorn
 *       ({@code obra/texte/traducao_ptbr} com os vídeos em {@code obra/Season 1}).</li>
 *   <li>Os sufixos da esteira ({@code _Track4}, {@code _PT-BR}, {@code .parcial}, {@code _PTBR})
 *       não separam legenda e vídeo; o vídeo de origem vence o publicado.</li>
 *   <li><b>Fronteira (A1):</b> o episódio VIZINHO tem quase o mesmo nome e nunca é escolhido; dois
 *       candidatos iguais são ambiguidade, não sorteio.</li>
 * </ul>
 *
 * <h2>Comportamento em caso de falha</h2>
 * Sem vídeo, a resposta traz o motivo — e a fonte fica NÃO VERIFICADA lá adiante.
 */
class LocalizadorDoVideoDeOrigemTest {

    @TempDir
    Path raiz;

    private Path arquivo(Path p) throws IOException {
        Files.createDirectories(p.getParent());
        return Files.writeString(p, "x");
    }

    @Test
    @DisplayName("legenda extraída e traduzida acha o MKV de origem na pasta irmã")
    void achaNaPastaIrma() throws IOException {
        Path video = arquivo(raiz.resolve("Obra/Obra - S01E01.mkv"));
        arquivo(raiz.resolve("Obra/Obra - S01E02.mkv"));
        Path legenda = arquivo(raiz.resolve("Obra/legendas_traduzidas/Obra - S01E01_Track4_PT-BR.ass"));

        LocalizadorDoVideoDeOrigem.Localizacao achado = LocalizadorDoVideoDeOrigem.localizar(legenda);

        assertEquals(video, achado.video(), () -> "motivo: " + achado.motivo());
        assertNull(achado.motivo());
    }

    @Test
    @DisplayName("arranjo do Unicorn: legendas dois níveis abaixo e vídeos numa pasta de temporada")
    void arranjoDoUnicorn() throws IOException {
        Path video = arquivo(raiz.resolve("Unicorn/Gundam Unicorn Season 1/Mobile Suit Gundam Unicorn Re0096 - S01E01.mkv"));
        Path legenda = arquivo(raiz.resolve("Unicorn/texte/traducao_ptbr/Mobile Suit Gundam Unicorn Re0096 - S01E01.ass"));

        assertEquals(video, LocalizadorDoVideoDeOrigem.localizar(legenda).video());
    }

    @Test
    @DisplayName("o vídeo de origem vence o publicado _PTBR; só o publicado existindo, ele serve")
    void origemVenceOPublicado() throws IOException {
        Path origem = arquivo(raiz.resolve("Obra/Obra - S01E03.mkv"));
        Path publicado = arquivo(raiz.resolve("Obra/Obra - S01E03_PTBR.mkv"));
        Path legenda = arquivo(raiz.resolve("Obra/traducao_ptbr/Obra - S01E03.ass"));

        assertEquals(origem, LocalizadorDoVideoDeOrigem.localizar(legenda).video());

        Files.delete(origem);
        assertEquals(publicado, LocalizadorDoVideoDeOrigem.localizar(legenda).video());
    }

    /** Fronteira A1: o vizinho difere em um dígito — o nome parecido não basta. */
    @Test
    @DisplayName("fronteira: o episódio vizinho nunca é escolhido; sem o próprio, a resposta é o motivo")
    void vizinhoNuncaEhEscolhido() throws IOException {
        arquivo(raiz.resolve("Obra/Obra - S01E11.mkv"));
        arquivo(raiz.resolve("Obra/Obra - S01E01 v2.mkv"));
        Path legenda = arquivo(raiz.resolve("Obra/traducao_ptbr/Obra - S01E01.ass"));

        LocalizadorDoVideoDeOrigem.Localizacao achado = LocalizadorDoVideoDeOrigem.localizar(legenda);

        assertNull(achado.video(), () -> "escolheu o video de outro episodio: " + achado.video());
        assertTrue(achado.motivo().startsWith("nenhum vídeo .mkv com o nome da legenda"), achado.motivo());
    }

    @Test
    @DisplayName("dois vídeos com o mesmo nome em pastas diferentes: ambiguidade, nenhum escolhido")
    void ambiguidadeNaoEscolhe() throws IOException {
        arquivo(raiz.resolve("Obra/BD/Obra - S01E04.mkv"));
        arquivo(raiz.resolve("Obra/TV/Obra - S01E04.mkv"));
        Path legenda = arquivo(raiz.resolve("Obra/traducao_ptbr/Obra - S01E04.ass"));

        LocalizadorDoVideoDeOrigem.Localizacao achado = LocalizadorDoVideoDeOrigem.localizar(legenda);

        assertNull(achado.video());
        assertTrue(achado.motivo().startsWith("mais de um vídeo"), achado.motivo());
    }

    @Test
    @DisplayName("identidade: sufixos da esteira, extensão, maiúsculas e separadores não contam")
    void identidadeIgnoraSufixosDaEsteira() {
        String esperado = LocalizadorDoVideoDeOrigem.identidade("Obra - S01E01.mkv");
        assertEquals(esperado, LocalizadorDoVideoDeOrigem.identidade("Obra - S01E01_Track4_PT-BR.ass"));
        assertEquals(esperado, LocalizadorDoVideoDeOrigem.identidade("obra_-_s01e01.parcial.ass"));
        assertEquals(esperado, LocalizadorDoVideoDeOrigem.identidade("Obra - S01E01_PTBR.mkv"));
        assertTrue(!esperado.equals(LocalizadorDoVideoDeOrigem.identidade("Obra - S01E11.mkv")),
            "o episodio 11 nao pode ter a identidade do 01");
    }
}
