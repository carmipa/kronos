package org.traducao.projeto.traducaoKaraoke.application;

import jakarta.enterprise.context.ApplicationScoped;
import org.traducao.projeto.legenda.application.DetectorEfeitoKaraokeService;
import org.traducao.projeto.legenda.domain.PadraoEstiloMusical;
import org.traducao.projeto.traducaoKaraoke.domain.ClasseLinhaKaraoke;
import org.traducao.projeto.traducaoKaraoke.domain.SinaisDeKaraoke;

import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Decide o destino de cada evento de música: preservar (letra original),
 * traduzir (camada em inglês) ou não tocar (efeito KFX / já em PT-BR).
 * <p>
 * O problema central que este classificador resolve: cantores japoneses
 * misturam inglês no meio da letra ("kimi no heart ni fly away"). A heurística
 * estrita de romaji do {@link DetectorEfeitoKaraokeService} exige que TODAS as
 * palavras sejam silabáveis em japonês — uma única palavra inglesa derruba a
 * detecção e a letra original iria ao LLM. Aqui a decisão é por EVIDÊNCIA:
 * partículas/palavras japonesas romanizadas inequívocas votam em "original",
 * palavras gramaticais inequívocas de inglês votam em "tradução", e o estilo
 * do evento (Romaji/JP vs English) decide antes de qualquer análise de texto.
 * Em caso de dúvida o viés é PRESERVAR — o mesmo princípio de todo o projeto:
 * deixar uma linha sem traduzir custa menos que destruir a letra original.
 */
@ApplicationScoped
public class ClassificadorLetraKaraokeService {

    private static final Pattern PADRAO_REMOVE_TAGS_ASS = Pattern.compile("\\{[^}]*\\}");
    private static final Pattern ESCRITA_JAPONESA_PATTERN =
        Pattern.compile("[\\p{IsHiragana}\\p{IsKatakana}\\p{IsHan}]");
    // "rom" cobre abreviações reais de fansub como o estilo "ED-ROM" (86).
    private static final Pattern ESTILO_JAPONES_ROMAJI_PATTERN = Pattern.compile(
        "(?i)\\b(rom|romaji|jp|jpn|japanese|japones|japon[eê]s|kana|kanji)\\b");
    // Mesma decomposição silábica Hepburn do DetectorEfeitoKaraokeService.
    private static final Pattern PALAVRA_ROMAJI_PATTERN = Pattern.compile(
        "^(?:n|(?:([kgsztdnhbpmyrwfjv])\\1?|sh|ch|ts|ky|gy|ny|hy|my|ry|by|py)?[aeiou])+$");
    private static final Pattern ACENTO_PORTUGUES_PATTERN = Pattern.compile("[ãõçáéíóúâêôà]");
    /**
     * O nome do estilo declara PAPEL DE CAMADA de karaokê, e não o nome de uma música. Fronteira
     * por LETRA (e não {@code \b}) pelo mesmo motivo já registrado no peer {@code legenda}:
     * sublinhado e dígito são caractere de palavra, e {@code ED_S2_roma} não casaria.
     *
     * <p>Medido no acervo em 2026-08-19: casa 4 estilos e 2.179 eventos — {@code Hey World
     * English}, {@code RISE LIGHT RISE English}, {@code Eng}, {@code English}. <b>Zero estilo de
     * diálogo</b>.
     */
    private static final Pattern ESTILO_PAPEL_DE_CAMADA_PATTERN = Pattern.compile(
        "(?i)(?<!\\p{L})(english|eng|romaji|roma|rom|kanji|lyrics?)(?!\\p{L})");

    /**
     * Palavras gramaticais de inglês que não colidem com romaji nem com PT-BR.
     * Uma camada de TRADUÇÃO em inglês sempre carrega várias delas; a letra
     * japonesa com inglês misturado usa palavras de conteúdo (heart, fly,
     * dream), quase nunca a gramática ("the", "of", "with"...).
     */
    private static final Set<String> INGLES_FORTE = Set.of(
        "the", "you", "your", "yours", "my", "mine", "we", "our", "ours", "it", "its",
        "is", "are", "was", "were", "be", "been", "being", "this", "that", "these", "those",
        "of", "and", "but", "or", "if", "in", "on", "at", "for", "with", "from", "by",
        "will", "would", "can", "could", "should", "shall", "must", "not", "don", "won",
        "what", "when", "where", "who", "whom", "how", "why", "all", "every", "never",
        "always", "there", "here", "have", "has", "had", "get", "got", "let", "just",
        "still", "only", "even", "into", "through", "without");

