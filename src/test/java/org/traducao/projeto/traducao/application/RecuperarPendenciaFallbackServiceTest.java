package org.traducao.projeto.traducao.application;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.traducao.projeto.traducao.domain.ports.FallbackTraducaoMaquinaPort;
import org.traducao.projeto.traducao.infrastructure.config.FallbackOnlineProperties;

import java.util.LinkedHashSet;
import java.util.Map;
import java.util.function.Function;
import org.traducao.projeto.qualidadeTraducao.domain.LoreAtivaPort;
import org.traducao.projeto.traducao.domain.fallback.ProvedorFallback;
import org.traducao.projeto.traducao.domain.fallback.ResultadoFallback;
import org.traducao.projeto.traducao.domain.fallback.StatusFallback;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.traducao.projeto.qualidadeTraducao.application.LoreAtivaFake;

/**
 * PROPÓSITO DE NEGÓCIO: fixa o contrato do {@link RecuperarPendenciaFallbackService} — opt-in,
 * escopo restrito às pendências informadas e guarda de nomes próprios — sem rede.
 *
 * <p>INVARIANTES DO DOMÍNIO: desligado não chama a porta; ligado só aceita respostas que
 * preservam nomes próprios; recusa é segura (fala omitida do resultado).
 *
 * <p>COMPORTAMENTO EM CASO DE FALHA: qualquer desvio de gate, de guarda ou de escopo reprova.
 */
class RecuperarPendenciaFallbackServiceTest {

    private static RecuperarPendenciaFallbackService servico(boolean ativo, FallbackTraducaoMaquinaPort porta) {
        return servico(ativo, porta, Set.of());
    }

    /**
     * PROPÓSITO DE NEGÓCIO: monta o serviço com uma lore ATIVA controlada pelo teste — a guarda
     * passou a exigir preservação apenas do que a obra declara como termo protegido, então cada
     * caso precisa dizer qual é essa terminologia.
     * <p>INVARIANTES DO DOMÍNIO: lore vazia significa "obra sem terminologia declarada"; a guarda
     * então só exige siglas e identificadores.
     * <p>COMPORTAMENTO EM CASO DE FALHA: dublê puro, sem I/O; não lança.
     */
    private static RecuperarPendenciaFallbackService servico(
            boolean ativo, FallbackTraducaoMaquinaPort porta, Set<String> termosProtegidos) {
        LoreAtivaPort lore = new LoreAtivaPort() {
            @Override
            public Set<String> termosProtegidosAtivos() {
                return termosProtegidos;
            }

            @Override
            public String obterLoreAtiva() {
                return "";
            }
        };
        return new RecuperarPendenciaFallbackService(new FallbackOnlineProperties(ativo), porta, lore,
            new VerificadorIdentificadorNumerico(LoreAtivaFake.vazia()));
    }

    private static LinkedHashSet<String> conjunto(String... itens) {
        return new LinkedHashSet<>(Set.of(itens));
    }

    /**
     * PROPÓSITO DE NEGÓCIO: monta uma porta de teste a partir apenas da função de tradução,
     * para que cada caso declare só o desfecho que lhe interessa. A porta deixou de ser
     * funcional ao ganhar {@code provedor()}, e sem este helper cada teste teria de repetir
     * uma classe anônima de dois métodos.
     * <p>INVARIANTES DO DOMÍNIO: o provedor reportado é sempre {@link ProvedorFallback#GOOGLE};
     * nenhum caso deste teste depende de outro provedor.
     * <p>COMPORTAMENTO EM CASO DE FALHA: repassa o resultado da função sem interpretá-lo.
     */
    private static FallbackTraducaoMaquinaPort porta(Function<String, ResultadoFallback> traducao) {
        return new FallbackTraducaoMaquinaPort() {
            @Override
            public ResultadoFallback traduzir(String original) {
                return traducao.apply(original);
            }

            @Override
            public ProvedorFallback provedor() {
                return ProvedorFallback.GOOGLE;
            }
        };
    }

