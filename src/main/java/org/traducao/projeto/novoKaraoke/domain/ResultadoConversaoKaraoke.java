package org.traducao.projeto.novoKaraoke.domain;

import java.util.ArrayList;
import java.util.List;

/**
 * Resultado da conversão de um arquivo .ass: contadores para o resumo do
 * console/telemetria e o material do manifesto de auditoria.
 */
public class ResultadoConversaoKaraoke {

    private final String arquivoOrigem;
    private String arquivoDestino;
    private int eventosTotais;
    private int eventosDialogoPreservados;
    private int eventosKaraokeRemovidos;
    private int eventosPreservadosPorSeguranca;
    /** Camadas de tipografia empilhada achatadas fora da música (cartão de data, letreiro). */
    private int eventosTipografiaColapsados;
    private final List<LinhaSimplesKaraoke> linhasCriadas = new ArrayList<>();
    private final List<String> avisos = new ArrayList<>();
    private long tamanhoOriginalBytes;
    private long tamanhoNovoBytes;

    public ResultadoConversaoKaraoke(String arquivoOrigem) {
        this.arquivoOrigem = arquivoOrigem;
    }

    public String getArquivoOrigem() {
        return arquivoOrigem;
    }

    public String getArquivoDestino() {
        return arquivoDestino;
    }

    public void setArquivoDestino(String arquivoDestino) {
        this.arquivoDestino = arquivoDestino;
    }

    public int getEventosTotais() {
        return eventosTotais;
    }

    public void setEventosTotais(int eventosTotais) {
        this.eventosTotais = eventosTotais;
    }

    public int getEventosDialogoPreservados() {
        return eventosDialogoPreservados;
    }

    public void incrementarDialogoPreservado() {
        this.eventosDialogoPreservados++;
    }

    /**
     * PROPÓSITO DE NEGÓCIO: registra as camadas de tipografia empilhada removidas pelo colapso. Elas
     * tinham sido contadas como diálogo preservado ANTES do colapso, e o manifesto dizia que saíram
     * mais falas do que saíram (auditoria da 4.1, 28/10/2026).
     *
     * <p>INVARIANTES DO DOMÍNIO: desconta do diálogo preservado o mesmo número que soma aqui.
     */
    public void registrarTipografiaColapsada(int quantidade) {
        eventosTipografiaColapsados += quantidade;
        eventosDialogoPreservados -= quantidade;
    }

    public int getEventosTipografiaColapsados() {
        return eventosTipografiaColapsados;
    }

    /**
     * PROPÓSITO DE NEGÓCIO: o arquivo que FALHOU entra no manifesto com o motivo — até 28/10/2026 ele
     * virava só um contador, e se todos falhassem não havia manifesto nenhum.
     *
     * <p>COMPORTAMENTO EM CASO DE FALHA: motivo nulo vira o nome da exceção ausente ("sem motivo").
     */
    public static ResultadoConversaoKaraoke falhou(String arquivo, String motivo) {
        ResultadoConversaoKaraoke r = new ResultadoConversaoKaraoke(arquivo);
        r.adicionarAviso("FALHOU: " + (motivo == null || motivo.isBlank() ? "sem motivo" : motivo));
        return r;
    }

    public int getEventosKaraokeRemovidos() {
        return eventosKaraokeRemovidos;
    }

    public void incrementarKaraokeRemovido() {
        this.eventosKaraokeRemovidos++;
    }

    public int getEventosPreservadosPorSeguranca() {
        return eventosPreservadosPorSeguranca;
    }

    public void adicionarPreservadosPorSeguranca(int quantidade) {
        this.eventosPreservadosPorSeguranca += quantidade;
    }

    public List<LinhaSimplesKaraoke> getLinhasCriadas() {
        return linhasCriadas;
    }

    public List<String> getAvisos() {
        return avisos;
    }

    public void adicionarAviso(String aviso) {
        avisos.add(aviso);
    }

    public long getTamanhoOriginalBytes() {
        return tamanhoOriginalBytes;
    }

    public void setTamanhoOriginalBytes(long tamanhoOriginalBytes) {
        this.tamanhoOriginalBytes = tamanhoOriginalBytes;
    }

    public long getTamanhoNovoBytes() {
        return tamanhoNovoBytes;
    }

    public void setTamanhoNovoBytes(long tamanhoNovoBytes) {
        this.tamanhoNovoBytes = tamanhoNovoBytes;
    }

    public int getPercentualReducao() {
        if (tamanhoOriginalBytes <= 0) {
            return 0;
        }
        return (int) Math.round(100.0 * (tamanhoOriginalBytes - tamanhoNovoBytes) / tamanhoOriginalBytes);
    }
}
