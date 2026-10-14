package org.traducao.projeto.legendasExtracao.application;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.traducao.projeto.legendasExtracao.application.strategy.ExtratorStrategy;
import org.traducao.projeto.legendasExtracao.domain.ExtratorException;
import org.traducao.projeto.legendasExtracao.domain.FaixaLegenda;
import org.traducao.projeto.legendasExtracao.domain.FormatoLegenda;
import org.traducao.projeto.legendasExtracao.domain.ItemExtracao;
import org.traducao.projeto.legendasExtracao.domain.RelatorioExtracao;
import org.traducao.projeto.legendasExtracao.domain.exceptions.ExtracaoTimeoutException;
import org.traducao.projeto.legendasExtracao.domain.ports.ExtratorVideoPort;
import org.traducao.projeto.telemetria.TelemetriaService;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

/**
 * PROPÓSITO DE NEGÓCIO: Orquestra a extração de softsubs de vídeos — recebe um
 * arquivo ou pasta, o formato desejado (ASS/SRT/PGS) e a pasta de saída,
 * localiza a faixa daquele formato e a extrai sem conversão, preservando
 * timestamps, estilos e conteúdo. Delega a leitura do contêiner aos adaptadores
 * ({@link ExtratorVideoPort}) e a escolha da faixa às strategies
 * ({@link ExtratorStrategy}).
 *
 * <p>INVARIANTES DO DOMÍNIO: extrai exatamente o formato pedido, sem fallback
 * para outro; nunca sobrescreve arquivo de saída existente; só publica resultado
 * validado (existe, não-vazio, formato correto); cada vídeo gera um item no
 * relatório e é contabilizado na telemetria.
 *
 * <p>COMPORTAMENTO EM CASO DE FALHA: falhas por vídeo são isoladas — o item vira
 * {@code FALHA}/{@code TIMEOUT}/{@code FAIXA_NAO_ENCONTRADA} e o loop segue; o
 * parcial ({@code .part}) é removido. Só a ausência da pasta de entrada ou a
 * falta de strategy para o formato abortam a execução com {@link ExtratorException}.
 */
@Service
public class ExtrairLegendaUseCase {

    private static final Logger log = LoggerFactory.getLogger(ExtrairLegendaUseCase.class);
    private static final String SUFIXO_SAIDA_DO_REMUX = "_ptbr.mkv";
    private static final String PREFIXO_PASTA_DE_TRABALHO = "_kronos_";

    private final List<ExtratorVideoPort> adaptadoresVideo;
    private final List<ExtratorStrategy> strategies;
    private final TelemetriaService telemetriaService;

    public ExtrairLegendaUseCase(
            List<ExtratorVideoPort> adaptadoresVideo,
            List<ExtratorStrategy> strategies,
            TelemetriaService telemetriaService) {
        this.adaptadoresVideo = adaptadoresVideo;
        this.strategies = strategies;
        this.telemetriaService = telemetriaService;
    }

    public RelatorioExtracao executar(Path pastaVideos, FormatoLegenda formato) {
        return executar(pastaVideos, null, formato);
    }

