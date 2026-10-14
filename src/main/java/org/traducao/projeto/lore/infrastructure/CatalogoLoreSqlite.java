package org.traducao.projeto.lore.infrastructure;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.sqlite.SQLiteConfig;
import org.sqlite.SQLiteDataSource;
import org.traducao.projeto.lore.domain.ProvedorContexto;
import org.traducao.projeto.lore.domain.ProvedorPromptRevisaoLore;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * PROPÓSITO DE NEGÓCIO: lê a lore do KRONOS dos arquivos SQL ({@code /lore/esquema.sql},
 * {@code /lore/obras.lst} e um {@code /lore/obras/<id>.sql} por obra) e entrega as obras pelos mesmos
 * contratos de sempre ({@link ProvedorContexto} e {@link ProvedorPromptRevisaoLore}). Substitui o
 * {@code lore.yaml} de 1 MB por um arquivo de ~16 KB por obra (média de 70, medida em 14/10/2026) — o que a IA e Paulo abrem para anexar
 * ou corrigir lore — sem que nenhum dos consumidores saiba da troca.
 *
 * <h2>Como carrega</h2>
 * Abre um SQLite EM MEMÓRIA, executa o esquema e cada obra da lista numa transação só, confere a
 * integridade, materializa os objetos imutáveis e FECHA o banco. O caminho quente da tradução não
 * toca em banco nem em código nativo: lê os mesmos mapas em memória de antes.
 *
 * <h2>Invariantes do domínio</h2>
 * <ul>
 *   <li><b>Falha FECHADA</b>, como o leitor do YAML: arquivo ausente, BOM, {@code \r}, literal não
 *       fechado, instrução que não seja {@code INSERT INTO <tabela do esquema> VALUES}, restrição do
 *       esquema recusando o dado, lado da tradução vazio ou nome/prompt em branco lançam, nomeando
 *       arquivo e linha. Nunca há catálogo parcial: lore silenciosamente incompleta faria o pipeline
 *       traduzir sem os nomes e gravar o resultado.</li>
 *   <li><b>O arquivo de uma obra só escreve aquela obra.</b> Depois de cada arquivo, as linhas de
 *       OUTRAS obras são recontadas; se mudaram, o arquivo copiou o id de outra obra.</li>
 *   <li><b>Mesma semântica do leitor do YAML</b>, validação por validação: id repetido recusado
 *       (chave primária), terminologia unificada entre tradução e revisão (agora uma tabela só, então
 *       o conflito deixou de ser possível), revisão recebe os termos protegidos da tradução,
 *       equivalências em minúsculas ({@link Locale#ROOT}), revisão vazia permitida.</li>
 *   <li><b>Ordem determinística</b> ({@code ORDER BY}): obras por id, pares e equivalências pela
 *       ordem declarada, mapas e conjuntos pela chave. O leitor do YAML iterava mapas na ordem
 *       aleatória do {@code Map.copyOf}; aqui ela é estável.</li>
 *   <li><b>O banco não sobrevive à construção</b>: nenhum campo guarda conexão.</li>
 * </ul>
 *
 * <h2>Comportamento em caso de falha</h2>
 * Lança {@link IllegalStateException} com arquivo, linha e motivo; {@link UncheckedIOException} se a
 * leitura falhar. O bean que o usa não sobe, e a aplicação não arranca com lore pela metade.
 */
public final class CatalogoLoreSqlite {

    private static final Logger log = LoggerFactory.getLogger(CatalogoLoreSqlite.class);

    /** Pasta dos recursos da lore no classpath. */
    public static final String RAIZ = "/lore/";

    /** As tabelas do esquema — as únicas que um arquivo de obra pode preencher. */
    static final List<String> TABELAS = List.of("obra", "lore_traducao", "lore_revisao", "termo_protegido",
        "correcao_terminologia", "traducao_obrigatoria", "par_inconfundivel", "apelido_pasta", "equivalencia_aceita");

    private static final Pattern INSERCAO = Pattern.compile("(?i)\\AINSERT\\s+INTO\\s+([a-z_]+)\\s+VALUES\\s*");
    private static final Pattern ID_VALIDO = Pattern.compile("[a-z0-9_]+");

    private final List<ProvedorContexto> obras;
    private final List<ProvedorPromptRevisaoLore> obrasRevisao;

    /**
     * PROPÓSITO DE NEGÓCIO: carrega a lore dos recursos padrão do classpath.
     * <p>INVARIANTES DO DOMÍNIO: ver a classe.
     * <p>COMPORTAMENTO EM CASO DE FALHA: lança; não existe catálogo parcial.
     */
    public CatalogoLoreSqlite() {
        this(CatalogoLoreSqlite::lerDoClasspath);
    }

    /**
     * PROPÓSITO DE NEGÓCIO: carrega a lore de um leitor de recursos — usado pelos testes para
     * exercitar arquivos doentes sem tocar nos reais.
     * <p>INVARIANTES DO DOMÍNIO: o leitor recebe o caminho relativo ({@code esquema.sql},
     * {@code obras.lst}, {@code obras/<id>.sql}) e devolve o texto, ou {@code null} se não existir.
     * <p>COMPORTAMENTO EM CASO DE FALHA: recurso ausente lança {@link IllegalStateException}.
     *
     * @param leitor função caminho relativo → conteúdo UTF-8, ou {@code null}
     */
    public CatalogoLoreSqlite(Function<String, String> leitor) {
        long inicio = System.nanoTime();
        String esquema = exigir(leitor, "esquema.sql");
        List<String> ids = lista(exigir(leitor, "obras.lst"));
        int instrucoes = 0;

        SQLiteConfig config = new SQLiteConfig();
        config.enforceForeignKeys(true);
        SQLiteDataSource fonte = new SQLiteDataSource(config);
        fonte.setUrl("jdbc:sqlite::memory:");
        try (Connection c = fonte.getConnection()) {
            c.setAutoCommit(false);
            for (Instrucao i : Divisor.dividir("esquema.sql", esquema)) {
                executar(c, "esquema.sql", i);
            }
            for (String id : ids) {
                String arquivo = "obras/" + id + ".sql";
                String texto = exigir(leitor, arquivo);
                long outrasAntes = linhasDeOutras(c, id);
                for (Instrucao i : Divisor.dividir(arquivo, texto)) {
                    Matcher m = INSERCAO.matcher(i.texto());
                    if (!m.lookingAt() || !TABELAS.contains(m.group(1))
                        || primeiroInvalidoNasTuplas(i.texto().substring(m.end())) >= 0) {
                        throw new IllegalStateException("Instrução não permitida em " + arquivo + ", linha " + i.linha()
                            + ": um arquivo de obra só pode ter INSERT INTO <tabela do esquema> VALUES (...) com "
                            + "valores literais (texto entre aspas, número, NULL). Recebido: " + recorte(i.texto()));
                    }
                    executar(c, arquivo, i);
                    instrucoes++;
                }
                long outrasDepois = linhasDeOutras(c, id);
                if (outrasDepois != outrasAntes) {
                    throw new IllegalStateException("O arquivo " + arquivo + " escreveu " + (outrasDepois - outrasAntes)
                        + " linha(s) de OUTRA obra. Cada arquivo só pode inserir a obra \"" + id
                        + "\" — confira se o id foi trocado em todas as linhas ao copiar o modelo.");
                }
            }
            c.commit();
            conferirIntegridade(c);
            this.obras = materializarTraducao(c);
            this.obrasRevisao = materializarRevisao(c);
        } catch (SQLException e) {
            throw new IllegalStateException("Falha ao montar a lore em SQLite: " + e.getMessage(), e);
        }
        if (obras.isEmpty()) {
            throw new IllegalStateException("A lore não tem nenhuma obra do lado da TRADUÇÃO: o pipeline traduziria "
                + "sem lore nenhuma. Confira obras.lst.");
        }
        log.info("Lore carregada de SQL: {} obras de tradução, {} de revisão, {} instruções, {} ms",
            obras.size(), obrasRevisao.size(), instrucoes, (System.nanoTime() - inicio) / 1_000_000);
    }

    /**
     * PROPÓSITO DE NEGÓCIO: as obras do lado da tradução, por id.
     * <p>INVARIANTES DO DOMÍNIO: lista imutável e nunca vazia.
     * <p>COMPORTAMENTO EM CASO DE FALHA: não lança.
     */
    public List<ProvedorContexto> obras() {
        return obras;
    }

    /**
     * PROPÓSITO DE NEGÓCIO: as obras do lado da revisão de lore, por id.
     * <p>INVARIANTES DO DOMÍNIO: lista imutável; pode ser vazia — sem lore de revisão a Opção 7 só
     * não tem obra a oferecer, diferente da tradução.
     * <p>COMPORTAMENTO EM CASO DE FALHA: não lança.
     */
    public List<ProvedorPromptRevisaoLore> obrasRevisao() {
        return obrasRevisao;
    }

    // ------------------------------------------------------------------ leitura

    private static String lerDoClasspath(String relativo) {
        try (InputStream in = CatalogoLoreSqlite.class.getResourceAsStream(RAIZ + relativo)) {
            return in == null ? null : new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("Falha ao ler a lore: " + RAIZ + relativo, e);
        }
    }

    private static String exigir(Function<String, String> leitor, String relativo) {
        String texto = leitor.apply(relativo);
        if (texto == null) {
            throw new IllegalStateException("Arquivo de lore não encontrado: " + RAIZ + relativo
                + (relativo.startsWith("obras/") ? " (listado em obras.lst)" : ""));
        }
        if (!texto.isEmpty() && texto.charAt(0) == '﻿') {
            throw new IllegalStateException("O arquivo de lore " + relativo + " começa com BOM. Salve em UTF-8 sem BOM.");
        }
        int cr = texto.indexOf('\r');
        if (cr >= 0) {
            throw new IllegalStateException("O arquivo de lore " + relativo + " tem quebra de linha CRLF (linha "
                + (texto.substring(0, cr).chars().filter(ch -> ch == '\n').count() + 1) + "). Um \\r dentro do "
                + "prompt troca o hash do cache e joga fora a tradução da obra — salve com LF (o .gitattributes "
                + "já pede isso).");
        }
        return texto;
    }

    /**
     * PROPÓSITO DE NEGÓCIO: lê {@code obras.lst} — a lista do que existe, na ordem de carga.
     * <p>INVARIANTES DO DOMÍNIO: linha em branco e linha iniciada por {@code #} são ignoradas; id
     * fora de {@code [a-z0-9_]} ou repetido lança; lista vazia lança.
     * <p>COMPORTAMENTO EM CASO DE FALHA: {@link IllegalStateException} nomeando a linha.
     */
    static List<String> lista(String texto) {
        List<String> ids = new ArrayList<>();
        Set<String> vistos = new LinkedHashSet<>();
        String[] linhas = texto.split("\n", -1);
        for (int n = 0; n < linhas.length; n++) {
            String id = linhas[n].strip();
            if (id.isEmpty() || id.startsWith("#")) {
                continue;
            }
            if (!ID_VALIDO.matcher(id).matches()) {
                throw new IllegalStateException("obras.lst, linha " + (n + 1) + ": id inválido \"" + id + "\"");
            }
            if (!vistos.add(id)) {
                throw new IllegalStateException("obras.lst, linha " + (n + 1) + ": id repetido \"" + id + "\"");
            }
            ids.add(id);
        }
        if (ids.isEmpty()) {
            throw new IllegalStateException("obras.lst não lista nenhuma obra");
        }
        return ids;
    }

    private static void executar(Connection c, String arquivo, Instrucao i) {
        try (Statement s = c.createStatement()) {
            s.executeUpdate(i.texto());
        } catch (SQLException e) {
            throw new IllegalStateException("O SQLite recusou " + arquivo + ", linha " + i.linha() + ": "
                + e.getMessage() + conflitoDeTerminologia(c, i, e) + " — em: " + recorte(i.texto()), e);
        }
    }

    /**
     * PROPÓSITO DE NEGÓCIO: quando a mesma forma-ruim chega duas vezes à terminologia de uma obra,
     * diz qual canônico JÁ estava lá. São duas verdades sobre o mesmo termo, e sem os dois valores
     * na mensagem não se sabe qual cadastro está errado.
     * <p>INVARIANTES DO DOMÍNIO: só age em violação de chave de {@code correcao_terminologia}; lê os
     * literais da instrução recusada, sem executar nada dela.
     * <p>COMPORTAMENTO EM CASO DE FALHA: qualquer imprevisto devolve texto vazio — o diagnóstico
     * extra nunca esconde a recusa original.
     */
    private static String conflitoDeTerminologia(Connection c, Instrucao i, SQLException e) {
        if (!String.valueOf(e.getMessage()).contains("correcao_terminologia.forma_ruim")) {
            return "";
        }
        try {
            Matcher m = INSERCAO.matcher(i.texto());
            if (!m.lookingAt()) {
                return "";
            }
            List<String> v = literaisDaPrimeiraTupla(i.texto().substring(m.end()));
            if (v.size() < 3) {
                return "";
            }
            try (PreparedStatement p = c.prepareStatement(
                "SELECT canonico FROM correcao_terminologia WHERE obra_id = ? AND forma_ruim = ?")) {
                p.setString(1, v.get(0));
                p.setString(2, v.get(1));
                try (ResultSet r = p.executeQuery()) {
                    return r.next() ? " (TERMINOLOGIA EM CONFLITO na obra \"" + v.get(0) + "\": a forma \"" + v.get(1)
                        + "\" já aponta para \"" + r.getString(1) + "\" e esta linha quer \"" + v.get(2)
                        + "\" — duas verdades sobre o mesmo termo; corrija o arquivo)" : "";
                }
            }
        } catch (RuntimeException | SQLException outro) {
            return "";
        }
    }

    /** Os valores de texto e número da primeira tupla, com o {@code ''} já desfeito. */
    static List<String> literaisDaPrimeiraTupla(String tuplas) {
        List<String> valores = new ArrayList<>();
        int i = pular(tuplas, 0);
        if (i >= tuplas.length() || tuplas.charAt(i) != '(') {
            return valores;
        }
        i = pular(tuplas, i + 1);
        while (i < tuplas.length()) {
            int fim = valor(tuplas, i);
            if (fim < 0) {
                return valores;
            }
            String bruto = tuplas.substring(i, fim);
            valores.add(bruto.startsWith("'") ? bruto.substring(1, bruto.length() - 1).replace("''", "'") : bruto);
            i = pular(tuplas, fim);
            if (i < tuplas.length() && tuplas.charAt(i) == ',') {
                i = pular(tuplas, i + 1);
            } else {
                return valores;
            }
        }
        return valores;
    }

    private static long linhasDeOutras(Connection c, String id) throws SQLException {
        long total = 0;
        for (String tabela : TABELAS) {
            String coluna = "obra".equals(tabela) ? "id" : "obra_id";
            try (PreparedStatement p = c.prepareStatement("SELECT count(*) FROM " + tabela + " WHERE " + coluna + " <> ?")) {
                p.setString(1, id);
                try (ResultSet r = p.executeQuery()) {
                    r.next();
                    total += r.getLong(1);
                }
            }
        }
        return total;
    }

    private static void conferirIntegridade(Connection c) throws SQLException {
        try (Statement s = c.createStatement(); ResultSet r = s.executeQuery("PRAGMA foreign_key_check")) {
            if (r.next()) {
                throw new IllegalStateException("Chave estrangeira violada na lore: tabela " + r.getString(1));
            }
        }
        try (Statement s = c.createStatement(); ResultSet r = s.executeQuery("PRAGMA integrity_check")) {
            r.next();
            if (!"ok".equals(r.getString(1))) {
                throw new IllegalStateException("integrity_check da lore: " + r.getString(1));
            }
        }
    }

    private static String recorte(String s) {
        String uma = s.replace('\n', ' ');
        return uma.length() > 120 ? uma.substring(0, 120) + "…" : uma;
    }

    // ------------------------------------------------------------------ materialização

    private static List<ProvedorContexto> materializarTraducao(Connection c) throws SQLException {
        List<ProvedorContexto> saida = new ArrayList<>();
        try (Statement s = c.createStatement();
             ResultSet r = s.executeQuery("SELECT obra_id, nome, aparece_na_lista, prompt FROM lore_traducao ORDER BY obra_id")) {
            while (r.next()) {
                String id = r.getString(1);
                saida.add(new ObraDeSql(id, naoEmBranco(id, "nome", r.getString(2)),
                    naoEmBranco(id, "prompt", r.getString(4)),
                    conjunto(c, "SELECT apelido FROM apelido_pasta WHERE obra_id = ? ORDER BY apelido", id),
                    conjunto(c, "SELECT termo FROM termo_protegido WHERE obra_id = ? ORDER BY termo", id),
                    pares(c, id),
                    mapa(c, "SELECT forma_ruim, canonico FROM correcao_terminologia WHERE obra_id = ? ORDER BY forma_ruim", id),
                    mapa(c, "SELECT origem, destino FROM traducao_obrigatoria WHERE obra_id = ? ORDER BY origem", id),
                    r.getInt(3) == 1));
            }
        }
        return List.copyOf(saida);
    }

    private static List<ProvedorPromptRevisaoLore> materializarRevisao(Connection c) throws SQLException {
        List<ProvedorPromptRevisaoLore> saida = new ArrayList<>();
        try (Statement s = c.createStatement();
             ResultSet r = s.executeQuery("SELECT obra_id, nome, prompt FROM lore_revisao ORDER BY obra_id")) {
            while (r.next()) {
                String id = r.getString(1);
                Map<String, List<String>> equivalencias = new LinkedHashMap<>();
                try (PreparedStatement p = c.prepareStatement(
                    "SELECT termo, forma FROM equivalencia_aceita WHERE obra_id = ? ORDER BY termo, ordem")) {
                    p.setString(1, id);
                    try (ResultSet x = p.executeQuery()) {
                        while (x.next()) {
                            equivalencias.computeIfAbsent(x.getString(1).toLowerCase(Locale.ROOT), k -> new ArrayList<>())
                                .add(x.getString(2).toLowerCase(Locale.ROOT));
                        }
                    }
                }
                Map<String, List<String>> imutavel = new LinkedHashMap<>();
                equivalencias.forEach((k, v) -> imutavel.put(k, List.copyOf(v)));
                saida.add(new RevisaoDeSql(id, naoEmBranco(id, "nome da revisão", r.getString(2)),
                    naoEmBranco(id, "prompt da revisão", r.getString(3)),
                    mapa(c, "SELECT forma_ruim, canonico FROM correcao_terminologia WHERE obra_id = ? ORDER BY forma_ruim", id),
                    Collections.unmodifiableMap(imutavel),
                    conjunto(c, "SELECT termo FROM termo_protegido WHERE obra_id = ? ORDER BY termo", id)));
            }
        }
        return List.copyOf(saida);
    }

    private static String naoEmBranco(String id, String campo, String valor) {
        if (valor == null || valor.isBlank()) {
            throw new IllegalStateException("Obra \"" + id + "\" com " + campo + " em branco na lore");
        }
        return valor;
    }

    private static Set<String> conjunto(Connection c, String sql, String id) throws SQLException {
        Set<String> s = new LinkedHashSet<>();
        try (PreparedStatement p = c.prepareStatement(sql)) {
            p.setString(1, id);
            try (ResultSet r = p.executeQuery()) {
                while (r.next()) {
                    s.add(r.getString(1));
                }
            }
        }
        return Collections.unmodifiableSet(s);
    }

    private static Map<String, String> mapa(Connection c, String sql, String id) throws SQLException {
        Map<String, String> m = new LinkedHashMap<>();
        try (PreparedStatement p = c.prepareStatement(sql)) {
            p.setString(1, id);
            try (ResultSet r = p.executeQuery()) {
                while (r.next()) {
                    m.put(r.getString(1), r.getString(2));
                }
            }
        }
        return Collections.unmodifiableMap(m);
    }

    private static Set<List<String>> pares(Connection c, String id) throws SQLException {
        Set<List<String>> s = new LinkedHashSet<>();
        try (PreparedStatement p = c.prepareStatement("SELECT a, b FROM par_inconfundivel WHERE obra_id = ? ORDER BY ordem")) {
            p.setString(1, id);
            try (ResultSet r = p.executeQuery()) {
                while (r.next()) {
                    s.add(List.of(r.getString(1), r.getString(2)));
                }
            }
        }
        return Collections.unmodifiableSet(s);
    }

    // ------------------------------------------------------------------ divisor de instruções

    /** Uma instrução SQL sem os comentários, com a linha em que começa no arquivo. */
    record Instrucao(String texto, int linha) {
    }

    /**
     * PROPÓSITO DE NEGÓCIO: separa um arquivo SQL em instruções. Existe porque o
     * {@code Statement.execute} do driver roda SÓ a primeira instrução de um texto e descarta o resto
     * sem erro (medido em 09/10/2026), e porque cada instrução precisa ser julgada antes de rodar.
     * <p>INVARIANTES DO DOMÍNIO: respeita literal entre aspas simples (com {@code ''} dobrado) — um
     * {@code ;} ou {@code --} dentro do prompt não corta nada; um {@code --} fora de literal é
     * comentário até o fim da linha, e o apóstrofo dentro dele não abre literal.
     * <p>COMPORTAMENTO EM CASO DE FALHA: literal não fechado no fim do arquivo lança nomeando a linha
     * em que ele abriu — é o sintoma do apóstrofo esquecido sem dobrar.
     */
    static final class Divisor {

        private Divisor() {
        }

        static List<Instrucao> dividir(String arquivo, String sql) {
            List<Instrucao> saida = new ArrayList<>();
            StringBuilder atual = new StringBuilder();
            int linha = 1;
            int inicio = -1;
            int linhaDoLiteral = -1;
            boolean emLiteral = false;
            int i = 0;
            while (i < sql.length()) {
                char ch = sql.charAt(i);
                if (emLiteral) {
                    atual.append(ch);
                    if (ch == '\'') {
                        if (i + 1 < sql.length() && sql.charAt(i + 1) == '\'') {
                            atual.append('\'');
                            i += 2;
                            continue;
                        }
                        emLiteral = false;
                    } else if (ch == '\n') {
                        linha++;
                    }
                    i++;
                    continue;
                }
                if (ch == '-' && i + 1 < sql.length() && sql.charAt(i + 1) == '-') {
                    int fim = sql.indexOf('\n', i);
                    i = fim < 0 ? sql.length() : fim;
                    continue;
                }
                if (ch == ';') {
                    String texto = atual.toString().strip();
                    if (!texto.isEmpty()) {
                        saida.add(new Instrucao(texto, inicio));
                    }
                    atual.setLength(0);
                    inicio = -1;
                    i++;
                    continue;
                }
                if (ch == '\n') {
                    linha++;
                }
                if (inicio < 0 && !Character.isWhitespace(ch)) {
                    inicio = linha;
                }
                if (ch == '\'') {
                    emLiteral = true;
                    linhaDoLiteral = linha;
                }
                atual.append(ch);
                i++;
            }
            if (emLiteral) {
                throw new IllegalStateException("Literal não fechado em " + arquivo + ": a aspa aberta na linha "
                    + linhaDoLiteral + " nunca fecha. Apóstrofo dentro de texto tem de ser dobrado ('').");
            }
            String resto = atual.toString().strip();
            if (!resto.isEmpty()) {
                throw new IllegalStateException("Instrução sem ponto e vírgula no fim de " + arquivo + ", linha "
                    + inicio + ": " + recorte(resto));
            }
            return saida;
        }
    }

    // ------------------------------------------------------------------ objetos entregues

    /**
     * PROPÓSITO DE NEGÓCIO: uma obra da tradução, pelo contrato que o resto do sistema consome.
     * <p>INVARIANTES DO DOMÍNIO: campos imutáveis; nenhum método faz I/O. Os dois mapas saem como
     * cópia mutável a cada chamada, como no leitor do YAML — há consumidor que os modifica.
     * <p>COMPORTAMENTO EM CASO DE FALHA: não lança.
     */
    private record ObraDeSql(String id, String nome, String prompt, Set<String> apelidos, Set<String> termos,
                             Set<List<String>> pares, Map<String, String> correcoes, Map<String, String> obrigatorias,
                             boolean apareceNaLista) implements ProvedorContexto {
        @Override
        public String getId() {
            return id;
        }

        @Override
        public String getNomeExibicao() {
            return nome;
        }

        @Override
        public String obterPromptSistema() {
            return prompt;
        }

        @Override
        public Set<String> apelidosPasta() {
            return apelidos;
        }

        @Override
        public Set<String> termosProtegidos() {
            return termos;
        }

        @Override
        public Set<List<String>> paresInconfundiveis() {
            return pares;
        }

        @Override
        public Map<String, String> correcoesTerminologia() {
            return new LinkedHashMap<>(correcoes);
        }

        @Override
        public Map<String, String> traducoesObrigatorias() {
            return new LinkedHashMap<>(obrigatorias);
        }

        @Override
        public boolean apareceNaListaDeObras() {
            return apareceNaLista;
        }
    }

    /**
     * PROPÓSITO DE NEGÓCIO: uma obra do lado da revisão, pelo contrato que a Opção 7 consome.
     * <p>INVARIANTES DO DOMÍNIO: campos imutáveis; sem I/O; termos protegidos são os da tradução da
     * mesma obra (vazio se ela não tiver lado de tradução), como no leitor do YAML.
     * <p>COMPORTAMENTO EM CASO DE FALHA: não lança.
     */
    private record RevisaoDeSql(String id, String nome, String prompt, Map<String, String> correcoes,
                                Map<String, List<String>> equivalencias, Set<String> protegidos)
        implements ProvedorPromptRevisaoLore {
        @Override
        public String getId() {
            return id;
        }

        @Override
        public String getNomeExibicao() {
            return nome;
        }

        @Override
        public String obterPromptSistema() {
            return prompt;
        }

        @Override
        public Map<String, String> correcoesTerminologia() {
            return correcoes;
        }

        @Override
        public Map<String, List<String>> equivalenciasAceitas() {
            return equivalencias;
        }

        @Override
        public Set<String> termosProtegidos() {
            return protegidos;
        }
    }

    /**
     * PROPÓSITO DE NEGÓCIO: confere que, depois de {@code VALUES}, só há tuplas de LITERAIS.
     * <p>INVARIANTES DO DOMÍNIO: valor é texto entre aspas simples (com {@code ''}), inteiro ou
     * {@code NULL}; tuplas separadas por vírgula; nada depois da última. Isto fecha a porta que a
     * expressão regular deixava aberta: {@code ON CONFLICT ... DO UPDATE} alteraria a linha de OUTRA
     * obra sem mudar a contagem de linhas que a guarda por arquivo confere, e função ou subconsulta
     * transformaria dado em código.
     * <p>COMPORTAMENTO EM CASO DE FALHA: devolve a posição do primeiro caractere inválido, ou -1 se
     * estiver tudo certo.
     */
    static int primeiroInvalidoNasTuplas(String s) {
        int i = pular(s, 0);
        while (true) {
            if (i >= s.length() || s.charAt(i) != '(') {
                return i;
            }
            i = pular(s, i + 1);
            while (true) {
                int depois = valor(s, i);
                if (depois < 0) {
                    return i;
                }
                i = pular(s, depois);
                if (i < s.length() && s.charAt(i) == ',') {
                    i = pular(s, i + 1);
                    continue;
                }
                if (i < s.length() && s.charAt(i) == ')') {
                    i = pular(s, i + 1);
                    break;
                }
                return i;
            }
            if (i >= s.length()) {
                return -1;
            }
            if (s.charAt(i) != ',') {
                return i;
            }
            i = pular(s, i + 1);
        }
    }

    private static int pular(String s, int i) {
        while (i < s.length() && Character.isWhitespace(s.charAt(i))) {
            i++;
        }
        return i;
    }

    /** Fim do literal que começa em {@code i}, ou -1 se ali não houver literal permitido. */
    private static int valor(String s, int i) {
        if (i >= s.length()) {
            return -1;
        }
        char ch = s.charAt(i);
        if (ch == '\'') {
            int j = i + 1;
            while (j < s.length()) {
                if (s.charAt(j) == '\'') {
                    if (j + 1 < s.length() && s.charAt(j + 1) == '\'') {
                        j += 2;
                        continue;
                    }
                    return j + 1;
                }
                j++;
            }
            return -1;
        }
        int j = i;
        if (ch == '-') {
            j++;
        }
        int digitos = j;
        while (j < s.length() && Character.isDigit(s.charAt(j))) {
            j++;
        }
        if (j > digitos) {
            return j;
        }
        if (s.regionMatches(true, i, "NULL", 0, 4)
            && (i + 4 >= s.length() || !Character.isLetterOrDigit(s.charAt(i + 4)))) {
            return i + 4;
        }
        return -1;
    }
}
