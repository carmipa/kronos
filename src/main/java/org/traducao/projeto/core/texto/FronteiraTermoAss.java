package org.traducao.projeto.core.texto;

import java.util.regex.Pattern;

/**
 * PROPÓSITO DE NEGÓCIO: mecânica de casamento de um TERMO dentro de uma legenda ASS, onde a
 * quebra de linha do fansub cai em qualquer lugar — inclusive colada ao termo e DENTRO dele.
 *
 * <h2>O problema, em um parágrafo</h2>
 * {@code \N} é a quebra do ASS e ocupa DOIS caracteres: contrabarra e a letra {@code N}. O
 * {@code N} é letra para {@code \p{L}}, então:
 * <ul>
 *   <li>na FRONTEIRA, {@code (?<![\p{L}\p{N}])} conclui que {@code "Argama"} em
 *       {@code "the\NNahel Argama"} é sufixo de um {@code "NArgama"} inexistente;</li>
 *   <li>DENTRO do termo, {@code Pattern.quote("Nahel Argama")} procura um ESPAÇO literal que não
 *       está em {@code "Nahel\NArgama"}.</li>
 * </ul>
 *
 * <h2>Por que em {@code core}, e não copiado em cada fatia</h2>
 * O projeto prefere duplicação consciente a acoplamento, e por isso esta regra foi copiada — até
 * derivar. Medido entre 03 e 04/08/2026: a fronteira estava em 12 arquivos de 7 fatias; o
 * separador interno, em UM. O resultado foi corrupção de tradução correta em
 * {@code ValidadorTraducaoService} — ora revertendo {@code "Nahel Argama"} para {@code "Argama"},
 * ora produzindo {@code "Nahel\NNahel Argama"} na tela.
 *
 * <p>Isto NÃO é regra de negócio de nenhuma fatia: é a mecânica do formato ASS, do mesmo nível de
 * {@code core.io.DiretorioBaseKronos}. {@code core} é consumo livre por contrato — o grafo
 * ArchUnit o ignora —, então centralizar aqui elimina a cópia SEM criar aresta entre fatias.
 * A regra de ouro do projeto continua valendo: duplicação consciente vence acoplamento; o que não
 * se sustenta é duplicar MECÂNICA DE FORMATO, que não tem versão "por fatia".
 *
 * <h2>Invariantes do domínio</h2>
 * <ul>
 *   <li>Só o lado ESQUERDO precisa da alternativa da quebra: à direita do termo o caractere é a
 *       contrabarra, que já não é letra nem dígito. {@link #FIM} não a menciona de propósito.</li>
 *   <li>{@link #corpo(String)} ESCAPA cada palavra ({@code Pattern.quote}), então termo com
 *       metacaractere ({@code "Gaza-C"}, {@code "A.E.U.G."}) segue comparado literalmente; só o
 *       separador entre palavras fica flexível.</li>
 *   <li>Termo de UMA palavra produz exatamente o padrão de antes — nada muda para nome simples.</li>
 * </ul>
 *
 * <h2>Comportamento em caso de falha</h2>
 * Termo nulo ou em branco devolve corpo vazio, que não casa com nada; quem chama já barra antes.
 * Nada aqui lança.
 */
public final class FronteiraTermoAss {

    /**
     * Fronteira de INÍCIO do termo, com a quebra do ASS tratada como separador.
     *
     * <p>MEDIDO no acervo (60.891 falas, 24,6% com a quebra): das 12 formas sem acento do
     * dicionário de {@code NormalizadorAcentosComuns}, <b>0 sobreviveram soltas e 11 sobreviveram
     * coladas</b> — o cache é experimento natural, porque o normalizador roda antes de gravar.
     *
     * <p>A outra metade, achada em 14/10/2026: o termo também NÃO pode começar logo depois da
     * contrabarra. A primeira alternativa só olhava letra e dígito, e a contrabarra não é nenhum
     * dos dois — então em {@code "informação\Nao controle"} o casamento começava no {@code N} da
     * quebra, lia {@code "Nao"} e o normalizador escrevia {@code "\Não controle"}: a tela mostra
     * a quebra e depois {@code "ão controle"}. A 2.1 fez isso em falas revisadas à mão da
     * Reconguista I (3 no filme, pela saída relida) — a revisão dizia "ao", a legenda saía "ão".
     * <p>E a terceira, de 28/10/2026: {@code \N} não é a única quebra. {@code \n} (quebra suave)
     * e {@code \h} (espaço fixo) também separam palavras, e com só o {@code \N} na alternativa o
     * {@code "\hRoger."} não casava — o glossário decidia agir pela chave e a reescrita não
     * achava o termo.
     */
    public static final String INICIO = "(?:(?<=\\\\[Nnh])|(?<![\\p{L}\\p{N}\\\\]))";

    /** Fronteira de FIM do termo. Sem alternativa: à direita a quebra começa por contrabarra. */
    public static final String FIM = "(?![\\p{L}\\p{N}])";

