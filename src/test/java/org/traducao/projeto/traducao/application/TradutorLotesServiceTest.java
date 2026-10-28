package org.traducao.projeto.traducao.application;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.traducao.projeto.llm.domain.Lote;
import org.traducao.projeto.llm.domain.TraducaoLote;
import org.traducao.projeto.qualidadeTraducao.application.MascaradorTags;
import org.traducao.projeto.qualidadeTraducao.application.IsoladorQuebraDialogo;
import org.traducao.projeto.qualidadeTraducao.application.ProtecaoLegendaAssService;
import org.traducao.projeto.traducao.domain.TelemetriaTraducao;
import org.traducao.projeto.traducao.domain.exceptions.TraducaoParcialException;
import org.traducao.projeto.traducao.domain.ports.TelemetriaTraducaoPort;
import org.traducao.projeto.traducao.infrastructure.config.TradutorProperties;
import org.traducao.projeto.traducao.presentation.ui.ConsoleUILogger;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.traducao.projeto.qualidadeTraducao.application.RemovedorItalico;

/**
 * PROPÓSITO DE NEGÓCIO: fixa por regressão o coração do fluxo de tradução em lote do
 * {@link TradutorLotesService} (FASE F, R6) — mascaramento, ordenação em lotes, restauração
 * de tags e o caminho de exceção parcial — sem depender de LM Studio.
 *
 * <p>INVARIANTES DO DOMÍNIO: usa dublês determinísticos ({@link ProcessarEpisodioUseCase},
 * UI, proteção e telemetria) e o {@link MascaradorTags} real; nenhum teste usa rede, sleep ou
 * dependência temporal.
 *
 * <p>COMPORTAMENTO EM CASO DE FALHA: qualquer desvio de ordem, mascaramento, fallback ou
 * reconstrução parcial reprova a suíte.
 */
class TradutorLotesServiceTest {

    /** Dublê do episódio: registra os lotes recebidos, ecoa {@code "T:"+linha} por padrão e pode abortar. */
    private static final class FakeEpisodio extends ProcessarEpisodioUseCase {
        final List<Lote> lotesRecebidos = new ArrayList<>();
        int chamadas = 0;
        TraducaoParcialException aLancar = null;
        Function<Lote, List<String>> tradutor = l -> l.linhasOriginais().stream().map(s -> "T:" + s).toList();
        /** Mascarado enviado → causa de o pipeline ter desistido (e devolvido o original). */
        Function<Lote, Map<String, String>> causas = l -> Map.of();

        FakeEpisodio() { super(null, null, null, null, null, null); }

        @Override
        public List<TraducaoLote> processarEpisodio(List<Lote> lotes, String promptSistemaCongelado) {
            chamadas++;
            lotesRecebidos.addAll(lotes);
            if (aLancar != null) { throw aLancar; }
            List<TraducaoLote> r = new ArrayList<>();
            for (Lote l : lotes) {
                r.add(new TraducaoLote(l.idLote(), tradutor.apply(l), true, null, List.of(), false, causas.apply(l)));
            }
            return r;
        }

        /** A chamada que o serviço faz desde 28/10/2026, com as partes da quebra isolada. */
        @Override
        public List<TraducaoLote> processarEpisodio(List<Lote> lotes, String promptSistemaCongelado,
                Map<String, List<String>> partesIsoladasPorLinha) {
            partesRecebidas.putAll(partesIsoladasPorLinha);
            return processarEpisodio(lotes, promptSistemaCongelado);
        }

        final Map<String, List<String>> partesRecebidas = new java.util.HashMap<>();
    }

    /** Dublê da UI: conta {@code finalizar()} e não constrói a barra de progresso. */
    private static final class FakeUiLogger extends ConsoleUILogger {
        int finalizacoes = 0;
        @Override public synchronized void iniciarLotes(int totalLotes, String nomeEpisodio) { /* no-op */ }
        @Override public synchronized void finalizar() { finalizacoes++; }
    }

    /** Dublê da telemetria: conta alucinações prevenidas. */
    private static final class FakeTelemetria implements TelemetriaTraducaoPort {
        int alucinacoesPrevenidas = 0;
        @Override public void registrarTraducao(TelemetriaTraducao t) { /* não exercitado */ }
        @Override public void registrarAlucinacaoPrevenida() { alucinacoesPrevenidas++; }
        @Override public void registrarRespostaTraducaoRejeitada() { /* não exercitado */ }
        @Override public void registrarFalhaTraducaoRecuperada() { /* não exercitado */ }
        @Override public void registrarFallbackMantido() { /* não exercitado */ }
    }

    /** Dublê da proteção ASS: {@code respostaSuspeita} controlado por campo. */
    private static final class FakeProtecao extends ProtecaoLegendaAssService {
        boolean suspeita = false;
        @Override public boolean respostaSuspeita(String original, String traduzido) { return suspeita; }
    }

    private static TradutorProperties props(int tamanhoLote) {
        return new TradutorProperties("e", "s", "c", tamanhoLote, List.of(), "en", "pt-BR");
    }

    private static LinkedHashSet<String> pendentes(String... itens) {
        return new LinkedHashSet<>(List.of(itens));
    }

