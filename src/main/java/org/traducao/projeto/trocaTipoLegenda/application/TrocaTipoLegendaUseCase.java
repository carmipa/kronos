package org.traducao.projeto.trocaTipoLegenda.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import jakarta.inject.Inject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.traducao.projeto.legenda.domain.DocumentoLegenda;
import org.traducao.projeto.core.presentation.ui.AnsiCores;
// DIVIDA REMANESCENTE: TelemetriaService entra aqui so pelo utilitario estatico
// resolverPastaRelatorios. O registro de telemetria ja passa por TelemetriaTrocaPort;
// falta uma porta para a PASTA de relatorios, junto com a saida de console desta classe.
import org.traducao.projeto.telemetria.TelemetriaService;
import org.traducao.projeto.trocaTipoLegenda.domain.ports.ArmazenamentoBackupPort;
import org.traducao.projeto.trocaTipoLegenda.domain.ports.AuditoriaTrocaFontePort;
import org.traducao.projeto.trocaTipoLegenda.domain.ports.LegendaIoPort;
import org.traducao.projeto.trocaTipoLegenda.domain.ports.TelemetriaTrocaPort;
import org.traducao.projeto.trocaTipoLegenda.domain.AuditoriaFonteInfo;
import org.traducao.projeto.trocaTipoLegenda.domain.AuditoriaLegendaResultado;
import org.traducao.projeto.trocaTipoLegenda.domain.EntradaAuditoriaTrocaFonte;
import org.traducao.projeto.trocaTipoLegenda.domain.ResultadoGeralAuditoria;
import org.traducao.projeto.trocaTipoLegenda.domain.ResultadoTrocaFonte;
import org.traducao.projeto.trocaTipoLegenda.domain.exceptions.TrocaTipoLegendaException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

@Service
public class TrocaTipoLegendaUseCase {

    private static final Logger log = LoggerFactory.getLogger(TrocaTipoLegendaUseCase.class);
    private static final DateTimeFormatter UTC_FORMATTER = DateTimeFormatter.ISO_INSTANT;
    private static final DateTimeFormatter TIMESTAMP_DIR = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss");

    private final LegendaIoPort legendaIo;
    private final AuditoriaFontesService auditoriaService;
    private final TelemetriaTrocaPort telemetria;
    private final AuditoriaTrocaFontePort auditoriaCache;
    private final ObjectMapper objectMapper;
    private final ArmazenamentoBackupPort backup;

    private static final class SessaoTroca {
        final long inicioMs = System.currentTimeMillis();

        void out(String msg) {
            String limpa = msg.replaceAll("\\u001B\\[[0-9;]*m", "").replaceAll("\\033\\[[0-9;]*m", "");
            long decorridoMs = Math.max(0, System.currentTimeMillis() - inicioMs);
            String prefixo = "[UTC " + UTC_FORMATTER.format(Instant.now()) + " | +" + formatarDuracao(decorridoMs) + "]";
            String logCompleto = prefixo + " " + limpa;
            
            System.out.println(msg);
            log.info(logCompleto);
        }

        private String formatarDuracao(long ms) {
            long s = ms / 1000;
            long m = s / 60;
            s = s % 60;
            return String.format("%02d:%02d", m, s);
        }
    }

    /**
     * PROPÓSITO DE NEGÓCIO: compõe a troca de fontes usando a pasta operacional
     * padrão de backups do projeto.
     * <p>INVARIANTES DO DOMÍNIO: produção grava somente sob `backups`.
     * <p>COMPORTAMENTO EM CASO DE FALHA: dependência inválida impede a operação.
     */
    @Inject
    public TrocaTipoLegendaUseCase(
        LegendaIoPort legendaIo,
        AuditoriaFontesService auditoriaService,
        TelemetriaTrocaPort telemetria,
        AuditoriaTrocaFontePort auditoriaCache,
        ArmazenamentoBackupPort backup
    ) {
        this.legendaIo = legendaIo;
        this.auditoriaService = auditoriaService;
        this.telemetria = telemetria;
        this.auditoriaCache = auditoriaCache;
        this.objectMapper = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
        this.backup = backup;
    }

