package org.traducao.projeto.qualidadeTraducao.application;

import org.traducao.projeto.core.texto.FronteiraTermoAss;

import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.Comparator;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * PROPÓSITO DE NEGÓCIO: repõe acentos em palavras que o LLM às vezes devolve sem acento
 * (visto no 08th: {@code infancia}, {@code ate}). Age SÓ sobre formas cuja grafia sem acento
 * NUNCA é uma palavra válida em português — assim a correção é determinística e não muda
 * sentido. Complementa a revisão gramatical do modelo com uma rede determinística barata.
 *
 * <h2>Invariantes do domínio</h2>
 * <ul>
 *   <li>Dicionário CURADO e conservador: só entram formas cuja versão sem acento não existe em
 *       PT ({@code nao}, {@code voce}, {@code tambem}, {@code ate}, {@code infancia}, ...). São
 *       DELIBERADAMENTE excluídos homógrafos e casos que dependem de sentido — {@code esta}
 *       (this)≠{@code está} (is), {@code e} (and)≠{@code é} (is), {@code as vezes} (the times)≠
 *       {@code às vezes} (sometimes) — para nunca introduzir erro.</li>
 *   <li>Substituição por fronteira de palavra (não casa {@code ate} dentro de {@code Kate}),
 *       preservando a caixa do achado (Nao→Não, NAO→NÃO, nao→não).</li>
 *   <li>Classe sem estado; só JDK + Spring. Não conhece cache, LLM nem legenda.</li>
 * </ul>
 *
 * <h2>Comportamento em caso de falha</h2>
 * Texto {@code null}/vazio ou sem nenhuma forma do dicionário é devolvido intacto; nunca lança.
 */
@Component
public class NormalizadorAcentosComuns {

    /**
     * Fronteira de início do termo, com a quebra {@code \N} do ASS tratada como separador.
     *
     * <p>{@code \N} ocupa DOIS caracteres — contrabarra e a letra {@code N} — e o {@code N} é letra
     * para {@code \p{L}}. Sem a alternativa, {@code "voce"} colado na quebra vira sufixo de um
     * {@code "Nvoce"} inexistente e o acento nunca é reposto.
     *
     * <p><b>MEDIDO no cache do acervo em 2026-08-04</b> (60.891 falas traduzidas, 24,6% com a
     * quebra): das 12 formas do dicionário, <b>0</b> sobreviveram SOLTAS e <b>11</b> sobreviveram
     * COLADAS na quebra. Como este normalizador roda em {@code ProcessarArquivoUseCase} ANTES de
     * gravar, o cache é experimento natural: forma solta foi SEMPRE corrigida, forma colada NUNCA
     * foi. As 11 são "para\Nvoce", "voz,\Nnao vai ganhar", "se\Nvoce era um Cyber-Newtype".
     *
     * <p>A reposição usa {@code appendReplacement} sobre o grupo, e o lookbehind é de largura zero
     * — a quebra não é consumida nem reescrita.
     */
    private static final String INICIO_DE_TERMO = FronteiraTermoAss.INICIO;

