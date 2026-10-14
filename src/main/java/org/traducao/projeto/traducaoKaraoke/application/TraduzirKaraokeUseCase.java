package org.traducao.projeto.traducaoKaraoke.application;

import io.quarkus.runtime.Startup;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.traducao.projeto.core.util.DuracaoUtil;
import org.traducao.projeto.telemetria.TelemetriaService;
import org.traducao.projeto.qualidadeTraducao.application.ValidadorTraducaoService;
import org.traducao.projeto.llm.domain.Lote;
import org.traducao.projeto.llm.domain.StatusLlm;
import org.traducao.projeto.llm.domain.TraducaoLote;
import org.traducao.projeto.qualidadeTraducao.domain.AlucinacaoDetectadaException;
import org.traducao.projeto.qualidadeTraducao.domain.MarcadorPerdidoException;
import org.traducao.projeto.legenda.domain.DocumentoLegenda;
import org.traducao.projeto.legenda.domain.EventoLegenda;
import org.traducao.projeto.llm.domain.LlmPort;
import org.traducao.projeto.cachetraducao.infrastructure.CacheTraducaoService;
import org.traducao.projeto.cachetraducao.domain.EntradaCache;
import org.traducao.projeto.cachetraducao.domain.ProvenienciaCache;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.traducao.projeto.lore.domain.SnapshotContexto;
import org.traducao.projeto.lore.infrastructure.GerenciadorContexto;
import org.traducao.projeto.legenda.infrastructure.EscritorLegendaAss;
import org.traducao.projeto.legenda.infrastructure.LeitorLegendaAss;
import org.traducao.projeto.qualidadeTraducao.application.MascaradorTags;
import org.traducao.projeto.core.presentation.web.LogStreamService;
import org.traducao.projeto.traducaoKaraoke.domain.AcentosLetraKaraoke;
import org.traducao.projeto.traducaoKaraoke.domain.ClasseLinhaKaraoke;
import org.traducao.projeto.traducaoKaraoke.domain.DesfechoKaraoke;
import org.traducao.projeto.traducaoKaraoke.domain.FalhaArquivoKaraoke;
import org.traducao.projeto.traducaoKaraoke.domain.StatusExecucaoKaraoke;
import org.traducao.projeto.core.texto.FonteVietnamitaLegada;
import org.traducao.projeto.core.texto.TextoSemTags;
import org.traducao.projeto.traducaoKaraoke.domain.GradienteKaraoke;
import org.traducao.projeto.traducaoKaraoke.domain.ResultadoTraducaoKaraoke;
import org.traducao.projeto.traducaoKaraoke.domain.SinaisDeKaraoke;
import org.traducao.projeto.traducaoKaraoke.domain.TraducaoKaraokeException;
import org.traducao.projeto.traducaoKaraoke.infrastructure.TraducaoKaraokePersistencia;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

/**
 * Traduz as LETRAS DE MÚSICA de legendas .ass para PT-BR mantendo a letra
 * original junto na tela: a camada japonesa/romaji é preservada intacta
 * (mesmo com inglês misturado pelo cantor) e apenas a camada de tradução em
 * inglês vai ao LLM — resultado final: romaji em cima, PT-BR embaixo, nos
 * mesmos tempos do arquivo original.
 * <p>
 * Garantias (mesmo contrato do Karaokê Simples):
 * <ul>
 *   <li>Os arquivos de entrada NUNCA são alterados — a saída vai para a pasta
 *       irmã {@code <entrada>-karaoke-ptbr}, com o mesmo nome de arquivo.</li>
 *   <li>Diálogo/placas/efeitos KFX são reemitidos sem nenhuma alteração.</li>
 *   <li>Falha ou alucinação do LLM numa linha mantém a linha original e
 *       sinaliza aviso — nunca derruba o arquivo inteiro.</li>
 *   <li>Traduções ficam em cache JSON por arquivo (editável manualmente),
 *       reaproveitado em reexecuções, no mesmo padrão da Tradução Local.</li>
 * </ul>
 */
/*
 * CRIAÇÃO ANTECIPADA (@Startup), e não preferência de estilo: um bean
 * @ApplicationScoped só é instanciado na PRIMEIRA chamada, e é nesse momento que os
 * @ConfigProperty abaixo são resolvidos. O endpoint /simular despacha por
 * CompletableFuture.runAsync SEM executor, ou seja, no ForkJoinPool.commonPool — cujas
 * threads são globais da JVM e não carregam o class loader do Quarkus. Numa aplicação
 * recém-iniciada, o Dry-Run era a primeira chamada, a instanciação caía naquela thread e
 * morria com "SRCFG00015: No configuration is available for this class loader".
 *
 * O defeito só aparecia no Dry-Run: /traduzir passa pela FilaExecucaoPipeline, cujas
 * threads têm o contexto certo, então bastava traduzir uma vez para o bean nascer bom e o
 * simulador passar a funcionar — o que fazia o erro parecer intermitente.
 *
 * Criar no boot resolve na origem: a instanciação acontece na thread principal do Quarkus,
 * com configuração disponível, e nenhuma thread de execução volta a instanciar nada. O
 * construtor é vazio (só injeção de campo), então antecipar não executa trabalho nenhum.
 *
 * Isto NÃO revoga a decisão de rodar o /simular fora da fila: ela foi auditada e mantida
 * por ser read-only. Aquela auditoria perguntou se era SEGURO, não se FUNCIONAVA.
 */
@Startup
@ApplicationScoped
public class TraduzirKaraokeUseCase {

