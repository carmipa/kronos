package org.traducao.projeto.trocaTipoLegenda.application;

import jakarta.inject.Inject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.traducao.projeto.core.util.DuracaoUtil;
import org.traducao.projeto.legenda.domain.DocumentoLegenda;
import org.traducao.projeto.trocaTipoLegenda.domain.ResultadoTrocaFonte;
import org.traducao.projeto.trocaTipoLegenda.domain.exceptions.TrocaTipoLegendaException;
import org.traducao.projeto.trocaTipoLegenda.domain.ports.ArmazenamentoBackupPort;
import org.traducao.projeto.trocaTipoLegenda.domain.ports.ConsoleTrocaPort;
import org.traducao.projeto.trocaTipoLegenda.domain.ports.LegendaIoPort;
import org.traducao.projeto.trocaTipoLegenda.domain.ports.TelemetriaTrocaPort;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;

/**
 * PROPÓSITO DE NEGÓCIO: aplica em lote o "achatamento" de estilos decorativos numa
 * pasta de legendas .ass — transforma letras de abertura/encerramento e placas
 * (fontes decorativas como Dash Horizon/Althea/Androgyne, com {@code \pos}/{@code \fad})
 * em legendas brancas legíveis no estilo Default. É o passo que faltava na tela de
 * Troca de Legenda: a substituição de fontes só conserta fontes ANSI quebradas, e a
 * cura de tags PRESERVA a formatação — nenhum dos dois removia a frescura visual.
 *
 * <h2>Invariantes do domínio</h2>
 * <ul>
 *   <li>Nunca sobrescreve sem antes copiar o arquivo original para
 *       {@code backups/achatar_estilos_<timestamp>/} — a operação é reversível.</li>
 *   <li>Só grava arquivos em que houve achatamento efetivo; arquivos sem estilo
 *       decorativo são pulados byte a byte.</li>
 *   <li>A pasta de destino da gravação é a MESMA de origem (edição in-place com
 *       backup); a extensão e o conteúdo não-decorativo são preservados pelo par
 *       {@link LeitorLegendaAss}/{@link EscritorLegendaAss}.</li>
 * </ul>
 *
 * <h2>Comportamento em caso de falha</h2>
 * Diretório inválido lança {@link TrocaTipoLegendaException}. Falha ao ler/gravar um
 * arquivo específico é registrada no console e contabilizada, sem abortar os demais;
 * o backup já feito permanece para reversão manual. Pasta sem legendas devolve um
 * resultado zerado, sem exceção.
 */
@Service
public class AchatarEstilosUseCase {

    private static final Logger log = LoggerFactory.getLogger(AchatarEstilosUseCase.class);
    private static final String OPERACAO = "Achatar Estilos Decorativos";

    private final LegendaIoPort legendaIo;
    private final AchatadorEstilosDecorativosService achatador;
    private final TelemetriaTrocaPort telemetria;
    private final ArmazenamentoBackupPort backup;
    private final ConsoleTrocaPort console;

    @Inject
    public AchatarEstilosUseCase(
        LegendaIoPort legendaIo,
        AchatadorEstilosDecorativosService achatador,
        TelemetriaTrocaPort telemetria,
        ArmazenamentoBackupPort backup,
        ConsoleTrocaPort console
    ) {
        this.legendaIo = legendaIo;
        this.achatador = achatador;
        this.telemetria = telemetria;
        this.backup = backup;
        this.console = console;
    }

