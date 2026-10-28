package org.traducao.projeto.traducaoKaraoke.application;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.traducao.projeto.core.presentation.web.LogStreamService;
import org.traducao.projeto.core.texto.TextoSemTags;
import org.traducao.projeto.llm.domain.LlmPort;
import org.traducao.projeto.llm.domain.Lote;
import org.traducao.projeto.llm.domain.TraducaoLote;
import org.traducao.projeto.qualidadeTraducao.application.MascaradorTags;
import org.traducao.projeto.qualidadeTraducao.application.ValidadorTraducaoService;
import org.traducao.projeto.qualidadeTraducao.domain.AlucinacaoDetectadaException;
import org.traducao.projeto.qualidadeTraducao.domain.MarcadorPerdidoException;
import org.traducao.projeto.telemetria.TelemetriaService;
import org.traducao.projeto.traducaoKaraoke.domain.GradienteKaraoke;
import org.traducao.projeto.traducaoKaraoke.domain.ServidorLlmIndisponivelException;
import org.traducao.projeto.traducaoKaraoke.domain.TagsNoMeioDaLetra;
import org.traducao.projeto.traducaoKaraoke.domain.VersosDaLetra;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;

/**
 * PROPÓSITO DE NEGÓCIO: leva UMA linha de letra ao LLM e devolve a tradução vestida com a
 * moldura original. É o dono dos TRÊS caminhos de envio, e a ordem entre eles não é preferência:
 * cada um nasceu de um prejuízo medido.
 *
 * <h2>Os três caminhos, e o que cada um custou para existir</h2>
 * <ol>
 *   <li><b>Gradiente</b> — karaokê pintado letra a letra. Guilty Crown, 07/08/2026: 28 de 31
 *       recusas eram marcador intercalado que nenhum modelo devolve na ordem.</li>
 *   <li><b>Texto puro</b> — tags só na borda. 08th MS Team, 08/08/2026: 1.258 de 1.258 avisos
 *       pelo mesmo motivo, com português perfeito sendo descartado.</li>
 *   <li><b>Mascarador</b> — o caminho antigo, só para o que não couber nos dois primeiros.</li>
 *   <li><b>Refeita sem marcador</b> — SEGUNDO tiro, só quando o mascarador falha por marcador
 *       perdido ou por linhas a mais. Guilty Crown, 09/10/2026: 70 de 71 recusas do acervo eram
 *       tag no meio da frase, uma delas com tradução certa descartada ("que os seus olhos foram
 *       dados a você para reconhecer os outros,"). Ver {@link TagsNoMeioDaLetra}.</li>
 * </ol>
 *
 * <h2>Por que isto saiu do use case</h2>
 * Eram 780 bytecodes e CINCO dependências ({@code llmPort}, {@code mascarador},
 * {@code validador}, {@code telemetriaService}, {@code logStream}) dentro de um objeto que
 * também classificava, gravava cache e escrevia arquivo. Aqui a mesma lógica é testável sem
 * disco, sem cache e sem manifesto.
 *
 * <h2>Invariantes do domínio</h2>
 * <ul>
 *   <li>Resposta inválida, recusa do pedido ou alucinação devolvem {@code null} — a linha fica
 *       no idioma original e um aviso é registrado COM A CAUSA que o adaptador deu (A7: até
 *       28/10/2026 toda falha virava "LLM não retornou tradução").</li>
 *   <li>Servidor indisponível (falha que o adaptador não classificou como recusa deste pedido) NÃO
 *       é problema da linha: lança {@link ServidorLlmIndisponivelException} e a execução para,
 *       em vez de gravar o resto da pasta em inglês com banner de sucesso.</li>
 *   <li>Interrupção pelo operador ("Parar") nunca vira chamada nova nem exceção de servidor: a
 *       linha volta {@code null} e quem decide o desfecho é o caso de uso.</li>
 *   <li>A moldura devolvida é a do ORIGINAL, nunca a que o modelo imaginou.</li>
 *   <li>O {@code sequencialLote} é o contador LOCAL da execução, recebido por parâmetro — nunca
 *       campo de instância, para não ser perturbado por execução concorrente deste bean.</li>
 * </ul>
 *
 * <h2>Comportamento em caso de falha</h2>
 * Lança só {@link ServidorLlmIndisponivelException}. Todo outro caminho de erro devolve
 * {@code null} e escreve o motivo em {@code avisos}.
 */