    private static final Logger log = LoggerFactory.getLogger(TraduzirKaraokeUseCase.class);

    public static final String CANAL_LOG = "traducao-karaoke";
    static final String SUFIXO_PASTA_SAIDA = "-karaoke-ptbr";
    private static final String SUBPASTA_CACHE = "karaoke";

    @Inject
    LeitorLegendaAss leitor;

    @Inject
    EscritorLegendaAss escritor;




    @Inject
    LlmPort llmPort;

    @Inject
    GerenciadorContexto gerenciadorContexto;

    @Inject
    ClassificadorLetraKaraokeService classificador;

    /**
     * Os TRÊS caminhos de envio ao LLM. Saíram daqui em 2026-08-19: eram 780 bytecodes e cinco
     * dependências dentro de um objeto que também classifica, grava cache e escreve arquivo.
     */
    @Inject
    TradutorDeLetraKaraoke tradutorDeLetra;

    /** Manifesto, relatorio, telemetria e console. Saiu daqui em 2026-08-19. */
    @Inject
    RegistroDaExecucao registro;

    /** Carregar e salvar o cache JSON por arquivo. Saiu daqui em 2026-08-19. */
    @Inject
    CacheDoArquivo cacheDoArquivo;

    /** Acento e empilhamento da letra original. Saiu daqui em 2026-08-19. */
    @Inject
    MontadorEventoFinal montador;

    /**
     * As fontes que viajam no vídeo de origem, para a linha portuguesa da música não sair com
     * caractere de reserva (12/10/2026). Nulo em teste de unidade: a fonte fica NÃO VERIFICADA e
     * nenhuma é trocada.
     */
    @Inject
    org.traducao.projeto.traducaoKaraoke.domain.ports.FontesEmbutidasPort fontesEmbutidas;


    @Inject
    LogStreamService logStream;

    @Inject
    TelemetriaService telemetriaService;

    @Inject
    TraducaoKaraokePersistencia persistencia;

    /**
     * Os três dicionários (português, inglês, romaji) decidem se uma linha da camada ORIGINAL é
     * toda inglês e deve virar português (regra de Paulo, 24/09/2026). Nulo em teste de unidade: o
     * plano sai como antes.
     */
    @Inject
    org.traducao.projeto.core.texto.dicionarioOrtografia.CorretorOrtograficoLegenda corretorOrtografico;

    @ConfigProperty(name = "tradutor.idioma-original")
    Optional<String> idiomaOriginal;

    @ConfigProperty(name = "tradutor.idioma-traduzido")
    Optional<String> idiomaTraduzido;


    public List<ResultadoTraducaoKaraoke> simular(Path pastaOrigem, String contextoId) {
        return executar(pastaOrigem, contextoId, false);
    }

    public List<ResultadoTraducaoKaraoke> aplicar(Path pastaOrigem, String contextoId) {
        return executar(pastaOrigem, contextoId, true);
    }

