package org.traducao.projeto.telemetria;

import org.traducao.projeto.core.io.DiretorioBaseKronos;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.enterprise.context.ApplicationScoped;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.traducao.projeto.core.util.ProcessoExternoUtil;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.Year;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * PROPÓSITO DE NEGÓCIO: publica a telemetria acumulada como dataset público num repositório Git
 * DEDICADO ({@code kronos-anime-translation-telemetry-dataset}, seguindo a
 * convenção {@code [NomeDoSistema]-telemetry-dataset} para dados de pesquisa/ML).
 * <p>
 * O serviço é auto-suficiente: se o repositório local não existir, ele clona o
 * remoto configurado (ou inicializa um novo e associa o remoto); na primeira
 * publicação gera README com declaração de anonimização (LGPD/GDPR), LICENSE e
 * a estrutura {@code metrics/}. Cada publicação = 1 commit + push, e o
 * histórico Git é o versionamento natural dos snapshots.
 * <p>
 * <p>INVARIANTES DO DOMÍNIO: a sanitização deliberada mantém
 * carrega apenas MÉTRICAS: nada de textos de legenda (os avisos viram
 * contagem), nada de caminhos de máquina (o campo {@code detalhe} das
 * operações é descartado e nomes de episódio perdem qualquer diretório); o
 * ambiente de hardware pertence integralmente à máquina publicadora.
 *
 * <p>COMPORTAMENTO EM CASO DE FALHA: erros de geração, Git ou rede interrompem a
 * publicação com {@link IOException}, preservando o snapshot anterior.
 */
@ApplicationScoped
public class TelemetriaDatasetService {

    private static final Logger log = LoggerFactory.getLogger(TelemetriaDatasetService.class);

    static final String NOME_ARQUIVO_DATASET = "kronos-telemetria-dataset.json";
    /** Acervo append-only: uma linha por EXECUÇÃO. Cresce e nunca diminui. */
    static final String NOME_ARQUIVO_EXECUCOES = "kronos-telemetria-execucoes.jsonl";
    /** Histórico local que alimenta o acervo (limitado por teto na máquina do operador). */
    private static final String NOME_ARQUIVO_HISTORICO_LOCAL = "telemetria_execucoes.jsonl";
    /** Acervo do KARAOKÊ: append-only, uma linha por ARQUIVO de legenda processado. */
    static final String NOME_ARQUIVO_KARAOKE = "kronos-karaoke-execucoes.jsonl";
    /**
     * O acervo local que a fatia de karaokê escreve. O nome é o mesmo declarado em
     * {@code TelemetriaKaraokeDataset.NOME_ARQUIVO}; ele NÃO é importado de lá porque o módulo
     * de telemetria não depende de fatia — ele lê {@code logs/}, como já faz com o diálogo.
     * {@code CatracaTelemetriaKaraokeCompletaTest} congela a igualdade dos dois nomes.
     */
    static final String NOME_ARQUIVO_KARAOKE_LOCAL = "telemetria_karaoke_execucoes.jsonl";
    private static final Duration TIMEOUT_GIT = Duration.ofSeconds(30);
    private static final Duration TIMEOUT_REDE = Duration.ofMinutes(2);

    private final TelemetriaService telemetria;
    private final TelemetriaDatasetProperties propriedades;
    private final AmbienteExecucaoDatasetService ambienteExecucao;
    private final ObjectMapper mapper = new ObjectMapper();

    private final ConsolidadorTelemetriaPorFatia consolidador;

    public TelemetriaDatasetService(
            TelemetriaService telemetria,
            TelemetriaDatasetProperties propriedades,
            AmbienteExecucaoDatasetService ambienteExecucao,
            ConsolidadorTelemetriaPorFatia consolidador) {
        this.telemetria = telemetria;
        this.propriedades = propriedades;
        this.ambienteExecucao = ambienteExecucao;
        this.consolidador = consolidador;
    }

    /**
     * PROPÓSITO DE NEGÓCIO: copia para o repositório público um arquivo POR
     * FATIA, para que cada assunto tenha o próprio dataset em vez de tudo virar
     * uma lista genérica de seis campos.
     *
     * <h2>O prejuízo que originou</h2>
     * Medido em 06/08/2026: das 6.601 operações do acervo, apenas <b>85</b>
     * chegaram ao repositório público — 1,3% — porque o publicador só olhava para
     * {@code logs/}. E o que chegava vinha achatado num formato único, sem o campo
     * livre, que era omitido por carregar caminho absoluto.
     *
     * <p>Com o consolidado por fatia, cada dataset carrega o detalhe SANITIZADO:
     * a medição sobrevive, o caminho não.
     *
     * <p>INVARIANTES DO DOMÍNIO: copia apenas fatias que existem — não cria
     * arquivo vazio para fatia sem dado, porque dataset vazio publicado é ruído
     * que quem consome precisa aprender a ignorar.
     *
     * <p>COMPORTAMENTO EM CASO DE FALHA: fatia ilegível é pulada com aviso; a
     * publicação das demais continua. Devolve o total de operações copiadas.
     */
    private int publicarDatasetsPorFatia(Path pastaMetrics) throws IOException {
        Path pastaFatias = pastaMetrics.resolve("fatias");
        Files.createDirectories(pastaFatias);

        Path logs = DiretorioBaseKronos.resolver("logs");
        if (!Files.isDirectory(logs)) {
            return 0;
        }

        int total = 0;
        try (java.util.stream.Stream<Path> fluxo = Files.list(logs)) {
            for (Path origem : fluxo
                    .filter(p -> p.getFileName().toString().startsWith("telemetria_fatia_"))
                    .sorted().toList()) {
                try {
                    com.fasterxml.jackson.databind.JsonNode raiz = mapper.readTree(origem.toFile());
                    com.fasterxml.jackson.databind.JsonNode ops = raiz.get("operacoes");
                    int n = ops == null ? 0 : ops.size();
                    if (n == 0) {
                        continue;
                    }
                    Path destino = pastaFatias.resolve(
                        origem.getFileName().toString().replace("telemetria_fatia_", "kronos-fatia-"));
                    mapper.writerWithDefaultPrettyPrinter().writeValue(destino.toFile(), raiz);
                    total += n;
                } catch (IOException e) {
                    log.warn("Consolidado de fatia ilegivel, pulado: {} ({})", origem, e.getMessage());
                }
            }
        }
        log.info("Datasets por fatia publicados em {}: {} operacao(oes).", pastaFatias, total);
        return total;
    }

    static final List<String> COLUNAS_RESUMO = List.of(
        "totalEpisodiosTraduzidos", "totalLinhasTraduzidas", "tempoMedioPorLinhaMs",
        "totalFalasReaproveitadasDoCache", "alucinacoesLlmPrevenidas", "respostasTraducaoRejeitadas",
        "falhasTraducaoRecuperadas", "fallbacksTraducaoMantidos", "arquivosRenomeados",
        "totalOperacoesRegistradas");

    static final List<String> COLUNAS_AMBIENTE = List.of(
        "fabricante", "modeloMaquina", "cpu", "gpuPrincipal", "gpusDetectadas", "ramTotalGb",
        "sistemaOperacional", "arquitetura", "hardwareColetadoAutomaticamente");

    static final List<String> COLUNAS_TRADUCOES = List.of(
        "episodio", "anime", "temporada", "modeloLlm", "totalLinhas", "falasTraduzidas",
        "falasDoCache", "tempoTotalMs", "quantidadeAvisos", "registradoEm",
        "falasItalicoRemovido", "falasItalicoPreservado");

    static final List<String> COLUNAS_OPERACOES = List.of(
        "tipo", "tempoTotalMs", "arquivosProcessados", "itensDetectados", "itensCorrigidos",
        "registradoEm");

    static final List<String> COLUNAS_EXECUCOES = List.of(
        "registradoEm", "nomeEpisodio", "animeNome", "temporada", "loreNome", "modeloLlm",
        "statusFinal", "totalLinhas", "falasTraduzidas", "falasDoCache", "tempoTotalMs",
        "quantidadeAvisos");

