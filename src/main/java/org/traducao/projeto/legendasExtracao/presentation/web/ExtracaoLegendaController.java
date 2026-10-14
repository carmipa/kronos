package org.traducao.projeto.legendasExtracao.presentation.web;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.traducao.projeto.legendasExtracao.application.ExtrairLegendaUseCase;
import org.traducao.projeto.legendasExtracao.domain.FormatoLegenda;
import org.traducao.projeto.legendasExtracao.domain.RelatorioExtracao;
import org.traducao.projeto.legendasExtracao.domain.exceptions.FormatoLegendaInvalidoException;
import org.traducao.projeto.legendasExtracao.presentation.ui.TabelaExtracaoRenderer;
import org.traducao.projeto.core.presentation.web.PipelineWebSupport;
import org.traducao.projeto.core.presentation.web.RespostaPadrao;

import java.nio.file.Path;

/**
 * PROPÓSITO DE NEGÓCIO: expõe a extração de legendas (Opção 2) à interface web,
 * validando o formato-alvo e enfileirando o processamento pesado que percorre a
 * pasta de vídeos e extrai as faixas de legenda no formato escolhido.
 *
 * <p>Fronteira arquitetural: este endpoint pertence ao módulo
 * {@code legendasExtracao} (Opção 2) e reside na sua camada de apresentação
 * própria. Não importa nenhuma regra funcional da Tradução Local (Opção 4): usa
 * apenas o use case e os tipos do próprio módulo. As dependências
 * {@link PipelineWebSupport}, {@link RespostaPadrao} e {@link ExtracaoRequest}
 * são <b>glue técnico de apresentação</b> (fila única e contratos de transporte
 * HTTP) hoje em {@code traducao.presentation.web}; é dívida técnica temporária
 * reservada para saneamento na FASE E — não é acoplamento funcional.
 *
 * <p>INVARIANTES DO DOMÍNIO: usa a MESMA fila compartilhada via
 * {@link PipelineWebSupport}; o formato é validado antes de enfileirar; caminhos
 * são normalizados; a rota {@code POST /api/extrair}, o status e os campos de DTO
 * são contrato público preservado exatamente como antes da movimentação.
 *
 * <p>COMPORTAMENTO EM CASO DE FALHA: entrada em branco ou formato inválido
 * retorna HTTP 400; falhas do job de background são registradas no log e no
 * console SSE, sem derrubar a fila.
 */
@RestController
@RequestMapping("/api")
public class ExtracaoLegendaController {

    private static final Logger log = LoggerFactory.getLogger(ExtracaoLegendaController.class);

    private final PipelineWebSupport pipelineWebSupport;
    private final ExtrairLegendaUseCase extrairLegendaUseCase;
    private final org.traducao.projeto.core.io.GuardaCaminhoEntrada guardaCaminho;

    public ExtracaoLegendaController(
            PipelineWebSupport pipelineWebSupport,
            ExtrairLegendaUseCase extrairLegendaUseCase,
            org.traducao.projeto.core.io.GuardaCaminhoEntrada guardaCaminho) {
        this.pipelineWebSupport = pipelineWebSupport;
        this.extrairLegendaUseCase = extrairLegendaUseCase;
        this.guardaCaminho = guardaCaminho;
    }