    /**
     * PROPÓSITO DE NEGÓCIO: motor único da tradução de karaokê, compartilhado por
     * {@code simular} (dry-run, read-only) e {@code aplicar} (grava e chama o LLM) — lista as
     * legendas, classifica cada linha e produz o resumo por arquivo.
     *
     * <p>INVARIANTES DO DOMÍNIO: só o modo {@code gravar} verifica o LLM, congela o contexto
     * escolhido e escreve saída/cache; a simulação não toca estado global nem a GPU/LLM. O
     * contador de lotes ({@code sequencialLote}) é local a esta execução, nunca campo de
     * instância, para não ser perturbado por uma execução concorrente deste bean singleton.
     *
     * <p>COMPORTAMENTO EM CASO DE FALHA: pasta inexistente lança
     * {@link TraducaoKaraokeException}; falha por arquivo é contabilizada e não interrompe os
     * demais; interrupção cooperativa entre arquivos preserva o que já foi gravado.
     */
    private List<ResultadoTraducaoKaraoke> executar(Path pastaOrigem, String contextoId, boolean gravar) {
        long inicioMs = System.currentTimeMillis();
        String modo = gravar ? "Tradução" : "Simulação (Dry-Run)";
        logStream.publicarLog(CANAL_LOG, "==== Tradução de Karaokê — " + modo + " ====");
        logStream.publicarLog(CANAL_LOG, "Pasta das legendas: " + pastaOrigem);

        if (pastaOrigem == null || !Files.isDirectory(pastaOrigem)) {
            throw new TraducaoKaraokeException("A pasta informada não existe ou não é um diretório: " + pastaOrigem);
        }
        Path pastaDestino = resolverPastaSaida(pastaOrigem);
        logStream.publicarLog(CANAL_LOG, "Pasta de destino: " + pastaDestino);

        List<ResultadoTraducaoKaraoke> resultados = new ArrayList<>();
        List<FalhaArquivoKaraoke> falhas = new ArrayList<>();
        // Holder porque a informação nasce lá dentro, em carregarCache, e precisa subir até o
        // manifesto: proveniência divergente significa que TUDO foi retraduzido, e sem esse sinal
        // um pico de tempo e de custo fica sem explicação no histórico.
        java.util.concurrent.atomic.AtomicBoolean cacheIgnorado =
            new java.util.concurrent.atomic.AtomicBoolean(false);
        StatusExecucaoKaraoke status = StatusExecucaoKaraoke.COMPLETA;
        String motivo = null;
        SnapshotContexto contextoJob = null;
        ProvenienciaCache proveniencia = null;
        boolean houveLinhaCorrigivel = false;

        try {
            List<Path> arquivos = listarLegendas(pastaOrigem);
            if (arquivos.isEmpty()) {
                // Sem .ass/.ssa nao ha o que fazer — e a pasta de destino NAO e criada: ate 09/10/2026
                // ela nascia vazia ao lado da traducao, e o console fechava com [SUCESSO]. No teste
                // ponta a ponta daquele dia isso aconteceu com o Reconguista, que so tem legenda SRT.
                logStream.publicarLog(CANAL_LOG, "Nenhum arquivo .ass/.ssa encontrado na pasta.");
                long srts = contarSrt(pastaOrigem);
                if (srts > 0) {
                    logStream.publicarLog(CANAL_LOG, "[AVISO] A pasta tem " + srts + " legenda(s) .srt. SRT nao tem "
                        + "estilo nem efeito, entao nao ha camada de karaoke para traduzir: a letra, se houver, "
                        + "ja foi traduzida como fala comum pela Traducao Local.");
                }
                return List.of();
            }
            logStream.publicarLog(CANAL_LOG, "Arquivos de legenda encontrados: " + arquivos.size());

            if (gravar) {
                try {
                    Files.createDirectories(pastaDestino);
                } catch (IOException e) {
                    throw new TraducaoKaraokeException("Não foi possível criar a pasta de destino: " + pastaDestino, e);
                }
            }

            if (gravar) {
                StatusLlm statusLlm = llmPort.verificarDisponibilidade();
                if (statusLlm == null || !statusLlm.modeloCarregado()) {
                    throw new TraducaoKaraokeException("Servidor LLM indisponível: "
                        + (statusLlm != null ? statusLlm.mensagem() : "sem resposta"));
                }
                logStream.publicarLog(CANAL_LOG, "[OK] Servidor LLM ativo.");
                contextoJob = congelarContexto(contextoId);
                proveniencia = provenienciaDe(contextoJob);
                logStream.publicarLog(CANAL_LOG, "[CONTEXTO CONGELADO] " + contextoJob.nomeExibicao()
                    + " | id=" + contextoJob.id() + " | hash=" + proveniencia.contextoHash());
            }

            // Contador de lotes local à execução: um use case @ApplicationScoped é
            // singleton, então um campo de instância seria compartilhado entre
            // execuções concorrentes (simular fora da fila + aplicar na fila).
            AtomicInteger sequencialLote = new AtomicInteger();
            for (Path arquivo : arquivos) {
                if (Thread.currentThread().isInterrupted()) {
                    status = StatusExecucaoKaraoke.INTERROMPIDA;
                    motivo = "Execução cancelada entre arquivos; o que já foi gravado está preservado.";
                    logStream.publicarLog(CANAL_LOG, "[INTERROMPIDO] " + motivo);
                    break;
                }
                try {
                    ResultadoTraducaoKaraoke r = processarArquivo(
                        arquivo, pastaDestino, gravar, sequencialLote, contextoJob, proveniencia,
                        cacheIgnorado);
                    resultados.add(r);
                    houveLinhaCorrigivel = houveLinhaCorrigivel
                        || r.traduzidas() + r.reaproveitadasCache() > 0;
                } catch (Exception e) {
                    // O arquivo que quebra NÃO pode sumir do manifesto: até 14/08/2026 ele virava
                    // só um contador e uma linha de console, e quem auditasse depois via 23 de 25
                    // sem nenhum vestígio dos outros dois.
                    falhas.add(new FalhaArquivoKaraoke(
                        arquivo.getFileName().toString(),
                        e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()));
                    log.error("Falha ao processar {}", arquivo, e);
                    logStream.publicarLog(CANAL_LOG, "[ERRO] " + arquivo.getFileName() + ": " + e.getMessage());
                }
            }
        } catch (RuntimeException e) {
            // ABORTADA é o "não verificou" desta fatia. A exceção segue para o chamador — mas
            // agora deixa artefato antes, no finally, em vez de existir só numa linha de log.
            status = StatusExecucaoKaraoke.ABORTADA;
            motivo = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            throw e;
        } finally {
            long duracaoMs = System.currentTimeMillis() - inicioMs;
            int musicas = resultados.stream().mapToInt(ResultadoTraducaoKaraoke::paraTraduzir).sum();
            int traduzidas = resultados.stream().mapToInt(ResultadoTraducaoKaraoke::traduzidas).sum();
            int doCache = resultados.stream().mapToInt(ResultadoTraducaoKaraoke::reaproveitadasCache).sum();
            int preservadas = resultados.stream().mapToInt(ResultadoTraducaoKaraoke::preservadasOriginalJapones).sum();

            logStream.publicarLog(CANAL_LOG, "==============================================================");
            logStream.publicarLog(CANAL_LOG, String.format(Locale.ROOT,
                "[%s] %s %s: %d arquivo(s), %d falha(s) | letras originais preservadas: %d | traduzíveis: %d (LLM: %d, cache: %d)",
                rotuloDoBanner(status, resultados.size(), falhas.size()),
                modo, status.name().toLowerCase(Locale.ROOT), resultados.size(), falhas.size(),
                preservadas, musicas, traduzidas, doCache));
            for (FalhaArquivoKaraoke f : falhas) {
                logStream.publicarLog(CANAL_LOG, "   [FALHOU] " + f.arquivo() + " — " + f.motivo());
            }
            logStream.publicarLog(CANAL_LOG, "==============================================================");
            logStream.publicarLog(CANAL_LOG, DuracaoUtil.linhaRelatorioFinal(
                gravar ? "Tradução de Karaokê (LLM)" : "Tradução de Karaokê (simulação)", inicioMs));

            // SEMPRE, e não só quando houve resultado: execução abortada com zero arquivo é
            // justamente a que mais precisa deixar rastro.
            if (gravar) {
                registro.registrar(
                    pastaOrigem, pastaDestino, resultados, duracaoMs, musicas, traduzidas + doCache,
                    contextoJob, proveniencia,
                    new DesfechoKaraoke(status, motivo, falhas, cacheIgnorado.get(),
                        estadoDoDicionario(houveLinhaCorrigivel)));
            }
        }
        return resultados;
    }

