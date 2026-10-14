package org.traducao.projeto.remuxer.application;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.traducao.projeto.core.texto.FonteVietnamitaLegada;
import org.traducao.projeto.legendasExtracao.application.ValidadorSaidaExtracao;
import org.traducao.projeto.legendasExtracao.domain.ExtratorException;
import org.traducao.projeto.legendasExtracao.domain.FormatoLegenda;
import org.traducao.projeto.remuxer.domain.LegendaComFonteVietnamitaException;
import org.traducao.projeto.remuxer.domain.PlanoRemux;
import org.traducao.projeto.remuxer.domain.RelatorioRemux;
import org.traducao.projeto.remuxer.domain.RemuxTarefa;
import org.traducao.projeto.remuxer.domain.RemuxerException;
import org.traducao.projeto.remuxer.domain.SaidaRemuxJaExisteException;
import org.traducao.projeto.remuxer.infrastructure.adapters.MkvmergeAdapter;
import org.traducao.projeto.remuxer.presentation.ui.ConsoleRemuxerLogger;
import org.traducao.projeto.telemetria.TelemetriaService;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

/**
 * PROPÓSITO DE NEGÓCIO: orquestra o remux em lote, da validação das entradas à
 * telemetria final, sem reencodar vídeo/áudio.
 *
 * <p>INVARIANTES DO DOMÍNIO: somente legenda textual válida chega ao mkvmerge;
 * cada sucesso representa temporário validado e publicado; cancelamento é
 * observado entre arquivos.
 *
 * <p>COMPORTAMENTO EM CASO DE FALHA: o lote preserva sucessos anteriores,
 * classifica falhas/pendências e sempre tenta registrar status final no dataset.
 */
@Service
public class RemuxarLoteUseCase {
    private static final Logger log = LoggerFactory.getLogger(RemuxarLoteUseCase.class);

    /**
     * Subpasta criada dentro da pasta de vídeos quando o operador não escolhe um
     * destino. É o comportamento histórico e continua sendo o padrão: quem não
     * mexe no campo novo não vê diferença nenhuma.
     */
    public static final String PASTA_SAIDA_PADRAO = "mkv_final_ptbr";

    private final MkvmergeAdapter mkvmergeAdapter;
    private final MapeadorMidiaService mapeadorMidiaService;
    private final ConsoleRemuxerLogger console;
    private final TelemetriaService telemetriaService;

    /**
     * PROPÓSITO DE NEGÓCIO: recebe as fronteiras responsáveis por planejamento,
     * execução externa, console e observabilidade.
     *
     * <p>INVARIANTES DO DOMÍNIO: dependências são compartilhadas pelo container e
     * o relatório permanece local a cada execução.
     *
     * <p>COMPORTAMENTO EM CASO DE FALHA: dependência ausente é erro de bootstrap.
     */
    public RemuxarLoteUseCase(MkvmergeAdapter mkvmergeAdapter, MapeadorMidiaService mapeadorMidiaService,
            ConsoleRemuxerLogger console, TelemetriaService telemetriaService) {
        this.mkvmergeAdapter = mkvmergeAdapter;
        this.mapeadorMidiaService = mapeadorMidiaService;
        this.console = console;
        this.telemetriaService = telemetriaService;
    }

    /**
     * PROPÓSITO DE NEGÓCIO: executa lote padrão sem offset e substituindo as
     * legendas originais pela faixa PT-BR final.
     *
     * <p>INVARIANTES DO DOMÍNIO: delega ao fluxo completo com opções explícitas.
     *
     * <p>COMPORTAMENTO EM CASO DE FALHA: devolve relatório classificado.
     */
    public RelatorioRemux executar(Path pastaVideos, Path pastaLegendas) {
        return executar(pastaVideos, pastaLegendas, 0, null);
    }

