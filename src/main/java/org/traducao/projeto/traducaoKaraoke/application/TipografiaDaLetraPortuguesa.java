package org.traducao.projeto.traducaoKaraoke.application;

import org.traducao.projeto.core.texto.FonteVietnamitaLegada;
import org.traducao.projeto.legenda.domain.DocumentoLegenda;
import org.traducao.projeto.legenda.domain.EventoLegenda;
import org.traducao.projeto.traducaoKaraoke.domain.ClasseLinhaKaraoke;
import org.traducao.projeto.traducaoKaraoke.domain.EstiloAss;
import org.traducao.projeto.traducaoKaraoke.domain.FontesDoVideo;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * PROPÓSITO DE NEGÓCIO: veste a linha PORTUGUESA da música com a tipografia do diálogo quando a
 * fonte da música não sabe desenhar o português — decisão de Paulo em 12/10/2026, depois de ver na
 * TV a abertura do Unicorn com o "ê" de "Você" saindo como um "Ê" solto e o "?" em outra fonte.
 *
 * <h2>As duas situações</h2>
 * <ul>
 *   <li><b>Glifo faltando.</b> A fonte efetiva da linha (o {@code \fn} dela ou a do estilo) não
 *       desenha algum caractere do português, segundo as fontes embutidas no vídeo: a linha ganha
 *       {@code \fn} e {@code \fs} do estilo do diálogo. Só a linha portuguesa muda; a original
 *       empilhada acima continua com a fonte estilizada.</li>
 *   <li><b>Frase de KFX que ficou só em português.</b> A moldura dela foi desenhada para ter as
 *       palavras do efeito por cima, que davam o contorno — no Unicorn ela é {@code \bord0}.
 *       Sem o efeito, o português ficaria branco e sem contorno sobre cena clara; a linha ganha o
 *       contorno, a sombra e as cores deles do estilo do diálogo.</li>
 * </ul>
 *
 * <h2>Qual é o estilo do diálogo</h2>
 * O de maior TEMPO DE TELA (união dos intervalos) entre as falas que o plano classificou fora de
 * música — a mesma régua do achatador, pelo mesmo prejuízo: contar eventos elegia o logo animado
 * quadro a quadro.
 *
 * <p>INVARIANTES DO DOMÍNIO: nunca troca a fonte sem ter VERIFICADO, nas fontes do vídeo, que a da
 * música não desenha o texto (A8: não verificado não autoriza mudança). A fonte do diálogo que não
 * viaja no vídeo é aceita — é a que o player já usa para todas as falas do episódio —, menos a de
 * codificação vietnamita antiga ({@link #codificacaoAntiga}), que tem glifo e desenha a letra
 * errada. Não mexe no
 * texto visível, só acrescenta tags ao primeiro bloco da linha. As fontes só são lidas quando
 * alguma linha precisa (vídeo grande, leitura de segundos).
 *
 * <p>COMPORTAMENTO EM CASO DE FALHA: sem estilo do diálogo, sem estilo da música, ou fontes não
 * verificadas, a linha volta intacta e o motivo fica em {@link #estadoDasFontes()}. Nunca lança.
 */
public final class TipografiaDaLetraPortuguesa {

    private static final Pattern FONTE_INLINE = Pattern.compile("\\\\fn([^\\\\}]*)");
    /** Os blocos de tag que abrem a linha, antes do primeiro caractere visível. */
    private static final Pattern BLOCOS_DE_ABERTURA = Pattern.compile("(?:\\{[^}]*\\})+");

    private final Map<String, EstiloAss> estilos;
    private final EstiloAss dialogo;
    private final Supplier<FontesDoVideo> leitorDeFontes;
    private FontesDoVideo fontes;
    private int linhasComFonteDoDialogo;
    private boolean dialogoDeCodificacaoAntiga;
    private final Set<String> familiasNaoEmbutidas = new LinkedHashSet<>();

    private TipografiaDaLetraPortuguesa(Map<String, EstiloAss> estilos, EstiloAss dialogo,
                                        Supplier<FontesDoVideo> leitorDeFontes) {
        this.estilos = estilos;
        this.dialogo = dialogo;
        this.leitorDeFontes = leitorDeFontes;
    }

    /**
     * PROPÓSITO DE NEGÓCIO: prepara a tipografia de UM arquivo — estilos do cabeçalho e estilo do
     * diálogo — deixando a leitura das fontes para quando for preciso.
     *
     * <p>INVARIANTES DO DOMÍNIO: o estilo do diálogo sai das falas FORA_DE_MUSICA do plano, nunca
     * de letra de música.
     *
     * <p>COMPORTAMENTO EM CASO DE FALHA: documento sem estilos ou sem fala devolve uma tipografia
     * que não veste nada e diz por quê.
     */
    public static TipografiaDaLetraPortuguesa doArquivo(DocumentoLegenda documento,
            PlanoDeClassificacao plano, Supplier<FontesDoVideo> leitorDeFontes) {
        Map<String, EstiloAss> estilos = EstiloAss.doCabecalho(documento == null ? null : documento.cabecalho());
        String nomeDialogo = estiloDoDialogo(documento, plano);
        EstiloAss dialogo = EstiloAss.procurar(estilos, nomeDialogo);
        return new TipografiaDaLetraPortuguesa(estilos, dialogo,
            leitorDeFontes == null ? () -> FontesDoVideo.naoVerificadas("leitor de fontes ausente") : leitorDeFontes);
    }

    /**
     * PROPÓSITO DE NEGÓCIO: devolve a linha portuguesa com as tags que a tornam legível, ou
     * intacta quando nada precisa mudar.
     *
     * <p>INVARIANTES DO DOMÍNIO: ver a classe. {@code soPortuguesNoLugarDoKfx} só acrescenta
     * contorno/sombra; a fonte continua dependendo da verificação de glifo.
     *
     * <p>COMPORTAMENTO EM CASO DE FALHA: entrada nula volta como veio.
     */
    public String vestir(EventoLegenda evento, String traduzido, boolean soPortuguesNoLugarDoKfx) {
        if (evento == null || traduzido == null || traduzido.isBlank() || dialogo == null) {
            return traduzido;
        }
        EstiloAss musica = EstiloAss.procurar(estilos, evento.estilo());
        StringBuilder tags = new StringBuilder();
        String fonteEfetiva = fonteEfetiva(traduzido, musica);
        if (fonteEfetiva != null && dialogo.fonte() != null && !mesmaFonte(fonteEfetiva, dialogo.fonte())) {
            String visivel = visivel(traduzido);
            Optional<String> faltam = fontes().cobertura().caracteresSemDesenho(fonteEfetiva, visivel);
            if (faltam.isEmpty()) {
                familiasNaoEmbutidas.add(fonteEfetiva);
            } else if (!faltam.get().isEmpty() && dialogoDesenha(visivel)) {
                tags.append("\\fn").append(dialogo.fonte());
                if (dialogo.tamanho() != null) {
                    tags.append("\\fs").append(dialogo.tamanho());
                }
                linhasComFonteDoDialogo++;
            }
        }
        if (soPortuguesNoLugarDoKfx) {
            if (dialogo.contorno() != null) {
                tags.append("\\bord").append(dialogo.contorno());
            }
            if (dialogo.sombra() != null) {
                tags.append("\\shad").append(dialogo.sombra());
            }
            tags.append(EstiloAss.corEmTag(dialogo.corContorno(), 3));
            tags.append(EstiloAss.corEmTag(dialogo.corSombra(), 4));
        }
        return tags.isEmpty() ? traduzido : noPrimeiroBloco(traduzido, tags.toString());
    }

    /** Linhas portuguesas que receberam a fonte do diálogo neste arquivo. */
    public int linhasComFonteDoDialogo() {
        return linhasComFonteDoDialogo;
    }

    /**
     * PROPÓSITO DE NEGÓCIO: diz, em uma palavra e um motivo, se a fonte da música foi conferida —
     * para o resumo do arquivo e o dataset (três estados, A8).
     *
     * <p>INVARIANTES DO DOMÍNIO: {@code NAO_NECESSARIO} quando nenhuma linha precisou perguntar;
     * {@code VERIFICADAS} quando o vídeo foi lido e toda família consultada estava nele;
     * {@code NAO_VERIFICADAS: ...} com o motivo em qualquer outro caso.
     *
     * <p>COMPORTAMENTO EM CASO DE FALHA: nunca devolve nulo.
     */
    public String estadoDasFontes() {
        if (dialogo == null) {
            return "NAO_VERIFICADAS: estilo do dialogo nao identificado";
        }
        if (dialogoDeCodificacaoAntiga) {
            return "NAO_VERIFICADAS: fonte do dialogo de codificacao antiga (" + dialogo.fonte()
                + ") desenha outra letra no lugar do acento; troca nao feita — rode a 1.3 Troca de Tipo"
                + " de Legenda antes da 4.1";
        }
        if (fontes == null) {
            return "NAO_NECESSARIO";
        }
        if (!fontes.verificadas()) {
            return "NAO_VERIFICADAS: " + fontes.motivoNaoVerificado();
        }
        if (!familiasNaoEmbutidas.isEmpty()) {
            return "NAO_VERIFICADAS: fonte(s) da musica fora do video " + familiasNaoEmbutidas;
        }
        return "VERIFICADAS (" + fontes.origem() + ")";
    }

    private FontesDoVideo fontes() {
        if (fontes == null) {
            FontesDoVideo lidas;
            try {
                lidas = leitorDeFontes.get();
            } catch (RuntimeException e) {
                lidas = FontesDoVideo.naoVerificadas("falha ao ler as fontes: " + e.getMessage());
            }
            fontes = lidas == null ? FontesDoVideo.naoVerificadas("leitor de fontes nao respondeu") : lidas;
        }
        return fontes;
    }

    private boolean dialogoDesenha(String visivel) {
        if (codificacaoAntiga(dialogo.fonte())) {
            dialogoDeCodificacaoAntiga = true;
            return false;
        }
        Optional<String> faltamNoDialogo = fontes().cobertura().caracteresSemDesenho(dialogo.fonte(), visivel);
        // Fora do vídeo, a fonte do diálogo é a que o player já usa para as falas do episódio.
        return faltamNoDialogo.isEmpty() || faltamNoDialogo.get().isEmpty();
    }

    /**
     * PROPÓSITO DE NEGÓCIO: reconhece a fonte vietnamita de codificação antiga — TCVN3
     * ({@code .VnTime}, {@code .VnBook-Antiqua}) e VNI ({@code VNI-Times}) — que TEM glifo no lugar
     * do "ç" e do "ã", só que é outra letra. Medido em 12/10/2026 no F91: o estilo do diálogo usa
     * {@code .VnBook-Antiqua}, o leitor de fontes respondeu "desenha tudo", e na tela "É hora da
     * atração" saiu "ã hora da atraÕóo".
     *
     * <p>INVARIANTES DO DOMÍNIO: a conferência de glifo responde se EXISTE desenho, não se ele é a
     * letra certa; para essas famílias a resposta não vale, e a troca não acontece (A8). Quem decide
     * é a regra única do kernel: a cópia por prefixo que morava aqui ({@code .vn}, {@code vni-}) não
     * reconhecia o {@code VnBook-Antiqua} SEM ponto do Gundam ZZ (14/10/2026).
     *
     * <p>COMPORTAMENTO EM CASO DE FALHA: nome nulo não é codificação antiga.
     */
    static boolean codificacaoAntiga(String familia) {
        return FonteVietnamitaLegada.ehLegada(familia);
    }

    private static String fonteEfetiva(String traduzido, EstiloAss musica) {
        Matcher abertura = BLOCOS_DE_ABERTURA.matcher(traduzido);
        String prefixo = abertura.lookingAt() ? abertura.group() : "";
        Matcher m = FONTE_INLINE.matcher(prefixo);
        String ultima = null;
        while (m.find()) {
            ultima = m.group(1).trim();
        }
        if (ultima != null && !ultima.isEmpty()) {
            return ultima;
        }
        return musica == null ? null : musica.fonte();
    }

    private static boolean mesmaFonte(String a, String b) {
        return semArroba(a).equalsIgnoreCase(semArroba(b));
    }

    private static String semArroba(String fonte) {
        String f = fonte.trim();
        return f.startsWith("@") ? f.substring(1) : f;
    }

    private static String visivel(String texto) {
        return ClassificadorLetraKaraokeService.extrairTextoVisivel(texto)
            .replace("\\N", " ").replace("\\n", " ").replace("\\h", " ");
    }

    private static String noPrimeiroBloco(String texto, String tags) {
        if (texto.startsWith("{")) {
            int fim = texto.indexOf('}');
            if (fim > 0) {
                return texto.substring(0, fim) + tags + texto.substring(fim);
            }
        }
        return "{" + tags + "}" + texto;
    }

    /**
     * O estilo de maior tempo de tela entre as falas fora de música. União dos intervalos, não
     * soma: camadas sobrepostas do mesmo estilo não valem duas vezes.
     */
    static String estiloDoDialogo(DocumentoLegenda documento, PlanoDeClassificacao plano) {
        if (documento == null || documento.eventos() == null || plano == null) {
            return null;
        }
        Map<String, List<long[]>> porEstilo = new HashMap<>();
        List<EventoLegenda> eventos = documento.eventos();
        for (int i = 0; i < eventos.size(); i++) {
            EventoLegenda ev = eventos.get(i);
            if (plano.classeNaPosicao(i) != ClasseLinhaKaraoke.FORA_DE_MUSICA || !ev.isDialogo()
                || !ev.temTexto() || ev.estilo() == null || visivel(ev.texto()).isBlank()) {
                continue;
            }
            long ini = tempoCs(ev, 1);
            long fim = tempoCs(ev, 2);
            if (ini >= 0 && fim > ini) {
                porEstilo.computeIfAbsent(ev.estilo(), k -> new ArrayList<>()).add(new long[] {ini, fim});
            }
        }
        String melhor = null;
        long melhorTempo = -1;
        for (Map.Entry<String, List<long[]>> e : porEstilo.entrySet()) {
            long tempo = uniao(e.getValue());
            if (tempo > melhorTempo || (tempo == melhorTempo && melhor != null && e.getKey().compareTo(melhor) < 0)) {
                melhor = e.getKey();
                melhorTempo = tempo;
            }
        }
        return melhor;
    }

    private static long uniao(List<long[]> intervalos) {
        intervalos.sort((a, b) -> Long.compare(a[0], b[0]));
        long total = 0;
        long ini = -1;
        long fim = -1;
        for (long[] j : intervalos) {
            if (j[0] > fim) {
                if (fim > ini) {
                    total += fim - ini;
                }
                ini = j[0];
                fim = j[1];
            } else if (j[1] > fim) {
                fim = j[1];
            }
        }
        if (fim > ini) {
            total += fim - ini;
        }
        return total;
    }


    private static long tempoCs(EventoLegenda ev, int campo) {
        if (ev.prefixo() == null) {
            return -1;
        }
        String[] campos = ev.prefixo().split(",", -1);
        if (campos.length <= campo) {
            return -1;
        }
        String[] partes = campos[campo].trim().split(":");
        if (partes.length != 3) {
            return -1;
        }
        try {
            return Long.parseLong(partes[0].trim()) * 360000L + Long.parseLong(partes[1].trim()) * 6000L
                + Math.round(Double.parseDouble(partes[2].trim().replace(',', '.')) * 100);
        } catch (NumberFormatException e) {
            return -1;
        }
    }
}