@ApplicationScoped
public class TradutorDeLetraKaraoke {

    static final String CANAL_LOG = TraduzirKaraokeUseCase.CANAL_LOG;

    /**
     * PROPÓSITO DE NEGÓCIO: a MESMA linha da MESMA música tem de sair com a MESMA tradução em
     * todos os episódios da obra. Amostragem determinística é o que garante isso.
     *
     * <h2>O prejuízo que originou, medido em 2026-08-20</h2>
     * O cache do karaokê é por ARQUIVO e os dois mapas de deduplicação vivem dentro de
     * {@code processarArquivo}: cada episódio pergunta a mesma frase de novo. Com a temperatura
     * configurada (0,3), N perguntas idênticas devolvem N respostas diferentes. Medido nas quatro
     * obras traduzidas naquele dia, sobre o texto DISTINTO que foi ao LLM:
     * <pre>
     *   86 Part 1   16 de 31 divergentes (52%)   pior: 5 traduções
     *   86 Part 2    5 de 11 (45%)               pior: 4
     *   Zeta        19 de 33 (58%)               pior: 8 — "Searching, a guidepost floats up ahead."
     *   Unicorn     73 de 131 (56%)              pior: 10 — o fragmento "dnt"
     * </pre>
     * Na tela, a abertura muda de texto a cada episódio. Uma das variantes de
     * <i>"No matter how hard I wish, nothing ever changes"</i> saiu <i>"Importante quanto
     * desejar, nada nunca muda."</i> — errada.
     *
     * <h2>Por que aqui, e não em {@code application.yml}</h2>
     * A propriedade {@code llm.temperature} é COMPARTILHADA com a Tradução Local, e lá a
     * variação é deliberada: o laço de retentativa sobe a temperatura para escapar de uma
     * alucinação que se repetiria com a mesma amostragem — está no Javadoc de
     * {@link LlmPort#traduzir(org.traducao.projeto.llm.domain.Lote, Double)}. Além disso
     * {@code LlmProperties} COAGE qualquer valor {@code <= 0} de volta a 0,3, então
     * {@code temperature: 0} no YAML não teria efeito nenhum. O override da porta não passa por
     * essa coerção e é contrato declarado — é o ponto certo.
     *
     * <p>INVARIANTES DO DOMÍNIO: não existe retentativa com a MESMA entrada nesta classe, então
     * fixar a temperatura não desliga nenhuma rota de recuperação. O segundo tiro de
     * {@link #traduzirSemMarcador} (09/10/2026) não é retentativa nesse sentido: ele manda uma
     * ENTRADA DIFERENTE — a frase limpa no lugar da mascarada —, e é a entrada, não a
     * amostragem, que muda a resposta. Por isso usa esta mesma temperatura, e a mesma letra
     * continua saindo igual em todos os episódios. Retentativa com a mesma entrada precisaria
     * de override próprio, e este comentário é o aviso.
     */
    static final Double TEMPERATURA_DETERMINISTICA = 0.0d;

    /**
     * Marcador de máscara ({@code [[TAG0]]}) que o modelo inventa nos caminhos que NÃO mandaram
     * marcador nenhum. Reconguista IV, 14/10/2026: "Encontrarei minha força dentro do seu
     * encanto." / "[[TAG0]]" — duas linhas para um verso, e a tradução certa foi recusada nas duas
     * camadas. Nesses caminhos ele nunca é legítimo; no do mascarador ele é, e não passa por aqui.
     */
    private static final Pattern MARCADOR_INVENTADO = Pattern.compile("\\[\\[TAG\\d+\\]\\]");

    @Inject
    LlmPort llmPort;

    @Inject
    MascaradorTags mascarador;

    @Inject
    ValidadorTraducaoService validador;

    @Inject
    TelemetriaService telemetriaService;

    @Inject
    LogStreamService logStream;