    /**
     * PROPÓSITO DE NEGÓCIO: mantém compatibilidade com offset manual publicando
     * na pasta de saída padrão.
     *
     * <p>INVARIANTES DO DOMÍNIO: offset é aplicado igualmente a todo o lote.
     *
     * <p>COMPORTAMENTO EM CASO DE FALHA: devolve relatório classificado.
     */
    public RelatorioRemux executar(Path pastaVideos, Path pastaLegendas, long sincronismoMs) {
        return executar(pastaVideos, pastaLegendas, sincronismoMs, null);
    }

    /**
     * PROPÓSITO DE NEGÓCIO: executa o lote com offset e destino explícito para os
     * MKVs publicados.
     *
     * <p>Onde havia um booleano {@code preservarLegendasOriginais}: ele deixou de
     * existir. O caminho que apagava faixa foi REMOVIDO do adaptador em
     * 2026-07-29 (decisão do Paulo), e desde então o parâmetro só decidia qual
     * frase o console imprimia — o CLI passava {@code false} e anunciava
     * "remover legendas originais" enquanto o adaptador preservava todas. Um
     * parâmetro inerte que faz o console mentir é pior que parâmetro nenhum.
     *
     * <p>INVARIANTES DO DOMÍNIO: telemetria é emitida em sucesso, pendência,
     * cancelamento e falha; exceção inesperada não produz sucesso falso; o
     * destino nunca coincide com a pasta varrida em busca de vídeos
     * (INV-REMUX-DESTINO-001).
     *
     * <p>COMPORTAMENTO EM CASO DE FALHA: captura a fronteira do lote, registra
     * erro inesperado, finaliza o relatório e retorna ao controller.
     */
    public RelatorioRemux executar(Path pastaVideos, Path pastaLegendas, long sincronismoMs,
                                   Path pastaDestinoEscolhida) {
        long inicioMs = System.currentTimeMillis();
        RelatorioRemux relatorio = new RelatorioRemux();
        Path destinoEfetivo = pastaDestinoEscolhida;
        try {
            destinoEfetivo = executarInterno(pastaVideos, pastaLegendas, sincronismoMs,
                pastaDestinoEscolhida, relatorio);
        } catch (Exception e) {
            log.error("Falha inesperada no lote de remux", e);
            console.erro("Falha inesperada no lote de remux: " + e.getMessage());
            relatorio.registrarErroInesperado();
        } finally {
            relatorio.finalizar();
            registrarTelemetria(pastaVideos, pastaLegendas, sincronismoMs,
                destinoEfetivo, inicioMs, relatorio);
        }
        return relatorio;
    }

    /**
     * PROPÓSITO DE NEGÓCIO: resolve onde os MKVs finais serão publicados, mantendo
     * como padrão a subpasta histórica dentro da pasta de vídeos.
     *
     * <p>INVARIANTES DO DOMÍNIO: ausência de escolha ⇒
     * {@code <pasta de vídeos>/mkv_final_ptbr}; escolha explícita é usada tal como
     * veio, já normalizada pela borda.
     *
     * <p>COMPORTAMENTO EM CASO DE FALHA: nunca devolve {@code null} — sempre há
     * um destino resolvido para o chamador validar.
     */
    public static Path resolverPastaDestino(Path pastaVideos, Path pastaDestinoEscolhida) {
        return pastaDestinoEscolhida == null
            ? pastaVideos.resolve(PASTA_SAIDA_PADRAO)
            : pastaDestinoEscolhida;
    }

