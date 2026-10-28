package org.traducao.projeto.qualidadeTraducao.application;

import org.junit.jupiter.api.Test;
import org.traducao.projeto.qualidadeTraducao.domain.LoreAtivaPort;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * PROPÓSITO DE NEGÓCIO: impede que nomes próprios legítimos sejam enviados ao
 * revisor apenas porque são idênticos no inglês e no PT-BR.
 * <p>INVARIANTES DO DOMÍNIO: hesitação e pontuação não descaracterizam nomes;
 * palavras conversacionais inglesas continuam pendentes.
 * <p>COMPORTAMENTO EM CASO DE FALHA: falso nome ou falso inglês reprova o teste.
 */
class DetectorTraducaoIdenticaServiceTest {

    /**
     * PROPÓSITO DE NEGÓCIO: fake de {@link LoreAtivaPort} fiel ao estado que o detector
     * enxergava antes da E8c.1 com {@code new GerenciadorContexto(List.of())} — nenhum
     * provedor de contexto, logo nenhum termo protegido e a lore neutra do prompt padrão.
     * <p>INVARIANTES DO DOMÍNIO: reproduz exatamente os retornos daquele cenário para que
     * a inversão de dependência não altere o comportamento observado nestes casos.
     * <p>COMPORTAMENTO EM CASO DE FALHA: não lança; retornos fixos e determinísticos.
     */
    private static final class LoreAtivaVazia implements LoreAtivaPort {
        @Override
        public Set<String> termosProtegidosAtivos() {
            return Set.of();
        }

        @Override
        public String obterLoreAtiva() {
            return "Voce e um tradutor especialista. Traduza fielmente.";
        }
    }

    private final DetectorTraducaoIdenticaService detector =
        new DetectorTraducaoIdenticaService(new LoreAtivaVazia());

    /**
     * PROPÓSITO DE NEGÓCIO: cobre os nomes que o Nemo recebeu indevidamente na execução real.
     * <p>INVARIANTES DO DOMÍNIO: uma a quatro palavras capitalizadas são preservadas.
     * <p>COMPORTAMENTO EM CASO DE FALHA: qualquer nome não reconhecido reprova o teste.
     */
    @Test
    void preservaNomesSimplesGaguejadosESequenciais() {
        assertTrue(detector.deveManterIdentico("Maria?"));
        assertTrue(detector.deveManterIdentico("E-Eledore..."));
        assertTrue(detector.deveManterIdentico("Rob... Sally... Mike..."));
    }

    /**
     * PROPÓSITO DE NEGÓCIO: mantém saudações inglesas na fila de tradução.
     * <p>INVARIANTES DO DOMÍNIO: capitalização isolada não protege vocabulário comum.
     * <p>COMPORTAMENTO EM CASO DE FALHA: classificação como nome reprova o teste.
     */
    @Test
    void naoConfundePalavraInglesaComNome() {
        assertFalse(detector.deveManterIdentico("Hello!"));
    }

    /**
     * PROPÓSITO DE NEGÓCIO: nome próprio CORTADO no meio, com duas letras, não é tradução
     * faltando. O piso de 3 letras da heurística de nome próprio reprovava esse caso.
     *
     * <h2>O custo, medido em 12/08/2026</h2>
     * A Revisão de Legendas varreu as 5.620 falas do Gundam Unicorn e reportou 3 problemas;
     * os TRÊS eram falso positivo, e este era um deles — E22, linha 157:
     * <pre>
     *   EN  "Ri..."      (começo de "Riddhe", personagem sendo interrompido)
     *   PT  "Ri..."      -> acusada de "fala não traduzida (idêntica ao original)"
     * </pre>
     * Depois de {@code limpar} remover a pontuação sobram DUAS letras, e o piso de três as
     * reprovava. Relatório que aponta 3 e erra nos 3 ensina a ignorar o relatório.
     */
    @Test
    @org.junit.jupiter.api.DisplayName("Unicorn E22: nome próprio interrompido com duas letras é preservado")
    void nomeProprioInterrompidoComDuasLetrasEhPreservado() {
        assertTrue(detector.deveManterIdentico("Ri..."), "\"Ri...\" é o começo de Riddhe");
        assertTrue(detector.deveManterIdentico("Ba…"), "reticência tipográfica também conta");
    }

