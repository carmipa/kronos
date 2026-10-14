package org.traducao.projeto.arquitetura;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * PROPÓSITO DE NEGÓCIO: a interface do KRONOS se traduz pelo widget do Google (página inteira,
 * cookie {@code googtrans} + reload), nunca por dicionário. Esta catraca congela o que a regra de
 * internacionalização do projeto exige e o que já custou caro aqui.
 *
 * <h2>O prejuízo que originou</h2>
 * Medido em 14/10/2026 no contêiner da vitrine, com o Google bloqueado e a bandeira inglesa clicada:
 * a página continuou em português e o {@code <html>} passou a declarar {@code lang="en-US"} — o
 * {@code definirIdioma} trocava o atributo. Leitor de tela lia o português com a voz inglesa. E o
 * aviso da falha saía em português, para quem tinha acabado de dizer que não lê português.
 *
 * <h2>Invariantes do domínio</h2>
 * <ul>
 *   <li>nenhum script da tela troca o {@code lang} do documento (quem troca, depois de traduzir, é o
 *       próprio Google); elemento próprio com {@code lang} — o aviso de falha — é legítimo;</li>
 *   <li>o {@code index.html} declara {@code <html lang="pt-BR">};</li>
 *   <li>o div do widget existe e não se traduz; o callback {@code googleTranslateElementInit} é
 *       definido ANTES de o {@code element.js} ser anexado, e o anexo é {@code async};</li>
 *   <li>ícone e código (Material Symbols, {@code code}, {@code pre}, {@code kbd}, {@code samp}) estão
 *       na lista protegida — nome de glifo traduzido apaga o ícone;</li>
 *   <li>nenhum resquício de dicionário ({@code data-i18n=}, {@code data-i18n-key});</li>
 *   <li>o aviso de falha existe em inglês e em espanhol, com {@code translate="no"}.</li>
 * </ul>
 *
 * <h2>Comportamento em caso de falha</h2>
 * Nomeia o arquivo e o trecho. Alvo vazio (nenhum script encontrado) reprova como instrumento cego,
 * nunca aprova. O comportamento no navegador — tradução real, aviso com o Google bloqueado, ícones
 * intactos — é provado por {@code ferramentas/navegador/verificar-i18n.mjs}, que esta catraca não
 * substitui.
 */
class CatracaI18nAutomaticaTest {

    private static final Path ESTATICOS = Path.of("src", "main", "resources", "static");
    private static final Path I18N_JS = ESTATICOS.resolve("i18n").resolve("i18n.js");
    private static final Path INDEX = ESTATICOS.resolve("index.html");

    /** Atribuição ou setAttribute de {@code lang} no PRÓPRIO documento — não em outro elemento. */
    private static final Pattern TROCA_LANG_DO_DOCUMENTO = Pattern.compile(
        "documentElement\\s*\\.\\s*(?:lang\\s*=(?!=)|setAttribute\\s*\\(\\s*['\"]lang['\"])");

    /** Marcação de dicionário palavra por palavra; {@code data-i18n-ignore} é proteção, não chave. */
    private static final Pattern DICIONARIO = Pattern.compile("data-i18n(?:=|-key)");

    static List<String> violacoes(String texto) {
        List<String> out = new ArrayList<>();
        Matcher lang = TROCA_LANG_DO_DOCUMENTO.matcher(texto);
        while (lang.find()) {
            out.add("troca o lang do documento: " + lang.group());
        }
        Matcher dic = DICIONARIO.matcher(texto);
        while (dic.find()) {
            out.add("resquicio de dicionario: " + dic.group());
        }
        return out;
    }

    @Test
    @DisplayName("instrumento: reprova a troca do lang do documento e aceita o lang do proprio aviso")
    void instrumentoDiscriminaNaFronteira() {
        assertEquals(1, violacoes("document.documentElement.lang = idiomaAtual;").size());
        assertEquals(1, violacoes("document.documentElement.setAttribute('lang', 'en');").size());
        assertEquals(1, violacoes("<span data-i18n=\"menu.inicio\">Inicio</span>").size());
        // Mesmo sinal ("lang", "data-i18n"), legítimos:
        assertEquals(0, violacoes("avisoFalha.setAttribute('lang', idiomaPedido);").size());
        assertEquals(0, violacoes("if (document.documentElement.lang === 'pt-BR') {}").size());
        assertEquals(0, violacoes("const c = document.documentElement.classList;").size());
        assertEquals(0, violacoes("'[data-i18n-ignore]', '.notranslate'").size());
    }