    /**
     * PROPÓSITO DE NEGÓCIO: impede que o MKV publicado caia no mesmo diretório que
     * a execução seguinte varre em busca de vídeo ou de legenda.
     *
     * <p>INVARIANTES DO DOMÍNIO (INV-REMUX-DESTINO-001): o dano não é sobrescrita
     * — disso o {@code SaidaRemuxJaExisteException} já cuida — é o remuxado virar
     * ENTRADA de um remux de remux, sem o operador ter como distinguir o gerado
     * do original. A comparação é por caminho absoluto normalizado, porque
     * {@code C:\a\b} e {@code C:\a\.\b} são o mesmo diretório.
     *
     * <p>COMPORTAMENTO EM CASO DE FALHA: devolve o motivo pronto para virar HTTP
     * 400 na web ou mensagem de erro no CLI; {@code null} significa destino
     * aceitável.
     */
    public static String motivoDestinoRecusado(Path pastaVideos, Path pastaLegendas, Path pastaDestino) {
        Path destino = pastaDestino.toAbsolutePath().normalize();
        if (destino.equals(pastaVideos.toAbsolutePath().normalize())) {
            return "A pasta de destino não pode ser a própria pasta de vídeos: o MKV gerado seria lido"
                + " como vídeo de entrada na próxima execução. Escolha outra pasta ou deixe o campo"
                + " vazio para usar a subpasta padrão '" + PASTA_SAIDA_PADRAO + "'.";
        }
        if (destino.equals(pastaLegendas.toAbsolutePath().normalize())) {
            return "A pasta de destino não pode ser a pasta das legendas traduzidas."
                + " Escolha outra pasta ou deixe o campo vazio para usar a subpasta padrão '"
                + PASTA_SAIDA_PADRAO + "'.";
        }
        return null;
    }