    @Test
    @DisplayName("desligado: não chama a porta e devolve mapa vazio")
    void desligadoNaoChamaPorta() {
        boolean[] chamou = {false};
        FallbackTraducaoMaquinaPort porta = porta(o -> { chamou[0] = true; return ResultadoFallback.recuperada("x", ProvedorFallback.GOOGLE); });

        Map<String, String> r = servico(false, porta).recuperar(conjunto("Hello")).recuperadas();

        assertTrue(r.isEmpty());
        assertTrue(!chamou[0], "porta não pode ser chamada com o modo desligado");
    }

    @Test
    @DisplayName("ligado: devolve a tradução quando a porta responde e os nomes são preservados")
    void ligadoRecuperaComNomePreservado() {
        FallbackTraducaoMaquinaPort porta = porta(o -> ResultadoFallback.recuperada("Eu vi o Lena ontem", ProvedorFallback.GOOGLE));

        Map<String, String> r = servico(true, porta).recuperar(conjunto("I saw Lena yesterday")).recuperadas();

        assertEquals("Eu vi o Lena ontem", r.get("I saw Lena yesterday"));
    }

    @Test
    @DisplayName("ligado: recusa (mantém pendente) quando um nome DA LORE some da tradução")
    void ligadoRecusaQuandoNomeDaLoreSome() {
        // "Lena" é personagem declarada pela obra; a tradução a perdeu -> recusa segura.
        // Desde F3 a exigência vem da terminologia da obra, não da capitalização.
        FallbackTraducaoMaquinaPort porta = porta(o -> ResultadoFallback.recuperada("Eu vi ela ontem", ProvedorFallback.GOOGLE));

        Map<String, String> r = servico(true, porta, Set.of("Lena"))
            .recuperar(conjunto("I saw Lena yesterday")).recuperadas();

        assertTrue(r.isEmpty(), "nome declarado pela lore e perdido deve manter a fala pendente");
    }

    /**
     * PROPÓSITO DE NEGÓCIO: documenta o TRADE-OFF assumido em F3 — um nome próprio que a obra
     * NÃO declara deixa de ser protegido por esta guarda. É deliberado: era exatamente essa
     * exigência indiscriminada que recusava 57,7% das pendências reais. A rede de segurança
     * passa a ser a validação canônica a jusante, aplicada pelo chamador.
     * <p>INVARIANTES DO DOMÍNIO: sem lore declarada, só siglas e identificadores obrigam.
     * <p>COMPORTAMENTO EM CASO DE FALHA: se voltar a recusar, a heurística de capitalização
     * ressurgiu.
     */
    @Test
    @DisplayName("F3 (trade-off): nome FORA da lore não é mais exigido pela guarda")
    void nomeForaDaLoreNaoEhMaisExigido() {
        FallbackTraducaoMaquinaPort porta = porta(o -> ResultadoFallback.recuperada("Eu vi ela ontem", ProvedorFallback.GOOGLE));

        Map<String, String> r = servico(true, porta).recuperar(conjunto("I saw Lena yesterday")).recuperadas();

        assertEquals("Eu vi ela ontem", r.get("I saw Lena yesterday"),
            "sem termo de lore declarado, a guarda não bloqueia — a validação canônica assume");
    }

    @Test
    @DisplayName("ligado: capital de início de frase não é tratado como nome próprio")
    void capitalDeInicioNaoEhNomeProprio() {
        // "Why" abre a frase; a tradução PT não o contém e mesmo assim é aceita.
        FallbackTraducaoMaquinaPort porta = porta(o -> ResultadoFallback.recuperada("Por que temos que aguentar isso", ProvedorFallback.GOOGLE));

        Map<String, String> r = servico(true, porta).recuperar(conjunto("Why do we have to put up with this")).recuperadas();

        assertEquals("Por que temos que aguentar isso", r.get("Why do we have to put up with this"));
    }

