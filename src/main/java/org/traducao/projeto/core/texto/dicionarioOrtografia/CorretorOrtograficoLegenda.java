package org.traducao.projeto.core.texto.dicionarioOrtografia;

import jakarta.enterprise.context.ApplicationScoped;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * PROPÓSITO DE NEGÓCIO: o ponto ÚNICO por onde a legenda passa para ter acento reposto pelo
 * dicionário do sistema. É o que fecha o eixo em que a aya perde para o mistral — medido em
 * 13/08/2026 no Zeta: 119 falas com acento faltando contra 39 do mistral.
 *
 * <h2>Só uma coisa é corrigida, e de propósito</h2>
 * Das seis classificações possíveis, apenas {@link VeredictoPalavra#ACENTO_FALTANDO} autoriza
 * escrita. Resíduo de inglês, termo alemão de lore, romaji e palavra desconhecida são REPORTADOS
 * e não tocados — porque "não é português" não significa "está errado" numa legenda de anime, onde
 * {@code cockpit}, {@code Nordlicht} e {@code sasageyo} são todos legítimos.
 *
 * <p>E mesmo o acento só entra pela regra do {@link CorretorAcentoPorDicionario}: a sugestão tem
 * de ser a MESMA palavra acentuada. {@code colonia} vira {@code colônia}, nunca {@code colonial}.
 *
 * <h2>Ordem no pipeline</h2>
 * Roda DEPOIS do {@code NormalizadorAcentosComuns}, que é determinístico e instantâneo: o que a
 * lista e a regra de terminação já resolvem não chega a custar uma consulta. Sobra para o
 * dicionário o que nenhuma regra alcança — {@code fatidico}, {@code minimo}, {@code aereo},
 * {@code psicicas}, medidos no Unicorn.
 *
 * <h2>Invariantes do domínio</h2>
 * <ul>
 *   <li>Dicionário indisponível devolve o texto BYTE A BYTE igual, e o contador de "não
 *       verificado" sobe — nunca se apresenta como legenda conferida.</li>
 *   <li>Uma consulta por FALA, não por palavra. Consultas repetidas da mesma palavra num
 *       episódio são absorvidas pelo hunspell, que já responde em lote.</li>
 *   <li>Não conhece legenda, cache nem LLM: recebe texto e devolve texto.</li>
 * </ul>
 *
 * <h2>Comportamento em caso de falha</h2>
 * Nunca lança. Qualquer problema devolve o original.
 */
@ApplicationScoped
public class CorretorOrtograficoLegenda {

    private static final Logger log = LoggerFactory.getLogger(CorretorOrtograficoLegenda.class);

    private final ClassificadorQuatroIdiomas classificador;
    private final DicionarioOrtograficoPort portugues;

    /**
     * O dicionário de INGLÊS, guardado à parte do classificador.
     *
     * <p>O acervo tem fala inteira que nunca foi traduzida — {@code "That might not be a bad
     * idea."}, {@code "Call again after restrictions have been lifted."} O corretor português
     * olhava para {@code idea} e {@code have}, via que {@code ideá} e {@code havê} existem, e
     * gravava isso. Oito falas em 24/08/2026.
     *
     * <p>O classificador não serve para responder isso: ele decide {@code ACENTO_FALTANDO} ANTES
     * de perguntar ao inglês, então {@code idea} nunca chega a ser rotulado como inglês.
     */
    private final DicionarioOrtograficoPort ingles;
    /** O rótulo de romaji: sem ele, "a linha tem japonês?" não tem resposta (24/09/2026). */
    private final DicionarioOrtograficoPort romaji;
    private final AtomicInteger corrigidas = new AtomicInteger();
    private final AtomicInteger naoVerificadas = new AtomicInteger();

    /**
     * Palavra -> correção conhecida. Entrada com valor vazio significa "já perguntei e não há
     * correção", que é informação tão útil quanto a correção em si.
     *
     * <h2>O prejuízo que obrigou a existir, medido</h2>
     * A primeira versão consultava o dicionário A CADA FALA, e o custo do hunspell é o ARRANQUE do
     * processo, não a palavra. No Unicorn de 13/08/2026 isso significou 5.643 processos e levou o
     * episódio inteiro de <b>28m16s para 68m01s</b> — 2,4x mais lento pelo mesmo resultado.
     *
     * <p>Uma legenda repete vocabulário à beça: 5.643 falas do Unicorn têm só 4.688 formas
     * distintas, e a maioria aparece em dezenas de falas. Com o cache, cada forma é perguntada UMA
     * vez na execução inteira.
     */
    private final Map<String, String> memoria = new java.util.concurrent.ConcurrentHashMap<>();

    public CorretorOrtograficoLegenda() {
        this.portugues = new HunspellDicionarioAdapter("hunspell", "pt_BR");
        this.ingles = new HunspellDicionarioAdapter("hunspell", "en_US");
        this.romaji = new HunspellDicionarioAdapter("hunspell", "ja_ROMAJI");
        // O francês entrou em 14/08/2026, quando o acervo passou a ter obra traduzida A PARTIR
        // dele. Não é preciosismo: no primeiro run do Memories pela faixa francesa, o detector de
        // nome próprio acusou seis palavras e cinco eram francês comum (Dieu, Octobre, Juillet,
        // Maman, Californie), porque sem este dicionário elas não pertencem a idioma nenhum.
        // O ja_ROMAJI é gerado do IPADIC pelo gerar-dicionario-romaji.ps1 (129.745 formas) e
        // rotula japonês escrito em alfabeto latino, que nenhum dos outros reconhece. Ligado no
        // mesmo passo em que se instala: dicionário parado em C:\Hunspell não classifica nada.
        this.classificador = new ClassificadorQuatroIdiomas(
            portugues,
            ingles,
            new HunspellDicionarioAdapter("hunspell", "de_DE"),
            new HunspellDicionarioAdapter("hunspell", "fr_FR"),
            romaji,
            // O ESPANHOL entrou em 26/08/2026: 54 ocorrencias no acervo, todas caindo em
            // DESCONHECIDA junto com termo de franquia e nome de personagem. Nenhuma obra foi
            // traduzida a partir dele — quando aparece, e deriva do modelo.
            new HunspellDicionarioAdapter("hunspell", "es_ES"));
    }

    /**
     * PROPÓSITO DE NEGÓCIO: costura para o teste exercitar ESTE caminho — o que a Tradução
     * Local percorre de verdade — sem depender de haver hunspell instalado na máquina.
     *
     * <h2>Por que a costura existe</h2>
     * O reparo de terminação nasceu dentro de {@code CorretorAcentoPorDicionario#corrigir()},
     * que o pipeline NÃO chama: ele consome apenas os helpers estáticos daquela classe. O teste
     * passava exercitando o método direto enquanto a legenda continuava saindo com
     * {@code Esquadroo}. Sem um jeito de instanciar este corretor com dicionário controlado, o
     * caminho real seguiria sem teste — e foi exatamente ali que o defeito morava.
     *
     * <p>INVARIANTES DO DOMÍNIO: o classificador de quatro idiomas recebe o MESMO dicionário em
     * todas as posições. Serve para exercitar a correção, não para classificar idioma — teste
     * que dependa da classificação usa o construtor real.
     *
     * <p>COMPORTAMENTO EM CASO DE FALHA: idêntico ao construtor de produção.
     *
     * @param dicionario dicionário de português a consultar
     */
    CorretorOrtograficoLegenda(DicionarioOrtograficoPort dicionario) {
        this.portugues = dicionario;
        // O MESMO dublê em todas as posições, inglês incluído. Serve para exercitar a correção,
        // não para separar idioma — teste sobre idioma usa o construtor real, e o Javadoc acima
        // diz isso desde que a costura nasceu.
        this.ingles = dicionario;
        this.romaji = dicionario;
        this.classificador = new ClassificadorQuatroIdiomas(
            dicionario, dicionario, dicionario, dicionario, dicionario);
    }

    /**
     * PROPÓSITO DE NEGÓCIO: devolve a fala com os acentos que faltavam, sem tocar em mais nada.
     *
     * <p>INVARIANTES DO DOMÍNIO: só palavras classificadas como {@code ACENTO_FALTANDO} são
     * alteradas; tags, quebras {@code \N} e o resto do texto voltam byte a byte.
     *
     * <p>COMPORTAMENTO EM CASO DE FALHA: devolve o texto recebido.
     */
    public String corrigir(String texto) {
        return corrigir(texto, Set.of());
    }

    /**
     * PROPÓSITO DE NEGÓCIO: expõe a classificação de idioma POR PALAVRA — o mesmo classificador de
     * dicionário que sustenta a correção de acento — para quem só precisa saber a LÍNGUA, não
     * corrigir. Nasceu para o achatador de karaokê (Passo 2) decidir se um par com {@code \N} é
     * {@code original\Ntradução} (preserva as 2 linhas) ou verso monolíngue quebrado pelo fansub
     * (achata para 1). Substitui a heurística de lista de palavras à mão, que colidia com o inglês
     * ({@code do}/{@code no}/{@code so}); o dicionário decide por PONTUAÇÃO ({@code "Do you feel
     * alone"} tem 3 palavras inglesas contra 1 portuguesa, entao e ingles).
     *
     * <p>INVARIANTES DO DOMÍNIO: consulta em LOTE (o custo do hunspell é o arranque do processo, não
     * a palavra); NÃO corrige nada. Dicionário indisponível devolve {@link VeredictoPalavra#NAO_VERIFICADO}
     * para tudo, e cabe a quem chama tratar como "não sei" e cair no próprio fallback.
     *
     * <p>COMPORTAMENTO EM CASO DE FALHA: nunca lança; entrada nula/vazia devolve mapa vazio.
     */
    public Map<String, VeredictoPalavra> classificarPalavras(java.util.Collection<String> palavras) {
        return classificador.classificar(palavras);
    }

    /**
     * PROPÓSITO DE NEGÓCIO: o dicionário de INGLÊS respondeu ao menos uma vez nesta execução? Existe
     * para quem decide PORTUGUÊS × INGLÊS pela classificação: se o inglês está indisponível, o
     * veredicto {@code RESIDUO_INGLES} nunca sai e a decisão fica cega para um dos lados — quem
     * pergunta deve cair no próprio fallback em vez de confiar num resultado só de português.
     *
     * <p>INVARIANTES DO DOMÍNIO: o estado é preguiçoso (o hunspell só marca disponível após a
     * primeira consulta), então isto só é confiável DEPOIS de {@link #classificarPalavras}.
     */
    public boolean inglesDisponivel() {
        return ingles != null && ingles.disponivel();
    }

    /**
     * PROPÓSITO DE NEGÓCIO: o dicionário de ROMAJI respondeu nesta execução? Quem decide "esta
     * linha de letra tem japonês?" pelo veredicto {@link VeredictoPalavra#ROMAJI} precisa saber se
     * a ausência do rótulo é "não há romaji" ou "não havia como rotular" (regra 23).
     *
     * <p>INVARIANTES DO DOMÍNIO: preguiçoso como {@link #inglesDisponivel()} — só é confiável
     * DEPOIS de {@link #classificarPalavras}.
     *
     * <p>COMPORTAMENTO EM CASO DE FALHA: sem o adaptador, {@code false}.
     */
    public boolean romajiDisponivel() {
        return romaji != null && romaji.disponivel();
    }

    /** Palavra como o dicionário a consulta: letras, com apóstrofo ou hífen internos. */
    private static final java.util.regex.Pattern PALAVRA_DO_DICIONARIO =
        java.util.regex.Pattern.compile("[\\p{L}][\\p{L}'\\-]*");

    /** {@code \N}, {@code \n} e {@code \h} do ASS: separadores, nunca a letra inicial da palavra seguinte. */
    private static final java.util.regex.Pattern QUEBRA_OU_ESPACO_ASS = java.util.regex.Pattern.compile("\\\\[Nnh]");

    /**
     * PROPÓSITO DE NEGÓCIO: DONO ÚNICO de "quais palavras deste texto se perguntam ao dicionário".
     * Nasceu em 24/09/2026 quando a mesma regex existia no achatador (novoKaraoke) e no plano da
     * tradução de karaokê — a catraca de regra duplicada entre fatias reprovou a segunda cópia.
     *
     * <p>INVARIANTES DO DOMÍNIO: preserva a grafia (o veredicto é por forma exata) e a ordem. As
     * quebras e o espaço fixo do ASS ({@code \N}, {@code \n}, {@code \h}) SEPARAM palavras: sem isso
     * {@code "Dreamer...\NSonhador..."} dava a palavra {@code "NSonhador"} (DESCONHECIDA), o lado
     * português do par ficava mudo e o achatador colava o par numa linha (0083, 27:00.47, 24/09/2026).
     *
     * <p>COMPORTAMENTO EM CASO DE FALHA: nulo devolve lista vazia.
     */
    public static java.util.List<String> palavrasDe(String texto) {
        java.util.List<String> palavras = new java.util.ArrayList<>();
        if (texto == null) {
            return palavras;
        }
        java.util.regex.Matcher m = PALAVRA_DO_DICIONARIO.matcher(QUEBRA_OU_ESPACO_ASS.matcher(texto).replaceAll(" "));
        while (m.find()) {
            palavras.add(m.group());
        }
        return palavras;
    }

    /**
     * Compara ignorando caixa: o dicionário propõe a forma capitalizada ou não conforme a posição
     * na frase, e {@code "apsaras"} no meio da fala é o mesmo nome de {@code "Apsaras"}.
     */
    private static boolean contemIgnorandoCaixa(Set<String> lista, String palavra) {
        for (String termo : lista) {
            if (termo != null && termo.equalsIgnoreCase(palavra)) {
                return true;
            }
        }
        return false;
    }

    /** Uma palavra dentro de um termo de lore, pela mesma régua de letras das candidatas. */
    private static final java.util.regex.Pattern PECA_DE_TERMO = java.util.regex.Pattern.compile("\\p{L}+");

    /**
     * PROPÓSITO DE NEGÓCIO: as palavras de dentro dos termos de lore COMPOSTOS ({@code "Aida
     * Rayhunton"}, {@code "Apocalypse Virus"}, {@code "Para-RAID"}). A lista de intocáveis compara
     * a candidata — UMA palavra — com o termo inteiro, então termo de duas palavras não protegia
     * nenhuma das duas.
     *
     * <h2>O prejuízo, medido em 14/10/2026</h2>
     * A Reconguista I saiu da 2.1 com {@code Aída} em 20 falas (o dicionário acentua "Aida", e a
     * lore só a tem como {@code Aida Rayhunton} e {@code Aida Surugan}). O mesmo defeito já tinha
     * sido visto em 12/10 no Guilty Crown ({@code Apocalypse Vírus}, 14 falas no acervo) e ficado
     * fora do escopo. Pelo corretor de produção, das 927 palavras que só existem dentro de termo
     * composto em todas as lores, 15 seriam acentuadas: Aida, Angelo, Claudia, Cleo, Domenico,
     * Elmer, Genesis, Ilion, Jose, KaKa, Monica, Nadia, Norio, Oasis e Virus.
     *
     * <p>INVARIANTES DO DOMÍNIO: a peça vale com a CAIXA do termo. É o que separa o nome do
     * substantivo comum com o mesmo sinal: {@code "Virus"} de {@code "Apocalypse Virus"} fica, e
     * {@code "o virus se espalhou"} continua virando {@code "vírus"}. Termo de uma palavra só segue
     * a regra antiga (sem caixa) no chamador.
     *
     * <p>COMPORTAMENTO EM CASO DE FALHA: termo nulo é pulado; lista sem termo composto devolve
     * conjunto vazio, e o comportamento fica o de antes.
     */
    private static Set<String> palavrasDeTermoComposto(Set<String> termos) {
        Set<String> pecas = new java.util.HashSet<>();
        for (String termo : termos) {
            if (termo == null) {
                continue;
            }
            java.util.List<String> desteTermo = new java.util.ArrayList<>();
            java.util.regex.Matcher m = PECA_DE_TERMO.matcher(termo);
            while (m.find()) {
                desteTermo.add(m.group());
            }
            if (desteTermo.size() > 1) {
                pecas.addAll(desteTermo);
            }
        }
        return pecas;
    }

    /**
     * PROPÓSITO DE NEGÓCIO: mesma correção de acento, com uma lista de palavras INTOCÁVEIS — os
     * termos de lore da obra. Nome próprio de ficção não leva acento do português, e o dicionário
     * não tem como saber que aquilo é um nome.
     *
     * <h2>O prejuízo, medido no acervo em 18/08/2026</h2>
     * Três termos de lore chegaram acentuados à legenda entregue, em 32 falas. Os três são
     * defeito — inclusive o que parecia exceção legítima:
     * <pre>
     *   Apsaras -> Apsarás   23 falas   (o mobile armor do 08th MS Team)
     *   Bosnia  -> Bósnia     7 falas   (uma NAVE do Zeta, nao o pais)
     *   Cardeas -> Cárdeas    2 falas   (Cardeas Vist, do Unicorn)
     * </pre>
     * O caso {@code Bosnia} é o melhor argumento para esta lista existir: no Zeta a fala é
     * <i>"Send a signal flare to the Bosnia"</i> e <i>"the Alexandria, Bosnia and Sichuan"</i> —
     * são navios. O acento transformou uma nave num país, e o resultado é português impecável,
     * o que torna o defeito invisível para qualquer revisão que não confira contra o inglês.
     *
     * <p>Consertar caso a caso no {@code correcoesTerminologia} funciona para o que já foi
     * traduzido; não impede a próxima obra de nascer com o mesmo dano.
     *
     * <p>INVARIANTES DO DOMÍNIO: a comparação com o termo inteiro ignora caixa — o dicionário
     * propõe forma capitalizada ou não conforme a posição na frase. Palavra de dentro de termo
     * composto ({@link #palavrasDeTermoComposto}) também é intocável, com a caixa do termo. Lista
     * vazia reproduz exatamente o comportamento anterior.
     *
     * <p>COMPORTAMENTO EM CASO DE FALHA: lista nula é tratada como vazia; texto nulo volta nulo.
     */
    public String corrigir(String texto, Set<String> intocaveis) {
        if (texto == null || texto.isBlank()) {
            return texto;
        }
        try {
            Set<String> candidatas = CorretorAcentoPorDicionario.candidatas(texto);
            if (intocaveis != null && !intocaveis.isEmpty()) {
                Set<String> pecas = palavrasDeTermoComposto(intocaveis);
                Set<String> semLore = new java.util.LinkedHashSet<>();
                for (String candidata : candidatas) {
                    if (!contemIgnorandoCaixa(intocaveis, candidata) && !pecas.contains(candidata)) {
                        semLore.add(candidata);
                    }
                }
                candidatas = semLore;
            }
            if (candidatas.isEmpty()) {
                return texto;
            }

            if (!aquecer(candidatas)) {
                return texto;
            }

            Map<String, String> acentos = new java.util.LinkedHashMap<>();
            for (String c : candidatas) {
                String correcao = memoria.get(c);
                if (correcao != null && !correcao.isEmpty()) {
                    acentos.put(c, correcao);
                }
            }
            if (acentos.isEmpty()) {
                return texto;
            }
            corrigidas.addAndGet(acentos.size());
            return CorretorAcentoPorDicionario.aplicar(texto, acentos);
        } catch (RuntimeException e) {
            log.debug("Correção ortográfica ignorada nesta fala: {}", e.getMessage());
            return texto;
        }
    }

    /**
     * PROPÓSITO DE NEGÓCIO: pergunta ao dicionário sobre um LOTE de palavras de uma vez, para que
     * as consultas seguintes sobre elas não custem nada.
     *
     * <h2>A cicatriz: 283 segundos para corrigir zero falas</h2>
     * A memória sempre evitou a SEGUNDA pergunta sobre uma palavra. O que ela nunca evitou foi a
     * primeira — e é a primeira que custa: cada fala que traz UMA palavra inédita paga um processo
     * externo inteiro. Em 24/08/2026 a tela 3.3 passou por 3.518 falas de seis episódios e o
     * relógio por elo mostrou o estrago:
     *
     * <pre>
     *   genero (determinante)     0 agiu ·   0,1s (0,04 ms/fala)
     *   acento por POS tagger     0 agiu ·   6,3s (1,78 ms/fala)
     *   acento por padrao        38 agiu ·   0,2s (0,04 ms/fala)
     *   acento por dicionario     0 agiu · 283,3s (80,53 ms/fala)   &lt;-- 97% do tempo, zero ganho
     * </pre>
     *
     * O acervo inteiro levaria quase três horas, e o operador que olha a tela não distingue
     * "lenta" de "travada" — o detector de travamento do Quarkus, aliás, também não distinguiu.
     *
     * <p>Perguntando o arquivo inteiro de uma vez, a resposta vem num processo só e toda fala
     * seguinte encontra tudo na memória.
     *
     * <h2>Invariantes do domínio</h2>
     * <ul>
     *   <li>Aquecer NÃO altera texto nenhum — só povoa a memória. Chamar ou não chamar produz a
     *       MESMA legenda; muda apenas o tempo.</li>
     *   <li>Guarda também o que não tem correção: "já perguntei e não há" é a maior parte das
     *       palavras, e é o que impede a pergunta de voltar.</li>
     * </ul>
     *
     * <p>COMPORTAMENTO EM CASO DE FALHA: dicionário fora do ar devolve {@code false} e conta uma
     * não-verificação; nada é memorizado, e quem chamou decide o que fazer.
     *
     * @param palavras as formas a perguntar; as já conhecidas são ignoradas
     * @return {@code true} se a memória está boa para estas palavras
     */
    public boolean aquecer(Set<String> palavras) {
        // Só o que NUNCA foi perguntado chega ao processo externo. É o que separa 5.643 consultas
        // de algumas dezenas: legenda repete vocabulário, e a resposta do dicionário para uma
        // palavra não muda no meio da execução.
        Set<String> inéditas = new java.util.LinkedHashSet<>();
        for (String c : palavras) {
            if (!memoria.containsKey(c)) {
                inéditas.add(c);
            }
        }
        if (inéditas.isEmpty()) {
            return true;
        }
        Map<String, Set<String>> sugestoes = portugues.sugestoes(inéditas);
        if (!portugues.disponivel()) {
            naoVerificadas.incrementAndGet();
            return false;
        }
        Map<String, String> novas =
            new java.util.LinkedHashMap<>(CorretorAcentoPorDicionario.apenasAcentuacoes(sugestoes));

        // O REPARO DE TERMINAÇÃO ENTRA AQUI, e não só no corrigir() de instância do
        // CorretorAcentoPorDicionario — porque é ESTE caminho que a Tradução Local percorre.
        //
        // A primeira versão do reparo ficou naquele outro corrigir(), que a produção não usa: o
        // pipeline consome apenas os helpers estáticos daqui. O teste passava exercitando o método
        // direto enquanto a legenda continuava saindo com "Esquadroo". Foi Paulo quem perguntou "e
        // por que o reparo não está ligado?" — código verde num caminho que ninguém percorre é a
        // definição de guarda cega.
        novas.putAll(CorretorAcentoPorDicionario.reparosDeTerminacaoAo(
            portugues, inéditas, novas.keySet()));

        // Guarda TAMBÉM o que não tem correção: "já perguntei e não há" evita repetir a pergunta,
        // e é a maior parte das palavras.
        for (String c : inéditas) {
            memoria.put(c, novas.getOrDefault(c, ""));
        }
        return true;
    }

    /**
     * PROPÓSITO DE NEGÓCIO: a mesma coisa, recebendo os TEXTOS em vez das palavras — quem chama
     * tem falas na mão, não vocabulário.
     *
     * <p>INVARIANTES DO DOMÍNIO: quem extrai as formas é o mesmo extrator que
     * {@link #corrigir(String, Set)} usa. Uma segunda extração aqui divergiria da primeira, e
     * palavra que o aquecimento não enxergasse voltaria a custar um processo por fala.
     *
     * <p>COMPORTAMENTO EM CASO DE FALHA: coleção nula ou vazia devolve {@code true} (nada a
     * fazer); dicionário fora do ar devolve {@code false}.
     */
    public boolean aquecerComTextos(java.util.Collection<String> textos) {
        if (textos == null || textos.isEmpty()) {
            return true;
        }
        Set<String> todas = new java.util.LinkedHashSet<>();
        for (String t : textos) {
            if (t != null && !t.isBlank()) {
                todas.addAll(CorretorAcentoPorDicionario.candidatas(t));
            }
        }
        // O INGLES tambem, e no mesmo lote. O portao de idioma pergunta a ele por fala; sem
        // aquecer, cada fala pagaria um processo externo — que e exatamente o defeito de 80
        // ms/fala corrigido horas antes, reintroduzido por outra porta.
        if (!todas.isEmpty()) {
            ingles.desconhecidas(todas);
        }
        return aquecer(todas);
    }

    /**
     * PROPÓSITO DE NEGÓCIO: diz se uma fala está <b>predominantemente em inglês</b> — ou seja, se
     * ela nunca foi traduzida e não deve ser corrigida como se fosse português.
     *
     * <h2>As oito falas que obrigaram este método a existir</h2>
     * Em 24/08/2026 a leitura do acervo pegou o corretor de acento estragando fala inglesa:
     *
     * <pre>
     *   "That might not be a bad idea."                       -> "a bad ideá"     4 falas
     *   "Is the place where you aim,"                         -> "the placê"      2 falas
     *   "Call again after restrictions have been lifted."     -> "havê been"      2 falas
     * </pre>
     *
     * <p>O dicionário português não sabe o que é {@code idea}; sabe que {@code ideá} existe, e
     * troca. Não é erro do dicionário — é pergunta feita fora do domínio dele.
     *
     * <h2>Por que o classificador de quatro idiomas NÃO responde isto</h2>
     * Ele decide {@link VeredictoPalavra#ACENTO_FALTANDO} <b>antes</b> de consultar o inglês, de
     * modo que {@code idea} jamais chega a ser rotulado como inglês. A ordem está certa para o
     * propósito dele e errada para esta pergunta, então esta pergunta ganhou método próprio em vez
     * de uma mudança na ordem que quebraria o outro uso.
     *
     * <h2>Por que a régua é "a maioria", e não "existe alguma palavra inglesa"</h2>
     * Uma tentativa mais larga foi MEDIDA e recusada: exigir que a fala se prove portuguesa por
     * diacrítico ou palavra-função barrava {@code "Chegamos a borda do territorio."}, que é
     * português perfeitamente normal. O custo era ~19 falas legítimas para salvar 8 — a guarda
     * gastava mais do que rendia.
     *
     * <h2>Invariantes do domínio</h2>
     * <ul>
     *   <li>Palavra que os DOIS dicionários aceitam não conta para nenhum lado: {@code total},
     *       {@code radio} e {@code area} existem nos dois idiomas e não decidem nada.</li>
     *   <li>Empate NÃO é inglês. Na dúvida a fala é tratada como portuguesa e segue para a
     *       correção — falhar para o lado de corrigir é o comportamento antigo, e o que se está
     *       barrando aqui é só o caso claro.</li>
     * </ul>
     *
     * <p>COMPORTAMENTO EM CASO DE FALHA: dicionário indisponível devolve {@code false} (não é
     * inglês), porque bloquear a correção inteira por falta de dicionário seria trocar um defeito
     * de 8 falas por um de mil.
     *
     * @param texto a fala como está no arquivo
     * @return {@code true} se a maioria das palavras julgáveis é inglesa e não portuguesa
     */
    public boolean predominantementeInglesa(String texto) {
        if (texto == null || texto.isBlank()) {
            return false;
        }
        Set<String> palavras = CorretorAcentoPorDicionario.candidatas(texto);
        if (palavras.size() < 3) {
            // Fala curta nao tem evidencia para decidir idioma. "Sim!" nao e ingles nem deixa
            // de ser, e chutar aqui barraria correcao boa em legenda cheia de fala curta.
            return false;
        }
        Set<String> foraDoIngles = ingles.desconhecidas(palavras);
        Set<String> foraDoPortugues = portugues.desconhecidas(palavras);
        if (!ingles.disponivel() || !portugues.disponivel()) {
            return false;
        }
        int inglesas = 0;
        int portuguesas = 0;
        for (String p : palavras) {
            boolean ehIngles = !foraDoIngles.contains(p);
            boolean ehPortugues = !foraDoPortugues.contains(p);
            if (ehIngles && !ehPortugues) {
                inglesas++;
            } else if (ehPortugues && !ehIngles) {
                portuguesas++;
            }
        }
        return inglesas > portuguesas && inglesas * 2 >= palavras.size();
    }

    /**
     * PROPÓSITO DE NEGÓCIO: classifica sem corrigir — para o relatório dizer o que é resíduo de
     * inglês, termo de lore e romaji, em vez de chamar tudo de erro.
     *
     * <p>COMPORTAMENTO EM CASO DE FALHA: mapa vazio.
     */
    public Map<String, VeredictoPalavra> classificar(Set<String> palavras) {
        try {
            return classificador.classificar(palavras);
        } catch (RuntimeException e) {
            return Map.of();
        }
    }

    /** Quantas palavras tiveram acento reposto nesta execução. */
    public int totalCorrigidas() {
        return corrigidas.get();
    }

    /** Quantas falas passaram SEM verificação — o estado 2, que não é aprovação. */
    public int totalNaoVerificadas() {
        return naoVerificadas.get();
    }

    /** Se o verificador respondeu ao menos uma vez. */
    public boolean disponivel() {
        return portugues.disponivel();
    }
}
