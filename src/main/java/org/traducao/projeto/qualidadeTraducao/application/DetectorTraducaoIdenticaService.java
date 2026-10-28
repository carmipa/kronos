package org.traducao.projeto.qualidadeTraducao.application;

import org.traducao.projeto.core.texto.FronteiraTermoAss;

import org.springframework.stereotype.Service;
import org.traducao.projeto.qualidadeTraducao.domain.LoreAtivaPort;

import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * PROPÓSITO DE NEGÓCIO: decide se uma fala pode legitimamente permanecer idêntica ao
 * original (nomes próprios, números, siglas, termos de lore) ou se a igualdade é sinal
 * de que o LLM simplesmente devolveu a fala sem traduzir. Impede que manutenção ou
 * retomada do cache apague nomes canônicos e, simultaneamente, não aceite frases
 * inglesas como tradução. Além da lista global fixa, consulta os termos protegidos e a
 * lore do contexto ATIVO através da porta {@link LoreAtivaPort}, para que um termo novo
 * anexado ao contexto selecionado seja protegido sem editar este detector — e sem que o
 * peer {@code qualidadeTraducao} dependa da fatia {@code contexto}.
 *
 * <h2>Invariantes do domínio</h2>
 * <ul>
 *   <li>A lore ativa (via porta) é a fonte dos termos protegidos; expressões
 *       conversacionais comuns continuam exigindo tradução.</li>
 *   <li>A precedência das verificações é preservada: limpeza de tags, gagueira e
 *       pontuação; caso de caractere único; fala só de números; palavra única; então lore
 *       ativa e, por fim, heurística de capitalização.</li>
 *   <li>Número não se traduz: fala composta apenas de dígitos e pontuação (contagem
 *       regressiva, altímetro, coordenadas) é sempre identidade legítima.</li>
 * </ul>
 *
 * <h2>Comportamento em caso de falha</h2>
 * Texto sem evidência suficiente é preservado para evitar uma decisão destrutiva; a
 * porta não lança, então lore/termos ausentes apenas recaem nas heurísticas globais.
 */
@Service
public class DetectorTraducaoIdenticaService {

    private static final String INICIO_DE_TERMO = FronteiraTermoAss.INICIO;

    private static final Pattern PADRAO_REMOVE_TAGS_ASS = Pattern.compile("\\{[^}]+}");
    private static final Pattern QUEBRA_ASS = Pattern.compile("\\\\[Nnh]");
    private static final Pattern PADRAO_GAGUEIRA_NOME = Pattern.compile(
        "(?iu)" + INICIO_DE_TERMO + "([\\p{L}])-(?=\\1[\\p{L}])");
    /** Fala só com dígitos e espaços (a pontuação já saiu na limpeza): número não se traduz. */
    private static final Pattern SOMENTE_NUMEROS = Pattern.compile("[\\d\\s]+");

    /**
     * Fala cortada no meio — reticências no fim, com ou sem espaço. Em legenda de anime é o
     * personagem sendo interrompido, e o que sobra costuma ser o começo de um nome próprio.
     * Usado só para BAIXAR o piso de letras do nome próprio, nunca para aceitar sozinho.
     */
    private static final Pattern FALA_INTERROMPIDA = Pattern.compile("(\\.{2,}|…)\\s*$");

    private final LoreAtivaPort loreAtiva;

    /**
     * PROPÓSITO DE NEGÓCIO: recebe a porta de lore ativa que substitui o acoplamento
     * direto ao gerenciador de contexto, mantendo o detector dentro do peer de qualidade.
     * <p>INVARIANTES DO DOMÍNIO: guarda a porta recebida; não a substitui nem cria
     * implementação própria.
     * <p>COMPORTAMENTO EM CASO DE FALHA: não valida o argumento; injeção CDI garante o bean.
     *
     * @param loreAtiva porta de acesso aos termos protegidos e à lore do contexto ativo
     */
    public DetectorTraducaoIdenticaService(LoreAtivaPort loreAtiva) {
        this.loreAtiva = loreAtiva;
    }

