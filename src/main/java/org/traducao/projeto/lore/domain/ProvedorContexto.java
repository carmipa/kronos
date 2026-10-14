package org.traducao.projeto.lore.domain;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * PROPÓSITO DE NEGÓCIO: contrato de um provedor de contexto/lore de tradução — cada
 * obra (Gundam, Macross, Danmachi...) implementa esta interface para fornecer o
 * prompt de sistema do LLM, o rótulo de UI e os termos que não devem ser traduzidos.
 * É o ponto de extensão do módulo compartilhado {@code contexto}: novas obras entram
 * apenas adicionando implementações {@code @Component}, sem tocar em quem consome.
 *
 * <p>INVARIANTES DO DOMÍNIO: interface pura (só depende do JDK); {@link #getId()} é o
 * identificador único e estável usado para seleção e para carimbar a proveniência do
 * cache; {@link #obterPromptSistema()} devolve o prompt completo já montado; termos
 * protegidos são um conjunto imutável (por padrão vazio). Nenhum método realiza I/O.
 *
 * <p>COMPORTAMENTO EM CASO DE FALHA: os métodos não lançam por contrato; um provedor
 * mal formado (id nulo/duplicado) é rejeitado por quem agrega os provedores
 * ({@code GerenciadorContexto}), não por esta interface.
 */
public interface ProvedorContexto {

    /**
     * PROPÓSITO DE NEGÓCIO: id RESERVADO da tradução sem lore declarada — o anime acabou de ser
     * baixado e a intenção é ver como fica. É também o valor que libera a trava de lore na UI.
     *
     * <p>INVARIANTES DO DOMÍNIO: vive no CONTRATO, e não numa implementação, porque a lore deixou
     * de ser classe Java: hoje é um arquivo SQL por obra ({@code lore/obras/sem_lore.sql} inclusive),
     * lido por {@code CatalogoLoreSqlite} — quem precisa do id não pode depender de uma classe
     * concreta. Era a ÚNICA amarra concreta a uma
     * lore em todo o projeto ({@code TraducaoController} importava {@code ContextoSemLore} só
     * por esta constante); tirá-la daqui reabriria essa amarra.
     *
     * <p>COMPORTAMENTO EM CASO DE FALHA: constante; não há falha possível.
     */
    String ID_SEM_LORE = "sem_lore";

    /**
     * Retorna o ID único para seleção via UI.
     */
    String getId();

    /**
     * Retorna o nome amigável para exibição no combo box da UI.
     */
    String getNomeExibicao();

    /**
     * Retorna o prompt de sistema completo para o LLM, com regras gerais e lore especifico da midia.
     */
    String obterPromptSistema();

    /**
     * Termos desta obra que NÃO devem ser traduzidos (nomes próprios, facções,
     * patentes, lugares, mecha). Por padrão vazio; cada contexto pode
     * sobrescrever para que o detector de "tradução idêntica" proteja o lore
     * selecionado, em vez de depender só da lista global fixa no detector.
     */
    default Set<String> termosProtegidos() {
        return Set.of();
    }

    /**
     * Mapa forma-ruim → termo canônico para REFORÇO DETERMINÍSTICO de terminologia
     * pós-tradução: quando o LLM traduz um termo de mundo que a lore manda manter no
     * original (ex.: {@code "Legião" → "Legion"}), este mapa permite restaurar a grafia
     * oficial SEM depender do modelo. Por padrão vazio; cada obra sobrescreve com as
     * traduções indevidas observadas. A chave é a forma-ruim (em PT); o valor é o termo
     * canônico a restaurar. A restauração só ocorre quando o ORIGINAL contém o termo
     * canônico — nunca altera uma tradução legítima que não veio dele.
     */
    default Map<String, String> correcoesTerminologia() {
        return Map.of();
    }

    /**
     * PROPÓSITO DE NEGÓCIO: mapa termo-em-INGLÊS → forma obrigatória em PORTUGUÊS. É o inverso
     * exato de {@link #termosProtegidos()}: aquele manda MANTER em inglês, este manda TRADUZIR
     * de um jeito só.
     *
     * <h2>Por que precisou existir</h2>
     * A lore sabia proteger em inglês e restaurar do inglês, e não sabia mandar traduzir. Com
     * isso, dois pedidos do dono do acervo ficaram sem mecanismo:
     * <ul>
     *   <li><b>22/08/2026</b>: {@code "Universal Century"} devia sair {@code "Século Universal"}.
     *       Estava em {@code termosProtegidos}, então saía em inglês por regra — e a medição de
     *       prontidão achou 7 falas do Unicorn publicadas como {@code "Universal Century 0096."}
     *       </li>
     *   <li>{@code "Side Four"} devia sair {@code "Lado Quatro"} sem que {@code "Four"} sozinho
     *       (Four Murasame, personagem) fosse tocado. Está declarado como lacuna na própria
     *       lore das obras UC ({@code lore/obras/<id>.sql}).</li>
     * </ul>
     * {@code correcoesTerminologia} não servia: ele só age quando o ORIGINAL contém o canônico,
     * e o canônico aqui é português — que nunca aparece no inglês.
     *
     * <h2>Invariantes do domínio</h2>
     * <ul>
     *   <li>Só age quando o termo INGLÊS sobreviveu na tradução. Fala já traduzida de outro
     *       jeito não é reescrita — o mecanismo conserta o que ficou em inglês, não impõe estilo
     *       sobre tradução legítima.</li>
     *   <li>Um termo NÃO pode estar aqui e em {@code termosProtegidos} ao mesmo tempo: proteger
     *       é manter em inglês, e os dois estados se cancelam.</li>
     * </ul>
     *
     * <p>COMPORTAMENTO EM CASO DE FALHA: vazio por padrão; obra que não declara nada não muda
     * comportamento nenhum.
     */
    default Map<String, String> traducoesObrigatorias() {
        return Map.of();
    }

    /**
     * PROPÓSITO DE NEGÓCIO: pares de termos desta obra que o modelo NÃO pode trocar um pelo
     * outro, porque são entidades DIFERENTES com nomes parecidos ou relacionados. Cada par é
     * uma lista de exatamente dois termos; a proibição vale nas duas direções.
     *
     * <h2>Por que uma declaração nova, e não mais texto de prompt</h2>
     * Medido no acervo em 2026-07-28, cada caso com a mesma forma — a lore ensina uma relação
     * e o modelo a lê como licença para substituir:
     * <ul>
     *   <li><b>172 falas</b> em Zeta: {@code Four} (Four Murasame) virou {@code Quatro}
     *       (numeral) e, em 15 delas, {@code Quattro} — OUTRO personagem. A lore repete três
     *       vezes "Quattro NUNCA Quatro" e não diz nada sobre {@code Four}.</li>
     *   <li><b>20 falas</b> em Guilty Crown: {@code Inori} virou {@code Crow}. A lore diz que
     *       Crow é a persona de palco de Inori; {@code Crow} não aparece UMA vez no inglês do
     *       acervo inteiro.</li>
     *   <li><b>55 falas</b> em ZZ: {@code Zeta Gundam} virou {@code ZZ Gundam} — mecha
     *       diferente. E <b>31</b>: {@code Argama} virou {@code Nahel Argama} — nave diferente.</li>
     * </ul>
     *
     * <p>A regra genérica "termo protegido no PT ausente do EN" foi MEDIDA e descartada:
     * dispara 1447 vezes em 59.625 falas, e a maioria esmagadora é normalização legítima
     * ({@code A.E.U.G.}→{@code AEUG}, {@code mobile suit}→{@code Mobile Suit},
     * {@code Ouma Shu}→{@code Shu Ouma}, {@code Undertaker}→{@code Funeral Parlor}). Como
     * portão duro rejeitaria tradução correta em massa. Por par, dispara só onde há confusão
     * real declarada por quem conhece a obra.
     *
     * <p>INVARIANTES DO DOMÍNIO: cada elemento tem exatamente dois termos, ambos não vazios;
     * a relação é simétrica e o consumidor não deve supor ordem. Não é sinônimo nem forma-ruim
     * — para isso existem {@link #termosProtegidos()} e {@link #correcoesTerminologia()}.
     *
     * <p>COMPORTAMENTO EM CASO DE FALHA: por padrão vazio; obra que não declara nada não muda
     * de comportamento.
     */
    default Set<List<String>> paresInconfundiveis() {
        return Set.of();
    }

    /**
     * PROPÓSITO DE NEGÓCIO: nomes ALTERNATIVOS e abreviações pelos quais ESTA obra também é
     * reconhecível na pasta em que os arquivos de legenda moram (ex.: {@code "Stardust Memory"},
     * {@code "Z Gundam"}, {@code "86"}). É o COMPLEMENTO da identidade canônica: o id e o nome
     * de exibição já identificam a obra automaticamente (ver {@link IdentidadeObra#de}), e este
     * conjunto cobre o que eles não alcançam — o título abreviado, o nome do arco/subtítulo, a
     * grafia que o grupo de fansub usa.
     *
     * <p>INVARIANTES DO DOMÍNIO: por padrão VAZIO — e vazio NÃO significa mais "obra sem
     * identidade": desde a identidade canônica derivada, toda obra do catálogo se reconhece
     * pelo id e pelo nome de exibição sem declarar nada. Cada apelido é comparado por
     * SEQUÊNCIA DE PALAVRAS INTEIRAS, então apelidos curtos e numéricos ({@code "86"},
     * {@code "0083"}) são seguros: não casam dentro de outra palavra nem dentro de
     * {@code "(1986)"}. Um apelido idêntico ao de OUTRO provedor é erro de configuração e
     * impede o startup — declarar {@code "Break Blade"} nos seis filmes, por exemplo, é
     * proibido; cada entrada precisa de um nome que só ela reivindique.
     *
     * <p>COMPORTAMENTO EM CASO DE FALHA: nunca lança; o conjunto é imutável e sem I/O.
     *
     * @return apelidos de pasta desta obra, possivelmente vazio
     */
    default Set<String> apelidosPasta() {
        return Set.of();
    }

    /**
     * PROPÓSITO DE NEGÓCIO: responde se o nome de uma pasta de obra identifica ESTA lore.
     * Cada obra sabe se reconhecer; quem orquestra apenas pergunta, sem heurística própria.
     *
     * <p>INVARIANTES DO DOMÍNIO: delega à {@link IdentidadeObra} derivada deste provedor —
     * FONTE ÚNICA do reconhecimento. Nenhuma comparação de nome de obra é reimplementada aqui:
     * duas cópias da regra divergiriam e o catálogo passaria a discordar da guarda sobre o que
     * é a mesma obra. O casamento exige sequência de palavras INTEIRAS e nunca é um palpite por
     * similaridade, prefixo ou distância.
     *
     * <p>COMPORTAMENTO EM CASO DE FALHA: nome nulo/em branco devolve {@code false}; nunca
     * lança.
     *
     * @param nomeDaPasta nome da pasta da obra, cru como veio do sistema de arquivos
     * @return {@code true} somente quando um nome canônico casa por palavras inteiras
     */
    default boolean reconhecePasta(String nomeDaPasta) {
        return IdentidadeObra.de(this).reconhece(nomeDaPasta);
    }

    /**
     * PROPÓSITO DE NEGÓCIO: responde se este contexto deve aparecer na lista de OBRAS que a UI
     * oferece para seleção. Quase todo contexto aparece — a exceção é o contexto genérico de
     * tradução sem lore, que existe para uma tela própria e cuja presença nos combos das outras
     * telas seria um convite ao engano: escolher "sem lore" onde havia lore disponível descarta
     * silenciosamente toda a terminologia da obra.
     *
     * <p>INVARIANTES DO DOMÍNIO: não aparecer na lista NÃO significa não existir — o id continua
     * resolvível por {@code existeContexto} e utilizável pelo pipeline. É filtro de APRESENTAÇÃO,
     * nunca de validade.
     *
     * <p>COMPORTAMENTO EM CASO DE FALHA: por padrão {@code true}; contexto que não declara nada
     * continua listado como sempre.
     */
    default boolean apareceNaListaDeObras() {
        return true;
    }
}
