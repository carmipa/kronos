package org.traducao.projeto.traducaoKaraoke.domain;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * PROPÓSITO DE NEGÓCIO: a tipografia de um estilo do {@code .ass} — fonte, tamanho, contorno e
 * sombra — que a linha portuguesa da música precisa conhecer para se vestir como o diálogo quando
 * a fonte da música não desenha o português (decisão de Paulo, 12/10/2026).
 *
 * <p>INVARIANTES DO DOMÍNIO: os valores ficam como TEXTO, exatamente como o arquivo os escreveu —
 * eles voltam para dentro de tags ({@code \fs70}, {@code \bord2}) e converter para número só
 * arriscaria trocar {@code 70} por {@code 70.0}. As cores ficam no formato do estilo
 * ({@code &HAABBGGRR}); quem as põe em tag usa {@link #corEmTag}.
 *
 * <p>COMPORTAMENTO EM CASO DE FALHA: campo ausente vira {@code null}; quem consulta decide não
 * vestir o que não conhece. Nunca lança.
 */
public record EstiloAss(String nome, String fonte, String tamanho, String corContorno,
                        String corSombra, String contorno, String sombra) {

    /**
     * PROPÓSITO DE NEGÓCIO: lê os estilos do cabeçalho do arquivo, pela linha {@code Format:} da
     * própria seção — a ordem dos campos é do arquivo, não suposta.
     *
     * <p>INVARIANTES DO DOMÍNIO: aceita {@code [V4+ Styles]} e o {@code [V4 Styles]} do SSA
     * antigo, onde o contorno se chama {@code TertiaryColour}. O último campo do {@code Style:} é
     * o único que pode conter vírgula, por isso o corte respeita o tamanho do {@code Format:}.
     *
     * <p>COMPORTAMENTO EM CASO DE FALHA: cabeçalho nulo, sem seção de estilos ou sem
     * {@code Format:} devolve mapa vazio.
     */
    public static Map<String, EstiloAss> doCabecalho(String cabecalho) {
        Map<String, EstiloAss> estilos = new LinkedHashMap<>();
        if (cabecalho == null) {
            return estilos;
        }
        boolean naSecao = false;
        List<String> formato = null;
        for (String bruta : cabecalho.split("\\r?\\n")) {
            String linha = bruta.trim();
            if (linha.startsWith("[")) {
                String secao = linha.toLowerCase(Locale.ROOT);
                naSecao = secao.equals("[v4+ styles]") || secao.equals("[v4 styles]");
                formato = null;
                continue;
            }
            if (!naSecao) {
                continue;
            }
            if (linha.regionMatches(true, 0, "Format:", 0, 7)) {
                formato = new ArrayList<>();
                for (String campo : linha.substring(7).split(",")) {
                    formato.add(campo.trim().toLowerCase(Locale.ROOT));
                }
            } else if (formato != null && linha.regionMatches(true, 0, "Style:", 0, 6)) {
                String[] valores = linha.substring(6).split(",", formato.size());
                Map<String, String> campos = new LinkedHashMap<>();
                for (int i = 0; i < Math.min(valores.length, formato.size()); i++) {
                    campos.put(formato.get(i), valores[i].trim());
                }
                String nome = campos.get("name");
                if (nome == null || nome.isEmpty()) {
                    continue;
                }
                String contorno = campos.getOrDefault("outlinecolour", campos.get("tertiarycolour"));
                estilos.putIfAbsent(nome, new EstiloAss(nome, campos.get("fontname"),
                    campos.get("fontsize"), contorno, campos.get("backcolour"),
                    campos.get("outline"), campos.get("shadow")));
            }
        }
        return estilos;
    }

    /**
     * PROPÓSITO DE NEGÓCIO: procura o estilo pelo nome como o player procura — o nome exato
     * primeiro, e sem diferenciar maiúsculas se o exato não existir.
     *
     * <p>INVARIANTES DO DOMÍNIO: nunca inventa estilo; devolve o do mapa ou {@code null}.
     *
     * <p>COMPORTAMENTO EM CASO DE FALHA: mapa ou nome nulos devolvem {@code null}.
     */
    public static EstiloAss procurar(Map<String, EstiloAss> estilos, String nome) {
        if (estilos == null || nome == null) {
            return null;
        }
        EstiloAss exato = estilos.get(nome);
        if (exato != null) {
            return exato;
        }
        for (Map.Entry<String, EstiloAss> e : estilos.entrySet()) {
            if (e.getKey().equalsIgnoreCase(nome)) {
                return e.getValue();
            }
        }
        return null;
    }

    /**
     * PROPÓSITO DE NEGÓCIO: transforma a cor do estilo ({@code &HAABBGGRR}) no par de tags de
     * cor e transparência que vale dentro do texto ({@code \3c&HBBGGRR&\3a&HAA&}).
     *
     * <p>INVARIANTES DO DOMÍNIO: aceita de 6 a 8 dígitos hexadecimais depois de {@code &H}; com 6,
     * a transparência é {@code 00} (opaco), como o ASS lê.
     *
     * <p>COMPORTAMENTO EM CASO DE FALHA: cor ausente ou em formato desconhecido devolve texto
     * vazio — a tag simplesmente não entra, e o estilo do arquivo continua valendo.
     */
    public static String corEmTag(String corEstilo, int camada) {
        if (corEstilo == null) {
            return "";
        }
        String bruto = corEstilo.trim();
        // SSA v4 ([V4 Styles]) grava a cor em DECIMAL (16777215 = branco). Até 28/10/2026 os 6 a 8
        // dígitos decimais eram lidos como hexadecimal — "16777215" virava &H777215 (auditoria, A8).
        if (bruto.matches("-?\\d+") && !bruto.regionMatches(true, 0, "&H", 0, 2)) {
            try {
                long valor = Long.parseLong(bruto) & 0xFFFFFFFFL;
                bruto = String.format(Locale.ROOT, "%08X", valor);
            } catch (NumberFormatException e) {
                return "";
            }
        }
        String hex = bruto.replaceFirst("(?i)^&H", "").replace("&", "");
        if (!hex.matches("[0-9A-Fa-f]{6,8}")) {
            return "";
        }
        String alfa = hex.length() > 6 ? hex.substring(0, hex.length() - 6) : "00";
        if (alfa.length() == 1) {
            alfa = "0" + alfa;
        }
        String bgr = hex.substring(hex.length() - 6);
        return "\\" + camada + "c&H" + bgr.toUpperCase(Locale.ROOT) + "&\\" + camada + "a&H"
            + alfa.toUpperCase(Locale.ROOT) + "&";
    }
}