    private static final Set<String> PALAVRAS_INGLES_COMUNS = Set.of(
        "hello", "hi", "hey", "goodbye", "bye", "yes", "no", "yeah", "yep", "nope",
        "thanks", "thank", "sorry", "please", "wait", "stop", "go", "come", "run",
        "what", "why", "who", "where", "when", "how", "right", "okay", "ok", "fine",
        "good", "morning", "night", "help", "me", "you", "away", "back", "welcome"
    );

    private static final Pattern PADRAO_INTERJEICAO = Pattern.compile("^(o+h+|a+h+|u+h+|e+h+|e+r+|h*m+|u+g+h+|t+c+h+|h+m+p+h+)$");

    /**
     * Léxico de EVIDÊNCIA: inglês que uma legenda PT-BR tem obrigação de traduzir. Diferente de
     * {@link #PALAVRAS_INGLES_COMUNS} (consultada só para palavra única), este conjunto é o
     * gatilho da recusa por evidência e vale para a fala inteira.
     *
     * <p>A lista é DERIVADA DE MEDIÇÃO, não de suposição: em 2026-07-22 varri as 55.322 entradas
     * distintas dos 216 caches versionados e encontrei 1.569 falas publicadas idênticas ao
     * original. Destas, 128 ocorrências em 25 textos distintos eram inglês corrente — o resto era
     * nome próprio legítimo (995 de uma palavra: Kamille, Judau, Leina, Roux, Katz...). As
     * palavras abaixo são exatamente as que apareceram nessas 25 falas.
     *
     * <p>Ocorrências medidas: {@code Next Episode} 63x, {@code Roger!} 29x, {@code Gotcha!} 12x,
     * mais {@code Report!}, {@code Move!}, {@code Fire!}, {@code Big Brother}, {@code Little
     * Sister}, {@code Ready, Lieutenant!}, {@code Lady Haman! Hurry!}, {@code Heavy!},
     * {@code Enter.} e {@code "Doctor Flanagan"...?}.
     *
     * <p>CRESCE POR EVIDÊNCIA, nunca por palpite: acrescentar palavra sem ocorrência medida
     * transforma esta lista no mesmo tipo de heurística cega que ela existe para substituir.
     *
     * <p>E ENCOLHE POR DECISÃO EDITORIAL. {@code roger} saiu em 2026-07-23: a medição mostrou 220
     * ocorrências no acervo, sendo 179 traduzidas como "Entendido" e 34 mantidas em inglês — e o
     * Paulo decidiu que manter em inglês está correto, porque "Roger" é compreendido em PT-BR no
     * contexto de rádio. Mantê-lo aqui transformaria 34 falas HOJE corretas em pendência. Nem
     * toda palavra inglesa numa legenda PT-BR é defeito; o léxico só pode conter o que a obra
     * tem obrigação de traduzir.
     *
     * <p>As poucas entradas SEM ocorrência medida estão marcadas abaixo e entraram por revisão
     * arquitetural — são componentes de termos compostos que a lore do 08th declarou
     * ({@code Far East Division}), incluídos para provar que declarar o termo composto NÃO
     * concede imunidade às suas partes soltas.
     */
    private static final Set<String> PALAVRAS_INGLES_TRADUZIVEIS = Set.of(
        // medidas nos caches (2026-07-22)
        "next", "episode", "preview", "gotcha", "report", "move", "heavy",
        "enter", "big", "brother", "little", "sister", "fire", "ready", "lieutenant",
        "lady", "hurry", "doctor",
        // por revisão arquitetural: partes de termos compostos declarados na lore
        "east", "division", "point", "sand",
        // vocabulário militar corrente da mesma família das medidas
        "captain", "sergeant", "commander", "sir", "enemy",
        "target", "retreat", "advance", "hold", "cover", "watch", "listen", "look",
        // medidas em 08/10/2026: "Mom?!" voltou do modelo como "Mom?!!" e a régua o absolvia como
        // nome; nos caches, 0 de 59 falas curtas com mom/dad estão publicadas idênticas ao inglês
        "mom", "dad"
    );