    @Test
    @DisplayName("ligado: porta vazia (rede/recusa) omite a fala do resultado")
    void portaVaziaOmiteFala() {
        FallbackTraducaoMaquinaPort porta = porta(o -> ResultadoFallback.recusada(ProvedorFallback.GOOGLE, StatusFallback.RESPOSTA_VAZIA, "sem resposta"));

        Map<String, String> r = servico(true, porta).recuperar(conjunto("Hello there")).recuperadas();

        assertTrue(r.isEmpty());
    }

    @Test
    @DisplayName("#7: termo da lore é exigido em QUALQUER posição, inclusive após token numérico")
    void termoDaLoreEhExigidoEmQualquerPosicao() {
        // "Zaku" é mecha declarado pela obra e vem após "42". Desde F3 a posição na frase deixou
        // de importar — o que obriga é ser terminologia da obra —, então ele é exigido aqui e
        // também seria se abrisse a frase (a regra antiga o deixaria escapar no início).
        FallbackTraducaoMaquinaPort porta = porta(o -> ResultadoFallback.recuperada("Pare! 42 caiu.", ProvedorFallback.GOOGLE));

        Map<String, String> r = servico(true, porta, Set.of("Zaku"))
            .recuperar(conjunto("Stop! 42 Zaku fell.")).recuperadas();

        assertTrue(r.isEmpty(), "termo da lore perdido deve manter a fala pendente");
    }

    /**
     * PROPÓSITO DE NEGÓCIO: caracteriza (subfase F1) o DEFEITO hoje vigente na guarda de nomes
     * próprios — tratar QUALQUER palavra capitalizada no meio da frase como nome próprio
     * obrigatório. Consequência real: um título em Title Case jamais pode ser traduzido, porque
     * a tradução correta necessariamente substitui as palavras capitalizadas. Nenhum provedor
     * de fallback contorna isso: a recusa acontece DEPOIS da resposta, sobre ela.
     *
     * <p>INVARIANTES DO DOMÍNIO: este teste fixa o comportamento ATUAL (recusa) para que a
     * correção da subfase F3 apareça como inversão explícita no diff. Ele NÃO descreve o
     * comportamento desejado — descreve o bug.
     *
     * <p>Medição da subfase F0 sobre as 560 falas pendentes reais dos caches versionados:
     * <b>323 delas (57,7%)</b> são recusadas exclusivamente por palavra capitalizada comum que
     * não é termo de lore, sigla nem identificador. Outras 35 (6,2%) dependem de termo de lore
     * legítimo e devem continuar protegidas.
     *
     * <p>COMPORTAMENTO EM CASO DE FALHA: se este teste passar a falhar sem que F3 tenha sido
     * aplicada, a guarda mudou por acidente — investigar antes de ajustar a expectativa.
     */
    @Test
    @DisplayName("F3 (corrigido): título em Title Case é ACEITO — 'Battle' não é termo de lore")
    void tituloTitleCaseAgoraEhAceito() {
        // Título real do Gundam 0083, corretamente traduzido: "Battle"/"Three"/"Dimensions"
        // somem, como devem. Nenhum deles é termo de lore, sigla ou identificador.
        FallbackTraducaoMaquinaPort porta = porta(o -> ResultadoFallback.recuperada("A Batalha em Três Dimensões", ProvedorFallback.GOOGLE));

        Map<String, String> r = servico(true, porta).recuperar(conjunto("The Battle in Three Dimensions")).recuperadas();

        assertEquals("A Batalha em Três Dimensões", r.get("The Battle in Three Dimensions"),
            "palavra capitalizada comum não pode mais reprovar um título corretamente traduzido");
    }