    /**
     * PROPÓSITO DE NEGÓCIO: camadas de composição com o MESMO texto visível vão ao LLM UMA vez,
     * mesmo tendo quantidades diferentes de tag de prefixo — e cada uma volta com o prefixo dela.
     *
     * <h2>O caso real</h2>
     * Cartão de data do 86, três camadas na mesma janela para compor halo, sombra e texto:
     * <pre>
     *   {=0}{\an2..}July 30th…   ->  [[TAG0]][[TAG1]]July 30th…   (2 tags)
     *   {\an2..}July 30th…       ->  [[TAG0]]July 30th…           (1 tag)
     *   {=1}{\an2..}July 30th…   ->  [[TAG0]][[TAG1]]July 30th…   (2 tags)
     * </pre>
     * A chave de dedup era o texto MASCARADO — texto E estrutura de tags. As de 2 tags juntavam
     * entre si; a de 1 ia sozinha. Resultado medido no acervo em 05/08/2026: <b>139 grupos, 378
     * falas</b> com o mesmo texto visível recebendo traduções DIFERENTES na mesma execução. E
     * elas renderizam JUNTAS, então a divergência aparece na tela.
     *
     * <h2>Invariantes do domínio</h2>
     * <ul>
     *   <li>UMA chamada ao LLM para as três — é a contagem de linhas do lote que prova.</li>
     *   <li>Cada camada volta com as SUAS tags, na sua quantidade. Nada é remontado.</li>
     *   <li>Só vale para fala cujas tags são todas de PREFIXO. Com tag no MEIO a chave continua
     *       a mascarada, porque ali a tradução de uma camada não tem onde reencaixar os
     *       marcadores da outra.</li>
     *   <li>Só vale para o subconjunto DEDUPLICÁVEL — diálogo nunca deduplica.</li>
     * </ul>
     *
     * <h2>Comportamento em caso de falha</h2>
     * Volta a divergência na tela: a mesma legenda com duas redações sobrepostas.
     */
    @Test
    void camadasDeCompositoComPrefixosDiferentesTraduzemUmaVezSo() throws Exception {
        String c1 = "{=0}{\\an2}July 30th, Stellar Year 2149";
        String c2 = "{\\an2}July 30th, Stellar Year 2149";
        String c3 = "{=1}{\\an2}July 30th, Stellar Year 2149";
        FakeEpisodio ep = new FakeEpisodio();
        // O dublê padrão devolve "T:" + mascarado, pondo texto ANTES dos marcadores — coisa que
        // nenhum LLM real faz, e que a guarda de reaplicação corretamente recusa. Aqui ele traduz
        // como o modelo traduz: marcadores onde estavam, texto visível trocado.
        ep.tradutor = l -> l.linhasOriginais().stream()
            .map(s -> s.replace("July 30th, Stellar Year 2149", "30 de julho, Ano Estelar 2149"))
            .toList();
        TradutorLotesService s = servico(props(20), ep, new FakeUiLogger(),
            new FakeProtecao(), new FakeTelemetria());

        Map<String, String> r = s.traduzirPendentes(pendentes(c1, c2, c3),
            Set.of(c1, c2, c3), "ep.ass", new ArrayList<>(), null);

        long linhasEnviadas = ep.lotesRecebidos.stream()
            .mapToLong(l -> l.linhasOriginais().size()).sum();
        assertEquals(1, linhasEnviadas,
            () -> "as tres camadas sao o MESMO texto visivel: uma chamada basta. Enviadas: "
                + ep.lotesRecebidos.stream().map(l -> l.linhasOriginais().toString()).toList());

        assertEquals(3, r.size(), "as tres camadas tem de receber traducao");
        assertTrue(r.get(c1).startsWith("{=0}{\\an2}"), () -> "camada 1 perdeu o prefixo: " + r.get(c1));
        assertTrue(r.get(c2).startsWith("{\\an2}"), () -> "camada 2 perdeu o prefixo: " + r.get(c2));
        assertTrue(r.get(c3).startsWith("{=1}{\\an2}"), () -> "camada 3 perdeu o prefixo: " + r.get(c3));

        String visivel1 = r.get(c1).replaceAll("^(\\{[^}]*})+", "");
        String visivel2 = r.get(c2).replaceAll("^(\\{[^}]*})+", "");
        String visivel3 = r.get(c3).replaceAll("^(\\{[^}]*})+", "");
        assertEquals(visivel1, visivel2, "as camadas nao podem divergir no texto");
        assertEquals(visivel1, visivel3, "as camadas nao podem divergir no texto");
    }

    /**
     * PROPÓSITO DE NEGÓCIO: com tag no MEIO, a dedup por texto visível NÃO se aplica — a chave
     * continua sendo o mascarado, e camadas de estrutura diferente vão ao LLM separadamente.
     *
     * <p>INVARIANTES DO DOMÍNIO: ali a tradução de uma camada não tem onde reencaixar os
     * marcadores da outra; juntar produziria fala sem as tags do meio. É o limite declarado do
     * conserto: dos 139 grupos divergentes medidos, 122 são só-prefixo e 17 não.
     *
     * <p>COMPORTAMENTO EM CASO DE FALHA: tag do meio some da legenda, ou o marcador vaza no texto.
     */
    @Test
    void tagNoMeioNaoDeduplicaPorTextoVisivel() throws Exception {
        String comTagNoMeio = "Vamos {\\i1}agora{\\i0} mesmo";
        String semTagNoMeio = "{\\an8}Vamos agora mesmo";
        FakeEpisodio ep = new FakeEpisodio();
        TradutorLotesService s = servico(props(20), ep, new FakeUiLogger(),
            new FakeProtecao(), new FakeTelemetria());

        s.traduzirPendentes(pendentes(comTagNoMeio, semTagNoMeio),
            Set.of(comTagNoMeio, semTagNoMeio), "ep.ass", new ArrayList<>(), null);

        long linhasEnviadas = ep.lotesRecebidos.stream()
            .mapToLong(l -> l.linhasOriginais().size()).sum();
        assertEquals(2, linhasEnviadas,
            "fala com tag no MEIO nao pode deduplicar com fala de estrutura diferente");
    }