    public RelatorioExtracao executar(Path pastaVideos, Path pastaSaidaCustomizada, FormatoLegenda formato) {
        long inicioMs = System.currentTimeMillis();
        RelatorioExtracao relatorio = new RelatorioExtracao(formato);

        if (!Files.exists(pastaVideos)) {
            throw new ExtratorException("Pasta de vídeos ou arquivo não existe: " + pastaVideos);
        }

        ExtratorStrategy strategy = strategies.stream()
                .filter(s -> s.suporta(formato))
                .findFirst()
                .orElseThrow(() -> new ExtratorException("Nenhuma estratégia suporta o formato " + formato));

        Path pastaSaida = (pastaSaidaCustomizada != null && !pastaSaidaCustomizada.toString().isBlank())
                ? pastaSaidaCustomizada
                : (Files.isDirectory(pastaVideos) 
                    ? pastaVideos.resolve("legendas_extraidas_" + formato.name().toLowerCase())
                    : (pastaVideos.getParent() != null ? pastaVideos.getParent().resolve("legendas_extraidas_" + formato.name().toLowerCase()) : Path.of("legendas_extraidas_" + formato.name().toLowerCase())));
        // A pasta de saída só nasce quando há legenda para gravar (extrairFaixaSelecionada). Até
        // 09/10/2026 ela era criada aqui, antes de olhar os vídeos: o MKV do filme de Macross Delta,
        // que só tem PGS, deixou uma legendas_extraidas_ass VAZIA ao lado — e a pasta vazia parece
        // "extraí e não sobrou nada" para quem passa por ela depois.

        VideosDaEntrada entrada = encontrarVideos(pastaVideos);
        List<Path> videos = entrada.videos();
        if (entrada.saidasDoRemuxIgnoradas() > 0) {
            relatorio.registrarSaidasDoRemuxIgnoradas(entrada.saidasDoRemuxIgnoradas());
            System.out.printf("   [IGNORADOS] %d MKV(s) publicado(s) pelo remux (_PTBR.mkv): a pasta também tem "
                + "os originais, e a saída do remux não é legenda de origem. Para extrair de um deles, "
                + "copie-o para uma pasta só com MKVs publicados e extraia dessa pasta.%n",
                entrada.saidasDoRemuxIgnoradas());
            log.info("Extração ignorou {} MKV(s) publicado(s) pelo remux em {} (a pasta tem os originais)",
                entrada.saidasDoRemuxIgnoradas(), pastaVideos);
        }

        Set<ExtratorVideoPort> adaptadoresEmUso = new HashSet<>();
        videos.forEach(v -> resolverAdaptador(v).ifPresent(adaptadoresEmUso::add));
        adaptadoresEmUso.forEach(ExtratorVideoPort::validarInfraestrutura);

        int total = videos.size();
        int indice = 0;
        // Destino -> vídeo que o gerou NESTA execução: a saída é plana, e dois vídeos de mesmo nome
        // em subpastas (S01/Ep01 e S02/Ep01) dão o mesmo destino (auditoria de 08/10/2026, E6).
        java.util.Map<Path, String> geradoPor = new java.util.HashMap<>();
        for (Path video : videos) {
            // Parada do operador (Parar/Sair): o adaptador religa a flag de interrupção depois de
            // converter o InterruptedException. Sem conferir aqui, cada vídeo restante falhava na
            // hora no Process.waitFor e virava FALHA (auditoria de 08/10/2026, E4).
            if (Thread.currentThread().isInterrupted()) {
                pararPeloOperador(relatorio, total - indice, indice, total);
                break;
            }
            indice++;
            relatorio.registrarDetectado();
            String nomeVideo = video.getFileName().toString();
            // Progresso ARQUIVO A ARQUIVO ao vivo no console web: System.out é
            // redirecionado para o canal SSE 'extracao' (ver ConsoleRedirector +
            // PipelineWebSupport). log.* vai só para o terminal/arquivo do servidor,
            // por isso a tela ficava muda durante o lote.
            System.out.printf(">> [%d/%d] Extraindo legenda: %s%n", indice, total, nomeVideo);
            log.debug("Processando {}", nomeVideo);

            ExtratorVideoPort adaptador = resolverAdaptador(video).orElseThrow();

            try {
                List<FaixaLegenda> faixas = adaptador.identificarFaixas(video);
                relatorio.registrarFaixasEncontradas(faixas.size());
                Optional<FaixaLegenda> faixaAlvo = strategy.selecionarMelhorFaixa(faixas);

                if (faixaAlvo.isEmpty()) {
                    relatorio.registrarSemLegenda();
                    relatorio.adicionarItem(ItemExtracao.semFaixa(nomeVideo, formato.name()));
                    Optional<FaixaLegenda> textoNaoConvertido = faixas.stream()
                        .filter(ExtrairLegendaUseCase::ehTextoQueAExtracaoNaoConverte).findFirst();
                    if (textoNaoConvertido.isPresent()) {
                        relatorio.registrarSemFaixaComTextoNaoSuportado();
                        System.out.printf("   [SEM FAIXA %s] %s — há legenda de TEXTO em %s, formato que a extração "
                            + "ainda não converte (não é hardsub)%n", formato.name(), nomeVideo,
                            textoNaoConvertido.get().codec());
                    } else {
                        System.out.printf("   [SEM FAIXA %s] %s%n", formato.name(), nomeVideo);
                    }
                    log.warn("Nenhuma faixa {} encontrada no vídeo: {}", formato, nomeVideo);
                    continue;
                }

                extrairFaixaSelecionada(adaptador, video, nomeVideo, faixaAlvo.get(), formato, pastaSaida, relatorio,
                    geradoPor, pastaVideos.relativize(video).toString());
            } catch (ExtracaoTimeoutException e) {
                relatorio.registrarTimeout();
                relatorio.adicionarItem(ItemExtracao.falha(nomeVideo, formato.name(), null,
                    "Timeout ao identificar faixas"));
                System.out.printf("   [TIMEOUT] %s — ao identificar faixas%n", nomeVideo);
                log.error("Timeout ao identificar faixas em {}: {}", nomeVideo, e.getMessage());
            } catch (ExtratorException e) {
                if (Thread.currentThread().isInterrupted()) {
                    // A "falha" é a parada chegando no meio deste vídeo: ele conta como não
                    // processado, junto com os que faltam.
                    pararPeloOperador(relatorio, total - indice + 1, indice - 1, total);
                    break;
                }
                relatorio.registrarFalha();
                relatorio.adicionarItem(ItemExtracao.falha(nomeVideo, formato.name(), null, e.getMessage()));
                System.out.printf("   [FALHA] %s — %s%n", nomeVideo, e.getMessage());
                log.error("Falha ao processar {}: {}", nomeVideo, e.getMessage());
            } catch (Exception e) {
                relatorio.registrarFalha();
                relatorio.adicionarItem(ItemExtracao.falha(nomeVideo, formato.name(), null, e.getMessage()));
                System.out.printf("   [ERRO] %s — %s%n", nomeVideo, e.getMessage());
                log.error("Erro inesperado em {}: {}", nomeVideo, e.getMessage(), e);
            }
        }

        // Telemetria: vídeos processados (arquivosProcessados), faixas encontradas
        // (itensDetectados) e extraídas com sucesso (itensCorrigidos); formato,
        // falhas e timeouts vão no detalhe para o painel exibir a íntegra.
        String detalheTelemetria = String.format(
            "%s | %s | faixas: %d, extraídas: %d, sem faixa: %d, já existiam: %d, falhas: %d, timeouts: %d%s%s",
            pastaVideos.toAbsolutePath(), formato.name(),
            relatorio.getFaixasEncontradas(), relatorio.getLegendasExtraidas(),
            relatorio.getArquivosSemLegenda(), relatorio.getArquivosJaExistentes(),
            relatorio.getFalhasInesperadas(), relatorio.getTimeouts(),
            relatorio.getSaidasDoRemuxIgnoradas() > 0
                ? ", saídas do remux ignoradas: " + relatorio.getSaidasDoRemuxIgnoradas()
                : "",
            relatorio.isInterrompidoPeloOperador()
                ? ", PARADO pelo operador: " + relatorio.getNaoProcessadosPorParada() + " não processado(s)"
                : "");

        telemetriaService.registrarOperacao(TelemetriaService.criarOperacao(
            "Extracao de Legendas (" + formato.name() + ")",
            detalheTelemetria,
            System.currentTimeMillis() - inicioMs,
            relatorio.getArquivosDetectados(),
            relatorio.getFaixasEncontradas(),
            relatorio.getLegendasExtraidas()
        ));

        return relatorio;
    }

