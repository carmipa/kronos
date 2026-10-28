package org.traducao.projeto.traducao.application;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.traducao.projeto.cachetraducao.domain.ProvenienciaCache;
import org.traducao.projeto.legenda.domain.ArquivoLegendaException;
import org.traducao.projeto.legenda.domain.CarimboCabecalhoLegenda;
import org.traducao.projeto.legenda.domain.DocumentoLegenda;
import org.traducao.projeto.legenda.domain.EventoLegenda;
import org.traducao.projeto.legenda.infrastructure.EscritorLegendaAss;
import org.traducao.projeto.legenda.infrastructure.EscritorLegendaSrt;
import org.traducao.projeto.legenda.infrastructure.LeitorLegendaAss;
import org.traducao.projeto.legenda.infrastructure.LeitorLegendaSrt;
import org.traducao.projeto.traducao.presentation.ui.ConsoleUILogger;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * PROPÓSITO DE NEGÓCIO: publica a legenda traduzida da 2.1 sem desfazer o que outras telas e
 * pessoas corrigiram direto no arquivo — mescla de três vias contra a base da publicação
 * anterior, backup antes de toda substituição e troca atômica.
 *
 * <h2>Por que é uma classe à parte (auditoria de 28/10/2026)</h2>
 * Morava dentro do {@link ProcessarArquivoUseCase}, que passava de 1.600 linhas e 30
 * dependências: a mescla só era testável montando o caso de uso inteiro. A mecânica é a
 * mesma de antes, movida sem mudança de comportamento; o caso de uso a monta com as dependências
 * que já recebe, então o grafo CDI e os construtores não mudam.
 *
 * <h2>Invariantes do domínio</h2>
 * <ul>
 *   <li>A base é a última geração publicada NAQUELA pasta de saída (INV-MESCLA-001).</li>
 *   <li>Toda substituição é precedida de backup; falha no backup aborta a publicação.</li>
 *   <li>Conteúdo idêntico não é regravado; o carimbo do cabeçalho é sempre o da execução.</li>
 * </ul>
 *
 * <h2>Comportamento em caso de falha</h2>
 * Falha ao gerar, comparar ou fazer backup lança {@link ArquivoLegendaException} antes de o
 * destino ser tocado. Falha ao ler a base degrada para a substituição com backup; falha ao
 * gravar a base só é avisada.
 */
final class PublicadorLegendaMescla {

    private static final Logger log = LoggerFactory.getLogger(PublicadorLegendaMescla.class);

    private final LeitorLegendaAss leitor;
    private final LeitorLegendaSrt leitorSrt;
    private final EscritorLegendaAss escritor;
    private final EscritorLegendaSrt escritorSrt;
    private final ConsoleUILogger uiLogger;
    private final PoliticaBackupTraducao politicaBackup;

    PublicadorLegendaMescla(LeitorLegendaAss leitor, LeitorLegendaSrt leitorSrt, EscritorLegendaAss escritor,
            EscritorLegendaSrt escritorSrt, ConsoleUILogger uiLogger, PoliticaBackupTraducao politicaBackup) {
        this.leitor = leitor;
        this.leitorSrt = leitorSrt;
        this.escritor = escritor;
        this.escritorSrt = escritorSrt;
        this.uiLogger = uiLogger;
        this.politicaBackup = politicaBackup;
    }

    private void escreverLegenda(Path destino, DocumentoLegenda documento, boolean ehSrt) {
        if (ehSrt) {
            escritorSrt.escrever(destino, documento);
        } else {
            escritor.escrever(destino, documento);
        }
    }

    /**
     * O que a publicação fez: o backup da versão anterior (quando houve substituição) e os índices
     * das falas mantidas do arquivo — corrigidas nele por outra tela ou à mão —, que a conferência
     * pós-gravação não pode tratar como divergência.
     */
    record ResultadoPublicacao(Path backup, Set<Integer> preservadasDoArquivo) {
        static final ResultadoPublicacao NADA = new ResultadoPublicacao(null, Set.of());
    }

    /** Resultado da mesclagem de três vias. */
    private record Mescla(DocumentoLegenda documento, Set<Integer> preservadas) {}