    /**
     * A CONTRAPROVA: o afrouxamento vale só para fala interrompida, e não alcança palavra
     * inglesa comum. Sem este par, baixar o piso poderia ter congelado inglês em legenda —
     * exatamente o vazamento que a régua de evidência existe para impedir.
     */
    @Test
    @org.junit.jupiter.api.DisplayName("contraprova: o piso menor não libera inglês comum nem palavra solta de 2 letras")
    void afrouxamentoNaoAlcancaInglesComum() {
        assertFalse(detector.deveManterIdentico("No..."), "'No' é inglês comum, tem de ser traduzido");
        assertFalse(detector.deveManterIdentico("Go..."), "'Go' é inglês comum, tem de ser traduzido");
        assertFalse(detector.deveManterIdentico("Hello..."),
            "reticência não pode absolver palavra inglesa de vocabulário");
    }

    /** Lore com a terminologia UC de Zeon, como as obras da Guerra de Um Ano declaram. */
    private static final class LoreZeon implements LoreAtivaPort {
        @Override
        public Set<String> termosProtegidosAtivos() {
            return Set.of("Sieg Zeon", "Zeon", "Principality of Zeon", "Earth Federation",
                "Minovsky", "Gundam");
        }

        @Override
        public String obterLoreAtiva() {
            return "Universal Century; Principality of Zeon vs Earth Federation.";
        }
    }

    private final DetectorTraducaoIdenticaService detectorZeon =
        new DetectorTraducaoIdenticaService(new LoreZeon());

    /**
     * PROPÓSITO DE NEGÓCIO: o brado do Principado de Zeon é canônico em toda a linha do
     * tempo UC e deve permanecer no original — traduzi-lo para "Salve Zeon!" perde o
     * paralelo histórico deliberado da obra. Caso REAL: na corrida de 2026-07-22 a fala
     * "Sieg Zeon! Sieg Zeon! Sieg Zeon! Sieg Zeon!" virou pendência por "modelo devolveu o
     * texto original", porque o reconhecimento exigia o texto inteiro igual ao termo e não
     * enxergava a repetição.
     * <p>INVARIANTES DO DOMÍNIO: repetição do termo canônico continua canônica.
     * <p>COMPORTAMENTO EM CASO DE FALHA: se voltar a reprovar, o brado vira pendência a
     * cada execução de qualquer obra UC.
     */
    @Test
    void bradoCanonicoRepetidoPermaneceNoOriginal() {
        assertTrue(detectorZeon.deveManterIdentico("Sieg Zeon! Sieg Zeon! Sieg Zeon! Sieg Zeon!"));
        assertTrue(detectorZeon.deveManterIdentico("{\\i1}Sieg Zeon! Sieg Zeon!{\\i0}"));
        assertTrue(detectorZeon.deveManterIdentico("Sieg Zeon!"));
    }

    /**
     * PROPÓSITO DE NEGÓCIO: garante que a regra nova NÃO virou porta dos fundos — basta
     * sobrar uma palavra traduzível para a fala continuar exigindo tradução.
     * <p>INVARIANTES DO DOMÍNIO: só texto INTEIRAMENTE composto de termos declarados passa.
     * <p>COMPORTAMENTO EM CASO DE FALHA: aceitar frase inglesa como tradução.
     */
    @Test
    void fraseComPalavraTraduzivelContinuaPendente() {
        assertFalse(detectorZeon.deveManterIdentico("Zeon attacks the colony"),
            "sobrou \"attacks the colony\": a fala não foi traduzida");
        assertFalse(detectorZeon.deveManterIdentico("The Gundam is here"),
            "sobrou \"is here\": a fala não foi traduzida");
    }

