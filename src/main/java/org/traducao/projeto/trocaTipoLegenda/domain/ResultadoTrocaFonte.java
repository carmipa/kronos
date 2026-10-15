package org.traducao.projeto.trocaTipoLegenda.domain;

/**
 * PROPÓSITO DE NEGÓCIO: o que uma gravação da 1.3 (troca de fonte ou achatamento) fez de fato
 * na pasta — vai para o console, para o relatório JSON/MD e para a resposta.
 *
 * <p>INVARIANTES DO DOMÍNIO: {@code totalFalhas} conta os arquivos em que a gravação falhou;
 * com ele acima de zero a operação não pode ser anunciada como sucesso.
 *
 * <p>COMPORTAMENTO EM CASO DE FALHA: é um valor; não lança nada.
 */
public record ResultadoTrocaFonte(
    int totalAnalisados,
    int totalAlterados,
    int totalSubstituicoes,
    String dataHora,
    String pastaBackup,
    String caminhoRelatorioJson,
    int totalFalhas
) {}