    /**
     * PROPÓSITO DE NEGÓCIO: cria uma instância de teste com o armazenamento de backup
     * isolado, sem ampliar o contrato público de produção.
     *
     * <p>Antes recebia uma {@code Path raizBackups} e o caso de uso resolvia diretório e
     * cópia por conta própria. Agora o isolamento vem de passar outro
     * {@link ArmazenamentoBackupPort} — o teste troca a implementação, não o caminho.
     *
     * <p>COMPORTAMENTO EM CASO DE FALHA: dependência nula falha na primeira chamada.
     */
    static TrocaTipoLegendaUseCase criarParaTeste(
        LegendaIoPort legendaIo,
        AuditoriaFontesService auditoriaService,
        TelemetriaTrocaPort telemetria,
        AuditoriaTrocaFontePort auditoriaCache,
        ArmazenamentoBackupPort backup
    ) {
        return new TrocaTipoLegendaUseCase(
            legendaIo, auditoriaService, telemetria, auditoriaCache, backup);
    }

    /**
     * PROPÓSITO DE NEGÓCIO: diagnostica, sem gravar nada, quais legendas da pasta usam fonte que
     * desenha a letra errada — é o "checar os outros animes" antes de trocar.
     *
     * <p>INVARIANTES DO DOMÍNIO: olha os estilos do cabeçalho E o {@code \fn} dentro das falas, pela
     * mesma regra única que reprova na 4.1 e no remux; só leitura.
     *
     * <p>COMPORTAMENTO EM CASO DE FALHA: pasta inválida lança {@link TrocaTipoLegendaException};
     * arquivo ilegível vira linha {@code ERRO_LEITURA} e o lote segue.
     */
    public ResultadoGeralAuditoria escanear(Path diretorio) {
        validarDiretorio(diretorio);

        List<Path> arquivos = listarLegendas(diretorio);
        List<AuditoriaLegendaResultado> resultados = new ArrayList<>();
        int totalComProblemas = 0;

        for (Path arq : arquivos) {
            try {
                DocumentoLegenda doc = legendaIo.ler(arq);
                List<AuditoriaFonteInfo> fontes = new ArrayList<>(auditoriaService.analisarCabecalho(doc.cabecalho()));
                fontes.addAll(auditoriaService.analisarFontesNasFalas(doc.eventos()));
                boolean temProblemas = fontes.stream().anyMatch(AuditoriaFonteInfo::problematica);
                
                if (temProblemas) {
                    totalComProblemas++;
                }

                resultados.add(new AuditoriaLegendaResultado(
                    arq.getFileName().toString(),
                    tipoLegenda(arq),
                    fontes,
                    temProblemas
                ));
            } catch (Exception e) {
                log.warn("Erro ao auditar fontes do arquivo: " + arq.getFileName(), e);
                resultados.add(new AuditoriaLegendaResultado(
                    arq.getFileName().toString(),
                    tipoLegenda(arq),
                    List.of(new AuditoriaFonteInfo("ERRO_LEITURA", "N/A", "N/A", false)),
                    false
                ));
            }
        }

        return new ResultadoGeralAuditoria(resultados, arquivos.size(), totalComProblemas);
    }

    public ResultadoTrocaFonte aplicar(Path diretorio) {
        return aplicar(diretorio, false);
    }

