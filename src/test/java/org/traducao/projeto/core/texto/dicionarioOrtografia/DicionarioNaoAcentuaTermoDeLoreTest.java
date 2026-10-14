package org.traducao.projeto.core.texto.dicionarioOrtografia;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * PROPÓSITO DE NEGÓCIO: nome próprio de ficção não leva acento do português, e o dicionário não
 * tem como saber que aquilo é um nome. Os termos de lore da obra são intocáveis para ele.
 *
 * <h2>O prejuízo, medido no acervo em 18/08/2026</h2>
 * Varrendo os 1.142 termos protegidos do catálogo contra a legenda ENTREGUE, três chegaram
 * acentuados, em 32 falas — e os três são defeito:
 * <pre>
 *   Apsaras -> Apsarás   23 falas   mobile armor do 08th MS Team
 *   Bosnia  -> Bósnia     7 falas   uma NAVE do Zeta
 *   Cardeas -> Cárdeas    2 falas   Cardeas Vist, do Unicorn
 * </pre>
 *
 * <h2>O caso Bosnia, que é a razão de esta guarda existir</h2>
 * Parecia a exceção legítima: "Bósnia" é o português correto para o país. Só que no Zeta a fala
 * é <i>"Send a signal flare to the Bosnia"</i> e <i>"Continue the pursuit with the Alexandria,
 * Bosnia and Sichuan"</i> — <b>são navios</b>. O acento transformou uma nave num país.
 *
 * <p>E o resultado é português impecável. Nenhuma revisão de idioma pegaria; só quem conferir
 * contra o inglês. É o defeito que passa por todas as réguas de qualidade porque não parece
 * defeito — daí ele precisar de mecanismo, não de olho.
 *
 * <h2>Invariantes do domínio</h2>
 * Consertar caso a caso no {@code correcoesTerminologia} resolve o que já foi traduzido e não
 * impede a próxima obra de nascer com o mesmo dano. O contra-teste garante que palavra COMUM
 * continue sendo acentuada — senão a guarda viraria "desligar o dicionário".
 *
 * <h2>Comportamento em caso de falha</h2>
 * A mensagem mostra o texto que saiu e lembra qual nome foi corrompido.
 */
class DicionarioNaoAcentuaTermoDeLoreTest {

    /** Dicionário de mentira: responde exatamente o que o hunspell respondeu no acervo. */
    private static final class DicionarioDeMentira implements DicionarioOrtograficoPort {

        private static final Map<String, Set<String>> RESPOSTAS = Map.of(
            "Apsaras", Set.of("Apsarás"),
            "apsaras", Set.of("apsarás"),
            "Bosnia", Set.of("Bósnia"),
            "Cardeas", Set.of("Cárdeas"),
            "fatidico", Set.of("fatídico"),
            "Aida", Set.of("Aída"),
            "Virus", Set.of("Vírus"),
            "virus", Set.of("vírus"));

        @Override
        public boolean disponivel() {
            return true;
        }

        @Override
        public String descricao() {
            return "dicionario de mentira do teste";
        }

        @Override
        public Set<String> desconhecidas(Collection<String> palavras) {
            Set<String> saida = new LinkedHashSet<>();
            for (String palavra : palavras) {
                if (RESPOSTAS.containsKey(palavra)) {
                    saida.add(palavra);
                }
            }
            return saida;
        }

        @Override
        public Map<String, Set<String>> sugestoes(Collection<String> palavras) {
            Map<String, Set<String>> saida = new LinkedHashMap<>();
            for (String palavra : palavras) {
                Set<String> resposta = RESPOSTAS.get(palavra);
                if (resposta != null) {
                    saida.put(palavra, new LinkedHashSet<>(resposta));
                }
            }
            return saida;
        }
    }

    private final CorretorOrtograficoLegenda corretor =
        new CorretorOrtograficoLegenda(new DicionarioDeMentira());

    private static final Set<String> LORE = Set.of("Apsaras", "Bosnia", "Cardeas");

