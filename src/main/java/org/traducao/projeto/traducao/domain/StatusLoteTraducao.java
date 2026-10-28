package org.traducao.projeto.traducao.domain;

import java.util.List;

/**
 * PROPÓSITO DE NEGÓCIO: Desfecho do LOTE de tradução (vários arquivos), para a
 * UI/telemetria pararem de mostrar "sucesso" quando houve arquivos com falha.
 *
 * <p>INVARIANTES DO DOMÍNIO: derivado dos status por arquivo, contando apenas os NÃO
 * bloqueados — {@code BLOQUEADO} (arquivo já traduzido num rerun) é neutro e não conta como
 * falha. Todos os não-bloqueados concluídos (ou lote 100% bloqueado) → {@code CONCLUIDO};
 * nenhum concluído → {@code FALHOU}; mistura com falha real → {@code CONCLUIDO_COM_FALHAS}.
 * {@code CANCELADO} é reservado para interrupção explícita do usuário.
 *
 * <p>BLOQUEIO POR OBRA NÃO É NEUTRO (28/10/2026). {@code BLOQUEADO} também vem da guarda
 * obra×contexto — lore errada escolhida no combo —, e aí nada foi traduzido: o lote inteiro
 * recusado assim saía {@code CONCLUIDO}, com banner verde e som de sucesso. Esses bloqueios contam
 * como falha; lote em que TODO bloqueio é de obra vira {@link #BLOQUEADO}.
 *
 * <p>COMPORTAMENTO EM CASO DE FALHA: {@link #consolidar(List)} nunca lança; lote
 * vazio devolve {@code FALHOU}.
 */
public enum StatusLoteTraducao {
    CONCLUIDO("Concluído"),
    CONCLUIDO_COM_FALHAS("Concluído com falhas"),
    FALHOU("Falhou"),
    CANCELADO("Cancelado"),
    BLOQUEADO("Bloqueado — obra não confere com a lore");

    private final String rotulo;

    StatusLoteTraducao(String rotulo) {
        this.rotulo = rotulo;
    }

    public String getRotulo() {
        return rotulo;
    }

    public static StatusLoteTraducao consolidar(List<ResultadoTraducaoArquivo> resultados) {
        return consolidar(resultados, 0);
    }

    /**
     * @param bloqueadosPorObra quantos dos resultados {@code BLOQUEADO} vieram da guarda
     *        obra×contexto (e não de arquivo já traduzido) — esses contam como falha
     */
    public static StatusLoteTraducao consolidar(List<ResultadoTraducaoArquivo> resultados, int bloqueadosPorObra) {
        if (resultados == null || resultados.isEmpty()) {
            return FALHOU;
        }
        // BLOQUEADO por arquivo já traduzido (rerun): não é sucesso novo nem falha; fica FORA do
        // denominador para não contaminar o desfecho do lote. O bloqueio por OBRA fica dentro.
        long bloqueados = resultados.stream()
            .filter(r -> r.status() == StatusArquivoTraducao.BLOQUEADO).count();
        long porObra = Math.min(Math.max(bloqueadosPorObra, 0), bloqueados);
        long naoBloqueados = resultados.size() - bloqueados + porObra;
        if (naoBloqueados == 0) {
            return CONCLUIDO; // lote inteiramente já traduzido: nada falhou
        }
        if (porObra == naoBloqueados) {
            return BLOQUEADO; // tudo o que havia a fazer foi recusado pela guarda obra×contexto
        }
        long ok = resultados.stream().filter(r ->
            r.status() == StatusArquivoTraducao.CONCLUIDO || r.status() == StatusArquivoTraducao.PARCIAL).count();
        if (ok == naoBloqueados) {
            return CONCLUIDO;
        }
        if (ok == 0) {
            return FALHOU;
        }
        return CONCLUIDO_COM_FALHAS;
    }
}