    /**
     * PROPÓSITO DE NEGÓCIO: traduz uma única linha de letra via LLM (uma linha por lote — a
     * letra é curta e o lote unitário é o padrão do projeto), mascarando as tags antes e
     * restaurando-as depois.
     *
     * <p>INVARIANTES DO DOMÍNIO: o {@code sequencialLote} é o contador LOCAL da execução (ver
     * {@link #executar}), incrementado atomicamente para numerar o lote; nunca é campo de
     * instância, evitando estado compartilhado entre execuções concorrentes deste bean
     * singleton. A saída passa por desmascaramento e validação antes de ser aceita.
     *
     * <p>COMPORTAMENTO EM CASO DE FALHA: falha de comunicação, resposta inválida ou
     * {@link AlucinacaoDetectadaException} devolve {@code null} (mantém a linha original) e
     * registra um aviso — nunca propaga para derrubar o arquivo. No caminho do mascarador,
     * marcador perdido e linhas a mais não devolvem {@code null} de imediato: passam antes pelo
     * segundo tiro de {@link #traduzirSemMarcador}, e só a falha dele mantém a linha.
     */
    String traduzirViaLlm(String original, List<String> avisos, AtomicInteger sequencialLote,
                                  String promptSistemaCongelado) {
        // Karaokê pintado LETRA A LETRA: o mascarador comum produziria uma dezena de [[TAGn]]
        // intercalados e o LLM não os devolve na ordem — medido no Guilty Crown em 07/08/2026,
        // 28 das 31 recusas de uma execução foram exatamente isso, e a única que passou saiu
        // como "So, eu e evidentementereithyingthathingthatmakes mea whole wholed".
        // Aqui a linha é decomposta em paleta + texto: o LLM recebe a frase limpa e as MESMAS
        // cores voltam distribuídas sobre a tradução. Ver GradienteKaraoke.
        Optional<GradienteKaraoke> gradiente = GradienteKaraoke.decompor(original);
        if (gradiente.isPresent()) {
            return traduzirGradiente(
                gradiente.get(), avisos, sequencialLote, promptSistemaCongelado);
        }

        // TAG NA BORDA (o caso do 08th MS Team, 08/08/2026): a linha tem UMA tag de prefixo,
        // vira UM marcador [[TAG0]], e o LLM simplesmente nao o repete. A traducao vinha CERTA e
        // era jogada fora. Do manifesto daquela execucao — 1.258 de 1.258 avisos, todos iguais:
        //
        //   Esperado 1 marcador(es) [0], recebido: Voce ve o sonho brilhando dentro da tempestade
        //   Esperado 1 marcador(es) [0], recebido: Aguenta firme agora! Nao solta isso.
        //
        // Portugues perfeito, descartado por falta de um marcador de controle. Resultado: de
        // 1.636 linhas detectadas, apenas 378 (23%) chegavam a legenda.
        //
        // A saida e a mesma do gradiente e a mesma que Paulo propos em 07/08: NAO mascarar,
        // SEPARAR. O LLM recebe a frase pura — sem marcador nenhum para perder — e a moldura e
        // recolocada aqui. TextoSemTags e o dono desse criterio, ja usado pela fatia traducao.
        Optional<TextoSemTags> semTags = TextoSemTags.decompor(original);
        if (semTags.isPresent()) {
            return traduzirTextoPuro(
                semTags.get(), avisos, sequencialLote, promptSistemaCongelado);
        }

        MascaradorTags.Mascarado mascarado = mascarador.mascarar(original);
        TraducaoLote resposta;
        try {
            resposta = llmPort.traduzir(
                new Lote(sequencialLote.incrementAndGet(), List.of(mascarado.texto())),
                TEMPERATURA_DETERMINISTICA,
                promptSistemaCongelado);
        } catch (RuntimeException e) {
            resposta = falhaDeComunicacao(e);
        }
        if (!respostaUtil(resposta, avisos, original, "")) {
            return null;
        }
        // Os avisos desta tentativa ficam de lado ate se saber se o segundo tiro a salva: se
        // salvar, "linha mantida" seria MENTIRA no manifesto (A7) — a linha saiu traduzida.
        List<String> recusaDoMascarador = new ArrayList<>();
        String unida = unirVersos(resposta.linhasTraduzidas(), VersosDaLetra.contar(original), recusaDoMascarador, original);
        if (unida == null) {
            return traduzirSemMarcador(original, recusaDoMascarador, avisos, sequencialLote, promptSistemaCongelado);
        }
        try {
            String traduzido = mascarador.desmascarar(unida, mascarado.tags());
            validarContraOriginal(original, traduzido);
            return traduzido;
        } catch (MarcadorPerdidoException e) {
            // NAO e alucinacao, e o console nao pode dizer que e (08/08/2026): o modelo traduziu
            // e so nao repetiu o marcador. Mostrar a TRADUCAO RECUSADA e o que permite ao
            // operador ver, na hora, que perdeu trabalho bom — e nao lixo.
            telemetriaService.registrarAlucinacaoPrevenida();
            recusaDoMascarador.add("Marcador perdido (" + e.getMessage() + "); linha mantida: " + original);
            logStream.publicarLog(CANAL_LOG, "   [MARCADOR PERDIDO] traducao DESCARTADA por falta de tag: \""
                + e.traducaoRecusada() + "\"");
            return traduzirSemMarcador(original, recusaDoMascarador, avisos, sequencialLote, promptSistemaCongelado);
        } catch (AlucinacaoDetectadaException e) {
            telemetriaService.registrarAlucinacaoPrevenida();
            avisos.add("Alucinação detectada (" + e.getMessage() + "); linha mantida: " + original);
            logStream.publicarLog(CANAL_LOG, "   [AVISO] Alucinação interceptada — linha mantida sem tradução: "
                + RegistroDaExecucao.visivelResumido(original));
            return null;
        }
    }

