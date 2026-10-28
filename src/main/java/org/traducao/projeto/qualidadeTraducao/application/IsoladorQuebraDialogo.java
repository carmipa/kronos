package org.traducao.projeto.qualidadeTraducao.application;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * PROPÓSITO DE NEGÓCIO: nas legendas, {@code \N} é o "pula-linha" (quebra visual dura),
 * não uma tag de estilo. Quando ele aparece no MEIO de uma frase de diálogo, mascará-lo
 * como {@code [[TAGn]]} força o LLM a reposicionar um marcador no meio da tradução — e,
 * como o português reordena as palavras, o modelo tende a perder o marcador e devolver a
 * fala original em inglês (eco). Este componente ISOLA o {@code \N} mid-sentence do diálogo
 * ANTES do mascaramento (o modelo traduz a frase inteira e limpa) e o REAPLICA depois, de
 * modo que a quebra visual volte à legenda sem contaminar a tradução. Reduz o balde de
 * falas-eco sem afrouxar o {@code MascaradorTags} e sem tocar em música/karaokê/KFX.
 *
 * <h2>Invariantes do domínio</h2>
 * <ul>
 *   <li>Só o {@code \N} <b>mid-sentence</b> (com letra/dígito antes E depois, ignorando
 *       tags {@code {...}}) é isolado; {@code \N} de borda (início/fim) é estrutural e
 *       permanece intocado para seguir o caminho de mascaramento normal.</li>
 *   <li>{@link #reaplicar(String, int)} reinsere exatamente a mesma quantidade de quebras
 *       que {@link #isolar(String)} removeu, em fronteiras de palavra próximas dos pontos
 *       de divisão equilibrada — a posição exata do original não é preservada (o PT reordena),
 *       mas a legenda volta a ter as quebras que precisa.</li>
 *   <li>Classe sem estado (stateless); só JDK + Spring. Não conhece cache, LLM nem legenda.</li>
 * </ul>
 *
 * <h2>Comportamento em caso de falha</h2>
 * Texto {@code null}, sem {@code \N} ou sem {@code \N} mid-sentence é devolvido intacto com
 * {@code quebras = 0} — {@link #reaplicar(String, int)} com {@code quebras <= 0} é no-op.
 * Se a tradução não tiver espaço onde inserir a quebra, o texto é devolvido sem quebra em
 * vez de lançar.
 */
@Component
public class IsoladorQuebraDialogo {

    /** Quebra dura de linha do ASS/SSA que este componente trata como pula-linha. */
    private static final Pattern PADRAO_QUEBRA = Pattern.compile("\\\\N");
    /** Tags de estilo {@code {...}} — removidas apenas para checar presença de texto real. */
    private static final Pattern PADRAO_TAG_ESTILO = Pattern.compile("\\{[^{}]*}");
    /** Escapes estruturais do ASS ({@code \N}, {@code \n}, {@code \h}) — não são texto humano. */
    private static final Pattern PADRAO_ESCAPE_ESTRUTURAL = Pattern.compile("\\\\[Nnh]");
    /** Presença de ao menos uma letra ou dígito (texto humano) num trecho. */
    private static final Pattern PADRAO_TEXTO_REAL = Pattern.compile("[\\p{L}\\p{N}]");
    /** Pontuação que não pode abrir uma linha: quebrar num espaço seguido dela orfaniza o sinal. */
    private static final String PONTUACAO_ORFA = ".,;:!?";

    /**
     * PROPÓSITO DE NEGÓCIO: transporta a fala pronta para o LLM (sem o {@code \N}
     * mid-sentence) e quantas quebras foram removidas para posterior reaplicação.
     *
     * <p>INVARIANTES DO DOMÍNIO: {@code quebras >= 0}; {@code quebras == 0} significa
     * texto intocado.
     *
     * <p>COMPORTAMENTO EM CASO DE FALHA: portador de dados puro, sem validação.
     */
    public record FalaIsolada(String textoSemQuebra, int quebras) {}

    /**
     * PROPÓSITO DE NEGÓCIO: remove do texto o {@code \N} que quebra uma frase no meio,
     * substituindo-o por espaço, para o LLM traduzir a frase inteira sem marcador interno.
     *
     * <p>INVARIANTES DO DOMÍNIO: só remove {@code \N} com texto real antes E depois; quebra
     * de borda é mantida; o espaço no lugar da quebra só é inserido quando nenhum dos lados
     * já tem um, preservando o espaçamento não relacionado do original.
     *
     * <p>COMPORTAMENTO EM CASO DE FALHA: texto {@code null} ou sem {@code \N} mid-sentence é
     * devolvido intacto com {@code quebras = 0}.
     *
     * @param original a fala com tags/quebras no formato ASS
     * @return {@link FalaIsolada} com o texto sem as quebras internas e a contagem removida
     */
    public FalaIsolada isolar(String original) {
        if (original == null || !original.contains("\\N")) {
            return new FalaIsolada(original, 0);
        }
        Matcher matcher = PADRAO_QUEBRA.matcher(original);
        StringBuilder resultado = new StringBuilder();
        int ultimoFim = 0;
        int quebras = 0;
        while (matcher.find()) {
            resultado.append(original, ultimoFim, matcher.start());
            boolean textoAntes = temTextoReal(original.substring(0, matcher.start()));
            boolean textoDepois = temTextoReal(original.substring(matcher.end()));
            if (textoAntes && textoDepois) {
                // Insere o espaço da quebra só quando nenhum dos lados já tem um — assim não
                // cria espaço duplo no ponto da quebra nem colapsa espaçamento não relacionado.
                boolean espacoAntes = resultado.length() > 0
                    && resultado.charAt(resultado.length() - 1) == ' ';
                boolean espacoDepois = matcher.end() < original.length()
                    && original.charAt(matcher.end()) == ' ';
                if (!espacoAntes && !espacoDepois) {
                    resultado.append(' ');
                }
                quebras++;
                // Se os DOIS lados já têm espaço, consome o da direita para não sobrar espaço duplo.
                ultimoFim = (espacoAntes && espacoDepois) ? matcher.end() + 1 : matcher.end();
            } else {
                resultado.append("\\N");
                ultimoFim = matcher.end();
            }
        }
        resultado.append(original.substring(ultimoFim));
        if (quebras == 0) {
            return new FalaIsolada(original, 0);
        }
        return new FalaIsolada(resultado.toString(), quebras);
    }

    /**
     * PROPÓSITO DE NEGÓCIO: reinsere na fala traduzida as quebras {@code \N} que foram
     * isoladas, para a legenda voltar a quebrar visualmente onde precisa.
     *
     * <p>INVARIANTES DO DOMÍNIO: insere exatamente {@code quebras} quebras, cada uma no
     * espaço mais próximo de um ponto de divisão equilibrada ({@code k/(quebras+1)} do
     * comprimento); cada espaço é usado no máximo uma vez; espaços DENTRO de tags {@code {...}}
     * nunca são pontos de quebra (não corrompe a formatação da tag); espaços SEGUIDOS de
     * pontuação ({@code . , ; : ! ?}) também não são candidatos — quebrar ali orfanaria o
     * sinal no início da próxima linha.
     *
     * <p>COMPORTAMENTO EM CASO DE FALHA: {@code quebras <= 0}, texto {@code null} ou sem
     * espaços disponíveis devolve o texto inalterado (sem lançar).
     *
     * @param traduzido a fala já traduzida e com as tags de estilo restauradas
     * @param quebras quantas quebras {@code \N} reinserir
     * @return a fala com as quebras reinseridas, ou inalterada se não houver onde inserir
     */
    public String reaplicar(String traduzido, int quebras) {
        if (traduzido == null || quebras <= 0) {
            return traduzido;
        }
        // Só as quebras que FALTAM. Quando o modelo devolve a fala em linhas separadas onde a
        // quebra estava, o episódio as junta com \N no ponto exato (ProcessarEpisodioUseCase,
        // 28/10/2026); reaplicar todas criaria uma segunda quebra no ponto de equilíbrio.
        int existentes = traduzido.split("\\\\N", -1).length - 1;
        quebras -= existentes;
        if (quebras <= 0) {
            return traduzido;
        }
        // Coleta espaços candidatos, IGNORANDO os que estão dentro de tags {...} — inserir um
        // \N ali corromperia a formatação (ex.: {\pos(20 50)} vira {\pos(20\N50)}) — e também
        // os espaços SEGUIDOS de pontuação: quebrar num "palavra , resto" (espaço antes da
        // vírgula, que o LLM às vezes emite) produziria "palavra\N," — pontuação órfã no início
        // da linha. Assim a quebra cai depois da pontuação, mantendo-a colada à palavra.
        List<Integer> espacos = new ArrayList<>();
        boolean dentroDeTag = false;
        for (int i = 0; i < traduzido.length(); i++) {
            char c = traduzido.charAt(i);
            if (c == '{') {
                dentroDeTag = true;
            } else if (c == '}') {
                dentroDeTag = false;
            } else if (c == ' ' && !dentroDeTag) {
                char proximo = (i + 1 < traduzido.length()) ? traduzido.charAt(i + 1) : '\0';
                if (PONTUACAO_ORFA.indexOf(proximo) < 0) {
                    espacos.add(i);
                }
            }
        }
        if (espacos.isEmpty()) {
            return traduzido;
        }
        TreeSet<Integer> escolhidos = new TreeSet<>();
        for (int k = 1; k <= quebras; k++) {
            int alvo = (int) ((long) traduzido.length() * k / (quebras + 1));
            int melhor = -1;
            for (int espaco : espacos) {
                if (escolhidos.contains(espaco)) {
                    continue;
                }
                if (melhor < 0 || Math.abs(espaco - alvo) < Math.abs(melhor - alvo)) {
                    melhor = espaco;
                }
            }
            if (melhor >= 0) {
                escolhidos.add(melhor);
            }
        }
        StringBuilder resultado = new StringBuilder(traduzido);
        for (Integer indice : escolhidos.descendingSet()) {
            resultado.replace(indice, indice + 1, "\\N");
        }
        return resultado.toString();
    }

    /**
     * PROPÓSITO DE NEGÓCIO: decide se um trecho contém texto humano (letra ou dígito),
     * ignorando as tags de estilo {@code {...}}, para distinguir quebra mid-sentence de
     * quebra de borda.
     *
     * <p>INVARIANTES DO DOMÍNIO: tags de estilo {@code {...}} e escapes estruturais
     * ({@code \N}/{@code \n}/{@code \h}) não contam como texto; só letra/dígito conta.
     *
     * <p>COMPORTAMENTO EM CASO DE FALHA: trecho vazio, só com tags ou só com escapes devolve
     * {@code false}.
     */
    private boolean temTextoReal(String trecho) {
        String semTags = PADRAO_TAG_ESTILO.matcher(trecho).replaceAll("");
        String semEscapes = PADRAO_ESCAPE_ESTRUTURAL.matcher(semTags).replaceAll("");
        return PADRAO_TEXTO_REAL.matcher(semEscapes).find();
    }
}