    /**
     * PROPÓSITO DE NEGÓCIO: realiza validação, planejamento e processamento
     * sequencial de cada tarefa do lote.
     *
     * <p>INVARIANTES DO DOMÍNIO: pasta de saída é filha de vídeos; avisos do plano
     * entram no relatório antes do primeiro processo; interrupção impede iniciar
     * nova tarefa.
     *
     * <p>COMPORTAMENTO EM CASO DE FALHA: problemas de infraestrutura encerram;
     * problemas por arquivo não impedem os próximos, salvo cancelamento. Devolve
     * o destino realmente usado para a telemetria registrá-lo mesmo quando o
     * lote não chega ao fim.
     */
    private Path executarInterno(Path pastaVideos, Path pastaLegendas, long sincronismoMs,
                                 Path pastaDestinoEscolhida, RelatorioRemux relatorio) {
        Path pastaSaida = resolverPastaDestino(pastaVideos, pastaDestinoEscolhida);
        if (!Files.isDirectory(pastaVideos) || !Files.isDirectory(pastaLegendas)) {
            console.erro("Pasta de vídeos ou legendas não encontrada. Vídeos=" + pastaVideos
                + " | Legendas=" + pastaLegendas);
            relatorio.registrarErroInfra();
            return pastaSaida;
        }
        // Segunda camada do INV-REMUX-DESTINO-001: a borda HTTP já recusa com 400,
        // mas o CLI e qualquer chamador futuro entram por aqui.
        String motivoRecusa = motivoDestinoRecusado(pastaVideos, pastaLegendas, pastaSaida);
        if (motivoRecusa != null) {
            console.erro("Destino recusado: " + motivoRecusa);
            relatorio.registrarErroInfra();
            return pastaSaida;
        }
        try {
            mkvmergeAdapter.validarInfraestrutura();
        } catch (RemuxerException e) {
            console.erro("Falha na validação do MKVToolNix: " + e.getMessage());
            relatorio.registrarErroInfra();
            return pastaSaida;
        }

        try {
            Files.createDirectories(pastaSaida);
        } catch (IOException e) {
            console.erro("Não foi possível criar pasta de saída: " + pastaSaida + " — " + e.getMessage());
            relatorio.registrarErroInfra();
            return pastaSaida;
        }

        PlanoRemux plano;
        try {
            plano = mapeadorMidiaService.construirPlano(pastaVideos, pastaLegendas, pastaSaida);
        } catch (RemuxerException e) {
            console.erro("Falha ao mapear vídeos e legendas: " + e.getMessage());
            relatorio.registrarErroInfra();
            return pastaSaida;
        }
        relatorio.registrarDeteccao(plano.videosDetectados(), plano.tarefas().size());
        for (int i = 0; i < plano.videosSemLegenda(); i++) relatorio.registrarSemLegenda();
        for (int i = 0; i < plano.pareamentosAmbiguos(); i++) relatorio.registrarPareamentoAmbiguo();
        plano.avisos().forEach(console::aviso);

        console.info("Vídeos=" + plano.videosDetectados() + " | Legendas=" + plano.legendasDetectadas()
            + " | Pareados=" + plano.tarefas().size() + " | Sem legenda=" + plano.videosSemLegenda()
            + " | Ambíguos=" + plano.pareamentosAmbiguos());
        // Uma frase só, porque só existe uma política: nenhum caminho apaga faixa.
        console.info("Política de faixas: as legendas originais são preservadas; a PT-BR entra como padrão.");
        console.info("Destino dos MKVs finais: " + pastaSaida
            + (pastaDestinoEscolhida == null ? " (padrão)" : " (escolhido)"));
        if (sincronismoMs != 0) console.info("Sincronismo manual do lote: " + sincronismoMs + "ms");

        for (int indice = 0; indice < plano.tarefas().size(); indice++) {
            if (Thread.currentThread().isInterrupted()) {
                relatorio.registrarCancelamento();
                console.aviso("Cancelamento detectado antes do próximo arquivo; nenhum novo remux será iniciado.");
                break;
            }
            RemuxTarefa tarefa = plano.tarefas().get(indice);
            console.info("[REMUX " + (indice + 1) + "/" + plano.tarefas().size() + "] "
                + tarefa.nomeVideo() + " + " + tarefa.caminhoLegenda().getFileName()
                + " -> " + tarefa.caminhoSaida().getFileName());
            try {
                validarLegenda(tarefa.caminhoLegenda());
                mkvmergeAdapter.executarRemux(tarefa, sincronismoMs);
                long bytes = Files.size(tarefa.caminhoSaida());
                relatorio.registrarSucesso(bytes);
                console.sucesso("[OK " + (indice + 1) + "/" + plano.tarefas().size()
                    + "] MKV validado: " + tarefa.caminhoSaida().getFileName());
            } catch (SaidaRemuxJaExisteException e) {
                relatorio.registrarSaidaJaExistente();
                console.aviso("[PRESERVADO] " + e.getMessage());
            } catch (LegendaComFonteVietnamitaException e) {
                relatorio.registrarLegendaComFonteVietnamita();
                console.erro("[REPROVADA — FONTE VIETNAMITA] " + tarefa.caminhoLegenda().getFileName() + " — "
                    + e.getMessage());
            } catch (ExtratorException e) {
                relatorio.registrarErroLegendaInvalida();
                console.erro("[LEGENDA INVÁLIDA] " + tarefa.caminhoLegenda().getFileName() + " — " + e.getMessage());
            } catch (RemuxerException e) {
                if (Thread.currentThread().isInterrupted()) {
                    relatorio.registrarCancelamento();
                    console.aviso("Remux cancelado durante " + tarefa.nomeVideo() + "; parcial descartado.");
                    break;
                }
                relatorio.registrarErroRuntime();
                console.erro("[FALHA MKVMERGE] " + tarefa.nomeVideo() + " — " + e.getMessage());
            } catch (IOException e) {
                relatorio.registrarErroIo();
                console.erro("[FALHA I/O] " + tarefa.nomeVideo() + " — " + e.getMessage());
            } catch (Exception e) {
                relatorio.registrarErroInesperado();
                console.erro("[FALHA INESPERADA] " + tarefa.nomeVideo() + " — " + e.getMessage());
            }
        }
        return pastaSaida;
    }