    /** Uma linha por AVISO, não por execução: é aqui que mora o diagnóstico com o trecho da fala. */
    static final List<String> COLUNAS_AVISOS = List.of(
        "registradoEm", "nomeEpisodio", "animeNome", "ordem", "aviso");

    /**
     * Uma linha por ARQUIVO de legenda de karaokê — a unidade desta fatia.
     *
     * <p>Carrega o que nenhuma outra tabela do dataset tem: {@code desfechoArquivo} com as três
     * saídas, o estado do dicionário de acentuação, o cache descartado por proveniência
     * divergente e as duas contagens da camada japonesa. Sem ela, a única pergunta que a base
     * respondia sobre karaokê era "quantas execuções houve".
     */
    static final List<String> COLUNAS_KARAOKE = List.of(
        "registradoEm", "origemDoRegistro", "arquivo", "desfechoArquivo", "motivoFalha", "statusExecucao",
        "motivoExecucao", "contextoNome", "contextoId", "contextoHash", "modeloLlm",
        "cacheIgnorado", "estadoDicionario", "duracaoExecucaoMs", "arquivosNaExecucao",
        "eventosTotais", "efeitosKfxPreservados", "preservadasOriginalJapones", "jaEmPortugues",
        "paraTraduzir", "reaproveitadasCache", "traduzidas", "mantidasSemTraducao",
        "acentosRepostos", "entradasCacheDescartadas", "frasesKfxSoPortugues",
        "silabasKfxSilenciadas", "linhasComFonteDoDialogo", "fontesDaMusica", "quantidadeAvisos");

    /** Tidy data do karaokê: uma linha por aviso, ligada de volta por {@code registradoEm + arquivo}. */
    static final List<String> COLUNAS_KARAOKE_AVISOS = List.of(
        "registradoEm", "arquivo", "contextoNome", "ordem", "aviso");

    /**
     * PROPÓSITO DE NEGÓCIO: publica as mesmas métricas em CSV ao lado do JSON, para quem for
     * analisar o dataset abrir em pandas/R/planilha sem escrever parser de JSON aninhado.
     *
     * <h2>Invariantes do domínio</h2>
     * <ul>
     *   <li>As tabelas de resumo, ambiente, traduções e operações saem do <b>mesmo
     *       {@code ObjectNode}</b> gravado como JSON — um só caminho de montagem, uma só
     *       sanitização. Montar de novo a partir do {@code TelemetriaResumo} criaria a segunda
     *       implementação da mesma regra, e ela divergiria na primeira mudança de schema.</li>
     *   <li>{@code kronos-avisos.csv} é <b>tidy data</b>: uma linha por aviso, com
     *       {@code registradoEm + nomeEpisodio} ligando de volta à execução. Espremer N avisos
     *       numa célula produziria campo gigante que nenhuma planilha abre e nenhum
     *       {@code group by} agrega.</li>
     *   <li>O CSV de avisos carrega o <b>texto do diagnóstico</b>, que inclui trecho de fala —
     *       publicação DELIBERADA (decisão de 2026-08-10), declarada no README. Não é vazamento:
     *       é o dado que permite estudar por que uma tradução falhou.</li>
     * </ul>
     *
     * <p>COMPORTAMENTO EM CASO DE FALHA: acervo de execuções ausente gera os CSVs derivados do
     * JSON e pula os dois derivados do JSONL — publicar tabela vazia é melhor que abortar a
     * publicação inteira. Linha ilegível do acervo é pulada. Devolve o total de linhas de dados
     * escritas em todos os CSVs.
     */
    // Visibilidade de PACOTE: mesma razao de acumularExecucoesKaraoke — provar a tabela gerada
    // sem efeito externo. Publicar CSV nao muda estado fora de pastaMetrics.
    int publicarCsv(Path pastaMetrics, ObjectNode dataset) throws IOException {
        Path pastaCsv = pastaMetrics.resolve("csv");
        Files.createDirectories(pastaCsv);

        int linhas = 0;
        linhas += gravarCsv(pastaCsv.resolve("kronos-resumo.csv"),
            TelemetriaDatasetCsv.deObjeto(dataset.get("resumo"), COLUNAS_RESUMO));
        linhas += gravarCsv(pastaCsv.resolve("kronos-ambiente-execucao.csv"),
            TelemetriaDatasetCsv.deObjeto(dataset.get("ambienteExecucao"), COLUNAS_AMBIENTE));
        linhas += gravarCsv(pastaCsv.resolve("kronos-traducoes-llm.csv"),
            TelemetriaDatasetCsv.deArray(dataset.get("traducoesLlm"), COLUNAS_TRADUCOES));
        linhas += gravarCsv(pastaCsv.resolve("kronos-operacoes.csv"),
            TelemetriaDatasetCsv.deArray(dataset.get("operacoes"), COLUNAS_OPERACOES));

        List<List<String>> execucoes = new ArrayList<>();
        List<List<String>> avisos = new ArrayList<>();
        lerAcervoParaCsv(pastaMetrics.resolve(NOME_ARQUIVO_EXECUCOES), TABELA_EXECUCOES,
            execucoes, avisos);
        linhas += gravarCsv(pastaCsv.resolve("kronos-execucoes.csv"),
            TelemetriaDatasetCsv.deLinhas(COLUNAS_EXECUCOES, execucoes));
        linhas += gravarCsv(pastaCsv.resolve("kronos-avisos.csv"),
            TelemetriaDatasetCsv.deLinhas(COLUNAS_AVISOS, avisos));

        // O karaokê tem tabela PRÓPRIA: os contadores dele (camada japonesa preservada, efeito
        // KFX, acento reposto, cache descartado) não existem no schema por episódio do diálogo.
        List<List<String>> karaoke = new ArrayList<>();
        List<List<String>> karaokeAvisos = new ArrayList<>();
        lerAcervoParaCsv(pastaMetrics.resolve(NOME_ARQUIVO_KARAOKE), TABELA_KARAOKE,
            karaoke, karaokeAvisos);
        linhas += gravarCsv(pastaCsv.resolve("kronos-karaoke.csv"),
            TelemetriaDatasetCsv.deLinhas(COLUNAS_KARAOKE, karaoke));
        linhas += gravarCsv(pastaCsv.resolve("kronos-karaoke-avisos.csv"),
            TelemetriaDatasetCsv.deLinhas(COLUNAS_KARAOKE_AVISOS, karaokeAvisos));

        log.info("CSVs do dataset publicados em {}: {} linha(s) de dados.", pastaCsv, linhas);
        return linhas;
    }

    /** Grava o CSV e devolve quantas linhas de DADOS ele tem (total menos o cabeçalho). */
    private int gravarCsv(Path destino, String conteudo) throws IOException {
        Files.writeString(destino, conteudo, StandardCharsets.UTF_8);
        long total = conteudo.lines().count();
        return (int) Math.max(0, total - 1);
    }

    /**
     * PROPÓSITO DE NEGÓCIO: desdobra o acervo JSONL nas duas tabelas que ele contém — uma linha
     * por execução e uma linha por aviso.
     *
     * <p>INVARIANTES DO DOMÍNIO: a chave {@code registradoEm + nomeEpisodio} aparece nas duas
     * tabelas, para o join ser possível sem inventar id. {@code ordem} preserva a posição do aviso
     * dentro da execução, que se perderia num CSV desordenado.
     *
     * <p>COMPORTAMENTO EM CASO DE FALHA: arquivo ausente devolve as listas como estão (vazias).
     * Linha ilegível é pulada com log em DEBUG — uma linha corrompida não pode derrubar a tabela.
     */
    private void lerAcervoParaCsv(Path acervo, FormatoTabela formato,
            List<List<String>> execucoes, List<List<String>> avisos) throws IOException {
        if (!Files.exists(acervo)) {
            return;
        }
        for (String linha : Files.readAllLines(acervo, StandardCharsets.UTF_8)) {
            if (linha == null || linha.isBlank()) {
                continue;
            }
            try {
                JsonNode no = mapper.readTree(linha);
                String quando = texto(no, "registradoEm");
                String unidade = texto(no, formato.campoUnidade());
                JsonNode erros = no.get(formato.campoLista());
                int quantidade = erros == null || !erros.isArray() ? 0 : erros.size();

                List<String> exec = new ArrayList<>(formato.colunas().size());
                for (String coluna : formato.colunas()) {
                    exec.add("quantidadeAvisos".equals(coluna)
                        ? String.valueOf(quantidade)
                        : valorSimples(no, coluna));
                }
                execucoes.add(exec);

                if (erros != null && erros.isArray()) {
                    for (int i = 0; i < erros.size(); i++) {
                        avisos.add(List.of(
                            quando == null ? "" : quando,
                            unidade == null ? "" : unidade,
                            valorSimples(no, formato.campoAgrupador()),
                            String.valueOf(i + 1),
                            erros.get(i) == null ? "" : erros.get(i).asText()));
                    }
                }
            } catch (IOException e) {
                log.debug("Linha ilegivel no acervo ao gerar CSV, ignorada: {}", e.getMessage());
            }
        }
    }