    /**
     * 2. EXTRAÇÃO DE LEGENDAS
     *
     * <p>PROPÓSITO DE NEGÓCIO: recebe da tela a pasta (ou o arquivo) de vídeos e o formato, e roda a
     * extração na fila do servidor, devolvendo na hora só a confirmação de que o job foi aceito; o
     * resultado aparece no console web, com a tabela por vídeo e um banner de fechamento.
     *
     * <p>INVARIANTES DO DOMÍNIO: caminho vazio, formato inválido ou pasta inexistente são recusados
     * ANTES de enfileirar; o banner só diz SUCESSO quando houve extração sem falha nem timeout — com
     * qualquer um dos dois o título é ATENÇÃO, e os MKVs publicados que a extração pulou são contados.
     *
     * <p>COMPORTAMENTO EM CASO DE FALHA: entrada inválida devolve 400 com a mensagem; erro dentro do
     * job vira linha de ERRO no console e no log, sem derrubar a fila.
     */
    @PostMapping("/extrair")
    public ResponseEntity<RespostaPadrao> extrair(@RequestBody ExtracaoRequest req) {
        if (req.entrada() == null || req.entrada().isBlank()) {
            return ResponseEntity.badRequest().body(new RespostaPadrao("Caminho da pasta de vídeos obrigatório."));
        }

        final FormatoLegenda formatoSelecionado;
        try {
            formatoSelecionado = FormatoLegenda.fromString(req.formato());
        } catch (FormatoLegendaInvalidoException e) {
            return ResponseEntity.badRequest().body(new RespostaPadrao(e.getMessage()));
        }

        // ANTES do submeter: aqui a recusa tardia é especialmente traiçoeira, porque uma pasta
        // inexistente não estoura — ela produz "nenhum arquivo de vídeo encontrado", que é o MESMO
        // aviso de uma pasta legítima sem vídeo suportado. "Não achei" e "não tinha como achar"
        // não podem sair pelo mesmo canal.
        var recusa = guardaCaminho.conferirDiretorio("A pasta de vídeos", req.entrada());
        if (recusa.isPresent()) {
            return ResponseEntity.badRequest().body(new RespostaPadrao(recusa.get().mensagem()));
        }

        pipelineWebSupport.submeterJobComRelatorio("extracao", "Extração de Legendas", () -> {
            try {
                Path pathEntrada = pipelineWebSupport.normalizarCaminho(req.entrada());
                if (pathEntrada == null) {
                    log.error("Caminho de entrada inválido informado para extração: {}", req.entrada());
                    return;
                }
                Path pathSaida = pipelineWebSupport.normalizarCaminho(req.saida());
                FormatoLegenda formato = formatoSelecionado;
                RelatorioExtracao rel = extrairLegendaUseCase.executar(pathEntrada, pathSaida, formato);

                String tabela = TabelaExtracaoRenderer.render(rel);
                if (!tabela.isBlank()) {
                    System.out.print("\u001B[36m" + tabela + "\u001B[0m");
                }

                if (rel.isInterrompidoPeloOperador()) {
                    // Parada não é sucesso nem falha: antes caía no ramo SUCESSO com centenas de
                    // "Falhas de Processamento" que eram só os vídeos que a parada não deixou rodar.
                    System.out.println("\n\u001B[33m========================================================================\u001B[0m");
                    System.out.println("\u001B[33m  ⏹ [PARADO] EXTRAÇÃO INTERROMPIDA PELO OPERADOR\u001B[0m");
                    System.out.println("\u001B[33m========================================================================\u001B[0m");
                    System.out.println("\u001B[36m  • Faixas Extraídas antes da parada : " + rel.getLegendasExtraidas() + " [" + formato.name() + "]\u001B[0m");
                    System.out.println("\u001B[33m  • Vídeos não processados           : " + rel.getNaoProcessadosPorParada() + "\u001B[0m");
                    if (rel.getFalhasInesperadas() > 0) {
                        System.out.println("\u001B[31m  • Falhas reais antes da parada     : " + rel.getFalhasInesperadas() + "\u001B[0m");
                    }
                    System.out.println("\u001B[33m  💡 Rode de novo para continuar: o que já foi extraído é preservado (JÁ EXISTE).\u001B[0m");
                    System.out.println("\u001B[33m========================================================================\n\u001B[0m");
                    log.info("[PARADO] Extração interrompida pelo operador. Extraídas: {}, não processados: {}",
                        rel.getLegendasExtraidas(), rel.getNaoProcessadosPorParada());
                } else if (rel.getArquivosDetectados() == 0) {
                    System.out.println("\n\u001B[33m========================================================================\u001B[0m");
                    System.out.println("\u001B[33m  ⚠️ [AVISO] NENHUM ARQUIVO DE VÍDEO SUPORTADO FOI ENCONTRADO!\u001B[0m");
                    System.out.println("\u001B[33m========================================================================\u001B[0m");
                    System.out.println("\u001B[36m  • Caminho informado : " + pathEntrada + "\u001B[0m");
                    System.out.println("\u001B[33m  • Formatos suportados: .mkv/.webm (MKVToolNix) e .mp4/.mov/.avi/.ts/.m2ts/.flv/.wmv (ffmpeg).\u001B[0m");
                    System.out.println("\u001B[33m========================================================================\n\u001B[0m");
                    log.warn("[AVISO] Nenhum arquivo de vídeo suportado foi encontrado no caminho: {}", pathEntrada);
                } else if (rel.getLegendasExtraidas() == 0) {
                    System.out.println("\n\u001B[33m========================================================================\u001B[0m");
                    System.out.println("\u001B[33m  ⚠️ [ALERTA] NENHUMA LEGENDA [" + formato.name() + "] FOI EXTRAÍDA!\u001B[0m");
                    System.out.println("\u001B[33m========================================================================\u001B[0m");
                    System.out.println("\u001B[36m  • Arquivos de Vídeo Analisados : " + rel.getArquivosDetectados() + "\u001B[0m");
                    System.out.println("\u001B[31m  • Faixas Extraídas com Sucesso : 0 [" + formato.name() + "]\u001B[0m");
                    System.out.println("\u001B[33m  • Vídeos sem Faixa " + formato.name() + "        : " + rel.getArquivosSemLegenda() + "\u001B[0m");
                    if (rel.getFalhasInesperadas() > 0) {
                        System.out.println("\u001B[31m  • Falhas de Processamento     : " + rel.getFalhasInesperadas() + "\u001B[0m");
                    }
                    if (rel.getSemFaixaComTextoNaoSuportado() > 0) {
                        System.out.println("\u001B[33m  💡 " + rel.getSemFaixaComTextoNaoSuportado() + " vídeo(s) TÊM legenda de texto, "
                            + "mas em formato que a extração ainda não converte (mov_text do MP4, WebVTT) — não é hardsub.\u001B[0m");
                    } else {
                        System.out.println("\u001B[33m  💡 Dica: Verifique se o vídeo possui legendas em outro formato (ex: PGS ou SRT) ou se a legenda está queimada na imagem (Hardsub).\u001B[0m");
                    }
                    System.out.println("\u001B[33m========================================================================\n\u001B[0m");
                    log.warn("[ALERTA] Extração finalizada sem faixas geradas. 0 de {} vídeos possuíam faixa {}", rel.getArquivosDetectados(), formato.name());
                } else {
                    // Com falha ou timeout o lote NÃO terminou com sucesso: o Gundam ZZ de 14/10/2026 saiu
                    // "[SUCESSO] Extraídas: 92 de 95" com o episódio 28 corrompido no meio. A contagem em
                    // vermelho existia, mas o título dizia o contrário — e é o título que se lê.
                    boolean comFalha = rel.getFalhasInesperadas() > 0 || rel.getTimeouts() > 0;
                    String cor = comFalha ? "\u001B[33m" : "\u001B[32m";
                    System.out.println("\n" + cor + "========================================================================\u001B[0m");
                    System.out.println(comFalha
                        ? cor + "  ⚠️ [ATENÇÃO] EXTRAÇÃO FINALIZADA COM FALHA — confira os itens em vermelho\u001B[0m"
                        : cor + "  🎉 [SUCESSO] EXTRAÇÃO DE LEGENDAS FINALIZADA COM SUCESSO!\u001B[0m");
                    System.out.println(cor + "========================================================================\u001B[0m");
                    System.out.println("\u001B[36m  • Arquivos de Vídeo Analisados : " + rel.getArquivosDetectados() + "\u001B[0m");
                    System.out.println("\u001B[32m  • Faixas Extraídas com Sucesso : " + rel.getLegendasExtraidas() + " [" + formato.name() + "]\u001B[0m");
                    if (rel.getArquivosSemLegenda() > 0) {
                        System.out.println("\u001B[33m  • Vídeos sem Faixa " + formato.name() + "        : " + rel.getArquivosSemLegenda() + "\u001B[0m");
                    }
                    if (rel.getSaidasDoRemuxIgnoradas() > 0) {
                        System.out.println("\u001B[33m  • MKVs publicados ignorados   : " + rel.getSaidasDoRemuxIgnoradas()
                            + " (_PTBR.mkv; a pasta tem os originais)\u001B[0m");
                    }
                    if (rel.getFalhasInesperadas() > 0) {
                        System.out.println("\u001B[31m  • Falhas de Processamento     : " + rel.getFalhasInesperadas() + "\u001B[0m");
                    }
                    if (rel.getTimeouts() > 0) {
                        System.out.println("\u001B[31m  • Tempo Esgotado              : " + rel.getTimeouts() + "\u001B[0m");
                    }
                    System.out.println(cor + "========================================================================\n\u001B[0m");
                    if (comFalha) {
                        log.warn("[ATENÇÃO] Extração de legendas finalizada com falha. Extraídas: {} de {}, falhas: {}, timeouts: {}",
                            rel.getLegendasExtraidas(), rel.getArquivosDetectados(), rel.getFalhasInesperadas(), rel.getTimeouts());
                    } else {
                        log.info("[SUCESSO] Extração de legendas finalizada. Extraídas: {} de {}", rel.getLegendasExtraidas(), rel.getArquivosDetectados());
                    }
                }
            } catch (Exception e) {
                log.error("Erro na extração de legendas em background", e);
                System.out.println("\u001B[31m[ERRO] Falha na extração: " + e.getMessage() + "\u001B[0m");
            }
        });

        return ResponseEntity.ok(new RespostaPadrao("Extração de legendas iniciada no servidor."));
    }
}
