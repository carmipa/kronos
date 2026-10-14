# 🎬 Módulo: Metadados de Anime

[← Módulo Telemetria](modulo-telemetria.md) | [Mapa do Projeto →](modulo-mapa-projeto.md)

---

## Para que serve

Módulo **decorativo/informativo** — não afeta tradução, cache ou lore. Busca pôster, título, sinopse, ano, episódios e score de um anime a partir do caminho da pasta informada em qualquer painel, para que o operador confirme visualmente que está prestes a processar o anime certo antes de disparar uma operação em lote.

![Cartão de metadados exibido ao selecionar uma obra (exemplo: DanMachi Season 4)](../src/main/resources/static/img/screenshots/metadados-anime.webp)

---

## Fluxo de resolução

```mermaid
graph TD
    A["Caminho da pasta<br/>ex: E:/animes/[Sokudo] DanMachi/Season 05"] --> B["extrairNomeTermoBusca()<br/>remove tags de release, temporada, idioma"]
    B --> C{"Cache local em<br/>cache/metadata/&lt;slug&gt;.json?"}
    C -->|Existe| D["Retorna direto — sem chamar API externa"]
    C -->|Não existe| E{"tmdb.api-key<br/>configurada?"}
    E -->|Sim| F["TmdbApiClientAdapter<br/>/search/tv → /search/movie (pt-BR)"]
    E -->|Não| G["AniListApiClientAdapter<br/>graphql.anilist.co (sem chave)"]
    F -->|Falhou/sem resultado| G
    G -->|Falhou/sem resultado| I["JikanApiClientAdapter<br/>api.jikan.moe (último fallback)"]
    F --> H["Salva em cache/metadata/&lt;slug&gt;.json"]
    G --> H
    I --> H
    H --> D
```

- **Normalização do nome:** remove tags de release (`[1080p]`, `(BD)`, `S01E01`, `ENG`, `PTBR` etc.) e extrai o segmento de pasta mais provável como nome do anime.
- **TMDB** é tentado primeiro (melhor cobertura de sinopse em português), só se `tmdb.api-key` estiver configurada — ver [Configuração](ref-configuracao.md).
- **AniList** é o fallback público principal, sem chave ou autenticação, e fornece capa, títulos, ano, episódios, nota, sinopse e gêneros.
- **Jikan/MyAnimeList** permanece como último fallback quando TMDB e AniList não retornam resultado.
- **Conferência de obra:** o resultado de um provedor só é aceito se **toda palavra de 3+ letras** do termo buscado aparece em algum dos títulos devolvidos (original, inglês ou japonês). Resultado que não confere conta como ausência e a busca segue para o próximo provedor; se nenhum confere, a tela fica **sem cartão** — melhor do que exibir (e gravar no cache) outra obra.
- **Cache negativo de 5 minutos:** termo que nenhuma fonte resolveu não é buscado de novo nesse intervalo.
- **Buscas concorrentes deduplicadas:** pedidos simultâneos do mesmo termo esperam a mesma busca, em vez de cada um chamar as APIs.

---

## Onde aparece na UI

Treze painéis possuem `.anime-meta-banner`: 1.1 Análise de Mídia, 1.2 Extração, 1.3 Troca de Tipo de Legenda, 1.4 Análise de Legenda (auditor), 2.1 Tradução Local, 2.3 Correção de Cache, 3.1 Revisão de Legendas, 3.2 Revisão de Lore, 3.3 Revisão de Concordância, 4.1 Karaokê, 4.2 Correção de Karaokê, 5.1 Remuxer e 5.2 Renomear Arquivos. Ao digitar/selecionar um caminho ou mudar o select de obra, o frontend (`inicializarMetadadosDinamicos()` em `js/app.js`) chama `GET /api/metadata?caminho=...` e renderiza o banner (pôster, título, sinopse, score) acima do formulário. Se todas as fontes falharem, o banner fica oculto e nunca bloqueia a operação.

---

## Endpoint REST

### `GET /api/metadata?caminho=<pasta_ou_nome>`

```json
{
  "titulo": "DanMachi: Is It Wrong to Try to Pick Up Girls in a Dungeon?",
  "tituloIngles": "DanMachi",
  "tituloJapones": "ダンジョンに出会いを求めるのは間違っているだろうか",
  "posterUrl": "https://...",
  "ano": 2015,
  "episodios": 13,
  "score": 7.8,
  "sinopse": "...",
  "generos": ["Ação", "Aventura", "Fantasia"]
}
```

---

## Navegação

| Anterior | Próximo |
|----------|---------|
| [← Módulo Telemetria](modulo-telemetria.md) | [Mapa do Projeto →](modulo-mapa-projeto.md) |