    /**
     * Partículas e palavras japonesas romanizadas inequívocas (não são palavras
     * de inglês nem de português). Duas ocorrências bastam para cravar letra
     * original mesmo com inglês misturado no meio.
     */
    private static final Set<String> ROMAJI_FORTE = Set.of(
        "wa", "ga", "wo", "ni", "mo", "ne", "yo", "ka", "kara", "made", "dake", "demo",
        "kedo", "koto", "mono", "desu", "masu", "nai", "shite", "suru", "kimi", "boku",
        "ore", "watashi", "anata", "minna", "itsumo", "zutto", "motto", "kitto",
        "kokoro", "yume", "sora", "hikari", "kaze", "hoshi", "namida", "sekai",
        "mirai", "ashita", "kyou", "ima", "koi", "inochi", "tsuki", "hana",
        "arigatou", "sayonara", "daijoubu", "issho", "futari", "hitori");

    /**
     * Palavras PT-BR frequentes em letras já traduzidas, para reconhecer pasta
     * reprocessada sem depender só de acentos (que já decidem sozinhos).
     */
    private static final Set<String> PORTUGUES_FORTE = Set.of(
        "voce", "nao", "meu", "minha", "seu", "sua", "por", "para", "mais", "sempre",
        "nunca", "quando", "quero", "posso", "vamos", "coracao", "mundo", "ceu",
        "amor", "sonho", "vida", "dentro", "longe", "ate", "sou", "estou");

    /** Fração mínima de palavras silabáveis em japonês no desempate final. */
    private static final double FRACAO_ROMAJI_DESEMPATE = 0.8;
    private static final int MINIMO_PALAVRAS_DESEMPATE = 3;

    private final DetectorEfeitoKaraokeService detector;

    public ClassificadorLetraKaraokeService(DetectorEfeitoKaraokeService detector) {
        this.detector = detector;
    }

    /**
     * PROPÓSITO DE NEGÓCIO: separa a LETRA da SÍLABA dentro de um estilo musical com efeito.
     *
     * <p>INVARIANTES DO DOMÍNIO: duas ou mais palavras visíveis é frase; uma só é fragmento de
     * KFX. O critério olha o texto DEPOIS de removidas as tags — {@code {\t(...)}Do} tem uma
     * palavra, ainda que a linha seja longa em caracteres.
     *
     * <p>Medido no Unicorn E01, estilo OPL2: 17 frases e 138 fragmentos em 155 linhas. As 17 são
     * exatamente as que sobram quando a música é achatada.
     *
     * <p>COMPORTAMENTO EM CASO DE FALHA: texto nulo ou sem palavra visível devolve {@code false}
     * — na dúvida, fragmento, que é o lado seguro (preserva).
     */
    /**
     * PROPÓSITO DE NEGÓCIO: o NOME do estilo declara PAPEL DE CAMADA de karaokê — {@code English},
     * {@code Romaji}, {@code Kanji}, {@code Lyrics}. Ninguém batiza uma faixa de diálogo assim;
     * quem batiza é o fansub, para separar as camadas simultâneas da mesma música.
     *
     * <h2>O prejuízo que originou, e o tamanho dele</h2>
     * A régua de evidência positiva (2026-08-19) removeu 133.410 falsos positivos, mas levava
     * junto a letra de obras cujo estilo é o NOME DA MÚSICA, sem nenhuma palavra musical:
     * {@code Hey World English} 1.150 e {@code RISE LIGHT RISE English} 927 — as duas são letra
     * de verdade ("My body's totally exhausted, and yet"). São <b>2.179 eventos</b> no acervo,
     * em quatro estilos, e <b>nenhum estilo de diálogo casa este padrão</b> — medido.
     *
     * <p>Note o que este método NÃO faz: ele decide se a linha é KARAOKÊ, não qual idioma ela é.
     * A escolha entre preservar e traduzir continua sendo da segunda pergunta, mais abaixo.
     *
     * <p>COMPORTAMENTO EM CASO DE FALHA: estilo nulo devolve {@code false}; nunca lança.
     */
    private boolean declaraPapelDeCamada(String estilo) {
        return estilo != null && ESTILO_PAPEL_DE_CAMADA_PATTERN.matcher(estilo).find();
    }