    @Test
    @DisplayName("nenhum script da tela troca o lang do documento nem usa dicionario")
    void nenhumScriptTrocaOLangNemUsaDicionario() throws IOException {
        List<String> todas = new ArrayList<>();
        int lidos = 0;
        try (Stream<Path> arquivos = Files.walk(ESTATICOS)) {
            for (Path p : arquivos.filter(Files::isRegularFile)
                    .filter(f -> {
                        String n = f.getFileName().toString();
                        return (n.endsWith(".js") && !n.endsWith(".min.js")) || n.endsWith(".html");
                    }).toList()) {
                lidos++;
                for (String v : violacoes(Files.readString(p, StandardCharsets.UTF_8))) {
                    todas.add(ESTATICOS.relativize(p) + ": " + v);
                }
            }
        }
        assertTrue(lidos > 20, "instrumento cego: so " + lidos + " arquivos .js/.html lidos em " + ESTATICOS);
        assertEquals(List.of(), todas);
    }

    @Test
    @DisplayName("o HTML declara portugues e o widget fica escondido e fora da traducao")
    void htmlDeclaraPortuguesEWidgetEscondido() throws IOException {
        String index = Files.readString(INDEX, StandardCharsets.UTF_8);
        assertTrue(index.contains("<html lang=\"pt-BR\">"), "index.html sem <html lang=\"pt-BR\">");
        Matcher widget = Pattern.compile("<div[^>]*id=\"google_translate_element\"[^>]*>").matcher(index);
        assertTrue(widget.find(), "index.html sem o div do widget (google_translate_element)");
        assertTrue(widget.group().contains("translate=\"no\""), "o div do widget precisa de translate=\"no\": "
            + widget.group());
    }

    @Test
    @DisplayName("callback definido antes do element.js, anexado com async")
    void callbackAntesDoElementJsEAsync() throws IOException {
        String js = Files.readString(I18N_JS, StandardCharsets.UTF_8);
        int callback = js.indexOf("globalThis.googleTranslateElementInit =");
        int anexo = js.indexOf("document.head.appendChild(script)");
        assertTrue(callback > 0 && anexo > callback,
            "o callback googleTranslateElementInit tem de existir ANTES do element.js ser anexado "
                + "(callback=" + callback + ", anexo=" + anexo + ")");
        assertTrue(js.contains("script.async = true"),
            "o element.js e de terceiro: sem async, um Google mudo segura a pagina");
        assertTrue(js.contains("translate_a/element.js?cb=googleTranslateElementInit"),
            "a URL do element.js tem de chamar o callback googleTranslateElementInit");
    }

    @Test
    @DisplayName("icones e codigo estao na lista protegida do tradutor")
    void ilhasTecnicasProtegidas() throws IOException {
        String js = Files.readString(I18N_JS, StandardCharsets.UTF_8);
        int ini = js.indexOf("SELETORES_PROTEGIDOS");
        int fim = js.indexOf("]);", ini);
        assertTrue(ini > 0 && fim > ini, "lista SELETORES_PROTEGIDOS nao encontrada em " + I18N_JS);
        String lista = js.substring(ini, fim);
        for (String seletor : List.of("'.material-symbols-outlined'", "'code'", "'pre'", "'kbd'", "'samp'",
                "'[translate=\"no\"]'", "'.notranslate'")) {
            assertTrue(lista.contains(seletor), "SELETORES_PROTEGIDOS sem " + seletor);
        }
    }

    @Test
    @DisplayName("o aviso de falha do tradutor existe nos idiomas das bandeiras e nao se traduz")
    void avisoDeFalhaNoIdiomaPedido() throws IOException {
        String js = Files.readString(I18N_JS, StandardCharsets.UTF_8);
        assertTrue(js.contains("'en-US': Object.freeze({") && js.contains("Google Translate did not respond"),
            "aviso de falha em ingles ausente em " + I18N_JS);
        assertTrue(js.contains("'es-ES': Object.freeze({") && js.contains("El Traductor de Google no respondi"),
            "aviso de falha em espanhol ausente em " + I18N_JS);
        assertTrue(js.contains("avisoFalha.setAttribute('translate', 'no')")
                && js.contains("avisoFalha.setAttribute('lang', idiomaPedido)"),
            "o aviso de falha precisa de translate=no e do lang do idioma pedido");
    }
}
