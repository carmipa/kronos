package org.traducao.projeto.traducaoKaraoke.domain;

/**
 * PROPÓSITO DE NEGÓCIO: o que se conseguiu saber das fontes embutidas no vídeo de onde a legenda
 * saiu — a cobertura delas, de qual vídeo vieram e, quando não deu para ler, o porquê.
 *
 * <p>INVARIANTES DO DOMÍNIO: {@code motivoNaoVerificado} preenchido significa que a cobertura é
 * {@link CoberturaDeFontes#nenhuma()} e que NENHUMA decisão de fonte pode ser tomada com base nela
 * (A8: não verificado não é aprovação). O motivo sobe para o resumo do arquivo e para o dataset
 * (A7), em vez de virar um "nada a corrigir" calado.
 *
 * <p>COMPORTAMENTO EM CASO DE FALHA: cobertura nula vira {@link CoberturaDeFontes#nenhuma()}.
 *
 * @param cobertura           o que as fontes lidas desenham
 * @param origem              nome do vídeo lido, ou {@code null} quando nenhum foi
 * @param motivoNaoVerificado por que não foi possível ler; {@code null} quando leu
 */
public record FontesDoVideo(CoberturaDeFontes cobertura, String origem, String motivoNaoVerificado) {

    public FontesDoVideo {
        cobertura = cobertura == null ? CoberturaDeFontes.nenhuma() : cobertura;
    }

    /** Não foi possível ler as fontes; o motivo é obrigatório e vai ao resumo. */
    public static FontesDoVideo naoVerificadas(String motivo) {
        return new FontesDoVideo(CoberturaDeFontes.nenhuma(), null,
            motivo == null || motivo.isBlank() ? "motivo não informado" : motivo);
    }

    public boolean verificadas() {
        return motivoNaoVerificado == null;
    }
}