    /**
     * PROPÓSITO DE NEGÓCIO: publica a legenda PT-BR sem destruir o que foi corrigido nela depois da
     * última publicação, e sem deixar de entregar o que foi corrigido no cache.
     *
     * <h2>O prejuízo que originou (auditoria de 25/09/2026)</h2>
     * A Tradução Local regenera a legenda a partir do CACHE. As telas que corrigem a legenda depois
     * dela — Revisão de Lore, Concordância, a correção de tags, a troca de fonte e o Aegisub —
     * escrevem direto no {@code .ass} e não tocam no cache. Reexecutar a 2.1 com a proteção LIGADA
     * regravava o {@code _PT-BR} e desfazia essas correções sem backup (medido no 0080 E02). E
     * reexecutar é justamente o que a tela da 2.3 manda fazer para publicar o cache corrigido — os
     * dois fluxos legítimos colidiam no mesmo arquivo.
     *
     * <h2>Invariantes do domínio</h2>
     * <ul>
     *   <li>MESCLAGEM DE TRÊS VIAS por linha, com a BASE = o que o cache gerou na publicação
     *       anterior (gravada a cada publicação em {@code cache/<obra>/.publicado/}): linha que o
     *       arquivo não mudou recebe a do cache (fluxo da 2.3); linha que o arquivo mudou e o cache
     *       não é MANTIDA (correção humana ou de outra tela); linha mudada nos dois lados é conflito,
     *       mantida a do arquivo e avisada.</li>
     *   <li>Sem base, ou com o arquivo estruturalmente diferente (linhas incluídas ou removidas), não
     *       há como separar os dois casos: substitui pelo cache COM backup obrigatório e aviso.</li>
     *   <li>Conteúdo final idêntico ao publicado não é regravado — uma reexecução sem mudança
     *       produz o arquivo byte a byte igual, então não há backup por ruído.</li>
     *   <li>Toda substituição é precedida de backup; falha no backup aborta a publicação.</li>
     *   <li>Retradução liberada é substituição PEDIDA: não mescla, faz backup e publica o cache.</li>
     * </ul>
     *
     * <h2>Comportamento em caso de falha</h2>
     * Falha ao gerar, comparar ou fazer backup lança {@link ArquivoLegendaException} antes de o
     * destino ser tocado. Falha ao ler a base ou o arquivo para mesclar degrada para a substituição
     * com backup (declarada no console). Falha ao gravar a base só é avisada — a legenda já foi
     * publicada, e a próxima reexecução cai no caminho sem base.
     */
    /**
     * PROPÓSITO DE NEGÓCIO: publica a legenda gerada em {@code destino}, mesclando com o que já
     * está lá contra a base da publicação anterior DESTA pasta de saída.
     *
     * <p>INVARIANTES DO DOMÍNIO: as de {@link #publicar(Path, DocumentoLegenda, boolean, boolean,
     * List, Path, Path)}; a base é a da pasta de saída (INV-MESCLA-001), e a antiga por nome só
     * vale como migração.
     *
     * <p>COMPORTAMENTO EM CASO DE FALHA: falha ao gerar, comparar ou fazer backup lança
     * {@link ArquivoLegendaException} antes de o destino ser tocado.
     */
    ResultadoPublicacao publicar(Path destino, DocumentoLegenda gerado, boolean ehSrt,
            boolean substituicaoPedida, List<String> avisos, Path arquivoCache) {
        return publicar(destino, gerado, ehSrt, substituicaoPedida, avisos,
            basePorPastaDeSaida(arquivoCache, destino), baseLegadaPorNome(arquivoCache, destino));
    }

    private ResultadoPublicacao publicar(Path destino, DocumentoLegenda gerado, boolean ehSrt,
            boolean substituicaoPedida, List<String> avisos, Path base, Path baseLegada) {
        if (!Files.exists(destino)) {
            escreverLegenda(destino, gerado, ehSrt);
            registrarBase(base, gerado, ehSrt, destino);
            return ResultadoPublicacao.NADA;
        }
        // MIGRAÇÃO: antes de 28/10/2026 a base era só pelo nome do arquivo. Ela vale UMA vez, até a
        // primeira publicação gravar a base desta pasta de saída.
        Path baseParaMesclar = base != null && Files.isRegularFile(base) ? base : baseLegada;
        Mescla mescla = substituicaoPedida ? null : mesclar(destino, gerado, ehSrt, baseParaMesclar);
        DocumentoLegenda aPublicar = mescla != null ? mescla.documento() : gerado;
        Path backup = publicarPreservandoAnterior(destino, aPublicar, ehSrt, substituicaoPedida, avisos,
            mescla != null);
        registrarBase(base, gerado, ehSrt, destino);
        return new ResultadoPublicacao(backup, mescla != null ? mescla.preservadas() : Set.of());
    }

