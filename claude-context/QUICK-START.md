# Quick Start for New Claude Session

## How to Resume

Copie-colle ce prompt dans une nouvelle session Claude :

---

Je travaille sur l'app Android **Streamflix** (Kotlin, streaming multi-providers). Lis le dossier `claude-context/` dans le projet pour récupérer le contexte complet de nos sessions précédentes :

1. `claude-context/PROJECT-OVERVIEW.md` — architecture, providers, extracteurs, tous les fichiers modifiés
2. `claude-context/CONVERSATION-HISTORY.md` — historique des 3 sessions, erreurs rencontrées, décisions prises
3. `claude-context/modified-files/` — copies des fichiers clés modifiés récemment

Lis ces fichiers puis dis-moi ce que tu as compris. Ensuite on continue le travail.

---

## Current State (Session 3 — 2026-04-23)

- **Providers modifiés** : WiflixProvider, VoirDramaProvider, VoirAnimeProvider, AnimeSamaProvider
- **Extracteurs modifiés** : LuluVdoExtractor, RpmvidExtractor
- **Navigation** : Movie→TvShow redirect pour VoirDrama/VoirAnime
- **UI** : Tabs renommés FR/VOSTFR, film detection, episode thumbnails, empty content filter
- **Wiflix** : Featured slider, Derniers Episodes, vrais noms de serveurs, déduplication
- **LuluVdo** : Corrigé — utilise NetworkClient.default + ScalarsConverterFactory + logs debug
- **Status** : LuluVdo extractor fonctionne (200 OK, source HLS extraite)

## Fichiers modifiés (session 3)
- `extractors/LuluVdoExtractor.kt` — refonte complète (NetworkClient, Scalars, debug logs)
- `extractors/RpmvidExtractor.kt` — nouveaux domaines flemmix, rotatingDomain, extractId amélioré
- `providers/WiflixProvider.kt` — featured slider, Derniers Episodes, vrais noms serveurs, déduplication
- `providers/VoirDramaProvider.kt` — episode thumbnails, filtre contenu vide
- `providers/VoirAnimeProvider.kt` — idem VoirDrama
- `providers/AnimeSamaProvider.kt` — filtre Scan, probe contenu, suppression fallback fake
- `adapters/viewholders/MovieViewHolder.kt` — redirect Movie→TvShow pour Drama/Anime
- `adapters/viewholders/TvShowViewHolder.kt` — "Regarder maintenant" pour films, hide Saisons
- `fragments/tv_show/TvShowMobileFragment.kt` — hide section Saisons pour films
- `activities/main/MainMobileActivity.kt` — tabs VoirDrama/VoirAnime customisation
- `res/navigation/nav_main_graph_mobile.xml` — action movies→tv_show, nullable args
- `res/values/strings.xml` + `res/values-fr/strings.xml` — tabs FR/VOSTFR
