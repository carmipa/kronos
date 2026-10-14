package org.traducao.projeto.remuxer.application;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.traducao.projeto.remuxer.domain.PlanoRemux;
import org.traducao.projeto.remuxer.domain.RemuxTarefa;
import org.traducao.projeto.remuxer.domain.RemuxerException;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * PROPÓSITO DE NEGÓCIO: pareia vídeos MKV e legendas finais de forma
 * determinística, gerando nomes de saída limpos para a etapa de remux.
 *
 * <p>INVARIANTES DO DOMÍNIO: uma legenda não atende dois vídeos; episódio 01
 * nunca casa por prefixo com 010; empates de mesma prioridade são reportados
 * como ambíguos; destinos não colidem.
 *
 * <p>COMPORTAMENTO EM CASO DE FALHA: pastas ilegíveis lançam
 * {@link RemuxerException}; ausência ou ambiguidade vira aviso sem tarefa.
 */
@Service
public class MapeadorMidiaService {
    private static final Logger log = LoggerFactory.getLogger(MapeadorMidiaService.class);
    private static final Comparator<Path> ORDEM_NOME = Comparator.comparing(
        p -> p.getFileName().toString().toLowerCase(Locale.ROOT));
    private static final Pattern EPISODIO_OCIDENTAL = Pattern.compile(
        "(?i)(?:S\\d{1,2})?E(\\d{1,3})(?!\\d)");
    private static final Pattern EPISODIO_ROTULO = Pattern.compile(
        "(?i)\\b(?:ep|eps|episode|ep\\.|eps\\.)\\s*?(\\d{1,3})(?!\\d)");
    private static final Pattern EPISODIO_SEPARADOR = Pattern.compile(
        "(?i)(?:\\s+-\\s+|_-_|_[\\s]*-[\\s_-]*)\\s*?(\\d{1,3})(?!\\d)");
    private static final Pattern EPISODIO_FALLBACK = Pattern.compile("(?<!\\d)(\\d{2,3})(?!\\d)");
    private static final Pattern TEMPORADA_OCIDENTAL = Pattern.compile("(?i)S(\\d{1,2})E\\d{1,3}(?!\\d)");
    private static final Pattern PARENTESES_TECNICOS = Pattern.compile(
        "(?i)\\([^)]*(?:720p|1080p|2160p|4k|x26[45]|av1|hevc|10bit|8bit|dual\\s*audio|bd(?:rip)?|web[- .]?dl)[^)]*\\)");
    private static final Pattern SUFIXOS_LEGENDA = Pattern.compile(
        "(?i)(?:[\\s._-]+(?:track\\s*\\d+|pt[-_ ]?br|ptbr|eng(?:lish)?|en|full|forced|signs|songs))+$");

    /**
     * PROPÓSITO DE NEGÓCIO: mantém a API histórica que entrega somente a fila,
     * delegando ao plano auditável a decisão de pareamento.
     *
     * <p>INVARIANTES DO DOMÍNIO: a lista contém apenas tarefas não ambíguas e
     * destinos únicos.
     *
     * <p>COMPORTAMENTO EM CASO DE FALHA: propaga falha de leitura; problemas de
     * pareamento ficam fora da lista e registrados no log.
     */
    public List<RemuxTarefa> construirFilaProcessamento(Path pastaVideos, Path pastaLegendas, Path pastaSaida) {
        return construirPlano(pastaVideos, pastaLegendas, pastaSaida).tarefas();
    }

