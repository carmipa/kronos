package org.traducao.projeto.qualidadeTraducao.application;

import org.springframework.stereotype.Component;
import org.traducao.projeto.core.texto.FronteiraTermoAss;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * PROPÓSITO DE NEGÓCIO: repõe o acento de {@code é} e {@code está} usando o ORIGINAL INGLÊS
 * como prova — os dois defeitos que Paulo leu na tela assistindo ao Gundam ZZ, e os únicos
 * dessa classe que nenhum dicionário consegue ver.
 *
 * <h2>Por que dicionário não resolve, e por que o inglês resolve</h2>
 * {@code e} e {@code esta} são palavras válidas em português. Nenhum corretor ortográfico as
 * acusa, porque não há nada de errado com elas — está errado o SENTIDO. O
 * {@link NormalizadorAcentosComuns} exclui esses homógrafos de propósito, e o Javadoc dele diz
 * por quê: sem contexto, trocar {@code e} por {@code é} introduz erro em toda coordenação.
 *
 * <p>O contexto que falta está no original. Uma legenda é tradução de uma frase inglesa
 * conhecida, e o inglês responde as duas perguntas de forma verificável:
 * <ul>
 *   <li><b>{@code e} → {@code é}</b>: o português não pode ter mais conjunção {@code e} do que
 *       o inglês tem coordenador. Se o original não traz {@code and}, {@code &}, vírgula,
 *       ponto-e-vírgula nem dois-pontos, <b>não há o que coordenar</b> — e todo {@code e} solto
 *       da tradução é o verbo {@code ser}.</li>
 *   <li><b>{@code esta} → {@code está}</b>: {@code esta} só é demonstrativo se o original
 *       trouxer um demonstrativo ({@code this}, {@code that}, {@code these}, {@code those}).
 *       Sem ele e com o verbo {@code to be} presente, {@code esta} é o verbo {@code estar}.</li>
 * </ul>
 *
 * <h2>Por que aqui, e não na fatia 3.3</h2>
 * A {@code RevisarConcordanciaUseCase} declara no próprio Javadoc que lê "o {@code .ass} PT-BR,
 * <b>sem inglês e sem cache</b>". Foi essa cegueira que obrigou o
 * {@code CorretorAcentoPorPadraoService} a decidir por LISTA FECHADA da palavra ANTERIOR —
 * {@code Isso|Isto|Essa|Esse|não|Você|Ela|Ele} — e é por isso que
 * <i>"também e motivo de preocupação"</i> passa: {@code também} não está na lista. A ironia está
 * escrita naquele mesmo arquivo, no padrão do {@code so}: <i>"Lista de palavra seguinte é frágil
 * por construção"</i>. A lição foi aprendida para o {@code so} e nunca aplicada ao {@code e}.
 *
 * <p>Este serviço mora no peer de QUALIDADE porque a mecânica é a mesma nas duas pontas: o
 * caminho de tradução tem o original em mãos ao gravar, e a tela 3.1 tem {@code originalEn} por
 * fala. A fatia decide QUANDO aplicar; a mecânica é uma só.
 *
 * <h2>O prejuízo que originou, medido em 03/09/2026</h2>
 * No acervo entregue (76.794 falas de diálogo, música fora, depois de a 3.3 já ter rodado em
 * agosto): <b>371 falas</b> com {@code e} onde cabe {@code é} e <b>195</b> com {@code esta} onde
 * cabe {@code está}. O ZZ sozinho tem 166. No episódio 1 do ZZ a 3.3 corrigiu 4 falas em 705.
 *
 * <h2>Invariantes do domínio</h2>
 * <ul>
 *   <li><b>Na dúvida, NÃO corrige.</b> Quando o inglês tem coordenador, qual {@code e} é o verbo
 *       vira adivinhação, e o serviço se abstém. Medido: a regra estrita alcança 350 das 371
 *       falas e recusa 21. Corretor que estraga texto certo é pior que corretor nenhum, e as 21
 *       ficam para a rota que consulta o LLM.</li>
 *   <li>Original ausente ou em branco NÃO autoriza palpite: devolve o texto intacto. É a mesma
 *       falha-fechada do resto do projeto — sem a prova, não há correção.</li>
 *   <li>Fronteira de termo pela {@link FronteiraTermoAss}: a quebra {@code \N} do ASS ocupa dois
 *       caracteres e o {@code N} é letra, então sem ela o {@code e} colado à quebra não é visto.
 *       Foi assim que 11 das 12 formas do dicionário sobreviveram no acervo em 04/08.</li>
 *   <li>Não toca em tag: o padrão exige fronteira de palavra, e {@code {\i1}} não a produz.</li>
 *   <li>Sem estado; só JDK e Spring. Não conhece cache, LLM nem legenda.</li>
 * </ul>
 *
 * <h2>Comportamento em caso de falha</h2>
 * Texto ou original {@code null}/vazio devolve o texto como veio; nunca lança. Nenhuma troca
 * aplicável devolve a mesma instância.
 */