    /**
     * PROPÓSITO DE NEGÓCIO: o que muda entre o acervo do diálogo e o do karaokê — e SÓ isso.
     *
     * <p>Existe para os dois acervos passarem pelo mesmo desdobramento em tabela. A alternativa
     * era um segundo método com o mesmo laço e outros nomes de campo, e a segunda implementação
     * da mesma regra diverge da primeira na primeira mudança de schema.
     *
     * <p>INVARIANTES DO DOMÍNIO: {@code campoUnidade} é a chave da linha (episódio × arquivo);
     * {@code campoAgrupador} é a coluna que dá contexto ao aviso (obra × contexto de lore);
     * {@code campoLista} é o array que vira tidy data.
     */
    private record FormatoTabela(
        List<String> colunas,
        List<String> colunasAvisos,
        String campoUnidade,
        String campoAgrupador,
        String campoLista,
        List<String> camposLivres
    ) {}

    private static final FormatoTabela TABELA_EXECUCOES = new FormatoTabela(
        COLUNAS_EXECUCOES, COLUNAS_AVISOS, "nomeEpisodio", "animeNome", "errosOcorridos",
        List.of());

    private static final FormatoTabela TABELA_KARAOKE = new FormatoTabela(
        COLUNAS_KARAOKE, COLUNAS_KARAOKE_AVISOS, "arquivo", "contextoNome", "avisos",
        List.of("motivoFalha", "motivoExecucao"));

    private static String valorSimples(JsonNode no, String campo) {
        JsonNode v = no.get(campo);
        return v == null || v.isNull() ? "" : v.asText();
    }

    /** Resultado da publicação, devolvido ao painel de Telemetria. */
    public record ResultadoPublicacao(String repositorio, String commit, boolean pushOk, String mensagem) {}

    /**
     * PROPÓSITO DE NEGÓCIO: faz o ACERVO do dataset crescer e nunca diminuir. O
     * {@code kronos-telemetria-dataset.json} é uma FOTO — reescrito por inteiro a cada
     * publicação e contendo só o último resultado de cada episódio, porque o banco em memória é
     * chaveado pelo nome do episódio. Isso serve ao painel, mas não a pesquisa: retraduzir o
     * mesmo episódio apaga a medição anterior e a base nunca cresce em linhas.
     *
     * <p>Este método publica, ao lado da foto, um acervo em JSONL onde cada linha é UMA
     * execução. Ele funde o que já está no repositório do dataset com o histórico local e grava
     * de volta — o arquivo cresce em linhas e o git preserva cada commit.
     *
     * <h2>Invariantes do domínio</h2>
     * <ul>
     *   <li>NADA é removido: o que já está no acervo permanece, mesmo que tenha saído do
     *       histórico local por causa do teto de armazenamento da máquina.</li>
     *   <li>Deduplicação por {@code nomeEpisodio + registradoEm} — a chave natural de uma
     *       execução. Republicar não duplica linha.</li>
     *   <li>Ordem cronológica estável por {@code registradoEm}, para o diff do commit mostrar
     *       apenas as linhas acrescentadas.</li>
     * </ul>
     *
     * <h2>Comportamento em caso de falha</h2>
     * Histórico local ausente (primeira execução após a mudança, ou máquina nova) é caso normal:
     * devolve 0 e o acervo remoto segue intacto. Linha ilegível é PULADA, nunca descarta o
     * arquivo. Falha de I/O na gravação propaga {@link IOException} e aborta a publicação antes
     * do commit, preservando o acervo anterior.
     *
     * @return quantas execuções NOVAS entraram no acervo nesta publicação
     */
    // Visibilidade de PACOTE pela mesma razao de acumularExecucoesKaraoke: e por onde um teste
    // prova a sanitizacao na fronteira sem disparar clone/commit/push.
    int acumularExecucoes(Path pastaMetrics) throws IOException {
        return acumularAcervo(
            pastaMetrics.resolve(NOME_ARQUIVO_EXECUCOES),
            DiretorioBaseKronos.resolver("logs", NOME_ARQUIVO_HISTORICO_LOCAL),
            TABELA_EXECUCOES,
            "execuções");
    }

    /**
     * PROPÓSITO DE NEGÓCIO: faz o acervo do KARAOKÊ crescer, pelo mesmo caminho e com as mesmas
     * garantias do acervo de diálogo.
     *
     * <h2>O buraco que fecha, medido</h2>
     * Em 2026-08-20: {@code telemetria_execucoes.jsonl} tinha 2.266 execuções e <b>zero de
     * karaokê</b>. A fatia media status, dicionário, falha por arquivo, acento reposto e cache
     * descartado — e nada disso saía de {@code logs/traducao-karaoke/manifestos/}, que o
     * publicador nunca leu. O que chegava ao repositório público era UMA linha genérica de sete
     * campos por execução inteira.
     *
     * <p>INVARIANTES DO DOMÍNIO: a chave é {@code registradoEm + arquivo}, não
     * {@code nomeEpisodio} — a unidade do karaokê é o ARQUIVO de legenda, e uma execução varre a
     * pasta inteira. Reusa {@link #acumularAcervo}: um segundo laço de fusão divergiria do
     * primeiro na primeira mudança de schema.
     *
     * <p>COMPORTAMENTO EM CASO DE FALHA: acervo local ausente (nenhuma tradução de karaokê nesta
     * máquina) devolve 0 e o acervo remoto segue intacto.
     */
    // Visibilidade de PACOTE de proposito: e o unico ponto por onde um teste consegue provar que
    // o acervo do karaoke vira arquivo publicado sem disparar clone/commit/push do repositorio.
    int acumularExecucoesKaraoke(Path pastaMetrics) throws IOException {
        return acumularAcervo(
            pastaMetrics.resolve(NOME_ARQUIVO_KARAOKE),
            DiretorioBaseKronos.resolver("logs", NOME_ARQUIVO_KARAOKE_LOCAL),
            TABELA_KARAOKE,
            "karaokê");
    }

    /**
     * PROPÓSITO DE NEGÓCIO: funde o histórico local com o acervo já publicado, de modo que a base
     * só cresça — o motor comum dos dois acervos (diálogo e karaokê).
     *
     * <p>INVARIANTES DO DOMÍNIO: nada é removido; deduplica por {@code registradoEm + campoChave};
     * ordem cronológica estável para o diff do commit mostrar só o que entrou.
     *
     * <p>COMPORTAMENTO EM CASO DE FALHA: arquivos ausentes são caso normal (devolve 0). Falha de
     * I/O na gravação propaga e aborta a publicação antes do commit, preservando o acervo anterior.
     */
    private int acumularAcervo(Path acervo, Path historicoLocal, FormatoTabela formato, String rotulo)
            throws IOException {
        Map<String, String> porExecucao = new LinkedHashMap<>();
        int[] limpas = new int[1];
        int jaNoAcervo = indexar(acervo, porExecucao, formato, limpas);
        int antes = porExecucao.size();
        indexar(historicoLocal, porExecucao, formato, limpas);
        int novas = porExecucao.size() - antes;

        if (limpas[0] > 0) {
            log.info("Acervo de {}: {} linha(s) tiveram texto livre sanitizado na publicação.",
                rotulo, limpas[0]);
        }
        // A sanitização entra na decisão de reescrever: sem isso, uma publicação sem execução
        // nova deixaria intacto o caminho de máquina que ela acabou de encontrar.
        if (novas == 0 && jaNoAcervo == porExecucao.size() && limpas[0] == 0) {
            return 0; // nada mudou: não reescreve o arquivo à toa
        }
        List<String> ordenadas = porExecucao.entrySet().stream()
            .sorted(Map.Entry.comparingByKey())
            .map(Map.Entry::getValue)
            .toList();
        Files.write(acervo, ordenadas, StandardCharsets.UTF_8);
        log.info("Acervo de {} do dataset: {} linha(s) no total, {} nova(s) nesta publicação.",
            rotulo, ordenadas.size(), novas);
        return novas;
    }

