# Streamflix — Project Overview

## Architecture
- **Android app** (Kotlin) — streaming multi-providers
- **Package**: `com.streamflixreborn.streamflix`
- **Build**: Gradle, AndroidX, ExoPlayer (Media3)
- **Network**: OkHttp + Retrofit, DoH DNS (Cloudflare), WebView bypass Cloudflare
- **Navigation**: Android Navigation Component with SafeArgs

## Key Components

### Providers (sources de contenu)
- **WiflixProvider** — séries/films FR, domaine changeant (actuellement flemmix.wales), auto-update via portail
- **VoirDramaProvider** — dramas, thème WordPress Madara (wp-manga)
- **VoirAnimeProvider** — anime, même structure que VoirDrama
- **AnimeSamaProvider** — anime VOSTFR, structure custom avec episodes.js

### Extractors (extraction vidéo depuis les hébergeurs)
Chaque extracteur hérite de `Extractor()` avec `name`, `mainUrl`, `aliasUrls`, `rotatingDomain`, `extract(link)`

- **LuluVdoExtractor** — luluvdo.com (aliases: luluvdoo, luluvid, lulustream). Utilise JWPlayer + eval packed JS. Refait en session 3: NetworkClient.default + ScalarsConverterFactory
- **RpmvidExtractor** — rpmvid.com + flemmix.* domains. API /api/v1/video, AES decrypt (key: kiemtienmua911ca)
- **VoeExtractor** — voe.sx + domaines rotatifs (charlestoughrace.com, etc.)
- **DoodLaExtractor** — dood.la + aliases (playmogo.com, etc.)
- **VidaraExtractor** — vidara.to
- **VidsonicExtractor** — vidsonic.net
- **FrembedExtractor** — frembed.cyou, agrégateur (contient Netu, Voe, Dood, etc.)
- **NetuExtractor** — netu.tv, problèmes ISP FR (cfglobalcdn.com bloqué)

### Network Layer
- **NetworkClient** — OkHttpClient partagé avec CookieJar WebView, headers navigateur, TLS config
- **DnsResolver** — DNS over HTTPS via Cloudflare
- **WebViewResolver** — bypass Cloudflare challenges via WebView Android

### UI / Navigation
- **MainMobileActivity** — bottom nav avec tabs personnalisés par provider
- **MovieViewHolder** — affichage items films, redirect Movie→TvShow pour VoirDrama/VoirAnime
- **TvShowViewHolder** — affichage détail série, détection film (single episode)
- **TvShowMobileFragment** — page détail série/film, hide Saisons pour films

## Fichiers modifiés (toutes sessions)

### Session 1-2 (Player + Netu)
- `fragments/player/PlayerTvFragment.kt` — DoH + TCP pre-check + auto-fallback
- `fragments/player/PlayerMobileFragment.kt` — idem TV
- `extractors/NetuExtractor.kt` — fix DNS
- `extractors/FrembedExtractor.kt` — Netu priority 99

### Session 3 (Multi-provider UI + Wiflix + LuluVdo)
- `extractors/LuluVdoExtractor.kt` — refonte: NetworkClient.default, ScalarsConverterFactory, debug logs
- `extractors/RpmvidExtractor.kt` — nouveaux domaines flemmix, rotatingDomain, extractId 3 formats
- `providers/WiflixProvider.kt` — featured slider, Derniers Episodes, vrais noms serveurs, deduplicateServers()
- `providers/VoirDramaProvider.kt` — episode thumbnails (show poster), filtre contenu vide
- `providers/VoirAnimeProvider.kt` — idem VoirDrama
- `providers/AnimeSamaProvider.kt` — filtre Scan, probe episodes.js, suppression fake Season 1
- `adapters/viewholders/MovieViewHolder.kt` — isDramaOrAnimeProvider(), redirect Movie→TvShow
- `adapters/viewholders/TvShowViewHolder.kt` — "Regarder maintenant" pour films, hide Saisons
- `fragments/tv_show/TvShowMobileFragment.kt` — hide section Saisons si 1 season ≤1 episode
- `activities/main/MainMobileActivity.kt` — tabs FR/VOSTFR pour VoirDrama/VoirAnime
- `res/navigation/nav_main_graph_mobile.xml` — action movies→tv_show, nullable args
- `res/values/strings.xml` + `res/values-fr/strings.xml` — tabs FR/VOSTFR

## Patterns importants

### Extractor Domain Matching
```kotlin
override val mainUrl = "https://example.com"
override val aliasUrls = listOf("https://alias1.com", "https://alias2.com")
override val rotatingDomain = listOf(Regex("pattern\\..*?/embed"))
// Extractor.identifyServiceName(url) → résout l'extracteur par URL
```

### Provider Content Filtering (Madara/wp-manga)
```kotlin
val listChapter = element.selectFirst(".list-chapter")
if (listChapter != null && listChapter.select(".chapter-item").isEmpty()) return null
```

### Server Deduplication (Wiflix)
```kotlin
private fun deduplicateServers(servers: List<Video.Server>): List<Video.Server> {
    val seen = mutableSetOf<String>()
    return servers.filter { server ->
        val serviceName = Extractor.identifyServiceName(server.src)
        if (serviceName != null) seen.add(serviceName) else true
    }
}
```

### LuluVdo Extraction Flow
1. Fetch page via NetworkClient.default (cookies partagés WebView)
2. ScalarsConverterFactory → HTML brut en String
3. Try direct regex (sources/file patterns)
4. If not found → unpackJs() eval(function(p,a,c,k,e,d))
5. Extract m3u8 URL from unpacked JS
6. Return Video with Referer header

## Domaines Wiflix actuels (vérifié 2026-04-23)
- Site: flemmix.wales (change fréquemment, auto-update via portail)
- Lecteur 1: luluvdo.com (LuluVdo/LuluStream)
- Lecteur 2: playmogo.com (DoodStream) ou charlestoughrace.com (VOE)
- Lecteur 3: flemmix.upns.pro (Rpmvid) ou vidara.to/vidsonic.net