    /**
     * PROPÓSITO DE NEGÓCIO: constrói o dry-run interno do remux com tarefas,
     * ausências, ambiguidades e nomes finais previsíveis.
     *
     * <p>INVARIANTES DO DOMÍNIO: pareamento por arquivo único só ocorre com um de
     * cada; nos lotes, identidade normalizada precede episódio e a melhor
     * candidata precisa ser única.
     *
     * <p>COMPORTAMENTO EM CASO DE FALHA: diretório inválido/ilegível lança
     * exceção; item problemático é preservado e descrito em {@code avisos}.
     */
    public PlanoRemux construirPlano(Path pastaVideos, Path pastaLegendas, Path pastaSaida) {
        validarDiretorios(pastaVideos, pastaLegendas, pastaSaida);
        List<Path> mkvs = listar(pastaVideos, ".mkv");
        List<Path> legendasDisponiveis = listarLegendas(pastaLegendas);
        int totalLegendas = legendasDisponiveis.size();
        List<RemuxTarefa> tarefas = new ArrayList<>();
        List<String> avisos = new ArrayList<>();
        Set<String> destinos = new HashSet<>();
        int semLegenda = 0;
        int ambiguos = 0;

        for (Path mkv : mkvs) {
            SelecaoLegenda selecao = selecionarLegenda(mkv, mkvs, legendasDisponiveis);
            if (selecao.legenda() == null) {
                if (selecao.ambigua()) {
                    ambiguos++;
                } else {
                    semLegenda++;
                }
                avisos.add(selecao.motivo());
                log.warn(selecao.motivo());
                continue;
            }

            Path legenda = selecao.legenda();
            String nomeSaida = gerarNomeSaidaLimpo(mkv, legenda);
            String chaveDestino = nomeSaida.toLowerCase(Locale.ROOT);
            if (!destinos.add(chaveDestino)) {
                ambiguos++;
                String aviso = "Destino duplicado para " + mkv.getFileName() + ": " + nomeSaida
                    + ". O item foi bloqueado para evitar sobrescrita.";
                avisos.add(aviso);
                log.warn(aviso);
                continue;
            }

            legendasDisponiveis.remove(legenda);
            Path destino = pastaSaida.resolve(nomeSaida);
            tarefas.add(new RemuxTarefa(mkv.getFileName().toString(), mkv, legenda, destino));
            log.info("Pareado com segurança: {} -> {} -> {}", mkv.getFileName(), legenda.getFileName(), nomeSaida);
        }

        return new PlanoRemux(tarefas, mkvs.size(), totalLegendas, semLegenda, ambiguos, avisos);
    }

    /**
     * PROPÓSITO DE NEGÓCIO: seleciona uma única legenda completa e preferencialmente
     * PT-BR para um vídeo, recusando empate silencioso.
     *
     * <p>INVARIANTES DO DOMÍNIO: igualdade de identidade é case-insensitive;
     * fallback por episódio exige episódio igual e unicidade após pontuação; quando
     * vídeo e legenda trazem temporada ({@code SxxEyy}), a temporada também tem de ser
     * igual; legenda SEM temporada não vai para o especial {@code S00} se o lote tem o
     * episódio regular de mesmo número. Sem essas duas, o especial S00E01 do Gundam ZZ,
     * que vem antes na ordem dos nomes, levou a legenda do episódio 1 em 09/10/2026 e o
     * MKV publicado saiu com o vídeo de 4 minutos e a legenda de 24.
     *
     * <p>COMPORTAMENTO EM CASO DE FALHA: devolve seleção vazia com motivo de
     * ausência ou ambiguidade; o especial que perde a legenda sem temporada para o
     * regular recebe um motivo que diz isso.
     */
    private SelecaoLegenda selecionarLegenda(Path mkv, List<Path> todosVideos, List<Path> legendas) {
        if (todosVideos.size() == 1 && legendas.size() == 1) {
            if (ehFaixaAuxiliar(legendas.get(0))) {
                return new SelecaoLegenda(null, false,
                    "A única legenda disponível é auxiliar (Forced/Signs/Songs) para "
                        + mkv.getFileName() + "; o remux exige a faixa completa PT-BR.");
            }
            return new SelecaoLegenda(legendas.get(0), false, "Pareamento por arquivo único.");
        }

        String identidadeVideo = normalizarIdentidade(mkv.getFileName().toString());
        List<Path> candidatas = legendas.stream()
            .filter(p -> identidadeVideo.equals(normalizarIdentidade(p.getFileName().toString())))
            .toList();

        String episodio = extrairTagEpisodio(mkv.getFileName().toString());
        boolean especialComRegularDoMesmoNumero = false;
        if (candidatas.isEmpty() && episodio != null) {
            Integer temporadaVideo = extrairTemporada(mkv.getFileName().toString());
            especialComRegularDoMesmoNumero = temporadaVideo != null && temporadaVideo == 0
                && todosVideos.stream().filter(v -> !v.equals(mkv)).anyMatch(v -> {
                    Integer t = extrairTemporada(v.getFileName().toString());
                    return t != null && t > 0 && episodio.equals(extrairTagEpisodio(v.getFileName().toString()));
                });
            boolean especialPerdeSemTemporada = especialComRegularDoMesmoNumero;
            candidatas = legendas.stream()
                .filter(p -> episodio.equals(extrairTagEpisodio(p.getFileName().toString())))
                .filter(p -> {
                    Integer temporadaLegenda = extrairTemporada(p.getFileName().toString());
                    if (temporadaVideo != null && temporadaLegenda != null) {
                        return temporadaVideo.equals(temporadaLegenda);
                    }
                    return !(temporadaLegenda == null && especialPerdeSemTemporada);
                })
                .toList();
        }
        if (candidatas.isEmpty()) {
            String motivo = especialComRegularDoMesmoNumero
                ? " É especial (S00) e o lote tem o episódio regular de mesmo número: legenda sem"
                    + " temporada fica com o regular; para o especial, a legenda precisa dizer S00E" + episodio + "."
                : " Nenhum nome ou episódio compatível foi encontrado.";
            return new SelecaoLegenda(null, false, "Legenda ausente para " + mkv.getFileName() + "." + motivo);
        }

        List<Path> completas = candidatas.stream().filter(p -> !ehFaixaAuxiliar(p)).toList();
        if (completas.isEmpty()) {
            return new SelecaoLegenda(null, false,
                "Somente legendas auxiliares Forced/Signs/Songs foram encontradas para "
                    + mkv.getFileName() + "; o remux exige a faixa completa PT-BR.");
        }
        candidatas = completas;

        int melhorPontuacao = candidatas.stream().mapToInt(this::pontuarLegenda).max().orElse(Integer.MIN_VALUE);
        List<Path> melhores = candidatas.stream()
            .filter(p -> pontuarLegenda(p) == melhorPontuacao)
            .sorted(ORDEM_NOME)
            .toList();
        if (melhores.size() != 1) {
            return new SelecaoLegenda(null, true,
                "Pareamento ambíguo para " + mkv.getFileName() + ": "
                    + melhores.stream().map(p -> p.getFileName().toString()).toList()
                    + ". Mantenha apenas a legenda completa correta ou padronize os nomes.");
        }
        return new SelecaoLegenda(melhores.get(0), false, "Pareamento determinístico.");
    }