    /**
     * PROPÓSITO DE NEGÓCIO: indexa um arquivo JSONL de execuções pela chave natural da execução,
     * de modo que fundir dois arquivos seja um {@code putIfAbsent} sem risco de duplicar.
     *
     * <p>INVARIANTES DO DOMÍNIO: a chave é {@code registradoEm + '|' + nomeEpisodio} — o carimbo
     * primeiro para que a ordenação por chave já seja cronológica. Uma execução já presente NÃO
     * é sobrescrita: o acervo é lido antes do histórico local, então o que já foi publicado
     * vence.
     *
     * <p>COMPORTAMENTO EM CASO DE FALHA: arquivo inexistente devolve 0 (caso normal). Linha
     * ilegível ou sem os campos-chave é ignorada com log em DEBUG — uma linha corrompida não
     * pode impedir a publicação de todas as outras.
     *
     * @return quantas linhas válidas o arquivo contribuiu
     */
    private int indexar(Path arquivo, Map<String, String> destino, FormatoTabela formato,
            int[] limpas) throws IOException {
        if (!Files.exists(arquivo)) {
            return 0;
        }
        int validas = 0;
        for (String linha : Files.readAllLines(arquivo, StandardCharsets.UTF_8)) {
            if (linha == null || linha.isBlank()) {
                continue;
            }
            try {
                JsonNode no = mapper.readTree(linha);
                String unidade = texto(no, formato.campoUnidade());
                String quando = texto(no, "registradoEm");
                if (unidade == null || quando == null) {
                    continue;
                }
                String publicavel = sanitizarTextoLivre(no, formato);
                if (publicavel != null) {
                    linha = publicavel;
                    limpas[0]++;
                }
                destino.putIfAbsent(quando + '|' + unidade, linha);
                validas++;
            } catch (IOException e) {
                log.debug("Linha ilegivel no historico de execucoes, ignorada: {}", e.getMessage());
            }
        }
        return validas;
    }

    private static String texto(JsonNode no, String campo) {
        JsonNode v = no.get(campo);
        return v == null || v.isNull() ? null : v.asText();
    }

    /**
     * PROPÓSITO DE NEGÓCIO: passa pelo sanitizador o TEXTO LIVRE de uma linha do acervo, na
     * fronteira do que vira público — a única passagem por onde toda linha de todo acervo
     * obrigatoriamente atravessa.
     *
     * <h2>O prejuízo que originou, medido em 20/08/2026</h2>
     * O acervo do diálogo tinha <b>402 caminhos {@code C:\animes\<obra>}</b> nos avisos, todos
     * já publicados: eles nascem de mensagens de bloqueio que citam o arquivo. O README do
     * dataset promete "nada de caminhos de máquina" e o dado não honrava. Medido com o
     * sanitizador de produção sobre os 9.335 avisos reais: 402 transformados, <b>zero
     * redigidos</b> — nenhum diagnóstico se perde, porque de um caminho sobrevivem os dois
     * últimos segmentos, que são obra e arquivo.
     *
     * <h2>Por que aqui, e não na escrita</h2>
     * Sanitizar na escrita mataria o caminho também no artefato LOCAL, onde ele é o que permite
     * abrir o arquivo e depurar. A fronteira do público é a publicação. É também a razão de a
     * limpeza valer para as linhas que JÁ estavam no acervo, e não só para as novas: uma
     * publicação sem execução nova deixaria intacto o caminho que ela acabou de encontrar.
     *
     * <p>INVARIANTES DO DOMÍNIO: só campos declarados em {@code campoLista} e
     * {@code camposLivres} são tocados. Nome de episódio, obra e lore NÃO passam por aqui — são
     * identificação, e o sanitizador poderia aparar um nome legítimo.
     *
     * <p>COMPORTAMENTO EM CASO DE FALHA: devolve {@code null} quando nada mudou, para o chamador
     * preservar a linha ORIGINAL byte a byte e não reescrever o acervo à toa.
     */
    private String sanitizarTextoLivre(JsonNode no, FormatoTabela formato) {
        if (!(no instanceof ObjectNode objeto)) {
            return null;
        }
        boolean mudou = false;

        JsonNode lista = objeto.get(formato.campoLista());
        if (lista != null && lista.isArray()) {
            ArrayNode limpa = mapper.createArrayNode();
            boolean mudouLista = false;
            for (JsonNode item : lista) {
                if (item == null || !item.isTextual()) {
                    limpa.add(item);
                    continue;
                }
                String sanitizado = SanitizadorTelemetria.sanitizar(item.asText());
                mudouLista = mudouLista || !item.asText().equals(sanitizado);
                limpa.add(sanitizado);
            }
            if (mudouLista) {
                objeto.set(formato.campoLista(), limpa);
                mudou = true;
            }
        }

        for (String campo : formato.camposLivres()) {
            JsonNode valor = objeto.get(campo);
            if (valor == null || !valor.isTextual()) {
                continue;
            }
            String sanitizado = SanitizadorTelemetria.sanitizar(valor.asText());
            if (!valor.asText().equals(sanitizado)) {
                objeto.put(campo, sanitizado);
                mudou = true;
            }
        }

        if (!mudou) {
            return null;
        }
        try {
            return mapper.writeValueAsString(objeto);
        } catch (IOException e) {
            // Reserializar falhou: a linha original segue como está. Publicar meia linha seria
            // pior que publicar a suja, e a suja o chamador já tem.
            log.warn("Nao foi possivel reserializar linha sanitizada, mantida original: {}",
                e.getMessage());
            return null;
        }
    }