    /**
     * PROPÓSITO DE NEGÓCIO: prova que afrouxar a guarda NÃO desprotegeu a terminologia — o mesmo
     * título continua sendo recusado quando a obra declara "Battle" como termo protegido. É o
     * contraponto obrigatório do teste acima: sem ele, "passou a aceitar" poderia significar
     * "parou de verificar".
     * <p>INVARIANTES DO DOMÍNIO: a exigência agora vem da lore, não da capitalização.
     * <p>COMPORTAMENTO EM CASO DE FALHA: se passar a aceitar, a proteção de lore regrediu.
     */
    @Test
    @DisplayName("F3: o MESMO título é recusado quando 'Battle' é termo protegido da obra")
    void tituloEhRecusadoQuandoPalavraEhTermoDeLore() {
        FallbackTraducaoMaquinaPort porta = porta(o -> ResultadoFallback.recuperada("A Batalha em Três Dimensões", ProvedorFallback.GOOGLE));

        Map<String, String> r = servico(true, porta, Set.of("Battle"))
            .recuperar(conjunto("The Battle in Three Dimensions")).recuperadas();

        assertTrue(r.isEmpty(), "termo declarado pela lore deve continuar obrigatório na tradução");
    }

    /**
     * PROPÓSITO DE NEGÓCIO: caracteriza o mesmo defeito no padrão de DATA/legenda de época
     * ("May 12th, Stellar Year 2148"), que aparece com alta frequência nas pendências reais
     * (Stellar 42x, Year 43x na medição F0). "Stellar" e "Year" não são nomes próprios, mas a
     * guarda os exige.
     * <p>INVARIANTES DO DOMÍNIO: fixa o comportamento atual; F3 inverte.
     * <p>COMPORTAMENTO EM CASO DE FALHA: ver o teste irmão acima.
     */
    @Test
    @DisplayName("F3 (corrigido): data de época é ACEITA — mas o número 2148 continua exigido")
    void dataDeEpocaAgoraEhAceita() {
        // "Stellar"/"Year" são palavras comuns e podem ser traduzidas; "2148" é identificador
        // numérico e a tradução o preserva — por isso passa.
        FallbackTraducaoMaquinaPort porta = porta(o -> ResultadoFallback.recuperada("12 de maio, Ano Estelar 2148", ProvedorFallback.GOOGLE));

        Map<String, String> r = servico(true, porta).recuperar(conjunto("May 12th, Stellar Year 2148")).recuperadas();

        assertEquals("12 de maio, Ano Estelar 2148", r.get("May 12th, Stellar Year 2148"));
    }

    /**
     * PROPÓSITO DE NEGÓCIO: regressão de um falso-positivo achado no benchmark F4 — o ordinal
     * português ("1º") coloca depois do número um caractere que o Unicode classifica como LETRA.
     * Com fronteira {@code \p{L}}, o "1" exigido de "September 1st" não era reconhecido dentro
     * de "1º de setembro", e uma data corretamente traduzida era recusada. Ocorria em falas
     * reais do Gundam 0083 ("September 1st, Stellar Year 2149").
     * <p>INVARIANTES DO DOMÍNIO: token puramente numérico usa fronteira só de dígito.
     * <p>COMPORTAMENTO EM CASO DE FALHA: recusar aqui significa que a fronteira voltou a exigir
     * separador não-letra depois do número.
     */
    @Test
    @DisplayName("F4-fix: número seguido de ordinal português ('1º') conta como sobrevivente")
    void numeroSeguidoDeOrdinalPortuguesSobrevive() {
        FallbackTraducaoMaquinaPort porta = porta(o -> ResultadoFallback.recuperada("1º de setembro, Ano Estelar 2149", ProvedorFallback.GOOGLE));

        Map<String, String> r = servico(true, porta).recuperar(conjunto("September 1st, Stellar Year 2149")).recuperadas();

        assertEquals("1º de setembro, Ano Estelar 2149", r.get("September 1st, Stellar Year 2149"),
            "o '1' de '1st' sobrevive dentro de '1º' — a data está corretamente traduzida");
    }

