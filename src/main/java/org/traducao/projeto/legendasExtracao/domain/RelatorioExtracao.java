package org.traducao.projeto.legendasExtracao.domain;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * PROPÓSITO DE NEGÓCIO: Acumula o resultado de uma execução de extração — tanto
 * os contadores agregados (para o resumo e a telemetria) quanto a lista granular
 * por vídeo ({@link ItemExtracao}, que alimenta a tabela da UI). É o objeto que o
 * use case devolve à camada de apresentação.
 *
 * <p>INVARIANTES DO DOMÍNIO: cada vídeo processado incrementa {@code arquivosDetectados}
 * e adiciona exatamente um item; a soma de extraídas + sem-faixa + já-existentes +
 * falhas + timeouts nunca ultrapassa os vídeos detectados. {@code timeouts} é
 * contado à parte de {@code falhasInesperadas}. A lista de itens é exposta como
 * cópia imutável.
 *
 * <p>COMPORTAMENTO EM CASO DE FALHA: mutador simples, não lança. Contadores
 * começam em zero; a lista de itens começa vazia.
 */
public class RelatorioExtracao {
    private int arquivosDetectados = 0;
    private int faixasEncontradas = 0;
    private int legendasExtraidas = 0;
    private int arquivosSemLegenda = 0;
    private int arquivosJaExistentes = 0;
    private int falhasInesperadas = 0;
    private int timeouts = 0;
    private boolean interrompidoPeloOperador = false;
    private int semFaixaComTextoNaoSuportado = 0;
    private int naoProcessadosPorParada = 0;
    private int saidasDoRemuxIgnoradas = 0;

    private final List<ItemExtracao> itens = new ArrayList<>();
    private final FormatoLegenda formatoAlvo;

    public RelatorioExtracao(FormatoLegenda formatoAlvo) {
        this.formatoAlvo = formatoAlvo;
    }

    public void registrarDetectado() {
        this.arquivosDetectados++;
    }

    public void registrarFaixasEncontradas(int quantidade) {
        if (quantidade > 0) {
            this.faixasEncontradas += quantidade;
        }
    }

    public void registrarExtraido() {
        this.legendasExtraidas++;
    }

    public void registrarSemLegenda() {
        this.arquivosSemLegenda++;
    }

    public void registrarJaExiste() {
        this.arquivosJaExistentes++;
    }

    public void registrarFalha() {
        this.falhasInesperadas++;
    }

    public void registrarTimeout() {
        this.timeouts++;
    }

    public void adicionarItem(ItemExtracao item) {
        if (item != null) {
            this.itens.add(item);
        }
    }

    public int getArquivosDetectados() { return arquivosDetectados; }
    public int getFaixasEncontradas() { return faixasEncontradas; }
    public int getLegendasExtraidas() { return legendasExtraidas; }
    public int getArquivosSemLegenda() { return arquivosSemLegenda; }
    public int getArquivosJaExistentes() { return arquivosJaExistentes; }
    public int getFalhasInesperadas() { return falhasInesperadas; }
    public int getTimeouts() { return timeouts; }

    /**
     * PROPÓSITO DE NEGÓCIO: registra que o operador parou o lote (Parar/Sair) e quantos vídeos
     * ficaram sem processar — que NÃO são falha. Antes, cada vídeo restante virava "[FALHA] Falha
     * ao invocar mkvmerge" e o banner dizia SUCESSO com "Falhas de Processamento: 334" (auditoria
     * de 08/10/2026, E4).
     *
     * <p>INVARIANTES DO DOMÍNIO: registra uma vez; a contagem é não negativa e não entra em
     * {@code falhasInesperadas}.
     *
     * <p>COMPORTAMENTO EM CASO DE FALHA: valor negativo vira zero.
     */
    public void registrarParada(int naoProcessados) {
        this.interrompidoPeloOperador = true;
        this.naoProcessadosPorParada = Math.max(0, naoProcessados);
    }

    /**
     * PROPÓSITO DE NEGÓCIO: conta os vídeos sem faixa do formato pedido que TÊM legenda de texto
     * num formato que a extração não converte (mov_text do MP4, WebVTT) — para a tela não mandar
     * procurar "hardsub" onde há legenda (auditoria de 08/10/2026, E5).
     *
     * <p>INVARIANTES DO DOMÍNIO: subconjunto de {@code arquivosSemLegenda}; não é falha.
     *
     * <p>COMPORTAMENTO EM CASO DE FALHA: só incrementa; nunca lança.
     */
    public void registrarSemFaixaComTextoNaoSuportado() { semFaixaComTextoNaoSuportado++; }
    public int getSemFaixaComTextoNaoSuportado() { return semFaixaComTextoNaoSuportado; }

    /**
     * PROPÓSITO DE NEGÓCIO: conta os MKVs publicados pelo remux ({@code _PTBR.mkv}) que o percurso da
     * pasta deixou de fora porque a mesma pasta tem os originais — para a tela dizer quantos foram
     * pulados e por quê, em vez de o total de vídeos encolher sem explicação.
     *
     * <p>INVARIANTES DO DOMÍNIO: não entra em {@code arquivosDetectados} (o vídeo pulado não foi
     * processado); registra uma vez por execução.
     *
     * <p>COMPORTAMENTO EM CASO DE FALHA: valor negativo vira zero; nunca lança.
     */
    public void registrarSaidasDoRemuxIgnoradas(int quantidade) { saidasDoRemuxIgnoradas = Math.max(0, quantidade); }
    public int getSaidasDoRemuxIgnoradas() { return saidasDoRemuxIgnoradas; }

    public boolean isInterrompidoPeloOperador() { return interrompidoPeloOperador; }
    public int getNaoProcessadosPorParada() { return naoProcessadosPorParada; }
    public FormatoLegenda getFormatoAlvo() { return formatoAlvo; }

    public List<ItemExtracao> getItens() {
        return Collections.unmodifiableList(itens);
    }
}
