package org.traducao.projeto.remuxer.domain;

import java.time.LocalDateTime;

/**
 * PROPÓSITO DE NEGÓCIO: consolida o resultado real de um lote de remux para a
 * interface, CLI e dataset de telemetria.
 *
 * <p>INVARIANTES DO DOMÍNIO: sucesso conta somente MKV validado e promovido ao
 * nome final; ausência, ambiguidade e destino existente são pendências; falhas
 * técnicas nunca resultam em status de sucesso.
 *
 * <p>COMPORTAMENTO EM CASO DE FALHA: contadores preservam progresso parcial e o
 * status final distingue falha, pendência, cancelamento e lote vazio.
 */
public class RelatorioRemux {
    private final LocalDateTime dataHoraInicio = LocalDateTime.now();
    private LocalDateTime dataHoraFim;
    private int mkvDetectados;
    private int mkvProcessadosSucesso;
    private int legendasPareadas;
    private int errosInfraestrutura;
    private int errosMkvmergeRuntime;
    private int errosPermissaoIo;
    private int errosInesperados;
    private int errosLegendaInvalida;
    private int arquivosIgnorados;
    private int videosSemLegenda;
    private int pareamentosAmbiguos;
    private int saidasJaExistentes;
    private int legendasComFonteVietnamita;
    private long bytesMkvGeradosTotal;
    private boolean cancelado;

    /**
     * PROPÓSITO DE NEGÓCIO: encerra cronologicamente o relatório do lote.
     * INVARIANTES DO DOMÍNIO: término nunca antecede a criação.
     * COMPORTAMENTO EM CASO DE FALHA: pode ser chamado mais de uma vez e mantém o
     * término mais recente.
     */
    public void finalizar() { this.dataHoraFim = LocalDateTime.now(); }

    /**
     * PROPÓSITO DE NEGÓCIO: registra os totais encontrados no planejamento.
     * INVARIANTES DO DOMÍNIO: pareadas não excedem MKVs detectados.
     * COMPORTAMENTO EM CASO DE FALHA: valores negativos são normalizados para zero.
     */
    public void registrarDeteccao(int totalMkv, int totalPareadas) {
        this.mkvDetectados = Math.max(0, totalMkv);
        this.legendasPareadas = Math.max(0, Math.min(totalPareadas, this.mkvDetectados));
    }

    /**
     * PROPÓSITO DE NEGÓCIO: registra vídeo que não entrou na fila.
     * INVARIANTES DO DOMÍNIO: incrementa uma ocorrência por vídeo.
     * COMPORTAMENTO EM CASO DE FALHA: operação em memória não lança exceção.
     */
    public void registrarIgnorado() { this.arquivosIgnorados++; }

    /**
     * PROPÓSITO DE NEGÓCIO: registra ausência objetiva de legenda compatível.
     * INVARIANTES DO DOMÍNIO: ausência também compõe ignorados/pêndencias.
     * COMPORTAMENTO EM CASO DE FALHA: operação em memória não lança exceção.
     */
    public void registrarSemLegenda() { this.videosSemLegenda++; this.arquivosIgnorados++; }

    /**
     * PROPÓSITO DE NEGÓCIO: registra empate que exige decisão humana.
     * INVARIANTES DO DOMÍNIO: ambiguidade nunca conta como pareamento executável.
     * COMPORTAMENTO EM CASO DE FALHA: operação em memória não lança exceção.
     */
    public void registrarPareamentoAmbiguo() { this.pareamentosAmbiguos++; this.arquivosIgnorados++; }

    /**
     * PROPÓSITO DE NEGÓCIO: registra destino final preservado por já existir.
     * INVARIANTES DO DOMÍNIO: não conta sucesso desta execução.
     * COMPORTAMENTO EM CASO DE FALHA: operação em memória não lança exceção.
     */
    public void registrarSaidaJaExistente() { this.saidasJaExistentes++; this.arquivosIgnorados++; }

    /**
     * PROPÓSITO DE NEGÓCIO: registra MKV validado e publicado com tamanho real.
     * INVARIANTES DO DOMÍNIO: bytes negativos não são acumulados.
     * COMPORTAMENTO EM CASO DE FALHA: tamanho inválido soma zero.
     */
    public void registrarSucesso(long bytes) {
        this.mkvProcessadosSucesso++;
        this.bytesMkvGeradosTotal += Math.max(0, bytes);
    }