    /**
     * PROPÓSITO DE NEGÓCIO: a fala de dois locutores vai ao modelo como dois textos comuns e volta
     * montada com o travessão de cada um e a quebra entre eles. No teste ponta a ponta de
     * 09/10/2026 ela foi a maior causa de fala em inglês: o modelo recebia "- This way. - Right."
     * como uma linha e devolvia duas.
     * <p>INVARIANTES DO DOMÍNIO: o modelo nunca recebe travessão nem quebra desses trechos; a fala
     * comum ao lado segue como sempre.
     * <p>COMPORTAMENTO EM CASO DE FALHA: trecho enviado com travessão, ou fala montada torta, reprova.
     */
    @Test
    void falaDeLocutoresVaiPorTrechoEVoltaMontada() throws Exception {
        String locutores = "- This way.\\N- Right.";
        Map<String, String> dicionario = Map.of("This way.", "Por aqui.", "Right.", "- Certo.", "Alone.", "Sozinho.");
        FakeEpisodio ep = new FakeEpisodio();
        ep.tradutor = l -> l.linhasOriginais().stream().map(dicionario::get).toList();
        TradutorLotesService s = servico(props(1), ep, new FakeUiLogger(), new FakeProtecao(), new FakeTelemetria());

        Map<String, String> r = s.traduzirPendentes(pendentes(locutores, "Alone."), Set.of(), Set.of(locutores),
            "ep.srt", new ArrayList<>(), null);

        List<String> enviados = ep.lotesRecebidos.stream().flatMap(l -> l.linhasOriginais().stream()).toList();
        assertEquals(List.of("This way.", "Right.", "Alone."), enviados);
        assertEquals("- Por aqui.\\N- Certo.", r.get(locutores));
        assertEquals("Sozinho.", r.get("Alone."));
        assertEquals(2, r.size(), "os trechos nao podem vazar como falas no mapa devolvido: " + r.keySet());
    }

    /**
     * PROPÓSITO DE NEGÓCIO: quando o pipeline desiste de UM trecho, ele devolve o inglês daquele
     * trecho; montar com isso publicaria a fala meio em inglês, que o portão final não pega porque a
     * linha inteira não é idêntica. A fala inteira fica pendente, com a causa do trecho dentro.
     * <p>INVARIANTES DO DOMÍNIO: a causa registrada nomeia o trecho e carrega a causa original
     * (o classificador de pendência lê esse texto).
     * <p>COMPORTAMENTO EM CASO DE FALHA: fala meio traduzida no mapa, ou pendência sem causa, reprova.
     */
    @Test
    void trechoAbandonadoDeixaAFalaInteiraPendenteComCausa() throws Exception {
        String locutores = "- This way.\\N- Right.";
        FakeEpisodio ep = new FakeEpisodio();
        ep.tradutor = l -> l.linhasOriginais().stream().map(t -> t.equals("Right.") ? "Right." : "Por aqui.").toList();
        ep.causas = l -> l.linhasOriginais().contains("Right.")
            ? Map.of("Right.", "Lote 2 retornou 2 linha(s), esperado 1") : Map.of();
        TradutorLotesService s = servico(props(1), ep, new FakeUiLogger(), new FakeProtecao(), new FakeTelemetria());
        DesfechoDasFalas desfecho = new DesfechoDasFalas();

        Map<String, String> r = s.traduzirPendentes(pendentes(locutores), Set.of(), Set.of(locutores),
            "ep.srt", new ArrayList<>(), null, desfecho);

        assertNull(r.get(locutores), "fala meio em ingles nao pode ser publicada: " + r.get(locutores));
        String causa = desfecho.causaDoOriginalMantido().get(locutores);
        assertTrue(causa != null && causa.contains("\"Right.\"") && causa.contains("linha(s), esperado"),
            "a causa tem de nomear o trecho e carregar a causa dele: " + causa);
    }

    /**
     * 28/10/2026: o trecho que caía em marcador corrompido voltava como o ORIGINAL inglês sem
     * causa registrada, e a fala era remontada meio em inglês ("- Vá até lá.\N- Sorry.").
     */
    @Test
    void trechoComMarcadorCorrompidoDeixaAFalaPendente() throws Exception {
        String locutores = "- Go there.\\N- Sorry.";
        FakeEpisodio ep = new FakeEpisodio();
        ep.tradutor = l -> l.linhasOriginais().stream()
            .map(t -> t.equals("Sorry.") ? "Desculpe.[[TAG0]]" : "Vá até lá.").toList();
        TradutorLotesService s = servico(props(1), ep, new FakeUiLogger(), new FakeProtecao(), new FakeTelemetria());
        DesfechoDasFalas desfecho = new DesfechoDasFalas();

        Map<String, String> r = s.traduzirPendentes(pendentes(locutores), Set.of(), Set.of(locutores),
            "ep.srt", new ArrayList<>(), null, desfecho);

        assertNull(r.get(locutores), "fala meio em ingles nao pode ser publicada: " + r.get(locutores));
        String causa = desfecho.causaDoOriginalMantido().get(locutores);
        assertTrue(causa != null && causa.contains("\"Sorry.\"") && causa.contains("marcadores"),
            "a causa nomeia o trecho e diz que foi marcador corrompido: " + causa);
    }

    private TradutorLotesService servico(TradutorProperties props, FakeEpisodio ep,
                                         FakeUiLogger ui, FakeProtecao protecao, FakeTelemetria telemetria) {
        return new TradutorLotesService(new MascaradorTags(), props, ui, ep, protecao, telemetria,
            new IsoladorQuebraDialogo(), new RemovedorItalico(), new SimplificadorItalicoRedundante(),
            new DescarteItalicoUltimoRecurso(), new DetectorCorrenteFrasePartida(),
            new GuardaCorrenteTraduzida());
    }

