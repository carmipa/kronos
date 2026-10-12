"""Gera a fonte de teste src/test/resources/karaoke/fontes/acento-vazio.ttf.

PROPÓSITO DE NEGÓCIO: reproduz, com desenho PRÓPRIO (um quadrado), o defeito medido em 12/10/2026 na
fonte "Complete in Him" do encerramento do Guilty Crown: o cmap aponta as letras acentuadas para um
glifo SEM CONTORNO, então `Font.canDisplay` diz "desenha" e o libass mostra um buraco ("voc ." no
lugar de "você"). Fonte de terceiro não entra no repositório público; esta é inteira nossa.

INVARIANTES DO DOMÍNIO: "a", "e" e "ã" apontam para o quadrado (desenham); "ê" e o espaço de largura
zero (U+200B) apontam para um glifo vazio; o espaço comum é vazio, como em qualquer fonte. A família
é "Kronos Acento Vazio".

COMPORTAMENTO EM CASO DE FALHA: sem o fontTools instalado o script para com ImportError e não grava
nada. Uso: python ferramentas/gerar-fonte-teste-acento-vazio.py <saida.ttf>
"""
import sys

from fontTools.fontBuilder import FontBuilder
from fontTools.pens.ttGlyphPen import TTGlyphPen


def quadrado():
    """Glifo que DESENHA: um quadrado de 400x700 unidades.

    Invariante: um contorno fechado, com área. Falha: não falha.
    """
    pen = TTGlyphPen(None)
    pen.moveTo((100, 0))
    pen.lineTo((100, 700))
    pen.lineTo((500, 700))
    pen.lineTo((500, 0))
    pen.closePath()
    return pen.glyph()


def vazio():
    """Glifo SEM CONTORNO, o defeito da "Complete in Him".

    Invariante: zero contornos. Falha: não falha.
    """
    return TTGlyphPen(None).glyph()


def main(saida):
    """Monta e grava a fonte de teste em `saida`.

    Propósito: o fixture do CoberturaAwtGlifoVazioTest. Invariantes: as do
    módulo. Falha: erro do fontTools sobe e nada é gravado pela metade
    (o save do fontTools escreve no fim).
    """
    fb = FontBuilder(1000, isTTF=True)
    fb.setupGlyphOrder([".notdef", "space", "quadrado", "vazio"])
    fb.setupCharacterMap({0x20: "space", 0x61: "quadrado", 0x65: "quadrado", 0xE3: "quadrado",
                          0xEA: "vazio", 0x200B: "vazio"})
    fb.setupGlyf({".notdef": vazio(), "space": vazio(), "quadrado": quadrado(), "vazio": vazio()})
    fb.setupHorizontalMetrics({".notdef": (500, 0), "space": (500, 0), "quadrado": (600, 100),
                               "vazio": (600, 0)})
    fb.setupHorizontalHeader(ascent=800, descent=-200)
    # O leitor do Java recusa a fonte sem o nome completo (nameID 4): "Font name not found".
    fb.setupNameTable({"familyName": "Kronos Acento Vazio", "styleName": "Regular",
                       "uniqueFontIdentifier": "KronosAcentoVazio-Regular",
                       "fullName": "Kronos Acento Vazio", "psName": "KronosAcentoVazio-Regular"})
    fb.setupOS2(sTypoAscender=800, sTypoDescender=-200, usWinAscent=800, usWinDescent=200)
    fb.setupPost()
    fb.save(saida)


if __name__ == "__main__":
    main(sys.argv[1])
