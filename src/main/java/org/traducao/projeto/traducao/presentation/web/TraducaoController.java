package org.traducao.projeto.traducao.presentation.web;
import org.traducao.projeto.core.presentation.web.PipelineWebSupport;
import org.traducao.projeto.core.presentation.web.OperacaoRequest;
import org.traducao.projeto.core.presentation.web.RespostaPadrao;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.traducao.projeto.traducao.domain.TelemetriaTraducao;
import org.traducao.projeto.traducao.domain.ports.TelemetriaTraducaoPort;
import org.traducao.projeto.traducao.application.ProcessarArquivoUseCase;
import org.traducao.projeto.llm.domain.StatusLlm;
import org.traducao.projeto.llm.domain.LlmPort;
import org.traducao.projeto.traducao.infrastructure.config.TradutorProperties;
import org.traducao.projeto.lore.domain.ProvedorContexto;
import org.traducao.projeto.lore.domain.SnapshotContexto;
import org.traducao.projeto.lore.infrastructure.GerenciadorContexto;
import org.traducao.projeto.traducao.presentation.ui.PastasExecucao;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

/**
 * PROPÓSITO DE NEGÓCIO: expõe a tradução local via LLM (Opção 3) à interface
 * web, verificando a disponibilidade do servidor LLM, configurando as pastas de
 * execução, aplicando o contexto de lore selecionado e traduzindo em lote os
 * arquivos de legenda encontrados.
 *
 * <p>INVARIANTES DO DOMÍNIO: usa a MESMA fila compartilhada via
 * {@link PipelineWebSupport}; contexto de lore é obrigatório e validado (sem
 * fallback silencioso); o contexto do lote é congelado UMA vez, a partir do
 * {@code contextoId} explícito da requisição, e passado por parâmetro a cada arquivo —
 * de modo que trocar a obra ativa durante o lote não muda a lore, o prompt nem a
 * proveniência dos arquivos restantes; apenas extensões suportadas
 * ({@code .ass/.ssa/.srt}) são traduzidas; nenhuma URL, código HTTP ou nome de campo de
 * DTO é alterado em relação ao controller monolítico original.
 *
 * <p>COMPORTAMENTO EM CASO DE FALHA: entrada em branco ou contexto ausente/
 * inválido retorna HTTP 400; falhas por arquivo são contabilizadas, registradas
 * na telemetria e reportadas no console SSE, sem derrubar a fila.
 */
@RestController
@RequestMapping("/api")
public class TraducaoController {

    private static final Logger log = LoggerFactory.getLogger(TraducaoController.class);
    private static final Set<String> EXTENSOES_SUPORTADAS = Set.of(".ass", ".ssa", ".srt");

    private final PipelineWebSupport pipelineWebSupport;
    private final ProcessarArquivoUseCase processarArquivoUseCase;
    private final LlmPort llmPort;
    private final GerenciadorContexto gerenciadorContexto;
    private final PastasExecucao pastasExecucao;
    private final TradutorProperties propriedades;
    private final TelemetriaTraducaoPort telemetriaTraducao;
    private final org.traducao.projeto.core.io.GuardaCaminhoEntrada guardaCaminho;
    private final org.traducao.projeto.core.presentation.web.LogStreamService logStreamService;
    private final org.traducao.projeto.traducao.infrastructure.AvisoSonoroSistema avisoSonoro;

    /**
     * Sufixo do canal SSE que anuncia o FIM do lote — {@code traducao-finalizada} e
     * {@code traducao-sem-lore-finalizada}. Canal próprio, e não uma linha de console: quem
     * espera o fim precisa de um sinal com chave estável, não de um texto de apresentação.
     */
    static final String SUFIXO_CANAL_FIM = "-finalizada";

    /** Quantos toques o aviso de fim dá. Pedido de Paulo: três. */
    static final int TOQUES_FIM_DE_LOTE = 3;

