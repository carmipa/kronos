package org.traducao.projeto.telemetria;

/**
 * PROPÓSITO DE NEGÓCIO: uma linha do Histórico Operacional da tela de Telemetria — operação do
 * pipeline, tradução de episódio ou análise de mídia.
 *
 * <p>INVARIANTES DO DOMÍNIO: {@code itensDetectados} e {@code itensCorrigidos} são os números
 * crus da operação (nulos quando ela não detecta nada); {@code taxaSucesso} é derivada deles,
 * em divisão inteira, e fica só por compatibilidade — a tela mostra "corrigidos de detectados",
 * porque o percentual arredondado para baixo exibia "0%" para 91 de 24.441.
 *
 * <p>COMPORTAMENTO EM CASO DE FALHA: é um valor; não lança nada.
 */
public record OperacaoHistorico(
    String nomeOperacao,
    String detalheOperacao,
    String duracaoFormatada,
    Integer taxaSucesso,
    String origem,
    Long duracaoMs,
    /** Instante UTC (ISO-8601) do registro; o navegador converte para hora local. */
    String registradoEm,
    Integer itensDetectados,
    Integer itensCorrigidos
) {}
