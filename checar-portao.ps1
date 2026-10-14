<#
.SYNOPSIS
    Portao unico do KRONOS: confere a cadeia de leitura das regras e roda TODAS as guardas.

.DESCRIPTION
    PROPOSITO DE NEGOCIO
    Endereco unico. Guarda que depende de alguem lembrar de rodar e documentacao com sorte,
    e regra que alguem "leu" sem comprovante e lembranca.

    TRES ESTADOS, NUNCA DOIS
        0  pode trabalhar
        1  defeito real  (guarda vermelha, hash divergente, documento sumido, compilacao quebrada)
        2  NAO DEU PARA CONFERIR  (sem Java, sem gradlew, zero teste selecionado)

    O estado 2 existe porque "nao verificou" nao e aprovacao. Sem ele, um ambiente sem Java e
    um repositorio impecavel produzem exatamente o mesmo silencio.

    INVARIANTE: --rerun-tasks e OBRIGATORIO.
    O PREJUIZO, medido neste repositorio: uma IA comitou alteracao de lore com a suite
    "verde" que na verdade era UP-TO-DATE do cache do Gradle. Nenhum teste havia rodado.
    Verde de cache e indistinguivel de verde de execucao — a nao ser que se force a execucao.

    COBERTURA DECLARADA: o filtro pega a convencao de nome (Catraca*, Fronteira*, Guarda*) e
    mais as DUAS guardas que moram fora dela, nomeadas explicitamente abaixo. Guarda nova com
    nome fora da convencao NAO entra sozinha aqui — acrescente na lista.

.EXAMPLE
    .\checar-portao.ps1
#>

$ErrorActionPreference = 'Stop'
Set-Location -Path $PSScriptRoot

$COMPROVANTE = '.claude/LEITURA-REGRA-ATUAL.md'

# As duas ultimas moram fora da convencao de nome e estao documentadas em
# docs/catracas-e-fronteiras.md, secao "Guardas que nao se chamam Catraca".
# As da vitrine publica (VitrineRotasDoCodigoTest, PoliticaVitrineTest, ModoVitrineCamada2Test...)
# entram pelo nome QUALIFICADO: no Windows o lancador Java expande curinga solto em nome de arquivo,
# e '*Vitrine*' casaria com o Dockerfile.vitrine da raiz e viraria nome de tarefa do Gradle.
$FILTROS = @('*Catraca*', '*Fronteira*', '*Guarda*', '*WebInterfaceTest', '*SseConsoleDinamicoTest', 'org.traducao.projeto.*Vitrine*')

function Escrever($rotulo, $texto) { Write-Host ("  {0,-14} {1}" -f $rotulo, $texto) }

Write-Host ''
Write-Host '=== PORTAO DO KRONOS ==='
Write-Host ''

# --------------------------------------------------------------------------
# 1. Cadeia de leitura: o comprovante existe, aponta para arquivo vivo e bate o hash.
# --------------------------------------------------------------------------
Write-Host '[1/2] Portao de leitura'

if (-not (Test-Path $COMPROVANTE)) {
    Escrever 'DEFEITO' "$COMPROVANTE nao existe — ninguem registrou leitura de regra."
    Escrever '' 'Leia docs/catracas-e-fronteiras.md e crie o comprovante com os SHA-256.'
    exit 1
}

$linhas = Get-Content $COMPROVANTE

# O padrao e FROUXO de proposito: casa qualquer linha de tabela cujo primeiro campo
# esteja entre crases, e valida o conteudo DEPOIS.
#
# O PREJUIZO, medido em 11/08/2026 na primeira execucao deste script: o padrao exigia
# `[0-9a-f]{64}` na propria expressao. Um hash adulterado para "a1c890d7b048FFFF"
# simplesmente NAO CASAVA — a linha era pulada em silencio, o contador caia de 3 para 2
# e o portao respondia "OK, 2 documento(s) conferido(s)" e saia ZERO. Guarda que descarta
# o que nao entende aprova por cegueira. Linha malformada agora e DEFEITO, nunca descarte.
$padrao = '^\|\s*`([^`]+)`\s*\|\s*([^|]*)\|\s*`([^`]*)`'
$conferidos = 0
$problemas = @()