    /**
     * PROPÓSITO DE NEGÓCIO: prova que identificadores numéricos continuam obrigatórios — perder
     * o ano numa legenda de época corromperia a informação, e nenhum afrouxamento da guarda pode
     * permitir isso.
     * <p>INVARIANTES DO DOMÍNIO: token com dígito é sempre exigido, independentemente da lore.
     * <p>COMPORTAMENTO EM CASO DE FALHA: aceitar aqui significa que a regra (c) da guarda sumiu.
     */
    @Test
    @DisplayName("F3: identificador numérico perdido continua recusando (ano some da tradução)")
    void identificadorNumericoPerdidoEhRecusado() {
        FallbackTraducaoMaquinaPort porta = porta(o -> ResultadoFallback.recuperada("12 de maio, Ano Estelar", ProvedorFallback.GOOGLE));

        Map<String, String> r = servico(true, porta).recuperar(conjunto("May 12th, Stellar Year 2148")).recuperadas();

        assertTrue(r.isEmpty(), "identificador numérico perdido deve manter a fala pendente");
    }

    /**
     * PROPÓSITO DE NEGÓCIO: prova que siglas/acrônimos continuam obrigatórios — "MS" (Mobile
     * Suit) traduzido ou apagado descaracterizaria a fala.
     * <p>INVARIANTES DO DOMÍNIO: token em CAIXA ALTA com ≥2 caracteres é sempre exigido.
     * <p>COMPORTAMENTO EM CASO DE FALHA: aceitar aqui significa que a regra (b) da guarda sumiu.
     */
    @Test
    @DisplayName("F3: sigla em caixa alta perdida continua recusando")
    void siglaPerdidaEhRecusada() {
        FallbackTraducaoMaquinaPort porta = porta(o -> ResultadoFallback.recuperada("A unidade foi destruída", ProvedorFallback.GOOGLE));

        Map<String, String> r = servico(true, porta).recuperar(conjunto("The MS unit was destroyed")).recuperadas();

        assertTrue(r.isEmpty(), "sigla perdida deve manter a fala pendente");
    }

    /**
     * PROPÓSITO DE NEGÓCIO: prova que o relatório por CAUSA é preenchido — é ele que transforma
     * "recuperou N de M" em diagnóstico acionável.
     * <p>INVARIANTES DO DOMÍNIO: cada tentativa incrementa exatamente uma causa; a soma cobre
     * todas as falas informadas.
     * <p>COMPORTAMENTO EM CASO DE FALHA: contadores ausentes reprovam.
     */
    @Test
    @DisplayName("F3: contadores por causa cobrem todas as tentativas")
    void contadoresPorCausaSaoPreenchidos() {
        FallbackTraducaoMaquinaPort porta = porta(o -> o.contains("MS")
            ? ResultadoFallback.recuperada("A unidade foi destruída", ProvedorFallback.GOOGLE)
            : ResultadoFallback.recusada(ProvedorFallback.GOOGLE, StatusFallback.HTTP_ERRO, "HTTP 429"));

        var resultado = servico(true, porta)
            .recuperar(conjunto("The MS unit was destroyed", "Hello there"));

        assertEquals(1, resultado.porCausa().get(StatusFallback.GUARDA_LORE),
            "a fala com sigla perdida deve contar como GUARDA_LORE");
        assertEquals(1, resultado.porCausa().get(StatusFallback.HTTP_ERRO),
            "a fala com erro de transporte deve contar como HTTP_ERRO");
        assertTrue(resultado.recuperadas().isEmpty());
        assertTrue(resultado.resumoPorCausa().contains("HTTP_ERRO"),
            "o resumo legível deve listar as causas ocorridas: " + resultado.resumoPorCausa());
    }

    @Test
    @DisplayName("ligado: pontuação isolada encerra a frase; a 1ª palavra da frase seguinte não é nome próprio")
    void pontuacaoIsoladaEncerraFrase() {
        // "London" abre a 2ª frase (após o ponto isolado) e foi legitimamente traduzido para
        // "Londres"; não pode ser tratado como nome próprio obrigatório e reprovar a recuperação.
        FallbackTraducaoMaquinaPort porta = porta(o -> ResultadoFallback.recuperada("Ele saiu . Londres chama", ProvedorFallback.GOOGLE));

        Map<String, String> r = servico(true, porta).recuperar(conjunto("He left . London calls")).recuperadas();

        assertEquals("Ele saiu . Londres chama", r.get("He left . London calls"),
            "palavra inicial da frase após pontuação isolada não é nome próprio obrigatório");
    }