    /**
     * PROPÓSITO DE NEGÓCIO: decide se um conteúdo idêntico pode permanecer no
     * cache por ser nome, sigla, número ou termo canônico da lore.
     *
     * <p>INVARIANTES DO DOMÍNIO: expressões conversacionais em inglês não são
     * protegidas só por estarem em Title Case; termo exato da lore tem prioridade.
     *
     * <p>COMPORTAMENTO EM CASO DE FALHA: texto nulo/sem base suficiente é
     * preservado para evitar limpeza destrutiva por heurística fraca.
     */
    public boolean deveManterIdentico(String texto) {
        // CONJUNÇÃO DELIBERADA (não disjunção): a régua por evidência só pode RECUSAR o que as
        // heurísticas aceitariam, nunca aceitar o que elas recusam. Isso torna a mudança
        // monótona — nenhuma fala que hoje é mandada traduzir passa a ser publicada em inglês —
        // e confina toda a regressão possível às palavras escritas em
        // PALAVRAS_INGLES_TRADUZIVEIS, que são revisáveis em diff.
        return identidadeAceitaPelasHeuristicas(texto) && !temEvidenciaDeInglesTraduzivel(texto);
    }

    /**
     * PROPÓSITO DE NEGÓCIO: régua histórica de identidade — nome, número, sigla ou termo de lore.
     * Preservada intacta como PRIMEIRO filtro; o que ela recusa continua recusado.
     *
     * <p>INVARIANTES DO DOMÍNIO: mesma precedência de sempre (caractere único, palavra única,
     * termo da lore, composição de termos, capitalização).
     *
     * <p>COMPORTAMENTO EM CASO DE FALHA: texto nulo devolve {@code true} (preservação segura).
     */
    private boolean identidadeAceitaPelasHeuristicas(String texto) {
        if (texto == null) {
            return true;
        }
        String textoLimpo = limpar(texto);

        // Um único caractere visível (letra de karaokê por letra, interjeição
        // "A", numeral) não dá base para julgar tradução — manter idêntico.
        if (textoLimpo.length() <= 1) {
            return true;
        }

        // Fala feita SÓ de números não tem tradução possível: contagem regressiva
        // ("98! 97!"), leitura de altímetro ("6500! 7000!"), coordenadas. O caso de UMA
        // palavra já era aceito por deveManterPalavraUnicaIdentica, mas a SEQUÊNCIA caía na
        // heurística de capitalização — e '9' não é maiúscula, então a fala era acusada de eco.
        // Consequência real (2026-07-23): a "Limpar Falhas do Cache" apagava "98! 97!" do
        // episódio 11 do 08th a cada passagem, recriando uma pendência que não existe.
        if (SOMENTE_NUMEROS.matcher(textoLimpo).matches()) {
            return true;
        }

        String[] palavras = textoLimpo.split("\\s+");
        if (palavras.length == 1) {
            return deveManterPalavraUnicaIdentica(textoLimpo, FALA_INTERROMPIDA.matcher(texto).find());
        }

        String minusculo = textoLimpo.toLowerCase(Locale.ROOT);
        if (termoDoLoreAtivo(minusculo)) {
            return true;
        }
        if (compostoSoDeTermosDaLore(minusculo)) {
            return true;
        }
        if (palavras.length >= 2 && palavras.length <= 4
            && java.util.Arrays.stream(palavras)
                .allMatch(p -> !p.isBlank() && Character.isUpperCase(p.charAt(0)))) {
            return java.util.Arrays.stream(palavras)
                .map(p -> p.toLowerCase(Locale.ROOT))
                .noneMatch(PALAVRAS_INGLES_COMUNS::contains);
        }
        return false;
    }