    public synchronized ResultadoPublicacao publicar() throws IOException {
        Path repo = Path.of(propriedades.repositorioLocal()).toAbsolutePath().normalize();
        prepararRepositorio(repo);
        garantirDocumentosBase(repo);
        // A consolidação roda ANTES do snapshot: sem ela, os consolidados de
        // logs/ estariam com o conteúdo da publicação anterior e o dataset sairia
        // com um retrato velho sem ninguém notar.
        try {
            consolidador.consolidar();
        } catch (IOException e) {
            log.warn("Consolidacao por fatia falhou antes de publicar: {}", e.getMessage());
        }

        TelemetriaResumo resumo = telemetria.gerarResumo(DiretorioBaseKronos.resolver("cache"));
        Path pastaMetrics = repo.resolve("metrics");
        Files.createDirectories(pastaMetrics);
        Path arquivo = pastaMetrics.resolve(NOME_ARQUIVO_DATASET);
        // O MESMO nó vai para o JSON e para o CSV: um só caminho de montagem, uma só sanitização.
        ObjectNode dataset = montarDatasetSanitizado(
            resumo, mapper, ambienteExecucao.detectar(propriedades.hardware()));
        // Pretty-print proposital: o arquivo é lido por humanos no GitHub.
        mapper.writerWithDefaultPrettyPrinter().writeValue(arquivo.toFile(), dataset);
        log.info("Dataset de telemetria gerado em {}", arquivo);

        int execucoesNovas = acumularExecucoes(pastaMetrics);
        int karaokeNovas = acumularExecucoesKaraoke(pastaMetrics);
        int operacoesPorFatia = publicarDatasetsPorFatia(pastaMetrics);
        int linhasCsv = publicarCsv(pastaMetrics, dataset);

        git(repo, TIMEOUT_GIT, "add", "README.md", "LICENSE", "metrics");
        String mensagemCommit = String.format(Locale.ROOT,
            "dataset: snapshot com %d episódios e %d operações (+%d execução(ões) no acervo, "
                + "+%d arquivo(s) de karaokê, %d operação(ões) por fatia, %d linha(s) em CSV)",
            resumo.totalEpisodios(), resumo.operacoes() != null ? resumo.operacoes().size() : 0,
            execucoesNovas, karaokeNovas, operacoesPorFatia, linhasCsv);
        ProcessoExternoUtil.Resultado commit = git(repo, TIMEOUT_GIT, "commit", "-m", mensagemCommit);
        boolean semMudancas = commit.codigoSaida() != 0
            && saida(commit).toLowerCase(Locale.ROOT).contains("nothing to commit");
        if (commit.codigoSaida() != 0 && !semMudancas) {
            throw new IOException("git commit falhou no repositório do dataset: " + resumir(saida(commit)));
        }
        String hash = saida(git(repo, TIMEOUT_GIT, "rev-parse", "--short", "HEAD")).trim();

        // Push sempre (mesmo sem commit novo): publica commits pendentes de
        // tentativas anteriores sem rede/sem repositório remoto criado.
        ProcessoExternoUtil.Resultado push = git(repo, TIMEOUT_REDE, "push");
        if (push.codigoSaida() != 0 && saida(push).contains("--set-upstream")) {
            push = git(repo, TIMEOUT_REDE, "push", "-u", "origin", "HEAD");
        }
        if (push.codigoSaida() != 0 && saida(push).contains("[rejected]")) {
            // Repositório remoto criado com README/commits próprios (caso real
            // de 2026-07-09): integra o histórico preferindo a versão LOCAL em
            // conflito — o gerador local é a fonte de verdade do dataset.
            git(repo, TIMEOUT_REDE, "pull", "--no-edit", "--allow-unrelated-histories", "-X", "ours", "origin", "main");
            push = git(repo, TIMEOUT_REDE, "push", "-u", "origin", "HEAD");
        }
        boolean pushOk = push.codigoSaida() == 0;
        if (!pushOk) {
            log.warn("git push do dataset falhou: {}", saida(push));
        }

        String mensagem = montarMensagem(semMudancas, pushOk, hash, saida(push));
        return new ResultadoPublicacao(repo.toString(), semMudancas ? "sem mudanças" : hash, pushOk, mensagem);
    }

    private String montarMensagem(boolean semMudancas, boolean pushOk, String hash, String saidaPush) {
        if (pushOk) {
            return semMudancas
                ? "Dataset já estava atualizado — nenhum commit novo; push confirmado."
                : "Dataset publicado no repositório dedicado (commit " + hash + ").";
        }
        String dica = saidaPush != null && (saidaPush.contains("not found") || saidaPush.contains("does not exist"))
            ? " Crie o repositório \"" + nomeRepositorioRemoto() + "\" no GitHub e publique novamente."
            : "";
        return (semMudancas ? "Dataset já estava atualizado" : "Commit " + hash + " criado localmente")
            + ", mas o push falhou: " + resumir(saidaPush) + dica;
    }

    private String nomeRepositorioRemoto() {
        String remoto = propriedades.repositorioRemoto();
        if (remoto == null || remoto.isBlank()) {
            return "kronos-anime-translation-telemetry-dataset";
        }
        String semSufixo = remoto.replaceFirst("\\.git$", "");
        int barra = semSufixo.lastIndexOf('/');
        return barra >= 0 ? semSufixo.substring(barra + 1) : semSufixo;
    }

    /** Clona o remoto configurado ou inicializa um repositório novo com o remoto associado. */
    private void prepararRepositorio(Path repo) throws IOException {
        if (Files.isDirectory(repo.resolve(".git"))) {
            return;
        }
        String remoto = propriedades.repositorioRemoto();
        if (remoto != null && !remoto.isBlank()) {
            log.info("Repositório do dataset não existe em {}; clonando {}", repo, remoto);
            ProcessoExternoUtil.Resultado clone = executarGit(
                List.of("git", "clone", remoto, repo.toString()), TIMEOUT_REDE);
            if (clone.codigoSaida() == 0) {
                return;
            }
            log.warn("Clone do dataset falhou ({}); inicializando repositório local novo.", resumir(saida(clone)));
        }
        Files.createDirectories(repo);
        ProcessoExternoUtil.Resultado init = git(repo, TIMEOUT_GIT, "init", "-b", "main");
        if (init.codigoSaida() != 0) {
            throw new IOException("git init falhou em " + repo + ": " + resumir(saida(init)));
        }
        if (remoto != null && !remoto.isBlank()) {
            git(repo, TIMEOUT_GIT, "remote", "add", "origin", remoto);
        }
    }

    /**
     * Bootstrap do repositório na primeira publicação: README com formato dos
     * dados e declaração de anonimização (LGPD/GDPR) + LICENSE (MIT) — os três
     * itens que a comunidade procura primeiro num repositório de dataset.
     */
    private void garantirDocumentosBase(Path repo) throws IOException {
        // O README é REESCRITO a cada publicação, não criado só uma vez. Ele descreve o formato
        // dos dados e a política de privacidade — se ficar congelado na primeira publicação,
        // passa a descrever um dataset que não existe mais. Prejuízo real: em 10/08/2026 o README
        // ainda afirmava "não publica texto de legenda" enquanto o acervo de execuções publicava
        // trecho de fala em 1.238 das 1.547 linhas. Documento gerado que não se atualiza vira
        // afirmação falsa assinada pelo projeto.
        Files.writeString(repo.resolve("README.md"), README_DATASET, StandardCharsets.UTF_8);
        Path licenca = repo.resolve("LICENSE");
        if (!Files.exists(licenca)) {
            Files.writeString(licenca, textoLicencaMit(), StandardCharsets.UTF_8);
        }
    }

    /**
     * PROPÓSITO DE NEGÓCIO: monta o snapshot sanitizado por episódio e operação.
     *
     * <p>INVARIANTES DO DOMÍNIO: não inclui
     * textos de fala (avisos viram {@code quantidadeAvisos}) e sem caminhos de
     * máquina ({@code detalhe} descartado; episódio reduzido ao nome do arquivo).
     *
     * <p>COMPORTAMENTO EM CASO DE FALHA: ambiente ausente é simplesmente omitido;
     * métricas obrigatórias continuam sendo serializadas no formato atual.
     */
    static ObjectNode montarDatasetSanitizado(TelemetriaResumo resumo, ObjectMapper mapper) {
        return montarDatasetSanitizado(resumo, mapper, null);
    }