    /**
     * PROPÓSITO DE NEGÓCIO: recusa arquivo vazio ou com conteúdo incompatível
     * com a extensão ASS/SRT antes de invocar o mkvmerge — e, no ASS, a legenda
     * que põe fonte vietnamita antiga na tela (o Gundam ZZ publicado em 09/10/2026
     * mostrava "Nóo, ộ a atraỗóo!"; ordem de Paulo em 14/10: reprova e pede a 1.3).
     *
     * <p>INVARIANTES DO DOMÍNIO: apenas ASS e SRT textuais são aceitos; a fonte é
     * julgada pela regra única do kernel ({@link FonteVietnamitaLegada}), a mesma da
     * 1.3 e da 4.1, só no que vai à tela (estilo usado por fala, {@code \fn} na fala).
     *
     * <p>COMPORTAMENTO EM CASO DE FALHA: formato desconhecido ou assinatura
     * inválida lança {@link ExtratorException}; fonte vietnamita antiga lança
     * {@link LegendaComFonteVietnamitaException}; nos dois, a tarefa é pulada.
     */
    private void validarLegenda(Path legenda) throws IOException {
        String nome = legenda.getFileName().toString().toLowerCase(Locale.ROOT);
        FormatoLegenda formato;
        if (nome.endsWith(".ass")) {
            formato = FormatoLegenda.ASS;
        } else if (nome.endsWith(".srt")) {
            formato = FormatoLegenda.SRT;
        } else {
            throw new ExtratorException("Formato não suportado no remux: " + nome);
        }
        ValidadorSaidaExtracao.validar(legenda, formato);
        if (formato == FormatoLegenda.ASS) {
            // Leitura tolerante, como a do ValidadorSaidaExtracao: legenda externa em ANSI continua
            // passando (os nomes de fonte são ASCII); Files.readString lançaria e viraria FALHA I/O.
            String conteudo = new String(Files.readAllBytes(legenda), StandardCharsets.UTF_8);
            List<String> achados = FonteVietnamitaLegada.achadosNoAss(conteudo);
            if (!achados.isEmpty()) {
                throw new LegendaComFonteVietnamitaException("fonte vietnamita antiga na tela ("
                    + String.join("; ", achados) + "). Rode a 1.3 Troca de Tipo de Legenda (Arial) na pasta "
                    + "das legendas e refaça o remux; o MKV deste episódio não foi gerado.");
            }
        }
    }

    /**
     * PROPÓSITO DE NEGÓCIO: registra o lote como dataset com status, destino,
     * offset, pendências, falhas e volume final.
     *
     * <p>INVARIANTES DO DOMÍNIO: itens corrigidos equivalem a MKVs efetivamente
     * publicados; detalhe contém o status real e o destino REALMENTE usado — sem
     * ele, duas execuções da mesma obra em pastas diferentes ficam
     * indistinguíveis no dataset.
     *
     * <p>COMPORTAMENTO EM CASO DE FALHA: telemetria é secundária e não altera os
     * arquivos já concluídos.
     */
    private void registrarTelemetria(Path videos, Path legendas, long sincronismoMs,
                                     Path destino, long inicioMs, RelatorioRemux relatorio) {
        String detalhe = "status=" + relatorio.getStatusFinal()
            + "; videos=" + videos + "; legendas=" + legendas
            + "; destino=" + (destino == null ? videos.resolve(PASTA_SAIDA_PADRAO) : destino)
            + "; syncMs=" + sincronismoMs + "; preservarOriginais=true"
            + "; semLegenda=" + relatorio.getVideosSemLegenda()
            + "; ambiguos=" + relatorio.getPareamentosAmbiguos()
            + "; existentes=" + relatorio.getSaidasJaExistentes()
            + "; fonteVietnamita=" + relatorio.getLegendasComFonteVietnamita()
            + "; falhas=" + relatorio.getTotalErros()
            + "; bytes=" + relatorio.getBytesMkvGeradosTotal();
        try {
            telemetriaService.registrarOperacao(TelemetriaService.criarOperacao(
                "Remux (mkvmerge)", detalhe, System.currentTimeMillis() - inicioMs,
                relatorio.getMkvDetectados(), relatorio.getLegendasPareadas(),
                relatorio.getMkvProcessadosSucesso()));
        } catch (RuntimeException e) {
            log.warn("Remux concluído, mas a telemetria não pôde ser registrada: {}", e.getMessage());
        }
    }
}
