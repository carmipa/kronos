package org.traducao.projeto.vitrine.presentation.web;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.traducao.projeto.core.vitrine.ModoVitrine;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * PROPÓSITO DE NEGÓCIO: o outro lado do controle da vitrine. Com ela desligada (o padrão, que é
 * o KRONOS da máquina do Paulo), as mesmas rotas que o {@code GuardaVitrineHttpTest} vê
 * recusadas respondem de verdade — então o 403 de lá vem da guarda, e não de a rota ou o arquivo
 * não existirem ("passou pelo motivo certo", cicatriz do binmapper em 02/09/2026).
 *
 * <p>INVARIANTES DO DOMÍNIO: só usa rotas inofensivas (consulta de capacidade, arquivo de teste,
 * parar uma fila vazia); nenhuma escreve no acervo.
 *
 * <p>COMPORTAMENTO EM CASO DE FALHA: se a vitrine vazar para o perfil normal (estado estático),
 * o cabeçalho aparece aqui e o teste reprova.
 */
@QuarkusTest
class GuardaVitrineDesligadaTest {

    @Test
    @DisplayName("desligada: a rota real responde e não há cabeçalho da vitrine")
    void rotaRealResponde() {
        given().get("/api/dialogo/capacidade").then().statusCode(200)
            .header(GuardaVitrine.CABECALHO_VITRINE, nullValue())
            .body(containsString("nativo"));
        assertFalse(ModoVitrine.ativa(), "a vitrine vazou para o perfil normal");
    }

    @Test
    @DisplayName("desligada: o arquivo plantado existe e é servido (o 403 da vitrine é da guarda)")
    void arquivoPlantadoExiste() {
        given().get("/prova-vitrine/nao-deve-vazar.txt").then().statusCode(200)
            .body(containsString("Caso-controle da vitrine"));
    }

    @Test
    @DisplayName("desligada: o POST inofensivo é aceito pelo controller real")
    void postInofensivoAceito() {
        given().post("/api/pipeline/parar").then().statusCode(200);
    }
}