    /**
     * PROPÓSITO DE NEGÓCIO: traduz uma linha de karaokê cujas tags estão todas na BORDA, enviando
     * ao LLM só a frase e recolocando a moldura na volta.
     *
     * <h2>O prejuízo que originou</h2>
     * Execução real no 08th MS Team em 08/08/2026: <b>1.636 linhas detectadas, 378 corrigidas
     * (23%)</b>. Os 1.258 avisos do manifesto são TODOS o mesmo motivo — marcador
     * {@code [[TAG0]]} não devolvido pelo modelo — e o texto recusado estava correto em
     * português. O sistema descartava tradução boa por causa de um marcador de controle.
     *
     * <p>INVARIANTES DO DOMÍNIO: o texto que sai daqui rumo ao LLM não contém tag ASS nem
     * marcador, então não existe marcador a perder; a moldura devolvida é a do ORIGINAL, nunca a
     * que o modelo tenha imaginado. A validação de alucinação roda sobre o texto puro, ANTES de
     * recompor — validar depois faria a própria tag disparar o detector de resíduo.
     *
     * <p>COMPORTAMENTO EM CASO DE FALHA: falha de comunicação, resposta inválida ou alucinação
     * devolvem {@code null} e a linha fica no idioma original, com aviso. Nunca linha meio montada.
     */
    String traduzirTextoPuro(TextoSemTags semTags, List<String> avisos,
                                     AtomicInteger sequencialLote, String promptSistemaCongelado) {
        TraducaoLote resposta;
        try {
            resposta = llmPort.traduzir(
                new Lote(sequencialLote.incrementAndGet(), List.of(semTags.textoLimpo())),
                TEMPERATURA_DETERMINISTICA,
                promptSistemaCongelado);
        } catch (RuntimeException e) {
            resposta = falhaDeComunicacao(e);
        }
        if (!respostaUtil(resposta, avisos, semTags.textoLimpo(), "")) {
            return null;
        }
        String traduzido = unirVersos(semMarcadorInventado(resposta.linhasTraduzidas()),
            VersosDaLetra.contar(semTags.textoLimpo()), avisos, semTags.textoLimpo());
        if (traduzido == null) {
            return null;
        }
        if (VersosDaLetra.suspeitaDeVersoPerdido(semTags.textoLimpo(), traduzido)) {
            String porVerso = versoAVerso(semTags.textoLimpo(), sequencialLote, promptSistemaCongelado);
            if (porVerso != null) {
                avisos.add("Verso perdido na resposta (\"" + traduzido + "\"); refeita verso a verso: "
                    + semTags.textoLimpo() + " => " + porVerso);
                logStream.publicarLog(CANAL_LOG, "   [VERSO A VERSO] a resposta juntou os versos e encurtou; refeita: "
                    + porVerso);
                traduzido = porVerso;
            }
        }
        try {
            validarContraOriginal(semTags.textoLimpo(), traduzido);
        } catch (AlucinacaoDetectadaException e) {
            telemetriaService.registrarAlucinacaoPrevenida();
            avisos.add("Alucinação detectada (" + e.getMessage() + "); letra mantida: "
                + semTags.textoLimpo());
            logStream.publicarLog(CANAL_LOG,
                "   [AVISO] Alucinação interceptada na letra — mantida sem tradução: "
                    + semTags.textoLimpo());
            return null;
        }
        return semTags.recompor(traduzido);
    }

