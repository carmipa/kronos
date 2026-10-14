package org.traducao.projeto.core.vitrine;

/**
 * PROPÓSITO DE NEGÓCIO: diz a qualquer camada do KRONOS se esta execução é a VITRINE — a
 * cópia pública que mostra as telas na VPS sem que nenhuma função rode fora da máquina do
 * Paulo (decisão de 14/10/2026). É a segunda camada de defesa: o filtro HTTP da fatia
 * {@code vitrine} recusa as rotas, e os pontos únicos de efeito do {@code core} (fila do
 * pipeline e processo externo) consultam esta classe e recusam por conta própria.
 *
 * <p>INVARIANTES DO DOMÍNIO:
 * <ul>
 *   <li>Durante a execução só LIGA ({@link #ligar()}); o valor "desligada" só é gravado no
 *       arranque, por {@link #registrarArranque(boolean)}, e a guarda
 *       {@code VitrineRotasDoCodigoTest} reprova o build se outra classe de produção chamar
 *       esse método. Gravar no arranque, e não só ligar, impede que a vitrine de uma partida
 *       sobreviva no estado estático de outra (reinício de perfil nos testes do Quarkus).</li>
 *   <li>A variável de ambiente {@value #VARIAVEL_AMBIENTE} basta para ligar, sem depender do
 *       CDI nem da configuração do Quarkus — fecha a janela entre o servidor HTTP abrir e o
 *       evento de arranque, e não sofre a armadilha do {@code ConfigProvider} em outro
 *       classloader (registrada no {@code LogStreamService}).</li>
 *   <li>Sem nenhuma das fontes ligadas, o KRONOS local se comporta exatamente como antes.</li>
 * </ul>
 *
 * <p>COMPORTAMENTO EM CASO DE FALHA: {@link #exigirForaDaVitrine(String)} lança
 * {@link OperacaoBloqueadaNaVitrineException} ANTES de qualquer efeito; a leitura da variável
 * de ambiente que falhar por política de segurança da JVM é tratada como "não definida", e o
 * sinalizador do arranque continua valendo.
 */
public final class ModoVitrine {

    /** Variável de ambiente que liga a vitrine na imagem da VPS. */
    public static final String VARIAVEL_AMBIENTE = "KRONOS_VITRINE_ATIVA";

    /** Chave de configuração equivalente, lida pela fatia {@code vitrine} no arranque. */
    public static final String CHAVE_CONFIGURACAO = "kronos.vitrine.ativa";

    private static volatile boolean ligadaNoArranque;

    private ModoVitrine() {
    }

    /**
     * PROPÓSITO DE NEGÓCIO: responde se esta execução é a vitrine.
     *
     * <p>INVARIANTES DO DOMÍNIO: verdadeiro se o arranque ligou a vitrine OU se a variável de
     * ambiente {@value #VARIAVEL_AMBIENTE} vale {@code true} (sem diferenciar maiúscula).
     *
     * <p>COMPORTAMENTO EM CASO DE FALHA: ambiente ilegível conta como não definido.
     */
    public static boolean ativa() {
        return ligadaNoArranque || variavelDeAmbienteLiga();
    }

    /**
     * PROPÓSITO DE NEGÓCIO: liga a vitrine a partir da configuração lida no arranque.
     *
     * <p>INVARIANTES DO DOMÍNIO: idempotente e sem volta — chamar de novo não desliga.
     *
     * <p>COMPORTAMENTO EM CASO DE FALHA: não lança.
     */
    public static void ligar() {
        ligadaNoArranque = true;
    }

    /**
     * PROPÓSITO DE NEGÓCIO: barreira dos pontos únicos de efeito — fila do pipeline e processo
     * externo. Chamada antes de qualquer trabalho, faz a vitrine recusar mesmo que uma rota
     * escape do filtro HTTP.
     *
     * <p>INVARIANTES DO DOMÍNIO: fora da vitrine não faz nada; dentro dela, recusa sempre.
     *
     * <p>COMPORTAMENTO EM CASO DE FALHA: lança {@link OperacaoBloqueadaNaVitrineException}
     * nomeando a operação recusada.
     */
    public static void exigirForaDaVitrine(String operacao) {
        if (ativa()) {
            throw new OperacaoBloqueadaNaVitrineException(operacao);
        }
    }

    /**
     * PROPÓSITO DE NEGÓCIO: grava, no início de cada partida, se esta execução é a vitrine.
     *
     * <p>INVARIANTES DO DOMÍNIO: chamado só pela conferência de arranque da fatia
     * {@code vitrine} (e por teste); a variável de ambiente continua valendo mesmo com
     * {@code false} aqui.
     *
     * <p>COMPORTAMENTO EM CASO DE FALHA: não lança.
     */
    public static void registrarArranque(boolean ativa) {
        ligadaNoArranque = ativa;
    }

    private static boolean variavelDeAmbienteLiga() {
        try {
            return "true".equalsIgnoreCase(System.getenv(VARIAVEL_AMBIENTE));
        } catch (SecurityException e) {
            return false;
        }
    }
}
