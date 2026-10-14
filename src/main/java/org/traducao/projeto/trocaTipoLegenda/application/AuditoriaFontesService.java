package org.traducao.projeto.trocaTipoLegenda.application;

import org.springframework.stereotype.Service;
import org.traducao.projeto.core.texto.FonteVietnamitaLegada;
import org.traducao.projeto.legenda.domain.EventoLegenda;
import org.traducao.projeto.trocaTipoLegenda.domain.AuditoriaFonteInfo;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * PROPÓSITO DE NEGÓCIO: o diagnóstico e a troca da 1.3 — acha a fonte que desenha a letra errada
 * (vietnamita de codificação antiga) no cabeçalho e nas falas de uma legenda ASS e a troca por Arial.
 *
 * <p>INVARIANTES DO DOMÍNIO: no cabeçalho só a coluna Fontname muda; nas falas só o nome depois do
 * {@code \fn}; o que é "antiga" vem da regra única do kernel, a mesma que reprova na 4.1 e no remux.
 *
 * <p>COMPORTAMENTO EM CASO DE FALHA: cabeçalho ou falas ausentes devolvem resultado vazio, sem lançar.
 */
@Service
public class AuditoriaFontesService {

    /**
     * A fonte segura no lugar da vietnamita antiga. Quem decide QUAL é antiga é a regra única do
     * kernel ({@link FonteVietnamitaLegada}); até 14/10/2026 esta classe tinha uma lista de seis nomes
     * exatos, que deixava passar {@code .VnTime} e {@code VNI-Times}, enquanto a 4.1 tinha prefixos que
     * deixavam passar {@code VnBook-Antiqua} — duas cópias da mesma verdade, cada uma cega numa ponta.
     */
    private static final String FONTE_SEGURA = "Arial";

    /** Rótulo da linha do relatório para fonte escrita com {@code \fn} dentro das falas, fora do cabeçalho. */
    static final String ESTILO_FN_NAS_FALAS = "\\fn nas falas";

    public record ResultadoSubstituicaoFalas(List<EventoLegenda> eventos, int substituicoes) {}

    public record ResultadoSubstituicaoCabecalho(String cabecalho, int substituicoes) {}

    public List<AuditoriaFonteInfo> analisarCabecalho(String cabecalho) {
        if (cabecalho == null || cabecalho.isBlank()) {
            return Collections.emptyList();
        }

        String[] linhas = cabecalho.split("\r\n|\n", -1);
        int indiceStyles = -1;
        for (int i = 0; i < linhas.length; i++) {
            if (ehSecaoEstilos(linhas[i].trim())) {
                indiceStyles = i;
                break;
            }
        }

        // Se não houver a seção [V4+ Styles], não há estilos para analisar
        if (indiceStyles < 0) {
            return Collections.emptyList();
        }

        int indiceFormat = -1;
        for (int i = indiceStyles + 1; i < linhas.length; i++) {
            String linha = linhas[i].trim();
            // Se encontrar outra seção antes de achar o Format, encerra a busca
            if (linha.startsWith("[") && linha.endsWith("]")) {
                break;
            }
            if (linha.startsWith("Format:")) {
                indiceFormat = i;
                break;
            }
        }

        if (indiceFormat < 0) {
            return Collections.emptyList();
        }

        // Parseando colunas de formato
        String linhaFormat = linhas[indiceFormat];
        String dadosFormat = linhaFormat.substring(linhaFormat.indexOf(':') + 1).trim();
        List<String> colunas = Arrays.stream(dadosFormat.split(","))
            .map(String::trim)
            .toList();

        int indexName = indiceColuna(colunas, "Name");
        int indexFontname = indiceColuna(colunas, "Fontname");

        if (indexName < 0 || indexFontname < 0) {
            // Formato inválido ou inesperado, sem as colunas obrigatórias
            return Collections.emptyList();
        }

        List<AuditoriaFonteInfo> resultado = new ArrayList<>();
        int numColunas = colunas.size();

        for (int i = indiceFormat + 1; i < linhas.length; i++) {
            String linha = linhas[i].trim();
            // Se começar outra seção, a seção de estilos acabou
            if (linha.startsWith("[") && linha.endsWith("]")) {
                break;
            }
            if (linha.startsWith("Style:")) {
                String dadosEstilo = linha.substring(linha.indexOf(':') + 1);
                if (dadosEstilo.startsWith(" ")) {
                    dadosEstilo = dadosEstilo.substring(1);
                }
                
                // Dividir por vírgula limitando pelo número de colunas
                String[] partes = dadosEstilo.split(",", numColunas);
                if (partes.length >= numColunas) {
                    String nomeEstilo = partes[indexName].trim();
                    String nomeFonte = partes[indexFontname].trim();
                    
                    String fonteSugerida = fonteSugerida(nomeFonte);
                    boolean problematica = !fonteSugerida.equals(nomeFonte);
                    
                    resultado.add(new AuditoriaFonteInfo(nomeEstilo, nomeFonte, fonteSugerida, problematica));
                }
            }
        }

        return resultado;
    }

