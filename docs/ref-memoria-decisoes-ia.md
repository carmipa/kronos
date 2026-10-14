# Memoria de Decisoes da IA

Este arquivo registra decisoes que nao devem depender apenas do historico da conversa.

## Correcao de Legendas

- Pacote oficial: `org.traducao.projeto.correcaoLegendas`.
- Nome conceitual: **Correcao de Legendas**.
- Nome antigo/legado: **Cura de Tags**.
- Endpoint oficial: `POST /api/correcao-legendas`.
- Endpoint legado mantido: `POST /api/cura-tags`.
- A legenda original e referencia imutavel e nunca deve ser gravada.
- A legenda traduzida PT-BR e o unico alvo de alteracao.
- O modulo roda depois da traducao pronta e depois/ao lado dos refinamentos, como pos-processamento estrutural.
- Com `contextoId`, pode usar LLM local para corrigir falas suspeitas.
- Sem `contextoId`, deve operar de forma estrutural/deterministica sem LLM.
- Toda execucao deve registrar logs coloridos/temporizados no console, JSON de relatorio e telemetria.

## Revisao de Lore

- `revisaoLore` e modulo separado e desacoplado da Traducao (`traducao`); a lore que ele consome vem do peer `lore`.
- Os prompts da revisao de lore vivem na tabela `lore_revisao` dos arquivos `src/main/resources/lore/obras/<id>.sql` e sao entregues por `revisaoLore.application.GerenciadorPromptRevisaoLore`.
- Objetivo: corrigir nomes de personagens, lugares, objetos, organizacoes e termos de universo.
- Deve preservar nomes no idioma original quando o prompt/lore assim determinar.
- E uma etapa de refinamento posterior a traducao pronta.
- Usa LLM para revisar/corrigir quando ha termos suspeitos.

## Regra de Ouro da Pipeline

```text
Traducao gera PT-BR.
Revisao de lore corrige termos de universo.
Correcao de legendas usa a original como espelho estrutural.
Remuxer acopla a legenda final.
```

## Aprendizados dos Relatorios

- Relatorios de `86` mostraram que o pareamento precisava reconhecer `*_ENG.ass -> *_PT-BR.ass`.
- Relatorios de Gundam 0083 e 08th MS Team mostraram que o corretor corrigiu tags reais sem erros quando encontrou pares.
- Relatorio de Gundam Narrative mostrou que `semPar` precisa ser investigado antes de concluir que nao ha alteracoes a fazer.
- O sanitizador deve reconhecer como tag ASS valida apenas prefixos `{\\...}` e `{=...}`; texto como `{pensamento}` deve ser preservado como conteudo, nao tratado como tag real.

## Estado Atual Validado

- Teste manual via API confirmou:
  - original intacta;
  - traduzida corrigida;
  - prefixo ASS restaurado;
  - tag alucinada removida;
  - texto em chave invalida preservado;
  - relatorio JSON gerado;
  - telemetria registrada.
- `./gradlew.bat test` passou apos os ajustes.