    /**
     * PROPÓSITO DE NEGÓCIO: prioriza a legenda de diálogo completa em PT-BR e o
     * formato ASS, deixando faixas Forced/Signs/Songs abaixo da completa.
     *
     * <p>INVARIANTES DO DOMÍNIO: a pontuação serve apenas para desempate entre
     * candidatas já compatíveis com o vídeo.
     *
     * <p>COMPORTAMENTO EM CASO DE FALHA: nome sem marcadores recebe pontuação
     * neutra e ainda precisa ser candidato único.
     */
    private int pontuarLegenda(Path legenda) {
        String nome = legenda.getFileName().toString().toLowerCase(Locale.ROOT);
        int pontos = 0;
        if (nome.contains("pt-br") || nome.contains("ptbr")) pontos += 100;
        if (nome.endsWith(".ass")) pontos += 20;
        if (nome.contains("forced") || nome.contains("signs") || nome.contains("songs")) pontos -= 60;
        if (nome.contains("full")) pontos += 10;
        // A Tradução Local grava o "_PT-BR.parcial.ass" AO LADO do final quando uma reexecução
        // termina com pendência e o final já existe. Com a mesma pontuação, o episódio ficava
        // "ambíguo" e fora do remux (auditoria da 2.1, 25/09/2026). O parcial não é entrega:
        // perde para o final, mas continua servindo quando é a única versão existente.
        if (nome.contains(".parcial.")) pontos -= 50;
        return pontos;
    }

    /**
     * PROPÓSITO DE NEGÓCIO: identifica faixas auxiliares que não podem substituir
     * a legenda completa de diálogos.
     *
     * <p>INVARIANTES DO DOMÍNIO: comparação ignora caixa e usa marcadores
     * Forced/Signs/Songs.
     *
     * <p>COMPORTAMENTO EM CASO DE FALHA: nome sem marcador é tratado como faixa
     * completa candidata.
     */
    private boolean ehFaixaAuxiliar(Path legenda) {
        String nome = legenda.getFileName().toString().toLowerCase(Locale.ROOT);
        return nome.contains("forced") || nome.contains("signs") || nome.contains("songs");
    }