    // ------------------------------------------------------------------------------------
    // Bug 2 (corrida de 2026-07-22): a guarda decompunha termos compostos em palavras soltas,
    // tratava cartela de título como sigla e comparava número sem normalizar. Os originais
    // abaixo são reais, extraídos das 37 recusas GUARDA_LORE daquele log.
    // ------------------------------------------------------------------------------------

    /** Termos compostos reais da lore do 08th MS Team, onde nascia o falso-positivo. */
    private static final Set<String> LORE_08TH = Set.of(
        "Earth Federation", "Principality of Zeon", "One Year War", "08th MS Team",
        "Eledore Massis", "Zeon", "Hovertruck");

    @Test
    @DisplayName("Bug 2: substantivo comum de termo composto NÃO é exigido (one/team/war/Earth/Federation)")
    void substantivoComumDeTermoCompostoNaoEhExigido() {
        FallbackTraducaoMaquinaPort porta = porta(o -> ResultadoFallback.recuperada(
            switch (o) {
                case "{\\i1}I can't just stand by and let one of our guys get killed!{\\i}" ->
                    "{\\i1}Não posso ficar parado e deixar um dos nossos morrer!{\\i}";
                case "{\\i1}The Earth can be a harsh place.{\\i0}" ->
                    "{\\i1}A Terra pode ser um lugar cruel.{\\i0}";
                case "{\\i1}I can't let my team die like dogs!{\\i0}" ->
                    "{\\i1}Não posso deixar minha equipe morrer como cães!{\\i0}";
                case "Two Federation human-types.\\NMinovsky density rising!" ->
                    "Dois humanoides da Federação.\\NDensidade de Minovsky subindo!";
                default -> "traduzido";
            }, ProvedorFallback.GOOGLE));

        Map<String, String> r = servico(true, porta, LORE_08TH).recuperar(conjunto(
            "{\\i1}I can't just stand by and let one of our guys get killed!{\\i}",
            "{\\i1}The Earth can be a harsh place.{\\i0}",
            "{\\i1}I can't let my team die like dogs!{\\i0}",
            "Two Federation human-types.\\NMinovsky density rising!")).recuperadas();

        assertEquals(4, r.size(),
            "exigir 'one', 'Earth', 'team' e 'Federation' em português contradiz a política de "
                + "tradução da própria obra e reprovava traduções corretas; recuperadas: " + r.keySet());
    }

    @Test
    @DisplayName("Bug 2: nome de personagem dentro de termo composto CONTINUA exigido (Eledore)")
    void nomeDePersonagemEmTermoCompostoContinuaExigido() {
        FallbackTraducaoMaquinaPort porta = porta(o ->
            ResultadoFallback.recuperada("Ajude aqueles civis!", ProvedorFallback.GOOGLE));

        var resultado = servico(true, porta, LORE_08TH).recuperar(conjunto("Eledore! Help those civilians out!"));

        assertTrue(resultado.recuperadas().isEmpty(),
            "apagar o nome do personagem deve continuar reprovando a resposta do tradutor de máquina");
        assertEquals(1, resultado.porCausa().get(StatusFallback.GUARDA_LORE));
    }

