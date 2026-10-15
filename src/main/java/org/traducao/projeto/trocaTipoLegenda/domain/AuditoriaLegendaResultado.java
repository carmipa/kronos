package org.traducao.projeto.trocaTipoLegenda.domain;

import java.util.List;

/**
 * PROPÓSITO DE NEGÓCIO: o diagnóstico de UMA legenda na 1.3 — que fontes ela usa e se alguma
 * desenha a letra errada — como a tela mostra na tabela.
 *
 * <p>INVARIANTES DO DOMÍNIO: {@code erroLeitura} é {@code null} quando o arquivo foi lido; quando
 * não foi, traz o motivo e {@code fontes} fica vazia — arquivo ilegível nunca aparece como "OK"
 * nem ganha uma fonte inventada. {@code temProblemas} fala só de fonte, não de leitura.
 *
 * <p>COMPORTAMENTO EM CASO DE FALHA: é um valor; não lança nada.
 */
public record AuditoriaLegendaResultado(
    String arquivo,
    String tipoLegenda,
    List<AuditoriaFonteInfo> fontes,
    boolean temProblemas,
    String erroLeitura
) {}
