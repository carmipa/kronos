package org.traducao.projeto.arquitetura;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * PROPÓSITO DE NEGÓCIO: contar as REGRAS declaradas em mais de uma fatia, para que duplicar deixe
 * de ser silencioso. O projeto adota deliberadamente "duplicação consciente &gt; acoplamento" —
 * acoplar {@code novoKaraoke} a {@code qualidadeTraducao} só para reusar uma regex criaria
 * dependência pior que a cópia. Esta catraca NÃO proíbe duplicar; ela exige que a cópia seja
 * DECLARADA, porque cópia não declarada não é decisão, é acidente.
 *
 * <h2>O que motivou a catraca</h2>
 * Em 2026-08-03 três defeitos do acervo tiveram a MESMA assinatura: a regra existia em dois
 * lugares, nenhum sabia do outro, um foi corrigido e o outro ficou.
 * <ul>
 *   <li><b>Fronteira de nome musical</b>: o padrão largo já alcançava {@code OP_S2}, mas
 *       {@code eEstiloDeMusica} usava o de {@code \b}. Dez dos 23 episódios do Guilty Crown
 *       atravessaram a simplificação sem fundir NADA, por nove dias.</li>
 *   <li><b>Pares inconfundíveis</b>: consultados no portão da tradução e não no reforço de cache.
 *       Vinte e duas trocas de entidade ficaram presas no cache do Gundam ZZ.</li>
 *   <li><b>Proporção de romaji</b>: o detector passou a exigir 70% em vez de 100%, e a CÓPIA
 *       dentro do conversor ficou no 100%. Trinta e três versos do Guilty Crown saíram com a
 *       tradução acima do original, porque {@code hitoribocchi} e {@code tsudzuku} reprovam numa
 *       régua que exige sílaba Hepburn pura.</li>
 * </ul>
 * Nos três, o defeito foi LOCALIZÁVEL com precisão — arquivo, linha, palavra — que é a vantagem
 * real da arquitetura por fatias. O que faltou foi o sinal de que a outra cópia existia.
 *
 * <h2>Invariantes do domínio</h2>
 * <ul>
 *   <li>O total é congelado. Duplicar a próxima regra reprova o teste, e quem duplicar tem de
 *       declarar aqui — no código, não em conversa — por que a cópia é melhor que o acoplamento.</li>
 *   <li>Unificar uma regra também reprova, de propósito: o número cair sem alguém confirmar
 *       esconderia acoplamento novo tanto quanto duplicação nova esconde deriva.</li>
 *   <li>Mede só {@code Pattern.compile("...")} com 12+ caracteres. Padrão trivial repetido não é
 *       regra de negócio, e contá-lo afogaria o sinal em ruído.</li>
 * </ul>
 *
 * <h2>Comportamento em caso de falha</h2>
 * A mensagem nomeia a regra e as fatias onde ela vive, para a correção não exigir nova varredura.
 */
class CatracaRegraDuplicadaEntreFatiasTest {

    private static final Path RAIZ = Path.of("src/main/java/org/traducao/projeto");

    /** Corpo do primeiro literal de {@code Pattern.compile("...")}. */
    private static final Pattern DECLARACAO = Pattern.compile(
        "Pattern\\.compile\\(\\s*\"((?:[^\"\\\\]|\\\\.)*)\"", Pattern.DOTALL);

    /**
     * Constante {@code String} com corpo de regex — {@code static final String X = "..."}.
     *
     * <p>Esta segunda forma existe porque a primeira foi CONTORNADA em 2026-08-04. Ao propagar o
     * conserto da quebra {@code \N} para 12 arquivos, a fronteira saiu de dentro de
     * {@code Pattern.compile("...")} e virou constante concatenada. O scanner deixou de vê-la, o
     * total caiu de 14 para 11 e o número foi ajustado — quando a duplicação tinha AUMENTADO: 12
     * cópias da mesma string, em 6 fatias, invisíveis.
     *
     * <p>Catraca que só olha uma forma sintática mede a forma, não a duplicação.
     */
    private static final Pattern CONSTANTE_REGEX = Pattern.compile(
        "static\\s+final\\s+String\\s+\\w+\\s*=\\s*\"((?:[^\"\\\\]|\\\\.)*)\"", Pattern.DOTALL);

