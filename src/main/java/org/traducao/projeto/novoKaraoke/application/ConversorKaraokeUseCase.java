package org.traducao.projeto.novoKaraoke.application;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.traducao.projeto.novoKaraoke.domain.EventoAss;
import org.traducao.projeto.novoKaraoke.domain.MedicaoEstiloKaraoke;
import org.traducao.projeto.novoKaraoke.domain.LinhaSimplesKaraoke;
import org.traducao.projeto.novoKaraoke.domain.NovoKaraokeException;
import org.traducao.projeto.novoKaraoke.domain.ResultadoConversaoKaraoke;
import org.traducao.projeto.novoKaraoke.domain.ports.TelemetriaKaraokePort;
import org.traducao.projeto.core.util.DuracaoUtil;
import org.traducao.projeto.novoKaraoke.infrastructure.NovoKaraokePersistencia;
import org.traducao.projeto.telemetria.TelemetriaService;
import org.traducao.projeto.legenda.application.DetectorEfeitoKaraokeService;
import org.traducao.projeto.core.presentation.web.LogStreamService;
import org.traducao.projeto.core.texto.dicionarioOrtografia.CorretorOrtograficoLegenda;
import org.traducao.projeto.core.texto.dicionarioOrtografia.VeredictoPalavra;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Converte legendas .ass com karaokê KFX (milhares de eventos por sílaba/frame)
 * em legendas simples: uma linha limpa por frase da música, no MESMO tempo do
 * efeito original (início = menor início do bloco, fim = maior fim).
 * <p>
 * Garantias de segurança:
 * <ul>
 *   <li>O arquivo original NUNCA é alterado — a saída vai para a pasta que o
 *       usuário escolher.</li>
 *   <li>Diálogo, placas e Comment são reemitidos byte a byte (linha crua).</li>
 *   <li>Bloco musical que não puder ser reconstruído com confiança é mantido
 *       intacto (viés de preservação, mesmo princípio do
 *       {@link DetectorEfeitoKaraokeService}).</li>
 * </ul>
 */
@ApplicationScoped
public class ConversorKaraokeUseCase {

    private static final Logger log = LoggerFactory.getLogger(ConversorKaraokeUseCase.class);

    public static final String CANAL_LOG = "novo-karaoke";
    static final String NOME_ESTILO_SIMPLES = "Karaoke Simples";
    /** Quebra visual do ASS, usada para separar as duas camadas de um verso pareado. */
    private static final String QUEBRA_ASS = "\\N";

    /** Gap acima deste valor separa duas ocorrências da mesma frase (refrão repetido). */
    private static final long GAP_MESMA_FRASE_CS = 1000; // 10s
    /**
     * Evento que fica na tela ao menos isto é um VERSO INTEIRO, não uma cópia de quadro da
     * animação. Medido em 24/09/2026: as cópias de fade/wipe da abertura do 86 duram 5 a 12
     * centésimos; os versos inteiros do ED e ED - EN do Unicorn E01, de 4,0 a 5,9 s. Só vale para
     * frases de 2+ palavras — sílaba solta de KFX nunca chega a este agrupamento.
     */
    private static final long DURACAO_VERSO_INTEIRO_CS = 150;

    private static boolean ehVersoInteiro(EventoAss evento) {
        return evento.fimCs() - evento.inicioCs() >= DURACAO_VERSO_INTEIRO_CS;
    }
    /** Gap usado quando só há fragmentos silábicos/frame-a-frame, sem linha inteira. */
    private static final long GAP_FRAGMENTOS_KFX_CS = 200; // 2s
    private static final long GAP_REPETICAO_FRAGMENTO_CS = 50;
    /** Concorrência a partir da qual o KFX é letra-por-letra (frase inteira na tela ao mesmo tempo). */
    private static final int PICO_SIMULTANEO_MINIMO = 8;
    /** Vale de concorrência (fração do pico) que marca a troca de frase num KFX letra-por-letra. */
    private static final double FRACAO_VALE_CONCORRENCIA = 0.15;
    private static final int VALE_CONCORRENCIA_MINIMO = 2;
    /** Segmento de frase mais curto que isto indica que o corte por vale não se aplica. */
    private static final long DURACAO_MINIMA_SEGMENTO_CS = 300; // 3s
    /** Linha reconstruída acima destes limites é implausível — preserva o KFX original. */
    private static final long DURACAO_MAXIMA_LINHA_FRAGMENTO_CS = 2000; // 20s
    private static final int TEXTO_MAXIMO_LINHA_FRAGMENTO = 140;
    /** Margem pequena só para cobrir arredondamento/frame na remoção dos eventos KFX. */
    private static final long MARGEM_COBERTURA_CS = 25;
    /** Janelas com sobreposição >= 60% e texto parecido são variantes divergentes da mesma frase. */
    private static final double SOBREPOSICAO_MINIMA_VARIANTE = 0.6;
    private static final double SIMILARIDADE_MINIMA_TOKENS = 0.3;

    private static final Pattern PADRAO_PLAY_RES_Y = Pattern.compile("(?i)^PlayResY\\s*:\\s*(\\d+)");
    private static final Pattern PADRAO_ESTILO = Pattern.compile("(?i)^Style\\s*:");
    private static final Pattern PADRAO_SECAO = Pattern.compile("^\\s*\\[[^\\]]+\\]");
    private static final Pattern PADRAO_ARQUIVO_AUXILIAR = Pattern.compile(
        "(?i).*(?:NCOP\\d*|NCED\\d*|Special Edition|[_\\s-]SP[_\\s-]).*");
    private static final Pattern PADRAO_ARTEFATO_TAG_VISIVEL = Pattern.compile("(?i)!?\\[?TAG\\d+\\]?");
    private static final Pattern PADRAO_JAPONES = Pattern.compile("[\\p{IsHiragana}\\p{IsKatakana}\\p{IsHan}]");
    private static final Pattern PADRAO_COORDENADA_PRINCIPAL = Pattern.compile(
        "\\\\(?:pos|move)\\((-?\\d+(?:\\.\\d+)?),(-?\\d+(?:\\.\\d+)?)");
    private static final Pattern PALAVRA_ROMAJI_PATTERN = Pattern.compile(
        "^(?:n|(?:([kgsztdnhbpmyrwfjv])\\1?|sh|ch|ts|ky|gy|ny|hy|my|ry|by|py)?[aeiou])+$");
    private static final Pattern NAO_ASCII_PATTERN = Pattern.compile("[^\\x00-\\x7F]");
    /** Modo de desenho do ASS ligado num bloco de override: {@code \p1}, {@code \p2}... */
    private static final Pattern PADRAO_DESENHO = Pattern.compile("\\{[^}]*\\\\p[1-9]");

    @Inject
    DetectorEfeitoKaraokeService detectorKaraoke;

    /**
     * Classificador de idioma por dicionário Hunspell (CORE), usado SÓ para decidir se um par com
     * {@code \N} é {@code original\Ntradução} (preserva 2 linhas) ou verso monolíngue (achata). É o
     * dicionário real do projeto, não uma lista de palavras à mão (ordem do Paulo, 21/09).
     */
    @Inject
    CorretorOrtograficoLegenda corretorOrtografico;

    @Inject
    LogStreamService logStream;

    @Inject
    TelemetriaService telemetriaService;

    @Inject
    NovoKaraokePersistencia persistencia;

    /**
     * Medição por arquivo E por estilo. Entra por PORTA justamente porque o defeito desta
     * fatia é NÃO agir: quem mede o que foi recusado precisa de contrato, não de um serviço
     * concreto que aceita o que alguém lembrar de mandar.
     */
    @Inject
    TelemetriaKaraokePort telemetriaKaraoke;

    public List<ResultadoConversaoKaraoke> simular(Path pastaOrigem, Path pastaDestino) {
        return executar(pastaOrigem, pastaDestino, false);
    }

    public List<ResultadoConversaoKaraoke> aplicar(Path pastaOrigem, Path pastaDestino) {
        return executar(pastaOrigem, pastaDestino, true);
    }

    private List<ResultadoConversaoKaraoke> executar(Path pastaOrigem, Path pastaDestino, boolean gravar) {
        long inicioMs = System.currentTimeMillis();
        String modo = gravar ? "Aplicação" : "Simulação (Dry-Run)";
        logStream.publicarLog(CANAL_LOG, "==== Karaokê Simples — " + modo + " ====");
        logStream.publicarLog(CANAL_LOG, "Origem: " + pastaOrigem);
        logStream.publicarLog(CANAL_LOG, "Destino: " + pastaDestino);

        validarPastas(pastaOrigem, pastaDestino, gravar);

        List<Path> arquivos = listarLegendas(pastaOrigem);
        if (arquivos.isEmpty()) {
            logStream.publicarLog(CANAL_LOG, "Nenhum arquivo .ass encontrado na pasta de origem.");
            return List.of();
        }
        logStream.publicarLog(CANAL_LOG, "Arquivos .ass encontrados: " + arquivos.size());

        List<ResultadoConversaoKaraoke> resultados = new ArrayList<>();
        List<ResultadoConversaoKaraoke> falhados = new ArrayList<>();
        int falhas = 0;
        String carimboDaExecucao = carimboAgora();
        for (Path arquivo : arquivos) {
            if (Thread.currentThread().isInterrupted()) {
                logStream.publicarLog(CANAL_LOG, "[INTERROMPIDO] Conversão cancelada; arquivos já gravados foram preservados.");
                break;
            }
            try {
                resultados.add(converterArquivo(arquivo, pastaDestino, gravar, carimboDaExecucao));
            } catch (Exception e) {
                falhas++;
                falhados.add(ResultadoConversaoKaraoke.falhou(arquivo.getFileName().toString(), e.getMessage()));
                log.error("Falha ao converter {}", arquivo, e);
                logStream.publicarLog(CANAL_LOG, "[ERRO] " + arquivo.getFileName() + ": " + e.getMessage());
            }
        }

        long duracaoMs = System.currentTimeMillis() - inicioMs;
        publicarResumo(resultados, falhas, duracaoMs, gravar);

        if (gravar && (!resultados.isEmpty() || !falhados.isEmpty())) {
            List<ResultadoConversaoKaraoke> paraOManifesto = new ArrayList<>(resultados);
            paraOManifesto.addAll(falhados);
            registrarTelemetriaEManifesto(pastaOrigem, pastaDestino, paraOManifesto, duracaoMs);
        }
        // A medição por porta fecha SEMPRE, inclusive em ensaio e inclusive com zero resultado:
        // execução que não produziu nada é justamente o sintoma que precisa aparecer. O registro
        // acima é o histórico da conversão; este é o dos SINTOMAS, e são perguntas diferentes.
        telemetriaKaraoke.publicarOperacao(
            "Karaokê Simples" + (gravar ? "" : " (simulação)"),
            pastaOrigem, pastaDestino, duracaoMs, resultados.size());
        return resultados;
    }

    private void validarPastas(Path pastaOrigem, Path pastaDestino, boolean gravar) {
        if (!Files.isDirectory(pastaOrigem)) {
            throw new NovoKaraokeException("A pasta de origem não existe ou não é um diretório: " + pastaOrigem);
        }
        if (pastaDestino == null) {
            throw new NovoKaraokeException("Informe a pasta de destino para as legendas convertidas.");
        }
        Path origemNorm = pastaOrigem.toAbsolutePath().normalize();
        Path destinoNorm = pastaDestino.toAbsolutePath().normalize();
        // Compara o lugar REAL, não o texto: destino que é junção, subst ou link para a origem
        // passava na comparação textual e o achatamento regravava a ENTRADA (auditoria de
        // 28/10/2026, reproduzido com SHA antes e depois).
        if (origemNorm.equals(destinoNorm) || lugarReal(origemNorm).equals(lugarReal(destinoNorm))) {
            throw new NovoKaraokeException(
                "A pasta de destino deve ser DIFERENTE da origem: os arquivos originais são preservados para auditoria.");
        }
        if (gravar) {
            try {
                Files.createDirectories(destinoNorm);
            } catch (IOException e) {
                throw new NovoKaraokeException("Não foi possível criar a pasta de destino: " + destinoNorm, e);
            }
        }
    }

    /**
     * PROPÓSITO DE NEGÓCIO: o lugar do disco para onde o caminho aponta, para que "a entrada nunca
     * é alterada" valha também quando o destino é junção, {@code subst} ou link para a origem.
     *
     * <p>INVARIANTES DO DOMÍNIO: o destino pode ainda não existir — resolve o ancestral mais próximo
     * que existe e acrescenta o resto do caminho.
     *
     * <p>COMPORTAMENTO EM CASO DE FALHA: sem ancestral resolvível (unidade ausente), devolve o
     * caminho normalizado como veio — a comparação textual continua valendo.
     */
    static Path lugarReal(Path caminho) {
        Path existente = caminho;
        while (existente != null && !Files.exists(existente)) {
            existente = existente.getParent();
        }
        if (existente == null) {
            return caminho;
        }
        try {
            return existente.toRealPath().resolve(existente.relativize(caminho)).normalize();
        } catch (IOException e) {
            return caminho;
        }
    }