    /**
     * PROPÓSITO DE NEGÓCIO: fecha o vazamento MEDIDO de inglês publicado como se fosse tradução.
     * Em 2026-07-22 varri as 55.322 entradas distintas dos 216 caches versionados: 1.569 falas
     * saíram idênticas ao original e 128 dessas ocorrências eram inglês corrente, entregue no
     * {@code _PT-BR.ass} final e gravado no cache como tradução VÁLIDA — congelando em inglês,
     * porque {@code isCacheReaproveitavel} consulta esta mesma régua na execução seguinte.
     *
     * <p>Todas passavam pela última linha de {@code deveManterPalavraUnicaIdentica}: "3+ letras
     * e inicial maiúscula ⇒ deve ser nome próprio". Nenhuma lore, por melhor que fosse curada,
     * fecharia isso — a regra nem consultava a lore antes de aceitar.
     *
     * <p>INVARIANTES DO DOMÍNIO: evidência positiva de inglês traduzível RECUSA a identidade.
     * <p>COMPORTAMENTO EM CASO DE FALHA: se voltar a aceitar, o inglês volta à legenda entregue.
     */
    @Test
    void inglesCorrenteMedidoNosCachesDeixaDeSerAceitoComoTraducao() {
        assertFalse(detectorZeon.deveManterIdentico("Next Episode"), "63 ocorrências medidas");
        assertFalse(detectorZeon.deveManterIdentico("NEXT EPISODE"), "caixa alta não é salvo-conduto");
        assertTrue(detectorZeon.deveManterIdentico("Roger!"),
            "DECISÃO EDITORIAL (Paulo, 2026-07-23): 'Roger' de rádio PODE ficar em inglês — é "
                + "compreendido em PT-BR. Das 220 ocorrências no acervo, 179 viraram 'Entendido' "
                + "e 34 ficaram em inglês; as duas formas são aceitáveis, então recusar aqui "
                + "transformaria 34 falas corretas em pendência");
        assertFalse(detectorZeon.deveManterIdentico("Gotcha!"), "12 ocorrências medidas");
        assertFalse(detectorZeon.deveManterIdentico("Heavy!"), "caso real do episódio 2");
        assertFalse(detectorZeon.deveManterIdentico("Enter."), "caso real do episódio 7");
        assertFalse(detectorZeon.deveManterIdentico("\"Doctor Flanagan\"...?"), "caso real do episódio 12");
        assertFalse(detectorZeon.deveManterIdentico("Big Brother! Big Brother!"));
        assertFalse(detectorZeon.deveManterIdentico("Little Sister!"));
        assertFalse(detectorZeon.deveManterIdentico("Ready, Lieutenant!"));
        assertFalse(detectorZeon.deveManterIdentico("Move!"));
        assertFalse(detectorZeon.deveManterIdentico("Fire!"));
    }

    /**
     * PROPÓSITO DE NEGÓCIO: a régua por evidência não pode cobrar o preço dos nomes próprios.
     * As mesmas 1.569 falas idênticas incluem 995 de uma palavra só que são personagens reais
     * — e as legendas os chamam pelo PRIMEIRO nome, enquanto a lore declara o nome completo.
     * Uma régua que exigisse cadastro exaustivo reprovaria todos eles.
     *
     * <p>INVARIANTES DO DOMÍNIO: sem evidência de inglês traduzível, a identidade é preservada,
     * esteja o nome declarado na lore ou não.
     * <p>COMPORTAMENTO EM CASO DE FALHA: personagem virando pendência a cada execução, gravado
     * como vazio no cache e retentado para sempre.
     */
    @Test
    void nomesProprioMedidosNosCachesContinuamPreservados() {
        // Nenhum destes está declarado na LoreZeon deste teste: são aceitos por AUSÊNCIA de
        // evidência, que é justamente a propriedade que dispensa curadoria exaustiva.
        assertTrue(detectorZeon.deveManterIdentico("Kamille!"), "43 ocorrências medidas");
        assertTrue(detectorZeon.deveManterIdentico("Judau!"), "39 ocorrências medidas");
        assertTrue(detectorZeon.deveManterIdentico("Michel!"));
        assertTrue(detectorZeon.deveManterIdentico("Karen!"));
        assertTrue(detectorZeon.deveManterIdentico("Dell!"), "não está em lore nenhuma e mesmo assim é nome");
        assertTrue(detectorZeon.deveManterIdentico("Leina!"));
        assertTrue(detectorZeon.deveManterIdentico("E-Eledore..."), "hesitação não descaracteriza o nome");
    }

