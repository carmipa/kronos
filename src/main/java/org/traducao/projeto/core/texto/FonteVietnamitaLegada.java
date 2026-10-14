package org.traducao.projeto.core.texto;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * PROPÓSITO DE NEGÓCIO: reconhecer, numa legenda ASS, a fonte vietnamita de CODIFICAÇÃO ANTIGA —
 * TCVN3 ({@code .VnBook-Antiqua}, {@code .VnTime}, e as mesmas sem o ponto) e VNI ({@code VNI-Times})
 * — que TEM desenho no lugar do "ç" e do "ã", só que é outra letra. Na tela, "Você está aqui? Não,
 * é a atração!" sai "Vocờ estở aqui? Nóo, ộ a atraỗóo!", e maiúscula acentuada some. A conferência
 * de glifo (cmap, {@code canDisplay}) responde "desenha" e não serve; só o nome da família avisa.
 *
 * <h2>O prejuízo que obrigou a fonte ÚNICA</h2>
 * <ul>
 *   <li>F91, 12/10/2026: o diálogo inteiro publicado em {@code .VnBook-Antiqua} ("ã hora da
 *       atraÕóo").</li>
 *   <li>Gundam ZZ, 14/10/2026: 10.514 de 16.467 falas publicadas em {@code VnBook-Antiqua} — SEM o
 *       ponto. A 4.1 tinha a regra por prefixo ({@code .vn}, {@code vni-}) e não a reconhecia; a 1.3
 *       tinha uma lista de nomes exatos e não reconhecia {@code .VnTime} nem {@code VNI-Times}. Duas
 *       cópias da mesma verdade, cada uma cega para o que a outra via. Mecânica de formato não tem
 *       versão por fatia: divergir aqui é defeito, não evolução — por isso mora no kernel.</li>
 * </ul>
 *
 * <h2>Invariantes do domínio</h2>
 * <ul>
 *   <li>É legada: começa por {@code .vn} ou {@code vni-} (sem diferenciar maiúsculas), ou é
 *       {@code Vn} seguido de MAIÚSCULA ({@code VnBook}, {@code VnTimes}), ou é um dos nomes que a
 *       1.3 já conhecia em minúsculas. Um {@code @} na frente (fonte vertical) não muda nada.</li>
 *   <li>NÃO é legada: {@code VNF-…} (as vietnamitas modernas, em Unicode), {@code Vinque Sans},
 *       {@code Verdana} — o sinal "começa com V/Vn" sozinho não basta.</li>
 *   <li>BANIDA em qualquer lugar da legenda (Paulo, 14/10/2026: "banir esse tipo de fonte"): todo
 *       estilo do cabeçalho, usado ou não, e todo {@code \fn} dentro de linha {@code Dialogue:} ou
 *       {@code Comment:} — os dois tipos de evento que o leitor ASS do KRONOS lê com texto e que a
 *       1.3 reescreve. Estilo sem fala e comentário não desenham HOJE, mas basta alguém reativar a
 *       linha ou apontar uma fala para o estilo (a 4.1 escolhe a fonte do diálogo para a letra) e a
 *       letra trocada volta à tela sem aviso. O motivo diz se o estilo está ou não na tela.</li>
 *   <li>Só JDK, sem regex: o kernel não conhece fatia nem framework.</li>
 * </ul>
 *
 * <h2>Comportamento em caso de falha</h2>
 * Nunca lança. Nome nulo não é legado; cabeçalho sem seção de estilos devolve mapa vazio; legenda
 * sem {@code [Events]} devolve lista vazia — quem decide bloquear é o chamador.
 */
public final class FonteVietnamitaLegada {

    /** Os nomes que a lista da 1.3 aceitava sem ponto e em qualquer caixa — mantidos para não regredir. */
    private static final Set<String> NOMES_CONHECIDOS = Set.of("vnbook-antiqua", "vnarial", "vntimes", "vntime");

    private static final String MARCA_FONTE_INLINE = "\\fn";

    /**
     * Uma linha de evento reduzida ao que importa aqui; {@code naTela} é {@code Dialogue:}, falso é
     * {@code Comment:}.
     */
    public record Fala(String estilo, String texto, boolean naTela) {

        /** Linha {@code Dialogue:}. */
        public Fala(String estilo, String texto) {
            this(estilo, texto, true);
        }
    }

    private FonteVietnamitaLegada() {
    }

    /**
     * PROPÓSITO DE NEGÓCIO: diz se a família de fonte é de codificação vietnamita antiga.
     *
     * <p>INVARIANTES DO DOMÍNIO: ver a classe.
     *
     * <p>COMPORTAMENTO EM CASO DE FALHA: nulo ou vazio devolve {@code false}.
     */
    public static boolean ehLegada(String familia) {
        if (familia == null) {
            return false;
        }
        String f = familia.trim();
        if (f.startsWith("@")) {
            f = f.substring(1).trim();
        }
        String minuscula = f.toLowerCase(Locale.ROOT);
        if (minuscula.startsWith(".vn") || minuscula.startsWith("vni-")) {
            return true;
        }
        if (f.length() > 2 && f.charAt(0) == 'V' && f.charAt(1) == 'n' && Character.isUpperCase(f.charAt(2))) {
            return true;
        }
        return NOMES_CONHECIDOS.contains(minuscula);
    }

    /**
     * PROPÓSITO DE NEGÓCIO: lista os estilos do cabeçalho cuja fonte é legada, com a fonte de cada um.
     *
     * <p>INVARIANTES DO DOMÍNIO: lê só a seção {@code [V4+ Styles]}/{@code [V4 Styles]}, pelas colunas
     * que o {@code Format:} dela declara; a ordem é a do arquivo.
     *
     * <p>COMPORTAMENTO EM CASO DE FALHA: cabeçalho nulo, sem a seção ou sem as colunas Name e
     * Fontname devolve mapa vazio.
     */
    public static Map<String, String> estilosComFonteLegada(String cabecalho) {
        Map<String, String> legados = new LinkedHashMap<>();
        if (cabecalho == null) {
            return legados;
        }
        boolean emEstilos = false;
        int idxNome = -1;
        int idxFonte = -1;
        int colunas = 0;
        for (String bruta : cabecalho.split("\r\n|\n", -1)) {
            String linha = bruta.trim();
            if (linha.startsWith("[") && linha.endsWith("]")) {
                emEstilos = linha.equalsIgnoreCase("[V4+ Styles]") || linha.equalsIgnoreCase("[V4 Styles]");
                continue;
            }
            if (!emEstilos) {
                continue;
            }
            if (linha.regionMatches(true, 0, "Format:", 0, 7)) {
                String[] nomes = linha.substring(7).split(",");
                colunas = nomes.length;
                for (int i = 0; i < nomes.length; i++) {
                    String n = nomes[i].trim();
                    if (n.equalsIgnoreCase("Name")) idxNome = i;
                    if (n.equalsIgnoreCase("Fontname")) idxFonte = i;
                }
                continue;
            }
            if (linha.regionMatches(true, 0, "Style:", 0, 6) && idxNome >= 0 && idxFonte >= 0) {
                String[] partes = linha.substring(6).split(",", colunas);
                if (partes.length > Math.max(idxNome, idxFonte)) {
                    String fonte = partes[idxFonte].trim();
                    if (ehLegada(fonte)) {
                        legados.put(partes[idxNome].trim(), fonte);
                    }
                }
            }
        }
        return legados;
    }

    /**
     * PROPÓSITO DE NEGÓCIO: lista as fontes escritas com {@code \fn} nos blocos de tag de uma fala.
     *
     * <p>INVARIANTES DO DOMÍNIO: só dentro de {@code {...}}; o nome vai até a próxima barra ou o fim
     * do bloco, sem espaços nas pontas.
     *
     * <p>COMPORTAMENTO EM CASO DE FALHA: texto nulo ou sem bloco devolve lista vazia.
     */
    public static List<String> fontesInline(String texto) {
        List<String> fontes = new ArrayList<>();
        if (texto == null) {
            return fontes;
        }
        int abre = texto.indexOf('{');
        while (abre >= 0) {
            int fecha = texto.indexOf('}', abre);
            if (fecha < 0) {
                break;
            }
            String bloco = texto.substring(abre + 1, fecha);
            int i = bloco.indexOf(MARCA_FONTE_INLINE);
            while (i >= 0) {
                int inicio = i + MARCA_FONTE_INLINE.length();
                int fim = bloco.indexOf('\\', inicio);
                fontes.add(bloco.substring(inicio, fim < 0 ? bloco.length() : fim).trim());
                i = fim < 0 ? -1 : bloco.indexOf(MARCA_FONTE_INLINE, fim);
            }
            abre = texto.indexOf('{', fecha);
        }
        return fontes;
    }

    /**
     * PROPÓSITO DE NEGÓCIO: troca, dentro dos blocos de tag da fala, cada {@code \fn} de fonte legada
     * pela fonte pedida — a parte da troca que o cabeçalho sozinho não alcança.
     *
     * <p>INVARIANTES DO DOMÍNIO: o texto visível e as outras tags ficam byte a byte; {@code \fn} de
     * fonte comum não é tocado.
     *
     * <p>COMPORTAMENTO EM CASO DE FALHA: texto nulo volta nulo; sem nada a trocar, volta o mesmo.
     */
    public static String trocarFontesInlineLegadas(String texto, String fonteNova) {
        if (texto == null || texto.indexOf(MARCA_FONTE_INLINE) < 0) {
            return texto;
        }
        StringBuilder saida = new StringBuilder(texto.length());
        int cursor = 0;
        int abre = texto.indexOf('{');
        while (abre >= 0) {
            int fecha = texto.indexOf('}', abre);
            if (fecha < 0) {
                break;
            }
            saida.append(texto, cursor, abre + 1);
            String bloco = texto.substring(abre + 1, fecha);
            StringBuilder novo = new StringBuilder(bloco.length());
            int pos = 0;
            int i = bloco.indexOf(MARCA_FONTE_INLINE);
            while (i >= 0) {
                int inicio = i + MARCA_FONTE_INLINE.length();
                int fim = bloco.indexOf('\\', inicio);
                int ate = fim < 0 ? bloco.length() : fim;
                novo.append(bloco, pos, inicio);
                String nome = bloco.substring(inicio, ate);
                novo.append(ehLegada(nome) ? fonteNova : nome);
                pos = ate;
                i = fim < 0 ? -1 : bloco.indexOf(MARCA_FONTE_INLINE, fim);
            }
            novo.append(bloco, pos, bloco.length());
            saida.append(novo).append('}');
            cursor = fecha + 1;
            abre = texto.indexOf('{', cursor);
        }
        saida.append(texto, cursor, texto.length());
        return saida.toString();
    }

    /**
     * PROPÓSITO DE NEGÓCIO: diz, em frases curtas para o operador, onde a legenda tem fonte legada —
     * o motivo de reprovar e pedir a 1.3.
     *
     * <p>INVARIANTES DO DOMÍNIO: todo estilo legado do cabeçalho conta, na ordem do arquivo; o que
     * nenhuma linha {@code Dialogue:} usa sai marcado "(nenhuma fala na tela usa)" (nome comparado sem
     * caixa, com o {@code *} de estilo padrão ignorado). {@code \fn} legado conta por família, em
     * fala e em comentário, com o número de linhas.
     *
     * <p>COMPORTAMENTO EM CASO DE FALHA: sem achado, lista vazia; nunca lança.
     */
    public static List<String> achados(String cabecalho, List<Fala> falas) {
        Map<String, String> legados = new LinkedHashMap<>();
        estilosComFonteLegada(cabecalho).forEach((estilo, fonte) -> legados.put(normalizarEstilo(estilo), estilo + "\" = " + fonte));
        Set<String> estilosNaTela = new LinkedHashSet<>();
        Map<String, Integer> inline = new LinkedHashMap<>();
        for (Fala fala : falas == null ? List.<Fala>of() : falas) {
            if (fala.naTela()) {
                estilosNaTela.add(normalizarEstilo(fala.estilo()));
            }
            for (String fonte : fontesInline(fala.texto())) {
                if (ehLegada(fonte)) {
                    inline.merge(fonte, 1, Integer::sum);
                }
            }
        }
        List<String> achados = new ArrayList<>();
        legados.forEach((estilo, descricao) -> achados.add("estilo \"" + descricao
            + (estilosNaTela.contains(estilo) ? "" : " (nenhuma fala na tela usa)")));
        inline.forEach((fonte, n) -> achados.add("\\fn" + fonte + " em " + n + " linha(s)"));
        return achados;
    }

    /**
     * PROPÓSITO DE NEGÓCIO: o mesmo diagnóstico de {@link #achados(String, List)} a partir do arquivo
     * ASS inteiro em texto — para quem não tem o documento já lido, como o remux.
     *
     * <p>INVARIANTES DO DOMÍNIO: cabeçalho é tudo antes de {@code [Events]}; as colunas Style e Text
     * vêm do {@code Format:} de {@code [Events]} (sem ele, o padrão ASS de 10 colunas); linhas
     * {@code Dialogue:} e {@code Comment:} — o universo que a 1.3 consegue reescrever.
     *
     * <p>COMPORTAMENTO EM CASO DE FALHA: conteúdo nulo ou sem {@code [Events]} devolve lista vazia.
     */
    public static List<String> achadosNoAss(String conteudo) {
        if (conteudo == null) {
            return List.of();
        }
        String texto = conteudo.startsWith("﻿") ? conteudo.substring(1) : conteudo;
        String[] linhas = texto.split("\r\n|\n", -1);
        StringBuilder cabecalho = new StringBuilder();
        int i = 0;
        for (; i < linhas.length; i++) {
            if (linhas[i].trim().equalsIgnoreCase("[Events]")) {
                break;
            }
            cabecalho.append(linhas[i]).append('\n');
        }
        if (i >= linhas.length) {
            return List.of();
        }
        int colunas = 10;
        int idxEstilo = 3;
        int idxTexto = 9;
        List<Fala> falas = new ArrayList<>();
        for (i = i + 1; i < linhas.length; i++) {
            String linha = linhas[i];
            String aparada = linha.trim();
            if (aparada.startsWith("[") && aparada.endsWith("]")) {
                break;
            }
            if (aparada.regionMatches(true, 0, "Format:", 0, 7)) {
                String[] nomes = aparada.substring(7).split(",");
                colunas = nomes.length;
                for (int c = 0; c < nomes.length; c++) {
                    String n = nomes[c].trim();
                    if (n.equalsIgnoreCase("Style")) idxEstilo = c;
                    if (n.equalsIgnoreCase("Text")) idxTexto = c;
                }
                continue;
            }
            boolean dialogo = aparada.startsWith("Dialogue:");
            if (dialogo || aparada.startsWith("Comment:")) {
                String[] partes = linha.substring(linha.indexOf(':') + 1).split(",", colunas);
                if (partes.length > Math.max(idxEstilo, idxTexto)) {
                    falas.add(new Fala(partes[idxEstilo].trim(), partes[idxTexto], dialogo));
                }
            }
        }
        return achados(cabecalho.toString(), falas);
    }

    private static String normalizarEstilo(String estilo) {
        if (estilo == null) {
            return "";
        }
        String e = estilo.trim();
        if (e.startsWith("*")) {
            e = e.substring(1);
        }
        return e.toLowerCase(Locale.ROOT);
    }
}