    /**
     * PROPÓSITO DE NEGÓCIO: troca por Arial a fonte vietnamita antiga das legendas da pasta — no
     * cabeçalho e no {@code \fn} das falas —, que é o que a 4.1 e o remux pedem quando reprovam.
     *
     * <p>INVARIANTES DO DOMÍNIO: backup ANTES de escrever; só a coluna Fontname e o nome depois do
     * {@code \fn} mudam; arquivo sem fonte legada não é tocado (no modo forçado, todo estilo vira
     * Arial, e o {@code \fn} legado das falas também).
     *
     * <p>COMPORTAMENTO EM CASO DE FALHA: pasta inválida lança {@link TrocaTipoLegendaException}; erro
     * num arquivo vira linha FALHA na auditoria e o lote segue; relatório que não grava vira aviso.
     */
    public ResultadoTrocaFonte aplicar(Path diretorio, boolean forcarArial) {
        SessaoTroca sessao = new SessaoTroca();
        validarDiretorio(diretorio);

        String modo = forcarArial ? "Substituição Manual para Arial" : "Substituição em Lote de Fontes ASS";
        sessao.out(AnsiCores.CYAN + "\n=== Iniciando " + modo + " ===" + AnsiCores.RESET);
        sessao.out("Pasta alvo: " + diretorio.toAbsolutePath());
        if (forcarArial) {
            sessao.out(AnsiCores.YELLOW
                + "[FORÇADO] O operador solicitou normalizar todos os estilos para Arial, mesmo sem diagnóstico automático."
                + AnsiCores.RESET);
        }

        List<Path> arquivos = listarLegendas(diretorio);
        if (arquivos.isEmpty()) {
            sessao.out(AnsiCores.YELLOW + "[AVISO] Nenhum arquivo .ass/.ssa encontrado." + AnsiCores.RESET);
            return new ResultadoTrocaFonte(0, 0, 0, LocalDateTime.now().toString(), "N/A", null);
        }

        // Criar pasta de backup automático
        Path pastaBackup = backup.abrirSessao("troca_tipo_legenda");
        sessao.out("Diretório de backup criado com sucesso: " + pastaBackup);
        // Carimbo próprio para NOMEAR os relatórios JSON/MD desta execução. Não é o mesmo
        // do backup: aquele agora é responsabilidade do ArmazenamentoBackupPort.
        String timestamp = TIMESTAMP_DIR.format(LocalDateTime.now());

        int totalAlterados = 0;
        int totalSubstituicoes = 0;

        for (Path arq : arquivos) {
            if (Thread.currentThread().isInterrupted()) {
                sessao.out(AnsiCores.YELLOW + "[AVISO] Execução interrompida cooperativamente." + AnsiCores.RESET);
                break;
            }

            try {
                DocumentoLegenda doc = legendaIo.ler(arq);
                List<AuditoriaFonteInfo> fontes = new ArrayList<>(auditoriaService.analisarCabecalho(doc.cabecalho()));
                fontes.addAll(auditoriaService.analisarFontesNasFalas(doc.eventos()));
                boolean temProblemas = fontes.stream().anyMatch(AuditoriaFonteInfo::problematica);
                List<AuditoriaFonteInfo> fontesAlvo = fontes.stream()
                    .filter(f -> forcarArial ? !"Arial".equals(f.fonteAtual()) : f.problematica())
                    .toList();

                if (!forcarArial && !temProblemas) {
                    sessao.out("Arquivo " + arq.getFileName() + " [OK] - Sem fontes legadas problemáticas. Pulando.");
                    continue;
                }
                if (fontesAlvo.isEmpty()) {
                    sessao.out("Arquivo " + arq.getFileName() + " [OK] - Todos os estilos já estão em Arial. Pulando.");
                    continue;
                }

                // Backup ANTES de escrever: sem a copia intacta, a substituicao de fonte nao
                // teria volta.
                backup.preservar(pastaBackup, arq);

                // Executa a substituição no cabeçalho
                String cabecalhoOriginal = doc.cabecalho();
                AuditoriaFontesService.ResultadoSubstituicaoCabecalho substituicao =
                    forcarArial
                        ? auditoriaService.substituirTodasFontesPorArial(cabecalhoOriginal)
                        : auditoriaService.substituirFontesProblematicas(cabecalhoOriginal);
                String cabecalhoNovo = substituicao.cabecalho();
                // O \fn dentro das falas: sem isto a 4.1 e o remux reprovariam pedindo uma troca que
                // a 1.3 não fazia (o cabeçalho sozinho não alcança a tag escrita na fala).
                AuditoriaFontesService.ResultadoSubstituicaoFalas falas =
                    auditoriaService.substituirFontesNasFalas(doc.eventos());
                int substituicoesNoArquivo = substituicao.substituicoes() + falas.substituicoes();

                if (substituicoesNoArquivo > 0) {
                    DocumentoLegenda novoDoc = new DocumentoLegenda(
                        cabecalhoNovo,
                        falas.eventos(),
                        doc.quebraDeLinha(),
                        doc.comBom()
                    );
                    
                    // Escreve com o escritor ASS
                    legendaIo.escrever(arq, novoDoc);

                    // Registra auditoria granular apenas depois da gravação física
                    // ser concluída, para o cache não marcar sucesso se o IO falhar.
                    for (AuditoriaFonteInfo fonteInfo : fontesAlvo) {
                        String fonteLegada = fonteInfo.fonteAtual();
                        String fonteUnicode = forcarArial ? "Arial" : fonteInfo.fonteSugerida();
                        EntradaAuditoriaTrocaFonte entrada = new EntradaAuditoriaTrocaFonte(
                            Instant.now().toString(),
                            arq.getFileName().toString(),
                            fonteInfo.estilo(),
                            fonteLegada,
                            fonteUnicode,
                            pastaBackup.toString(),
                            forcarArial ? "FORCADO_ARIAL" : "SUBSTITUIDO"
                        );
                        auditoriaCache.registrar(entrada);

                        sessao.out("  -> No arquivo: " + arq.getFileName() + " estilo [" + fonteInfo.estilo()
                            + "] substituído: " + fonteLegada + " -> " + fonteUnicode
                            + (forcarArial ? " (manual)" : ""));
                    }

                    totalAlterados++;
                    totalSubstituicoes += substituicoesNoArquivo;
                    sessao.out(AnsiCores.GREEN + "  [SUCESSO] Arquivo " + arq.getFileName() + " atualizado!" + AnsiCores.RESET);
                }
            } catch (Exception e) {
                sessao.out(AnsiCores.RED + "  [ERRO] Falha ao processar arquivo: " + arq.getFileName() + ". Erro: " + e.getMessage() + AnsiCores.RESET);
                log.error("Erro no processamento do arquivo " + arq, e);
                
                // Registra falha na auditoria
                EntradaAuditoriaTrocaFonte entradaErro = new EntradaAuditoriaTrocaFonte(
                    Instant.now().toString(),
                    arq.getFileName().toString(),
                    "N/A",
                    "N/A",
                    "N/A",
                    pastaBackup.toString(),
                    "FALHA: " + e.getMessage()
                );
                auditoriaCache.registrar(entradaErro);
            }
        }

        sessao.out(AnsiCores.GREEN + "\n========================================================================" + AnsiCores.RESET);
        sessao.out(AnsiCores.GREEN + "  [SUCESSO] OPERAÇÃO DE SUBSTITUIÇÃO DE FONTES FINALIZADA!" + AnsiCores.RESET);
        sessao.out(AnsiCores.GREEN + "========================================================================" + AnsiCores.RESET);
        sessao.out("  • Arquivos analisados  : " + arquivos.size());
        sessao.out("  • Arquivos alterados   : " + totalAlterados);
        sessao.out("  • Substituições feitas : " + totalSubstituicoes);
        sessao.out("  • Pasta de Backup      : " + pastaBackup);
        sessao.out(AnsiCores.GREEN + "========================================================================" + AnsiCores.RESET);

        // Salvar relatórios JSON e Markdown
        Path pastaRelatorios = TelemetriaService.resolverPastaRelatorios(diretorio);
        Path caminhoJson = pastaRelatorios.resolve("troca_fontes_" + timestamp + ".json");
        Path caminhoMd = pastaRelatorios.resolve("troca_fontes_" + timestamp + ".md");

        ResultadoTrocaFonte resultado = new ResultadoTrocaFonte(
            arquivos.size(),
            totalAlterados,
            totalSubstituicoes,
            LocalDateTime.now().toString(),
            pastaBackup.toString(),
            caminhoJson.toAbsolutePath().toString()
        );

        try {
            Files.createDirectories(pastaRelatorios);
            // Salvar JSON
            objectMapper.writeValue(caminhoJson.toFile(), resultado);
            
            // Salvar Markdown
            String markdown = gerarRelatorioMarkdown(resultado);
            Files.writeString(caminhoMd, markdown, StandardCharsets.UTF_8);
            
            sessao.out("Relatórios persistidos em: " + pastaRelatorios);
        } catch (IOException e) {
            sessao.out(AnsiCores.YELLOW + "[AVISO] Falha ao gravar relatórios finais em disco: " + e.getMessage() + AnsiCores.RESET);
        }

        // Registrar na telemetria. O carimbo de tempo deixou de ser montado aqui: quem o
        // preenche agora é o adaptador, junto com o resto do registro.
        telemetria.registrar(
            "Troca de Fontes ASS",
            "Diretório: " + diretorio.getFileName(),
            System.currentTimeMillis() - sessao.inicioMs,
            arquivos.size(),
            totalAlterados,
            totalSubstituicoes
        );

        return resultado;
    }