    /** PROPÓSITO DE NEGÓCIO: conta falha de infraestrutura. INVARIANTES DO DOMÍNIO: uma por ocorrência. COMPORTAMENTO EM CASO DE FALHA: não lança. */
    public void registrarErroInfra() { this.errosInfraestrutura++; }
    /** PROPÓSITO DE NEGÓCIO: conta falha do mkvmerge. INVARIANTES DO DOMÍNIO: uma por tarefa. COMPORTAMENTO EM CASO DE FALHA: não lança. */
    public void registrarErroRuntime() { this.errosMkvmergeRuntime++; }
    /** PROPÓSITO DE NEGÓCIO: conta falha de permissão/I-O. INVARIANTES DO DOMÍNIO: uma por tarefa. COMPORTAMENTO EM CASO DE FALHA: não lança. */
    public void registrarErroIo() { this.errosPermissaoIo++; }
    /** PROPÓSITO DE NEGÓCIO: conta falha não classificada. INVARIANTES DO DOMÍNIO: uma por tarefa. COMPORTAMENTO EM CASO DE FALHA: não lança. */
    public void registrarErroInesperado() { this.errosInesperados++; }
    /** PROPÓSITO DE NEGÓCIO: conta legenda inválida. INVARIANTES DO DOMÍNIO: nunca executa remux dessa tarefa. COMPORTAMENTO EM CASO DE FALHA: não lança. */
    public void registrarErroLegendaInvalida() { this.errosLegendaInvalida++; }
    /**
     * PROPÓSITO DE NEGÓCIO: conta legenda reprovada por fonte vietnamita antiga no que vai à tela —
     * ela pede a 1.3 Troca de Tipo de Legenda antes do remux (ordem de Paulo, 14/10/2026).
     * INVARIANTES DO DOMÍNIO: nunca executa remux dessa tarefa; é PENDÊNCIA (pede uma ação do
     * operador), não falha técnica.
     * COMPORTAMENTO EM CASO DE FALHA: não lança.
     */
    public void registrarLegendaComFonteVietnamita() { this.legendasComFonteVietnamita++; this.arquivosIgnorados++; }

    /**
     * PROPÓSITO DE NEGÓCIO: marca parada solicitada pelo Paulo ou encerramento da fila.
     * INVARIANTES DO DOMÍNIO: cancelamento prevalece no status final.
     * COMPORTAMENTO EM CASO DE FALHA: flag idempotente.
     */
    public void registrarCancelamento() { this.cancelado = true; }

    /**
     * PROPÓSITO DE NEGÓCIO: soma falhas técnicas do lote.
     * INVARIANTES DO DOMÍNIO: pendências seguras não entram como erro técnico.
     * COMPORTAMENTO EM CASO DE FALHA: sempre devolve inteiro não negativo.
     */
    public int getTotalErros() {
        return errosInfraestrutura + errosMkvmergeRuntime + errosPermissaoIo
            + errosInesperados + errosLegendaInvalida;
    }

    /**
     * PROPÓSITO DE NEGÓCIO: soma itens que exigem correção ou decisão posterior.
     * INVARIANTES DO DOMÍNIO: inclui ausência, ambiguidade, saída preservada e legenda reprovada por
     * fonte vietnamita antiga (pede a 1.3 antes do remux).
     * COMPORTAMENTO EM CASO DE FALHA: sempre devolve inteiro não negativo.
     */
    public int getTotalPendencias() {
        return videosSemLegenda + pareamentosAmbiguos + saidasJaExistentes + legendasComFonteVietnamita;
    }

    /**
     * PROPÓSITO DE NEGÓCIO: fornece estado final verdadeiro para console e dataset.
     * INVARIANTES DO DOMÍNIO: cancelamento e falha têm precedência sobre sucesso.
     * COMPORTAMENTO EM CASO DE FALHA: lote sem MKV ou sem ação retorna SEM_ARQUIVOS
     * ou CONCLUIDO_COM_PENDENCIAS, nunca sucesso falso.
     */
    public String getStatusFinal() {
        if (cancelado) return "CANCELADO";
        if (getTotalErros() > 0) return "CONCLUIDO_COM_FALHAS";
        if (getTotalPendencias() > 0) return "CONCLUIDO_COM_PENDENCIAS";
        if (mkvDetectados == 0 || mkvProcessadosSucesso == 0) return "SEM_ARQUIVOS";
        return "CONCLUIDO";
    }