    /**
     * PROPÓSITO DE NEGÓCIO: escolhe a palavra que abre o banner final, para "não havia nada a
     * fazer" não sair como "[SUCESSO]" — o mesmo silêncio ambíguo que o {@link StatusExecucaoKaraoke}
     * existe para evitar, agora no console.
     * <p>INVARIANTES DO DOMÍNIO: SUCESSO só com execução completa, sem falha e com ao menos um
     * arquivo processado; zero arquivos e zero falhas numa execução completa é NADA A FAZER.
     * <p>COMPORTAMENTO EM CASO DE FALHA: qualquer outro caso é ATENÇÃO; nunca lança.
     */
    static String rotuloDoBanner(StatusExecucaoKaraoke status, int arquivos, int falhas) {
        if (status == StatusExecucaoKaraoke.COMPLETA && falhas == 0) {
            return arquivos > 0 ? "SUCESSO" : "NADA A FAZER";
        }
        return "ATENÇÃO";
    }

    /**
     * PROPÓSITO DE NEGÓCIO: conta as legendas SRT da pasta para explicar ao operador por que a
     * tradução de karaokê não tem o que fazer nela.
     * <p>INVARIANTES DO DOMÍNIO: só a própria pasta, sem descer em subpastas; só leitura.
     * <p>COMPORTAMENTO EM CASO DE FALHA: erro de leitura conta zero — a mensagem de aviso é que
     * deixa de sair, nunca a execução.
     */
    private static long contarSrt(Path pasta) {
        try (var arquivos = Files.list(pasta)) {
            return arquivos.filter(p -> p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".srt")).count();
        } catch (IOException e) {
            return 0;
        }
    }

    /**
     * PROPÓSITO DE NEGÓCIO: responde em TRÊS estados se a ortografia foi conferida nesta execução.
     *
     * <p>INVARIANTES DO DOMÍNIO: {@code disponivel()} do corretor é {@code false} tanto quando o
     * hunspell falta quanto quando ninguém perguntou nada — fundir os dois faria "0 acento
     * reposto" parecer sempre bom resultado. Por isso só vira AUSENTE/DISPONIVEL quando ALGUMA
     * linha chegou a passar pelo corretor.
     *
     * <p>COMPORTAMENTO EM CASO DE FALHA: sem corretor injetado, devolve NAO_CONSULTADO.
     */
    private DesfechoKaraoke.EstadoDicionario estadoDoDicionario(boolean houveLinhaCorrigivel) {
        if (!houveLinhaCorrigivel) {
            return DesfechoKaraoke.EstadoDicionario.NAO_CONSULTADO;
        }
        return montador.dicionarioDisponivel()
            ? DesfechoKaraoke.EstadoDicionario.DISPONIVEL
            : DesfechoKaraoke.EstadoDicionario.AUSENTE;
    }

    /**
     * PROPÓSITO DE NEGÓCIO: reprova a legenda cujo texto vai à tela em fonte vietnamita antiga —
     * "Não, é a atração!" sai "Nóo, ộ a atraỗóo!". O Gundam ZZ foi publicado assim em 09/10/2026 e a
     * 4.1 não acusou. Ordem de Paulo (14/10): reprova e pede a 1.3 Troca de Tipo de Legenda.
     *
     * <p>INVARIANTES DO DOMÍNIO: a regra é a única do kernel ({@link FonteVietnamitaLegada}); só conta
     * estilo usado por fala e {@code \fn} dentro de fala ({@code Comment:} e estilo sem fala não vão à
     * tela). Vale para simular e para aplicar.
     *
     * <p>COMPORTAMENTO EM CASO DE FALHA: com achado, lança {@link TraducaoKaraokeException} nomeando a
     * fonte e a 1.3; sem achado, não faz nada.
     */
    private void reprovarFonteVietnamitaAntiga(DocumentoLegenda documento) {
        List<FonteVietnamitaLegada.Fala> falas = documento.eventos().stream()
            .filter(EventoLegenda::isDialogo)
            .map(e -> new FonteVietnamitaLegada.Fala(e.estilo(), e.texto()))
            .toList();
        List<String> achados = FonteVietnamitaLegada.achados(documento.cabecalho(), falas);
        if (!achados.isEmpty()) {
            throw new TraducaoKaraokeException("REPROVADO: fonte vietnamita antiga na tela ("
                + String.join("; ", achados) + ") desenha outra letra no lugar do acento. Rode a 1.3 "
                + "Troca de Tipo de Legenda (Arial) nesta pasta e depois a 4.1 de novo.");
        }
    }

    /**
     * PROPÓSITO DE NEGÓCIO: processa uma legenda .ass — preserva letra original/japonesa e
     * KFX, reaproveita cache e (só em modo gravar) traduz a camada inglesa via LLM, montando
     * o resumo de classificação do arquivo.
     *
     * <p>INVARIANTES DO DOMÍNIO: o arquivo de entrada nunca é alterado; falas repetidas gastam
     * uma só tradução (dedup por texto original); em dry-run nenhuma linha vai ao LLM e nada é
     * gravado. O {@code sequencialLote} recebido é o contador local da execução, repassado ao
     * LLM para numerar os lotes desta run.
     *
     * <p>COMPORTAMENTO EM CASO DE FALHA: falha/alucinação do LLM numa linha mantém o texto
     * original e registra aviso, sem derrubar o arquivo; interrupção cooperativa encerra no
     * próximo ponto seguro preservando o que já foi resolvido. Legenda com fonte vietnamita antiga
     * no que vai à tela é REPROVADA antes de cache e LLM: lança {@link TraducaoKaraokeException}
     * pedindo a 1.3, o arquivo entra nas falhas do manifesto e nada é gravado.
     */
    private ResultadoTraducaoKaraoke processarArquivo(Path arquivo, Path pastaDestino, boolean gravar,
                                                       AtomicInteger sequencialLote,
                                                       SnapshotContexto contextoJob,
                                                       ProvenienciaCache proveniencia,
                                                       java.util.concurrent.atomic.AtomicBoolean cacheIgnorado) {
        String nome = arquivo.getFileName().toString();
        logStream.publicarLog(CANAL_LOG, "");
        logStream.publicarLog(CANAL_LOG, ">> " + nome);

        DocumentoLegenda documento = leitor.ler(arquivo);
        reprovarFonteVietnamitaAntiga(documento);
        Path arquivoCache = cacheDoArquivo.arquivoDe(arquivo);
        Map<String, String> cacheExistente =
            cacheDoArquivo.carregar(arquivoCache, gravar, proveniencia, cacheIgnorado);

        int kfx = 0;
        int originais = 0;
        int jaPt = 0;
        int paraTraduzir = 0;
        int doCache = 0;
        int traduzidas = 0;
        int semTraducao = 0;
        // TELEMETRIA DA FATIA: o defeito do acento tem de aparecer NA HORA, na fatia onde nasce.
        // Sem este número, as 5 falas do 86 que saíram com "nao" sem acento em 14/08/2026 só
        // apareceriam para quem lesse o .ass — e apareceram mesmo, semanas depois.
        int acentosRepostos = 0;
        List<String> avisos = new ArrayList<>();
        // Original -> traducao QUE VAI PARA O ARQUIVO (depois do acento), do LLM ou do cache. E
        // sobre ela que a palavra inventada e procurada, depois da ultima transformacao (A6).
        Map<String, String> traduzidasNoArquivo = new java.util.LinkedHashMap<>();

        // Traduções desta execução, deduplicadas por texto original (refrão
        // repetido gasta uma chamada de LLM só).
        Map<String, String> traducoes = new HashMap<>();
        // Dedup por texto VISÍVEL, para o caso em que a MESMA letra aparece em camadas com tags
        // diferentes. Sem isto a chave é o texto COM tags, e {\fad(100,100)}You old Earthlings
        // e {\fad(200,200)}You old Earthlings viram duas chaves — duas chamadas ao LLM e, pior,
        // duas traduções DIVERGENTES na tela. Observado em arquivo ZZ que estava na pasta de
        // saída usada pela execução de karaokê em 09/08/2026:
        //     You old Earthlings, => Vocês, velhos terráqueos,
        //     You old Earthlings, => Vocês, terrestres velhos
        // Só vale quando as tags estão na BORDA (TextoSemTags): com tag no meio a tradução de
        // uma camada não tem onde reencaixar os marcadores da outra.
        Map<String, String> traducaoPorTextoVisivel = new HashMap<>();
        List<EventoLegenda> eventosFinais = new ArrayList<>(documento.eventos().size());

        // Quais instantes JÁ têm uma camada original preservada (japonês/romaji). Precisa ser
        // calculado ANTES de emitir, porque a decisão de cada linha depende do arquivo inteiro.
        //
        // O PREJUÍZO que obriga isto, medido nos 22 do Unicorn em 13/08: nos episódios 13-22 o
        // encerramento tem UMA camada só (ED2, 23 linhas). Traduzir substituía o evento e a letra
        // original DESAPARECIA da tela — 22 de 23 linhas, em 10 episódios. Nos episódios 01-12
        // havia duas camadas (ED romaji + ED - EN inglês) e sobrava a outra por acaso, o que
        // mascarou o defeito: parecia preservação, era substituição com sorte.
        //
        // A DECISÃO inteira do arquivo é tomada de uma vez, e por outro objeto. Antes, este
        // método percorria o documento DUAS vezes chamando o classificador nas duas — no acervo,
        // 3,95 milhões de classificações onde 1,98 milhão basta. E o ganho maior nem é esse:
        // mexer no critério de música deixou de exigir tocar num método que também grava cache e
        // escreve arquivo. Ver PlanoDeClassificacao, inclusive quanto à ORDEM dos dois passes.
        PlanoDeClassificacao plano = PlanoDeClassificacao.montar(documento, classificador, corretorOrtografico);
        // A leitura das fontes do vídeo é PREGUIÇOSA: só acontece se alguma linha portuguesa
        // estiver numa fonte diferente da do diálogo. Vídeo de 500 MB, segundos de mkvextract.
        TipografiaDaLetraPortuguesa tipografia = TipografiaDaLetraPortuguesa.doArquivo(
            documento, plano, () -> lerFontesDoVideo(arquivo));
        // Frases de KFX palavra a palavra que ficaram só em português: as palavras do efeito
        // delas são silenciadas DEPOIS do laço, porque podem vir antes ou depois da frase.
        List<Integer> frasesKfxSoPortugues = new ArrayList<>();
        // Linhas que deixaram de empilhar o inglês porque o romaji está na tela em sílabas (86, 12/10).
        int inglesNaoEmpilhado = 0;

        List<EventoLegenda> eventos = documento.eventos();
        for (int posicao = 0; posicao < eventos.size(); posicao++) {
            EventoLegenda evento = eventos.get(posicao);
            ClasseLinhaKaraoke classe = plano.classeNaPosicao(posicao);

            switch (classe) {
                case FORA_DE_MUSICA -> eventosFinais.add(evento);
                case EFEITO_KFX -> {
                    kfx++;
                    eventosFinais.add(evento);
                }
                case ORIGINAL_JAPONES -> {
                    originais++;
                    eventosFinais.add(evento);
                    logStream.publicarLog(CANAL_LOG, "   [MÚSICA-JP] mantida: " + RegistroDaExecucao.visivelResumido(evento.texto()));
                }
                case JA_PORTUGUES -> {
                    jaPt++;
                    eventosFinais.add(evento);
                    logStream.publicarLog(CANAL_LOG, "   [MÚSICA-PT] já traduzida: " + RegistroDaExecucao.visivelResumido(evento.texto()));
                }
                case TRADUZIVEL_INGLES -> {
                    paraTraduzir++;
                    String original = evento.texto();
                    String traduzido = traducoes.get(original);
                    boolean veioDoCache = false;

                    // Mesma letra, moldura diferente: reaproveita a tradução já obtida e veste-a
                    // com a tag DESTA camada. Evita a segunda chamada ao LLM e, sobretudo, evita
                    // que duas camadas simultâneas mostrem textos diferentes na tela.
                    if (traduzido == null) {
                        Optional<TextoSemTags> molde = TextoSemTags.decompor(original);
                        if (molde.isPresent()) {
                            String jaTraduzido = traducaoPorTextoVisivel.get(molde.get().textoLimpo());
                            if (jaTraduzido != null) {
                                traduzido = molde.get().recompor(jaTraduzido);
                                logStream.publicarLog(CANAL_LOG,
                                    "   [CAMADA] mesma letra ja traduzida nesta execucao: "
                                        + RegistroDaExecucao.visivelResumido(traduzido));
                            }
                        }
                    }

                    if (traduzido == null) {
                        String cacheado = cacheExistente.get(original);
                        if (cacheado != null && !cacheado.isBlank()) {
                            traduzido = cacheado;
                            veioDoCache = true;
                        } else if (gravar) {
                            if (Thread.currentThread().isInterrupted()) {
                                eventosFinais.add(evento);
                                semTraducao++;
                                continue;
                            }
                            traduzido = tradutorDeLetra.traduzirViaLlm(
                                original, avisos, sequencialLote, contextoJob.promptSistema());
                        }
                    }
                    if (traduzido == null) {
                        eventosFinais.add(evento);
                        if (gravar) {
                            semTraducao++;
                        } else {
                            logStream.publicarLog(CANAL_LOG, "   [MÚSICA-EN → LLM] será traduzida: " + RegistroDaExecucao.visivelResumido(original));
                        }
                        continue;
                    }
                    // PRESERVACAO E O DEFAULT AQUI: so chega neste ponto o que JA foi traduzido — romaji e
                    // os fragmentos de KFX nem entram no mapa. E o corretor so aceita a sugestao que e A
                    // MESMA PALAVRA ACENTUADA, entao 'gonna', 'kieta' e 'Nordlicht' passam intactos: nao
                    // existe versao acentuada deles para o dicionario oferecer.
                    //
                    // Medido no Unicorn em 13/08: 88 ocorrencias de acento faltando em 372 linhas ja
                    // traduzidas (ED - EN com 11, ED2 com 77) — 'tras', 'mascara', 'nao', 'voce'.
                    traducoes.put(original, traduzido);
                    // Registra pelo texto VISÍVEL para que a próxima camada com a mesma letra
                    // reaproveite em vez de gastar outra chamada e divergir.
                    TextoSemTags.decompor(traduzido).ifPresent(t ->
                        traducaoPorTextoVisivel.putIfAbsent(
                            TextoSemTags.decompor(original).map(TextoSemTags::textoLimpo).orElse(original),
                            t.textoLimpo()));
                    // AQUI e nao no traducoes.put(): por este ponto passa TUDO que vai para o arquivo,
                    // inclusive o que veio do CACHE. Plugado na origem, a rodada de 13/08 17:53 saiu sem
                    // correcao nenhuma — 40x 'nao', 13x 'voce', 7x 'tras' — porque o karaoke reaproveitou
                    // cache e a traducao nem passou pelo ponto que eu tinha escolhido.
                    String comAcento = montador.corrigirAcentos(traduzido);
                    if (!comAcento.equals(traduzido)) {
                        acentosRepostos++;
                    }
                    traduzidasNoArquivo.putIfAbsent(original, comAcento);
                    boolean kfxSoPortugues = !plano.silabasDaFrase(posicao).isEmpty();
                    if (!kfxSoPortugues && plano.temOriginalEmSilabasNaPosicao(posicao)
                        && !plano.temOriginalPreservadaNoInstante(evento)) {
                        inglesNaoEmpilhado++;
                    }
                    String vestido = tipografia.vestir(evento, comAcento, kfxSoPortugues);
                    eventosFinais.add(evento.comTexto(
                        montador.comOriginalPreservada(evento, vestido, plano, posicao)));
                    if (kfxSoPortugues) {
                        frasesKfxSoPortugues.add(posicao);
                        logStream.publicarLog(CANAL_LOG, "   [KFX → SÓ PT] "
                            + RegistroDaExecucao.visivelResumido(original) + "  =>  "
                            + RegistroDaExecucao.visivelResumido(comAcento) + " ("
                            + plano.silabasDaFrase(posicao).size() + " palavra(s) do efeito saem da tela)");
                    }
                    if (veioDoCache) {
                        doCache++;
                        logStream.publicarLog(CANAL_LOG, "   [CACHE] reaproveitada: " + RegistroDaExecucao.visivelResumido(traduzido));
                    } else {
                        traduzidas++;
                        logStream.publicarLog(CANAL_LOG, "   [LLM] " + RegistroDaExecucao.visivelResumido(original)
                            + "  =>  " + RegistroDaExecucao.visivelResumido(traduzido));
                    }
                }
            }
        }

        // As palavras do efeito que pintavam por cima uma frase agora SÓ em português saem da tela
        // como Comment: — ficam no arquivo, reversíveis. Abertura do Unicorn, 12/10/2026: deixá-las
        // em Dialogue escrevia o inglês EM CIMA do português (TV, MPC-HC e libass, 336 frases).
        // A posição em eventosFinais é a mesma do documento: todo ramo do laço acima acrescenta
        // exatamente um evento por evento lido.
        int silabasSilenciadas = 0;
        if (eventosFinais.size() == eventos.size()) {
            for (int frase : frasesKfxSoPortugues) {
                for (int silaba : plano.silabasDaFrase(frase)) {
                    EventoLegenda atual = eventosFinais.get(silaba);
                    EventoLegenda comentado = MontadorEventoFinal.comoComentario(atual);
                    if (comentado != atual) {
                        eventosFinais.set(silaba, comentado);
                        silabasSilenciadas++;
                    }
                }
            }
        } else if (!frasesKfxSoPortugues.isEmpty()) {
            avisos.add("Efeito palavra a palavra NAO silenciado: a lista de saida perdeu o alinhamento "
                + "com o documento (" + eventosFinais.size() + " x " + eventos.size() + ")");
        }

        // PALAVRA INVENTADA PELO MODELO ("Açãoaria", "Anjel", "desfezera"): vai ao manifesto e ao
        // console, e a traducao SEGUE — o ensaio de 09/10/2026 mostrou que refazer nao conserta
        // (1 de 4) e recusar devolveria a letra ao ingles. Ver PalavrasForaDoDicionario.
        PalavrasForaDoDicionario.Verificacao foraDoDicionario =
            PalavrasForaDoDicionario.verificar(corretorOrtografico, traduzidasNoArquivo);
        for (Map.Entry<String, List<String>> achado : foraDoDicionario.porLetra().entrySet()) {
            String traduzida = traduzidasNoArquivo.get(achado.getKey());
            avisos.add("Palavra fora do dicionario " + achado.getValue() + "; traducao mantida para revisao: "
                + RegistroDaExecucao.visivelResumido(achado.getKey()) + " => "
                + RegistroDaExecucao.visivelResumido(traduzida));
            logStream.publicarLog(CANAL_LOG, "   [FORA DO DICIONÁRIO] " + achado.getValue() + " em: "
                + RegistroDaExecucao.visivelResumido(traduzida));
        }

        String nomeDestino = null;
        if (gravar) {
            Path destino = pastaDestino.resolve(nome);
            escritor.escrever(destino, new DocumentoLegenda(
                documento.cabecalho(), eventosFinais, documento.quebraDeLinha(), documento.comBom()));
            nomeDestino = destino.toString();
            cacheDoArquivo.salvar(arquivoCache, documento, traducoes, proveniencia);
            logStream.publicarLog(CANAL_LOG, "   [GRAVADO] " + destino);
        }

        String estadoFontes = tipografia.estadoDasFontes();
        logStream.publicarLog(CANAL_LOG, String.format(Locale.ROOT,
            "   Resumo: %d evento(s) | KFX preservado: %d | letra original: %d | já PT: %d | traduzível: %d (LLM %d, cache %d, sem tradução %d) | acento reposto: %s | fora do dicionário: %s | KFX só PT: %d frase(s), %d palavra(s) do efeito fora da tela | romaji em sílabas: %d linha(s) sem o inglês | fonte do diálogo: %d linha(s) (fontes da música: %s)",
            documento.eventos().size(), kfx, originais, jaPt, paraTraduzir, traduzidas, doCache, semTraducao,
            // Três estados, nunca dois: sem hunspell, "0 corrigidas" e "não pude verificar" são
            // coisas diferentes e não podem imprimir o mesmo sinal.
            !montador.dicionarioDisponivel()
                ? acentosRepostos + " (dicionário AUSENTE — NÃO VERIFICADO)"
                : String.valueOf(acentosRepostos),
            foraDoDicionario.verificado()
                ? String.valueOf(foraDoDicionario.porLetra().size())
                : "NÃO VERIFICADO",
            frasesKfxSoPortugues.size(), silabasSilenciadas, inglesNaoEmpilhado,
            tipografia.linhasComFonteDoDialogo(), estadoFontes));

        // O que estava no cache e NAO foi usado some na regravacao — e some certo, porque a regua
        // de evidencia positiva tornou inalcançavel o que o classificador antigo criou por engano
        // (258 entradas de estilo Signs e 8 de romaji, medidas no cache real em 19/08). Mas cache
        // que encolhe sem numero e indistinguivel de perda de dado, entao o numero vai ao relatorio.
        int entradasCacheDescartadas = 0;
        for (String original : cacheExistente.keySet()) {
            if (!traducoes.containsKey(original)) {
                entradasCacheDescartadas++;
            }
        }
        if (entradasCacheDescartadas > 0) {
            logStream.publicarLog(CANAL_LOG, String.format(Locale.ROOT,
                "   [CACHE] %d entrada(s) do cache nao sao mais alcancaveis e serao descartadas na "
                    + "regravacao — o criterio de musica mudou em 19/08/2026.",
                entradasCacheDescartadas));
        }

        return new ResultadoTraducaoKaraoke(
            nome, nomeDestino, documento.eventos().size(), kfx, originais, jaPt,
            paraTraduzir, doCache, traduzidas, semTraducao, acentosRepostos,
            entradasCacheDescartadas, frasesKfxSoPortugues.size(), silabasSilenciadas,
            tipografia.linhasComFonteDoDialogo(), estadoFontes, List.copyOf(avisos));
    }

    /**
     * PROPÓSITO DE NEGÓCIO: acha o vídeo de onde a legenda saiu e lê as fontes dele, para a linha
     * portuguesa da música não sair com caractere de reserva.
     *
     * <p>INVARIANTES DO DOMÍNIO: só lê; o motivo de não ter lido sobe para o resumo (A7).
     *
     * <p>COMPORTAMENTO EM CASO DE FALHA: vídeo não achado ou leitor ausente devolvem
     * {@link org.traducao.projeto.traducaoKaraoke.domain.FontesDoVideo#naoVerificadas} — nenhuma
     * fonte é trocada sem verificação.
     */
    private org.traducao.projeto.traducaoKaraoke.domain.FontesDoVideo lerFontesDoVideo(Path legenda) {
        if (fontesEmbutidas == null) {
            return org.traducao.projeto.traducaoKaraoke.domain.FontesDoVideo.naoVerificadas(
                "leitor de fontes do video nao configurado");
        }
        LocalizadorDoVideoDeOrigem.Localizacao achado = LocalizadorDoVideoDeOrigem.localizar(legenda);
        if (achado.video() == null) {
            logStream.publicarLog(CANAL_LOG, "   [FONTE] não verificada: " + achado.motivo());
            return org.traducao.projeto.traducaoKaraoke.domain.FontesDoVideo.naoVerificadas(achado.motivo());
        }
        logStream.publicarLog(CANAL_LOG, "   [FONTE] lendo as fontes embutidas em " + achado.video().getFileName());
        return fontesEmbutidas.lerFontes(achado.video());
    }


    /**
     * PROPÓSITO DE NEGÓCIO: repõe acento na linha que ESTA fatia acabou de traduzir.
     *
     * <p>INVARIANTES DO DOMÍNIO: sem o corretor injetado — construção manual em teste, mesmo
     * caso de {@code SeletorEventosTraduziveis} — devolve o texto INTACTO. Preservação é o
     * default do karaokê, então a ausência do corretor não pode virar exceção no meio de uma
     * tradução que já custou chamadas ao LLM.
     *
     * <p>COMPORTAMENTO EM CASO DE FALHA: qualquer problema devolve o texto recebido.
     */


    /** Congela exatamente a obra escolhida; a pasta não participa da decisão de lore. */
    private SnapshotContexto congelarContexto(String contextoId) {
        if (gerenciadorContexto == null) {
            throw new TraducaoKaraokeException(
                "Gerenciador de contexto indisponível; a lore selecionada não pode ser congelada.");
        }
        return gerenciadorContexto.snapshotPorId(contextoId);
    }

    private ProvenienciaCache provenienciaDe(SnapshotContexto contexto) {
        String modelo = llmPort.modeloAtivo();
        return new ProvenienciaCache(
            ProvenienciaCache.SCHEMA_ATUAL,
            contexto.id(),
            ProvenienciaCache.hashDe(contexto.promptSistema()),
            modelo == null || modelo.isBlank() ? "desconhecido" : modelo,
            idiomaOriginal.filter(s -> !s.isBlank()).orElse("en"),
            idiomaTraduzido.filter(s -> !s.isBlank()).orElse("pt-br"));
    }

    private List<Path> listarLegendas(Path pasta) {
        try (Stream<Path> stream = Files.list(pasta)) {
            return stream
                .filter(Files::isRegularFile)
                .filter(p -> {
                    String n = p.getFileName().toString().toLowerCase(Locale.ROOT);
                    return n.endsWith(".ass") || n.endsWith(".ssa");
                })
                .sorted(Comparator.comparing(p -> p.getFileName().toString()))
                .toList();
        } catch (IOException e) {
            throw new TraducaoKaraokeException("Falha ao listar as legendas em " + pasta, e);
        }
    }

    /**
     * Saída sempre em pasta irmã {@code <entrada>-karaoke-ptbr}: preserva os
     * originais para auditoria sem pedir um segundo campo na UI. A pasta só é
     * criada de fato na aplicação — a simulação não toca o disco.
     */
    static Path resolverPastaSaida(Path pastaOrigem) {
        Path absoluta = pastaOrigem.toAbsolutePath().normalize();
        String nome = absoluta.getFileName() != null ? absoluta.getFileName().toString() : "legendas";
        Path pai = absoluta.getParent();
        return pai != null ? pai.resolve(nome + SUFIXO_PASTA_SAIDA) : absoluta.resolve(nome + SUFIXO_PASTA_SAIDA);
    }
}