    private void validarDiretorio(Path dir) {
        if (dir == null) {
            throw new TrocaTipoLegendaException("Diretório de legendas não pode ser nulo.");
        }
        if (!Files.exists(dir)) {
            throw new TrocaTipoLegendaException("Diretório de legendas não existe: " + dir.toAbsolutePath());
        }
        if (!Files.isDirectory(dir)) {
            throw new TrocaTipoLegendaException("O caminho fornecido não é uma pasta: " + dir.toAbsolutePath());
        }
    }

    private List<Path> listarLegendas(Path dir) {
        try (Stream<Path> stream = Files.list(dir)) {
            return stream
                .filter(p -> Files.isRegularFile(p))
                .filter(p -> p.toString().toLowerCase().endsWith(".ass") || p.toString().toLowerCase().endsWith(".ssa"))
                .toList();
        } catch (IOException e) {
            throw new TrocaTipoLegendaException("Falha ao listar arquivos de legenda na pasta: " + dir, e);
        }
    }

    private String tipoLegenda(Path arquivo) {
        String nome = arquivo == null || arquivo.getFileName() == null
            ? ""
            : arquivo.getFileName().toString().toLowerCase();
        if (nome.endsWith(".ass")) {
            return "ASS estilizada";
        }
        if (nome.endsWith(".ssa")) {
            return "SSA estilizada";
        }
        return "DESCONHECIDO";
    }