    /**
     * PROPÓSITO DE NEGÓCIO: normalização única do texto antes de qualquer julgamento de
     * identidade — remove tags ASS, hesitação escrita e pontuação, deixando só as palavras.
     *
     * <p>INVARIANTES DO DOMÍNIO: é a MESMA limpeza para a régua histórica e para a régua por
     * evidência; se as duas divergissem, uma poderia aceitar o que a outra recusa sobre um
     * texto que elas nem enxergam igual.
     *
     * <p>COMPORTAMENTO EM CASO DE FALHA: não lança; texto sem palavra devolve string vazia.
     */
    private String limpar(String texto) {
        String limpo = PADRAO_REMOVE_TAGS_ASS.matcher(texto).replaceAll("").strip();
        // A QUEBRA DO ASS É ESPAÇO, não pontuação. Sem esta linha a contrabarra saía e o N ficava:
        // "NEXT\NEPISODE" virava a "palavra" NEXTNEPISODE, em caixa alta, e era aceita idêntica
        // pelo portão do cache — enquanto o portão final, que troca \N por espaço, a reprovava.
        // Medido em 28/10/2026: 5 episódios do 0080 com o letreiro em inglês.
        limpo = QUEBRA_ASS.matcher(limpo).replaceAll(" ");
        limpo = removerGagueiraDeNome(limpo);
        limpo = limpo.replaceAll("[^\\w\\s\\d]", "").strip();
        // LETREIRO VERTICAL: uma letra por linha ("C\N\Na\N\Nf\N\Né" é o letreiro "Café"). Com a
        // quebra virando espaço, cada letra seria uma palavra e o letreiro deixaria de ser
        // reconhecido; as letras soltas voltam a formar a palavra que são.
        String[] pedacos = limpo.split("\\s+");
        if (pedacos.length > 1 && java.util.Arrays.stream(pedacos).allMatch(p -> p.codePointCount(0, p.length()) == 1)) {
            return String.join("", pedacos);
        }
        return limpo;
    }

    /**
     * PROPÓSITO DE NEGÓCIO: recusa a identidade quando há EVIDÊNCIA POSITIVA de que a fala é
     * inglês corrente que o espectador brasileiro tem direito de ver traduzido. É o conserto do
     * vazamento medido em 2026-07-22: {@code Next Episode} (63x), {@code Roger!} (29x) e
     * {@code Gotcha!} (12x) eram publicados no {@code _PT-BR.ass} final e gravados no cache como
     * tradução válida, congelando em inglês para sempre.
     *
     * <p>A polaridade é deliberada. A alternativa avaliada — só aceitar identidade quando a fala
     * fosse 100% terminologia declarada — foi medida contra os caches reais e reprovava 1 em cada
     * 3 identidades legítimas da própria obra, porque as legendas chamam os personagens pelo
     * primeiro nome ({@code Michel!}) enquanto a lore declara o nome completo
     * ({@code Michel Ninorich}). Exigir evidência para RECUSAR, em vez de exigir cadastro para
     * ACEITAR, protege os 995 nomes próprios sem depender de curadoria exaustiva de lore.
     *
     * <h2>Invariantes do domínio</h2>
     * <ul>
     *   <li>A terminologia da obra ativa VETA a evidência: uma fala que é termo canônico, ou
     *       composta só de termos canônicos, nunca é recusada — mesmo contendo palavra do léxico
     *       (ex.: um título declarado como {@code War in the Pocket}).</li>
     *   <li>O veto também vale por TOKEN: se a palavra suspeita é parte de um termo declarado da
     *       obra, ela não conta como evidência.</li>
     *   <li>Sem lore ativa, o veto simplesmente não age — o léxico decide sozinho.</li>
     * </ul>
     *
     * <h2>Comportamento em caso de falha</h2>
     * Devolve {@code false} (sem evidência ⇒ não recusa) para texto nulo, vazio ou sem palavra
     * alguma. Nunca lança: na dúvida, preserva o comportamento anterior.
     */
    private boolean temEvidenciaDeInglesTraduzivel(String texto) {
        if (texto == null) {
            return false;
        }
        String limpo = limpar(texto);
        if (limpo.isBlank()) {
            return false;
        }
        String normalizado = normalizarParaEvidencia(limpo);
        // Veto por terminologia DECLARADA e INTEIRA. Deliberadamente NÃO usa termoDoLoreAtivo:
        // aquele método faz busca livre na PROSA do prompt, e a prosa contém "Miller's Report",
        // "Magella Attack" e "08th MS Team" — bastaria isso para "Report!", "Attack!" e "Team!"
        // soltos ganharem imunidade, que é exatamente o vazamento que esta régua fecha.
        if (terminologiaDeclaradaCobre(normalizado)) {
            return false;
        }
        for (String palavra : normalizado.split("\\s+")) {
            if (PALAVRAS_INGLES_TRADUZIVEIS.contains(palavra)) {
                return true;
            }
        }
        return false;
    }