    /**
     * PROPÓSITO DE NEGÓCIO: sem falas pendentes não há chamada ao LLM.
     * <p>INVARIANTES DO DOMÍNIO: conjunto vazio devolve mapa vazio e não invoca o episódio.
     * <p>COMPORTAMENTO EM CASO DE FALHA: qualquer chamada ao episódio reprova.
     */
    @Test
    void conjuntoVazioRetornaMapaVazioSemChamarEpisodio() throws Exception {
        FakeEpisodio ep = new FakeEpisodio();
        TradutorLotesService s = servico(props(20), ep, new FakeUiLogger(), new FakeProtecao(), new FakeTelemetria());

        Map<String, String> r = s.traduzirPendentes(pendentes(), Set.of(), "ep.ass", new ArrayList<>(), null);

        assertTrue(r.isEmpty());
        assertEquals(0, ep.chamadas, "episódio não pode ser chamado sem pendências");
    }

    /**
     * PROPÓSITO DE NEGÓCIO: cada fala mapeia para a sua própria tradução, mesmo distribuída em
     * múltiplos lotes.
     * <p>INVARIANTES DO DOMÍNIO: com tamanho de lote 2 e três falas, a ordem posição↔tradução
     * é preservada entre os lotes.
     * <p>COMPORTAMENTO EM CASO DE FALHA: qualquer troca de mapeamento reprova.
     */
    @Test
    void ordemOriginalDasFalasEhPreservada() throws Exception {
        FakeEpisodio ep = new FakeEpisodio();
        TradutorLotesService s = servico(props(2), ep, new FakeUiLogger(), new FakeProtecao(), new FakeTelemetria());

        Map<String, String> r = s.traduzirPendentes(pendentes("A", "B", "C"), Set.of(), "ep.ass", new ArrayList<>(), null);

        assertEquals("T:A", r.get("A"));
        assertEquals("T:B", r.get("B"));
        assertEquals("T:C", r.get("C"));
        assertEquals(2, ep.lotesRecebidos.size(), "3 falas com tamanho de lote 2 geram 2 lotes");
    }

    /**
     * PROPÓSITO DE NEGÓCIO: as tags de formatação são mascaradas antes do LLM e restauradas na
     * resposta — o modelo nunca vê a sintaxe de estilo.
     * <p>INVARIANTES DO DOMÍNIO: o lote enviado traz {@code [[TAG0]]} no lugar da tag; a saída
     * recupera a tag original.
     * <p>COMPORTAMENTO EM CASO DE FALHA: tag vista pelo LLM ou não restaurada reprova.
     */
    @Test
    void tagsSaoMascaradasAntesDoLlmERestauradasNaResposta() throws Exception {
        FakeEpisodio ep = new FakeEpisodio();
        ep.tradutor = l -> l.linhasOriginais().stream().map(s -> s.replace("Oi mundo", "Ola mundo")).toList();
        TradutorLotesService s = servico(props(20), ep, new FakeUiLogger(), new FakeProtecao(), new FakeTelemetria());

        Map<String, String> r = s.traduzirPendentes(pendentes("{\\b1}Oi mundo"), Set.of(), "ep.ass", new ArrayList<>(), null);

        assertEquals("[[TAG0]]Oi mundo", ep.lotesRecebidos.get(0).linhasOriginais().get(0),
            "o LLM deve receber o texto mascarado");
        assertEquals("{\\b1}Ola mundo", r.get("{\\b1}Oi mundo"),
            "a resposta deve ter a tag restaurada");
    }

    /**
     * PROPÓSITO DE NEGÓCIO: com {@code tradutor.texto-puro-ao-llm} LIGADA, a fala cujas tags estão
     * todas na borda viaja SEM marcador nenhum e volta vestida com a moldura original. É o par
     * exato de {@link #tagsSaoMascaradasAntesDoLlmERestauradasNaResposta()}, sobre a MESMA
     * entrada — os dois juntos mostram os dois contratos lado a lado.
     *
     * <h2>O prejuízo que originou</h2>
     * O marcador é a causa isolada das falas perdidas: 393 de 412 (95%) em 2026-07-22, e 269 de
     * 269 no corretor de karaokê em 07/08/2026. Onde ele deixou de viajar, a taxa de sucesso foi
     * de 34% para 100%.
     *
     * <p>COMPORTAMENTO EM CASO DE FALHA: se o LLM passar a receber {@code [[TAG0]]} com a flag
     * ligada, ou se a moldura não voltar, este teste reprova.
     */
    @Test
    void comFlagLigadaOTextoVaiPuroAoLlmEAMolduraVolta() throws Exception {
        FakeEpisodio ep = new FakeEpisodio();
        ep.tradutor = l -> l.linhasOriginais().stream().map(s -> s.replace("Oi mundo", "Ola mundo")).toList();
        TradutorProperties p = props(20);
        p.setTextoPuroAoLlm(true);
        TradutorLotesService s = servico(p, ep, new FakeUiLogger(), new FakeProtecao(), new FakeTelemetria());

        Map<String, String> r = s.traduzirPendentes(pendentes("{\\b1}Oi mundo"), Set.of(), "ep.ass", new ArrayList<>(), null);

        assertEquals("Oi mundo", ep.lotesRecebidos.get(0).linhasOriginais().get(0),
            "com a flag ligada o LLM recebe a frase PURA — nenhum [[TAGn]] para ele perder");
        assertEquals("{\\b1}Ola mundo", r.get("{\\b1}Oi mundo"),
            "a moldura tem de voltar literal, sem depender de o modelo ter repetido marcador");
    }