    /** PROPÓSITO DE NEGÓCIO: expõe início do lote. INVARIANTES DO DOMÍNIO: valor nasce com o relatório. COMPORTAMENTO EM CASO DE FALHA: nunca retorna nulo. */
    public LocalDateTime getDataHoraInicio() { return dataHoraInicio; }
    /** PROPÓSITO DE NEGÓCIO: expõe término do lote. INVARIANTES DO DOMÍNIO: existe após finalizar. COMPORTAMENTO EM CASO DE FALHA: retorna nulo enquanto em execução. */
    public LocalDateTime getDataHoraFim() { return dataHoraFim; }
    /** PROPÓSITO DE NEGÓCIO: expõe vídeos detectados. INVARIANTES DO DOMÍNIO: não negativo. COMPORTAMENTO EM CASO DE FALHA: retorna zero. */
    public int getMkvDetectados() { return mkvDetectados; }
    /** PROPÓSITO DE NEGÓCIO: expõe MKVs publicados. INVARIANTES DO DOMÍNIO: só conta saída validada. COMPORTAMENTO EM CASO DE FALHA: não incrementa. */
    public int getMkvProcessadosSucesso() { return mkvProcessadosSucesso; }
    /** PROPÓSITO DE NEGÓCIO: expõe pareamentos executáveis. INVARIANTES DO DOMÍNIO: não excede detectados. COMPORTAMENTO EM CASO DE FALHA: retorna zero. */
    public int getLegendasPareadas() { return legendasPareadas; }
    /** PROPÓSITO DE NEGÓCIO: expõe falhas de infraestrutura. INVARIANTES DO DOMÍNIO: não negativo. COMPORTAMENTO EM CASO DE FALHA: retorna contador acumulado. */
    public int getErrosInfraestrutura() { return errosInfraestrutura; }
    /** PROPÓSITO DE NEGÓCIO: expõe falhas do mkvmerge. INVARIANTES DO DOMÍNIO: uma por tarefa falha. COMPORTAMENTO EM CASO DE FALHA: retorna contador acumulado. */
    public int getErrosMkvmergeRuntime() { return errosMkvmergeRuntime; }
    /** PROPÓSITO DE NEGÓCIO: expõe falhas de I/O. INVARIANTES DO DOMÍNIO: não negativo. COMPORTAMENTO EM CASO DE FALHA: retorna contador acumulado. */
    public int getErrosPermissaoIo() { return errosPermissaoIo; }
    /** PROPÓSITO DE NEGÓCIO: expõe falhas não classificadas. INVARIANTES DO DOMÍNIO: não negativo. COMPORTAMENTO EM CASO DE FALHA: retorna contador acumulado. */
    public int getErrosInesperados() { return errosInesperados; }
    /** PROPÓSITO DE NEGÓCIO: expõe legendas rejeitadas. INVARIANTES DO DOMÍNIO: não foram remuxadas. COMPORTAMENTO EM CASO DE FALHA: retorna contador acumulado. */
    public int getErrosLegendaInvalida() { return errosLegendaInvalida; }
    /** PROPÓSITO DE NEGÓCIO: expõe volume publicado. INVARIANTES DO DOMÍNIO: soma somente sucessos. COMPORTAMENTO EM CASO DE FALHA: retorna zero sem publicação. */
    public long getBytesMkvGeradosTotal() { return bytesMkvGeradosTotal; }
    /** PROPÓSITO DE NEGÓCIO: expõe itens não processados. INVARIANTES DO DOMÍNIO: inclui pendências seguras. COMPORTAMENTO EM CASO DE FALHA: retorna contador acumulado. */
    public int getArquivosIgnorados() { return arquivosIgnorados; }
    /** PROPÓSITO DE NEGÓCIO: expõe vídeos sem legenda. INVARIANTES DO DOMÍNIO: não entram na fila. COMPORTAMENTO EM CASO DE FALHA: retorna contador acumulado. */
    public int getVideosSemLegenda() { return videosSemLegenda; }
    /** PROPÓSITO DE NEGÓCIO: expõe decisões ambíguas. INVARIANTES DO DOMÍNIO: não são escolhidas automaticamente. COMPORTAMENTO EM CASO DE FALHA: retorna contador acumulado. */
    public int getPareamentosAmbiguos() { return pareamentosAmbiguos; }
    /** PROPÓSITO DE NEGÓCIO: expõe destinos preservados. INVARIANTES DO DOMÍNIO: não são sobrescritos. COMPORTAMENTO EM CASO DE FALHA: retorna contador acumulado. */
    public int getSaidasJaExistentes() { return saidasJaExistentes; }
    /** PROPÓSITO DE NEGÓCIO: expõe as legendas reprovadas por fonte vietnamita antiga. INVARIANTES DO DOMÍNIO: subconjunto das pendências. COMPORTAMENTO EM CASO DE FALHA: nunca negativo. */
    public int getLegendasComFonteVietnamita() { return legendasComFonteVietnamita; }
    /** PROPÓSITO DE NEGÓCIO: informa cancelamento. INVARIANTES DO DOMÍNIO: estado é idempotente. COMPORTAMENTO EM CASO DE FALHA: retorna falso até cancelamento. */
    public boolean isCancelado() { return cancelado; }
}