    /**
     * PROPÓSITO DE NEGÓCIO: quando a própria obra DECLARA uma expressão que contém palavra do
     * léxico, a terminologia canônica vence — senão declarar o termo na lore não teria efeito.
     *
     * <p>INVARIANTES DO DOMÍNIO: o veto age tanto sobre a fala inteira quanto por token.
     * <p>COMPORTAMENTO EM CASO DE FALHA: terminologia oficial da obra sendo traduzida.
     */
    @Test
    void terminologiaDeclaradaVenceOLexicoDeEvidencia() {
        DetectorTraducaoIdenticaService comTitulo = new DetectorTraducaoIdenticaService(new LoreAtivaPort() {
            @Override public Set<String> termosProtegidosAtivos() {
                return Set.of("War in the Pocket", "Doctor Flanagan", "Zeon");
            }
            @Override public String obterLoreAtiva() {
                return "Universal Century.";
            }
        });
        assertTrue(comTitulo.deveManterIdentico("War in the Pocket"),
            "título declarado da obra permanece, mesmo contendo palavra do léxico");
        assertTrue(comTitulo.deveManterIdentico("\"Doctor Flanagan\"...?"),
            "declarado na lore, 'doctor' deixa de ser evidência");
    }

    /**
     * PROPÓSITO DE NEGÓCIO: fala feita SÓ de números não tem tradução possível — contagem
     * regressiva, leitura de altímetro, coordenadas. Publicá-la idêntica é a única saída correta.
     *
     * <p>CASO REAL (2026-07-23): a "Limpar Falhas do Cache" apagava {@code "98! 97!"} do episódio
     * 11 do 08th MS Team a cada passagem, recriando uma pendência inexistente. O caso de UMA
     * palavra já era aceito ({@code "6000!"} passa por {@code matches("\\d+")}), mas a SEQUÊNCIA
     * caía na heurística de capitalização — e {@code '9'} não é maiúscula, então a fala era
     * acusada de eco.
     *
     * <p>INVARIANTES DO DOMÍNIO: só dígitos, espaço e pontuação passam; basta uma letra para a
     * fala voltar a exigir tradução.
     *
     * <p>COMPORTAMENTO EM CASO DE FALHA: a contagem regressiva vira pendência eterna, apagada e
     * retentada a cada execução, em qualquer obra com números em sequência.
     */
    @Test
    void falaFeitaSoDeNumerosNaoExigeTraducao() {
        assertTrue(detector.deveManterIdentico("98! 97!"), "contagem regressiva");
        assertTrue(detector.deveManterIdentico("{\\i1}98! 97!"), "com tag ASS");
        assertTrue(detector.deveManterIdentico("6500! 7000! 8000!"), "leitura de altímetro");
        assertTrue(detector.deveManterIdentico("3... 2... 1..."), "contagem com reticências");

        assertFalse(detector.deveManterIdentico("Level 5"), "tem letra: continua exigindo tradução");
        assertFalse(detector.deveManterIdentico("98 dead"), "número + palavra inglesa");
    }