    /**
     * PROPÓSITO DE NEGÓCIO: normalização própria da régua por evidência, aplicada IGUALMENTE ao
     * texto da fala e aos termos declarados, para que os dois lados sejam comparáveis.
     *
     * <p>INVARIANTES DO DOMÍNIO: usa {@code \p{L}\p{N}} (não {@code \w}), então acento e cedilha
     * sobrevivem nos DOIS lados. A régua histórica usa {@code \w} ASCII e apaga acento só do
     * texto — assimetria que faria um termo acentuado declarado nunca casar. Aqui isso não
     * acontece porque a mesma função normaliza fala e termo.
     *
     * <p>COMPORTAMENTO EM CASO DE FALHA: texto sem letra nem dígito devolve string vazia.
     */
    private String normalizarParaEvidencia(String texto) {
        return texto.toLowerCase(Locale.ROOT)
            .replaceAll("[^\\p{L}\\p{N}\\s]", "")
            .replaceAll("\\s+", " ")
            .strip();
    }

    /**
     * PROPÓSITO DE NEGÓCIO: confirma que a fala é INTEIRAMENTE coberta por terminologia que a
     * obra declarou — o único caso em que uma expressão contendo palavra do léxico pode
     * legitimamente permanecer em inglês (ex.: {@code Miller's Report}, {@code Far East
     * Division}).
     *
     * <p>INVARIANTES DO DOMÍNIO: consome apenas termos INTEIROS, do mais longo para o mais
     * curto. Um componente solto de termo composto NUNCA ganha imunidade: declarar
     * {@code "Far East Division"} não autoriza {@code East!} nem {@code Division!}, e declarar
     * {@code "Miller's Report"} não autoriza {@code Report!}. Só devolve {@code true} quando
     * NADA sobra do texto após o consumo.
     *
     * <p>COMPORTAMENTO EM CASO DE FALHA: sem lore ativa ou sem termos declarados devolve
     * {@code false} — a ausência de cadastro nunca vira imunidade. Não lança.
     */
    private boolean terminologiaDeclaradaCobre(String normalizado) {
        Set<String> declarados = loreAtiva.termosProtegidosAtivos();
        if (declarados == null || declarados.isEmpty() || normalizado.isBlank()) {
            return false;
        }
        List<String> ordenados = declarados.stream()
            .filter(t -> t != null && !t.isBlank())
            .map(this::normalizarParaEvidencia)
            .filter(t -> !t.isBlank())
            .sorted(Comparator.comparingInt(String::length).reversed())
            .toList();

        String resto = normalizado;
        for (String termo : ordenados) {
            resto = Pattern.compile(
                    "(?iu)" + INICIO_DE_TERMO + FronteiraTermoAss.corpo(termo) + "(?![\\p{L}\\p{N}])")
                .matcher(resto).replaceAll(" ");
            if (resto.isBlank()) {
                return true;
            }
        }
        return resto.isBlank();
    }

