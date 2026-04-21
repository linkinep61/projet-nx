# Quick Start for New Claude Session

## How to Resume

Give this prompt to your new Claude session:

---

**Prompt:**

Lis tous les fichiers dans le dossier `claude-context/` de mon projet pour récupérer le contexte complet :

1. `claude-context/PROJECT-OVERVIEW.md` — vue d'ensemble du projet, architecture, et toutes les modifications récentes
2. `claude-context/CONVERSATION-HISTORY.md` — historique des conversations et décisions
3. `claude-context/modified-files/` — copies des fichiers modifiés

Les fichiers modifiés dans le projet sont :
- `app/src/main/java/com/streamflixreborn/streamflix/fragments/player/PlayerTvFragment.kt`
- `app/src/main/java/com/streamflixreborn/streamflix/fragments/player/PlayerMobileFragment.kt`
- `app/src/main/java/com/streamflixreborn/streamflix/extractors/NetuExtractor.kt`
- `app/src/main/java/com/streamflixreborn/streamflix/extractors/FrembedExtractor.kt`
- `local.properties` (APP_LAYOUT=mobile pour tests émulateur)

---

## Current State

- **TV Player**: Fully updated with DoH + TCP pre-check + auto-fallback
- **Mobile Player**: Fully updated (same features as TV)
- **Netu priority**: Set to last (99) in FrembedExtractor
- **Build mode**: mobile (for emulator testing)
- **Status**: Ready to build and test

## What Needs Testing
1. Build the mobile APK and run on emulator
2. Play a movie via Frembed provider
3. Check logcat with filter "PlayerNetwork" to see DataSource selection
4. If Netu is tried (last), verify TCP pre-check detects blocked IPs in ~3s
5. Verify auto-fallback to next server works