    private Mescla mesclar(Path destino, DocumentoLegenda gerado, boolean ehSrt, Path base) {
        if (base == null || !Files.isRegularFile(base)) {
            uiLogger.log("[ MESCLA ] " + destino.getFileName() + ": sem registro da publicação anterior"
                + " — não dá para separar correção feita no arquivo de correção feita no cache.");
            return null;
        }
        DocumentoLegenda noArquivo;
        DocumentoLegenda publicadoAntes;
        try {
            noArquivo = ehSrt ? leitorSrt.ler(destino) : leitor.ler(destino);
            publicadoAntes = ehSrt ? leitorSrt.ler(base) : leitor.ler(base);
        } catch (RuntimeException naoDeu) {
            uiLogger.log("[ MESCLA ] " + destino.getFileName() + ": não consegui ler o arquivo ou a base ("
                + naoDeu.getMessage() + ") — mesclagem NÃO feita.");
            return null;
        }
        List<EventoLegenda> d = noArquivo.eventos();
        List<EventoLegenda> b = publicadoAntes.eventos();
        List<EventoLegenda> n = gerado.eventos();
        if (d.size() != b.size() || b.size() != n.size()) {
            uiLogger.log("[ MESCLA ] " + destino.getFileName() + ": o arquivo tem " + d.size()
                + " linha(s) de evento e a publicação anterior tinha " + b.size()
                + " — estrutura mudou, mesclagem linha a linha NÃO é segura.");
            return null;
        }
        List<EventoLegenda> saida = new ArrayList<>(n.size());
        Set<Integer> preservadas = new java.util.LinkedHashSet<>();
        int atualizadas = 0;
        int conflitos = 0;
        String exemplo = null;
        for (int i = 0; i < n.size(); i++) {
            String kd = chaveDaLinha(d.get(i));
            String kb = chaveDaLinha(b.get(i));
            String kn = chaveDaLinha(n.get(i));
            if (kd.equals(kb)) {
                saida.add(n.get(i));
                if (!kn.equals(kb)) {
                    atualizadas++;
                }
            } else if (kn.equals(kb) || kn.equals(kd)) {
                saida.add(d.get(i));
                if (!kn.equals(kd)) {
                    preservadas.add(d.get(i).indice());
                }
            } else {
                saida.add(d.get(i));
                preservadas.add(d.get(i).indice());
                conflitos++;
                if (exemplo == null && d.get(i).temTexto() && n.get(i).temTexto()) {
                    exemplo = "no arquivo: \"" + recortar(d.get(i).texto())
                        + "\" | o cache gera agora: \"" + recortar(n.get(i).texto()) + "\"";
                }
            }
        }
        // O CARIMBO NÃO ENTRA NA COMPARAÇÃO (auditoria de 28/10/2026). Ele muda a cada execução, e
        // comparado junto fazia o cabeçalho editado no arquivo (1.3 trocando fonte) valer INTEIRO,
        // com o carimbo antigo — "pendentes: 5" para sempre, depois de a 2.3 resolver as cinco.
        // É a classe do "carimbo mentiroso" de 09/09: o artefato tem de declarar o que ESTA
        // execução fez (decisão de 07/08).
        String hn = gerado.cabecalho();
        String hd = CarimboCabecalhoLegenda.remover(noArquivo.cabecalho());
        boolean cabecalhoPreservado = !hd.equals(CarimboCabecalhoLegenda.remover(publicadoAntes.cabecalho()))
            && !hd.equals(CarimboCabecalhoLegenda.remover(hn));
        String cabecalho = cabecalhoPreservado ? CarimboCabecalhoLegenda.aplicar(hd, linhasDoCarimbo(hn)) : hn;

        if (!preservadas.isEmpty() || atualizadas > 0 || cabecalhoPreservado) {
            String resumo = destino.getFileName() + ": " + preservadas.size()
                + " fala(s) corrigida(s) direto no arquivo mantida(s) (o cache não as conhece), "
                + atualizadas + " atualizada(s) pelo cache"
                + (conflitos > 0 ? ", " + conflitos + " CONFLITO(s) — mudou no arquivo E no cache;"
                    + " mantida a do arquivo" + (exemplo != null ? ". Ex.: " + exemplo : "") : "")
                + (cabecalhoPreservado ? "; cabeçalho editado no arquivo (estilos/fonte) mantido" : "")
                + ".";
            log.info("[ MESCLA ] {}", resumo);
            uiLogger.log("[ MESCLA ] " + resumo);
        }
        return new Mescla(new DocumentoLegenda(cabecalho, saida, gerado.quebraDeLinha(), gerado.comBom()),
            preservadas);
    }