    /**
     * PROPÓSITO DE NEGÓCIO: reconhece nomes e terminologia diretamente na lore
     * ativa (via {@link LoreAtivaPort}), eliminando listas hardcoded específicas de
     * DanMachi/Gundam e permitindo proteger nomes/facções do contexto selecionado.
     *
     * <p>INVARIANTES DO DOMÍNIO: comparação exige termo inteiro; termos
     * declarados explicitamente pelo provedor continuam tendo prioridade.
     *
     * <p>COMPORTAMENTO EM CASO DE FALHA: lore vazia ou termo vazio retorna falso.
     */
    private boolean termoDoLoreAtivo(String termoMinusculo) {
        for (String termo : loreAtiva.termosProtegidosAtivos()) {
            if (termo != null && termo.toLowerCase(Locale.ROOT).equals(termoMinusculo)) {
                return true;
            }
        }
        String lore = loreAtiva.obterLoreAtiva();
        if (lore == null || lore.isBlank() || termoMinusculo == null || termoMinusculo.isBlank()) {
            return false;
        }
        Pattern termoInteiro = Pattern.compile(
            "(?iu)" + INICIO_DE_TERMO + FronteiraTermoAss.corpo(termoMinusculo)
                + "(?![\\p{L}\\p{N}])");
        return termoInteiro.matcher(lore.toLowerCase(Locale.ROOT)).find();
    }