    /**
     * PROPÓSITO DE NEGÓCIO: traduz uma linha de karaokê com gradiente de cor por letra, enviando
     * ao LLM apenas o texto que o espectador lê e devolvendo a tradução vestida com as MESMAS
     * cores — o efeito visual do fansub sobrevive à tradução.
     *
     * <p>INVARIANTES DO DOMÍNIO: o texto enviado ao LLM não tem nenhuma tag ASS, portanto não há
     * marcador para o modelo perder; a paleta é reposicionada, nunca alterada. A validação de
     * alucinação roda sobre o TEXTO PURO, antes de recompor — validar depois faria as tags de cor
     * dispararem o detector de resíduo, que foi o outro motivo de recusa observado.
     *
     * <p>COMPORTAMENTO EM CASO DE FALHA: falha de comunicação, resposta inválida ou alucinação
     * devolvem {@code null} e a linha permanece no idioma original, com aviso — exatamente como no
     * caminho comum. Nunca devolve linha meio montada.
     */
    String traduzirGradiente(GradienteKaraoke gradiente, List<String> avisos,
                                     AtomicInteger sequencialLote, String promptSistemaCongelado) {
        TraducaoLote resposta;
        try {
            resposta = llmPort.traduzir(
                new Lote(sequencialLote.incrementAndGet(), List.of(gradiente.textoVisivel())),
                TEMPERATURA_DETERMINISTICA,
                promptSistemaCongelado);
        } catch (RuntimeException e) {
            resposta = falhaDeComunicacao(e);
        }
        if (!respostaUtil(resposta, avisos, gradiente.textoVisivel(), "")) {
            return null;
        }
        // O gradiente veta \N na decomposição: aqui a letra é sempre de UM verso.
        String traduzido = unirVersos(semMarcadorInventado(resposta.linhasTraduzidas()), 1, avisos,
            gradiente.textoVisivel());
        if (traduzido == null) {
            return null;
        }
        try {
            validarContraOriginal(gradiente.textoVisivel(), traduzido);
        } catch (AlucinacaoDetectadaException e) {
            telemetriaService.registrarAlucinacaoPrevenida();
            avisos.add("Alucinação detectada (" + e.getMessage() + "); letra mantida: "
                + gradiente.textoVisivel());
            logStream.publicarLog(CANAL_LOG,
                "   [AVISO] Alucinação interceptada na letra — mantida sem tradução: "
                    + gradiente.textoVisivel());
            return null;
        }
        return gradiente.recompor(traduzido);
    }

