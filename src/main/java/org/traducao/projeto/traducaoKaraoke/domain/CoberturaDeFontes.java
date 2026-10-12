package org.traducao.projeto.traducaoKaraoke.domain;

import java.util.Optional;

/**
 * PROPÓSITO DE NEGÓCIO: responde, para as fontes que viajam DENTRO do vídeo, quais caracteres de
 * um texto cada uma não sabe desenhar.
 *
 * <h2>O prejuízo que originou, medido em 12/10/2026</h2>
 * A fonte da abertura do Unicorn (Dash Horizon) não tem {@code á à â ã é ê í ó ô õ ú ç} nem
 * {@code ? ! . ,}. Com a letra em português, o player trocava cada um desses caracteres por uma
 * fonte de reserva — na TV o "ê" de "Você" saía como um "Ê" solto acima da palavra. O mesmo na
 * Conformity do encerramento do 86 Parte 2 ({@code ã é í}) e na fonte da música do F91 ({@code ã}).
 *
 * <p>INVARIANTES DO DOMÍNIO: três respostas, nunca duas — {@code Optional.empty()} quando a
 * família NÃO está entre as fontes lidas (não verificado), texto vazio quando ela desenha tudo, e
 * os caracteres que faltam quando não desenha. Espaço em branco não conta.
 *
 * <p>COMPORTAMENTO EM CASO DE FALHA: família ou texto nulos são "não verificado"; nunca lança.
 */
public interface CoberturaDeFontes {

    Optional<String> caracteresSemDesenho(String familia, String texto);

    /** Nenhuma fonte lida: toda família responde "não verificado". */
    static CoberturaDeFontes nenhuma() {
        return (familia, texto) -> Optional.empty();
    }
}