    /**
     * PROPÓSITO DE NEGÓCIO: recusa como letra o que é COMANDO DE DESENHO vetorial do ASS —
     * {@code m 0 476 l 2000 476 2000 576 0 576}. É coordenada, não frase, e mandá-la ao LLM
     * produz alucinação garantida.
     *
     * <h2>Por que entra JUNTO com o papel de camada, e não depois</h2>
     * Sem ele, a evidência de papel de camada acima traria de volta os 10 eventos do estilo
     * {@code English} cujo "texto" é exatamente um traçado vetorial. Ou seja: seria eu criando o
     * problema no mesmo commit em que conserto outro. É a mesma classe do prejuízo já registrado
     * no projeto — 2.062 "cartazes" que eram comandos de desenho.
     *
     * <p>INVARIANTES DO DOMÍNIO: exige dígito E que toda letra do texto visível seja uma das
     * letras de comando do ASS ({@code m n l b s p c}). "Take off my dress" tem letras fora
     * desse conjunto e passa intacta; {@code m 0 476 l 2000} não tem nenhuma.
     *
     * <p>COMPORTAMENTO EM CASO DE FALHA: texto sem dígito devolve {@code false} — na dúvida,
     * trata como letra, que é o lado que preserva a tradução.
     */
    private static boolean ehComandoDeDesenho(String visivel) {
        boolean temDigito = false;
        boolean temLetra = false;
        for (int i = 0; i < visivel.length(); i++) {
            char c = visivel.charAt(i);
            if (Character.isDigit(c)) {
                temDigito = true;
            } else if (Character.isLetter(c)) {
                temLetra = true;
                if ("mnlbspc".indexOf(Character.toLowerCase(c)) < 0) {
                    return false;
                }
            }
        }
        return temDigito && temLetra;
    }

    private boolean ehFraseCompleta(String texto) {
        String visivel = extrairTextoVisivel(texto);
        if (visivel == null || visivel.isBlank()) {
            return false;
        }
        return visivel.trim().split("\\s+").length >= 2;
    }

    /**
     * PROPÓSITO DE NEGÓCIO: forma sem contexto de arquivo — consulta avulsa e teste de unidade.
     *
     * <p>INVARIANTES DO DOMÍNIO: sem os sinais externos a decisão fica só com o estilo e a tag
     * {@code \k}, que é o lado RESTRITIVO. "Não perguntei" nunca vira "é música".
     */
    public ClasseLinhaKaraoke classificar(String estilo, String texto) {
        return classificar(estilo, texto, SinaisDeKaraoke.nenhum());
    }