    /**
     * Separa o desfecho do veredito do som no corpo do evento. Barra vertical porque nenhum
     * rótulo de {@code StatusLoteTraducao} a contém — a chave do filtro tem de ser inequívoca.
     */
    static final String SEPARADOR_EVENTO = "|";

    public TraducaoController(
            PipelineWebSupport pipelineWebSupport,
            ProcessarArquivoUseCase processarArquivoUseCase,
            LlmPort llmPort,
            GerenciadorContexto gerenciadorContexto,
            PastasExecucao pastasExecucao,
            TradutorProperties propriedades,
            TelemetriaTraducaoPort telemetriaTraducao,
            org.traducao.projeto.core.io.GuardaCaminhoEntrada guardaCaminho,
            org.traducao.projeto.core.presentation.web.LogStreamService logStreamService,
            org.traducao.projeto.traducao.infrastructure.AvisoSonoroSistema avisoSonoro) {
        this.pipelineWebSupport = pipelineWebSupport;
        this.processarArquivoUseCase = processarArquivoUseCase;
        this.llmPort = llmPort;
        this.gerenciadorContexto = gerenciadorContexto;
        this.pastasExecucao = pastasExecucao;
        this.propriedades = propriedades;
        this.telemetriaTraducao = telemetriaTraducao;
        this.guardaCaminho = guardaCaminho;
        this.logStreamService = logStreamService;
        this.avisoSonoro = avisoSonoro;
    }