    /**
     * PROPÓSITO DE NEGÓCIO: REGRA DE OURO — nenhuma palavra ganha imunidade só por aparecer
     * dentro da prosa do prompt ou recortada de um termo composto. As duas rotas de imunidade
     * indevida existiram de fato na primeira versão desta régua e foram apanhadas em revisão:
     * (1) o veto chamava {@code termoDoLoreAtivo}, que varre o TEXTO CORRIDO do prompt, e a
     * prosa do 08th contém "Miller's Report" — bastava isso para {@code Report!} continuar
     * vazando; (2) o veto decompunha termos compostos em tokens, então declarar
     * {@code "Far East Division"} imunizava {@code East!} e {@code Division!}.
     *
     * <p>INVARIANTES DO DOMÍNIO: o veto consome apenas termos DECLARADOS e INTEIROS; a prosa do
     * prompt não participa; sobrar qualquer palavra do léxico recusa a identidade.
     *
     * <p>COMPORTAMENTO EM CASO DE FALHA: qualquer uma das duas rotas reabre o vazamento sem que
     * nenhum outro teste perceba — foi assim que ele passou despercebido da primeira vez.
     */
    @Test
    void componenteDeTermoCompostoNaoHerdaImunidadeDoTermoInteiro() {
        DetectorTraducaoIdenticaService comCompostos = new DetectorTraducaoIdenticaService(new LoreAtivaPort() {
            @Override public Set<String> termosProtegidosAtivos() {
                return Set.of("Miller's Report", "Far East Division", "Michel Ninorich");
            }
            @Override public String obterLoreAtiva() {
                // A prosa MENCIONA os termos: é precisamente o que não pode conceder imunidade.
                return "Universal Century. Miller's Report; Far East Division; Magella Attack; 08th MS Team.";
            }
        });

        assertTrue(comCompostos.deveManterIdentico("Miller's Report"),
            "termo composto declarado por inteiro permanece");
        assertTrue(comCompostos.deveManterIdentico("Far East Division"),
            "termo composto declarado por inteiro permanece");

        assertFalse(comCompostos.deveManterIdentico("Report!"),
            "'report' está na prosa e dentro de um termo composto — nenhum dos dois dá imunidade");
        assertFalse(comCompostos.deveManterIdentico("East!"),
            "componente solto de 'Far East Division' deve ser traduzido");
        assertFalse(comCompostos.deveManterIdentico("Division!"),
            "componente solto de 'Far East Division' deve ser traduzido");
    }

    /**
     * PROPÓSITO DE NEGÓCIO: a mudança precisa ser MONÓTONA — a régua por evidência só pode
     * recusar, nunca aceitar algo que as heurísticas históricas já recusavam. Sem isso, um
     * conserto de vazamento poderia abrir outro.
     *
     * <p>INVARIANTES DO DOMÍNIO: o portão é uma conjunção, não uma disjunção.
     * <p>COMPORTAMENTO EM CASO DE FALHA: inglês novo passando a ser aceito.
     */
    @Test
    void reguaPorEvidenciaSoRecusaNuncaPassaAAceitar() {
        assertFalse(detector.deveManterIdentico("Hello!"), "continua recusado como sempre foi");
        assertFalse(detectorZeon.deveManterIdentico("Zeon attacks the colony"),
            "sobra texto traduzível: segue recusado pela régua histórica");
    }

    /**
     * PROPÓSITO DE NEGÓCIO: sem obra selecionada, a regra nova não pode inventar proteção.
     * <p>INVARIANTES DO DOMÍNIO: lore vazia degrada para as heurísticas globais.
     * <p>COMPORTAMENTO EM CASO DE FALHA: aceitação cega sem terminologia declarada.
     */
    @Test
    void semLoreAtivaOBradoNaoEhProtegidoPelaRegraNova() {
        assertFalse(detector.deveManterIdentico("Sieg Zeon! Sieg Zeon! Sieg Zeon! Sieg Zeon!"),
            "sem termos declarados, nada autoriza manter a fala em inglês");
    }

    /**
     * PROPÓSITO DE NEGÓCIO: interjeições idênticas (Ah, Oh, Hmm) devem ser mantidas
     * porque são universais e não traduzíveis.
     * <p>INVARIANTES DO DOMÍNIO: regex precisa ser exata e tolerar alongamento.
     */
    @Test
    void aceitaInterjeicoesDeLinguaInglesa() {
        String[] aceitas = {"Oh", "Ooooh", "Ah", "Ahhh", "Uh", "Eh", "Er", "Hm", "Hmm", "Ugh", "Tch", "Hmph"};
        for (String interjeicao : aceitas) {
            assertTrue(detector.deveManterIdentico(interjeicao), "deve aceitar interjeição: " + interjeicao);
        }
    }

