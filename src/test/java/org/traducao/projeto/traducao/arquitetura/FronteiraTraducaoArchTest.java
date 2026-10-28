package org.traducao.projeto.traducao.arquitetura;

import com.tngtech.archunit.core.domain.Dependency;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * PROPÓSITO DE NEGÓCIO: congela a fronteira funcional da fatia vertical Tradução
 * Local ({@code org.traducao.projeto.traducao}). É a Camada A (estática, por
 * bytecode) do harness de fitness da FASE D: prova, a cada build, que a Tradução
 * Local só depende de outras fatias por meio de uma allowlist **estrita por
 * aresta exata** (FQN de origem → FQN de destino), que encolhe subfase a subfase
 * até restar somente o débito dos três controllers bloqueados para a C2. Analisa
 * dependências no bytecode, alcançando o que o import textual não mostra
 * (usos totalmente qualificados no corpo, campos, construtores, herança, genéricos).
 *
 * <h2>Invariantes do domínio</h2>
 * <ul>
 *   <li><b>Baseline dupla (histórico D0)</b>: a auditoria por import textual
 *       encontrou <b>15</b> arestas funcionais; o bytecode revelava <b>17</b> — as
 *       2 extras eram usos por FQN no corpo ({@code TraducaoController → LlmTelemetria}
 *       e {@code TelemetriaController → TelemetriaDatasetService}).</li>
 *   <li><b>Após D-Ext</b>: eliminadas as 2 arestas {@code RestClientConfig →
 *       ExtratorVideoPort/ExtratorStrategy} (producers movidos para
 *       {@code legendasExtracao.ExtracaoBeansConfig}).</li>
 *   <li><b>Após D-Lore</b>: eliminada a aresta {@code LlmClientAdapter →
 *       PromptRevisaoLore} (Revisão de Lore com stack LLM própria).</li>
 *   <li><b>Após D-Tel-4</b>: eliminadas as 5 arestas vivas de bytecode do grupo
 *       D-Tel (ProcessarArquivoUseCase/ProcessarEpisodioUseCase/TraducaoController →
 *       telemetria), pois esses consumidores passaram a usar a telemetria própria
 *       ({@code TelemetriaTraducaoPort}); o bytecode caiu para <b>9</b> arestas
 *       funcionais — somente os três controllers bloqueados para a C2.</li>
 *   <li><b>Após a FASE C2</b>: os três controllers (CorrecaoCacheController,
 *       RevisaoLegendasController, TelemetriaController) foram movidos para suas
 *       fatias proprietárias e o kernel técnico de apresentação (PipelineWebSupport,
 *       OperacaoRequest, RespostaPadrao, AnsiCores, LogStreamService) foi extraído
 *       para {@code core.presentation}. A Tradução Local passa a ter <b>ZERO</b>
 *       arestas funcionais de saída; a exceção ALLOW-TELEMETRIA-C2 foi eliminada.</li>
 *   <li>O conjunto real de arestas funcionais de saída da Tradução Local deve ser
 *       <b>exatamente</b> {@link #ARESTAS_FUNCIONAIS_ESPERADAS} (VAZIO após a C2).</li>
 *   <li><b>Regra dedicada revisaoLore</b>: nenhuma classe de {@code ..revisaoLore..}
 *       depende de {@code LlmPort}, {@code StatusLlm}, {@code LlmProperties},
 *       {@code JsonHttpClient}, {@code RecordsLlm}, {@code LlmClientAdapter}
 *       ou {@code GerenciadorContexto}.</li>
 *   <li><b>Regra blindada telemetria</b>: nenhuma classe de {@code ..traducao..}
 *       depende de {@code ..telemetria..} (ZERO — exceção C2 eliminada).</li>
 *   <li><b>Independência do core</b>: nenhuma classe de {@code ..core..} depende de
 *       qualquer fatia funcional; o core só pode usar JDK, bibliotecas técnicas e o
 *       próprio {@code core}. Nenhuma allowlist {@code core → fatia} é permitida.</li>
 *   <li><b>Após D-Config</b>: a fronteira {@code config ⇄ traducao} é <b>zero nos
 *       dois sentidos</b>. As exceções nominais ALLOW-CONFIG-CLI
 *       ({@code TradutorCLI → config.ExecucaoCli}) e ALLOW-STARTUP-CLI
 *       ({@code ModoExecucaoStartup → TradutorCLI}) foram eliminadas juntas: a
 *       {@code TradutorCLI} não implementa mais {@code ExecucaoCli} e o modo TRADUZIR
 *       ganhou bootstrap próprio ({@code traducao.presentation.bootstrap.TraducaoStartup}),
 *       de modo que {@code config} não conhece nenhuma classe de {@code traducao}.</li>
 *   <li>{@code core} é congelado <b>por tipo</b>: só os doze tipos de
 *       {@link #CORE_TIPOS_CONGELADOS} podem ser usados (5 técnicos + 5 do kernel Web
 *       extraído na C2 + 1 da UI de console migrada na E2 + 1 cliente HTTP da E4a);
 *       nenhum décimo-terceiro tipo de core entra.</li>
 *   <li>Origem e destino são normalizados à respectiva classe de topo (um tipo
 *       aninhado pertence à mesma classe-alvo já catalogada). A granularidade
 *       permanece de <b>classe</b> exata — nunca de fatia.</li>
 *   <li>Somente classes de produção são analisadas ({@link ImportOption.Predefined#DO_NOT_INCLUDE_TESTS}).</li>
 * </ul>
 *
 * <h2>Comportamento em caso de falha</h2>
 * Qualquer divergência (aresta inesperada, aresta esperada ausente, tipo de core
 * fora dos cinco, violação da regra reversa) reprova o teste listando exatamente
 * o desvio. Se o importador do ArchUnit não conseguir ler o bytecode Java 25
 * (class file major version 69), a importação lança e o teste erra — sinalizando
 * a incompatibilidade para decisão de Paulo, sem contorno.
 */
class FronteiraTraducaoArchTest {

    private static final String RAIZ = "org.traducao.projeto";
    private static final String PREFIXO = RAIZ + ".";
    private static final String PKG_TRADUCAO = RAIZ + ".traducao";
    private static final String PKG_CONFIG = RAIZ + ".config";
    private static final String FATIA_TRADUCAO = "traducao";
    private static final String FATIA_CORE = "core";
    private static final String FATIA_CONFIG = "config";
    private static final String FATIA_LEGENDA = "legenda";
    private static final String FATIA_CACHETRADUCAO = "cachetraducao";
    private static final String FATIA_CONTEXTO = "lore";
    private static final String FATIA_QUALIDADETRADUCAO = "qualidadeTraducao";
    private static final String FATIA_LLM = "llm";

    // Origens (produção, em traducao) ainda referenciadas pelos testes.
    private static final String PROCESSAR_ARQUIVO = RAIZ + ".traducao.application.ProcessarArquivoUseCase";
    private static final String LLM_ADAPTER = RAIZ + ".traducao.infrastructure.adapters.LlmClientAdapter";

    // FASE C2 CONCLUÍDA: os três controllers bloqueados (CorrecaoCacheController,
    // RevisaoLegendasController, TelemetriaController) saíram de traducao para suas
    // fatias proprietárias (traducaoCorrige / raspagemRevisao / telemetria .presentation.web).
    // Com isso, as 9 arestas funcionais de saída foram ELIMINADAS e a allowlist ficou VAZIA.

    // Pacote da fatia Revisão de Lore e os tipos que a stack LLM própria dela NÃO pode
    // importar (D-Lore). Após a E8d, o contrato do LLM e seus records vivem no peer
    // {@code llm.domain}; a proibição acompanha os novos FQNs para continuar impedindo que
    // revisaoLore volte a acoplar-se à stack LLM compartilhada (porta, status, properties,
    // DTOs, adapter concreto e o gerenciador de contexto).
    private static final String PKG_REVISAO_LORE = RAIZ + ".revisaoLore";
    // FASE E avancada em 2026-09-17 (ordem de Paulo): o GerenciadorContexto (catalogo de contextos,
    // peer `lore`) DEIXOU de ser proibido para a revisaoLore. A guarda obra×contexto da 3.2 precisa
    // de idsQueReconhecem(obra) para provar DIVERGENTE com seguranca (sem ele, bloquearia
    // INDETERMINADO legitimo). A traducao ja consome o mesmo catalogo. Os tipos LLM da Traducao
    // Local continuam proibidos — a revisaoLore tem a propria stack LLM.
    private static final Set<String> REVISAO_LORE_PROIBIDOS = Set.of(
        RAIZ + ".llm.domain.LlmPort",
        RAIZ + ".llm.domain.StatusLlm",
        RAIZ + ".traducao.infrastructure.config.LlmProperties",
        RAIZ + ".traducao.infrastructure.dtos.RecordsLlm",
        LLM_ADAPTER
    );

    // Pacote do módulo de telemetria. Após a FASE C2 (TelemetriaController movido para
    // telemetria.presentation.web), NENHUMA classe de traducao depende de telemetria —
    // a exceção nominal ALLOW-TELEMETRIA-C2 foi eliminada.
    private static final String PKG_TELEMETRIA = RAIZ + ".telemetria";

    // D-Config CONCLUÍDA: as exceções nominais ALLOW-CONFIG-CLI (traducao → config)
    // e ALLOW-STARTUP-CLI (config → traducao) foram eliminadas juntas. A fronteira
    // config ⇄ traducao é agora ZERO nos dois sentidos (ver testes dedicados abaixo).

    /**
     * Baseline exata após a FASE C2: a Tradução Local possui <b>ZERO</b> arestas
     * funcionais de saída para outras fatias. Os três controllers bloqueados foram
     * movidos para suas fatias proprietárias; não há mais nenhuma dependência de
     * saída de {@code traducao..} para código de outra fatia funcional.
     */
    private static final Set<String> ARESTAS_FUNCIONAIS_ESPERADAS = Set.of();

    // Fatia (primeiro segmento após org.traducao.projeto.) do pacote core.
    private static final String FATIA_CORE_NOME = "core";

    /**
     * Superfície técnica de core congelada POR TIPO. Após a FASE C2, o kernel técnico
     * de apresentação foi extraído para {@code core.presentation}: os cinco tipos
     * originais somam-se às cinco classes-kernel Web movidas (PipelineWebSupport,
     * OperacaoRequest, RespostaPadrao, AnsiCores, LogStreamService), ao ConsoleEntrada
     * migrado na E2 (core.presentation.ui) e ao JsonHttpClient neutralizado na E4a
     * (core.infrastructure.http) — exatamente DOZE tipos congelados; nenhum
     * décimo-terceiro tipo de core pode entrar.
     */
    private static final Set<String> CORE_TIPOS_CONGELADOS = Set.of(
        RAIZ + ".core.util.ArquivoAtomicoUtil",
        RAIZ + ".core.io.DiretorioBaseKronos",
        RAIZ + ".core.execucao.FilaExecucaoPipeline",
        RAIZ + ".core.exception.BasePipelineException",
        RAIZ + ".core.presentation.web.PipelineWebSupport",
        RAIZ + ".core.presentation.web.OperacaoRequest",
        RAIZ + ".core.presentation.web.RespostaPadrao",
        RAIZ + ".core.presentation.web.LogStreamService",
        RAIZ + ".core.presentation.ui.ConsoleEntrada",
        RAIZ + ".core.infrastructure.http.JsonHttpClient",
        // DECIMO TERCEIRO, homologado em 2026-08-04: mecanica de casamento de termo em legenda
        // ASS. Nao e regra de negocio de fatia nenhuma — e o formato. Vivia copiada em 12
        // arquivos de 7 fatias, e o problema NAO foi divergirem: foi a copia ser INCOMPLETA. A
        // fronteira que aceita a quebra \N estava nos 12; o separador interno, que aceita a
        // quebra DENTRO do termo composto, estava em UM. O resultado foi corrupcao de traducao
        // correta — "Nahel Argama" revertido para "Argama" num caso, "Nahel\NNahel Argama" na
        // tela no outro. Medido: 435 campos do acervo com nome composto partido, 230 formas.
        // Congelado por CatracaFronteiraQuebraAssTest, que exige definicao em UM unico arquivo.
        RAIZ + ".core.texto.FronteiraTermoAss",
        // DECIMO QUARTO, homologado em 2026-08-08. Separa a MOLDURA de tags do texto que o
        // espectador le, para que ao LLM va a frase pura — sem marcador [[TAGn]] para ele perder.
        //
        // Nasceu em traducao.domain e MUDOU-SE para core por imposicao da FronteiraInboundArchTest:
        // o corretor de karaoke precisou do mesmo mecanismo, e fatia nao importa de fatia. Core e
        // consumo livre por contrato, e o precedente e o vizinho acima — mecanica de FORMATO nao
        // pertence a fatia nenhuma.
        //
        // O prejuizo que justifica: no 08th MS Team, 1.258 de 1.636 linhas de karaoke (77%) foram
        // descartadas por falta de UM marcador, com a traducao ja pronta e correta na mao
        // ("Voce ve o sonho brilhando dentro da tempestade"). Na fatia traducao o mesmo mecanismo
        // esta atras da flag tradutor.texto-puro-ao-llm; no karaoke e incondicional, porque la
        // TODA linha tem tag e o mascarador nao tem chance.
        RAIZ + ".core.texto.TextoSemTags",
        // DECIMO QUINTO, homologado em 2026-08-12. Recusa na PORTA o trabalho que nao tem como
        // dar certo — pasta inexistente, texto que nao forma caminho, arquivo onde se espera
        // diretorio. E o unico jeito de a borda assincrona nao responder "iniciada" para o
        // impossivel: depois do submeterJobComRelatorio a resposta HTTP ja saiu, e o unico canal
        // que resta e o log, que ninguem le no instante do clique.
        //
        // Mesmo precedente dos dois vizinhos acima: e mecanica de BORDA, nao regra de negocio de
        // fatia nenhuma, e ja e consumida por 7 controllers de fatias distintas — traze-la para a
        // Traducao Local nao cria aresta entre fatias, so usa a porta compartilhada.
        //
        // O prejuizo que justifica, medido nesta rota em 2026-08-12: um POST com caminho do host
        // contra o KRONOS de conteiner — que enxerga o acervo em /acervo — recebeu HTTP 200
        // "Traducao via LLM iniciada" e falhou depois, so no log. A varredura de 11/08 fechou 7
        // rotas e nao alcancou esta porque a catraca procurava "filaExecucao.submeter", e aqui o
        // disparo e submeterJobComRelatorio: guarda que nao reconhece a forma aprova por cegueira.
        RAIZ + ".core.io.GuardaCaminhoEntrada",
        // DECIMO SEXTO ao VIGESIMO, homologados em 2026-08-13: o subpacote dicionarioOrtografia.
        //
        // Mesmo precedente de FronteiraTermoAss e TextoSemTags — ortografia e mecanica de IDIOMA,
        // nao regra de negocio de fatia nenhuma. A traducao usa, a revisao vai usar, o corretor de
        // karaoke usaria; nenhuma delas e dona do portugues.
        //
        // O prejuizo que justifica, MEDIDO: o NormalizadorAcentosComuns corrige por LISTA, montada
        // com o que foi medido em cada obra. Em 13/08, sobre o Zeta recem-traduzido pela aya, essa
        // lista resolvia ZERO das 119 falas com acento faltando — o vocabulario de uma obra nova
        // nao e o da anterior. A regra de terminacao -cao fechou 105; as ~200 formas restantes
        // (colonia 53, sera 32, area 14, opiniao 10, assembleia — grafia pre-acordo) so um
        // dicionario completo alcanca.
        //
        // Falha fechada com TRES estados: sem hunspell instalado, nada e corrigido E o corretor se
        // declara indisponivel. "Nao verifiquei" nunca vira "esta limpo".
        RAIZ + ".core.texto.dicionarioOrtografia.CorretorOrtograficoLegenda",
        // 28/10/2026: DuracaoUtil, AnsiCores e cinco tipos do dicionarioOrtografia sairam desta lista
        // -- homologados e sem uso no bytecode da Traducao Local (so o CorretorOrtograficoLegenda e
        // usado). A regra agora confere IGUALDADE: tipo sem uso reprova, e a lista so desce.
        // VIGESIMO PRIMEIRO, homologado em 2026-08-18: limpeza do token de template do LLM.
        //
        // Mesmo precedente de FronteiraTermoAss e do dicionarioOrtografia — e MECANICA de
        // TRANSPORTE, nao regra de negocio de fatia nenhuma. Nenhuma fatia e dona da pergunta
        // "isto e token de controle do servidor?"; todas sofrem se a resposta chegar suja.
        //
        // O prejuizo que justifica, MEDIDO DUAS VEZES em fatias diferentes:
        //   11/08  traducao ...... 115 das 116 falas perdidas numa execucao (99%) eram
        //                          <|END_OF_TURN_TOKEN|> colado numa traducao CORRETA.
        //   18/08  revisaoLore ... 4.903 propostas recusadas, 100% com o token, e em 2.616
        //                          (53,4%) o token era a UNICA diferenca — o modelo nao mudara
        //                          nada. Sete obras fecharam com "Falas corrigidas: 0".
        //
        // A segunda so aconteceu porque a primeira correcao ficou DENTRO da fatia traducao, e o
        // Javadoc de la afirmava cobrir "os DOIS pontos que leem message.content()" — sem saber
        // que a Revisao de Lore tem o proprio cliente. Mecanica presa numa fatia repete o
        // prejuizo na fatia vizinha; e por isso que sobe para o core.
        RAIZ + ".core.texto.TokenDeControleLlm"
    );

    /**
     * Superfície do módulo peer {@code legenda} congelada POR TIPO. A Tradução Local só
     * pode depender nominalmente destes tipos; qualquer tipo extra reprova até autorização
     * explícita (sem flexibilização genérica do pacote legenda).
     * <ul>
     *   <li>E3c: {@code PoliticaEstiloMusical} (política de estilo musical).</li>
     *   <li>E5a: {@code DocumentoLegenda} e {@code EventoLegenda} (modelo puro de legenda,
     *       movido de {@code traducao.domain.legenda}).</li>
     *   <li>E5b: {@code ExcecaoLegenda} (raiz das falhas do módulo) e
     *       {@code ArquivoLegendaException} (falha de I/O de legenda), movidos de
     *       {@code traducao.domain.exceptions}. {@code EntradaJaTraduzidaException}
     *       permanece em {@code traducao} e NÃO entra neste conjunto.</li>
     *   <li>E5c: {@code LeitorLegendaAss/Srt} e {@code EscritorLegendaAss/Srt} (I/O de
     *       legenda), movidos de {@code traducao.infrastructure.legenda} para
     *       {@code legenda.infrastructure}. {@code MascaradorTags} saiu de traducao na E8b
     *       para o peer {@code qualidadeTraducao} (ver {@link #QUALIDADE_TRADUCAO_TIPOS_CONGELADOS}).</li>
     *   <li>E8a: {@code DetectorEfeitoKaraokeService} (regra única música/karaokê),
     *       movido de {@code traducao.application} para {@code legenda.application};
     *       consumido pela Tradução Local via {@code ProcessarArquivoUseCase}.</li>
     *   <li>Proteção de romaji (Plano-Mestre 2026-07-25, Fase 2):
     *       {@code ProtecaoCamadasMusicaisService} — pré-passe que reconhece, uma vez por arquivo,
     *       qual linha é a camada ORIGINAL do karaokê. O record de resultado é ANINHADO no serviço
     *       e normaliza para a classe de topo, por isso não consome uma segunda entrada aqui.
     *       Entrou com a fatia consumindo pela superfície pública do peer, sem tocar em
     *       {@code legenda.infrastructure}.</li>
     *   <li>Carimbo de proveniência (2026-08-07): {@code CarimboCabecalhoLegenda} — escreve no
     *       {@code [Script Info]} do próprio arquivo quantas falas existiam na origem, quantas
     *       eram traduzíveis, quantas foram preservadas por regra do pipeline e qual lore
     *       decidiu.
     *       <p><b>Por que entra:</b> carimbar é mecânica de CABEÇALHO ASS — inserir comentário
     *       depois de {@code [Script Info]}, preservar {@code \r\n}, substituir o carimbo
     *       anterior em vez de empilhar. O dono do formato é o peer {@code legenda}. A mecânica
     *       já existia PRIVADA dentro de {@code AchatadorEstilosDecorativosService}; a
     *       alternativa a homologar este tipo seria COPIÁ-LA para a fatia {@code traducao},
     *       criando a segunda implementação da mesma regra — a classe de defeito que
     *       {@code CatracaRegraDuplicadaEntreFatiasTest} existe para contar.
     *       <p><b>O prejuízo que originou:</b> auditando o acervo em 07/08/2026, cinco
     *       conclusões erradas saíram de recalcular por fora o que o pipeline decidiu por
     *       dentro — 18.431 falas dadas como resíduo de tradução (eram letra de música cujo
     *       estilo o achatamento apagou), 2.898 dadas como perdidas no Unicorn (eram o karaokê
     *       {@code OPL2} descartado de propósito) e 364 "defeitos" que eram estilos que o
     *       {@code application.yml} manda ignorar. Em nenhum o dado estava perdido; perdida
     *       estava a DESCOBERTA, porque o arquivo não declarava nada.
     *       <p>É {@code domain} puro, sem I/O e sem estado; a fatia consome pela superfície
     *       pública do peer, sem tocar em {@code legenda.infrastructure}.</li>
     * </ul>
     */
    private static final Set<String> LEGENDA_TIPOS_CONGELADOS = Set.of(
        RAIZ + ".legenda.domain.PoliticaEstiloMusical",
        RAIZ + ".legenda.domain.CarimboCabecalhoLegenda",
        RAIZ + ".legenda.domain.DocumentoLegenda",
        RAIZ + ".legenda.domain.EventoLegenda",
        RAIZ + ".legenda.domain.ExcecaoLegenda",
        RAIZ + ".legenda.domain.ArquivoLegendaException",
        RAIZ + ".legenda.infrastructure.LeitorLegendaAss",
        RAIZ + ".legenda.infrastructure.LeitorLegendaSrt",
        RAIZ + ".legenda.infrastructure.EscritorLegendaAss",
        RAIZ + ".legenda.infrastructure.EscritorLegendaSrt",
        RAIZ + ".legenda.application.DetectorEfeitoKaraokeService",
        RAIZ + ".legenda.application.ProtecaoCamadasMusicaisService"
    );

    /**
     * Superfície do peer {@code cachetraducao} (E6) congelada POR TIPO, SEPARADA de
     * {@link #LEGENDA_TIPOS_CONGELADOS}. A Tradução Local só pode depender nominalmente
     * destes tipos do peer de cache; qualquer tipo extra reprova até autorização explícita
     * (sem liberação genérica do pacote {@code cachetraducao}). Contém apenas os tipos que
     * a fatia {@code traducao} consome de fato (via {@code ProcessarArquivoUseCase});
     * {@code CacheDocumento} e {@code CacheManutencaoService} não entram por não serem
     * consumidos diretamente pela fatia {@code traducao}.
     */
    private static final Set<String> CACHE_TRADUCAO_TIPOS_CONGELADOS = Set.of(
        RAIZ + ".cachetraducao.infrastructure.CacheTraducaoService",
        RAIZ + ".cachetraducao.domain.EntradaCache",
        RAIZ + ".cachetraducao.domain.ProvenienciaCache"
    );

    /**
     * Superfície do peer {@code contexto} congelada POR TIPO (medida na E7b), SEPARADA dos
     * demais peers. A Tradução Local só pode depender nominalmente destes tipos; qualquer
     * tipo extra reprova até autorização explícita (sem liberação genérica do pacote
     * {@code contexto}). É EXATA: {@link #contextoCongeladoPorTipo()} exige igualdade
     * {@code tiposContextoUsados == CONTEXTO_TIPOS_CONGELADOS}, reprovando tanto tipo novo
     * inesperado quanto entrada obsoleta sobrando na allowlist. Conteúdo medido pós-E7b:
     * <ul>
     *   <li>{@code GerenciadorContexto} (infrastructure): consumido por classes de
     *       {@code traducao} — os pontos de ENTRADA que resolvem o contexto do job
     *       ({@code TraducaoController} via {@code snapshotPorId}, {@code TradutorCLI} via
     *       {@code snapshotAtivo}, {@code PipelineController}), a guarda obra×contexto
     *       ({@code idsQueReconhecem}), o {@code LlmClientAdapter} e o
     *       {@code LoreAtivaContextoAdapter} — migrou do {@code traducao} para o peer na E7b.
     *       {@code ProcessarArquivoUseCase} e {@code ResolvedorCacheTraducao} SAÍRAM da lista
     *       de consumidores: o contexto do job passou a chegar neles por PARÂMETRO (ver
     *       {@link #contextoDoJobEntraPorParametroNaoPeloGerenciador()}).</li>
     *   <li>{@code ProvedorContexto}: {@code PipelineController.getProvedores()}
     *       (lambda {@code p -> new ContextoResponse(p.getId()...)}).</li>
     *   <li>{@code RegrasConcordanciaPtBr}: {@code LlmClientAdapter} (bloco de tradução
     *       e prompt de revisão de concordância).</li>
     *   <li>{@code SnapshotContexto}: fotografia imutável do contexto, congelada UMA vez por
     *       JOB no ponto de entrada e repassada por parâmetro a cada arquivo do lote.</li>
     *   <li>Guarda obra×contexto (identidade de obra é do peer {@code contexto}):
     *       {@code ValidadorCompatibilidadeObraContexto} (emite o veredicto e redige as
     *       mensagens) e {@code VeredictoObraContexto} (o desfecho). Ambos chegaram a nascer
     *       em {@code qualidadeTraducao} e foram movidos para cá; a fatia {@code traducao}
     *       consome os dois APENAS pelo {@code GuardaContextoObraTraducao}, que traduz
     *       veredicto em efeito (bloqueio/aviso) e não decide compatibilidade.</li>
     * </ul>
     * SAÍRAM na E7b (deixaram de ser consumidos diretamente por {@code traducao}, pois só o
     * manager os usava): {@code ContextoPrompt} (obterLoreAtiva) e
     * {@code ContextoNaoEncontradoException} (lançada pelo manager). As 56 lores, as três
     * agregadoras Macross, {@code ExcecaoContexto} e {@code ContextoBeansConfig} NÃO entram.
     */
    private static final Set<String> CONTEXTO_TIPOS_CONGELADOS = Set.of(
        RAIZ + ".lore.infrastructure.GerenciadorContexto",
        RAIZ + ".lore.domain.ProvedorContexto",
        RAIZ + ".lore.domain.RegrasConcordanciaPtBr",
        // A fotografia imutável do contexto, congelada UMA vez por JOB no ponto de entrada
        // (TraducaoController/TradutorCLI) e trafegada por PARÂMETRO por ProcessarArquivoUseCase,
        // ResolvedorCacheTraducao, GuardaContextoObraTraducao, ContextoCongeladoDaExecucao
        // (ponte legada) e LoreAtivaContextoAdapter.
        RAIZ + ".lore.domain.SnapshotContexto",
        // Guarda obra×contexto (identidade de obra pertence ao peer contexto): o validador
        // que emite o veredicto e redige as mensagens, e o próprio veredicto. Consumidos
        // SOMENTE por GuardaContextoObraTraducao, o tradutor de veredicto em efeito da fatia.
        RAIZ + ".lore.application.ValidadorCompatibilidadeObraContexto",
        RAIZ + ".lore.domain.VeredictoObraContexto"
    );

    /**
     * PROPÓSITO DE NEGÓCIO: superfície do peer {@code qualidadeTraducao} (E8b/E8c/E8c.1)
     * congelada POR TIPO com igualdade EXATA, SEPARADA dos demais peers. A Tradução Local só
     * pode depender nominalmente destes tipos; qualquer tipo extra reprova até autorização
     * explícita (sem liberação genérica do pacote {@code qualidadeTraducao}).
     *
     * <p>INVARIANTES DO DOMÍNIO: contém apenas os tipos que a fatia {@code traducao}
     * consome de fato (medido): {@code MascaradorTags} e {@code AlucinacaoDetectadaException}
     * desde a E8b; {@code ValidadorTraducaoService} (via {@code ProcessarArquivoUseCase} e
     * {@code ProcessarEpisodioUseCase}) e {@code ProtecaoLegendaAssService} (via
     * {@code ProcessarArquivoUseCase}) desde a E8c; {@code DetectorTraducaoIdenticaService}
     * (via {@code ProcessarArquivoUseCase}) e {@code LoreAtivaPort} (implementada pelo
     * {@code LoreAtivaContextoAdapter} em {@code traducao.infrastructure}) desde a E8c.1.
     * {@code ExcecaoQualidadeTraducao} NÃO entra por ser base interna da exceção, sem consumo
     * direto medido. O nested {@code MascaradorTags$Mascarado} é normalizado ao proprietário
     * {@code MascaradorTags}. {@code GuardaObraContextoService} e {@code VeredictoObraContexto}
     * SAÍRAM deste conjunto: a compatibilidade obra×contexto migrou para o peer {@code contexto}
     * (ver {@link #CONTEXTO_TIPOS_CONGELADOS}), porque identidade de obra é do dono da obra e
     * este peer valida o TEXTO produzido.
     *
     * <p>COMPORTAMENTO EM CASO DE FALHA: {@link #qualidadeTraducaoCongeladoPorTipo()} exige
     * igualdade {@code tiposUsados == QUALIDADE_TRADUCAO_TIPOS_CONGELADOS}, reprovando tanto
     * tipo novo inesperado quanto entrada obsoleta na allowlist.
     */
    private static final Set<String> QUALIDADE_TRADUCAO_TIPOS_CONGELADOS = Set.of(
        RAIZ + ".qualidadeTraducao.application.DetectorTraducaoIdenticaService",
        // RemovedorItalico, 22/08/2026. Nasceu em traducao.application, junto dos irmaos
        // SimplificadorItalicoRedundante e DescarteItalicoUltimoRecurso, e mudou de casa no
        // MESMO dia por um motivo concreto: a fatia raspagemRevisao passou a precisar dele —
        // o SincronizadorLegendaCacheService escreve na legenda o valor do CACHE, e o cache
        // anterior a regra tem italico, entao a 3.1 reintroduziria o que a 2.1 tirou.
        //
        // A alternativa era COPIAR a classe para a segunda fatia, que e exatamente a classe de
        // defeito que CatracaRegraDuplicadaEntreFatiasTest conta. Ela ja pegou uma copia menor
        // nesta mesma mudanca: o padrao \\i[01]? tinha sido duplicado na telemetria, e a saida
        // foi dar DONO UNICO ao padrao (RemovedorItalico.temItalico), nao declarar a copia.
        RAIZ + ".qualidadeTraducao.application.RemovedorItalico",
        // EnforcadorTermosLore movido de traducao.application para o peer: havia uma SEGUNDA
        // cópia do algoritmo em revisaoLore, criada por não poder importar desta fatia, e as
        // duas divergiram em três propriedades (teto de ocorrências, ordem por comprimento,
        // canônico multi-palavra). Como peer, uma cópia só serve tradução, revisão de lore e a
        // correção de cache sem nenhuma aresta de dívida.
        RAIZ + ".qualidadeTraducao.application.EnforcadorTermosLore",
        // IsoladorQuebraDialogo é compartilhado por tradução e revisão: ambas retiram \N antes
        // do LLM e o recolocam deterministicamente, sem manter duas implementações da regra.
        RAIZ + ".qualidadeTraducao.application.IsoladorQuebraDialogo",
        RAIZ + ".qualidadeTraducao.application.MascaradorTags",
        // ReparadorMarcadoresLlm movido de traducao.application em 09/10/2026: a Revisão de Lore
        // descartava a resposta do modelo que só perdeu o [[TAGn]] de borda (57 de 123 falas
        // sinalizadas no filme de Sidonia, no teste ponta a ponta), porque o reparo morava nesta
        // fatia e a outra não podia importá-lo. Mecânica do formato do MascaradorTags, ao lado dele.
        RAIZ + ".qualidadeTraducao.application.ReparadorMarcadoresLlm",
        // NormalizadorAcentosComuns movido de traducao.application para o peer: fecha o arco
        // proibido raspagemRevisao.RevisorPtOnlyService -> traducao (INBOUND voltou a 0).
        RAIZ + ".qualidadeTraducao.application.NormalizadorAcentosComuns",
        // CorretorHomografoComOriginal entrou em 03/09/2026. É o par que o NormalizadorAcentos
        // EXCLUI de propósito — `e` e `esta` são palavras válidas, e trocá-las sem contexto
        // introduz erro em toda coordenação. O contexto que falta está no ORIGINAL INGLÊS, que
        // esta fatia tem em mãos ao gravar. Mora no peer pelo mesmo critério das duas linhas
        // acima: opera sobre o TEXTO, não sabe qual obra é, e DUAS fatias o consomem (traducao
        // e raspagemRevisao) — na fatia teria de ser copiado, que é a classe de defeito que
        // CatracaRegraDuplicadaEntreFatiasTest existe para contar.
        RAIZ + ".qualidadeTraducao.application.CorretorHomografoComOriginal",
        RAIZ + ".qualidadeTraducao.application.ProtecaoLegendaAssService",
        RAIZ + ".qualidadeTraducao.application.ValidadorTraducaoService",
        // Subpacote nomeProprio, 13/08/2026: mede nome próprio do original que não chegou à
        // tradução. Só o detector e o veredito cruzam a fronteira — o extrator é
        // package-private e fica dentro do peer, de propósito: a heurística de capitalização
        // sozinha é o que produziu 57,7% de falso positivo na versão anterior desta ideia, e
        // fora do peer alguém a usaria sem o filtro do dicionário que a torna utilizável.
        RAIZ + ".qualidadeTraducao.application.nomeProprio.DetectorNomeProprioTraduzido",
        RAIZ + ".qualidadeTraducao.application.nomeProprio.VeredictoNomeProprio",
        RAIZ + ".qualidadeTraducao.domain.AlucinacaoDetectadaException",
        RAIZ + ".qualidadeTraducao.domain.LoreAtivaPort"
    );

    /**
     * PROPÓSITO DE NEGÓCIO: superfície do peer de topo {@code llm} (E8d) congelada POR TIPO
     * com igualdade EXATA, SEPARADA dos demais peers. A Tradução Local só pode depender
     * nominalmente destes tipos; qualquer tipo extra reprova até autorização explícita (sem
     * liberação genérica do pacote {@code llm}).
     *
     * <p>INVARIANTES DO DOMÍNIO: contém apenas os tipos que a fatia {@code traducao} consome
     * de fato (medido) — o contrato {@code LlmPort} e seus records {@code Lote},
     * {@code TraducaoLote} e {@code StatusLlm} — via {@code LlmClientAdapter} (ponto de
     * composição), use cases e presentation. O adapter permanece em
     * {@code traducao.infrastructure}; por isso traducao consome o peer {@code llm}.
     *
     * <p>COMPORTAMENTO EM CASO DE FALHA: {@link #llmCongeladoPorTipo()} exige igualdade
     * {@code tiposUsados == LLM_TIPOS_CONGELADOS}, reprovando tanto tipo novo inesperado
     * quanto entrada obsoleta na allowlist.
     */
    private static final Set<String> LLM_TIPOS_CONGELADOS = Set.of(
        RAIZ + ".llm.domain.LlmPort",
        RAIZ + ".llm.domain.Lote",
        RAIZ + ".llm.domain.StatusLlm",
        RAIZ + ".llm.domain.TraducaoLote"
    );

    private static JavaClasses classesProducao;

    @BeforeAll
    static void importar() {
        classesProducao = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages(RAIZ);
    }

    @Test
    @DisplayName("ArchUnit importa o bytecode Java 25 (class file major 69) da Tradução Local")
    void importaBytecodeJava25() {
        assertFalse(classesProducao.isEmpty(), "ArchUnit não importou nenhuma classe de produção");
        boolean achouTraducao = classesProducao.stream().anyMatch(c -> c.getName().equals(PROCESSAR_ARQUIVO));
        assertTrue(achouTraducao,
            "ArchUnit deve importar classes Java 25 da Tradução Local (prova de leitura do major 69)");
    }

    @Test
    @DisplayName("Saídas funcionais da Tradução Local == ZERO arestas (FASE C2 concluída)")
    void saidasFuncionaisBatemComAllowlistExata() {
        Set<String> reais = new TreeSet<>();
        for (JavaClass classe : classesProducao) {
            if (!ehDaTraducao(classe)) {
                continue;
            }
            String origem = topo(classe.getName());
            for (Dependency dependencia : classe.getDirectDependenciesFromSelf()) {
                // Destino normalizado à classe de topo que o encerra: um tipo
                // aninhado (enum/record membro) pertence à mesma classe-alvo já
                // catalogada. Granularidade permanece de CLASSE, nunca de fatia.
                String destino = topo(dependencia.getTargetClass().getName());
                String fatia = fatiaDe(dependencia.getTargetClass().getPackageName());
                if (fatia == null || fatia.equals(FATIA_TRADUCAO) || fatia.equals(FATIA_CORE)
                    || fatia.equals(FATIA_CONFIG) || fatia.equals(FATIA_LEGENDA)
                    || fatia.equals(FATIA_CACHETRADUCAO) || fatia.equals(FATIA_CONTEXTO)
                    || fatia.equals(FATIA_QUALIDADETRADUCAO) || fatia.equals(FATIA_LLM)) {
                    continue; // core, config, legenda, cachetraducao, contexto e qualidadeTraducao (peers) e interno tratados em testes próprios
                }
                reais.add(aresta(origem, destino));
            }
        }

        Set<String> inesperadas = new TreeSet<>(reais);
        inesperadas.removeAll(ARESTAS_FUNCIONAIS_ESPERADAS);
        Set<String> ausentes = new TreeSet<>(ARESTAS_FUNCIONAIS_ESPERADAS);
        ausentes.removeAll(reais);

        assertTrue(inesperadas.isEmpty() && ausentes.isEmpty(),
            () -> "Após a FASE C2, a Tradução Local deve ter ZERO arestas funcionais de saída.\n"
                + "Arestas INESPERADAS (bumerangue/saída remanescente): " + inesperadas + "\n"
                + "Arestas ESPERADAS AUSENTES: " + ausentes);
    }

    @Test
    @DisplayName("Saída da Tradução Local para config == ZERO arestas (D-Config concluída)")
    void saidaParaConfigEhZero() {
        Set<String> reais = new TreeSet<>();
        for (JavaClass classe : classesProducao) {
            if (!ehDaTraducao(classe)) {
                continue;
            }
            String origem = topo(classe.getName());
            for (Dependency dependencia : classe.getDirectDependenciesFromSelf()) {
                if (FATIA_CONFIG.equals(fatiaDe(dependencia.getTargetClass().getPackageName()))) {
                    reais.add(aresta(origem, topo(dependencia.getTargetClass().getName())));
                }
            }
        }
        assertTrue(reais.isEmpty(),
            () -> "Após D-Config nenhuma classe de traducao pode depender de config "
                + "(ALLOW-CONFIG-CLI eliminada). Encontrado: " + reais);
    }

    @Test
    @DisplayName("config não depende de traducao (regra reversa exata: ZERO arestas, D-Config concluída)")
    void configNaoDependeDeTraducao() {
        Set<String> reais = new TreeSet<>();
        for (JavaClass classe : classesProducao) {
            if (!ehDoConfig(classe)) {
                continue;
            }
            String origem = topo(classe.getName());
            for (Dependency dependencia : classe.getDirectDependenciesFromSelf()) {
                if (ehDaTraducao(dependencia.getTargetClass())) {
                    reais.add(aresta(origem, topo(dependencia.getTargetClass().getName())));
                }
            }
        }
        assertTrue(reais.isEmpty(),
            () -> "Após D-Config a fatia config não pode depender de traducao "
                + "(ALLOW-STARTUP-CLI eliminada; TRADUZIR tem bootstrap próprio). Encontrado: " + reais);
    }

    @Test
    @DisplayName("core é congelado por tipo: somente os doze tipos homologados (5 técnicos + 5 kernel Web da C2 + 1 UI de console da E2 + 1 cliente HTTP da E4a)")
    void coreCongeladoPorTipo() {
        List<String> violacoes = new ArrayList<>();
        Set<String> tiposCoreUsados = new TreeSet<>();
        for (JavaClass classe : classesProducao) {
            if (!ehDaTraducao(classe)) {
                continue;
            }
            String origem = topo(classe.getName());
            for (Dependency dependencia : classe.getDirectDependenciesFromSelf()) {
                String destino = topo(dependencia.getTargetClass().getName());
                if (FATIA_CORE.equals(fatiaDe(dependencia.getTargetClass().getPackageName()))) {
                    tiposCoreUsados.add(destino);
                    if (!CORE_TIPOS_CONGELADOS.contains(destino)) {
                        violacoes.add(origem + " -> " + destino);
                    }
                }
            }
        }
        assertFalse(tiposCoreUsados.isEmpty(),
            "Esperado uso técnico dos tipos de core congelados pela Tradução Local");
        assertTrue(violacoes.isEmpty(),
            () -> "Tipo de core fora dos doze homologados:\n" + String.join("\n", new TreeSet<>(violacoes)));
        // IGUALDADE, e não só subconjunto (auditoria de 28/10/2026): tipo homologado que ninguém
        // usa é entrada morta, e entrada morta numa lista de permissão é permissão sobrando para a
        // próxima aresta entrar sem revisão. A lista só desce.
        Set<String> semUso = new TreeSet<>(CORE_TIPOS_CONGELADOS);
        semUso.removeAll(tiposCoreUsados);
        assertTrue(semUso.isEmpty(),
            () -> "Tipo de core homologado e sem uso na Tradução Local — tire da lista:\n" + String.join("\n", semUso));
    }

    /**
     * Arestas {@code traducao.application} → qualquer pacote {@code infrastructure} (da fatia ou
     * de peer), congeladas por IDENTIDADE (A4). As fatias {@code legenda}, {@code lore} e
     * {@code traducaoCorrige} já proíbem application → infrastructure; a {@code traducao} não tinha
     * regra nenhuma (auditoria de 28/10/2026). As arestas abaixo são a linha de base medida no
     * bytecode nessa data: tirar qualquer uma delas exige porta nova, que é trabalho próprio e não
     * cabe numa catraca. A regra impede a PRÓXIMA — aresta nova reprova listando qual entrou, e
     * aresta que sumiu também reprova, para a lista descer junto.
     */
    private static final Set<String> APLICACAO_PARA_INFRAESTRUTURA_CONGELADAS = Set.of(
        "org.traducao.projeto.traducao.application.GuardaContextoObraTraducao -> org.traducao.projeto.lore.infrastructure.GerenciadorContexto",
        "org.traducao.projeto.traducao.application.MontadorTelemetriaTraducao -> org.traducao.projeto.traducao.infrastructure.config.LlmProperties",
        "org.traducao.projeto.traducao.application.PoliticaBackupTraducao -> org.traducao.projeto.cachetraducao.infrastructure.CacheTraducaoService",
        "org.traducao.projeto.traducao.application.ProcessarArquivoUseCase -> org.traducao.projeto.cachetraducao.infrastructure.CacheTraducaoService",
        "org.traducao.projeto.traducao.application.ProcessarArquivoUseCase -> org.traducao.projeto.legenda.infrastructure.EscritorLegendaAss",
        "org.traducao.projeto.traducao.application.ProcessarArquivoUseCase -> org.traducao.projeto.legenda.infrastructure.EscritorLegendaSrt",
        "org.traducao.projeto.traducao.application.ProcessarArquivoUseCase -> org.traducao.projeto.legenda.infrastructure.LeitorLegendaAss",
        "org.traducao.projeto.traducao.application.ProcessarArquivoUseCase -> org.traducao.projeto.legenda.infrastructure.LeitorLegendaSrt",
        "org.traducao.projeto.traducao.application.ProcessarArquivoUseCase -> org.traducao.projeto.traducao.infrastructure.config.TradutorProperties",
        "org.traducao.projeto.traducao.application.PublicadorLegendaMescla -> org.traducao.projeto.legenda.infrastructure.EscritorLegendaAss",
        "org.traducao.projeto.traducao.application.PublicadorLegendaMescla -> org.traducao.projeto.legenda.infrastructure.EscritorLegendaSrt",
        "org.traducao.projeto.traducao.application.PublicadorLegendaMescla -> org.traducao.projeto.legenda.infrastructure.LeitorLegendaAss",
        "org.traducao.projeto.traducao.application.PublicadorLegendaMescla -> org.traducao.projeto.legenda.infrastructure.LeitorLegendaSrt",
        "org.traducao.projeto.traducao.application.RecuperarPendenciaFallbackService -> org.traducao.projeto.traducao.infrastructure.config.FallbackOnlineProperties",
        "org.traducao.projeto.traducao.application.ResolvedorCacheTraducao -> org.traducao.projeto.traducao.infrastructure.config.LlmProperties",
        "org.traducao.projeto.traducao.application.ResolvedorCacheTraducao -> org.traducao.projeto.traducao.infrastructure.config.TradutorProperties",
        "org.traducao.projeto.traducao.application.TradutorLotesService -> org.traducao.projeto.traducao.infrastructure.config.TradutorProperties"
    );

    @Test
    @DisplayName("traducao.application -> infrastructure: só as arestas da linha de base de 28/10/2026 (catraca por identidade)")
    void aplicacaoParaInfraestruturaCongelada() {
        Set<String> arestas = new TreeSet<>();
        for (JavaClass classe : classesProducao) {
            if (!classe.getPackageName().equals(PKG_TRADUCAO + ".application")) {
                continue;
            }
            String origem = topo(classe.getName());
            for (Dependency dependencia : classe.getDirectDependenciesFromSelf()) {
                String pkgDestino = dependencia.getTargetClass().getPackageName();
                if (pkgDestino.startsWith(PREFIXO) && (pkgDestino.contains(".infrastructure.")
                        || pkgDestino.endsWith(".infrastructure"))) {
                    arestas.add(aresta(origem, topo(dependencia.getTargetClass().getName())));
                }
            }
        }
        assertFalse(arestas.isEmpty(), "o instrumento tinha de enxergar as arestas da linha de base");
        Set<String> novas = new TreeSet<>(arestas);
        novas.removeAll(APLICACAO_PARA_INFRAESTRUTURA_CONGELADAS);
        Set<String> sumiram = new TreeSet<>(APLICACAO_PARA_INFRAESTRUTURA_CONGELADAS);
        sumiram.removeAll(arestas);
        assertTrue(novas.isEmpty() && sumiram.isEmpty(),
            () -> "Arestas application -> infrastructure mudaram.\nNOVAS (crie uma porta em vez disso):\n"
                + String.join("\n", novas) + "\nSUMIRAM (tire da lista):\n" + String.join("\n", sumiram));
    }

    @Test
    @DisplayName("legenda é congelado por tipo: Tradução Local só usa os tipos homologados do módulo (E3c/E5a/E5b + E5c: Leitor/Escritor Ass/Srt)")
    void legendaCongeladoPorTipo() {
        List<String> violacoes = new ArrayList<>();
        Set<String> tiposLegendaUsados = new TreeSet<>();
        for (JavaClass classe : classesProducao) {
            if (!ehDaTraducao(classe)) {
                continue;
            }
            String origem = topo(classe.getName());
            for (Dependency dependencia : classe.getDirectDependenciesFromSelf()) {
                String destino = topo(dependencia.getTargetClass().getName());
                if (FATIA_LEGENDA.equals(fatiaDe(dependencia.getTargetClass().getPackageName()))) {
                    tiposLegendaUsados.add(destino);
                    if (!LEGENDA_TIPOS_CONGELADOS.contains(destino)) {
                        violacoes.add(origem + " -> " + destino);
                    }
                }
            }
        }
        assertFalse(tiposLegendaUsados.isEmpty(),
            "Esperado uso do módulo legenda pela Tradução Local (E3c: PoliticaEstiloMusical)");
        assertTrue(violacoes.isEmpty(),
            () -> "Tipo de legenda fora dos homologados (LEGENDA_TIPOS_CONGELADOS):\n"
                + String.join("\n", new TreeSet<>(violacoes)));
    }

    @Test
    @DisplayName("cachetraducao é congelado por tipo: Tradução Local só usa os tipos homologados do peer (E6)")
    void cacheTraducaoCongeladoPorTipo() {
        List<String> violacoes = new ArrayList<>();
        Set<String> tiposCacheUsados = new TreeSet<>();
        for (JavaClass classe : classesProducao) {
            if (!ehDaTraducao(classe)) {
                continue;
            }
            String origem = topo(classe.getName());
            for (Dependency dependencia : classe.getDirectDependenciesFromSelf()) {
                String destino = topo(dependencia.getTargetClass().getName());
                if (FATIA_CACHETRADUCAO.equals(fatiaDe(dependencia.getTargetClass().getPackageName()))) {
                    tiposCacheUsados.add(destino);
                    if (!CACHE_TRADUCAO_TIPOS_CONGELADOS.contains(destino)) {
                        violacoes.add(origem + " -> " + destino);
                    }
                }
            }
        }
        assertFalse(tiposCacheUsados.isEmpty(),
            "Esperado uso do peer cachetraducao pela Tradução Local (E6: CacheTraducaoService/EntradaCache/ProvenienciaCache)");
        assertTrue(violacoes.isEmpty(),
            () -> "Tipo de cachetraducao fora dos homologados (CACHE_TRADUCAO_TIPOS_CONGELADOS):\n"
                + String.join("\n", new TreeSet<>(violacoes)));
    }

    /**
     * PROPÓSITO DE NEGÓCIO: congela o ÚNICO ponto da fatia {@code traducao} autorizado a tocar
     * na compatibilidade obra×contexto — {@code GuardaContextoObraTraducao}, que traduz o
     * veredicto do peer {@code contexto} em efeito (bloqueio, aviso ou seguimento). Espalhar
     * esse consumo por use cases ou controllers duplicaria, dentro da fatia, uma regra cujo
     * dono é o peer {@code contexto}.
     *
     * <p>INVARIANTES DO DOMÍNIO: nenhuma classe de {@code traducao} além de
     * {@code traducao.application.GuardaContextoObraTraducao} pode depender de
     * {@code ValidadorCompatibilidadeObraContexto} ou de {@code VeredictoObraContexto}. Os
     * demais consumidores da fatia enxergam apenas o EFEITO
     * ({@code ObraDivergenteDoContextoException}), nunca o veredicto.
     *
     * <p>COMPORTAMENTO EM CASO DE FALHA: qualquer outra classe de {@code traducao} apontando
     * para um dos dois tipos reprova, listando a aresta exata.
     */
    @Test
    @DisplayName("obra×contexto entra em traducao SÓ pelo GuardaContextoObraTraducao (a fatia traduz veredicto em efeito, não julga)")
    void obraContextoEntraSoPelaGuardaDaTraducao() {
        Set<String> tiposDoVeredicto = Set.of(
            RAIZ + ".lore.application.ValidadorCompatibilidadeObraContexto",
            RAIZ + ".lore.domain.VeredictoObraContexto");
        String guardaAutorizada = RAIZ + ".traducao.application.GuardaContextoObraTraducao";

        Set<String> violacoes = new TreeSet<>();
        boolean guardaConsome = false;
        for (JavaClass classe : classesProducao) {
            if (!ehDaTraducao(classe)) {
                continue;
            }
            String origem = topo(classe.getName());
            for (Dependency dependencia : classe.getDirectDependenciesFromSelf()) {
                String destino = topo(dependencia.getTargetClass().getName());
                if (!tiposDoVeredicto.contains(destino)) {
                    continue;
                }
                if (origem.equals(guardaAutorizada)) {
                    guardaConsome = true;
                } else {
                    violacoes.add(aresta(origem, destino));
                }
            }
        }
        assertTrue(guardaConsome,
            "GuardaContextoObraTraducao deve continuar consumindo o veredicto do peer contexto "
                + "(é ela quem traduz DIVERGENTE em bloqueio e INDETERMINADO em aviso)");
        assertTrue(violacoes.isEmpty(),
            () -> "Só GuardaContextoObraTraducao pode consumir a compatibilidade obra×contexto dentro de "
                + "traducao; as demais classes veem apenas o efeito (ObraDivergenteDoContextoException). "
                + "Violações:\n" + String.join("\n", violacoes));
    }

    /**
     * PROPÓSITO DE NEGÓCIO: congela a direção pela qual o contexto/lore entra no PIPELINE de
     * tradução — por PARÂMETRO, resolvido UMA vez por job no ponto de entrada. O
     * {@code GerenciadorContexto} guarda um contexto ativo global e mutável, compartilhado com
     * todas as outras rotas (correção, revisão, karaokê) e com a UI; qualquer classe do
     * pipeline que volte a consultá-lo no meio de um lote reabre a janela do incidente medido
     * nesta árvore — arquivos do mesmo lote traduzidos, validados e carimbados com obras
     * diferentes, sem nenhum sinal.
     *
     * <p>INVARIANTES DO DOMÍNIO: dentro de {@code traducao.application}, SOMENTE
     * {@code GuardaContextoObraTraducao} pode depender de {@code GerenciadorContexto} — e por
     * um motivo que não é escolher lore: ela pergunta ao catálogo QUAIS contextos reconhecem a
     * pasta ({@code idsQueReconhecem}), fato que não existe no snapshot. Todo o resto
     * ({@code ProcessarArquivoUseCase}, {@code ResolvedorCacheTraducao}, …) recebe
     * {@code SnapshotContexto} na assinatura. Resolver o contexto do job continua permitido em
     * {@code presentation} (controller e CLI), que é onde o id explícito chega.
     *
     * <p>COMPORTAMENTO EM CASO DE FALHA: qualquer outra classe de {@code traducao.application}
     * apontando para o gerenciador reprova, listando a aresta exata.
     */
    @Test
    @DisplayName("contexto do job entra por PARÂMETRO: em traducao.application só a guarda obra×contexto conhece o GerenciadorContexto")
    void contextoDoJobEntraPorParametroNaoPeloGerenciador() {
        String gerenciador = RAIZ + ".lore.infrastructure.GerenciadorContexto";
        String guardaAutorizada = RAIZ + ".traducao.application.GuardaContextoObraTraducao";
        String pkgApplication = RAIZ + ".traducao.application";

        Set<String> violacoes = new TreeSet<>();
        boolean guardaConsome = false;
        for (JavaClass classe : classesProducao) {
            String pkg = classe.getPackageName();
            if (!(pkg.equals(pkgApplication) || pkg.startsWith(pkgApplication + "."))) {
                continue;
            }
            String origem = topo(classe.getName());
            for (Dependency dependencia : classe.getDirectDependenciesFromSelf()) {
                if (!gerenciador.equals(topo(dependencia.getTargetClass().getName()))) {
                    continue;
                }
                if (origem.equals(guardaAutorizada)) {
                    guardaConsome = true;
                } else {
                    violacoes.add(aresta(origem, gerenciador));
                }
            }
        }
        assertTrue(guardaConsome,
            "GuardaContextoObraTraducao deve continuar consultando o catálogo (idsQueReconhecem): "
                + "quais contextos reconhecem a pasta é fato do catálogo, não do snapshot");
        assertTrue(violacoes.isEmpty(),
            () -> "Nenhuma outra classe de traducao.application pode ler o contexto ATIVO GLOBAL: "
                + "o contexto do job é resolvido UMA vez no ponto de entrada e trafega por "
                + "parâmetro (SnapshotContexto). Violações:\n" + String.join("\n", violacoes));
    }

    @Test
    @DisplayName("contexto é congelado por tipo com igualdade EXATA: Tradução Local usa exatamente os tipos homologados do peer (E7b + guarda obra×contexto)")
    void contextoCongeladoPorTipo() {
        List<String> violacoes = new ArrayList<>();
        Set<String> tiposContextoUsados = new TreeSet<>();
        for (JavaClass classe : classesProducao) {
            if (!ehDaTraducao(classe)) {
                continue;
            }
            String origem = topo(classe.getName());
            for (Dependency dependencia : classe.getDirectDependenciesFromSelf()) {
                String destino = topo(dependencia.getTargetClass().getName());
                if (FATIA_CONTEXTO.equals(fatiaDe(dependencia.getTargetClass().getPackageName()))) {
                    tiposContextoUsados.add(destino);
                    if (!CONTEXTO_TIPOS_CONGELADOS.contains(destino)) {
                        violacoes.add(origem + " -> " + destino);
                    }
                }
            }
        }
        assertFalse(tiposContextoUsados.isEmpty(),
            "Esperado uso do peer contexto pela Tradução Local (E7b: GerenciadorContexto/ProvedorContexto/RegrasConcordanciaPtBr)");
        assertTrue(violacoes.isEmpty(),
            () -> "Tipo de contexto fora dos homologados (CONTEXTO_TIPOS_CONGELADOS):\n"
                + String.join("\n", new TreeSet<>(violacoes)));
        // Igualdade EXATA (E7b): cobre tipos inesperados (violacoes) e entradas obsoletas na allowlist.
        assertEquals(new TreeSet<>(CONTEXTO_TIPOS_CONGELADOS), tiposContextoUsados,
            "CONTEXTO_TIPOS_CONGELADOS deve ser EXATAMENTE igual ao conjunto de tipos de contexto consumidos por traducao");
    }

    /**
     * PROPÓSITO DE NEGÓCIO: congela por tipo, com igualdade EXATA, a superfície do peer
     * {@code qualidadeTraducao} consumida pela Tradução Local (E8b) — garante que a
     * extração de {@code MascaradorTags} e {@code AlucinacaoDetectadaException} não abriu
     * dependência não homologada nem deixou entrada obsoleta na allowlist.
     *
     * <p>INVARIANTES DO DOMÍNIO: os tipos de {@code qualidadeTraducao} consumidos por
     * {@code traducao} são normalizados à classe de topo (nested {@code MascaradorTags$Mascarado}
     * vira {@code MascaradorTags}) e devem ser EXATAMENTE
     * {@link #QUALIDADE_TRADUCAO_TIPOS_CONGELADOS}.
     *
     * <p>COMPORTAMENTO EM CASO DE FALHA: um tipo fora do conjunto (violação) ou o conjunto
     * medido diferente do congelado (tipo novo ou entrada obsoleta) reprova o teste.
     */
    @Test
    @DisplayName("qualidadeTraducao é congelado por tipo com igualdade EXATA: MascaradorTags, AlucinacaoDetectadaException (E8b), ValidadorTraducaoService, ProtecaoLegendaAssService (E8c), DetectorTraducaoIdenticaService e LoreAtivaPort (E8c.1) — sem nada de identidade de obra")
    void qualidadeTraducaoCongeladoPorTipo() {
        List<String> violacoes = new ArrayList<>();
        Set<String> tiposQualidadeUsados = new TreeSet<>();
        for (JavaClass classe : classesProducao) {
            if (!ehDaTraducao(classe)) {
                continue;
            }
            String origem = topo(classe.getName());
            for (Dependency dependencia : classe.getDirectDependenciesFromSelf()) {
                String destino = topo(dependencia.getTargetClass().getName());
                if (FATIA_QUALIDADETRADUCAO.equals(fatiaDe(dependencia.getTargetClass().getPackageName()))) {
                    tiposQualidadeUsados.add(destino);
                    if (!QUALIDADE_TRADUCAO_TIPOS_CONGELADOS.contains(destino)) {
                        violacoes.add(origem + " -> " + destino);
                    }
                }
            }
        }
        assertFalse(tiposQualidadeUsados.isEmpty(),
            "Esperado uso do peer qualidadeTraducao pela Tradução Local (E8b: MascaradorTags/AlucinacaoDetectadaException)");
        assertTrue(violacoes.isEmpty(),
            () -> "Tipo de qualidadeTraducao fora dos homologados (QUALIDADE_TRADUCAO_TIPOS_CONGELADOS):\n"
                + String.join("\n", new TreeSet<>(violacoes)));
        assertEquals(new TreeSet<>(QUALIDADE_TRADUCAO_TIPOS_CONGELADOS), tiposQualidadeUsados,
            "QUALIDADE_TRADUCAO_TIPOS_CONGELADOS deve ser EXATAMENTE igual ao conjunto de tipos consumidos por traducao");
    }

    /**
     * PROPÓSITO DE NEGÓCIO: prova que a Tradução Local consome do peer {@code llm} EXATAMENTE
     * os tipos homologados em {@link #LLM_TIPOS_CONGELADOS} — o contrato do LLM extraído na
     * E8d — sem liberação genérica do pacote.
     *
     * <p>INVARIANTES DO DOMÍNIO: varre toda a fatia {@code traducao} (inclusive
     * infrastructure), normaliza classes aninhadas ao top-level proprietário e mede os tipos
     * de {@code llm} realmente consumidos. O adapter {@code LlmClientAdapter}, em
     * {@code traducao.infrastructure}, é o principal consumidor (ponto de composição).
     *
     * <p>COMPORTAMENTO EM CASO DE FALHA: reprova por tipo inesperado, por entrada obsoleta ou
     * por qualquer divergência da igualdade EXATA {@code tiposUsados == LLM_TIPOS_CONGELADOS}.
     */
    @Test
    @DisplayName("llm é congelado por tipo com igualdade EXATA: Tradução Local usa exatamente LlmPort, Lote, TraducaoLote e StatusLlm (E8d)")
    void llmCongeladoPorTipo() {
        List<String> violacoes = new ArrayList<>();
        Set<String> tiposLlmUsados = new TreeSet<>();
        for (JavaClass classe : classesProducao) {
            if (!ehDaTraducao(classe)) {
                continue;
            }
            String origem = topo(classe.getName());
            for (Dependency dependencia : classe.getDirectDependenciesFromSelf()) {
                String destino = topo(dependencia.getTargetClass().getName());
                if (FATIA_LLM.equals(fatiaDe(dependencia.getTargetClass().getPackageName()))) {
                    tiposLlmUsados.add(destino);
                    if (!LLM_TIPOS_CONGELADOS.contains(destino)) {
                        violacoes.add(origem + " -> " + destino);
                    }
                }
            }
        }
        assertFalse(tiposLlmUsados.isEmpty(),
            "Esperado uso do peer llm pela Tradução Local (E8d: LlmPort/Lote/TraducaoLote/StatusLlm via LlmClientAdapter)");
        assertTrue(violacoes.isEmpty(),
            () -> "Tipo de llm fora dos homologados (LLM_TIPOS_CONGELADOS):\n"
                + String.join("\n", new TreeSet<>(violacoes)));
        assertEquals(new TreeSet<>(LLM_TIPOS_CONGELADOS), tiposLlmUsados,
            "LLM_TIPOS_CONGELADOS deve ser EXATAMENTE igual ao conjunto de tipos de llm consumidos por traducao");
    }

    @Test
    @DisplayName("revisaoLore não depende da stack LLM da Tradução Local (contexto liberado na FASE E)")
    void revisaoLoreNaoDependeDaStackLlmDeTraducao() {
        List<String> violacoes = new ArrayList<>();
        for (JavaClass classe : classesProducao) {
            String pkg = classe.getPackageName();
            boolean ehRevisaoLore = pkg.equals(PKG_REVISAO_LORE) || pkg.startsWith(PKG_REVISAO_LORE + ".");
            if (!ehRevisaoLore) {
                continue;
            }
            String origem = topo(classe.getName());
            for (Dependency dependencia : classe.getDirectDependenciesFromSelf()) {
                String destino = topo(dependencia.getTargetClass().getName());
                if (REVISAO_LORE_PROIBIDOS.contains(destino)) {
                    violacoes.add(origem + " -> " + destino);
                }
            }
        }
        assertTrue(violacoes.isEmpty(),
            () -> "revisaoLore não pode importar a stack LLM da Tradução Local "
                + "(LlmPort/StatusLlm/LlmProperties/JsonHttpClient/RecordsLlm/LlmClientAdapter) — ela "
                + "tem a própria stack LLM. O GerenciadorContexto (catálogo de contextos) foi "
                + "LIBERADO na FASE E (2026-09-17) para a guarda obra×contexto da 3.2. Violações:\n"
                + String.join("\n", new TreeSet<>(violacoes)));
    }

    @Test
    @DisplayName("traducao não depende de telemetria (ZERO arestas — exceção C2 eliminada)")
    void traducaoNaoDependeDeTelemetria() {
        List<String> violacoes = new ArrayList<>();
        for (JavaClass classe : classesProducao) {
            if (!ehDaTraducao(classe)) {
                continue;
            }
            String origem = topo(classe.getName());
            for (Dependency dependencia : classe.getDirectDependenciesFromSelf()) {
                String destino = topo(dependencia.getTargetClass().getName());
                String pkg = dependencia.getTargetClass().getPackageName();
                boolean ehTelemetria = pkg.equals(PKG_TELEMETRIA) || pkg.startsWith(PKG_TELEMETRIA + ".");
                if (!ehTelemetria) {
                    continue;
                }
                violacoes.add(aresta(origem, destino));
            }
        }
        assertTrue(violacoes.isEmpty(),
            () -> "Nenhuma classe de traducao pode depender de telemetria. Após a FASE C2 o "
                + "TelemetriaController saiu para telemetria.presentation.web e a exceção "
                + "ALLOW-TELEMETRIA-C2 foi eliminada. Violações:\n"
                + String.join("\n", new TreeSet<>(violacoes)));
    }

    @Test
    @DisplayName("core NÃO depende de nenhuma fatia funcional (independência do kernel — regra permanente)")
    void coreNaoDependeDeFatiaFuncional() {
        List<String> violacoes = new ArrayList<>();
        for (JavaClass classe : classesProducao) {
            String pkg = classe.getPackageName();
            boolean ehDoCore = pkg.equals(RAIZ + ".core") || pkg.startsWith(RAIZ + ".core.");
            if (!ehDoCore) {
                continue;
            }
            String origem = topo(classe.getName());
            for (Dependency dependencia : classe.getDirectDependenciesFromSelf()) {
                String pkgDestino = dependencia.getTargetClass().getPackageName();
                String fatia = fatiaDe(pkgDestino);
                // Alvos permitidos: JDK / libs externas (fatia == null) e o próprio core.
                if (fatia == null || fatia.equals(FATIA_CORE_NOME)) {
                    continue;
                }
                violacoes.add(aresta(origem, topo(dependencia.getTargetClass().getName())));
            }
        }
        assertTrue(violacoes.isEmpty(),
            () -> "O core deve depender SOMENTE de JDK, bibliotecas técnicas e do próprio "
                + "org.traducao.projeto.core. Nenhuma allowlist core → fatia funcional é permitida, "
                + "nem temporária. Violações:\n" + String.join("\n", new TreeSet<>(violacoes)));
    }

    private static String aresta(String origemFqn, String destinoFqn) {
        return origemFqn + " -> " + destinoFqn;
    }

    private static boolean ehDaTraducao(JavaClass classe) {
        String pkg = classe.getPackageName();
        return pkg.equals(PKG_TRADUCAO) || pkg.startsWith(PKG_TRADUCAO + ".");
    }

    private static boolean ehDoConfig(JavaClass classe) {
        String pkg = classe.getPackageName();
        return pkg.equals(PKG_CONFIG) || pkg.startsWith(PKG_CONFIG + ".");
    }

    /** Nome da classe de topo (normaliza classes internas/aninhadas da ORIGEM; o destino é mantido exato). */
    private static String topo(String nomeCompleto) {
        int cifrao = nomeCompleto.indexOf('$');
        return cifrao < 0 ? nomeCompleto : nomeCompleto.substring(0, cifrao);
    }

    /** Primeiro segmento após {@code org.traducao.projeto.} (a "fatia"), ou {@code null} se externo. */
    private static String fatiaDe(String pkg) {
        if (pkg == null || !pkg.startsWith(PREFIXO)) {
            return null;
        }
        String resto = pkg.substring(PREFIXO.length());
        int ponto = resto.indexOf('.');
        return ponto < 0 ? resto : resto.substring(0, ponto);
    }
}