    /** Abaixo disto o padrão é trivial ({@code \\s+}, {@code ^\\d+}) e não representa regra. */
    private static final int TAMANHO_MINIMO = 12;

    /**
     * Total MEDIDO em 2026-08-04, contando as DUAS formas de declarar regra. Cada uma destas vive
     * em duas ou mais fatias e nenhuma delas declara a outra — é o inventário da dívida, não um
     * alvo aceitável.
     *
     * <h2>Histórico deste número, porque ele já enganou</h2>
     * Nasceu <b>14</b>, contando só literal dentro de {@code Pattern.compile("...")}.
     *
     * <p>No mesmo dia, ao propagar o conserto da quebra {@code \N} para 12 arquivos, a fronteira
     * saiu de dentro do {@code Pattern.compile} e virou constante {@code INICIO_DE_TERMO}
     * concatenada. O scanner deixou de enxergá-la, o total caiu para <b>11</b> e o número foi
     * ajustado para 11 — <b>quando a duplicação tinha AUMENTADO</b>: 12 cópias da mesma string,
     * em 6 fatias, invisíveis à catraca criada no dia anterior para impedir exatamente isso.
     *
     * <p>Com o scanner enxergando também constante, o número real subiu para <b>15</b>. A lição
     * está no scanner, não aqui: catraca que olha uma forma sintática mede a FORMA, não a
     * duplicação — e escapar dela pode ser acidental, bastando refatorar.
     *
     * <p>Caiu para <b>14</b> ainda em 04/08, e a queda é DÍVIDA PAGA, não catraca afrouxada: a
     * mecânica da quebra {@code \N} saiu das 7 fatias para
     * {@code core.texto.FronteiraTermoAss}. Não é acoplamento novo — {@code core} é consumo livre
     * por contrato e o grafo ArchUnit o ignora, então nenhuma aresta fatia→fatia nasceu disso.
     * E não contraria "duplicação consciente &gt; acoplamento": aquilo vale para REGRA DE NEGÓCIO,
     * que tem versão por fatia. Mecânica de FORMATO não tem — e a cópia dela custou corrupção de
     * tradução correta, porque uma das metades (o separador interno) só existia num arquivo.
     * Quem congela isso agora é {@code CatracaFronteiraQuebraAssTest}, sem número para ajustar.
     */
    /*
     * 14 -> 15 em 07/08/2026. A regra que entrou é {@code ^(?:\{[^}]*\})+} — "blocos de override
     * colados no começo da fala" — que passou a existir em {@code traducao.domain.TextoSemTags}
     * além de {@code trocaTipoLegenda.AchatadorEstilosDecorativosService}.
     *
     * SUBIU, e a cópia está DECLARADA nos dois lados, como esta catraca exige. Não foram
     * unificadas porque as intenções são OPOSTAS e devem poder evoluir separadas: o achatador
     * DESCARTA o bloco líder (é a frescura visual que ele existe para remover), enquanto o
     * TextoSemTags o PRESERVA para vestir de volta a tradução — que agora viaja ao LLM sem
     * nenhuma tag, eliminando a causa de 393 das 412 falas perdidas em 2026-07-22.
     * Unificar criaria aresta entre duas fatias que hoje não se conhecem, e o projeto prefere
     * duplicação consciente a acoplamento.
     *
     * Na mesma mudança o número quase subiu DUAS vezes: a primeira versão do TextoSemTags
     * reimplementava o padrão \k, criando a QUARTA cópia dele. Esta catraca pegou, e a cópia foi
     * eliminada em vez de declarada — naquela fatia o karaokê já é vetado antes por
     * SeletorEventosTraduziveis, então o veto local era redundante.
     */
    /*
     * 15 -> 16 em 20/08/2026. A regra que entrou e o NOME do acervo de karaoke,
     * "telemetria_karaoke_execucoes.jsonl", que passou a existir em
     * {@code traducaoKaraoke.infrastructure.TelemetriaKaraokeDataset.NOME_ARQUIVO} e em
     * {@code telemetria.TelemetriaDatasetService.NOME_ARQUIVO_KARAOKE_LOCAL}.
     *
     * SUBIU, e a copia esta DECLARADA nos dois lados, com o Javadoc de cada um apontando o outro.
     * Nao foram unificadas porque o modulo de telemetria NAO depende de fatia por contrato: ele
     * varre {@code logs/}, exatamente como ja faz com as duas copias irmas que este inventario ja
     * lista — "telemetria_execucoes.jsonl" e "telemetria_traducao.json", ambas [telemetria,
     * traducao]. Importar a constante da fatia criaria a aresta telemetria->traducaoKaraoke que
     * hoje nao existe, para economizar uma string.
     *
     * O risco de copia — renomear um lado e o karaoke sumir do dataset em silencio — nao ficou no
     * comentario: {@code CatracaTelemetriaKaraokeCompletaTest.nomeDoAcervoLocalEIgualNosDoisLados}
     * congela a igualdade dos dois nomes e reprova o build se divergirem.
     */
    /*
     * 16 -> 17 em 15/10/2026. A regra que entrou e o marcador do Request ID nas paginas de erro,
     * <meta name="kc-rid" content="">, que passou a existir em
     * {@code vitrine.presentation.web.GuardaVitrine.META_RID_VAZIA} alem de
     * {@code sistema.presentation.web.PaginasDeErroNavegador.META_RID_VAZIA}.
     *
     * SUBIU, e a copia esta DECLARADA nos dois lados. Nao foram unificadas porque a vitrine NAO
     * depende de outra fatia, e o filtro dela responde antes que o handler do sistema seja
     * alcancado: e por isso que, na VPS, a navegacao para endereco errado mostrava a pagina minima
     * da vitrine e nunca a pagina de erro do kit. Importar o handler criaria a aresta
     * vitrine->sistema para economizar uma string.
     *
     * O risco de copia — o gerador mudar o marcador e um lado parar de injetar o Request ID — nao
     * ficou no comentario: GuardaVitrineHttpTest e PaginasDeErroNavegadorHttpTest leem a pagina
     * REAL gerada e reprovam se o Request ID do cabecalho nao estiver dentro dela.
     */
    private static final int DUPLICADAS_CONHECIDAS = 17;

