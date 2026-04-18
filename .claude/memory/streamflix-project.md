# StreamfliX Project

## Informations générales
- **Repo GitHub** : Xx-nanico-xX/StreamfliX (privé)
- **Package** : com.streamfr.app
- **Type** : Application Android (TV + Mobile)
- **Langue** : Interface principalement en français

## Build & Release
- **Workflow** : .github/workflows/release.yml
- **Déclencheur** : push d'un tag `v*`
- **Produit** : 3 APKs (default, mobile, TV) signés et attachés à la release GitHub
- **Auto-update** : InAppUpdater.kt vérifie les releases GitHub pour les mises à jour automatiques

## Historique des versions
- v1.7.114 : version stable précédente (commit a9ea332)
- v1.7.115 : fix des fichiers tronqués (commit d185b3ed) — publié le 16/04/2026

## Problèmes connus
- Les modifications intentionnelles de v1.7.115 (renommage StreamFR, fix AfterDark CAPTCHA, fix UnJourUnFilm JSON, refactoring TmdbProvider) ont été perdues car jamais correctement commitées à git — les fichiers étaient tronqués dès le départ
- Ces changements devront être ré-appliqués dans une future version