    private static String chaveDaLinha(EventoLegenda evento) {
        return evento.prefixo() + '\u0000' + (evento.texto() == null ? "" : evento.texto());
    }

    private static String recortar(String texto) {
        return texto.length() > 60 ? texto.substring(0, 60) + "…" : texto;
    }

    /**
     * PROPÓSITO DE NEGÓCIO: endereço da base da mescla de três vias PARA ESTA PASTA DE SAÍDA
     * (INV-MESCLA-001). A base era só {@code .publicado/<nome>}: duas pastas de saída para a
     * mesma entrada (a padrão e uma escolhida na tela, ou o reuso entre modelos) dividiam a base, e
     * a correção da 2.3 publicada numa virava "correção feita no arquivo" na outra — nunca chegava
     * lá, e o console dizia "mantida" (auditoria de 28/10/2026).
     *
     * <p>INVARIANTES DO DOMÍNIO: a pasta vira um identificador estável (SHA-256 do caminho absoluto
     * normalizado, em minúsculas porque o Windows não distingue caixa); o caminho legível fica em
     * {@code ORIGEM.txt} ao lado, para quem abrir a pasta.
     *
     * <p>COMPORTAMENTO EM CASO DE FALHA: não lança; sem pasta-mãe, usa a raiz {@code .publicado}.
     */
    static Path basePorPastaDeSaida(Path arquivoCache, Path arquivoSaida) {
        Path pasta = arquivoSaida.toAbsolutePath().normalize().getParent();
        Path publicado = arquivoCache.getParent().resolve(".publicado");
        if (pasta == null) {
            return publicado.resolve(arquivoSaida.getFileName().toString());
        }
        return publicado.resolve(ProvenienciaCache.hashDe(pasta.toString().toLowerCase(java.util.Locale.ROOT)).substring(0, 12))
            .resolve(arquivoSaida.getFileName().toString());
    }

    /** As linhas do carimbo {@code ; KRONOS} de um cabeçalho, sem o fim de linha. */
    private static List<String> linhasDoCarimbo(String cabecalho) {
        return cabecalho.lines()
            .filter(l -> l.startsWith(CarimboCabecalhoLegenda.PREFIXO))
            .map(String::strip)
            .toList();
    }

    /** O endereço antigo da base, só pelo nome — lido uma vez, como migração. */
    private static Path baseLegadaPorNome(Path arquivoCache, Path arquivoSaida) {
        return arquivoCache.getParent().resolve(".publicado").resolve(arquivoSaida.getFileName().toString());
    }

    private void registrarBase(Path base, DocumentoLegenda gerado, boolean ehSrt, Path destino) {
        if (base == null) {
            return;
        }
        try {
            Files.createDirectories(base.getParent());
            escreverLegenda(base, gerado, ehSrt);
            Path origem = base.getParent().resolve("ORIGEM.txt");
            if (!base.getParent().getFileName().toString().equals(".publicado") && !Files.exists(origem)) {
                Files.writeString(origem, "pasta de saida desta base: "
                    + destino.toAbsolutePath().normalize().getParent() + System.lineSeparator(),
                    java.nio.charset.StandardCharsets.UTF_8);
            }
        } catch (RuntimeException | java.io.IOException naoDeu) {
            log.warn("Registro da publicação {} não gravado: {}", base, naoDeu.getMessage());
            uiLogger.log("[ MESCLA ] registro da publicação NÃO gravado (" + naoDeu.getMessage()
                + ") — a próxima reexecução não conseguirá preservar correções feitas no arquivo.");
        }
    }

