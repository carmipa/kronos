package org.traducao.projeto.core.vitrine;

/**
 * PROPÓSITO DE NEGÓCIO: recusa explícita de uma operação que, na vitrine pública, causaria
 * efeito real — trabalho na fila do pipeline ou processo externo.
 *
 * <p>INVARIANTES DO DOMÍNIO: é lançada ANTES do efeito; a mensagem nomeia a operação e diz que
 * ela só roda na instalação local, sem caminho, configuração ou dado do acervo.
 *
 * <p>COMPORTAMENTO EM CASO DE FALHA: exceção não verificada; quem a recebe não deve tentar de
 * novo — a recusa é permanente nesta execução.
 */
public class OperacaoBloqueadaNaVitrineException extends IllegalStateException {

    /**
     * PROPÓSITO DE NEGÓCIO: cria a recusa nomeando a operação barrada.
     *
     * <p>INVARIANTES DO DOMÍNIO: mensagem estável, sem dado do servidor.
     *
     * <p>COMPORTAMENTO EM CASO DE FALHA: não lança.
     */
    public OperacaoBloqueadaNaVitrineException(String operacao) {
        super("Vitrine do KRONOS: '" + operacao + "' só roda na instalação local.");
    }
}