    /**
     * Separador entre palavras de um termo composto: espaço OU a quebra.
     *
     * <p>MEDIDO: 435 campos do acervo trazem nome composto partido pela quebra, em 230 formas
     * distintas — {@code Amuro\NRay}, {@code Bask\NOm}, {@code Baund\NDoc}, {@code Anavel\NGato}.
     * No run do ZZ, {@code "Quin Mantha"} ficou preso em 66,7% de preservação nas duas gerações,
     * antes e depois de reconhecer a quebra como fronteira — porque o furo era este.
     */
    public static final String SEPARADOR_INTERNO = "(?:\\s|\\\\N)+";

    private static final Pattern SEPARADOR = Pattern.compile(SEPARADOR_INTERNO);

    private FronteiraTermoAss() {
    }

    /**
     * PROPÓSITO DE NEGÓCIO: troca um termo casado por {@link #corpo(String)} pelo seu substituto
     * SEM perder a quebra de linha que a legenda tinha dentro dele. O corpo aceita {@code \N} entre
     * as palavras justamente para achar "Mobile\NSuit"; trocar o trecho casado inteiro por
     * "Mobile Suit" apagava a quebra, e a fala passava a reprovar no portão de estrutura depois de
     * já aprovada. Medido no log de 17/09/2026: "We can grab some\Nnormal suits..." e "They hated his
     * mobile suit,\Nand they hated Tag!" publicadas numa linha só, acusadas pela releitura A6.
     *
     * <h2>Invariantes do domínio</h2>
     * <ul>
     *   <li>Sem {@code \N} no trecho casado, devolve o substituto como veio — nada muda para o caso
     *       comum.</li>
     *   <li>A quebra volta na MESMA posição entre palavras: "trajes\Nnormais" trocado por
     *       "Normal Suits" vira "Normal\NSuits".</li>
     *   <li>O número de quebras nunca diminui: se o substituto tem menos palavras que o casado, as
     *       quebras que sobraram vão para o fim do termo.</li>
     * </ul>
     *
     * <p>COMPORTAMENTO EM CASO DE FALHA: argumento nulo devolve o substituto; nunca lança.
     *
     * @param casado o trecho que o padrão do termo casou na legenda
     * @param substituto a forma que entra no lugar
     */
    public static String substituirPreservandoQuebra(String casado, String substituto) {
        if (casado == null || substituto == null || substituto.isBlank() || !casado.contains("\\N")) {
            return substituto;
        }
        java.util.List<Boolean> quebraEmCadaSeparador = new java.util.ArrayList<>();
        java.util.regex.Matcher m = SEPARADOR.matcher(casado);
        while (m.find()) {
            quebraEmCadaSeparador.add(m.group().contains("\\N"));
        }
        String[] palavras = substituto.trim().split("\\s+");
        StringBuilder sb = new StringBuilder(palavras[0]);
        long quebrasUsadas = 0;
        for (int i = 1; i < palavras.length; i++) {
            boolean quebra = i - 1 < quebraEmCadaSeparador.size() && quebraEmCadaSeparador.get(i - 1);
            sb.append(quebra ? "\\N" : " ").append(palavras[i]);
            if (quebra) {
                quebrasUsadas++;
            }
        }
        long quebrasDoCasado = quebraEmCadaSeparador.stream().filter(Boolean::booleanValue).count();
        for (long i = quebrasUsadas; i < quebrasDoCasado; i++) {
            sb.append("\\N");
        }
        return sb.toString();
    }

    /**
     * PROPÓSITO DE NEGÓCIO: corpo do padrão de um termo, aceitando que a legenda o parta na
     * virada da linha.
     * <p>INVARIANTES DO DOMÍNIO: cada palavra escapada; só o separador fica flexível.
     * <p>COMPORTAMENTO EM CASO DE FALHA: nulo/branco devolve string vazia.
     */
    public static String corpo(String termo) {
        if (termo == null || termo.isBlank()) {
            return "";
        }
        String[] palavras = termo.trim().split("\\s+");
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < palavras.length; i++) {
            if (i > 0) {
                sb.append(SEPARADOR_INTERNO);
            }
            sb.append(Pattern.quote(palavras[i]));
        }
        return sb.toString();
    }

    /**
     * PROPÓSITO DE NEGÓCIO: padrão completo do termo isolado — fronteira, corpo, fronteira.
     * <p>INVARIANTES DO DOMÍNIO: sensível à caixa; use {@link #padraoIgnorandoCaixa(String)}
     * quando o chamador precisar do contrário.
     * <p>COMPORTAMENTO EM CASO DE FALHA: termo vazio produz padrão que não casa com nada.
     */
    public static Pattern padrao(String termo) {
        return Pattern.compile(INICIO + corpo(termo) + FIM);
    }

    /**
     * PROPÓSITO DE NEGÓCIO: mesmo padrão, ignorando caixa e com semântica Unicode.
     * <p>INVARIANTES DO DOMÍNIO: {@code CASE_INSENSITIVE | UNICODE_CASE}, que é o que os
     * chamadores escreviam como {@code "(?iu)"}.
     * <p>COMPORTAMENTO EM CASO DE FALHA: idêntico a {@link #padrao(String)}.
     */
    public static Pattern padraoIgnorandoCaixa(String termo) {
        return Pattern.compile(INICIO + corpo(termo) + FIM,
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    }
}
