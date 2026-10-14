package org.traducao.projeto.qualidadeTraducao.application;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * PROPÓSITO DE NEGÓCIO: fixa o contrato do {@link NormalizadorAcentosComuns} — repor acento só
 * nas formas que sem ele NUNCA são palavra válida, sem tocar homógrafos nem sentido.
 *
 * <p>INVARIANTES DO DOMÍNIO: só o dicionário curado; fronteira de palavra; caixa preservada;
 * homógrafos ({@code esta}, {@code e}, {@code as vezes}) intocados.
 *
 * <p>COMPORTAMENTO EM CASO DE FALHA: qualquer correção indevida ou faltante reprova.
 */
class NormalizadorAcentosComunsTest {

    private final NormalizadorAcentosComuns norm = new NormalizadorAcentosComuns();

    @Test
    @DisplayName("repõe acento em formas inequívocas (nao/voce/tambem/ate)")
    void corrigeFormasInequivocas() {
        assertEquals("Não, você também vem até aqui?",
            norm.normalizar("Nao, voce tambem vem ate aqui?"));
    }

    @Test
    @DisplayName("repõe infância (caso do 08th)")
    void corrigeInfancia() {
        assertEquals("Desde a infância, tantos dias.",
            norm.normalizar("Desde a infancia, tantos dias."));
    }

    @Test
    @DisplayName("preserva a caixa do achado (primeira maiúscula e tudo maiúsculo)")
    void preservaCaixa() {
        assertEquals("Ninguém! NÃO!", norm.normalizar("Ninguem! NAO!"));
    }

    @Test
    @DisplayName("distingue voce de voces (mais longa primeiro)")
    void distingueVoceDeVoces() {
        assertEquals("vocês e você", norm.normalizar("voces e voce"));
    }

    @Test
    @DisplayName("NÃO toca homógrafos nem palavras válidas (esta/e/as vezes)")
    void naoTocaHomografos() {
        // 'esta'(this), 'e'(and), 'as vezes'(the times) são ambíguos: ficam intocados.
        String s = "esta e as vezes que lutei";
        assertEquals("esta e as vezes que lutei", norm.normalizar(s));
    }

    @Test
    @DisplayName("NÃO casa fragmento dentro de palavra (ate em Kate/mate)")
    void naoCasaFragmento() {
        assertEquals("Kate comeu mate", norm.normalizar("Kate comeu mate"));
    }

    @Test
    @DisplayName("preserva tags de estilo ao corrigir")
    void preservaTags() {
        assertEquals("{\\i1}Não vou além.", norm.normalizar("{\\i1}Nao vou alem."));
    }

    /**
     * PROPÓSITO DE NEGÓCIO: as formas MEDIDAS no Gundam Unicorn em 12/08/2026, que motivaram a
     * ampliação para fora da família {@code -ão/-ção}.
     *
     * <p>A medição comparou as três traduções da MESMA obra: mistral 23 de 5.676 falas (0,4%)
     * contra aya 197 de 5.458 (3,6%) — 9× mais. As mais frequentes: {@code familia} 22,
     * {@code proxima} 21, {@code unica} 11, {@code federacao} 10, {@code ultima} 9. A frase do
     * primeiro caso é a real do E02, onde o mistral escreveu "circunstâncias" e a aya não.
     */
    @Test
    @DisplayName("as formas medidas no Unicorn: proparoxítonas e -ção fora da lista anterior")
    void formasMedidasNoUnicorn() {
        assertEquals("falando com você nessas circunstâncias.",
            norm.normalizar("falando com você nessas circunstancias."));
        assertEquals("A família dela é a única que resta.",
            norm.normalizar("A familia dela é a unica que resta."));
        assertEquals("Na próxima vez, a última chance.",
            norm.normalizar("Na proxima vez, a ultima chance."));
        assertEquals("A Federação decretou a maldição.",
            norm.normalizar("A Federacao decretou a maldicao."));
        assertEquals("Não é possível, é muito difícil.",
            norm.normalizar("Não é possivel, é muito dificil."));
    }