    /**
     * PROPÓSITO DE NEGÓCIO: dá à linha com tag no MEIO um segundo tiro quando o mascarador falha
     * — a frase vai ao modelo LIMPA, sem marcador para perder, e as tags do original voltam
     * recolocadas por {@link TagsNoMeioDaLetra}. É o que tira do inglês as três frases do OP_S2 do
     * Guilty Crown medidas em 09/10/2026 (70 das 71 recusas do acervo).
     *
     * <p>INVARIANTES DO DOMÍNIO:
     * <ul>
     *   <li>Só roda DEPOIS do mascarador e só nas duas falhas em que a máscara é a causa
     *       provável (marcador perdido, linhas a mais). Onde o mascarador acerta, a tag continua
     *       onde o próprio modelo a pôs — a posição proporcional daqui é aproximação, e não
     *       substitui alinhamento melhor.</li>
     *   <li>Portão MAIS estrito que o dos outros caminhos: além de {@code validarFala}, passa por
     *       {@code validarPar} contra a frase original. Este tiro existe justamente para linhas em
     *       que o modelo já se perdeu ("rely" virou "Relatório recebido. Início da transmissão."),
     *       e a resposta fluente sem âncora — eco, meta-resposta, texto desproporcional — só o
     *       par enxerga.</li>
     *   <li>A7: o desfecho fica escrito. Salva, a linha ganha um aviso de "refeita sem marcador"
     *       com o motivo da recusa anterior e o resultado; perdida, os avisos das DUAS tentativas
     *       vão ao manifesto, e nenhum deles diz que o modelo não respondeu.</li>
     * </ul>
     *
     * <p>COMPORTAMENTO EM CASO DE FALHA: linha fora do recorte (sem tag, {@code \k}, {@code \N},
     * desenho), falha de comunicação, resposta com linhas a mais ou reprovação em qualquer dos
     * dois portões devolvem {@code null} — a linha fica no original, como antes deste caminho
     * existir. Nunca lança e nunca devolve linha meio montada.
     */
    String traduzirSemMarcador(String original, List<String> recusaDoMascarador, List<String> avisos,
                               AtomicInteger sequencialLote, String promptSistemaCongelado) {
        Optional<TagsNoMeioDaLetra> moldura = TagsNoMeioDaLetra.decompor(original);
        if (moldura.isEmpty()) {
            avisos.addAll(recusaDoMascarador);
            return null;
        }
        String frase = moldura.get().textoVisivel().strip();
        if (Thread.currentThread().isInterrupted()) {
            // "Parar" pedido entre o primeiro tiro e o segundo: não gasta chamada nova.
            avisos.addAll(recusaDoMascarador);
            avisos.add("Tradução interrompida pelo operador antes do segundo tiro; letra mantida: " + frase);
            return null;
        }
        logStream.publicarLog(CANAL_LOG, "   [SEM MARCADOR] refazendo com a frase limpa; as tags voltam recolocadas: "
            + frase);
        List<String> recusaDoSegundoTiro = new ArrayList<>();
        TraducaoLote resposta;
        try {
            resposta = llmPort.traduzir(
                new Lote(sequencialLote.incrementAndGet(), List.of(frase)),
                TEMPERATURA_DETERMINISTICA,
                promptSistemaCongelado);
        } catch (RuntimeException e) {
            resposta = falhaDeComunicacao(e);
        }
        List<String> falhaDoSegundoTiro = new ArrayList<>();
        if (!respostaUtil(resposta, falhaDoSegundoTiro, frase, " no segundo tiro")) {
            avisos.addAll(recusaDoMascarador);
            avisos.addAll(falhaDoSegundoTiro);
            return null;
        }
        // O recorte veta \N: aqui a letra e sempre de UM verso.
        String traduzido = unirVersos(semMarcadorInventado(resposta.linhasTraduzidas()), 1, recusaDoSegundoTiro, frase);
        if (traduzido == null) {
            avisos.addAll(recusaDoMascarador);
            avisos.addAll(recusaDoSegundoTiro);
            return null;
        }
        try {
            validarContraOriginal(frase, traduzido);
            validador.validarPar(frase, traduzido);
        } catch (AlucinacaoDetectadaException e) {
            telemetriaService.registrarAlucinacaoPrevenida();
            avisos.addAll(recusaDoMascarador);
            avisos.add("Segundo tiro reprovado (" + e.getMessage() + "); letra mantida: " + frase);
            logStream.publicarLog(CANAL_LOG,
                "   [AVISO] segundo tiro reprovado — letra mantida sem tradução: " + e.getMessage());
            return null;
        }
        String recomposta = moldura.get().recompor(traduzido);
        String motivo = recusaDoMascarador.isEmpty() ? "mascarador falhou" : recusaDoMascarador.getFirst();
        avisos.add("Refeita sem marcador (" + motivo + "); traduzida: " + frase + " => " + traduzido);
        logStream.publicarLog(CANAL_LOG, "   [SEM MARCADOR] traduzida: " + frase + "  =>  " + traduzido);
        return recomposta;
    }