    @Test
    @DisplayName("Bug 2: cartela 100% em CAIXA ALTA não vira acrônimo obrigatório")
    void cartelaEmCaixaAltaNaoViraAcronimo() {
        FallbackTraducaoMaquinaPort porta = porta(o -> ResultadoFallback.recuperada(
            switch (o) {
                case "{\\blur1\\pos(720,650)\\c&HDEDEE3&}THE WAR OF THE TWO" ->
                    "{\\blur1\\pos(720,650)\\c&HDEDEE3&}A GUERRA DOS DOIS";
                case "{\\blur1\\pos(720,650)\\c&HDEDEE3&}GUNDAMS IN THE JUNGLE" ->
                    "{\\blur1\\pos(720,650)\\c&HDEDEE3&}GUNDAMS NA SELVA";
                default -> "traduzido";
            }, ProvedorFallback.GOOGLE));

        Map<String, String> r = servico(true, porta, LORE_08TH).recuperar(conjunto(
            "{\\blur1\\pos(720,650)\\c&HDEDEE3&}THE WAR OF THE TWO",
            "{\\blur1\\pos(720,650)\\c&HDEDEE3&}GUNDAMS IN THE JUNGLE")).recuperadas();

        assertEquals(2, r.size(),
            "numa linha inteiramente maiúscula a caixa alta é estilo, não sigla: exigir THE/IN/OF "
                + "impedia traduzir qualquer cartela de título; recuperadas: " + r.keySet());
    }

    @Test
    @DisplayName("Bug 2: sigla real continua exigida quando CONTRASTA com a linha")
    void siglaRealContinuaExigidaEmLinhaMista() {
        FallbackTraducaoMaquinaPort porta = porta(o ->
            ResultadoFallback.recuperada("O piloto do robô chegou.", ProvedorFallback.GOOGLE));

        var resultado = servico(true, porta, Set.of()).recuperar(conjunto("The MS pilot has arrived."));

        assertTrue(resultado.recuperadas().isEmpty(), "apagar a sigla MS deve continuar reprovando");
    }

    @Test
    @DisplayName("Bug 2: número reescrito no padrão português sobrevive (9500 -> 9.500, 0.5 -> 0,5)")
    void numeroReescritoNoPadraoPortuguesSobrevive() {
        FallbackTraducaoMaquinaPort porta = porta(o -> ResultadoFallback.recuperada(
            switch (o) {
                case "{\\i1}Currently holding at\\Nan altitude of 9500!" ->
                    "{\\i1}Mantendo agora\\Numa altitude de 9.500!";
                case "{\\i1}Wind speed: 0.5 to 0.6." -> "{\\i1}Velocidade do vento: 0,5 a 0,6.";
                case "{\\an8\\i1}less than 1/30th that of the Earth Federation.{\\i}" ->
                    "{\\an8\\i1}menos de 1/30 da Federação Terrestre.{\\i}";
                default -> "traduzido";
            }, ProvedorFallback.GOOGLE));

        Map<String, String> r = servico(true, porta, LORE_08TH).recuperar(conjunto(
            "{\\i1}Currently holding at\\Nan altitude of 9500!",
            "{\\i1}Wind speed: 0.5 to 0.6.",
            "{\\an8\\i1}less than 1/30th that of the Earth Federation.{\\i}")).recuperadas();

        assertEquals(3, r.size(),
            "separador de milhar, vírgula decimal e queda do ordinal inglês são reescritas "
                + "legítimas do português, não perda do identificador; recuperadas: " + r.keySet());
    }

    @Test
    @DisplayName("Bug 2: número TROCADO continua reprovando (a guarda não virou permissiva)")
    void numeroTrocadoContinuaReprovando() {
        FallbackTraducaoMaquinaPort porta = porta(o ->
            ResultadoFallback.recuperada("Mantendo uma altitude de 8500!", ProvedorFallback.GOOGLE));

        var resultado = servico(true, porta, LORE_08TH)
            .recuperar(conjunto("Currently holding at an altitude of 9500!"));

        assertTrue(resultado.recuperadas().isEmpty(),
            "9500 virando 8500 é alteração de valor, não reescrita de formato");
    }