    /**
     * PROPÓSITO DE NEGÓCIO: CONTRA-TESTE do recorte. Com a flag ligada, a fala de tag NO MEIO
     * continua pelo caminho de mascaramento — ali a tag marca uma palavra específica, e recolocá-la
     * exigiria alinhamento palavra a palavra entre inglês e português.
     */
    @Test
    void comFlagLigadaTagNoMeioSegueMascarada() throws Exception {
        FakeEpisodio ep = new FakeEpisodio();
        ep.tradutor = l -> l.linhasOriginais();
        TradutorProperties p = props(20);
        p.setTextoPuroAoLlm(true);
        TradutorLotesService s = servico(p, ep, new FakeUiLogger(), new FakeProtecao(), new FakeTelemetria());

        s.traduzirPendentes(pendentes("Eu {\\b1}nunca{\\b0} vou"), Set.of(), "ep.ass", new ArrayList<>(), null);

        assertTrue(ep.lotesRecebidos.get(0).linhasOriginais().get(0).contains("[[TAG"),
            "tag no MEIO fica fora do recorte e continua mascarada, mesmo com a flag ligada");
    }

    /**
     * PROPÓSITO DE NEGÓCIO: alucinação de tags (marcador perdido) mantém só aquela fala original,
     * registra aviso e contabiliza na telemetria — sem derrubar o restante.
     * <p>INVARIANTES DO DOMÍNIO: o texto sem marcador reprova o desmascaramento e cai no fallback.
     * <p>COMPORTAMENTO EM CASO DE FALHA: fala traduzida à força, aviso ausente ou telemetria não
     * incrementada reprova.
     */
    @Test
    void alucinacaoDeTagsMantemFalaOriginalRegistraAvisoETelemetria() throws Exception {
        FakeEpisodio ep = new FakeEpisodio();
        ep.tradutor = l -> l.linhasOriginais().stream().map(s -> "Ola").toList();
        FakeTelemetria tele = new FakeTelemetria();
        List<String> avisos = new ArrayList<>();
        TradutorLotesService s = servico(props(20), ep, new FakeUiLogger(), new FakeProtecao(), tele);

        // Tag de POSIÇÃO: continua valendo a regra histórica — mover a legenda na tela não é
        // detalhe de ênfase, então o original é mantido e a alucinação é contabilizada.
        Map<String, String> r = s.traduzirPendentes(
            pendentes("{\\pos(10,20)}Oi"), Set.of(), "ep.ass", avisos, null);

        assertEquals("{\\pos(10,20)}Oi", r.get("{\\pos(10,20)}Oi"),
            "tag não-itálica corrompida mantém o original");
        assertEquals(1, avisos.size());
        assertEquals(1, tele.alucinacoesPrevenidas);
    }

    /**
     * PROPÓSITO DE NEGÓCIO: a fala de diálogo chega ao LLM SEM itálico e volta SEM itálico.
     * Decisão do Paulo em 2026-08-22: "num filme normal não tem itálico, é frescura".
     *
     * <p>INVARIANTES DO DOMÍNIO: o efeito não é só estético. O par de ênfase no MEIO da frase
     * é o caso que o mascaramento não consegue honrar — ele garante que a tag volte, não que
     * ela continue cercando a palavra realçada, e o português reordena. Sem o par, some o
     * {@code {\\i1}{\\i0}} vazio que aparecia na legenda entregue.
     *
     * <p>COMPORTAMENTO EM CASO DE FALHA: itálico visto pelo LLM, ou devolvido na tradução,
     * reprova. Tag que NÃO é itálico no mesmo bloco tem de sobreviver — {@code {\\q2\\i1}}
     * são 340 blocos do acervo, e apagar o bloco inteiro destruiria a quebra automática.
     */
    @Test
    @DisplayName("Itálico eliminado: não vai ao LLM e não volta na tradução")
    void italicoEliminadoAntesDoLlmENaoVoltaNaTraducao() throws Exception {
        FakeEpisodio ep = new FakeEpisodio();
        ep.tradutor = l -> l.linhasOriginais().stream()
            .map(t -> t.replace("no love in", "não há amor neste")).toList();
        TradutorLotesService s = servico(props(20), ep, new FakeUiLogger(), new FakeProtecao(),
            new FakeTelemetria());

        String fala = "there's {\\i1}no love in{\\i0} this kind";
        Map<String, String> r = s.traduzirPendentes(
            pendentes(fala), Set.of(), "ep.ass", new ArrayList<>(), null);

        assertEquals("there's no love in this kind",
            ep.lotesRecebidos.get(0).linhasOriginais().get(0),
            "o LLM recebe a frase INGLESA limpa: sem itálico e sem [[TAGn]] para perder");
        assertEquals("there's não há amor neste this kind", r.get(fala),
            "a tradução entregue não pode ter itálico de volta");
    }

    /**
     * PROPÓSITO DE NEGÓCIO: CONTRA-CASO. O bloco MISTO perde só o itálico; o resto do bloco
     * é formatação real que a tela precisa.
     *
     * <p>COMPORTAMENTO EM CASO DE FALHA: se o bloco inteiro sumir, a fala perde quebra
     * automática, posição ou cor — e o teste reprova.
     */
    @Test
    @DisplayName("Bloco misto: sai o itálico, fica o resto")
    void blocoMistoPerdeSoOItalicoNoFluxoDeTraducao() throws Exception {
        FakeEpisodio ep = new FakeEpisodio();
        ep.tradutor = l -> l.linhasOriginais().stream()
            .map(t -> t.replace("Hello", "Ola")).toList();
        TradutorLotesService s = servico(props(20), ep, new FakeUiLogger(), new FakeProtecao(),
            new FakeTelemetria());

        String fala = "{\\q2\\i1}Hello";
        Map<String, String> r = s.traduzirPendentes(
            pendentes(fala), Set.of(), "ep.ass", new ArrayList<>(), null);

        assertEquals("{\\q2}Ola", r.get(fala),
            "o \\q2 (quebra automática) tem de sobreviver à remoção do itálico");
    }