    // Forma-sem-acento (nunca palavra válida em PT) -> forma acentuada canônica.
    private static final Map<String, String> CORRECOES = Map.ofEntries(
        Map.entry("nao", "não"),
        // "sao" e "vao" entraram em 14/08/2026, medidas na tradução do 86: 48 e 11 ocorrências
        // chegaram à legenda sem acento, contra 1 na versão de 07/08. Elas caíam no vão entre os
        // dois mecanismos — não estavam nesta lista e têm 3 letras, abaixo do filtro do
        // dicionário. Baixar aquele filtro foi tentado e REPROVADO pela suíte: levaria "mae"
        // (前 em romaji) a virar "mãe", dano já medido em 100 ocorrências no Unicorn. Aqui é
        // nominal e por isso não alcança romaji.
        //
        // "São" com maiúscula sobrevive: a substituição preserva a caixa, e o par São Paulo /
        // San Magnólia não colide — "san" não está nesta lista.
        Map.entry("sao", "são"),
        Map.entry("vao", "vão"),
        Map.entry("voce", "você"),
        Map.entry("voces", "vocês"),
        Map.entry("tambem", "também"),
        Map.entry("ate", "até"),
        Map.entry("alem", "além"),
        Map.entry("apos", "após"),
        Map.entry("atras", "atrás"),
        Map.entry("infancia", "infância"),
        // MEDIDA em 13/08/2026 no Zeta: sobrou da regra de terminação, e é segura — "inutil" não
        // é palavra válida nem nome de ninguém no acervo.
        Map.entry("inutil", "inútil"),
        // "irma" -> "irmã" NÃO entra, e a exclusão é medida: são as 10 ocorrências restantes do
        // Zeta, mas "Irma" é NOME PRÓPRIO no 86 ("o Segundo Tenente Irma"). O dicionário é global e
        // não sabe de qual obra é a fala — corrigir aqui renomearia a personagem. É o mesmo efeito
        // ímã entre nomes parecidos que já acusou "Marida" de ser particípio feminino. Fica para o
        // enforçador de termos de lore, que conhece a obra.
        Map.entry("ninguem", "ninguém"),
        Map.entry("alguem", "alguém"),
        Map.entry("porem", "porém"),

        // ---------------------------------------------------------------
        // AMPLIAÇÃO DE 07/08/2026 — as seis primeiras foram MEDIDAS.
        //
        // Auditadas 10.918 falas das duas obras traduzidas na noite de 06/08
        // (Gundam 0083 Stardust Memory, 15 eps; Gundam 08th MS Team, 13 eps):
        // 74 falas (0,68%) saíram com a forma sem acento. A contagem, com a
        // fronteira que trata a quebra \N do ASS:
        //
        //   35x capitao   26x entao   7x estao   3x razao
        //    1x pressao    1x esquadrao
        //
        // "sera" apareceu 1x e ficou de FORA de propósito: pode ser nome
        // próprio, e a invariante deste mapa exige forma que nunca seja palavra
        // válida. Uma ocorrência não paga o risco de estragar um nome.
        //
        // O resto é a mesma família -ão/-ção, toda com a mesma propriedade: a
        // forma sem til não existe em português. Entram porque o defeito é do
        // modelo com a terminação, não com a palavra específica — e esperar
        // cada uma aparecer numa auditoria seria pagar o erro antes de corrigir.
        // ---------------------------------------------------------------
        Map.entry("capitao", "capitão"),
        Map.entry("capitaes", "capitães"),
        Map.entry("entao", "então"),
        Map.entry("estao", "estão"),
        Map.entry("razao", "razão"),
        Map.entry("razoes", "razões"),
        Map.entry("pressao", "pressão"),
        Map.entry("esquadrao", "esquadrão"),
        Map.entry("batalhao", "batalhão"),

        Map.entry("irmao", "irmão"),
        Map.entry("irmaos", "irmãos"),
        Map.entry("mae", "mãe"),
        Map.entry("maes", "mães"),
        Map.entry("coracao", "coração"),
        Map.entry("tres", "três"),
        Map.entry("comeco", "começo"),

        Map.entry("missao", "missão"),
        Map.entry("missoes", "missões"),
        Map.entry("posicao", "posição"),
        Map.entry("posicoes", "posições"),
        Map.entry("atencao", "atenção"),
        Map.entry("decisao", "decisão"),
        Map.entry("decisoes", "decisões"),
        Map.entry("licao", "lição"),
        Map.entry("nacao", "nação"),
        Map.entry("uniao", "união"),
        Map.entry("visao", "visão"),
        Map.entry("tensao", "tensão"),
        Map.entry("invasao", "invasão"),
        Map.entry("explosao", "explosão"),
        Map.entry("operacao", "operação"),
        Map.entry("operacoes", "operações"),
        Map.entry("situacao", "situação"),
        Map.entry("informacao", "informação"),
        Map.entry("informacoes", "informações"),
        Map.entry("explicacao", "explicação"),
        Map.entry("reacao", "reação"),
        Map.entry("direcao", "direção"),
        Map.entry("condicao", "condição"),
        Map.entry("condicoes", "condições"),
        Map.entry("destruicao", "destruição"),
        Map.entry("protecao", "proteção"),
        Map.entry("confirmacao", "confirmação"),
        Map.entry("autorizacao", "autorização"),

        // ---------------------------------------------------------------
        // AMPLIAÇÃO DE 12/08/2026 — MEDIDA no Gundam Unicorn, 22 episódios.
        //
        // MedicaoAcentuacaoFaltanteIT comparou as três traduções da MESMA obra e achou o
        // primeiro eixo em que os modelos divergem para o lado do mistral:
        //
        //   mistral   23 de 5.676 falas (0,4%)
        //   aya      197 de 5.458 falas (3,6%)   <-- 9x mais
        //
        // O dicionário acima é todo da família -ão/-ção e não cobria NADA do que a aya erra.
        // As mais frequentes, contadas: familia 22 · proxima 21 · unica 11 · federacao 10 ·
        // ultima 9 · maldicao 8 · fundacao 5 · unico 5 · facil 4 · historia 4 · possivel 4.
        //
        // Por que isto importa mais que parecer detalhe: erro de acento é corrigível por
        // MÁQUINA e erro de sentido não é. Com esta rede, o eixo em que a aya perdia deixa de
        // existir, e o eixo em que ela ganha — inverter pergunta e negação, 10x menos que o
        // mistral — continua valendo. É o que torna a escolha do titular uma decisão só.
        //
        // Mesmo critério de sempre: entra apenas forma cuja grafia sem acento NÃO é palavra
        // portuguesa. Ficaram DE FORA, mesmo tendo aparecido, todas as que também são verbo:
        // "publica" (ele publica), "medica" (ele medica), "critica", "pratica", "duvida",
        // "fabrica", "continua". Trocar essas introduziria erro onde não havia.
        // ---------------------------------------------------------------
        Map.entry("familia", "família"),
        Map.entry("familias", "famílias"),
        Map.entry("proxima", "próxima"),
        Map.entry("proximo", "próximo"),
        Map.entry("proximas", "próximas"),
        Map.entry("proximos", "próximos"),
        Map.entry("unica", "única"),
        Map.entry("unico", "único"),
        Map.entry("unicas", "únicas"),
        Map.entry("unicos", "únicos"),
        Map.entry("ultima", "última"),
        Map.entry("ultimo", "último"),
        Map.entry("ultimas", "últimas"),
        Map.entry("ultimos", "últimos"),
        Map.entry("historia", "história"),
        Map.entry("historias", "histórias"),
        Map.entry("memoria", "memória"),
        Map.entry("memorias", "memórias"),
        Map.entry("vitoria", "vitória"),
        Map.entry("gloria", "glória"),
        Map.entry("possivel", "possível"),
        Map.entry("impossivel", "impossível"),
        Map.entry("responsavel", "responsável"),
        Map.entry("responsaveis", "responsáveis"),
        Map.entry("disponivel", "disponível"),
        Map.entry("terrivel", "terrível"),
        Map.entry("incrivel", "incrível"),
        Map.entry("horrivel", "horrível"),
        Map.entry("nivel", "nível"),
        Map.entry("niveis", "níveis"),
        Map.entry("facil", "fácil"),
        Map.entry("dificil", "difícil"),
        Map.entry("faceis", "fáceis"),
        Map.entry("dificeis", "difíceis"),
        Map.entry("rapido", "rápido"),
        Map.entry("rapida", "rápida"),
        Map.entry("proprio", "próprio"),
        Map.entry("propria", "própria"),
        Map.entry("proprios", "próprios"),
        Map.entry("proprias", "próprias"),
        Map.entry("otimo", "ótimo"),
        Map.entry("otima", "ótima"),
        Map.entry("seculo", "século"),
        Map.entry("seculos", "séculos"),
        Map.entry("exercito", "exército"),
        Map.entry("veiculo", "veículo"),
        Map.entry("veiculos", "veículos"),
        Map.entry("servico", "serviço"),
        Map.entry("servicos", "serviços"),
        Map.entry("inicio", "início"),
        Map.entry("proposito", "propósito"),
        Map.entry("logica", "lógica"),
        Map.entry("logico", "lógico"),
        Map.entry("musica", "música"),
        Map.entry("musicas", "músicas"),
        Map.entry("importancia", "importância"),
        Map.entry("circunstancia", "circunstância"),
        Map.entry("circunstancias", "circunstâncias"),
        Map.entry("experiencia", "experiência"),
        Map.entry("experiencias", "experiências"),
        Map.entry("ciencia", "ciência"),
        Map.entry("consciencia", "consciência"),
        Map.entry("distancia", "distância"),
        Map.entry("emergencia", "emergência"),
        Map.entry("federacao", "federação"),
        Map.entry("federacoes", "federações"),
        Map.entry("maldicao", "maldição"),
        Map.entry("fundacao", "fundação"),
        Map.entry("ativacao", "ativação"),
        Map.entry("investigacao", "investigação"),
        Map.entry("localizacao", "localização"),
        Map.entry("geracao", "geração"),
        Map.entry("geracoes", "gerações"),
        Map.entry("populacao", "população"),
        Map.entry("estacao", "estação"),
        Map.entry("ligacao", "ligação"),
        Map.entry("traicao", "traição"),
        Map.entry("solucao", "solução"),
        Map.entry("evacuacao", "evacuação"),
        Map.entry("formacao", "formação"),
        Map.entry("comunicacao", "comunicação"),
        Map.entry("transmissao", "transmissão"),
        Map.entry("expressao", "expressão"),
        Map.entry("compreensao", "compreensão"),
        Map.entry("intencao", "intenção"),
        Map.entry("excecao", "exceção"),
        Map.entry("opcao", "opção"),
        Map.entry("opcoes", "opções"),
        Map.entry("acao", "ação"),
        Map.entry("acoes", "ações"),
        Map.entry("nacoes", "nações"),
        Map.entry("ilusao", "ilusão"),
        Map.entry("confusao", "confusão"),
        Map.entry("conclusao", "conclusão"),
        Map.entry("ocasiao", "ocasião"),
        Map.entry("regiao", "região"),
        Map.entry("regioes", "regiões"),
        Map.entry("rebeliao", "rebelião"),
        Map.entry("questao", "questão"),
        Map.entry("questoes", "questões"),
        Map.entry("sugestao", "sugestão"),
        Map.entry("dimensao", "dimensão"),
        Map.entry("dimensoes", "dimensões")
    );

