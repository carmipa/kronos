package org.traducao.projeto.remuxer.application;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.traducao.projeto.remuxer.domain.RelatorioRemux;
import org.traducao.projeto.remuxer.domain.RemuxTarefa;
import org.traducao.projeto.remuxer.domain.RemuxerException;
import org.traducao.projeto.remuxer.domain.SaidaRemuxJaExisteException;
import org.traducao.projeto.remuxer.infrastructure.adapters.MkvmergeAdapter;
import org.traducao.projeto.remuxer.infrastructure.config.RemuxerProperties;
import org.traducao.projeto.remuxer.presentation.ui.ConsoleRemuxerLogger;
import org.traducao.projeto.telemetria.OperacaoTelemetria;
import org.traducao.projeto.telemetria.TelemetriaService;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class RemuxarLoteUseCaseTest {
    /**
     * PROPÓSITO DE NEGÓCIO: garante que ASS não vazio porém estruturalmente
     * inválido seja bloqueado antes do adaptador externo.
     * INVARIANTES DO DOMÍNIO: nenhum MKV final é criado.
     * COMPORTAMENTO EM CASO DE FALHA: relatório registra legenda inválida.
     */
    @Test
    void bloqueiaLegendaComConteudoInvalido(@TempDir Path tempDir) throws IOException {
        Cenario cenario = criarCenario(tempDir, "isto não é ASS");
        MkvmergeAdapter adapter = adapterQueFalhaSeExecutado();

        RelatorioRemux relatorio = criarUseCase(adapter).executar(cenario.videos(), cenario.legendas(), 0);

        assertEquals(1, relatorio.getErrosLegendaInvalida());
        assertEquals("CONCLUIDO_COM_FALHAS", relatorio.getStatusFinal());
        assertEquals(0, relatorio.getMkvProcessadosSucesso());
    }

    /**
     * PROPÓSITO DE NEGÓCIO: legenda PT cujo diálogo está em fonte vietnamita antiga não vira MKV
     * publicado — o Gundam ZZ saiu assim em 09/10/2026 ("Nóo, ộ a atraỗóo!" na tela). Ordem de
     * Paulo (14/10): reprova e pede a 1.3 Troca de Tipo de Legenda.
     * INVARIANTES DO DOMÍNIO: o mkvmerge não é chamado; o lote termina com pendência, não falha.
     * COMPORTAMENTO EM CASO DE FALHA: chamada ao adaptador ou contador zerado reprovam.
     */
    @Test
    void legendaComFonteVietnamitaReprovaEPedeATrocaDeFonte(@TempDir Path tempDir) throws IOException {
        Cenario cenario = criarCenario(tempDir, legendaAssComFonteDoDialogo("VnBook-Antiqua"));

        RelatorioRemux relatorio = criarUseCase(adapterQueFalhaSeExecutado())
            .executar(cenario.videos(), cenario.legendas(), 0);

        assertEquals(1, relatorio.getLegendasComFonteVietnamita());
        assertEquals(0, relatorio.getMkvProcessadosSucesso());
        assertEquals("CONCLUIDO_COM_PENDENCIAS", relatorio.getStatusFinal());
    }

    /**
     * PROPÓSITO DE NEGÓCIO: CASO-CONTROLE (A1) — a MESMA legenda com o diálogo em Arial segue para o
     * remux e é publicada.
     * INVARIANTES DO DOMÍNIO: a reprovação depende só da fonte.
     * COMPORTAMENTO EM CASO DE FALHA: status diferente de CONCLUIDO reprova.
     */
    @Test
    void mesmaLegendaEmArialSegueParaORemux(@TempDir Path tempDir) throws IOException {
        Cenario cenario = criarCenario(tempDir, legendaAssComFonteDoDialogo("Arial"));
        MkvmergeAdapter adapter = new AdapterFake() {
            /** PROPÓSITO DE NEGÓCIO: simula publicação. INVARIANTES DO DOMÍNIO: escreve o destino. COMPORTAMENTO EM CASO DE FALHA: erro de domínio. */
            @Override
            public void executarRemux(RemuxTarefa tarefa, long sync) {
                try {
                    Files.writeString(tarefa.caminhoSaida(), "MKV_VALIDADO");
                } catch (IOException e) {
                    throw new RemuxerException("falha fake", e);
                }
            }
        };

        RelatorioRemux relatorio = criarUseCase(adapter).executar(cenario.videos(), cenario.legendas(), 0);

        assertEquals(0, relatorio.getLegendasComFonteVietnamita());
        assertEquals("CONCLUIDO", relatorio.getStatusFinal());
    }

    /**
     * PROPÓSITO DE NEGÓCIO: CASO-CONTROLE (A1) da leitura — legenda PT externa salva em ANSI
     * (cp1252), com acento, continua indo ao remux; a verificação de fonte não pode transformá-la em
     * FALHA I/O.
     * INVARIANTES DO DOMÍNIO: leitura tolerante, como a do validador de entrada.
     * COMPORTAMENTO EM CASO DE FALHA: status diferente de CONCLUIDO reprova.
     */
    @Test
    void legendaEmAnsiSemFonteVietnamitaContinuaIndoAoRemux(@TempDir Path tempDir) throws IOException {
        Cenario cenario = criarCenario(tempDir, "");
        Files.write(cenario.legendas().resolve("Anime - S01E01_PT-BR.ass"),
            legendaAssComFonteDoDialogo("Arial").getBytes(java.nio.charset.Charset.forName("windows-1252")));
        MkvmergeAdapter adapter = new AdapterFake() {
            /** PROPÓSITO DE NEGÓCIO: simula publicação. INVARIANTES DO DOMÍNIO: escreve o destino. COMPORTAMENTO EM CASO DE FALHA: erro de domínio. */
            @Override
            public void executarRemux(RemuxTarefa tarefa, long sync) {
                try {
                    Files.writeString(tarefa.caminhoSaida(), "MKV_VALIDADO");
                } catch (IOException e) {
                    throw new RemuxerException("falha fake", e);
                }
            }
        };

        RelatorioRemux relatorio = criarUseCase(adapter).executar(cenario.videos(), cenario.legendas(), 0);

        assertEquals("CONCLUIDO", relatorio.getStatusFinal());
        assertEquals(0, relatorio.getErrosPermissaoIo());
    }

    /**
     * PROPÓSITO DE NEGÓCIO: ASS com estilo declarado e uma fala que o usa.
     * INVARIANTES DO DOMÍNIO: passa na validação de entrada do remux.
     * COMPORTAMENTO EM CASO DE FALHA: constante sempre válida.
     */
    private String legendaAssComFonteDoDialogo(String fonte) {
        return "[Script Info]\n\n[V4+ Styles]\nFormat: Name, Fontname, Fontsize\nStyle: Dialogue," + fonte + ",75\n\n"
            + "[Events]\nFormat: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text\n"
            + "Dialogue: 0,0:00:00.00,0:00:01.00,Dialogue,,0,0,0,,Irmão, é a atração!\n";
    }

    /**
     * PROPÓSITO DE NEGÓCIO: classifica destino preexistente como pendência segura,
     * não como sucesso nem falha destrutiva.
     * INVARIANTES DO DOMÍNIO: conteúdo existente permanece intacto.
     * COMPORTAMENTO EM CASO DE FALHA: contador de preservados deve ser um.
     */
    @Test
    void destinoExistenteViraPendenciaEPermaneceIntacto(@TempDir Path tempDir) throws IOException {
        Cenario cenario = criarCenario(tempDir, legendaAssValida());
        Path pastaSaida = Files.createDirectories(cenario.videos().resolve("mkv_final_ptbr"));
        Path destino = pastaSaida.resolve("Anime - S01E01_PTBR.mkv");
        Files.writeString(destino, "FINAL_VALIDO");
        MkvmergeAdapter adapter = new AdapterFake() {
            /** PROPÓSITO DE NEGÓCIO: simula colisão de destino. INVARIANTES DO DOMÍNIO: nunca escreve. COMPORTAMENTO EM CASO DE FALHA: lança exceção específica. */
            @Override
            public void executarRemux(RemuxTarefa tarefa, long sync) {
                throw new SaidaRemuxJaExisteException("preservado: " + tarefa.caminhoSaida());
            }
        };

        RelatorioRemux relatorio = criarUseCase(adapter).executar(cenario.videos(), cenario.legendas(), 0);

        assertEquals("CONCLUIDO_COM_PENDENCIAS", relatorio.getStatusFinal());
        assertEquals(1, relatorio.getSaidasJaExistentes());
        assertEquals("FINAL_VALIDO", Files.readString(destino));
    }

    /**
     * PROPÓSITO DE NEGÓCIO: confirma que sucesso só é contado após o adaptador
     * publicar o destino e que o status termina concluído.
     * INVARIANTES DO DOMÍNIO: tamanho real do destino entra no relatório.
     * COMPORTAMENTO EM CASO DE FALHA: ausência do arquivo após mock falha o teste.
     */
    @Test
    void registraSucessoSomenteComArquivoPublicado(@TempDir Path tempDir) throws IOException {
        Cenario cenario = criarCenario(tempDir, legendaAssValida());
        MkvmergeAdapter adapter = new AdapterFake() {
            /** PROPÓSITO DE NEGÓCIO: simula publicação validada. INVARIANTES DO DOMÍNIO: escreve no destino planejado. COMPORTAMENTO EM CASO DE FALHA: converte I/O em erro de domínio. */
            @Override
            public void executarRemux(RemuxTarefa tarefa, long sync) {
                try {
                    Files.writeString(tarefa.caminhoSaida(), "MKV_VALIDADO");
                } catch (IOException e) {
                    throw new RemuxerException("falha fake", e);
                }
            }
        };

        RelatorioRemux relatorio = criarUseCase(adapter).executar(cenario.videos(), cenario.legendas(), 0);

        assertEquals("CONCLUIDO", relatorio.getStatusFinal());
        assertEquals(1, relatorio.getMkvProcessadosSucesso());
        assertTrue(relatorio.getBytesMkvGeradosTotal() > 0);
    }

    /**
     * PROPÓSITO DE NEGÓCIO: comprova que interrupção durante uma tarefa encerra o
     * lote como CANCELADO e não inicia arquivos posteriores.
     * INVARIANTES DO DOMÍNIO: flag de interrupção é limpa ao final do teste.
     * COMPORTAMENTO EM CASO DE FALHA: status diferente de CANCELADO falha.
     */
    @Test
    void interrupcaoEncerraLoteComoCancelado(@TempDir Path tempDir) throws IOException {
        Cenario cenario = criarCenario(tempDir, legendaAssValida());
        MkvmergeAdapter adapter = new AdapterFake() {
            /** PROPÓSITO DE NEGÓCIO: simula cancelamento. INVARIANTES DO DOMÍNIO: restaura a flag de interrupção. COMPORTAMENTO EM CASO DE FALHA: lança erro de domínio. */
            @Override
            public void executarRemux(RemuxTarefa tarefa, long sync) {
                Thread.currentThread().interrupt();
                throw new RemuxerException("cancelado");
            }
        };
        try {
            RelatorioRemux relatorio = criarUseCase(adapter).executar(cenario.videos(), cenario.legendas(), 0);
            assertEquals("CANCELADO", relatorio.getStatusFinal());
            assertEquals(0, relatorio.getMkvProcessadosSucesso());
        } finally {
            Thread.interrupted();
        }
    }

    /**
     * PROPÓSITO DE NEGÓCIO: monta o caso de uso com telemetria isolada do dataset
     * real e logger local.
     * INVARIANTES DO DOMÍNIO: mapeador usado é o de produção.
     * COMPORTAMENTO EM CASO DE FALHA: dependências inválidas falham na construção.
     */
    private RemuxarLoteUseCase criarUseCase(MkvmergeAdapter adapter) {
        TelemetriaService telemetria = new TelemetriaService() {
            /**
             * PROPÓSITO DE NEGÓCIO: evita persistência do teste no dataset real.
             * INVARIANTES DO DOMÍNIO: nenhuma escrita externa.
             * COMPORTAMENTO EM CASO DE FALHA: evento é descartado deliberadamente.
             */
            @Override
            public synchronized void registrarOperacao(OperacaoTelemetria operacao) {
                // Isolamento do teste.
            }
        };
        return new RemuxarLoteUseCase(adapter, new MapeadorMidiaService(), new ConsoleRemuxerLogger(), telemetria);
    }

    /**
     * PROPÓSITO DE NEGÓCIO: cria vídeo e legenda pareáveis em pastas temporárias.
     * INVARIANTES DO DOMÍNIO: conteúdo da legenda é definido pelo teste.
     * COMPORTAMENTO EM CASO DE FALHA: propaga IOException de fixture.
     */
    private Cenario criarCenario(Path raiz, String conteudoLegenda) throws IOException {
        Path videos = Files.createDirectory(raiz.resolve("videos"));
        Path legendas = Files.createDirectory(raiz.resolve("legendas"));
        Files.writeString(videos.resolve("Anime - S01E01.mkv"), "VIDEO");
        Files.writeString(legendas.resolve("Anime - S01E01_PT-BR.ass"), conteudoLegenda);
        return new Cenario(videos, legendas);
    }

    /**
     * PROPÓSITO DE NEGÓCIO: fornece ASS mínimo reconhecido pela validação de entrada.
     * INVARIANTES DO DOMÍNIO: possui Script Info, Events e Dialogue.
     * COMPORTAMENTO EM CASO DE FALHA: constante sempre é válida.
     */
    private String legendaAssValida() {
        return "[Script Info]\n[Events]\nDialogue: 0,0:00:00.00,0:00:01.00,Default,,0,0,0,,Teste";
    }

    /**
     * PROPÓSITO DE NEGÓCIO: detecta chamada indevida do adaptador em teste de
     * validação de entrada.
     * INVARIANTES DO DOMÍNIO: infraestrutura fake sempre responde.
     * COMPORTAMENTO EM CASO DE FALHA: execução lança AssertionError.
     */
    private MkvmergeAdapter adapterQueFalhaSeExecutado() {
        return new AdapterFake() {
            /** PROPÓSITO DE NEGÓCIO: acusa execução proibida. INVARIANTES DO DOMÍNIO: nunca produz arquivo. COMPORTAMENTO EM CASO DE FALHA: lança AssertionError. */
            @Override
            public void executarRemux(RemuxTarefa tarefa, long sync) {
                throw new AssertionError("adaptador não deveria executar");
            }
        };
    }

    /**
     * PROPÓSITO DE NEGÓCIO: seam de teste que elimina dependência do executável
     * real e permite especializar o comportamento por cenário.
     * INVARIANTES DO DOMÍNIO: validação de infraestrutura é sempre aprovada.
     * COMPORTAMENTO EM CASO DE FALHA: método de remux padrão denuncia ausência de override.
     */
    private static class AdapterFake extends MkvmergeAdapter {
        /** PROPÓSITO DE NEGÓCIO: cria adaptador isolado. INVARIANTES DO DOMÍNIO: não executa processo real. COMPORTAMENTO EM CASO DE FALHA: construção padrão do fake. */
        AdapterFake() { super(new RemuxerProperties("mkvmerge")); }
        /** PROPÓSITO DE NEGÓCIO: aprova infraestrutura no teste. INVARIANTES DO DOMÍNIO: não chama binário. COMPORTAMENTO EM CASO DE FALHA: não lança. */
        @Override public void validarInfraestrutura() { }
        /** PROPÓSITO DE NEGÓCIO: exige especialização do cenário. INVARIANTES DO DOMÍNIO: fake base não publica. COMPORTAMENTO EM CASO DE FALHA: lança AssertionError. */
        @Override public void executarRemux(RemuxTarefa tarefa, long sync) {
            throw new AssertionError("configure o comportamento do fake");
        }
    }

    /**
     * PROPÓSITO DE NEGÓCIO: agrupa pastas temporárias do cenário.
     * INVARIANTES DO DOMÍNIO: ambas existem antes do uso.
     * COMPORTAMENTO EM CASO DE FALHA: construção é local ao teste.
     */
    private record Cenario(Path videos, Path legendas) {}
}