@Component
public class CorretorHomografoComOriginal {

    /** Formas de {@code to be} no original. Sem elas não há verbo para o {@code é} traduzir. */
    /** {@code \N}, {@code \n} e {@code \h} do ASS no original ingles: separadores para a prova. */
    private static final Pattern QUEBRA_OU_ESPACO_ASS = Pattern.compile("\\\\[Nnh]");

    private static final Pattern VERBO_SER_INGLES = Pattern.compile(
        "\\b(?:is|are|am|was|were|be|been|isn't|aren't|wasn't|weren't)\\b"
            + "|(?<=\\w)'s\\b|(?<=\\w)'re\\b|(?<=\\w)'m\\b",
        Pattern.CASE_INSENSITIVE);

    /**
     * O que pode virar {@code e} em português. A vírgula entra porque o português coordena com
     * {@code e} o que o inglês separa por vírgula — <i>"Come here, sit down"</i> vira
     * <i>"Venha aqui e sente-se"</i>. Contar o lado inglês por CIMA faz a correção sair por
     * BAIXO, que é o lado seguro de errar.
     */
    private static final Pattern COORDENADOR_INGLES =
        Pattern.compile("\\band\\b|&|,|;|:", Pattern.CASE_INSENSITIVE);

    /** Demonstrativo no original: só ele autoriza {@code esta} a ser demonstrativo. */
    private static final Pattern DEMONSTRATIVO_INGLES =
        Pattern.compile("\\b(?:this|that|these|those)\\b", Pattern.CASE_INSENSITIVE);

    /**
     * Conjunção que ABRE a fala, dos dois lados. {@code "And Kamille's condition is..."} vira
     * {@code "E o estado de Kamille..."}: aquele {@code E} coordena com a fala ANTERIOR e é
     * legítimo. Tirá-lo só de um lado desequilibra a conta — foi o primeiro caso-controle a
     * reprovar quando esta regra nasceu.
     */
    private static final Pattern ABERTURA_INGLES =
        Pattern.compile("^(?:And|But|So|Then)\\s+", Pattern.CASE_INSENSITIVE);
    private static final Pattern ABERTURA_PORTUGUES =
        Pattern.compile("^(?:E|Mas|Ent[ãa]o)\\s+", Pattern.CASE_INSENSITIVE);

    private static final Pattern E_SOLTO = Pattern.compile(
        FronteiraTermoAss.INICIO + "(e)" + FronteiraTermoAss.FIM);
    private static final Pattern ESTA_SOLTO = Pattern.compile(
        FronteiraTermoAss.INICIO + "([Ee]sta)" + FronteiraTermoAss.FIM);

    /**
     * Marcador de FUTURO no original. A forma contraída depois de pronome entra sem fronteira à
     * esquerda de propósito: {@code you'll}, {@code We'll}, {@code he'll} são o futuro mais comum
     * na fala, e exigir fronteira ali os perde. <b>Medido:</b> 3 das 4 abstenções da primeira
     * versão desta medição eram exatamente essa forma.
     *
     * <p>{@code 'd} fica FORA: {@code "he'd probably die"} é condicional, não futuro, e a
     * tradução correta é {@code morreria}. A única abstenção que sobra no acervo é essa, e ela
     * está certa.
     */
    private static final Pattern FUTURO_INGLES = Pattern.compile(
        "\\b(?:will|shall|going to|gonna)\\b|won't|shan't|'ll",
        Pattern.CASE_INSENSITIVE);

    /**
     * Marcador de ANTERIORIDADE que obriga a abstenção. Com {@code had} no original, alguma forma
     * do português pode ser mais-que-perfeito legítimo, e não há como saber QUAL — é o mesmo
     * recuo que {@code podeTrocarE} faz diante de um coordenador.
     */
    private static final Pattern ANTERIOR_INGLES =
        Pattern.compile("\\bhad\\b", Pattern.CASE_INSENSITIVE);