    private List<Path> listarLegendas(Path pasta) {
        try (Stream<Path> stream = Files.list(pasta)) {
            List<Path> arquivos = stream.filter(Files::isRegularFile)
                .filter(p -> p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".ass"))
                .sorted()
                .toList();
            List<Path> principais = arquivos.stream()
                .filter(p -> !ehArquivoAuxiliar(p))
                .toList();
            if (!principais.isEmpty() && principais.size() < arquivos.size()) {
                int ignorados = arquivos.size() - principais.size();
                logStream.publicarLog(CANAL_LOG,
                    "Arquivos auxiliares NCOP/NCED/SP ignorados nesta execução: " + ignorados);
                return principais;
            }
            return arquivos;
        } catch (IOException e) {
            throw new NovoKaraokeException("Falha ao listar a pasta de origem: " + pasta, e);
        }
    }

    private static boolean ehArquivoAuxiliar(Path arquivo) {
        return PADRAO_ARQUIVO_AUXILIAR.matcher(arquivo.getFileName().toString()).matches();
    }

    ResultadoConversaoKaraoke converterArquivo(Path arquivo, Path pastaDestino, boolean gravar) throws IOException {
        return converterArquivo(arquivo, pastaDestino, gravar, carimboAgora());
    }

    ResultadoConversaoKaraoke converterArquivo(Path arquivo, Path pastaDestino, boolean gravar,
                                               String carimboDaExecucao) throws IOException {
        String nome = arquivo.getFileName().toString();
        logStream.publicarLog(CANAL_LOG, "");
        logStream.publicarLog(CANAL_LOG, ">> Processando: " + nome);

        byte[] bytesOriginais = Files.readAllBytes(arquivo);
        boolean temBom = bytesOriginais.length >= 3
            && (bytesOriginais[0] & 0xFF) == 0xEF && (bytesOriginais[1] & 0xFF) == 0xBB && (bytesOriginais[2] & 0xFF) == 0xBF;
        // Decodificação ESTRITA: com substituição, um .ass em Latin-1 virava "�" e era regravado
        // corrompido se tivesse música (auditoria de 28/10/2026). Agora o arquivo falha e fica intacto.
        String conteudo;
        try {
            conteudo = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
                .decode(java.nio.ByteBuffer.wrap(bytesOriginais, temBom ? 3 : 0, bytesOriginais.length - (temBom ? 3 : 0)))
                .toString();
        } catch (java.nio.charset.CharacterCodingException e) {
            throw new IOException("A legenda não está em UTF-8 — nada foi convertido para não corromper o texto: " + nome, e);
        }
        boolean crlf = conteudo.contains("\r\n");
        List<String> linhas = conteudo.lines().toList();

        ResultadoConversaoKaraoke resultado = new ResultadoConversaoKaraoke(nome);
        resultado.setTamanhoOriginalBytes(bytesOriginais.length);

        // Colunas de [Events] fora da ordem padrão: o leitor usa posições fixas e leria estilo no
        // lugar do tempo. Sem mapa de colunas, o arquivo sai INTACTO, com aviso (auditoria, D11).
        if (formatDeEventosForaDoPadrao(linhas)) {
            logStream.publicarLog(CANAL_LOG, "   [AVISO] Format de [Events] fora da ordem padrão — arquivo copiado sem alterações.");
            resultado.adicionarAviso("Format de [Events] fora da ordem padrão: arquivo copiado sem alterações.");
            resultado.setTamanhoNovoBytes(bytesOriginais.length);
            if (gravar) {
                gravarPreservandoAnterior(pastaDestino.resolve(nome), bytesOriginais, carimboDaExecucao, resultado);
            }
            return resultado;
        }

        // --- separa cabeçalho (tudo que não é Dialogue) e eventos ---
        List<String> cabecalho = new ArrayList<>();
        List<EventoAss> eventos = new ArrayList<>();
        int playResY = 0;
        int idxUltimoEstilo = -1;
        for (String linha : linhas) {
            EventoAss evento = EventoAss.interpretar(linha);
            if (evento != null) {
                eventos.add(evento);
                continue;
            }
            Matcher mRes = PADRAO_PLAY_RES_Y.matcher(linha.strip());
            if (mRes.find()) {
                playResY = Integer.parseInt(mRes.group(1));
            }
            if (PADRAO_ESTILO.matcher(linha.stripLeading()).find()) {
                idxUltimoEstilo = cabecalho.size();
            }
            cabecalho.add(linha);
        }
        resultado.setEventosTotais(eventos.size());

        // --- classifica eventos: musical (KFX/estilo de música) vs diálogo ---
        // Musical = nome do estilo indica música OU tag \k crua OU o campo Effect
        // carrega o carimbo "fx" do Kara Templater. NÃO usar a assinatura de template
        // (\t + alta densidade de tags): placas/letreiros animados têm a mesma
        // assinatura e DEVEM sair byte-idênticos — é o mesmo alerta documentado no
        // DetectorEfeitoKaraokeService. O carimbo Effect="fx" é seguro onde a assinatura
        // de template não é: diálogo e Signs têm o campo VAZIO (medido no Unicorn 2026-09-21),
        // e a abertura OPL2 — que o nome não denuncia (op+letra derrota a fronteira) e que não
        // tem \k — só entra por aqui. Sem isto, 155 eventos KFX por episódio saíam intactos.
        List<EventoAss> musicais = new ArrayList<>();
        List<EventoAss> dialogo = new ArrayList<>();
        // MEDIÇÃO DO QUE FOI RECUSADO, e não só do que foi feito: o defeito desta fatia é não
        // agir, então o número que denuncia é o do estilo que NÃO virou música. Ver
        // TelemetriaKaraokePort — foi assim que OP_S2/ED_S2 passaram nove dias despercebidos.
        Map<String, Integer> eventosPorEstilo = new LinkedHashMap<>();
        Map<String, Boolean> musicalPorEstilo = new LinkedHashMap<>();
        for (EventoAss evento : eventos) {
            // IDEMPOTENCIA: a propria saida do achatador (estilo "Karaoke Simples", Effect vazio, sem
            // \k) NAO pode ser reconhecida como musica e reprocessada — o nome contem "karaoke", que a
            // substring de PadraoEstiloMusical casaria. Rodar o achatador duas vezes (ou o
            // encadeamento de pasta apontar para uma saida ja achatada) tem de dar o MESMO resultado.
            boolean jaSimplificado = NOME_ESTILO_SIMPLES.equals(evento.estilo())
                && (evento.efeito() == null || evento.efeito().isBlank())
                && !detectorKaraoke.temTagKaraoke(evento.texto());
            boolean musical = !jaSimplificado
                && (detectorKaraoke.eEstiloDeMusica(evento.estilo())
                || detectorKaraoke.temTagKaraoke(evento.texto())
                || detectorKaraoke.efeitoDeclaraKaraoke(evento.efeito()));
            eventosPorEstilo.merge(evento.estilo(), 1, Integer::sum);
            musicalPorEstilo.merge(evento.estilo(), musical, (a, b) -> a || b);
            if (musical) {
                musicais.add(evento);
            } else {
                dialogo.add(evento);
                resultado.incrementarDialogoPreservado();
            }
        }

        if (musicais.isEmpty()) {
            logStream.publicarLog(CANAL_LOG, "   Nenhum evento de karaokê/música detectado — arquivo copiado sem alterações.");
            resultado.setTamanhoNovoBytes(bytesOriginais.length);
            if (gravar) {
                gravarPreservandoAnterior(pastaDestino.resolve(nome), bytesOriginais, carimboDaExecucao, resultado);
            }
            return resultado;
        }

        // --- reconstrói linhas simples por estilo musical ---
        List<LinhaSimplesKaraoke> linhasSimples = new ArrayList<>();
        List<EventoAss> preservados = new ArrayList<>();
        Map<String, List<EventoAss>> porEstilo = new LinkedHashMap<>();
        for (EventoAss evento : musicais) {
            porEstilo.computeIfAbsent(evento.estilo(), k -> new ArrayList<>()).add(evento);
        }
        // idioma dos pares (\N) classificado UMA vez por arquivo, pelo dicionario Hunspell.
        Map<String, VeredictoPalavra> idioma = classificarIdiomaDosPares(musicais);
        for (Map.Entry<String, List<EventoAss>> entrada : porEstilo.entrySet()) {
            processarEstiloMusical(entrada.getKey(), entrada.getValue(), linhasSimples, preservados, resultado, idioma);
        }
        linhasSimples = deduplicarLinhasSimples(linhasSimples, resultado, idioma);
        linhasSimples.sort(Comparator.comparingLong(LinhaSimplesKaraoke::inicioCs));
        resultado.getLinhasCriadas().addAll(linhasSimples);

        // Achata a tipografia EMPILHADA que sobrou fora da música (cartões de data, letreiros).
        int dialogoAntes = dialogo.size();
        dialogo = colapsarEmpilhados(dialogo);
        if (dialogo.size() < dialogoAntes) {
            resultado.registrarTipografiaColapsada(dialogoAntes - dialogo.size());
            resultado.adicionarAviso((dialogoAntes - dialogo.size()) + " camada(s) de tipografia empilhada "
                + "(mesmo texto, mesma posição e recorte) achatada(s) na de cima.");
        }

        // --- monta o arquivo de saída ---
        List<String> saida = montarSaida(cabecalho, idxUltimoEstilo, playResY, dialogo, preservados, linhasSimples);
        String novoConteudo = String.join(crlf ? "\r\n" : "\n", saida) + (crlf ? "\r\n" : "\n");
        byte[] novosBytes = novoConteudo.getBytes(StandardCharsets.UTF_8);
        resultado.setTamanhoNovoBytes(novosBytes.length + (temBom ? 3 : 0));

        Path destino = pastaDestino.resolve(nome);
        resultado.setArquivoDestino(destino.toString());
        if (gravar) {
            byte[] gravavel;
            if (temBom) {
                gravavel = new byte[novosBytes.length + 3];
                gravavel[0] = (byte) 0xEF;
                gravavel[1] = (byte) 0xBB;
                gravavel[2] = (byte) 0xBF;
                System.arraycopy(novosBytes, 0, gravavel, 3, novosBytes.length);
            } else {
                gravavel = novosBytes;
            }
            gravarPreservandoAnterior(destino, gravavel, carimboDaExecucao, resultado);
        }

        logStream.publicarLog(CANAL_LOG, String.format(Locale.ROOT,
            "   [%s] %s: %d eventos → %d falas preservadas + %d linhas de música simples (%d eventos KFX removidos, %d preservados por segurança) | %d KB → %d KB (-%d%%)",
            gravar ? "GRAVADO" : "SIMULADO", nome,
            resultado.getEventosTotais(), resultado.getEventosDialogoPreservados(), linhasSimples.size(),
            resultado.getEventosKaraokeRemovidos(), resultado.getEventosPreservadosPorSeguranca(),
            resultado.getTamanhoOriginalBytes() / 1024, resultado.getTamanhoNovoBytes() / 1024,
            resultado.getPercentualReducao()));
        publicarMedicao(nome, eventos.size(), dialogo.size() + preservados.size() + linhasSimples.size(),
            linhasSimples, eventosPorEstilo, musicalPorEstilo);
        return resultado;
    }

    private static final String FORMAT_PADRAO_EVENTOS = "layer,start,end,style,name,marginl,marginr,marginv,effect,text";

    /** A linha {@code Format:} da seção [Events] existe e NÃO é a ordem padrão do ASS. */
    private static boolean formatDeEventosForaDoPadrao(List<String> linhas) {
        boolean emEventos = false;
        for (String linha : linhas) {
            String l = linha.strip();
            if (PADRAO_SECAO.matcher(l).find()) {
                emEventos = l.equalsIgnoreCase("[Events]");
                continue;
            }
            if (emEventos && l.regionMatches(true, 0, "Format:", 0, 7)) {
                String campos = l.substring(7).replace(" ", "").toLowerCase(Locale.ROOT);
                return !campos.equals(FORMAT_PADRAO_EVENTOS);
            }
        }
        return false;
    }

    /** Subpasta, DENTRO da pasta de destino, que guarda o arquivo anterior antes de regravá-lo. */
    static final String PASTA_BACKUP = "backup_karaoke_simples";

    private static String carimboAgora() {
        return java.time.LocalDateTime.now()
            // Milissegundos: duas aplicações no mesmo segundo (pasta enfileirada duas vezes) gravavam
            // o backup na MESMA subpasta, com REPLACE_EXISTING (auditoria de 28/10/2026).
            .format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss_SSS"));
    }

    /**
     * PROPÓSITO DE NEGÓCIO: grava a legenda achatada sem PERDER a que já estava no destino.
     *
     * <h2>O prejuízo que originou, medido em 24/09/2026</h2>
     * O destino padrão ({@code legenda-simplificada}) é o MESMO para qualquer pasta de origem da
     * obra. Achatar a {@code traducao_ptbr} depois de achatar a {@code traducao_ptbr-karaoke-ptbr}
     * — engano plausível ao editar o campo do Passo 2 — regravou em silêncio o arquivo que tinha a
     * música traduzida: o SHA mudou e as linhas com português na música caíram de 32 para 0, sem
     * aviso e sem cópia.
     *
     * <p>INVARIANTES DO DOMÍNIO: arquivo existente com conteúdo DIFERENTE é copiado para
     * {@code <destino>/}{@value #PASTA_BACKUP}{@code /<carimbo da execução>/} antes de ser
     * regravado; conteúdo IGUAL (reexecução) não gera cópia. A subpasta de backup não é lida pela
     * próxima execução (a listagem só pega arquivos do nível de cima). Falha ao copiar ABORTA a
     * gravação daquele arquivo — o anterior continua lá, intacto.
     *
     * <p>COMPORTAMENTO EM CASO DE FALHA: propaga {@link IOException}; o arquivo vira falha no
     * resumo e os demais seguem.
     */
    private void gravarPreservandoAnterior(Path destino, byte[] conteudo, String carimbo,
                                           ResultadoConversaoKaraoke resultado) throws IOException {
        if (Files.isRegularFile(destino) && !java.util.Arrays.equals(Files.readAllBytes(destino), conteudo)) {
            Path pastaBackup = destino.toAbsolutePath().getParent().resolve(PASTA_BACKUP).resolve(carimbo);
            Files.createDirectories(pastaBackup);
            Path copia = pastaBackup.resolve(destino.getFileName());
            Files.copy(destino, copia, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            String aviso = "arquivo anterior DIFERENTE preservado antes de regravar: " + copia;
            resultado.adicionarAviso(aviso);
            logStream.publicarLog(CANAL_LOG, "   [BACKUP] " + aviso);
        }
        // Grava num temporário e troca de uma vez: desde que o achatar entrou na fila, o botão
        // Parar interrompe a thread, e um Files.write direto interrompido no meio deixaria o
        // destino TRUNCADO (revisão adversarial de 24/09/2026). Com a troca atômica, o destino é
        // o arquivo inteiro antigo ou o inteiro novo, nunca meio arquivo.
        Path temporario = destino.resolveSibling(destino.getFileName() + ".gravando");
        try {
            Files.write(temporario, conteudo);
            Files.move(temporario, destino, java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                java.nio.file.StandardCopyOption.ATOMIC_MOVE);
        } finally {
            Files.deleteIfExists(temporario);
        }
    }

    /**
     * PROPÓSITO DE NEGÓCIO: entrega à porta de telemetria o recorte que localiza um defeito desta
     * fatia — arquivo E estilo. Publicar o total sozinho não serviria: um arquivo que atravessa
     * sem mudar nada tem total plausível, e é justamente o sintoma.
     *
     * <p>INVARIANTES DO DOMÍNIO: medir NUNCA altera o resultado — este método roda depois de o
     * arquivo já estar decidido e gravado, e a porta é unidirecional. Falha aqui não pode
     * interromper a conversão, então a porta absorve.
     *
     * <p>COMPORTAMENTO EM CASO DE FALHA: delega à porta, que não lança.
     */
    private void publicarMedicao(
        String nome, int eventosEntrada, int eventosSaida, List<LinhaSimplesKaraoke> linhasSimples,
        Map<String, Integer> eventosPorEstilo, Map<String, Boolean> musicalPorEstilo
    ) {
        List<MedicaoEstiloKaraoke> porEstilo = new ArrayList<>();
        for (Map.Entry<String, Integer> e : eventosPorEstilo.entrySet()) {
            porEstilo.add(new MedicaoEstiloKaraoke(
                e.getKey(), e.getValue(),
                Boolean.TRUE.equals(musicalPorEstilo.get(e.getKey())),
                0, 0, 0));
        }
        int pareadas = 0;
        int invertidas = 0;
        for (LinhaSimplesKaraoke linha : linhasSimples) {
            String texto = linha.texto();
            if (texto == null || !texto.contains(QUEBRA_ASS)) {
                continue;
            }
            String[] partes = texto.split(java.util.regex.Pattern.quote(QUEBRA_ASS), 2);
            if (partes.length != 2 || partes[0].isBlank() || partes[1].isBlank()) {
                continue;
            }
            pareadas++;
            // O ORIGINAL (romaji) deve ficar em cima. Proporção de sílaba japonesa é a mesma
            // régua que o detector já usa para separar romaji de karaokê em inglês.
            // LIMITACAO DECLARADA (regra 22, auditoria 21/09/A3): este contador so mede a inversao
            // ROMAJI×nao-romaji. Nao mede inversao ingles×PT (os dois pontuam ~0 romaji), e para os
            // pares vindos de empilharCamadas a regua e a MESMA que os ordenou -> invertidas=0 por
            // construcao (nao ha inversao possivel neles, entao 0 e a resposta CERTA, so nao e uma
            // medida independente). Nao dispara AVISO falso; e telemetria, nao decisao de render.
            if (detectorKaraoke.proporcaoRomaji(partes[0]) < detectorKaraoke.proporcaoRomaji(partes[1])) {
                invertidas++;
            }
        }
        telemetriaKaraoke.publicarArquivo(nome, eventosEntrada, eventosSaida, pareadas, invertidas, porEstilo);
    }

    /** Fração da janela MENOR que precisa coincidir para dois eventos serem a mesma coisa. */
    private static final double SOBREPOSICAO_EMPILHADO = 0.80;

    /**
     * PROPÓSITO DE NEGÓCIO: colapsa a tipografia EMPILHADA — o mesmo texto renderizado várias
     * vezes no mesmo instante, em camadas diferentes, para compor halo, sombra e texto.
     *
     * <h2>Por que o critério é a LAYER, e não o estilo</h2>
     * A tentação era alargar {@code eEstiloDeMusica} para pegar {@code Signs}. Medido em
     * 05/08/2026 nos 23 episódios do 86, isso estava errado por dois lados: o nome do estilo varia
     * por release ({@code Signs}, {@code Signs----1}, {@code Sign-------3}) e há sobreposição
     * legítima <b>dentro</b> de estilos de diálogo.
     *
     * <p>O que separa sem ambiguidade é a LAYER do ASS. Duas pessoas dizendo a mesma coisa no
     * mesmo instante ficam na MESMA layer; composição tipográfica usa layers DISTINTAS. Números
     * do 86:
     * <pre>
     *   ESTILO     eventos  sobrepoe  EMPILHADOS(layer distinta)
     *   Opening      59335     51217       47908
     *   Ending       12691     11528       11528
     *   Signs          651       432         326
     *   Default       7006         2           0   &lt;- mesma layer, intocado
     * </pre>
     * Sobreposição sozinha marcaria 63.179; com a layer, 59.762. Os 3.417 de diferença são
     * exatamente o que não se pode tocar.
     *
     * <h2>Qual camada sobrevive, e por quê</h2>
     * A de MAIOR layer. Convenção do ASS: layer mais alta renderiza por cima. Confirmado no
     * cartão de data do 86, cujas três camadas são, em ordem:
     * <pre>
     *   layer 0   \1a&amp;HFF&amp;  \3a&amp;H30&amp;  \blur5   -> preenchimento TRANSPARENTE = halo
     *   layer 1   \c&amp;H000000&amp;  \bord1  \blur0.5   -> preto = sombra
     *   layer 2   (sem cor)                        -> herda &amp;H00FFFFFF do estilo = TEXTO
     * </pre>
     * Guardar a primeira renderizaria o halo invisível; a segunda, texto preto. Só a última é
     * legível sozinha.
     *
     * <h2>Invariantes do domínio</h2>
     * <ul>
     *   <li>Só colapsa com texto visível IDÊNTICO, sobreposição ≥ 80% e layer DIFERENTE. Faltando
     *       qualquer um dos três, os eventos passam intactos.</li>
     *   <li>Texto visível vazio nunca colapsa: evento decorativo puro não é camada de nada.</li>
     *   <li>A ordem dos sobreviventes é preservada.</li>
     * </ul>
     *
     * <h2>Comportamento em caso de falha</h2>
     * Lista vazia ou sem empilhamento devolve a mesma lista; nunca lança.
     */
    private List<EventoAss> colapsarEmpilhados(List<EventoAss> eventos) {
        if (eventos.size() < 2) {
            return eventos;
        }
        List<EventoAss> ordenados = new ArrayList<>(eventos);
        ordenados.sort(Comparator.comparingLong(EventoAss::inicioCs));
        boolean[] descartado = new boolean[ordenados.size()];
        int colapsados = 0;

        for (int i = 0; i < ordenados.size(); i++) {
            if (descartado[i]) {
                continue;
            }
            EventoAss base = ordenados.get(i);
            String visivel = limparArtefatosVisiveis(base.textoVisivel());
            if (visivel.isBlank()) {
                continue;
            }
            List<Integer> grupo = new ArrayList<>();
            grupo.add(i);
            boolean layerDistinta = false;
            for (int j = i + 1; j < ordenados.size(); j++) {
                EventoAss outro = ordenados.get(j);
                if (outro.inicioCs() > base.fimCs()) {
                    break;   // ordenado por início: daqui em diante não há sobreposição
                }
                if (descartado[j]
                    || !limparArtefatosVisiveis(outro.textoVisivel()).equals(visivel)
                    || sobreposicaoJanela(base, outro) < SOBREPOSICAO_EMPILHADO
                    || !mesmoLugar(base, outro)) {
                    continue;
                }
                grupo.add(j);
                if (outro.camada() != base.camada()) {
                    layerDistinta = true;
                }
            }
            if (grupo.size() < 2 || !layerDistinta) {
                continue;
            }
            int sobrevivente = grupo.stream()
                .max(Comparator.comparingInt(k -> ordenados.get(k).camada()))
                .orElse(i);
            for (int k : grupo) {
                if (k != sobrevivente) {
                    descartado[k] = true;
                    colapsados++;
                }
            }
        }

        if (colapsados == 0) {
            return eventos;
        }
        List<EventoAss> restantes = new ArrayList<>();
        for (int i = 0; i < ordenados.size(); i++) {
            if (!descartado[i]) {
                restantes.add(ordenados.get(i));
            }
        }
        logStream.publicarLog(CANAL_LOG, String.format(
            "   Tipografia empilhada achatada: %d evento(s) removido(s), %d preservado(s).",
            colapsados, restantes.size()));
        return restantes;
    }

    /**
     * PROPÓSITO DE NEGÓCIO: duas camadas só são a MESMA tipografia empilhada quando estão no mesmo
     * lugar da tela: mesmo {@code \pos}/{@code \move} (tolerância de {@value #TOLERANCIA_POSICAO_PX}
     * px) e mesmo {@code \clip}. Até 28/10/2026 o colapso olhava só texto, janela e layer: a placa
     * dividida em duas metades por {@code \clip} perdia uma metade, e duas placas iguais em lados
     * opostos da tela viravam uma (Patlabor 03 e 05: centenas de camadas de textura em outra posição).
     *
     * <p>COMPORTAMENTO EM CASO DE FALHA: sem {@code \pos} nos dois, a posição não distingue (vale o
     * resto do critério); com {@code \pos} só num deles, não é o mesmo lugar.
     */
    private static boolean mesmoLugar(EventoAss a, EventoAss b) {
        double[] pa = coordenadaPrincipal(a.texto());
        double[] pb = coordenadaPrincipal(b.texto());
        boolean semA = Double.isNaN(pa[0]);
        boolean semB = Double.isNaN(pb[0]);
        if (semA != semB) {
            return false;
        }
        if (!semA && (Math.abs(pa[0] - pb[0]) > TOLERANCIA_POSICAO_PX || Math.abs(pa[1] - pb[1]) > TOLERANCIA_POSICAO_PX)) {
            return false;
        }
        return java.util.Objects.equals(recorte(a.texto()), recorte(b.texto()));
    }

    private static final double TOLERANCIA_POSICAO_PX = 2.0;
    private static final Pattern PADRAO_RECORTE = Pattern.compile("\\\\i?clip\\(([^)]*)\\)");

    private static String recorte(String texto) {
        if (texto == null) {
            return null;
        }
        Matcher m = PADRAO_RECORTE.matcher(texto);
        return m.find() ? m.group(1).replace(" ", "") : null;
    }

    /** Fração da janela MENOR coberta pela interseção das duas. */
    private static double sobreposicaoJanela(EventoAss a, EventoAss b) {
        long inicio = Math.max(a.inicioCs(), b.inicioCs());
        long fim = Math.min(a.fimCs(), b.fimCs());
        long comum = Math.max(0, fim - inicio);
        long menor = Math.max(1, Math.min(a.fimCs() - a.inicioCs(), b.fimCs() - b.inicioCs()));
        return (double) comum / menor;
    }

    /**
     * Reconstrói as frases de um estilo musical. Candidatas a "linha inteira"
     * têm ao menos duas palavras; fragmentos silábicos do KFX são descartados
     * (estão sempre dentro da janela de uma linha inteira). Se algum evento
     * musical ficar fora de qualquer linha reconstruída, ele é preservado
     * intacto — nunca se perde um trecho de música.
     */
    private void processarEstiloMusical(
        String estilo,
        List<EventoAss> eventosEstilo,
        List<LinhaSimplesKaraoke> linhasSimples,
        List<EventoAss> preservados,
        ResultadoConversaoKaraoke resultado,
        Map<String, VeredictoPalavra> idioma
    ) {
        // agrupa candidatas por texto visível, separando repetições distantes (refrão)
        Map<String, List<EventoAss>> porTexto = new LinkedHashMap<>();
        // Companhia que faz de uma palavra uma SÍLABA é outra palavra solta (as sílabas do KFX
        // convivem entre si). A frase inteira do mesmo estilo — a tradução no mesmo estilo do romaji,
        // Reconguista IV "wasurerarenai" — só não conta quando a palavra é ROMAJI: até 28/10/2026
        // contava sempre, e o romaji sumia deixando o PT sozinho na tela. Para palavra que não é
        // romaji ("Claro!", "Set." no 0083 E02, medido no acervo) a regra antiga continua: com a
        // frase na tela ela é sílaba, e empilhá-la poluía a abertura.
        java.util.Set<EventoAss> acompanhados = acompanhadosNaTela(eventosEstilo);
        java.util.Set<EventoAss> acompanhadosPorPalavraSolta = acompanhadosNaTela(eventosEstilo.stream()
            .filter(e -> contarPalavras(limparArtefatosVisiveis(e.textoVisivel())) <= 1)
            .toList());
        for (EventoAss evento : eventosEstilo) {
            if (ehDesenho(evento)) {
                continue;
            }
            String visivel = limparArtefatosVisiveis(evento.textoVisivel());
            if (contarPalavras(visivel) >= 2
                    || versoDeUmaPalavraSozinho(evento, visivel, acompanhados, acompanhadosPorPalavraSolta)) {
                porTexto.computeIfAbsent(visivel, k -> new ArrayList<>()).add(evento);
            }
        }

        List<Grupo> grupos = new ArrayList<>();
        for (Map.Entry<String, List<EventoAss>> entrada : porTexto.entrySet()) {
            List<EventoAss> doTexto = new ArrayList<>(entrada.getValue());
            doTexto.sort(Comparator.comparingLong(EventoAss::inicioCs));
            Grupo atual = null;
            for (EventoAss evento : doTexto) {
                // Duas ocorrências INTEIRAS e seguidas da mesma frase são dois versos (a música
                // repete a linha), não cópias da animação. Fundidas, a linha de 10 s casava com o
                // primeiro verso romaji e o segundo aparecia SEM tradução, com o anterior ainda
                // aceso — medido no ED do Unicorn E01 (23:14–23:24), 3 linhas na tela.
                if (atual != null && evento.inicioCs() - atual.fimCs <= GAP_MESMA_FRASE_CS
                        && !atual.repeteVersoInteiro(evento)) {
                    atual.absorver(evento);
                } else {
                    // chave achatada p/ agrupar; saída preserva \N SÓ no par bilíngue (original\NPT)
                    atual = new Grupo(entrada.getKey(), textoSaidaDoEvento(evento, idioma), evento);
                    grupos.add(atual);
                }
            }
        }

        // funde variantes divergentes (mesma janela, texto parecido) por voto majoritário
        grupos.sort(Comparator.comparingLong(g -> g.inicioCs));
        List<Grupo> finais = new ArrayList<>();
        for (Grupo grupo : grupos) {
            Grupo variante = null;
            for (Grupo candidato : finais) {
                if (sobreposicao(grupo, candidato) >= SOBREPOSICAO_MINIMA_VARIANTE
                        && similaridadeTokens(grupo.texto, candidato.texto) >= SIMILARIDADE_MINIMA_TOKENS) {
                    variante = candidato;
                    break;
                }
            }
            if (variante == null) {
                finais.add(grupo);
            } else {
                variante.fundirVariante(grupo);
                logStream.publicarLog(CANAL_LOG, String.format(Locale.ROOT,
                    "   [VOTO] %s: variantes divergentes na janela %s — mantida a majoritária (\"%s\")",
                    estilo, variante.janela(), resumir(variante.texto)));
            }
        }

        for (Grupo grupo : finais) {
            LinhaSimplesKaraoke linha = grupo.paraLinha();
            linhasSimples.add(linha);
            logStream.publicarLog(CANAL_LOG, String.format(Locale.ROOT,
                "   [TROCA] %s %s–%s | %d eventos KFX → 1 linha: \"%s\"",
                estilo, linha.inicioAss(), linha.fimAss(), grupo.totalEventosJanela(eventosEstilo), resumir(linha.texto())));
        }

        List<EventoAss> semCoberturaInicial = eventosEstilo.stream()
            .filter(evento -> !ehDesenho(evento))
            .filter(evento -> finais.stream().noneMatch(g -> cobre(g, evento)))
            .toList();
        List<GrupoFragmentos> gruposFragmentos = reconstruirFragmentosKfx(estilo, semCoberturaInicial);
        for (GrupoFragmentos grupo : gruposFragmentos) {
            LinhaSimplesKaraoke linha = grupo.paraLinha();
            linhasSimples.add(linha);
            logStream.publicarLog(CANAL_LOG, String.format(Locale.ROOT,
                "   [KFX LIMPO] %s %s–%s | %d fragmentos animados → 1 linha simples: \"%s\"",
                estilo, linha.inicioAss(), linha.fimAss(), linha.eventosOrigem(), resumir(linha.texto())));
        }

        int removidos = 0;
        List<EventoAss> semCobertura = new ArrayList<>();
        for (EventoAss evento : eventosEstilo) {
            boolean coberto = finais.stream().anyMatch(g -> cobre(g, evento))
                || gruposFragmentos.stream().anyMatch(g -> g.cobre(evento));
            boolean efeitoVazio = ehDesenho(evento)
                || eEventoKfxSimplificavel(evento) && limparArtefatosVisiveis(evento.textoVisivel()).isBlank();
            if (coberto || efeitoVazio) {
                removidos++;
            } else {
                semCobertura.add(evento);
            }
        }
        for (int i = 0; i < removidos; i++) {
            resultado.incrementarKaraokeRemovido();
        }
        if (!semCobertura.isEmpty()) {
            preservados.addAll(semCobertura);
            resultado.adicionarPreservadosPorSeguranca(semCobertura.size());
            String aviso = String.format(Locale.ROOT,
                "Estilo \"%s\": %d eventos de música sem linha inteira correspondente foram PRESERVADOS intactos (KFX apenas silábico?).",
                estilo, semCobertura.size());
            resultado.adicionarAviso(aviso);
            logStream.publicarLog(CANAL_LOG, "   [AVISO] " + aviso);
        }
    }

    /**
     * PROPÓSITO DE NEGÓCIO: um verso de UMA palavra que é a letra inteira daquele momento — o
     * {@code "yasashikatta"} do encerramento do Guilty Crown — e não uma sílaba de KFX.
     *
     * <h2>O prejuízo que originou, medido em 24/09/2026</h2>
     * Só frase de 2+ palavras virava linha simples. O verso de uma palavra ficava preservado no
     * estilo original, animado e no canto da tela, enquanto a sua tradução ("e gentilmente") ia para
     * a linha limpa no topo — os dois em lugares diferentes, ao contrário de todos os outros versos.
     *
     * <p>INVARIANTES DO DOMÍNIO: exige as três coisas — uma palavra, verso inteiro (dura ao menos
     * {@link #DURACAO_VERSO_INTEIRO_CS}) e NENHUM outro evento do mesmo estilo na tela ao mesmo
     * tempo. A sílaba de KFX falha na última: ela convive com a frase e com as outras sílabas (os
     * fragmentos da OPL2 do Unicorn ficam acesos até a frase acabar).
     *
     * <p>COMPORTAMENTO EM CASO DE FALHA: texto em branco devolve {@code false}. Nunca lança.
     */
    private boolean versoDeUmaPalavraSozinho(EventoAss evento, String visivel, java.util.Set<EventoAss> acompanhados,
                                             java.util.Set<EventoAss> acompanhadosPorPalavraSolta) {
        // Dois pisos achados pela revisão do acervo inteiro (24/09/2026): no DanMachi S00E04 a nota
        // sustentada "m" de "Dream" (0:02:54.70–0:03:02.79) só existe na camada de brilho e as
        // outras letras têm duração ZERO — ninguém divide a tela com ela, e a letra "m" sozinha
        // virava linha limpa por 8 s. Evento carimbado pelo Kara Templater (Effect "fx") é
        // maquinaria de sílaba, nunca verso inteiro; e uma letra só nunca é verso.
        return !visivel.isBlank() && contarPalavras(visivel) == 1 && ehVersoInteiro(evento)
            && visivel.codePointCount(0, visivel.length()) >= 2
            && !detectorKaraoke.efeitoDeclaraKaraoke(evento.efeito())
            && !acompanhadosPorPalavraSolta.contains(evento)
            && (!acompanhados.contains(evento) || detectorKaraoke.proporcaoRomaji(visivel) >= LIMIAR_ROMAJI_SAIDA);
    }

    /**
     * Os eventos do estilo que dividem a tela com OUTRO evento do mesmo estilo (janelas que se
     * cruzam). Uma varredura por início: cruza com um anterior se o maior fim visto até ali passa
     * do seu início; com um posterior se o próximo começa antes do seu fim. O(n log n) — o estilo de
     * KFX letra a letra tem dezenas de milhares de eventos, e comparar todos com todos travaria.
     *
     * <p>A CÓPIA de tipografia (mesmo texto, mesma janela — a camada de contorno e a de preenchimento
     * do fansub) não conta como companhia: é o mesmo verso desenhado duas vezes. Medido em
     * 24/09/2026 no 08th E05: "itoshii" e "Ikutsumono" vêm em layer 0 e 1 idênticas, cada cópia
     * "acompanhava" a outra, o romaji não virava linha e a tradução ia sozinha para o topo.
     */
    private static java.util.Set<EventoAss> acompanhadosNaTela(List<EventoAss> doEstilo) {
        java.util.Map<String, List<EventoAss>> copias = new LinkedHashMap<>();
        for (EventoAss e : doEstilo) {
            copias.computeIfAbsent(e.inicioCs() + "|" + e.fimCs() + "|" + e.textoVisivel(), k -> new ArrayList<>()).add(e);
        }
        List<List<EventoAss>> ordenados = new ArrayList<>(copias.values());
        ordenados.sort(Comparator.comparingLong((List<EventoAss> g) -> g.getFirst().inicioCs())
            .thenComparingLong(g -> g.getFirst().fimCs()));
        java.util.Set<EventoAss> acompanhados = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        long maiorFimAnterior = Long.MIN_VALUE;
        for (int i = 0; i < ordenados.size(); i++) {
            EventoAss e = ordenados.get(i).getFirst();
            boolean cruzaAnterior = maiorFimAnterior > e.inicioCs();
            boolean cruzaPosterior = i + 1 < ordenados.size() && ordenados.get(i + 1).getFirst().inicioCs() < e.fimCs();
            if (cruzaAnterior || cruzaPosterior) {
                acompanhados.addAll(ordenados.get(i));
            }
            maiorFimAnterior = Math.max(maiorFimAnterior, e.fimCs());
        }
        return acompanhados;
    }

    private List<GrupoFragmentos> reconstruirFragmentosKfx(String estilo, List<EventoAss> eventos) {
        List<FragmentoKfx> fragmentos = new ArrayList<>();
        int ordem = 0;
        for (EventoAss evento : eventos) {
            if (!eEventoKfxSimplificavel(evento)) {
                continue;
            }
            double[] coordenada = coordenadaPrincipal(evento.texto());
            String texto = limparArtefatosVisiveis(evento.textoVisivel());
            fragmentos.add(new FragmentoKfx(evento, texto, coordenada[0], coordenada[1], ordem++));
        }
        fragmentos.sort(Comparator
            .comparingInt(FragmentoKfx::faixaVertical)
            .thenComparingLong(f -> f.evento().inicioCs())
            .thenComparingDouble(FragmentoKfx::xOrdenacao)
            .thenComparingInt(FragmentoKfx::ordem));
        if (fragmentos.isEmpty()) {
            return List.of();
        }

        List<GrupoFragmentos> brutos = new ArrayList<>();
        GrupoFragmentos atual = null;
        for (FragmentoKfx fragmento : fragmentos) {
            EventoAss evento = fragmento.evento();
            if (atual == null
                    || atual.faixaVertical != fragmento.faixaVertical()
                    || evento.inicioCs() - atual.fimCs > GAP_FRAGMENTOS_KFX_CS) {
                if (atual != null) {
                    brutos.add(atual);
                }
                atual = new GrupoFragmentos(fragmento);
            } else {
                atual.absorver(fragmento);
            }
        }
        if (atual != null) {
            brutos.add(atual);
        }

        List<GrupoFragmentos> grupos = new ArrayList<>();
        for (GrupoFragmentos bruto : brutos) {
            List<GrupoFragmentos> frases = bruto.subdividirPorFrase();
            if (frases.size() > 1) {
                logStream.publicarLog(CANAL_LOG, String.format(Locale.ROOT,
                    "   [FRASES] %s %s–%s: KFX letra-por-letra contínuo dividido em %d frases pelos vales de concorrência.",
                    estilo, EventoAss.csParaTempo(bruto.inicioCs), EventoAss.csParaTempo(bruto.fimCs), frases.size()));
            }
            for (GrupoFragmentos frase : frases) {
                if (!frase.eLinhaUtil()) {
                    continue;
                }
                LinhaSimplesKaraoke linha = frase.paraLinha();
                if (linha.fimCs() - linha.inicioCs() > DURACAO_MAXIMA_LINHA_FRAGMENTO_CS
                        || linha.texto().length() > TEXTO_MAXIMO_LINHA_FRAGMENTO) {
                    logStream.publicarLog(CANAL_LOG, String.format(Locale.ROOT,
                        "   [AVISO] %s %s–%s: linha reconstruída implausível (%ds, %d caracteres) — bloco KFX preservado intacto.",
                        estilo, linha.inicioAss(), linha.fimAss(),
                        (linha.fimCs() - linha.inicioCs()) / 100, linha.texto().length()));
                    continue;
                }
                grupos.add(frase);
            }
        }

        if (!grupos.isEmpty()) {
            String aviso = String.format(Locale.ROOT,
                "Estilo \"%s\": linha inteira não encontrada em parte do KFX; reconstrução por fragmentos ativada para remover animações pesadas.",
                estilo);
            logStream.publicarLog(CANAL_LOG, "   [INFO] " + aviso);
        }
        return grupos;
    }

    private boolean eEventoKfxSimplificavel(EventoAss evento) {
        return detectorKaraoke.temTagKaraoke(evento.texto()) || detectorKaraoke.eEfeitoKaraoke(evento.texto());
    }

    private static double[] coordenadaPrincipal(String texto) {
        if (texto == null) {
            return new double[] {Double.NaN, Double.NaN};
        }
        Matcher matcher = PADRAO_COORDENADA_PRINCIPAL.matcher(texto);
        if (!matcher.find()) {
            return new double[] {Double.NaN, Double.NaN};
        }
        return new double[] {Double.parseDouble(matcher.group(1)), Double.parseDouble(matcher.group(2))};
    }

    /**
     * PROPÓSITO DE NEGÓCIO: o evento PERTENCE ao verso do grupo — além de estar na janela, o texto
     * dele é parte do texto do verso (a sílaba "ki" de "kimi no koe", a cópia de brilho do verso
     * inteiro). Até 28/10/2026 bastava a janela: o "(Hey!)" concorrente do mesmo estilo era removido
     * como se fosse sílaba, e um fragmento logo depois do fim partia o bloco KFX ("nunca se perde um
     * trecho de música", diz o Javadoc de processarEstiloMusical).
     *
     * <p>INVARIANTES DO DOMÍNIO: comparação pelas letras do texto, sem espaço nem pontuação nem
     * caixa; evento sem letra visível (decoração) pertence a qualquer verso da janela.
     *
     * <p>COMPORTAMENTO EM CASO DE FALHA: nunca lança.
     */
    private static boolean cobre(Grupo grupo, EventoAss evento) {
        if (evento.inicioCs() >= grupo.fimCs + MARGEM_COBERTURA_CS
            || evento.fimCs() <= grupo.inicioCs - MARGEM_COBERTURA_CS) {
            return false;
        }
        String doEvento = letras(limparArtefatosVisiveis(evento.textoVisivel()));
        // Vale o texto de QUALQUER variante absorvida: a variante divergente que perdeu o voto
        // ("...que desmoronaram" × "...que se desfizeram", 0083) pertence ao mesmo verso e sai com
        // ele — sem isto ela ficava crua na tela (medido no acervo, 28/10/2026, antes de entrar).
        return doEvento.isEmpty() || grupo.textosDasVariantes.stream().anyMatch(t -> letras(t).contains(doEvento));
    }

    /** Só as letras e dígitos, em minúsculas — a forma de comparar sílaba, cópia e verso. */
    private static String letras(String texto) {
        return texto == null ? "" : texto.toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\d]", "");
    }

    /**
     * PROPÓSITO DE NEGÓCIO: o evento é DESENHO vetorial ({@code \p1}...), não texto: "m 0 0 l 0 -10"
     * é coordenada. Até 28/10/2026 a partícula do KFX contava duas palavras, virava candidata a verso
     * e se juntava a qualquer letra da janela, numa linha de até 84 s com as coordenadas na tela.
     *
     * <p>COMPORTAMENTO EM CASO DE FALHA: texto nulo devolve {@code false}.
     */
    private static boolean ehDesenho(EventoAss evento) {
        return evento.texto() != null && PADRAO_DESENHO.matcher(evento.texto()).find();
    }

    private List<String> montarSaida(
        List<String> cabecalho,
        int idxUltimoEstilo,
        int playResY,
        List<EventoAss> dialogo,
        List<EventoAss> preservados,
        List<LinhaSimplesKaraoke> linhasSimples
    ) {
        List<String> saida = new ArrayList<>(cabecalho);
        if (!linhasSimples.isEmpty()) {
            String estiloNovo = definirEstiloSimples(playResY);
            if (idxUltimoEstilo >= 0) {
                saida.add(idxUltimoEstilo + 1, estiloNovo);
            } else {
                saida.add(estiloNovo);
            }
        }

        record Emissao(long inicioCs, int ordem, String linha) {}
        List<Emissao> emissoes = new ArrayList<>();
        int ordem = 0;
        for (EventoAss e : dialogo) {
            emissoes.add(new Emissao(e.inicioCs(), ordem++, e.linhaCrua()));
        }
        for (EventoAss e : preservados) {
            emissoes.add(new Emissao(e.inicioCs(), ordem++, e.linhaCrua()));
        }
        for (LinhaSimplesKaraoke linha : linhasSimples) {
            emissoes.add(new Emissao(linha.inicioCs(), ordem++, linha.paraEventoAss(NOME_ESTILO_SIMPLES)));
        }
        emissoes.sort(Comparator.comparingLong(Emissao::inicioCs).thenComparingInt(Emissao::ordem));
        // Os eventos vão para o FIM DA SEÇÃO [Events], não do arquivo: com [Aegisub Extradata] depois
        // de [Events] (30 de 324 arquivos medidos), todo o diálogo ia parar dentro do Extradata — fora
        // da especificação, e com Comment fora de lugar (auditoria de 28/10/2026).
        int insercao = fimDaSecaoDeEventos(saida);
        List<String> linhasDeEvento = emissoes.stream().map(Emissao::linha).toList();
        saida.addAll(insercao, linhasDeEvento);
        return saida;
    }

    /**
     * PROPÓSITO DE NEGÓCIO: onde termina a seção {@code [Events]} — antes da próxima seção e das
     * linhas em branco que a precedem.
     *
     * <p>COMPORTAMENTO EM CASO DE FALHA: sem {@code [Events]}, ou sem seção depois dela, o fim do
     * arquivo (o comportamento de antes).
     */
    private static int fimDaSecaoDeEventos(List<String> linhas) {
        int eventos = -1;
        for (int i = 0; i < linhas.size(); i++) {
            if (linhas.get(i).strip().equalsIgnoreCase("[Events]")) {
                eventos = i;
                break;
            }
        }
        if (eventos < 0) {
            return linhas.size();
        }
        for (int i = eventos + 1; i < linhas.size(); i++) {
            if (PADRAO_SECAO.matcher(linhas.get(i)).find()) {
                int fim = i;
                while (fim > eventos + 1 && linhas.get(fim - 1).isBlank()) {
                    fim--;
                }
                return fim;
            }
        }
        return linhas.size();
    }

    /**
     * Estilo da legenda simples: topo da tela ({@code an8}), fonte Arial (sempre
     * disponível — evita o problema de fontes legadas de fansub), tamanho
     * proporcional ao PlayResY do arquivo.
     */
    private String definirEstiloSimples(int playResY) {
        int base = playResY > 0 ? playResY : 360;
        int tamanho = Math.max(14, Math.round(base / 22.0f));
        int margemV = Math.max(8, Math.round(base / 54.0f));
        return "Style: " + NOME_ESTILO_SIMPLES + ",Arial," + tamanho
            + ",&H00FFFFFF,&H000000FF,&H00000000,&H96000000,0,-1,0,0,100,100,0,0,1,2,1,8,30,30," + margemV + ",1";
    }

    private List<LinhaSimplesKaraoke> deduplicarLinhasSimples(
        List<LinhaSimplesKaraoke> linhas,
        ResultadoConversaoKaraoke resultado,
        Map<String, VeredictoPalavra> idioma
    ) {
        // camadas simultâneas (romaji em cima, tradução embaixo) raramente têm
        // janelas byte-idênticas — o agrupamento é por sobreposição de janela.
        // A janela de referência é a da primeira linha do grupo (sem expandir),
        // para uma linha longa ruim não encadear frases consecutivas.
        List<LinhaSimplesKaraoke> ordenadas = new ArrayList<>(linhas);
        ordenadas.sort(Comparator.comparingLong(LinhaSimplesKaraoke::inicioCs)
            .thenComparingLong(LinhaSimplesKaraoke::fimCs));
        List<List<LinhaSimplesKaraoke>> porJanela = new ArrayList<>();
        for (LinhaSimplesKaraoke linha : ordenadas) {
            List<LinhaSimplesKaraoke> alvo = null;
            for (List<LinhaSimplesKaraoke> grupo : porJanela) {
                LinhaSimplesKaraoke referencia = grupo.getFirst();
                if (sobreposicaoJanelas(referencia.inicioCs(), referencia.fimCs(),
                        linha.inicioCs(), linha.fimCs()) >= SOBREPOSICAO_MINIMA_VARIANTE) {
                    alvo = grupo;
                    break;
                }
            }
            if (alvo == null) {
                alvo = new ArrayList<>();
                porJanela.add(alvo);
            }
            alvo.add(linha);
        }

        List<LinhaSimplesKaraoke> deduplicadas = new ArrayList<>();
        int removidas = 0;
        int empilhadas = 0;
        for (List<LinhaSimplesKaraoke> grupo : porJanela) {
            if (grupo.size() == 1) {
                deduplicadas.add(grupo.getFirst());
                continue;
            }
            // Mesma janela NÃO significa mesma frase: o karaokê traz romaji e tradução no mesmo
            // tempo. Só é duplicata o que também tem TEXTO parecido — variantes pulverizadas do
            // KFX. Camadas de línguas diferentes são conteúdo distinto e as duas ficam.
            List<List<LinhaSimplesKaraoke>> camadas = agruparPorTextoParecido(grupo);
            List<LinhaSimplesKaraoke> representantes = new ArrayList<>();
            for (List<LinhaSimplesKaraoke> camada : camadas) {
                representantes.add(melhorDaCamada(camada));
                removidas += camada.size() - 1;
            }
            if (representantes.size() == 1) {
                LinhaSimplesKaraoke melhor = representantes.getFirst();
                deduplicadas.add(melhor);
                logStream.publicarLog(CANAL_LOG, String.format(Locale.ROOT,
                    "   [DEDUP] %s–%s | %d variante(s) do mesmo verso → mantida: \"%s\"",
                    melhor.inicioAss(), melhor.fimAss(), grupo.size(), resumir(melhor.texto())));
                continue;
            }
            LinhaSimplesKaraoke empilhada = empilharCamadas(representantes, idioma);
            deduplicadas.add(empilhada);
            empilhadas++;
            logStream.publicarLog(CANAL_LOG, String.format(Locale.ROOT,
                "   [CAMADAS] %s–%s | %d camadas preservadas juntas: \"%s\"",
                empilhada.inicioAss(), empilhada.fimAss(), representantes.size(),
                resumir(empilhada.texto())));
        }

        if (removidas > 0) {
            String aviso = removidas + " variante(s) do mesmo verso removida(s) por deduplicação.";
            resultado.adicionarAviso(aviso);
            logStream.publicarLog(CANAL_LOG, "   [AVISO] " + aviso);
        }
        if (empilhadas > 0) {
            String aviso = empilhadas + " verso(s) mantiveram as duas camadas (original em cima, tradução embaixo).";
            resultado.adicionarAviso(aviso);
            logStream.publicarLog(CANAL_LOG, "   [INFO] " + aviso);
        }
        return deduplicadas;
    }

    /**
     * PROPÓSITO DE NEGÓCIO: separa, dentro de uma mesma janela de tempo, o que é o MESMO verso
     * escrito de formas ligeiramente diferentes (variantes pulverizadas do KFX) do que são CAMADAS
     * de línguas distintas (romaji e tradução). Só o primeiro caso é duplicata.
     *
     * <p>INVARIANTES DO DOMÍNIO: usa o mesmo critério de similaridade de tokens que a fusão de
     * variantes já aplica no bloco musical; a ordem original de chegada é preservada dentro de cada
     * camada, o que mantém a decisão determinística.
     */
    private List<List<LinhaSimplesKaraoke>> agruparPorTextoParecido(List<LinhaSimplesKaraoke> grupo) {
        List<List<LinhaSimplesKaraoke>> camadas = new ArrayList<>();
        for (LinhaSimplesKaraoke linha : grupo) {
            List<LinhaSimplesKaraoke> alvo = null;
            for (List<LinhaSimplesKaraoke> camada : camadas) {
                // A variante PULVERIZADA ("k i m i n o k o e") tem as mesmas letras do verso e
                // nenhuma palavra em comum: sem esta comparação ela virava camada própria e empilhava
                // (auditoria de 28/10/2026, D5), contra a doc ("romaji original vence pulverizado").
                if (similaridadeTokens(camada.getFirst().texto(), linha.texto()) >= SIMILARIDADE_MINIMA_TOKENS
                        || mesmaLetraPulverizada(camada.getFirst().texto(), linha.texto())) {
                    alvo = camada;
                    break;
                }
            }
            if (alvo == null) {
                alvo = new ArrayList<>();
                camadas.add(alvo);
            }
            alvo.add(linha);
        }
        return camadas;
    }

    /** Uma das duas é pulverizada e as letras, sem espaço, são as mesmas. */
    private static boolean mesmaLetraPulverizada(String a, String b) {
        return (pareceTextoPulverizado(a) || pareceTextoPulverizado(b)) && !letras(a).isEmpty() && letras(a).equals(letras(b));
    }

    private LinhaSimplesKaraoke melhorDaCamada(List<LinhaSimplesKaraoke> camada) {
        return camada.stream()
            .min(Comparator.comparingInt(this::pontuarPreferenciaLinha)
                .thenComparing(Comparator.comparingInt((LinhaSimplesKaraoke l) -> l.eventosOrigem()).reversed()))
            .orElse(camada.getFirst());
    }

    /**
     * PROPÓSITO DE NEGÓCIO: junta as camadas simultâneas em UM evento, com quebra {@code \N} —
     * romaji em cima, tradução embaixo. Regra de negócio do Paulo: no karaokê se remove a frescura
     * visual (KFX, animação vetorial), preserva-se o romaji porque é a língua original, e traduz-se
     * apenas a camada em inglês. Manter as duas em eventos separados no mesmo tempo faria uma
     * imprimir sobre a outra, já que a saída usa um único estilo.
     *
     * <p>INVARIANTES DO DOMÍNIO: a janela resultante cobre as duas camadas (menor início, maior
     * fim); os contadores de origem são somados para a telemetria não perder eventos.
     */
    private LinhaSimplesKaraoke empilharCamadas(List<LinhaSimplesKaraoke> camadas, Map<String, VeredictoPalavra> idioma) {
        List<LinhaSimplesKaraoke> ordenadas = new ArrayList<>(camadas);
        // A MAIS japonesa em cima — por PROPORÇÃO, não por sim/não.
        //
        // Antes o critério era booleano e {@code pareceOriginalJaponesOuRomaji} exige 100% das
        // palavras serem sílaba Hepburn. Romaji real reprova nisso: "hitoribocchi", "tsudzuku",
        // "kekkyoku" e "icchatte" têm geminada/dígrafo que a regex de sílaba não cobre, e uma
        // palavra em seis derruba a linha inteira. As duas camadas empatavam em "não é romaji", o
        // sort ESTÁVEL preservava a ordem do arquivo, e o inglês subia.
        //
        // Medido no Guilty Crown em 2026-08-03: 33 dos 508 versos pareados (6%) saíram com a
        // tradução acima do original — sempre os MESMOS versos, repetidos em todos os episódios,
        // porque o defeito é do texto e não do arquivo. Na tela, a legenda troca de posição no
        // meio da música.
        //
        // A proporção é a mesma régua que o DetectorEfeitoKaraokeService já usa (limiar 70%),
        // criada justamente porque exigir 100% fazia o romaji desta obra vazar. Aqui não há
        // limiar: compara-se qual das camadas é MAIS romaji, que é a pergunta certa quando o
        // objetivo é ordenar duas linhas, não classificar uma.
        //
        // E, antes da proporcao, a CAMADA PORTUGUESA vai para baixo (24/09/2026): no 08th E05 a
        // traducao "A que eu amo." soa 75% romaji (a-que-eu-a-mo) e subiu acima de "Love!
        // Meguriaeta". Portugues = toda palavra reconhecida pelo pt_BR (o romaji fica DESCONHECIDA
        // ou cai no ingles). Sem dicionario (mapa vazio) ninguem e portugues e vale so a proporcao.
        ordenadas.sort(Comparator.comparingInt((LinhaSimplesKaraoke l) -> todaPortuguesa(l.texto(), idioma) ? 1 : 0)
            .thenComparingInt(l -> -detectorKaraoke.proporcaoRomaji(l.texto())));

        StringBuilder texto = new StringBuilder();
        long inicio = Long.MAX_VALUE;
        long fim = Long.MIN_VALUE;
        int eventos = 0;
        int variantes = 0;
        for (LinhaSimplesKaraoke linha : ordenadas) {
            if (!texto.isEmpty()) {
                texto.append("\\N");
            }
            texto.append(linha.texto());
            inicio = Math.min(inicio, linha.inicioCs());
            fim = Math.max(fim, linha.fimCs());
            eventos += linha.eventosOrigem();
            variantes += linha.variantesTexto();
        }
        return new LinhaSimplesKaraoke(texto.toString(), inicio, fim, eventos, variantes);
    }

    private int pontuarPreferenciaLinha(LinhaSimplesKaraoke linha) {
        String texto = linha.texto();
        int pontuacao = 0;
        if (temArtefatoColchetes(texto)) {
            pontuacao += 30;
        }
        if (pareceOriginalJaponesOuRomaji(texto)) {
            pontuacao += 100;
        }
        // Pulverizado perde SEMPRE, inclusive para o romaji (+100): a doc promete "romaji original
        // vence texto pulverizado", e com +60 a promessa era o contrário (auditoria de 28/10/2026).
        if (pareceTextoPulverizado(texto)) {
            pontuacao += 150;
        }
        pontuacao -= Math.min(8, contarPalavras(texto));
        pontuacao -= Math.min(20, pontuacaoPortugues(texto) * 4);
        if (NAO_ASCII_PATTERN.matcher(texto).find() && !PADRAO_JAPONES.matcher(texto).find()) {
            pontuacao -= 8;
        }
        return pontuacao;
    }

    private static String limparArtefatosVisiveis(String texto) {
        if (texto == null || texto.isBlank()) {
            return "";
        }
        String limpo = texto.replaceAll("(?i)\\[!?\\[?TAG\\d+\\]?\\]", "")
            .replace("[[]]", "")
            .replace("[[", "[")
            .replace("]]", "]");

        limpo = PADRAO_ARTEFATO_TAG_VISIVEL.matcher(limpo).replaceAll("");
        for (int i = 0; i < 4; i++) {
            limpo = limpo.replaceAll("\\[\\s*\\]", "");
        }
        return limpo
            .replaceAll("\\s+([,.!?;:])", "$1")
            .replaceAll("\\s{2,}", " ")
            .strip();
    }

    /** Proporção de sílaba japonesa acima da qual um lado é ROMAJI (mesma régua do detector). */
    private static final int LIMIAR_ROMAJI_SAIDA = 70;
    /**
     * Diacríticos EXCLUSIVOS do português (e latinos), SEM os macrons do romaji Hepburn
     * ({@code ā ī ū ē ō}): a presença de um destes num lado o marca como tradução PT, não original.
     */
    private static final Pattern DIACRITICO_PT = Pattern.compile("[çÇáàâãéêíóôõúüÁÀÂÃÉÊÍÓÔÕÚÜ]");

    /**
     * PROPÓSITO DE NEGÓCIO: o texto de SAÍDA da linha simples. Preserva a quebra {@code \N} SÓ
     * quando ela separa a letra ORIGINAL da sua TRADUÇÃO em português (o par bilíngue
     * {@code original\Ntradução} que o Passo 1 entrega num evento único — a abertura OPL2 do
     * Unicorn). Verso MONOLÍNGUE quebrado pelo fansub ({@code romaji\Nromaji},
     * {@code inglês\Ninglês} de {@code Song JP}/{@code ED}/{@code ED - EN}) ACHATA para uma linha —
     * comportamento histórico do simplificador.
     *
     * <h2>Por que só o par bilíngue, e não todo {@code \N}</h2>
     * A revisão adversarial de 21/09/2026 mediu: preservar todo {@code \N} musical mudava a saída de
     * 44 {@code Song JP} + 154 {@code ED - EN} + 13 {@code ED - Romaji} do acervo (verso de 1 linha
     * virava 2), poluía o contador {@code pareadas} da telemetria e — pior — um {@code \N} FINAL
     * virava LINHA VAZIA. A regra do Paulo é {@code original} em cima, {@code PT} embaixo: quebra sem
     * tradução do outro lado é layout do fansub, e o achatador existe para achatar.
     *
     * @param idioma classificação de idioma por palavra (dicionário Hunspell), montada em LOTE uma
     *               vez por arquivo em {@link #classificarIdiomaDosPares}; vazio quando o dicionário
     *               não está disponível, e aí a decisão cai no fallback do diacrítico.
     *
     * <p>COMPORTAMENTO EM CASO DE FALHA: nulo/branco devolve o visível achatado; nunca lança.
     */
    private String textoSaidaDoEvento(EventoAss evento, Map<String, VeredictoPalavra> idioma) {
        String comQuebra = normalizarQuebras(limparArtefatosVisiveis(evento.textoComQuebra()));
        if (!comQuebra.contains(QUEBRA_ASS)) {
            return comQuebra;
        }
        String[] partes = comQuebra.split(java.util.regex.Pattern.quote(QUEBRA_ASS));
        if (partes.length == 2 && ehParOriginalTraducao(partes[0], partes[1], idioma)) {
            return comQuebra; // par bilingue original\NtraducaoPT: preserva as duas linhas
        }
        if (partes.length >= 3) {
            Optional<String> par = parDeVersosMultiplos(partes, idioma);
            if (par.isPresent()) {
                return par.get();
            }
        }
        return limparArtefatosVisiveis(evento.textoVisivel()); // verso monolingue: achata (como antes)
    }

    /**
     * PROPÓSITO DE NEGÓCIO: o par bilíngue quando o ORIGINAL (ou a tradução) tem mais de um verso —
     * {@code "Take off my dress and crown,\Nthen I can fall sound asleep\NTire minha roupa..."}. Sai
     * em DUAS linhas: os versos originais unidos em cima, os portugueses unidos embaixo.
     *
     * <h2>O prejuízo que originou, medido em 24/09/2026</h2>
     * Só o caso de EXATAMENTE duas partes era reconhecido. Com o verso original já quebrado pelo
     * fansub, o Passo 1 entregava três partes e o achatador colava inglês e português numa linha só —
     * na tela do Unicorn E01 (23:12) e E14 (22:57, 23:01, 23:05): "...fall sound asleep Tire / minha
     * roupa e coroa...", a frase portuguesa quebrada no meio pelo renderizador.
     *
     * <p>INVARIANTES DO DOMÍNIO: cada parte é rotulada pelos MESMOS instrumentos do par de duas
     * partes (proporção de romaji e dicionário). É par sse a sequência for "não-português" seguido de
     * "português", com pelo menos uma parte de cada lado — qualquer outra ordem (português em cima,
     * idiomas intercalados) NÃO é par e achata como antes. O caso de duas partes não passa por aqui.
     *
     * <p>COMPORTAMENTO EM CASO DE FALHA: sequência fora do padrão devolve {@link Optional#empty()} e
     * o chamador achata. Nunca lança.
     */
    private Optional<String> parDeVersosMultiplos(String[] partes, Map<String, VeredictoPalavra> idioma) {
        List<String> originais = new ArrayList<>();
        List<String> portugueses = new ArrayList<>();
        for (String parte : partes) {
            String p = parte.strip();
            if (p.isEmpty()) {
                continue;
            }
            if (ehPortugues(p, idioma)) {
                portugueses.add(p);
            } else if (portugueses.isEmpty()) {
                originais.add(p);
            } else {
                return Optional.empty(); // original DEPOIS de português: não é o par
            }
        }
        if (originais.isEmpty() || portugueses.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(String.join(" ", originais) + QUEBRA_ASS + String.join(" ", portugueses));
    }

    /**
     * PROPÓSITO DE NEGÓCIO: a camada é a tradução portuguesa? Toda palavra reconhecida pelo pt_BR
     * (PORTUGUES_OK/ACENTO_FALTANDO). Usada só para ORDENAR camadas — o pior erro possível aqui é a
     * ordem, nunca apagar texto.
     *
     * <p>COMPORTAMENTO EM CASO DE FALHA: mapa vazio ou sem palavra devolve {@code false}.
     */
    private static boolean todaPortuguesa(String texto, Map<String, VeredictoPalavra> idioma) {
        // Letra solta nao e palavra portuguesa: o pt_BR aceita "a", "e", "o", "t", "n", e o romaji
        // PULVERIZADO do 86 Part 2 ("t e i k a n, s o r e m o...") passava por "todo portugues" e
        // descia para baixo do ingles em 11 episodios — achado pela revisao do acervo, 24/09/2026.
        if (idioma == null || idioma.isEmpty() || pareceTextoPulverizado(texto)) {
            return false;
        }
        int palavras = 0;
        for (String palavra : CorretorOrtograficoLegenda.palavrasDe(texto)) {
            if (palavra.length() < 2) {
                continue;
            }
            VeredictoPalavra v = idioma.get(palavra);
            if (v != VeredictoPalavra.PORTUGUES_OK && v != VeredictoPalavra.ACENTO_FALTANDO) {
                return false;
            }
            palavras++;
        }
        return palavras > 0;
    }

    /**
     * Um verso é português? Romaji nunca é; senão decide o dicionário (mais palavras portuguesas
     * que inglesas) e, com o dicionário mudo para o verso, o diacrítico — os mesmos instrumentos,
     * na mesma ordem, de {@link #ehParOriginalTraducao}.
     */
    private boolean ehPortugues(String verso, Map<String, VeredictoPalavra> idioma) {
        if (detectorKaraoke.proporcaoRomaji(verso) >= LIMIAR_ROMAJI_SAIDA) {
            return false;
        }
        int[] c = contarLinguaPeloDicionario(verso, idioma);
        if (c[0] + c[1] == 0) {
            return DIACRITICO_PT.matcher(verso).find();
        }
        return c[0] > c[1];
    }

    /**
     * PROPÓSITO DE NEGÓCIO: as duas metades de um {@code \N} são original + tradução (línguas
     * DIFERENTES), e não um verso monolíngue quebrado?
     *
     * <h2>Como decide, e por que dicionário e não lista à mão</h2>
     * (1) um lado ROMAJI (proporção ≥ {@value #LIMIAR_ROMAJI_SAIDA}) e o outro não ⇒ par; (2) os
     * dois romaji ⇒ verso romaji ⇒ achata; (3) nenhum romaji ⇒ inglês × português, decidido pelo
     * DICIONÁRIO Hunspell ({@link #classificarIdiomaDosPares}): é par quando EXATAMENTE um lado é
     * PORTUGUÊS-dominante (mais palavras {@code PORTUGUES_OK}/{@code ACENTO_FALTANDO} que
     * {@code RESIDUO_INGLES}) — ou, desde 24/09/2026, quando só o lado de CIMA tem palavra que apenas
     * o inglês reconhece e o de baixo tem português (o pt_BR aceita "Can't"/"escape"/"fate" e fazia
     * a letra inglesa parecer portuguesa; ver {@code MedicaoParBilingueAchatadorIT}). O dicionário
     * decide por PONTUAÇÃO, resolvendo a colisão que derrubou
     * a lista à mão: {@code "Do you feel alone"} tem 3 palavras inglesas contra 1 portuguesa
     * ({@code do}), então é inglês; {@code "Deixe a luz passar"} tem 4 portuguesas, então é a
     * tradução. Sem lista de palavras para manter (ordem do Paulo, 21/09).
     *
     * <p>FALLBACK (regra 22): dicionário indisponível (mapa sem veredicto para as palavras) cai no
     * DIACRÍTICO português — pega a tradução acentuada (a maioria) e achata a PT sem acento nenhum
     * (volta ao comportamento antigo); nunca vira linha errada nem vazia.
     */
    private boolean ehParOriginalTraducao(String a, String b, Map<String, VeredictoPalavra> idioma) {
        int romajiA = detectorKaraoke.proporcaoRomaji(a);
        int romajiB = detectorKaraoke.proporcaoRomaji(b);
        boolean aRomaji = romajiA >= LIMIAR_ROMAJI_SAIDA;
        boolean bRomaji = romajiB >= LIMIAR_ROMAJI_SAIDA;
        if (aRomaji != bRomaji) {
            return true; // romaji original de um lado, tradução do outro
        }
        if (aRomaji) {
            return false; // os dois romaji: verso romaji monolingue
        }
        // nenhum romaji: ingles (original) x portugues (traducao), pelo dicionario.
        int[] ca = contarLinguaPeloDicionario(a, idioma);
        int[] cb = contarLinguaPeloDicionario(b, idioma);
        if (ca[0] + ca[1] + cb[0] + cb[1] == 0) {
            // dicionario nao disse nada (indisponivel/todas desconhecidas): fallback diacritico
            return DIACRITICO_PT.matcher(a).find() != DIACRITICO_PT.matcher(b).find();
        }
        boolean aPt = ca[0] > ca[1];
        boolean bPt = cb[0] > cb[1];
        if (aPt != bPt) {
            return true; // exatamente um lado e portugues-dominante ⇒ par
        }
        // O pt_BR aceita muita palavra inglesa ("Can't", "I", "escape", "sole", "fate" — medido
        // em 24/09/2026 com o dicionario real), e a letra inglesa passa a "parecer portugues" por
        // contagem: os dois lados davam portugues e o par era achatado numa linha so, ingles e PT
        // colados. O sinal que sobra e a ASSIMETRIA: em cima ha palavra que SO o ingles reconhece,
        // embaixo nenhuma, e embaixo ha portugues. Verso monolingue ingles tem palavra inglesa nos
        // dois lados; monolingue portugues nao tem nenhuma em cima.
        return ca[1] >= 1 && cb[1] == 0 && cb[0] >= 1;
    }

    /**
     * PROPÓSITO DE NEGÓCIO: conta, num lado do {@code \N}, quantas palavras o dicionário deu como
     * PORTUGUÊS e quantas como INGLÊS. Devolve {@code [pt, en]}.
     *
     * <p>INVARIANTES DO DOMÍNIO: só consulta o mapa já classificado em lote — não chama o hunspell
     * aqui (o custo é o arranque do processo, e ele já rodou uma vez por arquivo).
     */
    private static int[] contarLinguaPeloDicionario(String lado, Map<String, VeredictoPalavra> idioma) {
        int pt = 0;
        int en = 0;
        for (String palavra : CorretorOrtograficoLegenda.palavrasDe(lado)) {
            VeredictoPalavra v = idioma.get(palavra);
            if (v == VeredictoPalavra.PORTUGUES_OK || v == VeredictoPalavra.ACENTO_FALTANDO) {
                pt++;
            } else if (v == VeredictoPalavra.RESIDUO_INGLES) {
                en++;
            }
        }
        return new int[] {pt, en};
    }

    /**
     * PROPÓSITO DE NEGÓCIO: classifica em LOTE (uma consulta por arquivo) o idioma das palavras dos
     * eventos musicais que trazem um {@code \N} — os únicos candidatos a par bilíngue. O custo do
     * hunspell é o arranque do processo, então juntar tudo num lote é o que a régra da medição pede.
     *
     * <p>COMPORTAMENTO EM CASO DE FALHA: sem palavras devolve mapa vazio; qualquer erro do corretor,
     * OU o dicionário de INGLÊS indisponível (o idioma de DECISÃO — sem ele a distincao PT×EN fica
     * cega para um lado), devolve mapa vazio e a decisão cai no fallback do diacrítico. Nunca lança.
     */
    private Map<String, VeredictoPalavra> classificarIdiomaDosPares(List<EventoAss> musicais) {
        java.util.LinkedHashSet<String> palavras = new java.util.LinkedHashSet<>();
        for (EventoAss e : musicais) {
            // TODAS as letras musicais, e nao so as com quebra: desde 24/09/2026 o mesmo mapa
            // tambem decide a ORDEM das camadas (portugues embaixo) em empilharCamadas.
            String t = normalizarQuebras(limparArtefatosVisiveis(e.textoComQuebra()));
            palavras.addAll(CorretorOrtograficoLegenda.palavrasDe(t));
        }
        if (palavras.isEmpty()) {
            return Map.of();
        }
        try {
            Map<String, VeredictoPalavra> classificadas = corretorOrtografico.classificarPalavras(palavras);
            // Se o dicionario de INGLES nao respondeu (indisponivel), nenhuma palavra vira
            // RESIDUO_INGLES e o lado ingles ficaria mudo — a decisao PT×EN seria pela metade
            // (uma palavra inglesa valida tambem em pt_BR, como "idea"/"have", inclinaria o lado
            // ingles para PT). Sem o idioma de decisao, o dicionario nao decide: cai no diacritico.
            if (!corretorOrtografico.inglesDisponivel()) {
                return Map.of();
            }
            return classificadas;
        } catch (RuntimeException ex) {
            log.warn("Classificacao de idioma indisponivel; achatador cai no fallback do diacritico", ex);
            return Map.of();
        }
    }

    /**
     * PROPÓSITO DE NEGÓCIO: remove a quebra {@code \N} de BORDA (que renderiza linha vazia) e
     * colapsa {@code \N} repetido em um só. Uma quebra no fim ({@code "...felt\N"}) é o defeito
     * medido em 21/09 que fazia uma linha em branco aparecer sob a letra.
     *
     * <p>COMPORTAMENTO EM CASO DE FALHA: sem {@code \N} devolve o texto limpo; nunca lança.
     */
    private static String normalizarQuebras(String texto) {
        // O soft-break \n (minusculo) do fansub NUNCA separa o par bilingue (o Passo 1 emite \N),
        // entao e sempre quebra de verso monolingue -> vira espaco (restaura o comportamento antigo,
        // que textoVisivel() dava; textoComQuebra() preserva \n de proposito e e aqui que se decide).
        String r = texto.replace("\\n", " ").replaceAll("\\s{2,}", " ").strip();
        while (r.startsWith(QUEBRA_ASS)) {
            r = r.substring(QUEBRA_ASS.length()).strip();
        }
        while (r.endsWith(QUEBRA_ASS)) {
            r = r.substring(0, r.length() - QUEBRA_ASS.length()).strip();
        }
        while (r.contains(QUEBRA_ASS + QUEBRA_ASS)) {
            r = r.replace(QUEBRA_ASS + QUEBRA_ASS, QUEBRA_ASS);
        }
        return r;
    }

    /**
     * KFX letra-por-letra com camadas caractere+sílaba sobrepostas produz texto
     * "pulverizado" ("t e i k a n" / "d da a r re e"): maioria das palavras com
     * uma letra só. Esse lado perde a deduplicação para a camada de tradução.
     */
    private static boolean pareceTextoPulverizado(String texto) {
        String[] palavras = texto.strip().split("\\s+");
        if (palavras.length < 5) {
            return false;
        }
        long soltas = Stream.of(palavras)
            .filter(p -> p.replaceAll("[^\\p{L}]", "").length() == 1)
            .count();
        return (double) soltas / palavras.length >= 0.4;
    }

    private static boolean temArtefatoColchetes(String texto) {
        return texto != null && (texto.contains("[]") || texto.matches("(?i).*\\[!?\\[?TAG\\d+\\]?.*"));
    }

    private static boolean pareceOriginalJaponesOuRomaji(String texto) {
        if (texto == null || texto.isBlank()) {
            return false;
        }
        if (PADRAO_JAPONES.matcher(texto).find()) {
            return true;
        }
        String normalizado = texto.toLowerCase(Locale.ROOT)
            .replace('ā', 'a').replace('ī', 'i').replace('ū', 'u')
            .replace('ē', 'e').replace('ō', 'o')
            .replace("'", "");
        if (NAO_ASCII_PATTERN.matcher(normalizado).find()) {
            return false;
        }

        int palavras = 0;
        int letras = 0;
        for (String palavra : normalizado.split("[^a-z]+")) {
            if (palavra.isBlank()) {
                continue;
            }
            if (!PALAVRA_ROMAJI_PATTERN.matcher(palavra).matches()) {
                return false;
            }
            palavras++;
            letras += palavra.length();
        }
        return palavras >= 2 && letras >= 6;
    }

    private static int pontuacaoPortugues(String texto) {
        String normalizado = texto.toLowerCase(Locale.ROOT)
            .replace('á', 'a').replace('à', 'a').replace('ã', 'a').replace('â', 'a')
            .replace('é', 'e').replace('ê', 'e')
            .replace('í', 'i')
            .replace('ó', 'o').replace('õ', 'o').replace('ô', 'o')
            .replace('ú', 'u')
            .replace('ç', 'c');
        int pontos = 0;
        for (String palavra : normalizado.split("[^a-z0-9]+")) {
            if (palavra.length() < 2) {
                // "a"/"e"/"o" soltos aparecem aos montes em KFX pulverizado — não são evidência de PT-BR
                continue;
            }
            pontos += switch (palavra) {
                case "nao", "voce", "voces", "eu", "meu", "minha", "mundo", "verdadeiro",
                    "ceu", "fugaz", "adeus", "crepusculo", "eterno", "ignorancia",
                    "deliberada", "voluntaria", "revoltante", "repugnante", "espectador",
                    "esperando", "flor", "vento", "culpados", "todos", "final", "apenas",
                    "quanto", "deseje", "nada", "muda", "sorrir", "depois", "ver", "oi" -> 2;
                case "de", "do", "da", "dos", "das", "para", "por", "com", "em", "no",
                    "na", "nos", "nas", "que", "uma", "um", "ao", "aos", "e", "o", "a",
                    "os", "as", "se" -> 1;
                default -> 0;
            };
        }
        return pontos;
    }

    private void publicarResumo(List<ResultadoConversaoKaraoke> resultados, int falhas, long duracaoMs, boolean gravar) {
        int arquivos = resultados.size();
        int removidos = resultados.stream().mapToInt(ResultadoConversaoKaraoke::getEventosKaraokeRemovidos).sum();
        int linhas = resultados.stream().mapToInt(r -> r.getLinhasCriadas().size()).sum();
        int preservadosSeguranca = resultados.stream().mapToInt(ResultadoConversaoKaraoke::getEventosPreservadosPorSeguranca).sum();
        long bytesAntes = resultados.stream().mapToLong(ResultadoConversaoKaraoke::getTamanhoOriginalBytes).sum();
        long bytesDepois = resultados.stream().mapToLong(ResultadoConversaoKaraoke::getTamanhoNovoBytes).sum();
        logStream.publicarLog(CANAL_LOG, "");
        logStream.publicarLog(CANAL_LOG, "==== RESUMO ====");
        logStream.publicarLog(CANAL_LOG, String.format(Locale.ROOT,
            "Arquivos processados: %d (%d com falha) | Eventos KFX removidos: %d | Linhas simples criadas: %d | Preservados por segurança: %d",
            arquivos, falhas, removidos, linhas, preservadosSeguranca));
        logStream.publicarLog(CANAL_LOG, String.format(Locale.ROOT,
            "Tamanho total: %d KB → %d KB | Duração: %.1fs",
            bytesAntes / 1024, bytesDepois / 1024, duracaoMs / 1000.0));
        if (falhas == 0) {
            logStream.publicarLog(CANAL_LOG, gravar
                ? "[SUCESSO] Conversão concluída — originais preservados na pasta de origem."
                : "[SUCESSO] Simulação concluída — nenhum arquivo foi gravado.");
        } else {
            logStream.publicarLog(CANAL_LOG, "[ATENÇÃO] Operação concluída com " + falhas + " falha(s) — verifique o log acima.");
        }
        logStream.publicarLog(CANAL_LOG, DuracaoUtil.linhaRelatorioFinal(
            gravar ? "Karaokê Simples (conversão)" : "Karaokê Simples (simulação)",
            System.currentTimeMillis() - duracaoMs));
    }

    private void registrarTelemetriaEManifesto(Path origem, Path destino, List<ResultadoConversaoKaraoke> resultados, long duracaoMs) {
        int removidos = resultados.stream().mapToInt(ResultadoConversaoKaraoke::getEventosKaraokeRemovidos).sum();
        int linhas = resultados.stream().mapToInt(r -> r.getLinhasCriadas().size()).sum();
        var operacao = TelemetriaService.criarOperacao(
            "NOVO_KARAOKE",
            "KFX → legenda simples: " + origem.getFileName() + " → " + destino,
            duracaoMs,
            resultados.size(),
            removidos,
            linhas
        );
        telemetriaService.registrarOperacao(operacao);
        try {
            Path manifesto = persistencia.salvarManifesto(origem, destino, resultados, duracaoMs);
            logStream.publicarLog(CANAL_LOG, "Manifesto de auditoria salvo em: " + manifesto);
        } catch (IOException e) {
            log.warn("Falha ao salvar manifesto de auditoria do novo karaokê", e);
            logStream.publicarLog(CANAL_LOG, "[AVISO] Não foi possível salvar o manifesto de auditoria: " + e.getMessage());
        }
    }

    private static int contarPalavras(String texto) {
        if (texto == null || texto.isBlank()) {
            return 0;
        }
        return texto.strip().split("\\s+").length;
    }

    private static double sobreposicao(Grupo a, Grupo b) {
        return sobreposicaoJanelas(a.inicioCs, a.fimCs, b.inicioCs, b.fimCs);
    }

    /** Fração da janela MENOR coberta pela interseção das duas janelas. */
    private static double sobreposicaoJanelas(long inicioA, long fimA, long inicioB, long fimB) {
        long inicio = Math.max(inicioA, inicioB);
        long fim = Math.min(fimA, fimB);
        long intersecao = Math.max(0, fim - inicio);
        long menor = Math.min(Math.max(1, fimA - inicioA), Math.max(1, fimB - inicioB));
        return (double) intersecao / menor;
    }

    /**
     * PROPÓSITO DE NEGÓCIO: o quanto duas linhas são o MESMO verso escrito de formas diferentes. A
     * palavra que é NOME PRÓPRIO (maiúscula fora do início, em qualquer das duas) não conta: termo de
     * lore aparece igual no romaji e na tradução ("mobile suits ha shinka suru noni" × "Os Mobile
     * Suits evoluem, mas e eu?"), e com ele duas línguas passavam do limiar e a deduplicação apagava
     * uma delas — a PT no Reconguista V, o romaji em estilos diferentes (auditoria de 28/10/2026).
     *
     * <p>INVARIANTES DO DOMÍNIO: mesmo conjunto de palavras é o mesmo verso (1,0), com nome ou sem;
     * o nome sai só do numerador e o denominador é a linha MAIOR (a variante pulverizada é tratada à
     * parte, pelas letras).
     *
     * <p>COMPORTAMENTO EM CASO DE FALHA: linha sem palavra comparável devolve 0.
     */
    private static double similaridadeTokens(String a, String b) {
        var tokensA = tokens(a);
        var tokensB = tokens(b);
        if (tokensA.isEmpty() || tokensB.isEmpty()) {
            return 0;
        }
        if (new java.util.HashSet<>(tokensA).equals(new java.util.HashSet<>(tokensB))) {
            return 1.0;
        }
        // O nome sai só do NUMERADOR: tirá-lo também do denominador encolhia a linha e fundia o que
        // não é variante — "Kimi ga Yubi Sashita Kanatae to" × "You pointed me toward...", com um
        // "to" em comum, ia de 1/6 para 1/3 e o Break Blade perdia a tradução empilhada (medido no
        // acervo, 28/10/2026, antes de entrar).
        java.util.Set<String> nomes = nomesProprios(a);
        nomes.addAll(nomesProprios(b));
        // Denominador pela linha MAIOR (até 28/10/2026, pela menor): duas palavras de lore em comum
        // davam 2/6 entre o romaji e a tradução inglesa ("mobile suits ha shinka suru noni" ×
        // "Mobile suits are evolving, but what about me?") e a tradução era fundida e sumia.
        // Variante de verdade do mesmo verso tem quase as mesmas palavras e não perde nada.
        long comuns = tokensA.stream().filter(x -> !nomes.contains(x)).filter(tokensB::contains).count();
        return (double) comuns / Math.max(tokensA.size(), tokensB.size());
    }

    /** Palavras com inicial maiúscula fora do começo da linha, em minúsculas. */
    private static java.util.Set<String> nomesProprios(String texto) {
        java.util.Set<String> nomes = new java.util.HashSet<>();
        String[] partes = texto.split("[^\\p{L}\\d]+");
        boolean primeira = true;
        for (String p : partes) {
            if (p.isBlank()) {
                continue;
            }
            if (!primeira && Character.isUpperCase(p.codePointAt(0))) {
                nomes.add(p.toLowerCase(Locale.ROOT));
            }
            primeira = false;
        }
        return nomes;
    }

    private static List<String> tokens(String texto) {
        return Stream.of(texto.toLowerCase(Locale.ROOT).split("[^\\p{L}\\d]+"))
            .filter(t -> !t.isBlank())
            .distinct()
            .toList();
    }

    private static String resumir(String texto) {
        return texto.length() <= 70 ? texto : texto.substring(0, 67) + "...";
    }

    /** Grupo de eventos KFX da mesma frase (mesmo texto, janela contígua). */
    private static final class Grupo {
        /** Chave de AGRUPAMENTO: texto achatado (sem {@code \N}), para casar sílabas e variantes. */
        private String texto;
        /**
         * Texto de SAÍDA: preserva o {@code \N} deliberado do evento (par bilíngue
         * {@code original\Ntradução} entregue pelo Passo 1). O achatamento agrupa pela chave
         * achatada, mas emite esta versão — senão colaria "inglês PT" numa linha só.
         */
        private String textoSaida;
        private long inicioCs;
        private long fimCs;
        private int eventos;
        private int variantes = 1;
        /** Os textos de todas as variantes fundidas neste grupo — para a pertença na remoção. */
        private final List<String> textosDasVariantes = new ArrayList<>();
        /** Fim do último evento de VERSO INTEIRO absorvido; -1 quando só houve cópias curtas. */
        private long fimUltimoVersoInteiro = -1;

        private Grupo(String texto, String textoSaida, EventoAss primeiro) {
            this.texto = texto;
            this.textoSaida = textoSaida;
            this.inicioCs = primeiro.inicioCs();
            this.fimCs = primeiro.fimCs();
            this.eventos = 1;
            this.textosDasVariantes.add(texto);
            marcarSeVersoInteiro(primeiro);
        }

        private void absorver(EventoAss evento) {
            inicioCs = Math.min(inicioCs, evento.inicioCs());
            fimCs = Math.max(fimCs, evento.fimCs());
            eventos++;
            marcarSeVersoInteiro(evento);
        }

        private void marcarSeVersoInteiro(EventoAss evento) {
            if (ehVersoInteiro(evento)) {
                fimUltimoVersoInteiro = Math.max(fimUltimoVersoInteiro, evento.fimCs());
            }
        }

        /**
         * O evento é uma NOVA ocorrência inteira da mesma frase, e não mais uma cópia da animação?
         * Só quando ele e uma ocorrência anterior do grupo são versos inteiros e ele começa depois
         * que a anterior terminou — a música repetindo a linha.
         */
        private boolean repeteVersoInteiro(EventoAss evento) {
            return ehVersoInteiro(evento) && fimUltimoVersoInteiro >= 0
                && evento.inicioCs() >= fimUltimoVersoInteiro - MARGEM_COBERTURA_CS;
        }

        /** Funde uma variante divergente: vence o texto com mais eventos de origem. */
        private void fundirVariante(Grupo outro) {
            if (outro.eventos > this.eventos) {
                this.texto = outro.texto;
                this.textoSaida = outro.textoSaida;
                this.eventos = outro.eventos;
            }
            inicioCs = Math.min(inicioCs, outro.inicioCs);
            fimCs = Math.max(fimCs, outro.fimCs);
            variantes += outro.variantes;
            textosDasVariantes.addAll(outro.textosDasVariantes);
        }

        private String janela() {
            return EventoAss.csParaTempo(inicioCs) + "–" + EventoAss.csParaTempo(fimCs);
        }

        private int totalEventosJanela(List<EventoAss> eventosEstilo) {
            return (int) eventosEstilo.stream()
                .filter(e -> e.inicioCs() < fimCs && e.fimCs() > inicioCs)
                .count();
        }

        private LinhaSimplesKaraoke paraLinha() {
            return new LinhaSimplesKaraoke(textoSaida, inicioCs, fimCs, eventos, variantes);
        }
    }

    private record FragmentoKfx(EventoAss evento, String texto, double x, double y, int ordem) {
        private int faixaVertical() {
            return Double.isNaN(y) ? Integer.MAX_VALUE : (int) Math.round(y / 120.0);
        }

        private double xOrdenacao() {
            return Double.isNaN(x) ? Double.MAX_VALUE : x;
        }
    }

    /** Fallback para KFX que só tem sílabas/letras/frames, sem uma linha inteira pronta. */
    private static final class GrupoFragmentos {
        private final List<FragmentoKfx> fragmentos = new ArrayList<>();
        private final List<FragmentoKfx> fragmentosUnicos = new ArrayList<>();
        private final List<String> chavesVistas = new ArrayList<>();
        private final int faixaVertical;
        private long inicioCs;
        private long fimCs;
        private int eventos;
        private String ultimoFragmento;
        private long ultimoInicioCs;

        private GrupoFragmentos(FragmentoKfx primeiro) {
            this.faixaVertical = primeiro.faixaVertical();
            this.inicioCs = primeiro.evento().inicioCs();
            this.fimCs = primeiro.evento().fimCs();
            this.eventos = 0;
            absorver(primeiro);
        }

        private void absorver(FragmentoKfx fragmento) {
            EventoAss evento = fragmento.evento();
            inicioCs = Math.min(inicioCs, evento.inicioCs());
            fimCs = Math.max(fimCs, evento.fimCs());
            eventos++;
            fragmentos.add(fragmento);

            String normalizado = fragmento.texto() == null ? "" : fragmento.texto().strip();
            // frame seguinte da mesma animação começa DEPOIS do anterior; início
            // idêntico é letra repetida da mesma frase ("ll", "ee") e deve ficar
            boolean repeticaoDoMesmoFrame = normalizado.equalsIgnoreCase(ultimoFragmento)
                && evento.inicioCs() > ultimoInicioCs
                && evento.inicioCs() - ultimoInicioCs <= GAP_REPETICAO_FRAGMENTO_CS;
            String chavePosicional = chavePosicional(fragmento, normalizado);
            if (!repeticaoDoMesmoFrame && !chavesVistas.contains(chavePosicional)) {
                fragmentosUnicos.add(fragmento);
                chavesVistas.add(chavePosicional);
                ultimoFragmento = normalizado;
                ultimoInicioCs = evento.inicioCs();
            }
        }

        private boolean cobre(EventoAss evento) {
            return evento.inicioCs() < fimCs + MARGEM_COBERTURA_CS
                && evento.fimCs() > inicioCs - MARGEM_COBERTURA_CS;
        }

        /**
         * KFX letra-por-letra mantém a frase inteira na tela (dezenas de eventos
         * simultâneos) e a frase seguinte começa exatamente quando a anterior
         * termina — o gap nunca separa as frases. Nesses grupos a troca de frase
         * aparece como um "vale" na concorrência (quase nada ativo), e o grupo é
         * cortado no meio de cada vale. KFX silábico/sequencial (concorrência
         * baixa) passa intacto, preservando o comportamento anterior.
         */
        private List<GrupoFragmentos> subdividirPorFrase() {
            if (fragmentos.size() < PICO_SIMULTANEO_MINIMO) {
                return List.of(this);
            }
            // varredura: (tempo, delta) com fim antes de início no empate (intervalo semiaberto)
            List<long[]> pontos = new ArrayList<>(fragmentos.size() * 2);
            for (FragmentoKfx fragmento : fragmentos) {
                pontos.add(new long[] {fragmento.evento().inicioCs(), 1});
                pontos.add(new long[] {fragmento.evento().fimCs(), -1});
            }
            pontos.sort(Comparator.<long[]>comparingLong(p -> p[0]).thenComparingLong(p -> p[1]));
            int pico = 0;
            int concorrencia = 0;
            for (long[] ponto : pontos) {
                concorrencia += (int) ponto[1];
                pico = Math.max(pico, concorrencia);
            }
            if (pico < PICO_SIMULTANEO_MINIMO) {
                return List.of(this);
            }

            int limiteVale = Math.max(VALE_CONCORRENCIA_MINIMO, (int) Math.round(pico * FRACAO_VALE_CONCORRENCIA));
            List<Long> cortes = new ArrayList<>();
            concorrencia = 0;
            long inicioVale = -1;
            for (long[] ponto : pontos) {
                concorrencia += (int) ponto[1];
                if (concorrencia <= limiteVale && inicioVale < 0) {
                    inicioVale = ponto[0];
                } else if (concorrencia > limiteVale && inicioVale >= 0) {
                    long corte = (inicioVale + ponto[0]) / 2;
                    if (corte > inicioCs + DURACAO_MINIMA_SEGMENTO_CS && corte < fimCs - DURACAO_MINIMA_SEGMENTO_CS) {
                        cortes.add(corte);
                    }
                    inicioVale = -1;
                }
            }
            if (cortes.isEmpty()) {
                return List.of(this);
            }

            // distribui cada fragmento pelo centro da sua janela (tolerante a crossfade)
            List<List<FragmentoKfx>> segmentos = new ArrayList<>();
            for (int i = 0; i <= cortes.size(); i++) {
                segmentos.add(new ArrayList<>());
            }
            for (FragmentoKfx fragmento : fragmentos) {
                long centro = (fragmento.evento().inicioCs() + fragmento.evento().fimCs()) / 2;
                int indice = 0;
                while (indice < cortes.size() && centro >= cortes.get(indice)) {
                    indice++;
                }
                segmentos.get(indice).add(fragmento);
            }

            List<GrupoFragmentos> frases = new ArrayList<>();
            for (List<FragmentoKfx> segmento : segmentos) {
                if (segmento.isEmpty()) {
                    continue;
                }
                GrupoFragmentos frase = new GrupoFragmentos(segmento.getFirst());
                for (int i = 1; i < segmento.size(); i++) {
                    frase.absorver(segmento.get(i));
                }
                frases.add(frase);
            }
            // segmento mais curto que uma frase de música indica KFX silábico em
            // camadas, não letra-por-letra — nesse caso o corte não se aplica
            boolean segmentoImplausivel = frases.stream()
                .anyMatch(f -> f.fimCs - f.inicioCs < DURACAO_MINIMA_SEGMENTO_CS);
            return segmentoImplausivel ? List.of(this) : frases;
        }

        private boolean eLinhaUtil() {
            return eventos >= 2 && fragmentosUnicos.stream()
                .map(FragmentoKfx::texto)
                .mapToInt(t -> t == null ? 0 : t.strip().length())
                .sum() >= 2;
        }

        private LinhaSimplesKaraoke paraLinha() {
            return new LinhaSimplesKaraoke(montarTexto(), inicioCs, fimCs, eventos, 1);
        }

        private String montarTexto() {
            List<FragmentoKfx> ordenados = new ArrayList<>(fragmentosUnicos);
            boolean porPosicao = ordenados.stream().filter(f -> !Double.isNaN(f.x())).count() >= Math.max(2, ordenados.size() / 2);
            double saltoDePalavra = Double.NaN;
            if (porPosicao) {
                ordenados.sort(Comparator.comparingDouble(FragmentoKfx::xOrdenacao).thenComparingInt(FragmentoKfx::ordem));
                saltoDePalavra = limiarSaltoDePalavra(ordenados);
            } else {
                ordenados.sort(Comparator.comparingLong((FragmentoKfx f) -> f.evento().inicioCs()).thenComparingInt(FragmentoKfx::ordem));
            }

            boolean modoCaracter = ordenados.stream()
                .map(FragmentoKfx::texto)
                .filter(t -> t != null && !t.isBlank())
                .allMatch(t -> t.strip().length() <= 1);

            StringBuilder texto = new StringBuilder();
            double xAnterior = Double.NaN;
            for (FragmentoKfx fragmento : ordenados) {
                String parte = fragmento.texto() == null ? "" : fragmento.texto().strip();
                if (parte.isBlank()) {
                    if (!texto.isEmpty() && texto.charAt(texto.length() - 1) != ' ') {
                        texto.append(' ');
                    }
                    continue;
                }
                boolean separa;
                if (modoCaracter) {
                    // KFX letra-por-letra não tem eventos de espaço: a palavra
                    // termina onde o avanço em X salta além do passo de um caractere
                    separa = !Double.isNaN(saltoDePalavra) && !Double.isNaN(xAnterior)
                        && !Double.isNaN(fragmento.x()) && fragmento.x() - xAnterior > saltoDePalavra;
                } else {
                    separa = true;
                }
                if (separa && !texto.isEmpty() && texto.charAt(texto.length() - 1) != ' ') {
                    texto.append(' ');
                }
                texto.append(parte);
                if (!Double.isNaN(fragmento.x())) {
                    xAnterior = fragmento.x();
                }
            }
            return texto.toString()
                .replaceAll("\\s+([,.!?;:])", "$1")
                .replaceAll("\\s{2,}", " ")
                .strip();
        }

        private static double limiarSaltoDePalavra(List<FragmentoKfx> ordenados) {
            List<Double> avancos = avancosX(ordenados);
            if (avancos.size() < 3) {
                return Double.NaN;
            }
            return mediana(avancos) * 1.6;
        }

        /** Avanços positivos em X entre fragmentos visíveis consecutivos (já ordenados por X). */
        private static List<Double> avancosX(List<FragmentoKfx> ordenados) {
            List<Double> avancos = new ArrayList<>();
            double xAnterior = Double.NaN;
            for (FragmentoKfx fragmento : ordenados) {
                if (Double.isNaN(fragmento.x()) || fragmento.texto() == null || fragmento.texto().isBlank()) {
                    continue;
                }
                if (!Double.isNaN(xAnterior) && fragmento.x() > xAnterior) {
                    avancos.add(fragmento.x() - xAnterior);
                }
                xAnterior = fragmento.x();
            }
            return avancos;
        }

        private static double mediana(List<Double> valores) {
            List<Double> ordenados = new ArrayList<>(valores);
            ordenados.sort(Double::compareTo);
            int meio = ordenados.size() / 2;
            return ordenados.size() % 2 == 1
                ? ordenados.get(meio)
                : (ordenados.get(meio - 1) + ordenados.get(meio)) / 2.0;
        }

        private static String chavePosicional(FragmentoKfx fragmento, String texto) {
            long x = Double.isNaN(fragmento.x()) ? Long.MIN_VALUE : Math.round(fragmento.x() / 3.0);
            long y = Double.isNaN(fragmento.y()) ? Long.MIN_VALUE : Math.round(fragmento.y() / 3.0);
            return texto.toLowerCase(Locale.ROOT) + "|" + x + "|" + y;
        }
    }
}