    /**
     * PROPÓSITO DE NEGÓCIO: reconhece legenda de TEXTO num codec que a extração ainda não converte,
     * para que o vídeo sem faixa do formato pedido não seja apresentado como "hardsub" quando
     * tem legenda.
     *
     * <p>INVARIANTES DO DOMÍNIO: mov_text/tx3g (MP4) e WebVTT/wvtt, olhando codec e codec id sem
     * diferenciar maiúsculas. Não decide extração — só a mensagem.
     *
     * <p>COMPORTAMENTO EM CASO DE FALHA: codec nulo conta como vazio; nunca lança.
     */
    static boolean ehTextoQueAExtracaoNaoConverte(FaixaLegenda faixa) {
        String c = ((faixa.codec() == null ? "" : faixa.codec()) + " "
            + (faixa.codecId() == null ? "" : faixa.codecId())).toLowerCase(java.util.Locale.ROOT);
        return c.contains("mov_text") || c.contains("mov text") || c.contains("tx3g")
            || c.contains("webvtt") || c.contains("wvtt");
    }

    /**
     * PROPÓSITO DE NEGÓCIO: encerra o lote quando o operador pede a parada, registrando os vídeos
     * restantes como NÃO PROCESSADOS — não como falha — para que relatório, telemetria e banner
     * digam o que aconteceu.
     *
     * <p>INVARIANTES DO DOMÍNIO: não toca em arquivo; a flag de interrupção continua ligada para
     * quem chamou (a fila) enxergar o cancelamento.
     *
     * <p>COMPORTAMENTO EM CASO DE FALHA: contagem negativa vira zero no relatório; nunca lança.
     */
    private void pararPeloOperador(RelatorioExtracao relatorio, int naoProcessados, int processados, int total) {
        relatorio.registrarParada(naoProcessados);
        System.out.printf("   [PARADO] Extração interrompida pelo operador: %d de %d vídeo(s) processado(s); "
            + "%d não processado(s) — os arquivos destes não foram tocados.%n", processados, total, naoProcessados);
        log.info("Extração interrompida pelo operador: {} de {} processados, {} não processados",
            processados, total, naoProcessados);
    }

