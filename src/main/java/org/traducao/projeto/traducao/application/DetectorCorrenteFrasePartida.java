package org.traducao.projeto.traducao.application;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * PROPÓSITO DE NEGÓCIO: numa legenda, uma frase longa é partida entre eventos consecutivos
 * — cada evento tem seu tempo na tela, mas gramaticalmente são uma frase só. Traduzidos um
 * por chamada, o LLM não vê a frase inteira e trata a segunda metade como frase nova:
 * {@code at} vira "Até", {@code to usher in} vira "Para inaugurar", {@code in which} vira
 * "Em que". Este detector agrupa essas CORRENTES de eventos para que sigam ao LLM na MESMA
 * chamada, onde a co-ocorrência no contexto resolve a regência.
 *
 * <p>Medido no acervo Unicorn (22 episódios, 2026-07-29): 706 ligações formando 583
 * correntes — 486 de 2 eventos, 73 de 3, 23 de 4 e 1 de 6. Traduzir a corrente junta derruba
 * a maiúscula indevida de 93,8% para ~13% das linhas de continuação e o conector convertido
 * de 29,7% para ~1%. Corrente LONGA sai igual ou melhor que corrente curta, então não há teto.
 *
 * <h2>Invariantes do domínio</h2>
 * <ul>
 *   <li>Só liga quando o anterior termina SEM pontuação final E o seguinte começa em
 *       minúscula, ambos medidos com as tags ASS removidas.</li>
 *   <li>Cartão (texto todo em maiúsculas — título de episódio, placa) NUNCA abre corrente.
 *       Sem este filtro, 20 dos 727 pares brutos do Unicorn seriam títulos colados à
 *       narração seguinte: uma regressão, não um conserto.</li>
 *   <li>O que não entra em corrente é fatiado pelo tamanho de lote configurado, exatamente
 *       como antes — o comportamento fora das correntes fica byte-idêntico.</li>
 *   <li>Classe sem estado; só JDK + Spring. Não conhece LLM, cache nem legenda.</li>
 * </ul>
 *
 * <h2>Comportamento em caso de falha</h2>
 * Lista nula ou vazia devolve lista vazia. A ORDEM da lista não prova adjacência: a lista é a
 * das falas pendentes, e dela saem as que vieram do cache, as repetidas e as mantidas por fonte
 * em português. Até 25/09/2026 este comentário afirmava que isso "nunca junta falas não
 * adjacentes" — era falso: com a fala do meio fora, as duas vizinhas ficavam coladas na lista. Por
 * isso {@link #agrupar(List, int, java.util.function.BiPredicate)} recebe do chamador quem é
 * vizinho no DOCUMENTO.
 */
@Component
public class DetectorCorrenteFrasePartida {

    /** Tags de estilo {@code {...}} e escapes estruturais — não contam como texto visível. */
    private static final Pattern PADRAO_TAG = Pattern.compile("\\{[^{}]*}|\\\\[Nnh]");
    /** Pontuação que FECHA a frase: se o evento termina nela, não há continuação. */
    private static final String PONTUACAO_FINAL = ".?!…:;—-\"'»)]}";

    /**
     * PROPÓSITO DE NEGÓCIO: agrupa os textos em lotes, mantendo cada corrente de frase
     * partida inteira num lote só e fatiando o resto pelo tamanho configurado.
     *
     * <p>INVARIANTES DO DOMÍNIO: preserva a ordem de entrada; todo texto aparece em
     * exatamente um grupo; nenhum grupo é vazio. Uma corrente nunca é dividida por causa do
     * tamanho de lote — ela É a unidade.
     *
     * <p>COMPORTAMENTO EM CASO DE FALHA: {@code textos} nulo/vazio devolve lista vazia;
     * {@code tamanhoLotePadrao} menor que 1 é tratado como 1.
     *
     * @param textos textos originais na ordem do documento
     * @param tamanhoLotePadrao tamanho de lote para o que NÃO é corrente
     * @return grupos na ordem de entrada, cada um pronto para virar um {@code Lote}
     */
    public List<List<String>> agrupar(List<String> textos, int tamanhoLotePadrao) {
        return agrupar(textos, tamanhoLotePadrao, (anterior, seguinte) -> true);
    }

    /**
     * PROPÓSITO DE NEGÓCIO: mesmo agrupamento, ligando dois textos SÓ quando o chamador confirma
     * que eles são vizinhos no documento.
     *
     * <p>INVARIANTES DO DOMÍNIO: a lista recebida é a das falas PENDENTES, não o documento — dela
     * já saíram as falas vindas do cache, as repetidas e as mantidas por fonte em português. Sem o
     * predicado, a vizinha de uma fala que saiu ficava colada na seguinte e podia virar corrente
     * (achado da auditoria da 2.1, 25/09/2026). A heurística gramatical continua a mesma.
     *
     * <p>COMPORTAMENTO EM CASO DE FALHA: predicado nulo é tratado como "todos vizinhos" — o
     * comportamento anterior.
     */
    public List<List<String>> agrupar(List<String> textos, int tamanhoLotePadrao,
            java.util.function.BiPredicate<String, String> vizinhas) {
        java.util.function.BiPredicate<String, String> saoVizinhas =
            vizinhas != null ? vizinhas : (anterior, seguinte) -> true;
        List<List<String>> grupos = new ArrayList<>();
        if (textos == null || textos.isEmpty()) {
            return grupos;
        }
        int passo = Math.max(1, tamanhoLotePadrao);
        int total = textos.size();

        // ligado[i] = o texto i é continuação gramatical do texto i-1.
        boolean[] ligado = new boolean[total];
        for (int i = 1; i < total; i++) {
            ligado[i] = saoVizinhas.test(textos.get(i - 1), textos.get(i))
                && ehContinuacao(textos.get(i - 1), textos.get(i));
        }

        List<String> soltos = new ArrayList<>();
        int i = 0;
        while (i < total) {
            if (i + 1 < total && ligado[i + 1]) {
                descarregar(soltos, passo, grupos, true);
                int fim = i + 1;
                while (fim < total && ligado[fim]) {
                    fim++;
                }
                grupos.add(List.copyOf(textos.subList(i, fim)));
                i = fim;
                continue;
            }
            soltos.add(textos.get(i));
            i++;
            descarregar(soltos, passo, grupos, false);
        }
        descarregar(soltos, passo, grupos, true);
        return grupos;
    }

    /**
     * PROPÓSITO DE NEGÓCIO: decide se {@code seguinte} completa a frase iniciada em
     * {@code anterior}.
     *
     * <p>INVARIANTES DO DOMÍNIO: exige as três condições — anterior não é cartão, anterior
     * termina sem pontuação final, seguinte começa em minúscula. Precisão medida no Unicorn:
     * 707 de 727 candidatos brutos, com os 20 restantes eliminados pelo filtro de cartão.
     *
     * <p>COMPORTAMENTO EM CASO DE FALHA: texto nulo ou sem letra devolve {@code false}.
     */
    public boolean ehContinuacao(String anterior, String seguinte) {
        return !ehCartao(anterior)
            && terminaSemPontuacao(anterior)
            && comecaMinuscula(seguinte);
    }

    private void descarregar(List<String> soltos, int passo, List<List<String>> grupos, boolean forcar) {
        while (soltos.size() >= passo) {
            grupos.add(List.copyOf(soltos.subList(0, passo)));
            soltos.subList(0, passo).clear();
        }
        if (forcar && !soltos.isEmpty()) {
            grupos.add(List.copyOf(soltos));
            soltos.clear();
        }
    }

    private String semTags(String texto) {
        return texto == null ? "" : PADRAO_TAG.matcher(texto).replaceAll(" ").strip();
    }

    private boolean terminaSemPontuacao(String texto) {
        String limpo = semTags(texto);
        return !limpo.isEmpty() && PONTUACAO_FINAL.indexOf(limpo.charAt(limpo.length() - 1)) < 0;
    }

    private boolean comecaMinuscula(String texto) {
        for (char c : semTags(texto).toCharArray()) {
            if (Character.isLetter(c)) {
                return Character.isLowerCase(c);
            }
            if (Character.isDigit(c)) {
                return false;
            }
        }
        return false;
    }

    /** Título de episódio, placa, letreiro: todas as letras em maiúscula. */
    private boolean ehCartao(String texto) {
        String limpo = semTags(texto);
        boolean temLetra = false;
        for (char c : limpo.toCharArray()) {
            if (Character.isLetter(c)) {
                temLetra = true;
                if (Character.isLowerCase(c)) {
                    return false;
                }
            }
        }
        return temLetra;
    }
}
