package org.traducao.projeto.traducaoKaraoke.domain;

/**
 * PROPÓSITO DE NEGÓCIO: o servidor do LLM deixou de responder NO MEIO da tradução de karaokê. A
 * execução para em vez de seguir gravando a pasta inteira em inglês com o banner de sucesso — o
 * que acontecia até a auditoria de 28/10/2026: cada linha restante gastava três tentativas de até
 * 180 s e o arquivo saía no idioma original sem nada abortar.
 *
 * <p>INVARIANTES DO DOMÍNIO: só nasce de uma falha que o adaptador NÃO classificou como recusa
 * deste pedido ({@code TraducaoLote.recusaDaRequisicao == false}: timeout, 5xx, conexão recusada),
 * que é o contrato do {@code TraducaoLote} — quem não sabe distinguir trata como indisponibilidade.
 * Interrupção pelo operador nunca vira esta exceção.
 *
 * <p>COMPORTAMENTO EM CASO DE FALHA: é a própria falha; a mensagem traz a causa devolvida pelo
 * adaptador e a letra em que parou.
 */
public class ServidorLlmIndisponivelException extends TraducaoKaraokeException {

    public ServidorLlmIndisponivelException(String mensagem) {
        super(mensagem);
    }
}