    /**
     * PROPÓSITO DE NEGÓCIO: decide o destino da linha em DUAS perguntas, nesta ordem — primeiro
     * "isto é karaokê?", depois "sendo karaokê, é japonês/romaji (preserva) ou inglês (traduz)?".
     * Até 2026-08-19 as duas estavam fundidas, e a primeira aceitava densidade de tag como prova.
     *
     * <h2>A régua da primeira pergunta: EVIDÊNCIA POSITIVA, quatro fontes</h2>
     * <ol>
     *   <li>tag de timing {@code \k} no texto;</li>
     *   <li>o NOME do estilo declara música;</li>
     *   <li>o campo {@code Effect} do ASS declara karaokê (contribuição de Paulo, 19/08);</li>
     *   <li>existe camada romaji no MESMO instante do arquivo.</li>
     * </ol>
     *
     * <h2>O que SAIU da régua, e por quê</h2>
     * {@code detector.eEfeitoKaraoke(texto)} — "posicionamento complexo + alta densidade de
     * tags" — <b>deixa de ser prova de que a linha é música</b>. Aquilo é assinatura de
     * TIPOGRAFIA, e o fansub usa as mesmas tags para mascarar diálogo. Medido no acervo inteiro:
     * das 165.827 linhas que iriam ao LLM, <b>133.951 (80,8%) entravam só por ali</b> —
     * {@code Char's Counterattack} 106.692 (o estilo de DIÁLOGO do filme, com
     * {@code \clip(601,835,1685,924)}), {@code Signs} 9.213, {@code Zeta Episode Title} 6.923.
     *
     * <p><b>Ele continua sendo usado logo abaixo</b>, e ali com razão: depois de já se saber que
     * a linha é música, a assinatura de efeito distingue SÍLABA de FRASE. Sair da primeira
     * pergunta não é sair da classe.
     *
     * <h2>Custo declarado</h2>
     * A régua remove 133.410 eventos e mantém 32.417. A perda residual é ~2.400, em estilos que
     * são NOME DE MÚSICA com {@code Effect} vazio e sem camada romaji ({@code NipSlip},
     * {@code Paradise}, {@code HestiaFamilia}, {@code EG}, {@code Hey World English}) — lacuna
     * conhecida, declarada, e alvo da fase seguinte.
     *
     * <p>COMPORTAMENTO EM CASO DE FALHA: texto nulo/vazio devolve {@code FORA_DE_MUSICA}; sinais
     * nulos são tratados como {@link SinaisDeKaraoke#nenhum()}. Nunca lança.
     */
    public ClasseLinhaKaraoke classificar(String estilo, String texto, SinaisDeKaraoke sinais) {
        if (texto == null || texto.isBlank()) {
            return ClasseLinhaKaraoke.FORA_DE_MUSICA;
        }
        SinaisDeKaraoke externos = sinais == null ? SinaisDeKaraoke.nenhum() : sinais;
        boolean indicaMusica = detector.eEstiloDeMusica(estilo)
            || detector.temTagKaraoke(texto)
            || declaraPapelDeCamada(estilo)
            || externos.algumaEvidencia();
        if (!indicaMusica) {
            return ClasseLinhaKaraoke.FORA_DE_MUSICA;
        }
        // SÍLABA de uma frase que TAMBÉM está no arquivo. Decidido pelo plano, que enxerga o
        // documento inteiro — uma linha sozinha não consegue responder isto.
        //
        // Vem ANTES do veto por tag porque é o caso que o veto por tag NÃO alcança: medido no
        // OPL2 do Unicorn, das 3.255 linhas do estilo, ZERO têm tag de karaokê — só \pos, \fad e
        // \blur. O veto abaixo foi calibrado num caso que tem a tag e é cego no que não tem, e o
        // preço foram 78 dos 131 textos distintos indo ao LLM como fragmento: "cant" virou
        // "Cantar." (é o "can't" sem apóstrofo) e "on" virou "começando".
        //
        // Não conflita com a lição das 17 linhas do OPL2 logo abaixo: a LETRA inteira tem duas
        // ou mais palavras e por construção nunca é marcada como sílaba — ver
        // PlanoDeClassificacao.posicoesDeSilaba.
        if (externos.silabaDeFraseIrma()) {
            return ClasseLinhaKaraoke.EFEITO_KFX;
        }
        // Sílaba/letra de KFX (cru ou pós-template): traduzir fragmento é
        // destruição garantida — o módulo Karaokê Simples é quem lida com isso.
        //
        // MAS a marca de efeito não basta sozinha, e isso custou 22 episódios: no Unicorn TODAS
        // as 155 linhas do OPL2 carregam tag de efeito, inclusive as 17 que são a LETRA inteira
        // ("And Im calling calling out your name again"). Com a guarda decidindo só pela tag, o
        // estilo saía 0 de 69 traduzidas em TODOS os episódios — a abertura ficava em inglês na
        // tela, e ainda por cima empilhada com os fragmentos dela mesma.
        //
        // O discriminador é o TEXTO VISÍVEL, não a tag: fragmento de KFX é UMA palavra ("Do",
        // "you", "feel", "lone"); letra é frase. Medido no E01: 17 frases contra 138 fragmentos.
        // Palavra única continua preservada, então o viés de preservar segue de pé — só deixa de
        // valer para o que comprovadamente não é sílaba.
        if (detector.temTagKaraoke(texto) || detector.eEfeitoKaraoke(texto)) {
            if (!ehFraseCompleta(texto)) {
                return ClasseLinhaKaraoke.EFEITO_KFX;
            }
        }

        String visivel = extrairTextoVisivel(texto);
        if (visivel.isBlank()) {
            return ClasseLinhaKaraoke.EFEITO_KFX;
        }
        if (ehComandoDeDesenho(visivel)) {
            return ClasseLinhaKaraoke.EFEITO_KFX;
        }
        if (ESCRITA_JAPONESA_PATTERN.matcher(visivel).find()) {
            return ClasseLinhaKaraoke.ORIGINAL_JAPONES;
        }

        // O nome do estilo é a evidência mais confiável: fansubs rotulam as
        // camadas ("OP - Romaji" / "OP - English"). Ele decide antes do texto.
        if (estilo != null && ESTILO_JAPONES_ROMAJI_PATTERN.matcher(estilo).find()) {
            return ClasseLinhaKaraoke.ORIGINAL_JAPONES;
        }
        // Dono único desde 08/10/2026: a auditoria (1.4) faz a mesma pergunta e precisa da MESMA
        // resposta — com duas, ela acusava como dano o que esta classe traduz de propósito.
        boolean estiloDizIngles = PadraoEstiloMusical.nomeDeclaraIngles(estilo);

        String[] palavras = normalizar(visivel).split("[^a-z]+");
        if (pareceJaPortugues(visivel, palavras)) {
            return ClasseLinhaKaraoke.JA_PORTUGUES;
        }
        if (estiloDizIngles) {
            return ClasseLinhaKaraoke.TRADUZIVEL_INGLES;
        }
        return classificarPorEvidenciaDeTexto(palavras);
    }