    /**
     * 3. TRADUÇÃO LOCAL
     */
    @PostMapping("/traduzir")
    public ResponseEntity<RespostaPadrao> traduzir(@RequestBody OperacaoRequest req) {
        if (req.entrada() == null || req.entrada().isBlank()) {
            return ResponseEntity.badRequest().body(new RespostaPadrao("Pasta de legendas de entrada obrigatória."));
        }
        if (req.contextoId() == null || req.contextoId().isBlank()) {
            return ResponseEntity.badRequest().body(new RespostaPadrao(
                    "Contexto de tradução (lore) obrigatório: selecione o contexto antes de traduzir. "
                    + "Não há fallback silencioso para a obra usada na execução anterior."));
        }
        if (!gerenciadorContexto.existeContexto(req.contextoId())) {
            return ResponseEntity.badRequest().body(new RespostaPadrao(
                    "Contexto de tradução desconhecido: \"" + req.contextoId() + "\". Recarregue a página e selecione um contexto válido."));
        }

        // ANTES do submeter: depois da fila a resposta HTTP já saiu como "tradução iniciada" e a
        // recusa só viveria no log, que ninguém lê no instante do clique. A checagem que existia
        // (Files.isDirectory, lá dentro do job) chegava tarde demais.
        //
        // Medido em 2026-08-12, nesta rota: um POST com pasta do host contra o KRONOS de contêiner
        // — que enxerga o acervo em /acervo — devolveu 200 "Tradução via LLM iniciada" e só falhou
        // depois, no log. A varredura de 11/08 corrigiu 7 rotas e não alcançou esta, porque a
        // catraca procurava "filaExecucao.submeter" e aqui o disparo é submeterJobComRelatorio.
        var recusa = guardaCaminho.conferirDiretorio("A pasta de legendas de entrada", req.entrada())
            // Lente de BOA-FÉ, não adversarial: ninguém aponta a pasta de saída de propósito.
            // Em 06/08/2026 uma tradução leu `legenda-simplificada` e sobrescreveu 17 arquivos
            // limpos; hoje o acervo tem traducao_mistral, traducao_aya e traducao_ptbr na mesma
            // obra, e as duas primeiras são baseline de um experimento de dias.
            .or(() -> guardaCaminho.conferirEntradaNaoEhSaidaDeTraducao(req.entrada(), req.saida()));
        if (recusa.isPresent()) {
            return ResponseEntity.badRequest().body(new RespostaPadrao(recusa.get().mensagem()));
        }

        // O CANAL SSE SEGUE O CONTEXTO, e não pode voltar a ser literal.
        //
        // Esta rota serve DUAS telas: "2.1 Tradução Local" e "2.2 Tradução sem Lore". Enquanto o
        // canal era o literal "traducao", todo o progresso da tradução SEM LORE era entregue ao
        // console da 2.1 — a 2.2 exibia as linhas que o próprio JS dela escreve e depois ficava
        // MUDA para sempre, porque o consoleMap do frontend não tinha entrada para ela e o
        // fallback genérico descartava a linha em silêncio ao não achar o destino.
        //
        // Medido em 14/08/2026 no Memories: 192 lotes traduzidos em ~3 minutos, todos publicados
        // em "[traducao]" (logs/console-web.log), com a tela 2.2 aberta e parada nas 7 linhas
        // iniciais. O operador concluiu que havia travado e matou o processo — perdendo o
        // arquivo em curso, porque o cache só é gravado ao fim de cada arquivo.
        //
        // O critério é o MESMO da escolha de pasta de saída, mais abaixo neste método: não há
        // segunda régua para "isto é sem lore?".
        boolean semLore = ProvedorContexto.ID_SEM_LORE.equals(req.contextoId());
        String canalSse = semLore ? "traducao-sem-lore" : "traducao";
        String nomeOperacao = semLore ? "Tradução sem Lore via LLM" : "Tradução Local via LLM";
        pipelineWebSupport.submeterJobComRelatorio(canalSse, nomeOperacao, () -> {
            // AVISO DE FIM DE LOTE — o operador sai de perto durante um lote que dura de meia
            // hora a duas. O sinal precisa de EVENTO PRÓPRIO: casar a string do banner
            // "[CONCLUÍDO] TRADUÇÃO LOCAL VIA LLM" seria filtrar pela APRESENTAÇÃO, e qualquer
            // ajuste de rótulo emudeceria o aviso sem ninguém perceber — a chave do filtro tem
            // de ser a chave da COISA.
            //
            // Mora no finally porque os QUATRO returns antecipados deste corpo (caminho
            // inválido, pasta inexistente, LLM offline, zero arquivos) também encerram a
            // espera. São justamente os piores: o lote morre em dois segundos e quem foi fazer
            // outra coisa continua esperando por horas. Terminar mal e terminar bem avisam
            // igual; o que muda é o desfecho que vai no evento.
            String desfecho = "ENCERRADO SEM RELATÓRIO";
            try {
                Path pathEntrada = pipelineWebSupport.normalizarCaminho(req.entrada());
                if (pathEntrada == null) {
                    log.error("Caminho de entrada inválido informado para tradução: {}", req.entrada());
                    System.out.println("[FAIL] Caminho de entrada inválido: " + req.entrada());
                    return;
                }
                if (!Files.isDirectory(pathEntrada)) {
                    System.out.println("\u001B[31m[FAIL] Pasta de entrada inválida: " + pathEntrada + "\u001B[0m");
                    return;
                }

                // Verifica LLM
                System.out.println("Verificando se o servidor LLM local está online...");
                StatusLlm status = llmPort.verificarDisponibilidade();
                if (!status.modeloCarregado()) {
                    System.out.println("\u001B[31m[FAIL] Servidor LLM indisponível: " + status.mensagem() + "\u001B[0m");
                    return;
                }
                System.out.println("\u001B[32m[OK] Servidor LLM ativo.\u001B[0m");

                // Regra 12: "ligado e sem efeito" e "desligado" NÃO podem dar o mesmo sinal. Sem
                // esta linha, um lote sem nenhuma recuperação é indistinguível de um modelo de
                // recuperação mal configurado — e o operador só descobriria lendo o yml. O próprio
                // ProcessarEpisodioUseCase já declara essa preocupação no catch da segunda opinião:
                // "um modelo de recuperação mal configurado ficaria eternamente ligado e sem
                // efeito, indistinguível de desligado".
                String segundaOpiniao = llmPort.modeloRecuperacao();
                System.out.println(segundaOpiniao == null || segundaOpiniao.isBlank()
                    ? "[SEGUNDA-OPINIAO] DESLIGADA (tradutor.llm.modelo-recuperacao vazio)."
                    : "[SEGUNDA-OPINIAO] Ligada: \"" + segundaOpiniao + "\".");

                // Configura as pastas compartilhadas
                String saida = req.saida() != null && !req.saida().isBlank() ? req.saida() : "";
                // Tradução SEM LORE nunca cai na pasta canônica por omissão: uma tradução crua
                // (nomes próprios desprotegidos, zero terminologia) não pode virar a versão
                // definitiva só porque o operador deixou o campo vazio. Saída explícita continua
                // valendo — quem digitou o caminho sabe o que quer.
                if (saida.isEmpty() && ProvedorContexto.ID_SEM_LORE.equals(req.contextoId())) {
                    Path paiSemLore = pathEntrada.getParent();
                    saida = (paiSemLore != null ? paiSemLore : pathEntrada)
                        .resolve("traducao_ptbr_sem_lore").toString();
                    System.out.println("[33m[SEM LORE] Saída separada: " + saida + "[0m");
                }
                pastasExecucao.configurar(req.entrada(), saida, propriedades.diretorioCache(), propriedades);

                // Fotografia ÚNICA do job, resolvida a partir do contextoId EXPLÍCITO da
                // requisição — não do contexto ativo global. Todos os arquivos deste lote
                // serão traduzidos, validados e carimbados com ESTE valor imutável: se o
                // operador (ou outra rota: correção, revisão, karaokê) trocar a obra ativa
                // enquanto o lote roda, os arquivos restantes continuam na obra pedida.
                SnapshotContexto contextoDoJob = gerenciadorContexto.snapshotPorId(req.contextoId());
                // O ativo global continua sendo definido para as rotas legadas que ainda o
                // leem (revisão de concordância no LlmClientAdapter, fallback do
                // LoreAtivaContextoAdapter fora de execução). Ele NÃO é a fonte deste lote.
                gerenciadorContexto.definirContextoAtivo(req.contextoId());
                System.out.println("\u001B[34m[CONTEXTO] Utilizando contexto: " + contextoDoJob.nomeExibicao() + "\u001B[0m");

                List<Path> arquivos;
                try (Stream<Path> stream = Files.list(pathEntrada)) {
                    arquivos = stream
                            .filter(Files::isRegularFile)
                            .filter(this::temExtensaoSuportada)
                            .sorted(Comparator.comparing(p -> p.getFileName().toString()))
                            .toList();
                }

                if (arquivos.isEmpty()) {
                    System.out.println("\u001B[33mNenhum arquivo de legenda .ass/.ssa/.srt encontrado.\u001B[0m");
                    return;
                }

                System.out.println("Iniciando tradução de " + arquivos.size() + " arquivo(s)...");

                java.util.List<org.traducao.projeto.traducao.domain.ResultadoTraducaoArquivo> resultados = new java.util.ArrayList<>();
                boolean permitir = Boolean.TRUE.equals(req.permitirRetraducao());
                // Rótulo de lore do RELATÓRIO e da telemetria: sai do snapshot do job, não do
                // ativo global — do contrário uma troca de obra no meio do lote renomearia,
                // no relatório, arquivos que foram traduzidos com a obra anterior.
                String loreNome = contextoDoJob.nomeExibicao();
                boolean parado = false;
                // Bloqueio por OBRA nao e "ja traduzido": conta como falha no desfecho do lote.
                int bloqueadosPorObra = 0;
                for (int i = 0; i < arquivos.size(); i++) {
                    Path arquivo = arquivos.get(i);
                    if (Thread.currentThread().isInterrupted()) {
                        // Parada cooperativa: um arquivo anterior foi cancelado (flag setada).
                        // Não reprocessa os restantes nem os marca como falha.
                        parado = true;
                        System.out.println("\n[PARADO] Tradução interrompida; " + (arquivos.size() - i)
                            + " arquivo(s) restante(s) não processado(s).");
                        break;
                    }
                    System.out.println("\n--------------------------------------------------------------");
                    System.out.println("Processando arquivo [" + (i + 1) + "/" + arquivos.size() + "]: " + arquivo.getFileName());
                    System.out.println("--------------------------------------------------------------");
                    try {
                        var resultado = processarArquivoUseCase.processar(
                            arquivo, permitir, contextoDoJob, Boolean.TRUE.equals(req.ignorarLoreExistente()));
                        resultados.add(resultado);
                        if (resultado.status() == org.traducao.projeto.traducao.domain.StatusArquivoTraducao.CONCLUIDO) {
                            System.out.println("[OK] Traduzido: " + arquivo.getFileName());
                        } else {
                            System.out.println("[PARCIAL] " + arquivo.getFileName()
                                + ": saída parcial em " + resultado.arquivoSaida()
                                + "; corrija o cache e execute novamente.");
                        }
                    } catch (org.traducao.projeto.traducao.domain.exceptions.ObraDivergenteDoContextoException ex) {
                        // Guarda obra×contexto: a obra do caminho é reconhecida por outro
                        // contexto. É BLOQUEIO, não falha técnica — nada foi enviado ao LLM
                        // nem gravado em cache, e o lote segue para o próximo arquivo.
                        resultados.add(org.traducao.projeto.traducao.domain.ResultadoTraducaoArquivo.bloqueado(arquivo.getFileName().toString(), loreNome));
                        bloqueadosPorObra++;
                        registrarTelemetriaFalhaTraducao(arquivo, loreNome, org.traducao.projeto.traducao.domain.StatusArquivoTraducao.BLOQUEADO, ex.getMessage());
                        System.out.println("[BLOQUEADO] " + arquivo.getFileName() + ": " + ex.getMessage());
                    } catch (org.traducao.projeto.traducao.domain.exceptions.EntradaJaTraduzidaException ex) {
                        resultados.add(org.traducao.projeto.traducao.domain.ResultadoTraducaoArquivo.bloqueado(arquivo.getFileName().toString(), loreNome));
                        registrarTelemetriaFalhaTraducao(arquivo, loreNome, org.traducao.projeto.traducao.domain.StatusArquivoTraducao.BLOQUEADO, ex.getMessage());
                        System.out.println("[BLOQUEADO] " + arquivo.getFileName() + ": " + ex.getMessage());
                    } catch (org.traducao.projeto.traducao.domain.exceptions.TraducaoParcialException ex) {
                        int salvas = ex.getDicionarioParcial() != null ? ex.getDicionarioParcial().size() : 0;
                        // PARADA PEDIDA NÃO É FALHA. O episódio converte a interrupção cooperativa
                        // em TraducaoParcialException para salvar o progresso, e o flag de
                        // interrupção continua ligado — é ele que separa "o operador parou" de "o
                        // LLM caiu". Até 25/09/2026 as duas saíam como [FALHA]/FALHOU, e o ramo
                        // [PARADO] logo abaixo nunca era alcançado por este caminho: medido na
                        // auditoria, um "Sair" no meio do Zeta E01 virou "[FALHOU] Traducao via LLM
                        // finalizada" na tela e FALHOU na telemetria.
                        //
                        // A MARCA NA EXCEÇÃO é o sinal principal: o flag da thread é consumido no
                        // caminho (barra de progresso, espera da escrita atômica) — a primeira
                        // versão deste conserto lia só o flag, passou no teste de unidade e falhou
                        // no "Sair" real, que chegou aqui com o flag limpo.
                        if (ex.interrompidaPeloUsuario() || Thread.currentThread().isInterrupted()) {
                            parado = true;
                            System.out.println("[PARADO] " + arquivo.getFileName() + " interrompido pelo usuário ("
                                + salvas + " linha(s) salvas no cache para retomar); "
                                + (arquivos.size() - i - 1) + " arquivo(s) seguinte(s) não processado(s).");
                            break;
                        }
                        // Abortou antes de escrever a legenda de saída: é FALHA deste run
                        // (nenhum _PT-BR gerado), mesmo que N linhas tenham sido salvas no
                        // cache para retomar depois. Não pode contar como "ok" no lote.
                        resultados.add(org.traducao.projeto.traducao.domain.ResultadoTraducaoArquivo.falha(arquivo.getFileName().toString(), loreNome));
                        registrarTelemetriaFalhaTraducao(arquivo, loreNome, org.traducao.projeto.traducao.domain.StatusArquivoTraducao.FALHOU, ex.getMessage());
                        System.out.println("[FALHA] " + arquivo.getFileName() + " abortado sem gerar saída (" + salvas + " linha(s) salvas no cache para retomar).");
                    } catch (InterruptedException ie) {
                        // Cancelamento cooperativo (botão Parar / shutdown): não conta como falha
                        // do arquivo e encerra o lote — os restantes não são reprocessados.
                        Thread.currentThread().interrupt();
                        parado = true;
                        System.out.println("[PARADO] " + arquivo.getFileName() + " interrompido pelo usuário.");
                        break;
                    } catch (Exception ex) {
                        resultados.add(org.traducao.projeto.traducao.domain.ResultadoTraducaoArquivo.falha(arquivo.getFileName().toString(), loreNome));
                        registrarTelemetriaFalhaTraducao(arquivo, loreNome, org.traducao.projeto.traducao.domain.StatusArquivoTraducao.FALHOU, ex.getMessage());
                        System.out.println("[FAIL] " + arquivo.getFileName() + ": " + ex.getMessage());
                    }
                }

                String tabelaTraducao = org.traducao.projeto.traducao.presentation.ui.TabelaTraducaoRenderer.render(resultados);
                if (!tabelaTraducao.isBlank()) {
                    System.out.println(tabelaTraducao);
                }

                // O lote PARADO pelo operador é CANCELADO, nunca FALHOU: o valor já existia no enum
                // e nenhum caminho o produzia. Consolidar uma lista vazia (parada no 1º arquivo)
                // devolvia FALHOU — o pior rótulo para quem só apertou "Sair".
                org.traducao.projeto.traducao.domain.StatusLoteTraducao statusLote = parado
                    ? org.traducao.projeto.traducao.domain.StatusLoteTraducao.CANCELADO
                    : org.traducao.projeto.traducao.domain.StatusLoteTraducao.consolidar(resultados, bloqueadosPorObra);
                long okCount = resultados.stream().filter(r ->
                    r.status() == org.traducao.projeto.traducao.domain.StatusArquivoTraducao.CONCLUIDO).count();
                long parcialCount = resultados.stream().filter(r ->
                    r.status() == org.traducao.projeto.traducao.domain.StatusArquivoTraducao.PARCIAL).count();
                long falhaCount = resultados.size() - okCount - parcialCount;
                // Fechamento consolidado: tempo, vazão, destino das falas e pendências por
                // causa. Os dados já vinham no resultado de cada arquivo e eram descartados
                // aqui — a contagem de arquivos por status, sozinha, não dizia quanto demorou
                // nem o que sobrou.
                String rotuloStatus = statusLote.getRotulo().toUpperCase();
                System.out.println(org.traducao.projeto.traducao.presentation.ui.RelatorioLoteRenderer.render(
                    resultados, rotuloStatus, propriedades.tamanhoLote()));
                log.info("[{}] Traducao via LLM finalizada. {} concluido(s), {} parcial(is), {} falha/bloqueio de {}.",
                    statusLote.name(), okCount, parcialCount, falhaCount, resultados.size());
                desfecho = rotuloStatus;

            } catch (Exception e) {
                log.error("Erro na tradução em background", e);
                desfecho = "FALHA GERAL";
                System.out.println("\u001B[31m[ERRO] Falha geral no tradutor: " + e.getMessage() + "\u001B[0m");
            } finally {
                // A MÁQUINA avisa primeiro — é ela que continua ali quando o operador fechou o
                // navegador e foi programar. O evento leva ao frontend o desfecho E o veredito
                // do som, para a tela só tocar quando a máquina não conseguiu: dois avisos
                // simultâneos viram barulho, e nenhum aviso é o defeito que isto vem resolver.
                //
                // Custo MEDIDO nesta máquina em 2026-08-15: 2,80s de fila no fim de um lote de
                // até duas horas — os três toques somam 1,35s e o resto é o arranque do
                // PowerShell. Teto de 10s. Tocar DEPOIS de publicar deixaria o frontend sem o
                // veredito e faria a tela tocar por cima da máquina.
                org.traducao.projeto.traducao.infrastructure.AvisoSonoroSistema.Resultado som =
                    avisoSonoro.tocar(TOQUES_FIM_DE_LOTE);
                logStreamService.publicarLog(canalSse + SUFIXO_CANAL_FIM,
                    desfecho + SEPARADOR_EVENTO + som);
            }
        });

        return ResponseEntity.ok(new RespostaPadrao("Tradução via LLM iniciada no servidor."));
    }

