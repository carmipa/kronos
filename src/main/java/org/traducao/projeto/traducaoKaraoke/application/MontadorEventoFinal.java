package org.traducao.projeto.traducaoKaraoke.application;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.traducao.projeto.core.texto.TextoSemTags;
import org.traducao.projeto.core.texto.dicionarioOrtografia.CorretorOrtograficoLegenda;
import org.traducao.projeto.legenda.domain.EventoLegenda;
import org.traducao.projeto.traducaoKaraoke.domain.AcentosLetraKaraoke;

/**
 * PROPOSITO DE NEGOCIO: monta o texto que vai PARA O ARQUIVO depois que a traducao volta do LLM
 * — repoe o acento e, quando preciso, empilha a letra original em cima com a quebra do ASS.
 *
 * <h2>A ORDEM entre as duas coisas e a invariante mais cara desta fatia</h2>
 * O acento e reposto SOMENTE sobre o texto traduzido, e a linha original e anexada DEPOIS.
 * Inverter isso devolve o defeito do Unicorn: o corretor virou {@code mae} em {@code mae com
 * til} <b>100 vezes nos 50 episodios</b>, e ali {@code mae} era o japones 前 — romaji, nao a
 * palavra portuguesa. Em karaoke o risco nao e o dicionario estar desligado: e ele agir DEMAIS,
 * sobre a camada que existe justamente para ser preservada.
 * {@code CorretorNaoAlcancaRomajiDoKaraokeTest} congela essa ordem.
 *
 * <h2>Por que empilhar, e por que nao um segundo evento</h2>
 * Dois eventos no MESMO instante desenham um por cima do outro, a menos que se mexa em
 * {@code MarginV} ou {@code pos} — e mexer em posicao de karaoke e como o timing se perde. A
 * quebra resolve dentro da propria linha. O prejuizo que obriga: nos episodios 13-22 do Unicorn
 * o encerramento tem UMA camada so, e traduzir sem empilhar apagava a letra da tela — 22 de 23
 * linhas, em 10 episodios.
 *
 * <h2>Comportamento em caso de falha</h2>
 * Sem o corretor injetado, a lista nominal da fatia ainda vale e o texto volta sem excecao —
 * preservacao e o default do karaoke. Entrada nula devolve o que recebeu. Nunca lanca.
 */
@ApplicationScoped
public class MontadorEventoFinal {

    /**
     * Repoe acento nas linhas que ESTA fatia traduziu para portugues.
     *
     * <p>Vem de {@code core}, que e consumo livre por contrato. Ortografia e mecanica de idioma;
     * nem a traducao nem o karaoke sao donos do portugues.
     */
    @Inject
    CorretorOrtograficoLegenda corretorOrtografico;

    /**
     * PROPOSITO DE NEGOCIO: o dicionario respondeu ao menos uma vez nesta execucao?
     *
     * <p>Existe para o {@code estadoDoDicionario} do desfecho continuar sabendo responder depois
     * que o corretor mudou de dono. Sem isso, "0 acento reposto" com hunspell ausente voltaria a
     * ser indistinguivel de "0 acento a repor".
     */
    public boolean dicionarioDisponivel() {
        return corretorOrtografico != null && corretorOrtografico.disponivel();
    }

    /**
     * PROPÓSITO DE NEGÓCIO: repõe acento na camada portuguesa da letra, em duas redes que se
     * completam — a lista NOMINAL desta fatia e, depois, o dicionário do sistema.
     *
     * <h2>Por que a lista é da FATIA e não a de {@code qualidadeTraducao}</h2>
     * Regra de Paulo, 2026-08-14: <i>camada de desacoplamento resolve o problema dela e não o
     * atravessa para as outras</i>. A lista do diálogo tem 162 entradas e QUATRO delas são romaji
     * válido — {@code ate}, {@code mae}, {@code nao}, {@code sao}, medidas contra o dicionário
     * {@code ja_ROMAJI}. No diálogo isso é inofensivo, porque lá não existe camada japonesa;
     * arrastá-la para cá traria de volta o dano do Unicorn ({@code mae} = 前 virou {@code mãe}
     * 100 vezes). Ver {@link AcentosLetraKaraoke}.
     *
     * <p>INVARIANTES DO DOMÍNIO: recebe SÓ o texto traduzido — a linha original é anexada depois,
     * em {@link #comOriginalPreservada}. Trocar essa ordem devolve o defeito do Unicorn, e
     * {@code CorretorNaoAlcancaRomajiDoKaraokeTest} congela isso.
     *
     * <p>COMPORTAMENTO EM CASO DE FALHA: sem o corretor injetado — construção manual em teste — a
     * lista nominal ainda vale e o texto volta sem exceção. Preservação é o default do karaokê.
     */
    public String corrigirAcentos(String traduzido) {
        String pelaListaDaFatia = AcentosLetraKaraoke.repor(traduzido);
        return corretorOrtografico == null
            ? pelaListaDaFatia
            : corretorOrtografico.corrigir(pelaListaDaFatia);
    }