    /**
     * Estilo ambíguo ("Song", "OP"...): decide contando evidências inequívocas
     * de cada idioma; empate cai na fração de palavras silabáveis em japonês.
     */
    private ClasseLinhaKaraoke classificarPorEvidenciaDeTexto(String[] palavras) {
        int sinaisIngles = 0;
        int sinaisRomaji = 0;
        int totalPalavras = 0;
        int palavrasSilabaveis = 0;
        for (String palavra : palavras) {
            if (palavra.isEmpty()) {
                continue;
            }
            totalPalavras++;
            if (INGLES_FORTE.contains(palavra)) {
                sinaisIngles++;
            } else if (ROMAJI_FORTE.contains(palavra)) {
                sinaisRomaji++;
            }
            if (PALAVRA_ROMAJI_PATTERN.matcher(palavra).matches()) {
                palavrasSilabaveis++;
            }
        }
        if (totalPalavras == 0) {
            return ClasseLinhaKaraoke.EFEITO_KFX;
        }
        if (sinaisRomaji > sinaisIngles) {
            return ClasseLinhaKaraoke.ORIGINAL_JAPONES;
        }
        if (sinaisIngles > sinaisRomaji) {
            return ClasseLinhaKaraoke.TRADUZIVEL_INGLES;
        }
        // REGRA DE PAULO (2026-08-07): "jamais mexe no japonês, só no inglês".
        //
        // TODA palavra silabável em Hepburn e ZERO sinal de inglês: é japonês, e o tamanho da
        // linha não importa. Sem esta guarda, o desempate abaixo exige três palavras e a linha
        // curta cai no `else`, que TRADUZ — ou seja, em dúvida o classificador mexia no japonês,
        // exatamente ao contrário da regra.
        //
        // O prejuízo: rodado no Guilty Crown em 07/08/2026, o karaokê vem quebrado uma palavra
        // por linha e 15 de 15 alterações foram erradas — `yasashikatta` virou "era legal" e
        // `Kizuite` virou "Crow é o nome de palco", alucinação puxada da lore da obra. No 86,
        // cuja letra vem em frases inteiras, foram 0 erros em 2.156 linhas.
        //
        // CUSTO MEDIDO (MedicaoLinhaCurtaKaraokeIT, 07/08/2026, 1.658 entradas do cache de
        // karaokê já traduzidas): 5 textos distintos deixam de ser traduzidos por causa desta
        // guarda. QUATRO são acerto — `yasashikatta`, `Kizuite`, `Hanasanaide` (romaji) e o `to`
        // que o LLM havia "traduzido" como uma fala inteira da Lena. UM é perda real: `One,`
        // sozinho numa linha, que virava "Um" e agora fica em inglês. Palavra inglesa curta e
        // silabável (one, take, shine, name) cai aqui, e Paulo aceitou explicitamente esse preço
        // em 07/08/2026 — "ainda que ganhemos partes do karaokê japonês em inglês".
        //
        // NÃO confundir com "One more time, one more chance", que é preservado pelo desempate
        // por fração logo abaixo (3+ palavras, todas silabáveis) e já era antes desta guarda:
        // nenhuma dessas palavras está em INGLES_FORTE — os dois caminhos levam ao mesmo lugar.
        boolean tudoSilabavelSemIngles =
            totalPalavras > 0 && palavrasSilabaveis == totalPalavras && sinaisIngles == 0;
        if (tudoSilabavelSemIngles) {
            return ClasseLinhaKaraoke.ORIGINAL_JAPONES;
        }

        // Empate (0x0 ou 1x1): "One more time, one more chance" é letra
        // original em inglês silabável; frase inglesa comum tem encontros
        // consonantais que derrubam a fração.
        boolean parecerOriginal = totalPalavras >= MINIMO_PALAVRAS_DESEMPATE
            && (double) palavrasSilabaveis / totalPalavras >= FRACAO_ROMAJI_DESEMPATE;
        return parecerOriginal ? ClasseLinhaKaraoke.ORIGINAL_JAPONES : ClasseLinhaKaraoke.TRADUZIVEL_INGLES;
    }