    /**
     * PROPÓSITO DE NEGÓCIO: varre a pasta, achata os estilos decorativos de cada
     * legenda e grava as alteradas, produzindo o resumo consumido pela tela.
     *
     * <p>INVARIANTES DO DOMÍNIO: cada arquivo gravado tem backup prévio; contadores
     * (arquivos alterados, falas achatadas) refletem apenas gravações concluídas.
     *
     * <p>COMPORTAMENTO EM CASO DE FALHA: diretório inexistente lança exceção antes de
     * qualquer escrita; falha por arquivo não impede os demais, entra em {@code totalFalhas}
     * e faz o fim da operação sair como aviso, nunca como sucesso.
     */
    public ResultadoTrocaFonte aplicar(Path diretorio) {
        long inicioMs = System.currentTimeMillis();
        validarDiretorio(diretorio);

        console.titulo("\n=== Iniciando Achatamento de Estilos Decorativos ===");
        console.info("Pasta alvo: " + diretorio.toAbsolutePath());

        List<Path> arquivos = LegendasDaPasta.listar(diretorio);
        if (arquivos.isEmpty()) {
            console.aviso("[AVISO] Nenhum arquivo .ass/.ssa nesta pasta."
                + TrocaTipoLegendaUseCase.avisoSubpastas(LegendasDaPasta.contarEmSubpastas(diretorio)));
            console.info(DuracaoUtil.linhaRelatorioFinal(OPERACAO, inicioMs));
            return new ResultadoTrocaFonte(0, 0, 0, LocalDateTime.now().toString(), "N/A", null, 0);
        }

        Path pastaBackup = backup.abrirSessao("achatar_estilos");
        console.info("Diretório de backup criado: " + pastaBackup);

        int totalAlterados = 0;
        int totalFalas = 0;
        int totalSilabas = 0;
        int totalFalhas = 0;
        for (Path arq : arquivos) {
            if (Thread.currentThread().isInterrupted()) {
                console.aviso("[AVISO] Execução interrompida — arquivos já gravados foram preservados.");
                break;
            }
            try {
                DocumentoLegenda doc = legendaIo.ler(arq);
                AchatadorEstilosDecorativosService.Resultado r = achatador.achatar(doc);
                if (!r.houveAchatamento()) {
                    console.info("Arquivo " + arq.getFileName() + " [OK] — sem estilos decorativos. Pulando.");
                    continue;
                }
                // Backup ANTES de escrever, sempre nesta ordem: o achatamento descarta a camada
                // de timing do karaokê, e sem a cópia intacta essa perda seria definitiva.
                backup.preservar(pastaBackup, arq);
                legendaIo.escrever(arq, r.documento());
                totalAlterados++;
                totalFalas += r.falasAchatadas();
                totalSilabas += r.silabasDescartadas();
                // O descarte é a diferença entre uma abertura legível e 138 legendas de uma
                // palavra piscando sobre o vídeo — precisa aparecer no console, não só no total.
                String silabas = r.silabasDescartadas() > 0
                    ? "; " + r.silabasDescartadas() + " sílaba(s) de karaokê descartada(s)"
                    : "";
                console.sucesso("  [ACHATADO] " + arq.getFileName() + ": " + r.falasAchatadas()
                    + " fala(s); estilos " + r.estilosDecorativos() + " -> " + r.estiloBase() + silabas + ".");
            } catch (Exception e) {
                totalFalhas++;
                console.erro("  [ERRO] Falha ao achatar " + arq.getFileName() + ": " + e.getMessage());
                log.error("Erro ao achatar estilos de {}", arq, e);
            }
        }

        // Com falha o fim é AVISO, não SUCESSO: o banner verde escondia os arquivos não gravados.
        if (totalFalhas > 0) {
            console.aviso("\n========================================================================");
            console.aviso("  [AVISO] ACHATAMENTO TERMINOU COM " + totalFalhas + " ARQUIVO(S) SEM GRAVAR");
        } else {
            console.sucesso("\n========================================================================");
            console.sucesso("  [SUCESSO] ACHATAMENTO DE ESTILOS DECORATIVOS FINALIZADO!");
        }
        console.info("  • Arquivos analisados : " + arquivos.size());
        console.info("  • Arquivos alterados  : " + totalAlterados);
        console.info("  • Falas achatadas     : " + totalFalas);
        console.info("  • Sílabas descartadas : " + totalSilabas + " (camada de timing de karaokê)");
        console.info("  • Arquivos com falha  : " + totalFalhas);
        console.info("  • Pasta de Backup     : " + pastaBackup);
        if (totalFalhas > 0) {
            console.aviso("========================================================================");
        } else {
            console.sucesso("========================================================================");
        }

        long duracaoMs = System.currentTimeMillis() - inicioMs;
        telemetria.registrar(
            OPERACAO,
            "Diretório: " + diretorio.getFileName() + "; alterados=" + totalAlterados + "; falas=" + totalFalas,
            duracaoMs,
            arquivos.size(),
            totalAlterados,
            totalFalas);
        console.info(DuracaoUtil.linhaRelatorioFinal(OPERACAO, inicioMs));

        return new ResultadoTrocaFonte(
            arquivos.size(), totalAlterados, totalFalas, LocalDateTime.now().toString(), pastaBackup.toString(), null,
            totalFalhas);
    }

    private void validarDiretorio(Path dir) {
        if (dir == null) {
            throw new TrocaTipoLegendaException("Diretório de legendas não pode ser nulo.");
        }
        if (!Files.isDirectory(dir)) {
            throw new TrocaTipoLegendaException("O caminho fornecido não é uma pasta existente: " + dir);
        }
    }

    // A escrita no console saiu daqui para ConsoleTrocaPort: o redirecionamento ao SSE
    // (canal "troca-tipo-legenda", definido pelo controller) continua funcionando porque o
    // adaptador escreve no mesmo System.out. O que mudou é que a cor deixou de ser decidida
    // aqui — este caso de uso declara sucesso/aviso/erro e não conhece código de escape.
}
