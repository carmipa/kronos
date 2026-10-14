package org.traducao.projeto.vitrine.infrastructure;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * PROPÓSITO DE NEGÓCIO: entrega à vitrine pública os dados de demonstração — retratos
 * gravados e saneados da telemetria do KRONOS — para que as telas mostrem números de verdade
 * sem que o servidor da VPS leia disco, Redis, LLM ou rede.
 *
 * <p>INVARIANTES DO DOMÍNIO:
 * <ul>
 *   <li>Só lê do classpath, em {@value #PASTA}; nunca do sistema de arquivos.</li>
 *   <li>Nome aceito só em {@code [a-z0-9-]}: nenhum texto do cliente vira caminho de recurso.</li>
 *   <li>O conteúdo é saneado ANTES de entrar no repositório (sem caminho, IP ou chave), e a
 *       guarda {@code VitrineDemonstracaoSemSegredoTest} reprova o build se não estiver.</li>
 * </ul>
 *
 * <p>COMPORTAMENTO EM CASO DE FALHA: nome inválido ou recurso ausente devolve
 * {@link Optional#empty()} (o filtro responde 404); erro de leitura de um recurso que existe
 * lança {@link UncheckedIOException}, porque é defeito do artefato, não do visitante.
 */
public final class DadosDemonstracao {

    /** Pasta do classpath com os retratos de demonstração. */
    public static final String PASTA = "vitrine/demonstracao/";

    private static final Pattern NOME_VALIDO = Pattern.compile("^[a-z0-9][a-z0-9-]{0,80}$");
    private static final Map<String, Optional<byte[]>> CACHE = new ConcurrentHashMap<>();

    private DadosDemonstracao() {
    }

    /**
     * PROPÓSITO DE NEGÓCIO: devolve o JSON de demonstração de um nome.
     *
     * <p>INVARIANTES DO DOMÍNIO: mesmo nome, mesmos bytes durante toda a execução (lidos uma vez).
     *
     * <p>COMPORTAMENTO EM CASO DE FALHA: vazio para nome inválido ou recurso inexistente.
     */
    public static Optional<byte[]> obter(String nome) {
        if (nome == null || !NOME_VALIDO.matcher(nome).matches()) {
            return Optional.empty();
        }
        return CACHE.computeIfAbsent(nome, DadosDemonstracao::ler);
    }

    private static Optional<byte[]> ler(String nome) {
        String recurso = PASTA + nome + ".json";
        ClassLoader cl = DadosDemonstracao.class.getClassLoader();
        try (InputStream in = cl.getResourceAsStream(recurso)) {
            if (in == null) {
                return Optional.empty();
            }
            return Optional.of(in.readAllBytes());
        } catch (IOException e) {
            throw new UncheckedIOException("Dado de demonstração ilegível: " + recurso, e);
        }
    }
}
