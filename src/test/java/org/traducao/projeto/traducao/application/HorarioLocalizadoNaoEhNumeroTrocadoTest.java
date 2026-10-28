package org.traducao.projeto.traducao.application;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import org.traducao.projeto.qualidadeTraducao.application.LoreAtivaFake;

/**
 * PROPÓSITO DE NEGÓCIO: garante que localizar um horário não seja confundido com trocar um
 * identificador numérico — e que trocar um identificador continue sendo recusado.
 *
 * <h2>O prejuízo que originou</h2>
 * Retradução completa de 2026-08-21, 184 arquivos. Das 12 recusas desta guarda, DEZ eram
 * tradução correta:
 * <pre>
 *   We'll land at 1500 hours, as planned.  ->  ...às 15h00...        RECUSADA
 *   The mission begins at 2200.            ->  ...às 22h.            RECUSADA
 *   At approximately 3:40 pm,              ->  Às 15:40,             RECUSADA
 * </pre>
 * O efeito é o pior possível: recusada a tradução, a fala inteira volta para o INGLÊS na
 * legenda final. Oito das dez são horário militar e duas são conversão de 12h para 24h.
 *
 * <h2>Invariantes do domínio</h2>
 * <ul>
 *   <li>O desconto é ARITMÉTICO e conferido contra os valores que a tradução realmente tem:
 *       1500 só é explicado por 15 e 00, e 3 só por 15 quando havia {@code pm}.</li>
 *   <li>A marca textual é obrigatória. Sem {@code hours} no original, {@code 1500} continua
 *       sendo identificador comum e sua troca continua reprovando.</li>
 *   <li>A cicatriz que criou a guarda — {@code "04th Team!"} publicado como {@code "Equipe
 *       08!"} — continua sendo recusada.</li>
 * </ul>
 *
 * <h2>Comportamento em caso de falha</h2>
 * Reprovar num caso doente devolve inglês à legenda. Reprovar num caso-controle é pior: a
 * guarda passou a liberar substituição de número, que é o dano que ela existe para impedir.
 */
@DisplayName("horário localizado não é número trocado")
class HorarioLocalizadoNaoEhNumeroTrocadoTest {

    private final VerificadorIdentificadorNumerico verificador = new VerificadorIdentificadorNumerico(LoreAtivaFake.vazia());

    @Test
    @DisplayName("CASO DOENTE: os horários militares reais de 21/08 passam a ser aceitos")
    void horarioMilitarEhAceito() {
        assertNull(verificador.divergencia(
            "We'll land at 1500 hours, as planned.", "Vamos pousar às 15h00, como planejado."));
        assertNull(verificador.divergencia("1530 hours... Here we go.", "15h30... Lá vamos nós."));
        assertNull(verificador.divergencia(
            "We leave at 1600 hours tomorrow. That is all.", "Partimos às 16h00 amanhã. É tudo."));
        assertNull(verificador.divergencia(
            "Today, at 2100 hours, the Albion will set out...",
            "Hoje, às 21h00, o Albion partirá..."));
        assertNull(verificador.divergencia(
            "Passengers of the Colloid, departing for Hong Kong at 1620 hours,",
            "Passageiros do Colloid, partindo para Hong Kong às 16h20,"));
    }

    @Test
    @DisplayName("CASO DOENTE: a conversão de 12h para 24h passa a ser aceita")
    void conversaoDe12ParaVinteQuatroEhAceita() {
        assertNull(verificador.divergencia("At approximately 3:40 pm,", "Por volta das 15:40,"));
    }