foreach ($linha in $linhas) {
    $m = [regex]::Match($linha, $padrao)
    if (-not $m.Success) { continue }

    $arquivo = $m.Groups[1].Value
    $linhasRegistradas = $m.Groups[2].Value.Trim()
    $hashRegistrado = $m.Groups[3].Value
    $conferidos++

    if ($hashRegistrado -cnotmatch '^[0-9a-f]{64}$') {
        $problemas += "$arquivo tem SHA-256 malformado no comprovante: '$hashRegistrado'"
        continue
    }
    if ($linhasRegistradas -notmatch '^\d+$') {
        $problemas += "$arquivo tem contagem de linhas malformada no comprovante: '$linhasRegistradas'"
        continue
    }

    if (-not (Test-Path $arquivo)) {
        # Caminho morto: o documento sumiu ou mudou de lugar, e o comprovante virou
        # instrucao para ler o vazio. E defeito real, nao "nao deu para conferir".
        $problemas += "documento sumiu: $arquivo"
        continue
    }

    $atual = (Get-FileHash $arquivo -Algorithm SHA256).Hash.ToLower()
    if ($atual -ne $hashRegistrado) {
        # Hash INTEIRO, nao prefixo. Na calibracao de 11/08/2026 a versao com
        # Substring(0,12) imprimiu dois prefixos identicos e a mensagem nao dizia nada.
        $problemas += "$arquivo mudou desde a ultima leitura`n                 registrado: $hashRegistrado`n                 atual.....: $atual"
        continue
    }

    # Segunda medida, independente do hash: obriga quem atualiza o comprovante a mexer
    # em DOIS campos coordenados. Hash colado as pressas com a contagem antiga aparece aqui.
    $agora = (Get-Content $arquivo).Count
    if ([int]$linhasRegistradas -ne $agora) {
        $problemas += "$arquivo tem $agora linha(s), o comprovante diz $linhasRegistradas"
    }
}

if ($conferidos -eq 0) {
    # Instrumento cego: o comprovante existe mas nenhuma linha da tabela foi lida.
    Escrever 'NAO CONFERIU' "$COMPROVANTE nao tem nenhuma linha no formato esperado."
    exit 2
}

if ($problemas.Count -gt 0) {
    foreach ($p in $problemas) { Escrever 'DEFEITO' $p }
    Escrever '' "Releia o documento inteiro e atualize $COMPROVANTE."
    exit 1
}

Escrever 'OK' "$conferidos documento(s)-regra conferido(s) por SHA-256."

# --------------------------------------------------------------------------
# 2. Guardas, com execucao FORCADA.
# --------------------------------------------------------------------------
Write-Host ''
Write-Host '[2/2] Guardas executaveis (--rerun-tasks)'

if (-not (Test-Path '.\gradlew.bat')) {
    Escrever 'NAO CONFERIU' 'gradlew.bat ausente — este script roda na raiz do projeto.'
    exit 2
}
if (-not (Get-Command java -ErrorAction SilentlyContinue)) {
    Escrever 'NAO CONFERIU' 'java nao esta no PATH — a suite nao pode nem comecar.'
    exit 2
}

$resultados = 'build/test-results/test'
if (Test-Path $resultados) { Remove-Item "$resultados/*.xml" -Force -ErrorAction SilentlyContinue }

$argumentos = @('test', '--rerun-tasks', '--console=plain')
foreach ($f in $FILTROS) { $argumentos += @('--tests', $f) }

& .\gradlew.bat @argumentos 2>&1 | Out-Host
$saidaGradle = $LASTEXITCODE

$xml = @(Get-ChildItem "$resultados/TEST-*.xml" -ErrorAction SilentlyContinue)
if ($xml.Count -eq 0) {
    if ($saidaGradle -ne 0) {
        # Gradle quebrou antes de produzir resultado: compilacao, dependencia, filtro vazio.
        Escrever 'DEFEITO' "gradlew saiu $saidaGradle sem gerar nenhum resultado de teste."
        exit 1
    }
    Escrever 'NAO CONFERIU' 'nenhum XML de resultado — a suite nao rodou.'
    exit 2
}

$total = 0; $falhas = 0; $erros = 0; $pulados = 0
foreach ($f in $xml) {
    $doc = [xml](Get-Content $f.FullName)
    $total   += [int]$doc.testsuite.tests
    $falhas  += [int]$doc.testsuite.failures
    $erros   += [int]$doc.testsuite.errors
    $pulados += [int]$doc.testsuite.skipped
}

$executados = $total - $pulados
if ($executados -le 0) {
    # ZERO teste executado com build verde e o falso-verde classico.
    Escrever 'NAO CONFERIU' "0 teste executado em $($xml.Count) classe(s) — instrumento cego, nao aprovacao."
    exit 2
}

Escrever 'RODARAM' "$executados teste(s) em $($xml.Count) classe(s) de guarda (pulados: $pulados)."

if ($falhas -gt 0 -or $erros -gt 0 -or $saidaGradle -ne 0) {
    Escrever 'DEFEITO' "$falhas falha(s), $erros erro(s). Relatorio: build/reports/tests/test/index.html"
    Write-Host ''
    Write-Host 'PORTAO: 1 — defeito real. NAO trabalhe por cima disto.'
    exit 1
}

Write-Host ''
Write-Host 'PORTAO: 0 — pode trabalhar.'
Write-Host 'Isto prova as GUARDAS, nao a suite inteira. Para tudo: .\gradlew.bat test --rerun-tasks'
exit 0
