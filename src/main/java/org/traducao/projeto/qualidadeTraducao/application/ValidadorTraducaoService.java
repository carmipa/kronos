package org.traducao.projeto.qualidadeTraducao.application;

import org.traducao.projeto.core.texto.FronteiraTermoAss;

import org.springframework.stereotype.Service;
import org.traducao.projeto.qualidadeTraducao.domain.AlucinacaoDetectadaException;
import org.traducao.projeto.qualidadeTraducao.domain.LoreAtivaPort;

import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * PROPÓSITO DE NEGÓCIO: impede que textos parcialmente traduzidos, respostas
 * rotuladas ou conteúdo em idioma indevido cheguem às legendas e ao cache.
 * <p>INVARIANTES DO DOMÍNIO: comentários ASS não visíveis são ignorados, nomes
 * próprios conhecidos não viram falso positivo e resíduos visíveis inequívocos
 * sempre bloqueiam a proposta.
 * <p>COMPORTAMENTO EM CASO DE FALHA: lança
 * {@link AlucinacaoDetectadaException} com diagnóstico e o chamador preserva a
 * tradução anterior.
 *
 * <h2>Por que a lore ativa entra aqui, e por porta</h2>
 * Medido em 2026-07-28 na limpeza de cache de Zeta Gundam: QUATRO traduções
 * <b>corretas</b> foram apagadas porque continham {@code "The O"} — o mobile suit do
 * Scirocco. {@code "The"} está em {@link #PADRAO_RESIDUO}, e a regra de resíduo, olhando
 * só a saída, não tem como saber que aquele {@code "The"} é metade de um nome próprio.
 * As falas destruídas eram estas, todas traduções perfeitas:
 * <pre>
 *   EN "The O, taking off!"                    PT apagado "The O, decolando!"
 *   EN "Neither The O nor the Qubeley has..."  PT apagado "Nem The O nem o Qubeley..."
 * </pre>
 * {@code "The O"} JÁ estava declarado em {@code termosProtegidos()} da obra: a informação
 * existia e não chegava até aqui.
 *
 * <p>A porta {@link LoreAtivaPort} é a forma que este projeto já usa para o mesmo problema
 * — {@link DetectorTraducaoIdenticaService}, no mesmo pacote, recebe a mesma porta pelo
 * mesmo motivo. O peer {@code qualidadeTraducao} continua sem importar o peer
 * {@code contexto}: quem implementa a porta é um adaptador de fora.
 *
 * <p>A alternativa — receber {@code Set<String>} por parâmetro em cada chamada — foi
 * descartada: tornaria a proteção OPCIONAL, dependente de cada chamador lembrar, e o
 * defeito medido nasceu exatamente de um chamador que não passou o que tinha em mãos.
 */
@Service
public class ValidadorTraducaoService {

    private final LoreAtivaPort loreAtiva;

    /** Mesmo critério de eco do portão final, sobre a MESMA porta de lore (nome legítimo fica igual). */
    private final DetectorTraducaoIdenticaService detectorIdentica;

    private static final String INICIO_DE_TERMO = FronteiraTermoAss.INICIO;

    /**
     * PROPÓSITO DE NEGÓCIO: recebe a porta de lore ativa para que nome próprio da obra
     * deixe de ser confundido com resíduo em inglês.
     * <p>INVARIANTES DO DOMÍNIO: guarda só a referência; a classe segue sem estado mutável.
     * <p>COMPORTAMENTO EM CASO DE FALHA: a porta nunca lança e degrada para conjunto vazio,
     * então sem contexto ativo o validador recai no comportamento histórico.
     */
    public ValidadorTraducaoService(LoreAtivaPort loreAtiva) {
        this.loreAtiva = loreAtiva;
        this.detectorIdentica = new DetectorTraducaoIdenticaService(loreAtiva);
    }

    // Regras robustas importadas do pipeline Python, ampliadas após observar em
    // produção o Mistral Nemo deixar fragmentos como "exactly the same" sem
    // traduzir mesmo traduzindo o resto da fala corretamente.
    //
    // UNICODE_CHARACTER_CLASS e necessario aqui: sem ela, \b no Java so reconhece
    // [a-zA-Z0-9_] como caractere de palavra, entao letras acentuadas (ç, ã, é...)
    // contam como "fronteira", e palavras em portugues como "força" ou "esforço"
    // batem com "\bfor\b" e disparam falso positivo de "resíduo em inglês".
    private static final Pattern PADRAO_RESIDUO = Pattern.compile(
        "\\b(you|they|without|very|where|what|when|why|who|this|that|these|those|"
            + "and|the|is|are|was|were|have|has|with|from|exactly|same|not|but|"
            + "except|because|could|would|should|will|shall|might|must|cannot|"
            + "their|yours|hers|ours|theirs|whom|whose|which|how|however|whenever|wherever|"
            + "about|above|across|after|against|along|among|around|before|behind|below|beneath|"
            + "beside|between|beyond|down|during|into|outside|over|past|since|through|"
            + "throughout|till|toward|under|underneath|until|upon|within|although|unless|"
            + "does|did|been|had|went|gone|got|make|made|take|took|saw|seen|thought|transform|"
            + "rather|unknown|ensign|always|never|sometimes|often|usually|really|also|even|"
            // "only": assinatura medida do LLM local com o "Just" enfático do inglês. Em
            // 2026-07-28, 33 falas do acervo (58.722 com tradução) traziam "only" cru, sempre
            // com o mesmo gatilho — "Just" abrindo oração, quase sempre depois de "!":
            //   "All right! Just try and stop me!"  ->  "Tudo bem!only tentem me impedir!"
            //   "Never mind the guidance! Just hit them!" -> "Não ligue...!only bati neles!"
            // Em 13 delas o modelo larga a oração INTEIRA em inglês ("only watch me!"). Nenhum
            // falso positivo em 36 casos inspecionados: "only" não é palavra do português, e a
            // régua já roda sobre o texto MENOS os termos de lore, então nome próprio protegido
            // não chega aqui.
            + "only|"
            + "every|please|thank|thanks|sorry|feds)\\b",
        Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CHARACTER_CLASS
    );

    // Contrações inglesas: resíduo inequívoco (nenhuma colide com PT-BR) que a
    // lista de palavras soltas não pega — caso real do 86: "Se você terminou sua
    // missão, it's seu dever me dar um relatório." passava sem disparar nada.
    // Aceita apóstrofo ASCII (') e tipográfico (’).
    private static final Pattern PADRAO_CONTRACAO_INGLES = Pattern.compile(
        "\\b(?:it|that|there|what|here|he|she|let)['’]s\\b"
            + "|\\b(?:don|can|won|ain|didn|doesn|isn|aren|wasn|weren|couldn|wouldn|shouldn|hasn|haven)['’]t\\b"
            + "|\\b(?:i|you|we|they)['’](?:m|ll|ve|re|d)\\b"
            + "|\\b(?:gonna|gotta|wanna)\\b",
        Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CHARACTER_CLASS
    );

    // Palavras inequívocas de francês (sem colisão com vocabulário PT-BR) que pegam
    // o LLM local "vazando" pra francês em vez de inglês — caso observado em
    // produção: "WITH SHINING BLUE FIRE" foi "corrigido" para "AURA BLEU BRILLANTE"
    // e passou batido pelo PADRAO_RESIDUO, que só cobre inglês. Espanhol não entra
    // aqui: nunca foi observado como idioma de origem/vazamento neste projeto.
    private static final Pattern PADRAO_OUTRO_IDIOMA = Pattern.compile(
        "\\b(bleu|rouge|noir|blanc|jaune|monde|cœur|coeur|amour|bonjour|merci|"
            + "toujours|déjà|deja|être|avoir|avec|chez|sans|vous|nous|elles|"
            // "juste": mesma assinatura do "only" acima, mas o vazamento sai em FRANCÊS —
            // "Just have some." virou "Juste um pouco.", "Just hang in there..." virou
            // "Juste aguenta aí...". 3 falas no acervo em 2026-07-28, 2 delas criadas na
            // retradução do 08th no mesmo dia. Não colide com português: a fronteira de palavra
            // impede que "ajuste"/"reajuste" casem, porque o "a" anterior é caractere de palavra.
            + "juste|"
            + "très|tres|où|quelque|aujourd'hui)\\b",
        Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CHARACTER_CLASS
    );

    // Evita os falsos positivos de "Abaixo a tirania" bloqueando apenas preâmbulos óbvios.
    // "tradução:"/"saída:" no início cobre o LLM rotulando a resposta (casos reais:
    // linha de karaokê do Gundam Narrative entregue como "Tradução: {\r\pos(488,23)...}ep"
    // e efeito visual devolvido como "Saída: {=68}{\pos(...)}").
    // Preâmbulo é o LLM falando DA TAREFA em vez de traduzir. Toda alternativa precisa citar
    // o vocabulário da tarefa (tradução/saída/resposta) ou vir com dois-pontos — senão a regra
    // condena diálogo legítimo.
    //
    // MEDIDO EM 2026-07-28: a alternativa "aqui está a" era solta e apagou uma fala real do
    // cache — EN "{\be3}Here's the order." traduzido como "{\be3}Aqui está a ordem.", tradução
    // correta, esvaziada com o motivo "Preâmbulo detectado". Pior, em laço: o Google repunha e
    // a limpeza seguinte apagava de novo. "Aqui está" é abertura comum de diálogo em português
    // ("Aqui está a chave", "Aqui está a ordem") e sozinha não distingue nada.
    //
    // O conserto exige o vocabulário da tarefa logo depois: "Aqui está a tradução:" continua
    // pego, "Aqui está a ordem." passa. Não precisa do texto original para decidir.
    //
    // MEDIDO EM 2026-09-09 (auditoria de terceiro): "Here's the answer to your question." é
    // fala de diálogo, e sua tradução correta "Aqui está a resposta à sua pergunta." casava a
    // alternativa "resposta" e era descartada. "Resposta" sozinha não distingue preâmbulo de
    // diálogo, exatamente como "aqui está a" não distinguia em 2026-07-28 — é a MESMA classe de
    // defeito, uma alternativa a mais dentro da mesma regra. A alternativa saiu daqui e passou a
    // viver em RECUSAS_AMBIGUAS, onde o ORIGINAL a absolve; sem original ela continua bloqueando.
    private static final Pattern PADRAO_PREAMBULO = Pattern.compile(
        "^(esta [ée] a tradu|abaixo seguem (a|as) tradu|"
            + "aqui (est[áa]|v[ãa]o) (a |as |o |os )?(tradu|sa[ií]da|texto traduzido)|"
            + "tradução solicitada|a tradução seria|"
            + "tradu[çc][ãa]o\\s*:|sa[ií]da\\s*:)",
        Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CHARACTER_CLASS
    );

    // Recusa/meta-resposta do LLM: em vez de traduzir, o modelo devolve uma
    // desculpa, um pedido de mais contexto ou fala SOBRE a tarefa de tradução, e
    // esse texto ia direto para a legenda (bug histórico do software antigo:
    // "Eh?" -> "Desculpe, mas não recebi nenhuma linha para traduzir. Por favor,
    // forneça-me as linhas...", "No, sir!" -> "Sem tradução.", "Dunno" -> "não
    // tenho contexto para traduzir a linha..."). Ancorado na META-referência à
    // tradução — NUNCA em "por favor"/"desculpe"/"não posso" sozinhos, que são
    // diálogo legítimo comum em anime (validado contra 88 recusas reais x 125
    // falas legítimas com "por favor" no cache do Gundam 08th MS Team).
    // Inclui também a RECITAÇÃO DE CAPACIDADE/PAPEL: ao ver "About who I am and what
    // I'm capable of." (08th ep1), o modelo recitou o prompt de sistema -> "Sou capaz
    // de traduzir legendas de anime ... preservando ... intenção emocional". Como está
    // em PT e preserva o ASS, escapava da validação. Ancorado no vocabulário de
    // tarefa/prompt ("traduzir legendas", "legendas de anime", "intenção emocional") —
    // NUNCA em "sou capaz"/"preservando"/"emocional" sozinhos, que são fala legítima.
    //
    // AS TRÊS ALTERNATIVAS AMBÍGUAS SAÍRAM DAQUI EM 2026-09-09 (auditoria de terceiro, medida
    // com o Aya carregado): "não recebi", "não tenho acesso" e "preciso de mais informações"
    // descartavam a tradução CORRETA de falas de diálogo — "I don't have access to the hangar."
    // e "I need more information about the enemy." morriam nas três temperaturas, porque
    // aumentar temperatura não vence regra que rejeita a resposta certa. Elas não são recusa
    // por si: são recusa quando o ORIGINAL não as sustenta. Foram para RECUSAS_AMBIGUAS.
    // O que fica aqui é vocabulário de PROTOCOLO, que fala de anime nenhuma produz.
    private static final Pattern PADRAO_RECUSA_META = Pattern.compile(
        "(?:sem\\s+tradu[çc][ãa]o\\b"
            + "|(?:linha|texto|frase)s?\\s+(?:para\\s+|que\\s+.{0,20}?)?traduzir"
            + "|traduzir\\s+(?:a\\s+linha|a\\s+frase|o\\s+texto|isso|este)"
            + "|nenhuma\\s+tradu[çc][ãa]o\\s+encontrada"
            + "|--\\s*tradu[çc][ãa]o|^traduzir\\s*:"
            + "|n[ãa]o\\s+precisa\\s+ser\\s+retrabalhada|a\\s+tradu[çc][ãa]o\\s+(?:atual\\s+)?[ée]\\s+correta"
            + "|as\\s+an\\s+ai|i\\s+(?:cannot|can'?t|am\\s+unable)\\b"
            + "|provide\\s+more\\s+(?:context|info)|don'?t\\s+have\\s+.{0,20}context"
            + "|traduzir\\s+legendas?|legendas?\\s+de\\s+anime|inten[çc][ãa]o\\s+emocional)",
        Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CHARACTER_CLASS
    );

    /**
     * PROPÓSITO DE NEGÓCIO: uma construção portuguesa que é meta-resposta do modelo OU tradução
     * legítima, e que só o ORIGINAL desempata. Guarda o padrão em português, a âncora que o
     * original precisa exibir para absolvê-la, e o rótulo que entra no diagnóstico.
     *
     * <p>INVARIANTES DO DOMÍNIO: a âncora é procurada SOMENTE no original; ausência de original
     * é tratada como ausência de licença, para que nenhum chamador cego perca proteção.
     *
     * <p>COMPORTAMENTO EM CASO DE FALHA: é um registro imutável; não lança.
     */
    private record RecusaAmbigua(String rotulo, Pattern emPortugues, Pattern ancoraNoOriginal) {}

    // POR QUE ANCORAR NO ORIGINAL, E NÃO AFROUXAR A REGRA (auditoria de 2026-09-09):
    // a auditoria reproduziu cinco traduções corretas bloqueadas e recomendou, com todas as
    // letras, NÃO criar liberação geral para qualquer frase que contenha estas palavras. A
    // diferença entre recusa e diálogo não está no português: "Não tenho acesso ao hangar."
    // é idêntico nos dois casos. Está em o original dizer, ou não, a mesma coisa. Com âncora,
    // "Go!" -> "Não recebi nenhuma linha para traduzir." continua bloqueado (nada em "Go!"
    // fala de receber), e "I don't have access to the hangar." passa.
    //
    // A ÂNCORA É DELIBERADAMENTE LARGA (o radical, não a frase): tradução não é decalque, e
    // exigir correspondência estrita devolveria o falso positivo por outra porta.
    private static final List<RecusaAmbigua> RECUSAS_AMBIGUAS = List.of(
        new RecusaAmbigua("não tenho acesso",
            Pattern.compile("n[ãa]o\\s+tenho\\s+acesso", Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CHARACTER_CLASS),
            Pattern.compile("\\b(?:access|clearance|permission)", Pattern.CASE_INSENSITIVE)),
        new RecusaAmbigua("não recebi",
            Pattern.compile("n[ãa]o\\s+recebi\\b", Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CHARACTER_CLASS),
            Pattern.compile("\\b(?:receiv|got|get|hear[dt]?|word)", Pattern.CASE_INSENSITIVE)),
        new RecusaAmbigua("não tenho como saber",
            Pattern.compile("n[ãa]o\\s+tenho\\s+como\\s+saber", Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CHARACTER_CLASS),
            Pattern.compile("\\b(?:know|tell|idea|way)", Pattern.CASE_INSENSITIVE)),
        new RecusaAmbigua("não tenho contexto/informação",
            Pattern.compile("n[ãa]o\\s+tenho\\s+(?:contexto|informa)", Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CHARACTER_CLASS),
            Pattern.compile("\\b(?:context|information|info|intel|detail)", Pattern.CASE_INSENSITIVE)),
        new RecusaAmbigua("preciso de mais contexto/informação",
            Pattern.compile("preciso\\s+de\\s+mais\\s+(?:contexto|informa)", Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CHARACTER_CLASS),
            Pattern.compile("\\b(?:need|want|require)\\b.{0,30}\\b(?:more|information|info|context|intel|detail)", Pattern.CASE_INSENSITIVE)),
        new RecusaAmbigua("fornecer linha/contexto",
            Pattern.compile("forne\\S*\\s+.{0,40}?(?:linha|contexto|informa|texto|t[íi]tulo)", Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CHARACTER_CLASS),
            Pattern.compile("\\b(?:provide|supply|give|send|report)", Pattern.CASE_INSENSITIVE)),
        new RecusaAmbigua("pode me enviar",
            Pattern.compile("pode\\s+me\\s+enviar", Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CHARACTER_CLASS),
            Pattern.compile("\\b(?:send|transmit|give|relay|forward)", Pattern.CASE_INSENSITIVE)),
        // A FRONTEIRA \b AQUI FOI MEDIDA, NÃO SUPOSTA (2026-09-09, 114.329 pares do acervo):
        // sem ela, "mand\S+" casava DENTRO de "cha-MANDO" e reprovava o verso "E eu estou
        // chamando seu nome novamente." — 21 ocorrências gravadas, e ZERO falas casariam com
        // a fronteira posta. Custo do conserto: nenhum verdadeiro positivo perdido.
        new RecusaAmbigua("enviar/mandar novamente",
            Pattern.compile("\\b(?:envi\\S+|mand\\S+)\\s+.{0,15}novamente", Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CHARACTER_CLASS),
            Pattern.compile("\\b(?:again|resend|repeat|once\\s+more|one\\s+more\\s+time)", Pattern.CASE_INSENSITIVE)),
        new RecusaAmbigua("preciso da linha/contexto",
            Pattern.compile("preciso\\s+(?:de|da|dessa)\\s+.{0,20}(?:linha|contexto|informa)", Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CHARACTER_CLASS),
            Pattern.compile("\\b(?:need|want|require)", Pattern.CASE_INSENSITIVE)),
        new RecusaAmbigua("aqui está a resposta",
            Pattern.compile("^\\s*(?:aqui\\s+(?:est[áa]|v[ãa]o)\\s+(?:a\\s+|as\\s+|o\\s+|os\\s+)?resposta|resposta\\s*:)",
                Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CHARACTER_CLASS),
            Pattern.compile("\\b(?:answer|response|reply|here)", Pattern.CASE_INSENSITIVE))
    );

    // Palavras do PADRAO_RESIDUO que também são nomes próprios comuns em
    // anime/mangá (ex.: o personagem "Will"). Uma ocorrência Capitalizada
    // dessas palavras numa frase PT-BR é quase sempre nome, não resíduo —
    // sem esta exceção a fala inteira era rejeitada e mantida sem tradução.
    private static final java.util.Set<String> RESIDUOS_TAMBEM_NOMES = java.util.Set.of("will");

    // Asterisco no texto visível: o LLM às vezes CENSURA palavrão ("You bastards!" ->
    // "uns *****es!") ou vaza MARKDOWN de ênfase ("Damn it!" -> "Merd**a**!"). Casos reais
    // em ZZ/08th/Zeta/86/Narrative. Neste projeto a fonte NUNCA traz '*' e as legendas usam
    // parênteses para ação — então qualquer '*' visível é artefato do modelo e a fala deve
    // ficar pendente (retraduz) em vez de ir censurada/pontilhada para a legenda. Bônus:
    // entradas de cache com asterisco deixam de ser reaproveitadas e retraduzem no próximo run.
    private static final Pattern PADRAO_ASTERISCO = Pattern.compile("\\*");

    // Blocos {...} do ASS (tags de override e comentários de fansub) não são
    // texto exibido: valida-los como fala gerava falso positivo (comentário
    // legítimo em inglês tipo "{Yes, ma'am}" disparava resíduo e queimava uma
    // chamada de LLM à toa) e falso negativo (preâmbulo depois de tag, como
    // "{\i1}Tradução: ...", escapava da âncora ^ do padrão de preâmbulo).
    private static final Pattern PADRAO_BLOCO_ASS = Pattern.compile("\\{[^}]*\\}");

    // Marcadores do pipeline ([[TAG0]]) não são texto exibido: entram e saem da fala sem
    // alterar o que o espectador lê. Precisam sair antes de QUALQUER medida de comprimento,
    // senão uma fala curta cheia de marcadores parece longa e a desproporção não dispara.
    private static final Pattern PADRAO_MARCADOR = Pattern.compile("\\[\\[TAG\\d+\\]\\]");

    // ---------------------------------------------------------------------------------
    // LIMIARES DA VALIDAÇÃO DE PAR — todos MEDIDOS, nenhum estimado.
    //
    // Corpus: as 5.794 entradas não-vazias do run completo de Guilty Crown (23 episódios,
    // mistral-nemo), com os 27 defeitos publicados nos .ass confirmados um a um contra a
    // legenda entregue. A varredura de limiares está registrada na discussão com o Codex
    // (RESPOSTA_CODEX_PARA_CLAUDE_KRONOS_2026-07-26.md).
    //
    // Resultado desta combinação: 26 dos 27 defeitos capturados, ZERO falso positivo em
    // 5.794. Afrouxar o fator para 2,0 ou o mínimo para 18 caracteres introduz falso
    // positivo imediato em fala legítima curta ("Huh?" -> "O que foi?"); apertar para 3,5
    // perde dois defeitos reais. Estes três números são um ponto ótimo medido, não uma
    // preferência — alterá-los exige repetir a medição sobre o corpus.
    // ---------------------------------------------------------------------------------

    /** Acima deste comprimento o original tem conteúdo suficiente para justificar expansão. */
    private static final int COMPRIMENTO_MAXIMO_ORIGINAL_CURTO = 30;

    /** Abaixo disto a tradução é curta demais para que a razão signifique alguma coisa. */
    private static final int COMPRIMENTO_MINIMO_PARA_DESPROPORCAO = 20;

    /** Razão tradução/original a partir da qual o excedente deixa de ser expansão natural do PT-BR. */
    private static final double FATOR_DESPROPORCAO = 2.5;

    // ---------------------------------------------------------------------------------
    // SENTIDO DA FALA (08/10/2026): duas reprovações de PAR que só o original prova — a resposta
    // com a polaridade INVERTIDA e a pergunta que virou AFIRMAÇÃO. O português sai limpo nas
    // duas, e por isso nenhuma regra de saída as via. Critério declarado e medido ANTES de virar
    // código, sobre os 66.857 pares dos caches do acervo:
    //   polaridade — 10 casos, 10 erros reais ("No." -> "Sim." cinco vezes no 0083;
    //                "No... it's too late for that." -> "Sim... é tarde demais" no Unicorn);
    //   pergunta   — 20 casos, 19 reais ("A rat?!" -> "Que porco colorido!", "Ensign Reccoa is?"
    //                -> "Reccoa é uma tenente.", "3:30? Only 30 minutes left!" com a pergunta
    //                cortada); o 20º é pergunta indireta aceitável.
    // Fora do ajuste, nas 1.300 falas traduzidas do zero em 08/10: 1 caso, real ("'Our'?" ->
    // "Nosso"). Falso negativo declarado: "Like this?!" -> "Como quiser!" escapa porque a
    // tradução abre com "Como" — a exclusão de interrogativo é larga de propósito (A8: a
    // reprovação vira outra tentativa, e o esgotamento republica o INGLÊS; errar para mais aqui
    // custa uma fala em inglês na tela).
    // ---------------------------------------------------------------------------------

    /** O original ABRE com resposta negativa — fora as locuções em que "no" não nega nada. */
    private static final Pattern NEGATIVA_NO_INICIO_EN = Pattern.compile(
        "^\\W*(?:n-)?(?:no|nah|nope|negative)\\b(?!\\s+(?:problem|worries|doubt|kidding|way|matter|one"
            + "|wonder|more|longer|need|sweat|offense|thanks)\\b)",
        Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CHARACTER_CLASS);

    /** A tradução ABRE com resposta afirmativa (com gagueira "S-Sim" inclusive). */
    private static final Pattern AFIRMATIVA_NO_INICIO_PT = Pattern.compile(
        "^\\W*(?:s-)?(?:sim|claro|certo|isso|ok|tudo bem|entendido|com certeza)\\b",
        Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CHARACTER_CLASS);

    /**
     * A única afirmativa que o reparo de polaridade troca: a palavra "Sim" na abertura (gagueira
     * "S-Sim" inclusive), depois de tags ASS e marcadores. Grupos: 1 o que vem antes, 2 a letra da
     * gagueira, 3 a palavra.
     */
    private static final Pattern SIM_NA_ABERTURA = Pattern.compile(
        "^((?:\\{[^}]*\\}|\\[\\[TAG\\d+\\]\\]|[^\\p{L}\\p{N}])*)(?:(s)-)?(sim)(?![\\p{L}\\p{N}])",
        Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

    /** Exemplo de tradução escrito no prompt: {@code "EN" fica "PT"} (também "vira", "->" e "→"). */
    private static final Pattern EXEMPLO_DO_PROMPT = Pattern.compile(
        "\"([^\"\\n]{2,200})\"\\s*(?:fica|vira|->|→)\\s*\"([^\"\\n]{2,200})\"");

    /** Os exemplos extraídos do último prompt visto; o prompt muda só quando a obra ativa muda. */
    private record ExemplosDoPrompt(String prompt, java.util.Map<String, Set<String>> ensPorPt) { }

    private volatile ExemplosDoPrompt exemplosEmMemoria;

    /** Pergunta-eco: o original visível é SÓ um trecho entre aspas seguido de "?"; grupo 1, as reticências. */
    private static final Pattern PERGUNTA_ECO_ENTRE_ASPAS = Pattern.compile(
        "^\\s*[\"'“‘][^\"“”]{1,40}[\"'”’]\\s*(\\.\\.\\.|…)?\\s*\\?\\s*$");

    /** A eco é curta: tradução maior que isto não é eco, é outra coisa, e não se repara. */
    private static final int MAX_PALAVRAS_PERGUNTA_ECO = 4;

    /** Pontuação final da fala e, no grupo 1, o que vem depois dela sem aparecer (tags, marcadores). */
    private static final Pattern FIM_DA_FALA = Pattern.compile(
        "[\\s.!…]*((?:\\{[^}]*\\}|\\[\\[TAG\\d+\\]\\]|\\s)*)$");

    /** Qualquer negação na tradução absolve: "Claro que não!" é a resposta certa a "No way!". */
    private static final Pattern NEGACAO_NA_TRADUCAO = Pattern.compile(
        "\\b(?:não|nao|nem|nunca|jamais|nenhum|nenhuma|nada|ninguém|ninguem)\\b",
        Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CHARACTER_CLASS);

    /**
     * Pergunta que o PT legítimo pode verter SEM "?": retórica de forma imperativa ("Would you
     * calm down?!" -> "Acalme-se!"), auxiliar negativo retórico ("Didn't I tell you...?!"),
     * suposição ("I suppose...?") e "I wonder" (vertido como "Eu me pergunto...").
     *
     * <p>"How about you" + VERBO é a mesma ordem disfarçada de "Why don't you": {@code "How about
     * you GET THE HELL OFF?!"} vertido como {@code "E aí, você vai sair daqui agora mesmo!"} ficava
     * pendente e saía em INGLÊS (Break Blade 3, 10/10/2026). Sem verbo depois, {@code "How about
     * you?"} é pergunta de verdade ("E você?") e continua cobrada.
     */
    private static final Pattern PERGUNTA_RETORICA_EN = Pattern.compile(
        "^\\W*(?:would|will|could|can|won'?t)\\s+you\\b|^\\W*why\\s+don'?t\\s+you\\b|\\bI\\s+wonder\\b"
            + "|^\\W*how\\s+about\\s+you\\s+\\p{L}"
            + "|^\\W*(?:didn'?t|don'?t|won'?t|wouldn'?t|can'?t|couldn'?t|isn'?t|aren'?t|wasn'?t|weren'?t"
            + "|haven'?t|hasn'?t|shouldn'?t|doesn'?t)\\b|^\\W*I\\s+(?:suppose|take\\s+it|guess)\\b",
        Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CHARACTER_CLASS);

    /** Pergunta de confirmação no fim (", right?", ", can we?", ", can you, Judau?"): cair é estilo. */
    private static final Pattern PERGUNTA_DE_CONFIRMACAO_EN = Pattern.compile(
        ",\\s*(?:right|remember|okay|ok|huh|eh|yes|no|(?:can|could|will|would|do|does|did|is|are|was|were"
            + "|have|has|should|shall)(?:n'?t)?\\s+(?:i|you|he|she|it|we|they))(?:\\s*,\\s*\\w+)?\\W*\\?[!?]*\\W*$",
        Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CHARACTER_CLASS);

    /** A tradução já abre como pergunta ("Por que você...!"): perdeu só a pontuação. */
    private static final Pattern INTERROGATIVO_NO_INICIO_PT = Pattern.compile(
        "^\\W*(?:por\\s+qu[eê]|o\\s+qu[eê]|quem|onde|quando|como|qual|quais|quant[oa]s?)\\b",
        Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CHARACTER_CLASS);

    // Prefixo "<algo>:" no início da fala — o formato que o modelo usa ao narrar quem fala
    // ("Inori: \"Você me ama, Shu?\"") em vez de traduzir. O limite de 30 caracteres antes dos
    // dois-pontos separa nome de personagem de uma oração inteira que legitimamente termina
    // em dois-pontos. O grupo 2 guarda o que vem depois, usado para distinguir invenção de
    // simples troca de pontuação.
    private static final Pattern PADRAO_PREFIXO_LOCUTOR = Pattern.compile("^([^:]{1,30}):(.*)$", Pattern.DOTALL);

    // Conectivos de discurso que legitimamente abrem uma fala com dois-pontos em PT-BR.
    // Sem esta allowlist, "I repeat:" traduzido como "Repito:" seria acusado de locutor
    // inventado — caso real e correto, medido no corpus.
    //
    // O VERBO DE ELOCUÇÃO foi o buraco: a lista só tinha substantivos e interjeições, então
    // discurso relatado — que em português EXIGE dois-pontos — caía como locutor inventado.
    // Dois casos medidos, em obras diferentes:
    //   08th  EN "They said \"Now we're reborn.\""        PT "Eles disseram: \"Agora nascemos...\""
    //   ZZ    EN "He said, \"That can wait till later!\"" PT "Ele disse: \"Isso pode esperar...\""
    // No 08th custou 1 das 10 falas vazias da rodada de 2026-07-28. Note que o sujeito vem
    // ANTES do verbo ("Eles disseram"), então o padrão precisa aceitar o pronome opcional —
    // casar só o verbo deixaria os dois casos reais de fora.
    //
    // A ASPA DE ABERTURA também não muda nada (10/10/2026, medido nas 116 recusas reais do console):
    //   EN "\"Warning \" A large number of Gauna Pods..."  PT "\"Aviso: Um grande número de pods..."
    //   EN "\"Warning \" The Large Gauna Cluster..."       PT "\"Atenção: O Grande Aglomerado..."
    // "Aviso:" e "Atenção:" já eram aceitos; a âncora ^ é que não deixava a aspa vir antes. E o
    // "Eu repito:" de "I say again," caía porque "repito" estava aqui e o pronome só na lista de
    // verbos — o mesmo verbo já valia como "repetiu" com pronome.
    private static final Pattern PADRAO_CONECTIVO_DISCURSO = Pattern.compile(
        "^[\"'“”«‘’]?\\s*(?:repito|aten[çc][ãa]o|aviso|nota|observa[çc][ãa]o|ou seja|isto|isso|ent[ãa]o|bem|sim|"
            + "n[ãa]o|mas|ah|oh|ei|olha|escuta|escute|veja|ora"
            + "|(?:(?:ele|ela|eles|elas|voc[êe]|voc[êe]s|eu|n[óo]s)\\s+)?"
            // Pronome oblíquo OPCIONAL entre sujeito e verbo: "Ela O saudara com", "Ele ME
            // disse". Sem isto, "Ela o saudara" não casa e a fala vira pendência — foi o caso
            // do ep 06 do 86 em 14/08/2026.
            + "(?:(?:o|a|os|as|lhe|lhes|me|te|nos|vos)\\s+)?"
            + "(?:disse|disseram|dizia|diziam|falou|falaram|respondeu|responderam|"
            + "gritou|gritaram|perguntou|perguntaram|avisou|avisaram|contou|contaram|"
            + "explicou|explicaram|repetiu|repetiram|repito|repetimos|pensou|pensaram"
            // Terceira leva, 14/08/2026, medida no 86: duas falas viraram pendência por aqui,
            // e as duas eram tradução CORRETA de conectivo que existe no original.
            //   ep 07  EN "She means, ..."          PT "Ela quer dizer: \"Depois que fomos mortos.\""
            //   ep 06  EN "She greeted him with..." PT "Ela o saudara com: \"E uma manha...\""
            // O verbo composto ("quer dizer") e o pronome oblíquo entre sujeito e verbo ("o
            // saudara") não casavam a lista, que só previa verbo simples logo após o pronome.
            + "|quer dizer|queria dizer|quis dizer|significa|significava"
            + "|saudou|saudara|saudaram|cumprimentou|chamou|chamaram|murmurou|sussurrou)"
            + ")\\s*:",
        Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CHARACTER_CLASS
    );

    // Meta-resposta que só é reconhecível COM o original em mãos: o modelo comenta a tarefa
    // de traduzir em vez de executá-la. Difere do PADRAO_RECUSA_META porque não depende de
    // recusa explícita — "Minha tradução para a sua linha é: ..." é afirmativo e escapava.
    // Ancorado no vocabulário de TAREFA, nunca em "desculpe"/"por favor" isolados, que são
    // fala legítima (50 dos 56 primeiros flags do corpus eram diálogo real).
    //
    // AS TRÊS ÚLTIMAS ALTERNATIVAS SAÍRAM DAQUI EM 2026-09-09 e viraram RECUSAS_AMBIGUAS:
    // "pode me enviar", "preciso da linha" e "enviar/mandar novamente" descrevem tanto o modelo
    // pedindo insumo quanto personagem pedindo coordenadas. Apesar do nome validarPar, a regra
    // aplicava o padrão SÓ à tradução e nunca olhava o original — que é justamente o que
    // desempata. O que fica aqui é o que nomeia a própria tradução como objeto ("minha
    // tradução", "tradução para a sua linha"), e isso nenhum personagem diz.
    private static final Pattern PADRAO_META_DE_PAR = Pattern.compile(
        "minha\\s+tradu[çc][ãa]o"
            + "|tradu[çc][ãa]o\\s+(?:para|de)\\s+(?:a\\s+)?(?:sua\\s+)?linha"
            + "|para\\s+poder\\s+traduzir",
        Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CHARACTER_CLASS
    );

    /**
     * PROPÓSITO DE NEGÓCIO: valida o texto visível de uma fala antes de sua
     * persistência, detectando resíduos e respostas fora do contrato PT-BR.
     * <p>INVARIANTES DO DOMÍNIO: tags e comentários ASS são retirados somente
     * para a inspeção; o texto recebido nunca é modificado.
     * <p>COMPORTAMENTO EM CASO DE FALHA: texto vazio é aceito; violação lança
     * {@link AlucinacaoDetectadaException} com a fala original no diagnóstico.
     */
    public void validarFala(String textoTraduzido) {
        validarFala(textoTraduzido, null);
    }

    /**
     * PROPÓSITO DE NEGÓCIO: mesma validação de {@link #validarFala(String)}, mas com o texto de
     * partida em mãos, para que uma construção ambígua — "não tenho acesso", "não recebi",
     * "preciso de mais informações" — deixe de ser lida como recusa do modelo quando o próprio
     * original diz aquilo. É a correção do defeito medido em 2026-09-09, em que a tradução
     * CORRETA de falas de diálogo era descartada nas três temperaturas.
     *
     * <p>INVARIANTES DO DOMÍNIO: o original é usado SÓ para absolver, nunca para condenar — toda
     * regra que já bloqueava sem ele continua bloqueando. {@code textoOriginal} nulo ou vazio
     * significa "sem licença possível" e reproduz exatamente o comportamento histórico, para que
     * nenhum chamador que não tem o original perca proteção.
     *
     * <p>COMPORTAMENTO EM CASO DE FALHA: lança {@link AlucinacaoDetectadaException} com o
     * diagnóstico; texto vazio é aceito em silêncio.
     *
     * @param textoTraduzido a proposta de tradução, com tags ASS ou sem elas
     * @param textoOriginal  o texto de partida, ou {@code null} quando o chamador não o tem
     */
    public void validarFala(String textoTraduzido, String textoOriginal) {
        if (textoTraduzido == null || textoTraduzido.trim().isEmpty()) {
            return;
        }

        String visivel = PADRAO_BLOCO_ASS.matcher(textoTraduzido).replaceAll("").trim();
        if (visivel.isEmpty()) {
            return;
        }

        // As regras de RESÍDUO/IDIOMA julgam palavra a palavra e por isso podem confundir nome
        // próprio da obra com texto não traduzido. Elas — e só elas — inspecionam o texto sem
        // os termos da lore. As demais regras (preâmbulo, recusa, asterisco) tratam de
        // vocabulário de TAREFA, que nome próprio não produz.
        String semTermosDaLore = removerTermosProtegidos(visivel);

        if (temResiduoRelevante(semTermosDaLore)) {
            throw new AlucinacaoDetectadaException("Resíduo gringo detectado: " + textoTraduzido);
        }

        if (PADRAO_CONTRACAO_INGLES.matcher(semTermosDaLore).find()) {
            throw new AlucinacaoDetectadaException("Resíduo gringo detectado (contração): " + textoTraduzido);
        }

        if (PADRAO_OUTRO_IDIOMA.matcher(semTermosDaLore).find()) {
            throw new AlucinacaoDetectadaException("Idioma incorreto detectado (não é PT-BR): " + textoTraduzido);
        }

        if (PADRAO_PREAMBULO.matcher(visivel).find()) {
            throw new AlucinacaoDetectadaException("Preâmbulo detectado: " + textoTraduzido);
        }

        if (PADRAO_RECUSA_META.matcher(visivel).find()) {
            throw new AlucinacaoDetectadaException("Recusa/meta-resposta do LLM detectada: " + textoTraduzido);
        }

        RecusaAmbigua semApoio = recusaSemApoioNoOriginal(visivel, textoOriginal);
        if (semApoio != null) {
            throw new AlucinacaoDetectadaException(
                "Recusa/meta-resposta do LLM detectada (\"" + semApoio.rotulo()
                    + "\" sem apoio no original): " + textoTraduzido);
        }

        // Marcador de falha do pipeline Python antigo encontrado em legendas
        // legadas (ex.: "[ERRO_TRADUCAO: The Garanden!]" na G-Reconguista).
        // Garante retradução na revisão mesmo quando o conteúdo não dispara
        // o padrão de resíduo em inglês.
        if (visivel.contains("ERRO_TRADUCAO")) {
            throw new AlucinacaoDetectadaException("Marcador de erro de tradução detectado: " + textoTraduzido);
        }

        if (PADRAO_ASTERISCO.matcher(visivel).find()) {
            throw new AlucinacaoDetectadaException("Censura/markdown com asterisco detectado: " + textoTraduzido);
        }
    }

    /**
     * PROPÓSITO DE NEGÓCIO: valida a fala CONTRA O ORIGINAL que a gerou, detectando o defeito
     * que {@link #validarFala(String)} é estruturalmente incapaz de ver — texto em PT-BR
     * impecável, sem resíduo, sem preâmbulo e sem vocabulário de tarefa, mas que <b>não está
     * ancorado no original</b>. É a classe medida em produção no run de Guilty Crown: 27
     * entradas publicadas nas legendas, entre elas {@code "No."} traduzido como
     * {@code "Shu: Eu não posso... usar o King's Power..."} e {@code "Souta!"} como uma ficha
     * inteira do roster. Nenhuma delas dispara qualquer regra que olhe só a saída.
     *
     * <p><b>Invariantes do domínio</b>
     * <ul>
     *   <li>O invariante violado é ANCORAGEM, não comprimento: a tradução carrega material que
     *       o original não sustenta. Comprimento é sintoma, e por isso a desproporção é apenas
     *       uma das três evidências — sozinha ela deixaria 8 dos 27 casos passar.</li>
     *   <li>Os três limiares saíram de MEDIÇÃO sobre as 5.794 entradas reais do run, não de
     *       estimativa: juntos capturam 26 dos 27 defeitos com ZERO falso positivo. Mexer
     *       neles sem repetir a medição troca precisão comprovada por chute.</li>
     *   <li>Prefixo de locutor só é defeito quando NÃO vem do original: {@code "Gai—"}
     *       traduzido como {@code "Gai:"} é troca de pontuação, não invenção de falante —
     *       exigir conteúdo após os dois-pontos OU prefixo não ancorado elimina esses três
     *       falsos positivos sem perder {@code "Done!" -> "Linha 1:"}.</li>
     *   <li>Dois-pontos legítimo herdado do original nunca acusa, e conectivos de discurso
     *       ({@code "Repito:"}, {@code "Atenção:"}) estão na allowlist — {@code "I repeat:"}
     *       traduzido como {@code "Repito:"} é tradução correta.</li>
     * </ul>
     *
     * <p><b>Comportamento em caso de falha</b>
     * Texto vazio, original ausente ou fala que sobra vazia após limpar tags são aceitos: sem
     * um par comparável não há ancoragem a medir, e reprovar aí trocaria uma checagem inútil
     * por uma pendência inventada. Violação lança {@link AlucinacaoDetectadaException} com o
     * diagnóstico, e o chamador preserva o original.
     *
     * @param textoOriginal fala de origem que produziu a tradução
     * @param textoTraduzido fala devolvida pelo tradutor (LLM ou fallback de máquina)
     */
    public void validarPar(String textoOriginal, String textoTraduzido) {
        if (textoOriginal == null || textoTraduzido == null) {
            return;
        }
        String original = visivel(textoOriginal);
        String traduzido = visivel(textoTraduzido);
        if (original.isEmpty() || traduzido.isEmpty()) {
            return;
        }

        // ECO DENTRO DA TENTATIVA (08/10/2026). A cópia do original só era julgada no portão final,
        // DEPOIS do laço de temperaturas — então o modelo ecoar "Damn it!" nunca virava outra
        // tentativa. E o portão comparava com aspas: '"Damn it!"' passava como "diferente", o
        // normalizador de aspas (que roda depois do portão) as tirava, e o inglês ia para a tela
        // como tradução — medido no 86 ep 1 em 08/10 (A6 acusou as 2 falas no arquivo gravado).
        // Aqui o detector já compara sem o envelope de aspas, e a mesma mensagem do portão final.
        if (detectorIdentica.pareceNaoTraduzida(original, traduzido)) {
            throw new AlucinacaoDetectadaException("modelo devolveu o texto original sem tradução: \""
                + traduzido + "\"");
        }

        String trocado = trocaDeEntidade(original, traduzido);
        if (trocado != null) {
            throw new AlucinacaoDetectadaException(trocado);
        }

        String termoDoExemplo = exemploDoPromptCopiado(original, traduzido);
        if (termoDoExemplo != null) {
            throw new AlucinacaoDetectadaException("Exemplo do prompt copiado: a tradução repete um exemplo da lore e traz \""
                + termoDoExemplo + "\", que o original não tem: \"" + traduzido + "\" (original: \"" + original + "\")");
        }

        if (PADRAO_META_DE_PAR.matcher(traduzido).find()) {
            throw new AlucinacaoDetectadaException(
                "Meta-resposta sobre a tarefa de traduzir: \"" + traduzido + "\" (original: \"" + original + "\")");
        }

        // Aqui o original SEMPRE existe, então a absolvição é a regra e não a exceção. Repetir a
        // checagem torna validarPar auto-suficiente: quem chamar só este método continua coberto
        // contra as construções ambíguas, sem depender de ter passado por validarFala antes.
        RecusaAmbigua semApoio = recusaSemApoioNoOriginal(traduzido, original);
        if (semApoio != null) {
            throw new AlucinacaoDetectadaException(
                "Meta-resposta sobre a tarefa de traduzir (\"" + semApoio.rotulo()
                    + "\" sem apoio no original): \"" + traduzido + "\" (original: \"" + original + "\")");
        }

        if (temLocutorInventado(original, traduzido)) {
            throw new AlucinacaoDetectadaException(
                "Locutor/narração inventado, ausente no original: \"" + traduzido
                    + "\" (original: \"" + original + "\")");
        }

        if (polaridadeInvertida(original, traduzido)) {
            throw new AlucinacaoDetectadaException(
                "Polaridade invertida: o original responde que não e a tradução responde que sim: \""
                    + traduzido + "\" (original: \"" + original + "\")");
        }

        if (perguntaPerdida(original, traduzido)) {
            throw new AlucinacaoDetectadaException(
                "Pergunta perdida: o original pergunta e a tradução afirma: \""
                    + traduzido + "\" (original: \"" + original + "\")");
        }

        if (traduzido.length() >= COMPRIMENTO_MINIMO_PARA_DESPROPORCAO
            && original.length() <= COMPRIMENTO_MAXIMO_ORIGINAL_CURTO
            && traduzido.length() >= original.length() * FATOR_DESPROPORCAO) {
            throw new AlucinacaoDetectadaException(
                "Tradução desproporcional ao original (" + original.length() + " -> "
                    + traduzido.length() + " caracteres), conteúdo não ancorado: \"" + traduzido
                    + "\" (original: \"" + original + "\")");
        }
    }

    /**
     * PROPÓSITO DE NEGÓCIO: isola o texto EXIBIDO, sem tags ASS nem marcadores do pipeline, que
     * é o único material comparável entre original e tradução.
     *
     * <p>INVARIANTES DO DOMÍNIO: remove blocos {@code {...}} e marcadores {@code [[TAGn]]} e
     * colapsa a quebra {@code \N} em espaço; o texto recebido nunca é modificado.
     *
     * <p>COMPORTAMENTO EM CASO DE FALHA: entrada nula vira string vazia.
     */
    private String visivel(String texto) {
        if (texto == null) {
            return "";
        }
        String semTags = PADRAO_BLOCO_ASS.matcher(texto).replaceAll("");
        return PADRAO_MARCADOR.matcher(semTags).replaceAll("").replace("\\N", " ").trim();
    }

    /**
     * PROPÓSITO DE NEGÓCIO: detecta o prefixo {@code "Nome:"} que o modelo cria quando decide
     * narrar quem fala em vez de traduzir a fala.
     *
     * <p>INVARIANTES DO DOMÍNIO: exige que o original NÃO traga o mesmo recurso; poupa
     * conectivos de discurso legítimos; e só acusa quando há conteúdo após os dois-pontos ou
     * quando o próprio prefixo não aparece no original — é essa segunda condição que separa
     * {@code "Gai—" -> "Gai:"} (legítimo) de {@code "Done!" -> "Linha 1:"} (inventado).
     *
     * <p>COMPORTAMENTO EM CASO DE FALHA: qualquer texto sem dois-pontos devolve falso.
     */
    /**
     * Piso que separa oracao de rotulo de falante. TRES, medido: nenhum dos 44 rotulos do
     * historico chega a tres palavras, e nenhuma das 25 oracoes fica abaixo delas.
     */
    private static final int MINIMO_PALAVRAS_ORACAO = 3;

    /**
     * Conta SEQUENCIAS DE LETRAS, e o hifen SEPARA — "Lembre-se disso" sao tres palavras.
     *
     * <p>A primeira versao contava tokens separados por espaco e divergiu do script que fez a
     * medicao das 112 recusas, que contava sequencias de letras: "Lembre-se disso" dava 3 la e
     * 2 aqui, e a fala continuava recusada. Criterio reimplementado diverge do criterio medido
     * — foi o proprio caso-controle que pegou.
     *
     * <p>Separar no hifen e seguro para o outro lado da fronteira: nenhum dos 44 rotulos do
     * historico tem hifen ({@code Gryps 2}, {@code Shiro Amada}, {@code Linha 1}).
     */
    private static int contarPalavras(String texto) {
        if (texto == null || texto.isBlank()) {
            return 0;
        }
        int n = 0;
        boolean dentro = false;
        for (int i = 0; i < texto.length(); i++) {
            if (Character.isLetter(texto.charAt(i))) {
                if (!dentro) {
                    n++;
                    dentro = true;
                }
            } else {
                dentro = false;
            }
        }
        return n;
    }

    /**
     * Leitura de instrumento: rótulo curto, dois-pontos, e um NÚMERO do próprio original logo
     * depois. {@code "Direção: 2-8-0. Distância: 5.000."} é isso; {@code "Linha 1:"} não é.
     */
    private static final Pattern CONTEUDO_COMECA_COM_NUMERO =
        Pattern.compile("^[^\\p{L}\\p{N}]*\\p{N}");

    /** Qualquer sequência de dígitos, para conferir se algum número foi inventado. */
    private static final Pattern DIGITOS = Pattern.compile("\\p{N}+");

    /**
     * PROPÓSITO DE NEGÓCIO: separa o rótulo de LEITURA DE INSTRUMENTO — rumo, altitude,
     * distância, horário — do prefixo de locutor inventado. Os dois têm exatamente a mesma
     * forma superficial: uma ou duas palavras, dois-pontos, conteúdo depois. O que os separa é
     * que a leitura de instrumento é seguida de um NÚMERO e esse número veio do original.
     *
     * <h2>O prejuízo MEDIDO — 2026-09-14</h2>
     * Quatro ocorrências independentes, três delas fora de qualquer alteração de prompt:
     * <pre>
     * "Heading 2-8-0. Distance 5,000."  -> "Direção: 2-8-0. Distância: 5.000."   RECUSADA
     * "Heading 2-8-0. Distance 5,000."  -> "Título: 2-8-0. Distância: 5.000."    RECUSADA
     * "Altitude 4,000! The load..."     -> "Altitude atual: 4.000 metros! ..."   RECUSADA
     * horário "16:00" lido como prefixo de locutor                               RECUSADA
     * </pre>
     * Recusa aqui devolve a fala ao INGLÊS na legenda. E o dano vai além: no acervo publicado,
     * {@code "Heading 2-8-0. Distance 5,000."} saiu como {@code "Distancia 5.000."} — o rumo
     * SUMIU. A explicação que os dados sustentam é que a resposta completa foi recusada e a
     * retentativa entregou uma resposta truncada, que passou.
     *
     * <h2>Invariantes do domínio</h2>
     * <ul>
     *   <li>Exige número LOGO APÓS os dois-pontos. Narração inventada é prosa, não leitura.</li>
     *   <li>Exige que TODO número da tradução exista no original. É o que mantém
     *       {@code "Direção: 180 graus"} recusado quando o original dizia {@code 060} — número
     *       inventado continua sendo invenção, e essa resposta foi medida no modelo real.</li>
     *   <li>Não olha o significado do rótulo: não há lista de palavras para manter. Lista de
     *       palavra é frágil por construção, e este arquivo já pagou por isso.</li>
     * </ul>
     *
     * <h2>Comportamento em caso de falha</h2>
     * Devolve {@code false} — que é manter a acusação — sempre que faltar número, sobrar número
     * novo ou o conteúdo depois dos dois-pontos não começar por número. A isenção é a exceção.
     */
    private boolean ehLeituraDeInstrumento(String prefixo, String depois, String original) {
        if (depois == null || !CONTEUDO_COMECA_COM_NUMERO.matcher(depois).find()) {
            return false;
        }
        java.util.Set<String> noOriginal = gruposDeDigitos(original);
        if (noOriginal.isEmpty()) {
            return false;
        }
        java.util.Set<String> naTraducao = gruposDeDigitos(prefixo + depois);
        return !naTraducao.isEmpty() && noOriginal.containsAll(naTraducao);
    }

    /**
     * PROPÓSITO DE NEGÓCIO: extrai os grupos de dígitos de um texto ignorando o separador de
     * grupo, para que a reescrita legítima do português não pareça número novo. {@code 4000} do
     * inglês e {@code 4.000} do português são o MESMO valor, e sem esta normalização
     * {@code "Current altitude 4000!"} traduzido como {@code "Altitude atual: 4.000 metros!"}
     * era acusado de locutor inventado e voltava ao inglês na legenda — medido em 14/09/2026.
     *
     * <p>INVARIANTES DO DOMÍNIO: normalização MÍNIMA e deliberada — só o ponto e a vírgula entre
     * dígitos. O critério completo de identidade numérica (ordinal inglês, ordinal português,
     * vírgula decimal, zero à esquerda) pertence ao {@code VerificadorIdentificadorNumerico} da
     * fatia de tradução, roda no mesmo caminho e <b>não</b> é reimplementado aqui: se esta
     * isenção errar para o lado permissivo, aquela guarda ainda reprova o número inventado. É
     * por isso que a isenção pode ser conservadora sem deixar buraco.
     *
     * <p>COMPORTAMENTO EM CASO DE FALHA: texto nulo ou sem dígito devolve conjunto vazio, e
     * conjunto vazio faz a isenção não valer — a acusação permanece.
     */
    private static java.util.Set<String> gruposDeDigitos(String texto) {
        if (texto == null) {
            return java.util.Set.of();
        }
        java.util.Set<String> grupos = new java.util.HashSet<>();
        java.util.regex.Matcher m = DIGITOS.matcher(texto.replaceAll("(?<=\\p{N})[.,](?=\\p{N})", ""));
        while (m.find()) {
            grupos.add(m.group());
        }
        return grupos;
    }

    /**
     * PROPÓSITO DE NEGÓCIO: reprova a resposta com a POLARIDADE INVERTIDA — o personagem diz que
     * não e a legenda diz que sim. O português sai impecável ("Sim... é tarde demais para isso."),
     * então só o original prova o defeito, e ele inverte a cena.
     *
     * <p>INVARIANTES DO DOMÍNIO: exige as três coisas — o original ABRE com resposta negativa
     * ({@code No}/{@code Nah}/{@code Nope}/{@code Negative}, fora "no problem", "no way" e afins),
     * a tradução ABRE com afirmativa, e a tradução NÃO tem negação nenhuma ("Claro que não!" é a
     * resposta certa a "No way!"). Trabalha sobre o texto visível já sem tags.
     *
     * <p>COMPORTAMENTO EM CASO DE FALHA: nunca lança; texto vazio devolve {@code false}.
     */
    private static boolean polaridadeInvertida(String original, String traduzido) {
        return NEGATIVA_NO_INICIO_EN.matcher(original).find()
            && AFIRMATIVA_NO_INICIO_PT.matcher(traduzido).find()
            && !NEGACAO_NA_TRADUCAO.matcher(traduzido).find();
    }

    /**
     * PROPÓSITO DE NEGÓCIO: reprova a pergunta que virou afirmação — "A rat?!" publicado como
     * "Que porco colorido!", "Ensign Reccoa is?" como "Reccoa é uma tenente." (o modelo
     * RESPONDEU em vez de traduzir). É a forma mais comum de o LLM sair do papel de tradutor.
     *
     * <p>INVARIANTES DO DOMÍNIO: o original tem "?" e a tradução não tem nenhum, EXCETO quando o
     * PT legítimo pode mesmo dispensar a interrogação — pergunta retórica/imperativa, auxiliar
     * negativo retórico, suposição, "I wonder", pergunta de confirmação no fim, ou tradução que
     * já abre com interrogativo. As exclusões vieram dos falsos positivos LIDOS no acervo.
     *
     * <p>COMPORTAMENTO EM CASO DE FALHA: nunca lança; sem "?" no original devolve {@code false}.
     */
    private static boolean perguntaPerdida(String original, String traduzido) {
        if (original.indexOf('?') < 0 || traduzido.indexOf('?') >= 0) {
            return false;
        }
        return !PERGUNTA_RETORICA_EN.matcher(original).find()
            && !PERGUNTA_DE_CONFIRMACAO_EN.matcher(original).find()
            && !INTERROGATIVO_NO_INICIO_PT.matcher(traduzido).find();
    }

    private boolean temLocutorInventado(String original, String traduzido) {
        java.util.regex.Matcher m = PADRAO_PREFIXO_LOCUTOR.matcher(traduzido);
        if (!m.matches()) {
            return false;
        }
        if (PADRAO_CONECTIVO_DISCURSO.matcher(traduzido).find()) {
            return false;
        }
        // ORACAO NAO E ROTULO DE FALANTE.
        //
        // Medido em 2026-08-21 sobre TODO o historico de recusas desta regra — 112 pares
        // distintos, separados pelo numero de palavras antes dos dois-pontos:
        //
        //   3+ palavras (38 pares, 25 prefixos): SEM EXCECAO, oracao portuguesa legitima.
        //     "A questao e" · "Diga a ele" · "E ele disse" · "Ela quer dizer"
        //     "Entao voce diz" · "Eu lhes digo" · "Lembre-se disso" · "O problema real e"
        //   1-2 palavras (74 pares, 44 prefixos): SEM EXCECAO, nome de personagem ou rotulo.
        //     Al · Bell · Bright · Hestia · Kamille · Reccoa · Shiro Amada · Haman Karn
        //     "Linha 1" · "Proximo Episodio" · "Legenda traduzida" · "Haruhime disse"
        //
        // O portugues usa dois-pontos onde o ingles usa virgula ("Then you say \"...\"" ->
        // "Entao voce diz: \"...\""), e a regra lia isso como falante inventado. O efeito e o
        // pior possivel: recusada a traducao correta, a fala volta para o INGLES na legenda.
        // Foram 11 falas na retraducao de 21/08, 11 de 11 falso-positivo.
        //
        // O LIMITE DECLARADO ATE 09/10/2026: "Rygart exclamou:" tem DUAS palavras e e traducao
        // correta de "Rygart boasted,", e era recusado. Nao da para liberar pela forma —
        // "Haruhime disse:" tem a mesma forma e e invencao de verdade, nascida de "Haruhime View".
        // O que separa as duas e o verbo existir no ORIGINAL logo depois do nome: ver
        // ehDiscursoRelatadoDoOriginal, que caiu o limite depois de medido contra as 116 recusas
        // reais do console (MedicaoRecusaLocutorNoLogIT).
        if (contarPalavras(m.group(1)) >= MINIMO_PALAVRAS_ORACAO) {
            return false;
        }
        if (ehLeituraDeInstrumento(m.group(1), m.group(2), original)) {
            return false;
        }
        if (PADRAO_PREFIXO_LOCUTOR.matcher(original).matches()) {
            return false;
        }
        if (ehDiscursoRelatadoDoOriginal(m.group(1), original)) {
            return false;
        }
        if (soAcrescentouPontuacao(original, traduzido)) {
            return false;
        }
        if (ehCartaoDeTituloNumerado(m.group(1), m.group(2), original)) {
            return false;
        }
        boolean temConteudoDepois = !m.group(2).trim().isEmpty();
        return temConteudoDepois || !prefixoVemDoOriginal(m.group(1), original);
    }

    /**
     * PROPÓSITO DE NEGÓCIO: aceita a fala em que a tradução NÃO acrescentou palavra nenhuma — só
     * pontuação. {@code "Próximo Episódio Partida"} revisado como {@code "Próximo Episódio:
     * Partida"} era recusado como locutor inventado (medido nas 116 recusas reais do console, em
     * 10/10/2026) e a fala voltava ao texto anterior. Sem palavra nova, não há locutor inventado.
     *
     * <p>INVARIANTES DO DOMÍNIO: exige a MESMA sequência de palavras, na mesma ordem, depois da
     * normalização de ancoragem (sem acento, sem caixa, sem pontuação). Reordenar não conta: {@code
     * "Os Olhos de Scirocco"} virado em {@code "Scirocco: \"Olhos de Scirocco\""} tem só palavras
     * do original e INVENTA o falante — é recusado, como {@code "Sombra de Reccoa"} → {@code
     * "Reccoa: Sombra"}.
     *
     * <p>COMPORTAMENTO EM CASO DE FALHA: texto que normaliza para vazio devolve {@code false} (a
     * regra de cima decide); nunca lança.
     */
    private boolean soAcrescentouPontuacao(String original, String traduzido) {
        String o = normalizarParaAncoragem(original);
        return !o.isEmpty() && o.equals(normalizarParaAncoragem(traduzido));
    }

    /**
     * Rótulos de cartão de título, PAREADOS português → inglês. Lista fechada de propósito: rótulo
     * fora dela continua recusado (o comportamento de antes), nunca aceito por engano.
     */
    private static final java.util.Map<String, String> ROTULO_DE_TITULO_PT_EN = java.util.Map.of(
        "livro", "book", "capitulo", "chapter", "parte", "part", "episodio", "episode",
        "volume", "volume", "ato", "act", "fase", "phase", "missao", "mission");

    /** Numerais de 1 a 12, por extenso (PT e EN, já sem acento) e em romano. */
    private static final java.util.Map<String, Integer> NUMERAL_POR_EXTENSO = java.util.Map.ofEntries(
        java.util.Map.entry("um", 1), java.util.Map.entry("uma", 1), java.util.Map.entry("one", 1),
        java.util.Map.entry("dois", 2), java.util.Map.entry("duas", 2), java.util.Map.entry("two", 2),
        java.util.Map.entry("tres", 3), java.util.Map.entry("three", 3),
        java.util.Map.entry("quatro", 4), java.util.Map.entry("four", 4),
        java.util.Map.entry("cinco", 5), java.util.Map.entry("five", 5),
        java.util.Map.entry("seis", 6), java.util.Map.entry("six", 6),
        java.util.Map.entry("sete", 7), java.util.Map.entry("seven", 7),
        java.util.Map.entry("oito", 8), java.util.Map.entry("eight", 8),
        java.util.Map.entry("nove", 9), java.util.Map.entry("nine", 9),
        java.util.Map.entry("dez", 10), java.util.Map.entry("ten", 10),
        java.util.Map.entry("onze", 11), java.util.Map.entry("eleven", 11),
        java.util.Map.entry("doze", 12), java.util.Map.entry("twelve", 12),
        java.util.Map.entry("i", 1), java.util.Map.entry("ii", 2), java.util.Map.entry("iii", 3),
        java.util.Map.entry("iv", 4), java.util.Map.entry("v", 5), java.util.Map.entry("vi", 6),
        java.util.Map.entry("vii", 7), java.util.Map.entry("viii", 8), java.util.Map.entry("ix", 9),
        java.util.Map.entry("x", 10), java.util.Map.entry("xi", 11), java.util.Map.entry("xii", 12));

    /**
     * PROPÓSITO DE NEGÓCIO: aceita o CARTÃO DE TÍTULO numerado — {@code "Book One The Time of
     * Awakening"} traduzido como {@code "Livro Um: O Tempo do Despertar"}. O inglês do cartão não
     * tem pontuação entre rótulo e título; o português pede os dois-pontos, e a regra lia isso como
     * falante inventado. Medido nas 116 recusas reais do console (10/10/2026): {@code Livro Um} e
     * {@code Livro Três}, as duas voltando ao inglês na legenda.
     *
     * <p>INVARIANTES DO DOMÍNIO: exige as TRÊS âncoras no original — o rótulo inglês pareado com o
     * do prefixo como 1ª palavra, o MESMO número como 2ª, e ao menos uma palavra de título depois.
     * Por isso {@code "Episode 2"} traduzido como {@code "Episódio 2: Eu não entendo..."} continua
     * recusado (o original não tem título: a fala foi inventada), {@code "Livro Dois:"} para
     * {@code "Book One"} também, e {@code "Gryps 2: Energia crítica!"} não é cartão (o rótulo não
     * é de título).
     *
     * <p>COMPORTAMENTO EM CASO DE FALHA: qualquer âncora ausente devolve {@code false} — a acusação
     * permanece; nunca lança.
     */
    private boolean ehCartaoDeTituloNumerado(String prefixo, String depois, String original) {
        if (depois == null || depois.isBlank()) {
            return false;
        }
        String[] p = normalizarParaAncoragem(prefixo).split(" ");
        String[] o = normalizarParaAncoragem(original).split(" ");
        if (p.length != 2 || o.length < 3) {
            return false;
        }
        String rotuloEn = ROTULO_DE_TITULO_PT_EN.get(p[0]);
        Integer numero = valorDoNumeral(p[1]);
        return rotuloEn != null && rotuloEn.equals(o[0]) && numero != null
            && numero.equals(valorDoNumeral(o[1]));
    }

    /**
     * Valor de um numeral já normalizado (dígitos, extenso PT/EN ou romano); {@code null} quando a
     * palavra não é numeral — e {@code null} nunca casa com nada.
     */
    private static Integer valorDoNumeral(String palavra) {
        if (palavra.matches("\\d{1,3}")) {
            return Integer.valueOf(palavra);
        }
        return NUMERAL_POR_EXTENSO.get(palavra);
    }

    /** Prefixo de DUAS palavras: um nome e um verbo de elocução em português ("Rygart exclamou"). */
    private static final Pattern NOME_E_VERBO_DE_ELOCUCAO_PT = Pattern.compile(
        "^\\s*[\"'“]?(\\p{L}[\\p{L}'’-]*)\\s+(?:diz|disse|dizia|fala|falou|exclama|exclamou|grita|gritou|"
            + "pergunta|perguntou|responde|respondeu|sussurra|sussurrou|berra|berrou|anuncia|anunciou|"
            + "declara|declarou|conta|contou|afirma|afirmou)\\s*$",
        Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

    /** Verbos de elocução do inglês que, logo depois do MESMO nome, ancoram o discurso relatado. */
    private static final String VERBO_DE_ELOCUCAO_EN = "(?:says|said|asks|asked|boasts|boasted|exclaims|"
        + "exclaimed|shouts|shouted|yells|yelled|tells|told|replies|replied|answers|answered|whispers|"
        + "whispered|cries|cried|announces|announced|declares|declared|screams|screamed)";

    /**
     * PROPÓSITO DE NEGÓCIO: aceita o discurso relatado que o ORIGINAL já tem — {@code "Rygart
     * boasted, ..."} vira {@code "Rygart exclamou: ..."} e {@code "Chuchumy says welcome home."}
     * vira {@code "Chuchumy diz: ..."}, porque o português pede dois-pontos onde o inglês usa
     * vírgula ou nada. Recusadas, essas falas voltavam ao INGLÊS na legenda.
     *
     * <h2>A medição que autorizou (09/10/2026)</h2>
     * As 116 recusas distintas por locutor inventado do {@code console-web.log} real, passadas de
     * novo pelo validador antes e depois desta regra ({@code MedicaoRecusaLocutorNoLogIT}): a regra
     * só vira as 2 do {@code Rygart boasted} (corretas) e nenhuma invenção. Mais o caso do teste
     * ponta a ponta de 09/10 ({@code Chuchumy says welcome home.}, Reconguista I).
     *
     * <p>INVARIANTES DO DOMÍNIO: exige as DUAS âncoras — o nome do prefixo seguido, no original, de
     * um verbo de elocução inglês. {@code "Haruhime disse:"} nascido de {@code "Haruhime View"} tem
     * o nome e não tem o verbo, e continua recusado; {@code "Bell disse: ..."} nascido de
     * {@code "Where's Syr?"} não tem nem o nome.
     *
     * <p>COMPORTAMENTO EM CASO DE FALHA: prefixo fora da forma nome + verbo devolve {@code false}
     * (a regra de cima decide); nunca lança.
     */
    private static boolean ehDiscursoRelatadoDoOriginal(String prefixo, String original) {
        java.util.regex.Matcher m = NOME_E_VERBO_DE_ELOCUCAO_PT.matcher(prefixo);
        if (!m.matches()) {
            return false;
        }
        return Pattern.compile("(?<!\\p{L})" + Pattern.quote(m.group(1)) + "\\s+" + VERBO_DE_ELOCUCAO_EN
                + "(?!\\p{L})", Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE)
            .matcher(original).find();
    }

    /**
     * PROPÓSITO DE NEGÓCIO: decide se o prefixo antes dos dois-pontos foi tirado do original
     * ou inventado, comparando palavra a palavra sem acento e sem caixa.
     *
     * <p>INVARIANTES DO DOMÍNIO: TODAS as palavras do prefixo precisam existir no original como
     * palavra inteira; uma só palavra estranha basta para caracterizar invenção.
     *
     * <p>COMPORTAMENTO EM CASO DE FALHA: prefixo que normaliza para vazio conta como ancorado,
     * porque não há evidência de invenção.
     */
    private boolean prefixoVemDoOriginal(String prefixo, String original) {
        String originalCercado = " " + normalizarParaAncoragem(original) + " ";
        for (String palavra : normalizarParaAncoragem(prefixo).split("\\s+")) {
            if (!palavra.isEmpty() && !originalCercado.contains(" " + palavra + " ")) {
                return false;
            }
        }
        return true;
    }

    /**
     * PROPÓSITO DE NEGÓCIO: forma canônica de comparação entre original e tradução — sem
     * acento, sem caixa e sem pontuação, para que "Inori" e "inori," sejam a mesma palavra.
     *
     * <p>INVARIANTES DO DOMÍNIO: só normaliza para COMPARAR; nada normalizado é persistido.
     *
     * <p>COMPORTAMENTO EM CASO DE FALHA: texto nulo devolve string vazia.
     */
    private String normalizarParaAncoragem(String texto) {
        if (texto == null) {
            return "";
        }
        String semAcentos = java.text.Normalizer
            .normalize(texto, java.text.Normalizer.Form.NFD)
            .replaceAll("\\p{M}+", "");
        return semAcentos.toLowerCase(java.util.Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]+", " ").trim();
    }

    /**
     * PROPÓSITO DE NEGÓCIO: diferencia resíduo inglês visível de palavra que é
     * também nome próprio de personagem.
     * <p>INVARIANTES DO DOMÍNIO: a exceção vale somente para ocorrência
     * capitalizada cadastrada; outro token inglês na mesma fala continua
     * produzindo bloqueio, como em {@code Will transform}.
     * <p>COMPORTAMENTO EM CASO DE FALHA: ausência de correspondência retorna
     * falso; qualquer resíduo inequívoco retorna verdadeiro.
     */
    /**
     * PROPÓSITO DE NEGÓCIO: tira do texto os termos canônicos da obra ativa antes das regras
     * de resíduo em inglês, para que {@code "The O"} (mobile suit), {@code "Power of the King"}
     * e afins deixem de ser lidos como inglês não traduzido.
     *
     * <p>INVARIANTES DO DOMÍNIO: o texto RECEBIDO nunca é alterado — o resultado alimenta só a
     * inspeção, e a mensagem de erro segue citando a fala íntegra. Termos mais longos saem
     * primeiro: sem isso {@code "The O"} nunca seria removido se algum termo curto o mutilasse
     * antes. Fronteira alfanumérica e comparação sem caixa; substitui por ESPAÇO, não por vazio,
     * para não colar as palavras vizinhas e criar um resíduo que não existia.
     *
     * <p>COMPORTAMENTO EM CASO DE FALHA: a porta nunca lança e devolve conjunto vazio sem
     * contexto ativo — nesse caso o texto volta inalterado e as regras agem como antes.
     */
    /**
     * PROPÓSITO DE NEGÓCIO: acusa quando a tradução trocou uma entidade da obra por OUTRA de
     * nome parecido ou relacionado — o defeito que nenhuma regra sobre a saída consegue ver,
     * porque o termo trocado é grafia canônica perfeita.
     *
     * <h2>O que foi medido</h2>
     * <pre>
     *   Zeta   172 falas   "Four" (personagem) -> "Quatro" (numeral) e, em 15, "Quattro" (OUTRO personagem)
     *   ZZ      55 falas   "Zeta Gundam" -> "ZZ Gundam"        (mecha diferente)
     *   ZZ      31 falas   "Argama" -> "Nahel Argama"          (nave diferente)
     *   GC      20 falas   "Inori" -> "Crow"                   ("Crow" nao aparece 1x no ingles do acervo)
     * </pre>
     *
     * <h2>Por que PAR, e não "todo termo protegido"</h2>
     * A regra genérica "termo protegido no PT ausente do EN" foi medida antes de ser escrita:
     * dispara <b>1447 vezes em 59.625 falas</b>, e a esmagadora maioria é normalização legítima
     * — {@code A.E.U.G.}→{@code AEUG}, {@code mobile suit}→{@code Mobile Suit},
     * {@code Ouma Shu}→{@code Shu Ouma}, {@code Undertaker}→{@code Funeral Parlor}. Como portão
     * duro, rejeitaria tradução correta em massa. Restringir a pares declarados por quem conhece
     * a obra troca uma heurística ampla por uma afirmação verificável.
     *
     * <h2>Invariantes do domínio</h2>
     * <ul>
     *   <li>Só acusa quando o original contém UM dos dois termos e a tradução contém o OUTRO —
     *       e o original NÃO contém esse outro. Fala que menciona os dois de verdade
     *       ("the Argama and the Nahel Argama") não é tocada.</li>
     *   <li>Comparação por fronteira de palavra sobre o termo INTEIRO, sensível à caixa. É o que
     *       impede {@code "Nahel Argama"} — que contém {@code "Argama"} — de acusar a si mesma, e
     *       o que separa o nome {@code "Four"} do numeral {@code "four"}.</li>
     *   <li>Fala em que o par é simplesmente ausente não custa nada: o laço sai no primeiro teste.</li>
     * </ul>
     *
     * <p>COMPORTAMENTO EM CASO DE FALHA: devolve {@code null} quando não há troca; a porta nunca
     * lança e sem contexto ativo devolve conjunto vazio, o que desliga a checagem.
     */
    private String trocaDeEntidade(String original, String traduzido) {
        for (List<String> par : loreAtiva.paresInconfundiveisAtivos()) {
            if (par == null || par.size() != 2) {
                continue;
            }
            String a = par.get(0);
            String b = par.get(1);
            String achado = trocou(original, traduzido, a, b);
            if (achado == null) {
                achado = trocou(original, traduzido, b, a);
            }
            if (achado != null) {
                return achado;
            }
        }
        return null;
    }

    /** O original fala de {@code presente} e a tradução respondeu com {@code ausente}. */
    private static String trocou(String original, String traduzido, String presente, String ausente) {
        // PAR ANINHADO ("Argama" dentro de "Nahel Argama"): a presença de cada termo é apurada
        // com o termo MAIS LONGO mascarado antes, senão a menção à nave maior conta como menção
        // à menor e a guarda mata a detecção nas duas direções. Medido no cache do ZZ em
        // 2026-08-03: 11 falas com "Nahel Argama" traduzido como "Argama" passavam intocadas.
        if (!contemTermoIsolado(original, presente, ausente)
            || contemTermoIsolado(original, ausente, presente)
            || !contemTermoIsolado(traduzido, ausente, presente)) {
            return null;
        }
        // TROCA é o nome SUMIR. Se a tradução PRESERVOU o termo do original, o outro é acréscimo
        // — e acréscimo pode estar certo. Medido ao vivo em 2026-07-28: o inglês
        // "Sanders the Reaper" foi traduzido como "Sanders, o Ceifador", que verte o apelido sem
        // trocar a pessoa, e a fala foi RECUSADA e gravada VAZIA.
        //
        // A remoção das ocorrências de `ausente` ANTES de procurar `presente` não é preciosismo:
        // em "Argama"/"Nahel Argama" o segundo termo CONTÉM o primeiro, então
        // "Reparem a Nahel Argama" satisfaz contemTermo(.., "Argama") por fronteira de palavra.
        // Sem remover primeiro, as 31 falas medidas no ZZ parariam de ser detectadas.
        if (contemTermo(semOcorrenciasDe(traduzido, ausente), presente)) {
            return null;
        }
        return "Entidade trocada: o original diz \"" + presente + "\" e a tradução diz \""
            + ausente + "\", que é outra entidade desta obra — \"" + traduzido + "\"";
    }

    /**
     * PROPÓSITO DE NEGÓCIO: DESFAZ a troca de entidade em vez de descartar a fala. Quando o portão
     * acusa, ele já sabe as três coisas necessárias para consertar: o termo que o original usa, o
     * que a tradução colocou no lugar, e a tradução inteira. Recusar joga fora um texto que está
     * correto em tudo menos numa palavra.
     *
     * <p>Medido na retradução do Zeta em 2026-07-29: das 59 falas que ficaram vazias, <b>22 eram
     * troca de entidade</b>, e as traduções descartadas estavam impecáveis — ritmo, pontuação,
     * ênfase. Só o nome estava trocado:
     * <pre>
     *   EN "Answer me, Four!"        descartado "Responda-me, Quattro!"   -> "Responda-me, Four!"
     *   EN "Wake up, Four!"          descartado "Acorda, Quattro!"        -> "Acorda, Four!"
     *   EN "Rendezvous with Char"    descartado "Rendezvous com Quattro"  -> "Rendezvous com Char"
     * </pre>
     * Eram as falas da morte da Four Murasame, a cena mais conhecida do episódio, saindo em branco.
     *
     * <h2>Invariantes do domínio</h2>
     * <ul>
     *   <li>Só reverte o que {@link #trocou} acusaria — mesma condição, inclusive a guarda de
     *       preservação. O reparo não pode alcançar fala que o portão deixaria passar.</li>
     *   <li>Devolve o texto reparado; NÃO decide se ele é aceitável. Quem chama tem de submetê-lo
     *       ao mesmo portão de novo — reparo que entra sem revalidar é porta dos fundos.</li>
     *   <li><b>LIMITE CONHECIDO:</b> troca a palavra, não a concordância em volta. "Somente o
     *       Quattro pode abrir" vira "Somente o Four pode abrir", e Four é mulher — o artigo
     *       continua no masculino. Fica errado de gênero e certo de identidade; antes ficava
     *       vazio. Consertar o artigo exigiria saber o gênero de cada termo do par, que a lore
     *       declara em texto livre e não em campo.</li>
     * </ul>
     *
     * <p>COMPORTAMENTO EM CASO DE FALHA: sem par declarado, ou sem troca detectável, devolve
     * {@code null} e nada muda.
     */
    public String repararTrocaDeEntidade(String original, String traduzido) {
        return repararTrocaDeEntidade(original, traduzido, loreAtiva.paresInconfundiveisAtivos());
    }

    /**
     * PROPÓSITO DE NEGÓCIO: mesmo reparo, com os pares vindos por PARÂMETRO em vez da lore ativa.
     * Existe para quem corrige o cache JÁ GRAVADO: aquele fluxo percorre obra por obra e resolve o
     * contexto de cada arquivo pelo carimbo de proveniência, sem tocar na lore ativa global. Ler
     * {@code loreAtiva} ali aplicaria os pares da obra ERRADA — ou nenhum, se não houvesse obra
     * selecionada — sobre trabalho pronto, em lote.
     *
     * <p>INVARIANTES DO DOMÍNIO: a regra é a mesma da sobrecarga sem pares; só a origem da
     * declaração muda. Conjunto vazio devolve {@code null} sem examinar nada. Desfaz TODAS as
     * trocas da fala, uma por par: até 08/10/2026 parava na primeira, e a fala com duas trocas
     * ({@code "Uraki! Keith!"} vertido como {@code "Kou! Chuck!"}, medido no 0083) saía do reparo
     * ainda com a segunda, era reprovada pela revalidação e ficava pendente em inglês.
     *
     * <p>COMPORTAMENTO EM CASO DE FALHA: pares nulos são tratados como vazio; nunca lança. Sem
     * troca detectável devolve {@code null}.
     *
     * @param pares pares inconfundíveis da obra DONA daquele texto
     */
    public String repararTrocaDeEntidade(String original, String traduzido, Set<List<String>> pares) {
        if (pares == null || traduzido == null) {
            return null;
        }
        String atual = traduzido;
        for (List<String> par : pares) {
            if (par == null || par.size() != 2) {
                continue;
            }
            String reparado = reverter(original, atual, par.get(0), par.get(1));
            if (reparado == null) {
                reparado = reverter(original, atual, par.get(1), par.get(0));
            }
            if (reparado != null) {
                atual = reparado;
            }
        }
        return atual.equals(traduzido) ? null : atual;
    }

    /**
     * PROPÓSITO DE NEGÓCIO: desfaz, sem nova chamada ao modelo, os defeitos de par que o portão
     * sabe CONSERTAR por saber exatamente o que está errado — a entidade trocada e a resposta com
     * a polaridade invertida. Sem isto, o modelo que insiste no mesmo defeito nas três temperaturas
     * faz a fala sair em INGLÊS: medido na retradução dirigida de 08/10/2026, 22 falas com
     * {@code "Kou"} no lugar de {@code "Uraki"} (e {@code "Chuck"}/{@code "Lena"}) e 4 com
     * {@code "Sim..."} no lugar de {@code "No..."} terminaram assim, todas com o resto da tradução
     * correto. O reparo da consolidação final (29/07) não alcançava esses casos desde que a
     * validação de par entrou na tentativa (09/09): a tentativa esgotada já devolvia o original.
     *
     * <h2>Invariantes do domínio</h2>
     * <ul>
     *   <li>Só conserta o que o próprio portão acusaria, pela mesma condição: troca de entidade
     *       ({@link #repararTrocaDeEntidade(String, String)}), polaridade invertida
     *       ({@link #polaridadeInvertida}) e, da pergunta perdida, SÓ a pergunta-eco entre aspas.
     *       O resto da pergunta perdida não tem reparo — o mesmo sinal cobre a pergunta que só
     *       perdeu o "?" e a fala que o modelo respondeu em vez de traduzir ("A rat?!" → "Que porco
     *       colorido!"); trocar o ponto final por "?" publicaria a segunda.</li>
     *   <li>A pergunta-eco é o original que é SÓ um trecho entre aspas seguido de "?"
     *       ({@code "\"She\"?"}) e a tradução sem "?" com até {@value #MAX_PALAVRAS_PERGUNTA_ECO}
     *       palavras: o ponto final (ou nada) vira "?", com as reticências do original. Critério
     *       declarado antes da medição: nos 75.856 pares, 8 casos ("Ela." → "Ela?", "Desta vez." →
     *       "Desta vez?", "Presidente Ouma" → "Presidente Ouma...?"), os 8 certos depois do reparo.</li>
     *   <li>A polaridade só se conserta quando a tradução ABRE com a palavra "Sim" (com gagueira
     *       {@code "S-Sim"}, depois de tags e marcadores): ela vira {@code "Não"} na mesma caixa e
     *       o resto da fala fica intacto. Outras afirmativas ("Claro", "Certo") seguem
     *       reprovadas. Critério declarado antes da medição: nos 75.856 pares distintos dos
     *       caches do acervo, 5 polaridades invertidas, as 5 abrindo com "Sim", as 5 corretas
     *       depois do reparo (lidas uma a uma).</li>
     *   <li>Devolve o texto; NÃO decide se ele é aceitável. Quem chama submete o reparado ao
     *       MESMO portão — reparo que entra sem revalidar é porta dos fundos.</li>
     * </ul>
     *
     * <p>COMPORTAMENTO EM CASO DE FALHA: nada a reparar (ou entrada nula) devolve {@code null};
     * nunca lança.
     */
    public String repararPar(String original, String traduzido) {
        if (original == null || traduzido == null) {
            return null;
        }
        String atual = traduzido;
        String semTroca = repararTrocaDeEntidade(original, atual);
        if (semTroca != null) {
            atual = semTroca;
        }
        if (polaridadeInvertida(visivel(original), visivel(atual))) {
            Matcher m = SIM_NA_ABERTURA.matcher(atual);
            if (m.find()) {
                String gagueira = m.group(2) == null ? "" : comCaixaDe(m.group(2), "n") + "-";
                atual = m.group(1) + gagueira + comCaixaDe(m.group(3), "não") + atual.substring(m.end());
            }
        }
        String originalVisivel = visivel(original);
        String atualVisivel = visivel(atual);
        Matcher eco = PERGUNTA_ECO_ENTRE_ASPAS.matcher(originalVisivel);
        if (eco.matches() && perguntaPerdida(originalVisivel, atualVisivel)
            && atualVisivel.split("\\s+").length <= MAX_PALAVRAS_PERGUNTA_ECO) {
            Matcher fim = FIM_DA_FALA.matcher(atual);
            if (fim.find()) {
                String reticencias = eco.group(1) == null ? "" : "...";
                atual = atual.substring(0, fim.start()) + reticencias + "?" + fim.group(1);
            }
        }
        return atual.equals(traduzido) ? null : atual;
    }

    /**
     * PROPÓSITO DE NEGÓCIO: reprova a fala em que o modelo devolveu um EXEMPLO do prompt da obra no lugar
     * de traduzir. O prompt do Zeta e do ZZ ensina a grafia do Psyco Gundam com frases inteiras
     * ({@code "As you wish, but I'll entrust the Psyco Gundam to you." fica "Como quiser, mas vou
     * confiar o Psyco Gundam a você."}), e o modelo as copia para falas sem relação: medido nos caches
     * em 08/10/2026, "All right, do as you wish." publicada como "Como quiser, mas vou confiar o Psyco
     * Gundam a você.", "...through the armor of a mobile suit, like a Newtype?" como "Você precisa sair
     * do cockpit do Psyco Gundam! Rápido!" e "G3?!", "Catl?", "Z-G...?" como "Psyco Gundam?".
     *
     * <h2>Invariantes do domínio</h2>
     * <ul>
     *   <li>Exige as três coisas: a fala é igual (sem tags, caixa, espaço e pontuação final) ao PT de
     *       um exemplo {@code "EN" fica/vira/-> "PT"} do prompt ATIVO; o original NÃO é o EN daquele
     *       exemplo; e a fala traz um termo protegido da obra que o original não tem. A terceira é a
     *       que separa vazamento de coincidência: "Não aguento mais isso!" para "I can't take this
     *       anymore!" repete um exemplo e está certa.</li>
     *   <li>Critério declarado e medido antes do código, nos 130.817 pares dos caches com o prompt de
     *       cada obra: 26 falas iguais a exemplo com outro original; a regra reprova 8, as 8 defeito
     *       real, e deixa passar 18 — 16 legítimas e 2 falsos negativos declarados: "A Gundam?!" →
     *       "Psyco Gundam?" (o original já diz Gundam) e "Open up, Four!" → "Abra os olhos, Four!".</li>
     *   <li>Sem conserto determinístico: o conteúdo foi inventado. A reprovação vira outra tentativa;
     *       esgotada, a fala sai pendente.</li>
     * </ul>
     *
     * <p>COMPORTAMENTO EM CASO DE FALHA: sem prompt ativo, sem exemplo igual ou sem termo estranho
     * devolve {@code null}; nunca lança. Devolve o termo que o original não tem.
     */
    private String exemploDoPromptCopiado(String original, String traduzido) {
        // Como a fala SERÁ PUBLICADA: o normalizador de aspas tira, depois do portão, o envelope que
        // o original não tem. Sem isto '"Psyco Gundam?"' passava aqui e saía "Psyco Gundam?".
        String publicada = DetectorTraducaoIdenticaService.semEnvelopeDeAspasQueOOriginalNaoTem(original, traduzido);
        Set<String> ensDoExemplo = exemplosDoPrompt().get(normalizarExemplo(publicada));
        if (ensDoExemplo == null || ensDoExemplo.contains(normalizarExemplo(original))) {
            return null;
        }
        for (String termo : loreAtiva.termosProtegidosAtivos()) {
            if (contemTermoIgnorandoCaixa(publicada, termo) && !contemTermoIgnorandoCaixa(original, termo)) {
                return termo;
            }
        }
        return null;
    }

    /** Os exemplos do prompt ativo (PT normalizado → ENs normalizados), recalculados só quando o prompt muda. */
    private java.util.Map<String, Set<String>> exemplosDoPrompt() {
        String prompt = loreAtiva.obterLoreAtiva();
        if (prompt == null || prompt.isBlank()) {
            return java.util.Map.of();
        }
        ExemplosDoPrompt guardados = exemplosEmMemoria;
        if (guardados != null && guardados.prompt().equals(prompt)) {
            return guardados.ensPorPt();
        }
        java.util.Map<String, Set<String>> ensPorPt = new java.util.HashMap<>();
        Matcher m = EXEMPLO_DO_PROMPT.matcher(prompt);
        while (m.find()) {
            String pt = normalizarExemplo(m.group(2));
            if (!pt.isEmpty()) {
                ensPorPt.computeIfAbsent(pt, k -> new java.util.HashSet<>()).add(normalizarExemplo(m.group(1)));
            }
        }
        java.util.Map<String, Set<String>> imutavel = new java.util.HashMap<>();
        ensPorPt.forEach((pt, ens) -> imutavel.put(pt, Set.copyOf(ens)));
        exemplosEmMemoria = new ExemplosDoPrompt(prompt, java.util.Map.copyOf(imutavel));
        return exemplosEmMemoria.ensPorPt();
    }

    /** Sem tags, sem {@code \N}, caixa baixa, espaço colapsado e sem a pontuação final. */
    private static String normalizarExemplo(String texto) {
        if (texto == null) {
            return "";
        }
        String semTags = PADRAO_MARCADOR.matcher(PADRAO_BLOCO_ASS.matcher(texto).replaceAll("")).replaceAll("")
            .replace("\\N", " ");
        return semTags.replaceAll("\\s+", " ").strip().toLowerCase(java.util.Locale.ROOT)
            .replaceAll("[\\s.!?…]+$", "");
    }

    /** Par de palavras separadas por barra, sem espaço: "cansada/o", "conhecê-lo/a", "ele/ela", "amigo/inimigo". */
    private static final Pattern PALAVRAS_COM_BARRA = Pattern.compile("(\\p{L}+)/(\\p{L}+)");

    /** Terminação de gênero ou número que vem sozinha depois da barra: "designado/a", "cansada/o", "-lo/a". */
    private static final Set<String> TERMINACAO_ALTERNATIVA = Set.of("o", "a", "os", "as");

    /** Pronomes e artigos que se alternam por gênero inteiros: "ele/ela", "o/a", "dele/dela". */
    private static final Set<String> PALAVRAS_DE_GENERO = Set.of(
        "o", "a", "os", "as", "ele", "ela", "eles", "elas", "dele", "dela", "deles", "delas",
        "um", "uma", "uns", "umas", "seu", "sua", "seus", "suas", "meu", "minha", "meus", "minhas");

    /**
     * PROPÓSITO DE NEGÓCIO: acusa a alternativa ou a nota que a tradução INVENTOU — o que o prompt
     * proíbe com todas as letras: "nunca ofereça duas alternativas de gênero na mesma palavra, nem com
     * barra nem com terminação entre parênteses: legenda não tem nota editorial". Medido nos caches do
     * acervo em 08/10/2026:
     * <ul>
     *   <li>PARÊNTESE: 95 traduções distintas com parêntese que o original não tem, e praticamente
     *       todas defeito — a alternativa de gênero ("cansado(a)", "bem-vindo(a)", "Bomba(s)", ~70) e
     *       a glosa ou nota do modelo ("(com tom sarcástico)", "Móbil Space (ou Mecha)", "A pursuer?!
     *       (Perseguidor?!)", "G3?! (G3?!)", ~25). Discutível, um: "O Sistema Destruidor Newtype
     *       (NT-D)".</li>
     *   <li>BARRA: 10 traduções distintas com barra entre letras que o original não tem; 9 são a
     *       alternativa de gênero ("Estou só cansada/o.", "Prazer em conhecê-lo/a.", "Eu fui tão
     *       estúpido/estupida.", "...para ele/ela.", "Querido/a") e 1 é legítima ("identificação
     *       amigo/inimigo" para IFF). A regra acusa as 9 e deixa passar a legítima. Foi a mesma
     *       medição que ajustou a regra — não há amostra separada; limitação declarada.</li>
     * </ul>
     * A barra entrou depois de o parêntese ser acusado: na retentativa o modelo trocava
     * "estranho(a)" por "estranho/a", o mesmo defeito com outra grafia.
     *
     * <h2>Invariantes do domínio</h2>
     * <ul>
     *   <li>Compara o texto VISÍVEL: parêntese em tag ASS não conta; parêntese ou barra que o
     *       original já tinha não é invenção.</li>
     *   <li>A barra só acusa ALTERNATIVA: terminação o/a/os/as sozinha depois dela, pronome ou artigo
     *       dos dois lados, ou a mesma palavra trocando só o/a no fim (acento ignorado). "amigo/inimigo",
     *       "km/h" e "e/ou" passam.</li>
     *   <li>NÃO entra em {@link #validarPar}: não é reprovação definitiva. Quem chama pede outra
     *       tentativa ao modelo e, na última, aceita — publicar "cansado(a)" é melhor que publicar o
     *       inglês, e não há conserto determinístico seguro (escolher o gênero masculino é o fallback
     *       que a regra de concordância proíbe).</li>
     * </ul>
     *
     * <p>COMPORTAMENTO EM CASO DE FALHA: argumento nulo ou sem alternativa inventada devolve
     * {@code null}; nunca lança. Devolve a descrição do defeito, para log e para a reprovação.
     */
    public String alternativaInventada(String original, String traduzido) {
        if (original == null || traduzido == null) {
            return null;
        }
        String o = visivel(original);
        String t = visivel(traduzido);
        if (t.indexOf('(') >= 0 && o.indexOf('(') < 0) {
            return "Parêntese que o original não tem: \"" + t + "\" (original: \"" + o + "\")";
        }
        if (o.indexOf('/') < 0) {
            Matcher m = PALAVRAS_COM_BARRA.matcher(t);
            while (m.find()) {
                if (ehAlternativaDeGenero(m.group(1), m.group(2))) {
                    return "Alternativa de gênero com barra que o original não tem: \"" + t
                        + "\" (original: \"" + o + "\")";
                }
            }
        }
        return null;
    }

    /** "cansada" + "o", "ele" + "ela", "estúpido" + "estupida": as duas formas de gênero da mesma palavra. */
    private static boolean ehAlternativaDeGenero(String antes, String depois) {
        String a = semAcento(antes).toLowerCase(java.util.Locale.ROOT);
        String d = semAcento(depois).toLowerCase(java.util.Locale.ROOT);
        if (TERMINACAO_ALTERNATIVA.contains(d) && (a.endsWith("o") || a.endsWith("a")
                || a.endsWith("os") || a.endsWith("as"))) {
            return true;
        }
        if (PALAVRAS_DE_GENERO.contains(a) && PALAVRAS_DE_GENERO.contains(d) && !a.equals(d)) {
            return true;
        }
        return a.length() >= 3 && a.length() == d.length()
            && a.substring(0, a.length() - 1).equals(d.substring(0, d.length() - 1))
            && "oa".indexOf(a.charAt(a.length() - 1)) >= 0 && "oa".indexOf(d.charAt(d.length() - 1)) >= 0
            && a.charAt(a.length() - 1) != d.charAt(d.length() - 1);
    }

    private static String semAcento(String texto) {
        return java.text.Normalizer.normalize(texto, java.text.Normalizer.Form.NFD).replaceAll("\\p{M}", "");
    }

    /** Devolve {@code base} com a caixa de {@code achado}: "SIM" → "NÃO", "Sim" → "Não", "sim" → "não". */
    private static String comCaixaDe(String achado, String base) {
        if (achado.length() > 1 && achado.chars().allMatch(Character::isUpperCase)) {
            return base.toUpperCase(java.util.Locale.ROOT);
        }
        if (Character.isUpperCase(achado.charAt(0))) {
            return Character.toUpperCase(base.charAt(0)) + base.substring(1);
        }
        return base;
    }

    /** Devolve a tradução com {@code ausente} trocado por {@code presente}, ou null se não é troca. */
    private static String reverter(String original, String traduzido, String presente, String ausente) {
        if (trocou(original, traduzido, presente, ausente) == null) {
            return null;
        }
        return padraoTermo(ausente, false)
            .matcher(traduzido).replaceAll(r -> Matcher.quoteReplacement(
                FronteiraTermoAss.substituirPreservandoQuebra(r.group(), presente)));
    }

    /**
     * PROPÓSITO DE NEGÓCIO: o texto menciona {@code termo} como ENTIDADE PRÓPRIA, e não apenas
     * como pedaço do outro termo do par?
     *
     * <p>INVARIANTES DO DOMÍNIO: quando {@code outro} CONTÉM {@code termo} ({@code "Nahel Argama"}
     * contém {@code "Argama"}), as ocorrências do mais longo são mascaradas antes da busca —
     * casamento mais longo primeiro. Sem isso, "Reparem a Nahel Argama" contaria como menção a
     * "Argama" por fronteira de palavra, e o par aninhado se acusaria sozinho. Quando os termos
     * são disjuntos ({@code "Zeta Gundam"}/{@code "ZZ Gundam"}) nada é mascarado e a checagem é a
     * de sempre.
     *
     * <p>COMPORTAMENTO EM CASO DE FALHA: argumentos nulos degradam para {@link #contemTermo}.
     */
    private static boolean contemTermoIsolado(String texto, String termo, String outro) {
        String base = texto;
        if (outro != null && termo != null && !outro.equals(termo) && contemTermo(outro, termo)) {
            base = semOcorrenciasDe(texto, outro);
        }
        // CAIXA NAO MUDA A ENTIDADE, e por isso a troca de nome se apura ignorando-a.
        //
        // A comparacao sensivel a caixa existe para separar o NOME "Four" do NUMERAL "four", e
        // continua valendo em toda regra que julga termo protegido. Mas para a troca de ENTIDADE
        // ela criava ponto cego: cartao de nome no ASS e escrito em CAIXA ALTA pelo typesetter, e
        // MEDIDO no cache do ZZ em 2026-09-09 o cartao "QUATTRO BAJEENA" foi publicado como
        // "Four Murasame." -- duas personagens diferentes, no letreiro que ANUNCIA quem e quem.
        // Com a comparacao sensivel a caixa, "QUATTRO" nao casava "Quattro" e a guarda passava.
        //
        // Nao afrouxa o par Four x numeral: numeral nao e entidade e nao entra em par nenhum.
        return contemTermoIgnorandoCaixa(base, termo);
    }

    /** Fronteira de palavra sobre o termo inteiro, IGNORANDO a caixa — só para troca de entidade. */
    private static boolean contemTermoIgnorandoCaixa(String texto, String termo) {
        return texto != null && termo != null && !termo.isBlank()
            && padraoTermo(termo, true).matcher(texto).find();
    }

    /** Remove as ocorrências do termo por fronteira de palavra, preservando o resto do texto. */
    private static String semOcorrenciasDe(String texto, String termo) {
        if (texto == null || termo == null || termo.isBlank()) {
            return texto == null ? "" : texto;
        }
        return padraoTermo(termo, false).matcher(texto).replaceAll(" ");
    }

    /** Fronteira de palavra sobre o termo inteiro, sensível à caixa (separa "Four" de "four"). */
    private static boolean contemTermo(String texto, String termo) {
        return texto != null && termo != null && !termo.isBlank()
            && padraoTermo(termo, false).matcher(texto).find();
    }

    /**
     * Padrões de termo já compilados, por termo e caixa. O validador roda várias vezes por
     * tentativa e de novo no portão final, e cada chamada recompilava um {@link Pattern} por termo
     * da lore (auditoria de 28/10/2026). Os termos são limitados pelas lores, então o mapa não
     * cresce sem fim; o padrão é o mesmo de antes, caractere a caractere.
     */
    private static final java.util.concurrent.ConcurrentHashMap<String, Pattern> PADROES_DE_TERMO =
        new java.util.concurrent.ConcurrentHashMap<>();

    private static Pattern padraoTermo(String termo, boolean ignorarCaixa) {
        return PADROES_DE_TERMO.computeIfAbsent((ignorarCaixa ? "i\u0000" : "s\u0000") + termo,
            chave -> ignorarCaixa
                ? Pattern.compile(INICIO_DE_TERMO + FronteiraTermoAss.corpo(termo) + "(?![\\p{L}\\p{N}])",
                    Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE)
                : Pattern.compile(INICIO_DE_TERMO + FronteiraTermoAss.corpo(termo) + "(?![\\p{L}\\p{N}])"));
    }

    /**
     * PROPÓSITO DE NEGÓCIO: decide se uma construção ambígua presente na tradução é meta-resposta
     * do modelo ou fala legítima, procurando no ORIGINAL a âncora que a sustentaria.
     *
     * <p>INVARIANTES DO DOMÍNIO: original ausente equivale a ausência de licença — a regra
     * bloqueia, como bloqueava antes de existir esta absolvição. A primeira construção sem apoio
     * encerra a busca: o diagnóstico nomeia UMA causa, não uma lista.
     *
     * <p>COMPORTAMENTO EM CASO DE FALHA: não lança; devolve {@code null} quando nada casa ou
     * quando tudo que casou está licenciado pelo original.
     *
     * @param traduzido texto visível da tradução, já sem blocos ASS
     * @param original  texto de partida, ou {@code null}/vazio quando o chamador não o tem
     * @return a construção ambígua não sustentada pelo original, ou {@code null}
     */
    private static RecusaAmbigua recusaSemApoioNoOriginal(String traduzido, String original) {
        boolean temOriginal = original != null && !original.isBlank();
        for (RecusaAmbigua candidata : RECUSAS_AMBIGUAS) {
            if (!candidata.emPortugues().matcher(traduzido).find()) {
                continue;
            }
            if (temOriginal && candidata.ancoraNoOriginal().matcher(original).find()) {
                continue;
            }
            return candidata;
        }
        return null;
    }

    private String removerTermosProtegidos(String texto) {
        Set<String> termos = loreAtiva.termosProtegidosAtivos();
        if (termos == null || termos.isEmpty()) {
            return texto;
        }
        List<String> ordenados = termos.stream()
            .filter(t -> t != null && !t.isBlank())
            .sorted(Comparator.comparingInt(String::length).reversed())
            .toList();
        String resultado = texto;
        for (String termo : ordenados) {
            Matcher matcher = padraoTermo(termo, true).matcher(resultado);
            if (matcher.find()) {
                resultado = matcher.reset().replaceAll(" ");
            }
        }
        return resultado;
    }

    private boolean temResiduoRelevante(String texto) {
        var matcher = PADRAO_RESIDUO.matcher(texto);
        while (matcher.find()) {
            String palavra = matcher.group(1);
            boolean capitalizada = palavra.length() > 1
                && Character.isUpperCase(palavra.charAt(0))
                && !palavra.equals(palavra.toUpperCase());
            boolean nomeProprioProvavel = capitalizada
                && RESIDUOS_TAMBEM_NOMES.contains(palavra.toLowerCase());
            if (!nomeProprioProvavel) {
                return true;
            }
        }
        return false;
    }
}
