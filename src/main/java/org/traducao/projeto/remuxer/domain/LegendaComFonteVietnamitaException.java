package org.traducao.projeto.remuxer.domain;

/**
 * PROPÓSITO DE NEGÓCIO: a legenda PT que ia virar MKV publicado tem fonte vietnamita de codificação
 * antiga — "Não, é a atração!" sai "Nóo, ộ a atraỗóo!". O Gundam ZZ foi publicado assim em
 * 09/10/2026. Ordens de Paulo (14/10): reprova, pede a 1.3 Troca de Tipo de Legenda, e a fonte é
 * banida em qualquer lugar da legenda (estilo sem fala e comentário também).
 *
 * <p>INVARIANTES DO DOMÍNIO: é PENDÊNCIA do operador, não falha técnica — o remux daquele episódio não
 * acontece, os outros seguem. A mensagem nomeia a fonte e onde ela está.
 *
 * <p>COMPORTAMENTO EM CASO DE FALHA: exceção de domínio; quem captura conta e segue o lote.
 */
public class LegendaComFonteVietnamitaException extends RemuxerException {

    public LegendaComFonteVietnamitaException(String message) {
        super(message);
    }
}