    private static final Pattern PALAVRA;
    static {
        // Alternância ordenada da mais longa para a mais curta (voces antes de voce).
        String alternancia = CORRECOES.keySet().stream()
            .sorted(Comparator.comparingInt(String::length).reversed())
            .reduce((a, b) -> a + "|" + b).orElse("");
        PALAVRA = Pattern.compile(
            INICIO_DE_TERMO + "(" + alternancia + ")(?![\\p{L}\\p{N}])",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    }

    /**
     * Terminação {@code -cao}/{@code -coes} — a MESMA regra do dicionário aplicada como CLASSE em
     * vez de lista: nenhuma palavra do português termina assim sem o til.
     *
     * <h2>Por que regra e não mais entradas no dicionário</h2>
     * Em 07/08 e 12/08 o dicionário foi ampliado com as formas MEDIDAS no acervo de então, o que
     * é palavra a palavra. Medido em 13/08 no Zeta recém-traduzido pela aya: das 119 falas com
     * acento faltando, o dicionário resolvia <b>ZERO</b> — porque o vocabulário de uma obra nova
     * não é o da anterior. As que escapavam eram quase todas a mesma terminação
     * ({@code organizacao}, {@code ambicoes}, {@code cooperacao}, {@code observacao}...).
     *
     * <h2>O risco foi medido antes, não suposto</h2>
     * 151 formas distintas terminadas em {@code -cao}/{@code -coes} no acervo inteiro, 317
     * ocorrências: <b>todas</b> legitimamente {@code -ção}/{@code -ções}. Nenhum estrangeirismo do
     * tipo {@code cacao}/{@code macao}, que seriam os falsos positivos plausíveis.
     *
     * <p>Exige 3+ letras antes da terminação, mantendo fora fragmentos curtos, e a mesma fronteira
     * do ASS do resto da classe — {@code \N} colado à palavra não pode esconder a correção.
     */
    // [cç] e NÃO ç? — o opcional casaria "-ao" e "-oes" sozinhos, e o risco medido em 13/08 cobriu
    // apenas as terminações com C/Ç (151 formas no acervo, todas legítimas). Terminação "-ao" solta
    // é outro conjunto, não medido, e alargar sem medir é como se produz alarme falso em massa.
    //
    // O cedilha entrou porque a saída de 20:30 trouxe 5 formas com Ç e sem til — "atenuaçao",
    // "admiraçao", "evacuaçao", "salvaçao", "recuperaçao": o modelo acerta a cedilha e erra o til.
    private static final Pattern TERMINACAO_CAO = Pattern.compile(
        INICIO_DE_TERMO + "(\\p{L}{3,}?)([cç]oes|[cç]ao)(?![\\p{L}\\p{N}])",
        Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

    /**
     * {@code que} TÔNICO — a terceira forma da classe "nunca é válida sem acento": o {@code que}
     * pronome logo antes de {@code ?} ou {@code !} ("O quê?!", "Por quê?", "Quê?"). Entre ele e a
     * pontuação só podem estar espaço, tag ASS, aspas e reticências. O padrão acha o CANDIDATO; quem
     * decide é a palavra anterior ({@link #tonicoPelaPalavraAnterior}).
     *
     * <p>MEDIDO em 08/10/2026 pelo LanguageTool de produção sobre a legenda entregue: 579 falas
     * com {@code QUE_QUÊ} e 128 com {@code POR_QUE_PORQUE} no acervo, e nenhum corretor do
     * pipeline as tocava. {@code porque?} (uma palavra) fica de fora pela fronteira — a forma
     * certa seria "por quê?", duas palavras, e isso não é reposição de acento.
     */
    private static final Pattern QUE_TONICO = Pattern.compile(
        INICIO_DE_TERMO + "(que)(?=(?:\\s|\\{[^}]*}|[\"'”’»….])*[?!])",
        Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

    /**
     * As únicas palavras que, logo antes do {@code que}, o fazem pronome tônico no acervo. Medido em
     * 08/10/2026 chamando esta regra sobre as 219.586 falas do acervo: das 760 que ela mudaria sem
     * filtro, 753 tinham "o que" (634), "por que" (119), "para que" (2) ou o {@code que} abrindo a
     * frase; as 7 restantes eram conjunção ou "ter que" cortados pela pontuação — "Tenho que!",
     * "Nós temos que!", "Quando foi que...?!", "quer dizer que...!", "aqueles que...!", "sempre tem
     * que...?!" — e o acento as estragaria. "Mas que?" e afins ficam de fora: falso negativo
     * declarado, preferível a acentuar conjunção.
     */
    private static final Set<String> ANTES_DO_QUE_TONICO = Set.of("o", "por", "para", "pra");

    /** Espaço, tag ASS, quebra {@code \N} e marcador {@code [[TAGn]]} no fim do texto. */
    private static final Pattern FIM_NEUTRO = Pattern.compile("(?:\\s|\\{[^}]*}|\\\\[Nn]|\\[\\[TAG\\d+]])+$");

    /** A palavra no fim do texto. */
    private static final Pattern ULTIMA_PALAVRA = Pattern.compile("(\\p{L}+)$");

    /**
     * PROPÓSITO DE NEGÓCIO: devolve o texto com os acentos repostos nas formas do dicionário.
     * <p>INVARIANTES DO DOMÍNIO: só troca formas do dicionário curado, por fronteira de palavra,
     * preservando a caixa; nada mais é tocado.
     * <p>COMPORTAMENTO EM CASO DE FALHA: texto {@code null}/vazio devolve a si mesmo.
     */
    public String normalizar(String texto) {
        return normalizar(texto, Set.of());
    }

    /**
     * Formas do dicionário que TAMBÉM são verbo conjugado: {@code "eu inicio"}, {@code "me
     * exercito"}, {@code "se distancia"}, {@code "ele experiencia"}. O invariante da classe é só
     * tocar forma que sem acento nunca é palavra, e estas são; tirá-las da lista devolveria
     * {@code "o inicio"} sem acento, que é o caso comum. O veto é pelo contexto: verbo vem depois
     * de pronome, substantivo depois de artigo ou preposição (auditoria de 28/10/2026).
     */
    private static final Set<String> TAMBEM_VERBO = Set.of("exercito", "inicio", "distancia", "experiencia");

    /** Pronome que antecede a forma VERBAL das palavras de {@link #TAMBEM_VERBO}. */
    private static final Set<String> PRONOME_ANTES_DE_VERBO = Set.of(
        "eu", "me", "te", "se", "nos", "ele", "ela", "você", "voce");

    private static final Pattern PALAVRA_ANTERIOR = Pattern.compile("(\\p{L}+)(?:\\s|\\\\[Nnh])*$");

    /**
     * PROPÓSITO DE NEGÓCIO: como {@link #normalizar(String)}, sem tocar os termos protegidos da
     * lore da obra. Nome próprio de ficção não leva acento do português: {@code "Hestia Familia"}
     * é termo do DanMachi, e o reforço de lore o escrevia certo para este método desfazê-lo logo
     * depois ({@code "Família"}). O corretor ortográfico já recebia os termos; este não.
     *
     * <p>INVARIANTES DO DOMÍNIO: um achado dentro de qualquer ocorrência de termo protegido (com a
     * fronteira do ASS, sem distinguir caixa) fica como está; o resto segue a regra de sempre.
     *
     * <p>COMPORTAMENTO EM CASO DE FALHA: {@code intocaveis} nulo vale como vazio; texto
     * {@code null}/vazio devolve a si mesmo.
     */
    public String normalizar(String texto, Set<String> intocaveis) {
        if (texto == null || texto.isBlank()) {
            return texto;
        }
        java.util.List<int[]> protegidos = trechosProtegidos(texto, intocaveis);
        Matcher m = PALAVRA.matcher(texto);
        StringBuilder sb = new StringBuilder(texto.length());
        while (m.find()) {
            String achado = m.group(1);
            String minusculo = achado.toLowerCase(Locale.ROOT);
            boolean verbal = TAMBEM_VERBO.contains(minusculo) && depoisDePronome(texto, m.start(1));
            if (verbal || dentroDe(protegidos, m.start(1), m.end(1))) {
                m.appendReplacement(sb, Matcher.quoteReplacement(achado));
                continue;
            }
            m.appendReplacement(sb, Matcher.quoteReplacement(aplicarCaixa(achado, CORRECOES.get(minusculo))));
        }
        m.appendTail(sb);
        String comDicionario = sb.toString();
        String comTerminacao = protegidos.isEmpty() ? aplicarTerminacaoCao(comDicionario)
            : aplicarTerminacaoCao(comDicionario, trechosProtegidos(comDicionario, intocaveis));
        return acentuarQueTonico(comTerminacao);
    }

    private static boolean depoisDePronome(String texto, int inicio) {
        Matcher m = PALAVRA_ANTERIOR.matcher(texto.substring(0, inicio));
        return m.find() && PRONOME_ANTES_DE_VERBO.contains(m.group(1).toLowerCase(Locale.ROOT));
    }

    private static java.util.List<int[]> trechosProtegidos(String texto, Set<String> intocaveis) {
        java.util.List<int[]> trechos = new java.util.ArrayList<>();
        if (intocaveis == null) {
            return trechos;
        }
        for (String termo : intocaveis) {
            if (termo == null || termo.isBlank()) {
                continue;
            }
            Matcher t = org.traducao.projeto.core.texto.FronteiraTermoAss.padraoIgnorandoCaixa(termo).matcher(texto);
            while (t.find()) {
                trechos.add(new int[] {t.start(), t.end()});
            }
        }
        return trechos;
    }

    private static boolean dentroDe(java.util.List<int[]> trechos, int inicio, int fim) {
        for (int[] t : trechos) {
            if (inicio >= t[0] && fim <= t[1]) {
                return true;
            }
        }
        return false;
    }

    /**
     * PROPÓSITO DE NEGÓCIO: repõe o circunflexo do {@code que} tônico — "O que?!" vira "O quê?!",
     * "Por que?" vira "Por quê?". Pública e estática porque é a MESMA regra para a fala recém
     * traduzida (via {@link #normalizar}) e para a revisão do acervo já gravado (3.3): uma regra,
     * um dono, sem cópia entre fatias.
     *
     * <p>INVARIANTES DO DOMÍNIO: só troca o {@code que} que é palavra inteira (com a fronteira do
     * ASS), vem logo antes de {@code ?}/{@code !} E é pronome — depois de "o", "por", "para",
     * "pra" ou abrindo a frase; preserva a caixa ("Que?!" vira "Quê?!", "QUE?!" vira "QUÊ?!");
     * "Por que não?", "O que é isso?", "porque?" e a conjunção cortada ("Tenho que!", "foi
     * que...?!") não mudam.
     *
     * <p>COMPORTAMENTO EM CASO DE FALHA: {@code null}/vazio volta como veio; texto sem o padrão
     * volta byte a byte igual; nunca lança.
     */
    public static String acentuarQueTonico(String texto) {
        if (texto == null || texto.isBlank()) {
            return texto;
        }
        Matcher m = QUE_TONICO.matcher(texto);
        StringBuilder sb = new StringBuilder(texto.length());
        while (m.find()) {
            String que = m.group(1);
            String troca = tonicoPelaPalavraAnterior(texto.substring(0, m.start(1))) ? aplicarCaixa(que, "quê") : que;
            m.appendReplacement(sb, Matcher.quoteReplacement(troca));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    /**
     * PROPÓSITO DE NEGÓCIO: decide se o {@code que} candidato é o pronome tônico pela palavra que o
     * antecede — a única pista que separa "O que?!" (pronome) de "Tenho que!" (locução verbal) com a
     * mesma pontuação depois.
     *
     * <p>INVARIANTES DO DOMÍNIO: ignora espaço, tag, {@code \N} e marcador antes de olhar; texto
     * vazio ou terminado em pontuação é abertura de frase ("Que?!", "Oh? Que?") e conta como
     * tônico; terminado em palavra, só conta se a palavra estiver em {@link #ANTES_DO_QUE_TONICO};
     * terminado em dígito não conta.
     *
     * <p>COMPORTAMENTO EM CASO DE FALHA: nunca lança; na dúvida devolve {@code false} (não acentua).
     */
    private static boolean tonicoPelaPalavraAnterior(String antes) {
        // a quebra \N é separador: sem trocá-la por espaço, "agora?\NO que?" lê a palavra "NO"
        String semFim = FIM_NEUTRO.matcher(antes.replace("\\N", " ").replace("\\n", " ")).replaceAll("");
        if (semFim.isEmpty()) {
            return true;
        }
        char ultimo = semFim.charAt(semFim.length() - 1);
        if (!Character.isLetterOrDigit(ultimo)) {
            return true;
        }
        Matcher palavra = ULTIMA_PALAVRA.matcher(semFim);
        return palavra.find() && ANTES_DO_QUE_TONICO.contains(palavra.group(1).toLowerCase(Locale.ROOT));
    }

    /**
     * PROPÓSITO DE NEGÓCIO: repõe o til da terminação {@code -ção}/{@code -ções} em qualquer
     * palavra, sem depender de ela estar no dicionário.
     *
     * <p>INVARIANTES DO DOMÍNIO: preserva a caixa do que veio antes da terminação, de modo que
     * {@code ORGANIZACAO} vire {@code ORGANIZAÇÃO} e {@code Organizacao} vire {@code Organização};
     * roda DEPOIS do dicionário, então uma forma listada lá continua vencendo.
     *
     * <p>COMPORTAMENTO EM CASO DE FALHA: texto sem a terminação volta byte a byte igual.
     */
    private static String aplicarTerminacaoCao(String texto) {
        return aplicarTerminacaoCao(texto, java.util.List.of());
    }

    private static String aplicarTerminacaoCao(String texto, java.util.List<int[]> protegidos) {
        Matcher m = TERMINACAO_CAO.matcher(texto);
        StringBuilder sb = new StringBuilder(texto.length());
        while (m.find()) {
            if (dentroDe(protegidos, m.start(), m.end())) {
                m.appendReplacement(sb, Matcher.quoteReplacement(m.group()));
                continue;
            }
            String inicio = m.group(1);
            // endsWith e não equals: o grupo agora pode vir com C ou Ç ("coes" ou "çoes").
            boolean plural = m.group(2).toLowerCase(Locale.ROOT).endsWith("oes");
            String terminacao = plural ? "ções" : "ção";
            if (m.group(2).chars().allMatch(Character::isUpperCase)) {
                terminacao = terminacao.toUpperCase(Locale.ROOT);
            }
            m.appendReplacement(sb, Matcher.quoteReplacement(inicio + terminacao));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    private static String aplicarCaixa(String achado, String base) {
        if (achado.length() > 1 && achado.chars().allMatch(Character::isUpperCase)) {
            return base.toUpperCase(Locale.ROOT);
        }
        if (Character.isUpperCase(achado.charAt(0))) {
            return Character.toUpperCase(base.charAt(0)) + base.substring(1);
        }
        return base;
    }
}
