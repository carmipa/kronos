package org.traducao.projeto.traducaoKaraoke.domain;

import java.util.List;

/**
 * Resumo, por arquivo .ass, do que a tradução de karaokê classificou e fez.
 * Alimenta o console da UI, o manifesto de auditoria e a telemetria.
 */
public record ResultadoTraducaoKaraoke(
    String arquivo,
    String arquivoDestino,
    int eventosTotais,
    int efeitosKfxPreservados,
    int preservadasOriginalJapones,
    int jaEmPortugues,
    int paraTraduzir,
    int reaproveitadasCache,
    int traduzidas,
    int mantidasSemTraducao,
    /**
     * Falas em que a camada portuguesa teve acento REPOSTO nesta fatia — o efeito medido do
     * {@link AcentosLetraKaraoke} mais o do dicionário. Existe para que o defeito apareça na
     * telemetria DA FATIA no momento em que acontece, em vez de ser descoberto meses depois
     * lendo o {@code .ass}: na tradução do 86 de 2026-08-14, 5 falas distintas saíram com
     * {@code nao} sem acento e ninguém tinha um número para olhar.
     */
    int acentosRepostos,
    /**
     * Entradas que estavam no cache do arquivo e NÃO foram usadas nesta execução — elas somem
     * quando o cache é regravado, porque {@code salvarCache} escreve só o que foi aplicado.
     *
     * <h2>Por que isto precisa de número, e não pode acontecer calado</h2>
     * A régua de evidência positiva (2026-08-19) tornou inalcançáveis as entradas que o
     * classificador antigo criou por engano: no cache real havia <b>258 de estilo {@code Signs}</b>
     * (cartazes de data) e <b>8 de estilo romaji</b> ({@code yasashikatta → foi bom}). Elas não
     * são mais consultadas e serão descartadas na primeira regravação — o que é o desfecho certo.
     *
     * <p>Sem este contador, porém, o operador veria o cache ENCOLHER sem explicação, e cache que
     * diminui sozinho é indistinguível de perda de dado. O número transforma limpeza silenciosa
     * em limpeza declarada.
     */
    int entradasCacheDescartadas,
    /**
     * Frases de KFX palavra a palavra que ficaram SÓ em português (sem o inglês empilhado),
     * decisão de Paulo em 12/10/2026 depois de ver a abertura do Unicorn na TV: empilhar punha o
     * português debaixo das palavras do efeito, e o inglês saía escrito em cima dele.
     */
    int frasesKfxSoPortugues,
    /**
     * Sílabas/palavras do efeito dessas frases que viraram {@code Comment:} — fora da tela, ainda
     * no arquivo. Sem o número, "a abertura perdeu o efeito" e "a abertura foi corrigida" seriam
     * indistinguíveis no dataset.
     */
    int silabasKfxSilenciadas,
    /** Linhas portuguesas que receberam a fonte do diálogo porque a da música não tem o glifo. */
    int linhasComFonteDoDialogo,
    /**
     * {@code NAO_NECESSARIO}, {@code VERIFICADAS (video)} ou {@code NAO_VERIFICADAS: motivo} — três
     * estados, para "nenhuma fonte trocada" nunca significar ao mesmo tempo "conferi" e "não pude
     * conferir" (A8).
     */
    String fontesDaMusica,
    List<String> avisos
) {
}