    /**
     * PROPÓSITO DE NEGÓCIO: julga a tradução da letra com a letra enviada em mãos. Até 28/10/2026
     * os três caminhos principais chamavam {@code validarFala} só com a tradução: "Não recebi a
     * ordem." para "I didn't receive the order." era reprovada como recusa do modelo, porque a
     * absolvição de 09/09/2026 depende do original.
     *
     * <h2>Por que o par ({@code validarPar}) NÃO entra aqui — medido, não suposto</h2>
     * Os 2.070 pares distintos do cache real do karaokê (208 arquivos, 28/10/2026), com o par
     * ligado: 11 reprovações novas. 9 eram letra JÁ em português devolvida igual (a saída não
     * muda); 1 era erro real ("Will anyone ever find out?" virou afirmação); 1 era tradução certa
     * ("asking, ..." => "perguntando: ...", acusada de locutor inventado). Um acerto por um falso
     * positivo não paga o custo, e a regra do par segue no segundo tiro, onde a linha já se perdeu
     * uma vez.
     *
     * <p>INVARIANTES DO DOMÍNIO: o original só ABSOLVE (regra de recusa ambígua); toda regra que
     * reprovava sem ele continua reprovando.
     *
     * <p>COMPORTAMENTO EM CASO DE FALHA: lança {@link AlucinacaoDetectadaException}, que cada
     * caminho converte em linha mantida com aviso.
     */
    private void validarContraOriginal(String original, String traduzido) {
        validador.validarFala(traduzido, original);
    }

    /**
     * PROPÓSITO DE NEGÓCIO: separa as duas razões de uma resposta inútil — o problema é DESTA linha
     * (recusa do pedido, resposta vazia ou truncada: a linha fica no original com a causa no aviso)
     * ou do SERVIDOR (timeout, 5xx, conexão: a execução para). Antes as duas eram "LLM não retornou
     * tradução", e o servidor caído no meio da pasta gravava o resto em inglês com [SUCESSO].
     *
     * <p>INVARIANTES DO DOMÍNIO: a classificação é a do adaptador ({@code recusaDaRequisicao}); falha
     * sem essa marca é indisponibilidade, como manda o contrato do {@link TraducaoLote}. Com a thread
     * interrompida nada é servidor: é o "Parar" do operador, e quem decide é o caso de uso.
     *
     * <p>COMPORTAMENTO EM CASO DE FALHA: resposta útil devolve {@code true}; problema da linha
     * registra o aviso com a causa e devolve {@code false}; servidor indisponível lança
     * {@link ServidorLlmIndisponivelException}.
     */
    private boolean respostaUtil(TraducaoLote resposta, List<String> avisos, String letra, String etapa) {
        if (resposta != null && resposta.sucesso()
            && resposta.linhasTraduzidas() != null && !resposta.linhasTraduzidas().isEmpty()) {
            return true;
        }
        String causa = resposta == null ? "sem resposta do adaptador"
            : resposta.sucesso() ? "resposta sem linhas"
            : resposta.mensagemErro() == null || resposta.mensagemErro().isBlank() ? "sem diagnóstico do adaptador"
            : resposta.mensagemErro();
        if (Thread.currentThread().isInterrupted()) {
            avisos.add("Tradução interrompida pelo operador" + etapa + "; letra mantida: " + letra);
            return false;
        }
        if (resposta == null || (!resposta.sucesso() && !resposta.recusaDaRequisicao())) {
            throw new ServidorLlmIndisponivelException("Servidor LLM indisponível" + etapa + " (" + causa
                + "); a tradução de karaokê parou em vez de gravar o resto em inglês. Letra: " + letra);
        }
        avisos.add("LLM não traduziu" + etapa + " (" + causa + "); letra mantida: " + letra);
        logStream.publicarLog(CANAL_LOG, "   [AVISO] LLM não traduziu" + etapa + " (" + causa + ") — letra mantida: "
            + letra);
        return false;
    }