    /**
     * PROPÓSITO DE NEGÓCIO: prova que a ampliação de 12/08 NÃO trouxe as formas que também são
     * verbo. Cada palavra abaixo tem grafia sem acento perfeitamente válida, e trocá-la
     * introduziria erro onde não havia — o oposto do que este normalizador existe para fazer.
     *
     * <p>Guarda que estraga texto correto é pior que guarda nenhuma, e aqui o dano seria
     * silencioso: "ele publica o relatório" virando "ele pública o relatório" passa por
     * qualquer validador.
     */
    @Test
    @DisplayName("a ampliação NÃO toca palavra que também é verbo")
    void naoTocaFormaQueTambemEhVerbo() {
        String s = "Ele publica e medica, critica a pratica, duvida e continua na fabrica.";
        assertEquals(s, norm.normalizar(s));
    }

    /**
     * PROPÓSITO DE NEGÓCIO: as seis formas MEDIDAS na auditoria de 07/08/2026.
     *
     * <p>Auditadas 10.918 falas das duas obras traduzidas na noite de 06/08
     * (Gundam 0083 Stardust Memory e Gundam 08th MS Team): 74 falas saíram sem
     * acento, dominadas por {@code capitao} (35) e {@code entao} (26). As frases
     * abaixo são as reais, não inventadas.
     */
    @Test
    @DisplayName("as seis formas medidas na auditoria das duas obras Gundam")
    void formasMedidasNaAuditoria() {
        assertEquals("sem permissão, Capitão Sinapus",
            norm.normalizar("sem permissão, Capitao Sinapus"));
        assertEquals("Então você pode me contatar.",
            norm.normalizar("Entao você pode me contatar."));
        assertEquals("Os arquivos estão todos prontos.",
            norm.normalizar("Os arquivos estao todos prontos."));
        assertEquals("Sim, você tem razão.", norm.normalizar("Sim, você tem razao."));
        assertEquals("Frente de baixa pressão", norm.normalizar("Frente de baixa pressao"));
        assertEquals("companheiros de esquadrão",
            norm.normalizar("companheiros de esquadrão"));
    }

    /**
     * PROPÓSITO DE NEGÓCIO: a forma colada numa quebra {@code \N} do ASS é a que
     * mais escapa de qualquer conferência ingênua — o {@code N} é letra, então
     * uma fronteira com {@code \b} conclui que o termo é sufixo de outra palavra
     * e não o enxerga.
     *
     * <p>Foi exatamente assim que a minha primeira medição achou 3 defeitos onde
     * havia 5: os dois {@code estao} estavam colados numa quebra.
     */
    @Test
    @DisplayName("corrige a forma colada na quebra \\N do ASS")
    void corrigeColadoNaQuebra() {
        assertEquals("O Unicorn e o Banshee\\Nestão se puxando!",
            norm.normalizar("O Unicorn e o Banshee\\Nestao se puxando!"));
        assertEquals("A missão falhou.\\NEntão recuem.",
            norm.normalizar("A missao falhou.\\NEntao recuem."));
    }

    /**
     * PROPÓSITO DE NEGÓCIO: o lado oposto da quebra colada — a letra {@code N} da PRÓPRIA quebra
     * não pode virar começo de palavra. Em 14/10/2026 a Reconguista I saiu com "ão controle" na
     * tela: a revisão escreveu "informação\Nao controle", e o normalizador leu "Nao" a partir do N.
     */
    @Test
    @DisplayName("\\N seguido de 'ao' continua 'ao' (nao vira \\Não)")
    void quebraSeguidaDeAoNaoViraNao() {
        assertEquals("Podem comunicar essa informação\\Nao controle da Capital Tower.",
            norm.normalizar("Podem comunicar essa informação\\Nao controle da Capital Tower."));
        assertEquals("Fiquei tão animada\\Nao ver o G-Self,",
            norm.normalizar("Fiquei tão animada\\Nao ver o G-Self,"));
        assertEquals("Voz,\\Nnão vai ganhar.", norm.normalizar("Voz,\\Nnao vai ganhar."),
            "FRONTEIRA: o 'nao' de verdade depois da quebra continua acentuado");
    }

    /** A família -ção acompanha, com singular e plural. */
    @Test
    @DisplayName("familia -ao/-cao, no singular e no plural")
    void familiaTerminacao() {
        assertEquals("missão / missões", norm.normalizar("missao / missoes"));
        assertEquals("posição / posições", norm.normalizar("posicao / posicoes"));
        assertEquals("informação / informações", norm.normalizar("informacao / informacoes"));
        assertEquals("irmão / irmãos", norm.normalizar("irmao / irmaos"));
        assertEquals("três", norm.normalizar("tres"));
        assertEquals("mãe", norm.normalizar("mae"));
    }

