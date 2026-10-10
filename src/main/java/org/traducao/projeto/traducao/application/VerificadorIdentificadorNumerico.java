package org.traducao.projeto.traducao.application;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * PROPÓSITO DE NEGÓCIO: garante que a TRADUÇÃO não altere, invente ou apague um identificador
 * numérico da fonte. O valor semântico de um número é invariável: numa legenda ele é a
 * designação de uma unidade militar, uma altitude, uma frequência de rádio ou uma data, e
 * trocá-lo corrompe o sentido de forma indetectável pelo espectador. A corrida de 2026-07-22
 * produziu o caso que motivou esta classe: {@code "04th Team!"} foi publicado como
 * {@code "Equipe 08!"} — o número mudou em silêncio, sem qualquer rastro em log ou telemetria,
 * provavelmente por contaminação do contexto de lore da obra ({@code 08th MS Team}).
 *
 * <p>O LLM NÃO recebe autoridade para corrigir a fonte. Se a lore considerar que o fansub
 * errou, a correção tem de vir de regra determinística e versionada (o mapa de terminologia),
 * nunca de uma reescrita silenciosa do modelo.
 *
 * <h2>Invariantes do domínio</h2>
 * <ul>
 *   <li>Todo identificador numérico presente no ORIGINAL deve continuar presente na tradução.</li>
 *   <li>A comparação é por VALOR, não por grafia: são aceitas as reescritas legítimas do
 *       português — separador de milhar ({@code 9500} → {@code 9.500}), vírgula decimal
 *       ({@code 0.5} → {@code 0,5}), queda do ordinal inglês ({@code 12th} → {@code 12}) e
 *       ordinal português ({@code 04th} → {@code 04ª}).</li>
 *   <li>Tags ASS/SSA e códigos de quebra são removidos antes da comparação: os números de
 *       {@code \pos(720,650)} são formatação, não conteúdo.</li>
 * </ul>
 *
 * <h2>Comportamento em caso de falha</h2>
 * {@link #divergencia(String, String)} devolve uma mensagem legível nomeando os valores que
 * sumiram (a fala permanece pendente) ou {@code null} quando todos sobreviveram. Não lança.
 *
 * <p>LIMITE DECLARADO: a verificação cobre a SUBSTITUIÇÃO de valor, não a mudança de
 * representação. Número escrito por extenso na tradução ({@code "10 years"} → {@code "Dez
 * anos"}) e quantidade por extenso virando algarismo ({@code "eighteen"} → {@code "18"}) são
 * as duas faces do mesmo fenômeno e ambas passam: são escolha legítima de tradução, não
 * corrupção. A consequência aceita é que um número simplesmente OMITIDO numa tradução sem
 * nenhum algarismo não é detectado aqui — em troca, nenhuma letra de música ou contagem
 * regressiva é reprovada. A corrida de 2026-07-22 mostrou o custo de errar esse lado: a
 * primeira versão da regra, que reprovava todo sumiço, derrubou 22 falas boas.
 */
@Component
public class VerificadorIdentificadorNumerico {

    private final org.traducao.projeto.qualidadeTraducao.domain.LoreAtivaPort loreAtiva;

    /**
     * PROPÓSITO DE NEGÓCIO: recebe a terminologia da obra ativa para distinguir um ORDINAL, que o
     * português legitimamente reescreve, de um NOME que apenas parece ordinal.
     *
     * <h2>Por que a lore precisava chegar até aqui</h2>
     * A auditoria de 2026-09-09 mostrou {@code "04th Team!"} traduzido como {@code "Equipe 4!"} e
     * reprovado, o que devolve a fala inteira ao inglês na legenda. Mas a decisão anterior do
     * projeto, fixada em teste, é que perder o zero de {@code 08th} muda o NOME da unidade — e a
     * lore confirma, mapeando {@code "8º Time MS"} de volta para {@code "08th MS Team"}. As duas
     * coisas são verdade ao mesmo tempo, e só a lore desempata: {@code 08th MS Team} é termo
     * protegido, {@code 04th Team} não é.
     *
     * <p>INVARIANTES DO DOMÍNIO: sem lore ativa a regra FALHA FECHADA e mantém o comportamento
     * histórico de reprovar — "não sei se é nome" nunca vira "pode trocar". A porta é obrigatória
     * pelo mesmo motivo que em {@code ValidadorTraducaoService}: proteção opcional depende de o
     * chamador lembrar, e o defeito nasce justamente de quem não passa o que tem em mãos.
     *
     * <p>COMPORTAMENTO EM CASO DE FALHA: a porta nunca lança e degrada para conjunto vazio.
     */
    public VerificadorIdentificadorNumerico(
            org.traducao.projeto.qualidadeTraducao.domain.LoreAtivaPort loreAtiva) {
        this.loreAtiva = loreAtiva;
    }

    private static final Pattern PADRAO_TAG = Pattern.compile("\\{[^{}]*}");
    /** Ordinal inglês colado ao número: {@code 12th}, {@code 1st}, {@code 04th}. */
    private static final Pattern ORDINAL_INGLES = Pattern.compile("(?i)(\\d)(st|nd|rd|th)");
    /** Ordinal/grau português colado ao número: {@code 4ª}, {@code 1º}, {@code 30°}. */
    private static final Pattern ORDINAL_PORTUGUES = Pattern.compile("(\\d)[ºª°]");


    /** Numero marcado como ORDINAL: sufixo ingles ({@code 04th}) ou portugues ({@code 4a}). */
    private static final Pattern MARCA_DE_ORDINAL =
        Pattern.compile("(?<![0-9])(\\d+)(?:st|nd|rd|th|[ºª°])", Pattern.CASE_INSENSITIVE);

    /** Marca do relogio de 12 horas, que autoriza a conta +12 na conversao para 24h. */
    private static final Pattern MARCA_PM = Pattern.compile("(?i)\\bp\\.?\\s?m\\.?");

    /**
     * PROPÓSITO DE NEGÓCIO: aponta qual identificador numérico do original não sobreviveu à
     * tradução, para que a fala fique pendente com diagnóstico em vez de publicar um número
     * trocado.
     *
     * <p>INVARIANTES DO DOMÍNIO: compara CONJUNTOS de valores (não multiplicidade), de modo que
     * repetir ou desdobrar um número na tradução não reprova; só a ausência do valor reprova.
     *
     * <p>COMPORTAMENTO EM CASO DE FALHA: argumento nulo devolve {@code null} (nada a verificar);
     * nunca lança.
     *
     * @param original fala de origem, ainda com tags ASS
     * @param traduzido tradução candidata
     * @return mensagem com os valores perdidos, ou {@code null} se todos sobreviveram
     */
    public String divergencia(String original, String traduzido) {
        if (original == null || traduzido == null) {
            return null;
        }
        Set<String> naFonte = valores(original);
        if (naFonte.isEmpty()) {
            return null;
        }
        Set<String> naTraducao = valores(traduzido);
        List<String> perdidos = new ArrayList<>();
        for (String valor : naFonte) {
            if (!naTraducao.contains(valor)) {
                perdidos.add(valor);
            }
        }
        if (perdidos.isEmpty()) {
            return null;
        }
        // Sumiço NÃO basta: a tradução pode ter escrito o número POR EXTENSO, que é português
        // legítimo ("10 years after" → "Dez anos depois", "2... 1... 0!" → "Dois... um...
        // zero!"). O que caracteriza corrupção é a SUBSTITUIÇÃO — o valor da fonte sumir E um
        // valor estranho ocupar o lugar dele em algarismos. Sem esta condição a regra reprovava
        // 22 falas boas na corrida de 2026-07-22, quase todas verso de música e contagem
        // regressiva, e era o espelho do caso que já se tolerava de propósito (numeralizar
        // quantidade escrita por extenso).
        List<String> estranhos = new ArrayList<>();
        for (String valor : naTraducao) {
            if (!naFonte.contains(valor)) {
                estranhos.add(valor);
            }
        }
        if (estranhos.isEmpty()) {
            return null;
        }

        // HORÁRIO LOCALIZADO NÃO É NÚMERO TROCADO.
        //
        // Medido na retradução de 2026-08-21: das 12 recusas desta guarda, DEZ eram tradução
        // correta. Oito são o horário militar do inglês virando o formato brasileiro
        // ("1500 hours" -> "15h00"), e duas são a conversão de 12h para 24h ("3:40 pm" ->
        // "15:40"). Nos dois casos a guarda via [1500] sumir e [15, 00] surgir e reprovava —
        // e o efeito é o pior possível: a fala inteira volta para o INGLÊS na legenda.
        //
        // O que torna seguro descontar: a equivalência é ARITMÉTICA e conferida, não suposta.
        // 1500 só é explicado por 15 e 00 porque 15*100+00 == 1500, e a palavra "hours" tem
        // de estar no original. 3 só é explicado por 15 porque 3+12 == 15 e havia "pm". O
        // caso que originou esta classe — "04th Team!" publicado como "Equipe 08!" — não casa
        // nenhuma das duas contas e continua reprovando.
        List<String> perdidosReais = new ArrayList<>();
        for (String valor : perdidos) {
            if (!explicadoPorHorario(valor, original, naTraducao)
                && !explicadoPorZeroDeOrdinal(valor, original, traduzido)
                && !explicadoPorMultiplicadorPorExtenso(valor, traduzido)) {
                perdidosReais.add(valor);
            }
        }
        if (perdidosReais.isEmpty()) {
            return null;
        }

        return "identificador numérico alterado pela tradução: original tem " + naFonte
            + ", tradução tem " + naTraducao + " (sumiu: " + perdidosReais + ", surgiu: " + estranhos + ")";
    }

    /**
     * PROPÓSITO DE NEGÓCIO: confirma que UM identificador numérico específico do original
     * aparece na tradução — usado pela guarda do tradutor de máquina, que avalia token a token.
     *
     * <p>INVARIANTES DO DOMÍNIO: mesma normalização de {@link #divergencia}, então as duas
     * verificações nunca discordam sobre o que conta como o mesmo número.
     *
     * <p>COMPORTAMENTO EM CASO DE FALHA: token sem dígito ou argumento nulo devolve
     * {@code false} (recusa segura). Não lança.
     */
    public boolean sobrevive(String token, String traduzido) {
        if (token == null || traduzido == null) {
            return false;
        }
        Set<String> doToken = valores(token);
        return !doToken.isEmpty() && valores(traduzido).containsAll(doToken);
    }

    /**
     * PROPÓSITO DE NEGÓCIO: extrai os VALORES numéricos de um texto, já livres de formatação e
     * de grafia local, para que a comparação enxergue só o número.
     *
     * <p>INVARIANTES DO DOMÍNIO: remove tags e quebras; derruba sufixo ordinal inglês e
     * português; descarta o separador que fica ENTRE dígitos (milhar/decimal), preservando-o
     * como fronteira em qualquer outra posição — assim {@code 500} nunca é confundido com o
     * {@code 500} interno de {@code 12500}.
     *
     * <p>COMPORTAMENTO EM CASO DE FALHA: texto sem dígito devolve conjunto vazio; não lança.
     */
    private Set<String> valores(String texto) {
        String limpo = PADRAO_TAG.matcher(texto).replaceAll(" ")
            .replace("\\N", " ").replace("\\n", " ").replace("\\h", " ");
        limpo = ORDINAL_INGLES.matcher(limpo).replaceAll("$1");
        limpo = ORDINAL_PORTUGUES.matcher(limpo).replaceAll("$1");

        Set<String> encontrados = new LinkedHashSet<>();
        StringBuilder atual = new StringBuilder();
        for (int i = 0; i < limpo.length(); i++) {
            char c = limpo.charAt(i);
            if (Character.isDigit(c)) {
                atual.append(c);
                continue;
            }
            boolean separadorEntreDigitos = ehSeparador(c)
                && atual.length() > 0
                && i + 1 < limpo.length() && Character.isDigit(limpo.charAt(i + 1));
            if (separadorEntreDigitos) {
                // MILHAR COLAPSA, DECIMAL NÃO. Antes desta distinção, "1.5" e "15" produziam a
                // MESMA sequência de dígitos e a troca de um pelo outro passava ilesa (medido na
                // auditoria de 2026-09-09). O separador vira ponto canônico, de modo que "1.5" e
                // "1,5" continuam sendo o mesmo valor — que é a grafia local, não outro número.
                if (ehDecimal(limpo, i, c)) {
                    atual.append('.');
                }
                continue; // 9.500 e 9500 são o mesmo valor
            }
            if (atual.length() > 0) {
                encontrados.add(atual.toString());
                atual.setLength(0);
            }
        }
        if (atual.length() > 0) {
            encontrados.add(atual.toString());
        }
        return encontrados;
    }

    /**
     * PROPÓSITO DE NEGÓCIO: decide se um número que sumiu da tradução foi apenas ESCRITO em
     * outro formato de hora, e não substituído.
     *
     * <h2>Invariantes do domínio</h2>
     * <ul>
     *   <li>A equivalência é aritmética e conferida contra os valores que a tradução realmente
     *       contém — nunca "parece hora, deixa passar".</li>
     *   <li>Militar exige a palavra {@code hours} no original; 12h exige {@code am}/{@code pm}.
     *       Sem a marca textual, {@code 1500} continua sendo um identificador comum.</li>
     * </ul>
     *
     * <p>COMPORTAMENTO EM CASO DE FALHA: qualquer dúvida devolve {@code false}, e o valor volta
     * a contar como perdido — a guarda erra para o lado de reprovar, não de liberar.
     */
    private static boolean explicadoPorHorario(String perdido, String original, Set<String> naTraducao) {
        // 1) militar: "1500 hours" -> a tradução tem 15 e 00
        if (perdido.length() == 4 && perdido.chars().allMatch(Character::isDigit)
            && Pattern.compile("(?<![0-9])" + Pattern.quote(perdido) + "\\s*(?i:hours?)").matcher(original).find()) {
            int hh = Integer.parseInt(perdido.substring(0, 2));
            int mm = Integer.parseInt(perdido.substring(2));
            if (hh <= 23 && mm <= 59
                && naTraducao.contains(perdido.substring(0, 2))
                && naTraducao.contains(perdido.substring(2))) {
                return true;
            }
        }
        // 2) 12h -> 24h: "3:40 pm" -> a tradução tem 15
        if (MARCA_PM.matcher(original).find()) {
            try {
                int h = Integer.parseInt(perdido);
                if (h >= 1 && h <= 12 && naTraducao.contains(String.valueOf(h + 12))) {
                    return true;
                }
            } catch (NumberFormatException fora) {
                return false;
            }
        }
        return false;
    }

    /**
     * PROPÓSITO DE NEGÓCIO: decide se um número que sumiu da tradução foi apenas reescrito sem o
     * zero à esquerda de um ORDINAL — {@code "04th Team"} virando {@code "4ª Equipe"} — em vez de
     * ter sido substituído por outro valor.
     *
     * <h2>Invariantes do domínio</h2>
     * <ul>
     *   <li>Só vale quando o ORIGINAL marca o número como ordinal, com sufixo inglês
     *       ({@code st}/{@code nd}/{@code rd}/{@code th}) ou português ({@code º}/{@code ª}/
     *       {@code °}) colado nele. Sem a marca, {@code "Unidade 04"} continua sendo código
     *       técnico e o zero segue significativo.</li>
     *   <li>A equivalência é conferida contra os valores que a tradução realmente contém, nos
     *       dois sentidos: {@code 04}→{@code 4} e {@code 4}→{@code 04}.</li>
     *   <li>Zero à esquerda é a ÚNICA diferença tolerada. {@code "04th"} publicado como
     *       {@code "08ª"} não casa e continua reprovando — foi o defeito que criou esta guarda.</li>
     * </ul>
     *
     * <p>COMPORTAMENTO EM CASO DE FALHA: qualquer dúvida devolve {@code false} e o valor volta a
     * contar como perdido; a guarda erra para o lado de reprovar. Não lança.
     *
     * <p>MEDIDO EM 2026-09-09 (auditoria de terceiro): {@code "04th Team!"} traduzido como
     * {@code "Equipe 4!"} era reprovado, e a reprovação devolve a fala inteira ao inglês na
     * legenda. São 27 falas do acervo expostas a isto, todas do 08th MS Team.
     */
    private boolean explicadoPorZeroDeOrdinal(String perdido, String original, String traduzido) {
        if (perdido.isEmpty() || !perdido.chars().allMatch(Character::isDigit)) {
            return false;
        }
        String valor = semZerosAEsquerda(perdido);

        // CAMINHO 1, sem depender da lore: a forma ORDINAL sobreviveu nos dois lados.
        // "04th Team" -> "4ª Equipe". Vale sempre, porque re-grafar ordinal como ordinal nao
        // pode mudar o sentido de nada.
        if (ordinaisDe(original).contains(valor) && ordinaisDe(traduzido).contains(valor)) {
            return true;
        }

        // CAMINHO 2, que EXIGE a lore: o ordinal virou algarismo solto ("04th Team" -> "Equipe 4").
        // Aqui o zero pode ser parte de um NOME, e so a terminologia da obra sabe dizer. Falha
        // fechada: sem lore ativa, nao absolve -- foi assim que "08th Mobile Suit Team" continuou
        // protegido antes desta regra existir, e continua depois.
        if (!ordinaisDe(original).contains(valor) || !naTraducaoComOuSemZero(traduzido, valor)) {
            return false;
        }
        Set<String> protegidos = loreAtiva.termosProtegidosAtivos();
        if (protegidos == null || protegidos.isEmpty()) {
            return false;
        }
        // O ordinal aparece dentro de algum termo canonico da obra? Entao ele e NOME, e trocar a
        // grafia muda a designacao. "08th MS Team" esta protegido; "04th Team" nao esta.
        String comoNoOriginal = grafiaDoOrdinalNoTexto(original, valor);
        for (String termo : protegidos) {
            if (termo == null || termo.isBlank()) {
                continue;
            }
            if (comoNoOriginal != null && termo.toLowerCase(java.util.Locale.ROOT)
                    .contains(comoNoOriginal.toLowerCase(java.util.Locale.ROOT))) {
                return false;
            }
        }
        return true;
    }

    /** Algarismo seguido do multiplicador por extenso: {@code 2 mil}, {@code 1,5 milhão}, {@code 3 bilhões}. */
    private static final Pattern MULTIPLICADOR_POR_EXTENSO = Pattern.compile(
        "(?<![\\d.,])(\\d+(?:[.,]\\d+)?)\\s*(mil|milh[ãa]o|milh[õo]es|bilh[ãa]o|bilh[õo]es)(?!\\p{L})",
        Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

    /**
     * PROPÓSITO DE NEGÓCIO: decide se um número que sumiu da tradução foi apenas escrito com o
     * multiplicador POR EXTENSO — {@code "2,000 soldiers"} virando {@code "2 mil soldados"} —, que
     * é o português corrente, e não substituído por outro valor.
     *
     * <h2>O prejuízo medido — 10/10/2026</h2>
     * Break Blade, filme 2: {@code "We've already lost 2,000 soldiers."} ficou pendente com
     * "original tem [2000], tradução tem [2]" e a fala foi publicada em INGLÊS. A guarda lia o
     * {@code 2} de {@code "2 mil"} como valor estranho.
     *
     * <h2>Invariantes do domínio</h2>
     * <ul>
     *   <li>A equivalência é ARITMÉTICA e conferida: {@code 2 × 1000 == 2000}. {@code "3 mil"}
     *       para {@code 2,000} não casa e continua reprovando.</li>
     *   <li>Só vale com o multiplicador escrito colado ao algarismo ({@code mil}, {@code milhão},
     *       {@code bilhão} e plurais). {@code "2 soldados"} para {@code 2,000} continua reprovando.</li>
     * </ul>
     *
     * <p>COMPORTAMENTO EM CASO DE FALHA: valor não numérico ou qualquer dúvida devolve
     * {@code false}, e o valor volta a contar como perdido. Não lança.
     */
    private static boolean explicadoPorMultiplicadorPorExtenso(String perdido, String traduzido) {
        java.math.BigDecimal alvo;
        try {
            alvo = new java.math.BigDecimal(perdido);
        } catch (NumberFormatException naoNumerico) {
            return false;
        }
        java.util.regex.Matcher achado = MULTIPLICADOR_POR_EXTENSO.matcher(traduzido);
        while (achado.find()) {
            String palavra = achado.group(2).toLowerCase(java.util.Locale.ROOT);
            java.math.BigDecimal fator = palavra.startsWith("bilh") ? java.math.BigDecimal.TEN.pow(9)
                : palavra.startsWith("milh") ? java.math.BigDecimal.TEN.pow(6)
                : java.math.BigDecimal.TEN.pow(3);
            java.math.BigDecimal base = new java.math.BigDecimal(achado.group(1).replace(',', '.'));
            if (base.multiply(fator).compareTo(alvo) == 0) {
                return true;
            }
        }
        return false;
    }

    /** A tradução traz o valor em algarismos, com ou sem zero à esquerda? */
    private boolean naTraducaoComOuSemZero(String traduzido, String valor) {
        for (String daTraducao : valores(traduzido)) {
            if (daTraducao.chars().allMatch(Character::isDigit)
                && semZerosAEsquerda(daTraducao).equals(valor)) {
                return true;
            }
        }
        return false;
    }

    /** Como o ordinal está escrito no texto, com sufixo: {@code 08th}, {@code 4ª}. */
    private static String grafiaDoOrdinalNoTexto(String texto, String valorSemZeros) {
        var achado = MARCA_DE_ORDINAL.matcher(texto);
        while (achado.find()) {
            if (semZerosAEsquerda(achado.group(1)).equals(valorSemZeros)) {
                return achado.group();
            }
        }
        return null;
    }

    /**
     * PROPÓSITO DE NEGÓCIO: colhe os números que o texto marca como ORDINAL — {@code 04th},
     * {@code 4ª}, {@code 8º} — já normalizados sem o zero à esquerda, para que as duas grafias
     * do mesmo posto sejam comparáveis.
     *
     * <p>INVARIANTES DO DOMÍNIO: só entra número com sufixo ordinal colado. Algarismo solto
     * ({@code "Equipe 4"}, {@code "8 Time"}) NÃO é ordinal e fica de fora — é o que mantém
     * {@code 08th Mobile Suit Team} distinto de {@code 8 Time de Mobile Suit}.
     *
     * <p>COMPORTAMENTO EM CASO DE FALHA: texto nulo devolve conjunto vazio; não lança.
     */
    private static Set<String> ordinaisDe(String texto) {
        Set<String> encontrados = new LinkedHashSet<>();
        if (texto == null) {
            return encontrados;
        }
        java.util.regex.Matcher achado = MARCA_DE_ORDINAL.matcher(texto);
        while (achado.find()) {
            encontrados.add(semZerosAEsquerda(achado.group(1)));
        }
        return encontrados;
    }

    private static String semZerosAEsquerda(String digitos) {
        return digitos.replaceFirst("^0+(?=\\d)", "");
    }

    private static boolean ehSeparador(char c) {
        // isSpaceChar cobre o espaço NÃO SEPARÁVEL (U+00A0), que isWhitespace ignora e que
        // aparece como separador de milhar em várias saídas de tradução.
        return c == '.' || c == ',' || Character.isWhitespace(c) || Character.isSpaceChar(c);
    }

    /**
     * PROPÓSITO DE NEGÓCIO: separa o ponto/vírgula que marca a parte FRACIONÁRIA daquele que só
     * agrupa milhar, para que {@code 1.5} não seja lido como o inteiro {@code 15}.
     *
     * <h2>Invariantes do domínio</h2>
     * <ul>
     *   <li>Só ponto e vírgula podem ser decimais. Espaço — inclusive o não separável — agrupa
     *       milhar e nada mais, então {@code "15 00"} continua colapsando em {@code 1500}.</li>
     *   <li>Grupo de EXATAMENTE três dígitos seguido de não-dígito é milhar ({@code 9.500});
     *       qualquer outro tamanho é fração ({@code 1.5}, {@code 3.14159}).</li>
     * </ul>
     *
     * <p>COMPORTAMENTO EM CASO DE FALHA: não lança; índice fora de faixa devolve {@code false},
     * que preserva o comportamento histórico de colapsar.
     */
    private static boolean ehDecimal(String texto, int posSeparador, char separador) {
        if (separador != '.' && separador != ',') {
            return false;
        }
        int fim = posSeparador + 1;
        while (fim < texto.length() && Character.isDigit(texto.charAt(fim))) {
            fim++;
        }
        int digitos = fim - (posSeparador + 1);
        boolean grupoDeMilhar = digitos == 3;
        return !grupoDeMilhar;
    }
}