    /**
     * PROPÓSITO DE NEGÓCIO: serializa métricas e a fotografia automática do host
     * no schema público vigente do dataset.
     *
     * <p>INVARIANTES DO DOMÍNIO: formato 2 não contém override manual de hardware,
     * textos de legenda ou caminhos locais.
     *
     * <p>COMPORTAMENTO EM CASO DE FALHA: ambiente nulo é omitido e o restante do
     * snapshot permanece válido.
     */
    static ObjectNode montarDatasetSanitizado(
            TelemetriaResumo resumo,
            ObjectMapper mapper,
            AmbienteExecucaoDataset ambienteExecucao) {
        ObjectNode root = mapper.createObjectNode();
        root.put("dataset", "kronos-anime-translation-telemetry-dataset");
        root.put("versaoFormato", 2);
        root.put("geradoEm", Instant.now().toString());
        root.put("descricao", "Métricas operacionais de tradução de legendas de anime com LLM 100% local "
            + "(LM Studio). Sem textos de legenda e sem caminhos de máquina — apenas métricas.");

        adicionarAmbienteExecucao(root, ambienteExecucao);

        ObjectNode agregado = root.putObject("resumo");
        agregado.put("totalEpisodiosTraduzidos", resumo.totalEpisodios());
        agregado.put("totalLinhasTraduzidas", resumo.totalLinhas());
        agregado.put("tempoMedioPorLinhaMs", resumo.tempoMedioPorLinhaMs());
        agregado.put("totalFalasReaproveitadasDoCache", resumo.totalCacheHits());
        agregado.put("alucinacoesLlmPrevenidas", resumo.alucinacoesPrevenidas());
        agregado.put("respostasTraducaoRejeitadas", resumo.respostasTraducaoRejeitadas());
        agregado.put("falhasTraducaoRecuperadas", resumo.falhasTraducaoRecuperadas());
        agregado.put("fallbacksTraducaoMantidos", resumo.fallbacksTraducaoMantidos());
        agregado.put("arquivosRenomeados", resumo.arquivosSanitizados());
        agregado.put("totalOperacoesRegistradas", resumo.operacoes() != null ? resumo.operacoes().size() : 0);

        ArrayNode traducoes = root.putArray("traducoesLlm");
        if (resumo.traducoesLlm() != null) {
            for (LlmTelemetria t : resumo.traducoesLlm()) {
                ObjectNode item = traducoes.addObject();
                item.put("episodio", apenasNomeDeArquivo(t.nomeEpisodio()));
                item.put("anime", t.animeNome());
                item.put("temporada", t.temporada());
                item.put("modeloLlm", t.modeloLlm());
                item.put("totalLinhas", t.totalLinhas());
                item.put("falasTraduzidas", t.falasTraduzidas());
                item.put("falasDoCache", t.falasDoCache());
                item.put("tempoTotalMs", t.tempoTotalMs());
                item.put("quantidadeAvisos", contarAvisos(t.errosOcorridos()));
                item.put("registradoEm", t.registradoEm());
                // null CHEGA AO DATASET COMO null, nao como 0: "nao medi" e "medi e deu
                // zero" sao respostas diferentes, e trocar uma pela outra e o que torna um
                // historico importado inutil para comparar epocas.
                item.put("falasItalicoRemovido", t.falasItalicoRemovido());
                item.put("falasItalicoPreservado", t.falasItalicoPreservado());
            }
        }

        ArrayNode operacoes = root.putArray("operacoes");
        if (resumo.operacoes() != null) {
            for (OperacaoTelemetria op : resumo.operacoes()) {
                ObjectNode item = operacoes.addObject();
                item.put("tipo", op.tipo());
                item.put("tempoTotalMs", op.tempoTotalMs());
                item.put("arquivosProcessados", op.arquivosProcessados());
                item.put("itensDetectados", op.itensDetectados());
                item.put("itensCorrigidos", op.itensCorrigidos());
                item.put("registradoEm", op.registradoEm());
            }
        }
        return root;
    }

    /**
     * PROPÓSITO DE NEGÓCIO: adiciona ao JSON a fotografia coerente do computador
     * responsável pela geração do snapshot.
     *
     * <p>INVARIANTES DO DOMÍNIO: GPU principal pertence à lista detectada e campos
     * ausentes não são inventados nem herdados de configuração.
     *
     * <p>COMPORTAMENTO EM CASO DE FALHA: ambiente nulo não cria o bloco; lista de
     * GPUs vazia é publicada como array vazio.
     */
    private static void adicionarAmbienteExecucao(ObjectNode root, AmbienteExecucaoDataset ambiente) {
        if (ambiente == null) {
            return;
        }
        ObjectNode node = root.putObject("ambienteExecucao");
        putIfPresent(node, "fabricante", ambiente.fabricante());
        putIfPresent(node, "modeloMaquina", ambiente.modeloMaquina());
        putIfPresent(node, "cpu", ambiente.cpu());
        putIfPresent(node, "gpuPrincipal", ambiente.gpuPrincipal());
        ArrayNode gpus = node.putArray("gpusDetectadas");
        ambiente.gpusDetectadas().forEach(gpus::add);
        if (ambiente.ramTotalGb() != null) {
            node.put("ramTotalGb", ambiente.ramTotalGb());
        }
        putIfPresent(node, "sistemaOperacional", ambiente.sistemaOperacional());
        putIfPresent(node, "arquitetura", ambiente.arquitetura());
        node.put("hardwareColetadoAutomaticamente", ambiente.hardwareColetadoAutomaticamente());
    }

    private static void putIfPresent(ObjectNode node, String campo, String valor) {
        if (valor != null && !valor.isBlank()) {
            node.put(campo, valor);
        }
    }

    private static final java.util.regex.Pattern MARCADOR_AVISOS_OMITIDOS =
        java.util.regex.Pattern.compile("\\(\\+(\\d+) avisos omitidos");

    /**
     * Conta os avisos REAIS do episódio: a telemetria canônica guarda no
     * máximo 30 avisos + uma linha-resumo "(+N avisos omitidos...)" (ver
     * {@link TelemetriaService}); aqui o total é reconstituído para o dataset
     * não subnotificar a métrica de qualidade.
     */
    static int contarAvisos(List<String> avisos) {
        if (avisos == null || avisos.isEmpty()) {
            return 0;
        }
        var matcher = MARCADOR_AVISOS_OMITIDOS.matcher(avisos.getLast());
        if (matcher.find()) {
            return avisos.size() - 1 + Integer.parseInt(matcher.group(1));
        }
        return avisos.size();
    }

    static String apenasNomeDeArquivo(String nome) {
        if (nome == null) {
            return null;
        }
        String normalizado = nome.replace('\\', '/');
        int barra = normalizado.lastIndexOf('/');
        return barra >= 0 ? normalizado.substring(barra + 1) : nome;
    }

    private ProcessoExternoUtil.Resultado git(Path repo, Duration timeout, String... argumentos) throws IOException {
        List<String> comando = new ArrayList<>(List.of("git", "-C", repo.toString()));
        comando.addAll(List.of(argumentos));
        return executarGit(comando, timeout);
    }

    private ProcessoExternoUtil.Resultado executarGit(List<String> comando, Duration timeout) throws IOException {
        try {
            return ProcessoExternoUtil.executar(comando, timeout, true);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Publicação do dataset interrompida.", e);
        } catch (java.util.concurrent.TimeoutException e) {
            throw new IOException("Comando git excedeu o tempo limite: " + String.join(" ", comando), e);
        }
    }

    private static String saida(ProcessoExternoUtil.Resultado resultado) {
        return new String(resultado.stdout(), StandardCharsets.UTF_8);
    }

    private static String resumir(String texto) {
        String plano = texto == null ? "" : texto.replaceAll("\\s+", " ").trim();
        return plano.length() > 180 ? plano.substring(0, 177) + "..." : plano;
    }

    private static String textoLicencaMit() {
        return "MIT License\n\n"
            + "Copyright (c) " + Year.now() + " Paulo André Carminati\n\n"
            + "Permission is hereby granted, free of charge, to any person obtaining a copy\n"
            + "of this software and associated documentation files (the \"Software\"), to deal\n"
            + "in the Software without restriction, including without limitation the rights\n"
            + "to use, copy, modify, merge, publish, distribute, sublicense, and/or sell\n"
            + "copies of the Software, and to permit persons to whom the Software is\n"
            + "furnished to do so, subject to the following conditions:\n\n"
            + "The above copyright notice and this permission notice shall be included in all\n"
            + "copies or substantial portions of the Software.\n\n"
            + "THE SOFTWARE IS PROVIDED \"AS IS\", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR\n"
            + "IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,\n"
            + "FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE\n"
            + "AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER\n"
            + "LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,\n"
            + "OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE\n"
            + "SOFTWARE.\n";
    }