    /**
     * PROPÓSITO DE NEGÓCIO: congela a INVERSÃO de prioridade decidida pelo Paulo em
     * 2026-07-31 — quando as únicas tags perdidas são itálico, publicar a tradução sem
     * ênfase é melhor que publicar o original em inglês com ênfase.
     *
     * <p>Medido nos logs: 47 das 212 corrupções estão nesta condição, e hoje todas saem
     * em inglês. Ver {@link DescarteItalicoUltimoRecurso}.
     *
     * <p><b>Por que a fala entra aqui como DEDUPLICÁVEL desde 2026-08-22:</b> a eliminação do
     * itálico ({@link RemovedorItalico}) tirou este caso do caminho de diálogo — lá não chega
     * mais itálico nenhum para o modelo corromper. A camada musical é a ÚNICA exceção da regra,
     * porque música não se toca nesta frente. Logo este teste virou também o CASO-CONTROLE do
     * veto: se alguém remover a exceção e passar o removedor por cima da música, a fala chega
     * sem {@code {\\i1}}, o descarte não acontece, e este teste reprova.
     */
    @Test
    @DisplayName("Itálico corrompido: salva a TRADUÇÃO sem ênfase em vez do inglês")
    void alucinacaoDeItalicoSalvaTraducaoSemEnfase() throws Exception {
        FakeEpisodio ep = new FakeEpisodio();
        ep.tradutor = l -> l.linhasOriginais().stream().map(s -> "Ola").toList();
        FakeTelemetria tele = new FakeTelemetria();
        List<String> avisos = new ArrayList<>();
        TradutorLotesService s = servico(props(20), ep, new FakeUiLogger(), new FakeProtecao(), tele);

        Map<String, String> r = s.traduzirPendentes(
            pendentes("{\\i1}Oi"), Set.of("{\\i1}Oi"), "ep.ass", avisos, null);

        assertEquals("Ola", r.get("{\\i1}Oi"),
            "itálico puro: a tradução vence a formatação");
        assertEquals(1, avisos.size(), "o descarte é registrado para auditoria");
        assertEquals(0, tele.alucinacoesPrevenidas,
            "não é alucinação prevenida: a fala foi SALVA, não descartada");
    }

    /**
     * PROPÓSITO DE NEGÓCIO: resposta suspeita numa linha ASS pesada mantém o original, mesmo com
     * desmascaramento bem-sucedido.
     * <p>INVARIANTES DO DOMÍNIO: proteção suspeita → original preservado + aviso + telemetria.
     * <p>COMPORTAMENTO EM CASO DE FALHA: publicar a resposta suspeita reprova.
     */
    @Test
    void respostaSuspeitaDeLinhaAssPesadaMantemOriginal() throws Exception {
        FakeEpisodio ep = new FakeEpisodio();
        FakeProtecao protecao = new FakeProtecao();
        protecao.suspeita = true;
        FakeTelemetria tele = new FakeTelemetria();
        List<String> avisos = new ArrayList<>();
        TradutorLotesService s = servico(props(20), ep, new FakeUiLogger(), protecao, tele);

        Map<String, String> r = s.traduzirPendentes(pendentes("Oi"), Set.of(), "ep.ass", avisos, null);

        assertEquals("Oi", r.get("Oi"), "resposta suspeita mantém o original");
        assertEquals(1, avisos.size());
        assertEquals(1, tele.alucinacoesPrevenidas);
    }

    /**
     * PROPÓSITO DE NEGÓCIO: um cancelamento no meio propaga TraducaoParcialException reconstruída
     * com o dicionário parcial já DESMASCARADO, e a barra de progresso é finalizada mesmo na falha.
     * <p>INVARIANTES DO DOMÍNIO: o dicionário reconstruído mapeia original→desmascarado; a exceção
     * de nível de arquivo não carrega lotes; {@code finalizar()} roda no bloco finally.
     * <p>COMPORTAMENTO EM CASO DE FALHA: dicionário mascarado, lotes preservados ou barra não
     * finalizada reprova.
     */
    /**
     * PROPÓSITO DE NEGÓCIO: o mesmo verso de música (OP/ED) aparece em muitas camadas
     * KFX/clip com tags diferentes mas o MESMO texto mascarado; o LLM deve traduzi-lo
     * uma unica vez e a traducao ser reaplicada a cada camada com suas proprias tags.
     * <p>INVARIANTES DO DOMÍNIO: dedup pelo texto mascarado (nao pelo visivel); cada
     * camada preserva suas tags; nenhuma traducao muda.
     * <p>COMPORTAMENTO EM CASO DE FALHA: mais de uma chamada ao LLM ou tags trocadas reprova.
     */
    @Test
    void dedupMusicaTraduzMascaradoUmaVezEAplicaTagsPorCamada() throws Exception {
        FakeEpisodio ep = new FakeEpisodio();
        TradutorLotesService s = servico(props(20), ep, new FakeUiLogger(), new FakeProtecao(), new FakeTelemetria());
        LinkedHashSet<String> pend = pendentes("{\\clip(0,10)}A flower", "{\\clip(0,20)}A flower");
        Set<String> dedup = Set.of("{\\clip(0,10)}A flower", "{\\clip(0,20)}A flower");

        Map<String, String> r = s.traduzirPendentes(pend, dedup, "ep.ass", new ArrayList<>(), null);

        int linhasEnviadas = ep.lotesRecebidos.stream().mapToInt(l -> l.linhasOriginais().size()).sum();
        assertEquals(1, linhasEnviadas, "o mesmo texto mascarado deve ir ao LLM uma unica vez");
        assertEquals("T:{\\clip(0,10)}A flower", r.get("{\\clip(0,10)}A flower"), "camada 1 com suas tags");
        assertEquals("T:{\\clip(0,20)}A flower", r.get("{\\clip(0,20)}A flower"), "camada 2 com suas tags");
    }

