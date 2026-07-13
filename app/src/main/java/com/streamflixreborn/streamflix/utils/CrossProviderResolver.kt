package com.streamflixreborn.streamflix.utils

import com.streamflixreborn.streamflix.models.Movie
import com.streamflixreborn.streamflix.models.TvShow
import com.streamflixreborn.streamflix.models.Video
import com.streamflixreborn.streamflix.providers.Provider
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 2026-06-28 (user "ça doit être générique tu vois faut que tu répares tes
 * recherches pour vraiment que ça matche avec n'importe quoi et pas que pour
 * ce provider") : utilitaire de résolution cross-provider.
 *
 * Problème :
 *   - On a un tvShow.id (= retourné par `provider.search`) + un videoType.Episode
 *     (= saison N, épisode M).
 *   - On appelle `provider.getServers(tvShowId, videoType)` → 0 servers
 *     car le provider attend un EPISODE id (= avec saison + ep encodés dans
 *     son format propre), PAS un tvShow id.
 *
 * Solution générique : appeler `provider.getTvShow(tvShowId)` →
 *   parcourir les saisons → trouver la bonne → `getEpisodesBySeason(season.id)` →
 *   trouver l'épisode N → retourner son `episode.id`.
 *
 * Coût : 2 appels réseau supplémentaires par provider. Acceptable car c'est
 * une fois par lookup et fait en parallèle entre tous les providers.
 */
object CrossProviderResolver {

    private const val TAG = "CrossProviderResolver"

    /**
     * Résout l'ID exact à passer à `provider.getServers(id, videoType)`.
     *
     * @param provider le provider de backup (= AnimeSama, FRAnime, etc.)
     * @param match résultat d'une recherche (Movie ou TvShow du provider)
     * @param videoType type demandé (Movie ou Episode avec saison+ep)
     * @return ID à passer à getServers, ou null si non résolu
     */
    suspend fun resolveServerId(
        provider: Provider,
        match: Any,
        videoType: Video.Type,
    ): String? {
        // Films : l'id direct du match suffit (= movie.id)
        if (videoType is Video.Type.Movie || match is Movie) {
            return when (match) {
                is Movie -> match.id
                is TvShow -> match.id
                else -> null
            }
        }
        // Série + Episode → résoudre vers episode.id
        if (videoType !is Video.Type.Episode || match !is TvShow) {
            return when (match) {
                is Movie -> match.id
                is TvShow -> match.id
                else -> null
            }
        }
        val tvShowId = match.id
        val originalSeason = videoType.season.number
        // 2026-07-10 (user AnimeSama : « ils matchent mais rendent 0 serveurs ») : quand l'épisode
        //   arrive en saison NON PRÉCISÉE (S0, ex. AnimeSama single-saison), on cherche la saison
        //   EFFECTIVE = S1 (les sites backup numérotent « Saison 1 »). Sinon CrossProviderResolver
        //   cherchait « S0 », ne le trouvait pas (sites en [S1…]) et abandonnait. Corrige
        //   FRAnime/FrenchManga/Wiflix/DessinAnime qui trouvaient la bonne fiche mais 0 serveur.
        val effectiveSeason = if (originalSeason <= 0) 1 else originalSeason
        val originalEpisode = videoType.number
        return try {
            // 2026-07-09 : timeout remonté 10s→25s. AnimeSama (et providers avec probing
            //   séquentiel de saisons) met 10-15s rien que pour fetch la page catalogue +
            //   parser le HTML + prober 6-12 dossiers episodes.js. À 10s, le timeout tuait
            //   les probes AVANT qu'elles démarrent → 0 saisons → anime non trouvé en backup.
            //   25s est safe : le plafond global BackupRegistry (60s/source) protège toujours.
            var full: TvShow = withTimeoutOrNull(25_000) { provider.getTvShow(tvShowId) }
                ?: run {
                    android.util.Log.w(TAG, "[${provider.name}] getTvShow($tvShowId) TIMEOUT/null")
                    return null
                }
            // 2026-07-09 : Détection de "wrappers langue" (AnimeSama…).
            //   Quand un provider a VF+VOSTFR, getTvShow retourne 2-3 saisons
            //   "VOSTFR" (#1), "VF" (#2) = wrappers, PAS les vraies saisons.
            //   Les numéros 1/2 = langues, pas les vrais numéros de saison TMDB.
            //   Signature : season.id contient "/@" (format wrapper AnimeSama).
            //   FIX : re-appeler getTvShow avec langue forcée (id@vf ou id@vostfr)
            //   → le provider retourne les VRAIES saisons numérotées (Saison 1,2,3…).
            // 2026-07-12 : wrapper unwrap INTELLIGENT. On essaie VF puis VOSTFR, mais on ne
            //   break QUE si la langue choisie a la SAISON CIBLE. Sinon on essaie l'autre langue.
            //   Ex: Mushoku Tensei S3 n'existe qu'en VOSTFR → VF n'a que S1/S2 → on passait en VF
            //   → S3 introuvable. Maintenant : VF essayé → S3 absente → on continue → VOSTFR → S3 trouvée.
            if (full.seasons.any { s -> s.id.contains("/@") }) {
                val baseSlug = tvShowId.substringBefore("@")
                android.util.Log.d(TAG, "[${provider.name}] detected lang wrappers for $tvShowId " +
                    "(seasons=${full.seasons.map { "'${it.title}'" }}) → retrying with forced lang (target S$effectiveSeason)")
                var bestFallback: TvShow? = null
                for (lang in listOf("vf", "vostfr")) {
                    val unwrapped = try {
                        withTimeoutOrNull(20_000) { provider.getTvShow("$baseSlug@$lang") }
                    } catch (_: Exception) { null }
                    if (unwrapped != null && unwrapped.seasons.isNotEmpty() &&
                        unwrapped.seasons.none { s -> s.id.contains("/@") }) {
                        android.util.Log.i(TAG, "[${provider.name}] unwrapped @$lang → ${unwrapped.seasons.size} real seasons: " +
                            unwrapped.seasons.map { "S${it.number}='${it.title}'" })
                        // Préférer la langue qui A la saison cible
                        if (unwrapped.seasons.any { it.number == effectiveSeason }) {
                            android.util.Log.i(TAG, "[${provider.name}] @$lang HAS target S$effectiveSeason → using it")
                            full = unwrapped
                            break
                        }
                        // Garder comme fallback (la 1ère langue qui a des vraies saisons)
                        if (bestFallback == null) bestFallback = unwrapped
                    }
                }
                // Si aucune langue n'a la saison cible, utiliser le meilleur fallback
                if (full.seasons.any { s -> s.id.contains("/@") } && bestFallback != null) {
                    android.util.Log.w(TAG, "[${provider.name}] no lang has S$effectiveSeason, using best fallback")
                    full = bestFallback
                }
            }
            // 2026-07-09 : PLUS de fallback aveugle sur la 1ère saison/épisode.
            //   Le user a dit « je préfère PAS DE SERVEUR que le mauvais film ».
            //   Si le numéro de saison ne matche pas (numérotation différente entre
            //   providers), on renvoie null → 0 serveurs pour CE provider. Avant, le
            //   code prenait full.seasons.firstOrNull() (= saison au hasard = mauvais
            //   contenu, ex: Bleach S1 → reçoit S5 ou S10).
            var targetSeason = full.seasons.firstOrNull { it.number == effectiveSeason }
            // 2026-07-09 : fallback S0 → S1. Certains providers (FrenchManga…) regroupent
            //   tout en une seule "Intégrale" numérotée S0. Si on cherche S1 et que le
            //   provider n'a QU'UNE saison S0, c'est probablement la bonne (= tout l'anime).
            if (targetSeason == null && effectiveSeason == 1 &&
                full.seasons.size == 1 && full.seasons[0].number == 0) {
                android.util.Log.i(TAG, "[${provider.name}] S1 not found, using sole S0 as fallback for $tvShowId")
                targetSeason = full.seasons[0]
            }
            // 2026-07-12 : fallback par TITRE de saison. Si le match par numéro échoue
            //   (numérotation positionnelle ≠ TMDB, ex "Saison 2 Partie 2" = #3 au lieu de #2),
            //   chercher une saison dont le LABEL contient "Saison N" / "Season N".
            if (targetSeason == null) {
                targetSeason = full.seasons.firstOrNull { s ->
                    val m = Regex("""(?i)\bsaison\s*(\d+)|\bseason\s*(\d+)""").find(s.title ?: "")
                    val n = m?.let { (it.groupValues[1].ifBlank { it.groupValues[2] }).toIntOrNull() }
                    n == effectiveSeason
                }
                if (targetSeason != null) {
                    android.util.Log.i(TAG, "[${provider.name}] S$effectiveSeason found by TITLE match: '${targetSeason.title}' (number=${targetSeason.number})")
                }
            }
            if (targetSeason == null) {
                android.util.Log.w(TAG, "[${provider.name}] no season S$effectiveSeason for $tvShowId " +
                    "(available: ${full.seasons.map { "S${it.number}='${it.title}'" }}) → SKIP (pas de fallback)")
                return null
            }
            val episodes = withTimeoutOrNull(20_000) { provider.getEpisodesBySeason(targetSeason.id) } ?: emptyList()
            if (episodes.isEmpty()) {
                android.util.Log.w(TAG, "[${provider.name}] no episodes in season ${targetSeason.id}")
                return null
            }
            // Idem : pas de fallback sur le 1er épisode si le numéro ne matche pas.
            val ep = episodes.firstOrNull { it.number == originalEpisode }
            if (ep == null) {
                android.util.Log.w(TAG, "[${provider.name}] no episode E$originalEpisode in season ${targetSeason.id} " +
                    "(available: ${episodes.map { "E${it.number}" }.take(10)}) → SKIP")
                return null
            }
            // 2026-07-09 : vérification TITRE de l'épisode — si le titre trouvé
            // n'a AUCUN mot significatif en commun avec le titre attendu, c'est
            // le MAUVAIS SHOW (ex: Bleach original vs TYBW sous le même nom).
            // User : « certains serveurs trouvent la vraie saison, d'autres
            //          trouvent les épisodes de la jaquette → n'importe quoi ».
            // EXCEPTION (user « si les épisodes sont juste marqués Épisode 1 ») :
            //   les titres GÉNÉRIQUES ("Épisode 1", "Episode bonus", "Saison 1
            //   Episode 3") ne sont PAS vérifiés — on ne peut pas comparer un
            //   titre générique à un vrai titre TMDB, et on ne veut pas perdre
            //   un serveur correct à cause d'un provider qui ne met pas de titre.
            if (videoType is Video.Type.Episode) {
                val expectedTitle = videoType.title
                val foundTitle = ep.title
                if (expectedTitle != null && foundTitle != null &&
                    expectedTitle.length > 3 && foundTitle.length > 3) {
                    val expWords = BackupRegistry.sigWords(expectedTitle)
                    val fndWords = BackupRegistry.sigWords(foundTitle)
                    // Mots génériques qu'on ignore (= pas un vrai titre)
                    val genericWords = setOf("episode", "episodes", "saison", "season",
                        "partie", "part", "chapitre", "chapter", "ova", "oav",
                        "special", "bonus", "film", "movie", "pilote", "pilot")
                    val fndReal = fndWords.filter { it !in genericWords }.toSet()
                    // Vérifier seulement si les DEUX côtés ont des vrais mots
                    if (expWords.size >= 2 && fndReal.size >= 2 &&
                        expWords.none { it in fndReal }) {
                        android.util.Log.w(TAG, "[${provider.name}] episode title MISMATCH: " +
                            "expected='$expectedTitle' found='$foundTitle' → SKIP (wrong content)")
                        return null
                    }
                }
            }
            android.util.Log.i(TAG, "[${provider.name}] resolved S${originalSeason}E${originalEpisode} → episode id=${ep.id} title='${ep.title}'")
            ep.id
        } catch (e: Exception) {
            android.util.Log.w(TAG, "[${provider.name}] resolve failed: ${e.message}")
            null
        }
    }

    /**
     * Helper : convertit le résultat d'un `provider.search(title)` en serveurs,
     * en faisant automatiquement la résolution episode si nécessaire.
     *
     * Pour usage dans n'importe quel pipeline de cross-provider backup
     * (= WebJsProvider, DessinAnimeProvider native, Cloudstream, etc.).
     */
    suspend fun resolveAndFetchServers(
        provider: Provider,
        match: Any,
        videoType: Video.Type,
        timeoutMs: Long = 15_000,
    ): List<Video.Server> {
        val resolvedId = resolveServerId(provider, match, videoType) ?: return emptyList()
        return try {
            withTimeoutOrNull(timeoutMs) { provider.getServers(resolvedId, videoType) } ?: emptyList()
        } catch (e: Exception) {
            android.util.Log.w(TAG, "[${provider.name}] getServers($resolvedId) failed: ${e.message}")
            emptyList()
        }
    }
}