    /** Exceção lançada pela porta vira falha sem classificação — o contrato a trata como indisponibilidade. */
    private static TraducaoLote falhaDeComunicacao(RuntimeException e) {
        return new TraducaoLote(0, null, false, "falha de comunicação: "
            + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()));
    }

    /**
     * PROPÓSITO DE NEGÓCIO: tira o marcador de máscara que o modelo inventou nos caminhos que não
     * mandaram marcador — linha que era só o marcador some, marcador colado ao texto sai do texto.
     *
     * <p>INVARIANTES DO DOMÍNIO: só é chamado onde nenhum marcador foi enviado; linha sem marcador
     * volta idêntica.
     *
     * <p>COMPORTAMENTO EM CASO DE FALHA: lista nula volta nula.
     */
    static List<String> semMarcadorInventado(List<String> linhas) {
        if (linhas == null) {
            return null;
        }
        List<String> limpas = new ArrayList<>(linhas.size());
        for (String linha : linhas) {
            if (linha == null || !MARCADOR_INVENTADO.matcher(linha).find()) {
                limpas.add(linha);
                continue;
            }
            limpas.add(MARCADOR_INVENTADO.matcher(linha).replaceAll(" ").replaceAll(" {2,}", " ").strip());
        }
        return limpas;
    }

    /**
     * PROPÓSITO DE NEGÓCIO: refaz verso a verso a letra cuja resposta juntou os versos E encurtou —
     * a marca do verso perdido. Até 28/10/2026 essa marca só era conferida no REAPROVEITAMENTO do
     * cache: o verso realmente perdido ia para a tela na primeira execução, e o falso positivo
     * ("Busco aqueles que tentam pisar neles." para os dois versos do Break Blade 05) voltava ao LLM
     * em TODA execução, com a mesma resposta, para sempre. Verso a verso, a resposta tem o mesmo
     * número de linhas do original e deixa de ser suspeita.
     *
     * <p>INVARIANTES DO DOMÍNIO: cada verso vai sozinho; só aceita se TODOS voltarem, unidos por
     * {@code \N} na ordem. A letra montada passa depois pelo mesmo portão contra o original.
     *
     * <p>COMPORTAMENTO EM CASO DE FALHA: qualquer verso que falhe devolve {@code null} e o chamador
     * fica com a resposta de antes; servidor indisponível propaga.
     */
    private String versoAVerso(String letra, AtomicInteger sequencialLote, String promptSistemaCongelado) {
        List<String> traduzidos = new ArrayList<>();
        for (String verso : letra.split(Pattern.quote("\\N"))) {
            if (verso.isBlank()) {
                continue;
            }
            TraducaoLote resposta;
            try {
                resposta = llmPort.traduzir(new Lote(sequencialLote.incrementAndGet(), List.of(verso.strip())),
                    TEMPERATURA_DETERMINISTICA, promptSistemaCongelado);
            } catch (RuntimeException e) {
                resposta = falhaDeComunicacao(e);
            }
            List<String> descartados = new ArrayList<>();
            if (!respostaUtil(resposta, descartados, verso, " verso a verso")) {
                return null;
            }
            String unido = unirVersos(semMarcadorInventado(resposta.linhasTraduzidas()), 1, descartados, verso);
            if (unido == null) {
                return null;
            }
            traduzidos.add(unido);
        }
        return traduzidos.size() < 2 ? null : String.join("\\N", traduzidos);
    }

    /**
     * PROPÓSITO DE NEGÓCIO: transforma a resposta do LLM no texto da letra sem perder verso. Até
     * 24/09/2026 os três caminhos pegavam só a PRIMEIRA linha da resposta, e o segundo verso de
     * letras como "Have a little break\NWe're running through the lights" sumia em silêncio.
     *
     * <p>INVARIANTES DO DOMÍNIO: a regra é de {@link VersosDaLetra#unirResposta} — 1 linha passa,
     * N linhas só quando N é o número de versos enviados. A recusa fica REGISTRADA com as duas
     * contagens (A7): o operador vê que a letra ficou em inglês porque a resposta não batia, e
     * não por falha do servidor.
     *
     * <p>COMPORTAMENTO EM CASO DE FALHA: contagem que não bate devolve {@code null} e registra
     * aviso — a linha fica no idioma original. Nunca lança.
     */
    private String unirVersos(List<String> linhas, int versosEnviados, List<String> avisos, String letra) {
        Optional<String> unida = VersosDaLetra.unirResposta(linhas, versosEnviados);
        if (unida.isEmpty()) {
            int recebidas = linhas == null ? 0 : linhas.size();
            // A7: guardar O QUE o modelo mandou, e não só as contagens — medido em 24/09/2026 (1
            // recusa em 929 traduções), o manifesto dizia que houve recusa sem dizer de quê.
            String recusada = linhas == null ? "" : String.join(" | ", linhas);
            avisos.add("Resposta com " + recebidas + " linha(s) para " + versosEnviados
                + " verso(s); letra mantida: " + letra + " | resposta recusada: " + recusada);
            logStream.publicarLog(CANAL_LOG, "   [AVISO] resposta do LLM com " + recebidas
                + " linha(s) para " + versosEnviados + " verso(s) — letra mantida no original: " + letra
                + " | recusada: " + recusada);
            return null;
        }
        return unida.get();
    }
}
