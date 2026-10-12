package org.traducao.projeto.traducaoKaraoke.domain;

import java.util.List;

/**
 * PROPÓSITO DE NEGÓCIO: uma linha do DATASET público por ARQUIVO de legenda processado — a
 * unidade de pesquisa desta fatia, do mesmo jeito que o episódio é a unidade da Tradução Local.
 *
 * <h2>O buraco que originou, medido</h2>
 * Em 2026-08-20, {@code logs/telemetria_execucoes.jsonl} tinha 2.266 execuções e <b>zero de
 * karaokê</b>. Tudo que esta fatia media — status, dicionário, cache descartado, acento reposto,
 * arquivo que falhou — morria em dois artefatos que o publicador nunca leu: o manifesto em
 * {@code logs/traducao-karaoke/manifestos/} e o relatório em {@code relatorios/}. O que chegava
 * ao repositório público era UMA linha genérica de sete campos por execução inteira, com o resto
 * espremido numa string de {@code detalhe} — log, não dataset.
 *
 * <h2>Por que por ARQUIVO e não por execução</h2>
 * Uma execução de karaokê varre uma pasta inteira (23 arquivos numa run do 86). Uma linha por
 * execução tornaria impossível a única pergunta que interessa à pesquisa — <i>qual arquivo
 * falhou, e com qual modelo</i> — e a falha é por arquivo por construção
 * ({@link FalhaArquivoKaraoke}). Por arquivo, a tabela junta-se por nome com a do diálogo.
 *
 * <h2>Invariantes do domínio</h2>
 * <ul>
 *   <li><b>Nenhum caminho absoluto.</b> O dataset é PÚBLICO: {@code arquivoDestino},
 *       {@code pastaOrigem} e {@code pastaDestino} ficam de fora por decisão, e a obra é
 *       identificada pelo contexto de lore ({@code contextoNome}/{@code contextoId}), que já é
 *       nome de exibição. A exclusão é NOMINAL e está congelada em
 *       {@code CatracaTelemetriaKaraokeCompletaTest} — campo novo do resultado sem par aqui
 *       reprova o build.</li>
 *   <li><b>{@link DesfechoDoArquivo} tem TRÊS estados.</b> Arquivo traduzido, arquivo que falhou
 *       e arquivo que nunca foi alcançado não podem sair com o mesmo sinal — é a regra das três
 *       saídas aplicada ao dataset. Sem o terceiro, uma execução abortada some da base e some
 *       calada, que é exatamente o defeito que esta classe existe para fechar.</li>
 *   <li>Os campos de EXECUÇÃO (status, motivo, contexto, modelo, dicionário) repetem-se em cada
 *       linha da mesma run. É desnormalização deliberada: {@code registradoEm} agrupa a run, e
 *       quem analisa não precisa de um segundo arquivo para responder "com qual modelo".</li>
 * </ul>
 *
 * <h2>Comportamento em caso de falha</h2>
 * Nunca lança. Lista de avisos nula vira lista vazia — "conferi e não houve aviso" e "não sei"
 * não podem virar o mesmo {@code null} no JSON. Contexto e proveniência ausentes (aborto antes
 * do congelamento) viram {@code null} declarado, não string inventada.
 */