    /**
     * PROPÓSITO DE NEGÓCIO: cria nome final a partir da legenda revisada, removendo
     * release group, codec, resolução, CRC, track e idioma técnico.
     *
     * <p>INVARIANTES DO DOMÍNIO: nome termina em {@code _PTBR.mkv}, não contém
     * caracteres inválidos do Windows e nunca fica vazio.
     *
     * <p>COMPORTAMENTO EM CASO DE FALHA: se a legenda não produzir base útil,
     * tenta o vídeo; em último caso usa {@code video_final}.
     */
    private String gerarNomeSaidaLimpo(Path video, Path legenda) {
        String base = sanitizarBase(semExtensao(legenda.getFileName().toString()));
        if (base.isBlank()) {
            base = sanitizarBase(semExtensao(video.getFileName().toString()));
        }
        if (base.isBlank()) {
            base = "video_final";
        }
        return base + "_PTBR.mkv";
    }

    /**
     * PROPÓSITO DE NEGÓCIO: reduz um nome de release à identidade legível usada
     * para comparar vídeo e legenda sem depender de caixa ou separadores.
     *
     * <p>INVARIANTES DO DOMÍNIO: metadados técnicos e marcadores de idioma/faixa
     * não participam da identidade.
     *
     * <p>COMPORTAMENTO EM CASO DE FALHA: nome vazio produz identidade vazia.
     */
    private String normalizarIdentidade(String nome) {
        return sanitizarBase(semExtensao(nome))
            .toLowerCase(Locale.ROOT)
            .replaceAll("[^\\p{L}\\p{N}]+", " ")
            .trim();
    }

    /**
     * PROPÓSITO DE NEGÓCIO: remove ruído técnico preservando partes editoriais do
     * título, como {@code (Narrative)} e a numeração do episódio.
     *
     * <p>INVARIANTES DO DOMÍNIO: grupos entre parênteses só são removidos quando
     * contêm marcadores técnicos conhecidos.
     *
     * <p>COMPORTAMENTO EM CASO DE FALHA: caracteres proibidos são substituídos
     * por espaço e o resultado pode ser vazio para o chamador tratar.
     */
    private String sanitizarBase(String valor) {
        String limpo = valor == null ? "" : valor;
        limpo = limpo.replaceAll("^(?:\\[[^]]+])+\\s*", "");
        limpo = limpo.replaceAll("\\[[^]]+]", " ");
        limpo = PARENTESES_TECNICOS.matcher(limpo).replaceAll(" ");
        limpo = limpo.replace('_', ' ');
        limpo = limpo.replaceAll("(?i)\\b(?:720p|1080p|2160p|4k|x26[45]|av1|hevc|10bit|8bit|bd(?:rip)?|web[- .]?dl|dual\\s*audio)\\b", " ");
        limpo = limpo.replaceAll("(?i)\\b[0-9a-f]{8}\\b", " ");
        limpo = SUFIXOS_LEGENDA.matcher(limpo).replaceAll("");
        limpo = limpo.replaceAll("[<>:\"/\\\\|?*\\p{Cc}]", " ");
        limpo = limpo.replaceAll("\\s+-\\s+", " - ");
        limpo = limpo.replaceAll("\\s+", " ").trim();
        return limpo.replaceAll("[. ]+$", "");
    }

    /**
     * PROPÓSITO DE NEGÓCIO: extrai episódio com regras explícitas antes do
     * fallback numérico, permitindo igualdade exata entre arquivos.
     *
     * <p>INVARIANTES DO DOMÍNIO: retorna ao menos dois dígitos e no máximo três.
     *
     * <p>COMPORTAMENTO EM CASO DE FALHA: ausência de número confiável devolve
     * {@code null}.
     */
    private String extrairTagEpisodio(String nome) {
        if (nome == null) return null;
        for (Pattern padrao : List.of(EPISODIO_OCIDENTAL, EPISODIO_ROTULO, EPISODIO_SEPARADOR)) {
            Matcher matcher = padrao.matcher(nome);
            if (matcher.find()) return padLeft(matcher.group(1));
        }
        Matcher fallback = EPISODIO_FALLBACK.matcher(nome);
        String ultimo = null;
        while (fallback.find()) ultimo = fallback.group(1);
        return ultimo == null ? null : padLeft(ultimo);
    }