    /**
     * PROPÓSITO DE NEGÓCIO: registra na telemetria um arquivo que FALHOU/foi
     * BLOQUEADO na tradução — sem isso, o dataset perdia justamente os casos mais
     * úteis (falhas). Carrega o lore e o status final para dar proveniência ao
     * registro.
     *
     * <p>INVARIANTES DO DOMÍNIO: o anime é inferido pela pasta avó do arquivo; o
     * status registrado é sempre o status final de falha/bloqueio informado.
     *
     * <p>COMPORTAMENTO EM CASO DE FALHA: motivo nulo é substituído pelo rótulo do
     * status; a origem indeterminada vira {@code "Desconhecido"}.
     */
    private void registrarTelemetriaFalhaTraducao(Path arquivo, String lore,
            org.traducao.projeto.traducao.domain.StatusArquivoTraducao status, String motivo) {
        String nome = arquivo.getFileName().toString();
        Path pai = arquivo.getParent();
        String anime = (pai != null && pai.getParent() != null && pai.getParent().getFileName() != null)
            ? pai.getParent().getFileName().toString() : "Desconhecido";
        telemetriaTraducao.registrarTraducao(new TelemetriaTraducao(
            nome, null, 0, 0, 0, 0L,
            java.util.List.of(motivo != null ? motivo : status.getRotulo()),
            anime, "Temporada Única", java.time.Instant.now().toString(),
            // null, e nao 0: este caminho e a FALHA ANTES DE PROCESSAR. O episodio nao foi
            // varrido, entao "nao medi" nao pode sair com a cara de "medi e nao havia italico".
            lore, status.name(), java.util.List.of(), null, null));
    }

    /**
     * PROPÓSITO DE NEGÓCIO: filtra os arquivos de legenda elegíveis para tradução
     * pela extensão suportada.
     *
     * <p>INVARIANTES DO DOMÍNIO: apenas {@code .ass/.ssa/.srt} são elegíveis; a
     * comparação ignora a caixa do nome do arquivo.
     *
     * <p>COMPORTAMENTO EM CASO DE FALHA: qualquer arquivo com extensão fora do
     * conjunto suportado retorna {@code false}.
     */
    private boolean temExtensaoSuportada(Path arquivo) {
        String nome = arquivo.getFileName().toString().toLowerCase();
        return EXTENSOES_SUPORTADAS.stream().anyMatch(nome::endsWith);
    }
}