    /**
     * PROPÓSITO DE NEGÓCIO: CONTRA-TESTE da ampliação. A invariante do mapa é que
     * a forma sem acento NUNCA seja palavra válida — e estas são.
     *
     * <p>{@code sera} ficou de fora de propósito apesar de ter aparecido na
     * auditoria: pode ser nome próprio, e uma ocorrência não paga o risco de
     * estragar um nome. {@code pai}, {@code ideia}, {@code apoio} e
     * {@code tenente} não levam acento nenhum — foram 301 falsos positivos na
     * minha primeira medição, e entrar com eles no mapa corromperia o texto.
     */
    /**
     * PROPÓSITO DE NEGÓCIO: o "que" tônico (08/10/2026) — 579 "O que?!" e 128 "Por que?" medidos
     * no acervo pelo LanguageTool de produção. Os casos RUIM são do acervo; os de controle carregam
     * o MESMO "que" e não mudam (A1).
     */
    @Test
    @DisplayName("que tonico antes de ? ou ! ganha circunflexo; o que nao e tonico fica")
    void queTonicoAntesDePerguntaGanhaCircunflexo() {
        assertEquals("O quê?!", norm.normalizar("O que?!"));
        assertEquals("Por quê?", norm.normalizar("Por que?"));
        assertEquals("Agora o quê?", norm.normalizar("Agora o que?"));
        assertEquals("Quê?!", norm.normalizar("Que?!"));
        assertEquals("QUÊ?!", norm.normalizar("QUE?!"));
        assertEquals("E agora, o quê...?", norm.normalizar("E agora, o que...?"));
        assertEquals("{\\i1}O quê{\\i0}?", norm.normalizar("{\\i1}O que{\\i0}?"));
        assertEquals("Sei lá o quê!", norm.normalizar("Sei lá o que!"));
        // controles: mesmo "que", não tônico — não muda
        assertEquals("Por que não?", norm.normalizar("Por que não?"));
        assertEquals("O que é isso?", norm.normalizar("O que é isso?"));
        assertEquals("Eu sei que você vem.", norm.normalizar("Eu sei que você vem."));
        assertEquals("porque?", norm.normalizar("porque?"));
        assertEquals("Porque sim!", norm.normalizar("Porque sim!"));
        assertEquals("Porquê?", norm.normalizar("Porquê?"));
    }

    @Test
    @DisplayName("A1 do 'quê': a mesma pontuação depois de conjunção ou 'ter que' cortados não acentua")
    void queConjuncaoCortadaNaoGanhaCircunflexo() {
        // os 6 casos reais do acervo (08/10/2026) que a regra sem a palavra anterior estragaria
        assertEquals("Tenho que! Tenho que!", norm.normalizar("Tenho que! Tenho que!"));
        assertEquals("Nós temos que!", norm.normalizar("Nós temos que!"));
        assertEquals("Quando foi que...?!", norm.normalizar("Quando foi que...?!"));
        assertEquals("O que espera aqueles que...!", norm.normalizar("O que espera aqueles que...!"));
        assertEquals("Por que você sempre tem que...?!", norm.normalizar("Por que você sempre tem que...?!"));
        // a mesma fala com o pronome E a conjunção: só o pronome muda
        assertEquals("O quê? Você quer dizer que...!", norm.normalizar("O que? Você quer dizer que...!"));
        // o pronome continua acentuado depois de "para"/"pra", abrindo frase após pontuação e após \N
        assertEquals("Para quê?", norm.normalizar("Para que?"));
        assertEquals("Pra quê?!", norm.normalizar("Pra que?!"));
        assertEquals("Oh? Quê?", norm.normalizar("Oh? Que?"));
        assertEquals("- Quê?!", norm.normalizar("- Que?!"));
        assertEquals("E agora?\\NO quê?", norm.normalizar("E agora?\\NO que?"));
        assertEquals("Ele disse o\\Nquê?", norm.normalizar("Ele disse o\\Nque?"));
    }

    @Test
    @DisplayName("NAO toca palavra que ja esta certa nem nome proprio")
    void naoTocaPalavraCorreta() {
        String s = "O tenente Sera teve a ideia com apoio do pai";
        assertEquals(s, norm.normalizar(s));
        assertEquals("Em breve sera realidade.", norm.normalizar("Em breve sera realidade."));
    }
}