    private String gerarRelatorioMarkdown(ResultadoTrocaFonte res) {
        StringBuilder sb = new StringBuilder();
        sb.append("# Troca de Fontes ASS\n\n");
        sb.append("## Resumo\n\n");
        sb.append("| Métrica | Valor |\n");
        sb.append("|---|---:|\n");
        sb.append("| Arquivos analisados | ").append(res.totalAnalisados()).append(" |\n");
        sb.append("| Arquivos alterados | ").append(res.totalAlterados()).append(" |\n");
        sb.append("| Substituições aplicadas | ").append(res.totalSubstituicoes()).append(" |\n\n");

        sb.append("## Arquivos\n\n");
        sb.append("- Data/hora: `").append(res.dataHora()).append("`\n");
        sb.append("- Backup: `").append(res.pastaBackup()).append("`\n");
        sb.append("- JSON resumido: `").append(res.caminhoRelatorioJson()).append("`\n");
        sb.append("- Auditoria granular: `").append(auditoriaCache.caminhoCanonico()).append("`\n\n");

        sb.append("## Resultado\n\n");
        if (res.totalSubstituicoes() > 0) {
            sb.append("Fontes detectadas ou escolhidas manualmente foram substituídas por `Arial`. ");
            sb.append("Use o backup para reverter arquivos específicos, se necessário.\n");
        } else {
            sb.append("Nenhuma substituição foi necessária.\n");
        }
        return sb.toString();
    }
}