    /** O CASO-CONTROLE. Sem ele a correção viraria licença para trocar número. */
    @Test
    @DisplayName("CASO SÃO: a cicatriz original continua recusada")
    void trocaDeIdentificadorContinuaRecusada() {
        assertNotNull(verificador.divergencia("04th Team!", "Equipe 08!"),
            "a troca que criou esta guarda voltou a passar");
        assertNotNull(verificador.divergencia(
                "Sir. It keeps repeating that they'll show us in 5000.",
                "Senhor. Continua repetindo que nos mostrarão em 5."),
            "5000 virando 5 e uma corrupcao real, nao localizacao de horario");
        assertNotNull(verificador.divergencia(
                "Combined Mechanized Battalion. 08th Mobile Suit Team.",
                "Batalhão Mecanizado Combinado. 8 Time de Mobile Suit."),
            "08th e a designacao oficial da unidade; perder o zero muda o nome");
    }

    /**
     * A marca textual é o que segura a regra. Sem {@code hours}, o mesmo 1500 é identificador
     * comum — altitude, frequência, designação — e trocá-lo continua sendo defeito.
     */
    @Test
    @DisplayName("CASO SÃO: sem a palavra 'hours', 1500 continua sendo identificador comum")
    void semAMarcaTextualNaoDesconta() {
        // "15 00" com ESPACO nao serve de controle: espaco entre digitos e separador de
        // milhar e a traducao volta a valer 1500 — nada sumiria, e a regra nova nem seria
        // alcancada. Foi assim que a primeira versao deste teste reprovou por defeito PROPRIO.
        // Com "15h00" os valores sao 15 e 00 de verdade, e o desconto so nao acontece porque
        // falta a palavra "hours" no original, que e exatamente o que se quer provar.
        assertNotNull(verificador.divergencia("Altitude 1500, holding.", "Altitude 15h00, mantendo."),
            "sem 'hours' no original a guarda nao pode descontar nada");
    }

    /**
     * 28/10/2026: a regra conferia DÍGITOS, não o instante. Recusava o horário brasileiro sem os
     * minutos e o de um dígito, e lia {@code 16.20 hours} como decimal — a fala do 0083 ficou em
     * inglês em 18/09 e 09/10.
     */
    @Test
    @DisplayName("CASO DOENTE (28/10): 15h, 8h00, 13h e 16.20 hours passam a ser aceitos")
    void horaSemMinutoUmDigitoEPonto() {
        assertNull(verificador.divergencia("We'll land at 1500 hours.", "Vamos pousar às 15h."));
        assertNull(verificador.divergencia("We'll land at 1500 hours.", "Vamos pousar às 15 horas."));
        assertNull(verificador.divergencia("Launch at 0800 hours.", "Lançamento às 8h00."));
        assertNull(verificador.divergencia("From 1:00PM today, the hangar is closed.",
            "A partir das 13h de hoje, o hangar fica fechado."));
        assertNull(verificador.divergencia(
            "Passengers of the Colloid, departing for Hong Kong at 16.20 hours,",
            "Passageiros do Colloid, partindo para Hong Kong às 16h20,"));
    }

    /** As mesmas formas, com o INSTANTE trocado: tem de continuar reprovando. */
    @Test
    @DisplayName("CASO SÃO (28/10): hora trocada e número fora da hora continuam reprovando")
    void horaTrocadaContinuaReprovando() {
        assertNotNull(verificador.divergencia("We'll land at 1500 hours.", "Vamos pousar às 16h."));
        assertNotNull(verificador.divergencia("Launch at 0800 hours.", "Lançamento às 18h00."));
        assertNotNull(verificador.divergencia("at 16.20 hours,", "às 16h02,"));
        assertNotNull(verificador.divergencia("The tank holds 16.20 liters.", "O tanque tem 16h20 litros."),
            "sem 'hours', 16.20 é decimal e nao pode ser absolvido como hora");
        assertNotNull(verificador.divergencia("Unit 5, move out at 3 pm.", "Unidade 17, saiam às 15h."),
            "o pm absolve so o numero colado nele; o 5 da unidade continua sob a guarda");
        assertNull(verificador.divergencia("Unit 5, move out at 3 pm.", "Unidade 5, saiam às 15h."),
            "CONTROLE: com o 5 preservado, a hora convertida passa");
    }
}
