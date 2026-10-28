package org.traducao.projeto.traducao.application;

import org.springframework.stereotype.Component;
import org.traducao.projeto.qualidadeTraducao.application.IsoladorQuebraDialogo;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * PROPÓSITO DE NEGÓCIO: nas legendas de fansub, a narração em duas linhas costuma vir
 * escrita como {@code {\i1}primeira{\i}\N{\i1}segunda} — o itálico é DESLIGADO e RELIGADO
 * em volta da quebra visual, onde não há texto para formatar. A renderização é idêntica à
 * de {@code {\i1}primeira\Nsegunda}, mas o pipeline vê QUATRO marcadores em vez de um, e
 * três deles caem no meio da frase. Este componente remove essa redundância ANTES do
 * mascaramento, sem alterar o que o espectador vê.
 *
 * <p>Medido nos logs em 2026-07-31: das 208 corrupções de {@code [[TAGn]]} registradas,
 * <b>141 (67,8%) eram falas com exatamente 4 marcadores</b> neste formato, e em 84% dos
 * casos o modelo não devolveu marcador NENHUM. O itálico responde por 643 das tags
 * envolvidas; {@code \pos} aparece em 17. É a mesma causa que motivou o
 * {@link IsoladorQuebraDialogo} para o {@code \N}: marcador no meio da frase se perde
 * porque o português reordena as palavras.
 *
 * <h2>Invariantes do domínio</h2>
 * <ul>
 *   <li>NÃO remove itálico: remove o par desliga/religa que envolve uma quebra
 *       {@code \N}. O envoltório de abertura permanece intacto, com os demais overrides
 *       que o acompanhem ({@code \an8}, {@code \pos}, cor).</li>
 *   <li>Só age quando entre o desligamento e o religamento houver <b>apenas</b> a quebra —
 *       se houver texto visível no meio, aquele trecho está fora do itálico de propósito e
 *       nada é tocado.</li>
 *   <li>Itálico de ÊNFASE ({@code I {\i1}will{\i0} fight}) nunca é alterado: ali o par
 *       delimita palavra, não quebra.</li>
 *   <li>Operação idempotente e sem estado; só JDK + Spring.</li>
 * </ul>
 *
 * <h2>Comportamento em caso de falha</h2>
 * Texto {@code null} ou sem o padrão é devolvido intacto. Não lança.
 */
@Component
public class SimplificadorItalicoRedundante {

    /**
     * Desliga o itálico, quebra a linha e religa — sem nada visível no meio.
     * {@code \i} sem argumento volta ao padrão do estilo; {@code \i0} desliga explicitamente.
     * Ambos são equivalentes aqui, porque nenhum texto é renderizado entre eles.
     */
    private static final Pattern DESLIGA_QUEBRA_RELIGA = Pattern.compile(
        "\\{\\\\i[0]?\\}\\s*(\\\\N)\\s*\\{\\\\i1\\}");

    /** Mesma redundância quando o desligamento traz outros overrides junto. */
    private static final Pattern DESLIGA_QUEBRA_RELIGA_COMPOSTO = Pattern.compile(
        "\\{[^{}]*\\\\i[0]?(?![0-9])[^{}]*\\}\\s*(\\\\N)\\s*\\{[^{}]*\\\\i1(?![0-9])[^{}]*\\}");

    /**
     * PROPÓSITO DE NEGÓCIO: devolve a fala sem o par de itálico redundante ao redor da
     * quebra, pronta para o mascaramento ver menos marcadores.
     *
     * <p>INVARIANTES DO DOMÍNIO: preserva a quebra {@code \N} e todo o texto visível;
     * remove apenas os dois blocos de tag que a cercavam quando ambos só tratavam itálico.
     * O caso composto (tag com outros overrides junto) é tratado de forma conservadora: só
     * simplifica quando o bloco que religa não traz nada além do itálico, porque um
     * {@code \pos} no religamento pode ter efeito visual próprio.
     *
     * <p>COMPORTAMENTO EM CASO DE FALHA: {@code null} devolve {@code null}; texto sem o
     * padrão volta idêntico (comparável por {@code equals}).
     *
     * @param original fala como veio da legenda
     * @return a fala sem a redundância; a MESMA instância quando nada muda
     */
    public String simplificar(String original) {
        if (original == null || !original.contains("\\N")) {
            return original;
        }
        Matcher simples = DESLIGA_QUEBRA_RELIGA.matcher(original);
        String resultado = simples.replaceAll("$1");
        if (!resultado.equals(original)) {
            return resultado;
        }
        // Só tenta a forma composta se a simples não pegou — e apenas quando o bloco de
        // religamento é exclusivamente itálico, para não descartar override com efeito.
        Matcher composto = DESLIGA_QUEBRA_RELIGA_COMPOSTO.matcher(original);
        StringBuilder saida = new StringBuilder();
        int ultimo = 0;
        boolean mudou = false;
        while (composto.find()) {
            String religa = original.substring(composto.end() - 1, composto.end());
            String blocoReliga = trechoDoUltimoBloco(original, composto.end());
            if (!apenasItalico(blocoReliga)) {
                continue;
            }
            // O bloco que DESLIGA pode trazer outros overrides junto ({\i0\c&H00FFFF&}): só o
            // \i0 sai, o resto fica. Antes o bloco inteiro sumia e a cor (ou o \a8) se perdia
            // da linha seguinte (auditoria de 28/10/2026).
            String blocoDesliga = original.substring(composto.start(), original.indexOf('}', composto.start()) + 1);
            String restoDoDesliga = blocoDesliga.substring(1, blocoDesliga.length() - 1)
                .replaceAll("\\\\i[0]?(?![0-9])", "");
            saida.append(original, ultimo, composto.start());
            if (!restoDoDesliga.isBlank()) {
                saida.append('{').append(restoDoDesliga).append('}');
            }
            saida.append("\\N");
            ultimo = composto.end();
            mudou = true;
            // 'religa' existe só para deixar explícito o limite consumido pelo casamento.
            assert religa != null;
        }
        if (!mudou) {
            return original;
        }
        saida.append(original.substring(ultimo));
        return saida.toString();
    }

    /**
     * PROPÓSITO DE NEGÓCIO: informa se a fala tem a redundância — usado pela telemetria
     * para contar quantas falas foram simplificadas sem repetir a regex fora daqui.
     *
     * <p>INVARIANTES DO DOMÍNIO: mesma condição de {@link #simplificar}.
     *
     * <p>COMPORTAMENTO EM CASO DE FALHA: {@code null} devolve {@code false}.
     */
    public boolean temRedundancia(String original) {
        return original != null && !simplificar(original).equals(original);
    }

    /** Último bloco {@code {...}} que termina em {@code fim}. */
    private String trechoDoUltimoBloco(String texto, int fim) {
        int abre = texto.lastIndexOf('{', fim - 1);
        return abre < 0 ? "" : texto.substring(abre, fim);
    }

    /** Bloco que só contém tags de itálico (nada de posição, cor, fonte ou efeito). */
    private boolean apenasItalico(String bloco) {
        String miolo = bloco.replaceAll("^\\{|\\}$", "");
        return !miolo.isBlank() && miolo.replaceAll("\\\\i[01]?(?![0-9])", "").isBlank();
    }
}
