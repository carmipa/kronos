package org.traducao.projeto.trocaTipoLegenda.application;

import org.traducao.projeto.trocaTipoLegenda.domain.exceptions.TrocaTipoLegendaException;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

/**
 * PROPÓSITO DE NEGÓCIO: diz quais legendas a 1.3 vai tratar numa pasta e quantas ficaram de fora
 * por estarem em subpastas — para a tela avisar em vez de mostrar "0 arquivos" sobre uma pasta
 * cheia de legendas um nível abaixo.
 *
 * <p>INVARIANTES DO DOMÍNIO: a 1.3 trata só a pasta escolhida (subpastas não entram, por desenho:
 * gravar em árvore inteira é outra decisão); a contagem das subpastas desce até
 * {@value #PROFUNDIDADE_SUBPASTAS} níveis e só conta, nunca lê nem grava.
 *
 * <p>COMPORTAMENTO EM CASO DE FALHA: pasta que não lista lança {@link TrocaTipoLegendaException};
 * subpasta que não dá para percorrer devolve {@code -1} (não conferido), nunca {@code 0}.
 */
final class LegendasDaPasta {

    static final int PROFUNDIDADE_SUBPASTAS = 3;

    private LegendasDaPasta() {
    }

    static List<Path> listar(Path dir) {
        try (Stream<Path> stream = Files.list(dir)) {
            return stream
                .filter(Files::isRegularFile)
                .filter(LegendasDaPasta::ehLegenda)
                .sorted()
                .toList();
        } catch (IOException e) {
            throw new TrocaTipoLegendaException("Falha ao listar arquivos de legenda na pasta: " + dir, e);
        }
    }

    static int contarEmSubpastas(Path dir) {
        try (Stream<Path> stream = Files.walk(dir, PROFUNDIDADE_SUBPASTAS)) {
            return (int) stream
                .filter(p -> !dir.equals(p.getParent()))
                .filter(Files::isRegularFile)
                .filter(LegendasDaPasta::ehLegenda)
                .count();
        } catch (IOException | UncheckedIOException | SecurityException e) {
            return -1;
        }
    }

    private static boolean ehLegenda(Path p) {
        String nome = p.toString().toLowerCase();
        return nome.endsWith(".ass") || nome.endsWith(".ssa");
    }
}