    /**
     * PROPÓSITO DE NEGÓCIO: Extrai a faixa já escolhida para o arquivo final,
     * garantindo entrega íntegra — nunca sobrescreve arquivo existente e nunca
     * publica um resultado que não passou na validação de formato.
     *
     * <p>INVARIANTES DO DOMÍNIO: (1) se o destino final já existe, o vídeo é
     * marcado {@code JA_EXISTE} e nada é gravado; (2) a extração ocorre primeiro
     * num arquivo temporário {@code .part}; (3) só após validar existência,
     * tamanho e formato o temporário é movido (atômico) para o nome final.
     *
     * <p>COMPORTAMENTO EM CASO DE FALHA: qualquer erro/timeout durante a extração
     * ou validação remove o parcial ({@code .part}) e registra o item como
     * {@code FALHA} ou {@code TIMEOUT} — o destino final nunca fica com lixo.
     */
    private void extrairFaixaSelecionada(
            ExtratorVideoPort adaptador, Path video, String nomeVideo, FaixaLegenda faixa,
            FormatoLegenda formato, Path pastaSaida, RelatorioExtracao relatorio,
            java.util.Map<Path, String> geradoPor, String videoRelativo) {

        String nomeBase = nomeVideo.replaceFirst("[.][^.]+$", "");
        String arquivoSaida = nomeBase + "_Track" + faixa.id() + "." + formato.getExtensaoSaida();
        Path caminhoFinal = pastaSaida.resolve(arquivoSaida);

        String origemNestaExecucao = geradoPor.get(caminhoFinal);
        if (origemNestaExecucao != null) {
            // O destino foi gerado AGORA por outro vídeo de mesmo nome: não é "já existia, preservado"
            // (o que soa como reexecução inofensiva) — é uma legenda que NÃO foi extraída.
            relatorio.registrarFalha();
            relatorio.adicionarItem(ItemExtracao.falha(nomeVideo, formato.name(), faixa.id(),
                "Colisão: " + arquivoSaida + " já foi gerado nesta execução a partir de " + origemNestaExecucao));
            System.out.printf("   [COLISÃO] %s -> %s já foi gerado nesta execução por %s; a legenda deste vídeo "
                + "NÃO foi extraída. Extraia cada temporada numa pasta de saída própria.%n",
                videoRelativo, arquivoSaida, origemNestaExecucao);
            log.warn("Colisão de nome na saída plana: {} e {} geram {}", origemNestaExecucao, videoRelativo, arquivoSaida);
            return;
        }

        if (Files.exists(caminhoFinal)) {
            relatorio.registrarJaExiste();
            relatorio.adicionarItem(ItemExtracao.jaExiste(nomeVideo, formato.name(), faixa.id(), arquivoSaida));
            System.out.printf("   [JÁ EXISTE] %s -> %s%n", nomeVideo, arquivoSaida);
            log.warn("Arquivo de saída já existe; extração ignorada para não sobrescrever: {}", caminhoFinal);
            return;
        }

        try {
            Files.createDirectories(pastaSaida);
        } catch (IOException e) {
            // Falha de UM vídeo, como as outras: o lote segue e o item registra o motivo.
            relatorio.registrarFalha();
            relatorio.adicionarItem(ItemExtracao.falha(nomeVideo, formato.name(), faixa.id(),
                "Falha ao criar pasta de saída: " + pastaSaida));
            System.out.printf("   [FALHA] %s — não foi possível criar a pasta de saída %s%n", nomeVideo, pastaSaida);
            log.error("Falha ao criar a pasta de saída {}: {}", pastaSaida, e.getMessage());
            return;
        }

        Path caminhoTemp = pastaSaida.resolve(arquivoSaida + ".part");
        limparParcial(caminhoTemp);

        try {
            adaptador.extrairTrilha(video, faixa.id(), caminhoTemp);
            ValidadorSaidaExtracao.validar(caminhoTemp, formato);
            moverParaFinal(caminhoTemp, caminhoFinal);
            relatorio.registrarExtraido();
            geradoPor.put(caminhoFinal, videoRelativo);
            relatorio.adicionarItem(ItemExtracao.sucesso(nomeVideo, formato.name(), faixa.id(), arquivoSaida));
            System.out.printf("   [OK] %s -> %s (Track %d)%n", nomeVideo, arquivoSaida, faixa.id());
        } catch (ExtracaoTimeoutException e) {
            limparParcial(caminhoTemp);
            relatorio.registrarTimeout();
            relatorio.adicionarItem(ItemExtracao.timeout(nomeVideo, formato.name(), faixa.id()));
            System.out.printf("   [TIMEOUT] %s (Track %d)%n", nomeVideo, faixa.id());
            log.error("Timeout ao extrair {} (Track {}): {}", nomeVideo, faixa.id(), e.getMessage());
        } catch (ExtratorException e) {
            limparParcial(caminhoTemp);
            if (Thread.currentThread().isInterrupted()) {
                // Parada do operador no meio da extração: não é falha; o laço contabiliza.
                throw e;
            }
            relatorio.registrarFalha();
            relatorio.adicionarItem(ItemExtracao.falha(nomeVideo, formato.name(), faixa.id(), e.getMessage()));
            System.out.printf("   [FALHA] %s (Track %d) — %s%n", nomeVideo, faixa.id(), e.getMessage());
            log.error("Falha ao extrair {} (Track {}): {}", nomeVideo, faixa.id(), e.getMessage());
        }
    }

