package org.traducao.projeto.vitrine.arquitetura;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import jakarta.ws.rs.HttpMethod;
import jakarta.ws.rs.Path;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.traducao.projeto.core.vitrine.ModoVitrine;
import org.traducao.projeto.vitrine.application.PoliticaVitrine;
import org.traducao.projeto.vitrine.application.PoliticaVitrine.Acao;
import org.traducao.projeto.vitrine.presentation.bootstrap.ConferenciaArranqueVitrine;

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Collectors;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * PROPÓSITO DE NEGÓCIO: garante que a vitrine pública recusa TODA rota do KRONOS que não está
 * na lista revisada — inclusive rota que nascer amanhã. As rotas são lidas do próprio código
 * compilado (anotações Spring e JAX-RS), nunca da documentação: em 14/10/2026 o
 * {@code docs/ref-api-endpoints.md} listava 35 de 49 rotas.
 *
 * <p>INVARIANTES DO DOMÍNIO:
 * <ul>
 *   <li>O gabarito {@link #PERMITIDAS_REVISADAS} é escrito aqui, a partir do mapa de efeitos de
 *       cada rota — não copiado da {@link PoliticaVitrine} (A3). As duas divergirem reprova.</li>
 *   <li>Toda rota fora do gabarito tem de dar {@link Acao#BLOQUEAR}.</li>
 *   <li>Toda entrada da lista de permitidos corresponde a uma rota real: entrada morta numa lista
 *       de segurança ensina o modelo errado (cicatriz do binmapper, 02/09/2026).</li>
 *   <li>Só a conferência de arranque grava o estado da vitrine no core.</li>
 * </ul>
 *
 * <p>COMPORTAMENTO EM CASO DE FALHA: o instrumento precisa enxergar rotas conhecidas (controle
 * positivo); se não enxergar, reprova dizendo NÃO VERIFICOU — nunca passa por não ver nada.
 */
class VitrineRotasDoCodigoTest {

    private static final String RAIZ = "org.traducao.projeto";

    /** Gabarito revisado: rota (com o parâmetro como no código) e o que a vitrine faz com ela. */
    private static final Map<String, Acao> PERMITIDAS_REVISADAS = new LinkedHashMap<>();

    static {
        PERMITIDAS_REVISADAS.put("GET /api/status", Acao.PERMITIR);
        PERMITIDAS_REVISADAS.put("GET /api/pipeline/status", Acao.PERMITIR);
        PERMITIDAS_REVISADAS.put("GET /api/contextos", Acao.PERMITIR);
        PERMITIDAS_REVISADAS.put("GET /api/revisao-lore/contextos", Acao.PERMITIR);
        PERMITIDAS_REVISADAS.put("GET /api/docs/{pagina}", Acao.PERMITIR);
        PERMITIDAS_REVISADAS.put("GET /api/telemetria", Acao.DEMONSTRAR);
        PERMITIDAS_REVISADAS.put("GET /api/telemetria/fatias", Acao.DEMONSTRAR);
        PERMITIDAS_REVISADAS.put("GET /api/telemetria/fatias/{fatia}", Acao.DEMONSTRAR);
        PERMITIDAS_REVISADAS.put("GET /api/fluxo/status", Acao.DEMONSTRAR);
        PERMITIDAS_REVISADAS.put("GET /api/llm/status", Acao.DEMONSTRAR);
        PERMITIDAS_REVISADAS.put("GET /api/dialogo/capacidade", Acao.DEMONSTRAR);
        PERMITIDAS_REVISADAS.put("GET /api/logs/stream", Acao.SSE_LOGS);
        PERMITIDAS_REVISADAS.put("GET /api/telemetria/stream", Acao.SSE_TELEMETRIA);
    }

    /** Rotas que o instrumento TEM de enxergar; se faltar uma, ele está cego. */
    private static final Set<String> CONTROLE_POSITIVO = Set.of(
        "POST /api/sistema/sair",
        "POST /api/traduzir",
        "POST /api/renomear-arquivos/aplicar",
        "POST /api/auditoria-conteudo",
        "GET /api/navegador/pastas",
        "POST /api/telemetria/publicar-dataset",
        "POST /api/novo-karaoke/simular");

    private static JavaClasses classes;
    private static Set<String> rotas;

    @BeforeAll
    static void lerRotasDoCodigo() throws ClassNotFoundException {
        classes = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages(RAIZ);
        rotas = new TreeSet<>();
        ClassLoader cl = VitrineRotasDoCodigoTest.class.getClassLoader();
        for (JavaClass jc : classes) {
            if (jc.isAnonymousClass() || jc.isLocalClass()) {
                continue;
            }
            Class<?> c = Class.forName(jc.getName(), false, cl);
            rotas.addAll(rotasSpring(c));
            rotas.addAll(rotasJaxRs(c));
        }
    }

    @Test
    @DisplayName("controle positivo: o instrumento enxerga as rotas perigosas conhecidas")
    void instrumentoEnxerga() {
        Set<String> faltando = new TreeSet<>(CONTROLE_POSITIVO);
        faltando.removeAll(rotas);
        assertTrue(faltando.isEmpty(), "NÃO VERIFICOU — o leitor de rotas está cego para " + faltando
            + ". Rotas lidas (" + rotas.size() + "): " + rotas);
        assertTrue(rotas.size() >= 45, "NÃO VERIFICOU — só " + rotas.size() + " rotas lidas: " + rotas);
    }

    @Test
    @DisplayName("toda rota do código fora do gabarito revisado é recusada pela vitrine")
    void todaRotaForaDoGabaritoEhRecusada() {
        Map<String, Acao> divergencias = new TreeMap<>();
        for (String rota : rotas) {
            Acao obtida = decidir(rota);
            Acao esperada = PERMITIDAS_REVISADAS.getOrDefault(rota, Acao.BLOQUEAR);
            if (obtida != esperada) {
                divergencias.put(rota + " (esperado " + esperada + ")", obtida);
            }
        }
        assertTrue(divergencias.isEmpty(), "rotas com decisão divergente do gabarito: " + divergencias);
    }

    @Test
    @DisplayName("nenhuma entrada morta: todo item do gabarito existe no código")
    void gabaritoSemEntradaMorta() {
        List<String> mortas = PERMITIDAS_REVISADAS.keySet().stream()
            .filter(r -> !rotas.contains(r))
            .collect(Collectors.toList());
        assertTrue(mortas.isEmpty(), "entradas do gabarito sem rota no código: " + mortas + " — rotas: " + rotas);

        List<String> listaDaPolitica = new ArrayList<>();
        PoliticaVitrine.GET_REAIS.forEach(c -> listaDaPolitica.add("GET " + c));
        PoliticaVitrine.GET_DEMONSTRACAO.keySet().forEach(c -> listaDaPolitica.add("GET " + c));
        listaDaPolitica.add("GET " + PoliticaVitrine.SSE_LOGS);
        listaDaPolitica.add("GET " + PoliticaVitrine.SSE_TELEMETRIA);
        List<String> mortasNaPolitica = listaDaPolitica.stream().filter(r -> !rotas.contains(r)).toList();
        assertTrue(mortasNaPolitica.isEmpty(), "entrada morta na PoliticaVitrine: " + mortasNaPolitica);
        assertEquals(PERMITIDAS_REVISADAS.size(), listaDaPolitica.size() + 2,
            "a política tem item que o gabarito revisado não tem (ou o contrário)");
    }

    @Test
    @DisplayName("só a conferência de arranque grava o estado da vitrine no core")
    void soOArranqueGravaOEstado() {
        boolean enxerga = classes.get(ConferenciaArranqueVitrine.class).getMethodCallsFromSelf().stream()
            .anyMatch(ch -> ch.getTarget().getName().equals("registrarArranque")
                && ch.getTargetOwner().isEquivalentTo(ModoVitrine.class));
        assertTrue(enxerga, "NÃO VERIFICOU — o importador não enxerga a chamada legítima a registrarArranque");

        noClasses().that().doNotHaveFullyQualifiedName(ConferenciaArranqueVitrine.class.getName())
            .should().callMethod(ModoVitrine.class, "registrarArranque", boolean.class)
            .because("desligar a vitrine durante a execução reabriria as funções na VPS")
            .check(classes);
    }

    private static Acao decidir(String rota) {
        int espaco = rota.indexOf(' ');
        String metodo = rota.substring(0, espaco);
        String caminho = rota.substring(espaco + 1)
            .replace("{pagina}", "arquitetura")
            .replace("{fatia}", "revisao")
            .replaceAll("\\{[^}]+}", "x");
        return PoliticaVitrine.decidir(metodo, caminho, caminho).acao();
    }

    private static List<String> rotasSpring(Class<?> c) {
        List<String> saida = new ArrayList<>();
        List<String> prefixos = List.of("");
        RequestMapping rmClasse = c.getAnnotation(RequestMapping.class);
        if (rmClasse != null) {
            prefixos = caminhos(rmClasse.value(), rmClasse.path());
        }
        for (Method m : c.getDeclaredMethods()) {
            for (Annotation a : m.getAnnotations()) {
                String verbo;
                String[] valores;
                String[] caminhosAlt;
                if (a instanceof GetMapping g) {
                    verbo = "GET"; valores = g.value(); caminhosAlt = g.path();
                } else if (a instanceof PostMapping p) {
                    verbo = "POST"; valores = p.value(); caminhosAlt = p.path();
                } else if (a instanceof PutMapping p) {
                    verbo = "PUT"; valores = p.value(); caminhosAlt = p.path();
                } else if (a instanceof DeleteMapping d) {
                    verbo = "DELETE"; valores = d.value(); caminhosAlt = d.path();
                } else if (a instanceof PatchMapping p) {
                    verbo = "PATCH"; valores = p.value(); caminhosAlt = p.path();
                } else if (a instanceof RequestMapping r) {
                    RequestMethod[] ms = r.method();
                    if (ms.length == 0) {
                        fail("@RequestMapping sem método em " + c.getName() + "#" + m.getName()
                            + ": a guarda não sabe classificar — declare o verbo");
                    }
                    for (RequestMethod rm : ms) {
                        for (String pre : prefixos) {
                            for (String suf : caminhos(r.value(), r.path())) {
                                saida.add(rm.name() + " " + juntar(pre, suf));
                            }
                        }
                    }
                    continue;
                } else {
                    continue;
                }
                for (String pre : prefixos) {
                    for (String suf : caminhos(valores, caminhosAlt)) {
                        saida.add(verbo + " " + juntar(pre, suf));
                    }
                }
            }
        }
        return saida;
    }

    private static List<String> rotasJaxRs(Class<?> c) {
        List<String> saida = new ArrayList<>();
        Path pClasse = c.getAnnotation(Path.class);
        for (Method m : c.getDeclaredMethods()) {
            String verbo = null;
            for (Annotation a : m.getAnnotations()) {
                HttpMethod hm = a.annotationType().getAnnotation(HttpMethod.class);
                if (hm != null) {
                    verbo = hm.value();
                }
            }
            if (verbo == null) {
                continue;
            }
            Path pMetodo = m.getAnnotation(Path.class);
            if (pClasse == null && pMetodo == null) {
                continue;
            }
            saida.add(verbo + " " + juntar(pClasse == null ? "" : pClasse.value(),
                pMetodo == null ? "" : pMetodo.value()));
        }
        return saida;
    }

    private static List<String> caminhos(String[] valor, String[] caminho) {
        List<String> todos = new ArrayList<>();
        todos.addAll(List.of(valor));
        todos.addAll(List.of(caminho));
        return todos.isEmpty() ? List.of("") : todos;
    }

    private static String juntar(String prefixo, String sufixo) {
        String c = ("/" + prefixo + "/" + sufixo).replaceAll("/+", "/");
        if (c.length() > 1 && c.endsWith("/")) {
            c = c.substring(0, c.length() - 1);
        }
        return c;
    }
}