    @Test
    @DisplayName("o caso REAL do Zeta: a nave Bosnia nao vira o pais Bosnia")
    void naveNaoViraPais() {
        String saida = corretor.corrigir("Envie um sinal de fumaca para a Bosnia.", LORE);

        assertEquals("Envie um sinal de fumaca para a Bosnia.", saida,
            "o ingles diz \"Send a signal flare to the Bosnia\" — e uma NAVE. O acento a "
                + "transforma num pais, e o portugues fica impecavel: ninguem pega sem conferir "
                + "contra o original.");
    }

    @Test
    @DisplayName("Apsaras e Cardeas atravessam sem acento")
    void nomesDeLoreAtravessamIntactos() {
        assertEquals("Minha Apsaras, voce quer dizer?",
            corretor.corrigir("Minha Apsaras, voce quer dizer?", LORE),
            "23 falas do 08th foram entregues como \"Apsarás\"");
        assertEquals("Eu sou o chefe da familia, Cardeas Vist.",
            corretor.corrigir("Eu sou o chefe da familia, Cardeas Vist.", LORE));
    }

    @Test
    @DisplayName("CONTRA-TESTE: palavra COMUM continua sendo acentuada")
    void palavraComumContinuaSendoCorrigida() {
        assertEquals("Foi um dia fatídico.",
            corretor.corrigir("Foi um dia fatidico.", LORE),
            "a guarda protege NOME DE LORE, nao desliga o dicionario. Sem esta assercao, "
                + "devolver o texto intacto sempre passaria nos testes acima — e o corretor de "
                + "acento existe porque a lista manual resolvia ZERO das 119 faltas do Zeta.");
    }

    @Test
    @DisplayName("o caso REAL da Reconguista I: a palavra de um termo COMPOSTO tambem e intocavel")
    void palavraDeTermoCompostoAtravessaIntacta() {
        Set<String> greco = Set.of("Aida Rayhunton", "Aida Surugan");

        assertEquals("Aida Rayhunton. Pirata espacial.",
            corretor.corrigir("Aida Rayhunton. Pirata espacial.", greco),
            "a lista comparava a palavra \"Aida\" com o termo inteiro \"Aida Rayhunton\" e nao "
                + "protegia nada: a 2.1 entregou \"Aída\" em 20 falas da Reconguista I");
        assertEquals("Onde esta a Senhorita Aida?!",
            corretor.corrigir("Onde esta a Senhorita Aida?!", greco),
            "o filme chama a personagem so pelo primeiro nome, que e a forma mais comum");
    }

    @Test
    @DisplayName("FRONTEIRA: o mesmo sinal em minuscula e substantivo comum e continua acentuado")
    void substantivoComumComOMesmoSinalContinuaAcentuado() {
        Set<String> guiltyCrown = Set.of("Apocalypse Virus");

        assertEquals("O Apocalypse Virus voltou.",
            corretor.corrigir("O Apocalypse Virus voltou.", guiltyCrown),
            "12/10, Guilty Crown: 14 falas entregues como \"Apocalypse Vírus\"");
        assertEquals("O vírus se espalhou pela cidade.",
            corretor.corrigir("O virus se espalhou pela cidade.", guiltyCrown),
            "a peca do termo vale com a caixa do termo: sem isso, a lore do Guilty Crown "
                + "desligaria o acento do substantivo \"vírus\" na obra inteira");
    }

    @Test
    @DisplayName("CONTRA-TESTE: sem lista, o comportamento anterior continua")
    void semListaAcentuaComoAntes() {
        assertEquals("Aída Rayhunton.", corretor.corrigir("Aida Rayhunton.", Set.of()),
            "prova de que o dicionario REALMENTE acentuaria \"Aida\" sem a lista");
        assertEquals("Minha Apsarás, voce quer dizer?",
            corretor.corrigir("Minha Apsaras, voce quer dizer?", Set.of()),
            "lista vazia tem de reproduzir o comportamento de antes — e a prova de que o "
                + "dicionario REALMENTE acentuaria este nome, sem a qual os testes acima "
                + "estariam verdes por o dicionario nao propor nada");
    }
}