    private void moverParaFinal(Path temp, Path destino) {
        try {
            try {
                Files.move(temp, destino, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException ex) {
                Files.move(temp, destino);
            }
        } catch (IOException e) {
            throw new ExtratorException("Falha ao mover a legenda extraída para o destino final: " + destino, e);
        }
    }

    private void limparParcial(Path temp) {
        try {
            Files.deleteIfExists(temp);
        } catch (IOException e) {
            log.warn("Não foi possível remover o arquivo parcial {}: {}", temp, e.getMessage());
        }
    }

    /** Os vídeos que a execução vai processar e quantos MKVs publicados pelo remux ficaram de fora. */
    private record VideosDaEntrada(List<Path> videos, int saidasDoRemuxIgnoradas) { }

    /**
     * PROPÓSITO DE NEGÓCIO: decide quais vídeos são ORIGEM de legenda numa entrada que pode ser um
     * arquivo ou a pasta da obra inteira — e a pasta da obra, no acervo, guarda junto o que o próprio
     * KRONOS produziu.
     *
     * <p>INVARIANTES DO DOMÍNIO:
     * <ul>
     *   <li>Arquivo escolhido diretamente é sempre processado: o operador pediu aquele.</li>
     *   <li>Pasta {@code _kronos_*} (cópia de trabalho e backup da republicação) nunca é origem.</li>
     *   <li>MKV publicado pelo remux ({@code X_PTBR.mkv}, o nome que o {@code MapeadorMidiaService}
     *       do 5.1 gera) fica de fora quando a mesma pasta tem os originais. No Gundam ZZ, em
     *       14/10/2026, a extração percorreu {@code en/} e os 47 publicados da raiz: 47 legendas
     *       duplicadas e, nos dois PTBR sem faixa inglesa, a faixa PORTUGUESA extraída como origem.</li>
     *   <li>Pasta SÓ com MKVs publicados continua extraindo: é como o acervo foi regenerado em 12/10,
     *       a partir do publicado, quando a pasta de origem não existia nesta máquina.</li>
     * </ul>
     *
     * <p>COMPORTAMENTO EM CASO DE FALHA: entrada que não é arquivo nem pasta lança
     * {@link ExtratorException}; erro de leitura da pasta também. Nenhum arquivo é tocado aqui.
     */
    private VideosDaEntrada encontrarVideos(Path entrada) {
        if (Files.isRegularFile(entrada)) {
            return new VideosDaEntrada(resolverAdaptador(entrada).isPresent() ? List.of(entrada) : List.of(), 0);
        }

        if (!Files.isDirectory(entrada)) {
            throw new ExtratorException("Pasta de vídeos não existe ou não é um diretório: " + entrada);
        }

        List<Path> todos;
        try (Stream<Path> walk = Files.walk(entrada)) {
            todos = walk
                .filter(Files::isRegularFile)
                .filter(p -> !dentroDePastaDeTrabalho(entrada, p))
                .filter(p -> resolverAdaptador(p).isPresent())
                .sorted()
                .toList();
        } catch (IOException e) {
            throw new ExtratorException("Falha ao ler o diretório " + entrada, e);
        }
        List<Path> originais = todos.stream().filter(p -> !ehSaidaDoRemux(p)).toList();
        if (originais.isEmpty() || originais.size() == todos.size()) {
            return new VideosDaEntrada(todos, 0);
        }
        return new VideosDaEntrada(originais, todos.size() - originais.size());
    }

    /**
     * PROPÓSITO DE NEGÓCIO: reconhece o MKV que o remux 5.1 publicou — o nome que ele gera termina em
     * {@code _PTBR.mkv} (invariante do {@code MapeadorMidiaService.gerarNomeSaidaLimpo}).
     *
     * <p>INVARIANTES DO DOMÍNIO: compara só o fim do nome, sem diferenciar maiúsculas.
     *
     * <p>COMPORTAMENTO EM CASO DE FALHA: nome nulo não é saída do remux; nunca lança.
     */
    private static boolean ehSaidaDoRemux(Path video) {
        Path nome = video.getFileName();
        return nome != null && nome.toString().toLowerCase(java.util.Locale.ROOT).endsWith(SUFIXO_SAIDA_DO_REMUX);
    }

    /**
     * PROPÓSITO DE NEGÓCIO: reconhece arquivo dentro de pasta de trabalho do KRONOS
     * ({@code _kronos_regen_*}, {@code _kronos_backup_*}) abaixo da entrada — cópia do que já existe,
     * que com o mesmo nome do original virava colisão no lote.
     *
     * <p>INVARIANTES DO DOMÍNIO: só as pastas ENTRE a entrada e o arquivo contam; a própria entrada
     * pode se chamar {@code _kronos_*} (o operador escolheu aquela pasta) e é percorrida.
     *
     * <p>COMPORTAMENTO EM CASO DE FALHA: caminho fora da entrada não conta como pasta de trabalho.
     */
    private static boolean dentroDePastaDeTrabalho(Path entrada, Path arquivo) {
        if (!arquivo.startsWith(entrada)) {
            return false;
        }
        Path relativo = entrada.relativize(arquivo);
        for (int i = 0; i < relativo.getNameCount() - 1; i++) {
            if (relativo.getName(i).toString().toLowerCase(java.util.Locale.ROOT).startsWith(PREFIXO_PASTA_DE_TRABALHO)) {
                return true;
            }
        }
        return false;
    }

    private Optional<ExtratorVideoPort> resolverAdaptador(Path video) {
        return adaptadoresVideo.stream().filter(a -> a.suporta(video)).findFirst();
    }
}