    /**
     * PROPÓSITO DE NEGÓCIO: substitui a legenda existente pelo conteúdo já decidido, com backup
     * obrigatório quando ela muda e sem tocar no arquivo quando é idêntica.
     *
     * <p>INVARIANTES DO DOMÍNIO: backup antes de qualquer substituição; troca atômica; conteúdo
     * idêntico não é regravado; sem mesclagem ({@code mesclado == false}) a diferença é avisada com
     * os dois casos possíveis, porque aí o sistema não sabe separar correção do cache de correção
     * feita no arquivo.
     *
     * <p>COMPORTAMENTO EM CASO DE FALHA: falha ao gerar, comparar ou fazer backup lança
     * {@link ArquivoLegendaException} antes de o destino ser tocado; o temporário é sempre apagado.
     *
     * @return o caminho do backup criado, ou {@code null} quando nada precisou ser substituído
     */
    private Path publicarPreservandoAnterior(Path destino, DocumentoLegenda documento, boolean ehSrt,
            boolean substituicaoPedida, List<String> avisos, boolean mesclado) {
        Path gerado;
        try {
            gerado = Files.createTempFile(destino.toAbsolutePath().getParent(),
                destino.getFileName().toString(), ".gerando.tmp");
        } catch (java.io.IOException e) {
            throw new ArquivoLegendaException("Não consegui preparar a nova versão de " + destino, e);
        }
        try {
            escreverLegenda(gerado, documento, ehSrt);
            boolean identico = Files.mismatch(gerado, destino) == -1L;
            if (identico && !substituicaoPedida) {
                uiLogger.log("[ SAIDA ] " + destino.getFileName()
                    + ": conteúdo idêntico ao já publicado — mantido sem regravar.");
                return null;
            }
            Path backup = politicaBackup.criarBackupAntesSobrescrita(destino);
            if (!identico && !substituicaoPedida && !mesclado) {
                // Decodificação TOLERANTE: o arquivo anterior pode ter sido salvo por outro programa
                // com byte inválido, e o aviso nunca pode ser o motivo de a publicação falhar.
                List<String> antes = new String(Files.readAllBytes(destino),
                    java.nio.charset.StandardCharsets.UTF_8).lines().toList();
                List<String> depois = new String(Files.readAllBytes(gerado),
                    java.nio.charset.StandardCharsets.UTF_8).lines().toList();
                int diferentes = Math.abs(antes.size() - depois.size());
                String exemplo = null;
                for (int i = 0; i < Math.min(antes.size(), depois.size()); i++) {
                    if (!antes.get(i).equals(depois.get(i))) {
                        diferentes++;
                        if (exemplo == null && antes.get(i).startsWith("Dialogue:")) {
                            exemplo = "no arquivo: \"" + textoDaLinha(antes.get(i))
                                + "\" → agora: \"" + textoDaLinha(depois.get(i)) + "\"";
                        }
                    }
                }
                // Os DOIS casos legítimos que produzem esta diferença pedem reações opostas, e o
                // aviso tem de dizer os dois: se foi o cache que mudou (Correção de Cache, 2.3), a
                // diferença É a correção chegando; se foi a legenda corrigida direto no .ass, a
                // correção acabou de ser substituída. Sem o registro da publicação anterior, o
                // sistema não sabe qual dos dois aconteceu.
                String aviso = "A legenda já publicada " + destino.getFileName() + " DIFERIA do que o cache"
                    + " gera em " + diferentes + " linha(s) e foi substituída pela versão do cache; a anterior"
                    + " está no backup. Se você corrigiu o cache (Correção de Cache), é a correção chegando."
                    + " Se a legenda tinha sido corrigida direto no .ass (Revisão de Lore, Concordância,"
                    + " edição manual), essa correção está no backup e não no arquivo."
                    + (exemplo != null ? " Exemplo: " + exemplo : "");
                log.warn("{} Backup: {}", aviso, backup);
                uiLogger.log("[ ATENÇÃO ] " + aviso + " Backup: " + backup);
                avisos.add(aviso);
            }
            org.traducao.projeto.core.util.ArquivoAtomicoUtil.substituirAtomico(gerado, destino);
            return backup;
        } catch (java.io.IOException e) {
            throw new ArquivoLegendaException("Falha ao publicar " + destino + " preservando a versão anterior", e);
        } finally {
            try {
                Files.deleteIfExists(gerado);
            } catch (java.io.IOException ignorada) {
                log.warn("Temporário {} não pôde ser apagado", gerado);
            }
        }
    }

    private static String textoDaLinha(String linhaAss) {
        String[] campos = linhaAss.split(",", 10);
        String texto = campos.length == 10 ? campos[9] : linhaAss;
        return texto.length() > 60 ? texto.substring(0, 60) + "…" : texto;
    }
}