    @Test
    @DisplayName("Bug 2: identificador não pode ser dado como sobrevivente dentro de outro número")
    void identificadorNaoSobreviveDentroDeOutroNumero() {
        FallbackTraducaoMaquinaPort porta = porta(o ->
            ResultadoFallback.recuperada("Alcance 12500 metros.", ProvedorFallback.GOOGLE));

        var resultado = servico(true, porta, Set.of()).recuperar(conjunto("Range 500 meters."));

        assertTrue(resultado.recuperadas().isEmpty(),
            "500 dentro de 12500 não é sobrevivência: a fronteira de dígito deve valer nas duas pontas");
    }

    private static LinkedHashSet<String> falas(int n) {
        LinkedHashSet<String> s = new LinkedHashSet<>();
        for (int i = 0; i < n; i++) {
            s.add("Line number " + (char) ('A' + i) + " here.");
        }
        return s;
    }

    /** 28/10/2026: com o provedor fora, cada uma das 300 falas pagava o próprio timeout. */
    @Test
    @DisplayName("disjuntor: 3 indisponibilidades seguidas e o resto fica NAO_TENTADA")
    void disjuntorParaDepoisDeTresIndisponibilidades() {
        int[] chamadas = {0};
        FallbackTraducaoMaquinaPort fora = porta(o -> {
            chamadas[0]++;
            return ResultadoFallback.recusada(ProvedorFallback.GOOGLE, StatusFallback.TIMEOUT, "timeout");
        });

        var r = servico(true, fora).recuperar(falas(10));

        assertEquals(3, chamadas[0], "o provedor nao pode ser martelado depois de 3 falhas seguidas");
        assertEquals(3, r.porCausa().get(StatusFallback.TIMEOUT));
        assertEquals(7, r.porCausa().get(StatusFallback.NAO_TENTADA));
    }

    @Test
    @DisplayName("CONTROLE: falha de fala (eco) nao arma o disjuntor")
    void ecoNaoArmaODisjuntor() {
        int[] chamadas = {0};
        FallbackTraducaoMaquinaPort eco = porta(o -> {
            chamadas[0]++;
            return ResultadoFallback.recusada(ProvedorFallback.GOOGLE, StatusFallback.ECO, "eco");
        });

        servico(true, eco).recuperar(falas(6));

        assertEquals(6, chamadas[0], "eco e problema da fala, nao do provedor");
    }

    /** 28/10/2026: o "Sair" seguia fala a fala, e as restantes saíam como provedor indisponível. */
    @Test
    @DisplayName("parada do operador interrompe o laco e o resto fica NAO_TENTADA")
    void paradaInterrompeOLaco() {
        int[] chamadas = {0};
        FallbackTraducaoMaquinaPort quePara = porta(o -> {
            chamadas[0]++;
            Thread.currentThread().interrupt();
            return ResultadoFallback.recusada(ProvedorFallback.GOOGLE, StatusFallback.PROVEDOR_INDISPONIVEL, "Sair");
        });
        try {
            var r = servico(true, quePara).recuperar(falas(5));
            assertEquals(1, chamadas[0], "depois do Sair nenhuma outra fala vai ao provedor");
            assertEquals(4, r.porCausa().get(StatusFallback.NAO_TENTADA));
        } finally {
            Thread.interrupted();
        }
    }

    /** 28/10/2026: "RECUPERADA=5, REPROVADA_VALIDACAO=2" somava 7 para 5 tentativas. */
    @Test
    @DisplayName("relatorio desconta da RECUPERADA a reprovada na validacao final")
    void relatorioDescontaReprovadas() {
        var r = new org.traducao.projeto.traducao.domain.fallback.ResultadoRecuperacao(Map.of(),
            Map.of(StatusFallback.RECUPERADA, 5, StatusFallback.ECO, 1));

        assertEquals("RECUPERADA=3, ECO=1, REPROVADA_VALIDACAO=2", r.comReprovadasNaValidacao(2).resumoPorCausa());
        assertEquals("ECO=1, REPROVADA_VALIDACAO=5", r.comReprovadasNaValidacao(5).resumoPorCausa());
        assertEquals(r.resumoPorCausa(), r.comReprovadasNaValidacao(0).resumoPorCausa(), "CONTROLE");
    }
}