    @Test
    @DisplayName("nenhuma REGRA nova e duplicada entre fatias sem declaracao")
    void totalDeRegrasDuplicadasEstaCongelado() throws IOException {
        Map<String, Set<String>> fatiasPorRegra = mapearRegras();
        Map<String, Set<String>> duplicadas = new TreeMap<>();
        fatiasPorRegra.forEach((regra, fatias) -> {
            if (fatias.size() > 1) {
                duplicadas.put(regra, fatias);
            }
        });

        assertEquals(DUPLICADAS_CONHECIDAS, duplicadas.size(), () -> """
            O NUMERO DE REGRAS DUPLICADAS ENTRE FATIAS MUDOU.

            Subiu? Uma regra passou a existir em duas fatias. Isso PODE ser certo — o projeto
            prefere duplicacao consciente a acoplamento —, mas a copia tem de ser DECLARADA:
            escreva no Javadoc de cada lado que a outra existe e por que nao foram unificadas.
            Depois atualize DUPLICADAS_CONHECIDAS. Copia nao declarada foi a causa de tres
            defeitos em 2026-08-03, um deles vivo por nove dias.

            Desceu? Alguem unificou uma regra. Confirme que a unificacao nao criou dependencia
            entre fatias que deviam permanecer independentes, e atualize o numero.

            Inventario atual:
            """ + formatar(duplicadas));
    }

