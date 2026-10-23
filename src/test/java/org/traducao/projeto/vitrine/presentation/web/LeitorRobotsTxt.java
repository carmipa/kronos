package org.traducao.projeto.vitrine.presentation.web;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * PROPÓSITO DE NEGÓCIO: lê um {@code robots.txt} como um buscador lê, para os testes perguntarem
 * "este robô pode buscar este caminho?" em vez de procurarem texto no arquivo. É o ÚNICO leitor de
 * {@code robots.txt} dos testes: dois leitores do mesmo arquivo divergiriam, e a divergência
 * apareceria como os dois lados "corretos".
 *
 * <p>INVARIANTES DO DOMÍNIO:
 * <ul>
 *   <li>Linhas {@code User-agent} consecutivas formam UM grupo; os grupos não se somam — o robô
 *       segue só o grupo que tem o nome dele, e na falta, o {@code *}.</li>
 *   <li>Entre {@code Allow} e {@code Disallow} vence a regra de prefixo mais longo; empate, vence
 *       {@code Allow} (comportamento documentado do Googlebot).</li>
 * </ul>
 *
 * <p>COMPORTAMENTO EM CASO DE FALHA: linha que não é comentário, vazia nem {@code campo: valor}
 * lança {@link IllegalArgumentException} — linha malformada é defeito, não algo a descartar.
 */
final class LeitorRobotsTxt {

    record Regra(boolean permite, String prefixo) {
    }

    private final Map<String, List<Regra>> grupos = new LinkedHashMap<>();
    private final List<String> sitemaps = new ArrayList<>();

    LeitorRobotsTxt(String texto) {
        List<String> agentesDoGrupo = new ArrayList<>();
        boolean grupoTemRegra = false;
        for (String bruta : texto.split("\\R")) {
            String linha = bruta.strip();
            if (linha.isEmpty() || linha.startsWith("#")) {
                continue;
            }
            int doisPontos = linha.indexOf(':');
            if (doisPontos <= 0) {
                throw new IllegalArgumentException("linha malformada no robots.txt: " + linha);
            }
            String campo = linha.substring(0, doisPontos).strip().toLowerCase(Locale.ROOT);
            String valor = linha.substring(doisPontos + 1).strip();
            switch (campo) {
                case "user-agent" -> {
                    if (grupoTemRegra) {
                        agentesDoGrupo = new ArrayList<>();
                        grupoTemRegra = false;
                    }
                    agentesDoGrupo.add(valor.toLowerCase(Locale.ROOT));
                    grupos.putIfAbsent(valor.toLowerCase(Locale.ROOT), new ArrayList<>());
                }
                case "allow", "disallow" -> {
                    if (agentesDoGrupo.isEmpty()) {
                        throw new IllegalArgumentException("regra fora de grupo: " + linha);
                    }
                    grupoTemRegra = true;
                    if (!valor.isEmpty()) {
                        Regra regra = new Regra(campo.equals("allow"), valor);
                        for (String agente : agentesDoGrupo) {
                            grupos.get(agente).add(regra);
                        }
                    }
                }
                case "crawl-delay" -> grupoTemRegra = true;
                case "sitemap" -> sitemaps.add(valor);
                default -> throw new IllegalArgumentException("campo desconhecido no robots.txt: " + linha);
            }
        }
    }

    /** Os grupos declarados, pelo nome do robô em minúsculas. */
    Map<String, List<Regra>> grupos() {
        return grupos;
    }

    List<String> sitemaps() {
        return sitemaps;
    }

    /** O robô {@code agente} pode buscar {@code caminho}? */
    boolean permitido(String agente, String caminho) {
        List<Regra> regras = grupos.get(agente.toLowerCase(Locale.ROOT));
        if (regras == null) {
            regras = grupos.getOrDefault("*", List.of());
        }
        Regra vencedora = null;
        for (Regra regra : regras) {
            if (!caminho.startsWith(regra.prefixo())) {
                continue;
            }
            if (vencedora == null
                || regra.prefixo().length() > vencedora.prefixo().length()
                || (regra.prefixo().length() == vencedora.prefixo().length() && regra.permite())) {
                vencedora = regra;
            }
        }
        return vencedora == null || vencedora.permite();
    }
}
