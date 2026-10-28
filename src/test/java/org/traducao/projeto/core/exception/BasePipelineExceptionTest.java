package org.traducao.projeto.core.exception;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.traducao.projeto.analisadorMidia.domain.AnalisadorException;
import org.traducao.projeto.analisadorMidia.domain.exceptions.AnaliseStreamException;
import org.traducao.projeto.apiDadosAnime.domain.exceptions.AnimeNaoEncontradoException;
import org.traducao.projeto.apiDadosAnime.domain.exceptions.ApiDadosAnimeException;
import org.traducao.projeto.legendasExtracao.domain.ExtratorException;
import org.traducao.projeto.legendasExtracao.domain.exceptions.FormatoLegendaInvalidoException;
import org.traducao.projeto.mapaProjeto.domain.exceptions.MapaProjetoException;
import org.traducao.projeto.raspagemCorrecao.domain.exceptions.RaspagemCorrecaoException;
import org.traducao.projeto.raspagemRevisao.domain.exceptions.RaspagemRevisaoException;
import org.traducao.projeto.remuxer.domain.MkvToolNixNaoEncontradoException;
import org.traducao.projeto.remuxer.domain.RemuxerException;
import org.traducao.projeto.qualidadeTraducao.domain.AlucinacaoDetectadaException;
import org.traducao.projeto.legenda.domain.ArquivoLegendaException;
import org.traducao.projeto.lore.domain.ContextoNaoEncontradoException;
import org.traducao.projeto.traducao.domain.exceptions.DivergenciaLinhasException;
import org.traducao.projeto.traducao.domain.exceptions.RespostaLlmVaziaException;
import org.traducao.projeto.traducao.domain.exceptions.TradutorException;
import org.traducao.projeto.traducaoCorrige.domain.exceptions.CorretorCacheException;

import java.io.IOException;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Cobre o contrato comum de {@link BasePipelineException}, herdado por toda
 * exceção de domínio do pipeline (uma por pacote). {@code TraducaoParcialException}
 * fica de fora deliberadamente: tem construtores com formato próprio (lista de
 * lotes salvos / dicionário parcial), não apenas mensagem+causa, mas herda o
 * mesmo comportamento de errorId/timestamp validado aqui pelas demais 20 classes.
 */
class BasePipelineExceptionTest {

    static Stream<BasePipelineException> todasAsSubclasses() {
        return Stream.of(
            new MapaProjetoException("falha no mapa do projeto"),
            new CorretorCacheException("falha no corretor de cache"),
            new RaspagemCorrecaoException("falha na correção via Google"),
            new RaspagemRevisaoException("falha na revisão de legendas"),
            new RemuxerException("falha no remuxer"),
            new MkvToolNixNaoEncontradoException("mkvmerge não encontrado"),
            new AnaliseStreamException("falha ao analisar stream"),
            new AnalisadorException("falha no analisador de mídia"),
            new ExtratorException("falha no extrator de legendas"),
            new FormatoLegendaInvalidoException("formato de legenda inválido"),
            new ApiDadosAnimeException("falha ao obter metadata"),
            new AnimeNaoEncontradoException("anime não encontrado"),
            new ContextoNaoEncontradoException("contexto não encontrado"),
            new TradutorException("falha no tradutor"),
            new RespostaLlmVaziaException("resposta vazia do LLM"),
            new ArquivoLegendaException("arquivo de legenda inválido"),
            new AlucinacaoDetectadaException("alucinação detectada"),
            new DivergenciaLinhasException("divergência de linhas")
        );
    }

    @ParameterizedTest
    @MethodSource("todasAsSubclasses")
    void carregaErrorIdComoUuidValido(BasePipelineException exception) {
        assertNotNull(exception.getErrorId());
        assertDoesNotThrow(() -> UUID.fromString(exception.getErrorId()),
            () -> exception.getClass().getSimpleName() + " deveria gerar um errorId UUID válido");
    }

    @ParameterizedTest
    @MethodSource("todasAsSubclasses")
    void carregaTimestampRecente(BasePipelineException exception) {
        LocalDateTime agora = LocalDateTime.now();
        assertNotNull(exception.getTimestamp());
        assertTrue(exception.getTimestamp().isAfter(agora.minus(1, ChronoUnit.MINUTES)));
        assertTrue(exception.getTimestamp().isBefore(agora.plus(1, ChronoUnit.MINUTES)));
    }

    @ParameterizedTest
    @MethodSource("todasAsSubclasses")
    void propagaAMensagem(BasePipelineException exception) {
        assertNotNull(exception.getMessage());
        assertFalse(exception.getMessage().isBlank());
    }

    @Test
    void cadaInstanciaTemErrorIdUnico() {
        RaspagemCorrecaoException primeira = new RaspagemCorrecaoException("falha");
        RaspagemCorrecaoException segunda = new RaspagemCorrecaoException("falha");

        assertNotEquals(primeira.getErrorId(), segunda.getErrorId());
    }

    @Test
    void propagaACauseQuandoFornecida() {
        IOException causaRaiz = new IOException("causa raiz");
        BasePipelineException exception = new RaspagemCorrecaoException("falha ao processar", causaRaiz);

        assertSame(causaRaiz, exception.getCause());
    }
}