    public ResultadoSubstituicaoCabecalho substituirFontesProblematicas(String cabecalho) {
        return substituirFontes(cabecalho, false);
    }

    public ResultadoSubstituicaoCabecalho substituirTodasFontesPorArial(String cabecalho) {
        return substituirFontes(cabecalho, true);
    }

    private ResultadoSubstituicaoCabecalho substituirFontes(String cabecalho, boolean forcarArial) {
        if (cabecalho == null || cabecalho.isBlank()) {
            return new ResultadoSubstituicaoCabecalho(cabecalho, 0);
        }

        String quebraLinha = cabecalho.contains("\r\n") ? "\r\n" : "\n";
        String[] linhas = cabecalho.split("\r\n|\n", -1);
        int indiceStyles = -1;
        for (int i = 0; i < linhas.length; i++) {
            if (ehSecaoEstilos(linhas[i].trim())) {
                indiceStyles = i;
                break;
            }
        }
        if (indiceStyles < 0) {
            return new ResultadoSubstituicaoCabecalho(cabecalho, 0);
        }

        int indiceFormat = -1;
        for (int i = indiceStyles + 1; i < linhas.length; i++) {
            String linha = linhas[i].trim();
            if (linha.startsWith("[") && linha.endsWith("]")) {
                break;
            }
            if (linha.regionMatches(true, 0, "Format:", 0, "Format:".length())) {
                indiceFormat = i;
                break;
            }
        }
        if (indiceFormat < 0) {
            return new ResultadoSubstituicaoCabecalho(cabecalho, 0);
        }

        String linhaFormat = linhas[indiceFormat];
        String dadosFormat = linhaFormat.substring(linhaFormat.indexOf(':') + 1).trim();
        List<String> colunas = Arrays.stream(dadosFormat.split(","))
            .map(String::trim)
            .toList();
        int indexFontname = indiceColuna(colunas, "Fontname");
        if (indexFontname < 0) {
            return new ResultadoSubstituicaoCabecalho(cabecalho, 0);
        }

        int numColunas = colunas.size();
        int substituicoes = 0;
        for (int i = indiceFormat + 1; i < linhas.length; i++) {
            String linhaOriginal = linhas[i];
            String linha = linhaOriginal.trim();
            if (linha.startsWith("[") && linha.endsWith("]")) {
                break;
            }
            if (!linha.regionMatches(true, 0, "Style:", 0, "Style:".length())) {
                continue;
            }

            int indiceDoisPontos = linhaOriginal.indexOf(':');
            if (indiceDoisPontos < 0) {
                continue;
            }
            String prefixo = linhaOriginal.substring(0, indiceDoisPontos + 1);
            String dadosEstilo = linhaOriginal.substring(indiceDoisPontos + 1);
            String espacoInicial = "";
            if (dadosEstilo.startsWith(" ")) {
                espacoInicial = " ";
                dadosEstilo = dadosEstilo.substring(1);
            }

            String[] partes = dadosEstilo.split(",", numColunas);
            if (partes.length < numColunas) {
                continue;
            }

            String fonteAtual = partes[indexFontname].trim();
            String fonteNova = forcarArial ? "Arial" : fonteSugerida(fonteAtual);
            if (!fonteNova.equals(fonteAtual)) {
                partes[indexFontname] = fonteNova;
                linhas[i] = prefixo + espacoInicial + String.join(",", partes);
                substituicoes++;
            }
        }

        return new ResultadoSubstituicaoCabecalho(String.join(quebraLinha, linhas), substituicoes);
    }