    /**
     * Futuro do presente na 3ª pessoa escrito SEM o acento. Lista FECHADA, tirada do acervo
     * publicado, e cada forma tem o par acentuado como futuro do MESMO verbo.
     *
     * <p><b>{@code vira} está FORA de propósito</b>, e é a fronteira desta regra: {@code vira} é
     * presente de <i>virar</i> e {@code virá} é futuro de <i>vir</i> — dois verbos diferentes. As
     * duas ocorrências no acervo estão CORRETAS ({@code "Essa maquina não vira rapidamente"},
     * {@code "Você vira para perseguir"}), e uma delas tem {@code we'll} numa oração vizinha.
     * Incluí-la corromperia tradução boa, que é o dano que esta classe existe para evitar.
     */
    private static final Pattern FUTURO_SEM_ACENTO = Pattern.compile(
        FronteiraTermoAss.INICIO
            + "(durara|chegara|ficara|partira|acabara|voltara|escondera|começara|morrera|virara)"
            + FronteiraTermoAss.FIM,
        Pattern.CASE_INSENSITIVE);

    /**
     * PROPÓSITO DE NEGÓCIO: devolve a fala com {@code é} e {@code está} acentuados onde o
     * original inglês PROVA que são verbo.
     *
     * <p>INVARIANTES DO DOMÍNIO: só troca o que o original autoriza; preserva a caixa
     * ({@code Esta} vira {@code Está}); não altera mais nada do texto.
     *
     * <p>COMPORTAMENTO EM CASO DE FALHA: entrada nula/vazia, ou original sem verbo {@code to
     * be}, devolve o texto como veio.
     *
     * @param originalIngles a fala original; {@code null} ou vazia desliga a correção
     * @param traduzido      a fala traduzida a corrigir
     * @return a fala corrigida, ou a original quando nada se aplica
     */
    public String corrigir(String originalIngles, String traduzido) {
        if (traduzido == null || traduzido.isBlank()
            || originalIngles == null || originalIngles.isBlank()) {
            return traduzido;
        }
        // O INGLES e lido como prova por padroes com \b, e o N da quebra \N do ASS e letra:
        // "losing\Nand is quick" escondia o "and" (14/10/2026, Reconguista I, falas 344 e 1223 —
        // a 2.1 publicou "perder\Né age"). A quebra, o \n e o espaco fixo \h viram espaco SO na
        // copia usada como prova; o texto traduzido nao muda por isso.
        originalIngles = QUEBRA_OU_ESPACO_ASS.matcher(originalIngles).replaceAll(" ");
        // O FUTURO NAO DEPENDE DO VERBO "to be", entao ele age ANTES do portao abaixo. Amarrar
        // as duas coisas faria "This war will end someday." -> "esta guerra acabara" sair sem
        // correcao, porque nao ha "is/are/was" no original.
        String saida = acentuarFuturo(originalIngles, traduzido);
        if (!VERBO_SER_INGLES.matcher(originalIngles).find()) {
            return saida;
        }
        if (podeTrocarE(originalIngles)) {
            saida = trocar(saida, E_SOLTO, "é", preservaAbertura(saida));
        }
        if (!DEMONSTRATIVO_INGLES.matcher(originalIngles).find()) {
            saida = trocar(saida, ESTA_SOLTO, "está", 0);
        }
        return saida;
    }

