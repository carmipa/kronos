package org.traducao.projeto.traducao.application;

import org.traducao.projeto.core.texto.FronteiraTermoAss;

import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * PROPÓSITO DE NEGÓCIO: garante forma ÚNICA para as falas que são inteiramente um termo
 * conhecido, sem depender de o modelo acertar. Existem expressões que aparecem centenas de vezes
 * numa obra e cuja tradução é sempre a mesma; deixá-las ao acaso do LLM produz saída inconsistente
 * entre episódios — o espectador vê "Entendido!" no episódio 3 e "Roger!" no 7, na mesma série.
 *
 * <h2>Medição que originou (acervo completo, 2026-07-23)</h2>
 * A fala {@code Roger} sozinha aparece 220 vezes nos caches versionados, com QUATRO desfechos:
 * <ul>
 *   <li>179x {@code Entendido} — correto, e a forma majoritária (81%);</li>
 *   <li>34x mantida em inglês — compreensível, mas inconsistente com as 179;</li>
 *   <li>5x {@code Shiro: Roger!} — rótulo de locutor INVENTADO pelo modelo, conteúdo que não
 *       existe no original entrando na legenda;</li>
 *   <li>2x {@code Rogério.} — traduzida como nome de pessoa; o "Roger" de rádio virou gente.</li>
 * </ul>
 * Nenhuma guarda existente pega os dois últimos: a régua de identidade só age quando a tradução é
 * IGUAL ao original, e {@code Rogério.} e {@code Shiro: Roger!} são textos diferentes — passam
 * limpos por toda a validação. Este enforçador resolve os três defeitos de uma vez, porque
 * reescreve a fala inteira a partir do ORIGINAL.
 *
 * <h2>Invariantes do domínio</h2>
 * <ul>
 *   <li>SÓ age quando o texto visível do original é INTEIRAMENTE o termo (ignorando tags ASS e
 *       pontuação). {@code "Roger."} é rádio; {@code "Roger, come here"} pode ser um personagem
 *       chamado Roger e NUNCA é tocado. É casamento de fala completa, jamais de substring.</li>
 *   <li>Preserva a estrutura do original: tags ASS e pontuação continuam exatamente onde estavam,
 *       porque a substituição acontece sobre o ORIGINAL, trocando apenas a palavra.</li>
 *   <li>É determinístico e independe do que o modelo devolveu — por isso corrige inclusive a
 *       tradução alucinada, que é o caso que nenhuma outra guarda alcança.</li>
 *   <li>Glossário GLOBAL, não por obra: o defeito foi medido em várias séries, não numa só.</li>
 * </ul>
 *
 * <h2>Comportamento em caso de falha</h2>
 * Texto nulo ou vazio devolve a tradução recebida sem alteração. Fala que não casa integralmente
 * com nenhum termo é devolvida intacta. Não lança e não faz I/O.
 */
@Component
public class EnforcadorGlossarioFala {

    private static final String INICIO_DE_TERMO = FronteiraTermoAss.INICIO;

    private static final Pattern TAGS_ASS = Pattern.compile("\\{[^}]*}");
    private static final Pattern QUEBRAS = Pattern.compile("\\\\[Nnh]");
    /**
     * Pontuação e espaços que envolvem a fala sem alterar o termo. {@code \p{P}} e {@code \p{Z}}
     * são as categorias UNICODE: {@code \p{Punct}} é só ASCII, e {@code "“Roger.”"} (aspas curvas)
     * ou o espaço não separável deixavam a chave com um caractere a mais e o termo não casava.
     */
    private static final Pattern ORNAMENTO = Pattern.compile("[\\p{P}\\p{Z}\\s]+");

    /**
     * Glossário de fala completa. Chave em minúsculas e sem pontuação; valor na forma canônica
     * PT-BR. CRESCE POR MEDIÇÃO: cada entrada precisa de ocorrências contadas no acervo, senão
     * vira palpite embutido no caminho quente.
     *
     * <p>{@code no}, medido em 28/10/2026 pela chave de produção nos caches da 2.1: 165 falas que
     * são só "No", 100 traduzidas "Não", <b>49 pendentes</b> (inglês na tela), 9 com conteúdo
     * inventado ("Não há escolha"), 4 "Nenhum" e 3 "Número".
     */
    private static final Map<String, String> GLOSSARIO = Map.of(
        "roger", "Entendido",
        "no", "Não"
    );

    private static final List<String> TERMOS_POR_TAMANHO = GLOSSARIO.keySet().stream()
        .sorted(Comparator.comparingInt(String::length).reversed())
        .toList();