    /**
     * PROPÓSITO DE NEGÓCIO: lê a temporada declarada no nome ({@code S01E02} → 1,
     * {@code S00E01} → 0, o especial), para que o pareamento por episódio não junte
     * temporadas diferentes de mesmo número de episódio.
     *
     * <p>INVARIANTES DO DOMÍNIO: só a forma {@code SxxEyy} conta como temporada
     * declarada; {@code S01} e {@code S1} são a mesma temporada.
     *
     * <p>COMPORTAMENTO EM CASO DE FALHA: nome sem {@code SxxEyy} devolve {@code null}
     * (temporada não declarada), nunca lança.
     */
    private Integer extrairTemporada(String nome) {
        if (nome == null) return null;
        Matcher matcher = TEMPORADA_OCIDENTAL.matcher(nome);
        return matcher.find() ? Integer.valueOf(matcher.group(1)) : null;
    }

    /**
     * PROPÓSITO DE NEGÓCIO: padroniza episódio de um dígito para comparação.
     *
     * <p>INVARIANTES DO DOMÍNIO: números com dois ou três dígitos são preservados.
     *
     * <p>COMPORTAMENTO EM CASO DE FALHA: entrada nula permanece nula.
     */
    private String padLeft(String numero) {
        if (numero == null) return null;
        return numero.length() == 1 ? "0" + numero : numero;
    }

    /**
     * PROPÓSITO DE NEGÓCIO: valida as três pastas que delimitam a operação.
     *
     * <p>INVARIANTES DO DOMÍNIO: vídeo e legenda são diretórios existentes; saída
     * existe antes do planejamento.
     *
     * <p>COMPORTAMENTO EM CASO DE FALHA: lança exceção antes de listar arquivos.
     */
    private void validarDiretorios(Path videos, Path legendas, Path saida) {
        if (!Files.isDirectory(videos) || !Files.isDirectory(legendas) || !Files.isDirectory(saida)) {
            throw new RemuxerException("Diretórios de vídeos, legendas ou saída não estão disponíveis.");
        }
    }

    /**
     * PROPÓSITO DE NEGÓCIO: lista arquivos de uma extensão em ordem estável.
     *
     * <p>INVARIANTES DO DOMÍNIO: somente arquivos regulares diretos são incluídos.
     *
     * <p>COMPORTAMENTO EM CASO DE FALHA: erro de I/O vira exceção de domínio.
     */
    private List<Path> listar(Path pasta, String extensao) {
        try (Stream<Path> stream = Files.list(pasta)) {
            return stream.filter(Files::isRegularFile)
                .filter(p -> p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(extensao))
                .sorted(ORDEM_NOME)
                .toList();
        } catch (IOException e) {
            throw new RemuxerException("Erro ao listar arquivos em " + pasta, e);
        }
    }

    /**
     * PROPÓSITO DE NEGÓCIO: lista ASS e SRT finais em ordem determinística.
     *
     * <p>INVARIANTES DO DOMÍNIO: outros formatos e subdiretórios são ignorados.
     *
     * <p>COMPORTAMENTO EM CASO DE FALHA: erro de leitura vira exceção de domínio.
     */
    private List<Path> listarLegendas(Path pasta) {
        try (Stream<Path> stream = Files.list(pasta)) {
            return new ArrayList<>(stream.filter(Files::isRegularFile)
                .filter(p -> {
                    String nome = p.getFileName().toString().toLowerCase(Locale.ROOT);
                    return nome.endsWith(".ass") || nome.endsWith(".srt");
                })
                .sorted(ORDEM_NOME)
                .toList());
        } catch (IOException e) {
            throw new RemuxerException("Erro ao listar legendas em " + pasta, e);
        }
    }

    /**
     * PROPÓSITO DE NEGÓCIO: remove somente a extensão final do nome.
     *
     * <p>INVARIANTES DO DOMÍNIO: pontos internos do título são preservados.
     *
     * <p>COMPORTAMENTO EM CASO DE FALHA: nome sem extensão é devolvido integral.
     */
    private String semExtensao(String nome) {
        int ponto = nome.lastIndexOf('.');
        return ponto > 0 ? nome.substring(0, ponto) : nome;
    }

    /**
     * PROPÓSITO DE NEGÓCIO: transporta a decisão interna de pareamento e sua
     * justificativa para o construtor do plano.
     *
     * <p>INVARIANTES DO DOMÍNIO: legenda nula sempre possui motivo; ambígua indica
     * múltiplas candidatas equivalentes.
     *
     * <p>COMPORTAMENTO EM CASO DE FALHA: ausência é representada sem exceção.
     */
    private record SelecaoLegenda(Path legenda, boolean ambigua, String motivo) {}
}