    /**
     * PROPÓSITO DE NEGÓCIO: repõe o acento do futuro do presente quando o ORIGINAL INGLÊS prova
     * que a fala está no futuro. {@code "This war will end someday."} traduzido como
     * {@code "esta guerra acabara"} fica {@code "acabará"} — e {@code acabara} sem acento é o
     * mais-que-perfeito, palavra válida que nenhum dicionário acusa.
     *
     * <h2>Por que o inglês, e por que não o dicionário</h2>
     * {@code NormalizadorAcentosComuns} só toca forma cuja grafia sem acento NUNCA é palavra em
     * português, e recusa esta classe com razão. O contexto que falta está no original: se o
     * inglês traz marcador de futuro, o português não pode estar no mais-que-perfeito.
     *
     * <h2>O prejuízo MEDIDO — 2026-09-14, acervo publicado</h2>
     * 134 pares casados pela chave completa do evento ASS. Denominador: <b>20</b> falas
     * publicadas com uma das formas da lista. Desfecho da regra:
     * <pre>
     * corrige ........................... 19
     * abstem, sem marcador de futuro ..... 1   ("he'd probably die" e condicional, nao futuro)
     * abstem por "had" ................... 0   (nao existe no acervo)
     * </pre>
     * Exemplos reais: {@code "This war will end someday."} saiu {@code "guerra acabara"};
     * {@code "Fido will stay and hide."} saiu {@code "Fido ficara e se escondera"};
     * {@code "the Albion will set out"} saiu {@code "o Albion partira"}.
     *
     * <h2>Invariantes do domínio</h2>
     * <ul>
     *   <li>Abstém-se diante de {@code had} no original — anterioridade legítima possível.</li>
     *   <li>A caixa do achado é preservada ({@code Começara} vira {@code Começará}).</li>
     *   <li>O acento entra trocando o ÚLTIMO {@code a} por {@code á}: é a forma do futuro do
     *       presente na 3ª pessoa, e vale para as dez formas da lista sem tabela nenhuma.</li>
     * </ul>
     *
     * <h2>Comportamento em caso de falha</h2>
     * Sem marcador de futuro, com {@code had}, ou sem nenhuma forma da lista, devolve o texto
     * exatamente como veio.
     */
    private String acentuarFuturo(String originalIngles, String traduzido) {
        if (!FUTURO_INGLES.matcher(originalIngles).find()
            || ANTERIOR_INGLES.matcher(originalIngles).find()) {
            return traduzido;
        }
        Matcher m = FUTURO_SEM_ACENTO.matcher(traduzido);
        StringBuilder saida = new StringBuilder();
        int ultimo = 0;
        while (m.find()) {
            String achado = m.group(1);
            // Caixa alta existe em legenda ASS: "COMEÇARA" tem de virar "COMEÇARÁ", não
            // "COMEÇARá". O acento herda a caixa da letra que ele substitui.
            int corte = Math.max(achado.lastIndexOf('a'), achado.lastIndexOf('A'));
            if (corte < 0) {
                continue;
            }
            char acentuada = achado.charAt(corte) == 'A' ? 'Á' : 'á';
            saida.append(traduzido, ultimo, m.start(1))
                .append(achado, 0, corte)
                .append(acentuada)
                .append(achado.substring(corte + 1));
            ultimo = m.end(1);
        }
        return ultimo == 0 ? traduzido : saida.append(traduzido.substring(ultimo)).toString();
    }

    /**
     * PROPÓSITO DE NEGÓCIO: diz se o original permite afirmar que TODO {@code e} solto da
     * tradução é o verbo.
     *
     * <p>INVARIANTES DO DOMÍNIO: um único coordenador no inglês já basta para a abstenção — não
     * há como saber QUAL {@code e} do português corresponde a ele.
     */
    private boolean podeTrocarE(String originalIngles) {
        String semAbertura = ABERTURA_INGLES.matcher(originalIngles).replaceFirst("");
        return !COORDENADOR_INGLES.matcher(semAbertura).find();
    }

    /**
     * PROPÓSITO DE NEGÓCIO: quantos caracteres do começo da fala ficam fora da troca, para a
     * conjunção que abre a fala sobreviver.
     */
    private int preservaAbertura(String traduzido) {
        Matcher m = ABERTURA_PORTUGUES.matcher(traduzido);
        return m.lookingAt() ? m.end() : 0;
    }

    /** Troca as ocorrências a partir de {@code desde}, preservando a caixa do achado. */
    private String trocar(String texto, Pattern padrao, String acentuado, int desde) {
        Matcher m = padrao.matcher(texto);
        StringBuilder saida = new StringBuilder(texto.length());
        int ultimo = 0;
        boolean achou = false;
        while (m.find()) {
            if (m.start(1) < desde) {
                continue;
            }
            achou = true;
            String original = m.group(1);
            String troca = Character.isUpperCase(original.charAt(0))
                ? Character.toUpperCase(acentuado.charAt(0)) + acentuado.substring(1)
                : acentuado;
            saida.append(texto, ultimo, m.start(1)).append(troca);
            ultimo = m.end(1);
        }
        if (!achou) {
            return texto;
        }
        return saida.append(texto, ultimo, texto.length()).toString();
    }
}