    /**
     * PROPÓSITO DE NEGÓCIO: garante que a letra ORIGINAL continue na tela quando não existe uma
     * camada irmã para preservá-la — original em cima, tradução embaixo, que é a promessa deste
     * modo ("romaji em cima, PT-BR embaixo").
     *
     * <h2>Invariantes do domínio</h2>
     * <ul>
     *   <li>Frase pintada por cima por um KFX palavra a palavra ({@link
     *       PlanoDeClassificacao#silabasDaFrase}) NÃO empilha: fica só o português, e as palavras
     *       do efeito são silenciadas por {@link #comoComentario}. Decisão de Paulo em 12/10/2026,
     *       depois de ver na TV a abertura do Unicorn: empilhar punha o português na altura em que
     *       as palavras do efeito estão desenhadas, e o inglês saía escrito EM CIMA dele.</li>
     *   <li>Se JÁ existe camada original preservada neste instante, NÃO empilha — senão a letra
     *       apareceria duas vezes. É o caso dos episódios 01-12 do Unicorn, que têm ED romaji ao
     *       lado do ED - EN.</li>
     *   <li>A moldura de tags do evento é preservada: o texto original entra como está.</li>
     *   <li>Tradução igual ao original não empilha — mostrar a mesma frase duas vezes é ruído.</li>
     * </ul>
     *
     * <h2>Comportamento em caso de falha</h2>
     * Qualquer entrada nula devolve o traduzido sozinho — o comportamento anterior. Plano nulo é
     * tratado como "sem camada original e sem KFX": empilha, o lado que preserva a letra.
     */
    public String comOriginalPreservada(EventoLegenda evento, String traduzido,
            PlanoDeClassificacao plano, int posicao) {
        if (evento == null || traduzido == null || traduzido.isBlank()) {
            return traduzido;
        }
        if (plano != null && !plano.silabasDaFrase(posicao).isEmpty()) {
            return traduzido;
        }
        if (plano != null && plano.temOriginalPreservadaNoInstante(evento)) {
            return traduzido;
        }
        String original = evento.texto();
        if (original == null || original.isBlank()) {
            return traduzido;
        }
        String visivelOriginal = TextoSemTags.decompor(original)
            .map(TextoSemTags::textoLimpo).orElse(original).trim();
        String visivelTraduzido = TextoSemTags.decompor(traduzido)
            .map(TextoSemTags::textoLimpo).orElse(traduzido).trim();
        if (visivelOriginal.isEmpty() || visivelOriginal.equalsIgnoreCase(visivelTraduzido)) {
            return traduzido;
        }
        return original + "\\N" + traduzido;
    }

    /**
     * PROPÓSITO DE NEGÓCIO: tira da tela uma sílaba/palavra do KFX cuja frase virou português,
     * sem apagá-la do arquivo.
     *
     * <h2>Por que {@code Comment:} e não apagar a linha</h2>
     * O ASS tem o tipo de linha que nenhum player desenha, e ele é a forma reversível: o evento
     * continua no arquivo com o texto, o tempo e a posição originais, à vista de quem auditar (A7),
     * e volta a ser {@code Dialogue:} trocando uma palavra. Medido em 12/10/2026: a linha
     * {@code Comment:} atravessa o {@code mkvmerge} e o libass não a desenha quando lê a faixa de
     * dentro do MKV, enquanto o {@code Dialogue:} do mesmo instante aparece.
     *
     * <p>INVARIANTES DO DOMÍNIO: só converte linha {@code Dialogue} cujo prefixo começa com
     * {@code Dialogue:}; índice, estilo, tempos e texto ficam idênticos.
     *
     * <p>COMPORTAMENTO EM CASO DE FALHA: evento nulo, que não é diálogo ou de prefixo em formato
     * desconhecido volta INTACTO — o lado que mantém o efeito na tela, nunca o que some com a letra
     * sem deixar rastro.
     */
    public static EventoLegenda comoComentario(EventoLegenda evento) {
        if (evento == null || !evento.isDialogo() || evento.prefixo() == null
            || !evento.prefixo().startsWith("Dialogue:")) {
            return evento;
        }
        String prefixo = "Comment:" + evento.prefixo().substring("Dialogue:".length());
        return new EventoLegenda(evento.indice(), "Comment", evento.estilo(), prefixo, evento.texto());
    }
}
