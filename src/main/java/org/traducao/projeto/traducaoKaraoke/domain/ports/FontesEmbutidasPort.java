package org.traducao.projeto.traducaoKaraoke.domain.ports;

import org.traducao.projeto.traducaoKaraoke.domain.FontesDoVideo;

import java.nio.file.Path;

/**
 * PROPÓSITO DE NEGÓCIO: porta pela qual a tradução de karaokê pergunta quais fontes viajam dentro
 * do vídeo (os anexos do MKV) e o que cada uma sabe desenhar — sem a fatia conhecer o
 * MKVToolNix, o formato do contêiner ou a biblioteca que lê fonte.
 *
 * <p>INVARIANTES DO DOMÍNIO: só LÊ o vídeo; nunca grava nada nele nem ao lado dele. Arquivos
 * temporários, se houver, são do adaptador e não sobrevivem à chamada.
 *
 * <p>COMPORTAMENTO EM CASO DE FALHA: nunca lança — ferramenta ausente, vídeo ilegível ou fonte
 * corrompida devolvem {@link FontesDoVideo#naoVerificadas} com o motivo.
 */
public interface FontesEmbutidasPort {

    FontesDoVideo lerFontes(Path video);
}