    /**
     * PROPÓSITO DE NEGÓCIO: reconhece a fala que é feita SÓ de terminologia canônica, ainda
     * que repetida — o caso do brado de guerra. {@code "Sieg Zeon! Sieg Zeon! Sieg Zeon!"}
     * é o slogan do Principado de Zeon e deve permanecer no original em toda a linha do
     * tempo UC, mas {@link #termoDoLoreAtivo} exige que o texto INTEIRO seja igual ao termo
     * e por isso não reconhecia a repetição: a fala era acusada de "modelo devolveu o
     * original sem traduzir" e virava pendência a cada execução.
     *
     * <p>INVARIANTES DO DOMÍNIO: só devolve {@code true} quando TODO o texto é consumido
     * por termos declarados da obra ativa e pelo menos um termo casou de fato — sobrando
     * qualquer palavra traduzível ("Zeon attacks" deixa "attacks"), a fala continua exigindo
     * tradução. Termos compostos são consumidos antes dos curtos, para que
     * "Principality of Zeon" não seja quebrado por "Zeon". A comparação ignora caixa e exige
     * fronteira de palavra.
     *
     * <p>COMPORTAMENTO EM CASO DE FALHA: sem contexto ativo ou sem termos declarados devolve
     * {@code false} (degrada para as heurísticas globais, nunca para aceitação cega); não lança.
     */
    private boolean compostoSoDeTermosDaLore(String textoMinusculo) {
        Set<String> declarados = loreAtiva.termosProtegidosAtivos();
        if (declarados == null || declarados.isEmpty() || textoMinusculo.isBlank()) {
            return false;
        }
        List<String> ordenados = declarados.stream()
            .filter(t -> t != null && !t.isBlank())
            .map(t -> t.toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}\\s]", " ")
                .replaceAll("\\s+", " ").strip())
            .filter(t -> !t.isBlank())
            .sorted(Comparator.comparingInt(String::length).reversed())
            .toList();

        String resto = textoMinusculo;
        boolean casouAlgum = false;
        for (String termo : ordenados) {
            String antes = resto;
            resto = Pattern.compile(
                    "(?iu)" + INICIO_DE_TERMO + FronteiraTermoAss.corpo(termo) + "(?![\\p{L}\\p{N}])")
                .matcher(resto).replaceAll(" ");
            if (!resto.equals(antes)) {
                casouAlgum = true;
            }
            if (resto.isBlank()) {
                break;
            }
        }
        return casouAlgum && resto.isBlank();
    }

    /**
     * PROPÓSITO DE NEGÓCIO: diferencia um nome próprio isolado de uma palavra
     * inglesa comum que ainda precisa de tradução.
     * <p>INVARIANTES DO DOMÍNIO: números, siglas, lore e nomes capitalizados são
     * preservados; vocabulário conversacional cadastrado nunca vira nome.
     * <p>COMPORTAMENTO EM CASO DE FALHA: evidência insuficiente mantém o termo
     * capitalizado para evitar retradução destrutiva de personagem.
     */
    private boolean deveManterPalavraUnicaIdentica(String textoLimpo, boolean falaInterrompida) {
        if (textoLimpo.matches("\\d+")) {
            return true;
        }
        String minusculo = textoLimpo.toLowerCase(Locale.ROOT);
        // Caixa alta é SIGLA (GHQ, GP02A, NTD) — mas não quando a palavra é inglês corrente:
        // "HELP!" e "GO!" eram aceitos idênticos só por estarem gritados, enquanto "Help!" ia ao
        // modelo (Macross II E01, medido em 28/10/2026). "OK" fica fora: é como o português
        // escreve, e mandá-lo ao modelo só gastaria três chamadas para voltar "OK".
        boolean inglesCorrente = PALAVRAS_INGLES_COMUNS.contains(minusculo) && !"ok".equals(minusculo);
        if (textoLimpo.length() > 1 && textoLimpo.equals(textoLimpo.toUpperCase(Locale.ROOT))
            && !inglesCorrente) {
            return true;
        }

        if (PADRAO_INTERJEICAO.matcher(minusculo).matches()) {
            return true;
        }

        if (PALAVRAS_INGLES_COMUNS.contains(minusculo)) {
            return false;
        }

        // O piso de 3 letras cai para 2 quando a fala foi INTERROMPIDA. Nome próprio cortado
        // no meio é o caso mais comum de reticência em legenda de anime, e duas letras não
        // dão base para exigir tradução.
        //
        // Medido em 12/08/2026, Unicorn E22 linha 157: "Ri..." — começo de "Riddhe" — foi
        // acusada de "fala não traduzida" pela Revisão de Legendas. Depois de `limpar` remover
        // a pontuação sobram DUAS letras, e o piso de 3 as reprovava. Foi 1 dos 3 achados da
        // varredura de 5.620 falas, e os 3 eram falso positivo.
        //
        // A guarda de PALAVRAS_INGLES_COMUNS continua ANTES desta linha, então "No...",
        // "Go..." e "So..." seguem sendo mandadas traduzir — o afrouxamento não as alcança.
        int pisoDeNomeProprio = falaInterrompida ? 2 : 3;
        return termoDoLoreAtivo(minusculo)
            || (textoLimpo.length() >= pisoDeNomeProprio && Character.isUpperCase(textoLimpo.charAt(0)));
    }

    /**
     * PROPÓSITO DE NEGÓCIO: normaliza hesitações escritas antes de reconhecer um
     * nome próprio, permitindo que `E-Eledore` seja comparado como `Eledore`.
     *
     * <p>INVARIANTES DO DOMÍNIO: o prefixo só é removido quando a letra após o
     * hífen repete a letra hesitada; palavras legítimas como `X-ray` permanecem.
     *
     * <p>COMPORTAMENTO EM CASO DE FALHA: texto nulo não é esperado pelo chamador;
     * texto sem o padrão é devolvido integralmente.
     */
    private String removerGagueiraDeNome(String texto) {
        return PADRAO_GAGUEIRA_NOME.matcher(texto).replaceAll("");
    }

    /**
     * PROPÓSITO DE NEGÓCIO: diz quando a "tradução" só repete o original em inglês — a fala nunca foi
     * traduzida de fato — e isso não é um caso legítimo de nome, número ou termo de lore.
     *
     * <h2>Invariantes do domínio</h2>
     * <ul>
     *   <li>Ignora tags ASS, quebras de linha, o envelope de aspas que o original não tem e, desde
     *       08/10/2026, a PONTUAÇÃO FINAL: "A pursuer?!" devolvido como "A pursuer?!!" é eco. Apareceu
     *       quando o parêntese inventado passou a ser recusado: "A pursuer?! (Perseguidor?!)" virou o
     *       inglês com um "!" a mais, e passava como traduzida.</li>
     *   <li>A absolvição continua sendo {@link #deveManterIdentico} sobre o original. Medido com a lore
     *       real nos 28 pares dos caches que só mudam a pontuação final: a comparação nova acusa 1
     *       ("A pursuer?!") e os outros 27 seguem absolvidos — nomes ("Judau?!" → "Judau?") e termos
     *       ("Titans?!" → "Titans?!!").</li>
     *   <li>Original que é SÓ pontuação ("..." → "...!") fica igual ao vazio do outro lado, e quem o
     *       absolve é o próprio {@link #deveManterIdentico} (um caractere visível ou menos).</li>
     * </ul>
     *
     * <p>COMPORTAMENTO EM CASO DE FALHA: argumento nulo, original vazio ou textos diferentes devolvem
     * {@code false}; nunca lança.
     */
    public boolean pareceNaoTraduzida(String original, String traduzido) {
        if (original == null || traduzido == null) {
            return false;
        }
        String o = normalizar(original);
        String t = semEnvelopeDeAspasQueOOriginalNaoTem(o, normalizar(traduzido));
        if (o.isEmpty() || !(o.equals(t) || mesmoTextoSoComOutraPontuacaoFinal(o, t))) {
            return false;
        }
        return !deveManterIdentico(original);
    }

    private static final Pattern PONTUACAO_FINAL = Pattern.compile("[\\s?!.…]+$");

    /** "A pursuer?!" e "A pursuer?!!": o mesmo texto, só a pontuação final trocada. */
    private static boolean mesmoTextoSoComOutraPontuacaoFinal(String original, String traduzido) {
        return PONTUACAO_FINAL.matcher(original).replaceAll("")
            .equals(PONTUACAO_FINAL.matcher(traduzido).replaceAll(""));
    }

    /**
     * PROPÓSITO DE NEGÓCIO: compara o eco como a fala SERÁ PUBLICADA. O normalizador de aspas da
     * Tradução Local tira, DEPOIS do portão, o par de aspas que envolve a fala quando o original
     * não o tinha; sem isto '"Damn it!"' passava aqui como "diferente" e ia para a tela como
     * "Damn it!" — medido no 86 ep 1 em 08/10/2026.
     *
     * <p>INVARIANTES DO DOMÍNIO: só retira UM par de borda (retas com exatamente duas aspas, ou
     * curvas de abertura e fechamento), e só quando o original NÃO estava envolto — fonte que já
     * citava mantém as aspas, que são legítimas. Mesmo critério de envelope do normalizador. Visível
     * no pacote porque a reprovação do exemplo do prompt copiado ({@code ValidadorTraducaoService})
     * compara do mesmo jeito: em 08/10/2026 o modelo devolveu '"Psyco Gundam?"' para "Z-G...?", o
     * portão comparou com as aspas, o normalizador as tirou, e a fala vazada foi publicada.
     *
     * <p>COMPORTAMENTO EM CASO DE FALHA: texto curto ou sem envelope volta intacto; nunca lança.
     */
    static String semEnvelopeDeAspasQueOOriginalNaoTem(String original, String traduzido) {
        if (envoltoPorAspas(original) || !envoltoPorAspas(traduzido)) {
            return traduzido;
        }
        return traduzido.substring(1, traduzido.length() - 1).strip();
    }

    private static boolean envoltoPorAspas(String s) {
        if (s == null || s.length() < 2) {
            return false;
        }
        char a = s.charAt(0);
        char b = s.charAt(s.length() - 1);
        if (a == '"' && b == '"') {
            return s.chars().filter(c -> c == '"').count() == 2;
        }
        return a == '“' && b == '”'
            && s.chars().filter(c -> c == '“').count() == 1 && s.chars().filter(c -> c == '”').count() == 1;
    }

    private String normalizar(String texto) {
        return PADRAO_REMOVE_TAGS_ASS.matcher(texto).replaceAll("")
            .replace("\\N", " ")
            .replace("\\n", " ")
            .replaceAll("\\s+", " ")
            .trim();
    }
}