    /**
     * PROPÓSITO DE NEGÓCIO: verso de música com {@code \N} no MEIO da frase deixa de mandar um
     * marcador no meio da tradução. Um {@code [[TAGn]]} mid-sentence é o que o LLM mais erra —
     * o português reordena as palavras e o marcador se perde, derrubando a fala inteira.
     *
     * <p>CASO REAL (Break Blade filme 1, 2026-07-23): das 34 falas, 6 viraram pendência por
     * marcador corrompido, TODAS verso de música e cartela com tag no meio, como
     * {@code {\be1\fad(0,200)\pos(502.5,930)}because I never make\Npromises I cannot keep}. A
     * isolação da quebra já existia, mas era exclusiva do diálogo.
     *
     * <p>INVARIANTES DO DOMÍNIO: o texto que chega ao LLM não tem marcador no meio; a fala
     * traduzida volta com a MESMA quantidade de quebras que tinha.
     *
     * <p>COMPORTAMENTO EM CASO DE FALHA: se o mascarado voltar a levar marcador mid-sentence,
     * a música volta a virar pendência a cada execução.
     */
    @Test
    void musicaComQuebraNoMeioNaoMandaMarcadorMidSentenceAoLlm() throws Exception {
        FakeEpisodio ep = new FakeEpisodio();
        TradutorLotesService s = servico(props(20), ep, new FakeUiLogger(), new FakeProtecao(), new FakeTelemetria());
        String verso = "{\\be1\\fad(0,200)}because I never make\\Npromises I cannot keep";
        LinkedHashSet<String> pend = pendentes(verso);

        Map<String, String> r = s.traduzirPendentes(
            pend, Set.of(verso), Set.of(verso), "ep.ass", new ArrayList<>(), null);

        String enviado = ep.lotesRecebidos.get(0).linhasOriginais().get(0);
        assertFalse(enviado.matches("(?s).*\\p{L}.*\\[\\[TAG\\d+]].*\\p{L}.*"),
            "nao pode haver marcador ENTRE trechos de texto visivel: " + enviado);
        assertTrue(r.get(verso).contains("\\N"),
            "a quebra visual precisa voltar na traducao: " + r.get(verso));
    }

    /**
     * PROPÓSITO DE NEGÓCIO: KFX e romaji ficam FORA da isolação — ali a quebra acompanha o
     * tempo do efeito, não é decoração visual, e mexer nela desalinharia o karaokê.
     * <p>INVARIANTES DO DOMÍNIO: fala ausente do inventário segue o caminho de mascaramento.
     * <p>COMPORTAMENTO EM CASO DE FALHA: isolar quebra de KFX corrompe o sincronismo.
     */
    @Test
    void falaForaDoInventarioMantemAQuebraMascarada() throws Exception {
        FakeEpisodio ep = new FakeEpisodio();
        TradutorLotesService s = servico(props(20), ep, new FakeUiLogger(), new FakeProtecao(), new FakeTelemetria());
        String kfx = "{\\k30}fu\\Nmi";
        LinkedHashSet<String> pend = pendentes(kfx);

        s.traduzirPendentes(pend, Set.of(kfx), Set.of(), "ep.ass", new ArrayList<>(), null);

        String enviado = ep.lotesRecebidos.get(0).linhasOriginais().get(0);
        assertTrue(enviado.contains("[[TAG"),
            "sem estar no inventario, a quebra segue mascarada: " + enviado);
    }

    /**
     * PROPÓSITO DE NEGÓCIO: fora do subconjunto deduplicavel (ex.: diálogo), cada fala
     * vai ao LLM separadamente — o dedup nunca vaza para o diálogo.
     * <p>INVARIANTES DO DOMÍNIO: {@code textosDeduplicaveis} vazio mantém o comportamento antigo.
     * <p>COMPORTAMENTO EM CASO DE FALHA: deduplicar fora do subconjunto reprova.
     */
    @Test
    void semDedupCadaCamadaVaiSeparadaAoLlm() throws Exception {
        FakeEpisodio ep = new FakeEpisodio();
        TradutorLotesService s = servico(props(20), ep, new FakeUiLogger(), new FakeProtecao(), new FakeTelemetria());
        LinkedHashSet<String> pend = pendentes("{\\clip(0,10)}A flower", "{\\clip(0,20)}A flower");

        Map<String, String> r = s.traduzirPendentes(pend, Set.of(), "ep.ass", new ArrayList<>(), null);

        int linhasEnviadas = ep.lotesRecebidos.stream().mapToInt(l -> l.linhasOriginais().size()).sum();
        assertEquals(2, linhasEnviadas, "sem dedup, as duas camadas vao ao LLM");
        assertEquals("T:{\\clip(0,10)}A flower", r.get("{\\clip(0,10)}A flower"));
        assertEquals("T:{\\clip(0,20)}A flower", r.get("{\\clip(0,20)}A flower"));
    }