    private boolean pareceJaPortugues(String visivel, String[] palavrasNormalizadas) {
        if (ACENTO_PORTUGUES_PATTERN.matcher(visivel.toLowerCase(Locale.ROOT)).find()) {
            return true;
        }
        int sinaisPt = 0;
        for (String palavra : palavrasNormalizadas) {
            if (PORTUGUES_FORTE.contains(palavra)) {
                sinaisPt++;
            }
        }
        return sinaisPt >= 2;
    }

    private static String normalizar(String visivel) {
        return visivel.toLowerCase(Locale.ROOT)
            .replace('ā', 'a').replace('ī', 'i').replace('ū', 'u')
            .replace('ē', 'e').replace('ō', 'o')
            .replace("'", "").replace("’", "");
    }

    /**
     * PROPÓSITO DE NEGÓCIO: o quanto (0–100) um texto parece japonês romanizado, para o plano
     * reconhecer a letra ORIGINAL quando ela é desenhada sílaba a sílaba, sem linha inteira.
     *
     * <p>INVARIANTES DO DOMÍNIO: o critério é do detector do peer {@code legenda}; aqui só se
     * pergunta a ele, nunca se copia a regra.
     *
     * <p>COMPORTAMENTO EM CASO DE FALHA: texto nulo ou sem palavras devolve 0 (ver o detector).
     */
    int proporcaoRomaji(String texto) {
        return detector.proporcaoRomaji(texto);
    }

    /**
     * Palavra de gramática inglesa ({@code the}, {@code you}, {@code with}...)? Pergunta única desta
     * fatia — o plano consulta aqui, não copia a lista.
     */
    static boolean ehInglesGramatical(String palavra) {
        return palavra != null && INGLES_FORTE.contains(normalizar(palavra));
    }

    /**
     * Palavra inteira silabável em romaji Hepburn ({@code como}, {@code de}, {@code anata})? Pergunta
     * única desta fatia — o plano consulta aqui, não copia o padrão.
     */
    static boolean ehSilabavelEmRomaji(String palavra) {
        return palavra != null && PALAVRA_ROMAJI_PATTERN.matcher(normalizar(palavra)).matches();
    }

    /** Há kana ou kanji no texto? Pergunta única desta fatia — o plano consulta aqui, não copia. */
    static boolean temEscritaJaponesa(String texto) {
        return texto != null && ESCRITA_JAPONESA_PATTERN.matcher(texto).find();
    }

    static String extrairTextoVisivel(String texto) {
        if (texto == null) {
            return "";
        }
        return PADRAO_REMOVE_TAGS_ASS.matcher(texto)
            .replaceAll("")
            .replace("\\N", " ")
            .replace("\\n", " ")
            .replace("\\h", " ")
            .strip();
    }
}
