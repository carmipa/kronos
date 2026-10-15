package org.traducao.projeto.trocaTipoLegenda.domain;

import java.util.List;

/**
 * PROPÓSITO DE NEGÓCIO: o resumo do escaneamento da 1.3 — quantos arquivos, quantos com fonte
 * errada, quantos ilegíveis e quantas legendas ficaram de fora por estarem em subpastas.
 *
 * <p>INVARIANTES DO DOMÍNIO: {@code totalComErroLeitura} conta os arquivos com
 * {@code erroLeitura} preenchido e nunca entra em {@code totalComProblemas}; {@code
 * legendasEmSubpastas} é {@code -1} quando as subpastas não puderam ser conferidas (nunca 0 por
 * omissão).
 *
 * <p>COMPORTAMENTO EM CASO DE FALHA: é um valor; não lança nada.
 */
public record ResultadoGeralAuditoria(
    List<AuditoriaLegendaResultado> arquivos,
    int totalArquivosAnalisados,
    int totalComProblemas,
    int totalComErroLeitura,
    int legendasEmSubpastas
) {}