    /**
     * PROPÓSITO DE NEGÓCIO: um cancelamento no meio propaga TraducaoParcialException reconstruída
     * com o dicionário parcial já DESMASCARADO, e a barra de progresso é finalizada mesmo na falha.
     * <p>INVARIANTES DO DOMÍNIO: o dicionário reconstruído mapeia original→desmascarado; a exceção
     * de nível de arquivo não carrega lotes; {@code finalizar()} roda no bloco finally.
     * <p>COMPORTAMENTO EM CASO DE FALHA: dicionário mascarado, lotes preservados ou barra não
     * finalizada reprova.
     */
    /**
     * PROPÓSITO DE NEGÓCIO (rede de segurança do módulo de isolamento de \N): uma fala de
     * diálogo com quebra {@code \N} NO MEIO da frase — hoje mascarada como {@code [[TAGn]]}
     * mid-sentence — não pode mais virar pendente só porque o LLM não reposiciona o marcador
     * na ordem PT. Isolando o {@code \N} antes de mascarar, o modelo traduz a frase limpa e a
     * quebra é reinserida depois.
     * <p>INVARIANTES DO DOMÍNIO: LLM que "traduz" mas dropa o marcador mid-sentence (falha real
     * observada nas 122 falas-eco) deve resultar em TRADUÇÃO com {@code \N} reinserido, não no
     * original mantido; só diálogo (fora de {@code textosDeduplicaveis}).
     * <p>COMPORTAMENTO EM CASO DE FALHA (antes do fix): o desmascaramento reprova por marcador
     * perdido e a fala é mantida em inglês — este teste falha, caracterizando o bug.
     */
    @Test
    void dialogoComQuebraNoMeioEhTraduzidoAoInvesDeManterOriginal() throws Exception {
        FakeEpisodio ep = new FakeEpisodio();
        // Simula o LLM que traduz o texto visível mas descarta o marcador mid-sentence.
        ep.tradutor = l -> l.linhasOriginais().stream()
            .map(s -> "PT:" + s.replaceAll("\\[\\[TAG\\d+]]", "")).toList();
        TradutorLotesService s = servico(props(20), ep, new FakeUiLogger(), new FakeProtecao(), new FakeTelemetria());

        String original = "Why do we have to put up \\Nwith this";
        Map<String, String> r = s.traduzirPendentes(pendentes(original), Set.of(), "ep.ass", new ArrayList<>(), null);

        String traduzido = r.get(original);
        assertTrue(traduzido.startsWith("PT:"),
            "a fala de diálogo com \\N no meio deve ser TRADUZIDA, não mantida em inglês: " + traduzido);
        assertTrue(traduzido.contains("\\N"),
            "a quebra de linha \\N deve ser reinserida na tradução: " + traduzido);
    }

    /**
     * PROPÓSITO DE NEGÓCIO: o módulo de isolamento é gated a diálogo e a {@code \N} mid-sentence;
     * uma camada musical deduplicável com {@code \N} NÃO é tocada (segue mascarada como hoje).
     * <p>INVARIANTES DO DOMÍNIO: fala em {@code textosDeduplicaveis} preserva o comportamento antigo.
     * <p>COMPORTAMENTO EM CASO DE FALHA: isolar \N fora do diálogo reprova.
     */
    @Test
    void quebraEmCamadaMusicalDeduplicavelNaoEhIsolada() throws Exception {
        FakeEpisodio ep = new FakeEpisodio();
        TradutorLotesService s = servico(props(20), ep, new FakeUiLogger(), new FakeProtecao(), new FakeTelemetria());
        String verso = "A flower \\Nblooms";
        LinkedHashSet<String> pend = pendentes(verso);
        Set<String> dedup = Set.of(verso);

        s.traduzirPendentes(pend, dedup, "ep.ass", new ArrayList<>(), null);

        String enviadoAoLlm = ep.lotesRecebidos.get(0).linhasOriginais().get(0);
        assertTrue(enviadoAoLlm.contains("[[TAG0]]"),
            "camada musical deduplicável deve manter o \\N mascarado como marcador: " + enviadoAoLlm);
    }

    /**
     * PROPÓSITO DE NEGÓCIO: um cancelamento no meio propaga TraducaoParcialException reconstruída
     * com o dicionário parcial já DESMASCARADO, e a barra de progresso é finalizada mesmo na falha.
     * <p>INVARIANTES DO DOMÍNIO: o dicionário reconstruído mapeia original→desmascarado; a exceção
     * de nível de arquivo não carrega lotes; {@code finalizar()} roda no bloco finally.
     * <p>COMPORTAMENTO EM CASO DE FALHA: dicionário mascarado, lotes preservados ou barra não
     * finalizada reprova.
     */
    @Test
    void traducaoParcialReconstruidaComDicionarioDesmascaradoEFinalizaBarra() throws Exception {
        FakeEpisodio ep = new FakeEpisodio();
        TraducaoLote loteSalvo = new TraducaoLote(1, List.of("[[TAG0]]Ola"), true, null);
        ep.aLancar = new TraducaoParcialException("parou", List.of(loteSalvo), null);
        FakeUiLogger ui = new FakeUiLogger();
        TradutorLotesService s = servico(props(20), ep, ui, new FakeProtecao(), new FakeTelemetria());

        TraducaoParcialException lancada = assertThrows(TraducaoParcialException.class,
            () -> s.traduzirPendentes(pendentes("{\\b1}Oi"), Set.of(), "ep.ass", new ArrayList<>(), null));

        assertEquals(Map.of("{\\b1}Oi", "{\\b1}Ola"), lancada.getDicionarioParcial(),
            "o dicionário parcial deve estar desmascarado");
        assertNull(lancada.getLotesSalvos(), "a exceção reconstruída usa o dicionário, não os lotes");
        assertEquals(1, ui.finalizacoes, "finalizar() deve ocorrer também no caminho de falha");
    }
}
