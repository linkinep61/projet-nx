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
            var full: TvShow = ficheMemo(provider, tvShowId, 45_000)
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
            val avaitWrappers = full.seasons.any { s -> s.id.contains("/@") }
            if (avaitWrappers) {
                val baseSlug = tvShowId.substringBefore("@")
                android.util.Log.d(TAG, "[${provider.name}] detected lang wrappers for $tvShowId " +
                    "(seasons=${full.seasons.map { "'${it.title}'" }}) → retrying with forced lang (target S$effectiveSeason)")
                var bestFallback: TvShow? = null
                for (lang in listOf("vf", "vostfr")) {
                    val unwrapped = try {
                        ficheMemo(provider, "$baseSlug@$lang", 30_000)
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
            // 2026-11-01 (Oppo, FRAnime 43078) : fiche PROPRE à cette saison → sa numérotation interne
            //   (« Saison 1, 2… » = les parties) ne correspond PAS à la nôtre : on part de sa 1ʳᵉ saison.
            val ficheDeSaison = effectiveSeason > 1 &&
                com.streamflixreborn.streamflix.utils.IndexOnyx.estFicheDeSaison(provider.name, tvShowId.substringBefore("@"))
            var targetSeason = if (ficheDeSaison) {
                (full.seasons.filter { it.number >= 1 }.minByOrNull { it.number } ?: full.seasons.singleOrNull()).also {
                    android.util.Log.i(TAG, "[${provider.name}] fiche propre à S$effectiveSeason → on lit sa saison '${it?.title}' " +
                        "(saisons : ${full.seasons.map { s -> "S${s.number}='${s.title}'" }})")
                }
            } else full.seasons.firstOrNull { it.number == effectiveSeason }
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
            // 2026-11-01 (user, Bleach Mille Ans) — la base a donné une fiche PROPRE à cette saison
            //   (FRAnime « Bleach: Sennen Kessen-hen » = TMDB saison 2) : sa 1ʳᵉ saison est la nôtre,
            //   les suivantes éventuelles en sont la continuation (cours).
            if (targetSeason == null) {
                android.util.Log.w(TAG, "[${provider.name}] no season S$effectiveSeason for $tvShowId " +
                    "(available: ${full.seasons.map { "S${it.number}='${it.title}'" }}) → SKIP (pas de fallback)")
                return null
            }
            val episodes = episodesMemo(provider, targetSeason.id) ?: emptyList()
            if (episodes.isEmpty()) {
                android.util.Log.w(TAG, "[${provider.name}] no episodes in season ${targetSeason.id}")
                return null
            }
            // Idem : pas de fallback sur le 1er épisode si le numéro ne matche pas.
            var ep = episodes.firstOrNull { it.number == originalEpisode }
            // 2026-11-01 (user, Bleach S2E47 : « animesama a déjà la série complète ») — saison TMDB
            //   découpée en PARTIES sur le site : TMDB S2 = 52 épisodes d'affilée, AnimeSama =
            //   « Thousand-Year Blood War Partie 1 » (13) puis « … Partie 2/3/4 » en saisons séparées.
            //   Si l'épisode dépasse la partie trouvée, on continue dans les parties SUIVANTES du même
            //   nom (base identique, seul le numéro de partie change) en décomptant les épisodes.
            //   Strict : uniquement des saisons qui se suivent et portent le même nom de base.
            if (ep == null && originalEpisode > (episodes.maxOfOrNull { it.number } ?: 0)) {
                ep = episodeDansPartiesSuivantes(provider, full, targetSeason, episodes, originalEpisode, tousLesSuivants = ficheDeSaison)
                // La VF s'arrête souvent avant la fin (doublage en retard) : on retente en VOSTFR.
                if (ep == null && avaitWrappers && !full.id.lowercase().endsWith("@vostfr")) {
                    val baseSlug = tvShowId.substringBefore("@")
                    val vostfr = try { ficheMemo(provider, "$baseSlug@vostfr", 30_000) } catch (_: Exception) { null }
                    val saisonVo = vostfr?.seasons?.firstOrNull { it.number == targetSeason.number && basePartie(it.title).first == basePartie(targetSeason.title).first }
                    if (vostfr != null && saisonVo != null) {
                        val epsVo = episodesMemo(provider, saisonVo.id) ?: emptyList()
                        android.util.Log.i(TAG, "[${provider.name}] VF trop courte → essai VOSTFR ('${saisonVo.title}', ${epsVo.size} ép.)")
                        ep = epsVo.firstOrNull { it.number == originalEpisode }
                            ?: if (epsVo.isNotEmpty()) episodeDansPartiesSuivantes(provider, vostfr, saisonVo, epsVo, originalEpisode) else null
                    }
                }
            }
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

    // 2026-11-01 (user, Bleach : « tous ceux où j'ai vraiment des serveurs ne vont même pas
    //   arriver ») — AnimeSama met > 25 s à monter une fiche (12 episodes.js) quand 40 sources
    //   tournent en même temps sur la télé, et on la remontait À CHAQUE ÉPISODE. Mémoire de
    //   15 min par source + id : l'épisode suivant se résout sans refaire le réseau.
    private const val MEMO_MS = 15 * 60_000L
    private val memoFiches = java.util.concurrent.ConcurrentHashMap<String, Pair<Long, TvShow>>()
    private val memoEpisodes = java.util.concurrent.ConcurrentHashMap<String, Pair<Long, List<com.streamflixreborn.streamflix.models.Episode>>>()

    private suspend fun ficheMemo(provider: Provider, id: String, delaiMs: Long): TvShow? {
        val cle = provider.name + "|" + id
        memoFiches[cle]?.let { (t, f) -> if (System.currentTimeMillis() - t < MEMO_MS) {
            android.util.Log.d(TAG, "[${provider.name}] fiche $id en mémoire"); return f } }
        val debut = System.currentTimeMillis()
        val f = withTimeoutOrNull(delaiMs) { provider.getTvShow(id) }
        android.util.Log.d(TAG, "[${provider.name}] fiche $id chargée en ${System.currentTimeMillis() - debut} ms" +
            if (f == null) " (échec)" else "")
        if (f != null && f.seasons.isNotEmpty()) memoFiches[cle] = System.currentTimeMillis() to f
        return f
    }

    private suspend fun episodesMemo(provider: Provider, saisonId: String): List<com.streamflixreborn.streamflix.models.Episode>? {
        val cle = provider.name + "|" + saisonId
        memoEpisodes[cle]?.let { (t, e) -> if (System.currentTimeMillis() - t < MEMO_MS) return e }
        val e = withTimeoutOrNull(20_000) { provider.getEpisodesBySeason(saisonId) }
        if (!e.isNullOrEmpty()) memoEpisodes[cle] = System.currentTimeMillis() to e
        return e
    }

    private val MARQUEUR_PARTIE = Regex("""(?i)\s*[-–:(]*\s*\b(?:partie|part|cour)\s*(\d+)\)?\s*$""")

    /** « Thousand-Year Blood War Partie 2 » → ("thousand year blood war", 2) ; sans marqueur → (nom, null). */
    private fun basePartie(titre: String?): Pair<String, Int?> {
        val t = titre.orEmpty()
        val m = MARQUEUR_PARTIE.find(t)
        val base = (if (m != null) t.substring(0, m.range.first) else t)
            .lowercase().replace(Regex("[^a-z0-9]+"), " ").trim()
        return base to m?.groupValues?.get(1)?.toIntOrNull()
    }

    private suspend fun episodeDansPartiesSuivantes(
        provider: Provider,
        full: TvShow,
        cible: com.streamflixreborn.streamflix.models.Season,
        episodesCible: List<com.streamflixreborn.streamflix.models.Episode>,
        numero: Int,
        /** Fiche propre à une saison TMDB : TOUTES ses saisons suivantes la continuent, quel que soit leur nom. */
        tousLesSuivants: Boolean = false,
    ): com.streamflixreborn.streamflix.models.Episode? {
        val (base, partieCible) = basePartie(cible.title)
        if (base.isBlank()) return null
        val idx = full.seasons.indexOfFirst { it.id == cible.id }
        if (idx < 0) return null
        // Saisons qui suivent immédiatement et portent le même nom de base avec un numéro de partie.
        val suivantes = if (tousLesSuivants) full.seasons.drop(idx + 1).filter { it.number >= 1 }
        else full.seasons.drop(idx + 1).takeWhile { s ->
            val (b, p) = basePartie(s.title)
            b == base && p != null
        }
        if (suivantes.isEmpty()) {
            android.util.Log.d(TAG, "[${provider.name}] E$numero > partie '${cible.title}', aucune partie suivante du même nom")
            return null
        }
        android.util.Log.i(TAG, "[${provider.name}] E$numero hors de '${cible.title}' (partie ${partieCible ?: 1}) → " +
            "parties suivantes : ${suivantes.map { "'${it.title}'" }}")
        var reste = numero - episodesCible.size
        for (s in suivantes) {
            val eps = episodesMemo(provider, s.id) ?: emptyList()
            android.util.Log.d(TAG, "[${provider.name}]   '${s.title}' (${s.id}) : ${eps.size} épisode(s) " +
                "${eps.map { it.number }.take(3)}…, reste à placer=$reste")
            if (eps.isEmpty()) return null
            // Numérotation continue sur le site (14, 15…) ou qui repart à 1 : on accepte les deux.
            if ((eps.minOfOrNull { it.number } ?: 1) > 1) eps.firstOrNull { it.number == numero }?.let {
                android.util.Log.i(TAG, "[${provider.name}] E$numero trouvé tel quel dans '${s.title}'")
                return it
            }
            if (reste <= eps.size) {
                val e = eps.firstOrNull { it.number == reste } ?: eps.getOrNull(reste - 1)
                android.util.Log.i(TAG, "[${provider.name}] E$numero → '${s.title}' épisode $reste " +
                    "(${e?.let { "id=${it.id}" } ?: "introuvable"})")
                return e
            }
            reste -= eps.size
        }
        android.util.Log.w(TAG, "[${provider.name}] E$numero au-delà de toutes les parties (${suivantes.size + 1})")
        return null
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