    /**
     * PROPÓSITO DE NEGÓCIO: reduz uma fala à CHAVE que este glossário indexa — sem tags, sem
     * quebra, sem pontuação, em minúsculas. É a forma pela qual {@code "{\i1}Roger!!"} e
     * {@code "Roger."} são a mesma fala.
     *
     * <p>É público porque o Javadoc de {@link #GLOSSARIO} exige que cada entrada nasça MEDIDA no
     * acervo, e medir com uma normalização diferente da que o enforcer usa produz contagem que
     * não vale para ele. Quem mede ({@code medicao.MineracaoGlossarioIT}) chama exatamente esta.
     * Reimplementar do lado de fora seria a mesma cópia-que-deriva que custou os defeitos de
     * 03 e 04/08/2026.
     *
     * <p>INVARIANTES DO DOMÍNIO: sem estado; a mesma entrada sempre produz a mesma chave.
     * <p>COMPORTAMENTO EM CASO DE FALHA: {@code null}/vazio devolve string vazia; nunca lança.
     */
    public static String chaveDeFala(String texto) {
        if (texto == null || texto.isBlank()) {
            return "";
        }
        return ORNAMENTO.matcher(
                QUEBRAS.matcher(TAGS_ASS.matcher(texto).replaceAll(" ")).replaceAll(" "))
            .replaceAll(" ")
            .strip()
            .toLowerCase(Locale.ROOT);
    }

    /**
     * PROPÓSITO DE NEGÓCIO: devolve a tradução com a forma canônica quando a fala inteira é um
     * termo do glossário; caso contrário devolve a tradução como veio.
     *
     * <p>INVARIANTES DO DOMÍNIO: a decisão vem do ORIGINAL, não da tradução — é isso que permite
     * corrigir uma tradução alucinada. A reescrita parte do original, então tags e pontuação
     * sobrevivem sem precisar ser remontadas.
     *
     * <p>COMPORTAMENTO EM CASO DE FALHA: original ou tradução nulos devolvem a tradução recebida;
     * fala que não casa integralmente é devolvida intacta. Não lança.
     *
     * @param original fala de origem, ainda com tags ASS
     * @param traduzido tradução candidata
     * @return a fala canônica, ou {@code traduzido} quando o glossário não se aplica
     */
    /**
     * PROPÓSITO DE NEGÓCIO: traduz SEM o modelo a fala que é inteiramente um termo do glossário.
     * A {@link #reforcar} só age sobre tradução que existe; quando o modelo é recusado em todas as
     * tentativas, a fala ficava pendente e saía em INGLÊS, embora a resposta certa fosse sabida de
     * antemão — 49 falas "No." no acervo de 28/10/2026.
     *
     * <p>INVARIANTES DO DOMÍNIO: a mesma decisão e a mesma reescrita de {@link #reforcar}; só
     * vale para fala-termo completa.
     *
     * <p>COMPORTAMENTO EM CASO DE FALHA: fala que não é termo, ou original nulo/vazio, devolve
     * {@link java.util.Optional#empty()}. Não lança.
     */
    public java.util.Optional<String> traduzirSemModelo(String original) {
        return java.util.Optional.ofNullable(reforcar(original, null));
    }

    public String reforcar(String original, String traduzido) {
        if (original == null || original.isBlank()) {
            return traduzido;
        }
        String visivel = chaveDeFala(original);
        if (visivel.isEmpty()) {
            return traduzido;
        }
        for (String termo : TERMOS_POR_TAMANHO) {
            if (!visivel.equals(termo)) {
                continue;
            }
            // A fala INTEIRA e o termo: reescreve a partir do original para herdar tags e
            // pontuacao, trocando so a palavra. Assim "{\i1}Roger." vira "{\i1}Entendido." e
            // "Roger!!" vira "Entendido!!", sem remontar estrutura na mao.
            //
            // ASSIMETRIA PERIGOSA, e o porque da guarda abaixo: quem DECIDE agir e `visivel`,
            // que ja normalizou \N, \n e \h para espaco; quem REESCREVE e o padrao, que aceita
            // espaco e \N mas nao \h. Termo de duas palavras separado por \h faria a chave casar
            // e o replaceAll nao casar — e replaceAll sem casamento devolve a ENTRADA INTACTA,
            // ou seja, a fala voltaria EM INGLES, descartando a traducao.
            //
            // Nao alargamos o separador: medido em 04/08/2026 no acervo (61.051 falas), nome
            // composto separado por \h aparece ZERO vezes, e regra sem medicao e palpite. A
            // guarda resolve a CLASSE inteira sem inventar casamento: se a reescrita nao mudou
            // nada, o glossario nao se aplicou, e o que sai e a traducao — nunca o original.
            //
            // A guarda NAO tem teste que a exercite, e isso esta escrito de proposito: com o
            // glossario atual (uma entrada, uma palavra) o caso e INALCANCAVEL pela API publica.
            // GlossarioNaoDevolveOriginalEmInglesTest documenta a tentativa e o limite. A versao
            // anterior deste comentario afirmava estar "congelado" nesse teste — que nao existia.
            String reescrito = FronteiraTermoAss.padraoIgnorandoCaixa(termo)
                .matcher(original)
                .replaceAll(java.util.regex.Matcher.quoteReplacement(GLOSSARIO.get(termo)));
            return reescrito.equals(original) ? traduzido : reescrito;
        }
        return traduzido;
    }
}