public record TelemetriaKaraoke(
    String registradoEm,
    String origemDoRegistro,
    String arquivo,
    String desfechoArquivo,
    String motivoFalha,
    String statusExecucao,
    String motivoExecucao,
    String contextoId,
    String contextoNome,
    String contextoHash,
    String modeloLlm,
    boolean cacheIgnorado,
    String estadoDicionario,
    long duracaoExecucaoMs,
    int arquivosNaExecucao,
    int eventosTotais,
    int efeitosKfxPreservados,
    int preservadasOriginalJapones,
    int jaEmPortugues,
    int paraTraduzir,
    int reaproveitadasCache,
    int traduzidas,
    int mantidasSemTraducao,
    int acentosRepostos,
    int entradasCacheDescartadas,
    int frasesKfxSoPortugues,
    int silabasKfxSilenciadas,
    int linhasComFonteDoDialogo,
    String fontesDaMusica,
    List<String> avisos
) {

    /**
     * PROPÓSITO DE NEGÓCIO: o que aconteceu com ESTE arquivo — três saídas, nunca duas.
     *
     * <p>INVARIANTES DO DOMÍNIO: {@link #NAO_ALCANCADO} é o "não verificou" da regra das guardas
     * trazido para o dataset. Ele não é sucesso nem falha do arquivo: é a execução que morreu
     * antes de chegar nele (LLM fora do ar, destino não criável, cancelamento entre arquivos).
     */
    public enum DesfechoDoArquivo {

        /** O arquivo foi percorrido e gravado no destino. Pode ter aviso — veja a lista. */
        TRADUZIDO,

        /** O arquivo foi tentado e falhou; o motivo vem de {@link FalhaArquivoKaraoke}. */
        FALHOU,

        /** A execução acabou antes de alcançar arquivo nenhum. Sem isto, aborto some da base. */
        NAO_ALCANCADO
    }

    /**
     * PROPÓSITO DE NEGÓCIO: de ONDE a linha veio — e, por consequência, quais campos dela têm
     * significado.
     *
     * <h2>O prejuízo que originou, medido</h2>
     * Os 19 manifestos de karaokê guardados desde 30/07/2026 têm 383 arquivos com nove
     * contadores reais cada. Mas <b>ZERO dos 19</b> tem {@code statusFinal},
     * {@code estadoDicionario}, {@code cacheIgnorado}, {@code arquivosComFalha},
     * {@code acentosRepostos} ou {@code entradasCacheDescartadas} — esses campos só passaram a
     * existir depois. Importá-los sem marcar a origem faria {@code acentosRepostos = 0}
     * significar ao mesmo tempo "medi e deu zero" e "não existia medição", que é a saída vazia
     * ambígua: "nada a processar" e "cego" não podem produzir o mesmo sinal.
     *
     * <p>INVARIANTES DO DOMÍNIO: numa linha {@link #EXECUCAO} todo campo tem significado, porque
     * o código sempre os preenche. Numa linha {@link #MANIFESTO_HISTORICO} os campos posteriores
     * a 14/08/2026 vêm vazios <b>por ausência de medição</b>, e quem consome o dataset filtra por
     * esta coluna em vez de adivinhar. Lacuna conhecida é permitida; lacuna silenciosa não.
     */
    public enum Origem {

        /** Nasceu da execução que acabou de rodar: todo campo foi medido. */
        EXECUCAO,

        /** Reconstruída de um manifesto antigo: campos que não existiam no schema vêm vazios. */
        MANIFESTO_HISTORICO
    }

    /** Nome do arquivo sintético da linha que registra execução sem arquivo nenhum. */
    public static final String SEM_ARQUIVO = "(nenhum arquivo alcancado)";

    public TelemetriaKaraoke {
        avisos = avisos == null ? List.of() : List.copyOf(avisos);
    }

    /**
     * PROPÓSITO DE NEGÓCIO: a linha de um arquivo que a execução processou até o fim.
     *
     * <p>INVARIANTES DO DOMÍNIO: copia TODOS os contadores de {@link ResultadoTraducaoKaraoke}
     * menos {@code arquivoDestino}, que é caminho absoluto e não vai a dataset público.
     *
     * <p>COMPORTAMENTO EM CASO DE FALHA: resultado nulo devolve {@code null} — quem chama filtra.
     */
    public static TelemetriaKaraoke deArquivo(String registradoEm, ResultadoTraducaoKaraoke r,
            ExecucaoKaraoke execucao) {
        if (r == null) {
            return null;
        }
        ExecucaoKaraoke e = execucao == null ? ExecucaoKaraoke.desconhecida() : execucao;
        return new TelemetriaKaraoke(
            registradoEm, Origem.EXECUCAO.name(), r.arquivo(), DesfechoDoArquivo.TRADUZIDO.name(), null,
            e.status(), e.motivo(), e.contextoId(), e.contextoNome(), e.contextoHash(),
            e.modeloLlm(), e.cacheIgnorado(), e.estadoDicionario(), e.duracaoMs(), e.arquivos(),
            r.eventosTotais(), r.efeitosKfxPreservados(), r.preservadasOriginalJapones(),
            r.jaEmPortugues(), r.paraTraduzir(), r.reaproveitadasCache(), r.traduzidas(),
            r.mantidasSemTraducao(), r.acentosRepostos(), r.entradasCacheDescartadas(),
            r.frasesKfxSoPortugues(), r.silabasKfxSilenciadas(), r.linhasComFonteDoDialogo(),
            r.fontesDaMusica(), r.avisos());
    }

    /**
     * PROPÓSITO DE NEGÓCIO: a linha de um arquivo que foi TENTADO e falhou.
     *
     * <p>INVARIANTES DO DOMÍNIO: contadores em zero aqui não são "nada a fazer" — o
     * {@code desfechoArquivo} diz {@code FALHOU} e o {@code motivoFalha} carrega a causa. É a
     * distinção que o manifesto já fazia e que o dataset não tinha.
     *
     * <p>COMPORTAMENTO EM CASO DE FALHA: falha nula devolve {@code null}.
     */
    public static TelemetriaKaraoke deFalha(String registradoEm, FalhaArquivoKaraoke falha,
            ExecucaoKaraoke execucao) {
        if (falha == null) {
            return null;
        }
        ExecucaoKaraoke e = execucao == null ? ExecucaoKaraoke.desconhecida() : execucao;
        return new TelemetriaKaraoke(
            registradoEm, Origem.EXECUCAO.name(), falha.arquivo(), DesfechoDoArquivo.FALHOU.name(),
            falha.motivo(),
            e.status(), e.motivo(), e.contextoId(), e.contextoNome(), e.contextoHash(),
            e.modeloLlm(), e.cacheIgnorado(), e.estadoDicionario(), e.duracaoMs(), e.arquivos(),
            0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, null, List.of());
    }

    /**
     * PROPÓSITO DE NEGÓCIO: a linha da execução que não chegou a arquivo nenhum.
     *
     * <p>Existe pelo prejuízo medido na fatia: até 2026-08-14 o registro era condicionado a haver
     * resultado, e LLM fora do ar produzia ZERO artefato — o pior desfecho possível saía
     * indistinguível de "não havia nada a fazer". O manifesto já foi consertado; o dataset
     * herdava o mesmo silêncio, e é este método que o fecha.
     *
     * <p>COMPORTAMENTO EM CASO DE FALHA: execução nula ainda produz linha, com os campos em
     * {@code null} — linha existir é o ponto.
     */
    public static TelemetriaKaraoke semArquivo(String registradoEm, ExecucaoKaraoke execucao) {
        ExecucaoKaraoke e = execucao == null ? ExecucaoKaraoke.desconhecida() : execucao;
        return new TelemetriaKaraoke(
            registradoEm, Origem.EXECUCAO.name(), SEM_ARQUIVO, DesfechoDoArquivo.NAO_ALCANCADO.name(),
            e.motivo(),
            e.status(), e.motivo(), e.contextoId(), e.contextoNome(), e.contextoHash(),
            e.modeloLlm(), e.cacheIgnorado(), e.estadoDicionario(), e.duracaoMs(), 0,
            0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, null, List.of());
    }

    /**
     * PROPÓSITO DE NEGÓCIO: a linha reconstruída de um MANIFESTO antigo, para o dataset não
     * nascer vazio esperando a próxima execução.
     *
     * <h2>O que ela pode e o que ela não pode afirmar</h2>
     * Um arquivo listado em {@code arquivos[]} do manifesto FOI processado até o fim — por isso
     * {@code TRADUZIDO} é afirmável por linha. O que não é afirmável é o desfecho da EXECUÇÃO:
     * os manifestos antigos não registravam falha por arquivo, então "esta run foi completa" é
     * exatamente o que não se sabe. Ele sai {@code null}, com {@link Origem#MANIFESTO_HISTORICO}
     * dizendo por quê.
     *
     * <p>INVARIANTES DO DOMÍNIO: os contadores que o manifesto NÃO tinha entram em zero, e é a
     * coluna de origem — não o zero — que carrega a informação "não medido". Sem ela este método
     * seria uma fábrica de dado falso.
     *
     * <p>COMPORTAMENTO EM CASO DE FALHA: contadores ausentes viram zero; texto ausente vira
     * {@code null}. Nunca lança.
     */
    public static TelemetriaKaraoke deManifesto(
            String registradoEm, String arquivo, String contextoId, String contextoNome,
            String contextoHash, String modeloLlm, long duracaoMs, int arquivosNaExecucao,
            int eventosTotais, int efeitosKfxPreservados, int preservadasOriginalJapones,
            int jaEmPortugues, int paraTraduzir, int reaproveitadasCache, int traduzidas,
            int mantidasSemTraducao, List<String> avisos) {
        return new TelemetriaKaraoke(
            registradoEm, Origem.MANIFESTO_HISTORICO.name(), arquivo,
            DesfechoDoArquivo.TRADUZIDO.name(), null,
            // Desfecho da execucao, dicionario e cache ignorado NAO existiam no schema da epoca.
            null, null, contextoId, contextoNome, contextoHash, modeloLlm, false, null,
            duracaoMs, arquivosNaExecucao,
            eventosTotais, efeitosKfxPreservados, preservadasOriginalJapones, jaEmPortugues,
            paraTraduzir, reaproveitadasCache, traduzidas, mantidasSemTraducao,
            // Acento reposto e cache descartado sao posteriores a 14/08, e KFX so em portugues e
            // fonte do dialogo a 12/10: zero aqui e "nao medido", e quem diz isso e a coluna
            // origemDoRegistro.
            0, 0, 0, 0, 0, null, avisos);
    }

    /**
     * PROPÓSITO DE NEGÓCIO: os campos que valem para a execução INTEIRA, agrupados para não
     * viajarem como onze parâmetros soltos por cada uma das três fábricas.
     *
     * <p>INVARIANTES DO DOMÍNIO: é o único ponto que traduz "não sei" em {@code null}. Nada aqui
     * inventa valor: contexto ausente é {@code null}, não {@code "desconhecido"} — o dataset tem
     * de saber a diferença entre um contexto chamado assim e a ausência dele.
     *
     * <p>COMPORTAMENTO EM CASO DE FALHA: {@link #desconhecida()} devolve tudo nulo com
     * {@code cacheIgnorado=false}, o lado que não afirma nada.
     */
    public record ExecucaoKaraoke(
        String status,
        String motivo,
        String contextoId,
        String contextoNome,
        String contextoHash,
        String modeloLlm,
        boolean cacheIgnorado,
        String estadoDicionario,
        long duracaoMs,
        int arquivos
    ) {
        public static ExecucaoKaraoke desconhecida() {
            return new ExecucaoKaraoke(null, null, null, null, null, null, false, null, 0L, 0);
        }
    }
}