    /**
     * PROPÓSITO DE NEGÓCIO: acha a fonte vietnamita antiga escrita com {@code \fn} DENTRO das falas,
     * que a troca do cabeçalho não alcança — sem isto, a reprovação da 4.1 e do remux pediria uma
     * troca que a 1.3 não faz.
     *
     * <p>INVARIANTES DO DOMÍNIO: só linhas {@code Dialogue} (as que vão à tela); uma linha por família,
     * na ordem em que aparece; sempre {@code problematica}, com Arial como sugerida.
     *
     * <p>COMPORTAMENTO EM CASO DE FALHA: lista nula ou sem {@code \fn} legado devolve lista vazia.
     */
    public List<AuditoriaFonteInfo> analisarFontesNasFalas(List<EventoLegenda> eventos) {
        Set<String> familias = new LinkedHashSet<>();
        for (EventoLegenda evento : eventos == null ? List.<EventoLegenda>of() : eventos) {
            if (evento != null && evento.isDialogo()) {
                FonteVietnamitaLegada.fontesInline(evento.texto()).stream()
                    .filter(FonteVietnamitaLegada::ehLegada)
                    .forEach(familias::add);
            }
        }
        List<AuditoriaFonteInfo> resultado = new ArrayList<>();
        for (String familia : familias) {
            resultado.add(new AuditoriaFonteInfo(ESTILO_FN_NAS_FALAS, familia, FONTE_SEGURA, true));
        }
        return resultado;
    }

    /**
     * PROPÓSITO DE NEGÓCIO: troca, nas falas, cada {@code \fn} de fonte vietnamita antiga por Arial.
     *
     * <p>INVARIANTES DO DOMÍNIO: só linhas {@code Dialogue}; texto visível, outras tags e {@code \fn}
     * de fonte comum ficam byte a byte; a contagem é por ocorrência trocada.
     *
     * <p>COMPORTAMENTO EM CASO DE FALHA: lista nula volta vazia com zero substituições; nunca lança.
     */
    public ResultadoSubstituicaoFalas substituirFontesNasFalas(List<EventoLegenda> eventos) {
        List<EventoLegenda> saida = new ArrayList<>();
        int substituicoes = 0;
        for (EventoLegenda evento : eventos == null ? List.<EventoLegenda>of() : eventos) {
            if (evento == null || !evento.isDialogo()) {
                saida.add(evento);
                continue;
            }
            long legadas = FonteVietnamitaLegada.fontesInline(evento.texto()).stream()
                .filter(FonteVietnamitaLegada::ehLegada).count();
            if (legadas == 0) {
                saida.add(evento);
                continue;
            }
            saida.add(evento.comTexto(FonteVietnamitaLegada.trocarFontesInlineLegadas(evento.texto(), FONTE_SEGURA)));
            substituicoes += (int) legadas;
        }
        return new ResultadoSubstituicaoFalas(saida, substituicoes);
    }

    private boolean ehSecaoEstilos(String linha) {
        return linha.equalsIgnoreCase("[V4+ Styles]") || linha.equalsIgnoreCase("[V4 Styles]");
    }

    private int indiceColuna(List<String> colunas, String nome) {
        for (int i = 0; i < colunas.size(); i++) {
            if (colunas.get(i).equalsIgnoreCase(nome)) {
                return i;
            }
        }
        return -1;
    }

    /**
     * PROPÓSITO DE NEGÓCIO: diz qual fonte o estilo deve usar — Arial no lugar da vietnamita antiga,
     * a própria fonte em qualquer outro caso.
     *
     * <p>INVARIANTES DO DOMÍNIO: a decisão é da regra única {@link FonteVietnamitaLegada#ehLegada}.
     *
     * <p>COMPORTAMENTO EM CASO DE FALHA: nome nulo devolve texto vazio.
     */
    private String fonteSugerida(String nomeFonte) {
        if (nomeFonte == null) {
            return "";
        }
        return FonteVietnamitaLegada.ehLegada(nomeFonte) ? FONTE_SEGURA : nomeFonte;
    }
}