    private static final String README_DATASET = """
        # KRONOS CORE — Telemetry Dataset

        [English](#english) | [Português](#portugues)

        <a id="english"></a>

        ## English

        Operational telemetry dataset from [KRONOS CORE](https://github.com/carmipa/traducao_animes_llm_local_quarkus), an anime subtitle translation pipeline that runs local LLM inference through LM Studio.

        Source translator project: [carmipa/traducao_animes_llm_local_quarkus](https://github.com/carmipa/traducao_animes_llm_local_quarkus)

        This repository is meant to expose reproducible performance and pipeline metrics, not subtitle content. Each commit is a dataset snapshot; the Git history is the public timeline.

        ### Repository Layout

        ```text
        ├── README.md
        ├── LICENSE
        └── metrics/
            ├── kronos-telemetria-dataset.json     # snapshot (latest state per episode)
            ├── kronos-telemetria-execucoes.jsonl  # append-only archive, one line per run
            ├── kronos-karaoke-execucoes.jsonl     # append-only, one line per karaoke subtitle file
            ├── fatias/                            # per-module consolidated metrics
            └── csv/                               # same data, tabular
                ├── kronos-resumo.csv
                ├── kronos-ambiente-execucao.csv
                ├── kronos-traducoes-llm.csv
                ├── kronos-operacoes.csv
                ├── kronos-execucoes.csv
                ├── kronos-avisos.csv
                ├── kronos-karaoke.csv
                └── kronos-karaoke-avisos.csv
        ```

        ### CSV Files

        Every metric published as JSON is also published as CSV, generated from the **same
        in-memory node** — the two formats cannot drift apart.

        - **Encoding** UTF-8, no BOM · **separator** `,` · **quoting** RFC 4180 (embedded quotes
          doubled) · **line ending** LF.
        - Real line breaks inside a field are written as the two-character text `\n`, so one
          physical line is always one record.
        - `kronos-avisos.csv` is tidy data: **one row per warning**, joinable back to a run by
          `registradoEm` + `nomeEpisodio`.
        - `kronos-karaoke.csv` is the **song-lyric pipeline**, one row per subtitle FILE — a
          different unit from the dialogue tables, because one karaoke run sweeps a whole folder.
          `desfechoArquivo` has three values on purpose: `TRADUZIDO`, `FALHOU` and `NAO_ALCANCADO`
          (the run died before reaching any file). Its own counters — `preservadasOriginalJapones`,
          `efeitosKfxPreservados`, `acentosRepostos`, `entradasCacheDescartadas` — do not exist in
          the dialogue schema. `kronos-karaoke-avisos.csv` is its tidy warning table, joinable by
          `registradoEm` + `arquivo`.
        - `origemDoRegistro` diz **quais campos de uma linha de karaokê significam alguma coisa**.
          Linha `EXECUCAO` foi medida por uma execução ao vivo e tem todos os campos preenchidos.
          Linha `MANIFESTO_HISTORICO` foi reconstruída de manifestos de auditoria escritos antes
          desses campos existirem, então `statusExecucao`, `estadoDicionario`, `acentosRepostos` e
          `entradasCacheDescartadas` voltam vazios **porque nada os mediu** — não porque o valor
          era zero. Filtre por esta coluna em vez de adivinhar.
        - `origemDoRegistro` tells you **which fields of a karaoke row mean anything**.
          `EXECUCAO` rows were measured by a live run and every field is populated.
          `MANIFESTO_HISTORICO` rows were rebuilt from audit manifests written before those
          fields existed, so `statusExecucao`, `estadoDicionario`, `acentosRepostos` and
          `entradasCacheDescartadas` come back empty **because nothing measured them** — not
          because the value was zero. Filter on this column instead of guessing.

        Opening in Excel: import as UTF-8 / comma-separated instead of double-clicking, otherwise
        accented characters and comma-bearing titles are misread.

        ### Data Format

        The dataset uses a custom UTF-8 JSON format with `versaoFormato` for schema evolution.

        #### `ambienteExecucao`

        Safe execution-environment metadata for benchmark context.

        | Field | Meaning |
        |-------|---------|
        | `fabricante` / `modeloMaquina` | Generic manufacturer and machine model reported by the OS |
        | `cpu` | Public CPU name |
        | `gpuPrincipal` | Dedicated GPU selected automatically from the current machine |
        | `gpusDetectadas` | All GPUs reported by the current operating system/driver |
        | `ramTotalGb` | Rounded total physical RAM in GB |
        | `sistemaOperacional` / `arquitetura` | Runtime platform, without username, hostname, paths, IPs or device IDs |
        | `hardwareColetadoAutomaticamente` | Whether the values were collected automatically from the local system |

        #### `resumo`

        Aggregate metrics.

        | Field | Meaning |
        |-------|---------|
        | `totalEpisodiosTraduzidos` | Episodes processed by the LLM translation pipeline |
        | `totalLinhasTraduzidas` | Subtitle dialogue lines translated |
        | `tempoMedioPorLinhaMs` | Average translation latency per dialogue line |
        | `totalFalasReaproveitadasDoCache` | Dialogue lines resolved from persistent cache without another LLM call |
        | `alucinacoesLlmPrevenidas` | LLM responses rejected by anti-hallucination guards |
        | `respostasTraducaoRejeitadas` | Invalid model attempts rejected before persistence |
        | `falhasTraducaoRecuperadas` | Lines recovered by a later validated retry |
        | `fallbacksTraducaoMantidos` | Distinct lines still pending after retry exhaustion |
        | `arquivosRenomeados` | Files normalized by the rename module |
        | `totalOperacoesRegistradas` | Recorded pipeline operations across modules |

        #### `traducoesLlm[]`

        Per-episode LLM translation metrics.

        | Field | Meaning |
        |-------|---------|
        | `episodio` | Subtitle filename only, without directories |
        | `anime` / `temporada` | Work and season |
        | `modeloLlm` | Local model id reported by LM Studio |
        | `totalLinhas` / `falasTraduzidas` / `falasDoCache` | Workload and translation source |
        | `tempoTotalMs` | Total episode translation duration |
        | `falasItalicoRemovido` | Lines whose italic was stripped by the italic rule (null = not measured) |
        | `falasItalicoPreservado` | Lines the rule ABSTAINED from: italic inherited from the `Style:` header |
        | `quantidadeAvisos` | Count of quality warnings, without warning text |
        | `registradoEm` | UTC ISO-8601 timestamp |

        #### `operacoes[]`

        Generic pipeline-operation metrics: `tipo`, `tempoTotalMs`, `arquivosProcessados`, `itensDetectados`, `itensCorrigidos`, `registradoEm`.

        This covers remuxing, subtitle extraction, lore/review steps, karaoke processing, file renaming and audits.

        ### Privacy And Anonymization

        This dataset does not publish local machine paths, usernames, hostnames, IP addresses, MAC addresses, serial numbers, device identifiers, credentials, tokens or API keys.

        **Subtitle excerpts are published, deliberately and only in the warning tables.** Pipeline warnings in `kronos-telemetria-execucoes.jsonl` / `metrics/csv/kronos-avisos.csv` (dialogue) and `kronos-karaoke-execucoes.jsonl` / `metrics/csv/kronos-karaoke-avisos.csv` (song lyrics) quote the subtitle line that triggered the failure — for example a line kept untranslated because the model corrupted its ASS tags. Without the line itself, the failure cannot be studied or reproduced, which is the point of publishing translation telemetry at all. The karaoke tables quote **song lyric lines** for the same reason and under the same rule: only the line that failed, never the full lyric.

        These are short diagnostic excerpts from fansub subtitle files, published for research into machine-translation failure modes. They are not a translated corpus and no complete subtitle file is redistributed. If you hold rights over a quoted line and want it removed, open an issue.

        The aggregate metrics (`kronos-telemetria-dataset.json` and the other CSVs) contain **no** subtitle text: warnings there are reduced to `quantidadeAvisos`.

        The only other public identifiers are release/work names, local LLM model ids and generic hardware metadata useful for benchmark interpretation.

        ### Generation

        The dataset is generated from the KRONOS CORE Telemetry panel through the **Publicar Dataset** button. KRONOS sanitizes the accumulated telemetry, writes `metrics/kronos-telemetria-dataset.json`, commits the snapshot and pushes it to this repository.

        ### License

        [MIT](LICENSE) — free use with attribution.

        <a id="portugues"></a>

        ## Português

        Dataset de telemetria operacional do [KRONOS CORE](https://github.com/carmipa/traducao_animes_llm_local_quarkus), uma esteira de tradução de legendas de anime que executa inferência LLM local via LM Studio.

        Projeto do tradutor: [carmipa/traducao_animes_llm_local_quarkus](https://github.com/carmipa/traducao_animes_llm_local_quarkus)

        Este repositório existe para expor métricas reprodutíveis de performance e pipeline, não conteúdo de legendas. Cada commit é um snapshot do dataset; o histórico Git é a linha do tempo pública.

        ### Estrutura Do Repositório

        ```text
        ├── README.md
        ├── LICENSE
        └── metrics/
            ├── kronos-telemetria-dataset.json     # foto (último estado por episódio)
            ├── kronos-telemetria-execucoes.jsonl  # acervo append-only, uma linha por execução
            ├── kronos-karaoke-execucoes.jsonl     # append-only, uma linha por arquivo de karaokê
            ├── fatias/                            # consolidado por módulo
            └── csv/                               # os mesmos dados, em tabela
                ├── kronos-resumo.csv
                ├── kronos-ambiente-execucao.csv
                ├── kronos-traducoes-llm.csv
                ├── kronos-operacoes.csv
                ├── kronos-execucoes.csv
                ├── kronos-avisos.csv
                ├── kronos-karaoke.csv
                └── kronos-karaoke-avisos.csv
        ```

        ### Arquivos CSV

        Toda métrica publicada em JSON é publicada também em CSV, gerada a partir do **mesmo nó em
        memória** — os dois formatos não têm como divergir.

        - **Codificação** UTF-8 sem BOM · **separador** `,` · **aspas** RFC 4180 (aspas internas
          duplicadas) · **fim de linha** LF.
        - Quebra de linha real dentro de campo é gravada como o texto `\n` de dois caracteres, para
          uma linha física ser sempre um registro.
        - `kronos-avisos.csv` é tidy data: **uma linha por aviso**, ligada à execução por
          `registradoEm` + `nomeEpisodio`.
        - `kronos-karaoke.csv` é o pipeline de **letra de música**, uma linha por ARQUIVO de
          legenda — unidade diferente das tabelas de diálogo, porque uma execução de karaokê varre
          a pasta inteira. `desfechoArquivo` tem três valores de propósito: `TRADUZIDO`, `FALHOU` e
          `NAO_ALCANCADO` (a execução morreu antes de alcançar arquivo nenhum). Os contadores
          próprios dela — `preservadasOriginalJapones`, `efeitosKfxPreservados`, `acentosRepostos`,
          `entradasCacheDescartadas` — não existem no schema do diálogo.
          `kronos-karaoke-avisos.csv` é a tabela tidy de avisos dela, ligada por
          `registradoEm` + `arquivo`.

        Abrindo no Excel: importe como UTF-8 / separado por vírgula em vez de dar duplo clique,
        senão acento e título com vírgula saem errados.

        ### Formato Dos Dados

        O dataset usa JSON próprio em UTF-8, com `versaoFormato` para evolução do schema.

        #### `ambienteExecucao`

        Metadados seguros do ambiente de execução para contextualizar benchmarks.

        | Campo | Significado |
        |-------|-------------|
        | `fabricante` / `modeloMaquina` | Fabricante e modelo genérico reportados pelo sistema operacional |
        | `cpu` | Nome público do processador |
        | `gpuPrincipal` | GPU dedicada selecionada automaticamente na máquina atual |
        | `gpusDetectadas` | Todas as GPUs reportadas pelo sistema operacional/driver atual |
        | `ramTotalGb` | RAM física total arredondada em GB |
        | `sistemaOperacional` / `arquitetura` | Plataforma de execução, sem usuário, hostname, caminhos, IPs ou IDs de dispositivo |
        | `hardwareColetadoAutomaticamente` | Indica se os valores foram coletados automaticamente do sistema local |

        #### `resumo`

        Métricas agregadas.

        | Campo | Significado |
        |-------|-------------|
        | `totalEpisodiosTraduzidos` | Episódios processados pelo pipeline de tradução LLM |
        | `totalLinhasTraduzidas` | Falas de legenda traduzidas |
        | `tempoMedioPorLinhaMs` | Latência média de tradução por fala |
        | `totalFalasReaproveitadasDoCache` | Falas resolvidas pelo cache persistente sem nova chamada ao LLM |
        | `alucinacoesLlmPrevenidas` | Respostas de LLM rejeitadas pelas guardas anti-alucinação |
        | `respostasTraducaoRejeitadas` | Tentativas inválidas rejeitadas antes da persistência |
        | `falhasTraducaoRecuperadas` | Falas recuperadas por tentativa posterior validada |
        | `fallbacksTraducaoMantidos` | Falas distintas ainda pendentes após esgotar tentativas |
        | `arquivosRenomeados` | Arquivos padronizados pelo módulo de renomeação |
        | `totalOperacoesRegistradas` | Operações de pipeline registradas entre os módulos |

        #### `traducoesLlm[]`

        Métricas de tradução LLM por episódio.

        | Campo | Significado |
        |-------|-------------|
        | `episodio` | Nome do arquivo de legenda, sem diretórios |
        | `anime` / `temporada` | Obra e temporada |
        | `modeloLlm` | Modelo local usado, conforme id reportado pelo LM Studio |
        | `totalLinhas` / `falasTraduzidas` / `falasDoCache` | Volume e origem das traduções |
        | `tempoTotalMs` | Duração total da tradução do episódio |
        | `falasItalicoRemovido` | Falas de que a regra do itálico tirou a tag (null = não medido) |
        | `falasItalicoPreservado` | Falas de que a regra SE ABSTEVE: o itálico vem do `Style:` do cabeçalho |
        | `quantidadeAvisos` | Contagem de avisos de qualidade, sem texto dos avisos |
        | `registradoEm` | Timestamp UTC em ISO-8601 |

        #### `operacoes[]`

        Métricas genéricas por operação de pipeline: `tipo`, `tempoTotalMs`, `arquivosProcessados`, `itensDetectados`, `itensCorrigidos`, `registradoEm`.

        Cobre remux, extração de legendas, revisões de lore/concordância, karaokê, renomeação de arquivos e auditorias.

        ### Privacidade E Anonimização

        Este dataset não publica caminhos locais da máquina, nomes de usuário, hostnames, endereços IP, endereços MAC, números de série, identificadores de dispositivo, credenciais, tokens ou chaves de API.

        **Trechos de legenda SÃO publicados, deliberadamente e só nas tabelas de aviso.** Os avisos do pipeline, em `kronos-telemetria-execucoes.jsonl` / `metrics/csv/kronos-avisos.csv` (diálogo) e em `kronos-karaoke-execucoes.jsonl` / `metrics/csv/kronos-karaoke-avisos.csv` (letra de música), citam a fala que provocou a falha — por exemplo uma linha mantida sem tradução porque o modelo corrompeu as tags ASS. Sem a fala, a falha não pode ser estudada nem reproduzida, que é a razão de publicar telemetria de tradução. As tabelas de karaokê citam **linha de letra de música** pelo mesmo motivo e sob a mesma regra: só a linha que falhou, nunca a letra inteira.

        São trechos curtos de diagnóstico, vindos de legendas de fansub, publicados para pesquisa de modos de falha em tradução automática. Não constituem corpus traduzido e nenhum arquivo de legenda completo é redistribuído. Se você detém direitos sobre uma fala citada e quer removê-la, abra uma issue.

        As métricas agregadas (`kronos-telemetria-dataset.json` e os demais CSVs) **não** contêm texto de legenda: ali os avisos viram apenas `quantidadeAvisos`.

        Fora isso, os únicos identificadores públicos são nomes de obras/releases, ids de modelos LLM locais e metadados genéricos de hardware úteis para interpretar benchmarks.

        ### Geração

        O dataset é gerado pelo painel de Telemetria do KRONOS CORE através do botão **Publicar Dataset**. O KRONOS sanitiza a telemetria acumulada, escreve `metrics/kronos-telemetria-dataset.json`, commita o snapshot e faz push para este repositório.

        ### Licença

        [MIT](LICENSE) — uso livre com atribuição.
        """;
}