    /**
     * PROPÓSITO DE NEGÓCIO: palavras normais ou interjeições que têm tradução 
     * estabelecida não podem vazar pela regra de interjeições.
     * <p>INVARIANTES DO DOMÍNIO: regex tem âncora de início e fim.
     */
    @Test
    void recusaFalsasInterjeicoesEPalavrasTraduziveis() {
        // Testando em minúsculas para não acionar a heurística de `Character.isUpperCase`
        // Assim isolamos que elas não vazam pela regra da Regex de interjeições.
        // Nota: a palavra "um" foi removida da lista porque ela está mockada como termo de lore
        // na configuração do teste principal.
        String[] recusadas = {"ouch", "ahoy", "tough", "hello", "no", "yes", "go", "me", "hey", "wow", "huh"};
        for (String recusa : recusadas) {
            assertFalse(detector.deveManterIdentico(recusa), "deve recusar falsa interjeição/traduzível: " + recusa);
        }
    }

    /**
     * PROPÓSITO DE NEGÓCIO: o eco que só muda a pontuação final continua sendo eco. Medido em 08/10/2026:
     * recusado o parêntese inventado, "A pursuer?! (Perseguidor?!)" voltou como "A pursuer?!!" e passava
     * como traduzida; "Mom?! (Mãe?!)" voltou como "Mom?!!" e a régua absolvia "Mom" como nome.
     * <p>INVARIANTES DO DOMÍNIO: A1 — o mesmo sinal (só a pontuação final mudou) em nome ou termo segue
     * absolvido: são os casos reais dos caches ("Judau?!" → "Judau?", "Reccoa...!" → "Reccoa!"). Original
     * feito só de pontuação não vira eco de nada.
     * <p>COMPORTAMENTO EM CASO DE FALHA: eco aceito, ou nome acusado, reprova o teste.
     */
    @Test
    void ecoComOutraPontuacaoFinalContinuaEco() {
        assertTrue(detector.pareceNaoTraduzida("A pursuer?!", "A pursuer?!!"), "eco com '!' a mais");
        assertTrue(detector.pareceNaoTraduzida("Damn it!", "Damn it!!"), "eco com '!' a mais");
        assertTrue(detector.pareceNaoTraduzida("Mom?!", "Mom?!!"), "mom é inglês traduzível, não nome");
        assertTrue(detector.pareceNaoTraduzida("Dad!", "Dad."), "dad é inglês traduzível, não nome");

        assertFalse(detector.pareceNaoTraduzida("Judau?!", "Judau?"));
        assertFalse(detector.pareceNaoTraduzida("Reccoa...!", "Reccoa!"));
        assertFalse(detector.pareceNaoTraduzida("Kamille?!", "Kamille?"));
        assertFalse(detector.pareceNaoTraduzida("...", "...!"));
        assertFalse(detector.pareceNaoTraduzida("A pursuer?!", "Um perseguidor?!"));
    }

    /**
     * 28/10/2026: a quebra {@code \N} perdia a contrabarra e ficava o N — "NEXT\NEPISODE" virava
     * a sigla NEXTNEPISODE, aceita idêntica pelo portão do cache e reprovada pelo final. Medido
     * nos 5 episódios do 0080. E "GO!" passava só por estar gritado.
     */
    @Test
    void quebraDoAssEhEspacoECaixaAltaNaoAbsolveInglesCorrente() {
        assertFalse(detector.deveManterIdentico("{\\fnQuadraat-Bold\\pos(718,548)}NEXT\\NEPISODE"),
            "letreiro em ingles com quebra tem de ir ao modelo");
        assertFalse(detector.deveManterIdentico("No!\\NNo!"));
        assertFalse(detector.deveManterIdentico("{\\b1\\fscx208}GO!"));
        assertFalse(detector.deveManterIdentico("HELP!"));

        assertTrue(detector.deveManterIdentico("GHQ!"), "CONTROLE: sigla continua preservada");
        assertTrue(detector.deveManterIdentico("GP02A"), "CONTROLE: sigla com digito continua preservada");
        assertTrue(detector.deveManterIdentico("OK!"), "CONTROLE: OK e como o portugues escreve");
        assertTrue(detector.deveManterIdentico("Kamille\\NJudau"), "CONTROLE: dois nomes com quebra");
        assertTrue(detector.deveManterIdentico(
                "{\\c&H0399C5&}C\\N\\N{\\c&H04733E&}a\\N\\N{\\c&H973C4F&}f\\N\\N{\\c&H9231C5&}é"),
            "CONTROLE: letreiro vertical, uma letra por linha, continua sendo a palavra que forma");
    }
}
