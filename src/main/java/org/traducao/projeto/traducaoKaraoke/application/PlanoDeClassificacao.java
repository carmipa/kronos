package org.traducao.projeto.traducaoKaraoke.application;

import org.traducao.projeto.legenda.domain.DocumentoLegenda;
import org.traducao.projeto.legenda.domain.EventoLegenda;
import org.traducao.projeto.traducaoKaraoke.domain.ClasseLinhaKaraoke;
import org.traducao.projeto.traducaoKaraoke.domain.SinaisDeKaraoke;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * PROPÓSITO DE NEGÓCIO: decide a classe de TODOS os eventos de um arquivo de legenda de uma vez
 * só, e passa a ser o único lugar que sabe ler do ASS as duas evidências externas de karaokê —
 * o campo {@code Effect} e a existência de camada romaji no mesmo instante.
 *
 * <h2>O desperdício que originou, medido</h2>
 * {@code TraduzirKaraokeUseCase} percorria o documento DUAS vezes e chamava
 * {@code classificar} nas duas — uma no pré-passe que descobre os instantes com romaji, outra no
 * laço que emite os eventos. No acervo isso é <b>3,95 milhões de classificações onde 1,98 milhão
 * basta</b>. Nenhum JIT conserta trabalho feito duas vezes de propósito.
 *
 * <p>E o ganho maior nem é esse: com a decisão isolada aqui, mexer no critério de música deixa de
 * exigir tocar num método que também grava cache e escreve arquivo.
 *
 * <h2>A ORDEM dos dois passes, que não é arbitrária</h2>
 * O pré-passe classifica com <b>apenas</b> o campo {@code Effect} — nunca com a evidência de
 * "romaji no mesmo instante". Se ele usasse, a regra se alimentaria da própria conclusão e uma
 * linha puxaria a vizinha para dentro da música em cascata. Só o passe final recebe as duas.
 *
 * <h2>Invariantes do domínio</h2>
 * <ul>
 *   <li>A classe é indexada por POSIÇÃO no documento, não por igualdade de evento: duas linhas
 *       idênticas em instantes diferentes são eventos diferentes, e karaokê é cheio delas — no
 *       86 a mesma frase aparece 650 vezes.</li>
 *   <li>Evento que não é diálogo ou não tem texto é {@link ClasseLinhaKaraoke#FORA_DE_MUSICA},
 *       sem consultar o classificador.</li>
 *   <li>É imutável depois de montado. Quem consulta não pode mudar a decisão.</li>
 * </ul>
 *
 * <h2>Comportamento em caso de falha</h2>
 * Índice fora da faixa devolve {@code FORA_DE_MUSICA} — o lado que não traduz e não altera.
 * Prefixo malformado devolve evidência ausente, que é o lado restritivo. Nunca lança.
 */
public final class PlanoDeClassificacao {

    /**
     * Folga em centésimos nos DOIS lados do casamento de instante entre o pedaço e a frase: o
     * fill de karaokê adianta e atrasa a linha por frações (medido no OPL2 do Unicorn — 0,15s no
     * início, 0,10s no fim). Um só valor para os dois lados, porque a assimetria era o defeito.
     */
    private static final long FOLGA_INSTANTE_CS = 100;

    /**
     * Folga, em centésimos, para duas camadas serem do MESMO momento da música — em cada ponta da
     * janela (início e fim), separadamente.
     *
     * <h2>O prejuízo que originou, medido em 24/09/2026</h2>
     * O pareamento era por instante EXATO. Na abertura do 86 E01 a camada romaji do verso termina
     * em 22:54.48 e a inglesa em 22:54.60 — 12 centésimos —, então a inglesa "não tinha original
     * preservada", empilhava {@code inglês\Nportuguês} e a tela mostrava romaji + inglês + português.
     * Um verso dura segundos; dois versos DIFERENTES nunca têm as duas pontas a menos de meio segundo.
     */
    static final long FOLGA_PAR_DE_CAMADAS_CS = 50;

    private final List<ClasseLinhaKaraoke> classePorPosicao;
    /** Janelas [início, fim] em centésimos das camadas com a letra ORIGINAL preservada. */
    private final List<long[]> janelasComOriginalPreservada;
    /**
     * Posição da FRASE inteira → posições das sílabas/palavras do KFX que a pintam por cima. Só
     * entra frase cujos pedaços reconstroem o texto dela (ver {@link #posicoesDeSilaba}).
     */
    private final Map<Integer, List<Integer>> silabasPorFrase;
    /** Posições traduzíveis com a letra original acesa em SÍLABAS no meio delas (ver {@link #originalEmSilabas}). */
    private final Set<Integer> comOriginalEmSilabas;

    /**
     * Mínimo de sílabas distintas acesas para contar como letra cantada: com menos, uma sílaba solta
     * de outra camada decidiria sozinha.
     */
    static final int MINIMO_SILABAS_DO_ORIGINAL = 4;
    /** O limiar de romaji do detector do peer legenda (70), aplicado às sílabas acesas juntas. */
    static final int LIMIAR_ROMAJI_DAS_SILABAS = 70;

    private PlanoDeClassificacao(List<ClasseLinhaKaraoke> classePorPosicao,
                                 List<long[]> janelasComOriginalPreservada,
                                 Map<Integer, List<Integer>> silabasPorFrase,
                                 Set<Integer> comOriginalEmSilabas) {
        this.classePorPosicao = List.copyOf(classePorPosicao);
        this.janelasComOriginalPreservada = List.copyOf(janelasComOriginalPreservada);
        this.silabasPorFrase = Map.copyOf(silabasPorFrase);
        this.comOriginalEmSilabas = Set.copyOf(comOriginalEmSilabas);
    }

    /**
     * PROPÓSITO DE NEGÓCIO: monta o plano do arquivo inteiro — dois passes, uma decisão por
     * evento.
     *
     * <p>INVARIANTES DO DOMÍNIO: o pré-passe usa SÓ o campo {@code Effect}; o passe final usa
     * {@code Effect} mais o pareamento por instante. Ver a nota de ORDEM no topo da classe.
     *
     * <p>COMPORTAMENTO EM CASO DE FALHA: documento nulo devolve um plano vazio, que responde
     * {@code FORA_DE_MUSICA} para tudo.
     */
    public static PlanoDeClassificacao montar(DocumentoLegenda documento,
                                              ClassificadorLetraKaraokeService classificador) {
        return montar(documento, classificador, null);
    }

    /**
     * PROPÓSITO DE NEGÓCIO: o mesmo plano, agora aplicando a regra de Paulo de 24/09/2026 para a
     * camada ORIGINAL: <i>"a linha de cima mistura inglês e japonês e a de baixo é a que o texto
     * todo é inglês da música e vira português"</i>.
     *
     * <h2>O que muda</h2>
     * Uma linha que o classificador chamou de ORIGINAL (pelo NOME do estilo — {@code Song JP},
     * {@code JP Song}, {@code ED-ROM} — ou pelo desempate silábico) mas cujo texto é todo inglês
     * passa a ser traduzida. Sem romaji irmão no instante ela é a letra cantada e empilha
     * {@code inglês\Nportuguês} (Zeta "I wanna have a pure time!", 0083 "Men of destiny!"); com
     * romaji irmão ela é a tradução do fansub posta no estilo JP e é trocada no lugar (08th "I was
     * watching you as you were watching the sun rise."). Linha que MISTURA as duas línguas
     * ("Kagayaku my history", "Stay together sono toki") continua intacta.
     *
     * <h2>"Todo inglês", medido com os três dicionários</h2>
     * Toda palavra reconhecida pelo português ou pelo inglês E (ao menos DUAS que só o inglês
     * reconhece, OU todas elas só-inglesas — o verso de uma palavra "Dreamer...", "One,": 5 de 21
     * linhas de uma palavra no acervo, todas inglês). O rótulo ROMAJI não serve de prova: o {@code ja_ROMAJI} deixa {@code wa},
     * {@code ga}, {@code shitemo}, {@code kawaranai} como DESCONHECIDA — então palavra que nenhum
     * dos dois reconhece é tratada como possível japonês e barra a conversão. O piso de duas é o que
     * barra "ai suru anata ni sou yo", em que o pt_BR aceita todas menos "yo". Medido no acervo em
     * 24/09/2026: 523 linhas originais distintas, 105 convertidas, todas inglês na leitura, zero
     * romaji. LIMITAÇÃO DECLARADA (A8): o piso foi escolhido olhando esse mesmo acervo.
     *
     * <p>INVARIANTES DO DOMÍNIO: sem corretor, ou com o dicionário inglês fora do ar, NADA muda —
     * o plano sai idêntico ao da versão sem este parâmetro (falha fechada). A linha convertida sai
     * das janelas de "original preservada", senão acharia a SI MESMA como irmã e trocaria o inglês
     * cantado em vez de empilhar.
     *
     * <p>COMPORTAMENTO EM CASO DE FALHA: erro do corretor devolve o plano sem a conversão.
     */
    public static PlanoDeClassificacao montar(DocumentoLegenda documento,
                                              ClassificadorLetraKaraokeService classificador,
                                              org.traducao.projeto.core.texto.dicionarioOrtografia.CorretorOrtograficoLegenda corretor) {
        if (documento == null || documento.eventos() == null) {
            return new PlanoDeClassificacao(List.of(), List.of(), Map.of(), Set.of());
        }
        List<EventoLegenda> eventos = documento.eventos();

        // As sílabas de fill do KFX são calculadas ANTES do conjunto comRomaji, porque uma
        // sílaba solta engana a deteccao de original neste primeiro passe (que ainda nao tem o
        // sinal silabaDeFraseIrma). Medido em 21/09/2026 no OPL2 do Unicorn: o fragmento "I" de
        // "I know that all the lies..." e de "I wonder how long..." (a letra "i", que casa o
        // padrao de silaba japonesa) saia ORIGINAL_JAPONES aqui e marcava o instante da frase
        // como "tem original preservada" — a frase INGLESA no mesmo instante entao NAO empilhava
        // o ingles original, e a abertura saia so em PT (2 de 16 frases). A original de verdade
        // (romaji do ED) e FRASE, nunca fragmento, entao continua contando: o scar das 22/23
        // linhas do ED single-layer segue protegido.
        Map<Integer, List<Integer>> silabasPorFrase = new HashMap<>();
        Set<Integer> silabas = posicoesDeSilaba(eventos, silabasPorFrase);

        // Duas perguntas diferentes, com réguas diferentes de propósito:
        //  - "há romaji no MESMO instante?" é EVIDÊNCIA DE MÚSICA para linhas cujo estilo não diz
        //    nada. Continua exigindo o instante EXATO. Com folga, fala de DIÁLOGO que coincide com
        //    um verso passava a ser tratada como letra e ia ao LLM — medido em 24/09/2026 no acervo:
        //    "Como posso pilotar o Zeta Gundam..." (ZZ) e "Aina Sakhalin... Ela tem um namorado?"
        //    (08th), as duas em português.
        //  - "já existe a letra original preservada?" decide só EMPILHAR ou não uma linha que JÁ é
        //    música, e aqui a folga é a correção (F6, ver FOLGA_PAR_DE_CAMADAS_CS).
        Set<String> instantesExatosComRomaji = new HashSet<>();
        List<long[]> janelasComRomaji = new ArrayList<>();
        for (int i = 0; i < eventos.size(); i++) {
            EventoLegenda ev = eventos.get(i);
            if (!classificavel(ev) || silabas.contains(i)) {
                continue;
            }
            ClasseLinhaKaraoke previa = classificador.classificar(
                ev.estilo(), ev.texto(), new SinaisDeKaraoke(campoEfeitoDe(ev), false));
            if (previa != ClasseLinhaKaraoke.ORIGINAL_JAPONES) {
                continue;
            }
            instantesExatosComRomaji.add(instanteExato(ev));
            long ini = inicioCs(ev);
            long fim = fimCs(ev);
            if (ini >= 0 && fim >= 0) {
                janelasComRomaji.add(new long[] {ini, fim, i});
            }
        }

        List<ClasseLinhaKaraoke> classes = new ArrayList<>(eventos.size());
        for (int i = 0; i < eventos.size(); i++) {
            EventoLegenda ev = eventos.get(i);
            if (!classificavel(ev)) {
                classes.add(ClasseLinhaKaraoke.FORA_DE_MUSICA);
                continue;
            }
            classes.add(classificador.classificar(ev.estilo(), ev.texto(),
                new SinaisDeKaraoke(campoEfeitoDe(ev), instantesExatosComRomaji.contains(instanteExato(ev)),
                    silabas.contains(i))));
        }

        Set<Integer> todaInglesa = originaisTodaInglesas(eventos, classes, silabas, corretor);
        if (!todaInglesa.isEmpty()) {
            for (int i : todaInglesa) {
                classes.set(i, ClasseLinhaKaraoke.TRADUZIVEL_INGLES);
            }
            janelasComRomaji.removeIf(j -> todaInglesa.contains((int) j[2]));
        }
        return new PlanoDeClassificacao(classes, janelasComRomaji, silabasPorFrase,
            originalEmSilabas(eventos, classes, silabasPorFrase, classificador));
    }

    /**
     * PROPÓSITO DE NEGÓCIO: reconhece a letra ORIGINAL que está na tela desenhada SÍLABA A SÍLABA,
     * sem nenhuma linha inteira — para a tradução inglesa do fansub virar só português no lugar, em
     * vez de empilhar o inglês.
     *
     * <h2>O prejuízo que originou, medido em 12/10/2026</h2>
     * No encerramento do 86 (Partes 1 e 2) o romaji ("Silent haze kasumi gachi ni toraeru kage",
     * "kimi no koto wo kangaeru") é desenhado sílaba por sílaba em três camadas, no alto da tela, e
     * não existe a linha inteira. As janelas de "original preservada" só conhecem linha inteira, e
     * a 4.1 empilhava {@code inglês\Nportuguês}: a tela mostrava romaji + inglês + português — o
     * inglês do fansub que Paulo manda sair ("removemos o inglês e deixamos só o português").
     *
     * <p>INVARIANTES DO DOMÍNIO: olha o MEIO de cada linha traduzível; conta os eventos de efeito
     * acesos ali, tirando os pedaços da PRÓPRIA frase ({@link #silabasDaFrase}) e as cópias de
     * camada (mesmo instante e texto). Com {@value #MINIMO_SILABAS_DO_ORIGINAL} sílabas ou mais e
     * romaji pelo critério do detector ({@value #LIMIAR_ROMAJI_DAS_SILABAS}), o original está na
     * tela. Sílaba inglesa ("Do", "you", "feel", "a", "lone") não chega ao limiar: a letra cantada
     * em inglês, sem romaji, continua empilhando (o encerramento ED2 do Unicorn).
     *
     * <p>COMPORTAMENTO EM CASO DE FALHA: tempo ilegível ou classificador ausente deixam a posição
     * de fora — o lado que empilha, preservando a letra.
     */
    private static Set<Integer> originalEmSilabas(List<EventoLegenda> eventos, List<ClasseLinhaKaraoke> classes,
                                                  Map<Integer, List<Integer>> silabasPorFrase,
                                                  ClassificadorLetraKaraokeService classificador) {
        if (classificador == null) {
            return Set.of();
        }
        List<Integer> efeitos = new ArrayList<>();
        for (int j = 0; j < eventos.size(); j++) {
            if (classes.get(j) == ClasseLinhaKaraoke.EFEITO_KFX && inicioCs(eventos.get(j)) >= 0
                && fimCs(eventos.get(j)) >= 0) {
                efeitos.add(j);
            }
        }
        if (efeitos.isEmpty()) {
            return Set.of();
        }
        efeitos.sort(Comparator.comparingLong((Integer j) -> inicioCs(eventos.get(j))).thenComparingInt(j -> j));
        Set<Integer> resultado = new HashSet<>();
        for (int i = 0; i < eventos.size(); i++) {
            if (classes.get(i) != ClasseLinhaKaraoke.TRADUZIVEL_INGLES) {
                continue;
            }
            long ini = inicioCs(eventos.get(i));
            long fim = fimCs(eventos.get(i));
            if (ini < 0 || fim <= ini) {
                continue;
            }
            long meio = (ini + fim) / 2;
            Set<Integer> daPropriaFrase = new HashSet<>(silabasPorFrase.getOrDefault(i, List.of()));
            Set<String> vistas = new HashSet<>();
            StringBuilder silabas = new StringBuilder();
            int distintas = 0;
            for (int j : efeitos) {
                EventoLegenda ef = eventos.get(j);
                long iniJ = inicioCs(ef);
                if (iniJ > meio) {
                    break;
                }
                if (fimCs(ef) <= meio || daPropriaFrase.contains(j)) {
                    continue;
                }
                String visivel = visivelDe(ef);
                if (visivel.isEmpty() || !vistas.add(iniJ + "|" + fimCs(ef) + "|" + visivel)) {
                    continue;
                }
                silabas.append(visivel).append(' ');
                distintas++;
            }
            if (distintas >= MINIMO_SILABAS_DO_ORIGINAL
                && classificador.proporcaoRomaji(silabas.toString()) >= LIMIAR_ROMAJI_DAS_SILABAS) {
                resultado.add(i);
            }
        }
        return resultado;
    }

    /** Mínimo de palavras que SÓ o inglês reconhece — ver o Javadoc de {@link #montar(DocumentoLegenda, ClassificadorLetraKaraokeService, org.traducao.projeto.core.texto.dicionarioOrtografia.CorretorOrtograficoLegenda)}. */
    static final int MINIMO_PALAVRAS_INGLESAS = 2;

    /**
     * PROPÓSITO DE NEGÓCIO: as posições das linhas ORIGINAL cujo texto é todo inglês (regra de
     * Paulo de 24/09/2026), decididas pelos dicionários numa consulta em LOTE por arquivo.
     *
     * <p>COMPORTAMENTO EM CASO DE FALHA: sem corretor, sem candidata, dicionário inglês fora do ar
     * ou erro na consulta devolvem conjunto vazio — o plano fica como era.
     */
    private static Set<Integer> originaisTodaInglesas(
            List<EventoLegenda> eventos, List<ClasseLinhaKaraoke> classes, Set<Integer> silabas,
            org.traducao.projeto.core.texto.dicionarioOrtografia.CorretorOrtograficoLegenda corretor) {
        if (corretor == null) {
            return Set.of();
        }
        java.util.Map<Integer, List<String>> candidatas = new java.util.LinkedHashMap<>();
        Set<String> todasAsPalavras = new HashSet<>();
        for (int i = 0; i < eventos.size(); i++) {
            if (classes.get(i) != ClasseLinhaKaraoke.ORIGINAL_JAPONES || silabas.contains(i)) {
                continue;
            }
            String visivel = visivelDe(eventos.get(i));
            if (ClassificadorLetraKaraokeService.temEscritaJaponesa(visivel)) {
                continue;
            }
            // Tokenização do DONO do dicionário (core) — a mesma do achatador, sem cópia.
            List<String> palavras =
                org.traducao.projeto.core.texto.dicionarioOrtografia.CorretorOrtograficoLegenda.palavrasDe(visivel);
            if (!palavras.isEmpty()) {
                candidatas.put(i, palavras);
                todasAsPalavras.addAll(palavras);
            }
        }
        if (candidatas.isEmpty()) {
            return Set.of();
        }
        java.util.Map<String, org.traducao.projeto.core.texto.dicionarioOrtografia.VeredictoPalavra> veredicto;
        try {
            veredicto = corretor.classificarPalavras(todasAsPalavras);
        } catch (RuntimeException e) {
            return Set.of();
        }
        if (!corretor.inglesDisponivel()) {
            return Set.of();
        }
        Set<Integer> todaInglesa = new HashSet<>();
        for (java.util.Map.Entry<Integer, List<String>> c : candidatas.entrySet()) {
            int soIngles = 0;
            boolean tudoReconhecido = true;
            for (String p : c.getValue()) {
                var v = veredicto.get(p);
                if (v == org.traducao.projeto.core.texto.dicionarioOrtografia.VeredictoPalavra.RESIDUO_INGLES) {
                    soIngles++;
                } else if (v != org.traducao.projeto.core.texto.dicionarioOrtografia.VeredictoPalavra.PORTUGUES_OK
                    && v != org.traducao.projeto.core.texto.dicionarioOrtografia.VeredictoPalavra.ACENTO_FALTANDO) {
                    tudoReconhecido = false; // desconhecida/romaji/outro idioma: pode ser japonês
                    break;
                }
            }
            // ">= 2 so-inglesas" OU "TODAS so-inglesas": a segunda cobre o verso de uma palavra
            // ("Dreamer...", "Evergreen...", "One,") — medido no acervo: 5 de 21 linhas de uma
            // palavra, todas ingles; romaji isolado sai DESCONHECIDA e nunca chega aqui.
            if (tudoReconhecido && (soIngles >= MINIMO_PALAVRAS_INGLESAS || soIngles == c.getValue().size())) {
                todaInglesa.add(c.getKey());
            }
        }
        return todaInglesa;
    }

    /**
     * {@code início,fim} do prefixo, como TEXTO — a chave da evidência de música por camada romaji
     * simultânea. NÃO usar o prefixo inteiro: ele carrega o ESTILO, e camadas irmãs têm estilos
     * diferentes por definição. Prefixo ilegível devolve vazio (sem irmã, lado que preserva).
     */
    private static String instanteExato(EventoLegenda evento) {
        if (evento == null || evento.prefixo() == null) {
            return "";
        }
        String[] campos = evento.prefixo().split(",");
        return campos.length >= 3 ? campos[1] + "," + campos[2] : "";
    }

    /**
     * PROPÓSITO DE NEGÓCIO: há uma camada original na tela durante TODO este evento? Responde de
     * duas formas, e as duas significam "a letra original já aparece, não empilhe":
     * <ul>
     *   <li><b>mesmo momento</b> — início e fim, cada um dentro de {@link #FOLGA_PAR_DE_CAMADAS_CS}
     *       (o F6 de 24/09/2026);</li>
     *   <li><b>fatia</b> — o evento cabe INTEIRO dentro da janela da camada original, com a mesma
     *       folga em cada ponta. Guilty Crown, OP_S2, 09/10/2026: o fansub partiu "that your eyes
     *       were given to you to acknowledge others," em CINCO eventos curtos (o efeito de pulsar)
     *       dentro de UM romaji de 5,4 s. Pelas pontas nenhuma fatia casava, e as cinco empilhariam
     *       o inglês sobre o português com o romaji já na tela — três camadas, o defeito do F6.</li>
     * </ul>
     *
     * <p>INVARIANTES DO DOMÍNIO: evento que só ENCOSTA na janela (começa antes ou termina depois
     * dela além da folga) continua sem irmã — a original não cobre o evento inteiro, e trocar no
     * lugar apagaria a letra da tela no trecho descoberto. É o lado do prejuízo do Unicorn (22 de
     * 23 linhas sumindo), e por isso é o lado que a dúvida escolhe.
     *
     * <p>COMPORTAMENTO EM CASO DE FALHA: prefixo ilegível devolve {@code false} — o evento é
     * tratado como sem irmã, o lado que PRESERVA a original (empilha).
     */
    private static boolean temJanelaDoMesmoMomento(List<long[]> janelas, EventoLegenda evento) {
        long ini = inicioCs(evento);
        long fim = fimCs(evento);
        if (ini < 0 || fim < 0) {
            return false;
        }
        for (long[] j : janelas) {
            boolean mesmoMomento = Math.abs(j[0] - ini) <= FOLGA_PAR_DE_CAMADAS_CS
                && Math.abs(j[1] - fim) <= FOLGA_PAR_DE_CAMADAS_CS;
            boolean fatia = ini >= j[0] - FOLGA_PAR_DE_CAMADAS_CS
                && fim <= j[1] + FOLGA_PAR_DE_CAMADAS_CS;
            if (mesmoMomento || fatia) {
                return true;
            }
        }
        return false;
    }

    /** A decisão já tomada para o evento naquela posição do documento. */
    public ClasseLinhaKaraoke classeNaPosicao(int posicao) {
        if (posicao < 0 || posicao >= classePorPosicao.size()) {
            return ClasseLinhaKaraoke.FORA_DE_MUSICA;
        }
        return classePorPosicao.get(posicao);
    }

    /**
     * PROPÓSITO DE NEGÓCIO: já existe uma camada com a letra ORIGINAL neste instante?
     *
     * <p>É a pergunta que decide se a tradução precisa empilhar a original com {@code \N}. O
     * prejuízo de errar está medido: nos episódios 13-22 do Unicorn o encerramento tem UMA
     * camada só, e traduzir sem empilhar apagava a letra da tela — 22 de 23 linhas, em 10
     * episódios.
     */
    public boolean temOriginalPreservadaNoInstante(EventoLegenda evento) {
        return evento != null && temJanelaDoMesmoMomento(janelasComOriginalPreservada, evento);
    }

    /**
     * PROPÓSITO DE NEGÓCIO: as sílabas/palavras do KFX que pintam ESTA frase por cima, no mesmo
     * lugar da tela.
     *
     * <h2>O prejuízo que originou, medido em 12/10/2026</h2>
     * Na abertura do Unicorn ({@code OPL2}) a frase "Do you feel alone" e as palavras "Do", "you",
     * "feel", "a", "lone" são desenhadas em {@code \pos(960,1050)}. Empilhar
     * {@code inglês\Nportuguês} na frase fazia o português ocupar a altura 1050 e as palavras
     * continuarem lá, escrevendo o inglês EM CIMA do português — visto na TV (Kodi) e no PC
     * (MPC-HC e libass), 336 frases em 21 dos 22 episódios.
     *
     * <p>INVARIANTES DO DOMÍNIO: lista vazia quando a frase não tem pedaços que a reconstroem; a
     * resposta é por POSIÇÃO no documento, como o resto do plano.
     *
     * <p>COMPORTAMENTO EM CASO DE FALHA: posição desconhecida devolve lista vazia — o lado que não
     * silencia nada.
     */
    public List<Integer> silabasDaFrase(int posicao) {
        return silabasPorFrase.getOrDefault(posicao, List.of());
    }

    /**
     * PROPÓSITO DE NEGÓCIO: a letra original está na tela, desenhada sílaba a sílaba, no meio desta
     * linha traduzível? Se está, a tradução do fansub vira só português — o caso do encerramento do
     * 86, 12/10/2026 (ver {@link #originalEmSilabas}).
     *
     * <p>INVARIANTES DO DOMÍNIO: resposta por POSIÇÃO, como o resto do plano.
     *
     * <p>COMPORTAMENTO EM CASO DE FALHA: posição desconhecida devolve {@code false} — o lado que
     * empilha e preserva a letra.
     */
    public boolean temOriginalEmSilabasNaPosicao(int posicao) {
        return comOriginalEmSilabas.contains(posicao);
    }

    /** Quantos eventos o plano decidiu — para o resumo por arquivo. */
    public int total() {
        return classePorPosicao.size();
    }

    private static boolean classificavel(EventoLegenda ev) {
        return ev != null && ev.isDialogo() && ev.temTexto();
    }

    /**
     * PROPÓSITO DE NEGÓCIO: acha as linhas que são PEDAÇO de uma frase que também está no
     * arquivo — karaokê pintado sílaba a sílaba com a letra inteira desenhada por cima.
     *
     * <h2>O prejuízo que originou</h2>
     * Ver {@link SinaisDeKaraoke#silabaDeFraseIrma()}: no {@code OPL2} do Unicorn as duas
     * camadas convivem, as duas iam ao LLM, e 78 dos 131 textos distintos traduzidos eram
     * fragmento — {@code cant} virou "Cantar.".
     *
     * <h2>O critério, e as DUAS armadilhas que ele já tropeçou</h2>
     * Os pedaços irmãos, ordenados por instante, têm de CONCATENAR exatamente no texto da frase
     * (normalizado para letra e dígito). {@code Do|you|feel|a|lone} reconstrói
     * {@code doyoufeelalone}; {@code i|to} não reconstrói nada.
     * <ul>
     *   <li><b>Substring não é evidência.</b> A primeira versão só exigia que o fragmento
     *       aparecesse na frase. Medido: trocando o texto pela letra {@code a}, 2.752 dos 2.835
     *       continuavam cobertos — e no 86 Part 2 os 875 "cobertos" eram a letra {@code i}.</li>
     *   <li><b>Cópia de tipografia reconstrói a si mesma.</b> A segunda versão marcou 75,6% do
     *       Zeta, incluindo as frases inteiras de {@code Song JP}, porque a mesma linha desenhada
     *       duas vezes "reconstruía" a frase sozinha. Por isso todo pedaço precisa ter MENOS
     *       palavras que a frase (a cópia da frase inteira tem o MESMO número e é barrada), e
     *       precisa haver pelo menos dois pedaços.</li>
     * </ul>
     *
     * <p><b>Correção de 2026-09-21:</b> o corte era "MENOS de DUAS palavras" e abortava o grupo
     * inteiro ao topar um pedaço de duas palavras — no {@code OPL2} do Unicorn o pedaço
     * {@code "you are"} derrubava os 9 fragmentos de <i>"If you are holding holding onto fear"</i>,
     * que iam ao LLM e viravam lixo ({@code hol}→"Olá", {@code on}→"começando", {@code dnt}→
     * meta-resposta). O corte passou a ser "MENOS palavras que a frase", que barra a cópia da
     * frase inteira (Zeta) e admite o pedaço de duas palavras (Unicorn). A concatenação exata
     * continua sendo a prova.
     *
     * <p>Efeito medido com o critério final: Unicorn 2.562 de 3.280 fragmentos (78,1%), e
     * <b>ZERO</b> no Zeta, no 86 Part 1 e no 86 Part 2 — nenhuma obra saudável é tocada.
     *
     * <p>INVARIANTES DO DOMÍNIO: a comparação é dentro do MESMO estilo. Camadas irmãs têm estilos
     * diferentes por definição, e cruzá-las faria a letra em inglês "reconstruir" o romaji. Cada
     * frase que se reconstrói deixa em {@code silabasPorFrase} QUAIS pedaços são os dela — é o que
     * permite silenciar exatamente esses pedaços quando a frase vira português (12/10/2026).
     *
     * <p>COMPORTAMENTO EM CASO DE FALHA: prefixo ilegível devolve tempo negativo e o evento fica
     * de fora — o lado que NÃO veta, preservando o viés de traduzir o que não se entendeu.
     */
    private static Set<Integer> posicoesDeSilaba(List<EventoLegenda> eventos,
                                                 Map<Integer, List<Integer>> silabasPorFrase) {
        // Tempo, texto visível e número de palavras são calculados UMA vez por evento. A versão
        // anterior refazia o parse do prefixo e a limpeza de tags a cada PAR de eventos e comparava
        // cada frase com TODOS os candidatos: O(n²) com string no laço. Medido em 24/09/2026: o
        // arquivo do Char's Counterattack tem 55.983 eventos, e a medição ficou 45 min de CPU presa
        // aqui — na produção isso prenderia a fila ÚNICA do pipeline por horas. Agora as irmãs
        // são buscadas por estilo e pela janela de FIM, com busca binária.
        Set<Integer> silabas = new HashSet<>();
        int n = eventos.size();
        String[] visivel = new String[n];
        long[] ini = new long[n];
        long[] fim = new long[n];
        List<Integer> candidatos = new ArrayList<>();
        java.util.Map<String, List<Integer>> porEstiloOrdemDeFim = new java.util.HashMap<>();
        for (int i = 0; i < n; i++) {
            EventoLegenda ev = eventos.get(i);
            if (!classificavel(ev)) {
                continue;
            }
            visivel[i] = visivelDe(ev);
            if (visivel[i].isEmpty()) {
                continue;
            }
            ini[i] = inicioCs(ev);
            fim[i] = fimCs(ev);
            candidatos.add(i);
            porEstiloOrdemDeFim.computeIfAbsent(ev.estilo(), k -> new ArrayList<>()).add(i); // HashMap aceita chave nula: mesma semantica do Objects.equals antigo
        }
        for (List<Integer> doEstilo : porEstiloOrdemDeFim.values()) {
            doEstilo.sort(Comparator.comparingLong((Integer id) -> fim[id]));
        }
        for (int idFrase : candidatos) {
            String textoFrase = visivel[idFrase];
            int palavrasFrase = palavras(textoFrase);
            if (palavrasFrase < 2) {
                continue;
            }
            long iniFrase = ini[idFrase];
            long fimFrase = fim[idFrase];
            if (iniFrase < 0 || fimFrase < 0) {
                continue;
            }
            List<Integer> doEstilo = porEstiloOrdemDeFim.get(eventos.get(idFrase).estilo());
            List<Integer> irmas = new ArrayList<>();
                // A âncora do pareamento é o FIM, não o início. Todo pedaço do fill de karaokê
                // termina JUNTO com a frase (fica aceso até a linha acabar), então casar pelo fim é
                // o que reconhece o pedaço e, ao mesmo tempo, exclui o fill da frase VIZINHA.
                // Medido em 21/09/2026 no OPL2 do Unicorn: em "We didnt see all its meaning" o
                // primeiro pedaço ("We") começa 0,15s ANTES da frase (39.38 < 39.53) — ancorar no
                // INÍCIO o excluía e os 8 fragmentos vazavam (dnt=>"Nao ha contexto", all=>"Eu sou a
                // Audrey"). Mas ancorar no início com folga puxava o ÚLTIMO pedaço da frase anterior
                // e poluía a reconstrução — medido, o leak subiu de 6 para 32. Pelo fim, "We"
                // termina com a frase (44.79 ≈ 44.69, dentro da folga) e é incluído, enquanto
                // "vive" (fim 39.23, o fill da frase anterior) fica a 5s do fim desta e é excluído.
                // A folga de 1s absorve os centésimos de diferença entre o held e o fill.
            int k = primeiroComFimAPartirDe(doEstilo, fim, fimFrase - FOLGA_INSTANTE_CS);
            for (; k < doEstilo.size() && fim[doEstilo.get(k)] <= fimFrase + FOLGA_INSTANTE_CS; k++) {
                int idIrma = doEstilo.get(k);
                if (idIrma != idFrase && ini[idIrma] <= fimFrase) {
                    irmas.add(idIrma);
                }
            }
            if (irmas.size() < 2) {
                continue;
            }
            // Desempate pela posição no documento: é a ordem que a versão O(n²) produzia (ela
            // coletava na ordem do arquivo e o sort é estável).
            irmas.sort(Comparator.comparingLong((Integer id) -> ini[id])
                .thenComparingLong(id -> fim[id])
                .thenComparingInt(id -> id));

            StringBuilder reconstruido = new StringBuilder();
            Set<String> jaVistos = new HashSet<>();
            int pedacos = 0;
            boolean algumEhFrase = false;
            for (int id : irmas) {
                String visivelIrma = visivel[id];
                // O corte é "MENOS palavras que a frase", não "< 2 palavras". O fansub divide a
                // letra em pedaços que às vezes têm duas palavras ("you are" no OPL2 do Unicorn),
                // e o corte antigo abortava o grupo inteiro ao encontrá-los — os 9 pedaços de
                // "If you are holding holding onto fear" ficavam sem veto e iam ao LLM como
                // fragmento (medido 21/09/2026: hol=>"Olá", on=>"começando", dnt=>meta-resposta).
                // O que o corte precisa barrar é a CÓPIA DE TIPOGRAFIA — a frase inteira desenhada
                // duas vezes, que "reconstruía a si mesma" (Zeta Song JP): essa tem o MESMO número
                // de palavras da frase, então >= palavrasFrase continua abortando-a. A prova forte
                // segue sendo a concatenação exata abaixo, não o número de palavras do pedaço.
                if (palavras(visivelIrma) >= palavrasFrase) {
                    algumEhFrase = true;
                    break;
                }
                String chave = ini[id] + "|" + fim[id] + "|" + visivelIrma;
                if (!jaVistos.add(chave)) {
                    continue; // cópia de tipografia: mesmo texto, mesmo instante
                }
                reconstruido.append(visivelIrma);
                pedacos++;
            }
            if (algumEhFrase || pedacos < 2) {
                continue;
            }
            if (normalizar(reconstruido.toString()).equals(normalizar(textoFrase))) {
                silabas.addAll(irmas);
                silabasPorFrase.put(idFrase, List.copyOf(irmas));
            }
        }
        return silabas;
    }

    /** Primeira posição da lista (ordenada por fim) cujo fim é >= {@code limite}. */
    private static int primeiroComFimAPartirDe(List<Integer> ordemDeFim, long[] fim, long limite) {
        int lo = 0;
        int hi = ordemDeFim.size();
        while (lo < hi) {
            int meio = (lo + hi) >>> 1;
            if (fim[ordemDeFim.get(meio)] < limite) {
                lo = meio + 1;
            } else {
                hi = meio;
            }
        }
        return lo;
    }

    private static String visivelDe(EventoLegenda ev) {
        String texto = ev.texto();
        if (texto == null) {
            return "";
        }
        return ClassificadorLetraKaraokeService.extrairTextoVisivel(texto).trim();
    }

    private static int palavras(String texto) {
        if (texto.isBlank()) {
            return 0;
        }
        return texto.trim().split("\\s+").length;
    }

    private static String normalizar(String texto) {
        return texto.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "");
    }

    /** {@code h:mm:ss.cc} do prefixo em centésimos; -1 quando ilegível. */
    private static long inicioCs(EventoLegenda ev) {
        return centesimos(campoDoPrefixo(ev, 1));
    }

    private static long fimCs(EventoLegenda ev) {
        return centesimos(campoDoPrefixo(ev, 2));
    }

    private static String campoDoPrefixo(EventoLegenda ev, int indice) {
        if (ev == null || ev.prefixo() == null) {
            return null;
        }
        String[] campos = ev.prefixo().split(",", -1);
        return campos.length > indice ? campos[indice].trim() : null;
    }

    private static long centesimos(String tempo) {
        if (tempo == null || tempo.isBlank()) {
            return -1;
        }
        String[] partes = tempo.split(":");
        if (partes.length != 3) {
            return -1;
        }
        try {
            long h = Long.parseLong(partes[0].trim());
            long m = Long.parseLong(partes[1].trim());
            double s = Double.parseDouble(partes[2].trim().replace(',', '.'));
            return h * 360000L + m * 6000L + Math.round(s * 100);
        } catch (NumberFormatException e) {
            return -1;
        }
    }


    /**
     * PROPÓSITO DE NEGÓCIO: o campo {@code Effect} da linha {@code Dialogue:} — o carimbo que o
     * Kara Templater do Aegisub deixa nas linhas que ELE gera.
     *
     * <p>INVARIANTES DO DOMÍNIO: o formato é
     * {@code Layer,Start,End,Style,Name,MarginL,MarginR,MarginV,Effect}, e o campo é o NONO. O
     * {@code split} usa limite negativo porque campo vazio no meio é o caso NORMAL — sem ele o
     * Java descarta os vazios do fim e o índice escorrega.
     *
     * <p>COMPORTAMENTO EM CASO DE FALHA: prefixo nulo ou com menos de nove campos devolve
     * {@code null}, que {@code SinaisDeKaraoke} lê como "sem evidência".
     */
    static String campoEfeitoDe(EventoLegenda evento) {
        if (evento == null || evento.prefixo() == null) {
            return null;
        }
        String[] campos = evento.prefixo().split(",", -1);
        return campos.length >= 9 ? campos[8].trim() : null;
    }
}