    /**
     * PROPÓSITO DE NEGÓCIO: as três regras que causaram defeito medido continuam duplicadas e são
     * nomeadas aqui para que ninguém as "descubra" de novo do zero.
     *
     * <p>INVARIANTES DO DOMÍNIO: este teste FALHA quando uma delas for unificada — e falhar aí é
     * bom: significa que a dívida foi paga e a nota pode sair.
     *
     * <p>COMPORTAMENTO EM CASO DE FALHA: a mensagem diz qual delas mudou.
     */
    @Test
    @DisplayName("as regras que ja causaram defeito seguem nomeadas enquanto duplicadas")
    void regrasComHistoricoDeDerivaEstaoNomeadas() throws IOException {
        Map<String, Set<String>> fatiasPorRegra = mapearRegras();

        String silabaRomaji = fatiasPorRegra.keySet().stream()
            .filter(r -> r.contains("kgsztdnhbpmyrwfjv"))
            .findFirst()
            .orElse(null);
        assertTrue(silabaRomaji != null, "a regex de silaba romaji sumiu do codigo");
        assertTrue(fatiasPorRegra.get(silabaRomaji).size() >= 2, () -> """
            A regex de SILABA ROMAJI foi unificada — otima noticia.
            Ela vivia em legenda, novoKaraoke e traducaoKaraoke; a copia do conversor ficou no
            criterio de 100% depois que o detector passou para 70%, e 33 versos do Guilty Crown
            sairam com a traducao acima do original. Confirme e ajuste esta catraca.
            """);

        String escritaJaponesa = fatiasPorRegra.keySet().stream()
            .filter(r -> r.contains("IsHiragana"))
            .findFirst()
            .orElse(null);
        assertTrue(escritaJaponesa != null, "a regex de escrita japonesa sumiu do codigo");
        assertTrue(fatiasPorRegra.get(escritaJaponesa).size() >= 2,
            "a regex de escrita japonesa foi unificada — confirme e ajuste esta catraca");
    }

    /** Mapeia corpo da regex → fatias em que ela é declarada. */
    private static Map<String, Set<String>> mapearRegras() throws IOException {
        Map<String, Set<String>> mapa = new LinkedHashMap<>();
        try (Stream<Path> arquivos = Files.walk(RAIZ)) {
            for (Path arquivo : arquivos.filter(p -> p.toString().endsWith(".java")).toList()) {
                String fatia = RAIZ.relativize(arquivo).getName(0).toString();
                String fonte = Files.readString(arquivo, StandardCharsets.UTF_8);
                // As DUAS formas de declarar regra: literal dentro de Pattern.compile e constante
                // String concatenada depois. Contar só a primeira permite escapar da catraca
                // movendo o corpo para uma constante — foi o que aconteceu em 2026-08-04.
                for (Pattern forma : new Pattern[] {DECLARACAO, CONSTANTE_REGEX}) {
                    Matcher m = forma.matcher(fonte);
                    while (m.find()) {
                        String corpo = m.group(1);
                        if (corpo.length() >= TAMANHO_MINIMO) {
                            mapa.computeIfAbsent(corpo, k -> new TreeSet<>()).add(fatia);
                        }
                    }
                }
            }
        }
        return mapa;
    }

    private static String formatar(Map<String, Set<String>> duplicadas) {
        StringBuilder sb = new StringBuilder();
        duplicadas.entrySet().stream()
            .sorted(Comparator.comparingInt((Map.Entry<String, Set<String>> e) -> -e.getValue().size()))
            .forEach(e -> sb.append("  ").append(e.getValue()).append("  /")
                .append(e.getKey().length() > 64 ? e.getKey().substring(0, 64) + "…" : e.getKey())
                .append("/").append(System.lineSeparator()));
        return sb.toString();
    }
}
